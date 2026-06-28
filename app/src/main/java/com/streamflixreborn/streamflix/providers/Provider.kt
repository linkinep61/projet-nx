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
            // 2026-05-21 (user) : ordre du home Films/Séries imposé =
            //   1 Cloudstream · 2 Movix · 3 Wiflix · 4 FrenchStream · 5 1Jour1Film
            //   · 6 Frembed · 7 aplouf · puis les autres (Papadustream, VoirDrama, Moviebox).
            //   L'ordre d'insertion dans ce linkedMapOf = l'ordre d'affichage par onglet.
            CloudstreamProvider to ProviderSupport(movies = true, tvShows = true, enrichHome = false),
            MovixProvider to ProviderSupport(movies = true, tvShows = true),
            WiflixProvider to ProviderSupport(movies = true, tvShows = true),
            // FrenchStream: enrichment disabled — home naturel a déjà "Nouveautés".
            FrenchStreamProvider to ProviderSupport(movies = true, tvShows = true, enrichHome = false),
            UnJourUnFilmProvider to ProviderSupport(movies = true, tvShows = true),
            FrembedProvider to ProviderSupport(movies = true, tvShows = true),
            aploufProvider to ProviderSupport(movies = true, tvShows = false),
            // « ensuite les autres » (Films/Séries) :
            // Papadustream — séries-only (DLE CMS), sources via PapadustreamExtractor.
            PapadustreamProvider to ProviderSupport(movies = false, tvShows = true),
            VoirDramaProvider to ProviderSupport(movies = true, tvShows = true, enrichHome = false),
            // Moviebox (themoviebox.org/aoneroom) — niche K-Dramas/animes/films VF.
            MovieboxProvider to ProviderSupport(movies = true, tvShows = true, enrichHome = false),
            // NetMirror — Netflix/Prime/Hotstar/Disney+ via net52.cc mirror.
            // Catalogue TMDB + CatalogFilter, streams via NewTV API.
            NetMirrorProvider to ProviderSupport(movies = true, tvShows = true, enrichHome = false),
            // ─── Anime (onglet Animés) — ordre interne inchangé ───
            AnimeSamaProvider to ProviderSupport(movies = true, tvShows = true, group = ProviderGroup.ANIME),
            FrenchMangaProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.ANIME),
            FrenchAnimeProvider to ProviderSupport(movies = true, tvShows = true, group = ProviderGroup.ANIME),
            // 2026-06-28 : ancien DessinAnimeProvider Kotlin MASQUÉ — remplacé par
            //   le WebJsProvider ci-dessous (même site, logique hébergée sur GitHub,
            //   éditable sans rebuild).
            // DessinAnimeProvider to ProviderSupport(movies = true, tvShows = true, group = ProviderGroup.ANIME),
            WebJsProvider(
                name = "DessinAnime",
                baseUrl = "https://dessinanime.cc",
                jsUrl = "https://raw.githubusercontent.com/linkinep61/projet-nx/main/provider_web/dessinanime.js",
                logo = "android.resource://${com.streamflixreborn.streamflix.BuildConfig.APPLICATION_ID}/drawable/logo_dessinanime",
                language = "fr",
            ) to ProviderSupport(movies = true, tvShows = true, group = ProviderGroup.ANIME, enrichHome = false),
            FranimeProvider to ProviderSupport(movies = true, tvShows = true, group = ProviderGroup.ANIME),
            VoirAnimeProvider to ProviderSupport(movies = true, tvShows = true, group = ProviderGroup.ANIME, enrichHome = false),
            // 2026-05-21 : AnimeSite SUPPRIMÉ du provider list (user). SendvidExtractor reste dispo pour les autres.
            // AnimeSiteProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.ANIME, enrichHome = false),
            // 2026-05-18 : AllMovieLand SUPPRIMÉ. Catalog principalement Bollywood
            // (pas l'usage anime voulu) + serveur Cloudflare-protected qui exige un
            // bypass WebView fragile. User : "supprime ce provider, on fera jamais
            // ce qu'on veut avec".
            // AllMovieLandProvider to ProviderSupport(movies = true, tvShows = false, group = ProviderGroup.ANIME, enrichHome = false),
            // ═══════════════════════════════════════════════════════════════
            //  IPTV providers — Vavoo EN TÊTE (catalogue le plus large + stable)
            // ═══════════════════════════════════════════════════════════════
            // Vavoo : ~1000 chaînes IPTV FR via vavoo.to/oha.to/huhu.to/kool.to
            // (catalogue MediaHubMX, ping signature via lokke.app/api/app/ping).
            // Home organisé par genre, TNT order pour les chaînes principales.
            VavooProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
            // 2026-05-31 : WiTV supprimé — consommait trop de quota Firebase.
            //   (Firebase entièrement retiré le 2026-06-02 ; OlaTV couvre les mêmes chaînes.)
            OlaTvProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
            VegetaTvProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
            // 2026-05-17 : 3BoxTV/BoxXtemus complètement retiré (fichier supprimé).
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
            // 2026-06-08 (user "il faudrait dupliquer le TV Hub, ajouts dans
            //   un autre TV Hub +") : provider séparé pour le contenu extra
            //   (World Live TV + à venir Tiri Marrano +18). Garde TV Hub
            //   principal léger avec uniquement les chaînes curated FR.
            LiveTvHubPlusProvider to ProviderSupport(movies = false, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
            // 2026-05-12 : Mon IPTV — provider user-configurable. L'utilisateur
            // ajoute ses propres URLs M3U via IptvSourcesActivity (clic provider
            // = ouvre tableau des sources). Auto-classification TV/Films/Séries
            // affichées en sections dans getHome(). Group=IPTV pour rester
            // visuellement avec Vavoo/WiTV/Ola dans l'onglet "TV / IPTV"
            // du picker.
            // 2026-05-12 : Mon IPTV — provider user-configurable (M3U/Xtream/Stalker)
            // ré-activé après release v1.7.186.
            MyIptvProvider to ProviderSupport(movies = true, tvShows = true, group = ProviderGroup.IPTV, enrichHome = false),
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
