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
import com.streamflixreborn.streamflix.models.Show
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

object UnJourUnFilmProvider : Provider, ProviderPortalUrl, ProviderConfigUrl {
    override val name = "1Jour1Film"

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
    override val defaultPortalUrl: String = "https://1jour1film-officiel.site/"

    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty { field }
        }

    override val defaultBaseUrl: String = "https://1jour1film0426c.site/"
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
            return listOf(
                "Action" to "action",
                "Animation" to "animation",
                "Aventure" to "aventure",
                "Comédie" to "comedie",
                "Crime" to "crime",
                "Documentaire" to "documentaire",
                "Drame" to "drame",
                "K-Drama" to "drama-coreen",
                "Familial" to "familial",
                "Fantastique" to "fantastique",
                "Guerre" to "guerre",
                "Histoire" to "histoire",
                "Horreur" to "horreur",
                "Musique" to "musique",
                "Mystère" to "mystere",
                "Romance" to "romance",
                "Science-Fiction" to "science-fiction",
                "Thriller" to "thriller",
                "Western" to "western",
            ).map { (name, slug) -> Genre(id = slug, name = name) }
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

        // Fallback to search card structure (used on search results pages)
        if (results.isEmpty()) {
            results = document.select("a.j1f-search-card")
                .mapNotNull { card ->
                    val href = card.attr("href")

                    val title = card.selectFirst(".j1f-search-card__title")?.text() ?: ""
                    val posterImg = card.selectFirst(".j1f-search-card__poster img")
                    val poster = posterImg?.attr("data-src")?.ifBlank { posterImg.attr("src") } ?: ""
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

                    // Try both old and new card inner selectors
                    val title = (card.selectFirst(".j1f-card__title") ?: card.selectFirst(".card-title"))?.text() ?: ""
                    val posterImg = card.selectFirst(".j1f-card__poster img") ?: card.selectFirst(".card-poster img")
                    val poster = posterImg?.attr("src")?.ifBlank { posterImg?.attr("data-src") } ?: ""
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

        // REST API fallback: catalogue pages are now JS-rendered, Jsoup sees nothing
        if (movies.isEmpty()) {
            movies = getMoviesFromApi(page)
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

                    // Try both old and new card inner selectors
                    val title = (card.selectFirst(".j1f-card__title") ?: card.selectFirst(".card-title"))?.text() ?: ""
                    val posterImg = card.selectFirst(".j1f-card__poster img") ?: card.selectFirst(".card-poster img")
                    val poster = posterImg?.attr("src")?.ifBlank { posterImg?.attr("data-src") } ?: ""
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

        // REST API fallback: catalogue pages are now JS-rendered, Jsoup sees nothing
        if (tvshows.isEmpty()) {
            tvshows = getTvShowsFromApi(page)
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
        // Decode JSON unicode escapes (\uXXXX) first
        val unescaped = s.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { mr ->
            val code = mr.groupValues[1].toInt(16)
            code.toChar().toString()
        }
        @Suppress("DEPRECATION")
        return Html.fromHtml(unescaped).toString()
    }

    data class itemLink(
        val embed_url: String?,
        val type: String?
    )

    override suspend fun getMovie(id: String): Movie {
        initializeService()
        val document = service.getMovie(id)

        val scriptdoc = document.head().selectFirst("script[type=application/ld+json]:not([class])")?.data().orEmpty()

        // Parse JSON-LD properly instead of naive string manipulation
        val jsonLd = try { JSONObject(scriptdoc) } catch (_: Exception) { JSONObject() }
        val title = decodeHtml(jsonLd.optString("name", "")).ifBlank {
            document.selectFirst("h1.entry-title, h1, .data h1")?.text() ?: ""
        }
        val overview = decodeHtml(jsonLd.optString("description", "")).substringAfter(": ").substringBeforeLast(" Voir ")
        val released = id.substringAfterLast("-")
        val strTrailerURL = jsonLd.optString("embedUrl", "").ifBlank {
            scriptdoc.substringAfter("\"embedUrl\":").substringBefore(",").replace("\"","")
        }

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

        // Resilient genres extraction with fallback + dedup
        val genres = (document.select("div.sgeneros").select("a")
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
            }).distinctBy { it.name }

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

        // Parse JSON-LD properly instead of naive string manipulation
        val jsonLdTv = try { JSONObject(scriptdoc) } catch (_: Exception) { JSONObject() }
        val title = decodeHtml(jsonLdTv.optString("name", "")).ifBlank {
            document.selectFirst("h1.entry-title, h1, .data h1")?.text() ?: ""
        }
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

                // Extract relative path from full URL (e.g. "saisons/slug")
                // getSeason() uses @GET("{slug}") with encoded=true, so slashes are preserved
                val seasonSlug = href.substringAfter("://").substringAfter("/").trimEnd('/')

                Season(
                    id = seasonSlug,
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

        // Resilient genres extraction with fallback + dedup
        val genres = (document.select("div.sgeneros").select("a")
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
            }).distinctBy { it.name }

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
     * The 1jour1film site embeds JS variables (j1fEpsData, J1F_SRV, J1F, etc.)
     * inside <script src="data:text/javascript;base64,..."> tags.
     * Jsoup treats these as external scripts so their content is NOT in document.html().
     * This helper extracts and decodes them so we can regex-search the JS code.
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
            // j1fEpsData is in base64-encoded data URL scripts, decode them
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

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService()

        val document = try {
            service.getGenre(id, page)
        } catch (e: HttpException) {
            when (e.code()) {
                   404 -> return Genre(id, "")
                else -> throw e
            }
        }

        // Nouvelle structure j1f-card
        var shows = document.select("a.j1f-card")
            .mapNotNull { card ->
                val href = card.attr("href")
                val posterImg = card.selectFirst(".j1f-card__poster img")
                    ?: card.selectFirst("img")
                val poster = posterImg?.attr("src")?.ifBlank { posterImg.attr("data-src") } ?: ""
                val itemId = href.substringBeforeLast("/").substringAfterLast("/")
                val title = card.selectFirst(".j1f-card__title")?.text()?.ifBlank { null }
                    ?: card.selectFirst(".j1f-card__info")?.text()?.ifBlank { null }
                    ?: card.attr("title").ifBlank { null }
                    ?: posterImg?.attr("alt")?.ifBlank { null }
                    ?: itemId.replace("-", " ").replaceFirstChar { it.uppercase() }

                if (href.contains("/films/")) {
                    Movie(
                        id = itemId,
                        title = title,
                        poster = poster
                    )
                } else if (href.contains("/tvshows/")) {
                    TvShow(
                        id = itemId,
                        title = title,
                        poster = poster
                    )
                } else {
                    null
                }
            }

        // Fallback ancienne structure
        if (shows.isEmpty()) {
            shows = document.select("div.items.full > article.item.movies, div.items.full > article.item.tvshows")
                .mapNotNull {
                    val link = it.selectFirst("div.data")?.selectFirst("a")
                    val fhref = link?.attr("href") ?: ""
                    val itemId = fhref.substringBeforeLast("/").substringAfterLast("/")
                    if (fhref.contains("films/")) {
                        Movie(
                            id = itemId,
                            title = link?.text() ?: "",
                            poster = it.selectFirst("img")?.attr("src")
                        )
                    } else if (fhref.contains("tvshows/")) {
                        TvShow(
                            id = itemId,
                            title = link?.text() ?: "",
                            poster = it.selectFirst("img")?.attr("src")
                        )
                    } else {
                        null
                    }
                }
        }

        val genre = Genre(
            id = id,
            name = "",
            shows = shows
        )

        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        initializeService()

        val document = try {
            service.getPeople(id, page)
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> return People(id, "")
                else -> throw e
            }
        }
        val people = People(
            id = id,
            name = "",
            filmography = document.select("div.items.full > article.item.movies, article.item.tvshows")
                .mapNotNull {
                    val link = it.selectFirst("div.data")?.selectFirst("a")
                    val fhref = link
                        ?.attr("href")?:""
                    val href = fhref.substringBeforeLast("/").substringAfterLast("/")
                    if (fhref.contains("/films/")) {
                        Movie(
                            href,
                            title = link
                                ?.text()
                                ?: "",
                            poster = it.selectFirst("img")?.attr("src")
                        )
                    } else if (fhref.contains("/tvshows/")) {
                        TvShow(
                            id = href,
                            title = link
                                ?.text()
                                ?: "",
                            poster = it.selectFirst("img")?.attr("src")
                        )
                    } else {
                        null
                    }
                }
        )

        return people
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()

        val apivoirfilm = ApiVoirFilmExtractor()
        val onregadeou = OnRegardeOuExtractor()
        var apiUrl = ""
        var onregardeUrl = ""

        val servers = mutableListOf<Video.Server>()

        // Handle new season-based episode format: SEASON:seasonSlug:epIndex
        if (id.startsWith("SEASON:")) {
            val parts = id.split(":")
            if (parts.size >= 3) {
                val seasonSlug = parts[1]
                val epIndex = parts[2].toIntOrNull() ?: 0

                val seasonDocument = service.getSeason(seasonSlug)
                val decodedScripts = extractDecodedScripts(seasonDocument)
                val seasonHtml = decodedScripts.ifEmpty { seasonDocument.html() }

                val epsDataRegex = Regex("""j1fEpsData\s*=\s*(\[.*?]);\s*""", RegexOption.DOT_MATCHES_ALL)
                val epsMatch = epsDataRegex.find(seasonHtml)

                if (epsMatch != null) {
                    try {
                        val jsonArray = JSONArray(epsMatch.groupValues[1])
                        if (epIndex < jsonArray.length()) {
                            val epObj = jsonArray.getJSONObject(epIndex)
                            val epServers = epObj.optJSONArray("servers")

                            if (epServers != null) {
                                for (i in 0 until epServers.length()) {
                                    val srv = epServers.getJSONObject(i)
                                    val label = srv.optString("label", "")
                                    val url = srv.optString("url", "")
                                    val flags = srv.optString("flags", "")

                                    if (url.isNotEmpty() && !ignoreSource("", url)) {
                                        val displayName = if (flags.isNotEmpty()) "$flags $label" else label

                                        if (url.startsWith(apivoirfilm.mainUrl)) {
                                            apiUrl = url
                                        } else if (url.startsWith(onregadeou.mainUrl)) {
                                            onregardeUrl = url
                                        } else {
                                            servers.add(
                                                Video.Server(
                                                    id = "srv$i",
                                                    name = displayName.ifBlank { "Server ${i + 1}" },
                                                    src = url
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }

                // Return early with season-based results
                val other = if (apiUrl.isNotEmpty())
                    apivoirfilm.expand(apiUrl, baseUrl, "FR ")
                else if (onregardeUrl.isNotEmpty())
                    onregadeou.expand(onregardeUrl, baseUrl, "FR ")
                else
                    emptyList()

                return sortServersByLanguage(servers + other)
            }
        }

        // Fetch the page (movie or episode)
        val document = when (videoType) {
            is Video.Type.Episode -> service.getEpisode(id)
            is Video.Type.Movie -> service.getMovie(id)
        }

        // Try to extract J1F_SRV servers from the page
        // Data is in base64-encoded data URL scripts
        val decodedMovieScripts = extractDecodedScripts(document)
        val rawHtml = document.html()
        val jsContent = decodedMovieScripts.ifEmpty { rawHtml }
        val srvRegex = Regex("""J1F_SRV\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL)
        val match = srvRegex.find(jsContent)

        if (match != null) {
            try {
                val jsonArrayStr = match.groupValues[1]
                val jsonArray = JSONArray(jsonArrayStr)

                for (i in 0 until jsonArray.length()) {
                    val serverObj = jsonArray.getJSONObject(i)
                    val label = serverObj.optString("label", "")
                    val url = serverObj.optString("url", "")

                    if (url.isNotEmpty() && !ignoreSource("", url)) {
                        if (url.startsWith(apivoirfilm.mainUrl)) {
                            apiUrl = url
                        } else if (url.startsWith(onregadeou.mainUrl)) {
                            onregardeUrl = url
                        } else {
                            servers.add(
                                Video.Server(
                                    id = "srv${servers.size}",
                                    name = label.ifBlank { "Server ${servers.size + 1}" },
                                    src = url
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // JSON parsing failed, try fallback method
            }
        }

        // Fallback: extract post ID from body class and probe DooPlay AJAX
        if (servers.isEmpty() && apiUrl.isEmpty() && onregardeUrl.isEmpty()) {
            val postIdMatch = Regex("""postid-(\d+)""").find(rawHtml)
            val postId = postIdMatch?.groupValues?.get(1) ?: ""
            val isEpisode = videoType is Video.Type.Episode

            if (postId.isNotEmpty()) {
                for (nume in 1..30) {
                    try {
                        val link = service.getServers(
                            num = nume.toString(),
                            post = postId,
                            type = if (isEpisode) "tv" else "movie"
                        )
                        if (link.embed_url.isNullOrEmpty()) break

                        if (ignoreSource("", link.embed_url)) continue

                        if (link.embed_url.startsWith(apivoirfilm.mainUrl)) {
                            apiUrl = link.embed_url
                            continue
                        }
                        if (link.embed_url.startsWith(onregadeou.mainUrl)) {
                            onregardeUrl = link.embed_url
                            continue
                        }

                        servers.add(
                            Video.Server(
                                id = "srv$nume",
                                name = "Serveur $nume",
                                src = link.embed_url
                            )
                        )
                    } catch (_: Exception) { break }
                }
            }
        }

        // Legacy fallback: old DooPlay player options (pre-redesign)
        if (servers.isEmpty() && apiUrl.isEmpty() && onregardeUrl.isEmpty()) {
            val oldServers = document.selectFirst("ul#playeroptionsul")?.select("li.dooplay_player_option")
                ?.mapIndexedNotNull { idx, it ->
                    val nume = it.attr("data-nume")
                    val post = it.attr("data-post")
                    val isEpisode = videoType is Video.Type.Episode
                    val link = service.getServers(num=nume, post=post, type=if (isEpisode) "tv" else "movie")

                    if (link.embed_url.isNullOrEmpty() ||
                        ignoreSource("", link.embed_url))
                        return@mapIndexedNotNull null

                    if (link.embed_url.startsWith(apivoirfilm.mainUrl)) {
                        apiUrl = link.embed_url
                        return@mapIndexedNotNull null
                    }
                    if (link.embed_url.startsWith(onregadeou.mainUrl)) {
                        onregardeUrl = link.embed_url
                        return@mapIndexedNotNull null
                    }

                    val title = it.selectFirst("span.title")?.text() ?: "Server $idx"

                    Video.Server(
                        id = "srv$idx",
                        name = title,
                        src = link.embed_url
                    )
                } ?: emptyList()
            servers.addAll(oldServers)
        }

        val other = if (apiUrl.isNotEmpty())
            apivoirfilm.expand(apiUrl, baseUrl, "FR ")
        else if (onregardeUrl.isNotEmpty())
            onregadeou.expand(onregardeUrl, baseUrl, "FR ")
        else
            emptyList()

        return sortServersByLanguage(servers + other)
    }

    /**
     * Sort servers so that FR/VF sources appear first and VOSTFR sources appear last.
     * Priority: VFF > VF > FR > (no tag) > VOSTFR > VO
     */
    private fun sortServersByLanguage(servers: List<Video.Server>): List<Video.Server> {
        return servers.sortedWith(compareBy { server ->
            val name = server.name.uppercase()
            when {
                name.contains("VFF") -> 0
                name.contains("VF") && !name.contains("VOSTFR") -> 1
                name.contains("FR") && !name.contains("VOSTFR") -> 2
                name.contains("VF") && name.contains("VOSTFR") -> 3  // "VF + VOSTFR" → dernier des FR
                name.contains("VOSTFR") || name.contains("VOST") -> 5
                name.contains("VO") -> 6
                else -> 4
            }
        })
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src)
    }

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                val addressService = Service.buildAddressFetcher()
                try {
                    val document = addressService.getHome()

                    val newUrl = document.html().substringAfter("window.location.href = \"").substringBefore("\"")
                        .trim()
                    if (!newUrl.isNullOrEmpty()) {
                        val newIcon = document.selectFirst("link[rel=apple-touch-icon]")
                            ?.attr("href")
                            ?: "$defaultPortalUrl/wp-content/uploads/2025/07/1J1F-150x150.jpg"
                        val finalUrl = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, finalUrl)
                        UserPreferences.setProviderCache(
                            this,
                            UserPreferences.PROVIDER_LOGO,
                            newIcon
                        )
                    }
                } catch (e: Exception) {
                    // In case of failure, use the default URL
                }
            }
            service = Service.build(baseUrl)
            serviceInitialized = true
        }
        return baseUrl
    }

    /**
     * Fallback: fetch movies from the WordPress REST API when the catalogue
     * pages are JS-rendered and Jsoup cannot parse them.
     */
    private suspend fun getMoviesFromApi(page: Int): List<Movie> {
        return try {
            val response = service.getMoviesApi(page = page)
            val jsonArray = JSONArray(response.string())
            (0 until jsonArray.length()).mapNotNull { i ->
                val obj = jsonArray.getJSONObject(i)
                val slug = obj.optString("slug", "")
                val title = obj.optJSONObject("title")?.optString("rendered", "") ?: ""
                val poster = try {
                    obj.getJSONObject("_embedded")
                        .getJSONArray("wp:featuredmedia")
                        .getJSONObject(0)
                        .optString("source_url", "")
                } catch (_: Exception) { "" }
                if (slug.isNotEmpty()) Movie(id = slug, title = decodeHtml(title), poster = poster) else null
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Fallback: fetch TV shows from the WordPress REST API.
     */
    private suspend fun getTvShowsFromApi(page: Int): List<TvShow> {
        return try {
            val response = service.getTvShowsApi(page = page)
            val jsonArray = JSONArray(response.string())
            (0 until jsonArray.length()).mapNotNull { i ->
                val obj = jsonArray.getJSONObject(i)
                val slug = obj.optString("slug", "")
                val title = obj.optJSONObject("title")?.optString("rendered", "") ?: ""
                val poster = try {
                    obj.getJSONObject("_embedded")
                        .getJSONArray("wp:featuredmedia")
                        .getJSONObject(0)
                        .optString("source_url", "")
                } catch (_: Exception) { "" }
                if (slug.isNotEmpty()) TvShow(id = slug, title = decodeHtml(title), poster = poster) else null
            }
        } catch (_: Exception) { emptyList() }
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
                .dns(DnsResolver.doh)
                .build()

            fun buildAddressFetcher(): Service {
                val addressRetrofit = Retrofit.Builder()
                    .baseUrl(portalUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()

                return addressRetrofit.create(Service::class.java)
            }

            fun build(baseUrl: String): Service {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET(".")
        suspend fun getHome(
            @Header("User-agent") user_agent: String = USER_AGENT
        ): Document

        @GET("/")
        suspend fun search(
            @Query("s") query: String,
            @Header("User-agent") user_agent: String = USER_AGENT
        ): Document

        @GET("films/page/{page}/")
        suspend fun getMovies(
            @Path("page") page: Int,
            @Header("User-agent") user_agent: String = USER_AGENT
        ): Document

        @GET("tvshows/page/{page}/")
        suspend fun getTvShows(
            @Path("page") page: Int,
            @Header("User-agent") user_agent: String = USER_AGENT
        ): Document

        @GET("films/{id}/")
        suspend fun getMovie(
            @Path("id") id: String,
            @Header("User-agent") user_agent: String = USER_AGENT
        ): Document

        @GET("tvshows/{id}/")
        suspend fun getTvShow(
            @Path("id") id: String,
            @Header("User-agent") user_agent: String = USER_AGENT
        ): Document

        @GET("episodes/{id}/")
        suspend fun getEpisode(
            @Path("id") id: String,
            @Header("User-agent") user_agent: String = USER_AGENT
        ): Document

        @GET("{slug}")
        suspend fun getSeason(
            @Path("slug", encoded = true) slug: String,
            @Header("User-agent") user_agent: String = USER_AGENT
        ): Document

        @POST("wp-admin/admin-ajax.php")
        @FormUrlEncoded
        suspend fun getServers(
            @Field("action") action: String = "doo_player_ajax",
            @Field("post") post: String,
            @Field("nume") num: String,
            @Field("type") type: String = "movie",
            @Header("User-agent") user_agent: String = USER_AGENT
        ): itemLink

        @GET("wp-json/wp/v2/movies")
        suspend fun getMoviesApi(
            @Query("per_page") perPage: Int = 24,
            @Query("page") page: Int,
            @Query("_fields") fields: String = "id,slug,title,featured_media,_links",
            @Query("_embed") embed: String = "wp:featuredmedia",
            @Header("User-agent") user_agent: String = USER_AGENT
        ): okhttp3.ResponseBody

        @GET("wp-json/wp/v2/tvshows")
        suspend fun getTvShowsApi(
            @Query("per_page") perPage: Int = 24,
            @Query("page") page: Int,
            @Query("_fields") fields: String = "id,slug,title,featured_media,_links",
            @Query("_embed") embed: String = "wp:featuredmedia",
            @Header("User-agent") user_agent: String = USER_AGENT
        ): okhttp3.ResponseBody

        @GET("genre/{genre}/page/{page}/")
        suspend fun getGenre(
            @Path("genre") genre: String,
            @Path("page") page: Int,
            @Header("User-agent") user_agent: String = USER_AGENT
        ): Document

        @GET("cast/{id}/page/{page}")
        suspend fun getPeople(
            @Path("id") id: String,
            @Path("page") page: Int,
            @Header("User-agent") user_agent: String = USER_AGENT
        ): Document
    }
}
