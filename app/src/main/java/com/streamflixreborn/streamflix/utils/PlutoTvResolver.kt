package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-06-27 : résolveur Pluto TV via l'API officielle (dossier "Pluto TV" du
 * TV Hub : live + VOD films/séries). Calqué sur PlexTvResolver.
 *
 * Pipeline :
 *   1. Boot ANONYME : GET boot.pluto.tv/v4/start?appName=web&...&clientID=<uuid>
 *      → sessionToken (Bearer) + stitcherParams (query du flux) + servers.stitcher/channels/vod.
 *   2. GÉO France forcée via header X-Forwarded-For=IP_FR sur le BOOT (le géo est
 *      gravé dans le token de session ; XFF post-boot est ignoré).
 *   3. Live : {channels}/v2/guide/categories + /v2/guide/channels.
 *      VOD  : {vod}/v4/vod/categories?includeItems=true (films + séries).
 *      Série: {vod}/v4/vod/series/<id>/seasons. Film flux: {vod}/v4/vod/items?ids=<id>.
 *   4. Flux jouable = {stitcherHost}{stitched.path}?{stitcherParams} (HLS).
 */
object PlutoTvResolver {
    private const val TAG = "PlutoTvResolver"
    private const val BOOT = "https://boot.pluto.tv/v4/start"
    private const val DEFAULT_CHANNELS = "https://service-channels.clusters.pluto.tv"
    private const val DEFAULT_VOD = "https://service-vod.clusters.pluto.tv"
    private const val DEFAULT_STITCHER = "https://cfd-v4-service-channel-stitcher-use1-1.prd.pluto.tv"
    private const val DEFAULT_STITCHER_DASH = "https://cfd-v4-service-stitcher-dash-use1-1.prd.pluto.tv"
    // IP métropolitaine FR (Free) → catalogue France au boot, quel que soit le device.
    private const val FR_IP = "82.64.0.1"
    private const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val SESSION_TTL_MS = 30 * 60 * 1000L

    // clientId CATALOGUE (associé à la région FR forcée par XFF).
    private val clientId: String = java.util.UUID.randomUUID().toString()
    // clientId FLUX séparé : la session de lecture ne doit PAS hériter du FR
    //   forcé du catalogue (sinon décalage région/IP → écran de fermeture).
    //   = exactement le comportement du test navigateur qui marche (clientID neuf).
    private val strClientId: String = java.util.UUID.randomUUID().toString()

    // 2026-06-27 : DEUX sessions.
    //   - CATALOGUE (boot XFF=FR) → IDs FR (chaînes/films/séries). channels/vod hosts.
    //   - FLUX (boot SANS XFF = IP réelle Tahiti/VPN) → jwt/sid pour lire le stream,
    //     comme l'ancien FAST. Le stitcher sert selon l'ID, pas la région de session.
    @Volatile private var catToken: String? = null
    @Volatile private var catTs: Long = 0L
    @Volatile private var channelsHost: String = DEFAULT_CHANNELS
    @Volatile private var vodHost: String = DEFAULT_VOD
    @Volatile private var strToken: String? = null
    @Volatile private var strSid: String = ""
    @Volatile private var strTs: Long = 0L
    @Volatile private var stitcherHost: String = DEFAULT_STITCHER
    @Volatile private var stitcherDashHost: String = DEFAULT_STITCHER_DASH
    @Volatile private var conciergeHost: String = "https://service-concierge.clusters.pluto.tv"

    /** Le VOD Pluto est parfois chiffré Widevine (CENC). License = concierge/v1/wv/alt
     *  avec le jwt de session. Pour le contenu CLAIR, ExoPlayer ignore cette config. */
    fun getWidevineLicenseUrl(source: String): String? {
        if (!source.contains("pluto.tv") || !source.contains("/dash/")) return null
        val tok = strToken ?: return null
        return "$conciergeHost/v1/wv/alt?jwt=$tok"
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun isPlutoLiveUrl(src: String): Boolean = src.startsWith("plutolive://")
    fun isPlutoVodUrl(src: String): Boolean = src.startsWith("plutovod://")

    data class PlutoChannel(val id: String, val name: String, val number: Int, val logo: String)
    data class PlutoLiveCategory(val name: String, val channels: List<PlutoChannel>)
    data class PlutoVodItem(val id: String, val name: String, val thumb: String, val isSeries: Boolean)
    data class PlutoVodCategory(val name: String, val items: List<PlutoVodItem>)
    data class PlutoEpisode(
        val id: String, val name: String, val season: Int, val episode: Int,
        val thumb: String, val overview: String,
    )
    data class PlutoSeason(val number: Int, val episodes: List<PlutoEpisode>)

    private fun bootUrl(cid: String) = "$BOOT?appName=web&appVersion=9.9.0&deviceVersion=120.0.0.0" +
        "&deviceModel=web&deviceMake=Chrome&deviceType=web&clientID=$cid" +
        "&clientModelNumber=na&serverSideAds=false"

    /** Session CATALOGUE (XFF=FR) → IDs français. */
    private suspend fun ensureCatalogSession(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (catToken != null && now - catTs < SESSION_TTL_MS) return@withContext true
        try {
            // PAS de XFF (comme le navigateur) : on s'appuie sur l'IP réelle
            //   (VPN France). Le XFF créait un décalage région/IP qui bloquait le VOD.
            val req = Request.Builder().url(bootUrl(clientId))
                .header("Accept", "application/json")
                .header("User-Agent", CHROME_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "cat boot HTTP ${resp.code}"); return@withContext false }
                val j = JSONObject(resp.body?.string().orEmpty())
                val tok = j.optString("sessionToken")
                if (tok.isBlank()) return@withContext false
                catToken = tok
                val servers = j.optJSONObject("servers")
                channelsHost = servers?.optString("channels").orEmpty().ifBlank { DEFAULT_CHANNELS }.trimEnd('/')
                vodHost = servers?.optString("vod").orEmpty().ifBlank { DEFAULT_VOD }.trimEnd('/')
                catTs = now
                Log.d(TAG, "cat boot OK (FR)")
                true
            }
        } catch (e: Exception) { Log.w(TAG, "cat boot failed: ${e.message}"); false }
    }

    /** Session FLUX (SANS XFF = IP réelle) → jwt/sid pour lire, comme l'ancien FAST. */
    private suspend fun ensureStreamSession(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (strToken != null && now - strTs < SESSION_TTL_MS) return@withContext true
        try {
            val req = Request.Builder().url(bootUrl(strClientId))
                .header("Accept", "application/json")
                .header("User-Agent", CHROME_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "str boot HTTP ${resp.code}"); return@withContext false }
                val j = JSONObject(resp.body?.string().orEmpty())
                val tok = j.optString("sessionToken")
                if (tok.isBlank()) return@withContext false
                strToken = tok
                strSid = extractSid(tok)
                val servers = j.optJSONObject("servers")
                stitcherHost = servers?.optString("stitcher").orEmpty().ifBlank { DEFAULT_STITCHER }.trimEnd('/')
                stitcherDashHost = servers?.optString("stitcherDash").orEmpty().ifBlank { DEFAULT_STITCHER_DASH }.trimEnd('/')
                conciergeHost = servers?.optString("concierge").orEmpty().ifBlank { conciergeHost }.trimEnd('/')
                strTs = now
                Log.d(TAG, "str boot OK (réel)")
                true
            }
        } catch (e: Exception) { Log.w(TAG, "str boot failed: ${e.message}"); false }
    }

    private suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer ${catToken.orEmpty()}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET ${url.substringBefore('?')} HTTP ${resp.code}")
                    return@withContext null
                }
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            Log.w(TAG, "getJson failed: ${e.message}")
            null
        }
    }

    private suspend fun getJsonArray(url: String): org.json.JSONArray? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer ${catToken.orEmpty()}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string().orEmpty().trim()
                when {
                    body.startsWith("[") -> org.json.JSONArray(body)
                    body.startsWith("{") -> JSONObject(body).optJSONArray("items")
                        ?: JSONObject(body).optJSONArray("data")
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getJsonArray failed: ${e.message}")
            null
        }
    }

    private fun firstImage(o: JSONObject): String {
        // covers[] ou images[] : prend une URL exploitable
        o.optJSONArray("covers")?.let { for (i in 0 until it.length()) {
            val u = it.optJSONObject(i)?.optString("url").orEmpty(); if (u.isNotBlank()) return u } }
        o.optJSONArray("images")?.let { for (i in 0 until it.length()) {
            val u = it.optJSONObject(i)?.optString("url").orEmpty(); if (u.isNotBlank()) return u } }
        return o.optString("poster16_9").ifBlank { o.optString("featuredImage").let {
            if (it.isNotBlank()) it else "" } }
    }

    /** Catégories live FR (15) avec leurs chaînes. */
    suspend fun getLiveCategories(): List<PlutoLiveCategory> {
        if (!ensureCatalogSession()) return emptyList()
        val catsJson = getJson("$channelsHost/v2/guide/categories") ?: return emptyList()
        val chansJson = getJson("$channelsHost/v2/guide/channels?offset=0&limit=1000") ?: return emptyList()
        // index chaînes par id
        val chArr = chansJson.optJSONArray("data") ?: chansJson.optJSONArray("channels")
        val byId = HashMap<String, PlutoChannel>()
        if (chArr != null) for (i in 0 until chArr.length()) {
            val c = chArr.optJSONObject(i) ?: continue
            val id = c.optString("id").ifBlank { c.optString("_id") }
            val name = c.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            if (c.optBoolean("plutoOfficeOnly", false)) continue
            byId[id] = PlutoChannel(id, name, c.optInt("number", 0), firstImage(c))
        }
        val catArr = catsJson.optJSONArray("data") ?: catsJson.optJSONArray("categories")
            ?: return emptyList()
        val out = ArrayList<PlutoLiveCategory>()
        for (i in 0 until catArr.length()) {
            val cat = catArr.optJSONObject(i) ?: continue
            val name = cat.optString("name").trim().ifBlank { continue }
            val ids = cat.optJSONArray("channelIDs") ?: continue
            val list = ArrayList<PlutoChannel>()
            for (k in 0 until ids.length()) byId[ids.optString(k)]?.let { list.add(it) }
            if (list.isNotEmpty()) out.add(PlutoLiveCategory(name, list))
        }
        return out
    }

    /** Catégories VOD (films + séries mélangés, tagués isSeries). */
    suspend fun getVodCategories(): List<PlutoVodCategory> {
        if (!ensureCatalogSession()) return emptyList()
        val j = getJson("$vodHost/v4/vod/categories?includeItems=true&offset=0&limit=100")
            ?: return emptyList()
        val cats = j.optJSONArray("categories") ?: return emptyList()
        val out = ArrayList<PlutoVodCategory>()
        for (i in 0 until cats.length()) {
            val cat = cats.optJSONObject(i) ?: continue
            val name = cat.optString("name").trim()
            // skip carrousels génériques
            if (name.isBlank() || name.equals("Hero Carousel", true)) continue
            val items = cat.optJSONArray("items") ?: continue
            val list = ArrayList<PlutoVodItem>()
            for (k in 0 until items.length()) {
                val it = items.optJSONObject(k) ?: continue
                val id = it.optString("_id").ifBlank { it.optString("id") }
                val nm = it.optString("name").trim()
                if (id.isBlank() || nm.isBlank()) continue
                val isSeries = it.optString("type") == "series"
                list.add(PlutoVodItem(id, nm, firstImage(it), isSeries))
            }
            if (list.isNotEmpty()) out.add(PlutoVodCategory(name, list))
        }
        return out
    }

    /** Saisons + épisodes d'une série. */
    suspend fun getSeasons(seriesId: String): List<PlutoSeason> {
        if (!ensureCatalogSession()) return emptyList()
        val j = getJson("$vodHost/v4/vod/series/$seriesId/seasons?offset=0&limit=1000")
            ?: return emptyList()
        val seasons = j.optJSONArray("seasons") ?: return emptyList()
        val out = ArrayList<PlutoSeason>()
        for (i in 0 until seasons.length()) {
            val s = seasons.optJSONObject(i) ?: continue
            val num = s.optInt("number", i + 1)
            val eps = s.optJSONArray("episodes") ?: continue
            val list = ArrayList<PlutoEpisode>()
            for (k in 0 until eps.length()) {
                val e = eps.optJSONObject(k) ?: continue
                val id = e.optString("_id").ifBlank { e.optString("id") }
                if (id.isBlank()) continue
                list.add(PlutoEpisode(
                    id = id,
                    name = e.optString("name").ifBlank { "Épisode ${e.optInt("number", k + 1)}" },
                    season = e.optInt("season", num),
                    episode = e.optInt("number", k + 1),
                    thumb = firstImage(e),
                    overview = e.optString("description"),
                ))
            }
            if (list.isNotEmpty()) out.add(PlutoSeason(num, list))
        }
        return out
    }

    /** Décode un champ du payload JWT (pour diagnostic région). */
    private fun jwtField(jwt: String, field: String): String {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return "?"
            var payload = parts[1]
            payload += "=".repeat((4 - payload.length % 4) % 4)
            val decoded = String(android.util.Base64.decode(
                payload.replace('-', '+').replace('_', '/'), android.util.Base64.DEFAULT))
            JSONObject(decoded).optString(field, "?")
        } catch (e: Exception) { "?" }
    }

    /** Extrait le sessionID du payload (2e segment base64) du JWT. */
    private fun extractSid(jwt: String): String {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return clientId
            var payload = parts[1]
            payload += "=".repeat((4 - payload.length % 4) % 4)
            val decoded = String(
                android.util.Base64.decode(
                    payload.replace('-', '+').replace('_', '/'),
                    android.util.Base64.DEFAULT
                )
            )
            JSONObject(decoded).optString("sessionID", clientId)
        } catch (e: Exception) { clientId }
    }

    /** EXACTEMENT comme l'ancien résolveur live connu-bon (qui marche depuis
     *  Tahiti SANS VPN) : construit le master URL avec params device + sid + jwt,
     *  fetch le master CÔTÉ APP, parse la meilleure variante, et renvoie CETTE
     *  variante au lecteur (et pas le master brut). */
    private suspend fun streamFromPath(masterPath: String, noAds: Boolean = false): Video? = withContext(Dispatchers.IO) {
        // noAds (VOD) : serverSideAds=false → pas de créneaux pub SSAI (qui sinon
        //   insèrent l'écran de fermeture géolocalisé sur l'IP réelle). Le live
        //   N'EN a PAS besoin (il marche déjà) → on ne touche pas à son flux.
        val adsParam = if (noAds) "&serverSideAds=false" else ""
        val q = "advertisingId=&appName=web&appVersion=9.9.0&deviceDNT=0" +
            "&deviceId=$strClientId&deviceLat=0&deviceLon=0&deviceMake=Chrome" +
            "&deviceModel=web&deviceType=web&deviceVersion=120.0.0.0$adsParam" +
            "&sid=$strSid&jwt=${strToken.orEmpty()}"
        val masterUrl = "$stitcherHost$masterPath?$q"
        Log.w(TAG, "STREAM region=${jwtField(strToken.orEmpty(), "country")} ip=${jwtField(strToken.orEmpty(), "clientIP")} noAds=$noAds path=$masterPath")
        try {
            val req = Request.Builder().url(masterUrl).header("User-Agent", CHROME_UA).build()
            val master = client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "master HTTP ${r.code}"); return@withContext null }
                r.body?.string().orEmpty()
            }
            Log.w(TAG, "MASTER variants=${(master.split("#EXT-X-STREAM-INF").size - 1)} " +
                "unavailable=${Regex("unavailable|encerramos|det var|n[aã]o est|tillg", RegexOption.IGNORE_CASE).containsMatchIn(master)} " +
                "drm=${master.contains("EXT-X-KEY")}")
            // parse la meilleure variante (#EXT-X-STREAM-INF + URL ligne suivante)
            val lines = master.lines()
            var bestUrl: String? = null
            var bestBw = -1L
            for (i in lines.indices) {
                val l = lines[i].trim()
                if (l.startsWith("#EXT-X-STREAM-INF:")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val u = lines.getOrNull(i + 1)?.trim().orEmpty()
                    if (u.isNotEmpty() && !u.startsWith("#") && bw > bestBw) { bestBw = bw; bestUrl = u }
                }
            }
            // résout l'URL relative de la variante par rapport au master
            val variantResolved = bestUrl?.let {
                if (it.startsWith("http")) it else java.net.URL(java.net.URL(masterUrl), it).toString()
            } ?: masterUrl  // fallback : master direct si pas de variante parsée
            // CRUCIAL (comme l'ancien FAST) : réinjecte le jwt sur la variante,
            //   sinon la sous-playlist renvoie HTTP 401 (le jwt n'est que sur le master).
            val variant = if (variantResolved.contains("jwt=")) variantResolved
                else variantResolved + (if (variantResolved.contains("?")) "&" else "?") +
                    "jwt=${strToken.orEmpty()}"
            Log.d(TAG, "pluto variant ${if (variantResolved == masterUrl) "(master)" else "OK"} +jwt")
            Video(
                source = variant,
                type = "application/vnd.apple.mpegurl",
                headers = mapOf("User-Agent" to CHROME_UA),
            )
        } catch (e: Exception) {
            Log.w(TAG, "streamFromPath failed: ${e.message}")
            null
        }
    }

    /** Résout `plutolive://<channelId>` → Video HLS live. */
    suspend fun resolveLive(src: String): Video? {
        if (!ensureStreamSession()) return null
        val id = src.removePrefix("plutolive://").trim()
        if (id.isEmpty()) return null
        // chemin /v2/ EXACT de l'ancien résolveur live connu-bon.
        return streamFromPath("/v2/stitch/hls/channel/$id/master.m3u8")
    }

    /**
     * Résout `plutovod://e/<episodeId>` (path déterministe) ou
     * `plutovod://m/<movieId>` (fetch /items?ids → stitched.path).
     */
    suspend fun resolveVod(src: String): Video? {
        if (!ensureStreamSession()) return null
        val payload = src.removePrefix("plutovod://").trim()
        // VOD = film COMPLET via DASH `/v2/stitch/dash/episode/<id>/main.mpd`
        //   (host stitcherDash). L'endpoint HLS ne renvoyait qu'un clip de 25s
        //   (la carte de fin) → c'était LE bug. Le DASH = la vraie durée (film entier).
        val id = when {
            payload.startsWith("e/") -> payload.removePrefix("e/")
            payload.startsWith("m/") -> payload.removePrefix("m/")
            else -> ""
        }
        if (id.isEmpty()) return null
        return vodDash(id)
    }

    private fun vodDash(id: String): Video {
        val q = "appName=web&appVersion=9.9.0&deviceDNT=0&deviceId=$strClientId" +
            "&deviceLat=0&deviceLon=0&deviceMake=Chrome&deviceModel=web&deviceType=web" +
            "&deviceVersion=120.0.0.0&sid=$strSid&jwt=${strToken.orEmpty()}"
        val url = "$stitcherDashHost/v2/stitch/dash/episode/$id/main.mpd?$q"
        Log.w(TAG, "VOD DASH episode=$id")
        return Video(
            source = url,
            type = "application/dash+xml",
            headers = mapOf("User-Agent" to CHROME_UA),
        )
    }
}
