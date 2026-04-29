package com.streamflixreborn.streamflix.extractors

import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url
class BigWarpExtractor: Extractor() {

    override val name = "BigWarp (VLC only)"
    override val mainUrl = "https://bigwarp.cc/"
    override val aliasUrls = listOf("https://bigwarp.io", "https://bigwarp.pro")


    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<BigWarpExtractorService>(mainUrl)
        val source = service.getSource(link.replace(mainUrl, ""))
        val scriptTags = source.select("script[type=text/javascript]")

        var m3u8: String? = null

        for (script in scriptTags) {
            val scriptData = script.data()
            if ("jwplayer" in scriptData && "sources" in scriptData && "file" in scriptData) {
                val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
                val match = fileRegex.find(scriptData)
                if (match != null) {
                    m3u8 = match.groupValues[1]
                    break
                }
            }
        }

        if (m3u8 == null) {
            throw Exception("Stream URL not found in script tags")
        }

        return Video(
            source = m3u8,
            subtitles = listOf(),
            type = MimeTypes.APPLICATION_MP4,
            headers =                     mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "Referer" to mainUrl
            )
        )

    }


    private interface BigWarpExtractorService {
        @GET
        suspend fun getSource(@Url url: String): Document
    }


}
