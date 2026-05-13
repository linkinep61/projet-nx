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
            // 2026-05-04: Nakios DÉSACTIVÉ comme provider standalone, mais le code
            // (NakiosProvider.kt + fetchNakiosBackupServers) reste actif. Il sert
            // UNIQUEMENT de source secondaire/backup d'extraction pour Cloudstream
            // (cf CloudstreamProvider.getServers ligne ~1680). Pas dans la liste
            // de providers visibles par le user.
            // NakiosProvider to ProviderSupport(movies = true, tvShows = true, enrichHome = false),

            // 2026-05-06: Cloudstream EN TÊTE — démarre vite, FR/VOSTFR strict,
            // catalogue TMDB-style avec carrousels riches + Nouveautés MovieBox+.
            // Backup #2 d'extraction = Nakios (TMDB-id-based, parallèle à MovieBox+).
            CloudstreamProvider to ProviderSupport(movies = true, tvShows = true, enrichHome = false),
            // 2026-05-04: Papadustream — provider standalone séries-only (DLE CMS).
            // Sources résolues via PapadustreamExtractor (WebView + getxfield AJAX).
            // 12 sources par épisode (VOE/Filemoon/Doodstream/Netu/Uqload/Vidoza × VF/VOSTFR).
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
            // 2026-05-12 : AnimeSite (animesite.fr) — provider anime FR. Next.js SPA,
            // catalogue parsé depuis la home server-rendered. 5 lecteurs par épisode
            // (sendvid x2 + myvi + sbfull + sendvid VF) — pour l'instant seul sendvid
            // est supporté (extractor existant). Series-only (movies = false).
            AnimeSiteProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.ANIME, enrichHome = false),
            // 2026-05-05: Moviebox (themoviebox.org/aoneroom) — niche K-Dramas + animes + films Hollywood VF.
            // v1: catalogue uniquement (search/browse). Extraction streams TODO v2.
            MovieboxProvider to ProviderSupport(movies = true, tvShows = true, enrichHome = false),
            // ═══════════════════════════════════════════════════════════════
            //  IPTV providers — Vavoo EN TÊTE (catalogue le plus large + stable)
            // ═══════════════════════════════════════════════════════════════
            // Vavoo : ~1000 chaînes IPTV FR via vavoo.to/oha.to/huhu.to/kool.to
            // (catalogue MediaHubMX, ping signature via lokke.app/api/app/ping).
            // Home organisé par genre, TNT order pour les chaînes principales.
            VavooProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
            WiTvProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
            OlaTvProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
            VegetaTvProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
            // 2026-05-11 : 3BoxTV — backup IPTV agrégateur (Google Sheets TSV).
            // Parser TSV heuristique + logging debug actif. Logo nettoyé.
            // Si le parser cale sur certaines catégories, on calibrera via les logs.
            // 2026-05-12 : DÉSACTIVÉ (demande user). Le fichier BoxXtemusProvider.kt
            // reste en place — pour réactiver, dé-commenter la ligne ci-dessous.
            // BoxXtemusProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
            // 2026-05-10 : SportLiveProvider supprimé (demande user). Le
            // fichier .kt physique a été retiré. Pour les chaînes sport, le
            // user passe directement par WiTV/Vegeta/Vavoo qui les listent.
            // 2026-05-10 : Movix LiveTV SUPPRIMÉ. Le code dépendait de
            // l'API Movix (api.movix.cash/livetv) avec des mirrors qui
            // timeout. Vavoo fonctionne bien dans Lokke car Lokke a un
            // VPN intégré qui débloque l'accès propre — sans VPN, depuis
            // Tahiti, le throttle vavoo casse la lecture. On garde Vegeta
            // + LiveReconnect comme solution IPTV.
            // 2026-05-08 : LiveTV Hub — méta-provider qui agrège WiTV+Ola+Vegeta
            // pour les chaînes mainstream (TF1/France2-5/M6/Arte/BFM/etc.).
            // Comme SportLive mais pour le généraliste. Favoris/bans
            // s'appliquent cross-provider via channelKey commun.
            LiveTvHubProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
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
