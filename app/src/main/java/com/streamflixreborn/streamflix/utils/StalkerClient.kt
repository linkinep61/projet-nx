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
    ): Map<String, String> {
        val ua = userAgent ?: DEFAULT_UA
        val base = normalizeBase(portalUrl)
        val token = handshake(base, mac, ua, serial) ?: return emptyMap()
        val body = execPortal(base, "type=itv&action=get_genres", mac, ua, token, serial) ?: return emptyMap()
        return try {
            val js = JSONObject(body).optJSONArray("js") ?: return emptyMap()
            val out = mutableMapOf<String, String>()
            for (i in 0 until js.length()) {
                val o = js.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val title = o.optString("title", "")
                if (id.isNotEmpty() && title.isNotEmpty()) out[id] = title
            }
            Log.d(TAG, "Stalker genres : ${out.size} catégories nommées")
            out
        } catch (e: Exception) {
            Log.w(TAG, "getGenres parse fail: ${e.message}")
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

    /** Résout l'URL réelle d'une chaîne Stalker à la demande (au moment du play). */
    suspend fun createStreamLink(
        portalUrl: String,
        mac: String,
        cmd: String,
        userAgent: String? = null,
    ): String? {
        val ua = userAgent ?: DEFAULT_UA
        val base = normalizeBase(portalUrl)
        val token = handshake(base, mac, ua, null) ?: return null
        val body = execPortal(
            base,
            "type=itv&action=create_link&cmd=${java.net.URLEncoder.encode(cmd, "UTF-8")}",
            mac, ua, token,
        ) ?: return null
        return try {
            val js = JSONObject(body).optJSONObject("js")
            val cmdField = js?.optString("cmd", "") ?: ""
            // Format typique : "ffmpeg http://stream.url/play.m3u8" → on prend après l'espace
            cmdField.substringAfter(' ').takeIf { it.isNotEmpty() } ?: cmdField
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
                val name = ch.optString("name", ch.optString("title", ""))
                val cmd = ch.optString("cmd", "")
                if (name.isBlank() || cmd.isBlank()) continue
                out += StalkerChannel(
                    name = name,
                    cmd = cmd,
                    logo = ch.optString("logo", "").takeIf { it.isNotEmpty() }
                        ?: ch.optString("logo_url", "").takeIf { it.isNotEmpty() },
                    group = ch.optString("tv_genre_id", "").takeIf { it.isNotEmpty() }
                        ?: ch.optString("genre", "").takeIf { it.isNotEmpty() },
                    tvgId = ch.optString("xmltv_id", "").takeIf { it.isNotEmpty() },
                )
            }
            Log.d(TAG, "Parsed ${out.size} Stalker channels")
            out
        } catch (e: Exception) {
            Log.w(TAG, "parseChannels fail: ${e.message}, body=${body.take(200)}")
            emptyList()
        }
    }
}
