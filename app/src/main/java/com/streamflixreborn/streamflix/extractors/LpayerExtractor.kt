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
                        try { LpayerBridge.onSourceFound('LP_NO_CRYPTO'); } catch(e) {}
                    }
                } catch(e) {
                    try { LpayerBridge.onSourceFound('LP_CRYPTO_ERR:' + e.message); } catch(x) {}
                }

                // === Hook HTMLMediaElement.src setter to catch source changes ===
                try {
                    var desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                    if (desc && desc.set) {
                        var _origSrcSet = desc.set;
                        Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                            set: function(v) {
                                try {
                                    LpayerBridge.onSourceFound('LP_VSRC:' + (v || '').substring(0, 300));
                                    if (v && v.indexOf('.m3u8') > -1 && v.indexOf('preload') === -1) {
                                        LpayerBridge.onSourceFound(v);
                                    }
                                    if (v && v.indexOf('.mp4') > -1 && v.indexOf('google') === -1) {
                                        LpayerBridge.onSourceFound(v);
                                    }
                                } catch(e) {}
                                return _origSrcSet.call(this, v);
                            },
                            get: desc.get,
                            configurable: true,
                            enumerable: desc.enumerable
                        });
                        LpayerBridge.onSourceFound('LP_SRCHOOK_OK');
                    }
                } catch(e) {
                    try { LpayerBridge.onSourceFound('LP_SRCHOOK_ERR:' + e.message); } catch(x) {}
                }

                // === Hook URL.createObjectURL for MSE-based playback ===
                try {
                    var _cou = URL.createObjectURL.bind(URL);
                    URL.createObjectURL = function(obj) {
                        var url = _cou(obj);
                        if (obj && obj.constructor && obj.constructor.name === 'MediaSource') {
                            try { LpayerBridge.onSourceFound('LP_MSE:' + url); } catch(e) {}
                        }
                        return url;
                    };
                } catch(e) {}

                // === MutationObserver to watch for source attribute changes ===
                try {
                    var mo = new MutationObserver(function(muts) {
                        muts.forEach(function(m) {
                            if (m.type === 'attributes' && m.attributeName === 'src') {
                                var v = m.target.getAttribute('src');
                                if (v) {
                                    try { LpayerBridge.onSourceFound('LP_ATTR:' + v.substring(0, 300)); } catch(e) {}
                                    if (v.indexOf('.m3u8') > -1 && v.indexOf('preload') === -1) {
                                        try { LpayerBridge.onSourceFound(v); } catch(e) {}
                                    }
                                }
                            }
                        });
                    });
                    // Observe the whole document for src attribute changes on any element
                    mo.observe(document.documentElement, { attributes: true, subtree: true, attributeFilter: ['src'] });
                } catch(e) {}

                try { LpayerBridge.onSourceFound('LP_HOOK_DONE'); } catch(e) {}
            })();
        """

        /**
         * Combined poll+play+self-decrypt JS.
         *
         * Flow: page loads → /api/v1/info returns encrypted hex → Vidstack sets preload.m3u8
         * → user clicks Play → JS decrypts via AES (Web Crypto) → sets real m3u8.
         *
         * Problem: dispatchEvent creates isTrusted:false events; decryption is gated behind
         * a trusted click. Solution: we captured the AES key (via importKey hook) and the
         * encrypted API response (via fetch hook). We perform decryption OURSELVES using
         * crypto.subtle.decrypt, bypassing the click gate entirely.
         *
         * Also tries play triggers as fallback (in case some versions don't gate on isTrusted).
         */
        private const val POLL_AND_PLAY_JS = """
            (function() {
                if (window.__lpPolling) return;
                window.__lpPolling = true;
                var att = 0, max = 100, found = false, decTried = false;

                function real(u) {
                    return u && typeof u === 'string' && u.indexOf('.m3u8') > -1 && u.indexOf('preload') === -1;
                }
                function report(u) {
                    if (found) return; found = true;
                    try { LpayerBridge.onSourceFound(u); } catch(e) {}
                }

                // === Self-decrypt: use captured key + encrypted API data ===
                function tryDecrypt() {
                    if (decTried) return;
                    var lp = window.__lp;
                    if (!lp || !lp.key || !lp.enc) return;
                    decTried = true;
                    try {
                        var raw = lp.enc.trim();
                        // Handle possible JSON wrapper
                        if (raw.charAt(0) === '{' || raw.charAt(0) === '"') {
                            try {
                                var j = JSON.parse(raw);
                                raw = j.data || j.encrypted || j.result || j.d || raw;
                            } catch(e) {}
                        }
                        // Convert hex string to bytes
                        var bytes = new Uint8Array(raw.length / 2);
                        for (var i = 0; i < raw.length; i += 2) {
                            bytes[i / 2] = parseInt(raw.substr(i, 2), 16);
                        }
                        var algoName = (lp.algoName || 'AES-CBC').toUpperCase();
                        LpayerBridge.onSourceFound('LP_TRYING:' + algoName + ' len=' + bytes.length);

                        if (algoName.indexOf('GCM') > -1) {
                            // AES-GCM: first 12 bytes = IV, rest = ciphertext+tag
                            var iv = bytes.slice(0, 12);
                            var ct = bytes.slice(12);
                            crypto.subtle.decrypt({name:'AES-GCM', iv:iv}, lp.key, ct.buffer)
                                .then(handleDecrypted)
                                .catch(function(e) {
                                    LpayerBridge.onSourceFound('LP_GCM_ERR:' + e.message);
                                    // Fallback: try full buffer (maybe IV is separate)
                                    crypto.subtle.decrypt({name:'AES-GCM', iv:new Uint8Array(12)}, lp.key, bytes.buffer)
                                        .then(handleDecrypted)
                                        .catch(function(e2) { LpayerBridge.onSourceFound('LP_GCM2_ERR:' + e2.message); });
                                });
                        } else {
                            // AES-CBC: first 16 bytes = IV, rest = ciphertext
                            var iv = bytes.slice(0, 16);
                            var ct = bytes.slice(16);
                            crypto.subtle.decrypt({name:'AES-CBC', iv:iv}, lp.key, ct.buffer)
                                .then(handleDecrypted)
                                .catch(function(e) {
                                    LpayerBridge.onSourceFound('LP_CBC_ERR:' + e.message);
                                    // Fallback: try zero IV
                                    crypto.subtle.decrypt({name:'AES-CBC', iv:new Uint8Array(16)}, lp.key, bytes.buffer)
                                        .then(handleDecrypted)
                                        .catch(function(e2) { LpayerBridge.onSourceFound('LP_CBC2_ERR:' + e2.message); });
                                });
                        }
                    } catch(e) {
                        try { LpayerBridge.onSourceFound('LP_DECERR:' + e.message); } catch(x) {}
                    }
                }

                function handleDecrypted(result) {
                    try {
                        var t = new TextDecoder().decode(result);
                        // Direct m3u8 URL match
                        var m = t.match(/https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/);
                        if (m) { report(m[0]); return; }
                        // Parse JSON — log keys and search for video URL components
                        try {
                            var obj = JSON.parse(t);
                            var keys = Object.keys(obj);
                            LpayerBridge.onSourceFound('LP_SELF_KEYS:' + keys.join(','));
                            for (var i = 0; i < keys.length; i++) {
                                var k = keys[i];
                                var v = obj[k];
                                if (typeof v === 'string' && k !== 'ads') {
                                    LpayerBridge.onSourceFound('LP_SF_' + k + ':' + v.substring(0, 300));
                                } else if (typeof v === 'object' && v !== null) {
                                    LpayerBridge.onSourceFound('LP_SO_' + k + ':' + JSON.stringify(v).substring(0, 300));
                                } else if (k !== 'ads') {
                                    LpayerBridge.onSourceFound('LP_SV_' + k + ':' + String(v));
                                }
                            }
                            // Also search full stringified JSON for URL patterns
                            var s = JSON.stringify(obj);
                            var m2 = s.match(/https?:\/\/[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*/);
                            if (m2) { report(m2[0]); return; }
                            // Search for /hls/ pattern
                            var m3 = s.match(/\/hls\/[^"'\\]+/);
                            if (m3) { LpayerBridge.onSourceFound('LP_HLS_PATH:' + m3[0]); }
                        } catch(e) {
                            LpayerBridge.onSourceFound('LP_OK_DEC:' + t.substring(0, 800));
                        }
                    } catch(e) {
                        try { LpayerBridge.onSourceFound('LP_PARSE_ERR:' + e.message); } catch(x) {}
                    }
                }

                function tick() {
                    if (found) return;
                    att++;

                    // === Remove ad overlays (z-index >= 2147483646) ===
                    try {
                        document.querySelectorAll('div').forEach(function(d) {
                            var z = parseInt(d.style.zIndex);
                            if (z >= 2147483646) d.remove();
                        });
                    } catch(e) {}

                    // === Try self-decrypt as soon as key + API data are ready ===
                    if (att >= 3 && !decTried) tryDecrypt();

                    // === Play triggers (att 6-30) as fallback ===
                    if (att >= 6 && att <= 30 && !found) {
                        try {
                            var player = document.querySelector('media-player');

                            // startLoading for custom load strategy
                            if (player && att === 6) {
                                try { if (player.startLoading) player.startLoading(); } catch(e) {}
                                try { player.autoplay = true; } catch(e) {}
                            }

                            // Correct Vidstack selector: <media-play-button> not <button>
                            var pb = document.querySelector('media-play-button');
                            if (pb && att >= 8 && att % 3 === 0) {
                                pb.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true}));
                                pb.dispatchEvent(new PointerEvent('pointerdown', {bubbles:true}));
                                pb.dispatchEvent(new PointerEvent('pointerup', {bubbles:true}));
                            }

                            // Also try media-gesture (Vidstack's gesture layer)
                            var gesture = document.querySelector('media-gesture[action="toggle:paused"]');
                            if (gesture && att === 9) {
                                gesture.dispatchEvent(new PointerEvent('pointerup', {bubbles:true}));
                            }

                            // Direct play attempts
                            if (att >= 12 && att % 4 === 0) {
                                if (player) {
                                    try { player.play(); } catch(e) {}
                                    try { if (player.startLoading) player.startLoading(); } catch(e) {}
                                }
                                var v = document.querySelector('video');
                                if (v && v.paused) try { v.play(); } catch(e) {}
                            }
                        } catch(e) {}
                    }

                    // === Check all source locations ===
                    try {
                        var player = document.querySelector('media-player');
                        if (player) {
                            try {
                                var pcs = player.provider && player.provider.currentSrc;
                                if (pcs) {
                                    var u = (typeof pcs === 'string') ? pcs : pcs.src;
                                    if (real(u)) { report(u); return; }
                                }
                            } catch(e) {}
                            try {
                                var st = player.state || player.${'$'}state;
                                if (st) {
                                    var ss = st.source;
                                    if (typeof ss === 'function') ss = ss();
                                    var u = (typeof ss === 'string') ? ss : (ss && ss.src);
                                    if (real(u)) { report(u); return; }
                                }
                            } catch(e) {}
                            var src = player.getAttribute('src');
                            if (real(src)) { report(src); return; }
                        }
                        var sources = document.querySelectorAll('source');
                        for (var i = 0; i < sources.length; i++) {
                            var s = sources[i].getAttribute('src') || '';
                            if (real(s) || (s.indexOf('.mp4') > -1 && s.indexOf('google') === -1)) {
                                report(s); return;
                            }
                        }
                    } catch(e) {}

                    if (att < max) setTimeout(tick, 500);
                }
                tick();
            })();
        """
    }
}
