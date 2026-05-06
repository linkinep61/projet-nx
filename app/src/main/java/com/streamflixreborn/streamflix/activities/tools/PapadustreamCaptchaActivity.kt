package com.streamflixreborn.streamflix.activities.tools

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CompletableDeferred

/**
 * Activity user-visible qui charge la page épisode Papadustream et permet à
 * l'utilisateur de cliquer sur le widget Cloudflare Turnstile.
 *
 * Pourquoi : en headless WebView, Cloudflare Turnstile invisible refuse de fire
 * son callback (anti-bot detection). En mode user-visible avec interaction, le
 * widget peut être résolu manuellement.
 *
 * Pipeline :
 *  1. PapadustreamExtractor crée un token UUID + CompletableDeferred
 *  2. Lance cette activity via startActivity() avec EXTRAs
 *  3. Activity charge la page, montre la WebView
 *  4. User clique le widget Turnstile s'il apparaît
 *  5. Token Turnstile → AJAX getxfield → réponse iframe HTML
 *  6. JS bridge capture l'URL iframe → PendingExtractions[token].complete(url)
 *  7. Activity se ferme automatiquement
 *  8. Extractor reprend la résolution avec l'URL d'iframe
 */
class PapadustreamCaptchaActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PdsCaptchaActivity"

        const val EXTRA_TOKEN = "pds_token"
        const val EXTRA_URL = "pds_url"
        const val EXTRA_XFIELD = "pds_xfield"
        const val EXTRA_ID = "pds_id"
        const val EXTRA_TYPE = "pds_type"

        /** Map partagée entre Activity et Extractor : token → deferred attendant l'iframe URL.
         *  L'Extractor crée le deferred avant de lancer l'activity, l'activity le résout
         *  via .complete(iframeUrl) quand le bridge JS reçoit l'URL.
         *  HashMap sync (ConcurrentHashMap pose problème de desugaring sur ce projet). */
        val pendingExtractions: MutableMap<String, CompletableDeferred<String?>> = mutableMapOf()
        private val pendingLock = Any()
        fun putPending(token: String, deferred: CompletableDeferred<String?>) {
            synchronized(pendingLock) { pendingExtractions[token] = deferred }
        }
        fun removePending(token: String): CompletableDeferred<String?>? {
            synchronized(pendingLock) { return pendingExtractions.remove(token) }
        }
        fun getPending(token: String): CompletableDeferred<String?>? {
            synchronized(pendingLock) { return pendingExtractions[token] }
        }
    }

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var cancelBtn: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private var token: String = ""
    private var pageUrl: String = ""
    private var xfield: String = ""
    private var dleId: String = ""
    private var contentType: String = ""
    private var captured = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.mobileThemeRes(UserPreferences.selectedTheme))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_papadustream_captcha)

        token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        pageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        xfield = intent.getStringExtra(EXTRA_XFIELD).orEmpty()
        dleId = intent.getStringExtra(EXTRA_ID).orEmpty()
        contentType = intent.getStringExtra(EXTRA_TYPE).orEmpty()

        if (token.isEmpty() || pageUrl.isEmpty()) {
            Log.w(TAG, "Missing extras, finishing")
            finish()
            return
        }

        webView = findViewById(R.id.pds_captcha_webview)
        status = findViewById(R.id.pds_captcha_status)
        progress = findViewById(R.id.pds_captcha_progress)
        cancelBtn = findViewById(R.id.pds_captcha_cancel)

        cancelBtn.setOnClickListener { finishWithResult(null, "user cancelled") }

        setupWebView()
        webView.loadUrl(pageUrl, mapOf(
            "Referer" to "https://papadustream.courses/",
            "User-Agent" to ANDROID_CHROME_UA,
        ))
    }

    private fun finishWithResult(iframeUrl: String?, reason: String) {
        if (captured) return
        captured = true
        Log.d(TAG, "Finishing : iframe=$iframeUrl reason=$reason")
        getPending(token)?.complete(iframeUrl)
        if (iframeUrl != null) {
            setResult(Activity.RESULT_OK, Intent().putExtra("iframe_url", iframeUrl))
        } else {
            setResult(Activity.RESULT_CANCELED)
        }
        // Petit délai pour que l'utilisateur voie le succès avant fermeture
        mainHandler.postDelayed({ if (!isFinishing) finish() }, 500)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.userAgentString = ANDROID_CHROME_UA
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        // Pinch-zoom user-friendly : si l'utilisateur veut zoomer manuellement
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.setSupportZoom(true)
        // Initial zoom 100% (sera ajusté côté JS)
        webView.setInitialScale(0)
        WebView.setWebContentsDebuggingEnabled(true)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // JS bridge : la page injectée appelle _pdsBridge.onIframeUrl(url)
        // quand elle a capturé l'URL d'iframe via le hook AJAX.
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onIframeUrl(url: String) {
                Log.d(TAG, "Iframe URL captured: $url")
                mainHandler.post {
                    status.text = "✓ Source trouvée — chargement…"
                    finishWithResult(url, "iframe captured")
                }
            }

            @JavascriptInterface
            fun onError(msg: String) {
                Log.w(TAG, "JS error: $msg")
                mainHandler.post { status.text = "Erreur JS : $msg" }
            }

            @JavascriptInterface
            fun log(msg: String) {
                Log.d(TAG, "JS_LOG: $msg")
            }
        }, "_pdsBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.message()?.let {
                    if (it.contains("captcha", ignoreCase = true) ||
                        it.contains("turnstile", ignoreCase = true) ||
                        it.contains("error", ignoreCase = true)
                    ) {
                        Log.d(TAG, "CONSOLE[${consoleMessage.messageLevel()}]: $it")
                    }
                }
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress == 100) progress.visibility = ProgressBar.INVISIBLE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                if (view == null || captured) return
                Log.d(TAG, "onPageFinished: $finishedUrl")
                status.text = "Page chargée. Cliquez sur le captcha si visible…"
                // Inject trigger script
                view.evaluateJavascript(buildTriggerScript(dleId, xfield, contentType), null)
            }
        }
    }

    override fun onDestroy() {
        try {
            webView.stopLoading()
            webView.removeJavascriptInterface("_pdsBridge")
            webView.destroy()
        } catch (_: Exception) {}
        // Si l'activity est détruite sans capture → libérer le deferred
        if (!captured) {
            getPending(token)?.complete(null)
        }
        removePending(token)
        super.onDestroy()
    }

    override fun onBackPressed() {
        finishWithResult(null, "back pressed")
        super.onBackPressed()
    }

    /** Le JS injecté hook les AJAX et déclenche getxfield. Si Turnstile attend
     *  une interaction user, l'utilisateur peut cliquer le widget (qui sera
     *  visible si on rend display:block le container). */
    private fun buildTriggerScript(episodeId: String, xfield: String, contentType: String): String {
        val safeId = episodeId.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val safeXfield = xfield.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val safeType = contentType.replace(Regex("[^a-zA-Z0-9_-]"), "")
        return """
            (function(){
                var B = window._pdsBridge;
                function log(m){ try { if (B && B.log) B.log(String(m).substring(0,500)); } catch(e){} }
                function onIframe(u){ try { if (B && B.onIframeUrl) B.onIframeUrl(u); } catch(e){} }
                log('=== PDS captcha activity script start ===');

                function extractIframeFromResponse(html) {
                    if (!html) return null;
                    var s = String(html);
                    var m = s.match(/<iframe[^>]+src=["']([^"']+)["']/i);
                    if (m && m[1]) return m[1];
                    var u = s.match(/(https?:\/\/[^"'\s<>]+\/(?:embed|e|d|v|f|player)\/[A-Za-z0-9_\-]+[^"'\s<>]*)/i);
                    if (u && u[1]) return u[1];
                    return null;
                }

                function waitDeps(attempts, cb) {
                    if (typeof window.jQuery === 'function' && typeof window.getxfield === 'function') {
                        log('deps ready (attempts=' + attempts + ')');
                        cb();
                        return;
                    }
                    if (attempts >= 60) { log('deps timeout'); return; }
                    setTimeout(function(){ waitDeps(attempts+1, cb); }, 500);
                }

                waitDeps(0, function(){
                    var ${'$'} = window.jQuery;

                    // Hook AJAX
                    if (${'$'}.ajax && !${'$'}.ajax.__pdsHooked) {
                        var origAjax = ${'$'}.ajax;
                        ${'$'}.ajax = function(opts) {
                            var xhr = origAjax.apply(this, arguments);
                            try {
                                if (xhr && xhr.done) {
                                    xhr.done(function(resp){
                                        var iframeUrl = extractIframeFromResponse(resp);
                                        if (iframeUrl) onIframe(iframeUrl);
                                    });
                                }
                            } catch(e) {}
                            return xhr;
                        };
                        ${'$'}.ajax.__pdsHooked = true;
                    }
                    if (${'$'}.post && !${'$'}.post.__pdsHooked) {
                        var origPost = ${'$'}.post;
                        ${'$'}.post = function(url, data, callback, dataType) {
                            var wrappedCb = function(resp) {
                                var iframeUrl = extractIframeFromResponse(resp);
                                if (iframeUrl) onIframe(iframeUrl);
                                if (typeof callback === 'function') { try { callback(resp); } catch(e){} }
                            };
                            return origPost.call(this, url, data, wrappedCb, dataType);
                        };
                        ${'$'}.post.__pdsHooked = true;
                    }

                    // Captcha auto-résolu (Turnstile invisible/managed mode) — pas besoin
                    // d'overlay ni de scale. On laisse la page rendre exactement comme
                    // sur le site, le widget se résout tout seul et le hook $.ajax
                    // intercepte la réponse iframe.
                    // On rend juste le container visible (s'il était display:none)
                    var capContainer = document.querySelector('#xf-captcha-visible');
                    if (capContainer) {
                        capContainer.style.display = 'block';
                        log('captcha container visible (auto-resolves)');
                    }

                    // Trigger getxfield to start the captcha flow
                    var liens = document.querySelectorAll('div.lien.fx-row');
                    var target = null;
                    for (var i = 0; i < liens.length; i++) {
                        var oc = liens[i].getAttribute('onclick') || '';
                        if (oc.indexOf("'$safeXfield'") !== -1) { target = liens[i]; break; }
                    }
                    if (!target) { log('no lien matching $safeXfield'); return; }
                    log('triggering getxfield for $safeXfield');
                    try { window.getxfield(target, '$safeId', '$safeXfield', '$safeType'); } catch(e) { log('getxfield err: ' + e.message); }
                    // Re-show container after getxfield (which may have re-hidden it)
                    setTimeout(function(){
                        if (capContainer) capContainer.style.display = 'block';
                    }, 200);

                    // Polling iframe DOM (fallback)
                    var pollCount = 0;
                    var pollId = setInterval(function() {
                        pollCount++;
                        var iframes = document.querySelectorAll('iframe[src]');
                        for (var i = 0; i < iframes.length; i++) {
                            var src = iframes[i].src;
                            if (src && src.startsWith('http') &&
                                src.indexOf('papadustream') < 0 &&
                                src.indexOf('challenges.cloudflare.com') < 0 &&
                                src.indexOf('googleapis') < 0 &&
                                src.indexOf('cdnjs') < 0) {
                                log('DOM poll iframe: ' + src);
                                onIframe(src);
                                clearInterval(pollId);
                                return;
                            }
                        }
                        if (pollCount > 240) clearInterval(pollId); // 240 * 500ms = 2 min
                    }, 500);
                });
            })();
        """.trimIndent()
    }

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
}
