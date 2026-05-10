package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.ConnectionSpec
import okhttp3.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * OLA TV provider — independent IPTV catalog sourced directly from OLA TV's API
 * (iptvdroid.monster). Distinct from WiTvProvider: catalog comes 100% from OLA TV
 * (not witv.team scraping), so it works regardless of witv.team availability.
 *
 * Strategy:
 *  - Phase 1: parseOlaTvServerList → all 1159 cids cached
 *  - Phase 2 (synchronous, ~3-5s): primary FR cid (cid w/ category_name="2020")
 *    → MAC portal handshake → FR channel list. Displayed immediately.
 *  - Phase 3 (background, opportunistic): scan other cids that look FR and
 *    enrich the registry with extra stream variants for each known channel.
 *
 * OLA TV protocol (same as WiTv's helpers but duplicated here to keep
 * providers decoupled — explicitly per user request "ne touche pas à WiTv").
 */
object OlaTvProvider : Provider, IptvProvider {

    override val name = "OLA TV"
    override val baseUrl = "https://iptvdroid.monster"
    override val logo = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_olatv"
    override val language = "fr"

    private const val TAG = "OlaTvProvider"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"

    // OLA TV API
    private const val OLA_TV_API = "http://iptvdroid.monster/IP11/api.php"
    private const val OLA_TV_AES_KEY = "3234567890123453"
    private const val OLA_TV_SECRET = "MRZEREZIS"
    // The cid whose category_name = "2020" is the primary FR server (user-confirmed).
    private const val OLA_TV_PRIMARY_FR = "2020"

    // ───────── HTTP ─────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .dns(DnsResolver.doh)
        .build()

    private val probeClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // ───────── Channel registry ─────────

    private data class OlaStreamRef(val cid: String, val label: String, val url: String)

    private class ChannelInfo(
        val displayName: String,
        var category: String = "Généraliste",
        var logo: String = "",
        val streams: MutableList<OlaStreamRef> = mutableListOf(),
    )

    private val channelRegistry = LinkedHashMap<String, ChannelInfo>()
    private val registryLock = Any()
    private val registryMutex = Mutex()
    @Volatile private var registryLoaded = false
    @Volatile private var lastLoadTime = 0L
    private const val CACHE_DURATION = 30 * 60 * 1000L // 30 min

    // cid → category_name (numeric tag)
    @Volatile private var olaTvServerMap: Map<String, String> = emptyMap()

    // ───────── Phase 3: multi-cid background scan ─────────
    // FR cids confirmed to have FR channels. Includes the primary + any discovered via scan.
    private val frCids = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile private var phase3Done = false
    private val phase3Mutex = Mutex()

    // Phase 3 tuning
    private const val PHASE3_MAX_CANDIDATES = 60  // probe more cids — most are dead, need breadth
    private const val PHASE3_MAX_FR_CIDS = 15     // collect up to N healthy FR cids for stream diversity
    private const val PHASE3_PARALLELISM = 8      // aggressive parallelism — dead domains fail fast anyway

    // Cap of progressive variants emitted per channel for the initial batch.
    // Keeps the "Chaîne" page tidy so the user can pick a source manually.
    // Server renewal (requestNextBatch) bypasses this cap — those extra servers
    // are only used by the automatic failover, not shown in the Chaîne list.
    // 2026-05-08 : passé de 5 à 10 pour avoir de la marge avec les bans IPTV.
    // Logique : user bannit jusqu'à 5 servers → reste au moins 5 non-bannis
    // visibles dans le picker. Couvre le cas "tous bannis sauf favoris".
    private const val MAX_VARIANTS_PER_CHANNEL = 10

    // Max servers per renewal batch (host-diversity cap applied fresh each batch)
    private const val RENEWAL_BATCH_SIZE = 24

    // Disk cache for the discovered FR cid list — 24h TTL
    private const val FR_CIDS_CACHE_FILE = "olatv_fr_cids.json"

    // Cap on the "Autres chaînes" home row to keep the TV RecyclerView responsive
    // (ANR if hundreds of items try to bind + load logos at the same time).
    private const val OTHER_CHANNELS_HOME_LIMIT = 60

    // Pagination size for getTvShows() — full "Toutes les chaînes" page. 50 keeps
    // the RecyclerView feed reasonable; the framework requests page+1 when the
    // user scrolls near the bottom.
    private const val TV_SHOWS_PAGE_SIZE = 50

    // Cap on search results — TV RecyclerView ANRs if a generic query ("tf",
    // "canal") returns 80+ items all loading logos at the same time.
    private const val SEARCH_RESULT_LIMIT = 30
    private const val FR_CIDS_CACHE_TTL_MS = 24L * 60 * 60 * 1000L

    // Disk cache for the full channel registry — boot fast on subsequent launches
    // (skip Phase 1 server-list parse + Phase 2 primary-cid ingestion ~30s).
    // 6h TTL: long enough to skip the heavy work, short enough that channel changes
    // upstream don't go stale. Phase 3 still refreshes opportunistically in background.
    // v2 — busts the previous disk cache after norm() now keeps "+" → "plus".
    private const val REGISTRY_CACHE_FILE = "olatv_registry_v2.json"
    private const val REGISTRY_CACHE_TTL_MS = 6L * 60 * 60 * 1000L

    // ───────── Normalization & TNT order ─────────

    /** Strip accents, brackets, quality tags, country suffixes, and "+1" markers — *anywhere*
     *  in the name (not just at the end), so all variants of a given channel collapse to the
     *  same base key. Iterates until no changes so that combos like "TF1 +1 HD" reduce to "tf1".
     *  KEEPS the word "France" as part of the channel name (e.g. "France 2" → "france2") because
     *  it's a real part of the channel identity — only strips "FR"/"French" at boundaries. */
    private fun norm(raw: String): String {
        var s = raw.lowercase()
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâä]"), "a")
            .replace(Regex("[ùûü]"), "u")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace("ç", "c")
            .replace(Regex("\\[.*?]"), " ")
            .replace(Regex("\\(.*?\\)"), " ")
            // Strip "FR:" / "France:" / "FR -" prefix only (an explicit channel-list prefix).
            .replace(Regex("^\\s*(fr|france)\\s*[:|\\-]\\s*"), "")
        // Quality tags and "+1": stripped anywhere.
        // 2026-05-09 : "live" est strippé SEULEMENT s'il n'est PAS suivi d'un
        // chiffre. Préserve "Canal+ Live 1" tout en strippant "TF1 LIVE HD".
        val midTag = Regex(
            "\\b(hd|sd|fhd|uhd|4k|raw|hevc|h\\.?265|ppv|ott|test|backup|fhdr|sdr)\\b" +
                "|\\blive\\b(?!\\s*\\d)" +
                "|(?:\\+\\s?1)|(?:1080p|720p|480p|360p)"
        )
        // Country qualifiers: only stripped at end (so "France 2" stays intact, but "TF1 FR" → "TF1").
        val endQualifier = Regex(
            "\\s+(fr|french|francais|belgique|be|suisse|ch|lux)\\s*$"
        )
        while (true) {
            val next = s.replace(midTag, " ")
                .replace(endQualifier, "")
                .replace(Regex("\\.fr\\b"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (next == s) break
            s = next
        }
        // Replace "+" with "plus" so curated keys (which use the same convention via
        // normalizeForLogo) match registry keys: "Canal+ Family" → "canalplusfamily".
        // Also "&" → "and" to keep parity.
        return s.replace("+", "plus").replace("&", "and")
            .replace(Regex("[^a-z0-9]"), "").replace("sports", "sport")
    }

    /** Extract the variant label ("HD", "FHD", "+1", "FR") from a raw channel name so
     *  the player's "Chaîne" page can show meaningful entries instead of the full
     *  channel name. Returns "" when no qualifier is present. */
    private fun extractVariantLabel(raw: String): String {
        val parts = mutableListOf<String>()
        Regex(
            "\\b(HD|SD|FHD|UHD|4K|RAW|HEVC|H\\.?265|PPV|OTT|LIVE|FHDR|SDR)\\b" +
                "|(?:\\+\\s?1)|(?:1080p|720p|480p|360p)",
            RegexOption.IGNORE_CASE
        ).findAll(raw).forEach { parts.add(it.value.uppercase()) }
        // Country qualifier kept separately so e.g. "TF1 FR" stays distinguishable from "TF1 BE".
        Regex(
            "\\b(FRANCE|FR|FRENCH|FRANCAIS|BELGIQUE|BE|SUISSE|CH|LUX)\\b",
            RegexOption.IGNORE_CASE
        ).findAll(raw).forEach { m -> parts.add(m.value.uppercase()) }
        return parts.distinct().joinToString(" ")
    }

    /** Pretty display name: keeps the channel identity ("France 2", "France Info") but
     *  strips quality / country / "+1" markers at appropriate positions. */
    private fun baseDisplayName(raw: String): String {
        val midTag = Regex(
            "\\b(HD|SD|FHD|UHD|4K|RAW|HEVC|H\\.?265|PPV|OTT|LIVE|TEST|BACKUP|FHDR|SDR)\\b" +
                "|(?:\\+\\s?1)|(?:1080p|720p|480p|360p)",
            RegexOption.IGNORE_CASE
        )
        val endQualifier = Regex(
            "\\s+(FR|French|Francais|Belgique|BE|Suisse|CH|LUX)\\s*$",
            RegexOption.IGNORE_CASE
        )
        var s = raw
            .replace(Regex("\\[.*?]"), " ")
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("^\\s*(FR|France)\\s*[:|\\-]\\s*", RegexOption.IGNORE_CASE), "")
            // Strip leading bullets / dashes / chevrons that some upstream providers prepend.
            .replace(Regex("^[\\-•●○▪►•‣⮞›»\\u2022]+\\s*"), "")
        while (true) {
            val next = s.replace(midTag, " ")
                .replace(endQualifier, "")
                .replace(Regex("\\.fr\\b", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (next == s) break
            s = next
        }
        return s.ifBlank { raw }
    }

    /** Channel display order (TNT FR + popular non-TNT). Channels not in this list go alphabetical. */
    private val tntOrder: Map<String, Int> by lazy {
        val list = listOf(
            "TF1", "France 2", "France 3", "Canal+", "France 5", "M6", "Arte",
            "C8", "W9", "TMC", "TFX", "NRJ 12", "LCP", "France 4",
            "BFM TV", "CNews", "CStar", "Gulli", "TF1 Séries Films", "L'Équipe",
            "6ter", "RMC Story", "RMC Découverte", "Chérie 25", "LCI", "Franceinfo",
            // Info
            "BFM Business", "France 24", "TV5 Monde", "Euronews", "i24 News",
            // Cinéma
            "Canal+ Cinéma", "Canal+ Séries", "Canal+ Family", "Canal+ Docs",
            "OCS Max", "OCS Géants", "OCS Choc", "OCS City",
            "Ciné+ Premier", "Ciné+ Frisson", "Ciné+ Émotion", "Ciné+ Famiz", "Ciné+ Club", "Ciné+ Classic",
            "Paramount Channel", "13ème Rue", "Syfy", "Warner TV",
            // Sport
            "Canal+ Sport", "Canal+ Sport 360", "beIN Sports 1", "beIN Sports 2", "beIN Sports 3",
            "RMC Sport 1", "RMC Sport 2", "RMC Sport 3", "RMC Sport 4",
            "Eurosport 1", "Eurosport 2", "La Chaîne L'Équipe", "Infosport+",
            // Musique
            "MTV", "MCM", "M6 Music", "NRJ Hits", "Trace Urban", "Mezzo",
            // Documentaire
            "Planète+", "Ushuaïa TV", "Histoire TV", "Toute l'Histoire",
            "Science & Vie TV", "National Geographic", "Nat Geo Wild",
            "Discovery Channel", "RMC Découverte", "Trek",
            // Enfants
            "Disney Channel", "Disney Junior", "Cartoon Network", "Boomerang",
            "Nickelodeon", "Nick Jr.", "Tiji", "Piwi+", "Canal J", "Tfou Max",
        )
        list.withIndex().associate { (idx, name) -> norm(name) to idx }
    }

    private fun sortByTnt(items: List<TvShow>): List<TvShow> {
        return items.sortedWith(compareBy(
            { tntOrder[norm(it.title)] ?: 999 },
            { it.title.lowercase() }
        ))
    }

    /** FROZEN curated channel list — the home page shows these in this exact order with
     *  their hardcoded logos, regardless of whether streams have been ingested yet.
     *  Streams are linked at click time via the registry; the visible list never moves. */
    private data class CuratedChannel(
        val key: String,        // norm() output — used to match the registry
        val displayName: String,
        val category: String,
    )

    private val curatedChannels: List<CuratedChannel> by lazy {
        listOf(
            // Généraliste TNT
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
            CuratedChannel("gulli", "Gulli", "Généraliste"),
            CuratedChannel("tf1seriesfilms", "TF1 Séries Films", "Généraliste"),
            CuratedChannel("6ter", "6ter", "Généraliste"),
            CuratedChannel("cherie25", "Chérie 25", "Généraliste"),
            CuratedChannel("franceo", "France Ô", "Généraliste"),
            CuratedChannel("rmcstory", "RMC Story", "Généraliste"),
            // Info
            CuratedChannel("bfmtv", "BFM TV", "Info"),
            CuratedChannel("cnews", "CNews", "Info"),
            CuratedChannel("lci", "LCI", "Info"),
            CuratedChannel("franceinfo", "Franceinfo", "Info"),
            CuratedChannel("bfmbusiness", "BFM Business", "Info"),
            CuratedChannel("france24", "France 24", "Info"),
            CuratedChannel("tv5monde", "TV5 Monde", "Info"),
            CuratedChannel("euronews", "Euronews", "Info"),
            CuratedChannel("i24news", "i24 News", "Info"),
            // Cinéma & Séries
            CuratedChannel("canalplus", "Canal+", "Cinéma"),
            CuratedChannel("canalpluscinema", "Canal+ Cinéma", "Cinéma"),
            CuratedChannel("canalplusseries", "Canal+ Séries", "Cinéma"),
            CuratedChannel("canalplusfamily", "Canal+ Family", "Cinéma"),
            CuratedChannel("canalplusdocs", "Canal+ Docs", "Cinéma"),
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
            // Sport
            CuratedChannel("canalplussport", "Canal+ Sport", "Sport"),
            CuratedChannel("canalplussport360", "Canal+ Sport 360", "Sport"),
            CuratedChannel("beinsports1", "beIN Sports 1", "Sport"),
            CuratedChannel("beinsports2", "beIN Sports 2", "Sport"),
            CuratedChannel("beinsports3", "beIN Sports 3", "Sport"),
            CuratedChannel("rmcsport1", "RMC Sport 1", "Sport"),
            CuratedChannel("rmcsport2", "RMC Sport 2", "Sport"),
            CuratedChannel("rmcsport3", "RMC Sport 3", "Sport"),
            CuratedChannel("eurosport1", "Eurosport 1", "Sport"),
            CuratedChannel("eurosport2", "Eurosport 2", "Sport"),
            CuratedChannel("lachainelequipe", "La Chaîne L'Équipe", "Sport"),
            CuratedChannel("infosportplus", "Infosport+", "Sport"),
            // Musique
            CuratedChannel("mtv", "MTV", "Musique"),
            CuratedChannel("mcm", "MCM", "Musique"),
            CuratedChannel("m6music", "M6 Music", "Musique"),
            CuratedChannel("nrjhits", "NRJ Hits", "Musique"),
            CuratedChannel("traceurban", "Trace Urban", "Musique"),
            CuratedChannel("mezzo", "Mezzo", "Musique"),
            // Documentaire
            CuratedChannel("planeteplus", "Planète+", "Documentaire"),
            CuratedChannel("ushuaiatv", "Ushuaïa TV", "Documentaire"),
            CuratedChannel("histoiretv", "Histoire TV", "Documentaire"),
            CuratedChannel("rmcdecouverte", "RMC Découverte", "Documentaire"),
            CuratedChannel("nationalgeographic", "National Geographic", "Documentaire"),
            CuratedChannel("natgeowild", "Nat Geo Wild", "Documentaire"),
            CuratedChannel("discoverychannel", "Discovery Channel", "Documentaire"),
            CuratedChannel("trek", "Trek", "Documentaire"),
            // Enfants
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
            // L'Équipe (sport généraliste)
            CuratedChannel("lequipe", "L'Équipe", "Sport"),

            // ─── Extension list — common FR channels missing from the original
            // 80-channel curation. Keys all run through norm(): accents stripped,
            // "+" → "plus", "&" → "and", "sports" → "sport", non-alphanumeric removed.
            // Généraliste / séries
            CuratedChannel("rtl9", "RTL9", "Généraliste"),
            CuratedChannel("ab1", "AB1", "Généraliste"),
            CuratedChannel("teva", "Téva", "Généraliste"),
            CuratedChannel("numero23", "Numéro 23", "Généraliste"),
            // Cinéma & Séries
            CuratedChannel("tcmcinema", "TCM Cinéma", "Cinéma"),
            CuratedChannel("polarplus", "Polar+", "Cinéma"),
            CuratedChannel("action", "Action", "Cinéma"),
            CuratedChannel("canalplusboxoffice", "Canal+ Box Office", "Cinéma"),
            CuratedChannel("canalplusgrandecran", "Canal+ Grand Écran", "Cinéma"),
            CuratedChannel("canalplusfoot", "Canal+ Foot", "Sport"),
            // 2026-05-09 : catégorie dédiée "Canal+ Live"
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
            CuratedChannel("canalpluslive14", "Canal+ Live 14", "Canal+ Live"),
            CuratedChannel("canalpluslive15", "Canal+ Live 15", "Canal+ Live"),
            CuratedChannel("canalpluslive16", "Canal+ Live 16", "Canal+ Live"),
            CuratedChannel("canalpluslive17", "Canal+ Live 17", "Canal+ Live"),
            CuratedChannel("canalpluslive18", "Canal+ Live 18", "Canal+ Live"),
            CuratedChannel("canalpluslive19", "Canal+ Live 19", "Canal+ Live"),
            // Sport extras
            CuratedChannel("beinsportmax4", "beIN Sports MAX 4", "Sport"),
            CuratedChannel("beinsportmax5", "beIN Sports MAX 5", "Sport"),
            CuratedChannel("beinsportmax6", "beIN Sports MAX 6", "Sport"),
            CuratedChannel("beinsportmax7", "beIN Sports MAX 7", "Sport"),
            CuratedChannel("beinsportmax8", "beIN Sports MAX 8", "Sport"),
            CuratedChannel("beinsportmax9", "beIN Sports MAX 9", "Sport"),
            CuratedChannel("beinsportmax10", "beIN Sports MAX 10", "Sport"),
            CuratedChannel("rmcsport4", "RMC Sport 4", "Sport"),
            CuratedChannel("eurosportnews", "Eurosport News", "Sport"),
            CuratedChannel("equidia", "Equidia", "Sport"),
            CuratedChannel("abmoteurs", "AB Moteurs", "Sport"),
            CuratedChannel("automotolachaine", "Auto Moto La Chaîne", "Sport"),
            CuratedChannel("motorvisiontv", "Motorvision TV", "Sport"),
            // Documentaire
            CuratedChannel("animaux", "Animaux", "Documentaire"),
            CuratedChannel("voyage", "Voyage", "Documentaire"),
            CuratedChannel("chasseetpeche", "Chasse et Pêche", "Documentaire"),
            CuratedChannel("seasons", "Seasons", "Documentaire"),
            CuratedChannel("crimedistrict", "Crime District", "Documentaire"),
            CuratedChannel("crimeplusinvestigation", "Crime + Investigation", "Documentaire"),
            CuratedChannel("discoveryinvestigation", "Discovery Investigation", "Documentaire"),
            CuratedChannel("discoveryfamily", "Discovery Family", "Documentaire"),
            CuratedChannel("discoveryscience", "Discovery Science", "Documentaire"),
            CuratedChannel("abxplore", "ABXplore", "Documentaire"),
            // Enfants
            CuratedChannel("toonami", "Toonami", "Enfants"),
            CuratedChannel("boing", "Boing", "Enfants"),
            CuratedChannel("mangas", "Mangas", "Enfants"),
            CuratedChannel("gameone", "Game One", "Enfants"),
            // Musique
            CuratedChannel("m6musichits", "M6 Music Hits", "Musique"),
            CuratedChannel("traceafrica", "Trace Africa", "Musique"),
            CuratedChannel("tracelatina", "Trace Latina", "Musique"),
            CuratedChannel("tracetropical", "Trace Tropical", "Musique"),
            CuratedChannel("funradiotv", "Fun Radio TV", "Musique"),
            // Info / régionales
            CuratedChannel("bfmparisidf", "BFM Paris Île-de-France", "Info"),
            CuratedChannel("bfmregions", "BFM Régions", "Info"),
        )
    }

    // ───────── OLA TV API helpers (protocol identical to WiTv's, duplicated for decoupling) ─────────

    /** Build OLA TV payload: JSON {salt, sign=MD5("MRZEREZIS"+salt), method_name, ...}, base64-encoded. */
    private fun olaBuildPayload(methodName: String, extras: Map<String, String> = emptyMap()): String {
        val salt = java.util.Random().nextInt(900).toString()
        val md = java.security.MessageDigest.getInstance("MD5")
        md.update(("$OLA_TV_SECRET$salt").toByteArray())
        val sign = md.digest().joinToString("") { "%02x".format(it) }
        val json = JSONObject().apply {
            put("salt", salt); put("sign", sign); put("method_name", methodName)
            extras.forEach { (k, v) -> put(k, v) }
        }
        return Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.DEFAULT)
    }

    private fun olaDecrypt(ciphertext: String): String {
        val keyBytes = OLA_TV_AES_KEY.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(keyBytes))
        val raw = Base64.decode(ciphertext.trim(), Base64.DEFAULT)
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }

    /** token1 → base64 → first bytes are metadata, then "http..." starts the URL. */
    private fun olaDecodeToken(token: String): String? = try {
        val raw = Base64.decode(token, Base64.DEFAULT)
        val text = String(raw, Charsets.ISO_8859_1)
        val idx = text.indexOf("http")
        if (idx >= 0) text.substring(idx).trim(' ') else null
    } catch (_: Exception) { null }

    /** token2 → base64 → contains MAC pattern XX:XX:XX:XX:XX:XX. */
    private fun olaDecodeToken2(token: String): String? = try {
        val raw = Base64.decode(token, Base64.DEFAULT)
        val text = String(raw, Charsets.ISO_8859_1)
        val macRegex = Regex("""([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2})""")
        macRegex.find(text)?.groupValues?.get(1)
    } catch (_: Exception) { null }

    /** Catalog entry from MAC portal: name + cmd (raw stream URL or localhost:create_link). */
    private data class MacChannel(val name: String, val cmd: String)

    /** List ALL channels from a MAC portal.
     *  - If `forceAllGenres = true` (used for the user-confirmed primary FR cid): fetch all genres.
     *    Useful when the portal doesn't tag FR explicitly — we already know it's FR-only.
     *  - Else: only fetch genres whose title matches FR keywords (used for background scan
     *    of unconfirmed cids).
     *
     *  Pages are fetched in **parallel** (after page 1 reveals the total count) to keep the
     *  initial load short — primary cid was 50s sequential, ~10s parallel.
     *  Returns channel name → cmd. We resolve cmd to actual stream URLs lazily in getServers. */
    private suspend fun olaListMacPortalChannels(baseUrl: String, mac: String, forceAllGenres: Boolean = false): List<MacChannel> {
        val results = mutableListOf<MacChannel>()
        val encodedMac = java.net.URLEncoder.encode(mac, "UTF-8")
        val portalBase = "${baseUrl.trimEnd('/')}/portal.php"
        val cookie = "mac=$encodedMac; stb_lang=en; timezone=Europe%2FLondon"
        val stbUA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

        try {
            // Step 1: handshake → token
            val hsReq = Request.Builder()
                .url("$portalBase?type=stb&action=handshake&token=&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie).build()
            val hsBody = probeClient.newCall(hsReq).execute().body?.string() ?: ""
            val token = try { JSONObject(hsBody).getJSONObject("js").getString("token") } catch (_: Exception) {
                Log.w(TAG, "MAC: no token in handshake")
                return results
            }

            // Step 2: get_profile (init session)
            val profReq = Request.Builder()
                .url("$portalBase?type=stb&action=get_profile&auth_token=$token&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie)
                .header("Authorization", "Bearer $token").build()
            probeClient.newCall(profReq).execute().body?.close()

            // Step 3: get_genres → find FR genres (FR| prefix or "France"/"French")
            val genreReq = Request.Builder()
                .url("$portalBase?type=itv&action=get_genres&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie)
                .header("Authorization", "Bearer $token").build()
            val genreBody = probeClient.newCall(genreReq).execute().body?.string() ?: ""

            val frGenreIds = mutableListOf<String>()
            try {
                val arr = JSONObject(genreBody).optJSONArray("js") ?: JSONArray()
                for (g in 0 until arr.length()) {
                    val genre = arr.optJSONObject(g) ?: continue
                    val title = genre.optString("title", "").lowercase()
                    val id = genre.optString("id", "")
                    if (title.startsWith("fr|") || title.startsWith("fr ")
                        || title.contains("france") || title.contains("french") || title.contains("français")) {
                        frGenreIds.add(id)
                    }
                }
            } catch (_: Exception) {}

            // Fallback: when no FR-tagged genre is found and forceAllGenres=true (primary cid),
            // we treat the whole portal as French and fetch all genres. This is needed for
            // FR-only portals that don't bother tagging language in genre titles.
            if (frGenreIds.isEmpty()) {
                if (!forceAllGenres) {
                    Log.d(TAG, "MAC portal $baseUrl: no FR genre found, skipping")
                    return results
                }
                Log.d(TAG, "MAC portal $baseUrl: no FR genre found — forceAllGenres=true, using genre=*")
                frGenreIds.add("*")
            } else {
                Log.d(TAG, "MAC portal $baseUrl: ${frGenreIds.size} FR genres → ${frGenreIds.take(3)}")
            }

            // Step 4: For each FR genre, fetch page 1 (sequential — gives totalItems),
            // then fetch pages 2..N in PARALLEL. This drops 50s → ~10s on the primary cid.
            val seenNames = mutableSetOf<String>()
            val nameLock = Any()
            // Page fetcher — returns the list of MacChannels found on a single page.
            suspend fun fetchPage(genreId: String, page: Int): Triple<List<MacChannel>, Int, Int> = withContext(Dispatchers.IO) {
                val pageChannels = mutableListOf<MacChannel>()
                try {
                    val chReq = Request.Builder()
                        .url("$portalBase?type=itv&action=get_ordered_list&genre=$genreId&force_ch_link_check=&fav=0&sortby=name&p=$page&JsHttpRequest=1-xml")
                        .header("User-Agent", stbUA).header("Cookie", cookie)
                        .header("Authorization", "Bearer $token").build()
                    val chBody = probeClient.newCall(chReq).execute().body?.string() ?: ""
                    val js = JSONObject(chBody).getJSONObject("js")
                    val data = js.optJSONArray("data") ?: JSONArray()
                    val totalItems = js.optInt("total_items", 0)
                    val maxPageItems = js.optInt("max_page_items", 14)

                    for (i in 0 until data.length()) {
                        val ch = data.optJSONObject(i) ?: continue
                        val rawName = ch.optString("name", "").trim()
                        if (rawName.isBlank() || rawName.startsWith("#")) continue
                        val cmd = ch.optString("cmd", "").trim()
                        if (cmd.isBlank()) continue
                        val cleaned = rawName
                            .replace(Regex("^(FR|ES|PT|EN|DE|IT|AR|TR|NL|PL|RO|US|UK|BE|CH)[|:\\s]+", RegexOption.IGNORE_CASE), "")
                            // Strip leading punctuation/decoration like "- TF1", "› France 2", "● Canal+".
                            .replace(Regex("^[\\-•●○▪►•‣⮞›»\\u2022]+\\s*"), "")
                            .replace(Regex("\\s+"), " ").trim()
                        if (cleaned.isBlank()) continue
                        synchronized(nameLock) {
                            if (cleaned in seenNames) return@synchronized
                            seenNames.add(cleaned)
                            pageChannels.add(MacChannel(cleaned, cmd))
                        }
                    }
                    return@withContext Triple(pageChannels, totalItems, maxPageItems)
                } catch (e: Exception) {
                    Log.w(TAG, "page $page genre=$genreId failed: ${e.message}")
                    return@withContext Triple(pageChannels, 0, 14)
                }
            }

            for (genreId in frGenreIds) {
                // 4a: page 1 → discover total_items
                val (page1Channels, totalItems, maxPageItems) = fetchPage(genreId, 1)
                results.addAll(page1Channels)
                if (totalItems <= maxPageItems || page1Channels.isEmpty()) continue

                // 4b: pages 2..N in parallel (cap at 30 pages defensively)
                val totalPages = minOf(30, ((totalItems + maxPageItems - 1) / maxPageItems))
                if (totalPages < 2) continue
                Log.d(TAG, "Genre $genreId: $totalItems items / $maxPageItems per page = $totalPages pages, fetching 2..$totalPages in parallel")
                coroutineScope {
                    val sem = kotlinx.coroutines.sync.Semaphore(6)  // cap at 6 parallel requests
                    val jobs = (2..totalPages).map { page ->
                        async {
                            sem.acquire()
                            try { fetchPage(genreId, page).first } finally { sem.release() }
                        }
                    }
                    jobs.awaitAll().forEach { results.addAll(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MAC portal failed for $baseUrl: ${e.message}")
        }
        Log.d(TAG, "MAC portal $baseUrl: ${results.size} unique FR channels")
        return results
    }

    /** Resolve a "cmd" (from get_ordered_list) to an actual playable stream URL.
     *  If cmd is already a direct *external* http URL, return it directly. Else
     *  (localhost:* form, which is how the MAC portal encodes "you must call create_link"),
     *  call create_link to get the real upstream URL. */
    private fun resolveStreamCmd(baseUrl: String, mac: String, cmd: String): String? {
        val rawCmd = cmd.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
        // Direct external HTTP URLs are playable as-is. Localhost URLs are placeholders
        // that ALWAYS need create_link resolution.
        val isLocalhost = rawCmd.contains("localhost") || rawCmd.contains("127.0.0.1")
        if (rawCmd.startsWith("http") && !isLocalhost) return rawCmd

        try {
            val encodedMac = java.net.URLEncoder.encode(mac, "UTF-8")
            val portalBase = "${baseUrl.trimEnd('/')}/portal.php"
            val cookie = "mac=$encodedMac; stb_lang=en; timezone=Europe%2FLondon"
            val stbUA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

            // Re-handshake (token may have expired)
            val hsBody = client.newCall(Request.Builder()
                .url("$portalBase?type=stb&action=handshake&token=&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie).build()
            ).execute().body?.string() ?: ""
            val token = JSONObject(hsBody).getJSONObject("js").getString("token")

            val cmdEncoded = java.net.URLEncoder.encode(rawCmd, "UTF-8")
            val linkReq = Request.Builder()
                .url("$portalBase?type=itv&action=create_link&cmd=$cmdEncoded&series=&forced_storage=undefined&fav=0&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie)
                .header("Authorization", "Bearer $token").build()
            val linkBody = client.newCall(linkReq).execute().body?.string() ?: ""
            val resolved = JSONObject(linkBody).getJSONObject("js").getString("cmd")
            return resolved.removePrefix("ffrt ").removePrefix("ffmpeg ").trim().ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "create_link failed for cmd='${rawCmd.take(60)}': ${e.message}")
            // If it's a DNS/connection failure, blacklist the domain for this session.
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("nxdomain") || msg.contains("unable to resolve") ||
                msg.contains("connect timed out") || msg.contains("failed to connect") ||
                msg.contains("connection refused") || msg.contains("no address associated")) {
                val domain = try { java.net.URI(baseUrl).host ?: "" } catch (_: Exception) { "" }
                if (domain.isNotBlank()) {
                    markDomainDead(domain)
                    saveDeadDomains()
                }
            }
            return null
        }
    }

    /** Get MAC portal credentials for a cid via getToken128910. */
    private data class MacCredentials(val baseUrl: String, val mac: String)

    // Cache MAC creds per cid — they don't change during a session and the API call is slow.
    private val macCredsCache = java.util.concurrent.ConcurrentHashMap<String, MacCredentials>()

    // Cache resolved upstream URLs (localhost → upstream) per (cid, cmd) so repeated plays
    // of the same variant don't trigger redundant create_link calls.
    private val resolvedUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // URLs that have recently failed — used for ordering: demoted URLs are emitted
    // LAST so the player tries fresh URLs first. No TTL throw: a demoted URL is still
    // playable if the player walks down to it (rotation, not blacklist).
    private val demotedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Per-hostname success/failure counters. Used to sort streams: portals with a
    // higher success ratio come first. Detects automatically when an upstream is
    // having a bad day and pushes its streams down the rotation across all channels.
    private data class HostHealth(var ok: Int = 0, var fail: Int = 0) {
        // Wilson lower-bound score on the success ratio — penalises low-data portals
        // less than naive ratio so a single fail doesn't tank a previously-perfect host.
        fun score(): Double {
            val n = (ok + fail).toDouble()
            if (n < 1.0) return 0.5  // unknown — neutral
            val p = ok.toDouble() / n
            val z = 1.0
            return (p + z * z / (2 * n) - z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n)) /
                (1 + z * z / n)
        }
    }
    private val hostHealth = java.util.concurrent.ConcurrentHashMap<String, HostHealth>()

    private fun hostnameOf(url: String): String = try {
        java.net.URI(url).host ?: ""
    } catch (_: Exception) { "" }

    private fun recordHostOk(url: String) {
        val h = hostnameOf(url).ifBlank { return }
        hostHealth.compute(h) { _, v -> (v ?: HostHealth()).also { it.ok++ } }
    }

    private fun recordHostFail(url: String) {
        val h = hostnameOf(url).ifBlank { return }
        hostHealth.compute(h) { _, v -> (v ?: HostHealth()).also { it.fail++ } }
    }

    private fun hostScore(url: String): Double {
        val h = hostnameOf(url).ifBlank { return 0.5 }
        return hostHealth[h]?.score() ?: 0.5
    }

    /** Player calls this when a stream URL fails. The URL is demoted to the bottom of
     *  the rotation but stays playable so the failover can wrap back to it if all
     *  fresher URLs also fail. */
    @JvmStatic
    fun reportBrokenStreamUrl(url: String) {
        if (url.isNotBlank()) {
            demotedUrls.add(url)
            probeOkCache.remove(url)
            recordHostFail(url)
            // Invalidate resolved URL cache so next attempt does a fresh create_link
            // (tokens in MAC portal URLs expire quickly → 401 errors)
            resolvedUrlCache.entries.removeIf { it.value == url }
            // Also invalidate MAC credentials for the host — token may have expired
            val host = hostnameOf(url)
            if (host.isNotBlank()) {
                val failCount = (hostHealth[host]?.fail ?: 0)
                if (failCount >= 2) {
                    // Multiple failures from this host — clear cached MAC credentials
                    // so next resolve does a fresh handshake
                    macCredsCache.entries.removeIf { hostnameOf(it.value.baseUrl) == host }
                    Log.d(TAG, "Cleared MAC creds for host $host after $failCount failures")
                    // 3+ failures: blacklist the domain to skip it entirely on future channels
                    if (failCount >= 3) {
                        markDomainDead(host)
                        saveDeadDomains()
                    }
                }
            }
            Log.d(TAG, "Demoted upstream URL (will retry last): ${url.take(80)}")
        }
    }

    /** Player reports a successful playback — we promote the host so its other streams
     *  bubble up in the rotation across other channels. Also persists the working
     *  stream info for this channel so the next app session starts with the best server. */
    @JvmStatic
    fun reportWorkingStreamUrl(url: String, channelKey: String? = null) {
        if (url.isNotBlank()) {
            recordHostOk(url)
            probeOkCache.add(url)
            // Persist the working URL for this channel
            if (!channelKey.isNullOrBlank()) {
                workingChannelUrls[channelKey] = url
                saveWorkingChannelUrls()
            }
        }
    }

    // ───────── Persistent working-URL cache ─────────
    // Maps channelKey (e.g. "tf1") to the last resolved URL that successfully played.
    // Saved to disk so that even after app restart, we try the best server first.
    private const val WORKING_URLS_FILE = "olatv_working_urls.json"
    private val workingChannelUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ───────── Persistent dead-domain blacklist ─────────
    // Domains that returned NXDOMAIN, connection refused, or repeated failures.
    // Persisted to disk so we skip them instantly on next launch instead of
    // wasting 5s per dead domain. TTL = 6h (domains can come back).
    private const val DEAD_DOMAINS_FILE = "olatv_dead_domains.json"
    private const val DEAD_DOMAINS_TTL_MS = 6L * 60 * 60 * 1000L
    private val deadDomains = java.util.concurrent.ConcurrentHashMap<String, Long>() // domain → timestamp

    // ───────── Server renewal pool ─────────
    // Full sorted stream list per channel (before host-diversity cap).
    // When the player exhausts the current batch, it can request the next batch
    // from this pool — seamless renewal without user-visible interruption.
    private val fullStreamPool = mutableMapOf<String, List<OlaStreamRef>>()
    private val emittedStreamUrls = mutableMapOf<String, MutableSet<String>>()

    /**
     * Request a new batch of servers for [channelKey] from the remaining pool.
     * Excludes URLs already tried (tracked in [triedServerIds]).
     * Returns the number of new servers emitted to the additionalServers flow.
     * Called by MiniPlayerController when all current servers are exhausted.
     */
    suspend fun requestNextBatch(channelKey: String, triedServerIds: Set<String>): Int {
        val key = channelKey.removePrefix("ola_ep::").removePrefix("ola::")
        val pool = synchronized(registryLock) { fullStreamPool[key] } ?: run {
            Log.d(TAG, "requestNextBatch('$key'): no pool available")
            return 0
        }
        val alreadyEmitted = synchronized(registryLock) { emittedStreamUrls[key] ?: emptySet() }

        // Extract tried URLs from server IDs (format: "ola_stream::cid::label::url")
        val triedUrls = triedServerIds.mapNotNull { id ->
            if (id.startsWith("ola_stream::")) {
                id.removePrefix("ola_stream::").split("::", limit = 3).getOrNull(2)
            } else null
        }.toSet()

        // Filter pool: exclude already emitted + dead domains
        val remaining = pool.filter { stream ->
            val url = stream.url
            if (url in alreadyEmitted) return@filter false
            if (url in triedUrls) return@filter false
            val raw = url.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
            val host = try { java.net.URI(raw).host ?: "" } catch (_: Exception) { "" }
            if (host.isNotBlank() && isDomainDead(host)) return@filter false
            true
        }

        if (remaining.isEmpty()) {
            Log.d(TAG, "requestNextBatch('$key'): pool exhausted — 0 remaining from ${pool.size} total")
            return 0
        }

        // Apply host-diversity cap to the remaining streams
        val hostCounts = mutableMapOf<String, Int>()
        val batch = remaining.filter { stream ->
            val raw = stream.url.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
            val host = try { java.net.URI(raw).host ?: "" } catch (_: Exception) { "" }
            if (host.isBlank()) return@filter true
            val count = hostCounts.getOrDefault(host, 0)
            if (count >= 2) false  // MAX_PER_HOST for renewal
            else { hostCounts[host] = count + 1; true }
        }.take(RENEWAL_BATCH_SIZE)

        Log.d(TAG, "requestNextBatch('$key'): emitting ${batch.size} new servers from ${remaining.size} remaining (${pool.size} total pool)")

        var emitted = 0
        for (stream in batch) {
            val server = Video.Server(
                id = "ola_stream::${stream.cid}::${stream.label}::${stream.url}",
                name = "OLA[$key] ${stream.label}",
            )
            synchronized(registryLock) {
                emittedStreamUrls.getOrPut(key) { mutableSetOf() }.add(stream.url)
            }
            Log.d(TAG, "  → renewal emit OLA[$key] ${stream.label} (cid=${stream.cid})")
            _additionalServers.emit(server)
            emitted++
        }
        return emitted
    }

    /**
     * Request a single replacement server for a banned source.
     * Returns the replacement Video.Server or null if pool exhausted.
     * Unlike requestNextBatch, this doesn't emit to the flow — the caller
     * adds it to the Settings.Server list directly.
     */
    fun requestSingleReplacement(bannedServerId: String): Video.Server? {
        // Extract channel key and URL from "ola_stream::cid::label::url"
        val parts = bannedServerId.removePrefix("ola_stream::").split("::", limit = 3)
        if (parts.size < 3) return null
        val channelKey = parts[0]
        val bannedUrl = parts[2]

        val pool = synchronized(registryLock) { fullStreamPool[channelKey] } ?: return null
        val alreadyEmitted = synchronized(registryLock) { emittedStreamUrls[channelKey] ?: emptySet() }

        // Find a stream from pool that wasn't already emitted and isn't on a dead domain
        val replacement = pool.firstOrNull { stream ->
            val url = stream.url
            if (url in alreadyEmitted) return@firstOrNull false
            if (url == bannedUrl) return@firstOrNull false
            val raw = url.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
            val host = try { java.net.URI(raw).host ?: "" } catch (_: Exception) { "" }
            if (host.isNotBlank() && isDomainDead(host)) return@firstOrNull false
            true
        } ?: return null

        // Mark as emitted
        synchronized(registryLock) {
            emittedStreamUrls.getOrPut(channelKey) { mutableSetOf() }.add(replacement.url)
        }

        Log.d(TAG, "requestSingleReplacement('$channelKey'): replacing banned with ${replacement.label}")
        return Video.Server(
            id = "ola_stream::${replacement.cid}::${replacement.label}::${replacement.url}",
            name = "OLA[$channelKey] ${replacement.label}",
        )
    }

    // ── Channel navigation (prev/next) for mobile zapping ──

    fun getOrderedChannelIds(): List<String> {
        return curatedChannels.map { "ola::${it.key}" }
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

    fun getChannelDisplayName(channelId: String): String? {
        val key = channelId.removePrefix("ola::").removePrefix("ola_ep::")
        return curatedChannels.firstOrNull { it.key == key }?.displayName
            ?: synchronized(registryLock) { channelRegistry[key]?.displayName }
    }

    fun getChannelPoster(channelId: String): String? {
        val key = channelId.removePrefix("ola::").removePrefix("ola_ep::")
        val name = getChannelDisplayName(channelId) ?: return null
        return logoUrlFor(name)
    }

    private fun loadWorkingChannelUrls() {
        try {
            val f = java.io.File(StreamFlixApp.instance.filesDir, WORKING_URLS_FILE)
            if (!f.exists()) return
            val json = org.json.JSONObject(f.readText())
            var loaded = 0
            var skipped = 0
            json.keys().forEach { key ->
                val url = json.getString(key)
                // Only keep direct stream URLs — MAC portal resolved URLs contain
                // ephemeral tokens that expire. Discard them on load to avoid playing
                // the wrong channel (expired tokens can redirect to default streams).
                if (isDirectStreamUrl(url)) {
                    workingChannelUrls[key] = url
                    loaded++
                } else {
                    Log.d(TAG, "Skipping cached URL for '$key' — looks like expired MAC portal URL")
                    skipped++
                }
            }
            Log.d(TAG, "Loaded $loaded working channel URLs from disk (skipped $skipped non-direct)")
            // If we skipped entries, rewrite the file without them.
            if (skipped > 0) saveWorkingChannelUrls()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load working URLs: ${e.message}")
        }
    }

    /** Returns true if the URL looks like a stable direct stream (not a MAC portal
     *  resolved URL with ephemeral tokens). MAC portal URLs typically contain /live/
     *  or /play/ with token parameters, or domains like funtogether.xyz, wowtv.cc, etc.
     *  Direct OTF/Vavoo/CDN streams are stable across sessions. */
    private fun isDirectStreamUrl(url: String): Boolean {
        val u = url.lowercase()
        // Localhost = MAC portal proxy — never cache
        if (u.contains("localhost") || u.contains("127.0.0.1")) return false
        // MAC portal resolved URLs typically have /live/ + token params or /play/
        // and come from known MAC portal domains. If URL has a "token=" param, it's ephemeral.
        if (u.contains("token=")) return false
        // Very short-lived MAC portal patterns
        if (Regex("/live/[^/]+/[^/]+/\\d+").containsMatchIn(u)) return false
        // If it looks like a normal stream URL, it's direct
        return u.startsWith("http")
    }

    private fun saveWorkingChannelUrls() {
        try {
            val json = org.json.JSONObject()
            workingChannelUrls.forEach { (k, v) -> json.put(k, v) }
            java.io.File(StreamFlixApp.instance.filesDir, WORKING_URLS_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save working URLs: ${e.message}")
        }
    }

    private fun loadDeadDomains() {
        try {
            val f = java.io.File(StreamFlixApp.instance.filesDir, DEAD_DOMAINS_FILE)
            if (!f.exists()) return
            val json = org.json.JSONObject(f.readText())
            val now = System.currentTimeMillis()
            json.keys().forEach { domain ->
                val ts = json.optLong(domain, 0)
                if (now - ts < DEAD_DOMAINS_TTL_MS) {
                    deadDomains[domain] = ts
                }
            }
            Log.d(TAG, "Loaded ${deadDomains.size} dead domains from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load dead domains: ${e.message}")
        }
    }

    private fun saveDeadDomains() {
        try {
            val json = org.json.JSONObject()
            val now = System.currentTimeMillis()
            deadDomains.forEach { (domain, ts) ->
                if (now - ts < DEAD_DOMAINS_TTL_MS) json.put(domain, ts)
            }
            java.io.File(StreamFlixApp.instance.filesDir, DEAD_DOMAINS_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save dead domains: ${e.message}")
        }
    }

    private fun markDomainDead(domain: String) {
        if (domain.isNotBlank()) {
            deadDomains[domain] = System.currentTimeMillis()
            Log.d(TAG, "Domain blacklisted: $domain")
        }
    }

    private fun isDomainDead(domain: String): Boolean {
        val ts = deadDomains[domain] ?: return false
        if (System.currentTimeMillis() - ts >= DEAD_DOMAINS_TTL_MS) {
            deadDomains.remove(domain)
            return false
        }
        return true
    }

    // URLs that recently passed a HEAD probe — short-circuit subsequent probes for the
    // same URL during one session (most channels won't change upstream within minutes).
    private val probeOkCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val probeClientFast = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Quick HEAD probe to filter dead upstreams before handing the URL to ExoPlayer.
     *  Returns true if the response is 2xx/3xx, false on 4xx/5xx/timeout. */
    private fun probeStreamHead(url: String): Boolean {
        return try {
            val req = Request.Builder().url(url).head()
                .header("User-Agent", USER_AGENT).build()
            probeClientFast.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful || resp.code in 300..399
                Log.d(TAG, "probe ${if (ok) "OK" else "FAIL"} (${resp.code}) ${url.take(80)}")
                ok
            }
        } catch (e: Exception) {
            Log.d(TAG, "probe TIMEOUT ${url.take(80)} — ${e.message}")
            false
        }
    }

    private fun getMacCredentials(cid: String): MacCredentials? {
        macCredsCache[cid]?.let { return it }
        try {
            val b64 = olaBuildPayload("getToken128910", mapOf("cid" to cid))
            val formBody = FormBody.Builder().add("data", b64).build()
            val req = Request.Builder().url(OLA_TV_API).post(formBody)
                .header("User-Agent", USER_AGENT).build()
            val body = probeClient.newCall(req).execute().body?.string() ?: return null
            val jsonStr = try {
                val trimmed = body.trim()
                if (trimmed.startsWith("[") || trimmed.startsWith("{")) trimmed else olaDecrypt(trimmed)
            } catch (_: Exception) { return null }

            val obj = try { JSONObject(jsonStr) } catch (_: Exception) { return null }
            // Two possible shapes: {LIVETV: [{token1, token2, ...}]} or {token1, token2}
            val target = obj.optJSONArray("LIVETV")?.optJSONObject(0) ?: obj
            val t1 = target.optString("token1", "")
            val t2 = target.optString("token2", "")
            if (t1.isBlank() || t2.isBlank()) return null
            val baseUrl = olaDecodeToken(t1) ?: return null
            val mac = olaDecodeToken2(t2) ?: return null

            // Check if this MAC portal domain is blacklisted (dead).
            // Saves 5-15s per dead domain instead of waiting for TCP timeout.
            val domain = try { java.net.URI(baseUrl).host ?: "" } catch (_: Exception) { "" }
            if (domain.isNotBlank() && isDomainDead(domain)) {
                Log.d(TAG, "getMacCredentials($cid) — domain $domain is blacklisted, skipping")
                return null
            }

            val creds = MacCredentials(baseUrl, mac)
            macCredsCache[cid] = creds
            return creds
        } catch (e: Exception) {
            Log.e(TAG, "getMacCredentials($cid) failed: ${e.message}")
            // If the failure looks like a DNS or connection error, blacklist the domain.
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("nxdomain") || msg.contains("unable to resolve") ||
                msg.contains("connect timed out") || msg.contains("failed to connect") ||
                msg.contains("connection refused")) {
                // Try to extract domain from the OLA TV API response we already parsed — but
                // at this point we may not have it. Mark based on error if we had a baseUrl.
            }
            return null
        }
    }

    /** Phase 1: parse the global server list. Caches cid → category_name. */
    private fun parseOlaTvServerList() {
        try {
            val b64 = olaBuildPayload("newolatvcategory0326")
            val formBody = FormBody.Builder().add("data", b64).build()
            val req = Request.Builder().url(OLA_TV_API).post(formBody)
                .header("User-Agent", USER_AGENT).build()
            val body = probeClient.newCall(req).execute().body?.string() ?: return
            val jsonStr = try {
                val trimmed = body.trim()
                if (trimmed.startsWith("[") || trimmed.startsWith("{")) trimmed else olaDecrypt(trimmed)
            } catch (_: Exception) { return }
            val arr = try { JSONArray(jsonStr) } catch (_: Exception) {
                try { JSONObject(jsonStr).optJSONArray("LIVETV") ?: return } catch (_: Exception) { return }
            }
            val map = mutableMapOf<String, String>()
            for (i in 0 until arr.length()) {
                val srv = arr.optJSONObject(i) ?: continue
                val cid = srv.optString("cid", "").trim()
                val catName = srv.optString("category_name", "").trim()
                if (cid.isNotBlank() && catName.isNotBlank()) map[cid] = catName
            }
            olaTvServerMap = map
            Log.d(TAG, "Server list: ${map.size} cid → category_name pairs")
        } catch (e: Exception) {
            Log.e(TAG, "parseOlaTvServerList failed: ${e.message}")
        }
    }

    /** Best guess of category from a channel display name. */
    private fun guessCategory(name: String): String {
        val l = name.lowercase()
        return when {
            l.contains("canal+ sport") || l.contains("bein") || l.contains("rmc sport")
                || l.contains("eurosport") || l.contains("equipe") || l.contains("infosport")
                || l.contains("ligue 1") || l.contains("sport en france") -> "Sport"
            l.contains("cinéma") || l.contains("ocs") || l.contains("ciné+") || l.contains("paramount")
                || l.contains("canal+ family") || l.contains("13eme rue") || l.contains("syfy") || l.contains("warner") -> "Cinéma"
            l.contains("bfm") || l.contains("cnews") || l.contains("lci") || l.contains("franceinfo")
                || l.contains("euronews") || l.contains("i24") || l.contains("tv5 monde") -> "Info"
            l.contains("planète+") || l.contains("nat geo") || l.contains("national geographic")
                || l.contains("ushuaia") || l.contains("histoire") || l.contains("discovery")
                || l.contains("rmc decouverte") || l.contains("rmc découverte") || l.contains("trek") -> "Documentaire"
            l.contains("disney") || l.contains("cartoon") || l.contains("nickelodeon") || l.contains("gulli")
                || l.contains("piwi") || l.contains("canal j") || l.contains("tiji") || l.contains("boomerang")
                || l.contains("tfou") -> "Enfants"
            l.contains("mtv") || l.contains("nrj hits") || l.contains("mcm") || l.contains("trace")
                || l.contains("mezzo") || l.contains("m6 music") -> "Musique"
            else -> "Généraliste"
        }
    }

    /** Comprehensive logo lookup built from iptv-org's channels.json + logos.json (FR
     *  channels in use). Bundled in app/src/main/assets/fr_channel_logos.json — keyed
     *  by official channel name, value is a real logo URL.
     *  Loaded once on first use, normalised on the fly. */
    private val iptvOrgLogoMap: Map<String, String> by lazy {
        try {
            val ctx = StreamFlixApp.instance
            ctx.assets.open("fr_channel_logos.json").bufferedReader(Charsets.UTF_8).use { reader ->
                val raw = reader.readText()
                val json = JSONObject(raw)
                val out = HashMap<String, String>(json.length() * 2)
                val it = json.keys()
                while (it.hasNext()) {
                    val name = it.next()
                    val url = json.optString(name, "")
                    if (url.isBlank()) continue
                    out[normalizeForLogo(name)] = url
                }
                Log.d(TAG, "iptv-org FR logos loaded: ${out.size} entries")
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load fr_channel_logos.json: ${e.message}")
            emptyMap()
        }
    }

    /** Compact lookup key — strips ALL non-alphanumeric so "C-8", "C8", "C 8" all
     *  collapse to "c8". Quality and country tags removed beforehand. */
    private fun normalizeForLogo(name: String): String {
        return name.lowercase()
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâä]"), "a")
            .replace(Regex("[ùûü]"), "u")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace("ç", "c")
            .replace(Regex("\\b(fhd|uhd|hd|sd|4k|raw|hevc|h265|ppv)\\b"), " ")
            .replace(Regex("\\+\\s?1\\b"), " ")
            .replace("+", "plus")
            .replace("&", "and")
            .replace(Regex("\\s+(fr|french|francais)\\s*$"), "")
            .replace(Regex("[^a-z0-9]"), "")
    }

    /** Manual map of channel display name → verified tv-logos GitHub URL.
     *  Used as primary lookup before iptv-org for top-priority TNT channels. */
    private val manualLogoMap: Map<String, String> by lazy {
        val base = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france"
        // Keys are compact (no spaces, lowercase, alphanumeric only) — matches normalizeForLogo.
        mapOf(
            // TNT généraliste
            "tf1" to "$base/tf1-fr.png",
            "tf1seriesfilms" to "$base/tf1-series-films-fr.png",
            "tfx" to "$base/tfx-fr.png",
            "tmc" to "$base/tmc-fr.png",
            "france2" to "$base/france-2-fr.png",
            "france3" to "$base/france-3-fr.png",
            "france4" to "$base/france-4-fr.png",
            "france5" to "$base/france-5-fr.png",
            "franceinfo" to "$base/franceinfo-fr.png",
            "franceo" to "$base/france-o-fr.png",
            "m6" to "$base/m6-fr.png",
            "w9" to "$base/w9-fr.png",
            "6ter" to "$base/6ter-fr.png",
            "arte" to "$base/arte-fr.png",
            "c8" to "$base/c8-fr.png",
            "cstar" to "$base/cstar-fr.png",
            "cnews" to "$base/cnews-fr.png",
            "nrj12" to "$base/nrj-12-fr.png",
            "lcp" to "$base/lcp-fr.png",
            "gulli" to "$base/gulli-fr.png",
            "lequipe" to "$base/l-equipe-fr.png",
            "lachainelequipe" to "$base/l-equipe-fr.png",
            "rmcstory" to "$base/rmc-story-fr.png",
            "rmcdecouverte" to "$base/rmc-decouverte-fr.png",
            "cherie25" to "$base/cherie-25-fr.png",
            "lci" to "$base/lci-fr.png",
            "bfmtv" to "$base/bfm-tv-fr.png",
            "bfmbusiness" to "$base/bfm-business-fr.png",
            // Info internationale
            "france24" to "$base/france-24-fr.png",
            "tv5monde" to "$base/tv5-monde-fr.png",
            "euronews" to "$base/euronews-fr.png",
            "i24news" to "$base/i24-news-fr.png",
            // Canal+ family
            "canalplus" to "$base/canal-plus-fr.png",
            "canalpluscinema" to "$base/canal-plus-cinema-fr.png",
            "canalplusseries" to "$base/canal-plus-series-fr.png",
            "canalplusfamily" to "$base/canal-plus-family-fr.png",
            "canalplusdocs" to "$base/canal-plus-docs-fr.png",
            "canalplussport" to "$base/canal-plus-sport-fr.png",
            "canalplussport360" to "$base/canal-plus-sport-360-fr.png",
            // Cinéma & séries
            "ocsmax" to "$base/ocs-max-fr.png",
            "ocsgeants" to "$base/ocs-geants-fr.png",
            "ocschoc" to "$base/ocs-choc-fr.png",
            "ocscity" to "$base/ocs-city-fr.png",
            "cineplupremier" to "$base/cine-plus-premier-fr.png",
            "cineplusfrisson" to "$base/cine-plus-frisson-fr.png",
            "cineplusemotion" to "$base/cine-plus-emotion-fr.png",
            "cineplusfamiz" to "$base/cine-plus-famiz-fr.png",
            "cineplusclub" to "$base/cine-plus-club-fr.png",
            "cineplusclassic" to "$base/cine-plus-classic-fr.png",
            "paramountchannel" to "$base/paramount-channel-fr.png",
            "13emerue" to "$base/13eme-rue-fr.png",
            "syfy" to "$base/syfy-fr.png",
            "warnertv" to "$base/warner-tv-fr.png",
            "tcmcinema" to "$base/tcm-cinema-fr.png",
            // Sport
            "beinsports1" to "$base/bein-sports-1-fr.png",
            "beinsports2" to "$base/bein-sports-2-fr.png",
            "beinsports3" to "$base/bein-sports-3-fr.png",
            "beinsports4" to "$base/bein-sports-4-fr.png",
            "rmcsport1" to "$base/rmc-sport-1-fr.png",
            "rmcsport2" to "$base/rmc-sport-2-fr.png",
            "rmcsport3" to "$base/rmc-sport-3-fr.png",
            "rmcsport4" to "$base/rmc-sport-4-fr.png",
            "eurosport1" to "$base/eurosport-1-fr.png",
            "eurosport2" to "$base/eurosport-2-fr.png",
            "infosportplus" to "$base/infosport-plus-fr.png",
            // Musique
            "mtv" to "$base/mtv-fr.png",
            "mtvhits" to "$base/mtv-hits-fr.png",
            "mcm" to "$base/mcm-fr.png",
            "m6music" to "$base/m6-music-fr.png",
            "nrjhits" to "$base/nrj-hits-fr.png",
            "traceurban" to "$base/trace-urban-fr.png",
            "mezzo" to "$base/mezzo-fr.png",
            // Documentaire
            "planeteplus" to "$base/planete-plus-fr.png",
            "ushuaiatv" to "$base/ushuaia-tv-fr.png",
            "histoiretv" to "$base/histoire-tv-fr.png",
            "toutelhistoire" to "$base/toute-l-histoire-fr.png",
            "scienceetvietv" to "$base/science-vie-tv-fr.png",
            "nationalgeographic" to "$base/national-geographic-fr.png",
            "natgeowild" to "$base/nat-geo-wild-fr.png",
            "discoverychannel" to "$base/discovery-channel-fr.png",
            "trek" to "$base/trek-fr.png",
            // Enfants
            "disneychannel" to "$base/disney-channel-fr.png",
            "disneyjunior" to "$base/disney-junior-fr.png",
            "cartoonnetwork" to "$base/cartoon-network-fr.png",
            "boomerang" to "$base/boomerang-fr.png",
            "nickelodeon" to "$base/nickelodeon-fr.png",
            "nickjr" to "$base/nick-jr-fr.png",
            "tiji" to "$base/tiji-fr.png",
            "piwiplus" to "$base/piwi-plus-fr.png",
            "canalj" to "$base/canal-j-fr.png",
            "tfoumax" to "$base/tfou-max-fr.png",
        )
    }

    /** Build a logo URL for a channel — guaranteed to return SOMETHING displayable.
     *  Priority order:
     *   1. manualLogoMap (verified tv-logos URL for popular FR channels)
     *   2. tv-logos slug heuristic (works for many channels)
     *   3. ui-avatars.com fallback (always renders a colored circle with the channel
     *      initials, color picked from category — guarantees no blank cards). */
    private fun logoUrlFor(name: String): String {
        val lookup = normalizeForLogo(name)

        // 1. Manual map first — TNT channels we want to be sure about (verified URLs)
        manualLogoMap[lookup]?.let { return it }

        // 2. iptv-org's curated FR logos — ~800 entries from channels.json + logos.json
        iptvOrgLogoMap[lookup]?.let { return it }

        // 3. ui-avatars fallback — guaranteed renderable image with channel initials
        return uiAvatarFallback(name)
    }

    /** Used by callers (Glide error handler) when the primary URL 404s. Always
     *  returns a renderable image: a colored circle with the channel's initials. */
    fun fallbackLogoUrlFor(name: String): String = uiAvatarFallback(name)

    private fun uiAvatarFallback(name: String): String {
        // Pick a category-themed background so different channel types are distinguishable.
        val color = when (guessCategory(name)) {
            "Sport" -> "388E3C"           // green
            "Cinéma" -> "8E24AA"          // purple
            "Info" -> "D32F2F"            // red
            "Musique" -> "F57C00"         // orange
            "Documentaire" -> "00897B"    // teal
            "Enfants" -> "F06292"         // pink
            else -> "1E88E5"              // blue (généraliste)
        }
        // ui-avatars accepts up to 3 letters. Keep first letter of up to first 3 words.
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

    // ───────── Phase 3 helpers: ingest one cid, probe FR genres, disk cache ─────────

    /** Ingest ALL channels from a cid into the registry. Reusable for primary + scan.
     *  Returns the number of stream refs added (channels merged by normalized name). */
    private suspend fun ingestCidChannels(cid: String, creds: MacCredentials, forceAllGenres: Boolean = false): Int {
        val channels = olaListMacPortalChannels(creds.baseUrl, creds.mac, forceAllGenres)
        var added = 0
        synchronized(registryLock) {
            for (ch in channels) {
                // 2026-05-08 : filtre langue partagé — exclut DE/EN/etc. à l'ingestion
                // pour pas que des chaînes allemandes/anglaises se retrouvent dans le
                // bucket FR (cf. bug Arte allemand sur VegetaTV).
                if (!com.streamflixreborn.streamflix.utils.IptvLangFilter.isFrCompatible(ch.name)) {
                    continue
                }
                val key = norm(ch.name)
                if (key.isBlank()) continue
                val info = channelRegistry.getOrPut(key) {
                    val displayName = baseDisplayName(ch.name)
                    ChannelInfo(
                        displayName = displayName,
                        category = guessCategory(displayName),
                        logo = logoUrlFor(displayName),
                    )
                }
                // Avoid duplicate streams (same cid + same cmd already added)
                if (info.streams.none { it.cid == cid && it.url == ch.cmd }) {
                    // Use a clean variant label (e.g. "HD", "+1", "FR") for the player's "Chaîne" page.
                    val variantLabel = extractVariantLabel(ch.name).ifBlank { "Source ${info.streams.size + 1}" }
                    info.streams.add(OlaStreamRef(cid, variantLabel, ch.cmd))
                    added++
                }
            }
        }
        return added
    }

    /** Quick probe: does this cid's MAC portal have at least one FR-tagged genre?
     *  Used to filter the 1159 cids down to FR-relevant ones in Phase 3.
     *  Skips channel pagination — only does handshake + get_genres (~3s). */
    private suspend fun cidHasFrenchGenre(cid: String): Boolean = withContext(Dispatchers.IO) {
        val creds = getMacCredentials(cid) ?: return@withContext false
        val portalBase = "${creds.baseUrl.trimEnd('/')}/portal.php"
        val cookie = "mac=${java.net.URLEncoder.encode(creds.mac, "UTF-8")}; stb_lang=en; timezone=Europe%2FLondon"
        val stbUA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        try {
            val hsBody = probeClient.newCall(Request.Builder()
                .url("$portalBase?type=stb&action=handshake&token=&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie).build()
            ).execute().body?.string() ?: return@withContext false
            val token = JSONObject(hsBody).getJSONObject("js").getString("token")
            val genreBody = probeClient.newCall(Request.Builder()
                .url("$portalBase?type=itv&action=get_genres&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie)
                .header("Authorization", "Bearer $token").build()
            ).execute().body?.string() ?: return@withContext false
            val arr = JSONObject(genreBody).optJSONArray("js") ?: return@withContext false
            for (g in 0 until arr.length()) {
                val title = arr.optJSONObject(g)?.optString("title", "")?.lowercase() ?: continue
                if (title.startsWith("fr|") || title.startsWith("fr ")
                    || title.contains("france") || title.contains("french") || title.contains("français")) {
                    return@withContext true
                }
            }
            false
        } catch (_: Exception) { false }
    }

    private fun frCidsCacheFile() = java.io.File(StreamFlixApp.instance.filesDir, FR_CIDS_CACHE_FILE)

    private fun loadFrCidsCache(): List<String>? {
        return try {
            val f = frCidsCacheFile()
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            val ts = obj.optLong("ts", 0)
            if (System.currentTimeMillis() - ts > FR_CIDS_CACHE_TTL_MS) return null
            val arr = obj.optJSONArray("cids") ?: return null
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) { null }
    }

    private fun saveFrCidsCache(cids: List<String>) {
        try {
            val f = frCidsCacheFile()
            val obj = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("cids", JSONArray(cids))
            }
            f.writeText(obj.toString())
            Log.d(TAG, "FR cids cache saved: ${cids.size} cids")
        } catch (e: Exception) {
            Log.w(TAG, "FR cids cache save failed: ${e.message}")
        }
    }

    // ─────────────── Channel registry disk cache ───────────────

    private fun registryCacheFile() = java.io.File(StreamFlixApp.instance.filesDir, REGISTRY_CACHE_FILE)

    /** Serialise the in-memory channelRegistry as JSON: { ts, channels: { key: {name, cat, logo, streams: [{cid,label,url}, ...]} } }
     *  Saved after Phase 2/3 to skip the heavy startup work on next launches. */
    private fun saveRegistryCache() {
        try {
            val snapshot = synchronized(registryLock) { LinkedHashMap(channelRegistry) }
            if (snapshot.isEmpty()) return
            val channels = JSONObject()
            for ((key, info) in snapshot) {
                val streams = JSONArray()
                for (s in info.streams) {
                    streams.put(JSONObject().apply {
                        put("cid", s.cid); put("label", s.label); put("url", s.url)
                    })
                }
                channels.put(key, JSONObject().apply {
                    put("name", info.displayName)
                    put("cat", info.category)
                    put("logo", info.logo)
                    put("streams", streams)
                })
            }
            val obj = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("channels", channels)
            }
            registryCacheFile().writeText(obj.toString())
            Log.d(TAG, "Registry cache saved: ${snapshot.size} channels")
        } catch (e: Exception) {
            Log.w(TAG, "Registry cache save failed: ${e.message}")
        }
    }

    /** Load the registry from disk if cache is fresh (<TTL). Returns true on success. */
    private fun loadRegistryCache(): Boolean {
        return try {
            val f = registryCacheFile()
            if (!f.exists()) return false
            val obj = JSONObject(f.readText())
            val ts = obj.optLong("ts", 0)
            if (System.currentTimeMillis() - ts > REGISTRY_CACHE_TTL_MS) {
                Log.d(TAG, "Registry cache expired, will re-fetch")
                return false
            }
            val channels = obj.optJSONObject("channels") ?: return false
            synchronized(registryLock) {
                channelRegistry.clear()
                val it = channels.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    val ch = channels.optJSONObject(key) ?: continue
                    val info = ChannelInfo(
                        displayName = ch.optString("name"),
                        category = ch.optString("cat", "Généraliste"),
                        logo = ch.optString("logo"),
                    )
                    val arr = ch.optJSONArray("streams") ?: continue
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        info.streams.add(OlaStreamRef(
                            cid = s.optString("cid"),
                            label = s.optString("label"),
                            url = s.optString("url"),
                        ))
                    }
                    if (info.streams.isNotEmpty()) channelRegistry[key] = info
                }
            }
            Log.d(TAG, "Registry cache loaded: ${channelRegistry.size} channels (age=${(System.currentTimeMillis() - ts) / 1000}s)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Registry cache load failed: ${e.message}")
            false
        }
    }

    /** Phase 3: scan up to PHASE3_MAX_CANDIDATES candidate cids in parallel.
     *  For each FR-tagged cid found, ingest channels into the existing registry.
     *  Stops at PHASE3_MAX_FR_CIDS cids found OR candidates exhausted. */
    private suspend fun scanAdditionalFrCids(primaryCid: String) {
        if (phase3Done) return
        phase3Mutex.withLock {
            if (phase3Done) return@withLock
            val t0 = System.currentTimeMillis()

            // Try disk cache first — if valid, skip the expensive probe
            val cachedCids = loadFrCidsCache()
            if (cachedCids != null && cachedCids.isNotEmpty()) {
                Log.d(TAG, "Phase 3: using cached FR cids (${cachedCids.size}) — ingesting directly")
                val toIngest = cachedCids.filter { it != primaryCid }.take(PHASE3_MAX_FR_CIDS - 1)
                coroutineScope {
                    val sem = kotlinx.coroutines.sync.Semaphore(PHASE3_PARALLELISM)
                    toIngest.map { cid ->
                        async {
                            sem.acquire()
                            try {
                                val creds = getMacCredentials(cid) ?: return@async
                                val n = ingestCidChannels(cid, creds, forceAllGenres = false)
                                if (n > 0) {
                                    frCids.add(cid)
                                    Log.d(TAG, "  cached cid=$cid → +$n streams")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "  cached cid=$cid failed: ${e.message}")
                            } finally { sem.release() }
                        }
                    }.awaitAll()
                }
                phase3Done = true
                Log.d(TAG, "Phase 3 (cached) done in ${System.currentTimeMillis() - t0}ms — ${frCids.size} FR cids active")
                return@withLock
            }

            // No cache → fresh scan
            val candidates = olaTvServerMap.keys
                .filter { it != primaryCid }
                .shuffled()
                .take(PHASE3_MAX_CANDIDATES)
            Log.d(TAG, "Phase 3 fresh scan: probing ${candidates.size} candidate cids…")

            val foundCids = java.util.concurrent.CopyOnWriteArrayList<String>()
            foundCids.add(primaryCid)

            coroutineScope {
                val sem = kotlinx.coroutines.sync.Semaphore(PHASE3_PARALLELISM)
                val jobs = candidates.map { cid ->
                    async {
                        sem.acquire()
                        try {
                            if (foundCids.size >= PHASE3_MAX_FR_CIDS) return@async
                            if (!cidHasFrenchGenre(cid)) return@async
                            val creds = getMacCredentials(cid) ?: return@async
                            val n = ingestCidChannels(cid, creds, forceAllGenres = false)
                            if (n > 0) {
                                foundCids.add(cid)
                                frCids.add(cid)
                                Log.d(TAG, "  Phase 3 cid=$cid IS FR → +$n streams (total FR cids: ${foundCids.size})")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "  Phase 3 cid=$cid failed: ${e.message}")
                        } finally { sem.release() }
                    }
                }
                jobs.awaitAll()
            }

            saveFrCidsCache(foundCids.toList())
            phase3Done = true
            Log.d(TAG, "Phase 3 fresh done in ${System.currentTimeMillis() - t0}ms — ${foundCids.size} FR cids active")
        }
    }

    // ───────── Catalog loading ─────────

    private suspend fun ensureRegistry() {
        if (registryLoaded && System.currentTimeMillis() - lastLoadTime < CACHE_DURATION) return
        // Load persistent caches on first call
        if (workingChannelUrls.isEmpty()) loadWorkingChannelUrls()
        if (deadDomains.isEmpty()) loadDeadDomains()
        registryMutex.withLock {
            if (registryLoaded && System.currentTimeMillis() - lastLoadTime < CACHE_DURATION) return@withLock

            // ↓ KEY: do NOT clear on retry. If a previous (possibly-cancelled) load already
            // populated the registry, we keep it and just retry the load to top it up.
            // We only clear on a full cache expiry (i.e. registry is loaded but stale).
            val cacheExpired = registryLoaded &&
                System.currentTimeMillis() - lastLoadTime >= CACHE_DURATION
            if (cacheExpired) synchronized(registryLock) { channelRegistry.clear() }
            registryLoaded = false

            val t0 = System.currentTimeMillis()

            // ── Step 0: try the disk cache first — restores ~800 channels in <100ms vs
            // ~30s for the full Phase 1+2 fetch. Phase 3 still refreshes in background.
            if (loadRegistryCache()) {
                registryLoaded = true
                lastLoadTime = System.currentTimeMillis()
                Log.d(TAG, "Registry restored from disk in ${System.currentTimeMillis() - t0}ms — refreshing in background")
                // Kick off Phase 3 anyway to top up streams from any new cids.
                scope.launch {
                    withContext(Dispatchers.IO + NonCancellable) {
                        try {
                            // Need olaTvServerMap for Phase 3 candidate selection — parse it.
                            parseOlaTvServerList()
                            val primaryCid = olaTvServerMap.entries.find { it.value == OLA_TV_PRIMARY_FR }?.key
                            if (primaryCid != null) {
                                scanAdditionalFrCids(primaryCid)
                                saveRegistryCache()  // refresh disk with newly enriched streams
                            }
                        } catch (e: Exception) { Log.w(TAG, "Background refresh after cache hit: ${e.message}") }
                    }
                }
                return@withLock
            }

            // ↓ KEY: NonCancellable wraps the load so navigation-away doesn't lose the
            // result mid-flight. The user can leave; the load completes; next visit finds
            // cached data ready.
            withContext(Dispatchers.IO + NonCancellable) {
                try {
                    // Step 1: get the global server list (1159 cids)
                    parseOlaTvServerList()
                    if (olaTvServerMap.isEmpty()) {
                        Log.e(TAG, "Server list empty — cannot proceed")
                        return@withContext
                    }

                    // Step 2: find the cid mapped to category_name "2020" (primary FR per OLA TV)
                    val primaryCid = olaTvServerMap.entries.find { it.value == OLA_TV_PRIMARY_FR }?.key
                    if (primaryCid == null) {
                        Log.e(TAG, "Primary FR cid (category_name=$OLA_TV_PRIMARY_FR) not found in server list")
                        return@withContext
                    }
                    Log.d(TAG, "Primary FR cid = $primaryCid (category_name=$OLA_TV_PRIMARY_FR)")

                    // Step 3: get MAC credentials + scrape MAC portal channel list
                    val creds = getMacCredentials(primaryCid)
                    if (creds == null) {
                        Log.e(TAG, "Could not extract MAC credentials for primary cid")
                        return@withContext
                    }
                    Log.d(TAG, "Primary FR portal: ${creds.baseUrl}")

                    // Primary cid is user-confirmed FR → fetch all genres even if not tagged "FR|"
                    val added = ingestCidChannels(primaryCid, creds, forceAllGenres = true)
                    frCids.add(primaryCid)
                    Log.d(TAG, "Primary FR cid ingested: $added streams, registry size=${channelRegistry.size} in ${System.currentTimeMillis() - t0}ms")

                    // ↓ KEY: set the loaded flag INSIDE the IO block, right after registry is
                    // populated. This way even if the calling scope cancels right after,
                    // subsequent calls see registryLoaded=true and skip the costly reload.
                    if (channelRegistry.isNotEmpty()) {
                        registryLoaded = true
                        lastLoadTime = System.currentTimeMillis()
                    }

                    // ─── Phase 3: scan additional FR cids in BACKGROUND ───
                    // Don't block ensureRegistry — fire-and-forget. As more cids are confirmed FR
                    // and ingested, channels gain more OlaStreamRef entries. User sees more
                    // servers in the picker the next time they open a channel.
                    scope.launch {
                        try {
                            scanAdditionalFrCids(primaryCid)
                            // Save full registry to disk so next launch boots in <100ms.
                            saveRegistryCache()
                        } catch (e: Exception) { Log.w(TAG, "Phase 3 background scan failed: ${e.message}") }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Load failed: ${e.message}", e)
                    // Even on partial failure, mark loaded if we got something
                    if (channelRegistry.isNotEmpty()) {
                        registryLoaded = true
                        lastLoadTime = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    // App-scoped supervisor for Phase 3 background work. Outlives the calling fragment scope.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Progressive servers flow — same pattern as WiTvProvider. Player collects this
    // and adds each emitted server to the "Chaîne" page so the user can switch
    // between OLA TV variants without closing the player.
    // replay = 50 so that variants emitted between getServers() and the player's
    // collector subscribing are still delivered (otherwise they would be lost).
    private val _additionalServers = MutableSharedFlow<Video.Server>(replay = 50, extraBufferCapacity = 100)
    override val additionalServersFlow: SharedFlow<Video.Server> = _additionalServers

    // Track current emit job so we can cancel it when the user switches channels.
    private var currentEmitJob: Job? = null

    /** Stop background server emission (e.g. when playback is working). */
    fun stopEmission() {
        currentEmitJob?.cancel()
        currentEmitJob = null
        Log.d(TAG, "stopEmission: background server loading cancelled")
    }

    // ───────── Provider API ─────────

    override suspend fun getHome(): List<Category> = try {
        // Kick off registry loading in background — never blocks the home render. The
        // page shows the FROZEN curated list with hardcoded logos for known channels.
        // Streams get linked at click-time; if they aren't ingested yet, the click waits.
        scope.launch { try { ensureRegistry() } catch (_: Exception) { } }

        val categoryOrder = listOf("Généraliste", "Cinéma", "Canal+ Live", "Info", "Sport", "Musique", "Documentaire", "Enfants")
        val sections = mutableListOf<Category>()
        // 2026-05-08 : helper inline pour filtrer une chaîne bannie. Cross-provider
        // via IptvBannedChannels.normalize() qui strip "ola::".
        val isChannelBanned: (String) -> Boolean = { id ->
            com.streamflixreborn.streamflix.fragments.player.settings
                .IptvBannedChannels.isBanned(id)
        }
        for (catName in categoryOrder) {
            val items = curatedChannels
                .filter { it.category == catName }
                .filter { !isChannelBanned("ola::${it.key}") }
                .map { c ->
                    TvShow(
                        id = "ola::${c.key}",
                        title = c.displayName,
                        poster = logoUrlFor(c.displayName),
                        banner = logoUrlFor(c.displayName),
                        providerName = name,
                    )
                }
            if (items.isNotEmpty()) sections.add(Category(name = catName, list = items))
        }

        // ─── "Autres chaînes" — FR channels in the registry not in the curated
        // list. Capped at OTHER_CHANNELS_HOME_LIMIT to keep the home RecyclerView
        // responsive on TV (full lists with hundreds of items + image loads can
        // ANR the main thread when the user scrolls onto the row). Use the
        // dedicated "Toutes les chaînes" page (getTvShows) for the full set.
        val curatedKeys = curatedChannels.map { it.key }.toSet()
        // Snapshot quickly under the lock, do filtering/sorting/mapping outside
        // so the lock isn't held during String.lowercase() and TvShow allocation.
        val registrySnapshot = synchronized(registryLock) {
            channelRegistry.entries
                .filter { (key, _) -> key !in curatedKeys }
                .map { (key, info) -> Triple(key, info.displayName, info.logo) }
        }
        val extraItems = registrySnapshot
            .filter { !isChannelBanned("ola::${it.first}") }
            .sortedBy { it.second.lowercase() }
            .take(OTHER_CHANNELS_HOME_LIMIT)
            .map { (key, displayName, logo) ->
                TvShow(
                    id = "ola::$key",
                    title = displayName,
                    poster = logo.ifEmpty { logoUrlFor(displayName) },
                    banner = logo.ifEmpty { logoUrlFor(displayName) },
                    providerName = name,
                )
            }
        if (extraItems.isNotEmpty()) {
            sections.add(Category(name = "Autres chaînes", list = extraItems))
        }

        // 2026-05-08 (pivot) : section "★ Favoris" RETIRÉE de Ola.
        // Favoris UNIQUEMENT dans TV Hub (option 1).

        // 2026-05-08 : section "✕ Chaînes bannies" EN BAS du home.
        // Dossier fixe pour ranger les chaînes bannies. Construit à partir du
        // curated + registry. Click sur une chaîne bannie → long-press → menu
        // "Débannir" pour la réactiver.
        try {
            val bannedKeys = com.streamflixreborn.streamflix.fragments.player.settings
                .IptvBannedChannels.getAllBannedKeys()
            if (bannedKeys.isNotEmpty()) {
                val bannedItems = mutableListOf<TvShow>()
                val seen = mutableSetOf<String>()
                for (c in curatedChannels) {
                    val id = "ola::${c.key}"
                    if (com.streamflixreborn.streamflix.fragments.player.settings
                            .IptvBannedChannels.isBanned(id)) {
                        if (seen.add(id)) {
                            bannedItems += TvShow(
                                id = id,
                                title = c.displayName,
                                poster = logoUrlFor(c.displayName),
                                banner = logoUrlFor(c.displayName),
                                providerName = name,
                            )
                        }
                    }
                }
                val regSnap2 = synchronized(registryLock) {
                    channelRegistry.entries.map { (k, info) -> Triple(k, info.displayName, info.logo) }
                }
                for ((key, displayName, logo) in regSnap2) {
                    val id = "ola::$key"
                    if (com.streamflixreborn.streamflix.fragments.player.settings
                            .IptvBannedChannels.isBanned(id)) {
                        if (seen.add(id)) {
                            bannedItems += TvShow(
                                id = id,
                                title = displayName,
                                poster = logo.ifEmpty { logoUrlFor(displayName) },
                                banner = logo.ifEmpty { logoUrlFor(displayName) },
                                providerName = name,
                            )
                        }
                    }
                }
                if (bannedItems.isNotEmpty()) {
                    sections.add(Category(name = "✕ Chaînes bannies", list = bannedItems))
                }
            }
        } catch (_: Throwable) { }

        sections
    } catch (e: Exception) {
        Log.e(TAG, "getHome error", e)
        emptyList()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        return try {
            // Curated list first (instantaneous), then any registry channel that matches
            // but isn't already in the curated set — covers chaînes "Autres". Capped at
            // SEARCH_RESULT_LIMIT so a generic query like "tf" doesn't return 80+ items
            // and ANR the TV RecyclerView on logo loads.
            val curatedKeys = curatedChannels.map { it.key }.toSet()
            val curatedHits = curatedChannels
                .filter { it.displayName.contains(query, ignoreCase = true) }
                .map { c ->
                    TvShow(
                        id = "ola::${c.key}",
                        title = c.displayName,
                        poster = logoUrlFor(c.displayName),
                        providerName = name,
                    )
                }
            // Snapshot under lock, allocate outside to keep the lock short.
            val registrySnapshot = synchronized(registryLock) {
                channelRegistry.entries
                    .filter { (key, info) -> key !in curatedKeys && info.displayName.contains(query, ignoreCase = true) }
                    .map { (key, info) -> Triple(key, info.displayName, info.logo) }
            }
            val registryHits = registrySnapshot
                .sortedBy { it.second.lowercase() }
                .map { (key, displayName, logo) ->
                    TvShow(
                        id = "ola::$key",
                        title = displayName,
                        poster = logo.ifEmpty { logoUrlFor(displayName) },
                        providerName = name,
                    )
                }
            (curatedHits + registryHits).take(SEARCH_RESULT_LIMIT)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            scope.launch { try { ensureRegistry() } catch (_: Exception) { } }
            // Paginated catalog: curated list first (fixed order), then alphabetically-sorted
            // upstream-only channels. Returning all 80+ curated + 100-300 registry channels
            // at once on TV ANRs the RecyclerView. PAGE_SIZE chunks keep it responsive.
            val curatedKeys = curatedChannels.map { it.key }.toSet()
            val curatedItems = curatedChannels.map { c ->
                TvShow(
                    id = "ola::${c.key}",
                    title = c.displayName,
                    poster = logoUrlFor(c.displayName),
                    providerName = name,
                )
            }
            val extraSnapshot = synchronized(registryLock) {
                channelRegistry.entries
                    .filter { (key, _) -> key !in curatedKeys }
                    .map { (key, info) -> Triple(key, info.displayName, info.logo) }
            }
            val extraItems = extraSnapshot
                .sortedBy { it.second.lowercase() }
                .map { (key, displayName, logo) ->
                    TvShow(
                        id = "ola::$key",
                        title = displayName,
                        poster = logo.ifEmpty { logoUrlFor(displayName) },
                        providerName = name,
                    )
                }
            val all = curatedItems + extraItems
            val from = ((page - 1).coerceAtLeast(0)) * TV_SHOWS_PAGE_SIZE
            if (from >= all.size) emptyList()
            else all.subList(from, (from + TV_SHOWS_PAGE_SIZE).coerceAtMost(all.size))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovie(id: String): Movie = throw Exception("Not supported")

    override suspend fun getTvShow(id: String): TvShow = try {
        // Click-time wait for the registry — by now Phase 1+2 are usually done since
        // the home triggered them in background. If a channel isn't ingested yet, we
        // synthesise a minimal TvShow so navigation doesn't crash.
        ensureRegistry()
        val key = id.removePrefix("ola::")
        val info = synchronized(registryLock) { channelRegistry[key] }
        if (info == null) {
            val curated = curatedChannels.firstOrNull { it.key == key }
            TvShow(
                id = id,
                title = curated?.displayName ?: key,
                poster = curated?.let { logoUrlFor(it.displayName) },
                providerName = name,
                seasons = listOf(
                    Season(id = id, number = 1, title = "En Direct",
                        episodes = listOf(Episode(id = id, number = 1,
                            title = "Regarder en Direct")))
                ),
            )
        } else {
            val logo = info.logo.ifBlank { null }
            toTvShow(key, info).copy(
                seasons = listOf(
                    Season(id = id, number = 1, title = "En Direct",
                        episodes = listOf(Episode(id = id, number = 1,
                            title = "Regarder en Direct", poster = logo)))
                )
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "getTvShow($id) error", e); throw e
    }

    /** Returns the single live "Regarder en Direct" episode for the season.
     *  seasonId == tvShowId == episodeId == "ola::<channelKey>" by design. */
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> =
        listOf(Episode(id = seasonId, number = 1, title = "Regarder en Direct"))

    override suspend fun getGenre(id: String, page: Int): Genre = throw Exception("Not supported")

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            ensureRegistry()
            val key = id.removePrefix("ola_ep::").removePrefix("ola::")
            val info = synchronized(registryLock) { channelRegistry[key] }
            if (info == null) {
                Log.w(TAG, "getServers: channel '$key' not found in registry (size=${channelRegistry.size})")
                // Player will keep itself open in IPTV mode and wait for progressive servers.
                // Even with no streams known yet, kick off a Phase-3 wait + retry loop so a
                // late discovery can still feed the player.
                spawnLateRegistryWatcher(key)
                return emptyList()
            }
            // Rotation: order streams by
            // (1) previously-working streams first (probeOkCache = already played OK)
            // (2) demoted (recently failed) last
            // (3) host with best historical success rate first
            // This ensures channels that worked before play instantly on re-click.
            // Smart sort: direct streams first, previously-working first, dead hosts last.
            // "Direct" = real URL (http://...), not a localhost MAC portal placeholder.
            // MAC portals require handshake + create_link and tokens expire fast → unreliable.
            // Filter out streams whose host is blacklisted (dead domain) — no point
            // handing them to the player only to waste 5-10s per dead host.
            val aliveStreams = info.streams.filter { stream ->
                val raw = stream.url.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
                val host = try { java.net.URI(raw).host ?: "" } catch (_: Exception) { "" }
                val dead = host.isNotBlank() && isDomainDead(host)
                if (dead) Log.d(TAG, "  filtered dead-domain stream: ${stream.label} ($host)")
                !dead
            }
            val sorted = aliveStreams.sortedWith(
                compareByDescending<OlaStreamRef> { it.url in probeOkCache }  // known-good first
                    .thenBy { it.url in demotedUrls }                         // known-bad last
                    .thenByDescending {                                        // direct > MAC portal
                        val raw = it.url.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
                        raw.startsWith("http") && !raw.contains("localhost") && !raw.contains("127.0.0.1")
                    }
                    .thenByDescending { hostScore(it.url) }                   // best host score
            )
            // Host diversity: limit to MAX_PER_HOST streams per MAC portal host.
            // Without this, 96 streams from wowtv.cc = cycling through 96 dead variants
            // of the same broken host. With cap=2, we try 2 from host A, 2 from host B, etc.
            val MAX_PER_HOST = 2
            val hostCounts = mutableMapOf<String, Int>()
            val streamsSnapshot = sorted.filter { stream ->
                val raw = stream.url.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
                val host = try { java.net.URI(raw).host ?: "" } catch (_: Exception) { "" }
                if (host.isBlank()) return@filter true  // can't determine host, keep it
                val count = hostCounts.getOrDefault(host, 0)
                if (count >= MAX_PER_HOST) {
                    false  // already have enough from this host
                } else {
                    hostCounts[host] = count + 1
                    true
                }
            }
            Log.d(TAG, "getServers '$key' (${info.displayName}): ${sorted.size} total → ${streamsSnapshot.size} after host-diversity cap — progressive emission")

            // Store full sorted pool for server renewal — when the player exhausts
            // the current batch, it can call requestNextBatch() to get more.
            synchronized(registryLock) {
                fullStreamPool[key] = sorted.toList()
                emittedStreamUrls[key] = streamsSnapshot.map { it.url }.toMutableSet()
            }

            // Fast-track: if we have a previously-working URL for this channel (persisted
            // to disk), inject it as the FIRST server. This URL is a resolved direct URL
            // (not a MAC portal placeholder), so getVideo will play it immediately without
            // any handshake — instant channel switch for known-good channels.
            val fastTrackUrl = workingChannelUrls[key]
            val initialServers = mutableListOf<Video.Server>()
            if (!fastTrackUrl.isNullOrBlank()) {
                Log.d(TAG, "  fast-track for '$key': ${fastTrackUrl.take(80)}")
                initialServers.add(Video.Server(
                    id = "ola_fasttrack::$key::$fastTrackUrl",
                    name = "OLA TV - Last Working",
                ))
            }

            // Build a Video.Server for the first stream
            val first = streamsSnapshot.firstOrNull()
            if (first != null) {
                Log.d(TAG, "  primary[0] cid=${first.cid} label='${first.label}' cmd=${first.url.take(80)}")
                initialServers.add(Video.Server(
                    id = "ola_stream::${first.cid}::${first.label}::${first.url}",
                    name = "OLA TV - ${first.label}",
                ))
            }

            // Cancel any previous emit job (channel switch) and start a new one.
            currentEmitJob?.cancel()
            // CRITICAL: clear the replay buffer so a new subscriber (e.g. MiniPlayerController
            // switching from TF1 to France 2) doesn't receive stale servers from the previous channel.
            _additionalServers.resetReplayCache()
            currentEmitJob = scope.launch {
                // Tiny delay so the player has time to subscribe to the flow.
                delay(150)
                // Emit additional variants — SKIP the first stream because it's already
                // the initial server. De-duplicate by URL. Cap to MAX_VARIANTS_PER_CHANNEL
                // so a hot channel with 50 cids doesn't flood the Chaîne page (memory + UX).
                val emittedUrls = HashSet<String>()
                streamsSnapshot.firstOrNull()?.let { emittedUrls.add(it.url) }
                var emittedCount = 0
                streamsSnapshot.drop(1).forEach { stream ->
                    if (!isActive) return@launch
                    if (emittedCount >= MAX_VARIANTS_PER_CHANNEL) {
                        Log.d(TAG, "  cap reached ($MAX_VARIANTS_PER_CHANNEL variants) — skipping rest")
                        return@forEach
                    }
                    if (!emittedUrls.add(stream.url)) {
                        Log.d(TAG, "  skip duplicate URL ${stream.label} (cid=${stream.cid})")
                        return@forEach
                    }
                    val server = Video.Server(
                        id = "ola_stream::${stream.cid}::${stream.label}::${stream.url}",
                        name = "OLA[$key] ${stream.label}",
                    )
                    Log.d(TAG, "  → emit OLA[$key] ${stream.label} (cid=${stream.cid})")
                    _additionalServers.emit(server)
                    emittedCount++
                }

                // DON'T emit late Phase 3 servers to the player — they flood the Chaîne page
                // and cause unnecessary loading. The renewal mechanism (requestNextBatch)
                // will pull from the full pool when the player exhausts current servers.
                // Just update the fullStreamPool so renewal has access to late discoveries.
                val seenUrls = streamsSnapshot.map { it.url }.toMutableSet()
                val waitDeadlineMs = System.currentTimeMillis() + 60_000L
                while (isActive && System.currentTimeMillis() < waitDeadlineMs && !phase3Done) {
                    delay(1500)
                    val latest = synchronized(registryLock) { channelRegistry[key]?.streams?.toList() } ?: continue
                    val newStreams = latest.filter { seenUrls.add(it.url) }
                    if (newStreams.isNotEmpty()) {
                        // Update the full pool for renewal — but don't emit to the player
                        synchronized(registryLock) {
                            fullStreamPool[key] = latest
                        }
                        Log.d(TAG, "  ${newStreams.size} late streams added to pool for '$key' (total: ${latest.size})")
                    }
                }
                Log.d(TAG, "OLA TV emit done for '$key' — ${seenUrls.size} stream(s) in pool")
            }

            initialServers
        } catch (e: Exception) {
            Log.e(TAG, "getServers($id) error", e); emptyList()
        }
    }

    /** When getServers is called on a channel that has 0 streams yet (very rare —
     *  Phase 2 not finished), spawn a watcher that emits as soon as streams appear. */
    private fun spawnLateRegistryWatcher(key: String) {
        currentEmitJob?.cancel()
        currentEmitJob = scope.launch {
            val seenUrls = mutableSetOf<String>()
            val deadline = System.currentTimeMillis() + 90_000L
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(1000)
                val latest = synchronized(registryLock) { channelRegistry[key]?.streams?.toList() } ?: continue
                for (s in latest) {
                    if (seenUrls.add(s.url)) {
                        val server = Video.Server(
                            id = "ola_stream::${s.cid}::${s.label}::${s.url}",
                            name = "OLA[$key] ${s.label}",
                        )
                        Log.d(TAG, "  → late-watch emit OLA[$key] ${s.label} (cid=${s.cid})")
                        _additionalServers.emit(server)
                    }
                }
                if (phase3Done && seenUrls.isNotEmpty()) break
            }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        try {
            // Fast-track: direct URL from working cache — skip all MAC portal resolution.
            if (server.id.startsWith("ola_fasttrack::")) {
                val ftParts = server.id.removePrefix("ola_fasttrack::").split("::", limit = 2)
                val ftUrl = ftParts.getOrNull(1) ?: throw Exception("Bad fast-track id")
                Log.d(TAG, "getVideo fast-track → ${ftUrl.take(120)}")
                return@withContext Video(ftUrl, headers = mapOf("User-Agent" to USER_AGENT))
            }
            // Format: "ola_stream::<cid>::<label>::<cmd>"
            val parts = server.id.removePrefix("ola_stream::").split("::", limit = 3)
            if (parts.size < 3) throw Exception("Bad server id: ${server.id}")
            val cid = parts[0]
            val cmd = parts[2]

            val rawCmd = cmd.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
            val isLocalhost = rawCmd.contains("localhost") || rawCmd.contains("127.0.0.1")
            val cacheKey = "$cid::$cmd"

            val streamUrl = when {
                // Direct upstream URL (no resolution needed) — instant play.
                rawCmd.startsWith("http") && !isLocalhost -> rawCmd
                // Localhost placeholder — must hit create_link to get the real URL.
                // Never cache these: MAC portal tokens expire within minutes → 401 errors.
                // Always resolve fresh for reliability.
                else -> {
                    val creds = getMacCredentials(cid) ?: throw Exception("Could not get MAC creds for cid=$cid")
                    val resolved = resolveStreamCmd(creds.baseUrl, creds.mac, cmd)
                        ?: throw Exception("create_link returned null")
                    Log.d(TAG, "getVideo resolved fresh for cid=$cid: ${resolved.take(80)}")
                    resolved
                }
            }
            // Soft HEAD probe — demotes streams that fail but does NOT block playback.
            // The player's buffering watchdog (10s) handles truly dead streams.
            // This lets slow-responding but working streams play immediately.
            if (!probeOkCache.contains(streamUrl)) {
                val probeOk = probeStreamHead(streamUrl)
                if (probeOk) {
                    probeOkCache.add(streamUrl)
                    demotedUrls.remove(streamUrl)
                } else {
                    Log.w(TAG, "HEAD probe failed (soft) — will try anyway: ${streamUrl.take(80)}")
                    // Demote but don't throw — let ExoPlayer attempt the stream.
                    demotedUrls.add(streamUrl)
                }
            }
            Log.d(TAG, "getVideo → ${streamUrl.take(120)}")
            Video(streamUrl, headers = mapOf("User-Agent" to USER_AGENT))
        } catch (e: Exception) {
            Log.e(TAG, "getVideo error", e); throw e
        }
    }

    /**
     * 2026-05-09 v18 : refresh server URL for an existing OLA Video.Server.
     * Symétrique à VegetaTvProvider.refreshServerUrl : sur 403 / token expiré,
     * refait un handshake Stalker et un create_link pour obtenir une URL
     * fraîche avec un play_token tout neuf. Le fast-path "URL HTTP directe →
     * as-is" de resolveStreamCmd est BYPASSÉ ici (sinon on retombe sur l'URL
     * expirée). Returns le Video.Server avec la nouvelle URL ou null en cas
     * d'échec.
     */
    suspend fun refreshServerUrl(server: Video.Server): Video.Server? = withContext(Dispatchers.IO) {
        try {
            val parts = server.id.removePrefix("ola_stream::").split("::", limit = 3)
            if (parts.size < 3) return@withContext null
            val cid = parts[0]
            val label = parts[1]
            val cmd = parts[2]
            val creds = getMacCredentials(cid) ?: run {
                Log.d(TAG, "refreshServerUrl: no MAC creds for cid=$cid")
                return@withContext null
            }
            // Force handshake + create_link même si cmd est URL HTTP directe.
            // C'est le seul moyen d'obtenir un nouveau play_token côté Stalker.
            val rawCmd = cmd.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
            val newUrl = try {
                val encodedMac = java.net.URLEncoder.encode(creds.mac, "UTF-8")
                val portalBase = "${creds.baseUrl.trimEnd('/')}/portal.php"
                val cookie = "mac=$encodedMac; stb_lang=en; timezone=Europe%2FLondon"
                val stbUA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
                val hsBody = client.newCall(Request.Builder()
                    .url("$portalBase?type=stb&action=handshake&token=&JsHttpRequest=1-xml")
                    .header("User-Agent", stbUA).header("Cookie", cookie).build()
                ).execute().body?.string() ?: ""
                val token = JSONObject(hsBody).getJSONObject("js").getString("token")
                val cmdEncoded = java.net.URLEncoder.encode(rawCmd, "UTF-8")
                val linkReq = Request.Builder()
                    .url("$portalBase?type=itv&action=create_link&cmd=$cmdEncoded&series=&forced_storage=undefined&fav=0&JsHttpRequest=1-xml")
                    .header("User-Agent", stbUA).header("Cookie", cookie)
                    .header("Authorization", "Bearer $token").build()
                val linkBody = client.newCall(linkReq).execute().body?.string() ?: ""
                JSONObject(linkBody).getJSONObject("js").getString("cmd")
                    .removePrefix("ffrt ").removePrefix("ffmpeg ").trim().ifBlank { null }
            } catch (e: Exception) {
                Log.w(TAG, "refreshServerUrl create_link failed: ${e.message}")
                null
            } ?: return@withContext null
            Log.d(TAG, "refreshServerUrl: cid=$cid → fresh token URL ${newUrl.take(80)}")
            // Note : on ne modifie PAS l'OlaStreamRef en mémoire — la cmd
            // canonique reste identique, c'est juste le token résolu qui change.
            // Le nouveau Video.Server contient la nouvelle URL résolue dans son id.
            // ATTENTION : on ne peut pas mettre l'URL résolue dans l'id (le format
            // attendu par getVideo c'est cmd, pas url). Donc on retourne un id
            // de fast-track qui contourne resolveStreamCmd.
            Video.Server(
                id = "ola_fasttrack::${cid}::${newUrl}",
                name = server.name,
            )
        } catch (e: Exception) {
            Log.w(TAG, "refreshServerUrl error: ${e.message}")
            null
        }
    }

    // ───────── Helpers ─────────

    private fun toTvShow(key: String, info: ChannelInfo): TvShow = TvShow(
        id = "ola::$key",
        title = info.displayName,
        poster = info.logo.ifBlank { null },
        banner = info.logo.ifBlank { null },
        providerName = name,
    )
}
