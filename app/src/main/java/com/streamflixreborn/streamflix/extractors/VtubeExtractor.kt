package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url

class VtubeExtractor : Extractor() {

    override val name = "Vtube"
    override val mainUrl = "https://vtbe.to"
    override val aliasUrls = listOf("https://vtube.to")

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<Service>(mainUrl)
        val document = service.getDocument(link)
        val html = document.outerHtml()

        val source = Regex("""sources:\s*\[\s*\{file:"([^"]+\.m3u8[^"]*)"""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""file:"([^"]+\.m3u8[^"]*)"""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
            ?: throw Exception("Unable to extract Vtube source")

        val uri = Uri.parse(link)
        val origin = "${uri.scheme}://${uri.host}"

        return Video(
            source = source,
            headers = mapOf(
                "Referer" to "$origin/",
                "Origin" to origin,
                "User-Agent" to USER_AGENT
            )
        )
    }

    private interface Service {
        @GET
        suspend fun getDocument(@Url url: String): Document
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
