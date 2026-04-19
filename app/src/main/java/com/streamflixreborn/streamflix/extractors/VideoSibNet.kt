package com.streamflixreborn.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.util.concurrent.TimeUnit


class VideoSibNetExtractor : Extractor() {
    override val name = "VideoSibNet"
    override val mainUrl = "https://video.sibnet.ru/"

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)

        val document = service.get(link, mainUrl)

        val relativeVideoUrl = extractVideoUrl(document)
            ?: throw Exception("Could not find video source in the webpage")

        val absoluteVideoUrl = when {
            relativeVideoUrl.startsWith("http") -> relativeVideoUrl
            relativeVideoUrl.startsWith("/") -> mainUrl.trimEnd('/') + relativeVideoUrl
            else -> mainUrl.trimEnd('/') + "/" + relativeVideoUrl
        }

        return Video(
            source = absoluteVideoUrl,
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to USER_AGENT,
                // Override NetworkClient defaults to look like a <video> tag request,
                // not a browser navigation — Sibnet checks these for hotlink protection
                "Sec-Fetch-Dest" to "video",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "same-origin",
                "Accept" to "*/*",
            )
        )
    }

    private fun extractVideoUrl(document: Document): String? {
        // Priority 1: <video src="..."> element (modern Sibnet uses this)
        document.selectFirst("video[src]")?.attr("src")?.let {
            if (it.isNotEmpty() && (it.contains(".mp4") || it.contains(".flv") || it.contains("/v/"))) {
                return it
            }
        }

        // Priority 2: <video><source src="..."></video>
        document.selectFirst("video source[src]")?.attr("src")?.let {
            if (it.isNotEmpty()) {
                return it
            }
        }

        val html = document.toString()

        // Pattern 3: player.src([{src: "..."}])
        Regex("""player\.src\(\[\{src:\s*["']([^"']+)["']""").find(html)?.let {
            return it.groupValues[1]
        }

        // Pattern 4: player.src({src: "..."})
        Regex("""player\.src\(\{src:\s*["']([^"']+)["']""").find(html)?.let {
            return it.groupValues[1]
        }

        // Pattern 5: src: "/video..." or "/v/..." inside a script with player
        val scriptTags = document.select("script")
        for (script in scriptTags) {
            val scriptContent = script.html()
            if ("player" in scriptContent) {
                Regex("""src:\s*["']([^"']+\.mp4[^"']*)["']""").find(scriptContent)?.let {
                    return it.groupValues[1]
                }
                Regex("""src:\s*["']([^"']+\.flv[^"']*)["']""").find(scriptContent)?.let {
                    return it.groupValues[1]
                }
                Regex("""file:\s*["']([^"']+)["']""").find(scriptContent)?.let {
                    return it.groupValues[1]
                }
                Regex("""src:\s*["'](/v/[^"']+)["']""").find(scriptContent)?.let {
                    return it.groupValues[1]
                }
            }
        }

        return null
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(
                    OkHttpClient.Builder()
                        .dns(DnsResolver.doh)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                )
                .addConverterFactory(JsoupConverterFactory.create())
                .build()
                .create(Service::class.java)
        }

        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("Accept") accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            @Header("Accept-Language") lang: String = "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
            @Header("User-Agent") userAgent: String = USER_AGENT,
        ): Document
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}