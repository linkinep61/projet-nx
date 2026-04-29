package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class FsvidExtractor : Extractor() {
    override val name = "FSVid"
    override val mainUrl = "https://fsvid.lol"

    private val service = Extractor.createGsonService<FsvidService>(mainUrl)

    private interface FsvidService {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String = "",
        ): String
    }

    override suspend fun extract(link: String): Video {
        val uri = Uri.parse(link)
        val host = "${uri.scheme}://${uri.host}"

        val html = service.get(link, "$host/")

        val scriptData = html
            .substringAfter("eval(function(p,a,c,k,e,d)")
            .substringBefore("</script>")
            .let { "eval(function(p,a,c,k,e,d)$it" }

        if (!scriptData.startsWith("eval")) throw Exception("Packed JS not found")
        val unpacked = JsUnpacker(scriptData).unpack() ?: throw Exception("Unpack failed")

        val m3u8 = Regex("""src\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
            ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
            ?: Regex("""sources\s*:\s*\[\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
            ?: Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(unpacked)?.groupValues?.get(1)
            ?: throw Exception("Stream URL not found in unpacked JS")

        return Video(
            source = m3u8,
            headers = mapOf(
                "Referer" to "$host/",
                "Origin" to host,
            )
        )
    }
}
