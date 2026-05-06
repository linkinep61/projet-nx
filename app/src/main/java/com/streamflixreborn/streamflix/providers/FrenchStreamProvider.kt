package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
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
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Url
import retrofit2.Response
import okhttp3.ResponseBody
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import org.jsoup.nodes.Element
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import kotlin.math.round
import kotlinx.coroutines.coroutineScope

object FrenchStreamProvider : Provider, ProviderPortalUrl, ProviderConfigUrl {
    override val name = "FrenchStream"

    override val defaultPortalUrl: String = "http://fstream.info/"

    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty { field }
        }

    override val defaultBaseUrl: String = "https://fs03.lol/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }

    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_frenchstream"
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    // ── 2026-05-04 : Résilience style Movix ──────────────────────────────
    // Centralisation des paths AJAX, timeouts stricts, circuit breaker par
    // endpoint et cache mémoire de l'agrégat getServers. Objectif : que
    // FrenchStream se comporte aussi bien que Movix quand un endpoint tombe
    // ou que le serveur traîne — l'app doit jamais bloquer plus de 8s sur
    // un appel et l'utilisateur doit pas refaire les mêmes appels en boucle.

    /** Centralisation des endpoints AJAX. Si FrenchStream change ses chemins
     *  (déjà arrivé entre VFV1 et VFR1), on patche ici une seule fois.
     *  Ces valeurs servent de défaut — le provider tente une auto-découverte
     *  quand un endpoint disable, cf [filmAjaxPath]/[serieAjaxPath]. */
    private object Endpoints {
        const val FILM_AJAX_JSON = "engine/ajax/film_api.php"
        const val SERIE_AJAX_JSON = "engine/ajax/sx.php"
        const val DEFAULT_REFERER = "https://fs03.lol/"
        const val DLE_SKIN_COOKIE = "dle_skin=VFV1"
    }

    // 2026-05-04 : Auto-découverte des paths AJAX. Quand l'endpoint hardcodé
    // tombe (FS renomme le fichier PHP, déjà arrivé entre VFV1/VFR1), on scanne
    // les <script src="/js/..."> de la page detail qu'on charge en fallback,
    // on récupère leur contenu, et on grep `engine/ajax/X.php`. Si on trouve
    // un nouveau path différent du défaut, on le stocke ici et le prochain
    // appel l'utilisera automatiquement.
    private val filmAjaxPath = java.util.concurrent.atomic.AtomicReference(Endpoints.FILM_AJAX_JSON)
    private val serieAjaxPath = java.util.concurrent.atomic.AtomicReference(Endpoints.SERIE_AJAX_JSON)
    private val lastDiscoveryAttemptMs = java.util.concurrent.atomic.AtomicLong(0L)
    private const val DISCOVERY_COOLDOWN_MS = 5L * 60L * 1000L // 5 min mini entre 2 scans

    private const val ENDPOINT_TIMEOUT_MS = 8_000L
    private const val ENDPOINT_FAILURE_THRESHOLD = 5
    private const val ENDPOINT_DISABLED_MS = 30L * 60L * 1000L // 30 min

    private data class EndpointHealth(
        var consecutiveFailures: Int = 0,
        var disabledUntilMs: Long = 0L,
    )
    private val endpointHealth = ConcurrentHashMap<String, EndpointHealth>()

    /** Wrap d'appel endpoint FrenchStream : timeout 8s + circuit breaker.
     *  Si ça timeout ou throw, on retourne emptyList(). 5 fails consécutifs
     *  → endpoint désactivé 30 min (skip immédiat). 1 succès → reset. */
    private suspend inline fun runEndpoint(
        endpointName: String,
        crossinline block: suspend () -> List<Video.Server>,
    ): List<Video.Server> {
        val health = endpointHealth.getOrPut(endpointName) { EndpointHealth() }
        val now = System.currentTimeMillis()
        if (now < health.disabledUntilMs) {
            Log.d("FrenchStream", "Endpoint '$endpointName' SKIP (disabled until ${health.disabledUntilMs})")
            return emptyList()
        }
        val result = try {
            withTimeoutOrNull(ENDPOINT_TIMEOUT_MS) { block() }
        } catch (e: Exception) {
            Log.w("FrenchStream", "Endpoint '$endpointName' threw: ${e.message}")
            null
        }
        synchronized(health) {
            if (result.isNullOrEmpty()) {
                health.consecutiveFailures++
                if (health.consecutiveFailures >= ENDPOINT_FAILURE_THRESHOLD) {
                    health.disabledUntilMs = System.currentTimeMillis() + ENDPOINT_DISABLED_MS
                    Log.w("FrenchStream", "Endpoint '$endpointName' disabled for 30min after ${health.consecutiveFailures} consecutive failures")
                    health.consecutiveFailures = 0
                }
            } else {
                health.consecutiveFailures = 0
            }
        }
        return result ?: emptyList()
    }

    /** Cache mémoire de l'agrégat getServers (TTL 5 min).
     *  L'utilisateur ferme le player et y revient → on refaisait AJAX +
     *  fallback HTML à chaque fois. Maintenant on cache par clé (id, season,
     *  episode) — bien plus snappy au retour. */
    private const val SERVERS_CACHE_TTL_MS = 5L * 60L * 1000L
    private data class CachedServers(val servers: List<Video.Server>, val expiresAtMs: Long)
    private val serversCache = ConcurrentHashMap<String, CachedServers>()

    private fun serversCacheKey(id: String, videoType: Video.Type): String = when (videoType) {
        is Video.Type.Movie -> "movie:$id"
        is Video.Type.Episode -> "tv:$id:e${videoType.number}"
    }

    /** Construit l'URL absolue d'un endpoint AJAX en utilisant le path
     *  potentiellement (re)découvert dynamiquement. */
    private fun buildAjaxUrl(path: String, query: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/') + "?" + query

    /** Récupère le body d'un endpoint AJAX et le valide comme JSON.
     *  Retourne le JSONObject parsé OU null avec un log explicite si :
     *    - body vide
     *    - body HTML (Cloudflare challenge, captcha, page d'erreur)
     *    - body pas du JSON valide
     *    - JSON top-level mais pas un objet (ex : `[]`, `null`, `42`)
     *  Le caller peut ensuite vérifier les clés attendues et logger si manquantes. */
    private fun parseJsonOrNull(body: String, source: String): org.json.JSONObject? {
        if (body.isBlank()) {
            Log.w("FrenchStream", "[$source] empty body — endpoint may be down")
            return null
        }
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<")) {
            // Cloudflare/captcha/HTML error page returned instead of JSON
            val preview = trimmed.take(120).replace('\n', ' ')
            Log.w("FrenchStream", "[$source] got HTML instead of JSON — likely WAF/captcha. Preview: $preview")
            return null
        }
        return try {
            val parsed = org.json.JSONObject(trimmed)
            if (parsed.optBoolean("error", false)) {
                Log.w("FrenchStream", "[$source] server returned {error:true} — content unavailable")
                return null
            }
            parsed
        } catch (e: org.json.JSONException) {
            val preview = trimmed.take(120).replace('\n', ' ')
            Log.w("FrenchStream", "[$source] JSON parse failed: ${e.message}. Preview: $preview")
            null
        }
    }

    /** Log les clés top-level d'un JSON quand on s'attendait à trouver une
     *  clé spécifique. Permet de détecter rapidement un changement de contrat. */
    private fun logUnexpectedShape(source: String, json: org.json.JSONObject, expectedKey: String) {
        val keys = json.keys().asSequence().toList().take(10)
        Log.w("FrenchStream", "[$source] missing expected key '$expectedKey'. Got top-level keys: $keys")
    }

    /** 2026-05-04 : Auto-découverte des paths AJAX en scannant les bundles
     *  JS référencés par une page detail. Si FrenchStream renomme `sx.php` →
     *  `episodes.php` ou `film_api.php` → `movie_api.php`, on l'apprend
     *  automatiquement la prochaine fois qu'on tombe sur une page detail.
     *
     *  Comment ça marche : on parse les `<script src="/js/...">` du document,
     *  on fetch chaque bundle JS, on grep `engine/ajax/X.php` dedans, et on
     *  classe ce qu'on trouve par heuristique :
     *    - paths qui contiennent "film"/"movie"  → filmAjaxPath
     *    - paths "sx"/"serie"/"tv"/"episode"     → serieAjaxPath
     *
     *  Cooldown 5min entre 2 scans pour pas hammer le serveur quand on a
     *  plusieurs échecs en rafale. */
    private suspend fun rediscoverEndpointsFromDocument(document: Document) {
        val now = System.currentTimeMillis()
        val last = lastDiscoveryAttemptMs.get()
        if (now - last < DISCOVERY_COOLDOWN_MS) return
        if (!lastDiscoveryAttemptMs.compareAndSet(last, now)) return // CAS pour éviter discovery concurrente

        val scriptSrcs = document.select("script[src]")
            .map { it.attr("src") }
            .filter { it.contains("/js/") && it.endsWith(".js") || it.contains(".js?v=") }
            .distinct()
            .take(8) // garde-fou, pas plus de 8 fetch JS

        if (scriptSrcs.isEmpty()) return
        Log.d("FrenchStream", "Endpoint rediscovery: scanning ${scriptSrcs.size} JS bundles")

        val ajaxRegex = Regex("""engine/ajax/([a-z0-9_]+)\.php""", RegexOption.IGNORE_CASE)
        val foundPaths = mutableSetOf<String>()

        for (src in scriptSrcs) {
            val fullUrl = when {
                src.startsWith("http") -> src
                src.startsWith("/") -> baseUrl.trimEnd('/') + src
                else -> baseUrl.trimEnd('/') + "/" + src
            }
            try {
                val js = withTimeoutOrNull(5_000L) { service.getRawText(fullUrl).string() }.orEmpty()
                if (js.isBlank()) continue
                ajaxRegex.findAll(js).forEach { match -> foundPaths.add(match.value) }
            } catch (_: Exception) {
                // skip ce bundle
            }
        }

        if (foundPaths.isEmpty()) {
            Log.d("FrenchStream", "Endpoint rediscovery: no AJAX paths found in JS bundles")
            return
        }
        Log.i("FrenchStream", "Endpoint rediscovery found: $foundPaths")

        // Heuristique de classification — on garde la plus spécifique d'abord
        foundPaths.forEach { path ->
            val lower = path.lowercase()
            when {
                lower.contains("film") || lower.contains("movie") -> {
                    if (filmAjaxPath.get() != path) {
                        Log.i("FrenchStream", "filmAjaxPath: ${filmAjaxPath.get()} → $path")
                        filmAjaxPath.set(path)
                        // Reset le circuit breaker pour ce endpoint, on retente avec le nouveau path
                        endpointHealth["film_api"]?.let { it.consecutiveFailures = 0; it.disabledUntilMs = 0L }
                    }
                }
                lower.contains("sx") || lower.contains("serie") || lower.contains("tv") || lower.contains("episode") -> {
                    if (serieAjaxPath.get() != path) {
                        Log.i("FrenchStream", "serieAjaxPath: ${serieAjaxPath.get()} → $path")
                        serieAjaxPath.set(path)
                        endpointHealth["sx_php"]?.let { it.consecutiveFailures = 0; it.disabledUntilMs = 0L }
                    }
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Extract newsid from href like /index.php?newsid=15126799.
     *  Falls back to slug (last path segment) for legacy URLs. */
    private fun extractNewsId(href: String): String? {
        if (href.contains("newsid=")) {
            return href.substringAfter("newsid=").substringBefore("&").takeIf { it.isNotBlank() }
        }
        return href.substringAfterLast("/").takeIf { it.isNotBlank() }
    }

    /** Fetch a detail page, routing numeric IDs through ?newsid= and slugs
     *  through the legacy /{slug} path. */
    private suspend fun fetchDetailPage(id: String): Document {
        return if (id.all { it.isDigit() }) {
            service.getItemByNewsId(id)
        } else {
            // Legacy slug — try /film/ then /serie/ then bare /{id}
            try { service.getMovie(id) }
            catch (_: Exception) {
                try { service.getTvShow(id) }
                catch (_: Exception) { service.getItem(id) }
            }
        }
    }

    /** Extrait l'URL d'une img en gérant le lazy-loading. Sur les pages
     *  search FS, les images ont `class="lazy" data-src="..."` et `src=""`
     *  (chargement différé via JS côté browser). On essaie data-src d'abord,
     *  puis les attributs alternatifs courants, puis src en dernier recours.
     *  On filtre les data:image (placeholder GIF transparent). */
    private fun extractImgUrl(item: Element): String? {
        val img = item.selectFirst("img") ?: return null
        return sequenceOf("data-src", "data-original", "data-lazy-src", "src")
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
    }

    fun ignoreSource(source: String, href: String): Boolean {
        if (source.trim().equals("Dood.Stream", ignoreCase = true) && href.contains("/bigwar5/")) return true
        // kakaflix.lol is a phantom domain that FrenchStream lists for the
        // VFF/VFQ/VOSTFR variants of Dood/Voe/Filmoon but every endpoint
        // returns a clean 404 from the LiteSpeed server (verified across
        // multiple URLs, methods, headers, networks). Filter them out so
        // the user only sees working sources (typically the Default entry
        // which routes through kokoflix.lol → playmogo.com).
        if (href.contains("kakaflix.lol", ignoreCase = true)) return true
        return false
    }

    suspend fun getRating(votes: Element): Double {
        val voteplus = votes
            .selectFirst("span.ratingtypeplusminus")
            ?.text()
            ?.toIntOrNull() ?: 0

        val votenum = votes
            .select("span[id]")
            .last()
            ?.text()
            ?.toIntOrNull() ?: 0

        val rating = if (votenum >= voteplus && votenum > 0) {
            round((votenum - (votenum - voteplus) / 2.0) / votenum * 100) / 10
        } else 0.0

        return rating
    }

    // ── Home ─────────────────────────────────────────────────────────────

    override suspend fun getHome(): List<Category> = coroutineScope {
        initializeService()
        val document = service.getHome()
        val categories = mutableListOf<Category>()
        val allItems = mutableListOf<AppAdapter.Item>()

        // 2026-05-04 : catégories bonus chargées en parallèle (timeout 5s par
        // catégorie). Échec silencieux pour ne pas bloquer le home.
        // Genres films + plateformes séries pour donner plus de découverte.
        val bonusCategories = listOf(
            "Films Action" to "/films/actions/",
            "Films Comédie" to "/films/comedies/",
            "Films Drame" to "/films/drames/",
            "Films Horreur" to "/films/epouvante-horreurs/",
            "Séries Netflix" to "/s-tv/netflix-series-/",
            "Séries Disney+" to "/s-tv/series-disney-plus/",
            "Séries Amazon Prime" to "/s-tv/serie-amazon-prime-videos/",
        )
        val bonusDeferred = bonusCategories.map { (name, path) ->
            async {
                try {
                    withTimeoutOrNull(5_000L) {
                        val isSeriesCat = path.startsWith("/s-tv/") || path.startsWith("/serie/")
                        val doc = service.getCategoryPage(path)
                        val items = doc.select("div.short").mapNotNull { item ->
                            val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                            val href = linkEl.attr("href")
                            val id = extractNewsId(href) ?: return@mapNotNull null
                            val title = item.selectFirst("div.short-title")?.text() ?: ""
                            val poster = item.selectFirst("img")?.attr("src") ?: ""
                            if (isSeriesCat) TvShow(id = id, title = title, poster = poster)
                            else Movie(id = id, title = title, poster = poster)
                        }.take(20)
                        if (items.isNotEmpty()) Category(name = name, list = items) else null
                    }
                } catch (e: Exception) {
                    Log.w("FrenchStream", "Bonus category '$name' failed: ${e.message}")
                    null
                }
            }
        }

        // 2026-05: site redesign — sections are now <div class="sect">
        // wrapping a header <div class="sect-t">. The clean title lives in
        // <a class="st-capt"> (e.g. "Nouveautés Films", "Nouveautés Séries");
        // the rest of .sect-t is filled with tab spans ("Par Défault",
        // "Animation", "Comédie"…) which we MUST NOT include in the title.
        // Each card is a div.short with a poster link, no .film class —
        // movie/series detection has to come from URL/title heuristics and
        // the parent section's "kind".
        // 2026-05-04 : on remplace le scrape des sections "Nouveautés Films"
        // et "Nouveautés Séries" qui sont lazy-loaded sur la home FS
        // (seulement 5-6 cards rendues côté serveur). À la place, fetch direct
        // /films et /series qui ont ~18 cards complètes par page.
        // Garder le scrape pour les autres sections (ex: "Ajouts de la Commu").
        val nouveautesFilmsDeferred = async {
            try {
                withTimeoutOrNull(6_000L) {
                    service.getMovies("films").select("div.short").mapNotNull { item ->
                        val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                        val href = linkEl.attr("href")
                        val id = extractNewsId(href) ?: return@mapNotNull null
                        val title = item.selectFirst("div.short-title")?.text() ?: ""
                        val poster = item.selectFirst("img")?.attr("src") ?: ""
                        Movie(id = id, title = title, poster = poster)
                    }.take(20)
                }
            } catch (e: Exception) {
                Log.w("FrenchStream", "Nouveautés Films failed: ${e.message}")
                null
            }
        }
        val nouveautesSeriesDeferred = async {
            try {
                withTimeoutOrNull(6_000L) {
                    service.getTvShows("series").select("div.short").mapNotNull { item ->
                        val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                        val href = linkEl.attr("href")
                        val id = extractNewsId(href) ?: return@mapNotNull null
                        val title = item.selectFirst("div.short-title")?.text() ?: ""
                        val poster = item.selectFirst("img")?.attr("src") ?: ""
                        TvShow(id = id, title = title, poster = poster)
                    }.take(20)
                }
            } catch (e: Exception) {
                Log.w("FrenchStream", "Nouveautés Séries failed: ${e.message}")
                null
            }
        }

        document.select("div.sect").forEach { section ->
            val capt = section.selectFirst(".sect-t a.st-capt")
            val sectionTitle = capt?.ownText()?.trim()
                ?: section.selectFirst(".sect-t .st-capt")?.ownText()?.trim()
                ?: return@forEach
            val sectionHref = capt?.attr("href") ?: ""

            // Skip "Nouveautés Films" et "Nouveautés Séries" — on les remplace
            // par fetch direct des catalogs (plus de cards car pas lazy-loaded).
            val isNouveautesFilms = sectionHref == "/films/" ||
                (sectionTitle.contains("Nouveau", ignoreCase = true) && sectionTitle.contains("Film", ignoreCase = true))
            val isNouveautesSeries = sectionHref == "/s-tv/" || sectionHref == "/serie/" ||
                (sectionTitle.contains("Nouveau", ignoreCase = true) && sectionTitle.contains("Séri", ignoreCase = true))
            if (isNouveautesFilms || isNouveautesSeries) return@forEach

            // A section is "series-only" when its capt link points to /s-tv/
            // or /serie/. Sections like /films/ or /film-commu/ are movies.
            val sectionIsSeries = sectionHref.contains("/s-tv/")
                || sectionHref.contains("/serie/")
                || sectionTitle.contains("Série", ignoreCase = true)

            val items = section.select("div.short").mapNotNull { item ->
                val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                val href = linkEl.attr("href")
                val id = extractNewsId(href) ?: return@mapNotNull null
                val title = item.selectFirst("div.short-title")?.text() ?: ""
                val poster = item.selectFirst("img")?.attr("src") ?: ""
                // Series detection: parent section first (most reliable),
                // then per-card URL/title fallbacks (in case the section
                // mixes types — e.g. "Ajouts de la Commu").
                val isSeries = sectionIsSeries
                    || title.contains("Saison", ignoreCase = true)
                    || href.contains("/s-tv/") || href.contains("/serie/")
                    || id.contains("-saison-")
                if (isSeries)
                    TvShow(id = id, title = title, poster = poster)
                else
                    Movie(id = id, title = title, poster = poster)
            }
            if (items.isNotEmpty()) {
                categories.add(Category(name = sectionTitle, list = items))
                allItems.addAll(items)
            }
        }

        // Insert "Nouveautés Films" + "Nouveautés Séries" en tête (avant "Ajouts de la Commu")
        val nouveautesFilms = nouveautesFilmsDeferred.await()
        val nouveautesSeries = nouveautesSeriesDeferred.await()
        if (!nouveautesSeries.isNullOrEmpty()) {
            categories.add(0, Category(name = "Nouveautés Séries", list = nouveautesSeries))
            allItems.addAll(0, nouveautesSeries)
        }
        if (!nouveautesFilms.isNullOrEmpty()) {
            categories.add(0, Category(name = "Nouveautés Films", list = nouveautesFilms))
            allItems.addAll(0, nouveautesFilms)
        }

        // Featured carousel from the first items across all sections.
        // The site only has the small portrait poster (no wide backdrop),
        // so when the carousel renders fullscreen the upscaled portrait
        // looks ugly and eats RAM. Pull a proper landscape backdrop from
        // TMDb when enabled — same pattern as WiflixProvider does.
        if (allItems.isNotEmpty()) {
            val featured = allItems.take(15).map { item ->
                when (item) {
                    is Movie -> Movie(id = item.id, title = item.title,
                        poster = item.poster, banner = item.poster)
                    is TvShow -> TvShow(id = item.id, title = item.title,
                        poster = item.poster, banner = item.poster)
                    else -> item
                }
            }
            // Parallel TMDb lookup for backdrops (capped to 15 items so we
            // never block home loading on slow TMDb responses; failures are
            // silent — falls back to portrait poster).
            if (UserPreferences.enableTmdb) {
                runCatching {
                    coroutineScope {
                        featured.map { item ->
                            async {
                                runCatching {
                                    val title = when (item) {
                                        is Movie -> item.title
                                        is TvShow -> item.title.substringBefore(" - ")
                                        else -> return@async
                                    }
                                    // 2026-05-05 : normalise (apostrophe typo, annotations VF/saison) avant TMDB
                                    val normalized = com.streamflixreborn.streamflix.utils.TitleNormalizer.cleanForTmdbSearch(title)
                                    val results = TMDb3.Search.multi(normalized.ifBlank { title }, language = "fr-FR")
                                    val tmdbBanner = results.results
                                        .firstNotNullOfOrNull { r ->
                                            when (r) {
                                                is TMDb3.Movie -> r.backdropPath?.original
                                                is TMDb3.Tv -> r.backdropPath?.original
                                                else -> null
                                            }
                                        }
                                    if (tmdbBanner != null) {
                                        when (item) {
                                            is Movie -> item.banner = tmdbBanner
                                            is TvShow -> item.banner = tmdbBanner
                                        }
                                    }
                                }
                            }
                        }.forEach { it.await() }
                    }
                }
            }
            categories.add(0, Category(name = Category.FEATURED, list = featured))
        }

        // 2026-05-04 : on rajoute les catégories bonus chargées en parallèle.
        // Elles arrivent APRÈS les sections natives de la home pour ne pas
        // pousser les "Nouveautés" en bas. Items aussi versés dans allItems
        // au cas où la home n'avait pas de sections (carousel fallback).
        bonusDeferred.forEach { deferred ->
            val bonus = deferred.await() ?: return@forEach
            categories.add(bonus)
            allItems.addAll(bonus.list)
        }

        return@coroutineScope categories
    }

    // ── Search ───────────────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        initializeService()
        if (query.isEmpty()) {
            val document = service.getHome()
            val genres = document.selectFirst("div.menu-section")?.select(">a")?.map {
                Genre(
                    id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                    name = it.text(),
                )
            }?.toMutableList() ?: mutableListOf()
            if (genres.none { (it as? Genre)?.id == "k-drama" }) {
                genres.add(Genre(id = "k-drama", name = "K-Drama"))
            }
            return genres
        }
        // 2026-05-04 : on utilise searchGet (GET /?do=search) au lieu de l'AJAX
        // POST /engine/ajax/search.php. L'AJAX retourne `<div class='search-item'
        // onclick="location.href=...">` (sans <a>), notre selecteur "a.short-poster,a"
        // ne trouvait rien -> recherche app cassée. Le GET retourne les <div.short>
        // standards avec <a class="short-poster" href="..." alt="..."> qu'on parse
        // déjà correctement pour le multi-saisons.
        val document = service.searchGet(query = query)
        return document.select("div.short").mapNotNull { item ->
            val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            val id = extractNewsId(href)
            if (id.isNullOrBlank()) return@mapNotNull null
            // Préfère le short-title (court, propre) à l'alt (verbeux "Regarder X").
            val title = (item.selectFirst("div.short-title")?.text()
                ?: linkEl.attr("alt")
                    .removePrefix("Regarder ")
                    .removeSuffix(" en streaming complet")
                    .removeSuffix(" en streaming")
                    .trim())
                .replace("\\'", "'")
            // 2026-05-04 : search FS lazy-load les jaquettes via data-src
            val poster = extractImgUrl(item) ?: ""
            val isSeries = title.contains("Saison", ignoreCase = true)
                || href.contains("/s-tv/") || href.contains("/serie/")
                || id.contains("-saison-")
            if (isSeries)
                TvShow(id = id, title = title, poster = poster)
            else
                Movie(id = id, title = title, poster = poster)
        }
    }

    // ── Catalog (Movies / TV Shows) ──────────────────────────────────────

    override suspend fun getMovies(page: Int): List<Movie> {
        // 2026-05-04 : pagination réactivée. Avant on plafonnait à page 1
        // (~18 films) car le fallback /home n'a pas de pagination, mais le
        // endpoint /films?page=N marche bien côté serveur (testé : ~18 films
        // par page, structure identique). User signalait "que 3 rangées de 7".
        initializeService()
        fun looksLikeSeries(href: String, title: String, id: String): Boolean {
            return href.contains("/serie/")
                || href.contains("/s-tv/")
                || id.contains("-saison-")
                || title.contains("Saison", ignoreCase = true)
                || title.matches(Regex(".*\\bS\\d{1,2}\\b.*", RegexOption.IGNORE_CASE))
        }
        // /films endpoint may return MySQL errors on the redesigned site.
        // Try it first, fall back to home page movies.
        return try {
            val url = if (page <= 1) "films" else "films/page/$page"
            val document = service.getMovies(url)
            val items = document.select("div.short").mapNotNull { item ->
                val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                val href = linkEl.attr("href")
                val id = extractNewsId(href) ?: return@mapNotNull null
                val title = item.selectFirst("div.short-title")?.text() ?: ""
                // Defensive: drop series that occasionally leak into /films/ (e.g.
                // a series titled "X - Saison 1" can be mis-tagged on the site).
                if (looksLikeSeries(href, title, id)) return@mapNotNull null
                Movie(id = id, title = title, poster = item.selectFirst("img")?.attr("src"))
            }
            items.ifEmpty { throw Exception("empty") }
        } catch (_: Exception) {
            // Fallback: films from home page (div.short.film, then heuristic for redesigned site)
            try {
                val doc = service.getHome()
                val list = doc.select("div.short.film").mapNotNull { item ->
                    val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                    val id = extractNewsId(linkEl.attr("href")) ?: return@mapNotNull null
                    Movie(
                        id = id,
                        title = item.selectFirst("div.short-title")?.text() ?: "",
                        poster = item.selectFirst("img")?.attr("src"),
                    )
                }
                if (list.isNotEmpty()) list else doc.select("div.short").mapNotNull { item ->
                    val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                    val href = linkEl.attr("href")
                    val id = extractNewsId(href) ?: return@mapNotNull null
                    val title = item.selectFirst("div.short-title")?.text() ?: ""
                    if (looksLikeSeries(href, title, id)) return@mapNotNull null
                    Movie(id = id, title = title,
                        poster = item.selectFirst("img")?.attr("src"))
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        // 2026-05-04 : pagination réactivée (cf getMovies pour le détail).
        initializeService()
        // Helper: looks like an actual TV series (not a film mis-classified).
        // The redesigned site doesn't use .film class anymore, so we have to
        // detect series by URL/title heuristics — otherwise films leak into
        // the "Séries Récentes" carousel and the type badge ends up flipping.
        fun looksLikeSeries(href: String, title: String, id: String): Boolean {
            return href.contains("/serie/")
                || href.contains("/s-tv/")
                || id.contains("-saison-")
                || title.contains("Saison", ignoreCase = true)
                || title.matches(Regex(".*\\bS\\d{1,2}\\b.*", RegexOption.IGNORE_CASE))
        }
        return try {
            val url = if (page <= 1) "series" else "series/page/$page"
            val document = service.getTvShows(url)
            // 2026-05-04 : on filtre les films qui s'invitent dans /series
            // (vu : "Sarah's Oil", "Le Diable s'habille en Prada 2", "F1 2026").
            // On utilise l'alt du <a> (plus fiable, complet) puis fallback short-title.
            // Si "Saison N" / "Season N" / "S1" / "-saison-" présent → c'est une série.
            val items = document.select("div.short").mapNotNull { item ->
                val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                val href = linkEl.attr("href")
                val id = extractNewsId(href) ?: return@mapNotNull null
                val alt = linkEl.attr("alt").orEmpty()
                val shortTitle = item.selectFirst("div.short-title")?.text() ?: ""
                val titleForCheck = if (alt.isNotBlank()) alt else shortTitle
                if (!looksLikeSeries(href, titleForCheck, id)) return@mapNotNull null
                val cleanTitle = (alt.takeIf { it.isNotBlank() } ?: shortTitle)
                TvShow(id = id, title = cleanTitle,
                    poster = item.selectFirst("img")?.attr("src"))
            }
            items.ifEmpty { throw Exception("empty") }
        } catch (_: Exception) {
            // Fallback: home page mixes types — must filter to only keep series.
            try {
                val doc = service.getHome()
                doc.select("div.short").mapNotNull { item ->
                    val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                    val href = linkEl.attr("href")
                    val id = extractNewsId(href) ?: return@mapNotNull null
                    val title = item.selectFirst("div.short-title")?.text() ?: ""
                    if (!looksLikeSeries(href, title, id)) return@mapNotNull null
                    TvShow(id = id, title = title,
                        poster = item.selectFirst("img")?.attr("src"))
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    // ── Movie detail ─────────────────────────────────────────────────────

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        initializeService()
        val document = fetchDetailPage(id)
        val title = document.selectFirst("h1#s-title")?.ownText()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val overview = document.selectFirst("div#s-desc")?.also {
            it.selectFirst("p.desc-text")?.remove()
        }?.text()?.trim()
            ?: document.selectFirst("div.fdesc p, p.fdesc-text")?.text()?.trim().orEmpty()
        val poster = document.selectFirst(".fposter img, img.dvd-thumbnail")?.attr("src")
        val releaseYear = document.selectFirst("span.release_date")?.text()
            ?.substringAfter("-")?.trim()
        val runtime = document.selectFirst("span.runtime")?.text()?.let { rt ->
            val hours = rt.substringBefore("h").trim().toIntOrNull() ?: 0
            val minutes = rt.substringAfter("h").trim().toIntOrNull() ?: 0
            (hours * 60 + minutes).takeIf { it > 0 }
        }
        val genres = document.select("span.genres a").map {
            Genre(
                id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                name = it.text().trim(),
            )
        }
        val directors = document.select("ul#s-list li")
            .find { it.selectFirst("span")?.text()?.contains("alisateur") == true }
            ?.select("a")?.mapIndexedNotNull { index, el ->
                People(id = "director$index", name = el.text())
            } ?: emptyList()
        val votes = document.selectFirst("div.fr-votes")
        val rating = if (votes != null) getRating(votes) else null

        Movie(
            id = id,
            title = title,
            overview = overview,
            released = releaseYear,
            runtime = runtime,
            poster = poster,
            banner = poster,
            genres = genres.ifEmpty { listOf(Genre(id = "unknown", name = "")) },
            directors = directors,
            rating = rating,
        )
    }

    // ── TV Show detail ───────────────────────────────────────────────────

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        initializeService()
        val document = fetchDetailPage(id)
        val title = document.selectFirst("h1#s-title")?.ownText()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val cleanTitle = title.substringBeforeLast("- Saison").trim().ifBlank { title }
        val overview = document.selectFirst("div#s-desc")?.also {
            it.selectFirst("p.desc-text")?.remove()
        }?.text()?.trim()
            ?: document.selectFirst("div.fdesc p, p.fdesc-text")?.text()?.trim().orEmpty()
        val poster = document.selectFirst(".fposter img, img.dvd-thumbnail")?.attr("src")
        val releaseYear = document.selectFirst("span.release_date")?.text()
            ?.substringBefore("-")?.trim()
        val genres = document.select("span.genres a").map {
            Genre(
                id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                name = it.text().trim(),
            )
        }
        val seasonNumber = title.substringAfter("Saison ", "").trim()
            .substringBefore(" ").toIntOrNull() ?: 1
        val votes = document.selectFirst("div.fr-votes")
        val rating = if (votes != null) getRating(votes) else null

        // 2026-05-04 : récupère TOUTES les saisons via search GET.
        // Sur FrenchStream chaque saison est un newsid distinct (S1=170941, S6=170938...).
        // L'AJAX search retourne max ~5 items avec format différent — donc on
        // utilise le search GET (`?do=search&subaction=search&story=...`) qui
        // retourne ~10+ résultats avec les `a.short-poster` standard et
        // alt="Title - Saison N".
        Log.d("FrenchStream", "getTvShow id=$id title='$title' cleanTitle='$cleanTitle' seasonNumber=$seasonNumber")
        val seasons = try {
            // 2026-05-04 : itère sur les pages de search jusqu'à ce qu'il
            // n'y ait plus de NOUVEAUX résultats (ou cap 10 pages pour
            // sécurité — couvre les séries jusqu'à ~20 saisons).
            // Critère d'arrêt :
            //  - page sans aucun div.short (fin du catalog)
            //  - page sans nouvelle URL inédite (= duplicates depuis la page précédente)
            val seasonRegex = Regex("""\bSaison\s+(\d+)\b""", RegexOption.IGNORE_CASE)
            val allShorts = mutableListOf<org.jsoup.nodes.Element>()
            val seenHrefs = HashSet<String>()
            for (searchPage in 1..10) {
                val searchDoc = service.searchGet(query = cleanTitle, searchStart = searchPage)
                val pageShorts = searchDoc.select("div.short")
                Log.d("FrenchStream", "searchGet '$cleanTitle' page=$searchPage returned ${pageShorts.size} div.short")
                if (pageShorts.isEmpty()) break
                var newOnPage = 0
                pageShorts.forEach { item ->
                    val href = item.selectFirst("a.short-poster")?.attr("href").orEmpty()
                    if (href.isNotBlank() && seenHrefs.add(href)) {
                        allShorts.add(item)
                        newOnPage++
                    }
                }
                if (newOnPage == 0) break // page entière déjà vue
            }
            val foundSeasons = allShorts.mapNotNull { item ->
                val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                val href = linkEl.attr("href")
                val sId = extractNewsId(href) ?: return@mapNotNull null
                // 2026-05-04 : sur les RÉSULTATS de recherche (vs catalog),
                // l'alt est "Regarder X - Saison N en streaming complet" et
                // pas juste "X - Saison N". On nettoie les deux côtés AVANT
                // d'extraire le titre de base. Le short-title (ex: "FROM - Saison 4")
                // reste plus simple à utiliser donc on le préfère quand dispo.
                val rawAlt = linkEl.attr("alt").orEmpty()
                val shortTitleEl = item.selectFirst("div.short-title")?.text().orEmpty()
                val sTitle = if (shortTitleEl.isNotBlank()) shortTitleEl
                    else rawAlt
                        .removePrefix("Regarder ")
                        .removeSuffix(" en streaming complet")
                        .removeSuffix(" en streaming")
                        .trim()
                val sCleanTitle = sTitle.substringBeforeLast("- Saison").trim()
                if (!sCleanTitle.equals(cleanTitle, ignoreCase = true)) return@mapNotNull null
                val sNumber = seasonRegex.find(sTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                Log.d("FrenchStream", "  matched season $sNumber id=$sId title='$sTitle'")
                // 2026-05-04 : les pages search FS lazy-load les jaquettes via
                // <img class="lazy" data-src="..."> — extractImgUrl gère ça.
                val sPoster = extractImgUrl(item)
                Season(
                    id = sId,
                    number = sNumber,
                    title = "Saison $sNumber",
                    poster = sPoster ?: poster,
                )
            }
                .distinctBy { it.number }
                .sortedBy { it.number }
                .take(30)
            Log.d("FrenchStream", "FS seasons natives: ${foundSeasons.size} -> ${foundSeasons.map { it.number }}")
            // 2026-05-05 v3 : on n'ajoute PAS de saisons fantômes TMDB —
            // l'utilisateur préfère voir moins de saisons mais qui ont
            // toutes des sources, plutôt que d'avoir des saisons cliquables
            // qui ne donnent rien. Pour les saisons que FS n'a pas, le user
            // peut switcher de provider (Movix, Moviebox, Papa) qui auront
            // potentiellement le show complet.
            // Garantit que la saison courante est dans la liste (si search incomplet)
            val withCurrent = if (foundSeasons.none { it.number == seasonNumber && it.id == id }) {
                (foundSeasons + Season(
                    id = id, number = seasonNumber,
                    title = "Saison $seasonNumber", poster = poster,
                )).distinctBy { it.number }.sortedBy { it.number }
            } else foundSeasons

            // Enrichit juste les posters depuis TMDB (sans ajouter de saisons inexistantes)
            val tmdbShow = runCatching {
                val cleanQuery = com.streamflixreborn.streamflix.utils.TitleNormalizer
                    .cleanForTmdbSearch(cleanTitle).ifBlank { cleanTitle }
                com.streamflixreborn.streamflix.utils.TmdbUtils.getTvShow(
                    title = cleanQuery,
                    year = releaseYear?.toIntOrNull(),
                    language = "fr-FR",
                )
            }.getOrNull()
            if (tmdbShow != null) {
                val tmdbSeasonsByNumber = tmdbShow.seasons.associateBy { it.number }
                withCurrent.map { fsSeason ->
                    val tmdbS = tmdbSeasonsByNumber[fsSeason.number]
                    if (tmdbS?.poster != null) fsSeason.copy(poster = tmdbS.poster) else fsSeason
                }
            } else withCurrent
        } catch (e: Exception) {
            Log.w("FrenchStream", "Multi-season search failed for '$cleanTitle': ${e.message}")
            listOf(Season(id = id, number = seasonNumber, title = "Saison $seasonNumber", poster = poster))
        }

        TvShow(
            id = id,
            title = cleanTitle,
            overview = overview,
            released = releaseYear,
            poster = poster,
            banner = poster,
            genres = genres,
            seasons = seasons,
            rating = rating,
        )
    }

    // ── Episodes ─────────────────────────────────────────────────────────

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        initializeService()
        // 2026-05-04 : nouvelle voie AJAX en priorité (la div#episodeN
        // n'existe plus dans le HTML, populée en JS via /engine/ajax/sx.php).
        // Fallback HTML legacy si l'API AJAX echoue (très vieilles entrées).
        try {
            val viaAjax = fetchEpisodesFromAjax(seasonId)
            if (viaAjax.isNotEmpty()) return viaAjax
        } catch (e: Exception) {
            Log.w("FrenchStream", "AJAX episodes failed for $seasonId, falling back to HTML: ${e.message}")
        }
        return try {
            val document = fetchDetailPage(seasonId)
            parseEpisodesFromPage(document, seasonId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 2026-05-04 : récupère la liste des numéros d'épisodes via l'endpoint
     *  AJAX `/engine/ajax/sx.php?p={newsId}` qui retourne :
     *    {"vf":{"1":{...players}, "2":{...}}, "vostfr":{...}, "vo":{...}}
     *  On agrège les clés numériques de toutes les versions (un épisode peut
     *  exister en VF mais pas en VOSTFR par exemple). */
    private suspend fun fetchEpisodesFromAjax(seasonId: String): List<Episode> {
        if (!seasonId.all { it.isDigit() }) return emptyList() // AJAX n'accepte que newsid numériques
        val url = buildAjaxUrl(serieAjaxPath.get(), "p=$seasonId")
        val body = service.getAjaxJsonByUrl(url).string()
        val json = parseJsonOrNull(body, "fetchEpisodesFromAjax($seasonId)") ?: return emptyList()
        // Versions VF/VOSTFR/VO ; au moins l'une doit exister sinon shape inconnue
        if (!json.has("vf") && !json.has("vostfr") && !json.has("vo")) {
            logUnexpectedShape("fetchEpisodesFromAjax", json, "vf|vostfr|vo")
            return emptyList()
        }
        val numbers = sortedSetOf<Int>()
        listOf("vf", "vostfr", "vo").forEach { version ->
            val obj = json.optJSONObject(version) ?: return@forEach
            obj.keys().forEach { key ->
                key.toIntOrNull()?.let { numbers.add(it) }
            }
        }
        // 2026-05-04 : sx.php renvoie aussi un bloc {info:{"1":{title,synopsis,poster}, ...}}
        // qu'on a longtemps ignoré. On l'utilise maintenant pour enrichir
        // chaque Episode avec son vrai titre, synopsis et la still TMDb.
        val infoBlock = json.optJSONObject("info")
        return numbers.map { num ->
            val ep = infoBlock?.optJSONObject(num.toString())
            val epTitle = ep?.optString("title")?.takeIf { it.isNotBlank() } ?: "Épisode $num"
            val epPoster = ep?.optString("poster")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            val epSynopsis = ep?.optString("synopsis")?.takeIf { it.isNotBlank() }
            Episode(
                id = "$seasonId/$num",
                number = num,
                title = epTitle,
                poster = epPoster,
                overview = epSynopsis,
            )
        }
    }

    /** 2026-05-04 : récupère les Video.Server d'un FILM via l'API AJAX
     *  `/engine/ajax/film_api.php?id={newsId}`. Le JSON retourne :
     *    {"players":{"vidzy":{"default":"url","vostfr":"url","vfq":"url"},
     *                "premium":{...}, "uqload":{...}, "dood":{...}, "voe":{...}, "filmoon":{...}}, ...}
     *  On expose chaque player × chaque version, en dédoublonnant les URLs
     *  identiques (default == vostfr arrive souvent quand le film n'a qu'une
     *  version dispo). */
    private suspend fun fetchMoviePlayersFromAjax(filmId: String): List<Video.Server> {
        if (!filmId.all { it.isDigit() }) return emptyList()
        val url = buildAjaxUrl(filmAjaxPath.get(), "id=$filmId")
        val body = service.getAjaxJsonByUrl(url).string()
        val json = parseJsonOrNull(body, "fetchMoviePlayersFromAjax($filmId)") ?: return emptyList()
        val players = json.optJSONObject("players") ?: run {
            logUnexpectedShape("fetchMoviePlayersFromAjax", json, "players")
            return emptyList()
        }

        val out = mutableListOf<Video.Server>()
        val seen = HashSet<String>()
        // Order: VF default first, then VFQ (Quebec), then VOSTFR (sub)
        val versionLabels = listOf("default" to "VF", "vfq" to "VFQ", "vostfr" to "VOSTFR")
        // Order players by reliability (most stable first)
        val playerOrder = listOf("vidzy", "voe", "filmoon", "dood", "uqload", "premium")
        val orderedPlayers = playerOrder.filter { players.has(it) } +
            players.keys().asSequence().filter { it !in playerOrder }

        orderedPlayers.forEach { playerName ->
            val playerObj = players.optJSONObject(playerName) ?: return@forEach
            versionLabels.forEach { (versionKey, versionLabel) ->
                val url = playerObj.optString(versionKey).trim()
                if (url.isBlank() || !url.startsWith("http")) return@forEach
                if (url.contains("vid=&", ignoreCase = true)) return@forEach
                if (ignoreSource(playerName, url)) return@forEach
                if (!seen.add(url)) return@forEach
                val displayName = "${playerName.replaceFirstChar { it.uppercase() }} ($versionLabel)"
                out.add(Video.Server(
                    id = "fs_film_${playerName}_$versionKey",
                    name = displayName,
                    src = url,
                ))
            }
        }
        return out
    }

    /** 2026-05-04 : récupère les Video.Server pour un épisode donné via l'API
     *  AJAX. Itère sur les versions VF/VOSTFR/VO et expose chaque player avec
     *  un label "Player (Version)". */
    private suspend fun fetchPlayersFromAjax(seasonId: String, episodeNumber: Int): List<Video.Server> {
        if (!seasonId.all { it.isDigit() }) return emptyList()
        val url = buildAjaxUrl(serieAjaxPath.get(), "p=$seasonId")
        val body = service.getAjaxJsonByUrl(url).string()
        val json = parseJsonOrNull(body, "fetchPlayersFromAjax($seasonId/E$episodeNumber)") ?: return emptyList()
        if (!json.has("vf") && !json.has("vostfr") && !json.has("vo")) {
            logUnexpectedShape("fetchPlayersFromAjax", json, "vf|vostfr|vo")
            return emptyList()
        }

        val out = mutableListOf<Video.Server>()
        val seen = HashSet<String>()
        listOf("vf" to "VF", "vostfr" to "VOSTFR", "vo" to "VO").forEach { (key, label) ->
            val versionObj = json.optJSONObject(key) ?: return@forEach
            val episodeObj = versionObj.optJSONObject(episodeNumber.toString()) ?: return@forEach
            episodeObj.keys().asSequence().forEach forEachPlayer@{ playerName ->
                val url = episodeObj.optString(playerName).trim()
                if (url.isBlank() || !url.startsWith("http")) return@forEachPlayer
                if (url.contains("vid=&", ignoreCase = true)) return@forEachPlayer
                if (ignoreSource(playerName, url)) return@forEachPlayer
                if (!seen.add(url)) return@forEachPlayer
                val displayName = "${playerName.replaceFirstChar { it.uppercase() }} ($label)"
                out.add(Video.Server(
                    id = "fs_ajax_${key}_${episodeNumber}_${playerName}",
                    name = displayName,
                    src = url,
                ))
            }
        }
        return out
    }

    /** Parse episodes from div#episodeN.fullsfeature blocks.
     *  Each block is delimited by <!-- episode N --> comments in the HTML
     *  and contains a div.selink with the episode title and embed links.
     *
     *  If the page has no episode blocks but DOES have movie players, we
     *  synthesize a single "episode 1" so the user can still launch playback
     *  — this happens when FrenchStream lists a movie on its /series catalog
     *  (e.g. "Le Mage du Kremlin") and the item gets typed as TvShow. */
    private fun parseEpisodesFromPage(document: Document, seasonId: String): List<Episode> {
        val out = mutableListOf<Episode>()
        document.select("div.fullsfeature[id^=episode]").forEach { epDiv ->
            val epId = epDiv.id() // e.g. "episode3"
            val number = epId.removePrefix("episode").toIntOrNull() ?: return@forEach
            val titleText = epDiv.selectFirst("div.selink span")?.text()?.trim()
            val title = if (!titleText.isNullOrBlank()) titleText else "Épisode $number"
            out.add(
                Episode(
                    id = "$seasonId/$number",
                    number = number,
                    title = title,
                )
            )
        }
        if (out.isEmpty()) {
            val hasMoviePlayers = document.selectFirst("button.player-option") != null
            if (hasMoviePlayers) {
                Log.d("FrenchStream",
                    "No episode blocks but page has movie players — " +
                    "synthesizing fake episode 1 for $seasonId")
                val movieTitle = document.selectFirst("h1#s-title")?.ownText()?.trim()
                    ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
                out.add(
                    Episode(
                        id = "$seasonId/1",
                        number = 1,
                        title = movieTitle.ifBlank { "Film" },
                    )
                )
            }
        }
        return out.sortedBy { it.number }
    }

    // ── Servers / Players ────────────────────────────────────────────────

    /** Parse players from the detail page HTML.
     *
     *  **Movies**: button.player-option elements carry data-player (name) and
     *  data-url-default (embed URL). Each button may contain children
     *  div.version-option with data-version (language) and data-url.
     *
     *  **Series episodes**: the episode block div#episodeN.fullsfeature contains
     *  a href="https://embed-url" links — one per available server. */
    private fun parsePlayersFromPage(
        document: Document,
        forEpisodeNumber: Int?
    ): List<Video.Server> {
        val out = mutableListOf<Video.Server>()
        val seen = HashSet<String>()

        if (forEpisodeNumber != null) {
            // ── Series episode: extract embed links from the episode block ──
            val epDiv = document.selectFirst("div#episode${forEpisodeNumber}.fullsfeature")
            if (epDiv == null) {
                // The /series catalog on FrenchStream sometimes lists movies
                // (e.g. "Le Mage du Kremlin", "Aventures Croisées") which then
                // get classified as TvShow upstream. The detail page has NO
                // episode blocks — but it has the movie button.player-option
                // structure. Fall through to the movie-parsing branch so the
                // user still gets the players instead of an empty list.
                Log.d("FrenchStream",
                    "Episode #$forEpisodeNumber not found in detail page — " +
                    "falling back to movie player parsing")
                // (don't return; let the movie branch run below)
            } else {
                epDiv.select("a[href]").forEachIndexed { i, link ->
                    val href = link.attr("href").trim()
                    if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript")) return@forEachIndexed
                    if (!href.startsWith("http")) return@forEachIndexed
                    if (!seen.add(href)) return@forEachIndexed
                    if (ignoreSource("", href)) return@forEachIndexed
                    val serviceName = Extractor.identifyServiceName(href)
                        ?: href.substringAfter("//").substringBefore("/")
                            .substringBeforeLast(".").substringAfterLast(".")
                    val displayName = serviceName.replaceFirstChar { it.uppercase() }
                    out.add(Video.Server(
                        id = "fs_ep${forEpisodeNumber}_$i",
                        name = displayName,
                        src = href,
                    ))
                }
                return out
            }
        }
        // Movie OR fallback when episode block was missing (movie wrongly
        // classified as series upstream). Both paths land here.
        run {
            // ── Movie: parse the JS `playerUrls = {...}` dictionary ──
            // The HTML <button class="player-option"> elements only carry
            // data-url-default for legacy players (ViDZY, Dood). Newer
            // players (Uqload, Voe, Filmoon, Premium, Netu) have empty
            // version-options and rely entirely on the JS dictionary.
            // Parsing the dict gives us every player + every version URL.
            val htmlText = document.toString()
            val dictMatch = Regex(
                """playerUrls\s*=\s*(\{[\s\S]*?\});""",
                RegexOption.MULTILINE
            ).find(htmlText)
            val playerOrder = mutableListOf<String>()
            document.select("button.player-option").forEach { button ->
                val name = button.attr("data-player").trim()
                if (name.isNotBlank()) playerOrder.add(name)
            }
            if (dictMatch != null) {
                try {
                    val json = org.json.JSONObject(dictMatch.groupValues[1])
                    val keys = json.keys().asSequence().toList()
                    // Iterate in HTML button order if available, else dict order
                    val orderedKeys = if (playerOrder.isNotEmpty())
                        playerOrder.filter { json.has(it) } +
                            keys.filter { it !in playerOrder }
                    else keys
                    orderedKeys.forEachIndexed { i, playerName ->
                        val versions = json.optJSONObject(playerName) ?: return@forEachIndexed
                        // Order versions: Default → VFF → VFQ → VOSTFR
                        val versionOrder = listOf("Default", "VFF", "VFQ", "VOSTFR")
                        val allKeys = versions.keys().asSequence().toList()
                        val orderedVersions = versionOrder.filter { allKeys.contains(it) } +
                            allKeys.filter { it !in versionOrder }
                        // Track URLs already added per player to dedupe Default
                        // when it equals one of the versions.
                        val seenForPlayer = HashSet<String>()
                        orderedVersions.forEach { versionName ->
                            val url = versions.optString(versionName, "").trim()
                            if (url.isBlank()) return@forEach
                            // Skip the Multiup placeholder Netu URL (vid= empty)
                            if (url.contains("vid=&", ignoreCase = true)) return@forEach
                            // Dedupe within this player (Default often dupes a version)
                            if (!seenForPlayer.add(url)) return@forEach
                            // Global dedupe across players too
                            if (!seen.add(url)) return@forEach
                            if (ignoreSource(playerName, url)) return@forEach
                            val displayName = if (versionName == "Default")
                                playerName else "$playerName ($versionName)"
                            out.add(Video.Server(
                                id = "fs_player_${i}_${versionName}",
                                name = displayName,
                                src = url,
                            ))
                        }
                    }
                } catch (e: Exception) {
                    Log.w("FrenchStream", "playerUrls JSON parse failed: ${e.message} — falling back to HTML buttons")
                    // Fall through to HTML parsing below
                }
            }
            // Legacy / fallback: parse button.player-option from HTML.
            // Runs only when JS dict was missing or unparseable.
            if (out.isEmpty()) {
                document.select("button.player-option").forEachIndexed { i, button ->
                    val playerName = button.attr("data-player").trim()
                        .ifBlank { "Player ${i + 1}" }
                    val defaultUrl = button.attr("data-url-default").trim()
                    val versionOptions = button.select("div.version-option")
                    if (versionOptions.isNotEmpty()) {
                        versionOptions.forEach { version ->
                            val versionName = version.attr("data-version").trim()
                            val versionUrl = version.attr("data-url").trim()
                            if (versionUrl.isNotBlank() && seen.add(versionUrl)) {
                                if (ignoreSource(playerName, versionUrl)) return@forEach
                                val displayName = if (versionName.isNotBlank())
                                    "$playerName ($versionName)" else playerName
                                out.add(Video.Server(
                                    id = "fs_player_${i}_${versionName}",
                                    name = displayName,
                                    src = versionUrl,
                                ))
                            }
                        }
                    } else if (defaultUrl.isNotBlank() && seen.add(defaultUrl)) {
                        if (!ignoreSource(playerName, defaultUrl)) {
                            out.add(Video.Server(
                                id = "fs_player_$i",
                                name = playerName,
                                src = defaultUrl,
                            ))
                        }
                    }
                }
            }
        }
        return out
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()

        // 2026-05-04 : cache mémoire de l'agrégat (TTL 5 min) — style Movix.
        val cacheKey = serversCacheKey(id, videoType)
        val now = System.currentTimeMillis()
        serversCache[cacheKey]?.let { cached ->
            if (now < cached.expiresAtMs) {
                Log.d("FrenchStream", "getServers cache HIT for $cacheKey (${cached.servers.size} servers)")
                return cached.servers
            }
            serversCache.remove(cacheKey)
        }

        // 2026-05-04 : strategy chain — chaque stratégie est isolée derrière
        // runEndpoint (timeout 8s + circuit breaker). On essaie en cascade et
        // on log laquelle a marché. Si toutes échouent → emptyList et le
        // PlayerViewModel affiche le message friendly.
        val nativeServers = when (videoType) {
            is Video.Type.Episode -> {
                val parts = id.split("/")
                val tvShowId = parts.getOrElse(0) { id }
                val episodeNum = parts.getOrElse(1) { videoType.number.toString() }
                    .toIntOrNull() ?: videoType.number
                resolveEpisodeServers(tvShowId, episodeNum)
            }
            is Video.Type.Movie -> resolveMovieServers(id)
        }

        // 2026-05-05 : Movix + Moviebox backup. FrenchStream a souvent peu de
        // sources (4-8 hosters) ; Movix en agrège 10-18 et Moviebox a sa
        // version VF directe. On résout le titre via TMDB pour faire la
        // jonction (FS ne stocke pas le tmdbId par show).
        val (tmdbIdResolved, tmdbYear) = runCatching {
            val title = when (videoType) {
                is Video.Type.Movie -> videoType.title
                is Video.Type.Episode -> videoType.tvShow.title
            }
            val cleanTitle = com.streamflixreborn.streamflix.utils.TitleNormalizer
                .cleanForTmdbSearch(title).ifBlank { title }
            val year = when (videoType) {
                is Video.Type.Movie -> videoType.releaseDate.takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull()
                is Video.Type.Episode -> videoType.tvShow.releaseDate?.take(4)?.toIntOrNull()
            }
            val tmdbItem: Any? = when (videoType) {
                is Video.Type.Movie -> com.streamflixreborn.streamflix.utils.TmdbUtils.getMovie(cleanTitle, year, "fr-FR")
                is Video.Type.Episode -> com.streamflixreborn.streamflix.utils.TmdbUtils.getTvShow(cleanTitle, year, "fr-FR")
            }
            val tid = when (tmdbItem) {
                is com.streamflixreborn.streamflix.models.Movie -> tmdbItem.id.toIntOrNull()
                is com.streamflixreborn.streamflix.models.TvShow -> tmdbItem.id.toIntOrNull()
                else -> null
            }
            tid to year
        }.getOrNull() ?: (null to null)

        val movixBackup = if (tmdbIdResolved != null) runCatching {
            val movixVideoType = if (videoType is Video.Type.Episode)
                videoType.copy(tvShow = videoType.tvShow.copy(id = "$tmdbIdResolved"))
            else videoType
            val movixId = when (videoType) {
                is Video.Type.Movie -> "$tmdbIdResolved"
                is Video.Type.Episode -> "$tmdbIdResolved-s${videoType.season.number}e${videoType.number}"
            }
            MovixProvider.getServers(movixId, movixVideoType)
        }.getOrNull().orEmpty() else emptyList()

        val movieboxBackup = if (tmdbIdResolved != null) runCatching {
            MovieboxProvider.getMovieboxSourcesByTmdbId(tmdbIdResolved, videoType)
        }.getOrNull().orEmpty() else emptyList()

        Log.d("FrenchStream", "Servers: native=${nativeServers.size} + movix=${movixBackup.size} + moviebox=${movieboxBackup.size} (tmdbId=$tmdbIdResolved)")
        // Dédup par src URL — Movix peut déjà avoir Moviebox dans son cache
        val seenSrc = mutableSetOf<String>()
        val servers = (nativeServers + movixBackup + movieboxBackup)
            .filter { it.src.isBlank() || seenSrc.add(it.src.lowercase().trim()) }

        // Sort: VF/TrueFrench first, then by service reliability, VOSTFR/VO last
        val sorted = servers.sortedWith(compareBy<Video.Server> { server ->
            val name = server.name.uppercase()
            when {
                name.contains("TRUEFRENCH") || name.contains("VFF") -> 0
                name.contains("VFQ") || (name.contains("FRENCH") && !name.contains("VOSTFR")) -> 1
                name.contains("VF") && !name.contains("VOSTFR") -> 2
                name.contains("VOSTFR") -> 5
                name.contains("VO") -> 6
                else -> 3
            }
        }.thenBy { server ->
            val serviceName = Extractor.identifyServiceName(server.src)?.lowercase() ?: ""
            val sn = server.name.lowercase()
            when {
                serviceName == "vidara" || sn.contains("vidara") -> 1
                serviceName == "vidsonic" || sn.contains("vidsonic") -> 2
                serviceName == "rpmvid" || sn.contains("rpmvid") -> 3
                serviceName == "filemoon" || sn.contains("filemoon") -> 4
                serviceName == "uqload" || sn.contains("uqload") -> 15
                else -> 5
            }
        })

        // Ne cache que les résultats non-vides : un échec passager ne doit
        // pas bloquer un retry 5 min plus tard.
        if (sorted.isNotEmpty()) {
            serversCache[cacheKey] = CachedServers(
                servers = sorted,
                expiresAtMs = System.currentTimeMillis() + SERVERS_CACHE_TTL_MS,
            )
        }
        return sorted
    }

    /** Strategy chain pour un FILM : AJAX `film_api.php` → fallback HTML
     *  `playerUrls = {...}` (vieux films legacy). Quand AJAX échoue on profite
     *  du fallback HTML pour scanner les JS bundles à la recherche du
     *  nouveau path AJAX (auto-découverte). */
    private suspend fun resolveMovieServers(filmId: String): List<Video.Server> {
        // Strategy 1 : AJAX film_api.php (template VFR1, mai 2026)
        val ajax = runEndpoint("film_api") { fetchMoviePlayersFromAjax(filmId) }
        if (ajax.isNotEmpty()) {
            Log.d("FrenchStream", "[strategy=film_api] ${ajax.size} players for $filmId")
            return ajax
        }
        // Strategy 2 : HTML fallback + opportunistic endpoint rediscovery
        val html = runEndpoint("html_movie_buttons") {
            val document = fetchDetailPage(filmId)
            // Tant qu'on a la page sous la main, on scanne ses <script> pour
            // découvrir un éventuel nouveau path AJAX. Cooldown 5min interne.
            try { rediscoverEndpointsFromDocument(document) } catch (_: Exception) {}
            parsePlayersFromPage(document, forEpisodeNumber = null)
        }
        if (html.isNotEmpty()) {
            Log.d("FrenchStream", "[strategy=html_movie_buttons] ${html.size} players for $filmId")
        } else {
            Log.w("FrenchStream", "All strategies failed for movie $filmId")
        }
        return html
    }

    /** Strategy chain pour un ÉPISODE : AJAX `sx.php` → fallback HTML
     *  `<div id=episodeN>` (très vieilles séries). Auto-découverte sur fallback. */
    private suspend fun resolveEpisodeServers(tvShowId: String, episodeNum: Int): List<Video.Server> {
        // Strategy 1 : AJAX sx.php (nouveau template)
        val ajax = runEndpoint("sx_php") { fetchPlayersFromAjax(tvShowId, episodeNum) }
        if (ajax.isNotEmpty()) {
            Log.d("FrenchStream", "[strategy=sx_php] ${ajax.size} players for $tvShowId/E$episodeNum")
            return ajax
        }
        // Strategy 2 : HTML fallback + opportunistic endpoint rediscovery
        val html = runEndpoint("html_episode_block") {
            val document = fetchDetailPage(tvShowId)
            try { rediscoverEndpointsFromDocument(document) } catch (_: Exception) {}
            parsePlayersFromPage(document, forEpisodeNumber = episodeNum)
        }
        if (html.isNotEmpty()) {
            Log.d("FrenchStream", "[strategy=html_episode_block] ${html.size} players for $tvShowId/E$episodeNum")
        } else {
            Log.w("FrenchStream", "All strategies failed for episode $tvShowId/E$episodeNum")
        }
        return html
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val finalUrl = if (server.src.contains("kokoflix.lol", ignoreCase = true)) {
            val response = service.getRedirectLink(server.src)
                .let { response -> response.raw() as okhttp3.Response }
            response.request.url.toString()
        } else {
            server.src
        }
        return Extractor.extract(finalUrl)
    }

    // ── Genre ────────────────────────────────────────────────────────────

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService()
        val document = try {
            service.getGenre(id, page)
        } catch (_: Exception) {
            return Genre(id = id, name = "", shows = emptyList())
        }
        val shows = document.select("div.short").mapNotNull { item ->
            val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            val itemId = extractNewsId(href) ?: return@mapNotNull null
            val title = item.selectFirst("div.short-title")?.text() ?: ""
            val poster = item.selectFirst("img")?.attr("src")
            val isSeries = !item.hasClass("film")
                && (title.contains("Saison", ignoreCase = true)
                || href.contains("/serie/") || href.contains("/s-tv/"))
            if (isSeries)
                TvShow(id = itemId, title = title, poster = poster)
            else
                Movie(id = itemId, title = title, poster = poster)
        }
        return Genre(id = id, name = "", shows = shows)
    }

    // ── People ───────────────────────────────────────────────────────────

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id, "")
    }

    // ── URL management ───────────────────────────────────────────────────

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                val addressService = Service.buildAddressFetcher()
                try {
                    val document = addressService.getHome()
                    val newUrl = document.select("div.container > div.url-card")
                        .selectFirst("a")
                        ?.attr("href")
                        ?.trim()
                    if (!newUrl.isNullOrEmpty()) {
                        val finalUrl = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, finalUrl)
                        UserPreferences.setProviderCache(
                            this,
                            UserPreferences.PROVIDER_LOGO,
                            finalUrl + "favicon-96x96.png"
                        )
                    }
                } catch (_: Exception) {
                    // Fallback to default URL
                }
            }
            service = Service.build(baseUrl)
            serviceInitialized = true
        }
        return baseUrl
    }

    private suspend fun initializeService() {
        initializationMutex.withLock {
            if (serviceInitialized) return
            onChangeUrl()
        }
    }

    // ── Retrofit Service ─────────────────────────────────────────────────

    private interface Service {

        companion object {
            private val client = OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .dns(DnsResolver.doh)
                .followRedirects(false)
                .addInterceptor { chain ->
                    var request = chain.request()
                    var response = chain.proceed(request)
                    var redirects = 0
                    while (response.isRedirect && redirects < 5) {
                        val location = response.header("Location") ?: break
                        val newUrl = request.url.resolve(location) ?: break
                        response.close()
                        request = request.newBuilder().url(newUrl).build()
                        response = chain.proceed(request)
                        redirects++
                    }
                    response
                }
                .build()

            fun buildAddressFetcher(): Service {
                return Retrofit.Builder()
                    .baseUrl(portalUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }

            fun build(baseUrl: String): Service {
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET(".")
        suspend fun getHome(
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        @FormUrlEncoded
        @POST("engine/ajax/search.php")
        suspend fun search(
            @Field("query") query: String,
            @Field("page") page: Int = 1
        ): Document

        // Catalog pages — may return MySQL errors on redesigned site.
        // 2026-05-04 : pagination via path-style /films/page/N (le `?page=N`
        // est ignoré par fs03.lol et retourne toujours la même page 1, ce
        // qui causait des doublons à scroll). Pour la page 1 on utilise
        // l'URL sans suffixe via Url runtime.
        @GET
        suspend fun getMovies(@retrofit2.http.Url url: String): Document

        @GET
        suspend fun getTvShows(@retrofit2.http.Url url: String): Document

        // Detail page by newsid (new URL scheme: /index.php?newsid=XXXXX)
        @GET("index.php")
        suspend fun getItemByNewsId(
            @Query("newsid") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        // 2026-05-04 : récupère une page de catégorie arbitraire (utilisé par
        // getHome pour les catégories bonus genres/plateformes).
        @GET
        suspend fun getCategoryPage(
            @retrofit2.http.Url path: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        // 2026-05-04 : recherche GET (résultats complets, pas l'AJAX limité).
        // Format des hrefs : `<a class="short-poster" href="/index.php?newsid=NNN">`
        // Avec alt="Title - Saison N" → utilisable pour récupérer toutes les
        // saisons d'une série (search "Game of Thrones" → S1, S2, ..., S8).
        // Pagination via `search_start=N` — sans ça on ne voit que les 4 premiers
        // résultats (FROM S3, S4) et on rate les saisons plus anciennes (S1, S2).
        @GET(".")
        suspend fun searchGet(
            @Query("do") doParam: String = "search",
            @Query("subaction") subaction: String = "search",
            @Query("story") query: String,
            @Query("search_start") searchStart: Int = 1,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        // 2026-05-04 : endpoint AJAX qui retourne les players d'un film en JSON.
        // Format : {"players":{"premium":{"default":"...","vostfr":"...","vfq":"..."},
        //          "vidzy":{...}, "uqload":{...}, "dood":{...}, "voe":{...}, "filmoon":{...}},
        //          "meta":{...}}
        // Découvert en sniffant la network XHR sur /index.php?newsid=15126804.
        // Avant on parsait `playerUrls = {...}` du HTML mais cette structure
        // n'apparait QUE sur les vieux films — les nouveaux (template VFR1
        // 2026) chargent les players via cet endpoint AJAX. Marche pour les
        // 2 templates donc on l'utilise en priorité.
        @GET("engine/ajax/film_api.php")
        suspend fun getFilmAjaxJson(
            @Query("id") newsId: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1",
            @Header("Referer") referer: String = "https://fs03.lol/"
        ): okhttp3.ResponseBody

        // 2026-05-04 : nouveau endpoint AJAX qui retourne les épisodes d'une
        // saison en JSON. Format : {vf:{"1":{premium:..., vidzy:..., ...}, "2":{...}}, vostfr:{...}, vo:{...}}
        // Découvert en lisant /js/serie-player13.js?v=20260504s ligne ~30:
        //   xhr.open('GET', '/engine/ajax/sx.php?p=' + newsId, true)
        // Avant on parsait `<div class="fullsfeature" id="episodeN">` du HTML
        // mais cette structure a disparu — la div `<div class="episodes-list">`
        // est maintenant vide côté serveur, populée en JS via cet endpoint.
        @GET("engine/ajax/sx.php")
        suspend fun getSerieAjaxJson(
            @Query("p") newsId: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1",
            @Header("Referer") referer: String = "https://fs03.lol/"
        ): okhttp3.ResponseBody

        // Legacy detail pages (slug-based, kept for backwards compatibility)
        @GET("/{id}")
        suspend fun getItem(
            @Path("id") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        @GET("film/{id}")
        suspend fun getMovie(
            @Path("id") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        @GET("serie/{id}")
        suspend fun getTvShow(
            @Path("id") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        // Genre listing
        @GET("films")
        suspend fun getGenre(
            @Query("genre") genre: String,
            @Query("page") page: Int,
        ): Document

        @GET("xfsearch/actors/{id}/page/{page}")
        suspend fun getPeople(
            @Path("id") id: String,
            @Path("page") page: Int,
        ): Document

        @GET
        suspend fun getRedirectLink(@Url url: String): Response<ResponseBody>

        // 2026-05-04 : variantes URL-runtime pour quand on a (re)découvert
        // dynamiquement le path d'un endpoint AJAX (cf rediscoverEndpoints).
        // On ne peut pas faire varier @GET("...") au runtime, mais @Url oui.
        @GET
        suspend fun getAjaxJsonByUrl(
            @Url url: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1",
            @Header("Referer") referer: String = "https://fs03.lol/"
        ): okhttp3.ResponseBody

        @GET
        suspend fun getRawText(
            @Url url: String,
            @Header("Referer") referer: String = "https://fs03.lol/"
        ): okhttp3.ResponseBody
    }
}
