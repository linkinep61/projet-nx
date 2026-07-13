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
        // 2026-07-12 : coflix.boston = site ACTIF (WordPress, WP REST API).
        //   Les anciens domaines (trade/cymru/date/click) redirigent vers Telegram.
        "https://coflix.boston",
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
        return extractFromCoflixPage(match.url, label = "Coflix Boston")
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
        return extractFromCoflixPage(episodeUrl, label = "Coflix Boston")
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
        // 2026-07-12 : coflix.boston = WordPress, utilise WP REST API pour la recherche.
        //   L'ancien suggest.php n'existe plus (HTTP 500).
        val mirrors = buildList {
            if (lastWorkingMirror !in this) add(lastWorkingMirror)
            MIRRORS.forEach { if (it !in this) add(it) }
        }
        // 1) WP REST API search (coflix.boston)
        for (mirror in mirrors) {
            val url = "$mirror/wp-json/wp/v2/search?search=$encoded&type=post&per_page=20"
            val body = httpGet(url) ?: continue
            lastWorkingMirror = mirror
            pickBestWpApi(body, cleanTitle, year, type)?.let { return it }
        }
        // 2) Fallback slug-direct : on construit l'URL à partir du titre slugifié.
        //    Validation par la présence de cfServers ou lecteurvideo dans la page.
        val slug = slugify(cleanTitle)
        if (slug.isNotBlank()) {
            val pathSegment = if (type == "movies") "film" else "serie"
            for (mirror in mirrors) {
                val directUrl = "$mirror/$pathSegment/$slug/"
                val body = httpGet(directUrl) ?: continue
                if (body.contains("cfServers") || body.contains("lecteurvideo")) {
                    lastWorkingMirror = mirror
                    Log.d(TAG, "Coflix slug-direct fallback: '$cleanTitle' → $directUrl")
                    return CoflixMatch(cleanTitle, directUrl, year?.toString())
                }
            }
            Log.d(TAG, "Coflix slug-direct fallback: aucun mirror n'a /$pathSegment/$slug/")
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

    /**
     * 2026-07-12 : Parse le JSON WP REST API search et pioche le meilleur match.
     * Résultats = [{id, title, url, type, subtype}] où subtype = "movies"|"series"|"animes"|"doramas".
     */
    private fun pickBestWpApi(json: String, query: String, year: Int?, type: String): CoflixMatch? {
        return try {
            val arr = JSONArray(json)
            val candidates = mutableListOf<Pair<CoflixMatch, Int>>()
            // WP REST API subtype mapping : "movies" pour films, "series" pour séries
            val wantedSubtype = if (type == "movies") "movies" else "series"
            val queryNorm = TitleNormalizer.stripUnicodeArtifacts(query).lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            val queryWords = queryNorm.split(" ").filter { it.length > 1 }.toSet()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val subtype = obj.optString("subtype")
                // Accepter "animes" comme série aussi
                if (subtype != wantedSubtype && !(wantedSubtype == "series" && subtype == "animes")) continue
                val title = obj.optString("title")
                val rawUrl = obj.optString("url")
                if (title.isBlank() || rawUrl.isBlank()) continue
                // WP REST API ne renvoie pas l'année — on extrait du slug si possible
                val slugYear = Regex("""(\d{4})""").find(
                    rawUrl.substringAfterLast("/").substringBeforeLast("/")
                )?.groupValues?.get(1)
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
                if (year != null && slugYear == year.toString()) score += 30
                else if (year != null && slugYear != null && slugYear.toIntOrNull() != null &&
                    kotlin.math.abs(slugYear.toInt() - year) > 2) score -= 50
                if (score >= 80) candidates.add(CoflixMatch(title, rawUrl, slugYear) to score)
            }
            val best = candidates.maxByOrNull { it.second }
            if (best == null) {
                Log.d(TAG, "Coflix WP '$query' (year=$year type=$type) : pas de match")
            } else {
                Log.d(TAG, "Coflix WP '$query' → '${best.first.title}' score=${best.second}")
            }
            best?.first
        } catch (e: Exception) {
            Log.d(TAG, "pickBestWpApi failed: ${e.message}")
            null
        }
    }

    /**
     * Fetch une page Coflix (film ou épisode), extrait les serveurs vidéo.
     *
     * 2026-07-12 : supporte DEUX formats :
     *   (a) Nouveau (coflix.boston WordPress) : inline JS `var cfServers = [{nombre, embed_url, idioma}]`
     *       + `var cfPlayerToken = "..."`. L'iframe lecteurvideo = embed_url + "&t=" + token.
     *   (b) Ancien (legacy) : iframe lecteurvideo directement dans le HTML de la page.
     *
     * Dans les deux cas, l'embed lecteurvideo contient des onclick showVideo base64.
     */
    private suspend fun extractFromCoflixPage(coflixPageUrl: String, label: String): List<Video.Server> {
        val pageHtml = httpGet(coflixPageUrl) ?: return emptyList()
        // ── (a) Nouveau format WordPress : cfServers inline ──────────────────
        val embedUrls = mutableListOf<String>()
        val cfServersMatch = Regex("""var\s+cfServers\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL)
            .find(pageHtml)
        val cfTokenMatch = Regex("""var\s+cfPlayerToken\s*=\s*"([^"]+)"""").find(pageHtml)
        if (cfServersMatch != null) {
            val token = cfTokenMatch?.groupValues?.get(1) ?: ""
            try {
                val arr = JSONArray(cfServersMatch.groupValues[1])
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    var embedUrl = obj.optString("embed_url")
                    if (embedUrl.isBlank()) continue
                    // Ajouter le token comme paramètre &t=
                    if (token.isNotBlank()) {
                        embedUrl += (if (embedUrl.contains("?")) "&" else "?") + "t=$token"
                    }
                    embedUrls.add(embedUrl)
                }
                Log.d(TAG, "cfServers inline: ${embedUrls.size} embeds, token=${token.length} chars")
            } catch (e: Exception) {
                Log.d(TAG, "cfServers parse failed: ${e.message}")
            }
        }
        // ── (b) Fallback ancien format : iframe dans le HTML ─────────────────
        if (embedUrls.isEmpty()) {
            val iframeUrl = Regex("""<iframe[^>]*src="([^"]+lecteurvideo[^"]+)"""").find(pageHtml)
                ?.groupValues?.get(1)
                ?: Regex("""<iframe[^>]*src="(https?://[^"]+)"""").find(pageHtml)?.groupValues?.get(1)
            if (iframeUrl != null) embedUrls.add(iframeUrl)
        }
        if (embedUrls.isEmpty()) return emptyList()
        // ── Fetch chaque embed lecteurvideo et extraire les showVideo base64 ──
        val results = mutableListOf<Video.Server>()
        for (embedUrl in embedUrls) {
            val embedHtml = httpGet(embedUrl, customReferer = coflixPageUrl) ?: continue
            val regex = Regex("""onclick="showVideo\('([^']+)',\s*'[^']*'\)"""")
            for (m in regex.findAll(embedHtml)) {
                val b64 = m.groupValues[1]
                val decoded = runCatching { String(Base64.getDecoder().decode(b64)) }.getOrNull() ?: continue
                if (decoded.isBlank() || !decoded.startsWith("http")) continue
                // 2026-07-13 : Streamhg = EarnVids derrière Cloudflare Turnstile qui ne
                //   s'auto-résout QUE dans une WebView rendue à l'écran (échec garanti en
                //   headless depuis l'IP courante). Inutile de le lister NI de tenter
                //   l'extraction → on le saute (le film a déjà ~13 autres serveurs).
                if (decoded.contains("streamhg", ignoreCase = true)) continue
                val hosterName = guessHosterName(decoded)
                results.add(Video.Server(
                    id = "coflix_${results.size}",
                    name = "$label · $hosterName",
                    src = decoded,
                ))
            }
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
