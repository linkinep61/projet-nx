package com.streamflixreborn.streamflix.providers

import android.util.Log
import android.webkit.CookieManager
import com.google.gson.Gson
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * WebflixProvider — backup NATIF par tmdbId (portage de webflix.js, 2026-07-04).
 *
 * Site : https://webflix.art (SPA React). API JSON same-origin : /api/fastflux.
 *   - Film  : /api/fastflux?type=movie&tmdb_id=<id>
 *   - Série : /api/fastflux?type=series&tmdb_id=<id>&season=<s>&episode=<e>
 * Réponse (validée Chrome) : { success, available, data:{ url, quality, language, ... } }.
 * Le contenu est un MP4 VF DIRECT sur cdn.drinkoflix.lol (CF-protégé) → à la lecture on
 * résout le cf_clearance (WebViewResolver) + STEALTH_UA, comme NakiosProvider.
 *
 * Source BACKUP uniquement (pas browsable) : appelée par le registre central par tmdbId.
 * L'API exige un header Origin: https://webflix.art (même contrainte que Nakios).
 */
object WebflixProvider {
    private const val TAG = "WebflixProvider"
    private const val BASE = "https://webflix.art"
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class FastfluxResponse(
        val success: Boolean = false,
        val available: Boolean = false,
        val data: FastfluxData? = null,
    )

    private data class FastfluxData(
        val url: String? = null,
        val file: String? = null,
        val src: String? = null,
        val quality: String? = null,
        val language: String? = null,
        val lang: String? = null,
    )

    /**
     * Backup par tmdbId. Retourne 0 ou 1 serveur (MP4 VF direct). N'émet que si
     * success && available && une URL http est présente.
     */
    suspend fun fetchWebflixBackupServers(
        tmdbId: String,
        videoType: Video.Type,
        season: Int = 0,
        episode: Int = 0,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        if (tmdbId.isBlank() || !tmdbId.all { it.isDigit() }) return@withContext emptyList()
        val query = when (videoType) {
            is Video.Type.Movie -> "type=movie&tmdb_id=$tmdbId"
            is Video.Type.Episode -> {
                if (season <= 0 || episode <= 0) return@withContext emptyList()
                "type=series&tmdb_id=$tmdbId&season=$season&episode=$episode"
            }
        }
        val url = "$BASE/api/fastflux?$query"
        val body = try {
            val req = Request.Builder()
                .url(url)
                .header("Origin", BASE)
                .header("Referer", "$BASE/")
                .header("User-Agent", WebViewResolver.STEALTH_UA)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "GET $url → HTTP ${resp.code}"); return@withContext emptyList() }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchWebflixBackupServers failed: ${e.message}")
            return@withContext emptyList()
        } ?: return@withContext emptyList()

        val resp = try { gson.fromJson(body, FastfluxResponse::class.java) } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${e.message}"); return@withContext emptyList()
        }
        if (resp == null || !resp.success || !resp.available || resp.data == null) return@withContext emptyList()
        val src = (resp.data.url ?: resp.data.file ?: resp.data.src)
            ?.takeIf { it.startsWith("http") } ?: return@withContext emptyList()
        val quality = resp.data.quality?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
        val lang = (resp.data.language ?: resp.data.lang)?.takeIf { it.isNotBlank() } ?: "VF"
        val label = buildString {
            append("Webflix")
            quality?.let { append(" $it") }
            append(" [$lang]")
        }
        listOf(Video.Server(id = "webflix_backup_$tmdbId", name = label, src = src))
    }

    /** Lecture : le MP4 est sur cdn.drinkoflix.lol (Cloudflare) → cf_clearance + STEALTH_UA. */
    suspend fun getVideo(server: Video.Server): Video {
        val src = server.src
        val cookie = resolveCfClearance(src)
        val h = HashMap<String, String>()
        h["User-Agent"] = WebViewResolver.STEALTH_UA
        h["Referer"] = "$BASE/"
        if (!cookie.isNullOrBlank()) h["Cookie"] = cookie
        return Video(source = src, type = androidx.media3.common.MimeTypes.VIDEO_MP4, headers = h)
    }

    /** Récupère un cf_clearance DÉJÀ présent pour le CDN drinkoflix. */
    private fun resolveCfClearance(url: String): String? {
        // 2026-07-04 (user "Webflix fonctionne mais TRÈS lent à se lancer") : on NE fait PLUS le
        //   bypass WebView sur la RACINE du CDN (cdn.drinkoflix.lol/). Ce n'est pas une page
        //   challenge Cloudflare → la WebView HANG jusqu'au "Global Timeout" (~30s), monopolise
        //   la WebView, et le MP4 se lit DE TOUTE FAÇON avec juste le STEALTH_UA (pass-through,
        //   vu dans le log). On se contente donc d'un cf_clearance déjà posé par un autre appel,
        //   sinon null → lecture directe immédiate, plus aucune attente de 30s.
        return try {
            CookieManager.getInstance().getCookie(url)?.takeIf { it.contains("cf_clearance") }
        } catch (e: Exception) {
            Log.w(TAG, "resolveCfClearance failed for $url: ${e.message}"); null
        }
    }
}
