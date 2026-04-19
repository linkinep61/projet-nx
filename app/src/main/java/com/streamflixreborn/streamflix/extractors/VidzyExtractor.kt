package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.streamflixreborn.streamflix.utils.UserPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VidzyExtractor : Extractor() {

    override val name = "Vidzy"
    override val mainUrl = "https://vidzy.org"
    override val aliasUrls = listOf("https://vidzy.live")

    // Match rotating subdomains like u14.vidzy.live
    override val rotatingDomain: List<Regex> = listOf(
        Regex("""u\d+\.vidzy\.(live|org)""")
    )

    companion object {
        private const val TAG = "VidzyExtractor"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

        val client: OkHttpClient = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore[url.host] = cookies
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            })
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    // Do NOT set Accept-Encoding — OkHttp handles gzip automatically
                    // Setting it manually prevents automatic decompression
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-User", "?1")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Connection", "keep-alive")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    fun extractSubtitles(text: String): List<Video.Subtitle> {
        val loadTracksRegex = Regex("""loadTracks\s*\(\s*\[(.*?)]\s*\)""")
        val tracksContent = loadTracksRegex.find(text)?.groupValues?.get(1) ?: return emptyList()

        val objectRegex = Regex("""\{(.*?)\}""")

        return objectRegex.findAll(tracksContent).mapNotNull { match ->
            val obj = match.groupValues[1]

            val label = Regex("""label:'([^']+)'""").find(obj)?.groupValues?.get(1)
            val file = Regex("""src:'([^']+)'""").find(obj)?.groupValues?.get(1)
            val default = Regex("""default:(true|false)""").find(obj)?.groupValues?.get(1)?.toBoolean() ?: false

            if (label == null || file == null || !file.startsWith("http")) return@mapNotNull null
            Video.Subtitle(
                file = file,
                label = label,
                initialDefault = default,
                default = if (UserPreferences.serverAutoSubtitlesDisabled) false else default
            )
        }.toList()
    }

    override suspend fun extract(link: String): Video {
        // If already a direct HLS URL from a vidzy subdomain, use it directly
        if (link.contains(".m3u8") || link.contains("/hls")) {
            Log.d(TAG, "Direct HLS URL detected: $link")
            return Video(
                source = link,
                type = MimeTypes.APPLICATION_M3U8,
                headers = mapOf("Referer" to "https://vidzy.live/")
            )
        }

        Log.d(TAG, "Fetching embed page: $link")

        val html = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(link)
                .build()

            // First request — may return a cookie-setting landing page
            val response1 = client.newCall(request).execute()
            val body1 = response1.body?.string() ?: throw Exception("Empty response from Vidzy")
            Log.d(TAG, "Request 1 - code: ${response1.code}, length: ${body1.length}, cookies: ${cookieStore.size}")

            // Check if we got the real page (has packed JS)
            val hasPacked = body1.contains("function(p,a,c,k,e,d)") || body1.contains("eval(")
            if (hasPacked) {
                Log.d(TAG, "Got full page on first request!")
                body1
            } else {
                // Second request with cookies from first response
                Log.d(TAG, "Landing page detected, retrying with cookies...")
                Thread.sleep(1000) // Small delay to mimic browser behavior
                val response2 = client.newCall(request).execute()
                val body2 = response2.body?.string() ?: throw Exception("Empty response on retry")
                Log.d(TAG, "Request 2 - code: ${response2.code}, length: ${body2.length}")

                // If still no packed JS, try with different referer
                val hasPacked2 = body2.contains("function(p,a,c,k,e,d)") || body2.contains("eval(")
                if (hasPacked2) {
                    body2
                } else {
                    // Third attempt with self-referer
                    Log.d(TAG, "Still no packed JS, trying with self-referer...")
                    val request3 = Request.Builder()
                        .url(link)
                        .header("Referer", link)
                        .build()
                    val response3 = client.newCall(request3).execute()
                    val body3 = response3.body?.string() ?: throw Exception("Empty response on 3rd try")
                    Log.d(TAG, "Request 3 - code: ${response3.code}, length: ${body3.length}")
                    body3
                }
            }
        }

        // Check if we got a Cloudflare challenge page
        if (html.contains("Just a moment") || html.contains("cf-browser-verification")) {
            Log.e(TAG, "Cloudflare challenge detected!")
            throw Exception("Cloudflare challenge - Vidzy blocked")
        }

        // Debug: log snippets to understand page structure
        val hasEval = html.contains("eval(")
        val hasPack = html.contains("function(p,a,c,k,e,d)")
        val hasSplit = html.contains(".split('|')")
        Log.d(TAG, "Page analysis: hasEval=$hasEval, hasPack=$hasPack, hasSplit=$hasSplit")

        // Try multiple approaches to find packed JS
        var unPacked: String? = null

        // Approach 1: Original method - find packed JS after function declaration
        if (hasPack) {
            val packedJS = Regex("""(\}\s*\('.*?'\.split\('\|'\))""")
                .find(html.substringAfter("function(p,a,c,k,e,d)"))
                ?.groupValues?.get(1)
            if (packedJS != null) {
                Log.d(TAG, "Found packed JS (approach 1), unpacking...")
                unPacked = JsUnpacker(packedJS).unpack()
            }
        }

        // Approach 2: Use JsUnpacker directly on the whole HTML
        if (unPacked == null && hasEval) {
            Log.d(TAG, "Trying JsUnpacker on full HTML...")
            // Find eval(function(p,a,c,k,e,d) blocks
            val evalRegex = Regex("""eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.*?)',\s*(\d+),\s*(\d+),\s*'(.*?)'\s*\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            val evalMatch = evalRegex.find(html)
            if (evalMatch != null) {
                Log.d(TAG, "Found eval block, trying to unpack...")
                val fullBlock = html.substring(evalMatch.range.first)
                    .substringAfter("eval(")
                    .let { "(" + it.substringBefore("})") + "})" }
                unPacked = try { JsUnpacker(fullBlock).unpack() } catch (e: Exception) { null }
            }
        }

        // Approach 3: Direct regex on the HTML for the p,a,c,k format
        if (unPacked == null && hasSplit) {
            Log.d(TAG, "Trying direct packed extraction...")
            val directRegex = Regex("""'([^']{100,})'\s*\.split\('\|'\)""")
            val directMatch = directRegex.find(html)
            if (directMatch != null) {
                Log.d(TAG, "Found split block, attempting manual unpack...")
                // Try feeding it to JsUnpacker differently
                val codeBlock = html.substring(
                    maxOf(0, directMatch.range.first - 2000),
                    minOf(html.length, directMatch.range.last + 10)
                )
                unPacked = try { JsUnpacker(codeBlock).unpack() } catch (e: Exception) {
                    Log.e(TAG, "Manual unpack failed: ${e.message}")
                    null
                }
            }
        }

        val searchText = unPacked ?: run {
            Log.d(TAG, "No packed JS unpacked, searching raw HTML")
            // Log first 500 chars for debugging
            Log.d(TAG, "HTML preview: ${html.take(500).replace("\n", " ")}")
            html
        }

        Log.d(TAG, "Searching for stream URL in content (length: ${searchText.length})")

        // Try multiple patterns to find the stream URL
        val streamUrl = listOf(
            Regex("""src\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""source\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https?://u\d+\.vidzy\.[^"'\s]+master\.m3u8[^"'\s]*)"""),
            Regex("""(https?://[^"'\s]+vidzy[^"'\s]+\.m3u8[^"'\s]*)"""),
            Regex("""src\s*:\s*["'](https?://[^"']+)["']"""),
            Regex("""file\s*:\s*["'](https?://[^"']+)["']""")
        ).firstNotNullOfOrNull { regex ->
            regex.find(searchText)?.groupValues?.get(1)
        } ?: throw Exception("No stream URL found in Vidzy response")

        Log.i(TAG, "Stream URL found: $streamUrl")

        val isM3u8 = streamUrl.contains(".m3u8")
        val baseHost = try { URL(link).host } catch (_: Exception) { "vidzy.live" }

        // Collect cookies from our cookie store for the stream host
        val streamHost = try { URL(streamUrl).host } catch (_: Exception) { baseHost }
        // Use the full embed URL as Referer (CDN checks this) + Origin header
        // Override Sec-Fetch headers: NetworkClient.default interceptor sets
        // "document"/"navigate"/"none" which tells the CDN "page navigation" →
        // CDN redirects to homepage. We need "empty"/"cors"/"same-site" like
        // a real browser JS player would send for XHR/fetch requests.
        val headers = mutableMapOf(
            "Referer" to link,
            "Origin" to "https://$baseHost",
            "Accept" to "*/*",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-site",
            "Upgrade-Insecure-Requests" to ""
        )

        // Add cookies for both the embed host and stream host
        val allCookies = mutableListOf<String>()
        cookieStore[baseHost]?.forEach { allCookies.add("${it.name}=${it.value}") }
        if (streamHost != baseHost) {
            cookieStore[streamHost]?.forEach { allCookies.add("${it.name}=${it.value}") }
        }
        if (allCookies.isNotEmpty()) {
            headers["Cookie"] = allCookies.joinToString("; ")
            Log.d(TAG, "Passing ${allCookies.size} cookies to player")
        }

        return Video(
            source = streamUrl,
            type = if (isM3u8) MimeTypes.APPLICATION_M3U8 else null,
            headers = headers,
            subtitles = extractSubtitles(searchText),
            useServerSubtitleSetting = true
        )
    }
}
