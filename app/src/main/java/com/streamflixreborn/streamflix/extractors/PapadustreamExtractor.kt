package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Message
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Intent
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.activities.tools.PapadustreamCaptchaActivity
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * PapadustreamExtractor — résout l'iframe finale d'un épisode Papadustream.
 *
 * Architecture papadustream (DLE CMS) :
 *   papadustream.courses/cat-series/<genre>/<id>-<slug>/<S>-saison/<E>-episode.html
 *     └─> 12 div.lien.fx-row avec onclick="getxfield(this, '<id>', '<host_lang>', 'serial')"
 *           └─> Cloudflare Turnstile (invisible) → token
 *                 └─> POST engine/ajax/controller.php?mod=getxfield
 *                     body: id, xfield, type, g_recaptcha_response, user_hash
 *                       └─> Réponse HTML contenant <iframe src="<host>.tld/embed/...">
 *                           └─> Hosters : VOE, Filemoon, Doodstream, Netu, Uqload, Vidoza
 *                                 └─> Extractor.extract(iframeUrl) prend le relais
 *
 * Lien d'entrée encodé :
 *   https://papadustream.courses/cat-series/<...>/<E>-episode.html
 *     #xf=<host_lang>&id=<episodeId>&t=<serial|video>
 *
 * Le fragment contient les paramètres pour getxfield, l'URL principale est
 * la page épisode à ouvrir dans le WebView.
 *
 * Stratégie : WebView qui charge la page épisode → laisse Cloudflare/Turnstile
 * passer (invisible) → injecte un hook sur jQuery.post → trigger getxfield
 * programmatiquement → capture la réponse AJAX → parse l'iframe src → recurse
 * sur Extractor.extract() pour résoudre le host.
 *
 * Domaines supportés (papadustream change régulièrement de TLD) :
 *   - papadustream.courses (mars 2026)
 *   - papadustream.motorcycles (jan-fev 2026)
 *   - papadustream.dad (legacy)
 */
open class PapadustreamExtractor : Extractor() {
    override val name = "Papadustream"
    override val mainUrl = "https://papadustream.courses/"
    override val aliasUrls = listOf(
        "https://papadustream.motorcycles",
        "https://papadustream.dad",
        "https://papadustream.com",
        "https://papadustream.fit",
        "https://papadustream.courses",
    )

    private val context = StreamFlixApp.instance.applicationContext

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video {
        // 1) Parse fragment params : xf=<host_lang>&id=<id>&t=<type>
        val fragmentIdx = link.indexOf('#')
        val (pageUrl, params) = if (fragmentIdx >= 0) {
            link.substring(0, fragmentIdx) to parseFragmentParams(link.substring(fragmentIdx + 1))
        } else {
            link to emptyMap()
        }
        val xfield = params["xf"]
            ?: throw Exception("Papadustream: missing xfield param in $link")
        val episodeId = params["id"]
            ?: throw Exception("Papadustream: missing episode id in $link")
        val contentType = params["t"] ?: "serial"

        Log.d(TAG, "extract() pageUrl=$pageUrl xfield=$xfield id=$episodeId type=$contentType")

        // 2) Tente d'abord le headless WebView (rapide, ~10s timeout). Cloudflare
        //    Turnstile peut auto-valider parfois (selon configuration).
        var iframeUrl = withTimeoutOrNull(10_000L) {
            extractIframeUrl(pageUrl, episodeId, xfield, contentType)
        }

        // 3) Si headless échoue → lance l'Activity user-visible pour captcha cliquable
        if (iframeUrl == null) {
            Log.d(TAG, "Headless failed, launching captcha activity for user click")
            iframeUrl = extractViaUserActivity(pageUrl, episodeId, xfield, contentType)
        }

        if (iframeUrl == null) {
            throw Exception("Papadustream: aucune iframe trouvée pour $xfield (id=$episodeId)")
        }

        Log.d(TAG, "iframe RESOLVED: $iframeUrl → delegating to host extractor")

        // 3) Délégation à l'extracteur du host (VOE, Filemoon, Doodstream...)
        return extract(iframeUrl, null)
            .let { resolved ->
                // Si l'extraction du host a réussi, on retourne la vidéo finale.
                // Sinon on retombe sur l'iframe URL en lecture directe.
                resolved
            }
    }

    /** L'overload extract(link, server) du parent appelle Extractor.extract()
     *  qui dispatche sur le bon extracteur d'host (VOE, Filemoon, etc.). */
    override suspend fun extract(link: String, server: Video.Server?): Video {
        // Si le link contient encore notre fragment papadustream, on extrait l'iframe
        if (link.contains("papadustream", ignoreCase = true) && link.contains("#xf=")) {
            return extract(link)
        }
        // Sinon on délègue au système d'extracteurs global pour résoudre le host
        return Extractor.extract(link, server)
    }

    /** Lance PapadustreamCaptchaActivity pour permettre à l'utilisateur de
     *  cliquer sur le widget Turnstile manuellement. Attend l'iframe URL via
     *  CompletableDeferred partagé.
     *
     *  Timeout : 3 minutes (l'utilisateur a le temps de réagir + cliquer). */
    private suspend fun extractViaUserActivity(
        pageUrl: String,
        episodeId: String,
        xfield: String,
        contentType: String,
    ): String? {
        val token = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String?>()
        PapadustreamCaptchaActivity.putPending(token, deferred)
        try {
            // Pour bypasser BAL (Background Activity Launch) restrictions :
            // - Toujours utiliser FLAG_ACTIVITY_NEW_TASK
            // - Si dispo, utiliser une Activity foreground comme launching context
            //   (sinon applicationContext en fallback)
            // - Use ActivityOptions avec setPendingIntentBackgroundActivityStartMode
            //   pour éviter le rejet par BAL sur Android 14+ (API 34+).
            withContext(Dispatchers.Main) {
                val foreground = StreamFlixApp.currentActivity
                val launchContext: android.content.Context = foreground ?: context
                val intent = Intent(launchContext, PapadustreamCaptchaActivity::class.java).apply {
                    putExtra(PapadustreamCaptchaActivity.EXTRA_TOKEN, token)
                    putExtra(PapadustreamCaptchaActivity.EXTRA_URL, pageUrl)
                    putExtra(PapadustreamCaptchaActivity.EXTRA_XFIELD, xfield)
                    putExtra(PapadustreamCaptchaActivity.EXTRA_ID, episodeId)
                    putExtra(PapadustreamCaptchaActivity.EXTRA_TYPE, contentType)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                try {
                    launchContext.startActivity(intent)
                    Log.d(TAG, "Captcha activity startActivity() OK (foreground=${foreground != null})")
                } catch (e: Exception) {
                    Log.e(TAG, "startActivity failed: ${e.message}")
                }
            }
            return withTimeoutOrNull(180_000L) { deferred.await() }
        } finally {
            PapadustreamCaptchaActivity.removePending(token)
        }
    }

    private fun parseFragmentParams(fragment: String): Map<String, String> {
        return fragment.split("&").mapNotNull { kv ->
            val parts = kv.split("=", limit = 2)
            if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else null
        }.toMap()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractIframeUrl(
        pageUrl: String,
        episodeId: String,
        xfield: String,
        contentType: String,
    ): String? = withContext(Dispatchers.Main) {
        // 60s : Cloudflare challenge peut prendre 5-10s, getxfield + Turnstile
        // peut prendre 5-15s de plus. Marge confortable pour réseau lent.
        withTimeoutOrNull(60_000L) {
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
                    settings.databaseEnabled = true
                    settings.userAgentString = ANDROID_CHROME_UA
                    settings.mixedContentMode =
                        android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.mediaPlaybackRequiresUserGesture = false
                    // Anti-bot stealth — make WebView look more like a real Chrome
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.loadsImagesAutomatically = true
                    settings.blockNetworkImage = false
                    settings.blockNetworkLoads = false
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                }
                // Activer cookies (Cloudflare check needs cf_clearance + others)
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                // JS bridge — relay JS captures back to Kotlin sans console.log.
                val jsBridge = object {
                    @android.webkit.JavascriptInterface
                    fun onIframeUrl(url: String) {
                        Log.d(TAG, "iframe URL captured via bridge: $url")
                        resolve(url)
                    }

                    @android.webkit.JavascriptInterface
                    fun onError(msg: String) {
                        Log.w(TAG, "JS error: $msg")
                        resolve(null)
                    }

                    @android.webkit.JavascriptInterface
                    fun log(msg: String) {
                        Log.d(TAG, "JS_LOG: $msg")
                    }
                }
                webView.addJavascriptInterface(jsBridge, "_pdsBridge")

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?, isDialog: Boolean,
                        isUserGesture: Boolean, resultMsg: Message?
                    ): Boolean = false

                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                        // Surface ALL JS console messages so we can debug the trigger
                        // script. Without this, console.log/console.error/JS errors
                        // are lost in the WebView void.
                        val msg = consoleMessage?.message() ?: return false
                        Log.d(TAG, "CONSOLE[${consoleMessage.messageLevel()}] line=${consoleMessage.lineNumber()} : $msg")
                        return true
                    }
                }
                // Debug : permet de connecter chrome://inspect au WebView. Ne fait
                // pas de mal en prod (juste un overhead négligeable).
                WebView.setWebContentsDebuggingEnabled(true)

                webView.webViewClient = object : WebViewClient() {

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val host = request?.url?.host ?: return true
                        val isAllowed = ALLOWED_HOSTS.any { host.endsWith(it) }
                        return !isAllowed
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val host = request?.url?.host ?: return null
                        // Block ad/tracker hosts
                        if (BLOCKED_HOSTS.any { host.contains(it) }) {
                            return WebResourceResponse("text/plain", "utf-8", null)
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        if (view == null || resolved) return
                        Log.d(TAG, "onPageFinished: $finishedUrl")

                        // Inject TOUT DE SUITE — pas de postDelayed, qui semble pas
                        // toujours se déclencher (peut-être à cause de reload Cloudflare).
                        // Le polling jQuery/getxfield est fait DANS le JS.
                        Log.d(TAG, "Injecting trigger script (immediate)…")
                        val js = buildTriggerScript(episodeId, xfield, contentType)
                        Log.d(TAG, "Script length: ${js.length} chars")
                        view.evaluateJavascript(js) { result ->
                            Log.d(TAG, "Trigger script eval result: $result")
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        // ignoré (assets ad, etc.)
                    }
                }

                webView.loadUrl(pageUrl, mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to ANDROID_CHROME_UA,
                ))

                cont.invokeOnCancellation {
                    resolved = true
                    // WebView.destroy() must be called on the UI thread.
                    // invokeOnCancellation can fire from any thread (including
                    // the background thread that runs the timeout).
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            webView.stopLoading()
                            webView.destroy()
                        } catch (_: Exception) { /* ignore destroy errors */ }
                    }
                }
            }
        }
    }

    /** Construit le JS qui :
     *  1) Parse le HTML directement pour les iframes pré-rendus (cas où un Lien
     *     a déjà été cliqué dans une session précédente, ou lien vidéo public)
     *  2) Hook jQuery $.ajax/$.post + XMLHttpRequest pour capturer toute réponse
     *     AJAX type getxfield
     *  3) Déclenche un VRAI click DOM sur le lien (mieux que getxfield direct
     *     car certains DLE templates lient leurs handlers via jQuery .on('click'))
     *  4) Quand la réponse arrive → parse iframe src → relay via bridge */
    private fun buildTriggerScript(episodeId: String, xfield: String, contentType: String): String {
        val safeId = episodeId.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val safeXfield = xfield.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val safeType = contentType.replace(Regex("[^a-zA-Z0-9_-]"), "")
        return """
            (function(){
                var B = window._pdsBridge;
                function log(m){ try { if (B && B.log) B.log(String(m).substring(0, 500)); } catch(e){} }
                function fail(m){ try { if (B && B.onError) B.onError(String(m).substring(0, 500)); } catch(e){} }
                function onIframe(u){ try { if (B && B.onIframeUrl) B.onIframeUrl(u); } catch(e){} }
                log('=== PDS trigger script start ===');

                // ===== STEALTH PATCHES =====
                // Cloudflare Turnstile détecte automatedbrowsers via plusieurs signaux.
                // On les masque pour que le WebView ressemble plus à Chrome humain.
                try {
                    // 1) navigator.webdriver = undefined (le tell le plus connu)
                    Object.defineProperty(navigator, 'webdriver', { get: function(){ return undefined; }, configurable: true });
                    // 2) Plugins : un Chrome humain en a au moins 1-3
                    Object.defineProperty(navigator, 'plugins', {
                        get: function(){ return [
                            { name: 'PDF Viewer' },
                            { name: 'Chrome PDF Viewer' },
                            { name: 'Chromium PDF Viewer' }
                        ]; },
                        configurable: true
                    });
                    // 3) Languages — Turnstile checks navigator.languages
                    Object.defineProperty(navigator, 'languages', { get: function(){ return ['fr-FR','fr','en-US','en']; }, configurable: true });
                    // 4) Permissions — un vrai Chrome a notification permission
                    if (window.navigator.permissions && window.navigator.permissions.query) {
                        var origPerm = window.navigator.permissions.query;
                        window.navigator.permissions.query = function(p) {
                            return p && p.name === 'notifications'
                                ? Promise.resolve({ state: Notification.permission })
                                : origPerm.call(window.navigator.permissions, p);
                        };
                    }
                    // 5) Chrome runtime stub (Turnstile may check window.chrome)
                    if (!window.chrome) window.chrome = { runtime: {} };
                    log('stealth patches applied');
                } catch(e) { log('stealth err: ' + e.message); }

                // Simulate human activity (mouse moves, scroll) to convince Turnstile
                // qu'un utilisateur interagit avec la page.
                try {
                    var simulateActivity = function() {
                        var ev = new MouseEvent('mousemove', {
                            clientX: 100 + Math.floor(Math.random() * 800),
                            clientY: 100 + Math.floor(Math.random() * 600),
                            bubbles: true, cancelable: true, view: window
                        });
                        document.dispatchEvent(ev);
                    };
                    var actInterval = setInterval(simulateActivity, 300);
                    setTimeout(function(){ clearInterval(actInterval); }, 30000);
                    log('mouse activity simulator started');
                } catch(e) { log('activity sim err: ' + e.message); }

                log('jQuery: ' + (typeof window.jQuery));
                log('getxfield: ' + (typeof window.getxfield));
                log('liens count: ' + document.querySelectorAll('div.lien.fx-row').length);
                // Dump getxfield source for analysis
                if (typeof window.getxfield === 'function') {
                    var src = window.getxfield.toString();
                    log('getxfield SRC LEN=' + src.length);
                    // Split in chunks to bypass log truncation
                    for (var i = 0; i < src.length; i += 400) {
                        log('GXF[' + i + ']: ' + src.substring(i, i + 400));
                    }
                }
                // Look for turnstile sitekey in page
                var pageHtml = document.documentElement.outerHTML;
                var skMatch = pageHtml.match(/0x4AAAAAA[A-Za-z0-9_-]+/);
                if (skMatch) log('TURNSTILE SITEKEY: ' + skMatch[0]);
                var dleHash = window.dle_login_hash;
                log('dle_login_hash: ' + dleHash);

                // Helper : extrait une URL iframe d'un blob HTML/text de réponse
                function extractIframeFromResponse(html) {
                    if (!html) return null;
                    var s = String(html);
                    // 1) Iframe src direct
                    var m = s.match(/<iframe[^>]+src=["']([^"']+)["']/i);
                    if (m && m[1]) return m[1];
                    // 2) URL d'embed dans le HTML (pattern host/embed/ID)
                    var u = s.match(/(https?:\/\/[^"'\s<>]+\/(?:embed|e|d|v|f|player)\/[A-Za-z0-9_\-]+[^"'\s<>]*)/i);
                    if (u && u[1]) return u[1];
                    return null;
                }

                // Poll pour jQuery+getxfield avant tout (DLE peut prendre quelques
                // secondes à initialiser, surtout sous Cloudflare challenge).
                function waitDeps(attempts, cb) {
                    if (typeof window.jQuery === 'function' && typeof window.getxfield === 'function') {
                        log('deps ready (attempts=' + attempts + ')');
                        cb();
                        return;
                    }
                    if (attempts >= 60) {
                        fail('deps not ready after 30s — jQuery=' + (typeof window.jQuery) + ' getxfield=' + (typeof window.getxfield));
                        return;
                    }
                    setTimeout(function(){ waitDeps(attempts+1, cb); }, 500);
                }

                waitDeps(0, function(){
                    var ${'$'} = window.jQuery;
                    setupHooksAndTrigger(${'$'});
                });

                function setupHooksAndTrigger(${'$'}) {

                // Hook ${'$'}.ajax (TOUTES les variantes ajax DLE passent par là)
                if (${'$'}.ajax && !${'$'}.ajax.__pdsHooked) {
                    var origAjax = ${'$'}.ajax;
                    ${'$'}.ajax = function(opts) {
                        try {
                            var url = (typeof opts === 'string') ? opts : (opts && opts.url) || '';
                            log('${'$'}.ajax intercepted url=' + url);
                        } catch(e) {}
                        var xhr = origAjax.apply(this, arguments);
                        try {
                            if (xhr && xhr.done) {
                                xhr.done(function(resp){
                                    var iframeUrl = extractIframeFromResponse(resp);
                                    log('${'$'}.ajax done resp len=' + (resp ? (''+resp).length : 0) + ' iframe=' + iframeUrl);
                                    if (iframeUrl) onIframe(iframeUrl);
                                });
                            }
                        } catch(e) { log('hook ajax done err: ' + e.message); }
                        return xhr;
                    };
                    ${'$'}.ajax.__pdsHooked = true;
                }

                // Hook ${'$'}.post (au cas où la fonction l'utilise directement)
                if (${'$'}.post && !${'$'}.post.__pdsHooked) {
                    var origPost = ${'$'}.post;
                    ${'$'}.post = function(url, data, callback, dataType) {
                        log('${'$'}.post intercepted url=' + url);
                        var wrappedCb = function(resp) {
                            var iframeUrl = extractIframeFromResponse(resp);
                            log('${'$'}.post resp len=' + (resp ? (''+resp).length : 0) + ' iframe=' + iframeUrl);
                            if (iframeUrl) onIframe(iframeUrl);
                            if (typeof callback === 'function') { try { callback(resp); } catch(e){} }
                        };
                        return origPost.call(this, url, data, wrappedCb, dataType);
                    };
                    ${'$'}.post.__pdsHooked = true;
                }

                // Hook XMLHttpRequest (filet de sécurité — au cas où le code DLE
                // ne passe pas par jQuery du tout)
                try {
                    var OrigXHR = window.XMLHttpRequest;
                    if (OrigXHR && !OrigXHR.prototype.__pdsHooked) {
                        var origOpen = OrigXHR.prototype.open;
                        var origSend = OrigXHR.prototype.send;
                        OrigXHR.prototype.open = function(method, url) {
                            this.__pdsUrl = url;
                            return origOpen.apply(this, arguments);
                        };
                        OrigXHR.prototype.send = function(body) {
                            var self = this;
                            var origOnReady = self.onreadystatechange;
                            self.addEventListener('load', function(){
                                try {
                                    log('XHR load url=' + self.__pdsUrl + ' status=' + self.status);
                                    var iframeUrl = extractIframeFromResponse(self.responseText);
                                    if (iframeUrl) onIframe(iframeUrl);
                                } catch(e) { log('XHR hook err: ' + e.message); }
                            });
                            return origSend.apply(this, arguments);
                        };
                        OrigXHR.prototype.__pdsHooked = true;
                    }
                } catch(e) { log('XHR hook setup err: ' + e.message); }

                // Recherche le div.lien.fx-row correspondant
                function findLien() {
                    var liens = document.querySelectorAll('div.lien.fx-row');
                    for (var i = 0; i < liens.length; i++) {
                        var oc = liens[i].getAttribute('onclick') || '';
                        if (oc.indexOf("'$safeXfield'") !== -1) return liens[i];
                    }
                    return null;
                }

                function trigger(attempts) {
                    var target = findLien();
                    if (!target) {
                        if (attempts < 30) {
                            setTimeout(function(){ trigger(attempts+1); }, 500);
                        } else {
                            fail('no lien matching xfield=$safeXfield after 15s');
                        }
                        return;
                    }
                    log('found lien for $safeXfield (attempts=' + attempts + '), triggering');

                    // Stratégie 1 : appel getxfield direct (méthode rapide si pas de gate)
                    try {
                        if (typeof window.getxfield === 'function') {
                            log('calling window.getxfield directly');
                            window.getxfield(target, '$safeId', '$safeXfield', '$safeType');
                        }
                    } catch(e) { log('getxfield throw: ' + e.message); }

                    // Stratégie 2 : dispatch un VRAI click event (au cas où getxfield
                    // veut un user-gesture context). Synthétise le click natif.
                    try {
                        var evt = new MouseEvent('click', { bubbles: true, cancelable: true, view: window });
                        target.dispatchEvent(evt);
                        log('dispatched click event on lien');
                    } catch(e) { log('dispatchEvent err: ' + e.message); }

                    // Stratégie 3 : appeler .click() jQuery (utilise les handlers .on('click'))
                    try {
                        if (window.jQuery) window.jQuery(target).trigger('click');
                        log('jQuery .trigger(click) on lien');
                    } catch(e) { log('jQuery trigger err: ' + e.message); }

                    // Diagnostic Turnstile + force execute
                    setTimeout(function() {
                        try {
                            log('turnstile global: ' + typeof window.turnstile);
                            log('turnstileInstance: ' + typeof window.turnstileInstance);
                            log('challengeCompleted: ' + window.challengeCompleted);

                            // Show the captcha container (some Turnstile modes refuse to validate in display:none)
                            var capContainer = document.querySelector('#xf-captcha-visible');
                            if (capContainer) {
                                capContainer.style.display = 'block';
                                capContainer.style.position = 'fixed';
                                capContainer.style.top = '10px';
                                capContainer.style.left = '10px';
                                capContainer.style.zIndex = '99999';
                                log('captcha container shown');
                            }

                            // Try to manually execute turnstile (works for invisible mode)
                            if (window.turnstile && window.turnstileInstance) {
                                try {
                                    window.turnstile.execute(window.turnstileInstance);
                                    log('turnstile.execute() called on instance');
                                } catch(e) { log('turnstile.execute err: ' + e.message); }
                            }
                        } catch(e) { log('diag err: ' + e.message); }
                    }, 5000);

                    // Diagnostic après 15s : Turnstile a-t-il fini de résoudre ?
                    setTimeout(function() {
                        try {
                            log('@15s : challengeCompleted=' + window.challengeCompleted + ' #videoIframe=' + (document.querySelector('#videoIframe') ? document.querySelector('#videoIframe').innerHTML.substring(0,200) : 'missing'));
                        } catch(e) {}
                    }, 15000);

                    // Diagnostic après 30s : final state
                    setTimeout(function() {
                        try {
                            log('@30s : challengeCompleted=' + window.challengeCompleted);
                            // Cherche tout iframe dans la page
                            var allIframes = document.querySelectorAll('iframe');
                            log('@30s : total iframes in page=' + allIframes.length);
                            for (var i = 0; i < Math.min(allIframes.length, 5); i++) {
                                log('@30s iframe[' + i + '] src=' + allIframes[i].src);
                            }
                        } catch(e) {}
                    }, 30000);
                }

                trigger(0);

                // Polling fallback : si après 5s aucun iframe capturé, scan le DOM
                // pour un iframe rendu (DLE pourrait injecter dans #player)
                var pollCount = 0;
                var pollIntervalId = setInterval(function() {
                    pollCount++;
                    var iframes = document.querySelectorAll('iframe[src]');
                    for (var i = 0; i < iframes.length; i++) {
                        var src = iframes[i].src || iframes[i].getAttribute('src');
                        if (src && src.startsWith('http') && src.indexOf('papadustream') < 0
                            && src.indexOf('challenges.cloudflare.com') < 0
                            && src.indexOf('googleapis') < 0) {
                            log('DOM poll found iframe: ' + src);
                            onIframe(src);
                            clearInterval(pollIntervalId);
                            return;
                        }
                    }
                    if (pollCount > 80) { // 80 * 500ms = 40s
                        clearInterval(pollIntervalId);
                    }
                }, 500);
                } // end setupHooksAndTrigger
            })();
        """.trimIndent()
    }

    companion object {
        private const val TAG = "PapadustreamExtractor"

        private val ALLOWED_HOSTS = listOf(
            "papadustream.courses",
            "papadustream.motorcycles",
            "papadustream.dad",
            "papadustream.com",
            "papadustream.fit",
            "challenges.cloudflare.com",
            "ajax.googleapis.com",
            "code.jquery.com",
            "cdnjs.cloudflare.com",
            "googleapis.com",
            "gstatic.com",
            "jsdelivr.net",
            "cloudfront.net",
        )

        private val BLOCKED_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice",
            "popads", "popunder", "popcash", "propellerads",
            "exoclick", "juicyads", "trafficjunky",
            "googletagmanager", "google-analytics",
            "matomo", "piwik",
        )
    }
}
