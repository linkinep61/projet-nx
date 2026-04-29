package com.streamflixreborn.streamflix.extractors

import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class GxPlayerExtractor : Extractor() {

    override val name = "GxPlayer"
    override val mainUrl = "https://watch.gxplayer.xyz"

    companion object {
        private val REGEX_ID = Regex("\"id\":\"([^\"]+)\"")
        private val REGEX_UID = Regex("\"uid\":\"([^\"]+)\"")
        private val REGEX_MD5 = Regex("\"md5\":\"([^\"]+)\"")
        private val REGEX_STATUS = Regex("\"status\":\"([^\"]+)\"")
    }

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<GxPlayerService>(mainUrl)
        val document = service.get(link, Extractor.DEFAULT_USER_AGENT, mainUrl)

        val scriptContent = document.select("script").find {
            it.html().contains("var video =")
        }?.html() ?: throw Exception("Video script not found")

        val id = REGEX_ID.find(scriptContent)?.groupValues?.get(1) ?: ""
        val uid = REGEX_UID.find(scriptContent)?.groupValues?.get(1) ?: ""
        val md5 = REGEX_MD5.find(scriptContent)?.groupValues?.get(1) ?: ""
        val status = REGEX_STATUS.find(scriptContent)?.groupValues?.get(1) ?: ""

        if (uid.isEmpty() || md5.isEmpty() || id.isEmpty()) {
            throw Exception("Could not extract video parameters")
        }

        val videoUrl = "$mainUrl/m3u8/$uid/$md5/master.txt?s=1&id=$id&cache=$status"

        return Video(
            source = videoUrl,
            headers = mapOf(
                "User-Agent" to Extractor.DEFAULT_USER_AGENT,
                "Referer" to mainUrl
            ),
            type = MimeTypes.APPLICATION_M3U8
        )
    }

    private interface GxPlayerService {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("User-Agent") userAgent: String,
            @Header("Referer") referer: String
        ): Document
    }
}
