package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import retrofit2.http.GET
import retrofit2.http.Url

class SupervideoExtractor : Extractor() {
    override val name = "Supervideo"
    override val mainUrl = "https://supervideo.cc"

    private val service = Extractor.createGsonService<SupervideoService>(mainUrl)

    private interface SupervideoService {
        @GET
        suspend fun get(@Url url: String): String
    }

    override suspend fun extract(link: String): Video {
        val pageHtml = try {
            service.get(link)
        } catch (_: Exception) {
            service.get(if (link.startsWith("http")) link else "https:$link")
        }

        val scriptData = pageHtml
            .substringAfter("eval(function(p,a,c,k,e,d)")
            .substringBefore("</script>")
            .let { "eval(function(p,a,c,k,e,d)$it" }

        if (!scriptData.startsWith("eval")) {
            throw Exception("Packed JS not found")
        }

        val unpacked = JsUnpacker(scriptData).unpack() ?: throw Exception("Unpack failed")

        val fileRegex = Regex("""file\s*:\s*[\"']([^\"']+)[\"']""")
        // Collect all file: matches; prefer video URLs (.m3u8/.mp4) over subtitles (.vtt/.srt/.ass)
        val allFileMatches = fileRegex.findAll(unpacked).map { it.groupValues[1] }.toList()
        val streamUrl = allFileMatches.firstOrNull {
            it.contains(".m3u8") || it.contains(".mp4") || it.contains(".mpd")
        } ?: allFileMatches.firstOrNull {
            !it.contains(".vtt") && !it.contains(".srt") && !it.contains(".ass")
        } ?: throw Exception("Stream URL not found in file field")

        val tracksBlock = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(unpacked)
            ?.groupValues?.get(1)
            ?: ""
        val captionMatches = Regex("""file\s*:\s*\"(.*?)\"\s*,\s*label\s*:\s*\"(.*?)\"\s*,\s*kind\s*:\s*\"captions\"""")
            .findAll(tracksBlock)
        val subtitles = captionMatches.map {
            Video.Subtitle(
                label = it.groupValues[2],
                file = it.groupValues[1]
            )
        }.toList()

        return Video(
            source = streamUrl,
            subtitles = subtitles,
            headers = mapOf("Referer" to mainUrl),
            extraBuffering = true
        )
    }
}


