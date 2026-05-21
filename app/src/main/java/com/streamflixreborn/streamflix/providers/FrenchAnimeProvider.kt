package com.streamflixreborn.streamflix.providers

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
import com.streamflixreborn.streamflix.utils.UserPreferences
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

object FrenchAnimeProvider : Provider, ProviderConfigUrl {
    override val defaultBaseUrl: String = "https://french-anime.com/"
    override val baseUrl: String = FrenchAnimeProvider.defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }
    override val name = "FrenchAnime"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_frenchanime"

    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private var service = FrenchAnimeService.build()

    // Client OkHttp partagé pour searchDirect — évite de recréer un client à chaque appel
    private val searchClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .readTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // Flag to track if more search results are available. Set to false when API returns fewer items than requested.
    // This prevents querying non-existent pages that could return random/incorrect results.
    private var hasMore = true
    private const val SEARCH_PAGE_SIZE = 10
    private val URL_DOMAIN_REGEX = Regex("""(?:https?:)?//(?:www\.)?([^.]+)\.""")

    override suspend fun getHome(): List<Category> {
        val document = service.getHome()
        val categories = mutableListOf<Category>()

        document.select(".owl-carousel .item").map { item ->
            val a = item.selectFirst("a") ?: return@map null
            val link = a.attr("href")
            val img = a.selectFirst("img")
            val bannerUrl = img?.let {
                it.attr("data-src").takeIf { s -> s.isNotEmpty() }
                    ?: it.attr("data-lazy-src").takeIf { s -> s.isNotEmpty() }
                    ?: it.attr("data-original").takeIf { s -> s.isNotEmpty() }
                    ?: it.attr("src")
            }?.toUrl()

            TvShow(
                id = link.substringAfterLast("/").substringBefore(".html"),
                title = a.selectFirst(".title1")?.text() ?: "",
                overview = a.selectFirst(".title0")?.text(),
                banner = bannerUrl
            )
        }.filterNotNull().takeIf { it.isNotEmpty() }?.let { featuredItems ->
            // Enhance banners with TMDB HD backdrops (filter Japanese/anime content)
            if (UserPreferences.enableTmdb) {
                val animeLanguages = setOf("ja", "ko", "zh")
                for (item in featuredItems) {
                    try {
                        val title = (item as? TvShow)?.title ?: (item as? Movie)?.title ?: continue
                        // 2026-05-05 : normalise (apostrophe typo, annotations VF/saison) avant TMDB
                        val normalized = com.streamflixreborn.streamflix.utils.TitleNormalizer.cleanForTmdbSearch(title)
                        val results = TMDb3.Search.multi(normalized.ifBlank { title })
                        val match = results.results.firstOrNull { result ->
                            when (result) {
                                is TMDb3.Movie -> result.originalLanguage in animeLanguages && result.backdropPath != null
                                is TMDb3.Tv -> result.originalLanguage in animeLanguages && result.backdropPath != null
                                else -> false
                            }
                        }
                        val tmdbBanner = when (match) {
                            is TMDb3.Movie -> match.backdropPath?.original
                            is TMDb3.Tv -> match.backdropPath?.original
                            else -> null
                        }
                        if (tmdbBanner != null) {
                            when (item) {
                                is Movie -> item.banner = tmdbBanner
                                is TvShow -> item.banner = tmdbBanner
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            categories.add(Category(name = Category.FEATURED, list = featuredItems))
        }

        document.select(".block-main").forEach { block ->
            val categoryName = block.selectFirst(".block-title .left-ma")?.text() ?: ""
            block.select(".mov").mapNotNull { mov ->
                val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                val link = a.attr("href")
                val id = link.substringAfterLast("/").substringBefore(".html")
                val isFrench = (mov.selectFirst(".block-sai")?.text()
                    ?: mov.selectFirst(".nbloc1")?.text()).isFrench()
                val title = if (categoryName.contains("FILMS", ignoreCase = true))
                    a.text().toTitle(isFrench) else a.text()
                val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl() ?: ""

                val isTvShow = mov.selectFirst(".block-ep") != null
                if (isTvShow) {
                    val seasonText = mov.selectFirst(".block-sai")?.text() ?: ""
                    val seasonNumber = seasonText.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
                    val episodeText = mov.selectFirst(".block-ep")?.text() ?: ""
                    // Handle cases like "Episode 13 et 14 Final" by taking the last number
                    val episodeNumberText = episodeText.split(" ")
                        .lastOrNull { it.toIntOrNull() != null }
                    val episodeNumber = episodeNumberText?.toIntOrNull() ?: 0

                    TvShow(
                        id = id,
                        title = title,
                        poster = poster,
                        seasons = if (seasonNumber > 0 && episodeNumber > 0) {
                            listOf(
                                Season(
                                    id = "",
                                    number = seasonNumber,
                                    episodes = listOf(
                                        Episode(
                                            id = "",
                                            number = episodeNumber
                                        )
                                    )
                                )
                            )
                        } else {
                            emptyList()
                        }
                    )
                } else {
                    Movie(
                        id = id,
                        title = title,
                        poster = poster
                    )
                }
            }.takeIf { it.isNotEmpty() }?.let { items ->
                categories.add(Category(name = categoryName, list = items))
            }
        }

        // Reorder: 1.FEATURED 2.Épisodes/récents 3.Séries récentes 4.Films récents 5.Séries 6.Films
        return categories.sortedWith(compareBy { cat ->
            val n = cat.name.lowercase()
            val isRecent = n.contains("récen") || n.contains("nouveau") || n.contains("nouvelle") || n.contains("derni") || n.contains("ajouté")
            val isSeries = n.contains("séri") || n.contains("seri") || n.contains("saison") || n.contains("tv")
            val isFilm = n.contains("film") || n.contains("movie") || n.contains("cinéma")
            when {
                cat.name == Category.FEATURED -> 0
                n.contains("épisode") || n.contains("episode") -> 1
                isRecent && isSeries -> 2
                isRecent && isFilm -> 3
                isSeries -> 4
                isFilm -> 5
                isRecent -> 1
                else -> 6
            }
        })
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            val document = service.getHome()

            val genres = document.select("div.side-b nav.side-c ul.flex-row li a").map {
                Genre(
                    id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                    name = it.text()
                )
            }.toMutableList()

            // Ajouter K-Drama (recherche par mots-clés via GenreViewModel)
            if (genres.none { it.id.contains("k-drama", ignoreCase = true) }) {
                genres.add(Genre(id = "k-drama", name = "K-Drama"))
            }

            return genres
        }

        if (page > 1 && !hasMore) return emptyList()

        // Reset hasMore for new searches (page 1)
        if (page == 1) hasMore = true

        android.util.Log.d("FrenchAnime", "search: query='$query' page=$page hasMore=$hasMore")

        // Use manual OkHttp POST instead of Retrofit to properly handle redirects.
        // Retrofit drops the POST body on 301/302 redirects which breaks DLE search.
        val document = searchDirect(query, page)

        android.util.Log.d("FrenchAnime", "search: got document, title='${document.title()}', body=${document.body()?.text()?.take(200)}")

        document.selectFirst("div.berrors")?.let(::checkHasMore)

        val results = document.select("div.mov").mapNotNull { mov ->
            val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
            val link = a.attr("href")
            val id = link.substringAfterLast("/").substringBefore(".html")
            val isFrench = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")
                ?.text()).isFrench()
            val title = a.text().toTitle(isFrench)
            val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()

            val isTvShow = mov.selectFirst(".block-ep") != null
            if (isTvShow) {
                TvShow(
                    id = id,
                    title = title,
                    poster = poster,
                )
            } else {
                Movie(
                    id = id,
                    title = title,
                    poster = poster
                )
            }
        }

        android.util.Log.d("FrenchAnime", "search: found ${results.size} results for '$query'")
        return results
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        // FR tab: must provide BOTH films (isSeries=false) AND series (isSeries=true)
        // The fragment filters by isSeries for the "Serie"/"Film" sub-tabs
        val movies = mutableListOf<Movie>()

        // Films VF/VOSTFR → Movie with isSeries=false (default)
        try {
            service.getMovies(page).select("div.mov.clearfix").mapNotNull { mov ->
                val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                val link = a.attr("href")
                val id = link.substringAfterLast("/").substringBefore(".html")
                val isFrench = (mov.selectFirst(".block-sai")?.text()
                    ?: mov.selectFirst(".nbloc1")?.text()).isFrench()
                if (!isFrench) return@mapNotNull null
                val title = a.text().toTitle(true)
                val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
                Movie(id = id, title = title, poster = poster)
            }.let { movies.addAll(it) }
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }

        // Animes VF → Movie with isSeries=true
        try {
            service.getTvSeries("animes-vf/page/$page/").select("div.mov.clearfix").mapNotNull { mov ->
                val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                val link = a.attr("href")
                val id = link.substringAfterLast("/").substringBefore(".html")
                val isFrench = (mov.selectFirst(".block-sai")?.text()
                    ?: mov.selectFirst(".nbloc1")?.text()).isFrench()
                val title = a.text().toTitle(isFrench)
                val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
                Movie(id = id, title = title, poster = poster).also { it.isSeries = true }
            }.let { movies.addAll(it) }
        } catch (_: Exception) {}

        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        // VOSTFR tab: must provide BOTH series (isMovie=false) AND films (isMovie=true)
        // The fragment filters by isMovie for the "Serie"/"Film" sub-tabs
        val tvShows = mutableListOf<TvShow>()

        // Animes VOSTFR → TvShow with isMovie=false (default)
        try {
            service.getTvSeries("animes-vostfr/page/$page/").select("div.mov.clearfix").mapNotNull { mov ->
                val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                val link = a.attr("href")
                val id = link.substringAfterLast("/").substringBefore(".html")
                val isFrench = (mov.selectFirst(".block-sai")?.text()
                    ?: mov.selectFirst(".nbloc1")?.text()).isFrench()
                val title = a.text().toTitle(isFrench)
                val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
                TvShow(id = id, title = title, poster = poster)
            }.let { tvShows.addAll(it) }
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }

        // Films VOSTFR → TvShow with isMovie=true (VOSTFR tab shows VOSTFR films only)
        try {
            service.getMovies(page).select("div.mov.clearfix").mapNotNull { mov ->
                val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                val link = a.attr("href")
                val id = link.substringAfterLast("/").substringBefore(".html")
                val isFrench = (mov.selectFirst(".block-sai")?.text()
                    ?: mov.selectFirst(".nbloc1")?.text()).isFrench()
                if (isFrench) return@mapNotNull null // Skip FR films, keep VOSTFR
                val title = a.text().toTitle(false)
                val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
                TvShow(id = id, title = title, poster = poster).also { it.isMovie = true }
            }.let { tvShows.addAll(it) }
        } catch (_: Exception) {}

        return tvShows
    }

    override suspend fun getMovie(id: String): Movie = withContext(Dispatchers.IO) {
        // Lancer page + recherche en parallèle pour réduire la latence
        val documentDeferred = async { service.getMovie(id) }

        // On a besoin du titre pour la recherche, donc on attend le document d'abord
        val document = documentDeferred.await()

        val isFrench = document.selectFirst("ul.mov-list li:contains(Version) .mov-desc span")
            ?.text().isFrench()

        val rawTitle = document.selectFirst("header.full-title h1")?.text() ?: ""
        val title = rawTitle.toTitle(isFrench)

        // ── Search for similar/related films (async) ──
        val baseTitle = rawTitle
            .replace(Regex("""(?i)\s*(film|movie|le film|the movie).*"""), "")
            .replace("FRENCH", "").replace("VOSTFR", "")
            .trim()

        val franchiseName = baseTitle
            .split(Regex("""\s*[:]\s*|\s+[-–—]\s+"""))
            .firstOrNull()?.trim()
            ?.takeIf { it.length >= 3 } ?: baseTitle

        val movieSearchQuery = franchiseName
            .replace(Regex("""[:\-–—·''""\[\]()!?,;]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

        // Lancer la recherche de recommandations en parallèle du parsing
        val relatedDeferred = async {
            if (movieSearchQuery.length < 3) return@async emptyList<Show>()
            try {
                val searchDoc = searchDirect(movieSearchQuery, 1)
                val searchBase = franchiseName.normalizeForCompare()
                searchDoc.select("div.mov").mapNotNull { mov ->
                    val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                    val link = a.attr("href")
                    val resultId = link.substringAfterLast("/").substringBefore(".html")
                    if (resultId == id) return@mapNotNull null
                    val resultTitle = a.text()
                    val resultPoster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()

                    val resultBase = resultTitle
                        .replace(Regex("""(?i)\s*(film|movie|le film|the movie|saison).*"""), "")
                        .replace("FRENCH", "").replace("VOSTFR", "")
                        .normalizeForCompare()
                    val resultFranchise = resultBase
                        .split(Regex("""\s*[:]\s*|\s+[-–—]\s+"""))
                        .firstOrNull()?.trim() ?: resultBase

                    if (titlesFuzzyMatch(resultBase, searchBase) || titlesFuzzyMatch(resultFranchise, searchBase)) {
                        Movie(id = resultId, title = resultTitle.toTitle(isFrench), poster = resultPoster)
                    } else null
                }
            } catch (_: Exception) { emptyList() }
        }

        val relatedFilms = relatedDeferred.await()

        Movie(
            id = id,
            title = title,
            overview = document.selectFirst("span[itemprop='description']")?.text() ?: "",
            released = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "Date de sortie:" }
                ?.selectFirst("div.mov-desc")?.text()?.substringBefore(" to"),
            runtime = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "Durée:" }
                ?.selectFirst("div.mov-desc")?.text()?.extractRuntime(),
            poster = document.selectFirst("div.mov-img img[itemprop='thumbnailUrl']")
                ?.attr("src")?.toUrl(),
            genres = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "GENRE:" }
                ?.select("span[itemprop='genre'] a")?.map {
                    Genre(
                        id = it.attr("href").substringAfter("/genre/").substringBefore("/"),
                        name = it.text(),
                    )
                } ?: listOf(),
            directors = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "RÉALISATEUR:" }
                ?.selectFirst("div.mov-desc span[itemprop='name']")?.text()
                .toPeople(),
            cast = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "ACTEURS:" }
                ?.selectFirst("div.mov-desc span[itemprop='name']")?.text()
                .toPeople(),
            recommendations = relatedFilms,
        )
    }

    override suspend fun getTvShow(id: String): TvShow = withContext(Dispatchers.IO) {
        val document = service.getTvShow(id)

        val isFrench = document.selectFirst("ul.mov-list li:contains(Version) .mov-desc span")
            ?.text().isFrench()

        val rawTitle = document.selectFirst("h1[itemprop=name]")?.text() ?: ""
        val title = rawTitle.toTitle(isFrench)
        val poster = document.selectFirst("div.mov-img img[itemprop=thumbnailUrl]")?.attr("src")
            ?.toUrl()

        val baseTitle = rawTitle
            .replace(Regex("""[Ss]aison\s*\d+"""), "")
            .replace("FRENCH", "").replace("VOSTFR", "")
            .trim()

        val searchQuery = baseTitle
            .replace(Regex("""[:\-–—·''""\[\]()!?,;]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // ── Recherche saisons + recommandations en parallèle ──
        val franchiseName = baseTitle
            .split(Regex("""\s*[:]\s*|\s+[-–—]\s+"""))
            .firstOrNull()?.trim()
            ?.takeIf { it.length >= 3 } ?: baseTitle
        val recoQuery = franchiseName
            .replace(Regex("""[:\-–—·''""\[\]()!?,;]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

        // UNE SEULE recherche — saisons + recos extraites du même résultat
        // (la query saisons et recos est quasi identique, inutile de doubler)
        val seasonsAndRecos = async {
            val seasons = mutableListOf<Season>()
            val recommendations = mutableListOf<Show>()
            if (searchQuery.isEmpty()) return@async Pair(seasons, recommendations)

            try {
                val searchDoc = searchDirect(searchQuery, 1)
                val searchBase = baseTitle.normalizeForCompare()
                val alternateNames = mutableSetOf<String>()

                searchDoc.select("div.mov").forEach { mov ->
                    val a = mov.selectFirst("a.mov-t") ?: return@forEach
                    val link = a.attr("href")
                    val resultId = link.substringAfterLast("/").substringBefore(".html")
                    val resultTitle = a.text()
                    val resultPoster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
                    val isTvShow = mov.selectFirst(".block-ep") != null

                    val resultBase = resultTitle
                        .replace(Regex("""[Ss]aison\s*\d+"""), "")
                        .replace("FRENCH", "").replace("VOSTFR", "")
                        .normalizeForCompare()

                    if (isTvShow) {
                        val saisonText = mov.selectFirst(".block-sai")?.text() ?: ""
                        val seasonNumber = Regex("""[Ss]aison\s*(\d+)""").find(saisonText)
                            ?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("""[Ss]aison\s*(\d+)""").find(resultTitle)
                                ?.groupValues?.get(1)?.toIntOrNull()
                            ?: 1

                        if (titlesFuzzyMatch(resultBase, searchBase)) {
                            seasons.add(Season(
                                id = resultId,
                                number = seasonNumber,
                                title = "Saison $seasonNumber",
                                poster = resultPoster ?: poster,
                            ))
                        } else {
                            val altName = resultTitle
                                .replace(Regex("""[Ss]aison\s*\d+"""), "")
                                .replace("FRENCH", "").replace("VOSTFR", "")
                                .trim()
                            if (altName.isNotEmpty() && !titlesFuzzyMatch(altName.normalizeForCompare(), searchBase)) {
                                alternateNames.add(altName)
                            }
                        }
                    }

                    // Recommandations (films et séries liés, excluant self)
                    if (resultId != id) {
                        val resultFranchise = resultBase
                            .replace(Regex("""(?i)\s*(film|movie|le film|the movie|saison).*"""), "")
                            .normalizeForCompare()
                            .split(Regex("""\s*[:]\s*|\s+[-–—]\s+"""))
                            .firstOrNull()?.trim() ?: resultBase
                        val recoBase = franchiseName.normalizeForCompare()

                        if (titlesFuzzyMatch(resultBase, recoBase) || titlesFuzzyMatch(resultFranchise, recoBase)) {
                            recommendations.add(
                                TvShow(id = resultId, title = resultTitle.toTitle(isFrench), poster = resultPoster)
                            )
                        }
                    }
                }

                // Second pass avec noms alternatifs en parallèle
                if (alternateNames.isNotEmpty()) {
                    val altResults = alternateNames.map { altName ->
                        async {
                            try {
                                val altQuery = altName
                                    .replace(Regex("""[:\-–—·''""\[\]()!?,;]"""), " ")
                                    .replace(Regex("""\s+"""), " ").trim()
                                if (altQuery.isBlank()) return@async emptyList<Season>()
                                val altDoc = searchDirect(altQuery, 1)
                                val altBase = altName.normalizeForCompare()
                                altDoc.select("div.mov").mapNotNull { mov ->
                                    val a2 = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                                    val link2 = a2.attr("href")
                                    val resultId2 = link2.substringAfterLast("/").substringBefore(".html")
                                    val resultTitle2 = a2.text()
                                    val resultPoster2 = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
                                    val isTvShow2 = mov.selectFirst(".block-ep") != null
                                    if (!isTvShow2) return@mapNotNull null

                                    val saisonText2 = mov.selectFirst(".block-sai")?.text() ?: ""
                                    val seasonNumber2 = Regex("""[Ss]aison\s*(\d+)""").find(saisonText2)
                                        ?.groupValues?.get(1)?.toIntOrNull()
                                        ?: Regex("""[Ss]aison\s*(\d+)""").find(resultTitle2)
                                            ?.groupValues?.get(1)?.toIntOrNull()
                                        ?: 1

                                    val resultBase2 = resultTitle2
                                        .replace(Regex("""[Ss]aison\s*\d+"""), "")
                                        .replace("FRENCH", "").replace("VOSTFR", "")
                                        .normalizeForCompare()

                                    if (titlesFuzzyMatch(resultBase2, altBase) || titlesFuzzyMatch(resultBase2, searchBase)) {
                                        Season(
                                            id = resultId2,
                                            number = seasonNumber2,
                                            title = "Saison $seasonNumber2",
                                            poster = resultPoster2 ?: poster,
                                        )
                                    } else null
                                }
                            } catch (_: Exception) { emptyList() }
                        }
                    }
                    altResults.forEach { seasons.addAll(it.await()) }
                }

                // Dedup + sort
                val deduped = seasons.distinctBy { it.number }.sortedBy { it.number }
                seasons.clear()
                seasons.addAll(deduped)
            } catch (_: Exception) {}

            Pair(seasons, recommendations)
        }

        val (seasons, recommendations) = seasonsAndRecos.await()

        // Fallback: if search found nothing, use current page as single season
        if (seasons.isEmpty()) {
            val seasonNum = Regex("""[Ss]aison\s*(\d+)""").find(rawTitle)?.groupValues?.get(1)
                ?.toIntOrNull() ?: 1
            seasons.add(Season(
                id = id,
                number = seasonNum,
                title = "Saison $seasonNum",
                poster = poster,
            ))
        }

        TvShow(
            id = id,
            title = title,
            overview = document.selectFirst("span[itemprop=description]")?.text(),
            released = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "Date de sortie:" }
                ?.selectFirst("div.mov-desc")?.text()?.substringBefore(" to"),
            runtime = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "Durée:" }
                ?.selectFirst("div.mov-desc")?.text()?.extractRuntime(),
            poster = poster,
            seasons = seasons,
            genres = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "GENRE:" }
                ?.select("span[itemprop='genre'] a")?.map {
                    Genre(
                        id = it.attr("href").substringAfter("/genre/").substringBefore("/"),
                        name = it.text(),
                    )
                } ?: listOf(),
            directors = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "RÉALISATEUR:" }
                ?.selectFirst("div.mov-desc span[itemprop='name']")?.text()
                .toPeople(),
            cast = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "ACTEURS:" }
                ?.selectFirst("div.mov-desc span[itemprop='name']")?.text()
                .toPeople(),
            recommendations = recommendations,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val document = service.getTvShow(seasonId)

        // Get poster for episodes
        val episodePoster = document.selectFirst("div.mov-img img[itemprop=thumbnailUrl]")
            ?.attr("src")?.toUrl()

        val epsText = document.selectFirst("div.eps")?.text() ?: return emptyList()
        val episodeLines = epsText.split(" ")
        val episodes = episodeLines.mapNotNull { line ->
            val parts = line.split("!")
            if (parts.size == 2) {
                val episodeNumber = parts[0].toIntOrNull() ?: return@mapNotNull null
                Episode(
                    id = "${seasonId}_${episodeNumber}",
                    number = episodeNumber,
                    poster = episodePoster,
                )
            } else {
                null
            }
        }

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val document = try {
            service.getGenre(id, page)
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> return Genre(id, "")
                else -> throw e
            }
        }

        val genreName = document.title().substringBefore(" - French Anime").substringBefore(" »")

        val shows = document.select("div.mov").mapNotNull { mov ->
            val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
            val link = a.attr("href")
            val itemId = link.substringAfterLast("/").substringBefore(".html")
            val isFrench = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")
                ?.text()).isFrench()
            val title = a.text().toTitle(isFrench)
            val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()

            val isTvShow = mov.selectFirst(".block-ep") != null
            if (isTvShow) {
                TvShow(
                    id = itemId,
                    title = title,
                    poster = poster
                )
            } else {
                Movie(
                    id = itemId,
                    title = title,
                    poster = poster
                )
            }
        }

        return Genre(id = id, name = genreName, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1 && !hasMore) return People(id = id, name = id)

        val document = searchDirect(id, page)

        document.selectFirst("div.berrors")?.let(::checkHasMore)

        val filmography = document.select("div.mov").mapNotNull { mov ->
            val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
            val link = a.attr("href")
            val itemId = link.substringAfterLast("/").substringBefore(".html")
            val isFrench = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")
                ?.text()).isFrench()
            val title = a.text().toTitle(isFrench)
            val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()

            val isTvShow = mov.selectFirst(".block-ep") != null
            if (isTvShow) {
                TvShow(
                    id = itemId,
                    title = title,
                    poster = poster,
                )
            } else {
                Movie(
                    id = itemId,
                    title = title,
                    poster = poster
                )
            }
        }

        return People(id = id, name = id, filmography = filmography)
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val episodeId = when (videoType) {
            is Video.Type.Movie -> id
            is Video.Type.Episode -> id.substringBeforeLast("_")
        }

        val document = service.getTvShow(episodeId)

        val epsText = document.selectFirst("div.eps")?.text() ?: return emptyList()
        val episodeLines = epsText.split(" ")
        val idSuffix = id.substringAfterLast("_")
        val servers = episodeLines.firstNotNullOfOrNull { line ->
            val parts = line.split("!")
            if (parts.size == 2 && (parts[0] == idSuffix || videoType is Video.Type.Movie)) {
                parts[1].split(",").filter { it.isNotEmpty() && it.startsWith("http") }
                    .mapIndexed { index, source ->
                        Video.Server(
                            id = index.toString(),
                            name = source.extractUrlDomain(),
                            src = source
                        )
                    }
            } else {
                null
            }
        } ?: emptyList()

        android.util.Log.d("FrenchAnime", "getServers: found ${servers.size} servers")
        servers.forEach { android.util.Log.d("FrenchAnime", "  server: name=${it.name}, src=${it.src}") }

        // Sort by service reliability
        val sorted = servers.sortedBy { server ->
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
        }

        // 2026-05-05/06 : Cloudstream + Moviebox backups pour les animes FR.
        val title = id.substringBefore("/").substringBefore("@").replace("-", " ").trim()

        val cloudstreamBackup = if (title.isNotBlank()) {
            try {
                val csVideoType: Video.Type = when (videoType) {
                    is Video.Type.Movie -> Video.Type.Movie(
                        id = "0", title = title, releaseDate = videoType.releaseDate,
                        poster = videoType.poster, imdbId = videoType.imdbId,
                    )
                    is Video.Type.Episode -> Video.Type.Episode(
                        id = "0", number = videoType.number, title = videoType.title,
                        poster = videoType.poster, overview = videoType.overview,
                        tvShow = videoType.tvShow.copy(id = "0", title = title),
                        season = videoType.season,
                    )
                }
                CloudstreamProvider.getServers("0", csVideoType)
                    .also { if (it.isNotEmpty()) android.util.Log.d("FrenchAnime", "+ Cloudstream: ${it.size}") }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        val movieboxBackup = if (title.isNotBlank()) {
            try {
                val type = if (videoType is Video.Type.Movie) 1 else 2
                MovieboxProvider.getMovieboxSourcesByTitle(
                    title, null, type,
                    seasonNumber = if (videoType is Video.Type.Episode) videoType.season.number else null,
                    episodeNumber = if (videoType is Video.Type.Episode) videoType.number else null,
                )
                    .also { if (it.isNotEmpty()) android.util.Log.d("FrenchAnime", "+ Moviebox: ${it.size}") }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        // Papadustream backup en dernier (captcha CF)
        val papaBackup = if (title.isNotBlank() && videoType is Video.Type.Episode) {
            try {
                PapadustreamProvider.getPapaSourcesByTitle(
                    title = title,
                    seasonNum = videoType.season.number,
                    episodeNum = videoType.number,
                ).also { if (it.isNotEmpty()) android.util.Log.d("FrenchAnime", "+ Papa: ${it.size}") }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        return sorted + cloudstreamBackup + movieboxBackup + papaBackup
    }

    override suspend fun getVideo(server: Video.Server): Video {
        android.util.Log.d("FrenchAnime", "getVideo: server=${server.name}, src=${server.src}")
        val video = try {
            Extractor.extract(server.src)
        } catch (e: Exception) {
            android.util.Log.e("FrenchAnime", "getVideo FAILED for ${server.name}: ${e.message}")
            throw e
        }
        android.util.Log.d("FrenchAnime", "getVideo result: ${video.source}")
        return video
    }

    private suspend fun fetchTvShows(path: String): List<TvShow> {
        return try {
            service.getTvSeries(path).let { document ->
                document.select("div.mov.clearfix").mapNotNull { extractTvShows(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractTvShows(mov: Element): TvShow? {
        val a = mov.selectFirst("a.mov-t") ?: return null
        val link = a.attr("href")
        val id = link.substringAfterLast("/").substringBefore(".html")
        val isFrench = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")
            ?.text()).isFrench()
        val title = a.text().toTitle(isFrench)
        val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl() ?: ""
        val episodeText = mov.selectFirst(".block-ep")?.text() ?: return null
        val episodeNumberText = episodeText.split(" ").lastOrNull { it.toIntOrNull() != null }
        val episodeNumber = episodeNumberText?.toIntOrNull() ?: 0
        val seasonText = mov.selectFirst(".block-sai")?.text() ?: ""
        val seasonNumber = seasonText.split(" ").getOrNull(1)?.toIntOrNull() ?: 0

        return TvShow(
            id = id,
            title = title,
            poster = poster,
            seasons = if (seasonNumber > 0 && episodeNumber > 0) {
                listOf(
                    Season(
                        id = "",
                        number = seasonNumber,
                        episodes = listOf(
                            Episode(
                                id = "",
                                number = episodeNumber
                            )
                        )
                    )
                )
            } else {
                emptyList()
            }
        )
    }

    private fun checkHasMore(berrorsDiv: Element) {
        val resultText = berrorsDiv.text()
        val totalResults = resultText.substringAfter("Trouvé ")
            .substringBefore(" réponses").toIntOrNull() ?: 0
        val currentRange = resultText.substringAfter("Résultats de la requête ")
            .substringBefore(")").split(" - ")
        val receivedItems = currentRange.getOrNull(1)?.toIntOrNull() ?: 0

        hasMore = receivedItems < totalResults
    }

    private fun String.toUrl(): String = if (startsWith("/")) baseUrl.dropLast(1).plus(this) else this

    private fun String?.isFrench(): Boolean = this?.contains("FRENCH", ignoreCase = true) ?: false

    private fun String.toTitle(isFrench: Boolean): String = if (isFrench) {
        "(FR) $this".replace(" FRENCH", "")
    } else {
        this.replace(" VOSTFR", "")
    }

    private fun String?.toPeople(): List<People> {
        return this?.split(", ")
            ?.map { it.replace(("[^\\p{L}\\d ]").toRegex(), " ").trim() }
            ?.filter { it.isNotEmpty() }
            ?.map {
                People(
                    id = it,
                    name = it,
                    image = "",
                )
            } ?: listOf()
    }

    /** Normalize a title for fuzzy comparison: N°→No , remove punctuation, collapse spaces, lowercase */
    private fun String.normalizeForCompare(): String = this
        .replace("N°", "No ")
        .replace("n°", "no ")
        .replace(Regex("""[.:·\-–—_''""\[\]()!?,;]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .lowercase()

    /** Check if two normalized titles refer to the same franchise.
     *  Handles sequels like "Title 2", "Title 3" matching "Title". */
    private fun titlesFuzzyMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length > b.length) a else b
        // "tate no yuusha" matches "tate no yuusha 2" (word boundary after shorter)
        return longer.startsWith(shorter) && longer.length > shorter.length
                && longer[shorter.length] == ' '
    }

    private fun String.extractUrlDomain(): String {
        return URL_DOMAIN_REGEX.find(this)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() }
            ?: this
    }

    private fun String.extractRuntime() = when {
        contains("h") -> {
            val hours = substringBefore("h").toIntOrNull() ?: 0
            val minutes = substringAfter("h ").substringBefore("min").toIntOrNull() ?: 0
            hours * 60 + minutes
        }
        contains("min") -> substringBefore(" min").toIntOrNull()
        contains("mn") -> substringBefore(" mn").toIntOrNull()
        else -> null
    }

    /**
     * Direct OkHttp search that properly preserves POST body across redirects.
     * DLE (DataLife Engine) sites use POST for search, but when the domain
     * redirects (301/302), OkHttp and Retrofit downgrade POST→GET losing the
     * form body.  This method manually follows redirects while keeping POST.
     */
    private suspend fun searchDirect(query: String, page: Int): org.jsoup.nodes.Document = withContext(Dispatchers.IO) {
        val formBody = okhttp3.FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("story", query)
            .add("search_start", if (page > 1) page.toString() else "0")
            .add("result_from", if (page > 1) ((page - 1) * SEARCH_PAGE_SIZE + 1).toString() else "1")
            .add("full_search", "0")
            .build()

        val url = baseUrl

        // POST direct — le service Retrofit gère déjà les redirects domaine,
        // pas besoin du HEAD probe qui ajoutait 1-2s de latence
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
            .header("Referer", url)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody)
            .build()

        var response = searchClient.newCall(request).execute()

        // Follow POST redirects preserving the body
        var postRedirects = 0
        while (response.isRedirect && postRedirects < 3) {
            val location = response.header("Location") ?: break
            val newUrl = request.url.resolve(location)?.toString() ?: break
            response.close()
            val redirectReq = okhttp3.Request.Builder()
                .url(newUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
                .header("Referer", url)
                .post(formBody)
                .build()
            response = searchClient.newCall(redirectReq).execute()
            postRedirects++
        }

        val html = response.body?.string() ?: ""
        response.close()
        org.jsoup.Jsoup.parse(html, url)
    }

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            service = FrenchAnimeService.build()
        }
        return baseUrl
    }

    private interface FrenchAnimeService {
        companion object {
            fun build(): FrenchAnimeService {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    // Preserve POST method & body on redirects (301/302/303).
                    // Without this, OkHttp downgrades POST→GET and drops form
                    // data, which breaks the DLE search endpoint when the site
                    // redirects to a new domain.
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

                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(FrenchAnimeService::class.java)
            }
        }

        @GET(".")
        suspend fun getHome(): Document

        @POST(".")
        @FormUrlEncoded
        suspend fun search(
            @Field("do") doAction: String = "search",
            @Field("subaction") subAction: String = "search",
            @Field("story") query: String,
            @Field("search_start") searchStart: Int = -1,
            @Field("result_from") resultFrom: Int = -1,
            @Field("full_search") fullSearch: Int = 0,
//            @Field("titleonly") titleOnly: Int = 3, // Narrow to title search
        ): Document

        @GET("films-vf-vostfr/page/{page}")
        suspend fun getMovies(@Path("page") page: Int): Document

        @GET("{path}")
        suspend fun getTvSeries(@Path("path") path: String): Document

        @GET("films-vf-vostfr/{id}.html")
        suspend fun getMovie(@Path("id") id: String): Document

        @GET("{id}.html")
        suspend fun getTvShow(@Path("id") id: String): Document

        @GET("genre/{genre}/page/{page}")
        suspend fun getGenre(
            @Path("genre") genre: String,
            @Path("page") page: Int
        ): Document

        @POST(".")
        @FormUrlEncoded
        suspend fun getPeople(
            @Field("do") doAction: String = "search",
            @Field("subaction") subAction: String = "search",
            @Field("story") query: String,
            @Field("search_start") searchStart: Int = -1,
            @Field("result_from") resultFrom: Int = -1,
            @Field("full_search") fullSearch: Int = 0
        ): Document
    }
}
