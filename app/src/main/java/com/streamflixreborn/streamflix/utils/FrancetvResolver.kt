package com.streamflixreborn.streamflix.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Résolveur France.tv replay — port du pipeline Catchup TV & More en Kotlin natif.
 *
 * Pipeline (reverse-engineered) :
 *   1. App reçoit URL `francetv://<si_id>` depuis le M3U replay
 *   2. Appel `https://k7.ftven.fr/videos/<si_id>?country_code=FR&capabilities=drm
 *       &os=androidtv&diffusion_mode=tunnel_first&offline=false`
 *      → retourne `video.url` (= URL master) + `video.token` (= URL TA) + `video.format`
 *   3. Appel `<video.token>?format=json&url=<video.url>`
 *      → retourne `{ "url": "<URL HLS finale signée>" }`
 *   4. L'URL HLS finale est jouable par ExoPlayer
 *
 * ⚠️ Étapes 2 et 3 DOIVENT être faites depuis l'IP user (= FR métropole).
 * Le JWT HLS est signé sur l'IP cliente. Si depuis hors FR → token invalide.
 */
object FrancetvResolver {

    private const val TAG = "FrancetvResolver"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; AndroidTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val URL_VIDEO_INFO = "https://k7.ftven.fr/videos/%s"
    private const val URL_TOKEN_FALLBACK = "https://hdfauth.ftven.fr/esi/TA"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** True si l'URL est de la forme `francetv://<si_id>`. */
    fun isFrancetvUrl(url: String): Boolean =
        url.startsWith("francetv://", ignoreCase = true)

    /** Résultat de résolution : URL finale + type MIME pour ExoPlayer. */
    data class Resolved(val url: String, val mimeType: String)

    /**
     * Résout une URL `francetv://<si_id>` en URL HLS ou DASH jouable.
     * Retourne null si la résolution échoue (= log explicite).
     */
    suspend fun resolveTyped(francetvUrl: String): Resolved? {
        val siId = francetvUrl.removePrefix("francetv://").trim()
        if (siId.isEmpty()) {
            Log.w(TAG, "Empty si_id in URL: $francetvUrl")
            return null
        }
        // Étape 1 : video info
        val videoInfoUrl = String.format(URL_VIDEO_INFO, siId) +
            "?country_code=FR&capabilities=drm&os=androidtv" +
            "&diffusion_mode=tunnel_first&offline=false"
        val videoInfo = try {
            httpGetJson(videoInfoUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Step 1 (video info) failed: ${e.message}", e)
            return null
        } ?: return null

        // Réponse d'erreur ?
        val code = videoInfo.optInt("code", 0)
        if (code != 0) {
            Log.w(TAG, "France.tv error code=$code message='${videoInfo.optString("message")}' for si_id=$siId")
            return null
        }

        val video = videoInfo.optJSONObject("video") ?: run {
            Log.w(TAG, "No 'video' field in response for $siId")
            return null
        }
        val format = video.optString("format", "").lowercase()
        val rawUrl = video.optString("url", "")
        if (rawUrl.isEmpty()) {
            Log.w(TAG, "Empty video.url for $siId")
            return null
        }
        // 2026-06-17 v2 (user "l'app devrait accepter n'importe quoi") :
        //   on ne rejette plus rien — DRM/format inconnu = on tente quand
        //   même. ExoPlayer auto-détecte le format depuis l'extension.
        val drm = video.optBoolean("drm", false)
        if (drm) {
            Log.w(TAG, "DRM=true for $siId — tentative quand même (Widevine peut échouer)")
        }
        // Token URL : video.token (= akamai / drm / fallback)
        val tokenUrl = when (val tk = video.opt("token")) {
            is String -> tk
            is JSONObject -> tk.optString("akamai", tk.optString("drm", URL_TOKEN_FALLBACK))
            else -> URL_TOKEN_FALLBACK
        }
        val isHls = format.contains("hls")
        val isDash = format.contains("dash")
        val finalUrl = try {
            val encodedRaw = URLEncoder.encode(rawUrl, "UTF-8")
            // 2026-06-17 v5 : tokenUrl peut déjà contenir "?format=json" →
            //   utiliser "&" si query existe, sinon "?"
            val sep = if (tokenUrl.contains("?")) "&" else "?"
            val tokenCallUrl = "$tokenUrl${sep}format=json&url=$encodedRaw"
            httpGetUrlOrJson(tokenCallUrl) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Step 2 (token) failed: ${e.message}", e)
            return null
        }
        if (finalUrl.isEmpty()) {
            Log.w(TAG, "Empty final URL from token for $siId")
            return null
        }
        // Détection mime : si format inconnu, on devine via l'URL.
        val mimeType = when {
            isDash || finalUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
            isHls || finalUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            else -> "video/*"  // ExoPlayer auto-detect
        }
        Log.d(TAG, "Resolved $siId (format=$format mime=$mimeType) → ${finalUrl.take(80)}...")
        return Resolved(finalUrl, mimeType)
    }

    /** Variante compat ancien code : retourne juste l'URL (= HLS only). */
    suspend fun resolve(francetvUrl: String): String? {
        val r = resolveTyped(francetvUrl) ?: return null
        return r.url
    }

    private fun httpGetJson(url: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} for $url")
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid JSON for $url: ${body.take(120)}")
                null
            }
        }
    }

    /** 2026-06-17 v5 : le tokenUrl peut retourner soit JSON {"url": "..."},
     *  soit une URL CDN brute en plain text. On accepte les deux. */
    private fun httpGetUrlOrJson(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty().trim()
            if (body.isBlank()) {
                Log.w(TAG, "Empty body for $url (HTTP ${resp.code})")
                return null
            }
            // Cas 1 : JSON avec "url" champ
            if (body.startsWith("{")) {
                return try {
                    val j = JSONObject(body)
                    j.optString("url", null) ?: run {
                        Log.w(TAG, "JSON sans champ 'url' pour $url")
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "JSON parse failed: ${body.take(100)}")
                    null
                }
            }
            // Cas 2 : URL plain text directe
            if (body.startsWith("http")) {
                return body
            }
            Log.w(TAG, "Réponse inattendue pour $url: ${body.take(100)}")
            return null
        }
    }
}
