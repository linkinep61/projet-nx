package com.streamflixreborn.streamflix.activities.tools

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.streamflixreborn.streamflix.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FRAnime watch2 capture activity — VISIBLE WebView fallback.
 *
 * Quand l'extracteur invisible (FranimeExtractor.captureEmbedInWebView) échoue
 * à passer Cloudflare Turnstile (mode "managé" qui exige interaction utilisateur),
 * on lance cette Activity qui montre une WebView visible. L'utilisateur peut
 * cliquer sur la case Turnstile si elle apparaît ; sinon Cloudflare auto-résout
 * le challenge invisible avec les cookies/fingerprint d'une "vraie" session
 * affichée à l'écran (vs background WebView qui est détectée automation).
 *
 * Workflow :
 *  1. FranimeExtractor.extract() appelle captureEmbed(ctx, watch2Url)
 *  2. captureEmbed lance l'Activity via startActivity + FLAG_ACTIVITY_NEW_TASK
 *  3. WebView charge watch2 → Turnstile passe (auto ou clic user) → iframe
 *     vers sibnet/sendvid/etc. apparaît
 *  4. shouldInterceptRequest capture l'URL de l'iframe → résoud le Deferred
 *     → Activity finish()
 *  5. captureEmbed retourne l'URL → délégué à l'extracteur spécifique
 *
 * 2026-05-16 v1 : créé pour fallback FRAnime.
 */
class FranimeCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WATCH2_URL = "watch2_url"
        private const val TAG = "FranimeCaptureActivity"
        private const val CAPTURE_TIMEOUT_MS = 180_000L  // 3 min

        @Volatile
        private var pendingResult: CompletableDeferred<String?>? = null

        /** Appelé depuis FranimeExtractor — lance l'Activity et attend
         *  l'URL embed capturée (ou null si annulation/timeout). */
        suspend fun captureEmbed(ctx: Context, watch2Url: String): String? {
            // Si une autre capture est en cours, on l'annule
            pendingResult?.let {
                if (!it.isCompleted) it.complete(null)
            }
            val deferred = CompletableDeferred<String?>()
            pendingResult = deferred

            // 2026-05-16 v5 : préférer l'Activity de foreground pour le launch.
            // Sur Android 10+, lancer une Activity depuis un context Application
            // est restreint (Background Activity Launch policy). Utiliser l'Activity
            // courante évite ce problème.
            val launchCtx: Context = com.streamflixreborn.streamflix.StreamFlixApp
                .currentActivity ?: ctx
            val intent = Intent(launchCtx, FranimeCaptureActivity::class.java).apply {
                // FLAG_ACTIVITY_NEW_TASK seulement si on lance depuis app context
                if (launchCtx !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                putExtra(EXTRA_WATCH2_URL, watch2Url)
            }
            try {
                launchCtx.startActivity(intent)
                Log.d(TAG, "FranimeCaptureActivity launched (launchCtx=${launchCtx.javaClass.simpleName})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start FranimeCaptureActivity: ${e.message}", e)
                pendingResult = null
                return null
            }

            return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                deferred.await()
            }.also {
                pendingResult = null
            }
        }

        /** Appelé depuis l'Activity quand un iframe est capturé (ou cancel). */
        internal fun resolveResult(url: String?) {
            val d = pendingResult
            if (d != null && !d.isCompleted) {
                d.complete(url)
            }
        }
    }

    /** Hosts vidéo qu'on reconnaît comme target iframe — déclenche la capture. */
    private val EMBED_HOST_TOKENS = listOf(
        "sibnet.ru", "video.sibnet",
        "sendvid",
        "filemoon", "moon.live", "moonembed", "bf0skv", "bysejikuar",
        "moflix-stream", "bysezoxexe", "bysebuho", "filemoon.sx",
        "bysekoze", "bysesayeveum", "lukefirst", "filemoon.site",
        "weneverbeenfree",
        "vidmoly", "vmoly",
        "uqload", "ulto",
        "mixdrop", "mxdrop", "m1xdrop", "mixdroop",
        "vidhide", "minochinos", "dhtpre", "peytonepre", "vidhideplus",
        "dingtezuni", "dintezuvio", "moflix-stream.click", "filelions",
        "streamhide",
        "smoothpre",
        "doodstream", "dood.", "dooood", "dood-",
        "streamtape", "strtape", "tapesvc",
        "voe.sx", "voe-network",
        "streamwish", "wishfast", "swhoi", "playerwish",
        "vk.com", "vk.ru", "vkvideo", "m.vk.",
        "lpayer", "embed4me",
        "dailymotion.com", "dai.ly",
        "yourupload",
        "ok.ru", "okru",
        "oneupload",
        "my.mail.ru", "mail.ru",
        "mivalyo", "movearnpre",
    )

    private val BLOCKED_HOSTS = listOf(
        "googlesyndication", "doubleclick", "googleads",
        "popads", "popunder", "popcash", "propellerads",
        "exoclick", "juicyads", "trafficjunky",
        "googletagmanager", "google-analytics",
        "mc.yandex.ru", "yandex.ru",
        "ad.a-ads", "a-ads.com",
        "adstagscripts",
    )

    private val ANTI_DETECTION_SCRIPT = """
        (function(){
            try { Object.defineProperty(navigator, 'webdriver', {get: function(){ return undefined; }}); } catch(e){}
            try { Object.defineProperty(navigator, 'plugins', {get: function(){ return [1,2,3,4,5]; }}); } catch(e){}
            try { Object.defineProperty(navigator, 'languages', {get: function(){ return ['fr-FR','fr','en']; }}); } catch(e){}
            try { window.chrome = window.chrome || { runtime: {} }; } catch(e){}
        })();
    """.trimIndent()

    private val MUTATION_OBSERVER_SCRIPT = """
        (function(){
            try {
                // 2026-05-16 v6 : auto-click "Regarder l'épisode" button.
                // FRAnime watch2 page a un bouton rouge interstitiel à cliquer
                // AVANT que l'iframe vidéo soit injecté.
                function tryClickWatchButton() {
                    var candidates = document.querySelectorAll(
                        'button, a, div[role="button"], span[role="button"], [class*="watch"], [class*="regarder"]'
                    );
                    for (var i = 0; i < candidates.length; i++) {
                        var el = candidates[i];
                        var text = (el.innerText || el.textContent || '').toLowerCase().trim();
                        if (text.indexOf('regarder') !== -1 &&
                            (text.indexOf('épisode') !== -1 || text.indexOf('episode') !== -1)) {
                            try { el.click(); return true; } catch(e){}
                        }
                    }
                    for (var j = 0; j < candidates.length; j++) {
                        var el2 = candidates[j];
                        var text2 = (el2.innerText || el2.textContent || '').toLowerCase().trim();
                        if (text2 === 'regarder' || text2.indexOf('regarder l') === 0) {
                            try { el2.click(); return true; } catch(e){}
                        }
                    }
                    return false;
                }
                function reportIframes() {
                    var iframes = document.querySelectorAll('iframe');
                    for (var i = 0; i < iframes.length; i++) {
                        var src = iframes[i].src || '';
                        if (src.startsWith('http') && typeof FranimeBridge !== 'undefined') {
                            try { FranimeBridge.onIframeFound(src); } catch(e){}
                        }
                    }
                }
                reportIframes();
                // Click toutes les secondes pendant 30s
                var attempts = 0;
                var clickInterval = setInterval(function(){
                    attempts++;
                    if (attempts > 30) { clearInterval(clickInterval); return; }
                    if (tryClickWatchButton() && typeof FranimeBridge !== 'undefined') {
                        try { FranimeBridge.onWatchClicked(); } catch(e){}
                    }
                }, 1000);
                var observer = new MutationObserver(function(mutations){ reportIframes(); });
                observer.observe(document.body || document.documentElement, {
                    childList: true, subtree: true,
                    attributes: true, attributeFilter: ['src']
                });
            } catch(e){}
        })();
    """.trimIndent()

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var cancelButton: Button

    private var resolved = false
    private val navigatedToWatch2 = java.util.concurrent.atomic.AtomicBoolean(false)
    private lateinit var watch2Url: String

    private fun isEmbedUrl(src: String): Boolean {
        if (!src.startsWith("http")) return false
        val lower = src.lowercase()
        if (lower.contains("franime.fr")) return false
        if (lower.contains("cloudflare") || lower.contains("kitsu") ||
            lower.contains("recaptcha") || lower.contains("turnstile") ||
            lower.contains("challenges")) return false
        return EMBED_HOST_TOKENS.any { lower.contains(it, ignoreCase = true) }
    }

    private fun resolve(url: String?) {
        if (resolved) return
        resolved = true
        Log.d(TAG, "resolve($url)")
        resolveResult(url)
        Handler(Looper.getMainLooper()).post {
            if (!isFinishing) finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_franime_capture)
        watch2Url = intent.getStringExtra(EXTRA_WATCH2_URL).orEmpty()
        if (watch2Url.isBlank()) {
            resolve(null)
            return
        }

        webView = findViewById(R.id.franime_capture_webview)
        progressBar = findViewById(R.id.franime_capture_progress)
        statusView = findViewById(R.id.franime_capture_status)
        cancelButton = findViewById(R.id.franime_capture_cancel)

        cancelButton.setOnClickListener { resolve(null) }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onIframeFound(src: String) {
                if (resolved) return
                if (isEmbedUrl(src)) {
                    Log.d(TAG, "EMBED via MutationObserver: $src")
                    resolve(src)
                }
            }
            @JavascriptInterface
            fun onWatchClicked() {
                Log.d(TAG, "JS clicked 'Regarder l'épisode' button")
                Handler(Looper.getMainLooper()).post {
                    if (!resolved) statusView.text = "Bouton cliqué — chargement du serveur vidéo…"
                }
            }
        }, "FranimeBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 0..99) View.VISIBLE else View.GONE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                val host = request.url?.host?.lowercase() ?: ""

                if (BLOCKED_HOSTS.any { host.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }

                if (isEmbedUrl(reqUrl)) {
                    Log.d(TAG, "EMBED CAPTURED (network): $reqUrl")
                    resolve(reqUrl)
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (resolved || view == null) return
                view.evaluateJavascript(ANTI_DETECTION_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                if (resolved || view == null) return
                Log.d(TAG, "onPageFinished($finishedUrl)")
                statusView.text = "Recherche du lecteur — clic automatique du bouton..."
                view.evaluateJavascript(MUTATION_OBSERVER_SCRIPT, null)

                // Polling DOM as fallback toutes les 1.5s
                val startMs = System.currentTimeMillis()
                val MAX_POLL_MS = 60_000L
                fun pollDom() {
                    if (resolved) return
                    view.evaluateJavascript(
                        "(function(){var f=document.querySelectorAll('iframe');var s=[];for(var i=0;i<f.length;i++){s.push(f[i].src||'')}return s.join('|');})();"
                    ) { result ->
                        val raw = result?.trim('"') ?: ""
                        val sources = raw.split("|")
                        val match = sources.firstOrNull { src -> isEmbedUrl(src) }
                        val elapsed = System.currentTimeMillis() - startMs
                        Log.d(TAG, "pollDom +${elapsed}ms sources=${sources.size} match=$match")
                        if (match != null) {
                            Log.d(TAG, "EMBED from DOM iframe (polling): $match")
                            resolve(match)
                        } else if (elapsed < MAX_POLL_MS) {
                            view.postDelayed({ pollDom() }, 1_500L)
                        }
                    }
                }
                view.postDelayed({ pollDom() }, 1_500L)
            }
        }

        // Charge directement la page épisode
        statusView.text = "Chargement de la page épisode..."
        webView.loadUrl(watch2Url)
    }

    override fun onDestroy() {
        // Si l'activity est détruite sans avoir résolu (back button, etc.) →
        // resolve null pour débloquer l'extracteur en attente.
        if (!resolved) {
            resolved = true
            resolveResult(null)
        }
        runCatching {
            webView.stopLoading()
            webView.removeJavascriptInterface("FranimeBridge")
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onBackPressed() {
        resolve(null)
        super.onBackPressed()
    }
}
