package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class HxfileExtractor : Extractor() {
    override val name = "Hxfile"
    override val mainUrl = "https://hxfile.co"

    override suspend fun extract(link: String): Video {
        val fileCode = link.substringAfterLast("/").substringBefore(".html")
        val embedUrl = if (link.contains("/embed-")) link else "$mainUrl/embed-$fileCode.html"

        val service = Extractor.createJsoupService<Service>(mainUrl)
        val source = service.getSource(
            url = embedUrl,
            referer = link
        )

        val packedJS = Regex("(eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?)</script>")
            .find(source.toString())?.groupValues?.get(1)
            ?: throw Exception("Packed JS not found")

        var unpacked = JsUnpacker(packedJS).unpack()
            ?: throw Exception("Unpacked is null")

        val xorRegex = """var\s+(_[0-9a-f]{6})\s*=\s*"([^"]+)".*?var\s+(_0x[0-9a-f]{6})\s*=\s*_[0-9a-z]{6}\(\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        xorRegex.find(unpacked)?.let { match ->
            val payload = match.groupValues[2]
            val key = match.groupValues[3]
            try {
                val data = Base64.decode(payload, Base64.DEFAULT)
                val decrypted = StringBuilder()
                for (i in data.indices) {
                    decrypted.append((data[i].toInt() xor key[i % key.length].code).toChar())
                }
                unpacked = decrypted.toString()
            } catch (e: Exception) {}
        }

        val finalUrl = Regex("""sources[\s\S]*?["']?file["']?\s*[:=]\s*["']([^"']+)["']""")
            .find(unpacked)?.groupValues?.get(1)
            ?: throw Exception("No file link found in unpacked JS")

        return Video(source = finalUrl)
    }

    private interface Service {
        @GET
        suspend fun getSource(
            @Url url: String,
            @Header("User-Agent") userAgent: String = Extractor.DEFAULT_USER_AGENT,
            @Header("Referer") referer: String
        ): Document
    }
}
