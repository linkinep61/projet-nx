package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Session FRAnime singleton — UNE seule WebView Chromium réutilisée pour
 * toutes les extractions d'épisodes.
 *
 * 2026-05-16 v10 : avant, chaque extract() créait une nouvelle WebView (ou
 * une nouvelle Activity). Conséquence : à chaque clic d'épisode, FRAnime
 * relançait son écran "Chargement du profil / préférences / animés" qui prend
 * 3-5s. Avec 11 lecteurs pré-extraits en parallèle (preExtractTopServers),
 * ça faisait 11 boots successifs visibles à l'utilisateur.
 *
 * Cette session :
 *  - Crée UNE WebView au premier extract(), reste vivante pour le cycle de vie de l'app
 *  - Navigue cette même WebView vers chaque URL d'épisode → Next.js fait juste
 *    un re-render côté client (instant, pas de re-boot)
 *  - Sérialise via Mutex (pas de navigations parallèles concurrentes)
 *  - Capture l'iframe via shouldInterceptRequest + MutationObserver
 *  - Auto-click "Regarder l'épisode" via JS injecté
 *
 * Si Cloudflare Turnstile bloque la première fois, l'extracteur peut fallback
 * sur FranimeCaptureActivity pour seed les cookies, puis revenir ici.
 */
object FranimeSession {

    private const val TAG = "FranimeSession"

    /** Hosts vidéo qu'on reconnaît comme target iframe. */
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

    private val CAPTURE_SCRIPT = """
        (function(){
            try {
                // 1) Récupère l'index ET le nom du lecteur désiré
                function getDesiredLecteurIndex() {
                    try {
                        var m = window.location.search.match(/[?&]l=(\d+)/);
                        return m ? parseInt(m[1], 10) : 0;
                    } catch(e) { return 0; }
                }
                function getDesiredLecteurName() {
                    try {
                        var h = window.location.hash || '';
                        var m = h.match(/lecteur=([^&]+)/i);
                        return m ? decodeURIComponent(m[1]).toLowerCase() : '';
                    } catch(e) { return ''; }
                }
                // 2) Auto-click "Regarder l'épisode" (interstitiel CTA)
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
                    return false;
                }
                // 3) Switch lecteur via dropdown / select / boutons
                //    Plusieurs UI possibles ; on essaie par NOM en priorité (plus fiable),
                //    sinon fallback à l'INDEX. Le nom vient du fragment `#lecteur=name`.
                function logJs(msg) {
                    if (typeof FranimeBridge !== 'undefined') {
                        try { FranimeBridge.onLog(msg); } catch(e){}
                    }
                }
                function dumpDropdownStructure() {
                    try {
                        var all = document.querySelectorAll('button, [role="combobox"], [role="button"], select');
                        var infos = [];
                        for (var i = 0; i < all.length; i++) {
                            var el = all[i];
                            var t = (el.innerText || el.textContent || '').trim().substring(0, 50);
                            if (t.toLowerCase().indexOf('lecteur') !== -1) {
                                infos.push('TRIG['+i+'] tag=' + el.tagName + ' role=' + (el.getAttribute('role')||'') + ' aria=' + (el.getAttribute('aria-haspopup')||'') + ' text="' + t + '"');
                            }
                        }
                        // Dump iframes
                        var ifr = document.querySelectorAll('iframe');
                        for (var j = 0; j < ifr.length; j++) {
                            infos.push('IFRAME['+j+'] src=' + (ifr[j].src || '').substring(0, 80));
                        }
                        logJs('DOM: ' + infos.join(' | '));
                    } catch(e) { logJs('dumpDropdown err: ' + e); }
                }
                function optionMatchesName(text, name) {
                    if (!name) return false;
                    return text.toLowerCase().indexOf(name) !== -1;
                }
                function clickOption(el, label) {
                    try {
                        el.click();
                        // Certains composants veulent un mousedown/mouseup explicit
                        try {
                            var rect = el.getBoundingClientRect();
                            var evtInit = { bubbles: true, cancelable: true, view: window, clientX: rect.left + rect.width/2, clientY: rect.top + rect.height/2 };
                            el.dispatchEvent(new MouseEvent('mousedown', evtInit));
                            el.dispatchEvent(new MouseEvent('mouseup', evtInit));
                            el.dispatchEvent(new MouseEvent('click', evtInit));
                        } catch(e){}
                        logJs('clicked option: ' + label);
                        return true;
                    } catch(e){ return false; }
                }
                function switchLecteur(idx, name) {
                    if (window.__franime_lecteur_set === (name || idx)) return false;
                    // a) <select> natif
                    var selects = document.querySelectorAll('select');
                    for (var s = 0; s < selects.length; s++) {
                        var sel = selects[s];
                        var opts = sel.options || [];
                        var matchIdx = -1;
                        for (var o = 0; o < opts.length; o++) {
                            var t = (opts[o].text || '').toLowerCase();
                            if (t.indexOf('lecteur') === -1) continue;
                            if (name && optionMatchesName(t, name)) { matchIdx = o; break; }
                        }
                        if (matchIdx === -1 && idx < opts.length && (opts[idx].text || '').toLowerCase().indexOf('lecteur') !== -1) {
                            matchIdx = idx;
                        }
                        if (matchIdx !== -1) {
                            sel.selectedIndex = matchIdx;
                            sel.dispatchEvent(new Event('change', { bubbles: true }));
                            window.__franime_lecteur_set = (name || idx);
                            logJs('switchLecteur SELECT matchIdx=' + matchIdx + ' name=' + name);
                            return true;
                        }
                    }
                    // b) shadcn/ui combobox button : "Lecteur SIBNET", "Lecteur FILEMOON"…
                    //    Trouver le trigger qui contient "lecteur" et le NOM ACTUEL
                    //    (≠ tous les boutons options en dehors du dropdown ouvert).
                    var triggers = document.querySelectorAll('button[aria-haspopup], button[role="combobox"], [role="combobox"], [aria-haspopup="listbox"]');
                    if (triggers.length === 0) {
                        // Fallback générique : boutons contenant "lecteur"
                        triggers = document.querySelectorAll('button, [role="button"]');
                    }
                    for (var t = 0; t < triggers.length; t++) {
                        var trig = triggers[t];
                        var trigText = (trig.innerText || trig.textContent || '').toLowerCase();
                        if (trigText.indexOf('lecteur') === -1) continue;
                        try { trig.click(); } catch(e){}
                        var attemptNum = 0;
                        function tryClickOptionByName() {
                            attemptNum++;
                            if (attemptNum > 10) { logJs('combobox option not found name=' + name + ' idx=' + idx); return; }
                            var options = document.querySelectorAll(
                                '[role="option"], li[role="menuitem"], [role="listbox"] > *, [role="menu"] > *'
                            );
                            if (options.length === 0) {
                                setTimeout(tryClickOptionByName, 80); // v68: 200→80ms retry plus rapide
                                return;
                            }
                            // 1) Match par nom
                            if (name) {
                                for (var i = 0; i < options.length; i++) {
                                    var ot = (options[i].innerText || options[i].textContent || '').toLowerCase();
                                    if (optionMatchesName(ot, name)) {
                                        clickOption(options[i], 'name=' + name);
                                        window.__franime_lecteur_set = name;
                                        return;
                                    }
                                }
                            }
                            // 2) Fallback index (filtré sur ceux contenant "lecteur")
                            var lecteurOpts = [];
                            for (var j = 0; j < options.length; j++) {
                                var ot2 = (options[j].innerText || options[j].textContent || '').toLowerCase();
                                if (ot2.indexOf('lecteur') !== -1) lecteurOpts.push(options[j]);
                            }
                            if (idx < lecteurOpts.length) {
                                clickOption(lecteurOpts[idx], 'idx=' + idx);
                                window.__franime_lecteur_set = idx;
                            } else {
                                logJs('options found but no match: total=' + options.length + ' lecteurOnly=' + lecteurOpts.length);
                            }
                        }
                        setTimeout(tryClickOptionByName, 120); // v68: 300→120ms initial faster
                        return true;
                    }
                    return false;
                }
                // 4) Capture iframes
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
                if (window.__franime_clicker) clearInterval(window.__franime_clicker);
                window.__franime_lecteur_set = null; // reset
                var desiredIdx = getDesiredLecteurIndex();
                var desiredName = getDesiredLecteurName();
                logJs('CAPTURE_SCRIPT init: url=' + window.location.href + ' idx=' + desiredIdx + ' name=' + desiredName);
                var attempts = 0;
                window.__franime_clicker = setInterval(function(){
                    attempts++;
                    if (attempts > 60) { clearInterval(window.__franime_clicker); return; } // v68: 30→60 retries (200ms × 60 = 12s total même couverture)
                    if (attempts === 6 || attempts === 16) {
                        // Dump DOM structure to logs at strategic moments
                        dumpDropdownStructure();
                    }
                    tryClickWatchButton();
                    // Tente de switcher le lecteur dès que le watch button est passé
                    var currentSet = window.__franime_lecteur_set;
                    var matchByName = (desiredName && currentSet === desiredName);
                    var matchByIdx = (!desiredName && currentSet === desiredIdx);
                    if (!matchByName && !matchByIdx) {
                        switchLecteur(desiredIdx, desiredName);
                    }
                    reportIframes();
                }, 200);  // v68 : 1000→200ms tick. Réagit 5x plus vite quand l'iframe apparaît ou que le bouton devient cliquable.
                if (window.__franime_observer) {
                    try { window.__franime_observer.disconnect(); } catch(e){}
                }
                window.__franime_observer = new MutationObserver(function(){ reportIframes(); });
                window.__franime_observer.observe(document.body || document.documentElement, {
                    childList: true, subtree: true,
                    attributes: true, attributeFilter: ['src']
                });
            } catch(e){}
        })();
    """.trimIndent()

    private val context = StreamFlixApp.instance.applicationContext

    @Volatile private var webView: WebView? = null
    /** Bootstrap est lié à l'instance webView. Si webView meurt (Android la kill),
     *  il faut re-bootstraper la nouvelle. Tracker via System.identityHashCode. */
    @Volatile private var bootstrappedWebViewHash: Int = 0
    /** Timestamp dernier bootstrap. Force re-bootstrap après TTL pour gérer
     *  les sessions Next.js qui décayent dans le temps. */
    @Volatile private var lastBootstrapAt: Long = 0L
    /** v68 (user "il galère à se connecter à chaque serveur") :
     *  Signal complete dès que la home franime.fr/ a terminé onPageFinished.
     *  Bootstrap await ce signal au lieu d'un delay(8s) fixe. Si la home
     *  charge en 1s → bootstrap retourne en 1s. Sinon fallback timeout. */
    @Volatile private var homePageLoadedDeferred: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val BOOTSTRAP_TTL_MS = 5L * 60L * 1000L  // 5 min
    private val sessionBootstrapped: Boolean
        get() {
            val wv = webView ?: return false
            if (System.identityHashCode(wv) != bootstrappedWebViewHash) return false
            val age = System.currentTimeMillis() - lastBootstrapAt
            return age < BOOTSTRAP_TTL_MS
        }
    private val mutex = Mutex()

    /** État de la capture en cours. Mis à jour par les callbacks JS bridge / network. */
    @Volatile private var currentCapture: CompletableDeferred<String?>? = null
    @Volatile private var currentTargetUrl: String? = null
    /** URL d'embed précédemment capturée (à ignorer pour le prochain switch
     *  de lecteur, sinon on retourne l'iframe stale du lecteur précédent). */
    @Volatile private var rejectedEmbedUrl: String? = null

    private fun isEmbedUrl(src: String): Boolean {
        if (!src.startsWith("http")) return false
        val lower = src.lowercase()
        if (lower.contains("franime.fr")) return false
        if (lower.contains("cloudflare") || lower.contains("kitsu") ||
            lower.contains("recaptcha") || lower.contains("turnstile") ||
            lower.contains("challenges")) return false
        if (lower.contains("ad.a-ads") || lower.contains("doubleclick") ||
            lower.contains("googlesyndication") || lower.contains("a-ads.com")) return false
        // Match token explicite OU n'importe quel extracteur connu par son nom
        return EMBED_HOST_TOKENS.any { lower.contains(it, ignoreCase = true) } ||
            Extractor.identifyServiceName(src) != null
    }

    private fun reportCapturedUrl(src: String) {
        if (!isEmbedUrl(src)) return
        // Ignore si c'est la même URL que la précédente extraction (lecteur pas
        // encore switché côté JS) — wait pour le nouveau iframe.
        if (src == rejectedEmbedUrl) return
        val deferred = currentCapture ?: return
        if (!deferred.isCompleted) {
            Log.d(TAG, "captured: $src")
            deferred.complete(src)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface", "JavascriptInterface")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onIframeFound(src: String) {
                reportCapturedUrl(src)
            }
            @JavascriptInterface
            fun onLog(msg: String) {
                Log.d(TAG, "JS: $msg")
            }
        }, "FranimeBridge")

        wv.webViewClient = object : WebViewClient() {
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
                    Log.d(TAG, "intercept embed: $reqUrl")
                    reportCapturedUrl(reqUrl)
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                view?.evaluateJavascript(ANTI_DETECTION_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "onPageFinished($url)")
                view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                // v68 : signal la fin du load home pour débloquer bootstrap
                //   plus tôt si la page charge avant le timeout 8s.
                if (url != null && (url == "https://franime.fr/" || url.startsWith("https://franime.fr/?"))) {
                    homePageLoadedDeferred?.takeIf { !it.isCompleted }?.complete(Unit)
                }
            }
        }
        webView = wv
        return wv
    }

    /** Annule la capture en cours et stoppe la WebView. À appeler depuis le
     *  fragment quand l'user clique "Toucher pour changer de serveur" — sinon
     *  la WebView continue à charger inutilement. */
    fun cancelCurrent() {
        currentCapture?.let {
            if (!it.isCompleted) {
                Log.d(TAG, "cancelCurrent: completing pending capture with null")
                it.complete(null)
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { webView?.stopLoading() } catch (_: Exception) {}
        }
    }

    /** Pre-warm la session : crée la WebView + load `franime.fr/` pour
     *  établir cookies + faire jouer le "Chargement profil/préférences/animés".
     *  Re-fire après TTL (5 min) — la session Next.js peut décayer. */
    suspend fun bootstrap() {
        if (sessionBootstrapped) {
            Log.d(TAG, "bootstrap skipped (recent, age=${System.currentTimeMillis() - lastBootstrapAt}ms)")
            return
        }
        mutex.withLock {
            if (sessionBootstrapped) return
            // v68 : crée un Deferred AVANT de loader la home pour ne pas rater
            //   le onPageFinished. Réinitialisé à chaque bootstrap.
            val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
            homePageLoadedDeferred = deferred
            val wv = withContext(Dispatchers.Main) {
                val w = ensureWebView()
                Log.d(TAG, "bootstrap: loading franime.fr/")
                w.loadUrl("https://franime.fr/")
                w
            }
            // v68 : attendre onPageFinished avec fallback 8s. Si home charge
            //   en 1s, bootstrap retourne en 1s + petit settle JS de 500ms.
            val t0 = System.currentTimeMillis()
            val ready = kotlinx.coroutines.withTimeoutOrNull(8_000L) { deferred.await() }
            val loadElapsed = System.currentTimeMillis() - t0
            if (ready != null) {
                // Settle JS post-load (Next.js hydration, cookies)
                kotlinx.coroutines.delay(500L)
                Log.d(TAG, "bootstrap: home loaded in ${loadElapsed}ms (event-driven)")
            } else {
                Log.w(TAG, "bootstrap: home onPageFinished timeout 8s — proceeding anyway")
            }
            bootstrappedWebViewHash = System.identityHashCode(wv)
            lastBootstrapAt = System.currentTimeMillis()
            Log.d(TAG, "bootstrap done (wvHash=$bootstrappedWebViewHash, total=${System.currentTimeMillis() - t0}ms)")
        }
    }

    /** Map base URL (anime/épisode/lang sans `&l=N`) → dernière embed URL retournée.
     *  Permet de rejeter les captures stale lors d'un switch lecteur sur le même épisode. */
    private val lastEmbedByEpisode = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** v70 : track le DERNIER lecteur (sibnet/vidmoly/...) capturé par épisode.
     *  Permet de distinguer "user re-clique même lecteur" vs "user switch lecteur",
     *  pour ne PAS rejeter une capture URL identique sur same-lecteur. */
    private val lastLecteurByEpisode = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun episodeKey(pageUrl: String): String =
        pageUrl.substringBefore("&l=").substringBefore("?l=")

    /** Extrait l'URL iframe pour une page épisode FRAnime.
     *  Sérialisé via mutex — pas de navigations parallèles concurrentes.
     *  Si la WebView n'a pas été bootstrapped (ou qu'elle a été détruite et
     *  recréée), on bootstrap d'abord (chargement franime.fr/ pour la session
     *  Next.js) — l'utilisateur n'attendra pas le "Chargement profil/prefs/animes"
     *  à chaque épisode. */
    suspend fun extractEmbed(pageUrl: String, timeoutMs: Long = 30_000L): String? {
        // 2026-05-16 v12 : retiré la préemption qui tuait les pre-extracts en
        // parallèle. La cancellation native du coroutine (viewModel.cancelGetVideo)
        // libère le mutex en propre quand l'user-init veut prendre le tour.
        // Si pas encore bootstrappé, le faire d'abord.
        if (!sessionBootstrapped) {
            Log.d(TAG, "extractEmbed: bootstrap not done yet → bootstrapping first")
            bootstrap()
        }
        return mutex.withLock {
            val key = episodeKey(pageUrl)
            val prevForEpisode = lastEmbedByEpisode[key]
            // v70 (user "FRAnime galère, le même épisode se lit instant en browser") :
            //   Bug rejectedEmbedUrl — quand l'user re-clique le MÊME lecteur que
            //   la précédente capture, l'iframe URL est identique → rejetée → timeout.
            //   Fix: rejectedEmbedUrl seulement si le LECTEUR (extrait du fragment
            //   #lecteur=) a CHANGÉ depuis la dernière capture. Sinon, on accepte
            //   immédiatement la même URL (légitime, c'est le bon lecteur).
            val currentLecteur = pageUrl.substringAfter("#lecteur=", "").substringBefore("&")
                .lowercase().ifBlank { null }
            val lastLecteurForKey = lastLecteurByEpisode[key]
            val sameLecteur = currentLecteur != null && currentLecteur == lastLecteurForKey
            val deferred = CompletableDeferred<String?>()
            currentCapture = deferred
            currentTargetUrl = pageUrl
            rejectedEmbedUrl = if (sameLecteur) null else prevForEpisode
            withContext(Dispatchers.Main) {
                val wv = ensureWebView()
                // Double-check : si la WebView a été recréée entre bootstrap et ici,
                // re-bootstraper rapidement (load franime.fr/ d'abord)
                if (System.identityHashCode(wv) != bootstrappedWebViewHash) {
                    Log.d(TAG, "extractEmbed: WebView changed since bootstrap, reseeding session")
                    wv.loadUrl("https://franime.fr/")
                    // Attend un peu que la page se charge (sera complété par les
                    // checks Next.js mais on n'attend pas qu'ils finissent — le
                    // simple chargement html suffit pour seed la session).
                }
                Log.d(TAG, "extractEmbed: navigate to $pageUrl (rejectPrev=$prevForEpisode)")
                wv.loadUrl(pageUrl)
                bootstrappedWebViewHash = System.identityHashCode(wv)
            }
            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            currentCapture = null
            currentTargetUrl = null
            rejectedEmbedUrl = null
            if (result != null) {
                lastEmbedByEpisode[key] = result
                // v70 : enregistre aussi le lecteur correspondant pour détection
                //   "same lecteur" à la prochaine capture.
                if (currentLecteur != null) lastLecteurByEpisode[key] = currentLecteur
            }
            result
        }
    }
}
