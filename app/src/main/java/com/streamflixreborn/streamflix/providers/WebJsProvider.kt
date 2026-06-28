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

    companion object {
        /**
         * Pre-warm TOUS les WebJsProvider au boot de l'app : navigate sur baseUrl
         * en background pour passer CF challenge + injection JS AVANT que l'user
         * ne clique le provider. Résultat : ouverture instantanée du provider.
         */
        fun warmUpAll() {
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                try {
                    val webJsProviders = Provider.providers.keys.filterIsInstance<WebJsProvider>()
                    for (p in webJsProviders) {
                        // 1 par 1 (pas en parallèle pour limiter la conso RAM WebView)
                        try {
                            android.util.Log.i("WebJsProvider", "warmUp START: ${p.name}")
                            p.warmUp()
                            android.util.Log.i("WebJsProvider", "warmUp DONE: ${p.name}")
                        } catch (e: Exception) {
                            android.util.Log.w("WebJsProvider", "warmUp ${p.name} failed: ${e.message}")
                        }
                    }
                } catch (_: Exception) {}
            }
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
            try {
                com.streamflixreborn.streamflix.utils.HomeCacheStore.write(
                    StreamFlixApp.instance, this, cats
                )
                android.util.Log.i(TAG, "warmUp: HomeBoot cache écrit (${cats.size} catégories)")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "warmUp HomeBoot cache write failed: ${e.message}")
            }
            // 2026-06-28 v4 : pre-load Glide RETIRÉ. Il bloquait le thread (44s !)
            // et le cache Glide n'était jamais hit pour cause de mismatch interne.
            // Le RecyclerView téléchargera les images au load normal (= pas pire).
            android.util.Log.i(TAG, "warmUp: pre-load Glide skipped (caused UI freeze)")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "warmUp pre-load failed: ${e.message}")
        }
    }

    private val http by lazy {
        OkHttpClient.Builder()
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
        webView?.let { return it }
        val ctx = StreamFlixApp.instance.applicationContext
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = android.webkit.WebSettings.getDefaultUserAgent(ctx)
            // Cache mode = DEFAULT (= comportement browser normal). LOAD_CACHE_ELSE_NETWORK
            // causait des timeouts sur les pages série jamais visitées (= rien en cache).
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        wv.addJavascriptInterface(Bridge(), "AndroidJsBridge")
        wv.webViewClient = WebViewClient()
        webView = wv
        return wv
    }

    /** Charge [url] dans la WebView et attend la fin du chargement (DOM prêt).
     *  Mutex : si warmUp + getHome se chevauchent, le 2e attend que le 1er
     *  finisse au lieu de lancer un loadUrl concurrent. */
    private suspend fun navigate(url: String, settleMs: Long = 300) {
        navMutex.lock()
        try {
            if (currentUrl == url) return  // déjà au bon endroit
            val done = CompletableDeferred<Unit>()
            main.post {
                val wv = ensureWebViewOnMain()
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) {
                        currentUrl = u ?: url
                        if (!done.isCompleted) done.complete(Unit)
                    }
                }
                wv.loadUrl(url)
            }
            withTimeoutOrNull(25_000) { done.await() }
            kotlinx.coroutines.delay(settleMs) // laisser hydrater le SSR/CSR
            injectJs()
        } finally {
            navMutex.unlock()
        }
    }

    private suspend fun injectJs() {
        val src = ensureJsSource()
        val done = CompletableDeferred<Unit>()
        main.post {
            webView?.evaluateJavascript(src) { done.complete(Unit) } ?: done.complete(Unit)
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
            if (items.isNotEmpty()) out.add(Category(name = c.optString("name", "—"), list = items))
        }
        return out
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
        navigate(pageUrl)
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
        navigate(pageUrl)
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
            // 2026-06-28 (user "vraiment un gros problème de serveur") : DROP des
            // sources WebJS nmlnode car injouables / pas de VF. On garde uniquement
            // le backup natif DessinAnime (= cascade CS/AS/WF/NM/FS) + cross-backup
            // anime (AnimeSama/Franime/etc) qui donnent du contenu vraiment FR.
            //
            // (Ancien launch fetchWebJsServers SUPPRIMÉ — sources nmlnode dropped)

            // 2bis) BACKUPS CROSS-PROVIDER par recherche titre (groupe ANIME) :
            // AnimeSama, FrenchAnime, VoirAnime, FrenchManga, Franime, etc. — chacun
            // cherche le titre et renvoie ses sources, ajoutées au fil de l'eau au
            // picker. Préfixe d'id "webjsxsbackup::<name>::" pour la délégation getVideo.
            // 2bis-bis) APPEL DIRECT AnimeSama par titre (= la méthode CORRECTE
            // pour récupérer les sources AnimeSama, identique à ce que la cascade
            // DessinAnime fait en interne). Renvoie vidmoly/sendvid/etc.
            launch {
                try {
                    val title = extractTitleHint(id)
                    if (title.length >= 3) {
                        val asProvider = Provider.providers.keys.firstOrNull { it.name == "AnimeSama" }
                        if (asProvider is AnimeSamaProvider) {
                            android.util.Log.i(TAG, "DIRECT AS: getAnimeSamaSourcesByTitle('$title') START")
                            val srvs = withTimeoutOrNull(15_000) {
                                asProvider.getAnimeSamaSourcesByTitle(title, videoType)
                            } ?: emptyList()
                            android.util.Log.i(TAG, "DIRECT AS: → ${srvs.size} sources")
                            if (srvs.isNotEmpty()) {
                                val wrapped = srvs.map { srv ->
                                    Video.Server(
                                        id = "webjsxsbackup::AnimeSama::${srv.id}",
                                        name = "⭐1 AnimeSama: ${srv.name}",
                                        src = srv.src,
                                        mirrors = srv.mirrors,
                                    )
                                }
                                send(wrapped)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "DIRECT AS failed: ${e.message}")
                }
            }

            launch {
                android.util.Log.i(TAG, "XBACKUP launch START id=$id")
                val title = try { extractTitleHint(id) } catch (e: Exception) {
                    android.util.Log.w(TAG, "XBACKUP extractTitleHint exception: ${e.message}")
                    ""
                }
                android.util.Log.i(TAG, "XBACKUP title='$title' (len=${title.length})")
                if (title.length >= 3) {
                    // 2026-06-28 (user "tu dois faire en sorte qu'on ait vraiment tous les
                    //   backups possibles et imaginables pour Dessin animé à part les backups
                    //   problématiques qui ont des challenges Cloudflare") :
                    //   on élargit au-delà du group ANIME pour inclure aussi les providers
                    //   généralistes (Cloudstream, Movix, Tmdb…) qui ont souvent de bonnes
                    //   sources VF/VOSTFR sur les animés populaires.
                    // 2026-06-28 (user "On devrait avoir AnimeSama et tous les providers
                    //   de dessin animé sont censés l'avoir") : FIX bug nom — vraies
                    //   constantes name des providers :
                    //   - "FRAnime" (PAS "Franime")
                    //   - "TMDb (fr-FR)" / "TMDb (en-US)" / etc. → match par prefix
                    val PRIO = listOf("AnimeSama", "FRAnime", "FrenchManga", "VoirAnime", "FrenchAnime",
                                      "Cloudstream", "Movix", "FrenchStream", "Wiflix",
                                      "Papadustream", "NetMirror", "Frembed")
                    val PRIO_PREFIX = listOf("TMDb")  // = TMDb (fr-FR), TMDb (en-US), …
                    val EXCLUDED = emptySet<String>()
                    val allProviders = Provider.providers.keys
                    val crossBackups = allProviders
                        .asSequence()
                        .filter { it.name != this@WebJsProvider.name }
                        .filter { it !is WebJsProvider }
                        .filter { it.name !in EXCLUDED }  // exclu captchas
                        .filter { it.baseUrl.trimEnd('/').lowercase() != baseUrl.trimEnd('/').lowercase() }
                        .filter { p -> PRIO.contains(p.name) || PRIO_PREFIX.any { p.name.startsWith(it) } }
                        .sortedBy { p ->
                            val exact = PRIO.indexOf(p.name)
                            if (exact >= 0) exact else 100 + PRIO_PREFIX.indexOfFirst { p.name.startsWith(it) }
                        }
                        .toList()
                    android.util.Log.i(TAG, "XBACKUP candidates RESOLVED=${crossBackups.map { it.name }} (allProviders=${allProviders.size})")
                    android.util.Log.i(TAG, "XBACKUP candidates=${crossBackups.map { it.name }}")
                    crossBackups.forEach { p ->
                        launch {
                            android.util.Log.d(TAG, "XBACKUP[${p.name}] search('$title') START")
                            try {
                                val matches = withTimeoutOrNull(10_000) { p.search(title, 1) }
                                if (matches == null) { android.util.Log.w(TAG, "XBACKUP[${p.name}] search TIMEOUT"); return@launch }
                                android.util.Log.d(TAG, "XBACKUP[${p.name}] search → ${matches.size} matches")
                                val match = matches.firstOrNull { it is Movie || it is TvShow }
                                if (match == null) { android.util.Log.w(TAG, "XBACKUP[${p.name}] NO match Movie/TvShow"); return@launch }
                                android.util.Log.d(TAG, "XBACKUP[${p.name}] match id=${(match as? TvShow)?.id ?: (match as? Movie)?.id}")

                                // 2026-06-28 (user "ça doit être générique tu vois faut que
                                //   tu répares tes recherches pour vraiment que ça matche
                                //   avec n'importe quoi et pas que pour ce provider") :
                                //   délègue au helper CrossProviderResolver (= générique,
                                //   réutilisable par n'importe quel pipeline cross-provider).
                                val srvs = com.streamflixreborn.streamflix.utils.CrossProviderResolver
                                    .resolveAndFetchServers(p, match, videoType, timeoutMs = 15_000)
                                if (srvs.isEmpty()) { android.util.Log.w(TAG, "XBACKUP[${p.name}] getServers → 0 (= match résolu mais aucun server)"); return@launch }
                                android.util.Log.i(TAG, "XBACKUP[${p.name}] getServers → ${srvs.size} servers")
                                // Filtre uniquement les sources POUBELLE : YouTube (bandes-annonces),
                                // hosts morts connus. PAS de filtre langue (= décision user 2026-06-28).
                                val cleanSrvs = srvs.filter { srv ->
                                    val src = srv.src.lowercase()
                                    val name = srv.name.lowercase()
                                    !src.contains("youtube.com") && !src.contains("youtu.be") &&
                                    !name.contains("youtube") && !name.contains("trailer") &&
                                    !name.contains("bande-annonce") && !name.contains("annonce")
                                }
                                if (cleanSrvs.isNotEmpty()) {
                                    // Préfixe numéroté pour tri alpha picker :
                                    // ⭐1 AnimeSama / ⭐2 Franime / ⭐3 FrenchManga / ⭐4 VoirAnime
                                    val PRIO = listOf("AnimeSama", "Franime", "FrenchManga", "VoirAnime")
                                    val rank = PRIO.indexOf(p.name).let { if (it >= 0) it + 1 else 5 }
                                    val wrapped = cleanSrvs.map { srv ->
                                        Video.Server(
                                            id = "webjsxsbackup::${p.name}::${srv.id}",
                                            name = "⭐$rank ${p.name}: ${srv.name}",
                                            src = srv.src,
                                            mirrors = srv.mirrors,
                                        )
                                    }
                                    android.util.Log.i(TAG, "XBACKUP[${p.name}] SEND +${wrapped.size} (filtered ${srvs.size - cleanSrvs.size} youtube/trailer)")
                                    send(wrapped)
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "XBACKUP[${p.name}] EXCEPTION: ${e.message}")
                            }
                        }
                    }
                } else {
                    android.util.Log.w(TAG, "XBACKUP SKIP : title too short ('$title')")
                }
            }

            // 2) Backup natif (DessinAnimeProvider / FrenchAnimeProvider / …)
            // 2026-06-28 (user "fais attention si tu exclus à celui-là ça doit pas
            //   exclure sur les autres") : on ne MET PLUS skipWiflixForExternalCall=true
            //   globalement — c'était un flag persistant qui bloquait Wiflix dans
            //   DessinAnime natif même quand utilisé normalement. Le filtre par
            //   nom srv.name plus bas suffit pour le scope WebJsProvider.
            val backup = findNativeBackupProvider()
            android.util.Log.i(TAG, "progressive: backup search → ${backup?.name ?: "NULL"} (baseUrl=$baseUrl)")
            if (backup != null) {
                launch {
                    try {
                        // CONVERTIT l'id WebJS au format attendu par le natif
                        val nativeId = convertIdForNativeProvider(id, backup.name)
                        android.util.Log.i(TAG, "progressive: backup id conversion '$id' → '$nativeId'")
                        if (backup is ProgressiveServersProvider) {
                            // Relay du flow → chaque batch émis (natifs DessinAnime
                            // puis CS, AS, WF, NM au fur et à mesure)
                            backup.getServersProgressive(nativeId, videoType).collect { batch ->
                                if (batch.isNotEmpty()) {
                                    val wrapped = batch
                                        // 2026-06-28 : plus de filtre Wiflix (= autre session
                                        //   a réparé le captcha Cloudflare auto en 2s).
                                        .filter { _ -> true }
                                        .map { srv ->
                                            Video.Server(
                                                id = "webjsbackup::${backup.name}::${srv.id}",
                                                name = "Backup: ${srv.name}",
                                                src = srv.src,
                                                mirrors = srv.mirrors,
                                            )
                                        }
                                    android.util.Log.i(TAG, "progressive: backup ${backup.name} +${wrapped.size} servers")
                                    send(wrapped)
                                }
                            }
                        } else {
                            // Backup non-progressif → 1 seule émission batch à la fin
                            val nativeId2 = convertIdForNativeProvider(id, backup.name)
                            val natives = withTimeoutOrNull(25_000) { backup.getServers(nativeId2, videoType) } ?: emptyList()
                            if (natives.isNotEmpty()) {
                                val wrapped = natives
                                    .filter { srv ->
                                        !srv.name.contains("WF · ", ignoreCase = true) &&
                                        !srv.name.contains("Wiflix", ignoreCase = true)
                                    }
                                    .map { srv ->
                                        Video.Server(
                                            id = "webjsbackup::${backup.name}::${srv.id}",
                                            name = "Backup: ${srv.name}",
                                            src = srv.src,
                                            mirrors = srv.mirrors,
                                        )
                                    }
                                android.util.Log.i(TAG, "progressive: backup ${backup.name} (batch) +${wrapped.size} servers")
                                send(wrapped)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "progressive backup ${backup.name} failed: ${e.message}")
                    }
                }
            }
        }

    /** Lance extractServers JS dans la WebView et renvoie les sources WebJS. */
    private suspend fun fetchWebJsServers(id: String): List<Video.Server> {
        // Strip langue éventuelle pour la navigation
        val rawId = id.substringBefore("@")
        val lang = id.substringAfter("@", "").takeIf { it.isNotBlank() }
        val pageUrl = baseUrl.trimEnd('/') + "/" + rawId.trimStart('/')
        navigate(pageUrl)
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
        // 2. Fallback : extrait du slug, début de l'id (movie/<slug> ou tv/<slug>/season/ep)
        val slugPart = id.removePrefix("movie/").removePrefix("tv/").substringBefore("/")
        return slugPart
            .replace(Regex("^\\d+-"), "")           // retire préfixe TMDB ID
            .replace(Regex("-(20\\d{2}|19\\d{2})$"), "") // retire année finale
            .replace("-", " ")
            .trim()
    }

    /** Convertit un id WebJS au format natif provider.
     *  Ex DessinAnime : "movie/<slug>" → "movie::<slug>", "tv/<slug>/3/5" → "tv::<slug>::s3::e5".
     *  Si le natif n'a pas ce format, on renvoie l'id original. */
    private fun convertIdForNativeProvider(id: String, providerName: String): String {
        if (providerName != "DessinAnime") return id  // pour l'instant DessinAnime uniquement
        // Strip suffix lang (@vf/@vostfr) éventuel
        val rawId = id.substringBefore("@")
        val parts = rawId.split("/")
        return when {
            parts.size == 2 && parts[0] == "movie" -> "movie::${parts[1]}"
            parts.size == 2 && parts[0] == "tv" -> "tv::${parts[1]}"
            parts.size == 4 && parts[0] == "tv" -> {
                // tv/<slug>/<season>/<episode> → tv::<slug>::s<season>::e<episode>
                "tv::${parts[1]}::s${parts[2]}::e${parts[3]}"
            }
            else -> rawId
        }
    }

    /** Cherche un provider in-app NATIF avec la même baseUrl (= source de backup). */
    private fun findNativeBackupProvider(): Provider? {
        val mine = baseUrl.trimEnd('/').lowercase()
        val all = Provider.providers.keys.toList()
        android.util.Log.d(TAG, "findNativeBackupProvider: looking for baseUrl=$mine among ${all.size} providers")
        val match = all.firstOrNull { p ->
            val theirs = p.baseUrl.trimEnd('/').lowercase()
            val ok = p.name != name && p !is WebJsProvider && theirs == mine
            if (theirs == mine) android.util.Log.d(TAG, "  candidate ${p.name} baseUrl=$theirs WebJs=${p is WebJsProvider} same-name=${p.name == name} → ok=$ok")
            ok
        }
        return match
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // ─── CROSS-BACKUP search par titre : délègue au provider natif ──
        if (server.id.startsWith("webjsxsbackup::")) {
            val rest = server.id.removePrefix("webjsxsbackup::")
            val sep = rest.indexOf("::")
            if (sep > 0) {
                val providerName = rest.substring(0, sep)
                val origId = rest.substring(sep + 2)
                val backup = Provider.providers.keys.firstOrNull { it.name == providerName }
                if (backup != null) {
                    val origServer = Video.Server(
                        id = origId,
                        name = server.name.removePrefix("$providerName: "),
                        src = server.src,
                        mirrors = server.mirrors,
                    )
                    return backup.getVideo(origServer)
                }
            }
        }
        // ─── BACKUP : délègue au provider natif si le server vient de lui ──
        if (server.id.startsWith("webjsbackup::")) {
            val rest = server.id.removePrefix("webjsbackup::")
            val sep = rest.indexOf("::")
            if (sep > 0) {
                val providerName = rest.substring(0, sep)
                val origId = rest.substring(sep + 2)
                val backup = Provider.providers.keys.firstOrNull { it.name == providerName }
                if (backup != null) {
                    val origServer = Video.Server(
                        id = origId,
                        name = server.name.removePrefix("Backup: "),
                        src = server.src,
                        mirrors = server.mirrors,
                    )
                    return backup.getVideo(origServer)
                }
            }
        }

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
            val headers = mapOf(
                "Referer" to baseUrl.trimEnd('/') + "/",
                "Origin" to baseUrl.trimEnd('/'),
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
            )
            return Video(source = src, headers = headers)
        }
        // Sinon : délègue aux Extractors IN-APP (uqload/hydrax/sendvid…) — robuste.
        return Extractor.extract(server.src)
    }
}
