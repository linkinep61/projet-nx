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
import java.net.URLEncoder

/**
 * 2026-07-14 : Backup provider pour dessinanime.net (dessins animés + animes en FR).
 *
 * Site = CMS turc (« Sezon/Bölüm »), structure TRÈS propre — saison/épisode dans l'URL,
 * donc zéro devinette d'id séquentiel (contrairement à CoflixWiki/Coflix Boston).
 *
 * Reverse-engineering (via Chrome) :
 *   1. Recherche : GET /search?q={titre}
 *        → HTML avec des blocs <a href="…/show/{hash}"> + titre en alt/title.
 *   2. Épisode  : GET /episode/{showHash}/season-{S}-episode-{E}
 *        → contient un/des élément(s) source `data-embed="{sourceId}"`.
 *   3. Résolution : POST /ajax/embed/{sourceId}  (body `id={sourceId}`, X-Requested-With)
 *        → renvoie du HTML `<iframe src="{embedUrl}">` (emmmmbed.com, 1a-1791 mp4…).
 *   Les embeds sont routés vers les extracteurs existants (emmmmbed = EmmmmbedExtractor).
 *   NB : UN seul serveur peut être derrière un CF/hCaptcha → il échoue proprement (skip).
 */
object DessinAnimeNetProvider {

    private const val TAG = "DessinAnimeNet"
    private const val BASE_URL = "https://dessinanime.net"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val httpClient by lazy { Extractor.sharedClient }

    // ── HTTP helpers ───────────────────────────────────────────────────────

    private suspend fun httpGet(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE_URL$path")
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9")
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

    /** GET AJAX (X-Requested-With + Accept json) — pour /ajax/posts (recherche typeahead). */
    private suspend fun ajaxGet(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE_URL$path")
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
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

    private suspend fun ajaxPostEmbed(sourceId: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = "id=$sourceId".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = Request.Builder()
                .url("$BASE_URL/ajax/embed/$sourceId")
                .post(body)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$BASE_URL/")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.i(TAG, "POST /ajax/embed/$sourceId → HTTP ${resp.code}")
                    return@withContext null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.i(TAG, "POST /ajax/embed/$sourceId failed: ${e.message}")
            null
        }
    }

    // ── PUBLIC ─────────────────────────────────────────────────────────────

    /** Sources d'un épisode de série (saison/épisode dans l'URL, 1-based). */
    suspend fun getEpisodeSources(
        showTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ): List<Video.Server> {
        val showId = searchMatch(showTitle, "show") ?: run {
            Log.i(TAG, "getEpisodeSources '$showTitle' → aucune série matchée")
            return emptyList()
        }
        val epUrl = "/episode/$showId/season-$seasonNumber-episode-$episodeNumber"
        val servers = extractServersFromPage(epUrl, "$showTitle S${seasonNumber}E$episodeNumber")
        Log.i(TAG, "getEpisodeSources '$showTitle' S${seasonNumber}E$episodeNumber → showId=$showId → ${servers.size} serveur(s)")
        return servers
    }

    /** Sources d'un film — sur ce CMS les films sont sur `/movie/{hash}` (player directement dessus). */
    suspend fun getMovieSources(title: String): List<Video.Server> {
        val movieId = searchMatch(title, "movie") ?: run {
            Log.i(TAG, "getMovieSources '$title' → aucun film matché")
            return emptyList()
        }
        val servers = extractServersFromPage("/movie/$movieId", title)
        Log.i(TAG, "getMovieSources '$title' → movieId=$movieId → ${servers.size} serveur(s)")
        return servers
    }

    // ── SEARCH ─────────────────────────────────────────────────────────────

    /**
     * Recherche via le typeahead : GET /ajax/posts?q={titre} → JSON [{ url, name, image }].
     *   `url` = "/show/{hash}" (série) ou "/movie/{hash}" (film). On matche `name` (STRICT)
     *   et on ne retient que le type demandé (`wantKind` = "show" ou "movie").
     * NB : /search?q= ne filtre PAS (grille générique) — le VRAI moteur est /ajax/posts.
     */
    private suspend fun searchMatch(rawTitle: String, wantKind: String): String? {
        val cleanTitle = TitleNormalizer.cleanForTmdbSearch(rawTitle).ifBlank { rawTitle }
        if (cleanTitle.isBlank()) return null
        val body = ajaxGet("/ajax/posts?q=${URLEncoder.encode(cleanTitle, "UTF-8")}") ?: return null

        // Réponse JSON : array direct OU { data/results/posts: [...] }. Chaque item = { url, name }.
        val items: org.json.JSONArray = runCatching {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) org.json.JSONArray(trimmed)
            else {
                val obj = org.json.JSONObject(trimmed)
                obj.optJSONArray("data") ?: obj.optJSONArray("results")
                    ?: obj.optJSONArray("posts") ?: org.json.JSONArray()
            }
        }.getOrNull() ?: return null

        var count = 0
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { o.optString("title") }.trim()
            val url = o.optString("url").ifBlank { o.optString("href") }
            val m = Regex("""/(show|movie)/([A-Za-z0-9]{8,})""").find(url) ?: continue
            if (m.groupValues[1] != wantKind) continue
            count++
            if (name.isNotBlank() &&
                BackupRegistry.titleMatches(name, rawTitle) &&
                BackupRegistry.titleMatches(rawTitle, name)
            ) {
                Log.i(TAG, "searchMatch '$cleanTitle' ($wantKind) → '$name' (id=${m.groupValues[2]})")
                return m.groupValues[2]
            }
        }
        Log.i(TAG, "searchMatch '$cleanTitle' ($wantKind) → $count candidats $wantKind, aucun titleMatch strict")
        return null
    }

    // ── EXTRACTION ─────────────────────────────────────────────────────────

    /** Page épisode OU film → TOUS les data-embed → POST /ajax/embed → iframe embed URLs → serveurs. */
    private suspend fun extractServersFromPage(pageUrl: String, label: String): List<Video.Server> {
        val pageHtml = httpGet(pageUrl) ?: return emptyList()
        val sourceIds = Regex("""data-embed="(\d+)"""").findAll(pageHtml)
            .map { it.groupValues[1] }.distinct().toList()
        if (sourceIds.isEmpty()) {
            Log.i(TAG, "extractServers($pageUrl) → 0 data-embed")
            return emptyList()
        }

        val seenDomains = mutableSetOf<String>()
        val result = mutableListOf<Video.Server>()
        for (sid in sourceIds) {
            val embedHtml = ajaxPostEmbed(sid) ?: continue
            val embedUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(embedHtml)?.groupValues?.get(1)
                ?.let { if (it.startsWith("//")) "https:$it" else it }
                ?: continue

            val domain = try { java.net.URL(embedUrl).host.lowercase() } catch (_: Throwable) { embedUrl }
            if (!seenDomains.add(domain)) continue

            val hosterName = when {
                domain.contains("emmmmbed") -> "Emmmmbed"
                domain.contains("1a-1791") || domain.contains("dood") -> "Doodstream"
                domain.contains("voe") -> "VOE"
                domain.contains("vidmoly") -> "Vidmoly"
                domain.contains("lulustream") -> "Lulustream"
                domain.contains("filemoon") -> "Filemoon"
                domain.contains("streamtape") -> "Streamtape"
                else -> domain.removePrefix("www.").substringBefore(".")
                    .replaceFirstChar { it.uppercase() }
            }
            result.add(
                Video.Server(
                    id = "dessinanimenet_${sid}",
                    name = "DessinAnime.net · $hosterName",
                    src = embedUrl,
                ),
            )
        }
        Log.i(TAG, "extractServers($pageUrl) '$label' → ${sourceIds.size} source(s) → ${result.size} serveur(s)")
        return result
    }
}
