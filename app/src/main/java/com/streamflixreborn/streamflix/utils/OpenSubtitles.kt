package com.streamflixreborn.streamflix.utils

import android.net.Uri
import androidx.core.net.toUri
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * 2026-05-03 — MIGRATION VERS API MODERNE.
 *
 * Avant : rest.opensubtitles.org (API legacy XML/JSON sans auth) — DEAD :
 * retournait 302 vers une URL cassée `https://_/...` → 0 résultats systématique.
 *
 * Après : api.opensubtitles.com/api/v1 — API moderne avec :
 * - Api-Key requis (gratuite, créée sur opensubtitles.com/fr/consumers)
 * - User-Agent dédié à l'app
 * - Quota 100 dl/jour si "dev_mode" activé sur le consumer (sinon 5/jour)
 * - Token de login optionnel pour augmenter quota (passe via JWT)
 *
 * Le DTO Subtitle garde les noms historiques (subFileName, subDownloadLink…)
 * pour ne pas casser les appelants downstream.
 */
object OpenSubtitles {

    private const val BASE_URL = "https://api.opensubtitles.com/api/v1/"
    private const val USER_AGENT = "StreamFR v1.7"

    /**
     * Pool de keys API. local.properties peut contenir plusieurs keys séparées
     * par virgule pour partager le quota entre les utilisateurs de l'app
     * (100 dl/jour/key avec dev_mode → N×100 si N keys).
     * Rotation : à chaque requête on prend une key différente. Si une key
     * tombe en quota dépassé (HTTP 429 ou 406), on l'écarte temporairement.
     */
    private val keysPool: List<String> = BuildConfig.OPENSUBTITLES_API_KEY
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    /** Map key -> timestamp ms du prochain retry (si quota dépassé). */
    private val keyCooldowns = mutableMapOf<String, Long>()
    private var nextKeyIndex = 0
    private val cooldownMs = 60L * 60L * 1000L // 1h cooldown si quota dépassé

    @Synchronized
    private fun nextKey(): String? {
        if (keysPool.isEmpty()) return null
        val now = System.currentTimeMillis()
        // Cherche la prochaine key dispo (round-robin)
        for (i in keysPool.indices) {
            val idx = (nextKeyIndex + i) % keysPool.size
            val k = keysPool[idx]
            val cooldown = keyCooldowns[k] ?: 0L
            if (cooldown < now) {
                nextKeyIndex = (idx + 1) % keysPool.size
                return k
            }
        }
        return null // toutes en cooldown
    }

    @Synchronized
    private fun markKeyExhausted(key: String) {
        keyCooldowns[key] = System.currentTimeMillis() + cooldownMs
    }

    private val service = Service.build()

    suspend fun download(subtitle: Subtitle): Uri = withContext(Dispatchers.IO) {
        android.util.Log.d("OpenSubtitles", "download() — fileId=${subtitle.fileId}, subDownloadLink='${subtitle.subDownloadLink}', subFileName='${subtitle.subFileName}'")
        // L'API v1 nécessite POST /download avec file_id pour obtenir l'URL réelle
        val realUrl = subtitle.subDownloadLink.takeIf { it.isNotBlank() }
            ?: subtitle.fileId?.let { resolveDownloadUrl(it) }
            ?: throw Exception("No download link available (fileId=${subtitle.fileId})")

        android.util.Log.d("OpenSubtitles", "download() — realUrl=$realUrl")
        val ext = File(realUrl.substringBefore("?")).extension.ifBlank { "srt" }
        val baseName = File(subtitle.subFileName.ifBlank { "subtitle" }).nameWithoutExtension
        val file = File.createTempFile("$baseName-", ".$ext")

        URL(realUrl).openStream().use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }

        file.toUri()
    }

    private suspend fun resolveDownloadUrl(fileId: Int): String? {
        android.util.Log.d("OpenSubtitles", "resolveDownloadUrl fileId=$fileId, poolSize=${keysPool.size}")
        val body = JSONObject().put("file_id", fileId).toString()
            .toRequestBody("application/json".toMediaType())
        // Essaie chaque key du pool jusqu'à trouver une qui marche
        repeat(keysPool.size.coerceAtLeast(1)) {
            val key = nextKey() ?: run {
                android.util.Log.w("OpenSubtitles", "resolveDownloadUrl: nextKey() returned null (all in cooldown?)")
                return null
            }
            try {
                val resp = service.requestDownload(key, USER_AGENT, body)
                android.util.Log.d("OpenSubtitles", "requestDownload key=${key.take(8)}... -> link=${resp.link}, remaining=${resp.remaining}, msg=${resp.message}")
                if (resp.link != null) return resp.link
                // remaining=0 ou erreur quota
                if (resp.remaining != null && resp.remaining <= 0) markKeyExhausted(key)
            } catch (e: Exception) {
                android.util.Log.e("OpenSubtitles", "requestDownload exception key=${key.take(8)}...: ${e.message}")
                if (e.message?.contains("406") == true || e.message?.contains("429") == true) {
                    markKeyExhausted(key)
                }
            }
        }
        return null
    }

    suspend fun search(
        imdbId: String? = null,
        query: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subLanguageId: String? = null,
    ): List<Subtitle> {
        if (keysPool.isEmpty()) return emptyList()

        // ISO 639-2/B (fre) -> ISO 639-1 (fr) pour la nouvelle API
        val lang = when (subLanguageId?.lowercase()) {
            "fre", "fra", "fr" -> "fr"
            "eng", "en" -> "en"
            "spa", "es" -> "es"
            "ger", "deu", "de" -> "de"
            "ita", "it" -> "it"
            null, "" -> null
            else -> subLanguageId.take(2).lowercase()
        }

        val cleanImdb = imdbId?.removePrefix("tt")?.toIntOrNull()

        // Essaie chaque key du pool jusqu'à succès (rotation auto)
        repeat(keysPool.size.coerceAtLeast(1)) {
            val key = nextKey() ?: return emptyList()
            try {
                val resp = service.searchSubtitles(
                    apiKey = key,
                    userAgent = USER_AGENT,
                    imdbId = cleanImdb,
                    query = query?.takeIf { it.isNotBlank() && cleanImdb == null },
                    seasonNumber = season,
                    episodeNumber = episode,
                    languages = lang,
                )
                return resp.data?.mapNotNull { it.toLegacySubtitle() } ?: emptyList()
            } catch (e: Exception) {
                if (e.message?.contains("406") == true || e.message?.contains("429") == true) {
                    markKeyExhausted(key)
                } else {
                    // Erreur autre que quota → return empty pour éviter boucle infinie
                    return emptyList()
                }
            }
        }
        return emptyList()
    }

    private interface Service {
        companion object {
            fun build(): Service {
                val client = OkHttpClient.Builder().build()
                val retrofit = Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                return retrofit.create(Service::class.java)
            }
        }

        @GET("subtitles")
        @retrofit2.http.Headers("Accept: */*")
        suspend fun searchSubtitles(
            @Header("Api-Key") apiKey: String,
            @Header("User-Agent") userAgent: String,
            @Query("imdb_id") imdbId: Int? = null,
            @Query("query") query: String? = null,
            @Query("season_number") seasonNumber: Int? = null,
            @Query("episode_number") episodeNumber: Int? = null,
            @Query("languages") languages: String? = null,
        ): SearchResponse

        @POST("download")
        @retrofit2.http.Headers("Accept: */*", "Content-Type: application/json")
        suspend fun requestDownload(
            @Header("Api-Key") apiKey: String,
            @Header("User-Agent") userAgent: String,
            @Body body: okhttp3.RequestBody,
        ): DownloadResponse
    }

    private data class SearchResponse(
        val data: List<DataItem>?,
        val total_pages: Int? = null,
        val total_count: Int? = null,
        val per_page: Int? = null,
        val page: Int? = null,
    )

    private data class DataItem(
        val id: String? = null,
        val type: String? = null,
        val attributes: Attributes? = null,
    ) {
        fun toLegacySubtitle(): Subtitle? {
            val attrs = attributes ?: return null
            val firstFile = attrs.files?.firstOrNull()
            return Subtitle(
                idSubtitleFile = firstFile?.file_id?.toString(),
                idSubtitle = id,
                fileId = firstFile?.file_id,
                subFileName = firstFile?.file_name ?: attrs.release ?: "",
                subFormat = "srt",
                subLanguageID = attrs.language,
                languageName = attrs.language,
                iso639 = attrs.language,
                subDownloadsCnt = attrs.download_count?.toString(),
                subRating = attrs.ratings?.toString(),
                movieReleaseName = attrs.release,
                subFromTrusted = attrs.from_trusted?.toString(),
                subDownloadLink = "", // résolu lors du download() via POST /download
                subFeatured = attrs.featured?.toString(),
                subHearingImpaired = attrs.hearing_impaired?.toString(),
                subForeignPartsOnly = attrs.foreign_parts_only?.toString(),
                subAddDate = attrs.upload_date,
            )
        }
    }

    private data class Attributes(
        val subtitle_id: String? = null,
        val language: String? = null,
        val download_count: Int? = null,
        val new_download_count: Int? = null,
        val hearing_impaired: Boolean? = null,
        val hd: Boolean? = null,
        val fps: Double? = null,
        val votes: Int? = null,
        val ratings: Double? = null,
        val from_trusted: Boolean? = null,
        val foreign_parts_only: Boolean? = null,
        val upload_date: String? = null,
        val ai_translated: Boolean? = null,
        val machine_translated: Boolean? = null,
        val release: String? = null,
        val comments: String? = null,
        val legacy_subtitle_id: Int? = null,
        val featured: Boolean? = null,
        val files: List<FileItem>? = null,
    )

    private data class FileItem(
        val file_id: Int? = null,
        val cd_number: Int? = null,
        val file_name: String? = null,
    )

    private data class DownloadResponse(
        val link: String? = null,
        val file_name: String? = null,
        val requests: Int? = null,
        val remaining: Int? = null,
        val message: String? = null,
        val reset_time: String? = null,
    )

    /**
     * DTO public — gardé compatible avec l'ancienne signature pour ne pas
     * casser les appelants (PlayerViewModel, etc.) qui lisent .subFileName,
     * .subDownloadLink, .languageName, .subDownloadsCnt.
     */
    data class Subtitle(
        @SerializedName("MatchedBy") val matchedBy: String? = null,
        @SerializedName("IDSubMovieFile") val idSubMovieFile: String? = null,
        @SerializedName("MovieHash") val movieHash: String? = null,
        @SerializedName("MovieByteSize") val movieByteSize: String? = null,
        @SerializedName("MovieTimeMS") val movieTimeMS: String? = null,
        @SerializedName("IDSubtitleFile") val idSubtitleFile: String? = null,
        // Nouveau : id du fichier (utilisé pour POST /download dans la v1 API)
        val fileId: Int? = null,
        @SerializedName("SubFileName") val subFileName: String = "",
        @SerializedName("SubActualCD") val subActualCD: String? = null,
        @SerializedName("SubSize") val subSize: String? = null,
        @SerializedName("SubHash") val subHash: String? = null,
        @SerializedName("SubLastTS") val subLastTS: String? = null,
        @SerializedName("SubTSGroup") val subTSGroup: String? = null,
        @SerializedName("InfoReleaseGroup") val infoReleaseGroup: String? = null,
        @SerializedName("InfoFormat") val infoFormat: String? = null,
        @SerializedName("InfoOther") val infoOther: String? = null,
        @SerializedName("IDSubtitle") val idSubtitle: String? = null,
        @SerializedName("UserID") val userID: String? = null,
        @SerializedName("SubLanguageID") val subLanguageID: String? = null,
        @SerializedName("SubFormat") val subFormat: String? = null,
        @SerializedName("SubSumCD") val subSumCD: String? = null,
        @SerializedName("SubAuthorComment") val subAuthorComment: String? = null,
        @SerializedName("SubAddDate") val subAddDate: String? = null,
        @SerializedName("SubBad") val subBad: String? = null,
        @SerializedName("SubRating") val subRating: String? = null,
        @SerializedName("SubSumVotes") val subSumVotes: String? = null,
        @SerializedName("SubDownloadsCnt") val subDownloadsCnt: String? = null,
        @SerializedName("MovieReleaseName") val movieReleaseName: String? = null,
        @SerializedName("MovieFPS") val movieFPS: String? = null,
        @SerializedName("IDMovie") val idMovie: String? = null,
        @SerializedName("IDMovieImdb") val idMovieImdb: String? = null,
        @SerializedName("MovieName") val movieName: String? = null,
        @SerializedName("MovieNameEng") val movieNameEng: String? = null,
        @SerializedName("MovieYear") val movieYear: String? = null,
        @SerializedName("MovieImdbRating") val movieImdbRating: String? = null,
        @SerializedName("SubFeatured") val subFeatured: String? = null,
        @SerializedName("UserNickName") val userNickName: String? = null,
        @SerializedName("SubTranslator") val subTranslator: String? = null,
        @SerializedName("ISO639") val iso639: String? = null,
        @SerializedName("LanguageName") val languageName: String? = null,
        @SerializedName("SubComments") val subComments: String? = null,
        @SerializedName("SubHearingImpaired") val subHearingImpaired: String? = null,
        @SerializedName("UserRank") val userRank: String? = null,
        @SerializedName("SeriesSeason") val seriesSeason: String? = null,
        @SerializedName("SeriesEpisode") val seriesEpisode: String? = null,
        @SerializedName("MovieKind") val movieKind: String? = null,
        @SerializedName("SubHD") val subHD: String? = null,
        @SerializedName("SeriesIMDBParent") val seriesIMDBParent: String? = null,
        @SerializedName("SubEncoding") val subEncoding: String? = null,
        @SerializedName("SubAutoTranslation") val subAutoTranslation: String? = null,
        @SerializedName("SubForeignPartsOnly") val subForeignPartsOnly: String? = null,
        @SerializedName("SubFromTrusted") val subFromTrusted: String? = null,
        @SerializedName("QueryCached") val queryCached: Int? = null,
        @SerializedName("SubDownloadLink") val subDownloadLink: String = "",
        @SerializedName("ZipDownloadLink") val zipDownloadLink: String? = null,
        @SerializedName("SubtitlesLink") val subtitlesLink: String? = null,
        @SerializedName("QueryNumber") val queryNumber: String? = null,
        @SerializedName("Score") val score: Double? = null,
    )
}

