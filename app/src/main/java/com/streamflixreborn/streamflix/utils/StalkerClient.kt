package com.streamflixreborn.streamflix.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-05-12 (user "Il faudra t'ajoutes a Qu'on puisse utiliser les adresses Mac Stalker") :
 * client Stalker MAG portal — version simplifiée pour récupérer la liste des chaînes.
 *
 * Protocole Stalker :
 * 1. Handshake : `GET /portal.php?type=stb&action=handshake&token=`
 *    → retourne JSON `{ "js": { "token": "..." } }`
 * 2. get_profile : `GET /portal.php?type=stb&action=get_profile`
 *    → enregistre la box, prépare le portail
 * 3. itv/get_all_channels : `GET /portal.php?type=itv&action=get_all_channels`
 *    → retourne la liste des chaînes au format JSON
 * 4. create_link : `GET /portal.php?type=itv&action=create_link&cmd=<channel_cmd>`
 *    → retourne l'URL réelle du stream (résolu à la demande)
 *
 * Headers requis :
 * - Cookie: mac=00:1A:79:XX:XX:XX
 * - Authorization: Bearer <token>
 * - User-Agent: Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 ...
 * - X-User-Agent: Model: MAG250; Link: WiFi
 *
 * Retour : List<StalkerChannel> avec stream cmd. Le URL réel est résolu plus tard
 * via [createStreamLink].
 */
object StalkerClient {
    private const val TAG = "StalkerClient"

    private val DEFAULT_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    private const val X_USER_AGENT = "Model: MAG250; Link: WiFi"

    private val client by lazy {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })
        val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .sslSocketFactory(sslCtx.socketFactory, trustAll[0] as javax.net.ssl.X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    data class StalkerChannel(
        val name: String,
        val cmd: String,
        val logo: String?,
        val group: String?,
        val tvgId: String?,
    )

    /** 2026-05-12 (user "Des numéros c'est pas parlant") : mapping category_id → nom
     *  via /portal.php?type=itv&action=get_genres. Stalker renvoie les category_id
     *  dans get_all_channels (e.g. "47", "46"), il faut faire ce 2e appel pour
     *  obtenir les vrais noms ("FR| GENERAL", "FR| CINEMA HD", etc.). */
    suspend fun getGenres(
        portalUrl: String,
        mac: String,
        userAgent: String? = null,
        serial: String? = null,
    ): Map<String, String> = fetchCategoriesGeneric(portalUrl, mac, userAgent, serial,
        "type=itv&action=get_genres", "ITV genres")

    /** 2026-05-13 (user "Les catégories film et n'apparaissent pas comme elles
     *  devraient apparaître") : fetch les noms de catégories VOD séparément
     *  (Stalker mappe les category_id différemment pour ITV vs VOD vs Series).
     *  Endpoint : type=vod&action=get_categories. */
    suspend fun getVodCategories(
        portalUrl: String,
        mac: String,
        userAgent: String? = null,
        serial: String? = null,
    ): Map<String, String> {
        // 2026-05-13 (user "toujours pareil") : essaie 2 noms d'endpoint
        // courants. Différents portails Stalker utilisent différents noms.
        val a = fetchCategoriesGeneric(portalUrl, mac, userAgent, serial,
            "type=vod&action=get_categories", "VOD categories")
        if (a.isNotEmpty()) return a
        val b = fetchCategoriesGeneric(portalUrl, mac, userAgent, serial,
            "type=vod&action=get_genres", "VOD genres (fallback)")
        if (b.isNotEmpty()) return b
        // Dernier essai : endpoint avec category= au lieu de get_categories
        return fetchCategoriesGeneric(portalUrl, mac, userAgent, serial,
            "type=vod&action=get_genres_for_category", "VOD genres_for_category (fallback 2)")
    }

    /** Fetch les noms de catégories Series. */
    suspend fun getSeriesCategories(
        portalUrl: String,
        mac: String,
        userAgent: String? = null,
        serial: String? = null,
    ): Map<String, String> {
        val a = fetchCategoriesGeneric(portalUrl, mac, userAgent, serial,
            "type=series&action=get_categories", "Series categories")
        if (a.isNotEmpty()) return a
        val b = fetchCategoriesGeneric(portalUrl, mac, userAgent, serial,
            "type=series&action=get_genres", "Series genres (fallback)")
        if (b.isNotEmpty()) return b
        return fetchCategoriesGeneric(portalUrl, mac, userAgent, serial,
            "type=series&action=get_genres_for_category", "Series genres_for_category (fallback 2)")
    }

    private suspend fun fetchCategoriesGeneric(
        portalUrl: String,
        mac: String,
        userAgent: String?,
        serial: String?,
        endpoint: String,
        label: String,
    ): Map<String, String> {
        val ua = userAgent ?: DEFAULT_UA
        val base = normalizeBase(portalUrl)
        val token = handshake(base, mac, ua, serial) ?: return emptyMap()
        val body = execPortal(base, endpoint, mac, ua, token, serial) ?: return emptyMap()
        return try {
            val js = JSONObject(body).optJSONArray("js") ?: return emptyMap()
            val out = mutableMapOf<String, String>()
            for (i in 0 until js.length()) {
                val o = js.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val title = o.optString("title", "").ifBlank { o.optString("name", "") }
                if (id.isNotEmpty() && title.isNotEmpty()) out[id] = title
            }
            Log.d(TAG, "Stalker $label : ${out.size} catégories nommées")
            out
        } catch (e: Exception) {
            Log.w(TAG, "$label parse fail: ${e.message}")
            emptyMap()
        }
    }

    /** Fetch toutes les chaînes d'un portail Stalker. */
    suspend fun getAllChannels(
        portalUrl: String,
        mac: String,
        userAgent: String? = null,
        serial: String? = null,
    ): List<StalkerChannel> {
        val ua = userAgent ?: DEFAULT_UA
        val base = normalizeBase(portalUrl)
        // 1. Handshake
        val token = handshake(base, mac, ua, serial) ?: run {
            Log.e(TAG, "Handshake failed")
            return emptyList()
        }
        Log.d(TAG, "Stalker handshake OK, token=${token.take(20)}…")

        // 2. get_profile (peut être skippé sur certains portails)
        runCatching {
            execPortal(base, "type=stb&action=get_profile", mac, ua, token)
        }.onFailure { Log.w(TAG, "get_profile fail: ${it.message}") }

        // 3. get_all_channels
        val body = execPortal(base, "type=itv&action=get_all_channels", mac, ua, token)
            ?: run {
                Log.e(TAG, "get_all_channels failed")
                return emptyList()
            }
        return parseChannels(body, type = "itv")
    }

    /** 2026-05-13 (user "rien en série alors je suis sûr il y en a plein") :
     *  fetch VOD (films) du portail Stalker. Endpoint type=vod. Pagine via
     *  get_ordered_list jusqu'à épuisement (max 5 pages = 500 items pour pas
     *  surcharger). */
    suspend fun getAllVod(
        portalUrl: String,
        mac: String,
        userAgent: String? = null,
        serial: String? = null,
        maxPages: Int = 20, // 20 × ~25 items = ~500 films max — bumpé pour les gros bouquets
    ): List<StalkerChannel> {
        val ua = userAgent ?: DEFAULT_UA
        val base = normalizeBase(portalUrl)
        val token = handshake(base, mac, ua, serial) ?: return emptyList()
        val all = mutableListOf<StalkerChannel>()
        for (page in 1..maxPages) {
            val body = execPortal(
                base,
                "type=vod&action=get_ordered_list&p=$page&genre=*&sortby=added",
                mac, ua, token,
            ) ?: break
            val pageItems = parseChannels(body, type = "vod")
            if (pageItems.isEmpty()) break
            all += pageItems
            Log.d(TAG, "Stalker VOD page $page : +${pageItems.size} items (total $all.size)")
        }
        return all
    }

    /** Fetch séries du portail Stalker. Endpoint type=series. */
    suspend fun getAllSeries(
        portalUrl: String,
        mac: String,
        userAgent: String? = null,
        serial: String? = null,
        maxPages: Int = 20, // 20 × ~25 items = ~500 séries max
    ): List<StalkerChannel> {
        val ua = userAgent ?: DEFAULT_UA
        val base = normalizeBase(portalUrl)
        val token = handshake(base, mac, ua, serial) ?: return emptyList()
        val all = mutableListOf<StalkerChannel>()
        for (page in 1..maxPages) {
            val body = execPortal(
                base,
                "type=series&action=get_ordered_list&p=$page&genre=*&sortby=added",
                mac, ua, token,
            ) ?: break
            val pageItems = parseChannels(body, type = "series")
            if (pageItems.isEmpty()) break
            all += pageItems
            Log.d(TAG, "Stalker Series page $page : +${pageItems.size} items (total $all.size)")
        }
        return all
    }

    /** 2026-05-14 (user "j'arrive pas à lire mes épisodes de série") : pour
     *  les Series Stalker, drill-down nécessaire :
     *   1. Series-level (cmd vide, id="X:X")
     *   2. → get_ordered_list?movie_id=X → Season (cmd Base64, id="X:N",
     *      series="123456" = épisodes 1,2,3,4,5,6 packés en string)
     *   3. → Play : type=vod&action=create_link&cmd=<season_cmd>&series=<ep_num>
     *
     *  Cette méthode fait l'étape 2 et retourne la liste des saisons. */
    data class StalkerSeason(
        val id: String,                  // "X:N" — format Stalker
        val name: String,                // "Season 1" ou nom custom
        val seasonNumber: Int,           // extracted from id ("X:N" → N)
        val cmd: String,                 // Base64 token à passer à create_link
        val episodeNumbers: List<Int>,   // épisodes dispos parsés depuis "series"
        val poster: String?,             // screenshot/affiche
    )

    suspend fun getSeasons(
        portalUrl: String,
        mac: String,
        seriesNumericId: String,         // ID série sans le ":" (ex "8305" pas "8305:8305")
        userAgent: String? = null,
        serial: String? = null,
    ): List<StalkerSeason> {
        val ua = userAgent ?: DEFAULT_UA
        val base = normalizeBase(portalUrl)
        val token = handshake(base, mac, ua, serial) ?: return emptyList()
        val body = execPortal(
            base,
            "type=series&action=get_ordered_list&movie_id=$seriesNumericId&season_id=0&episode_id=0&JsHttpRequest=1-xml",
            mac, ua, token,
        ) ?: return emptyList()
        return try {
            val js = JSONObject(body).optJSONObject("js") ?: return emptyList()
            val arr = js.optJSONArray("data")
                ?: js.optJSONObject("data")?.optJSONArray("data")
                ?: return emptyList()
            val out = mutableListOf<StalkerSeason>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                val cmd = obj.optString("cmd", "")
                if (cmd.isBlank()) continue
                val seasonNum = id.substringAfter(":", "1").toIntOrNull() ?: 1
                // Le champ "series" est soit "123456" (string packed), soit array Int.
                // On parse les 2 cas. Pour string : chaque char = un numéro d'épisode.
                val episodes = mutableListOf<Int>()
                val seriesRaw = obj.opt("series")
                when (seriesRaw) {
                    is org.json.JSONArray -> {
                        for (j in 0 until seriesRaw.length()) {
                            seriesRaw.optInt(j, -1).takeIf { it > 0 }?.let { episodes += it }
                        }
                    }
                    is String -> {
                        // "123456" → [1,2,3,4,5,6] ; "1,2,3,4,5,6,7,8,9,10" comma-separated
                        if (seriesRaw.contains(",")) {
                            seriesRaw.split(",").mapNotNull { it.trim().toIntOrNull() }
                                .forEach { if (it > 0) episodes += it }
                        } else if (seriesRaw.all { it.isDigit() }) {
                            seriesRaw.forEach { episodes += it.digitToInt() }
                        }
                    }
                    is Int -> {
                        // Packed int, ex 123456 → [1,2,3,4,5,6]
                        seriesRaw.toString().forEach { episodes += it.digitToInt() }
                    }
                }
                out += StalkerSeason(
                    id = id,
                    name = obj.optString("name", "").ifBlank { "Saison $seasonNum" },
                    seasonNumber = seasonNum,
                    cmd = cmd,
                    episodeNumbers = if (episodes.isEmpty()) listOf(1) else episodes,
                    poster = obj.optString("screenshot_uri", "").takeIf { it.isNotEmpty() },
                )
            }
            Log.d(TAG, "Stalker getSeasons($seriesNumericId) : ${out.size} saisons, total épisodes ${out.sumOf { it.episodeNumbers.size }}")
            out
        } catch (e: Exception) {
            Log.w(TAG, "getSeasons fail: ${e.message}")
            emptyList()
        }
    }

    /** Joue un épisode d'une saison Stalker. Le bon endpoint est `type=vod`
     *  (oui, vod pour les séries — c'est ainsi qu'agit le portail FoxBleu),
     *  avec cmd = saison.cmd (Base64) et series = numéro d'épisode. */
    suspend fun createStreamLinkSeriesEpisode(
        portalUrl: String,
        mac: String,
        seasonCmd: String,
        episodeNumber: Int,
        userAgent: String? = null,
        serial: String? = null,
    ): String? {
        val ua = userAgent ?: DEFAULT_UA
        val base = normalizeBase(portalUrl)
        val token = handshake(base, mac, ua, serial) ?: return null
        val endpoint = "type=vod&action=create_link&cmd=${java.net.URLEncoder.encode(seasonCmd, "UTF-8")}" +
            "&series=$episodeNumber&forced_storage=0&disable_ad=0&download=0&JsHttpRequest=1-xml"
        val body = execPortal(base, endpoint, mac, ua, token) ?: return null
        return try {
            val js = JSONObject(body).optJSONObject("js")
            val cmdField = js?.optString("cmd", "") ?: ""
            Log.d(TAG, "Series episode $episodeNumber resolved: ${cmdField.take(120)}")
            val resolved = cmdField.substringAfter(' ').takeIf { it.isNotEmpty() } ?: cmdField
            resolved.takeIf { it.isNotEmpty() && it.startsWith("http", ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "Series episode create_link parse fail: ${e.message}")
            null
        }
    }

    /** Résout l'URL réelle d'une chaîne Stalker à la demande (au moment du play).
     *  2026-05-13 : gère 3 cas :
     *    1. cmd commence par "vod::<id>" → endpoint VOD avec id
     *       Format id observé : "2450" ou "2450:2450" (Stalker peut dupliquer
     *       l'id séparé par ":"). On prend la 1ère partie numérique.
     *    2. cmd commence par "ffrt"/"ffmpeg" → endpoint live (ITV)
     *    3. cmd vide/inattendu → fallback ITV */
    suspend fun createStreamLink(
        portalUrl: String,
        mac: String,
        cmd: String,
        userAgent: String? = null,
    ): String? {
        val ua = userAgent ?: DEFAULT_UA
        val base = normalizeBase(portalUrl)
        val token = handshake(base, mac, ua, null) ?: return null
        // 2026-05-14 (user FoxBleu "les films ne sont pas lus") : 5 cas :
        //  - vod-cmd::<raw>   → VOD endpoint avec le cmd brut (Base64 FoxBleu
        //                       ou /media/file_xxx.mpg Stalker classique)
        //  - vod::<id>        → VOD endpoint, fabrique /media/<id>.mpg (fallback)
        //  - series-cmd::<raw> → SERIES endpoint avec cmd brut
        //  - series::<id>     → SERIES endpoint, fabrique /media/<id>.mpg
        //  - autre            → ITV endpoint (live)
        val endpoint = when {
            cmd.startsWith("vod-cmd::") -> {
                val rawCmd = cmd.removePrefix("vod-cmd::")
                Log.d(TAG, "createStreamLink VOD (raw cmd) : ${rawCmd.take(60)}")
                "type=vod&action=create_link&cmd=${java.net.URLEncoder.encode(rawCmd, "UTF-8")}&series=0&forced_storage=0&disable_ad=0"
            }
            cmd.startsWith("vod::") -> {
                // Legacy : extract numeric id ("id" or "id:id") → fabrique /media/<id>.mpg
                val rawId = cmd.removePrefix("vod::").substringBefore(":")
                Log.d(TAG, "createStreamLink VOD (legacy /media/.mpg) : id=$rawId")
                "type=vod&action=create_link&cmd=${java.net.URLEncoder.encode("/media/$rawId.mpg", "UTF-8")}&series=0&forced_storage=0&disable_ad=0"
            }
            cmd.startsWith("series-cmd::") -> {
                val rawCmd = cmd.removePrefix("series-cmd::")
                Log.d(TAG, "createStreamLink SERIES (raw cmd) : ${rawCmd.take(60)}")
                "type=series&action=create_link&cmd=${java.net.URLEncoder.encode(rawCmd, "UTF-8")}&series=1"
            }
            cmd.startsWith("series::") -> {
                val rawId = cmd.removePrefix("series::").substringBefore(":")
                Log.d(TAG, "createStreamLink SERIES (legacy) : id=$rawId")
                "type=series&action=create_link&cmd=${java.net.URLEncoder.encode("/media/$rawId.mpg", "UTF-8")}&series=1"
            }
            else -> {
                // 2026-05-14 (user "même les lives ne sont plus lus") : si le
                // cmd est DÉJÀ un URL complet playable (format
                // "ffmpeg http://serveur/...?...&stream=<id>&play_token=<x>")
                // et que l'host n'est PAS localhost, on l'utilise direct sans
                // passer par create_link. Certains portails (99976-fed) re-
                // génèrent un cmd avec stream=vide quand on leur renvoie le
                // cmd complet en URL-encoded, cassant la lecture.
                val urlPart = cmd.substringAfter(' ').takeIf { it.startsWith("http", ignoreCase = true) }
                if (urlPart != null &&
                    !urlPart.contains("localhost", ignoreCase = true) &&
                    !urlPart.contains("127.0.0.1") &&
                    urlPart.contains("stream=") &&
                    !urlPart.contains("stream=&") &&
                    !urlPart.endsWith("stream=")) {
                    Log.d(TAG, "createStreamLink LIVE : URL déjà complète, skip create_link → ${urlPart.take(80)}")
                    return urlPart
                }
                "type=itv&action=create_link&cmd=${java.net.URLEncoder.encode(cmd, "UTF-8")}"
            }
        }
        val body = execPortal(base, endpoint, mac, ua, token) ?: run {
            Log.w(TAG, "createStreamLink: empty response for endpoint=$endpoint")
            return null
        }
        return try {
            val js = JSONObject(body).optJSONObject("js")
            val cmdField = js?.optString("cmd", "") ?: ""
            Log.d(TAG, "createStreamLink response cmd=${cmdField.take(120)}")
            // Format typique : "ffmpeg http://stream.url/play.m3u8" → on prend après l'espace
            val resolved = cmdField.substringAfter(' ').takeIf { it.isNotEmpty() } ?: cmdField
            resolved.takeIf { it.isNotEmpty() && it.startsWith("http", ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "create_link parse fail: ${e.message}")
            null
        }
    }

    private fun handshake(base: String, mac: String, ua: String, serial: String?): String? {
        val body = execPortal(base, "type=stb&action=handshake&token=&JsHttpRequest=1-xml", mac, ua, null, serial)
            ?: return null
        return try {
            JSONObject(body).optJSONObject("js")?.optString("token", null)?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "handshake parse fail: ${e.message}, body=${body.take(120)}")
            null
        }
    }

    private fun execPortal(
        base: String, query: String, mac: String, ua: String, token: String?, serial: String? = null,
    ): String? {
        val url = "$base/portal.php?$query"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("X-User-Agent", X_USER_AGENT)
            .header("Accept", "*/*")
            .header("Cookie", buildCookie(mac, serial))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP ${resp.code} sur $url")
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "execPortal fail $url: ${e.message}")
            null
        }
    }

    private fun buildCookie(mac: String, serial: String?): String {
        val sb = StringBuilder()
        sb.append("mac=${java.net.URLEncoder.encode(mac, "UTF-8")}; ")
        sb.append("stb_lang=fr; timezone=Europe/Paris")
        if (!serial.isNullOrBlank()) sb.append("; sn=$serial")
        return sb.toString()
    }

    /** Normalise base : strip /portal.php, trailing / etc. → http://host:port */
    private fun normalizeBase(url: String): String {
        var u = url.trim().trimEnd('/')
        // 2026-05-14 : strip les double-prefixes http://http:// (sources mal
        // saisies). Filet de sécu en plus du sanitizeUrl côté provider.
        while (u.startsWith("http://http://", ignoreCase = true)) u = u.removePrefix("http://")
        while (u.startsWith("https://https://", ignoreCase = true)) u = u.removePrefix("https://")
        while (u.startsWith("http://https://", ignoreCase = true)) u = u.removePrefix("http://")
        while (u.startsWith("https://http://", ignoreCase = true)) u = u.removePrefix("https://")
        u = u.removeSuffix("/portal.php")
        u = u.removeSuffix("/c")
        u = u.removeSuffix("/stalker_portal")
        return u
    }

    private fun parseChannels(body: String, type: String = "itv"): List<StalkerChannel> {
        return try {
            val js = JSONObject(body).optJSONObject("js") ?: return emptyList()
            // 2 formats : `data` (array direct) ou `data.data` (paginated)
            val arr = js.optJSONArray("data")
                ?: js.optJSONObject("data")?.optJSONArray("data")
                ?: return emptyList()
            val out = mutableListOf<StalkerChannel>()
            for (i in 0 until arr.length()) {
                val ch = arr.optJSONObject(i) ?: continue
                // 2026-05-14 (user FoxBleu portal "les films et les séries ne
                // sont pas lus") : le cmd VOD/Series peut être :
                //  - Stalker classique : "/media/file_<id>.mpg" → on relit
                //  - FoxBleu/variants : token Base64 opaque qu'on relit tel quel
                //  - Vide (pas de cmd dans get_ordered_list) → fallback /media/<id>.mpg
                // On utilise un préfixe pour signaler à createStreamLink quel
                // endpoint utiliser ET si on a un cmd réel ou juste un id.
                val name = ch.optString("name", "")
                    .ifBlank { ch.optString("title", "") }
                    .ifBlank { ch.optString("o_name", "") }
                if (name.isBlank()) continue
                val cmdRaw = ch.optString("cmd", "")
                val id = ch.optString("id", "")
                val cmd = when (type) {
                    "vod" -> when {
                        cmdRaw.isNotBlank() -> "vod-cmd::$cmdRaw" // cmd réel (Base64 FoxBleu ou /media/...)
                        id.isNotBlank() -> "vod::$id"             // fallback : fabriquera /media/<id>.mpg
                        else -> continue
                    }
                    "series" -> when {
                        // 2026-05-15 (user FoxBleu "les séries ne sont pas lus") :
                        // PRIORITÉ à l'id numérique pour le drill-down. Le cmd
                        // brut au niveau série n'est PAS jouable directement
                        // (il faut d'abord get_ordered_list?movie_id=<id> pour
                        // récupérer les saisons puis create_link sur la saison).
                        // Avant, on prenait le cmd brut → MyIptvProvider.getVideo
                        // ne pouvait pas extraire l'id numérique → drill-down KO.
                        id.isNotBlank() -> "series::$id"
                        cmdRaw.isNotBlank() -> "series-cmd::$cmdRaw"
                        else -> continue
                    }
                    else -> when {
                        cmdRaw.isNotBlank() -> cmdRaw
                        id.isNotBlank() -> "vod::$id" // safety, ancien comportement
                        else -> continue
                    }
                }
                // 2026-05-13 (user "Les catégories n'apparaissent pas comme
                // elles devraient") : préfère les noms friendly (category_name,
                // category_title) avant les IDs numériques (category_id,
                // tv_genre_id). Si seulement l'ID est présent, sera mappé
                // par fetchStalker via la map dédiée par type.
                val groupName = ch.optString("category_name", "").takeIf { it.isNotEmpty() }
                    ?: ch.optString("category_title", "").takeIf { it.isNotEmpty() }
                    ?: ch.optString("genre", "").takeIf { it.isNotEmpty() }
                    ?: ch.optString("category_id", "").takeIf { it.isNotEmpty() }
                    ?: ch.optString("tv_genre_id", "").takeIf { it.isNotEmpty() }
                out += StalkerChannel(
                    name = name,
                    cmd = cmd,
                    logo = ch.optString("logo", "").takeIf { it.isNotEmpty() }
                        ?: ch.optString("logo_url", "").takeIf { it.isNotEmpty() }
                        ?: ch.optString("screenshot_uri", "").takeIf { it.isNotEmpty() },
                    group = groupName,
                    tvgId = ch.optString("xmltv_id", "").takeIf { it.isNotEmpty() },
                )
            }
            Log.d(TAG, "Parsed ${out.size} Stalker items")
            out
        } catch (e: Exception) {
            Log.w(TAG, "parseChannels fail: ${e.message}, body=${body.take(200)}")
            emptyList()
        }
    }
}
