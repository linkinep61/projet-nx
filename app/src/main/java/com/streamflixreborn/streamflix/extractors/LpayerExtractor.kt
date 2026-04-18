package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class LpayerExtractor : Extractor() {
    override val name = "Lpayer"
    override val mainUrl = "https://lpayer.embed4me.com"
    override val aliasUrls = listOf(
        "https://embed4me.com",
        "https://lpayer.embed4me.",
    )

    val context = StreamFlixApp.instance.applicationContext

    override suspend fun extract(link: String): Video {
        Log.e("Lpayer", "Extracting from: $link")

        val videoUrl = interceptVideoFromWebView(link)
            ?: throw Exception("Could not find video source in Lpayer (timeout or no media URL)")

        Log.e("Lpayer", "Found video URL: ${videoUrl.take(120)}")

        // Include cookies from the WebView session — may be needed for m3u8 auth
        val cookies = try {
            CookieManager.getInstance().getCookie("https://lpayer.embed4me.com") ?: ""
        } catch (_: Exception) { "" }

        val headers = mutableMapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT
        )
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies
            Log.e("Lpayer", "Including cookies: ${cookies.take(120)}")
        }

        return Video(
            source = videoUrl,
            headers = headers
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun interceptVideoFromWebView(url: String): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000) {
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
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = USER_AGENT
                        settings.databaseEnabled = true
                        settings.allowContentAccess = true
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                    }

                    // Give offscreen WebView a layout size so touch events work
                    webView.layout(0, 0, 1080, 1920)

                    // Track cookies for the final Video headers
                    var capturedCookies = ""

                    // JS bridge: lets injected JS call back when it finds the m3u8 URL
                    webView.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onSourceFound(sourceUrl: String) {
                            Log.e("Lpayer", "Lpayer JS bridge found source: ${sourceUrl.take(120)}")
                            if (sourceUrl.startsWith("http")
                                && (sourceUrl.contains(".m3u8") || sourceUrl.contains(".mp4"))
                                && !sourceUrl.contains("preload.m3u8")
                            ) {
                                // Capture cookies before resolving
                                try {
                                    capturedCookies = CookieManager.getInstance()
                                        .getCookie("https://lpayer.embed4me.com") ?: ""
                                } catch (_: Exception) {}
                                resolve(sourceUrl)
                                webView.post {
                                    webView.stopLoading()
                                    webView.destroy()
                                }
                            }
                        }
                    }, "LpayerBridge")

                    webView.webViewClient = object : WebViewClient() {

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            super.onPageStarted(view, url, favicon)
                            Log.e("Lpayer", "onPageStarted: resolved=$resolved view=${view != null}")
                            if (view == null || resolved) return
                            Log.e("Lpayer", "onPageStarted: injecting hooks")
                            view.evaluateJavascript(FETCH_HOOK_JS, null)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val urlPath = request.url?.path ?: ""
                            val host = request.url?.host ?: ""

                            Log.e("Lpayer", "req: ${reqUrl.take(120)}")

                            // === STRATEGY 1: Intercept real m3u8 requests ===
                            // Skip preload.m3u8 (placeholder before decryption)
                            if (reqUrl.contains(".m3u8") && !reqUrl.contains("preload.m3u8")) {
                                Log.e("Lpayer", "INTERCEPTED m3u8: $reqUrl")
                                view?.post { resolve(reqUrl) }
                                return null
                            }

                            // === STRATEGY 2: Intercept mp4 requests (path only) ===
                            if (urlPath.endsWith(".mp4")) {
                                Log.e("Lpayer", "INTERCEPTED mp4: $reqUrl")
                                view?.post { resolve(reqUrl) }
                                return null
                            }

                            // === STRATEGY 3: Intercept .ts segment → derive m3u8 ===
                            if (urlPath.matches(Regex(".*\\.ts$")) && host.contains("embed4me")) {
                                val m3u8Url = reqUrl.substringBeforeLast("/") + "/master.m3u8"
                                Log.e("Lpayer", "INTERCEPTED TS → m3u8: $m3u8Url")
                                view?.post { resolve(m3u8Url) }
                                return null
                            }

                            // === Block ad/tracking domains ===
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            Log.e("Lpayer", "onPageFinished: resolved=$resolved view=${view != null}")
                            if (view == null || resolved) return

                            view.evaluateJavascript(FETCH_HOOK_JS, null)
                            Log.e("Lpayer", "Injecting poll+play JS")
                            view.evaluateJavascript(POLL_AND_PLAY_JS, null)

                            // Schedule real touch events to trigger Vidstack's
                            // trusted-click gated loading flow.
                            // Multiple attempts with increasing delays.
                            val handler = Handler(Looper.getMainLooper())
                            for (delay in longArrayOf(2000, 4000, 6000, 8000, 10000)) {
                                handler.postDelayed({
                                    if (!resolved) {
                                        Log.e("Lpayer", "Simulating real click (delay=${delay}ms)")
                                        simulateClick(view)
                                    }
                                }, delay)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                Log.e("Lpayer", "Main frame error: ${error?.description} (${error?.errorCode})")
                            }
                        }
                    }

                    Log.e("Lpayer", "Lpayer Loading page: $url")
                    webView.loadUrl(url)

                    cont.invokeOnCancellation {
                        resolved = true
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
        }

    /**
     * Dispatch real MotionEvent (isTrusted=true) on the WebView center.
     * This bypasses Vidstack's isTrusted check on click events.
     */
    private fun simulateClick(view: WebView) {
        try {
            val x = view.width / 2f
            val y = view.height / 2f
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0
            )
            val up = MotionEvent.obtain(
                downTime, downTime + 80, MotionEvent.ACTION_UP, x, y, 0
            )
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            Log.e("Lpayer", "Real touch dispatched at ($x, $y)")
        } catch (e: Exception) {
            Log.e("Lpayer", "simulateClick error: ${e.message}")
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val BLOCKED_HOSTS = listOf(
            "googlesyndication", "google-analytics", "doubleclick",
            "adservice", "mc.yandex.ru", "cloudflareinsights",
            "imasdk.googleapis.com", "googletagmanager",
            "marketdeathly", "brisknessdebtordismiss",
            "cardboardcrispyrover", "polosanitizertrusting",
            "festivitynextrocker", "popads", "popunder",
        )

        /**
         * JS hook injected in onPageStarted BEFORE page JS runs.
         * Hooks:
         * 1. crypto.subtle.importKey → captures AES decryption key
         * 2. crypto.subtle.decrypt → captures decrypted result (m3u8 URL)
         * 3. fetch() → captures /api/ response (encrypted data) + m3u8/mp4 URLs
         * 4. XMLHttpRequest.open() → captures m3u8 URLs
         */
        private const val FETCH_HOOK_JS = """
            (function() {
                if (window.__lpHooked) return;
                window.__lpHooked = true;
                window.__lp = { key: null, algoName: null, enc: null };

                try { LpayerBridge.onSourceFound('LP_HOOK_START'); } catch(e) {}

                // === Hook fetch FIRST (safe, no crypto dependency) ===
                try {
                    var _fetch = window.fetch;
                    window.fetch = function() {
                        var url = (typeof arguments[0] === 'string') ? arguments[0] :
                                  (arguments[0] && arguments[0].url) ? arguments[0].url : '';
                        if (url && url.indexOf('.m3u8') > -1 && url.indexOf('preload') === -1) {
                            try { LpayerBridge.onSourceFound(url); } catch(e) {}
                        }
                        if (url && url.indexOf('.mp4') > -1 && url.indexOf('google') === -1) {
                            try { LpayerBridge.onSourceFound(url); } catch(e) {}
                        }
                        var result = _fetch.apply(this, arguments);
                        if (url && url.indexOf('/api/') > -1) {
                            result.then(function(r) { return r.clone().text(); })
                                  .then(function(t) {
                                      window.__lp.enc = t;
                                      try { LpayerBridge.onSourceFound('LP_API:' + t.length); } catch(e) {}
                                  }).catch(function(){});
                        }
                        return result;
                    };
                    try { LpayerBridge.onSourceFound('LP_FETCH_OK'); } catch(e) {}
                } catch(e) {
                    try { LpayerBridge.onSourceFound('LP_FETCH_ERR:' + e.message); } catch(x) {}
                }

                // === Hook XHR (safe) ===
                try {
                    var _xo = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function() {
                        var url = arguments[1] || '';
                        if (url.indexOf('.m3u8') > -1 && url.indexOf('preload') === -1) {
                            try { LpayerBridge.onSourceFound(url); } catch(e) {}
                        }
                        return _xo.apply(this, arguments);
                    };
                } catch(e) {}

                // === Hook crypto.subtle (wrapped in try-catch — may not be available) ===
                try {
                    if (crypto && crypto.subtle) {
                        // Hook importKey
                        var _ik = crypto.subtle.importKey.bind(crypto.subtle);
                        crypto.subtle.importKey = function(fmt, kd, algo, ext, usages) {
                            var p = _ik(fmt, kd, algo, ext, usages);
                            if (usages && usages.indexOf && usages.indexOf('decrypt') > -1) {
                                p.then(function(k) {
                                    window.__lp.key = k;
                                    window.__lp.algoName = (typeof algo === 'string') ? algo : (algo.name || '');
                                    try { LpayerBridge.onSourceFound('LP_KEY:' + window.__lp.algoName); } catch(e) {}
                                }).catch(function(){});
                            }
                            return p;
                        };

                        // Hook decrypt — parse JSON, construct m3u8 URL from poster path
                        var _dec = crypto.subtle.decrypt.bind(crypto.subtle);
                        crypto.subtle.decrypt = function(algo, key, data) {
                            return _dec(algo, key, data).then(function(result) {
                                try {
                                    var t = new TextDecoder().decode(result);
                                    // Direct m3u8 URL match
                                    var m = t.match(/https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/);
                                    if (m) { LpayerBridge.onSourceFound(m[0]); return result; }
                                    // Parse JSON and construct m3u8 URL from poster path
                                    try {
                                        var obj = JSON.parse(t);
                                        LpayerBridge.onSourceFound('LP_JSON_KEYS:' + Object.keys(obj).join(','));
                                        // Log poster-based URL for diagnostics only — do NOT resolve with it.
                                        // The poster token differs from the video token so the URL is invalid.
                                        // Let the WebView continue so we can intercept the real m3u8.
                                        if (obj.poster && typeof obj.poster === 'string') {
                                            var p = obj.poster;
                                            var hlsPath = '/hls' + p.replace('/poster.png', '/tt/master.m3u8');
                                            var m3u8Url = 'https://lpayer.embed4me.com' + hlsPath;
                                            LpayerBridge.onSourceFound('LP_BUILT_URL:' + m3u8Url);
                                            window.__lp.posterPath = p;
                                        }
                                        if (obj.title) LpayerBridge.onSourceFound('LP_TITLE:' + obj.title);
                                    } catch(pe) {
                                        LpayerBridge.onSourceFound('LP_DEC:' + t.substring(0, 800));
                                    }
                                } catch(e) {}
                                return result;
                            });
                        };
                        try { LpayerBridge.onSourceFound('LP_CRYPTO_OK'); } catch(e) {}
                    } else {
                        try { LpayerBridge.onSourc