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

    /**
     * 2026-06-14 (user "donc maintenant tu peux retirer la source du TV hub
     * c'est plus gênant") : flag pour réactiver l'injection des sections
     * Dric4rTV dans le TvHub. Désactivé par défaut car :
     *  - Les 17 radios sont hardcodées dans RadioCatalog.HARDCODED_DRIC4RTV_RADIOS
     *    (URLs CDN directes radiofrance/infomaniak/RTL/etc.).
     *  - Les chaînes TV intéressantes (178) sont sur le data.m3u Codeberg,
     *    accessibles via le provider World Live (MyIptvProvider URL externe).
     *  - 23 "Live & DJ set" droppées (= .mp4 statiques freebox Dric, pas live).
     *
     * Le code Dric4rTvProvider.kt reste en place pour compat des anciens
     * favoris cœur (IDs `livehub::dric4rtv::*`) : getServers/getVideo
     * continuent à fonctionner si un favori est cliqué depuis le cœur.
     */
    private const val DRIC4RTV_IN_HUB = false

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
            ?: "https://ui-avatars.com/api/?name=${java.net.URLEncoder.encode(c.displayName.take(3), "UTF-8")}&background=0EA5E9&color=fff&size=128&bold=true&format=png"
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
        val adarGroup = try { com.streamflixreborn.streamflix.utils.AdrarTvService.selectedGroup } catch (_: Throwable) { "" }
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
        return "$favSig|$otfGroup|$adarGroup|$bannedHash|$catFilter"
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

        // 2026-06-08 (user "Canal+ en position 0 de OTF") : déclaré hors du
        //   try pour rester accessible quand on construit otfShows plus bas.
        var canalPlusBonusForOtf: TvShow? = null
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
        // 2026-06-19 v36 (user "chargement infini après refresh TV Hub") :
        //   OtfTvService a déclenché un OutOfMemoryError dans le buffer
        //   OkHttp cache → catch (Exception) ne le rattrape pas (= Error,
        //   pas Exception) → le worker crash sans cancel le withTimeoutOrNull
        //   → getHome bloqué infiniment. Fix : catch Throwable.
        val tOtf = System.currentTimeMillis()
        val otfChannelsFromApi = try {
            kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                otfService.fetchChannels()
            } ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "OTF fetch failed (caught Throwable: ${t.javaClass.simpleName}: ${t.message})")
            // 2026-06-19 v36 : marque OTF comme failed pour éviter retry
            //   immédiat à chaque refresh home (= cooldown 60s).
            try { otfService.markFailure() } catch (_: Throwable) {}
            emptyList()
        }
        Log.d(TAG, "getHome: OTF fetched in ${System.currentTimeMillis() - tOtf}ms (${otfChannelsFromApi.size} channels)")

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
        // 2026-06-14 : LCI prepended retiré (= venait du bloc BoxXtemus 3boxTv,
        //   retiré dans ce commit). Si LCI doit revenir dans OTF un jour, le
        //   capturer autrement (= via WiTV / OLA / Vegeta qui ont LCI nativement).
        val prepended = mutableListOf<TvShow>()
        if (canalPlusBonusForOtf != null) prepended.add(canalPlusBonusForOtf!!)
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

        // 2026-06-19 : Adrar TV — agrégateur IPTV. Comme OTF : un seul groupe
        //   affiché à la fois (via selectedGroup) + picker via clic sur le
        //   titre "Adrar TV - <group>". Géré par CategoryViewHolder.
        // 2026-06-19 v37 : wrap timeout 4s + catch Throwable comme OTF/Replay.
        try {
            val adarService = com.streamflixreborn.streamflix.utils.AdrarTvService
            val adarChannels = kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                adarService.fetchChannels()
            } ?: emptyList()
            val adarSelectedGroup = adarService.selectedGroup.ifBlank { "France" }
            val adarFiltered = adarChannels.filter { it.group == adarSelectedGroup }
            val adarShows = adarFiltered.mapNotNull { ch ->
                val id = "livehub::adar::${adarService.keyOf(ch)}"
                if (com.streamflixreborn.streamflix.fragments.player.settings
                        .IptvBannedChannels.isBanned(id)) return@mapNotNull null
                TvShow(id = id, title = ch.name).apply {
                    providerName = "TV Hub"
                    poster = ch.logo
                    banner = ch.logo
                }
            }
            if (adarShows.isNotEmpty()) {
                sections.add(Category(name = "Adrar TV - $adarSelectedGroup", list = adarShows))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Adrar TV fetch failed (Throwable: ${t.javaClass.simpleName}): ${t.message}")
        }

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
        if (DRIC4RTV_IN_HUB) {
            try {
                val dricSections = com.streamflixreborn.streamflix.providers
                    .Dric4rTvProvider.getHome()
                for (cat in dricSections) {
                    val rebadged = (cat.list as? List<*>)?.mapNotNull { item ->
                        val tv = item as? TvShow ?: return@mapNotNull null
                        if (com.streamflixreborn.streamflix.fragments.player.settings
                                .IptvBannedChannels.isBanned(tv.id)) return@mapNotNull null
                        TvShow(id = tv.id, title = tv.title).apply {
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
                Log.w(TAG, "Dric4rTV getHome failed: ${t.message}")
            }
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

        // 2026-06-17 (Phase 1 Replay - port Catchup TV & More Kotlin) :
        //   fetch data-replay.m3u depuis nx-data → catégories "Replay France X".
        //   URLs francetv://<si_id> résolues par FrancetvResolver (k7.ftven.fr).
        // 2026-06-19 v37 (user "tvhub bloqué") : wrap dans withTimeoutOrNull 4s
        //   + catch Throwable pour rattraper Errors (OOM cache OkHttp etc.) qui
        //   ne sont pas caught par catch(Exception). Garantit que getHome ne
        //   peut PAS être bloqué par un fetch réseau qui traîne.
        try {
            val replayCats = kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                fetchReplayCategories()
            } ?: emptyList()
            if (replayCats.isNotEmpty()) {
                sections.addAll(replayCats)
                Log.d(TAG, "Replay sections added: ${replayCats.size} catégories")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Replay section failed (Throwable: ${t.javaClass.simpleName}): ${t.message}")
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
                    it.name.equals(currentCode, ignoreCase = true)
                }
                // Cache le résultat filtré aussi
                cachedHome = filtered
                cachedHomeSignature = sig
                cachedHomeAt = System.currentTimeMillis()
                Log.d(TAG, "getHome: built+cached ${filtered.size} sections (filter=$currentCode)")
                return filtered
            }
        }
        // Stocke le résultat dans le cache mémoire pour les 60s suivantes.
        cachedHome = sections.toList()
        cachedHomeSignature = sig
        cachedHomeAt = System.currentTimeMillis()
        Log.d(TAG, "getHome: built+cached ${sections.size} sections in ${System.currentTimeMillis() - tHomeStart}ms (TTL 60s)")
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
        // 2026-06-18 v24 : REPLAY TF1+ programme → fetch les épisodes via TF1+ HTML
        //   Pipeline :
        //   1. fetch https://www.tf1.fr/<show-path>  (ex: tf1/good-american-family)
        //   2. trouve tous les liens .../videos/<slug>-<id>.html (= chaque épisode)
        //   3. extrait pour chaque : titre, S0xE0x, poster
        //   4. retourne TvShow avec 1 saison "Tous les épisodes"
        if (id.startsWith("livehub::replay::tf1/")) {
            val showPath = id.removePrefix("livehub::replay::")
            return buildTf1ReplayShow(id, showPath)
        }
        // 2026-06-19 : REPLAY M6+ programme → fetch les videos via pc.middleware
        //   /programs/{id}/videos, parse les titres "S\d+ E\d+ - title" pour
        //   grouper en saisons.
        if (M6_SERVICES.any { id.startsWith("livehub::replay::$it/") }) {
            return buildM6ReplayShow(id)
        }
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
                        buildM6ReplayShow("livehub::replay::$service/$programId")
                        m6SeasonEpisodesCache[seasonId] ?: emptyList()
                    } catch (e: Exception) {
                        Log.w(TAG, "M6 getEpisodesBySeason rebuild fail: ${e.message}")
                        emptyList()
                    }
                } else emptyList()
            }
        }
        return emptyList()
    }

    // v28 : cache pour retrouver les épisodes par seasonId (= rebuild évité)
    private val tf1SeasonEpisodesCache = java.util.concurrent.ConcurrentHashMap<String, List<Episode>>()
    // 2026-06-19 : cache équivalent pour M6+
    private val m6SeasonEpisodesCache = java.util.concurrent.ConcurrentHashMap<String, List<Episode>>()

    // ====== 2026-06-19 : M6+ replay show parser ======
    private val M6_SERVICES = listOf(
        "m6replay", "w9replay", "6terreplay", "gulli", "tevareplay", "parispremierereplay"
    )

    /** Fetch /programs/<id>/videos via pc.middleware.6play.fr puis parse les
     *  titles "S\d+ E\d+ - title" pour grouper en saisons. */
    private suspend fun buildM6ReplayShow(showId: String): TvShow {
        // Format showId : livehub::replay::<service>/<program_id>
        val rest = showId.removePrefix("livehub::replay::")
        val service = rest.substringBefore("/")
        val programId = rest.substringAfter("/")
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
        // 2026-06-19 : Adrar TV — id `livehub::adar::<key>` → résout via
        //   AdrarTvService.getUrlForChannel(key). Le src est l'URL directe.
        if (id.startsWith("livehub::adar::")) {
            val key = id.removePrefix("livehub::adar::")
            val adarUrl = com.streamflixreborn.streamflix.utils.AdrarTvService
                .getUrlForChannel(key)
            if (adarUrl != null) {
                val (url, _) = adarUrl
                return listOf(
                    Video.Server(
                        id = id,
                        name = "Adrar TV",
                        src = url,
                    )
                )
            }
            return emptyList()
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

        // 2026-06-19 : Adrar TV — URL directe HLS ou TS. Type détecté de l'URL.
        //   Headers (UA spécifique) viennent de AdrarTvService selon la chaîne.
        if (server.id.startsWith("livehub::adar::")) {
            val key = server.id.removePrefix("livehub::adar::")
            val resolved = com.streamflixreborn.streamflix.utils.AdrarTvService
                .getUrlForChannel(key)
            val (src, headers) = resolved ?: (server.src to emptyMap())
            val isHls = src.contains(".m3u8", ignoreCase = true)
            return Video(
                source = src,
                type = if (isHls) "application/vnd.apple.mpegurl" else "video/mp2t",
                headers = headers,
            )
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
            .build()
    }

    /** 2026-06-18 (user "TF1 en premier et dans l'ordre") : ordre canonique
     *  TNT FR. Catégories non listées sont rejetées en queue avec priority
     *  élevée (= au final). */
    private val REPLAY_CATEGORY_ORDER: Map<String, Int> = linkedMapOf(
        "Live TF1+" to 5,
        "Live M6+" to 6,
        "Replay TF1" to 10,
        "Replay TMC" to 11,
        "Replay TFX" to 12,
        "Replay TF1 Séries Films" to 13,
        "Replay LCI" to 14,
        "Replay France 2" to 20,
        "Replay France 3" to 21,
        "Replay France 4" to 22,
        "Replay France 5" to 23,
        "Replay Franceinfo" to 24,
        "Replay M6" to 30,
        "Replay W9" to 31,
        "Replay 6ter" to 32,
        "Replay Gulli" to 33,
        "Replay Paris Première" to 34,
        "Replay Téva" to 35,
        "Arte Cinéma" to 40,
        "Arte Séries et fictions" to 41,
        "Arte Documentaires" to 42,
        "Arte Sciences" to 43,
        "Arte Culture et pop" to 44,
        "Arte Histoire" to 45,
        "Arte Arts" to 46,
        "Arte Société" to 47,
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
                        || t.startsWith("m6play://") || t.startsWith("m6live://"))) {
                val src = t
                val siId = src.substringAfter("://").trim()
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
                    if (tvgType == "movie") isMovie = true
                }
                groups.getOrPut(groupTitle) { mutableListOf() }.add(tv)
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
                    replayProgramTitles[service] = label
                    replayProgramSrcs[service] = "m6live://$service"
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
        return sortedEntries.map { (cat, items) ->
            val list = items.toMutableList()
            if (appCtx != null) {
                // 2026-06-19 (user "les logos de connexion devraient être devant
                //   les live M6 et TF1") : inclus aussi "Live TF1+" / "Live M6+"
                //   pour que la card "🔓 Connexion" apparaisse EN TÊTE de la
                //   section live, devant les chaînes M6/TF1 qui ne marcheront
                //   pas tant que l'user n'est pas connecté.
                val isTf1Plus = cat in setOf("Live TF1+", "Replay TF1", "Replay TMC", "Replay TFX",
                                              "Replay TF1 Séries Films", "Replay LCI")
                val isM6 = cat in setOf("Live M6+", "Replay M6", "Replay W9", "Replay 6ter",
                                        "Replay Gulli", "Replay Paris Première", "Replay Téva")
                val tf1Logged = com.streamflixreborn.streamflix.utils.TF1Auth.isLoggedIn(appCtx)
                val m6Logged = com.streamflixreborn.streamflix.utils.M6Auth.isLoggedIn(appCtx)
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
}
