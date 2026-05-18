package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * FRAnime watch2 resolver — WebView-based.
 *
 * franime.fr/watch2/?a=<hex> est une page protégée Cloudflare Turnstile qui,
 * une fois passée, injecte un iframe pointant vers le vrai host vidéo
 * (sibnet.ru, sendvid.com, filemoon, vidmoly, etc.).
 *
 * Stratégie v4 (2026-05-16) :
 *  1. Anti-détection : inject `navigator.webdriver=undefined` + plugins/languages
 *     spoofing AVANT que la page exécute son JS → Turnstile invisible passe
 *     plus souvent.
 *  2. Charge l'URL watch2 dans WebView Chromium
 *  3. Triple capture en parallèle :
 *     - shouldInterceptRequest : intercept network request vers host vidéo
 *     - DOM MutationObserver (via JS) : détecte nouveau iframe ajouté
 *     - Polling DOM (fallback) : scan iframes toutes les 2s
 *  4. Une fois capturé, délègue à `Extractor.extract(embedUrl)` qui appellera
 *     l'extracteur spécifique (SibnetExtractor, SendvidExtractor, etc.)
 *
 * Pattern proche de HydraxExtractor / Player4meExtractor.
 *
 * 2026-05-16 v4 : anti-detection JS injection + MutationObserver.
 */
class FranimeExtractor : Extractor() {
    override val name = "FRAnime"
    override val mainUrl = "https://franime.fr/anime"
    override val aliasUrls = listOf("https://franime.fr/watch2")

    // Pas de cache — chaque clic doit re-résoudre car URL contient token éphémère
    override val cacheTtlMs: Long = 0L

    private val context = StreamFlixApp.instance.applicationContext

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Hosts vidéo qu'on reconnaît comme target iframe — déclenche la capture.
     *  Inclut tous les hosts pour lesquels Streamflix a un extracteur, plus
     *  les mirrors connus de chaque host (filemoon, vidmoly, etc.). */
    private val EMBED_HOST_TOKENS = listOf(
        // Sibnet
        "sibnet.ru", "video.sibnet",
        // Sendvid
        "sendvid",
        // Filemoon (et tous ses mirrors)
        "filemoon", "moon.live", "moonembed", "bf0skv", "bysejikuar",
        "moflix-stream", "bysezoxexe", "bysebuho", "filemoon.sx",
        "bysekoze", "bysesayeveum", "lukefirst", "filemoon.site",
        "weneverbeenfree",
        // VidMoLy (toutes variations)
        "vidmoly", "vmoly",
        // Uqload
        "uqload", "ulto",
        // MixDrop
        "mixdrop", "mxdrop", "m1xdrop", "mixdroop",
        // VidHide
        "vidhide", "minochinos", "dhtpre", "peytonepre", "vidhideplus",
        "dingtezuni", "dintezuvio", "moflix-stream.click", "filelions",
        "streamhide",
        // Smoothpre
        "smoothpre",
        // Doodstream
        "doodstream", "dood.", "dooood", "dood-",
        // Streamtape
        "streamtape", "strtape", "tapesvc",
        // Voe / autres hosts FR communs
        "voe.sx", "voe-network",
        // StreamWish family (FRAnime peut occasionnellement servir via eux)
        "streamwish", "wishfast", "swhoi", "playerwish",
        // VK video (FRAnime : vk + vkru = 5232 occurrences, ~2%)
        "vk.com", "vk.ru", "vkvideo", "m.vk.",
        // Lpayer (1287 occurrences sur FRAnime)
        "lpayer", "embed4me",
        // Dailymotion / YourUpload / Okru / OneUpload / MailRu
        "dailymotion.com", "dai.ly",
        "yourupload",
        "ok.ru", "okru",
        "oneupload",
        "my.mail.ru", "mail.ru",
        // Variations FRAnime (mivalyo aliases, etc.)
        "mivalyo", "movearnpre",
    )

    /** Hosts à bloquer (ads / tracking / Cloudflare resources non utiles). */
    private val BLOCKED_HOSTS = listOf(
        "googlesyndication", "doubleclick", "googleads",
        "popads", "popunder", "popcash", "propellerads",
        "exoclick", "juicyads", "trafficjunky",
        "googletagmanager", "google-analytics",
        "mc.yandex.ru", "yandex.ru",
        "ad.a-ads", "a-ads.com",
        "adstagscripts",
    )

    /** Script injecté AVANT l'exécution du JS de la page pour spoofer
     *  les fingerprints utilisés par Cloudflare Turnstile pour détecter
     *  l'automation. Doit être executé via `evaluateJavascript` dans
     *  `onPageStarted` pour devancer les checks CF. */
    private val ANTI_DETECTION_SCRIPT = """
        (function(){
            try {
                Object.defineProperty(navigator, 'webdriver', {get: function(){ return undefined; }});
            } catch(e){}
            try {
                Object.defineProperty(navigator, 'plugins', {get: function(){ return [1,2,3,4,5]; }});
            } catch(e){}
            try {
                Object.defineProperty(navigator, 'languages', {get: function(){ return ['fr-FR','fr','en']; }});
            } catch(e){}
            try {
                window.chrome = window.chrome || { runtime: {} };
            } catch(e){}
            try {
                const originalQuery = window.navigator.permissions.query;
                window.navigator.permissions.query = (parameters) => (
                    parameters.name === 'notifications' ?
                        Promise.resolve({ state: Notification.permission }) :
                        originalQuery(parameters)
                );
            } catch(e){}
        })();
    """.trimIndent()

    /** Script qui pose un MutationObserver pour détecter dès qu'un iframe
     *  est ajouté au DOM (plus rapide que polling 2s). Notifie via
     *  l'interface Android.onIframeFound(src).
     *
     *  2026-05-16 v6 : ajout auto-click "Regarder l'épisode". FRAnime a un
     *  bouton interstitiel rouge qui doit être cliqué AVANT que l'iframe
     *  soit injecté dans la page. Sans ce clic, l'iframe n'apparaît jamais.
     *  On essaie de cliquer chaque seconde pendant 30s, ou jusqu'à ce qu'un
     *  iframe avec un host vidéo connu apparaisse. */
    private val MUTATION_OBSERVER_SCRIPT = """
        (function(){
            try {
                // Helper : essaie de cliquer le bouton "Regarder l'épisode"
                function tryClickWatchButton() {
                    var candidates = document.querySelectorAll(
                        'button, a, div[role="button"], span[role="button"], [class*="watch"], [class*="regarder"]'
                    );
                    for (var i = 0; i < candidates.length; i++) {
                        var el = candidates[i];
                        var text = (el.innerText || el.textContent || '').toLowerCase().trim();
                        if (text.indexOf('regarder') !== -1 &&
                            (text.indexOf('épisode') !== -1 || text.indexOf('episode') !== -1 ||
                             text.indexOf('l\'episode') !== -1 || text.indexOf("l'épisode") !== -1)) {
                            try { el.click(); return true; } catch(e){}
                        }
                    }
                    // Fallback : tout élément cliquable contenant juste "regarder"
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
                        if (src.startsWith('http') && typeof Android !== 'undefined') {
                            try { Android.onIframeFound(src); } catch(e){}
                        }
                    }
                }
                reportIframes();
                // Click watch button toutes les secondes pendant 30s
                var attempts = 0;
                var clickInterval = setInterval(function(){
                    attempts++;
                    if (attempts > 30) { clearInterval(clickInterval); return; }
                    if (tryClickWatchButton() && typeof Android !== 'undefined') {
                        try { Android.onWatchClicked(); } catch(e){}
                    }
                }, 1000);
                var observer = new MutationObserver(function(mutations){
                    reportIframes();
                });
                observer.observe(document.body || document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['src']
                });
            } catch(e){}
        })();
    """.trimIndent()

    /** Vérifie si on a un cookie cf_clearance valide pour franime.fr.
     *  Si oui → on peut tenter WebView invisible. Sinon → direct visible Activity. */
    private fun hasFranimeClearance(): Boolean {
        return try {
            val cookies = android.webkit.CookieManager.getInstance()
                .getCookie("https://franime.fr/")
            cookies?.contains("cf_clearance") == true
        } catch (_: Exception) { false }
    }

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract($link)")

        // 2026-05-16 v11 : utilise UNIQUEMENT le singleton FranimeSession.
        // Plus de fallback vers FranimeCaptureActivity visible (qui re-faisait
        // "Chargement profil/préférences/animes" à chaque changement de serveur).
        // Bootstrap idempotent — fait le boot UNE fois pour toute l'app.
        FranimeSession.bootstrap()
        val embedUrl = FranimeSession.extractEmbed(link, timeoutMs = 45_000L)
            ?: throw Exception("FranimeExtractor: failed to capture embed URL from $link")
        Log.d(TAG, "captured embed=$embedUrl, delegating to specific extractor")
        return Extractor.extract(embedUrl)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
    private suspend fun captureEmbedInWebView(url: String, timeoutMs: Long = 60_000L): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    var resolved = false
                    val state = java.util.concurrent.atomic.AtomicBoolean(false)  // navigatedToWatch2
                    // 2026-05-16 v5 : destroy hook stocké pour cleanup
                    // sur SUCCESS path (avant on n'avait que cancellation,
                    // causant un leak WebView → OOM après quelques tentatives).
                    var destroyHook: (() -> Unit)? = null
                    fun resolve(value: String?) {
                        if (!resolved && cont.isActive) {
                            resolved = true
                            cont.resume(value)
                        }
                        // Toujours destroy WebView après resolve, pas seulement sur cancel
                        destroyHook?.invoke()
                        destroyHook = null
                    }

                    // Vérifie si l'URL contient un host vidéo reconnu
                    fun isEmbedUrl(src: String): Boolean {
                        if (!src.startsWith("http")) return false
                        val lower = src.lowercase()
                        if (lower.contains("franime.fr")) return false
                        if (lower.contains("cloudflare") || lower.contains("kitsu") ||
                            lower.contains("recaptcha") || lower.contains("turnstile")) return false
                        return EMBED_HOST_TOKENS.any { lower.contains(it, ignoreCase = true) } ||
                            Extractor.identifyServiceName(src) != null
                    }

                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = ANDROID_CHROME_UA
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.loadsImagesAutomatically = true
                        settings.blockNetworkImage = false
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.mediaPlaybackRequiresUserGesture = false
                    }
                    // Enable cookies
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    // JS-Android bridge for MutationObserver callback
                    webView.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onIframeFound(src: String) {
                            if (resolved) return
                            if (isEmbedUrl(src)) {
                                Log.d(TAG, "EMBED via MutationObserver: $src")
                                // postValue from JS thread → switch to main
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    resolve(src)
                                }
                            }
                        }
                        @JavascriptInterface
                        fun onWatchClicked() {
                            Log.d(TAG, "JS clicked 'Regarder l'épisode' button")
                        }
                    }, "Android")

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host?.lowercase() ?: ""

                            // Block ads/trackers
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            // Detect target host : token-based liste explicite
                            if (EMBED_HOST_TOKENS.any { reqUrl.contains(it, ignoreCase = true) }) {
                                Log.d(TAG, "EMBED CAPTURED (token): $reqUrl")
                                resolve(reqUrl)
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            // Fallback : si l'URL est reconnue par UN extracteur quelconque
                            // (mainUrl ou aliasUrl), on intercepte aussi. Couvre les
                            // domaines/mirrors qu'on a oublié dans la liste hardcodée.
                            if (host.isNotBlank() && host != "franime.fr" &&
                                !host.endsWith("franime.fr") &&
                                !host.contains("cloudflare") &&
                                !host.contains("kitsu") &&
                                Extractor.identifyServiceName(reqUrl) != null) {
                                Log.d(TAG, "EMBED CAPTURED (identifyServiceName): $reqUrl")
                                resolve(reqUrl)
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return null
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            startedUrl: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            if (resolved || view == null) return
                            // v4 : injecter le script anti-détection DÈS le début
                            // pour devancer les checks Cloudflare Turnstile.
                            // navigator.webdriver = undefined etc.
                            view.evaluateJavascript(ANTI_DETECTION_SCRIPT, null)
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (resolved || view == null) return
                            Log.d(TAG, "onPageFinished($finishedUrl)")
                            // 2026-05-16 v7 : direct load de la page anime (plus de 2-step).
                            // Pose le MutationObserver (qui inclut l'auto-click Regarder
                            // l'épisode + détection iframe).
                            view.evaluateJavascript(MUTATION_OBSERVER_SCRIPT, null)

                            // Polling DOM as fallback (toutes les 2s pendant 50s)
                            val startMs = System.currentTimeMillis()
                            val MAX_POLL_MS = 50_000L
                            fun pollDom() {
                                if (resolved) return
                                view.evaluateJavascript(
                                    "(function(){var f=document.querySelectorAll('iframe');var s=[];for(var i=0;i<f.length;i++){s.push(f[i].src||'')}return s.join('|');})();"
                                ) { result ->
                                    val raw = result?.trim('"') ?: ""
                                    val sources = raw.split("|")
                                    val match = sources.firstOrNull { src ->
                                        isEmbedUrl(src)
                                    }
                                    val elapsed = System.currentTimeMillis() - startMs
                                    Log.d(TAG, "pollDom +${elapsed}ms sources=${sources.size} match=$match")
                                    if (match != null) {
                                        Log.d(TAG, "EMBED from DOM iframe (polling): $match")
                                        resolve(match)
                                    } else if (elapsed < MAX_POLL_MS) {
                                        view.postDelayed({ pollDom() }, 2_000L)
                                    }
                                }
                            }
                            view.postDelayed({ pollDom() }, 2_000L)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) { /* ignore */ }
                    }

                    // Charge directement la page d'épisode FRAnime
                    Log.d(TAG, "Loading anime page: $url")
                    webView.loadUrl(url)

                    // Pose le hook de destruction utilisé par resolve() ET cancellation.
                    // Idempotent (la 1ère invocation détruit, les suivantes sont no-op
                    // car destroyHook devient null).
                    destroyHook = {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                webView.stopLoading()
                                webView.removeJavascriptInterface("Android")
                                webView.destroy()
                            } catch (_: Exception) { }
                        }
                    }

                    cont.invokeOnCancellation {
                        resolved = true
                        destroyHook?.invoke()
                        destroyHook = null
                    }
                }
            }
        }

    companion object {
        private const val TAG = "FranimeExtractor"
    }
}
