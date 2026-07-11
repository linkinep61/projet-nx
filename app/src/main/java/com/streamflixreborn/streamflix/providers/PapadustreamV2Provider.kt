package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * PapadustreamV2Provider — backup NATIF par TITRE (portage de papadustream-v2.js, 2026-07-04).
 *
 * Distinct du PapadustreamProvider natif (ancien CMS DLE, séries) : c'est la NOUVELLE version
 * du site, films ET séries, sans WebView ni captcha.
 *   - Passerelle stable : https://papadustream.info → resolveContentBase() lit le lien
 *     « Accéder » (hôte papadustream-v\d, ex papadustream-v2.org) = domaine de contenu courant.
 *   - Recherche : GET <contenu>/?s=<titre> → liens a[href*=/movie/|/tv/|/tv-shows/].
 *   - Serveurs : JSON EN CLAIR dans la page → regex "server_name"/"version"/"link"
 *     (ex vidzy.org [VF] / [VOSTFR]). Résolus par les Extractors in-app.
 *
 * Source BACKUP uniquement (appelée par le registre par titre). Auto-répare le changement
 * de domaine via la passerelle (cache 30 min).
 */
object PapadustreamV2Provider {
    private const val TAG = "PapadustreamV2"
    private const val GATEWAY = "https://papadustream.info"
    private const val CONTENT_TTL_MS = 30L * 60L * 1000L

    @Volatile private var contentBase: String? = null
    @Volatile private var contentBaseAt = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", WebViewResolver.STEALTH_UA)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .header("Referer", "$GATEWAY/")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "GET $url → HTTP ${resp.code}"); return@withContext null }
                resp.body?.string()
            }
        } catch (e: Exception) { Log.w(TAG, "GET $url failed: ${e.message}"); null }
    }

    /** Résout le domaine de CONTENU courant depuis la passerelle (lien « Accéder »). Cache 30 min. */
    private suspend fun resolveContentBase(): String {
        contentBase?.let { if (System.currentTimeMillis() - contentBaseAt < CONTENT_TTL_MS) return it }
        val html = get(GATEWAY) ?: return contentBase ?: GATEWAY
        val hosts = try {
            Jsoup.parse(html, GATEWAY).select("a[href]")
                .mapNotNull { runCatching { java.net.URL(it.absUrl("href")) }.getOrNull() }
                .filter { it.host.contains("papadustream", ignoreCase = true) && !it.host.equals("papadustream.info", ignoreCase = true) }
        } catch (_: Exception) { emptyList() }
        val pick = hosts.firstOrNull { it.host.contains(Regex("-v\\d", RegexOption.IGNORE_CASE)) } ?: hosts.firstOrNull()
        val base = pick?.let { "${it.protocol}://${it.host}" } ?: (contentBase ?: GATEWAY)
        contentBase = base
        contentBaseAt = System.currentTimeMillis()
        Log.i(TAG, "contentBase résolu : $base")
        return base
    }

    private fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    // Mots de DÉCORATION (langue/qualité/format) à ignorer dans le matching — pas des mots
    //   d'identité. Permet « Naruto VF Complet » ≈ « Naruto », sans confondre avec « Naruto
    //   Uzumaki » (Uzumaki = mot d'identité, pas de la décoration).
    private val DECORATION = setOf(
        "vf", "vostfr", "vo", "truefrench", "french", "multi", "complet", "complete",
        "streaming", "stream", "gratuit", "film", "films", "serie", "series", "saison",
        "season", "episode", "integrale", "integral", "voir", "regarder", "hd", "full"
    )
    private fun sigWords(s: String): Set<String> = norm(s).split(" ")
        .filter { it.length >= 2 && it !in DECORATION && !it.matches(Regex("(19|20)\\d{2}")) && it.any { c -> !c.isDigit() } }
        .toSet()

    private fun wordMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val s = if (a.length <= b.length) a else b; val l = if (a.length <= b.length) b else a
        return s.length >= 4 && l.startsWith(s) && (l.length - s.length) <= 2  // pluriel/variante orthographe
    }

    /** Match titre STRICT (user "pas de mauvais films : Naruto ≠ Naruto Uzumaki"). Après retrait
     *  des mots de décoration, le candidat ne doit contenir AUCUN mot d'IDENTITÉ absent de la
     *  requête. → « Naruto Uzumaki »/« Naruto Shippuden » rejetés pour la requête « Naruto ».
     *  Accepte l'exact + les variantes de sous-titre plus courtes (candidat ⊆ requête). */
    private fun titleMatches(candidate: String, query: String): Boolean {
        val qw = sigWords(query)
        val cw = sigWords(candidate)
        if (qw.isEmpty() || cw.isEmpty()) {
            val nc = norm(candidate).replace(" ", ""); val nq = norm(query).replace(" ", "")
            return nc.isNotEmpty() && nc == nq
        }
        // Un mot d'identité du candidat absent de la requête = mauvais film → rejet.
        val extra = cw.filter { c -> qw.none { q -> wordMatch(c, q) } }
        if (extra.isNotEmpty()) return false
        // Le candidat couvre au moins la moitié des mots de la requête (évite le match générique).
        val covered = qw.count { q -> cw.any { c -> wordMatch(q, c) } }
        return covered.toDouble() / qw.size >= 0.5
    }

    /**
     * Backup par titre. Cherche le titre, match STRICT (bidirectionnel), CIBLE l'épisode exact
     * pour les séries, gate l'année pour les films, puis extrait les serveurs JSON.
     * Retourne [] si rien ne matche (préfère 0 serveur au mauvais film/épisode).
     *
     * 2026-07-09 (audit strict des backups) — structure réelle du site (vérifiée en direct) :
     *   - Films  : /movie/<slug>                       → serveurs sur la fiche.
     *   - Séries : /tv-show/<slug> (SINGULIER)         → fiche SANS serveurs ;
     *              les serveurs vivent sur /episode/<slug>/<saison>-<épisode>.
     *   Avant : le code lisait /tv/ + /tv-shows/ (n'existent pas → 0 série) et, pour une fiche
     *   série, aurait renvoyé tous les serveurs de la page (mauvais épisode). Corrigé.
     */
    suspend fun fetchPapadustreamV2Backup(
        title: String,
        year: Int? = null,
        season: Int = 0,
        episode: Int = 0,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val isSeries = season > 0 || episode > 0
        val base = resolveContentBase()
        // 2026-07-10 (fix « Papadustream V2 → 0 sur des titres pourtant présents, ex Sugar ») :
        //   VÉRIFIÉ EN DIRECT sur papadustream-v2.org — l'endpoint de recherche est `/search/<titre>`,
        //   PAS `/?s=` (qui renvoyait la HOME non filtrée → on matchait « Sugar » parmi les trending
        //   → 0). Espaces = %20 obligatoire (le `+` de URLEncoder casse la recherche du site).
        val searchHtml = get("$base/search/" + URLEncoder.encode(title, "UTF-8").replace("+", "%20"))
            ?: return@withContext emptyList()

        // Candidats : /movie/<slug> (films) ET /tv-show/<slug> (séries, singulier).
        val candidates = try {
            Jsoup.parse(searchHtml, base).select("a[href*=/movie/], a[href*=/tv-show/]")
                .mapNotNull { a ->
                    val href = a.attr("href")
                    val m = Regex("/(movie|tv-show)/([a-z0-9-]+)", RegexOption.IGNORE_CASE).find(href) ?: return@mapNotNull null
                    m.groupValues[1].lowercase() to m.groupValues[2]  // type to slug
                }
                .distinctBy { "${it.first}/${it.second}" }
        } catch (_: Exception) { emptyList() }

        // GARDE TYPE : série → uniquement tv-show ; film → uniquement movie (pas de confusion).
        val typed = candidates.filter { (type, _) -> if (isSeries) type == "tv-show" else type == "movie" }

        // Match titre BIDIRECTIONNEL (ultra strict) sur le slug : ni mot en trop côté candidat,
        //   ni côté requête → rejette « FROM » ⊄ « Beauty From Pain » ET « Solo » ⊄ « Solo Leveling ».
        val match = typed.firstOrNull { (_, slug) ->
            val slugTitle = slug.replace("-", " ")
            titleMatches(slugTitle, title) && titleMatches(title, slugTitle)
        } ?: return@withContext emptyList()

        // Page qui porte les serveurs : épisode ciblé pour une série, fiche pour un film.
        val pageUrl = if (isSeries) "$base/episode/${match.second}/$season-$episode"
                      else "$base/movie/${match.second}"
        val pageHtml = get(pageUrl) ?: return@withContext emptyList()

        // GATE ANNÉE (films uniquement) : la fiche affiche « Released … YYYY ». Rejette un remake
        //   homonyme (« Dune 1984 » ≠ « Dune 2021 »). Ne gate QUE si l'année est trouvée.
        if (!isSeries && year != null) {
            val relYear = Regex("Released[\\s\\S]{0,120}?((?:19|20)\\d{2})", RegexOption.IGNORE_CASE)
                .find(pageHtml)?.groupValues?.get(1)?.toIntOrNull()
            if (relYear != null && kotlin.math.abs(relYear - year) > 1) {
                Log.i(TAG, "PapaV2 rejet année: fiche=$relYear cible=$year ('${match.second}')")
                return@withContext emptyList()
            }
        }

        val re = Regex("\"server_name\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"version\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"link\"\\s*:\\s*\"([^\"]+)\"")
        val seen = HashSet<String>()
        val out = ArrayList<Video.Server>()
        for (mr in re.findAll(pageHtml)) {
            val name = mr.groupValues[1]
            val version = mr.groupValues[2]
            val link = mr.groupValues[3].replace("\\/", "/")
            if (!link.startsWith("http") || !seen.add(link)) continue
            out.add(Video.Server(id = "papav2_${link.hashCode()}", name = "$name [$version]", src = link))
        }
        out
    }

    /** Lecture : les liens (vidzy/dood…) sont résolus par les Extractors in-app. */
    suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src, server)
}
