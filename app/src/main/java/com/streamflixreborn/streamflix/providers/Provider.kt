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
 *  that should apply to ALL IPTV providers, not just WiTV.
 *
 *  IPTV providers expose a [additionalServersFlow] of progressively-discovered
 *  channel variants — the player surfaces these as "Chaîne" entries so the
 *  user can switch streams without closing the player. */
interface IptvProvider {
    val additionalServersFlow: kotlinx.coroutines.flow.SharedFlow<Video.Server>
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
        enum class ProviderGroup {
            ANIME,
            FILMS_SERIES,
            IPTV  // Live TV channels (WiTv, OlaTv, Vegeta…)
        }

        data class ProviderSupport(
            val movies: Boolean,
            val tvShows: Boolean,
            val group: ProviderGroup = ProviderGroup.FILMS_SERIES,
            val enrichHome: Boolean = true
        )

        val providers: Map<Provider, ProviderSupport> = linkedMapOf(
            // French providers — custom display order
            // 2026-05-04: Nakios DÉSACTIVÉ TEMPORAIREMENT (request user, en attente
            // de l'intégration Papadustream comme source pour Movix). Pour
            // réactiver, décommenter la ligne ci-dessous.
            // NakiosProvider to ProviderSupport(movies = true, tvShows = true, enrichHome = false),
            // 2026-05-04: Papadustream — provider standalone séries-only (DLE CMS).
            // Sources résolues via PapadustreamExtractor (WebView + getxfield AJAX).
            // 12 sources par épisode (VOE/Filemoon/Doodstream/Netu/Uqload/Vidoza × VF/VOSTFR).
            // 2026-05-05: Réactivé — utilise PapadustreamCaptchaActivity (user-visible)
            // pour résoudre Cloudflare Turnstile manuellement quand le headless échoue.
            PapadustreamProvider to ProviderSupport(movies = false, tvShows = true),
            // FrenchStream: enrichment disabled — natural home a déjà
            // "Nouveautés Films" + "Nouveautés Séries".
            FrenchStreamProvider to ProviderSupport(movies = true, tvShows = true, enrichHome = false),
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
            // 2026-05-05: Moviebox (themoviebox.org/aoneroom) — niche K-Dramas + animes + films Hollywood VF.
            // v1: catalogue uniquement (search/browse). Extraction streams TODO v2.
            MovieboxProvider to ProviderSupport(movies = true, tvShows = true, enrichHome = false),
            WiTvProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
            OlaTvProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
            VegetaTvProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
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
