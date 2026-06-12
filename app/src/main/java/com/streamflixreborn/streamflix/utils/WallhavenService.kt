package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 2026-06-09 (user "une API avec autant d'images ça doit être sympa et surtout
 * en 4K") : intégration de Wallhaven.cc, une API publique de wallpapers HD.
 *
 * Pas de clé API requise pour la lecture, juste un GET HTTP. Le service
 * supporte la recherche par mot-clé, la pagination et le filtrage SFW.
 *
 * Endpoint principal : https://wallhaven.cc/api/v1/search
 * Doc : https://wallhaven.cc/help/api
 */
object WallhavenService {
    private const val TAG = "WallhavenService"
    private const val API_BASE = "https://wallhaven.cc/api/v1/search"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class Wallpaper(
        val id: String,
        val thumbUrl: String,       // URL de la miniature (~300x200)
        val fullUrl: String,        // URL de l'image HD (4K+)
        val resolution: String,     // "1920x1080" etc.
        val colors: List<String>    // palette dominante (hex)
    )

    data class SearchResult(
        val wallpapers: List<Wallpaper>,
        val currentPage: Int,
        val lastPage: Int,
        val total: Int
    )

    /**
     * Recherche des wallpapers.
     * @param query texte libre (peut être vide → top wallpapers)
     * @param page pagination (1-based)
     * @param ratio "16x9" pour TV, "9x16" pour mobile portrait, null = tout
     */
    suspend fun search(
        query: String,
        page: Int = 1,
        ratio: String? = null
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        try {
            // categories : general(1) + anime(1) + people(0) = "110"
            // purity : SFW(1) + sketchy(0) + NSFW(0) = "100"
            // sorting : toplist (par défaut = relevance si query, sinon top)
            val urlBuilder = StringBuilder(API_BASE)
                .append("?categories=110")
                .append("&purity=100")
                .append("&sorting=")
                .append(if (query.isBlank()) "toplist" else "relevance")
                .append("&page=").append(page)
            if (ratio != null) urlBuilder.append("&ratios=").append(ratio)
            if (query.isNotBlank()) {
                urlBuilder.append("&q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            }

            val req = Request.Builder()
                .url(urlBuilder.toString())
                .header("User-Agent", "Streamflix-Wallpaper/1.0")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP ${resp.code}: ${resp.message}")
                    )
                }
                val body = resp.body?.string() ?: return@withContext Result.failure(
                    Exception("Empty body")
                )
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return@withContext Result.failure(
                    Exception("No 'data' field in response")
                )
                val list = mutableListOf<Wallpaper>()
                for (i in 0 until data.length()) {
                    val w = data.getJSONObject(i)
                    val thumbs = w.optJSONObject("thumbs")
                    val colorsArr = w.optJSONArray("colors")
                    val colors = mutableListOf<String>()
                    if (colorsArr != null) for (j in 0 until colorsArr.length()) {
                        colors.add(colorsArr.getString(j))
                    }
                    list.add(
                        Wallpaper(
                            id = w.optString("id"),
                            thumbUrl = thumbs?.optString("large") ?: w.optString("path"),
                            fullUrl = w.optString("path"),
                            resolution = w.optString("resolution", ""),
                            colors = colors
                        )
                    )
                }
                val meta = json.optJSONObject("meta")
                Result.success(
                    SearchResult(
                        wallpapers = list,
                        currentPage = meta?.optInt("current_page", page) ?: page,
                        lastPage = meta?.optInt("last_page", page) ?: page,
                        total = meta?.optInt("total", list.size) ?: list.size
                    )
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "search failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Télécharge l'image HD dans le cache local de l'app et retourne le File.
     * Le caller peut ensuite passer son Uri à AppearanceManager.setWallpaperUri.
     */
    suspend fun downloadToLocal(ctx: Context, wallpaper: Wallpaper): File? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(ctx.filesDir, "wallhaven").apply { mkdirs() }
                val ext = wallpaper.fullUrl.substringAfterLast(".").take(4)
                val outFile = File(dir, "wp_${wallpaper.id}.${ext}")
                if (outFile.exists() && outFile.length() > 0) return@withContext outFile

                val req = Request.Builder()
                    .url(wallpaper.fullUrl)
                    .header("User-Agent", "Streamflix-Wallpaper/1.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body ?: return@withContext null
                    FileOutputStream(outFile).use { fos ->
                        body.byteStream().copyTo(fos)
                    }
                }
                outFile
            } catch (e: Throwable) {
                Log.w(TAG, "downloadToLocal failed: ${e.message}")
                null
            }
        }
}
