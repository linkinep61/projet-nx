package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 2026-10-22 — MegacloudX, le lecteur de Cineby : `megacloudx.net/mv/{imdb}/{tmdb}/` (films) ou
 * `/pl/{tmdb}/{saison}/{épisode}/` (séries) répond une REDIRECTION vers un lecteur Byse/Filemoon
 * `…/e/{code}?sub.info=<json des sous-titres>`. Mesuré sur 11 titres : 9 ont de l'anglais,
 * 5 du français (Superman 2025 : 36 langues ; Squid Game / Meteor Garden : aucune).
 * On ne propose le serveur que si le JSON contient du français ou de l'anglais (traduit
 * automatiquement) — sans sous-titres, ce serait de la VO brute.
 */
object MegacloudX {
    private const val TAG = "MegacloudX"
    private val client by lazy {
        NetworkClient.default.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(8, TimeUnit.SECONDS).build()
    }

    suspend fun serveurs(chemin: String): List<Video.Server> = withContext(Dispatchers.IO) {
        val cible = runCatching {
            client.newCall(Request.Builder().url("https://megacloudx.net/$chemin/").header("Referer", "https://cineby.win/").build())
                .execute().use { r -> r.header("Location") }
        }.getOrNull()?.takeIf { it.contains("/e/") } ?: run {
            Log.d(TAG, "$chemin : inconnu"); return@withContext emptyList()
        }
        val info = Regex("""[?&]sub\.info=([^&]+)""").find(cible)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val langues = info?.let { u ->
            runCatching {
                NetworkClient.default.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    val a = JSONArray(r.body?.string().orEmpty())
                    (0 until a.length()).map { a.optJSONObject(it)?.optString("label").orEmpty() }
                }
            }.getOrNull()
        }.orEmpty()
        val fr = langues.any { it.startsWith("French", true) || it.startsWith("Fran", true) }
        val en = langues.any { it.startsWith("English", true) }
        Log.d(TAG, "$chemin : ${langues.size} langue(s) de sous-titres (FR=$fr, EN=$en)")
        if (!fr && !en) return@withContext emptyList()
        listOf(Video.Server(id = "megacloudx_${chemin.replace('/', '_')}", name = "[VOSTFR]", src = cible))
    }
}
