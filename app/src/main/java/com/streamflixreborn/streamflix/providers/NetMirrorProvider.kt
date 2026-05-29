package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.CatalogFilter
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TMDb3.w780
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.extractors.Extractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * NetMirror — provider qui agrège Netflix, Prime Video, Hotstar et Disney+
 * via le backend miroir net52.cc / net22.cc.
 *
 * **Catalogue** : 100% TMDB filtré par plateformes de streaming (Netflix,
 * Prime Video, Disney+) disponibles en France → contenu international
 * avec audio FR (VF/VOSTFR) quasi systématique.
 *
 * **Lecture** : backend NetMirror (net52.cc) via le système "NewTV" API
 * (domaines rotatifs mobidetect*). On cherche le titre TMDB sur les 4 OTT
 * mirrors et on retourne les liens M3U8 trouvés.
 *
 * IDs :
 *   - Movie / TvShow : TMDB id (entier)
 *   - Season : "<tmdbId>-<seasonNumber>"
 *   - Episode : "<tmdbId>:<seasonNumber>:<episodeNumber>"
 */
object NetMirrorProvider : Provider, ProgressiveServersProvider {

    override val name = "NetMirror"
    override val baseUrl = "https://api.themoviedb.org/3/"
    override val language = "fr"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_netmirror"

    private const val TAG = "NetMirrorProvider"

    // ══════════════════════════════════════════════════════════════════════
    //  NetMirror backend — auth + API
    // ══════════════════════════════════════════════════════════════════════

    private const val MAIN_URL = "https://net52.cc"
    private const val ALT_URL = "https://net22.cc"
    private const val VERIFY_URL = "https://net52.cc/verify.php"
    private const val IMG_CDN = "https://imgcdn.kim"

    /** Cookie de session t_hash_t (durée de vie ~15h). */
    @Volatile private var cachedCookie: String? = null
    @Volatile private var cookieExpiry: Long = 0L
    private const val COOKIE_TTL_MS = 54_000_000L // 15h

    /** URL de l'API NewTV résolue via checknewtv.php. */
    @Volatile private var resolvedApiUrl: String? = null
    @Volatile private var apiUrlExpiry: Long = 0L
    private const val API_URL_TTL_MS = 3_600_000L // 1h

    /** OTT platforms avec leur cookie ott + préfixes d'URL. */
    enum class OttPlatform(
        val ottCookie: String,
        val label: String,
        val searchPath: String,
        val postPath: String,
        val episodesPath: String,
        val posterPrefix: String,
        val bannerPrefix: String,
        val epImgPrefix: String,
    ) {
        NETFLIX(
            ottCookie = "nf",
            label = "Netflix",
            searchPath = "/mobile/search.php",
            postPath = "/mobile/post.php",
            episodesPath = "/mobile/episodes.php",
            posterPrefix = "/poster/v/",
            bannerPrefix = "/poster/h/",
            epImgPrefix = "/epimg/150/",
        ),
        PRIME_VIDEO(
            ottCookie = "pv",
            label = "Prime Video",
            searchPath = "/mobile/pv/search.php",
            postPath = "/mobile/pv/post.php",
            episodesPath = "/mobile/pv/episodes.php",
            posterPrefix = "/pv/v/",
            bannerPrefix = "/pv/h/",
            epImgPrefix = "/pvepimg/",
        ),
        HOTSTAR(
            ottCookie = "hs",
            label = "Hotstar",
            searchPath = "/mobile/hs/search.php",
            postPath = "/mobile/hs/post.php",
            episodesPath = "/mobile/hs/episodes.php",
            posterPrefix = "/hs/v/",
            bannerPrefix = "/hs/h/",
            epImgPrefix = "/hsepimg/150/",
        ),
        DISNEY_PLUS(
            ottCookie = "dp",
            label = "Disney+",
            searchPath = "/mobile/hs/search.php",
            postPath = "/mobile/hs/post.php",
            episodesPath = "/mobile/hs/episodes.php",
            posterPrefix = "/hs/v/",
            bannerPrefix = "/hs/h/",
            epImgPrefix = "/hsepimg/150/",
        );
    }

    /** Domaines NewTV à essayer pour résoudre l'API de streaming.
     *  .art vérifié fonctionnel (2026-05-22). Les autres peuvent timeout
     *  ou NXDOMAIN selon la géo — on itère jusqu'au 1er qui répond. */
    private val NEWTV_DOMAINS: List<String> by lazy {
        val priorityTlds = listOf(".art", ".cc", ".ink", ".pro", ".vip", ".wiki")
        val otherTlds = listOf(".com", ".app", ".click", ".live", ".shop",
            ".site", ".space", ".store", ".xyz")
        val bases = listOf("mobidetect", "mobidetects")
        // .art en premier (confirmé OK), puis les autres
        bases.flatMap { b -> (priorityTlds + otherTlds).map { t -> "https://$b$t" } }
    }

    private val httpClient: OkHttpClient by lazy {
        Extractor.sharedClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private val httpClientFollowRedirects: OkHttpClient by lazy {
        httpClient.newBuilder().followRedirects(true).build()
    }

    // ── Auth : bypass reCAPTCHA ─────────────────────────────────────────

    /**
     * Obtient un cookie de session t_hash_t en postant un faux token reCAPTCHA.
     * Le cookie est mis en cache 15h.
     */
    private suspend fun ensureCookie(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedCookie?.let { if (now < cookieExpiry) return@withContext it }

        val body = FormBody.Builder()
            .add("g-recaptcha-response", UUID.randomUUID().toString())
            .build()
        val req = Request.Builder()
            .url(VERIFY_URL)
            .post(body)
            .header("User-Agent", "Mozilla/5.0")
            .header("Origin", ALT_URL)
            .header("Referer", "$ALT_URL/")
            .build()

        val resp = httpClient.newCall(req).execute()
        resp.use { r ->
            val cookies = r.headers("Set-Cookie")
            val tHash = cookies.firstNotNullOfOrNull { cookie ->
                if (cookie.startsWith("t_hash_t=")) {
                    cookie.substringBefore(";")
                } else null
            } ?: throw IllegalStateException("NetMirror: no t_hash_t cookie received")

            cachedCookie = tHash
            cookieExpiry = now + COOKIE_TTL_MS
            Log.d(TAG, "bypass() OK — cookie: ${tHash.take(30)}…")
            tHash
        }
    }

    /** Construit la chaîne de cookies pour une plateforme OTT donnée. */
    private suspend fun buildCookies(platform: OttPlatform): String {
        val auth = ensureCookie()
        return "$auth; ott=${platform.ottCookie}; hd=on"
    }

    // ── Résolution API NewTV ────────────────────────────────────────────

    /**
     * Résout l'URL de l'API NewTV en itérant sur les domaines mobidetect*.
     * Cache le résultat 1h.
     */
    private suspend fun resolveApiUrl(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        resolvedApiUrl?.let { if (now < apiUrlExpiry) return@withContext it }

        for (domain in NEWTV_DOMAINS) {
            try {
                val url = "$domain/checknewtv.php"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("X-Requested-With", "NetmirrorNewTV v1.0")
                    .build()

                val resp = httpClientFollowRedirects.newCall(req).execute()
                resp.use { r ->
                    if (!r.isSuccessful) return@use
                    val body = r.body?.string() ?: return@use
                    val json = runCatching { JSONObject(body) }.getOrNull() ?: return@use
                    val tokenHash = json.optString("token_hash").takeIf { it.isNotBlank() }
                        ?: return@use
                    // Decode base64 → URL de l'API réelle
                    val decoded = String(Base64.decode(tokenHash, Base64.DEFAULT)).trim()
                    if (decoded.startsWith("http")) {
                        resolvedApiUrl = decoded
                        apiUrlExpiry = now + API_URL_TTL_MS
                        Log.d(TAG, "resolveApiUrl() OK via $domain → $decoded")
                        return@withContext decoded
                    }
                }
            } catch (e: Exception) {
                // Essayer le domaine suivant
            }
        }
        throw IllegalStateException("NetMirror: impossible de résoudre l'API NewTV (24 domaines testés)")
    }

    // ── Recherche sur le backend NetMirror ─────────────────────────────

    /** Normalise un titre pour la comparaison (lowercase, trim, strip unicode). */
    private fun normalizeTitle(raw: String): String =
        TitleNormalizer.stripUnicodeArtifacts(raw).lowercase().trim()

    /** Cache des IDs NetMirror par titre : "titre_normalisé" → Map<OttPlatform, netmirrorId> */
    private val idCache = ConcurrentHashMap<String, Map<OttPlatform, String>>()

    /**
     * Cherche un titre sur toutes les plateformes OTT du backend NetMirror.
     * Retourne une map plateforme → ID NetMirror pour les plateformes qui ont ce contenu.
     */
    private suspend fun searchOnNetMirror(
        title: String,
        year: Int? = null,
    ): Map<OttPlatform, String> = coroutineScope {
        val cacheKey = normalizeTitle(title) + (year?.let { "_$it" } ?: "")
        idCache[cacheKey]?.let { return@coroutineScope it }

        val results = ConcurrentHashMap<OttPlatform, String>()
        val ts = System.currentTimeMillis() / 1000

        OttPlatform.entries.map { platform ->
            async(Dispatchers.IO) {
                try {
                    val cookies = buildCookies(platform)
                    val url = "$MAIN_URL${platform.searchPath}?s=${java.net.URLEncoder.encode(title, "UTF-8")}&t=$ts"
                    val req = Request.Builder()
                        .url(url)
                        .header("Cookie", cookies)
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Referer", "$MAIN_URL/")
                        .build()

                    val resp = httpClientFollowRedirects.newCall(req).execute()
                    resp.use { r ->
                        if (!r.isSuccessful) return@async
                        val body = r.body?.string() ?: return@async
                        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@async
                        val searchResults = json.optJSONArray("searchResult") ?: return@async

                        // Trouver le meilleur match
                        val normalizedTitle = normalizeTitle(title)
                        for (i in 0 until searchResults.length()) {
                            val item = searchResults.optJSONObject(i) ?: continue
                            val resultTitle = item.optString("t")
                            val resultId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                            val normalizedResult = normalizeTitle(resultTitle)

                            if (normalizedResult == normalizedTitle ||
                                normalizedResult.contains(normalizedTitle) ||
                                normalizedTitle.contains(normalizedResult)) {
                                results[platform] = resultId
                                break
                            }
                        }
                        // Fallback : prendre le 1er résultat s'il n'y en a qu'un
                        if (!results.containsKey(platform) && searchResults.length() == 1) {
                            val first = searchResults.optJSONObject(0)
                            val firstId = first?.optString("id")?.takeIf { it.isNotBlank() }
                            if (firstId != null) {
                                results[platform] = firstId
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "searchOnNetMirror ${platform.label} error: ${e.message}")
                }
            }
        }.awaitAll()

        if (results.isNotEmpty()) {
            idCache[cacheKey] = results.toMap()
        }
        Log.d(TAG, "searchOnNetMirror '$title' → ${results.map { "${it.key.label}:${it.value}" }}")
        results.toMap()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Catalogue — TMDB (copie conforme de CloudstreamProvider)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Filtre par plateforme de streaming dispo en France (Netflix, Prime, Disney+).
     * Contrairement aux autres providers qui filtrent par langue d'origine, NetMirror
     * filtre par "disponible sur ces plateformes en France" — ces contenus ont
     * quasi-toujours le VF/VOSTFR car Netflix/Prime/Disney+ doublent en FR.
     */
    private fun nmWatchProviders(): TMDb3.Params.WithBuilder<TMDb3.Provider.WatchProviderId> =
        TMDb3.Params.WithBuilder<TMDb3.Provider.WatchProviderId>(TMDb3.Provider.WatchProviderId.NETFLIX)
            .or(TMDb3.Provider.WatchProviderId.AMAZON_PRIME_VIDEO_TIER_B)
            .or(TMDb3.Provider.WatchProviderId.DISNEY_PLUS)

    /** Filtre additionnel par langue d'origine (CatalogFilter choisi par l'user).
     *  null = pas de restriction de langue (= contenu mondial). Se combine
     *  AVEC le filtre watch providers ci-dessus. */
    private fun nmOriginalLanguageBuilder(): TMDb3.Params.WithBuilder<String>? {
        val lang = CatalogFilter.originalLanguage(name)
        return lang?.let { TMDb3.Params.WithBuilder(it) }
    }

    private val mapMovie: (TMDb3.Movie) -> Movie = { m ->
        Movie(
            id = m.id.toString(),
            title = m.title,
            overview = m.overview,
            released = m.releaseDate,
            rating = m.voteAverage.toDouble(),
            poster = m.posterPath?.w500,
            banner = m.backdropPath?.w1280,
            providerName = name,
        )
    }
    private val mapTv: (TMDb3.Tv) -> TvShow = { t ->
        TvShow(
            id = t.id.toString(),
            title = t.name,
            overview = t.overview,
            released = t.firstAirDate,
            rating = t.voteAverage.toDouble(),
            poster = t.posterPath?.w500,
            banner = t.backdropPath?.w1280,
            providerName = name,
        )
    }

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
        val trendingD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = 1, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                    voteCount = TMDb3.Params.Range(50, null),
                ).results
            }.getOrDefault(emptyList())
        }
        val popularMoviesD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = 1, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                ).results
            }.getOrDefault(emptyList())
        }
        val topMoviesD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = 1, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.VOTE_AVERAGE_DESC,
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                    voteCount = TMDb3.Params.Range(200, null),
                ).results
            }.getOrDefault(emptyList())
        }
        val popularTvD = async {
            runCatching {
                TMDb3.Discover.tv(
                    page = 1, language = language,
                    sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                ).results
            }.getOrDefault(emptyList())
        }
        val topTvD = async {
            runCatching {
                TMDb3.Discover.tv(
                    page = 1, language = language,
                    sortBy = TMDb3.Params.SortBy.Tv.VOTE_AVERAGE_DESC,
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                    voteCount = TMDb3.Params.Range(50, null),
                ).results
            }.getOrDefault(emptyList())
        }
        val newMoviesD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = 1, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                    voteCount = TMDb3.Params.Range(10, null),
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                ).results
            }.getOrDefault(emptyList())
        }
        val newTvD = async {
            runCatching {
                TMDb3.Discover.tv(
                    page = 1, language = language,
                    sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,
                    voteCount = TMDb3.Params.Range(10, null),
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                ).results
            }.getOrDefault(emptyList())
        }

        val categories = mutableListOf<Category>()

        // Featured (carrousel)
        val trending = trendingD.await()
        if (trending.isNotEmpty()) {
            categories.add(Category(name = Category.FEATURED, list = trending.take(15).map(mapMovie)))
        }

        // Nouveaux films
        val newMovies = newMoviesD.await()
        if (newMovies.isNotEmpty()) {
            categories.add(Category(name = "Nouveaux films", list = newMovies.map(mapMovie)))
        }

        // Nouvelles séries
        val newTv = newTvD.await()
        if (newTv.isNotEmpty()) {
            categories.add(Category(name = "Nouvelles séries", list = newTv.map(mapTv)))
        }

        // Films populaires
        val popularMovies = popularMoviesD.await()
        if (popularMovies.isNotEmpty()) {
            categories.add(Category(name = "Films populaires", list = popularMovies.map(mapMovie)))
        }

        // Films les mieux notés
        val topMovies = topMoviesD.await()
        if (topMovies.isNotEmpty()) {
            categories.add(Category(name = "Films les mieux notés", list = topMovies.map(mapMovie)))
        }

        // Séries populaires
        val popularTv = popularTvD.await()
        if (popularTv.isNotEmpty()) {
            categories.add(Category(name = "Séries populaires", list = popularTv.map(mapTv)))
        }

        // Séries les mieux notées
        val topTv = topTvD.await()
        if (topTv.isNotEmpty()) {
            categories.add(Category(name = "Séries les mieux notées", list = topTv.map(mapTv)))
        }

        categories
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return runCatching {
            TMDb3.Discover.movie(
                page = page, language = language, region = "FR",
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                watchRegion = "FR",
                withWatchProviders = nmWatchProviders(),
                withOriginalLanguage = nmOriginalLanguageBuilder(),
            ).results.map(mapMovie)
        }.getOrDefault(emptyList())
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return runCatching {
            TMDb3.Discover.tv(
                page = page, language = language,
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                watchRegion = "FR",
                withWatchProviders = nmWatchProviders(),
                withOriginalLanguage = nmOriginalLanguageBuilder(),
            ).results.map(mapTv)
        }.getOrDefault(emptyList())
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return listOf(
                Genre(id = "28", name = "Action"),
                Genre(id = "12", name = "Aventure"),
                Genre(id = "16", name = "Animation"),
                Genre(id = "35", name = "Comédie"),
                Genre(id = "80", name = "Crime"),
                Genre(id = "99", name = "Documentaire"),
                Genre(id = "18", name = "Drame"),
                Genre(id = "10751", name = "Famille"),
                Genre(id = "14", name = "Fantaisie"),
                Genre(id = "27", name = "Horreur"),
                Genre(id = "10402", name = "Musique"),
                Genre(id = "9648", name = "Mystère"),
                Genre(id = "10749", name = "Romance"),
                Genre(id = "878", name = "Science-Fiction"),
                Genre(id = "53", name = "Thriller"),
                Genre(id = "10752", name = "Guerre"),
                Genre(id = "37", name = "Western"),
            )
        }
        return runCatching {
            val cleanQuery = TitleNormalizer.cleanForTmdbSearch(query).ifBlank { query }
            TMDb3.Search.multi(cleanQuery, language = language, page = page).results.mapNotNull { item ->
                when (item) {
                    is TMDb3.Movie -> mapMovie(item)
                    is TMDb3.Tv -> mapTv(item)
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Détails — TMDB (copie conforme de CloudstreamProvider)
    // ══════════════════════════════════════════════════════════════════════

    override suspend fun getMovie(id: String): Movie {
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

    override suspend fun getTvShow(id: String): TvShow {
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

    override suspend fun getGenre(id: String, page: Int): Genre {
        val originCountry = when (id.lowercase()) {
            "k-drama", "drama-coreen" -> "KR"
            else -> null
        }
        return runCatching {
            val tmdbGenreId = id.toIntOrNull()
            val withOrigin: TMDb3.Params.WithBuilder<String>? = originCountry?.let {
                TMDb3.Params.WithBuilder(it)
            }
            val moviesD = coroutineScope {
                async {
                    TMDb3.Discover.movie(
                        page = page, language = language,
                        sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                        withGenres = tmdbGenreId?.let { TMDb3.Params.WithBuilder(TMDb3.Genre.Movie.entries.find { g -> g.id == it } ?: return@async emptyList()) },
                        withOriginCountry = withOrigin,
                        watchRegion = "FR",
                        withWatchProviders = if (originCountry == null) nmWatchProviders() else null,
                        withOriginalLanguage = if (originCountry == null) nmOriginalLanguageBuilder() else null,
                    ).results.map(mapMovie)
                }
            }
            val tvD = coroutineScope {
                async {
                    TMDb3.Discover.tv(
                        page = page, language = language,
                        sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                        withGenres = tmdbGenreId?.let { TMDb3.Params.WithBuilder(TMDb3.Genre.Tv.entries.find { g -> g.id == it } ?: return@async emptyList()) },
                        withOriginCountry = withOrigin,
                        watchRegion = "FR",
                        withWatchProviders = if (originCountry == null) nmWatchProviders() else null,
                        withOriginalLanguage = if (originCountry == null) nmOriginalLanguageBuilder() else null,
                    ).results.map(mapTv)
                }
            }
            val movies = moviesD.await()
            val tvShows = tvD.await()
            val genreName = TMDb3.Genre.Movie.entries.find { it.id == tmdbGenreId }?.name
                ?: originCountry ?: id
            Genre(id = id, name = genreName, shows = movies + tvShows)
        }.getOrElse { Genre(id = id, name = id) }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val personId = id.toIntOrNull() ?: return People(id = id, name = "")
        return runCatching {
            val p = TMDb3.People.details(personId = personId, language = language)
            People(
                id = p.id.toString(),
                name = p.name,
                image = p.profilePath?.w500,
            )
        }.getOrElse { People(id = id, name = "") }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Lecture — getServers / getVideo via NetMirror backend
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Pour un contenu TMDB, cherche sur toutes les plateformes OTT du backend
     * NetMirror et retourne un serveur par plateforme trouvée.
     *
     * Format du server.src : "nm::<ott>::<netmirrorId>[::s<season>::e<episode>]"
     */
    /** Parse l'ID TMDB + saison/épisode depuis l'id et le videoType. */
    private data class NmIds(
        val tmdbId: String,
        val title: String,
        /** Titre original (EN) résolu via TMDB — fallback si le titre FR ne matche pas. */
        val originalTitle: String?,
        val year: Int?,
        val seasonNum: Int?,
        val episodeNum: Int?,
    )

    private fun parseNmIds(id: String, videoType: Video.Type): NmIds {
        return when (videoType) {
            is Video.Type.Movie -> NmIds(
                tmdbId = id,
                title = videoType.title,
                originalTitle = null, // résolu après via resolveOriginalTitle()
                year = videoType.releaseDate.take(4).toIntOrNull(),
                seasonNum = null,
                episodeNum = null,
            )
            is Video.Type.Episode -> NmIds(
                tmdbId = id.substringBefore(":"),
                title = videoType.tvShow.title,
                originalTitle = null, // résolu après via resolveOriginalTitle()
                year = videoType.tvShow.releaseDate?.take(4)?.toIntOrNull(),
                seasonNum = videoType.season.number,
                episodeNum = videoType.number,
            )
        }
    }

    /**
     * Résout le titre original (EN) via TMDB pour le fallback de recherche.
     * Le backend NetMirror stocke les titres en anglais — si on cherche avec
     * le titre français (ex: "Ali Baba et les 40 voleurs") on ne trouve rien.
     */
    private suspend fun resolveOriginalTitle(ids: NmIds): NmIds {
        val tmdbIdInt = ids.tmdbId.toIntOrNull() ?: return ids
        return try {
            val originalTitle = if (ids.seasonNum != null) {
                // Série → originalName
                TMDb3.TvSeries.details(seriesId = tmdbIdInt).originalName
            } else {
                // Film → originalTitle
                TMDb3.Movies.details(movieId = tmdbIdInt).originalTitle
            }
            if (originalTitle.isNotBlank() && normalizeTitle(originalTitle) != normalizeTitle(ids.title)) {
                ids.copy(originalTitle = originalTitle)
            } else {
                ids // même titre FR/EN, pas besoin de fallback
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveOriginalTitle failed: ${e.message}")
            ids
        }
    }

    // ── Serveur natif par plateforme OTT (indépendant) ────────────────

    /**
     * Cherche + crée le serveur pour UNE plateforme OTT.
     * Retourne null si pas de résultat ou erreur.
     * Chaque plateforme est isolée : un échec Netflix ne bloque pas Prime.
     */
    /**
     * Recherche un titre sur le backend NetMirror pour une plateforme donnée.
     * Retourne l'ID NetMirror ou null si pas trouvé.
     */
    private suspend fun searchOnPlatform(
        platform: OttPlatform,
        searchTitle: String,
    ): String? = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis() / 1000
        val cookies = buildCookies(platform)
        val url = "$MAIN_URL${platform.searchPath}?s=${java.net.URLEncoder.encode(searchTitle, "UTF-8")}&t=$ts"
        val req = Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "$MAIN_URL/")
            .build()

        val resp = httpClientFollowRedirects.newCall(req).execute()
        resp.use { r ->
            if (!r.isSuccessful) return@withContext null
            val body = r.body?.string() ?: return@withContext null
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
            val searchResults = json.optJSONArray("searchResult") ?: return@withContext null

            val normalizedSearch = normalizeTitle(searchTitle)
            var foundId: String? = null
            for (i in 0 until searchResults.length()) {
                val item = searchResults.optJSONObject(i) ?: continue
                val resultTitle = item.optString("t")
                val resultId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val normalizedResult = normalizeTitle(resultTitle)
                if (normalizedResult == normalizedSearch ||
                    normalizedResult.contains(normalizedSearch) ||
                    normalizedSearch.contains(normalizedResult)
                ) {
                    foundId = resultId
                    break
                }
            }
            // Fallback : prendre le 1er si unique
            if (foundId == null && searchResults.length() == 1) {
                foundId = searchResults.optJSONObject(0)?.optString("id")
                    ?.takeIf { it.isNotBlank() }
            }
            if (foundId != null) {
                Log.d(TAG, "searchOnPlatform ${platform.label} '$searchTitle' → ID $foundId")
            }
            foundId
        }
    }

    /** Infos structurées d'une langue dispo sur le backend. */
    private data class LangInfo(val code: String, val label: String, val langId: String?)

    /**
     * Cherche + crée les serveurs pour UNE plateforme OTT.
     * Retourne une liste : si le FR est dispo on crée un serveur [VF] dédié
     * qui sera auto-play (trié en premier par orderByFrenchBuckets).
     * Les autres langues restent accessibles en serveurs secondaires.
     */
    private suspend fun fetchServersForPlatform(
        platform: OttPlatform,
        ids: NmIds,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        try {
            // 1) Recherche sur cette plateforme — titre FR d'abord, puis fallback titre original EN
            val cacheKey = normalizeTitle(ids.title) + (ids.year?.let { "_$it" } ?: "")
            val cachedAll = idCache[cacheKey]
            val netmirrorId = cachedAll?.get(platform) ?: run {
                // Essai 1 : titre français (TMDB fr-FR)
                var foundId = searchOnPlatform(platform, ids.title)
                // Essai 2 : titre original anglais (fallback si FR ne matche pas)
                if (foundId == null && ids.originalTitle != null) {
                    Log.d(TAG, "${platform.label} : titre FR '${ids.title}' non trouvé, essai EN '${ids.originalTitle}'")
                    foundId = searchOnPlatform(platform, ids.originalTitle)
                }
                foundId
            } ?: return@withContext emptyList()

            // 2) Langues disponibles (structurées)
            val langInfos = withTimeoutOrNull(5_000) {
                fetchLanguageInfos(platform, netmirrorId)
            } ?: emptyList()

            // 3) Construire les serveurs — un PAR LANGUE, VF en premier
            val baseSrc = buildString {
                append("nm::${platform.ottCookie}::$netmirrorId")
                if (ids.seasonNum != null && ids.episodeNum != null) {
                    append("::s${ids.seasonNum}::e${ids.episodeNum}")
                }
            }

            if (langInfos.isEmpty()) {
                // Pas de détail langue → serveur unique neutre
                return@withContext listOf(Video.Server(
                    id = "netmirror_${platform.ottCookie}_$netmirrorId",
                    name = "NetMirror ${platform.label}",
                    src = baseSrc,
                ))
            }

            // 2026-05-22 : on ne crée des serveurs QUE pour VF et VOSTFR.
            // Les langues étrangères (EN, HI, JA…) → UN SEUL serveur [VO]
            // en fallback, uniquement si aucun VF/VOSTFR n'existe.
            // Évite de polluer la liste avec 5x "Netflix [VO]".
            val frLangs = langInfos.filter { it.label == "VF" || it.label == "VOSTFR" }
            val servers = mutableListOf<Video.Server>()

            if (frLangs.isNotEmpty()) {
                // On a du FR → un serveur par piste FR (VF en premier)
                frLangs.sortedBy { if (it.label == "VF") 0 else 1 }.forEach { lang ->
                    val langSrc = if (lang.langId != null) "$baseSrc::lang${lang.langId}" else baseSrc
                    servers.add(Video.Server(
                        id = "netmirror_${platform.ottCookie}_${netmirrorId}_${lang.code}",
                        name = "NetMirror ${platform.label} [${lang.label}]",
                        src = langSrc,
                    ))
                }
            } else {
                // Pas de FR → UN SEUL serveur [VO] en dernier recours
                // (prend la 1re langue dispo pour le langId)
                val fallbackLang = langInfos.firstOrNull()
                val langSrc = if (fallbackLang?.langId != null) "$baseSrc::lang${fallbackLang.langId}" else baseSrc
                val allLangs = langInfos.map { it.label }.distinct().joinToString(", ")
                servers.add(Video.Server(
                    id = "netmirror_${platform.ottCookie}_${netmirrorId}_vo",
                    name = "NetMirror ${platform.label} [VO] ($allLangs)",
                    src = langSrc,
                ))
            }
            servers
        } catch (e: Exception) {
            Log.w(TAG, "fetchServersForPlatform ${platform.label} failed: ${e.message}")
            emptyList()
        }
    }

    /** Batch : récupère TOUTES les plateformes en parallèle (fallback pour getServers batch). */
    private suspend fun fetchNativeNetMirrorServers(
        ids: NmIds,
    ): List<Video.Server> = coroutineScope {
        OttPlatform.entries.map { platform ->
            async { fetchServersForPlatform(platform, ids) }
        }.awaitAll().flatten()
    }

    // ── Backups (Cloudstream, Movix, Moviebox, Papa, Coflix) ───────────

    private suspend fun fetchNetMirrorBackups(
        ids: NmIds,
        videoType: Video.Type,
    ): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        val tmdbIdInt = ids.tmdbId.toIntOrNull()

        // 1) Cloudstream backup — MovieBox+ via /resource (bons streams MP4)
        try {
            val csId = when (videoType) {
                is Video.Type.Movie -> ids.tmdbId
                is Video.Type.Episode -> "${ids.tmdbId}:${ids.seasonNum}:${ids.episodeNum}"
            }
            val cs = CloudstreamProvider.getServers(csId, videoType)
            if (cs.isNotEmpty()) {
                Log.d(TAG, "+ Cloudstream backup : ${cs.size} sources")
                servers.addAll(cs.map { it.copy(
                    id = "nm_cs__${it.id}",
                    name = "Cloudstream — ${it.name}",
                )})
            }
        } catch (e: Exception) {
            Log.d(TAG, "Cloudstream backup failed: ${e.message}")
        }

        // 2) Movix backup — multi-hosters (Filemoon, Doodstream, VOE, etc.)
        try {
            val movixId = when (videoType) {
                is Video.Type.Movie -> ids.tmdbId
                is Video.Type.Episode -> "${ids.tmdbId}-${ids.seasonNum}-${ids.episodeNum}"
            }
            // Anti-récursion : dire à Movix de ne pas appeler ses propres backups
            MovixProvider.skipBackupsForBackupCall = true
            try {
                val mx = MovixProvider.getServers(movixId, videoType)
                if (mx.isNotEmpty()) {
                    Log.d(TAG, "+ Movix backup : ${mx.size} sources")
                    servers.addAll(mx.map { it.copy(
                        id = "nm_movix__${it.id}",
                        name = it.name,
                    )})
                }
            } finally {
                MovixProvider.skipBackupsForBackupCall = false
            }
        } catch (e: Exception) {
            Log.d(TAG, "Movix backup failed: ${e.message}")
        }

        // 3) Moviebox backup — recherche par TMDB ID
        if (tmdbIdInt != null) {
            try {
                val mb = MovieboxProvider.getMovieboxSourcesByTmdbId(tmdbIdInt, videoType)
                if (mb.isNotEmpty()) {
                    Log.d(TAG, "+ Moviebox backup : ${mb.size} sources")
                    servers.addAll(mb.map { it.copy(
                        id = "nm_mb__${it.id}",
                        name = "Moviebox — ${it.name}",
                    )})
                }
            } catch (e: Exception) {
                Log.d(TAG, "Moviebox backup failed: ${e.message}")
            }
        }

        // 4) Papadustream — captcha CF, dernier recours
        try {
            val papa = PapadustreamProvider.getPapaSourcesByTmdbId(ids.tmdbId, videoType)
            if (papa.isNotEmpty()) {
                Log.d(TAG, "+ Papa backup : ${papa.size} sources")
                servers.addAll(papa.map { it.copy(
                    id = "nm_papa__${it.id}",
                    name = "Papa — ${it.name}",
                )})
            }
        } catch (e: Exception) {
            Log.d(TAG, "Papa backup failed: ${e.message}")
        }

        // 5) Coflix backup — multi-hosters FR
        if (tmdbIdInt != null) {
            try {
                val (title, year) = when (videoType) {
                    is Video.Type.Movie -> {
                        val det = TMDb3.Movies.details(movieId = tmdbIdInt, language = "fr-FR")
                        (det.title.takeIf { it.isNotBlank() } ?: det.originalTitle) to
                            det.releaseDate?.take(4)?.toIntOrNull()
                    }
                    is Video.Type.Episode -> {
                        val det = TMDb3.TvSeries.details(seriesId = tmdbIdInt, language = "fr-FR")
                        (det.name.takeIf { it.isNotBlank() } ?: det.originalName) to
                            det.firstAirDate?.take(4)?.toIntOrNull()
                    }
                }
                val coflix = when (videoType) {
                    is Video.Type.Movie -> CoflixSourceProvider.getMovieSources(title, year)
                    is Video.Type.Episode ->
                        CoflixSourceProvider.getEpisodeSources(title, year, ids.seasonNum ?: 1, ids.episodeNum ?: 1)
                }
                if (coflix.isNotEmpty()) {
                    Log.d(TAG, "+ Coflix backup : ${coflix.size} sources")
                    servers.addAll(coflix.map { it.copy(
                        id = "nm_coflix__${it.id}",
                        name = "Coflix — ${it.name}",
                    )})
                }
            } catch (e: Exception) {
                Log.d(TAG, "Coflix backup failed: ${e.message}")
            }
        }

        // 6) FrenchStream backup — recherche par titre FR, gros catalogue FR
        try {
            val frTitle = ids.title
            val fsResults = FrenchStreamProvider.search(frTitle, 1)
            val bestFs = fsResults.firstOrNull { item ->
                val t = when (item) {
                    is Movie -> item.title
                    is TvShow -> item.title
                    else -> ""
                }
                t.equals(frTitle, ignoreCase = true)
                    || t.contains(frTitle, ignoreCase = true)
                    || frTitle.contains(t, ignoreCase = true)
            } ?: fsResults.firstOrNull()
            if (bestFs != null) {
                val fsId = when (bestFs) { is Movie -> bestFs.id; is TvShow -> bestFs.id; else -> null }
                if (fsId != null) {
                    val fs = FrenchStreamProvider.getServers(
                        when (videoType) {
                            is Video.Type.Movie -> fsId
                            is Video.Type.Episode -> "$fsId/${ids.episodeNum ?: 1}"
                        },
                        videoType,
                    )
                    if (fs.isNotEmpty()) {
                        Log.d(TAG, "+ FrenchStream backup : ${fs.size} sources")
                        servers.addAll(fs.map { it.copy(
                            id = "nm_fs__${it.id}",
                            name = "FrenchStream — ${it.name}",
                        )})
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "FrenchStream backup failed: ${e.message}")
        }

        // 7) Wiflix backup — recherche par titre FR, catalogue FR
        try {
            val wfTitle = ids.title
            val wfResults = WiflixProvider.search(wfTitle, 1)
            val bestWf = wfResults.firstOrNull { item ->
                val t = when (item) {
                    is Movie -> item.title
                    is TvShow -> item.title
                    else -> ""
                }
                t.equals(wfTitle, ignoreCase = true)
                    || t.contains(wfTitle, ignoreCase = true)
                    || wfTitle.contains(t, ignoreCase = true)
            } ?: wfResults.firstOrNull()
            if (bestWf != null) {
                val wfId = when (bestWf) { is Movie -> bestWf.id; is TvShow -> bestWf.id; else -> null }
                if (wfId != null) {
                    val wf = WiflixProvider.getServers(
                        when (videoType) {
                            is Video.Type.Movie -> wfId
                            is Video.Type.Episode -> "$wfId/${ids.episodeNum ?: 1}"
                        },
                        videoType,
                    )
                    if (wf.isNotEmpty()) {
                        Log.d(TAG, "+ Wiflix backup : ${wf.size} sources")
                        servers.addAll(wf.map { it.copy(
                            id = "nm_wf__${it.id}",
                            name = "Wiflix — ${it.name}",
                        )})
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Wiflix backup failed: ${e.message}")
        }

        return servers
    }

    // ── getServers batch (fallback) ───────────────────────────────────

    /** Backups désactivés le temps de régler les serveurs NetMirror natifs.
     *  Passer à true pour activer Cloudstream/Movix/Moviebox/Papa/Coflix. */
    private const val BACKUPS_ENABLED = true

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = coroutineScope {
        val ids = resolveOriginalTitle(parseNmIds(id, videoType))
        Log.d(TAG, "getServers '${ids.title}' (original: '${ids.originalTitle ?: "="}') tmdb=${ids.tmdbId}")
        val native = fetchNativeNetMirrorServers(ids)
        val backups = if (BACKUPS_ENABLED) fetchNetMirrorBackups(ids, videoType) else emptyList()
        val all = native + backups
        Log.d(TAG, "getServers '${ids.title}' → ${native.size} natifs + ${backups.size} backups = ${all.size}")
        all
    }

    // ── getServersProgressive (affichage au fur et à mesure) ──────────

    override fun getServersProgressive(
        id: String,
        videoType: Video.Type,
    ): Flow<List<Video.Server>> = channelFlow {
        val ids = resolveOriginalTitle(parseNmIds(id, videoType))
        Log.d(TAG, "getServersProgressive '${ids.title}' (original: '${ids.originalTitle ?: "="}') tmdb=${ids.tmdbId}")
        // Chaque plateforme OTT émet ses serveurs INDÉPENDAMMENT
        // → si Netflix timeout, Prime/Disney+ arrivent quand même
        // → serveurs VF en premier dans chaque lot (tri interne à fetchServersForPlatform)
        for (platform in OttPlatform.entries) {
            launch {
                try {
                    val servers = fetchServersForPlatform(platform, ids)
                    if (servers.isNotEmpty()) send(servers)
                } catch (e: Exception) {
                    Log.w(TAG, "Progressive ${platform.label} failed: ${e.message}")
                }
            }
        }
        // Backups PARALLÈLES — chaque backup émet son lot dès qu'il est prêt
        // (au lieu d'un fetchNetMirrorBackups séquentiel qui attend TOUS les 7)
        if (BACKUPS_ENABLED) {
            val tmdbIdInt = ids.tmdbId.toIntOrNull()

            // 1) Cloudstream — rapide (cache fréquent)
            launch {
                try {
                    val csId = when (videoType) {
                        is Video.Type.Movie -> ids.tmdbId
                        is Video.Type.Episode -> "${ids.tmdbId}:${ids.seasonNum}:${ids.episodeNum}"
                    }
                    val cs = CloudstreamProvider.getServers(csId, videoType)
                    if (cs.isNotEmpty()) {
                        Log.d(TAG, "+ Cloudstream backup (prog) : ${cs.size} sources")
                        send(cs.map { it.copy(id = "nm_cs__${it.id}", name = "Cloudstream — ${it.name}") })
                    }
                } catch (e: Exception) { Log.d(TAG, "Prog Cloudstream failed: ${e.message}") }
            }

            // 2) Movix — rapide (cache fréquent)
            launch {
                try {
                    val movixId = when (videoType) {
                        is Video.Type.Movie -> ids.tmdbId
                        is Video.Type.Episode -> "${ids.tmdbId}-${ids.seasonNum}-${ids.episodeNum}"
                    }
                    MovixProvider.skipBackupsForBackupCall = true
                    try {
                        val mx = MovixProvider.getServers(movixId, videoType)
                        if (mx.isNotEmpty()) {
                            Log.d(TAG, "+ Movix backup (prog) : ${mx.size} sources")
                            send(mx.map { it.copy(id = "nm_movix__${it.id}") })
                        }
                    } finally { MovixProvider.skipBackupsForBackupCall = false }
                } catch (e: Exception) { Log.d(TAG, "Prog Movix failed: ${e.message}") }
            }

            // 3) Moviebox
            if (tmdbIdInt != null) launch {
                try {
                    val mb = MovieboxProvider.getMovieboxSourcesByTmdbId(tmdbIdInt, videoType)
                    if (mb.isNotEmpty()) {
                        Log.d(TAG, "+ Moviebox backup (prog) : ${mb.size} sources")
                        send(mb.map { it.copy(id = "nm_mb__${it.id}", name = "Moviebox — ${it.name}") })
                    }
                } catch (e: Exception) { Log.d(TAG, "Prog Moviebox failed: ${e.message}") }
            }

            // 4) Papa — captcha, lent
            launch {
                try {
                    val papa = PapadustreamProvider.getPapaSourcesByTmdbId(ids.tmdbId, videoType)
                    if (papa.isNotEmpty()) {
                        Log.d(TAG, "+ Papa backup (prog) : ${papa.size} sources")
                        send(papa.map { it.copy(id = "nm_papa__${it.id}", name = "Papa — ${it.name}") })
                    }
                } catch (e: Exception) { Log.d(TAG, "Prog Papa failed: ${e.message}") }
            }

            // 5) Coflix
            if (tmdbIdInt != null) launch {
                try {
                    val (title, year) = when (videoType) {
                        is Video.Type.Movie -> {
                            val det = TMDb3.Movies.details(movieId = tmdbIdInt, language = "fr-FR")
                            (det.title.takeIf { it.isNotBlank() } ?: det.originalTitle) to
                                det.releaseDate?.take(4)?.toIntOrNull()
                        }
                        is Video.Type.Episode -> {
                            val det = TMDb3.TvSeries.details(seriesId = tmdbIdInt, language = "fr-FR")
                            (det.name.takeIf { it.isNotBlank() } ?: det.originalName) to
                                det.firstAirDate?.take(4)?.toIntOrNull()
                        }
                    }
                    val coflix = when (videoType) {
                        is Video.Type.Movie -> CoflixSourceProvider.getMovieSources(title, year)
                        is Video.Type.Episode ->
                            CoflixSourceProvider.getEpisodeSources(title, year, ids.seasonNum ?: 1, ids.episodeNum ?: 1)
                    }
                    if (coflix.isNotEmpty()) {
                        Log.d(TAG, "+ Coflix backup (prog) : ${coflix.size} sources")
                        send(coflix.map { it.copy(id = "nm_coflix__${it.id}", name = "Coflix — ${it.name}") })
                    }
                } catch (e: Exception) { Log.d(TAG, "Prog Coflix failed: ${e.message}") }
            }

            // 6) FrenchStream — recherche + getServers
            launch {
                try {
                    val frTitle = ids.title
                    val fsResults = FrenchStreamProvider.search(frTitle, 1)
                    val bestFs = fsResults.firstOrNull { item ->
                        val t = when (item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }
                        t.equals(frTitle, ignoreCase = true) || t.contains(frTitle, ignoreCase = true) || frTitle.contains(t, ignoreCase = true)
                    } ?: fsResults.firstOrNull()
                    if (bestFs != null) {
                        val fsId = when (bestFs) { is Movie -> bestFs.id; is TvShow -> bestFs.id; else -> null }
                        if (fsId != null) {
                            val fs = FrenchStreamProvider.getServers(
                                when (videoType) { is Video.Type.Movie -> fsId; is Video.Type.Episode -> "$fsId/${ids.episodeNum ?: 1}" },
                                videoType,
                            )
                            if (fs.isNotEmpty()) {
                                Log.d(TAG, "+ FrenchStream backup (prog) : ${fs.size} sources")
                                send(fs.map { it.copy(id = "nm_fs__${it.id}", name = "FrenchStream — ${it.name}") })
                            }
                        }
                    }
                } catch (e: Exception) { Log.d(TAG, "Prog FrenchStream failed: ${e.message}") }
            }

            // 7) Wiflix — recherche + getServers
            launch {
                try {
                    val wfTitle = ids.title
                    val wfResults = WiflixProvider.search(wfTitle, 1)
                    val bestWf = wfResults.firstOrNull { item ->
                        val t = when (item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }
                        t.equals(wfTitle, ignoreCase = true) || t.contains(wfTitle, ignoreCase = true) || wfTitle.contains(t, ignoreCase = true)
                    } ?: wfResults.firstOrNull()
                    if (bestWf != null) {
                        val wfId = when (bestWf) { is Movie -> bestWf.id; is TvShow -> bestWf.id; else -> null }
                        if (wfId != null) {
                            val wf = WiflixProvider.getServers(
                                when (videoType) { is Video.Type.Movie -> wfId; is Video.Type.Episode -> "$wfId/${ids.episodeNum ?: 1}" },
                                videoType,
                            )
                            if (wf.isNotEmpty()) {
                                Log.d(TAG, "+ Wiflix backup (prog) : ${wf.size} sources")
                                send(wf.map { it.copy(id = "nm_wf__${it.id}", name = "Wiflix — ${it.name}") })
                            }
                        }
                    }
                } catch (e: Exception) { Log.d(TAG, "Prog Wiflix failed: ${e.message}") }
            }
        }
    }

    /**
     * Résout le lien M3U8 via le système NewTV API.
     *
     * 1. Parse le server.src pour extraire la plateforme OTT + l'ID NetMirror
     * 2. Résout l'URL de l'API NewTV (via mobidetect* → checknewtv.php)
     * 3. Appelle player.php?id=<contentId> avec le header Ott approprié
     * 4. Retourne le lien M3U8
     */
    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        // ── Délégation backup ──
        if (server.id.startsWith("nm_cs__")) {
            val original = server.copy(id = server.id.removePrefix("nm_cs__"))
            return@withContext try {
                CloudstreamProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "Cloudstream getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }
        if (server.id.startsWith("nm_movix__")) {
            val original = server.copy(id = server.id.removePrefix("nm_movix__"))
            return@withContext try {
                MovixProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "Movix getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }
        if (server.id.startsWith("nm_mb__")) {
            val original = server.copy(id = server.id.removePrefix("nm_mb__"))
            return@withContext try {
                MovieboxProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "Moviebox getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }
        if (server.id.startsWith("nm_papa__")) {
            val original = server.copy(id = server.id.removePrefix("nm_papa__"))
            return@withContext try {
                PapadustreamProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "Papa getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }
        if (server.id.startsWith("nm_coflix__")) {
            val original = server.copy(id = server.id.removePrefix("nm_coflix__"))
            return@withContext try {
                // Coflix servers contiennent directement l'URL embed dans src
                val extracted = Extractor.extract(original.src)
                extracted ?: Video(source = original.src)
            } catch (e: Exception) {
                Log.w(TAG, "Coflix getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }
        if (server.id.startsWith("nm_fs__")) {
            val original = server.copy(id = server.id.removePrefix("nm_fs__"))
            return@withContext try {
                FrenchStreamProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "FrenchStream getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }
        if (server.id.startsWith("nm_wf__")) {
            val original = server.copy(id = server.id.removePrefix("nm_wf__"))
            return@withContext try {
                WiflixProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "Wiflix getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }

        // ── Serveur natif NetMirror ──
        val parts = server.src.split("::")
        if (parts.size < 3 || parts[0] != "nm") {
            throw IllegalArgumentException("NetMirror: format src invalide: ${server.src}")
        }

        val ottCode = parts[1] // nf, pv, hs, dp
        val netmirrorId = parts[2]
        // Extraire season/episode et langId depuis le src encodé
        // Format : nm::ott::id[::sN::eN][::langXXX]
        var seasonNum: Int? = null
        var episodeNum: Int? = null
        var langId: String? = null
        for (p in parts.drop(3)) {
            when {
                p.startsWith("s") && p.drop(1).toIntOrNull() != null -> seasonNum = p.drop(1).toInt()
                p.startsWith("e") && p.drop(1).toIntOrNull() != null -> episodeNum = p.drop(1).toInt()
                p.startsWith("lang") -> langId = p.removePrefix("lang")
            }
        }

        // Construire l'ID de contenu pour le player
        val contentId = if (seasonNum != null && episodeNum != null) {
            // Pour les séries : d'abord récupérer l'ID de l'épisode via le backend
            val episodeId = resolveEpisodeId(ottCode, netmirrorId, seasonNum, episodeNum)
            episodeId ?: netmirrorId
        } else {
            netmirrorId
        }

        // Résoudre l'URL de l'API NewTV
        val apiBase = resolveApiUrl()

        val cookies = ensureCookie()
        // Si langId est dispo, on le passe au player pour forcer la piste audio
        val langParam = if (langId != null) "&lang=$langId" else ""
        val playerUrl = "$apiBase/newtv/player.php?id=$contentId$langParam"
        val langCookie = if (langId != null) "; lang=$langId" else ""
        val req = Request.Builder()
            .url(playerUrl)
            .header("User-Agent", "Mozilla/5.0")
            .header("Ott", ottCode)
            .header("Usertoken", "")
            .header("Cookie", "$cookies; ott=$ottCode; hd=on$langCookie")
            .header("Referer", "$MAIN_URL/")
            .build()

        val resp = httpClientFollowRedirects.newCall(req).execute()
        resp.use { r ->
            if (!r.isSuccessful) {
                throw IllegalStateException("NetMirror player.php returned ${r.code}")
            }
            val body = r.body?.string()
                ?: throw IllegalStateException("NetMirror player.php empty body")
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IllegalStateException("NetMirror player.php invalid JSON")

            val status = json.optString("status")
            if (status != "ok") {
                throw IllegalStateException("NetMirror player.php status=$status")
            }

            val videoLink = json.optString("video_link").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("NetMirror: no video_link in response")
            val referer = json.optString("referer").takeIf { it.isNotBlank() }

            Log.d(TAG, "getVideo OK: ${videoLink.take(80)}…")

            Video(
                source = videoLink,
                headers = buildMap {
                    put("User-Agent", "Mozilla/5.0")
                    put("Cookie", "hd=on")
                    if (referer != null) put("Referer", referer)
                },
            )
        }
    }

    /**
     * Résout l'ID d'un épisode spécifique via le backend NetMirror.
     *
     * L'API post.php retourne les épisodes dans un tableau plat avec le champ
     * "s" = "S1"/"S2"/etc. et "ep" = "E1"/"E2"/etc. Chaque entrée a un "id"
     * qui est l'ID de contenu à passer à player.php.
     *
     * Si post.php n'a pas les épisodes (certains providers les paginent via
     * episodes.php), on tombe en fallback sur episodes.php.
     */
    private suspend fun resolveEpisodeId(
        ottCode: String,
        showId: String,
        seasonNum: Int,
        episodeNum: Int,
    ): String? = withContext(Dispatchers.IO) {
        val platform = OttPlatform.entries.find { it.ottCookie == ottCode } ?: return@withContext null
        try {
            val cookies = buildCookies(platform)
            val ts = System.currentTimeMillis() / 1000

            // 1) post.php — contient souvent TOUS les épisodes en tableau plat
            val postUrl = "$MAIN_URL${platform.postPath}?id=$showId&t=$ts"
            val postReq = Request.Builder()
                .url(postUrl)
                .header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "$MAIN_URL/")
                .build()

            val postResp = httpClientFollowRedirects.newCall(postReq).execute()
            val postBody = postResp.use { it.body?.string() } ?: return@withContext null
            val postJson = runCatching { JSONObject(postBody) }.getOrNull() ?: return@withContext null

            // Les épisodes sont un tableau plat : [{id, t, s:"S3", ep:"E1", time}, ...]
            val episodes = postJson.optJSONArray("episodes")
            if (episodes != null) {
                val targetS = "S$seasonNum"
                val targetE = "E$episodeNum"
                for (i in 0 until episodes.length()) {
                    val ep = episodes.optJSONObject(i) ?: continue
                    val s = ep.optString("s", "").uppercase()
                    val e = ep.optString("ep", "").uppercase()
                    if (s == targetS && e == targetE) {
                        val id = ep.optString("id").takeIf { it.isNotBlank() }
                        if (id != null) {
                            Log.d(TAG, "resolveEpisodeId via post.php: $targetS$targetE → $id")
                            return@withContext id
                        }
                    }
                }
                // Fallback : parfois ep="1" au lieu de "E1"
                for (i in 0 until episodes.length()) {
                    val ep = episodes.optJSONObject(i) ?: continue
                    val s = ep.optString("s", "").uppercase().removePrefix("S")
                    val e = ep.optString("ep", "").uppercase().removePrefix("E")
                    if (s == seasonNum.toString() && e == episodeNum.toString()) {
                        val id = ep.optString("id").takeIf { it.isNotBlank() }
                        if (id != null) {
                            Log.d(TAG, "resolveEpisodeId via post.php (fallback): S${seasonNum}E${episodeNum} → $id")
                            return@withContext id
                        }
                    }
                }
            }

            // 2) Fallback : episodes.php (paginé, pour les providers qui séparent)
            val seasonData = postJson.optJSONArray("season")
            if (seasonData != null) {
                for (i in 0 until seasonData.length()) {
                    val s = seasonData.optJSONObject(i) ?: continue
                    val sNum = s.optString("s", "").uppercase().removePrefix("S")
                    if (sNum == seasonNum.toString()) {
                        val seasonId = s.optString("id").takeIf { it.isNotBlank() } ?: continue
                        // Paginer episodes.php
                        var page = 1
                        while (page <= 10) {
                            val epUrl = "$MAIN_URL${platform.episodesPath}?s=$seasonId&series=$showId&t=$ts&page=$page"
                            val epReq = Request.Builder()
                                .url(epUrl)
                                .header("Cookie", cookies)
                                .header("User-Agent", "Mozilla/5.0")
                                .header("Referer", "$MAIN_URL/")
                                .build()
                            val epResp = httpClientFollowRedirects.newCall(epReq).execute()
                            val epBody = epResp.use { it.body?.string() } ?: break
                            val epJson = runCatching { JSONObject(epBody) }.getOrNull() ?: break
                            val epArr = epJson.optJSONArray("episodes") ?: break
                            for (j in 0 until epArr.length()) {
                                val ep = epArr.optJSONObject(j) ?: continue
                                val eNum = ep.optString("ep", "").uppercase().removePrefix("E")
                                if (eNum == episodeNum.toString()) {
                                    return@withContext ep.optString("id").takeIf { it.isNotBlank() }
                                }
                            }
                            val next = epJson.optString("nextPageShow")
                            if (next.isBlank() || next == "null") break
                            page++
                        }
                        break
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "resolveEpisodeId error: ${e.message}")
            null
        }
    }

    /**
     * Récupère les langues audio disponibles pour un contenu via post.php.
     * Retourne une liste structurée [LangInfo] avec le code ISO, le label
     * lisible (VF/EN/etc.) et l'ID de langue du backend (pour forcer l'audio).
     */
    private suspend fun fetchLanguageInfos(
        platform: OttPlatform,
        contentId: String,
    ): List<LangInfo> = withContext(Dispatchers.IO) {
        try {
            val cookies = buildCookies(platform)
            val ts = System.currentTimeMillis() / 1000
            val url = "$MAIN_URL${platform.postPath}?id=$contentId&t=$ts"
            val req = Request.Builder()
                .url(url)
                .header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "$MAIN_URL/")
                .build()

            val resp = httpClientFollowRedirects.newCall(req).execute()
            val body = resp.use { it.body?.string() } ?: return@withContext emptyList()
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyList()
            val langArr = json.optJSONArray("lang") ?: return@withContext emptyList()

            val result = mutableListOf<LangInfo>()
            for (i in 0 until langArr.length()) {
                val l = langArr.optJSONObject(i) ?: continue
                val code = l.optString("s", "").take(3).uppercase()
                val langId = l.optString("id").takeIf { it.isNotBlank() }
                    ?: l.optString("lang_id").takeIf { it.isNotBlank() }
                val label = when (code) {
                    "FRA" -> "VF"
                    "ENG" -> "EN"
                    "HIN" -> "HI"
                    "JPN" -> "JA"
                    "SPA" -> "ES"
                    "DEU" -> "DE"
                    "ITA" -> "IT"
                    "KOR" -> "KO"
                    "TAM" -> "TA"
                    "TEL" -> "TE"
                    else -> code.takeIf { it.isNotBlank() } ?: continue
                }
                result.add(LangInfo(code = code, label = label, langId = langId))
            }
            result.distinctBy { it.code }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
