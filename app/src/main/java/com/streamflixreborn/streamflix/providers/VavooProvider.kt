package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.*
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
 * VavooProvider — chaînes IPTV françaises via oha.to / vavoo.to / huhu.to
 *
 * Architecture identique à WiTV : channelRegistry + m3u8 direct.
 * Léger : pas de WebView, pas de parsing HTML, juste des appels API JSON.
 *
 * Flow :
 *  1. Ping → récupère addonSig (signature pour les requêtes catalog/resolve)
 *  2. Catalog → liste paginée de chaînes IPTV, filtrée France
 *  3. Resolve → on-demand quand l'utilisateur ouvre une chaîne → URL m3u8
 */
object VavooProvider : Provider {

    override val name = "Vavoo"
    override val baseUrl = "https://oha.to"
    override val logo = "https://oha.to/favicon.ico"
    override val language = "fr"

    private const val TAG = "VavooProvider"
    private const val VAVOO_UA = "VAVOO/2.6"
    private const val MEDIAHUBMX_UA = "MediaHubMX/2"
    private const val CLIENT_VERSION = "3.0.2"
    private const val APP_VERSION = "3.1.8"

    // ───────── API domains (fallback chain) ─────────
    private val BASE_SITES = listOf(
        "https://oha.to",
        "https://vavoo.to",
        "https://huhu.to",
        "https://kool.to",
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

    // ───────── Channel registry ─────────
    data class VavooChannel(
        val id: String,
        val name: String,
        val logo: String,
        val group: String,
        val country: String,
        val url: String,  // vavoo internal URL used for resolve
    )

    private val channelRegistry = mutableListOf<VavooChannel>()
    private val registryLock = Any()
    private val registryMutex = Mutex()
    @Volatile private var registryLoaded = false
    @Volatile private var lastLoadTime = 0L
    private const val CACHE_DURATION = 30 * 60 * 1000L // 30 min

    // ───────── Addon signature cache ─────────
    @Volatile private var cachedSignature: String? = null
    @Volatile private var signatureExpiry = 0L

    // ───────── TNT order for sorting ─────────
    private val tntOrder: Map<String, Int> by lazy {
        listOf(
            "TF1", "France 2", "France 3", "Canal+", "France 5", "M6", "Arte",
            "C8", "W9", "TMC", "TFX", "NRJ 12", "LCP", "France 4",
            "BFM TV", "BFMTV", "CNews", "CNEWS", "CStar", "Gulli",
            "TF1 Séries Films", "L'Équipe", "6ter", "RMC Story",
            "RMC Découverte", "Chérie 25", "LCI", "Franceinfo",
        ).mapIndexed { idx, name -> name.lowercase() to idx }.toMap()
    }

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
        "Accept-Encoding" to "gzip, deflate",
    )

    private fun loadCatalogFromBase(baseUrl: String, signature: String): List<VavooChannel> {
        val catalogUrl = "${baseUrl.trimEnd('/')}/mediahubmx-catalog.json"
        val headers = catalogHeaders(signature)
        val channels = mutableListOf<VavooChannel>()
        var cursor: String? = null

        while (true) {
            val body = JSONObject().apply {
                put("language", "fr")
                put("region", "US")  // US = broad catalog
                put("catalogId", "iptv")
                put("id", "iptv")
                put("adult", false)
                put("search", "")
                put("sort", "")
                put("filter", JSONObject())
                put("clientVersion", CLIENT_VERSION)
                if (cursor != null) put("cursor", cursor)
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

                // Filtre France uniquement
                if (!country.equals("France", ignoreCase = true)) continue

                val ids = item.optJSONObject("ids")
                val id = ids?.optString("id", "") ?: item.optString("id", url)

                channels.add(VavooChannel(
                    id = id,
                    name = item.optString("name", ""),
                    logo = item.optString("logo", ""),
                    group = group,
                    country = country,
                    url = url,
                ))
            }

            cursor = json.optString("nextCursor", "")
            if (cursor.isNullOrBlank()) break
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

    override suspend fun getHome(): List<Category> = try {
        ensureRegistry()
        val channels = synchronized(registryLock) { channelRegistry.toList() }

        if (channels.isEmpty()) {
            listOf(Category(name = "Aucune chaîne disponible", list = emptyList()))
        } else {
            // Group by category (extracted from group field after country separator)
            val byCategory = channels.groupBy { ch ->
                val group = ch.group.trim()
                for (sep in COUNTRY_SEPARATORS) {
                    if (group.contains(sep)) {
                        val after = group.substringAfter(sep).trim()
                        if (after.isNotBlank()) return@groupBy after
                    }
                }
                "Général"
            }

            val categories = byCategory.map { (catName, chans) ->
                val sorted = chans.sortedWith(compareBy<VavooChannel> { ch ->
                    tntOrder[ch.name.lowercase()] ?: 999
                }.thenBy { it.name })

                Category(
                    name = catName,
                    list = sorted.map { ch ->
                        TvShow(
                            id = "vavoo::${ch.id}",
                            title = ch.name,
                            poster = ch.logo,
                        )
                    }
                )
            }

            // Sort: "Général" / "Entertainment" first, then alphabetical
            categories.sortedWith(compareBy {
                when {
                    it.name.contains("Général", ignoreCase = true) -> 0
                    it.name.contains("Entertainment", ignoreCase = true) -> 1
                    it.name.contains("News", ignoreCase = true) -> 2
                    it.name.contains("Sport", ignoreCase = true) -> 3
                    else -> 10
                }
            })
        }
    } catch (e: Exception) {
        Log.e(TAG, "getHome failed", e)
        emptyList()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        ensureRegistry()
        val q = query.lowercase()
        return synchronized(registryLock) {
            channelRegistry.filter { it.name.lowercase().contains(q) }
        }.map { ch ->
            TvShow(
                id = "vavoo::${ch.id}",
                title = ch.name,
                poster = ch.logo,
            )
        }
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
    //  Unused — IPTV provider, pas de films
    // ═══════════════════════════════════════════

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = Movie(id = id)
    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id = id, name = id)
    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = id)
}
