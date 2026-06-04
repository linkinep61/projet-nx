package com.streamflixreborn.streamflix.utils

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 2026-06-01 : Proxy Cast v4 — URLs simples sans query params.
 * /cast.m3u8      → playlist réécrite
 * /0.m3u8         → sous-playlist réécrite
 * /1.ts /2.ts ... → segments binaires relayés en chunked
 *
 * Pas de ?url= — juste des chemins courts avec extensions.
 * Le mapping index→URL réelle est en mémoire.
 */
object CastProxy {

    private const val TAG = "CastProxy"
    private const val PORT = 8247

    private var server: ProxyServer? = null
    var currentUrl: String? = null; private set
    var currentHeaders: Map<String, String>? = null; private set
    var currentContentType: String? = null; private set

    private val urlMap = ConcurrentHashMap<Int, String>()
    private val counter = AtomicInteger(0)

    private val client: OkHttpClient by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(tm), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .build()
    }

    fun start(videoUrl: String, headers: Map<String, String>?, contentType: String?): String? {
        currentUrl = videoUrl
        currentHeaders = headers
        currentContentType = contentType
        urlMap.clear()
        counter.set(0)
        try {
            stop()
            val srv = ProxyServer(PORT)
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = srv
            val ip = getLocalIp() ?: return null
            Log.d(TAG, "Started: http://$ip:$PORT/cast.m3u8 → $videoUrl")
            return "http://$ip:$PORT/cast.m3u8"
        } catch (e: Exception) {
            Log.e(TAG, "Start failed: ${e.message}")
            return null
        }
    }

    fun stop() {
        try { server?.stop() } catch (_: Exception) {}
        server = null; urlMap.clear()
    }

    private fun register(realUrl: String): Int {
        urlMap.forEach { (k, v) -> if (v == realUrl) return k }
        val idx = counter.getAndIncrement()
        urlMap[idx] = realUrl
        return idx
    }

    private class ProxyServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: ""
            Log.d(TAG, ">>> $uri from ${session.remoteIpAddress}")
            return try {
                when {
                    uri == "/cast.m3u8" -> serveCast()
                    uri.endsWith(".m3u8") -> serveSubPlaylist(uri)
                    uri.endsWith(".ts") -> serveSegment(uri)
                    else -> cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "serve: ${e.message}")
                cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "Error"))
            }
        }

        private fun serveCast(): Response {
            val masterUrl = currentUrl ?: return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "No stream"))
            val base = masterUrl.substringBefore("?").substringBeforeLast("/") + "/"
            val ip = getLocalIp() ?: return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "No IP"))
            val proxyBase = "http://$ip:$PORT"

            val masterBytes = fetchBytes(masterUrl) ?: return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "Fetch failed"))
            val master = String(masterBytes, Charsets.UTF_8)

            if (!master.trimStart().startsWith("#EXTM3U")) {
                // MP4 direct
                return cors(newFixedLengthResponse(Response.Status.OK,
                    currentContentType ?: "video/mp4",
                    java.io.ByteArrayInputStream(masterBytes), masterBytes.size.toLong()))
            }

            // Variant → résoudre sous-playlist
            var playlist = master
            var playlistBase = base
            if (master.contains("#EXT-X-STREAM-INF")) {
                val subUrl = firstVariant(master, base)
                if (subUrl != null) {
                    Log.d(TAG, "Variant → $subUrl")
                    val sub = fetchBytes(subUrl)
                    if (sub != null) {
                        playlist = String(sub, Charsets.UTF_8)
                        playlistBase = subUrl.substringBefore("?").substringBeforeLast("/") + "/"
                    }
                }
            }

            val rewritten = rewrite(playlist, proxyBase, playlistBase)
            val segs = rewritten.lines().count { it.startsWith("http://") }
            Log.d(TAG, "/cast.m3u8: $segs segments")
            return cors(newFixedLengthResponse(Response.Status.OK, "application/x-mpegurl", rewritten))
        }

        private fun serveSubPlaylist(uri: String): Response {
            val idx = uri.removePrefix("/").removeSuffix(".m3u8").toIntOrNull()
                ?: return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "Bad idx"))
            val realUrl = urlMap[idx] ?: return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "Unknown"))
            val bytes = fetchBytes(realUrl) ?: return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "Fail"))
            val body = String(bytes, Charsets.UTF_8)
            if (body.trimStart().startsWith("#EXTM3U")) {
                val ip = getLocalIp() ?: return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "No IP"))
                val segBase = realUrl.substringBefore("?").substringBeforeLast("/") + "/"
                val rewritten = rewrite(body, "http://$ip:$PORT", segBase)
                return cors(newFixedLengthResponse(Response.Status.OK, "application/x-mpegurl", rewritten))
            }
            return cors(newFixedLengthResponse(Response.Status.OK, "application/x-mpegurl", body))
        }

        private fun serveSegment(uri: String): Response {
            val idx = uri.removePrefix("/").removeSuffix(".ts").toIntOrNull()
                ?: return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "Bad idx"))
            val realUrl = urlMap[idx] ?: return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "Unknown $idx"))

            Log.d(TAG, "/seg $idx → ${realUrl.take(60)}")
            try {
                val req = Request.Builder().url(realUrl)
                currentHeaders?.forEach { (k, v) -> req.addHeader(k, v) }
                if (currentHeaders?.keys?.any { it.equals("User-Agent", true) } != true)
                    req.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                val resp = client.newCall(req.build()).execute()
                val body = resp.body ?: return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "Empty"))
                val ct = resp.header("Content-Type") ?: "video/mp2t"
                val len = resp.header("Content-Length")?.toLongOrNull()
                val r = if (len != null && len > 0)
                    newFixedLengthResponse(Response.Status.OK, ct, body.byteStream(), len)
                else
                    newChunkedResponse(Response.Status.OK, ct, body.byteStream())
                return cors(r)
            } catch (e: Exception) {
                Log.w(TAG, "Seg $idx error: ${e.message}")
                return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "Error"))
            }
        }

        private fun fetchBytes(url: String): ByteArray? {
            return try {
                val req = Request.Builder().url(url)
                currentHeaders?.forEach { (k, v) -> req.addHeader(k, v) }
                if (currentHeaders?.keys?.any { it.equals("User-Agent", true) } != true)
                    req.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                val resp = client.newCall(req.build()).execute()
                val b = resp.body?.bytes(); resp.close(); b
            } catch (e: Exception) { Log.w(TAG, "fetch: ${e.message}"); null }
        }

        private fun cors(r: Response): Response {
            r.addHeader("Access-Control-Allow-Origin", "*")
            r.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
            r.addHeader("Access-Control-Allow-Headers", "*")
            return r
        }
    }

    /** Réécrit m3u8 : URLs → /N.ts ou /N.m3u8 (selon extension) */
    private fun rewrite(content: String, proxyBase: String, base: String): String {
        return content.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> ""
                t.startsWith("#") && t.contains("URI=\"") -> {
                    t.replace(Regex("URI=\"([^\"]+)\"")) {
                        val abs = resolve(it.groupValues[1], base)
                        val ext = if (abs.contains(".m3u8")) ".m3u8" else ".ts"
                        val idx = register(abs)
                        "URI=\"$proxyBase/$idx$ext\""
                    }
                }
                t.startsWith("#") -> t
                else -> {
                    val abs = resolve(t, base)
                    val ext = if (abs.substringBefore("?").endsWith(".m3u8")) ".m3u8" else ".ts"
                    val idx = register(abs)
                    "$proxyBase/$idx$ext"
                }
            }
        }
    }

    private fun firstVariant(master: String, base: String): String? {
        var next = false
        for (line in master.lines()) {
            val t = line.trim()
            if (t.startsWith("#EXT-X-STREAM-INF")) next = true
            else if (next && t.isNotBlank() && !t.startsWith("#")) return resolve(t, base)
        }
        return null
    }

    private fun resolve(url: String, base: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("/")) {
            val h = base.indexOf("/", 8)
            return if (h > 0) base.substring(0, h) + url else url
        }
        return base + url
    }

    private fun getLocalIp(): String? {
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val i = ifaces.nextElement()
                if (!i.isUp || i.isLoopback) continue
                val a = i.inetAddresses
                while (a.hasMoreElements()) {
                    val addr = a.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
