package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import androidx.preference.PreferenceManager
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.IptvChannelIndexService
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.ConnectionSpec
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Marqueur pour les providers qui ont un warm-up bloquant au démarrage.
 *  HomeViewModel l'utilise pour NE PAS servir le cache disque tant que le
 *  warm-up n'est pas terminé → l'user voit le spinner de chargement. */
interface WarmUpCapable {
    val isWarmUpDone: Boolean
}

object WiTvProviderV2 : Provider, IptvProvider, WarmUpCapable {

    override val name = "WiTV v2"
    override val baseUrl = "https://witv.team"
    override val logo = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_witv"
    override val language = "fr"
    override val isWarmUpDone: Boolean get() = isWarmUpComplete

    private const val TAG = "WiTvProviderV2"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
    private const val SPORTS_PROG_URL = "https://sportsonline.vc/prog.txt"
    // V2 : plus de scraping WiTV ni CLUONE — catalogue hardcodé, OLA+OTF au clic

    // OTF TV API — AES-128-CBC encrypted channel list
    private const val OTF_API_URL = "https://app.otf-tv.com/otf/authV3.php"
    private const val OTF_AES_KEY = "@z5wFi5vDgtF_vds"

    // OLA TV API — JSON-in-Base64 protocol, AES-128-CBC response
    private const val OLA_TV_API = "http://iptvdroid.monster/IP11/api.php"
    private const val OLA_TV_AES_KEY = "3234567890123453"
    private const val OLA_TV_SECRET = "MRZEREZIS"
    // OLA TV: primary FR server (user confirmed)
    private val OLA_TV_PRIMARY_FR = "2020"
    // Full server list cached from newolatvcategory0326: cid → category_name
    @Volatile private var olaTvServerMap: Map<String, String> = emptyMap()
    // Discovered FR servers (populated by background scan): list of cids
    private val olaTvFrCids = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile private var olaTvFrScanDone = false

    // 2026-05-22 : persistance des CIDs FR découverts (2e ouverture instantanée)
    private const val PREF_OLA_FR_CIDS = "witv_ola_fr_cids"
    private fun loadPersistedFrCids() {
        try {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
            val saved = prefs.getString(PREF_OLA_FR_CIDS, null)
            if (!saved.isNullOrBlank()) {
                val cids = saved.split(",").filter { it.isNotBlank() }
                if (cids.isNotEmpty()) {
                    olaTvFrCids.addAllAbsent(cids)
                    Log.d(TAG, "OLA TV FR: restored ${cids.size} persisted CIDs")
                }
            }
        } catch (_: Exception) {}
    }
    private fun persistFrCids() {
        try {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
            prefs.edit().putString(PREF_OLA_FR_CIDS, olaTvFrCids.joinToString(",")).apply()
            Log.d(TAG, "OLA TV FR: persisted ${olaTvFrCids.size} CIDs")
        } catch (_: Exception) {}
    }

    // ── 2026-05-22 : BAN des CIDs OLA sans contenu FR ──
    // Persistés en SharedPrefs avec timestamp pour TTL (3 jours).
    // Quand l'user change de langue → clear ban → rescan complet.
    private const val PREF_OLA_BANNED_CIDS = "witv_ola_banned_cids"
    private const val PREF_OLA_BANNED_TS = "witv_ola_banned_ts"
    private const val BANNED_TTL_MS = 3L * 24 * 60 * 60 * 1000  // 3 jours
    private val olaTvBannedCids = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun loadPersistedBannedCids() {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
            val ts = prefs.getLong(PREF_OLA_BANNED_TS, 0L)
            // TTL expiré → on efface et on rescanne
            if (System.currentTimeMillis() - ts > BANNED_TTL_MS) {
                prefs.edit().remove(PREF_OLA_BANNED_CIDS).remove(PREF_OLA_BANNED_TS).apply()
                olaTvBannedCids.clear()
                Log.d(TAG, "OLA banned CIDs: TTL expiré, cache effacé")
                return
            }
            val saved = prefs.getString(PREF_OLA_BANNED_CIDS, null)
            if (!saved.isNullOrBlank()) {
                val cids = saved.split(",").filter { it.isNotBlank() }
                if (cids.isNotEmpty()) {
                    olaTvBannedCids.addAllAbsent(cids)
                    Log.d(TAG, "OLA banned CIDs: restored ${cids.size} banned servers")
                }
            }
        } catch (_: Exception) {}
    }

    private fun persistBannedCids() {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
            prefs.edit()
                .putString(PREF_OLA_BANNED_CIDS, olaTvBannedCids.joinToString(","))
                .putLong(PREF_OLA_BANNED_TS, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "OLA banned CIDs: persisted ${olaTvBannedCids.size} banned servers")
        } catch (_: Exception) {}
    }

    private fun clearBannedCids() {
        olaTvBannedCids.clear()
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
            prefs.edit().remove(PREF_OLA_BANNED_CIDS).remove(PREF_OLA_BANNED_TS).apply()
        } catch (_: Exception) {}
        Log.d(TAG, "OLA banned CIDs: cleared (langue changée)")
    }

    // ── 2026-05-22 : INDEX INVERSÉ chaîne → CIDs OLA ──
    // Persisté en SharedPrefs JSON. Quand on découvre que TF1 est sur CID 42,
    // on l'enregistre. Au prochain clic : on va DIRECTEMENT au CID 42, sans scanner les 800.
    private const val PREF_OLA_CHANNEL_INDEX = "witv_ola_channel_index"
    private val olaChannelIndex = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    private fun loadChannelIndex() {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
            val json = prefs.getString(PREF_OLA_CHANNEL_INDEX, null)
            if (!json.isNullOrBlank()) {
                val obj = JSONObject(json)
                var count = 0
                for (key in obj.keys()) {
                    val arr = obj.optJSONArray(key) ?: continue
                    val cids = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        val cid = arr.optString(i, "")
                        if (cid.isNotBlank()) cids.add(cid)
                    }
                    if (cids.isNotEmpty()) {
                        olaChannelIndex[key] = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>()).apply { addAll(cids) }
                        count++
                    }
                }
                Log.d(TAG, "OLA channel index: restored $count channels")
            }
        } catch (_: Exception) {}
    }

    private fun persistChannelIndex() {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
            val obj = JSONObject()
            for ((key, cids) in olaChannelIndex) {
                if (cids.isNotEmpty()) {
                    obj.put(key, JSONArray(cids.toList()))
                }
            }
            prefs.edit().putString(PREF_OLA_CHANNEL_INDEX, obj.toString()).apply()
            Log.d(TAG, "OLA channel index: persisted ${olaChannelIndex.size} channels")
        } catch (_: Exception) {}
    }

    /** Enregistre qu'un CID contient une chaîne donnée. Appelé après un fetch réussi. */
    private fun recordChannelCid(channelKey: String, cid: String) {
        val set = olaChannelIndex.getOrPut(channelKey) {
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        }
        set.add(cid)
    }

    /** Retourne les CIDs connus pour une chaîne (peut être vide si jamais vue). */
    private fun getKnownCids(channelKey: String): Set<String> {
        return olaChannelIndex[channelKey] ?: emptySet()
    }

    private fun clearChannelIndex() {
        olaChannelIndex.clear()
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
            prefs.edit().remove(PREF_OLA_CHANNEL_INDEX).apply()
        } catch (_: Exception) {}
        Log.d(TAG, "OLA channel index: cleared (langue changée)")
    }

    // ── 2026-05-22 : WARM-UP = chargement IPTV ──
    // À l'ouverture du provider : scan OLA + OTF en parallèle.
    // Le getHome() ATTEND que le warm-up soit terminé → l'user voit
    // le spinner de chargement standard, puis les chaînes apparaissent
    // avec les serveurs déjà chauds.
    @Volatile var isWarmUpComplete = false
        private set
    @Volatile private var warmUpRunning = false

    // ── V2 : Filtre langue/groupe ──
    // Défaut = "" (France, catalogue hardcodé). Autre = nom du groupe OTF.
    private const val PREF_LANG_GROUP = "witv_v2_lang_group"
    fun getSelectedGroup(): String {
        return try {
            PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
                .getString(PREF_LANG_GROUP, "") ?: ""
        } catch (_: Exception) { "" }
    }
    fun setSelectedGroup(group: String) {
        try {
            PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
                .edit().putString(PREF_LANG_GROUP, group).apply()
        } catch (_: Exception) {}
        // Langue changée → clear ban CIDs + index + reset scan pour rescan complet
        clearBannedCids()
        clearChannelIndex()
        IptvChannelIndexService.clearCache()
        olaTvFrCids.clear()
        olaTvFrScanDone = false
        isWarmUpComplete = false
        warmUpRunning = false
        Log.d(TAG, "setSelectedGroup('$group'): ban/FR CIDs cleared, warm-up reset")
    }

    // ── Progressive OLA TV server loading ──
    // Emits OLA CID servers (not streams) as background scan discovers them
    private val _additionalServers = MutableSharedFlow<Video.Server>(extraBufferCapacity = 100)
    override val additionalServersFlow: SharedFlow<Video.Server> = _additionalServers
    private val olaScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentOlaJob: kotlinx.coroutines.Job? = null

    // (OLA TV streams are emitted progressively via _additionalServers flow)

    // (Vavoo removed — backend (oha.to / vavoo.to / huhu.to / kool.to) returns 404 on /play
    //  endpoints since approximately late April 2026. All retrieval was speculative — disabled
    //  to avoid offering broken servers in the picker. See git history if needed.)

    // (Dric4rTV and 3BoxTV removed — negligible source coverage)

    // ───────── HTTP ─────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        // Force HTTP/1.1 — LiteSpeed server rejects OkHttp's HTTP/2 ALPN on some TLS stacks
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        // DNS-over-HTTPS — bypass system DNS filters (AV web protection, ISP DNS blocks, etc.)
        // Same pattern used by every other provider in the app.
        .dns(DnsResolver.doh)
        .cookieJar(object : CookieJar {
            private val store = HashMap<String, List<Cookie>>()
            override fun saveFromResponse(u: HttpUrl, c: List<Cookie>) { store[u.host] = c }
            override fun loadForRequest(u: HttpUrl): List<Cookie> = store[u.host] ?: emptyList()
        })
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
            )
        }
        .build()

    // Fast client for probing (short timeouts — fail fast, try next)
    private val probeClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(Service::class.java)

    interface Service {
        @GET
        suspend fun getPage(
            @Url url: String,
            @Header("Referer") referer: String = "https://witv.team"
        ): Document
    }

    // ───────── Channel registry (dedup across sources) ─────────

    private class ChannelInfo(
        var displayName: String,
        var logo: String,
        var category: String,
        val sources: MutableMap<String, String> = mutableMapOf(),
        // key = source label ("OLA", etc.), value = metadata
        // (OLA + OTF streams are fetched on-demand in getServers, not stored here)
    ) {
        /** True if the channel has at least one concrete server source.
         *  Channels without any source are hidden from the UI. */
        // V2 : catalogue hardcodé → toutes les chaînes sont visibles.
        // Les sources réelles (OLA/OTF) sont résolues au clic dans getServers.
        fun hasServer(): Boolean = true
    }

    private val channelRegistry = LinkedHashMap<String, ChannelInfo>()
    private val registryLock = Any()   // guard ALL reads/writes to channelRegistry
    @Volatile private var lastLoadTime = 0L
    private val registryMutex = Mutex()  // prevent concurrent ensureRegistry() calls
    @Volatile private var registryLoaded = false

    /** 2026-05-18 v85 : vide le cache catalogue (anti-OOM). */
    fun clearCache() {
        try {
            registryLoaded = false
            lastLoadTime = 0L
            phase2Done = false
            olaTvServerMap = emptyMap()
            olaTvFrScanDone = false
            isWarmUpComplete = false
            warmUpRunning = false
            android.util.Log.d(TAG, "clearCache: WiTV registry vidé")
        } catch (_: Throwable) {}
    }
    @Volatile private var phase2Done = false  // true once enrichment sources (OTF, OLA TV) finish
    private const val CACHE_DURATION = 30 * 60 * 1000L // 30 min


    // TNT channel order (numéro de canal TNT français) — used to sort channels in each category
    private val tntOrder: Map<String, Int> by lazy {
        val list = listOf(
            // ── Généraliste (TNT gratuite) ──
            "TF1", "France 2", "France 3", "Canal+", "France 5", "M6", "Arte",
            "C8", "W9", "TMC", "TFX", "NRJ 12", "LCP", "France 4",
            "BFM TV", "BFMTV", "CNews", "CNEWS", "CStar", "Gulli",
            "TF1 Séries Films", "L'Équipe", "6ter", "RMC Story",
            "RMC Découverte", "Chérie 25", "LCI", "Franceinfo",
            // ── Info ──
            "BFM Business", "France 24", "TV5 Monde", "Euronews", "i24 News",
            "Public Sénat", "BFM Paris", "BFM Lyon", "BFM Marseille",
            "BFM Grand Littoral", "BFM Grand Lille",
            // ── Cinéma ──
            "Canal+ Cinéma", "Canal+ Séries", "Canal+ Family", "Canal+ Docs",
            "OCS Max", "OCS Géants", "OCS Choc", "OCS City",
            "Ciné+ Premier", "Ciné+ Frisson", "Ciné+ Émotion", "Ciné+ Famiz", "Ciné+ Club", "Ciné+ Classic",
            "Paramount Channel",
            // ── Sport ──
            "Canal+ Sport", "Canal+ Foot", "Canal+ Sport 360",
            "beIN Sports 1", "beIN Sports 2", "beIN Sports 3",
            "RMC Sport 1", "RMC Sport 2",
            "Eurosport 1", "Eurosport 2", "L'Équipe",
            "Infosport+",
            // ── Enfants ──
            "Gulli", "Canal J", "Tiji", "Boing", "Cartoon Network",
            "Nickelodeon", "Nickelodéon", "Disney Channel", "Boomerang",
            "Toonami", "J-One",
            // ── Musique ──
            "M6 Music", "NRJ Hits", "Trace Urban", "MCM", "Mezzo",
            "MTV", "VH1", "Trace Africa",
            // ── Documentaire ──
            "Planète+", "Ushuaïa TV", "Histoire TV", "Toute l'Histoire",
            "Science & Vie TV", "National Geographic", "Nat Geo Wild",
            "Discovery Channel", "RMC Découverte", "Trek",
        )
        list.withIndex().associate { (idx, name) -> norm(name) to idx }
    }

    /** Sort channels within a category by TNT order, then alphabetically for unknowns. */
    private fun sortByTnt(items: List<TvShow>): List<TvShow> {
        return items.sortedWith(compareBy(
            { tntOrder[norm(it.title)] ?: 999 },
            { it.title.lowercase() }
        ))
    }

    /** Ordered list of active channel IDs (ch::key) sorted by TNT order. */
    fun getOrderedChannelIds(): List<String> {
        return synchronized(registryLock) {
            sortByTnt(
                channelRegistry
                    .filter { it.value.hasServer() }
                    .map { (k, v) -> toTvShow(k, v) }
            ).map { it.id }
        }
    }

    /** Get the channel ID before [currentId] in the ordered list, or null if first. */
    fun getPreviousChannelId(currentId: String): String? {
        val list = getOrderedChannelIds()
        val idx = list.indexOf(currentId)
        return if (idx > 0) list[idx - 1] else null
    }

    /** Get the channel ID after [currentId] in the ordered list, or null if last. */
    fun getNextChannelId(currentId: String): String? {
        val list = getOrderedChannelIds()
        val idx = list.indexOf(currentId)
        return if (idx in 0 until list.lastIndex) list[idx + 1] else null
    }

    /** Get display name for a channel ID. */
    fun getChannelDisplayName(channelId: String): String? {
        val key = channelId.removePrefix("ch::").removePrefix("sport::")
        return synchronized(registryLock) { channelRegistry[key]?.displayName }
    }

    /** Get poster URL for a channel ID. */
    fun getChannelPoster(channelId: String): String? {
        val key = channelId.removePrefix("ch::").removePrefix("sport::")
        return synchronized(registryLock) { channelRegistry[key]?.logo?.ifBlank { null } }
    }

    // ── Server preload cache for adjacent channels (IPTV zapping) ──

    private data class PreloadEntry(
        val servers: List<Video.Server>,
        val timestamp: Long,
    )

    private val preloadCache = java.util.concurrent.ConcurrentHashMap<String, PreloadEntry>()
    private val PRELOAD_TTL_MS = 5 * 60 * 1000L  // 5 minutes

    /** Get preloaded servers for a channel, or null if not cached / expired. */
    fun getPreloadedServers(channelId: String): List<Video.Server>? {
        val entry = preloadCache[channelId] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > PRELOAD_TTL_MS) {
            preloadCache.remove(channelId)
            return null
        }
        return entry.servers
    }

    /** Preload servers for adjacent channels in the background. */
    suspend fun preloadAdjacentChannels(currentId: String) {
        val ids = mutableListOf<String>()
        getPreviousChannelId(currentId)?.let { ids.add(it) }
        getNextChannelId(currentId)?.let { ids.add(it) }

        for (id in ids) {
            if (preloadCache[id] != null) continue  // already cached
            try {
                val videoType = com.streamflixreborn.streamflix.models.Video.Type.Episode(
                    id = id, number = 1, title = "",
                    poster = null, overview = null,
                    tvShow = com.streamflixreborn.streamflix.models.Video.Type.Episode.TvShow(
                        id = id, title = "", poster = null, banner = null, releaseDate = null, imdbId = null
                    ),
                    season = com.streamflixreborn.streamflix.models.Video.Type.Episode.Season(
                        number = 1, title = null
                    ),
                )
                val servers = getServers(id, videoType)
                preloadCache[id] = PreloadEntry(servers, System.currentTimeMillis())
                Log.d(TAG, "Preloaded ${servers.size} servers for $id")
            } catch (e: Exception) {
                Log.w(TAG, "Preload failed for $id: ${e.message}")
            }
        }
    }

    // V2 : catégories du home (ordre d'affichage)
    private val homeCategories = listOf(
        "Généraliste", "Cinéma", "Info", "Documentaire",
        "Enfants", "Musique", "Sport", "Divertissement",
    )

    // V2 : catalogue hardcodé — chaînes FR principales (TNT + thématiques)
    // Pas de réseau au démarrage = INSTANTANÉ. OLA+OTF fournissent les streams au clic.
    private val hardcodedChannels: List<Triple<String, String, String>> = listOf(
        // ── Généraliste (TNT) ──
        Triple("TF1", "Généraliste", ""), Triple("France 2", "Généraliste", ""),
        Triple("France 3", "Généraliste", ""), Triple("Canal+", "Généraliste", ""),
        Triple("France 5", "Généraliste", ""), Triple("M6", "Généraliste", ""),
        Triple("Arte", "Généraliste", ""), Triple("C8", "Généraliste", ""),
        Triple("W9", "Généraliste", ""), Triple("TMC", "Généraliste", ""),
        Triple("TFX", "Généraliste", ""), Triple("NRJ 12", "Généraliste", ""),
        Triple("France 4", "Généraliste", ""), Triple("CSTAR", "Généraliste", ""),
        Triple("6ter", "Généraliste", ""), Triple("RMC Story", "Généraliste", ""),
        Triple("Chérie 25", "Généraliste", ""), Triple("TV Breizh", "Généraliste", ""),
        Triple("Téva", "Généraliste", ""), Triple("Paris Première", "Généraliste", ""),
        Triple("RTL9", "Généraliste", ""), Triple("AB1", "Généraliste", ""),
        Triple("Série Club", "Généraliste", ""), Triple("13ème Rue", "Généraliste", ""),
        Triple("TF1 Séries Films", "Généraliste", ""),
        // ── Cinéma ──
        Triple("Canal+ Cinéma", "Cinéma", ""), Triple("OCS Max", "Cinéma", ""),
        Triple("OCS Choc", "Cinéma", ""), Triple("OCS City", "Cinéma", ""),
        Triple("OCS Géants", "Cinéma", ""), Triple("Ciné+ Premier", "Cinéma", ""),
        Triple("Ciné+ Frisson", "Cinéma", ""), Triple("Ciné+ Émotion", "Cinéma", ""),
        Triple("Ciné+ Famiz", "Cinéma", ""), Triple("Ciné+ Classic", "Cinéma", ""),
        Triple("Ciné+ Club", "Cinéma", ""), Triple("Action", "Cinéma", ""),
        Triple("TCM Cinéma", "Cinéma", ""), Triple("Paramount Channel", "Cinéma", ""),
        // ── Info ──
        Triple("BFM TV", "Info", ""), Triple("CNEWS", "Info", ""),
        Triple("LCI", "Info", ""), Triple("Franceinfo", "Info", ""),
        Triple("France 24", "Info", ""), Triple("Euronews", "Info", ""),
        Triple("BFM Business", "Info", ""), Triple("TV5 Monde", "Info", ""),
        // ── Documentaire ──
        Triple("Planète+", "Documentaire", ""), Triple("National Geographic", "Documentaire", ""),
        Triple("Nat Geo Wild", "Documentaire", ""), Triple("Discovery Channel", "Documentaire", ""),
        Triple("Science & Vie TV", "Documentaire", ""), Triple("Histoire TV", "Documentaire", ""),
        Triple("Ushuaïa TV", "Documentaire", ""), Triple("RMC Découverte", "Documentaire", ""),
        // ── Enfants ──
        Triple("Canal J", "Enfants", ""), Triple("Gulli", "Enfants", ""),
        Triple("Disney Channel", "Enfants", ""), Triple("Disney Junior", "Enfants", ""),
        Triple("Nickelodeon", "Enfants", ""), Triple("Cartoon Network", "Enfants", ""),
        Triple("Boing", "Enfants", ""), Triple("Tiji", "Enfants", ""),
        Triple("Boomerang", "Enfants", ""),
        // ── Musique ──
        Triple("MTV", "Musique", ""), Triple("MCM", "Musique", ""),
        Triple("Trace Urban", "Musique", ""), Triple("Mezzo", "Musique", ""),
        Triple("M6 Music", "Musique", ""), Triple("NRJ Hits", "Musique", ""),
        // ── Sport ──
        Triple("beIN Sports 1", "Sport", ""), Triple("beIN Sports 2", "Sport", ""),
        Triple("beIN Sports 3", "Sport", ""), Triple("RMC Sport 1", "Sport", ""),
        Triple("RMC Sport 2", "Sport", ""), Triple("Eurosport 1", "Sport", ""),
        Triple("Eurosport 2", "Sport", ""), Triple("Canal+ Sport", "Sport", ""),
        Triple("Canal+ Foot", "Sport", ""), Triple("L'Équipe", "Sport", ""),
        Triple("Infosport+", "Sport", ""), Triple("DAZN 1", "Sport", ""),
        Triple("DAZN 2", "Sport", ""), Triple("DAZN 3", "Sport", ""),
        // ── Divertissement ──
        Triple("Comédie+", "Divertissement", ""), Triple("Game One", "Divertissement", ""),
        Triple("J-One", "Divertissement", ""), Triple("Altice Studio", "Divertissement", ""),
        Triple("Polar+", "Divertissement", ""), Triple("Mangas", "Divertissement", ""),
    )

    /** Normalize a channel name for dedup matching. */
    private fun norm(raw: String): String =
        raw.lowercase()
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâä]"), "a")
            .replace(Regex("[ùûü]"), "u")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace("ç", "c")
            // Strip bracketed/parenthesized annotations: [Geo-blocked], (720p), (1080p), etc.
            .replace(Regex("\\[.*?]"), "")
            .replace(Regex("\\(.*?\\)"), "")
            // Strip country suffixes
            .replace(Regex("\\s+(france|fr|french|belgique|be|suisse|ch)\\s*$"), "")
            // Strip quality/resolution suffixes
            .replace(Regex("\\s*(hd|sd|fhd|uhd|4k|\\+1|1080p|720p|480p|360p)\\s*$"), "")
            .trim()
            .replace(Regex("[^a-z0-9]"), "")
            // Normalize sports→sport for beIN SPORTS vs beIN SPORT
            .replace("sports", "sport")

    /** V2 : populate registry from hardcoded channel list (instant, no network). */
    private fun populateHardcodedChannels() {
        synchronized(registryLock) {
            for ((name, category, _) in hardcodedChannels) {
                val key = norm(name)
                if (key.isEmpty()) continue
                channelRegistry.getOrPut(key) {
                    ChannelInfo(name, "", category)
                }
            }
        }
        Log.d(TAG, "V2 hardcoded: ${channelRegistry.size} channels loaded")
    }

    /** Load channels from all sources — Phase 1 returns instantly, Phase 2 runs in background. */
    private suspend fun ensureRegistry() {
        // Fast path: volatile flag avoids reading non-thread-safe LinkedHashMap
        if (registryLoaded && System.currentTimeMillis() - lastLoadTime < CACHE_DURATION) return

        // Slow path: acquire mutex so only ONE caller loads at a time
        registryMutex.withLock {
            // Re-check after acquiring lock — another coroutine may have loaded while we waited
            if (registryLoaded && System.currentTimeMillis() - lastLoadTime < CACHE_DURATION) return@withLock
            registryLoaded = false
            phase2Done = false
            channelRegistry.clear()
            UserPreferences.clearFailedChannels()
            // 2026-05-22 : restaurer les CIDs persistés (FR + bannis)
            if (olaTvFrCids.isEmpty()) loadPersistedFrCids()
            if (olaTvBannedCids.isEmpty()) loadPersistedBannedCids()
            if (olaChannelIndex.isEmpty()) loadChannelIndex()

        val t0 = System.currentTimeMillis()

        // ═══ V2 : catalogue HARDCODÉ = INSTANTANÉ (0 réseau) ═══
        populateHardcodedChannels()

        // ═══ Mark registry as loaded IMMÉDIATEMENT ═══
        lastLoadTime = System.currentTimeMillis()
        registryLoaded = true
        phase2Done = true
        Log.d(TAG, "⏱ V2 catalogue instantané: ${channelRegistry.size} channels in ${System.currentTimeMillis() - t0}ms")

        } // end registryMutex.withLock
    }

    // ═══ WARM-UP IPTV (séparé du registre) ═══
    // Appelé par getHome() APRÈS ensureRegistry().
    // BLOQUE getHome() → l'user voit le spinner de chargement standard.
    // Quand c'est fini, les chaînes apparaissent avec les serveurs déjà chauds.
    private val warmUpMutex = Mutex()

    // Top chaînes pré-fetchées en PRIORITÉ (les plus regardées)
    private val PREFETCH_TOP_CHANNELS = listOf(
        "tf1", "france2", "france3", "m6", "canal", "france5",
        "arte", "c8", "w9", "tmc",
    )

    private suspend fun ensureWarmUp() {
        // Fast path: déjà fait
        if (isWarmUpComplete) return

        warmUpMutex.withLock {
            // Re-check après lock
            if (isWarmUpComplete) return@withLock

            warmUpRunning = true
            Log.d(TAG, "⏱ WARM-UP: démarrage (Firebase + OTF rapide, OLA scan en fond)")
            val warmT0 = System.currentTimeMillis()

            try {
                withContext(Dispatchers.IO) {
                    // ═══ Phase BLOQUANTE (~1-3s) : Firebase index + OTF + server list OLA ═══
                    // Firebase a DÉJÀ les CIDs de toutes les chaînes (index communautaire).
                    // On les charge en mémoire → au clic, la COURSE les utilise DIRECT.
                    val firebaseJob = async {
                        try {
                            val index = IptvChannelIndexService.fetchAllChannels()
                            Log.d(TAG, "⏱ WARM-UP FIREBASE: index OK (${index.size} chaînes, ${index.values.sumOf { it.size }} CIDs) +${System.currentTimeMillis() - warmT0}ms")
                        } catch (e: Exception) {
                            Log.e(TAG, "WARM-UP FIREBASE failed: ${e.message}")
                        }
                    }
                    val otfJob = async {
                        try {
                            ensureOtfCache()
                            Log.d(TAG, "⏱ WARM-UP OTF: cache OK (${otfChannelCache.size} chaînes) +${System.currentTimeMillis() - warmT0}ms")
                        } catch (e: Exception) {
                            Log.e(TAG, "WARM-UP OTF failed: ${e.message}")
                        }
                    }
                    val serverListJob = async {
                        try {
                            parseOlaTvServerList()
                            Log.d(TAG, "⏱ WARM-UP OLA: server list OK (${olaTvServerMap.size} serveurs) +${System.currentTimeMillis() - warmT0}ms")
                        } catch (e: Exception) {
                            Log.e(TAG, "WARM-UP OLA serverlist failed: ${e.message}")
                        }
                    }
                    // Timeout court : ces 3 sont rapides (réseau léger)
                    withTimeoutOrNull(15_000L) {
                        firebaseJob.await()
                        otfJob.await()
                        serverListJob.await()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WARM-UP error: ${e.message}")
            }

            // Vérifier combien de top chaînes ont des CIDs Firebase
            val firebaseReady = PREFETCH_TOP_CHANNELS.count { key ->
                IptvChannelIndexService.getKnownCids(key).isNotEmpty()
            }

            isWarmUpComplete = true
            warmUpRunning = false
            Log.d(TAG, "⏱ WARM-UP: TERMINÉ en ${(System.currentTimeMillis() - warmT0) / 1000}s — Firebase: $firebaseReady/${PREFETCH_TOP_CHANNELS.size} top chaînes prêtes, ${olaTvFrCids.size} FR CIDs persistés, ${otfChannelCache.size} OTF, Firebase index=${IptvChannelIndexService.indexSize()} chaînes")

            // ═══ Phase NON-BLOQUANTE : OLA scan FR + prefetch en fond ═══
            // Le home s'affiche IMMÉDIATEMENT. Le scan OLA enrichit Firebase pour les prochains lancements.
            // Au clic, la COURSE utilise les CIDs Firebase (déjà chargés ci-dessus).
            olaScope.launch {
                try {
                    if (!olaTvFrScanDone && olaTvServerMap.isNotEmpty()) {
                        scanOlaTvFrServers()
                        Log.d(TAG, "⏱ WARM-UP OLA: scan FR terminé en fond (${olaTvFrCids.size} FR, ${olaTvBannedCids.size} bannis)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WARM-UP OLA scan failed: ${e.message}")
                }
                // Prefetch streams des top chaînes (enrichissement cache, pas critique)
                launchPrefetch()
            }
        }
    }

    /** Pré-fetch en fond des streams OLA pour les chaînes du home.
     *  Phase 1 : top 10 chaînes (les plus regardées) avec timeout court → home rapide.
     *  Phase 2 : TOUTES les chaînes restantes du catalogue → index Firebase complet.
     *  Au clic, les streams sont DÉJÀ en cache = lancement quasi-instantané.
     *  L'index Firebase se construit automatiquement pour TOUS les utilisateurs. */
    private fun launchPrefetch() {
        if (olaTvFrCids.isEmpty() && olaTvServerMap.isEmpty()) return
        olaScope.launch {
            val warmT0 = System.currentTimeMillis()
            val primaryCid = olaTvServerMap.entries.find { it.value == OLA_TV_PRIMARY_FR }?.key
            val cidsToQuery = if (primaryCid != null) {
                listOf(primaryCid) + olaTvFrCids.take(4).filter { it != primaryCid }
            } else {
                olaTvFrCids.take(5)
            }
            if (cidsToQuery.isEmpty()) return@launch

            val prefetchSem = Semaphore(8)

            // Helper : pré-fetch une chaîne (shared par phase 1 et 2)
            suspend fun prefetchChannel(channelKey: String) {
                prefetchSem.withPermit {
                    val existing = olaStreamCache[channelKey]
                    if (existing != null && System.currentTimeMillis() < existing.expiresAtMs && existing.streams.isNotEmpty()) {
                        return@withPermit
                    }
                    val streams = mutableListOf<OlaStream>()
                    val firebaseCids = IptvChannelIndexService.getKnownCids(channelKey)
                    val localCids = getKnownCids(channelKey)
                    val allKnownCids = (firebaseCids + localCids).toSet()

                    // 1) Scanner les CIDs connus EN PARALLÈLE (pas séquentiel)
                    if (allKnownCids.isNotEmpty()) {
                        val knownResults = kotlinx.coroutines.coroutineScope {
                            allKnownCids.take(10).map { cid ->
                                async(Dispatchers.IO) {
                                    try { olaFetchSingleServer(cid, channelKey) } catch (_: Exception) { emptyList() }
                                }
                            }.awaitAll()
                        }
                        for (result in knownResults) {
                            for (ola in result) {
                                if (streams.none { it.url == ola.url }) streams.add(ola)
                            }
                        }
                    }

                    // 2) Scanner les FR CIDs restants EN PARALLÈLE (batches de 5)
                    val allFrCids = olaTvFrCids.toList()
                    val remainingCids = allFrCids.filter { it !in allKnownCids }
                    if (remainingCids.isNotEmpty()) {
                        for (batch in remainingCids.chunked(5)) {
                            val batchResults = kotlinx.coroutines.coroutineScope {
                                batch.map { cid ->
                                    async(Dispatchers.IO) {
                                        try { olaFetchSingleServer(cid, channelKey) } catch (_: Exception) { emptyList() }
                                    }
                                }.awaitAll()
                            }
                            for (result in batchResults) {
                                for (ola in result) {
                                    if (streams.none { it.url == ola.url }) streams.add(ola)
                                }
                            }
                        }
                    }

                    if (streams.isNotEmpty()) {
                        olaStreamCache[channelKey] = CachedOlaStreams(
                            streams, System.currentTimeMillis() + OLA_STREAM_CACHE_TTL
                        )
                        Log.d(TAG, "⏱ PRÉ-FETCH: $channelKey → ${streams.size} streams")
                    }
                }
            }

            // ── Phase 1 : top 10 chaînes (timeout 90s) ──
            Log.d(TAG, "⏱ PRÉ-FETCH Phase 1: top ${PREFETCH_TOP_CHANNELS.size} chaînes")
            val topJobs = PREFETCH_TOP_CHANNELS.map { key -> async { prefetchChannel(key) } }
            withTimeoutOrNull(90_000L) { topJobs.awaitAll() }
            Log.d(TAG, "⏱ PRÉ-FETCH Phase 1: terminée, ${olaStreamCache.size} en cache (+${(System.currentTimeMillis() - warmT0) / 1000}s)")

            // ── Phase 2 : TOUTES les chaînes restantes du catalogue (en fond, timeout 5min) ──
            val allChannelKeys = synchronized(registryLock) { channelRegistry.keys.toList() }
            val remainingKeys = allChannelKeys.filter { it !in PREFETCH_TOP_CHANNELS }
            if (remainingKeys.isNotEmpty()) {
                Log.d(TAG, "⏱ PRÉ-FETCH Phase 2: ${remainingKeys.size} chaînes restantes (fond, timeout 5min)")
                val remainJobs = remainingKeys.map { key -> async { prefetchChannel(key) } }
                withTimeoutOrNull(300_000L) { remainJobs.awaitAll() }
                Log.d(TAG, "⏱ PRÉ-FETCH Phase 2: terminée, ${olaStreamCache.size} en cache total (+${(System.currentTimeMillis() - warmT0) / 1000}s)")
            }
            // 2026-05-22 : persister l'index après le pré-fetch (les CIDs découverts sont enregistrés)
            persistChannelIndex()
            Log.d(TAG, "⏱ PRÉ-FETCH: terminé, ${olaStreamCache.size} chaînes en cache, index=${olaChannelIndex.size} chaînes (+${(System.currentTimeMillis() - warmT0) / 1000}s)")
        }
    }

    // ───────── OTF TV AES helpers ─────────

    /** OTF TV AES-128-CBC encryption (for hash parameter). */
    private fun otfEncrypt(plaintext: String): String {
        val keySpec = SecretKeySpec(OTF_AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec) // random IV
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ct
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    /** OTF TV AES-128-CBC decryption (for API response). */
    private fun otfDecrypt(encrypted: String): String {
        val keySpec = SecretKeySpec(OTF_AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        val raw = Base64.decode(encrypted, Base64.DEFAULT)
        val iv = raw.copyOfRange(0, 16)
        val ct = raw.copyOfRange(16, raw.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    // V2 : OTF TV = BACKUP LAZY (getServers) + source alternative (si groupe non-FR sélectionné).
    // Cache en mémoire : normKey → liste d'URLs m3u8.
    @Volatile private var otfCacheLoaded = false
    private val otfChannelCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    // OTF groupes disponibles (pour le picker langue)
    private val otfGroups = java.util.concurrent.CopyOnWriteArrayList<String>()
    // OTF channels par groupe : groupName → list of (displayName, normKey, urls)
    private data class OtfChannelEntry(val displayName: String, val normKey: String, val urls: List<String>, val group: String)
    private val otfChannelsByGroup = java.util.concurrent.ConcurrentHashMap<String, MutableList<OtfChannelEntry>>()

    /** Get available OTF groups (populated after first ensureOtfCache call). */
    fun getAvailableGroups(): List<String> = otfGroups.toList()

    /** Fetch & cache OTF TV channels (lazy, called on first getServers or picker).
     *  Thread-safe — multiple callers get the same cached result. */
    fun ensureOtfCache(): Map<String, List<String>> {
        if (otfCacheLoaded) return otfChannelCache
        synchronized(this) {
            if (otfCacheLoaded) return otfChannelCache
            try {
                val deviceId = "sf" + android.os.Build.FINGERPRINT.hashCode().toUInt().toString(16).padStart(8, '0') + "otf"
                val hash = otfEncrypt("5wF${deviceId}_Opd")

                val formBody = FormBody.Builder()
                    .add("DeviceID", deviceId)
                    .add("hash", hash)
                    .build()
                val req = Request.Builder()
                    .url(OTF_API_URL)
                    .post(formBody)
                    .header("User-Agent", USER_AGENT)
                    .build()

                val response = client.newCall(req).execute()
                val body = response.body?.string()
                if (body.isNullOrBlank() || body.contains("denied") || body.contains("error") || body.length < 50) {
                    Log.w(TAG, "OTF TV: bad response (code=${response.code}, len=${body?.length})")
                    otfCacheLoaded = true
                    return otfChannelCache
                }

                val decrypted = otfDecrypt(body)
                Log.d(TAG, "OTF TV: decrypted ${decrypted.length} chars")

                val fixed = decrypted
                    .replace(Regex(",\\s*\\]"), "]")
                    .replace(Regex(",\\s*\\}"), "}")
                val json = JSONObject(fixed)
                val streams = json.optJSONArray("Streams") ?: run {
                    Log.w(TAG, "OTF TV: no Streams array")
                    otfCacheLoaded = true
                    return otfChannelCache
                }

                var total = 0
                val groups = mutableListOf<String>()
                for (g in 0 until streams.length()) {
                    val group = streams.getJSONObject(g)
                    val groupName = group.optString("name", "").trim().ifBlank { "Autres" }
                    val channels = group.optJSONArray("Channels") ?: continue
                    if (channels.length() > 0 && groupName !in groups) groups.add(groupName)

                    for (c in 0 until channels.length()) {
                        val ch = channels.getJSONObject(c)
                        val chName = ch.optString("name", "").trim()
                        if (chName.isBlank()) continue
                        total++

                        val key = norm(chName)
                        if (key.isEmpty()) continue

                        val vq = ch.optJSONArray("vq")
                        val urls = mutableListOf<String>()
                        if (vq != null) {
                            for (q in 0 until vq.length()) {
                                val urlStr = vq.getJSONObject(q).optString("url", "").trim()
                                if (urlStr.isNotBlank() && urlStr.startsWith("http")) urls.add(urlStr)
                            }
                        }
                        if (urls.isNotEmpty()) {
                            otfChannelCache.putIfAbsent(key, urls)
                            // Stocker par groupe pour le home alternatif
                            otfChannelsByGroup.getOrPut(groupName) { mutableListOf() }
                                .add(OtfChannelEntry(chName, key, urls, groupName))
                        }
                    }
                }
                otfGroups.clear()
                otfGroups.addAll(groups)
                Log.d(TAG, "OTF TV: $total channels parsed, ${otfChannelCache.size} cached, groups=$groups")
            } catch (e: Exception) {
                Log.e(TAG, "OTF TV: API call failed — ${e.javaClass.simpleName}: ${e.message}")
            }
            otfCacheLoaded = true
        }
        return otfChannelCache
    }

    // ───────── OLA TV helpers (JSON-in-Base64 protocol) ─────────

    /** Build an OLA TV API payload: JSON with salt+sign+method_name, Base64-encoded.
     *  Protocol discovered via DEX analysis of com.second.mainfolder.API class:
     *  1. salt = Random(0..899)
     *  2. sign = MD5("MRZEREZIS" + salt)
     *  3. Build JSON: {salt, sign, method_name, ...extra params}
     *  4. Base64-encode the whole JSON string
     *  5. POST data=<base64> to the API URL */
    private fun olaBuildPayload(methodName: String, extras: Map<String, String> = emptyMap()): String {
        val salt = java.util.Random().nextInt(900).toString()
        val md = java.security.MessageDigest.getInstance("MD5")
        md.update(("$OLA_TV_SECRET$salt").toByteArray())
        val sign = md.digest().joinToString("") { "%02x".format(it) }

        val json = JSONObject().apply {
            put("salt", salt)
            put("sign", sign)
            put("method_name", methodName)
            extras.forEach { (k, v) -> put(k, v) }
        }
        return Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.DEFAULT)
    }

    /** OLA TV AES-128-CBC decryption (key = IV = "3234567890123453"). */
    private fun olaDecrypt(ciphertext: String): String {
        val keyBytes = OLA_TV_AES_KEY.toByteArray(Charsets.UTF_8)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(keyBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val raw = Base64.decode(ciphertext.trim(), Base64.DEFAULT)
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }

    /** Decode OLA TV token2 to extract MAC address.
     *  token2 is Base64-encoded: first N bytes are metadata, rest is MAC like 00:1A:79:39:DE:88 */
    private fun olaDecodeToken2(token: String): String? {
        return try {
            val raw = android.util.Base64.decode(token, android.util.Base64.DEFAULT)
            val text = String(raw, Charsets.ISO_8859_1)
            // Find MAC pattern XX:XX:XX:XX:XX:XX (no word boundary — binary prefix may confuse \b)
            val macRegex = Regex("""([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2})""")
            val result = macRegex.find(text)?.groupValues?.get(1)
            Log.d(TAG, "OLA decodeToken2: input=${token.take(30)}, decoded=${text.length} chars, text='${text.takeLast(30)}', mac=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "OLA decodeToken2 error: ${e.message}")
            null
        }
    }

    /** Named OLA TV stream: channel variant label + stream URL. */
    private data class OlaStream(val label: String, val url: String)

    /** Fetch channels from an Xtream Codes MAC portal.
     *  1. Handshake to get auth token
     *  2. Get channel list
     *  3. Return ALL matching channel variants with their labels */
    private fun olaFetchMacPortal(baseUrl: String, mac: String, channelKey: String): List<OlaStream> {
        val results = mutableListOf<OlaStream>()
        val encodedMac = java.net.URLEncoder.encode(mac, "UTF-8")
        val portalBase = "${baseUrl.trimEnd('/')}/portal.php"
        val cookie = "mac=$encodedMac; stb_lang=en; timezone=Europe%2FLondon"

        data class MacCandidate(val chName: String, val cmd: String, val exactScore: Int)

        fun macQualityRank(name: String): Int {
            val upper = name.uppercase()
            return when {
                "UHD" in upper || "4K" in upper -> 5
                "FHD" in upper -> 4
                "HD" in upper && "SD" !in upper -> 3
                "HEVC" in upper || "H265" in upper -> 0
                "SD" in upper -> 1
                else -> 2
            }
        }

        fun parseCandidates(data: JSONArray, channelKey: String, logTag: String = ""): List<MacCandidate> {
            val candidates = mutableListOf<MacCandidate>()
            for (i in 0 until data.length()) {
                val ch = data.optJSONObject(i) ?: continue
                val chName = ch.optString("name", "").trim()
                if (chName.isBlank() || chName.startsWith("#")) continue
                val nameNoPrefix = chName
                    .replace(Regex("^(FR|ES|PT|EN|DE|IT|AR|TR|NL|PL|RO|US|UK|BE|CH)[|:\\s]+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s+"), " ").trim()
                val cleanName = nameNoPrefix
                    .replace(Regex("[\\s|/]*(HD|SD|FHD|UHD|4K|RAW|HEVC|H265|PPV)[\\s/]*", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("\\s+"), " ").trim()
                val key = norm(cleanName)
                // v70 : match exact (score=0/1) OU startsWith (score=2) pour capturer
                //   "TF1 Séries Films" quand on cherche "tf1", "M6 Boutique" quand on
                //   cherche "m6", etc. On ne garde les startsWith que si pas d'exact.
                when {
                    key == channelKey -> {
                        val exactScore = if (norm(nameNoPrefix) == channelKey) 0 else 1
                        candidates.add(MacCandidate(chName, ch.optString("cmd", "").trim(), exactScore))
                    }
                    key.startsWith(channelKey) && (key.length == channelKey.length
                        || !key[channelKey.length].isLetterOrDigit()) -> {
                        // Variant élargi (ex: "TF1 Séries Films" → key="tf1seriesfilms" starts with "tf1"
                        // mais PAS "CANAL J" pour "canal" car 'j' est alphanum collé)
                        // La condition !isLetterOrDigit vérifie que le match n'est pas au milieu d'un mot
                        candidates.add(MacCandidate(chName, ch.optString("cmd", "").trim(), 2))
                    }
                }
            }
            // Si on a des exacts (score 0 ou 1), ne pas garder les élargis
            val hasExact = candidates.any { it.exactScore <= 1 }
            val filtered = if (hasExact) candidates.filter { it.exactScore <= 1 } else candidates
            val sorted = filtered.sortedWith(compareBy({ it.exactScore }, { macQualityRank(it.chName) }))
            if (sorted.isNotEmpty()) {
                Log.d(TAG, "OLA MAC parseCandidates '$channelKey': ${sorted.size} matches (${sorted.joinToString { "'${it.chName}'(s=${it.exactScore})" }})")
            }
            return sorted.take(5)
        }

        fun resolveCandidates(topCandidates: List<MacCandidate>, portalBase: String, stbUA: String, cookie: String, token: String): List<OlaStream> {
            val resolved = mutableListOf<OlaStream>()
            for (cand in topCandidates) {
                val rawUrl = cand.cmd.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
                val streamUrl = if (rawUrl.contains("localhost")) {
                    try {
                        val linkCmd = java.net.URLEncoder.encode(rawUrl, "UTF-8")
                        val linkReq = Request.Builder()
                            .url("$portalBase?type=itv&action=create_link&cmd=$linkCmd&series=&forced_storage=undefined&fav=0&JsHttpRequest=1-xml")
                            .header("User-Agent", stbUA).header("Cookie", cookie)
                            .header("Authorization", "Bearer $token").build()
                        val linkResp = probeClient.newCall(linkReq).execute()
                        val linkBody = linkResp.body?.string() ?: ""
                        Log.d(TAG, "OLA MAC create_link '${cand.chName}': ${linkBody.take(300)}")
                        val cmdResult = try {
                            JSONObject(linkBody).getJSONObject("js").getString("cmd")
                        } catch (_: Exception) { null }
                        cmdResult?.removePrefix("ffrt ")?.removePrefix("ffmpeg ")?.trim() ?: rawUrl
                    } catch (e: Exception) { rawUrl }
                } else rawUrl
                // 2026-05-22 : label propre = retirer préfixe pays + suffixe qualité
                // "FR - FRANCE 2 4K" → "France 2", "FR| TF1 HEVC" → "TF1"
                val label = cand.chName
                    .replace(Regex("^(FR|ES|PT|EN|DE|IT|AR|TR|NL|PL|RO|US|UK|BE|CH)[|:\\s-]+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*(HD|SD|FHD|UHD|4K|RAW|HEVC|H265|H\\.?265|PPV)\\s*", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (streamUrl.startsWith("http") && resolved.none { it.url == streamUrl }) {
                    Log.d(TAG, "OLA MAC variant(score=${cand.exactScore}): '$label' → ${streamUrl.take(120)}")
                    resolved.add(OlaStream(label, streamUrl))
                }
            }
            return resolved
        }

        try {
            // Step 1: Handshake
            val hsReq = Request.Builder()
                .url("$portalBase?type=stb&action=handshake&token=&JsHttpRequest=1-xml")
                .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3")
                .header("Cookie", cookie)
                .build()
            val hsResp = probeClient.newCall(hsReq).execute()
            val hsBody = hsResp.body?.string() ?: ""
            Log.d(TAG, "OLA MAC handshake: code=${hsResp.code}, body=${hsBody.take(200)}")

            val token = try {
                JSONObject(hsBody).getJSONObject("js").getString("token")
            } catch (_: Exception) {
                Log.w(TAG, "OLA MAC: no token in handshake response")
                return results
            }
            Log.d(TAG, "OLA MAC: got token ${token.take(20)}...")

            // 2026-05-22 : get_profile + get_genres en PARALLÈLE (était séquentiel → +3s)
            val stbUA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

            // Step 2+3: profile init + genres en parallèle
            val profReq = Request.Builder()
                .url("$portalBase?type=stb&action=get_profile&auth_token=$token&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie)
                .header("Authorization", "Bearer $token").build()
            val genreReq = Request.Builder()
                .url("$portalBase?type=itv&action=get_genres&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie)
                .header("Authorization", "Bearer $token").build()

            // Fire both requests simultaneously
            val profCall = probeClient.newCall(profReq)
            val genreCall = probeClient.newCall(genreReq)
            val profFuture = scanExecutor.submit<Unit> { profCall.execute().body?.close() }
            val genreResp = genreCall.execute()
            val genreBody = genreResp.body?.string() ?: ""
            try { profFuture.get(3, TimeUnit.SECONDS) } catch (_: Exception) {}

            val frGenreIds = mutableListOf<String>()
            frGenreIds.add("*") // fallback: all genres
            try {
                val genreArr = JSONObject(genreBody).optJSONArray("js") ?: JSONArray()
                Log.d(TAG, "OLA MAC: ${genreArr.length()} genres")
                for (g in 0 until genreArr.length()) {
                    val genre = genreArr.optJSONObject(g) ?: continue
                    val gTitle = genre.optString("title", "").lowercase()
                    val gId = genre.optString("id", "")
                    // Log all genres for diagnostics
                    if (g < 20) Log.d(TAG, "OLA MAC genre[$g]: id=$gId, title='${genre.optString("title")}'")
                    // Match French genre names (FR| prefix is the standard pattern)
                    if (gTitle.startsWith("fr|") || gTitle.startsWith("fr |")
                        || gTitle.contains("france") || gTitle.contains("french") || gTitle.contains("français")) {
                        frGenreIds.add(0, gId) // prioritize FR genres
                    }
                }
            } catch (_: Exception) {}

            // v70 : recherche PAR NOM via search= au lieu de paginer alphabétiquement.
            //   Avant: pagination 3 pages × N genres = chaînes lettre A-D seulement →
            //   TF1 (lettre T) et M6 (lettre M) JAMAIS trouvés. Maintenant: 1 requête
            //   search= retourne directement les matches → O(1) au lieu de O(pages).
            //   Fallback pagination si search= ne donne rien (vieux portails sans search).
            // 2026-05-22 FIX : utiliser le NOM D'AFFICHAGE (avec espaces) au lieu de la
            //   clé normalisée. "france2" → search="FRANCE 2" (match le portail).
            //   Avant : "FRANCE2" ne matchait pas "FRANCE 2" sur la plupart des portails
            //   → France 2/3/5, Canal+, etc. = quasi AUCUN résultat MAC portal.
            val displayName = synchronized(registryLock) { channelRegistry[channelKey]?.displayName }
            val searchTerm = (displayName ?: channelKey).uppercase()  // ex: "FRANCE 2", "TF1", "CANAL+"
            for (genreId in frGenreIds) {
                if (results.isNotEmpty()) break
                // Essai 1 : search= direct (FR genre en priorité, puis *)
                val searchReq = Request.Builder()
                    .url("$portalBase?type=itv&action=get_ordered_list&genre=$genreId&force_ch_link_check=&fav=0&sortby=name&p=1&search=$searchTerm&JsHttpRequest=1-xml")
                    .header("User-Agent", stbUA).header("Cookie", cookie)
                    .header("Authorization", "Bearer $token").build()
                try {
                    val searchResp = probeClient.newCall(searchReq).execute()
                    val searchBody = searchResp.body?.string() ?: ""
                    val searchJs = try { JSONObject(searchBody).getJSONObject("js") } catch (_: Exception) { null }
                    val searchData = searchJs?.optJSONArray("data")
                    if (searchData != null && searchData.length() > 0) {
                        Log.d(TAG, "OLA MAC search='$searchTerm' genre=$genreId → ${searchData.length()} results")
                        val topCandidates = parseCandidates(searchData, channelKey)
                        if (topCandidates.isNotEmpty()) {
                            results.addAll(resolveCandidates(topCandidates, portalBase, stbUA, cookie, token))
                            if (results.isNotEmpty()) break
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "OLA MAC search failed (fallback to pagination): ${e.message}")
                }
                // Fallback : pagination classique si search n'a rien donné
                if (results.isNotEmpty()) break
                val maxPages = if (genreId == "*") 1 else 3
                for (page in 1..maxPages) {
                    val chReq = Request.Builder()
                        .url("$portalBase?type=itv&action=get_ordered_list&genre=$genreId&force_ch_link_check=&fav=0&sortby=name&p=$page&JsHttpRequest=1-xml")
                        .header("User-Agent", stbUA).header("Cookie", cookie)
                        .header("Authorization", "Bearer $token").build()
                    val chResp = probeClient.newCall(chReq).execute()
                    val chBody = chResp.body?.string() ?: ""

                    val js = try { JSONObject(chBody).getJSONObject("js") } catch (_: Exception) { break }
                    val data = js.optJSONArray("data") ?: break
                    val totalItems = js.optInt("total_items", 0)
                    val maxPageItems = js.optInt("max_page_items", 14)
                    if (page == 1) Log.d(TAG, "OLA MAC genre=$genreId p$page: ${data.length()} ch, total=$totalItems")

                    val topCandidates = parseCandidates(data, channelKey)
                    if (topCandidates.isNotEmpty()) {
                        results.addAll(resolveCandidates(topCandidates, portalBase, stbUA, cookie, token))
                    }

                    if (results.isNotEmpty()) break
                    if (data.length() == 0 || (page * maxPageItems) >= totalItems) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "OLA MAC portal failed: ${e.message}")
        }
        return results
    }

    /** Fetch OLA TV streams on-demand for a channel.
     *  Called from getServers() when user clicks a channel.
     *  Returns ALL matching variants (e.g. M6, M6 4K, M6 HEVC) with labels.
     *  Queries FR servers: gets credentials via getToken128910, then:
     *  - If response has LIVETV array: search channels by name
     *  - If response has token1/token2: use MAC portal auth to get channel list */
    /** Query a single OLA TV server (by CID) for a channel. Returns streams found on that server. */
    private fun olaFetchSingleServer(cid: String, channelKey: String): List<OlaStream> {
        val streams = mutableListOf<OlaStream>()
        val fetchT0 = System.currentTimeMillis()
        try {
            val b64 = olaBuildPayload("getToken128910", mapOf("cid" to cid))
            val formBody = FormBody.Builder().add("data", b64).build()
            val req = Request.Builder()
                .url(OLA_TV_API)
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = probeClient.newCall(req).execute()
            val body = response.body?.string()
            if (body.isNullOrBlank()) return streams

            val jsonStr = try {
                val trimmed = body.trim()
                if (trimmed.startsWith("[") || trimmed.startsWith("{")) trimmed
                else olaDecrypt(trimmed)
            } catch (_: Exception) { return streams }

            val obj = try { JSONObject(jsonStr) } catch (_: Exception) { null }

            val liveTvArr = obj?.optJSONArray("LIVETV")
            val credObj = if (liveTvArr != null && liveTvArr.length() > 0) {
                val hasChannelNames = (0 until liveTvArr.length()).any {
                    liveTvArr.optJSONObject(it)?.optString("channel_name", "")?.isNotBlank() == true
                }
                if (hasChannelNames) {
                    // LIVETV with named channels → search directly
                    for (i in 0 until liveTvArr.length()) {
                        val ch = liveTvArr.optJSONObject(i) ?: continue
                        val chName = ch.optString("channel_name", ch.optString("name", "")).trim()
                        if (chName.isBlank()) continue
                        val cleanName = chName
                            .replace(Regex("^(FR|ES|PT|EN|DE|IT|AR|TR|NL|PL|RO|US|UK|BE|CH)[|:\\s]+", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("[\\s|/]*(HD|SD|FHD|UHD|4K|RAW|HEVC|H265|PPV)[\\s/]*", RegexOption.IGNORE_CASE), " ")
                            .replace(Regex("\\s+"), " ").trim()
                        if (norm(cleanName) != channelKey) continue
                        val ct1 = ch.optString("token1", ch.optString("url", "")).trim()
                        val streamUrl = if (ct1.startsWith("http")) ct1 else olaDecodeToken(ct1) ?: continue
                        val label = chName
                            .replace(Regex("^(FR|ES|PT|EN|DE|IT|AR|TR|NL|PL|RO|US|UK|BE|CH)[|:\\s-]+", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("\\s*(HD|SD|FHD|UHD|4K|RAW|HEVC|H265|H\\.?265|PPV)\\s*", RegexOption.IGNORE_CASE), " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        if (streams.none { it.url == streamUrl }) {
                            streams.add(OlaStream(label, streamUrl))
                        }
                    }
                    // Index le CID pour ce chemin LIVETV + report latence communautaire
                    if (streams.isNotEmpty()) {
                        recordChannelCid(channelKey, cid)
                        val latencyMs = System.currentTimeMillis() - fetchT0
                        olaScope.launch {
                            try { IptvChannelIndexService.reportCidLatency(channelKey, cid, latencyMs) } catch (_: Exception) {}
                        }
                    }
                    return streams
                }
                liveTvArr.optJSONObject(0)
            } else obj

            // MAC portal
            val t1 = credObj?.optString("token1", "") ?: ""
            val t2 = credObj?.optString("token2", "") ?: ""
            if (t1.isNotBlank() && t2.isNotBlank()) {
                val serverUrl = olaDecodeToken(t1)
                val mac = olaDecodeToken2(t2)
                if (serverUrl != null && mac != null) {
                    streams.addAll(olaFetchMacPortal(serverUrl, mac, channelKey))
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "OLA single cid=$cid failed: ${e.message}")
        }
        // 2026-05-23 : indexer le CID + report latence communautaire (tri par vitesse)
        if (streams.isNotEmpty()) {
            recordChannelCid(channelKey, cid)
            val latencyMs = System.currentTimeMillis() - fetchT0
            olaScope.launch {
                try { IptvChannelIndexService.reportCidLatency(channelKey, cid, latencyMs) } catch (_: Exception) {}
            }
        }
        return streams
    }

    private fun olaFetchStreams(channelKey: String, excludeCids: Set<String> = emptySet()): List<OlaStream> {
        val streams = mutableListOf<OlaStream>()
        if (olaTvServerMap.isEmpty()) {
            Log.w(TAG, "OLA TV on-demand: server map empty, skipping")
            return streams
        }

        // Find cid for primary FR server + any discovered FR servers
        val targetCids = mutableListOf<String>()
        // Primary server first
        olaTvServerMap.entries.find { it.value == OLA_TV_PRIMARY_FR }?.key?.let { targetCids.add(it) }
        // Add discovered FR servers (from background scan) as fallbacks
        for (cid in olaTvFrCids) {
            if (cid !in targetCids) targetCids.add(cid)
        }
        // Remove already-queried CIDs
        targetCids.removeAll(excludeCids)
        if (targetCids.isEmpty()) {
            Log.w(TAG, "OLA TV on-demand: no FR servers found in map")
            return streams
        }
        Log.d(TAG, "OLA TV on-demand: querying ${targetCids.size} servers for '$channelKey': $targetCids, olaTvFrCids=${olaTvFrCids.size}")

        for (cid in targetCids) {
            try {
                val b64 = olaBuildPayload("getToken128910", mapOf("cid" to cid))
                val formBody = FormBody.Builder().add("data", b64).build()
                val req = Request.Builder()
                    .url(OLA_TV_API)
                    .post(formBody)
                    .header("User-Agent", USER_AGENT)
                    .build()

                val response = probeClient.newCall(req).execute()
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    Log.w(TAG, "OLA TV on-demand: cid=$cid empty")
                    continue
                }

                val jsonStr = try {
                    val trimmed = body.trim()
                    if (trimmed.startsWith("[") || trimmed.startsWith("{")) trimmed
                    else olaDecrypt(trimmed)
                } catch (e: Exception) {
                    Log.w(TAG, "OLA TV on-demand: cid=$cid decrypt failed: ${e.message}")
                    continue
                }

                val obj = try { JSONObject(jsonStr) } catch (_: Exception) { null }

                // Extract token1/token2 — could be at root level or inside LIVETV[0]
                val liveTvArr = obj?.optJSONArray("LIVETV")
                val credObj = if (liveTvArr != null && liveTvArr.length() > 0) {
                    val first = liveTvArr.optJSONObject(0)
                    // If LIVETV entries have channel_name → real channel list
                    val hasChannelNames = (0 until liveTvArr.length()).any {
                        liveTvArr.optJSONObject(it)?.optString("channel_name", "")?.isNotBlank() == true
                    }
                    if (hasChannelNames) {
                        // Case 1: LIVETV with named channels → search directly
                        Log.d(TAG, "OLA TV cid=$cid: LIVETV with ${liveTvArr.length()} named channels")
                        for (i in 0 until liveTvArr.length()) {
                            val ch = liveTvArr.optJSONObject(i) ?: continue
                            val chName = ch.optString("channel_name", ch.optString("name", "")).trim()
                            if (chName.isBlank()) continue
                            val cleanName = chName
                                .replace(Regex("^(FR|ES|PT|EN|DE|IT|AR|TR|NL|PL|RO|US|UK|BE|CH)[|:\\s]+", RegexOption.IGNORE_CASE), "")
                                .replace(Regex("[\\s|/]*(HD|SD|FHD|UHD|4K|RAW|HEVC|H265|PPV)[\\s/]*", RegexOption.IGNORE_CASE), " ")
                                .replace(Regex("\\s+"), " ").trim()
                            if (norm(cleanName) != channelKey) continue
                            val ct1 = ch.optString("token1", ch.optString("url", "")).trim()
                            val streamUrl = if (ct1.startsWith("http")) ct1 else olaDecodeToken(ct1) ?: continue
                            val label = chName
                                .replace(Regex("^(FR|ES|PT|EN|DE|IT|AR|TR|NL|PL|RO|US|UK|BE|CH)[|:\\s]+", RegexOption.IGNORE_CASE), "")
                                .trim()
                            if (streams.none { it.url == streamUrl }) {
                                Log.d(TAG, "OLA TV LIVETV match: '$label' → ${streamUrl.take(80)}")
                                streams.add(OlaStream(label, streamUrl))
                            }
                        }
                        continue
                    }
                    // No channel names → LIVETV[0] contains server credentials
                    first
                } else obj

                // Case 2: token1/token2 at root or LIVETV[0] → MAC portal server
                val t1 = credObj?.optString("token1", "") ?: ""
                val t2 = credObj?.optString("token2", "") ?: ""
                if (t1.isNotBlank() && t2.isNotBlank()) {
                    val serverUrl = olaDecodeToken(t1)
                    val mac = olaDecodeToken2(t2)
                    if (serverUrl != null && mac != null) {
                        Log.d(TAG, "OLA TV cid=$cid: MAC portal → ${serverUrl.take(60)}, mac=$mac")
                        val portalStreams = olaFetchMacPortal(serverUrl, mac, channelKey)
                        streams.addAll(portalStreams)
                    } else {
                        Log.w(TAG, "OLA TV cid=$cid: couldn't decode tokens (url=${serverUrl != null}, mac=${mac != null})")
                    }
                    continue
                }

                Log.w(TAG, "OLA TV cid=$cid: unknown response format, ${jsonStr.take(200)}")
            } catch (e: Exception) {
                Log.e(TAG, "OLA TV on-demand: cid=$cid failed: ${e.message}")
            }
        }
        Log.d(TAG, "OLA TV on-demand: found ${streams.size} streams for '$channelKey'")
        return streams
    }

    /** Decode OLA TV token1 to extract stream URL.
     *  token1 is Base64-encoded: first 12 bytes are metadata, rest is the URL. */
    private fun olaDecodeToken(token: String): String? {
        return try {
            val raw = android.util.Base64.decode(token, android.util.Base64.DEFAULT)
            // Find "http" in the decoded bytes
            val text = String(raw, Charsets.ISO_8859_1)
            val idx = text.indexOf("http")
            if (idx >= 0) text.substring(idx).trim(' ', ' ')
            else null
        } catch (_: Exception) { null }
    }

    /** Decode full token1 with metadata prefix for diagnostics. */
    private fun olaDecodeTokenFull(token: String): Triple<String, String, Int>? {
        return try {
            val raw = android.util.Base64.decode(token, android.util.Base64.DEFAULT)
            val text = String(raw, Charsets.ISO_8859_1)
            val idx = text.indexOf("http")
            if (idx >= 0) {
                val prefix = raw.take(idx).joinToString("") { "%02x".format(it) }
                val url = text.substring(idx).trim(' ', ' ')
                Triple(prefix, url, raw.size)
            } else null
        } catch (_: Exception) { null }
    }

    /** Background scan: probe OLA TV servers to find ones with French genres.
     *  Runs after registry load, populates olaTvFrCids for use as fallback servers.
     *  Strategy:
     *  1. Skip the primary server (already known FR)
     *  2. For each server: getToken128910 → decode tokens → handshake → get_genres
     *  3. If genres contain FR entries, add cid to olaTvFrCids
     *  4. **STOP as soon as 20 FR servers are found** — no need to scan all 1000+
     *  5. Parallelism capped at 5 concurrent probes to avoid API rate limiting */
    private const val MAX_FR_SERVERS = 20

    /** Shared thread pool for OLA TV scanning — 2026-05-22 : 15 threads (était 5, trop lent) */
    private val scanExecutor = java.util.concurrent.Executors.newFixedThreadPool(15)

    private fun scanOlaTvFrServers() {
        if (olaTvFrScanDone) return
        val t0 = System.currentTimeMillis()

        // Skip primary server (already known FR) and build probe list
        val primaryCid = olaTvServerMap.entries.find { it.value == OLA_TV_PRIMARY_FR }?.key
        // Skip CIDs déjà connus FR (persistés) ET CIDs bannis (non-FR, TTL 3j)
        val alreadyKnown = olaTvFrCids.toSet()
        val banned = olaTvBannedCids.toSet()
        val cidsToProbe = olaTvServerMap.keys.filter { it != primaryCid && it !in alreadyKnown && it !in banned }.toList()

        Log.d(TAG, "OLA TV FR scan: starting — ${olaTvServerMap.size} total, ${alreadyKnown.size} known FR, ${banned.size} banned, ${cidsToProbe.size} to probe (stop at $MAX_FR_SERVERS)")

        if (cidsToProbe.isEmpty()) {
            olaTvFrScanDone = true
            Log.d(TAG, "OLA TV FR scan: nothing to probe")
            return
        }

        val semaphore = java.util.concurrent.Semaphore(15)
        val latch = java.util.concurrent.CountDownLatch(cidsToProbe.size)
        val probedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val bannedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val earlyStop = java.util.concurrent.atomic.AtomicBoolean(false)

        for (cid in cidsToProbe) {
            scanExecutor.submit {
                try {
                    if (earlyStop.get()) return@submit
                    semaphore.acquire()
                    if (earlyStop.get()) { semaphore.release(); return@submit }
                    val hasFr = probeServerForFr(cid)
                    if (hasFr) {
                        olaTvFrCids.add(cid)
                        val total = olaTvFrCids.size
                        val name = olaTvServerMap[cid] ?: "?"
                        Log.d(TAG, "OLA TV FR scan: ✓ FR cid=$cid name='$name' (total=$total)")
                        if (total >= MAX_FR_SERVERS) {
                            Log.d(TAG, "OLA TV FR scan: reached $MAX_FR_SERVERS FR servers, stopping early")
                            earlyStop.set(true)
                        }
                    } else {
                        // 2026-05-22 : BAN ce CID — pas de FR, skip au prochain scan
                        olaTvBannedCids.addIfAbsent(cid)
                        bannedCount.incrementAndGet()
                    }
                    val p = probedCount.incrementAndGet()
                    if (p % 50 == 0) {
                        Log.d(TAG, "OLA TV FR scan: progress $p/${cidsToProbe.size}, found ${olaTvFrCids.size} FR, banned ${bannedCount.get()}")
                    }
                } catch (e: Exception) {
                    // Silent fail — on ne ban pas en cas d'erreur réseau (retry au prochain scan)
                } finally {
                    semaphore.release()
                    latch.countDown()
                }
            }
        }

        // Wait for all probes (with overall timeout of 3 minutes, or early stop)
        latch.await(3, TimeUnit.MINUTES)

        olaTvFrScanDone = true
        // Persister FR + bannis
        persistFrCids()
        persistBannedCids()
        val elapsed = System.currentTimeMillis() - t0
        Log.d(TAG, "OLA TV FR scan: DONE in ${elapsed / 1000}s — probed ${probedCount.get()}, found ${olaTvFrCids.size} FR, banned ${olaTvBannedCids.size} non-FR${if (earlyStop.get()) " (early stop)" else ""}")
        if (olaTvFrCids.isNotEmpty()) {
            Log.d(TAG, "OLA TV FR cids: ${olaTvFrCids.joinToString(", ") { "$it(${olaTvServerMap[it]})" }}")
        }
    }

    /** Probe a single OLA TV server to check if it has French genres.
     *  Returns true if the MAC portal has genres containing "fr|", "france", "french", "français". */
    private fun probeServerForFr(cid: String): Boolean {
        try {
            // Step 1: Get server credentials
            val b64 = olaBuildPayload("getToken128910", mapOf("cid" to cid))
            val formBody = FormBody.Builder().add("data", b64).build()
            val req = Request.Builder()
                .url(OLA_TV_API)
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = probeClient.newCall(req).execute()
            val body = response.body?.string()
            if (body.isNullOrBlank()) return false

            val jsonStr = try {
                val trimmed = body.trim()
                if (trimmed.startsWith("[") || trimmed.startsWith("{")) trimmed
                else olaDecrypt(trimmed)
            } catch (_: Exception) { return false }

            val obj = try { JSONObject(jsonStr) } catch (_: Exception) { return false }

            // Extract token1/token2
            val liveTvArr = obj.optJSONArray("LIVETV")
            val credObj = if (liveTvArr != null && liveTvArr.length() > 0) {
                val first = liveTvArr.optJSONObject(0)
                val hasChannelNames = (0 until liveTvArr.length()).any {
                    liveTvArr.optJSONObject(it)?.optString("channel_name", "")?.isNotBlank() == true
                }
                if (hasChannelNames) {
                    // LIVETV with named channels — check if any look French
                    for (i in 0 until liveTvArr.length()) {
                        val ch = liveTvArr.optJSONObject(i) ?: continue
                        val chName = ch.optString("channel_name", ch.optString("name", "")).lowercase()
                        if (chName.startsWith("fr|") || chName.startsWith("fr ") || chName.contains("france")
                            || chName.contains("tf1") || chName.contains("m6 ") || chName.contains("canal+")
                            || chName.contains("bein")) {
                            return true
                        }
                    }
                    return false
                }
                first
            } else obj

            val t1 = credObj?.optString("token1", "") ?: ""
            val t2 = credObj?.optString("token2", "") ?: ""
            if (t1.isBlank() || t2.isBlank()) return false

            val serverUrl = olaDecodeToken(t1) ?: return false
            val mac = olaDecodeToken2(t2) ?: return false

            // Step 2: Handshake to get auth token
            val encodedMac = java.net.URLEncoder.encode(mac, "UTF-8")
            val portalBase = "${serverUrl.trimEnd('/')}/portal.php"
            val cookie = "mac=$encodedMac; stb_lang=en; timezone=Europe%2FLondon"
            val stbUA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

            val hsReq = Request.Builder()
                .url("$portalBase?type=stb&action=handshake&token=&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA)
                .header("Cookie", cookie)
                .build()
            val hsResp = probeClient.newCall(hsReq).execute()
            val hsBody = hsResp.body?.string() ?: ""

            val token = try {
                JSONObject(hsBody).getJSONObject("js").getString("token")
            } catch (_: Exception) { return false }

            // Step 3: Get profile (init session)
            val profReq = Request.Builder()
                .url("$portalBase?type=stb&action=get_profile&auth_token=$token&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie)
                .header("Authorization", "Bearer $token").build()
            probeClient.newCall(profReq).execute().body?.close()

            // Step 4: Get genres and check for FR
            val genreReq = Request.Builder()
                .url("$portalBase?type=itv&action=get_genres&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie)
                .header("Authorization", "Bearer $token").build()
            val genreResp = probeClient.newCall(genreReq).execute()
            val genreBody = genreResp.body?.string() ?: ""

            val genreArr = try {
                JSONObject(genreBody).optJSONArray("js") ?: return false
            } catch (_: Exception) { return false }

            for (g in 0 until genreArr.length()) {
                val genre = genreArr.optJSONObject(g) ?: continue
                val gTitle = genre.optString("title", "").lowercase()
                if (gTitle.startsWith("fr|") || gTitle.startsWith("fr ")
                    || gTitle.contains("france") || gTitle.contains("french")
                    || gTitle.contains("français")) {
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /** Phase 2: Just cache the OLA TV server list (cid → category_name).
     *  This is lightweight (~2s). Actual channel fetching happens on-demand in getServers(). */
    private fun parseOlaTvServerList() {
        try {
            val b64 = olaBuildPayload("newolatvcategory0326")
            val formBody = FormBody.Builder().add("data", b64).build()
            val req = Request.Builder()
                .url(OLA_TV_API)
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = probeClient.newCall(req).execute()
            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                Log.e(TAG, "OLA TV servers: empty response (code=${response.code})")
                return
            }

            val jsonStr = try {
                val trimmed = body.trim()
                if (trimmed.startsWith("[") || trimmed.startsWith("{")) trimmed
                else olaDecrypt(trimmed)
            } catch (e: Exception) {
                Log.e(TAG, "OLA TV servers: decrypt failed: ${e.message}")
                return
            }

            val arr = try { JSONArray(jsonStr) } catch (_: Exception) {
                try {
                    val obj = JSONObject(jsonStr)
                    obj.optJSONArray("LIVETV") ?: obj.optJSONArray("data") ?: return
                } catch (_: Exception) { return }
            }

            val map = mutableMapOf<String, String>()
            // Log first entry's full structure for diagnostics
            if (arr.length() > 0) {
                val first = arr.optJSONObject(0)
                Log.d(TAG, "OLA TV server[0] keys: ${first?.keys()?.asSequence()?.toList()}")
                Log.d(TAG, "OLA TV server[0] full: ${first?.toString()?.take(500)}")
            }
            val frenchEntries = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val srv = arr.optJSONObject(i) ?: continue
                val cid = srv.optString("cid", "").trim()
                val catName = srv.optString("category_name", "").trim()
                if (cid.isNotBlank() && catName.isNotBlank()) {
                    map[cid] = catName
                    // Log entries that look French
                    val lower = catName.lowercase()
                    if (lower.contains("france") || lower.contains("tf1") || lower.contains("bein")
                        || lower.contains("canal") || lower.contains("m6") || lower.contains("rmc")
                        || lower == "2020" || lower == "2000") {
                        frenchEntries.add("$cid=$catName")
                    }
                }
            }
            olaTvServerMap = map
            Log.d(TAG, "OLA TV: cached ${map.size} servers")
            Log.d(TAG, "OLA TV FR-related entries: $frenchEntries")
        } catch (e: Exception) {
            Log.e(TAG, "OLA TV servers: EXCEPTION: ${e.message}", e)
        }
    }


    /** Convert a registry entry into a TvShow card. */
    private fun toTvShow(key: String, info: ChannelInfo): TvShow {
        return TvShow(
            id = "ch::$key",
            title = info.displayName,
            poster = info.logo.ifBlank { null },
            providerName = name,
        )
    }

    // ───────── Sport events from sportsonline.vc ─────────

    private suspend fun loadSportEvents(): List<TvShow> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(SPORTS_PROG_URL)
                .header("User-Agent", USER_AGENT).build()
            val text = client.newCall(req).execute().body?.string() ?: return@withContext emptyList()
            text.lines()
                .filter { it.contains("|") && it.contains("http") }
                .take(20)
                .mapNotNull { line ->
                    val parts = line.split("|", limit = 2)
                    if (parts.size < 2) return@mapNotNull null
                    val event = parts[0].trim()
                    val url = parts[1].trim()
                    if (event.isBlank() || url.isBlank()) return@mapNotNull null
                    TvShow(
                        id = "sport::$url",
                        title = event,
                        poster = null,
                        providerName = name,
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "SportOnline error", e)
            emptyList()
        }
    }

    // ═══════════════════════ Provider API ═══════════════════════

    override suspend fun getHome(): List<Category> = try {
        ensureRegistry()
        // V2 : warm-up BLOQUANT — l'user voit le spinner de chargement
        // pendant que OLA + OTF sont scannés et les streams pré-cachés.
        // Séparé du registre pour toujours s'exécuter (même si registre en cache).
        ensureWarmUp()
        val sections = mutableListOf<Category>()

        // Snapshot to avoid ConcurrentModificationException (Phase 2 modifies registry in background)
        val rawSnapshot = synchronized(registryLock) { LinkedHashMap(channelRegistry) }
        // 2026-05-08 : filtre les chaînes bannies (cross-provider via channelKey
        // normalisé) pour éviter qu'elles repeuplent le home après scan.
        val snapshot = LinkedHashMap(rawSnapshot.filter { (key, _) ->
            !com.streamflixreborn.streamflix.fragments.player.settings
                .IptvBannedChannels.isBanned("ch::$key")
        })

        // ─── ★ Favoris EN TÊTE ───
        // 2026-05-10 (user) : "applique le fix favoris Vavoo aux autres providers
        // aussi, qu'ils en bénéficient". → réintroduction de la section Favoris
        // que l'IptvFavorites(Tv|Mobile)Fragment cherche dans getHome() pour
        // peupler l'onglet ❤. (TV Hub continue d'agréger en parallèle.)
        try {
            val favKeys = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                .getAllCanonicalFavorites()
            if (favKeys.isNotEmpty()) {
                val favItems = snapshot
                    .filter { (key, info) -> key in favKeys && info.hasServer() }
                    .map { (k, v) -> toTvShow(k, v) }
                if (favItems.isNotEmpty()) {
                    sections.add(Category(name = "Favoris", list = favItems))
                }
            }
        } catch (_: Throwable) { }

        val selectedGroup = getSelectedGroup()

        if (selectedGroup.isBlank()) {
            // ═══ Mode FRANCE (défaut) : catalogue hardcodé, tri TNT ═══
            for (catName in homeCategories) {
                val items = sortByTnt(snapshot
                    .filter { it.value.category == catName }
                    .map { (k, v) -> toTvShow(k, v) })
                if (items.isNotEmpty()) sections.add(Category(name = catName, list = items))

                // Insert sport events right after Musique
                if (catName == "Musique") {
                    try {
                        val sports = loadSportEvents()
                        if (sports.isNotEmpty()) sections.add(Category(name = "Sport en Direct", list = sports))
                    } catch (_: Exception) {}
                }
            }
        } else {
            // ═══ Mode OTF (autre groupe sélectionné) : chaînes OTF de ce groupe ═══
            try {
                withContext(Dispatchers.IO) { ensureOtfCache() }
                val groupChannels = otfChannelsByGroup[selectedGroup]
                if (!groupChannels.isNullOrEmpty()) {
                    val items = groupChannels.map { entry ->
                        TvShow(
                            id = "ch::${entry.normKey}",
                            title = entry.displayName,
                            poster = "",
                            banner = "",
                        )
                    }
                    sections.add(Category(name = selectedGroup, list = items))
                } else {
                    Log.w(TAG, "getHome: OTF group '$selectedGroup' empty or not found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "getHome OTF group error", e)
            }
        }

        // 2026-05-08 : section "✕ Chaînes bannies" EN BAS du home.
        // L'user a demandé un dossier fixe en bas pour ranger les chaînes bannies
        // (au lieu de les cacher complètement). Ça permet de les débannir facilement
        // via long-press → menu. Source = rawSnapshot (avant filtre ban).
        try {
            val bannedKeys = com.streamflixreborn.streamflix.fragments.player.settings
                .IptvBannedChannels.getAllBannedKeys()
            if (bannedKeys.isNotEmpty()) {
                val bannedItems = mutableListOf<TvShow>()
                for ((key, info) in rawSnapshot) {
                    if (!info.hasServer()) continue
                    // normalize matching (cross-provider) : la WiTV key seule peut suffire
                    val normalizedKey = key.lowercase().trim()
                    if (bannedKeys.contains(normalizedKey)) {
                        bannedItems += toTvShow(key, info)
                    }
                }
                if (bannedItems.isNotEmpty()) {
                    sections.add(Category(name = "✕ Chaînes bannies", list = sortByTnt(bannedItems)))
                }
            }
        } catch (_: Throwable) { }

        sections
    } catch (e: Exception) {
        Log.e(TAG, "getHome error", e)
        emptyList()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = try {
        ensureRegistry()
        val snapshot = synchronized(registryLock) { LinkedHashMap(channelRegistry) }
        snapshot
            .filter { it.value.displayName.contains(query, true) && it.value.hasServer() }
            .map { (k, v) -> toTvShow(k, v) }
    } catch (_: Exception) {
        emptyList()
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        if (page > 1) emptyList()
        else try {
            ensureRegistry()
            val snapshot = synchronized(registryLock) { LinkedHashMap(channelRegistry) }
            sortByTnt(snapshot.filter { it.value.hasServer() }.map { (k, v) -> toTvShow(k, v) })
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun getMovie(id: String): Movie = throw Exception("Not supported")

    override suspend fun getTvShow(id: String): TvShow = try {
        ensureRegistry()

        if (id.startsWith("sport::")) {
            val url = id.removePrefix("sport::")
            TvShow(
                id = id, title = "Sport en Direct",
                overview = "Événement sportif en direct",
                seasons = listOf(
                    Season(
                        id = id, number = 1, title = "En Direct",
                        episodes = listOf(Episode(id = id, number = 1, title = "Regarder"))
                    )
                ),
                providerName = name,
            )
        } else {
            val key = id.removePrefix("ch::")
            val info = channelRegistry[key]
            if (info != null) {
                var overview = "En direct"
                // Fetch details from WiTV page if available
                info.sources["WiTV"]?.let { witvUrl ->
                    try {
                        val doc = service.getPage(witvUrl)
                        val article = doc.selectFirst("article") ?: doc
                        overview = article.selectFirst(
                            ".ann-detal .dle-text p, .tabs-content p"
                        )?.text()?.trim() ?: "En direct"
                    } catch (_: Exception) {}
                }

                val recs = channelRegistry
                    .filter { it.value.category == info.category && it.key != key }
                    .entries.take(10)
                    .map { (k, v) -> toTvShow(k, v) as Show }

                val logo = info.logo.ifBlank { null }
                TvShow(
                    id = id,
                    title = info.displayName,
                    overview = overview,
                    poster = logo,
                    banner = logo,
                    seasons = listOf(
                        Season(
                            id = id, number = 1, title = "En Direct",
                            episodes = listOf(
                                Episode(id = id, number = 1, title = "Regarder en Direct", poster = logo)
                            )
                        )
                    ),
                    recommendations = recs,
                    providerName = name,
                )
            } else {
                TvShow(id, "Chaîne inconnue", providerName = name)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "getTvShow error", e)
        TvShow(id, "Erreur", providerName = name)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> =
        listOf(Episode(id = seasonId, number = 1, title = "Signal en Direct"))

    override suspend fun getGenre(id: String, page: Int): Genre = throw Exception("Not supported")
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")

    // ───────── Servers: one per source / iframe ─────────

    // v71 (user "passage mini→fullscreen WiTV galère") :
    //   Cache des résultats getServers par id. Mini player et fullscreen
    //   PlayerViewModel.init appellent tous les deux getServers → scan OLA
    //   exécuté 2× (~5-10s × 2). Cache 60s = scan du Mini réutilisé par le
    //   fullscreen → transfert instant.
    private data class CachedServers(val servers: List<Video.Server>, val expiresAtMs: Long)
    private val getServersCache = java.util.concurrent.ConcurrentHashMap<String, CachedServers>()
    private val GET_SERVERS_TTL_MS = 60_000L

    // 2026-05-22 V2 : cache OLA streams par chaîne (TTL 2h)
    // Évite de re-faire les 5-10 requêtes MAC portal à chaque clic sur la même chaîne.
    // Les tokens IPTV durent en général quelques heures → 2h est safe.
    private data class CachedOlaStreams(val streams: List<OlaStream>, val expiresAtMs: Long)
    private val olaStreamCache = java.util.concurrent.ConcurrentHashMap<String, CachedOlaStreams>()
    private val OLA_STREAM_CACHE_TTL = 2 * 60 * 60 * 1000L // 2 heures

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        // v71 : check cache (TTL 60s)
        getServersCache[id]?.let { cached ->
            if (System.currentTimeMillis() < cached.expiresAtMs) {
                Log.d(TAG, "▶ getServers id=$id → cache HIT (${cached.servers.size} servers)")
                return cached.servers
            } else {
                getServersCache.remove(id)
            }
        }
        return getServersInternal(id, videoType)
    }

    private suspend fun getServersInternal(id: String, videoType: Video.Type): List<Video.Server> = try {
        val servers = mutableListOf<Video.Server>()
        Log.d(TAG, "▶ getServers id=$id")

        if (id.startsWith("sport::")) {
            val url = id.removePrefix("sport::")
            Log.d(TAG, "  sport event → $url")
            servers.add(Video.Server(url, "SportOnline"))
        } else {
            ensureRegistry()
            val key = id.removePrefix("ch::")
            val info = channelRegistry[key]
            Log.d(TAG, "  channel key=$key, sources=${info?.sources?.keys}")

            if (info != null) {
                Log.d(TAG, "  channel found: ${info.displayName}, cat=${info.category}")

                // ── Priority 1: OLA TV ──
                // 2026-05-22 V2 : cache OLA streams 2h + parallélisme 10 CIDs
                Log.d(TAG, "OLA TV: starting for key=$key, olaTvFrCids=${olaTvFrCids.size}, cacheEntries=${olaStreamCache.size}")

                // V2 fix : émettre les streams OLA via _additionalServers (→ onglet Chaîne)
                // au format "OLA[key] label" reconnu par PlayerTvFragment/PlayerMobileFragment.
                // getServers retourne 0 → le player attend l'émission OLA pour auto-play.
                val olaCached = olaStreamCache[key]
                val olaCacheHit = olaCached != null && System.currentTimeMillis() < olaCached.expiresAtMs && olaCached.streams.isNotEmpty()
                Log.d(TAG, "OLA TV: olaCacheHit=$olaCacheHit (cached=${olaCached?.streams?.size ?: 0}, expired=${olaCached?.let { System.currentTimeMillis() >= it.expiresAtMs } ?: false})")
                if (olaCacheHit) {
                    Log.d(TAG, "OLA TV: cache SYNC HIT for '$key' → ${olaCached!!.streams.size} streams → émission Chaîne (PREFETCH OK)")
                    // Cache = déjà probé au scan → émettre DIRECT sans re-probe (vitesse max).
                    // Si un stream est mort entre-temps, tryNextChannelVariant switche auto.
                    olaCached.streams.forEachIndexed { idx, ola ->
                        UserPreferences.unmarkChannelFailed("ch::$key")
                        val label = ola.label.ifBlank { "Chaîne ${idx + 1}" }
                        _additionalServers.emit(Video.Server("m3u8::${ola.url}", "OLA[$key] $label"))
                        Log.d(TAG, "  ✓ OLA émis Chaîne: $label")
                    }
                }

                // 2026-05-23 COURSE : lance TOUS les CIDs connus en parallèle.
                // Dès qu'UN CID répond → émet le stream au player = lecture instantanée.
                // Plus de awaitAll() qui attend le CID le plus lent du batch.
                if (!olaCacheHit) {
                    val firebaseCids = IptvChannelIndexService.getKnownCids(key)
                    val localCids = getKnownCids(key)
                    var knownCids = (firebaseCids + localCids).toList().distinct()
                    // Fallback : si aucun CID connu pour cette chaîne, utiliser les CIDs FR du warm-up
                    if (knownCids.isEmpty() && olaTvFrCids.isNotEmpty()) {
                        knownCids = olaTvFrCids.take(15).toList()
                        Log.d(TAG, "OLA TV: cache MISS for '$key' → COURSE FALLBACK ${knownCids.size} FR CIDs (pas d'index pour ce channel)")
                    }
                    if (knownCids.isNotEmpty()) {
                        Log.d(TAG, "OLA TV: cache MISS for '$key' → COURSE ${knownCids.size} CIDs en parallèle")
                        val syncT0 = System.currentTimeMillis()
                        val syncTimeout = 10_000L
                        val syncStreams = mutableListOf<OlaStream>()
                        val syncUrls = mutableSetOf<String>()

                        kotlinx.coroutines.coroutineScope {
                            val raceChannel = kotlinx.coroutines.channels.Channel<OlaStream>(kotlinx.coroutines.channels.Channel.UNLIMITED)

                            // Lancer TOUS les CIDs en parallèle (max 10)
                            val raceJobs = knownCids.take(10).map { cid ->
                                async(Dispatchers.IO) {
                                    try {
                                        val streams = olaFetchSingleServer(cid, key)
                                        for (ola in streams) raceChannel.send(ola)
                                    } catch (_: Exception) {}
                                }
                            }
                            // Fermer le channel quand tous les jobs sont finis
                            launch {
                                raceJobs.forEach { it.join() }
                                raceChannel.close()
                            }

                            // Collecter les résultats au fur et à mesure (le 1er arrivé = le 1er joué)
                            try {
                                kotlinx.coroutines.withTimeoutOrNull(syncTimeout) {
                                    for (ola in raceChannel) {
                                        if (syncUrls.add(ola.url)) {
                                            syncStreams.add(ola)
                                            // Émettre IMMÉDIATEMENT chaque stream trouvé
                                            UserPreferences.unmarkChannelFailed("ch::$key")
                                            val label = ola.label.ifBlank { "Chaîne ${syncStreams.size}" }
                                            _additionalServers.emit(Video.Server("m3u8::${ola.url}", "OLA[$key] $label"))
                                            Log.d(TAG, "  ✓ OLA COURSE émis: $label (+${System.currentTimeMillis() - syncT0}ms)")
                                        }
                                        if (syncStreams.size >= 3) break
                                    }
                                }
                            } catch (_: Exception) {}

                            // Annuler les jobs restants (on a assez de streams)
                            raceJobs.forEach { it.cancel() }
                        }

                        if (syncStreams.isNotEmpty()) {
                            olaStreamCache[key] = CachedOlaStreams(syncStreams, System.currentTimeMillis() + OLA_STREAM_CACHE_TTL)
                            Log.d(TAG, "OLA TV: COURSE '$key' → ${syncStreams.size} streams en ${System.currentTimeMillis() - syncT0}ms")
                        } else {
                            Log.d(TAG, "OLA TV: COURSE '$key' → 0 streams (${System.currentTimeMillis() - syncT0}ms)")
                        }
                    }
                }

                // Progressive : cherche de NOUVEAUX streams OLA en fond
                // 2026-05-22 FIX : lancer le scan async MÊME quand le cache a quelques streams
                // (avant : skip complet si cache avait 1-2 streams → user passait de 15 serveurs à 1)
                val cachedStreamCount = olaStreamCache[key]?.streams?.size ?: 0
                val maxOlaStreams = 8
                if (!olaCacheHit || cachedStreamCount < maxOlaStreams) {
                currentOlaJob?.cancel()
                currentOlaJob = olaScope.launch {
                    // Si le cache est PLEIN (≥ maxOlaStreams) → rien à chercher de plus
                    val cached = olaStreamCache[key]
                    if (cached != null && System.currentTimeMillis() < cached.expiresAtMs && cached.streams.size >= maxOlaStreams) {
                        Log.d(TAG, "OLA TV: cache FULL for '$key' → ${cached.streams.size} streams (async skip)")
                        return@launch
                    }

                    val queriedCids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val emittedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val allOlaStreams = java.util.concurrent.CopyOnWriteArrayList<OlaStream>()
                    // Pré-remplir avec les streams du cache pour éviter les doublons
                    if (cached != null && cached.streams.isNotEmpty()) {
                        for (ola in cached.streams) {
                            emittedUrls.add(ola.url)
                            allOlaStreams.add(ola)
                        }
                        Log.d(TAG, "OLA TV: pre-seeded ${cached.streams.size} cached streams, scanning for more (max $maxOlaStreams)")
                    }
                    // V2 optimisé : 5 en parallèle max (Chromecast = 2Go RAM, CPU faible)
                    val parallelism = 5
                    val emitMutex = kotlinx.coroutines.sync.Mutex()

                    suspend fun querySingleCid(cid: String) {
                        if (emittedUrls.size >= maxOlaStreams) return
                        if (!queriedCids.add(cid)) return
                        try {
                            val streams = olaFetchSingleServer(cid, key)
                            for (ola in streams) {
                                if (emittedUrls.size >= maxOlaStreams) return
                                if (emittedUrls.add(ola.url)) {
                                    allOlaStreams.add(ola)
                                    val num = allOlaStreams.size
                                    val label = ola.label.ifBlank { "Chaîne $num" }
                                    emitMutex.withLock {
                                        UserPreferences.unmarkChannelFailed("ch::$key")
                                        _additionalServers.emit(Video.Server("m3u8::${ola.url}", "OLA[$key] $label"))
                                    }
                                    Log.d(TAG, "OLA TV async émis Chaîne: $label (cid=$cid)")
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "OLA TV cid=$cid query failed: ${e.message}")
                        }
                    }

                    // Query known FR servers in parallel batches
                    suspend fun queryBatch(cids: List<String>): Boolean {
                        val pending = cids.filter { it !in queriedCids }
                        if (pending.isEmpty()) return false
                        pending.chunked(parallelism).forEach { batch ->
                            if (emittedUrls.size >= maxOlaStreams) return true
                            kotlinx.coroutines.coroutineScope {
                                batch.map { cid -> async { querySingleCid(cid) } }.awaitAll()
                            }
                        }
                        return emittedUrls.size >= maxOlaStreams
                    }

                    // 2026-05-22 : FIREBASE INDEX COMMUNAUTAIRE — CIDs connus par TOUS les users
                    val firebaseCids = IptvChannelIndexService.getKnownCids(key)
                    if (firebaseCids.isNotEmpty()) {
                        Log.d(TAG, "OLA TV: FIREBASE INDEX HIT for '$key' → ${firebaseCids.size} CIDs: $firebaseCids")
                        if (queryBatch(firebaseCids.toList())) {
                            olaStreamCache[key] = CachedOlaStreams(allOlaStreams.toList(), System.currentTimeMillis() + OLA_STREAM_CACHE_TTL)
                            persistChannelIndex()
                            Log.d(TAG, "OLA TV: limit reached via FIREBASE for '$key', cached ${allOlaStreams.size}")
                            return@launch
                        }
                    }

                    // INDEX LOCAL — CIDs découverts par CET appareil (complète Firebase)
                    val localCids = getKnownCids(key)
                    if (localCids.isNotEmpty()) {
                        val extraLocal = localCids.filter { it !in firebaseCids }
                        if (extraLocal.isNotEmpty()) {
                            Log.d(TAG, "OLA TV: LOCAL INDEX extra for '$key' → ${extraLocal.size} CIDs")
                            if (queryBatch(extraLocal)) {
                                olaStreamCache[key] = CachedOlaStreams(allOlaStreams.toList(), System.currentTimeMillis() + OLA_STREAM_CACHE_TTL)
                                persistChannelIndex()
                                return@launch
                            }
                        }
                    }

                    // Primary server (si pas déjà dans l'index)
                    val primaryCid = olaTvServerMap.entries.find { it.value == OLA_TV_PRIMARY_FR }?.key
                    if (primaryCid != null) {
                        querySingleCid(primaryCid)
                        if (emittedUrls.size >= maxOlaStreams) {
                            olaStreamCache[key] = CachedOlaStreams(allOlaStreams.toList(), System.currentTimeMillis() + OLA_STREAM_CACHE_TTL)
                            persistChannelIndex()
                            Log.d(TAG, "OLA TV: limit reached after primary for '$key', cached ${allOlaStreams.size}")
                            return@launch
                        }
                    }

                    // Remaining FR servers
                    if (queryBatch(olaTvFrCids.toList())) {
                        olaStreamCache[key] = CachedOlaStreams(allOlaStreams.toList(), System.currentTimeMillis() + OLA_STREAM_CACHE_TTL)
                        persistChannelIndex()
                        Log.d(TAG, "OLA TV: limit $maxOlaStreams reached for '$key', cached ${allOlaStreams.size}")
                        return@launch
                    }

                    // Keep polling for new FR servers from background scan
                    while (!olaTvFrScanDone) {
                        delay(1500)
                        if (queryBatch(olaTvFrCids.toList())) {
                            olaStreamCache[key] = CachedOlaStreams(allOlaStreams.toList(), System.currentTimeMillis() + OLA_STREAM_CACHE_TTL)
                            Log.d(TAG, "OLA TV: limit $maxOlaStreams reached for '$key', cached ${allOlaStreams.size}")
                            return@launch
                        }
                    }

                    // Final pass
                    queryBatch(olaTvFrCids.toList())
                    // V2 : persister les streams trouvés (cache 2h)
                    if (allOlaStreams.isNotEmpty()) {
                        olaStreamCache[key] = CachedOlaStreams(allOlaStreams.toList(), System.currentTimeMillis() + OLA_STREAM_CACHE_TTL)
                    }
                    // 2026-05-22 : persister l'index chaîne→CIDs après chaque session de fetch
                    persistChannelIndex()
                    Log.d(TAG, "OLA TV: done for '$key', emitted ${emittedUrls.size} streams from ${queriedCids.size} CIDs, cached ${allOlaStreams.size}, index=${olaChannelIndex[key]?.size ?: 0} CIDs")
                }
                } // end if (!olaCacheHit)

                // ── Priority 2: OTF TV (lazy backup — fetched on first call, cached) ──
                // Émet vers onglet Chaîne (format OLA[key]) — fallback auto si mort
                try {
                    val otfUrls = withContext(Dispatchers.IO) { ensureOtfCache()[key] }
                    if (!otfUrls.isNullOrEmpty()) {
                        for ((idx, url) in otfUrls.withIndex()) {
                            _additionalServers.emit(Video.Server("m3u8::$url", "OLA[$key] OTF #${idx + 1}"))
                            Log.d(TAG, "  ✓ OTF émis Chaîne: OTF #${idx + 1}")
                        }
                    } else {
                        Log.d(TAG, "  OTF: no match for key='$key'")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "  OTF backup error", e)
                }
            } else {
                Log.w(TAG, "  channel not found in registry")
            }
        }

        // 2026-05-22 : PRÉ-VALIDATION rapide des sources IPTV
        // Au lieu d'envoyer 5 serveurs au player et attendre qu'il plante sur les morts,
        // on teste chaque URL m3u8 en parallèle (GET 3s timeout). Les vivantes passent
        // en premier, les mortes sont retirées. L'user voit la chaîne se lancer du 1er coup.
        if (servers.isNotEmpty()) {
            val validated = mutableListOf<Video.Server>()
            val probeTimeout = 3_000L
            try {
                kotlinx.coroutines.coroutineScope {
                    val probeResults = servers.map { srv ->
                        async(Dispatchers.IO) {
                            val url = srv.id.removePrefix("m3u8::").split("||referer=", "||ua=")[0]
                            if (!url.startsWith("http")) return@async srv to true // non-m3u8, keep
                            try {
                                val req = Request.Builder().url(url)
                                    .header("User-Agent", USER_AGENT)
                                    .build()
                                val resp = withTimeoutOrNull(probeTimeout) {
                                    withContext(Dispatchers.IO) { probeClient.newCall(req).execute() }
                                }
                                val code = resp?.code ?: 0
                                val alive = code in 200..399
                                resp?.body?.close()
                                if (!alive) Log.d(TAG, "  ✗ probe DEAD: ${srv.name} → HTTP $code")
                                else Log.d(TAG, "  ✓ probe OK: ${srv.name} → HTTP $code")
                                srv to alive
                            } catch (e: Exception) {
                                Log.d(TAG, "  ✗ probe FAIL: ${srv.name} → ${e.message?.take(60)}")
                                srv to false
                            }
                        }
                    }.awaitAll()
                    for ((srv, alive) in probeResults) {
                        if (alive) validated.add(srv)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "probe phase error, keeping all servers: ${e.message}")
                validated.clear()
                validated.addAll(servers)
            }
            servers.clear()
            servers.addAll(validated)
            val deadCount = validated.size.let { servers.size } // servers was cleared
            Log.d(TAG, "getServers -> ${servers.size} servers (probe validated)")
        } else {
            Log.d(TAG, "getServers -> 0 servers")
        }

        // v71 : populate cache pour éviter re-scan dans 60s (mini→fullscreen)
        if (servers.isNotEmpty()) {
            getServersCache[id] = CachedServers(servers.toList(), System.currentTimeMillis() + GET_SERVERS_TTL_MS)
        }
        servers
    } catch (e: Exception) {
        Log.e(TAG, "getServers crash", e)
        emptyList()
    }

    // ───────── Video: iframe-chain resolver ──────────

    private val streamPatterns = listOf(
        Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
        Regex("""source\s*:\s*["']([^"']+)["']"""),
        Regex("""file\s*:\s*["']([^"']+)["']"""),
        Regex("""var\s+src\s*=\s*["']([^"']+)["']"""),
        Regex("""playStream\s*\(\s*["']([^"']+)["']"""),
        Regex("""atob\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)"""),
        Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""),
        Regex("""source\s+src=["']([^"']+)["']"""),
    )

    override suspend fun getVideo(server: Video.Server): Video {
        val originalUrl = server.id
        Log.d(TAG, "getVideo server=${server.name} url=$originalUrl")

        // Direct m3u8 (OLA/OTF streams resolved in getServers)
        if (originalUrl.startsWith("m3u8::")) {
            val parts = originalUrl.removePrefix("m3u8::").split("||referer=", "||ua=")
            val m3u8Original = parts[0]
            val referer = parts.getOrElse(1) { "" }
            val ua = parts.getOrElse(2) { USER_AGENT }
            // 2026-05-09 v25 : MPEG-TS swap pour Xtream-codes URLs (comme TiviMate).
            // Le `m3u8::` est juste notre préfixe interne. L'URL réelle peut être
            // soit HLS (.m3u8) soit MPEG-TS (.ts ou query extension=ts). Si Xtream
            // HLS → swap vers .ts pour stream HTTP continu sans rate-limit segments.
            val m3u8 = if (m3u8Original.contains("/live/") && m3u8Original.endsWith(".m3u8")) {
                m3u8Original.removeSuffix(".m3u8") + ".ts"
            } else {
                m3u8Original
            }
            Log.d(TAG, "  DIRECT M3U8: $m3u8 (MPEG-TS swap if Xtream)")
            val hdrs = mutableMapOf("User-Agent" to ua.ifBlank { USER_AGENT })
            if (referer.isNotBlank()) hdrs["Referer"] = referer
            return Video(m3u8, headers = hdrs)
        }

        for (startUrl in listOf(originalUrl)) {
            var url = startUrl
            var referer = baseUrl
            var depth = 0

            Log.d(TAG, "  trying base URL: $url")

            while (depth < 6) {
                depth++
                try {
                    Log.d(TAG, "  [$depth] fetching: $url (referer=$referer)")
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", referer)
                        .build()
                    val response = client.newCall(req).execute()
                    val html = response.body?.string() ?: ""
                    Log.d(TAG, "  [$depth] response ${response.code}, body ${html.length} chars")

                    // If server error or empty, break to try next URL
                    if (response.code >= 500 || (response.code >= 400 && html.length < 100)) {
                        Log.w(TAG, "  [$depth] bad response ${response.code}, will try next URL")
                        break
                    }

                    // Extract origin from current URL for CORS headers
                    val origin = Regex("""https?://[^/]+""").find(url)?.value ?: ""

                    // Search for stream URL in page
                    for ((i, r) in streamPatterns.withIndex()) {
                        r.find(html)?.let {
                            var stream = it.groupValues[1].replace("\\/", "/")
                            if (i == 5) {
                                try {
                                    stream = String(android.util.Base64.decode(stream, android.util.Base64.DEFAULT))
                                    Log.d(TAG, "  [$depth] decoded atob -> $stream")
                                } catch (_: Exception) {}
                            }
                            if (stream.contains(".m3u8") || stream.contains(".mp4") || stream.startsWith("http")) {
                                Log.d(TAG, "  [$depth] STREAM FOUND (pattern #$i): $stream")
                                val hdrs = mutableMapOf(
                                    "Referer" to url,
                                    "User-Agent" to USER_AGENT,
                                )
                                if (origin.isNotEmpty()) hdrs["Origin"] = origin
                                return Video(stream, headers = hdrs)
                            }
                        }
                    }
                    Log.d(TAG, "  [$depth] no stream pattern matched in raw HTML")

                    // Try JsUnpacker for eval(function(p,a,c,k,e,...)) packed JS
                    val evalIdx = html.indexOf("eval(function(p,a,c,k,e,")
                    if (evalIdx != -1) {
                        Log.d(TAG, "  [$depth] found packed JS, unpacking...")
                        try {
                            val packed = html.substring(evalIdx).substringBefore("</script>")
                            val unpacked = JsUnpacker(packed).unpack() ?: ""
                            Log.d(TAG, "  [$depth] unpacked ${unpacked.length} chars")
                            for ((i, r) in streamPatterns.withIndex()) {
                                r.find(unpacked)?.let {
                                    var stream = it.groupValues[1].replace("\\/", "/")
                                    if (i == 5) {
                                        try { stream = String(android.util.Base64.decode(stream, android.util.Base64.DEFAULT)) } catch (_: Exception) {}
                                    }
                                    if (stream.contains(".m3u8") || stream.contains(".mp4") || stream.startsWith("http")) {
                                        Log.d(TAG, "  [$depth] STREAM in unpacked (pattern #$i): $stream")
                                        val hdrs2 = mutableMapOf(
                                            "Referer" to url,
                                            "User-Agent" to USER_AGENT,
                                        )
                                        if (origin.isNotEmpty()) hdrs2["Origin"] = origin
                                        return Video(stream, headers = hdrs2)
                                    }
                                }
                            }
                            Log.d(TAG, "  [$depth] no stream in unpacked JS either")
                        } catch (e: Exception) {
                            Log.e(TAG, "  [$depth] unpack error", e)
                        }
                    }

                    // Follow nested iframes (skip chatango chat widget)
                    val nextSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
                        .findAll(html)
                        .map { it.groupValues[1] }
                        .firstOrNull { !it.contains("chatango") && it != url }
                    if (nextSrc != null) {
                        Log.d(TAG, "  [$depth] following iframe: $nextSrc")
                        referer = url
                        url = when {
                            nextSrc.startsWith("http") -> nextSrc
                            nextSrc.startsWith("//") -> "https:$nextSrc"
                            else -> "https://$nextSrc"
                        }
                    } else {
                        Log.d(TAG, "  [$depth] no iframe found, stopping")
                        break
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "  [$depth] fetch error: ${e.message}")
                    break
                }
            }
        }

        Log.w(TAG, "getVideo FAILED - no stream found")
        return Video("", emptyList())
    }
}