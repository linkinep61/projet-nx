package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.extractors.MoflixExtractor
import com.streamflixreborn.streamflix.extractors.MoviesapiExtractor
import com.streamflixreborn.streamflix.extractors.TwoEmbedExtractor
import com.streamflixreborn.streamflix.extractors.VidsrcNetExtractor
import com.streamflixreborn.streamflix.extractors.VidsrcToExtractor
import com.streamflixreborn.streamflix.extractors.VidzeeExtractor
import com.streamflixreborn.streamflix.extractors.VixSrcExtractor
import com.streamflixreborn.streamflix.extractors.VidLinkExtractor
import com.streamflixreborn.streamflix.extractors.VidsrcRuExtractor
import com.streamflixreborn.streamflix.extractors.EinschaltenExtractor
import com.streamflixreborn.streamflix.extractors.FrembedExtractor
import com.streamflixreborn.streamflix.extractors.VidflixExtractor
import com.streamflixreborn.streamflix.extractors.VidrockExtractor
import com.streamflixreborn.streamflix.extractors.VideasyExtractor
import com.streamflixreborn.streamflix.extractors.PrimeSrcExtractor
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
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TMDb3.w780
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.safeSubList
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class TmdbProvider(override val language: String) : Provider {
    override val baseUrl: String
        get() = ""

    override val name = "TMDb ($language)"
    override val logo =
        "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_tmdb"

    companion object {
        // 2026-05-04 : cache mémoire de getHome() — partagé par langue (la
        // classe est instanciée plusieurs fois). Le home fait ~22 calls
        // réseau (3 pages × 7 sections), recharger à chaque pull-to-refresh
        // ou retour Home/Search est wasteful. TTL 30 min : assez court pour
        // que les sections "Trending" reflètent le quotidien, assez long
        // pour rendre l'UX instantanée. L'utilisateur peut forcer en faisant
        // pull-to-refresh manuel après expiration.
        private const val HOME_CACHE_TTL_MS = 30L * 60L * 1000L
        private data class CachedHome(val categories: List<com.streamflixreborn.streamflix.models.Category>, val expiresAtMs: Long)
        private val homeCache = java.util.concurrent.ConcurrentHashMap<String, CachedHome>()

        // Cache search() — TTL court (5 min). Les utilisateurs retapent
        // souvent la même requête à la suite (typo, retour arrière).
        private const val SEARCH_CACHE_TTL_MS = 5L * 60L * 1000L
        private data class CachedSearch(val results: List<AppAdapter.Item>, val expiresAtMs: Long)
        private val searchCache = java.util.concurrent.ConcurrentHashMap<String, CachedSearch>()
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        // 2026-05-04 : cache hit ?
        val cacheKey = "home:$language"
        val now = System.currentTimeMillis()
        homeCache[cacheKey]?.let { cached ->
            if (now < cached.expiresAtMs) {
                Log.d("TmdbProvider", "getHome cache HIT for $cacheKey (${cached.categories.size} cats, age=${(now - (cached.expiresAtMs - HOME_CACHE_TTL_MS)) / 1000}s)")
                return@coroutineScope cached.categories
            }
            homeCache.remove(cacheKey)
        }

        val categories = mutableListOf<Category>()
        val watchRegion = if (language == "en") "US" else language.uppercase()

        val mapMulti: (TMDb3.MultiItem) -> AppAdapter.Item? = { multi ->
            when (multi) {
                is TMDb3.Movie -> Movie(
                    id = multi.id.toString(),
                    title = multi.title,
                    overview = multi.overview,
                    released = multi.releaseDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.w1280,
                )

                is TMDb3.Tv -> TvShow(
                    id = multi.id.toString(),
                    title = multi.name,
                    overview = multi.overview,
                    released = multi.firstAirDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.w1280,
                )

                else -> null
            }
        }

        val trendingDeferred = async {
            awaitAll(
                async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 1, language = language) },
                async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 2, language = language) },
                async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 3, language = language) },
            ).flatMap { it.results }
        }

        val popularMoviesDeferred = async {
            awaitAll(
                async { TMDb3.MovieLists.popular(page = 1, language = language) },
                async { TMDb3.MovieLists.popular(page = 2, language = language) },
                async { TMDb3.MovieLists.popular(page = 3, language = language) },
            ).flatMap { it.results }
        }

        val popularTvShowsDeferred = async {
            awaitAll(
                async { TMDb3.TvSeriesLists.popular(page = 1, language = language) },
                async { TMDb3.TvSeriesLists.popular(page = 2, language = language) },
                async { TMDb3.TvSeriesLists.popular(page = 3, language = language) },
            ).flatMap { it.results }
        }

        val popularAnimeDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        withKeywords = TMDb3.Params.WithBuilder(TMDb3.Keyword.KeywordId.ANIME)
                            .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withKeywords = TMDb3.Params.WithBuilder(TMDb3.Keyword.KeywordId.ANIME)
                            .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                    )
                },
            ).flatMap { it.results }
        }

        val netflixDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.NETFLIX),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.NETFLIX),
                    )
                },
            ).flatMap { it.results }
        }

        val amazonDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.AMAZON_VIDEO),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.AMAZON),
                    )
                },
            ).flatMap { it.results }
        }

        val disneyDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.DISNEY_PLUS),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.DISNEY_PLUS),
                    )
                },
            ).flatMap { it.results }
        }

        val huluDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.HULU),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.HULU),
                    )
                },
            ).flatMap { it.results }
        }

        val appleDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.APPLE_TV_PLUS),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.APPLE_TV),
                    )
                },
            ).flatMap { it.results }
        }

        val hboDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.HBO),
                        page = 1,
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.HBO),
                        page = 2,
                    )
                },
            ).flatMap { it.results }
        }

        val trending = trendingDeferred.await()
        categories.add(
            Category(
                name = Category.FEATURED,
                list = trending.safeSubList(0, 5).mapNotNull(mapMulti)
            )
        )

        categories.add(
            Category(
                name = getTranslation("Trending"),
                list = trending.safeSubList(5, trending.size).mapNotNull(mapMulti)
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular Movies"),
                list = popularMoviesDeferred.await().mapNotNull(mapMulti)
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular TV Shows"),
                list = popularTvShowsDeferred.await().mapNotNull(mapMulti)
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular Anime"),
                list = popularAnimeDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Netflix"),
                list = netflixDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Amazon"),
                list = amazonDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Disney+"),
                list = disneyDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Hulu"),
                list = huluDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Apple TV+"),
                list = appleDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on HBO"),
                list = hboDeferred.await().mapNotNull(mapMulti),
            )
        )

        // 2026-05-04 : cache write (TTL 30 min). On ne cache que les "vrais"
        // home (>3 catégories) pour éviter de mémoriser un état d'erreur partiel
        // si plusieurs sections ont timeout.
        if (categories.size >= 3) {
            homeCache[cacheKey] = CachedHome(
                categories = categories.toList(),
                expiresAtMs = System.currentTimeMillis() + HOME_CACHE_TTL_MS,
            )
        }

        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            val genres = listOf(
                TMDb3.Genres.movieList(language = language),
                TMDb3.Genres.tvList(language = language),
            ).flatMap { it.genres }
                .distinctBy { it.id }
                .sortedBy { it.name }
                .map {
                    Genre(
                        id = it.id.toString(),
                        name = it.name,
                    )
                }

            return genres
        }

        // 2026-05-04 : cache search (TTL 5 min). User retape vite la même
        // recherche après un retour arrière, ou cycle entre 2-3 termes.
        val searchKey = "search:$language:${query.trim().lowercase()}:p$page"
        val now = System.currentTimeMillis()
        searchCache[searchKey]?.let { cached ->
            if (now < cached.expiresAtMs) {
                Log.d("TmdbProvider", "search cache HIT for '$query' p$page")
                return cached.results
            }
            searchCache.remove(searchKey)
        }

        val results = TMDb3.Search.multi(query, page = page, language = language).results.mapNotNull { multi ->
            when (multi) {
                is TMDb3.Movie -> Movie(
                    id = multi.id.toString(),
                    title = multi.title,
                    overview = multi.overview,
                    released = multi.releaseDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.w1280,
                )

                is TMDb3.Tv -> TvShow(
                    id = multi.id.toString(),
                    title = multi.name,
                    overview = multi.overview,
                    released = multi.firstAirDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.w1280,
                )

                else -> null
            }
        }

        // Cache write (sauf si vide → laisser le user retry rapidement)
        if (results.isNotEmpty()) {
            searchCache[searchKey] = CachedSearch(
                results = results,
                expiresAtMs = System.currentTimeMillis() + SEARCH_CACHE_TTL_MS,
            )
        }

        return results
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val movies = TMDb3.MovieLists.popular(page = page, language = language).results.map { movie ->
            Movie(
                id = movie.id.toString(),
                title = movie.title,
                overview = movie.overview,
                released = movie.releaseDate,
                rating = movie.voteAverage.toDouble(),
                poster = movie.posterPath?.w500,
                banner = movie.backdropPath?.w1280,
            )
        }

        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val tvShows = TMDb3.TvSeriesLists.popular(page = page, language = language).results.map { tv ->
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.w500,
                banner = tv.backdropPath?.w1280,
            )
        }

        return tvShows
    }

    override suspend fun getMovie(id: String): Movie {
        val movie = TMDb3.Movies.details(
            movieId = id.toInt(),
            appendToResponse = listOf(
                TMDb3.Params.AppendToResponse.Movie.CREDITS,
                TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
            ),
            language = language
        ).let { movie ->
            Movie(
                id = movie.id.toString(),
                title = movie.title,
                overview = movie.overview,
                released = movie.releaseDate,
                runtime = movie.runtime,
                trailer = movie.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = movie.voteAverage.toDouble(),
                poster = movie.posterPath?.w780,
                banner = movie.backdropPath?.w1280,
                imdbId = movie.externalIds?.imdbId,

                genres = movie.genres.map { genre ->
                    Genre(
                        genre.id.toString(),
                        genre.name,
                    )
                },
                cast = movie.credits?.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    )
                } ?: listOf(),
                recommendations = movie.recommendations?.results?.mapNotNull { multi ->
                    when (multi) {
                        is TMDb3.Movie -> Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.w1280,
                        )

                        is TMDb3.Tv -> TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.w1280,
                        )

                        else -> null
                    }
                } ?: listOf(),
            )
        }

        return movie
    }

    override suspend fun getTvShow(id: String): TvShow {
        val tvShow = TMDb3.TvSeries.details(
            seriesId = id.toInt(),
            appendToResponse = listOf(
                TMDb3.Params.AppendToResponse.Tv.CREDITS,
                TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
            ),
            language = language
        ).let { tv ->
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                trailer = tv.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.w780,
                banner = tv.backdropPath?.w1280,
                imdbId = tv.externalIds?.imdbId,

                seasons = tv.seasons.map { season ->
                    Season(
                        id = "${tv.id}-${season.seasonNumber}",
                        number = season.seasonNumber,
                        title = season.name,
                        poster = season.posterPath?.w500,
                    )
                },
                genres = tv.genres.map { genre ->
                    Genre(
                        genre.id.toString(),
                        genre.name,
                    )
                },
                cast = tv.credits?.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    )
                } ?: listOf(),
                recommendations = tv.recommendations?.results?.mapNotNull { multi ->
                    when (multi) {
                        is TMDb3.Movie -> Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.w1280,
                        )

                        is TMDb3.Tv -> TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.w1280,
                        )

                        else -> null
                    }
                } ?: listOf(),
            )
        }

        return tvShow
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (tvShowId, seasonNumber) = seasonId.split("-")

        val episodes = TMDb3.TvSeasons.details(
            seriesId = tvShowId.toInt(),
            seasonNumber = seasonNumber.toInt(),
            language = language
        ).episodes?.map {
            Episode(
                id = it.id.toString(),
                number = it.episodeNumber,
                title = it.name ?: "",
                released = it.airDate,
                poster = it.stillPath?.w500,
            )
        } ?: listOf()

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        fun <T> List<T>.mix(other: List<T>): List<T> {
            return sequence {
                val first = iterator()
                val second = other.iterator()
                while (first.hasNext() && second.hasNext()) {
                    yield(first.next())
                    yield(second.next())
                }

                yieldAll(first)
                yieldAll(second)
            }.toList()
        }

        val genre = Genre(
            id = id,
            name = "",

            shows = TMDb3.Discover.movie(
                page = page,
                withGenres = TMDb3.Params.WithBuilder(id),
                language = language
            ).results.map { movie ->
                Movie(
                    id = movie.id.toString(),
                    title = movie.title,
                    overview = movie.overview,
                    released = movie.releaseDate,
                    rating = movie.voteAverage.toDouble(),
                    poster = movie.posterPath?.w500,
                    banner = movie.backdropPath?.w1280,
                )
            }.mix(TMDb3.Discover.tv(
                page = page,
                withGenres = TMDb3.Params.WithBuilder(id),
                language = language
            ).results.map { tv ->
                TvShow(
                    id = tv.id.toString(),
                    title = tv.name,
                    overview = tv.overview,
                    released = tv.firstAirDate,
                    rating = tv.voteAverage.toDouble(),
                    poster = tv.posterPath?.w500,
                    banner = tv.backdropPath?.w1280,
                )
            })
        )

        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val people = TMDb3.People.details(
            personId = id.toInt(),
            appendToResponse = listOfNotNull(
                if (page > 1) null else TMDb3.Params.AppendToResponse.Person.COMBINED_CREDITS,
            ),
            language = language
        ).let { person ->
            People(
                id = person.id.toString(),
                name = person.name,
                image = person.profilePath?.w500,
                biography = person.biography,
                placeOfBirth = person.placeOfBirth,
                birthday = person.birthday,
                deathday = person.deathday,

                filmography = person.combinedCredits?.cast
                    ?.mapNotNull { multi ->
                        when (multi) {
                            is TMDb3.Movie -> Movie(
                                id = multi.id.toString(),
                                title = multi.title,
                                overview = multi.overview,
                                released = multi.releaseDate,
                                rating = multi.voteAverage.toDouble(),
                                poster = multi.posterPath?.w500,
                                banner = multi.backdropPath?.w1280,
                            )

                            is TMDb3.Tv -> TvShow(
                                id = multi.id.toString(),
                                title = multi.name,
                                overview = multi.overview,
                                released = multi.firstAirDate,
                                rating = multi.voteAverage.toDouble(),
                                poster = multi.posterPath?.w500,
                                banner = multi.backdropPath?.w1280,
                            )

                        else -> null
                    }
                }
                    ?.sortedBy {
                        when (it) {
                            is Movie -> it.released
                            is TvShow -> it.released
                        }
                    }
                    ?.reversed()
                    ?: listOf()
            )
        }

        return people
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        val lang = language.lowercase().substringBefore("-")

        Log.d("TmdbProvider", "getServers: lang=$language, simplifiedLang=$lang")

        when (lang) {
            "it" -> {
                // Se la lingua è italiano, includiamo solo i server noti per l'italiano.
                servers.add(VixSrcExtractor().server(videoType))
                VideasyExtractor().server(videoType, language)?.let { servers.add(it) }
            }
            "de" -> {
                // Solo server tedeschi
                servers.addAll(0, MoflixExtractor().servers(videoType))
                if (videoType is Video.Type.Movie) {
                    EinschaltenExtractor().server(videoType)?.let { servers.add(it) }
                }
                VideasyExtractor().server(videoType, language)?.let { servers.add(it) }
            }
            "fr" -> {
                // Sources FR : priorité 1jour1film et FrenchStream, puis extracteurs directs
                val targetTitle = when (videoType) {
                    is Video.Type.Movie -> videoType.title
                    is Video.Type.Episode -> videoType.tvShow.title
                }

                fun isMatchFR(item: AppAdapter.Item, target: String): Boolean {
                    val isCorrectType = if (videoType is Video.Type.Movie) item is Movie else item is TvShow
                    if (!isCorrectType) return false
                    val itemTitle = if (item is Movie) item.title else (item as TvShow).title
                    val nItem = itemTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
                    val nTarget = target.lowercase().replace(Regex("[^a-z0-9]"), "")
                    if (nItem == nTarget) return true
                    if (nItem.contains(nTarget) || nTarget.contains(nItem)) {
                        if (Math.abs(nItem.length - nTarget.length) <= 5) return true
                    }
                    val cleanWords: (String) -> Set<String> = { s ->
                        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.length > 2 }.toSet()
                    }
                    val nItemWords = cleanWords(itemTitle)
                    val nTargetWords = cleanWords(target)
                    if (nItemWords.isEmpty() || nTargetWords.isEmpty()) return false
                    if (nTargetWords.size == 1) return nItemWords.contains(nTargetWords.first())
                    return nItemWords.containsAll(nTargetWords) || nTargetWords.containsAll(nItemWords)
                }

                // Providers FR + Extracteurs directs - tout en parallèle
                val frembedUrl = UserPreferences.getProviderCache(FrembedProvider, UserPreferences.PROVIDER_URL).ifEmpty { FrembedProvider.defaultBaseUrl }

                coroutineScope {
                    // 2026-05-04 : FrenchStreamProvider retiré (login wall + listing HTML cassé).
                    // Voir Provider.kt pour le détail. Quand FS aura une solution, le remettre.
                    val frProviders = listOf(UnJourUnFilmProvider, KidrazProvider)
                    val perProviderTimeout = 12_000L // 12s max par provider

                    // Providers de recherche (priorité par ordre) — timeout individuel
                    val providerDeferred = frProviders.mapIndexed { idx, provider ->
                        async {
                            try {
                                withTimeoutOrNull(perProviderTimeout) {
                                    val searchResults = provider.search(targetTitle, 1)
                                    val bestMatch = searchResults.firstOrNull { isMatchFR(it, targetTitle) }
                                    val id = if (bestMatch is Movie) bestMatch.id else (bestMatch as? TvShow)?.id
                                    if (id != null) {
                                        Log.i("StreamFlixFR", "[MATCH] ${provider.name} -> '$targetTitle' id=$id")
                                        val srvs = provider.getServers(id, videoType)
                                        Log.i("StreamFlixFR", "[SERVERS] ${provider.name} -> ${srvs.size} serveurs")
                                        idx to srvs
                                    } else {
                                        Log.d("StreamFlixFR", "[NO MATCH] ${provider.name} pour '$targetTitle'")
                                        idx to emptyList()
                                    }
                                } ?: run {
                                    Log.w("StreamFlixFR", "[TIMEOUT] ${provider.name} après ${perProviderTimeout}ms")
                                    idx to emptyList()
                                }
                            } catch (e: Exception) {
                                Log.e("StreamFlixFR", "[ERROR] ${provider.name}: ${e.message}")
                                idx to emptyList<Video.Server>()
                            }
                        }
                    }

                    // Extracteurs directs en parallèle — avec timeout aussi
                    val frembedDeferred = async {
                        try {
                            withTimeoutOrNull(perProviderTimeout) {
                                val srvs = FrembedExtractor(frembedUrl).servers(videoType)
                                Log.i("StreamFlixFR", "[SERVERS] Frembed -> ${srvs.size} serveurs")
                                srvs
                            } ?: run {
                                Log.w("StreamFlixFR", "[TIMEOUT] Frembed")
                                emptyList()
                            }
                        } catch (e: Exception) {
                            Log.e("StreamFlixFR", "[ERROR] Frembed: ${e.message}")
                            emptyList()
                        }
                    }
                    // AfterDark retiré

                    // 2026-05-04 : MazQuest (allostreaming.one / waaatch.art via
                    // embed.maz.quest -> Yandex Disk MP4 direct). Gros catalogue
                    // VF de vieilles séries (NY911, Friends, etc.) que les autres
                    // providers FR ne couvrent plus. TV uniquement (l'endpoint
                    // film côté maz.quest passe par un pipeline avec login wall).
                    val mazQuestDeferred = async {
                        try {
                            withTimeoutOrNull(perProviderTimeout) {
                                val ep = videoType as? Video.Type.Episode
                                    ?: return@withTimeoutOrNull emptyList()
                                val tmdbId = ep.tvShow.id
                                val ssPad = "%02d".format(ep.season.number)
                                val epPad = "%02d".format(ep.number)
                                val url = "https://embed.maz.quest/tv/api/$tmdbId/$ssPad/$epPad"
                                val req = okhttp3.Request.Builder()
                                    .url(url)
                                    .header("Accept", "application/json")
                                    .build()
                                val resp = com.streamflixreborn.streamflix.utils.NetworkClient.default
                                    .newCall(req).execute()
                                val body = resp.body?.string()
                                if (body.isNullOrBlank()) return@withTimeoutOrNull emptyList()
                                val json = org.json.JSONObject(body)
                                val hasSource = !json.optBoolean("error", true)
                                    && (json.optJSONArray("links")?.length() ?: 0) > 0
                                if (!hasSource) {
                                    Log.d("StreamFlixFR", "[MAZQUEST] no source for $tmdbId S${ssPad}E$epPad")
                                    return@withTimeoutOrNull emptyList()
                                }
                                Log.i("StreamFlixFR", "[MAZQUEST] +1 server (Yandex VF) for $tmdbId S${ssPad}E$epPad")
                                listOf(
                                    Video.Server(
                                        id = "mazquest-tv-$tmdbId-$ssPad-$epPad",
                                        name = "Yandex VF (Maz)",
                                        src = url,
                                    )
                                )
                            } ?: emptyList()
                        } catch (e: Exception) {
                            Log.e("StreamFlixFR", "[MAZQUEST] error: ${e.message}")
                            emptyList()
                        }
                    }

                    // Collecter tous les résultats
                    val providerResults = providerDeferred.awaitAll()
                    val frembedServers = frembedDeferred.await()
                    val mazQuestServers = mazQuestDeferred.await()

                    // Assembler : providers par priorité, puis extracteurs
                    val allFrServers = mutableListOf<Video.Server>()
                    providerResults.sortedBy { it.first }.forEach { allFrServers.addAll(it.second) }
                    allFrServers.addAll(frembedServers)
                    allFrServers.addAll(mazQuestServers)

                    // Trier : VF/French/TrueFrench en premier, VOSTFR à la fin
                    // + priorité aux serveurs fiables (Vidzy, Uqload, Filemoon, Voe, Netu...)
                    val isVostfr: (Video.Server) -> Boolean = { srv ->
                        val n = srv.name.lowercase()
                        n.contains("vostfr") || n.contains("vost") || (n.contains("vo") && !n.contains("vof"))
                    }
                    val reliableServers = listOf("vidzy", "dood", "voe", "filemoon", "filmoon", "netu", "sharecloudy", "playmogo")
                    val unreliableServers = listOf("premium", "fsvid", "uqload")
                    val serverPriority: (Video.Server) -> Int = { srv ->
                        val n = srv.name.lowercase()
                        when {
                            reliableServers.any { n.contains(it) } -> 0
                            unreliableServers.any { n.contains(it) } -> 2
                            else -> 1
                        }
                    }
                    val vfServers = allFrServers.filter { !isVostfr(it) }.sortedBy { serverPriority(it) }
                    val vostfrServers = allFrServers.filter { isVostfr(it) }.sortedBy { serverPriority(it) }
                    servers.addAll(vfServers)
                    servers.addAll(vostfrServers)

                    Log.i("StreamFlixFR", "[TOTAL] ${servers.size} serveurs (${vfServers.size} VF + ${vostfrServers.size} VOSTFR)")
                }

                // Fallback : si aucun serveur FR trouvé, ajouter les extracteurs globaux (VOSTFR)
                if (servers.isEmpty()) {
                    Log.i("StreamFlixFR", "[FALLBACK] Aucun serveur FR, ajout des extracteurs globaux")
                    servers.addAll(listOf(
                        VixSrcExtractor().server(videoType),
                        VidsrcNetExtractor().server(videoType),
                        VidLinkExtractor().server(videoType),
                        VidsrcRuExtractor().server(videoType),
                        TwoEmbedExtractor().server(videoType),
                    ))
                    VideasyExtractor().server(videoType, language)?.let { servers.add(it) }
                }
            }
            "es" -> {
                // Providers espagnols archivés — fallback sur extracteurs globaux
                servers.addAll(listOf(
                    VixSrcExtractor().server(videoType),
                    VidsrcNetExtractor().server(videoType),
                    VidLinkExtractor().server(videoType),
                    VidsrcRuExtractor().server(videoType),
                    TwoEmbedExtractor().server(videoType),
                ))
                VideasyExtractor().server(videoType, language)?.let { servers.add(it) }
            }
            else -> {
                // Per inglese (en) o altre lingue non specifiche, usiamo i server globali
                servers.addAll(listOf(
                    VixSrcExtractor().server(videoType),
                    TwoEmbedExtractor().server(videoType),
                    VidsrcNetExtractor().server(videoType),
                    VidLinkExtractor().server(videoType),
                    VidsrcRuExtractor().server(videoType),
                    VidflixExtractor().server(videoType),
                ))

                if (videoType is Video.Type.Movie) {
                    servers.add(2, MoviesapiExtractor().server(videoType))
                }

                servers.addAll(VidrockExtractor().servers(videoType))
                servers.addAll(VidzeeExtractor().servers(videoType))
                servers.addAll(PrimeSrcExtractor().servers(videoType))

                if (language == "en") {
                    servers.addAll(1, VideasyExtractor().servers(videoType, language))
                }
            }
        }

        // ORDINE PRIORITÀ FINALE: Portiamo i server con audio Spagnolo e Filemoon in cima
        val finalServers = if (language.startsWith("es")) {
            servers.sortedByDescending { server ->
                val n = server.name.uppercase()
                when {
                    // Filemoon e tag audio spagnoli hanno la massima priorità
                    n.contains("FILEMOON") -> 110
                    n.contains("[CAS]") || n.contains("[LAT]") || n.contains("[ES]") || n.contains("SPAIN") || n.contains("[CAST]") ||
                    n.contains("LATINO") || n.contains("SPANISH") || n.contains("CASTELLANO") || n.contains("(LAT)") || n.contains("(ESP)") -> 100
                    
                    // Altri aggregatori multi-lingua
                    n.contains("VIDSRC") || n.contains("VIDLINK") -> 80
                    
                    // Sottotitoli o inglese
                    n.contains("[EN]") || n.contains("[SUB]") || n.contains("(EN)") || n.contains("(SUB)") -> 50
                    
                    else -> 0
                }
            }
        } else {
            servers
        }

        Log.i("StreamFlixES", "[SERVERS LIST] -> Found ${finalServers.size} servers: ${finalServers.joinToString { it.name }}")
        return finalServers.distinctBy { it.id }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val url = server.src.ifEmpty { server.id }
        Log.i("StreamFlixES", "[SERVER] -> Using: ${server.name} (URL: $url)")
        
        val video = when {
            server.video != null -> server.video!!
            else -> Extractor.extract(url, server)
        }

        // LOGICA SOTTOTITOLI FORZATI: Se siamo in spagnolo, attiviamo solo i forced di default
        if (language.startsWith("es")) {
            var forcedFound = false
            video.subtitles.forEach { sub ->
                val label = sub.label.lowercase()
                val isSpanish = label.contains("spanish") || label.contains("español") || 
                                label.contains("espanol") || label.contains("castellano") || 
                                label.contains(" lat ")
                val isForced = label.contains("forced") || label.contains("forzati") || label.contains("forzato")

                if (isSpanish && isForced) {
                    sub.default = true
                    forcedFound = true
                    Log.i("StreamFlixES", "[SUBTITLE] -> TMDb (es): Selected FORCED subtitle: ${sub.label}")
                } else {
                    sub.default = false
                }
            }
            
            if (!forcedFound) {
                video.subtitles.forEach { it.default = false }
                Log.i("StreamFlixES", "[SUBTITLE] -> TMDb (es): No forced subs found, keeping them OFF")
            }
        }
        
        Log.i("StreamFlixES", "[VIDEO] -> Final source: ${video.source}")
        return video
    }

    private fun getTranslation(key: String): String {
        return when (language) {
            "it" -> when (key) {
                "Trending" -> "Di tendenza"
                "Popular Movies" -> "Film popolari"
                "Popular TV Shows" -> "Serie TV popolari"
                "Popular Anime" -> "Anime popolari"
                "Popular on Netflix" -> "Popolari su Netflix"
                "Popular on Amazon" -> "Popolari su Amazon"
                "Popular on Disney+" -> "Popolari su Disney+"
                "Popular on Hulu" -> "Popolari su Hulu"
                "Popular on Apple TV+" -> "Popolari su Apple TV+"
                "Popular on HBO" -> "Popolari su HBO"
                else -> key
            }
            "es" -> when (key) {
                "Trending" -> "Tendencias"
                "Popular Movies" -> "Películas populares"
                "Popular TV Shows" -> "Series de TV populares"
                "Popular Anime" -> "Anime populares"
                "Popular on Netflix" -> "Popular en Netflix"
                "Popular on Amazon" -> "Popular en Amazon"
                "Popular on Disney+" -> "Popular en Disney+"
                "Popular on Hulu" -> "Popular en Hulu"
                "Popular on Apple TV+" -> "Popular en Apple TV+"
                "Popular on HBO" -> "Popular en HBO"
                else -> key
            }
            "de" -> when (key) {
                "Trending" -> "Trends"
                "Popular Movies" -> "Beliebte Filme"
                "Popular TV Shows" -> "Beliebte Serien"
                "Popular Anime" -> "Beliebte Anime"
                "Popular on Netflix" -> "Beliebt bei Netflix"
                "Popular on Amazon" -> "Beliebt bei Amazon"
                "Popular on Disney+" -> "Beliebt bei Disney+"
                "Popular on Hulu" -> "Beliebt bei Hulu"
                "Popular on Apple TV+" -> "Beliebt bei Apple TV+"
                "Popular on HBO" -> "Beliebt bei HBO"
                else -> key
            }
            "fr" -> when (key) {
                "Trending" -> "Tendances"
                "Popular Movies" -> "Films populaires"
                "Popular TV Shows" -> "Séries populaires"
                "Popular Anime" -> "Animes populaires"
                "Popular on Netflix" -> "Populaire sur Netflix"
                "Popular on Amazon" -> "Populaire sur Amazon"
                "Popular on Disney+" -> "Populaire sur Disney+"
                "Popular on Hulu" -> "Populaire sur Hulu"
                "Popular on Apple TV+" -> "Populaire sur Apple TV+"
                "Popular on HBO" -> "Populaire sur HBO"
                else -> key
            }
            else -> key
        }
    }
}
