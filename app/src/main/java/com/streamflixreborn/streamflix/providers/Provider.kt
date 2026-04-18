package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.sync.Mutex

interface ProviderPortalUrl {
    val portalUrl: String
    val defaultPortalUrl: String
}

interface ProviderConfigUrl {
    val defaultBaseUrl: String

    suspend fun onChangeUrl(forceRefresh: Boolean = false): String
    val changeUrlMutex: Mutex
}

interface Provider {

    val baseUrl: String
    val name: String
    val logo: String
    val language: String

    suspend fun getHome(): List<Category>

    suspend fun search(query: String, page: Int = 1): List<AppAdapter.Item>

    suspend fun getMovies(page: Int = 1): List<Movie>

    suspend fun getTvShows(page: Int = 1): List<TvShow>

    suspend fun getMovie(id: String): Movie

    suspend fun getTvShow(id: String): TvShow

    suspend fun getEpisodesBySeason(seasonId: String): List<Episode>

    suspend fun getGenre(id: String, page: Int = 1): Genre

    suspend fun getPeople(id: String, page: Int = 1): People

    suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server>

    suspend fun getVideo(server: Video.Server): Video

    companion object {
        data class ProviderSupport(
            val movies: Boolean,
            val tvShows: Boolean
        )

        val providers: Map<Provider, ProviderSupport> = mapOf(
            // French providers only
            WiflixProvider to ProviderSupport(movies = true, tvShows = true),
            FrenchStreamProvider to ProviderSupport(movies = true, tvShows = true),
            FrenchAnimeProvider to ProviderSupport(movies = true, tvShows = true),
            FrenchMangaProvider to ProviderSupport(movies = false, tvShows = true),
            FrembedProvider to ProviderSupport(movies = true, tvShows = true),

            KidrazProvider to ProviderSupport(movies = true, tvShows = false),
            UnJourUnFilmProvider to ProviderSupport(movies = true, tvShows = true),
            // UnJourUnFilm2Provider désactivé — miroir de UnJourUnFilm, tombe en panne en même temps
            // UnJourUnFilm2Provider to ProviderSupport(movies = true, tvShows = true),
            aploufProvider to ProviderSupport(movies = true, tvShows = false),
            AnimeSamaProvider to ProviderSupport(movies = true, tvShows = true),
        )

        // Helper functions to check support
        fun supportsMovies(provider: Provider): Boolean {
            val support = providers[provider] ?: ProviderSupport(movies = true, tvShows = true)
            return support.movies
        }

        fun supportsTvShows(provider: Provider): Boolean {
            val support = providers[provider] ?: ProviderSupport(movies = true, tvShows = true)
            return support.tvShows
        }

        fun findByName(name: String): Provider? {
            return providers.keys.find { it.name == name }
        }
    }
}
