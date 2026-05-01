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

interface FilterableProvider {
    suspend fun getFilteredMovies(language: String, page: Int = 1): List<Movie>
    suspend fun getFilteredTvShows(language: String, page: Int = 1): List<TvShow>
}

/** Marker interface for providers that expose live TV channels (IPTV).
 *  Used to gate features like the mini-player and channel-specific player UI
 *  that should apply to ALL IPTV providers, not just WiTV. */
interface IptvProvider

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
        enum class ProviderGroup {
            ANIME,
            FILMS_SERIES
        }

        data class ProviderSupport(
            val movies: Boolean,
            val tvShows: Boolean,
            val group: ProviderGroup = ProviderGroup.FILMS_SERIES,
            val enrichHome: Boolean = true
        )

        val providers: Map<Provider, ProviderSupport> = linkedMapOf(
            // French providers — custom display order
            FrenchStreamProvider to ProviderSupport(movies = true, tvShows = true),
            MovixProvider to ProviderSupport(movies = true, tvShows = true),
            UnJourUnFilmProvider to ProviderSupport(movies = true, tvShows = true),
            AnimeSamaProvider to ProviderSupport(movies = true, tvShows = true, group = ProviderGroup.ANIME),
            FrembedProvider to ProviderSupport(movies = true, tvShows = true),
            aploufProvider to ProviderSupport(movies = true, tvShows = false),
            FrenchMangaProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.ANIME),
            FrenchAnimeProvider to ProviderSupport(movies = true, tvShows = true, group = ProviderGroup.ANIME),
            KidrazProvider to ProviderSupport(movies = true, tvShows = false),
            WiflixProvider to ProviderSupport(movies = true, tvShows = true),
            VoirDramaProvider to ProviderSupport(movies = true, tvShows = true, enrichHome = false),
            VoirAnimeProvider to ProviderSupport(movies = true, tvShows = true, group = ProviderGroup.ANIME, enrichHome = false),
            WiTvProvider to ProviderSupport(movies = false, tvShows = true, enrichHome = false),
            OlaTvProvider to ProviderSupport(movies = false, tvShows = true, enrichHome = false),
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

        fun getGroup(provider: Provider): ProviderGroup {
            return providers[provider]?.group ?: ProviderGroup.FILMS_SERIES
        }

        fun getProvidersByGroup(group: ProviderGroup): List<Provider> {
            return providers.filter { it.value.group == group }.keys.toList()
        }
    }
}
