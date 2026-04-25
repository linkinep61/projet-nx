package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * LuluVdo/LuluStream extractor — uses a hidden WebView to bypass CDN protection.
 *
 * CDN (cdn-tnmr.org) blocks all non-WebView clients with 403.
 * Strategy: fetch M3U8 content via WebView's JS fetch() API, then serve
 * it to ExoPlayer as a data URI. ExoPlayer reads the manifest locally
 * and fetches .ts segments from CDN (which aren't token-protected).
 */
class LuluVdoExtractor : Extractor() {

    override val name = "LuluVdo"
    override val mainUrl = "https://luluvdo.com/"
    override val aliasUrls = listOf("https://luluvdoo.com", "https://luluvid.com", "https://lulustream.com")

    companion object {
        private const val TAG = "LuluVdoExtractor"
        private const val TIMEOUT_MS = 25_000L

        /**
         * Shared WebView kept alive after extraction so that WebViewDataSource
         * can reuse it for fetching .ts segments from the same CDN origin.
         * The WebView has the correct origin, cookies, and TLS fingerprint.
         */
        @Volatile
        var sharedWebView: WebView? = null
            private set

        /** Release the shared WebView (call from player on destroy). */
        fun releaseSharedWebView() {
            val wv = sharedWebView ?: return
            sharedWebView = null
            Handler(Looper.getMainLooper()).post {
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {}
            }
            Log.d(TAG, "Shared WebView released")
        }
    }

    override suspend fun extract(link: String): Video {
        val linkHost = try {
            java.net.URL(link).let { "${it.protocol}://${it.host}" }
        } catch (_: Exception) { mainUrl.trimEnd('/') }

        val fileCode = link.trimEnd('/').substringAfterLast("/").substringBefore(".")
        Log.d(TAG, "Extracting file_code=$fileCode from $linkHost")

        val result = resolveViaWebView(linkHost, fileCode)
            ?: throw Exception("WebView extraction timed out or failed for LuluVdo")

        val headers = mutableMapOf(
            "Referer" to "$linkHost/",
            "Origin" to linkHost,
            "Accept" to "*/*",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
        )

        val source: String
        val mimeType: String?

        if (result.m3u8Content != null) {
            Log.d(TAG, "M3U8 content captured (${result.m3u8Content.length} chars) → data URI")
            Log.d(TAG, "Raw M3U8:\n${result.m3u8Content}")

            // Rewrite relative URLs to absolute so ExoPlayer can resolve them
            // from the data: URI (which has no base path)
            val baseUrl = result.m3u8Url.substringBefore("?").substringBeforeLast("/") + "/"
            val rewrittenContent = result.m3u8Content.lines().joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("#") && !trimmed.startsWith("http")) {
                    baseUrl + trimmed
                } else {
                    line
                }
            }
            Log.d(TAG, "Rewritten M3U8:\n$rewrittenContent")

            val encoded = Base64.encodeToString(rewrittenContent.toByteArray(), Base64.NO_WRAP)
            source = "data:application/vnd.apple.mpegurl;base64,$encoded"
            mimeType = MimeTypes.APPLICATION_M3U8
        } else {
            Log.w(TAG, "No M3U8 content captured, using URL: ${result.m3u8Url.take(80)}")
            source = result.m3u8Url
            mimeType = if (source.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null
        }

        return Video(
            source = source,
            subtitles = result.subtitles,
            type = mimeType,
            headers = headers,
            webViewUrl = link
        )
    }

    private data class ExtractionResult(
        val m3u8Url: String,
        val m3u8Content: String? = null,
        val subtitles: List<Video.Subtitle> = emptyList()
    )

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveViaWebView(host: String, fileCode: String): ExtractionResult? {
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val mainHandler = Handler(Looper.getMainLooper())
                    var webView: WebView? = null
                    var resolved = false
                    var capturedM3u8Url: String? = null  // Only process first M3U8 URL
                    var allowXhrThrough = false  // When true, let our XHR request pass through
                    var capturedSubtitles: List<Video.Subtitle> = emptyList()
                    var pendingM3u8Fetch = false  // Set true after navigating to blank.html
                    var inPlaybackMode = false  // When true, allow all requests through

                    fun cleanup(keepWebView: Boolean = false) {
                        if (keepWebView) {
                            // Keep the WebView alive for WebViewDataSource to reuse
                            // Don't reset WebViewClient — just switch to playback mode
                            // so shouldInterceptRequest allows all requests through
                            inPlaybackMode = true
                            Log.d(TAG, "Keeping WebView alive for segment fetching (playback mode, file:// origin)")
                            sharedWebView = webView
                        } else {
                            mainHandler.post {
                                try {
                                    webView?.stopLoading()
                                    webView?.destroy()
                                    webView = null
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    fun resolve(result: ExtractionResult) {
                        if (resolved) return
                        resolved = true
                        Log.d(TAG, "Resolved: url=${result.m3u8Url.take(80)}, hasContent=${result.m3u8Content != null}")
                        // Keep WebView alive if we captured M3U8 content (data URI mode)
                        // WebViewDataSource will use it to fetch .ts segments
                        cleanup(keepWebView = result.m3u8Content != null)
                        if (continuation.isActive) continuation.resume(result)
                    }

                    continuation.invokeOnCancellation { cleanup() }

                    try {
                        val wv = WebView(com.streamflixreborn.streamflix.StreamFlixApp.instance)
                        webView = wv

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            userAgentString = NetworkClient.USER_AGENT
                            mediaPlaybackRequiresUserGesture = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            @Suppress("DEPRECATION")
                            allowUniversalAccessFromFileURLs = true
                        }

                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url?.toString() ?: return null

                                // In playback mode, let ALL requests through
                                // (WebViewDataSource needs .ts and .m3u8 to pass)
                                if (inPlaybackMode) return null

                                // Block .ts segments (don't let WebView download video)
                                if (url.contains(".ts") && url.contains("tnmr.org")) {
                                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                }

                                // Capture FIRST M3U8 URL only
                                if (url.contains(".m3u8") && capturedM3u8Url == null) {
                                    capturedM3u8Url = url
                                    Log.d(TAG, "M3U8 URL captured: ${url.take(100)}")

                                    // Set flag BEFORE starting XHR so shouldInterceptRequest
                                    // lets our XHR request pass through
                                    allowXhrThrough = true

                                    // Extract subtitles while JWPlayer page is still loaded,
                                    // then navigate to file:// page for CORS-free XHR
                                    mainHandler.post {
                                        extractSubtitles(view) { subs ->
                                            capturedSubtitles = subs
                                            pendingM3u8Fetch = true
                                            Log.d(TAG, "Subtitles extracted (${subs.size}), navigating to blank.html for CORS-free fetch")
                                            view?.loadUrl("file:///android_asset/blank.html")
                                        }
                                    }

                                    // Block the original JWPlayer request (don't consume the token)
                                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                }

                                // Block duplicate M3U8 requests (JWPlayer retries)
                                // BUT allow our own XHR request through!
                                if (url.contains(".m3u8") && !allowXhrThrough) {
                                    Log.d(TAG, "Blocking duplicate M3U8 request")
                                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                }
                                if (url.contains(".m3u8") && allowXhrThrough) {
                                    Log.d(TAG, "Allowing XHR M3U8 request through: ${url.take(80)}")
                                }

                                return null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Log.d(TAG, "Page finished: $url")

                                // blank.html loaded → fetch M3U8 via CORS-free XHR (file:// origin)
                                if (pendingM3u8Fetch && url?.startsWith("file:") == true) {
                                    pendingM3u8Fetch = false
                                    val m3u8Url = capturedM3u8Url ?: return
                                    Log.d(TAG, "blank.html ready, starting CORS-free M3U8 fetch from file:// origin")
                                    startM3u8Fetch(view, m3u8Url, mainHandler) { content ->
                                        allowXhrThrough = false
                                        resolve(ExtractionResult(m3u8Url, content, capturedSubtitles))
                                    }
                                }
                            }
                        }

                        val postData = "op=embed&file_code=$fileCode&auto=1&referer="
                        wv.postUrl("$host/dl", postData.toByteArray())

                    } catch (e: Exception) {
                        Log.e(TAG, "WebView setup failed: ${e.message}", e)
                        cleanup()
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }
    }

    /**
     * Fetch M3U8 content using WebView's JS fetch() API via async polling.
     * evaluateJavascript can't await Promises, so we store the result
     * in a window variable and poll for it.
     */
    private fun startM3u8Fetch(
        view: WebView?,
        m3u8Url: String,
        handler: Handler,
        callback: (String?) -> Unit
    ) {
        if (view == null) { callback(null); return }

        Log.d(TAG, "Starting async M3U8 fetch via JS...")

        // Inject fetch call that stores result in a window variable
        view.evaluateJavascript("""
            (function() {
                window.__m3u8 = null;
                window.__m3u8err = null;

                var xhr = new XMLHttpRequest();
                xhr.open('GET', '$m3u8Url', true);
                xhr.withCredentials = true;
                xhr.setRequestHeader('Accept', '*/*');
                xhr.onload = function() {
                    if (xhr.status === 200) {
                        window.__m3u8 = xhr.responseText;
                    } else {
                        window.__m3u8err = 'HTTP ' + xhr.status;
                    }
                };
                xhr.onerror = function() {
                    window.__m3u8err = 'XHR error';
                };
                xhr.send();
            })();
        """.trimIndent(), null)

        // Poll for result
        pollM3u8Result(view, handler, 0, callback)
    }

    private fun pollM3u8Result(
        view: WebView?,
        handler: Handler,
        attempt: Int,
        callback: (String?) -> Unit
    ) {
        if (view == null || attempt > 20) {
            Log.w(TAG, "M3U8 fetch polling exhausted (attempt $attempt)")
            callback(null)
            return
        }

        handler.postDelayed({
            view.evaluateJavascript("""
                (function() {
                    if (window.__m3u8err) return 'ERR:' + window.__m3u8err;
                    if (window.__m3u8) return window.__m3u8;
                    return '';
                })();
            """.trimIndent()) { result ->
                val raw = result?.trim()?.removeSurrounding("\"") ?: ""

                if (raw.startsWith("ERR:")) {
                    Log.e(TAG, "M3U8 XHR failed: $raw")
                    callback(null)
                    return@evaluateJavascript
                }

                if (raw.isNotEmpty() && raw != "null") {
                    val content = raw
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\/", "/")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")

                    if (content.contains("#EXTM3U") || content.contains("#EXT-X-")) {
                        Log.d(TAG, "M3U8 content fetched OK (${content.length} chars, attempt $attempt)")
                        callback(content)
                    } else {
                        Log.w(TAG, "Content not M3U8 (attempt $attempt): ${content.take(100)}")
                        // Keep polling — might not be ready yet
                        pollM3u8Result(view, handler, attempt + 1, callback)
                    }
                    return@evaluateJavascript
                }

                // Not ready yet
                pollM3u8Result(view, handler, attempt + 1, callback)
            }
        }, 500)
    }

    private fun extractSubtitles(view: WebView?, callback: (List<Video.Subtitle>) -> Unit) {
        if (view == null) { callback(emptyList()); return }

        view.evaluateJavascript("""
            (function() {
                if (typeof jwplayer !== 'undefined') {
                    try {
                        var config = jwplayer().getConfig();
                        var tracks = config.tracks || [];
                        return JSON.stringify(tracks.filter(function(t) {
                            return t.kind === 'captions' || t.kind === 'subtitles';
                        }));
                    } catch(e) {}
                }
                return '[]';
            })();
        """.trimIndent()) { tracksJson ->
            callback(parseSubtitles(tracksJson))
        }
    }

    private fun parseSubtitles(jsonStr: String?): List<Video.Subtitle> {
        if (jsonStr.isNullOrBlank() || jsonStr == "null" || jsonStr == "[]") return emptyList()
        return try {
            val clean = jsonStr.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            val arr = org.json.JSONArray(clean)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val file = obj.optString("file", "")
                val label = obj.optString("label", "")
                if (file.isNotBlank() && label != "Upload captions") {
                    Video.Subtitle(label = label, file = file)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Subtitle parsing failed: ${e.message}")
            emptyList()
        }
    }
}
