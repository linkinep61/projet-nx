package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.utils.TmdbUtils
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
        // 2026-07-04 (sync avec coflix.js web) : le JS pointe coflix.trade, absent du pool
        //   natif → ajouté en tête pour rester à jour avec le domaine actif du site.
        "https://coflix.trade",
        "https://coflix.cymru",
        "https://coflix.date",
        "https://coflix.click",
    )

    @Volatile private var lastWorkingMirror: String = MIRRORS.first()

    /** 2026-06-02 — Backoff après HTTP 429. Quand un mirror Coflix renvoie 429,
     *  on marque Coflix comme indispo pendant 30 min : tous les calls suivants
     *  retournent null instantanement, sans frapper le serveur. Ca evite le
     *  ban CF qui s'aggrave a force de hammer-and-retry. */
    private const val COOLDOWN_AFTER_429_MS = 30L * 60L * 1000L // 30 min
    @Volatile private var coflixCooldownUntilMs: Long = 0L

    /** Headers communs pour les calls Coflix. */
    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "fr-FR,fr;q=0.9",
        "Referer" to "$lastWorkingMirror/",
    )

    private val httpClient by lazy { Extractor.sharedClient }

    private suspend fun httpGet(url: String, customReferer: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .apply {
                    headers().forEach { (k, v) ->
                        // 2026-06-02 : Referer overridable. lecteurvideo.com renvoie 403/520
                        // (CF bloque) si on envoie le Referer global (lastWorkingMirror) au lieu
                        // de l'URL de la page Coflix d'origine. Validé par curl : sans Referer
                        // 403, avec Referer = coflix.cymru/film/X → 200.
                        if (k == "Referer" && customReferer != null) header(k, customReferer)
                        else header(k, v)
                    }
                }
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "GET $url → HTTP ${resp.code}")
                    if (resp.code == 429) {
                        // Coflix CF rate-limit. On gèle tous les appels pour 30 min.
                        coflixCooldownUntilMs = System.currentTimeMillis() + COOLDOWN_AFTER_429_MS
                        Log.w(TAG, "Coflix global cooldown 30 min apres HTTP 429")
                    }
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
    suspend fun getMovieSources(title: String, year: Int? = null, altTitle: String? = null): List<Video.Server> {
        // 2026-06-03 (user "Pourquoi ce film là Il est pas trouvé par Ce site
        //   Dans l'application" pour Aventures croisées 2026) : essai du titre
        //   principal puis fallback titre alternatif (souvent le titre original
        //   anglais quand TMDB FR n'a pas encore traduit, ou inversement). Le
        //   slug Coflix dépend du titre VF (ex : "aventures-croisees") alors
        //   que TMDB peut renvoyer "Swapped" pour les films récents → on ratait.
        val match = searchBest(title, year, type = "movies")
            ?: altTitle?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
                ?.let { searchBest(it, year, type = "movies") }
            // Dernier recours : on demande à TMDB la version FR du titre puis
            //   on retente. Couvre les films récents où Cloudstream/Movix ont
            //   le titre EN ("Swapped") mais Coflix indexe en FR ("aventures-
            //   croisees").
            ?: resolveFrenchTitleViaTmdb(title, year, isMovie = true)
                ?.takeIf { !it.equals(title, ignoreCase = true) }
                ?.let { Log.d(TAG, "Coflix fallback FR title TMDB: '$title' -> '$it'"); searchBest(it, year, type = "movies") }
            ?: return emptyList()
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
        altShowTitle: String? = null,
    ): List<Video.Server> {
        // 2026-06-03 : même fallback titre alternatif que pour les films.
        val match = searchBest(showTitle, year, type = "series")
            ?: altShowTitle?.takeIf { it.isNotBlank() && !it.equals(showTitle, ignoreCase = true) }
                ?.let { searchBest(it, year, type = "series") }
            ?: resolveFrenchTitleViaTmdb(showTitle, year, isMovie = false)
                ?.takeIf { !it.equals(showTitle, ignoreCase = true) }
                ?.let { Log.d(TAG, "Coflix fallback FR series TMDB: '$showTitle' -> '$it'"); searchBest(it, year, type = "series") }
            ?: return emptyList()
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
        if (System.currentTimeMillis() < coflixCooldownUntilMs) {
            val remainSec = (coflixCooldownUntilMs - System.currentTimeMillis()) / 1000
            Log.d(TAG, "Coflix en cooldown 429 (${remainSec}s restants) — skip")
            return null
        }
        val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
        // 2026-06-02 : on essaie d'abord le miroir DECOUVERT via coflix.blog
        // (toujours a jour), puis lastWorkingMirror, puis le pool statique.
        val discovered = CoflixMirrorDiscovery.discover()
        val mirrors = buildList {
            if (discovered != null) add(discovered)
            if (lastWorkingMirror !in this) add(lastWorkingMirror)
            MIRRORS.forEach { if (it !in this) add(it) }
        }
        for (mirror in mirrors) {
            val url = "$mirror/suggest.php?query=$encoded"
            val body = httpGet(url) ?: continue
            lastWorkingMirror = mirror
            pickBest(body, cleanTitle, year, type)?.let { return it }
        }
        // 2026-06-02 : fallback slug-direct.
        // /suggest.php est souvent rate-limited (HTTP 429) — Coflix protege son API
        // anti-DDoS. La page film elle, /film/<slug>/, n'est PAS rate-limited.
        // → on construit l'URL directement a partir du titre slugifie. Validation
        // par la presence d'une iframe lecteurvideo dans la page.
        val slug = slugify(cleanTitle)
        if (slug.isNotBlank()) {
            val pathSegment = if (type == "movies") "film" else "serie"
            for (mirror in mirrors) {
                val directUrl = "$mirror/$pathSegment/$slug/"
                val body = httpGet(directUrl) ?: continue
                if (body.contains("lecteurvideo")) {
                    lastWorkingMirror = mirror
                    Log.d(TAG, "Coflix slug-direct fallback: '$cleanTitle' → $directUrl")
                    return CoflixMatch(cleanTitle, directUrl, year?.toString())
                }
            }
            Log.d(TAG, "Coflix slug-direct fallback: aucun mirror n'a /$pathSegment/$slug/ avec iframe valide")
        }
        return null
    }

    /** 2026-06-03 : Résout le titre FR d'un film/série via TMDB.
     *  Sert de pont quand le caller passe le titre EN ("Swapped") alors que
     *  Coflix indexe en FR ("aventures-croisees"). On demande TMDB en fr-FR
     *  pour récupérer la traduction officielle, puis on retente Coflix avec.
     *  Retourne null si TMDB ne connaît pas le film ou si la version FR
     *  n'apporte rien (= égale à l'input). */
    private suspend fun resolveFrenchTitleViaTmdb(rawTitle: String, year: Int?, isMovie: Boolean): String? = try {
        if (isMovie) {
            TmdbUtils.getMovie(rawTitle, year, language = "fr-FR")?.title?.takeIf { it.isNotBlank() }
        } else {
            TmdbUtils.getTvShow(rawTitle, year, language = "fr-FR")?.title?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        Log.d(TAG, "TMDB FR resolve failed for '$rawTitle' ($year): ${e.message}")
        null
    }

    /** Slugifie un titre pour construire une URL Coflix.
     *  "Super Charlie" → "super-charlie", "L'Été" → "l-ete". */
    private fun slugify(title: String): String =
        title.lowercase()
            .replace("é", "e").replace("è", "e").replace("ê", "e").replace("ë", "e")
            .replace("à", "a").replace("â", "a").replace("ä", "a")
            .replace("ô", "o").replace("ö", "o")
            .replace("ù", "u").replace("û", "u").replace("ü", "u")
            .replace("ç", "c").replace("î", "i").replace("ï", "i")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

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
        // 2026-06-02 : passe la coflixPageUrl en Referer pour que lecteurvideo.com
        // ne renvoie pas 403/520 (CF anti-hotlink).
        val embedHtml = httpGet(iframeUrl, customReferer = coflixPageUrl) ?: return emptyList()
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
