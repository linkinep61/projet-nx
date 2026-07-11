package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Stream4FreeResolver {
    private const val TAG = "Stream4Free"
    private const val BASE_URL = "https://www.stream4free.tv"
    private const val CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val M3U8_REGEX = Regex("""https?://[a-z0-9]+\.data-stream\.top/[a-f0-9]+/hls/[^"'\s<>]+\.m3u8""")

    // 2026-07-10 (user "localiser d'où viennent vraiment les flux au lieu de scraper") : les flux
    //   viennent de `data-stream.top` (sv1..sv7) via une URL `…/{token64}/hls/{chaîne}.m3u8`, et le
    //   TOKEN est STABLE par chaîne (vérifié en direct : identique après reload). On CACHE donc
    //   l'URL directe par chaîne : on ne scrape la page stream4free QU'UNE FOIS, puis on rejoue
    //   data-stream.top directement (zapping instantané, et resilient si la page est momentanément
    //   down). TTL long (6 h) ; si l'URL cachée finit par tomber (token/serveur qui tourne), le
    //   player échoue → on invalide et on re-scrape au prochain essai.
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    private val urlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** Invalide le cache d'une chaîne (à appeler si sa lecture directe échoue → force re-scrape). */
    fun invalidate(slug: String) { urlCache.remove(slug.removePrefix("stream4free://").trim()) }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun isStream4FreeUrl(url: String): Boolean {
        return url.startsWith("stream4free://")
    }

    suspend fun resolve(src: String): Video? = withContext(Dispatchers.IO) {
        val slug = src.removePrefix("stream4free://").trim()
        if (slug.isEmpty()) {
            Log.w(TAG, "resolve: slug vide")
            return@withContext null
        }

        // Cache-hit : token stable → on rejoue l'URL directe data-stream.top sans re-scraper la page.
        urlCache[slug]?.let { (cachedUrl, ts) ->
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                Log.d(TAG, "resolve CACHE-HIT: $slug → $cachedUrl (direct data-stream.top, pas de scrape)")
                return@withContext videoOf(cachedUrl)
            }
        }

        val pageUrl = "$BASE_URL/$slug"
        Log.d(TAG, "resolve: fetching $pageUrl")

        try {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.5")
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "resolve: HTTP ${response.code} for $pageUrl")
                    return@withContext null
                }

                val html = response.body?.string() ?: ""
                if (html.isBlank()) {
                    Log.w(TAG, "resolve: empty body for $pageUrl")
                    return@withContext null
                }

                val m3u8Url = M3U8_REGEX.find(html)?.value
                if (m3u8Url == null) {
                    Log.w(TAG, "resolve: no m3u8 URL found in page $slug")
                    return@withContext null
                }

                Log.d(TAG, "resolve OK: $slug → $m3u8Url")
                urlCache[slug] = m3u8Url to System.currentTimeMillis()
                videoOf(m3u8Url)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "resolve failed for $slug: ${e.message}")
            null
        }
    }

    /** Construit le Video direct data-stream.top (Referer/Origin stream4free obligatoires). */
    private fun videoOf(m3u8Url: String): Video = Video(
        source = m3u8Url,
        headers = mapOf(
            "User-Agent" to Extractor.DEFAULT_USER_AGENT,
            "Referer" to "$BASE_URL/",
            "Origin" to BASE_URL,
        ),
        type = "application/vnd.apple.mpegurl",
    )
}
