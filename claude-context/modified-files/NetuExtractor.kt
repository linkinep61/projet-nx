package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NetuExtractor : Extractor() {

    override val name = "Netu"
    override val mainUrl = "https://waaw1.tv"
    override val aliasUrls = listOf(
        "https://netu.tv",
        "https://hqq.tv",
        "https://waaw.tv",
        "https://hqcloud.to",
        "https://wsaw.to",
        "https://netu.ac",
        "https://hqq.ac",
        "https://waaw.ac",
        "https://waaw.to",
        "https://younetu.org",
        "https://netu.frembed.bond"
    )

    private val client = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun extract(link: String): Video {
        val embedUrl = link
            .replace("/watch/", "/e/")
            .replace("/v/", "/e/")
            .replace("/f/", "/e/")
        val baseUrl = java.net.URL(embedUrl).let { "${it.protocol}://${it.host}" }
        val isFrembed = embedUrl.contains("frembed")

        // For Frembed-hosted Netu mirrors (netu.frembed.bond), use frembed.cyou as Referer
        val referer = if (isFrembed) "https://frembed.cyou/" else embedUrl

        return withContext(Dispatchers.IO) {
            // Step 1: Load the embed page
            val pageRequest = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .build()

            val pageHtml = client.newCall(pageRequest).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Netu page error: ${response.code}")
                response.body?.string() ?: throw Exception("Empty Netu page")
            }

            // Step 2: Always try direct video URL extraction first
            val playerSrcMatch = Regex("""(?:olplayer|player)\.src\(\s*\{\s*src\s*:\s*['"]([^'"]+)['"]""").find(pageHtml)
            val videoMatch = Regex("""sources\s*:\s*\[\s*\{\s*(?:src|file)\s*:\s*['"]([^'"]+)['"]""").find(pageHtml)
            val hlsMatch = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(pageHtml)
            val mp4Match = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(pageHtml)
            val dataMatch = Regex("""data-src\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)

            val directUrl = playerSrcMatch?.groupValues?.get(1)
                ?: videoMatch?.groupValues?.get(1)
                ?: hlsMatch?.groupValues?.get(1)
                ?: mp4Match?.groupValues?.get(1)
                ?: dataMatch?.groupValues?.get(1)

            if (directUrl != null) {
                val rewrittenUrl = rewriteCfglobalcdn(directUrl)
                return@withContext Video(
                    source = rewrittenUrl,
                    headers = mapOf("Referer" to referer)
                )
            }

            // Step 3: Try iframe extraction (e.g. /f/ page wrapping /e/)
            val iframeSrc = Regex("""<iframe[^>]+src\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)
                ?.groupValues?.get(1)
                ?.let { if (it.startsWith("//")) "https:$it" else it }

            if (iframeSrc != null && iframeSrc != embedUrl) {
                return@withContext extract(iframeSrc)
            }

            // Step 4: For Frembed mirrors, don't attempt hash/POST (API doesn't exist)
            if (isFrembed) {
                throw Exception("Netu (Frembed): no video URL found in page")
            }

            // Step 5: Standard Netu hash/POST flow
            val issMatch = Regex("""(?:var|let|const)\s+iss\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)
            val hashMatch = Regex("""(?:var|let|const)\s+hash\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)
                ?: Regex("""data-hash\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)
                ?: Regex("""hash\s*:\s*['"]([^'"]+)['"]""").find(pageHtml)

            val iss = issMatch?.groupValues?.get(1)
            val hash = hashMatch?.groupValues?.get(1)
                ?: throw Exception("Could not find hash in Netu page")

            val formBody = FormBody.Builder()
                .add("hash", hash)
                .add("r", embedUrl)
                .apply { if (iss != null) add("iss", iss) }
                .build()

            val postRequest = Request.Builder()
                .url("$baseUrl/player/index.php?data=$hash&do=getVideo")
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .header("Referer", embedUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val postResponse = client.newCall(postRequest).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Netu API error: ${response.code}")
                response.body?.string() ?: throw Exception("Empty Netu API response")
            }

            // Step 6: Parse response to get video URL
            try {
                val json = JSONObject(postResponse)
                val videoUrl = json.optString("securedLink")
                    .ifEmpty { json.optString("videoSource") }
                    .ifEmpty { json.optString("file") }
                    .ifEmpty { json.optString("url") }
                    .takeIf { it.isNotEmpty() }
                    ?: throw Exception("No video URL in Netu response")

                Video(
                    source = rewriteCfglobalcdn(videoUrl),
                    headers = mapOf("Referer" to baseUrl)
                )
            } catch (e: Exception) {
                // Try regex fallback on response
                val urlMatch = Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""")
                    .find(postResponse)
                    ?: throw Exception("Could not parse Netu video URL: ${e.message}")

                Video(
                    source = rewriteCfglobalcdn(urlMatch.groupValues[1]),
                    headers = mapOf("Referer" to baseUrl)
                )
            }
        }
    }

    /**
     * ISPs in France block cfglobalcdn.com at DNS level.
     * Subdomains like d65f47.cfglobalcdn.com have CNAME → c28.netu.tv → real IP (50.7.230.18).
     * OkHttp's wire-format DoH may not follow CNAME chains properly.
     * This resolver uses Cloudflare's JSON DoH API to resolve the full CNAME chain
     * and returns the final A record IP, keeping the original hostname for TLS/SNI.
     */
    private fun rewriteCfglobalcdn(videoUrl: String): String {
        // No rewrite — keep original cfglobalcdn.com hostname for correct SSL cert
        return videoUrl
    }

    companion object {
        private const val TAG = "NetuExtractor"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
