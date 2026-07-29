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
import java.util.concurrent.TimeUnit

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

    /** 2026-07-31 : nb max de liens conservés pour un MÊME hébergeur (URLs différentes).
     *  >1 permet de garder un second lien quand le premier pointe sur un fichier supprimé. */
    private const val MAX_SERVERS_PER_DOMAIN = 3
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val httpClient by lazy { Extractor.sharedClient }

    /**
     * Client BORNÉ pour la fiche saison (page HTML). Le sharedClient a des timeouts larges ;
     * une page coflix.wiki lente/CF peut BLOQUER le thread OkHttp (HANGDUMP observé) → la source
     * CoflixWiki dépasse alors son plafond et DISPARAÎT. On borne donc à 10s max total.
     */
    private val pageClient by lazy {
        Extractor.sharedClient.newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

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

    /** GET AJAX (X-Requested-With) — utilisé pour /ajax/episode/list-episode. */
    private suspend fun ajaxGet(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE_URL$path")
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$BASE_URL/")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.i(TAG, "GET $path → HTTP ${resp.code}")
                    return@withContext null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.i(TAG, "GET $path failed: ${e.message}")
            null
        }
    }

    /**
     * GET page HTML brute (document) — pour extraire le movieId de la fiche saison.
     * Utilise le client BORNÉ (jamais de hang) + détecte un éventuel challenge CF (→ null).
     */
    private suspend fun fetchPage(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE_URL$path")
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .header("Referer", "$BASE_URL/")
                .build()
            pageClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.i(TAG, "GET page $path → HTTP ${resp.code}")
                    return@withContext null
                }
                val html = resp.body?.string() ?: return@withContext null
                // Challenge CF (« Just a moment » / Turnstile) → pas de vraie page → null
                if (Regex("(?i)just a moment|challenge-platform|cf-browser-verification|turnstile").containsMatchIn(html)) {
                    Log.i(TAG, "GET page $path → challenge CF détecté")
                    return@withContext null
                }
                html
            }
        } catch (e: Exception) {
            Log.i(TAG, "GET page $path failed: ${e.message}")
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

        // 2026-07-31 (user : « on n'était pas censé fusionner les URL qui étaient différentes ? ») :
        //   AVANT on dédupait par DOMAINE → deux liens DIFFÉRENTS du même hébergeur (ex. deux
        //   fichiers Lulustream, l'un supprimé et l'autre valide) étaient fusionnés et un seul
        //   survivait. Si le survivant était le fichier mort, la source valide était PERDUE.
        //   MAINTENANT : dédup par URL EXACTE (on ne jette que les vrais doublons), avec un
        //   plafond par hébergeur pour ne pas noyer la liste (les suivants sont numérotés #2, #3).
        val seenUrls = mutableSetOf<String>()
        val perDomain = mutableMapOf<String, Int>()
        val merged = mutableListOf<Video.Server>()
        for (match in matches) {
            val servers = fetchServersForEpisode(match.episodeId, match.version)
            for (server in servers) {
                val url = server.src.trim()
                if (url.isBlank() || !seenUrls.add(url)) continue   // vrai doublon → ignoré
                val domain = try { java.net.URL(url).host.lowercase() } catch (_: Throwable) { url }
                val n = (perDomain[domain] ?: 0) + 1
                perDomain[domain] = n
                if (n > MAX_SERVERS_PER_DOMAIN) continue
                merged.add(
                    if (n > 1) server.copy(id = "${server.id}#$n", name = "${server.name} #$n")
                    else server
                )
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
                    when {
                        // Slug qui déclare une AUTRE saison → rejet (cas « FROM S2 » → fiche S4).
                        slugSeason != null && slugSeason != seasonNumber -> {
                            Log.i(TAG, "Fallback REJETÉ: slug '${fallback.slug}' = saison $slugSeason ≠ demandé S$seasonNumber")
                            null
                        }
                        // 2026-07-31 (user « CoflixWiki fait encore des mauvais matchs », Silo S3) :
                        //   slug SANS « saison-N » alors qu'on demande une saison > 1 → c'est la fiche
                        //   GÉNÉRIQUE (= saison 1) → on jouerait la S1 à la place de la SN. REJET.
                        //   (S1 : beaucoup de fiches légitimes n'écrivent pas « saison-1 » → tolérées.)
                        slugSeason == null && seasonNumber > 1 -> {
                            Log.i(TAG, "Fallback REJETÉ: slug '${fallback.slug}' ne déclare aucune saison mais on demande S$seasonNumber")
                            null
                        }
                        // Le TITRE de la fiche doit aussi être cohérent avec la saison demandée.
                        !BackupRegistry.seasonTitleOk(fallback.title, false, seasonNumber) -> {
                            Log.i(TAG, "Fallback REJETÉ: titre '${fallback.title}' ≠ saison demandée S$seasonNumber")
                            null
                        }
                        else -> fallback
                    }
                } else null
            }
            ?: return emptyList()

        // 2026-07-14 FIX (House of the Dragon) : les episode_id NE SONT PAS séquentiels.
        //   Ex HotD S3 VF : E1=217907, E2=220810, E3=221053, E4=221227 (écarts de milliers).
        //   L'ancien calcul `firstEpId + (N-1)` matchait donc le MAUVAIS épisode (E2 → 217908
        //   = en fait l'E1 VOSTFR). On lit désormais la VRAIE map épisode→id via l'AJAX
        //   /ajax/episode/list-episode?movieId={id} (le movieId vit dans la fiche saison,
        //   seul `data-id` de la page brute). Fallback = ancien calcul si l'AJAX échoue.
        // 2026-07-31 (user « mauvais matchs », Silo S3E5) : le fallback « firstEpId + (N-1) »
        //   fabriquait un id ARBITRAIRE quand la map échouait/l'épisode manquait — cet id
        //   appartient presque toujours à un AUTRE contenu → mauvais film/série joué.
        //   Règle user : « PAS DE SERVEUR plutôt que le mauvais film ».
        //   → E1 : firstEpId est fiable PAR DÉFINITION (id du 1er épisode donné par le suggest).
        //   → E>1 : UNIQUEMENT via la map réelle du site, sinon on n'émet RIEN.
        val realEpId = runCatching { resolveRealEpisodeId(match.slug, match.episodeId, episodeNumber) }.getOrNull()
        val targetEpId = realEpId
            ?: if (episodeNumber == 1) {
                match.episodeId.also { Log.i(TAG, "map indisponible → E1 = firstEpId=$it (sûr)") }
            } else {
                Log.i(TAG, "ABANDON S${seasonNumber}E$episodeNumber : épisode absent de la map du site " +
                    "(slug=${match.slug}) — aucun serveur émis plutôt qu'un mauvais contenu")
                return emptyList()
            }
        Log.i(TAG, "Épisode S${seasonNumber}E${episodeNumber} → epId=$targetEpId (firstEp=${match.episodeId})")

        return fetchServersForEpisode(targetEpId, match.version)
    }

    /**
     * 2026-07-14 : résout le VRAI episode_id via la map officielle du site (les ids ne sont
     *   PAS séquentiels). Étapes :
     *     1. GET la fiche saison → extrait movieId (unique `data-id` de la page brute).
     *     2. GET /ajax/episode/list-episode?movieId={movieId} → JSON { html } contenant
     *        des <a data-num="N" data-id="{epId}">.
     *     3. map[episodeNumber] = le vrai id.
     *   Retourne null si une étape échoue (→ l'appelant retombe sur l'ancien calcul).
     */
    private suspend fun resolveRealEpisodeId(slug: String, firstEpId: Int, episodeNumber: Int): Int? {
        val pageHtml = fetchPage("/film/$slug") ?: run {
            Log.i(TAG, "resolveRealEpisodeId: fiche saison /film/$slug injoignable")
            return null
        }
        val movieId = Regex("""data-id="(\d+)"""").find(pageHtml)?.groupValues?.get(1) ?: run {
            Log.i(TAG, "resolveRealEpisodeId: movieId introuvable dans la fiche saison")
            return null
        }
        val body = ajaxGet("/ajax/episode/list-episode?movieId=$movieId") ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val html = json.optString("html").takeIf { it.isNotBlank() } ?: return null
        val map = Regex("""data-num="(\d+)"\s+data-id="(\d+)"""").findAll(html)
            .mapNotNull { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val id = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                n to id
            }.toMap()
        val realId = map[episodeNumber]
        Log.i(TAG, "resolveRealEpisodeId slug=$slug movieId=$movieId E$episodeNumber → $realId (${map.size} épisodes)")
        return realId
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

        // 2026-07-29 (issue #123 « Joker » — mauvais film joué) : plusieurs films partagent souvent
        //   le MÊME titre (Joker 2019, Joker 2015, …). Le « 2015 » se normalise aussi en « joker » →
        //   il passe titleMatches. Comme getMovieSources FUSIONNE les serveurs de toutes les fiches
        //   matchées, sans année on mélangeait 2 films différents et on jouait le mauvais.
        //   → On désambiguïse par ANNÉE : si ONYX ne fournit pas d'année, on prend celle de la 1re
        //   fiche (ordre du site = la plus canonique/populaire) et on écarte les fiches d'une AUTRE
        //   année. Les fiches sans année sont gardées (souvent = versions VF/VOSTFR de la même fiche).
        val filmYear = targetYear ?: matched.mapNotNull { it.year }.firstOrNull()
        val disambiguated = if (filmYear == null) matched
            else matched.filter { it.year == null || kotlin.math.abs(it.year - filmYear) <= yearTolerance }
        Log.i(TAG, "searchAllMatches '$cleanTitle' → ${candidates.size} candidats, ${pool.size} typés, ${matched.size} titleMatches('$titleForMatch'), ${disambiguated.size} après désambiguïsation année=$filmYear")

        // Trier : VF d'abord, puis VOSTFR
        return disambiguated.sortedByDescending { it.version == "VF" }
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
                    // 2026-07-31 : le TITRE doit aussi déclarer la bonne saison (double sécurité
                    //   slug+titre) — « saison-3 » peut apparaître dans un slug pour une autre raison.
                    BackupRegistry.seasonTitleOk(c.title, false, season) &&
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

        // 2026-07-31 (user : « la fusion doit toucher tous les extracteurs, on doit perdre pas
        //   mal de serveurs à cause de ça ») — IL AVAIT RAISON.
        //   AVANT : dédup par DOMAINE → sur 3 miroirs d'un même hébergeur (URLs DIFFÉRENTES,
        //   donc fichiers différents), 2 étaient jetés. Si le seul gardé pointait sur un fichier
        //   supprimé, la source était perdue alors qu'un lien valide existait (constaté :
        //   Lulustream mort dans ONYX mais fonctionnel sur Wiflix).
        //   MAINTENANT : dédup par URL EXACTE (seuls les vrais doublons sautent) + plafond par
        //   hébergeur pour ne pas noyer la liste ; les suivants sont numérotés #2, #3.
        val seenUrls = mutableSetOf<String>()
        val perDomainCount = mutableMapOf<String, Int>()
        val result = mutableListOf<Video.Server>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val serverLink = s.optString("server_link").takeIf { it.isNotBlank() } ?: continue
            val serverVersion = s.optString("version").ifBlank { langLabel }

            val hosterDomain = serverLink.substringAfter("://").substringBefore("/").lowercase()
            if (!seenUrls.add(serverLink.trim())) continue          // vrai doublon (URL identique)
            val dupIndex = (perDomainCount[hosterDomain] ?: 0) + 1
            perDomainCount[hosterDomain] = dupIndex
            if (dupIndex > MAX_SERVERS_PER_DOMAIN) continue         // garde-fou anti-liste à rallonge

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

            // Miroirs supplémentaires du même hébergeur → numérotés pour rester distinguables.
            val displayName = if (dupIndex > 1) "$hosterName #$dupIndex" else hosterName
            result.add(
                Video.Server(
                    id = "coflixwiki_${episodeId}_$i",
                    name = "CoflixWiki · $displayName ($serverVersion)",
                    src = serverLink,
                ),
            )
        }

        Log.i(TAG, "player epId=$episodeId → ${result.size} serveurs")
        return result
    }
}
