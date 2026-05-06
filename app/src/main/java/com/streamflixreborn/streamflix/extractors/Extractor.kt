package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.Cache
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

abstract class Extractor {

    abstract val name: String
    abstract val mainUrl: String
    open val aliasUrls: List<String> = emptyList()
    open val rotatingDomain: List<Regex> = emptyList()

    // 2026-05-04 : TTL du cache d'extraction par extracteur. Par défaut 10 min
    // (cf EXTRACTION_TTL_MS) mais Dailymotion utilise des tokens `sec=` court
    // terme et lies à l'IP/session — on les remet à 30s pour éviter l'erreur
    // ExoPlayer "Source error" lors du replay sur un cache HIT périmé.
    // Mettre 0 pour désactiver complètement la mise en cache.
    open val cacheTtlMs: Long = 10L * 60L * 1000L

    // THIS is the main method all subclasses must implement
    abstract suspend fun extract(link: String): Video

    // THIS is a convenience helper
    open suspend fun extract(link: String, server: Video.Server? = null): Video {
        return extract(link)
    }

    // ── Shared HTTP infrastructure ──────────────────────────────────────
    // All extractors should use these instead of creating their own clients.
    // sharedClient uses newBuilder() derivatives so they share the connection pool.

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * HTTP cache for the shared client. 20 MB on disk, lets OkHttp respect Cache-Control
         * headers from upstream provider pages and API endpoints (e.g. Filemoon `details`,
         * Wiflix HTML pages). Falls back to no-cache if appContext isn't ready yet — the
         * client still works, just without HTTP cache.
         */
        private val httpCache: Cache? by lazy {
            runCatching {
                val ctx = StreamFlixApp.instance.applicationContext
                val dir = File(ctx.cacheDir, "extractor-http")
                Cache(dir, 20L * 1024L * 1024L)
            }.getOrNull()
        }

        /** Base OkHttpClient shared by all extractors. Has DoH, redirects, 30s timeouts, User-Agent, HTTP cache. */
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dns(DnsResolver.doh)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .apply { httpCache?.let { cache(it) } }
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .build()
                    chain.proceed(request)
                }
                .build()
        }

        /** Derive a client with a Referer header (shares connection pool with sharedClient). */
        fun clientWithReferer(referer: String): OkHttpClient =
            sharedClient.newBuilder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Referer", referer)
                        .build()
                    chain.proceed(request)
                }
                .build()

        /** Build a Retrofit with JsoupConverterFactory. */
        fun jsoupRetrofit(baseUrl: String, client: OkHttpClient = sharedClient): Retrofit =
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(JsoupConverterFactory.create())
                .build()

        /**
         * Build a Retrofit with ScalarsConverterFactory + GsonConverterFactory.
         * Order matters: Scalars is tried first and only handles primitive return types
         * (String, Boolean, Number). Service methods returning typed objects fall through
         * to Gson. This lets a single Retrofit instance serve services like Rpmvid that
         * receive raw text/hex bodies (`suspend fun get(...): String`) alongside services
         * that deserialize JSON into data classes.
         */
        fun gsonRetrofit(baseUrl: String, client: OkHttpClient = sharedClient): Retrofit =
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create())
                .build()

        /** Inline helper: create a Jsoup-backed Retrofit service in one call. */
        inline fun <reified T> createJsoupService(baseUrl: String, referer: String? = null): T {
            val client = if (referer != null) clientWithReferer(referer) else sharedClient
            return jsoupRetrofit(baseUrl, client).create(T::class.java)
        }

        /** Inline helper: create a Gson-backed Retrofit service in one call. */
        inline fun <reified T> createGsonService(baseUrl: String, referer: String? = null): T {
            val client = if (referer != null) clientWithReferer(referer) else sharedClient
            return gsonRetrofit(baseUrl, client).create(T::class.java)
        }

        private val extractors = listOf(
            RabbitstreamExtractor(),
            RabbitstreamExtractor.MegacloudExtractor(),
            RabbitstreamExtractor.DokicloudExtractor(),
            RabbitstreamExtractor.PremiumEmbedingExtractor(),
            UpzoneExtractor(),
            StreamhubExtractor(),
            VtubeExtractor(),
            VoeExtractor(),
            StreamtapeExtractor(),
            VidozaExtractor(),
            VidsrcToExtractor(),
            VidplayExtractor(),
            FilemoonExtractor(),
            VidplayExtractor.MyCloud(),
            VidplayExtractor.VidplayOnline(),
            MyFileStorageExtractor(),
            MoflixExtractor(),
            MStreamDayExtractor(),
            MStreamClickExtractor(),
            VidsrcNetExtractor(),
            StreamWishExtractor(),
            StreamWishExtractor.UqloadsXyz(),
            StreamWishExtractor.SwishExtractor(),
            StreamWishExtractor.HlswishExtractor(),
            StreamWishExtractor.PlayerwishExtractor(),
            StreamWishExtractor.SwiftPlayersExtractor(),
            TwoEmbedExtractor(),
            ChillxExtractor(),
            ChillxExtractor.JeanExtractor(),
            MoviesapiExtractor(),
            CloseloadExtractor(),
            LuluVdoExtractor(),
            DoodLaExtractor(),
            DoodLaExtractor.DoodLiExtractor(),
            VidPlyExtractor(),
            MagaSavorExtractor(),
            VidMoLyExtractor(),
            VidMoLyExtractor.ToDomain(),
            YflixExtractor(),
            MoiflixExtractor(),
            PapadustreamExtractor(),
            MovieboxExtractor(),
            VideoSibNetExtractor(),
            LpayerExtractor(),
            SaveFilesExtractor(),
            BigWarpExtractor(),
            DoodLaExtractor.DoodExtractor(),
            LoadXExtractor(),
            VidHideExtractor(),
            VeevExtractor(),
            RidooExtractor(),
            USTRExtractor(),
            VidGuardExtractor(),
            OkruExtractor(),
            VixSrcExtractor(),
            GoodstreamExtractor(),
            LamovieExtractor(),
            UqloadExtractor(),
            MailRuExtractor(),
            MixDropExtractor(),
            SupervideoExtractor(),
            DroploadExtractor(),
            RpmvidExtractor(),
            YourUploadExtractor(),
            PlusPomlaExtractor(),
            OneuploadExtractor(),
            FsvidExtractor(),
            GoogleDriveExtractor(),
            PcloudExtractor(),
            AmazonDriveExtractor(),
            VidzyExtractor(),
            GuploadExtractor(),
            StreamUpExtractor(),
            EinschaltenExtractor(),
            VidLinkExtractor(),
            VidsrcRuExtractor(),
            VidflixExtractor(),
            VidrockExtractor(),
            VideasyExtractor(),
            VidzeeExtractor(),
            VidnestExtractor(),
            PrimeSrcExtractor(),
            VidoraExtractor(),
            GxPlayerExtractor(),
            UpZurExtractor(),
            DailymotionExtractor(),
            ApiVoirFilmExtractor(),
            StreamixExtractor(),
            ShareCloudyExtractor(),
            StreamrubyExtractor(),
            VidaraExtractor(),
            VidsonicExtractor(),
            HxfileExtractor(),
            ZillaExtractor(),
            PDrainExtractor(),
            KakaflixExtractor(),
            NetuExtractor(),
            SeekPlaysExtractor(),
            XshotcokExtractor(),
            DarkiboxExtractor(),
            Up4StreamExtractor(),
            // 2026-05-04 : nouveaux extracteurs pour les vieilles séries FR
            // (NY911, Friends, etc.) — backend allostreaming.one / waaatch.art
            // qui sert via embed.maz.quest -> Yandex Disk public API.
            MazQuestExtractor(),
            YandexDiskExtractor(),
        )

        // ── A: Extraction cache ─────────────────────────────────────────────
        // Memoise resolved Video objects by source link. Avoids replaying the full
        // pipeline (DNS → details → playback POST → AES decrypt → m3u8) when the user
        // re-selects the same server within the cache window. m3u8 URLs carry tokens
        // typically valid for 1–3h server-side; we use a conservative TTL of 10 min so
        // we don't hand back URLs about to expire mid-playback.
        private const val EXTRACTION_TTL_MS = 10L * 60L * 1000L
        private data class CachedExtraction(val video: Video, val expiresAtMillis: Long)
        private val extractionCache = ConcurrentHashMap<String, CachedExtraction>()

        // ── C: Server health tracking ──────────────────────────────────────
        // Per-extractor counters of recent failures. The provider's server-list sort
        // uses healthScore() to push servers that just blew up to the bottom, so the
        // user doesn't waste taps on something we know to be broken.
        private const val HEALTH_FAILURE_THRESHOLD = 3      // failures within window
        private const val HEALTH_FAILURE_WINDOW_MS = 5L * 60L * 1000L
        private const val HEALTH_BROKEN_DURATION_MS = 5L * 60L * 1000L
        private data class ServerHealth(
            var failureCount: Int = 0,
            var firstFailureAtMs: Long = 0L,
            var brokenUntilMs: Long = 0L,
        )
        private val serverHealth = ConcurrentHashMap<String, ServerHealth>()

        private fun recordSuccess(serverName: String) {
            // Successful extraction resets the failure counter for that server.
            serverHealth.remove(serverName)
        }

        private fun recordFailure(serverName: String) {
            val now = System.currentTimeMillis()
            val rec = serverHealth.getOrPut(serverName) { ServerHealth() }
            synchronized(rec) {
                if (now - rec.firstFailureAtMs > HEALTH_FAILURE_WINDOW_MS) {
                    rec.firstFailureAtMs = now
                    rec.failureCount = 1
                } else {
                    rec.failureCount++
                }
                if (rec.failureCount >= HEALTH_FAILURE_THRESHOLD) {
                    rec.brokenUntilMs = now + HEALTH_BROKEN_DURATION_MS
                    Log.w("Extractor", "Server '$serverName' marked broken until ${rec.brokenUntilMs} (${rec.failureCount} failures in window)")
                }
            }
        }

        /**
         * Returns 1.0 for healthy servers, 0.0 for ones currently in the "broken" window.
         * Providers can use this as an extra sort criterion to deprioritise dodgy servers.
         */
        fun healthScore(serverName: String): Float {
            val rec = serverHealth[serverName] ?: return 1f
            return if (System.currentTimeMillis() < rec.brokenUntilMs) 0f else 1f
        }

        suspend fun extract(link: String, server: Video.Server? = null): Video {
            Log.d("Extractor", "extract() called with link=$link server=${server?.name}")

            // A: cache hit?
            extractionCache[link]?.let { cached ->
                if (System.currentTimeMillis() < cached.expiresAtMillis) {
                    Log.i("StreamFlixES", "[EXTRACTOR] -> Cache HIT for $link")
                    return cached.video
                }
                extractionCache.remove(link)
            }

            var finalLink = link

            // 1. RISOLUZIONE BRIDGE UNIVERSALE (StreamHG/Sync/Cuevana)
            // Facciamo questo PRIMA di cercare l'estrattore perché il link bridge (es. mysync.mov)
            // non appartiene a nessun estrattore specifico, ma il link risolto sì (es. filemoon).
            if (link.contains("mysync.mov/stream/")) {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build()
                    
                    val responseBody = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val request = okhttp3.Request.Builder()
                            .url(link)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                            .build()
                        client.newCall(request).execute().use { it.body?.string() }
                    } ?: ""
                    
                    val redirectUrl = responseBody.substringAfter("window.location.replace(\"", "").substringBefore("\"")
                        .ifEmpty { responseBody.substringAfter("window.location.href = \"", "").substringBefore("\"") }
                        .ifEmpty { responseBody.substringAfter("src=\"", "").substringBefore("\"") }
                    
                    if (redirectUrl.isNotEmpty() && redirectUrl.startsWith("http")) {
                        Log.d("Extractor", "Universal Bridge resolved: $link -> $redirectUrl")
                        finalLink = redirectUrl
                    }
                } catch (e: Exception) {
                    Log.e("Extractor", "Universal Bridge error: ${e.message}")
                }
            }

            val urlRegex = Regex("^(https?://)?(www\\.)?")
            val compareUrl = finalLink.lowercase().replace(urlRegex, "")

            var foundExtractor: Extractor? = null

            for (extractor in extractors) {
                if (compareUrl.startsWith(extractor.mainUrl.replace(urlRegex, ""))) {
                    foundExtractor = extractor
                    break
                } else {
                    for (aliasUrl in extractor.aliasUrls) {
                        if (compareUrl.startsWith(aliasUrl.lowercase().replace(urlRegex, ""))) {
                            foundExtractor = extractor
                            break
                        }
                    }
                }
                if (foundExtractor != null) break
            }

            if (foundExtractor == null) {
                for (extractor in extractors) {
                    if (compareUrl.startsWith(
                            extractor.mainUrl.replace(
                                Regex("^(https?://)?(www\\.)?(.*?)(\\.[a-z]+)"),
                                "$3"
                            )
                        )
                    ) {
                        foundExtractor = extractor
                        break
                    } else {
                        for (aliasUrl in extractor.aliasUrls) {
                            if (compareUrl.startsWith(
                                    aliasUrl.replace(
                                        Regex("^(https?://)?(www\\.)?(.*?)(\\.[a-z]+)"),
                                        "$3"
                                    )
                                )
                            ) {
                                foundExtractor = extractor
                                break
                            }
                        }
                    }
                    if (foundExtractor != null) break
                }
            }

            if (foundExtractor == null) {
                for (extractor in extractors) {
                    if (extractor.rotatingDomain.any { it.containsMatchIn(compareUrl) }) {
                        foundExtractor = extractor
                        break
                    }
                }
            }

            if (foundExtractor == null) {
                for (extractor in extractors) {
                    if ((server?.name?.lowercase() ?: "").contains(extractor.name.lowercase())) {
                        foundExtractor = extractor
                        break
                    }
                }
            }

            if (foundExtractor != null) {
                Log.i("StreamFlixES", "[EXTRACTOR] -> Starting: ${foundExtractor.name} (URL: $finalLink)")
                val name = foundExtractor.name
                val video = try {
                    foundExtractor.extract(finalLink)
                } catch (e: Exception) {
                    // C: track the failure so the provider can grey/sort this server out
                    recordFailure(name)
                    throw e
                }
                Log.i("StreamFlixES", "[VIDEO] -> Extracted: ${video.source}")
                // C: success → wipe any prior failure counter
                recordSuccess(name)

                // App-wide subtitle filter (French only).
                val filtered = enforceFrenchSubtitlesOnly(video)

                // A: stash the resolved video so the next click on the same server is instant.
                // Cached against the original link (pre-bridge resolution) so the lookup at the
                // top of extract() actually hits.
                // TTL spécifique à l'extracteur (Dailymotion = 30s, défaut = 10 min).
                if (foundExtractor.cacheTtlMs > 0L) {
                    extractionCache[link] = CachedExtraction(
                        video = filtered,
                        expiresAtMillis = System.currentTimeMillis() + foundExtractor.cacheTtlMs,
                    )
                }
                return filtered
            }

            Log.e("Extractor", "No extractors found for URL: $finalLink (original: $link)")
            throw Exception("No extractors found for URL: $finalLink")
        }

        /**
         * App-wide policy: keep ONLY French subtitles in the Video returned to the player.
         * Streamflix is a French-audience app — every other language is dead weight in the
         * subtitle picker. Centralised here so all current and future extractors / providers
         * inherit the behaviour automatically (no need to patch each one individually).
         *
         * If a French track is the sole survivor and none was already marked default, we
         * promote it so the player auto-enables it for non-French audio.
         */
        private fun enforceFrenchSubtitlesOnly(video: Video): Video {
            if (video.subtitles.isEmpty()) return video
            val frenchSubs = video.subtitles.filter { isFrenchSubtitle(it.label) }
            if (frenchSubs.size == video.subtitles.size) {
                // already French-only, nothing to change
                return video
            }
            Log.d("Extractor", "Subtitle filter — kept ${frenchSubs.size} FR / dropped ${video.subtitles.size - frenchSubs.size} non-FR")

            // Auto-default if a single FR sub remains and none was flagged default already.
            val finalSubs = if (frenchSubs.size == 1 && frenchSubs.none { it.default }) {
                listOf(frenchSubs.first().copy(default = true))
            } else {
                frenchSubs
            }
            return video.copy(subtitles = finalSubs)
        }

        /**
         * Heuristic: is this subtitle label French?
         * Matches: fr, fre, fra, fr-FR, fr-CA, french, français, francais, vf, vff, etc.
         * Case-insensitive, accent-tolerant.
         */
        fun isFrenchSubtitle(label: String): Boolean {
            val lower = label.lowercase().trim()
            if (lower.isEmpty()) return false

            // Exact short codes
            if (lower in setOf("fr", "fre", "fra", "fr-fr", "fr-ca", "fr_fr", "fr_ca", "vf", "vff", "vfq", "vfi")) return true

            // Substring-based (covers labels like "Français (France)", "French (Canada)", "VOSTFR")
            return lower.contains("french") ||
                    lower.contains("francais") ||
                    lower.contains("français") ||
                    lower.startsWith("fr-") ||
                    lower.startsWith("fr_") ||
                    lower.contains(Regex("\\bvf\\b")) ||
                    lower.contains("vostfr")
        }

        /**
         * Identify the extractor/service name for a given URL.
         * Returns the extractor name (e.g. "Filemoon", "Vidara", "Rpmvid") or null if unknown.
         */
        fun identifyServiceName(url: String): String? {
            val urlRegex = Regex("^(https?://)?(www\\.)?")
            val compareUrl = url.lowercase().replace(urlRegex, "")

            for (extractor in extractors) {
                if (compareUrl.startsWith(extractor.mainUrl.replace(urlRegex, ""))) {
                    return extractor.name
                }
                for (aliasUrl in extractor.aliasUrls) {
                    if (compareUrl.startsWith(aliasUrl.lowercase().replace(urlRegex, ""))) {
                        return extractor.name
                    }
                }
            }
            // Fallback: match base domain name without TLD
            for (extractor in extractors) {
                val baseName = extractor.mainUrl.replace(Regex("^(https?://)?(www\\.)?(.*?)(\\.[a-z]+)"), "$3")
                if (compareUrl.startsWith(baseName)) {
                    return extractor.name
                }
            }
            return null
        }
    }
}
