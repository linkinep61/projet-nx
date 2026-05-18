package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

/**
 * VK.com video embed extractor.
 *
 * Format URL : `https://vk.com/video_ext.php?oid=<oid>&id=<id>&hash=<hash>` ou
 * variantes (`vk.ru`, `vkvideo.ru`, `m.vk.com`). La page contient un object JS
 * `var playerParams = {...}` avec une propriété `params` qui liste les sources
 * vidéo par qualité (url240, url360, url480, url720, url1080, hls).
 *
 * 2026-05-16 : créé pour supporter FranimeProvider (vk + vkru = 5232
 * occurrences dans le catalogue franime.fr, après les top 4
 * sibnet/sendvid/vidmoly/filemoon).
 */
class VkExtractor : Extractor() {
    override val name = "VK"
    override val mainUrl = "https://vk.com"
    override val aliasUrls = listOf(
        "https://vk.ru",
        "https://vkvideo.ru",
        "https://m.vk.com",
        "https://m.vk.ru",
    )

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract($link)")
        // Normaliser m.vk → vk pour matcher le format du player
        val target = link
            .replace("m.vk.com", "vk.com")
            .replace("m.vk.ru", "vk.ru")
        val service = Extractor.createJsoupService<Service>(mainUrl, mainUrl)
        val document = service.get(
            url = target,
            referer = "https://vk.com/",
            userAgent = ANDROID_CHROME_UA,
        )
        val html = document.toString()

        // Pattern principal : "url720":"https://..." (ou url480/url360/etc.).
        // Préfère HLS si présent, sinon descend par qualité.
        val hls = Regex(""""hls":"([^"]+\.m3u8[^"]*)"""")
            .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        if (hls != null) {
            Log.d(TAG, "HLS found: $hls")
            return Video(
                source = hls,
                headers = mapOf(
                    "Referer" to "https://vk.com/",
                    "User-Agent" to ANDROID_CHROME_UA,
                ),
            )
        }

        // Sinon, choisir le MP4 de meilleure qualité disponible
        val qualities = listOf("url1080", "url720", "url480", "url360", "url240")
        for (q in qualities) {
            val m = Regex("""\"$q\":\"([^\"]+)\"""").find(html)
            if (m != null) {
                val url = m.groupValues[1].replace("\\/", "/")
                Log.d(TAG, "Found $q: $url")
                return Video(
                    source = url,
                    headers = mapOf(
                        "Referer" to "https://vk.com/",
                        "User-Agent" to ANDROID_CHROME_UA,
                    ),
                )
            }
        }

        // Fallback : chercher cache_url pattern (legacy)
        val cacheUrl = Regex(""""cache_url":"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
        if (cacheUrl != null) {
            Log.d(TAG, "cache_url fallback: $cacheUrl")
            return Video(
                source = cacheUrl,
                headers = mapOf(
                    "Referer" to "https://vk.com/",
                    "User-Agent" to ANDROID_CHROME_UA,
                ),
            )
        }

        throw Exception("VK: no playable source found in embed page")
    }

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("User-Agent") userAgent: String,
            @Header("Accept-Language") lang: String = "fr-FR,fr;q=0.9,en;q=0.8",
        ): Document
    }

    companion object {
        private const val TAG = "VkExtractor"
    }
}
