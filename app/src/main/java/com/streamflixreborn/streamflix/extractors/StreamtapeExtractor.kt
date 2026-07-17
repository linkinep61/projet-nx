package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import okhttp3.ResponseBody
import org.jsoup.nodes.Document
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Streaming
import retrofit2.http.Url

class StreamtapeExtractor : Extractor() {

    override val name = "Streamtape"
    override val mainUrl = "https://streamtape.com"
    override val aliasUrls = listOf("https://streamta.site", "https://strtape.cloud", "https://stape.fun")


    override suspend fun extract(link: String): Video {
        val linkJustParameter = link.replace(mainUrl, "")

        val service = Extractor.createJsoupService<StreamtapeExtractorService>(mainUrl)
        val source = service.getSource(linkJustParameter)

        // Streamtape uses rotating element IDs: 'botlink', 'norobotlink', 'ideoolink', etc.
        // Try multiple regex patterns for different Streamtape page versions
        val html = source.html()

        val scriptRegexPatterns = listOf(
            // Old format: document.getElementById('norobotlink').innerHTML = '...' + ('...').substring(N)
            Regex("""document\.getElementById\('norobotlink'\)\.innerHTML\s*=\s*'([^']+)'\s*\+\s*\('([^']+)'\)\.substring\(([0-9]+)\)"""),
            // Current format: document.getElementById('botlink').innerHTML = '...' + ('...').substring(N)
            Regex("""document\.getElementById\('botlink'\)\.innerHTML\s*=\s*'([^']+)'\s*\+\s*\('([^']+)'\)\.substring\(([0-9]+)\)"""),
            // Generic: any getElementById with *link ID and innerHTML assignment
            Regex("""document\.getElementById\('[^']*link'\)\.innerHTML\s*=\s*'([^']+)'\s*\+\s*\('([^']+)'\)\.substring\(([0-9]+)\)"""),
        )

        val scriptMatch = scriptRegexPatterns.firstNotNullOfOrNull { it.find(html) }
            ?: throw Exception("Streamtape link JavaScript not found (tried botlink/norobotlink)")

        val baseUrl = scriptMatch.groupValues[1]
        val paramString = scriptMatch.groupValues[2]
        val substringIndex = scriptMatch.groupValues[3].toInt()

        // Concat baseUrl + remaining params to build the full get_video URL.
        // baseUrl is typically "//streamtape.com/get_video?id=" and cleanParams
        // starts with the id value followed by "&expires=...&ip=...&token=...".
        // Previous code tried to parse each param individually but Streamtape
        // moved the id= into the baseUrl part → "video id not found" crash.
        val cleanParams = paramString.substring(substringIndex)
        var finalVideoUrl = baseUrl + cleanParams

        // Ensure scheme
        if (finalVideoUrl.startsWith("//")) {
            finalVideoUrl = "https:$finalVideoUrl"
        } else if (!finalVideoUrl.startsWith("http")) {
            finalVideoUrl = "$mainUrl$finalVideoUrl"
        }

        // Append &stream=1 if not present (forces redirect to actual mp4)
        if (!finalVideoUrl.contains("stream=")) {
            finalVideoUrl += "&stream=1"
        }

        val response = service.getVideo(finalVideoUrl)
        val sourceUrl = (response.raw() as okhttp3.Response).networkResponse?.request?.url?.toString()
            ?: throw Exception("Can't retrieve URL")

        val video = Video(
            source = sourceUrl,
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            ),
            subtitles = listOf()
        )
        return video
    }

    private interface StreamtapeExtractorService {
        @GET
        @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        suspend fun getSource(@Url url: String): Document

        @GET
        @Streaming
        @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        suspend fun getVideo(@Url url: String): Response<ResponseBody>
    }
}
