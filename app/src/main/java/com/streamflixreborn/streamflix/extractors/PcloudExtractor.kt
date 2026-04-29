package com.streamflixreborn.streamflix.extractors

import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

class PcloudExtractor : Extractor() {

    override val name = "Pcloud"
    override val mainUrl = "https://u.pcloud.link"

    override suspend fun extract(link: String): Video {
        val codeMatch = Regex("""code=([^&]+)""").find(link)
        val code = codeMatch?.groupValues?.get(1)
            ?: throw Exception("Code not found in URL")

        val service = Extractor.createGsonService<Service>("https://api.pcloud.com/")

        val responseBody = service.getPublinkDownload(code)
        val responseString = responseBody.string()

        val jsonObject = try {
            JsonParser.parseString(responseString).asJsonObject
        } catch (e: Exception) {
            throw Exception("Failed to parse API response: ${e.message}")
        }

        val path = jsonObject.get("path")?.asString
            ?: throw Exception("Path not found in API response")

        val hostsArray = jsonObject.getAsJsonArray("hosts")
            ?: throw Exception("Hosts not found in API response")

        if (hostsArray.size() == 0) {
            throw Exception("Hosts array is empty")
        }

        val firstHost = hostsArray.get(0).asString

        val downloadlink = "https://$firstHost$path"

        return Video(
            source = downloadlink
        )
    }

    private interface Service {
        @GET("getpublinkdownload")
        suspend fun getPublinkDownload(@Query("code") code: String): ResponseBody
    }
}

