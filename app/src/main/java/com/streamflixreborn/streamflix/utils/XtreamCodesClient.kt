package com.streamflixreborn.streamflix.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-05-12 (user "VU IPTV pré-charge live/movies/series via Xtream API") :
 * client Xtream Codes player_api.php. Pattern utilisé par VU IPTV, TiviMate,
 * IPTVSmarters, etc. — beaucoup plus fiable que parser get.php?type=m3u_plus
 * et classifier nous-mêmes.
 *
 * Endpoints :
 * - `player_api.php?username=X&password=Y` → JSON `{user_info, server_info}`
 * - `player_api.php?username=X&password=Y&action=get_live_categories` → catégories TV
 * - `player_api.php?username=X&password=Y&action=get_live_streams[&category_id=N]` → chaînes live
 * - `player_api.php?username=X&password=Y&action=get_vod_categories` → catégories Films
 * - `player_api.php?username=X&password=Y&action=get_vod_streams[&category_id=N]` → films
 * - `player_api.php?username=X&password=Y&action=get_series_categories` → catégories Séries
 * - `player_api.php?username=X&password=Y&action=get_series[&category_id=N]` → séries (un par show)
 *
 * URL streams construites côté client :
 * - Live : `http://host:port/live/USER/PASS/STREAM_ID.ts` (ou `.m3u8`)
 * - VOD : `http://host:port/movie/USER/PASS/STREAM_ID.EXT` (ext = container_extension)
 * - Séries : `http://host:port/series/USER/PASS/EPISODE_ID.EXT`
 */
object XtreamCodesClient {
    private const val TAG = "XtreamCodes"

    private val client by lazy {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })
        val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .sslSocketFactory(sslCtx.socketFactory, trustAll[0] as javax.net.ssl.X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** URL Xtream parsée : `host:port`, `user`, `pass`. */
    data class XtreamCreds(
        val host: String,
        val user: String,
        val pass: String,
    ) {
        /** Base URL sans path. ex: `http://host:8080` */
        val baseUrl: String get() = host
    }

    data class XtreamCategory(val id: String, val name: String)

    data class XtreamLive(
        val streamId: String,
        val name: String,
        val logo: String?,
        val categoryId: String?,
        val epgChannelId: String?,
    ) {
        fun streamUrl(creds: XtreamCreds, ext: String = "ts"): String =
            "${creds.baseUrl}/live/${creds.user}/${creds.pass}/$streamId.$ext"
    }

    data class XtreamVod(
        val streamId: String,
        val name: String,
        val logo: String?,
        val categoryId: String?,
        val containerExtension: String?,
    ) {
        fun streamUrl(creds: XtreamCreds): String {
            val ext = containerExtension?.ifBlank { "mp4" } ?: "mp4"
            return "${creds.baseUrl}/movie/${creds.user}/${creds.pass}/$streamId.$ext"
        }
    }

    data class XtreamSeries(
        val seriesId: String,
        val name: String,
        val cover: String?,
        val categoryId: String?,
        val plot: String?,
    )

    data class XtreamEpisode(
        val id: String,
        val seriesId: String,
        val seasonNum: Int,
        val episodeNum: Int,
        val title: String,
        val containerExtension: String?,
    ) {
        fun streamUrl(creds: XtreamCreds): String {
            val ext = containerExtension?.ifBlank { "mp4" } ?: "mp4"
            return "${creds.baseUrl}/series/${creds.user}/${creds.pass}/$id.$ext"
        }
    }

    /** Parse une URL Xtream get.php → creds, ou null si format invalide. */
    fun parseCreds(url: String): XtreamCreds? {
        return try {
            val uri = java.net.URI(url)
            val baseUrl = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            val params = (uri.query ?: "").split('&').associate {
                val (k, v) = it.split('=', limit = 2).let { p ->
                    if (p.size == 2) p[0] to p[1] else p[0] to ""
                }
                k to java.net.URLDecoder.decode(v, "UTF-8")
            }
            val user = params["username"]?.takeIf { it.isNotBlank() } ?: return null
            val pass = params["password"]?.takeIf { it.isNotBlank() } ?: return null
            XtreamCreds(host = baseUrl, user = user, pass = pass)
        } catch (e: Exception) {
            Log.w(TAG, "parseCreds fail '$url': ${e.message}")
            null
        }
    }

    /** Ping `player_api.php?username=X&password=Y` pour vérifier que le serveur
     *  expose bien l'API Xtream. Retourne true si JSON contient `user_info`. */
    fun probeApi(creds: XtreamCreds, userAgent: String? = null): Boolean {
        val body = fetch(creds.baseUrl + "/player_api.php?username=${creds.user}&password=${creds.pass}", userAgent)
            ?: return false
        return try {
            JSONObject(body).has("user_info")
        } catch (_: Exception) { false }
    }

    fun getLiveCategories(creds: XtreamCreds, userAgent: String? = null): List<XtreamCategory> =
        fetchCategories(creds, "get_live_categories", userAgent)

    fun getVodCategories(creds: XtreamCreds, userAgent: String? = null): List<XtreamCategory> =
        fetchCategories(creds, "get_vod_categories", userAgent)

    fun getSeriesCategories(creds: XtreamCreds, userAgent: String? = null): List<XtreamCategory> =
        fetchCategories(creds, "get_series_categories", userAgent)

    /** Variante filtrée : appelle `get_live_streams&category_id=N` pour CHAQUE catégorie
     *  spécifiée. Beaucoup plus efficace que tout télécharger puis filtrer côté client. */
    fun getLiveStreamsForCategories(
        creds: XtreamCreds,
        categoryIds: Collection<String>,
        userAgent: String? = null,
    ): List<XtreamLive> {
        val out = mutableListOf<XtreamLive>()
        for (cid in categoryIds) {
            val arr = fetch(
                "${creds.baseUrl}/player_api.php?username=${creds.user}&password=${creds.pass}&action=get_live_streams&category_id=$cid",
                userAgent,
            )?.let { try { JSONArray(it) } catch (_: Exception) { null } } ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("stream_id", "").takeIf { it.isNotEmpty() } ?: continue
                out += XtreamLive(
                    streamId = id,
                    name = o.optString("name", "Sans nom"),
                    logo = o.optString("stream_icon", "").takeIf { it.isNotEmpty() },
                    categoryId = cid,
                    epgChannelId = o.optString("epg_channel_id", "").takeIf { it.isNotEmpty() },
                )
            }
        }
        Log.d(TAG, "Xtream live filtrée (${categoryIds.size} catégories) : ${out.size} chaînes")
        return out
    }

    fun getVodStreamsForCategories(
        creds: XtreamCreds,
        categoryIds: Collection<String>,
        userAgent: String? = null,
    ): List<XtreamVod> {
        val out = mutableListOf<XtreamVod>()
        for (cid in categoryIds) {
            val arr = fetch(
                "${creds.baseUrl}/player_api.php?username=${creds.user}&password=${creds.pass}&action=get_vod_streams&category_id=$cid",
                userAgent,
            )?.let { try { JSONArray(it) } catch (_: Exception) { null } } ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("stream_id", "").takeIf { it.isNotEmpty() } ?: continue
                out += XtreamVod(
                    streamId = id,
                    name = o.optString("name", "Sans nom"),
                    logo = o.optString("stream_icon", "").takeIf { it.isNotEmpty() },
                    categoryId = cid,
                    containerExtension = o.optString("container_extension", "").takeIf { it.isNotEmpty() },
                )
            }
        }
        Log.d(TAG, "Xtream VOD filtrée : ${out.size} films")
        return out
    }

    fun getSeriesForCategories(
        creds: XtreamCreds,
        categoryIds: Collection<String>,
        userAgent: String? = null,
    ): List<XtreamSeries> {
        val out = mutableListOf<XtreamSeries>()
        for (cid in categoryIds) {
            val arr = fetch(
                "${creds.baseUrl}/player_api.php?username=${creds.user}&password=${creds.pass}&action=get_series&category_id=$cid",
                userAgent,
            )?.let { try { JSONArray(it) } catch (_: Exception) { null } } ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("series_id", "").takeIf { it.isNotEmpty() } ?: continue
                out += XtreamSeries(
                    seriesId = id,
                    name = o.optString("name", "Sans nom"),
                    cover = o.optString("cover", "").takeIf { it.isNotEmpty() },
                    categoryId = cid,
                    plot = o.optString("plot", "").takeIf { it.isNotEmpty() },
                )
            }
        }
        Log.d(TAG, "Xtream series filtrée : ${out.size} shows")
        return out
    }

    fun getLiveStreams(creds: XtreamCreds, userAgent: String? = null): List<XtreamLive> {
        val arr = fetchArray(creds, "get_live_streams", userAgent) ?: return emptyList()
        val out = mutableListOf<XtreamLive>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("stream_id", "").takeIf { it.isNotEmpty() } ?: continue
            out += XtreamLive(
                streamId = id,
                name = o.optString("name", "Sans nom"),
                logo = o.optString("stream_icon", "").takeIf { it.isNotEmpty() },
                categoryId = o.optString("category_id", "").takeIf { it.isNotEmpty() },
                epgChannelId = o.optString("epg_channel_id", "").takeIf { it.isNotEmpty() },
            )
        }
        Log.d(TAG, "Xtream live: ${out.size} chaînes")
        return out
    }

    fun getVodStreams(creds: XtreamCreds, userAgent: String? = null): List<XtreamVod> {
        val arr = fetchArray(creds, "get_vod_streams", userAgent) ?: return emptyList()
        val out = mutableListOf<XtreamVod>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("stream_id", "").takeIf { it.isNotEmpty() } ?: continue
            out += XtreamVod(
                streamId = id,
                name = o.optString("name", "Sans nom"),
                logo = o.optString("stream_icon", "").takeIf { it.isNotEmpty() },
                categoryId = o.optString("category_id", "").takeIf { it.isNotEmpty() },
                containerExtension = o.optString("container_extension", "").takeIf { it.isNotEmpty() },
            )
        }
        Log.d(TAG, "Xtream VOD: ${out.size} films")
        return out
    }

    fun getSeries(creds: XtreamCreds, userAgent: String? = null): List<XtreamSeries> {
        val arr = fetchArray(creds, "get_series", userAgent) ?: return emptyList()
        val out = mutableListOf<XtreamSeries>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("series_id", "").takeIf { it.isNotEmpty() } ?: continue
            out += XtreamSeries(
                seriesId = id,
                name = o.optString("name", "Sans nom"),
                cover = o.optString("cover", "").takeIf { it.isNotEmpty() },
                categoryId = o.optString("category_id", "").takeIf { it.isNotEmpty() },
                plot = o.optString("plot", "").takeIf { it.isNotEmpty() },
            )
        }
        Log.d(TAG, "Xtream series: ${out.size} shows")
        return out
    }

    fun getSeriesInfo(creds: XtreamCreds, seriesId: String, userAgent: String? = null): List<XtreamEpisode> {
        val body = fetch(
            "${creds.baseUrl}/player_api.php?username=${creds.user}&password=${creds.pass}&action=get_series_info&series_id=$seriesId",
            userAgent,
        ) ?: return emptyList()
        return try {
            val episodes = JSONObject(body).optJSONObject("episodes") ?: return emptyList()
            val out = mutableListOf<XtreamEpisode>()
            episodes.keys().forEach { seasonKey ->
                val seasonNum = seasonKey.toIntOrNull() ?: 0
                val list = episodes.optJSONArray(seasonKey) ?: return@forEach
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    out += XtreamEpisode(
                        id = o.optString("id", ""),
                        seriesId = seriesId,
                        seasonNum = seasonNum,
                        episodeNum = o.optInt("episode_num", i + 1),
                        title = o.optString("title", "Episode ${i + 1}"),
                        containerExtension = o.optString("container_extension", "").takeIf { it.isNotEmpty() },
                    )
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "getSeriesInfo parse fail: ${e.message}")
            emptyList()
        }
    }

    // ───────── helpers ─────────

    private fun fetchCategories(creds: XtreamCreds, action: String, userAgent: String?): List<XtreamCategory> {
        val arr = fetchArray(creds, action, userAgent) ?: return emptyList()
        val out = mutableListOf<XtreamCategory>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += XtreamCategory(
                id = o.optString("category_id", "$i"),
                name = o.optString("category_name", "Sans nom"),
            )
        }
        return out
    }

    private fun fetchArray(creds: XtreamCreds, action: String, userAgent: String?): JSONArray? {
        val body = fetch(
            "${creds.baseUrl}/player_api.php?username=${creds.user}&password=${creds.pass}&action=$action",
            userAgent,
        ) ?: return null
        return try {
            // Xtream renvoie un JSONArray direct au top-level pour ces actions
            JSONArray(body)
        } catch (e: Exception) {
            Log.w(TAG, "fetchArray $action parse fail: ${e.message}, body=${body.take(200)}")
            null
        }
    }

    private fun fetch(url: String, userAgent: String?): String? {
        val ua = userAgent ?: "Mozilla/5.0 (Linux; U; Android 14; en-us; Pixel) AppleWebKit/534.30"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "application/json, */*")
            .header("Accept-Encoding", "identity")
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
            Log.w(TAG, "fetch $url fail: ${e.message}")
            null
        }
    }
}
