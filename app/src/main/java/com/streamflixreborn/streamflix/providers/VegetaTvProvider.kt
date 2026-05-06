package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.Random

/**
 * Vegeta TV provider — rewired to use Stalker MAC protocol instead of Xtream Codes.
 * The Vegeta servers at http://212.47.64.168/serveurs.txt are actually Stalker MAC portals.
 *
 * Strategy:
 *  - Phase 1: parseVegetaServerList → parse serveurs.txt, filter FR/GLOBAL flags
 *  - Phase 2 (synchronous, ~5-10s): fetch channels via Stalker MAC handshake + get_genres + get_ordered_list
 *    from top 3-5 FR servers, index in registry by normalized name. Displayed immediately.
 *  - Phase 3 (background, opportunistic): scan remaining FR servers in parallel,
 *    enrich registry with extra stream variants for each known channel.
 */
object VegetaTvProvider : Provider, IptvProvider {

    override val name = "Vegeta TV"
    override val baseUrl = "http://212.47.64.168"
    override val logo = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_vegetatv"
    override val language = "fr"

    private const val TAG = "VegetaTvProvider"
    private const val USER_AGENT = "VLC/3.0.18 LibVLC/3.0.18"
    private const val VEGETA_SERVERS_URL = "http://212.47.64.168/serveurs.txt"

    // ───────── HTTP ─────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .dns(DnsResolver.doh)
        .retryOnConnectionFailure(true)
        .build()

    private val probeClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    // ───────── Channel registry ─────────

    private data class VegetaStreamRef(val serverIdx: Int, val label: String, val url: String)

    private class ChannelInfo(
        val displayName: String,
        var category: String = "Généraliste",
        var logo: String = "",
        val streams: MutableList<VegetaStreamRef> = mutableListOf(),
    )

    private val channelRegistry = LinkedHashMap<String, ChannelInfo>()
    private val registryLock = Any()
    private val registryMutex = Mutex()
    @Volatile private var registryLoaded = false
    @Volatile private var lastLoadTime = 0L
    private const val CACHE_DURATION = 30 * 60 * 1000L // 30 min

    // Vegeta servers: list of (index, baseUrl, isFr).
    //  - idx: 1-indexed URL position in serveurs.txt (i.e. ignoring blank lines)
    //  - isFr: true when flagged 🇫🇷/FR, OR when URL-position is in 39..47
    //    (the user confirmed positions 39-47 are the FR block)
    private data class StalkerServer(
        val idx: Int,
        val baseUrl: String,
        val isFr: Boolean,
        /** Full Xtream get.php URL with username/password as found in serveurs.txt */
        val xtreamUrl: String = "",
    )

    /** CORS proxy chain — same list as the Vegeta web player uses. We try direct
     *  fetch first; if that returns empty body, we cycle through these proxies
     *  (which have cloud IPs the FR Xtream servers DO accept). */
    private val CORS_PROXIES = listOf(
        "https://api.allorigins.win/raw?url={url}",
        "https://corsproxy.io/?{url}",
        "https://api.codetabs.com/v1/proxy/?quest={url}",
        "https://thingproxy.freeboard.io/fetch/{url_raw}",
        "https://yacdn.org/proxy/{url_raw}",
    )
    @Volatile private var vegetaServers: List<StalkerServer> = emptyList()
    // 2026-05-03: hardcode position-range supprimé. Le backend reordering les
    // serveurs régulièrement — vérifié ce jour les FR sont en pos 37..45 alors
    // que le hardcode 39..47 incluait à tort 2 US (pos 46-47) et ratait pos 37-38.
    // On se fie uniquement au flag 🇫🇷 dans la string (plus robuste).
    // Si jamais le backend oublie un flag, ouvrir un bug et signaler la pos.

    // ───────── Phase 3: multi-server background scan ─────────
    private val frServerIndices = java.util.concurrent.CopyOnWriteArrayList<Int>()
    @Volatile private var phase3Done = false
    private val phase3Mutex = Mutex()

    /** Last-known-good stream URL per channel key (e.g. "tf1" → working URL).
     *  When getServers is called, we put the last working one first so the user
     *  doesn't have to retry the broken variant they hit yesterday.
     *  Using synchronizedMap instead of ConcurrentHashMap to avoid a D8 desugaring
     *  bug that references j$.util.concurrent.ConcurrentHashMap at runtime. */
    private val lastGoodStreamUrl: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(HashMap())
    /** Map streamUrl → channelKey, so getVideo can record which channel it played. */
    private val streamUrlToChannelKey: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(HashMap())

    private const val PHASE3_MAX_CANDIDATES = 20

    // Cap on the "Autres chaînes" home row to keep TV RecyclerView responsive
    // (ANR risk if hundreds of items inflate + load logos simultaneously).
    private const val OTHER_CHANNELS_HOME_LIMIT = 60

    // Pagination size for getTvShows() — full "Toutes les chaînes" page.
    private const val TV_SHOWS_PAGE_SIZE = 50

    // Cap on search results to avoid TV RecyclerView ANR on logo loads.
    private const val SEARCH_RESULT_LIMIT = 30
    private const val PHASE3_MAX_FR_SERVERS = 8
    private const val PHASE3_PARALLELISM = 2

    private const val MAX_VARIANTS_PER_CHANNEL = 5
    private const val RENEWAL_BATCH_SIZE = 20

    private const val FR_SERVERS_CACHE_FILE = "vegeta_fr_servers.json"
    private const val FR_SERVERS_CACHE_TTL_MS = 24L * 60 * 60 * 1000L

    private const val REGISTRY_CACHE_FILE = "vegeta_registry.json"
    private const val REGISTRY_CACHE_TTL_MS = 6L * 60 * 60 * 1000L

    // ───────── Normalization ─────────

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
            .replace(Regex("^\\s*(fr|france)\\s*[:|\\-]\\s*"), "")
        val midTag = Regex(
            "\\b(hd|sd|fhd|uhd|4k|raw|hevc|h\\.?265|ppv|ott|live|test|backup|fhdr|sdr)\\b" +
                "|(?:\\+\\s?1)|(?:1080p|720p|480p|360p)"
        )
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
        return s.replace("+", "plus").replace("&", "and")
            .replace(Regex("[^a-z0-9]"), "").replace("sports", "sport")
    }

    private fun extractVariantLabel(raw: String): String {
        val parts = mutableListOf<String>()
        Regex(
            "\\b(HD|SD|FHD|UHD|4K|RAW|HEVC|H\\.?265|PPV|OTT|LIVE|FHDR|SDR)\\b" +
                "|(?:\\+\\s?1)|(?:1080p|720p|480p|360p)",
            RegexOption.IGNORE_CASE
        ).findAll(raw).forEach { parts.add(it.value.uppercase()) }
        Regex(
            "\\b(FRANCE|FR|FRENCH|FRANCAIS|BELGIQUE|BE|SUISSE|CH|LUX)\\b",
            RegexOption.IGNORE_CASE
        ).findAll(raw).forEach { m -> parts.add(m.value.uppercase()) }
        return parts.distinct().joinToString(" ")
    }

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

    private fun guessCategory(name: String): String {
        val l = name.lowercase()
        return when {
            l.contains("canal+ sport") || l.contains("bein") || l.contains("rmc sport")
                || l.contains("eurosport") || l.contains("equipe") -> "Sport"
            l.contains("cinéma") || l.contains("ocs") || l.contains("ciné+") || l.contains("canal+ family") -> "Cinéma"
            l.contains("bfm") || l.contains("cnews") || l.contains("lci") || l.contains("franceinfo") -> "Info"
            l.contains("planète+") || l.contains("nat geo") || l.contains("discovery") -> "Documentaire"
            l.contains("disney") || l.contains("cartoon") || l.contains("nickelodeon") || l.contains("gulli") -> "Enfants"
            l.contains("mtv") || l.contains("nrj hits") || l.contains("mcm") -> "Musique"
            else -> "Généraliste"
        }
    }

    private val manualLogoMap: Map<String, String> by lazy {
        val base = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france"
        mapOf(
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
            "canalplus" to "$base/canal-plus-fr.png",
            "canalpluscinema" to "$base/canal-plus-cinema-fr.png",
            "bfmtv" to "$base/bfm-tv-fr.png",
            "lci" to "$base/lci-fr.png",
            "lequipe" to "$base/l-equipe-fr.png",
        )
    }

    private fun logoUrlFor(name: String): String {
        val lookup = normalizeForLogo(name)
        manualLogoMap[lookup]?.let { return it }
        return uiAvatarFallback(name)
    }

    private fun uiAvatarFallback(name: String): String {
        val color = when (guessCategory(name)) {
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

    // ───────── FROZEN curated channels ─────────

    private data class CuratedChannel(
        val key: String,
        val displayName: String,
        val category: String,
    )

    private val curatedChannels: List<CuratedChannel> by lazy {
        listOf(
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
            CuratedChannel("bfmtv", "BFM TV", "Info"),
            CuratedChannel("cnews", "CNews", "Info"),
            CuratedChannel("lci", "LCI", "Info"),
            CuratedChannel("franceinfo", "Franceinfo", "Info"),
            CuratedChannel("bfmbusiness", "BFM Business", "Info"),
            CuratedChannel("france24", "France 24", "Info"),
            CuratedChannel("tv5monde", "TV5 Monde", "Info"),
            CuratedChannel("euronews", "Euronews", "Info"),
            CuratedChannel("i24news", "i24 News", "Info"),
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
            CuratedChannel("mtv", "MTV", "Musique"),
            CuratedChannel("mcm", "MCM", "Musique"),
            CuratedChannel("m6music", "M6 Music", "Musique"),
            CuratedChannel("nrjhits", "NRJ Hits", "Musique"),
            CuratedChannel("traceurban", "Trace Urban", "Musique"),
            CuratedChannel("mezzo", "Mezzo", "Musique"),
            CuratedChannel("planeteplus", "Planète+", "Documentaire"),
            CuratedChannel("ushuaiatv", "Ushuaïa TV", "Documentaire"),
            CuratedChannel("histoiretv", "Histoire TV", "Documentaire"),
            CuratedChannel("nationalgeographic", "National Geographic", "Documentaire"),
            CuratedChannel("natgeowild", "Nat Geo Wild", "Documentaire"),
            CuratedChannel("discoverychannel", "Discovery Channel", "Documentaire"),
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
            CuratedChannel("lequipe", "L'Équipe", "Sport"),

            // ─── Extension list — common FR channels missing from the original
            // 80-channel curation. Keys are the result of norm(displayName).
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
            CuratedChannel("trek", "Trek", "Documentaire"),
            CuratedChannel("abxplore", "ABXplore", "Documentaire"),
            CuratedChannel("rmcdecouverte", "RMC Découverte", "Documentaire"),
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

    // ───────── Server list parsing ─────────

    /** Parse serveurs.txt. We keep the URL-position (1-indexed, ignoring blanks)
     *  because positions 39..47 are confirmed 🇫🇷 by user. We return ALL servers
     *  whose flag set OR position marks them FR-compatible, with FR-prio first. */
    private fun parseVegetaServerList(): List<StalkerServer> {
        val frFirst = mutableListOf<StalkerServer>()
        val globalOnly = mutableListOf<StalkerServer>()
        try {
            val req = Request.Builder().url(VEGETA_SERVERS_URL)
                .header("User-Agent", USER_AGENT).build()
            val body = probeClient.newCall(req).execute().body?.string() ?: return emptyList()
            val rawLines = body.split("\n").map { it.trim() }

            var pos = 0
            for (line in rawLines) {
                if (line.isBlank()) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.isEmpty() || !parts[0].startsWith("http")) continue

                pos++
                val url = parts[0]
                val flags = parts.drop(1).joinToString("")

                val flagFr = flags.contains("🇫🇷") || flags.contains(" FR") || flags.endsWith("FR")
                val hasGlobal = flags.contains("🌐")
                val isFr = flagFr

                if (!isFr && !hasGlobal) continue

                val baseUrl = url.substringBefore("/get.php").trimEnd('/')
                if (!baseUrl.startsWith("http")) continue

                val server = StalkerServer(pos, baseUrl, isFr, xtreamUrl = url)
                if (isFr) frFirst.add(server) else globalOnly.add(server)
                Log.d(TAG, "Server[pos=$pos isFr=$isFr global=$hasGlobal] → $baseUrl")
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseVegetaServerList failed: ${e.message}")
        }
        val all = frFirst + globalOnly
        Log.d(TAG, "parseVegetaServerList: ${frFirst.size} FR + ${globalOnly.size} GLOBAL = ${all.size} servers")
        return all
    }

    // ───────── Stalker MAC protocol ─────────

    /** Fetch a URL and return body bytes, or null if empty/error.
     *  Tries direct first, then falls back to each CORS proxy in order. */
    private suspend fun fetchWithProxyFallback(url: String, timeoutMs: Long = 12_000L): String? {
        suspend fun attempt(targetUrl: String, label: String): String? = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/130.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "*/*")
                    .build()
                val resp = client.newCall(req).execute()
                // Cap body size at 10 MB to avoid OutOfMemoryError on huge m3us
                // (some Xtream servers return 100k+ channels = 100+ MB).
                // 10 MB is enough for all FR channels — typical FR-only m3u is ~200KB.
                val MAX_BODY_BYTES = 10L * 1024 * 1024
                val contentLength = resp.body?.contentLength() ?: -1L
                if (contentLength > MAX_BODY_BYTES) {
                    Log.w(TAG, "  fetchWithProxy too large via $label (${contentLength / 1024 / 1024}MB > 25MB) — skipping")
                    resp.close()
                    return@withContext null
                }
                val body = try {
                    val source = resp.body?.source() ?: run { resp.close(); return@withContext null }
                    if (!source.request(MAX_BODY_BYTES)) {
                        // body is smaller than cap — read all
                        source.buffer.readUtf8()
                    } else {
                        // body is larger than cap — read only first MAX_BODY_BYTES
                        Log.w(TAG, "  fetchWithProxy truncating via $label at ${MAX_BODY_BYTES / 1024 / 1024}MB cap")
                        source.buffer.readUtf8(MAX_BODY_BYTES)
                    }
                } finally { resp.close() }
                if (body.isNotBlank() && resp.isSuccessful) {
                    Log.d(TAG, "  fetchWithProxy OK via $label (${body.length}B)")
                    body
                } else {
                    Log.d(TAG, "  fetchWithProxy empty via $label (status=${resp.code})")
                    null
                }
            } catch (e: Exception) {
                Log.d(TAG, "  fetchWithProxy fail via $label: ${e.message}")
                null
            }
        }

        // 1) Try direct
        attempt(url, "direct")?.let { return it }
        // 2) Try each proxy
        val encoded = java.net.URLEncoder.encode(url, "UTF-8")
        for (tpl in CORS_PROXIES) {
            val proxyUrl = if (tpl.contains("{url_raw}")) tpl.replace("{url_raw}", url)
                           else tpl.replace("{url}", encoded)
            val proxyName = proxyUrl.substringAfter("//").substringBefore("/")
            attempt(proxyUrl, proxyName)?.let { return it }
        }
        return null
    }

    /** Fetch the Xtream m3u listing for a server (direct or via CORS proxy),
     *  parse it, and ingest FR-named channels into the registry.
     *  Returns the number of new streams added. */
    private suspend fun ingestServerChannelsViaXtreamM3u(
        serverIdx: Int,
        xtreamUrl: String,
        isFr: Boolean,
    ): Int {
        if (xtreamUrl.isBlank()) return 0
        Log.d(TAG, "Server[$serverIdx]: fetching Xtream m3u (isFr=$isFr) — $xtreamUrl")
        val m3u = fetchWithProxyFallback(xtreamUrl) ?: run {
            Log.w(TAG, "Server[$serverIdx]: m3u fetch failed (all proxies)")
            return 0
        }
        if (!m3u.contains("#EXTM3U") && !m3u.contains("#EXTINF")) {
            Log.w(TAG, "Server[$serverIdx]: response doesn't look like m3u (${m3u.take(80)})")
            return 0
        }

        // FR channel name regex (same logic as Stalker fallback)
        val frNameRegex = Regex(
            "\\b(TF1|TMC|TFX|LCI|France\\s*[1-9]|FR[1-9]|M6|W9|6ter|Gulli|" +
                "BFM|CNews|LCP|FranceInfo|France\\s*Info|Arte|RTL9?|NRJ\\s*12|NT1|" +
                "Canal\\+|OCS|Cin[ée]\\+|Paramount\\+|TV5|TV\\s*5|" +
                "L'?[Éé]quipe|beIN|RMC|Eurosport|" +
                "13[èe]me\\s*Rue|Syfy|Discovery|National\\s*Geo|Histoire|" +
                "MCM|Trace|Nostalgie|" +
                "Disney|Boomerang|Cartoon|Tiji|Piwi|Nickelodeon|" +
                "T[ée]l[ée]toon|TFOU|Mangas|" +
                "AB1|AB3|RTBF|TV5MONDE|France\\s*24|FR:|FRENCH|Français)\\b",
            RegexOption.IGNORE_CASE
        )

        var totalAdded = 0
        var totalChannelsSeen = 0
        // Parse m3u robustly: an "entry" is everything from #EXTINF:... up to the
        // next URL line. Some proxies wrap long EXTINF lines onto multiple physical
        // lines, so we accumulate text until we hit a non-comment line that starts
        // with http(s):// and treat that as the stream URL.
        val rawLines = m3u.split("\n")
        var pending = StringBuilder()
        for (raw in rawLines) {
            val line = raw.trim()
            if (line.isBlank()) continue
            if (line.startsWith("#EXTINF:")) {
                pending = StringBuilder(line)
                continue
            }
            if (line.startsWith("#")) {
                // EXTGRP, EXTM3U etc. — keep accumulating with EXTINF if active
                if (pending.isNotEmpty()) pending.append(' ').append(line)
                continue
            }
            if (line.startsWith("http")) {
                if (pending.isEmpty()) continue
                val streamUrl = line
                val extInfFull = pending.toString()
                pending = StringBuilder()
                // Channel name = text after the LAST comma in the EXTINF (m3u spec)
                val rawName = extInfFull.substringAfterLast(",").trim()
                if (rawName.isBlank()) continue
                totalChannelsSeen++

                // Decide whether to keep this channel:
                //  - Server marked FR  → accept ALL (entire m3u is FR-relevant)
                //  - Server is GLOBAL  → only keep names matching FR brand regex
                val keep = if (isFr) true else frNameRegex.containsMatchIn(rawName)
                if (!keep) continue

                val cleaned = rawName
                    .replace(Regex("^(FR|ES|PT|EN|DE|IT|AR|TR|NL|PL|RO|US|UK|BE|CH)[|:\\s]+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("^[\\-•●○▪►•‣⮞›»\\u2022]+\\s*"), "")
                    .replace(Regex("\\s+"), " ").trim()
                if (cleaned.isBlank()) continue

                synchronized(registryLock) {
                    val key = norm(cleaned)
                    if (key.isBlank()) return@synchronized
                    val info = channelRegistry.getOrPut(key) {
                        ChannelInfo(
                            displayName = baseDisplayName(cleaned),
                            category = guessCategory(baseDisplayName(cleaned)),
                            logo = "",
                        )
                    }
                    if (info.streams.none { it.url == streamUrl }) {
                        val label = extractVariantLabel(cleaned).ifBlank { "Server $serverIdx" }
                        info.streams.add(VegetaStreamRef(serverIdx, label, streamUrl))
                        totalAdded++
                    }
                }
            } else {
                // Continuation of the EXTINF line wrapped by the proxy
                if (pending.isNotEmpty()) pending.append(' ').append(line)
            }
        }
        Log.d(TAG, "Server[$serverIdx]: ingested $totalAdded / $totalChannelsSeen channels from m3u (lines parsed: ${rawLines.size})")
        return totalAdded
    }

    /** Generate a random MAC address in the format 00:1A:79:XX:XX:XX */
    private fun generateRandomMac(): String {
        val random = Random()
        val bytes = ByteArray(3) { random.nextInt(256).toByte() }
        return "00:1A:79:${String.format("%02X", bytes[0])}" +
                ":${String.format("%02X", bytes[1])}" +
                ":${String.format("%02X", bytes[2])}"
    }

    /** Ingest channels for a given Stalker MAC server via portal protocol.
     *  1. Handshake to get token
     *  2. Get FR genres
     *  3. For each FR genre, fetch all channels
     *  4. For each channel, resolve cmd to actual stream URL */
    private suspend fun ingestServerChannelsViaStalker(serverIdx: Int, baseUrl: String): Int {
        val mac = generateRandomMac()
        val encodedMac = java.net.URLEncoder.encode(mac, "UTF-8")
        val portalBase = "${baseUrl.trimEnd('/')}/portal.php"
        val cookie = "mac=$encodedMac; stb_lang=en; timezone=Europe%2FLondon"
        val stbUA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 250 Safari/533.3"

        return try {
            // Step 1: handshake → token
            val hsReq = Request.Builder()
                .url("$portalBase?type=stb&action=handshake&token=&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie).build()
            val hsBody = withContext(Dispatchers.IO) {
                probeClient.newCall(hsReq).execute().body?.string() ?: ""
            }
            val token = try { JSONObject(hsBody).getJSONObject("js").getString("token") } catch (_: Exception) {
                Log.w(TAG, "Server[$serverIdx]: no token in handshake")
                return 0
            }

            // Step 2: get_profile (init session)
            val profReq = Request.Builder()
                .url("$portalBase?type=stb&action=get_profile&auth_token=$token&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie)
                .header("Authorization", "Bearer $token").build()
            withContext(Dispatchers.IO) {
                probeClient.newCall(profReq).execute().body?.close()
            }

            // Step 3: get_genres → find FR genres
            val genreReq = Request.Builder()
                .url("$portalBase?type=itv&action=get_genres&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie)
                .header("Authorization", "Bearer $token").build()
            val genreBody = withContext(Dispatchers.IO) {
                probeClient.newCall(genreReq).execute().body?.string() ?: ""
            }

            val frGenreIds = mutableListOf<String>()
            val allGenreIds = mutableListOf<String>()
            val allGenreTitles = mutableListOf<String>()
            try {
                val arr = JSONObject(genreBody).optJSONArray("js") ?: JSONArray()
                for (g in 0 until arr.length()) {
                    val genre = arr.optJSONObject(g) ?: continue
                    val title = genre.optString("title", "")
                    val id = genre.optString("id", "")
                    if (id.isBlank() || id == "*" || id == "0") continue
                    val titleLower = title.lowercase()
                    allGenreIds.add(id)
                    allGenreTitles.add(title)
                    // Stricter FR matchers: title contains FR markers
                    if (titleLower.contains("fr") || titleLower.contains("france")
                        || titleLower.contains("french") || titleLower.contains("français")
                        || titleLower.contains("francais")
                        || titleLower.contains("belgique") || titleLower.contains("suisse")) {
                        frGenreIds.add(id)
                    }
                }
            } catch (_: Exception) {}

            Log.d(TAG, "Server[$serverIdx]: ${allGenreIds.size} genres total, ${frGenreIds.size} FR-marked. Sample: ${allGenreTitles.take(8).joinToString(" | ")}")

            // Fallback: if no FR-marked genre, scan ALL genres and filter channels
            // by NAME at ingestion time (TF1, M6, France 2, BFM, etc.). Many Stalker
            // servers don't tag genres by country — channels are mixed in generic
            // categories like "Sport", "Movies", "News".
            val genresToScan: List<String>
            val filterByName: Boolean
            if (frGenreIds.isNotEmpty()) {
                genresToScan = frGenreIds
                filterByName = false
                Log.d(TAG, "Server[$serverIdx]: using ${frGenreIds.size} FR-tagged genres")
            } else {
                // Cap the all-genre scan at 12 to avoid 5000+ requests
                genresToScan = allGenreIds.take(12)
                filterByName = true
                Log.d(TAG, "Server[$serverIdx]: no FR genre, fallback scan ${genresToScan.size} all-genres + filter channels by FR name")
            }

            if (genresToScan.isEmpty()) {
                Log.d(TAG, "Server[$serverIdx]: no genres at all, skipping")
                return 0
            }

            // Step 4: For each FR genre, fetch all channels and resolve their stream URLs
            var totalAdded = 0
            val seenNames = mutableSetOf<String>()
            val nameLock = Any()

            suspend fun fetchPageChannels(genreId: String, page: Int): Pair<List<Pair<String, String>>, Int> = withContext(Dispatchers.IO) {
                val pageChannels = mutableListOf<Pair<String, String>>()
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
                            .replace(Regex("^[\\-•●○▪►•‣⮞›»\\u2022]+\\s*"), "")
                            .replace(Regex("\\s+"), " ").trim()
                        if (cleaned.isNotBlank()) pageChannels.add(Pair(cleaned, cmd))
                    }
                    return@withContext Pair(pageChannels, totalItems)
                } catch (e: Exception) {
                    Log.w(TAG, "Server[$serverIdx] genre=$genreId page=$page failed: ${e.message}")
                    return@withContext Pair(pageChannels, 0)
                }
            }

            // FR channel name detector — used in fallback mode when no genre is
            // tagged FR. Captures the major French TV brands.
            val frNameRegex = Regex(
                "\\b(TF1|TMC|TFX|LCI|France\\s*[1-9]|FR[1-9]|M6|W9|6ter|Gulli|" +
                    "BFM|CNews|LCP|FranceInfo|France\\s*Info|Arte|RTL9?|NRJ\\s*12|NT1|" +
                    "Canal\\+|OCS|Cin[ée]\\+|Paramount\\+|TV5|TV\\s*5|" +
                    "L'?[Éé]quipe|beIN|RMC|Eurosport|" +
                    "13[èe]me\\s*Rue|Syfy|Discovery|National\\s*Geo|Histoire|" +
                    "MCM|Trace|Nostalgie|" +
                    "Disney|Boomerang|Cartoon|Tiji|Piwi|Nickelodeon|" +
                    "T[ée]l[ée]toon|TFOU|Mangas|" +
                    "AB1|AB3|RTBF|TV5MONDE|France\\s*24)\\b",
                RegexOption.IGNORE_CASE
            )

            fun ingestChannel(rawName: String, cmd: String) {
                if (filterByName && !frNameRegex.containsMatchIn(rawName)) return
                synchronized(nameLock) {
                    if (rawName in seenNames) return
                    seenNames.add(rawName)
                    val streamUrl = resolveStreamCmd(baseUrl, mac, cmd) ?: return
                    val key = norm(rawName)
                    if (key.isBlank()) return
                    val info = channelRegistry.getOrPut(key) {
                        ChannelInfo(
                            displayName = baseDisplayName(rawName),
                            category = guessCategory(baseDisplayName(rawName)),
                            logo = "",
                        )
                    }
                    if (info.streams.none { it.url == streamUrl }) {
                        val label = extractVariantLabel(rawName).ifBlank { "Server $serverIdx" }
                        info.streams.add(VegetaStreamRef(serverIdx, label, streamUrl))
                        totalAdded++
                    }
                }
            }

            for (genreId in genresToScan) {
                val (page1Channels, totalItems) = fetchPageChannels(genreId, 1)
                val maxPageItems = 14

                for ((rawName, cmd) in page1Channels) ingestChannel(rawName, cmd)

                // Fetch remaining pages in parallel if needed
                if (totalItems > maxPageItems) {
                    val totalPages = minOf(30, ((totalItems + maxPageItems - 1) / maxPageItems))
                    if (totalPages >= 2) {
                        coroutineScope {
                            val sem = kotlinx.coroutines.sync.Semaphore(6)
                            val jobs = (2..totalPages).map { page ->
                                async {
                                    sem.acquire()
                                    try {
                                        val (pageChannels, _) = fetchPageChannels(genreId, page)
                                        for ((rawName, cmd) in pageChannels) ingestChannel(rawName, cmd)
                                    } finally { sem.release() }
                                }
                            }
                            jobs.awaitAll()
                        }
                    }
                }
            }

            Log.d(TAG, "Server[$serverIdx]: +$totalAdded channels via Stalker MAC")
            totalAdded
        } catch (e: Exception) {
            Log.e(TAG, "ingestServerChannelsViaStalker[$serverIdx]: ${e.message}")
            0
        }
    }

    /** Resolve a "cmd" from get_ordered_list to an actual playable stream URL. */
    private fun resolveStreamCmd(baseUrl: String, mac: String, cmd: String): String? {
        val rawCmd = cmd.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
        val isLocalhost = rawCmd.contains("localhost") || rawCmd.contains("127.0.0.1")
        if (rawCmd.startsWith("http") && !isLocalhost) return rawCmd

        return try {
            val encodedMac = java.net.URLEncoder.encode(mac, "UTF-8")
            val portalBase = "${baseUrl.trimEnd('/')}/portal.php"
            val cookie = "mac=$encodedMac; stb_lang=en; timezone=Europe%2FLondon"
            val stbUA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 250 Safari/533.3"

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
            resolved.removePrefix("ffrt ").removePrefix("ffmpeg ").trim().ifBlank { null }
        } catch (e: Exception) {
            Log.d(TAG, "create_link for cmd='${rawCmd.take(60)}' failed: ${e.message}")
            null
        }
    }

    // ───────── Disk cache ─────────

    private fun frServersCacheFile() = java.io.File(StreamFlixApp.instance.filesDir, FR_SERVERS_CACHE_FILE)

    private fun loadFrServersCache(): List<Int>? {
        return try {
            val f = frServersCacheFile()
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            val ts = obj.optLong("ts", 0)
            if (System.currentTimeMillis() - ts > FR_SERVERS_CACHE_TTL_MS) return null
            val arr = obj.optJSONArray("indices") ?: return null
            (0 until arr.length()).mapNotNull { arr.optInt(it).takeIf { i -> i >= 0 } }
        } catch (_: Exception) { null }
    }

    private fun saveFrServersCache(indices: List<Int>) {
        try {
            val obj = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("indices", JSONArray(indices))
            }
            frServersCacheFile().writeText(obj.toString())
            Log.d(TAG, "FR servers cache saved: ${indices.size} indices")
        } catch (e: Exception) {
            Log.w(TAG, "FR servers cache save failed: ${e.message}")
        }
    }

    private fun registryCacheFile() = java.io.File(StreamFlixApp.instance.filesDir, REGISTRY_CACHE_FILE)

    private fun saveRegistryCache() {
        try {
            val snapshot = synchronized(registryLock) { LinkedHashMap(channelRegistry) }
            if (snapshot.isEmpty()) return

            // SAFETY: cap saved channels to avoid runaway disk cache. We only need
            // the curated FR channel list (TF1, M6, etc.) — the millions of global
            // entries from giant m3us would balloon the file to 100+MB and crash
            // on next launch when re-loading. Cap at 1000 channels, 5 streams each.
            val MAX_CHANNELS_TO_SAVE = 1000
            val MAX_STREAMS_PER_CHANNEL = 5
            val keysToSave = if (snapshot.size <= MAX_CHANNELS_TO_SAVE) {
                snapshot.keys.toList()
            } else {
                // Prefer the curated channel keys first, then fill remaining with
                // channels that have multiple variants (more useful).
                val curatedKeys = curatedChannels.map { it.key }.toSet()
                val curated = snapshot.keys.filter { it in curatedKeys }
                val others = snapshot.entries
                    .filter { it.key !in curatedKeys }
                    .sortedByDescending { it.value.streams.size }
                    .map { it.key }
                (curated + others).take(MAX_CHANNELS_TO_SAVE)
            }

            val channels = JSONObject()
            for (key in keysToSave) {
                val info = snapshot[key] ?: continue
                val streams = JSONArray()
                for (s in info.streams.take(MAX_STREAMS_PER_CHANNEL)) {
                    val obj = JSONObject().apply {
                        put("serverIdx", s.serverIdx)
                        put("label", s.label)
                        put("url", s.url)
                    }
                    streams.put(obj)
                }
                val ch = JSONObject().apply {
                    put("displayName", info.displayName)
                    put("category", info.category)
                    put("logo", info.logo)
                    put("streams", streams)
                }
                channels.put(key, ch)
            }
            val data = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("channels", channels)
            }
            registryCacheFile().writeText(data.toString())
            Log.d(TAG, "Registry cache saved: ${keysToSave.size}/${snapshot.size} channels")
        } catch (e: Exception) {
            Log.w(TAG, "Registry cache save failed: ${e.message}")
        }
    }

    private fun loadRegistryCache(): Boolean {
        return try {
            val f = registryCacheFile()
            if (!f.exists()) return false
            // Safety: if the cache has grown out of control (e.g. accumulated 100k+
            // channels from huge global m3us in previous sessions), delete it. We
            // can't safely load >20MB into a JSON string without OOM on low-RAM
            // devices, and we don't need that much anyway.
            val MAX_CACHE_BYTES = 20L * 1024 * 1024
            if (f.length() > MAX_CACHE_BYTES) {
                Log.w(TAG, "Registry cache too large (${f.length() / 1024 / 1024}MB > 20MB) — deleting and starting fresh")
                f.delete()
                return false
            }
            val obj = JSONObject(f.readText())
            val ts = obj.optLong("ts", 0)
            if (System.currentTimeMillis() - ts > REGISTRY_CACHE_TTL_MS) return false

            synchronized(registryLock) {
                channelRegistry.clear()
                val channels = obj.optJSONObject("channels") ?: return false
                for (key in channels.keys()) {
                    val ch = channels.getJSONObject(key)
                    val info = ChannelInfo(
                        displayName = ch.getString("displayName"),
                        category = ch.getString("category"),
                        logo = ch.getString("logo"),
                    )
                    val streams = ch.getJSONArray("streams")
                    for (i in 0 until streams.length()) {
                        val s = streams.getJSONObject(i)
                        info.streams.add(VegetaStreamRef(
                            serverIdx = s.getInt("serverIdx"),
                            label = s.getString("label"),
                            url = s.getString("url"),
                        ))
                    }
                    channelRegistry[key] = info
                }
            }
            Log.d(TAG, "Registry loaded from disk: ${channelRegistry.size} channels")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Registry cache load failed: ${e.message}")
            false
        }
    }

    /** Phase 3: scan remaining FR servers first, then GLOBAL, in pairs. Skips
     *  servers already ingested in the race phase. */
    private suspend fun scanAdditionalServers() {
        try {
            val alreadyDone = frServerIndices.toSet()
            val frRemaining = vegetaServers.filter { it.isFr && it.idx !in alreadyDone }
            val globals = vegetaServers.filter { !it.isFr && it.idx !in alreadyDone }
            val candidates = (frRemaining + globals).take(PHASE3_MAX_CANDIDATES)
            if (candidates.isEmpty()) {
                phase3Done = true
                return
            }
            Log.d(TAG, "Phase 3: ${frRemaining.size} more FR + ${globals.size} GLOBAL → scanning ${candidates.size} (parallelism=$PHASE3_PARALLELISM)")

            // Strategy: ALWAYS scan all FR-flagged servers (the user's primary source).
            // Only skip GLOBAL servers when we have enough — they're slow, huge, and
            // contain mostly non-FR content.
            val ENOUGH_TO_SKIP_GLOBALS = 200
            // Track which channels were added by THIS session (vs. loaded from disk cache),
            // so the disk cache doesn't trigger the early-skip immediately.
            val sessionStartSize = synchronized(registryLock) { channelRegistry.size }

            coroutineScope {
                val sem = kotlinx.coroutines.sync.Semaphore(PHASE3_PARALLELISM)
                candidates.map { server ->
                    async {
                        if (frServerIndices.size >= PHASE3_MAX_FR_SERVERS) return@async
                        // For NON-FR (global) servers only: skip if FR servers already
                        // produced enough new content this session.
                        if (!server.isFr) {
                            val newThisSession = synchronized(registryLock) { channelRegistry.size } - sessionStartSize
                            if (newThisSession >= ENOUGH_TO_SKIP_GLOBALS) {
                                Log.d(TAG, "Phase 3: $newThisSession new channels — skipping GLOBAL server[${server.idx}]")
                                return@async
                            }
                        }
                        sem.acquire()
                        try {
                            // Try Xtream m3u via proxies first, fallback to Stalker
                            var added = ingestServerChannelsViaXtreamM3u(server.idx, server.xtreamUrl, server.isFr)
                            if (added == 0) added = ingestServerChannelsViaStalker(server.idx, server.baseUrl)
                            if (added > 0) {
                                frServerIndices.add(server.idx)
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Server[${server.idx}] background scan: ${e.message}")
                        } finally { sem.release() }
                    }
                }.awaitAll()
            }
            phase3Done = true
            Log.d(TAG, "Phase 3 done: ${frServerIndices.size} servers contributing, ${channelRegistry.size} channels in registry")
        } catch (e: Exception) {
            Log.w(TAG, "scanAdditionalServers failed: ${e.message}")
            phase3Done = true
        }
    }

    /** On-demand backfill: when getServers is called on a channel that has 0 streams,
     *  trigger an immediate scan of the next 2 untouched servers in the background.
     *  The lateRegistryWatcher will then emit any newly-found streams to the player. */
    @Volatile private var ondemandJob: Job? = null
    private fun triggerOnDemandBackfill(reason: String) {
        if (ondemandJob?.isActive == true) return
        ondemandJob = scope.launch {
            try {
                val alreadyDone = frServerIndices.toSet()
                val nextPair = vegetaServers
                    .filter { it.idx !in alreadyDone }
                    .sortedBy { if (it.isFr) 0 else 1 }
                    .take(2)
                if (nextPair.isEmpty()) return@launch
                Log.d(TAG, "On-demand backfill ($reason): scanning ${nextPair.map { it.idx }}")
                coroutineScope {
                    nextPair.map { server ->
                        async {
                            try {
                                var added = ingestServerChannelsViaXtreamM3u(server.idx, server.xtreamUrl, server.isFr)
                                if (added == 0) added = ingestServerChannelsViaStalker(server.idx, server.baseUrl)
                                if (added > 0) frServerIndices.add(server.idx)
                            } catch (e: Exception) {
                                Log.d(TAG, "On-demand server[${server.idx}]: ${e.message}")
                            }
                        }
                    }.awaitAll()
                }
                saveRegistryCache()
            } catch (e: Exception) {
                Log.w(TAG, "On-demand backfill failed: ${e.message}")
            }
        }
    }

    private suspend fun ensureRegistry() {
        if (registryLoaded && System.currentTimeMillis() - lastLoadTime < CACHE_DURATION) return
        registryMutex.withLock {
            if (registryLoaded && System.currentTimeMillis() - lastLoadTime < CACHE_DURATION) return@withLock

            val cacheExpired = registryLoaded &&
                System.currentTimeMillis() - lastLoadTime >= CACHE_DURATION
            if (cacheExpired) synchronized(registryLock) { channelRegistry.clear() }
            registryLoaded = false

            val t0 = System.currentTimeMillis()

            // Try disk cache first
            if (loadRegistryCache()) {
                registryLoaded = true
                lastLoadTime = System.currentTimeMillis()
                Log.d(TAG, "Registry restored from disk in ${System.currentTimeMillis() - t0}ms")
                scope.launch {
                    try {
                        if (vegetaServers.isEmpty()) {
                            vegetaServers = parseVegetaServerList()
                        }
                        scanAdditionalServers()
                        saveRegistryCache()
                    } catch (e: Exception) {
                        Log.w(TAG, "Background refresh: ${e.message}")
                    }
                }
                return@withLock
            }

            withContext(Dispatchers.IO + NonCancellable) {
                try {
                    vegetaServers = parseVegetaServerList()
                    if (vegetaServers.isEmpty()) {
                        Log.e(TAG, "No servers found — cannot proceed")
                        return@withContext
                    }

                    // Phase 2 (per user request, like OlaTV): preload only 2 servers in
                    // parallel. Phase 3 backfills the rest in background and on-demand
                    // when getServers() is called on a channel that has too few streams.
                    val frServers = vegetaServers.filter { it.isFr }
                    val nonFrServers = vegetaServers.filter { !it.isFr }
                    // Take 2 FR servers for the initial preload race
                    val raceServers = frServers.take(2)
                    Log.d(TAG, "Initial race: 2 FR servers (out of ${frServers.size} FR + ${nonFrServers.size} GLOBAL)")
                    val unblockSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val fastResults = java.util.concurrent.atomic.AtomicInteger(0)
                    val totalAdded = java.util.concurrent.atomic.AtomicInteger(0)

                    val raceJob = scope.launch(Dispatchers.IO + NonCancellable) {
                        coroutineScope {
                            raceServers.map { server ->
                                async {
                                    val added = try {
                                        // Try Xtream m3u via proxies first (the protocol the
                                        // native Vegeta web player actually uses), fallback
                                        // to Stalker MAC if m3u path returns 0.
                                        var n = ingestServerChannelsViaXtreamM3u(server.idx, server.xtreamUrl, server.isFr)
                                        if (n == 0) n = ingestServerChannelsViaStalker(server.idx, server.baseUrl)
                                        n
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Server[${server.idx}] race failed: ${e.message}")
                                        0
                                    }
                                    if (added > 0) {
                                        frServerIndices.add(server.idx)
                                        totalAdded.addAndGet(added)
                                        if (fastResults.incrementAndGet() >= 1 && !unblockSignal.isCompleted) {
                                            unblockSignal.complete(Unit)
                                            Log.d(TAG, "Race: unblocked at 1 server in ${System.currentTimeMillis() - t0}ms")
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                        if (!unblockSignal.isCompleted) unblockSignal.complete(Unit)
                        Log.d(TAG, "Race finished: ${frServerIndices.size}/${raceServers.size} OK, ${channelRegistry.size} channels, ${totalAdded.get()} added in ${System.currentTimeMillis() - t0}ms")
                        try {
                            saveRegistryCache()
                            scanAdditionalServers()
                            saveRegistryCache()
                        } catch (e: Exception) {
                            Log.w(TAG, "Phase 3 background: ${e.message}")
                        }
                    }

                    try {
                        kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                            unblockSignal.await()
                        }
                    } catch (_: Exception) {}

                    if (channelRegistry.isNotEmpty()) {
                        registryLoaded = true
                        lastLoadTime = System.currentTimeMillis()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Load failed: ${e.message}")
                    if (channelRegistry.isNotEmpty()) {
                        registryLoaded = true
                        lastLoadTime = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    // ───────── App scope ─────────

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _additionalServers = MutableSharedFlow<Video.Server>(replay = 50, extraBufferCapacity = 100)
    override val additionalServersFlow: SharedFlow<Video.Server> = _additionalServers

    private var currentEmitJob: Job? = null

    fun stopEmission() {
        currentEmitJob?.cancel()
        currentEmitJob = null
    }

    // ───────── Provider API ─────────

    override suspend fun getHome(): List<Category> = try {
        scope.launch { try { ensureRegistry() } catch (_: Exception) { } }

        val categoryOrder = listOf("Généraliste", "Cinéma", "Info", "Sport", "Musique", "Documentaire", "Enfants")
        val sections = mutableListOf<Category>()
        for (catName in categoryOrder) {
            val items = curatedChannels
                .filter { it.category == catName }
                .map { c ->
                    TvShow(
                        id = "vegeta::${c.key}",
                        title = c.displayName,
                        poster = logoUrlFor(c.displayName),
                        banner = logoUrlFor(c.displayName),
                        providerName = name,
                    )
                }
            if (items.isNotEmpty()) sections.add(Category(name = catName, list = items))
        }

        // ─── "Autres chaînes" — FR channels in the registry not in the curated
        // list. Capped at OTHER_CHANNELS_HOME_LIMIT to keep the home responsive
        // on TV (full lists ANR when hundreds of items inflate + load logos at once).
        val curatedKeys = curatedChannels.map { it.key }.toSet()
        // Snapshot under the lock, then filter/sort outside so we don't hold it
        // during String.lowercase() and TvShow allocation.
        val registrySnapshot = synchronized(registryLock) {
            channelRegistry.entries
                .filter { (key, _) -> key !in curatedKeys }
                .map { (key, info) -> Triple(key, info.displayName, info.logo) }
        }
        val extraItems = registrySnapshot
            .sortedBy { it.second.lowercase() }
            .take(OTHER_CHANNELS_HOME_LIMIT)
            .map { (key, displayName, logo) ->
                TvShow(
                    id = "vegeta::$key",
                    title = displayName,
                    poster = logo.ifEmpty { logoUrlFor(displayName) },
                    banner = logo.ifEmpty { logoUrlFor(displayName) },
                    providerName = name,
                )
            }
        if (extraItems.isNotEmpty()) {
            sections.add(Category(name = "Autres chaînes", list = extraItems))
        }

        // ─── "Favoris" — user-favorited channels (long-press a channel to add).
        // Always last so it sits just before the bottom Paramètres tab.
        val favoriteIds = com.streamflixreborn.streamflix.utils.IptvFavoritesStore.getFavorites(name)
        if (favoriteIds.isNotEmpty()) {
            val curatedByKey = curatedChannels.associateBy { "vegeta::${it.key}" }
            val registryByKey = synchronized(registryLock) {
                channelRegistry.entries.associate { (k, info) -> "vegeta::$k" to Triple(k, info.displayName, info.logo) }
            }
            val favItems = mutableListOf<TvShow>()
            for (favId in favoriteIds) {
                val curated = curatedByKey[favId]
                if (curated != null) {
                    favItems += TvShow(
                        id = favId,
                        title = curated.displayName,
                        poster = logoUrlFor(curated.displayName),
                        banner = logoUrlFor(curated.displayName),
                        providerName = name,
                    )
                    continue
                }
                val reg = registryByKey[favId]
                if (reg != null) {
                    val (_, displayName, logo) = reg
                    favItems += TvShow(
                        id = favId,
                        title = displayName,
                        poster = logo.ifEmpty { logoUrlFor(displayName) },
                        banner = logo.ifEmpty { logoUrlFor(displayName) },
                        providerName = name,
                    )
                }
            }
            if (favItems.isNotEmpty()) {
                sections.add(Category(name = "Favoris", list = favItems))
            }
        }

        sections
    } catch (e: Exception) {
        Log.e(TAG, "getHome error", e)
        emptyList()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        return try {
            // Curated first, then registry-only matches. Capped at SEARCH_RESULT_LIMIT
            // so a generic query doesn't ANR the TV RecyclerView on logo loads.
            val curatedKeys = curatedChannels.map { it.key }.toSet()
            val curatedHits = curatedChannels
                .filter { it.displayName.contains(query, ignoreCase = true) }
                .map { c ->
                    TvShow(
                        id = "vegeta::${c.key}",
                        title = c.displayName,
                        poster = logoUrlFor(c.displayName),
                        providerName = name,
                    )
                }
            val registrySnapshot = synchronized(registryLock) {
                channelRegistry.entries
                    .filter { (key, info) -> key !in curatedKeys && info.displayName.contains(query, ignoreCase = true) }
                    .map { (key, info) -> Triple(key, info.displayName, info.logo) }
            }
            val registryHits = registrySnapshot
                .sortedBy { it.second.lowercase() }
                .map { (key, displayName, logo) ->
                    TvShow(
                        id = "vegeta::$key",
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
            // Paginated catalog: curated first, then alphabetical upstream-only.
            // Loading 200+ channels at once on TV ANRs the RecyclerView.
            val curatedKeys = curatedChannels.map { it.key }.toSet()
            val curatedItems = curatedChannels.map { c ->
                TvShow(
                    id = "vegeta::${c.key}",
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
                        id = "vegeta::$key",
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
        ensureRegistry()
        val key = id.removePrefix("vegeta::")
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
            val logo = info.logo.ifBlank { logoUrlFor(info.displayName) }
            TvShow(
                id = id,
                title = info.displayName,
                poster = logo,
                providerName = name,
                seasons = listOf(
                    Season(id = id, number = 1, title = "En Direct",
                        episodes = listOf(Episode(id = id, number = 1,
                            title = "Regarder en Direct", poster = logo)))
                ),
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "getTvShow($id) error", e); throw e
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> =
        listOf(Episode(id = seasonId, number = 1, title = "Regarder en Direct"))

    override suspend fun getGenre(id: String, page: Int): Genre = throw Exception("Not supported")

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            ensureRegistry()
            val key = id.removePrefix("vegeta_ep::").removePrefix("vegeta::")
            val info = synchronized(registryLock) { channelRegistry[key] }
            if (info == null) {
                Log.w(TAG, "getServers: channel '$key' not found (registry size=${channelRegistry.size}) — triggering backfill")
                triggerOnDemandBackfill("missing $key")
                spawnLateRegistryWatcher(key)
                return emptyList()
            }

            // Promote the last-known-good stream for this channel (if any) to first
            // position so the user doesn't have to wait through a broken variant.
            val lastGood = lastGoodStreamUrl[key]
            val sortedStreams = if (lastGood != null) {
                val pinned = info.streams.firstOrNull { it.url == lastGood }
                if (pinned != null) listOf(pinned) + info.streams.filter { it.url != lastGood }
                else info.streams
            } else info.streams

            // Build display labels with auto-disambiguation: when multiple streams
            // share the same base label (e.g. all "Server 39"), append #1, #2, etc.
            // so the user can actually see and pick distinct variants. The first
            // entry (last-known-good) gets a ★ marker.
            val labelCounter = mutableMapOf<String, Int>()
            val displayedStreams = sortedStreams.take(MAX_VARIANTS_PER_CHANNEL).mapIndexed { i, stream ->
                val baseName = "Vegeta[${stream.serverIdx}] ${stream.label}"
                val idx = labelCounter.getOrDefault(baseName, 0) + 1
                labelCounter[baseName] = idx
                val numbered = if (idx == 1) baseName else "$baseName #$idx"
                val displayName = if (i == 0 && lastGood != null && stream.url == lastGood) "★ $numbered" else numbered
                stream to displayName
            }
            val servers = displayedStreams.map { (stream, displayName) ->
                // Track url→channelKey so getVideo can record success when this stream plays
                streamUrlToChannelKey[stream.url] = key
                Video.Server(
                    id = "vegeta_stream::${stream.serverIdx}::${stream.label}::${stream.url}",
                    name = displayName,
                )
            }

            Log.d(TAG, "getServers '$key': ${servers.size} servers (total in registry: ${info.streams.size})")

            // If the channel has very few streams AND Phase 3 hasn't run yet,
            // trigger on-demand backfill. Once we have ≥2 streams or Phase 3 is
            // done, skip — the user is about to start playing, no point loading more
            // (creates GC pressure that can stutter playback).
            if (servers.size < 2 && !phase3Done) {
                triggerOnDemandBackfill("low-streams $key (${servers.size})")
            }

            currentEmitJob?.cancel()
            _additionalServers.resetReplayCache()
            currentEmitJob = scope.launch {
                delay(150)
                var emitted = 0
                for (stream in info.streams.drop(servers.size)) {
                    if (!isActive) return@launch
                    if (emitted >= MAX_VARIANTS_PER_CHANNEL) return@launch
                    val server = Video.Server(
                        id = "vegeta_stream::${stream.serverIdx}::${stream.label}::${stream.url}",
                        name = "Vegeta[${stream.serverIdx}] ${stream.label}",
                    )
                    _additionalServers.emit(server)
                    emitted++
                }
                // Late watcher: if backfill completes after this, emit new streams.
                spawnLateRegistryWatcher(key)
            }

            servers.ifEmpty { emptyList() }
        } catch (e: Exception) {
            Log.e(TAG, "getServers($id) error", e); emptyList()
        }
    }

    /** Watch the registry for new streams on `key` after backfill finishes, emit each one. */
    private fun spawnLateRegistryWatcher(key: String) {
        scope.launch {
            val seenUrls = mutableSetOf<String>()
            synchronized(registryLock) {
                channelRegistry[key]?.streams?.forEach { seenUrls.add(it.url) }
            }
            val deadline = System.currentTimeMillis() + 45_000L
            var emittedExtra = 0
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(1500)
                val latest = synchronized(registryLock) { channelRegistry[key]?.streams?.toList() } ?: continue
                for (s in latest) {
                    if (seenUrls.add(s.url)) {
                        if (emittedExtra >= MAX_VARIANTS_PER_CHANNEL) break
                        val server = Video.Server(
                            id = "vegeta_stream::${s.serverIdx}::${s.label}::${s.url}",
                            name = "Vegeta[${s.serverIdx}] ${s.label}",
                        )
                        Log.d(TAG, "  → late emit Vegeta[${s.serverIdx}] ${s.label} for '$key'")
                        _additionalServers.emit(server)
                        emittedExtra++
                    }
                }
                if (phase3Done && ondemandJob?.isActive != true) break
            }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        try {
            val parts = server.id.removePrefix("vegeta_stream::").split("::", limit = 3)
            if (parts.size < 3) throw Exception("Bad server id")
            val streamUrl = parts[2]
            // Mark this stream as the last-known-good for the channel — next time
            // getServers is called we'll surface it first (with a ★ prefix).
            streamUrlToChannelKey[streamUrl]?.let { channelKey ->
                lastGoodStreamUrl[channelKey] = streamUrl
                Log.d(TAG, "getVideo: pinning $streamUrl as last-known-good for '$channelKey'")
            }
            Log.d(TAG, "getVideo → ${streamUrl.take(120)}")
            Video(streamUrl, headers = mapOf("User-Agent" to USER_AGENT))
        } catch (e: Exception) {
            Log.e(TAG, "getVideo error", e); throw e
        }
    }

    fun getOrderedChannelIds(): List<String> {
        return curatedChannels.map { "vegeta::${it.key}" }
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
        val key = channelId.removePrefix("vegeta::").removePrefix("vegeta_ep::")
        return curatedChannels.firstOrNull { it.key == key }?.displayName
            ?: synchronized(registryLock) { channelRegistry[key]?.displayName }
    }

    fun getChannelPoster(channelId: String): String? {
        val key = channelId.removePrefix("vegeta::").removePrefix("vegeta_ep::")
        val name = getChannelDisplayName(channelId) ?: return null
        return logoUrlFor(name)
    }
}
