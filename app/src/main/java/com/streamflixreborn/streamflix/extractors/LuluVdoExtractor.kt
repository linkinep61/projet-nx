package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

class LuluVdoExtractor : Extractor() {

    override val name = "LuluVdo"
    override val mainUrl = "https://luluvdo.com/"
    override val aliasUrls = listOf("https://luluvdoo.com", "https://luluvid.com")
    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)

        val document = service.get(link)
        val html = document.toString()

        // Try direct regex first (non-packed pages)
        var source = listOf(
            Regex("""sources:\s*\[\s*\{\s*file\s*:\s*"(.*?)""""),
            Regex("""sources\s*:\s*\[\s*\{\s*"file"\s*:\s*"(.*?)""""),
            Regex("""file\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
            Regex("""file\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
        ).firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.get(1)
        }

        // Unpack eval(function(p,a,c,k,e,d){...}('...',a,c,'...',e,d))
        var unpacked = ""
        if (source == null) {
            unpacked = unpackJs(html)
            if (unpacked.isNotEmpty()) {
                Log.d("LuluVdo", "Unpacked JS: ${unpacked.take(500)}")
                source = listOf(
                    Regex("""sources:\s*\[\s*\{\s*file\s*:\s*"(.*?)""""),
                    Regex("""file\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
                    Regex("""file\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
                ).firstNotNullOfOrNull { regex ->
                    regex.find(unpacked)?.groupValues?.get(1)
                }
            }
        }

        if (source == null) throw Exception("Can't retrieve source")

        // Extract subtitles from unpacked or raw HTML
        val searchText = unpacked.ifEmpty { html }
        val subtitles = Regex("""file\s*:\s*"(.*?)"\s*,\s*label\s*:\s*"(.*?)"""").findAll(
            Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(searchText)
                ?.groupValues?.get(1) ?: ""
        )
            .map { Video.Subtitle(label = it.groupValues[2], file = it.groupValues[1]) }
            .toList()
            .filter { it.label != "Upload captions" }

        return Video(
            source = source,
            subtitles = subtitles,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to "$mainUrl/"
            )
        )
    }

    /**
     * Unpack eval(function(p,a,c,k,e,d) packed JavaScript.
     * p = template with number placeholders, a = radix, c = count, k = word list (split by |)
     */
    private fun unpackJs(html: String): String {
        // Match: eval(function(p,a,c,k,e,d){...}('template',radix,count,'words|list'.split('|'),0,{}))
        val match = Regex(
            """\beval\(function\(p,a,c,k,e,d\)\{[^}]*\}\('(.*?)',\s*(\d+),\s*(\d+),\s*'(.*?)'\s*\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html) ?: return ""

        var p = match.groupValues[1]
            .replace("\\'", "'")  // unescape single quotes
        val a = match.groupValues[2].toIntOrNull() ?: return ""
        val words = match.groupValues[4].split("|")

        // Replace each word-boundary number token (in base a) with the word from array k
        // Process longest tokens first to avoid partial replacement
        val tokenRegex = Regex("\\b(\\w+)\\b")
        p = tokenRegex.replace(p) { mr ->
            val token = mr.groupValues[1]
            val index = try { token.toInt(a) } catch (_: Exception) { -1 }
            if (index in words.indices && words[index].isNotEmpty()) words[index]
            else token
        }

        return p
    }


    private interface Service {

        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }


        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept-Language: en-US,en;q=0.9"
        )
        suspend fun get(@Url url: String): Document
    }
}