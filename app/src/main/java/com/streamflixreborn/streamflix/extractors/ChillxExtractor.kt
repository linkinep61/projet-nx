package com.streamflixreborn.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.AesHelper
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import android.util.Log

open class ChillxExtractor : Extractor() {

    override val name = "Chillx"
    override val mainUrl = "https://chillx.top"

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)

        val document = service.getDocument(link, mainUrl)
        val html = document.toString()

        // Try multiple regex patterns: Cloudstream uses JScript, older versions use generic assignment
        val content = Regex("""JScript[\w+]?\s*=\s*'([^']+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""\s*=\s*'([^']+)""").find(html)?.groupValues?.get(1)
            ?: throw Exception("Can't retrieve content")

        val key = fetchKey(service)

        val decrypt = AesHelper.cryptoAESHandler(
            content,
            key.toByteArray(),
            false
        )
            ?.replace("\\n", "\n")
            ?.replace("\\", "")
            ?: throw Exception("Failed to decrypt")

        // Extract source URL
        val source = Regex("\"?file\"?:\\s*\"([^\"]+)").find(decrypt)
            ?.groupValues?.get(1)
            ?: throw Exception("Can't retrieve source")

        // Extract subtitles - try both JSON format and bracket format [lang]url
        val subtitles = mutableListOf<Video.Subtitle>()

        // Try JSON subtitle format: {"file":"url","label":"lang","kind":"captions"}
        Regex("""\{"file":"([^"]+)","label":"([^"]+)","kind":"captions"(?:,"default":\w+)?\}""")
            .findAll(decrypt)
            .forEach {
                subtitles.add(Video.Subtitle(label = it.groupValues[2], file = it.groupValues[1]))
            }

        // Try bracket format: [Language]https://url (used by newer Cloudstream)
        if (subtitles.isEmpty()) {
            val subtitleField = Regex("""subtitle"?\s*:\s*"([^"]+)""").find(decrypt)?.groupValues?.get(1)
            if (subtitleField != null) {
                Regex("""\[(.*?)](https?://[^\s,]+)""").findAll(subtitleField).forEach {
                    subtitles.add(Video.Subtitle(label = it.groupValues[1], file = it.groupValues[2]))
                }
            }
        }

        return Video(
            source = source,
            subtitles = subtitles,
            headers = mapOf("Referer" to mainUrl),
        )
    }

    companion object {
        private var cachedKey: String? = null

        // Key URLs to try in order (repos get deleted frequently)
        private val KEY_URLS = listOf(
            "https://raw.githubusercontent.com/rushi-chavan/multi-keys/keys/keys.json",
            "https://raw.githubusercontent.com/Rowdy-Avocado/multi-keys/keys/index.html",
        )

        private suspend fun fetchKey(service: Service): String {
            cachedKey?.let { return it }

            // Try each key URL
            for (url in KEY_URLS) {
                try {
                    val keys = service.getKeys(url)
                    val key = keys.chillx.firstOrNull()
                    if (key != null) {
                        cachedKey = key
                        return key
                    }
                } catch (_: Exception) {
                    continue
                }
            }

            throw Exception("Chillx key unavailable — all key sources are down")
        }
    }

    class JeanExtractor : ChillxExtractor() {
        override val name = "Jean"
        override val mainUrl = "https://player.jeansaispasplus.homes/"
    }

    class MoviesapiExtractor : ChillxExtractor() {
        override val name = "Moviesapi"
        override val mainUrl = "https://moviesapi.club/"
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
        suspend fun getDocument(
            @Url url: String,
            @Header("referer") referer: String,
        ): Document

        @GET
        suspend fun getKeys(@Url url: String): Keys


        data class Keys(
            val chillx: List<String>,
        )
    }
}