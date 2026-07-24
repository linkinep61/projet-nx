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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_favori"
    override val language = "fr"

    private const val TAG = "LiveTvHubProvider"
    private const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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
        // 2026-05-31 : Canal+ Live 1-19 RETIRÉS (ne fonctionnent pas)
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
        "Généraliste", "Cinéma", "Info", "Sport", "Musique", "Documentaire", "Enfants",
    )

    // ═══════════════════════════════════════════════════════════════
    //  2026-05-15 (user "Je te propose des liens URL direct de chaînes
    //  à intégrer") : chaînes bonus avec URLs directes en provenance des
    //  mirrors bolaloca/cartelive/embedme + Dailymotion.
    //
    //  3 mirrors par chaîne pour fallback :
    //    - https://bolaloca.my/player/2/<id>
    //    - https://cartelive.club/player/2/<id>
    //    - https://embedme.click/player/2/<id>
    //
    //  Extracteur : Hoca8Extractor (WebView, intercepte le m3u8 chargé par
    //  l'iframe interne hoca8.com/footy.php?live=<feed>).
    //
    //  Note : Ligue1+ ne fonctionne que pendant un match.
    // ═══════════════════════════════════════════════════════════════
    private data class BonusChannel(
        val displayName: String,
        val id: Int,                  // numéro chaîne dans le système bolaloca (1-48)
        val category: String,
        val logoKey: String? = null,  // clé pour manualLogoMap si applicable
    )

    private val bonusChannels: List<BonusChannel> = listOf(
        // ─── BeinSport (1-10) ───
        BonusChannel("beIN Sports 1", 1, "Sport"),
        BonusChannel("beIN Sports 2", 2, "Sport"),
        BonusChannel("beIN Sports 3", 3, "Sport"),
        BonusChannel("beIN Sports MAX 4", 4, "Sport"),
        BonusChannel("beIN Sports MAX 5", 5, "Sport"),
        BonusChannel("beIN Sports MAX 6", 6, "Sport"),
        BonusChannel("beIN Sports MAX 7", 7, "Sport"),
        BonusChannel("beIN Sports MAX 8", 8, "Sport"),
        BonusChannel("beIN Sports MAX 9", 9, "Sport"),
        BonusChannel("beIN Sports MAX 10", 10, "Sport"),
        // ─── Canal+ (11-14) ───
        BonusChannel("Canal+", 11, "Cinéma", "canal"),
        BonusChannel("Canal+ Foot", 12, "Sport"),
        BonusChannel("Canal+ Sport", 13, "Sport"),
        BonusChannel("Canal+ Sport 360", 14, "Sport"),
        // ─── Sport (15-19) ───
        // 2026-05-15 : audit batch — supprimé Ligue 1+ 1/4/5/6 (B18, B20-B22) →
        // ne diffusent que pendant les matchs, retournent timeout 99% du temps.
        BonusChannel("Eurosport 1", 15, "Sport"),
        BonusChannel("Eurosport 2", 16, "Sport"),
        BonusChannel("RMC Sport 1", 17, "Sport"),
        BonusChannel("Automoto", 19, "Sport"),
    )
    // 2026-05-31 : Canal+ Live 2-9 bonus RETIRÉS (ne fonctionnent pas)

    /** ID Dailymotion pour Sport en France. Pas d'extraction WebView nécessaire
     *  — DailymotionExtractor gère déjà. */
    private const val DAILYMOTION_SPORT_EN_FRANCE_ID = "x8sayn8"

    // ═══════════════════════════════════════════════════════════════
    //  2026-05-15 (user "intègre freeshot.live") : 48 chaînes FR
    //  scrapées depuis freeshot.live/live-tv/france. Chaque chaîne pointe
    //  vers sa page freeshot, et FreeshotExtractor (WebView) suit le chain
    //  d'iframes pour récupérer le m3u8.
    // ═══════════════════════════════════════════════════════════════
    private data class FreeshotChannel(
        val displayName: String,
        val slug: String,
        val id: String,
        val category: String,
    )

    // 2026-05-15 (user "supprime ce qui ne sert à rien") : liste vidée après
    // audit batch — TOUTES les 48 chaînes freeshot ont échoué (Cloudflare anti-bot
    // bloque freeshot.live au niveau TLS, ERR_CONNECTION_CLOSED côté WebView et
    // SSLHandshakeException côté OkHttp). La liste reste DÉCLARÉE (vide) pour ne
    // pas casser le routing getTvShow/getServers/getVideo qui regarde le préfixe
    // `livehub::freeshot::` au cas où des users ont des favoris stockés. Le code
    // de rendering itère sur liste vide donc rien ne s'affiche, propre.
    private val freeshotChannels: List<FreeshotChannel> = emptyList()

    private fun bonusToTvShow(c: BonusChannel): TvShow {
        val logoUrl = c.logoKey?.let { manualLogoMap[it] }
            ?: "https://ui-avatars.com/api/?name=${java.net.URLEncoder.encode(c.displayName, "UTF-8")}&background=DC2626&color=fff&size=128&bold=true&format=png"
        return TvShow(
            id = "livehub::bonus::${c.id}",
            title = c.displayName,
            overview = "Chaîne TV — 3 sources mirrors (bolaloca/cartelive/embedme).",
            quality = "Live",
            poster = logoUrl,
            banner = logoUrl,
            providerName = name,
        )
    }

    private fun dailymotionChannelToTvShow(): TvShow {
        val logo = "https://ui-avatars.com/api/?name=SF&background=0EA5E9&color=fff&size=128&bold=true&format=png"
        return TvShow(
            id = "livehub::dailymotion::$DAILYMOTION_SPORT_EN_FRANCE_ID",
            title = "Sport en France",
            overview = "Sport en France — diffusion Dailymotion.",
            quality = "Live",
            poster = logo,
            banner = logo,
            providerName = name,
        )
    }

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
            "rmcstory" to "$base/rmc-story-fr.png",
            "rmcdecouverte" to "$base/rmc-decouverte-fr.png",
            "rmclife" to "$base/bfm-tv-fr.png",
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
        return "https://ui-avatars.com/api/?name=$encoded&background=7C3AED&color=fff&size=128&bold=true&format=png"
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

    // 2026-06-19 (user "optimise pour pas faire ramer les petits appareils") :
    //   cache getHome 60s. Sur un OPPO bas de gamme, le getHome construit
    //   ~15-25 sections (OTF, Adrar, Replays, Favoris, Sport bonus, etc.)
    //   avec fetch réseau (4s OTF + 3s replays + 2s adar) + ~50ms de logique
    //   par section. Le re-déclencher à chaque scroll/refresh saturait le CPU.
    //   Avec ce cache, 95% des navigations utilisent un snapshot mémoire.
    //   Le cache est invalidé par ProviderChangeNotifier (sélection groupe OTF/Adrar
    //   change), par favori ajouté/retiré (= signature change), ou par TTL 60s.
    @Volatile private var cachedHome: List<Category>? = null
    @Volatile private var cachedHomeSignature: String = ""
    @Volatile private var cachedHomeAt: Long = 0L
    private val HOME_CACHE_TTL_MS = 60_000L

    private fun computeHomeSignature(favs: List<HubChannel>): String {
        val favSig = favs.joinToString(",") { it.witvKey }
        val otfGroup = try { com.streamflixreborn.streamflix.utils.OtfTvService.selectedGroup } catch (_: Throwable) { "" }
        val bannedHash = try {
            com.streamflixreborn.streamflix.fragments.player.settings
                .IptvBannedChannels.getAllBannedKeys().sorted().joinToString(",").hashCode().toString()
        } catch (_: Throwable) { "0" }
        val catFilter = try {
            val ctx = appContextRef
            if (ctx != null) com.streamflixreborn.streamflix.providers
                .BoxXtemusCategorySettings.getCurrentCode(ctx)
            else ""
        } catch (_: Throwable) { "" }
        // v13 : inclure les favoris replay dans la signature pour invalider
        //   le cache quand l'user ajoute/retire un favori replay.
        val replayFavSig = try {
            com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.all()
                .joinToString(",") { it.id }
        } catch (_: Throwable) { "" }
        return "$favSig|$otfGroup|$bannedHash|$catFilter|$replayFavSig"
    }

    fun clearHomeCache() {
        cachedHome = null
        cachedHomeAt = 0L
    }

    override suspend fun getHome(): List<Category> {
        val tHomeStart = System.currentTimeMillis()
        val favs = favoriteChannels()
        // Cache hit ? 95% des refresh tombent ici.
        val sig = computeHomeSignature(favs)
        val now = System.currentTimeMillis()
        val cached = cachedHome
        if (cached != null && sig == cachedHomeSignature && now - cachedHomeAt < HOME_CACHE_TTL_MS) {
            Log.d(TAG, "getHome: cache HIT (${cached.size} sections, ${(now - cachedHomeAt) / 1000}s old)")
            return cached
        }
        Log.d(TAG, "getHome: COLD START — building from scratch")
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

        // 2026-06-22 : Canal+ bonus OTF RETIRÉ (ne fonctionne plus).
        // 2026-06-14 : `lciForOtf` retiré avec le bloc BoxXtemus.

        // 2026-05-15 : sections BONUS — wrappé dans try/catch pour ne pas bloquer OTF
        try {
        val bonusBanned = { id: String ->
            com.streamflixreborn.streamflix.fragments.player.settings
                .IptvBannedChannels.isBanned(id)
        }
        val bonusByCat = bonusChannels
            .filterNot { bonusBanned("livehub::bonus::${it.id}") }
            .groupBy { it.category }
        // Sport bonus : on append à la section Sport existante OU on crée si absente.
        val sportBonus = bonusByCat["Sport"].orEmpty().map { bonusToTvShow(it) }
        if (sportBonus.isNotEmpty()) {
            val existingIdx = sections.indexOfFirst { it.name == "Sport" }
            if (existingIdx >= 0) {
                val merged = sections[existingIdx].list + sportBonus
                sections[existingIdx] = Category(name = "Sport", list = merged)
            } else {
                sections.add(Category(name = "Sport", list = sportBonus))
            }
        }
        // Dailymotion Sport en France dans Sport
        val dmShow = dailymotionChannelToTvShow()
        if (!bonusBanned(dmShow.id)) {
            val existingIdx = sections.indexOfFirst { it.name == "Sport" }
            if (existingIdx >= 0) {
                sections[existingIdx] = Category(
                    name = "Sport",
                    list = sections[existingIdx].list + dmShow,
                )
            } else {
                sections.add(Category(name = "Sport", list = listOf(dmShow)))
            }
        }
        // 2026-06-22 : Canal+ bonus OTF RETIRÉ (ne fonctionne plus).
        // 2026-05-31 : Canal+ Live section RETIRÉE (chaînes ne fonctionnent pas)

        // 2026-05-15 : freeshot.live chaînes retirées (freeshotChannels = emptyList,
        //   Cloudflare bloque toutes les 48 chaînes). Favoris anciens via getServers/getVideo.

        } catch (e: Exception) {
            Log.w(TAG, "Bonus/freeshot sections failed: ${e.message}")
        }

        // 2026-07-12 : OTF TV = LAZY LOAD — on n'appelle PAS l'API OTF au boot.
        //   Le folder card apparaît quand même grâce à alwaysShowKeys("otf")
        //   dans groupSectionsIntoFolders. Au clic, LiveHubFolderDialog détecte
        //   folderContents["otf"] == null → lance le fetch on-demand via
        //   showOtfWithGroupPicker (toast "Chargement…" + fetch API + dialog).
        //   NE PAS ajouter de section vide : sinon folderContents["otf"] contient
        //   1 Category vide que le dialog prend pour du cache → dialog vide.

        // 2026-06-14 (user "3tvbox a fermé, on nettoie sauf l'alias") :
        //   bloc BoxXtemus retiré (= ne pollue plus le hub avec ses 12 catégories
        //   "France TV - TF1+/Canal+/...", qui faisaient doublon avec World Live).
        //   La pipeline BoxXtemus reste 100% fonctionnelle pour World Live si
        //   l'user ajoute manuellement une URL playlist via le picker — c'est
        //   juste son injection automatique dans CE hub qui disparait.

        // 2026-06-14 (user "donc maintenant tu peux retirer la source du TV
        //   hub c'est plus gênant") : Dric4rTV DÉSACTIVÉ du TvHub.
        //   - Les 17 radios sont maintenant HARDCODÉES dans RadioCatalog.kt
        //     (HARDCODED_DRIC4RTV_RADIOS) avec leurs URLs CDN directes
        //     (icecast.radiofrance.fr, infomaniak, etc.) → continuent à jouer
        //     même si dric4rt.free.fr meurt.
        //   - Les chaînes TV intéressantes (178 chaînes Nature/Muzik/Sports/
        //     Locales/Horse + "90 Is Good IT") sont migrées vers le data.m3u
        //     externe (raw.githubusercontent.com/xdata-mix/nx-data/main/data.m3u
        //     — migré depuis Codeberg en 2026-06-15 car ubuntu-latest plus fiable)
        //     accessible via le provider World Live (MyIptvProvider URL externe).
        //   - 23 "Live & DJ set" droppées (= .mp4 statiques sur la Freebox
        //     personnelle de Dric4rd, pas du vrai live).
        //
        //   Le code Dric4rTvProvider.kt RESTE en place pour le moment afin de
        //   préserver les anciens favoris cœur (IDs `livehub::dric4rtv::*`)
        //   qui pourraient toujours être joués via getServers/getVideo. Seul
        //   l'injection auto dans le TvHub disparait.
        //
        //   Pour réactiver temporairement (ex: debug), passer DRIC4RTV_IN_HUB
        //   à true en haut du fichier.
        // 2026-06-14 : Dric4rTV injection dans le TvHub désactivée (user
        //   a demandé de retirer cette source du Hub). Dric4rTvProvider.kt
        //   reste pour compat des anciens favoris (IDs `livehub::dric4rtv::*`).

        // 2026-06-08 (user "favoris ça marche sur OTF mais pas France TV /
        //   Dric4rTV") : section "Favoris France TV" qui agrège les chaînes
        //   favorites de BoxXtemus + Dric4rTV. On parcourt les sections déjà
        //   construites et on garde celles dont l'ID matche un favKey du store.
        try {
            val allFavs = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                .getFavorites("TV Hub")  // = livehub::ftv* + livehub::dric*
            val ftvFavKeys = allFavs
                .filter { it.startsWith("livehub::ftv") || it.startsWith("livehub::dric") }
                .map { it.removePrefix("livehub::") }  // "ftv<name>" ou "dric<name>"
                .toSet()
            if (ftvFavKeys.isNotEmpty()) {
                val ftvFavShows = mutableListOf<TvShow>()
                val seenIds = HashSet<String>()
                // Re-normalize l'ID de chaque chaîne France TV/Dric4rTV des sections
                // déjà collectées et compare à ftvFavKeys.
                for (sec in sections) {
                    if (!sec.name.startsWith("France TV - ")) continue
                    (sec.list as? List<*>)?.forEach { item ->
                        val tv = item as? TvShow ?: return@forEach
                        if (!seenIds.add(tv.id)) return@forEach
                        val normalizedKey = when {
                            tv.id.startsWith("livehub::francetv::") ->
                                "ftv" + tv.id.substringAfterLast("::").lowercase().trim()
                            tv.id.startsWith("livehub::dric4rtv::") ->
                                "dric" + tv.id.substringAfterLast("::").lowercase().trim()
                            else -> return@forEach
                        }
                        if (normalizedKey in ftvFavKeys) {
                            ftvFavShows.add(
                                TvShow(id = tv.id, title = tv.title).apply {
                                    providerName = "TV Hub"
                                    poster = tv.poster
                                    banner = tv.banner
                                }
                            )
                        }
                    }
                }
                if (ftvFavShows.isNotEmpty()) {
                    // Insérer JUSTE AVANT la première section "France TV - ..."
                    val firstFtvIdx = sections.indexOfFirst { it.name.startsWith("France TV - ") }
                    val favSection = Category(name = "Favoris France TV", list = ftvFavShows)
                    if (firstFtvIdx >= 0) {
                        sections.add(firstFtvIdx, favSection)
                    } else {
                        sections.add(favSection)
                    }
                    Log.d(TAG, "Favoris France TV: ${ftvFavShows.size} chaînes")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Favoris France TV section failed: ${t.message}")
        }

        // 2026-06-08 (user "il faudrait dupliquer le TV Hub. Ce qu'on vient
        //   d'ajouter là, le mettre dans un autre TV Hub +") : World Live TV
        //   déplacé dans LiveTvHubPlusProvider pour ne pas surcharger ce Hub.

        // 2026-06-19 (user "maintenant faut que ça soit optimisé par dossier" +
        //   "maintenant le poids doit être vraiment réduit Au niveau des fetchs
        //   en général") : LAZY FETCH du m3u replay. Au boot, on n'appelle PAS
        //   fetchReplayCategories (= fetch 452 KB GitHub + parse 1979 programmes).
        //   À la place, on ajoute juste les sections HARDCODED Live TF1+ et
        //   Live M6+ (= les 5 chaînes connues, ne nécessitent pas le m3u).
        //   Les sections Replay TF1/TMC/M6/W9/Arte/France TV sont fetchées
        //   on-demand au clic sur le dossier correspondant (LiveHubFolderDialog).
        try {
            val liveHardcoded = buildHardcodedLiveSections()
            if (liveHardcoded.isNotEmpty()) {
                sections.addAll(liveHardcoded)
                Log.d(TAG, "Live hardcoded sections added: ${liveHardcoded.size} (no replay m3u fetch)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Live hardcoded failed: ${t.message}")
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
        // 2026-06-08 (user "quand on fait LEFT, faut TOUTES les catégories de
        //   chaînes, pas qu'une seule") : populate le cache `homeChannelsCache`
        //   qui agrège TOUTES les sections (sauf "Favoris" + "Chaînes bannies")
        //   pour que getOrderedChannelIds() et getChannelDisplayName() puissent
        //   lister WiTV + OTF + France TV + Vavoo + n'importe quelle catégorie.
        try {
            val cache = mutableListOf<Pair<String, String>>()
            for (cat in sections) {
                if (cat.name.equals("Favoris", ignoreCase = true)) continue
                if (cat.name.startsWith("✕")) continue
                (cat.list as? List<*>)?.forEach { item ->
                    val tv = item as? TvShow ?: return@forEach
                    if (tv.id.isNotBlank()) {
                        val title = tv.title.ifBlank { tv.id }
                        cache.add(tv.id to title)
                    }
                }
            }
            // Dedup par id (préserve l'ordre — 1re occurrence gardée)
            val seenIds = HashSet<String>()
            homeChannelsCache = cache.filter { seenIds.add(it.first) }
            Log.d(TAG, "homeChannelsCache populated: ${homeChannelsCache.size} channels across ${sections.size} sections")
        } catch (t: Throwable) {
            Log.w(TAG, "homeChannelsCache populate failed: ${t.message}")
        }
        // 2026-06-20 (user "pouvoir mettre La série en favoris En restant appuyé
        //   longtemps dessus Et qu'elle apparaisse directement sur le TV hub") :
        //   section Favoris Replay en haut (juste après Favoris IPTV).
        //   v13 : AVANT le filtre catégorie pour que les favoris soient visibles
        //   même quand un filtre est actif (= bug corrigé).
        try {
            val replayFavEntries = com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.all()
            if (replayFavEntries.isNotEmpty()) {
                val replayFavShows = replayFavEntries.map { e ->
                    TvShow(
                        id = e.id,
                        title = e.title,
                        poster = e.poster,
                        banner = e.banner,
                    ).apply {
                        providerName = "TV Hub"
                        isMovie = e.isMovie
                    }
                }
                // Insérer après la section "Favoris" IPTV (si elle existe), sinon en tête
                val existingFavIdx = sections.indexOfFirst { it.name.equals("Favoris", ignoreCase = true) }
                val insertIdx = if (existingFavIdx >= 0) existingFavIdx + 1 else 0
                sections.add(insertIdx, Category(name = "Favoris Replay", list = replayFavShows))
            }
        } catch (_: Throwable) {}

        // 2026-06-19 v35 (user "ce ne sont pas les bonnes catégories... ça
        //   devrait être tout ce qu'il y a dans le TV Hub en catégorie") :
        //   filtre les sections par la catégorie sélectionnée dans
        //   BoxXtemusCategorySettings. Si ALL_CODE → tout, sinon ne garde
        //   QUE la section dont le name == currentCode.
        val ctxForFilter = appContextRef
        if (ctxForFilter != null) {
            val currentCode = com.streamflixreborn.streamflix.providers
                .BoxXtemusCategorySettings.getCurrentCode(ctxForFilter)
            if (currentCode != com.streamflixreborn.streamflix.providers
                    .BoxXtemusCategorySettings.ALL_CODE) {
                val filtered = sections.filter {
                    it.name.equals(currentCode, ignoreCase = true) ||
                    it.name.equals("Favoris Replay", ignoreCase = true)
                }
                // Cache le résultat filtré aussi
                cachedHome = filtered
                cachedHomeSignature = sig
                cachedHomeAt = System.currentTimeMillis()
                Log.d(TAG, "getHome: built+cached ${filtered.size} sections (filter=$currentCode)")
                return filtered
            }
        }

        // 2026-06-19 (user "faut peut-être qu'on optimise notre TV hub et faire
        //   des dossiers Pour que ça charge moins et que ça charge qu'à
        //   l'ouverture du dossier") : regroupe les sections lourdes en
        //   dossiers cliquables. Au boot du home, l'user voit ~10 cards au
        //   lieu de 200+ → moins de bitmaps chargés par Glide → moins de RAM.
        //   Au clic sur un dossier, LiveHubFolderDialog liste les
        //   sous-catégories puis les chaînes.
        val foldered = groupSectionsIntoFolders(sections)
        // Stocke le résultat dans le cache mémoire pour les 60s suivantes.
        cachedHome = foldered.toList()
        cachedHomeSignature = sig
        cachedHomeAt = System.currentTimeMillis()
        Log.d(TAG, "getHome: built+cached ${foldered.size} sections (=>${sections.size} originales regroupées en dossiers) in ${System.currentTimeMillis() - tHomeStart}ms (TTL 60s)")
        return foldered
    }

    /** 2026-06-19 : wrapper public pour LiveHubFolderDialog (= lazy fetch
     *  on-demand au clic sur un dossier Replay). */
    suspend fun fetchReplayCategoriesPublic(): List<Category> = fetchReplayCategories()

    /** 2026-06-19 : retourne Live TF1+ et Live M6+ hardcodés (= sans fetch du
     *  m3u replay). Permet d'afficher ces 2 sections au boot sans payer le
     *  coût du fetch m3u (= 452 KB GitHub + parse 1979 programmes). Les
     *  chaînes restent jouables car leurs IDs `livehub::replay::m6live::*` et
     *  `livehub::replay::tf1live::*` sont résolus par M6Resolver / TF1Resolver. */
    private fun buildHardcodedLiveSections(): List<Category> {
        val out = mutableListOf<Category>()
        val appCtx = appContextRef
        // Live TF1+ : 11 chaînes Direct FIXES (BASIC = gratuites)
        //   5 traditionnelles + 6 externes + carte 🔓 Connexion si non connecté.
        // Les ~25 chaînes FAST (replay 24/7 — Camping Paradis, Alice Nevers,
        // Baby Boom, Demain nous appartient, etc.) sont DYNAMIQUES et viennent
        // du m3u data-replay-tf1.m3u (= refresh toutes les 5 min via
        // scripts/refresh_tf1.py + cron-job.org). Le parser m3u (parseReplayM3u
        // ~ligne 2650) détecte les URLs `tf1live://L_FAST_*` et les ajoute à
        // la même catégorie "Live TF1+". Si TF1 ajoute/retire une FAST, l'app
        // la voit au prochain refresh m3u SANS rebuild APK.
        val tf1Logo = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/tf1-fr.png"
        val liveTf1 = listOf(
            // ── Traditionnelles ──
            Triple("TF1",                  "tf1",              "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/tf1-fr.png"),
            Triple("TMC",                  "tmc",              "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/tmc-fr.png"),
            Triple("TFX",                  "tfx",              "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/tfx-fr.png"),
            Triple("TF1 Séries Films",     "tf1-series-films", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/tf1-series-films-fr.png"),
            Triple("LCI",                  "lci",              "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/lci-fr.png"),
            // ── Chaînes externes (gratuites sur TF1+) ──
            Triple("ARTE",                "arte",             "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/arte-fr.png"),
            Triple("L'Equipe",            "l-equipe",         "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/lequipe-fr.png"),
            Triple("LCP / Public Sénat",  "lcp-public-senat", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/lcp-an-fr.png"),
            Triple("Le Figaro TV",        "le-figaro",        tf1Logo),
            Triple("Paris Première",      "novo19",           "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/paris-premiere-fr.png"),
            Triple("Red Bull TV",         "redbulltv",        tf1Logo),
        )
        // 2026-06-23 : SPLIT TF1+ EN 2 SOURCES (sur demande user) :
        //   - Chaînes FIXES (11) hardcoded ici : TF1, TMC, TFX, TF1-Séries-Films,
        //     LCI, ARTE, L'Equipe, LCP, Le Figaro, Paris Première, Red Bull TV.
        //     Ces chaînes sont stables et ne nécessitent PAS de refresh fréquent.
        //   - Chaînes FAST (replay 24/7) viennent du m3u data-replay-tf1.m3u qui
        //     est refresh toutes les 5 min via scripts/refresh_tf1.py + cron-job.org.
        //     Le parser m3u (parseReplayM3u, lignes ~2680) détecte les URLs
        //     `tf1live://L_FAST_*` et les ajoute à la même catégorie "Live TF1+".
        //   AVANTAGE : si TF1 ajoute/retire une chaîne FAST, l'app la voit au
        //   prochain refresh m3u SANS rebuild APK.
        val tf1Channels = liveTf1.map { (label, service, logo) ->
            TvShow(
                id = "livehub::replay::tf1live::$service",
                title = label,
            ).apply {
                providerName = "TV Hub"
                poster = logo
                banner = logo
                val siKey = "tf1live::$service"
                replayProgramTitles[siKey] = label
                replayProgramSrcs[siKey] = "tf1live://$service"
            }
        }
        val tf1List = mutableListOf<TvShow>()
        if (appCtx != null && !com.streamflixreborn.streamflix.utils.TF1Auth.isLoggedIn(appCtx)) {
            tf1List.add(makeLoginCard("tf1", "🔓 Connexion TF1+"))
        }
        tf1List.addAll(tf1Channels)
        // 2026-06-23 (user "je vois pas les chaînes Live supplémentaire Dans TF1+
        //   qu'on est censé refresh tous les 5 min") : merger les FAST channels
        //   du replay m3u (= `tf1live://L_FAST_*`) qui ne sont PAS dans le hardcode.
        try {
            // 2026-06-23 (user "FAST channels TF1+ apparaissent pas dans home") :
            //   au boot, replayCacheSections est vide (= lazy fetch). On force
            //   un parse SYNCHRONOUS du cache DISQUE si disponible pour avoir
            //   les FAST channels dès le premier rendu.
            if (replayCacheSections.isEmpty()) {
                val diskFile = replayDiskCacheFile
                if (diskFile != null && diskFile.exists()) {
                    try {
                        val body = diskFile.readText()
                        val parsed = parseReplayM3u(body)
                        if (parsed.isNotEmpty()) {
                            replayCacheSections = parsed
                            replayCacheTs = System.currentTimeMillis()
                            Log.d(TAG, "Live TF1+ pre-parse from DISK: ${parsed.size} cats")
                        }
                    } catch (_: Throwable) {}
                }
            }
            val replayLiveTf1 = replayCacheSections.firstOrNull { it.name == "Live TF1+" }
            val fastItems = (replayLiveTf1?.list as? List<*>)?.filterIsInstance<TvShow>()
                ?.filter { it.id.contains("L_FAST_") } ?: emptyList()
            if (fastItems.isNotEmpty()) {
                tf1List.addAll(fastItems)
                Log.d(TAG, "Live TF1+ merged: +${fastItems.size} FAST channels from replay m3u")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Live TF1+ FAST merge failed: ${t.message}")
        }
        out.add(Category(name = "Live TF1+", list = tf1List))
        // Live M6+ (4 chaînes M6/W9/6ter/Gulli gratuites) + carte 🔓 Connexion
        val live6 = listOf(
            Triple("M6",    "m6",    "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/m6-fr.png"),
            Triple("W9",    "w9",    "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/w9-fr.png"),
            Triple("6ter",  "6ter",  "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/6ter-fr.png"),
            Triple("Gulli", "gulli", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/gulli-fr.png"),
        )
        val m6Channels = live6.map { (label, service, logo) ->
            TvShow(
                id = "livehub::replay::m6live::$service",
                title = label,
            ).apply {
                providerName = "TV Hub"
                poster = logo
                banner = logo
                // 2026-06-20 : même fix que TF1+ — clé LONG (m6live::$service)
                //   pour matcher le get dans getServers (sinon fallback francetv://).
                val siKey = "m6live::$service"
                replayProgramTitles[siKey] = label
                replayProgramSrcs[siKey] = "m6live://$service"
            }
        }
        val m6List = mutableListOf<TvShow>()
        if (appCtx != null && !com.streamflixreborn.streamflix.utils.M6Auth.isLoggedIn(appCtx)) {
            m6List.add(makeLoginCard("m6", "🔓 Connexion 6play"))
        }
        m6List.addAll(m6Channels)
        out.add(Category(name = "Live M6+", list = m6List))
        // Live France TV (14 chaînes gratuites — si_ids API Yatta francetv)
        //   Pas besoin de login, toutes les chaînes France TV sont en clair.
        //   Résolution via FrancetvResolver (francetv://<si_id>).
        // 2026-06-23 (user "il manque les jaquettes Live France TV") : remplace
        //   les anciens imgur i.imgur.com/... (= morts) par les vrais logos
        //   officiels via raw.githubusercontent.com/tv-logo/tv-logos. Pour les
        //   chaînes thématiques france.tv (Sport/Docs/Séries/Mieux/INA) qui
        //   n'ont pas de logo dans tv-logos, on utilise leur asset officiel
        //   france.tv via images.france.tv/v0/<si_id>.
        val tvLogos = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france"
        val liveFtv = listOf(
            Triple("France 2",          "006194ea-117d-4bcf-94a9-153d999c59ae", "$tvLogos/france-2-fr.png"),
            Triple("France 3",          "29bdf749-7082-4426-a4f3-595cc436aa0d", "$tvLogos/france-3-fr.png"),
            Triple("France 4",          "9a6a7670-dde9-4264-adbc-55b89558594b", "$tvLogos/france-4-fr.png"),
            Triple("France 5",          "45007886-f3ff-4b3e-9706-1ef1014c5a60", "$tvLogos/france-5-fr.png"),
            Triple("franceinfo",        "35be22fb-1569-43ff-857c-99bf81defa2e", "$tvLogos/franceinfo-fr.png"),
            Triple("France 24",         "da9e13f0-42f3-4618-9954-9f5b63e0fe1f", "$tvLogos/france-24-fr.png"),
            Triple("Arte",              "7e3d129e-9c17-4d49-a25e-5e913ba91e35", "$tvLogos/arte-fr.png"),
            Triple("france.tv Sport",   "33a20612-120a-4e60-bd96-225146e4cf0c", "https://medias.france.tv/Z-JbiuxrvoIXjZU38qxUqrr7S1c/400x400/filters:quality(85)/x/f/8/phpmax8fx.png"),
            Triple("france.tv Docs",    "1e4bd223-8b32-42a0-bc24-12edb323eec7", "https://medias.france.tv/LN-jkEQRhHC-zBKvJHK8bubIY6w/800x450/filters:quality(85)/5/a/i/phpktcia5.png"),
            Triple("france.tv Séries",  "61c8c8fd-2454-4fee-80cb-38419e78eea2", "https://medias.france.tv/i-Z2u5FG6GnesxovOQCjMCOvAh4/800x450/filters:quality(85)/h/a/n/phpzpjnah.png"),
            Triple("france.tv Mieux",   "c7471f59-f1c4-4a73-9184-8ef2ce96d6d8", "$tvLogos/france-5-fr.png"),
            Triple("Public Sénat",      "733d60e7-914f-40ed-b296-0ddbc4b6d414", "https://medias.france.tv/XojCo_Ue1Yw50gyRR7X_vo1ZoRg/400x400/filters:quality(85)/i/g/i/phpbtsigi.jpg"),
            Triple("TV5 Monde",         "c2b61cd9-4923-44e9-820b-b7365734d507", "https://medias.france.tv/DkgjPtoHhedFWOmgweYOorW7B7I/300x0/filters:quality(85)/g/r/l/phpthllrg.png"),
            Triple("INA",               "ce51459d-2ada-484d-98e2-c8b24e95912d", "https://medias.france.tv/1u0HqgdutwU_L3xnJqwUBuwkNHg/400x400/filters:quality(85)/r/r/j/phpltsjrr.jpg"),
        )
        val ftvChannels = liveFtv.map { (label, siId, logo) ->
            TvShow(
                id = "livehub::replay::francetv-live::$siId",
                title = label,
            ).apply {
                providerName = "TV Hub"
                poster = logo
                banner = logo
                val siKey = "francetv-live::$siId"
                replayProgramTitles[siKey] = label
                replayProgramSrcs[siKey] = "francetv://$siId"
            }
        }
        out.add(Category(name = "Live France TV", list = ftvChannels.toMutableList()))
        // Live BFM Play (5 chaînes — nécessitent un compte RMC BFM Play connecté)
        //   Résolution via BfmResolver (bfmlive://<channel>).
        val bfmBase = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france"
        val liveBfm = listOf(
            Triple("BFM TV",          "bfmtv",         "$bfmBase/bfm-tv-fr.png"),
            Triple("RMC Story",       "rmcstory",      "$bfmBase/rmc-story-fr.png"),
            Triple("RMC Découverte",  "rmcdecouverte", "$bfmBase/rmc-decouverte-fr.png"),
            Triple("BFM Business",    "bfmbusiness",   "$bfmBase/bfm-business-fr.png"),
            Triple("RMC Life",        "rmclife",       "$bfmBase/bfm-tv-fr.png"),
        )
        val bfmChannels = liveBfm.map { (label, chanKey, logo) ->
            TvShow(
                id = "livehub::replay::bfmlive::$chanKey",
                title = label,
            ).apply {
                providerName = "TV Hub"
                poster = logo
                banner = logo
                val bfmKey = "bfmlive::$chanKey"
                replayProgramTitles[bfmKey] = label
                replayProgramSrcs[bfmKey] = "bfmlive://$chanKey"
            }
        }
        val bfmList = mutableListOf<TvShow>()
        if (appCtx != null && !com.streamflixreborn.streamflix.utils.BfmAuth.isLoggedIn(appCtx)) {
            // 2026-06-21 v2 (user "BFM se déconnecte à chaque fois qu'on
            //   quitte le provider") : si le token est expiré mais qu'on a
            //   les credentials sauvegardés → relogin silencieux via REST.
            val bfmAutoRelogged = if (com.streamflixreborn.streamflix.utils.BfmSsoAuth.hasCredentials(appCtx)) {
                try {
                    val fresh = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        com.streamflixreborn.streamflix.utils.BfmSsoAuth.reloginFromSaved(appCtx)
                    }
                    fresh != null
                } catch (_: Exception) { false }
            } else false
            if (!bfmAutoRelogged) {
                bfmList.add(makeLoginCard("bfm", "🔓 Connexion RMC BFM Play"))
            }
        }
        bfmList.addAll(bfmChannels)
        out.add(Category(name = "Live BFM Play", list = bfmList))
        return out
    }

    /** 2026-06-19 : registre des sections regroupées en dossier. La clé est
     *  le groupKey (= "tf1plus", "m6plus", "francetv", "arte", "otf", "adrar"
     *  + autres). La valeur est la liste des Category originales (= ce qui
     *  était dans `sections` avant le regroupement). LiveHubFolderDialog lit
     *  ce registre au clic sur un dossier. */
    val folderContents = java.util.concurrent.ConcurrentHashMap<String, List<Category>>()

    /** Regroupe les sections détaillées en dossiers cliquables.
     *  2026-06-19 v2 (user "tout le reste est encore affiché Du coup charge
     *  encore") : on étend le regroupement à TOUTES les sections sauf Favoris,
     *  pour ne laisser visibles QUE 2 lignes au home (Favoris + 📁 Dossiers).
     *  L'user voit ~10 cards au lieu de 200+. Au clic sur un dossier, dialog
     *  liste les sous-catégories puis les chaînes. */
    private fun groupSectionsIntoFolders(sections: List<Category>): List<Category> {
        // Définit les groupes par regex sur le nom de section.
        data class FolderDef(val key: String, val label: String, val regex: Regex)
        val defs = listOf(
            // 2026-06-19 v3 (user "tu me gares juste live M6 et Live TF1") :
            //   Live TF1+ et Live M6+ NE sont PAS dans les dossiers TF1+/M6+
            //   → ils restent en sections directes au home.
            // 2026-06-20 v5 (user "sous-dossiers séries TF1+") : le `$` est
            //   remplacé par `(\\s.*)?$` pour capter les sous-catégories
            //   ex "Replay TF1 - Séries U.S", "Replay TF1 - 100% Nostalgie".
            //   Au clic, le dialog affiche les sous-catégories puis les séries.
            // 2026-06-23 (user "C'est pas compliqué actuellement c'est déj Bien
            //   On clique sur film Ensuite on voit les catégories") : les
            //   sections thématiques scrapées "Replay TF1+ Films - <section>"
            //   et "Replay TF1+ Séries - <section>" doivent être DANS le folder
            //   Replay TF1+ existant (= pas dans 2 nouveaux folders séparés).
            //   Le regex est élargi pour capter aussi "Replay TF1+ Films - X"
            //   et "Replay TF1+ Séries - X" en plus des chaînes individuelles.
            FolderDef("tf1plus", "Replay TF1+", Regex("^Replay (TF1|TMC|TFX|TF1 Séries Films|LCI)(\\s.*)?$|^Replay TF1\\+ (Films|Séries) - .*$")),
            FolderDef("m6plus", "Replay M6+", Regex("^Replay (M6|W9|6ter|Gulli|Paris Première|Téva)(\\s.*)?$")),
            FolderDef("bfmplay", "Replay BFM Play", Regex("^Replay (BFM TV|RMC Story|RMC Découverte|BFM Business|RMC Life)(\\s.*)?$")),
            // 2026-06-22 (user "fais des bons dossiers pour pas que ça soit
            //   trop mélangé, comme l'exemple du site") : les thématiques
            //   transverses sont regroupées dans des dossiers dédiés, séparés
            //   des chaînes individuelles. Le script refresh_replays.py
            //   génère les group-title "Thématique <Plateforme> - <nom>".
            //   - BFM Play : 14 thématiques (Crime, Cinéma, Moteur, Aventure,
            //     Divertissement, Documentaire, Mystère, Histoire, Science,
            //     Société, Docu-Réalité, Sport, Info & Talk, Grand Reportage)
            //   - M6+ : 9 thématiques (Divertissement, Séries réalité, Séries,
            //     Sport, Infos & Société, Cinéma, Téléfilms, Jeunesse, Podcasts)
            FolderDef("bfmthemes", "Thématiques BFM Play", Regex("^Thématique BFM Play - .*$")),
            FolderDef("m6themes", "Thématiques M6+", Regex("^Thématique M6\\+ - .*$")),
            // 2026-06-26 : FUSION — un seul dossier "France TV" qui contient les
            //   CHAÎNES (Replay France 2/3/...) ET les CATÉGORIES thématiques
            //   ("Thématique France TV - <Cat> - <Rayon>"). LiveHubFolderDialog
            //   range les chaînes sous "Chaînes" et chaque catégorie à part.
            FolderDef("francetv", "France TV", Regex("^Replay (France ?[2-5]|France ?24|france ?info|France ?info|France ô|FranceTV|Slash).*|^Thématique France TV - .*$", RegexOption.IGNORE_CASE)),
            // 2026-06-19 (user "le dossier Arte ne fonctionne pas") : les noms
            //   réels dans data-replay.m3u sont "Arte Cinéma", "Arte Histoire",
            //   "Arte Sciences", etc. — PAS de préfixe "Replay ".
            // 2026-06-22 : refonte regex Arte — couvre les 3 patterns possibles :
            //   "Arte Cinéma", "Arte Concert - Pop & Rock", "Arte Thématique - À voir en famille".
            FolderDef("arte", "Arte", Regex("^Arte (?!Concert -|Thématique -).*", RegexOption.IGNORE_CASE)),
            FolderDef("arteconcert", "Arte Concert", Regex("^Arte Concert - .*$")),
            FolderDef("artethemes", "Arte Thématiques", Regex("^Arte Thématique - .*$")),
            FolderDef("otf", "OTF TV", Regex("^OTF TV.*")),
            // 2026-06-19 v4 (user "tu la mets juste dans un dossier" pour Sport) :
            //   les sections WiTV sont DANS les dossiers (= au home, l'user
            //   voit "📁 Sport", "📁 Cinéma", etc. + Live TF1+/M6+ + Favoris).
            //   Clic sur le dossier → dialog liste les chaînes.
            // 2026-07-13 : "Généraliste" WiTV RETIRÉ du home (user "qu'est-ce qu'il fout là")
            //   → absorbé par le catch-all "autres_replay" ci-dessous.
            FolderDef("cinema", "Cinéma", Regex("^Cinéma$")),
            FolderDef("info", "Info", Regex("^Info$")),
            FolderDef("sport", "Sport", Regex("^Sport$")),
            FolderDef("musique", "Musique", Regex("^Musique$")),
            // 2026-07-10 (user "supprime l'intégration LumiChat partout") : dossier LumiChat RETIRÉ.
            // 2026-07-10 (user "supprime la version de base, renomme notre dossier CF comme la base") :
            //   la version CF (scrape live + logos + bypass WebView + cache 24h + auto-réparation) DEVIENT
            //   LE dossier Stream4Free. L'ancienne version de base (résolveur OkHttp + git) est retirée.
            //   Clé interne `stream4cf`/prefix `stream4cf://` conservés (invisibles), affichage = « Stream4Free ».
            FolderDef("stream4cf", "Stream4Free", Regex("^Stream4Free - .*")),
            // 2026-07-08 : dossier NetMirror TV hub MIS DE CÔTÉ (archivé C:\Users\guill\Desktop\ONYX).
            //   Lecture WebView pas finalisée (CORS/hls.js). Provider natif NetMirror = OK par ailleurs.
            FolderDef("documentaire", "Documentaire", Regex("^Documentaire$")),
            FolderDef("enfants", "Enfants", Regex("^Enfants$")),
            FolderDef("divertissement", "Divertissement", Regex("^Divertissement$")),
            FolderDef("bonus", "Bonus / Dailymotion", Regex("^(Bonus|Dailymotion|Freeshot).*", RegexOption.IGNORE_CASE)),
            FolderDef("francetv_box", "France TV (chaînes)", Regex("^France TV - .*")),
            // FAST channels — seul Plex masqué (404), les autres OK
            FolderDef("samsung_tvplus", "Samsung TV+", Regex("^Samsung TV\\+.*")),
            FolderDef("pluto_tv", "Pluto TV", Regex("^Pluto TV.*")),
            // 2026-06-26 (user "réactive Plex TV on va essayer de la réparer") : réactivé.
            FolderDef("plex_tv", "Plex TV", Regex("^Plex TV.*")),
            FolderDef("lg_channels", "LG Channels", Regex("^LG Channels.*")),
            FolderDef("rakuten_tv", "Rakuten TV", Regex("^Rakuten TV.*")),
            FolderDef("sony_one", "Sony One", Regex("^Sony One.*")),
            // Catch-all : Arte sous-catégories, Replay non encore matchés.
            //   On limite au préfixe "Replay " (= n'attrape pas WiTV).
            // 2026-07-13 : "Généraliste" WiTV absorbé ici (plus de dossier dédié au home)
            FolderDef("autres_replay", "Autres Replays", Regex("^Replay .*|^Généraliste$")),
        )
        // Sections gardées visibles directement (= pas dans un dossier) :
        //   - Favoris (= le top du home)
        //   - Live TF1+ / Live M6+ (= user veut accès direct, 1-clic)
        //   - ✕ Chaînes bannies (= meta-section bottom)
        fun isVisibleDirect(name: String): Boolean {
            if (name.equals("Favoris", ignoreCase = true)) return true
            if (name.equals("Favoris Replay", ignoreCase = true)) return true
            if (name.startsWith("Live ")) return true
            if (name.startsWith("✕")) return true
            return false
        }
        // Identifie quelles sections vont dans quels dossiers.
        //   Toute section non-visible-direct matche au moins le catch-all
        //   "Autres" (= Regex(".+")) en dernier recours.
        val sectionToFolder = mutableMapOf<Category, FolderDef>()
        for (sec in sections) {
            if (isVisibleDirect(sec.name)) continue
            for (def in defs) {
                if (def.regex.matches(sec.name)) {
                    sectionToFolder[sec] = def
                    break
                }
            }
        }
        if (sectionToFolder.isEmpty()) return sections
        // Populate folderContents (= cache pour LiveHubFolderDialog)
        // 2026-06-27 (user "des dossiers disparaissent pendant la navigation et
        //   réapparaissent si on recharge") : NE PAS faire folderContents.clear()
        //   global — ça effaçait le contenu LAZY (Pluto/Plex/Musique/FAST/WorldWide,
        //   peuplé à la volée par le dialog) à chaque getHome(). On ne vide QUE les
        //   clés re-dérivées des sections ce tour-ci ; les clés lazy sont préservées.
        val sectionKeys = sectionToFolder.values.map { it.key }.toSet()
        for (k in sectionKeys) folderContents.remove(k)
        for ((sec, def) in sectionToFolder) {
            val existing = folderContents.getOrDefault(def.key, emptyList())
            folderContents[def.key] = existing + sec
        }
        // Log détaillé pour debug (user "Sport fonctionnait avant, plus maintenant")
        for ((key, secs) in folderContents) {
            val totalChans = secs.sumOf { (it.list as? List<*>)?.size ?: 0 }
            val secNames = secs.joinToString(", ") { "${it.name}(${(it.list as? List<*>)?.size ?: 0})" }
            Log.d(TAG, "folderContents[$key] = $totalChans chans across ${secs.size} sections: $secNames")
        }
        // Construit les cards dossier
        // 2026-06-19 (user "la moitié des catégories qu'il y avait disparu
        //   dans un dossier") : on AFFICHE TOUJOURS les cards Replay
        //   (tf1plus/m6plus/francetv/arte/autres_replay) même si vides au
        //   boot (le contenu est fetché on-demand au click via lazy fetch).
        //   Sans ça, dossiers Replay invisibles au home en mode lazy.
        val alwaysShowKeys = setOf("tf1plus", "m6plus", "bfmplay", "francetv", "arte", "autres_replay", "samsung_tvplus", "pluto_tv", "plex_tv", "lg_channels", "rakuten_tv", "sony_one", "musique", "stream4cf", "otf")
        // 2026-06-20 (user "mettre une petite jaquette sur les dossiers pour faire
        //   joli, correspondant au replay/catégorie") : map folderKey → URL logo.
        //   Pour les bouquets de chaînes (TF1+/M6+/France TV/Arte/OTF/Adrar), on
        //   utilise le logo de leur chaîne phare ou marque. Pour les thématiques,
        //   on n'attribue rien (l'emoji 📁 + label suffisent).
        val tvLogosBase = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france"
        val folderArtwork = mapOf(
            "tf1plus"       to "$tvLogosBase/tf1-fr.png",
            "m6plus"        to "$tvLogosBase/m6-fr.png",
            "bfmplay"       to "$tvLogosBase/bfm-tv-fr.png",
            "francetv"      to "$tvLogosBase/france-2-fr.png",
            "arte"          to "$tvLogosBase/arte-fr.png",
            "otf"           to "$tvLogosBase/canal-plus-fr.png",  // placeholder bouquet payant
            "francetv_box"  to "$tvLogosBase/france-2-fr.png",
            // 2026-06-24 : FAST channel folder artwork
            "samsung_tvplus" to "https://stg-images.samsung.com/is/image/samsung/p5/ca/tvs/tvplus/Samsung_TVPlus_Logo_500x500.jpg",
            "pluto_tv"       to "https://i.ibb.co/N6Nr80wR/Pluto-TV-logo-2024-svg.png",
            "plex_tv"        to "https://apps.homeycdn.net/app/tv.plex/25/db367d3f-957b-43c0-83e5-1ff357748210/assets/images/large.png",
            "lg_channels"    to "https://static.wikia.nocookie.net/wiggles/images/4/4f/LGChannels.png",
            // 2026-06-27 : logo dossier Musique (note de musique).
            "musique"        to "https://cdn-icons-png.flaticon.com/512/727/727218.png",
            "stream4cf"      to "https://www.stream4free.tv/images/logos4f.png",
        )
        // 2026-06-27 (user "mets Rakuten TV, Sony One et Sport dans Autres Replays") :
        //   ces 3 dossiers ne s'affichent plus en haut du TV Hub ; ils deviennent
        //   des sous-dossiers du dialog "Autres Replays" (cf LiveHubFolderDialog).
        val nestedInAutresReplay = setOf("sport", "rakuten_tv", "sony_one")
        val folderShows = defs.mapNotNull { def ->
            if (def.key in nestedInAutresReplay) return@mapNotNull null
            val secs = folderContents[def.key]
            val totalChans = secs?.sumOf { (it.list as? List<*>)?.size ?: 0 } ?: 0
            if (secs.isNullOrEmpty() && def.key !in alwaysShowKeys) return@mapNotNull null
            val title = if (totalChans > 0)
                "📁 ${def.label} ($totalChans)"
            else
                "📁 ${def.label}"
            TvShow(
                id = "livehub::folder::${def.key}",
                title = title,
            ).apply {
                providerName = "TV Hub"
                val logo = folderArtwork[def.key]
                if (logo != null) {
                    poster = logo
                    banner = logo
                }
            }
        }
        // Reconstruit la liste des sections : on garde les sections non
        //   regroupées, puis on insère la section "📁 Dossiers" en TÊTE après
        //   Favoris (si présent).
        val kept = sections.filterNot { sectionToFolder.containsKey(it) }
        val result = mutableListOf<Category>()
        val favIdx = kept.indexOfFirst { it.name.equals("Favoris", ignoreCase = true) }
        if (favIdx >= 0) {
            result.add(kept[favIdx])
            result.add(Category(name = "📁 Dossiers", list = folderShows))
            result.addAll(kept.drop(favIdx + 1).filter { !it.name.equals("Favoris", ignoreCase = true) })
            result.addAll(kept.take(favIdx).filter { !it.name.equals("Favoris", ignoreCase = true) })
        } else {
            result.add(Category(name = "📁 Dossiers", list = folderShows))
            result.addAll(kept)
        }
        return result
    }

    /** 2026-06-08 (user "dans l'onglet toutes les chaînes qui sert à rien pour
     *  l'instant on peut afficher toutes les chaînes qu'il y a dans le home
     *  entier TV hub") : on aplatit getHome() — toutes les sections sauf les
     *  méta-sections (Favoris, Favoris France TV, ✕ Chaînes bannies). Dedup
     *  par ID, ordre préservé. */
    private suspend fun allHomeChannels(): List<TvShow> {
        val home = getHome()
        val out = mutableListOf<TvShow>()
        val seen = HashSet<String>()
        for (cat in home) {
            val n = cat.name
            if (n.equals("Favoris", ignoreCase = true)) continue
            if (n.equals("Favoris France TV", ignoreCase = true)) continue
            if (n.startsWith("✕")) continue
            (cat.list as? List<*>)?.forEach { item ->
                val tv = item as? TvShow ?: return@forEach
                // 2026-06-20 : exclure les cards d'action (login, refresh) qui
                //   ne sont pas des chaînes et n'ont pas de handler dans les grid views.
                if (tv.id.contains("__login_") || tv.id.contains("__refresh__")) return@forEach
                if (seen.add(tv.id)) out.add(tv)
            }
        }
        return out
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page > 1) return emptyList()
        return allHomeChannels()
    }

    override suspend fun search(query: String, page: Int): List<TvShow> {
        if (page > 1) return emptyList()
        val q = query.trim().lowercase()
        val all = allHomeChannels()
        if (q.isBlank()) return all
        val liveHits = all.filter { (it.title ?: "").lowercase().contains(q) }
        // 2026-07-23 (user "cale une recherche à l'ouverture du replay BFM pour
        //   retrouver une série/film rapidement — mais dans notre playlist déjà
        //   scrapée, sinon ça ne marchera pas") : la recherche couvre désormais
        //   AUSSI le catalogue REPLAY (data-replay.m3u déjà en cache disque) →
        //   TF1+, M6+, BFM/RMC, France.tv, Arte. Tout ce qui remonte est déjà
        //   jouable (on NE fait PAS de recherche RMC live = ids non résolubles).
        val replayHits = try {
            fetchReplayCategories()
                .asSequence()
                .flatMap { it.list.asSequence() }
                .filterIsInstance<TvShow>()
                .filter { (it.title ?: "").lowercase().contains(q) }
                .distinctBy { it.id }
                .take(150)
                .toList()
        } catch (e: Exception) {
            Log.w(TAG, "search: replay filter failed: ${e.message}")
            emptyList()
        }
        return (liveHits + replayHits).distinctBy { it.id }
    }

    /**
     * 2026-07-23 — BACKUP VOD par TITRE (France.tv / Arte, gratuit sans compte) depuis le catalogue
     * REPLAY DÉJÀ EN CACHE (`fetchReplayCategories`, zéro appel réseau à la recherche). Matching
     * STRICT : titre normalisé exact + (pour les séries) saison/épisode exacts → jamais le mauvais
     * contenu (« pas de serveur plutôt que le mauvais film/épisode »). Appelé par BackupRegistry.
     */
    suspend fun fetchTvHubReplayBackupServers(
        videoType: Video.Type,
        season: Int,
        episode: Int,
        titles: List<String>,
    ): List<Video.Server> {
        val wanted = titles.mapNotNull { it.trim().takeIf { t -> t.isNotBlank() }?.let(::tvhubNorm) }.toHashSet()
        if (wanted.isEmpty()) return emptyList()
        val items = try {
            fetchReplayCategories().asSequence().flatMap { it.list.asSequence() }.toList()
        } catch (e: Exception) { return emptyList() }

        for (item in items) {
            val id: String
            val title: String
            when (item) {
                is Movie -> { id = item.id; title = item.title }
                is TvShow -> { id = item.id; title = item.title }
                else -> continue
            }
            if (title.isBlank() || tvhubNorm(title) !in wanted) continue

            // TF1+ / M6+ = LOGIN REQUIS. On ne les propose qu'aux comptes CONNECTÉS (sinon serveur
            //   mort). France.tv/Arte (gratuit sans compte) passent toujours. Détection par l'id.
            val idl = id.lowercase()
            val isTf1 = listOf("replay::tf1", "replay::tmc", "replay::tfx", "replay::lci", "tf1ep::", "tf1plus")
                .any { idl.contains(it) }
            val isM6 = M6_SERVICES.any { idl.contains(it) } || idl.contains("m6live") ||
                idl.contains("m6play") || idl.contains("6play")
            if (isTf1 || isM6) {
                val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
                if (isTf1 && !com.streamflixreborn.streamflix.utils.TF1Auth.isLoggedIn(ctx)) continue
                if (isM6 && !com.streamflixreborn.streamflix.utils.M6Auth.isLoggedIn(ctx)) continue
            }

            try {
                val servers = if (videoType is Video.Type.Movie) {
                    getServers(id, videoType)
                } else {
                    if (season <= 0 || episode <= 0) continue
                    val show = getTvShow(id)
                    val seas = show.seasons.firstOrNull { it.number == season } ?: continue
                    val ep = getEpisodesBySeason(seas.id).firstOrNull { it.number == episode } ?: continue
                    getServers(ep.id, videoType)
                }
                if (servers.isNotEmpty()) {
                    Log.i(TAG, "TVHub replay backup: '$title' → ${servers.size} serveurs")
                    return servers.map {
                        Video.Server(id = it.id, name = "TV Hub · ${it.name}", src = it.src, mirrors = it.mirrors)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TVHub backup resolve KO for '$title': ${e.message}")
            }
        }
        return emptyList()
    }

    private fun tvhubNorm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "")

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getMovie(id: String): Movie =
        throw UnsupportedOperationException("LiveTvHub n'a pas de films")

    override suspend fun getTvShow(id: String): TvShow {
        // 2026-06-19 (user "OTF et Adrar ne marchent pas au clic dossier") :
        //   pour les IDs `livehub::folder::*` (= cards dossier), retourne un
        //   TvShow synthétique sans saisons. Évite IllegalArgumentException
        //   "Channel inconnue" qui était thrown par ArtworkRepair sur chaque
        //   card dossier au rendu.
        if (id.startsWith("livehub::folder::")) {
            val key = id.removePrefix("livehub::folder::")
            return TvShow(id = id, title = "📁 $key").apply {
                providerName = "TV Hub"
            }
        }
        // 2026-06-18 v24 : REPLAY TF1+ programme → fetch les épisodes via TF1+ HTML
        //   Pipeline :
        //   1. fetch https://www.tf1.fr/<show-path>  (ex: tf1/good-american-family)
        //   2. trouve tous les liens .../videos/<slug>-<id>.html (= chaque épisode)
        //   3. extrait pour chaque : titre, S0xE0x, poster
        //   4. retourne TvShow avec 1 saison "Tous les épisodes"
        // 2026-06-20 : étendu aux 5 chaînes TF1+ (tf1, tmc, tfx, tf1-series-films, lci)
        val tf1PlusChans = listOf("tf1/", "tmc/", "tfx/", "tf1-series-films/", "lci/")
        if (tf1PlusChans.any { id.startsWith("livehub::replay::$it") }) {
            val showPath = id.removePrefix("livehub::replay::")
            return buildTf1ReplayShow(id, showPath)
        }
        // 2026-06-19 : REPLAY M6+ programme → fetch les videos via pc.middleware
        //   /programs/{id}/videos, parse les titres "S\d+ E\d+ - title" pour
        //   grouper en saisons.
        if (M6_SERVICES.any { id.startsWith("livehub::replay::$it/") }) {
            val m6Path = id.removePrefix("livehub::replay::")
            return buildM6ReplayShow(id, m6Path)
        }
        val bonusShow = when {
            id.startsWith("livehub::bonus::") -> {
                val num = id.removePrefix("livehub::bonus::").toIntOrNull()
                bonusChannels.firstOrNull { it.id == num }?.let { bonusToTvShow(it) }
            }
            id.startsWith("livehub::dailymotion::") -> dailymotionChannelToTvShow()
            id.startsWith("livehub::freeshot::") -> {
                // freeshot.live retired (all 48 channels CF-blocked); kept for old favorites compat
                null
            }
            // 2026-06-04 (user "OTF TV coupe sur téléphone") : avant, on tombait
            //   ligne 646 sur `channelById[id]` (keyed sur livehub::<witvKey>) qui
            //   n'a JAMAIS les ids `livehub::otf::<key>` → throw IllegalArgument
            //   "Channel inconnue" → la fiche détaillée échouait, le mini-player
            //   ne pouvait pas init proprement. Maintenant : on récupère la
            //   chaîne directement depuis le catalogue OTF.
            id.startsWith("livehub::otf::") -> {
                val key = id.removePrefix("livehub::otf::").substringBefore("::")
                val otfChannels = try {
                    com.streamflixreborn.streamflix.utils.OtfTvService.fetchChannels()
                } catch (e: Exception) {
                    Log.w(TAG, "OTF fetchChannels failed in getTvShow($id): ${e.message}")
                    emptyList()
                }
                otfChannels.firstOrNull { it.normalizedKey == key }?.let { ch ->
                    TvShow(id = id, title = ch.name).apply {
                        providerName = "TV Hub"
                        poster = ch.logo
                    }
                }
            }
            // 2026-06-08 : France TV (BoxXtemus) — strip prefix + délègue
            id.startsWith("livehub::francetv::") -> {
                val nativeId = id.removePrefix("livehub::francetv::")
                try {
                    val tv = com.streamflixreborn.streamflix.providers
                        .BoxXtemusProvider.getTvShow(nativeId)
                    // Réémet avec l'ID Hub + saison "En Direct" (cf bonusShow)
                    tv.copy(id = id).apply { providerName = "TV Hub" }
                } catch (e: Exception) {
                    Log.w(TAG, "France TV getTvShow failed for $id: ${e.message}")
                    null
                }
            }
            // 2026-06-08 : Dric4rTV — délègue
            id.startsWith("livehub::dric4rtv::") -> {
                try {
                    val tv = com.streamflixreborn.streamflix.providers
                        .Dric4rTvProvider.getTvShow(id)
                    tv.apply { providerName = "TV Hub" }
                } catch (e: Exception) {
                    Log.w(TAG, "Dric4rTV getTvShow failed for $id: ${e.message}")
                    null
                }
            }
            // 2026-06-08 : World Live TV — délègue, providerName "TV Hub"
            //   pour partager les réglages (favoris/bans).
            id.startsWith("livehub::worldlivetv::") -> {
                val nativeId = id.removePrefix("livehub::worldlivetv::")
                try {
                    val tv = com.streamflixreborn.streamflix.providers
                        .WorldLiveTvProvider.getTvShow(nativeId)
                    tv.copy(id = id).apply { providerName = "TV Hub" }
                } catch (e: Exception) {
                    Log.w(TAG, "World Live TV getTvShow failed for $id: ${e.message}")
                    null
                }
            }
            else -> null
        }
        if (bonusShow != null) {
            return bonusShow.copy(
                seasons = listOf(
                    Season(id = id, number = 1, title = "En Direct",
                        episodes = listOf(Episode(id = id, number = 1,
                            title = "Regarder en Direct", poster = bonusShow.poster)))
                )
            )
        }

        // 2026-06-20 v7 (user "quand on clique sur une série replay ça affiche
        //   pas le menu synopsis avec les saisons et les épisodes") :
        //   Avant le fallback générique "En Direct", on détecte les SÉRIES
        //   TF1+/M6+ via replayProgramSrcs et on build le vrai show avec
        //   saisons/épisodes depuis le site web.
        if (id.startsWith("livehub::replay::") &&
            !id.startsWith("livehub::replay::tf1live::") &&
            !id.startsWith("livehub::replay::m6live::") &&
            !id.startsWith("livehub::replay::tf1ep::") &&
            !id.startsWith("livehub::replay::m6ep::") &&
            !id.startsWith("livehub::replay::bfmlive::") &&
            !id.startsWith("livehub::replay::bfmep::") &&
            !id.startsWith("livehub::replay::ftvep::") &&
            !id.startsWith("livehub::replay::plexep::") &&
            !id.startsWith("livehub::replay::plutoep::") &&
            !id.startsWith("livehub::replay::__")) {
            val siId = id.removePrefix("livehub::replay::")
            val src = replayProgramSrcs[siId]
            if (src != null) {
                try {
                    if (src.startsWith("tf1plus://")) {
                        val showPath = src.removePrefix("tf1plus://")
                        Log.d(TAG, "getTvShow: replay TF1+ série détectée, buildTf1ReplayShow($showPath)")
                        return buildTf1ReplayShow(id, showPath)
                    }
                    if (src.startsWith("m6play://")) {
                        val m6Path = src.removePrefix("m6play://")
                        Log.d(TAG, "getTvShow: replay M6+ série détectée, buildM6ReplayShow($m6Path)")
                        return buildM6ReplayShow(id, m6Path)
                    }
                    // 2026-06-21 : BFM Play replay série → fetch épisodes via API BFM
                    if (src.startsWith("bfmplay://")) {
                        val bfmProductId = src.removePrefix("bfmplay://")
                        Log.d(TAG, "getTvShow: replay BFM série détectée, buildBfmReplayShow($bfmProductId)")
                        return buildBfmReplayShow(id, bfmProductId)
                    }
                    // 2026-06-26 : France TV programme (série / dessin animé Okoo)
                    //   → fetch les épisodes via /apps/program/<path>.
                    if (src.startsWith("francetv://program/")) {
                        val ftvPath = src.removePrefix("francetv://program/")
                        Log.d(TAG, "getTvShow: replay France TV série détectée, buildFrancetvReplayShow($ftvPath)")
                        return buildFrancetvReplayShow(id, ftvPath)
                    }
                    // 2026-06-27 : série Plex on-demand → saisons/épisodes.
                    if (src.startsWith("plexshow://")) {
                        val plexRk = src.removePrefix("plexshow://")
                        Log.d(TAG, "getTvShow: série Plex détectée, buildPlexReplayShow($plexRk)")
                        return buildPlexReplayShow(id, plexRk)
                    }
                    // 2026-06-27 : série Pluto on-demand → saisons/épisodes.
                    if (src.startsWith("plutoshow://")) {
                        val plutoId = src.removePrefix("plutoshow://")
                        Log.d(TAG, "getTvShow: série Pluto détectée, buildPlutoReplayShow($plutoId)")
                        return buildPlutoReplayShow(id, plutoId)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getTvShow: build replay show failed for $id: ${e.message}")
                    // Fallback vers le générique ci-dessous
                }
            }
        }
        // 2026-06-24 : FAST channel (= Samsung TV+, Pluto TV, Plex TV, LG Channels, etc.)
        //   ID = "livehub::fast::<hash>" → TvShow synthétique "En Direct".
        if (id.startsWith("livehub::fast::")) {
            val title = fastChannelNames[id] ?: homeChannelsCache.firstOrNull { it.first == id }?.second
                ?: id.removePrefix("livehub::fast::").replace("-", " ")
            val logo = fastChannelLogos[id] ?: ""
            return TvShow(id = id, title = title).apply {
                providerName = "TV Hub"
                poster = logo
                banner = logo
            }.copy(
                seasons = listOf(
                    Season(id = id, number = 1, title = "En Direct",
                        episodes = listOf(Episode(id = id, number = 1,
                            title = "Regarder en Direct", poster = logo)))
                )
            )
        }
        // 2026-06-19 v40 (user "Le mini player mouline") : pour les IDs
        //   `livehub::replay::*` qui représentent UN LIVE DIRECT (TMC, M6Live, etc.
        //   PAS un episode replay) → fallback synthétique. Sans ça getTvShow
        //   throw IllegalArgumentException → EpisodeManager.addEpisodesFromDb
        //   catch pas → mini-player stuck en spinning.
        if (id.startsWith("livehub::replay::")) {
            // Titre best-effort : cache home OU dernier segment de l'ID
            val title = homeChannelsCache.firstOrNull { it.first == id }?.second
                ?: id.removePrefix("livehub::replay::").substringAfterLast("/")
                    .replace("-", " ").replaceFirstChar { it.uppercase() }
            return TvShow(id = id, title = title).apply {
                providerName = "TV Hub"
            }.copy(
                seasons = listOf(
                    Season(id = id, number = 1, title = "En Direct",
                        episodes = listOf(Episode(id = id, number = 1,
                            title = "Regarder en Direct", poster = null)))
                )
            )
        }
        // 2026-07-10 (user "supprime LumiChat partout") : branche LumiChat lumimulti RETIRÉE.
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

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // v28 : TF1+ replay → retourne les épisodes depuis le cache buildTf1ReplayShow
        if (seasonId.startsWith("livehub::replay::tf1season::")) {
            return tf1SeasonEpisodesCache[seasonId] ?: run {
                // Cache miss : extrait showPath du seasonId pour re-build
                // Format : livehub::replay::tf1season::<showPath>::<seasonNum>
                val payload = seasonId.removePrefix("livehub::replay::tf1season::")
                val showPath = payload.substringBeforeLast("::")
                val seasonNum = payload.substringAfterLast("::").toIntOrNull() ?: 1
                try {
                    buildTf1ReplayShow("livehub::replay::$showPath", showPath)
                    tf1SeasonEpisodesCache[seasonId] ?: emptyList()
                } catch (e: Exception) {
                    Log.w(TAG, "getEpisodesBySeason rebuild fail: ${e.message}")
                    emptyList()
                }
            }
        }
        // 2026-06-19 : M6+ replay → cache m6SeasonEpisodesCache
        if (seasonId.startsWith("livehub::replay::m6season::")) {
            return m6SeasonEpisodesCache[seasonId] ?: run {
                // Cache miss : Format livehub::replay::m6season::<service>::<program_id>::<seasonNum>
                val payload = seasonId.removePrefix("livehub::replay::m6season::")
                val parts = payload.split("::")
                if (parts.size >= 2) {
                    val service = parts[0]
                    val programId = parts[1]
                    try {
                        buildM6ReplayShow("livehub::replay::$service/$programId", "$service/$programId")
                        m6SeasonEpisodesCache[seasonId] ?: emptyList()
                    } catch (e: Exception) {
                        Log.w(TAG, "M6 getEpisodesBySeason rebuild fail: ${e.message}")
                        emptyList()
                    }
                } else emptyList()
            }
        }
        // 2026-06-26 : France TV (séries Okoo / replay programmes) → cache
        //   ftvSeasonEpisodesCache, rebuild via buildFrancetvReplayShow si miss.
        //   seasonId = "livehub::replay::program/<path>::s<num>".
        if (seasonId.startsWith("livehub::replay::program/")) {
            return ftvSeasonEpisodesCache[seasonId] ?: run {
                val showId = seasonId.substringBeforeLast("::s")
                val programPath = showId.removePrefix("livehub::replay::program/")
                try {
                    buildFrancetvReplayShow(showId, programPath)
                    ftvSeasonEpisodesCache[seasonId] ?: emptyList()
                } catch (e: Exception) {
                    Log.w(TAG, "FTV getEpisodesBySeason rebuild fail: ${e.message}")
                    emptyList()
                }
            }
        }
        // 2026-06-27 : série Plex → cache plexSeasonEpisodesCache, rebuild via
        //   buildPlexReplayShow si miss. seasonId = "livehub::replay::plexshow::<rk>::s<num>".
        if (seasonId.startsWith("livehub::replay::plexshow::")) {
            return plexSeasonEpisodesCache[seasonId] ?: run {
                val showId = seasonId.substringBeforeLast("::s")
                val showRk = showId.removePrefix("livehub::replay::plexshow::")
                try {
                    buildPlexReplayShow(showId, showRk)
                    plexSeasonEpisodesCache[seasonId] ?: emptyList()
                } catch (e: Exception) {
                    Log.w(TAG, "Plex getEpisodesBySeason rebuild fail: ${e.message}")
                    emptyList()
                }
            }
        }
        // 2026-06-27 : série Pluto → cache plutoSeasonEpisodesCache.
        if (seasonId.startsWith("livehub::replay::plutoshow::")) {
            return plutoSeasonEpisodesCache[seasonId] ?: run {
                val showId = seasonId.substringBeforeLast("::s")
                val seriesId = showId.removePrefix("livehub::replay::plutoshow::")
                try {
                    buildPlutoReplayShow(showId, seriesId)
                    plutoSeasonEpisodesCache[seasonId] ?: emptyList()
                } catch (e: Exception) {
                    Log.w(TAG, "Pluto getEpisodesBySeason rebuild fail: ${e.message}")
                    emptyList()
                }
            }
        }
        return emptyList()
    }

    // v28 : cache pour retrouver les épisodes par seasonId (= rebuild évité)
    private val tf1SeasonEpisodesCache = java.util.concurrent.ConcurrentHashMap<String, List<Episode>>()
    // 2026-06-19 : cache équivalent pour M6+
    private val m6SeasonEpisodesCache = java.util.concurrent.ConcurrentHashMap<String, List<Episode>>()
    // 2026-06-26 : cache équivalent pour France TV (séries Okoo / replay programmes)
    private val ftvSeasonEpisodesCache = java.util.concurrent.ConcurrentHashMap<String, List<Episode>>()

    // ====== 2026-06-19 : M6+ replay show parser ======
    private val M6_SERVICES = listOf(
        "m6replay", "w9replay", "6terreplay", "gulli", "tevareplay", "parispremierereplay"
    )

    /** Fetch /programs/<id>/videos via pc.middleware.6play.fr puis parse les
     *  titles "S\d+ E\d+ - title" pour grouper en saisons. */
    private suspend fun buildM6ReplayShow(showId: String, m6Path: String): TvShow {
        // m6Path vient de l'URL m6play://<service>/<program_id>
        //   ex: "m6replay/28326", "w9replay/18441", "6terreplay/25219"
        val service = m6Path.substringBefore("/")
        val programId = m6Path.substringAfter("/")
        val videosUrl = "https://pc.middleware.6play.fr/6play/v2/platforms/m6group_web/services/$service/programs/$programId/videos?csa=6&with=clips,freemiumpacks&type=vi,vc,playlist&limit=100&offset=0"
        val videosJson = try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val req = okhttp3.Request.Builder()
                    .url(videosUrl)
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    .newCall(req).execute().use { r -> r.body?.string().orEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "M6 replay show fetch failed for $programId: ${e.message}")
            ""
        }
        val showTitle = try {
            val arr = org.json.JSONArray(videosJson)
            if (arr.length() > 0) {
                val firstClip = arr.optJSONObject(0)?.optJSONObject("program")?.optString("title", "")
                if (!firstClip.isNullOrBlank()) firstClip else "Replay"
            } else "Replay"
        } catch (_: Throwable) { "Replay" }
        // Parse pour extraire S<num> E<num> - title
        val seasonsMap = LinkedHashMap<Int, MutableList<Episode>>()
        val flatEpisodes = mutableListOf<Episode>()
        var firstImage: String? = null
        try {
            val arr = org.json.JSONArray(videosJson)
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                val vid = v.optString("id", "")
                if (vid.isBlank()) continue
                val title = v.optString("title", "").trim()
                if (title.isBlank()) continue
                // Image preview
                val images = v.optJSONArray("images")
                val imgKey = images?.optJSONObject(0)?.optString("external_key", "") ?: ""
                val imgUrl = if (imgKey.isNotBlank()) "https://images.6play.fr/v1/images/$imgKey/raw" else null
                if (firstImage == null && imgUrl != null) firstImage = imgUrl
                // Parse "S<num> E<num> - title"
                val match = Regex("""^S(\d+)\s*E(\d+)\s*[-–]\s*(.+)$""").find(title)
                if (match != null) {
                    val sNum = match.groupValues[1].toIntOrNull() ?: 1
                    val eNum = match.groupValues[2].toIntOrNull() ?: 1
                    val epTitle = match.groupValues[3].trim()
                    val ep = Episode(
                        id = "livehub::replay::m6ep::$service::$vid",
                        number = eNum,
                        title = "S${"%02d".format(sNum)}E${"%02d".format(eNum)} — $epTitle",
                        poster = imgUrl,
                    )
                    seasonsMap.getOrPut(sNum) { mutableListOf() }.add(ep)
                } else {
                    flatEpisodes.add(Episode(
                        id = "livehub::replay::m6ep::$service::$vid",
                        number = flatEpisodes.size + 1,
                        title = title,
                        poster = imgUrl,
                    ))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "M6 replay parse fail: ${e.message}")
        }
        val seasons = if (seasonsMap.isNotEmpty()) {
            seasonsMap.entries.sortedBy { it.key }.map { (sNum, eps) ->
                Season(
                    id = "livehub::replay::m6season::$service::$programId::$sNum",
                    number = sNum,
                    title = "Saison $sNum",
                    episodes = eps.sortedByDescending { it.number },  // épisodes récents en haut
                )
            }
        } else if (flatEpisodes.isNotEmpty()) {
            listOf(Season(
                id = "livehub::replay::m6season::$service::$programId::1",
                number = 1,
                title = "Tous les épisodes",
                episodes = flatEpisodes,
            ))
        } else {
            listOf(Season(id = showId, number = 1, title = "Replay",
                episodes = listOf(Episode(id = showId, number = 1,
                    title = "Voir", poster = firstImage))))
        }
        for (s in seasons) {
            m6SeasonEpisodesCache[s.id] = s.episodes
        }
        Log.d(TAG, "buildM6ReplayShow $service/$programId: ${seasons.size} seasons, ${seasons.sumOf { it.episodes.size }} total eps")
        return TvShow(id = showId, title = showTitle, seasons = seasons).apply {
            providerName = "TV Hub"
            this.poster = firstImage
            this.banner = firstImage
        }
    }

    // ===== 2026-06-21 : BFM Play replay show parser =====
    /**
     * Fetch les épisodes d'un programme BFM via l'API Gaia-core CDN.
     * Si c'est un programme à épisodes (série, émission quotidienne) → retourne
     * un TvShow avec saison(s) et épisodes navigables.
     * Si c'est un film ou single → retourne un TvShow avec 1 épisode = lecture directe.
     */
    private suspend fun buildBfmReplayShow(showId: String, productId: String): TvShow {
        // Titre best-effort depuis le cache home
        val showTitle = homeChannelsCache.firstOrNull { it.first == showId }?.second
            ?: replayProgramTitles.entries.firstOrNull {
                replayProgramSrcs[it.key] == "bfmplay://$productId"
            }?.value ?: "BFM Replay"

        // Fetch épisodes depuis l'API BFM (avec token pour contenu non-FranceTV)
        val bfmToken = appContextRef?.let { com.streamflixreborn.streamflix.utils.BfmAuth.getToken(it) }
        val bfmEpisodes = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.streamflixreborn.streamflix.utils.BfmResolver.fetchEpisodes(productId, bfmToken)
        }

        if (bfmEpisodes.isNullOrEmpty()) {
            // Pas d'épisodes = film ou single → 1 épisode pour lecture directe
            Log.d(TAG, "buildBfmReplayShow $productId: single (no episodes), direct play")
            val singleSeason = listOf(Season(
                id = showId,
                number = 1,
                title = "Replay",
                episodes = listOf(Episode(
                    id = showId,
                    number = 1,
                    title = showTitle,
                ))
            ))
            return TvShow(id = showId, title = showTitle, seasons = singleSeason).apply {
                providerName = "TV Hub"
            }
        }

        // Construire les épisodes avec leur contentId stocké dans replayProgramSrcs
        val episodes = bfmEpisodes.mapIndexed { idx, bfmEp ->
            // Stocker le src bfmplay:// de chaque épisode pour getServers/getVideo
            val epSiId = "bfmep::${bfmEp.contentId}"
            replayProgramSrcs[epSiId] = "bfmplay://${bfmEp.contentId}"
            replayProgramTitles[epSiId] = bfmEp.title

            Episode(
                id = "livehub::replay::$epSiId",
                number = idx + 1,
                title = bfmEp.title,
                poster = bfmEp.poster,
            ).also {
                it.overview = bfmEp.description
            }
        }

        val seasons = listOf(Season(
            id = "${showId}::s1",
            number = 1,
            title = "Tous les épisodes",
            episodes = episodes,
        ))

        Log.d(TAG, "buildBfmReplayShow $productId: ${episodes.size} episodes found")
        return TvShow(id = showId, title = showTitle, seasons = seasons).apply {
            providerName = "TV Hub"
            this.poster = bfmEpisodes.firstOrNull()?.poster
        }
    }

    // ===== 2026-06-26 : France TV (Okoo / replay) programme -> episodes =====
    /** Fetch les episodes d'un programme France TV via l'API yatta
     *  /apps/program/<path> (collection playlist_video). Construit un TvShow
     *  avec saison(s) + episodes navigables. Chaque episode est stocke comme
     *  francetv://<si_id> dans replayProgramSrcs (resolu par le pipeline France
     *  TV existant, comme une video unitaire). Miroir de buildBfmReplayShow. */
    private suspend fun buildFrancetvReplayShow(showId: String, programPath: String): TvShow {
        val fallbackTitle = homeChannelsCache.firstOrNull { it.first == showId }?.second
            ?: replayProgramTitles[showId.removePrefix("livehub::replay::")]
            ?: programPath.substringAfterLast("_").replace("-", " ")
                .replaceFirstChar { it.uppercase() }
        val url = "https://api-mobile.yatta.francetv.fr/apps/program/$programPath?platform=apps"
        val json = try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    .newCall(req).execute().use { r -> r.body?.string().orEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTV program fetch failed $programPath: ${e.message}")
            ""
        }
        val root = try { org.json.JSONObject(json) } catch (e: Exception) { null }
        val showTitle = root?.optString("label")?.takeIf { it.isNotBlank() } ?: fallbackTitle
        // Collecte les episodes des collections playlist_video
        data class FtvEp(val si: String, val title: String, val season: Int,
                         val episode: Int, val poster: String?, val overview: String?)
        val eps = mutableListOf<FtvEp>()
        val collections = root?.optJSONArray("collections")
        if (collections != null) {
            for (i in 0 until collections.length()) {
                val coll = collections.optJSONObject(i) ?: continue
                if (coll.optString("type") != "playlist_video") continue
                val items = coll.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val it = items.optJSONObject(j) ?: continue
                    val si = it.optString("si_id").takeIf { s -> s.isNotBlank() } ?: continue
                    val epTitle = it.optString("episode_title")
                        .ifBlank { it.optString("title") }
                        .ifBlank { "Episode ${j + 1}" }
                    val season = it.optInt("season", 1).let { s -> if (s <= 0) 1 else s }
                    val episode = it.optInt("episode", 0).let { e -> if (e <= 0) j + 1 else e }
                    val overview = it.optString("description").takeIf { d -> d.isNotBlank() }
                    val poster = ftvEpisodeImage(it)
                    eps.add(FtvEp(si, epTitle, season, episode, poster, overview))
                }
            }
        }
        if (eps.isEmpty()) {
            // Pas d'episodes -> 1 entree lecture directe
            val single = listOf(Season(id = showId, number = 1, title = "Replay",
                episodes = listOf(Episode(id = showId, number = 1, title = showTitle))))
            return TvShow(id = showId, title = showTitle, seasons = single).apply {
                providerName = "TV Hub"
            }
        }
        val bySeason = eps.groupBy { it.season }.toSortedMap()
        val multiSeason = bySeason.size > 1
        val seasons = bySeason.map { (seasonNum, seasonEps) ->
            val episodes = seasonEps.sortedBy { it.episode }.mapIndexed { idx, ep ->
                val epSiId = "ftvep::${ep.si}"
                replayProgramSrcs[epSiId] = "francetv://${ep.si}"
                replayProgramTitles[epSiId] = ep.title
                Episode(
                    id = "livehub::replay::$epSiId",
                    number = if (ep.episode > 0) ep.episode else idx + 1,
                    title = ep.title,
                    poster = ep.poster,
                ).also { it.overview = ep.overview }
            }
            ftvSeasonEpisodesCache["$showId::s$seasonNum"] = episodes
            Season(
                id = "$showId::s$seasonNum",
                number = seasonNum,
                title = if (multiSeason) "Saison $seasonNum" else "Tous les episodes",
                episodes = episodes,
            )
        }
        Log.d(TAG, "buildFrancetvReplayShow $programPath: ${eps.size} episodes, ${seasons.size} saison(s)")
        val firstPoster = eps.firstOrNull { it.poster != null }?.poster
        return TvShow(id = showId, title = showTitle, seasons = seasons).apply {
            providerName = "TV Hub"
            if (firstPoster != null) { this.poster = firstPoster; this.banner = firstPoster }
        }
    }

    /** Extrait une URL d'image (~w:400) d'un item episode yatta. */
    private fun ftvEpisodeImage(item: org.json.JSONObject): String? {
        val imgs = item.optJSONArray("images") ?: return null
        var chosen: org.json.JSONObject? = null
        for (i in 0 until imgs.length()) {
            val im = imgs.optJSONObject(i) ?: continue
            val type = im.optString("type")
            if (type.startsWith("vignette")) { chosen = im; break }
            if (chosen == null && type.startsWith("background")) chosen = im
        }
        if (chosen == null && imgs.length() > 0) chosen = imgs.optJSONObject(0)
        val urls = chosen?.optJSONObject("urls") ?: return null
        for (k in listOf("w:400", "w:300", "w:800", "w:265", "w:1024")) {
            val u = urls.optString(k)
            if (u.isNotBlank()) return u
        }
        val keys = urls.keys()
        return if (keys.hasNext()) urls.optString(keys.next()) else null
    }

    // ===== v24 : TF1+ replay show parser =====
    /** Parse la page programme TF1+ pour extraire la liste des épisodes.
     *  URL exemple : https://www.tf1.fr/tf1/good-american-family
     *  Cherche les liens `<a href="/tf1/<show>/videos/<title-with-episodeNum>-<id>.html">`
     *  + le titre dans `<h3>` ou `aria-label` parent. */
    private suspend fun buildTf1ReplayShow(showId: String, showPath: String): TvShow {
        val pageUrl = "https://www.tf1.fr/$showPath"
        val html = try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val req = okhttp3.Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.tf1.fr/")
                    .build()
                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    .newCall(req).execute().use { r -> r.body?.string().orEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TF1 replay show fetch failed for $showPath: ${e.message}")
            ""
        }
        // Titre programme (depuis <title> ou <h1>)
        val showTitle = Regex("""<title>([^<]+)\|\s*TF1\+</title>""")
            .find(html)?.groupValues?.get(1)?.trim()
            ?: showPath.substringAfterLast("/")
                .split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        // Poster (depuis 1ère image ou meta og:image)
        val poster = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""https://photos\.tf1\.fr/[^"\s]+@3x\.(?:avif|webp|jpg)""")
                .find(html)?.value
        // Cherche tous les liens vidéo + titre + saison/épisode + image
        // Pattern : /<chan>/<show>/videos/<slug-s01-e01-...-numId>.html
        val episodes = mutableListOf<Episode>()
        val seenIds = mutableSetOf<String>()
        val videoLinkRx = Regex("""/[a-z0-9-]+/[a-z0-9-]+/videos?/([a-z0-9-]+-s(\d{1,2})-e(\d{1,3})-[a-z0-9-]+-(\d{6,14}))\.html""")
        // Cherche aussi les liens sans S0xE0x (= bonus, replay sans num saison)
        val anyVideoLinkRx = Regex("""/[a-z0-9-]+/[a-z0-9-]+/videos?/([a-z0-9-]+-(\d{6,14}))\.html""")
        // Map num → (season, episode)
        val seasonsMap = LinkedHashMap<Int, MutableList<Episode>>()
        for (m in videoLinkRx.findAll(html)) {
            val (slug, sNum, eNum, _) = m.destructured
            if (slug in seenIds) continue
            seenIds.add(slug)
            val season = sNum.toIntOrNull() ?: 1
            val episode = eNum.toIntOrNull() ?: 1
            // Titre de l'épisode = essaye d'extraire un titre lisible du slug
            val titlePart = slug.substringAfter("-s$sNum-e$eNum-")
                .replace("-", " ")
                .replaceFirstChar { it.uppercase() }
            val ep = Episode(
                id = "livehub::replay::tf1ep::$slug",
                number = episode,
                title = "S${"%02d".format(season)}E${"%02d".format(episode)} — $titlePart",
                poster = poster
            )
            seasonsMap.getOrPut(season) { mutableListOf() }.add(ep)
        }
        // Si aucune saison détectée, fallback : liste plate de vidéos
        if (seasonsMap.isEmpty()) {
            for (m in anyVideoLinkRx.findAll(html)) {
                val (slug, _) = m.destructured
                if (slug in seenIds) continue
                seenIds.add(slug)
                val titlePart = slug.split("-").dropLast(1)
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                episodes.add(Episode(
                    id = "livehub::replay::tf1ep::$slug",
                    number = episodes.size + 1,
                    title = titlePart,
                    poster = poster
                ))
            }
        }
        val seasons = if (seasonsMap.isNotEmpty()) {
            seasonsMap.entries.sortedBy { it.key }.map { (sNum, eps) ->
                Season(
                    id = "livehub::replay::tf1season::$showPath::$sNum",
                    number = sNum,
                    title = "Saison $sNum",
                    episodes = eps.sortedBy { it.number }
                )
            }
        } else if (episodes.isNotEmpty()) {
            listOf(Season(
                id = "livehub::replay::tf1season::$showPath::1",
                number = 1,
                title = "Tous les épisodes",
                episodes = episodes
            ))
        } else {
            // Pas d'épisode trouvé : 1 "Voir" qui lance le 1er flux dispo
            listOf(Season(id = showId, number = 1, title = "Replay",
                episodes = listOf(Episode(id = showId, number = 1,
                    title = "Voir", poster = poster))))
        }
        // v28 : remplit le cache pour getEpisodesBySeason
        for (s in seasons) {
            tf1SeasonEpisodesCache[s.id] = s.episodes
        }
        Log.d(TAG, "buildTf1ReplayShow $showPath: html=${html.length}, seasons=${seasonsMap.size}, flatEps=${episodes.size}, finalSeasons=${seasons.size}, total eps=${seasons.sumOf { it.episodes.size }}")
        return TvShow(id = showId, title = showTitle, seasons = seasons).apply {
            providerName = "TV Hub"
            this.poster = poster
            this.banner = poster
        }
    }

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
        // v24 : épisode replay TF1+ (= id "livehub::replay::tf1ep::<slug>")
        //   → résout via TF1Resolver avec le slug video complet, qui fait :
        //   slug → fetch page video → extrait UUID → mediainfo → délivery URL.
        if (id.startsWith("livehub::replay::tf1ep::")) {
            val slug = id.removePrefix("livehub::replay::tf1ep::")
            // On reconstruit l'URL tf1plus:// avec le path complet de la vidéo
            //   TF1Resolver gère le format "tf1plus://<chan>/<show>/videos/<slug>.html"
            //   en passant directement par resolveProgramPath qui détecte le `/videos/`.
            // On ne connaît pas le chan ni le show ici → utilise un format simplifié
            //   tf1plus://VIDEO_SLUG/<slug> qui sera traité.
            return listOf(
                Video.Server(
                    id = id,
                    name = "TF1+ replay",
                    src = "tf1plus://VIDEO/$slug",
                )
            )
        }
        // 2026-06-19 v38 : chaîne LIVE M6+ (= id "livehub::replay::m6live::<service>")
        //   → résout via M6Resolver en mode LIVE (= renvoie m6live:// pour
        //   que M6Resolver fetche le live stream m3u8 depuis 6play API).
        if (id.startsWith("livehub::replay::m6live::")) {
            val service = id.removePrefix("livehub::replay::m6live::")
            return listOf(
                Video.Server(
                    id = id,
                    name = "M6+ Live",
                    src = "m6live://$service",
                )
            )
        }
        // 2026-06-19 : épisode replay M6+ (= id "livehub::replay::m6ep::<service>::<clip_id>")
        //   → résout via M6Resolver direct sur la vidéo.
        if (id.startsWith("livehub::replay::m6ep::")) {
            val payload = id.removePrefix("livehub::replay::m6ep::")
            val service = payload.substringBefore("::")
            val videoId = payload.substringAfter("::")
            // M6Resolver gère m6play://<service>/<program_or_video_id> et le détecte
            //   via le préfixe "clip_" pour aller direct à la vidéo (= skip /programs/{id}/videos)
            return listOf(
                Video.Server(
                    id = id,
                    name = "M6+ replay",
                    src = "m6play://$service/$videoId",
                )
            )
        }
        // 2026-07-10 (user "supprime LumiChat partout") : branche LumiChat lumimulti RETIRÉE.
        // 2026-06-24 : FAST channel = id "livehub::fast::<hash>"
        //   → retourne l'URL stream directe depuis fastChannelUrls map.
        if (id.startsWith("livehub::fast::")) {
            // 2026-06-27 : si la map est vide (favori rouvert après redémarrage,
            //   sans avoir ouvert le dossier), reconstruit le src depuis l'id pour
            //   les sources résolvables (Plex/Pluto live + VOD).
            val url = fastChannelUrls[id] ?: run {
                val tail = id.removePrefix("livehub::fast::")
                when {
                    tail.startsWith("plexvod_") -> "plexvod://${tail.removePrefix("plexvod_")}"
                    tail.startsWith("plex_") -> "plex://${tail.removePrefix("plex_")}"
                    tail.startsWith("plutomovie_") -> "plutovod://m/${tail.removePrefix("plutomovie_")}"
                    tail.startsWith("plutolive_") -> "plutolive://${tail.removePrefix("plutolive_")}"
                    else -> null
                }
            // 2026-06-27 : favori fast (ex chaîne Musique) rouvert depuis les
            //   Cœurs après redémarrage → URL non reconstructible depuis l'id
            //   (hash) → on la restaure depuis le store persisté.
            } ?: restoreFastFavorite(id)
            if (url != null) {
                val name = fastChannelNames[id] ?: "FAST"
                return listOf(
                    Video.Server(
                        id = id,
                        name = name,
                        src = url,
                    )
                )
            }
            Log.w(TAG, "FAST channel URL not found for $id")
            return emptyList()
        }
        // 2026-06-17 (Phase 1 Replay) : chaîne replay = id "livehub::replay::<si_id>"
        //   → retourne le src complet stocké dans replayProgramSrcs (= peut être
        //   francetv://, arte://, tf1plus://, m6play://).
        if (id.startsWith("livehub::replay::")) {
            val siId = id.removePrefix("livehub::replay::")
            val title = replayProgramTitles[siId] ?: "Replay"
            // Fallback francetv:// pour rétrocompat si le src n'a pas été stocké
            val src = replayProgramSrcs[siId] ?: "francetv://$siId"
            return listOf(
                Video.Server(
                    id = "livehub::replay::$siId",
                    name = title,
                    src = src,
                )
            )
        }
        // 2026-06-08 : France TV (BoxXtemus) — délègue au provider natif. Le
        //   Video.Server retourné aura un id préfixé "bxt-ch::" qui sera
        //   routé dans getVideo() vers BoxXtemusProvider.getVideo().
        if (id.startsWith("livehub::francetv::")) {
            val nativeId = id.removePrefix("livehub::francetv::")
            return try {
                com.streamflixreborn.streamflix.providers
                    .BoxXtemusProvider.getServers(nativeId, videoType)
            } catch (e: Exception) {
                Log.w(TAG, "France TV getServers failed for $id: ${e.message}")
                emptyList()
            }
        }
        // 2026-06-08 : Dric4rTV — Video.Server.id sera préfixé "dric-st::"
        //   qui sera routé dans getVideo() vers Dric4rTvProvider.getVideo().
        if (id.startsWith("livehub::dric4rtv::")) {
            return try {
                com.streamflixreborn.streamflix.providers
                    .Dric4rTvProvider.getServers(id, videoType)
            } catch (e: Exception) {
                Log.w(TAG, "Dric4rTV getServers failed for $id: ${e.message}")
                emptyList()
            }
        }
        // 2026-06-08 : World Live TV — routing partagé avec LiveTvHubPlus.
        //   Les chaînes World Live TV (préfixe `livehub::worldlivetv::`)
        //   apparaissent uniquement dans le provider "World Live", mais le
        //   routing est dans LiveTvHubProvider pour que les bans/favoris
        //   fonctionnent avec le même channelKey normalisé.
        if (id.startsWith("livehub::worldlivetv::")) {
            val nativeId = id.removePrefix("livehub::worldlivetv::")
            return try {
                com.streamflixreborn.streamflix.providers
                    .WorldLiveTvProvider.getServers(nativeId, videoType)
            } catch (e: Exception) {
                Log.w(TAG, "World Live TV getServers failed for $id: ${e.message}")
                emptyList()
            }
        }

        // 2026-05-31 : OTF TV — chaînes directes depuis le catalogue OTF
        if (id.startsWith("livehub::otf::")) {
            val key = id.removePrefix("livehub::otf::").split("::").first()
            val urls = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.streamflixreborn.streamflix.utils.OtfTvService.getUrlsForChannel(key)
            }
            Log.d(TAG, "OTF getServers: key=$key, urls=${urls.size}")
            // Debug nettoyé
            if (urls.isEmpty()) {
                Log.w(TAG, "OTF getServers: no URLs for key=$key")
            }
            return urls.mapIndexed { i, url ->
                Video.Server(
                    id = "livehub::otf::${key}::$i",
                    name = "OTF #${i + 1}",
                    src = url,
                )
            }
        }
        // 2026-05-15 : bonus channels — 3 mirrors par chaîne pour bolaloca
        if (id.startsWith("livehub::bonus::")) {
            val num = id.removePrefix("livehub::bonus::").toIntOrNull() ?: return emptyList()
            val name = bonusChannels.firstOrNull { it.id == num }?.displayName ?: "Chaîne $num"
            return listOf(
                Video.Server(
                    id = "livehub::bonus::$num::bolaloca",
                    name = "bolaloca",
                    src = "https://bolaloca.my/player/2/$num",
                ),
                Video.Server(
                    id = "livehub::bonus::$num::cartelive",
                    name = "cartelive",
                    src = "https://cartelive.club/player/2/$num",
                ),
                Video.Server(
                    id = "livehub::bonus::$num::embedme",
                    name = "embedme",
                    src = "https://embedme.click/player/2/$num",
                ),
            )
        }
        // Dailymotion Sport en France
        if (id.startsWith("livehub::dailymotion::")) {
            val dmId = id.removePrefix("livehub::dailymotion::")
            return listOf(
                Video.Server(
                    id = "livehub::dailymotion::$dmId",
                    name = "Dailymotion",
                    src = "https://geo.dailymotion.com/player.html?video=$dmId",
                ),
            )
        }
        // 2026-05-15 : Freeshot channels — 1 server par chaîne, l'URL pointe
        // vers la page freeshot et FreeshotExtractor (WebView) suit le chain
        // d'iframes pour récupérer le m3u8.
        // Note 2026-05-15 : freeshot.live est bloqué par Cloudflare (ERR_CONNECTION_CLOSED
        // côté WebView, SSLHandshakeException côté OkHttp). Source non fiable.
        if (id.startsWith("livehub::freeshot::")) {
            val parts = id.removePrefix("livehub::freeshot::").split("::")
            if (parts.size != 2) return emptyList()
            val (fsId, slug) = parts
            return listOf(
                Video.Server(
                    id = "livehub::freeshot::$fsId::$slug",
                    name = "Freeshot",
                    src = "https://www.freeshot.live/live-tv/$slug/$fsId",
                ),
            )
        }

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

            // 2026-05-31 : OTF TV — URLs m3u8 directes (pas de portail MAC)
            val otfD = async {
                runCatching {
                    withTimeoutOrNull(8_000L) {
                        val urls = com.streamflixreborn.streamflix.utils.OtfTvService.getUrlsForChannel(c.olaVegetaKey)
                        urls.mapIndexed { i, url ->
                            Video.Server(
                                id = "livehub::otf::${c.olaVegetaKey}::$i",
                                name = "OTF #${i + 1}",
                                src = url,
                            )
                        }
                    } ?: emptyList()
                }.getOrElse { emptyList() }
            }

            val all = olaD.await() + vegetaD.await() + otfD.await()
            val seenSrc = HashSet<String>()
            val deduped = all.filter { srv -> seenSrc.add(srv.src.ifBlank { srv.id }) }

            Log.d(TAG, "getServers '${c.displayName}' → ${deduped.size} (fallback agrégé OLA+Vegeta+OTF)")
            deduped
        }
    }

    /** getVideo : délègue au provider d'origine selon le prefix de l'id.
     *  Couvre tous les providers IPTV via IptvCrossDelegate. */
    override suspend fun getVideo(server: Video.Server): Video {
        // 2026-07-10 (user : « le CF ne se résout pas au boot ») : VOIE FIABLE Stream4Free = les liens
        //   `data-stream.top` DÉJÀ RÉSOLUS (poussés sur git par le scraper serveur, token stable, SANS
        //   CF). On les joue DIRECT avec le Referer stream4free obligatoire → zéro CF côté app.
        if (server.src.contains("data-stream.top", ignoreCase = true)) {
            return Video(
                source = server.src,
                headers = mapOf(
                    "User-Agent" to com.streamflixreborn.streamflix.extractors.Extractor.DEFAULT_USER_AGENT,
                    "Referer" to "https://www.stream4free.tv/",
                    "Origin" to "https://www.stream4free.tv",
                ),
                type = "application/vnd.apple.mpegurl",
            )
        }
        // REPLI (git injoignable) : chaînes de la liste EN DUR en `stream4cf://` → résolues via le
        //   bypass WebView CF (cache 24h + auto-réparation). Marche seulement si le CF passe.
        if (com.streamflixreborn.streamflix.utils.Stream4FreeResolverCfTest.isCfUrl(server.src)) {
            // userInitiated=true : le user a cliqué sur cette chaîne → QR autorisé (quand réactivé)
            return com.streamflixreborn.streamflix.utils.Stream4FreeResolverCfTest.resolve(server.src, userInitiated = true)
                ?: throw Exception("Stream4Free resolver failed for ${server.src}")
        }
        // 2026-07-10 (user "supprime LumiChat partout") : résolveur LumiChat RETIRÉ.
        // 2026-06-26 : Plex TV (FAST) → PlexTvResolver (token anonyme + master m3u8).
        //   Le scraper émet `plex://<channelId>` (stable, auto-refresh), résolu
        //   frais à la lecture → jamais d'URL périmée.
        if (com.streamflixreborn.streamflix.utils.PlexTvResolver.isPlexUrl(server.src)) {
            return com.streamflixreborn.streamflix.utils.PlexTvResolver.resolve(server.src)
                ?: throw Exception("Plex resolver failed for ${server.src}")
        }
        // 2026-06-27 : Plex VOD (À la demande) → resolveVod (HLS VOD, seek OK).
        //   src `plexvod://<metadataId>` → fetch /library/metadata → Part hls.
        if (com.streamflixreborn.streamflix.utils.PlexTvResolver.isPlexVodUrl(server.src)) {
            return com.streamflixreborn.streamflix.utils.PlexTvResolver.resolveVod(server.src)
                ?: throw Exception("Plex VOD resolver failed for ${server.src}")
        }
        // 2026-06-27 : Pluto TV — live + VOD via API officielle (boot géo-FR).
        if (com.streamflixreborn.streamflix.utils.PlutoTvResolver.isPlutoLiveUrl(server.src)) {
            return com.streamflixreborn.streamflix.utils.PlutoTvResolver.resolveLive(server.src)
                ?: throw Exception("Pluto live resolver failed for ${server.src}")
        }
        if (com.streamflixreborn.streamflix.utils.PlutoTvResolver.isPlutoVodUrl(server.src)) {
            return com.streamflixreborn.streamflix.utils.PlutoTvResolver.resolveVod(server.src)
                ?: throw Exception("Pluto VOD resolver failed for ${server.src}")
        }
        // 2026-06-21 : BFM Play replay/live → BfmResolver
        if (server.src.startsWith("bfmplay://") || server.src.startsWith("bfmlive://")) {
            val resolved = com.streamflixreborn.streamflix.utils.BfmResolver.resolveTyped(server.src)
                ?: throw Exception("BFM resolver failed for ${server.src}")
            return Video(
                source = resolved.url,
                type = resolved.mimeType,
            )
        }
        // 2026-07-24 : Brightcove Live Widevine (Télé-Québec / Jeunesse) →
        //   BrightcoveResolver. src = brightcove://<acct>/<player>/<video>.
        //   Renvoie une source DASH ; la licence Widevine est mise en cache par
        //   le resolver et lue par le player (getWidevineLicenseUrl).
        if (com.streamflixreborn.streamflix.utils.BrightcoveResolver.isBrightcoveUrl(server.src)) {
            val resolved = com.streamflixreborn.streamflix.utils.BrightcoveResolver.resolveTyped(server.src)
                ?: throw Exception("Brightcove resolver failed for ${server.src}")
            return Video(
                source = resolved.url,
                type = resolved.mimeType,
            )
        }
        // 2026-06-17 (Phase 1 Replay) : URL francetv://<si_id> → FrancetvResolver
        if (server.id.startsWith("livehub::replay::")) {
            return com.streamflixreborn.streamflix.providers
                .BoxXtemusProvider.resolveExternalChannel(
                    streamUrl = server.src,
                    customUa = "Mozilla/5.0",
                    customReferer = "https://www.france.tv/",
                    channelName = server.name,
                    channelKey = server.id,
                )
        }
        // 2026-06-08 : France TV (BoxXtemus) — server.id préfixé "bxt-ch::"
        //   par BoxXtemusProvider.getServers. Pipeline résolution :
        //   RSS feed, ftven auth, anti-bot HTML — tout est encapsulé dans
        //   BoxXtemusProvider.getVideo. On délègue tel quel.
        if (server.id.startsWith("bxt-ch::")) {
            return com.streamflixreborn.streamflix.providers
                .BoxXtemusProvider.getVideo(server)
        }
        // 2026-06-08 : Dric4rTV — délègue, URL directe + headers du JSON
        if (server.id.startsWith("dric-st::")) {
            return com.streamflixreborn.streamflix.providers
                .Dric4rTvProvider.getVideo(server)
        }
        // 2026-06-08 : World Live TV — délègue. URL directe + headers UA/Ref
        //   capturés du M3U (#EXTVLCOPT).
        if (server.id.startsWith("wltv-ch::")) {
            return com.streamflixreborn.streamflix.providers
                .WorldLiveTvProvider.getVideo(server)
        }

        // 2026-05-15 : bonus channels — délègue à Extractor.extract qui route
        // vers Hoca8Extractor (bolaloca/cartelive/embedme) ou DailymotionExtractor.
        // 2026-05-31 : OTF TV — URLs directes m3u8, pas d'extraction
        if (server.id.startsWith("livehub::otf::")) {
            // 2026-05-31 : User-Agent = celui d'ExoPlayer 2.19.1 (l'app OTF originale).
            // Le CDN stm.linkip.org accepte ce UA sans couper après 60s.
            return Video(
                source = server.src,
                subtitles = emptyList(),
                type = "application/vnd.apple.mpegurl",
                headers = mapOf(
                    "User-Agent" to "ExoPlayerLib/2.19.1",
                ),
            )
        }
        // 2026-06-24 : FAST channels — résolution des URLs html.bet + direct.
        //   Samsung TV+ (html.bet/?jmp2.uk/stvp-X) : 302 redirect → amagi.tv m3u8
        //   Pluto TV (html.bet/?<hexId>#) : boot API → JWT → stream URL
        //   Autres : URL directe m3u8/ts → ExoPlayer direct
        if (server.id.startsWith("livehub::fast::")) {
            val src = server.src
            val channelName = fastChannelNames[server.id] ?: "FAST"
            Log.w(TAG, "FAST getVideo: $channelName src=${src.take(100)}")

            // ── html.bet wrapper → résolution directe ──
            if (src.contains("html.bet", ignoreCase = true)) {
                val betIdx = src.indexOf("html.bet/?")
                if (betIdx >= 0) {
                    val inner = src.substring(betIdx + 10).trimEnd('#', ' ')
                    Log.w(TAG, "FAST inner='$inner' (len=${inner.length})")

                    // Samsung TV+ : jmp2.uk/stvp-<ID> → 302 → m3u8
                    if (inner.contains("jmp2.uk/stvp-", ignoreCase = true)) {
                        Log.w(TAG, "FAST Samsung TV+ resolve: $channelName")
                        val m3u8 = resolveSamsungRedirect(inner)
                        val mime = if (m3u8.contains(".mpd", ignoreCase = true))
                            "application/dash+xml" else "application/x-mpegURL"
                        return Video(
                            source = m3u8, subtitles = emptyList(), type = mime,
                            headers = mapOf("User-Agent" to CHROME_UA),
                        )
                    }

                    // Pluto TV : hex channel ID → boot API → JWT → stream
                    if (inner.matches(Regex("[0-9a-fA-F]{20,}"))) {
                        Log.w(TAG, "FAST Pluto TV resolve: $channelName (id=$inner)")
                        try {
                            val streamUrl = resolvePlutoStream(inner)
                            Log.w(TAG, "FAST Pluto TV OK: ${streamUrl.take(120)}")
                            return Video(
                                source = streamUrl, subtitles = emptyList(),
                                type = "application/x-mpegURL",
                                headers = mapOf("User-Agent" to CHROME_UA),
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "FAST Pluto TV FAIL: ${e.message}", e)
                            throw e
                        }
                    }

                    // Autre inner URL : suivre les redirects
                    if (inner.startsWith("http", ignoreCase = true)) {
                        Log.w(TAG, "FAST generic redirect: $channelName")
                        val finalUrl = resolveFollowRedirects(inner)
                        val mime = if (finalUrl.contains(".mpd", ignoreCase = true))
                            "application/dash+xml" else "application/x-mpegURL"
                        return Video(
                            source = finalUrl, subtitles = emptyList(), type = mime,
                            headers = mapOf("User-Agent" to CHROME_UA),
                        )
                    }
                }
                // Fallback : pas de pattern reconnu → tenter quand même en direct
                Log.w(TAG, "FAST html.bet non reconnu: $channelName → ${src.take(80)}")
            }

            // ── URL directe (pas html.bet) : LG Channels, Sony One, Plex, Rakuten ──
            val isHls = src.contains(".m3u8", ignoreCase = true)
            // 2026-06-27 : Mix FR (data.m3u) → headers spécifiques (UA/Referer/Origin)
            //   captés des #EXTVLCOPT, sinon UA Chrome par défaut.
            val mixHeaders = fastChannelHeaders[server.id]
            val headers = if (!mixHeaders.isNullOrEmpty()) {
                if (mixHeaders.containsKey("User-Agent")) mixHeaders
                else mixHeaders + ("User-Agent" to CHROME_UA)
            } else mapOf("User-Agent" to CHROME_UA)
            return Video(
                source = src,
                subtitles = emptyList(),
                type = if (isHls) "application/vnd.apple.mpegurl" else "video/mp2t",
                headers = headers,
            )
        }
        if (server.id.startsWith("livehub::bonus::") ||
            server.id.startsWith("livehub::dailymotion::") ||
            server.id.startsWith("livehub::freeshot::")) {
            return com.streamflixreborn.streamflix.extractors.Extractor.extract(server.src, server)
        }
        return IptvCrossDelegate.delegateGetVideo(server)
            ?: throw Exception("No IPTV provider can handle server: ${server.id}")
    }

    // ──────── 2026-06-24 FAST html.bet resolvers ────────

    /** Samsung TV+ : jmp2.uk/stvp-<ID> → 302 redirect → amagi.tv m3u8 */
    private suspend fun resolveSamsungRedirect(jmpUrl: String): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val noRedirClient = okhttp3.OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            var currentUrl = jmpUrl
            for (hop in 0..5) {
                val req = okhttp3.Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", CHROME_UA)
                    .get()
                    .build()
                val resp = noRedirClient.newCall(req).execute()
                resp.use { r ->
                    val location = r.header("Location")
                    if (location != null) {
                        currentUrl = location
                        if (location.contains(".m3u8") || location.contains("amagi") || location.contains("playout")) {
                            return@withContext location
                        }
                    } else {
                        return@withContext currentUrl
                    }
                }
            }
            currentUrl
        }
    }

    /** Pluto TV : boot API → JWT + stitcher → stream URL.
     *  Le JWT contient un sessionID qu'il FAUT passer dans &sid= sinon 401.
     *  Le master.m3u8 retourne des variantes SANS jwt= dans l'URL → ExoPlayer perd le JWT.
     *  Solution : on fetch le master.m3u8 nous-mêmes, on extrait la meilleure variante,
     *  et on ajoute &jwt= à son URL pour que ExoPlayer l'utilise directement. */
    private suspend fun resolvePlutoStream(channelId: String): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val httpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val deviceId = java.util.UUID.randomUUID().toString()
            // Boot API → JWT + stitcher
            val bootUrl = "https://boot.pluto.tv/v4/start?appName=web&appVersion=9.9.0" +
                "&deviceVersion=120.0.0.0&deviceModel=web&deviceMake=Chrome&deviceType=web" +
                "&clientID=$deviceId&clientModelNumber=na&serverSideAds=false"
            Log.w(TAG, "Pluto boot API call for $channelId...")
            val bootReq = okhttp3.Request.Builder()
                .url(bootUrl)
                .header("User-Agent", CHROME_UA)
                .get()
                .build()
            val bootBody = httpClient.newCall(bootReq).execute().use { r ->
                Log.w(TAG, "Pluto boot response: ${r.code}")
                if (!r.isSuccessful) throw IllegalStateException("Pluto boot HTTP ${r.code}")
                r.body?.string() ?: throw IllegalStateException("Pluto boot body vide")
            }
            val bootJson = org.json.JSONObject(bootBody)
            val jwt = bootJson.getString("sessionToken")
            val stitcher = bootJson.getJSONObject("servers").getString("stitcher")
            // Extraire le sessionID du JWT payload (base64 partie 2)
            val sid = try {
                val parts = jwt.split(".")
                if (parts.size >= 2) {
                    var payload = parts[1]
                    val pad = (4 - payload.length % 4) % 4
                    payload += "=".repeat(pad)
                    val decoded = String(
                        android.util.Base64.decode(
                            payload.replace('-', '+').replace('_', '/'),
                            android.util.Base64.DEFAULT
                        )
                    )
                    org.json.JSONObject(decoded).optString("sessionID", deviceId)
                } else deviceId
            } catch (_: Exception) { deviceId }
            Log.w(TAG, "Pluto boot OK: stitcher=$stitcher sid=$sid")

            // Étape 2 : fetch le master.m3u8 pour extraire la variante
            val masterUrl = "$stitcher/v2/stitch/hls/channel/$channelId/master.m3u8" +
                "?advertisingId=&appName=web&appVersion=9.9.0&deviceDNT=0" +
                "&deviceId=$deviceId&deviceLat=0&deviceLon=0" +
                "&deviceMake=Chrome&deviceModel=web&deviceType=web" +
                "&deviceVersion=120.0.0.0&sid=$sid&jwt=$jwt"
            val masterReq = okhttp3.Request.Builder()
                .url(masterUrl)
                .header("User-Agent", CHROME_UA)
                .get()
                .build()
            val masterBody = httpClient.newCall(masterReq).execute().use { r ->
                Log.w(TAG, "Pluto master.m3u8 response: ${r.code}")
                if (!r.isSuccessful) throw IllegalStateException("Pluto master HTTP ${r.code}")
                r.body?.string() ?: ""
            }
            Log.w(TAG, "Pluto master.m3u8 body (${masterBody.length} chars): ${masterBody.take(300)}")

            // Parser les variantes : chercher la meilleure résolution
            // Format HLS : #EXT-X-STREAM-INF:BANDWIDTH=...,RESOLUTION=...
            //               <relative or absolute URL>
            val lines = masterBody.lines()
            var bestUrl: String? = null
            var bestBandwidth = 0L
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(line)
                        ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    // La ligne suivante est l'URL de la variante
                    val nextLine = lines.getOrNull(i + 1)?.trim() ?: continue
                    if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                        if (bw > bestBandwidth) {
                            bestBandwidth = bw
                            bestUrl = nextLine
                        }
                    }
                }
            }
            if (bestUrl == null) {
                // Pas de variante trouvée, fallback sur le master direct
                Log.w(TAG, "Pluto: pas de variante trouvée, fallback master URL")
                masterUrl
            } else {
                // Résoudre l'URL relative si besoin
                val variantFullUrl = if (bestUrl.startsWith("http")) {
                    bestUrl
                } else {
                    // Résoudre relativement au master URL
                    val baseUrl = masterUrl.substringBefore("?")
                        .substringBeforeLast("/") + "/"
                    baseUrl + bestUrl
                }
                // Ajouter &jwt= à l'URL de la variante si absent
                val finalUrl = if (variantFullUrl.contains("jwt=")) {
                    variantFullUrl
                } else {
                    val separator = if (variantFullUrl.contains("?")) "&" else "?"
                    "$variantFullUrl${separator}jwt=$jwt"
                }
                Log.w(TAG, "Pluto variante (bw=$bestBandwidth): ${finalUrl.take(150)}")
                finalUrl
            }
        }
    }

    /** URL directe : suivre les redirects HTTP et retourner l'URL finale */
    private suspend fun resolveFollowRedirects(url: String): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val followClient = okhttp3.OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", CHROME_UA)
                .get()
                .build()
            followClient.newCall(req).execute().use { r ->
                r.request.url.toString()
            }
        }
    }

    // ─────────────── Channel navigation (next/previous) ───────────────
    // 2026-05-08 : boutons ⏮ ⏭ pour zapper entre chaînes dans le Hub.
    // L'ordre suit getHome (favorites uniquement, par catégorie, bannies exclues).

    // 2026-06-08 : cache populé par getHome() — agrège TOUTES les catégories
    //   du Hub (WiTV, OTF, France TV, Vavoo, …) sous forme (id, displayName).
    //   Sert à getOrderedChannelIds() + getChannelDisplayName() pour que la
    //   liste D-pad LEFT en fullscreen inclue tout, pas juste WiTV.
    @Volatile private var homeChannelsCache: List<Pair<String, String>> = emptyList()

    /** Returns la liste ordonnée des channelIds du Hub (= ordre du home,
     *  par catégorie, favorites uniquement, bannies exclues).
     *  2026-06-08 : si [currentChannelId] est fourni et appartient à un sous-groupe
     *  (France TV, OTF, …), la liste est filtrée pour ne contenir QUE ce groupe.
     *  C'est pour que le panel D-pad LEFT en fullscreen affiche les chaînes du
     *  MÊME provider que celle qu'on regarde (ex: en plein France 3, on voit
     *  les 385 chaînes France TV, pas les WiTV mixées). */
    /** 2026-06-21 (user "si je vais sur OTF il affiche que les chaînes OTF,
     *  pareil pour tous les dossiers en fait") : identifie une chaîne
     *  Replay France TV. Tout ce qui commence par "livehub::replay::"
     *  ET n'est PAS tf1/m6/bfm/__ est considéré France TV (séries, épisodes,
     *  saisons, lives — siId direct ou francetv-live::). */
    private fun isFranceTvReplayId(id: String): Boolean {
        if (!id.startsWith("livehub::replay::")) return false
        return !id.startsWith("livehub::replay::tf1") &&
               !id.startsWith("livehub::replay::m6") &&
               !id.startsWith("livehub::replay::bfm") &&
               !id.startsWith("livehub::replay::__")
    }

    /** 2026-06-27 (user "quand je lance un film en plein écran et clique sur les
     *  3 traits, ça affiche des chaînes TV au lieu des films du dossier cliqué,
     *  ex Pluto → Le meilleur du cinéma → Elektra → ≡ devrait lister ce dossier ;
     *  c'est pareil pour tous les dossiers, et pareil sur TV") :
     *  cherche l'item courant dans folderContents (= cache des dossiers TV Hub :
     *  Pluto/Plex Films+Live, replays, FAST, Mix FR...) et retourne les TvShow
     *  FRÈRES (= même catégorie du même dossier). Couvre films VOD + chaînes live.
     *  Retourne null si l'item n'est pas dans un dossier (= vrai live WiTV/Vavoo). */
    private fun folderSiblingsOf(currentId: String?): List<TvShow>? {
        if (currentId.isNullOrBlank()) return null
        for ((_, cats) in folderContents) {
            for (cat in cats) {
                val items = (cat.list as? List<*>)?.filterIsInstance<TvShow>() ?: continue
                if (items.any { it.id == currentId }) return items
            }
        }
        return null
    }

    /** Retrouve un TvShow par son id dans folderContents (pour name/poster). */
    private fun folderItemById(id: String): TvShow? {
        for ((_, cats) in folderContents) {
            for (cat in cats) {
                val m = (cat.list as? List<*>)?.filterIsInstance<TvShow>()
                    ?.firstOrNull { it.id == id }
                if (m != null) return m
            }
        }
        return null
    }

    fun getOrderedChannelIds(currentChannelId: String? = null): List<String> {
        // 2026-06-27 : si l'item courant appartient à un dossier TV Hub
        //   (folderContents), retourner les frères de SA catégorie (films du
        //   dossier "Le meilleur du cinéma", chaînes live de la catégorie, etc.)
        //   au lieu des chaînes live globales. Couvre Pluto/Plex/replay/FAST/Mix FR.
        folderSiblingsOf(currentChannelId)?.let { sib ->
            if (sib.isNotEmpty()) {
                Log.d(TAG, "getOrderedChannelIds: folder siblings (${sib.size}) for $currentChannelId")
                return sib.map { it.id }
            }
        }
        // 2026-06-08 : si le cache est dispo (= getHome a tourné au moins une
        //   fois), on retourne les chaînes du groupe courant. Sinon fallback
        //   sur l'ancien comportement WiTV-only (1er boot, getHome pas encore).
        var cache = homeChannelsCache
        // 2026-06-08 (user "quand on fait gauche y a plus rien") : si on est
        //   sur une chaîne France TV et que le cache est vide (parce que
        //   getHome n'a pas été refresh depuis cette session), on FORCE un
        //   fetch direct BoxXtemus pour ne pas afficher "Chaînes (0)". Le
        //   caller est dans Dispatchers.Default donc runBlocking est OK.
        if (cache.isEmpty() && currentChannelId?.startsWith("livehub::francetv::") == true) {
            try {
                cache = kotlinx.coroutines.runBlocking {
                    val sections = com.streamflixreborn.streamflix.providers
                        .BoxXtemusProvider.getHome()
                    val out = mutableListOf<Pair<String, String>>()
                    val seen = HashSet<String>()
                    for (cat in sections) {
                        if (cat.name.equals("Favoris", ignoreCase = true)) continue
                        (cat.list as? List<*>)?.forEach { item ->
                            val tv = item as? TvShow ?: return@forEach
                            val id = "livehub::francetv::${tv.id}"
                            if (seen.add(id)) out.add(id to tv.title.ifBlank { tv.id })
                        }
                    }
                    out
                }
                homeChannelsCache = cache
                Log.d(TAG, "homeChannelsCache populated lazy (France TV only): ${cache.size}")
            } catch (t: Throwable) {
                Log.w(TAG, "Lazy France TV channels fetch failed: ${t.message}")
            }
        }
        // 2026-06-08 (user "chaînes du bas du Hub : prev/next pas, liste vide") :
        //   lazy populate pour les chaînes Dric4rTV (= "France TV - Ligue1+/
        //   Muzik/etc."). Sans ça, l'user clique sur une chaîne Dric4rTV
        //   alors que le cache n'a pas encore été populé → fallback WiTV →
        //   0 chaîne dans le filter.
        if (cache.isEmpty() && currentChannelId?.startsWith("livehub::dric4rtv::") == true) {
            try {
                cache = kotlinx.coroutines.runBlocking {
                    val sections = com.streamflixreborn.streamflix.providers
                        .Dric4rTvProvider.getHome()
                    val out = mutableListOf<Pair<String, String>>()
                    val seen = HashSet<String>()
                    for (cat in sections) {
                        (cat.list as? List<*>)?.forEach { item ->
                            val tv = item as? TvShow ?: return@forEach
                            if (seen.add(tv.id)) out.add(tv.id to tv.title.ifBlank { tv.id })
                        }
                    }
                    out
                }
                homeChannelsCache = cache
                Log.d(TAG, "homeChannelsCache populated lazy (Dric4rTV only): ${cache.size}")
            } catch (t: Throwable) {
                Log.w(TAG, "Lazy Dric4rTV channels fetch failed: ${t.message}")
            }
        }
        if (cache.isNotEmpty()) {
            // 2026-06-21 v2 (user "sur les replay quand on fait gauche en
            //   plein écran ça affiche toutes les chaînes qui traînent dans
            //   le TV hub. Là je suis sur France TV j'ai toutes les chaînes
            //   du TV hub au lieu d'avoir que la catégorie France TV") :
            //   Refactor avec PRÉDICAT au lieu de prefix simple. Un prédicat
            //   permet de gérer le cas France TV Replay = tout
            //   "livehub::replay::*" SAUF les prefixes TF1/M6/BFM connus.
            //   Le filter accepte ainsi UN GROUPE LOGIQUE (= France TV avec
            //   chaînes principales + live + replay) au lieu de UN SEUL
            //   préfixe textuel.
            val groupPredicate: ((String) -> Boolean)? = currentChannelId?.let { id ->
                when {
                    id.startsWith("livehub::francetv::") -> {{ it: String ->
                        it.startsWith("livehub::francetv::")
                            || (it.startsWith("livehub::replay::") && isFranceTvReplayId(it))
                    }}
                    id.startsWith("livehub::otf::") -> {{ it: String -> it.startsWith("livehub::otf::") }}
                    id.startsWith("livehub::dric4rtv::") -> {{ it: String -> it.startsWith("livehub::dric4rtv::") }}
                    // BFM family (live + episode + replay)
                    id.startsWith("livehub::replay::bfm") ->
                        {{ it: String -> it.startsWith("livehub::replay::bfm") }}
                    // TF1 family (live + episode + replay)
                    id.startsWith("livehub::replay::tf1") ->
                        {{ it: String -> it.startsWith("livehub::replay::tf1") }}
                    // M6 family (live + episode + replay)
                    id.startsWith("livehub::replay::m6") ->
                        {{ it: String -> it.startsWith("livehub::replay::m6") }}
                    // France TV replay (= livehub::replay::* sauf TF1/M6/BFM) :
                    //   user est sur un show ou épisode France TV → ne montrer
                    //   QUE les autres shows/épisodes France TV.
                    id.startsWith("livehub::replay::") && isFranceTvReplayId(id) ->
                        {{ it: String -> isFranceTvReplayId(it) }}
                    else -> null
                }
            }
            return if (groupPredicate != null) {
                cache.filter { groupPredicate(it.first) }.map { it.first }
            } else {
                cache.map { it.first }
            }
        }
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

    /** Returns l'id de la chaîne avant [currentId] dans la liste ordonnée.
     *  2026-06-08 : passe currentId à getOrderedChannelIds pour filtrer par
     *  groupe (= ne pas zapper de France TV vers WiTV). */
    fun getPreviousChannelId(currentId: String): String? {
        val list = getOrderedChannelIds(currentId)
        val idx = list.indexOf(currentId)
        return if (idx > 0) list[idx - 1] else null
    }

    /** Returns l'id de la chaîne après [currentId] dans la liste ordonnée.
     *  2026-06-08 : passe currentId à getOrderedChannelIds pour filtrer par
     *  groupe (= ne pas zapper de France TV vers WiTV). */
    fun getNextChannelId(currentId: String): String? {
        val list = getOrderedChannelIds(currentId)
        val idx = list.indexOf(currentId)
        return if (idx in 0 until list.lastIndex) list[idx + 1] else null
    }

    /** Returns le displayName de la chaîne pour [channelId].
     *  2026-06-08 : consulte d'abord [homeChannelsCache] (qui contient TOUS les
     *  providers du Hub — France TV, OTF, …) sinon fallback sur channelById
     *  qui est WiTV-only. Sans ce fix, le panel D-pad LEFT en fullscreen
     *  affichait "Chaînes (0)" pour France TV (les ids livehub::francetv::*
     *  n'étaient pas dans channelById WiTV). */
    fun getChannelDisplayName(channelId: String): String? {
        val cached = homeChannelsCache.firstOrNull { it.first == channelId }?.second
        if (cached != null) return cached
        // 2026-06-21 : les replays (lazy-fetched) ne sont pas dans homeChannelsCache.
        //   Chercher dans replayCacheSections + replayProgramTitles.
        if (channelId.startsWith("livehub::replay::")) {
            val siId = channelId.removePrefix("livehub::replay::")
            val rpTitle = replayProgramTitles[siId]
            if (rpTitle != null) return rpTitle
            for (cat in replayCacheSections) {
                val match = (cat.list as? List<*>)?.filterIsInstance<TvShow>()
                    ?.firstOrNull { it.id == channelId }
                if (match != null) return match.title
            }
        }
        // 2026-06-27 : items des dossiers TV Hub (Pluto/Plex Films+Live, Mix FR,
        //   FAST...) ne sont ni dans homeChannelsCache ni dans channelById.
        folderItemById(channelId)?.let { return it.title }
        return channelById[channelId]?.displayName
    }

    /** Returns le poster (logo URL) de la chaîne pour [channelId]. */
    fun getChannelPoster(channelId: String): String? {
        // 2026-06-21 : les replays (lazy-fetched) ont leurs posters dans
        //   replayCacheSections, pas dans channelById (= WiTV-only).
        if (channelId.startsWith("livehub::replay::")) {
            for (cat in replayCacheSections) {
                val match = (cat.list as? List<*>)?.filterIsInstance<TvShow>()
                    ?.firstOrNull { it.id == channelId }
                if (match?.poster != null) return match.poster
            }
        }
        // 2026-06-27 : items des dossiers TV Hub (poster dans folderContents).
        folderItemById(channelId)?.poster?.let { return it }
        val c = channelById[channelId] ?: return null
        return logoUrlFor(c.witvKey, c.displayName)
    }

    /** 2026-05-18 v85 : LiveTvHub agrège WiTV/Ola/Vegeta, le clear est délégué à eux. */
    fun clearCache() {
        // no-op : pas de cache propre (forward via WiTV/Ola/Vegeta).
    }

    /** 2026-06-21 (user "quand je fais gauche sur un replay, ça affiche les
     *  chaînes live au lieu des autres replays de la même catégorie") :
     *  retourne les TvShow frères (= même catégorie replay) pour [currentReplayId].
     *  Lazy-fetch les catégories replay si pas encore chargées.
     *  Retourne null si l'item n'est pas un replay ou n'est pas trouvé. */
    fun getReplaySiblings(currentReplayId: String): List<TvShow>? {
        if (!currentReplayId.startsWith("livehub::replay::") || currentReplayId.contains("live::")) {
            return null
        }
        // 2026-06-27 : les séries Pluto/Plex (plutoshow::/plexshow::) sont dans
        //   folderContents, pas replayCacheSections → chercher là en priorité.
        folderSiblingsOf(currentReplayId)?.let { sib ->
            if (sib.isNotEmpty()) {
                Log.d(TAG, "getReplaySiblings: folder siblings (${sib.size}) for $currentReplayId")
                return sib
            }
        }
        var sections = replayCacheSections
        if (sections.isEmpty()) {
            try {
                sections = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    fetchReplayCategories()
                }
            } catch (_: Throwable) {
                return null
            }
        }
        for (cat in sections) {
            val items = (cat.list as? List<*>)?.filterIsInstance<TvShow>() ?: continue
            if (items.any { it.id == currentReplayId }) {
                Log.d(TAG, "getReplaySiblings: → cat '${cat.name}' (${items.size} items)")
                return items
            }
        }
        Log.w(TAG, "getReplaySiblings: '$currentReplayId' not found in ${sections.size} replay cats")
        return null
    }

    // ────────────── 2026-06-17 Phase 1 Replay (Port Catchup TV & More) ──────────────
    private const val REPLAY_M3U_URL =
        "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data-replay.m3u"
    @Volatile private var replayCacheTs: Long = 0L
    @Volatile private var replayCacheSections: List<Category> = emptyList()
    /** Map si_id → titre programme, pour réutiliser dans getServers. */
    private val replayProgramTitles: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    /** 2026-06-18 (Phase 2 — TF1+/M6) : map siId → src complet (= "francetv://..."
     *  ou "arte://..." ou "tf1plus://..." ou "m6play://..."). Permet à
     *  getServers de retourner le bon protocole pour la résolution. */
    private val replayProgramSrcs: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    // 2026-06-18 (user "Là j'ai refresh Le nouveau Les catégories
    //   n'apparaissent toujours pas dans replay") : 6h trop long → user
    //   ne voit pas les MAJ après push. Maintenant : RAM cache 30 min +
    //   re-fetch FORCÉ au cold start (warmReplayCache → forceRefresh=true).
    //   Le cache disque sert d'affichage instantané avant que le re-fetch
    //   en background ne ramène les vraies données fraîches.
    private const val REPLAY_TTL_MS = 30L * 60 * 1000  // 30 min
    /** Tag posé pendant un pre-fetch en background pour éviter qu'un appel
     *  bloquant en parallèle fasse 2 fetch simultanés. */
    @Volatile private var replayFetchInProgress: Boolean = false
    /** Fichier de cache disque (= persisté entre redémarrages app). */
    @Volatile private var replayDiskCacheFile: java.io.File? = null
    /** Application context capturé au cold start. Sert à savoir si TF1+/M6
     *  sont connectés pour décider d'afficher les cards "Se connecter". */
    @Volatile private var appContextRef: android.content.Context? = null

    /** Délégué à appeler une fois (= au cold start) pour donner accès au
     *  cacheDir Android. Stocké pour les fetchs ultérieurs. */
    fun installReplayDiskCache(cacheDir: java.io.File) {
        if (replayDiskCacheFile == null) {
            replayDiskCacheFile = java.io.File(cacheDir, "replay-m3u.cache")
        }
    }

    /** Capture l'application context pour le parser (= check login state). */
    fun installAppContext(ctx: android.content.Context) {
        if (appContextRef == null) appContextRef = ctx.applicationContext
    }

    private val replayClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** 2026-06-18 (user "TF1 en premier et dans l'ordre") : ordre canonique
     *  TNT FR. Catégories non listées sont rejetées en queue avec priority
     *  élevée (= au final). */
    private val REPLAY_CATEGORY_ORDER: Map<String, Int> = linkedMapOf(
        "Live TF1+" to 5,
        "Live M6+" to 6,
        "Live France TV" to 7,
        // TF1+ : catégories de base (= placeholders vides si M3U pas chargé)
        "Replay TF1" to 10,
        "Replay TMC" to 11,
        "Replay TFX" to 12,
        "Replay TF1 Séries Films" to 13,
        "Replay LCI" to 14,
        // 2026-06-20 v6 : sous-catégories TF1+ (format "Replay CHAN - Cat")
        //   Regroupées dans le même range 10-14 car toutes folded dans le
        //   dossier "Replay TF1+" — l'ordre interne est géré par le dialog.
        "Replay TF1 - Films" to 10,
        "Replay TF1 - Séries" to 10,
        "Replay TF1 - Téléfilms" to 10,
        "Replay TF1 - Divertissement" to 10,
        "Replay TF1 - Info" to 10,
        "Replay TF1 - Docs" to 10,
        "Replay TF1 - Sport" to 10,
        "Replay TF1 - Jeunesse" to 10,
        "Replay TF1 - Impact" to 10,
        "Replay TMC - Films" to 11,
        "Replay TMC - Séries" to 11,
        "Replay TMC - Divertissement" to 11,
        "Replay TMC - Info" to 11,
        "Replay TMC - Impact" to 11,
        "Replay TMC - Sport" to 11,
        "Replay TFX - Films" to 12,
        "Replay TFX - Séries" to 12,
        "Replay TFX - Divertissement" to 12,
        "Replay TFX - Impact" to 12,
        "Replay TFX - Docs" to 12,
        "Replay TF1 Séries Films - Films" to 13,
        "Replay TF1 Séries Films - Séries" to 13,
        "Replay TF1 Séries Films - Divertissement" to 13,
        "Replay LCI - Films" to 14,
        "Replay LCI - Info" to 14,
        "Replay LCI - Impact" to 14,
        // France TV
        "Replay France 2" to 20,
        "Replay France 3" to 21,
        "Replay France 4" to 22,
        "Replay France 5" to 23,
        "Replay Franceinfo" to 24,
        "Replay Slash" to 25,
        "Replay FranceTV Sport" to 26,
        "Replay France 24" to 27,
        "Replay France Ô" to 28,
        // M6+
        "Replay M6" to 30,
        "Replay W9" to 31,
        "Replay 6ter" to 32,
        "Replay Gulli" to 33,
        "Replay Paris Première" to 34,
        "Replay Téva" to 35,
        // BFM / RMC Play
        "Live BFM Play" to 8,
        "Replay BFM TV" to 36,
        "Replay RMC Story" to 37,
        "Replay RMC Découverte" to 38,
        "Replay BFM Business" to 39,
        "Replay RMC Life" to 39,
        // 2026-06-21 : sous-catégories BFM Play (format "Replay CHAN - Cat")
        "Replay BFM TV - Infos" to 36,
        "Replay BFM TV - Politique" to 36,
        "Replay BFM TV - Police justice" to 36,
        "Replay BFM TV - Société" to 36,
        "Replay BFM TV - Economie" to 36,
        "Replay BFM TV - International" to 36,
        "Replay BFM TV - Grand Reportage" to 36,
        "Replay BFM TV - Tous les replays" to 36,
        "Replay BFM TV - Les Grandes Interviews" to 36,
        "Replay BFM TV - Les enquêtes de la Rédac" to 36,
        "Replay BFM TV - Affaire suivante" to 36,
        "Replay BFM TV - Plus d'infos et de débats" to 36,
        "Replay RMC Story - Top tendances" to 37,
        "Replay RMC Story - Crime & Investigation" to 37,
        "Replay RMC Story - Divertissement" to 37,
        "Replay RMC Story - Cinéma & Fiction" to 37,
        "Replay RMC Story - Documentaire RMC Story" to 37,
        "Replay RMC Story - Histoire & civilisation" to 37,
        "Replay RMC Story - Mystère & Etrange" to 37,
        "Replay RMC Story - Moteur & Mécanique" to 37,
        "Replay RMC Story - Aventure & Survie" to 37,
        "Replay RMC Story - Société & Immersion" to 37,
        "Replay RMC Story - Docu-réalité" to 37,
        "Replay RMC Story - Science & Technologie" to 37,
        "Replay RMC Story - Sport & Combat" to 37,
        "Replay RMC Story - Info & Talk" to 37,
        "Replay RMC Story - Grand Reportage" to 37,
        "Replay RMC Story - En immersion" to 37,
        "Replay RMC Story - Les shows RMC radio" to 37,
        "Replay RMC Story - Ce mois-ci sur RMC Story" to 37,
        "Replay RMC Découverte - Top tendances" to 38,
        "Replay RMC Découverte - Science & Technologie" to 38,
        "Replay RMC Découverte - Sur le banc des accusés" to 38,
        "Replay RMC Découverte - Ce mois-ci sur RMC Découverte" to 38,
        "Replay BFM Business - Infos" to 39,
        "Replay BFM Business - Les experts de la finance" to 39,
        "Replay BFM Business - Business Actu" to 39,
        "Replay BFM Business - La sélection high tech" to 39,
        "Replay BFM Business - Les docs en plus" to 39,
        "Replay RMC Life - Top tendances" to 39,
        "Replay RMC Life - Société & Immersion" to 39,
        "Replay RMC Life - Info & Talk" to 39,
        "Replay RMC Life - Ce mois-ci sur RMC Life" to 39,
        // Arte
        "Arte Cinéma" to 40,
        "Arte Séries et fictions" to 41,
        "Arte Documentaires" to 42,
        "Arte Sciences" to 43,
        "Arte Culture et pop" to 44,
        "Arte Histoire" to 45,
        "Arte Arts" to 46,
        "Arte Info et société" to 47,
        "Arte Voyages et découvertes" to 48,
    )

    private fun parseReplayM3u(body: String): List<Category> {
        if (body.isBlank() || "#EXTM3U" !in body) return emptyList()
        val groups = LinkedHashMap<String, MutableList<TvShow>>()
        val lines = body.lines()
        var pendingExtinf: String? = null
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("#EXTINF:")) {
                pendingExtinf = t
            } else if (pendingExtinf != null &&
                       (t.startsWith("francetv://") || t.startsWith("arte://")
                        || t.startsWith("tf1plus://") || t.startsWith("tf1live://")
                        || t.startsWith("m6play://") || t.startsWith("m6live://")
                        || t.startsWith("bfmplay://") || t.startsWith("bfmlive://"))) {
                val src = t
                // 2026-06-21 : filtrer les content IDs tiers qui retournent HTTP 500
                // sur l'API SFR Gaia (chaînes nécessitant un abonnement séparé :
                // Ciné+ OCS, 01Net, L'Équipe TV, Virgin17, Universal, Kitchen Mania,
                // Ushuaïa, Film d'Afrique). Seuls les contenus BFM/RMC natifs +
                // FranceTV + Numéro23 + TV5Monde + chaînes sport fonctionnent.
                if (src.startsWith("bfmplay://")) {
                    val cid = src.substringAfter("://").trim()
                    val isBrokenThirdParty = cid.startsWith("NEUF_CINE_PLUS_OCS") ||
                        cid.startsWith("NEUF_01NET") ||
                        cid.startsWith("NEUF_LEQUIPETV") ||
                        cid.startsWith("NEUF_VIRGIN17") ||
                        cid.startsWith("NEUF_UNIVERSAL") ||
                        cid.startsWith("NEUF_KITCHEN_MANIA") ||
                        cid.startsWith("NEUF_USHUAIA") ||
                        cid.startsWith("NEUF_FILMDAFRIQUE")
                    if (isBrokenThirdParty) {
                        pendingExtinf = null
                        continue
                    }
                }
                // 2026-06-21 : BFM replay items get a "bfm/" prefix so
                // TvShowViewHolder can route them to episode browsing
                // (same pattern as tf1/ and m6replay/ natural prefixes).
                val siId = if (src.startsWith("bfmplay://")) {
                    "bfm/" + src.substringAfter("://").trim()
                } else {
                    src.substringAfter("://").trim()
                }
                val title = pendingExtinf!!.substringAfterLast(",").trim()
                val groupTitle = Regex("""group-title="([^"]+)"""")
                    .find(pendingExtinf!!)?.groupValues?.get(1) ?: "Replay"
                val logo = Regex("""tvg-logo="([^"]+)"""")
                    .find(pendingExtinf!!)?.groupValues?.get(1) ?: ""
                // 2026-06-19 : tvg-type="movie" sur les films/spectacles/etc.
                //   pour afficher le badge "Film" au lieu de "Série" (= ViewHolder
                //   utilise tvShow.isMovie pour distinguer).
                val tvgType = Regex("""tvg-type="([^"]+)"""")
                    .find(pendingExtinf!!)?.groupValues?.get(1) ?: ""
                val tv = TvShow(
                    id = "livehub::replay::$siId",
                    title = title,
                ).apply {
                    providerName = "TV Hub"
                    poster = logo
                    banner = logo
                    if (tvgType == "movie" || tvgType == "telefilm") isMovie = true
                }
                // 2026-06-20 v9 : split catégories par tvg-type pour que
                //   displayAggregatedCategories puisse agrèger Films / Séries.
                //   "Replay TF1" + tvg-type="movie" → "Replay TF1 - Films"
                //   "Replay TF1" + tvg-type="series" → "Replay TF1 - Séries"
                //   Les groupes "Live *" et "Arte *" restent inchangés (Arte
                //   a ses propres catégories thématiques, Live = pas de split).
                // 2026-06-21 : ne PAS splitter par tvg-type si le groupTitle
                //   contient DÉJÀ une sous-section (ex "Replay BFM TV - Infos").
                //   Le split ne s'applique qu'aux groupes de base ("Replay TF1",
                //   "Replay M6", etc.) qui n'ont pas de " - " après le nom canal.
                val alreadyHasSubSection = groupTitle.startsWith("Replay ") &&
                    groupTitle.indexOf(" - ") > 0
                // 2026-06-21 fix : BFM/RMC entries have their own thematic
                //   sub-categories from the M3U (e.g. "Replay BFM TV - Politique").
                //   The tvg-type split (Films/Séries) is designed for TF1+/M6+
                //   only — applying it to BFM merges all items into one blob.
                val isBfmRmc = groupTitle.startsWith("Replay BFM") ||
                    groupTitle.startsWith("Replay RMC")
                val effectiveGroup = if (groupTitle.startsWith("Replay ") && !alreadyHasSubSection && !isBfmRmc) {
                    when (tvgType) {
                        "movie" -> "$groupTitle - Films"
                        "telefilm" -> "$groupTitle - Téléfilms"
                        else    -> "$groupTitle - Séries"
                    }
                } else {
                    groupTitle
                }
                groups.getOrPut(effectiveGroup) { mutableListOf() }.add(tv)
                replayProgramTitles[siId] = title
                replayProgramSrcs[siId] = src
                pendingExtinf = null
            } else if (t.isEmpty() || !t.startsWith("#")) {
                pendingExtinf = null
            }
        }
        // 2026-06-18 (user "Ton bouton refresh il devrait être Tout en haut
        //   à côté de La planète… il a rien à faire là") : RETIRE la card
        //   refresh. Le bouton est maintenant dans la top-bar à côté du globe.
        // 2026-06-18 (user "faut peut être que les catégories soient fixées
        //   pour se connecter" + "J'ai connecté mon TF1 en replay Il a disparu
        //   carrément") : INSÈRE TOUJOURS les catégories TF1+/M6, qu'on soit
        //   connecté ou non. Comme ça :
        //   - Pas connecté → card "🔓 Connexion" + (programmes M3U si dispo)
        //   - Connecté + 0 programme → catégorie reste visible (sinon l'user
        //     ne voit plus que TF1 a disparu après s'être connecté)
        //   - Connecté + programmes → catégorie avec les programmes
        val appCtxForFix = appContextRef
        if (appCtxForFix != null) {
            val tf1Cats = listOf("Replay TF1", "Replay TMC", "Replay TFX",
                                  "Replay TF1 Séries Films", "Replay LCI")
            val m6Cats  = listOf("Replay M6", "Replay W9", "Replay 6ter",
                                  "Replay Gulli", "Replay Paris Première", "Replay Téva")
            for (cat in tf1Cats) groups.getOrPut(cat) { mutableListOf() }
            for (cat in m6Cats) groups.getOrPut(cat) { mutableListOf() }
        }
        // 2026-06-19 v38 (user "il nous manque toujours la catégorie M6 Live
        //   tu mets toutes les chaînes à part celle qu'il faut payer") :
        //   injecte la catégorie "Live M6+" avec les 4 chaînes M6+ GRATUITES :
        //   M6, W9, 6ter, Gulli. (Paris Première et Téva sont premium-only
        //   chez M6 → exclues.) ID format livehub::replay::m6live::<service>.
        run {
            val liveM6Cat = groups.getOrPut("Live M6+") { mutableListOf() }
            // Si déjà alimenté par data-replay.m3u → on ne re-ajoute pas pour
            //   éviter les doublons. Sinon hardcode les 4 chaînes.
            if (liveM6Cat.isEmpty()) {
                // 2026-06-19 fix : les URLs `6play.fr/m6_dist/logo_*.png` sont
                //   des 404. On utilise tv-logos GitHub (CC0 public).
                val live6 = listOf(
                    Triple("M6",     "m6",    "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/m6-fr.png"),
                    Triple("W9",     "w9",    "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/w9-fr.png"),
                    Triple("6ter",   "6ter",  "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/6ter-fr.png"),
                    Triple("Gulli",  "gulli", "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/gulli-fr.png"),
                )
                for ((label, service, logo) in live6) {
                    val tv = TvShow(
                        id = "livehub::replay::m6live::$service",
                        title = label,
                    ).apply {
                        providerName = "TV Hub"
                        poster = logo
                        banner = logo
                    }
                    liveM6Cat.add(tv)
                    // 2026-06-20 : clé LONG pour matcher get dans getServers
                    val siKey = "m6live::$service"
                    replayProgramTitles[siKey] = label
                    replayProgramSrcs[siKey] = "m6live://$service"
                }
            }
        }
        // Tri canonique : TF1, TMC, ..., France 2, ..., M6, ..., Arte, ...
        val sortedEntries = groups.entries.sortedWith(
            compareBy(
                { REPLAY_CATEGORY_ORDER[it.key] ?: 999 },
                { it.key }
            )
        )
        // 2026-06-18 (user "Le système de connexion à côté du nom de la
        //   Playlist") : pour chaque catégorie M6/TF1+, insère une "card
        //   Se connecter" en TÊTE de liste. Clic = lance LoginWebViewActivity
        //   (intercepté dans TvShowViewHolder). Card masquée si déjà connecté.
        val appCtx = appContextRef
        // 2026-06-21 v2 : pré-calculer les états de connexion UNE SEULE FOIS
        //   (pas à chaque itération du map). Relogin auto BFM si expiré.
        val tf1Logged = appCtx?.let { com.streamflixreborn.streamflix.utils.TF1Auth.isLoggedIn(it) } ?: false
        val m6Logged = appCtx?.let { com.streamflixreborn.streamflix.utils.M6Auth.isLoggedIn(it) } ?: false
        // 2026-06-23 (user "sur BFM il n'y a pas l'option connexion comme TF1
        //   et M6 tout en haut quand on clique sur le dossier") : aligné sur
        //   TF1/M6 — juste check le token valide, PAS de runBlocking +
        //   reloginFromSaved (= bloquait le main thread + consumait des
        //   tentatives serveur BFM à chaque rendu du home → favorisait le
        //   ban). Si l'user a un token expiré, la card "🔓 Connexion" s'affiche
        //   et il peut se reconnecter manuellement (= safe, contrôlé).
        val bfmLogged = appCtx?.let {
            com.streamflixreborn.streamflix.utils.BfmAuth.isLoggedIn(it)
        } ?: false
        return sortedEntries.map { (cat, items) ->
            val list = items.toMutableList()
            if (appCtx != null) {
                val isTf1Plus = cat == "Live TF1+" ||
                    cat.startsWith("Replay TF1") ||
                    cat.startsWith("Replay TMC") ||
                    cat.startsWith("Replay TFX") ||
                    cat.startsWith("Replay LCI")
                val isM6 = cat in setOf("Live M6+", "Replay M6", "Replay W9", "Replay 6ter",
                                        "Replay Gulli", "Replay Paris Première", "Replay Téva")
                val isBfm = cat == "Live BFM Play" ||
                    cat.startsWith("Replay BFM TV") ||
                    cat.startsWith("Replay RMC Story") ||
                    cat.startsWith("Replay RMC Découverte") ||
                    cat.startsWith("Replay BFM Business") ||
                    cat.startsWith("Replay RMC Life")
                if (isTf1Plus) {
                    if (!tf1Logged) {
                        list.add(0, makeLoginCard("tf1", "🔓 Connexion TF1+"))
                    } else if (list.isEmpty()) {
                        // 2026-06-18 (user "J'ai connecté mon TF1 Il a disparu
                        //   carrément") : connecté mais aucun programme M3U →
                        //   card "✓ Connecté" pour montrer que la catégorie
                        //   existe + en attente du M3U.
                        list.add(0, makeStatusCard("tf1", "✓ TF1+ connecté\n(programmes en attente)"))
                    }
                } else if (isM6) {
                    if (!m6Logged) {
                        list.add(0, makeLoginCard("m6", "🔓 Connexion 6play"))
                    } else if (list.isEmpty()) {
                        list.add(0, makeStatusCard("m6", "✓ 6play connecté\n(programmes en attente)"))
                    }
                } else if (isBfm) {
                    if (!bfmLogged) {
                        list.add(0, makeLoginCard("bfm", "🔓 Connexion RMC BFM Play"))
                    } else if (list.isEmpty()) {
                        list.add(0, makeStatusCard("bfm", "✓ BFM Play connecté\n(programmes en attente)"))
                    }
                }
            }
            Category(name = cat, list = list)
        }
    }

    /** Crée une card "✓ Connecté" comme TvShow synthétique. Clic = ouvre la
     *  WebView de login (= permet à l'user de basculer compte ou logout). */
    private fun makeStatusCard(service: String, label: String): TvShow {
        return TvShow(
            id = "livehub::replay::__login_${service}__",  // même id → relance login (= switch compte)
            title = label,
        ).apply {
            providerName = "TV Hub"
            poster = ""
            banner = ""
        }
    }

    /** Crée une card "Se connecter" comme TvShow synthétique. Son id
     *  spécial sera intercepté par TvShowViewHolder pour lancer
     *  LoginWebViewActivity au lieu du player. */
    private fun makeLoginCard(service: String, label: String): TvShow {
        // 2026-06-18 (user "image cassée imgur") : laisse poster vide →
        //   placeholder card propre avec juste le texte (titre/label).
        return TvShow(
            id = "livehub::replay::__login_${service}__",
            title = label,
        ).apply {
            providerName = "TV Hub"
            poster = ""
            banner = ""
        }
    }

    /** Fetch + parse data-replay.m3u → Category par chaîne, TRIÉ par
     *  ordre canonique TNT FR. Cache RAM + disque. Non-bloquant si vide.
     *  `forceRefresh=true` (= cold start) ignore le cache et re-fetch. */
    private suspend fun fetchReplayCategories(forceRefresh: Boolean = false): List<Category> {
        val now = System.currentTimeMillis()
        // 1. Cache RAM frais (sauf si force)
        if (!forceRefresh && replayCacheSections.isNotEmpty() && now - replayCacheTs < REPLAY_TTL_MS) {
            return replayCacheSections
        }
        // 2. Cache disque (sauf si force)
        val diskFile = replayDiskCacheFile
        if (!forceRefresh && diskFile != null && diskFile.exists()) {
            val diskAge = now - diskFile.lastModified()
            if (diskAge < REPLAY_TTL_MS) {
                try {
                    val body = diskFile.readText()
                    val parsed = parseReplayM3u(body)
                    if (parsed.isNotEmpty()) {
                        replayCacheSections = parsed
                        replayCacheTs = now
                        Log.d(TAG, "Replay loaded from DISK cache: ${parsed.size} cats (age=${diskAge / 1000}s)")
                        return parsed
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Replay disk cache read failed: ${e.message}")
                }
            }
        }
        // 3. Fetch réseau, mais ne bloque QU'UNE FOIS — les appels concurrents
        //    retournent le cache RAM ou empty.
        if (replayFetchInProgress) {
            Log.d(TAG, "Replay fetch already in progress, return cached (${replayCacheSections.size} cats)")
            return replayCacheSections
        }
        replayFetchInProgress = true
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val req = okhttp3.Request.Builder().url(REPLAY_M3U_URL)
                    .header("User-Agent", "Mozilla/5.0").build()
                // 2026-06-19 (user "TV hub crash au démarrage sur certaines box") :
                //   stream-bounded read au lieu de .string() qui alloue tout en
                //   RAM d'un coup. Cap 8 MB (= m3u replay actuel ~500 KB, marge
                //   x16). Catch Throwable pour rattraper OOM.
                val body = try {
                    replayClient.newCall(req).execute().use { resp ->
                        val source = resp.body?.source()
                        if (source == null) "" else {
                            val buf = okio.Buffer()
                            val MAX_BYTES = 8L * 1024 * 1024
                            var total = 0L
                            while (!source.exhausted() && total < MAX_BYTES) {
                                val n = source.read(buf, 64 * 1024L)
                                if (n == -1L) break
                                total += n
                            }
                            if (total >= MAX_BYTES) {
                                Log.w(TAG, "Replay M3U exceeded ${MAX_BYTES} bytes — abort")
                                ""
                            } else buf.readUtf8()
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Replay body read failed: ${t.javaClass.simpleName}: ${t.message}")
                    ""
                }
                if (body.isBlank() || "#EXTM3U" !in body) {
                    Log.w(TAG, "Replay M3U empty or invalid")
                    return@withContext emptyList<Category>()
                }
                val parsed = parseReplayM3u(body)
                replayCacheSections = parsed
                replayCacheTs = now
                // Persiste sur disque
                try {
                    diskFile?.writeText(body)
                    Log.d(TAG, "Replay saved to DISK cache (${body.length} bytes)")
                } catch (e: Throwable) {
                    Log.w(TAG, "Replay disk cache write failed: ${e.message}")
                }
                Log.d(TAG, "Replay parsed: ${parsed.size} catégories, ${replayProgramTitles.size} programmes")
                parsed
            } catch (e: Throwable) {
                Log.w(TAG, "Replay fetch failed: ${e.message}")
                emptyList()
            } finally {
                replayFetchInProgress = false
            }
        }
    }

    /** 2026-06-18 (user "ça met du temps à charger Le replay" + "Là j'ai
     *  refresh Le nouveau Les catégories n'apparaissent toujours pas") :
     *  appelé une fois au cold start (StreamFlixApp.onCreate). Pré-charge
     *  le M3U replay en background. forceRefresh=true → ignore le cache
     *  disque et va chercher la VRAIE dernière version du M3U depuis
     *  GitHub. Le cache disque reste utilisé comme affichage instantané
     *  au démarrage avant que le re-fetch ne complète. */
    suspend fun warmReplayCache() {
        try {
            fetchReplayCategories(forceRefresh = true)
        } catch (e: Throwable) {
            Log.w(TAG, "warmReplayCache failed: ${e.message}")
        }
    }

    /** 2026-06-18 (user "on peut pas mettre un bouton pour ça Dans le TVhub
     *  Pour forcer le Refresh") : vide TOUS les caches replay (RAM + disque)
     *  et relance immédiatement un fetch frais. Retourne le nombre de
     *  catégories obtenues (= 0 si KO réseau). À appeler depuis l'UI. */
    suspend fun forceRefreshReplay(): Int {
        replayCacheSections = emptyList()
        replayCacheTs = 0L
        try { replayDiskCacheFile?.delete() } catch (_: Throwable) {}
        Log.d(TAG, "forceRefreshReplay: cache cleared, re-fetching from network")
        val cats = fetchReplayCategories(forceRefresh = true)
        return cats.size
    }

    // ────────────── 2026-06-24 FAST Channels (Samsung TV+, Pluto TV, etc.) ──────────────
    private const val FAST_M3U_URL =
        "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data-fast.m3u"
    @Volatile private var fastCacheTs: Long = 0L
    @Volatile private var fastCacheSections: List<Category> = emptyList()
    private const val FAST_TTL_MS = 30L * 60 * 1000  // 30 min
    @Volatile private var fastFetchInProgress: Boolean = false
    @Volatile private var fastDiskCacheFile: java.io.File? = null
    /** Map fastId → stream URL (= URL HTTP directe). */
    val fastChannelUrls: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    /** Map fastId → nom de la chaîne (= pour getTvShow + getServers). */
    val fastChannelNames: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    /** Map fastId → logo URL. */
    val fastChannelLogos: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    /** 2026-06-27 : Map fastId → headers HTTP (UA/Referer captés des #EXTVLCOPT,
     *  pour les chaînes Mix FR / data.m3u qui exigent un UA/Referer spécifique). */
    val fastChannelHeaders: MutableMap<String, Map<String, String>> = java.util.concurrent.ConcurrentHashMap()

    /** 2026-06-27 : persiste une chaîne fast favorisée (url+name+logo+headers)
     *  pour qu'elle reste jouable depuis les Cœurs même après redémarrage (l'id
     *  est un hash non reconstructible — ex chaînes Musique/WorldWide). */
    fun persistFastFavorite(id: String) {
        val ctx = appContextRef ?: return
        val url = fastChannelUrls[id] ?: return
        try {
            val o = org.json.JSONObject()
            o.put("url", url)
            o.put("name", fastChannelNames[id] ?: "")
            o.put("logo", fastChannelLogos[id] ?: "")
            fastChannelHeaders[id]?.let { h ->
                val ho = org.json.JSONObject()
                for ((k, v) in h) ho.put(k, v)
                o.put("headers", ho)
            }
            ctx.getSharedPreferences("livehub_fast_fav", android.content.Context.MODE_PRIVATE)
                .edit().putString(id, o.toString()).apply()
        } catch (_: Throwable) {}
    }

    /** Restaure (et re-peuple les maps RAM) une chaîne fast favorisée. */
    private fun restoreFastFavorite(id: String): String? {
        val ctx = appContextRef ?: return null
        return try {
            val raw = ctx.getSharedPreferences("livehub_fast_fav", android.content.Context.MODE_PRIVATE)
                .getString(id, null) ?: return null
            val o = org.json.JSONObject(raw)
            val url = o.optString("url").ifBlank { return null }
            fastChannelUrls[id] = url
            o.optString("name").takeIf { it.isNotBlank() }?.let { fastChannelNames[id] = it }
            o.optString("logo").takeIf { it.isNotBlank() }?.let { fastChannelLogos[id] = it }
            o.optJSONObject("headers")?.let { h ->
                val m = HashMap<String, String>()
                val keys = h.keys()
                while (keys.hasNext()) { val k = keys.next(); m[k] = h.optString(k) }
                if (m.isNotEmpty()) fastChannelHeaders[id] = m
            }
            url
        } catch (_: Throwable) { null }
    }

    // 2026-06-27 : cache de la playlist "Mix FR" (data.m3u, World Live mix).
    @Volatile private var mixFrCacheSections: List<Category> = emptyList()
    @Volatile private var mixFrCacheTs: Long = 0L
    private val MIX_FR_TTL_MS = 30 * 60 * 1000L
    private const val MIX_FR_M3U_URL =
        "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data.m3u"

    // 2026-06-27 (user "ajouter la playlist WorldWide dans Autres Replays,
    //   mirror sur notre git pour auto-refresh") : la playlist WorldWide
    //   (chaînes du monde, source epg.pw) est copiée dans notre repo nx-data
    //   par un workflow GitHub (refresh_worldwide.yml) → on lit NOTRE copie
    //   (indépendant d'epg.pw + auto-refresh). Même parser que Mix FR.
    @Volatile private var worldwideCacheSections: List<Category> = emptyList()
    @Volatile private var worldwideCacheTs: Long = 0L
    private const val WORLDWIDE_M3U_URL =
        "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data-worldwide.m3u"

    // 2026-06-27 (user "nouveau dossier Musique avec tout ce qui traîne de
    //   musique, en sous-dossiers, mirror git auto-refresh") : playlist musique
    //   (source iptv-org catégorie music = mondiale + maintenue) mirror dans
    //   notre git nx-data (refresh_musique.yml) → data-musique.m3u. Fallback
    //   direct iptv-org tant que la copie git n'existe pas. Groupé par langue.
    @Volatile private var musiqueCacheSections: List<Category> = emptyList()
    @Volatile private var musiqueCacheTs: Long = 0L
    private const val MUSIQUE_M3U_URL =
        "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data-musique.m3u"
    private const val MUSIQUE_FALLBACK_URL =
        "https://iptv-org.github.io/iptv/categories/music.m3u"

    // 2026-06-29 (REPAIR — re-appliqué) : dossier Stream4Free. M3U dédié
    //   (refs stream4free://<slug>, résolues à la lecture). Cache RAM 30 min.
    @Volatile private var stream4CacheSections: List<Category> = emptyList()
    @Volatile private var stream4CacheTs: Long = 0L
    // 2026-06-29 (REPAIR — user "le fail-safe Stream4Free doit être sur TOUS les
    //   dossiers") : cache + fail-safe pour Pluto et Plex (sinon un seul await()
    //   qui échoue tue tout le dossier à l'ouverture).
    @Volatile private var plutoFolderCacheSections: List<Category> = emptyList()
    @Volatile private var plutoFolderCacheTs: Long = 0L
    @Volatile private var plexFolderCacheSections: List<Category> = emptyList()
    @Volatile private var plexFolderCacheTs: Long = 0L
    private const val STREAM4_M3U_URL =
        "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data-stream4free.m3u"
    // 2026-07-10 (user "supprime l'intégration LumiChat partout, même sur git") : tous les champs
    //   et helpers LumiChat (cache, M3U URL nx-data, multi-serveurs, rank, normName) RETIRÉS.

    fun installFastDiskCache(cacheDir: java.io.File) {
        if (fastDiskCacheFile == null) {
            fastDiskCacheFile = java.io.File(cacheDir, "fast-m3u.cache")
        }
    }

    /** Parse le M3U FAST qui contient des URLs HTTP/HTTPS standard.
     *  Groupe par group-title, crée des TvShow avec ID livehub::fast::<hash>. */
    private fun parseFastM3u(body: String): List<Category> {
        if (body.isBlank() || "#EXTM3U" !in body) return emptyList()
        val groups = LinkedHashMap<String, MutableList<TvShow>>()
        val lines = body.lines()
        var pendingExtinf: String? = null
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("#EXTINF:")) {
                pendingExtinf = t
            } else if (pendingExtinf != null && (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("stream4free://") || t.startsWith("stream4cf://"))) {
                val url = t
                val title = pendingExtinf.substringAfterLast(",").trim()
                val rawGroupTitle = Regex("""group-title="([^"]+)"""")
                    .find(pendingExtinf)?.groupValues?.get(1) ?: "FAST"
                // Remap LG sub-brands → dossiers dédiés (Pluto TV, Rakuten TV,
                //   Sony One sont catégorisés par le script sous "LG Channels - X"
                //   mais doivent apparaître dans leurs propres dossiers TV Hub).
                val groupTitle = when {
                    rawGroupTitle == "LG Channels - Pluto TV"   -> "Pluto TV"
                    rawGroupTitle == "LG Channels - Rakuten TV" -> "Rakuten TV"
                    rawGroupTitle == "LG Channels - Sony One"   -> "Sony One"
                    else -> rawGroupTitle
                }
                val logo = Regex("""tvg-logo="([^"]+)"""")
                    .find(pendingExtinf)?.groupValues?.get(1) ?: ""
                // ID basé sur un hash court de l'URL pour unicité stable
                val hash = url.hashCode().toUInt().toString(16)
                val fastId = "livehub::fast::$hash"
                val tv = TvShow(
                    id = fastId,
                    title = title,
                ).apply {
                    providerName = "TV Hub"
                    poster = logo
                    banner = logo
                }
                groups.getOrPut(groupTitle) { mutableListOf() }.add(tv)
                fastChannelUrls[fastId] = url
                fastChannelNames[fastId] = title
                if (logo.isNotBlank()) fastChannelLogos[fastId] = logo
                pendingExtinf = null
            } else if (t.isEmpty() || !t.startsWith("#")) {
                pendingExtinf = null
            }
        }
        return groups.map { (name, items) ->
            Category(name = name, list = items)
        }
    }

    /** Fetch + parse data-fast.m3u → Category par service FAST.
     *  Cache RAM + disque, TTL 30 min. */
    private suspend fun fetchFastCategories(forceRefresh: Boolean = false): List<Category> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && fastCacheSections.isNotEmpty() && now - fastCacheTs < FAST_TTL_MS) {
            return fastCacheSections
        }
        val diskFile = fastDiskCacheFile
        if (!forceRefresh && diskFile != null && diskFile.exists()) {
            val diskAge = now - diskFile.lastModified()
            if (diskAge < FAST_TTL_MS) {
                try {
                    val body = diskFile.readText()
                    val parsed = parseFastM3u(body)
                    if (parsed.isNotEmpty()) {
                        fastCacheSections = parsed
                        fastCacheTs = now
                        Log.d(TAG, "FAST loaded from DISK cache: ${parsed.size} cats (age=${diskAge / 1000}s)")
                        return parsed
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "FAST disk cache read failed: ${e.message}")
                }
            }
        }
        if (fastFetchInProgress) {
            Log.d(TAG, "FAST fetch already in progress, return cached (${fastCacheSections.size} cats)")
            return fastCacheSections
        }
        fastFetchInProgress = true
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val req = okhttp3.Request.Builder().url(FAST_M3U_URL)
                    .header("User-Agent", "Mozilla/5.0").build()
                val body = try {
                    replayClient.newCall(req).execute().use { resp ->
                        val source = resp.body?.source()
                        if (source == null) "" else {
                            val buf = okio.Buffer()
                            val MAX_BYTES = 4L * 1024 * 1024  // 4 MB cap
                            var total = 0L
                            while (!source.exhausted() && total < MAX_BYTES) {
                                val n = source.read(buf, 64 * 1024L)
                                if (n == -1L) break
                                total += n
                            }
                            if (total >= MAX_BYTES) {
                                Log.w(TAG, "FAST M3U exceeded ${MAX_BYTES} bytes — abort")
                                ""
                            } else buf.readUtf8()
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "FAST body read failed: ${t.javaClass.simpleName}: ${t.message}")
                    ""
                }
                if (body.isBlank() || "#EXTM3U" !in body) {
                    Log.w(TAG, "FAST M3U empty or invalid")
                    return@withContext emptyList<Category>()
                }
                val parsed = parseFastM3u(body)
                fastCacheSections = parsed
                fastCacheTs = now
                try {
                    diskFile?.writeText(body)
                    Log.d(TAG, "FAST saved to DISK cache (${body.length} bytes)")
                } catch (e: Throwable) {
                    Log.w(TAG, "FAST disk cache write failed: ${e.message}")
                }
                val totalChans = parsed.sumOf { (it.list as? List<*>)?.size ?: 0 }
                Log.d(TAG, "FAST parsed: ${parsed.size} catégories, $totalChans chaînes")
                parsed
            } catch (e: Throwable) {
                Log.w(TAG, "FAST fetch failed: ${e.message}")
                emptyList()
            } finally {
                fastFetchInProgress = false
            }
        }
    }

    /** Public wrapper pour LiveHubFolderDialog (lazy fetch on-demand). */
    suspend fun fetchFastCategoriesPublic(): List<Category> = fetchFastCategories()

    /** 2026-06-27 : parseur de la playlist "Mix FR" (data.m3u, World Live mix).
     *  Comme parseFastM3u MAIS capture les headers HTTP (#EXTVLCOPT
     *  http-user-agent / http-referrer / http-origin + inline EXTINF) pour les
     *  chaînes qui exigent un UA/Referer. group-title préservé = nom de catégorie. */
    private fun parseMixFrM3u(body: String): List<Category> {
        if (body.isBlank() || "#EXTM3U" !in body) return emptyList()
        val groups = LinkedHashMap<String, MutableList<TvShow>>()
        var pendingExtinf: String? = null
        val pendingHeaders = HashMap<String, String>()
        fun resetPending() { pendingExtinf = null; pendingHeaders.clear() }
        for (line in body.lines()) {
            val t = line.trim()
            when {
                t.startsWith("#EXTINF:") -> {
                    pendingExtinf = t
                    pendingHeaders.clear()
                    // headers inline éventuels dans l'EXTINF
                    Regex("""http-user-agent="([^"]+)"""", RegexOption.IGNORE_CASE)
                        .find(t)?.let { pendingHeaders["User-Agent"] = it.groupValues[1] }
                    Regex("""http-referrer="([^"]+)"""", RegexOption.IGNORE_CASE)
                        .find(t)?.let { pendingHeaders["Referer"] = it.groupValues[1] }
                }
                t.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = t.substringAfter(":").trim()
                    val k = opt.substringBefore("=").trim().lowercase()
                    val v = opt.substringAfter("=", "").trim()
                    if (v.isNotEmpty()) when (k) {
                        "http-user-agent" -> pendingHeaders["User-Agent"] = v
                        "http-referrer", "http-referer" -> pendingHeaders["Referer"] = v
                        "http-origin" -> pendingHeaders["Origin"] = v
                    }
                }
                pendingExtinf != null && (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("brightcove://")) -> {
                    val ext = pendingExtinf!!
                    val url = t
                    val title = ext.substringAfterLast(",").trim()
                    val groupTitle = Regex("""group-title="([^"]+)"""")
                        .find(ext)?.groupValues?.get(1) ?: "Mix FR"
                    val logo = Regex("""tvg-logo="([^"]+)"""")
                        .find(ext)?.groupValues?.get(1) ?: ""
                    // id distinct (préfixe mixfr) pour ne pas collisionner avec data-fast,
                    // mais toujours sous "livehub::fast::" pour réutiliser le getVideo direct.
                    val hash = ("mixfr" + url).hashCode().toUInt().toString(16)
                    val fastId = "livehub::fast::$hash"
                    val tv = TvShow(id = fastId, title = title).apply {
                        providerName = "TV Hub"
                        poster = logo
                        banner = logo
                    }
                    groups.getOrPut(groupTitle) { mutableListOf() }.add(tv)
                    fastChannelUrls[fastId] = url
                    fastChannelNames[fastId] = title
                    if (logo.isNotBlank()) fastChannelLogos[fastId] = logo
                    if (pendingHeaders.isNotEmpty()) fastChannelHeaders[fastId] = HashMap(pendingHeaders)
                    resetPending()
                }
                t.isEmpty() || (!t.startsWith("#") ) -> resetPending()
            }
        }
        return groups.map { (name, items) -> Category(name = name, list = items) }
    }

    /** 2026-06-27 (user "Mix FR dans le dossier Autres Replays") : fetch + parse
     *  data.m3u (mix m3u xdata-mix/nx-data, auto-refresh via refresh.yml) →
     *  catégories par group-title. Cache RAM 30 min. */
    suspend fun fetchMixFrCategoriesPublic(): List<Category> {
        val now = System.currentTimeMillis()
        if (mixFrCacheSections.isNotEmpty() && now - mixFrCacheTs < MIX_FR_TTL_MS) {
            return mixFrCacheSections
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val req = okhttp3.Request.Builder().url(MIX_FR_M3U_URL)
                    .header("User-Agent", "Mozilla/5.0").build()
                val body = replayClient.newCall(req).execute().use { resp ->
                    resp.body?.string().orEmpty()
                }
                if (body.isBlank() || "#EXTM3U" !in body) {
                    Log.w(TAG, "Mix FR M3U empty/invalid")
                    return@withContext mixFrCacheSections
                }
                val parsed = parseMixFrM3u(body)
                if (parsed.isNotEmpty()) {
                    mixFrCacheSections = parsed
                    mixFrCacheTs = now
                }
                val total = parsed.sumOf { (it.list as? List<*>)?.size ?: 0 }
                Log.d(TAG, "Mix FR parsed: ${parsed.size} catégories, $total chaînes")
                parsed
            } catch (e: Throwable) {
                Log.w(TAG, "Mix FR fetch failed: ${e.message}")
                mixFrCacheSections
            }
        }
    }

    /** 2026-06-27 (user "ajouter WorldWide dans Autres Replays, auto-refresh") :
     *  fetch + parse data-worldwide.m3u (mirror epg.pw sur notre git nx-data,
     *  rafraîchi par refresh_worldwide.yml) → catégories par group-title (pays).
     *  Réutilise le parser M3U générique de Mix FR (URLs de flux directes,
     *  jouées telles quelles par getVideo FAST). Cache RAM 30 min. */
    suspend fun fetchWorldwideCategoriesPublic(): List<Category> {
        val now = System.currentTimeMillis()
        if (worldwideCacheSections.isNotEmpty() && now - worldwideCacheTs < MIX_FR_TTL_MS) {
            return worldwideCacheSections
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                fun dl(url: String): String = try {
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0").build()
                    replayClient.newCall(req).execute().use { it.body?.string().orEmpty() }
                } catch (_: Throwable) { "" }
                // 1) notre copie git (auto-refresh) ; 2) fallback epg.pw direct
                //    tant que le mirror git n'existe pas encore.
                var body = dl(WORLDWIDE_M3U_URL)
                if (body.isBlank() || "#EXTM3U" !in body) {
                    Log.d(TAG, "WorldWide: git copy absente, fallback epg.pw direct")
                    body = dl("https://epg.pw/test_channels_all.m3u")
                }
                if (body.isBlank() || "#EXTM3U" !in body) {
                    Log.w(TAG, "WorldWide M3U empty/invalid")
                    return@withContext worldwideCacheSections
                }
                val parsed = parseMixFrM3u(body)
                if (parsed.isNotEmpty()) {
                    worldwideCacheSections = parsed
                    worldwideCacheTs = now
                }
                val total = parsed.sumOf { (it.list as? List<*>)?.size ?: 0 }
                Log.d(TAG, "WorldWide parsed: ${parsed.size} catégories, $total chaînes")
                parsed
            } catch (e: Throwable) {
                Log.w(TAG, "WorldWide fetch failed: ${e.message}")
                worldwideCacheSections
            }
        }
    }

    /** Mots-clés pour détecter une chaîne musicale dans nos playlists génériques. */
    private val MUSIC_KEYWORDS = Regex(
        "\\b(music|musi[ck]|mtv|vevo|clip|hits|trace|mcm|melody|mélodie|nrj|rfm|" +
        "virgin radio|kpop|k-pop|jukebox|stingray|vh1|clubbing|dance|techno|house music|" +
        "rock|metal|jazz|reggae|rap|hip.?hop|r&b|country music|latino|salsa|" +
        "classical|classique|opera|concert|festival|dj set|deephouse|electro)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Label langue lisible (FR) depuis un code/nom de langue ou pays. */
    private fun musiqueLangLabel(lang: String, country: String): String {
        val l = lang.trim().lowercase()
        val c = country.trim().lowercase()
        return when {
            l.contains("fr") || l.contains("french") || c == "fr" || c.contains("france") -> "Français"
            l.contains("en") || l.contains("english") -> "Anglais"
            l.contains("es") || l.contains("span") -> "Espagnol"
            l.contains("it") || l.contains("ital") -> "Italien"
            l.contains("de") || l.contains("ger") || l.contains("deu") -> "Allemand"
            l.contains("pt") || l.contains("portu") -> "Portugais"
            l.contains("ar") || l.contains("arab") -> "Arabe"
            l.isNotBlank() -> lang.replaceFirstChar { it.uppercase() }
            else -> "International"
        }
    }

    /** Ingère un m3u dans [groups] (groupé par langue), avec dédup par URL via
     *  [seenUrls]. Si [musicOnly], ne garde que les chaînes dont le nom/groupe
     *  matche MUSIC_KEYWORDS (= pour filtrer la musique dans nos playlists mixtes). */
    private fun ingestMusiqueM3u(
        body: String,
        musicOnly: Boolean,
        seenUrls: HashSet<String>,
        groups: LinkedHashMap<String, MutableList<TvShow>>,
    ) {
        if (body.isBlank() || "#EXTM3U" !in body) return
        var ext: String? = null
        val hdrs = HashMap<String, String>()
        for (raw in body.lines()) {
            val t = raw.trim()
            when {
                t.startsWith("#EXTINF:") -> { ext = t; hdrs.clear() }
                t.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = t.substringAfter(":").trim()
                    val k = opt.substringBefore("=").trim().lowercase()
                    val v = opt.substringAfter("=", "").trim()
                    if (v.isNotEmpty()) when (k) {
                        "http-user-agent" -> hdrs["User-Agent"] = v
                        "http-referrer", "http-referer" -> hdrs["Referer"] = v
                        "http-origin" -> hdrs["Origin"] = v
                    }
                }
                ext != null && (t.startsWith("http://") || t.startsWith("https://")) -> {
                    val e = ext!!; ext = null
                    if (!seenUrls.add(t)) { hdrs.clear(); continue }  // dédup URL
                    val title = e.substringAfterLast(",").trim()
                    val group = Regex("""group-title="([^"]+)"""").find(e)?.groupValues?.get(1) ?: ""
                    if (musicOnly && !MUSIC_KEYWORDS.containsMatchIn("$title $group")) { hdrs.clear(); continue }
                    val logo = Regex("""tvg-logo="([^"]+)"""").find(e)?.groupValues?.get(1) ?: ""
                    val lang = Regex("""tvg-language="([^"]*)"""").find(e)?.groupValues?.get(1) ?: ""
                    val country = Regex("""tvg-country="([^"]*)"""").find(e)?.groupValues?.get(1) ?: ""
                    val label = musiqueLangLabel(lang, country)
                    val hash = ("musiq" + t).hashCode().toUInt().toString(16)
                    val fastId = "livehub::fast::$hash"
                    val tv = TvShow(id = fastId, title = title).apply {
                        providerName = "TV Hub"; poster = logo; banner = logo
                    }
                    groups.getOrPut(label) { mutableListOf() }.add(tv)
                    fastChannelUrls[fastId] = t
                    fastChannelNames[fastId] = title
                    if (logo.isNotBlank()) fastChannelLogos[fastId] = logo
                    if (hdrs.isNotEmpty()) fastChannelHeaders[fastId] = HashMap(hdrs)
                    hdrs.clear()
                }
                t.isEmpty() || !t.startsWith("#") -> { ext = null; hdrs.clear() }
            }
        }
    }

    /** 2026-06-27 (user "dossier Musique : tout ce qu'on a en musique, sous-dossiers,
     *  sans doublons, mirror git auto-refresh") : agrège la musique de plusieurs
     *  sources avec dédup par URL, groupé par langue (sous-dossiers) :
     *   1) notre copie git data-musique.m3u (pré-agrégée, si présente) — sinon
     *   2) iptv-org catégorie music (mondiale) + nos playlists WorldLive
     *      (data.m3u Mix FR + data-worldwide.m3u) filtrées sur mots-clés musique.
     *  Cache RAM 30 min. */
    suspend fun fetchMusiqueCategoriesPublic(): List<Category> {
        val now = System.currentTimeMillis()
        if (musiqueCacheSections.isNotEmpty() && now - musiqueCacheTs < MIX_FR_TTL_MS) {
            return musiqueCacheSections
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                fun dl(url: String): String = try {
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0").build()
                    replayClient.newCall(req).execute().use { it.body?.string().orEmpty() }
                } catch (_: Throwable) { "" }
                val seen = HashSet<String>()
                val groups = LinkedHashMap<String, MutableList<TvShow>>()
                // 2026-06-29 (REPAIR — user "même device sans VPN, l'ancien trouve
                //   807, le nouveau 790 → c'est le code") : on MERGE TOUTES les
                //   sources (au lieu de git-OU-fallback). Avant : si data-musique.m3u
                //   existait, on prenait UNIQUEMENT lui (790) en ignorant iptv-org +
                //   WorldLive → on perdait ~17 chaînes. Maintenant union dédupliquée
                //   par URL (l'ordre git d'abord garde nos métadonnées curées).
                // 1) copie git pré-agrégée (curée), si présente
                val gitBody = dl(MUSIQUE_M3U_URL)
                if (gitBody.isNotBlank() && "#EXTM3U" in gitBody) {
                    ingestMusiqueM3u(gitBody, musicOnly = false, seen, groups)
                }
                // 2) iptv-org music (base mondiale) — tout est musique
                ingestMusiqueM3u(dl(MUSIQUE_FALLBACK_URL), musicOnly = false, seen, groups)
                // 3) complément depuis nos playlists WorldLive (filtré musique, dédup URL)
                ingestMusiqueM3u(dl(MIX_FR_M3U_URL), musicOnly = true, seen, groups)
                ingestMusiqueM3u(dl(WORLDWIDE_M3U_URL).ifBlank { dl("https://epg.pw/test_channels_all.m3u") },
                    musicOnly = true, seen, groups)
                // 2026-06-29 (REPAIR — user "807 vs 790 : il manque les chaînes
                //   hardcodées, ex '90 Is Good'") : ré-injecte les 17 radios Dric4rTv
                //   hardcodées (RadioCatalog) + la chaîne musique custom "90 Is Good"
                //   dans le dossier Musique. Elles avaient disparu quand Dric4rTV a
                //   été retiré du hub (DRIC4RTV_IN_HUB=false). URLs CDN directes,
                //   jouables via le pipeline FAST (fastChannelUrls). Dédup par URL.
                run {
                    data class ExtraMus(val id: String, val name: String, val poster: String?, val url: String, val ua: String?)
                    val extras = ArrayList<ExtraMus>()
                    com.streamflixreborn.streamflix.utils.RadioCatalog.hardcodedDricRadios().forEach { r ->
                        val u = r.streamUrl ?: return@forEach
                        extras.add(ExtraMus(r.id, r.name, r.poster, u, null))
                    }
                    // "90 Is Good (IT)" — chaîne musique 90s custom Dric4rTv (m3u8 direct Wowza).
                    extras.add(ExtraMus(
                        "livehub::dric4rtv::muzik::90isgoodit", "90 Is Good (IT)",
                        "https://www.coolstreaming.us/img/ch/image40300666727.jpg",
                        "https://64b16f23efbee.streamlock.net/isgoodforyou/isgoodforyou/playlist.m3u8",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    ))
                    var added = 0
                    for (e in extras) {
                        // Extras CURÉS : dédup par ID (PAS par URL). Avant, dédup par
                        //   URL sautait "90 Is Good" si une entrée iptv-org partageait
                        //   son URL sous un autre nom (→ introuvable à la recherche).
                        if (fastChannelUrls.containsKey(e.id)) continue
                        seen.add(e.url)
                        val tv = TvShow(id = e.id, title = e.name).apply {
                            providerName = "TV Hub"; poster = e.poster ?: ""; banner = e.poster ?: ""
                        }
                        groups.getOrPut("International") { mutableListOf() }.add(tv)
                        fastChannelUrls[e.id] = e.url
                        fastChannelNames[e.id] = e.name
                        if (!e.poster.isNullOrBlank()) fastChannelLogos[e.id] = e.poster!!
                        if (e.ua != null) fastChannelHeaders[e.id] = HashMap<String, String>().apply { put("User-Agent", e.ua!!) }
                        added++
                    }
                    Log.w(TAG, "MUSIQ: +$added radios/custom Dric4rTv injectées")
                }
                // 2026-07-05 (user "pourquoi 1 chaîne toute seule dans Français
                //   au lieu d'être avec les autres") : fusionner les catégories
                //   trop petites (≤5 chaînes) dans "International".
                val intl = groups.getOrPut("International") { mutableListOf() }
                val tinyKeys = groups.keys.filter { it != "International" && (groups[it]?.size ?: 0) <= 5 }
                for (k in tinyKeys) {
                    intl.addAll(groups[k]!!)
                    groups.remove(k)
                }
                // Français d'abord (si > 5 chaînes), puis le reste alpha
                val ordered = groups.entries.sortedWith(
                    compareByDescending<Map.Entry<String, MutableList<TvShow>>> { it.key == "Français" }
                        .thenBy { it.key }
                )
                val cats = ordered.map { (name, items) ->
                    Category(name = "${name} (${items.size})", list = items)
                }
                if (cats.isNotEmpty()) { musiqueCacheSections = cats; musiqueCacheTs = now }
                val total = cats.sumOf { (it.list as? List<*>)?.size ?: 0 }
                Log.d(TAG, "Musique: ${cats.size} langues, $total chaînes (dédup)")
                cats
            } catch (e: Throwable) {
                Log.w(TAG, "Musique fetch failed: ${e.message}")
                musiqueCacheSections
            }
        }
    }

    /** 2026-06-29 (REPAIR — re-appliqué) : fetch le M3U Stream4Free dédié et le
     *  parse en catégories (group-title). Refs stream4free:// résolues à la lecture. */
    // 2026-07-10 (user "nouveau dossier séparé CF, scrape live") : dossier de TEST « Stream4Free CF ».
    //   PRIMAIRE = scrape LIVE de la liste des chaînes sur le site (via WebView-bypass CF). FALLBACK =
    //   liste git ré-préfixée `stream4cf://` (folder peuplé pour itérer même si le CF dur n'est pas
    //   encore passé). Lecture routée vers Stream4FreeResolverCfTest (préfixe `stream4cf://`).
    @Volatile private var stream4CfCache: List<Category> = emptyList()
    @Volatile private var stream4CfCacheTs: Long = 0L
    suspend fun fetchStream4CfCategoriesLive(): List<Category> {
        val now = System.currentTimeMillis()
        if (stream4CfCache.isNotEmpty() && now - stream4CfCacheTs < MIX_FR_TTL_MS) return stream4CfCache
        // 2026-07-10 (user : « faut faire ça DANS l'app, le git ne passe pas le CF, l'app oui ») :
        // 1) PRIMAIRE = SCRAPE LIVE dans l'app. L'app passe le CF (WebView-bypass) → liste FRAÎCHE des
        //    chaînes directement depuis stream4free.tv (avec logos). ZÉRO git.
        val liveM3u = runCatching {
            com.streamflixreborn.streamflix.utils.Stream4FreeResolverCfTest.scrapeChannelsM3u()
        }.getOrDefault("")
        if (liveM3u.isNotBlank()) {
            val liveCats = parseFastM3u(liveM3u)
            if (liveCats.isNotEmpty()) {
                stream4CfCache = liveCats; stream4CfCacheTs = now
                Log.d(TAG, "Stream4Free: ${liveCats.size} cat (SCRAPE LIVE app-CF)")
                launchCfPreResolve()  // pré-résout en fond → prêtes à cliquer
                return liveCats
            }
        }
        // 2) REPLI (CF qui hoquette au boot) : LISTE EN DUR des 53 chaînes (slugs + logos) → le dossier
        //    n'est JAMAIS vide ; chaque chaîne se résout via le CF au clic (l'app le passe).
        val cats = parseFastM3u(com.streamflixreborn.streamflix.utils.Stream4FreeResolverCfTest.BAKED_CHANNELS_M3U_PROXIED)
        if (cats.isNotEmpty()) {
            stream4CfCache = cats; stream4CfCacheTs = now
            Log.d(TAG, "Stream4Free: ${cats.size} cat (liste EN DUR repli ; CF au clic)")
            launchCfPreResolve()
        }
        return cats
    }

    // 2026-07-10 (user "scraper les 53 chaînes pour qu'elles soient déjà prêtes à cliquer, refresh
    //   auto") : PRÉ-RÉSOLUTION en fond de toutes les chaînes du dossier CF. Le 1er passe le CF à
    //   froid (~24s, warm le cf_clearance partagé), les suivantes sont rapides (cf_clearance chaud) →
    //   chaque m3u8 data-stream.top est mis en cache (Stream4FreeResolverCfTest.urlCache). Résultat :
    //   au clic, lecture INSTANTANÉE (cache-hit). Mémoire négligeable (53 courtes URLs). Idempotent.
    @Volatile private var cfPreResolveRunning = false
    private fun launchCfPreResolve() {
        if (cfPreResolveRunning) return
        cfPreResolveRunning = true
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val srcs = fastChannelUrls.values.filter { it.startsWith("stream4cf://") }.distinct()
                Log.d(TAG, "Stream4Free CF: pré-résolution de ${srcs.size} chaînes en fond…")
                var ok = 0
                for (src in srcs) {
                    try {
                        if (com.streamflixreborn.streamflix.utils.Stream4FreeResolverCfTest.resolve(src) != null) ok++
                    } catch (_: Throwable) {}
                }
                Log.d(TAG, "Stream4Free CF: pré-résolution finie — $ok/${srcs.size} chaînes prêtes")
            } catch (_: Throwable) {} finally { cfPreResolveRunning = false }
        }
    }

    suspend fun fetchStream4CategoriesPublic(): List<Category> {
        val now = System.currentTimeMillis()
        if (stream4CacheSections.isNotEmpty() && now - stream4CacheTs < MIX_FR_TTL_MS) {
            return stream4CacheSections
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val req = okhttp3.Request.Builder().url(STREAM4_M3U_URL)
                    .header("User-Agent", "Mozilla/5.0").build()
                val body = replayClient.newCall(req).execute().use { it.body?.string().orEmpty() }
                val cats = parseFastM3u(body)
                if (cats.isNotEmpty()) { stream4CacheSections = cats; stream4CacheTs = now }
                Log.d(TAG, "Stream4Free: ${cats.size} catégories")
                cats
            } catch (e: Throwable) {
                Log.w(TAG, "Stream4 fetch failed: ${e.message}")
                stream4CacheSections
            }
        }
    }

    // 2026-07-10 (user "supprime l'intégration LumiChat partout, même sur git") :
    //   parseLumiChatGrouped + fetchLumiChatCategoriesPublic RETIRÉS (passerelle en panne).

    /** 2026-06-26 : Plex TV — lineup récupéré DEPUIS la connexion de l'appareil
     *  (géo-correct, respecte un éventuel VPN/WARP). Le scraper US ne pouvant pas
     *  fournir le bon lineup, le dossier Plex passe par ici au lieu de data-fast.m3u.
     *  Émet des chaînes `livehub::fast::plex_<id>` dont le src = `plex://<id>`,
     *  résolu à la lecture par PlexTvResolver (token frais + master m3u8). */
    suspend fun fetchPlexLineupCategoryPublic(): List<Category> {
        val now = System.currentTimeMillis()
        // Fail-safe (pattern Stream4Free) : cache frais → retour direct.
        if (plexFolderCacheSections.isNotEmpty() && now - plexFolderCacheTs < MIX_FR_TTL_MS) {
            return plexFolderCacheSections
        }
        // 2026-06-26 (user "que le français" + "les chaînes TV ensemble") :
        //   chaînes Plex live FR, TOUTES ENSEMBLE (pas de catégories pour le live ;
        //   les catégories Action/Comédies = VOD "À la demande", chantier séparé).
        val channels = try {
            com.streamflixreborn.streamflix.utils.PlexTvResolver.getChannels()
                .filter { it.language == "fr" }
        } catch (e: Throwable) { Log.w(TAG, "Plex channels failed: ${e.message}"); emptyList() }
        val out = ArrayList<Category>()
        if (channels.isNotEmpty()) {
            val shows = channels.map { ch ->
                val fastId = "livehub::fast::plex_${ch.id}"
                fastChannelUrls[fastId] = "plex://${ch.id}"
                fastChannelNames[fastId] = ch.title
                if (ch.logo.isNotBlank()) fastChannelLogos[fastId] = ch.logo
                TvShow(id = fastId, title = ch.title).apply {
                    providerName = "TV Hub"
                    poster = ch.logo
                    banner = ch.logo
                }
            }
            out.add(Category(name = "Chaînes en direct", list = shows))
        }
        // 2026-06-27 (user "t'as oublié les films et séries de plex, un seul dossier
        //   live+films+séries en FR") : ajoute les catégories VOD (films par genre).
        //   Catalogue géo-FR (device en France via VPN). Items = `plexvod://<id>`.
        out.addAll(try { fetchPlexVodCategories() } catch (e: Throwable) { Log.w(TAG, "Plex VOD failed: ${e.message}"); emptyList() })
        // 2026-06-27 (user "on n'a pas oublié les séries ?") : OUI il y a des séries
        //   Plex on-demand → catégories séries (hubs _tv + éditoriaux séries).
        out.addAll(try { fetchPlexShowCategories() } catch (e: Throwable) { Log.w(TAG, "Plex shows failed: ${e.message}"); emptyList() })
        // Fail-safe : cache QUE si contenu, sinon dernier cache connu.
        return if (out.isNotEmpty()) { plexFolderCacheSections = out; plexFolderCacheTs = now; out }
        else plexFolderCacheSections
    }

    /** 2026-06-27 : catégories SÉRIES Plex (hubs séries par genre + éditoriaux).
     *  Chaque série = TvShow `livehub::replay::plexshow::<rk>` (ouvre fiche
     *  saisons/épisodes via buildPlexReplayShow, comme France TV). */
    suspend fun fetchPlexShowCategories(): List<Category> = coroutineScope {
        val hubs = listOf(
            "binge-worthy-shows" to "Séries — Incontournables",
            "drama_tv" to "Séries — Drame",
            "crime_tv" to "Séries — Policier",
            "comedy-gold" to "Séries — Comédie",
            "sci-fi_tv" to "Séries — Science-fiction",
            "reality-tv" to "Séries — Télé-réalité",
            "anime" to "Séries — Animé",
            "true-crime" to "Séries — True Crime",
            "masters-of-food" to "Séries — Cuisine",
        )
        hubs.map { (slug, label) ->
            async {
                val shows = com.streamflixreborn.streamflix.utils.PlexTvResolver.getVodShows(slug)
                if (shows.isEmpty()) return@async null
                val list = shows.map { sh ->
                    val showId = "livehub::replay::plexshow::${sh.id}"
                    replayProgramSrcs["plexshow::${sh.id}"] = "plexshow://${sh.id}"
                    replayProgramTitles["plexshow::${sh.id}"] = sh.title
                    TvShow(
                        id = showId,
                        title = if (sh.year > 0) "${sh.title} (${sh.year})" else sh.title,
                    ).apply {
                        providerName = "TV Hub"
                        poster = sh.thumb
                        banner = sh.thumb
                    }
                }
                Category(name = label, list = list)
            }
        }.mapNotNull { it.await() }
    }

    private val plexSeasonEpisodesCache = java.util.concurrent.ConcurrentHashMap<String, List<Episode>>()

    /** 2026-06-27 : construit la fiche série Plex (saisons + épisodes) façon
     *  France TV. showId = "livehub::replay::plexshow::<rk>". */
    private suspend fun buildPlexReplayShow(showId: String, showRk: String): TvShow {
        val title = replayProgramTitles["plexshow::$showRk"] ?: "Série"
        val seasons = com.streamflixreborn.streamflix.utils.PlexTvResolver.getSeasons(showRk)
        if (seasons.isEmpty()) {
            return TvShow(id = showId, title = title).apply { providerName = "TV Hub" }
        }
        val multi = seasons.size > 1
        var firstPoster: String? = null
        val builtSeasons = seasons.map { se ->
            val eps = com.streamflixreborn.streamflix.utils.PlexTvResolver.getEpisodes(se.id, se.index)
            val episodes = eps.map { ep ->
                val epKey = "plexep::${ep.id}"
                replayProgramSrcs[epKey] = "plexvod://${ep.id}"
                replayProgramTitles[epKey] = ep.title
                if (firstPoster == null && ep.thumb.isNotBlank()) firstPoster = ep.thumb
                Episode(
                    id = "livehub::replay::$epKey",
                    number = ep.episode,
                    title = ep.title,
                    poster = ep.thumb.ifBlank { null },
                ).also { it.overview = ep.overview.ifBlank { null } }
            }
            val seasonKey = "$showId::s${se.index}"
            plexSeasonEpisodesCache[seasonKey] = episodes
            Season(
                id = seasonKey,
                number = se.index,
                title = if (multi) (se.title.ifBlank { "Saison ${se.index}" }) else "Tous les épisodes",
                episodes = episodes,
            )
        }
        Log.d(TAG, "buildPlexReplayShow $showRk: ${builtSeasons.size} saison(s)")
        return TvShow(id = showId, title = title, seasons = builtSeasons).apply {
            providerName = "TV Hub"
            firstPoster?.let { this.poster = it; this.banner = it }
        }
    }

    // ===================== PLUTO TV (live + VOD) — 2026-06-27 =====================
    private val plutoSeasonEpisodesCache = java.util.concurrent.ConcurrentHashMap<String, List<Episode>>()

    /** Dossier Pluto unique : catégories préfixées "Live — " / "Films — " / "Séries — "
     *  (displayPlutoFolder les range en 3 sous-dossiers). Tout via l'API officielle
     *  Pluto, géo-FR forcée au boot. */
    suspend fun fetchPlutoFolderCategoriesPublic(): List<Category> = coroutineScope {
        val now = System.currentTimeMillis()
        // Fail-safe (pattern Stream4Free) : cache frais → retour direct.
        if (plutoFolderCacheSections.isNotEmpty() && now - plutoFolderCacheTs < MIX_FR_TTL_MS) {
            return@coroutineScope plutoFolderCacheSections
        }
        val resolver = com.streamflixreborn.streamflix.utils.PlutoTvResolver
        // Chaque branche est indépendante : si le LIVE échoue, le VOD reste (et
        //   inversement). Un await() qui throw ne tue plus tout le dossier.
        val liveDef = async { try { resolver.getLiveCategories() } catch (e: Throwable) { Log.w(TAG, "Pluto live failed: ${e.message}"); emptyList() } }
        val vodDef = async { try { resolver.getVodCategories() } catch (e: Throwable) { Log.w(TAG, "Pluto vod failed: ${e.message}"); emptyList() } }
        val out = ArrayList<Category>()
        val liveCats = liveDef.await()
        val vodCats = vodDef.await()
        // LIVE
        for (cat in liveCats) {
            val shows = cat.channels.map { ch ->
                val fastId = "livehub::fast::plutolive_${ch.id}"
                fastChannelUrls[fastId] = "plutolive://${ch.id}"
                fastChannelNames[fastId] = ch.name
                if (ch.logo.isNotBlank()) fastChannelLogos[fastId] = ch.logo
                TvShow(id = fastId, title = ch.name).apply {
                    providerName = "TV Hub"; poster = ch.logo; banner = ch.logo
                }
            }
            if (shows.isNotEmpty()) out.add(Category(name = "Live — ${cat.name}", list = shows))
        }
        // VOD : sépare films et séries de chaque catégorie Pluto
        for (cat in vodDef.await()) {
            val films = ArrayList<TvShow>()
            val series = ArrayList<TvShow>()
            for (it2 in cat.items) {
                if (it2.isSeries) {
                    val showId = "livehub::replay::plutoshow::${it2.id}"
                    replayProgramSrcs["plutoshow::${it2.id}"] = "plutoshow://${it2.id}"
                    replayProgramTitles["plutoshow::${it2.id}"] = it2.name
                    series.add(TvShow(id = showId, title = it2.name).apply {
                        providerName = "TV Hub"; poster = it2.thumb; banner = it2.thumb
                    })
                } else {
                    val fastId = "livehub::fast::plutomovie_${it2.id}"
                    fastChannelUrls[fastId] = "plutovod://m/${it2.id}"
                    fastChannelNames[fastId] = it2.name
                    if (it2.thumb.isNotBlank()) fastChannelLogos[fastId] = it2.thumb
                    films.add(TvShow(id = fastId, title = it2.name).apply {
                        providerName = "TV Hub"; poster = it2.thumb; banner = it2.thumb
                    })
                }
            }
            if (films.isNotEmpty()) out.add(Category(name = "Films — ${cat.name}", list = films))
            if (series.isNotEmpty()) out.add(Category(name = "Séries — ${cat.name}", list = series))
        }
        if (out.isNotEmpty()) {
            plutoFolderCacheSections = out; plutoFolderCacheTs = now
            return@coroutineScope out
        }
        // 2026-06-29 (REPAIR — user "data-fast.m3u a 129 chaînes Pluto, c'est l'app
        //   le souci") : l'API officielle Pluto revient vide (géo/session) → on
        //   BASCULE sur les chaînes data-fast.m3u (group "Pluto TV - X"), jouables
        //   via la branche html.bet → resolvePlutoStream de getVideo. Le dossier
        //   s'ouvre toujours, comme Samsung TV+.
        val fastPluto = try {
            fetchFastCategories()
                .filter { it.name.startsWith("Pluto TV", ignoreCase = true) }
                .map { c ->
                    val clean = c.name.removePrefix("Pluto TV - ").ifBlank { c.name }
                    Category(name = clean, list = c.list)
                }
        } catch (e: Throwable) { Log.w(TAG, "Pluto FAST fallback failed: ${e.message}"); emptyList() }
        if (fastPluto.isNotEmpty()) {
            plutoFolderCacheSections = fastPluto; plutoFolderCacheTs = now
            fastPluto
        } else plutoFolderCacheSections
    }

    /** Fiche série Pluto (saisons + épisodes). showId = "livehub::replay::plutoshow::<id>". */
    private suspend fun buildPlutoReplayShow(showId: String, seriesId: String): TvShow {
        val title = replayProgramTitles["plutoshow::$seriesId"] ?: "Série"
        val seasons = com.streamflixreborn.streamflix.utils.PlutoTvResolver.getSeasons(seriesId)
        if (seasons.isEmpty()) {
            return TvShow(id = showId, title = title).apply { providerName = "TV Hub" }
        }
        val multi = seasons.size > 1
        var firstPoster: String? = null
        val built = seasons.map { se ->
            val episodes = se.episodes.map { ep ->
                val epKey = "plutoep::${ep.id}"
                replayProgramSrcs[epKey] = "plutovod://e/${ep.id}"
                replayProgramTitles[epKey] = ep.name
                if (firstPoster == null && ep.thumb.isNotBlank()) firstPoster = ep.thumb
                Episode(
                    id = "livehub::replay::$epKey",
                    number = ep.episode,
                    title = ep.name,
                    poster = ep.thumb.ifBlank { null },
                ).also { it.overview = ep.overview.ifBlank { null } }
            }
            val seasonKey = "$showId::s${se.number}"
            plutoSeasonEpisodesCache[seasonKey] = episodes
            Season(
                id = seasonKey,
                number = se.number,
                title = if (multi) "Saison ${se.number}" else "Tous les épisodes",
                episodes = episodes,
            )
        }
        Log.d(TAG, "buildPlutoReplayShow $seriesId: ${built.size} saison(s)")
        return TvShow(id = showId, title = title, seasons = built).apply {
            providerName = "TV Hub"
            firstPoster?.let { this.poster = it; this.banner = it }
        }
    }

    /** 2026-06-27 : catégories VOD Plex (films par genre) pour le dossier "Plex TV".
     *  Chaque genre = 1 Category de TvShow `livehub::fast::plexvod_<id>` (src
     *  `plexvod://<id>`, résolu à la lecture en HLS VOD par PlexTvResolver). */
    suspend fun fetchPlexVodCategories(): List<Category> = coroutineScope {
        // slug Plex -> libellé FR. (en-espanol/descriptive-audio écartés.)
        // slugs VALIDÉS sur l'API vod (les autres = 404 : thriller/animation/
        //   crime/romance/musical n'existent pas comme hub films Plex).
        val genres = listOf(
            "action" to "Action", "comedy" to "Comédie", "drama" to "Drame",
            "sci-fi" to "Science-fiction", "horror" to "Horreur", "adventure" to "Aventure",
            "documentary" to "Documentaires", "western" to "Western", "war-films" to "Guerre",
        )
        genres.map { (slug, label) ->
            async {
                val items = com.streamflixreborn.streamflix.utils.PlexTvResolver
                    .getVodCategory(slug)
                if (items.isEmpty()) return@async null
                val shows = items.map { it2 ->
                    val fastId = "livehub::fast::plexvod_${it2.id}"
                    fastChannelUrls[fastId] = "plexvod://${it2.id}"
                    fastChannelNames[fastId] = it2.title
                    if (it2.thumb.isNotBlank()) fastChannelLogos[fastId] = it2.thumb
                    TvShow(
                        id = fastId,
                        title = if (it2.year > 0) "${it2.title} (${it2.year})" else it2.title,
                    ).apply {
                        providerName = "TV Hub"
                        poster = it2.thumb
                        banner = it2.thumb
                    }
                }
                Category(name = "Films — $label", list = shows)
            }
        }.mapNotNull { it.await() }
    }

    /** Warm-up au cold start — pré-charge le M3U FAST en background. */
    suspend fun warmFastCache() {
        try {
            fetchFastCategories(forceRefresh = true)
        } catch (e: Throwable) {
            Log.w(TAG, "warmFastCache failed: ${e.message}")
        }
    }
}
