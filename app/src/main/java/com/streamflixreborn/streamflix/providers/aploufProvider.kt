package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.coroutineScope
import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody
import org.jsoup.nodes.Element
import retrofit2.Response
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

object aploufProvider : Provider, ProviderPortalUrl, ProviderConfigUrl {

    override val name = "aplouf"

    override val defaultPortalUrl: String = "https://www.aplouf.com/zaxd03o2n0gfpub/home/aplouf"
    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty{ field }
        }

    // Fallback portal : kidraz.com (même backend, fusionné 2026-05-21)
    private const val FALLBACK_PORTAL_URL = "http://chezlesducs.free.fr/films.php"

    override val defaultBaseUrl: String = "https://www.aplouf.com/zaxd03o2n0gfpub/home/aplouf"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty{ field }
        }

    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_aplouf"

    const val user_agent = "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:147.0) Gecko/20100101 Firefox/147.0"

    private var homePath = ""
    private var moviePath = ""

    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()
    
    data class FilmResponse(
        val films: List<KidrazFilm>,
        val total: Int? = null,
        val hasMore: Boolean? = null
    )

    data class KidrazFilm(
        val id: String,
        val title: String,
        val poster: String?,
        val link: String?,
        val cat: String? = null,
        val hd: Boolean? = null
    )

    override suspend fun getHome(): List<Category> = coroutineScope {
        initializeService()
        val doc = service.loadPage(homePath)

        // 1. Sections
        val categories = mutableListOf<Category>()

        // 2. Dynamic Section Discovery via content-row identification
        val carousels = doc.select(".trend-row, .showcase, .film-grid, .newfilms-wrap, .newfilms-section")
        
        carousels.forEach { rowContainer ->
            // 1. Find items: inside or nearby
            var items = rowContainer.select(".showcase-card, .film-card, .trend-card, a.trend-card")
            if (items.isEmpty()) {
                rowContainer.nextElementSibling()?.let { nextSib ->
                    items = nextSib.select(".showcase-card, .film-card, .trend-card, a.trend-card")
                }
            }
            if (items.isEmpty()) return@forEach

            var titleText = ""
            rowContainer.selectFirst(".trend-vignette, .vignette")?.let { vignette ->
                val vTitle = vignette.selectFirst(".trend-vignette-title, .vignette-title")
                val vCount = vignette.selectFirst(".trend-vignette-count, .vignette-count")
                if (vTitle != null) {
                    titleText = vTitle.text().trim()
                    if (vCount != null) {
                        titleText += " " + vCount.text().trim()
                    }
                }
            }

            if (titleText.isEmpty()) {
                var curr: Element? = rowContainer
                while (curr != null && titleText.isEmpty()) {
                    var prev = curr.previousElementSibling()
                    while (prev != null) {
                        val hEl = prev.selectFirst("h2, h3, h4") ?: if (prev.tagName() in listOf("h2", "h3", "h4")) prev else null
                        if (hEl != null) {
                            titleText = hEl.text().trim()
                            break
                        }
                        val tEl = prev.selectFirst(".section-header, .title, .newfilms-header")
                        if (tEl != null) {
                            titleText = tEl.text().trim()
                            break
                        }
                        prev = prev.previousElementSibling()
                    }
                    if (titleText.isNotEmpty()) break
                    curr = curr.parent()
                    if (curr == null || curr.tagName().equals("body", true)) break
                }
            }

            if (titleText.isEmpty()) return@forEach

            var cleanKey = titleText
                .replace(Regex("""(?i)\s*\+\d+$"""), "")
                .replace(Regex("""(?i)tout voir|voir tout"""), "")
                .trim()


            val categoryTitle = if (cleanKey.isNotEmpty()) cleanKey else null
            if (categoryTitle == null) return@forEach

            if (categories.none { it.name.equals(categoryTitle, true) }) {
                val movies = items.mapNotNull { item ->
                    val a = if (item.tagName() == "a") item else item.selectFirst("a")
                    val img = item.selectFirst("img") ?: return@mapNotNull null
                    val rawTitle = (item.selectFirst(".showcase-card-title, .film-card-title, .trend-card-title")?.text()?.trim() 
                        ?: img.attr("alt")?.trim() 
                        ?: "Unknown").toString()
                    
                    val titleMatch = Regex("""^(.*?)\s*\((\d{4})\)\s*$""").find(rawTitle)
                    val name = titleMatch?.groupValues?.getOrNull(1)?.trim() ?: rawTitle.replace(Regex("""\(\d{4}\)"""), "").trim()
                    val year = titleMatch?.groupValues?.getOrNull(2) ?: Regex("""(\d{4})""").find(rawTitle)?.groupValues?.getOrNull(1)
                    
                    Movie(
                        id = a?.attr("href") ?: "",
                        title = name,
                        released = year,
                        poster = img.attr("src")?.replace("/original/", "/w300/")
                    )
                }.distinctBy { it.id }
                
                if (movies.isNotEmpty()) {
                    categories.add(Category(categoryTitle, movies))
                }
            }
        }

        // Reorder: 1.FEATURED 2.Épisodes/récents 3.Séries récentes 4.Films récents 5.Séries 6.Films
        categories.sortedWith(compareBy { cat ->
            val n = cat.name.lowercase()
            val isRecent = n.contains("récen") || n.contains("nouveau") || n.contains("nouvelle") || n.contains("derni") || n.contains("ajouté")
            val isSeries = n.contains("séri") || n.contains("seri") || n.contains("saison") || n.contains("tv")
            val isFilm = n.contains("film") || n.contains("movie") || n.contains("cinéma")
            when {
                cat.name == Category.FEATURED -> 0
                n.contains("épisode") || n.contains("episode") -> 1
                isRecent && isSeries -> 2
                isRecent && isFilm -> 3
                isSeries -> 4
                isFilm -> 5
                isRecent -> 1
                else -> 6
            }
        })
    }
    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = coroutineScope {
        initializeService()

        if (query.isEmpty()) {
            val doc = service.loadPage(homePath)
            val genres = doc.select(".navbar-dropdown-grid a.navbar-dropdown-item").map { a ->
                val name = a.text().trim()
                val href = a.attr("href")
                val parts = href.substringAfter("/c/").split("/")
                val catid = if (parts.size >= 2) parts[1] else ""
                Genre(
                    name = name,
                    id = "$name|$catid"
                )
            }.toMutableList()

            // Ajouter K-Drama (recherche par mots-clés via GenreViewModel)
            if (genres.none { (it as? Genre)?.id?.contains("k-drama", ignoreCase = true) == true }) {
                genres.add(Genre(id = "k-drama", name = "K-Drama"))
            }

            return@coroutineScope genres
        }

        val apiBase = homePath.substringBefore("/home/")
        val folder = apiBase.removePrefix("/").substringBefore("/")
        val pr = homePath.substringAfterLast("/")

        val response = service.search(
            "$apiBase/api_search.php",
            query = query,
            offset = (page - 1) * 20,
            limit = 20,
            folder = folder,
            pr = pr
        )

        response.films.map { film ->
            val titleMatch = Regex("^(.*?)\\s*\\((\\d{4})\\)\\s*$").find(film.title.trim())
            val displayTitle = titleMatch?.groupValues?.getOrNull(1)?.trim() ?: film.title
            val year = titleMatch?.groupValues?.getOrNull(2)

            Movie(
                id = film.link ?: "",
                title = displayTitle,
                released = year,
                poster = film.poster?.replace("/original/", "/w300/"),
                quality = if (film.hd == true) "HD" else null
            )
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = coroutineScope {
        initializeService()

        val apiBase = homePath.substringBefore("/home/")
        val folder = apiBase.removePrefix("/").substringBefore("/")
        val pr = homePath.substringAfterLast("/")

        val response = service.apiFilms(
            "$apiBase/api_films.php",
            offset = (page - 1) * 20,
            limit = 20,
            folder = folder,
            pr = pr
        )

        response.films.map { film ->
            val titleMatch = Regex("^(.*?)\\s*\\((\\d{4})\\)\\s*$").find(film.title.trim())
            val displayTitle = titleMatch?.groupValues?.getOrNull(1)?.trim() ?: film.title
            val year = titleMatch?.groupValues?.getOrNull(2)

            Movie(
                id = film.link ?: "",
                title = displayTitle,
                released = year,
                poster = film.poster?.replace("/original/", "/w300/"),
                quality = if (film.hd == true) "HD" else null
            )
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()

    override suspend fun getMovie(id: String): Movie {
        initializeService()
        val doc = service.loadPage(id)

        val rawTitle = doc.selectFirst(".film-detail-title")?.text()?.trim() 
            ?: doc.selectFirst("title")?.text()?.trim() 
            ?: ""
        val titleMatch = Regex("^(.*?)\\s*\\((\\d{4})\\)\\s*$").find(rawTitle)
        val title = titleMatch?.groupValues?.getOrNull(1)?.trim() ?: rawTitle
        val year = titleMatch?.groupValues?.getOrNull(2)

        val poster = (doc.selectFirst(".film-detail-poster img")?.attr("src")
            ?: doc.selectFirst("img")?.attr("src"))?.replace("/original/", "/w300/")

        val overview = doc.selectFirst(".film-synopsis-text, .film-detail-synopsis")?.text()?.trim()
        val genre = doc.selectFirst(".film-detail-cat")?.text()?.trim()

        return Movie(
            id = id,
            title = title,
            released = year,
            overview = overview,
            genres = genre?.let { listOf(Genre(name = it, id = it)) } ?: listOf(),
            poster = poster,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        return TvShow()
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre = coroutineScope {
        initializeService()
        val (name, catid) = id.split("|", limit = 2)

        val apiBase = homePath.substringBefore("/home/")
        val folder = apiBase.removePrefix("/").substringBefore("/")
        val pr = homePath.substringAfterLast("/")

        val response = service.apiCategory(
            "$apiBase/api_category.php",
            catid = catid,
            offset = (page - 1) * 20,
            limit = 20,
            folder = folder,
            pr = pr
        )

        val movies = response.films.map { film ->
            val titleMatch = Regex("^(.*?)\\s*\\((\\d{4})\\)\\s*$").find(film.title.trim())
            val displayTitle = titleMatch?.groupValues?.getOrNull(1)?.trim() ?: film.title
            val year = titleMatch?.groupValues?.getOrNull(2)

            Movie(
                id = film.link ?: "",
                title = displayTitle,
                released = year,
                poster = film.poster?.replace("/original/", "/w300/"),
                quality = if (film.hd == true) "HD" else null
            )
        }

        Genre(
            id = name,
            name = name,
            shows = movies
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id="", name="")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()
        val document = service.loadPage(id)

        // Take ALL iframes (not just the first one): when the site lists
        // multiple players the first iframe might be dead while a later one
        // works — same trap that hit VoirAnime. Dedup by URL.
        val iframeUrls = document.select("iframe").mapNotNull { it.attr("src").takeIf { s -> s.startsWith("http") } }
        if (iframeUrls.isEmpty()) throw Exception("Video unavailable")
        val seen = mutableSetOf<String>()
        return iframeUrls.mapNotNull { url ->
            if (!seen.add(url)) return@mapNotNull null
            val urlobj = url.toHttpUrl()
            val serviceName = Extractor.identifyServiceName(url)
            val displayName = serviceName ?: urlobj.host.replace(".com", "")
            Video.Server(id = url, name = displayName, src = url)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2026-05-27 : Backups Movix + Cloudstream (user "ajoute wiflix et
    //   frenchstream en backup pour tous les providers"). aplouf = scraper
    //   natif iframe, pas de tmdbId → résolution TMDB par titre/année.
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun resolveAploufTmdbId(videoType: Video.Type): Int? = runCatching {
        val title = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }
        val cleanTitle = TitleNormalizer.cleanForTmdbSearch(title).ifBlank { title }
        val year = when (videoType) {
            is Video.Type.Movie -> videoType.releaseDate.takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull()
            is Video.Type.Episode -> videoType.tvShow.releaseDate?.take(4)?.toIntOrNull()
        }
        val tmdbItem: Any? = when (videoType) {
            is Video.Type.Movie -> TmdbUtils.getMovie(cleanTitle, year, "fr-FR")
            is Video.Type.Episode -> TmdbUtils.getTvShow(cleanTitle, year, "fr-FR")
        }
        when (tmdbItem) {
            is Movie -> tmdbItem.id.toIntOrNull()
            is TvShow -> tmdbItem.id.toIntOrNull()
            else -> null
        }
    }.getOrNull()

    private suspend fun fetchAploufMovixBackup(tmdbId: Int, videoType: Video.Type): List<Video.Server> = runCatching {
        val movixVideoType = if (videoType is Video.Type.Episode)
            videoType.copy(tvShow = videoType.tvShow.copy(id = "$tmdbId"))
        else videoType
        val movixId = when (videoType) {
            is Video.Type.Movie -> "$tmdbId"
            is Video.Type.Episode -> "$tmdbId-s${videoType.season.number}e${videoType.number}"
        }
        MovixProvider.getServersAsBackup(movixId, movixVideoType)
            .map { srv -> srv.copy(id = "movix_backup__${srv.id}") }
    }.getOrNull().orEmpty()

    private suspend fun fetchAploufCloudstreamBackup(tmdbId: Int, videoType: Video.Type): List<Video.Server> = runCatching {
        val csId = when (videoType) {
            is Video.Type.Movie -> "$tmdbId"
            is Video.Type.Episode -> "$tmdbId:${videoType.season.number}:${videoType.number}"
        }
        val csVideoType = if (videoType is Video.Type.Episode)
            videoType.copy(tvShow = videoType.tvShow.copy(id = "$tmdbId"))
        else videoType
        CloudstreamProvider.getServers(csId, csVideoType)
            .map { srv -> srv.copy(id = "cs_backup__${srv.id}", name = "Cloudstream — ${srv.name}") }
    }.getOrNull().orEmpty()

    override suspend fun getVideo(server: Video.Server): Video {
        // Délégation backups
        if (server.id.startsWith("movix_backup__")) {
            val original = server.copy(id = server.id.removePrefix("movix_backup__"))
            return try { MovixProvider.getVideo(original) }
            catch (e: Exception) { Log.w("aplouf", "Movix getVideo failed: ${e.message}"); Video(source = original.src) }
        }
        if (server.id.startsWith("cs_backup__")) {
            val original = server.copy(id = server.id.removePrefix("cs_backup__"))
            return try { CloudstreamProvider.getVideo(original) }
            catch (e: Exception) { Log.w("aplouf", "CS getVideo failed: ${e.message}"); Video(source = original.src) }
        }

        val video = Extractor.extract(server.src)

        return video
    }

    /**
     * Initializes the service with the current domain URL.
     * This function is necessary because the provider's domain frequently changes.
     * We fetch the latest URL from a dedicated website that tracks these changes.
     */
    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this,UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                val url = portalUrl.toHttpUrl()
                val addressService = Service.buildAddressFetcher(url)
                try {
                    var document = addressService.loadPage(url.encodedPath.removePrefix("/"))
                    var link = document.selectFirst("a:contains(kidraz)")
                    var newUrl = link?.attr("href")

                    if (!newUrl.isNullOrEmpty()) {

                        newUrl = if (newUrl.endsWith("/")) newUrl else "$newUrl/"

                        document = addressService.loadPage(newUrl)
                        var newPath = document.selectFirst("a#kidrazc")?.attr("href")?:""
                        val raw = addressService.loadPageRaw(newUrl+newPath)
                        if (raw.isSuccessful) {
                            val homeUrl = raw.raw().request.url
                            homePath = homeUrl.encodedPath //.removePrefix("/")

                            UserPreferences.setProviderCache(this,
                                UserPreferences.PROVIDER_URL, homeUrl.toString())
                            UserPreferences.setProviderCache(
                            this,
                            UserPreferences.PROVIDER_LOGO,
                            newUrl + "favicon.png")

                        }
                    }
                } catch (e: Exception) {
                    // Portal aplouf.com KO → fallback kidraz via chezlesducs.free.fr
                    try {
                        val fbUrl = FALLBACK_PORTAL_URL.toHttpUrl()
                        val fbService = Service.buildAddressFetcher(fbUrl)
                        val fbDoc = fbService.loadPage(fbUrl.encodedPath.removePrefix("/"))
                        val fbLink = fbDoc.selectFirst("a:contains(kidraz)")
                        var fbNewUrl = fbLink?.attr("href")
                        if (!fbNewUrl.isNullOrEmpty()) {
                            fbNewUrl = if (fbNewUrl.endsWith("/")) fbNewUrl else "$fbNewUrl/"
                            val fbDoc2 = fbService.loadPage(fbNewUrl)
                            val fbPath = fbDoc2.selectFirst("a#kidrazc")?.attr("href") ?: ""
                            val fbRaw = fbService.loadPageRaw(fbNewUrl + fbPath)
                            if (fbRaw.isSuccessful) {
                                val homeUrl = fbRaw.raw().request.url
                                homePath = homeUrl.encodedPath
                                UserPreferences.setProviderCache(this,
                                    UserPreferences.PROVIDER_URL, homeUrl.toString())
                                UserPreferences.setProviderCache(this,
                                    UserPreferences.PROVIDER_LOGO, fbNewUrl + "favicon.png")
                            }
                        }
                    } catch (_: Exception) {
                        // Les 2 portals KO → on garde l'URL par défaut
                    }
                }
            }
            val url = baseUrl.toHttpUrl()
            service = Service.build(url)
            homePath = url.encodedPath.removePrefix("/")
            moviePath = ""

            serviceInitialized = true
        }

        return baseUrl
    }

    private suspend fun initializeService() {
        initializationMutex.withLock {
            if (serviceInitialized) return

            onChangeUrl()
        }
    }

    private interface Service {
        companion object {
            private val client = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val newRequest = chain.request().newBuilder()
                        .addHeader("User-agent", user_agent)
                        .addHeader("Cookie", "g=true")
                        .build()
                    chain.proceed(newRequest)
                }
                .dns(DnsResolver.doh)
                .build()

            fun buildAddressFetcher(url: HttpUrl): Service {
                val burl = url.newBuilder()
                    .encodedPath("/")
                    .query(null)
                    .build()
                    .toString()

                val addressRetrofit = Retrofit.Builder()
                    .baseUrl(burl)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()

                return addressRetrofit.create(Service::class.java)
            }

            fun build(url: HttpUrl): Service {
                val burl = url.newBuilder()
                    .encodedPath("/")
                    .query(null)
                    .build()
                    .toString()

                val retrofit = Retrofit.Builder()
                    .baseUrl(burl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }


        @GET
        suspend fun search(
            @Url url: String,
            @Query("searchword") query: String,
            @Query("offset") offset: Int,
            @Query("limit") limit: Int,
            @Query("folder") folder: String,
            @Query("pr") pr: String
        ): FilmResponse

        @GET
        suspend fun apiFilms(
            @Url url: String,
            @Query("offset") offset: Int,
            @Query("limit") limit: Int,
            @Query("folder") folder: String,
            @Query("pr") pr: String
        ): FilmResponse

        @GET
        suspend fun apiCategory(
            @Url url: String,
            @Query("catid") catid: String,
            @Query("offset") offset: Int,
            @Query("limit") limit: Int,
            @Query("folder") folder: String,
            @Query("pr") pr: String
        ): FilmResponse

        @GET
        suspend fun loadPage(
            @Url url: String
        ): Document

        @GET
        suspend fun loadPageRaw(
            @Url url: String
        ): Response<ResponseBody>
    }
}