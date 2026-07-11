package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.BackupRegistry
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 2026-07-08 : Backup provider pour coflix.wiki — FLUX 100% AJAX (zéro CF).
 *
 * Architecture découverte via reverse-engineering du JS (all.js) :
 *   1. Recherche : POST /ajax/search/suggest?keyword={titre}
 *      → JSON { html: "<items avec data-jp=titre, href=/film/{slug-vf}/ep-{id}>", status: true }
 *      Les slugs contiennent la langue (-vf / -vostfr) ET l'episode_id directement.
 *
 *   2. Player :   POST /ajax/episode/player?episode_id={id}
 *      → JSON { status: true, message: [{ server_name, server_link, server_type, version }] }
 *      Retourne les URLs embed (vidzy.cc, uqload.is, kokoflix.lol…) prêtes pour les extractors.
 *
 *   3. Séries : les episode_id sont SÉQUENTIELS au sein d'une saison.
 *      Le suggest retourne l'ep-ID du 1er épisode → épisode N = firstEpId + (N - 1).
 *
 * Aucune page HTML n'est scrapée (toutes sont CF-protégées).
 * Tous les endpoints AJAX sont POST et répondent sans CF challenge.
 */
object CoflixWikiProvider {

    private const val TAG = "CoflixWikiProvider"
    private const val BASE_URL = "https://coflix.wiki"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val httpClient by lazy { Extractor.sharedClient }

    // ── HTTP helpers ───────────────────────────────────────────────────────

    private suspend fun ajaxPost(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL$path"
            val emptyBody = "".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = Request.Builder()
                .url(url)
                .post(emptyBody)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$BASE_URL/")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.i(TAG, "POST $path → HTTP ${resp.code}")
                    return@withContext null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.i(TAG, "POST $path failed: ${e.message}")
            null
        }
    }

    // ── PUBLIC ─────────────────────────────────────────────────────────────

    /**
     * Récupère les sources CoflixWiki pour un film.
     * 2026-07-10 : fetch serveurs de TOUS les candidats qui matchent (pas seulement le 1er),
     *   merge avec dedup par domaine. Coflix a souvent 2 fiches du même film (slug -vf et
     *   -french) avec des serveurs DIFFÉRENTS ; en ne prenant que le 1er on ratait les bons.
     */
    suspend fun getMovieSources(title: String, year: Int? = null): List<Video.Server> {
        val matches = searchAllMatches(title, isMovie = true, strictTitle = title, targetYear = year)
        if (matches.isEmpty()) return emptyList()

        // Fetch serveurs de CHAQUE fiche candidate, merge avec dedup par domaine
        val seenDomains = mutableSetOf<String>()
        val merged = mutableListOf<Video.Server>()
        for (match in matches) {
            val servers = fetchServersForEpisode(match.episodeId, match.version)
            for (server in servers) {
                val domain = try { java.net.URL(server.src).host.lowercase() } catch (_: Throwable) { server.src }
                if (seenDomains.add(domain)) {
                    merged.add(server)
                }
            }
        }
        Log.i(TAG, "getMovieSources '$title' → ${matches.size} fiches, ${merged.size} serveurs mergés")
        return merged
    }

    /**
     * Récupère les sources CoflixWiki pour un épisode de série.
     */
    suspend fun getEpisodeSources(
        showTitle: String,
        year: Int? = null,
        seasonNumber: Int,
        episodeNumber: Int,
    ): List<Video.Server> {
        // 2026-07-09 : chercher PAR SAISON d'abord (valide "saison-N" dans le slug)
        //   pour éviter de matcher la mauvaise saison (les numéros sont strippés par
        //   la normalisation → Saison 1 et Saison 2 deviennent identiques).
        val match = searchBestSeason(showTitle, seasonNumber, year)
            ?: run {
                // Fallback : searchBest avec "titre Saison N"
                val seasonQuery = "$showTitle Saison $seasonNumber"
                val fallback = searchBest(seasonQuery, isMovie = false, strictTitle = showTitle, targetYear = year)
                // 2026-07-09 : VÉRIFIER que le slug du fallback correspond à la bonne saison.
                //   Sans ça, "FROM S2" → cleanForTmdbSearch strip "Saison 2" → cherche "FROM"
                //   → matche "FROM - Saison 4" (sigWords identiques, year absent) → calcule
                //   l'episodeId de la S4 au lieu de la S2 → MAUVAIS CONTENU.
                //   Si le slug contient "saison-X" avec X ≠ seasonNumber → REJET.
                if (fallback != null) {
                    val slugLower = fallback.slug.lowercase()
                    val slugSaisonMatch = Regex("saison-(\\d+)").find(slugLower)
                    val slugSeason = slugSaisonMatch?.groupValues?.get(1)?.toIntOrNull()
                    if (slugSeason != null && slugSeason != seasonNumber) {
                        Log.i(TAG, "Fallback REJETÉ: slug '${fallback.slug}' = saison $slugSeason ≠ demandé S$seasonNumber")
                        null
                    } else fallback
                } else null
            }
            ?: return emptyList()

        // L'ep-ID du suggest est le 1er épisode → épisode N = firstEpId + (N - 1)
        val targetEpId = match.episodeId + (episodeNumber - 1)
        Log.i(TAG, "Épisode S${seasonNumber}E${episodeNumber} → epId=${match.episodeId} + ${episodeNumber - 1} = $targetEpId")

        return fetchServersForEpisode(targetEpId, match.version)
    }

    // ── SEARCH (AJAX) ─────────────────────────────────────────────────────

    private data class SuggestMatch(
        val title: String,
        val slug: String,      // ex: enola-holmes-3-vf/ep-220915
        val episodeId: Int,    // ex: 220915
        val version: String,   // VF ou VOSTFR (déduit du slug)
        val type: String = "unknown", // movie / series / unknown — depuis la .meta du suggest
        val year: Int? = null,        // année — depuis la .meta (« Movie 2025 Completed »)
    )

    /**
     * 2026-07-10 : logique de recherche extraite — retourne TOUS les candidats qui matchent
     *   (triés VF d'abord). Utilisé par getMovieSources (merge serveurs de toutes les fiches)
     *   et par searchBest (prend le premier).
     */
    private suspend fun searchAllMatches(rawTitle: String, isMovie: Boolean, strictTitle: String? = null, targetYear: Int? = null): List<SuggestMatch> {
        val cleanTitle = TitleNormalizer.cleanForTmdbSearch(rawTitle).ifBlank { rawTitle }
        if (cleanTitle.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
        val body = ajaxPost("/ajax/search/suggest?keyword=$encoded") ?: return emptyList()

        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (json.optBoolean("status") != true) return emptyList()

        val html = json.optString("html").takeIf { it.isNotBlank() } ?: return emptyList()
        val candidates = parseSuggestHtml(html)
        if (candidates.isEmpty()) {
            Log.i(TAG, "Suggest '$cleanTitle' → 0 résultats")
            return emptyList()
        }

        val typed = candidates.filter { c ->
            when {
                isMovie -> c.type != "series"
                else -> c.type != "movie"
            }
        }
        val pool = typed.ifEmpty { candidates }

        val titleForMatch = strictTitle ?: rawTitle
        val yearTolerance = if (isMovie) 1 else 5
        val matched = pool.filter { c ->
            BackupRegistry.titleMatches(c.title, titleForMatch) &&
                BackupRegistry.titleMatches(titleForMatch, c.title) &&
                (targetYear == null || c.year == null || kotlin.math.abs(c.year - targetYear) <= yearTolerance)
        }
        Log.i(TAG, "searchAllMatches '$cleanTitle' → ${candidates.size} candidats, ${pool.size} typés, ${matched.size} titleMatches('$titleForMatch') année=$targetYear")

        // Trier : VF d'abord, puis VOSTFR
        return matched.sortedByDescending { it.version == "VF" }
    }

    /**
     * POST /ajax/search/suggest → parse le HTML, retourne le meilleur match VF.
     * 2026-07-10 : délègue à searchAllMatches, prend le premier.
     */
    private suspend fun searchBest(rawTitle: String, isMovie: Boolean, strictTitle: String? = null, targetYear: Int? = null): SuggestMatch? {
        val all = searchAllMatches(rawTitle, isMovie, strictTitle, targetYear)
        val best = all.firstOrNull()
        if (best != null) {
            Log.i(TAG, "Match '${TitleNormalizer.cleanForTmdbSearch(rawTitle)}' → '${best.title}' (${best.version}) epId=${best.episodeId}")
        } else {
            Log.i(TAG, "AUCUN match strict pour '${strictTitle ?: rawTitle}'")
        }
        return best
    }

    /**
     * Fallback pour séries : cherche le titre puis filtre par "Saison N" dans le slug.
     * 2026-07-09 : ajout titleMatches STRICT pour éviter faux positifs ("FROM" ≠ "Beauty FROM Pain").
     * 2026-07-09 FIX : l'API suggest ne renvoie que ~10 résultats → chercher juste
     *   "FROM" peut ne PAS inclure la S3. On essaie D'ABORD "titre Saison N" comme
     *   mot-clé (l'API retourne la bonne saison), puis le titre seul en fallback.
     *   Année tolérée ±5 (séries longues : S1=2022, S5=2026 → écart normal).
     */
    private suspend fun searchBestSeason(showTitle: String, season: Int, targetYear: Int? = null): SuggestMatch? {
        val cleanTitle = TitleNormalizer.cleanForTmdbSearch(showTitle).ifBlank { showTitle }
        if (cleanTitle.isBlank()) return null

        val seasonStr = "saison-$season"
        // Essayer 2 mots-clés : avec la saison (plus ciblé) puis sans (plus large)
        for (keyword in listOf("$cleanTitle Saison $season", cleanTitle)) {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val body = ajaxPost("/ajax/search/suggest?keyword=$encoded") ?: continue

            val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val html = json.optString("html").takeIf { it.isNotBlank() } ?: continue
            val candidates = parseSuggestHtml(html)

            val seasonMatch = candidates.filter { c ->
                c.slug.lowercase().contains(seasonStr) &&
                    c.type != "movie" &&
                    BackupRegistry.titleMatches(c.title, showTitle) &&
                    BackupRegistry.titleMatches(showTitle, c.title) &&
                    (targetYear == null || c.year == null || kotlin.math.abs(c.year - targetYear) <= 5)
            }
            Log.i(TAG, "searchBestSeason('$showTitle', S$season) keyword='$keyword' → ${candidates.size} candidats, ${seasonMatch.size} slug+titleMatch année=$targetYear")

            if (seasonMatch.isNotEmpty()) {
                return seasonMatch.firstOrNull { it.version == "VF" }
                    ?: seasonMatch.firstOrNull()
            }
        }
        return null
    }

    /**
     * Parse le HTML du suggest AJAX.
     *
     * Structure :
     * <a href="https://coflix.wiki/film/{slug-vf}/ep-{id}" class="...">
     *   <div class="info"><div class="name" data-jp="Titre">Titre</div>...</div>
     * </a>
     */
    private fun parseSuggestHtml(html: String): List<SuggestMatch> {
        val results = mutableListOf<SuggestMatch>()

        fun versionOf(slug: String): String {
            val s = slug.lowercase()
            return when {
                s.contains("-vostfr") -> "VOSTFR"
                s.contains("-vf") -> "VF"
                else -> "VF"
            }
        }
        // 2026-07-09 (user « décortique la meta du provider ») : le suggest renvoie pour CHAQUE
        //   item une div .meta = « <Type> <Année> <Statut> » (ex « Series 2022 Completed »,
        //   « Movie 2025 Completed »). On parse PAR BLOC <a>…</a> pour associer titre + slug +
        //   ep-id + Type + Année → gate type/année en aval. Bien plus fort que le titre seul.
        val blockRegex = Regex(
            """<a\s+[^>]*href="https?://coflix\.wiki/film/([^"]+/ep-(\d+))"[\s\S]*?</a>""",
            RegexOption.IGNORE_CASE,
        )
        for (b in blockRegex.findAll(html)) {
            val block = b.value
            val slug = b.groupValues[1]
            val epId = b.groupValues[2].toIntOrNull() ?: continue
            val title = (Regex("""data-jp="([^"]*)"""").find(block)?.groupValues?.get(1)
                ?: Regex("""class="name"[^>]*>([^<]+)<""").find(block)?.groupValues?.get(1)
                ?: "").trim()
            if (title.isBlank()) continue
            val meta = Regex("""class="meta"[^>]*>([\s\S]*?)</""").find(block)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim() ?: ""
            val type = when {
                Regex("(?i)\\bseries\\b").containsMatchIn(meta) || slug.contains("saison", true) -> "series"
                Regex("(?i)\\bmovie\\b").containsMatchIn(meta) -> "movie"
                else -> "unknown"
            }
            val year = Regex("(19|20)\\d{2}").find(meta)?.value?.toIntOrNull()
            results.add(SuggestMatch(title, slug, epId, versionOf(slug), type, year))
        }

        // Fallback : si le parsing par bloc échoue (markup différent), on retombe sur l'ancien
        //   extract (titre/slug/ep-id seuls, sans type/année) pour ne pas tout perdre.
        if (results.isEmpty()) {
            val itemRegex = Regex(
                """href="https?://coflix\.wiki/film/([^"]+/ep-(\d+))"[\s\S]*?data-jp="([^"]*)"""",
            )
            for (m in itemRegex.findAll(html)) {
                val slug = m.groupValues[1]
                val epId = m.groupValues[2].toIntOrNull() ?: continue
                val title = m.groupValues[3].trim()
                if (title.isBlank()) continue
                results.add(SuggestMatch(title, slug, epId, versionOf(slug)))
            }
        }

        return results
    }

    // 2026-07-09 : matching remplacé par BackupRegistry.titleMatches (STRICT).
    //   L'ancien matchScore maison était trop permissif ("FROM" matchait "Beauty FROM Pain").

    // ── EXTRACTION (AJAX) ─────────────────────────────────────────────────

    /**
     * POST /ajax/episode/player?episode_id={id} → serveurs embed.
     *
     * Réponse : { status: true, message: [{ server_name, server_link, server_type, version }] }
     */
    /** @param langLabel label langue par défaut si le serveur ne le précise pas. */
    private suspend fun fetchServersForEpisode(episodeId: Int, langLabel: String): List<Video.Server> {
        val body = ajaxPost("/ajax/episode/player?episode_id=$episodeId") ?: run {
            Log.i(TAG, "player epId=$episodeId → null")
            return emptyList()
        }

        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (json.optBoolean("status") != true) {
            Log.i(TAG, "player epId=$episodeId → status=false")
            return emptyList()
        }

        val message = json.opt("message")
        val servers: JSONArray = when (message) {
            is JSONArray -> message
            else -> {
                Log.i(TAG, "player epId=$episodeId → message n'est pas un array")
                return emptyList()
            }
        }

        // Dedup par DOMAINE hôte : l'API renvoie souvent 3 miroirs Kokoflix (URLs différentes,
        // même hébergeur) → on ne garde que le 1er par domaine pour éviter les doublons visuels.
        val seenDomains = mutableSetOf<String>()
        val result = mutableListOf<Video.Server>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val serverLink = s.optString("server_link").takeIf { it.isNotBlank() } ?: continue
            val serverVersion = s.optString("version").ifBlank { langLabel }

            val hosterDomain = serverLink.substringAfter("://").substringBefore("/").lowercase()
            if (!seenDomains.add(hosterDomain)) continue  // même hébergeur déjà vu → skip

            val hosterName = when {
                hosterDomain.contains("vidzy") -> "Vidzy"
                hosterDomain.contains("filemoon") -> "Filemoon"
                hosterDomain.contains("lulustream") -> "Lulustream"
                hosterDomain.contains("voe") -> "VOE"
                hosterDomain.contains("uqload") -> "Uqload"
                hosterDomain.contains("kokoflix") -> "Kokoflix"
                hosterDomain.contains("streamtape") -> "Streamtape"
                hosterDomain.contains("dood") -> "Doodstream"
                else -> hosterDomain.removePrefix("www.").substringBefore(".")
                    .replaceFirstChar { it.uppercase() }
            }

            result.add(
                Video.Server(
                    id = "coflixwiki_${episodeId}_$i",
                    name = "CoflixWiki · $hosterName ($serverVersion)",
                    src = serverLink,
                ),
            )
        }

        Log.i(TAG, "player epId=$episodeId → ${result.size} serveurs")
        return result
    }
}
