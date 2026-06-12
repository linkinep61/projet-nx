package com.streamflixreborn.streamflix.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 2026-06-08 (user "ajoute l'API avec plein de radios") : client pour
 * RadioBrowser API (https://www.radio-browser.info). Gratuit, sans quotas,
 * environ 5000+ stations FR disponibles. Filtre uniquement les stations
 * "click-ok" (= récemment validées) pour éviter les morts.
 *
 * Plusieurs mirrors disponibles, on en pick un au hasard pour load-balancer.
 */
object RadioBrowserClient {

    private const val TAG = "RadioBrowser"

    // Mirrors connus de l'API RadioBrowser. On rotate au cas où l'un est down.
    private val MIRRORS = listOf(
        "https://de1.api.radio-browser.info",
        "https://fr1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)  // 5000 stations = JSON gros
            .build()
    }

    data class BrowserStation(
        val uuid: String,
        val name: String,
        val url: String,
        val favicon: String?,
        val country: String?,
        val language: String?,
        val tags: String?,
        val votes: Int,
    )

    /** Fetch les stations FR (cap 5000 par défaut, triées par votes décroissants
     *  = popularité). Safe : retourne emptyList en cas d'échec.
     *  2026-06-08 (user "5000 radios FR j'en vois que 400") : cap monté de
     *  500 à 5000. La dedup par nom réduit ensuite (France Inter sur 5
     *  streams = 1 entrée). */
    suspend fun fetchFrenchStations(limit: Int = 5000): List<BrowserStation> =
        withContext(Dispatchers.IO) {
            val mirrors = MIRRORS.shuffled()
            for (base in mirrors) {
                try {
                    val url = "$base/json/stations/search?countrycode=FR" +
                        "&hidebroken=true&order=votes&reverse=true&limit=$limit"
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Streamflix/1.0")
                        .build()
                    val resp = client.newCall(req).execute()
                    resp.use {
                        if (!it.isSuccessful) return@use
                        val body = it.body?.string() ?: return@use
                        val arr = JSONArray(body)
                        val out = ArrayList<BrowserStation>(arr.length())
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val streamUrl = o.optString("url_resolved")
                                .ifBlank { o.optString("url") }
                            if (streamUrl.isBlank()) continue
                            val name = o.optString("name").trim()
                            if (name.isBlank()) continue
                            out.add(
                                BrowserStation(
                                    uuid = o.optString("stationuuid"),
                                    name = name,
                                    url = streamUrl,
                                    favicon = o.optString("favicon").takeIf { f -> f.isNotBlank() },
                                    country = o.optString("country").takeIf { c -> c.isNotBlank() },
                                    language = o.optString("language").takeIf { l -> l.isNotBlank() },
                                    tags = o.optString("tags").takeIf { t -> t.isNotBlank() },
                                    votes = o.optInt("votes", 0),
                                )
                            )
                        }
                        if (out.isNotEmpty()) {
                            Log.d(TAG, "fetched ${out.size} FR stations from $base")
                            return@withContext out
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "mirror $base failed: ${t.message}")
                }
            }
            emptyList()
        }
}
