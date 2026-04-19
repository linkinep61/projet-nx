package com.streamflixreborn.streamflix.extractors

import androidx.media3.common.MimeTypes
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URL
import java.util.concurrent.TimeUnit

class DarkiboxExtractor : Extractor() {

    override val name = "Darkibox"
    override val mainUrl = "https://darkibox.com"

    // Match dl[N].darkibox.com subdomains for direct HLS streaming URLs
    override val rotatingDomain: List<Regex> = listOf(
        Regex("""dl\d+\.darkibox\.com""")
    )

    override suspend fun extract(link: String): Video {
        // If the link is already a direct m3u8 URL (from series/download API), use it directly
        if (link.contains(".m3u8") || link.contains("/hls")) {
            return Video(
                source = link,
                type = MimeTypes.APPLICATION_M3U8,
                headers = mapOf(
                    "Referer" to mainUrl
                )
            )
        }

        val baseUrl = URL(link).protocol + "://" + URL(link).host
        val service = Service.build(baseUrl)
        val document = service.getSource(url = link)

        val fullHtml = document.html()

        // Search for m3u8 or video source in scripts
        val scripts = document.select("script")
        val scriptContent = scripts.find {
            it.html().contains("sources") || it.html().contains(".m3u8") || it.html().contains("player") || it.html().contains("file")
        }?.html() ?: fullHtml

        // Try multiple regex patterns for different Darkibox page versions
        val regexPatterns = listOf(
            // HLS m3u8 patterns
            Regex("""sources:\s*\[\{[^}]*file\s*:\s*"([^"]+\.m3u8[^"]*)""""),
            Regex("""sources:\s*\[\{[^}]*src\s*:\s*"([^"]+\.m3u8[^"]*)""""),
            Regex("""file\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
            Regex("""src\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
            Regex("""(https?://[^"'\s]+/hls[^"'\s]*master\.m3u8[^"'\s]*)"""),
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
            // MP4 fallback patterns
            Regex("""sources:\s*\[\{[^}]*file\s*:\s*"([^"]+\.mp4[^"]*)""""),
            Regex("""file\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
            Regex("""src\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
            Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)"""),
            // Generic video URL pattern
            Regex("""video_link\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""player\.src\(\s*\{[^}]*src\s*:\s*"([^"]+)"[^}]*\}""")
        )

        var sourceUrl: String? = null
        var isM3u8 = false

        for (regex in regexPatterns) {
            val match = regex.find(scriptContent)
            if (match != null) {
                sourceUrl = match.groupValues[1]
                isM3u8 = sourceUrl.contains(".m3u8")
                break
            }
        }

        // If not found in script, try full HTML
        if (sourceUrl == null && scriptContent != fullHtml) {
            for (regex in regexPatterns) {
                val match = regex.find(fullHtml)
                if (match != null) {
                    sourceUrl = match.groupValues[1]
                    isM3u8 = sourceUrl.contains(".m3u8")
                    break
                }
            }
        }

        if (sourceUrl == null) throw Exception("Sources not found for Darkibox")

        return Video(
            source = sourceUrl,
            type = if (isM3u8) MimeTypes.APPLICATION_M3U8 else null,
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
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", USER_AGENT)
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
