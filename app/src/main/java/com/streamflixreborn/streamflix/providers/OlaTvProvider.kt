package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.*
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
object OlaTvProvider : Provider {

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

    // ───────── Normalization & TNT order ─────────

    /** Same as WiTv's norm — strip accents, quality tags, country suffixes. */
    private fun norm(raw: String): String =
        raw.lowercase()
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâä]"), "a")
            .replace(Regex("[ùûü]"), "u")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace("ç", "c")
            .replace(Regex("\\[.*?]"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\s+(france|fr|french|belgique|be|suisse|ch)\\s*$"), "")
            .replace(Regex("\\s*(hd|sd|fhd|uhd|4k|\\+1|1080p|720p|480p|360p)\\s*$"), "")
            .trim()
            .replace(Regex("[^a-z0-9]"), "")
            .replace("sports", "sport")

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
     *  Returns channel name → cmd. We resolve cmd to actual stream URLs lazily in getServers. */
    private fun olaListMacPortalChannels(baseUrl: String, mac: String, forceAllGenres: Boolean = false): List<MacChannel> {
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

            // Step 4: For each FR genre, paginate get_ordered_list
            val seenNames = mutableSetOf<String>()
            for (genreId in frGenreIds) {
                for (page in 1..30) {
                    val chReq = Request.Builder()
                        .url("$portalBase?type=itv&action=get_ordered_list&genre=$genreId&force_ch_link_check=&fav=0&sortby=name&p=$page&JsHttpRequest=1-xml")
                        .header("User-Agent", stbUA).header("Cookie", cookie)
                        .header("Authorization", "Bearer $token").build()
                    val chBody = probeClient.newCall(chReq).execute().body?.string() ?: ""
                    val js = try { JSONObject(chBody).getJSONObject("js") } catch (_: Exception) { break }
                    val data = js.optJSONArray("data") ?: break
                    val totalItems = js.optInt("total_items", 0)
                    val maxPageItems = js.optInt("max_page_items", 14)

                    for (i in 0 until data.length()) {
                        val ch = data.optJSONObject(i) ?: continue
                        val rawName = ch.optString("name", "").trim()
                        if (rawName.isBlank() || rawName.startsWith("#")) continue
                        val cmd = ch.optString("cmd", "").trim()
                        if (cmd.isBlank()) continue

                        // Strip country prefix "FR|" / "FR " etc.
                        val cleaned = rawName
                            .replace(Regex("^(FR|ES|PT|EN|DE|IT|AR|TR|NL|PL|RO|US|UK|BE|CH)[|:\\s]+", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("\\s+"), " ").trim()
                        if (cleaned.isBlank()) continue
                        if (cleaned in seenNames) continue
                        seenNames.add(cleaned)
                        results.add(MacChannel(cleaned, cmd))
                    }
                    if (data.length() == 0 || (page * maxPageItems) >= totalItems) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MAC portal failed for $baseUrl: ${e.message}")
        }
        Log.d(TAG, "MAC portal $baseUrl: ${results.size} unique FR channels")
        return results
    }

    /** Resolve a "cmd" (from get_ordered_list) to an actual playable stream URL.
     *  If cmd already starts with http://, return it directly. Else (localhost:* form),
     *  call create_link to get the real URL. */
    private fun resolveStreamCmd(baseUrl: String, mac: String, cmd: String): String? {
        val rawCmd = cmd.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
        if (rawCmd.startsWith("http")) return rawCmd

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

    /** Build a tv-logo/tv-logos GitHub URL from a channel name (slug FR convention). */
    private fun logoUrlFor(name: String): String {
        val slug = name.lowercase()
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâä]"), "a")
            .replace(Regex("[ùûü]"), "u")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace("ç", "c")
            .replace("+", "-plus")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/$slug-fr.png"
    }

    // ───────── Catalog loading ─────────

    private suspend fun ensureRegistry() {
        if (registryLoaded && System.currentTimeMillis() - lastLoadTime < CACHE_DURATION) return
        registryMutex.withLock {
            if (registryLoaded && System.currentTimeMillis() - lastLoadTime < CACHE_DURATION) return@withLock
            registryLoaded = false
            synchronized(registryLock) { channelRegistry.clear() }

            val t0 = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
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
                val channels = olaListMacPortalChannels(creds.baseUrl, creds.mac, forceAllGenres = true)
                Log.d(TAG, "Primary FR portal returned ${channels.size} channels")

                // Step 4: ingest into the registry (group by normalized name to merge variants)
                synchronized(registryLock) {
                    for (ch in channels) {
                        val key = norm(ch.name)
                        if (key.isBlank()) continue
                        val info = channelRegistry.getOrPut(key) {
                            val displayName = ch.name
                                .replace(Regex("\\s*(HD|SD|FHD|UHD|4K|RAW|HEVC|H265|PPV)\\s*$", RegexOption.IGNORE_CASE), "")
                                .trim()
                                .ifBlank { ch.name }
                            ChannelInfo(
                                displayName = displayName,
                                category = guessCategory(displayName),
                                logo = logoUrlFor(displayName),
                            )
                        }
                        info.streams.add(OlaStreamRef(primaryCid, ch.name, ch.cmd))
                    }
                }
                Log.d(TAG, "Registry built: ${channelRegistry.size} unique channels in ${System.currentTimeMillis() - t0}ms")
            }
            registryLoaded = true
            lastLoadTime = System.currentTimeMillis()
        }
    }

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
        toTvShow(key, info).copy(
            seasons = listOf(
                Season(id = "ola_season::$key", number = 1, title = "En direct",
                    episodes = listOf(Episode(id = "ola_ep::$key", number = 1,
                        title = "Regarder en Direct", poster = info.logo.ifBlank { null })))
            )
        )
    } catch (e: Exception) {
        Log.e(TAG, "getTvShow($id) error", e); throw e
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()

    override suspend fun getGenre(id: String, page: Int): Genre = throw Exception("Not supported")

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            ensureRegistry()
            val key = id.removePrefix("ola_ep::").removePrefix("ola::")
            val info = synchronized(registryLock) { channelRegistry[key] } ?: return emptyList()
            val servers = mutableListOf<Video.Server>()
            for (stream in info.streams) {
                // Encode the cmd into the server id; resolve at getVideo time.
                servers.add(Video.Server(
                    id = "ola_stream::${stream.cid}::${stream.label}::${stream.url}",
                    name = if (info.streams.size == 1) "OLA TV" else "OLA TV - ${stream.label}",
                ))
            }
            servers
        } catch (e: Exception) {
            Log.e(TAG, "getServers($id) error", e); emptyList()
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
            // If it's already a direct URL, use it.
            val streamUrl = if (rawCmd.startsWith("http")) {
                rawCmd
            } else {
                // Resolve via create_link
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
