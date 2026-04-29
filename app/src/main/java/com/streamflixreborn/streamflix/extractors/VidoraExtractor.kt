package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

open class VidoraExtractor : Extractor() {

    override val name = "Vidora"
    override val mainUrl = "https://vidora.stream"

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<Service>(mainUrl, referer = mainUrl)

        val response = service.get(link, referer = mainUrl)

        val packedJS = Regex("(eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?)</script>")
            .find(response.toString())?.let { it.groupValues[1] }
            ?: throw Exception("Packed JS not found")

        val unpacked = JsUnpacker(packedJS).unpack()
            ?: throw Exception("Unpacked is null")

        var source: String? = null
        if ("jwplayer" in unpacked && "sources" in unpacked && "file" in unpacked) {
            val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
            source = fileRegex.find(unpacked)?.groupValues?.get(1)
        }

        if (source == null) throw Exception("No source found")

        return Video(
            source = source,
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to Extractor.DEFAULT_USER_AGENT
            )
        )
    }

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("User-Agent") userAgent: String = Extractor.DEFAULT_USER_AGENT
        ): Document
    }
}
