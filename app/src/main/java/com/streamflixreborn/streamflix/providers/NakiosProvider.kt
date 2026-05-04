package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Nakios — streaming films & séries en HD/4K, catalogue TMDB-shaped.
 *
 * API publique sur https://api.nakios.fit/api — retourne directement du JSON
 * compatible TMDB (mêmes champs : id, title, overview, poster_path, etc.).
 * Le seul gating : l'API exige un header `Origin: https://nakios.fit` pour
 * autoriser les requêtes (sinon 403). Les pubs côté site web sont juste pour
 * leur monétisation, pas une auth — l'API mobile/native n'en a pas besoin.
 *
 * Sources VOD : `/api/sources/movie/{tmdbId}` et `/api/sources/tv/{tmdbId}/{season}/{episode}`
 * renvoient une liste de serveurs avec URLs MP4 directes (`isEmbed=false`) ou
 * URLs d'embed (`isEmbed=true`) à résoudre via les extracteurs existants.
 */
object NakiosProvider : Provider, ProviderConfigUrl {

    override val name = "Nakios"
    override val logo = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_nakios"
    override val language = "fr"

    private const val TAG = "NakiosProvider"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Page de statut officielle Nakios — stable, sert à découvrir le domaine
     *  courant si nakios.fit change (le JS bundle contient la valeur "live"). */
    private const val STATUS_PAGE = "https://nakios.online"

    /** Default si la découverte échoue. Mis à jour par onChangeUrl(). */
    override val defaultBaseUrl: String = "https://nakios.fit"

    /** Front URL courant (pour Origin/Referer). */
    @Volatile private var currentFrontUrl: String = "https://nakios.fit"

    /** API base courant (`https://api.nakios.fit` par défaut). */
    @Volatile private var currentApiBase: String = "https://api.nakios.fit"

    override val baseUrl: String
        get() = currentApiBase

    override val changeUrlMutex: Mutex = Mutex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    // TMDB image base
    private fun tmdbImg(path: String?, size: String = "w500"): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    // Movie/TvShow/Episode constructors prennent `released: String?` directement
    // (la classe convertit en Calendar via toCalendar() en interne). Pas besoin
    // de pré-parser, juste passer la string brute.

    // ───────── Domain auto-discovery ─────────

    /**
     * Si l'API courante échoue (DNS/timeout/403), on va chercher le bon domaine
     * via la page de statut nakios.online dont le JS bundle contient toujours
     * l'URL officielle live. Lorsque Nakios change de domaine (ex: .fit → .city),
     * la status page est mise à jour avant le reste.
     */
    override suspend fun onChangeUrl(forceRefresh: Boolean): String = changeUrlMutex.withLock {
        if (!forceRefresh && currentApiBase != "https://api.nakios.fit") {
            // déjà découvert et différent du défaut → on garde
            return@withLock currentApiBase
        }
        try {
            val statusHtml = httpGetRaw(STATUS_PAGE) ?: return@withLock currentApiBase
            // Trouver le bundle JS référencé
            val jsPath = Regex("""src="(/assets/[^"]+\.js)"""").find(statusHtml)?.groupValues?.get(1)
                ?: return@withLock currentApiBase
            val js = httpGetRaw("$STATUS_PAGE$jsPath") ?: return@withLock currentApiBase
            // Le JS contient https://nakios.<TLD> (le domaine officiel courant)
            val officialFront = Regex("""https?://nakios\.[a-z]{2,8}""")
                .findAll(js)
                .map { it.value }
                .filter { !it.contains("nakios.online", ignoreCase = true) }
                .firstOrNull()
                ?: return@withLock currentApiBase
            currentFrontUrl = officialFront
            // L'API suit le pattern api.nakios.<TLD>
            val tld = officialFront.substringAfterLast('.')
            currentApiBase = "https://api.nakios.$tld"
            Log.d(TAG, "Domain re-discovered: front=$currentFrontUrl api=$currentApiBase")
            currentApiBase
        } catch (e: Exception) {
            Log.w(TAG, "onChangeUrl failed: ${e.message}, keeping $currentApiBase")
            currentApiBase
        }
    }

    private suspend fun httpGetRaw(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ───────── HTTP helper ─────────

    private suspend fun apiGet(path: String, retryWithRediscovery: Boolean = true): String? = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else "$currentApiBase$path"
        try {
            val req = Request.Builder()
                .url(url)
                .header("Origin", currentFrontUrl)
                .header("Referer", "$currentFrontUrl/")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET $url → HTTP ${resp.code}")
                    // 403/404 sur l'API → potentiellement un changement de domaine
                    if (retryWithRediscovery && (resp.code == 404 || resp.code in 500..599)) {
                        val newBase = onChangeUrl(forceRefresh = true)
                        if (newBase != currentApiBase || newBase != "https://api.nakios.fit") {
                            // baseUrl a changé → retry une fois sans rediscover récursif
                            return@withContext apiGet(path, retryWithRediscovery = false)
                        }
                    }
                    return@withContext null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET $url failed: ${e.message}")
            // Erreur réseau → tenter rediscover
            if (retryWithRediscovery) {
                val newBase = onChangeUrl(forceRefresh = true)
                if (newBase != currentApiBase) {
                    return@withContext apiGet(path, retryWithRediscovery = false)
                }
            }
            null
        }
    }

    // ───────── DTOs ─────────

    private data class ListResponse(
        val page: Int? = null,
        val results: List<TmdbItem> = emptyList(),
    )

    private data class TmdbItem(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        val overview: String? = null,
        @SerializedName("poster_path") val posterPath: String? = null,
        @SerializedName("backdrop_path") val backdropPath: String? = null,
        @SerializedName("release_date") val releaseDate: String? = null,
        @SerializedName("first_air_date") val firstAirDate: String? = null,
        @SerializedName("vote_average") val voteAverage: Double? = null,
        @SerializedName("media_type") val mediaType: String? = null,
        @SerializedName("genre_ids") val genreIds: List<Int>? = null,
        @SerializedName("origin_country") val originCountry: List<String>? = null,
    )

    private data class MovieDetail(
        val id: Int? = null,
        val title: String? = null,
        val overview: String? = null,
        @SerializedName("poster_path") val posterPath: String? = null,
        @SerializedName("backdrop_path") val backdropPath: String? = null,
        @SerializedName("release_date") val releaseDate: String? = null,
        @SerializedName("vote_average") val voteAverage: Double? = null,
        val runtime: Int? = null,
        val genres: List<TmdbGenre>? = null,
    )

    private data class TmdbGenre(val id: Int? = null, val name: String? = null)

    private data class TvDetail(
        val id: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        @SerializedName("poster_path") val posterPath: String? = null,
        @SerializedName("backdrop_path") val backdropPath: String? = null,
        @SerializedName("first_air_date") val firstAirDate: String? = null,
        @SerializedName("vote_average") val voteAverage: Double? = null,
        val genres: List<TmdbGenre>? = null,
        val seasons: List<TvSeason>? = null,
    )

    private data class TvSeason(
        val id: Int? = null,
        @SerializedName("season_number") val seasonNumber: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        @SerializedName("poster_path") val posterPath: String? = null,
        @SerializedName("episode_count") val episodeCount: Int? = null,
    )

    private data class SeasonDetail(
        @SerializedName("season_number") val seasonNumber: Int? = null,
        val episodes: List<EpisodeDetail>? = null,
    )

    private data class EpisodeDetail(
        val id: Int? = null,
        @SerializedName("episode_number") val episodeNumber: Int? = null,
        @SerializedName("season_number") val seasonNumber: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        @SerializedName("still_path") val stillPath: String? = null,
        @SerializedName("air_date") val airDate: String? = null,
    )

    private data class SourcesResponse(
        val success: Boolean? = null,
        val sources: List<SourceItem>? = null,
    )

    private data class SourceItem(
        val id: String? = null,
        val name: String? = null,
        val url: String? = null,
        val quality: String? = null,
        val isEmbed: Boolean? = null,
        val isPremium: Boolean? = null,
        val lang: String? = null,
        val provider: String? = null,
    )

    // ───────── Mapping helpers ─────────

    private fun TmdbItem.toMovie(): Movie? {
        val tmdbId = id?.toString() ?: return null
        return Movie(
            id = tmdbId,
            title = title ?: name ?: "",
            overview = overview,
            released = releaseDate ?: firstAirDate,
            rating = voteAverage,
            poster = tmdbImg(posterPath),
            banner = tmdbImg(backdropPath, "original"),
        )
    }

    private fun TmdbItem.toTvShow(): TvShow? {
        val tmdbId = id?.toString() ?: return null
        return TvShow(
            id = tmdbId,
            title = name ?: title ?: "",
            overview = overview,
            released = firstAirDate ?: releaseDate,
            rating = voteAverage,
            poster = tmdbImg(posterPath),
            banner = tmdbImg(backdropPath, "original"),
        )
    }

    /** Heuristique : un item de /search/multi est-il une série ? */
    private fun TmdbItem.looksLikeTvShow(): Boolean {
        val mt = mediaType?.lowercase()
        if (mt == "tv") return true
        if (mt == "movie") return false
        // Fallback: champs présents
        return name != null && title == null
    }

    // ───────── Provider impl ─────────

    override suspend fun getHome(): List<Category> = coroutineScope {
        // 6 fetches en parallèle (await individuel — destructuring d'une List<>
        // ne marche que jusqu'à 5 composants en Kotlin).
        val trendingMoviesD = async { fetchList("/api/movies/trending") }
        val popularMoviesD = async { fetchList("/api/movies/popular?page=1") }
        val topMoviesD = async { fetchList("/api/movies/top-rated?page=1") }
        val trendingTvD = async { fetchList("/api/series/trending") }
        val popularTvD = async { fetchList("/api/series/popular?page=1") }
        val topTvD = async { fetchList("/api/series/top-rated?page=1") }

        val trendingMovies = trendingMoviesD.await()
        val popularMovies = popularMoviesD.await()
        val topMovies = topMoviesD.await()
        val trendingTv = trendingTvD.await()
        val popularTv = popularTvD.await()
        val topTv = topTvD.await()

        // FEATURED carousel = tendances mixées
        val featured = (trendingMovies.mapNotNull { it.toMovie() } +
                trendingTv.mapNotNull { it.toTvShow() })
            .shuffled()
            .take(15) as List<AppAdapter.Item>

        listOfNotNull(
            featured.takeIf { it.isNotEmpty() }?.let { Category(name = Category.FEATURED, list = it) },
            trendingMovies.mapNotNull { it.toMovie() }
                .takeIf { it.isNotEmpty() }
                ?.let { Category(name = "Films Tendances", list = it) },
            popularMovies.mapNotNull { it.toMovie() }
                .takeIf { it.isNotEmpty() }
                ?.let { Category(name = "Films Populaires", list = it) },
            topMovies.mapNotNull { it.toMovie() }
                .takeIf { it.isNotEmpty() }
                ?.let { Category(name = "Films les Mieux Notés", list = it) },
            trendingTv.mapNotNull { it.toTvShow() }
                .takeIf { it.isNotEmpty() }
                ?.let { Category(name = "Séries Tendances", list = it) },
            popularTv.mapNotNull { it.toTvShow() }
                .takeIf { it.isNotEmpty() }
                ?.let { Category(name = "Séries Populaires", list = it) },
            topTv.mapNotNull { it.toTvShow() }
                .takeIf { it.isNotEmpty() }
                ?.let { Category(name = "Séries les Mieux Notées", list = it) },
        )
    }

    private suspend fun fetchList(path: String): List<TmdbItem> {
        val body = apiGet(path) ?: return emptyList()
        return try {
            // /api/movies/trending renvoie {results:[...]} sans page
            // /api/movies/popular renvoie {page:N, results:[...]}
            gson.fromJson(body, ListResponse::class.java)?.results ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "fetchList parse failed for $path: ${e.message}")
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        val body = apiGet("/api/search/multi?query=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page")
            ?: return emptyList()
        val list = try {
            gson.fromJson(body, ListResponse::class.java)?.results ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "search parse failed: ${e.message}")
            emptyList()
        }
        return list.mapNotNull { item ->
            if (item.looksLikeTvShow()) item.toTvShow() else item.toMovie()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return fetchList("/api/movies/popular?page=$page").mapNotNull { it.toMovie() }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return fetchList("/api/series/popular?page=$page").mapNotNull { it.toTvShow() }
    }

    override suspend fun getMovie(id: String): Movie {
        val body = apiGet("/api/movies/$id")
            ?: throw Exception("Nakios: film $id introuvable")
        val detail = try {
            gson.fromJson(body, MovieDetail::class.java)
        } catch (e: Exception) {
            throw Exception("Nakios: parse échec film $id (${e.message})")
        }
        return Movie(
            id = id,
            title = detail.title ?: "",
            overview = detail.overview,
            released = detail.releaseDate,
            runtime = detail.runtime,
            rating = detail.voteAverage,
            poster = tmdbImg(detail.posterPath),
            banner = tmdbImg(detail.backdropPath, "original"),
            genres = detail.genres?.mapNotNull {
                val gid = it.id?.toString() ?: return@mapNotNull null
                Genre(id = gid, name = it.name ?: "")
            } ?: emptyList(),
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val body = apiGet("/api/series/$id")
            ?: throw Exception("Nakios: série $id introuvable")
        val detail = try {
            gson.fromJson(body, TvDetail::class.java)
        } catch (e: Exception) {
            throw Exception("Nakios: parse échec série $id (${e.message})")
        }
        return TvShow(
            id = id,
            title = detail.name ?: "",
            overview = detail.overview,
            released = detail.firstAirDate,
            rating = detail.voteAverage,
            poster = tmdbImg(detail.posterPath),
            banner = tmdbImg(detail.backdropPath, "original"),
            genres = detail.genres?.mapNotNull {
                val gid = it.id?.toString() ?: return@mapNotNull null
                Genre(id = gid, name = it.name ?: "")
            } ?: emptyList(),
            seasons = detail.seasons
                ?.filter { (it.seasonNumber ?: 0) > 0 } // skip "Specials" (saison 0)
                ?.mapNotNull { s ->
                    val sn = s.seasonNumber ?: return@mapNotNull null
                    Season(
                        id = "$id|$sn", // composite : id série + numéro saison (utilisé par getEpisodesBySeason)
                        number = sn,
                        title = s.name ?: "Saison $sn",
                        poster = tmdbImg(s.posterPath),
                    )
                } ?: emptyList(),
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // seasonId = "tmdbId|seasonNumber"
        val parts = seasonId.split("|")
        if (parts.size != 2) return emptyList()
        val tvId = parts[0]
        val seasonNum = parts[1].toIntOrNull() ?: return emptyList()
        val body = apiGet("/api/series/$tvId/season/$seasonNum") ?: return emptyList()
        val detail = try {
            gson.fromJson(body, SeasonDetail::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodesBySeason parse failed: ${e.message}")
            return emptyList()
        }
        return detail.episodes?.mapNotNull { ep ->
            val n = ep.episodeNumber ?: return@mapNotNull null
            Episode(
                id = "$tvId|$seasonNum|$n",
                number = n,
                title = ep.name,
                poster = tmdbImg(ep.stillPath, "w500"),
                released = ep.airDate,
                overview = ep.overview,
            )
        } ?: emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        // Nakios supporte /api/movies/discover avec filtre par genre côté TMDB,
        // mais on n'a pas mappé les genres. Pour l'instant on renvoie un genre vide.
        return Genre(id = id, name = id, shows = emptyList())
    }

    override suspend fun getPeople(id: String, page: Int): People {
        // /api/person/{id} existe — implémentation minimale
        val body = apiGet("/api/person/$id")
        val name = body?.let {
            try { gson.fromJson(it, JsonObject::class.java).get("name")?.asString } catch (_: Exception) { null }
        } ?: id
        return People(id = id, name = name, filmography = emptyList())
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val path = when (videoType) {
            is Video.Type.Movie -> "/api/sources/movie/$id"
            is Video.Type.Episode -> {
                // id Episode = "tvId|season|episode" (cf getEpisodesBySeason)
                val parts = id.split("|")
                if (parts.size != 3) return emptyList()
                val tvId = parts[0]
                val seasonN = parts[1]
                val epN = parts[2]
                "/api/sources/tv/$tvId/$seasonN/$epN"
            }
        }
        val body = apiGet(path) ?: return emptyList()
        val resp = try {
            gson.fromJson(body, SourcesResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "getServers parse failed: ${e.message}")
            return emptyList()
        }
        val sources = resp?.sources ?: return emptyList()
        return sources.mapNotNull { src ->
            val srcUrl = src.url ?: return@mapNotNull null
            // URLs relatives → on les préfixe avec baseUrl
            val absoluteUrl = if (srcUrl.startsWith("http")) srcUrl else "$baseUrl$srcUrl"
            val label = buildString {
                append(src.name ?: src.provider ?: "Nakios")
                src.quality?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
                src.lang?.takeIf { it.isNotBlank() }?.let { append(" [$it]") }
            }
            Video.Server(
                id = src.id ?: srcUrl,
                name = label,
                src = absoluteUrl,
            )
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // Si le src pointe sur une URL MP4/m3u8 directe → lecture directe.
        // Sinon (URL d'embed) → on tente de la résoudre via le sélecteur d'extracteurs.
        val src = server.src
        val isDirect = src.matches(Regex(".*\\.(mp4|m3u8|mpd|webm|mkv)(\\?.*)?$", RegexOption.IGNORE_CASE)) ||
                src.startsWith("$baseUrl/api/")
        return if (isDirect) {
            // 2026-05-04 : déclare explicitement le MimeType. Sans ça, sur la TV
            // (PlayerTvFragment) ExoPlayer fait un auto-detect basé sur l'extension
            // de l'URL — qui rate les URLs proxy `api.nakios.fit/api/sources/proxy?url=...`
            // (pas de .m3u8 dans le path) → tombe sur Mp4Extractor → crash avec
            // UnrecognizedInputFormatException. Le mobile a un fallback HLS-detect
            // (TeeDataSource) qui rattrapait ; pas la TV.
            // On regarde l'URL upstream encodée (param `url=` du proxy) pour deviner.
            val upstreamUrl = if (src.contains("/api/sources/proxy?url=")) {
                java.net.URLDecoder.decode(src.substringAfter("url=").substringBefore("&"), "UTF-8")
            } else src
            val mimeType = when {
                upstreamUrl.contains(".m3u8") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                upstreamUrl.contains(".mpd") -> androidx.media3.common.MimeTypes.APPLICATION_MPD
                upstreamUrl.contains(".mp4") -> androidx.media3.common.MimeTypes.VIDEO_MP4
                upstreamUrl.contains(".webm") -> androidx.media3.common.MimeTypes.VIDEO_WEBM
                upstreamUrl.contains(".mkv") -> androidx.media3.common.MimeTypes.VIDEO_MATROSKA
                // Par défaut, les sources Nakios via /api/sources/proxy?url= sont
                // 99% HLS (Darkibox/Xalaflix servent du HLS via leur proxy).
                src.startsWith("$baseUrl/api/") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                else -> null
            }
            Video(
                source = src,
                type = mimeType,
                headers = mapOf(
                    "Origin" to currentFrontUrl,
                    "Referer" to "$currentFrontUrl/",
                    "User-Agent" to USER_AGENT,
                ),
            )
        } else {
            // Tente d'utiliser un extracteur connu (DoodLa, Voe, Filemoon, etc.)
            try {
                com.streamflixreborn.streamflix.extractors.Extractor.extract(src)
            } catch (e: Exception) {
                Log.w(TAG, "Extractor failed for $src, fallback to direct play: ${e.message}")
                Video(
                    source = src,
                    headers = mapOf(
                        "Referer" to "$currentFrontUrl/",
                        "User-Agent" to USER_AGENT,
                    ),
                )
            }
        }
    }
}
