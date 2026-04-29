package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url

class VidozaExtractor : Extractor() {

    override val name = "Vidoza"

    override val mainUrl = "https://vidoza.net"
    override val aliasUrls = listOf<String>("https://videzz.net")

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<VidozaService>(mainUrl)
        val source = service.getSource(link.replace(mainUrl, ""))
        val videoUrl = source.select("source").attr("src")
        return Video(
            source = videoUrl,
            subtitles = listOf()
        )
    }


    private interface VidozaService {
        @GET
        suspend fun getSource(@Url url: String): Document
    }
}