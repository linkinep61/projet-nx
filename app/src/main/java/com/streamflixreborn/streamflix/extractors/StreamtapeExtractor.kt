package com.streamflixreborn.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import okhttp3.ResponseBody
import org.jsoup.nodes.Document
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Streaming
import retrofit2.http.Url

class StreamtapeExtractor : Extractor() {

    override val name = "Streamtape"
    override val mainUrl = "https://streamtape.com"
    override val aliasUrls = listOf("https://streamta.site")
    

    override suspend fun extract(link: String): Video {
        val linkJustParameter = link.replace(mainUrl, "")

        val service = StreamtapeExtractorService.build(mainUrl)
        val source = service.getSource(linkJustParameter)

        // Streamtape uses rotating element IDs: 'botlink', 'norobotlink', 'ideoooolink', etc.
        // Try multiple regex patterns for different Streamtape page versions
        val html = source.html()

        val scriptRegexPatterns = listOf(
            // New format: document.getElementById('norobotlink').innerHTML = '...' + ('...').substring(N)
            Regex("""document\.getElementById\('norobotlink'\)\.innerHTML\s*=\s*'([^']+)'\s*\+\s*\('([^']+)'\)\.substring\(([0-9]+)\)"""),
            // Old format: document.getElementById('botlink').innerHTML = '...' + ('...').substring(N)
            Regex("""document\.getElementById\('botlink'\)\.innerHTML\s*=\s*'([^']+)'\s*\+\s*\('([^']+)'\)\.substring\(([0-9]+)\)"""),
            // Generic: any getElementById with innerHTML assignment
            Regex("""document\.getElementById\('[^']*link'\)\.innerHTML\s*=\s*'([^']+)'\s*\+\s*\('([^']+)'\)\.substring\(([0-9]+)\)"""),
        )

        val scriptMatch = scriptRegexPatterns.firstNotNullOfOrNull { it.find(html) }
            ?: throw Exception("Streamtape link JavaScript not found (tried botlink/norobotlink)")

        val baseUrl = scriptMatch.groupValues[1]
        val paramString = scriptMatch.groupValues[2]
        val substringIndex = scriptMatch.groupValues[3].toInt()

        // Applica substring per ottenere i parametri corretti
        val cleanParams = paramString.substring(substringIndex)

        // Build the video URL from baseUrl + cleanParams
        val tokenRegex = Regex("token=([^&']+)")
        val token = tokenRegex.find(cleanParams)?.groupValues?.get(1) ?: throw Exception("token not found")

        // Reconstruct the full get_video URL
        val idRegex = Regex("id=([^&]+)")
        val expiresRegex = Regex("expires=([^&]+)")
        val ipRegex = Regex("ip=([^&]+)")

        val videoId = idRegex.find(cleanParams)?.groupValues?.get(1) ?: throw Exception("video id not found")
        val expires = expiresRegex.find(cleanParams)?.groupValues?.get(1) ?: throw Exception("expires not found")
        val ip = ipRegex.find(cleanParams)?.groupValues?.get(1) ?: throw Exception("ip not found")

        val finalVideoUrl = "$mainUrl/get_video?id=$videoId&expires=$expires&ip=$ip&token=$token&stream=1"

        val response = service.getVideo(finalVideoUrl)
        val sourceUrl = (response.raw() as okhttp3.Response).networkResponse?.request?.url?.toString()
            ?: throw Exception("Can't retrieve URL")

        val video = Video(
            source = sourceUrl,
            subtitles = listOf()
        )
        return video
    }

    private interface StreamtapeExtractorService {
        companion object {
            fun build(baseUrl: String): StreamtapeExtractorService {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()

                return retrofit.create(StreamtapeExtractorService::class.java)
            }
        }

        @GET
        @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        suspend fun getSource(@Url url: String): Document

        @GET
        @Streaming
        @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        suspend fun getVideo(@Url url: String): Response<ResponseBody>
    }
}