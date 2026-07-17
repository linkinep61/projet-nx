package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.utils.TmdbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max

/**
 * 2026-07-11 — Backup Cpasmieux (site DataLife FR, VF/VOSTFR streaming).
 *
 * Site 100% STREAMING (pas de DDL/débrideur). Reverse-engineering :
 *   1. Recherche : POST /index.php?do=search  body do=search&subaction=search&story={titre}
 *      → HTML avec des <a href="/{id}-{slug}.html">Titre</a>. Les SÉRIES ont un titre
 *      « … - s N e E » (dernier épisode) ; les FILMS non → discrimination de type.
 *   2. Film  : page /{id}-{slug}.html
 *   3. Épisode : /{id}-{slug}-saison-{N}-episode-{E}.html  (URL directe, PAS d'AJAX)
 *   4. Serveurs : des <div data-url="{embed}"> « Lien N : HOST » directement dans le HTML.
 *      Hôtes = Uqload / Filemoon / Dood(kakaflix) / Vidzy / VOE / FSVID … → TOUS gérés par
 *      les extracteurs existants. On renvoie chaque data-url comme Video.Server (src) et
 *      Extractor.extract() route par domaine.
 *
 * Matching STRICT (score ≥ 90, titre exact ou mot-à-mot + longueur proche, bonus/malus année
 * via le slug) — calqué sur CoflixSourceProvider pour ne pas laisser passer n'importe quoi.
 */
object CpasmieuxProvider {

    private const val TAG = "CpasmieuxProvider"
    private const val BASE = "https://www.cpasmieux.is"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val httpClient by lazy { Extractor.sharedClient }

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .header("Referer", "$BASE/")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.d(TAG, "GET $url → HTTP ${resp.code}"); return@withContext null }
                resp.body?.string()
            }
        } catch (e: Exception) { Log.d(TAG, "GET $url failed: ${e.message}"); null }
    }

    // 2026-07-11 : le VRAI moteur = GET /search/{keyword}/ (custom, cf search.js →
    //   window.location='/search/'+kw+'/'). Le POST DLE `do=search` renvoyait juste
    //   l'accueil (33 KB fixes) → ne filtrait pas. `query` est DÉJÀ url-encodé par l'appelant.
    private suspend fun httpSearch(query: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE/search/$query/")
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .header("Referer", "$BASE/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.d(TAG, "search → HTTP ${resp.code}"); return@withContext null }
                resp.body?.string()
            }
        } catch (e: Exception) { Log.d(TAG, "search failed: ${e.message}"); null }
    }

    // ── API publique ─────────────────────────────────────────────────────────
    suspend fun getMovieSources(title: String, year: Int? = null, altTitle: String? = null): List<Video.Server> {
        val m = searchBest(title, year, wantSeries = false)
            ?: altTitle?.takeIf { it.isNotBlank() && !it.equals(title, true) }?.let { searchBest(it, year, wantSeries = false) }
            // Fallback : le site indexe en FR mais l'app passe parfois le titre EN → on
            //   demande le titre FR à TMDB et on réessaie.
            ?: resolveFrenchTitleViaTmdb(title, year, isMovie = true)
                ?.takeIf { !it.equals(title, true) }
                ?.let { Log.d(TAG, "Cpasmieux fallback FR TMDB: '$title' -> '$it'"); searchBest(it, year, wantSeries = false) }
            ?: return emptyList()
        return extractServers("$BASE${m.path}", wantYear = year)
    }

    /** Résout le titre FR d'un film/série via TMDB (pont EN→FR pour les sites FR). */
    private suspend fun resolveFrenchTitleViaTmdb(rawTitle: String, year: Int?, isMovie: Boolean): String? = try {
        if (isMovie) TmdbUtils.getMovie(rawTitle, year, language = "fr-FR")?.title?.takeIf { it.isNotBlank() }
        else TmdbUtils.getTvShow(rawTitle, year, language = "fr-FR")?.title?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { Log.d(TAG, "TMDB FR resolve KO '$rawTitle': ${e.message}"); null }

    suspend fun getEpisodeSources(
        showTitle: String, year: Int? = null, seasonNumber: Int, episodeNumber: Int, altShowTitle: String? = null,
    ): List<Video.Server> {
        val m = searchBest(showTitle, year, wantSeries = true)
            ?: altShowTitle?.takeIf { it.isNotBlank() && !it.equals(showTitle, true) }?.let { searchBest(it, year, wantSeries = true) }
            ?: resolveFrenchTitleViaTmdb(showTitle, year, isMovie = false)
                ?.takeIf { !it.equals(showTitle, true) }
                ?.let { Log.d(TAG, "Cpasmieux fallback FR TMDB série: '$showTitle' -> '$it'"); searchBest(it, year, wantSeries = true) }
            ?: return emptyList()
        val base = m.path.removeSuffix(".html")
        val epUrl = "$BASE$base-saison-$seasonNumber-episode-$episodeNumber.html"
        return extractServers(epUrl)
    }

    // ── Recherche + matching strict ───────────────────────────────────────────
    private data class Match(val title: String, val path: String, val year: Int?)

    private val SERIES_EP = Regex("""-\s*s\s*\d+\s*e\s*\d+""", RegexOption.IGNORE_CASE)
    // Capture l'ANCRE ENTIÈRE (le titre est imbriqué : poster + libellé dans le même <a>).
    private val RESULT_ANCHOR = Regex(
        """<a\b[^>]*href="(/\d+-[a-z0-9-]+\.html)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TAG_STRIP = Regex("<[^>]+>")

    private fun norm(s: String): String =
        TitleNormalizer.stripUnicodeArtifacts(s).lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private suspend fun searchBest(rawTitle: String, year: Int?, wantSeries: Boolean): Match? {
        val clean = TitleNormalizer.cleanForTmdbSearch(rawTitle).ifBlank { rawTitle }
        if (clean.isBlank()) return null
        val html = httpSearch(URLEncoder.encode(clean, "UTF-8").replace("+", "%20")) ?: return null

        val qNorm = norm(clean)
        val qWords = qNorm.split(" ").filter { it.length > 1 }.toSet()
        var best: Pair<Match, Int>? = null

        for (a in RESULT_ANCHOR.findAll(html)) {
            val path = a.groupValues[1]
            val rawText = TAG_STRIP.replace(a.groupValues[2], " ").replace(Regex("\\s+"), " ").trim()
            if (rawText.isBlank()) continue
            val isSeries = SERIES_EP.containsMatchIn(rawText)
            // discrimination de type : film ↔ série
            if (wantSeries != isSeries) continue
            // nettoyer le titre affiché (« - s N e E », « HD/VF/VOSTFR/4K/FRENCH »)
            var t = rawText.replace(SERIES_EP, "")
                .replace(Regex("""\s*-\s*s\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+(HD|VF|VOSTFR|VOST|4K|FRENCH|MULTI|TRUEFRENCH)\b.*$""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (t.isBlank()) continue
            val tNorm = norm(t)
            val tWords = tNorm.split(" ").filter { it.length > 1 }.toSet()
            val lenDiff = if (max(tNorm.length, qNorm.length) > 0)
                abs(tNorm.length - qNorm.length).toDouble() / max(tNorm.length, qNorm.length) else 0.0
            var score = when {
                tNorm == qNorm -> 100
                qWords.isNotEmpty() && tWords.containsAll(qWords) && lenDiff <= 0.30 -> 90
                tWords.isNotEmpty() && qWords.containsAll(tWords) && lenDiff <= 0.30 -> 80
                else -> 0
            }
            // année via le slug (ex : "...-2026.html" ou "...-2026-saison-1")
            val slugYear = Regex("""-((?:19|20)\d\d)(?:-|\.)""").findAll(path).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            if (year != null && slugYear != null) {
                if (slugYear == year) score += 30 else if (abs(slugYear - year) > 2) score -= 50
            }
            if (score >= 90 && (best == null || score > best!!.second)) best = Match(t, path, slugYear) to score
        }
        if (best == null) Log.d(TAG, "Cpasmieux '$clean' (year=$year series=$wantSeries) : pas de match fiable")
        else Log.d(TAG, "Cpasmieux '$clean' → '${best!!.first.title}' ${best!!.first.path} score=${best!!.second}")
        return best?.first
    }

    // ── Extraction des serveurs (data-url) ────────────────────────────────────
    private val DATA_URL = Regex("""data-url=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    // 2026-07-12 : « Date de sortie: 2016 » sur la page film → pour rejeter un même-titre d'année
    //   différente (ex : « L'Odyssée » 2016 alors qu'on cherche celui de 2026).
    private val RELEASE_YEAR = Regex("""Date de sortie\s*:?\s*((?:19|20)\d\d)""", RegexOption.IGNORE_CASE)

    private suspend fun extractServers(pageUrl: String, wantYear: Int? = null): List<Video.Server> {
        val html = httpGet(pageUrl) ?: return emptyList()
        // 2026-07-12 (user « des serveurs cpasmieux apparaissent alors que le film n'y est pas ») :
        //   le slug n'a pas toujours l'année → le matching titre seul acceptait un MAUVAIS film de
        //   même titre (année différente). On lit « Date de sortie » sur la page et on REJETTE si
        //   l'écart d'année est > 1 (donc pas le bon film).
        if (wantYear != null) {
            val pageYear = RELEASE_YEAR.find(html)?.groupValues?.get(1)?.toIntOrNull()
            if (pageYear != null && kotlin.math.abs(pageYear - wantYear) > 1) {
                Log.d(TAG, "Cpasmieux: année page=$pageYear ≠ demandée=$wantYear → rejet (mauvais film) $pageUrl")
                return emptyList()
            }
        }
        val seen = HashSet<String>()
        val out = mutableListOf<Video.Server>()
        for (m in DATA_URL.findAll(html)) {
            val url = m.groupValues[1].trim().replace("&amp;", "&")
            if (!url.startsWith("http") || !seen.add(url)) continue
            out.add(
                Video.Server(
                    id = "cpasmieux_${out.size}",
                    name = "Cpasmieux · ${hostName(url)}",
                    src = url,
                )
            )
        }
        Log.d(TAG, "extractServers($pageUrl) → ${out.size} serveurs")
        return out
    }

    private fun hostName(url: String): String {
        val host = url.substringAfter("://").substringBefore("/").lowercase().removePrefix("www.")
        return when {
            host.contains("uqload") -> "Uqload"
            host.contains("filemoon") || host.contains("filmoon") -> "Filemoon"
            host.contains("dood") -> "Dood"
            host.contains("kakaflix") || host.contains("kokoflix") -> "Proxy"
            host.contains("vidzy") -> "Vidzy"
            host.contains("voe") -> "VOE"
            host.contains("fsvid") -> "FSVID"
            host.contains("vidaraa") -> "Vidaraa"
            else -> host.substringBefore(".").replaceFirstChar { it.uppercase() }
        }
    }
}
