package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url

class OkruExtractor : Extractor() {

    override val name = "Okru"
    override val mainUrl = "https://ok.ru"

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<Service>(mainUrl)
        val document = service.get(link)

        val videoString = document.selectFirst("div[data-options]")
            ?.attr("data-options")
            ?: throw Exception("No se encontró 'data-options' en la página de Ok.ru")

        val arrayData = videoString.substringAfterLast("\\\"videos\\\":[{\\\"name\\\":\\\"").substringBefore("]")
        val videos = arrayData.split("{\\\"name\\\":\\\"").reversed().mapNotNull {
            val videoUrl = it.substringAfter("url\\\":\\\"").substringBefore("\\\"").replace("\\\\u0026", "&")
            val quality = fixQuality(it.substringBefore("\\\""))

            if (videoUrl.startsWith("https://")) {
                Pair(quality, videoUrl)
            } else {
                null
            }
        }

        if (videos.isEmpty()) {
            throw Exception("No se encontraron videos válidos en el JSON de Ok.ru")
        }

        val bestVideoUrl = videos.first().second

        return Video(
            source = bestVideoUrl,
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
            )
        )
    }

    private fun fixQuality(quality: String): String {
        return when (quality) {
            "ultra" -> "2160p"
            "quad" -> "1440p"
            "full" -> "1080p"
            "hd" -> "720p"
            "sd" -> "480p"
            "low" -> "360p"
            "lowest" -> "240p"
            "mobile" -> "144p"
            else -> quality
        }
    }

    private interface Service {
        @GET
        suspend fun get(@Url url: String): Document
    }
}