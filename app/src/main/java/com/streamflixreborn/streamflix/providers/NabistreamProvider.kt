package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.Gson
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * NabistreamProvider — backup NATIF par tmdbId (nabistream.mom, 2026-07-23).
 *
 * Partenaire d'AfterDark, **dramas coréens/asiatiques VOSTFR**, moteur TanStack + Payload CMS.
 * **AUCUNE protection** (pas de Cloudflare, pas d'ad-gate, pas de token à la requête).
 *
 * Flux (reverse dans le Chrome du user) :
 *   - Film    : GET /proxy/api/movies?where[tmdbId][equals]=<tmdb>&limit=1 → doc.id (uuid)
 *   - Série   : GET /proxy/api/shows?where[tmdbId][equals]=<tmdbSérie>&limit=1 → showUuid
 *               puis /proxy/api/episodes?where[show][equals]=<showUuid>&where[seasonNumber][equals]=<s>
 *                    &where[episodeNumber][equals]=<e>&limit=1 → episodeUuid
 *   - Sources : GET /api/stream/<uuid> → { video:{url}, audio:[{lang}], subtitles:[{lang,url}] }
 *               video.url = master HLS `proxy.tanastream.space/file/<ns>/…/index.m3u8` (segments TS,
 *               jouable DIRECT par ExoPlayer). subtitles = pistes {lang,url} → importées dans Video.
 *
 * Source BACKUP uniquement (pas browsable) : appelée par BackupRegistry par tmdbId. La résolution
 * finale (m3u8 + sous-titres) se fait à la LECTURE (getVideo) car l'hôte tanastream porte un token.
 */
object NabistreamProvider {
    private const val TAG = "NabistreamProvider"
    private const val BASE = "https://nabistream.mom"
    private const val SRC_PREFIX = "nabistream::"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ── DTOs ────────────────────────────────────────────────────────────────
    private data class PayloadList(val docs: List<PayloadDoc>? = null)
    private data class PayloadDoc(val id: String? = null, val tmdbId: Long? = null, val title: String? = null)

    private data class StreamResp(
        val title: String? = null,
        val video: StreamVideo? = null,
        val audio: List<StreamTrack>? = null,
        val subtitles: List<StreamTrack>? = null,
    )
    private data class StreamVideo(val url: String? = null, val lang: String? = null)
    private data class StreamTrack(val lang: String? = null, val language: String? = null, val label: String? = null, val url: String? = null)

    // ── HTTP ────────────────────────────────────────────────────────────────
    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Referer", "$BASE/")
            .header("User-Agent", DEFAULT_UA)
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "GET $url → HTTP ${r.code}"); null } else r.body?.string()
        }
    } catch (e: Exception) { Log.w(TAG, "GET $url KO: ${e.message}"); null }

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ── Backup par tmdbId ─────────────────────────────────────────────────────
    suspend fun fetchNabistreamBackupServers(
        tmdbId: String,
        videoType: Video.Type,
        season: Int = 0,
        episode: Int = 0,
        titles: List<String> = emptyList(),
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        // Titres de recherche : le titre du videoType + les knownTitles fournis.
        val titleCandidates = LinkedHashSet<String>()
        when (videoType) {
            is Video.Type.Movie -> videoType.title?.let { titleCandidates.add(it) }
            is Video.Type.Episode -> videoType.tvShow.title?.let { titleCandidates.add(it) }
        }
        titles.forEach { it.takeIf { t -> t.isNotBlank() }?.let(titleCandidates::add) }

        // 1) trouver le CONTENU nabistream — chercher dans les DEUX collections (le type peut diverger :
        //    ONYX peut voir « série » là où nabistream a « movie » et inversement).
        var contentUuid: String? = null
        var fromShows = false

        for (coll in listOf("shows", "movies")) {
            if (contentUuid != null) break
            if (tmdbId.isNotBlank() && tmdbId.all { it.isDigit() }) {
                get("$BASE/proxy/api/$coll?where%5BtmdbId%5D%5Bequals%5D=$tmdbId&limit=1")
                    ?.let { parseList(it)?.firstOrNull()?.id }?.let { contentUuid = it; fromShows = (coll == "shows") }
            }
        }
        // fallback TITRE (tmdbId différent entre ONYX et nabistream) — garde-fou : titre normalisé égal.
        if (contentUuid == null) {
            outer@ for (coll in listOf("shows", "movies")) {
                for (title in titleCandidates) {
                    val body = get("$BASE/proxy/api/$coll?where%5Btitle%5D%5Blike%5D=${enc(title)}&limit=5") ?: continue
                    val docs = parseList(body) ?: continue
                    val wanted = norm(title)
                    for (d in docs) {
                        if (d.id != null && d.title != null && norm(d.title) == wanted) {
                            contentUuid = d.id; fromShows = (coll == "shows"); break@outer
                        }
                    }
                }
            }
        }
        val cUuid = contentUuid ?: return@withContext emptyList()

        // 2) pour un épisode d'une VRAIE série nabistream : résoudre l'episodeUuid ; sinon (film) : direct.
        val streamUuid: String = if (fromShows && videoType is Video.Type.Episode && season > 0 && episode > 0) {
            val epUrl = "$BASE/proxy/api/episodes?where%5Bshow%5D%5Bequals%5D=${enc(cUuid)}" +
                "&where%5BseasonNumber%5D%5Bequals%5D=$season" +
                "&where%5BepisodeNumber%5D%5Bequals%5D=$episode&limit=1"
            get(epUrl)?.let { parseList(it)?.firstOrNull()?.id } ?: cUuid
        } else cUuid

        Log.i(TAG, "Nabistream tmdb=$tmdbId → contentUuid=$cUuid streamUuid=$streamUuid")
        listOf(
            Video.Server(
                id = "nabistream_$streamUuid",
                name = "Nabistream [VOSTFR]",
                src = "$SRC_PREFIX$streamUuid",
            ),
        )
    }

    private fun norm(s: String) = s.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "")

    private fun parseList(body: String): List<PayloadDoc>? =
        try { gson.fromJson(body, PayloadList::class.java)?.docs } catch (e: Exception) { null }

    /** Lecture : résout le m3u8 tanastream + importe les sous-titres FR/EN via /api/stream/<uuid>. */
    suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val uuid = server.src.removePrefix(SRC_PREFIX)
        val body = get("$BASE/api/stream/$uuid") ?: throw Exception("Nabistream: /api/stream KO")
        val resp = try { gson.fromJson(body, StreamResp::class.java) } catch (e: Exception) { null }
            ?: throw Exception("Nabistream: parse stream KO")
        val m3u8 = resp.video?.url?.takeIf { it.startsWith("http") }
            ?: throw Exception("Nabistream: video.url absente")

        val subs = (resp.subtitles ?: emptyList()).mapNotNull { t ->
            val file = t.url?.let { if (it.startsWith("http")) it else "$BASE$it" } ?: return@mapNotNull null
            val lang = (t.lang ?: t.language ?: t.label ?: "sub").lowercase()
            val label = when (lang) {
                "fr", "fre", "fra" -> "Français"
                "en", "eng" -> "English"
                else -> lang.uppercase()
            }
            Video.Subtitle(label = label, file = file, default = lang.startsWith("fr"))
        }

        Video(
            source = m3u8,
            subtitles = subs,
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            headers = mapOf("Referer" to "$BASE/", "User-Agent" to DEFAULT_UA),
        )
    }
}
