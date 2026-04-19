package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

class VixSrcExtractor : Extractor() {

    override val name = "VixSrc"
    override val mainUrl = "https://vixsrc.to"

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Episode -> "$mainUrl/api/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
                is Video.Type.Movie -> "$mainUrl/api/movie/${videoType.id}"
            },
        )
    }

    override suspend fun extract(link: String): Video {
        val service = VixSrcExtractorService.build(mainUrl)
        val providerLang = UserPreferences.currentProvider?.language ?: "en"

        val apiPath = link.substringAfter(mainUrl) + "?lang=$providerLang"

        // Retrofit exceptions propagate directly — no useless try/catch.
        val apiResponse = service.getSourceApi(apiPath)

        var currentEmbedPath = apiResponse.src.trimStart('/')

        val source = try {
            service.getSource(currentEmbedPath)
        } catch (e: Exception) {
            val isGone = (e as? retrofit2.HttpException)?.code() == 410 ||
                e.message?.contains("410") == true
            if (isGone) {
                Log.w(TAG, "410 Gone detected, retrying API call...")
                val retryApiResponse = service.getSourceApi(apiPath)
                currentEmbedPath = retryApiResponse.src.trimStart('/')
                service.getSource(currentEmbedPath)
            } else {
                throw e
            }
        }

        val scriptText = source.body().selectFirst("script")?.data().orEmpty()
        if (scriptText.isBlank()) {
            throw IllegalStateException("VixSrc: empty script body — page layout may have changed")
        }

        val videoId = extractField(scriptText, "window.video = {", "id: '", "',")
            ?: throw IllegalStateException("VixSrc: videoId not found in script (layout change?)")
        val token = extractField(scriptText, "window.masterPlaylist", "'token': '", "',")
            ?: throw IllegalStateException("VixSrc: token not found in script (layout change?)")
        val expires = extractField(scriptText, "window.masterPlaylist", "'expires': '", "',")
            ?: throw IllegalStateException("VixSrc: expires not found in script (layout change?)")

        val hasBParam = scriptText
            .substringAfter("url:", "")
            .substringBefore(",", "")
            .contains("b=1")

        val canPlayFHD = scriptText.contains("window.canPlayFHD = true")

        val masterParams = mutableMapOf(
            "token" to token,
            "expires" to expires,
            "lang" to providerLang,
        )
        if (hasBParam) masterParams["b"] = "1"
        if (canPlayFHD) masterParams["h"] = "1"

        val baseUrl = "$mainUrl/playlist/$videoId"
        val httpUrlBuilder = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("VixSrc: invalid base URL $baseUrl")
        masterParams.forEach { (key, value) -> httpUrlBuilder.addQueryParameter(key, value) }
        val finalUrl = httpUrlBuilder.build().toString()

        val finalHeaders = mapOf(
            "Referer" to "$mainUrl/$currentEmbedPath",
            "User-Agent" to USER_AGENT,
        )

        val videoSource = tryPatchManifest(finalUrl, finalHeaders, providerLang) ?: finalUrl

        return Video(
            source = videoSource,
            subtitles = emptyList(),
            type = MimeTypes.APPLICATION_M3U8,
            headers = finalHeaders,
        )
    }

    /**
     * Extract a field from the inline script with [after] -> [prefix] -> [suffix] delimiters.
     * Returns null if any delimiter is missing or the extracted value is blank, so callers
     * can fail fast instead of building a broken URL from empty strings.
     */
    private fun extractField(script: String, after: String, prefix: String, suffix: String): String? {
        if (!script.contains(after)) return null
        val afterAnchor = script.substringAfter(after, "")
        if (!afterAnchor.contains(prefix)) return null
        val afterPrefix = afterAnchor.substringAfter(prefix, "")
        if (!afterPrefix.contains(suffix)) return null
        return afterPrefix.substringBefore(suffix, "").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Download the master playlist and patch URIs + audio/subtitle selection.
     * Returns the base64-encoded data URL on success, or null on any failure (caller
     * falls back to the raw URL). Failures are logged rather than silently swallowed.
     */
    private fun tryPatchManifest(
        finalUrl: String,
        headers: Map<String, String>,
        providerLang: String,
    ): String? {
        return try {
            val client = OkHttpClient.Builder()
                .dns(DnsResolver.doh)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val headersBuilder = okhttp3.Headers.Builder()
            headers.forEach { (k, v) -> headersBuilder.add(k, v) }
            val request = Request.Builder().url(finalUrl).headers(headersBuilder.build()).build()

            client.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    Log.w(TAG, "Playlist fetch failed: HTTP ${response.code}")
                    return null
                }

                val playlistContent = body.string()
                val baseUri = response.request.url

                val uriRegex = """URI=["']([^"']+)["']""".toRegex()
                val finalLines = playlistContent.lines().map { line ->
                    var patchedLine = line

                    if (line.startsWith("#")) {
                        patchedLine = uriRegex.replace(line) { match ->
                            val relative = match.groupValues[1]
                            if (relative.startsWith("http") || relative.startsWith("data:")) match.value
                            else "URI=\"${baseUri.resolve(relative) ?: relative}\""
                        }
                    } else if (line.isNotBlank()) {
                        patchedLine = baseUri.resolve(line)?.toString() ?: line
                    }

                    when {
                        patchedLine.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") ->
                            patchAudioTrack(patchedLine, providerLang)
                        patchedLine.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES") ->
                            patchSubtitleTrack(patchedLine, providerLang)
                        else -> patchedLine
                    }
                }

                val manifestBytes = finalLines.joinToString("\n").toByteArray()
                val base64Manifest = Base64.encodeToString(manifestBytes, Base64.NO_WRAP)
                "data:application/vnd.apple.mpegurl;base64,$base64Manifest"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Manifest patching failed, falling back to raw URL: ${e.message}")
            null
        }
    }

    private fun patchAudioTrack(line: String, providerLang: String): String {
        val reset = line
            .replace(Regex("DEFAULT=YES", RegexOption.IGNORE_CASE), "DEFAULT=NO")
            .replace(Regex("AUTOSELECT=YES", RegexOption.IGNORE_CASE), "AUTOSELECT=NO")

        val isTarget = reset.contains("LANGUAGE=\"$providerLang\"", ignoreCase = true) ||
            reset.contains("NAME=\"$providerLang\"", ignoreCase = true) ||
            matchesLanguageAliases(providerLang, reset)

        return if (isTarget) {
            reset.replace("DEFAULT=NO", "DEFAULT=YES")
                .replace("AUTOSELECT=NO", "AUTOSELECT=YES")
        } else {
            reset
        }
    }

    private fun patchSubtitleTrack(line: String, providerLang: String): String {
        val trackName = line.substringAfter("NAME=\"", "Unknown").substringBefore("\"")
        val trackLang = line.substringAfter("LANGUAGE=\"", "").substringBefore("\"")

        val reset = line
            .replace(Regex("DEFAULT=YES", RegexOption.IGNORE_CASE), "DEFAULT=NO")
            .replace(Regex("AUTOSELECT=YES", RegexOption.IGNORE_CASE), "AUTOSELECT=NO")

        val isForced = trackName.contains("forced", ignoreCase = true) ||
            trackLang.contains("forced", ignoreCase = true) ||
            reset.contains("FORCED=YES", ignoreCase = true)

        val isRightLanguage = trackLang.contains(providerLang, ignoreCase = true) ||
            trackName.contains(providerLang, ignoreCase = true) ||
            matchesLanguageAliases(providerLang, trackName) ||
            matchesLanguageAliases(providerLang, trackLang)

        return if (isForced && isRightLanguage) {
            reset.replace("DEFAULT=NO", "DEFAULT=YES")
                .replace("AUTOSELECT=NO", "AUTOSELECT=YES")
        } else {
            reset
        }
    }

    /**
     * Extended language aliases used by VixSrc master playlists (full names + 3-letter codes).
     * Centralised here so audio and subtitles share the same matching rules.
     */
    private fun matchesLanguageAliases(providerLang: String, haystack: String): Boolean {
        val aliases = LANGUAGE_ALIASES[providerLang] ?: return false
        return aliases.any { haystack.contains(it, ignoreCase = true) }
    }

    companion object {
        private const val TAG = "VixSrcExtractor"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val LANGUAGE_ALIASES = mapOf(
            "it" to listOf("Italian", "ita"),
            "es" to listOf("Spanish", "Español", "Castellano", "spa"),
            "en" to listOf("English", "eng"),
            "fr" to listOf("French", "Français", "fra", "fre"),
            "de" to listOf("German", "Deutsch", "ger", "deu"),
            "pt" to listOf("Portuguese", "Português", "por"),
            "ja" to listOf("Japanese", "日本語", "jpn"),
            "ko" to listOf("Korean", "한국어", "kor"),
        )
    }

    private interface VixSrcExtractorService {
        companion object {
            fun build(baseUrl: String): VixSrcExtractorService {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Referer", baseUrl)
                            .build()
                        chain.proceed(request)
                    }
                    .build()
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(VixSrcExtractorService::class.java)
            }
        }

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept: application/json, text/plain, */*",
            "X-Requested-With: XMLHttpRequest",
        )
        suspend fun getSourceApi(@Url url: String): VixSrcApiResponse

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "X-Requested-With: XMLHttpRequest",
        )
        suspend fun getSource(@Url url: String): Document

        data class VixSrcApiResponse(val src: String)
    }
}
