package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class ApiVoirFilmExtractor : Extractor() {
    override val name = "ApiVoirFilm"
    override val mainUrl = "https://api.voirfilm.cam"
    override val aliasUrls = listOf("https://api.voirfilm.")

    // Reliability ranking: lower = better
    private val reliabilityOrder = mapOf(
        "Vidara" to 1,
        "Vidsonic" to 2,
        "Rpmvid" to 3,
        "StreamWish" to 4,
        "Streamix" to 4,
        "Filemoon" to 10,
    )
    private val defaultReliability = 5

    suspend fun expand(link: String, referer: String = mainUrl, suffix: String = ""): List<Video.Server> {
        val service = Extractor.createJsoupService<Service>(mainUrl)
        val doc = service.get(link, referer)

        val links = doc.select("div.top ul.content > li").mapIndexedNotNull { idx, item ->
            val url = item.attr("data-url")
            if (url.isEmpty()) return@mapIndexedNotNull null
            val originalTitle = item.text() ?: "Server$idx"
            val serviceName = Extractor.identifyServiceName(url)
            val title = if (serviceName != null) {
                "$suffix$serviceName"
            } else {
                "$suffix$originalTitle"
            }
            Video.Server(
                "${name}_${idx}",
                name = title,
                src = url
            )
        }

        // Sort by reliability
        return links.sortedBy { server ->
            val serviceName = Extractor.identifyServiceName(server.src)
            reliabilityOrder[serviceName] ?: defaultReliability
        }
    }

    override suspend fun extract(link: String): Video {
        throw Exception("None")
    }

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("User-agent") useragent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        ): Document
    }
}