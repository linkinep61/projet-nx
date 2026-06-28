package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
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
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DessinAnime (dessinanime.cc) — provider FR dessins animés + animes.
 *
 * 2026-05-18 v84 : recodé après suppression (le user dit que le site
 *   remarche). Catalogue classiques (Dragon Ball, Sailor Moon, Astérix,
 *   Tintin, Les Simpson…) + animes modernes + films Disney/Pixar.
 *
 * Architecture du site :
 *   - Next.js App Router (pas de __NEXT_DATA__ JSON ; SSR rendering)
 *   - API JSON propre : /api/search?q=<query> → liste {id,slug,title,
 *     posterPath,mediaType:MOVIE|TV,releaseYear,voteAverage}
 *   - Detail :
 *       Film    : /movie/<slug>
 *       Série   : /tv/<slug>
 *       Épisode : /tv/<slug>/<season>/<episode>
 *   - Chaque page contient un <iframe src="..."> unique vers l'host
 *     vidéo (uqload, sendvid, minochinos, etc.). Pas de sélecteur lecteur
 *     multi-source : un seul iframe par page → un seul serveur par item.
 *
 * Extraction :
 *   - getServers retourne 1 Server avec src = URL iframe
 *   - getVideo délègue à Extractor.extract qui choisit l'extractor
 *     selon le host (uqload, sendvid, minochinos…)
 */
object DessinAnimeProvider : Provider, ProgressiveServersProvider {

    override val name = "DessinAnime"
    override val baseUrl = "https://dessinanime.cc"
    override val logo = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_dessinanime"
    override val language = "fr"

    private const val TAG = "DessinAnime"

    // 2026-06-28 (user "Wiflix s'appelle toujours, captcha à l'ouverture") :
    //   flag global pour skip Wiflix backup quand le getServers est appelé
    //   depuis l'extérieur (= WebJsProvider DessinAnime Git). Évite que le
    //   captcha Wiflix s'affiche dans le pipeline cross-provider.
    @Volatile var skipWiflixForExternalCall: Boolean = false
    // 2026-06-09 : UA dynamique = celui du WebView Android pour que les
    //   cookies cf_clearance obtenus dans la WebView soient acceptés par
    //   OkHttp (Cloudflare lie le cookie au User-Agent).
    private val UA: String by lazy {
        try {
            android.webkit.WebSettings.getDefaultUserAgent(StreamFlixApp.instance)
        } catch (_: Throwable) {
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        }
    }

    // 2026-06-09 : CookieJar partagé avec WebView (NetworkClient.cookieJar
    //   proxie vers WebKit CookieManager). Quand l'user complète le Cloudflare
    //   Turnstile dans la WebView, le cookie cf_clearance est automatiquement
    //   disponible pour OkHttp.
    private val cookieJar = NetworkClient.cookieJar

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .cache(null) // 2026-06-09 : pas de cache HTTP (CF Turnstile rend les pages challenge cachées nocives)
            .build()
    }

    // ── HTTP simplifié (pattern Wiflix) ────────────────────────────────
    //   OkHttp direct → CF détecté → WebView VISIBLE immédiat (user résout
    //   Turnstile une fois) → cookie cf_clearance → WebView silent rapide.
    private val webViewMutex = Mutex()

    // 2026-06-23 (user "DessinAnime synopsis lent à ouvrir") : 5+ fetches
    //   catalogue en parallèle au démarrage → CF rate-limit → la requête
    //   synopsis (Zootopie 2) tombe en 403 → WebView bypass +1s. Semaphore(3)
    //   limite à 3 fetches simultanés max = CF content, synopsis fluide.
    private val networkSemaphore = Semaphore(3)
    private val challengeKeywords = listOf(
        "Just a moment", "cf-browser-verification", "challenge-running",
        "Checking your browser", "cf-turnstile", "Un instant", "Vérification"
    )

    // 2026-06-22 : flag CF — après le 1er bypass visible réussi, on bascule
    //   en WebView silent (le cookie cf_clearance est valide ~30 min).
    @Volatile private var cfBypassDone = false

    // 2026-06-23 : cache items catalogue PARSÉS (survit entre getMovies et
    //   getTvShows pour éviter le double-fetch des mêmes pages catalogue).
    private val catalogItemsCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, List<AppAdapter.Item>>>()
    private const val CATALOG_ITEMS_TTL_MS = 5 * 60 * 1000L

    fun resetState() {
        pageCache.clear()
        catalogItemsCache.clear()
        cfBypassDone = false
        Log.d(TAG, "State reset (pageCache+catalogItems vidé, cfBypassDone=false)")
    }

    // 2026-06-23 (user "pas de bypass pas de contenu") : gate qui fait le
    //   bypass CF UNE SEULE FOIS avant de charger quoi que ce soit. Toutes
    //   les méthodes publiques (getHome, getMovies, getTvShows) appellent
    //   ceci EN PREMIER. Si le bypass est déjà fait (cookie valide), no-op.
    //   Si pas fait, la PREMIÈRE entrée montre la WebView visible, les
    //   autres suspendent sur le mutex et re-check cfBypassDone → skip.
    private suspend fun ensureCfBypass() {
        if (cfBypassDone) return
        withContext(Dispatchers.IO) {
            webViewMutex.withLock {
                // Re-check après acquisition du lock — un autre coroutine
                // a peut-être déjà fait le bypass pendant qu'on attendait.
                if (cfBypassDone) {
                    Log.d(TAG, "ensureCfBypass → déjà fait pendant l'attente")
                    return@withLock
                }
                // Tester d'abord OkHttp (site sans CF ?)
                try {
                    val req = Request.Builder()
                        .url(baseUrl)
                        .header("User-Agent", UA)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string() ?: ""
                            val isChallenge = challengeKeywords.any { body.contains(it, ignoreCase = true) }
                            if (!isChallenge && body.length > 500) {
                                Log.d(TAG, "ensureCfBypass → OkHttp OK (pas de CF), bypass inutile")
                                cfBypassDone = true
                                pageCachePut("$baseUrl/", body)
                                return@withLock
                            }
                        }
                    }
                } catch (_: Exception) {}

                // CF actif → WebView VISIBLE (user résout Turnstile 1 fois)
                Log.d(TAG, "ensureCfBypass → WebView visible sur $baseUrl")
                try {
                    val ctx = StreamFlixApp.currentActivity ?: StreamFlixApp.instance
                    val resolver = WebViewResolver(ctx)
                    try {
                        val html = resolver.get(baseUrl, silent = false)
                        if (html.length > 500) {
                            val isChallenge = challengeKeywords.any { html.contains(it, ignoreCase = true) }
                            if (!isChallenge || html.length > 50_000) {
                                cfBypassDone = true
                                pageCachePut("$baseUrl/", html)
                                Log.d(TAG, "ensureCfBypass → OK (${html.length} chars)")
                            }
                        }
                    } finally {
                        try { resolver.cleanup() } catch (_: Throwable) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ensureCfBypass failed: ${e.message}")
                }
            }
        }
    }

    /** No-op — CF bypass automatique par httpGet si nécessaire. */
    fun prefetchCfBypassIfNeeded() {}

    // ── Poster URL cleaner ─────────────────────────────────────────────
    //   dessinanime.cc sert les posters via /_next/image?url=<TMDB_ENCODED>
    //   qui est derrière CF → 400 depuis weserv et direct. On extrait
    //   l'URL TMDB brute et on utilise /w342/ (optimal mémoire TV).
    //   2026-06-22 : w500 → w185 (Chromecast = grille TV ~150-180px wide,
    //   w185 suffit largement). w185 = 185×278px ≈ 206 KB ARGB_8888 vs
    //   w342 ≈ 700 KB. Réduit massivement la pression LOS/bitmap Glide.
    private val NEXT_IMAGE_PARAM = Regex("[?&]url=([^&]+)")
    private fun cleanPosterUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var url = raw.replace("&amp;", "&")  // HTML entity decode
        // _next/image wrapper → extraire l'URL TMDB encodée dans le param ?url=
        if (url.contains("/_next/image")) {
            val m = NEXT_IMAGE_PARAM.find(url)
            if (m != null) {
                url = try { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (_: Exception) { url }
            }
        }
        // Si c'est une URL TMDB, utiliser w185 (petit poster grille TV)
        url = url.replace("/original/", "/w185/")
            .replace("/w500/", "/w185/")
            .replace("/w780/", "/w185/")
            .replace("/w342/", "/w185/")
        // posterPath nu (juste le filename, pas d'URL complète) → construire l'URL TMDB
        if (url.startsWith("/") && !url.startsWith("//") && url.endsWith(".jpg")) {
            url = "https://image.tmdb.org/t/p/w185$url"
        }
        return url.takeIf { it.startsWith("http") }
    }

    // ── Cache RAM simple (pattern FrenchAnime) ─────────────────────────
    //   2026-06-22 : CAP à 8 entrées max (LRU par timestamp) pour éviter
    //   l'accumulation mémoire (chaque page HTML = 235 KB+). Avant : pas
    //   de limite → le cache grossissait indéfiniment en navigation.
    private data class CachedHtml(val html: String, val ts: Long)
    private val pageCache = java.util.concurrent.ConcurrentHashMap<String, CachedHtml>()
    private const val PAGE_CACHE_MAX = 5
    private const val CACHE_TTL_MS = 5 * 60 * 1000L        // 5 min (home, catalogue)
    private const val CACHE_TTL_DETAIL_MS = 2 * 60 * 60 * 1000L // 2h (film, série)

    /** Insère dans pageCache avec éviction LRU si >PAGE_CACHE_MAX. */
    private fun pageCachePut(url: String, html: String) {
        pageCache[url] = CachedHtml(html, System.currentTimeMillis())
        if (pageCache.size > PAGE_CACHE_MAX) {
            // Évicter l'entrée la plus ancienne
            val oldest = pageCache.entries.minByOrNull { it.value.ts }
            if (oldest != null) pageCache.remove(oldest.key)
        }
    }

    // ── HTTP helper ────────────────────────────────────────────────────

    /**
     * GET simple : OkHttp direct → WebView fallback si CF challenge.
     * Pattern identique à Wiflix tryOkHttp / FrenchAnime safeGetDoc.
     * PAS de Accept-Encoding manuel → OkHttp gère la décompression auto.
     */
    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        // 1) Cache RAM (= avant le semaphore, hit = 0 latence)
        val ttl = if (url.contains("/movie/") || url.contains("/tv/")) CACHE_TTL_DETAIL_MS else CACHE_TTL_MS
        pageCache[url]?.let { cached ->
            if (System.currentTimeMillis() - cached.ts < ttl) return@withContext cached.html
        }
        // 2026-06-23 : Semaphore(3) pour limiter les fetches parallèles (= évite
        //   CF rate-limit qui faisait tomber les requêtes synopsis en 403).
        networkSemaphore.withPermit {

        // 2) OkHttp direct (sans Accept-Encoding → auto décompression gzip)
        val isApi = url.contains("/api/")
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", if (isApi) "application/json, text/plain, */*"
                    else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Referer", baseUrl)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val isChallenge = challengeKeywords.any { body.contains(it, ignoreCase = true) }
                    if (!isChallenge && body.length > 200) {
                        Log.d(TAG, "httpGet OK $url (${body.length} chars)")
                        pageCachePut(url, body)
                        return@withContext body
                    }
                    if (isChallenge) Log.d(TAG, "CF challenge sur $url → WebView fallback")
                } else {
                    Log.w(TAG, "httpGet $url → ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "httpGet OkHttp $url failed: ${e.message}")
        }

        // API endpoints : pas de WebView fallback (JSON pur)
        if (isApi) return@withContext null

        // 2026-06-22 (user "le bypass CF doit être INSTANTANÉ comme Wiflix") :
        //   Si CF pas encore bypassé → WebView VISIBLE immédiat (l'user résout
        //   Turnstile une fois → cookie cf_clearance stocké). Après ça, WebView
        //   silent suffit (le cookie est valide ~30 min).
        if (!cfBypassDone) {
            // 3a) Le bypass n'est PAS encore fait — normalement ensureCfBypass()
            //   a été appelé avant, mais si on arrive ici c'est un edge case
            //   (cookie expiré mid-session ?). WebView visible avec re-check
            //   après mutex pour éviter les cascades.
            Log.d(TAG, "CF bypass pas encore fait → WebView visible pour $url")
            try {
                val html = webViewMutex.withLock {
                    // 2026-06-23 : re-check après acquisition du lock — un autre
                    //   coroutine a pu faire le bypass pendant qu'on attendait.
                    if (cfBypassDone) {
                        Log.d(TAG, "CF bypass fait pendant l'attente mutex → skip visible WebView pour $url")
                        null // on va retomber dans le else (WebView silent)
                    } else {
                        val ctx = StreamFlixApp.currentActivity ?: StreamFlixApp.instance
                        val resolver = WebViewResolver(ctx)
                        try { resolver.get(url, silent = false) } finally {
                            try { resolver.cleanup() } catch (_: Throwable) {}
                        }
                    }
                }
                if (html != null && html.length > 500) {
                    val isChallenge = challengeKeywords.any { html.contains(it, ignoreCase = true) }
                    if (!isChallenge || html.length > 50_000) {
                        Log.d(TAG, "httpGet WebView visible OK $url (${html.length} chars) — cfBypassDone=true")
                        cfBypassDone = true
                        pageCachePut(url, html)
                        return@withContext html
                    }
                }
                // Si html==null (bypass fait entre temps), on tombe dans le else ci-dessous
                if (html == null && cfBypassDone) {
                    // Retry OkHttp direct avec le cookie frais
                    try {
                        val req2 = Request.Builder()
                            .url(url)
                            .header("User-Agent", UA)
                            .header("Accept", if (isApi) "application/json, text/plain, */*"
                                else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                            .header("Referer", baseUrl)
                            .build()
                        httpClient.newCall(req2).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val body = resp.body?.string() ?: ""
                                val isCf = challengeKeywords.any { body.contains(it, ignoreCase = true) }
                                if (!isCf && body.length > 200) {
                                    Log.d(TAG, "httpGet retry OkHttp OK $url (${body.length} chars)")
                                    pageCachePut(url, body)
                                    return@withContext body
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "httpGet WebView visible $url failed: ${e.message}")
            }
        } else {
            // 3b) CF déjà bypassé → WebView silent (cookie valide, pas de dialog)
            try {
                val html = webViewMutex.withLock {
                    val ctx = StreamFlixApp.currentActivity ?: StreamFlixApp.instance
                    val resolver = WebViewResolver(ctx)
                    try { resolver.get(url, silent = true) } finally {
                        try { resolver.cleanup() } catch (_: Throwable) {}
                    }
                }
                if (html.length > 500) {
                    val isChallenge = challengeKeywords.any { html.contains(it, ignoreCase = true) }
                    if (!isChallenge || html.length > 50_000) {
                        Log.d(TAG, "httpGet WebView silent OK $url (${html.length} chars)")
                        pageCachePut(url, html)
                        return@withContext html
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "httpGet WebView silent $url failed: ${e.message}")
            }

            // 4) Dernier recours — WebView visible (cookie expiré ?)
            if (UserPreferences.currentProvider?.name == "DessinAnime") {
                try {
                    val html = webViewMutex.withLock {
                        val ctx = StreamFlixApp.currentActivity ?: StreamFlixApp.instance
                        val resolver = WebViewResolver(ctx)
                        try { resolver.get(url, silent = false) } finally {
                            try { resolver.cleanup() } catch (_: Throwable) {}
                        }
                    }
                    if (html.length > 500) {
                        Log.d(TAG, "httpGet WebView visible OK $url (${html.length} chars)")
                        cfBypassDone = true
                        pageCachePut(url, html)
                        return@withContext html
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "httpGet WebView visible $url failed: ${e.message}")
                }
            }
        }

        Log.w(TAG, "httpGet ÉCHEC total $url")
        null
        } // end networkSemaphore.withPermit
    }

    // ── Parsing helpers ────────────────────────────────────────────────

    /** Convertit un JSONObject de l'API search en Movie ou TvShow. */
    private fun jsonToItem(j: JSONObject): AppAdapter.Item? {
        val slug = j.optString("slug").ifBlank { return null }
        val title = j.optString("title").ifBlank { return null }
        val mediaType = j.optString("mediaType")
        val poster = cleanPosterUrl(j.optString("posterPath").takeIf { it.isNotBlank() })
        val year = j.optInt("releaseYear", 0).takeIf { it > 0 }?.toString()
        val rating = j.optDouble("voteAverage", 0.0).takeIf { it > 0 }
        return when (mediaType) {
            "MOVIE" -> Movie(
                id = "movie::$slug",
                title = title,
                released = year,
                poster = poster,
                rating = rating,
            )
            "TV" -> TvShow(
                id = "tv::$slug",
                title = title,
                released = year,
                poster = poster,
                rating = rating,
            )
            else -> null
        }
    }

    /** Métadonnées propres d'un item, extraites du RSC ou des cartes HTML. */
    private data class RichInfo(
        val title: String?,
        val poster: String?,
        val year: String?,
        val rating: Double?,
    )

    // 2026-06-22 : regex PRÉCOMPILÉES (companion-level) au lieu de Regex()
    //   recréées à chaque appel buildRichMap/parseHomeItems. Économise ~2 ms
    //   + évite les allocations Pattern sur chaque page.
    private val RSC_SLUG_REGEX = Regex("""\{[^{}]*?"slug"\s*:\s*"([^"]+)"[^{}]*?"mediaType"\s*:\s*"(?:MOVIE|TV)"[^{}]*?\}""")
    private val HTML_CARD_REGEX = Regex("""<img\b[^>]*\balt="([^"]*)"[^>]*\bsrc="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"[\s\S]{0,600}?href="/(?:movie|tv)/([^"/]+)"""")
    private val ITEM_HREF_REGEX = Regex("""href="/(movie|tv)/([^"/]+)"""")

    /**
     * Construit une table slug -> métadonnées propres en lisant les DEUX sources
     * fiables présentes dans les pages dessinanime.cc.
     *
     * 2026-06-22 (mémoire) : regex précompilées + limite de taille de la
     *   string unescapée (on ne copie que si nécessaire, et on cap à 500 KB
     *   pour éviter de doubler la RAM sur des pages exceptionnellement grosses).
     */
    private fun buildRichMap(html: String): Map<String, RichInfo> {
        val map = HashMap<String, RichInfo>()
        // 1) Objets RSC (flat, sans accolades imbriquées) une fois déséchappés.
        //    Cap : si le HTML > 500 KB on skip le unescape complet (rare, pages normales = ~235 KB)
        val un = if (html.length < 500_000) html.replace("\\\"", "\"") else html
        RSC_SLUG_REGEX.findAll(un).forEach { m ->
                try {
                    val o = JSONObject(m.value)
                    val slug = o.optString("slug").ifBlank { return@forEach }
                    map[slug] = RichInfo(
                        title = o.optString("title").takeIf { it.isNotBlank() },
                        poster = cleanPosterUrl(o.optString("posterPath").takeIf { it.isNotBlank() }),
                        year = o.optInt("releaseYear", 0).takeIf { it > 0 }?.toString(),
                        rating = o.optDouble("voteAverage", 0.0).takeIf { it > 0 },
                    )
                } catch (_: Exception) {}
            }
        // 2) Cartes HTML <img alt src> ... href=/type/slug (catalogue & home HTML).
        HTML_CARD_REGEX.findAll(html).forEach { m ->
                val slug = m.groupValues[3]
                if (map.containsKey(slug)) return@forEach   // RSC déjà plus riche
                val alt = m.groupValues[1].trim()
                map[slug] = RichInfo(
                    title = alt.takeIf { it.isNotBlank() },
                    poster = cleanPosterUrl(m.groupValues[2]),
                    year = null,
                    rating = null,
                )
            }
        return map
    }

    /** Parse une page (home ou catalogue) en items propres : titre réel +
     *  poster TMDB + année + note (via buildRichMap). L'ordre suit l'ordre DOM
     *  des liens ; fallback titre dérivé du slug si l'item n'est ni dans le RSC
     *  ni dans une carte HTML (rare). */
    private fun parseHomeItems(html: String): List<AppAdapter.Item> {
        val rich = buildRichMap(html)
        val out = mutableListOf<AppAdapter.Item>()
        val seen = mutableSetOf<String>()
        for (m in ITEM_HREF_REGEX.findAll(html)) {
            val type = m.groupValues[1]
            val slug = m.groupValues[2]
            if (!seen.add(slug)) continue
            val info = rich[slug]
            val title = info?.title
                ?: slug.replace(Regex("^\\d+-"), "").replace("-", " ").replaceFirstChar { it.uppercase() }
            val item: AppAdapter.Item = when (type) {
                "movie" -> Movie(id = "movie::$slug", title = title, poster = info?.poster, released = info?.year, rating = info?.rating)
                "tv" -> TvShow(id = "tv::$slug", title = title, poster = info?.poster, released = info?.year, rating = info?.rating)
                else -> continue
            }
            out.add(item)
        }
        return out
    }

    // ── Provider API ────────────────────────────────────────────────────

    /** Récupère une page du catalogue (/catalogue?page=N, 14 items mixés films+tv).
     *  2026-06-23 : cache ITEMS PARSÉS (pas juste le HTML) pour que
     *  getMovies(page=4) et getTvShows(page=4) ne refetchent pas
     *  les mêmes pages catalogue. Divise par 2 les appels WebView. */
    private suspend fun fetchCatalogPage(page: Int): List<AppAdapter.Item> {
        // Cache items parsés (survit entre getMovies ↔ getTvShows)
        catalogItemsCache[page]?.let { (ts, items) ->
            if (System.currentTimeMillis() - ts < CATALOG_ITEMS_TTL_MS) {
                Log.d(TAG, "fetchCatalogPage($page) → cache hit (${items.size} items)")
                return items
            }
        }
        val html = httpGet("$baseUrl/catalogue?page=$page") ?: return emptyList()
        val items = parseHomeItems(html)
        catalogItemsCache[page] = System.currentTimeMillis() to items
        Log.d(TAG, "fetchCatalogPage($page) → fetched (${items.size} items, mis en cache)")
        return items
    }

    override suspend fun getHome(): List<Category> = kotlinx.coroutines.coroutineScope {
        // 2026-06-23 (user "pas de bypass pas de contenu") : gate CF bypass
        //   AVANT tout contenu. Si pas de CF → no-op instantané.
        ensureCfBypass()
        // 2026-06-09 : ne fetch QUE la page d'accueil (1 fetch WebView ≈ 6s).
        val homeItems = parseHomeItems(httpGet("$baseUrl/") ?: "")
        val homeFilms = homeItems.filterIsInstance<Movie>()
        val homeTvs = homeItems.filterIsInstance<TvShow>()

        val categories = mutableListOf<Category>()

        // 2026-06-22 (mémoire) : ZÉRO DUPLICATION entre catégories.
        //   Avant : un même film apparaissait dans "Films", "Films récents",
        //   "Films les mieux notés", "À découvrir", "Top général", etc.
        //   = ×6 ViewHolders Glide pour le MÊME poster → pression bitmap.
        //   Maintenant : chaque item n'apparaît que dans UNE SEULE catégorie.
        //   On SPLIT en tranches disjointes pour avoir ≥15 catégories.
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

        // ── Films : split en 4 tranches disjointes ──
        val usedFilmIds = mutableSetOf<String>()
        fun pickFilms(source: List<Movie>, max: Int): List<Movie> {
            val picked = source.filter { usedFilmIds.add(it.id) }.take(max)
            return picked
        }
        // 2026-06-23 (user "y'a vraiment pas assez de contenu par catégorie") :
        //   8 → 15 items par rail. recentFilms supprimé (= doublon visuel avec
        //   "Films" car ils proviennent de la même source).
        val topFilms = pickFilms(homeFilms.sortedByDescending { it.rating ?: 0.0 }
            .filter { (it.rating ?: 0.0) >= 6.5 }, 15)
        val classicFilms = pickFilms(homeFilms.filter { m ->
            val y = m.released?.get(java.util.Calendar.YEAR) ?: 0; y in 1900..(currentYear - 3)
        }, 15)
        val remainFilms1 = pickFilms(homeFilms, 15)
        val remainFilms2 = pickFilms(homeFilms, 15)

        // ── Séries : split en 4 tranches disjointes ──
        val usedShowIds = mutableSetOf<String>()
        fun pickShows(source: List<TvShow>, max: Int): List<TvShow> {
            val picked = source.filter { usedShowIds.add(it.id) }.take(max)
            return picked
        }
        // 2026-06-23 : 8 → 15 par rail (idem films). recentShows supprimé.
        val topShows = pickShows(homeTvs.sortedByDescending { it.rating ?: 0.0 }
            .filter { (it.rating ?: 0.0) >= 6.5 }, 15)
        val classicShows = pickShows(homeTvs.filter { s ->
            val y = s.released?.get(java.util.Calendar.YEAR) ?: 0; y in 1900..(currentYear - 3)
        }, 15)
        val remainShows1 = pickShows(homeTvs, 15)
        val remainShows2 = pickShows(homeTvs, 15)

        // ── Assemblage des catégories (zéro duplication, ≥10 cat) ──
        // 2026-06-23 (user) : SUPPRIMÉ : "Films récents" (= doublon visuel de
        //   "Films"), "Séries récentes" (idem), "Sélection films",
        //   "Tout le catalogue" (= placeholder inutile).
        if (topFilms.isNotEmpty()) categories.add(Category(name = "Films populaires", list = topFilms))
        if (topShows.isNotEmpty()) categories.add(Category(name = "Séries populaires", list = topShows))
        if (classicFilms.isNotEmpty()) categories.add(Category(name = "Films classiques", list = classicFilms))
        if (classicShows.isNotEmpty()) categories.add(Category(name = "Séries classiques", list = classicShows))
        if (remainFilms1.isNotEmpty()) categories.add(Category(name = "Films", list = remainFilms1))
        if (remainShows1.isNotEmpty()) categories.add(Category(name = "Séries", list = remainShows1))
        if (remainFilms2.isNotEmpty()) categories.add(Category(name = "Encore plus de films", list = remainFilms2))
        if (remainShows2.isNotEmpty()) categories.add(Category(name = "Encore plus de séries", list = remainShows2))
        // Top mixte (items restants non utilisés)
        val usedAll = usedFilmIds + usedShowIds
        val mixRemain = homeItems.filter { item ->
            val id = when (item) { is Movie -> item.id; is TvShow -> item.id; else -> "" }
            id.isNotEmpty() && id !in usedAll
        }.take(15)
        if (mixRemain.isNotEmpty()) categories.add(Category(name = "À découvrir", list = mixRemain))
        // 2026-06-23 : SUPPRIMÉ "Sélection séries" (= re-tri d'items déjà dans
        //   "Séries populaires") et "Derniers ajouts" (= items sans vérif doublon).
        //   Réduit les doublons visibles sur le home.

        // 2026-06-22 (mémoire) : carrousel ALLÉGÉ — 4 items max (au lieu de 10).
        //   Chaque item du carrousel = 1 backdrop w500 en RAM (~60 KB décodé).
        //   4 items = ~0.6 MB. TMDB enrichissement cappé à 2s.
        val featuredSource = homeItems
            .filter { it is Movie || it is TvShow }
            .take(4)
        if (featuredSource.isNotEmpty() && UserPreferences.enableTmdb) {
            val featured = featuredSource.map { item ->
                when (item) {
                    is Movie -> Movie(id = item.id, title = item.title, banner = item.poster, poster = item.poster)
                    is TvShow -> TvShow(id = item.id, title = item.title, banner = item.poster, poster = item.poster)
                    else -> item
                }
            }
            kotlinx.coroutines.withTimeoutOrNull(2_000L) {
            featured.map { item ->
                async(Dispatchers.IO) {
                    try {
                        val title = when (item) {
                            is Movie -> item.title
                            is TvShow -> item.title
                            else -> return@async
                        }
                        val norm = TitleNormalizer.cleanForTmdbSearch(title)
                        val searchQuery = norm.ifBlank { title }
                        if (searchQuery.length < 2) return@async
                        val results = TMDb3.Search.multi(searchQuery, language = "fr-FR")
                        // Matching strict : type + titre similaire
                        val match = results.results.firstOrNull { r ->
                            when {
                                item is TvShow && r is TMDb3.Tv && r.backdropPath != null -> {
                                    val t = r.name?.trim() ?: ""
                                    t.equals(searchQuery, true) || t.contains(searchQuery, true) || searchQuery.contains(t, true)
                                }
                                item is Movie && r is TMDb3.Movie && r.backdropPath != null -> {
                                    val t = r.title?.trim() ?: ""
                                    t.equals(searchQuery, true) || t.contains(searchQuery, true) || searchQuery.contains(t, true)
                                }
                                // Fallback : accepter n'importe quel type anime si titre match
                                r is TMDb3.Tv && r.backdropPath != null -> {
                                    val t = r.name?.trim() ?: ""
                                    t.equals(searchQuery, true) || t.contains(searchQuery, true) || searchQuery.contains(t, true)
                                }
                                r is TMDb3.Movie && r.backdropPath != null -> {
                                    val t = r.title?.trim() ?: ""
                                    t.equals(searchQuery, true) || t.contains(searchQuery, true) || searchQuery.contains(t, true)
                                }
                                else -> false
                            }
                        }
                        val backdrop = when (match) {
                            is TMDb3.Movie -> match.backdropPath?.original
                            is TMDb3.Tv -> match.backdropPath?.original
                            else -> null
                        }
                        if (backdrop != null) when (item) {
                            is Movie -> item.banner = backdrop
                            is TvShow -> item.banner = backdrop
                        }
                    } catch (_: Exception) {}
                }
            }.awaitAll()
            }
            // Ne garder que les items avec backdrop TMDb HD (min 5 sinon liste complète)
            val carouselItems = featured.filter { item ->
                val b = when (item) { is Movie -> item.banner; is TvShow -> item.banner; else -> null }
                b != null && b.contains("/t/p/")
            }
            val finalCarousel = if (carouselItems.size >= 5) carouselItems else featured
            if (finalCarousel.isNotEmpty()) {
                categories.add(0, Category(name = Category.FEATURED, list = finalCarousel))
            }
        }

        Log.d(TAG, "getHome → ${categories.size} categories, ${homeItems.size} items (0 duplication)")
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = withContext(Dispatchers.IO) {
        ensureCfBypass() // 2026-06-23 : pas de contenu tant que le bypass n'est pas fait
        // Empty query : expose genre picker (peu de genres exposés par le site,
        //   on retourne juste les sections home en attendant de mapper /api/categories).
        if (query.isBlank()) {
            if (page > 1) return@withContext emptyList()
            return@withContext listOf(
                Genre(id = "all", name = "Tout le catalogue"),
                Genre(id = "movies", name = "Films"),
                Genre(id = "tv", name = "Séries"),
            )
        }
        // API search — pas de pagination native, on retourne tout.
        if (page > 1) return@withContext emptyList()
        val url = "$baseUrl/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val body = httpGet(url) ?: return@withContext emptyList()
        try {
            val arr = JSONArray(body)
            val results = mutableListOf<AppAdapter.Item>()
            for (i in 0 until arr.length()) {
                val item = jsonToItem(arr.optJSONObject(i) ?: continue) ?: continue
                results.add(item)
            }
            Log.d(TAG, "search('$query') → ${results.size} results")
            results
        } catch (e: Exception) {
            Log.w(TAG, "search parse failed: ${e.message}")
            emptyList()
        }
    }

    /** 2026-05-19 v85d (user "il y a meme pas 0,1 % des films dans l'onglet
     *  film et dans serie") : 2 pages catalogue par page user ramenait souvent
     *  0 films (catalog mixe) -> MoviesViewModel set hasMore=false ->
     *  pagination stoppee a la 1ere page. Fix : fetch 8 pages catalogue en
     *  parallele par page user (~112 items, ~40 films garantis) -> la
     *  pagination peut continuer jusqu'a atteindre la fin du catalogue
     *  (~300 pages catalog = ~38 pages user). */
    // 2026-06-09 : réduit de 8 à 3.
    // 2026-06-23 : réduit de 3 à 2 — avec le catalogItemsCache qui évite
    //   le double-fetch getMovies↔getTvShows, 2 pages suffisent. Le premier
    //   onglet (Films ou Séries) fetch 2 pages WebView (~4s), le 2e onglet
    //   les récupère du cache items (~0s). Total = ~4s au lieu de ~12s.
    private val PAGES_PER_USER_PAGE = 2

    override suspend fun getMovies(page: Int): List<Movie> = kotlinx.coroutines.coroutineScope {
        ensureCfBypass() // 2026-06-23 : pas de contenu tant que le bypass n'est pas fait
        val start = (page - 1) * PAGES_PER_USER_PAGE + 1
        val end = start + PAGES_PER_USER_PAGE - 1
        val pages = (start..end).map { p -> async { fetchCatalogPage(p) } }.map { it.await() }
        val movies = pages.flatten().filterIsInstance<Movie>().distinctBy { it.id }
        Log.d(TAG, "getMovies(page=$page) fetched catalog $start..$end -> ${movies.size} movies")
        movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = kotlinx.coroutines.coroutineScope {
        ensureCfBypass() // 2026-06-23 : pas de contenu tant que le bypass n'est pas fait
        val start = (page - 1) * PAGES_PER_USER_PAGE + 1
        val end = start + PAGES_PER_USER_PAGE - 1
        val pages = (start..end).map { p -> async { fetchCatalogPage(p) } }.map { it.await() }
        val shows = pages.flatten().filterIsInstance<TvShow>().distinctBy { it.id }
        Log.d(TAG, "getTvShows(page=$page) fetched catalog $start..$end -> ${shows.size} shows")
        shows
    }

    /** Extrait slug + type depuis id "movie::SLUG" ou "tv::SLUG". */
    private fun parseInternalId(id: String): Pair<String, String>? {
        val parts = id.split("::")
        if (parts.size < 2) return null
        if (parts[0] !in setOf("movie", "tv")) return null
        return parts[0] to parts[1]
    }

    override suspend fun getMovie(id: String): Movie = withContext(Dispatchers.IO) {
        val (type, slug) = parseInternalId(id) ?: return@withContext Movie(id = id, title = id)
        if (type != "movie") return@withContext Movie(id = id, title = slug)
        val html = httpGet("$baseUrl/movie/$slug")
        val title = html?.let { Regex("""<h1[^>]*>([^<]+)</h1>""").find(it)?.groupValues?.get(1)?.trim() }
            ?: slug.replace(Regex("^\\d+-"), "").replace("-", " ").replaceFirstChar { it.uppercase() }
        val overview = html?.let {
            Regex("""<p[^>]*>([^<]{80,})</p>""").find(it)?.groupValues?.get(1)?.trim()
        }
        val poster = cleanPosterUrl(html?.let {
            Regex("""<meta property="og:image" content="([^"]+)"""").find(it)?.groupValues?.get(1)
        })
        val year = html?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value }
        Movie(id = id, title = title, overview = overview, poster = poster, released = year)
    }

    override suspend fun getTvShow(id: String): TvShow = withContext(Dispatchers.IO) {
        val (type, slug) = parseInternalId(id) ?: return@withContext TvShow(id = id, title = id)
        if (type != "tv") return@withContext TvShow(id = id, title = slug)
        val html = httpGet("$baseUrl/tv/$slug")
        val title = html?.let { Regex("""<h1[^>]*>([^<]+)</h1>""").find(it)?.groupValues?.get(1)?.trim() }
            ?: slug.replace(Regex("^\\d+-"), "").replace("-", " ").replaceFirstChar { it.uppercase() }
        val overview = html?.let {
            Regex("""<p[^>]*>([^<]{80,})</p>""").find(it)?.groupValues?.get(1)?.trim()
        }
        val poster = cleanPosterUrl(html?.let {
            Regex("""<meta property="og:image" content="([^"]+)"""").find(it)?.groupValues?.get(1)
        })
        val year = html?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value }
        // 2026-05-20 : parsing LÉGER par regex (PAS de Jsoup.parse sur 355 Ko).
        //   L'enrichment "Continuer à regarder" du home appelle getTvShow +
        //   getEpisodesBySeason en parallèle pour TOUS les shows ; un Jsoup
        //   full-doc x N saturait la Chromecast (384 Mo) → ANR. Le regex
        //   extrait quand même l'image propre de chaque saison (fallback poster).
        val seasons = if (html != null) {
            val nums = Regex("""href="/tv/${Regex.escape(slug)}/(\d+)/1"""")
                .findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it > 0 }.toSet().sorted()
            val imgByNum = HashMap<Int, String>()
            Regex("""href="/tv/${Regex.escape(slug)}/(\d+)/1"[\s\S]{0,350}?<img\b[^>]*?\bsrc="([^"]+)"""")
                .findAll(html).forEach { m ->
                    val n = m.groupValues[1].toIntOrNull() ?: return@forEach
                    if (!imgByNum.containsKey(n)) imgByNum[n] = m.groupValues[2]
                }
            nums.map { n ->
                val raw = imgByNum[n] ?: ""
                val img = when {
                    raw.isBlank() -> poster
                    raw.startsWith("http") -> raw
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("/") -> "$baseUrl$raw"
                    else -> raw
                }
                Season(id = "tv::$slug::s$n", number = n, title = "Saison $n", poster = cleanPosterUrl(img))
            }
        } else emptyList<Season>()
        if (seasons.isNotEmpty()) Log.d(TAG, "getTvShow: ${seasons.size} saisons — ex poster=${seasons.first().poster?.take(110)}")
        TvShow(id = id, title = title, overview = overview, poster = poster, released = year, seasons = seasons)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = withContext(Dispatchers.IO) {
        // Format : tv::<slug>::sN
        val parts = seasonId.split("::")
        if (parts.size != 3 || parts[0] != "tv") return@withContext emptyList()
        val slug = parts[1]
        val seasonNum = parts[2].removePrefix("s").toIntOrNull() ?: return@withContext emptyList()
        val html = httpGet("$baseUrl/tv/$slug/$seasonNum/1") ?: return@withContext emptyList()
        // 2026-05-20 : parsing LÉGER regex (PAS de Jsoup full-doc → évite l'ANR
        //   quand l'enrichment home appelle getEpisodesBySeason en parallèle).
        //   Chaque carte = <a href="/tv/slug/season/ep">…<img alt="titre" src="img">.
        val epData = LinkedHashMap<Int, Pair<String?, String?>>()  // num -> (alt, src)
        Regex("""href="/tv/${Regex.escape(slug)}/$seasonNum/(\d+)"[\s\S]{0,500}?<img\b([^>]*)>""")
            .findAll(html).forEach { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@forEach
                if (n <= 0 || epData.containsKey(n)) return@forEach
                val attrs = m.groupValues[2]
                val src = Regex("""\bsrc="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                val alt = Regex("""\balt="([^"]*)"""").find(attrs)?.groupValues?.get(1)
                epData[n] = alt to src
            }
        // Garantir TOUS les épisodes même sans image trouvée
        Regex("""href="/tv/${Regex.escape(slug)}/$seasonNum/(\d+)"""").findAll(html).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@forEach
            if (n > 0 && !epData.containsKey(n)) epData[n] = null to null
        }
        val episodes = epData.entries.sortedBy { it.key }.map { (n, data) ->
            val (alt, rawSrc) = data
            val imgUrl = when {
                rawSrc.isNullOrBlank() -> null
                rawSrc.startsWith("http") -> rawSrc
                rawSrc.startsWith("//") -> "https:$rawSrc"
                rawSrc.startsWith("/") -> "$baseUrl$rawSrc"
                else -> rawSrc
            }
            val altTitle = alt?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("episode", true) && !it.startsWith("Épisode", true) }
            Episode(
                id = "tv::$slug::s$seasonNum::e$n",
                number = n,
                title = altTitle ?: "Épisode $n",
                poster = cleanPosterUrl(imgUrl),
            )
        }
        if (episodes.isNotEmpty()) {
            Log.d(TAG, "getEpisodesBySeason: ${episodes.size} eps — ex: '${episodes.first().title}' poster=${episodes.first().poster?.take(110)}")
        }
        episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext Genre(id = id, name = id, shows = emptyList())
        val html = httpGet("$baseUrl/") ?: return@withContext Genre(id = id, name = id, shows = emptyList())
        val all = parseHomeItems(html)
        val filtered: List<com.streamflixreborn.streamflix.models.Show> = when (id) {
            "movies" -> all.filterIsInstance<Movie>()
            "tv" -> all.filterIsInstance<TvShow>()
            else -> all.mapNotNull { it as? com.streamflixreborn.streamflix.models.Show }
        }
        Genre(id = id, name = when (id) {
            "movies" -> "Films"
            "tv" -> "Séries"
            else -> "Tout"
        }, shows = filtered)
    }

    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = id)

    // 2026-05-20 (user "rattacher un provider qui peut contenir plein de dessins
    //   animés au cas où") : DessinAnime = serveurs natifs PUIS deux backups
    //   lancés en parallèle, plafonnés en temps, ajoutés en fin de liste :
    //     • Cloudstream  → couvre les films/séries occidentaux (Super Mario,
    //       Zootopie…) via recherche titre→TMDB ; embarque déjà MovieBox+ + Movix
    //       + Nakios. Préfixe d'id "csbackup__" → délégué à CloudstreamProvider.
    //     • AnimeSama    → couvre l'anime japonais (séries + films). Helper natif
    //       sans ses propres backups. Préfixe "asbackup__" → délégué à AnimeSama.
    //   Garde anti-récursion : Cloudstream/AnimeSama appelés ici ne rappellent
    //   jamais DessinAnime, et AnimeSama tourne avec skipBackupsForBackupCall=true.
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = withContext(Dispatchers.IO) {
        val native = try {
            fetchNativeServers(id, videoType)
        } catch (e: Exception) {
            Log.w(TAG, "native servers KO: ${e.message}"); emptyList()
        }

        // Backups TOUJOURS lancés (même si natifs présents) — l'ancien early-return
        // coupait les backups dès qu'un seul serveur natif existait.

        val title = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }.trim()
        if (title.isBlank()) return@withContext native

        // Extraire le tmdbId du slug DessinAnime (ex: "movie::269149-zootopie" → "269149")
        // pour que les backups Cloudstream/Nakios/Movix cherchent par tmdbId direct.
        val tmdbId = id.split("::").getOrNull(1)
            ?.substringBefore("-")
            ?.takeIf { it.all(Char::isDigit) && it.length >= 2 }
            ?: "0"
        Log.d(TAG, "backups: title='$title', tmdbId=$tmdbId (from id=$id)")

        val backups = try {
            coroutineScope {
                // Backup #1 : Cloudstream (films/séries FR via TMDB, inclut Movix+Nakios)
                val csD = async {
                    try {
                        val csType: Video.Type = when (videoType) {
                            is Video.Type.Movie -> Video.Type.Movie(
                                id = tmdbId, title = title, releaseDate = videoType.releaseDate,
                                poster = videoType.poster, imdbId = videoType.imdbId,
                            )
                            is Video.Type.Episode -> Video.Type.Episode(
                                id = tmdbId, number = videoType.number, title = videoType.title,
                                poster = videoType.poster, overview = videoType.overview,
                                tvShow = videoType.tvShow.copy(id = tmdbId, title = title),
                                season = videoType.season,
                            )
                        }
                        (kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                            CloudstreamProvider.getServers(tmdbId, csType)
                        } ?: emptyList()).map {
                            it.copy(id = "csbackup__${it.id}", name = "CS · ${it.name}")
                        }
                    } catch (e: Exception) { Log.w(TAG, "CS backup KO: ${e.message}"); emptyList() }
                }
                // Backup #2 : AnimeSama (anime natif uniquement)
                val asD = async {
                    try {
                        (kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                            AnimeSamaProvider.getAnimeSamaSourcesByTitle(title, videoType)
                        } ?: emptyList()).map {
                            it.copy(id = "asbackup__${it.id}", name = "AS · ${it.name}")
                        }
                    } catch (e: Exception) { Log.w(TAG, "AS backup KO: ${e.message}"); emptyList() }
                }
                // Backup #3 : Wiflix (films/séries FR, VF + VOSTFR)
                val wfD = async {
                    if (skipWiflixForExternalCall) return@async emptyList<Video.Server>()
                    try {
                        fetchWiflixBackup(title, videoType)
                    } catch (e: Exception) { Log.w(TAG, "WF backup KO: ${e.message}"); emptyList() }
                }
                // Backup #4 : NetMirror (Netflix/Prime/Disney+ mirrors)
                val nmD = async {
                    try {
                        (kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                            NetMirrorProvider.getServers(tmdbId, videoType)
                        } ?: emptyList()).map {
                            it.copy(id = "nmbackup__${it.id}", name = "NM · ${it.name}")
                        }
                    } catch (e: Exception) { Log.w(TAG, "NM backup KO: ${e.message}"); emptyList() }
                }
                // 2026-06-09 v2 : retiré FS/MX/MB (doublons — déjà dans CS).
                csD.await() + asD.await() + wfD.await() + nmD.await()
            }
        } catch (e: Exception) { Log.w(TAG, "backups KO: ${e.message}"); emptyList() }

        Log.d(TAG, "getServers: ${native.size} natifs + ${backups.size} backups (CS+AS)")
        native + backups
    }

    // ── Chargement PROGRESSIF (2026-05-21) ─────────────────────────────
    //   Natifs émis tout de suite, puis Cloudstream et AnimeSama émis
    //   dès qu'ils répondent, en parallèle — pas d'attente bloquante.
    override fun getServersProgressive(id: String, videoType: Video.Type): Flow<List<Video.Server>> = channelFlow {
        val title = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }.trim()

        // 2026-06-09 (user "regarde ce que t'as fait sur les autres providers") :
        //   déduplication par URL src — pattern repris de MovixProvider seenUrls.
        //   Chaque backup envoie seulement les sources dont l'URL n'a pas déjà
        //   été émise. Évite Movix·X et CS·Movix·X qui pointent vers la même
        //   vidéo (CS inclut Movix en interne).
        val seenUrls = java.util.Collections.synchronizedSet(HashSet<String>())
        fun List<Video.Server>.dedup(): List<Video.Server> =
            this.filter { srv -> srv.src.isNotBlank() && seenUrls.add(srv.src) }

        // 1) Natifs — lancés en parallèle, émis quand prêts
        val nativeJob = launch(Dispatchers.IO) {
            try {
                val native = fetchNativeServers(id, videoType).dedup()
                if (native.isNotEmpty()) send(native)
            } catch (e: Exception) { Log.w(TAG, "progressive native KO: ${e.message}") }
        }
        // Si pas de titre on s'arrête après le natif.
        if (title.isBlank()) { nativeJob.join(); return@channelFlow }

        // Extraire le tmdbId du slug DessinAnime pour les backups
        val tmdbId = id.split("::").getOrNull(1)
            ?.substringBefore("-")
            ?.takeIf { it.all(Char::isDigit) && it.length >= 2 }
            ?: "0"

        // VideoType pour Cloudstream (tmdbId extrait du slug)
        val csType: Video.Type = when (videoType) {
            is Video.Type.Movie -> Video.Type.Movie(
                id = tmdbId, title = title, releaseDate = videoType.releaseDate,
                poster = videoType.poster, imdbId = videoType.imdbId,
            )
            is Video.Type.Episode -> Video.Type.Episode(
                id = tmdbId, number = videoType.number, title = videoType.title,
                poster = videoType.poster, overview = videoType.overview,
                tvShow = videoType.tvShow.copy(id = tmdbId, title = title),
                season = videoType.season,
            )
        }

        // Backups en parallèle — chacun émet dès qu'il finit
        val csJob = launch(Dispatchers.IO) {
            try {
                val servers = (kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    CloudstreamProvider.getServers(tmdbId, csType)
                } ?: emptyList()).map { it.copy(id = "csbackup__${it.id}", name = "CS · ${it.name}") }.dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive CS backup KO: ${e.message}") }
        }
        val asJob = launch(Dispatchers.IO) {
            try {
                val servers = (kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    AnimeSamaProvider.getAnimeSamaSourcesByTitle(title, videoType)
                } ?: emptyList()).map { it.copy(id = "asbackup__${it.id}", name = "AS · ${it.name}") }.dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive AS backup KO: ${e.message}") }
        }
        // Backup #3 : Wiflix
        val wfJob = launch(Dispatchers.IO) {
            if (skipWiflixForExternalCall) return@launch
            try {
                val servers = fetchWiflixBackup(title, videoType).dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive WF backup KO: ${e.message}") }
        }
        // Backup #4 : NetMirror
        val nmJob = launch(Dispatchers.IO) {
            try {
                val servers = (kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                    NetMirrorProvider.getServers(tmdbId, videoType)
                } ?: emptyList()).map { it.copy(id = "nmbackup__${it.id}", name = "NM · ${it.name}") }.dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive NM backup KO: ${e.message}") }
        }
        // 2026-06-09 v3 : re-ajout FS/MX/MB avec déduplication par URL.
        val fsJob = launch(Dispatchers.IO) {
            try {
                val servers = fetchFrenchStreamBackup(title, videoType).dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive FS backup KO: ${e.message}") }
        }
        val mxJob = launch(Dispatchers.IO) {
            try {
                val servers = (kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                    MovixProvider.getServers(tmdbId, videoType)
                } ?: emptyList()).map { it.copy(id = "mxbackup__${it.id}", name = "MX · ${it.name}") }.dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive MX backup KO: ${e.message}") }
        }
        val mbJob = launch(Dispatchers.IO) {
            try {
                val type = if (videoType is Video.Type.Movie) 1 else 2
                val servers = (kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                    MovieboxProvider.getMovieboxSourcesByTitle(
                        title, null, type,
                        seasonNumber = if (videoType is Video.Type.Episode) videoType.season.number else null,
                        episodeNumber = if (videoType is Video.Type.Episode) videoType.number else null,
                    )
                } ?: emptyList()).map { it.copy(id = "mbbackup__${it.id}", name = "MB · ${it.name}") }.dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive MB backup KO: ${e.message}") }
        }
        nativeJob.join()
        csJob.join()
        asJob.join()
        wfJob.join()
        nmJob.join()
        fsJob.join()
        mxJob.join()
        mbJob.join()
    }

    /**
     * Backup Wiflix : recherche par titre → getServers du 1er match.
     * Timeout 10s. Préfixe "wfbackup__".
     */
    private suspend fun fetchWiflixBackup(
        title: String,
        videoType: Video.Type,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        val results = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            WiflixProvider.search(title, 1)
        } ?: return@withContext emptyList()

        // 2026-06-09 (user "Wiflix Backup match avec des films qui ne sont pas
        //   bons") : matching strict. Avant on prenait le 1er résultat brut →
        //   "Mario" matchait avec n'importe quel film contenant "Mario" dans
        //   le titre. Maintenant on score sur similarité titre + année.
        fun normalizeForCompare(s: String): String = s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        val wantTitle = normalizeForCompare(title)
        val wantYear: Int? = when (videoType) {
            is Video.Type.Movie -> videoType.releaseDate.take(4).toIntOrNull()
            is Video.Type.Episode -> videoType.tvShow.releaseDate?.take(4)?.toIntOrNull()
        }

        fun scoreCandidate(candidateTitle: String?, candidateYear: Int?): Int {
            if (candidateTitle.isNullOrBlank()) return -1000
            val cTitle = normalizeForCompare(candidateTitle)
            // Titre EXACT = +100, contient le souhaité comme mot = +60,
            //   l'un dans l'autre (substring) = +30, sinon 0.
            val titleScore = when {
                cTitle == wantTitle -> 100
                cTitle.split(" ").containsAll(wantTitle.split(" ")) -> 60
                cTitle.contains(wantTitle) || wantTitle.contains(cTitle) -> 30
                else -> {
                    // Vérifie au moins 50% des mots du wantTitle présents dans cTitle.
                    val wantWords = wantTitle.split(" ").filter { it.length >= 3 }
                    if (wantWords.isEmpty()) return -100
                    val matched = wantWords.count { cTitle.contains(it) }
                    if (matched.toDouble() / wantWords.size >= 0.5) 10 else -100
                }
            }
            // Année : ±1 = bonus, ±2 = neutre, ≥3 = grosse pénalité.
            val yearScore = when {
                wantYear == null || candidateYear == null -> 0
                kotlin.math.abs(candidateYear - wantYear) <= 1 -> 30
                kotlin.math.abs(candidateYear - wantYear) == 2 -> 0
                else -> -60 // écart ≥3 ans = faux positif probable
            }
            return titleScore + yearScore
        }

        val matchId: String = when (videoType) {
            is Video.Type.Movie -> {
                val candidates = results.filterIsInstance<Movie>()
                val best = candidates
                    .map { it to scoreCandidate(it.title, it.released?.get(java.util.Calendar.YEAR)) }
                    .filter { it.second >= 50 } // seuil minimum pour valider le match
                    .maxByOrNull { it.second }
                Log.d(TAG, "WF backup match Movie '$title' (year=$wantYear) → " +
                    "${best?.first?.title} score=${best?.second}")
                best?.first?.id
            }
            is Video.Type.Episode -> {
                val candidates = results.filterIsInstance<TvShow>()
                val best = candidates
                    .map { it to scoreCandidate(it.title, it.released?.get(java.util.Calendar.YEAR)) }
                    .filter { it.second >= 50 }
                    .maxByOrNull { it.second }
                Log.d(TAG, "WF backup match TvShow '$title' (year=$wantYear) → " +
                    "${best?.first?.title} score=${best?.second}")
                best?.first?.id
            }
        } ?: run {
            Log.d(TAG, "WF backup: aucun match valable pour '$title'")
            return@withContext emptyList()
        }

        val wfServers = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            WiflixProvider.getServers(matchId, videoType)
        } ?: emptyList()

        wfServers.map {
            it.copy(id = "wfbackup__${it.id}", name = "WF · ${it.name}")
        }
    }

    /**
     * 2026-06-09 : backup FrenchStream — recherche par titre, sélection du
     *   meilleur match (titre+année), getServers. Identique au pattern Wiflix.
     */
    private suspend fun fetchFrenchStreamBackup(
        title: String,
        videoType: Video.Type,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        val results = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            FrenchStreamProvider.search(title, 1)
        } ?: return@withContext emptyList()

        fun normalizeForCompare(s: String): String = s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val wantTitle = normalizeForCompare(title)
        val wantYear: Int? = when (videoType) {
            is Video.Type.Movie -> videoType.releaseDate.take(4).toIntOrNull()
            is Video.Type.Episode -> videoType.tvShow.releaseDate?.take(4)?.toIntOrNull()
        }

        fun scoreCandidate(candidateTitle: String?, candidateYear: Int?): Int {
            if (candidateTitle.isNullOrBlank()) return -1000
            val cTitle = normalizeForCompare(candidateTitle)
            val titleScore = when {
                cTitle == wantTitle -> 100
                cTitle.split(" ").containsAll(wantTitle.split(" ")) -> 60
                cTitle.contains(wantTitle) || wantTitle.contains(cTitle) -> 30
                else -> {
                    val wantWords = wantTitle.split(" ").filter { it.length >= 3 }
                    if (wantWords.isEmpty()) return -100
                    val matched = wantWords.count { cTitle.contains(it) }
                    if (matched.toDouble() / wantWords.size >= 0.5) 10 else -100
                }
            }
            val yearScore = when {
                wantYear == null || candidateYear == null -> 0
                kotlin.math.abs(candidateYear - wantYear) <= 1 -> 30
                kotlin.math.abs(candidateYear - wantYear) == 2 -> 0
                else -> -60
            }
            return titleScore + yearScore
        }

        val matchId: String = when (videoType) {
            is Video.Type.Movie -> {
                val candidates = results.filterIsInstance<Movie>()
                candidates
                    .map { it to scoreCandidate(it.title, it.released?.get(java.util.Calendar.YEAR)) }
                    .filter { it.second >= 50 }
                    .maxByOrNull { it.second }?.first?.id
            }
            is Video.Type.Episode -> {
                val candidates = results.filterIsInstance<TvShow>()
                candidates
                    .map { it to scoreCandidate(it.title, it.released?.get(java.util.Calendar.YEAR)) }
                    .filter { it.second >= 50 }
                    .maxByOrNull { it.second }?.first?.id
            }
        } ?: run {
            Log.d(TAG, "FS backup: aucun match pour '$title'")
            return@withContext emptyList()
        }

        val fsServers = kotlinx.coroutines.withTimeoutOrNull(12_000L) {
            FrenchStreamProvider.getServers(matchId, videoType)
        } ?: emptyList()

        Log.d(TAG, "FS backup: ${fsServers.size} sources pour '$title'")
        fsServers.map {
            it.copy(id = "fsbackup__${it.id}", name = "FS · ${it.name}")
        }
    }

    /**
     * Serveurs natifs DessinAnime (RSC + miroirs empilés). Renvoie emptyList
     *   en cas d'échec (pas d'exception) pour que les backups prennent le relais.
     */
    private suspend fun fetchNativeServers(id: String, videoType: Video.Type): List<Video.Server> = withContext(Dispatchers.IO) {
        // Pour Movie : id = "movie::<slug>" → fetch /movie/<slug>
        // Pour Episode : id reçu = celui de l'épisode "tv::<slug>::sN::eM"
        val pageUrl: String = when (videoType) {
            is Video.Type.Movie -> {
                val (type, slug) = parseInternalId(id) ?: return@withContext emptyList()
                if (type != "movie") return@withContext emptyList()
                "$baseUrl/movie/$slug"
            }
            is Video.Type.Episode -> {
                // L'id peut être de l'épisode ou du show ; on cherche le slug
                // depuis le videoType qui contient la séquence saison/épisode.
                val slug = parseInternalId(id)?.second
                    ?: id.split("::").getOrNull(1)
                    ?: return@withContext emptyList()
                val s = videoType.season.number
                val e = videoType.number
                "$baseUrl/tv/$slug/$s/$e"
            }
        }
        Log.d(TAG, "getServers fetch $pageUrl")
        val html = httpGet(pageUrl) ?: return@withContext emptyList()

        // v85d 2026-05-19 (user "il trouve qu'un serveur par source alors qu'il
        //   devrait y en avoir plusieurs") : la page est Next.js + RSC. Le HTML
        //   rendu a un seul <iframe> par défaut, mais le payload RSC sérialisé
        //   plus bas contient TOUS les players (uqload, mixdrop, vidhide,
        //   player4me, hydrax, etc.) avec embedId + iframeTemplate. Parse ça
        //   → 6 serveurs au lieu d'1. Fallback iframe<src> si parse échoue.
        val unescaped = html.replace("\\\"", "\"")
        // 2026-05-19 v85d2 (user "il manque bien un serveur") : language est
        //   parfois null (pas un objet) -> on ne l'exige plus dans le regex
        //   principal. On la cherche ensuite dans la fenetre du match.
        val playerRegex = Regex(
            "\"embedId\":\"([^\"]+)\".{0,1500}?" +
            "\"host\":\\{[^}]*?\"name\":\"([^\"]+)\"[^}]*?\"iframeTemplate\":\"([^\"]+)\"[^}]*\\}"
        )
        val langInWindowRegex = Regex(
            "\"effectiveLanguage\":\"([^\"]+)\"|\"sourceLanguage\":\"([^\"]+)\"|\"language\":\\{[^}]*?\"name\":\"([^\"]+)\""
        )
        val seenUrls = mutableSetOf<String>()
        val nameToUrls = LinkedHashMap<String, MutableList<String>>()
        playerRegex.findAll(unescaped).forEach { m ->
            val embedId = m.groupValues[1]
            val hostName = m.groupValues[2]
            val iframeTemplate = m.groupValues[3]
            val url = iframeTemplate.replace("{{slug}}", embedId)
            if (!url.startsWith("http")) return@forEach
            // 2026-05-23 : Player4me désactivé (API "Video not found" sur tout)
            if (url.contains("4meplayer")) return@forEach
            if (!seenUrls.add(url)) return@forEach
            val ws = maxOf(0, m.range.first - 250)
            val we = minOf(unescaped.length, m.range.last + 50)
            val window = unescaped.substring(ws, we)
            val langMatch = langInWindowRegex.find(window)
            val rawLang = listOfNotNull(
                langMatch?.groupValues?.getOrNull(1),
                langMatch?.groupValues?.getOrNull(2),
                langMatch?.groupValues?.getOrNull(3),
            ).firstOrNull { it.isNotBlank() && it != "null" } ?: ""
            val langUp = rawLang.uppercase()
            if (!(langUp.isBlank() || langUp == "MULTI" || langUp.contains("FR") || langUp.contains("VF"))) return@forEach  // user: FR uniquement
            val pretty = hostName.replaceFirstChar { it.uppercase() }
            val name = if (rawLang.isNotBlank() && rawLang != "MULTI") "$pretty ($rawLang)" else pretty
            // On EMPILE tous les miroirs du meme nom (pas de dedup par nom) :
            //   1 entree visible, mais src contient tous les miroirs -> fallback.
            nameToUrls.getOrPut(name) { mutableListOf() }.add(url)
        }
        if (nameToUrls.isNotEmpty()) {
            val rscServers = nameToUrls.map { (name, urls) ->
                Video.Server(id = urls.first(), name = name, src = urls.first(), mirrors = urls)
            }
            Log.d(TAG, "getServers RSC: ${rscServers.size} players (${rscServers.joinToString { "${it.name} x${it.mirrors.size}" }})")
            return@withContext rscServers
        }

        // Fallback iframe<src> — pages anciennes sans payload RSC.
        val iframes = Regex("""<iframe[^>]+src="([^"]+)"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()
        if (iframes.isEmpty()) {
            Log.w(TAG, "No iframe found on $pageUrl (RSC parse aussi vide)")
            return@withContext emptyList()
        }
        // Empile les miroirs du meme host sous 1 seule entree (idem RSC).
        val ifNameToUrls = LinkedHashMap<String, MutableList<String>>()
        iframes.forEach { iframeUrl ->
            val host = try { java.net.URI(iframeUrl).host ?: "Lecteur" } catch (_: Exception) { "Lecteur" }
            val cleanHost = host.removePrefix("www.")
                .substringBefore('.')
                .replaceFirstChar { it.uppercase() }
            ifNameToUrls.getOrPut(cleanHost) { mutableListOf() }.add(iframeUrl)
        }
        Log.d(TAG, "getServers fallback iframe: ${ifNameToUrls.size} server(s) (${ifNameToUrls.entries.joinToString { "${it.key} x${it.value.size}" }})")
        ifNameToUrls.map { (name, urls) ->
            Video.Server(
                id = urls.first(),
                name = name,
                src = urls.first(),
                mirrors = urls,
            )
        }
    }
    override suspend fun getVideo(server: Video.Server): Video {
        // Backups : on délègue au provider d'origine (qui sait extraire/headers).
        if (server.id.startsWith("csbackup__")) {
            return CloudstreamProvider.getVideo(server.copy(id = server.id.removePrefix("csbackup__")))
        }
        if (server.id.startsWith("asbackup__")) {
            return AnimeSamaProvider.getVideo(server.copy(id = server.id.removePrefix("asbackup__")))
        }
        if (server.id.startsWith("wfbackup__")) {
            return WiflixProvider.getVideo(server.copy(id = server.id.removePrefix("wfbackup__")))
        }
        if (server.id.startsWith("nmbackup__")) {
            return NetMirrorProvider.getVideo(server.copy(id = server.id.removePrefix("nmbackup__")))
        }
        // 2026-06-22 : fix délégation FS/MX/MB (manquait → fallback générique KO)
        if (server.id.startsWith("fsbackup__")) {
            return FrenchStreamProvider.getVideo(server.copy(id = server.id.removePrefix("fsbackup__")))
        }
        if (server.id.startsWith("mxbackup__")) {
            return MovixProvider.getVideo(server.copy(id = server.id.removePrefix("mxbackup__")))
        }
        if (server.id.startsWith("mbbackup__")) {
            return MovieboxProvider.getVideo(server.copy(id = server.id.removePrefix("mbbackup__")))
        }
        // Un host peut avoir PLUSIEURS miroirs (ex 3 liens Uqload pour 1 film).
        //   On essaie chacun jusqu'au premier qui sort une vraie source -> un
        //   miroir mort (page 931 octets / timeout) est saute automatiquement.
        val mirrors = (if (server.mirrors.isNotEmpty()) server.mirrors else listOf(server.src))
            .map { it.trim() }.filter { it.isNotEmpty() }
        Log.d(TAG, "getVideo(${server.name}) mirrors=${mirrors.size}")
        if (mirrors.size <= 1) {
            return Extractor.extract(server.src, server)
        }
        var lastError: Throwable? = null
        for ((i, url) in mirrors.withIndex()) {
            try {
                Log.d(TAG, "getVideo(${server.name}) miroir ${i + 1}/${mirrors.size}: $url")
                return Extractor.extract(url, server)
            } catch (e: Throwable) {
                Log.w(TAG, "getVideo(${server.name}) miroir ${i + 1} KO: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: Exception("${server.name}: tous les miroirs ont echoue")
    }
}
