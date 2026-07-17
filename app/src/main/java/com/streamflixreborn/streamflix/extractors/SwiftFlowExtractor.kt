package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 2026-07-16 — Player "SwiftFlow" de Movix (swiftflow.lol).
 *
 * Découvert en direct dans le Chrome du user (L'Odyssée / tmdb 1368337). Movix expose un
 * endpoint dédié `api/swiftflow/movie/{tmdbId}` (parsé par MovixProvider) dont chaque player
 * est une iframe :
 *   `https://swiftflow.lol/api/v1/index.php?route=movies/{tmdbId}/player&api_key=<key>`
 *
 * La PAGE /player (HTML) est protégée par un Cloudflare Turnstile (« Vérification rapide »)
 * + un ad-gate → jouer ça dans une WebView reste bloqué après « Succès ! ». MAIS l'API
 * sous-jacente N'EST PAS gardée (testé cold, credentials omis → 200 JSON, pas de challenge) :
 *
 *   1. `route=movies/{id}/stream` (au lieu de /player) → JSON
 *      `{success:true, data:{sources:[{url:"https://sv.citron-edge.lol/movies/<f>.mp4", type:"mp4"}]}}`
 *      L'URL citron est SANS signature → 503 telle quelle.
 *   2. `cheksum.lol/api/video_proxy.php?file=<path>&cdn=<cdnHost>` (Referer = swiftflow.lol,
 *      anti-hotlink : sans Referer → redirige vers un Telegram) → redirige (302) vers l'URL
 *      SIGNÉE `…mp4?ff=<expiry>.<sig>`.
 *   3. On joue l'URL signée. `sv.citron-edge.lol` fait du JA3/TLS fingerprinting strict →
 *      needsCronet() (PlayerMobile/Tv) le sait DÉJÀ (même CDN que Nakios) → le player bascule
 *      sur Cronet (TLS Chrome) + Referer → 206 OK. Pas de WebView, pas de Turnstile.
 *
 * On fait les 2 GET (/stream + cheksum) via **Cronet** (JA3 Chrome) car swiftflow.lol/cheksum.lol
 * sont derrière Cloudflare agressif (OkHttp/Conscrypt = souvent bloqué). Fallback OkHttp si Cronet
 * indisponible (ex: Chromecast sans Play Services).
 *
 * FILMS UNIQUEMENT : les routes TV de swiftflow.lol renvoient 403 (« TV partenaires »).
 */
class SwiftFlowExtractor : Extractor() {

    override val name = "SwiftFlow"
    override val mainUrl = "https://swiftflow.lol"

    override val cacheTtlMs: Long = 0L

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val uri = URL(link)
        val base = "${uri.protocol}://${uri.host}"                 // https://swiftflow.lol
        val query = uri.query ?: throw Exception("SwiftFlow: no query in $link")
        val params = query.split("&").mapNotNull {
            val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val route = params["route"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: throw Exception("SwiftFlow: no route param")
        val apiKey = params["api_key"] ?: throw Exception("SwiftFlow: no api_key param")

        // route "movies/{id}/player" → "movies/{id}/stream"
        val streamRoute = if (route.endsWith("/player")) route.dropLast(7) + "/stream" else "$route/stream"
        val streamUrl = "$base/api/v1/index.php?route=$streamRoute&api_key=$apiKey"

        // 1) /stream → URL citron (non signée)
        val streamBody = httpGet(streamUrl, referer = "$base/", followRedirects = true)?.second
            ?: throw Exception("SwiftFlow: /stream fetch failed")
        val citronUrl = try {
            JSONObject(streamBody).getJSONObject("data").getJSONArray("sources")
                .getJSONObject(0).getString("url")
        } catch (e: Exception) {
            throw Exception("SwiftFlow: no source in /stream: ${streamBody.take(120)}")
        }
        val citron = URL(citronUrl)
        val cdnHost = citron.host                                  // sv.citron-edge.lol
        val filePath = citron.path                                 // /movies/<file>.mp4

        // 2) cheksum → URL signée (302 Location ou dans le body). Referer swiftflow.lol OBLIGATOIRE.
        val proxyUrl = "https://cheksum.lol/api/video_proxy.php?file=$filePath&cdn=$cdnHost"
        val (proxyCode, proxyBody, proxyLocation) = httpGetFull(
            proxyUrl, referer = "$base/", followRedirects = false
        ) ?: throw Exception("SwiftFlow: cheksum fetch failed")

        val signedUrl = when {
            proxyLocation != null && proxyLocation.contains("ff=") -> proxyLocation
            proxyCode in 200..299 && proxyBody != null -> {
                Regex("""https?://[^\s"'<>\\]*citron[^\s"'<>\\]*ff=[^\s"'<>\\]+""")
                    .find(proxyBody)?.value
                    ?: throw Exception("SwiftFlow: no signed url in cheksum body (code=$proxyCode)")
            }
            // le proxy peut aussi rediriger directement vers le mp4 sur un autre CDN
            proxyLocation != null && (proxyLocation.contains(".mp4") || proxyLocation.contains("citron")) -> proxyLocation
            else -> throw Exception("SwiftFlow: cheksum no signed url (code=$proxyCode, loc=${proxyLocation?.take(40)})")
        }

        Log.d(TAG, "SwiftFlow resolved signed mp4 (${signedUrl.take(60)}…)")

        Video(
            source = signedUrl,
            type = MimeTypes.VIDEO_MP4,
            headers = mapOf(
                "User-Agent" to WebViewResolver.STEALTH_UA,
                "Referer" to "$base/",
            ),
        )
    }

    /** GET renvoyant (code, body) — Cronet d'abord (JA3 Chrome), fallback OkHttp. */
    private fun httpGet(url: String, referer: String, followRedirects: Boolean): Pair<Int, String>? {
        val full = httpGetFull(url, referer, followRedirects) ?: return null
        return full.first to (full.second ?: "")
    }

    /** GET complet : (code, body?, Location?). Cronet d'abord, fallback OkHttp. */
    private fun httpGetFull(
        url: String, referer: String, followRedirects: Boolean
    ): Triple<Int, String?, String?>? {
        // ── Cronet (TLS Chrome = bypass JA3/Cloudflare) ──
        try {
            val engine = StreamFlixApp.getCronetEngine(StreamFlixApp.instance.applicationContext)
            if (engine != null) {
                val openConnection = engine.javaClass.getMethod("openConnection", URL::class.java)
                val conn = openConnection.invoke(engine, URL(url)) as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = followRedirects
                    conn.setRequestProperty("User-Agent", WebViewResolver.STEALTH_UA)
                    conn.setRequestProperty("Referer", referer)
                    conn.setRequestProperty("Accept", "*/*")
                    conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                    conn.connectTimeout = 8000
                    conn.readTimeout = 12000
                    val code = conn.responseCode
                    val location = conn.getHeaderField("Location")
                    val body = try {
                        (if (code in 200..299) conn.inputStream else conn.errorStream)
                            ?.bufferedReader()?.use { it.readText() }
                    } catch (_: Exception) { null }
                    Log.d(TAG, "Cronet GET $code ${url.take(60)}")
                    return Triple(code, body, location)
                } finally {
                    try { conn.disconnect() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cronet GET failed (${e.message}), fallback OkHttp")
        }

        // ── Fallback OkHttp ──
        return try {
            val client = if (followRedirects) Extractor.sharedClient
                else Extractor.sharedClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val req = Request.Builder().url(url)
                .header("User-Agent", WebViewResolver.STEALTH_UA)
                .header("Referer", referer)
                .header("Accept", "*/*")
                .build()
            client.newCall(req).execute().use { resp ->
                val loc = resp.header("Location")
                val body = try { resp.body?.string() } catch (_: Exception) { null }
                Triple(resp.code, body, loc)
            }
        } catch (e: Exception) {
            Log.w(TAG, "OkHttp GET failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SwiftFlowExtractor"
    }
}
