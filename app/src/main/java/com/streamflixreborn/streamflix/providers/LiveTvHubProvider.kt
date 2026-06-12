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

    private fun freeshotToTvShow(c: FreeshotChannel): TvShow {
        // Réutilise les logos officiels via la même map que les autres providers,
        // sinon fallback avatar avec initiales.
        val logoKey = when {
            c.slug == "tf1" -> "tf1"
            c.slug == "france-2" -> "france2"
            c.slug == "france-3" -> "france3"
            c.slug == "france-4" -> "france4"
            c.slug == "france-5" -> "france5"
            c.slug == "m6" -> "m6"
            c.slug == "bfmtv" -> "bfmtv"
            c.slug == "bfm-business" -> "bfmbusiness"
            c.slug == "cnews" -> "cnews"
            c.slug == "france-info" -> "franceinfo"
            c.slug == "france-24" -> "france24"
            c.slug == "canal-fr" -> "canal"
            c.slug == "canal-docs-fr" -> "canalcinema"
            else -> null
        }
        val logoUrl = logoKey?.let { manualLogoMap[it] }
            ?: "https://ui-avatars.com/api/?name=${java.net.URLEncoder.encode(c.displayName.take(3), "UTF-8")}&background=0EA5E9&color=fff&size=512&bold=true&format=png"
        return TvShow(
            id = "livehub::freeshot::${c.id}::${c.slug}",
            title = c.displayName,
            overview = "Chaîne TV — diffusée via freeshot.live.",
            quality = "Live",
            poster = logoUrl,
            banner = logoUrl,
            providerName = name,
        )
    }

    private fun bonusToTvShow(c: BonusChannel): TvShow {
        val logoUrl = c.logoKey?.let { manualLogoMap[it] }
            ?: "https://ui-avatars.com/api/?name=${java.net.URLEncoder.encode(c.displayName, "UTF-8")}&background=DC2626&color=fff&size=512&bold=true&format=png"
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
        val logo = "https://ui-avatars.com/api/?name=SF&background=0EA5E9&color=fff&size=512&bold=true&format=png"
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

        // 2026-06-08 (user "Canal+ en position 0 de OTF") : déclaré hors du
        //   try pour rester accessible quand on construit otfShows plus bas.
        var canalPlusBonusForOtf: TvShow? = null
        // 2026-06-08 (user "LCI tu peux la mettre") : capturée depuis TF1+
        //   blacklistée mais sauvée car son shortcut TF1 mediainfo marche.
        //   Insérée en position 1 de OTF (juste après Canal+).
        var lciForOtf: TvShow? = null

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
        // 2026-06-08 (user "fusionne Canal+ à OTF en position 0, supprime cat
        //   Cinéma qui prend la place pour rien") : capture Canal+ bonus (id=11)
        //   pour l'insérer plus bas en position 0 de la section "OTF TV - France".
        //   Pas de section "Cinéma" dédiée. Routing inchangé : ID reste
        //   `livehub::bonus::11` → bonusShow dans getTvShow → pipeline natif.
        canalPlusBonusForOtf = bonusByCat["Cinéma"]
            ?.firstOrNull { it.id == 11 }
            ?.let { bonusToTvShow(it) }
        // 2026-05-31 : Canal+ Live section RETIRÉE (chaînes ne fonctionnent pas)

        // 2026-05-15 : 48 chaînes freeshot.live (TF1, M6, BFM*, CANAL+ FR/DOCS,
        // DAZN, Eurosport, France 2-5, L'Equipe Live, CNews, etc.) merged dans
        // les catégories existantes.
        val freeshotBanned = { id: String -> bonusBanned(id) }
        val freeshotByCat = freeshotChannels
            .filterNot { freeshotBanned("livehub::freeshot::${it.id}::${it.slug}") }
            .groupBy { it.category }
        for ((cat, list) in freeshotByCat) {
            val tvShows = list.map { freeshotToTvShow(it) }
            val existingIdx = sections.indexOfFirst { it.name == cat }
            if (existingIdx >= 0) {
                sections[existingIdx] = Category(
                    name = cat,
                    list = sections[existingIdx].list + tvShows,
                )
            } else {
                sections.add(Category(name = cat, list = tvShows))
            }
        }

        } catch (e: Exception) {
            Log.w(TAG, "Bonus/freeshot sections failed: ${e.message}")
        }

        // 2026-05-31 : OTF TV — chaînes dynamiques depuis l'API OTF.
        // 2026-06-04 : timeout 8s → 4s (couplé au cache négatif 60s dans
        //   OtfTvService) pour ne pas faire patienter quand OTF est down.
        val otfService = com.streamflixreborn.streamflix.utils.OtfTvService
        val otfChannelsFromApi = try {
            kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                otfService.fetchChannels()
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val selectedGroup = otfService.selectedGroup.ifBlank { "France" }
        val otfFiltered = otfChannelsFromApi.filter { it.group == selectedGroup }

        // normalizedKey = ID unique par chaîne (catId est l'ID du GROUPE, pas de la chaîne)
        val seen = HashSet<String>()
        val otfShows = otfFiltered.filter { seen.add(it.normalizedKey) }
            .mapNotNull { ch ->
                val id = "livehub::otf::${ch.normalizedKey}"
                // 2026-06-08 : skip si bannie
                if (com.streamflixreborn.streamflix.fragments.player.settings
                        .IptvBannedChannels.isBanned(id)) return@mapNotNull null
                TvShow(id = id, title = ch.name).apply {
                    providerName = "TV Hub"
                    poster = ch.logo
                }
            }
        // 2026-06-08 (user "Canal+ en première chaîne, décaler un peu OTF") :
        //   prepend Canal+ bonus en position 0. Garde le même look visuel
        //   que les chaînes OTF (rebadgé TV Hub via bonusToTvShow).
        // 2026-06-08 (user "LCI tu peux la mettre") : LCI en position 1
        //   juste après Canal+ (sauvée de TF1+ blacklistée).
        val prepended = mutableListOf<TvShow>()
        if (canalPlusBonusForOtf != null) prepended.add(canalPlusBonusForOtf!!)
        if (lciForOtf != null) prepended.add(lciForOtf!!)
        val otfShowsFinal = if (prepended.isNotEmpty()) prepended + otfShows else otfShows
        val otfSectionName = "OTF TV - $selectedGroup"
        sections.add(Category(name = otfSectionName, list = otfShowsFinal))

        // 2026-05-31 : section "Favoris" OTF — inclut les chaînes OTF marquées favorites
        try {
            // getFavorites retourne "livehub::" + clé normalisée (ex: "livehub::otfcanaldecale")
            val allFavs = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                .getFavorites("TV Hub")
            val otfFavKeys = allFavs
                .filter { it.startsWith("livehub::otf") }
                .map { it.removePrefix("livehub::otf") } // "canaldecale"
            if (otfFavKeys.isNotEmpty()) {
                val otfFavShows = otfFavKeys.mapNotNull { favKey ->
                    val ch = otfChannelsFromApi.firstOrNull { it.normalizedKey == favKey }
                    ch?.let {
                        TvShow(id = "livehub::otf::${it.normalizedKey}", title = it.name).apply {
                            providerName = "TV Hub"
                            poster = it.logo
                        }
                    }
                }
                if (otfFavShows.isNotEmpty()) {
                    // Insérer en haut de la liste (après les sections OLA/Vegeta existantes, avant OTF)
                    val otfIdx = sections.indexOfFirst { it.name.startsWith("OTF TV") }
                    if (otfIdx >= 0) {
                        sections.add(otfIdx, Category(name = "Favoris", list = otfFavShows))
                    } else {
                        sections.add(Category(name = "Favoris", list = otfFavShows))
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2026-06-08 (user "remettre la source 3BoxTV dans le TV hub sous le
        //   nom France TV") : on délègue à BoxXtemusProvider (restauré depuis
        //   git après suppression v1.7.192). 12 catégories FR (TF1+, Canal+,
        //   M6+, RTBF/Suisse, TNT Sat, Cinéma, Replay, Sports, etc.). Le
        //   préfixe `livehub::francetv::` reroute getTvShow/getServers/getVideo
        //   vers BoxXtemusProvider sans dupliquer la logique.
        try {
            val francetvSections = com.streamflixreborn.streamflix.providers
                .BoxXtemusProvider.getHome()
            // 2026-06-08 (user "cette catégorie-là retirée") : sections du
            //   JSON 3BoxTV à NE PAS exposer dans le Hub France TV (doublons,
            //   irrelevant, etc.). Étendre cette liste si besoin.
            val francetvBlacklistedSections = setOf(
                "IPTV Daily",  // doublon — pas de la VRAIE France TV
                // 2026-06-10 (user "Sport peut revenir, ça va sûrement marcher") :
                //   "Sports" RETIRÉ du blacklist. Le bypass WebView qu'on vient
                //   d'implémenter résout maintenant le pipeline html.bet, donc
                //   les chaînes Sport devraient fonctionner.
            )
            for (cat in francetvSections) {
                // Skip section "Favoris" interne de BoxXtemus — les favoris du
                // Hub sont gérés au niveau hub, pas du sous-provider.
                if (cat.name.equals("Favoris", ignoreCase = true)) continue
                // 2026-06-08 : avant de skip une cat blacklistée, on capture
                //   LCI si elle est dans TF1+ (= sauvée par mediainfo shortcut).
                if (cat.name.equals("TF1+", ignoreCase = true)) {
                    val lci = (cat.list as? List<*>)?.mapNotNull { it as? TvShow }
                        ?.firstOrNull {
                            it.title?.uppercase()?.contains("LCI") == true ||
                            it.id.contains("lci", ignoreCase = true)
                        }
                    if (lci != null) {
                        lciForOtf = TvShow(
                            id = "livehub::francetv::${lci.id}",
                            title = "LCI",
                        ).apply {
                            providerName = "TV Hub"
                            poster = lci.poster
                            banner = lci.banner
                        }
                    }
                }
                // 2026-06-08 : trim + lowercase pour blinder le match (le
                //   nom de cat peut avoir un trailing space dans le JSON).
                val catNameNorm = cat.name.trim().lowercase()
                if (francetvBlacklistedSections.any { it.trim().lowercase() == catNameNorm }) continue
                // 2026-06-10 : filtre catégorie utilisateur (BoxXtemusCategorySettings).
                val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
                if (!com.streamflixreborn.streamflix.providers
                        .BoxXtemusCategorySettings.matches(ctx, cat.name)) continue
                val rebadged = (cat.list as? List<*>)?.mapNotNull { item ->
                    val tv = item as? TvShow ?: return@mapNotNull null
                    val newId = "livehub::francetv::${tv.id}"
                    // 2026-06-08 (user "active ban pour TV hub") : skip si bannie
                    if (com.streamflixreborn.streamflix.fragments.player.settings
                            .IptvBannedChannels.isBanned(newId)) return@mapNotNull null
                    TvShow(id = newId, title = tv.title ?: "").apply {
                        providerName = "TV Hub"
                        poster = tv.poster
                        banner = tv.banner
                    }
                } ?: emptyList()
                if (rebadged.isNotEmpty()) {
                    sections.add(Category(name = "France TV - ${cat.name}", list = rebadged))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "France TV (BoxXtemus) getHome failed: ${t.message}")
        }

        // 2026-06-08 (user "appareil non compatible avec l'API complexe de
        //   3TVBOX V2 → essayer Dric4rTV plus simple") : sections Dric4rTV
        //   (Ligue1+, US Sports & NBA, Horse, Nature, Live & DJ, Muzik,
        //   R4di0, Locales TV). Format JSON simple, URLs directes — pas
        //   de pipeline html.bet. Les IDs sont déjà préfixés `livehub::dric4rtv::`
        //   dans Dric4rTvProvider, donc on les passe tels quels.
        try {
            val dricSections = com.streamflixreborn.streamflix.providers
                .Dric4rTvProvider.getHome()
            for (cat in dricSections) {
                val rebadged = (cat.list as? List<*>)?.mapNotNull { item ->
                    val tv = item as? TvShow ?: return@mapNotNull null
                    // 2026-06-08 : skip si bannie
                    if (com.streamflixreborn.streamflix.fragments.player.settings
                            .IptvBannedChannels.isBanned(tv.id)) return@mapNotNull null
                    // L'id contient déjà "livehub::dric4rtv::..." — pas besoin de re-préfixer.
                    TvShow(id = tv.id, title = tv.title).apply {
                        providerName = "TV Hub"
                        poster = tv.poster
                        banner = tv.banner
                    }
                } ?: emptyList()
                if (rebadged.isNotEmpty()) {
                    // 2026-06-08 (user "renomme Dric4rTV en France TV pour
                    //   unifier") : ces sections viennent du provider Dric4rTV
                    //   mais on les affiche comme "France TV - X" pour fondre
                    //   visuellement avec les autres sections France TV.
                    sections.add(Category(name = "France TV - ${cat.name}", list = rebadged))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Dric4rTV getHome failed: ${t.message}")
        }

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
        return sections
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
        return all.filter { (it.title ?: "").lowercase().contains(q) }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getMovie(id: String): Movie =
        throw UnsupportedOperationException("LiveTvHub n'a pas de films")

    override suspend fun getTvShow(id: String): TvShow {
        // 2026-05-15 : gestion bonus channels (bolaloca + dailymotion + freeshot)
        val bonusShow = when {
            id.startsWith("livehub::bonus::") -> {
                val num = id.removePrefix("livehub::bonus::").toIntOrNull()
                bonusChannels.firstOrNull { it.id == num }?.let { bonusToTvShow(it) }
            }
            id.startsWith("livehub::dailymotion::") -> dailymotionChannelToTvShow()
            id.startsWith("livehub::freeshot::") -> {
                val fid = id.removePrefix("livehub::freeshot::").substringBefore("::")
                freeshotChannels.firstOrNull { it.id == fid }?.let { freeshotToTvShow(it) }
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
        if (server.id.startsWith("livehub::bonus::") ||
            server.id.startsWith("livehub::dailymotion::") ||
            server.id.startsWith("livehub::freeshot::")) {
            return com.streamflixreborn.streamflix.extractors.Extractor.extract(server.src, server)
        }
        return IptvCrossDelegate.delegateGetVideo(server)
            ?: throw Exception("No IPTV provider can handle server: ${server.id}")
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
    fun getOrderedChannelIds(currentChannelId: String? = null): List<String> {
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
            val groupPrefix = currentChannelId?.let { id ->
                when {
                    id.startsWith("livehub::francetv::") -> "livehub::francetv::"
                    id.startsWith("livehub::otf::") -> "livehub::otf::"
                    id.startsWith("livehub::dric4rtv::") -> "livehub::dric4rtv::"
                    else -> null
                }
            }
            return if (groupPrefix != null) {
                cache.filter { it.first.startsWith(groupPrefix) }.map { it.first }
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
        return channelById[channelId]?.displayName
    }

    /** Returns le poster (logo URL) de la chaîne pour [channelId]. */
    fun getChannelPoster(channelId: String): String? {
        val c = channelById[channelId] ?: return null
        return logoUrlFor(c.witvKey, c.displayName)
    }

    /** 2026-05-18 v85 : LiveTvHub agrège WiTV/Ola/Vegeta, le clear est délégué à eux. */
    fun clearCache() {
        // no-op : pas de cache propre (forward via WiTV/Ola/Vegeta).
    }
}
