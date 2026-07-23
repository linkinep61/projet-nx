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
                // 2026-07-07 : callTimeout = plafond ABSOLU par appel HTTP complet.
                //   Sans ça, un Call bloqué dans execute() ne se libère JAMAIS (le
                //   readTimeout est par-segment, pas par-appel). Résultat : les threads
                //   de BackupRegistry restent bloqués, le flux serveur fige, le timeout
                //   coroutine (45s) ne peut pas interrompre le thread natif → blocage
                //   permanent sur Chromecast. callTimeout programme un watchdog OkHttp
                //   qui Call.cancel() au niveau socket → IOException → thread libéré.
                .callTimeout(30, TimeUnit.SECONDS)
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
            AnonMp4Extractor(),
            OnRegardeOuExtractor(),
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
            // JeanExtractor retiré (NXDOMAIN)
            MoviesapiExtractor(),
            CloseloadExtractor(),
            LuluVdoExtractor(),
            DoodLaExtractor(),
            DoodLaExtractor.DoodLiExtractor(),
            VidPlyExtractor(),
            MagaSavorExtractor(),
            VidMoLyExtractor(),
            VidMoLyExtractor.ToDomain(),
            // 2026-07-22 (user « tant qu'on n'a pas l'équivalent FR, désactive-le ») : yflix est
            //   un site anglophone international (contenu NON-FRANÇAIS ; l'ancien yflix.to n'était
            //   français que via Google Translate). Hors périmètre ONYX → extracteur DÉSACTIVÉ.
            //   Réactiver (décommenter) si un équivalent réellement français est trouvé.
            // YflixExtractor(),
            MeritendExtractor(),
            MoiflixExtractor(),
            // 2026-07-17 : nouveaux hosters vus côté FrenchStream / Flemmix(Wiflix)
            //   qui remontaient « No extractors found ». Extraction 100 % native
            //   (API JSON en clair, aucune WebView) — cf. en-têtes des fichiers.
            VidaraExtractor(),   // viewdara.com / vidara.to → POST /api/stream
            // 2026-07-17 : AnonMp4Extractor RETIRÉ (décision user).
            //   L'extraction native est impossible : `tracks[].track_url` n'est
            //   servable que depuis le contexte de la page, et le player ne
            //   s'initialise que sur un vrai geste utilisateur (profil Abyss).
            //   Seul l'overlay WebView aurait marché → jugé pas rentable pour
            //   1 serveur sur les 15 que propose Wiflix, d'autant que l'auto-switch
            //   bascule déjà tout seul sur un autre serveur.
            //   Serveur MASQUÉ à la source (MovixProvider) + extracteur retiré ici
            //   (inutile d'appeler un truc qui échoue). Fichier gardé pour ref.
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
            // 2026-07-23 : importés depuis streamflix-reborn (upstream) — hosts qu'on
            //   n'avait pas. Additifs, domaines vérifiés sans chevauchement avec l'existant.
            //   (VidxGo et AfterDark NON importés : le 1er exige un champ player maintainToken
            //    absent chez nous, le 2ᵉ est un agrégateur qui se câble dans un provider, pas
            //    un extracteur d'URL — évités pour ne rien casser.)
            VixcloudExtractor(),   // vixcloud.co (famille VixSrc)
            MaxstreamExtractor(),  // maxstream.video
            NuuploadExtractor(),   // nupload.top / nupload.me
            NekostreamExtractor(), // vidtube.site / megaplay.buzz (anime)
            StreamSBExtractor(),   // streamsb + alias (packed)
            Mp4UploadExtractor(),  // mp4upload.com (packed)
            StreamlareExtractor(), // streamlare.com (packed)
            NinjaStreamExtractor(),// ninjastream (packed, rotating)
            UchExtractor(),        // uch (packed, rotating)
            GoodstreamExtractor(),
            LamovieExtractor(),
            UqloadExtractor(),
            // 2026-05-14 : ajouts pour DessinAnime (hydrax + 4meplayer)
            HydraxExtractor(),
            // 2026-05-15 : ajout Hoca8 pour chaînes sport/Canal+ Live de LiveTvHub
            Hoca8Extractor(),
            // 2026-05-15 : Freeshot pour les ~48 chaînes FR de freeshot.live
            FreeshotExtractor(),
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
            // 2026-07-13 : kakaflix.lol MORT mais kokoflix.lol VIVANT (même famille).
            // Transformé en handler proxy générique : kokoflix + kakaflix + newPlayer.php.
            // Suit le redirect HTTP → délègue à l'extracteur du domaine résolu.
            KakaflixExtractor(),
            NetuExtractor(),
            SeekPlaysExtractor(),
            // 2026-07-09 : nouveau domaine + chiffrement de SeekStreaming (seekplayer.vip/.me)
            //   → extraction via WebView (leur JS déchiffre, on capte le m3u8).
            SeekPlayerExtractor(),
            // 2026-07-16 : SwiftFlow (swiftflow.lol) — player Movix à ad-gate qui sert un mp4
            //   citron-edge signé. Joué dans l'overlay WebView (même pattern que SeekStreaming).
            SwiftFlowExtractor(),
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
            // 2026-05-16 : Smoothpre — host JWPlayer packed JS (pattern VidHide-like)
            // utilisé par DessinAnime. URL `https://smoothpre.com/embed/<slug>`.
            // Extracteur dédié plus fiable que l'alias StreamWish (qui forçait un
            // WebView-redirect inutile sur ce domaine).
            SmoothpreExtractor(),
            // 2026-05-16 : FRAnime watch2 resolver — WebView intercepte iframe vers
            // sibnet/sendvid/filemoon/vidmoly, puis délègue à l'extractor spécifique.
            FranimeExtractor(),
            // 2026-05-16 : VK.com video embeds — utilisé par FRAnime (5232 occurrences
            // sur les 2370 animes du catalogue). Pattern playerParams JS avec qualités
            // url240/360/480/720/1080 + hls.
            VkExtractor(),
            // 2026-07-13 : Emmmmbed / "PlayerBx" (mirror RNVIDS lecteurvideo, serveur
            //   Coflix Boston). Page video.js + crypto-js qui déchiffre un .mp4 rumble.cloud
            //   côté client → WebView headless capte le mp4.
            EmmmmbedExtractor(),
            // 2026-07-13 : Streamhg (EarnVids) = Cloudflare Turnstile non passable en headless
            //   depuis l'IP courante → serveur MASQUÉ à la source (CoflixSourceProvider) et
            //   extracteur RETIRÉ (inutile d'appeler un truc qui échoue). Fichier gardé pour ref.
        )

        // 2026-05-27 : expose la liste des noms d'extracteurs pour le toggle UI.
        fun allExtractorNames(): List<String> =
            extractors.map { it.name }.distinct().sorted()

        // ── A: Extraction cache ─────────────────────────────────────────────
        // Memoise resolved Video objects by source link. Avoids replaying the full
        // pipeline (DNS → details → playback POST → AES decrypt → m3u8) when the user
        // re-selects the same server within the cache window. m3u8 URLs carry tokens
        // typically valid for 1–3h server-side; we use a conservative TTL of 10 min so
        // we don't hand back URLs about to expire mid-playback.
        private const val EXTRACTION_TTL_MS = 10L * 60L * 1000L
        private data class CachedExtraction(val video: Video, val expiresAtMillis: Long)
        private val extractionCache = ConcurrentHashMap<String, CachedExtraction>()

        /** 2026-07-04 : vide le cache d'extraction global + health tracking.
         *  Appelé par ProviderCacheRefresh. Le serverHealth reset évite qu'un
         *  serveur marqué "broken" sur un titre précédent reste bloqué à tort.
         *  2026-07-07 : AJOUT éviction du cache DISQUE HTTP (20MB à
         *  cacheDir/extractor-http). Ce cache était JAMAIS vidé — des réponses
         *  HTTP erreur cachées sur disque bloquaient TOUS les appels suivants
         *  (notamment CloudstreamProvider qui hérite de sharedClient via
         *  newBuilder()). Seul le clear-data manuel l'effaçait. */
        fun clearAllCache() {
            extractionCache.clear()
            serverHealth.clear()
            // Éviction du cache disque OkHttp de l'extracteur
            runCatching {
                httpCache?.evictAll()
                android.util.Log.d("Extractor", "Extractor HTTP disk cache evicted (extractor-http)")
            }.onFailure {
                android.util.Log.w("Extractor", "Extractor HTTP disk cache evict failed: ${it.message}")
            }
        }

        /**
         * Lecture NON-DESTRUCTIVE du cache d'extraction.
         * Retourne le [Video] caché si l'entrée existe ET n'est pas expirée,
         * sinon null. Ne retire PAS l'entrée du cache (contrairement à extract()
         * qui la consomme). Utilisé par le probe qualité headless pour éviter
         * de ré-extraire un serveur déjà pré-extrait.
         */
        fun peekCachedVideo(embedUrl: String): Video? {
            val cached = extractionCache[embedUrl] ?: return null
            return if (System.currentTimeMillis() < cached.expiresAtMillis) cached.video else null
        }

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
            // Reset aussi le compteur « extracteur mort » (un succès = l'extracteur vit).
            runCatching {
                com.streamflixreborn.streamflix.utils.BrokenSourceReporter.noteExtractorOutcome(
                    name = serverName, success = true,
                )
            }
            // Reset aussi le compteur persistant — l'écran "Extracteurs" affiche
            // donc les échecs CONSÉCUTIFS (depuis le dernier succès), pas le
            // cumul. Détecte mieux les extracteurs vraiment cassés vs bruit.
            com.streamflixreborn.streamflix.utils.ExtractorFailureTracker.recordSuccess(serverName)
        }

        private fun recordFailure(serverName: String, error: Throwable? = null) {
            val now = System.currentTimeMillis()
            val errorType = error?.let { classifyError(it) }

            // 2026-07-17 — Une SOURCE morte ne dit RIEN sur la santé de
            //   l'EXTRACTEUR : vidéo supprimée, pas encore encodée côté hoster,
            //   lien expiré… L'extracteur a parfaitement fait son travail, c'est
            //   le fichier qui n'existe pas.
            //   Sans ce garde-fou, quelques fichiers HS d'affilée suffisent à
            //   blacklister un extracteur 100 % fonctionnel pendant tout le
            //   HEALTH_BROKEN_DURATION_MS. Constaté en réel : AnonMP4 marqué
            //   "broken (3 failures)" alors que son API répondait proprement
            //   "Video queued for processing" à chaque fois.
            //   → On enregistre quand même la STAT (utile dans l'écran
            //     Extracteurs), mais on ne compte pas l'échec SANTÉ.
            if (errorType != "dead-content") {
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
            } else {
                Log.d("Extractor", "Server '$serverName' : source morte (dead-content) — santé extracteur NON pénalisée")
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
            // 2026-07-13 : « flux mort » — l'extracteur a réussi mais le stream final est KO
            //   (HEAD 404/timeout). Cumul → issue [flux mort] (l'extracteur crache des liens périmés).
            runCatching { com.streamflixreborn.streamflix.utils.BrokenSourceReporter.noteStreamDead(serverName) }
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

        // ── Domain fallback : redirection automatique ────────────────────
        // Quand un domaine d'hoster tombe (DNS/connect/SSL), on ré-essaie
        // avec les aliasUrls de l'extracteur. Si ça marche, on mémorise
        // la redirection pour les prochains appels (évite de re-tenter
        // le domaine mort). Cache en mémoire, reset au restart app.
        private val domainRedirects = ConcurrentHashMap<String, String>()

        /** Extrait le host d'une URL (sans protocole ni www). */
        private fun extractHost(url: String): String? {
            return try {
                val cleaned = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
                cleaned.substringBefore("/").substringBefore(":").ifBlank { null }
            } catch (_: Exception) { null }
        }

        /** Vrai si l'erreur est liée au domaine/réseau (DNS, connect, SSL, timeout).
         *  Ces erreurs justifient un retry sur un domaine alias car le contenu
         *  est probablement le même — c'est juste le TLD qui a changé. */
        private fun isDomainError(e: Throwable): Boolean {
            var cur: Throwable? = e
            var depth = 0
            while (cur != null && depth < 6) {
                when (cur) {
                    is java.net.UnknownHostException -> return true
                    is java.net.ConnectException -> return true
                    is javax.net.ssl.SSLException -> return true
                    is java.net.SocketTimeoutException -> return true
                    is java.io.InterruptedIOException -> return true
                    is kotlinx.coroutines.TimeoutCancellationException -> return true
                }
                val msg = cur.message?.lowercase().orEmpty()
                if (msg.contains("unable to resolve host") || msg.contains("failed to connect")) return true
                cur = cur.cause
                depth++
            }
            return false
        }

        /** Applique les redirections domaine mémorisées sur un lien.
         *  Si le host du lien a un redirect connu (succès précédent sur un alias),
         *  on réécrit directement le lien pour éviter de retenter le domaine mort. */
        private fun applyDomainRedirect(url: String): String {
            val host = extractHost(url) ?: return url
            val redirectTo = domainRedirects[host] ?: return url
            Log.d("Extractor", "Applying cached domain redirect: $host → $redirectTo")
            return url.replace(host, redirectTo)
        }

        /** 2026-07-01 : certains providers (UnJourUnFilm) encodent l'URL d'embed en
         *  base64 (ex "aHR0cHM6..." = "https://..."). On la décode ici pour que le
         *  routage trouve le bon extracteur (sinon "No extractors found"). Si ce n'est
         *  pas du base64 valide décodant en http, on renvoie le lien d'origine. */
        private fun decodeBase64Link(link: String): String {
            if (link.startsWith("http", ignoreCase = true)) return link
            return try {
                val decoded = String(android.util.Base64.decode(link.trim(), android.util.Base64.DEFAULT))
                if (decoded.startsWith("http", ignoreCase = true)) decoded else link
            } catch (_: Exception) { link }
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

            // 2026-05-21 : Appliquer les redirections de domaine mémorisées
            // (un domaine mort qui a déjà été résolu vers un alias vivant).
            // Ceci évite de re-tenter le domaine mort à chaque extraction.
            var finalLink = applyDomainRedirect(decodeBase64Link(link))

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
                    // 2026-05-21 : DOMAIN FALLBACK — si l'erreur est réseau
                    // (DNS/connect/SSL/timeout), on ré-essaie en remplaçant le
                    // domaine du lien par chaque alias connu. Couvre le cas
                    // classique : un hoster change de TLD (streamwish.to → .com,
                    // moiflix.com → .click, moviesapi.club → .to, etc.) et les
                    // providers envoient encore l'ancien domaine dans les embeds.
                    // Le fallback est transparent : l'extracteur reçoit l'URL
                    // réécrite et fonctionne normalement.
                    if (isDomainError(e) && foundExtractor.aliasUrls.isNotEmpty()) {
                        val linkHost = extractHost(finalLink)
                        if (linkHost != null) {
                            // Construire la liste de domaines candidats : mainUrl + aliasUrls
                            // sauf celui qui vient de fail
                            val allDomains = buildList {
                                extractHost(foundExtractor.mainUrl)?.let { add(it) }
                                foundExtractor.aliasUrls.forEach { alias ->
                                    extractHost(alias)?.let { add(it) }
                                }
                            }.distinct().filter { it != linkHost }

                            for (altHost in allDomains) {
                                val altLink = finalLink.replace(linkHost, altHost)
                                Log.w("Extractor", "Domain fallback ${foundExtractor.name}: $linkHost → $altHost")
                                try {
                                    val altVideo = foundExtractor.extract(altLink)
                                    // Succès ! Mémoriser le domaine qui marche
                                    domainRedirects[linkHost] = altHost
                                    val altDuration = System.currentTimeMillis() - extractStartMs
                                    com.streamflixreborn.streamflix.utils.ExtractorLatencyTracker
                                        .recordExtraction(name, altDuration)
                                    Log.i("StreamFlixES", "[VIDEO] -> Extracted via fallback ($altHost): ${altVideo.source}")
                                    recordSuccess(name)
                                    val filtered = enforceFrenchSubtitlesOnly(altVideo)
                                    if (foundExtractor.cacheTtlMs > 0L) {
                                        extractionCache[link] = CachedExtraction(
                                            video = filtered,
                                            expiresAtMillis = System.currentTimeMillis() + foundExtractor.cacheTtlMs,
                                        )
                                    }
                                    return filtered
                                } catch (altE: Exception) {
                                    Log.w("Extractor", "Domain fallback $altHost also failed: ${altE.message}")
                                }
                            }
                        }
                    }
                    // C: track the failure so the provider can grey/sort this server out.
                    // On passe l'exception pour permettre au tracker de classifier
                    // le type d'erreur (timeout / 403 / parsing / …) et de logger
                    // le provider source (UserPreferences.currentProvider) dans le
                    // rapport bug — utile pour debug à distance.
                    recordFailure(name, error = e)
                    // 2026-07-13 : rapport auto « source cassée » (URL/domaine changé) → GitHub,
                    //   1 seule fois par source+domaine, seulement pour dns/connect/ssl/404/parsing.
                    runCatching {
                        com.streamflixreborn.streamflix.utils.BrokenSourceReporter.maybeReport(
                            sourceName = name,
                            url = finalLink,
                            error = e,
                            providerName = runCatching { UserPreferences.currentProvider?.name }.getOrNull(),
                        )
                        // Détection « extracteur mort » : cumul d'échecs consécutifs.
                        com.streamflixreborn.streamflix.utils.BrokenSourceReporter.noteExtractorOutcome(
                            name = name, success = false, error = e, url = finalLink,
                        )
                    }
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

            // 2026-06-03 (user "il nous manque un extracteur" pour
            //   cdn.fastflux.xyz/movies/Aventures-Croisees-2026.mp4) : avant
            //   de jeter, si l'URL ressemble à un fichier média DIRECT
            //   (.mp4 / .m3u8 / .mpd, éventuellement avec query string),
            //   on la renvoie telle quelle. ExoPlayer sait les lire seul,
            //   pas besoin d'un extracteur HTML/JS dédié. Couvre les CDN
            //   "open" type fastflux.xyz, fastly, etc.
            val directMediaRegex = Regex("""\.(mp4|m3u8|mpd)(\?|$|#)""", RegexOption.IGNORE_CASE)
            if (directMediaRegex.containsMatchIn(finalLink)) {
                Log.i("Extractor", "No extractor matched, but URL looks like a direct media file → pass-through: $finalLink")
                // 2026-06-29 (REPAIR — user "★ Frembed Free VF = Source error") : les
                //   m3u8 VIP Frembed (host senpai-stream) sont protégés et renvoient
                //   403 / BAD_HTTP_STATUS sans Referer frembed + cf_clearance. On
                //   attache Referer/Origin/UA + cookie cf_clearance pour ces sources.
                val passHeaders: Map<String, String>? =
                    if (finalLink.contains("senpai-stream", ignoreCase = true)) {
                        val cookie = try {
                            android.webkit.CookieManager.getInstance().getCookie("https://frembed.hair/")
                        } catch (e: Exception) { null }
                        buildMap {
                            put("Referer", "https://frembed.hair/")
                            put("Origin", "https://frembed.hair")
                            put("User-Agent", DEFAULT_USER_AGENT)
                            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
                        }
                    } else null
                val direct = com.streamflixreborn.streamflix.models.Video(
                    source = finalLink,
                    headers = passHeaders,
                )
                return enforceFrenchSubtitlesOnly(direct)
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
        // 2026-07-04 (user "à froid le film met 8s, c'est pareil tous providers") : identify
        //   ServiceName était appelé ~80× par ouverture (map + dedup + comparateur de tri), et
        //   CHAQUE appel COMPILAIT des regex par extracteur (ligne baseName) + itérait tous les
        //   extracteurs 3×. Sous la contention CPU du démarrage à froid → 7,6s. On MÉMOÏSE
        //   (cache url→nom) : appelé une seule fois par URL unique. Regex en constantes (compilées
        //   une fois). Vaut pour TOUS les providers (tous trient via identifyServiceName).
        private val URL_PREFIX_REGEX = Regex("^(https?://)?(www\\.)?")
        private val BASE_NAME_REGEX = Regex("^(https?://)?(www\\.)?(.*?)(\\.[a-z]+)")
        private val serviceNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        // 2026-07-04 : table host→nom construite UNE fois. identifyServiceName tente
        //   d'abord un lookup O(1) par host exact (cas courant : uqload.is, vidara.to…) avant
        //   de retomber sur l'itération complète des extracteurs (domaines rotatifs, prefix).
        // 2026-07-05 : EAGER init (plus de `by lazy(SYNCHRONIZED)`). Le lazy lock bloquait 5s+
        //   sur Chromecast quand un thread IO commençait l'init pendant que le CPU était affamé
        //   par les backups HTTP → tous les callers (y compris le tri serveurs) bloqués sur le
        //   verrou. L'init est légère (~1ms, juste des strings) → pas de raison de la différer.
        private val hostToName: Map<String, String> = run {
            val m = HashMap<String, String>()
            for (e in extractors) {
                hostKey(e.mainUrl)?.let { m.putIfAbsent(it, e.name) }
                for (a in e.aliasUrls) hostKey(a)?.let { m.putIfAbsent(it, e.name) }
            }
            m
        }
        private fun hostKey(url: String): String? =
            url.lowercase().replace(URL_PREFIX_REGEX, "").substringBefore("/").takeIf { it.isNotBlank() }

        fun identifyServiceName(url: String): String? {
            if (url.isBlank()) return null
            serviceNameCache[url]?.let { return it.ifEmpty { null } }
            // Fast path O(1) : host exact
            val host = url.lowercase().replace(URL_PREFIX_REGEX, "").substringBefore("/")
            val result = hostToName[host] ?: identifyServiceNameUncached(url)
            serviceNameCache[url] = result ?: ""
            return result
        }

        private fun identifyServiceNameUncached(url: String): String? {
            val urlRegex = URL_PREFIX_REGEX
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
            // 2026-06-13 (user "Frembed mapping richardquestionbuilding→voe") :
            //   2e passe via `rotatingDomain` regex pour capter automatiquement
            //   les domaines rotatifs (Voe utilise des noms aléatoires comme
            //   richardquestionbuilding.com, jessicayeahcatch.com…). Sans ce
            //   check, le picker affichait "Richardquestionbuilding" au lieu
            //   de "VOE" (la lecture marchait déjà via rotatingDomain dans le
            //   matching d'extractor — c'est juste l'AFFICHAGE qui était
            //   cassé). Maintenant ce check résout TOUS les futurs domaines
            //   rotatifs sans avoir à les hardcoder dans une map (= mieux que
            //   l'approche upstream qui ajoute juste 1 mapping spécifique).
            for (extractor in extractors) {
                if (extractor.rotatingDomain.any { it.containsMatchIn(compareUrl) }) {
                    return extractor.name
                }
            }
            // Fallback: match base domain name without TLD
            for (extractor in extractors) {
                val baseName = extractor.mainUrl.replace(BASE_NAME_REGEX, "$3")
                if (compareUrl.startsWith(baseName)) {
                    return extractor.name
                }
            }
            return null
        }
    }
}
