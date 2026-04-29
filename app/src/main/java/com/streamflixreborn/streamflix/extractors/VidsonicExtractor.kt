package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.net.URL

class VidsonicExtractor : Extractor() {
    override val name = "Vidsonic"
    override val mainUrl = "https://vidsonic.net"

    override suspend fun extract(link: String): Video {
        // Use link's own domain as base (domain may have changed from mainUrl)
        val linkUrl = URL(link)
        val linkBaseUrl = "${linkUrl.protocol}://${linkUrl.host}"
        val service = Extractor.createJsoupService<Service>(linkBaseUrl)

        val source = service.getSource(
            url = link,
            userAgent = Extractor.DEFAULT_USER_AGENT,
            referer = "$linkBaseUrl/"
        )

        val html = source.toString()

        // Try multiple patterns for encoded hex string
        val encodedRegexes = listOf(
            Regex("""(?:const|var|let)\s+\w+\s*=\s*'([a-fA-F0-9|]{50,})'\s*;"""),
            Regex("""(?:const|var|let)\s+\w+\s*=\s*"([a-fA-F0-9|]{50,})"\s*;"""),
            Regex("""['"]([a-fA-F0-9|]{100,})['"]""")
        )

        val encodedStr = encodedRegexes.firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.get(1)
        }

        if (encodedStr != null) {
            val cleaned = encodedStr.replace("|", "")
            val asciiBuilder = StringBuilder()
            for (i in cleaned.indices step 2) {
                val hexPair = cleaned.substring(i, i + 2)
                asciiBuilder.append(hexPair.toInt(16).toChar())
            }
            val sourceUrl = asciiBuilder.toString().reversed()
            return Video(
                source = sourceUrl,
                headers = mapOf("Referer" to "$linkBaseUrl/", "Origin" to linkBaseUrl)
            )
        }

        // Fallback: try direct URL extraction
        val directPatterns = listOf(
            Regex("""sources\s*:\s*\[\s*\{\s*(?:src|file)\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
            Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""")
        )

        val directUrl = directPatterns.firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.get(1)
        } ?: throw Exception("Could not find video source in Vidsonic HTML")

        return Video(
            source = directUrl,
            headers = mapOf("Referer" to "$linkBaseUrl/", "Origin" to linkBaseUrl)
        )
    }

    private interface Service {
        @GET
        suspend fun getSource(
            @Url url: String,
            @Header("User-Agent") userAgent: String = Extractor.DEFAULT_USER_AGENT,
            @Header("Referer") referer: String = ""
        ): Document
    }
}
