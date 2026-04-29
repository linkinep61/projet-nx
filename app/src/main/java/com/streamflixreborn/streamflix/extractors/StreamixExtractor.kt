package com.streamflixreborn.streamflix.extractors

import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query
import java.net.URL

class StreamixExtractor : Extractor() {

    override val name = "Streamix"
    override val mainUrl = "https://streamix.so"
    override val aliasUrls = listOf("https://stmix.io")

    override suspend fun extract(link: String): Video {
        val uri = URL(link)
        val host = uri.host
        val apiBaseUrl = (listOf(mainUrl) + aliasUrls).find { it.contains(host) } ?: "${uri.protocol}://${host}"
        val fileCode = uri.path.split("/").last { it.isNotEmpty() }

        if (fileCode.isEmpty()) {
            throw Exception("File code not found in URL")
        }

        val service = Extractor.createGsonService<Service>(apiBaseUrl)

        val responseBody = service.getStream(
            fileCode = fileCode
        )
        val responseString = responseBody.string()

        val jsonObject = try {
            JsonParser.parseString(responseString).asJsonObject
        } catch (e: Exception) {
            throw Exception("Failed to parse Streamix API response: ${e.message}")
        }

        val streamingUrl = jsonObject.get("streaming_url")?.asString
            ?: throw Exception("Streaming URL not found in Streamix API response")

        return Video(
            source = streamingUrl
        )
    }

    private interface Service {
        @GET("ajax/stream")
        suspend fun getStream(
            @Query("filecode") fileCode: String
        ): ResponseBody
    }
}
