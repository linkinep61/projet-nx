package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.Gson
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * FileSearchProvider — backup NATIF par TITRE (filesearch.tools, 2026-07-24).
 *
 * Indexeur d'**open-directories** multi-hôtes : renvoie des fichiers VIDÉO **DIRECTS**
 * (.mp4/.mkv/.avi/.webm) hébergés un peu partout (archive.org, IP perso, serveurs VOD).
 * **AUCUNE protection** (pas de Cloudflare, pas de token, pas d'ad-gate).
 *
 * API (reverse dans le Chrome du user) :
 *   GET /api/files/search?q=<titre>&page=1&sort_by=time&order=desc&category=video
 *   → { files:[{name, path, size, size_bytes, time}], total, pages }
 *   `path` = URL DIRECTE du fichier → lue en NATIF par ExoPlayer (aucun extracteur).
 *
 * Pièges gérés :
 *   - L'API casse sur les tirets/guillemets/ponctuation (« Invalid search syntax ») → requête
 *     nettoyée (alphanumérique + espaces ; les accents sont normalisés côté API mais on les
 *     retire aussi pour être sûr).
 *   - Les `name` sont des fichiers BRUTS avec tags de release → matching STRICT obligatoire
 *     (titre + année pour les films, SxxExx pour les séries) sinon on sert le mauvais fichier.
 *
 * Source BACKUP uniquement (par titre) : appelée par BackupRegistry. Non browsable.
 */
object FileSearchProvider {
    private const val TAG = "FileSearchProvider"
    private const val BASE = "https://filesearch.tools"
    const val SRC_PREFIX = "filesearch::"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

    private val DIRECT_EXTS = setOf("mp4", "mkv", "avi", "webm", "m4v", "mov")
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma")
    private const val MAX_SERVERS = 12

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class SearchResp(val files: List<FileItem>? = null, val total: Int = 0)
    private data class FileItem(val name: String? = null, val path: String? = null, val size: String? = null)

    // ── HTTP ──────────────────────────────────────────────────────────────────
    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .header("Referer", "$BASE/")
            .build()
        client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) { Log.w(TAG, "get KO: ${e.message}"); null }

    // ── Normalisation ───────────────────────────────────────────────────────────
    private fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Titre → forme normalisée (minuscule, sans accent, alphanum+espaces). */
    private fun norm(s: String): String =
        stripAccents(s).lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Nettoie la requête pour l'API (qui rejette tirets/guillemets/ponctuation). */
    private fun sanitizeQuery(s: String): String =
        stripAccents(s).replace(Regex("[^A-Za-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun extToMime(ext: String): String? = when (ext) {
        "mp4", "m4v", "mov" -> androidx.media3.common.MimeTypes.VIDEO_MP4
        "mkv" -> androidx.media3.common.MimeTypes.VIDEO_MATROSKA
        "webm" -> androidx.media3.common.MimeTypes.VIDEO_WEBM
        "avi" -> "video/x-msvideo"
        else -> null
    }

    private fun extOf(url: String): String =
        Regex("\\.([a-z0-9]{2,4})(?:\\?|$)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase() ?: ""

    private fun qualityOf(name: String): String =
        Regex("(2160p|1440p|1080p|720p|480p|4k)", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.uppercase() ?: ""

    private fun langOf(name: String): String {
        val n = name.uppercase()
        return when {
            "MULTI" in n -> "MULTI"
            "TRUEFRENCH" in n -> "TRUEFRENCH"
            Regex("\\bVFF\\b").containsMatchIn(n) -> "VFF"
            "FRENCH" in n || Regex("\\bVF\\b").containsMatchIn(n) -> "FR"
            "VOSTFR" in n -> "VOSTFR"
            else -> ""
        }
    }

    /** Classement : FR/MULTI d'abord, puis meilleure qualité. */
    private fun frenchScore(name: String): Int {
        val l = langOf(name)
        val langPts = when (l) { "MULTI", "TRUEFRENCH", "VFF", "FR" -> 100; "VOSTFR" -> 40; else -> 0 }
        val q = qualityOf(name)
        val qPts = when (q) { "2160P", "4K" -> 5; "1440P" -> 4; "1080P" -> 3; "720P" -> 2; "480P" -> 1; else -> 0 }
        return langPts + qPts
    }

    // ── FETCH (par titre, matching strict) ────────────────────────────────────────
    suspend fun fetchFileSearchBackupServers(
        videoType: Video.Type,
        season: Int,
        episode: Int,
        year: Int?,
        titles: List<String>,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        val isMovie = videoType is Video.Type.Movie
        val wantTitles = titles.mapNotNull { it.trim().takeIf { t -> t.isNotBlank() } }.distinct()
        if (wantTitles.isEmpty()) return@withContext emptyList()
        val wantNorm = wantTitles.map { norm(it) }.filter { it.isNotBlank() }.toHashSet()
        if (wantNorm.isEmpty()) return@withContext emptyList()

        // Regex épisode (série) : S01E02 / 1x02, tolérant aux zéros/séparateurs.
        val seRegex = if (!isMovie && season > 0 && episode > 0)
            Regex("(?:s0*${season}e0*${episode}|(?<![0-9])${season}x0*${episode})(?![0-9])", RegexOption.IGNORE_CASE)
        else null

        val out = LinkedHashMap<String, Video.Server>() // dédup par URL
        for (title in wantTitles.take(3)) {
            val q = sanitizeQuery(title)
            if (q.isBlank()) continue
            val url = "$BASE/api/files/search?q=${URLEncoder.encode(q, "UTF-8")}" +
                "&page=1&sort_by=time&order=desc&category=video"
            val body = get(url) ?: continue
            val resp = try { gson.fromJson(body, SearchResp::class.java) } catch (e: Exception) { null } ?: continue
            val files = resp.files ?: continue

            for (f in files) {
                val path = f.path ?: continue
                if (path.isBlank() || out.containsKey(path)) continue
                if (extOf(path) !in DIRECT_EXTS) continue

                val rawName = try { URLDecoder.decode(f.name ?: "", "UTF-8") } catch (e: Exception) { f.name ?: "" }
                val nName = norm(rawName)                 // nom fichier normalisé
                val nCompact = nName.replace(" ", "")     // pour SxxExx collé

                // 1) le TITRE doit être présent dans le nom du fichier
                if (wantNorm.none { it.isNotBlank() && nName.contains(it) }) continue

                // 2) film → l'ANNÉE (si connue) doit figurer ; série → le SxxExx doit matcher
                if (isMovie) {
                    if (year != null && year > 1900 && !nName.contains(year.toString())) continue
                } else {
                    val re = seRegex ?: continue
                    if (!re.containsMatchIn(nName) && !re.containsMatchIn(nCompact)) continue
                }

                val lang = langOf(rawName)
                val qual = qualityOf(rawName)
                val label = buildString {
                    append("FileSearch")
                    if (lang.isNotBlank()) append(" · $lang")
                    if (qual.isNotBlank()) append(" · $qual")
                    f.size?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
                out[path] = Video.Server(id = SRC_PREFIX + path, name = label, src = path)
                if (out.size >= MAX_SERVERS) break
            }
            if (out.size >= MAX_SERVERS) break
        }

        val ranked = out.values.sortedByDescending { frenchScore(it.name) }
        Log.i(TAG, "FileSearch '${wantTitles.firstOrNull()}' → ${ranked.size} fichiers directs")
        ranked
    }

    // ── RECHERCHE AUDIO (musique — fichiers .mp3/.m4a directs) ─────────────────────
    /** Résultat audio direct (lu par le mini-player radio via son URL). */
    data class AudioResult(val title: String, val url: String, val size: String)

    /**
     * Recherche de fichiers AUDIO directs par titre/artiste (category=audio).
     * Pas de matching strict : l'utilisateur PARCOURT de la musique (comme une radio) →
     * on renvoie tout ce qui matche la requête, dédupliqué par URL.
     */
    suspend fun searchAudio(query: String, limit: Int = 300, maxPages: Int = 5): List<AudioResult> = withContext(Dispatchers.IO) {
        val q = sanitizeQuery(query)
        if (q.isBlank()) return@withContext emptyList()
        val out = LinkedHashMap<String, AudioResult>()
        for (page in 1..maxPages) {
            val url = "$BASE/api/files/search?q=${URLEncoder.encode(q, "UTF-8")}" +
                "&page=$page&sort_by=time&order=desc&category=audio"
            val body = get(url) ?: break
            val resp = try { gson.fromJson(body, SearchResp::class.java) } catch (e: Exception) { null } ?: break
            val files = resp.files ?: break
            if (files.isEmpty()) break
            for (f in files) {
                val path = f.path ?: continue
                if (path.isBlank() || out.containsKey(path)) continue
                if (extOf(path) !in AUDIO_EXTS) continue
                val name = try { URLDecoder.decode(f.name ?: "", "UTF-8") } catch (e: Exception) { f.name ?: "" }
                out[path] = AudioResult(
                    title = name.ifBlank { path.substringAfterLast('/') },
                    url = path,
                    size = f.size ?: "",
                )
                if (out.size >= limit) break
            }
            if (out.size >= limit) break
        }
        Log.i(TAG, "searchAudio '$q' → ${out.size} morceaux")
        out.values.toList()
    }

    // ── GETVIDEO (fichier DIRECT → lecture native) ────────────────────────────────
    fun getVideo(server: Video.Server): Video {
        val url = server.src
        return Video(
            source = url,
            type = extToMime(extOf(url)),
            headers = mapOf("User-Agent" to UA),
        )
    }
}
