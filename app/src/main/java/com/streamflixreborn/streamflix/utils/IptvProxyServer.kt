package com.streamflixreborn.streamflix.utils

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

/**
 * 2026-06-14 (user "on doit faire en sorte que voir le live soit capable de
 *   lire n'importe quoi comme mon IPTV") : proxy HTTP local pour respecter
 *   STRICTEMENT les headers (User-Agent, Referer, custom) que le M3U définit
 *   par chaîne.
 *
 *   Sans ce proxy, Streamflix utilise CronetDataSource (= TLS Chrome BoringSSL
 *   pour contourner le fingerprinting Conscrypt sur Vavoo) avec un UA hardcoded
 *   "MediaHubMX/2". Le `perVideoUA` du M3U est appliqué seulement au pipeline
 *   OkHttp qui n'est plus utilisé en prod → les UA custom du M3U sont ignorés
 *   → certains serveurs (ex: prime-tv anti-leech) renvoient du HTML au lieu du
 *   stream → ExoPlayer crash avec UnrecognizedInputFormatException.
 *
 *   Architecture :
 *    1. `wrap(url, ua, referer, extraHeaders)` retourne une URL locale
 *       `http://127.0.0.1:<port>/iptv/<token>.<ext>`.
 *    2. ExoPlayer fetch cette URL locale (= aucun changement côté pipeline
 *       Cronet/MiniPlayerController générique).
 *    3. Le proxy intercepte, regarde le `token`, fait la VRAIE requête vers
 *       l'upstream avec les headers strictement comme demandé par le M3U.
 *    4. La réponse upstream (binaire MPEG-TS / HLS playlist / etc.) est
 *       streamée chunked à ExoPlayer.
 *
 *   Cible : World Live (provider IPTV générique avec M3U externe). Les autres
 *   providers (Vavoo/OLA/Vegeta/OTF/Movix) ne sont pas affectés — leur pipeline
 *   reste intact.
 *
 *   Performance : OkHttp pool + streaming chunked. Le proxy ne buffer pas tout
 *   le flux, il forward au fur et à mesure. CPU/mémoire négligeable.
 */
object IptvProxyServer {

    private const val TAG = "IptvProxyServer"

    @Volatile private var server: ProxyServer? = null
    @Volatile private var port: Int = 0

    private val streamMap = ConcurrentHashMap<String, StreamConfig>()
    private val counter = AtomicLong(0)

    data class StreamConfig(
        val url: String,
        val userAgent: String?,
        val referer: String?,
        val extraHeaders: Map<String, String> = emptyMap(),
    )

    /** Client OkHttp dédié : trust-all SSL (certains hosts IPTV ont des certs
     *  bizarres), Connection close (= comportement app IPTV typique), redirects
     *  suivis pour les URLs 302 → load balancer. */
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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Wrap une URL stream + headers du M3U → URL locale qui forwardera avec
     *  EXACTEMENT ces headers. Si url est déjà une URL locale ou si headers vides,
     *  retourne `url` tel quel (pas de surcoût). */
    fun wrap(url: String, ua: String?, referer: String?, extraHeaders: Map<String, String> = emptyMap()): String {
        if (url.isBlank()) return url
        if (url.contains("127.0.0.1") || url.contains("localhost")) return url
        if (ua.isNullOrBlank() && referer.isNullOrBlank() && extraHeaders.isEmpty()) return url
        try {
            val p = ensureStarted()
            val token = generateToken()
            streamMap[token] = StreamConfig(url, ua?.takeIf { it.isNotBlank() }, referer?.takeIf { it.isNotBlank() }, extraHeaders)
            // Préserve l'extension pour aider ExoPlayer à choisir l'extractor.
            val ext = when {
                url.contains(".m3u8", ignoreCase = true) -> "m3u8"
                url.contains(".mpd", ignoreCase = true) -> "mpd"
                url.contains(".mp4", ignoreCase = true) -> "mp4"
                else -> "ts"
            }
            return "http://127.0.0.1:$p/iptv/$token.$ext"
        } catch (e: Throwable) {
            Log.w(TAG, "wrap failed (${e.message}), fallback to direct URL")
            return url
        }
    }

    /** Idempotent : démarre le proxy nanohttpd sur un port libre (= pas de
     *  conflit avec CastProxy:8247). Retourne le port en écoute. */
    private fun ensureStarted(): Int {
        server?.let { return port }
        synchronized(this) {
            server?.let { return port }
            val srv = ProxyServer()
            // Port 0 = OS choisit libre. SOCKET_READ_TIMEOUT = 5s par défaut.
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, /*daemon=*/true)
            server = srv
            port = srv.listeningPort
            Log.d(TAG, "Started on 127.0.0.1:$port")
        }
        return port
    }

    private fun generateToken(): String {
        val n = counter.incrementAndGet()
        val rand = Random.nextLong(0, Long.MAX_VALUE).toString(36)
        return "${n.toString(36)}-$rand"
    }

    private class ProxyServer : NanoHTTPD(0) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            // Format attendu : /iptv/<token>.<ext>
            val match = Regex("""^/iptv/([^./]+)\.\w+$""").matchEntire(uri)
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad URI")
            val token = match.groupValues[1]
            val cfg = streamMap[token]
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Token expired")

            return try {
                forward(cfg, session)
            } catch (e: Throwable) {
                Log.w(TAG, "forward failed for ${cfg.url}: ${e.message}")
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Upstream error: ${e.message}")
            }
        }

        private fun forward(cfg: StreamConfig, session: IHTTPSession): Response {
            val req = Request.Builder().url(cfg.url)

            // Headers from M3U (= comportement IPTV standard)
            if (cfg.userAgent != null) {
                req.header("User-Agent", cfg.userAgent)
            }
            if (cfg.referer != null) {
                req.header("Referer", cfg.referer)
            }
            cfg.extraHeaders.forEach { (k, v) -> req.header(k, v) }

            // Connection: close — beaucoup de serveurs IPTV anti-leech ne supportent
            // pas keep-alive correctement (= prime-tv app utilise ce header)
            req.header("Connection", "close")
            // Accept large pour passer la plupart des filtres
            if (cfg.extraHeaders["Accept"] == null) {
                req.header("Accept", "*/*")
            }

            // Transmet le Range header si le client (= ExoPlayer) l'a demandé.
            session.headers["range"]?.let { req.header("Range", it) }

            val resp = client.newCall(req.build()).execute()

            val status = when (resp.code) {
                200 -> Response.Status.OK
                206 -> Response.Status.PARTIAL_CONTENT
                301, 302, 307, 308 -> Response.Status.REDIRECT
                404 -> Response.Status.NOT_FOUND
                403 -> Response.Status.FORBIDDEN
                else -> Response.Status.lookup(resp.code) ?: Response.Status.INTERNAL_ERROR
            }
            val contentType = resp.header("Content-Type") ?: guessContentType(cfg.url)

            val body = resp.body
            if (body == null) {
                resp.close()
                return newFixedLengthResponse(status, contentType, "")
            }

            // Stream chunked à ExoPlayer (pas de buffer mémoire)
            val out = newChunkedResponse(status, contentType, body.byteStream())
            // Propage les headers utiles (Content-Length pour les segments TS,
            // Content-Range pour les seek/buffer ExoPlayer).
            resp.header("Content-Length")?.let { out.addHeader("Content-Length", it) }
            resp.header("Content-Range")?.let { out.addHeader("Content-Range", it) }
            resp.header("Accept-Ranges")?.let { out.addHeader("Accept-Ranges", it) }
            return out
        }

        private fun guessContentType(url: String): String {
            val lower = url.lowercase().substringBefore('?')
            return when {
                lower.contains(".m3u8") -> "application/vnd.apple.mpegurl"
                lower.contains(".mpd") -> "application/dash+xml"
                lower.endsWith(".ts") -> "video/mp2t"
                lower.endsWith(".mp4") -> "video/mp4"
                lower.endsWith(".aac") -> "audio/aac"
                lower.endsWith(".mp3") -> "audio/mpeg"
                else -> "application/octet-stream"
            }
        }
    }
}
