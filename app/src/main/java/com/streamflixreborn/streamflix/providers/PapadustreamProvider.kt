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
import com.streamflixreborn.streamflix.utils.TmdbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * PapadustreamProvider — provider standalone séries-only pour papadustream.
 *
 * Architecture pragmatique :
 *   - **Catalogue/recherche/détails** : scraping HTML papadustream (DLE CMS)
 *     pour avoir un fonctionnement immédiat sans dépendance TMDB stricte.
 *   - **Enrichissement TMDB** : sur getTvShow, on tente une recherche TMDB par
 *     titre pour récupérer poster/banner/synopsis FR HD si dispos. Best-effort,
 *     fallback silencieux sur les données scrapées.
 *   - **Sources vidéo** : 12 par épisode (VOE/Filemoon/Doodstream/Netu/Uqload/
 *     Vidoza × VF/VOSTFR), résolues via PapadustreamExtractor (WebView →
 *     getxfield AJAX → iframe → host extractor).
 *
 * IDs internes :
 *   TvShow.id      = "<genre>|<id>-<slug>"        (ex: "drame-s|13924-tracker-d22")
 *   Season.id      = "<genre>|<id>-<slug>|<S>"
 *   Episode.id     = "<genre>|<id>-<slug>|<S>|<E>"
 *
 * Domaine : papadustream.courses (mars 2026), auto-discovery sur autres TLDs.
 */
object PapadustreamProvider : Provider, ProviderConfigUrl {

    override val name = "Papadustream"
    override val logo = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_papadustream"
    override val language = "fr"

    private const val TAG = "PapadustreamProvider"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override val defaultBaseUrl: String = "https://papadustream.courses"
    @Volatile private var currentBaseUrl: String = defaultBaseUrl
    override val baseUrl: String
        get() = currentBaseUrl
    override val changeUrlMutex: Mutex = Mutex()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val knownDomains = listOf(
        "https://papadustream.courses",
        "https://papadustream.motorcycles",
        "https://papadustream.dad",
        "https://papadustream.fit",
    )

    override suspend fun onChangeUrl(forceRefresh: Boolean): String = changeUrlMutex.withLock {
        for (candidate in knownDomains) {
            if (candidate == currentBaseUrl && !forceRefresh) continue
            try {
                val req = Request.Builder().url("$candidate/").header("User-Agent", USER_AGENT).build()
                val ok = withContext(Dispatchers.IO) {
                    client.newCall(req).execute().use { it.isSuccessful }
                }
                if (ok) {
                    currentBaseUrl = candidate
                    Log.d(TAG, "Domain switched to $candidate")
                    return@withLock candidate
                }
            } catch (_: Exception) { /* try next */ }
        }
        currentBaseUrl
    }

    // ───────── HTTP helpers ─────────

    private suspend fun httpGet(url: String): Document? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$baseUrl/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET $url → HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                Jsoup.parse(body, url)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET $url failed: ${e.message}")
            null
        }
    }

    private suspend fun httpPost(url: String, formData: Map<String, String>): Document? =
        withContext(Dispatchers.IO) {
            try {
                val builder = FormBody.Builder()
                formData.forEach { (k, v) -> builder.add(k, v) }
                val req = Request.Builder()
                    .url(url).post(builder.build())
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$baseUrl/")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "fr-FR,fr;q=0.9")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    Jsoup.parse(body, url)
                }
            } catch (e: Exception) {
                Log.e(TAG, "POST $url failed: ${e.message}")
                null
            }
        }

    // ───────── Helpers parsing ─────────

    private fun extractShowId(url: String): String? {
        val m = Regex("""/cat-series/([^/]+)/([^/]+?)(?:\.html|/)""").find(url) ?: return null
        return "${m.groupValues[1]}|${m.groupValues[2]}"
    }

    private fun showIdToUrl(showId: String): String? {
        val parts = showId.split("|")
        if (parts.size != 2) return null
        return "$baseUrl/cat-series/${parts[0]}/${parts[1]}.html"
    }

    private fun seasonIdToUrl(seasonId: String): String? {
        val parts = seasonId.split("|")
        if (parts.size != 3) return null
        return "$baseUrl/cat-series/${parts[0]}/${parts[1]}/${parts[2]}-saison.html"
    }

    private fun episodeUrl(genre: String, slug: String, season: Int, episode: Int): String =
        "$baseUrl/cat-series/$genre/$slug/$season-saison/$episode-episode.html"

    /** Cache TMDB enrichment positif par titre normalisé.
     *  HashMap synchronisé manuellement (pas ConcurrentHashMap car son
     *  desugaring sur ce projet a posé problème en runtime). */
    private val tmdbCache: MutableMap<String, TvShow> = mutableMapOf()
    private val tmdbCacheLock = Any()
    /** Cache des titres non-trouvés sur TMDB pour éviter de re-chercher. */
    private val tmdbNegativeCache: MutableSet<String> = mutableSetOf()
    /** Mapping showId Papadustream → TMDB show ID (Int).
     *  Populé lors de getTvShow et utilisé par getEpisodesBySeason pour
     *  récupérer les still images TMDB des épisodes. */
    private val papaToTmdbShowId: MutableMap<String, Int> = mutableMapOf()

    /** Enrichit un TvShow avec poster/banner/overview/rating TMDB. Best-effort :
     *  garde l'ID Papadustream (pour navigation future), remplace juste les
     *  champs visuels. Si TMDB rate, retourne le show original tel quel.
     *  Si papaShowId est fourni, on stocke aussi le mapping vers le TMDB id
     *  pour pouvoir récupérer les still images d'épisodes plus tard. */
    private suspend fun enrichWithTmdb(show: TvShow, year: Int? = null, papaShowId: String? = null): TvShow {
        if (show.title.isBlank()) return show
        val cacheKey = "${show.title.lowercase().trim()}|${year ?: ""}"
        // Cache hit
        synchronized(tmdbCacheLock) {
            tmdbCache[cacheKey]?.let { cached ->
                if (papaShowId != null) {
                    cached.id.toIntOrNull()?.let { papaToTmdbShowId[papaShowId] = it }
                }
                return show.copy(
                    poster = cached.poster ?: show.poster,
                    banner = cached.banner ?: show.banner,
                    overview = cached.overview ?: show.overview,
                    rating = cached.rating ?: show.rating,
                    seasons = cached.seasons.takeIf { it.isNotEmpty() } ?: show.seasons,
                )
            }
            if (tmdbNegativeCache.contains(cacheKey)) return show
        }
        return try {
            // 2026-05-05 : normalise apostrophes typographiques + annotations avant TMDB
            val cleanTitle = com.streamflixreborn.streamflix.utils.TitleNormalizer.cleanForTmdbSearch(show.title)
                .ifBlank { show.title }
            val tmdbShow = TmdbUtils.getTvShow(title = cleanTitle, year = year, language = "fr-FR")
            synchronized(tmdbCacheLock) {
                if (tmdbCache.size > 500) tmdbCache.clear()
                if (tmdbNegativeCache.size > 500) tmdbNegativeCache.clear()
                if (tmdbShow != null) {
                    tmdbCache[cacheKey] = tmdbShow
                    if (papaShowId != null) {
                        tmdbShow.id.toIntOrNull()?.let { papaToTmdbShowId[papaShowId] = it }
                    }
                } else {
                    tmdbNegativeCache.add(cacheKey)
                }
            }
            if (tmdbShow != null) {
                show.copy(
                    poster = tmdbShow.poster ?: show.poster,
                    banner = tmdbShow.banner ?: show.banner,
                    overview = tmdbShow.overview ?: show.overview,
                    rating = tmdbShow.rating ?: show.rating,
                    // IMPORTANT : copier les seasons TMDB (avec leurs posters) car
                    // le caller s'en sert pour merger les posters par numéro de saison.
                    // Si tmdbShow.seasons est vide, on garde les nôtres scrapées.
                    seasons = tmdbShow.seasons.takeIf { it.isNotEmpty() } ?: show.seasons,
                    // 2026-05-05 : copier les genres TMDB pour les afficher dans la
                    // search/listing. Sinon les cards Papa n'avaient pas de badges genre.
                    genres = tmdbShow.genres.takeIf { it.isNotEmpty() } ?: show.genres,
                )
            } else show
        } catch (e: Exception) {
            Log.d(TAG, "TMDB enrich failed for '${show.title}': ${e.message}")
            show
        }
    }

    /** Enrichit une liste de TvShow en parallèle via TMDB (jusqu'à 20 en parallèle). */
    private suspend fun enrichListWithTmdb(shows: List<TvShow>): List<TvShow> = coroutineScope {
        shows.map { show ->
            async(Dispatchers.IO) { enrichWithTmdb(show) }
        }.map { it.await() }
    }

    private fun parseShortToTvShow(item: org.jsoup.nodes.Element): TvShow? {
        val link = item.selectFirst("a.short_img, a.short-poster, .short_title a, h3 a, h2 a")
            ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        if (href.isBlank() || !href.contains("/cat-series/")) return null
        val showId = extractShowId(href) ?: return null
        val title = (item.selectFirst(".short_title, .short-title, h3, h2")?.text()
            ?: link.attr("title") ?: link.text())
            .substringBefore(" Saison ").substringBefore("  Saison ").trim()
        if (title.isBlank()) return null
        // 2026-05-05 : sur Papadustream le `src` contient un placeholder
        // (loading spinner) et le vrai poster est dans `data-src` ou
        // `data-lazy-src`. On essaie les attributs lazy d'abord ; si on n'a
        // que `src`, on filtre les data-URIs (placeholders SVG/PNG inline).
        val img = item.selectFirst("img")
        val poster = img?.let { el ->
            val candidates = listOf(
                el.absUrl("data-src"),
                el.absUrl("data-lazy-src"),
                el.absUrl("data-original"),
                el.absUrl("data-srcset").substringBefore(" "),
                el.absUrl("src"),
            )
            candidates.firstOrNull {
                it.isNotBlank() &&
                !it.startsWith("data:") &&  // skip inline placeholders
                !it.contains("loading", ignoreCase = true) &&
                !it.contains("lazyload", ignoreCase = true)
            }
        }
        return TvShow(id = showId, title = title, poster = poster?.takeIf { it.isNotBlank() })
    }

    // ───────── Provider impl ─────────

    override suspend fun getHome(): List<Category> = coroutineScope {
        val papaD = async {
            val doc = httpGet("$baseUrl/") ?: return@async emptyList<TvShow>()
            val seen = mutableSetOf<String>()
            doc.select("div.short, div.short_in").mapNotNull { parseShortToTvShow(it) }
                .filter { seen.add(it.id) }
                .take(20)
        }
        // Catalogue ENRICHI : on puise aussi dans les séries de Movix (TMDB-based,
        // catalogue beaucoup plus large). User a explicitement demandé ce mix.
        val movixSeriesD = async {
            try { MovixProvider.getTvShows(page = 1) } catch (_: Exception) { emptyList() }
        }
        val movixTopRatedD = async {
            try { MovixProvider.getTvShows(page = 2) } catch (_: Exception) { emptyList() }
        }

        val papa = papaD.await()
        val enrichedPapa = if (papa.isNotEmpty()) enrichListWithTmdb(papa) else emptyList()
        val movixSeries = movixSeriesD.await()
        val movixTop = movixTopRatedD.await()

        // Dedupe par titre normalisé (Papadustream prioritaire car liens vidéo dispo)
        val papaTitles = enrichedPapa.map { normalizeForMatch(it.title) }.toSet()
        val movixUnique = movixSeries.filter { normalizeForMatch(it.title) !in papaTitles }
        val movixTopUnique = movixTop.filter {
            val n = normalizeForMatch(it.title)
            n !in papaTitles && movixSeries.none { ms -> normalizeForMatch(ms.title) == n }
        }

        // FEATURED carousel — pattern AnimeSamaProvider exact :
        // 10 items max, items frais (Movie/TvShow) avec id+title+banner=poster
        // (en cas où banner null), construction par map sur la liste source
        // (pas sur un nouveau pool venant d'ailleurs).
        val featuredSource = enrichedPapa.filter { !it.poster.isNullOrBlank() }.take(10)
        val featured: List<AppAdapter.Item> = featuredSource.map { item ->
            TvShow(
                id = item.id,
                title = item.title,
                banner = item.banner ?: item.poster,
                poster = item.poster,
            )
        }

        listOfNotNull(
            featured.takeIf { it.isNotEmpty() }
                ?.let { Category(name = Category.FEATURED, list = it) },
            enrichedPapa.takeIf { it.isNotEmpty() }
                ?.let { Category(name = "Nouveautés Papadustream", list = it) },
            movixUnique.takeIf { it.isNotEmpty() }
                ?.let { Category(name = "Séries Populaires", list = it.take(20)) },
            movixTopUnique.takeIf { it.isNotEmpty() }
                ?.let { Category(name = "Plus de Séries", list = it.take(20)) },
        )
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = coroutineScope {
        if (query.isBlank()) {
            // 2026-05-05 : retourne les genres Papadustream + ceux de Movix (TMDB)
            // sur la page 1 quand la query est vide. Permet à l'utilisateur de
            // browse par catégorie depuis l'écran de recherche, comme Movix.
            if (page > 1) return@coroutineScope emptyList<AppAdapter.Item>()
            return@coroutineScope PAPA_GENRES.map { (id, name) ->
                Genre(id = id, name = name)
            } as List<AppAdapter.Item>
        }

        val papaD = async {
            if (page > 1) return@async emptyList<TvShow>()
            val doc = httpPost(
                "$baseUrl/?do=search&subaction=search",
                mapOf("do" to "search", "subaction" to "search", "story" to query),
            ) ?: return@async emptyList()
            val seen = mutableSetOf<String>()
            val results = doc.select("div.short, div.short_in").mapNotNull { parseShortToTvShow(it) }
                .filter { seen.add(it.id) }
            enrichListWithTmdb(results)
        }
        val movixD = async {
            try {
                MovixProvider.search(query, page).filterIsInstance<TvShow>()
            } catch (_: Exception) { emptyList() }
        }

        val papa = papaD.await()
        val movix = movixD.await()
        val papaTitles = papa.map { normalizeForMatch(it.title) }.toSet()
        val movixUnique = movix.filter { normalizeForMatch(it.title) !in papaTitles }
        // Papadustream prioritaire (lecture native dispo), Movix en complément
        (papa + movixUnique) as List<AppAdapter.Item>
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> = coroutineScope {
        val papaD = async {
            val url = if (page <= 1) "$baseUrl/cat-series/" else "$baseUrl/cat-series/page/$page/"
            val doc = httpGet(url) ?: return@async emptyList<TvShow>()
            val seen = mutableSetOf<String>()
            val list = doc.select("div.short, div.short_in").mapNotNull { parseShortToTvShow(it) }
                .filter { seen.add(it.id) }
            enrichListWithTmdb(list)
        }
        val movixD = async {
            try { MovixProvider.getTvShows(page) } catch (_: Exception) { emptyList() }
        }
        val papa = papaD.await()
        val movix = movixD.await()
        val papaTitles = papa.map { normalizeForMatch(it.title) }.toSet()
        val movixUnique = movix.filter { normalizeForMatch(it.title) !in papaTitles }
        papa + movixUnique
    }

    override suspend fun getMovie(id: String): Movie =
        throw Exception("Papadustream: pas de films (séries uniquement)")

    /** Détecte si un ID provient de Movix (format TMDB int) plutôt que Papadustream
     *  ("genre|slug"). Permet de router vers le bon provider. */
    private fun isMovixId(id: String): Boolean = id.toIntOrNull() != null

    override suspend fun getTvShow(id: String): TvShow {
        // Show importé de Movix → délègue à Movix pour le détail (TMDB rich data)
        if (isMovixId(id)) {
            return try { MovixProvider.getTvShow(id) }
            catch (e: Exception) { throw Exception("Movix-sourced show $id failed: ${e.message}") }
        }

        val url = showIdToUrl(id) ?: throw Exception("Papadustream: id série invalide $id")
        val doc = httpGet(url) ?: throw Exception("Papadustream: page série introuvable $id")

        val h1Text = doc.selectFirst("h1")?.text() ?: ""
        val title = h1Text
            .removePrefix("Série ")
            .removeSuffix(" en streaming Français")
            .removeSuffix(" en streaming")
            .replace(Regex("""\s*\(\d{4}\)\s*"""), "")
            .trim()
            .ifBlank { id.substringAfter("|").substringAfter("-").replace("-", " ") }
        val year = Regex("""\((\d{4})\)""").find(h1Text)?.groupValues?.get(1)

        val synopsis = doc.selectFirst(".fdesc, .ftext, .full-text, [itemprop=description]")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val poster = doc.selectFirst(".full_content-poster img, .fposter img, .fimg img")
            ?.let { it.absUrl("src").ifBlank { it.attr("src") } }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val seasonNumbers = doc.select("a[href*='-saison']")
            .mapNotNull { a ->
                val href = a.absUrl("href")
                if (!href.contains("/${id.substringAfter("|")}/")) return@mapNotNull null
                Regex("""/(\d+)-saison(?:\.html|/)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            }
            .distinct().sorted()

        val seasons = seasonNumbers.map { sn ->
            Season(
                id = "$id|$sn",
                number = sn,
                title = "Saison $sn",
            )
        }

        val baseShow = TvShow(
            id = id,
            title = title,
            overview = synopsis,
            released = year,
            poster = poster?.takeIf { it.isNotBlank() },
            seasons = seasons,
        )
        // Enrichissement TMDB (poster HD, banner, synopsis FR, posters par saison)
        // On passe papaShowId=id pour que la fn stocke le mapping papa→TMDB id.
        val enriched = enrichWithTmdb(baseShow, year?.toIntOrNull(), papaShowId = id)
        // Merge : on garde nos IDs Papadustream pour les saisons mais on récupère
        // les posters TMDB par numéro de saison (jaquette de chaque saison).
        val mergedSeasons = seasons.map { papaSeason ->
            val tmdbSeasonPoster = enriched.seasons
                .firstOrNull { it.number == papaSeason.number }
                ?.poster
            if (!tmdbSeasonPoster.isNullOrBlank()) {
                Season(
                    id = papaSeason.id,
                    number = papaSeason.number,
                    title = papaSeason.title,
                    poster = tmdbSeasonPoster,
                )
            } else papaSeason
        }
        return enriched.copy(id = id, seasons = mergedSeasons)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // Movix utilise format "<tmdbId>/<seasonNumber>" (slash).
        // Papadustream utilise "<genre>|<slug>|<seasonNumber>" (3 parties pipe).
        if (seasonId.matches(Regex("""^\d+/\d+$"""))) {
            return try { MovixProvider.getEpisodesBySeason(seasonId) }
            catch (e: Exception) {
                Log.w(TAG, "Movix getEpisodesBySeason($seasonId) failed: ${e.message}")
                emptyList()
            }
        }
        val parts = seasonId.split("|")

        val url = seasonIdToUrl(seasonId) ?: return emptyList()
        if (parts.size != 3) return emptyList()
        val papaShowId = "${parts[0]}|${parts[1]}"
        val seasonNum = parts[2].toIntOrNull() ?: return emptyList()
        val doc = httpGet(url) ?: return emptyList()

        val episodeLinks = doc.select("a[href*='-saison/'][href*='-episode.html']")
            .mapNotNull { a ->
                val href = a.absUrl("href")
                if (!href.contains("/${parts[1]}/")) return@mapNotNull null
                val m = Regex("""/(\d+)-saison/(\d+)-episode\.html""").find(href)
                    ?: return@mapNotNull null
                val s = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                if (s != seasonNum) return@mapNotNull null
                val e = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                e to a.text().trim()
            }
            .distinctBy { it.first }
            .sortedBy { it.first }

        // Récupérer les still images TMDB par épisode si on a le mapping TMDB id.
        val tmdbId = synchronized(tmdbCacheLock) { papaToTmdbShowId[papaShowId] }
        val tmdbEpisodes: Map<Int, com.streamflixreborn.streamflix.models.Episode> = if (tmdbId != null) {
            try {
                TmdbUtils.getEpisodesBySeason(tvShowId = tmdbId.toString(), seasonNumber = seasonNum, language = "fr-FR")
                    .mapNotNull { ep -> ep.number?.let { it to ep } }
                    .toMap()
            } catch (e: Exception) {
                Log.d(TAG, "TMDB episodes fetch failed for $papaShowId S$seasonNum: ${e.message}")
                emptyMap()
            }
        } else emptyMap()

        return episodeLinks.map { (epNum, label) ->
            val tmdbEp = tmdbEpisodes[epNum]
            Episode(
                id = "$seasonId|$epNum",
                number = epNum,
                title = tmdbEp?.title?.takeIf { it.isNotBlank() }
                    ?: label.takeIf { it.isNotBlank() && !it.matches(Regex("""\d+""")) }
                    ?: "Épisode $epNum",
                poster = tmdbEp?.poster, // still TMDB w500
                // released TmdbUtils retourne Calendar? mais Episode attend String? :
                // on convertit en yyyy-MM-dd ou skip si null
                released = tmdbEp?.released?.let { cal ->
                    String.format(
                        java.util.Locale.US,
                        "%04d-%02d-%02d",
                        cal.get(java.util.Calendar.YEAR),
                        cal.get(java.util.Calendar.MONTH) + 1,
                        cal.get(java.util.Calendar.DAY_OF_MONTH),
                    )
                },
                overview = tmdbEp?.overview,
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page <= 1) "$baseUrl/cat-series/$id/"
        else "$baseUrl/cat-series/$id/page/$page/"
        val doc = httpGet(url) ?: return Genre(id = id, name = displayNameForGenre(id))
        // 2026-05-05 : déduplique. Les sélecteurs `div.short` et `div.short_in`
        // matchent souvent les mêmes items (l'un est wrapper de l'autre), donc
        // sans dédup on a chaque série affichée 2 fois.
        val seen = mutableSetOf<String>()
        val shows = doc.select("div.short, div.short_in").mapNotNull { parseShortToTvShow(it) }
            .filter { seen.add(it.id) }
        return Genre(id = id, name = displayNameForGenre(id), shows = shows)
    }

    // 2026-05-05 : liste statique des genres Papadustream (URLs /cat-series/<id>/).
    // Récupérée via scrap de la page d'accueil. Pas d'API → liste figée mais stable.
    private val PAPA_GENRES = listOf(
        "action-s" to "Action",
        "animation-s" to "Animation",
        "aventure-s" to "Aventure",
        "biopic-s" to "Biopic",
        "comedie-s" to "Comédie",
        "documentaire-s" to "Documentaire",
        "drame-s" to "Drame",
        "famille-s" to "Famille",
        "fantastique-s" to "Fantastique",
        "guerre-s" to "Guerre",
        "historique-s" to "Historique",
        "horreur-s" to "Horreur",
        "judiciare-s" to "Judiciaire",
        "musical-s" to "Musical",
        "policier-s" to "Policier",
        "romance-s" to "Romance",
        "science-fiction-s" to "Science-Fiction",
        "thriller-s" to "Thriller",
        "western-s" to "Western",
        "series-vf" to "Séries VF",
        "series-vostfr" to "Séries VOSTFR",
    )

    private fun displayNameForGenre(id: String): String {
        return PAPA_GENRES.firstOrNull { it.first == id }?.second
            ?: id.removeSuffix("-s").replace("-", " ")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = id, filmography = emptyList())
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = coroutineScope {
        // Cas 1 : id Movix
        //   - Episode : "<tmdbId>-s<S>e<E>" (ex: "12345-s1e3")
        //   - Movie : "<tmdbId>" (juste un int)
        val isMovixEpisode = id.matches(Regex("""^\d+-s\d+e\d+$"""))
        val isMovixMovie = id.matches(Regex("""^\d+$"""))
        if (isMovixEpisode || isMovixMovie) {
            // Show importé de Movix → MOVIX EN PREMIER (pas de captcha, plus fluide),
            // Papa en dernier (12 sources mais nécessite captcha → friction).
            // User a explicitement demandé cet ordre pour optimiser l'UX.
            // Moviebox backup ajouté en parallèle si dispo (FR-only).
            val movixServersD = async {
                try { MovixProvider.getServers(id, videoType) } catch (_: Exception) { emptyList() }
            }
            val papaServersD = async { tryPapaByTmdbId(id, videoType) }
            val movieboxServersD = async {
                try {
                    val tmdbIdInt = when (videoType) {
                        is Video.Type.Movie -> id.toIntOrNull()
                        is Video.Type.Episode -> id.substringBefore("-").toIntOrNull()
                    }
                    if (tmdbIdInt != null) MovieboxProvider.getMovieboxSourcesByTmdbId(tmdbIdInt, videoType)
                    else emptyList()
                } catch (_: Exception) { emptyList() }
            }
            val movix = movixServersD.await()
            val papa = papaServersD.await()
            val moviebox = movieboxServersD.await()
            Log.d(TAG, "Hybrid getServers (Movix-id $id) : movix=${movix.size} + papa=${papa.size} + moviebox=${moviebox.size}")
            return@coroutineScope sortByLanguagePref(movix + papa + moviebox)
        }
        val parts = id.split("|")

        // Cas 2 : épisode Papadustream natif "<genre>|<slug>|<S>|<E>"
        if (parts.size != 4) {
            Log.w(TAG, "getServers: id épisode invalide $id")
            return@coroutineScope emptyList()
        }
        val genre = parts[0]
        val slug = parts[1]
        val seasonNum = parts[2].toIntOrNull() ?: return@coroutineScope emptyList()
        val episodeNum = parts[3].toIntOrNull() ?: return@coroutineScope emptyList()
        val pageUrl = episodeUrl(genre, slug, seasonNum, episodeNum)

        val doc = httpGet(pageUrl) ?: return@coroutineScope emptyList()

        val liens = doc.select("div.lien.fx-row").mapNotNull { div ->
            val onclick = div.attr("onclick")
            val m = Regex("""getxfield\(\s*this\s*,\s*'(\d+)'\s*,\s*'([a-z0-9_]+)'\s*,\s*'([a-z]+)'\s*\)""")
                .find(onclick) ?: return@mapNotNull null
            val dleId = m.groupValues[1]
            val xfield = m.groupValues[2]
            val type = m.groupValues[3]
            val hosterName = div.selectFirst(".serv")?.text()?.trim()?.uppercase()
                ?: xfield.substringBefore("_").uppercase()
            val lang = when {
                xfield.endsWith("_vf") -> "VF"
                xfield.endsWith("_vostfr") -> "VOSTFR"
                xfield.endsWith("_vo") -> "VO"
                else -> ""
            }
            val label = buildString {
                append(hosterName)
                if (lang.isNotBlank()) append(" [$lang]")
            }
            val src = "$pageUrl#xf=$xfield&id=$dleId&t=$type"
            Video.Server(
                id = "papadustream_${xfield}_${dleId}",
                name = label,
                src = src,
            )
        }

        // Tri : VF d'abord, puis VOSTFR, puis VO
        val sorted = liens.sortedBy { server ->
            when {
                server.name.contains("[VF]") -> 0
                server.name.contains("[VOSTFR]") -> 1
                server.name.contains("[VO]") -> 2
                else -> 3
            }
        }

        Log.d(TAG, "getServers $pageUrl → ${sorted.size} liens (papa natifs)")

        // Récupérer aussi les sources Movix (incluant Yflix.to backup) si on a
        // un TMDB id mappé. MOVIX EN PREMIER (pas de captcha) et PAPA EN DERNIER
        // (nécessite captcha → friction UX).
        val papaShowId = "${parts[0]}|${parts[1]}"
        val tmdbId = synchronized(tmdbCacheLock) { papaToTmdbShowId[papaShowId] }
        val movixServers = if (tmdbId != null) {
            try {
                // Format Movix episode id : "<tmdbId>-s<S>e<E>"
                val movixEpisodeId = "$tmdbId-s${parts[2]}e${parts[3]}"
                MovixProvider.getServers(movixEpisodeId, videoType)
                    .also { Log.d(TAG, "+ Movix sources via TMDB id $tmdbId : ${it.size}") }
            } catch (e: Exception) {
                Log.d(TAG, "Movix backup failed for $papaShowId: ${e.message}")
                emptyList()
            }
        } else {
            Log.d(TAG, "No TMDB id mapping for $papaShowId — skip Movix backup. Hint: getTvShow doit avoir été appelé d'abord.")
            emptyList()
        }

        // Moviebox backup (FR-only) si on a un TMDB id mappé
        val movieboxServers = if (tmdbId != null) {
            try {
                MovieboxProvider.getMovieboxSourcesByTmdbId(tmdbId, videoType)
                    .also { Log.d(TAG, "+ Moviebox backup via TMDB id $tmdbId : ${it.size}") }
            } catch (e: Exception) {
                Log.d(TAG, "Moviebox backup failed for $papaShowId: ${e.message}")
                emptyList()
            }
        } else emptyList()

        // Combine Movix + Papa + Moviebox puis tri global VF → VOSTFR → VO (request user)
        sortByLanguagePref(movixServers + sorted + movieboxServers)
    }

    /** Public helper appelable depuis d'autres providers (ex: MovixProvider) pour
     *  obtenir uniquement les sources Papadustream pour un id TMDB-style sans
     *  re-déclencher la chaîne hybride (évite les cycles entre providers).
     *  Retourne emptyList si Papadustream n'a pas le show.  */
    suspend fun getPapaSourcesByTmdbId(id: String, videoType: Video.Type): List<Video.Server> {
        return tryPapaByTmdbId(id, videoType)
    }

    /** Bridge title-based : pour un show importé de Movix (TMDB id), on cherche
     *  le titre sur Papadustream et on tente d'en extraire les sources Papadustream.
     *  Retourne emptyList si pas de match.
     *
     *  Formats id Movix supportés :
     *    - Episode : "<tmdbId>-s<S>e<E>" (ex: "12345-s1e3")
     *    - Movie   : "<tmdbId>" (Papa = séries-only donc skip) */
    private suspend fun tryPapaByTmdbId(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            // Parse "<tmdbId>-s<S>e<E>"
            val match = Regex("""^(\d+)-s(\d+)e(\d+)$""").find(id) ?: return emptyList()
            val tmdbId = match.groupValues[1].toIntOrNull() ?: return emptyList()
            val seasonNum = match.groupValues[2].toIntOrNull() ?: return emptyList()
            val episodeNum = match.groupValues[3].toIntOrNull() ?: return emptyList()
            // Get title from TMDB
            val tmdbDetail = try {
                com.streamflixreborn.streamflix.utils.TMDb3.TvSeries.details(seriesId = tmdbId, language = "fr-FR")
            } catch (_: Exception) { return emptyList() }
            val title = tmdbDetail.name.takeIf { it.isNotBlank() } ?: return emptyList()
            val year = tmdbDetail.firstAirDate?.take(4)?.toIntOrNull()

            // Cherche sur Papadustream par titre
            val showUrl = withTimeoutOrNull(8_000) {
                searchPapaShowUrl(title, year)
            } ?: return emptyList()

            // Construit l'URL épisode et fetch les sources
            val pageUrl = "${showUrl.removeSuffix(".html").removeSuffix("/")}/" +
                    "$seasonNum-saison/$episodeNum-episode.html"
            val doc = httpGet(pageUrl) ?: return emptyList()
            val liens = doc.select("div.lien.fx-row").mapNotNull { div ->
                val onclick = div.attr("onclick")
                val m = Regex("""getxfield\(\s*this\s*,\s*'(\d+)'\s*,\s*'([a-z0-9_]+)'\s*,\s*'([a-z]+)'\s*\)""")
                    .find(onclick) ?: return@mapNotNull null
                val dleId = m.groupValues[1]
                val xfield = m.groupValues[2]
                val type = m.groupValues[3]
                val hosterName = div.selectFirst(".serv")?.text()?.trim()?.uppercase()
                    ?: xfield.substringBefore("_").uppercase()
                val lang = when {
                    xfield.endsWith("_vf") -> "VF"
                    xfield.endsWith("_vostfr") -> "VOSTFR"
                    xfield.endsWith("_vo") -> "VO"
                    else -> ""
                }
                val label = "$hosterName${if (lang.isNotBlank()) " [$lang]" else ""}"
                val src = "$pageUrl#xf=$xfield&id=$dleId&t=$type"
                Video.Server(
                    id = "papadustream_${xfield}_${dleId}",
                    name = "Papadustream — $label",
                    src = src,
                )
            }
            Log.d(TAG, "tryPapaByTmdbId($id) title='$title' → ${liens.size} liens")
            liens.sortedBy { s ->
                when {
                    s.name.contains("[VF]") -> 0
                    s.name.contains("[VOSTFR]") -> 1
                    s.name.contains("[VO]") -> 2
                    else -> 3
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "tryPapaByTmdbId($id) failed: ${e.message}")
            emptyList()
        }
    }

    /** Cherche un titre sur Papadustream et retourne l'URL de la fiche série. */
    private suspend fun searchPapaShowUrl(title: String, year: Int? = null): String? {
        if (title.isBlank()) return null
        val doc = httpPost(
            "$baseUrl/?do=search&subaction=search",
            mapOf("do" to "search", "subaction" to "search", "story" to title),
        ) ?: return null
        // 2026-05-05 v2 : matching strict (cf Moiflix/Yflix/Coflix). Pas de
        // substring lâche. Exige exact (100) ou word-for-word + longueur ≤30%.
        val targetNorm = normalizeForMatch(title)
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        val targetWords = targetNorm.split(" ").filter { it.length > 1 }.toSet()
        var bestUrl: String? = null
        var bestScore = -1
        var bestTitle: String? = null
        for (item in doc.select("div.short, div.short_in")) {
            val titleEl = item.selectFirst(".short_title, .short-title, h3, h2")
            val link = titleEl?.selectFirst("a")
                ?: item.selectFirst("a.short_img, a.short-poster, a[href*='/cat-series/']")
                ?: continue
            val itemUrl = link.absUrl("href").ifBlank { link.attr("href") }
            if (itemUrl.isBlank() || !itemUrl.contains("/cat-series/")) continue
            if (itemUrl.contains("-saison/")) continue // skip episode URLs
            val itemTitle = titleEl?.text() ?: link.attr("title") ?: ""
            val nNorm = normalizeForMatch(itemTitle.substringBefore(" Saison ").trim())
                .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            val nWords = nNorm.split(" ").filter { it.length > 1 }.toSet()
            val lenDiffPct = if (kotlin.math.max(nNorm.length, targetNorm.length) > 0)
                kotlin.math.abs(nNorm.length - targetNorm.length).toDouble() /
                    kotlin.math.max(nNorm.length, targetNorm.length)
            else 0.0
            var score = when {
                nNorm == targetNorm -> 100
                targetWords.isNotEmpty() && nWords.containsAll(targetWords) && lenDiffPct <= 0.30 -> 90
                nWords.isNotEmpty() && targetWords.containsAll(nWords) && lenDiffPct <= 0.30 -> 80
                else -> 0
            }
            if (year != null && itemTitle.contains(year.toString())) score += 10
            if (score > bestScore) {
                bestScore = score
                bestUrl = itemUrl
                bestTitle = itemTitle
            }
        }
        // Seuil 90 : on n'accepte que les vrais matches
        return if (bestUrl != null && bestScore >= 90) {
            Log.d(TAG, "searchPapaShowUrl '$title' → '$bestTitle' score=$bestScore")
            bestUrl
        } else {
            Log.d(TAG, "searchPapaShowUrl '$title' : pas de match fiable (best='$bestTitle' score=$bestScore)")
            null
        }
    }

    private fun normalizeForMatch(s: String): String =
        s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Tri global VF → VOSTFR → VO (request user). Dans chaque groupe : Movix
     *  AVANT Papa (Papa = nécessite captcha = friction → en queue).
     *  Detection VO : "vo" comme MOT (entouré de non-lettres) pour catcher
     *  "Phoenix (Videasy VO)" mais pas "vodplay" ou "video". */
    private fun sortByLanguagePref(servers: List<Video.Server>): List<Video.Server> {
        val vf = mutableListOf<Video.Server>()
        val vostfr = mutableListOf<Video.Server>()
        val vo = mutableListOf<Video.Server>()
        val voRegex = Regex("""(^|[^a-z])vo([^a-z]|$)""")
        for (s in servers) {
            val n = s.name.lowercase()
            when {
                n.contains("vostfr") || n.contains("sous-titr") -> vostfr.add(s)
                voRegex.containsMatchIn(n) -> vo.add(s)
                else -> vf.add(s)
            }
        }
        // 2026-05-05 v4 : Papadustream natif RELÉGUÉ derrière les backups
        // car ses liens nécessitent un captcha Cloudflare (friction UX
        // grosse). Ordre :
        //   (0) Backups Movix / Moviebox VF — rapide, sans captcha
        //   (1) Papadustream natif — captcha mais fichiers vivants
        //   (2) Coflix — link rot fréquent, filet de sécurité
        // Au sein de chaque tier : bonus qualité HD/4K.
        fun isNativePapa(s: Video.Server) = s.name.contains("Papadustream — ", ignoreCase = false) ||
            s.id.startsWith("papadustream_")
        fun isCoflix(s: Video.Server) = s.name.startsWith("Coflix", ignoreCase = false) ||
            s.id.startsWith("coflix_")
        fun qualityBonus(s: Video.Server): Int {
            val n = s.name.lowercase()
            return when {
                n.contains("4k") || n.contains("2160") || n.contains("uhd") -> -3
                n.contains("1080") || n.contains("fhd") || n.contains("full hd") -> -2
                n.contains(" hd") || n.endsWith("hd") || n.contains("[hd]") || n.contains("(hd)") -> -1
                n.contains("720") -> 0
                n.contains("480") || n.contains(" sd") -> 1
                else -> 0
            }
        }
        val cmp = compareBy<Video.Server>(
            {
                when {
                    isCoflix(it) -> 2          // tout en bas
                    isNativePapa(it) -> 1      // captcha → après les backups
                    else -> 0                  // backups Movix/Moviebox en tête
                }
            },
            { qualityBonus(it) },
        )
        return vf.sortedWith(cmp) + vostfr.sortedWith(cmp) + vo.sortedWith(cmp)
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src, server)
    }
}
