package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.extractors.FrembedExtractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.Response
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.Header
import retrofit2.http.Query
import kotlin.collections.map

object FrembedProvider : Provider, ProviderPortalUrl, ProviderConfigUrl, ProgressiveServersProvider {
    override val name = "Frembed"

    // 2026-06-29 (REPAIR — user "mets la nouvelle URL directement en principal") :
    //   domaine courant = frembed.hair (audin213.com + frembed.bond = anciens, on
    //   les ignore dans le cache pour que les users existants basculent direct).
    override val defaultPortalUrl: String = "https://frembed.hair/"

    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            val isValid = cachePortalURL.length > 10 && cachePortalURL.startsWith("http") &&
                !cachePortalURL.contains("audin213") && !cachePortalURL.contains("frembed.bond")
            return if (isValid) cachePortalURL else field
        }

    // 2026-06-29 : URL de base = frembed.hair en dur (domaine courant).
    override val defaultBaseUrl: String = "https://frembed.hair/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            // ignore cache pourri (= "/", vide, sans http) + anciens domaines (audin213, bond)
            val isValid = cacheURL.length > 10 && cacheURL.startsWith("http") &&
                !cacheURL.contains("audin213") && !cacheURL.contains("frembed.bond")
            return if (isValid) cacheURL else field
        }

    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_frembed"
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    private val genres: HashMap<String, String> = hashMapOf("28" to "Action",
        "12" to "Adventure",
        "16" to "Animation",
        "35" to "Comedy",
        "80" to "Crime",
        "99" to "Documentary",
        "19" to "Drama",
        "10751" to "Family",
        "14" to "Fantasy",
        "36" to "History",
        "27" to "Horror",
        "k-drama" to "K-Drama",
        "10402" to "Music",
        "9648" to "Mystery",
        "10749" to "Romance",
        "878" to "Sci-Fi",
        "10770" to "TV Movie",
        "53" to "Thriller",
        "10752" to "War",
        "37" to "Western"
    )

    data class FrembedCastItem(
        val id: Int,
        val name: String,
        val profile_path: String?
    )

    fun FrembedCastItem.toPeople(): People =
        People( id = id.toString(),
            name = name,
            image = profile_path)

    data class FrembedSimilarItem(
        val tmdb: Int,
        val title: String,
        val poster_path: String?
    )

    fun List<FrembedSimilarItem>.toListShow(movie: Boolean = false, tvshow: Boolean = false): List<Show> =
        if (movie == false && tvshow)
            this.map { it ->
                TvShow( id = it.tmdb.toString(),
                    title = it.title,
                    poster = it.poster_path?.w500
                )
            }
        else
            this.map { it ->
                Movie( id = it.tmdb.toString(),
                    title = it.title,
                    poster = it.poster_path?.w500
                )
            }

    fun List<FrembedSeasonResponse>.toListSeason(id:String, posters: List<String>) : List<Season> =
        this.mapIndexed { idx, it ->
            val poster = if (posters.isNotEmpty()) {
                posters[idx % posters.size].w500
            } else {
                null
            }
            Season( id="$id/"+it.sa, number = it.sa, title = "Saison "+it.sa, poster = poster?.w500 )
        }

    data class FrembedShowItem(
        val director: String?,
        val genres: String?,
        val imdb: String?,
        val tmdb: Int?,
        val overview: String?,
        val overview_fr: String?,
        val rating: Double?,
        val title: String?,
        val title_fr: String?,
        val trailer: String?,
        val year: String?,
        val poster: String?,
        val backdrops: List<String>?,
        val cast: List<FrembedCastItem>?
    )

    data class FrembedShortCutItem(
        val tmdb: String?,
        val id: Int?,
        val imdb: String?,
        val title: String?,
        val title_fr: String?,
        val name: String?,
        val director: String,
        val cast: List<FrembedCastItem>,
        val poster: String?,
        val poster_path: String?,
        val version: String?,
        val year: String?,
        val release_date: String?,
        val first_air_date: String?,
        val rating: Double?,
        var sa: Int?,
        var overview: String?,
        var overview_fr: String?,
        var trailer: String?,
        var media_type: String?,
    )

    data class FrembedListEpItem(
        val epi: Int,
        val id: Int,
        val title: String?
    )

    fun FrembedShortCutItem.toShow(movie: Boolean = false, tvshow: Boolean = false): Show =
        if ((sa != null && (media_type == null || media_type != "movie") && movie == false) || tvshow)
            TvShow(
                id = (tmdb?:id).toString(),
                title = buildString {
                    append(title_fr?:title?:name?:"TvShow")
                    if (sa != null) {
                        append(" - S${sa}")
                    } },
                poster = (poster?.w500)?:poster_path,
                banner = poster?.original,
                rating = rating
            )
        else {
            Movie(
                id = (tmdb?:id).toString(),
                title = title_fr?:title?:name?:"Movie",
                poster = (poster?.w500)?:poster_path,
                banner = poster?.original,
                rating = rating
            )
        }

    fun List<FrembedShortCutItem>.toCategorie(name: String): Category =
        Category(
            name = name,
            list = this.map { it.toShow() }
        )

    data class FrembedSeasonResponse(
        val episodes: List<FrembedListEpItem>,
        val sa: Int
    )

    data class FrembedMoviesResponse(
        val movies: List<FrembedShortCutItem>
    )

    data class FrembedTvShowsResponse(
        val series: List<FrembedShortCutItem>
    )

    data class FrembedSearchResponse(
        val movies: List<FrembedShortCutItem>,
        val tvShows: List<FrembedShortCutItem>
    )

    data class FrembedActorItem(
        val name: String,
        val birthday: String?,
        val deathday: String?,
        val profile_path: String?,
        val place_of_birth: String?,
        val biography: String?,
        val known_for_department: String?,
        val known_for: List<FrembedShortCutItem>?
    )

    data class FrembedSearchActorsResponse(
        val actor: FrembedActorItem,
        val movies: List<FrembedShortCutItem>
    )

    override suspend fun getHome(): List<Category> {
        initializeService()

        val categories = mutableListOf<Category>()

        try {
            val ranking = service.getApiPublic("ranking")
            categories.add(ranking.toCategorie(Category.FEATURED))
            // Séries récentes en premier
            val latestAdded = service.getApiView("latest-added-seasons")
            categories.add(latestAdded.toCategorie("Nouvelles séries"))
            val mostViewedSeasons = service.getApiView("most-viewed-seasons")
            categories.add(mostViewedSeasons.toCategorie("Meilleures séries"))
            // Puis les films
            val latest = service.getApiPublic("latest")
            categories.add(latest.toCategorie("Nouveaux films"))
            val updated = service.getApiPublic("updated")
            categories.add(updated.toCategorie("Films mis à jour"))
            val mostViewed = service.getApiView("most-viewed")
            categories.add(mostViewed.toCategorie("Meilleurs films"))

        } catch (e: Exception) { }

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
        if (page == 1) {
            if (query.isEmpty()) {
                return genres.map { (id, label) ->
                    Genre(
                        id = id,
                        name = label
                    )
                }
            }
            initializeService()
        }

        val result = service.getApiSearch(page, query)

        return result.movies.map { it.toShow(true) as Movie } + result.tvShows.map { it.toShow(tvshow=true) as TvShow }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        if (page == 1) initializeService()
        return service.getMovies(page).movies.map { it.toShow() as Movie }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page == 1) initializeService()
        return service.getTvShows(page).series.map { it.toShow() as TvShow }
    }

    override suspend fun getMovie(id: String): Movie {
        initializeService()

        var recommendations : List<Show> = try {
            service.getApiSimilar(id=id).toListShow(movie = true)
        } catch (e: Exception) {
            emptyList()
        }

        return service.getMovie(id).let { movie ->
            Movie(
                id = movie.tmdb.toString(),
                title = movie.title_fr?:movie.title?:"Movie",
                overview = movie.overview_fr?:movie.overview,
                released = movie.year,
                trailer = movie.trailer?.let { "https://www.youtube.com/watch?v=${movie.trailer}" },
                rating = movie.rating,
                poster = movie.poster?.w500,
                banner = movie.backdrops?.randomOrNull()?.original,
                imdbId = movie.imdb,
                genres = movie.genres?.split(", ")?.map { genre ->
                    Genre(
                        genre,
                        genre,
                    )
                } ?: emptyList(),
                directors = movie.director?.split(", ")?.map { director ->
                                People(
                                    id = director,
                                    name = director
                                )
                            } ?: listOf(),
                cast = movie.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profile_path?.original
                    ) } ?: listOf(),
                recommendations = recommendations
            )
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        initializeService()

        var recommendations : List<Show> = try {
            service.getApiSimilar(type="tv-show", id).toListShow(tvshow = true)
        } catch (e: Exception) {
            emptyList()
        }

        val tvshowp = service.getTvShow(id)

        var seasons : List<Season> = try {
            service.getApiListEp(id).toListSeason(id=id, posters = tvshowp.backdrops ?: emptyList())
        } catch (e: Exception) {
            emptyList()
        }

        return tvshowp.let { tvshow ->
                TvShow(
                    id = tvshow.tmdb.toString(),
                    title = tvshow.title_fr ?: tvshow.title ?: "TvShow",
                    overview = tvshow.overview_fr ?: tvshow.overview,
                    released = tvshow.year,
                    trailer = tvshow.trailer?.let { "https://www.youtube.com/watch?v=${tvshow.trailer}" },
                    rating = tvshow.rating,
                    poster = tvshow.poster?.w500,
                    banner = tvshow.backdrops?.randomOrNull()?.original,

                imdbId = tvshow.imdb,
                genres = tvshow.genres?.split(", ")?.map { genre ->
                    Genre(
                        genre,
                        genre,
                    )
                } ?: emptyList(),
                directors = tvshow.director?.split(", ")?.map { director ->
                    People(
                        id = director,
                        name = director
                    )
                } ?: listOf(),
                cast = tvshow.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profile_path?.original
                    ) } ?: listOf(),
                recommendations = recommendations,
                    seasons = seasons,

                    )
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (tvShowId, seasonNumber) = seasonId.split("/")
        val episodes : List<Episode> = try {
            service.getApiListEp(tvShowId).firstOrNull { it.sa.toString() == seasonNumber }.let { season ->
                season?.episodes?.map { ep ->
                    Episode(id = ep.id.toString(),
                        number = ep.epi,
                        title = ep.title ?: ("Episode "+ep.epi)
                    )
                } ?: listOf()
            }
        } catch (e: Exception) {
            listOf()
        }

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService()

        val result = service.getMovies(page, genre=id)

        val genre = Genre(id=id,
                          name=genres.getOrDefault(id, 0).toString(),
                          shows = result.movies.map { it.toShow(true) as Movie })
        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id=id,name=name)
        initializeService()

        val result = service.getApiSearchActor(id = id)
        val actor = result.actor
        return People(
                id = id, name = actor.name,
                birthday = actor.birthday,
                deathday = actor.deathday,
                image = actor.profile_path,
                biography = actor.biography,
                filmography = result.movies.map { item -> item.toShow() }
            )

    }

    override suspend fun getVideo(server: Video.Server): Video {
        // Délégation backups
        if (server.id.startsWith("movix_backup__")) {
            val original = server.copy(id = server.id.removePrefix("movix_backup__"))
            return try { MovixProvider.getVideo(original) }
            catch (e: Exception) { Log.w("Frembed", "Movix getVideo failed: ${e.message}"); Video(source = original.src) }
        }
        if (server.id.startsWith("cs_backup__")) {
            val original = server.copy(id = server.id.removePrefix("cs_backup__"))
            return try { CloudstreamProvider.getVideo(original) }
            catch (e: Exception) { Log.w("Frembed", "CS getVideo failed: ${e.message}"); Video(source = original.src) }
        }
        return when {
            server.video != null -> server.video!!
            else -> Extractor.extract(server.src)
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return FrembedExtractor(baseUrl).servers(videoType)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2026-05-27 : Backups Movix + Cloudstream (user "ajoute wiflix et
    //   frenchstream en backup pour tous les providers"). Frembed = API
    //   propre, pas de tmdbId natif → résolution TMDB par titre/année.
    // ─────────────────────────────────────────────────────────────────────

    /** Résout le tmdbId via TMDB par titre. */
    private suspend fun resolveFrembedTmdbId(videoType: Video.Type): Int? = runCatching {
        val title = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }
        val cleanTitle = TitleNormalizer.cleanForTmdbSearch(title).ifBlank { title }
        val year = when (videoType) {
            is Video.Type.Movie -> videoType.releaseDate.takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull()
            is Video.Type.Episode -> videoType.tvShow.releaseDate?.take(4)?.toIntOrNull()
        }
        val tmdbItem: Any? = when (videoType) {
            is Video.Type.Movie -> TmdbUtils.getMovie(cleanTitle, year, "fr-FR")
            is Video.Type.Episode -> TmdbUtils.getTvShow(cleanTitle, year, "fr-FR")
        }
        when (tmdbItem) {
            is Movie -> tmdbItem.id.toIntOrNull()
            is TvShow -> tmdbItem.id.toIntOrNull()
            else -> null
        }
    }.getOrNull()

    private suspend fun fetchFrembedMovixBackup(tmdbId: Int, videoType: Video.Type): List<Video.Server> = runCatching {
        val movixVideoType = if (videoType is Video.Type.Episode)
            videoType.copy(tvShow = videoType.tvShow.copy(id = "$tmdbId"))
        else videoType
        val movixId = when (videoType) {
            is Video.Type.Movie -> "$tmdbId"
            is Video.Type.Episode -> "$tmdbId-s${videoType.season.number}e${videoType.number}"
        }
        // 2026-06-13 (user "le patch FS Voe HD pollue tous les autres providers,
        //   je viens de voir sur Frembed exactement le meme probleme que sur
        //   Cloudstream") : meme filtre que CloudstreamProvider.fetchMovixBackupForCs.
        //   L'endpoint Movix /api/fstream/tv/<tmdbId>/season/<N> retourne des
        //   sources labellisees "FS · X (VF - HD)" qui jouent frequemment du
        //   mauvais contenu (= URLs d'autres shows). On les ecarte ici quand
        //   Movix est appele EN BACKUP par Frembed.
        MovixProvider.getServersAsBackup(movixId, movixVideoType)
            .filter { !it.id.startsWith("fstream-") }
            .map { srv -> srv.copy(id = "movix_backup__${srv.id}") }
    }.getOrNull().orEmpty()

    private suspend fun fetchFrembedCloudstreamBackup(tmdbId: Int, videoType: Video.Type): List<Video.Server> = runCatching {
        val csId = when (videoType) {
            is Video.Type.Movie -> "$tmdbId"
            is Video.Type.Episode -> "$tmdbId:${videoType.season.number}:${videoType.number}"
        }
        val csVideoType = if (videoType is Video.Type.Episode)
            videoType.copy(tvShow = videoType.tvShow.copy(id = "$tmdbId"))
        else videoType
        CloudstreamProvider.getServers(csId, csVideoType)
            .map { srv -> srv.copy(id = "cs_backup__${srv.id}", name = "Cloudstream — ${srv.name}") }
    }.getOrNull().orEmpty()

    /** 2026-06-13 (user "désactiver tous les backups Frembed pour test
     *  natif" → "réactive les backup") : backups Movix + Cloudstream
     *  ré-activés après validation que le natif marche. */
    private val ENABLE_BACKUPS: Boolean = true

    override fun getServersProgressive(
        id: String, videoType: Video.Type,
    ): Flow<List<Video.Server>> = channelFlow {
        // Natif : part tout de suite.
        launch {
            try {
                val native = FrembedExtractor(baseUrl).servers(videoType)
                if (native.isNotEmpty()) send(native)
            } catch (e: Exception) { Log.w("Frembed", "Progressive native failed: ${e.message}") }
        }
        // Backups : désactivés tant que ENABLE_BACKUPS=false (= test natif).
        if (ENABLE_BACKUPS) {
            launch {
                val tid = resolveFrembedTmdbId(videoType) ?: return@launch
                launch {
                    try { val mx = fetchFrembedMovixBackup(tid, videoType); if (mx.isNotEmpty()) send(mx) }
                    catch (e: Exception) { Log.w("Frembed", "Progressive Movix failed: ${e.message}") }
                }
                launch {
                    try { val cs = fetchFrembedCloudstreamBackup(tid, videoType); if (cs.isNotEmpty()) send(cs) }
                    catch (e: Exception) { Log.w("Frembed", "Progressive CS failed: ${e.message}") }
                }
            }
        }
    }

    /**
     * Initializes the service with the current domain URL.
     * This function is necessary because the provider's domain frequently changes.
     * We fetch the latest URL from a dedicated website that tracks these changes.
     */
    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this,UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                val addressService = Service.buildAddressFetcher()
                try {
                    val document = addressService.getPortalHome()

                    var newUrl = document.selectFirst("a")
                        ?.attr("href")
                        ?.trim()
                    if (!newUrl.isNullOrEmpty()) {
                        // Follow redirects to find the real domain
                        // (e.g. frembed.cyou → frembed.one)
                        try {
                            val resolvedUrl = resolveRedirects(newUrl)
                            if (resolvedUrl.isNotEmpty()) newUrl = resolvedUrl
                        } catch (_: Exception) {}

                        newUrl = if (newUrl!!.endsWith("/")) newUrl else "$newUrl/"
                        UserPreferences.setProviderCache(this,UserPreferences.PROVIDER_URL, newUrl)
                        UserPreferences.setProviderCache(
                            this,
                            UserPreferences.PROVIDER_LOGO,
                            newUrl + "favicon-32x32.png"
                        )
                    }
                } catch (e: Exception) {
                    // In case of failure, we'll use the default URL
                    // No need to throw as we already have a fallback URL
                }
            }
            service = Service.build(baseUrl)

            serviceInitialized = true
        }
        return baseUrl
    }

    /**
     * Follow HTTP redirects to find the final URL.
     * e.g. frembed.cyou → frembed.one
     */
    private suspend fun resolveRedirects(url: String): String {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .dns(DnsResolver.doh)
                .build()

            var currentUrl = if (url.endsWith("/")) url.dropLast(1) else url
            var maxRedirects = 5

            while (maxRedirects-- > 0) {
                val request = Request.Builder().url(currentUrl).head().build()
                val response = client.newCall(request).execute()
                val code = response.code
                response.close()

                if (code in 301..308) {
                    val location = response.header("Location") ?: break
                    currentUrl = if (location.startsWith("http")) location
                                 else "${currentUrl.substringBefore("://") }://${java.net.URL(currentUrl).host}$location"
                    Log.d("FrembedProvider", "Redirect: $code → $currentUrl")
                } else {
                    break
                }
            }

            // Return the scheme + host (base URL)
            val parsed = java.net.URL(currentUrl)
            "${parsed.protocol}://${parsed.host}"
        }
    }

    private suspend fun initializeService() {
        initializationMutex.withLock {
            if (serviceInitialized) return
            onChangeUrl()
        }
    }

    private interface Service {
        companion object {
            private val client = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .dns(DnsResolver.doh)
                .build()

            fun buildAddressFetcher(): Service {
                val addressRetrofit = Retrofit.Builder()
                    .baseUrl(portalUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)

                    .build()

                return addressRetrofit.create(Service::class.java)
            }

            fun build(baseUrl: String): Service {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET("{url}")
        suspend fun loadPage(
            @Path("url") url: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): Response<String>

        @GET(".")
        suspend fun getPortalHome(
            @Header("user-agent") user_agent: String = "Mozilla"
        ): Document

        @GET("api/public/movies/{section}")
        suspend fun getApiPublic(
            @Path("section") section: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): List<FrembedShortCutItem>

        @GET("api/views/{section}")
        suspend fun getApiView(
            @Path("section") section: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): List<FrembedShortCutItem>

        @GET("api/public/movies")
        suspend fun getMovies(
            @Query("page") page: Int = 1,
            @Query("pageSize") pageSize: Int = 16,
            @Query("orderBy") orderBy: String = "date_creation",
            @Query("orderDirection") orderDirection: String = "desc",
            @Query("searchQuery") searchQuery: String = "",
            @Query("searchBy") searchBy: String = "title",
            @Query("version") version: String = "",
            @Query("genre") genre: String = "",
            @Header("user-agent") user_agent: String = "Mozilla"
        ): FrembedMoviesResponse

        @GET("api/public/tv-show")
        suspend fun getTvShows(
            @Query("page") page: Int = 1,
            @Query("pageSize") pageSize: Int = 16,
            @Query("orderBy") orderBy: String = "date_creation",
            @Query("orderDirection") orderDirection: String = "desc",
            @Query("searchQuery") searchQuery: String = "",
            @Query("searchBy") searchBy: String = "title",
            @Query("version") version: String = "",
            @Query("genre") genre: String = "",
            @Header("user-agent") user_agent: String = "Mozilla"
        ): FrembedTvShowsResponse

        @GET("api/public/search")
        suspend fun getApiSearch(
            @Query("page") page: Int = 1,
            @Query("query") query: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): FrembedSearchResponse

        @GET("api/public/actor/{id}")
        suspend fun getApiSearchActor(
            @Path("id") id: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): FrembedSearchActorsResponse

        @GET("api/public/movies/{id}")
        suspend fun getMovie(
            @Path("id") id: String,
        ): FrembedShowItem

        @GET("api/public/tv-show/{id}")
        suspend fun getTvShow(
            @Path("id") id: String,
        ): FrembedShowItem

        @GET("api/public/{type}/similar/{id}")
        suspend fun getApiSimilar(
            @Path("type") type: String = "movies",
            @Path("id") id: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): List<FrembedSimilarItem>

        @GET("api/public/tv-show/{id}/listep")
        suspend fun getApiListEp(
            @Path("id") id: String,
            @Header("user-agent") user_agent: String = "Mozilla"
        ): List<FrembedSeasonResponse>
    }
}