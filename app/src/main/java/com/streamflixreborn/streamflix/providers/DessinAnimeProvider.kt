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
    // 2026-07-04 (user "une fois le bypass fait, il doit PARTAGER les cookies à tout ; DessinAnime
    //   doit faire exactement comme Wiflix/FrenchAnime") : l'UA OkHttp DOIT être le MÊME que celui
    //   de la WebView de bypass (STEALTH_UA). Le cf_clearance est LIÉ à l'UA qui a résolu le
    //   Turnstile ; avec l'UA par défaut du device (≠ STEALTH_UA), OkHttp envoyait le cookie avec
    //   le mauvais UA → CF le rejetait → 403 → re-bypass WebView à CHAQUE page. En alignant l'UA,
    //   OkHttp passe avec le cookie partagé (cookieJar commun) → plus de re-bypass = synopsis rapide.
    private val UA: String
        get() = com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_UA

    // 2026-06-09 : CookieJar partagé avec WebView (NetworkClient.cookieJar
    //   proxie vers WebKit CookieManager). Quand l'user complète le Cloudflare
    //   Turnstile dans la WebView, le cookie cf_clearance est automatiquement
    //   disponible pour OkHttp.
    private val cookieJar = NetworkClient.cookieJar

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
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
    // 2026-07-04 (user "à l'ouverture du Home il devrait OBLIGATOIREMENT vérifier qu'un bypass est
    //   disponible ; on a 30 min de cache mémoire ce qui crée un bug quand y a plus de bypass") :
    //   cfBypassDone restait à true APRÈS l'expiration du cookie cf_clearance (~30 min) → ensureCfBypass
    //   faisait return immédiat, la WebView silent retombait sur un challenge, chaque page du home
    //   faisait son propre bypass lent → getHome global timeout → home partiel (2-3 catégories).
    //   FIX : on horodate le bypass + on vérifie la présence RÉELLE du cookie. Si périmé (>25 min) OU
    //   cookie absent → on force un re-bypass UNE fois avant de charger le home (cookie frais partagé
    //   à toutes les requêtes OkHttp via le cookieJar commun).
    @Volatile private var cfBypassTs = 0L
    private const val CF_CLEARANCE_TTL_MS = 25 * 60 * 1000L // 25 min (< 30 min de validité CF)

    private fun markBypassFresh() {
        cfBypassDone = true
        cfBypassTs = System.currentTimeMillis()
    }

    /**
     * true UNIQUEMENT si le cookie cf_clearance a DISPARU.
     * 2026-07-04 (user "les cookies ne doivent pas s'effacer comme ça, seulement à la demande
     *   du site s'il propose un nouveau challenge") : on NE périme PLUS le bypass sur un timer.
     *   Tant que le cookie est là, on le garde. Si le site redemande un challenge, httpGet le
     *   détecte (page challenge / 403) et relance le WebView à ce moment-là — pas avant.
     */
    private fun isBypassStale(): Boolean {
        return try {
            val c = android.webkit.CookieManager.getInstance().getCookie(baseUrl)
            c == null || !c.contains("cf_clearance")
        } catch (_: Throwable) { false }
    }

    // 2026-06-23 : cache items catalogue PARSÉS (survit entre getMovies et
    //   getTvShows pour éviter le double-fetch des mêmes pages catalogue).
    private val catalogItemsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<AppAdapter.Item>>>()
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
        // 2026-07-04 : si le bypass est marqué "fait" mais périmé (>25 min) ou le cookie a disparu,
        //   on le REDÉCLENCHE. Sans ça, le home rouvre avec un cookie mort → challenges en cascade.
        if (cfBypassDone && isBypassStale()) {
            Log.d(TAG, "ensureCfBypass → cf_clearance périmé/absent, re-bypass forcé")
            cfBypassDone = false
        }
        if (cfBypassDone) return
        // 2026-07-05 (user "le home met 30s à froid alors qu'on préchauffe au boot") : resetState()
        //   remet cfBypassDone=false à l'ouverture du provider → ensureCfBypass refaisait un bypass
        //   WebView (~8s) ALORS QUE le cookie cf_clearance (pré-chauffé au boot, ou d'une session
        //   précédente) est encore VALIDE. FIX : si le cookie est présent, on considère le bypass
        //   fait SANS relancer la WebView → ouverture du home bien plus rapide.
        if (!isBypassStale()) {
            markBypassFresh()
            Log.d(TAG, "ensureCfBypass → cookie cf_clearance déjà valide → skip WebView (rapide)")
            return
        }
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
                                markBypassFresh()
                                pageCachePut("$baseUrl/", body)
                                return@withLock
                            }
                        }
                    }
                } catch (_: Exception) {}

                // 2026-07-04 (user "le home est gelé une minute alors que le bypass est chaud ;
                //   c'est le bypass qui recharge 379 Ko sur le thread principal et bloque l'UI") :
                //   bypass en mode LÉGER (clearanceOnly) → on s'arrête au cf_clearance SANS charger
                //   la home lourde. ~4s de challenge au lieu de ~11s de home → jank bien plus court,
                //   plus de gel d'une minute. La home est fetchée par getHome quand nécessaire (et
                //   servie depuis le cache 30 min la plupart du temps).
                Log.d(TAG, "ensureCfBypass → WebView LÉGER (clearanceOnly) sur $baseUrl")
                try {
                    val ctx = StreamFlixApp.currentActivity ?: StreamFlixApp.instance
                    val resolver = WebViewResolver(ctx)
                    try {
                        val html = resolver.get(baseUrl, silent = false, clearanceOnly = true)
                        val gotClearance = html.contains("clearance warmed") ||
                            (android.webkit.CookieManager.getInstance().getCookie(baseUrl)?.contains("cf_clearance") == true)
                        if (gotClearance) {
                            markBypassFresh()
                            Log.d(TAG, "ensureCfBypass → clearance OK (léger)")
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

    /**
     * 2026-07-04 (user "il faut préchauffer le bypass à l'ouverture de l'app, comme Wiflix ;
     *   dans un navigateur on ouvre 3 challenges CF en même temps sans problème, c'est juste
     *   des pages") : pré-résout le cf_clearance au boot (WebView TRANSITOIRE, détruite après).
     *   On s'arrête DÈS que le cookie cf_clearance est posé — on ne charge PAS toute la home
     *   lourde → léger en CPU (page de challenge uniquement), pas d'ANR comme l'ancien préchauffage
     *   qui attendait le rendu complet. Idempotent : si déjà fait, no-op.
     */
    suspend fun warmUpCf() {
        if (cfBypassDone && !isBypassStale()) return
        withContext(Dispatchers.IO) {
            webViewMutex.withLock {
                if (cfBypassDone && !isBypassStale()) return@withLock
                try {
                    val ctx = StreamFlixApp.currentActivity ?: StreamFlixApp.instance
                    val resolver = WebViewResolver(ctx)
                    try {
                        // clearanceOnly=true → s'arrête au cf_clearance, ne charge pas la home lourde.
                        val html = resolver.get(baseUrl, silent = true, clearanceOnly = true)
                        val ok = html.contains("clearance warmed") ||
                            android.webkit.CookieManager.getInstance().getCookie(baseUrl)?.contains("cf_clearance") == true
                        if (ok) {
                            markBypassFresh()
                            Log.d(TAG, "warmUpCf → cf_clearance pré-chauffé au boot")
                        }
                    } finally {
                        try { resolver.cleanup() } catch (_: Throwable) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "warmUpCf failed: ${e.message}")
                }
            }
        }
    }

    // 2026-07-04 (user "les séries n'ont pas de saisons") : les pages /tv/* de dessinanime.cc
    //   ont un challenge Cloudflare Turnstile SÉPARÉ de /catalogue — le cf_clearance de base ne
    //   le couvre PAS. En silencieux, getTvShow (marker gate 12s) ne résout pas ce challenge À
    //   FROID → le fetch RSC reste en 403 → dossier série vide + fallback visible annulé par le
    //   ViewModel ("Job was cancelled"). FIX (comme le WebJS le faisait) : pré-résoudre le
    //   challenge /tv/* EN FOND (jusqu'à 30s, reload auto) pendant que l'user parcourt le home.
    //   Une fois le cf_clearance /tv/* posé (cookie domaine, persiste), getTvShow passe en <12s.
    @Volatile private var detailWarmed = false
    private val warmScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    @Volatile private var warmJob: kotlinx.coroutines.Deferred<Boolean>? = null

    /** Résout (UNE fois) le challenge Turnstile SÉPARÉ des pages détail tv. Tourne dans warmScope
     *  (indépendant du viewModelScope → SURVIT à l'annulation d'un getTvShow : le prochain clic
     *  est déjà prêt). Partagé entre appelants (dédup via warmJob). silent + reload auto ≤30s. */
    private fun startDetailWarm(tvSlug: String): kotlinx.coroutines.Deferred<Boolean> {
        warmJob?.let { if (it.isActive) return it }
        val job = warmScope.async {
            try {
                webViewMutex.withLock {
                    if (detailWarmed) return@withLock true
                    val ctx = StreamFlixApp.currentActivity ?: StreamFlixApp.instance
                    val resolver = WebViewResolver(ctx)
                    try {
                        val html = resolver.get("$baseUrl/tv/$tvSlug", silent = true)
                        val ok = html.length > 5000 &&
                            android.webkit.CookieManager.getInstance().getCookie(baseUrl)?.contains("cf_clearance") == true
                        if (ok) { detailWarmed = true; Log.d(TAG, "detailWarm → /tv/* clearance OK (via $tvSlug)") }
                        else Log.w(TAG, "detailWarm → /tv/* KO (len=${html.length})")
                        ok
                    } finally { try { resolver.cleanup() } catch (_: Throwable) {} }
                }
            } catch (e: Exception) { Log.w(TAG, "detailWarm KO: ${e.message}"); false }
        }
        warmJob = job
        return job
    }

    /** Fire-and-forget : boot + ouverture du provider (getHome). Ne bloque rien. */
    fun warmDetailChallengeAsync(tvSlug: String) {
        if (!detailWarmed && tvSlug.isNotBlank()) startDetailWarm(tvSlug)
    }

    /** BLOQUANT : appelé au clic sur une série → GARANTIT que le challenge détail tv est résolu
     *  avant le fetch RSC (sinon 1ère série = dossier vide). Si le warm tourne déjà, on l'attend. */
    private suspend fun ensureDetailWarmed(tvSlug: String) {
        if (detailWarmed || tvSlug.isBlank()) return
        try { startDetailWarm(tvSlug).await() } catch (_: Exception) {}
    }

    /** Pré-chauffage complet au BOOT (base + détail tv). Récupère un slug série du catalogue puis
     *  résout le challenge détail. Idempotent. */
    suspend fun warmUpAll() {
        try {
            warmUpCf()
            if (detailWarmed) return
            val items = fetchCatalogPage(1, "TV")
            val slug = items.filterIsInstance<TvShow>().firstOrNull()?.id?.removePrefix("tv::")
            if (!slug.isNullOrBlank()) startDetailWarm(slug)
        } catch (e: Exception) { Log.w(TAG, "warmUpAll failed: ${e.message}") }
    }

    // 2026-07-06 (user « tant qu'on est sur le home DessinAnime, simuler un clic jaquette de
    //   temps en temps pour garder le captcha résolu, un truc pas gourmand ») : keep-alive.
    //   Appelé PÉRIODIQUEMENT par StreamFlixApp UNIQUEMENT quand DessinAnime est le provider
    //   actif. Ne fait RIEN si le clearance est encore frais (< seuil) ET la fiche déjà chaude
    //   (donc pas gourmand : simple check de cookie/horodatage la plupart du temps). Sinon il
    //   re-simule l'ouverture d'une fiche (startDetailWarm) pour rafraîchir le cf_clearance AVANT
    //   qu'il n'expire → au clic suivant, jamais de captcha à re-résoudre.
    private val KEEPALIVE_REFRESH_MS = 20 * 60 * 1000L  // rafraîchit à 20 min (< ~30 min de validité CF)

    suspend fun keepAliveIfActive() {
        val ageMs = System.currentTimeMillis() - cfBypassTs
        val cookieGone = isBypassStale()
        // Frais + fiche déjà chaude + pas trop vieux → rien à faire (léger).
        if (!cookieGone && detailWarmed && ageMs < KEEPALIVE_REFRESH_MS) return
        try {
            if (cookieGone) cfBypassDone = false
            warmUpCf()
            // Force une NOUVELLE simulation de clic fiche pour garder /tv/* frais.
            detailWarmed = false
            warmJob = null
            val items = try { fetchCatalogPage(1, "TV") } catch (_: Exception) { emptyList() }
            val slug = items.filterIsInstance<TvShow>().firstOrNull()?.id?.removePrefix("tv::")
            if (!slug.isNullOrBlank()) startDetailWarm(slug).await()
            Log.d(TAG, "keepAlive → re-simulation clic fiche (cf_clearance rafraîchi, age=${ageMs/1000}s)")
        } catch (e: Exception) { Log.w(TAG, "keepAlive failed: ${e.message}") }
    }

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
    // 2026-07-04 (user "le temps d'ouverture de la synopsis DessinAnime ; il doit réutiliser le
    //   cache une fois la jaquette chargée") : 5 → 30. Avec 5, ouvrir une série (page série + pages
    //   épisodes = 2-3 slots) évinçait vite → réouverture = re-fetch + re-bypass CF Turnstile (~10s).
    //   Avec 30, les synopsis déjà vus restent en cache (TTL détail 2h) → réouverture instantanée.
    private const val PAGE_CACHE_MAX = 30
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
    private suspend fun httpGet(url: String, contentMarker: String? = null, rscFetchUrl: String? = null, markerTimeoutMs: Long = 12_000L): String? = withContext(Dispatchers.IO) {
        // 1) Cache RAM (= avant le semaphore, hit = 0 latence)
        //   2026-07-04 : si un marqueur est requis (page player) on n'accepte le cache
        //   QUE s'il contient déjà le marqueur — évite la COLLISION de cache où
        //   getEpisodesBySeason a mis en cache /tv/slug/1/1 SANS les sources du lecteur,
        //   puis fetchNativeServers(ep1) réutilisait cette page incomplète = 0 serveur.
        // 2026-07-04 : clé de cache DISTINCTE pour le fetch RSC — sinon le RSC (sources)
        //   et la page HTML (liste d'épisodes) se marcheraient dessus sous la même URL
        //   (getEpisodesBySeason récupérerait les sources au lieu des épisodes, et vice-versa).
        val cacheKey = if (rscFetchUrl != null) "$url##rsc" else url
        val ttl = if (url.contains("/movie/") || url.contains("/tv/")) CACHE_TTL_DETAIL_MS else CACHE_TTL_MS
        pageCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.ts < ttl &&
                (contentMarker == null || cached.html.contains(contentMarker))) return@withContext cached.html
        }
        // 2026-06-23 : Semaphore(3) pour limiter les fetches parallèles (= évite
        //   CF rate-limit qui faisait tomber les requêtes synopsis en 403).
        networkSemaphore.withPermit {

        // 2) OkHttp direct (sans Accept-Encoding → auto décompression gzip)
        //   2026-07-04 : en mode RSC (page player) on saute OkHttp (l'épisode renvoie 403
        //   à OkHttp de toute façon → le fetch RSC se fait DANS le WebView, seul contexte
        //   qui passe CF). Évite ~1s de 403 inutile.
        val isApi = url.contains("/api/")
        if (rscFetchUrl == null) try {
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
                    // 2026-07-04 : si un marqueur est requis (page player) et absent du body
                    //   OkHttp → on NE l'accepte PAS (SSR partiel sans les sources) et on
                    //   bascule sur le WebView qui attend le chunk streamé.
                    val markerOk = contentMarker == null || body.contains(contentMarker) ||
                        body.contains(contentMarker.replace("\"", "\\\""))
                    if (!isChallenge && markerOk && body.length > 200) {
                        Log.d(TAG, "httpGet OK $url (${body.length} chars)")
                        pageCachePut(cacheKey, body)
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
                        try { resolver.get(url, silent = false, contentMarker = contentMarker, rscFetchUrl = rscFetchUrl, markerTimeoutMs = markerTimeoutMs) } finally {
                            try { resolver.cleanup() } catch (_: Throwable) {}
                        }
                    }
                }
                if (html != null && html.length > 500) {
                    val isChallenge = challengeKeywords.any { html.contains(it, ignoreCase = true) }
                    if (!isChallenge || html.length > 50_000) {
                        Log.d(TAG, "httpGet WebView visible OK $url (${html.length} chars) — cfBypassDone=true")
                        markBypassFresh()
                        pageCachePut(cacheKey, html)
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
                                    pageCachePut(cacheKey, body)
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
                    try { resolver.get(url, silent = true, contentMarker = contentMarker, rscFetchUrl = rscFetchUrl, markerTimeoutMs = markerTimeoutMs) } finally {
                        try { resolver.cleanup() } catch (_: Throwable) {}
                    }
                }
                if (html.length > 500) {
                    val isChallenge = challengeKeywords.any { html.contains(it, ignoreCase = true) }
                    // 2026-07-04 (user "on avait un home instantané mais pas de jaquettes dans
                    //   le menu synopsis") : sur Chromecast, l'hydratation React de /tv/<slug>
                    //   peut dépasser les 12s de poll du WebViewResolver → l'HTML retourné est
                    //   un SHELL sans les liens de saisons (marker absent). Ancien code : on
                    //   l'acceptait comme succès → seasons = emptyList() → menu synopsis vide.
                    //   Fix : on ré-vérifie le marker à la sortie ; si absent, on tombe sur le
                    //   fallback WebView visible qui poll plus longtemps + laisse au réseau/CPU
                    //   Chromecast le temps de finir l'hydratation.
                    val markerOk = contentMarker == null ||
                        html.contains(contentMarker) ||
                        html.contains(contentMarker.replace("\"", "\\\""))
                    if ((!isChallenge && markerOk) || html.length > 50_000) {
                        Log.d(TAG, "httpGet WebView silent OK $url (${html.length} chars, markerOk=$markerOk)")
                        pageCachePut(cacheKey, html)
                        return@withContext html
                    }
                    if (!markerOk) {
                        Log.w(TAG, "httpGet WebView silent $url — marker '$contentMarker' ABSENT → fallback visible")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "httpGet WebView silent $url failed: ${e.message}")
            }

            // 4) Dernier recours — WebView visible (cookie expiré ? shell sans hydratation ?)
            if (UserPreferences.currentProvider?.name == "DessinAnime") {
                try {
                    val html = webViewMutex.withLock {
                        val ctx = StreamFlixApp.currentActivity ?: StreamFlixApp.instance
                        val resolver = WebViewResolver(ctx)
                        try { resolver.get(url, silent = false, contentMarker = contentMarker, rscFetchUrl = rscFetchUrl, markerTimeoutMs = markerTimeoutMs) } finally {
                            try { resolver.cleanup() } catch (_: Throwable) {}
                        }
                    }
                    if (html.length > 500) {
                        // 2026-07-04 : même check marker sur le fallback visible — évite le
                        //   cas où l'user résout un CF dialog mais l'hydratation n'a pas fini
                        //   (=on aurait toujours pas les saisons).
                        val markerOk = contentMarker == null ||
                            html.contains(contentMarker) ||
                            html.contains(contentMarker.replace("\"", "\\\""))
                        if (markerOk || html.length > 50_000) {
                            Log.d(TAG, "httpGet WebView visible OK $url (${html.length} chars, markerOk=$markerOk)")
                            markBypassFresh()
                            pageCachePut(cacheKey, html)
                            return@withContext html
                        }
                        Log.w(TAG, "httpGet WebView visible $url — marker '$contentMarker' ABSENT après visible → renvoie quand même (best-effort, seasons peuvent être vides)")
                        // Best-effort : on renvoie le HTML — les parseurs downstream feront
                        // ce qu'ils peuvent. Mieux que null qui casse la fiche entière.
                        pageCachePut(cacheKey, html)
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

    /**
     * 2026-07-04 : fetch RSC via OkHttp DIRECT (header RSC:1). Le WebView est trop instable sur
     *   les pages détail tv (CSP connect-src 'none' bloque le fetch interne + re-challenge par
     *   WebView fraîche + hydratation >12s sur Chromecast). Une fois le cf_clearance détail posé (detailWarm)
     *   et l'UA OkHttp aligné sur STEALTH_UA (cookie lié à l'UA), OkHttp peut récupérer le RSC
     *   server-rendered (~98 Ko, saisons/épisodes en clair). Réponse = null si 403/challenge → le
     *   caller retombe sur le WebView. La clé de succès : le RSC contient le marker demandé.
     */
    private suspend fun okHttpRsc(url: String, marker: String): String? = withContext(Dispatchers.IO) {
        networkSemaphore.withPermit {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("RSC", "1")
                    .header("Accept", "text/x-component,*/*;q=0.9")
                    .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Referer", baseUrl)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        if (body.contains(marker)) {
                            Log.d(TAG, "okHttpRsc OK $url (${body.length} chars, marker présent)")
                            return@withContext body
                        }
                        Log.d(TAG, "okHttpRsc $url → 200 mais marker '$marker' absent (${body.length} chars)")
                    } else {
                        Log.d(TAG, "okHttpRsc $url → ${resp.code} (fallback WebView)")
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "okHttpRsc KO $url: ${e.message}") }
            null
        }
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
        // 2026-07-04 : flux RSC (home/catalogue via header RSC:1) — les items sont des OBJETS
        //   {"slug":…,"mediaType":"MOVIE|TV","title":…,"posterPath":…} SANS href="/tv/…" (le href
        //   y est en clé JSON "href":"…", pas en attribut HTML). ITEM_HREF_REGEX ne les voit donc
        //   pas. On itère les objets RSC directement (ordre du flux) → parseHomeItems marche AUSSI
        //   sur les réponses RSC légères (104 Ko) que sur les pages HTML lourdes (378 Ko).
        if (out.isEmpty() || html.contains("\"mediaType\"")) {
            val un = if (html.length < 800_000) html.replace("\\\"", "\"") else html
            for (m in RSC_SLUG_REGEX.findAll(un)) {
                try {
                    val o = JSONObject(m.value)
                    val slug = o.optString("slug").ifBlank { continue }
                    if (!seen.add(slug)) continue
                    jsonToItem(o)?.let { out.add(it) }
                } catch (_: Exception) {}
            }
        }
        return out
    }

    // ── Provider API ────────────────────────────────────────────────────

    /** Récupère une page du catalogue (/catalogue?page=N, 14 items mixés films+tv).
     *  2026-06-23 : cache ITEMS PARSÉS (pas juste le HTML) pour que
     *  getMovies(page=4) et getTvShows(page=4) ne refetchent pas
     *  les mêmes pages catalogue. Divise par 2 les appels WebView. */
    //   2026-07-04 (user "je trouve que 6 films sur 375 pages") : le catalogue MÉLANGÉ
    //   (/catalogue?page=N) ne rend que ~5 films par page → l'onglet Films semblait quasi vide.
    //   Le site expose un filtre mediaType (vérifié en direct : /catalogue?mediaType=MOVIE&page=N
    //   = 12 films PROPRES/page, pages distinctes, ~375 pages de contenu). On l'utilise pour
    //   getMovies (MOVIE) et getTvShows (TV) → 12 items du bon type par page.
    private suspend fun fetchCatalogPage(page: Int, mediaType: String? = null): List<AppAdapter.Item> {
        val cacheKey = (mediaType ?: "ALL") + ":" + page
        // Cache items parsés (survit entre getMovies ↔ getTvShows)
        catalogItemsCache[cacheKey]?.let { (ts, items) ->
            if (System.currentTimeMillis() - ts < CATALOG_ITEMS_TTL_MS) {
                Log.d(TAG, "fetchCatalogPage($cacheKey) → cache hit (${items.size} items)")
                return items
            }
        }
        val url = if (mediaType != null) "$baseUrl/catalogue?mediaType=$mediaType&page=$page"
                  else "$baseUrl/catalogue?page=$page"
        val html = httpGet(url) ?: return emptyList()
        val items = parseHomeItems(html)
        catalogItemsCache[cacheKey] = System.currentTimeMillis() to items
        Log.d(TAG, "fetchCatalogPage($cacheKey) → fetched (${items.size} items, mis en cache)")
        return items
    }

    // 2026-07-04 v2 (user "les catégories du home ne sont pas les vraies du site — je veux
    //   En Tendance, Top Français, Nouveaux Épisodes, Nouveaux Films, Mieux Notés") :
    //   on parse les VRAIES sections du SSR HTML au lieu de découper en chunks artificiels.
    //   Le SSR contient 5 <header><p>SECTION</p></header> avec ~12 items chacune.
    //   Regex flexible qui capture aussi les hrefs épisode (/tv/slug/3/9).
    private val SECTION_HEADER_REGEX = Regex("""<header[^>]*>\s*<p[^>]*>([^<]+)</p>""")
    private val SECTION_ITEM_REGEX = Regex("""<img\b[^>]*\balt="([^"]*)"[^>]*\bsrc="([^"]+)"[\s\S]{0,800}?href="\/(movie|tv)\/([^"\/]+)""")

    // 2026-07-09 : getHome 100% TMDB — ZÉRO CF, ZÉRO scraping, chargement INSTANTANÉ.
    //   Le site DessinAnime a 363+ pages d'animation sur TMDB. On utilise Discover (genre
    //   Animation=16) + Trending pour peupler le home. Le CF bypass tourne EN FOND (warmUpCf)
    //   pour être prêt quand le user cliquera sur un titre (= getServers, seul endroit CF).
    override suspend fun getHome(): List<Category> = coroutineScope {
        // Lancer le préchauffage CF en fond (non bloquant) pour les futurs getServers
        launch(Dispatchers.IO) {
            try { warmUpCf() } catch (_: Exception) {}
        }

        val animGenreMovie = TMDb3.Params.WithBuilder(TMDb3.Genre.Movie.ANIMATION)
        val animGenreTv = TMDb3.Params.WithBuilder(TMDb3.Genre.Tv.ANIMATION)
        val categories = mutableListOf<Category>()

        try {
            // ── FEATURED (carrousel) : Tendances animation de la semaine ──
            val trending = TMDb3.Trending.all(TMDb3.Params.TimeWindow.WEEK, language = "fr-FR")
            val trendingAnim = trending.results.filter { item ->
                when (item) {
                    is TMDb3.Movie -> 16 in item.genresIds
                    is TMDb3.Tv -> 16 in item.genresIds
                    else -> false
                }
            }.take(12)
            if (trendingAnim.isNotEmpty()) {
                val featured = trendingAnim.take(6).map { it.toAppItem(banner = true) }
                categories.add(Category(name = Category.FEATURED, list = featured))
                if (trendingAnim.size > 6) {
                    categories.add(Category(name = "En Tendance", list = trendingAnim.drop(6).map { it.toAppItem() }))
                }
            }

            // ── Films Populaires ──
            val popMovies = TMDb3.Discover.movie(
                withGenres = animGenreMovie,
                language = "fr-FR",
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                page = 1,
            )
            if (popMovies.results.isNotEmpty()) {
                categories.add(Category(name = "Films Populaires", list = popMovies.results.take(20).map { it.toAppMovie() }))
            }

            // ── Séries Populaires ──
            val popTv = TMDb3.Discover.tv(
                withGenres = animGenreTv,
                language = "fr-FR",
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                page = 1,
            )
            if (popTv.results.isNotEmpty()) {
                categories.add(Category(name = "Séries Populaires", list = popTv.results.take(20).map { it.toAppTvShow() }))
            }

            // ── Mieux Notés (films animation avec ≥100 votes) ──
            val topRated = TMDb3.Discover.movie(
                withGenres = TMDb3.Params.WithBuilder(TMDb3.Genre.Movie.ANIMATION),
                language = "fr-FR",
                sortBy = TMDb3.Params.SortBy.Movie.VOTE_AVERAGE_DESC,
                voteCount = TMDb3.Params.Range(gte = 100),
                page = 1,
            )
            if (topRated.results.isNotEmpty()) {
                categories.add(Category(name = "Mieux Notés", list = topRated.results.take(20).map { it.toAppMovie() }))
            }

            // ── Nouveautés Films ──
            val newMovies = TMDb3.Discover.movie(
                withGenres = TMDb3.Params.WithBuilder(TMDb3.Genre.Movie.ANIMATION),
                language = "fr-FR",
                sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                voteCount = TMDb3.Params.Range(gte = 5),
                page = 1,
            )
            if (newMovies.results.isNotEmpty()) {
                categories.add(Category(name = "Nouveaux Films", list = newMovies.results.take(20).map { it.toAppMovie() }))
            }

            // ── Nouveautés Séries ──
            val newTv = TMDb3.Discover.tv(
                withGenres = TMDb3.Params.WithBuilder(TMDb3.Genre.Tv.ANIMATION),
                language = "fr-FR",
                sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,
                voteCount = TMDb3.Params.Range(gte = 5),
                page = 1,
            )
            if (newTv.results.isNotEmpty()) {
                categories.add(Category(name = "Nouvelles Séries", list = newTv.results.take(20).map { it.toAppTvShow() }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getHome TMDB failed: ${e.message}")
        }

        Log.d(TAG, "getHome → ${categories.size} categories (100% TMDB, zéro CF)")
        categories
    }

    // ── Helpers conversion TMDB → modèles app ──────────────────────────
    private fun TMDb3.Movie.toAppMovie(): Movie {
        val poster = this.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
        val banner = this.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
        return Movie(
            id = "movie::tmdb::${this.id}",
            title = this.title,
            poster = poster,
            banner = banner,
            released = this.releaseDate?.take(4),
            rating = this.voteAverage.takeIf { it > 0 }?.toDouble(),
        )
    }

    private fun TMDb3.Tv.toAppTvShow(): TvShow {
        val poster = this.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
        val banner = this.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
        return TvShow(
            id = "tv::tmdb::${this.id}",
            title = this.name,
            poster = poster,
            banner = banner,
            released = this.firstAirDate?.take(4),
            rating = this.voteAverage.takeIf { it > 0 }?.toDouble(),
        )
    }

    private fun TMDb3.MultiItem.toAppItem(banner: Boolean = false): AppAdapter.Item = when (this) {
        is TMDb3.Movie -> {
            val p = this.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            val b = if (banner) (this.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" } ?: p) else null
            Movie(id = "movie::tmdb::${this.id}", title = this.title, poster = p, banner = b,
                released = this.releaseDate?.take(4), rating = this.voteAverage.takeIf { it > 0 }?.toDouble())
        }
        is TMDb3.Tv -> {
            val p = this.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            val b = if (banner) (this.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" } ?: p) else null
            TvShow(id = "tv::tmdb::${this.id}", title = this.name, poster = p, banner = b,
                released = this.firstAirDate?.take(4), rating = this.voteAverage.takeIf { it > 0 }?.toDouble())
        }
        else -> Movie(id = "unknown", title = "?")
    }

    // 2026-07-09 : search 100% TMDB (genre Animation, fr-FR) — ZÉRO CF.
    //   Résultats instantanés. Le site n'est plus interrogé pour la recherche.
    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            if (page > 1) return@withContext emptyList()
            return@withContext listOf(
                Genre(id = "all", name = "Tout le catalogue"),
                Genre(id = "movies", name = "Films"),
                Genre(id = "tv", name = "Séries"),
            )
        }
        if (page > 1) return@withContext emptyList()
        try {
            val tmdbResults = TMDb3.Search.multi(query, language = "fr-FR")
            val items = tmdbResults.results
                .filter { item ->
                    when (item) {
                        is TMDb3.Movie -> 16 in item.genresIds  // Animation
                        is TMDb3.Tv -> 16 in item.genresIds
                        else -> false
                    }
                }
                .map { it.toAppItem() }
            Log.d(TAG, "search('$query') → ${items.size} results (TMDB animation)")
            items
        } catch (e: Exception) {
            Log.w(TAG, "search TMDB failed: ${e.message}")
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

    // 2026-07-09 : catalogue 100% TMDB Discover (genre Animation, fr-FR) — ZÉRO CF.
    //   TMDB a 363+ pages d'animation. Pagination native TMDB (20 items/page).
    override suspend fun getMovies(page: Int): List<Movie> = withContext(Dispatchers.IO) {
        try {
            val result = TMDb3.Discover.movie(
                withGenres = TMDb3.Params.WithBuilder(TMDb3.Genre.Movie.ANIMATION),
                language = "fr-FR",
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                page = page,
            )
            val movies = result.results.map { it.toAppMovie() }
            Log.d(TAG, "getMovies(page=$page) → ${movies.size} movies (TMDB Discover)")
            movies
        } catch (e: Exception) {
            Log.w(TAG, "getMovies TMDB failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = withContext(Dispatchers.IO) {
        try {
            val result = TMDb3.Discover.tv(
                withGenres = TMDb3.Params.WithBuilder(TMDb3.Genre.Tv.ANIMATION),
                language = "fr-FR",
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                page = page,
            )
            val shows = result.results.map { it.toAppTvShow() }
            Log.d(TAG, "getTvShows(page=$page) → ${shows.size} shows (TMDB Discover)")
            shows
        } catch (e: Exception) {
            Log.w(TAG, "getTvShows TMDB failed: ${e.message}")
            emptyList()
        }
    }

    /** Extrait slug + type depuis id "movie::SLUG" ou "tv::SLUG". */
    private fun parseInternalId(id: String): Pair<String, String>? {
        val parts = id.split("::")
        if (parts.size < 2) return null
        if (parts[0] !in setOf("movie", "tv")) return null
        return parts[0] to parts[1]
    }

    // 2026-07-09 : getMovie 100% TMDB — ZÉRO CF. Si l'ID est movie::tmdb::ID, on
    //   appelle Movies.details directement. Si c'est un ancien slug (movie::SLUG),
    //   on extrait le tmdbId du slug (format "<tmdbId>-<titre>") et on fait pareil.
    override suspend fun getMovie(id: String): Movie = withContext(Dispatchers.IO) {
        val tmdbId = extractTmdbId(id)
        if (tmdbId != null) {
            try {
                val detail = TMDb3.Movies.details(tmdbId, language = "fr-FR")
                val poster = detail.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val banner = detail.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
                Log.d(TAG, "getMovie($id) → TMDB detail '${detail.title}' (tmdbId=$tmdbId)")
                return@withContext Movie(
                    id = id,
                    title = detail.title,
                    overview = detail.overview,
                    poster = poster,
                    banner = banner,
                    released = detail.releaseDate?.take(4),
                    rating = detail.voteAverage.takeIf { it > 0 }?.toDouble(),
                    runtime = detail.runtime,
                )
            } catch (e: Exception) {
                Log.w(TAG, "getMovie TMDB failed (tmdbId=$tmdbId): ${e.message}")
            }
        }
        // Fallback : ancien slug sans tmdbId extractible
        val (type, slug) = parseInternalId(id) ?: return@withContext Movie(id = id, title = id)
        if (type != "movie") return@withContext Movie(id = id, title = slug)
        val titleFromSlug = slug.replace(Regex("^\\d+-"), "").replace("-", " ").replaceFirstChar { it.uppercase() }
        Movie(id = id, title = titleFromSlug)
    }

    /** Extrait le tmdbId depuis un ID DessinAnime (movie::tmdb::123 ou movie::123-slug ou tv::tmdb::456). */
    private fun extractTmdbId(id: String): Int? {
        val parts = id.split("::")
        // Format nouveau : movie::tmdb::123 ou tv::tmdb::123
        if (parts.size >= 3 && parts[1] == "tmdb") {
            return parts[2].toIntOrNull()
        }
        // Format ancien : movie::123-slug ou tv::123-slug (le slug commence par le tmdbId)
        if (parts.size >= 2) {
            return parts[1].substringBefore("-").toIntOrNull()
        }
        return null
    }

    /**
     * 2026-07-09 : Résout un slug DessinAnime depuis un ID (nouveau tmdb:: ou ancien slug).
     * Pour les IDs tmdb:: : recherche le titre sur /api/search du site pour trouver le slug.
     * Pour les anciens IDs (movie::slug) : extrait le slug directement.
     * Retourne null si impossible (slug introuvable → les backups prendront le relais).
     */
    private suspend fun resolveSlugFromId(id: String, title: String): String? {
        val parts = id.split("::")
        // Format nouveau tmdb:: → rechercher le titre sur le site
        if (parts.size >= 3 && parts[1] == "tmdb") {
            val tmdbId = parts[2].toIntOrNull()
            if (title.isBlank()) {
                Log.w(TAG, "resolveSlugFromId: titre vide pour id=$id")
                return null
            }
            // Recherche sur le site DessinAnime pour trouver le slug correspondant
            try {
                val searchUrl = "$baseUrl/api/search?q=${java.net.URLEncoder.encode(title, "UTF-8")}"
                val body = httpGet(searchUrl)
                if (body != null) {
                    val arr = JSONArray(body)
                    // Chercher le meilleur match : tmdbId direct ou titre exact
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: continue
                        // Le slug DessinAnime commence par le tmdbId (ex: "269149-zootopie")
                        val slugTmdb = slug.substringBefore("-").toIntOrNull()
                        if (slugTmdb != null && slugTmdb == tmdbId) {
                            Log.d(TAG, "resolveSlugFromId: tmdb::$tmdbId → slug='$slug' (tmdbId match)")
                            return slug
                        }
                    }
                    // Fallback : premier résultat dont le titre matche
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: continue
                        val candidateTitle = obj.optString("title")
                        if (candidateTitle.equals(title, ignoreCase = true)) {
                            Log.d(TAG, "resolveSlugFromId: tmdb::$tmdbId → slug='$slug' (titre match)")
                            return slug
                        }
                    }
                    // Dernier fallback : premier résultat tout court
                    val firstSlug = arr.optJSONObject(0)?.optString("slug")?.takeIf { it.isNotBlank() }
                    if (firstSlug != null) {
                        Log.d(TAG, "resolveSlugFromId: tmdb::$tmdbId → slug='$firstSlug' (1er résultat)")
                        return firstSlug
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolveSlugFromId search KO: ${e.message}")
            }
            Log.w(TAG, "resolveSlugFromId: aucun slug trouvé pour '$title' (tmdb::$tmdbId)")
            return null
        }
        // Format ancien : movie::slug ou tv::slug::sN::eM → extraire le slug
        return parseInternalId(id)?.second
            ?: parts.getOrNull(1)
    }

    // 2026-07-09 : getTvShow 100% TMDB — ZÉRO CF. Saisons incluses dans TvSeries.details.
    //   Gère movie::tmdb::ID, tv::tmdb::ID et les anciens slugs tv::123-slug.
    override suspend fun getTvShow(id: String): TvShow = withContext(Dispatchers.IO) {
        val tmdbId = extractTmdbId(id)
        if (tmdbId != null) {
            try {
                val detail = TMDb3.TvSeries.details(tmdbId, language = "fr-FR")
                val poster = detail.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val banner = detail.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
                // Construire les saisons depuis la liste TMDB (exclure Saison 0 = Specials si vide)
                val seasons = detail.seasons
                    .filter { it.seasonNumber > 0 || (it.episodeCount ?: 0) > 0 }
                    .map { s ->
                        val sPoster = s.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" } ?: poster
                        Season(
                            id = "tv::tmdb::${tmdbId}::s${s.seasonNumber}",
                            number = s.seasonNumber,
                            title = s.name.ifBlank { "Saison ${s.seasonNumber}" },
                            poster = sPoster,
                        )
                    }
                Log.d(TAG, "getTvShow($id) → TMDB '${detail.name}', ${seasons.size} saisons (tmdbId=$tmdbId)")
                return@withContext TvShow(
                    id = id,
                    title = detail.name,
                    overview = detail.overview,
                    poster = poster,
                    banner = banner,
                    released = detail.firstAirDate?.take(4),
                    rating = detail.voteAverage.takeIf { it > 0 }?.toDouble(),
                    seasons = seasons,
                )
            } catch (e: Exception) {
                Log.w(TAG, "getTvShow TMDB failed (tmdbId=$tmdbId): ${e.message}")
            }
        }
        // Fallback : ancien slug sans tmdbId extractible
        val (type, slug) = parseInternalId(id) ?: return@withContext TvShow(id = id, title = id)
        if (type != "tv") return@withContext TvShow(id = id, title = slug)
        val titleFromSlug = slug.replace(Regex("^\\d+-"), "").replace("-", " ").replaceFirstChar { it.uppercase() }
        TvShow(id = id, title = titleFromSlug)
    }

    // 2026-07-09 : getEpisodesBySeason 100% TMDB — ZÉRO CF.
    //   Format seasonId : tv::tmdb::123::s2 (nouveau) ou tv::slug::s2 (ancien).
    //   TvSeasons.details retourne épisodes + stills + noms en 1 appel (~1 Ko).
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = withContext(Dispatchers.IO) {
        val parts = seasonId.split("::")
        // Nouveau format : tv::tmdb::123::s2 (4 parts)
        // Ancien format  : tv::slug::s2 (3 parts)
        val seasonNumStr = parts.lastOrNull()?.removePrefix("s") ?: return@withContext emptyList()
        val seasonNum = seasonNumStr.toIntOrNull() ?: return@withContext emptyList()

        // Extraire le tmdbId
        val tmdbId: Int? = if (parts.size >= 4 && parts[1] == "tmdb") {
            parts[2].toIntOrNull()
        } else if (parts.size >= 3) {
            // Ancien slug : tv::123-slug::s2
            parts[1].substringBefore("-").toIntOrNull()
        } else null

        if (tmdbId == null) {
            Log.w(TAG, "getEpisodesBySeason: impossible d'extraire tmdbId de '$seasonId'")
            return@withContext emptyList()
        }

        try {
            val seasonDetail = TMDb3.TvSeasons.details(tmdbId, seasonNum, language = "fr-FR")
            val tmdbEps = seasonDetail.episodes ?: emptyList()
            val episodes = tmdbEps.map { ep ->
                val still = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                val epTitle = ep.name?.takeIf { it.isNotBlank() } ?: "Épisode ${ep.episodeNumber}"
                // L'ID épisode suit le format de la saison : tv::tmdb::123::s2::e5
                val idPrefix = if (parts.size >= 4 && parts[1] == "tmdb") "tv::tmdb::$tmdbId" else "tv::${parts[1]}"
                Episode(
                    id = "$idPrefix::s$seasonNum::e${ep.episodeNumber}",
                    number = ep.episodeNumber,
                    title = epTitle,
                    poster = still,
                )
            }
            Log.d(TAG, "getEpisodesBySeason($seasonId) → ${episodes.size} eps TMDB (tmdbId=$tmdbId, s$seasonNum)")
            episodes
        } catch (e: Exception) {
            Log.w(TAG, "getEpisodesBySeason TMDB failed (tmdbId=$tmdbId, s$seasonNum): ${e.message}")
            emptyList()
        }
    }

    // 2026-07-09 : getGenre via TMDB Discover (genre Animation) — ZÉRO CF.
    override suspend fun getGenre(id: String, page: Int): Genre = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext Genre(id = id, name = id, shows = emptyList())
        try {
            val shows: List<com.streamflixreborn.streamflix.models.Show> = when (id) {
                "movies" -> TMDb3.Discover.movie(
                    withGenres = TMDb3.Params.WithBuilder(TMDb3.Genre.Movie.ANIMATION),
                    language = "fr-FR", sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC, page = 1,
                ).results.take(20).map { it.toAppMovie() }
                "tv" -> TMDb3.Discover.tv(
                    withGenres = TMDb3.Params.WithBuilder(TMDb3.Genre.Tv.ANIMATION),
                    language = "fr-FR", sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC, page = 1,
                ).results.take(20).map { it.toAppTvShow() }
                else -> {
                    val movies = TMDb3.Discover.movie(
                        withGenres = TMDb3.Params.WithBuilder(TMDb3.Genre.Movie.ANIMATION),
                        language = "fr-FR", sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC, page = 1,
                    ).results.take(10).map { it.toAppMovie() }
                    val tvShows = TMDb3.Discover.tv(
                        withGenres = TMDb3.Params.WithBuilder(TMDb3.Genre.Tv.ANIMATION),
                        language = "fr-FR", sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC, page = 1,
                    ).results.take(10).map { it.toAppTvShow() }
                    movies + tvShows
                }
            }
            Genre(id = id, name = when (id) {
                "movies" -> "Films"
                "tv" -> "Séries"
                else -> "Tout"
            }, shows = shows)
        } catch (e: Exception) {
            Log.w(TAG, "getGenre TMDB failed: ${e.message}")
            Genre(id = id, name = id, shows = emptyList())
        }
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
    // 2026-07-09 : getServers gère les IDs TMDB (movie::tmdb::123, tv::tmdb::123::s2::e5).
    //   Pour les serveurs natifs, on recherche le titre sur le site via /api/search pour
    //   trouver le slug, puis on utilise fetchNativeServers avec le slug. CF bypass ICI seulement.
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = withContext(Dispatchers.IO) {
        val native = try {
            fetchNativeServers(id, videoType)
        } catch (e: Exception) {
            Log.w(TAG, "native servers KO: ${e.message}"); emptyList()
        }

        // Backups TOUJOURS lancés (même si natifs présents)

        // 2026-07-04 : backups inline DÉSACTIVÉS → registre central.
        if (com.streamflixreborn.streamflix.utils.BackupRegistry.INLINE_BACKUPS_DISABLED) return@withContext native

        val title = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }.trim()
        if (title.isBlank()) return@withContext native

        // Extraire le tmdbId — fonctionne avec movie::tmdb::123 ET movie::123-slug
        val tmdbId = extractTmdbId(id)?.toString() ?: "0"
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
                // Backup #4 : NetMirror — DÉSACTIVÉ (2026-07-08, user : « on retire NetMirror des
                //   autres backups »). NetMirror exige un challenge Turnstile visible qui ne doit
                //   apparaître QUE quand NetMirror est le provider choisi, jamais en backup ici.
                val nmD = async { emptyList<Video.Server>() }
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
        // 2026-07-04 : backups inline DÉSACTIVÉS → registre central.
        if (com.streamflixreborn.streamflix.utils.BackupRegistry.INLINE_BACKUPS_DISABLED) { nativeJob.join(); return@channelFlow }

        // 2026-07-09 : extractTmdbId gère les 2 formats (tmdb:: et ancien slug)
        val tmdbId = extractTmdbId(id)?.toString() ?: "0"

        // VideoType pour Cloudstream (tmdbId extrait)
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
        // Backup #4 : NetMirror — DÉSACTIVÉ (2026-07-08, user : « on retire NetMirror des autres
        //   backups » — son challenge Turnstile ne doit apparaître que si NetMirror est choisi).
        val nmJob = launch(Dispatchers.IO) { /* NetMirror backup désactivé */ }
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
        // 2026-07-09 : bypass CF AVANT le fetch RSC — c'est le SEUL endroit qui touche au site.
        ensureCfBypass()
        // 2026-07-09 : résolution de l'URL de la page. Si l'ID est tmdb:: (nouveau format),
        //   on recherche le titre sur le site pour trouver le slug natif.
        val pageUrl: String = when (videoType) {
            is Video.Type.Movie -> {
                val slug = resolveSlugFromId(id, videoType.title) ?: return@withContext emptyList()
                "$baseUrl/movie/$slug"
            }
            is Video.Type.Episode -> {
                val title = videoType.tvShow.title
                val slug = resolveSlugFromId(id, title) ?: return@withContext emptyList()
                val s = videoType.season.number
                val e = videoType.number
                "$baseUrl/tv/$slug/$s/$e"
            }
        }
        Log.d(TAG, "getServers fetch $pageUrl")
        // 2026-07-04 : récupération des sources du player via fetch `RSC:1` DANS le WebView
        //   (contexte navigateur → passe CF, là où OkHttp reçoit 403 et où l'hydratation
        //   complète de la page est trop lourde/lente sur TV). Réponse ~184 Ko contenant
        //   directement les tokens extractor.nmlnode.cc. Marqueur = structure des sources.
        // 2026-07-06 : le site est passé au format "players":[{"host","url"}] (uqload/hydrax…).
        //   L'ancien marqueur "sources":[{"type" n'existe PLUS → le WebView pollait ~60× dans le
        //   vide (markerOk=false) puis « No iframe found » = 0 serveur. On attend le NOUVEAU
        //   marqueur (fallback sur l'ancien pour compat si une vieille page traîne en cache).
        val html = httpGet(
            pageUrl,
            contentMarker = "\"players\":[{\"host\"",
            rscFetchUrl = pageUrl,
        ) ?: httpGet(
            pageUrl,
            contentMarker = "\"sources\":[{\"type\"",
            rscFetchUrl = pageUrl,
        ) ?: return@withContext emptyList()

        // ── 2026-07-06 : NOUVEAU format du site — "players":[{"host":"...","url":"..."}] ──
        //   Le lecteur liste directement les embeds (hydrax→abysscdn.com, uqload→uqload.is…).
        //   Chaque url est un embed jouable par les extracteurs in-app (getVideo route par domaine).
        run {
            val h = if (html.contains("\\\"players\\\"")) html.replace("\\\"", "\"") else html
            val marker = "\"players\":[{\"host\""
            val idx = h.indexOf(marker)
            if (idx < 0) return@run
            val arrStart = h.indexOf('[', idx)
            if (arrStart < 0) return@run
            var depth = 0
            var arrEnd = -1
            for (i in arrStart until minOf(h.length, arrStart + 20000)) {
                when (h[i]) {
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { arrEnd = i + 1; break } }
                }
            }
            if (arrEnd < 0) return@run
            val arr = try { JSONArray(h.substring(arrStart, arrEnd)) } catch (_: Exception) { return@run }
            val servers = mutableListOf<Video.Server>()
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val host = o.optString("host").ifBlank { "lecteur" }
                val url = o.optString("url")
                if (url.startsWith("http") && seen.add(url)) {
                    servers.add(Video.Server(id = url, name = host.replaceFirstChar { it.uppercase() }, src = url))
                }
            }
            if (servers.isNotEmpty()) {
                Log.d(TAG, "getServers players[]: ${servers.size} embeds (${servers.joinToString { it.name }})")
                return@withContext servers
            }
        }

        // 2026-07-04 : NOUVEAU format RSC — sources directes nmlnode
        // Le payload RSC contient \"title\":\"S1E1\",\"sources\":[{\"type\":\"mp4\",
        //   \"host\":\"hydrax\",...,\"sources\":[{\"label\":\"1080p\",
        //   \"source\":\"https://extractor.nmlnode.cc/proxy/mp4?token=...\"}]}]
        // Les URLs nmlnode sont directement jouables par ExoPlayer (court-circuit getVideo).
        run {
            // 2026-07-04 : le HTML peut être ÉCHAPPÉ (OkHttp, RSC brut : \"sources\") ou
            //   DÉJÀ DÉSÉCHAPPÉ (WebView : le resolver remplace \" par "). On NORMALISE en
            //   déséchappant, puis on cherche des marqueurs NON échappés. Sans ça, sur une
            //   page WebView (SEUL chemin possible : l'épisode renvoie 403 en OkHttp), les
            //   marqueurs échappés n'étaient JAMAIS trouvés → 0 serveur natif (le bug série).
            val h = if (html.contains("\\\"sources\\\"")) html.replace("\\\"", "\"") else html
            // Ancre directement sur le tableau de sources du lecteur : "sources":[{"type"
            val srcMarker = "\"sources\":[{\"type\""
            val srcIdx = h.indexOf(srcMarker)
            if (srcIdx < 0) return@run

            val arrStart = srcIdx + srcMarker.indexOf("[") // pointe sur [
            var depth = 0
            var arrEnd = -1
            for (i in arrStart until minOf(h.length, arrStart + 50000)) {
                when (h[i]) {
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { arrEnd = i + 1; break } }
                }
            }
            if (arrEnd < 0) return@run

            val raw = h.substring(arrStart, arrEnd)
            val arr = try { JSONArray(raw) } catch (_: Exception) { return@run }
            if (arr.length() == 0) return@run

            val rscServers = mutableListOf<Video.Server>()
            // 2026-07-04 (user "pourquoi autant de fois le même serveur") : le tableau sources du
            //   site répète souvent le même host/qualité (miroirs). On DÉDUPLIQUE par URL src ET
            //   par (host+label) → une seule entrée "Hydrax 1080p", "Hydrax 720p", etc.
            val seenSrc = HashSet<String>()
            val seenName = HashSet<String>()
            for (i in 0 until arr.length()) {
                val group = arr.optJSONObject(i) ?: continue
                val host = group.optString("host", "lecteur")
                val hostName = host.replaceFirstChar { it.uppercase() }
                val type = group.optString("type")
                val sources = group.optJSONArray("sources")

                if (sources != null && sources.length() > 0) {
                    if (type == "m3u8") {
                        // HLS adaptatif — 1 serveur par host (qualité auto)
                        val src = sources.optJSONObject(0)?.optString("source")
                        if (!src.isNullOrBlank() && seenSrc.add(src) && seenName.add(hostName)) {
                            rscServers.add(Video.Server(id = src, name = hostName, src = src))
                        }
                    } else {
                        // MP4 — plusieurs qualités, meilleure en premier
                        val sorted = (0 until sources.length())
                            .mapNotNull { j -> sources.optJSONObject(j) }
                            .sortedByDescending { it.optString("label").filter(Char::isDigit).toIntOrNull() ?: 0 }
                        for (s in sorted) {
                            val src = s.optString("source")
                            val label = s.optString("label")
                            val name = "$hostName $label"
                            if (src.isNotBlank() && seenSrc.add(src) && seenName.add(name)) {
                                rscServers.add(Video.Server(id = src, name = name, src = src))
                            }
                        }
                    }
                }
                // Iframe en fallback (traité par les extracteurs in-app)
                val iframeUrl = group.optString("iframe_url")
                if (iframeUrl.isNotBlank() && iframeUrl.startsWith("http") && seenSrc.add(iframeUrl) && seenName.add("$hostName (embed)")) {
                    rscServers.add(Video.Server(id = iframeUrl, name = "$hostName (embed)", src = iframeUrl))
                }
            }
            if (rscServers.isNotEmpty()) {
                Log.d(TAG, "getServers RSC sources: ${rscServers.size} direct sources (nmlnode+iframe)")
                return@withContext rscServers
            }
        }

        // ── Ancien format RSC (embedId/iframeTemplate) — fallback ──
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
        // 2026-07-04 : nmlnode proxy URLs → lecture directe (pas d'extracteur)
        if (server.src.contains("extractor.nmlnode.cc/proxy/")) {
            return Video(
                source = server.src,
                headers = mapOf(
                    "Referer" to "$baseUrl/",
                    "Origin" to baseUrl,
                    "User-Agent" to UA,
                ),
            )
        }
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
