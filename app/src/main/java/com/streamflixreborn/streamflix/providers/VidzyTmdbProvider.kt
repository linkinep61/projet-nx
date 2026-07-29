package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * 2026-07-31 (user : « https://api.vidzy.org/ — tout est bien en place de notre côté ? »)
 *
 * Source backup **par identifiant TMDB** → AUCUN matching de titre, donc AUCUN
 * risque de mauvais film/série (contrairement aux providers qui cherchent par nom).
 * C'est la réponse de fond au problème « CoflixWiki fait des mauvais matchs ».
 *
 * API publique, sans clé ni compte :
 *   - `GET /api/{tmdbId}`                      → { available, detectedType, title, year, languages[] }
 *   - `GET /movie/{tmdbId}`                    → page embed (film)
 *   - `GET /serie/{tmdbId}/{saison}/{episode}` → page embed (épisode)
 *
 * La page embed contient DIRECTEMENT (HTML brut, sans JS) une `<iframe src="…vidzy.cc/embed-XXXX.html">`
 * → on émet ce lien comme serveur ; l'extracteur **Vidzy** existant sait déjà le lire.
 * Vérifié en direct : tmdb 125988 (Silo) S3E5 → « Silo - Saison 3 … Souvenirs » ✅.
 */
object VidzyTmdbProvider {

    private const val TAG = "VidzyTmdbProvider"
    private const val BASE_URL = "https://vidzy.org"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val IFRAME_RX = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "$BASE_URL/")
                .header("Accept", "text/html,application/json,*/*")
                .build()
            Extractor.sharedClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        }.getOrNull()
    }

    /**
     * Récupère les serveurs Vidzy pour un contenu identifié par son **tmdbId**.
     *
     * @param tmdbId  id TMDB (id SÉRIE pour un épisode — déjà résolu en amont par BackupRegistry)
     * @param isMovie true = film, false = épisode de série
     */
    suspend fun fetchVidzyBackupServers(
        tmdbId: String,
        isMovie: Boolean,
        season: Int = 1,
        episode: Int = 1,
    ): List<Video.Server> {
        // id TMDB purement numérique attendu (BackupRegistry résout déjà l'id SÉRIE pour un épisode)
        if (tmdbId.isBlank() || !tmdbId.all { it.isDigit() }) return emptyList()

        // 1) Disponibilité + langues (1 requête légère, évite d'ouvrir une page pour rien).
        //    Si l'API ne répond pas, on tente quand même la page embed (elle fait autorité).
        var languages = ""
        val apiBody = httpGet("$BASE_URL/api/$tmdbId")
        if (apiBody != null) {
            val json = runCatching { JSONObject(apiBody) }.getOrNull()
            if (json != null) {
                if (!json.optBoolean("available", true)) {
                    Log.i(TAG, "tmdb=$tmdbId indisponible chez Vidzy")
                    return emptyList()
                }
                // Garde-fou type : une série demandée doit être détectée comme 'tv' (et inversement).
                val detected = json.optString("detectedType", "")
                if (detected.isNotBlank()) {
                    val typeOk = if (isMovie) detected.equals("movie", true)
                                 else detected.equals("tv", true) || detected.equals("series", true)
                    if (!typeOk) {
                        Log.i(TAG, "tmdb=$tmdbId type Vidzy='$detected' ≠ attendu (isMovie=$isMovie) → abandon")
                        return emptyList()
                    }
                }
                languages = runCatching {
                    val arr = json.optJSONArray("languages")
                    (0 until (arr?.length() ?: 0)).joinToString("/") { arr!!.optString(it).uppercase() }
                }.getOrNull().orEmpty()
            }
        }

        // 2) Page embed → iframe (vidzy.cc/embed-XXXX.html)
        val path = if (isMovie) "/movie/$tmdbId" else "/serie/$tmdbId/$season/$episode"
        val page = httpGet("$BASE_URL$path") ?: run {
            Log.i(TAG, "page $path injoignable")
            return emptyList()
        }
        val iframe = IFRAME_RX.find(page)?.groupValues?.get(1)?.trim().orEmpty()
        if (iframe.isBlank()) {
            Log.i(TAG, "aucune iframe dans $path")
            return emptyList()
        }
        val src = when {
            iframe.startsWith("http", true) -> iframe
            iframe.startsWith("//") -> "https:$iframe"
            iframe.startsWith("/") -> "$BASE_URL$iframe"
            else -> return emptyList()
        }

        val label = buildString {
            append("Vidzy")
            if (languages.isNotBlank()) append(" · ").append(languages)
        }
        Log.i(TAG, "tmdb=$tmdbId ${if (isMovie) "film" else "S${season}E$episode"} → serveur Vidzy ($languages)")
        return listOf(Video.Server(id = "vidzy::$tmdbId::$season::$episode", name = label, src = src))
    }
}
