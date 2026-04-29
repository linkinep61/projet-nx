package com.streamflixreborn.streamflix.extractors

import androidx.media3.common.MimeTypes
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

class GoogleDriveExtractor : Extractor() {

    override val name = "GoogleDrive"
    override val mainUrl = "https://drive.google.com"

    override suspend fun extract(link: String): Video {
        val fileIdMatch = Regex("""/file/d/([^/]+)""").find(link)
        val fileId = fileIdMatch?.groupValues?.get(1)
            ?: throw Exception("File ID not found in URL")

        val service = Extractor.createGsonService<Service>("https://content-workspacevideo-pa.googleapis.com/")

        val responseBody = service.getPlayback(
            fileId = fileId,
            key = "AIzaSyDVQw45DwoYh632gvsP5vPDqEKvb-Ywnb8",
            unique = "gc999"
        )

        val responseString = responseBody.string()
        val jsonObject = JsonParser.parseString(responseString).asJsonObject

        val mediaStreamingData = jsonObject.getAsJsonObject("mediaStreamingData")
        val hlsManifestUrl = mediaStreamingData.get("hlsManifestUrl")?.asString
            ?: throw Exception("HLS manifest URL not found")

        val headers = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Connection" to "keep-alive",
            "Origin" to "https://youtube.googleapis.com",
            "Pragma" to "no-cache",
            "Referer" to "https://youtube.googleapis.com/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Storage-Access" to "active",
            "User-Agent" to Extractor.DEFAULT_USER_AGENT,
            "X-Browser-Channel" to "stable",
            "X-Browser-Copyright" to "Copyright 2025 Google LLC. All Rights reserved.",
            "X-Browser-Validation" to "Aj9fzfu+SaGLBY9Oqr3S7RokOtM=",
            "X-Browser-Year" to "2025",
            "sec-ch-ua" to "\"Chromium\";v=\"142\", \"Google Chrome\";v=\"142\", \"Not_A Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Content-Type" to "application/x-www-form-urlencoded"
        )

        return Video(
            source = hlsManifestUrl,
            headers = headers,
            type = MimeTypes.APPLICATION_M3U8
        )
    }

    private interface Service {
        @GET("v1/drive/media/{fileId}/playback")
        suspend fun getPlayback(
            @Path("fileId") fileId: String,
            @Query("key") key: String,
            @Query("\$unique") unique: String
        ): ResponseBody
    }
}

