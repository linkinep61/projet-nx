package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VavooProvider — chaînes IPTV françaises via Vavoo (oha.to / vavoo.to / huhu.to / kool.to).
 *
 * Architecture :
 *  1. POST `https://www.lokke.app/api/app/ping` → addonSig (token signé, cache 5 min)
 *  2. POST `https://oha.to/mediahubmx-catalog.json` (avec mediahubmx-signature header) →
 *     liste paginée des chaînes IPTV, filtrée par pays = "France"
 *  3. POST `https://vavoo.to/mediahubmx-resolve.json` on-demand → URL m3u8 directe
 *
 * Multi-base fallback (oha.to → vavoo.to → huhu.to → kool.to) pour résilience.
 * Multi-ping fallback (lokke.app → vavoo.tv) pour le ping signature.
 *
 * Home organisé en catégories par genre (extraites du `group` field après séparateur
 * pays "➾"), avec tri TNT pour les chaînes principales (TF1, France 2-5, M6, etc.)
 * et catégories Général / Entertainment / News / Sport en tête.
 */
object VavooProvider : Provider, IptvProvider {

    override val name = "Vavoo TV"
    override val baseUrl = "https://oha.to"
    override val logo = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_vavoo"
    override val language = "fr"

    private const val TAG = "VavooProvider"
    private const val VAVOO_UA = "VAVOO/2.6"
    private const val MEDIAHUBMX_UA = "MediaHubMX/2"
    private const val CLIENT_VERSION = "3.0.2"
    private const val APP_VERSION = "3.1.8"

    // ───────── API domains (fallback chain) ─────────
    // 2026-05-10 : vavoo.to et kool.to en premier — testés UP. oha.to et
    // huhu.to retournent 404 sur les routes catalog/resolve (l'addon n'y
    // est plus servi en mai 2026), gardés en fallback ultime.
    private val BASE_SITES = listOf(
        "https://vavoo.to",
        "https://kool.to",
        "https://oha.to",
        "https://huhu.to",
    )

    private val PING_URLS = listOf(
        "https://www.lokke.app/api/app/ping",
        "https://www.vavoo.tv/api/app/ping",
    )

    // ───────── HTTP client ─────────
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    // ───────── IptvProvider contract (additional channel variants progressivement) ─────────
    // Vavoo n'a pas de variants progressifs (catalog complet en une fois), flow vide.
    private val _additionalServersFlow = MutableSharedFlow<Video.Server>(replay = 0)
    override val additionalServersFlow: SharedFlow<Video.Server> = _additionalServersFlow.asSharedFlow()

    // ───────── Channel registry ─────────
    // 2026-05-10 (FIX FREEZE 32s) : canonicalKey est pré-calculé via normalizeKey()
    // UNE SEULE FOIS au chargement de la registry. AVANT : normalizeKey() était
    // appelé ~30 000 fois sur main thread lors de getOrderedChannelIds (compareBy
    // sortedWith sur 983 chaînes × 10 regex/normalize) → freeze 32s.
    data class VavooChannel(
        val id: String,
        val name: String,
        val logo: String,
        val group: String,
        val country: String,
        val url: String,  // vavoo internal URL used for resolve
        val canonicalKey: String, // pré-calculé via normalizeKey(name)
    )

    private val channelRegistry = mutableListOf<VavooChannel>()
    private val registryLock = Any()
    private val registryMutex = Mutex()
    @Volatile private var registryLoaded = false
    @Volatile private var lastLoadTime = 0L
    private const val CACHE_DURATION = 30 * 60 * 1000L // 30 min

    // 2026-05-10 : cache de la liste ordonnée des IDs (pour prev/next channel
    // navigation depuis le grand player). Calculé une seule fois après chaque
    // registry refresh, partagé par getPreviousChannelId et getNextChannelId.
    @Volatile private var cachedOrderedIds: List<String>? = null

    // ───────── Addon signature cache ─────────
    @Volatile private var cachedSignature: String? = null
    @Volatile private var signatureExpiry = 0L

    // ───────── TNT order for sorting (chaînes principales) ─────────
    // 2026-05-10 (user) : "Bah tu analyses comment VAVOO trier les chaînes TV et
    // tu t'adaptes". → Vavoo natif présente les chaînes en ordre TNT officiel +
    // thématiques connues. On reproduit cet ordre, indexé par normalizeKey() pour
    // matcher peu importe les suffixes ".b"/".c"/".s" ou tags "HD"/"FHD".
    // Chaînes hors-liste tombent à 9999 (tri alpha derrière le bloc TNT).
    private val tntOrder: Map<String, Int> by lazy {
        listOf(
            // TNT 1-25 (ordre officiel CSA)
            "TF1", "France 2", "France 3", "Canal+", "France 5", "M6", "Arte",
            "C8", "W9", "TMC", "TFX", "NRJ 12", "LCP", "France 4",
            "BFM TV", "CNews", "CStar", "Gulli",
            "TF1 Séries Films", "L'Équipe", "6ter", "RMC Story",
            "RMC Découverte", "Chérie 25", "LCI", "Franceinfo",
            "Public Sénat", "France Ô",
            // Canal+ extensions
            "Canal+ Cinéma", "Canal+ Séries", "Canal+ Family", "Canal+ Docs",
            "Canal+ Box Office", "Canal+ Grand Écran", "Canal+ Sport",
            "Canal+ Sport 360", "Canal+ Foot",
            "Canal+ Live 1", "Canal+ Live 2", "Canal+ Live 3", "Canal+ Live 4",
            "Canal+ Live 5", "Canal+ Live 6", "Canal+ Live 7", "Canal+ Live 8",
            "Canal+ Live 9", "Canal+ Live 10", "Canal+ Live 11",
            "Canal+ Live 12", "Canal+ Live 13",
            // Info supplémentaire
            "BFM Business", "France 24", "TV5 Monde", "Euronews", "i24 News",
            // Cinéma
            "OCS Max", "OCS City", "OCS Choc", "OCS Géants",
            "Ciné+ Premier", "Ciné+ Frisson", "Ciné+ Émotion",
            "Ciné+ Famiz", "Ciné+ Club", "Ciné+ Classic",
            "TCM Cinéma", "Paramount Channel", "13ème Rue", "Syfy",
            "Warner TV", "Polar+", "Action",
            // Sport
            "beIN Sports 1", "beIN Sports 2", "beIN Sports 3",
            "RMC Sport 1", "RMC Sport 2", "RMC Sport 3", "RMC Sport 4",
            "Eurosport 1", "Eurosport 2", "Infosport+",
            "Equidia", "Auto Moto La Chaîne", "Motorvision TV",
            // Documentaire
            "Planète+", "Ushuaïa TV", "Histoire TV",
            "National Geographic", "Nat Geo Wild", "Discovery Channel",
            "Animaux", "Voyage", "Seasons", "Crime District", "Trek",
            // Enfants
            "Disney Channel", "Disney Junior", "Cartoon Network", "Boomerang",
            "Nickelodeon", "Nick Jr.", "Tiji", "Piwi+", "Canal J",
            "Tfou Max", "Toonami", "Boing", "Mangas",
            // Musique
            "MTV", "MCM", "M6 Music", "NRJ Hits",
            "Trace Urban", "Trace Tropical", "Trace Africa", "Mezzo",
            "Fun Radio TV",
            // Régionales / autres
            "RTL9", "AB1", "Téva", "Numéro 23",
            "BFM Paris Île-de-France", "BFM Régions",
        ).mapIndexed { idx, n -> normalizeKey(n) to idx }.toMap()
    }

    // ───────── Ordre des catégories au home ─────────
    // 7 catégories officielles + Canal+ Live, dans le même ordre que Vegeta TV
    // pour une UX cohérente entre providers IPTV.
    private val CATEGORY_ORDER = listOf(
        "Généraliste", "Cinéma", "Canal+ Live", "Info", "Sport",
        "Musique", "Documentaire", "Enfants",
    )


    // ───────── Curated channels — calqué sur VegetaTvProvider ─────────
    // 2026-05-10 (user) : "Et tu me classes les chaînes Comme sur végéta TV".
    // → on adopte exactement la même approche : liste hard-codée de ~150 chaînes
    // FR connues, chacune avec (key normalisé, displayName affiché, catégorie).
    // On match les chaînes Vavoo par nom canonique (`normalizeKey()`).
    // Tout ce qui n'est PAS dans la curated list va dans le tab 2 paginé +
    // est trouvable via la recherche.
    private data class CuratedChannel(
        val key: String,
        val displayName: String,
        val category: String,
    )

    private val curatedChannels: List<CuratedChannel> by lazy {
        listOf(
            // ─── Généraliste TNT ───
            CuratedChannel("tf1", "TF1", "Généraliste"),
            CuratedChannel("france2", "France 2", "Généraliste"),
            CuratedChannel("france3", "France 3", "Généraliste"),
            CuratedChannel("france4", "France 4", "Généraliste"),
            CuratedChannel("france5", "France 5", "Généraliste"),
            CuratedChannel("m6", "M6", "Généraliste"),
            CuratedChannel("arte", "Arte", "Généraliste"),
            CuratedChannel("c8", "C8", "Généraliste"),
            CuratedChannel("w9", "W9", "Généraliste"),
            CuratedChannel("tmc", "TMC", "Généraliste"),
            CuratedChannel("tfx", "TFX", "Généraliste"),
            CuratedChannel("nrj12", "NRJ 12", "Généraliste"),
            CuratedChannel("lcp", "LCP", "Généraliste"),
            CuratedChannel("publicsenat", "Public Sénat", "Généraliste"),
            CuratedChannel("gulli", "Gulli", "Généraliste"),
            CuratedChannel("tf1seriesfilms", "TF1 Séries Films", "Généraliste"),
            CuratedChannel("6ter", "6ter", "Généraliste"),
            CuratedChannel("cherie25", "Chérie 25", "Généraliste"),
            CuratedChannel("franceo", "France Ô", "Généraliste"),
            CuratedChannel("rmcstory", "RMC Story", "Généraliste"),
            CuratedChannel("rtl9", "RTL9", "Généraliste"),
            CuratedChannel("ab1", "AB1", "Généraliste"),
            CuratedChannel("teva", "Téva", "Généraliste"),
            CuratedChannel("numero23", "Numéro 23", "Généraliste"),
            // ─── Cinéma ───
            CuratedChannel("canalplus", "Canal+", "Cinéma"),
            CuratedChannel("canalpluscinema", "Canal+ Cinéma", "Cinéma"),
            CuratedChannel("canalplusseries", "Canal+ Séries", "Cinéma"),
            CuratedChannel("canalplusfamily", "Canal+ Family", "Cinéma"),
            CuratedChannel("canalplusdocs", "Canal+ Docs", "Cinéma"),
            CuratedChannel("canalplusboxoffice", "Canal+ Box Office", "Cinéma"),
            CuratedChannel("canalplusgrandecran", "Canal+ Grand Écran", "Cinéma"),
            CuratedChannel("ocsmax", "OCS Max", "Cinéma"),
            CuratedChannel("ocsgeants", "OCS Géants", "Cinéma"),
            CuratedChannel("ocschoc", "OCS Choc", "Cinéma"),
            CuratedChannel("ocscity", "OCS City", "Cinéma"),
            CuratedChannel("cinepluspremier", "Ciné+ Premier", "Cinéma"),
            CuratedChannel("cineplusfrisson", "Ciné+ Frisson", "Cinéma"),
            CuratedChannel("cineplusemotion", "Ciné+ Émotion", "Cinéma"),
            CuratedChannel("cineplusfamiz", "Ciné+ Famiz", "Cinéma"),
            CuratedChannel("cineplusclub", "Ciné+ Club", "Cinéma"),
            CuratedChannel("cineplusclassic", "Ciné+ Classic", "Cinéma"),
            CuratedChannel("paramountchannel", "Paramount Channel", "Cinéma"),
            CuratedChannel("13emerue", "13ème Rue", "Cinéma"),
            CuratedChannel("syfy", "Syfy", "Cinéma"),
            CuratedChannel("warnertv", "Warner TV", "Cinéma"),
            CuratedChannel("tcmcinema", "TCM Cinéma", "Cinéma"),
            CuratedChannel("polarplus", "Polar+", "Cinéma"),
            CuratedChannel("action", "Action", "Cinéma"),
            // ─── Canal+ Live multi-feeds ───
            CuratedChannel("canalpluslive1", "Canal+ Live 1", "Canal+ Live"),
            CuratedChannel("canalpluslive2", "Canal+ Live 2", "Canal+ Live"),
            CuratedChannel("canalpluslive3", "Canal+ Live 3", "Canal+ Live"),
            CuratedChannel("canalpluslive4", "Canal+ Live 4", "Canal+ Live"),
            CuratedChannel("canalpluslive5", "Canal+ Live 5", "Canal+ Live"),
            CuratedChannel("canalpluslive6", "Canal+ Live 6", "Canal+ Live"),
            CuratedChannel("canalpluslive7", "Canal+ Live 7", "Canal+ Live"),
            CuratedChannel("canalpluslive8", "Canal+ Live 8", "Canal+ Live"),
            CuratedChannel("canalpluslive9", "Canal+ Live 9", "Canal+ Live"),
            CuratedChannel("canalpluslive10", "Canal+ Live 10", "Canal+ Live"),
            CuratedChannel("canalpluslive11", "Canal+ Live 11", "Canal+ Live"),
            CuratedChannel("canalpluslive12", "Canal+ Live 12", "Canal+ Live"),
            CuratedChannel("canalpluslive13", "Canal+ Live 13", "Canal+ Live"),
            // ─── Info ───
            CuratedChannel("bfmtv", "BFM TV", "Info"),
            CuratedChannel("cnews", "CNews", "Info"),
            CuratedChannel("lci", "LCI", "Info"),
            CuratedChannel("franceinfo", "Franceinfo", "Info"),
            CuratedChannel("bfmbusiness", "BFM Business", "Info"),
            CuratedChannel("france24", "France 24", "Info"),
            CuratedChannel("tv5monde", "TV5 Monde", "Info"),
            CuratedChannel("euronews", "Euronews", "Info"),
            CuratedChannel("i24news", "i24 News", "Info"),
            CuratedChannel("bfmparisidf", "BFM Paris Île-de-France", "Info"),
            CuratedChannel("bfmregions", "BFM Régions", "Info"),
            // ─── Sport ───
            CuratedChannel("canalplussport", "Canal+ Sport", "Sport"),
            CuratedChannel("canalplussport360", "Canal+ Sport 360", "Sport"),
            CuratedChannel("canalplusfoot", "Canal+ Foot", "Sport"),
            CuratedChannel("beinsports1", "beIN Sports 1", "Sport"),
            CuratedChannel("beinsports2", "beIN Sports 2", "Sport"),
            CuratedChannel("beinsports3", "beIN Sports 3", "Sport"),
            CuratedChannel("rmcsport1", "RMC Sport 1", "Sport"),
            CuratedChannel("rmcsport2", "RMC Sport 2", "Sport"),
            CuratedChannel("rmcsport3", "RMC Sport 3", "Sport"),
            CuratedChannel("rmcsport4", "RMC Sport 4", "Sport"),
            CuratedChannel("eurosport1", "Eurosport 1", "Sport"),
            CuratedChannel("eurosport2", "Eurosport 2", "Sport"),
            CuratedChannel("lequipe", "L'Équipe", "Sport"),
            CuratedChannel("infosportplus", "Infosport+", "Sport"),
            CuratedChannel("equidia", "Equidia", "Sport"),
            CuratedChannel("automotolachaine", "Auto Moto La Chaîne", "Sport"),
            CuratedChannel("motorvisiontv", "Motorvision TV", "Sport"),
            // ─── Musique ───
            CuratedChannel("mtv", "MTV", "Musique"),
            CuratedChannel("mcm", "MCM", "Musique"),
            CuratedChannel("m6music", "M6 Music", "Musique"),
            CuratedChannel("nrjhits", "NRJ Hits", "Musique"),
            CuratedChannel("traceurban", "Trace Urban", "Musique"),
            CuratedChannel("tracetropical", "Trace Tropical", "Musique"),
            CuratedChannel("traceafrica", "Trace Africa", "Musique"),
            CuratedChannel("mezzo", "Mezzo", "Musique"),
            CuratedChannel("funradiotv", "Fun Radio TV", "Musique"),
            // ─── Documentaire ───
            CuratedChannel("planeteplus", "Planète+", "Documentaire"),
            CuratedChannel("ushuaiatv", "Ushuaïa TV", "Documentaire"),
            CuratedChannel("histoiretv", "Histoire TV", "Documentaire"),
            CuratedChannel("nationalgeographic", "National Geographic", "Documentaire"),
            CuratedChannel("natgeowild", "Nat Geo Wild", "Documentaire"),
            CuratedChannel("discoverychannel", "Discovery Channel", "Documentaire"),
            CuratedChannel("animaux", "Animaux", "Documentaire"),
            CuratedChannel("voyage", "Voyage", "Documentaire"),
            CuratedChannel("seasons", "Seasons", "Documentaire"),
            CuratedChannel("crimedistrict", "Crime District", "Documentaire"),
            CuratedChannel("trek", "Trek", "Documentaire"),
            CuratedChannel("rmcdecouverte", "RMC Découverte", "Documentaire"),
            // ─── Enfants ───
            CuratedChannel("disneychannel", "Disney Channel", "Enfants"),
            CuratedChannel("disneyjunior", "Disney Junior", "Enfants"),
            CuratedChannel("cartoonnetwork", "Cartoon Network", "Enfants"),
            CuratedChannel("boomerang", "Boomerang", "Enfants"),
            CuratedChannel("nickelodeon", "Nickelodeon", "Enfants"),
            CuratedChannel("nickjr", "Nick Jr.", "Enfants"),
            CuratedChannel("tiji", "Tiji", "Enfants"),
            CuratedChannel("piwiplus", "Piwi+", "Enfants"),
            CuratedChannel("canalj", "Canal J", "Enfants"),
            CuratedChannel("tfoumax", "Tfou Max", "Enfants"),
            CuratedChannel("toonami", "Toonami", "Enfants"),
            CuratedChannel("boing", "Boing", "Enfants"),
            CuratedChannel("mangas", "Mangas", "Enfants"),
        )
    }

    /** Normalise un nom Vavoo pour matcher avec un curated key.
     *  Vavoo name format observé (probe 2026-05-10) :
     *    "13 EME RUE HD .s"   "6TER FHD .b"   "TF1 .c"   "13EME RUE .c"
     *  → suffixe ".b"/".c"/".s" = source marker (Backup/Cluster/Stream),
     *  → tags qualité HD/FHD inline,
     *  → spacing inconsistant ("13 EME RUE" vs "13EME RUE").
     *  Strip aggressif puis collapse non-alphanumériques. */
    private fun normalizeKey(name: String): String {
        return name.lowercase()
            // 1) Strip trailing source marker " .b" / " .c" / " .s"
            .replace(Regex("\\s*\\.[a-z]\\s*$"), "")
            // 2) Strip brackets and parens content
            .replace(Regex("\\[.*?\\]"), " ")
            .replace(Regex("\\(.*?\\)"), " ")
            // 3) Diacritic normalization
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâä]"), "a")
            .replace(Regex("[ùûü]"), "u")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace("ç", "c")
            // 4) Quality / variant tags (word-bounded)
            // 2026-05-11 : "live" retiré du strip car "Canal+ Live 1/2/3" est un
            // distinguishing marker (multi-feed Canal+). Sans ça, normalizeKey
            // collapse Canal+ Live 1, 2, 3, ... → "canal" → tous matchent la même
            // entrée → 1 seule Canal+ Live affichée + placeholder "CA" pour les autres.
            .replace(Regex("\\b(fhd|uhd|hd|sd|4k|raw|hevc|h265|ppv|backup|alt|premium|gold|fullhd|full|1080p?|720p?|480p?|test|ott|raw)\\b"), " ")
            // 5) +1 timeshift
            .replace(Regex("\\+\\s?1\\b"), " ")
            // 6) Symbols → text
            .replace("+", "plus")
            .replace("&", "and")
            // 7) Leading "FR :" / "FR -" / "France:" prefix
            .replace(Regex("^(fr|france)\\s*[:|.\\-]\\s*"), "")
            // 8) Trailing FR / French
            .replace(Regex("\\s+(fr|french|francais|france)\\s*$"), "")
            // 9) Final strip non-alphanumeric (collapses everything to canonical key)
            .replace(Regex("[^a-z0-9]"), "")
    }

    // ───────── Logos officiels FR (calqué sur VegetaTvProvider.manualLogoMap) ─────────
    // 2026-05-11 : étendu pour couvrir plus de chaînes Vavoo curated. Glide cache
    // les URLs partagées (ex: Canal+ Live 1-13 partagent le même logo Canal+),
    // donc UNE seule requête réseau pour 13 chaînes. Pas d'overload comme l'ancien
    // ui-avatars.com qui générait une URL unique par chaîne (~900 requêtes).
    private val manualLogoMap: Map<String, String> by lazy {
        val base = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france"
        val canalPlus = "$base/canal-plus-fr.png"
        // 2026-05-11 : URLs vérifiées via HEAD requests le 2026-05-11. Toute URL
        // 404 sur tv-logo/tv-logos retirée du map (sinon Glide affiche placeholder
        // noir/CA). Les chaînes sans logo officiel disponible → "" → pas de tile.
        mapOf(
            // ─── TNT généraliste (vérifié OK) ───
            "tf1" to "$base/tf1-fr.png",
            "france2" to "$base/france-2-fr.png",
            "france3" to "$base/france-3-fr.png",
            "france4" to "$base/france-4-fr.png",
            "france5" to "$base/france-5-fr.png",
            "m6" to "$base/m6-fr.png",
            "w9" to "$base/w9-fr.png",
            "c8" to "$base/c8-fr.png",
            "arte" to "$base/arte-fr.png",
            "tmc" to "$base/tmc-fr.png",
            "tfx" to "$base/tfx-fr.png",
            "nrj12" to "$base/nrj-12-fr.png",
            "6ter" to "$base/6ter-fr.png",
            "gulli" to "$base/gulli-fr.png",
            "rmcstory" to "$base/rmc-story-fr.png",
            "tf1seriesfilms" to "$base/tf1-series-films-fr.png",
            // ─── Canal+ et toutes ses déclinaisons (logo Canal+ partagé) ───
            "canalplus" to canalPlus,
            "canalpluscinema" to canalPlus,    // canal-plus-cinema-fr.png 404
            "canalplusseries" to canalPlus,
            "canalplusfamily" to canalPlus,
            "canalplusdocs" to canalPlus,
            "canalplussport" to canalPlus,
            "canalplussport360" to canalPlus,
            "canalplusfoot" to canalPlus,
            "canalplusboxoffice" to canalPlus,
            "canalplusgrandecran" to canalPlus,
            "canalpluslive1" to canalPlus,
            "canalpluslive2" to canalPlus,
            "canalpluslive3" to canalPlus,
            "canalpluslive4" to canalPlus,
            "canalpluslive5" to canalPlus,
            "canalpluslive6" to canalPlus,
            "canalpluslive7" to canalPlus,
            "canalpluslive8" to canalPlus,
            "canalpluslive9" to canalPlus,
            "canalpluslive10" to canalPlus,
            "canalpluslive11" to canalPlus,
            "canalpluslive12" to canalPlus,
            "canalpluslive13" to canalPlus,
            // ─── Info (5 corrigés via alternate URLs trouvées) ───
            "bfmtv" to "$base/bfm-tv-fr.png",
            "cnews" to "$base/c-news-fr.png",          // était cnews-fr.png
            "lci" to "$base/lci-fr.png",
            "franceinfo" to "$base/franceinfo-fr.png", // était france-info-fr.png
            "france24" to "$base/france-24-fr.png",
            "tv5monde" to "$base/tv5-monde-fr.png",    // était tv5monde-fr.png
            "bfmbusiness" to "$base/bfm-business-fr.png",
            // euronews, i24news → pas de logo dispo, retirés (pas de placeholder)
            // ─── Sport (lequipe corrigé) ───
            "lequipe" to "$base/lequipe-fr.png",       // était l-equipe-fr.png
            "rmcsport1" to "$base/rmc-sport-1-fr.png",
            "rmcsport2" to "$base/rmc-sport-2-fr.png",
            "rmcsport3" to "$base/rmc-sport-3-fr.png",
            "rmcsport4" to "$base/rmc-sport-4-fr.png",
            "eurosport1" to "$base/eurosport-1-fr.png",
            "eurosport2" to "$base/eurosport-2-fr.png",
            "infosportplus" to "$base/infosport-plus-fr.png",
            // beinsports1/2/3 → pas de logo dispo, retirés
            // ─── Documentaire ───
            "planeteplus" to "$base/planete-plus-fr.png",
            "ushuaiatv" to "$base/ushuaia-tv-fr.png",
            "histoiretv" to "$base/histoire-tv-fr.png",
            "rmcdecouverte" to "$base/rmc-decouverte-fr.png",
            "animaux" to "$base/animaux-fr.png",
            // nationalgeographic, discoverychannel → pas de logo dispo, retirés
            // ─── Enfants ───
            "disneychannel" to "$base/disney-channel-fr.png",
            "disneyjunior" to "$base/disney-jr-fr.png", // était disney-junior-fr.png
            "cartoonnetwork" to "$base/cartoon-network-fr.png",
            "boomerang" to "$base/boomerang-fr.png",
            "nickelodeon" to "$base/nickelodeon-fr.png",
            "tiji" to "$base/tiji-fr.png",
            "piwiplus" to "$base/piwi-plus-fr.png",
            "canalj" to "$base/canal-j-fr.png",
            // tfoumax → pas de logo dispo, retiré
            // ─── Musique ───
            "mcm" to "$base/mcm-fr.png",
            "m6music" to "$base/m6-music-fr.png",
            "nrjhits" to "$base/nrj-hits-fr.png",
            "mezzo" to "$base/mezzo-fr.png",
            // mtv → pas de logo dispo, retiré
            // ─── Cinéma ───
            "ocsmax" to "$base/ocs-max-fr.png",
            "ocsgeants" to "$base/ocs-geants-fr.png",
            "ocschoc" to "$base/ocs-choc-fr.png",
            "ocscity" to "$base/ocs-city-fr.png",
            "cinepluspremier" to "$base/cine-plus-premier-fr.png",
            "cineplusfrisson" to "$base/cine-plus-frisson-fr.png",
            "cineplusemotion" to "$base/cine-plus-emotion-fr.png",
            "cineplusclassic" to "$base/cine-plus-classic-fr.png",
            "paramountchannel" to "$base/paramount-channel-fr.png",
            "tcmcinema" to "$base/tcm-cinema-fr.png",
            "warnertv" to "$base/warner-tv-fr.png",
            "polarplus" to "$base/polar-plus-fr.png",
            // cineplusfamiz, 13emerue, syfy → pas de logo dispo, retirés
        )
    }

    /** Renvoie une URL logo pour `name` :
     *  1. logo officiel manualLogoMap (lookup par normalize key) — ~80 chaînes FR
     *  2. SINON chaîne vide → placeholder local Glide
     *  Glide cache les URLs partagées (Canal+ Live 1-13 = 1 seule requête réseau). */
    private fun logoUrlFor(name: String): String {
        val key = normalizeKey(name)
        return manualLogoMap[key] ?: ""
    }

    // Pagination du tab 2 "Chaînes TV" (chargement progressif).
    private const val TVSHOWS_PAGE_SIZE = 60

    // ═══════════════════════════════════════════
    //  API helpers
    // ═══════════════════════════════════════════

    private fun postJson(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): String {
        val reqBody = body.toString().toRequestBody(JSON_TYPE)
        val builder = Request.Builder()
            .url(url)
            .post(reqBody)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "*/*")
            .header("Connection", "close")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = client.newCall(builder.build()).execute()
        val text = response.body?.string() ?: ""
        if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
        return text
    }

    // ───────── Step 1: Get addon signature ─────────

    private fun getAddonSignature(): String {
        val now = System.currentTimeMillis()
        cachedSignature?.let { sig ->
            if (now < signatureExpiry) return sig
        }

        val payload = JSONObject().apply {
            put("reason", "app-focus")
            put("locale", "fr")
            put("theme", "dark")
            put("metadata", JSONObject().apply {
                put("device", JSONObject().apply {
                    put("type", "phone")
                    put("uniqueId", "sf-${android.os.Build.FINGERPRINT.hashCode().toUInt().toString(16)}")
                })
                put("os", JSONObject().apply {
                    put("name", "android")
                    put("version", android.os.Build.VERSION.RELEASE)
                    put("abis", JSONArray(listOf("arm64-v8a")))
                    put("host", "android")
                })
                put("app", JSONObject().apply { put("platform", "android") })
                put("version", JSONObject().apply {
                    put("package", "tv.vavoo.app")
                    put("binary", APP_VERSION)
                    put("js", APP_VERSION)
                })
            })
            put("appFocusTime", 0)
            put("playerActive", false)
            put("playDuration", 0)
            put("devMode", false)
            put("hasAddon", true)
            put("castConnected", false)
            put("package", "tv.vavoo.app")
            put("version", APP_VERSION)
            put("process", "app")
            put("firstAppStart", now)
            put("lastAppStart", now)
            put("adblockEnabled", true)
            put("proxy", JSONObject().apply {
                put("supported", JSONArray(listOf("ss")))
                put("engine", "Mu")
                put("enabled", false)
                put("autoServer", true)
            })
            put("iap", JSONObject().apply { put("supported", false) })
        }

        for (url in PING_URLS) {
            try {
                val text = postJson(url, payload)
                val json = JSONObject(text)
                val sig = json.optString("addonSig", "")
                if (sig.isNotBlank()) {
                    cachedSignature = sig
                    signatureExpiry = now + 5 * 60 * 1000 // 5 min
                    Log.d(TAG, "✓ addonSig obtained from $url")
                    return sig
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ping failed for $url: ${e.message}")
            }
        }
        throw Exception("Unable to obtain addonSig")
    }

    // ───────── Step 2: Fetch channel catalog ─────────

    private fun catalogHeaders(signature: String): Map<String, String> = mapOf(
        "mediahubmx-signature" to signature,
        "user-agent" to MEDIAHUBMX_UA,
        "Accept-Language" to "fr",
    )

    private fun loadCatalogFromBase(baseUrl: String, signature: String): List<VavooChannel> {
        val catalogUrl = "${baseUrl.trimEnd('/')}/mediahubmx-catalog.json"
        val headers = catalogHeaders(signature)
        val channels = mutableListOf<VavooChannel>()
        var cursorInt = 0

        while (true) {
            // 2026-05-10 : body strict, validé par test depuis Console DevTools de Lokke.
            // language=de + region=AT + sort=name + cursor=0 + filter.group=France
            // → HTTP 200 avec items. Tout autre combo → HTTP 400 "Validation error".
            val body = JSONObject().apply {
                put("language", "de")
                put("region", "AT")
                put("catalogId", "iptv")
                put("id", "iptv")
                put("adult", false)
                put("search", "")
                put("sort", "name")
                put("filter", JSONObject().apply { put("group", "France") })
                put("cursor", cursorInt)
                put("clientVersion", "3.1.0")
            }

            val text = postJson(catalogUrl, body, headers)
            val json = JSONObject(text)
            val items = json.optJSONArray("items") ?: JSONArray()

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.optString("type") != "iptv") continue
                val url = item.optString("url", "")
                if (url.isBlank()) continue

                val group = item.optString("group", "")
                val country = extractCountry(group)

                // Filtre France uniquement (la majorité des chaînes que veut le user)
                if (!country.equals("France", ignoreCase = true) &&
                    !country.equals("France Sport", ignoreCase = true)) continue

                val ids = item.optJSONObject("ids")
                val id = ids?.optString("id", "") ?: item.optString("id", url)

                val rawLogo = item.optString("logo", "")
                val name = item.optString("name", "")
                // 2026-05-11 (Option C) : PRIORITÉ INVERSÉE pour résoudre les
                // tuiles "CA" et tuiles noires sur le home Vavoo.
                //   1) manualLogoMap (logo officiel pour ~80 chaînes FR curated)
                //   2) sinon rawLogo (logo API Vavoo, souvent placeholder/dead)
                //   3) sinon fallbackLogoUrl (vide → Glide placeholder local)
                //
                // Avant : on prenait rawLogo en premier, donc Canal+ Live 1/2/3
                // affichait le placeholder ui-avatars renvoyé par l'API Vavoo
                // au lieu du vrai logo Canal+ que j'avais mappé.
                val curatedLogo = logoUrlFor(name)
                val logo = curatedLogo.ifBlank {
                    rawLogo.ifBlank { fallbackLogoUrl(name) }
                }

                channels.add(VavooChannel(
                    id = id,
                    name = name,
                    logo = logo,
                    group = group,
                    country = country,
                    url = url,
                    canonicalKey = normalizeKey(name), // pré-calcul ONCE
                ))
            }

            // nextCursor peut être Int ou String selon endpoint. On accepte les deux.
            val nextCursor = json.opt("nextCursor")
            cursorInt = when (nextCursor) {
                is Int -> nextCursor
                is String -> nextCursor.toIntOrNull() ?: -1
                else -> -1
            }
            if (cursorInt < 0) break
        }

        return channels
    }

    private val COUNTRY_SEPARATORS = listOf("➾", "⟾", "->", "→", "»", "›")

    private fun extractCountry(group: String): String {
        val raw = group.trim()
        if (raw.isBlank()) return "default"
        for (sep in COUNTRY_SEPARATORS) {
            if (raw.contains(sep)) {
                return raw.split(sep)[0].trim().ifBlank { "default" }
            }
        }
        return raw
    }

    /** 2026-05-11 (Option C) : fallback ui-avatars utilisé en DERNIER recours
     *  par Glide via .error() quand l'URL primaire 404 (typiquement les logos
     *  pourris renvoyés par l'API Vavoo). Évite les tuiles noires : on garantit
     *  une tuile avec les initiales de la chaîne, couleur par catégorie. Glide
     *  cache la même URL par chaîne donc pas de re-requête au scroll.
     *
     *  Public car appelé depuis TvShowViewHolder.setPoster() (branche IPTV). */
    fun fallbackLogoUrlFor(channelName: String): String = uiAvatarFallback(channelName)

    private fun fallbackLogoUrl(channelName: String): String = ""

    private fun uiAvatarFallback(name: String): String {
        // Couleur par catégorie devinée (Sport vert, Cinéma violet, etc.) →
        // un coup d'œil rapide permet de distinguer le genre de chaîne.
        val color = when (guessCategoryByName(name)) {
            "Sport" -> "388E3C"
            "Cinéma" -> "8E24AA"
            "Info" -> "D32F2F"
            "Musique" -> "F57C00"
            "Documentaire" -> "00897B"
            "Enfants" -> "F06292"
            else -> "1E88E5"
        }
        val initials = name
            .replace(Regex("[\\[\\(].*?[\\]\\)]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString("+")
            .ifBlank { name.take(3) }
        val safeName = java.net.URLEncoder.encode(initials, "UTF-8")
        return "https://ui-avatars.com/api/?name=$safeName&background=$color&color=fff&size=512&font-size=0.5&bold=true&format=png"
    }

    private fun guessCategoryByName(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("sport") || n.contains("foot") || n.contains("rugby") ||
                n.contains("equidia") || n.contains("eurosport") -> "Sport"
            n.contains("cine") || n.contains("ocs") || n.contains("paramount") ||
                n.contains("warner") || n.contains("tcm") || n.contains("polar") -> "Cinéma"
            n.contains("news") || n.contains("info") || n.contains("bfm") ||
                n.contains("lci") || n.contains("france 24") -> "Info"
            n.contains("music") || n.contains("mtv") || n.contains("mcm") ||
                n.contains("nrj") || n.contains("trace") || n.contains("mezzo") -> "Musique"
            n.contains("nat geo") || n.contains("discovery") || n.contains("planete") ||
                n.contains("ushuaia") || n.contains("histoire") -> "Documentaire"
            n.contains("kids") || n.contains("disney") || n.contains("nickel") ||
                n.contains("gulli") || n.contains("tiji") || n.contains("boomerang") -> "Enfants"
            else -> "default"
        }
    }

    // ───────── Step 3: Resolve stream URL ─────────

    private fun resolveStreamUrl(channel: VavooChannel): String {
        val signature = getAddonSignature()
        val headers = catalogHeaders(signature)

        for (base in BASE_SITES) {
            val resolveUrl = "${base.trimEnd('/')}/mediahubmx-resolve.json"
            try {
                val body = JSONObject().apply {
                    put("language", "fr")
                    put("region", "US")
                    put("url", channel.url)
                    put("clientVersion", CLIENT_VERSION)
                }
                val text = postJson(resolveUrl, body, headers)

                // Response can be array or object
                val streamUrl = if (text.trimStart().startsWith("[")) {
                    val arr = JSONArray(text)
                    if (arr.length() > 0) arr.getJSONObject(0).optString("url", "") else ""
                } else {
                    val json = JSONObject(text)
                    json.optString("url", json.optString("streamUrl", ""))
                }

                if (streamUrl.isNotBlank()) {
                    Log.d(TAG, "✓ Resolved '${channel.name}' via $base → ${streamUrl.take(80)}")
                    return streamUrl
                }
            } catch (e: Exception) {
                Log.w(TAG, "Resolve failed via $base: ${e.message}")
            }
        }
        throw Exception("Unable to resolve stream for '${channel.name}'")
    }

    // ═══════════════════════════════════════════
    //  Registry management
    // ═══════════════════════════════════════════

    private suspend fun ensureRegistry() {
        if (registryLoaded && System.currentTimeMillis() - lastLoadTime < CACHE_DURATION) return
        registryMutex.withLock {
            if (registryLoaded && System.currentTimeMillis() - lastLoadTime < CACHE_DURATION) return
            withContext(Dispatchers.IO) {
                val t = System.currentTimeMillis()
                try {
                    val signature = getAddonSignature()
                    var channels: List<VavooChannel>? = null

                    for (base in BASE_SITES) {
                        try {
                            channels = loadCatalogFromBase(base, signature)
                            Log.d(TAG, "✓ Catalog from $base: ${channels.size} FR channels")
                            break
                        } catch (e: Exception) {
                            Log.w(TAG, "Catalog failed for $base: ${e.message}")
                        }
                    }

                    if (channels != null && channels.isNotEmpty()) {
                        synchronized(registryLock) {
                            channelRegistry.clear()
                            channelRegistry.addAll(channels)
                        }
                        cachedOrderedIds = null // invalidate cache, sera recalculé au prochain accès
                        registryLoaded = true
                        lastLoadTime = System.currentTimeMillis()
                        Log.d(TAG, "✓ Registry loaded: ${channels.size} channels in ${System.currentTimeMillis() - t}ms")
                    } else {
                        Log.w(TAG, "No FR channels found from any base site")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Registry load failed: ${e.message}")
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    //  Provider interface
    // ═══════════════════════════════════════════

    /** Construit un TvShow IPTV propre avec providerName pour reconnaissance dans
     *  TvShowViewHolder.isIptvProvider() → handleDirectPlay → mini player. */
    private fun VavooChannel.toTvShow(): TvShow = TvShow(
        id = "vavoo::$id",
        title = name,
        poster = logo,
        providerName = VavooProvider.name,
    )

    override suspend fun getHome(): List<Category> = try {
        ensureRegistry()
        val all = synchronized(registryLock) { channelRegistry.toList() }

        if (all.isEmpty()) {
            listOf(Category(name = "Aucune chaîne disponible", list = emptyList()))
        } else {
            // 2026-05-10 : home = curated channels Vegeta-style. On match les
            // chaînes Vavoo par normalizeKey() (TF1 / TF1 HD / TF1 Backup → "tf1")
            // contre la curated list. Pour chaque curated entry trouvée, on garde
            // la PREMIÈRE chaîne Vavoo qui matche (c'est l'extracteur Vavoo qui
            // sélectionne le best stream à la résolution).
            // Tout ce qui n'est pas curated → tab 2 paginé OU recherche.
            val byKey = all.groupBy { it.canonicalKey } // pré-calculé, pas de regex

            val sections = mutableListOf<Category>()

            // ─── ★ Favoris EN TÊTE ───
            // 2026-05-10 (user) : "les chaînes favoris ne s'ajoutent pas à l'onglet
            // cœur favori". → on ajoute la section "Favoris" qu'IptvFavoritesTvFragment
            // / IptvFavoritesMobileFragment cherchent dans getHome() pour peupler
            // l'onglet ❤. Aussi affichée en tête du home Vavoo lui-même.
            try {
                val favKeys = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                    .getAllCanonicalFavorites()
                if (favKeys.isNotEmpty()) {
                    val favItems = favKeys.mapNotNull { key ->
                        val matching = byKey[key]?.firstOrNull() ?: return@mapNotNull null
                        // Si curated match → displayName + logo curated. Sinon nom Vavoo nettoyé.
                        val curated = curatedChannels.firstOrNull { it.key == key }
                        val displayTitle = curated?.displayName ?: cleanDisplayName(matching.name)
                        TvShow(
                            id = "vavoo::${matching.id}",
                            title = displayTitle,
                            poster = logoUrlFor(displayTitle),
                            banner = logoUrlFor(displayTitle),
                            providerName = name,
                        )
                    }
                    if (favItems.isNotEmpty()) {
                        sections.add(Category(name = "Favoris", list = favItems))
                    }
                }
            } catch (_: Throwable) { }

            for (catName in CATEGORY_ORDER) {
                val items = curatedChannels
                    .filter { it.category == catName }
                    .mapNotNull { curated ->
                        val matching = byKey[curated.key]?.firstOrNull() ?: return@mapNotNull null
                        TvShow(
                            id = "vavoo::${matching.id}",
                            title = curated.displayName,
                            poster = logoUrlFor(curated.displayName),
                            banner = logoUrlFor(curated.displayName),
                            providerName = name,
                        )
                    }
                if (items.isNotEmpty()) sections.add(Category(name = catName, list = items))
            }
            sections
        }
    } catch (e: Exception) {
        Log.e(TAG, "getHome failed", e)
        emptyList()
    }

    /** Tab 2 "Chaînes TV" — TOUTES les chaînes Vavoo paginées TVSHOWS_PAGE_SIZE
     *  par page, tri TNT order (TF1, France 2, France 3, Canal+, ...) puis alpha
     *  pour le reste. Tri indexé par normalizeKey() pour matcher les noms Vavoo
     *  qui ont des suffixes ".b"/".c"/".s" et tags "HD"/"FHD".
     *  Logos garantis via logoUrlFor() (jaquette pour tout le monde). */
    override suspend fun getTvShows(page: Int): List<TvShow> {
        ensureRegistry()
        val all = synchronized(registryLock) { channelRegistry.toList() }
        if (all.isEmpty()) return emptyList()

        // 2026-05-10 (FIX FREEZE) : utilise canonicalKey pré-calculé (0 regex sur main thread).
        val unique = all.distinctBy { it.canonicalKey }
        val sorted = unique.sortedWith(compareBy<VavooChannel> { ch ->
            tntOrder[ch.canonicalKey] ?: 9999
        }.thenBy { it.canonicalKey })

        val from = (page - 1) * TVSHOWS_PAGE_SIZE
        if (from >= sorted.size) return emptyList()
        val to = minOf(from + TVSHOWS_PAGE_SIZE, sorted.size)

        return sorted.subList(from, to).map { ch ->
            val curated = curatedChannels.firstOrNull { it.key == ch.canonicalKey }
            val displayTitle = curated?.displayName ?: cleanDisplayName(ch.name)
            val displayLogo = curated?.let { logoUrlFor(it.displayName) }
                ?: ch.logo.ifBlank { logoUrlFor(displayTitle) }

            TvShow(
                id = "vavoo::${ch.id}",
                title = displayTitle,
                poster = displayLogo,
                banner = displayLogo,
                providerName = name,
            )
        }
    }

    /** Nettoie un nom Vavoo pour l'affichage (sans modifier le matching).
     *  "13EME RUE FHD .s" → "13EME RUE", "6TER FHD .b" → "6TER", etc.
     *  Si le nettoyage produit une chaîne vide (cas pathologique : nom = "HD"
     *  ou ".c" tout seul), fallback au nom raw pour éviter les UI vides. */
    private fun cleanDisplayName(raw: String): String {
        val cleaned = raw
            .replace(Regex("\\s*\\.[a-zA-Z]\\s*$"), "")
            .replace(Regex("\\b(FHD|UHD|HD|SD|4K|RAW|HEVC|H265|PPV|BACKUP|FULLHD|FULL|1080p?|720p?)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifBlank { raw.trim().ifBlank { "Chaîne" } }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        ensureRegistry()
        val q = query.lowercase()
        return synchronized(registryLock) {
            channelRegistry.filter { it.name.lowercase().contains(q) }
        }.map { it.toTvShow() }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val vavooId = id.removePrefix("vavoo::")
        ensureRegistry()
        val ch = synchronized(registryLock) {
            channelRegistry.find { it.id == vavooId }
        }
        return TvShow(
            id = id,
            title = ch?.name ?: vavooId,
            poster = ch?.logo ?: "",
            overview = "Chaîne IPTV via Vavoo",
            providerName = name,
            seasons = listOf(
                Season(id = id, number = 1, title = "Direct")
            ),
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val vavooId = seasonId.removePrefix("vavoo::")
        val ch = synchronized(registryLock) {
            channelRegistry.find { it.id == vavooId }
        }
        return listOf(
            Episode(
                id = seasonId,
                number = 1,
                title = ch?.name ?: "Direct",
                poster = ch?.logo ?: "",
            )
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try {
        val vavooId = id.removePrefix("vavoo::")
        ensureRegistry()
        val ch = synchronized(registryLock) {
            channelRegistry.find { it.id == vavooId }
        }

        if (ch != null) {
            // Resolve on-demand — direct m3u8
            val streamUrl = withContext(Dispatchers.IO) { resolveStreamUrl(ch) }
            listOf(Video.Server("m3u8::$streamUrl", "Vavoo"))
        } else {
            Log.w(TAG, "Channel not found: $vavooId")
            emptyList()
        }
    } catch (e: Exception) {
        Log.e(TAG, "getServers failed for $id: ${e.message}")
        emptyList()
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val url = server.id
        if (url.startsWith("m3u8::")) {
            val m3u8 = url.removePrefix("m3u8::")
            return Video(
                source = m3u8,
                headers = mapOf("User-Agent" to VAVOO_UA)
            )
        }
        throw Exception("Unknown server format: $url")
    }

    // ═══════════════════════════════════════════
    //  Channel navigation (prev/next) — pour les boutons du grand player
    // ═══════════════════════════════════════════

    /** Liste ordonnée des IDs de chaînes (même ordre que tab 2 "Chaînes TV").
     *  2026-05-10 (FIX FREEZE 32s) : utilise canonicalKey pré-calculé + cache.
     *  AVANT : appelait normalizeKey() ~30 000 fois sur main thread (compareBy
     *  sortedWith sur 983 chaînes × 10 regex chacune) = freeze 32 sec.
     *  MAINTENANT : 0 regex sur main thread, juste comparaisons string. */
    private fun getOrderedChannelIds(): List<String> {
        cachedOrderedIds?.let { return it }
        val all = synchronized(registryLock) { channelRegistry.toList() }
        val unique = all.distinctBy { it.canonicalKey } // pré-calculé
        val sorted = unique.sortedWith(compareBy<VavooChannel> { ch ->
            tntOrder[ch.canonicalKey] ?: 9999 // pré-calculé
        }.thenBy { it.canonicalKey })
        val ids = sorted.map { "vavoo::${it.id}" }
        cachedOrderedIds = ids
        return ids
    }

    fun getPreviousChannelId(currentId: String): String? {
        val list = getOrderedChannelIds()
        val idx = list.indexOf(currentId)
        return if (idx > 0) list[idx - 1] else null
    }

    fun getNextChannelId(currentId: String): String? {
        val list = getOrderedChannelIds()
        val idx = list.indexOf(currentId)
        return if (idx in 0 until list.lastIndex) list[idx + 1] else null
    }

    /** Affiché à l'écran lors du zapping (overlay channel switch). */
    fun getChannelDisplayName(channelId: String): String? {
        val vid = channelId.removePrefix("vavoo::")
        val ch = synchronized(registryLock) { channelRegistry.find { it.id == vid } } ?: return null
        val curated = curatedChannels.firstOrNull { it.key == ch.canonicalKey }
        return curated?.displayName ?: cleanDisplayName(ch.name)
    }

    /** Logo affiché lors du zapping. */
    fun getChannelPoster(channelId: String): String? {
        val vid = channelId.removePrefix("vavoo::")
        val ch = synchronized(registryLock) { channelRegistry.find { it.id == vid } } ?: return null
        val curated = curatedChannels.firstOrNull { it.key == ch.canonicalKey }
        return curated?.let { logoUrlFor(it.displayName) }
            ?: ch.logo.ifBlank { logoUrlFor(cleanDisplayName(ch.name)) }
    }

    // ═══════════════════════════════════════════
    //  Favoris cross-provider — bridge ID interne ↔ key canonique
    // ═══════════════════════════════════════════

    /** Convertit un Vavoo internal ID (ex: "ABC123") en clé canonique cross-provider
     *  (ex: "tf1"). Utilisé par IptvFavoritesStore pour normaliser les favoris.
     *  2026-05-10 (FIX FREEZE) : retourne canonicalKey pré-calculé. */
    fun getCanonicalKeyForChannelId(internalId: String): String? {
        val ch = synchronized(registryLock) { channelRegistry.find { it.id == internalId } }
            ?: return null
        return ch.canonicalKey
    }

    /** Inverse : convertit une clé canonique (ex: "tf1") en Vavoo internal ID.
     *  Renvoie null si la registry n'est pas encore chargée ou si la chaîne
     *  n'existe pas chez Vavoo. */
    fun getInternalIdForCanonicalKey(canonicalKey: String): String? {
        val ch = synchronized(registryLock) {
            channelRegistry.firstOrNull { it.canonicalKey == canonicalKey }
        } ?: return null
        return ch.id
    }

    // ═══════════════════════════════════════════
    //  Unused — IPTV provider, pas de films
    // ═══════════════════════════════════════════

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getMovie(id: String): Movie = Movie(id = id)
    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id = id, name = id)
    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = id)
}
