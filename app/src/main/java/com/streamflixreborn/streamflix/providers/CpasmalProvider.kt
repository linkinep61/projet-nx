package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.WebViewResolver
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max

/**
 * 2026-07-11 — Backup Cpasmal (cpasmal.rip, ex-Voirfilms, DataLife FR streaming VF/VOSTFR).
 *
 * ⚠️ CLOUDFLARE : cpasmal est derrière un challenge JS Cloudflare (« Un instant… »). OkHttp = 403
 *   (fingerprint TLS), et une WebView barebones reste bloquée sur le challenge. On réutilise donc
 *   `WebViewResolver` (le MÊME bypass CF que Wiflix/DessinAnime : STEALTH_UA + STEALTH_JS + attente
 *   du reload-après-clearance) pour récupérer le HTML des pages.
 *
 * Flux :
 *   1. Recherche : GET /index.php?do=search&subaction=search&story={titre} → HTML (page résultats
 *      <a href="/{id}-{slug}.html"> OU redirection directe sur la page film si match unique).
 *   2. Film /{id}-{slug}.html — Épisode /{id}-{slug}-saison-{N}-episode-{E}.html.
 *   3. Serveurs : <div onclick="getxfield('{id}','{host}_{lang}','{token}')">. getxfield.php accepte
 *      GET → on le résout IN-PAGE via WebViewResolver(rscFetchUrl) (fetch same-origin credentials
 *      include, passe le CF) → <iframe src="{embed}">. Hôtes = Uqload/Filemoon/Dood/VOE → extracteurs.
 *
 * Matching STRICT (score ≥ 90, titre exact/mot-à-mot + longueur proche, année via slug).
 */
object CpasmalProvider {

    private const val TAG = "CpasmalProvider"
    private const val BASE = "https://www.cpasmal.rip"
    private const val MAX_SERVERS = 8

    private val app get() = StreamFlixApp.instance

    // ── API publique ─────────────────────────────────────────────────────────
    suspend fun getMovieSources(title: String, year: Int? = null, altTitle: String? = null): List<Video.Server> {
        searchMovieServers(title, year)?.let { return it }
        altTitle?.takeIf { it.isNotBlank() && !it.equals(title, true) }
            ?.let { searchMovieServers(it, year)?.let { s -> return s } }
        resolveFrenchTitleViaTmdb(title, year, isMovie = true)
            ?.takeIf { !it.equals(title, true) }
            ?.let { Log.d(TAG, "Cpasmal fallback FR TMDB: '$title' -> '$it'"); searchMovieServers(it, year)?.let { s -> return s } }
        return emptyList()
    }

    suspend fun getEpisodeSources(
        showTitle: String, year: Int? = null, seasonNumber: Int, episodeNumber: Int, altShowTitle: String? = null,
    ): List<Video.Server> {
        searchEpisodeServers(showTitle, year, seasonNumber, episodeNumber)?.let { return it }
        altShowTitle?.takeIf { it.isNotBlank() && !it.equals(showTitle, true) }
            ?.let { searchEpisodeServers(it, year, seasonNumber, episodeNumber)?.let { s -> return s } }
        resolveFrenchTitleViaTmdb(showTitle, year, isMovie = false)
            ?.takeIf { !it.equals(showTitle, true) }
            ?.let { Log.d(TAG, "Cpasmal fallback FR TMDB série: '$showTitle' -> '$it'"); searchEpisodeServers(it, year, seasonNumber, episodeNumber)?.let { s -> return s } }
        return emptyList()
    }

    private suspend fun resolveFrenchTitleViaTmdb(rawTitle: String, year: Int?, isMovie: Boolean): String? = try {
        if (isMovie) TmdbUtils.getMovie(rawTitle, year, language = "fr-FR")?.title?.takeIf { it.isNotBlank() }
        else TmdbUtils.getTvShow(rawTitle, year, language = "fr-FR")?.title?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { Log.d(TAG, "TMDB FR resolve KO '$rawTitle': ${e.message}"); null }

    // ── Recherche film ────────────────────────────────────────────────────────
    private suspend fun searchMovieServers(rawTitle: String, year: Int?): List<Video.Server>? {
        val clean = TitleNormalizer.cleanForTmdbSearch(rawTitle).ifBlank { rawTitle }
        if (clean.isBlank()) return null
        val html = fetchHtml(searchUrl(clean)) ?: run { Log.d(TAG, "Cpasmal '$clean' (movie) : HTML null (CF/timeout)"); return null }

        // Redirection directe sur la page film ? (getxfield présents + og:title cohérent)
        if (GETXFIELD.containsMatchIn(html)) {
            val ogTitle = cleanSiteTitle(OG_TITLE.find(html)?.groupValues?.get(1) ?: "")
            if (!titleMatches(ogTitle, clean)) { Log.d(TAG, "Cpasmal redirect film titre='$ogTitle' ≠ '$clean' → rejet"); return null }
            val filmUrl = canonicalUrl(html) ?: searchUrl(clean)
            val servers = resolveServers(html, filmUrl)
            Log.d(TAG, "Cpasmal redirect direct film '$ogTitle' → ${servers.size} serveurs")
            return servers.ifEmpty { null }
        }
        val path = bestAnchor(html, clean, year, wantSeries = false) ?: run { Log.d(TAG, "Cpasmal '$clean' (movie) : pas de match fiable"); return null }
        val filmUrl = "$BASE$path"
        val filmHtml = fetchHtml(filmUrl) ?: return null
        val servers = resolveServers(filmHtml, filmUrl)
        Log.d(TAG, "Cpasmal '$clean' → $path → ${servers.size} serveurs")
        return servers.ifEmpty { null }
    }

    // ── Recherche épisode ───────────────────────────────────────────────────────
    private suspend fun searchEpisodeServers(rawTitle: String, year: Int?, se: Int, ep: Int): List<Video.Server>? {
        val clean = TitleNormalizer.cleanForTmdbSearch(rawTitle).ifBlank { rawTitle }
        if (clean.isBlank()) return null
        val html = fetchHtml(searchUrl(clean)) ?: return null
        val basePath: String = if (GETXFIELD.containsMatchIn(html)) {
            val ogTitle = cleanSiteTitle(OG_TITLE.find(html)?.groupValues?.get(1) ?: "")
            if (!titleMatches(ogTitle, clean)) return null
            canonicalPath(html) ?: return null
        } else {
            bestAnchor(html, clean, year, wantSeries = true) ?: run { Log.d(TAG, "Cpasmal '$clean' (série) : pas de match fiable"); return null }
        }
        val epUrl = "$BASE" + basePath.removeSuffix(".html") + "-saison-$se-episode-$ep.html"
        val epHtml = fetchHtml(epUrl) ?: return null
        val servers = resolveServers(epHtml, epUrl)
        Log.d(TAG, "Cpasmal '$clean' S${se}E${ep} → $epUrl → ${servers.size} serveurs")
        return servers.ifEmpty { null }
    }

    private fun searchUrl(title: String): String =
        "$BASE/index.php?do=search&subaction=search&search_start=0&full_search=0&result_from=1&story=" +
            URLEncoder.encode(title, "UTF-8").replace("+", "%20")

    // ── Fetch HTML via WebViewResolver (passe Cloudflare) ───────────────────────
    private suspend fun fetchHtml(url: String): String? {
        val html = try {
            WebViewResolver(app).get(url, silent = true, markerTimeoutMs = 14_000L)
        } catch (e: Exception) { Log.d(TAG, "fetchHtml KO ${e.message}"); null } ?: return null
        val low = html.lowercase()
        if (html.length < 500 || low.contains("un instant") || low.contains("just a moment") ||
            low.contains("checking your browser") || html.contains("<body>Timeout</body>")
        ) { Log.d(TAG, "fetchHtml: CF non passé (len=${html.length}) $url"); return null }
        return html
    }

    /** Résout les serveurs d'une page film/épisode. getxfield.php via WebViewResolver(rscFetchUrl). */
    private suspend fun resolveServers(pageHtml: String, pageUrl: String): List<Video.Server> {
        val calls = GETXFIELD.findAll(pageHtml).map {
            Triple(it.groupValues[1], it.groupValues[2].lowercase(), it.groupValues[3])
        }.distinctBy { it.second }.take(MAX_SERVERS).toList()
        if (calls.isEmpty()) { Log.d(TAG, "Cpasmal: aucun getxfield sur $pageUrl"); return emptyList() }
        val seen = HashSet<String>()
        val out = mutableListOf<Video.Server>()
        for ((id, xfield, token) in calls) {
            val getUrl = "$BASE/engine/ajax/getxfield.php?id=" + URLEncoder.encode(id, "UTF-8") +
                "&xfield=" + URLEncoder.encode(xfield, "UTF-8") + "&token=" + URLEncoder.encode(token, "UTF-8")
            val resp = try {
                WebViewResolver(app).get(pageUrl, silent = true, rscFetchUrl = getUrl, contentMarker = "iframe", markerTimeoutMs = 12_000L)
            } catch (e: Exception) { Log.d(TAG, "getxfield $xfield KO ${e.message}"); continue }
            var embed = IFRAME_SRC.find(resp)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&") ?: continue
            if (embed.startsWith("//")) embed = "https:$embed"
            if (!embed.startsWith("http") || !seen.add(embed)) continue
            out.add(Video.Server(id = "cpasmal_${out.size}", name = "Cpasmal · ${hostName(embed)} ${langLabel(xfield)}".trim(), src = embed))
        }
        return out
    }

    // ── Regex ───────────────────────────────────────────────────────────────────
    private val GETXFIELD = Regex(
        """getxfield\(\s*['"](\d+)['"]\s*,\s*['"]([a-z0-9_]+)['"]\s*,\s*['"]([a-f0-9]+)['"]\s*\)""",
        RegexOption.IGNORE_CASE,
    )
    private val IFRAME_SRC = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val RESULT_ANCHOR = Regex(
        """<a\b[^>]*href="(?:https?://[^/"]+)?(/\d+-[a-z0-9-]+\.html)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val OG_TITLE = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val CANONICAL = Regex("""<link[^>]+rel=["']canonical["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_URL = Regex("""<meta[^>]+property=["']og:url["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val SERIES_EP = Regex("""-\s*s\s*\d+\s*e\s*\d+""", RegexOption.IGNORE_CASE)
    private val TAG_STRIP = Regex("<[^>]+>")

    private fun canonicalUrl(html: String): String? =
        (CANONICAL.find(html)?.groupValues?.get(1) ?: OG_URL.find(html)?.groupValues?.get(1))?.takeIf { it.contains(".html") }
    private fun canonicalPath(html: String): String? =
        canonicalUrl(html)?.replace(Regex("^https?://[^/]+"), "")?.takeIf { it.startsWith("/") }

    private fun norm(s: String): String =
        TitleNormalizer.stripUnicodeArtifacts(s).lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun cleanSiteTitle(raw: String): String = raw
        .replace(Regex("""\s+(streaming|en streaming|vf|vostfr|complet|le film complet|film|série).*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun titleMatches(candidate: String, query: String): Boolean {
        val c = norm(candidate); val q = norm(query)
        if (c.isBlank() || q.isBlank()) return false
        if (c == q) return true
        val cw = c.split(" ").filter { it.length > 1 }.toSet()
        val qw = q.split(" ").filter { it.length > 1 }.toSet()
        if (qw.isEmpty() || cw.isEmpty()) return false
        return cw.containsAll(qw) || qw.containsAll(cw)
    }

    /** Meilleur <a> de la page de résultats (score ≥ 90) → PATH "/id-slug.html". */
    private fun bestAnchor(html: String, clean: String, year: Int?, wantSeries: Boolean): String? {
        val qNorm = norm(clean)
        val qWords = qNorm.split(" ").filter { it.length > 1 }.toSet()
        val byPath = LinkedHashMap<String, String>()
        for (a in RESULT_ANCHOR.findAll(html)) {
            val path = a.groupValues[1]
            val text = TAG_STRIP.replace(a.groupValues[2], " ").replace(Regex("\\s+"), " ").trim()
            if (text.isNotBlank() || !byPath.containsKey(path)) byPath[path] = text
        }
        var best: Pair<String, Int>? = null
        for ((path, rawText) in byPath) {
            if (rawText.isBlank()) continue
            val isSeries = SERIES_EP.containsMatchIn(rawText)
            if (wantSeries != isSeries) continue
            val t = rawText.replace(SERIES_EP, "")
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
            val slugYear = Regex("""-((?:19|20)\d\d)(?:-|\.)""").findAll(path).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            if (year != null && slugYear != null) { if (slugYear == year) score += 30 else if (abs(slugYear - year) > 2) score -= 50 }
            if (score >= 90 && (best == null || score > best!!.second)) best = path to score
        }
        return best?.first
    }

    private fun langLabel(xfield: String): String = when {
        xfield.endsWith("_vostfr") || xfield.endsWith("_vost") -> "(VOSTFR)"
        xfield.endsWith("_vf") -> "(VF)"
        else -> ""
    }

    private fun hostName(url: String): String {
        val host = url.substringAfter("://").substringBefore("/").lowercase().removePrefix("www.")
        return when {
            host.contains("uqload") -> "Uqload"
            host.contains("filemoon") || host.contains("lukefirst") || host.contains("filmoon") -> "Filemoon"
            host.contains("dood") -> "Dood"
            host.contains("voe") -> "VOE"
            host.contains("vidzy") -> "Vidzy"
            host.contains("fsvid") -> "FSVID"
            else -> host.substringBefore(".").replaceFirstChar { it.uppercase() }
        }
    }
}
