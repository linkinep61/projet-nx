package com.streamflixreborn.streamflix.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-07-24 (user "les 2 chaînes Télé-Québec ne fonctionnent pas") : résolveur
 * Brightcove Live pour les chaînes protégées Widevine (Télé-Québec, Jeunesse).
 *
 * Diagnostic : les flux Brightcove de Télé-Québec sont chiffrés Widevine → jouer
 * l'URL HLS brute donne `UnsupportedDrmException`. Il faut passer par l'API
 * Brightcove Playback pour récupérer la source **DASH + l'URL de licence
 * Widevine**, puis laisser le DrmSessionManager existant (même pipeline que
 * M6/BFM) déchiffrer.
 *
 * Scheme M3U : `brightcove://<accountId>/<playerId>/<videoId>`
 *   ex Télé-Québec  : brightcove://6150020952001/R35LpOeJ4/6391088380112
 *   ex Jeunesse     : brightcove://6150020952001/R35LpOeJ4/6391090845112
 *
 * Pipeline (reverse-engineered en direct sur telequebec.tv) :
 *   1. GET players.brightcove.net/<acct>/<playerId>_default/index.min.js
 *      → extraction de la **policy key** publique (`BCpk...`) au runtime
 *      (pas de hardcode : si Télé-Québec la fait tourner, on suit).
 *   2. GET edge.api.brightcove.com/playback/v1/accounts/<acct>/videos/<videoId>
 *      header `Accept: application/json;pk=<policyKey>`
 *      → `sources[]` : on prend la source `application/dash+xml` dont
 *        `key_systems["com.widevine.alpha"].license_url` existe.
 *   3. Retourne { dashUrl, widevineLicenseUrl } → le player joue DASH+Widevine.
 */
object BrightcoveResolver {
    private const val TAG = "BrightcoveResolver"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class Resolved(
        val url: String,
        val mimeType: String,
        val widevineLicenseUrl: String?,
        val widevineHeaders: Map<String, String>?,
    )

    /** Cache stream URL → licence Widevine (lu par le player, pattern M6Resolver). */
    private val widevineLicenseCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun getWidevineLicenseUrl(streamUrl: String): String? = widevineLicenseCache[streamUrl]
    private val widevineHeadersCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    fun getWidevineHeaders(streamUrl: String): Map<String, String>? = widevineHeadersCache[streamUrl]

    /** Cache policy key par (account/player) — stable, évite de re-fetch le player JS. */
    private val policyKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun isBrightcoveUrl(url: String): Boolean = url.startsWith("brightcove://", ignoreCase = true)

    suspend fun resolveTyped(bcUrl: String): Resolved? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val path = bcUrl.removePrefix("brightcove://").trim().removePrefix("/")
            val parts = path.split("/").filter { it.isNotBlank() }
            if (parts.size < 3) {
                Log.w(TAG, "URL brightcove invalide (attendu acct/player/video) : $bcUrl")
                return@withContext null
            }
            val accountId = parts[0]
            val playerId = parts[1]
            val videoId = parts[2]

            val policyKey = policyKeyForPlayer(accountId, playerId) ?: run {
                Log.e(TAG, "policy key introuvable pour $accountId/$playerId")
                return@withContext null
            }

            val apiUrl = "https://edge.api.brightcove.com/playback/v1/accounts/$accountId/videos/$videoId"
            val body = httpGet(apiUrl, mapOf("Accept" to "application/json;pk=$policyKey")) ?: run {
                Log.e(TAG, "Playback API sans réponse pour $videoId")
                return@withContext null
            }
            val json = try { JSONObject(body) } catch (e: Exception) {
                Log.e(TAG, "Playback API JSON parse fail: ${body.take(160)}")
                return@withContext null
            }
            val sources = json.optJSONArray("sources") ?: run {
                Log.e(TAG, "Playback API sans sources (video $videoId)")
                return@withContext null
            }

            // Cherche une source DASH avec licence Widevine.
            var dashUrl: String? = null
            var licenseUrl: String? = null
            for (i in 0 until sources.length()) {
                val s = sources.optJSONObject(i) ?: continue
                val type = s.optString("type", "")
                if (!type.contains("dash", ignoreCase = true)) continue
                val ks = s.optJSONObject("key_systems") ?: continue
                val wv = ks.optJSONObject("com.widevine.alpha") ?: continue
                val lic = wv.optString("license_url", "").takeIf { it.startsWith("http") } ?: continue
                val src = s.optString("src", "").takeIf { it.startsWith("http") } ?: continue
                dashUrl = src
                licenseUrl = lic
                break
            }
            if (dashUrl == null || licenseUrl == null) {
                Log.e(TAG, "Aucune source DASH+Widevine pour video $videoId")
                return@withContext null
            }

            val headers = mapOf("User-Agent" to UA)
            widevineLicenseCache[dashUrl] = licenseUrl
            widevineHeadersCache[dashUrl] = headers
            Log.i(TAG, "✓ Brightcove résolu $videoId → DASH+Widevine (lic=${licenseUrl.take(40)}…)")
            Resolved(dashUrl, "application/dash+xml", licenseUrl, headers)
        } catch (e: Throwable) {
            Log.e(TAG, "resolveTyped crash: ${e.message}", e)
            null
        }
    }

    /** Récupère la policy key publique depuis le JS du player Brightcove (cache). */
    private fun policyKeyForPlayer(accountId: String, playerId: String): String? {
        val key = "$accountId/$playerId"
        policyKeyCache[key]?.let { return it }
        for (variant in listOf("index.min.js", "index.js")) {
            val jsUrl = "https://players.brightcove.net/$accountId/${playerId}_default/$variant"
            val js = httpGet(jsUrl, mapOf("Referer" to "https://players.brightcove.net/")) ?: continue
            val m = Regex("BCpk[A-Za-z0-9_\\-.]{20,}").find(js)?.value
            if (!m.isNullOrBlank()) {
                policyKeyCache[key] = m
                Log.d(TAG, "policy key récupérée ($accountId/$playerId, len=${m.length})")
                return m
            }
        }
        return null
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val b = Request.Builder().url(url).header("User-Agent", UA)
            headers.forEach { (k, v) -> b.header(k, v) }
            client.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "httpGet ${resp.code} sur ${url.take(60)}"); return null }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "httpGet fail ${url.take(60)}: ${e.message}")
            null
        }
    }
}
