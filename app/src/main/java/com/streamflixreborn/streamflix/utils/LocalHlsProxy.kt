package com.streamflixreborn.streamflix.utils

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * 2026-06-12 (user "TF1 marche pas — SFR HTTP 400 IP mismatch") :
 *
 * Mini proxy HTTP local qui force TOUTES les requêtes HLS (master playlist
 * + sub-playlists + segments) à passer par notre OkHttpClient — donc par
 * la MÊME connection pool que le call u301.com qui a signé le JWT.
 *
 * Problème résolu : SFR vérifie que l'IP de la requête m3u8 = IP du JWT.
 * Sans proxy, ExoPlayer utilise CronetEngine (= autre stack réseau, autre
 * IP CF). Avec proxy, le call SFR sort par notre OkHttp = potentiellement
 * la même IP CF que u301.
 *
 * Le proxy intercepte les playlists HLS et réécrit toutes les URLs des
 * segments pour qu'elles passent aussi par le proxy → l'app SFR voit
 * une suite cohérente de requêtes depuis la même IP.
 *
 * Générique : marche pour n'importe quelle URL HLS, pas que SFR.
 */
object LocalHlsProxy {

    private const val TAG = "LocalHlsProxy"
    @Volatile private var server: ServerSocket? = null
    @Volatile private var port: Int = 0
    private val executor = Executors.newCachedThreadPool()

    // 2026-06-12 — CookieJar partagé pour persister les cookies entre les
    //   différents calls (u301 → m3u8 → segments). Certains CDN (SFR) posent
    //   un cookie session lors du call signé qu il faut retransmettre pour
    //   les segments. Sans cookie → 400/403.
    private val cookieStore = mutableMapOf<String, MutableList<okhttp3.Cookie>>()
    private val cookieJar = object : okhttp3.CookieJar {
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            synchronized(cookieStore) {
                val host = url.host
                cookieStore.getOrPut(host) { mutableListOf() }.apply {
                    // Évite les doublons par nom
                    cookies.forEach { c ->
                        removeAll { it.name == c.name }
                        add(c)
                    }
                }
            }
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            synchronized(cookieStore) {
                // Cookies du host courant + parents (= .pfd.sfr.net etc.)
                val host = url.host
                val matches = mutableListOf<okhttp3.Cookie>()
                cookieStore.forEach { (k, v) ->
                    if (host == k || host.endsWith(".$k")) matches.addAll(v)
                }
                return matches
            }
        }
    }

    /** Expose le cookieJar pour partage avec les autres clients OkHttp
     *  de l app (u301, bypassFranceTvSfr, etc.). */
    fun getCookieJar(): okhttp3.CookieJar = cookieJar

    // Client partagé avec les calls API (u301, etc.) → même connection pool,
    // potentiellement même IP CF sortante. Persiste les cookies.
    private val sharedClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, t: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, t: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), java.security.SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .build()
    }

    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return port }
        val s = ServerSocket(0)
        port = s.localPort
        server = s
        Log.d(TAG, "Started on port $port")
        executor.submit { serveLoop(s) }
        return port
    }

    private fun serveLoop(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                val client = s.accept()
                executor.submit { handleRequest(client) }
            } catch (_: Throwable) {
                if (s.isClosed) break
            }
        }
    }

    private fun handleRequest(socket: Socket) {
        socket.use { sock ->
            try {
                val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                val requestLine = reader.readLine() ?: return
                // Parse les autres headers (Range etc.)
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim()] =
                        line.substring(idx + 1).trim()
                }
                val match = Regex("""GET\s+/\?u=([^\s]+).*""").find(requestLine) ?: run {
                    writeError(sock, 400, "Bad Request")
                    return
                }
                val encoded = match.groupValues[1]
                val targetUrl = try {
                    String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP),
                        Charsets.UTF_8)
                } catch (_: Throwable) {
                    writeError(sock, 400, "Bad Encoding"); return
                }
                forward(targetUrl, headers, sock)
            } catch (e: Throwable) {
                Log.w(TAG, "handleRequest error: ${e.message}")
            }
        }
    }

    private fun forward(targetUrl: String, clientHeaders: Map<String, String>, sock: Socket) {
        try {
            val reqBuilder = Request.Builder().url(targetUrl)
            // Forward Range si demandé par ExoPlayer (pour segments TS)
            clientHeaders["Range"]?.let { reqBuilder.header("Range", it) }
            // User-Agent simple FR
            reqBuilder.header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            )
            // 2026-06-12 : pour les hosts SFR, on essaye d injecter l IP du
            //   JWT via X-Forwarded-For. Si SFR respecte ce header pour
            //   determiner l IP source du client, l IP signee dans le JWT
            //   matchera l IP que SFR voit = no mismatch = no reject.
            //   Ajout aussi Origin, X-Real-IP, et CF-Connecting-IP qui sont
            //   les conventions communes des CDN derriere CloudFlare.
            if (targetUrl.contains("pfd.sfr.net")) {
                val jwtIp = extractIpFromJwtUrl(targetUrl)
                reqBuilder.header("Referer", "https://www.molotov.tv/")
                reqBuilder.header("Origin", "https://www.molotov.tv")
                if (jwtIp != null) {
                    reqBuilder.header("X-Forwarded-For", jwtIp)
                    reqBuilder.header("X-Real-IP", jwtIp)
                    reqBuilder.header("CF-Connecting-IP", jwtIp)
                    Log.d(TAG, "SFR call with X-Forwarded-For=$jwtIp (from JWT)")
                }
            }
            val resp = sharedClient.newCall(reqBuilder.build()).execute()
            val contentType = resp.header("Content-Type", "application/octet-stream")
                ?: "application/octet-stream"
            val bodyBytes = resp.body?.bytes() ?: byteArrayOf()
            resp.close()

            // Si playlist HLS, on réécrit les URLs pour qu'elles passent par
            // le proxy aussi (= chain reaction : master → sub → segments).
            val isPlaylist = contentType.contains("mpegurl", ignoreCase = true) ||
                targetUrl.contains(".m3u8")
            val outBytes = if (isPlaylist) rewriteM3u8(bodyBytes, targetUrl) else bodyBytes

            val out = sock.getOutputStream()
            out.write("HTTP/1.1 ${resp.code} OK\r\n".toByteArray())
            out.write("Content-Type: $contentType\r\n".toByteArray())
            out.write("Content-Length: ${outBytes.size}\r\n".toByteArray())
            out.write("Cache-Control: no-cache\r\n".toByteArray())
            out.write("Connection: close\r\n".toByteArray())
            out.write("\r\n".toByteArray())
            out.write(outBytes)
            out.flush()
            Log.d(TAG, "Forwarded ${targetUrl.take(80)} → ${resp.code}, ${outBytes.size}B" +
                    if (isPlaylist) " (m3u8 rewritten)" else "")
            // 2026-06-12 : si erreur, log le body pour comprendre ce que dit le
            //   serveur (= souvent un JSON/XML avec un code d erreur précis).
            if (resp.code >= 400 && bodyBytes.isNotEmpty()) {
                val errorBody = String(bodyBytes, Charsets.UTF_8).take(500)
                Log.w(TAG, "Error body for ${targetUrl.take(80)}: $errorBody")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "forward error for ${targetUrl.take(80)}: ${e.message}")
            try { writeError(sock, 502, "Bad Gateway") } catch (_: Throwable) {}
        }
    }

    private fun rewriteM3u8(body: ByteArray, originalUrl: String): ByteArray {
        val text = String(body, Charsets.UTF_8)
        val baseUrl = try { URL(originalUrl) } catch (_: Throwable) { return body }
        val sb = StringBuilder(text.length + 256)
        for (line in text.lineSequence()) {
            val t = line.trim()
            when {
                t.isEmpty() || t.startsWith("#") -> sb.appendLine(line)
                t.startsWith("http://") || t.startsWith("https://") -> {
                    sb.appendLine(wrapUrl(t))
                }
                else -> {
                    // URL relative — résoudre vers la base
                    val absolute = try { URL(baseUrl, t).toString() } catch (_: Throwable) { t }
                    sb.appendLine(wrapUrl(absolute))
                }
            }
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Wrap une URL pour qu'elle passe par le proxy local. */
    fun wrapUrl(targetUrl: String): String {
        if (server == null) start()
        val encoded = Base64.encodeToString(
            targetUrl.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "http://127.0.0.1:$port/?u=$encoded"
    }

    /** Extrait l IP signee dans le payload JWT d une URL SFR.
     *  Le JWT est dans le path apres `sid=`. Payload base64-encoded contient
     *  `"ip":"X.X.X.X"`. On le décode et extrait cette IP. */
    private fun extractIpFromJwtUrl(url: String): String? {
        return try {
            val sidStart = url.indexOf("sid=")
            if (sidStart < 0) return null
            val jwt = url.substring(sidStart + 4).substringBefore('/')
            val parts = jwt.split('.')
            if (parts.size < 2) return null
            val payloadB64 = parts[1]
            // JWT utilise base64url sans padding — ajoute padding si nécessaire
            val padded = when (payloadB64.length % 4) {
                2 -> "$payloadB64=="
                3 -> "$payloadB64="
                else -> payloadB64
            }.replace('-', '+').replace('_', '/')
            val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            // ex: {"sub":"TF1","iss":"...","exp":1781326607,"ip":"104.22.102.2",...}
            Regex(""""ip"\s*:\s*"([0-9.]+)"""").find(decoded)?.groupValues?.get(1)
        } catch (_: Throwable) { null }
    }

    private fun writeError(sock: Socket, code: Int, msg: String) {
        val out = sock.getOutputStream()
        out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }
}
