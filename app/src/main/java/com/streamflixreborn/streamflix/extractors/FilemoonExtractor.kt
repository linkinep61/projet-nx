package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import org.json.JSONObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

open class FilemoonExtractor : Extractor() {

    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.org"
    override val aliasUrls = listOf("https://bf0skv.org","https://bysejikuar.com","https://moflix-stream.link","https://bysezoxexe.com","https://bysebuho.com","https://filemoon.sx","https://bysekoze.com","https://bysesayeveum.com","https://lukefirst.lol","https://filemoon.site")

    override suspend fun extract(link: String): Video {
        val service = Extractor.createGsonService<Service>(mainUrl)
        // Regex to match /e/ or /d/ and ID
        val matcher = Regex("""/(e|d)/([a-zA-Z0-9]+)""").find(link) 
            ?: throw Exception("Could not extract video ID or type")
        
        val linkType = matcher.groupValues[1]
        val videoId = matcher.groupValues[2]
        
        val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1)
            ?: throw Exception("Could not extract Base URL")

        val detailsUrl = "$currentDomain/api/videos/$videoId/embed/details"
        val details = service.getDetails(detailsUrl)
        val embedFrameUrl = details.embed_frame_url 
            ?: throw Exception("embed_frame_url not found")

        var playbackDomain = ""
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = Extractor.DEFAULT_USER_AGENT
        headers["Accept"] = "application/json"
        headers["Content-Type"] = "application/json"

        if (linkType == "d") {
            playbackDomain = currentDomain
            headers["Referer"] = link
        } else {
            playbackDomain = Regex("""(https?://[^/]+)""").find(embedFrameUrl)?.groupValues?.get(1)
                ?: throw Exception("Could not extract domain from embed_frame_url")
            headers["Referer"] = embedFrameUrl
            // Required by current API (April 2026) — server returns 405 without these.
            // Reverse-engineered from the official SPA Vite bundle (Lt + Nt helpers in
            // videoPagesBundle-CQv1AfZY.js). The X-Embed-* trio identifies the parent
            // frame so the WAF lets the request through.
            headers["X-Embed-Parent"] = link
            headers["X-Embed-Origin"] = playbackDomain
            headers["X-Embed-Referer"] = link
        }

        // POST /api/videos/{id}/embed/playback with a fingerprint body. The server only
        // validates that `fingerprint` is a JSON object with token/viewer_id/device_id/
        // confidence keys; the actual values are not checked, so we send placeholders.
        // Returns AES-256-GCM encrypted JSON with iv/payload/key_parts → decryptPlayback().
        val playbackUrl = "$playbackDomain/api/videos/$videoId/embed/playback"
        val playbackResponse = service.getPlayback(playbackUrl, headers, FingerprintBody())
        val playbackData = playbackResponse.playback
            ?: throw Exception("No playback data")


        val decryptedJson = decryptPlayback(playbackData)
        
        val jsonObject = JSONObject(decryptedJson)
        val sources = jsonObject.optJSONArray("sources")
            ?: throw Exception("No sources found in decrypted data")
            
        if (sources.length() == 0) throw Exception("Empty sources list")
        
        val sourceUrl = sources.getJSONObject(0).getString("url")

        Log.i("StreamFlixES", "[Filemoon] -> Source found: $sourceUrl")

        val videoHeaders = mutableMapOf(
            "Referer" to embedFrameUrl,
            "User-Agent" to Extractor.DEFAULT_USER_AGENT,
            "Origin" to playbackDomain
        )
        return Video(
            source = sourceUrl,
            headers = videoHeaders
        )
    }

    private fun decryptPlayback(data: PlaybackData): String {
        val iv = Base64.decode(data.iv, Base64.URL_SAFE)
        val payload = Base64.decode(data.payload, Base64.URL_SAFE)
        val p1 = Base64.decode(data.key_parts[0], Base64.URL_SAFE)
        val p2 = Base64.decode(data.key_parts[1], Base64.URL_SAFE)
        
        val key = ByteArray(p1.size + p2.size)
        System.arraycopy(p1, 0, key, 0, p1.size)
        System.arraycopy(p2, 0, key, p1.size, p2.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(key, "AES")
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        
        val decryptedBytes = cipher.doFinal(payload)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    class Any(hostUrl: String) : FilemoonExtractor() {
        override val mainUrl = hostUrl
    }

    private interface Service {
        @GET
        suspend fun getDetails(@Url url: String): DetailsResponse

        @POST
        suspend fun getPlayback(
            @Url url: String,
            @HeaderMap headers: Map<String, String>,
            @Body body: FingerprintBody,
        ): PlaybackResponse
    }

    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(
        val iv: String,
        val payload: String,
        val key_parts: List<String>
    )

    /**
     * Body required by the embed/playback endpoint as of April 2026. The server validates
     * the JSON shape (a `fingerprint` object with token/viewer_id/device_id/confidence
     * fields) but doesn't currently check the actual values — placeholders work.
     */
    data class FingerprintBody(
        val fingerprint: Fingerprint = Fingerprint()
    )

    data class Fingerprint(
        val token: String = "x",
        val viewer_id: String = "y",
        val device_id: String = "z",
        val confidence: Double = 0.5,
    )

}
