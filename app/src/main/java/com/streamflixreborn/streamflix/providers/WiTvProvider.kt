package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.EnrichmentTrigger
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
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

object WiTvProvider : Provider {

    override val name = "WiTV"
    override val baseUrl = "https://witv.team"
    override val logo = "https://witv.team/templates/witv/images/witv-logo-w2.png"
    override val language = "fr"

    private const val TAG = "WiTvProvider"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
    private const val SPORTS_PROG_URL = "https://sportsonline.vc/prog.txt"
    private const val CLUONE_TOKEN = "388FAA8743C4E2980F7B4089B7E81087"
    private const val CLUONE_BASE = "https://cluone.dad/live"

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

    // ── Progressive OLA TV server loading ──
    // Emits OLA CID servers (not streams) as background scan discovers them
    private val _additionalServers = MutableSharedFlow<Video.Server>(extraBufferCapacity = 100)
    val additionalServersFlow: SharedFlow<Video.Server> = _additionalServers
    private val olaScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentOlaJob: kotlinx.coroutines.Job? = null

    // (OLA TV streams are emitted progressively via _additionalServers flow)

    // (Dric4rTV and 3BoxTV removed — negligible source coverage)

    // ───────── HTTP ─────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
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
        // key = source label ("WiTV", "OTF", etc.), value = page URL or ID
        // OTF TV direct m3u8 URLs
        var otfTvUrls: List<String> = emptyList(),
        // (OLA TV streams are fetched on-demand in getServers, not stored here)
    ) {
        /** True if the channel has at least one concrete server source.
         *  Channels without any source are hidden from the UI. */
        fun hasServer(): Boolean =
            sources.isNotEmpty() || otfTvUrls.isNotEmpty()
    }

    private val channelRegistry = LinkedHashMap<String, ChannelInfo>()
    private val registryLock = Any()   // guard ALL reads/writes to channelRegistry
    @Volatile private var lastLoadTime = 0L
    private val registryMutex = Mutex()  // prevent concurrent ensureRegistry() calls
    @Volatile private var registryLoaded = false
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
                    .filter { it.value.hasServer() && !UserPreferences.isChannelFailed("ch::${it.key}") }
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

    // WiTV category pages — Généraliste first, Sport after Musique per user request
    private val witvCategories = listOf(
        "Généraliste" to "/chaines-live/generaliste/",
        "Cinéma" to "/chaines-live/cinema/",
        "Info" to "/chaines-live/info/",
        "Documentaire" to "/chaines-live/documentaire/",
        "Enfants" to "/chaines-live/enfants/",
        "Musique" to "/chaines-live/musique/",
        "Sport" to "/chaines-live/sport/",
        "Belgique" to "/chaines-live/belgique/",
        "Suisse" to "/chaines-live/suisse/",
    )

    /** Normalize a channel name for dedup matching.
     *  Strips accents, quality tags, country suffixes, resolution markers.
     *  Also normalizes "sports" → "sport" for beIN SPORTS/SPORT matching. */
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

    /** Parse `.ann-short_item` cards from an element and register in channelRegistry. */
    private fun registerCards(container: Element, category: String): List<String> {
        val keys = mutableListOf<String>()
        container.select(".ann-short_item").forEach { card ->
            val link = card.selectFirst("a[href*=.html]") ?: return@forEach
            val href = link.attr("href").let { if (it.startsWith("http")) it else "$baseUrl$it" }
            val title = card.selectFirst(".ann-short_price")?.text()?.trim() ?: return@forEach
            val img = card.selectFirst("img")?.attr("src")?.let {
                if (it.startsWith("http")) it else "$baseUrl$it"
            } ?: ""
            val key = norm(title)
            if (key.isEmpty()) return@forEach

            synchronized(registryLock) {
                val entry = channelRegistry.getOrPut(key) {
                    ChannelInfo(title, img, category)
                }
                entry.sources["WiTV"] = href
                if (entry.category.isBlank()) entry.category = category
            }
            keys.add(key)
        }
        return keys
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

        val t0 = System.currentTimeMillis()

        // ═══ PHASE 1: WiTV categories — creates registry entries ═══
        // This is the ONLY blocking phase. Channels display as soon as it finishes.
        coroutineScope {
            for ((catName, catPath) in witvCategories) {
                async(Dispatchers.IO) {
                    val t = System.currentTimeMillis()
                    try {
                        val doc = service.getPage("$baseUrl$catPath")
                        registerCards(doc, catName)
                        Log.d(TAG, "⏱ WiTV/$catName: ${System.currentTimeMillis() - t}ms")
                    } catch (e: Exception) {
                        Log.e(TAG, "⏱ WiTV/$catName FAILED (${System.currentTimeMillis() - t}ms): ${e.message}")
                    }
                }
            }
        }

        Log.d(TAG, "⏱ Phase 1 done: ${channelRegistry.size} base channels in ${System.currentTimeMillis() - t0}ms")

        // ═══ Mark registry as loaded NOW so UI can display channels immediately ═══
        lastLoadTime = System.currentTimeMillis()
        registryLoaded = true

        // ═══ PHASE 2: Enrichment sources — DEFERRED until player opens ═══
        // These add extra servers (OTF, OLA TV) to existing entries.
        // Channels are playable via WiTV while Phase 2 loads. Waiting for the player
        // signal avoids competing for CPU/network with ExoPlayer init on Chromecast.
        CoroutineScope(Dispatchers.IO).launch {
            // Wait until player has started loading the first channel
            // Safety timeout: 30s so Phase 2 still runs if user never opens the player
            withTimeoutOrNull(30_000) {
                EnrichmentTrigger.playerReadyFlow.first()
            }
            Log.d(TAG, "⏱ Phase 2 starting (player ready or timeout)")
            val t2 = System.currentTimeMillis()
            val jobs = listOf(
                async {
                    val t = System.currentTimeMillis()
                    try { parseOtfTvChannels() } catch (e: Exception) { Log.e(TAG, "OTF failed: ${e.message}") }
                    Log.d(TAG, "⏱ OTF TV: ${System.currentTimeMillis() - t}ms")
                },
                async {
                    val t = System.currentTimeMillis()
                    try { parseOlaTvServerList() } catch (e: Exception) { Log.e(TAG, "OLA TV list failed: ${e.message}") }
                    Log.d(TAG, "⏱ OLA TV servers: ${System.currentTimeMillis() - t}ms")
                },
            )
            jobs.forEach { it.await() }

            phase2Done = true
            val witvCount = channelRegistry.count { it.value.sources.containsKey("WiTV") }
            val otfCount = channelRegistry.count { it.value.sources.containsKey("OTF") }
            val multi = channelRegistry.count { it.value.sources.size > 1 }
            Log.d(TAG, "✓ Phase 2 done in ${System.currentTimeMillis() - t2}ms: ${channelRegistry.size} channels (WiTV=$witvCount, OTF=$otfCount, multi=$multi), OLA TV=${olaTvServerMap.size} servers cached")

            // Launch background FR server scan after Phase 2
            if (!olaTvFrScanDone && olaTvServerMap.isNotEmpty()) {
                scanOlaTvFrServers()
            }
        }

        } // end registryMutex.withLock
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

    /** Fetch OTF TV channel list via encrypted API.
     *  Returns direct m3u8 URLs on stm.linkip.org.
     *  Only enriches existing channels — does NOT create new entries. */
    private fun parseOtfTvChannels() {
        try {
            // Generate a device ID (stable per install via a hash)
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
            if (body.isNullOrBlank()) {
                Log.w(TAG, "OTF TV: empty response (code=${response.code})")
                return
            }

            // Check for rate-limit or error messages
            if (body.contains("denied") || body.contains("error") || body.length < 50) {
                Log.w(TAG, "OTF TV: API error: ${body.take(100)}")
                return
            }

            // Decrypt response
            val decrypted = otfDecrypt(body)
            Log.d(TAG, "OTF TV: decrypted ${decrypted.length} chars")

            // Fix trailing commas and parse JSON
            val fixed = decrypted
                .replace(Regex(",\\s*\\]"), "]")
                .replace(Regex(",\\s*\\}"), "}")
            val json = JSONObject(fixed)
            val streams = json.optJSONArray("Streams") ?: run {
                Log.w(TAG, "OTF TV: no Streams array in response")
                return
            }

            var matched = 0
            var total = 0
            // Iterate all stream groups (France, BeIN Sports, Cinema, etc.)
            for (g in 0 until streams.length()) {
                val group = streams.getJSONObject(g)
                val channels = group.optJSONArray("Channels") ?: continue
                for (c in 0 until channels.length()) {
                    val ch = channels.getJSONObject(c)
                    val chName = ch.optString("name", "").trim()
                    if (chName.isBlank()) continue
                    total++

                    val key = norm(chName)
                    if (key.isEmpty()) continue

                    // Collect all quality URLs outside lock
                    val vq = ch.optJSONArray("vq")
                    val urls = mutableListOf<String>()
                    if (vq != null) {
                        for (q in 0 until vq.length()) {
                            val urlStr = vq.getJSONObject(q).optString("url", "").trim()
                            if (urlStr.isNotBlank() && urlStr.startsWith("http")) urls.add(urlStr)
                        }
                    }

                    if (urls.isNotEmpty()) {
                        synchronized(registryLock) {
                            val existing = channelRegistry[key] ?: return@synchronized
                            if (existing.sources.containsKey("OTF")) return@synchronized
                            existing.sources["OTF"] = urls.first()
                            existing.otfTvUrls = urls
                            matched++
                            Log.d(TAG, "OTF TV: ✓ matched '$chName' → key='$key' (${urls.size} URLs)")
                        }
                    }
                }
            }
            Log.d(TAG, "OTF TV: $total channels parsed, $matched matched to existing registry entries")
        } catch (e: Exception) {
            Log.e(TAG, "OTF TV: API call failed — ${e.javaClass.simpleName}: ${e.message}")
        }
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

            // Step 2: Get profile (needed to init session)
            val profReq = Request.Builder()
                .url("$portalBase?type=stb&action=get_profile&auth_token=$token&JsHttpRequest=1-xml")
                .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3")
                .header("Cookie", cookie)
                .header("Authorization", "Bearer $token")
                .build()
            probeClient.newCall(profReq).execute().body?.close()

            // Step 3: Get genres, find French genre, then paginate to find matching channel
            val stbUA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

            // 3a: Get genres list
            val genreReq = Request.Builder()
                .url("$portalBase?type=itv&action=get_genres&JsHttpRequest=1-xml")
                .header("User-Agent", stbUA).header("Cookie", cookie)
                .header("Authorization", "Bearer $token").build()
            val genreResp = probeClient.newCall(genreReq).execute()
            val genreBody = genreResp.body?.string() ?: ""

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

            // 3b: For each genre (FR first, then fallback *), paginate to find channel variants
            for (genreId in frGenreIds) {
                if (results.isNotEmpty()) break
                val maxPages = if (genreId == "*") 5 else 30 // more pages for targeted FR genre
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

                    // Collect ALL candidate matches on this page, then pick the best one.
                    // This avoids "M6 4K" being returned when the user wants "M6":
                    // both normalize to "m6", but we prefer the one without quality tags.
                    data class Candidate(val chName: String, val cmd: String, val exactScore: Int)
                    val candidates = mutableListOf<Candidate>()

                    for (i in 0 until data.length()) {
                        val ch = data.optJSONObject(i) ?: continue
                        val chName = ch.optString("name", "").trim()
                        if (chName.isBlank() || chName.startsWith("#")) continue

                        // Strip country prefix only (no quality strip) for exact-match check
                        val nameNoPrefix = chName
                            .replace(Regex("^(FR|ES|PT|EN|DE|IT|AR|TR|NL|PL|RO|US|UK|BE|CH)[|:\\s]+", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("\\s+"), " ").trim()
                        // Full clean: also strip quality tags
                        val cleanName = nameNoPrefix
                            .replace(Regex("[\\s|/]*(HD|SD|FHD|UHD|4K|RAW|HEVC|H265|PPV)[\\s/]*", RegexOption.IGNORE_CASE), " ")
                            .replace(Regex("\\s+"), " ").trim()
                        val key = norm(cleanName)

                        // Log first few channels for diagnostics
                        if (page == 1 && i < 5) Log.d(TAG, "OLA MAC ch[$i]: raw='$chName', norm='$key', looking='$channelKey'")

                        if (key == channelKey) {
                            // Score: 0 = exact match (norm of prefix-stripped name matches without quality strip)
                            //        1 = had quality tags stripped to match
                            val exactScore = if (norm(nameNoPrefix) == channelKey) 0 else 1
                            candidates.add(Candidate(chName, ch.optString("cmd", "").trim(), exactScore))
                        }
                    }

                    // Sort: prefer exact match (score 0), then non-HEVC over HEVC
                    candidates.sortWith(compareBy(
                        { it.exactScore },
                        { if (it.chName.contains("HEVC", ignoreCase = true)) 1 else 0 }
                    ))

                    // Resolve URLs for ALL candidates — each becomes a separate server option
                    for (cand in candidates) {
                        val rawUrl = cand.cmd.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()

                        // If URL is localhost (internal), resolve via create_link API
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
                            } catch (e: Exception) {
                                Log.e(TAG, "OLA MAC create_link failed: ${e.message}")
                                rawUrl
                            }
                        } else rawUrl

                        // Build a clean label from the raw IPTV channel name
                        val label = cand.chName
                            .replace(Regex("^(FR|ES|PT|EN|DE|IT|AR|TR|NL|PL|RO|US|UK|BE|CH)[|:\\s]+", RegexOption.IGNORE_CASE), "")
                            .trim()

                        if (streamUrl.startsWith("http") && results.none { it.url == streamUrl }) {
                            Log.d(TAG, "OLA MAC variant(score=${cand.exactScore}): '$label' → ${streamUrl.take(120)}")
                            results.add(OlaStream(label, streamUrl))
                        }
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
                            .replace(Regex("^(FR|ES|PT|EN|DE|IT|AR|TR|NL|PL|RO|US|UK|BE|CH)[|:\\s]+", RegexOption.IGNORE_CASE), "")
                            .trim()
                        if (streams.none { it.url == streamUrl }) {
                            streams.add(OlaStream(label, streamUrl))
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

    /** Shared thread pool for OLA TV scanning — reused across calls, avoids re-allocation */
    private val scanExecutor = java.util.concurrent.Executors.newFixedThreadPool(5)

    private fun scanOlaTvFrServers() {
        if (olaTvFrScanDone) return
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "OLA TV FR scan: starting, ${olaTvServerMap.size} servers to probe (stopping at $MAX_FR_SERVERS FR)")

        // Skip primary server (already known FR) and build probe list
        val primaryCid = olaTvServerMap.entries.find { it.value == OLA_TV_PRIMARY_FR }?.key
        val cidsToProbe = olaTvServerMap.keys.filter { it != primaryCid }.toList()

        val semaphore = java.util.concurrent.Semaphore(5) // max 5 concurrent
        val latch = java.util.concurrent.CountDownLatch(cidsToProbe.size)
        val probedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val earlyStop = java.util.concurrent.atomic.AtomicBoolean(false)

        for (cid in cidsToProbe) {
            scanExecutor.submit {
                try {
                    if (earlyStop.get()) return@submit // skip remaining work
                    semaphore.acquire()
                    if (earlyStop.get()) { semaphore.release(); return@submit }
                    val hasFr = probeServerForFr(cid)
                    if (hasFr) {
                        olaTvFrCids.add(cid)
                        val total = olaTvFrCids.size
                        val name = olaTvServerMap[cid] ?: "?"
                        Log.d(TAG, "OLA TV FR scan: ✓ FR server found: cid=$cid name='$name' (total=$total)")
                        if (total >= MAX_FR_SERVERS) {
                            Log.d(TAG, "OLA TV FR scan: reached $MAX_FR_SERVERS FR servers, stopping early")
                            earlyStop.set(true)
                        }
                    }
                    val p = probedCount.incrementAndGet()
                    if (p % 50 == 0) {
                        Log.d(TAG, "OLA TV FR scan: progress $p/${cidsToProbe.size}, found ${olaTvFrCids.size} FR servers so far")
                    }
                } catch (e: Exception) {
                    // Silent fail for individual servers
                } finally {
                    semaphore.release()
                    latch.countDown()
                }
            }
        }

        // Wait for all probes (with overall timeout of 3 minutes, or early stop)
        latch.await(3, TimeUnit.MINUTES)
        if (earlyStop.get()) {
            // Cancel remaining queued tasks immediately
            scanExecutor.shutdownNow()
        }

        olaTvFrScanDone = true
        val elapsed = System.currentTimeMillis() - t0
        Log.d(TAG, "OLA TV FR scan: DONE in ${elapsed / 1000}s — probed ${probedCount.get()} servers, found ${olaTvFrCids.size} with FR genres${if (earlyStop.get()) " (early stop)" else ""}")
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
        val sections = mutableListOf<Category>()

        // Snapshot to avoid ConcurrentModificationException (Phase 2 modifies registry in background)
        val snapshot = synchronized(registryLock) { LinkedHashMap(channelRegistry) }

        // WiTV categories — same order as witvCategories (Généraliste first)
        // Sport events inserted right after Musique
        for ((catName, _) in witvCategories) {
            val items = sortByTnt(snapshot
                .filter { it.value.category == catName && it.value.hasServer() && !UserPreferences.isChannelFailed("ch::${it.key}") }
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

        sections
    } catch (e: Exception) {
        Log.e(TAG, "getHome error", e)
        emptyList()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = try {
        ensureRegistry()
        val snapshot = synchronized(registryLock) { LinkedHashMap(channelRegistry) }
        snapshot
            .filter { it.value.displayName.contains(query, true) && it.value.hasServer() && !UserPreferences.isChannelFailed("ch::${it.key}") }
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
            sortByTnt(snapshot.filter { it.value.hasServer() && !UserPreferences.isChannelFailed("ch::${it.key}") }.map { (k, v) -> toTvShow(k, v) })
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

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = try {
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
                Log.d(TAG, "  sources=${info.sources.keys}, otf=${info.otfTvUrls.size}")

                // ── Priority 1: OTF TV (direct m3u8, no WebView needed) ──
                if (info.otfTvUrls.isNotEmpty()) {
                    info.otfTvUrls.forEachIndexed { idx, url ->
                        Log.d(TAG, "  ✓ server OTF #${idx + 1} → $url")
                        servers.add(Video.Server("m3u8::$url", "OTF #${idx + 1}"))
                    }
                }

                // ── Priority 2: WiTV (fast, ~1s) ──
                info.sources["WiTV"]?.let { witvUrl ->
                    try {
                        Log.d(TAG, "  fetching WiTV page: $witvUrl")
                        val doc = withContext(Dispatchers.IO) { service.getPage(witvUrl) }
                        var idx = 1
                        val iframes = doc.select("iframe").ifEmpty { doc.select(".tabs-sel iframe") }
                        Log.d(TAG, "  found ${iframes.size} iframes")
                        iframes.forEach { iframe ->
                            val src = iframe.attr("src")
                            if (src.isNotBlank() && !src.contains("youtube")) {
                                val witvPlayerMatch = Regex("""witv-player\.php\?id=(\d+)""").find(src)
                                if (witvPlayerMatch != null) {
                                    val chId = witvPlayerMatch.groupValues[1]
                                    val m3u8 = "$CLUONE_BASE/$CLUONE_TOKEN/$chId.m3u8"
                                    Log.d(TAG, "  server WiTV #$idx (cluone) -> $m3u8")
                                    servers.add(Video.Server("m3u8::$m3u8", "WiTV #$idx"))
                                } else {
                                    val fullSrc = when {
                                        src.startsWith("http") -> src
                                        src.startsWith("//") -> "https:$src"
                                        else -> "$baseUrl$src"
                                    }
                                    Log.d(TAG, "  server WiTV #$idx -> $fullSrc")
                                    servers.add(Video.Server(fullSrc, "WiTV #$idx"))
                                }
                                idx++
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "  WiTV servers error", e)
                    }
                }

                // ── Priority 3: OLA TV (progressive — emits each server as soon as found) ──
                // Emit OLA TV streams progressively — parallel queries, 5 CIDs at a time
                Log.d(TAG, "OLA TV: starting parallel stream emission for key=$key")
                // Cancel any previous OLA job (e.g. user switched channels)
                currentOlaJob?.cancel()
                currentOlaJob = olaScope.launch {
                    val queriedCids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val emittedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val maxOlaStreams = 20
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
                                    emitMutex.withLock {
                                        UserPreferences.unmarkChannelFailed("ch::$key")
                                        val serverUrl = "m3u8::${ola.url}"
                                        val serverName = "OLA[$key] ${ola.label}"
                                        Log.d(TAG, "  OLA TV stream: $serverName (cid=$cid)")
                                        _additionalServers.emit(Video.Server(serverUrl, serverName))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "OLA TV cid=$cid query failed: ${e.message}")
                        }
                    }

                    // Primary server first (fast path)
                    val primaryCid = olaTvServerMap.entries.find { it.value == OLA_TV_PRIMARY_FR }?.key
                    if (primaryCid != null) {
                        querySingleCid(primaryCid)
                        if (emittedUrls.size >= maxOlaStreams) {
                            Log.d(TAG, "OLA TV: limit reached after primary for '$key'")
                            return@launch
                        }
                    }

                    // Query known FR servers in parallel batches of $parallelism
                    suspend fun queryBatch(cids: List<String>): Boolean {
                        val pending = cids.filter { it !in queriedCids }
                        if (pending.isEmpty()) return false
                        pending.chunked(parallelism).forEach { batch ->
                            if (emittedUrls.size >= maxOlaStreams) return true
                            kotlinx.coroutines.coroutineScope {
                                batch.map { cid ->
                                    async { querySingleCid(cid) }
                                }.awaitAll()
                            }
                        }
                        return emittedUrls.size >= maxOlaStreams
                    }

                    if (queryBatch(olaTvFrCids.toList())) {
                        Log.d(TAG, "OLA TV: limit $maxOlaStreams reached for '$key'")
                        return@launch
                    }

                    // Keep polling for new FR servers from background scan
                    while (!olaTvFrScanDone) {
                        delay(1500)
                        if (queryBatch(olaTvFrCids.toList())) {
                            Log.d(TAG, "OLA TV: limit $maxOlaStreams reached for '$key'")
                            return@launch
                        }
                    }

                    // Final pass
                    queryBatch(olaTvFrCids.toList())
                    Log.d(TAG, "OLA TV: done for '$key', emitted ${emittedUrls.size} streams from ${queriedCids.size} CIDs")
                }

                // Fallback: raw WiTV page if nothing else worked
                if (servers.isEmpty()) {
                    info.sources["WiTV"]?.let {
                        Log.d(TAG, "  no servers found, fallback direct -> $it")
                        servers.add(Video.Server(it, "Direct"))
                    }
                }
            } else {
                Log.w(TAG, "  channel not found in registry")
            }
        }

        Log.d(TAG, "getServers -> ${servers.size} servers")
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

        // Direct m3u8 (cluone.dad resolved in getServers)
        if (originalUrl.startsWith("m3u8::")) {
            val parts = originalUrl.removePrefix("m3u8::").split("||referer=", "||ua=")
            val m3u8 = parts[0]
            val referer = parts.getOrElse(1) { "" }
            val ua = parts.getOrElse(2) { USER_AGENT }
            Log.d(TAG, "  DIRECT M3U8: $m3u8")
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