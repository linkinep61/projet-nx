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
    private const val PHASE3_MAX_CANDIDATES = 30  // probe at most this many cids per scan
    private const val PHASE3_MAX_FR_CIDS = 20     // stop once we have N healthy FR cids (incl primary)
    private const val PHASE3_PARALLELISM = 4      // concurrent probes

    // Disk cache for the discovered FR cid list — 24h TTL
    private const val FR_CIDS_CACHE_FILE = "olatv_fr_cids.json"
    private const val FR_CIDS_CACHE_TTL_MS = 24L * 60 * 60 * 1000L

    // ───────── Normalization & TNT order ─────────

    /** Strip accents, brackets, quality tags, country suffixes, and "+1" markers — *anywhere*
     *  in the name (not just at the end), so all variants of a given channel collapse to the
     *  same base key. Iterates until no changes so that combos like "TF1 +1 HD" reduce to "tf1". */
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
        // Repeatedly strip quality tags, country tags, and +1 markers until stable.
        val tag = Regex(
            "\\b(hd|sd|fhd|uhd|4k|raw|hevc|h\\.?265|ppv|ott|live|test|backup|fhdr|sdr)\\b" +
                "|\\b(france|fr|french|francais|belgique|be|suisse|ch|lux)\\b" +
                "|(?:\\+\\s?1)|(?:1080p|720p|480p|360p)|\\.fr\\b"
        )
        while (true) {
            val next = s.replace(tag, " ").replace(Regex("\\s+"), " ").trim()
            if (next == s) break
            s = next
        }
        return s.replace(Regex("[^a-z0-9]"), "").replace("sports", "sport")
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

    /** Pretty display name: same logic as norm but preserves case/accents and word spacing.
     *  Used for the UI label on a channel card. */
    private fun baseDisplayName(raw: String): String {
        val tag = Regex(
            "\\b(HD|SD|FHD|UHD|4K|RAW|HEVC|H\\.?265|PPV|OTT|LIVE|TEST|BACKUP|FHDR|SDR)\\b" +
                "|\\b(France|FR|French|Francais|Belgique|BE|Suisse|CH|LUX)\\b" +
                "|(?:\\+\\s?1)|(?:1080p|720p|480p|360p)|\\.fr\\b",
            RegexOption.IGNORE_CASE
        )
        var s = raw
            .replace(Regex("\\[.*?]"), " ")
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("^\\s*(FR|France)\\s*[:|\\-]\\s*", RegexOption.IGNORE_CASE), "")
        while (true) {
            val next = s.replace(tag, " ").replace(Regex("\\s+"), " ").trim()
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
            return null
        }
    }

    /** Get MAC portal credentials for a cid via getToken128910. */
    private data class MacCredentials(val baseUrl: String, val mac: String)
    private fun getMacCredentials(cid: String): MacCredentials? {
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
            return MacCredentials(baseUrl, mac)
        } catch (e: Exception) {
            Log.e(TAG, "getMacCredentials($cid) failed: ${e.message}")
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

    /** Build a tv-logo/tv-logos GitHub URL from a channel name (slug FR convention).
     *  Strips quality/locale markers (FR, FHD, HD, +1, etc.) anywhere in the name —
     *  the tv-logo repo uses minimal slugs like "tf1-fr.png" / "france-2-fr.png". */
    private fun logoUrlFor(name: String): String {
        val slug = name.lowercase()
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâä]"), "a")
            .replace(Regex("[ùûü]"), "u")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace("ç", "c")
            // Strip locale/quality markers anywhere (word-boundary safe via spaces)
            .replace(Regex("\\b(fr|fhd|uhd|hd|sd|4k|raw|hevc|h265|ppv)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\+\\s?1\\b"), " ")
            .replace("+", "-plus")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/$slug-fr.png"
    }

    // ───────── Phase 3 helpers: ingest one cid, probe FR genres, disk cache ─────────

    /** Ingest ALL channels from a cid into the registry. Reusable for primary + scan.
     *  Returns the number of stream refs added (channels merged by normalized name). */
    private suspend fun ingestCidChannels(cid: String, creds: MacCredentials, forceAllGenres: Boolean = false): Int {
        val channels = olaListMacPortalChannels(creds.baseUrl, creds.mac, forceAllGenres)
        var added = 0
        synchronized(registryLock) {
            for (ch in channels) {
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
                        try { scanAdditionalFrCids(primaryCid) }
                        catch (e: Exception) { Log.w(TAG, "Phase 3 background scan failed: ${e.message}") }
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

    // ───────── Provider API ─────────

    override suspend fun getHome(): List<Category> = try {
        ensureRegistry()
        val snapshot = synchronized(registryLock) { LinkedHashMap(channelRegistry) }

        val categoryOrder = listOf("Généraliste", "Cinéma", "Info", "Sport", "Musique", "Documentaire", "Enfants")
        val sections = mutableListOf<Category>()
        for (catName in categoryOrder) {
            val items = sortByTnt(snapshot
                .filter { it.value.category == catName }
                .map { (k, v) -> toTvShow(k, v) })
            if (items.isNotEmpty()) sections.add(Category(name = catName, list = items))
        }
        sections
    } catch (e: Exception) {
        Log.e(TAG, "getHome error", e)
        emptyList()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = try {
        if (page > 1) emptyList<AppAdapter.Item>()
        ensureRegistry()
        val snapshot = synchronized(registryLock) { LinkedHashMap(channelRegistry) }
        snapshot
            .filter { it.value.displayName.contains(query, ignoreCase = true) }
            .map { (k, v) -> toTvShow(k, v) }
    } catch (_: Exception) { emptyList() }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        if (page > 1) emptyList()
        else try {
            ensureRegistry()
            val snapshot = synchronized(registryLock) { LinkedHashMap(channelRegistry) }
            sortByTnt(snapshot.map { (k, v) -> toTvShow(k, v) })
        } catch (_: Exception) { emptyList() }

    override suspend fun getMovie(id: String): Movie = throw Exception("Not supported")

    override suspend fun getTvShow(id: String): TvShow = try {
        ensureRegistry()
        val key = id.removePrefix("ola::")
        val info = synchronized(registryLock) { channelRegistry[key] }
            ?: throw Exception("Channel '$key' not found")
        // Use the SAME id for TvShow / Season / Episode so getServers(episodeId)
        // can extract the same key. Mirrors WiTvProvider's pattern.
        val logo = info.logo.ifBlank { null }
        toTvShow(key, info).copy(
            seasons = listOf(
                Season(id = id, number = 1, title = "En Direct",
                    episodes = listOf(Episode(id = id, number = 1,
                        title = "Regarder en Direct", poster = logo)))
            )
        )
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
            val streamsSnapshot = info.streams.toList()
            Log.d(TAG, "getServers '$key' (${info.displayName}): ${streamsSnapshot.size} stream(s) — progressive emission")

            // Build a Video.Server for the first stream (synchronous result so the
            // player can open immediately) and emit ALL variants (including the first)
            // via the progressive flow so they all appear in the player's "Chaîne" page.
            val first = streamsSnapshot.firstOrNull()
            val initialServers = if (first != null) {
                Log.d(TAG, "  primary[0] cid=${first.cid} label='${first.label}' cmd=${first.url.take(80)}")
                listOf(Video.Server(
                    id = "ola_stream::${first.cid}::${first.label}::${first.url}",
                    name = "OLA TV - ${first.label}",
                ))
            } else {
                emptyList()
            }

            // Cancel any previous emit job (channel switch) and start a new one.
            currentEmitJob?.cancel()
            currentEmitJob = scope.launch {
                // Tiny delay so the player has time to subscribe to the flow.
                // (replay=50 also covers this, but the delay keeps logs clean.)
                delay(150)
                // Emit ALL known streams as variants (including the one already returned
                // as initialServers) so the user sees them all in the Chaîne page.
                streamsSnapshot.forEach { stream ->
                    if (!isActive) return@launch
                    val server = Video.Server(
                        id = "ola_stream::${stream.cid}::${stream.label}::${stream.url}",
                        name = "OLA[$key] ${stream.label}",
                    )
                    Log.d(TAG, "  → emit OLA[$key] ${stream.label} (cid=${stream.cid})")
                    _additionalServers.emit(server)
                }

                // Wait for Phase 3 / late additions for up to ~60s and emit any new streams
                // that arrive after the initial snapshot. We poll the registry for size growth.
                val seenUrls = streamsSnapshot.map { it.url }.toMutableSet()
                val waitDeadlineMs = System.currentTimeMillis() + 60_000L
                while (isActive && System.currentTimeMillis() < waitDeadlineMs && !phase3Done) {
                    delay(1500)
                    val latest = synchronized(registryLock) { channelRegistry[key]?.streams?.toList() } ?: continue
                    for (s in latest) {
                        if (!isActive) return@launch
                        if (seenUrls.add(s.url)) {
                            val server = Video.Server(
                                id = "ola_stream::${s.cid}::${s.label}::${s.url}",
                                name = "OLA[$key] ${s.label}",
                            )
                            Log.d(TAG, "  → late emit OLA[$key] ${s.label} (cid=${s.cid})")
                            _additionalServers.emit(server)
                        }
                    }
                }
                Log.d(TAG, "OLA TV emit done for '$key' — ${seenUrls.size} stream(s)")
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
            // Format: "ola_stream::<cid>::<label>::<cmd>"
            val parts = server.id.removePrefix("ola_stream::").split("::", limit = 3)
            if (parts.size < 3) throw Exception("Bad server id: ${server.id}")
            val cid = parts[0]
            val cmd = parts[2]

            val rawCmd = cmd.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
            // localhost URLs are placeholders — the MAC portal expects you to call
            // create_link to get the real upstream URL. Only direct external HTTP URLs
            // can be played as-is.
            val isLocalhost = rawCmd.contains("localhost") || rawCmd.contains("127.0.0.1")
            val streamUrl = if (rawCmd.startsWith("http") && !isLocalhost) {
                rawCmd
            } else {
                val creds = getMacCredentials(cid) ?: throw Exception("Could not get MAC creds for cid=$cid")
                resolveStreamCmd(creds.baseUrl, creds.mac, cmd) ?: throw Exception("create_link returned null")
            }
            Log.d(TAG, "getVideo resolved → ${streamUrl.take(120)}")
            Video(streamUrl, headers = mapOf("User-Agent" to USER_AGENT))
        } catch (e: Exception) {
            Log.e(TAG, "getVideo error", e); throw e
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
