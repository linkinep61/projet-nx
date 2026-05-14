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
        return parseChannels(body)
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
            val pageItems = parseChannels(body)
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
            val pageItems = parseChannels(body)
            if (pageItems.isEmpty()) break
            all += pageItems
            Log.d(TAG, "Stalker Series page $page : +${pageItems.size} items (total $all.size)")
        }
        return all
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
        // Détecte le type d'endpoint à utiliser
        val endpoint = when {
            cmd.startsWith("vod::") -> {
                // Extract numeric id (handle "id" or "id:id" format)
                val rawId = cmd.removePrefix("vod::").substringBefore(":")
                Log.d(TAG, "createStreamLink VOD : id=$rawId (cmd=$cmd)")
                "type=vod&action=create_link&cmd=${java.net.URLEncoder.encode("/media/$rawId.mpg", "UTF-8")}&series=0&forced_storage=0&disable_ad=0"
            }
            cmd.startsWith("series::") -> {
                val rawId = cmd.removePrefix("series::").substringBefore(":")
                Log.d(TAG, "createStreamLink SERIES : id=$rawId (cmd=$cmd)")
                "type=series&action=create_link&cmd=${java.net.URLEncoder.encode("/media/$rawId.mpg", "UTF-8")}&series=1"
            }
            else -> "type=itv&action=create_link&cmd=${java.net.URLEncoder.encode(cmd, "UTF-8")}"
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
        u = u.removeSuffix("/portal.php")
        u = u.removeSuffix("/c")
        u = u.removeSuffix("/stalker_portal")
        return u
    }

    private fun parseChannels(body: String): List<StalkerChannel> {
        return try {
            val js = JSONObject(body).optJSONObject("js") ?: return emptyList()
            // 2 formats : `data` (array direct) ou `data.data` (paginated)
            val arr = js.optJSONArray("data")
                ?: js.optJSONObject("data")?.optJSONArray("data")
                ?: return emptyList()
            val out = mutableListOf<StalkerChannel>()
            for (i in 0 until arr.length()) {
                val ch = arr.optJSONObject(i) ?: continue
                // 2026-05-13 (user "il est censé avoir tout ça pourquoi y a pas
                // les séries") : pour VOD/Series Stalker le `cmd` est souvent
                // vide — l'item porte juste un `id` numérique. On stocke alors
                // un placeholder `vod::<id>` ou `series::<id>` qui sera résolu
                // au moment du play (besoin de créer un endpoint dédié dans
                // createStreamLink). On accepte aussi `o_name` (VOD-specific).
                val name = ch.optString("name", "")
                    .ifBlank { ch.optString("title", "") }
                    .ifBlank { ch.optString("o_name", "") }
                if (name.isBlank()) continue
                val cmdRaw = ch.optString("cmd", "")
                val id = ch.optString("id", "")
                val cmd = when {
                    cmdRaw.isNotBlank() -> cmdRaw
                    id.isNotBlank() -> "vod::$id" // placeholder, resolve via create_link au play
                    else -> { continue }
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
