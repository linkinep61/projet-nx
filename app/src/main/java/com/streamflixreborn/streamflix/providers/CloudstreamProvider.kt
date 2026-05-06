package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TMDb3.w780
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 2026-05-06 v2 : Cloudstream — refacto inspiré de MovixProvider.
 *
 * **Catalogue & home : 100% TMDB** (popular/top_rated/trending). Plus de
 * dépendance à l'API mobile-bff pour le browsing — TMDB fournit un catalogue
 * propre, mainstream, déjà filtré (pas de DramaBox, pas de pubs, pas de
 * contenu régional aléatoire).
 *
 * **Lecture : MovieBox+ mobile-bff via /resource**. Au moment du play, on
 * fait un search sur l'API Cloudstream (api*.aoneroom.com/wefeed-mobile-bff)
 * avec le titre TMDB + année, on prend le subjectId qui matche et on récupère
 * les streams MP4 signés via /resource (URLs `bcdn.hakunaymatata.com` sans
 * pre-roll publicitaire, contrairement à /play-info qui retourne `hcdn3` avec
 * des pubs).
 *
 * IDs :
 *   - Movie / TvShow : TMDB id (entier, e.g. "12345")
 *   - Season         : "<tmdbId>-<seasonNumber>"
 *   - Episode        : "<tmdbId>:<seasonNumber>:<episodeNumber>"
 *
 * Réfs reverse-engineering : https://github.com/Simatwa/moviebox-api (v3).
 */
object CloudstreamProvider : Provider {

    override val name = "Cloudstream"
    override val baseUrl = "https://api.themoviedb.org/3/"  // TMDB pour browsing
    override val language = "fr"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_cloudstream"

    private const val TAG = "CloudstreamProvider"

    // ── Cloudstream backend (api*.aoneroom.com / wefeed-mobile-bff) ──────

    /** Pool de hosts à essayer en cascade (fallback en cas de 403/429/500). */
    private val HOST_POOL = listOf(
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api6sg.aoneroom.com",
        "https://api.inmoviebox.com",
    )

    private const val SECRET_KEY_DEFAULT_B64 = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
    private val SECRET_KEY_BYTES: ByteArray by lazy {
        android.util.Base64.decode(
            SECRET_KEY_DEFAULT_B64.padEnd(((SECRET_KEY_DEFAULT_B64.length + 3) / 4) * 4, '='),
            android.util.Base64.DEFAULT
        )
    }

    private const val USER_AGENT =
        "com.community.oneroom/50020045 (Linux; U; Android 13; en_US; 23078RKD5C; Build/TQ2A.230405.003; Cronet/135.0.7012.3)"
    private val CLIENT_INFO = """
        {"package_name":"com.community.oneroom","version_name":"3.0.03.0529.03","version_code":50020045,
        "os":"android","os_version":"13","install_ch":"ps","device_id":"a1b2c3d4e5f60718293a4b5c6d7e8f90",
        "install_store":"ps","gaid":"00000000-0000-0000-0000-000000000000","brand":"Redmi",
        "model":"23078RKD5C","system_language":"fr","net":"NETWORK_WIFI","region":"FR",
        "timezone":"Europe/Paris","sp_code":"40401","X-Play-Mode":"2"}
    """.trimIndent().replace("\n", "").replace("        ", "")

    private const val MAIN_PAGE_PATH = "/wefeed-mobile-bff/tab-operating"
    private const val SEARCH_PATH = "/wefeed-mobile-bff/subject-api/search"
    private const val SUBJECT_GET_PATH = "/wefeed-mobile-bff/subject-api/get"
    private const val RESOURCE_PATH = "/wefeed-mobile-bff/subject-api/resource"
    private const val PLAY_INFO_PATH = "/wefeed-mobile-bff/subject-api/play-info"
    private const val SEASON_INFO_PATH = "/wefeed-mobile-bff/subject-api/season-info"

    /** Regex pour identifier un suffixe de langue non-FR dans un titre. */
    private val LANG_NON_FR_REGEX = Regex(
        """\[(Hindi|Tamil|Telugu|Korean|Japanese|Indonesian|Thai|Vietnamese|""" +
        """Arabic|Spanish|Portuguese|Mandarin|Cantonese|Russian|Turkish|""" +
        """Bengali|Punjabi|Urdu|Gujarati|Marathi|Malayalam|Kannada|Sinhala|""" +
        """Burmese|Filipino|Tagalog|Khmer|Lao|Nepali|Polish|Italian|German|""" +
        """Greek|Hebrew|Persian|Farsi|Swahili|Romanian|Hungarian|Czech|""" +
        """Dutch|Swedish|Norwegian|Danish|Finnish|Ukrainian)\]""",
        RegexOption.IGNORE_CASE
    )

    /** Regex pour stripper n'importe quel suffixe `[XYZ]` à la fin du titre. */
    private val LANG_SUFFIX_REGEX = Regex("""\s*\[[A-Za-z]+\]\s*$""")

    /** Détection de DramaBox / shorts (signaux multiples). */
    private fun isShortDrama(o: JSONObject): Boolean {
        if (o.optInt("shortsEpisode", 0) > 0) return true
        val genre = o.optString("genre").orEmpty()
        if (genre.equals("Drame moderne", ignoreCase = true)) return true
        val seconds = o.optInt("seconds", 0)
        if (seconds in 1..599) return true
        return false
    }

    private val httpClient: OkHttpClient by lazy {
        Extractor.sharedClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // ── HMAC-MD5 signing ──────────────────────────────────────────────────

    private fun generateXClientToken(tsMs: Long): String {
        val ts = tsMs.toString()
        val reversed = ts.reversed()
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(reversed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$ts,$md5"
    }

    private fun sortedQueryString(query: String): String {
        if (query.isEmpty()) return ""
        val params = query.split("&").mapNotNull {
            val eq = it.indexOf("=")
            if (eq < 0) it to "" else it.substring(0, eq) to it.substring(eq + 1)
        }
        return params.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun buildCanonicalString(
        method: String, accept: String?, contentType: String?,
        url: String, body: String?, tsMs: Long,
    ): String {
        val uri = java.net.URI(url)
        val path = uri.rawPath ?: ""
        val query = sortedQueryString(uri.rawQuery ?: "")
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
        val (bodyHash, bodyLen) = if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val md5 = java.security.MessageDigest.getInstance("MD5")
                .digest(bytes.take(102_400).toByteArray())
                .joinToString("") { "%02x".format(it) }
            md5 to bytes.size.toString()
        } else "" to ""
        return "${method.uppercase()}\n${accept.orEmpty()}\n${contentType.orEmpty()}\n$bodyLen\n$tsMs\n$bodyHash\n$canonicalUrl"
    }

    private fun generateXTrSignature(
        method: String, accept: String?, contentType: String?,
        url: String, body: String?, tsMs: Long,
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, tsMs)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SECRET_KEY_BYTES, "HmacMD5"))
        val sigBytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val sigB64 = android.util.Base64.encodeToString(sigBytes, android.util.Base64.NO_WRAP)
        return "$tsMs|2|$sigB64"
    }

    private fun signedHeaders(
        method: String, url: String, body: String? = null,
        accept: String = "application/json",
        contentType: String = "application/json",
    ): Map<String, String> {
        val ts = System.currentTimeMillis()
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to accept,
            "Content-Type" to contentType,
            "Connection" to "keep-alive",
            "X-Client-Token" to generateXClientToken(ts),
            "x-tr-signature" to generateXTrSignature(method, accept, contentType, url, body, ts),
            "X-Client-Info" to CLIENT_INFO,
            "X-Client-Status" to "0",
        )
    }

    // ── HTTP avec rotation de host ──────────────────────────────────────

    private suspend fun apiGet(path: String, params: Map<String, String> = emptyMap()): JSONObject? = withContext(Dispatchers.IO) {
        val query = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val pathWithQuery = if (query.isNotBlank()) "$path?$query" else path
        for (host in HOST_POOL) {
            val url = "$host$pathWithQuery"
            try {
                val req = Request.Builder().url(url).apply {
                    signedHeaders("GET", url).forEach { (k, v) -> header(k, v) }
                }.build()
                val resp = httpClient.newCall(req).execute()
                resp.use {
                    val code = it.code
                    if (code in setOf(403, 407, 429, 500, 502, 503, 504)) {
                        Log.d(TAG, "Host $host returned $code, retry next")
                        return@use
                    }
                    if (!it.isSuccessful) return@withContext null
                    val body = it.body?.string() ?: return@withContext null
                    return@withContext JSONObject(body)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Host $host error: ${e.message}, retry next")
            }
        }
        null
    }

    private suspend fun apiPost(path: String, jsonBody: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val bodyStr = jsonBody.toString()
        for (host in HOST_POOL) {
            val url = "$host$path"
            try {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val req = Request.Builder().url(url).apply {
                    signedHeaders("POST", url, bodyStr, contentType = "application/json; charset=utf-8").forEach { (k, v) -> header(k, v) }
                }.post(bodyStr.toRequestBody(mediaType)).build()
                val resp = httpClient.newCall(req).execute()
                resp.use {
                    val code = it.code
                    if (code in setOf(403, 407, 429, 500, 502, 503, 504)) return@use
                    if (!it.isSuccessful) return@withContext null
                    val body = it.body?.string() ?: return@withContext null
                    return@withContext JSONObject(body)
                }
            } catch (e: Exception) {
                Log.d(TAG, "POST host $host error: ${e.message}")
            }
        }
        null
    }

    // ── TMDB → Cloudstream subjectId mapping (avec cache) ──────────────

    /** Cache process-wide : cleanTitle|year → subjectId (vide = pas trouvé). */
    private val tmdbToSubjectIdCache = ConcurrentHashMap<String, String>()

    /** Recherche dans l'API Cloudstream le subjectId qui matche `title`+`year`.
     *  Préfère un match exact sur le titre nettoyé (sans suffixe [Hindi]/etc.)
     *  et sur l'année. Fallback : meilleur match disponible. */
    private suspend fun findSubjectId(title: String, year: Int? = null): String? {
        val cleanQuery = TitleNormalizer.cleanForTmdbSearch(title).ifBlank { title }
        val cacheKey = "${cleanQuery.lowercase()}|${year ?: 0}"
        tmdbToSubjectIdCache[cacheKey]?.let { return it.ifBlank { null } }

        val resp = apiPost(SEARCH_PATH, JSONObject().apply {
            put("keyword", cleanQuery)
            put("page", 0)
            put("perPage", 15)
        }) ?: return null
        val items = resp.optJSONObject("data")?.optJSONArray("items") ?: return null

        data class Candidate(val sid: String, val cleanTitle: String, val year: Int)
        val candidates = mutableListOf<Candidate>()
        for (i in 0 until items.length()) {
            val s = items.optJSONObject(i) ?: continue
            val sid = s.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
            val itTitle = s.optString("title")
            val cleanIt = itTitle.replace(LANG_SUFFIX_REGEX, "").trim()
            val itYear = s.optString("releaseDate").take(4).toIntOrNull() ?: 0
            candidates.add(Candidate(sid, cleanIt, itYear))
        }
        if (candidates.isEmpty()) {
            tmdbToSubjectIdCache[cacheKey] = ""
            return null
        }

        // Match exact titre + année si possible
        val exactBoth = candidates.firstOrNull {
            it.cleanTitle.equals(cleanQuery, ignoreCase = true) && (year != null && it.year == year)
        }
        if (exactBoth != null) {
            tmdbToSubjectIdCache[cacheKey] = exactBoth.sid
            return exactBoth.sid
        }
        // Match exact titre, année à ±1
        val exactTitleNearYear = candidates.firstOrNull {
            it.cleanTitle.equals(cleanQuery, ignoreCase = true) && (year == null || kotlin.math.abs(it.year - year) <= 1)
        }
        if (exactTitleNearYear != null) {
            tmdbToSubjectIdCache[cacheKey] = exactTitleNearYear.sid
            return exactTitleNearYear.sid
        }
        // Match exact titre, n'importe quelle année
        val exactTitle = candidates.firstOrNull { it.cleanTitle.equals(cleanQuery, ignoreCase = true) }
        if (exactTitle != null) {
            tmdbToSubjectIdCache[cacheKey] = exactTitle.sid
            return exactTitle.sid
        }
        // Pas de match strict — on rejette plutôt que prendre n'importe quoi
        tmdbToSubjectIdCache[cacheKey] = ""
        return null
    }

    // ── Provider impl : home/catalog 100% TMDB (à la Movix) ─────────────

    private fun TMDb3.MultiItem.toAppItem(): AppAdapter.Item? = when (this) {
        is TMDb3.Movie -> Movie(
            id = id.toString(),
            title = title,
            overview = overview,
            released = releaseDate,
            rating = voteAverage.toDouble(),
            poster = posterPath?.w500,
            banner = backdropPath?.w1280,
        )
        is TMDb3.Tv -> TvShow(
            id = id.toString(),
            title = name,
            overview = overview,
            released = firstAirDate,
            rating = voteAverage.toDouble(),
            poster = posterPath?.w500,
            banner = backdropPath?.w1280,
        )
        else -> null
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val mapMovie: (TMDb3.Movie) -> Movie = { m ->
            Movie(
                id = m.id.toString(),
                title = m.title,
                overview = m.overview,
                released = m.releaseDate,
                rating = m.voteAverage.toDouble(),
                poster = m.posterPath?.w500,
                banner = m.backdropPath?.w1280,
            )
        }
        val mapTv: (TMDb3.Tv) -> TvShow = { t ->
            TvShow(
                id = t.id.toString(),
                title = t.name,
                overview = t.overview,
                released = t.firstAirDate,
                rating = t.voteAverage.toDouble(),
                poster = t.posterPath?.w500,
                banner = t.backdropPath?.w1280,
            )
        }

        val trendingD = async {
            runCatching { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 1, language = language).results }
                .getOrDefault(emptyList())
        }
        val popularMoviesD = async {
            runCatching { TMDb3.MovieLists.popular(page = 1, language = language).results }
                .getOrDefault(emptyList())
        }
        val topMoviesD = async {
            runCatching { TMDb3.MovieLists.topRated(page = 1, language = language).results }
                .getOrDefault(emptyList())
        }
        val popularTvD = async {
            runCatching { TMDb3.TvSeriesLists.popular(page = 1, language = language).results }
                .getOrDefault(emptyList())
        }
        val topTvD = async {
            runCatching { TMDb3.TvSeriesLists.topRated(page = 1, language = language).results }
                .getOrDefault(emptyList())
        }

        val sections = mutableListOf<Category>()

        val featured = trendingD.await().take(10).mapNotNull { it.toAppItem() }
        if (featured.isNotEmpty()) sections.add(Category(name = Category.FEATURED, list = featured))

        val popularTv = popularTvD.await().map(mapTv)
        if (popularTv.isNotEmpty()) sections.add(Category(name = "Séries populaires", list = popularTv))

        val topTv = topTvD.await().map(mapTv)
        if (topTv.isNotEmpty()) sections.add(Category(name = "Séries les mieux notées", list = topTv))

        val popularMovies = popularMoviesD.await().map(mapMovie)
        if (popularMovies.isNotEmpty()) sections.add(Category(name = "Films populaires", list = popularMovies))

        val topMovies = topMoviesD.await().map(mapMovie)
        if (topMovies.isNotEmpty()) sections.add(Category(name = "Films les mieux notés", list = topMovies))

        Log.d(TAG, "getHome (TMDB): ${sections.size} sections, total items=${featured.size + popularMovies.size + topMovies.size + popularTv.size + topTv.size}")
        sections
    }

    /** Films onglet : scrape le backend Cloudstream (tabs 0..5 sur N pages),
     *  filtre subjectType=1 (Movie), filtre suffixe non-FR + DramaBox shorts.
     *  IDs Cloudstream : "cs::m::<subjectId>" — distingués des TMDB (numérique pur). */
    override suspend fun getMovies(page: Int): List<Movie> = coroutineScope {
        if (page > 8) return@coroutineScope emptyList()
        // Pour chaque page demandée, on agrège tabs 0..5
        val responses = (0..5).map { tabId ->
            async { apiGet(MAIN_PAGE_PATH, mapOf("page" to "$page", "tabId" to "$tabId", "version" to "")) }
        }.awaitAll()
        val movies = mutableListOf<Movie>()
        for (resp in responses) {
            val items = resp?.optJSONObject("data")?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val subjects = it.optJSONArray("subjects")
                    ?: it.optJSONObject("group")?.optJSONArray("subjects")
                    ?: continue
                for (j in 0 until subjects.length()) {
                    val raw = subjects.optJSONObject(j) ?: continue
                    val s = raw.optJSONObject("subject") ?: raw
                    if (s.optInt("subjectType", 1) != 1) continue
                    if (isShortDrama(s)) continue
                    val title = s.optString("title")
                    if (LANG_NON_FR_REGEX.containsMatchIn(title)) continue
                    val sid = s.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
                    val cleanTitle = title.replace(LANG_SUFFIX_REGEX, "").trim()
                    movies.add(Movie(
                        id = "cs::m::$sid",
                        title = cleanTitle,
                        overview = s.optString("description").takeIf { it.isNotBlank() },
                        released = s.optString("releaseDate").takeIf { it.isNotBlank() },
                        poster = s.optJSONObject("cover")?.optString("url"),
                        banner = s.optJSONObject("cover")?.optString("url"),
                        rating = s.optString("imdbRatingValue").toDoubleOrNull(),
                        providerName = name,
                    ))
                }
            }
        }
        movies.distinctBy { it.id }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = coroutineScope {
        if (page > 8) return@coroutineScope emptyList()
        val responses = (0..5).map { tabId ->
            async { apiGet(MAIN_PAGE_PATH, mapOf("page" to "$page", "tabId" to "$tabId", "version" to "")) }
        }.awaitAll()
        val shows = mutableListOf<TvShow>()
        for (resp in responses) {
            val items = resp?.optJSONObject("data")?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val subjects = it.optJSONArray("subjects")
                    ?: it.optJSONObject("group")?.optJSONArray("subjects")
                    ?: continue
                for (j in 0 until subjects.length()) {
                    val raw = subjects.optJSONObject(j) ?: continue
                    val s = raw.optJSONObject("subject") ?: raw
                    if (s.optInt("subjectType", 1) != 2) continue
                    if (isShortDrama(s)) continue
                    val title = s.optString("title")
                    if (LANG_NON_FR_REGEX.containsMatchIn(title)) continue
                    val sid = s.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
                    val cleanTitle = title.replace(LANG_SUFFIX_REGEX, "").trim()
                    shows.add(TvShow(
                        id = "cs::s::$sid",
                        title = cleanTitle,
                        overview = s.optString("description").takeIf { it.isNotBlank() },
                        released = s.optString("releaseDate").takeIf { it.isNotBlank() },
                        poster = s.optJSONObject("cover")?.optString("url"),
                        banner = s.optJSONObject("cover")?.optString("url"),
                        rating = s.optString("imdbRatingValue").toDoubleOrNull(),
                        providerName = name,
                    ))
                }
            }
        }
        shows.distinctBy { it.id }
    }

    /** Recherche : tape directement dans /subject-api/search du backend Cloudstream
     *  pour avoir tout ce que la source expose (pas limité à TMDB). Filtre suffixe
     *  non-FR + DramaBox. Pas de cap dur sur le nombre de résultats. */
    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        if (page > 10) return emptyList()
        val cleanQuery = TitleNormalizer.cleanForTmdbSearch(query).ifBlank { query }
        val resp = apiPost(SEARCH_PATH, JSONObject().apply {
            put("keyword", cleanQuery)
            put("page", page - 1)
            put("perPage", 30)
        }) ?: return emptyList()
        val items = resp.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        val results = mutableListOf<AppAdapter.Item>()
        for (i in 0 until items.length()) {
            val s = items.optJSONObject(i) ?: continue
            if (isShortDrama(s)) continue
            val title = s.optString("title")
            if (LANG_NON_FR_REGEX.containsMatchIn(title)) continue
            val sid = s.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
            val cleanTitle = title.replace(LANG_SUFFIX_REGEX, "").trim()
            val parsed: AppAdapter.Item = when (s.optInt("subjectType", 1)) {
                1 -> Movie(
                    id = "cs::m::$sid",
                    title = cleanTitle,
                    overview = s.optString("description").takeIf { it.isNotBlank() },
                    released = s.optString("releaseDate").takeIf { it.isNotBlank() },
                    poster = s.optJSONObject("cover")?.optString("url"),
                    banner = s.optJSONObject("cover")?.optString("url"),
                    rating = s.optString("imdbRatingValue").toDoubleOrNull(),
                    providerName = name,
                )
                2 -> TvShow(
                    id = "cs::s::$sid",
                    title = cleanTitle,
                    overview = s.optString("description").takeIf { it.isNotBlank() },
                    released = s.optString("releaseDate").takeIf { it.isNotBlank() },
                    poster = s.optJSONObject("cover")?.optString("url"),
                    banner = s.optJSONObject("cover")?.optString("url"),
                    rating = s.optString("imdbRatingValue").toDoubleOrNull(),
                    providerName = name,
                )
                else -> continue
            }
            results.add(parsed)
        }
        Log.d(TAG, "search('$cleanQuery' p=$page): ${results.size} hits")
        return results
    }

    override suspend fun getMovie(id: String): Movie {
        // Si c'est un ID Cloudstream (cs::m::<subjectId>), lookup direct via /subject-api/get
        if (id.startsWith("cs::m::")) {
            return getCloudstreamMovie(id)
        }
        val tmdbId = id.toIntOrNull() ?: return Movie(id = id, title = "", providerName = name)
        return runCatching {
            val m = TMDb3.Movies.details(
                movieId = tmdbId,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Movie.CREDITS,
                    TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                    TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
                ),
                language = language,
            )
            Movie(
                id = m.id.toString(),
                title = m.title,
                overview = m.overview,
                released = m.releaseDate,
                runtime = m.runtime,
                trailer = m.videos?.results?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = m.voteAverage.toDouble(),
                poster = m.posterPath?.w780,
                banner = m.backdropPath?.w1280,
                imdbId = m.externalIds?.imdbId,
                genres = m.genres.map { Genre(it.id.toString(), it.name) },
                cast = m.credits?.cast?.map { c ->
                    People(id = c.id.toString(), name = c.name, image = c.profilePath?.w500)
                } ?: emptyList(),
                recommendations = m.recommendations?.results?.mapNotNull {
                    it.toAppItem() as? com.streamflixreborn.streamflix.models.Show
                } ?: emptyList(),
                providerName = name,
            )
        }.getOrElse { Movie(id = id, title = "", providerName = name) }
    }

    /** Lookup détail Cloudstream pour un movie via /subject-api/get. */
    private suspend fun getCloudstreamMovie(id: String): Movie {
        val sid = id.removePrefix("cs::m::")
        val resp = apiGet(SUBJECT_GET_PATH, mapOf("subjectId" to sid))
            ?: return Movie(id = id, title = "", providerName = name)
        val data = resp.optJSONObject("data") ?: return Movie(id = id, title = "", providerName = name)
        val title = data.optString("title").replace(LANG_SUFFIX_REGEX, "").trim()
        return Movie(
            id = id,
            title = title,
            overview = data.optString("description").takeIf { it.isNotBlank() },
            released = data.optString("releaseDate").takeIf { it.isNotBlank() },
            poster = data.optJSONObject("cover")?.optString("url"),
            banner = data.optJSONObject("cover")?.optString("url"),
            rating = data.optString("imdbRatingValue").toDoubleOrNull(),
            providerName = name,
        )
    }

    /** Lookup détail Cloudstream pour une série via /subject-api/get + /season-info. */
    private suspend fun getCloudstreamTvShow(id: String): TvShow = coroutineScope {
        val sid = id.removePrefix("cs::s::")
        val detailD = async { apiGet(SUBJECT_GET_PATH, mapOf("subjectId" to sid)) }
        val seasonsD = async { apiGet(SEASON_INFO_PATH, mapOf("subjectId" to sid)) }
        val resp = detailD.await() ?: return@coroutineScope TvShow(id = id, title = "", providerName = name)
        val data = resp.optJSONObject("data") ?: return@coroutineScope TvShow(id = id, title = "", providerName = name)
        val title = data.optString("title").replace(LANG_SUFFIX_REGEX, "").trim()
        // Saisons via season-info, fallback sur seNum du détail
        val seasons = mutableListOf<Season>()
        seasonsD.await()?.optJSONObject("data")?.optJSONArray("seasons")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val seNum = s.optInt("se", i + 1)
                seasons.add(Season(
                    id = "cs::season::$sid::$seNum",
                    number = seNum,
                    title = s.optString("title").ifEmpty { "Saison $seNum" },
                    poster = s.optJSONObject("cover")?.optString("url"),
                ))
            }
        }
        if (seasons.isEmpty()) {
            val seNum = data.optInt("seNum", 0)
            for (i in 1..seNum) {
                seasons.add(Season(
                    id = "cs::season::$sid::$i",
                    number = i,
                    title = "Saison $i",
                    poster = data.optJSONObject("cover")?.optString("url"),
                ))
            }
        }
        TvShow(
            id = id,
            title = title,
            overview = data.optString("description").takeIf { it.isNotBlank() },
            released = data.optString("releaseDate").takeIf { it.isNotBlank() },
            poster = data.optJSONObject("cover")?.optString("url"),
            banner = data.optJSONObject("cover")?.optString("url"),
            rating = data.optString("imdbRatingValue").toDoubleOrNull(),
            seasons = seasons,
            providerName = name,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        if (id.startsWith("cs::s::")) {
            return getCloudstreamTvShow(id)
        }
        val tmdbId = id.toIntOrNull() ?: return TvShow(id = id, title = "", providerName = name)
        return runCatching {
            val tv = TMDb3.TvSeries.details(
                seriesId = tmdbId,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Tv.CREDITS,
                    TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                    TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
                ),
                language = language,
            )
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                trailer = tv.videos?.results?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.w780,
                banner = tv.backdropPath?.w1280,
                imdbId = tv.externalIds?.imdbId,
                seasons = tv.seasons.map { s ->
                    Season(
                        id = "${tv.id}-${s.seasonNumber}",
                        number = s.seasonNumber,
                        title = s.name,
                        poster = s.posterPath?.w500,
                    )
                },
                genres = tv.genres.map { Genre(it.id.toString(), it.name) },
                cast = tv.credits?.cast?.map { c ->
                    People(id = c.id.toString(), name = c.name, image = c.profilePath?.w500)
                } ?: emptyList(),
                recommendations = tv.recommendations?.results?.mapNotNull {
                    it.toAppItem() as? com.streamflixreborn.streamflix.models.Show
                } ?: emptyList(),
                providerName = name,
            )
        }.getOrElse { TvShow(id = id, title = "", providerName = name) }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // Cloudstream backend : seasonId = "cs::season::<sid>::<seNum>"
        if (seasonId.startsWith("cs::season::")) {
            val parts = seasonId.removePrefix("cs::season::").split("::")
            if (parts.size != 2) return emptyList()
            val sid = parts[0]
            val seNum = parts[1].toIntOrNull() ?: return emptyList()
            return getCloudstreamEpisodes(sid, seNum)
        }
        // TMDB : seasonId = "<tmdbId>-<seNum>"
        val parts = seasonId.split("-")
        if (parts.size != 2) return emptyList()
        val tvId = parts[0].toIntOrNull() ?: return emptyList()
        val seNum = parts[1].toIntOrNull() ?: return emptyList()
        return runCatching {
            TMDb3.TvSeasons.details(seriesId = tvId, seasonNumber = seNum, language = language)
                .episodes?.map {
                    Episode(
                        id = "$tvId:$seNum:${it.episodeNumber}",
                        number = it.episodeNumber,
                        title = it.name ?: "",
                        released = it.airDate,
                        poster = it.stillPath?.w500,
                    )
                } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Liste des épisodes d'une série Cloudstream via /resource paginé.
     *  Le champ `episode` est encodé S*100+EP (101 = S1E1, 203 = S2E3). */
    private suspend fun getCloudstreamEpisodes(sid: String, seNum: Int): List<Episode> {
        val episodes = mutableMapOf<Int, Episode>()
        var page = 1
        while (page <= 20) {
            val r = apiGet(RESOURCE_PATH, mapOf(
                "subjectId" to sid,
                "se" to "$seNum",
                "ep" to "1",
                "page" to "$page",
            )) ?: break
            val data = r.optJSONObject("data") ?: break
            val list = data.optJSONArray("list") ?: break
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                if (it.optInt("se") != seNum) continue
                val ep = it.optInt("ep")
                if (ep <= 0 || episodes.containsKey(ep)) continue
                val title = it.optString("title").ifBlank { "Épisode $ep" }
                episodes[ep] = Episode(
                    id = "cs::ep::$sid::$seNum::$ep",
                    number = ep,
                    title = title,
                )
            }
            val pager = data.optJSONObject("pager")
            if (pager?.optBoolean("hasMore", false) != true) break
            page++
        }
        if (episodes.isEmpty()) {
            // Fallback : season-info pour récupérer maxEp
            val seasonsResp = apiGet(SEASON_INFO_PATH, mapOf("subjectId" to sid))
            val arr = seasonsResp?.optJSONObject("data")?.optJSONArray("seasons")
            var maxEp = 0
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    if (s.optInt("se", -1) == seNum) {
                        maxEp = s.optInt("maxEp", s.optInt("epNum", 0))
                        break
                    }
                }
            }
            if (maxEp <= 0) return emptyList()
            return (1..maxEp).map { ep ->
                Episode(id = "cs::ep::$sid::$seNum::$ep", number = ep, title = "Épisode $ep")
            }
        }
        return episodes.values.sortedBy { it.number }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        // Genre browsing simplifié : on ne fait rien (la liste de films/séries
        // d'un genre se fait via les onglets Films/Séries).
        return Genre(id = id, name = "", shows = emptyList())
    }

    override suspend fun getPeople(id: String, page: Int): People =
        People(id = id, name = "", filmography = emptyList())

    // ── Lecture : recherche subjectId Cloudstream + /resource ───────────

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = coroutineScope {
        // Cas 1 : id Cloudstream (cs::m::sid, cs::s::sid, cs::ep::sid::se::ep)
        // → on a déjà le subjectId, pas besoin de search
        var subjectId: String? = null
        var se = 0
        var ep = 0
        var queryTitle = ""
        var year: Int? = null

        when {
            id.startsWith("cs::ep::") -> {
                val parts = id.removePrefix("cs::ep::").split("::")
                subjectId = parts.getOrNull(0)
                se = parts.getOrNull(1)?.toIntOrNull() ?: 0
                ep = parts.getOrNull(2)?.toIntOrNull() ?: 0
            }
            id.startsWith("cs::m::") -> {
                subjectId = id.removePrefix("cs::m::")
            }
            id.startsWith("cs::s::") -> {
                subjectId = id.removePrefix("cs::s::")
            }
            else -> {
                // Cas 2 : id TMDB → extract title/year/se/ep depuis videoType, search backend
                when (videoType) {
                    is Video.Type.Movie -> {
                        queryTitle = videoType.title
                        year = videoType.releaseDate.take(4).toIntOrNull()
                    }
                    is Video.Type.Episode -> {
                        queryTitle = videoType.tvShow.title
                        year = videoType.tvShow.releaseDate?.take(4)?.toIntOrNull()
                        val parts = id.split(":")
                        se = parts.getOrNull(1)?.toIntOrNull() ?: videoType.season.number
                        ep = parts.getOrNull(2)?.toIntOrNull() ?: videoType.number
                    }
                }
                if (queryTitle.isBlank()) {
                    Log.d(TAG, "getServers : titre vide pour $id")
                    return@coroutineScope emptyList()
                }
                subjectId = findSubjectId(queryTitle, year)
                if (subjectId.isNullOrBlank()) {
                    Log.d(TAG, "getServers : pas de subjectId Cloudstream pour '$queryTitle' ($year)")
                    return@coroutineScope emptyList()
                }
                Log.d(TAG, "getServers TMDB→Cloudstream : '$queryTitle' ($year) → sid=$subjectId, se=$se ep=$ep")
            }
        }
        if (subjectId.isNullOrBlank()) {
            return@coroutineScope emptyList()
        }

        // Tenter /resource pour les 4 résolutions en parallèle
        val resolutions = listOf(360, 480, 720, 1080)
        val streams = resolutions.map { resoltn ->
            async { findResourceStream(subjectId, se, ep, resoltn) }
        }.awaitAll().filterNotNull().distinctBy { it.first }.sortedByDescending { it.first }

        val servers = mutableListOf<Video.Server>()
        for ((idx, t) in streams.withIndex()) {
            servers.add(
                Video.Server(
                    id = "cs_resource_${subjectId}_${se}_${ep}_${t.first}_$idx",
                    name = "Cloudstream [${t.first}p MP4]",
                    src = t.second,
                )
            )
        }
        if (servers.isNotEmpty()) {
            Log.d(TAG, "getServers /resource $id → ${servers.size} streams (bcdn)")
            return@coroutineScope servers
        }

        // Fallback play-info (DASH ou MP4 hcdn3 — peut avoir pre-roll)
        val params = mutableMapOf("subjectId" to subjectId)
        if (se > 0) params["se"] = "$se"
        if (ep > 0) params["ep"] = "$ep"
        val resp = apiGet(PLAY_INFO_PATH, params) ?: return@coroutineScope emptyList()
        val data = resp.optJSONObject("data") ?: return@coroutineScope emptyList()
        val streamArr = data.optJSONArray("streams") ?: return@coroutineScope emptyList()
        val list = (0 until streamArr.length()).mapNotNull { i ->
            val s = streamArr.optJSONObject(i) ?: return@mapNotNull null
            val u = s.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val res = s.optString("resolutions").ifEmpty { s.optString("resolution") }
            (res.toIntOrNull() ?: 0) to u
        }.sortedByDescending { it.first }
        for ((idx, t) in list.withIndex()) {
            servers.add(
                Video.Server(
                    id = "cs_playinfo_${subjectId}_${se}_${ep}_${t.first}_$idx",
                    name = "Cloudstream [${t.first}p MP4]",
                    src = t.second,
                )
            )
        }
        Log.d(TAG, "getServers play-info $id → ${servers.size} streams (fallback)")
        servers
    }

    /** Cherche le stream d'un épisode/film donné dans /resource (paginé par 10).
     *  Retourne (resolution, url) ou null si pas trouvé. */
    private suspend fun findResourceStream(subjectId: String, se: Int, ep: Int, resoltn: Int): Pair<Int, String>? {
        val targetCode = if (se > 0 && ep > 0) se * 100 + ep else 0
        var page = 1
        while (page <= 8) {
            val r = apiGet(RESOURCE_PATH, mapOf(
                "subjectId" to subjectId,
                "se" to "${if (se > 0) se else 1}",
                "ep" to "${if (ep > 0) ep else 1}",
                "page" to "$page",
                "resolution" to "$resoltn",
            )) ?: return null
            val data = r.optJSONObject("data") ?: return null
            val list = data.optJSONArray("list") ?: return null
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val epCode = item.optInt("episode", -1)
                if (targetCode == 0 || epCode == targetCode) {
                    val u = item.optString("resourceLink").takeIf { it.isNotBlank() } ?: continue
                    return resoltn to u
                }
            }
            val pager = data.optJSONObject("pager")
            if (pager?.optBoolean("hasMore", false) != true) break
            page++
        }
        return null
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Video(
            source = server.src,
            headers = mutableMapOf(
                "Referer" to "https://moviebox.ph/",
                "User-Agent" to USER_AGENT,
            ),
        )
    }

}
