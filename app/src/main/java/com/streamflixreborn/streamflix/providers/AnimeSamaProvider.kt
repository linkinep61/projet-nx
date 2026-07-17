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
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

object AnimeSamaProvider : Provider, ProviderConfigUrl, ProviderPortalUrl, FilterableProvider, ProgressiveServersProvider {

    override val name = "AnimeSama"
    override val defaultBaseUrl: String = "https://anime-sama.to/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }
    override val defaultPortalUrl: String = "https://anime-sama.pw/"
    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty { field }
        }
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_animesama"
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private const val TAG = "AnimeSama"
    // 2026-05-20 (user "certaines jaquettes ne chargent qu'au reload — chargement
    //   trop rapide qui oublie") : raw.githubusercontent.com est lent/peu caché
    //   (surtout réseau satellite Tahiti) → 1er affichage rate l'image, reload =
    //   disque cache. jsDelivr sert le MÊME fichier (vérifié 200, taille identique)
    //   depuis un CDN global à cache immutable → 1er chargement rapide et fiable.
    private const val IMG_BASE = "https://cdn.jsdelivr.net/gh/Anime-Sama/IMG@img/contenu/"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Réécrit les URLs d'images lentes vers le CDN jsDelivr.
     * - raw.githubusercontent.com/Anime-Sama/IMG/img/contenu/X.jpg → jsDelivr CDN
     * - URLs anime-sama.to/assets/… qui sont parfois instables → jsDelivr fallback
     * - Déjà sur jsDelivr → inchangé
     */
    private fun optimizeImageUrl(rawUrl: String?, slug: String): String {
        // Origine (sans schéma) à passer au proxy de redimensionnement.
        val origin: String = when {
            rawUrl.isNullOrBlank() || rawUrl.startsWith("data:") ->
                "raw.githubusercontent.com/Anime-Sama/IMG/img/contenu/${slug}.jpg"
            rawUrl.contains("raw.githubusercontent.com/Anime-Sama/IMG") && rawUrl.contains("/contenu/") ->
                "raw.githubusercontent.com/Anime-Sama/IMG/img/contenu/${rawUrl.substringAfter("/contenu/")}"
            rawUrl.contains("cdn.jsdelivr.net") && rawUrl.contains("/contenu/") ->
                "raw.githubusercontent.com/Anime-Sama/IMG/img/contenu/${rawUrl.substringAfter("/contenu/")}"
            rawUrl.contains("anime-sama.to") || rawUrl.contains("anime-sama.pw") ->
                "raw.githubusercontent.com/Anime-Sama/IMG/img/contenu/${slug}.jpg"
            else ->
                // Autre CDN déjà optimisé (ex: TMDb) → pas de proxy.
                return rawUrl
        }
        // 2026-05-20 (user "jaquettes trop longues à charger sur Chromecast") :
        //   les covers AnimeSama sont des JPEG full-res ~680 Ko. Sur Chromecast /
        //   réseau satellite, c'est le POIDS qui plombe le home (le choix du CDN
        //   ne change quasi rien). On redimensionne en thumbnail webp (~15 Ko,
        //   mesuré 680 Ko→17 Ko) via le proxy weserv → ~×40 plus léger.
        return "https://images.weserv.nl/?url=" +
            java.net.URLEncoder.encode(origin, "UTF-8") +
            "&w=300&output=webp&q=72"
    }

    // Cache of all Film slugs from AnimeSama catalogue — used to exclude films from the Série tab
    @Volatile private var cachedFilmSlugs: Set<String>? = null

    // 2026-05-20 : quand true, getServers NE lance PAS ses propres backups
    //   (Cloudstream, Moviebox, Papa). Utilisé quand AnimeSama est appelé COMME
    //   backup par un autre provider (ex DessinAnime) → évite double-backup,
    //   latence inutile et récursion. Même patron que MovixProvider.
    @Volatile var skipBackupsForBackupCall: Boolean = false
    // 2026-07-09 : flag anti-boucle TMDB. Quand getAnimeSamaSourcesByTitle résout
    //   les serveurs natifs, il appelle getTvShow/getEpisodesBySeason en INTERNE.
    /**
     * Fetches all Film slugs from the AnimeSama catalogue (all pages, up to 20).
     * Cached after first call so subsequent pages/tabs are instant.
     */
    private suspend fun getFilmSlugs(): Set<String> {
        cachedFilmSlugs?.let { return it }
        val slugs = mutableSetOf<String>()
        try {
            var page = 1
            while (page <= 5) { // reduced from 20 — enough for exclusion filter
                val filmUrl = "${baseUrl}catalogue/?type[]=Film&page=$page"
                val cards = fetchDocument(filmUrl).select(".catalog-card")
                if (cards.isEmpty()) break
                cards.forEach { card ->
                    card.selectFirst("a[href*=/catalogue/]")?.attr("href")
                        ?.substringAfter("/catalogue/")?.removeSuffix("/")
                        ?.split("/")?.firstOrNull()?.let { slugs.add(it) }
                }
                page++
            }
        } catch (_: Exception) { }
        cachedFilmSlugs = slugs
        return slugs
    }

    private val client = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        // Preserve POST method & body on redirects (site may change domain)
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

    // Faster client for season probing (shorter timeouts to avoid long waits on 404s)
    private val probeClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var serviceInitialized = false

    private var service: Service = Service.build(baseUrl)

    private suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .build()
        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: ""
        Jsoup.parse(html).apply { setBaseUri(baseUrl) }
    }

    private suspend fun fetchText(url: String): String = fetchTextWith(url, client)

    private suspend fun probeText(url: String): String = fetchTextWith(url, probeClient)

    private suspend fun fetchTextWith(url: String, httpClient: OkHttpClient): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        val text = response.body?.string() ?: ""
        // Detect 404 pages disguised as 200
        if (text.contains("Page introuvable") || text.contains("Accès Introuvable")) {
            throw Exception("Soft 404 detected")
        }
        text
    }

    private suspend fun searchPost(query: String): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("query", query)
            .build()
        val request = Request.Builder()
            .url("${baseUrl}template-php/defaut/fetch.php")
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        response.body?.string() ?: ""
    }

    // ========== HOME ==========

    override suspend fun getHome(): List<Category> = kotlinx.coroutines.coroutineScope {
        initializeService()
        // 2026-05-21 (user "fais 2 appels en parallèle pour que ça aille plus vite" +
        //   "y a plus le carrousel") : le carrousel TMDB tournait SÉQUENTIELLEMENT après
        //   le scrape et SANS timeout → home lent, et si l'appel TMDB traînait/échouait
        //   le catch l'avalait → carrousel disparu. On le lance EN PARALLÈLE du scrape
        //   et on le borne par un timeout : le home s'affiche vite, le carrousel arrive
        //   dès que TMDB répond (sinon home sans carrousel, mais jamais bloqué).
        val featuredDeferred: kotlinx.coroutines.Deferred<List<TvShow>>? = if (UserPreferences.enableTmdb) {
            async(Dispatchers.IO) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                        TMDb3.Discover.tv(
                            page = 1,
                            language = "fr-FR",
                            sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                            withOriginalLanguage = TMDb3.Params.WithBuilder("ja"),
                            voteCount = TMDb3.Params.Range(100, null),
                            withGenres = TMDb3.Params.WithBuilder(TMDb3.Genre.Tv.ANIMATION),
                        ).results.filter { it.backdropPath != null && it.posterPath != null }
                            .take(10)
                            .map { t ->
                                TvShow(
                                    id = "tmdb_anime_${t.id}",
                                    title = t.name,
                                    overview = t.overview,
                                    released = t.firstAirDate,
                                    rating = t.voteAverage.toDouble(),
                                    poster = t.posterPath?.w500,
                                    banner = t.backdropPath?.w1280,
                                )
                            }
                    } ?: emptyList<TvShow>()
                } catch (e: Exception) {
                    Log.w(TAG, "TMDB featured anime failed: ${e.message}")
                    emptyList<TvShow>()
                }
            }
        } else null
        // ── Catégories Catalogue (page 1) — lancées EN PARALLÈLE du scrape homepage ──
        val catalogAnimeDeferred = async(Dispatchers.IO) {
            try {
                val catalogAnimeDoc = fetchDocument("${baseUrl}catalogue/?type[]=Anime&page=1")
                catalogAnimeDoc.select(".catalog-card").mapNotNull { card ->
                    val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                    val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                        .split("/").firstOrNull() ?: return@mapNotNull null
                    val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                    val img = optimizeImageUrl(card.selectFirst("img")?.attr("src"), slug)
                    TvShow(id = slug, title = title, poster = img)
                }.distinctBy { it.id }
            } catch (e: Exception) {
                Log.w(TAG, "Catalogue Anime fetch failed: ${e.message}")
                emptyList()
            }
        }
        val catalogFilmDeferred = async(Dispatchers.IO) {
            try {
                val catalogFilmDoc = fetchDocument("${baseUrl}catalogue/?type[]=Film&page=1")
                catalogFilmDoc.select(".catalog-card").mapNotNull { card ->
                    val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                    val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                        .split("/").firstOrNull() ?: return@mapNotNull null
                    val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                    val img = optimizeImageUrl(card.selectFirst("img")?.attr("src"), slug)
                    Movie(id = slug, title = title, poster = img)
                }.distinctBy { it.id }
            } catch (e: Exception) {
                Log.w(TAG, "Catalogue Films fetch failed: ${e.message}")
                emptyList()
            }
        }
        val document = fetchDocument(baseUrl)
        val categories = mutableListOf<Category>()

        // The homepage has .grabScroll containers, each preceded by a sibling
        // flex div containing an h2 section header.
        // Cards inside have: a[href*=/catalogue/], img with alt=title & src=poster,
        // .badge-text for type (Anime/Film), h2.card-title for title.
        val scrollContainers = document.select(".grabScroll")

        for (container in scrollContainers) {
            // Find section header: walk previous siblings to find an h2
            var sectionTitle = ""
            var prev = container.previousElementSibling()
            var attempts = 0
            while (prev != null && attempts < 5) {
                val h2 = prev.selectFirst(":root > h2")
                    ?: if (prev.tagName() == "h2") prev else null
                if (h2 != null) {
                    val parentClass = h2.parent()?.className() ?: ""
                    // Section headers have parent with class "flex", card titles have parent "card-content"
                    if (!parentClass.contains("card-content")) {
                        sectionTitle = h2.text().trim()
                        break
                    }
                }
                prev = prev.previousElementSibling()
                attempts++
            }

            if (sectionTitle.isBlank()) continue
            // 2026-05-03 : sur anime-sama.to/, après "Derniers scans ajoutés"
            // toutes les sections sont scans/mangas/webtoons. On BREAK la boucle
            // dès qu'on rencontre cette section pour ne plus rien parser ensuite
            // (info confirmée par le user : à partir de "Derniers épisodes
            // ajoutés" après c'est que des scans).
            val sectionLowerCheck = sectionTitle.lowercase()
            if (sectionLowerCheck.contains("derniers scans") || sectionLowerCheck.contains("derniers manga")) break
            // Skip individuelles : sections scans/mangas/webtoons
            if (listOf("scan", "manga", "manhwa", "manhua", "webtoon", "light novel", "ln ").any { sectionLowerCheck.contains(it) }) continue
            // Skip "Reprenez votre visionnage" — requires user cookies (not available server-side)
            if (sectionTitle.contains("Reprenez", ignoreCase = true)) continue
            // Skip unreliable film sections — AnimeSama mixes series into "Films Populaires"/"Films Récents"
            if (sectionLowerCheck.contains("film")) continue

            val cards = container.select("a[href*=/catalogue/]")
            if (cards.isEmpty()) continue

            val shows = cards.mapNotNull { card ->
                val href = card.attr("href")
                if (href.isBlank()) return@mapNotNull null

                val slug = href.substringAfter("/catalogue/")
                    .split("/").firstOrNull()?.trim()
                    ?.removeSuffix("/")
                    ?: return@mapNotNull null
                if (slug.isBlank()) return@mapNotNull null

                // Title: prefer img alt, then h2.card-title, then slug
                val imgEl = card.selectFirst("img")
                val title = imgEl?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: card.selectFirst("h2.card-title")?.text()?.trim()
                    ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

                val rawImg = imgEl?.let { el ->
                    el.attr("src")?.takeIf { it.isNotBlank() }
                        ?: el.attr("data-src")?.takeIf { it.isNotBlank() }
                }
                val img = optimizeImageUrl(rawImg, slug)

                // 2026-05-03 : ALLOWLIST strict — on ne garde QUE les cards qui
                // sont explicitement marquées vidéo (Anime / Film). Tout le reste
                // (scans, mangas, manhwa, webtoons, light novels, ce qu'on n'a
                // jamais vu) est SKIPPÉ. Plus robuste qu'un denylist qu'il faut
                // maintenir au fil des nouveaux types ajoutés par AnimeSama.
                //
                // Marqueurs vidéo (au moins 1 doit matcher) :
                //   1) badge-text contient "anime" ou "film"
                //   2) parent class contient "anime-badge" ou "film-badge"
                //   3) 2e segment du href est un type vidéo (saison*, film, kai,
                //      oav, ova, special, episodes…) ou une langue (vostfr, vf…)
                //
                // Les scans ont href /catalogue/{slug}/scan/{lang} → segment 2
                // = "scan" qui n'est PAS dans l'allowlist → SKIP automatique.
                val badgeText = card.selectFirst(".badge-text")?.text()?.trim()?.lowercase() ?: ""
                val parentBadgeClasses = card.select(".badge").joinToString(" ") { it.className() }.lowercase()
                val pathSegments = href.substringAfter("/catalogue/").split("/").filter { it.isNotBlank() }
                val typeSegment = pathSegments.getOrNull(1)?.lowercase() ?: ""

                val videoBadgeTokens = listOf("anime", "film", "movie", "série", "serie", "ova", "oav", "special", "épisode", "episode")
                val videoTypeSegments = listOf("saison", "film", "movie", "kai", "oav", "ova", "special", "episodes", "vostfr", "vf", "va", "vo")

                val isVideoByBadge = videoBadgeTokens.any { badgeText.contains(it) } ||
                        listOf("anime-badge", "film-badge", "movie-badge").any { parentBadgeClasses.contains(it) }
                val isVideoByPath = videoTypeSegments.any { token ->
                    typeSegment == token || typeSegment.startsWith("${token}-") || typeSegment.startsWith(token) && typeSegment.length <= token.length + 2
                }

                if (!isVideoByBadge && !isVideoByPath) return@mapNotNull null
                // 2026-05-03 : classification Movie vs TvShow basée sur le BADGE
                // par card (plus fiable que le titre de section, car les sections
                // "Sorties du Dimanche/Lundi/etc" mélangent Anime + Film).
                // Fallback sur le titre de section si pas de badge clair.
                val sectionLower = sectionTitle.lowercase()
                val isFilm = badgeText.contains("film") || (badgeText.isBlank() && sectionLower.contains("film"))
                if (isFilm) {
                    Movie(id = slug, title = title, poster = img)
                } else {
                    TvShow(id = slug, title = title, poster = img)
                }
            }

            if (shows.isNotEmpty()) {
                categories.add(Category(name = sectionTitle, list = shows.distinctBy {
                    when (it) {
                        is Movie -> it.id
                        is TvShow -> it.id
                        else -> ""
                    }
                }))
            }
        }

        // FEATURED: récupère le carrousel TMDB lancé EN PARALLÈLE au début (await).
        //   Il a tourné pendant le scrape → ici on ne fait qu'attendre son résultat
        //   (déjà prêt la plupart du temps), borné par le timeout interne de 4s.
        // Attend le résultat TMDB max 5s après le scrape. L'appel TMDB tourne en
        // parallèle du scrape donc en pratique il est souvent déjà prêt ici.
        // Timeout interne = 5s, await = encore 5s → total max 10s mais le scrape
        // prend lui-même 5-15s donc le TMDB a largement le temps.
        val trendingAnimeRaw = if (featuredDeferred != null) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(5_000L) { featuredDeferred.await() } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        } else emptyList()
        // 2026-06-21 (user "quand je clique sur cette synopsis là dans le
        //   carrousel elle ne trouve pas de contenu au final, elle va le piocher
        //   où le contenu si elle l'a pas, pourquoi elle l'affiche") :
        //   On filtre les items TMDB pour ne garder QUE ceux qui ont un
        //   match sur AnimeSama (sinon clic → page vide). En profite pour
        //   remplacer l'id "tmdb_anime_X" par le VRAI slug AnimeSama →
        //   getTvShow() est ensuite direct (plus de redirect TMDB→search).
        //   Parallèle (sem=8) + timeout 1.5s par recherche + 4s global →
        //   home reste rapide.
        val trendingAnime: List<TvShow> = if (trendingAnimeRaw.isNotEmpty()) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                    val sem = kotlinx.coroutines.sync.Semaphore(8)
                    trendingAnimeRaw.map { tmdbShow ->
                        async(Dispatchers.IO) {
                            sem.withPermit {
                                kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                                    try {
                                        val html = searchPost(tmdbShow.title ?: return@withTimeoutOrNull null)
                                        val doc = Jsoup.parse(html)
                                        val firstResult = doc.select("a.asn-search-result").firstOrNull()
                                            ?: return@withTimeoutOrNull null
                                        val href = firstResult.attr("href")
                                        val realSlug = href.substringAfter("/catalogue/")
                                            .removeSuffix("/").split("/").firstOrNull()
                                            ?: return@withTimeoutOrNull null
                                        if (realSlug.isBlank()) return@withTimeoutOrNull null
                                        // Match confirmé → on remplace id par slug réel
                                        tmdbShow.copy(id = realSlug)
                                    } catch (_: Exception) { null }
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        } else emptyList()
        // 2026-05-22 (user "le carrousel AnimeSama n'apparaît pas du tout") : si le
        //   featured TMDB revient vide (timeout / TMDB désactivé / échec / aucun
        //   item TMDB n'a de match AnimeSama), on bâtit un carrousel de SECOURS
        //   depuis les items scrappés — même approche que DessinAnime — pour
        //   que le carrousel soit TOUJOURS présent. On crée des COPIES neuves
        //   (id identique mais objet distinct) pour ne pas partager de référence
        //   avec les rangées (sinon conflits d'itemType du FEATURED côté
        //   HomeViewModel).
        val fallbackPool = categories.flatMap { it.list }
            .filterIsInstance<TvShow>()
            .filter { !it.id.startsWith("tmdb_anime_") }
            .distinctBy { it.id }
            .take(10)
            .map { s -> TvShow(id = s.id, title = s.title, poster = s.poster, banner = s.poster ?: s.banner) }
        // Si le trending TMDB a moins de 5 items validés, compléter avec le fallback scrapé
        val featuredList: List<TvShow> = if (trendingAnime.size >= 5) {
            trendingAnime
        } else if (trendingAnime.isNotEmpty()) {
            val existingIds = trendingAnime.map { it.id }.toSet()
            trendingAnime + fallbackPool.filter { it.id !in existingIds }.take(10 - trendingAnime.size)
        } else {
            fallbackPool
        }
        if (featuredList.isNotEmpty()) {
            categories.add(0, Category(name = Category.FEATURED, list = featuredList))
        }

        // ── Await catalogue deferred (lancés en parallèle au début) ──
        // IMPORTANT : await AVANT l'enrichissement TMDB pour que les items
        // catalogue reçoivent aussi leurs posters TMDB (sinon ils gardent
        // l'URL weserv/GitHub qui peut être cassée — bug jaquettes catalogue).
        val catalogAnimeItems = try { catalogAnimeDeferred.await() } catch (_: Exception) { emptyList() }
        val catalogFilmItems = try { catalogFilmDeferred.await() } catch (_: Exception) { emptyList() }
        if (catalogAnimeItems.isNotEmpty()) {
            categories.add(Category(name = "Catalogue Anime", list = catalogAnimeItems))
        }
        if (catalogFilmItems.isNotEmpty()) {
            categories.add(Category(name = "Catalogue Films", list = catalogFilmItems))
        }

        // ── Enrichissement TMDB des jaquettes ──
        // Les covers AnimeSama (GitHub Anime-Sama/IMG) ne correspondent souvent pas
        // au contenu réel. On cherche chaque titre sur TMDB et on remplace le poster
        // par celui de TMDB (w500, net et cohérent). Borné à 6s global + sem=8.
        if (UserPreferences.enableTmdb) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                    val allItems = categories.flatMap { it.list }
                    // Titres uniques à enrichir (hors featured déjà TMDB)
                    val uniqueTitles = mutableMapOf<String, String>() // title → poster TMDB
                    val seen = mutableSetOf<String>()
                    for (item in allItems) {
                        val title = when (item) {
                            is Movie -> item.title
                            is TvShow -> if (item.id.startsWith("tmdb_anime_")) null else item.title
                            else -> null
                        } ?: continue
                        val key = title.lowercase().trim()
                        if (key.isNotBlank() && seen.add(key)) {
                            uniqueTitles[title] = "" // placeholder
                        }
                    }
                    // Recherche TMDB en parallèle (sem=8, timeout par recherche 2s)
                    val sem = Semaphore(8)
                    val results = uniqueTitles.keys.map { title ->
                        async(Dispatchers.IO) {
                            sem.withPermit {
                                try {
                                    val resp = kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                                        TMDb3.Search.multi(query = title, language = "fr-FR", page = 1)
                                    }
                                    val best = resp?.results?.firstOrNull { r ->
                                        when (r) {
                                            is TMDb3.Tv -> r.posterPath != null
                                            is TMDb3.Movie -> r.posterPath != null
                                            else -> false
                                        }
                                    }
                                    val poster = when (best) {
                                        is TMDb3.Tv -> best.posterPath?.w500
                                        is TMDb3.Movie -> best.posterPath?.w500
                                        else -> null
                                    }
                                    if (poster != null) title to poster else null
                                } catch (_: Exception) { null }
                            }
                        }
                    }.awaitAll().filterNotNull().toMap()

                    // Applique les posters TMDB sur les items existants
                    if (results.isNotEmpty()) {
                        for (cat in categories) {
                            for (item in cat.list) {
                                when (item) {
                                    is Movie -> results[item.title]?.let { item.poster = it }
                                    is TvShow -> if (!item.id.startsWith("tmdb_anime_")) {
                                        results[item.title]?.let { item.poster = it }
                                    }
                                    else -> {}
                                }
                            }
                        }
                        Log.d(TAG, "TMDB enrichment: ${results.size}/${uniqueTitles.size} posters replaced")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TMDB enrichment failed: ${e.message}")
            }
        }

        // Reorder: 1.FEATURED 2.Épisodes/récents 3.Séries récentes 4.Films récents 5.Séries 6.Films 7.Catalogue
        categories.sortedWith(compareBy { cat ->
            val n = cat.name.lowercase()
            val isRecent = n.contains("récen") || n.contains("nouveau") || n.contains("nouvelle") || n.contains("derni") || n.contains("ajouté")
            val isSeries = n.contains("séri") || n.contains("seri") || n.contains("saison") || n.contains("tv") || n.contains("anim")
            val isFilm = n.contains("film") || n.contains("movie") || n.contains("cinéma")
            when {
                cat.name == Category.FEATURED -> 0
                n.contains("épisode") || n.contains("episode") -> 1
                n.contains("derniers contenus") -> 2
                isRecent && isSeries -> 2
                isRecent && isFilm -> 3
                isSeries -> 4
                isFilm -> 5
                isRecent -> 1
                n.startsWith("catalogue") -> 7
                else -> 6
            }
        })
    }

    // ========== SEARCH ==========

    // Cache des genres récupérés dynamiquement depuis le catalogue
    private var cachedGenres: List<Genre>? = null

    // 2026-06-20 (user "les genres doivent être directement réglés en FR
    //   quand on est sur cette catégorie là Puis quand on va sur VOSTFR
    //   [...] Donc pour l'instant ça marche pas") : on track la dernière
    //   langue + type utilisés dans getFilteredMovies/getFilteredTvShows
    //   pour que `getGenre()` applique le BON filtre langue + type. Sinon
    //   l'user clique un genre depuis l'onglet FR et voit tout mixé.
    @Volatile private var lastLanguageFilter: String? = null  // "vf" / "vostfr" / null
    @Volatile private var lastTypeFilter: String? = null      // "Anime" / "Film" / null

    /** 2026-06-20 (user "VOSTFR + arts martiaux + Série OK / + Film → séries au
     *  lieu de films") : quand un genre est actif, le ViewModel rappelle
     *  `getGenre()` SANS repasser par getFilteredXxx → mes flags langue/type
     *  ne sont pas mis à jour quand l'user switch sub-tab. Solution : les
     *  ViewModels (Movies/TvShows) appellent ce helper AVANT chaque
     *  setLanguageFilter pour rafraîchir le contexte côté provider. */
    fun setActiveTabContext(languageFilter: String, fromMovies: Boolean) {
        lastLanguageFilter = if (fromMovies) "vf" else "vostfr"
        lastTypeFilter = if (languageFilter == "film") "Film" else "Anime"
        Log.d(TAG, "[setActiveTabContext] languageFilter=$languageFilter fromMovies=$fromMovies → lang=$lastLanguageFilter type=$lastTypeFilter")
    }

    /**
     * Récupère la liste complète des genres depuis la page catalogue.
     * Les genres sont dans des checkboxes : <input class="filter-checkbox" name="genre[]" value="Isekai">
     * Fallback sur une liste hardcodée si le fetch échoue.
     */
    private suspend fun fetchGenreList(): List<Genre> {
        cachedGenres?.let { return it }
        return try {
            val document = fetchDocument("${baseUrl}catalogue/")
            val genres = document.select("#genreList input.filter-checkbox").mapNotNull { input ->
                val value = input.attr("value").trim()
                if (value.isEmpty()) return@mapNotNull null
                Genre(id = value, name = value)
            }
            if (genres.isNotEmpty()) {
                cachedGenres = genres
                genres
            } else {
                fallbackGenres()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch dynamic genre list: ${e.message}")
            fallbackGenres()
        }
    }

    // 2026-06-20 (user "l'autre session avait voulu réparer les genres sur
    //   AnimeSama mais pas bien réussi") : liste fallback étendue aux 109
    //   genres réels du site (fetché depuis
    //   https://anime-sama.to/catalogue/ - #genreList input[name=genre[]]).
    //   Utilisée si fetchGenreList() dynamique échoue (= offline, CF block,
    //   site down, sélecteur DOM cassé). Permet à l'user de filtrer par
    //   N'IMPORTE quel genre du site sans dépendre du fetch HTML.
    private fun fallbackGenres(): List<Genre> = listOf(
        "Action", "Adolescence", "Aliens / Extra-terrestres", "Amitié", "Amour",
        "Apocalypse", "Art", "Arts martiaux", "Assassinat", "Autre monde",
        "Aventure", "Combats", "Comédie", "Crime", "Cyberpunk",
        "Danse", "Démons", "Détective", "Donghua", "Dragon",
        "Drame", "Ecchi", "Ecole", "Elfe", "Enquête",
        "Famille", "Fantastique", "Fantasy", "Fantômes", "Futur",
        "Gastronomie", "Ghibli", "Guerre", "Harcèlement", "Harem",
        "Harem inversé", "Histoire", "Historique", "Homosexualité", "Horreur",
        "Isekai", "Jeunesse", "Jeux", "Jeux vidéo", "Josei",
        "Journalisme", "Kaï", "LGBT+", "Mafia", "Magical girl",
        "Magie", "Maladie", "Mariage", "Mature", "Mechas",
        "Médiéval", "Militaire", "Monde virtuel", "Monstres", "Musique",
        "Mystère", "Nekketsu", "Ninjas", "Nostalgie", "Paranormal",
        "Philosophie", "Pirates", "Police", "Politique", "Post-apocalyptique",
        "Pouvoirs psychiques", "Préhistoire", "Prison", "Psychologique", "Quotidien",
        "Réincarnation / Transmigration", "Religion", "Romance", "Samouraïs", "School Life",
        "Science-Fantasy", "Science-fiction", "Scientifique", "Seinen", "Shôjo",
        "Shôjo-Ai", "Shônen", "Shônen-Ai", "Slice of Life", "Société",
        "Sport", "Super pouvoirs", "Super-héros", "Surnaturel", "Survie",
        "Survival game", "Technologies", "Thriller", "Tournois", "Travail",
        "Vampires", "Vengeance", "Voyage", "Voyage temporel", "Webcomic",
        "Yakuza", "Yaoi", "Yokai", "Yuri",
    ).map { name -> Genre(id = name, name = name) }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            return fetchGenreList()
        }

        val html = searchPost(query)
        val doc = Jsoup.parse(html)

        val rawResults = doc.select("a.asn-search-result").mapNotNull { result ->
            val href = result.attr("href")
            val slug = href.substringAfter("/catalogue/")
                .removeSuffix("/")
                .split("/").firstOrNull() ?: return@mapNotNull null
            val title = result.selectFirst("h3.asn-search-result-title")?.text()?.trim() ?: return@mapNotNull null
            val img = optimizeImageUrl(result.selectFirst("img")?.attr("src"), slug)
            Triple(slug, title, img)
        }

        // Determine type by checking panneauAnime entries on each result's catalogue page.
        // Show everything: series, individual films, and OAVs as separate results.
        return coroutineScope {
            rawResults.map { (slug, title, img) ->
                async(Dispatchers.IO) {
                    try {
                        val pageHtml = probeText("${baseUrl}catalogue/$slug/")
                        val cleanHtml = pageHtml.replace(Regex("""/\*[\s\S]*?\*/"""), "")
                        val panneauRegex = Regex("""panneauAnime\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")
                        val entriesWithPath = panneauRegex.findAll(cleanHtml).mapNotNull { match ->
                            val label = match.groupValues[1].trim()
                            val path = match.groupValues[2].trim()
                            if (label == "nom" && path == "url") null else (label to path)
                        }.toList()

                        val hasFilm = entriesWithPath.any { it.first.equals("Film", ignoreCase = true) }
                        val hasOav = entriesWithPath.any { it.first.equals("OAV", ignoreCase = true) }
                        val hasSeason = entriesWithPath.any {
                            !it.first.equals("Film", ignoreCase = true) && !it.first.equals("OAV", ignoreCase = true)
                        }

                        val items = mutableListOf<AppAdapter.Item>()

                        // Add TvShow if there are seasons
                        if (hasSeason) {
                            items.add(TvShow(id = slug, title = title, poster = img))
                        }

                        // Helper: count episodes in a folder by probing episodes.js
                        suspend fun countEpisodes(folder: String): Int {
                            for (lang in listOf("vostfr", "vf")) {
                                try {
                                    val epsJs = probeText("${baseUrl}catalogue/$slug/$folder/$lang/episodes.js")
                                    val eps1 = Regex("""var\s+eps1\s*=\s*\[([\s\S]*?)\]""").find(epsJs)?.groupValues?.get(1) ?: ""
                                    val count = Regex("""['"]https?://[^'"]+['"]""").findAll(eps1).count()
                                    if (count > 0) return count
                                } catch (_: Exception) {}
                            }
                            return 1
                        }

                        // Add individual films
                        if (hasFilm) {
                            val filmCount = countEpisodes("film")
                            if (filmCount <= 1) {
                                items.add(Movie(id = "$slug@film0", title = if (hasSeason || hasOav) "$title (Film)" else title, poster = img))
                            } else {
                                for (i in 0 until filmCount) {
                                    items.add(Movie(id = "$slug@film$i", title = "$title - Film ${i + 1}", poster = img))
                                }
                            }
                        }

                        // Add individual OAVs
                        if (hasOav) {
                            val oavCount = countEpisodes("oav")
                            if (oavCount <= 1) {
                                items.add(Movie(id = "$slug@oav0", title = "$title (OAV)", poster = img))
                            } else {
                                for (i in 0 until oavCount) {
                                    items.add(Movie(id = "$slug@oav$i", title = "$title - OAV ${i + 1}", poster = img))
                                }
                            }
                        }

                        // Fallback if nothing found
                        if (items.isEmpty()) {
                            items.add(TvShow(id = slug, title = title, poster = img))
                        }

                        items.map { it as AppAdapter.Item }
                    } catch (_: Exception) {
                        listOf(TvShow(id = slug, title = title, poster = img) as AppAdapter.Item)
                    }
                }
            }.awaitAll().flatten()
        }
    }

    // ========== MOVIES ==========

    override suspend fun getMovies(page: Int): List<Movie> {
        // FR tab: returns BOTH series (isSeries=true) and films (isSeries=false)
        // 2026-06-20 (FIX) : NE PAS toucher aux flags lastLanguageFilter/lastTypeFilter
        //   ici — getMovies est aussi appelé par HomeViewModel pour l'enrichment
        //   background et écraserait le contexte posé par les ViewModels.
        val movies = mutableListOf<Movie>()

        // 1. Anime series with VF → Movie with isSeries=true
        try {
            val animeUrl = "${baseUrl}catalogue/?type[]=Anime&langue[]=VF&page=$page"
            fetchDocument(animeUrl).select(".catalog-card").mapNotNull { card ->
                val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                    .split("/").firstOrNull() ?: return@mapNotNull null
                val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                val img = optimizeImageUrl(card.selectFirst("img")?.attr("src"), slug)
                Movie(id = "$slug@vf", title = title, poster = img).also { it.isSeries = true }
            }.let { movies.addAll(it) }
        } catch (_: Exception) {}

        // 2. Standalone films from catalogue type=Film → Movie with isSeries=false
        try {
            val filmUrl = "${baseUrl}catalogue/?type[]=Film&page=$page"
            fetchDocument(filmUrl).select(".catalog-card").mapNotNull { card ->
                val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                    .split("/").firstOrNull() ?: return@mapNotNull null
                val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                val img = optimizeImageUrl(card.selectFirst("img")?.attr("src"), slug)
                Movie(id = "$slug@film0", title = title, poster = img)
            }.let { movies.addAll(it) }
        } catch (_: Exception) {}

        return movies
    }

    // ========== TV SHOWS ==========

    override suspend fun getTvShows(page: Int): List<TvShow> {
        // Default: load only anime series (fast — single request)
        return getFilteredTvShows("serie", page)
    }

    override suspend fun getFilteredTvShows(language: String, page: Int): List<TvShow> {
        // "language" est en fait un type-filter pour AnimeSama : "serie" ou "film"
        // (sub-tabs labellés "Série" et "Film" dans TvShowsTvFragment).
        // 2026-06-20 (FIX) : NE PAS toucher aux flags ici. HomeViewModel
        //   enrichment appelle getTvShows() → écraserait le contexte
        //   Movies posé par MoviesViewModel. Le tracking est fait via
        //   `setActiveTabContext()` appelé depuis les ViewModels.
        return when (language) {
            "film" -> try {
                val filmUrl = "${baseUrl}catalogue/?type[]=Film&page=$page"
                fetchDocument(filmUrl).select(".catalog-card").mapNotNull { card ->
                    val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                    val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                        .split("/").firstOrNull() ?: return@mapNotNull null
                    val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                    val img = optimizeImageUrl(card.selectFirst("img")?.attr("src"), slug)
                    TvShow(id = "$slug@vostfr-film0", title = title, poster = img).also { it.isMovie = true }
                }
            } catch (_: Exception) { emptyList() }
            else -> try {
                val filmSlugs = getFilmSlugs()
                val animeUrl = "${baseUrl}catalogue/?type[]=Anime&langue[]=vostfr&page=$page"
                fetchDocument(animeUrl).select(".catalog-card").mapNotNull { card ->
                    val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                    val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                        .split("/").firstOrNull() ?: return@mapNotNull null
                    if (slug in filmSlugs) return@mapNotNull null
                    val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                    val img = optimizeImageUrl(card.selectFirst("img")?.attr("src"), slug)
                    TvShow(id = "$slug@vostfr", title = title, poster = img)
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    override suspend fun getFilteredMovies(language: String, page: Int): List<Movie> {
        // "language" = type-filter "serie" / "film". Onglet Films Onyx → contexte langue=vf.
        // 2026-06-20 (FIX) : NE PAS toucher aux flags ici. Tracking via setActiveTabContext.
        return when (language) {
            "film" -> try {
                val filmUrl = "${baseUrl}catalogue/?type[]=Film&page=$page"
                fetchDocument(filmUrl).select(".catalog-card").mapNotNull { card ->
                    val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                    val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                        .split("/").firstOrNull() ?: return@mapNotNull null
                    val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                    val img = optimizeImageUrl(card.selectFirst("img")?.attr("src"), slug)
                    Movie(id = "$slug@film0", title = title, poster = img)
                }
            } catch (_: Exception) { emptyList() }
            else -> try {
                val filmSlugs = getFilmSlugs()
                val animeUrl = "${baseUrl}catalogue/?type[]=Anime&langue[]=VF&page=$page"
                fetchDocument(animeUrl).select(".catalog-card").mapNotNull { card ->
                    val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                    val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                        .split("/").firstOrNull() ?: return@mapNotNull null
                    if (slug in filmSlugs) return@mapNotNull null
                    val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                    val img = optimizeImageUrl(card.selectFirst("img")?.attr("src"), slug)
                    Movie(id = "$slug@vf", title = title, poster = img).also { it.isSeries = true }
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    // ========== MOVIE DETAIL ==========

    /**
     * 2026-06-23 — Sanitize les champs synopsis/genres parsés via regex naïf.
     *
     * Bug visible sur One Piece : le regex `SYNOPSIS[\s\S]*?<p>...</p>` tombait
     * sur le panel infos complet ("Watchlist Favoris Vu État En cours Année 1999
     * Épisodes 1181 Chapitres ? Créateur Eiichiro Oda Studio Toei... Voir plus
     * Correspondance Episode 1155 -> Chapitre 1125 Synopsis Il fut un temps...").
     *
     * Si on détecte ≥ 2 marqueurs UI du panel (Watchlist, Voir plus, Correspondance
     * Episode, Chapitres ?, Créateur ) → on considère le champ pollué :
     *  - pour le synopsis on tente d'extraire ce qui suit le mot "Synopsis "
     *  - pour les genres on vide complètement
     */
    private fun sanitizeAnimeSamaField(text: String, extractAfterSynopsis: Boolean): String {
        val uiMarkers = listOf("Watchlist", "Voir plus", "Correspondance Episode",
                               "Chapitres ?", "Créateur ")
        val matched = uiMarkers.count { text.contains(it, ignoreCase = true) }
        if (matched < 2) return text
        if (extractAfterSynopsis) {
            val idx = text.lastIndexOf("Synopsis ", ignoreCase = true)
            if (idx >= 0 && idx + 9 < text.length) {
                return text.substring(idx + 9).trim()
            }
        }
        return ""
    }

    override suspend fun getMovie(id: String): Movie {
        val slug = id.substringBefore("@")
        val document = fetchDocument("${baseUrl}catalogue/$slug/")
        val html = document.html()

        val title = document.title().substringBefore(" |").trim()
        val synopsisMatch = Regex("SYNOPSIS[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(html)
        val synopsisRaw = synopsisMatch?.groupValues?.get(1)?.let { Jsoup.parse(it).text() } ?: ""
        val synopsis = sanitizeAnimeSamaField(synopsisRaw, extractAfterSynopsis = true)
        val genresMatch = Regex("GENRES[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(html)
        val genresRaw = genresMatch?.groupValues?.get(1)?.let { Jsoup.parse(it).text() } ?: ""
        val genresText = sanitizeAnimeSamaField(genresRaw, extractAfterSynopsis = false)

        // ── Search for related content from the same franchise ──
        val relatedFilms = mutableListOf<Show>()
        try {
            val searchHtml = searchPost(title)
            val searchDoc = Jsoup.parse(searchHtml)
            searchDoc.select("a.asn-search-result").mapNotNull { result ->
                val href = result.attr("href")
                val resultSlug = href.substringAfter("/catalogue/")
                    .removeSuffix("/").split("/").firstOrNull() ?: return@mapNotNull null
                if (resultSlug == slug) return@mapNotNull null // Skip self
                val resultTitle = result.selectFirst("h3.asn-search-result-title")?.text()?.trim()
                    ?: return@mapNotNull null
                val resultImg = optimizeImageUrl(result.selectFirst("img")?.attr("src"), resultSlug)
                // Show as a TvShow (clickable → will open detail with seasons/films)
                TvShow(id = resultSlug, title = resultTitle, poster = resultImg)
            }.let { relatedFilms.addAll(it) }
        } catch (_: Exception) {}

        return Movie(
            id = id,
            title = title,
            overview = synopsis,
            poster = "${IMG_BASE}${slug}.jpg",
            genres = genresText.split(",").map { Genre(id = it.trim().lowercase(), name = it.trim()) },
            recommendations = relatedFilms,
        )
    }

    // ========== TV SHOW DETAIL ==========

    override suspend fun getTvShow(id: String): TvShow {
        // ── Carrousel TMDB : redirection vers le catalogue AnimeSama ──
        // Les items du carrousel TMDB ont un id "tmdb_anime_<tmdbId>" qui n'est
        // pas un slug AnimeSama. On récupère le titre FR depuis TMDB, on cherche
        // sur AnimeSama, et on redirige vers le premier résultat.
        if (id.startsWith("tmdb_anime_")) {
            val tmdbId = id.removePrefix("tmdb_anime_").toIntOrNull()
            if (tmdbId != null) {
                try {
                    val details = TMDb3.TvSeries.details(tmdbId, language = "fr-FR")
                    val searchTitle = details.name
                    val html = searchPost(searchTitle)
                    val doc = Jsoup.parse(html)
                    val firstResult = doc.select("a.asn-search-result").firstOrNull()
                    if (firstResult != null) {
                        val href = firstResult.attr("href")
                        val realSlug = href.substringAfter("/catalogue/")
                            .removeSuffix("/").split("/").firstOrNull()
                        if (!realSlug.isNullOrBlank()) {
                            return getTvShow(realSlug)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "TMDB→AnimeSama redirect failed for $id: ${e.message}")
                }
                // Fallback : pas trouvé sur AnimeSama → TvShow minimal avec les infos TMDB
                try {
                    val details = TMDb3.TvSeries.details(tmdbId, language = "fr-FR")
                    return TvShow(
                        id = id,
                        title = details.name,
                        overview = details.overview,
                        poster = details.posterPath?.w500,
                        banner = details.backdropPath?.w1280,
                    )
                } catch (_: Exception) {}
                throw Exception("Anime non trouvé sur AnimeSama")
            }
        }

        val slug = id.substringBefore("@")
        val afterAt = id.substringAfter("@", "")
        // Parse forced language: supports "vostfr", "vf", "vostfr-film0", "vf-film2", etc.
        val forcedLang = when {
            afterAt.startsWith("vostfr") -> "vostfr"
            afterAt.startsWith("vf") -> "vf"
            else -> null
        }
        // Detect film/OAV entries: "vostfr-film0", "film0", "vf-oav2", etc.
        val filmEntryMatch = Regex("""(film|oav)(\d+)""").find(afterAt)
        val isFilmEntry = filmEntryMatch != null
        val filmFolder = filmEntryMatch?.groupValues?.get(1) ?: "film"
        val filmIndex = filmEntryMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0

        val document = fetchDocument("${baseUrl}catalogue/$slug/")
        val html = document.html()

        val title = document.title().substringBefore(" |").trim()
        val synopsisMatch = Regex("SYNOPSIS[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(html)
        val synopsisRaw = synopsisMatch?.groupValues?.get(1)?.let { Jsoup.parse(it).text() } ?: ""
        val synopsis = sanitizeAnimeSamaField(synopsisRaw, extractAfterSynopsis = true)
        val genresMatch = Regex("GENRES[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(html)
        val genresRaw = genresMatch?.groupValues?.get(1)?.let { Jsoup.parse(it).text() } ?: ""
        val genresText = sanitizeAnimeSamaField(genresRaw, extractAfterSynopsis = false)

        val animePoster = "${IMG_BASE}${slug}.jpg"

        val seasons = mutableListOf<Season>()

        // ── Film/OAV entry shortcut ──
        // When the user clicked a specific film from a tab (FR or VOSTFR),
        // go directly to that film's content — no season probing, no language choice.
        if (isFilmEntry) {
            val lang = forcedLang ?: "vostfr"
            val seasonId = "$slug/@filmentry-$filmFolder-$lang-$filmIndex"
            seasons.add(Season(
                id = seasonId,
                number = 1,
                title = title,
                poster = animePoster,
            ))

            // ── Search for related content from the same franchise ──
            val relatedContent = mutableListOf<Show>()
            try {
                val searchHtml = searchPost(title)
                val searchDoc = Jsoup.parse(searchHtml)
                searchDoc.select("a.asn-search-result").mapNotNull { result ->
                    val href = result.attr("href")
                    val resultSlug = href.substringAfter("/catalogue/")
                        .removeSuffix("/").split("/").firstOrNull() ?: return@mapNotNull null
                    if (resultSlug == slug) return@mapNotNull null // Skip self
                    val resultTitle = result.selectFirst("h3.asn-search-result-title")?.text()?.trim()
                        ?: return@mapNotNull null
                    val resultImg = optimizeImageUrl(result.selectFirst("img")?.attr("src"), resultSlug)
                    TvShow(id = resultSlug, title = resultTitle, poster = resultImg)
                }.let { relatedContent.addAll(it) }
            } catch (_: Exception) {}

            return TvShow(
                id = id,
                title = title,
                overview = synopsis,
                poster = animePoster,
                genres = genresText.split(",").filter { it.isNotBlank() }.map { Genre(id = it.trim().lowercase(), name = it.trim()) },
                seasons = seasons,
                recommendations = relatedContent,
            )
        }

        val languages = if (forcedLang != null) listOf(forcedLang) else listOf("vostfr", "vf")
        val langLabels = mapOf("vostfr" to "VOSTFR", "vf" to "VF")

        // ── Step 1: parse panneauAnime() calls from page JavaScript ──
        // AnimeSama uses document.write via JS: panneauAnime("Label", "folder/lang");
        // These are NOT rendered as <a> tags in the raw HTML, so we parse the JS calls.
        // IMPORTANT: strip JS block comments (/* ... */) first — AnimeSama often comments out
        // future/placeholder seasons, and our regex would otherwise pick them up.
        val htmlNoComments = html.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        val excludedPrefixes = listOf("kai", "scans", "manga")
        val panneauRegex = Regex("""panneauAnime\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")
        val scrapedFolders = panneauRegex.findAll(htmlNoComments).mapNotNull { match ->
            val label = match.groupValues[1].trim()
            val rawPath = match.groupValues[2].trim() // e.g. "saison2-2/vostfr"
            // Extract the folder part (before the language suffix)
            val folder = rawPath.split("/").firstOrNull()?.trim() ?: return@mapNotNull null
            if (folder.isBlank()) return@mapNotNull null
            // Skip the template/header entry: panneauAnime("nom", "url")
            if (label == "nom" && rawPath == "url") return@mapNotNull null
            // Skip Kai versions, Scans, Manga (match by label prefix or folder prefix)
            val labelLower = label.lowercase()
            if (excludedPrefixes.any { labelLower.startsWith(it) || folder.startsWith(it) }) return@mapNotNull null
            // Skip "Sans fillers" variants (duplicate of the full version)
            if (labelLower.contains("sans filler")) return@mapNotNull null
            // Clean up "Avec fillers" label → just show as season name
            val cleanLabel = if (label.lowercase().contains("avec filler")) {
                // Try to extract a meaningful name from the folder (e.g. "saison1" → "Saison 1")
                val num = Regex("""saison(\d+)""").find(folder)?.groupValues?.get(1)
                if (num != null) "Saison $num" else label
            } else label
            folder to cleanLabel
        }.toList().distinctBy { it.first }

        Log.d(TAG, "[Seasons] Parsed ${scrapedFolders.size} panneauAnime entries: ${scrapedFolders.map { "${it.second} -> ${it.first}" }}")

        // ── Step 2: build the list of folder paths to probe ──
        // Use scraped folders if found, otherwise fall back to sequential saison1..20
        data class SeasonFolder(val path: String, val label: String)

        val foldersToProbe = if (scrapedFolders.isNotEmpty()) {
            scrapedFolders.map { (folder, label) -> SeasonFolder(folder, label) }
        } else {
            // Fallback: probe saison1..20 sequentially
            (1..20).map { SeasonFolder("saison$it", "Saison $it") }
        }

        // ── Step 3: probe each folder for each language ──
        // langSeasonFolders maps lang -> list of SeasonFolder that have valid episodes
        val langSeasonFolders = mutableMapOf<String, MutableList<SeasonFolder>>()
        var consecutiveMisses = 0
        // Track eps1 content per lang to deduplicate seasons with identical episodes
        val seenEps1PerLang = mutableMapOf<String, MutableSet<String>>()

        for (folder in foldersToProbe) {
            var anyFound = false
            for (lang in languages) {
                try {
                    val probeUrl = "${baseUrl}catalogue/$slug/${folder.path}/$lang/episodes.js"
                    val text = probeText(probeUrl)
                    if (text.contains("var eps1") && text.contains("http")) {
                        val eps1Content = Regex("""var\s+eps1\s*=\s*\[([\s\S]*?)\]""").find(text)?.groupValues?.get(1) ?: ""
                        val urlCount = Regex("""['"]https?://[^'"]+['"]""").findAll(eps1Content).count()
                        if (urlCount >= 1) {
                            // Deduplicate: skip if another season already has identical eps1 URLs
                            val eps1Urls = Regex("""['"]https?://[^'"]+['"]""").findAll(eps1Content)
                                .map { it.value }.sorted().joinToString("|")
                            val seen = seenEps1PerLang.getOrPut(lang) { mutableSetOf() }
                            if (seen.contains(eps1Urls)) {
                                Log.d(TAG, "[Seasons] Skipping duplicate $slug/${folder.path}/$lang (same eps1 as earlier season)")
                                continue
                            }
                            seen.add(eps1Urls)
                            langSeasonFolders.getOrPut(lang) { mutableListOf() }.add(folder)
                            anyFound = true
                        } else {
                            Log.d(TAG, "[Seasons] Skipping $slug/${folder.path}/$lang: only $urlCount URL(s)")
                        }
                    }
                } catch (_: Exception) {}
            }
            if (anyFound) {
                consecutiveMisses = 0
            } else {
                consecutiveMisses++
                // For sequential probing (no scraped folders), stop after 3 consecutive misses
                if (scrapedFolders.isEmpty() && consecutiveMisses >= 3 && langSeasonFolders.isNotEmpty()) break
                if (scrapedFolders.isEmpty() && consecutiveMisses >= 4) break
            }
        }

        // Fallback: try without subfolder (single-season anime: slug/vf/episodes.js)
        if (langSeasonFolders.isEmpty()) {
            for (lang in languages) {
                try {
                    val probeUrl = "${baseUrl}catalogue/$slug/$lang/episodes.js"
                    val text = probeText(probeUrl)
                    if (text.contains("var eps1") && text.contains("http")) {
                        langSeasonFolders.getOrPut(lang) { mutableListOf() }.add(SeasonFolder("", "Épisodes"))
                    }
                } catch (_: Exception) {}
            }
        }

        // If forced language found nothing, try the other language as fallback
        if (langSeasonFolders.isEmpty() && forcedLang != null) {
            val fallbackLang = if (forcedLang == "vf") "vostfr" else "vf"
            val fallbackFolders = if (scrapedFolders.isNotEmpty()) {
                scrapedFolders.map { (folder, label) -> SeasonFolder(folder, label) }
            } else {
                (1..3).map { SeasonFolder("saison$it", "Saison $it") }
            }
            for (folder in fallbackFolders) {
                try {
                    val probeUrl = "${baseUrl}catalogue/$slug/${folder.path}/$fallbackLang/episodes.js"
                    val text = probeText(probeUrl)
                    if (text.contains("var eps1") && text.contains("http")) {
                        val eps1Content = Regex("""var\s+eps1\s*=\s*\[([\s\S]*?)\]""").find(text)?.groupValues?.get(1) ?: ""
                        val urlCount = Regex("""['"]https?://[^'"]+['"]""").findAll(eps1Content).count()
                        if (urlCount >= 1) {
                            langSeasonFolders.getOrPut(fallbackLang) { mutableListOf() }.add(folder)
                        }
                    }
                } catch (_: Exception) {}
            }
            // Also try without subfolder
            if (langSeasonFolders.isEmpty()) {
                try {
                    val probeUrl = "${baseUrl}catalogue/$slug/$fallbackLang/episodes.js"
                    val text = probeText(probeUrl)
                    if (text.contains("var eps1") && text.contains("http")) {
                        langSeasonFolders.getOrPut(fallbackLang) { mutableListOf() }.add(SeasonFolder("", "Épisodes"))
                    }
                } catch (_: Exception) {}
            }
        }

        // 2026-06-24 v9 DÉFINITIF (user "pourquoi t'as retiré mes 2 dossiers
        //   VOSTFR et FR du menu synepsie") : ON EMET les 2 wrappers VOSTFR/VF
        //   comme jaquettes de saison dans le synopsis quand les 2 langues
        //   sont dispo. Title = "VOSTFR" / "VF" (= s'affiche tel quel sur la
        //   jaquette). Click → Season fragment → drill dans les vraies
        //   sous-saisons via re-probe (cf langFolderRegex getEpisodesBySeason).
        //   Si 1 seule langue dispo OU forcedLang → vraies saisons directes.
        val hasBothLangs = forcedLang == null
            && langSeasonFolders.containsKey("vostfr")
            && langSeasonFolders.containsKey("vf")
        if (hasBothLangs) {
            listOf("vostfr" to "VOSTFR", "vf" to "VF").forEachIndexed { idx, (lang, label) ->
                val folders = langSeasonFolders[lang] ?: return@forEachIndexed
                if (folders.isEmpty()) return@forEachIndexed
                val paths = folders.joinToString(",") { f -> if (f.path.isEmpty()) "0" else f.path }
                val labels = folders.joinToString("|") { f -> f.label }
                seasons.add(Season(
                    id = "$slug/@$lang/$paths/$labels",
                    number = idx + 1,
                    title = label, // "VOSTFR" / "VF"
                    poster = animePoster,
                ))
            }
        } else {
            val bestLang = forcedLang
                ?: if (langSeasonFolders.containsKey("vostfr")) "vostfr"
                   else langSeasonFolders.keys.firstOrNull() ?: "vostfr"
            val folders = langSeasonFolders[bestLang] ?: langSeasonFolders.values.firstOrNull() ?: mutableListOf()
            for ((idx, folder) in folders.withIndex()) {
                val seasonId = if (folder.path.isEmpty()) "$slug/$bestLang" else "$slug/${folder.path}/$bestLang"
                // 2026-07-12 : numéroter par le LABEL (ex "Saison 3" → 3), PAS par la position.
                //   AnimeSama split parfois une saison en parties (Saison 2 Partie 1 / Partie 2)
                //   → la numérotation positionnelle décale toutes les saisons suivantes
                //   (Saison 3 se retrouvait en position 4, introuvable pour les backups TMDB).
                val labelNum = Regex("""(?i)saison\s*(\d+)""").find(folder.label)
                    ?.groupValues?.get(1)?.toIntOrNull()
                val seasonNumber = labelNum ?: (idx + 1)
                seasons.add(Season(
                    id = seasonId,
                    number = seasonNumber,
                    title = folder.label,
                    poster = animePoster,
                ))
            }
        }

        return TvShow(
            id = id,
            title = title,
            overview = synopsis,
            poster = "${IMG_BASE}${slug}.jpg",
            genres = genresText.split(",").filter { it.isNotBlank() }.map { Genre(id = it.trim().lowercase(), name = it.trim()) },
            seasons = seasons,
        )
    }

    // ========== EPISODES ==========

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // Three formats:
        // 1. Film entry:      "slug/@filmentry-film-vostfr-0" → single film episode
        // 2. Language folder:  "slug/@vostfr/path1,path2/Label1|Label2" → sub-seasons
        // 3. Direct season:    "slug/saison1/vostfr" or "slug/vostfr" → single season

        // ── Film entry shortcut ──
        val filmEntryRegex = Regex("""(.+)/@filmentry-(film|oav)-(\w+)-(\d+)""")
        val filmEntryMatch = filmEntryRegex.find(seasonId)
        if (filmEntryMatch != null) {
            val feSlug = filmEntryMatch.groupValues[1]
            val feFolder = filmEntryMatch.groupValues[2]
            val feLang = filmEntryMatch.groupValues[3]
            val feIndex = filmEntryMatch.groupValues[4].toIntOrNull() ?: 0
            val episodePoster = "${IMG_BASE}${feSlug}.jpg"
            // Return a single episode that points to the specific film
            return listOf(Episode(
                id = "$feSlug/$feFolder/$feLang/${feIndex + 1}",
                number = 1,
                title = "Film${if (feIndex > 0) " ${feIndex + 1}" else ""}",
                poster = episodePoster,
            ))
        }

        val langFolderRegex = Regex("""(.+)/@(\w+)/([^/]+)/(.+)""")
        val langMatch = langFolderRegex.find(seasonId)

        // Extract anime slug for episode poster (use the anime's cover image)
        val animeSlug = langMatch?.groupValues?.get(1)
            ?: seasonId.split("/").firstOrNull() ?: seasonId
        val episodePoster = "${IMG_BASE}${animeSlug}.jpg"

        if (langMatch != null) {
            // Language folder mode: return sub-seasons as fake episodes.
            // 2026-06-24 v5 (user "obligé de cliquer sur VF et revenir sur
            //   VOSTFR pour afficher toutes les saisons") : on IGNORE les
            //   paths/labels figés dans l'ID (= peuvent être incomplets si le
            //   scan initial getTvShow a été coupé) et on RE-PROBE la liste
            //   complète des sous-saisons à chaque ouverture. Toujours fresh.
            val slug = langMatch.groupValues[1]
            val lang = langMatch.groupValues[2]
            Log.d(TAG, "[Episodes] Language folder REPROBE: lang=$lang, slug=$slug")

            // Re-scrape la liste des dossiers depuis la page index du slug
            //   (= source authoritative). Si la page liste 4 saisons, on aura
            //   les 4 garanties.
            val scrapedFolders = try {
                val indexUrl = "${baseUrl}catalogue/$slug/"
                val html = probeText(indexUrl)
                val regex = Regex("""(?:nom|name)\s*:\s*['"]([^'"]+)['"]\s*[,;]?\s*(?:url|chemin|path)\s*:\s*['"]([^'"]+)['"]""")
                regex.findAll(html).mapNotNull { match ->
                    val label = match.groupValues[1].trim()
                    val path = match.groupValues[2].trim()
                    if (label == "nom" && path == "url") null else (path to label)
                }.toList()
            } catch (_: Exception) { emptyList() }

            // Si pas de scrape (= probe), fallback sur l'ID figé d'origine
            //   (= comportement ancien). Sinon on re-probe les sous-saisons
            //   trouvées pour vérifier qu'elles ont bien des épisodes dans
            //   la langue demandée.
            val foldersToList: List<Pair<String, String>> = if (scrapedFolders.isNotEmpty()) {
                // Vérifie quelles saisons ont vraiment des épisodes dans CETTE langue
                val verified = mutableListOf<Pair<String, String>>()
                val seenEps1 = mutableSetOf<String>()
                for ((path, label) in scrapedFolders) {
                    try {
                        val probeUrl = "${baseUrl}catalogue/$slug/$path/$lang/episodes.js"
                        val text = probeText(probeUrl)
                        if (text.contains("var eps1") && text.contains("http")) {
                            val eps1Content = Regex("""var\s+eps1\s*=\s*\[([\s\S]*?)\]""").find(text)?.groupValues?.get(1) ?: ""
                            val urlCount = Regex("""['"]https?://[^'"]+['"]""").findAll(eps1Content).count()
                            if (urlCount >= 1) {
                                // dedupe
                                val eps1Urls = Regex("""['"]https?://[^'"]+['"]""").findAll(eps1Content)
                                    .map { it.value }.sorted().joinToString("|")
                                if (eps1Urls !in seenEps1) {
                                    seenEps1.add(eps1Urls)
                                    verified.add(path to label)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                verified
            } else {
                // Fallback : utilise les paths/labels d'origine figés dans l'ID
                val folderPaths = langMatch.groupValues[3].split(",")
                val folderLabels = langMatch.groupValues[4].split("|")
                folderPaths.mapIndexed { idx, p ->
                    val realPath = if (p == "0") "" else p
                    val l = folderLabels.getOrElse(idx) { p }
                    realPath to l
                }
            }

            val episodes = mutableListOf<Episode>()
            for ((idx, pair) in foldersToList.withIndex()) {
                val (path, label) = pair
                val subSeasonId = if (path.isEmpty()) "$slug/$lang" else "$slug/$path/$lang"
                // 2026-07-13 (user « le Film/OAV ne doivent pas être comptés/numérotés comme des
                //   saisons » + diag OPPO Slime : AniCloud cherchait « Saison 5 » pour la Saison 4) :
                //   les VRAIES saisons sont numérotées par le LABEL (« Saison 4 » → 4), PAS par la
                //   position (idx+1) qui COMPTAIT le Film/OAV et décalait tout (+1). Les Film/OAV/
                //   spin-offs (label SANS « Saison N ») restent VISIBLES mais reçoivent un numéro
                //   HORS-SAISON (900+idx) → ils n'entrent jamais en collision avec une vraie saison
                //   ni ne sont matchés par un backup (pas de « Saison 900 »).
                val subLabelNum = Regex("""(?i)saison\s*(\d+)""").find(label)
                    ?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(Episode(
                    id = "@subfolder:$subSeasonId",
                    number = subLabelNum ?: (900 + idx),
                    title = label,
                    poster = episodePoster,
                    overview = "@subfolder",
                ))
            }
            Log.d(TAG, "[Episodes] Language folder: ${episodes.size} sub-seasons (re-probed)")
            return episodes
        }

        // Direct season mode
        val url = "${baseUrl}catalogue/$seasonId/episodes.js"
        Log.d(TAG, "[Episodes] Fetching: $url")

        val episodesJs = try {
            val text = fetchText(url)
            if (text.contains("var eps1")) text else ""
        } catch (e: Exception) {
            Log.e(TAG, "[Episodes] Error fetching: ${e.message}")
            ""
        }

        if (episodesJs.isBlank()) {
            Log.w(TAG, "[Episodes] No valid episodes.js for seasonId=$seasonId")
            return emptyList()
        }

        val eps1Regex = Regex("""var\s+eps1\s*=\s*\[([\s\S]*?)\]""")
        val eps1Content = eps1Regex.find(episodesJs)?.groupValues?.get(1) ?: ""
        val urlRegex = Regex("""['"]((https?://[^'"]+))['"]""")
        val urls = urlRegex.findAll(eps1Content).map { it.groupValues[1] }.toList()

        Log.d(TAG, "[Episodes] Found ${urls.size} episodes for $seasonId")

        return urls.mapIndexed { index, _ ->
            Episode(
                id = "$seasonId/${index + 1}",
                number = index + 1,
                title = "Episode ${index + 1}",
                poster = episodePoster,
            )
        }
    }

    // ========== SERVERS ==========

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        val urlRegex = Regex("""['"]((https?://[^'"]+))['"]""")
        val epsRegex = Regex("""var\s+(eps\w+)\s*=\s*\[([\s\S]*?)\]""")

        if (videoType is Video.Type.Movie) {
            // Movies/OAV: ID format is "slug@filmN" or "slug@oavN" where N is the index (0-based)
            // or legacy "slug@vf"/"slug@vostfr"/"slug" (defaults to film index 0)
            val movieSlug = id.substringBefore("@")
            val filmMatch = Regex("""@(film|oav)(\d+)""").find(id)
            val folder = filmMatch?.groupValues?.get(1) ?: "film"
            val filmIndex = filmMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
            Log.d(TAG, "[Movie Servers] Starting for id=$id slug=$movieSlug folder=$folder filmIndex=$filmIndex")
            val languages = listOf("vf", "vostfr")
            val langLabels = mapOf("vostfr" to "VOSTFR", "vf" to "VF")

            val langResults = coroutineScope {
                languages.map { lang ->
                    async {
                        val fetchUrl = "${baseUrl}catalogue/$movieSlug/$folder/$lang/episodes.js"
                        Log.d(TAG, "[Movie Servers] Fetching: $fetchUrl")
                        val episodesJs = try {
                            fetchText(fetchUrl)
                        } catch (e: Exception) {
                            Log.e(TAG, "[Movie Servers] Fetch failed for $lang: ${e.message}")
                            return@async emptyList<Video.Server>()
                        }
                        Log.d(TAG, "[Movie Servers] $lang response: length=${episodesJs.length}, hasEps1=${episodesJs.contains("var eps1")}")
                        if (episodesJs.isBlank() || !episodesJs.contains("var eps1")) return@async emptyList()

                        val langServers = mutableListOf<Video.Server>()
                        for (match in epsRegex.findAll(episodesJs)) {
                            val varName = match.groupValues[1]
                            val urls = urlRegex.findAll(match.groupValues[2])
                                .map { it.groupValues[1] }.toList()
                            Log.d(TAG, "[Movie Servers] $lang $varName: ${urls.size} urls, using index $filmIndex")
                            if (filmIndex < urls.size) {
                                val url = urls[filmIndex]
                                val serverName = getServerName(varName, url)
                                val langSuffix = if (languages.size > 1) " (${langLabels[lang]})" else ""
                                langServers.add(Video.Server(
                                    id = "${varName}_${lang}_$filmIndex",
                                    name = "$serverName$langSuffix",
                                    src = url,
                                ))
                            }
                        }
                        langServers
                    }
                }.awaitAll()
            }
            langResults.forEach { servers.addAll(it) }
            Log.d(TAG, "[Movie Servers] Total servers found: ${servers.size}")
        } else {
            // TV Episodes: id format "slug/saison1/lang/3" or "slug/lang/3"
            val parts = id.split("/")
            val episodeNum: Int
            val jsPath: String

            when {
                parts.size >= 4 && parts.last().toIntOrNull() != null -> {
                    episodeNum = parts.last().toInt()
                    jsPath = parts.dropLast(1).joinToString("/")
                }
                parts.size >= 3 && parts.last().toIntOrNull() != null -> {
                    episodeNum = parts.last().toInt()
                    jsPath = parts.dropLast(1).joinToString("/")
                }
                else -> return emptyList()
            }
            val episodeIndex = episodeNum - 1
            // 2026-07-04 : dériver la langue du path épisode pour tagger les serveurs
            // Format: "slug/saison1/vf/3" ou "slug/vostfr/3" → on cherche "vf"/"vostfr" dans les parts
            val tvLangLabel = run {
                // parts sans le dernier (= numéro épisode)
                val pathParts = parts.dropLast(1).map { it.lowercase() }
                when {
                    pathParts.any { it == "vostfr" } -> " (VOSTFR)"
                    pathParts.any { it == "vf" }     -> " (VF)"
                    else -> ""  // pas de langue détectable → pas de tag
                }
            }
            Log.d(TAG, "[TV Servers] id=$id → jsPath=$jsPath epNum=$episodeNum epIdx=$episodeIndex lang=$tvLangLabel")

            val fetchUrl = "${baseUrl}catalogue/$jsPath/episodes.js"
            Log.d(TAG, "[TV Servers] Fetching: $fetchUrl")
            val episodesJs = try {
                fetchText(fetchUrl)
            } catch (e: Exception) {
                Log.e(TAG, "[TV Servers] Error fetching episodes.js: ${e.message}")
                return emptyList()
            }
            if (episodesJs.isBlank()) return emptyList()

            for (match in epsRegex.findAll(episodesJs)) {
                val varName = match.groupValues[1]
                val urls = urlRegex.findAll(match.groupValues[2])
                    .map { it.groupValues[1] }.toList()

                if (episodeIndex < urls.size) {
                    val url = urls[episodeIndex]
                    val serverName = getServerName(varName, url)
                    Log.d(TAG, "[TV Servers] $varName → $serverName$tvLangLabel → ${url.take(80)}")
                    servers.add(Video.Server(
                        id = varName,
                        name = "$serverName$tvLangLabel",
                        src = url,
                    ))
                }
            }
        }

        // 2026-05-10 : RÈGLE STRICTE — tout VO/VOSTFR à la fin, jamais mélangé
        // avec le VF. PRIMARY = langue (VF avant VO/VOSTFR), SECONDARY = priorité
        // extracteur (Sibnet > SendVid > VidMoLy > autres > Lpayer).
        servers.sortWith(
            compareBy<Video.Server> { srv ->
                // 0 = VF (ou aucun tag), 1 = VO/VOSTFR (toujours en bas)
                val n = srv.name
                when {
                    n.contains("VOSTFR", ignoreCase = true) -> 1
                    n.contains("(VO)", ignoreCase = true) -> 1
                    Regex("""\bVO\b""").containsMatchIn(n) -> 1
                    else -> 0
                }
            }.thenByDescending { srv ->
                when {
                    srv.name.contains("Sibnet", ignoreCase = true) -> 3
                    srv.name.contains("SendVid", ignoreCase = true) -> 2
                    srv.name.contains("VidMoLy", ignoreCase = true) -> 1
                    // Lpayer en dernier: WebView + décryptage = lent (~5-10s)
                    srv.name.contains("Lpayer", ignoreCase = true) -> -1
                    else -> 0
                }
            }
        )

        // 2026-05-21 (user "remets les appels comme avant, ça a toujours bien chargé") :
        //   natif d'abord — les secours croisés (Cloudstream → Movix avec id "0")
        //   restaient bloqués (fstream/0, wiflix/0) et bloquaient l'affichage. On ne
        //   les lance QUE si le natif est vide.
        if (servers.isNotEmpty()) {
            Log.d(TAG, "[Servers] ${servers.size} natifs (secours ignorés, natif présent)")
            return servers
        }

        // 2026-05-05 : Moviebox + Cloudstream backups pour les animes.
        val slug = id.substringBefore("@").substringBefore("/")
        val title = slug.replace("-", " ").trim()

        // 2026-07-04 : backups inline DÉSACTIVÉS → registre central (BackupRegistry.fetchAll
        //   dans PlayerViewModel via ProgressiveServersProvider). Plus de Cloudstream/Moviebox/
        //   Papadustream inline ici — le registre les gère en parallèle.

        // 2026-05-10 v3 : BRUTE FORCE — pénalité -500000 sur tout VO/VOSTFR.
        // Garantit absolument qu'aucun VO/VOSTFR ne peut jamais passer au-dessus
        // d'un VF, peu importe quelle priorité interne il a. sortedByDescending
        // est stable donc préserve l'ordre relatif au sein de chaque groupe.
        val isVoLike: (Video.Server) -> Boolean = { srv ->
            val n = srv.name
            n.contains("VOSTFR", ignoreCase = true) ||
            n.contains("(VO)", ignoreCase = true) ||
            Regex("""\bVO\b""").containsMatchIn(n)
        }
        val sorted = servers.withIndex().sortedByDescending { (idx, srv) ->
            val voOffset = if (isVoLike(srv)) -500000 else 0
            (1000 - idx) + voOffset
        }.map { it.value }
        return sorted
    }

    /**
     * 2026-06-23 (user "AnimeSama n'a pas de captcha, faire un backup progressif") :
     *   ProgressiveServersProvider. Émet le natif en 1er, puis backups
     *   (Cloudstream + Moviebox + Papa) en parallèle ajoutés au lot.
     *   L'user voit les sources natives instantanément + les backups arrivent
     *   ~5-10s après sans bloquer la lecture.
     */
    // 2026-07-04 : simplifié — natif SEUL, le registre central (BackupRegistry.fetchAll
    //   dans PlayerViewModel) gère tous les backups (Cloudstream/Moviebox/Papa/etc.)
    //   en parallèle. Plus de doublons inline.
    override fun getServersProgressive(id: String, videoType: Video.Type): kotlinx.coroutines.flow.Flow<List<Video.Server>> =
        kotlinx.coroutines.flow.channelFlow {
            try {
                val native = kotlinx.coroutines.withContext(Dispatchers.IO) { getServers(id, videoType) }
                if (native.isNotEmpty()) send(native)
            } catch (e: Exception) { Log.w(TAG, "progressive native KO: ${e.message}") }
        }

    /**
     * Backup pour autres providers (ex DessinAnime) : retourne UNIQUEMENT les
     *   serveurs natifs AnimeSama d'un titre (pas de backups internes, donc pas
     *   de récursion ni de double-fetch). Best-effort, match par titre normalisé.
     *   Réutilise les méthodes testées search → getTvShow → getEpisodesBySeason
     *   → getServers, donc pas de reconstruction d'IDs fragile.
     */
    suspend fun getAnimeSamaSourcesByTitle(title: String, videoType: Video.Type): List<Video.Server> {
        if (title.isBlank()) return emptyList()
        // 2026-07-07 : matching renforcé via BackupRegistry.titleMatches (anti faux-positifs).
        //   L'ancien matching maison (contains/startsWith sur 2 chars normalisés) matchait
        //   "K.O." sur n'importe quel anime contenant "ko" (DragonBall KOai, ToKYo Ghoul…).
        //   Désormais on délègue au même algorithme durci que les autres backups.
        //   Garde supplémentaire : titre normalisé ≤ 2 chars → exact seulement (trop de bruit sinon).
        val norm = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]"), "") }
        val want = norm(title)
        if (want.isBlank()) return emptyList()
        val strictShort = want.length <= 2
        val matches = { candidateTitle: String ->
            if (strictShort) {
                // Titre ultra-court (ex: "K.O." → "ko") → exact seulement
                norm(candidateTitle) == want
            } else {
                com.streamflixreborn.streamflix.utils.BackupRegistry.titleMatches(candidateTitle, title)
            }
        }
        return try {
            val items = kotlinx.coroutines.withTimeoutOrNull(9_000L) { search(title, 1) } ?: return emptyList()
            val prev = skipBackupsForBackupCall
            skipBackupsForBackupCall = true
            try {
                when (videoType) {
                    is Video.Type.Movie -> {
                        val movie = items.filterIsInstance<Movie>().firstOrNull { matches(it.title) }
                            ?: return emptyList()
                        Log.d(TAG, "[backup] AnimeSama movie match: ${movie.title} (${movie.id})")
                        getServers(movie.id, videoType)
                    }
                    is Video.Type.Episode -> {
                        val show = items.filterIsInstance<TvShow>().firstOrNull { matches(it.title) }
                            ?: return emptyList()
                        Log.d(TAG, "[backup] AnimeSama show match: ${show.title} (${show.id})")
                        // 2026-07-12 : déléguer à CrossProviderResolver qui gère wrappers langue
                        //   (VOSTFR/VF), numérotation label, fallback titre — au lieu de la logique
                        //   manuelle cassée qui prenait full.seasons.firstOrNull() (= mauvaise saison).
                        com.streamflixreborn.streamflix.utils.CrossProviderResolver
                            .resolveAndFetchServers(this, show, videoType, timeoutMs = 25_000)
                    }
                }
            } finally {
                skipBackupsForBackupCall = prev
            }
        } catch (e: Exception) {
            Log.w(TAG, "getAnimeSamaSourcesByTitle KO: ${e.message}")
            emptyList()
        }
    }

    // ========== HELPERS ==========

    private fun getServerName(varName: String, url: String): String = when {
        varName == "epsAS" -> "AnimeSama"
        url.contains("anime-sama.fr") -> "AnimeSama"
        url.contains("sibnet") -> "Sibnet"
        url.contains("vidmoly") -> "VidMoLy"
        url.contains("vk.com") || url.contains("vkvideo.ru") -> "VK"
        url.contains("sendvid") -> "SendVid"
        url.contains("myvi") -> "MyVi"
        url.contains("oneupload") -> "OneUpload"
        url.contains("smoothpre", ignoreCase = true) -> "Smoothpre"
        url.contains("filemoon") -> "Filemoon"
        url.contains("minochinos") -> "Minochinos"
        url.contains("lpayer") || url.contains("embed4me") -> "Lpayer"
        else -> {
            try {
                java.net.URL(url).host.substringBefore(".").replaceFirstChar { it.uppercase() }
            } catch (_: Exception) { varName }
        }
    }

    // ========== VIDEO ==========

    override suspend fun getVideo(server: Video.Server): Video {
        val url = server.src
        Log.d(TAG, "[getVideo] Server: ${server.name}, src: $url")

        // 2026-05-10 : Direct MP4 links hébergés sur anime-sama.fr (subdomains
        // s5/s22.anime-sama.fr). Les "redirections" .to/.pw/.si/.tv/.org sont
        // UNIQUEMENT au niveau du site web (page HTML), le CDN de stockage des
        // MP4 n'existe que sur .fr. Donc PAS de rewrite — on garde l'URL .fr
        // d'origine. Le DNS .fr peut être bloqué chez certains FAI (Tahiti) ;
        // PlayerTvFragment.needsDoH() force OkHttp+DoH pour anime-sama.* qui
        // by-passe ce blocage.
        if (url.contains("anime-sama.", ignoreCase = true) || url.endsWith(".mp4")) {
            return Video(
                source = url,
                headers = mapOf(
                    "Referer" to "https://anime-sama.fr/",
                    "User-Agent" to USER_AGENT
                )
            )
        }

        // Use the project's existing extractors (Sibnet, VidMoLy, SendVid, etc.)
        return try {
            val video = Extractor.extract(url)
            Log.d(TAG, "[getVideo] Extractor succeeded for ${server.name}: ${video.source.take(80)}")
            video
        } catch (e: Exception) {
            Log.e(TAG, "[getVideo] Extractor failed for ${server.name}: ${e.message}")
            throw e // Let the player auto-fallback to the next server
        }
    }

    // ========== GENRE ==========

    override suspend fun getGenre(id: String, page: Int): Genre {
        // Ne PAS ajouter type[]=Anime&type[]=Film — le serveur casse la requête et ne renvoie quasi rien.
        // On filtre les scans côté client à la place.
        // 2026-06-20 (user "les genres doivent être directement réglés en FR
        //   quand on est sur cette catégorie là Puis quand on va sur VOSTFR
        //   c'est l'inverse") : applique le contexte langue+type capturé par
        //   le dernier appel à getMovies/getTvShows/getFilteredXxx. Comme ça
        //   l'onglet (FR vs VOSTFR vs Films) pré-coche les bons filtres.
        val lang = lastLanguageFilter
        val type = lastTypeFilter
        // 2026-06-20 (user a partagé l'URL site avec `langue[]=VF` MAJ) :
        //   le serveur AnimeSama est case-sensitive sur "VF" (majuscule),
        //   "vf" minuscule retourne 0 résultats. VOSTFR est insensible.
        val langParam = if (lang.equals("vf", ignoreCase = true)) "VF" else lang
        val sb = StringBuilder("${baseUrl}catalogue/?genre[]=$id&page=$page")
        if (!langParam.isNullOrEmpty()) sb.append("&langue[]=$langParam")
        if (!type.isNullOrEmpty()) sb.append("&type[]=$type")
        val url = sb.toString()
        Log.d(TAG, "[getGenre] id=$id page=$page lang=$lang type=$type → $url")
        val document = fetchDocument(url)
        return Genre(
            id = id,
            name = id.replaceFirstChar { it.uppercase() },
            shows = document.select(".catalog-card").flatMap { card ->
                // Skip scans — l'user veut uniquement Anime/Film/Autres
                val typeText = card.select(".type-row .info-value").joinToString { it.text().trim() }
                if (typeText.contains("Scans", ignoreCase = true)) return@flatMap emptyList<Show>()

                val link = card.selectFirst("a[href*=/catalogue/]") ?: return@flatMap emptyList<Show>()
                val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                    .split("/").firstOrNull() ?: return@flatMap emptyList<Show>()
                val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@flatMap emptyList<Show>()
                val img = optimizeImageUrl(card.selectFirst("img")?.attr("src"), slug)
                // 2026-06-20 (user "sur FR il y a rien") : MoviesViewModel
                //   filterIsInstance<Movie>() et TvShowsViewModel
                //   filterIsInstance<TvShow>(). Donc on émet UN Movie ET UN
                //   TvShow pour chaque item — chaque viewmodel garde celui
                //   qui matche son type. L'id est suffixé "@vf" ou "@vostfr"
                //   selon la langue active pour que getMovie/getTvShow sache
                //   quelle saison/langue ouvrir au clic.
                val suffix = if (lang.equals("vf", ignoreCase = true)) "vf" else "vostfr"
                val movieId = if (type == "Film") "$slug@$suffix-film0" else "$slug@$suffix"
                val movie = Movie(id = movieId, title = title, poster = img)
                if (type == "Anime") movie.isSeries = true
                val tvShow = TvShow(id = "$slug@$suffix", title = title, poster = img)
                if (type == "Film") tvShow.isMovie = true
                listOf(movie, tvShow)
            },
        )
    }

    // ========== PEOPLE ==========

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = id)
    }

    // ========== URL MANAGEMENT ==========

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                try {
                    Log.d(TAG, "Refreshing URL from portal: $portalUrl")
                    // Fetch the portal page at anime-sama.pw
                    val portalDoc = withContext(Dispatchers.IO) {
                        val request = Request.Builder()
                            .url(portalUrl)
                            .header("User-Agent", USER_AGENT)
                            .build()
                        val response = client.newCall(request).execute()
                        val html = response.body?.string() ?: ""
                        Jsoup.parse(html)
                    }

                    // Extract the redirect link from "Accéder à Anime-Sama" button
                    val redirectLink = portalDoc.selectFirst("a.btn-primary[href*=anime-sama]")
                        ?.attr("href")

                    if (!redirectLink.isNullOrEmpty()) {
                        // Follow the redirect to get the final active domain
                        val finalUrl = withContext(Dispatchers.IO) {
                            val request = Request.Builder()
                                .url(redirectLink)
                                .header("User-Agent", USER_AGENT)
                                .build()
                            val response = client.newCall(request).execute()
                            // The response URL after following redirects is the active domain
                            val url = response.request.url.toString()
                            response.close()
                            url
                        }

                        if (finalUrl.contains("anime-sama")) {
                            val normalizedUrl = if (finalUrl.endsWith("/")) finalUrl else "$finalUrl/"
                            Log.d(TAG, "Resolved active URL: $normalizedUrl")
                            UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, normalizedUrl)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh URL from portal: ${e.message}")
                    // Keep using current URL on failure
                }
            }
            service = Service.build(baseUrl)
            serviceInitialized = true
        }
        return baseUrl
    }

    private val initializationMutex = Mutex()

    private suspend fun initializeService() {
        initializationMutex.withLock {
            if (serviceInitialized) return
            onChangeUrl()
        }
    }

    // 2026-06-07 (user "anime-sama a retiré sa vérification Cloudflare,
    //   reverse pour qu'il soit comme avant à l'ouverture") : restauration
    //   du provider à son état pré-bypass (v1.7.198 = commit 1f9227de). Les
    //   2 stubs ci-dessous sont des no-op qui gardent la surface API pour
    //   MainMobileActivity / MainTvActivity / ProviderViewHolder /
    //   ProviderChangeNotifier qui appellent encore init/resetState. Le
    //   backup avec bypass CF est dans le Bureau (streamflix_backups/).
    fun init(context: android.content.Context) { /* no-op */ }
    fun resetState() { /* no-op */ }

    // ========== SERVICE INTERFACE ==========

    private interface Service {
        @GET("catalogue/{id}/")
        suspend fun getShow(@Path("id") id: String): Document

        @GET
        suspend fun getPage(@Url url: String): Document

        companion object {
            fun build(baseUrl: String): Service {
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }
    }
}
