package com.streamflixreborn.streamflix.providers

import android.util.Log
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
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

object MovixProvider : Provider, ProviderConfigUrl, ProviderPortalUrl {

    override val name = "Movix"
    override val defaultBaseUrl: String = "https://api.movix.cash/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }
    override val defaultPortalUrl: String = "https://movix.cash/"
    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty { field }
        }
    override val logo: String
        get() {
            val cacheLogo = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_LOGO)
            return cacheLogo.ifEmpty { "${portalUrl}movix.png" }
        }
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private const val AUTO_UPDATE_URL = "https://movix.health/"

    private lateinit var movixServiceInstance: MovixService
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    private const val TMDB_API_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
    private const val TMDB_BASE_URL = "https://api.themoviedb.org/3/"
    private const val TMDB_IMG_W500 = "https://image.tmdb.org/t/p/w500"
    private const val TMDB_IMG_ORIGINAL = "https://image.tmdb.org/t/p/original"

    /**
     * Formate le nom de la langue pour l'affichage.
     * Ex: "vf" -> "VF", "vostfr" -> "VOSTFR", "vo" -> "VO"
     */
    private fun formatLang(lang: String): String {
        val l = lang.trim().lowercase()
        return when {
            l == "vf" || l == "french" || l == "français" -> "VF"
            l == "vostfr" || l == "vost" -> "VOSTFR"
            l == "vo" || l == "original" -> "VO"
            l == "multi" -> "Multi"
            else -> lang.uppercase()
        }
    }

    private val tmdbService: TmdbService by lazy { buildTmdbService() }

    private val genreNames = mapOf(
        "28" to "Action", "12" to "Aventure", "16" to "Animation",
        "35" to "Comédie", "80" to "Crime", "99" to "Documentaire",
        "18" to "Drame", "10751" to "Famille", "14" to "Fantaisie",
        "27" to "Horreur", "9648" to "Mystère", "10749" to "Romance",
        "878" to "Science-Fiction", "53" to "Thriller", "10752" to "Guerre",
        "37" to "Western"
    )

    // ==================== DATA CLASSES ====================

    // --- Movix API responses ---

    data class MovixSearchItem(
        val id: Int?,
        val name: String?,
        val type: String?,
        val tmdb_id: Int?,
        val poster: String?,
        val backdrop: String?,
        val description: String?,
        val release_date: String?,
        val imdb_id: String?
    )

    data class FstreamPlayer(
        val url: String?,
        val type: String?,
        val quality: String?,
        val player: String?
    )

    data class FstreamMovieResponse(
        val success: Boolean?,
        val players: Map<String, List<FstreamPlayer>>?,
        val error: String?,
        val message: String?
    )

    data class FstreamTvEpisode(
        val number: Int?,
        val title: String?,
        val languages: Map<String, List<FstreamPlayer>>?
    )

    data class FstreamTvResponse(
        val success: Boolean?,
        val episodes: Map<String, FstreamTvEpisode>?
    )

    data class LinksMovieData(
        val id: String?,
        val links: List<String>?
    )

    data class LinksMovieResponse(
        val success: Boolean?,
        val data: LinksMovieData?,
        val error: String?,
        val message: String?
    )

    data class LinksTvItem(
        val series_id: String?,
        val season_number: Int?,
        val episode_number: Int?,
        val links: List<String>?
    )

    data class LinksTvResponse(
        val success: Boolean?,
        val data: List<LinksTvItem>?
    )

    data class WiflixEpisodeSource(
        val name: String?,
        val url: String?,
        val episode: Int?,
        val type: String?
    )

    data class WiflixTvResponse(
        val success: Boolean?,
        val episodes: Map<String, Map<String, List<WiflixEpisodeSource>>>?
    )

    data class WiflixMovieResponse(
        val success: Boolean?,
        val players: Map<String, List<WiflixEpisodeSource>>?,
        val error: String?,
        val message: String?
    )

    // --- Cpasmal API responses ---

    data class CpasmalLink(
        val server: String?,
        val url: String?
    )

    data class CpasmalResponse(
        val title: String?,
        val links: Map<String, List<CpasmalLink>>?
    )

    data class CpasmalMovieResponse(
        val title: String?,
        val links: Map<String, List<CpasmalLink>>?
    )

    // --- Series Download API responses ---

    data class SeriesDownloadSource(
        val src: String?,
        val language: String?,
        val quality: String?,
        val m3u8: String?
    )

    data class SeriesDownloadResponse(
        val sources: List<SeriesDownloadSource>?
    )

    // --- TMDB Movix API responses ---

    data class TmdbMovixPlayerLink(
        val decoded_url: String?,
        val quality: String?,
        val language: String?
    )

    data class TmdbMovixEpisode(
        val season_number: Int?,
        val episode_number: Int?,
        val title: String?,
        val iframe_src: String?,
        val player_links: List<TmdbMovixPlayerLink>?
    )

    data class TmdbMovixTvResponse(
        val current_episode: TmdbMovixEpisode?,
        val seasons: List<Any>?
    )

    data class TmdbMovixMovieResponse(
        val iframe_src: String?,
        val player_links: List<TmdbMovixPlayerLink>?
    )

    // --- TMDB API responses ---

    data class TmdbMovieResult(
        val id: Int,
        val title: String?,
        val overview: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val release_date: String?,
        val vote_average: Double?,
        val runtime: Int?,
        val imdb_id: String?,
        val genres: List<TmdbGenre>?,
        val credits: TmdbCredits?
    )

    data class TmdbTvResult(
        val id: Int,
        val name: String?,
        val overview: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val first_air_date: String?,
        val vote_average: Double?,
        val number_of_seasons: Int?,
        val seasons: List<TmdbSeason>?,
        val genres: List<TmdbGenre>?,
        val credits: TmdbCredits?,
        val external_ids: TmdbExternalIds?
    )

    data class TmdbSeason(
        val id: Int?,
        val season_number: Int?,
        val name: String?,
        val poster_path: String?,
        val episode_count: Int?,
        val air_date: String?
    )

    data class TmdbSeasonDetail(
        val id: Int?,
        val season_number: Int?,
        val name: String?,
        val episodes: List<TmdbEpisode>?
    )

    data class TmdbEpisode(
        val id: Int?,
        val episode_number: Int?,
        val name: String?,
        val overview: String?,
        val still_path: String?,
        val air_date: String?
    )

    data class TmdbGenre(
        val id: Int?,
        val name: String?
    )

    data class TmdbCredits(
        val cast: List<TmdbCast>?,
        val crew: List<TmdbCrew>?
    )

    data class TmdbCast(
        val id: Int?,
        val name: String?,
        val profile_path: String?,
        val character: String?
    )

    data class TmdbCrew(
        val id: Int?,
        val name: String?,
        val profile_path: String?,
        val job: String?
    )

    data class TmdbExternalIds(
        val imdb_id: String?
    )

    data class TmdbPageResult<T>(
        val page: Int?,
        val results: List<T>?,
        val total_pages: Int?
    )

    data class TmdbMovieListItem(
        val id: Int,
        val title: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val release_date: String?,
        val vote_average: Double?,
        val overview: String?
    )

    data class TmdbTvListItem(
        val id: Int,
        val name: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val first_air_date: String?,
        val vote_average: Double?,
        val overview: String?
    )

    data class TmdbTrendingItem(
        val id: Int,
        val title: String?,
        val name: String?,
        val media_type: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val release_date: String?,
        val first_air_date: String?,
        val vote_average: Double?,
        val overview: String?
    )

    // ==================== INITIALIZATION ====================

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                try {
                    val activeDomain = fetchActiveDomain()
                    if (!activeDomain.isNullOrEmpty()) {
                        val portalBase = if (activeDomain.endsWith("/")) activeDomain else "$activeDomain/"
                        val apiBase = portalBase.replace("://", "://api.")

                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, apiBase)
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL, portalBase)
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_LOGO, "${portalBase}movix.png")
                        Log.d("MovixProvider", "Auto-update: active domain -> $portalBase (API: $apiBase)")
                    }
                } catch (e: Exception) {
                    Log.e("MovixProvider", "Auto-update failed: ${e.message}")
                }
            }
            movixServiceInstance = buildMovixService()
            serviceInitialized = true
        }
        return baseUrl
    }

    /**
     * Fetches the active Movix domain from movix.health.
     *
     * movix.health is a React SPA that embeds domain data in its JS bundle.
     * The domains are stored as an array of objects with properties:
     *   id, label, url, blocked (boolean), blockedReason (optional)
     *
     * Strategy:
     * 1. Fetch the HTML page to find the JS bundle filename
     * 2. Fetch the JS bundle
     * 3. Regex for the first domain entry with blocked:!1 (= not blocked)
     */
    private fun fetchActiveDomain(): String? {
        val client = OkHttpClient.Builder()
            .readTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()

        // Step 1: Fetch the HTML to find the JS bundle path
        val htmlRequest = Request.Builder()
            .url(AUTO_UPDATE_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val html = client.newCall(htmlRequest).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }

        // Extract JS bundle path: <script ... src="/assets/index-XXXX.js">
        val jsPath = Regex("""src="(/assets/index-[^"]+\.js)"""")
            .find(html)?.groupValues?.get(1)
            ?: return null

        val jsUrl = AUTO_UPDATE_URL.trimEnd('/') + jsPath

        // Step 2: Fetch the JS bundle
        val jsRequest = Request.Builder()
            .url(jsUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val jsContent = client.newCall(jsRequest).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }

        // Step 3: Find the first domain with blocked:!1 (not blocked)
        // Format: url:'https://movix.XXXX',blocked:!1
        val activeDomainUrl = Regex("""url:'(https://movix\.[a-z]+)',blocked:!1""")
            .find(jsContent)?.groupValues?.get(1)

        return activeDomainUrl
    }

    private suspend fun initializeService() {
        initializationMutex.withLock {
            if (serviceInitialized) return
            onChangeUrl()
        }
    }

    // ==================== PROVIDER METHODS ====================

    override suspend fun getHome(): List<Category> {
        initializeService()
        val categories = mutableListOf<Category>()

        try {
            // Featured - trending
            val trending = tmdbService.getTrending(apiKey = TMDB_API_KEY)
            val featuredItems = trending.results?.take(10)?.mapNotNull { item ->
                when (item.media_type) {
                    "movie" -> Movie(
                        id = item.id.toString(),
                        title = item.title ?: item.name ?: "",
                        poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                        banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                        rating = item.vote_average,
                        overview = item.overview,
                        released = item.release_date
                    )
                    "tv" -> TvShow(
                        id = item.id.toString(),
                        title = item.name ?: item.title ?: "",
                        poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                        banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                        rating = item.vote_average,
                        overview = item.overview,
                        released = item.first_air_date
                    )
                    else -> null
                }
            } ?: emptyList()
            if (featuredItems.isNotEmpty()) {
                categories.add(Category(name = Category.FEATURED, list = featuredItems))
            }

            // Séries récentes en premier
            val popularTv = tmdbService.getPopularTvShows(apiKey = TMDB_API_KEY)
            val tvItems = popularTv.results?.map { item ->
                TvShow(
                    id = item.id.toString(),
                    title = item.name ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.first_air_date
                )
            } ?: emptyList()
            if (tvItems.isNotEmpty()) {
                categories.add(Category(name = "Séries populaires", list = tvItems))
            }

            // Top rated TV
            val topTv = tmdbService.getTopRatedTvShows(apiKey = TMDB_API_KEY)
            val topTvItems = topTv.results?.map { item ->
                TvShow(
                    id = item.id.toString(),
                    title = item.name ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.first_air_date
                )
            } ?: emptyList()
            if (topTvItems.isNotEmpty()) {
                categories.add(Category(name = "Séries les mieux notées", list = topTvItems))
            }

            // Puis les films
            val popularMovies = tmdbService.getPopularMovies(apiKey = TMDB_API_KEY)
            val movieItems = popularMovies.results?.map { item ->
                Movie(
                    id = item.id.toString(),
                    title = item.title ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.release_date
                )
            } ?: emptyList()
            if (movieItems.isNotEmpty()) {
                categories.add(Category(name = "Films populaires", list = movieItems))
            }

            // Top rated movies
            val topMovies = tmdbService.getTopRatedMovies(apiKey = TMDB_API_KEY)
            val topMovieItems = topMovies.results?.map { item ->
                Movie(
                    id = item.id.toString(),
                    title = item.title ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.release_date
                )
            } ?: emptyList()
            if (topMovieItems.isNotEmpty()) {
                categories.add(Category(name = "Films les mieux notés", list = topMovieItems))
            }

        } catch (e: Exception) {
            Log.e("MovixProvider", "Error loading home: ${e.message}")
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
            if (page > 1) return emptyList()
            return listOf(
                Genre(id = "28", name = "Action"),
                Genre(id = "12", name = "Aventure"),
                Genre(id = "16", name = "Animation"),
                Genre(id = "35", name = "Comédie"),
                Genre(id = "80", name = "Crime"),
                Genre(id = "99", name = "Documentaire"),
                Genre(id = "18", name = "Drame"),
                Genre(id = "10751", name = "Famille"),
                Genre(id = "14", name = "Fantaisie"),
                Genre(id = "27", name = "Horreur"),
                Genre(id = "k-drama", name = "K-Drama"),
                Genre(id = "9648", name = "Mystère"),
                Genre(id = "10749", name = "Romance"),
                Genre(id = "878", name = "Science-Fiction"),
                Genre(id = "53", name = "Thriller"),
                Genre(id = "10752", name = "Guerre"),
                Genre(id = "37", name = "Western")
            )
        }

        return try {
            val tmdbResults = TMDb3.Search.multi(query, language = "fr-FR", page = page)
            tmdbResults.results.mapNotNull { item ->
                when (item) {
                    is TMDb3.Movie -> Movie(
                        id = item.id.toString(),
                        title = item.title,
                        poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                        banner = item.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
                        overview = item.overview,
                        released = item.releaseDate
                    )
                    is TMDb3.Tv -> TvShow(
                        id = item.id.toString(),
                        title = item.name,
                        poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                        banner = item.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
                        overview = item.overview,
                        released = item.firstAirDate
                    )
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.e("MovixProvider", "TMDB search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val result = tmdbService.discoverMovies(apiKey = TMDB_API_KEY, page = page)
            result.results?.map { item ->
                Movie(
                    id = item.id.toString(),
                    title = item.title ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.release_date
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("MovixProvider", "getMovies error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val result = tmdbService.discoverTvShows(apiKey = TMDB_API_KEY, page = page)
            result.results?.map { item ->
                TvShow(
                    id = item.id.toString(),
                    title = item.name ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.first_air_date
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("MovixProvider", "getTvShows error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val tmdb = tmdbService.getMovieDetails(
            id = id.toInt(),
            apiKey = TMDB_API_KEY,
            appendToResponse = "credits"
        )

        val directors = tmdb.credits?.crew
            ?.filter { it.job == "Director" }
            ?.map { People(id = it.id.toString(), name = it.name ?: "", image = it.profile_path?.let { p -> "$TMDB_IMG_W500$p" }) }
            ?: emptyList()

        val cast = tmdb.credits?.cast?.take(20)?.map {
            People(
                id = it.id.toString(),
                name = it.name ?: "",
                image = it.profile_path?.let { p -> "$TMDB_IMG_W500$p" }
            )
        } ?: emptyList()

        val genres = tmdb.genres?.map {
            Genre(id = it.id.toString(), name = it.name ?: "")
        } ?: emptyList()

        // Get recommendations
        val recommendations: List<Show> = try {
            val recs = tmdbService.getMovieRecommendations(id = id.toInt(), apiKey = TMDB_API_KEY)
            recs.results?.map { item ->
                Movie(
                    id = item.id.toString(),
                    title = item.title ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average
                )
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }

        return Movie(
            id = tmdb.id.toString(),
            title = tmdb.title ?: "",
            overview = tmdb.overview,
            released = tmdb.release_date,
            runtime = tmdb.runtime,
            rating = tmdb.vote_average,
            poster = tmdb.poster_path?.let { "$TMDB_IMG_W500$it" },
            banner = tmdb.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
            imdbId = tmdb.imdb_id,
            genres = genres,
            directors = directors,
            cast = cast,
            recommendations = recommendations
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val tmdb = tmdbService.getTvDetails(
            id = id.toInt(),
            apiKey = TMDB_API_KEY,
            appendToResponse = "credits,external_ids"
        )

        val seasons = tmdb.seasons
            ?.filter { (it.season_number ?: 0) > 0 }
            ?.map { s ->
                Season(
                    id = "$id/${s.season_number}",
                    number = s.season_number ?: 0,
                    title = s.name ?: "Saison ${s.season_number}",
                    poster = s.poster_path?.let { "$TMDB_IMG_W500$it" }
                )
            } ?: emptyList()

        val cast = tmdb.credits?.cast?.take(20)?.map {
            People(
                id = it.id.toString(),
                name = it.name ?: "",
                image = it.profile_path?.let { p -> "$TMDB_IMG_W500$p" }
            )
        } ?: emptyList()

        val genres = tmdb.genres?.map {
            Genre(id = it.id.toString(), name = it.name ?: "")
        } ?: emptyList()

        val recommendations: List<Show> = try {
            val recs = tmdbService.getTvRecommendations(id = id.toInt(), apiKey = TMDB_API_KEY)
            recs.results?.map { item ->
                TvShow(
                    id = item.id.toString(),
                    title = item.name ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average
                )
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }

        return TvShow(
            id = tmdb.id.toString(),
            title = tmdb.name ?: "",
            overview = tmdb.overview,
            released = tmdb.first_air_date,
            rating = tmdb.vote_average,
            poster = tmdb.poster_path?.let { "$TMDB_IMG_W500$it" },
            banner = tmdb.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
            imdbId = tmdb.external_ids?.imdb_id,
            seasons = seasons,
            genres = genres,
            cast = cast,
            recommendations = recommendations
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("/")
        if (parts.size < 2) return emptyList()
        val tvShowId = parts[0]
        val seasonNumber = parts[1].toIntOrNull() ?: return emptyList()

        return try {
            val detail = tmdbService.getSeasonDetails(
                tvId = tvShowId.toInt(),
                seasonNumber = seasonNumber,
                apiKey = TMDB_API_KEY
            )
            detail.episodes?.map { ep ->
                Episode(
                    id = "$tvShowId-s${seasonNumber}e${ep.episode_number}",
                    number = ep.episode_number ?: 0,
                    title = ep.name ?: "Épisode ${ep.episode_number}",
                    released = ep.air_date,
                    poster = ep.still_path?.let { "$TMDB_IMG_W500$it" },
                    overview = ep.overview
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("MovixProvider", "getEpisodesBySeason error: ${e.message}")
            emptyList()
        }
    }

    // Genres spéciaux basés sur le pays d'origine (pas un ID TMDB numérique)
    private val specialGenres = mapOf(
        "k-drama" to "KR",
        "drama-coreen" to "KR",
        "K-Drama|" to "KR"
    )

    override suspend fun getGenre(id: String, page: Int): Genre {
        return try {
            val shows = mutableListOf<Show>()
            val originCountry = specialGenres[id]
            val genreFilter = if (originCountry != null) null else id

            // Films du genre (ou du pays d'origine)
            try {
                val movieResult = tmdbService.discoverMovies(
                    apiKey = TMDB_API_KEY,
                    page = page,
                    withGenres = genreFilter,
                    withOriginCountry = originCountry
                )
                movieResult.results?.forEach { item ->
                    shows.add(
                        Movie(
                            id = item.id.toString(),
                            title = item.title ?: "",
                            poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                            banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                            rating = item.vote_average,
                            released = item.release_date
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("MovixProvider", "getGenre movies error: ${e.message}")
            }

            // Séries TV du genre (ou du pays d'origine)
            try {
                val tvResult = tmdbService.discoverTvShows(
                    apiKey = TMDB_API_KEY,
                    page = page,
                    withGenres = genreFilter,
                    withOriginCountry = originCountry
                )
                tvResult.results?.forEach { item ->
                    shows.add(
                        TvShow(
                            id = item.id.toString(),
                            title = item.name ?: "",
                            poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                            banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                            rating = item.vote_average,
                            released = item.first_air_date
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("MovixProvider", "getGenre tvShows error: ${e.message}")
            }

            // Mélanger films et séries par popularité (rating décroissant)
            val sorted = shows.sortedByDescending { show ->
                when (show) {
                    is Movie -> show.rating ?: 0.0
                    is TvShow -> show.rating ?: 0.0
                }
            }

            val genreName = when {
                originCountry == "KR" -> "K-Drama"
                else -> genreNames[id] ?: id
            }
            Genre(id = id, name = genreName, shows = sorted)
        } catch (e: Exception) {
            Genre(id = id, name = genreNames[id] ?: id)
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id = id, name = "")
        return try {
            val person = tmdbService.getPersonDetails(id = id.toInt(), apiKey = TMDB_API_KEY)
            People(
                id = id,
                name = person.name ?: "",
                image = person.profile_path?.let { "$TMDB_IMG_W500$it" },
                biography = person.biography,
                birthday = person.birthday,
                deathday = person.deathday,
                placeOfBirth = person.place_of_birth
            )
        } catch (e: Exception) {
            People(id = id, name = "")
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()
        val servers = mutableListOf<Video.Server>()

        when (videoType) {
            is Video.Type.Movie -> {
                val tmdbId = id
                Log.d("MovixProvider", "getServers Movie tmdbId=$tmdbId")

                // Fstream sources
                try {
                    val fstream = movixServiceInstance.getFstreamMovie(tmdbId)
                    if (fstream.success == true) {
                        fstream.players?.forEach { (lang, players) ->
                            players.forEach { player ->
                                val url = player.url ?: return@forEach
                                if (url.isBlank()) return@forEach
                                val displayLang = formatLang(lang)
                                val quality = player.quality?.takeIf { it.isNotBlank() } ?: "HD"
                                val playerName = player.player?.takeIf { it.isNotBlank() }
                                    ?: guessPlayerName(url)
                                servers.add(
                                    Video.Server(
                                        id = "fstream-$lang-${servers.size}",
                                        name = "$playerName ($displayLang - $quality)",
                                        src = url
                                    )
                                )
                            }
                        }
                        Log.d("MovixProvider", "Fstream: ${servers.size} servers found")
                    } else {
                        Log.w("MovixProvider", "Fstream: ${fstream.error ?: fstream.message ?: "no content"}")
                    }
                } catch (e: Exception) {
                    Log.e("MovixProvider", "Fstream movie error: ${e.message}")
                }

                // Links sources
                val preLinksCount = servers.size
                try {
                    val links = movixServiceInstance.getLinksMovie(tmdbId)
                    if (links.success == true) {
                        links.data?.links?.forEachIndexed { index, url ->
                            if (url.isNotBlank()) {
                                val playerName = guessPlayerName(url)
                                servers.add(
                                    Video.Server(
                                        id = "links-$index",
                                        name = "$playerName (Link ${index + 1})",
                                        src = url
                                    )
                                )
                            }
                        }
                        Log.d("MovixProvider", "Links: ${servers.size - preLinksCount} servers found")
                    } else {
                        Log.w("MovixProvider", "Links: ${links.error ?: links.message ?: "no content"}")
                    }
                } catch (e: Exception) {
                    Log.e("MovixProvider", "Links movie error: ${e.message}")
                }

                // Wiflix sources
                val preWiflixCount = servers.size
                try {
                    val wiflix = movixServiceInstance.getWiflixMovie(tmdbId)
                    if (wiflix.success == true) {
                        wiflix.players?.forEach { (lang, sources) ->
                            val displayLang = formatLang(lang)
                            sources.forEach { source ->
                                val url = source.url ?: return@forEach
                                if (url.isBlank()) return@forEach
                                val playerName = source.name?.takeIf { it.isNotBlank() }
                                    ?: guessPlayerName(url)
                                servers.add(
                                    Video.Server(
                                        id = "wiflix-$lang-${servers.size}",
                                        name = "$playerName ($displayLang)",
                                        src = url
                                    )
                                )
                            }
                        }
                        Log.d("MovixProvider", "Wiflix: ${servers.size - preWiflixCount} servers found")
                    } else {
                        Log.w("MovixProvider", "Wiflix: ${wiflix.error ?: wiflix.message ?: "no content"}")
                    }
                } catch (e: Exception) {
                    Log.e("MovixProvider", "Wiflix movie error: ${e.message}")
                }

                // Cpasmal movie sources
                try {
                    val cpasmal = movixServiceInstance.getCpasmalMovie(tmdbId)
                    cpasmal.links?.forEach { (lang, links) ->
                        val displayLang = formatLang(lang)
                        links.forEach { link ->
                            val url = link.url ?: return@forEach
                            if (url.isBlank()) return@forEach
                            val playerName = link.server?.replaceFirstChar { it.uppercase() }
                                ?: guessPlayerName(url)
                            servers.add(
                                Video.Server(
                                    id = "cpasmal-$lang-${servers.size}",
                                    name = "$playerName ($displayLang)",
                                    src = url
                                )
                            )
                        }
                    }
                    Log.d("MovixProvider", "Cpasmal movie: ${servers.size} total servers")
                } catch (e: Exception) {
                    Log.e("MovixProvider", "Cpasmal movie error: ${e.message}")
                }

                // TMDB Movix movie sources
                try {
                    val tmdbMovix = movixServiceInstance.getTmdbMovixMovie(tmdbId)
                    tmdbMovix.player_links?.forEach { link ->
                        val url = link.decoded_url ?: return@forEach
                        if (url.isBlank()) return@forEach
                        val lang = if (link.language?.lowercase()?.contains("french") == true) "VF"
                            else link.language ?: ""
                        val qualityLabel = link.quality?.substringBefore("/")?.trim() ?: "HD"
                        val playerName = guessPlayerName(url)
                        servers.add(
                            Video.Server(
                                id = "tmdbmovix-${servers.size}",
                                name = "$playerName - $qualityLabel ($lang)",
                                src = url
                            )
                        )
                    }
                    Log.d("MovixProvider", "TmdbMovix movie: ${servers.size} total servers")
                } catch (e: Exception) {
                    Log.e("MovixProvider", "TmdbMovix movie error: ${e.message}")
                }

                Log.i("MovixProvider", "Total servers for movie $tmdbId: ${servers.size}")
            }

            is Video.Type.Episode -> {
                val tmdbId = videoType.tvShow.id
                val seasonNum = videoType.season.number
                val episodeNum = videoType.number

                // Fstream TV sources
                try {
                    val fstream = movixServiceInstance.getFstreamTv(tmdbId, seasonNum)
                    val episode = fstream.episodes?.get(episodeNum.toString())
                    episode?.languages?.forEach { (lang, players) ->
                        val displayLang = formatLang(lang)
                        players.forEach { player ->
                            val url = player.url ?: return@forEach
                            if (url.isBlank()) return@forEach
                            val quality = player.quality?.takeIf { it.isNotBlank() } ?: "HD"
                            val playerName = player.player?.takeIf { it.isNotBlank() }
                                ?: guessPlayerName(url)
                            servers.add(
                                Video.Server(
                                    id = "fstream-$lang-${servers.size}",
                                    name = "$playerName ($displayLang - $quality)",
                                    src = url
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MovixProvider", "Fstream tv error: ${e.message}")
                }

                // Links TV sources
                try {
                    val links = movixServiceInstance.getLinksTv(tmdbId, seasonNum, episodeNum)
                    links.data?.forEach { item ->
                        item.links?.forEachIndexed { index, url ->
                            if (url.isNotBlank()) {
                                val playerName = guessPlayerName(url)
                                servers.add(
                                    Video.Server(
                                        id = "links-tv-$index",
                                        name = "$playerName (Link ${index + 1})",
                                        src = url
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MovixProvider", "Links tv error: ${e.message}")
                }

                // Wiflix TV sources
                try {
                    val wiflix = movixServiceInstance.getWiflixTv(tmdbId, seasonNum)
                    val epSources = wiflix.episodes?.get(episodeNum.toString())
                    epSources?.forEach { (lang, sources) ->
                        val displayLang = formatLang(lang)
                        sources.forEach { source ->
                            val url = source.url ?: return@forEach
                            if (url.isBlank()) return@forEach
                            val playerName = source.name?.takeIf { it.isNotBlank() }
                                ?: guessPlayerName(url)
                            servers.add(
                                Video.Server(
                                    id = "wiflix-$lang-${servers.size}",
                                    name = "$playerName ($displayLang)",
                                    src = url
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MovixProvider", "Wiflix tv error: ${e.message}")
                }

                // Cpasmal TV sources
                try {
                    val cpasmal = movixServiceInstance.getCpasmalTv(tmdbId, seasonNum, episodeNum)
                    cpasmal.links?.forEach { (lang, links) ->
                        val displayLang = formatLang(lang)
                        links.forEach { link ->
                            val url = link.url ?: return@forEach
                            if (url.isBlank()) return@forEach
                            val playerName = link.server?.replaceFirstChar { it.uppercase() }
                                ?: guessPlayerName(url)
                            servers.add(
                                Video.Server(
                                    id = "cpasmal-$lang-${servers.size}",
                                    name = "$playerName ($displayLang)",
                                    src = url
                                )
                            )
                        }
                    }
                    Log.d("MovixProvider", "Cpasmal tv: added servers")
                } catch (e: Exception) {
                    Log.e("MovixProvider", "Cpasmal tv error: ${e.message}")
                }

                // TMDB Movix TV sources
                try {
                    val tmdbMovix = movixServiceInstance.getTmdbMovixTv(tmdbId, seasonNum, episodeNum)
                    tmdbMovix.current_episode?.player_links?.forEach { link ->
                        val url = link.decoded_url ?: return@forEach
                        if (url.isBlank()) return@forEach
                        val lang = if (link.language?.lowercase()?.contains("french") == true) "VF"
                            else link.language ?: ""
                        val qualityLabel = link.quality?.substringBefore("/")?.trim() ?: "HD"
                        val playerName = guessPlayerName(url)
                        servers.add(
                            Video.Server(
                                id = "tmdbmovix-${servers.size}",
                                name = "$playerName - $qualityLabel ($lang)",
                                src = url
                            )
                        )
                    }
                    Log.d("MovixProvider", "TmdbMovix tv: added servers")
                } catch (e: Exception) {
                    Log.e("MovixProvider", "TmdbMovix tv error: ${e.message}")
                }

                // Series Download sources (darkibox m3u8 direct)
                try {
                    // Search for internal Movix series ID
                    val showTitle = videoType.tvShow.title
                    val searchResults = movixServiceInstance.searchMovix(showTitle)
                    val movixShow = searchResults.firstOrNull { it.type == "tv" && it.tmdb_id?.toString() == tmdbId }
                    val movixId = movixShow?.id?.toString()
                    if (movixId != null) {
                        val dl = movixServiceInstance.getSeriesDownload(movixId, seasonNum, episodeNum)
                        dl.sources?.forEach { source ->
                            val m3u8Url = source.m3u8
                            val embedUrl = source.src
                            val url = if (!m3u8Url.isNullOrBlank()) m3u8Url else embedUrl ?: return@forEach
                            if (url.isBlank()) return@forEach
                            val displayLang = source.language?.uppercase() ?: "MULTI"
                            val quality = source.quality ?: "HD"
                            val playerName = if (!m3u8Url.isNullOrBlank()) "Darkibox HLS" else guessPlayerName(url)
                            servers.add(
                                Video.Server(
                                    id = "seriesdl-${servers.size}",
                                    name = "$playerName ($displayLang - $quality)",
                                    src = url
                                )
                            )
                        }
                        Log.d("MovixProvider", "SeriesDownload tv: added servers")
                    }
                } catch (e: Exception) {
                    Log.e("MovixProvider", "SeriesDownload tv error: ${e.message}")
                }
            }
        }

        // Trier les serveurs : VF en premier, puis VO, puis VOSTFR en dernier
        return sortServersByLanguage(servers)
    }

    /**
     * Devine le nom du player/extracteur à partir de l'URL (pour le fallback par nom dans Extractor).
     * Ex: "https://filemoon.sx/e/abc123" -> "Filemoon"
     *     "https://streamtape.com/e/xyz" -> "Streamtape"
     */
    private fun guessPlayerName(url: String): String {
        // Try the accurate extractor-based detection first
        Extractor.identifyServiceName(url)?.let { return it }
        // Fallback: derive from domain name
        return try {
            val host = url.substringAfter("://").substringBefore("/").substringBefore(":")
            val domain = host.removePrefix("www.")
            val name = domain.substringBeforeLast(".").substringBeforeLast(".")
                .ifEmpty { domain.substringBeforeLast(".") }
            name.replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            "Serveur"
        }
    }

    /**
     * Trie les serveurs par priorité de langue :
     * 1. VF (Version Française) en premier — sous-trié par fiabilité extracteur
     * 2. VO / Multi / sans langue spécifiée au milieu
     * 3. VOSTFR (Version Originale Sous-Titrée FR) en dernier (max 2)
     */
    private fun sortServersByLanguage(servers: List<Video.Server>): List<Video.Server> {
        val vfServers = mutableListOf<Video.Server>()
        val vostfrServers = mutableListOf<Video.Server>()
        val defaultServers = mutableListOf<Video.Server>()

        servers.forEach { server ->
            val name = server.name.lowercase()
            when {
                name.contains("vostfr") || name.contains("sous-titr") -> vostfrServers.add(server)
                name.contains("vf") || name.contains("french") || name.contains("français")
                    || name.contains("francais") -> vfServers.add(server)
                else -> defaultServers.add(server) // DEFAULT = VF par défaut
            }
        }

        // Sous-trier VF et DEFAULT par fiabilité des extracteurs
        val sortedVf = vfServers.sortedBy { serverReliabilityScore(it) }
        val sortedDefault = defaultServers.sortedBy { serverReliabilityScore(it) }

        // Garder tous les VF + DEFAULT, et max 2 VOSTFR
        val result = mutableListOf<Video.Server>()
        result.addAll(sortedVf)
        result.addAll(sortedDefault)
        result.addAll(vostfrServers.take(2))

        Log.d("MovixProvider", "Servers: ${vfServers.size} VF, ${defaultServers.size} DEFAULT, ${vostfrServers.size} VOSTFR (kept ${vostfrServers.take(2).size})")
        return result
    }

    /**
     * Score de fiabilité des extracteurs (plus bas = meilleur, affiché en premier).
     * Basé sur les tests réels de disponibilité des serveurs.
     */
    private fun serverReliabilityScore(server: Video.Server): Int {
        val name = server.name.lowercase()
        val src = server.src.lowercase()
        return when {
            // === Tier 1 : Les meilleurs (fiables, rapides) ===
            src.contains("darkibox") || name.contains("darkibox") || name.contains("darki") -> 0
            src.contains("vixsrc") -> 1
            src.contains("flemmix") || name.contains("flemmix") -> 2
            // Netu/Waaw : CronetDataSource + TLS Chromium, très fiable
            src.contains("netu") || name.contains("netu")
                || src.contains("frembed") || name.contains("frembed")
                || src.contains("waaw") || name.contains("waaw") -> 3
            // VidHide/Minochinos : fonctionne bien (hls2 CDN)
            src.contains("minochinos") || name.contains("minochinos")
                || src.contains("vidhide") || name.contains("vidhide")
                || src.contains("filelions") || name.contains("filelions") -> 4

            // === Tier 2 : Fonctionnels ===
            name.contains("vidzy") || src.contains("vidzy") -> 5
            src.contains("uqload") || name.contains("uqload") -> 22
            src.contains("filemoon") || name.contains("filemoon") -> 7
            src.contains("streamwish") || name.contains("streamwish") -> 8
            src.contains("savefiles") || name.contains("savefiles") -> 9
            src.contains("playmogo") || name.contains("playmogo") -> 10
            src.contains("vidguard") || name.contains("vidguard") -> 11

            // === Tier 3 : Lents ou instables ===
            src.contains("dood") || name.contains("dood") -> 15
            src.contains("vidsonic") || name.contains("vidsonic") -> 20
            src.contains("vidara") || name.contains("vidara") -> 21
            src.contains("voe.sx") || name.contains("voe") -> 6
            src.contains("luluvdo") || name.contains("luluvdo") -> 23

            // === Tier 4 : Cassés ou mauvaise qualité ===
            src.contains("hgcloud") || name.contains("hgcloud") -> 25
            // xshotcok : HS - embeds désactivés (hxfile.co/embed_disabled)
            src.contains("xshotcok") || name.contains("xshotcok") -> 99

            // Défaut
            else -> 12
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.video != null) return server.video!!

        var url = server.src.trim()

        // Résolution de redirections courantes avant extraction
        try {
            if (url.contains("/redirect") || url.contains("/go/") || url.contains("/out/")) {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                val response = client.newCall(request).execute()
                val finalUrl = response.request.url.toString()
                response.close()
                if (finalUrl.isNotEmpty() && finalUrl != url) {
                    Log.d("MovixProvider", "Redirect resolved: $url -> $finalUrl")
                    url = finalUrl
                }
            }
        } catch (e: Exception) {
            Log.w("MovixProvider", "Redirect resolution failed: ${e.message}")
        }

        // Gestion des liens vidéo directs (m3u8, mp4, etc.)
        val directExtensions = listOf(".m3u8", ".mp4", ".mkv", ".webm", ".avi")
        val urlLower = url.lowercase().split("?").first()
        if (directExtensions.any { urlLower.endsWith(it) }) {
            val type = when {
                urlLower.endsWith(".m3u8") -> "application/x-mpegURL"
                urlLower.endsWith(".mp4") -> "video/mp4"
                urlLower.endsWith(".mkv") -> "video/x-matroska"
                urlLower.endsWith(".webm") -> "video/webm"
                else -> "video/mp4"
            }
            // Add Referer for darkibox direct HLS URLs
            val headers = if (url.contains("darkibox.com")) {
                mapOf("Referer" to "https://darkibox.com")
            } else {
                emptyMap()
            }
            return Video(
                source = url,
                type = type,
                headers = headers
            )
        }

        // Extraction via les extracteurs enregistrés (avec server pour le fallback par nom)
        return Extractor.extract(url, server)
    }

    // ==================== TMDB PERSON ====================

    data class TmdbPersonDetail(
        val id: Int?,
        val name: String?,
        val biography: String?,
        val birthday: String?,
        val deathday: String?,
        val place_of_birth: String?,
        val profile_path: String?
    )

    // ==================== RETROFIT SERVICES ====================

    private interface MovixService {
        @GET("api/search")
        suspend fun search(
            @Query("title") title: String
        ): List<MovixSearchItem>

        @GET("api/fstream/movie/{tmdbId}")
        suspend fun getFstreamMovie(
            @Path("tmdbId") tmdbId: String
        ): FstreamMovieResponse

        @GET("api/fstream/tv/{tmdbId}/season/{season}")
        suspend fun getFstreamTv(
            @Path("tmdbId") tmdbId: String,
            @Path("season") season: Int
        ): FstreamTvResponse

        @GET("api/links/movie/{tmdbId}")
        suspend fun getLinksMovie(
            @Path("tmdbId") tmdbId: String
        ): LinksMovieResponse

        @GET("api/links/tv/{tmdbId}")
        suspend fun getLinksTv(
            @Path("tmdbId") tmdbId: String,
            @Query("season") season: Int,
            @Query("episode") episode: Int
        ): LinksTvResponse

        @GET("api/wiflix/movie/{tmdbId}")
        suspend fun getWiflixMovie(
            @Path("tmdbId") tmdbId: String
        ): WiflixMovieResponse

        @GET("api/wiflix/tv/{tmdbId}/{season}")
        suspend fun getWiflixTv(
            @Path("tmdbId") tmdbId: String,
            @Path("season") season: Int
        ): WiflixTvResponse

        // --- Cpasmal endpoints ---

        @GET("api/cpasmal/movie/{tmdbId}")
        suspend fun getCpasmalMovie(
            @Path("tmdbId") tmdbId: String
        ): CpasmalMovieResponse

        @GET("api/cpasmal/tv/{tmdbId}/{season}/{episode}")
        suspend fun getCpasmalTv(
            @Path("tmdbId") tmdbId: String,
            @Path("season") season: Int,
            @Path("episode") episode: Int
        ): CpasmalResponse

        // --- Series Download endpoints ---

        @GET("api/series/download/{seriesId}/season/{season}/episode/{episode}")
        suspend fun getSeriesDownload(
            @Path("seriesId") seriesId: String,
            @Path("season") season: Int,
            @Path("episode") episode: Int
        ): SeriesDownloadResponse

        @GET("api/series/download/{movieId}")
        suspend fun getMovieDownload(
            @Path("movieId") movieId: String
        ): SeriesDownloadResponse

        // --- TMDB Movix endpoints ---

        @GET("api/tmdb/tv/{tmdbId}")
        suspend fun getTmdbMovixTv(
            @Path("tmdbId") tmdbId: String,
            @Query("season") season: Int,
            @Query("episode") episode: Int
        ): TmdbMovixTvResponse

        @GET("api/tmdb/movie/{tmdbId}")
        suspend fun getTmdbMovixMovie(
            @Path("tmdbId") tmdbId: String
        ): TmdbMovixMovieResponse

        // --- Search for internal series ID ---

        @GET("api/search")
        suspend fun searchMovix(
            @Query("title") title: String
        ): List<MovixSearchItem>
    }

    private interface TmdbService {
        @GET("trending/all/day")
        suspend fun getTrending(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR"
        ): TmdbPageResult<TmdbTrendingItem>

        @GET("movie/popular")
        suspend fun getPopularMovies(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbMovieListItem>

        @GET("movie/top_rated")
        suspend fun getTopRatedMovies(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbMovieListItem>

        @GET("tv/popular")
        suspend fun getPopularTvShows(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbTvListItem>

        @GET("tv/top_rated")
        suspend fun getTopRatedTvShows(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbTvListItem>

        @GET("discover/movie")
        suspend fun discoverMovies(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1,
            @Query("sort_by") sortBy: String = "popularity.desc",
            @Query("with_genres") withGenres: String? = null,
            @Query("with_origin_country") withOriginCountry: String? = null,
            @Query("include_adult") includeAdult: Boolean = false
        ): TmdbPageResult<TmdbMovieListItem>

        @GET("discover/tv")
        suspend fun discoverTvShows(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1,
            @Query("sort_by") sortBy: String = "popularity.desc",
            @Query("with_genres") withGenres: String? = null,
            @Query("with_origin_country") withOriginCountry: String? = null,
            @Query("include_adult") includeAdult: Boolean = false
        ): TmdbPageResult<TmdbTvListItem>

        @GET("movie/{id}")
        suspend fun getMovieDetails(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("append_to_response") appendToResponse: String? = null
        ): TmdbMovieResult

        @GET("tv/{id}")
        suspend fun getTvDetails(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("append_to_response") appendToResponse: String? = null
        ): TmdbTvResult

        @GET("tv/{tv_id}/season/{season_number}")
        suspend fun getSeasonDetails(
            @Path("tv_id") tvId: Int,
            @Path("season_number") seasonNumber: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR"
        ): TmdbSeasonDetail

        @GET("movie/{id}/recommendations")
        suspend fun getMovieRecommendations(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR"
        ): TmdbPageResult<TmdbMovieListItem>

        @GET("tv/{id}/recommendations")
        suspend fun getTvRecommendations(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR"
        ): TmdbPageResult<TmdbTvListItem>

        @GET("person/{id}")
        suspend fun getPersonDetails(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("append_to_response") appendToResponse: String? = null
        ): TmdbPersonDetail
    }

    // ==================== SERVICE BUILDERS ====================

    private fun buildMovixService(): MovixService {
        val client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Referer", portalUrl)
                    .header("Origin", portalUrl.trimEnd('/'))
                    .build()
                Log.d("MovixProvider", "API Request: ${request.url}")
                val response = chain.proceed(request)
                if (!response.isSuccessful) {
                    val body = response.peekBody(2048).string()
                    Log.e("MovixProvider", "API Error ${response.code}: ${request.url} -> $body")
                }
                response
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(MovixService::class.java)
    }

    private fun buildTmdbService(): TmdbService {
        val client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()

        return Retrofit.Builder()
            .baseUrl(TMDB_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(TmdbService::class.java)
    }
}
