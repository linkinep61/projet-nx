package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url

class YourUploadExtractor : Extractor() {

    override val name = "YourUpload"
    override val mainUrl = "https://www.yourupload.com"
    override val aliasUrls = listOf("https://www.yucache.net")

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<YourUploadExtractorService>(mainUrl)
        val doc = try {
            service.getSource(link.replace(mainUrl, ""))
        } catch (e: retrofit2.HttpException) {
            // 2026-11-02 (banc Jujutsu Kaisen, vérifié depuis le PC) : 404 « Content Restricted »
            //   = vidéo retirée → dead-content (signalé à la base), pas une erreur HTTP banale.
            if (e.code() == 404 || e.code() == 410) throw Exception("YourUpload: video not found - content restricted (HTTP ${e.code()})")
            throw e
        }

        // Extract JWPlayer config script
        val scriptContent = doc.select("script:containsData(jwplayerOptions)").html()

        // Look for .m3u8 first, then fallback to .mp4
        val regex = Regex("""file:\s*'([^']+\.(?:m3u8|mp4))'""")
        val match = regex.find(scriptContent)
        val videoUrl = match?.groupValues?.get(1)
            ?: throw Exception("Stream URL not found in JWPlayer config")

        return Video(
            source = videoUrl,
            subtitles = listOf(),
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to Extractor.DEFAULT_USER_AGENT
            )
        )
    }

    private interface YourUploadExtractorService {
        @GET
        suspend fun getSource(@Url url: String): Document
    }
}