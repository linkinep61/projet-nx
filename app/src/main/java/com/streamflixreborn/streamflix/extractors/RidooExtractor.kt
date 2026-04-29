package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url

class RidooExtractor : Extractor() {

    override val name = "Ridoo"
    override val mainUrl = "https://ridoo.net"

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<Service>(mainUrl)
        val document = service.get(link)

        val regex = Regex("""file\s*:\s*"([^"]+\.m3u8[^"]*)"""")
        val match = regex.find(document.toString())
        val m3u8Url = match?.groups?.get(1)?.value
            ?: throw Exception("Can't extract m3u8 URL from embed page")
        val headers = mapOf(
            "Referer" to "https://ridoo.net/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            "Accept" to "*/*",
            "Accept-Language" to "de,en-US;q=0.7,en;q=0.3",
            "Origin" to "https://ridoo.net"
        )
        return Video(
            source = m3u8Url,
            subtitles = listOf(),
            headers = headers
        )
    }


    interface Service {
        @GET
        suspend fun get(@Url url: String): Document
    }

}
