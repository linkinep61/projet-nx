package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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
        .cookieJar(NetworkClient.cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "NetuExtractor"
        private const val TIMEOUT_MS = 20_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * Shared WebView kept alive after extraction so that WebViewDataSource
         * can reuse it for fetching .ts segments from cfglobalcdn.com.
         * French ISPs block cfglobalcdn.com at IP level — WebView's Chromium
         * network stack bypasses this.
         */
        @Volatile
        var sharedWebView: WebView? = null

        /** Last fetched embed page HTML — used by the player overlay to inject
         *  the page via loadDataWithBaseURL instead of re-fetching from the network. */
        @Volatile
        var lastEmbedHtml: String? = null

        /** Base URL of the last embed page (for loadDataWithBaseURL). */
        @Volatile
        var lastEmbedBaseUrl: String? = null

        fun releaseSharedWebView() {
            val wv = sharedWebView ?: return
            sharedWebView = null
            Handler(Looper.getMainLooper()).post {
                try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
            }
            Log.d(TAG, "Shared WebView released")
        }
    }

    override suspend fun extract(link: String): Video {
        val embedUrl = link
            .replace("/watch/", "/e/")
            .replace("/v/", "/e/")
            .replace("/f/", "/e/")
        val baseUrl = java.net.URL(embedUrl).let { "${it.protocol}://${it.host}" }
        val isFrembed = embedUrl.contains("frembed")
        val referer = if (isFrembed) "https://frembed.cyou/" else embedUrl

        Log.d(TAG, "extract() embedUrl=$embedUrl isFrembed=$isFrembed")

        // Phase 1: Extract the M3U8 URL from the page HTML (OkHttp — fast, works through DoH)
        val m3u8Url = withContext(Dispatchers.IO) { extractM3u8Url(embedUrl, baseUrl, referer, isFrembed) }
        Log.d(TAG, "M3U8 URL extracted: ${m3u8Url.take(100)}")

        // Phase 2: If cfglobalcdn URL → go straight to WebView overlay
        // (WebView navigation and Cloudflare proxies both fail against cfglobalcdn's
        //  TLS fingerprinting + anti-bot session requirement, so skip them entirely)
        if (m3u8Url.contains("cfglobalcdn.com")) {
            Log.d(TAG, "cfglobalcdn detected → needsWebViewClick (direct to overlay)")
            return Video(
                source = m3u8Url,
                type = MimeTypes.APPLICATION_M3U8,
                headers = mapOf("Referer" to referer),
                webViewUrl = embedUrl,
                needsWebViewClick = true
            )
        }

        // Fallback: return direct URL (works if ISP doesn't block cfglobalcdn)
        return Video(
            source = m3u8Url,
            headers = mapOf("Referer" to referer)
        )
    }

    /**
     * Fetch M3U8 via a Cloudflare Worker proxy that relays requests to cfglobalcdn.com.
     * The Worker can reach the blocked IP because Cloudflare's servers aren't affected
     * by French ISP blocks.
     *
     * Worker URL format: https://your-worker.workers.dev/?url=ENCODED_URL
     * The Worker fetches the target URL and returns the content.
     * For M3U8 playlists, segment URLs are rewritten to also go through the proxy.
     */
    private suspend fun fetchViaProxy(
        m3u8Url: String, proxyBaseUrl: String, referer: String
    ): Video? = withContext(Dispatchers.IO) {
        val proxiedM3u8Url = "$proxyBaseUrl/?url=${java.net.URLEncoder.encode(m3u8Url, "UTF-8")}"
        Log.d(TAG, "Fetching M3U8 via proxy: ${proxiedM3u8Url.take(100)}")

        val request = Request.Builder()
            .url(proxiedM3u8Url)
            .header("User-Agent", USER_AGENT)
            .build()

        val proxyClient = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val m3u8Content = proxyClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Proxy M3U8 fetch failed: HTTP ${response.code}")
                return@withContext null
            }
            response.body?.string()
        }

        if (m3u8Content.isNullOrBlank() || !m3u8Content.contains("#EXTM3U")) {
            Log.e(TAG, "Proxy returned invalid M3U8 content")
            return@withContext null
        }

        Log.d(TAG, "Proxy M3U8 fetched OK (${m3u8Content.length} chars)")

        // Rewrite segment URLs to go through the proxy too
        val m3u8Base = m3u8Url.substringBefore("?").substringBeforeLast("/") + "/"
        val rewritten = m3u8Content.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isBlank() || trimmed.startsWith("#") -> line
                trimmed.startsWith("http") -> {
                    // Absolute URL → proxy it
                    "$proxyBaseUrl/?url=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
                }
                else -> {
                    // Relative URL → make absolute then proxy it
                    val absUrl = m3u8Base + trimmed
                    "$proxyBaseUrl/?url=${java.net.URLEncoder.encode(absUrl, "UTF-8")}"
                }
            }
        }

        Log.d(TAG, "M3U8 rewritten with proxy URLs (${rewritten.length} chars)")
        val encoded = Base64.encodeToString(rewritten.toByteArray(), Base64.NO_WRAP)

        Video(
            source = "data:application/vnd.apple.mpegurl;base64,$encoded",
            type = MimeTypes.APPLICATION_M3U8,
            headers = mapOf("Referer" to referer)
        )
    }

    /**
     * Extract the M3U8/MP4 URL from the Netu embed page using OkHttp + regex.
     */
    private suspend fun extractM3u8Url(
        embedUrl: String, baseUrl: String, referer: String, isFrembed: Boolean
    ): String {
        val pageRequest = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()

        val pageHtml = client.newCall(pageRequest).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Netu page error: ${response.code}")
            response.body?.string() ?: throw Exception("Empty Netu page")
        }
        Log.d(TAG, "Page loaded (${pageHtml.length} chars)")

        // Save for WebView overlay (player will inject this HTML instead of re-fetching)
        lastEmbedHtml = pageHtml
        lastEmbedBaseUrl = embedUrl

        // Try direct URL patterns
        val directUrl = Regex("""(?:olplayer|player)\.src\(\s*\{\s*src\s*:\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""sources\s*:\s*\[\s*\{\s*(?:src|file)\s*:\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(pageHtml)?.groupValues?.get(1)

        if (directUrl != null) {
            Log.d(TAG, "Direct URL found: ${directUrl.take(80)}")
            return directUrl
        }

        // Try iframe
        val iframeSrc = Regex("""<iframe[^>]+src\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)
            ?.groupValues?.get(1)
            ?.let { if (it.startsWith("//")) "https:$it" else it }
        if (iframeSrc != null && iframeSrc != embedUrl) {
            Log.d(TAG, "Following iframe → $iframeSrc")
            return extractM3u8Url(iframeSrc, baseUrl, referer, isFrembed)
        }

        // Frembed mirrors: no hash/POST API
        if (isFrembed) throw Exception("Netu (Frembed): no video URL found in page")

        // Standard Netu hash/POST flow
        val hash = Regex("""(?:var|let|const)\s+hash\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""data-hash\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""hash\s*:\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
            ?: throw Exception("Could not find hash in Netu page")
        val iss = Regex("""(?:var|let|const)\s+iss\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)

        val formBody = FormBody.Builder().add("hash", hash).add("r", embedUrl)
            .apply { if (iss != null) add("iss", iss) }.build()
        val postRequest = Request.Builder()
            .url("$baseUrl/player/index.php?data=$hash&do=getVideo")
            .post(formBody)
            .header("User-Agent", USER_AGENT)
            .header("Referer", embedUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val postResponse = client.newCall(postRequest).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Netu API error: ${response.code}")
            response.body?.string() ?: throw Exception("Empty Netu API response")
        }

        return try {
            val json = JSONObject(postResponse)
            json.optString("securedLink").ifEmpty { json.optString("videoSource") }
                .ifEmpty { json.optString("file") }.ifEmpty { json.optString("url") }
                .takeIf { it.isNotEmpty() } ?: throw Exception("No video URL in response")
        } catch (e: Exception) {
            Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""").find(postResponse)
                ?.groupValues?.get(1) ?: throw Exception("Could not parse Netu video URL: ${e.message}")
        }
    }

    /**
     * Fetch M3U8 content via a hidden WebView on file:///android_asset/blank.html.
     * WebView's Chromium network stack can reach cfglobalcdn.com even when
     * French ISPs block the IP at network level.
     * Keeps the WebView alive so WebViewDataSource can reuse it for .ts segments.
     */
    /**
     * Fetch M3U8 by navigating the WebView directly to the URL.
     * This uses Chrome's TLS stack (bypasses cfglobalcdn TLS fingerprinting)
     * and avoids CORS issues since it's a navigation, not XHR.
     * The WebView ends up on cfglobalcdn origin, so segment fetches are same-origin.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchM3u8ViaWebView(m3u8Url: String): String? {
        releaseSharedWebView()

        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val handler = Handler(Looper.getMainLooper())
                    var resolved = false

                    fun resolve(content: String?) {
                        if (resolved) return
                        resolved = true
                        if (continuation.isActive) continuation.resume(content)
                    }

                    try {
                        val wv = WebView(com.streamflixreborn.streamflix.StreamFlixApp.instance)

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = NetworkClient.USER_AGENT
                            mediaPlaybackRequiresUserGesture = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }

                        wv.webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url == null || url.startsWith("about:") || url.startsWith("file:")) return

                                Log.d(TAG, "WebView loaded M3U8 URL: ${url.take(80)}")

                                // Extract page content — M3U8 is plain text rendered by WebView
                                view?.evaluateJavascript(
                                    "(document.body.innerText || document.documentElement.innerText || '')"
                                ) { result ->
                                    val raw = result?.trim()?.removeSurrounding("\"") ?: ""
                                    val content = raw
                                        .replace("\\n", "\n")
                                        .replace("\\r", "\r")
                                        .replace("\\/", "/")
                                        .replace("\\\"", "\"")
                                        .replace("\\\\", "\\")

                                    if (content.contains("#EXTM3U") || content.contains("#EXT-X-")) {
                                        Log.d(TAG, "M3U8 extracted via WebView navigation (${content.length} chars)")
                                        sharedWebView = wv
                                        Log.d(TAG, "WebView kept alive on cfglobalcdn origin for segment fetching")
                                        resolve(content)
                                    } else {
                                        Log.e(TAG, "WebView page content is not M3U8: ${content.take(200)}")
                                        handler.post { try { wv.destroy() } catch (_: Exception) {} }
                                        resolve(null)
                                    }
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    Log.e(TAG, "WebView navigation error: ${error?.description}")
                                    handler.post { try { view?.destroy() } catch (_: Exception) {} }
                                    resolve(null)
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                errorResponse: android.webkit.WebResourceResponse?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    Log.e(TAG, "WebView HTTP error: ${errorResponse?.statusCode}")
                                    handler.post { try { view?.destroy() } catch (_: Exception) {} }
                                    resolve(null)
                                }
                            }
                        }

                        continuation.invokeOnCancellation {
                            handler.post { try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {} }
                        }

                        // Navigate directly to M3U8 URL — uses Chrome's TLS stack
                        Log.d(TAG, "WebView navigating to M3U8: ${m3u8Url.take(80)}")
                        wv.loadUrl(m3u8Url, mapOf("Referer" to "https://frembed.cyou/"))

                    } catch (e: Exception) {
                        Log.e(TAG, "WebView setup failed: ${e.message}", e)
                        resolve(null)
                    }
                }
            }
        }
    }
}
