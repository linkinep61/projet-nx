package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.Gson
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AfterDarkProvider — backup NATIF par tmdbId (afterdark06.mom, 2026-07-23).
 *
 * Site : https://afterdark06.mom (SPA TanStack/React « Voirdrama »). API sources en **NDJSON**
 * (une ligne JSON par groupe de providers, streaming progressif) basée sur le TMDB id :
 *   - Film    : <SOURCES>?tmdbId=<id>&type=movie
 *   - Épisode : <SOURCES>?tmdbId=<idSérie>&type=tv&season=<s>&episode=<e>
 * Chaque ligne : { id:"<groupe>", items:[ { service, url, quality, language, type, provider, proxied } ] }.
 *   - type = hls|mp4 (flux direct) ou embed (hébergeur).
 *   - Hébergeurs vus : doodstream(playmogo), uqload, voe, luluvdo, vidmoly, morencius, savefiles,
 *     netu, vidara, vidsonic, hanerix — presque tous déjà gérés par nos extracteurs.
 *   - Les items `proxied=true` passent par proxy.taekong.space (non géré) → on les SAUTE.
 *
 * ⚠ SLUG : l'endpoint streaming est sur un chemin à slug (`/api/staging-<date>-<rnd>/sources`) que le
 * client construit dans un chunk lazy. On le code EN DUR (comme un domaine) ; s'il meurt (404/500),
 * le mettre à jour ici (l'outil de santé des domaines / le reporter de sources le détectera).
 *
 * Source BACKUP uniquement (pas browsable) : appelée par BackupRegistry par tmdbId.
 */
object AfterDarkProvider {
    private const val TAG = "AfterDarkProvider"
    private const val BASE = "https://afterdark06.mom"

    // Slug de l'endpoint streaming (à mettre à jour comme un domaine s'il tourne).
    private const val SOURCES_PATH = "/api/staging-20260420-yuna-hipaa-86nnorn0/sources"

    private val gson = Gson()

    private data class NdGroup(
        val id: String? = null,
        val items: List<NdItem>? = null,
    )

    private data class NdItem(
        val service: String? = null,
        val url: String? = null,
        val quality: String? = null,
        val language: String? = null,
        val type: String? = null,
        val provider: String? = null,
        val proxied: Boolean = false,
    )

    suspend fun fetchAfterDarkBackupServers(
        tmdbId: String,
        videoType: Video.Type,
        season: Int = 0,
        episode: Int = 0,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        if (tmdbId.isBlank() || !tmdbId.all { it.isDigit() }) return@withContext emptyList()
        val query = when (videoType) {
            is Video.Type.Movie -> "tmdbId=$tmdbId&type=movie"
            is Video.Type.Episode -> {
                if (season <= 0 || episode <= 0) return@withContext emptyList()
                "tmdbId=$tmdbId&type=tv&season=$season&episode=$episode"
            }
        }
        val url = "$BASE$SOURCES_PATH?$query"
        // L'API est protégée Cloudflare + AD-GATE (2 clics) → on charge la page /video dans une
        // WebView, on clique la gate (window.open neutralisé), puis on fetch /sources dedans.
        val body = try {
            AfterDarkResolver.fetchSourcesNdjson(url)
        } catch (e: Exception) {
            Log.e(TAG, "fetchAfterDarkBackupServers failed: ${e.message}")
            return@withContext emptyList()
        }
        if (body.isNullOrBlank()) return@withContext emptyList()

        val servers = ArrayList<Video.Server>()
        val seen = HashSet<String>()
        for (line in body.split('\n')) {
            val l = line.trim()
            if (l.isEmpty() || !l.startsWith("{")) continue
            val group = try { gson.fromJson(l, NdGroup::class.java) } catch (e: Exception) { null } ?: continue
            val items = group.items ?: continue
            for (it in items) {
                if (it.proxied) continue // proxy.taekong.space non résolu par nos extracteurs
                val src = it.url?.takeIf { u -> u.startsWith("http") } ?: continue
                if (!seen.add(src)) continue
                val host = runCatching { java.net.URI(src).host?.removePrefix("www.") }.getOrNull() ?: ""
                val svc = it.service?.takeIf { s -> s.isNotBlank() && !s.equals("unknown", true) }
                    ?: it.provider?.takeIf { s -> s.isNotBlank() }
                    ?: host
                val q = it.quality?.takeIf { s -> s.isNotBlank() && !s.equals("unknown", true) }
                val lang = it.language?.takeIf { s -> s.isNotBlank() }?.uppercase() ?: "VF"
                val name = buildString {
                    append("AfterDark · ").append(svc)
                    q?.let { append(" ").append(it) }
                    append(" [").append(lang).append("]")
                }
                servers.add(Video.Server(id = "afterdark-${servers.size}", name = name, src = src))
            }
        }
        Log.i(TAG, "AfterDark tmdb=$tmdbId → ${servers.size} serveurs")
        servers
    }
}
