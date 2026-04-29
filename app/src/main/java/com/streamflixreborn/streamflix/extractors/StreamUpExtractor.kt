package com.streamflixreborn.streamflix.extractors

import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.net.URL

class StreamUpExtractor : Extractor() {

    override val name = "StreamUp"
    override val mainUrl = "https://strmup.to"

    override suspend fun extract(link: String): Video {
        val fileCode = URL(link).path.split("/").last { it.isNotEmpty() }
        if (fileCode.isEmpty()) {
            throw Exception("File code not found in URL")
        }

        val service = Extractor.createGsonService<Service>(mainUrl)

        val responseBody = service.getStream(
            fileCode = fileCode,
            referer = "$mainUrl/v/$fileCode"
        )
        val responseString = responseBody.string()

        val jsonObject = try {
            JsonParser.parseString(responseString).asJsonObject
        } catch (e: Exception) {
            throw Exception("Failed to parse API response: ${e.message}")
        }

        val streamingUrl = jsonObject.get("streaming_url")?.asString
            ?: throw Exception("Streaming URL not found in API response")

        val defaultSub = jsonObject.get("default_sub_lang")?.asString?:""
        var alreadySelect = false
        val subtitles = jsonObject.getAsJsonArray("subtitles")
            ?.map { elem ->
                val obj = elem.asJsonObject
                val label = obj.get("language")?.asString?:""
                Video.Subtitle(
                    label = label,
                    file = obj.get("file_path")?.asString?:"",
                    default = if (alreadySelect == false && defaultSub.isNotEmpty() && label.contains(
                            defaultSub
                        )
                    ) {
                        alreadySelect = true
                        true
                    } else {
                        false
                    }
                )
            } ?: emptyList()

        return Video(
            source = streamingUrl,
            subtitles = subtitles
        )
    }

    private interface Service {
        @GET("ajax/stream")
        suspend fun getStream(
            @Query("filecode") fileCode: String,
            @Header("Referer") referer: String
        ): ResponseBody
    }
}
