package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
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
            MeritendExtractor(),
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
            // 2026-05-09 : extracteur pour bll.embedseek.com (alias "Bll" dans
            // les listes Movix). Movix l'index pour les nouveautés (films
            // sortis < 7 jours) où Wiflix/FStream/Cpasmal n'ont pas encore
            // indexé. Permet de gagner des sources VF sur les films récents.
            EmbedSeekExtractor(),
            // 2026-05-12 : SendVid — utilisé par AnimeSiteProvider (lecteurs 1 VOSTFR + 5 VF).
            // Page embed contient <source src="..mp4"> direct, hash time-limited ~4h.
            SendvidExtractor(),
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
            // Reset aussi le compteur persistant — l'écran "Extracteurs" affiche
            // donc les échecs CONSÉCUTIFS (depuis le dernier succès), pas le
            // cumul. Détecte mieux les extracteurs vraiment cassés vs bruit.
            com.streamflixreborn.streamflix.utils.ExtractorFailureTracker.recordSuccess(serverName)
        }

        private fun recordFailure(serverName: String, error: Throwable? = null) {
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
            // Persistance pour l'écran "Extracteurs" dans Paramètres
            // (auto-reset au bump de versionCode).
            // On enrichit avec :
            //  - **type d'erreur** : classifié depuis l'exception (timeout, 403, 404…)
            //    pour distinguer "serveur mort" vs "ils ont changé leur HTML".
            //  - **provider source** : lu depuis UserPreferences.currentProvider —
            //    l'utilisateur navigue forcément depuis un provider à un instant T,
            //    donc cette valeur reflète bien d'où vient l'appel (y compris pour
            //    les chaînes de backup type Movix → Cloudstream → ...).
            val errorType = error?.let { classifyError(it) }
            val providerName = runCatching { UserPreferences.currentProvider?.name }.getOrNull()
            com.streamflixreborn.streamflix.utils.ExtractorFailureTracker.recordFailure(
                extractorName = serverName,
                errorType = errorType,
                providerName = providerName,
            )
        }

        /**
         * Classifie une exception en label court, utilisable comme clé dans
         * la breakdown du rapport bug. On marche la cause-chain (les libs HTTP
         * wrap souvent la vraie cause sous IOException/CancellationException).
         *
         * Labels possibles :
         *  - **timeout** : SocketTimeoutException, withTimeout, message "timeout"
         *  - **dns-fail** : UnknownHostException
         *  - **connect-fail** : ConnectException
         *  - **ssl-fail** : SSLException, CertificateException
         *  - **403** / **404** / **5xx** / **http-NNN** : HttpException ou message
         *  - **parsing** : JSONException, NoSuchElementException, NullPointer dans
         *    Jsoup, IndexOutOfBounds (sélecteur cassé)
         *  - **other** : tout le reste
         */
        fun classifyError(e: Throwable): String {
            // Walk causes (jusqu'à 6 niveaux pour éviter une boucle si cycle).
            var cur: Throwable? = e
            var depth = 0
            while (cur != null && depth < 6) {
                when (cur) {
                    is java.net.SocketTimeoutException -> return "timeout"
                    is java.net.UnknownHostException -> return "dns-fail"
                    is java.net.ConnectException -> return "connect-fail"
                    is javax.net.ssl.SSLException -> return "ssl-fail"
                    is java.security.cert.CertificateException -> return "ssl-fail"
                    is org.json.JSONException -> return "parsing"
                    is java.util.NoSuchElementException -> return "parsing"
                    is IndexOutOfBoundsException -> return "parsing"
                    is kotlinx.coroutines.TimeoutCancellationException -> return "timeout"
                    is java.io.InterruptedIOException -> return "timeout"
                }
                // Retrofit HttpException → exposer le code HTTP via reflection.
                if (cur::class.simpleName == "HttpException") {
                    val code = runCatching {
                        cur!!::class.java.getMethod("code").invoke(cur) as? Int
                    }.getOrNull()
                    if (code != null) {
                        return when (code) {
                            403 -> "403"
                            404 -> "404"
                            in 500..599 -> "5xx"
                            else -> "http-$code"
                        }
                    }
                }
                val msg = cur.message?.lowercase().orEmpty()
                when {
                    // 2026-05-09 : "dead-content" — la VIDÉO a été supprimée
                    // côté CDN (Vidoza "File was deleted", Filemoon "video not
                    // found", VOE "title 404", etc.). C'est PAS un bug de
                    // l'extracteur — l'extracteur fait son boulot, c'est juste
                    // le contenu qui a été retiré ou expiré. À distinguer
                    // strictement des autres erreurs (cf [isDeadContentError]
                    // qui décide de ne PAS pénaliser l'extracteur).
                    msg.contains("deleted") -> return "dead-content"
                    msg.contains("video not found") -> return "dead-content"
                    msg.contains("video expired") -> return "dead-content"
                    msg.contains("file removed") -> return "dead-content"
                    msg.contains("file not found") -> return "dead-content"
                    msg.contains("removed by") -> return "dead-content"
                    msg.contains("tombstone") -> return "dead-content"
                    msg.contains("link rot") -> return "dead-content"
                    // 2026-05-09 : Videasy aggregator dit explicitement
                    // "source unavailable" / "enc-dec returned no result"
                    // quand il connaît le film mais aucun de ses CDN sources
                    // ne l'a vraiment. C'est PAS un bug d'extracteur — c'est
                    // juste que le contenu n'est pas hébergé.
                    msg.contains("source unavailable") -> return "dead-content"
                    msg.contains("enc-dec returned no result") -> return "dead-content"
                    msg.contains("no source") -> return "dead-content"
                    msg.contains("no streams") -> return "dead-content"
                    msg.contains("no result") -> return "dead-content"
                    msg.contains("403") && msg.contains("forbidden") -> return "403"
                    msg.contains("404") && msg.contains("not found") -> return "404"
                    msg.contains("timeout") || msg.contains("timed out") -> return "timeout"
                    msg.contains("ssl") -> return "ssl-fail"
                    msg.contains("unable to resolve host") -> return "dns-fail"
                }
                cur = cur.cause
                depth++
            }
            return "other"
        }

        /**
         * Vrai si l'erreur indique que le CONTENU est mort (URL morte,
         * vidéo supprimée, expirée), pas que l'EXTRACTEUR a un bug.
         *
         *  Cas typiques :
         *  - Vidoza "File was deleted" (Movix indexe une URL morte)
         *  - Filemoon "video not found"
         *  - VOE "title 404"
         *  - Filemoon "Filemoon: link rot detected"
         *
         *  Quand vrai → on log dans le tracker pour la visibilité, mais on
         *  N'incrémente PAS le compteur principal (l'extracteur n'est pas
         *  cassé, c'est le provider qui indexe du mort). Le score du ranker
         *  reste sain pour cet extracteur, il garde sa place naturelle.
         */
        fun isDeadContentError(errorType: String): Boolean = errorType == "dead-content"

        /**
         * Returns 1.0 for healthy servers, 0.0 for ones currently in the "broken" window.
         * Providers can use this as an extra sort criterion to deprioritise dodgy servers.
         */
        fun healthScore(serverName: String): Float {
            val rec = serverHealth[serverName] ?: return 1f
            return if (System.currentTimeMillis() < rec.brokenUntilMs) 0f else 1f
        }

        /**
         * Returns the set of extractor names currently flagged broken
         * (≥3 failures in window, brokenUntil > now). Used by PlayerViewModel
         * to re-sort server lists pushing broken ones to bottom.
         */
        fun brokenServerNames(): Set<String> {
            val now = System.currentTimeMillis()
            return serverHealth.entries
                .filter { now < it.value.brokenUntilMs }
                .map { it.key }
                .toSet()
        }

        /** Expose recordFailure pour le pre-extract validation (HEAD check du stream).
         *  Quand le HEAD échoue après extraction réussie, on doit quand même flag
         *  l'extracteur en failure pour que le tri healthScore le pousse en bas.
         *
         *  Pour HEAD-fail c'est une mort certaine (404/timeout du stream final),
         *  on bypass le seuil de 3 failures et on marque broken immédiatement.
         *  Le tri voit broken→bas, fallback skip → user attend pas dessus.
         *  La passe 2 (re-extract sans HEAD) peut un-flag si c'était un faux positif. */
        fun recordFailureExternal(serverName: String, errorTag: String = "external") {
            val now = System.currentTimeMillis()
            val rec = serverHealth.getOrPut(serverName) { ServerHealth() }
            synchronized(rec) {
                rec.firstFailureAtMs = now
                rec.failureCount = HEALTH_FAILURE_THRESHOLD  // trip threshold
                rec.brokenUntilMs = now + HEALTH_BROKEN_DURATION_MS
            }
            Log.w("Extractor", "Server '$serverName' marked broken (external/$errorTag, instant flag, until ${rec.brokenUntilMs})")
            // Track aussi en persistance pour l'écran "Extracteurs"
            val providerName = runCatching { UserPreferences.currentProvider?.name }.getOrNull()
            com.streamflixreborn.streamflix.utils.ExtractorFailureTracker.recordFailure(
                extractorName = serverName,
                errorType = errorTag,
                providerName = providerName,
            )
        }

        /** Expose recordSuccess pour le pre-extract 2nde passe — quand la
         *  ré-extraction sans HEAD check passe, on un-flag le broken pour rendre
         *  le serveur ré-éligible (couvre le cas "HEAD ment, GET marche"). */
        fun recordSuccessExternal(serverName: String) {
            recordSuccess(serverName)
        }

        /** Invalide une entrée du cache d'extraction par URL d'embed. Utilisé par
         *  PlayerViewModel quand la validation HEAD post-extraction échoue, pour
         *  éviter qu'ExoPlayer reçoive ensuite l'URL morte au clic user. */
        fun invalidateCache(embedUrl: String) {
            extractionCache.remove(embedUrl)
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
                // 2026-05-09 : mesure de latence d'extraction (Option B du plan
                // smart sort). Wrapper avec System.currentTimeMillis() avant/après
                // — coût négligeable (<1µs) et 0 impact sur la lecture vidéo.
                // Sur succès → recordExtraction qui alimente la moyenne mobile
                // utilisée par ExtractorRanker pour trier les serveurs.
                val extractStartMs = System.currentTimeMillis()
                val video = try {
                    foundExtractor.extract(finalLink)
                } catch (e: Exception) {
                    // C: track the failure so the provider can grey/sort this server out.
                    // On passe l'exception pour permettre au tracker de classifier
                    // le type d'erreur (timeout / 403 / parsing / …) et de logger
                    // le provider source (UserPreferences.currentProvider) dans le
                    // rapport bug — utile pour debug à distance.
                    recordFailure(name, error = e)
                    throw e
                }
                val extractDurationMs = System.currentTimeMillis() - extractStartMs
                com.streamflixreborn.streamflix.utils.ExtractorLatencyTracker
                    .recordExtraction(name, extractDurationMs)
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
