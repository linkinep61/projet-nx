package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URL

class XshotcokExtractor : Extractor() {
    override val name = "Xshotcok"
    override val mainUrl = "https://xshotcok.com"

    override suspend fun extract(link: String): Video {
        val baseUrl = URL(link).let { "${it.protocol}://${it.host}" }
        val service = Extractor.createJsoupService<Service>(baseUrl)
        val source = service.getSource(url = link)
        val html = source.toString()

        // Try multiple patterns for encoded hex string (like Vidsonic)
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
                headers = mapOf("Referer" to baseUrl, "Origin" to baseUrl)
            )
        }

        // Fallback: try direct URL extraction (common patterns)
        val directPatterns = listOf(
            Regex("""sources\s*:\s*\[\s*\{\s*(?:src|file)\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
            Regex("""source\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
            Regex("""src\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
            Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)"""),
            Regex("""player\.src\(\s*\{[^}]*src\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""video_link\s*=\s*['"]([^'"]+)['"]""")
        )

        val directUrl = directPatterns.firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.get(1)
        } ?: throw Exception("Could not find video source in Xshotcok HTML")

        return Video(
            source = directUrl,
            headers = mapOf("Referer" to baseUrl, "Origin" to baseUrl)
        )
    }

    private interface Service {
        @GET
        suspend fun getSource(
            @Url url: String
        ): Document
    }
}
