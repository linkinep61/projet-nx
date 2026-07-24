package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.providers.FileSearchProvider.AudioResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Request as OkRequest

/**
 * NewPipeAudio — 2e source MUSIQUE (2026-07-24) : recherche + extraction AUDIO YouTube via
 * NewPipeExtractor (lib JVM native, PAS d'instance tierce contrairement à Piped → robuste).
 *
 * Complète FileSearch : FileSearch = mp3 bruts d'open-directories ; NewPipe = tout le reste
 * (tout ce qui est sur YouTube = quasi toute la musique FR). Audio seul, pas de vidéo.
 *
 * Flux :
 *   - search(q) → StreamInfoItem (titre/artiste/durée) + url = page watch YouTube (PAS jouable direct).
 *   - resolveAudioUrl(watchUrl) → URL audio DIRECTE (m4a/webm progressif) → jouée par ExoPlayer.
 *     Résolue paresseusement à la lecture (ResolvingDataSource) pour supporter l'enchaînement.
 */
object NewPipeAudio {
    private const val TAG = "NewPipeAudio"
    @Volatile private var initialized = false
    private val resolveCache = ConcurrentHashMap<String, String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    // Downloader NewPipe adossé à OkHttp.
    private class NpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val builder = OkRequest.Builder().url(request.url())
            for ((name, values) in request.headers()) {
                for (v in values) builder.addHeader(name, v)
            }
            val data = request.dataToSend()
            when (val method = request.httpMethod()) {
                "GET" -> builder.get()
                "POST" -> builder.post((data ?: ByteArray(0)).toRequestBody())
                "HEAD" -> builder.head()
                else -> builder.method(method, data?.toRequestBody())
            }
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val headers = HashMap<String, MutableList<String>>()
                for (n in resp.headers.names()) headers[n] = resp.headers.values(n).toMutableList()
                return Response(resp.code, resp.message, headers, body, resp.request.url.toString())
            }
        }
    }

    @Synchronized
    private fun ensureInit() {
        if (initialized) return
        NewPipe.init(NpDownloader(), Localization("fr", "FR"), ContentCountry("FR"))
        initialized = true
    }

    fun isYouTubeUrl(u: String): Boolean =
        u.contains("youtube.com/watch", true) || u.contains("youtu.be/", true) ||
            u.contains("music.youtube.com", true)

    /** Recherche de morceaux (YouTube Music « songs »). url = page watch (résolue à la lecture).
     *  Pagine jusqu'à `limit` (les pages YouTube font ~20 items). */
    suspend fun search(query: String, limit: Int = 150, maxPages: Int = 8): List<AudioResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        try {
            ensureInit()
            val service = ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(
                q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "",
            )
            val extractor = service.getSearchExtractor(handler)
            extractor.fetchPage()
            val out = ArrayList<AudioResult>()
            val seen = HashSet<String>()
            fun consume(items: List<org.schabi.newpipe.extractor.InfoItem>) {
                for (item in items) {
                    if (item !is StreamInfoItem) continue
                    val url = item.url ?: continue
                    if (!seen.add(url)) continue
                    val name = item.name ?: continue
                    val artist = item.uploaderName ?: ""
                    val dur = item.duration
                    val durStr = if (dur > 0) "%d:%02d".format(dur / 60, dur % 60) else ""
                    val base = if (artist.isNotBlank()) "$name — $artist" else name
                    val title = if (durStr.isNotBlank()) "$base ($durStr)" else base
                    out.add(AudioResult(title = title, url = url, size = "YouTube"))
                    if (out.size >= limit) return
                }
            }
            var page = extractor.initialPage
            consume(page.items)
            var guard = 0
            while (out.size < limit && page.hasNextPage() && guard++ < maxPages) {
                page = extractor.getPage(page.nextPage)
                consume(page.items)
            }
            Log.i(TAG, "search '$q' → ${out.size} morceaux YouTube")
            out
        } catch (e: Exception) {
            Log.w(TAG, "search KO: ${e.message}")
            emptyList()
        }
    }

    /** Résout une URL watch YouTube → URL audio DIRECTE (m4a/webm progressif). Caché en session. */
    fun resolveAudioUrl(watchUrl: String): String? {
        resolveCache[watchUrl]?.let { return it }
        return try {
            ensureInit()
            val se = ServiceList.YouTube.getStreamExtractor(watchUrl)
            se.fetchPage()
            val audios: List<AudioStream> = se.audioStreams
            val progressive = audios.filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank()
            }
            val pool = progressive.ifEmpty { audios.filter { !it.content.isNullOrBlank() } }
            val best = pool.maxByOrNull { it.averageBitrate }
            val url = best?.content
            if (!url.isNullOrBlank()) { resolveCache[watchUrl] = url; url } else null
        } catch (e: Exception) {
            Log.w(TAG, "resolve KO: ${e.message}")
            null
        }
    }
}
