package com.streamflixreborn.streamflix.extractors

import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import java.net.URL

class VidaraExtractor : Extractor() {

    override val name = "Vidara"
    override val mainUrl = "https://vidara.to"
    override val aliasUrls = listOf("https://vidara.so")

    override suspend fun extract(link: String): Video {
        val url = URL(link)
        val fileCode = url.path.substringAfterLast("/")
        require(fileCode.isNotEmpty()) { "File code not found in URL" }

        val baseUrl = "${url.protocol}://${url.host}"
        val service = Service.build(baseUrl)

        // API expects JSON body with filecode and device fields
        val jsonBody = """{"filecode":"$fileCode","device":"web"}"""
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val responseBody = service.getStream(requestBody)
        val jsonString = responseBody.use { it.string() }
        if (jsonString.isBlank()) throw Exception("Empty API response")

        val json = try {
            JsonParser.parseString(jsonString).asJsonObject
        } catch (e: Exception) {
            throw Exception("Failed to parse API response: ${e.message}")
        }

        val streamingUrl = json.get("streaming_url")?.asString
            ?: throw Exception("Streaming URL not found in API response")

        val defaultSub = json.get("default_sub_lang")?.asString.orEmpty()
        val subtitlesArray = json.get("subtitles")?.takeIf { it.isJsonArray }?.asJsonArray
        val subtitles = subtitlesArray?.map { elem ->
            val obj = elem.asJsonObject
            val lang = obj.get("language")?.asString.orEmpty()
            Video.Subtitle(
                label = lang,
                file = obj.get("file_path")?.asString.orEmpty(),
                default = lang.equals(defaultSub, ignoreCase = true) && defaultSub.isNotEmpty()
            )
        }.orEmpty()

        return Video(
            source = streamingUrl,
            subtitles = subtitles
        )
    }

    private interface Service {

        companion object {
            private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", "$baseUrl/")
                            .header("Origin", baseUrl)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Accept", "application/json, text/plain, */*")
                            .build()
                        chain.proceed(request)
                    }
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @POST("api/stream")
        suspend fun getStream(
            @Body body: okhttp3.RequestBody
        ): ResponseBody
    }
}
