package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Base64

/**
 * 2026-05-05 : Helper cross-provider qui récupère les sources Coflix
 * (https://coflix.date, https://coflix.blog, etc.) et les renvoie sous forme
 * de [Video.Server] consommables par n'importe quel provider.
 *
 * Architecture Coflix :
 *   1. Search API : `GET /suggest.php?query={titre}` → JSON [{ID, title, url,
 *      post_type: movies|series, year}]
 *   2. Film page : `/film/{slug}/` → contient un iframe vers
 *      `lecteurvideo.com/embed.php?id={id}`
 *   3. Episode page : `/episode/{serie-slug}-{season}x{episode}/` → idem
 *   4. lecteurvideo.com embed → contient N boutons avec
 *      `onclick="showVideo('<base64>', '<sand>')"` où la base64 décode en URL
 *      d'un hoster (Lulustream / VOE / Vidoza / Darkibox / Veev / Goodstream
 *      / Minochinos / lecteur1.xtremestream.xyz mp4 / coflix.upn.one ...).
 *
 * Notre implem :
 *   - Fait domain rotation entre les miroirs connus
 *   - Score les candidats search par title+year fuzzy match
 *   - Fetch lecteurvideo, extrait toutes les onclick base64, décode, mappe
 *     chaque URL à un nom d'extracteur connu pour l'affichage
 *   - Filtre les hosters non supportés (xtremestream raw mp4 OK ; le reste
 *     est délégué à [Extractor] qui matche par URL)
 */
object CoflixSourceProvider {

    private const val TAG = "CoflixSourceProvider"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Liste des miroirs connus du site. On essaie dans l'ordre jusqu'à un 200. */
    private val MIRRORS = listOf(
        "https://coflix.date",
        "https://coflix.blog",
        "https://coflix.click",
    )

    @Volatile private var lastWorkingMirror: String = MIRRORS.first()

    /** Headers communs pour les calls Coflix. */
    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "fr-FR,fr;q=0.9",
        "Referer" to "$lastWorkingMirror/",
    )

    private val httpClient by lazy { Extractor.sharedClient }

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .apply { headers().forEach { (k, v) -> header(k, v) } }
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "GET $url → HTTP ${resp.code}")
                    return@withContext null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.d(TAG, "GET $url failed: ${e.message}")
            null
        }
    }

    /**
     * Public — récupère les sources Coflix pour un film.
     *
     * @param title Titre à matcher (ex: "Joker: Folie à deux", "Wednesday").
     * @param year Année de sortie (utilisée pour disambiguer remakes).
     * @return Liste de [Video.Server] (un par hoster Coflix), ou empty si
     *         aucun match ou erreur.
     */
    suspend fun getMovieSources(title: String, year: Int? = null): List<Video.Server> {
        val match = searchBest(title, year, type = "movies") ?: return emptyList()
        return extractFromCoflixPage(match.url, label = "Coflix")
    }

    /**
     * Public — récupère les sources Coflix pour un épisode de série.
     *
     * @param showTitle Titre de la série (ex: "Mercredi", "Wednesday").
     * @param year Année de sortie de la série (premier air-date).
     * @param seasonNumber Numéro de saison (1-based).
     * @param episodeNumber Numéro d'épisode (1-based).
     */
    suspend fun getEpisodeSources(
        showTitle: String,
        year: Int? = null,
        seasonNumber: Int,
        episodeNumber: Int,
    ): List<Video.Server> {
        val match = searchBest(showTitle, year, type = "series") ?: return emptyList()
        // L'URL du serie s'appelle `/serie/{slug}/`. On en extrait le slug pour
        // construire l'URL épisode `/episode/{slug}-{season}x{episode}/`.
        val serieSlug = match.url
            .substringAfter("/serie/")
            .substringBefore("/")
            .trim()
        if (serieSlug.isBlank()) return emptyList()
        val episodeUrl = "${urlBase(match.url)}/episode/$serieSlug-${seasonNumber}x${episodeNumber}/"
        return extractFromCoflixPage(episodeUrl, label = "Coflix")
    }

    private fun urlBase(url: String): String {
        return url.substringBefore("/", missingDelimiterValue = url)
            .let { if (it.startsWith("http")) it.substringBefore("/", url) else url }
            .let { Regex("^(https?://[^/]+)").find(url)?.groupValues?.get(1) ?: lastWorkingMirror }
    }

    private data class CoflixMatch(val title: String, val url: String, val year: String?)

    /** Recherche un titre et retourne le meilleur match (par titre+année). */
    private suspend fun searchBest(rawTitle: String, year: Int?, type: String): CoflixMatch? {
        val cleanTitle = TitleNormalizer.cleanForTmdbSearch(rawTitle).ifBlank { rawTitle }
        if (cleanTitle.isBlank()) return null
        val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
        // Essaie chaque miroir jusqu'à un succès
        val mirrors = listOf(lastWorkingMirror) + MIRRORS.filter { it != lastWorkingMirror }
        for (mirror in mirrors) {
            val url = "$mirror/suggest.php?query=$encoded"
            val body = httpGet(url) ?: continue
            lastWorkingMirror = mirror
            return pickBest(body, cleanTitle, year, type) ?: continue
        }
        return null
    }

    /** Parse le JSON suggest et pioche le meilleur match. */
    private fun pickBest(json: String, query: String, year: Int?, type: String): CoflixMatch? {
        return try {
            val arr = JSONArray(json)
            val candidates = mutableListOf<Pair<CoflixMatch, Int>>()
            // 2026-05-05 v2 : matching strict — pas de substring lâche.
            // Avant : "Mercredi" matchait "Mercredi, folle journée" (substring 40).
            // Maintenant : exige match exact OU word-for-word + longueur similaire.
            val queryNorm = TitleNormalizer.stripUnicodeArtifacts(query).lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            val queryWords = queryNorm.split(" ").filter { it.length > 1 }.toSet()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pt = obj.optString("post_type")
                if (pt != type) continue
                val title = obj.optString("title")
                val rawUrl = obj.optString("url")
                if (title.isBlank() || rawUrl.isBlank()) continue
                val y = obj.optString("year").takeIf { it.isNotBlank() }
                val titleNorm = TitleNormalizer.stripUnicodeArtifacts(title).lowercase()
                    .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
                val titleWords = titleNorm.split(" ").filter { it.length > 1 }.toSet()
                val lenDiffPct = if (kotlin.math.max(titleNorm.length, queryNorm.length) > 0)
                    kotlin.math.abs(titleNorm.length - queryNorm.length).toDouble() /
                        kotlin.math.max(titleNorm.length, queryNorm.length)
                else 0.0
                var score = when {
                    titleNorm == queryNorm -> 100
                    queryWords.isNotEmpty() && titleWords.containsAll(queryWords) && lenDiffPct <= 0.30 -> 90
                    titleWords.isNotEmpty() && queryWords.containsAll(titleWords) && lenDiffPct <= 0.30 -> 80
                    else -> 0
                }
                if (year != null && y == year.toString()) score += 30
                else if (year != null && y != null && y.toIntOrNull() != null &&
                    kotlin.math.abs(y.toInt() - year) > 2) score -= 50  // pénalise gros écart d'année
                if (score >= 90) candidates.add(CoflixMatch(title, rawUrl, y) to score)
            }
            // Seuil 90 strict — sinon Coflix ne propose rien (better silent than wrong)
            val best = candidates.maxByOrNull { it.second }
            if (best == null) {
                Log.d(TAG, "Coflix '$query' (year=$year type=$type) : pas de match fiable")
            } else {
                Log.d(TAG, "Coflix '$query' → '${best.first.title}' year=${best.first.year} score=${best.second}")
            }
            best?.first
        } catch (e: Exception) {
            Log.d(TAG, "pickBest parse failed: ${e.message}")
            null
        }
    }

    /**
     * Fetch une page Coflix (film ou épisode), trouve l'iframe lecteurvideo,
     * fetch l'embed, extrait toutes les onclick base64, décode, et renvoie
     * la liste des Video.Server.
     */
    private suspend fun extractFromCoflixPage(coflixPageUrl: String, label: String): List<Video.Server> {
        val pageHtml = httpGet(coflixPageUrl) ?: return emptyList()
        // Extrait l'URL de l'iframe (généralement lecteurvideo.com/embed.php)
        val iframeUrl = Regex("""<iframe[^>]*src="([^"]+lecteurvideo[^"]+)"""").find(pageHtml)
            ?.groupValues?.get(1)
            ?: Regex("""<iframe[^>]*src="(https?://[^"]+)"""").find(pageHtml)?.groupValues?.get(1)
            ?: return emptyList()
        val embedHtml = httpGet(iframeUrl) ?: return emptyList()
        // Extrait les `onclick="showVideo('<base64>', '...')"` patterns
        val regex = Regex("""onclick="showVideo\('([^']+)',\s*'[^']*'\)"""")
        val results = mutableListOf<Video.Server>()
        for (m in regex.findAll(embedHtml)) {
            val b64 = m.groupValues[1]
            val decoded = runCatching { String(Base64.getDecoder().decode(b64)) }.getOrNull() ?: continue
            if (decoded.isBlank() || !decoded.startsWith("http")) continue
            val hosterName = guessHosterName(decoded)
            val server = Video.Server(
                id = "coflix_${results.size}",
                name = "$label · $hosterName",
                src = decoded,
            )
            results.add(server)
        }
        Log.d(TAG, "extractFromCoflixPage($coflixPageUrl) → ${results.size} sources")
        return results
    }

    /** Devine le nom du hoster à partir de l'URL pour l'affichage utilisateur. */
    private fun guessHosterName(url: String): String {
        val host = url.substringAfter("://").substringBefore("/").lowercase()
        return when {
            host.contains("lulustream") -> "Lulustream"
            host.contains("voe.") || host.contains("voe-") -> "VOE"
            host.contains("vidoza") -> "Vidoza"
            host.contains("darkibox") -> "Darkibox"
            host.contains("veev.") -> "Veev"
            host.contains("goodstream") -> "Goodstream"
            host.contains("minochinos") -> "Minochinos"
            host.contains("filemoon") -> "Filemoon"
            host.contains("doodstream") || host.contains("dood.") -> "Doodstream"
            host.contains("uqload") -> "Uqload"
            host.contains("streamtape") -> "Streamtape"
            host.contains("megaup") -> "MegaUp"
            host.contains("xtremestream") -> "MP4 Direct"
            host.contains("upn.one") -> "Coflix Upn"
            else -> host.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() }
        }
    }
}
