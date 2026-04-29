package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URL

class UqloadExtractor : Extractor() {
    override val name = "Uqload"
    override val mainUrl = "https://uqload.is"
    override val aliasUrls = listOf(
        "https://uqload.cx",
        "https://uqload.co",
        "https://uqload.to"
    )


    override suspend fun extract(link: String): Video {
        val baseUrl = URL(link).protocol + "://" + URL(link).host
        val service = Extractor.createJsoupService<Service>(baseUrl, baseUrl)
        val document = service.getSource(url = link)

        val fullHtml = document.html()

        // Search in all scripts, not just type="text/javascript"
        val scripts = document.select("script")
        val scriptContent = scripts.find { it.html().contains("sources:") || it.html().contains("player.src") || it.html().contains(".mp4") }?.html()
            ?: fullHtml // Fallback to full HTML

        // Try multiple regex patterns for different Uqload page versions
        val regexPatterns = listOf(
            Regex("""sources:\s*\["([^"]+)"\]"""),
            Regex("""sources:\s*\[\{[^}]*file\s*:\s*"([^"]+)"[^}]*\}\]"""),
            Regex("""sources:\s*\[\{[^}]*src\s*:\s*"([^"]+)"[^}]*\}\]"""),
            Regex("""file\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
            Regex("""src\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
            Regex("""player\.src\(\s*\{[^}]*src\s*:\s*"([^"]+)"[^}]*\}"""),
            Regex("""(https?://[^"'\s]+/v\.mp4[^"'\s]*)"""),
            Regex("""video_link\s*=\s*['"]([^'"]+)['"]""")
        )

        var sourceUrl: String? = null
        for (regex in regexPatterns) {
            val match = regex.find(scriptContent)
            if (match != null) {
                sourceUrl = match.groupValues[1]
                break
            }
        }

        // If not found in script, try full HTML
        if (sourceUrl == null && scriptContent != fullHtml) {
            for (regex in regexPatterns) {
                val match = regex.find(fullHtml)
                if (match != null) {
                    sourceUrl = match.groupValues[1]
                    break
                }
            }
        }

        if (sourceUrl == null) throw Exception("Sources not found in script")

        return Video(
            source = sourceUrl,
            headers = mapOf(
                "Referer" to baseUrl
            )
        )
    }

    private interface Service {
        @GET
        suspend fun getSource(
            @Url url: String
        ): Document
    }
}
