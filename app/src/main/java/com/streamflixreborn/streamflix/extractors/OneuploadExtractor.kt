package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url

class OneuploadExtractor : Extractor() {

    override val name = "OneUpload"
    override val mainUrl = "https://oneupload.net"
    override val aliasUrls = listOf("https://tipfly.xyz", "https://oneupload.to")

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<Service>(mainUrl)

        val document = service.get(link)
        val scriptTags = document.select("script[type*=javascript]")

        var fileUrl: String? = null

        for (script in scriptTags) {
            val scriptData = script.data()
            if ("jwplayer" in scriptData && "sources" in scriptData && "file" in scriptData) {
                val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
                val match = fileRegex.find(scriptData)
                if (match != null) {
                    fileUrl = match.groupValues[1]
                    break
                }
            }
        }

        return Video(
            source = fileUrl ?: throw Exception("Can't retrieve source")
        )
    }

    private interface Service {
        @GET
        suspend fun get(@Url url: String): Document
    }
}
