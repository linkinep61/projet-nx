package com.streamflixreborn.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
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
        val service = Service.build(baseUrl)
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

        companion object {
            private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", baseUrl)
                            .build()
                        chain.proceed(request)
                    }
                    .build()
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                return retrofit.create(Service::class.java)
            }
        }
    }
}
