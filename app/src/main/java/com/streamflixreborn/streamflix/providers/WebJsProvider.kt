package com.streamflixreborn.streamflix.providers

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import com.streamflixreborn.streamflix.utils.NetworkClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 2026-06-27 — MOTEUR DE PROVIDERS HÉBERGÉS SUR GITHUB (modèle Wiseplay/CloudStream).
 *
 * But (user) : pouvoir réparer/ajouter N'IMPORTE QUEL provider en éditant un
 * fichier .js sur GitHub, SANS republier l'APK.
 *
 * Principe :
 *   - Une WebView headless est chargée sur [baseUrl] du site cible → on hérite
 *     gratuitement des cookies Cloudflare + d'un `fetch` same-origin + du DOM
 *     (querySelector, clics) + de l'exécution JS de la page.
 *   - On télécharge le script provider depuis [jsUrl] (raw GitHub) et on l'injecte.
 *     Le script définit `window.__P = { getHome, search, getMovies, getTvShows,
 *     getTvShow, getEpisodesBySeason, extractServers, ... }` (fonctions async
 *     renvoyant du JSON).
 *   - Kotlin appelle ces fonctions via evaluateJavascript + un pont
 *     `AndroidJsBridge.resolve/reject` (Promise → CompletableDeferred).
 *   - getVideo : le JS ne fait que fournir l'URL du host (uqload, hydrax…) ;
 *     l'extraction finale reste faite par les Extractors IN-APP (robustes).
 *
 * Contrat JSON (modèles app) :
 *   item    = { "type":"movie"|"tv", "id":"<path>", "title", "poster", "banner",
 *               "year", "overview" }
 *   home    = [ { "name":"<cat>", "items":[item,...] }, ... ]
 *   tvShow  = item + { "seasons":[ {"id","number","title"} ] }
 *   episode = { "id":"<path>", "number", "title", "poster" }
 *   server  = { "name", "url" }
 *
 * Les `id` sont des CHEMINS relatifs (ex "movie/10191-dragons",
 * "tv/31910-naruto/1/1") → getServers navigue la WebView sur baseUrl+"/"+id.
 */
open class WebJsProvider(
    override val name: String,
    override val baseUrl: String,
    private val jsUrl: String,
    override val logo: String,
    override val language: String,
    // 2026-07-04 : si true, on NE navigue PAS vers les pages détail (fiche/saison/épisode) —
    //   on garde le WebView sur baseUrl et le JS récupère les données par fetch RSC depuis ce
    //   contexte. Nécessaire pour dessinanime.cc : naviguer vers une page détail y déclenche un
    //   challenge Cloudflare (shell 27 Ko) qui empoisonne le contexte → tous les fetch RSC 403.
    //   Depuis baseUrl (déjà validé CF), les fetch passent (200). Le path détail est passé au JS.
    private val detailViaFetch: Boolean = false,
) : Provider, ProgressiveServersProvider {

    private val TAG = "WebJsProvider[$name]"
    private val main = Handler(Looper.getMainLooper())
    private val reqId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @Volatile private var webView: WebView? = null
    @Volatile private var jsSource: String? = null
    @Volatile private var currentUrl: String = ""

    // Mutex pour éviter double navigate concurrent (= warmUp + getHome se chevauchent)
    private val navMutex = kotlinx.coroutines.sync.Mutex()

    // 2026-07-03 (opti TV, user "on garde les providers web") : on garde les WebJS providers
    //   MAIS sur low-RAM (Chromecast) chaque WebView vivante coûte ~30MB de mémoire GPU
    //   (meminfo : 2 WebViews = 53MB Graphics à plat) → le heap sature → GC de 3s → gel.
    //   FIX : sur low-RAM, on DÉTRUIT la WebView après un délai d'inactivité (idle = 0
    //   WebView). Recréée à la demande (nav relancée). Rien n'est déconnecté. Mobile : inchangé.
    private val lowRam = Runtime.getRuntime().maxMemory() < 200L * 1024 * 1024
    @Volatile private var releaseScheduled: Runnable? = null

    /** Programme la destruction de la WebView après 15s d'inactivité (low-RAM only).
     *  Reprogrammé à chaque nav ; annulé dès réutilisation. Garde anti-crash : si une nav
     *  ou des appels JS sont en cours (navMutex verrouillé / pending non vide), on reporte. */
    private fun scheduleWebViewRelease() {
        if (!lowRam) return
        main.post {
            releaseScheduled?.let { main.removeCallbacks(it) }
            val r = Runnable {
                releaseScheduled = null
                if (navMutex.isLocked || pending.isNotEmpty()) { scheduleWebViewRelease(); return@Runnable }
                webView?.let { wv ->
                    try { wv.stopLoading(); wv.loadUrl("about:blank"); wv.destroy() } catch (_: Throwable) {}
                }
                webView = null
                currentUrl = ""
                android.util.Log.i(TAG, "WebView libérée (idle, low-RAM): $name")
            }
            releaseScheduled = r
            main.postDelayed(r, 15_000)
        }
    }

    companion object {
        // 2026-06-27 (REPAIR_HANDOFF #5) : coupe tout l'audio/vidéo de la WebView
        //   headless (l'extraction clique parfois "play" → pas de son parasite).
        private const val MUTE_JS = """
            (function(){
              function mute(){try{document.querySelectorAll('video,audio').forEach(function(m){m.muted=true;m.volume=0;});}catch(e){}}
              mute();
              try{ new MutationObserver(mute).observe(document.documentElement,{childList:true,subtree:true}); }catch(e){}
            })();
        """

        /**
         * Pre-warm TOUS les WebJsProvider au boot de l'app : navigate sur baseUrl
         * en background pour passer CF challenge + injection JS AVANT que l'user
         * ne clique le provider. Résultat : ouverture instantanée du provider.
         */
        fun warmUpAll() {
            // 2026-07-03 (opti TV) : sur low-RAM (Chromecast), NE PAS préchauffer — sinon on
            //   tient des WebViews chaudes dès le boot (meminfo Chromecast : ~2-3 WebViews =
            //   50-96MB de mémoire GPU) qui saturent le heap → GC de 3s → gel + serveurs en
            //   bloc. Les providers WebJS navigueront à la demande. Mobile (RAM large) : inchangé.
            if (Runtime.getRuntime().maxMemory() < 200L * 1024 * 1024) {
                android.util.Log.i("WebJsProvider", "warmUpAll SKIP (low-RAM : pas de préchauffage WebView)")
                return
            }
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                try {
                    // 2026-07-04 v4 (user "le préchauffage doit se faire AU BOOT ET à l'ouverture
                    //   du provider") : DessinAnime réactivé au boot. Mitigation ANR sur Chromecast :
                    //   delay initial 20s (au lieu de 10s) laisse l'app démarrer complètement (UI
                    //   dessinée, focus posé, cache home lu, providers register), PUIS on attend
                    //   que le main looper soit idle avant de lancer le premier warmUp = on ne
                    //   marche pas sur les toes du user qui navigue. Yield 5s entre providers.
                    kotlinx.coroutines.delay(20_000L)
                    awaitMainIdle()
                    val webJsProviders = Provider.providers.keys.filterIsInstance<WebJsProvider>()
                    for (p in webJsProviders) {
                        try {
                            android.util.Log.i("WebJsProvider", "warmUp START: ${p.name}")
                            p.warmUp()
                            // Persist cookies immédiatement → survit à un ANR/restart ultérieur.
                            try { android.webkit.CookieManager.getInstance().flush() } catch (_: Throwable) {}
                            android.util.Log.i("WebJsProvider", "warmUp DONE: ${p.name}")
                        } catch (e: Exception) {
                            android.util.Log.w("WebJsProvider", "warmUp ${p.name} failed: ${e.message}")
                        }
                        // Yield 5s + attendre idle → laisse main thread traiter events D-pad
                        // (évite l'accumulation input timeouts si 5-6 providers warmup à la suite).
                        kotlinx.coroutines.delay(5_000L)
                        awaitMainIdle()
                    }
                } catch (_: Exception) {}
            }
        }

        /** 2026-07-04 v4 : suspend jusqu'à ce que le main looper soit idle
         *  (aucune tâche en queue). Ainsi le warmUp WebView ne collide pas
         *  avec les touches D-pad de l'user OU les animations en cours.
         *  Timeout 5s pour ne pas bloquer indéfiniment sur Chromecast si le
         *  main thread est constamment busy. */
        private suspend fun awaitMainIdle() {
            try {
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    val d = kotlinx.coroutines.CompletableDeferred<Unit>()
                    android.os.Looper.getMainLooper().queue.addIdleHandler {
                        if (!d.isCompleted) d.complete(Unit)
                        false // one-shot
                    }
                    d.await()
                }
            } catch (_: Throwable) {}
        }
    }

    /** Pre-charge la WebView sur baseUrl + injecte le JS + PRE-DOWNLOAD jaquettes
     *  + ÉCRIT le HomeBoot cache disque → click DessinAnime Git = INSTANT. */
    suspend fun warmUp() {
        if (currentUrl.isNotBlank()) return  // déjà chaud
        val baseUrlSlash = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        navigate(baseUrlSlash)
        try {
            val cats = getHome()
            // ÉCRIT le cache HomeBoot disque MAINTENANT → quand l'user clique
            // le provider, cache read +5ms (categories=N) → SKIP network → INSTANT.
            // 2026-07-03 : ne PAS écraser un bon cache avec 0 catégories (CF fail TV).
            if (cats.isNotEmpty()) {
                try {
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.write(
                        StreamFlixApp.instance, this, cats
                    )
                    android.util.Log.i(TAG, "warmUp: HomeBoot cache écrit (${cats.size} catégories)")
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "warmUp HomeBoot cache write failed: ${e.message}")
                }
            } else {
                android.util.Log.w(TAG, "warmUp: HomeBoot cache SKIP (0 catégories, garde le cache existant)")
            }
            // 2026-06-28 v4 : pre-load Glide RETIRÉ. Il bloquait le thread (44s !)
            // et le cache Glide n'était jamais hit pour cause de mismatch interne.
            // Le RecyclerView téléchargera les images au load normal (= pas pire).
            android.util.Log.i(TAG, "warmUp: pre-load Glide skipped (caused UI freeze)")
            // 2026-07-04 (user "pré-chauffe le challenge /tv/* au boot en ouvrant une fiche ;
            //   une fois résolu tout fonctionne bien") : sur les providers detailViaFetch
            //   (DessinAnime), les pages /tv/* ont un challenge CF SÉPARÉ du home. On en
            //   pré-résout UNE au boot (fiche du 1er item) → la clearance /tv/* est établie →
            //   toutes les fiches s'ouvrent instantanément ensuite (plus d'attente ~15s).
            if (detailViaFetch && cats.isNotEmpty()) {
                val firstDetailId = cats.asSequence()
                    .flatMap { it.list.asSequence() }
                    .mapNotNull { item ->
                        when (item) {
                            is com.streamflixreborn.streamflix.models.TvShow -> item.id
                            is com.streamflixreborn.streamflix.models.Movie -> item.id
                            else -> null
                        }
                    }
                    .firstOrNull { it.startsWith("tv/") || it.startsWith("movie/") }
                if (firstDetailId != null) {
                    try {
                        navigate(baseUrl.trimEnd('/') + "/" + firstDetailId.trimStart('/'), waitFullContent = true)
                        android.util.Log.i(TAG, "warmUp: challenge /tv/* pré-résolu ($firstDetailId)")
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "warmUp prewarm détail KO: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "warmUp pre-load failed: ${e.message}")
        }
    }

    // 2026-07-04 : aligné sur le pattern Wiflix/FrenchAnime — utilise
    // NetworkClient.default (cookie jar partagé CookieManager ↔ WebView ↔ Glide,
    // DoH, pool connexions, UA par défaut). Avant = OkHttpClient isolé (pas de
    // cookies, pas de DoH).
    private val http by lazy {
        NetworkClient.default.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // ─────────────── Pont JS → Kotlin ───────────────
    inner class Bridge {
        @JavascriptInterface
        fun resolve(id: String, json: String) { pending.remove(id)?.complete(json) }
        @JavascriptInterface
        fun reject(id: String, err: String) { pending.remove(id)?.complete("__ERR__:$err") }
    }

    private suspend fun ensureJsSource(): String {
        jsSource?.let { return it }
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(jsUrl).header("Cache-Control", "no-cache").build()
            val body = http.newCall(req).execute().use { it.body?.string().orEmpty() }
            jsSource = body
            body
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebViewOnMain(): WebView {
        // Toute réutilisation de la WebView annule sa destruction programmée (low-RAM).
        releaseScheduled?.let { main.removeCallbacks(it); releaseScheduled = null }
        webView?.let { return it }
        val ctx = StreamFlixApp.instance.applicationContext
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 2026-06-27 (REPAIR_HANDOFF #5) : remis à true — l'extraction n'a pas
            //   besoin de lancer la lecture ; on évite tout autoplay audio/vidéo
            //   dans la WebView headless (+ muteAllMedia ci-dessous en renfort).
            mediaPlaybackRequiresUserGesture = true
            // 2026-07-03 : UA stealth (Pixel) — cohérent avec STEALTH_JS
            //   pour passer CF Turnstile sans challenge (surtout sur TV).
            com.streamflixreborn.streamflix.utils.WebViewResolver.initStealthUa(ctx)
            userAgentString = com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_UA
                ?: android.webkit.WebSettings.getDefaultUserAgent(ctx)
            // Cache mode = DEFAULT (= comportement browser normal). LOAD_CACHE_ELSE_NETWORK
            // causait des timeouts sur les pages série jamais visitées (= rien en cache).
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        wv.addJavascriptInterface(Bridge(), "AndroidJsBridge")
        wv.webViewClient = WebViewClient()
        // 2026-07-04 : forwarde les console.log du JS provider dans le logcat (diagnostic).
        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(cm: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.i(TAG, "JS> ${cm.message()}")
                return true
            }
        }
        webView = wv
        return wv
    }

    /** Charge [url] dans la WebView et attend la fin du chargement (DOM prêt).
     *  Mutex : si warmUp + getHome se chevauchent, le 2e attend que le 1er
     *  finisse au lieu de lancer un loadUrl concurrent. */
    private suspend fun navigate(url: String, settleMs: Long = 300, waitFullContent: Boolean = false) {
        navMutex.lock()
        try {
            if (currentUrl == url && !waitFullContent) return  // déjà au bon endroit
            val done = CompletableDeferred<Unit>()
            main.post {
                val wv = ensureWebViewOnMain()
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, u, favicon)
                        // 2026-07-03 : STEALTH_JS au document-start AVANT les scripts CF
                        //   Turnstile — spoof webdriver/chrome/plugins/WebGL/deviceMemory
                        //   pour que la WebView primaire passe CF sans challenge.
                        view?.evaluateJavascript(
                            com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_JS, null)
                    }
                    override fun onPageFinished(view: WebView?, u: String?) {
                        currentUrl = u ?: url
                        if (!done.isCompleted) done.complete(Unit)
                    }
                }
                wv.loadUrl(url)
            }
            withTimeoutOrNull(25_000) { done.await() }
            kotlinx.coroutines.delay(settleMs) // laisser hydrater le SSR/CSR
            // 2026-07-04 : sur les pages détail (dessinanime /tv/*), CF sert un SHELL de challenge
            //   Turnstile (~27 Ko) au lieu du contenu. On POLLE jusqu'à ce que la vraie page soit
            //   chargée (Turnstile auto-résolu grâce à STEALTH_JS), avec un reload-après-clearance
            //   comme le bypass natif WebViewResolver. Sinon le JS lit un shell → 0 saison.
            if (waitFullContent) waitForRealContent()
            injectJs()
        } finally {
            navMutex.unlock()
            // Low-RAM : (re)programme la libération de la WebView après inactivité.
            scheduleWebViewRelease()
        }
    }

    /** Lit l'innerHTML courant de la WebView (chaîne JS échappée — on ne veut que sa taille
     *  et détecter le shell de challenge, pas le contenu exact). */
    private suspend fun evalInnerHtml(): String {
        val d = CompletableDeferred<String>()
        main.post {
            val wv = webView
            if (wv == null) { d.complete(""); return@post }
            wv.evaluateJavascript("document.documentElement.innerHTML") { r -> if (!d.isCompleted) d.complete(r ?: "") }
        }
        return withTimeoutOrNull(4_000) { d.await() } ?: ""
    }

    /** Poll jusqu'à ce que la page ne soit plus un SHELL de challenge Cloudflare (Turnstile).
     *  Réinjecte STEALTH_JS + reload-après-clearance (comme WebViewResolver). ~18s max. */
    private suspend fun waitForRealContent() {
        var reloaded = false
        for (i in 0 until 30) {
            val html = evalInnerHtml()
            // 2026-07-04 : NE PAS se fier à "challenges.cloudflare.com"/"cf-turnstile" — la VRAIE
            //   page dessinanime les référence dans ses scripts CF (faux positif). Le seul vrai
            //   marqueur d'interstitiel = le titre "Just a moment" sur une page PETITE. Sinon,
            //   une page volumineuse = contenu réel chargé.
            val isInterstitial = html.length < 45_000 && html.contains("Just a moment", true)
            if (!isInterstitial && html.length > 55_000) {
                android.util.Log.i(TAG, "waitForRealContent OK (poll $i, len=${html.length})")
                return
            }
            // Après ~3s, reload une fois : le cf_clearance vient d'être posé → la page servira
            //   le contenu au lieu du shell.
            if (i == 6 && !reloaded) {
                reloaded = true
                main.post { try { webView?.reload() } catch (_: Throwable) {} }
            }
            kotlinx.coroutines.delay(600)
        }
        android.util.Log.w(TAG, "waitForRealContent : shell persistant après ~18s")
    }

    private suspend fun injectJs() {
        val src = ensureJsSource()
        val done = CompletableDeferred<Unit>()
        main.post {
            webView?.let { wv ->
                wv.evaluateJavascript(src) { done.complete(Unit) }
                wv.evaluateJavascript(MUTE_JS, null)  // REPAIR_HANDOFF #5
            } ?: done.complete(Unit)
        }
        withTimeoutOrNull(8_000) { done.await() }
    }

    /** Appelle `window.__P.<method>(<args JSON>)` (qui renvoie une Promise JSON). */
    private suspend fun call(method: String, vararg args: String, timeoutMs: Long = 30_000): String {
        // S'assure d'être sur une page same-origin (baseUrl) + JS injecté.
        if (currentUrl.isBlank()) navigate(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        var res = doCallRaw(method, args, timeoutMs)
        // SI le JS dit window.__P undefined → ré-inject + retry 1 fois.
        // Ça arrive après navigation SPA Next.js qui peut reset window.__P.
        if (res.startsWith("__ERR__:") && res.contains("undefined", ignoreCase = true)) {
            android.util.Log.i(TAG, "$method: window.__P lost → re-inject + retry")
            injectJs()
            res = doCallRaw(method, args, timeoutMs)
        }
        if (res.startsWith("__ERR__:")) {
            android.util.Log.w(TAG, "$method failed: ${res.removePrefix("__ERR__:")}")
            return "null"
        }
        return res
    }

    private suspend fun doCallRaw(method: String, args: Array<out String>, timeoutMs: Long): String {
        val id = "R${reqId.incrementAndGet()}"
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        val argList = args.joinToString(",")
        val js = """
            (function(){
              try {
                if (typeof window.__P === 'undefined' || typeof window.__P.$method !== 'function') {
                  AndroidJsBridge.reject("$id", "window.__P.$method undefined");
                  return;
                }
                Promise.resolve(window.__P.$method($argList))
                  .then(function(r){ AndroidJsBridge.resolve("$id", JSON.stringify(r==null?null:r)); })
                  .catch(function(e){ AndroidJsBridge.reject("$id", ""+(e&&e.message||e)); });
              } catch(e){ AndroidJsBridge.reject("$id", ""+(e&&e.message||e)); }
            })();
        """.trimIndent()
        main.post { webView?.evaluateJavascript(js, null) }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            pending.remove(id); "__ERR__:timeout"
        }
    }

    private fun q(s: String): String = JSONObject.quote(s)

    // ─────────────── Parsing JSON → modèles ───────────────
    private fun parseItem(o: JSONObject): AppAdapter.Item? {
        val id = o.optString("id").ifBlank { return null }
        val title = o.optString("title")
        val poster = o.optString("poster").takeIf { it.isNotBlank() }
        val banner = o.optString("banner").takeIf { it.isNotBlank() }
        val overview = o.optString("overview").takeIf { it.isNotBlank() }
        return if (o.optString("type") == "tv") {
            TvShow(id = id, title = title, poster = poster, banner = banner, overview = overview)
        } else {
            Movie(id = id, title = title, poster = poster, banner = banner, overview = overview)
        }
    }

    private fun parseItemArray(json: String): List<AppAdapter.Item> {
        if (json.isBlank() || json == "null") return emptyList()
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<AppAdapter.Item>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { parseItem(it)?.let(out::add) }
        return out
    }

    // ─────────────── Provider API ───────────────
    override suspend fun getHome(): List<Category> {
        // 2026-07-03 (user "DessinAnime recharge à chaque retour, comme s'il n'y avait pas
        //   de cache") : le warmup écrivait le cache home, mais il est SKIPPÉ sur low-RAM/TV
        //   → jamais de cache → re-fetch WebView à chaque ouverture. On lit/écrit ici le cache
        //   disque directement (comme Wiflix/FrenchAnime). Cache frais (<30 min) → retour
        //   INSTANTANÉ. Sinon fetch + réécriture (fin de fonction).
        run {
            val ctx = StreamFlixApp.instance
            val cached = com.streamflixreborn.streamflix.utils.HomeCacheStore.read(ctx, this)
            val age = com.streamflixreborn.streamflix.utils.HomeCacheStore.ageMs(ctx, this) ?: Long.MAX_VALUE
            // 2026-07-04 (user "le cache DessinAnime ne doit se vider que à la fermeture
            //   ou au refresh bouton") : TTL 12h pour DessinAnime (le warmUp boot rafraîchit
            //   de toute façon), 30 min pour les autres WebJS.
            val ttl = if (name.contains("DessinAnime", ignoreCase = true)) 12L * 60 * 60 * 1000L else 30L * 60 * 1000L
            if (!cached.isNullOrEmpty() && age < ttl) {
                // 2026-07-04 v2 : même en cache-hit, on trigger le prewarm du challenge
                //   /tv/* pour que le premier clic user sur une fiche soit instantané.
                //   Fallback essentiel du warmUp boot qui ANR sur Chromecast.
                try { schedulePrewarmDetailChallenge(cached) } catch (_: Throwable) {}
                return cached
            }
        }
        // Pas de retry — 1 seul fetch rapide. Si <8 cats, c'est le BG refresh
        // de HomeViewModel qui rattrapera silencieusement à la prochaine ouverture.
        val json = call("getHome")
        if (json.isBlank() || json == "null") return emptyList()
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<Category>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val itemsArr = c.optJSONArray("items") ?: continue
            val items = ArrayList<AppAdapter.Item>()
            for (j in 0 until itemsArr.length()) itemsArr.optJSONObject(j)?.let { parseItem(it)?.let(items::add) }
            // 2026-06-29 (REPAIR — user "active le carrousel dans l'app") : un provider
            //   web peut nommer sa catégorie carrousel "" (vide) OU un libellé FR
            //   ("À l'affiche", "En vedette", "À la une"...). On la mappe sur
            //   Category.FEATURED (= "") pour que le home la rende en SWIPER, sinon
            //   elle s'affiche en simple rail (= pas de carrousel, bug actuel).
            val rawName = c.optString("name", "—")
            val isFeatured = rawName.trim().lowercase().let { n ->
                n.isEmpty() || n in setOf(
                    "à l'affiche", "a l'affiche", "featured", "en vedette",
                    "à la une", "a la une", "à découvrir en avant"
                )
            }
            val catName = if (isFeatured) Category.FEATURED else rawName
            if (items.isNotEmpty()) out.add(Category(name = catName, list = items))
        }
        // 2026-07-04 : écrit le cache disque du home — GARDE : ne pas écraser un
        // cache riche avec un résultat partiel (hydratation incomplète / CF dégradé).
        if (out.isNotEmpty()) {
            try {
                val prevCached = com.streamflixreborn.streamflix.utils.HomeCacheStore.read(StreamFlixApp.instance, this)
                if (prevCached.isNullOrEmpty() || out.size >= prevCached.size - 1) {
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.write(StreamFlixApp.instance, this, out)
                }
            } catch (_: Exception) {}
        }
        // 2026-07-04 v2 (user "les cookies du menu synopsis doivent charger à
        //   L'OUVERTURE DU PROVIDER, pas juste au boot ; simule un clic jaquette
        //   pour déclencher le challenge CF qui débloque les saisons") :
        //   après avoir servi le home au user, on lance en background un navigate
        //   vers /tv/<slug> (comme si l'user avait cliqué la première jaquette).
        //   Ça résout le challenge CF /tv/* et persiste cf_clearance dans le
        //   CookieManager → premier clic user sur une fiche = INSTANTANÉ.
        //   Fallback CRITIQUE du warmUpAll boot qui ANR sur Chromecast (voir logs
        //   2026-07-04 20:39:29 : ANR 1s après warmUp START DessinAnime).
        try { schedulePrewarmDetailChallenge(out) } catch (_: Throwable) {}
        return out
    }

    /** 2026-07-04 : à l'ouverture du provider, simule un click sur la 1ère
     *  jaquette du home → charge la page /tv/<slug> ou /movie/<slug> → passe
     *  le challenge Cloudflare Turnstile → persiste cf_clearance dans le
     *  CookieManager. Le premier vrai clic user sur une fiche sera instantané.
     *
     *  Skips :
     *  - Provider pas detailViaFetch (= autre chose que DessinAnime pour l'instant)
     *  - cf_clearance déjà présent (challenge déjà passé lors du warmUp boot ou
     *    d'une session précédente)
     *  - Aucun item /tv/ ou /movie/ dans le home
     *
     *  Delay 3s : laisse le RecyclerView du home s'afficher avant de saturer
     *  le main thread avec la WebView (limite les ANR Chromecast). */
    private fun schedulePrewarmDetailChallenge(cats: List<Category>) {
        if (!detailViaFetch) return
        if (cats.isEmpty()) return
        // Cookie clearance déjà présent → skip (pas besoin de re-provoquer le challenge).
        val existingCookies = try {
            android.webkit.CookieManager.getInstance().getCookie(baseUrl)
        } catch (_: Throwable) { null }
        if (existingCookies?.contains("cf_clearance") == true) {
            android.util.Log.i(TAG, "prewarmDetail SKIP (cf_clearance déjà en CookieManager)")
            return
        }
        val firstDetailId = cats.asSequence()
            .flatMap { it.list.asSequence() }
            .mapNotNull { item ->
                when (item) {
                    is com.streamflixreborn.streamflix.models.TvShow -> item.id
                    is com.streamflixreborn.streamflix.models.Movie -> item.id
                    else -> null
                }
            }
            .firstOrNull { it.startsWith("tv/") || it.startsWith("movie/") }
        if (firstDetailId == null) {
            android.util.Log.i(TAG, "prewarmDetail SKIP (aucun tv/ ou movie/ dans le home)")
            return
        }
        // Lancement en background sur main dispatcher (WebView doit être main thread).
        // GlobalScope OK : c'est un préchauffage best-effort, pas rattaché à un ViewModel.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                // Laisser le home s'afficher fluidement avant de saturer le main thread.
                kotlinx.coroutines.delay(3_000L)
                android.util.Log.i(TAG, "prewarmDetail START ($firstDetailId)")
                navigate(
                    baseUrl.trimEnd('/') + "/" + firstDetailId.trimStart('/'),
                    waitFullContent = true
                )
                // Persist immédiat : si l'app crash ou ANR après, les cookies sont sauvés.
                try { android.webkit.CookieManager.getInstance().flush() } catch (_: Throwable) {}
                android.util.Log.i(TAG, "prewarmDetail DONE (cf_clearance persisté)")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "prewarmDetail KO: ${e.message}")
            }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> =
        parseItemArray(call("search", q(query), page.toString()))

    override suspend fun getMovies(page: Int): List<Movie> =
        parseItemArray(call("getMovies", page.toString())).filterIsInstance<Movie>()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        parseItemArray(call("getTvShows", page.toString())).filterIsInstance<TvShow>()

    override suspend fun getMovie(id: String): Movie {
        val json = call("getMovie", q(id))
        val o = try { JSONObject(json) } catch (_: Exception) { null }
        return Movie(
            id = id,
            title = o?.optString("title").orEmpty(),
            overview = o?.optString("overview")?.takeIf { it.isNotBlank() },
            poster = o?.optString("poster")?.takeIf { it.isNotBlank() },
            banner = o?.optString("banner")?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        // Pipeline normal — pas de compound wrapper VF/VOSTFR pour ce provider
        // (= décision user 2026-06-28 : "Retire la logique du VF VOSFR").
        val rawId = id.substringBefore("@")
        val lang = id.substringAfter("@", "").takeIf { it.isNotBlank() }
        val pageUrl = baseUrl.trimEnd('/') + "/" + rawId.trimStart('/')
        navigate(pageUrl, waitFullContent = detailViaFetch)
        val langArg = if (lang != null) q(lang) else "null"
        val json = call("getTvShow", q(rawId), langArg, timeoutMs = 20_000)
        val o = try { JSONObject(json) } catch (_: Exception) { null }
        val seasons = ArrayList<Season>()
        o?.optJSONArray("seasons")?.let { sArr ->
            for (i in 0 until sArr.length()) {
                val s = sArr.optJSONObject(i) ?: continue
                val rawSeasonId = s.optString("id")
                val seasonId = if (lang != null && !rawSeasonId.contains("@")) {
                    "$rawSeasonId@$lang"
                } else rawSeasonId
                seasons.add(Season(
                    id = seasonId,
                    number = s.optInt("number", i + 1),
                    title = s.optString("title").takeIf { it.isNotBlank() },
                    poster = s.optString("poster").takeIf { it.isNotBlank() },
                ))
            }
        }
        return TvShow(
            id = id,
            title = o?.optString("title").orEmpty(),
            overview = o?.optString("overview")?.takeIf { it.isNotBlank() },
            poster = o?.optString("poster")?.takeIf { it.isNotBlank() },
            banner = o?.optString("banner")?.takeIf { it.isNotBlank() },
            seasons = seasons,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val rawSeasonId = seasonId.substringBefore("@")
        val lang = seasonId.substringAfter("@", "").takeIf { it.isNotBlank() }
        val pageUrl = baseUrl.trimEnd('/') + "/" + rawSeasonId.trimStart('/') + "/1"
        navigate(pageUrl, waitFullContent = detailViaFetch)
        val langArg = if (lang != null) q(lang) else "null"
        val json = call("getEpisodesBySeason", q(rawSeasonId), langArg, timeoutMs = 20_000)
        if (json.isBlank() || json == "null") return emptyList()
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<Episode>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val rawEpId = e.optString("id")
            val epId = if (lang != null && !rawEpId.contains("@")) "$rawEpId@$lang" else rawEpId
            out.add(Episode(
                id = epId,
                number = e.optInt("number", i + 1),
                title = e.optString("title").takeIf { it.isNotBlank() },
                poster = e.optString("poster").takeIf { it.isNotBlank() },
            ))
        }
        return out
    }

    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id = id, name = id)
    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = id)

    /** Mode batch (compat ProgressiveServersProvider) : collecte le flow et renvoie tout. */
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val out = ArrayList<Video.Server>()
        try {
            getServersProgressive(id, videoType).collect { batch -> out.addAll(batch) }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getServers (collect progressive) failed: ${e.message}")
        }
        return out
    }

    /**
     * Mode PROGRESSIF : envoie les sources au fur et à mesure qu'elles arrivent.
     * — Lance le WebJS extractServers ET le backup natif en parallèle.
     * — Le backup natif (s'il implémente ProgressiveServersProvider, ex DessinAnime,
     *   Cloudstream, Movix) relay son propre flow → ses propres backups internes
     *   (CS/AS/WF/NM pour DessinAnime) arrivent eux aussi progressivement.
     * — Pas de bloc d'attente cumulé : les 1res sources s'affichent en ~2s, les
     *   backups se rajoutent au fil du temps.
     */
    override fun getServersProgressive(id: String, videoType: Video.Type): Flow<List<Video.Server>> =
        channelFlow {
            android.util.Log.i(TAG, "PROGRESSIVE START id=$id")
            // 1) Sources NATIVES WebJS (extractServers du JS hébergé).
            // 2026-07-03 : RÉTABLI — le user veut les serveurs natifs du site
            //   + les backups cross-provider en complément.
            launch {
                try {
                    val natives = withTimeoutOrNull(20_000) { fetchWebJsServers(id) } ?: emptyList()
                    android.util.Log.i(TAG, "progressive: WebJS natives → ${natives.size} servers")
                    if (natives.isNotEmpty()) send(natives)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "progressive: fetchWebJsServers failed: ${e.message}")
                }
            }



        }

    /** Lance extractServers JS dans la WebView et renvoie les sources WebJS. */
    private suspend fun fetchWebJsServers(id: String): List<Video.Server> {
        // Strip langue éventuelle pour la navigation
        val rawId = id.substringBefore("@")
        val lang = id.substringAfter("@", "").takeIf { it.isNotBlank() }
        val pageUrl = baseUrl.trimEnd('/') + "/" + rawId.trimStart('/')
        navigate(pageUrl, waitFullContent = detailViaFetch)
        // Passe la langue au JS qui cliquera le bouton AVANT de récupérer les hosts
        val langArg = if (lang != null) q(lang) else "null"
        val json = call("extractServers", langArg, timeoutMs = 15_000)
        val out = ArrayList<Video.Server>()
        if (json.isNotBlank() && json != "null") {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val url = s.optString("url").ifBlank { continue }
                    val tag = if (lang != null) "[${lang.uppercase()}] " else ""
                    out.add(Video.Server(id = "webjs::$i", name = tag + s.optString("name", "Lecteur ${i + 1}"), src = url))
                }
            } catch (_: Exception) {}
        }
        return out
    }

    /**
     * Récupère un hint titre pour la recherche cross-provider. Tente d'abord le JS
     * (getMovie/getTvShow → title) ; fallback = slug de l'id (= "movie/1327819-jumpers"
     * → "jumpers"). Le slug enlève le préfixe numérique TMDB + remplace les tirets.
     */
    /** Titre court issu du slug URL — titre canonique sans sous-titre d'arc.
     *  Ex: "tv/95479-jujutsu-kaisen/1/1" → "jujutsu kaisen". */
    private fun extractSlugTitle(id: String): String {
        val slugPart = id.removePrefix("movie/").removePrefix("tv/").substringBefore("/")
        return slugPart
            .replace(Regex("^\\d+-"), "")           // retire préfixe TMDB ID
            .replace(Regex("-(20\\d{2}|19\\d{2})$"), "") // retire année finale
            .replace("-", " ")
            .trim()
    }

    private suspend fun extractTitleHint(id: String): String {
        // 1. Tente via JS (titre propre)
        try {
            val isMovie = !id.startsWith("tv/")
            val json = withTimeoutOrNull(5_000) {
                if (isMovie) call("getMovie", q(id), timeoutMs = 5_000)
                else call("getTvShow", q(id), timeoutMs = 5_000)
            }
            if (!json.isNullOrBlank() && json != "null") {
                val o = try { JSONObject(json) } catch (_: Exception) { null }
                val title = o?.optString("title")?.trim().orEmpty()
                if (title.length >= 3) return title
            }
        } catch (_: Exception) {}
        // 2. Fallback : extrait du slug
        return extractSlugTitle(id)
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val src = server.src
        // ─── Court-circuit pour URLs DÉJÀ résolues (m3u8/mp4/dash) ──────
        // Certains sites (ex dessinanime.cc) renvoient déjà l'URL d'un proxy
        // HLS/MP4 côté serveur (extractor.nmlnode.cc/proxy/{hls,mp4}?token=…)
        // qui est jouable TEL QUEL par ExoPlayer — pas besoin d'extracteur
        // in-app. On passe le Referer/Origin du site source, certains proxies
        // vérifient ces headers.
        val isDirectStream = src.contains("extractor.nmlnode.cc/proxy/") ||
                Regex("""\.(m3u8|mpd)(\?|$)""").containsMatchIn(src) ||
                (Regex("""\.mp4(\?|$)""").containsMatchIn(src) && !src.contains("/embed"))
        if (isDirectStream) {
            // 2026-07-04 : STEALTH_UA (Chrome 131, même que WebView/Glide)
            // + injection cookie CookieManager (cf_clearance, etc.) — aligné
            // sur le pattern Wiflix/FrenchAnime pour la diffusion.
            val cookieStr = try {
                android.webkit.CookieManager.getInstance().getCookie(baseUrl)
            } catch (_: Exception) { null }
            val hdrs = mutableMapOf(
                "Referer" to baseUrl.trimEnd('/') + "/",
                "Origin" to baseUrl.trimEnd('/'),
                "User-Agent" to com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_UA,
            )
            if (!cookieStr.isNullOrBlank()) hdrs["Cookie"] = cookieStr
            return Video(source = src, headers = hdrs)
        }
        // Sinon : délègue aux Extractors IN-APP (uqload/hydrax/sendvid…) — robuste.
        return Extractor.extract(server.src)
    }
}
