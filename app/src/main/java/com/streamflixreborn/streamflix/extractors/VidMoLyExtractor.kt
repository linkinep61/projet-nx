package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Message
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

open class VidMoLyExtractor : Extractor() {
    override val name = "VidMoLy"
    override val mainUrl = "https://vidmoly.me/"
    override val aliasUrls = listOf(
        "https://vidmoly.net",
        "https://vidmoly.biz",
        "https://vidmoly.to",
    )

    private val context = StreamFlixApp.instance.applicationContext

    override suspend fun extract(link: String): Video {
        // vidmoly.to is heavily rate-limited (429) — use .biz which works
        val normalizedLink = if (link.contains("vidmoly"))
            link.replace(Regex("vidmoly\\.(to|me|net)"), "vidmoly.biz")
        else link

        val hlsUrl = extractByIntercepting(normalizedLink)
        if (hlsUrl != null) {
            return buildVideo(hlsUrl, normalizedLink)
        }

        throw Exception("VidMoLy: Could not find HLS source in page: $link")
    }

    private fun buildVideo(hlsUrl: String, pageUrl: String): Video {
        val host = Uri.parse(pageUrl).host ?: "vidmoly.to"
        return Video(
            source = hlsUrl,
            headers = mapOf(
                "Referer" to "https://$host/",
                "Origin" to "https://$host",
                "User-Agent" to USER_AGENT,
            )
        )
    }

    /**
     * Load the actual embed page in WebView with JS enabled.
     * Intercept network requests to capture m3u8 URL when JWPlayer loads it.
     * Also runs JS extraction as backup after page load.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractByIntercepting(url: String): String? =
        withContext(Dispatchers.Main) {
            val refererHost = Uri.parse(url).host ?: "vidmoly.to"
            val referer = "https://$refererHost/"

            withTimeoutOrNull(25_000) {
                suspendCancellableCoroutine { cont ->
                    var resolved = false

                    fun resolve(value: String?) {
                        if (!resolved && cont.isActive) {
                            resolved = true
                            cont.resume(value)
                        }
                    }

                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = USER_AGENT
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                    }

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean,
                            isUserGesture: Boolean, resultMsg: Message?
                        ): Boolean = false
                    }

                    webView.webViewClient = object : WebViewClient() {

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val reqUrl = request?.url?.toString() ?: return true
                            val host = request.url?.host ?: ""
                            // Allow main vidmoly domains, block ww1/ww2/etc ad redirects
                            val isMainVidmoly = host.matches(Regex("vidmoly\\.(to|biz|me|net)"))
                            if (isMainVidmoly) return false
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null

                            if (reqUrl.contains(".m3u8")) {
                                view?.post { resolve(reqUrl) }
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            val host = request.url?.host ?: ""
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return

                            // Check if this is a JS challenge page (small HTML with window.location.replace)
                            // If so, extract the redirect URL and manually navigate to it
                            view.evaluateJavascript(
                                "(function(){ var h = document.documentElement.outerHTML; if (h.length < 1000 && h.indexOf('window.location') > -1) { var m = h.match(/window\\.location\\.replace\\(['\"]([^'\"]+)['\"]/); if (m) return m[1]; } return null; })()"
                            ) { result ->
                                val redirectUrl = result?.trim()
                                    ?.removeSurrounding("\"")
                                    ?.takeIf { it != "null" && it.startsWith("http") }
                                if (redirectUrl != null) {
                                    view.loadUrl(redirectUrl, mapOf("Referer" to referer))
                                    return@evaluateJavascript
                                }

                                // Not a challenge page — check for m3u8
                                if (resolved) return@evaluateJavascript

                                // Try immediate extraction
                                view.evaluateJavascript(EXTRACT_M3U8_JS) { m3u8Result ->
                                    val extracted = m3u8Result?.trim()
                                        ?.removeSurrounding("\"")
                                        ?.takeIf { it != "null" && it.contains(".m3u8") }
                                    if (extracted != null) {
                                        resolve(extracted)
                                    } else {
                                        // Retry after delay (JWPlayer may need time to init)
                                        view.postDelayed({
                                            if (resolved) return@postDelayed
                                            view.evaluateJavascript(EXTRACT_M3U8_JS) { retryResult ->
                                                val retryExtracted = retryResult?.trim()
                                                    ?.removeSurrounding("\"")
                                                    ?.takeIf { it != "null" && it.contains(".m3u8") }
                                                if (retryExtracted != null) {
                                                    resolve(retryExtracted)
                                                }
                                            }
                                        }, 3000)
                                    }
                                }
                            }

                            // Final timeout — resolve null after 12s
                            view.postDelayed({
                                if (!resolved) {
                                    resolve(null)
                                }
                            }, 12000)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            // Main frame errors handled silently
                        }
                    }

                    webView.loadUrl(url, mapOf("Referer" to referer))

                    cont.invokeOnCancellation {
                        resolved = true
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
        }

    class ToDomain : VidMoLyExtractor() {
        override val mainUrl: String = "https://vidmoly.to/"
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val BLOCKED_HOSTS = listOf(
            "ww1.vidmoly", "ww2.vidmoly", "ww3.vidmoly", "ww4.vidmoly", "ww5.vidmoly",
            "googlesyndication", "doubleclick", "adservice",
            "popads", "searchresultsworld", "ad.plus",
            "juicyads", "exoclick", "trafficjunky",
            "popunder", "popcash", "propellerads",
            "bvtpk.com", "videocdnshop.com",
            "mc.yandex.ru", "clksite.com", "clkurl.com",
        )

        /**
         * Synchronous JS to extract m3u8 from page DOM.
         */
        private const val EXTRACT_M3U8_JS = """
            (function() {
                try {
                    var scripts = document.querySelectorAll('script');
                    for (var i = 0; i < scripts.length; i++) {
                        var text = scripts[i].textContent;
                        if (text && text.indexOf('m3u8') > -1) {
                            var match = text.match(/sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]*\.m3u8[^'"]*)['"]/);
                            if (match) return match[1];
                            match = text.match(/file\s*:\s*['"]([^'"]*\.m3u8[^'"]*)['"]/);
                            if (match) return match[1];
                            match = text.match(/['"]([^'"]*\.m3u8[^'"]*)['"]/);
                            if (match) return match[1];
                        }
                    }
                    var html = document.documentElement.innerHTML;
                    var match = html.match(/['"]([^'"]*\.m3u8[^'"]*)['"]/);
                    if (match) return match[1];
                } catch(e) {
                    return 'ERROR:' + e.message;
                }
                return null;
            })();
        """
    }
}
