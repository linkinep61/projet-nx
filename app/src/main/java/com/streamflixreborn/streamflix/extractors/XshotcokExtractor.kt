package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URL

class XshotcokExtractor : Extractor() {
    override val name = "Xshotcok"
    override val mainUrl = "https://xshotcok.com"

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override suspend fun extract(link: String): Video {
        val baseUrl = URL(link).let { "${it.protocol}://${it.host}" }
        val service = Service.build(baseUrl)
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

        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .followRedirects(true)
                    .followSslRedirects(true)
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
