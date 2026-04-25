package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url

class LuluVdoExtractor : Extractor() {

    override val name = "LuluVdo"
    override val mainUrl = "https://luluvdo.com/"
    override val aliasUrls = listOf("https://luluvdoo.com", "https://luluvid.com", "https://lulustream.com")

    companion object {
        private const val TAG = "LuluVdoExtractor"
    }

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)
        val html = service.get(link)

        Log.d(TAG, "Page loaded, length: ${html.length}")

        // Try direct regex first (non-packed pages)
        var source = listOf(
            Regex("""sources:\s*\[\s*\{\s*file\s*:\s*"(.*?)""""),
            Regex("""sources\s*:\s*\[\s*\{\s*"file"\s*:\s*"(.*?)""""),
            Regex("""file\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
            Regex("""file\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
        ).firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.get(1)
        }

        if (source != null) {
            Log.d(TAG, "Direct source found: ${source.take(60)}...")
        }

        // Unpack eval(function(p,a,c,k,e,d){...}('...',a,c,'...',e,d))
        var unpacked = ""
        if (source == null) {
            unpacked = unpackJs(html)
            Log.d(TAG, "Unpacked JS length: ${unpacked.length}")
            if (unpacked.isNotEmpty()) {
                source = listOf(
                    Regex("""sources:\s*\[\s*\{\s*file\s*:\s*"(.*?)""""),
                    Regex("""file\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
                    Regex("""file\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
                ).firstNotNullOfOrNull { regex ->
                    regex.find(unpacked)?.groupValues?.get(1)
                }
                if (source != null) {
                    Log.d(TAG, "Unpacked source found: ${source.take(60)}...")
                } else {
                    Log.e(TAG, "No source in unpacked JS. Snippet: ${unpacked.take(200)}")
                }
            }
        }

        if (source == null) throw Exception("Can't retrieve source from LuluVdo")

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
                "Referer" to mainUrl
            )
        )
    }

    /**
     * Unpack eval(function(p,a,c,k,e,d) packed JavaScript.
     * p = template with number placeholders, a = radix, c = count, k = word list (split by |)
     */
    private fun unpackJs(html: String): String {
        // Match: eval(function(p,a,c,k,e,d){...}('template',radix,count,'words|list'.split('|'),0,{}))
        // Use [\s\S]*? for function body to handle nested braces
        val match = Regex(
            """\beval\(function\(p,a,c,k,e,d\)\{[\s\S]*?\}\('([\s\S]*?)',\s*(\d+),\s*(\d+),\s*'([\s\S]*?)'\s*\.split\('\|'\)"""
        ).find(html)

        if (match == null) {
            Log.e(TAG, "unpackJs: eval regex did not match. Has eval: ${html.contains("eval(function(p,a,c,k,e,d)")}")
            return ""
        }

        var p = match.groupValues[1]
            .replace("\\'", "'")  // unescape single quotes
        val a = match.groupValues[2].toIntOrNull() ?: return ""
        val words = match.groupValues[4].split("|")

        Log.d(TAG, "unpackJs: radix=$a, words=${words.size}, template length=${p.length}")

        // Replace each word-boundary number token (in base a) with the word from array k
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
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .client(NetworkClient.default)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: 