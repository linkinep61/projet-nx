package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url

class GoodstreamExtractor : Extractor() {

    override val name = "Goodstream"
    override val mainUrl = "https://goodstream.one"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<Service>(mainUrl)

        val document = service.get(link)
        val scriptTags = document.select("script[type*=javascript]")

        var m3u8: String? = null

        for (script in scriptTags) {
            val scriptData = script.data()
            if ("jwplayer" in scriptData && "sources" in scriptData && "file" in scriptData) {
                val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
                val match = fileRegex.find(scriptData)
                if (match != null) {
                    m3u8 = match.groupValues[1]
                    break
                }
            }
        }

        return Video(
            source = m3u8 ?: throw Exception("Can't retrieve source"),
            headers = mapOf("User-Agent" to userAgent)
        )
    }

    private interface Service {
        @GET
        suspend fun get(@Url url: String): Document
    }
}
