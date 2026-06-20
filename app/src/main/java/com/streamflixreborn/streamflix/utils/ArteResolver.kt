package com.streamflixreborn.streamflix.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Résolveur Arte+7 replay — port du pipeline Catchup TV & More en Kotlin.
 *
 * Pipeline :
 *   1. App reçoit URL `arte://<programId>` depuis le M3U replay
 *   2. Appel `https://api.arte.tv/api/player/v2/config/fr/<programId>`
 *      → retourne `data.attributes.streams[]`
 *   3. On choisit le 1er stream (généralement HLS, parfois DASH)
 *   4. ExoPlayer joue
 *
 * Note : pas de geoblock strict — Arte est multilingue européen, accessible
 * depuis la France métropole sans souci. Token Akamai signé sur IP cliente
 * mais valide ~6h.
 */
object ArteResolver {

    private const val TAG = "ArteResolver"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val URL_API = "https://api.arte.tv/api/player/v2/config/fr/%s"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    fun isArteUrl(url: String): Boolean = url.startsWith("arte://", ignoreCase = true)

    data class Resolved(val url: String, val mimeType: String)

    suspend fun resolveTyped(arteUrl: String): Resolved? {
        val pid = arteUrl.removePrefix("arte://").trim()
        if (pid.isEmpty()) {
            Log.w(TAG, "Empty programId in URL: $arteUrl")
            return null
        }
        val apiUrl = URL_API.format(pid)
        val resp = try {
            httpGetJson(apiUrl)
        } catch (e: Exception) {
            Log.e(TAG, "API fetch failed for $pid: ${e.message}", e)
            return null
        } ?: return null

        val data = resp.optJSONObject("data") ?: run {
            Log.w(TAG, "No data field for $pid")
            return null
        }
        val attrs = data.optJSONObject("attributes") ?: run {
            Log.w(TAG, "No attributes for $pid")
            return null
        }
        // Streams : choisir HLS en priorité
        val streams = attrs.optJSONArray("streams") ?: JSONArray()
        if (streams.length() == 0) {
            Log.w(TAG, "Empty streams for $pid")
            return null
        }
        var pickedUrl: String? = null
        var pickedProtocol = ""
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            val proto = s.optString("protocol", "")
            val url = s.optString("url", "")
            if (url.isBlank()) continue
            // Priorité HLS
            if (proto.contains("HLS", ignoreCase = true)) {
                pickedUrl = url
                pickedProtocol = proto
                break
            }
            // Sinon premier disponible
            if (pickedUrl == null) {
                pickedUrl = url
                pickedProtocol = proto
            }
        }
        if (pickedUrl == null) {
            Log.w(TAG, "No usable stream for $pid")
            return null
        }
        val mime = when {
            pickedProtocol.contains("HLS", ignoreCase = true)
                || pickedUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            pickedProtocol.contains("DASH", ignoreCase = true)
                || pickedUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
            else -> "video/*"
        }
        Log.d(TAG, "Resolved $pid ($pickedProtocol) → ${pickedUrl.take(80)}...")
        return Resolved(pickedUrl, mime)
    }

    private fun httpGetJson(url: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            // 2026-06-18 : pas d'Accept-Encoding manuel — OkHttp auto-décompresse
            //   uniquement quand le header est ABSENT. Sinon body retourné en
            //   gzip brut → JSONObject parse fail → "Invalid JSON: \b..."
            //   Même bug que TF1Resolver v21→v22.
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                Log.w(TAG, "Empty body HTTP ${resp.code} for $url")
                return null
            }
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid JSON: ${body.take(120)}")
                null
            }
        }
    }
}
