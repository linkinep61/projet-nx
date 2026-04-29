package com.streamflixreborn.streamflix.extractors

import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
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
        val service = Extractor.createGsonService<Service>(baseUrl)

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
        @POST("api/stream")
        suspend fun getStream(
            @Body body: okhttp3.RequestBody
        ): ResponseBody
    }
}
