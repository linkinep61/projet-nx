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
import retrofit2.http.Url
import retrofit2.Response
import okhttp3.ResponseBody
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import org.jsoup.nodes.Element
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import kotlin.collections.map
import kotlin.collections.mapNotNull
import kotlin.collections.mapIndexedNotNull
import kotlin.math.round
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object FrenchStreamProvider : Provider, ProviderPortalUrl, ProviderConfigUrl {
    override val name = "FrenchStream"

    override val defaultPortalUrl: String = "http://fstream.info/"

    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty { field }
        }

    override val defaultBaseUrl: String = "https://fs03.lol/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }

    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_frenchstream"
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    override suspend fun getHome(): List<Category> {
        initializeService()
        // 2026-05: site is still classic server-rendered HTML (DataLifeEngine /templates/FSV1/),
        // not Next.js as a previous patch wrongly assumed. Sections are now wrapped in
        // <div class="sect-c floats clearfix" id="films|series|commu|box"> instead of
        // div#dle-content. The legacy "VFV25 vod-section" interface no longer exists, so we
        // skip it and parse the sections directly.
        val document = service.getHome("dle_skin=VFV1")
        val categories = mutableListOf<Category>()

        fun parseShorts(sectionId: String, sectionTitle: String, asTvShow: Boolean) {
            val items = document.select("div#$sectionId div.short").mapNotNull { item ->
                val link = item.selectFirst("a.short-poster")?.attr("href") ?: return@mapNotNull null
                val href = link.substringAfterLast("/")
                val title = item.selectFirst("div.short-title")?.text() ?: ""
                val poster = item.selectFirst("img")?.attr("src") ?: ""
                if (asTvShow || link.contains("/s-tv/") || link.contains("-saison-"))
                    TvShow(id = href, title = title, poster = poster)
                else
                    Movie(id = href, title = title, poster = poster)
            }
            if (items.isNotEmpty()) categories.add(Category(name = sectionTitle, list = items))
        }
        // Pick a featured banner from the first home item as before.
        val firstItem = document.selectFirst("div.short a.short-poster")
        val featuredPoster = firstItem?.selectFirst("img")?.attr("src") ?: ""
        val featuredHref = firstItem?.attr("href")?.substringAfterLast("/").orEmpty()
        val featuredTitle = firstItem?.parent()?.selectFirst("div.short-title")?.text() ?: ""
        if (firstItem != null && featuredHref.isNotBlank()) {
            val featured = if (firstItem.attr("href").contains("/s-tv/"))
                TvShow(id = featuredHref, title = featuredTitle, poster = featuredPoster, banner = featuredPoster)
            else
                Movie(id = featuredHref, title = featuredTitle, poster = featuredPoster, banner = featuredPoster)
            categories.add(Category(name = Category.FEATURED, list = listOf(featured)))
        }
        parseShorts("films", "Nouveautés Films", asTvShow = false)
        parseShorts("series", "Nouveautés Séries", asTvShow = true)
        parseShorts("commu", "Ajouts de la Commu", asTvShow = false)
        parseShorts("box", "BOX OFFICE", asTvShow = false)

        return categories

        /*
        val isNewInterface = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_NEW_INTERFACE) != "false"
        val document = if (isNewInterface) {
            service.postHome()
        } else {
            service.getHome("dle_skin=VFV1")
        }
        val cookie = if (isNewInterface) "dle_skin=VFV25" else "dle_skin=VFV1"
        val categories = mutableListOf<Category>()
        if ( cookie.contains("VFV25")) {
            var first = true
            document.select("section.vod-section").map { cat_item ->
                val title = cat_item
                    .selectFirst("> div.vod-header h2.vod-title-section")
                    ?.let {
                        listOfNotNull(
                            it.ownText().trim(),
                            it.select("span").firstOrNull()?.text()?.trim()
                        ).joinToString(" ")
                    } ?: ""

                val movies = cat_item
                    .select("> div.vod-wrap > div.vod-slider > article.vod-card")
                    .mapNotNull { item ->
                        val a = item.selectFirst("a") ?: return@mapNotNull null
                        val link = a.attr("href")
                        val href = link.substringAfterLast("/")
                        val title = a.selectFirst("div.vod-name")?.text() ?: ""
                        val poster = a.selectFirst("div.vod-poster > img")?.attr("src") ?: ""
                        val mtype = item.selectFirst("> div.vod-br > span.vod-tag a")?.attr("href") ?: ""
                        if (link.startsWith("/s-tv/") || link.contains("-saison-") || title.contains(" - Saison ") || mtype.contains("-serie"))
                            TvShow(
                                id = href,
                                title = title,
                                poster = poster,
                                banner = if (first) poster else null
                            )
                        else
                            Movie(
                                id = href,
                                title = title,
                                poster = poster,
                                banner = if (first) poster else null
                            )
                    }

                if (movies.isNotEmpty()) {
                    categories.add(
                        Category(
                            name = if (first) Category.FEATURED else title,
                            list = movies
                        )
                    )
                    first = false
                }
            }
        } else {
            categories.add(
                Category(
                    name = "Nouveautés Séries",
                    list = document.select("div.pages.clearfix").getOrNull(1)?.select("div.short")
                        ?.map {
                            TvShow(
                                id = it.selectFirst("a.short-poster")
                                    ?.attr("href")?.substringAfterLast("/")
                                    ?: "",
                                title = listOfNotNull(
                                    it.selectFirst("div.short-title")?.text(),
                                    it.selectFirst("span.film-version")?.text(),
                                ).joinToString(" - "),
                                poster = it.selectFirst("img")
                                    ?.attr("src")
                                    ?: "",
                            )
                        } ?: emptyList(),
                )
            )

            categories.add(
                Category(
                    name = "Nouveautés Films",
                    list = document.select("div.pages.clearfix").getOrNull(0)?.select("div.short")
                        ?.map {
                            Movie(
                                id = it.selectFirst("a.short-poster")
                                    ?.attr("href")?.substringAfterLast("/")
                                    ?: "",
                                title = it.selectFirst("div.short-title")
                                    ?.text()
                                    ?: "",
                                poster = it.selectFirst("img")
                                    ?.attr("src")
                                    ?: "",
                            )
                        } ?: emptyList(),
                )
            )

            categories.add(
                Category(
                    name = "Ajouts de la Commu",
                    list = document.select("div.pages.clearfix").getOrNull(2)?.select("div.short")
                        ?.map {
                            Movie(
                                id = it.selectFirst("a.short-poster")
                                    ?.attr("href")?.substringAfterLast("/")
                                    ?: "",
                                title = it.selectFirst("div.short-title")
                                    ?.text()
                                    ?: "",
                                poster = it.selectFirst("img")
                                    ?.attr("src")
                                    ?: "",
                            )
                        } ?: emptyList(),
                )
            )

            categories.add(
                Category(
                    name = "BOX OFFICE",
                    list = document.select("div.pages.clearfix").getOrNull(3)?.select("div.short")
                        ?.map {
                            Movie(
                                id = it.selectFirst("a.short-poster")
                                    ?.attr("href")?.substringAfterLast("/")
                                    ?: "",
                                title = it.selectFirst("div.short-title")
                                    ?.text()
                                    ?: "",
                                poster = it.selectFirst("img")
                                    ?.attr("src")
                                    ?: "",
                            )
                        } ?: emptyList(),
                )
            )
        }

        // Reorder: 1.FEATURED 2.Épisodes/récents 3.Séries récentes 4.Films récents 5.Séries 6.Films
        return categories.sortedWith(compareBy { cat ->
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
        */
    }

    fun ignoreSource(source: String, href: String): Boolean {
        if (source.trim().equals("Dood.Stream", ignoreCase = true) && href.contains("/bigwar5/")) return true
        return false
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        initializeService()
        if (query.isEmpty()) {
            val document = service.getHome()
            val genres = document.selectFirst("div.menu-section")?.select(">a")?.map {
                Genre(
                    id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                    name = it.text(),
                )
            }?.toMutableList() ?: mutableListOf()
            if (genres.none { (it as? Genre)?.id == "k-drama" }) {
                genres.add(Genre(id = "k-drama", name = "K-Drama"))
            }
            return genres
        }
        val document = service.search(query = query)
        // 2026-05: same loosening as getMovies/getTvShows. Search results may render in
        // either the legacy div.search-item layout OR the new div.short layout.
        val items = document.select("div.search-item, div.short").mapNotNull {
            val link = it.selectFirst("a.short-poster, a")?.attr("href").orEmpty()
            val onclickId = it.attr("onclick").substringAfter("/").substringBefore("'")
            val id = link.substringAfterLast("/").ifBlank { onclickId }
            if (id.isBlank()) return@mapNotNull null
            val title = (it.selectFirst("div.short-title")?.text()
                ?: it.selectFirst("div.search-title")?.text())
                ?.replace("\\'", "'") ?: ""
            val poster = it.selectFirst("img")?.attr("src") ?: ""
            if (id.contains("-saison-") || title.contains(" - Saison ") || link.contains("/s-tv/"))
                TvShow(id = id, title = title, poster = poster)
            else
                Movie(id = id, title = title, poster = poster)
        }
        return items
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        initializeService()
        val document = service.getMovies(page)
        // 2026-05: parent wrapper changed from div#dle-content to div.sect-c (sections like
        // #films, #series). Use the looser div.short selector — works on both home and
        // category/listing pages. We filter to movie-style hrefs to be safe on home.
        return document.select("div.short").mapNotNull { item ->
            val link = item.selectFirst("a.short-poster")?.attr("href") ?: return@mapNotNull null
            // Skip TV-show entries that the same selector also picks up on the home page.
            if (link.contains("/s-tv/") || link.contains("-saison-")) return@mapNotNull null
            Movie(
                id = link.substringAfterLast("/"),
                title = item.selectFirst("div.short-title")?.text() ?: "",
                poster = item.selectFirst("img")?.attr("src"),
            )
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        initializeService()
        val document = service.getTvShows(page)
        return document.select("div.short").mapNotNull { item ->
            val link = item.selectFirst("a.short-poster")?.attr("href") ?: return@mapNotNull null
            // Only keep TV shows (filter out movies that share the same listing on home).
            if (!link.contains("/s-tv/") && !link.contains("-saison-")) return@mapNotNull null
            TvShow(
                id = link.substringAfterLast("/"),
                title = item.selectFirst("div.short-title")?.text() ?: "",
                poster = item.selectFirst("img")?.attr("src"),
            )
        }
    }

    suspend fun getRating(votes: Element): Double {
        val voteplus = votes
            ?.selectFirst("span.ratingtypeplusminus")
            ?.text()
            ?.toIntOrNull() ?: 0

        val votenum = votes
            ?.select("span[id]")
            ?.last()
            ?.text()
            ?.toIntOrNull() ?: 0

        val rating = if (votenum >= voteplus && votenum > 0) {
            round((votenum - (votenum - voteplus) / 2.0) / votenum * 100) / 10
        } else 0.0

        return rating
    }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        initializeService()
        // 2026-05: detail page is at /film/{slug}. Server-rendered HTML with inline
        // Next.js streaming JSON. Show-level fields: year (int), duration, description,
        // genres[], plus the embedded players[]. Poster/banner come from the DOM.
        val document = service.getMovie(id)
        val raw = document.html()
        val title = document.selectFirst("h1#s-title")?.ownText()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val overview = document.selectFirst("p.fdesc-text")?.text()?.trim().orEmpty()
        val releaseYear = extractNumberFromInlineJson(raw, "year")?.toString()
        val poster = document.selectFirst(".fposter img, img.dvd-thumbnail")?.attr("src")
        val genres = document.select("ul#s-list a[href*=genre]").map {
            Genre(
                id = it.attr("href").substringAfter("genre=").substringBefore("&"),
                name = it.text().trim(),
            )
        }
        Movie(
            id = id,
            title = title,
            overview = overview,
            released = releaseYear,
            poster = poster,
            banner = poster,
            genres = genres,
        )

        /*
        val document = service.getItem(id)
        val filmData = filmDataDeferred.await()
        val actors = extractActors(document)
        val trailerURL = filmData ?.meta?.trailer
                                  ?.let { "https://www.youtube.com/watch?v=$it" }
        val poster = filmData ?.meta?.affiche
        val banner = filmData ?.meta?.affiche2

        val votes = document.selectFirst("div.fr-votes")
        val rating = if (votes != null) getRating(votes) else null
        val movie = Movie(
            id = id,
            title = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: "",
            overview = document.selectFirst("div#s-desc")
                ?.apply {
                    selectFirst("p.desc-text")?.remove()
                }
                ?.text()
                ?.trim()
                ?: "",
            released = document.selectFirst("span.release_date")
                ?.text()
                ?.substringAfter("-")
                ?.trim(),
            runtime = document.select("span.runtime")
                .text().substringAfter(" ").let {
                    val hours = it.substringBefore("h").toIntOrNull() ?: 0
                    val minutes =
                        it.substringAfter("h").trim().toIntOrNull() ?: 0
                    hours * 60 + minutes
                }.takeIf { it != 0 },
            quality = document.selectFirst("span[id=film_quality]")
                ?.text(),
            poster = poster,
            banner = banner,
            trailer = trailerURL,
            genres = document.select("span.genres")
                .select("a").mapNotNull {
                    Genre(
                        id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                        name = it.text(),
                    )
                }.ifEmpty {
                    listOf(Genre(id = "unknown", name = ""))
                },
            directors = document.select("ul#s-list li")
                .find {
                    it.selectFirst("span")?.text()?.contains("alisateur") == true
                }
                ?.select("a")?.mapIndexedNotNull { index, it ->
                    People(
                        id = "director$index",
                        name = it.text(),
                    )
                }
                ?: emptyList(),
            cast = actors.map {
                People(
                    id = it[0].replace(" ", "+"),
                    name = it[0],
                    image = it[1]
                )
            },
            rating = rating

        )

        movie
        */
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        initializeService()
        // 2026-05: /serie/{slug-with-saison-N} renders a single-season page. Show-level
        // JSON has year/description/genres; episodes[] sits next to it with each
        // {id, number, title, thumbnail, players[]} block.
        val document = service.getTvShow(id)
        val raw = document.html()
        val title = document.selectFirst("h1#s-title")?.ownText()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val cleanTitle = title.substringBeforeLast("- Saison").trim().ifBlank { title }
        val overview = document.selectFirst("p.fdesc-text")?.text()?.trim().orEmpty()
        val releaseYear = extractNumberFromInlineJson(raw, "year")?.toString()
        val poster = document.selectFirst(".fposter img, img.dvd-thumbnail")?.attr("src")
        val genres = document.select("ul#s-list a[href*=genre]").map {
            Genre(
                id = it.attr("href").substringAfter("genre=").substringBefore("&"),
                name = it.text().trim(),
            )
        }
        val seasonNumber = title.substringAfter("Saison ", "").trim().substringBefore(" ")
            .toIntOrNull() ?: 1
        TvShow(
            id = id,
            title = cleanTitle,
            overview = overview,
            released = releaseYear,
            poster = poster,
            banner = poster,
            genres = genres,
            seasons = listOf(
                Season(
                    id = id,
                    number = seasonNumber,
                    title = "Saison $seasonNumber",
                    poster = poster,
                )
            ),
        )

        /*
        val document = service.getItem(id, "dle_skin=VFV25")
        val filmDataDeferred = async {
            try { service.getFilmData(itemId) } catch (_: Exception) { null }
        }

        val document = documentDeferred.await()
        val tvShowData = filmDataDeferred.await()
        val actors = extractActors(document)
        val seasonsData = try {
            service.getSeasonsData(tvShowData?.meta?.tagz ?: "")
        } catch (_: Exception) {
            null
        }

        val trailerURL = tvShowData ?.meta?.trailer
            ?.let { "https://www.youtube.com/watch?v=$it" }
        val poster = tvShowData ?.meta?.affiche
        val banner = tvShowData ?.meta?.affiche2
        val votes = document.selectFirst("div.fr-votes")
        val rating = if (votes != null) getRating(votes) else null
        val title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?: ""

        val seasonNumber = title.substringAfter("Saison ").trim().toIntOrNull() ?: 0

        val seasons = seasonsData
            ?.mapIndexed { idx, season ->
                Season(
                    id = season.id ?: idx.toString(),
                    number = season.title?.substringAfter("Saison ")?.toIntOrNull() ?: (idx + 1),
                    title = season.title ?: "Saison ${idx + 1}",
                    poster = season.affiche
                )
            }
            ?.toMutableList()
            ?: mutableListOf()

        if (seasons.none { it.number == seasonNumber }) {
            seasons.add(
                Season(
                    id = itemId,
                    number = seasonNumber,
                    title = if (title.contains("- Saison")) "Saison "+title.substringAfter("- Saison ") else title,
                    poster = poster
                )
            )
        }

        seasons.sortBy { it.number }

        val tvShow = TvShow(
            id = id,
            title = title.substringBeforeLast("- Saison"),
            overview = document.selectFirst("div.fdesc > p")
                ?.text()
                ?.trim()
                ?: "",
            released = document.selectFirst("span.release")
                ?.text()
                ?.substringBefore("-")
                ?.trim(),
            runtime = document.select("span.runtime")
                .text().substringAfter(" ").let {
                    val hours = it.substringBefore("h").toIntOrNull() ?: 0
                    val minutes =
                        it.substringAfter("h").trim().toIntOrNull() ?: 0
                    hours * 60 + minutes
                }.takeIf { it != 0 },
            quality = document.selectFirst("span[id=film_quality]")
                ?.text(),
            poster = poster,
            banner = banner,
            trailer = trailerURL,
            seasons = seasons,
            genres = document.select("span.genres").text().
                split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map {
                    Genre(
                        id = it,
                        name = it,
                    )
                },
            directors = document.select("ul#s-list li")
                .find {
                    it.selectFirst("span")?.text()?.contains("alisateur") == true
                }
                ?.select("a")?.mapIndexedNotNull { index, it ->
                    People(
                        id = "director$index",
                        name = it.text(),
                    )
                }
                ?: emptyList(),
            cast = actors.map {
                People(
                    id = it[0].replace(" ", "+"),
                    name = it[0],
                    image = it[1]
                )
            },
            rating = rating
        )

        */
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        initializeService()
        // 2026-05: AJAX endpoint /engine/ajax/episodes_np.php is gone (404). Episodes
        // are now embedded in the /serie/{slug} page's Next.js JSON. We parse the page
        // and extract every {episodeNumber, name, thumbnail, ...} block.
        return try {
            val raw = service.getTvShow(seasonId).html()
            parseEpisodesFromPage(raw, seasonId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Extract the episode list from a /serie/{slug} page's inline Next.js JSON.
     *  Episode block: {id, number, title, description, thumbnail, duration, players}.
     *  Description can be 500-2000 chars long, so the gap between title and thumbnail
     *  needs a wide span. We do TWO passes: pick number+title from one regex, then
     *  search for thumbnail starting from the same id within a wider window. */
    private fun parseEpisodesFromPage(raw: String, seasonId: String): List<Episode> {
        val out = mutableListOf<Episode>()
        val episodeRx = Regex(
            "\\\\\"id\\\\\":(\\d+)" +
                "[^}]{0,200}\\\\\"number\\\\\":(\\d+)" +
                "[^}]{0,200}\\\\\"title\\\\\":\\\\\"([^\\\\]*)\\\\\""
        )
        val seen = HashSet<Int>()
        episodeRx.findAll(raw).forEach { m ->
            val number = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (!seen.add(number)) return@forEach
            val title = m.groupValues[3]
            // Look for the thumbnail in the next ~3000 chars after the match.
            val from = m.range.last
            val window = raw.substring(from, (from + 3000).coerceAtMost(raw.length))
            val thumb = Regex("\\\\\"thumbnail\\\\\":(?:\\\\\"([^\\\\]+)\\\\\"|null)")
                .find(window)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.ifBlank { null }
            out.add(
                Episode(
                    id = "$seasonId/$number",
                    number = number,
                    title = title.ifBlank { "Épisode $number" },
                    poster = thumb?.let { tmdbImg(it) },
                )
            )
        }
        return out.sortedBy { it.number }
    }

    /** Pull a single string field from the Next.js streaming JSON inline in a page.
     *  Pattern: \\"<key>\\":\\"<value>\\". Returns null if not found or value is null. */
    private fun extractFromInlineJson(raw: String, key: String): String? {
        val m = Regex("\\\\\"$key\\\\\":\\\\\"([^\\\\]*)\\\\\"").find(raw) ?: return null
        return m.groupValues[1].ifBlank { null }
    }

    /** Pull a numeric field (e.g. \\"year\\":2026, \\"duration\\":53) from the inline JSON. */
    private fun extractNumberFromInlineJson(raw: String, key: String): Int? {
        return Regex("\\\\\"$key\\\\\":(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Build a TMDB image URL from a /path/to/img.jpg slug. */
    private fun tmdbImg(slug: String): String =
        if (slug.startsWith("http")) slug
        else "https://image.tmdb.org/t/p/w500$slug"

    /** Parse the Next.js inline JSON of a /film/{slug} or /serie/{slug} page and turn
     *  every {hostName, embedUrl, language} entry into a Video.Server.
     *
     *  For series, [forEpisodeNumber] filters: only players whose surrounding
     *  episodeNumber field matches the requested episode are returned. For movies,
     *  pass null and we take all players on the page.
     *
     *  Server names follow the existing extractor convention so the dispatcher in
     *  Extractor.extract() routes them (e.g. "fsvid" → FilemoonExtractor via aliasUrls). */
    private fun parsePlayersFromPage(raw: String, forEpisodeNumber: Int?): List<Video.Server> {
        if (raw.isBlank()) return emptyList()

        // Slice the page into per-episode segments when filtering. Each episode block
        // in the inline JSON is keyed by `\"number\":N` (e.g. {"id":...,"number":1,
        // "title":"Épisode 1",...}). We slice the text between successive `number`
        // markers so the regex below only matches players for the requested episode.
        val haystack: String = if (forEpisodeNumber != null) {
            val markerRegex = Regex("\\\\\"number\\\\\":(\\d+)")
            val markers = markerRegex.findAll(raw).toList()
            val target = markers.firstOrNull { it.groupValues[1].toIntOrNull() == forEpisodeNumber }
                ?: return emptyList()
            val nextStart = markers.firstOrNull { it.range.first > target.range.first }
                ?.range?.first ?: raw.length
            raw.substring(target.range.first, nextStart)
        } else raw

        // Each player block in the JSON: {"id":N,"hostName":"X","embedUrl":"Y","language":"Z",...}
        val rx = Regex(
            "\\\\\"hostName\\\\\":\\\\\"([^\\\\]+)\\\\\"" +
                "[^}]*?\\\\\"embedUrl\\\\\":\\\\\"([^\\\\]+)\\\\\"" +
                "(?:[^}]*?\\\\\"language\\\\\":(?:\\\\\"([^\\\\]*)\\\\\"|null))?"
        )

        val seen = HashSet<String>()
        val out = mutableListOf<Video.Server>()
        rx.findAll(haystack).forEachIndexed { i, m ->
            val host = m.groupValues[1].trim()
            // The HTML escapes "/" as "\\/" in some cases — clean it up.
            val embedUrl = m.groupValues[2].replace("\\/", "/").trim()
            val lang = m.groupValues.getOrNull(3)?.uppercase()?.takeIf { it.isNotBlank() }
            if (embedUrl.isBlank() || !seen.add(embedUrl)) return@forEachIndexed
            val niceHost = host.replaceFirstChar { it.uppercase() }
            val displayName = if (lang != null) "$niceHost ($lang)" else niceHost
            out.add(
                Video.Server(
                    id = "fs_player_$i",
                    name = displayName,
                    src = embedUrl,
                )
            )
        }
        return out
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService()
        // 2026-05: /films?genre=<slug> serves filtered listings. The same div.short cards
        // as the home page; we route /s-tv/ entries to TvShow as before.
        val document = service.getGenre(id, page)
        val shows = document.select("div.short").mapNotNull { item ->
            val link = item.selectFirst("a.short-poster")?.attr("href") ?: return@mapNotNull null
            val href = link.substringAfterLast("/")
            val title = item.selectFirst("div.short-title")?.text() ?: ""
            val poster = item.selectFirst("img")?.attr("src")
            if (link.contains("/s-tv/") || link.contains("/serie/") || link.contains("-saison-"))
                TvShow(id = href, title = title, poster = poster)
            else
                Movie(id = href, title = title, poster = poster)
        }
        return Genre(id = id, name = "", shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        // PATCH 2026-05: Provider suspended - fs14.lol migrated to Next.js CSR
        return People(id, "")

        /*
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
            filmography = document.select("div#dle-content > div.short").mapNotNull {
                val href = it.selectFirst("a.short-poster")
                    ?.attr("href")
                    ?: ""
                val id = href.substringAfterLast("/")

                val title = it.selectFirst("div.short-title")
                    ?.text()
                    ?: ""
                val poster = it.selectFirst("img")
                    ?.attr("src")
                    ?: ""

                if (href.contains("-saison-") || href.contains("s-tv/") || title.contains(" - Saison ")) {
                    TvShow(
                        id = id,
                        title = title,
                        poster = poster,
                    )
                } else if (href.isNotBlank()) {
                    Movie(
                        id = id,
                        title = title,
                        poster = poster,
                    )
                } else {
                    null
                }
            },
        )

        return people
        */
    }

    fun extractTvShowVersions(document: Document, episodesData: EpisodesData? = null): MutableList<String> {
        val versions = listOf("vostfr", "vf", "vo")
        val found = mutableListOf<String>()

        for (version in versions) {
            val hasAtLeastOneEpInJson = episodesData?.let {
                when (version) {
                    "vf" -> !it.vf.isNullOrEmpty()
                    "vostfr" -> !it.vostfr.isNullOrEmpty()
                    "vo" -> !it.vo.isNullOrEmpty()
                    else -> false
                }
            } ?: false

            if (hasAtLeastOneEpInJson) {
                found.add(version)
            }
        }

        return found
    }

    suspend fun extractActors(document: Document): List<List<String>> {
        val scriptContent = document.select("script").joinToString("\n") { it.data() }
        val arrayContentRegex = Regex("""actorData\s*=\s*\[(.*?)];""", RegexOption.DOT_MATCHES_ALL)

        return arrayContentRegex.find(scriptContent)
            ?.groupValues?.get(1)
            ?.let { content ->
                Regex(""""(.+?)\s*\(.*?\)\s*-\s*([^"]+)"""")
                    .findAll(content)
                    .map { match ->
                        val actorName = match.groupValues[1].trim()
                        val poster = match.groupValues[2].trim()
                        listOf(actorName, poster)
                    }
                    .toList()
            } ?: emptyList()
    }

    data class VideoProvider(
        val id: Number,
        val order: Int,
        val name: String,
        val lang: String,
        val url: String
    )

    data class EpisodesData(
        val vf: Map<String, Map<String, String>>? = null,
        val vostfr: Map<String, Map<String, String>>? = null,
        val vo: Map<String, Map<String, String>>? = null,
        val info: Map<String, EpisodeInfo>? = null
    )

    data class EpisodeInfo(
        val title: String? = null,
        val synopsis: String? = null,
        val poster: String? = null
    )

    data class FilmData(
        val players: Map<String, Map<String, String>>? = null,
        val meta: FilmMeta? = null
    )

    data class FilmMeta(
        val affiche: String? = null,
        val affiche2: String? = null,
        val trailer: String? = null,
        val tagz: String? = null,
        val bkp: String? = null
    )

    data class SeasonData(
        val affiche: String? = null,
        val alt_name: String? = null,
        val full_url: String? = null,
        val id: String? = null,
        val serie_annee: String? = null,
        val title: String? = null
    )

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()

        // 2026-05: AJAX endpoints (film_api.php, episodes_np.php) are dead. The new site
        // ships a Next.js streaming JSON inline in /film/{slug} and /serie/{slug} pages
        // with each player's embed URL. We fetch the page once and regex out the players.
        val servers = when (videoType) {
            is Video.Type.Episode -> {
                val parts = id.split("/")
                val tvShowId = parts.getOrElse(0) { id }
                val episodeNum = parts.getOrElse(1) { videoType.number.toString() }.toIntOrNull()
                    ?: videoType.number
                val raw = try { service.getTvShow(tvShowId).html() } catch (_: Exception) { "" }
                parsePlayersFromPage(raw, forEpisodeNumber = episodeNum)
            }

            is Video.Type.Movie -> {
                val raw = try { service.getMovie(id).html() } catch (_: Exception) { "" }
                parsePlayersFromPage(raw, forEpisodeNumber = null)
            }
        }

        // Sort: VF/TrueFrench first, then by service reliability, VOSTFR/VO last
        return servers.sortedWith(compareBy<Video.Server> { server ->
            val name = server.name.uppercase()
            when {
                name.contains("TRUEFRENCH") || name.contains("VFF") -> 0
                name.contains("VF") && !name.contains("VOSTFR") -> 1
                name.contains("FRENCH") && !name.contains("VOSTFR") -> 2
                name.contains("VOSTFR") -> 5
                name.contains("VO") -> 6
                else -> 3
            }
        }.thenBy { server ->
            val serviceName = Extractor.identifyServiceName(server.src)?.lowercase() ?: ""
            val sn = server.name.lowercase()
            when {
                serviceName == "vidara" || sn.contains("vidara") -> 1
                serviceName == "vidsonic" || sn.contains("vidsonic") -> 2
                serviceName == "rpmvid" || sn.contains("rpmvid") -> 3
                serviceName == "filemoon" || sn.contains("filemoon") -> 4
                serviceName == "uqload" || sn.contains("uqload") -> 15
                else -> 5
            }
        })
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val finalUrl = if (server.src.contains("kokoflix.lol", ignoreCase = true)) {
            val response = service.getRedirectLink(server.src)
                .let { response -> response.raw() as okhttp3.Response }
            response.request.url.toString()
        } else {
            server.src
        }

        val video = Extractor.extract(finalUrl)

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
                val addressService = Service.buildAddressFetcher()
                try {
                    val document = addressService.getHome()

                    val newUrl = document.select("div.container > div.url-card")
                        .selectFirst("a")
                        ?.attr("href")
                        ?.trim()
                    if (!newUrl.isNullOrEmpty()) {
                        val newUrl = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
                        UserPreferences.setProviderCache(this,UserPreferences.PROVIDER_URL, newUrl)
                        UserPreferences.setProviderCache(
                            this,
                            UserPreferences.PROVIDER_LOGO,
                            newUrl + "favicon-96x96.png"
                        )
                    }
                } catch (e: Exception) {
                    // In case of failure, we'll use the default URL
                    // No need to throw as we already have a fallback URL
                }
            }
            service = Service.build(baseUrl)
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
                .dns(DnsResolver.doh)
                // Preserve POST method & body on redirects (site may change domain)
                .followRedirects(false)
                .addInterceptor { chain ->
                    var request = chain.request()
                    var response = chain.proceed(request)
                    var redirects = 0
                    while (response.isRedirect && redirects < 5) {
                        val location = response.header("Location") ?: break
                        val newUrl = request.url.resolve(location) ?: break
                        response.close()
                        request = request.newBuilder().url(newUrl).build()
                        response = chain.proceed(request)
                        redirects++
                    }
                    response
                }
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
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        @FormUrlEncoded
        @POST(".")
        suspend fun postHome(
            @Field("skin_name") skinName: String = "VFV25",
            @Field("action_skin_change") actionSkinChange: String = "yes",
            @Header("Cookie") cookie: String = "dle_skin=VFV25"
        ): Document

        @FormUrlEncoded
        @POST("engine/ajax/search.php")
        suspend fun search(
            @Field("query") query: String,
            @Field("page") page: Int = 1
        ): Document

        // 2026-05: catalog routes simplified — /films and /series replace /films/page/N
        // and /s-tv/page/N. Pagination is now via ?page=N query.
        @GET("films")
        suspend fun getMovies(@Query("page") page: Int): Document

        @GET("series")
        suspend fun getTvShows(@Query("page") page: Int): Document

        @GET("/{id}")
        suspend fun getItem(
            @Path("id") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        // Detail pages — slugs now live under /film/<slug> and /serie/<slug>.
        @GET("film/{id}")
        suspend fun getMovie(
            @Path("id") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        @GET("serie/{id}")
        suspend fun getTvShow(
            @Path("id") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document


        // Genre listing is now /films?genre=<slug> (and /series?genre=<slug>); the
        // separate "/film-en-streaming/X/page/Y" route is gone.
        @GET("films")
        suspend fun getGenre(
            @Query("genre") genre: String,
            @Query("page") page: Int,
        ): Document

        @GET("xfsearch/actors/{id}/page/{page}")
        suspend fun getPeople(
            @Path("id") id: String,
            @Path("page") page: Int,
        ): Document

        @GET("engine/ajax/episodes_np.php")
        suspend fun getEpisodesData(
            @Query("id") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1",
            @Header("X-Requested-With") requestedWith: String = "XMLHttpRequest"
        ): EpisodesData

        @GET("engine/ajax/film_api.php")
        suspend fun getFilmData(
            @Query("id") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1",
            @Header("X-Requested-With") requestedWith: String = "XMLHttpRequest"
        ): FilmData

        @POST("engine/ajax/get_seasons.php")
        @FormUrlEncoded
        suspend fun getSeasonsData(
            @Field("serie_tag") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1",
            @Header("X-Requested-With") requestedWith: String = "XMLHttpRequest"
        ): List<SeasonData>

        @GET
        suspend fun getRedirectLink(@Url url: String): Response<ResponseBody>
    }
}
