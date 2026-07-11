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
            .connectTimeout(5, TimeUnit.SECONDS)   // 2026-07-10 : réduit de 10s (4 mirrors séquentiels = 40s total connect)
            .readTimeout(20, TimeUnit.SECONDS)      // 2026-07-10 : réduit de 45s (2000 stations = JSON raisonnable)
            .callTimeout(30, TimeUnit.SECONDS)      // 2026-07-10 : réduit de 60s
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

    // 2026-07-10 : fallback GitHub — fichier JSON statique hébergé sur nx-data,
    // rechargeable par cron. Utilisé quand TOUS les miroirs RadioBrowser échouent
    // (DNS bloqué par AdGuard/Fritz!Box sur le réseau du user).
    private const val GITHUB_FALLBACK =
        "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data/radio-fr.json"

    /** Fetch les stations FR (cap 2000 par défaut, triées par votes décroissants
     *  = popularité). Safe : retourne emptyList en cas d'échec.
     *  2026-06-08 (user "5000 radios FR j'en vois que 400") : cap monté de
     *  500 à 5000. La dedup par nom réduit ensuite (France Inter sur 5
     *  streams = 1 entrée).
     *  2026-07-10 : réduit de 5000 à 2000 — le user dit "1800 chaînes" =
     *  c'est assez après dedup, et le JSON plus petit réduit le risque de
     *  timeout/OOM sur Chromecast (2 Go RAM).
     *  2026-07-10 : ajouté fallback GitHub (nx-data/radio-fr.json) si tous
     *  les miroirs RadioBrowser échouent (DNS bloqué chez le user). */
    suspend fun fetchFrenchStations(limit: Int = 2000): List<BrowserStation> =
        withContext(Dispatchers.IO) {
            // 1) Tenter les miroirs RadioBrowser officiels
            val mirrors = MIRRORS.shuffled()
            for (base in mirrors) {
                val result = tryFetchFromUrl(
                    "$base/json/stations/search?countrycode=FR" +
                        "&hidebroken=true&order=votes&reverse=true&limit=$limit",
                    base
                )
                if (result.isNotEmpty()) return@withContext result
            }
            // 2) Fallback : fichier JSON statique sur GitHub (nx-data)
            Log.d(TAG, "All RadioBrowser mirrors failed, trying GitHub fallback...")
            val ghResult = tryFetchFromUrl(GITHUB_FALLBACK, "GitHub nx-data")
            if (ghResult.isNotEmpty()) return@withContext ghResult
            emptyList()
        }

    /** Parse un JSON de stations depuis une URL (RadioBrowser API ou GitHub fallback).
     *  Retourne emptyList si ça échoue. */
    private fun tryFetchFromUrl(url: String, label: String): List<BrowserStation> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Streamflix/1.0")
                .build()
            val resp = client.newCall(req).execute()
            resp.use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "mirror $label failed: HTTP ${it.code}")
                    return emptyList()
                }
                val body = it.body?.string() ?: return emptyList()
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
                            uuid = o.optString("stationuuid")
                                .ifBlank { "gh-${name.hashCode().toUInt()}" },
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
                    Log.d(TAG, "fetched ${out.size} FR stations from $label")
                }
                out
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mirror $label failed: ${t.message}")
            emptyList()
        }
    }
}
