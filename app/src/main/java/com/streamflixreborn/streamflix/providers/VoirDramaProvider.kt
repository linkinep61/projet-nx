package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
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
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

object VoirDramaProvider : Provider, ProviderConfigUrl {

    override val name = "VoirDrama"

    override val defaultBaseUrl: String = "https://voirdrama.to/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }

    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_voirdrama"

    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: VoirDramaService
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    // ==================== DRAMACOOL FALLBACK SOURCE ====================
    //
    // 2026-05-04 : Dramacool9.com.ro est utilisé en SOURCE COMPLEMENTAIRE
    // (pas un provider standalone). Il étend le catalogue avec du contenu
    // asiatique sub anglais que VoirDrama n'a pas (K/C/J-dramas, K-shows,
    // Asian movies). Les items Dramacool sont préfixés "dc::" pour que les
    // méthodes du provider sachent à quelle source aller chercher.
    //
    // ID format pour les items Dramacool :
    //   dc::{slug}           → show (ex: dc::pearl-in-red-2026)
    //   dc::{slug}/{ep_num}  → épisode

    private const val DC_PREFIX = "dc::"
    // 2026-05-04 : URLs primaire + secours pour Dramacool. Si dramacool9.com.ro
    // tombe (saisie domaine, panne CF), on tente .so en fallback. Le résolveur
    // mémorise le premier qui répond pour la session.
    private val DC_BASE_URLS = listOf(
        "https://dramacool9.com.ro/",
        "https://dramacool.so/",
    )
    @Volatile private var dcResolvedBaseUrl: String? = null
    private val dcResolveMutex = Mutex()

    /** Retourne la première URL Dramacool qui répond 200 avec contenu valide.
     *  Mémorisé pour la session — si le primaire répond une fois, on garde. */
    private suspend fun dcBaseUrl(): String {
        dcResolvedBaseUrl?.let { return it }
        return dcResolveMutex.withLock {
            dcResolvedBaseUrl?.let { return@withLock it }
            for (candidate in DC_BASE_URLS) {
                try {
                    val doc = service.getPage(candidate)
                    // Heuristique : si la page contient au moins 1 a.mask
                    // (notre selecteur de listing), c'est un vrai Dramacool
                    if (doc.select("a.mask").isNotEmpty()) {
                        Log.d("VoirDramaProvider", "Dramacool base URL resolved to: $candidate")
                        dcResolvedBaseUrl = candidate
                        return@withLock candidate
                    }
                } catch (e: Exception) {
                    Log.w("VoirDramaProvider", "DC mirror $candidate unreachable: ${e.message}")
                }
            }
            // Tous les mirrors KO — on retourne le primaire pour que les
            // requêtes failent proprement (au lieu de boucler à l'infini)
            Log.w("VoirDramaProvider", "All DC mirrors unreachable, using primary anyway")
            DC_BASE_URLS.first()
        }
    }

    private fun isDramacool(id: String) = id.startsWith(DC_PREFIX)
    private fun dcStripPrefix(id: String) = id.removePrefix(DC_PREFIX)

    /** Extrait l'URL d'une img en gérant le lazy-loading data-original (Dramacool). */
    private fun dcExtractImgUrl(item: Element): String? {
        val img = item.selectFirst("img") ?: return null
        return sequenceOf("data-original", "data-src", "data-lazy-src", "src")
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
    }

    /** Extrait le slug-id Dramacool depuis une URL. */
    private fun dcExtractSlug(href: String): String? {
        val cleanHref = href.removeSuffix("/").substringAfterLast("/")
        return when {
            cleanHref.contains("-episode-") -> cleanHref.substringBefore("-episode-")
            cleanHref.endsWith(".html") -> cleanHref.removeSuffix(".html")
            else -> cleanHref.takeIf { it.isNotBlank() }
        }
    }

    private fun dcExtractEpNumber(text: String): Int? {
        val patterns = listOf(
            Regex("""-episode-(\d+)\.html""", RegexOption.IGNORE_CASE),
            Regex("""\bEpisode\s+(\d+)\b""", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    /**
     * Normalise les caractères Unicode "typographiques" en ASCII équivalent.
     *
     * 2026-05-05 : bug repro — VoirDrama retourne `Yumi's Cells 3` avec
     * une apostrophe `'` (U+2019) au lieu de `'` ASCII. Quand on URL-encode
     * pour la search Dramacool, ça devient `Yumi%E2%80%99s+Cells+3` qui
     * matche 0 résultat alors que `Yumi's+Cells+3` retourne le show.
     */
    private fun normalizeUnicodeChars(s: String): String {
        return s
            .replace('’', '\'')   // ' → '
            .replace('‘', '\'')   // ' → '
            .replace('“', '"')    // " → "
            .replace('”', '"')    // " → "
            .replace('–', '-')    // – → -
            .replace('—', '-')    // — → -
            .replace('…', ' ')    // … → space (pour search)
    }

    /** Search Dramacool9 et retourne des TvShow avec ID préfixé dc::. */
    private suspend fun searchOnDramacool(query: String): List<TvShow> {
        if (query.isBlank()) return emptyList()
        return try {
            val cleanQuery = normalizeUnicodeChars(query)
            val url = "${dcBaseUrl()}?s=${cleanQuery.replace(" ", "+")}"
            val doc = service.getPage(url)
            doc.select("a.mask").mapNotNull { link ->
                val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val slug = dcExtractSlug(href) ?: return@mapNotNull null
                val parent = link.parent() ?: link
                val title = link.attr("title").takeIf { it.isNotBlank() }
                    ?: parent.selectFirst("h3")?.text()
                    ?: return@mapNotNull null
                val cleanTitle = title.replace(
                    Regex("""\s+Episode\s+\d+\s*$""", RegexOption.IGNORE_CASE), ""
                ).trim()
                val poster = dcExtractImgUrl(parent)
                TvShow(
                    id = "$DC_PREFIX$slug",
                    title = cleanTitle,
                    poster = poster,
                )
            }
        } catch (e: Exception) {
            Log.w("VoirDramaProvider", "Dramacool search '$query' failed: ${e.message}")
            emptyList()
        }
    }

    /** Récupère le détail TvShow Dramacool depuis le slug. */
    private suspend fun getTvShowFromDramacool(slug: String): TvShow {
        val url = "${dcBaseUrl()}$slug"
        val doc = try { service.getPage(url) } catch (_: Exception) {
            return TvShow(id = "$DC_PREFIX$slug", title = slug.replace("-", " "))
        }
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }
        val poster = doc.selectFirst("article img, .single-info img, div.image img")
            ?.let { dcExtractImgUrl(it.parent() ?: it) }
        val overview = doc.selectFirst(".desc-content, .single-info p, .description p")?.text()?.trim()
        val genres = doc.select("a[href*='/genre/']").map {
            Genre(
                id = it.attr("href").substringAfterLast("/genre/").removeSuffix("/"),
                name = it.text().trim(),
            )
        }
        return TvShow(
            id = "$DC_PREFIX$slug",
            title = title,
            overview = overview,
            poster = poster,
            banner = poster,
            genres = genres,
            seasons = listOf(Season(id = "$DC_PREFIX$slug", number = 1, title = "Season 1")),
        )
    }

    /** Liste des épisodes pour un show Dramacool depuis le bloc #all-episodes. */
    private suspend fun getEpisodesFromDramacool(slug: String): List<Episode> {
        val url = "${dcBaseUrl()}$slug"
        val doc = try { service.getPage(url) } catch (_: Exception) {
            return emptyList()
        }
        return doc.select("#all-episodes a, #episode-list a").mapNotNull { link ->
            val href = link.attr("href")
            val number = dcExtractEpNumber(href) ?: return@mapNotNull null
            Episode(
                id = "$DC_PREFIX$slug/$number",
                number = number,
                title = "Episode $number",
            )
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    /** Récupère les Video.Server depuis une page épisode Dramacool. */
    /**
     * 2026-05-05 : Cascade d'URL patterns pour gérer 4 cas réels de Dramacool :
     *   1. Série classique multi-épisodes : `{slug}-episode-{ep}.html`
     *   2. Film unique (pas de "-episode-X" sur le site) : `{slug}.html`
     *   3. Film avec slug nu : `{slug}` (pas de suffixe)
     *   4. Drama avec encoding différent : `{slug}-episode-1.html` retourne
     *      un silent-200 (page homepage) — détectable via title qui ne contient
     *      pas le slug normalisé.
     *
     * On tente le pattern "épisode" en premier, mais si la page retournée
     * est la homepage (silent 404), on fallback sur le slug nu (cas film).
     * Le check est fait via title : page valide a "Episode N" ou le titre
     * du show, page d'accueil a juste "Dramacool | Kdrama...".
     */
    private suspend fun getServersFromDramacool(slug: String, ep: Int): List<Video.Server> {
        val candidates = listOf(
            "${dcBaseUrl()}$slug-episode-$ep.html",  // Série
            "${dcBaseUrl()}$slug.html",                // Film .html
            "${dcBaseUrl()}$slug",                     // Film slug nu
        )

        for (url in candidates) {
            val out = try {
                val doc = service.getPage(url)
                // Détection silent-404 : le title de la homepage Dramacool est
                // "Dramacool | Kdrama, Movies & Shows ..." (sans le slug ou
                // "Episode N"). Si title ressemble à la homepage, on skip ce
                // candidat et on essaie le suivant.
                val title = doc.title()
                val titleLooksValid = title.contains("Episode", ignoreCase = true) ||
                    title.contains(slug.replace("-", " "), ignoreCase = true) ||
                    !title.startsWith("Dramacool |")
                if (!titleLooksValid) {
                    Log.d("VoirDramaProvider", "DC URL silent-404: $url (title='$title')")
                    continue
                }

                val list = mutableListOf<Video.Server>()
                val seen = HashSet<String>()
                doc.select("button.server-btn[data-src]").forEach { btn ->
                    val src = btn.attr("data-src").takeIf { it.startsWith("http") } ?: return@forEach
                    if (!seen.add(src)) return@forEach
                    val name = btn.text().replace("▶", "").trim().ifBlank { dcHostShort(src) }
                    list.add(Video.Server(id = "dc_$src", name = "$name (DC)", src = src))
                }
                doc.select("iframe#video-frame, iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src").takeIf { it.startsWith("http") } ?: return@forEach
                    if (!seen.add(src)) return@forEach
                    list.add(Video.Server(id = "dc_$src", name = "${dcHostShort(src)} (DC)", src = src))
                }
                list
            } catch (e: Exception) {
                Log.w("VoirDramaProvider", "Dramacool $url failed: ${e.message}")
                emptyList()
            }
            if (out.isNotEmpty()) {
                Log.d("VoirDramaProvider", "DC servers via $url : ${out.size}")
                return out
            }
        }

        Log.w("VoirDramaProvider", "DC: no servers for slug='$slug' ep=$ep (tried ${candidates.size} URLs)")
        return emptyList()
    }

    private fun dcHostShort(url: String): String =
        try {
            java.net.URL(url).host.split(".").first { it != "www" }
                .replaceFirstChar { it.uppercase() }
        } catch (_: Exception) { "Server" }

    /** Cherche le show sur Dramacool par titre, et si trouvé fetch les
     *  servers de l'épisode demandé. Utilisé pour ENRICHIR les servers
     *  VoirDrama natifs (au cas où ceux-ci sont morts/lents).
     *
     *  Retourne une liste vide silencieusement si :
     *   - le titre est trop court (< 3 chars) → trop générique
     *   - aucun match titre sur Dramacool
     *   - l'épisode demandé n'existe pas sur DC
     *   - une erreur HTTP arrive (best-effort, on loggue puis ignore)
     */
    private suspend fun fetchDramacoolServersByTitle(
        title: String,
        episodeNumber: Int,
        year: Int? = null,
        seasonNumber: Int? = null,
    ): List<Video.Server> {
        val cleanTitle = normalizeUnicodeChars(title.trim())
        if (cleanTitle.length < 3) return emptyList()

        return try {
            fun normalize(s: String) = normalizeUnicodeChars(s).lowercase()
                .replace(Regex("""\s*\(\d{4}\)\s*"""), " ")
                .replace(Regex("""[^a-z0-9\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            val targetWords = normalize(cleanTitle).split(" ").filter { it.length >= 3 }.toSet()
            val targetNorm = normalize(cleanTitle)
            val candidates = searchOnDramacool(cleanTitle)

            if (candidates.isEmpty()) {
                Log.d("VoirDramaProvider", "DC enrich: 0 search results for '$cleanTitle'")
                return emptyList()
            }

            // Score chaque candidat sur 4 critères :
            //   - Match exact normalisé : +100
            //   - DC title contient TOUS les mots cibles : +50
            //   - Année correspondante (si fournie) : +30
            //   - Saison correspondante (si fournie) : +30
            data class Scored(val show: TvShow, val score: Int)
            val scored = candidates.map { dc ->
                val dcNorm = normalize(dc.title)
                val dcWords = dcNorm.split(" ").filter { it.length >= 3 }.toSet()
                var score = 0
                if (dcNorm == targetNorm) score += 100
                if (dcWords.containsAll(targetWords)) score += 50
                if (year != null && dc.title.contains("($year)")) score += 30
                if (seasonNumber != null && dcNorm.contains("season $seasonNumber")) score += 30
                Scored(dc, score)
            }.filter { it.score > 0 }
                .sortedByDescending { it.score }

            if (scored.isEmpty()) {
                Log.d("VoirDramaProvider", "DC enrich: no scored match for '$cleanTitle' " +
                    "(${candidates.size} candidates: ${candidates.take(3).map { it.title }})")
                return emptyList()
            }

            // Essaie chaque candidat dans l'ordre du score, retourne le premier
            // qui yield des servers réels. Évite de tomber sur "Squid Game The
            // Challenge" quand l'utilisateur regarde "Squid Game Season 3".
            for (sc in scored.take(5)) {
                val slug = dcStripPrefix(sc.show.id)
                Log.d("VoirDramaProvider", "DC enrich: trying '${sc.show.title}' (score=${sc.score}, slug='$slug') ep=$episodeNumber")
                val servers = getServersFromDramacool(slug, episodeNumber)
                if (servers.isNotEmpty()) {
                    Log.d("VoirDramaProvider", "DC enrich: matched '${sc.show.title}' → ${servers.size} servers")
                    return servers
                }
            }
            Log.d("VoirDramaProvider", "DC enrich: ${scored.size} candidates scored but none returned servers for ep=$episodeNumber")
            emptyList()
        } catch (e: Exception) {
            Log.w("VoirDramaProvider", "DC enrich for '$cleanTitle' E$episodeNumber failed: ${e.message}")
            emptyList()
        }
    }

    // ==================== HOME ====================

    override suspend fun getHome(): List<Category> {
        initializeService()
        return try {
            coroutineScope {
                val homeDeferred = async { service.getPage(baseUrl) }
                val recentDeferred = async {
                    try { service.getPage("${baseUrl}nouveaux-ajouts/") } catch (_: Exception) { null }
                }
                val popularDeferred = async {
                    try { service.getPage("${baseUrl}drama/?m_orderby=views") } catch (_: Exception) { null }
                }
                val popularFilmsDeferred = async {
                    try { service.getPage("${baseUrl}liste-dramas/?filter=dubbed&m_orderby=views") } catch (_: Exception) { null }
                }
                val topRatedDeferred = async {
                    try { service.getPage("${baseUrl}drama/?m_orderby=rating") } catch (_: Exception) { null }
                }

                val document = homeDeferred.await()
                val categories = mutableListOf<Category>()

                // Section principale "EN COURS" — .page-item-detail items
                val allItems = document.select(".page-item-detail").mapNotNull { item ->
                    parseHomeItem(item)
                }

                if (allItems.isNotEmpty()) {
                    // FEATURED carousel with deep copies to avoid shared itemType conflicts
                    val featuredItems = allItems.take(10).map { item ->
                        when (item) {
                            is Movie -> item.copy(banner = item.banner ?: item.poster)
                            is TvShow -> item.copy(banner = item.banner ?: item.poster)
                            else -> item
                        }
                    }
                    // Enhance banners with TMDB HD backdrops (filter Asian content only)
                    val asianLanguages = setOf("ko", "ja", "zh", "th", "tl")
                    if (UserPreferences.enableTmdb) {
                        for (item in featuredItems) {
                            try {
                                val title = when (item) {
                                    is Movie -> item.title
                                    is TvShow -> item.title
                                    else -> null
                                } ?: continue
                                // 2026-05-05 : normalise titres scrappés HTML (apostrophes typographiques etc.)
                                val cleanTitle = com.streamflixreborn.streamflix.utils.TitleNormalizer
                                    .cleanForTmdbSearch(title).ifBlank { title }
                                val results = TMDb3.Search.multi(cleanTitle)
                                val match = results.results.firstOrNull { result ->
                                    when (result) {
                                        is TMDb3.Movie -> result.originalLanguage in asianLanguages && result.backdropPath != null
                                        is TMDb3.Tv -> result.originalLanguage in asianLanguages && result.backdropPath != null
                                        else -> false
                                    }
                                }
                                val banner = when (match) {
                                    is TMDb3.Movie -> match.backdropPath?.original
                                    is TMDb3.Tv -> match.backdropPath?.original
                                    else -> null
                                }
                                if (banner != null) {
                                    when (item) {
                                        is Movie -> item.banner = banner
                                        is TvShow -> item.banner = banner
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    categories.add(Category(name = Category.FEATURED, list = featuredItems))
                    categories.add(Category(name = "En cours", list = allItems))
                }

                // 2. Nouveaux ajouts
                val recentDoc = recentDeferred.await()
                if (recentDoc != null) {
                    val recentShows = recentDoc.select(".page-item-detail").mapNotNull { item ->
                        parseHomeItem(item)
                    }
                    if (recentShows.isNotEmpty()) {
                        categories.add(Category(name = "Nouveau", list = recentShows))
                    }
                }

                // 3. Séries populaires (par vues)
                val popularDoc = popularDeferred.await()
                if (popularDoc != null) {
                    val popularShows = popularDoc.select(".page-item-detail").mapNotNull { item ->
                        parseHomeItem(item)
                    }
                    if (popularShows.isNotEmpty()) {
                        categories.add(Category(name = "Séries Populaires", list = popularShows))
                    }
                }

                // 4. Films populaires (VF, triés par vues)
                val popularFilmsDoc = popularFilmsDeferred.await()
                if (popularFilmsDoc != null) {
                    val popularFilms = popularFilmsDoc.select(".page-item-detail").mapNotNull { item ->
                        val show = parseHomeItem(item) ?: return@mapNotNull null
                        when (show) {
                            is TvShow -> Movie(id = show.id, title = show.title, poster = show.poster).apply { isSeries = true }
                            is Movie -> show
                            else -> null
                        }
                    }
                    if (popularFilms.isNotEmpty()) {
                        categories.add(Category(name = "Films Populaires", list = popularFilms))
                    }
                }

                // 5. Mieux notés (par note)
                val topRatedDoc = topRatedDeferred.await()
                if (topRatedDoc != null) {
                    val topRated = topRatedDoc.select(".page-item-detail").mapNotNull { item ->
                        parseHomeItem(item)
                    }
                    if (topRated.isNotEmpty()) {
                        categories.add(Category(name = "Mieux Notés", list = topRated))
                    }
                }

                categories
            }
        } catch (e: Exception) {
            Log.e("VoirDramaProvider", "getHome error: ", e)
            emptyList()
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            // Genres réels du site voirdrama.to
            return listOf(
                Genre(id = "action", name = "Action"),
                Genre(id = "affaires", name = "Affaires"),
                Genre(id = "amitie", name = "Amitié"),
                Genre(id = "arts-martiaux", name = "Arts martiaux"),
                Genre(id = "aventure", name = "Aventure"),
                Genre(id = "comedie", name = "Comédie"),
                Genre(id = "crime", name = "Crime"),
                Genre(id = "drame", name = "Drame"),
                Genre(id = "famille", name = "Famille"),
                Genre(id = "fantastique", name = "Fantastique"),
                Genre(id = "historique", name = "Historique"),
                Genre(id = "horreur", name = "Horreur"),
                Genre(id = "melodrame", name = "Mélodrame"),
                Genre(id = "mystere", name = "Mystère"),
                Genre(id = "psychologique", name = "Psychologique"),
                Genre(id = "romance", name = "Romance"),
                Genre(id = "sf", name = "SF"),
                Genre(id = "surnaturel", name = "Surnaturel"),
                Genre(id = "thriller", name = "Thriller"),
                Genre(id = "vie-quotidienne", name = "Vie quotidienne"),
                Genre(id = "wuxia", name = "Wuxia"),
                Genre(id = "k-drama", name = "K-Drama"),
            )
        }

        initializeService()
        return try {
            // 2026-05-04 : search croisé VoirDrama + Dramacool9 en parallèle.
            // Les items Dramacool sont préfixés "dc::" pour le routing.
            // Dédup par titre normalisé pour éviter les doublons quand la
            // même série existe sur les deux sites.
            coroutineScope {
                val voirDramaDeferred = async {
                    try { parseSearchResults(service.search(query, page)) }
                    catch (e: Exception) {
                        Log.w("VoirDramaProvider", "VoirDrama search failed: ${e.message}")
                        emptyList()
                    }
                }
                // Dramacool seulement sur page 1 (pas de pagination cross-site)
                val dramacoolDeferred = async {
                    if (page <= 1) searchOnDramacool(query) else emptyList()
                }
                val voirDramaResults = voirDramaDeferred.await()
                val dramacoolResults = dramacoolDeferred.await()

                // Normalise les titres pour dédup
                fun normalize(s: String) = s.lowercase()
                    .replace(Regex("""\s*\(\d{4}\)\s*"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                val seenTitles = voirDramaResults.mapNotNull {
                    when (it) {
                        is Movie -> normalize(it.title)
                        is TvShow -> normalize(it.title)
                        else -> null
                    }
                }.toMutableSet()
                val mergedDc = dramacoolResults.filter { dc ->
                    seenTitles.add(normalize(dc.title))
                }
                Log.d("VoirDramaProvider", "search '$query' p$page : VD=${voirDramaResults.size}, DC=${dramacoolResults.size}, merged=${mergedDc.size}")
                voirDramaResults + mergedDc
            }
        } catch (e: Exception) {
            Log.e("VoirDramaProvider", "search error: ", e)
            emptyList()
        }
    }

    // ==================== MOVIES / TV SHOWS ====================

    override suspend fun getMovies(page: Int): List<Movie> = coroutineScope {
        // Onglet "FR" — contenu VF via liste-dramas/?filter=dubbed
        initializeService()
        try {
            val url = if (page > 1) "${baseUrl}liste-dramas/page/$page/?filter=dubbed"
                      else "${baseUrl}liste-dramas/?filter=dubbed"
            val document = service.getPage(url)
            val items = document.select(".page-item-detail").mapNotNull { item ->
                val show = parseHomeItem(item) ?: return@mapNotNull null
                val id = when (show) { is Movie -> show.id; is TvShow -> show.id; else -> return@mapNotNull null }
                val title = when (show) { is Movie -> show.title; is TvShow -> show.title; else -> return@mapNotNull null }
                val poster = when (show) { is Movie -> show.poster; is TvShow -> show.poster; else -> return@mapNotNull null }
                Triple(id, title, poster)
            }

            // Vérifier chaque page détail en parallèle (batches de 5)
            // >1 épisode = série, ≤1 épisode = film
            items.chunked(5).flatMap { batch ->
                batch.map { (id, title, poster) ->
                    async {
                        val hasMultipleEpisodes = try {
                            val detailDoc = service.getPage("${baseUrl}drama/$id/")
                            detailDoc.select("li.wp-manga-chapter").size > 1
                        } catch (e: Exception) { false }
                        Movie(id = id, title = title, poster = poster).apply { isSeries = hasMultipleEpisodes }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            Log.e("VoirDramaProvider", "getMovies error: ", e)
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = coroutineScope {
        // Onglet "VOSTFR" — via liste-dramas/?filter=subbed
        initializeService()
        try {
            val url = if (page > 1) "${baseUrl}liste-dramas/page/$page/?filter=subbed"
                      else "${baseUrl}liste-dramas/?filter=subbed"
            val document = service.getPage(url)
            val items = document.select(".page-item-detail").mapNotNull { item ->
                val show = parseHomeItem(item) ?: return@mapNotNull null
                val id = when (show) { is Movie -> show.id; is TvShow -> show.id; else -> return@mapNotNull null }
                val title = when (show) { is Movie -> show.title; is TvShow -> show.title; else -> return@mapNotNull null }
                val poster = when (show) { is Movie -> show.poster; is TvShow -> show.poster; else -> return@mapNotNull null }
                Triple(id, title, poster)
            }

            // Vérifier chaque page détail en parallèle (batches de 5)
            items.chunked(5).flatMap { batch ->
                batch.map { (id, title, poster) ->
                    async {
                        val hasMultipleEpisodes = try {
                            val detailDoc = service.getPage("${baseUrl}drama/$id/")
                            detailDoc.select("li.wp-manga-chapter").size > 1
                        } catch (e: Exception) { true }
                        TvShow(id = id, title = title, poster = poster).apply { isMovie = !hasMultipleEpisodes }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            Log.e("VoirDramaProvider", "getTvShows error: ", e)
            emptyList()
        }
    }



    // ==================== DETAIL ====================

    override suspend fun getMovie(id: String): Movie {
        initializeService()
        // Item Dramacool : on traite comme un TvShow car Dramacool n'a pas de
        // distinction movie/show propre. Les "movies" sont en fait des
        // dramas single-episode chez eux. On retourne donc un Movie minimal.
        if (isDramacool(id)) {
            val show = getTvShowFromDramacool(dcStripPrefix(id))
            return Movie(
                id = id,
                title = show.title,
                overview = show.overview,
                poster = show.poster,
                banner = show.banner,
                genres = show.genres,
            )
        }
        val url = if (id.startsWith("http")) id else "${baseUrl}drama/$id/"
        val document = service.getPage(url)

        val title = document.selectFirst(".post-title h1, .post-title h3")
            ?.text()?.trim() ?: ""
        val poster = improveImageUrl(
            document.selectFirst(".summary_image img")?.attr("src")
        )
        val overview = document.selectFirst(".description-summary .summary__content")
            ?.text()?.trim()

        val genres = document.select(".genres-content a").map {
            Genre(
                id = it.attr("href").trimEnd('/').substringAfterLast("/"),
                name = it.text().trim()
            )
        }

        // Extraire la note depuis .score
        val rating = document.selectFirst(".score.font-meta.total_votes")
            ?.text()?.trim()?.toDoubleOrNull()

        // Extraire les infos depuis .post-content_item
        val released = getInfoValue(document, "Start date")
            ?: getInfoValue(document, "Année")

        val cast = document.select(".artist-content a").mapIndexed { index, el ->
            People(id = "cast$index", name = el.text().trim())
        }

        return Movie(
            id = id,
            title = title,
            poster = poster,
            overview = overview,
            genres = genres,
            rating = rating,
            released = released,
            cast = cast
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        initializeService()
        if (isDramacool(id)) return getTvShowFromDramacool(dcStripPrefix(id))
        val url = if (id.startsWith("http")) id else "${baseUrl}drama/$id/"
        val document = service.getPage(url)

        val title = document.selectFirst(".post-title h1, .post-title h3")
            ?.text()?.trim() ?: ""
        val poster = improveImageUrl(
            document.selectFirst(".summary_image img")?.attr("src")
        )
        val overview = document.selectFirst(".description-summary .summary__content")
            ?.text()?.trim()

        val genres = document.select(".genres-content a").map {
            Genre(
                id = it.attr("href").trimEnd('/').substringAfterLast("/"),
                name = it.text().trim()
            )
        }

        val rating = document.selectFirst(".score.font-meta.total_votes")
            ?.text()?.trim()?.toDoubleOrNull()

        val released = getInfoValue(document, "Start date")
            ?: getInfoValue(document, "Année")

        val cast = document.select(".artist-content a").mapIndexed { index, el ->
            People(id = "cast$index", name = el.text().trim())
        }

        // Récupérer les épisodes — sur VoirDrama ils sont chargés inline dans .listing-chapters_wrap
        val episodes = document.select("li.wp-manga-chapter")

        // Sur VoirDrama, pas de saisons multiples — une seule saison avec tous les épisodes
        val season = Season(
            id = id,
            number = 1,
            title = title,
            poster = poster
        )

        return TvShow(
            id = id,
            title = title,
            poster = poster,
            overview = overview,
            genres = genres,
            rating = rating,
            released = released,
            cast = cast,
            seasons = if (episodes.isNotEmpty()) listOf(season) else emptyList()
        )
    }

    // ==================== EPISODES ====================

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        initializeService()
        if (isDramacool(seasonId)) return getEpisodesFromDramacool(dcStripPrefix(seasonId))
        val url = if (seasonId.startsWith("http")) seasonId else "${baseUrl}drama/$seasonId/"
        val document = service.getPage(url)

        // Récupérer le poster de la série pour l'utiliser comme thumbnail des épisodes
        val showPoster = improveImageUrl(
            document.selectFirst(".summary_image img")?.attr("src")
        )

        val episodeElements = document.select("li.wp-manga-chapter")

        // Les épisodes sont en ordre décroissant sur le site, on les inverse
        return episodeElements.reversed().mapIndexed { index, el ->
            val link = el.selectFirst("a")
            val epUrl = link?.attr("href") ?: ""
            val epTitle = link?.text()?.trim() ?: "Épisode ${index + 1}"
            // L'ID inclut le drama slug: "climax/climax-2026-10-vostfr"
            // pour reconstruire l'URL: ${baseUrl}drama/{epId}/
            val epId = epUrl.trimEnd('/').substringAfter("/drama/").trimEnd('/')
                .takeIf { it.isNotBlank() }
                ?: "$seasonId/ep-${index + 1}"

            // Extraire le numéro d'épisode — format: "Drama (2026) - 10 VOSTFR - 10"
            val epNumber = Regex("""[\s-]+(\d+)\s*(?:VOSTFR|VF|VO)""", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\s*$""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)

            val releaseDate = el.selectFirst(".chapter-release-date i")
                ?.text()?.trim()

            Episode(
                id = epId,
                number = epNumber,
                title = epTitle,
                poster = showPoster
            )
        }
    }

    // ==================== GENRE ====================

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService()
        return try {
            val url = if (page > 1) "${baseUrl}drama-genre/$id/page/$page/"
                      else "${baseUrl}drama-genre/$id/"
            val document = service.getPage(url)
            val shows = document.select(".page-item-detail").mapNotNull { item ->
                parseHomeItem(item)
            }
            Genre(
                id = id,
                name = id.replace("-", " ").replaceFirstChar { it.uppercase() },
                shows = shows
            )
        } catch (e: Exception) {
            Log.e("VoirDramaProvider", "getGenre error: ", e)
            Genre(id = id, name = id, shows = emptyList())
        }
    }

    // ==================== SERVERS / VIDEO ====================

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()
        // 2026-05-04 : Dramacool routing — IDs préfixés "dc::" sont servis par
        // les helpers Dramacool, pas par VoirDrama. Pour un movie DC on tape
        // sur episode-1.html, pour un épisode DC on construit l'URL via slug + ep.
        if (isDramacool(id)) {
            val stripped = dcStripPrefix(id)
            return when (videoType) {
                is Video.Type.Episode -> {
                    val parts = stripped.split("/")
                    val slug = parts.getOrElse(0) { stripped }
                    val ep = parts.getOrElse(1) { videoType.number.toString() }
                        .toIntOrNull() ?: videoType.number
                    getServersFromDramacool(slug, ep)
                }
                is Video.Type.Movie -> getServersFromDramacool(stripped, 1)
            }
        }
        return try {
            coroutineScope {
            // L'ID d'un épisode est le chemin complet sous /drama/ ex: "climax/climax-2026-10-vostfr"
            val url = when (videoType) {
                is Video.Type.Movie -> if (id.startsWith("http")) id else "${baseUrl}drama/$id/"
                is Video.Type.Episode -> if (id.startsWith("http")) id else "${baseUrl}drama/$id/"
            }
            val document = service.getPage(url)

            // 2026-05-04 : extraire le titre du show pour ENRICHIR avec les
            // sources Dramacool en parallèle. Si DC a la même série, on
            // ajoute ses players comme alternatives → l'utilisateur a plus
            // de chances qu'au moins un fonctionne.
            //
            // 2026-05-05 : on NETTOIE agressivement le titre (vire "Saison X",
            // "Épisode Y", "VOSTFR/VF/VO", année) — sinon DC search retourne 0
            // résultats car cherche le full title type "Climax Saison 1 Épisode 5".
            val rawTitle = document.selectFirst("h1.entry-title")?.text()?.trim()
                ?: document.title()
                    .substringBefore(" - ")
                    .substringBefore(" – ")
                    .trim()
                    .ifBlank { null }
            val showTitle = rawTitle?.let { t ->
                t.replace(Regex("""\s*[-–]?\s*Saison\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\s*[-–]?\s*[Ss]\d+\s*[Ee]\d+.*$"""), "")
                    .replace(Regex("""\s*[-–]?\s*[ÉéEe]pisode\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\s+(VOSTFR|VOST|VF|VO|FR)\b.*$""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
                    .trim()
                    .ifBlank { null }
            }
            val episodeNumber = (videoType as? Video.Type.Episode)?.number ?: 1
            val seasonNumber = (videoType as? Video.Type.Episode)?.season?.number
            // Extrait l'année du rawTitle si présent (cas "Show Name (2024)")
            val year = rawTitle?.let {
                Regex("""\((\d{4})\)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }
            val dramacoolServersDeferred = if (!showTitle.isNullOrBlank()) {
                Log.d("VoirDramaProvider", "DC enrich: rawTitle='$rawTitle' → cleaned='$showTitle' " +
                    "year=$year season=$seasonNumber E$episodeNumber")
                async { fetchDramacoolServersByTitle(showTitle, episodeNumber, year, seasonNumber) }
            } else null

            val servers = mutableListOf<Video.Server>()
            val seenSrcs = mutableSetOf<String>()

            // 1) Same Madara WP theme as VoirAnime: ALL players are stored in
            //    `var thisChapterSources = {"LECTEUR X":"<iframe src=...>...}` —
            //    the page only displays the first one. Without parsing this
            //    JS map we miss every alternate player and end up stuck on
            //    a single (often dead) embed.
            val html = document.outerHtml()
            // Same JS-aware regex as VoirAnime: tolerant to `\"` inside the
            // value (lazy match) AND restricted to `\/` only inside the URL
            // group (so the trailing `\"` doesn't leak into the captured URL
            // and break extractors with a literal `"` suffix).
            val srcRegex = Regex(
                "\"([^\"]*LECTEUR[^\"]*)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*?src=\\\\?[\"']?(https?:(?:[^\"'\\\\\\s]|\\\\/)+)",
                RegexOption.IGNORE_CASE
            )
            srcRegex.findAll(html).forEach { match ->
                val rawName = match.groupValues[1].trim()
                val rawSrc = match.groupValues[2]
                    .replace("\\/", "/")
                    .replace("\\\"", "\"")
                    .takeIf { it.startsWith("http") } ?: return@forEach
                if (!seenSrcs.add(rawSrc)) return@forEach
                val hostShort = try {
                    java.net.URL(rawSrc).host.split(".").first { it != "www" }
                        .replaceFirstChar { it.uppercase() }
                } catch (_: Exception) { "Lecteur" }
                servers.add(Video.Server(id = rawSrc, name = "$rawName ($hostShort)", src = rawSrc))
                Log.w("VoirDramaProvider", "JS source matched: $rawName -> $rawSrc")
            }
            Log.w("VoirDramaProvider", "After JS parse: ${servers.size} servers")

            // 2) DOM iframe fallback (older episodes / different page templates).
            document.select(".chapter-video-frame iframe, .reading-content iframe, .entry-content iframe, iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() && it.startsWith("http") } ?: return@forEach
                if (!seenSrcs.add(src)) return@forEach
                val serverName = try {
                    java.net.URL(src).host.split(".").first { it != "www" }
                        .replaceFirstChar { it.uppercase() }
                } catch (_: Exception) { "Lecteur" }
                servers.add(Video.Server(id = src, name = serverName, src = src))
            }

            // Détecter la langue dans le titre/URL de la page
            val pageTitle = document.title().lowercase()
            val pageUrl = url.lowercase()
            val lang = when {
                pageTitle.contains("vostfr") || pageUrl.contains("vostfr") -> "VOSTFR"
                pageTitle.contains("-vf") || pageUrl.contains("-vf") -> "VF"
                pageTitle.contains("vo ") || pageUrl.contains("-vo-") -> "VO"
                else -> "VOSTFR" // Par défaut pour les dramas coréens
            }

            // 2026-05-04 : reordered — mail.ru en premier car pas de
            // Cloudflare challenge, extraction quasi-instantanée. vidmoly
            // est descendu à priorité 5 car CF Turnstile prend ~10-20s.
            // Filemoon / yourupload / voe en priorité moyenne car ont
            // souvent leur propre captcha mais sont plus stables que vidmoly
            // depuis que CF est en place.
            val priority = mapOf(
                "mail.ru" to 0, "my.mail.ru" to 0,  // Pas de CF, rapide
                "voe" to 1, "voe.sx" to 1,
                "filemoon" to 2, "weneverbeenfree" to 2,
                "yourupload" to 3, "www.yourupload.com" to 3,
                "streamtape" to 4,
                "vidmoly" to 5,  // CF challenge → lent, dernière option
                "streamhide" to 6
            )
            val withLang = servers.map { server ->
                if (!server.name.contains("VF") && !server.name.contains("VOSTFR") && !server.name.contains("VO")) {
                    Video.Server(id = server.id, name = "${server.name} ($lang)", src = server.src)
                } else server
            }.distinctBy { it.id }
            val voirDramaSorted = withLang.sortedBy { server ->
                val host = try { java.net.URL(server.src).host.lowercase() } catch (_: Exception) { "" }
                priority.entries.firstOrNull { host.contains(it.key) }?.value ?: 50
            }

            // 2026-05-04 : on attend le résultat de l'enrichissement DC et on
            // l'ajoute en QUEUE de liste (servers VoirDrama d'abord, DC en
            // backup). Timeout de 5s pour pas bloquer le player si DC traîne.
            val dcServers = dramacoolServersDeferred?.let { deferred ->
                try {
                    kotlinx.coroutines.withTimeoutOrNull(5_000L) { deferred.await() } ?: emptyList()
                } catch (_: Exception) { emptyList() }
            } ?: emptyList()
            // Dédup : si DC pointe vers une URL déjà dans VoirDrama, skip
            val existingSrcs = voirDramaSorted.map { it.src }.toHashSet()
            val dcUnique = dcServers.filter { it.src !in existingSrcs }
            if (dcUnique.isNotEmpty()) {
                Log.d("VoirDramaProvider", "DC enrich: added ${dcUnique.size} backup servers")
            }

            // 2026-05-06 : Cloudstream backup #2 — pour les K-Dramas, Cloudstream
            // (MovieBox+) couvre une partie du catalogue avec audio/sub FR.
            val cloudstreamBackup = if (!showTitle.isNullOrBlank()) {
                try {
                    val year = document.selectFirst("h1.entry-title")?.text()?.let {
                        Regex("""\((\d{4})\)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
                    }
                    val csVideoType = when (videoType) {
                        is Video.Type.Movie -> Video.Type.Movie(
                            id = "0", title = showTitle,
                            releaseDate = year?.toString() ?: "",
                            poster = videoType.poster, imdbId = videoType.imdbId,
                        )
                        is Video.Type.Episode -> Video.Type.Episode(
                            id = "0", number = videoType.number,
                            title = videoType.title, poster = videoType.poster,
                            overview = videoType.overview,
                            tvShow = videoType.tvShow.copy(
                                id = "0", title = showTitle,
                                releaseDate = year?.toString() ?: videoType.tvShow.releaseDate,
                            ),
                            season = videoType.season,
                        )
                    }
                    CloudstreamProvider.getServers("0", csVideoType)
                        .also {
                            if (it.isNotEmpty()) Log.d("VoirDramaProvider",
                                "+ Cloudstream backup pour '$showTitle' : ${it.size} sources")
                        }
                } catch (e: Exception) {
                    Log.d("VoirDramaProvider", "Cloudstream backup failed: ${e.message}")
                    emptyList()
                }
            } else emptyList()

            // 2026-05-05 : Moviebox backup pour les K-Dramas.
            val movieboxBackup = if (!showTitle.isNullOrBlank()) {
                try {
                    val year = document.selectFirst("h1.entry-title")?.text()?.let {
                        Regex("""\((\d{4})\)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
                    }
                    val type = if (videoType is Video.Type.Movie) 1 else 2
                    val (sNum, eNum) = if (videoType is Video.Type.Episode)
                        videoType.season.number to videoType.number else null to null
                    MovieboxProvider.getMovieboxSourcesByTitle(showTitle, year, type, sNum, eNum)
                        .also {
                            if (it.isNotEmpty()) Log.d("VoirDramaProvider",
                                "+ Moviebox backup pour '$showTitle' : ${it.size} sources")
                        }
                } catch (e: Exception) {
                    Log.d("VoirDramaProvider", "Moviebox backup failed: ${e.message}")
                    emptyList()
                }
            } else emptyList()

            voirDramaSorted + cloudstreamBackup + dcUnique + movieboxBackup
            } // close coroutineScope
        } catch (e: Exception) {
            Log.e("VoirDramaProvider", "getServers error: ", e)
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val video = Extractor.extract(server.src)
        // Pour les dramas VOSTFR, garder les sous-titres activés par défaut
        if (server.name.contains("VOSTFR")) {
            video.subtitles.forEach { sub ->
                if (sub.initialDefault) {
                    sub.default = true
                }
            }
        } else if (video.subtitles.isNotEmpty() && !server.name.contains("VO")) {
            // VF : désactiver les sous-titres
            return video.copy(subtitles = emptyList())
        }
        return video
    }

    // ==================== PEOPLE ====================

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = id)
    }

    // ==================== URL CONFIG ====================

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            // VoirDrama n'a pas de mécanisme d'auto-update de domaine
            // L'utilisateur peut changer l'URL manuellement dans les paramètres
            service = VoirDramaService.build(baseUrl)
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

    // ==================== HELPERS ====================

    /**
     * Vérifie si un contenu est en VF (doublé en français).
     * Les versions VF ont "(VF)" dans le titre ou "-vf" dans le slug.
     */
    private fun isVf(show: Show): Boolean {
        val title = when (show) {
            is TvShow -> show.title
            is Movie -> show.title
            else -> return false
        }
        val id = when (show) {
            is TvShow -> show.id
            is Movie -> show.id
            else -> return false
        }
        return title.contains("(VF)", ignoreCase = true)
            || title.endsWith(" VF", ignoreCase = true)
            || id.endsWith("-vf")
            || id.contains("-vf-")
    }

    /**
     * Améliore la qualité d'une URL d'image WordPress.
     * Les thumbnails homepage sont au format "-110x150.jpg", "-175x238.jpg" etc (trop petit).
     * On les remplace par "-193x278" (taille poster du site, bonne qualité).
     * Si l'URL contient un redimensionnement de taille, on essaie le format -193x278
     * avant de retomber sur l'URL originale.
     * NB: la version sans suffixe peut être bloquée par Cloudflare.
     */
    private fun improveImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // Remplacer le suffixe de redimensionnement WordPress par -193x278 (meilleure qualité dispo)
        return url.replace(Regex("""-\d+x\d+\."""), "-193x278.")
    }

    /**
     * Extraire une valeur d'info depuis les .post-content_item de la page détail.
     * Structure : .summary-heading h5 contient le label, .summary-content contient la valeur.
     */
    private fun getInfoValue(document: Document, label: String): String? {
        return document.select(".post-content_item").firstOrNull { item ->
            item.selectFirst(".summary-heading h5")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        }?.selectFirst(".summary-content")?.text()?.trim()
    }

    /**
     * Parse un item de la page d'accueil ou de genre (.page-item-detail).
     * Structure : .item-thumb (id=manga-item-{id}, data-post-id) > a > img
     *             .item-summary > h3 > a (titre + lien)
     *             .score (note)
     *             .chapter-item > a (épisodes récents)
     *
     * Détection film vs série :
     *   1) Badge Madara type (.manga-type-badge, .type-label, span[class*=type]) : "MOVIE","TV","OVA"…
     *   2) Genres dans l'item (.mg_genres a, .genres a, .post-on span) : contient "Movie"/"Film"
     *   3) Nombre de chapitres listés : 0-1 = Movie, >1 = TvShow
     *   4) Titre contient "(Film)" ou "(Movie)"
     */
    private fun parseHomeItem(element: Element): Show? {
        val thumb = element.selectFirst(".item-thumb")
        val summary = element.selectFirst(".item-summary")

        // Lien et titre depuis .item-summary h3 a (prioritaire) ou .item-thumb a
        val titleLink = summary?.selectFirst("h3 a")
            ?: thumb?.selectFirst("a")
            ?: return null

        val href = titleLink.attr("href").takeIf { it.isNotBlank() } ?: return null

        // Extraire le slug comme ID — format: https://voirdrama.to/drama/{slug}/
        val id = href.trimEnd('/').substringAfter("/drama/").trimEnd('/')
            .takeIf { it.isNotBlank() && !it.contains("://") }
            ?: href.trimEnd('/').substringAfterLast("/").takeIf { it.isNotBlank() }
            ?: return null

        val title = titleLink.text().trim().takeIf { it.isNotBlank() }
            ?: titleLink.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null

        // Filtrer les items sans contenu (pas d'épisodes/chapitres)
        val listChapter = element.selectFirst(".list-chapter")
        if (listChapter != null && listChapter.select(".chapter-item").isEmpty()) return null

        // Poster depuis .item-thumb img (src direct, pas de data-src sur ce site)
        val img = thumb?.selectFirst("img") ?: element.selectFirst("img")
        val rawPoster = img?.let {
            it.attr("data-src").ifEmpty {
                it.attr("src")
            }
        }?.takeIf { it.isNotBlank() && it.startsWith("http") }
        val poster = improveImageUrl(rawPoster)

        // --- Détection film vs série ---

        // 1) Badge de type Madara (span contenant MOVIE, TV, OVA, ONA, SPECIAL, TV SHORT…)
        val typeBadge = element.selectFirst(".manga-type-badge, .type-label, span[class*=type]")
            ?.text()?.trim()?.uppercase()

        // 2) Texte de tous les genres / labels dans l'item
        val genreTexts = element.select(".mg_genres a, .genres a, .post-on span, .item-summary .font-meta")
            .joinToString(" ") { it.text() }.lowercase()

        // 3) Nombre de chapitres — essayer plusieurs sélecteurs Madara
        val chapterItems = listChapter?.select(".chapter-item")
            ?.ifEmpty { element.select(".listing-chapters_wrap .wp-manga-chapter") }
            ?.ifEmpty { element.select("li.wp-manga-chapter") }
            ?: emptyList()
        val chapterCount = chapterItems.size

        // 4) Texte d'épisode — récupérer le texte du 1er chapitre listé
        val firstChapterText = (chapterItems.firstOrNull()?.selectFirst("a")?.text()
            ?: chapterItems.firstOrNull()?.text())?.trim()?.lowercase() ?: ""

        val isMovie = when {
            // Badge de type explicite
            typeBadge == "MOVIE" || typeBadge == "FILM" -> true
            typeBadge == "TV" || typeBadge == "TV SHORT" || typeBadge == "OVA" || typeBadge == "ONA" || typeBadge == "SPECIAL" -> false
            // Genres contiennent "movie" ou "film"
            genreTexts.contains("movie") || genreTexts.contains("film") -> true
            // Titre contient "(Film)" ou "(Movie)"
            title.contains("(Film)", ignoreCase = true) || title.contains("(Movie)", ignoreCase = true) -> true
            // Texte de chapitre contient "film" ou "movie"
            firstChapterText.contains("film") || firstChapterText.contains("movie") -> true
            // Texte de chapitre contient "episode" ou "épisode" → série
            firstChapterText.contains("episode") || firstChapterText.contains("épisode") || firstChapterText.contains("saison") -> false
            // Fallback : nombre de chapitres
            chapterCount > 1 -> false   // Plusieurs épisodes = série
            chapterCount == 1 -> true   // 1 seul chapitre = probablement un film
            else -> false               // Pas d'info chapitres = série par défaut
        }

        Log.d("VoirDrama_Parse", "id=$id title=$title type=${if (isMovie) "MOVIE" else "TVSHOW"} badge=$typeBadge genres='$genreTexts' chapters=$chapterCount firstCh='$firstChapterText' listChapter=${listChapter != null}")

        return if (isMovie) {
            Movie(id = id, title = title, poster = poster)
        } else {
            TvShow(id = id, title = title, poster = poster)
        }
    }

    /**
     * Parse un seul élément de résultat de recherche (.c-tabs-item__content).
     */
    private fun parseSearchItem(element: Element): Show? {
        val link = element.selectFirst(".post-title a, h3 a, h4 a, a[href*=/drama/]")
            ?: return null

        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null

        val id = href.trimEnd('/').substringAfter("/drama/").trimEnd('/')
            .takeIf { it.isNotBlank() && !it.contains("://") }
            ?: href.trimEnd('/').substringAfterLast("/").takeIf { it.isNotBlank() }
            ?: return null

        val title = link.text().trim().takeIf { it.isNotBlank() } ?: return null

        val img = element.selectFirst("img")
        val rawPoster = img?.let {
            // Madara lazy-loading : data-lazy-src > data-src > srcset > src
            it.attr("data-lazy-src").ifEmpty {
                it.attr("data-src").ifEmpty {
                    it.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull().orEmpty().ifEmpty {
                        it.attr("src")
                    }
                }
            }
        }?.takeIf { it.isNotBlank() && it.startsWith("http") }
        val poster = improveImageUrl(rawPoster)

        return TvShow(id = id, title = title, poster = poster)
    }

    /**
     * Parse les résultats de recherche.
     */
    private fun parseSearchResults(document: Document): List<AppAdapter.Item> {
        return document.select(".c-tabs-item__content").mapNotNull { item ->
            parseSearchItem(item) as? AppAdapter.Item
        }.distinctBy { (it as? TvShow)?.id ?: (it as? Movie)?.id }
    }

    // ==================== PUBLIC HELPER (MovixProvider linking) ====================
    //
    // 2026-05-11 : MovixProvider K-drama enrichment.
    //
    // Movix expose souvent 0 ou 1 serveur pour les K-dramas (Reply 1988, etc.) car
    // les sources FR (fstream/links/wiflix/cpasmal) ne les indexent pas. Yflix peut
    // les trouver mais l'extracteur WebView échoue sur certaines pages (timeout 25s
    // sans .m3u8 capté).
    //
    // VoirDrama (+ Dramacool9 fallback) a une couverture beaucoup plus large sur
    // l'Asia. On appelle ce helper depuis MovixProvider.getServers pour ENRICHIR
    // les servers d'une série/film K-drama identifié par son titre TMDB.
    //
    // Le src URL pointe vers un embed (Filemoon, Lpayer, MovPlayer, etc.) qui est
    // routé par Extractor.extract côté Movix → les extracteurs existants prennent
    // le relais. Pas besoin de prefix custom sur l'ID.
    //
    // Limites :
    //   - Timeout 2.5s côté Movix (runEndpoint) — si VoirDrama search est lent,
    //     les servers n'apparaissent pas. C'est acceptable : ce sont des bonus.
    //   - Pas de matching par TMDB ID (VoirDrama n'a pas d'ID TMDB) → fuzzy match
    //     par titre normalisé + bonus année.
    suspend fun findAndGetServersByTitle(
        title: String,
        year: Int?,
        videoType: Video.Type
    ): List<Video.Server> {
        if (title.isBlank()) return emptyList()
        initializeService()
        return try {
            // 1) Recherche cross (VoirDrama + Dramacool) — page 1 seulement
            val results = search(title, 1)
            if (results.isEmpty()) {
                Log.d("VoirDramaProvider", "findAndGetServersByTitle: no results for '$title'")
                return emptyList()
            }

            // 2) Fuzzy match : meilleur score par titre + année
            fun normalize(s: String) = s.lowercase()
                .replace(Regex("""\(\d{4}\)"""), " ")
                .replace(Regex("""\b(saison|season|vostfr|vost|vf|vo|fr)\b"""), " ")
                .replace(Regex("""[^a-z0-9\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            val targetNorm = normalize(title)

            data class Candidate(val id: String, val title: String, val score: Int)
            val candidates = results.mapNotNull { item ->
                val triple: Triple<String, String, Int?> = when (item) {
                    is Movie -> Triple(item.id, item.title, item.released?.get(java.util.Calendar.YEAR))
                    is TvShow -> Triple(item.id, item.title, item.released?.get(java.util.Calendar.YEAR))
                    else -> return@mapNotNull null
                }
                val itemId = triple.first
                val itemTitle = triple.second
                val itemYear = triple.third
                val itemNorm = normalize(itemTitle)
                var score = when {
                    itemNorm == targetNorm -> 100
                    itemNorm.startsWith("$targetNorm ") || targetNorm.startsWith("$itemNorm ") -> 80
                    itemNorm.contains(targetNorm) || targetNorm.contains(itemNorm) -> 50
                    else -> 0
                }
                if (score == 0) return@mapNotNull null
                // Bonus année si match (Calendar.YEAR == year passé en param)
                if (year != null && itemYear != null && itemYear == year) score += 20
                Candidate(itemId, itemTitle, score)
            }.sortedByDescending { it.score }

            if (candidates.isEmpty()) {
                Log.d("VoirDramaProvider", "findAndGetServersByTitle: no match for '$title' (year=$year)")
                return emptyList()
            }
            val best = candidates.first()
            Log.d("VoirDramaProvider", "findAndGetServersByTitle: '$title' → '${best.title}' " +
                "(id=${best.id}, score=${best.score})")

            // 3) Pour Episode : récupérer la liste des épisodes puis trouver le bon numéro
            //    Pour Movie : appeler getServers directement avec l'ID du show (équivalent
            //    page detail sur VoirDrama / episode-1 sur Dramacool).
            when (videoType) {
                is Video.Type.Episode -> {
                    val episodes = try {
                        getEpisodesBySeason(best.id)
                    } catch (e: Exception) {
                        Log.w("VoirDramaProvider", "getEpisodesBySeason failed for ${best.id}: ${e.message}")
                        return emptyList()
                    }
                    val ep = episodes.firstOrNull { it.number == videoType.number }
                    if (ep == null) {
                        Log.d("VoirDramaProvider", "Episode ${videoType.number} not found on VoirDrama '${best.title}'")
                        return emptyList()
                    }
                    getServers(ep.id, videoType).map { s ->
                        s.copy(name = "${s.name} [VoirDrama]")
                    }
                }
                is Video.Type.Movie -> {
                    getServers(best.id, videoType).map { s ->
                        s.copy(name = "${s.name} [VoirDrama]")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("VoirDramaProvider", "findAndGetServersByTitle error: ${e.message}")
            emptyList()
        }
    }

    // ==================== SERVICE ====================

    private interface VoirDramaService {
        companion object {
            private val client = NetworkClient.default.newBuilder().build()

            fun build(baseUrl: String): VoirDramaService {
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(VoirDramaService::class.java)
            }
        }

        @GET
        suspend fun getPage(@Url url: String): Document

        @GET(".")
        suspend fun search(
            @Query("s") query: String,
            @Query("page") page: Int = 1,
            @Query("post_type") postType: String = "wp-manga"
        ): Document

        @GET(".")
        suspend fun searchFiltered(
            @Query("s") query: String = "",
            @Query("post_type") postType: String = "wp-manga",
            @Query("type") type: String,
            @Query("language") language: String,
            @Query("m_orderby") orderBy: String = "views",
            @Query("paged") page: Int = 1
        ): Document
    }
}
