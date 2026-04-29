package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

abstract class Extractor {

    abstract val name: String
    abstract val mainUrl: String
    open val aliasUrls: List<String> = emptyList()
    open val rotatingDomain: List<Regex> = emptyList()

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

        /** Base OkHttpClient shared by all extractors. Has DoH, redirects, 30s timeouts, User-Agent. */
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dns(DnsResolver.doh)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
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

        /** Build a Retrofit with GsonConverterFactory. */
        fun gsonRetrofit(baseUrl: String, client: OkHttpClient = sharedClient): Retrofit =
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
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
            Up4StreamExtractor()
        )

        suspend fun extract(link: String, server: Video.Server? = null): Video {
            Log.d("Extractor", "extract() called with link=$link server=${server?.name}")
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
                val video = foundExtractor.extract(finalLink)
                Log.i("StreamFlixES", "[VIDEO] -> Extracted: ${video.source}")
                return video
            }

            Log.e("Extractor", "No extractors found for URL: $finalLink (original: $link)")
            throw Exception("No extractors found for URL: $finalLink")
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
