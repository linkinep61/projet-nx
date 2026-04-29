package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SeekPlaysExtractor : Extractor() {

    override val name = "SeekPlays"
    override val mainUrl = "https://seekplays.pro"
    override val rotatingDomain: List<Regex> = listOf(
        Regex("""[a-z]+\.seekplays\.pro""")
    )

    private val client = Extractor.sharedClient

    override suspend fun extract(link: String): Video {
        return withContext(Dispatchers.IO) {
            val uri = URI(link)
            val baseUrl = "${uri.scheme}://${uri.host}"
            val videoId = uri.fragment ?: uri.path.substringAfterLast("/")

            // Step 1: Fetch the encrypted video info
            val infoUrl = "$baseUrl/api/v1/info?id=$videoId"
            val infoRequest = Request.Builder()
                .url(infoUrl)
                .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                .header("Referer", link)
                .build()

            val encryptedHex = client.newCall(infoRequest).execute().use { response ->
                response.body?.string() ?: throw Exception("Failed to load SeekPlays info")
            }

            // Step 2: Derive key from subdomain
            // The subdomain (e.g., "sebbjokey") is used to derive the AES key
            val subdomain = uri.host.substringBefore(".")
            val key = deriveKey(subdomain)
            val iv = deriveIV(subdomain)

            // Step 3: Decrypt the data
            val decryptedJson = try {
                val encryptedBytes = hexToBytes(encryptedHex.trim())
                decrypt(encryptedBytes, key, iv)
            } catch (e: Exception) {
                // Try alternative: first 16 bytes as IV, rest as ciphertext
                try {
                    val allBytes = hexToBytes(encryptedHex.trim())
                    val ivBytes = allBytes.copyOfRange(0, 16)
                    val cipherBytes = allBytes.copyOfRange(16, allBytes.size)
                    decryptWithIV(cipherBytes, key, ivBytes)
                } catch (e2: Exception) {
                    // Fallback: try to fetch direct video endpoint
                    return@withContext fetchDirectVideo(baseUrl, videoId, link)
                }
            }

            // Step 4: Parse the JSON to get video URL
            val json = JSONObject(decryptedJson)
            val videoUrl = json.optString("superPlayer")
                .ifEmpty { json.optString("httpStream") }
                .ifEmpty { json.optString("file") }
                .ifEmpty { json.optString("url") }
                .ifEmpty { json.optString("source") }

            if (videoUrl.isEmpty()) {
                throw Exception("No video URL found in SeekPlays response")
            }

            Video(
                source = videoUrl,
                headers = mapOf("Referer" to baseUrl)
            )
        }
    }

    private suspend fun fetchDirectVideo(baseUrl: String, videoId: String, referer: String): Video {
        // Try the /api/v1/video endpoint as fallback
        val videoUrl = "$baseUrl/api/v1/video?id=$videoId"
        val request = Request.Builder()
            .url(videoUrl)
            .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
            .header("Referer", referer)
            .build()

        val response = client.newCall(request).execute().use { resp ->
            resp.body?.string() ?: throw Exception("Failed to fetch SeekPlays video endpoint")
        }

        // Try to parse as JSON
        try {
            val json = JSONObject(response)
            val url = json.optString("url")
                .ifEmpty { json.optString("source") }
                .ifEmpty { json.optString("file") }

            if (url.isNotEmpty()) {
                return Video(source = url, headers = mapOf("Referer" to baseUrl))
            }
        } catch (_: Exception) {}

        // Try regex for direct URLs
        val urlMatch = Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""")
            .find(response)
            ?: throw Exception("Could not extract SeekPlays video URL")

        return Video(
            source = urlMatch.groupValues[1],
            headers = mapOf("Referer" to baseUrl)
        )
    }

    private fun deriveKey(subdomain: String): ByteArray {
        // Pad or truncate subdomain to 16 bytes for AES-128 key
        val padded = subdomain.padEnd(16, '0').take(16)
        return padded.toByteArray(Charsets.UTF_8)
    }

    private fun deriveIV(subdomain: String): ByteArray {
        // Reverse subdomain and pad for IV
        val reversed = subdomain.reversed().padEnd(16, '0').take(16)
        return reversed.toByteArray(Charsets.UTF_8)
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private fun decryptWithIV(data: ByteArray, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(Regex("[^0-9a-fA-F]"), "")
        val len = cleanHex.length
        val result = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            result[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) +
                    Character.digit(cleanHex[i + 1], 16)).toByte()
        }
        return result
    }

}
