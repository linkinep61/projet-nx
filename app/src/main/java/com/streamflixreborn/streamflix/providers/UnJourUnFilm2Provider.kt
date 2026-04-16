package com.streamflixreborn.streamflix.providers

import android.text.Html
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.ApiVoirFilmExtractor
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.extractors.OnRegardeOuExtractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.nodes.Element
import retrofit2.http.Header
import retrofit2.http.Query
import kotlin.collections.map
import kotlin.collections.mapNotNull
import kotlin.collections.mapIndexed
import kotlin.math.round
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import android.util.Base64

object UnJourUnFilm2Provider : Provider, ProviderPortalUrl, ProviderConfigUrl {
    override val name = "wowfilms2026"

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
    override val defaultPortalUrl: String = "https://wowfilms2026.site/"

    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty { field }
        }

    override val defaultBaseUrl: String = "https://wowfilms0426c.site/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }

    override val logo: String
        get() {
            val cacheLogo = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_LOGO)
            return cacheLogo.ifEmpty { portalUrl + "wp-content/uploads/2025/07/1J1F-150x150.jpg" }
        }
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    fun ignoreSource(source: String, href: String): Boolean {
        if (arrayOf("youtube.").any {
                href.contains(
                    it,
                    true
                )
            })
            return true
        return false
    }

    override suspend fun getHome(): List<Category> {
        initializeService()

        val document = service.getHome()
        val categories = mutableListOf<Category>()

        // FEATURED: Parse hero slider
        val featured = document.select(".j1f-slider .j1f-slide")
            .mapNotNull { slide ->
                val link = slide.selectFirst("a.j1f-slide__btn")
                val href = link?.attr("href") ?: return@mapNotNull null
                val title = slide.selectFirst(".j1f-slide__title")?.text() ?: ""
                val banner = slide.selectFirst(".j1f-slide__bg")?.attr("src") ?: ""
                val id = href.substringBeforeLast("/").substringAfterLast("/")

                if (href.contains("/films/")) {
                    Movie(
                        id = id,
                        title = title,
                        banner = banner
                    )
                } else if (href.contains("/tvshows/")) {
                    TvShow(
                        id = id,
                        title = title,
                        banner = banner
                    )
                } else {
                    null
                }
            }

        if (featured.isNotEmpty()) {
            categories.add(
                Category(
                    name = Category.FEATURED,
                    list = featured
                )
            )
        }

        // SECTIONS: Parse new card structure
        val regex_episode = Regex("^(.*?)-s\\d+-episode-\\d+")
        val regex_saison = Regex("^(.*?)-saison-\\d+")

        document.select(".j1f-section").forEach { section ->
            val sectionTitle = section.selectFirst(".j1f-section__title")?.text() ?: ""

            val items = section.select("a.j1f-card")
                .mapNotNull { card ->
                    val href = card.attr("href")

                    val title = card.selectFirst(".j1f-card__title")?.text() ?: ""
                    val posterImg = card.selectFirst(".j1f-card__poster img")
                    val poster = posterImg?.attr("src")?.ifBlank { posterImg.attr("data-src") } ?: ""
                    val id = href.substringBeforeLast("/").substringAfterLast("/")

                    if (href.contains("/films/")) {
                        Movie(
                            id = id,
                            title = title,
                            poster = poster
                        )
                    } else if (href.contains("/tvshows/")) {
                        var tvShowId = id
                        tvShowId = regex_episode.find(tvShowId)?.groupValues?.get(1)
                            ?: regex_saison.find(tvShowId)?.groupValues?.get(1)
                            ?: tvShowId
                        TvShow(
                            id = tvShowId,
                            title = title,
                            poster = poster
                        )
                    } else {
                        null
                    }
                }

            if (items.isNotEmpty()) {
                categories.add(
                    Category(
                        name = sectionTitle,
                        list = items
                    )
                )
            }
        }

        // Fallback to old structure if no sections found
        if (categories.size <= 1) {
            document.select("header")
                .filter { it.children().firstOrNull()?.tagName() == "h2" }
                .mapNotNull { part ->
                    var sibling: Element? = part.nextElementSibling()

                    while (sibling != null) {
                        if (sibling.tagName() == "header" && sibling.selectFirst("h2") != null) {
                            break
                        }

                        val items = sibling.select("article.item")
                            .mapNotNull { item ->
                                val link = item.selectFirst("a") ?: return@mapNotNull null
                                val img = item.selectFirst("img")

                                if (item.hasClass("movies"))
                                    Movie(
                                        id = link.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                                        title = img?.attr("alt") ?: "",
                                        poster = img?.let { img ->
                                            img.attr("src").ifBlank { img.attr("data-src") }
                                        } ?: ""
                                    )
                                else {
                                    var id = link.attr("href").substringBeforeLast("/")
                                        .substringAfterLast("/")
                                    id = regex_episode.find(id)?.groupValues?.get(1)
                                            ?: regex_saison.find(id)?.groupValues?.get(1)
                                                    ?: id
                                    TvShow(
                                        id = id,
                                        title = img?.attr("alt") ?: "",
                                        poster = img?.let { img ->
                                            img.attr("src").ifBlank { img.attr("data-src") }
                                        } ?: "",
                                    )
                                }
                            }
                        if (items.isNotEmpty()) {
                            categories.add(
                                Category(
                                    name = part.selectFirst("h2")?.text() ?: "",
                                    list = items
                                )
                            )
                        }
                        sibling = sibling.nextElementSibling()
                    }
                }
        }

        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        initializeService()
        if (query.isEmpty()) {
            val document = service.getHome()

            val genres = document.selectFirst("ul.mega-sub-menu:has(li.mega-menu-item-object-genres)")
                    ?.select("li.mega-menu-item-object-genres")
                    ?.mapNotNull {
                        val a = it.selectFirst(">a")
                        if (a == null) return@mapNotNull null

                        Genre(
                            id = a.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                            name = a.text(),
                        )
                    } ?: emptyList()

            return genres
        }

        val document = service.search( query )

        // Try old selectors first, then new selectors
        var results = document.select("div.result-item > article")
            .mapNotNull {
                val link = it.selectFirst("div.title")?.selectFirst("a")
                val id = link
                    ?.attr("href")
                    ?: "";
                if (id.contains("/films/")) {
                    Movie(
                        id = id.substringBeforeLast("/").substringAfterLast("/"),
                        title = link
                            ?.text()
                            ?: "",
                        poster = it.selectFirst("img.lazyload")?.attr("data-src")
                    )
                } else if (id.contains("/tvshows/")) {
                    TvShow(
                        id = id.substringBeforeLast("/").substringAfterLast("/"),
                        title = link
                            ?.text()
                            ?: "",
                        poster = it.selectFirst("img.lazyload")?.attr("data-src")
                    )
                } else {
                    null
                }
            }

        // Fallback to new card structure if old selectors returned nothing
        if (results.isEmpty()) {
            results = document.select("a.j1f-card")
                .mapNotNull { card ->
                    val href = card.attr("href")

                    val title = card.selectFirst(".j1f-card__title")?.text() ?: ""
                    val posterImg = card.selectFirst(".j1f-card__poster img")
                    val poster = posterImg?.attr("src")?.ifBlank { posterImg.attr("data-src") } ?: ""
                    val id = href.substringBeforeLast("/").substringAfterLast("/")

                    if (href.contains("/films/")) {
                        Movie(
                            id = id,
                            title = title,
                            poster = poster
                        )
                    } else if (href.contains("/tvshows/")) {
                        TvShow(
                            id = id,
                            title = title,
                            poster = poster
                        )
                    } else {
                        null
                    }
                }
        }

        return results
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        initializeService()

        val document = service.getMovies(page)

        var movies: List<Movie> = emptyList()
        if (page == 1) {
            movies = document.select("div#slider-movies").getOrNull(0)?.select("article.item")
                ?.map {
                    Movie(
                        id = it.selectFirst("a")
                            ?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/")
                            ?: "",
                        title = it.selectFirst("h3.title")
                            ?.text()
                            ?: "",
                        poster = it.selectFirst("img")?.let { img ->
                            img.attr("src").ifBlank { img.attr("data-src") }
                        } ?: "",
                    )
                } ?: emptyList();

            val items = document.selectFirst("div.items.featured")
                ?.select("article.item")
                            ?.mapNotNull { item ->
                                val link = item.selectFirst("a") ?: return@mapNotNull null
                                val img = item.selectFirst("img")

                                    Movie(
                                        id = link.attr("href"),
                                        title = img?.attr("alt") ?: "",
                                        poster = img?.let { img ->
                                            img.attr("src").ifBlank { img.attr("data-src") }
                                        } ?: ""
                                    )
                            }
                ?: emptyList()

            if (items.isNotEmpty()) {
                movies = movies + items
            }
        }

        var itemsRec = document.selectFirst("div.items.full")
            ?.select("article.item")
            ?.mapNotNull { item ->
                val link = item.selectFirst("a") ?: return@mapNotNull null
                val img = item.selectFirst("img")

                Movie(
                    id = link.attr("href"),
                    title = img?.attr("alt") ?: "",
                    poster = img?.let { img ->
                        img.attr("src").ifBlank { img.attr("data-src") }
                    } ?: ""
                )
            }
            ?: emptyList()

        // Fallback to new card structure if old selectors returned nothing
        if (itemsRec.isEmpty()) {
            itemsRec = document.select("a.j1f-card")
                .mapNotNull { card ->
                    val href = card.attr("href")

                    if (!href.contains("/films/")) return@mapNotNull null

                    val title = card.selectFirst(".j1f-card__title")?.text() ?: ""
                    val posterImg = card.selectFirst(".j1f-card__poster img")
                    val poster = posterImg?.attr("src")?.ifBlank { posterImg.attr("data-src") } ?: ""
                    val id = href.substringBeforeLast("/").substringAfterLast("/")

                    Movie(
                        id = id,
                        title = title,
                        poster = poster
                    )
                }
        }

        if (itemsRec.isNotEmpty()) {
            movies = movies + itemsRec
        }

        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        initializeService()
        val document = service.getTvShows(page)

        var tvshows: List<TvShow> = emptyList()

        if (page == 1) {
            tvshows = document.select("div#slider-tvshows").getOrNull(0)?.select("article.item")
                ?.map {
                    TvShow(
                        id = it.selectFirst("a")
                            ?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/")
                            ?: "",
                        title = it.selectFirst("h3.title")
                            ?.text()
                            ?: "",
                        poster = it.selectFirst("img")?.let { img ->
                            img.attr("src").ifBlank { img.attr("data-src") }
                        } ?: "",
                    )
                } ?: emptyList();

            val items = document.selectFirst("div.items.featured")
                ?.select("article.item")
                ?.mapNotNull { item ->
                    val link = item.selectFirst("a") ?: return@mapNotNull null
                    val img = item.selectFirst("img")

                    TvShow(
                        id = link.attr("href"),
                        title = img?.attr("alt") ?: "",
                        poster = img?.let { img ->
                            img.attr("src").ifBlank { img.attr("data-src") }
                        } ?: ""
                    )
                }
                ?: emptyList()

            if (items.isNotEmpty()) {
                tvshows = tvshows + items
            }
        }

        var itemsRec = document.selectFirst("div.items.full")
            ?.select("article.item")
            ?.mapNotNull { item ->
                val link = item.selectFirst("a") ?: return@mapNotNull null
                val img = item.selectFirst("img")

                TvShow(
                    id = link.attr("href"),
                    title = img?.attr("alt") ?: "",
                    poster = img?.let { img ->
                        img.attr("src").ifBlank { img.attr("data-src") }
                    } ?: ""
                )
            }
            ?: emptyList()

        // Fallback to new card structure if old selectors returned nothing
        if (itemsRec.isEmpty()) {
            itemsRec = document.select("a.j1f-card")
                .mapNotNull { card ->
                    val href = card.attr("href")

                    if (!href.contains("/tvshows/")) return@mapNotNull null

                    val title = card.selectFirst(".j1f-card__title")?.text() ?: ""
                    val posterImg = card.selectFirst(".j1f-card__poster img")
                    val poster = posterImg?.attr("src")?.ifBlank { posterImg.attr("data-src") } ?: ""
                    val id = href.substringBeforeLast("/").substringAfterLast("/")

                    TvShow(
                        id = id,
                        title = title,
                        poster = poster
                    )
                }
        }

        if (itemsRec.isNotEmpty()) {
            tvshows = tvshows + itemsRec
        }

        return tvshows
    }

    suspend fun getRating(votes: Element): Double {
        val voteplus = votes
            .selectFirst("span.ratingtypeplusminus")
            ?.text()
            ?.toIntOrNull() ?: 0

        val votenum = votes
            .select("span[id]")
            .last()
            ?.text()
            ?.toIntOrNull() ?: 0

        val rating = if (votenum >= voteplus && votenum > 0) {
            round((votenum - (votenum - voteplus) / 2.0) / votenum * 100) / 10
        } else 0.0

        return rating
    }

    fun decodeHtml(s: String): String {
        @Suppress("DEPRECATION")
        val text = Html.fromHtml(s).toString()
        return JSONObject("""{"v":"$text"}""").getString("v")
    }

    data class itemLink(
        val embed_url: String?,
        val type: String?
    )

    override suspend fun getMovie(id: String): Movie {
        initializeService()
        val document = service.getMovie(id)

        val scriptdoc = document.head().selectFirst("script[type=application/ld+json]:not([class])")?.data().orEmpty()

        val title = scriptdoc.substringAfter("name\":\"").substringBefore("\",")
        val overview = decodeHtml(scriptdoc.substringAfter("description\":\"").substringBefore("\",")).substringAfter(": ").substringBeforeLast(" Voir ")
        val released = id.substringAfterLast("-")
        val strTrailerURL = scriptdoc.substringAfter("\"embedUrl\":").substringBefore(",").replace("\"","")

        val trailerURL: String? = strTrailerURL.takeIf { it != "null" && it.isNotEmpty() }
                                    ?.substringBefore("?")
                                    ?.substringAfterLast("/")
                                    ?.let { "https://www.youtube.com/watch?v=$it" }

        // Resilient poster extraction with fallbacks
        val poster = (document.selectFirst("div.poster > img.lazyload")?.attr("data-src")
            ?: document.selectFirst("div.poster img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?: document.selectFirst(".j1f-hero img, img[class*=poster]")?.attr("src")
            ?: "")

        // Resilient runtime extraction with fallback
        val runtime = try {
            document.select("span.runtime")
                .text().substringAfter(" ").let {
                    val hours = it.substringBefore("h").toIntOrNull() ?: 0
                    val minutes =
                        it.substringAfter("h").trim().toIntOrNull() ?: 0
                    hours * 60 + minutes
                }.takeIf { it != 0 }
        } catch (e: Exception) {
            null
        }

        // Resilient quality extraction with fallback
        val quality = document.selectFirst("div.fakeplayer span.quality")?.text()
            ?: document.selectFirst("span.quality")?.text()

        // Resilient genres extraction with fallback
        val genres = document.select("div.sgeneros").select("a")
            .takeIf { it.isNotEmpty() }
            ?.mapNotNull {
                Genre(
                    id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                    name = it.text(),
                )
            }
            ?: document.select("a[href*=/genre/]").mapNotNull {
                Genre(
                    id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                    name = it.text(),
                )
            }

        // Resilient directors extraction with fallback
        val directors = document.select("div.persons > div.person[itemprop=director]").map { it ->
            val id = it.selectFirst("a[itemprop=url]")
            People(
                id = id?.attr("href")?:"",
                name = id?.text()?:"",
            )
        }.takeIf { it.isNotEmpty() } ?: emptyList()

        // Resilient cast extraction with fallback
        val cast = document.select("div.persons > div.person[itemprop=actor]").map { it ->
            val id = it.selectFirst("a[itemprop=url]")
            People(
                id = id?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/")?:"",
                name = id?.text()?:"",
                image = it.selectFirst("div.img > a > img")?.attr("data-src")?:""
            )
        }.takeIf { it.isNotEmpty() } ?: emptyList()

        // Resilient rating extraction with fallback
        val rating = document.selectFirst("span.dt_rating_vgs[itemprop=ratingValue]")?.text()?.toDoubleOrNull()

        // Resilient recommendations extraction with fallback
        val recommendations = document.select("div#single_relacionados > article").map {
            val id = it.selectFirst("a")
            val img = it.selectFirst("img.lazyload")
            val href = id?.attr("href")?:""
            if (href.contains("/films/")) {
                Movie(
                    id = href.substringBeforeLast("/").substringAfterLast("/"),
                    poster = img?.attr("data-src") ?: "",
                    title = img?.attr("alt") ?: ""
                )
            } else {
                TvShow(
                    id = href.substringBeforeLast("/").substringAfterLast("/"),
                    poster = img?.attr("data-src") ?: "",
                    title = img?.attr("alt") ?: ""
                )
            }
        }.takeIf { it.isNotEmpty() } ?: emptyList()

        val movie = Movie(
            id = id,
            title = decodeHtml(title),
            overview = decodeHtml(overview),
            released = released,
            runtime = runtime,
            quality = quality,
            poster = poster,
            trailer = trailerURL,
            genres = genres,
            directors = directors,
            cast = cast,
            rating = rating,
            recommendations = recommendations
        )

        return movie
    }

    override suspend fun getTvShow(id: String): TvShow {
        initializeService()

        val document = service.getTvShow(id)

        val scriptdoc = document.head().selectFirst("script[type=application/ld+json]:not([class])")?.data().orEmpty()

        val title = scriptdoc.substringAfter("name\":\"").substringBefore("\",")
        var overview = decodeHtml(document.selectFirst("div.wp-content")?.text()?:"")
        if (overview.startsWith("Regarder ")) {
            overview = overview.substringAfter(": ")
        }

        val trailerURL = document.selectFirst("div#trailer div.embed iframe")?.attr("src")
            ?.substringBefore("?")
            ?.substringAfterLast("/")
            ?.let { "https://www.youtube.com/watch?v=$it" }

        var releaseFirst = ""
        var releaseLast = ""

        // Try new season structure first, then fallback to old structure
        var seasons = document.select(".seasons-grid .season-card")
            .mapIndexed { idx, seasonCard ->
                val href = seasonCard.attr("href")
                val seasonTitle = seasonCard.selectFirst(".j1f-season-hover-title")?.text() ?: ""

                // Extract season number from title (format: "... Saison N") or from URL ("saison-N")
                val seasonNumber = if (seasonTitle.contains("Saison")) {
                    seasonTitle.substringAfter("Saison ").substringBefore(" ").toIntOrNull() ?: (idx + 1)
                } else {
                    val urlMatch = Regex("saison-(\\d+)").find(href)
                    urlMatch?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                }

                Season(
                    id = href.substringBeforeLast("/").substringAfterLast("/"),
                    number = seasonNumber,
                    title = seasonTitle.ifBlank { "Saison $seasonNumber" },
                    poster = seasonCard.selectFirst("img")?.attr("src")
                )
            }

        // Fallback to old structure if no new structure found
        if (seasons.isEmpty()) {
            seasons = document.selectFirst("div#seasons")
                ?.select("div.se-c")
                ?.mapIndexed { idx, season ->
                    val release = (season.selectFirst("span.title")?.selectFirst("i")?.text()?:"").substringAfterLast(", ")
                    if (releaseFirst.isBlank()) releaseFirst = release
                    releaseLast = release
                    val title = (season.selectFirst("span.title")?.text()?:"Saison $idx").replaceAfterLast(")","")
                    val number = title.substringAfter("Saison ").substringBefore(" ").toIntOrNull() ?: (idx + 1)

                    Season(
                        id = "$id/$idx",
                        number = number,
                        title = title,
                        poster = season.selectFirst("img.lazyload")?.attr("data-src")
                    )
                } ?: emptyList()
        }

        val released = if (releaseFirst != releaseLast) "$releaseLast-$releaseFirst" else releaseFirst

        // Resilient poster extraction with fallbacks
        val poster = (document.selectFirst("div.poster > img.lazyload")?.attr("data-src")
            ?: document.selectFirst("div.poster img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?: document.selectFirst(".j1f-hero img, img[class*=poster]")?.attr("src")
            ?: "")

        // Resilient genres extraction with fallback
        val genres = document.select("div.sgeneros").select("a")
            .takeIf { it.isNotEmpty() }
            ?.mapNotNull {
                Genre(
                    id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                    name = it.text(),
                )
            }
            ?: document.select("a[href*=/genre/]").mapNotNull {
                Genre(
                    id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                    name = it.text(),
                )
            }

        // Resilient directors extraction with fallback
        val directors = document.select("div.persons > div.person[itemprop=director]").map { it ->
            val id = it.selectFirst("a[itemprop=url]")
            People(
                id = id?.attr("href")?:"",
                name = id?.text()?:"",
            )
        }.takeIf { it.isNotEmpty() } ?: emptyList()

        // Resilient cast extraction with fallback
        val cast = document.select("div.persons > div.person[itemprop=actor]").map { it ->
            val id = it.selectFirst("a[itemprop=url]")
            People(
                id = id?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/")?:"",
                name = id?.text()?:"",
                image = it.selectFirst("div.img > a > img")?.attr("data-src")?:""
            )
        }.takeIf { it.isNotEmpty() } ?: emptyList()

        // Resilient rating extraction with fallback
        val rating = document.selectFirst("span.dt_rating_vgs[itemprop=ratingValue]")?.text()?.toDoubleOrNull()

        // Resilient recommendations extraction with fallback
        val recommendations = document.select("div#single_relacionados > article").map {
            val id = it.selectFirst("a")
            val img = it.selectFirst("img.lazyload")
            val href = id?.attr("href") ?: ""
            if (href.contains("/films/")) {
                Movie(
                    id = href.substringBeforeLast("/").substringAfterLast("/"),
                    poster = img?.attr("data-src") ?: "",
                    title = img?.attr("alt") ?: ""
                )
            } else {
                TvShow(
                    id = href.substringBeforeLast("/").substringAfterLast("/"),
                    poster = img?.attr("data-src") ?: "",
                    title = img?.attr("alt") ?: ""
                )
            }
        }.takeIf { it.isNotEmpty() } ?: emptyList()

        val tvShow = TvShow(
            id = id,
            title = decodeHtml(title),
            overview = decodeHtml(overview),
            released = released,
            poster = poster,
            seasons = seasons,
            trailer = trailerURL,
            genres = genres,
            directors = directors,
            cast = cast,
            rating = rating,
            recommendations = recommendations
        )

        return tvShow
    }

    /**
     * Decode all base64 data-URL scripts from a Jsoup document.
     */
    private fun extractDecodedScripts(document: Document): String {
        val sb = StringBuilder()
        for (script in document.select("script[src]")) {
            val src = script.attr("src")
            if (src.startsWith("data:text/javascript;base64,")) {
                try {
                    val b64 = src.substringAfter("data:text/javascript;base64,")
                    val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                    sb.append(decoded).append("\n")
                } catch (_: Exception) { }
            }
        }
        return sb.toString()
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        initializeService()

        var episodes = emptyList<Episode>()

        // New format: fetch season page and parse j1fEpsData JS variable
        try {
            val seasonDocument = service.getSeason(seasonId)
            val decodedScripts = extractDecodedScripts(seasonDocument)
            val html = seasonDocument.html()
            val searchText = decodedScripts.ifEmpty { html }

            // Try parsing j1fEpsData from the page's JavaScript
            val epsDataRegex = Regex("""j1fEpsData\s*=\s*(\[.*?]);\s*""", RegexOption.DOT_MATCHES_ALL)
            val epsMatch = epsDataRegex.find(searchText)

            if (epsMatch != null) {
                val jsonArrayStr = epsMatch.groupValues[1]
                val jsonArray = JSONArray(jsonArrayStr)

                episodes = (0 until jsonArray.length()).map { idx ->
                    val epObj = jsonArray.getJSONObject(idx)
                    val num = epObj.optString("num", "${idx + 1}").toIntOrNull() ?: (idx + 1)
                    val label = epObj.optString("label", "Épisode ${idx + 1}")
                    val backdrop = epObj.optString("backdrop", "")

                    Episode(
                        id = "SEASON:$seasonId:$idx",
                        number = num,
                        poster = backdrop,
                        title = label,
                        overview = ""
                    )
                }
            }

            // Fallback: parse from DOM (.season-ep-item) if no JS data found
            if (episodes.isEmpty()) {
                val defaultPoster = seasonDocument.selectFirst("div.poster > img.lazyload")
                    ?.attr("data-src")
                    ?: seasonDocument.selectFirst("img[class*=poster]")?.attr("src")
                    ?: ""

                episodes = seasonDocument.select(".season-ep-item")
                    .mapIndexed { idx, ep ->
                        val title = ep.selectFirst(".ep-title")?.text() ?: ""
                        val numStr = ep.selectFirst(".ep-num-overlay")?.text() ?: ""

                        val parts = numStr.split("·")
                        val number = if (parts.size >= 2) {
                            parts[1].trim().toIntOrNull() ?: (idx + 1)
                        } else {
                            idx + 1
                        }

                        val posterImg = ep.selectFirst(".ep-thumb img")
                        val poster = posterImg?.attr("src")?.ifBlank { posterImg?.attr("data-src") } ?: defaultPoster

                        Episode(
                            id = "SEASON:$seasonId:$idx",
                            number = number,
                            poster = poster,
                            title = title,
                            overview = ""
                        )
                    }
            }
        } catch (e: Exception) {
            // Fallback to old format
        }

        // Legacy fallback: old DooPlay structure with div#seasons
        if (episodes.isEmpty()) {
            val parts = seasonId.split("/")
            if (parts.size >= 2) {
                val tvShowId = parts[0]
                val seasonNum = parts.getOrNull(1)?.toIntOrNull() ?: 0

                val document = service.getTvShow(tvShowId)
                val season = document.selectFirst("div#seasons")
                    ?.select("div.se-c")?.getOrNull(seasonNum)

                val defaultPoster = document.selectFirst("div.poster > img.lazyload")
                    ?.attr("data-src")
                    ?: ""

                episodes = season?.select("ul.episodios > li")?.mapIndexed { idx, ep ->
                    val number = idx + 1
                    val link = ep.selectFirst("div.episodiotitle > a")
                    val url = link?.attr("href") ?: ""

                    val id = url.substringBeforeLast("/").substringAfterLast("/")

                    Episode(
                        id = id,
                        number = number,
                        poster = ep.selectFirst("img.lazyload")?.attr("data-src") ?: defaultPoster,
                        title = link?.text() ?: "",
                        overview = ""
                    )
                } ?: emptyList()
            }
        }

        return e