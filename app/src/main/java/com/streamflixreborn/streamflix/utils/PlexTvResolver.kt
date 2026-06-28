package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-06-26 : résolveur Plex free live TV (FAST, dossier "Plex TV" du TV Hub).
 *
 * Pipeline (calqué sur FrancetvResolver = référence stable côté m3u, résolution
 * fraîche à la lecture, donc jamais d'URL périmée comme l'ancien jmp2.uk) :
 *   1. Token ANONYME (aucun compte) : POST plex.tv/api/v2/users/anonymous
 *      avec headers X-Plex-Product + X-Plex-Client-Identifier → { authToken }.
 *   2. Le scraper émet `plex://<channelId>` (channelId = id du lineup
 *      epg.provider.plex.tv/lineups/plex/channels, stable, auto-refreshé).
 *   3. Stream = master HLS direct :
 *      https://epg.provider.plex.tv/library/parts/<channelId>.m3u8
 *        ?X-Plex-Token=<tok>&X-Plex-Client-Identifier=<uuid>
 *      (le .m3u8 gère la session/stitcher côté serveur → ExoPlayer joue direct).
 */
object PlexTvResolver {
    private const val TAG = "PlexTvResolver"
    private const val PRODUCT = "Plex Mediaverse"
    private const val EPG = "https://epg.provider.plex.tv"
    private const val VOD = "https://vod.provider.plex.tv"
    // 2026-06-27 : Plex géolocalise via X-Forwarded-For. On force une IP
    //   métropolitaine FR → lineup + catalogue FRANCE (50 chaînes fr) quel que
    //   soit le pays réel/VPN du device. Validé : toute IP Free/Orange/SFR → fr=50.
    //   (NB : ne force que le LISTING ; la lecture du flux passe par l'IP réelle
    //   du device — Tahiti = territoire français, donc OK en principe.)
    private const val FR_IP = "82.64.0.1"
    private fun Request.Builder.frGeo(): Request.Builder =
        this.header("X-Forwarded-For", FR_IP).header("X-Real-IP", FR_IP)
    private const val TOKEN_TTL_MS = 6 * 60 * 60 * 1000L // 6h

    // Un client-identifier stable pour la durée du process.
    private val clientId: String = java.util.UUID.randomUUID().toString()

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenTs: Long = 0L

    // 2026-06-27 (user "filtrer : ne garder que les films/séries VF") : cache
    //   par ratingKey du résultat "a une piste audio française". Évite de
    //   re-sonder les métadonnées à chaque ouverture du dossier Plex.
    private val frAudioCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun isPlexUrl(src: String): Boolean = src.startsWith("plex://")

    data class PlexChannel(val id: String, val title: String, val logo: String, val language: String)

    /**
     * Récupère le lineup Plex DEPUIS la connexion de l'appareil (géo-correct :
     * lineup Tahiti par défaut, ou France si l'user a activé son VPN). C'est la
     * raison pour laquelle le scraper (runner US) ne peut PAS fournir ce lineup.
     */
    suspend fun getChannels(): List<PlexChannel> = withContext(Dispatchers.IO) {
        val tok = getToken() ?: return@withContext emptyList()
        try {
            val lu = "$EPG/lineups/plex/channels" +
                "?X-Plex-Token=$tok&X-Plex-Client-Identifier=$clientId"
            val req = Request.Builder().url(lu).header("Accept", "application/json").frGeo().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "lineup HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string().orEmpty()
                val root = JSONObject(body)
                val mc = root.optJSONObject("MediaContainer") ?: root
                val arr = mc.optJSONArray("Channel") ?: mc.optJSONArray("Metadata")
                    ?: return@withContext emptyList()
                val out = ArrayList<PlexChannel>()
                for (i in 0 until arr.length()) {
                    val ch = arr.optJSONObject(i) ?: continue
                    val cid = ch.optString("id").trim()
                    val title = ch.optString("title").trim()
                    if (cid.isBlank() || title.isBlank()) continue
                    if (ch.optBoolean("hidden", false)) continue
                    val logo = ch.optString("coverPoster")
                        .ifBlank { ch.optString("thumb") }
                        .ifBlank { ch.optString("art") }
                    val lang = ch.optString("language").trim().lowercase()
                    out.add(PlexChannel(cid, title, logo, lang))
                }
                Log.d(TAG, "lineup: ${out.size} channels")
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "getChannels failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getToken(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now - tokenTs < TOKEN_TTL_MS) return@withContext it }
        try {
            val req = Request.Builder()
                .url("https://plex.tv/api/v2/users/anonymous")
                .post(ByteArray(0).toRequestBody(null))
                .header("Accept", "application/json")
                .header("X-Plex-Product", PRODUCT)
                .header("X-Plex-Client-Identifier", clientId)
                .frGeo()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "anonymous token HTTP ${resp.code}: ${body.take(120)}")
                    return@withContext null
                }
                val tok = JSONObject(body).optString("authToken")
                    .ifBlank { JSONObject(body).optJSONObject("authentication")?.optString("token").orEmpty() }
                if (tok.isBlank()) {
                    Log.w(TAG, "no authToken in response: ${body.take(120)}")
                    return@withContext null
                }
                cachedToken = tok
                tokenTs = now
                Log.d(TAG, "anonymous token OK")
                tok
            }
        } catch (e: Exception) {
            Log.w(TAG, "token fetch failed: ${e.message}")
            null
        }
    }

    fun isPlexVodUrl(src: String): Boolean = src.startsWith("plexvod://")

    /** Un film VOD Plex. `id` = ratingKey (= metadata id). */
    data class PlexVodItem(val id: String, val title: String, val year: Int, val thumb: String)

    /**
     * 2026-06-27 : catalogue VOD Plex par genre. Réutilise le token anonyme.
     * `GET vod.provider.plex.tv/hubs/sections/movies/<slug>?count=N` →
     * MediaContainer.Metadata[] (films gratuits, géo-corrects via la connexion device).
     */
    suspend fun getVodCategory(slug: String, count: Int = 60): List<PlexVodItem> =
        withContext(Dispatchers.IO) {
            val tok = getToken() ?: return@withContext emptyList()
            try {
                val url = "$VOD/hubs/sections/movies/$slug" +
                    "?count=$count&X-Plex-Token=$tok&X-Plex-Client-Identifier=$clientId"
                val req = Request.Builder().url(url).header("Accept", "application/json").frGeo().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "vod category '$slug' HTTP ${resp.code}")
                        return@withContext emptyList()
                    }
                    val mc = JSONObject(resp.body?.string().orEmpty())
                        .optJSONObject("MediaContainer") ?: return@withContext emptyList()
                    val arr = mc.optJSONArray("Metadata") ?: return@withContext emptyList()
                    val out = ArrayList<PlexVodItem>()
                    for (i in 0 until arr.length()) {
                        val it = arr.optJSONObject(i) ?: continue
                        if (it.optString("type") != "movie") continue
                        val rk = it.optString("ratingKey")
                            .ifBlank { it.optString("key").substringAfterLast('/') }
                        val title = it.optString("title").trim()
                        if (rk.isBlank() || title.isBlank()) continue
                        out.add(PlexVodItem(rk, title, it.optInt("year", 0), it.optString("thumb")))
                    }
                    // 2026-06-27 v2 : filtre VF RÉACTIVÉ en mode FAIL-SAFE
                    //   (movieHasFrenchAudio garde le film en cas d'échec réseau →
                    //   ne vide pas le catalogue). On ne retire que les films dont
                    //   l'absence de VF est CONFIRMÉE (ex "Them" 1954).
                    val fr = filterFrench(out) { movieHasFrenchAudio(it.id) }
                    Log.d(TAG, "vod '$slug': ${out.size} → ${fr.size} films (VF + indéterminés)")
                    fr
                }
            } catch (e: Exception) {
                Log.w(TAG, "getVodCategory('$slug') failed: ${e.message}")
                emptyList()
            }
        }

    /** Une série Plex (type "show"). `id` = ratingKey. */
    data class PlexShow(val id: String, val title: String, val year: Int, val thumb: String)
    data class PlexSeason(val id: String, val title: String, val index: Int)
    data class PlexEpisode(
        val id: String, val title: String, val season: Int, val episode: Int,
        val thumb: String, val overview: String,
    )

    private fun authQuery(tok: String) = "X-Plex-Token=$tok&X-Plex-Client-Identifier=$clientId"

    private suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("Accept", "application/json").frGeo().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) { null }
    }

    // ───────── Filtre VF (par piste AUDIO française) ─────────
    // 2026-06-27 (user "on parle de langues audio, ne supprime pas tous les
    //   autres") : un titre est GARDÉ dès qu'il POSSÈDE une piste audio FR,
    //   même s'il a aussi de l'anglais/d'autres langues. On ne retire QUE les
    //   titres sans AUCUNE piste FR. (streamType==2 = audio dans l'API Plex.)
    private fun metaHasFrenchAudio(meta: JSONObject): Boolean {
        val media = meta.optJSONArray("Media") ?: return false
        for (i in 0 until media.length()) {
            val parts = media.optJSONObject(i)?.optJSONArray("Part") ?: continue
            for (j in 0 until parts.length()) {
                val streams = parts.optJSONObject(j)?.optJSONArray("Stream") ?: continue
                for (k in 0 until streams.length()) {
                    val s = streams.optJSONObject(k) ?: continue
                    if (s.optInt("streamType") != 2) continue
                    val lang = (s.optString("language") + " " +
                        s.optString("languageCode") + " " + s.optString("languageTag")).lowercase()
                    if (lang.contains("french") || lang.contains("français") ||
                        lang.contains("fra") || lang.contains("fre") ||
                        lang.contains("fr-") || Regex("\\bfr\\b").containsMatchIn(lang)) return true
                }
            }
        }
        return false
    }

    /** True si le FILM [rk] a une piste audio FR. Caché. En cas d'échec réseau,
     *  retourne true (on ne filtre pas à l'aveugle pour ne pas tout vider). */
    private suspend fun movieHasFrenchAudio(rk: String): Boolean {
        frAudioCache[rk]?.let { return it }
        val tok = getToken() ?: return true
        val meta = getJson("$VOD/library/metadata/$rk?${authQuery(tok)}")
            ?.optJSONObject("MediaContainer")?.optJSONArray("Metadata")?.optJSONObject(0)
            ?: return true
        val fr = metaHasFrenchAudio(meta)
        frAudioCache[rk] = fr
        return fr
    }

    /** True si la SÉRIE [showRk] a une VF — vérifie le 1er épisode de la 1re saison
     *  (l'audio n'existe qu'au niveau épisode chez Plex). Caché. */
    private suspend fun showHasFrenchAudio(showRk: String): Boolean {
        frAudioCache["show:$showRk"]?.let { return it }
        val tok = getToken() ?: return true
        // FAIL-SAFE (2026-06-27) : on ne retire une série QUE si on a réussi à
        //   lire l'audio du 1er épisode ET qu'il n'a pas de FR. Tout échec réseau
        //   (rate-limit, timeout, structure inattendue) → return true = on GARDE
        //   (et on NE met PAS en cache → re-tenté plus tard). C'est ce qui évite
        //   de vider les séries comme la 1re version.
        val s1 = getJson("$VOD/library/metadata/$showRk/children?${authQuery(tok)}")
            ?.optJSONObject("MediaContainer")?.optJSONArray("Metadata")?.optJSONObject(0)
        val s1rk = s1?.optString("ratingKey")
        if (s1rk.isNullOrBlank()) return true
        val e1 = getJson("$VOD/library/metadata/$s1rk/children?${authQuery(tok)}")
            ?.optJSONObject("MediaContainer")?.optJSONArray("Metadata")?.optJSONObject(0)
        val e1rk = e1?.optString("ratingKey")
        if (e1rk.isNullOrBlank()) return true
        val meta = getJson("$VOD/library/metadata/$e1rk?${authQuery(tok)}")
            ?.optJSONObject("MediaContainer")?.optJSONArray("Metadata")?.optJSONObject(0)
            ?: return true
        val fr = metaHasFrenchAudio(meta)
        frAudioCache["show:$showRk"] = fr  // cache UNIQUEMENT le résultat définitif
        return fr
    }

    /** Filtre une liste en gardant les éléments VF, en parallèle (concurrence 12). */
    private suspend fun <T> filterFrench(items: List<T>, key: suspend (T) -> Boolean): List<T> =
        coroutineScope {
            val sem = Semaphore(5) // concurrence réduite = moins de rate-limit Plex
            val deferreds = items.map { item ->
                async {
                    sem.acquire()
                    try { if (key(item)) item else null } finally { sem.release() }
                }
            }
            deferreds.awaitAll().filterNotNull()
        }

    /** 2026-06-27 : séries d'un hub Plex (type "show"). slug = hub VOD
     *  (drama_tv, crime_tv, binge-worthy-shows, comedy-gold, reality-tv...). */
    suspend fun getVodShows(slug: String, count: Int = 80): List<PlexShow> {
        val tok = getToken() ?: return emptyList()
        val mc = getJson("$VOD/hubs/sections/movies/$slug?count=$count&${authQuery(tok)}")
            ?.optJSONObject("MediaContainer") ?: return emptyList()
        val arr = mc.optJSONArray("Metadata") ?: return emptyList()
        val out = ArrayList<PlexShow>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("type") != "show") continue
            val rk = it.optString("ratingKey").ifBlank { it.optString("key").substringAfterLast('/') }
            val title = it.optString("title").trim()
            if (rk.isBlank() || title.isBlank()) continue
            out.add(PlexShow(rk, title, it.optInt("year", 0), it.optString("thumb")))
        }
        // 2026-06-27 v2 : filtre VF RÉACTIVÉ en mode FAIL-SAFE (showHasFrenchAudio
        //   garde la série en cas d'échec réseau → ne vide plus). On ne retire que
        //   les séries dont l'absence de VF est CONFIRMÉE (ex Bodies, Weird History).
        val fr = filterFrench(out) { showHasFrenchAudio(it.id) }
        Log.d(TAG, "shows '$slug': ${out.size} → ${fr.size} (VF confirmées gardées + indéterminées)")
        return fr
    }

    /** Saisons d'une série : GET /library/metadata/<showRk>/children (type season). */
    suspend fun getSeasons(showRk: String): List<PlexSeason> {
        val tok = getToken() ?: return emptyList()
        val mc = getJson("$VOD/library/metadata/$showRk/children?${authQuery(tok)}")
            ?.optJSONObject("MediaContainer") ?: return emptyList()
        val arr = mc.optJSONArray("Metadata") ?: return emptyList()
        val out = ArrayList<PlexSeason>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("type") != "season") continue
            val rk = it.optString("ratingKey")
            if (rk.isBlank()) continue
            out.add(PlexSeason(rk, it.optString("title").ifBlank { "Saison ${it.optInt("index", i + 1)}" },
                it.optInt("index", i + 1)))
        }
        return out
    }

    /** Épisodes d'une saison : GET /library/metadata/<seasonRk>/children (type episode). */
    suspend fun getEpisodes(seasonRk: String, seasonIndex: Int): List<PlexEpisode> {
        val tok = getToken() ?: return emptyList()
        val mc = getJson("$VOD/library/metadata/$seasonRk/children?${authQuery(tok)}")
            ?.optJSONObject("MediaContainer") ?: return emptyList()
        val arr = mc.optJSONArray("Metadata") ?: return emptyList()
        val out = ArrayList<PlexEpisode>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("type") != "episode") continue
            val rk = it.optString("ratingKey")
            if (rk.isBlank()) continue
            out.add(PlexEpisode(
                id = rk,
                title = it.optString("title").ifBlank { "Épisode ${it.optInt("index", i + 1)}" },
                season = it.optInt("parentIndex", seasonIndex),
                episode = it.optInt("index", i + 1),
                thumb = it.optString("thumb"),
                overview = it.optString("summary"),
            ))
        }
        return out
    }

    /** Résout `plexvod://<metadataId>` → Video HLS VOD jouable (token frais). */
    suspend fun resolveVod(src: String): Video? = withContext(Dispatchers.IO) {
        val rk = src.removePrefix("plexvod://").trim()
        if (rk.isEmpty()) return@withContext null
        val tok = getToken() ?: return@withContext null
        val q = "X-Plex-Token=$tok&X-Plex-Client-Identifier=$clientId"
        try {
            val mdUrl = "$VOD/library/metadata/$rk?$q"
            val req = Request.Builder().url(mdUrl).header("Accept", "application/json").frGeo().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "vod metadata $rk HTTP ${resp.code}")
                    return@withContext null
                }
                val md = JSONObject(resp.body?.string().orEmpty())
                    .optJSONObject("MediaContainer")?.optJSONArray("Metadata")?.optJSONObject(0)
                    ?: return@withContext null
                val media = md.optJSONArray("Media") ?: return@withContext null
                // Cherche une Part HLS (.m3u8) en priorité, sinon DASH (.mpd).
                var hls: String? = null
                var dash: String? = null
                for (i in 0 until media.length()) {
                    val parts = media.optJSONObject(i)?.optJSONArray("Part") ?: continue
                    for (j in 0 until parts.length()) {
                        val key = parts.optJSONObject(j)?.optString("key").orEmpty()
                        if (key.endsWith(".m3u8") && hls == null) hls = key
                        else if (key.endsWith(".mpd") && dash == null) dash = key
                    }
                }
                val partKey = hls ?: dash ?: return@withContext null
                val streamUrl = "$VOD$partKey?$q"
                val type = if (partKey.endsWith(".mpd")) "application/dash+xml"
                           else "application/vnd.apple.mpegurl"
                Log.d(TAG, "resolved plexvod $rk")
                Video(source = streamUrl, type = type,
                    headers = mapOf("X-Forwarded-For" to FR_IP, "X-Real-IP" to FR_IP))
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveVod($rk) failed: ${e.message}")
            null
        }
    }

    /** Résout `plex://<channelId>` → Video HLS jouable (token frais). */
    suspend fun resolve(src: String): Video? {
        val channelId = src.removePrefix("plex://").trim().removePrefix("/library/parts/").removeSuffix(".m3u8")
        if (channelId.isEmpty()) return null
        val tok = getToken() ?: return null
        val url = "$EPG/library/parts/$channelId.m3u8" +
            "?X-Plex-Token=$tok&X-Plex-Client-Identifier=$clientId"
        Log.d(TAG, "resolved plex channel $channelId")
        return Video(
            source = url,
            type = "application/vnd.apple.mpegurl",
            headers = mapOf("X-Forwarded-For" to FR_IP, "X-Real-IP" to FR_IP),
        )
    }
}
