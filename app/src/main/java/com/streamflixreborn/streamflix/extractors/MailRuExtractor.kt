package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class MailRuExtractor : Extractor() {
    override val name = "MailRu"
    override val mainUrl = "https://my.mail.ru"

    private val client = OkHttpClient.Builder().build()

    override suspend fun extract(link: String): Video {
        val videoId = extractVideoId(link) ?: throw Exception("Could not extract video ID from URL")

        val timestamp = System.currentTimeMillis()

        val metaUrl = "$mainUrl/+/video/meta/$videoId?xemail=&ajax_call=1&func_name=&mna=&mnb=&ext=1&_=$timestamp"

        val request = Request.Builder()
            .url(metaUrl)
            .build()

        val responseBody = withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Failed to fetch video metadata (${response.code})")
            }
            response.body?.string()
                ?: throw Exception("Empty response body")
        }

        val jsonResponse = JsonParser.parseString(responseBody).asJsonObject
        val videos = jsonResponse.getAsJsonArray("videos")

        if (videos == null || videos.size() == 0) {
            throw Exception("No videos found in response")
        }

        // Get the first available video with a URL
        val selectedVideo = (0 until videos.size())
            .map { videos.get(it).asJsonObject }
            .firstOrNull { (it.get("url")?.asString ?: "").isNotEmpty() }
            ?: throw Exception("No valid videos found")

        val streamUrl = selectedVideo.get("url")?.asString ?: ""
        val finalUrl = when {
            streamUrl.startsWith("//") -> "https:$streamUrl"
            streamUrl.startsWith("http") -> streamUrl
            else -> throw Exception("Invalid stream URL format")
        }

        return Video(
            source = finalUrl,
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to DEFAULT_USER_AGENT
            )
        )
    }

    private fun extractVideoId(link: String): String? {
        // Support both embed/ID and mail.ru video page URLs
        val embedPattern = Regex("embed/([0-9]+)")
        return embedPattern.find(link)?.groupValues?.get(1)
    }
}
