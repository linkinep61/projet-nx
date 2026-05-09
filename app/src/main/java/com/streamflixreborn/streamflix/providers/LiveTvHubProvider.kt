package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LiveTV Hub — provider META qui agrège les chaînes TV mainstream (TF1, France
 * 2/3/4/5, M6, Arte, BFM, CNews, etc.) depuis WiTV, OLA TV et Vegeta TV.
 *
 *  But
 *  ---
 *  Le user voulait pouvoir marquer un favori (ex: Vegeta[43] HD pour Arte) et
 *  voir aussi des servers Ola/WiTV pour la même chaîne, sans changer de provider.
 *  Le mécanisme favoris/bans (IptvFavorites + IptvBannedServers) marche déjà
 *  cross-provider tant que le channelKey est le même → on l'utilise tel quel.
 *
 *  Architecture (calquée sur SportLiveProvider)
 *  --------------------------------------------
 *   • Pas de backend propre. Liste curée de chaînes mainstream.
 *   • getServers appelle les 3 sous-providers en parallèle (timeout 6s chacun).
 *   • Servers retournés tels quels — leurs ids `vegeta_stream::`/`ola_stream::`/`m3u8::`
 *     restent reconnus par isIptv UI → croix/cœur fonctionnent.
 *   • getVideo délègue au provider d'origine via le préfixe id.
 *   • Aucun appel circulaire : LiveTvHub consomme WiTv/Ola/Vegeta mais inversement
 *     ils n'ont aucun backup chez LiveTvHub.
 */
object LiveTvHubProvider : Provider, IptvProvider {

    override val name = "TV Hub"
    override val baseUrl: String = "https://localhost/"
    override val logo: String =
        "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_livetvhub"
    override val language = "fr"

    private const val TAG = "LiveTvHubProvider"

    private val _additionalServersFlow = MutableSharedFlow<Video.Server>()
    override val additionalServersFlow: SharedFlow<Video.Server> = _additionalServersFlow.asSharedFlow()

    // ─────────────── Catalogue curé ───────────────

    private data class HubChannel(
        val displayName: String,
        /** Clé WiTV : norm-style — strip non-alphanumeric. "TF1" → "tf1". */
        val witvKey: String,
        /** Clé OLA/Vegeta — généralement identique à witvKey pour les chaînes
         *  mainstream sans caractères spéciaux. */
        val olaVegetaKey: String,
        /** Catégorie pour le home (Généraliste/Cinéma/Info/Sport/Musique/
         *  Documentaire/Enfants/Divertissement). Reprend la taxonomie WiTV/Ola/
         *  Vegeta pour cohérence cross-provider. */
        val category: String,
    )

    /** 2026-05-08 : catégorisation ALIGNÉE sur Vegeta TV (curatedChannels)
     *  pour cohérence cross-provider. Quand l'user marque ❤ sur une chaîne
     *  via Vegeta, elle se range dans la même catégorie au TV Hub.
     *  Liste extensive (= mêmes chaînes que Vegeta + variantes WiTV/Ola). */
    private val channels: List<HubChannel> = listOf(
        // ─── Généraliste (= Vegeta) ───
        HubChannel("TF1", "tf1", "tf1", "Généraliste"),
        HubChannel("TF1 Séries Films", "tf1seriesfilms", "tf1seriesfilms", "Généraliste"),
        HubChannel("TFX", "tfx", "tfx", "Généraliste"),
        HubChannel("TMC", "tmc", "tmc", "Généraliste"),
        HubChannel("France 2", "france2", "france2", "Généraliste"),
        HubChannel("France 3", "france3", "france3", "Généraliste"),
        HubChannel("France 4", "france4", "france4", "Généraliste"),
        HubChannel("France 5", "france5", "france5", "Généraliste"),
        HubChannel("France Ô", "franceo", "franceo", "Généraliste"),
        HubChannel("M6", "m6", "m6", "Généraliste"),
        HubChannel("6ter", "6ter", "6ter", "Généraliste"),
        HubChannel("W9", "w9", "w9", "Généraliste"),
        HubChannel("Arte", "arte", "arte", "Généraliste"),
        HubChannel("C8", "c8", "c8", "Généraliste"),
        HubChannel("NRJ 12", "nrj12", "nrj12", "Généraliste"),
        HubChannel("Chérie 25", "cherie25", "cherie25", "Généraliste"),
        HubChannel("Numéro 23", "numero23", "numero23", "Généraliste"),
        HubChannel("RMC Story", "rmcstory", "rmcstory", "Généraliste"),
        HubChannel("RTL9", "rtl9", "rtl9", "Généraliste"),
        HubChannel("AB1", "ab1", "ab1", "Généraliste"),
        HubChannel("Paris Première", "parispremiere", "parispremiere", "Généraliste"),
        HubChannel("Téva", "teva", "teva", "Généraliste"),
        HubChannel("LCP", "lcp", "lcp", "Généraliste"),
        HubChannel("Gulli", "gulli", "gulli", "Généraliste"),
        // ─── Cinéma (= Vegeta) ───
        HubChannel("Canal+", "canal", "canalplus", "Cinéma"),
        HubChannel("Canal+ Cinéma", "canalcinema", "canalpluscinema", "Cinéma"),
        HubChannel("Canal+ Séries", "canalseries", "canalplusseries", "Cinéma"),
        HubChannel("Canal+ Family", "canalfamily", "canalplusfamily", "Cinéma"),
        HubChannel("Canal+ Docs", "canaldocs", "canalplusdocs", "Cinéma"),
        HubChannel("Canal+ Box Office", "canalboxoffice", "canalplusboxoffice", "Cinéma"),
        HubChannel("Canal+ Grand Écran", "canalgrandecran", "canalplusgrandecran", "Cinéma"),
        HubChannel("OCS Max", "ocsmax", "ocsmax", "Cinéma"),
        HubChannel("OCS Géants", "ocsgeants", "ocsgeants", "Cinéma"),
        HubChannel("OCS Choc", "ocschoc", "ocschoc", "Cinéma"),
        HubChannel("OCS City", "ocscity", "ocscity", "Cinéma"),
        HubChannel("Ciné+ Premier", "cinepluspremier", "cinepluspremier", "Cinéma"),
        HubChannel("Ciné+ Frisson", "cineplusfrisson", "cineplusfrisson", "Cinéma"),
        HubChannel("Ciné+ Émotion", "cineplusemotion", "cineplusemotion", "Cinéma"),
        HubChannel("Ciné+ Famiz", "cineplusfamiz", "cineplusfamiz", "Cinéma"),
        HubChannel("Ciné+ Club", "cineplusclub", "cineplusclub", "Cinéma"),
        HubChannel("Ciné+ Classic", "cineplusclassic", "cineplusclassic", "Cinéma"),
        HubChannel("Paramount Channel", "paramountchannel", "paramountchannel", "Cinéma"),
        HubChannel("13ème Rue", "13emerue", "13emerue", "Cinéma"),
        HubChannel("Syfy", "syfy", "syfy", "Cinéma"),
        HubChannel("Warner TV", "warnertv", "warnertv", "Cinéma"),
        HubChannel("TCM Cinéma", "tcmcinema", "tcmcinema", "Cinéma"),
        HubChannel("Polar+", "polarplus", "polarplus", "Cinéma"),
        HubChannel("Action", "action", "action", "Cinéma"),
        // ─── Info (= Vegeta) ───
        HubChannel("BFM TV", "bfmtv", "bfmtv", "Info"),
        HubChannel("CNews", "cnews", "cnews", "Info"),
        HubChannel("LCI", "lci", "lci", "Info"),
        HubChannel("Franceinfo", "franceinfo", "franceinfo", "Info"),
        HubChannel("BFM Business", "bfmbusiness", "bfmbusiness", "Info"),
        HubChannel("France 24", "france24", "france24", "Info"),
        HubChannel("TV5 Monde", "tv5monde", "tv5monde", "Info"),
        HubChannel("Euronews", "euronews", "euronews", "Info"),
        HubChannel("i24 News", "i24news", "i24news", "Info"),
        HubChannel("BFM Paris Île-de-France", "bfmparisidf", "bfmparisidf", "Info"),
        HubChannel("BFM Régions", "bfmregions", "bfmregions", "Info"),
        // ─── Sport (= Vegeta) ───
        HubChannel("Canal+ Sport", "canalsport", "canalplussport", "Sport"),
        HubChannel("Canal+ Sport 360", "canalsport360", "canalplussport360", "Sport"),
        HubChannel("Canal+ Foot", "canalfoot", "canalplusfoot", "Sport"),
        // 2026-05-09 : catégorie dédiée "Canal+ Live"
        HubChannel("Canal+ Live 1", "canalpluslive1", "canalpluslive1", "Canal+ Live"),
        HubChannel("Canal+ Live 2", "canalpluslive2", "canalpluslive2", "Canal+ Live"),
        HubChannel("Canal+ Live 3", "canalpluslive3", "canalpluslive3", "Canal+ Live"),
        HubChannel("Canal+ Live 4", "canalpluslive4", "canalpluslive4", "Canal+ Live"),
        HubChannel("Canal+ Live 5", "canalpluslive5", "canalpluslive5", "Canal+ Live"),
        HubChannel("Canal+ Live 6", "canalpluslive6", "canalpluslive6", "Canal+ Live"),
        HubChannel("Canal+ Live 7", "canalpluslive7", "canalpluslive7", "Canal+ Live"),
        HubChannel("Canal+ Live 8", "canalpluslive8", "canalpluslive8", "Canal+ Live"),
        HubChannel("Canal+ Live 9", "canalpluslive9", "canalpluslive9", "Canal+ Live"),
        HubChannel("Canal+ Live 10", "canalpluslive10", "canalpluslive10", "Canal+ Live"),
        HubChannel("Canal+ Live 11", "canalpluslive11", "canalpluslive11", "Canal+ Live"),
        HubChannel("Canal+ Live 12", "canalpluslive12", "canalpluslive12", "Canal+ Live"),
        HubChannel("Canal+ Live 13", "canalpluslive13", "canalpluslive13", "Canal+ Live"),
        HubChannel("Canal+ Live 14", "canalpluslive14", "canalpluslive14", "Canal+ Live"),
        HubChannel("Canal+ Live 15", "canalpluslive15", "canalpluslive15", "Canal+ Live"),
        HubChannel("Canal+ Live 16", "canalpluslive16", "canalpluslive16", "Canal+ Live"),
        HubChannel("Canal+ Live 17", "canalpluslive17", "canalpluslive17", "Canal+ Live"),
        HubChannel("Canal+ Live 18", "canalpluslive18", "canalpluslive18", "Canal+ Live"),
        HubChannel("Canal+ Live 19", "canalpluslive19", "canalpluslive19", "Canal+ Live"),
        HubChannel("beIN Sports 1", "beinsports1", "beinsports1", "Sport"),
        HubChannel("beIN Sports 2", "beinsports2", "beinsports2", "Sport"),
        HubChannel("beIN Sports 3", "beinsports3", "beinsports3", "Sport"),
        HubChannel("RMC Sport 1", "rmcsport1", "rmcsport1", "Sport"),
        HubChannel("RMC Sport 2", "rmcsport2", "rmcsport2", "Sport"),
        HubChannel("RMC Sport 3", "rmcsport3", "rmcsport3", "Sport"),
        HubChannel("RMC Sport 4", "rmcsport4", "rmcsport4", "Sport"),
        HubChannel("Eurosport 1", "eurosport1", "eurosport1", "Sport"),
        HubChannel("Eurosport 2", "eurosport2", "eurosport2", "Sport"),
        HubChannel("Eurosport News", "eurosportnews", "eurosportnews", "Sport"),
        HubChannel("La Chaîne L'Équipe", "lachainelequipe", "lachainelequipe", "Sport"),
        HubChannel("L'Équipe", "lequipe", "lequipe", "Sport"),
        HubChannel("Infosport+", "infosportplus", "infosportplus", "Sport"),
        HubChannel("Equidia", "equidia", "equidia", "Sport"),
        HubChannel("AB Moteurs", "abmoteurs", "abmoteurs", "Sport"),
        HubChannel("Auto Moto La Chaîne", "automotolachaine", "automotolachaine", "Sport"),
        HubChannel("Motorvision TV", "motorvisiontv", "motorvisiontv", "Sport"),
        // ─── Musique (= Vegeta) ───
        HubChannel("MTV", "mtv", "mtv", "Musique"),
        HubChannel("MCM", "mcm", "mcm", "Musique"),
        HubChannel("M6 Music", "m6music", "m6music", "Musique"),
        HubChannel("M6 Music Hits", "m6musichits", "m6musichits", "Musique"),
        HubChannel("NRJ Hits", "nrjhits", "nrjhits", "Musique"),
        HubChannel("Trace Urban", "traceurban", "traceurban", "Musique"),
        HubChannel("Trace Africa", "traceafrica", "traceafrica", "Musique"),
        HubChannel("Trace Latina", "tracelatina", "tracelatina", "Musique"),
        HubChannel("Trace Tropical", "tracetropical", "tracetropical", "Musique"),
        HubChannel("Mezzo", "mezzo", "mezzo", "Musique"),
        HubChannel("Fun Radio TV", "funradiotv", "funradiotv", "Musique"),
        // ─── Documentaire (= Vegeta) ───
        HubChannel("Planète+", "planeteplus", "planeteplus", "Documentaire"),
        HubChannel("Ushuaïa TV", "ushuaiatv", "ushuaiatv", "Documentaire"),
        HubChannel("Histoire TV", "histoiretv", "histoiretv", "Documentaire"),
        HubChannel("National Geographic", "nationalgeographic", "nationalgeographic", "Documentaire"),
        HubChannel("Nat Geo Wild", "natgeowild", "natgeowild", "Documentaire"),
        HubChannel("Discovery Channel", "discoverychannel", "discoverychannel", "Documentaire"),
        HubChannel("Discovery Investigation", "discoveryinvestigation", "discoveryinvestigation", "Documentaire"),
        HubChannel("Discovery Family", "discoveryfamily", "discoveryfamily", "Documentaire"),
        HubChannel("Discovery Science", "discoveryscience", "discoveryscience", "Documentaire"),
        HubChannel("RMC Découverte", "rmcdecouverte", "rmcdecouverte", "Documentaire"),
        HubChannel("Animaux", "animaux", "animaux", "Documentaire"),
        HubChannel("Voyage", "voyage", "voyage", "Documentaire"),
        HubChannel("Chasse et Pêche", "chasseetpeche", "chasseetpeche", "Documentaire"),
        HubChannel("Seasons", "seasons", "seasons", "Documentaire"),
        HubChannel("Crime District", "crimedistrict", "crimedistrict", "Documentaire"),
        HubChannel("Crime + Investigation", "crimeplusinvestigation", "crimeplusinvestigation", "Documentaire"),
        HubChannel("Trek", "trek", "trek", "Documentaire"),
        HubChannel("ABXplore", "abxplore", "abxplore", "Documentaire"),
        // ─── Enfants (= Vegeta) ───
        HubChannel("Disney Channel", "disneychannel", "disneychannel", "Enfants"),
        HubChannel("Disney Junior", "disneyjunior", "disneyjunior", "Enfants"),
        HubChannel("Cartoon Network", "cartoonnetwork", "cartoonnetwork", "Enfants"),
        HubChannel("Boomerang", "boomerang", "boomerang", "Enfants"),
        HubChannel("Nickelodeon", "nickelodeon", "nickelodeon", "Enfants"),
        HubChannel("Nick Jr.", "nickjr", "nickjr", "Enfants"),
        HubChannel("Tiji", "tiji", "tiji", "Enfants"),
        HubChannel("Piwi+", "piwiplus", "piwiplus", "Enfants"),
        HubChannel("Canal J", "canalj", "canalj", "Enfants"),
        HubChannel("Tfou Max", "tfoumax", "tfoumax", "Enfants"),
        HubChannel("Toonami", "toonami", "toonami", "Enfants"),
        HubChannel("Boing", "boing", "boing", "Enfants"),
        HubChannel("Mangas", "mangas", "mangas", "Enfants"),
        HubChannel("Game One", "gameone", "gameone", "Enfants"),
    )

    /** Ordre fixe des catégories au home (cohérent avec WiTV/Ola/Vegeta). */
    private val categoryOrder: List<String> = listOf(
        "Généraliste", "Cinéma", "Canal+ Live", "Info", "Sport", "Musique", "Documentaire", "Enfants",
    )

    private val channelById: Map<String, HubChannel> by lazy {
        channels.associateBy { "livehub::${it.witvKey}" }
    }

    /** Mapping nom de chaîne → logo officiel via repo tv-logos/tv-logos.
     *  Réutilisé de Vegeta pour cohérence visuelle entre les providers. */
    private val manualLogoMap: Map<String, String> by lazy {
        val base = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france"
        mapOf(
            "tf1" to "$base/tf1-fr.png",
            "tf1seriesfilms" to "$base/tf1-series-films-fr.png",
            "tfx" to "$base/tfx-fr.png",
            "tmc" to "$base/tmc-fr.png",
            "france2" to "$base/france-2-fr.png",
            "france3" to "$base/france-3-fr.png",
            "france4" to "$base/france-4-fr.png",
            "france5" to "$base/france-5-fr.png",
            "franceo" to "$base/france-o-fr.png",
            "franceinfo" to "$base/france-info-fr.png",
            "france24" to "$base/france-24-fr.png",
            "m6" to "$base/m6-fr.png",
            "6ter" to "$base/6ter-fr.png",
            "w9" to "$base/w9-fr.png",
            "arte" to "$base/arte-fr.png",
            "canal" to "$base/canal-plus-fr.png",
            "canalcinema" to "$base/canal-plus-cinema-fr.png",
            "canalseries" to "$base/canal-plus-series-fr.png",
            "bfmtv" to "$base/bfm-tv-fr.png",
            "bfmbusiness" to "$base/bfm-business-fr.png",
            "cnews" to "$base/cnews-fr.png",
            "lci" to "$base/lci-fr.png",
            "lcp" to "$base/lcp-fr.png",
            "rtl9" to "$base/rtl-9-fr.png",
            "nrj12" to "$base/nrj-12-fr.png",
            "cherie25" to "$base/cherie-25-fr.png",
            "gulli" to "$base/gulli-fr.png",
            "disneychannel" to "$base/disney-channel-fr.png",
            "cartoonnetwork" to "$base/cartoon-network-fr.png",
            "boomerang" to "$base/boomerang-fr.png",
            "nationalgeographic" to "$base/national-geographic-fr.png",
            "discoverychannel" to "$base/discovery-channel-fr.png",
            "parispremiere" to "$base/paris-premiere-fr.png",
            "teva" to "$base/teva-fr.png",
            "histoiretv" to "$base/histoire-tv-fr.png",
            "voyage" to "$base/voyage-fr.png",
        )
    }

    private fun logoUrlFor(witvKey: String, name: String): String {
        manualLogoMap[witvKey]?.let { return it }
        // Fallback : carré violet avec initiales
        val initials = name
            .replace(Regex("[^A-Za-z0-9 +]"), "")
            .split(" ")
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "T" }
        val encoded = java.net.URLEncoder.encode(initials, "UTF-8")
        return "https://ui-avatars.com/api/?name=$encoded&background=7C3AED&color=fff&size=512&bold=true&format=png"
    }

    private fun channelToTvShow(c: HubChannel): TvShow = TvShow(
        id = "livehub::${c.witvKey}",
        title = c.displayName,
        overview = "Chaîne TV — agrégée depuis WiTV, OLA TV et Vegeta TV.",
        quality = "Live",
        poster = logoUrlFor(c.witvKey, c.displayName),
        banner = logoUrlFor(c.witvKey, c.displayName),
        providerName = name,
    )

    // ─────────────── Provider impl ───────────────

    /** Returns la liste des channels du TV Hub : chaînes marquées favorites,
     *  cross-provider. Combine 2 sources :
     *   1. IptvFavoritesStore = chaîne marquée favorite (long-press → ★ sur la card)
     *   2. IptvFavorites = AU MOINS un server marqué favori (❤) sur n'importe quel
     *      provider IPTV pour cette chaîne — ça crée la chaîne dans le hub
     *      automatiquement (l'user a juste à mettre un ❤ sur un server WiTV/Ola/
     *      Vegeta TF1 et TF1 apparaît dans le Hub avec les servers favoris).
     *
     *  2026-05-08 : pivot UX — TV Hub n'est plus un catalogue exhaustif mais
     *  un agrégateur de favoris uniquement.
     *  2026-05-08 (fix) : inclure aussi les server-favorites (❤ picker), pas
     *  juste les channel-favorites (★ long-press). */
    private fun favoriteChannels(): List<HubChannel> {
        val channelFavKeys = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
            .getAllCanonicalFavorites()
        val serverFavKeys = com.streamflixreborn.streamflix.fragments.player.settings
            .IptvFavorites.getAllChannelKeysWithFavorites()
        val allFavKeys = channelFavKeys + serverFavKeys
        return channels.filter { it.witvKey in allFavKeys || it.olaVegetaKey in allFavKeys }
    }

    override suspend fun getHome(): List<Category> {
        val favs = favoriteChannels()
        val sections = mutableListOf<Category>()
        // Filtre les chaînes bannies du home (elles vont dans la section
        // "Chaînes bannies" en bas).
        val notBanned = favs.filter {
            !com.streamflixreborn.streamflix.fragments.player.settings
                .IptvBannedChannels.isBanned("livehub::${it.witvKey}")
        }
        // 2026-05-08 : groupe les chaînes favorites par catégorie (Généraliste,
        // Cinéma, Info, Sport, Musique, Documentaire, Enfants) — même ordre
        // que WiTV/Ola/Vegeta. Au fur et à mesure que l'user ajoute des
        // favoris, ils se rangent automatiquement dans la bonne section.
        val byCat = notBanned.groupBy { it.category }
        for (cat in categoryOrder) {
            val list = byCat[cat] ?: continue
            if (list.isEmpty()) continue
            sections.add(Category(name = cat, list = list.map { channelToTvShow(it) }))
        }
        // Catch-all pour catégories non listées dans categoryOrder (rare).
        for ((cat, list) in byCat) {
            if (cat in categoryOrder) continue
            sections.add(Category(name = cat, list = list.map { channelToTvShow(it) }))
        }

        // 2026-05-08 : section "✕ Chaînes bannies" EN BAS du home.
        try {
            val banned = channels.filter {
                com.streamflixreborn.streamflix.fragments.player.settings
                    .IptvBannedChannels.isBanned("livehub::${it.witvKey}")
            }
            if (banned.isNotEmpty()) {
                sections.add(Category(name = "✕ Chaînes bannies", list = banned.map { channelToTvShow(it) }))
            }
        } catch (_: Throwable) { }
        return sections
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page > 1) return emptyList()
        return favoriteChannels().map { channelToTvShow(it) }
    }

    override suspend fun search(query: String, page: Int): List<TvShow> {
        if (page > 1) return emptyList()
        val q = query.trim().lowercase()
        val favs = favoriteChannels()
        if (q.isBlank()) return favs.map { channelToTvShow(it) }
        return favs.filter { it.displayName.lowercase().contains(q) }
            .map { channelToTvShow(it) }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getMovie(id: String): Movie =
        throw UnsupportedOperationException("LiveTvHub n'a pas de films")

    override suspend fun getTvShow(id: String): TvShow {
        val c = channelById[id] ?: throw IllegalArgumentException("Channel inconnue: $id")
        val tvShow = channelToTvShow(c)
        return tvShow.copy(
            seasons = listOf(
                Season(id = id, number = 1, title = "En Direct",
                    episodes = listOf(Episode(id = id, number = 1,
                        title = "Regarder en Direct", poster = tvShow.poster)))
            )
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()

    override suspend fun getGenre(id: String, page: Int): Genre =
        throw UnsupportedOperationException("LiveTvHub n'a pas de genres")

    override suspend fun getPeople(id: String, page: Int): People =
        throw UnsupportedOperationException("LiveTvHub n'a pas de people")

    /** getServers : ONLY favorites (❤ servers cross-provider).
     *
     *  2026-05-08 (pivot) : le user veut que TV Hub ne charge QUE les servers
     *  marqués favoris (❤). C'est cohérent avec l'UX du Hub (vue agrégée des
     *  favoris uniquement) et ça évite de relancer 3 providers à chaque clic.
     *
     *  Fallback : si la chaîne est arrivée dans le Hub via channel-favorite ★
     *  (long-press) mais SANS aucun ❤ server marqué, on agrège quand même les
     *  3 providers en parallèle (timeout 6s) pour ne pas avoir un Hub qui plante.
     *
     *  2026-05-08 (fix) : cherche les favoris sous LES DEUX clés (witvKey ET
     *  olaVegetaKey). Sans ça, marquer ❤ sur Vegeta TV → stocké sous
     *  "canalplus", mais le Hub cherchait sous "canal" (witvKey) → fallback
     *  déclenché à tort, l'user voyait toute la liste agrégée. */
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val c = channelById[id] ?: return emptyList()

        // 1. Favoris ❤ d'abord (cross-provider via IptvFavorites).
        //    Cherche sous les 2 clés possibles (witvKey + olaVegetaKey) car
        //    l'user peut avoir marqué le ❤ depuis n'importe quel provider.
        val crossFavorites = mutableListOf<Video.Server>()
        val seenIds = mutableSetOf<String>()
        for (lookupKey in listOf("livehub::${c.witvKey}", "vegeta::${c.olaVegetaKey}", "ola::${c.olaVegetaKey}", "ch::${c.witvKey}")) {
            val favs = IptvCrossDelegate.getFavoritesAsServers(lookupKey, existingIds = seenIds)
            for (srv in favs) {
                if (seenIds.add(srv.id)) crossFavorites += srv
            }
        }
        if (crossFavorites.isNotEmpty()) {
            Log.d(TAG, "getServers '${c.displayName}' → ${crossFavorites.size} cross-favs (favoris uniquement)")
            return crossFavorites
        }

        // 2. Fallback : la chaîne est dans le Hub via ★ mais sans ❤ server marqué
        //    → on agrège pour ne pas avoir un player vide.
        return coroutineScope {
            val witvD = async {
                runCatching {
                    withTimeoutOrNull(6_000L) {
                        WiTvProvider.getServers("ch::${c.witvKey}", videoType)
                    } ?: emptyList()
                }.getOrElse { emptyList() }
            }
            val olaD = async {
                runCatching {
                    withTimeoutOrNull(6_000L) {
                        OlaTvProvider.getServers("ola::${c.olaVegetaKey}", videoType)
                    } ?: emptyList()
                }.getOrElse { emptyList() }
            }
            val vegetaD = async {
                runCatching {
                    withTimeoutOrNull(6_000L) {
                        VegetaTvProvider.getServers("vegeta::${c.olaVegetaKey}", videoType)
                    } ?: emptyList()
                }.getOrElse { emptyList() }
            }

            val all = witvD.await() + olaD.await() + vegetaD.await()
            // Dédup par URL (la même chaîne peut être diffusée via la même URL
            // sur plusieurs providers — pas la peine de doubler)
            val seenSrc = HashSet<String>()
            val deduped = all.filter { srv -> seenSrc.add(srv.src.ifBlank { srv.id }) }

            Log.d(TAG, "getServers '${c.displayName}' → ${deduped.size} (fallback agrégé : pas de ❤ favori)")
            deduped
        }
    }

    /** getVideo : délègue au provider d'origine selon le prefix de l'id.
     *  Couvre tous les providers IPTV via IptvCrossDelegate. */
    override suspend fun getVideo(server: Video.Server): Video {
        return IptvCrossDelegate.delegateGetVideo(server)
            ?: WiTvProvider.getVideo(server)  // fallback si format inconnu
    }

    // ─────────────── Channel navigation (next/previous) ───────────────
    // 2026-05-08 : boutons ⏮ ⏭ pour zapper entre chaînes dans le Hub.
    // L'ordre suit getHome (favorites uniquement, par catégorie, bannies exclues).

    /** Returns la liste ordonnée des channelIds du Hub (= ordre du home,
     *  par catégorie, favorites uniquement, bannies exclues). */
    fun getOrderedChannelIds(): List<String> {
        val favs = favoriteChannels()
        val notBanned = favs.filter {
            !com.streamflixreborn.streamflix.fragments.player.settings
                .IptvBannedChannels.isBanned("livehub::${it.witvKey}")
        }
        val ids = mutableListOf<String>()
        val byCat = notBanned.groupBy { it.category }
        for (cat in categoryOrder) {
            byCat[cat]?.forEach { ids += "livehub::${it.witvKey}" }
        }
        for ((cat, list) in byCat) {
            if (cat in categoryOrder) continue
            list.forEach { ids += "livehub::${it.witvKey}" }
        }
        return ids
    }

    /** Returns l'id de la chaîne avant [currentId] dans la liste ordonnée. */
    fun getPreviousChannelId(currentId: String): String? {
        val list = getOrderedChannelIds()
        val idx = list.indexOf(currentId)
        return if (idx > 0) list[idx - 1] else null
    }

    /** Returns l'id de la chaîne après [currentId] dans la liste ordonnée. */
    fun getNextChannelId(currentId: String): String? {
        val list = getOrderedChannelIds()
        val idx = list.indexOf(currentId)
        return if (idx in 0 until list.lastIndex) list[idx + 1] else null
    }

    /** Returns le displayName de la chaîne pour [channelId]. */
    fun getChannelDisplayName(channelId: String): String? {
        return channelById[channelId]?.displayName
    }

    /** Returns le poster (logo URL) de la chaîne pour [channelId]. */
    fun getChannelPoster(channelId: String): String? {
        val c = channelById[channelId] ?: return null
        return logoUrlFor(c.witvKey, c.displayName)
    }
}
