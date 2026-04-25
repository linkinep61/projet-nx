package com.streamflixreborn.streamflix.activities.tools

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.NetworkClient
import java.io.ByteArrayInputStream

/**
 * Fullscreen WebView player for sources whose CDN blocks direct HTTP clients
 * (e.g. LuluVdo/LuluStream). Uses the /dl embed endpoint for a lightweight
 * player-only page instead of loading the full site.
 *
 * Features:
 * - POST /dl embed for lightweight player page
 * - HTML5 fullscreen video support (onShowCustomView)
 * - Resource-level ad blocking (shouldInterceptRequest)
 * - Proper lifecycle pause/resume
 * - Error overlay with retry
 * - Hardware-accelerated rendering
 */
class WebViewPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebViewPlayer"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"

        /** Known ad/tracker domain fragments to block at resource level */
        private val AD_DOMAINS = listOf(
            "doubleclick", "googlesyndication", "adservice", "googleadservices",
            "facebook.com/tr", "analytics", "adnxs", "adsrvr", "criteo",
            "taboola", "outbrain", "popads", "popcash", "propellerads",
            "exoclick", "juicyads", "trafficjunky", "clickadu", "hilltopads",
            "revcontent", "mgid", "adsterra", "monetag", "a-ads",
            "ad.php", "/ads/", "banner", "prebid", "pubmatic",
            "amazon-adsystem", "moat", "adsafeprotected",
        )

        fun launch(context: Context, url: String, title: String? = null) {
            context.startActivity(
                Intent(context, WebViewPlayerActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var errorOverlay: LinearLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalUrl: String = ""
    private var fileCode: String = ""
    private var hostUrl: String = ""

    // ─────────────────────────── Lifecycle ───────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_player)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        enterImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        webView = findViewById(R.id.webview_player)
        progressBar = findViewById(R.id.webview_progress)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        errorOverlay = findViewById(R.id.error_overlay)

        originalUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        if (originalUrl.isBlank()) { finish(); return }

        fileCode = originalUrl.trimEnd('/').substringAfterLast("/").substringBefore(".")
        hostUrl = try {
            val u = java.net.URL(originalUrl)
            "${u.protocol}://${u.host}"
        } catch (_: Exception) { "https://luluvdo.com" }

        findViewById<TextView>(R.id.btn_retry).setOnClickListener { retry() }

        setupWebView()
        loadEmbed()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        // Pause JS execution to save battery
        webView.evaluateJavascript("if(typeof jwplayer!=='undefined') try{jwplayer().pause()}catch(e){}", null)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        enterImmersiveMode()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            // Exit HTML5 fullscreen first
            customViewCallback?.onCustomViewHidden()
            return
        }
        finish()
    }

    // ─────────────────────────── Setup ───────────────────────────

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun loadEmbed() {
        errorOverlay.visibility = View.GONE
        webView.visibility = View.VISIBLE

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val postData = "op=embed&file_code=$fileCode&auto=1&referer="
        val postUrl = "$hostUrl/dl"
        Log.d(TAG, "Loading embed: POST $postUrl with file_code=$fileCode")
        webView.postUrl(postUrl, postData.toByteArray())
    }

    private fun retry() {
        Log.d(TAG, "Retrying...")
        loadEmbed()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWebView() {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Ensure touch events reach JWPlayer controls
        webView.setOnTouchListener { v, event ->
            v.performClick()
            false // Don't consume — let WebView handle it
        }
        webView.isClickable = true
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = NetworkClient.USER_AGENT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            // Caching for faster reloads
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // ── WebChromeClient: progress + HTML5 fullscreen ──
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            // HTML5 fullscreen: JWPlayer calls requestFullscreen() on the <video>
            // We must handle it, otherwise the video stays in a small container
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                Log.d(TAG, "onShowCustomView → entering HTML5 fullscreen")
                customView = view
                customViewCallback = callback
                webView.visibility = View.GONE
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                enterImmersiveMode()
            }

            override fun onHideCustomView() {
                Log.d(TAG, "onHideCustomView → exiting HTML5 fullscreen")
                fullscreenContainer.removeAllViews()
                fullscreenContainer.visibility = View.GONE
                customView = null
                customViewCallback = null
                webView.visibility = View.VISIBLE
                enterImmersiveMode()
            }
        }

        // ── WebViewClient: ad blocking, CSS injection, error handling ──
        webView.webViewClient = object : WebViewClient() {

            // Block navigation to non-whitelisted domains (popup/redirect ads)
            // Allow javascript: and blob: URIs (used by JWPlayer internally)
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val scheme = request?.url?.scheme ?: return true
                if (scheme == "javascript" || scheme == "blob") return false
                val host = request.url?.host ?: return true
                val allowed = isAllowedHost(host)
                if (!allowed) Log.d(TAG, "Blocked nav: $host")
                return !allowed
            }

            // Block ad resources at network level — they never load at all
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (isAdUrl(url)) {
                    Log.d(TAG, "Blocked ad resource: ${url.take(80)}")
                    return WebResourceResponse(
                        "text/plain", "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                errorOverlay.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page finished: $url")
                injectPlayerCss(view)
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Only show error overlay for main frame failures
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "Main frame error: ${error?.description}")
                    webView.visibility = View.GONE
                    errorOverlay.visibility = View.VISIBLE
                }
            }

            @Suppress("DEPRECATION")
            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler?, error: SslError?
            ) {
                // Accept SSL for CDN domains (some use self-signed certs)
                val host = error?.url ?: ""
                if (isAllowedHost(host)) {
                    handler?.proceed()
                } else {
                    handler?.cancel()
                }
            }
        }
    }

    // ─────────────────────────── Helpers ───────────────────────────

    private fun isAllowedHost(host: String): Boolean {
        return host.contains("luluvdo") || host.contains("lulustream")
                || host.contains("luluvid") || host.contains("luluvdoo")
                || host.contains("lulucdn") || host.contains("tnmr.org")
                || host.contains("jwpcdn") || host.contains("jwplayer")
                || host.contains("google") || host.contains("gstatic")
    }

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return AD_DOMAINS.any { lower.contains(it) }
    }

    /** Inject CSS to fullscreen the player + JS to auto-play with retry */
    private fun injectPlayerCss(view: WebView?) {
        view?.evaluateJavascript("""
            (function() {
                // ── CSS: fullscreen player, ensure JWPlayer controls are interactive ──
                var style = document.createElement('style');
                style.textContent = `
                    html, body {
                        margin: 0 !important; padding: 0 !important;
                        background: #000 !important;
                        width: 100vw !important; height: 100vh !important;
                        overflow: hidden !important;
                    }

                    /* Hide everything except the player container */
                    body > *:not(#vplayer):not(.jw-wrapper):not(script):not(link):not(style) {
                        display: none !important;
                    }

                    /* Fullscreen the player */
                    #vplayer, #vplayer > div, .jwplayer, .jw-wrapper {
                        width: 100vw !important; height: 100vh !important;
                        position: relative !important;
                    }

                    video {
                        object-fit: contain !important;
                        width: 100% !important; height: 100% !important;
                    }

                    /* ── CRITICAL: Make JWPlayer controls fully interactive ── */
                    /* Controls bar (play, seek, volume, fullscreen) */
                    .jw-controls {
                        pointer-events: auto !important;
                        z-index: 999 !important;
                    }
                    .jw-controlbar {
                        pointer-events: auto !important;
                        z-index: 999 !important;
                        opacity: 1 !important;
                    }
                    /* Time slider (seek bar) */
                    .jw-slider-time, .jw-slider-time * {
                        pointer-events: auto !important;
                        z-index: 1000 !important;
                    }
                    .jw-knob {
                        pointer-events: auto !important;
                    }
                    /* Play/pause display icon in center */
                    .jw-display, .jw-display-container, .jw-display-icon-container {
                        pointer-events: auto !important;
                        z-index: 998 !important;
                    }
                    /* Volume slider */
                    .jw-slider-volume, .jw-slider-volume * {
                        pointer-events: auto !important;
                    }

                    /* Kill anything that sits on top and steals clicks */
                    .video_ad, .video_ad_fadein, #over_player_msg,
                    .site-header, .site-footer, nav, header, footer,
                    .icon-share, .embed-share, .embed-logo,
                    [class*="popup"], [class*="banner"], [id*="overlay"],
                    [id*="over_player"], [class*="adblock"] {
                        display: none !important;
                        pointer-events: none !important;
                    }
                `;
                document.head.appendChild(style);

                // ── Aggressive overlay removal ──
                function removeBlockingOverlays() {
                    // 1. Kill all non-JWPlayer elements with absolute/fixed positioning
                    document.querySelectorAll('body *').forEach(function(el) {
                        if (el.closest('.jwplayer') || el.closest('#vplayer') ||
                            el.tagName === 'SCRIPT' || el.tagName === 'LINK' || el.tagName === 'STYLE') return;
                        var pos = window.getComputedStyle(el).position;
                        if (pos === 'absolute' || pos === 'fixed') {
                            el.style.display = 'none';
                            el.style.pointerEvents = 'none';
                        }
                    });

                    // 2. Kill all links with target=_blank (ad click catchers)
                    document.querySelectorAll('a[target="_blank"]').forEach(function(el) {
                        el.style.pointerEvents = 'none';
                        el.style.display = 'none';
                        el.removeAttribute('href');
                        el.removeAttribute('onclick');
                    });

                    // 3. Kill transparent/invisible overlays on top of video
                    document.querySelectorAll('div, span, a, iframe').forEach(function(el) {
                        if (el.closest('.jwplayer') || el.closest('.jw-controls')) return;
                        var style = window.getComputedStyle(el);
                        var zIndex = parseInt(style.zIndex) || 0;
                        if (zIndex > 50 && !el.classList.toString().includes('jw')) {
                            el.style.display = 'none';
                            el.style.pointerEvents = 'none';
                        }
                    });

                    // 4. Remove onclick handlers from non-JW elements near the player
                    document.querySelectorAll('[onclick]').forEach(function(el) {
                        if (!el.closest('.jwplayer')) {
                            el.removeAttribute('onclick');
                            el.style.pointerEvents = 'none';
                        }
                    });
                }

                // ── JS: resize JWPlayer + auto-play with retry ──
                function tryPlay() {
                    if (typeof jwplayer !== 'undefined') {
                        try {
                            var p = jwplayer();
                            p.resize('100%', '100%');
                            p.play();
                            removeBlockingOverlays();
                            return true;
                        } catch(e) {}
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        try { v.play(); removeBlockingOverlays(); return true; } catch(e) {}
                    }
                    return false;
                }
                if (!tryPlay()) {
                    var attempts = 0;
                    var interval = setInterval(function() {
                        if (tryPlay() || ++attempts > 30) {
                            clearInterval(interval);
                            removeBlockingOverlays();
                        }
                    }, 500);
                }

                // Keep cleaning overlays — ads re-inject them
                setInterval(removeBlockingOverlays, 1500);
            })();
        """.trimIndent(), null)
    }
}
