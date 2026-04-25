package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

object VoirAnimeProvider : Provider, ProviderConfigUrl {

    override val name = "VoirAnime"

    override val defaultBaseUrl: String = "https://voir-anime.to/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }

    override val logo: String
        get() = "${baseUrl}wp-content/uploads/2019/12/vato.png"

    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: VoirAnimeService
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    // ==================== HOME ====================

    override suspend fun getHome(): List<Category> {
        initializeService()
        return try {
            coroutineScope {
                val homeDeferred = async { service.getPage(baseUrl) }
                val recentDeferred = async {
                    try { service.getPage("${baseUrl}nouveaux-ajouts/") } catch (_: Exception) { null }
                }
                val popularDeferred = async {
                    try { service.getPage("${baseUrl}anime/?m_orderby=views") } catch (_: Exception) { null }
                }

                val document = homeDeferred.await()
                val categories = mutableListOf<Category>()

                val allItems = document.select(".page-item-detail").mapNotNull { item ->
                    parseHomeItem(item)
                }

                if (allItems.isNotEmpty()) {
                    val bannerItems = allItems.take(10).map { show ->
                        when (show) {
                            is Movie -> Movie(
                                id = show.id, title = show.title,
                                banner = show.poster, poster = show.poster ?: ""
                            )
                            is TvShow -> TvShow(
                                id = show.id, title = show.title,
                                banner = show.poster, poster = show.poster ?: ""
                            )
                            else -> show
                        }
                    }
                    categories.add(Category(name = Category.FEATURED, list = bannerItems))
                    categories.add(Category(name = "En cours", list = allItems))
                }

                val recentDoc = recentDeferred.await()
                if (recentDoc != null) {
                    val recentShows = recentDoc.select(".page-item-detail").mapNotNull { item ->
                        parseHomeItem(item)
                    }
                    if (recentShows.isNotEmpty()) {
                        categories.add(Category(name = "Nouveau", list = recentShows))
                    }
                }

                val popularDoc = popularDeferred.await()
                if (popularDoc != null) {
                    val popularShows = popularDoc.select(".page-item-detail").mapNotNull { item ->
                        parseHomeItem(item)
                    }
                    if (popularShows.isNotEmpty()) {
                        categories.add(Category(name = "Animes Populaires", list = popularShows))
                    }
                }

                categories
            }
        } catch (e: Exception) {
            Log.e("VoirAnimeProvider", "getHome error: ", e)
            emptyList()
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            return listOf(
                Genre(id = "action", name = "Action"),
                Genre(id = "adventure", name = "Adventure"),
                Genre(id = "chinese", name = "Chinese"),
                Genre(id = "comedy", name = "Comedy"),
                Genre(id = "drama", name = "Drama"),
                Genre(id = "ecchi", name = "Ecchi"),
                Genre(id = "fantasy", name = "Fantasy"),
                Genre(id = "horror", name = "Horror"),
                Genre(id = "mahou-shoujo", name = "Mahou Shoujo"),
                Genre(id = "mecha", name = "Mecha"),
                Genre(id = "music", name = "Music"),
                Genre(id = "mystery", name = "Mystery"),
                Genre(id = "psychological", name = "Psychological"),
                Genre(id = "romance", name = "Romance"),
                Genre(id = "sci-fi", name = "Sci-Fi"),
                Genre(id = "slice-of-life", name = "Slice of Life"),
                Genre(id = "sports", name = "Sports"),
                Genre(id = "supernatural", name = "Supernatural"),
                Genre(id = "thriller", name = "Thriller"),
            )
        }

        initializeService()
        return try {
            val document = service.search(query, page)
            parseSearchResults(document)
        } catch (e: Exception) {
            Log.e("VoirAnimeProvider", "search error: ", e)
            emptyList()
        }
    }

    // ==================== MOVIES / TV SHOWS ====================

    override suspend fun getMovies(page: Int): List<Movie> {
        // Onglet "Série FR" — contenu VF via liste-danimes/?filter=dubbed
        initializeService()
        return try {
            val url = if (page > 1) "${baseUrl}liste-danimes/page/$page/?filter=dubbed"
                      else "${baseUrl}liste-danimes/?filter=dubbed"
            val document = service.getPage(url)
            document.select(".page-item-detail").mapNotNull { item ->
                val show = parseHomeItem(item) ?: return@mapNotNull null
                when (show) {
                    is TvShow -> Movie(id = show.id, title = show.title, poster = show.poster)
                    is Movie -> show
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.e("VoirAnimeProvider", "getMovies error: ", e)
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        // Onglet "Série VOSTFR" — via liste-danimes/?filter=subbed
        initializeService()
        return try {
            val url = if (page > 1) "${baseUrl}liste-danimes/page/$page/?filter=subbed"
                      else "${baseUrl}liste-danimes/?filter=subbed"
            val document = service.getPage(url)
            document.select(".page-item-detail").mapNotNull { item ->
                val show = parseHomeItem(item) ?: return@mapNotNull null
                when (show) {
                    is TvShow -> show
                    is Movie -> TvShow(id = show.id, title = show.title, poster = show.poster)
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.e("VoirAnimeProvider", "getTvShows error: ", e)
            emptyList()
        }
    }

    // ==================== DETAIL ====================

    override suspend fun getMovie(id: String): Movie {
        initializeService()
        val url = if (id.startsWith("http")) id else "${baseUrl}anime/$id/"
        val document = service.getPage(url)

        val title = document.selectFirst(".post-title h1, .post-title h3")
            ?.text()?.trim() ?: ""
        val poster = improveImageUrl(
            document.selectFirst(".summary_image img")?.attr("src")
        )
        val overview = document.selectFirst(".description-summary .summary__content")
            ?.text()?.trim()

        val genres = document.select(".genres-content a").map {
            Genre(
                id = it.attr("href").trimEnd('/').substringAfterLast("/"),
                name = it.text().trim()
            )
        }

        val rating = document.selectFirst(".score.font-meta.total_votes")
            ?.text()?.trim()?.toDoubleOrNull()

        val released = getInfoValue(document, "Start date")
            ?: getInfoValue(document, "Année")

        val cast = document.select(".artist-content a").mapIndexed { index, el ->
            People(id = "cast$index", name = el.text().trim())
        }

        return Movie(
            id = id,
            title = title,
            poster = poster,
            overview = overview,
            genres = genres,
            rating = rating,
            released = released,
            cast = cast
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        initializeService()
        val url = if (id.startsWith("http")) id else "${baseUrl}anime/$id/"
        val document = service.getPage(url)

        val title = document.selectFirst(".post-title h1, .post-title h3")
            ?.text()?.trim() ?: ""
        val poster = improveImageUrl(
            document.selectFirst(".summary_image img")?.attr("src")
        )
        val overview = document.selectFirst(".description-summary .summary__content")
            ?.text()?.trim()

        val genres = document.select(".genres-content a").map {
            Genre(
                id = it.attr("href").trimEnd('/').substringAfterLast("/"),
                name = it.text().trim()
            )
        }

        val rating = document.selectFirst(".score.font-meta.total_votes")
            ?.text()?.trim()?.toDoubleOrNull()

        val released = getInfoValue(document, "Start date")
            ?: getInfoValue(document, "Année")

        val cast = document.select(".artist-content a").mapIndexed { index, el ->
            People(id = "cast$index", name = el.text().trim())
        }

        val episodes = document.select("li.wp-manga-chapter")

        val season = Season(
            id = id,
            number = 1,
            title = title,
            poster = poster
        )

        return TvShow(
            id = id,
            title = title,
            poster = poster,
            overview = overview,
            genres = genres,
            rating = rating,
            released = released,
            cast = cast,
            seasons = if (episodes.isNotEmpty()) listOf(season) else emptyList()
        )
    }

    // ==================== EPISODES ====================

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        initializeService()
        val url = if (seasonId.startsWith("http")) seasonId else "${baseUrl}anime/$seasonId/"
        val document = service.getPage(url)

        // Récupérer le poster de la série pour l'utiliser comme thumbnail des épisodes
        val showPoster = improveImageUrl(
            document.selectFirst(".summary_image img")?.attr("src")
        )

        val episodeElements = document.select("li.wp-manga-chapter")

        return episodeElements.reversed().mapIndexed { index, el ->
            val link = el.selectFirst("a")
            val epUrl = link?.attr("href") ?: ""
            val epTitle = link?.text()?.trim() ?: "Épisode ${index + 1}"
            val epId = epUrl.trimEnd('/').substringAfter("/anime/").trimEnd('/')
                .takeIf { it.isNotBlank() }
                ?: "$seasonId/ep-${index + 1}"

            val epNumber = Regex("""[\s-]+(\d+)\s*(?:VOSTFR|VF|VO)""", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\s*$""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)

            Episode(
                id = epId,
                number = epNumber,
                title = epTitle,
                poster = showPoster
            )
        }
    }

    // ==================== GENRE ====================

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService()
        return try {
            val url = if (page > 1) "${baseUrl}anime-genre/$id/page/$page/"
                      else "${baseUrl}anime-genre/$id/"
            val document = service.getPage(url)
            val shows = document.select(".page-item-detail").mapNotNull { item ->
                parseHomeItem(item)
            }
            Genre(
                id = id,
                name = id.replace("-", " ").replaceFirstChar { it.uppercase() },
                shows = shows
            )
        } catch (e: Exception) {
            Log.e("VoirAnimeProvider", "getGenre error: ", e)
            Genre(id = id, name = id, shows = emptyList())
        }
    }

    // ==================== SERVERS / VIDEO ====================

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()
        return try {
            val url = when (videoType) {
                is Video.Type.Movie -> if (id.startsWith("http")) id else "${baseUrl}anime/$id/"
                is Video.Type.Episode -> if (id.startsWith("http")) id else "${baseUrl}anime/$id/"
            }
            val document = service.getPage(url)

            val servers = mutableListOf<Video.Server>()

            document.select(".chapter-video-frame iframe, .reading-content iframe, .entry-content iframe, iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() && it.startsWith("http") } ?: return@forEach
                val serverName = try {
                    java.net.URL(src).host.split(".").first { it != "www" }
                        .replaceFirstChar { it.uppercase() }
                } catch (_: Exception) { "Lecteur" }
                servers.add(Video.Server(id = src, name = serverName, src = src))
            }

            val pageTitle = document.title().lowercase()
            val pageUrl = url.lowercase()
            val lang = when {
                pageTitle.contains("vostfr") || pageUrl.contains("vostfr") -> "VOSTFR"
                pageTitle.contains("-vf") || pageUrl.contains("-vf") -> "VF"
                pageTitle.contains("vo ") || pageUrl.contains("-vo-") -> "VO"
                else -> "VOSTFR"
            }

            servers.map { server ->
                if (!server.name.contains("VF") && !server.name.contains("VOSTFR") && !server.name.contains("VO")) {
                    Video.Server(id = server.id, name = "${server.name} ($lang)", src = server.src)
                } else server
            }.distinctBy { it.id }
        } catch (e: Exception) {
            Log.e("VoirAnimeProvider", "getServers error: ", e)
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val video = Extractor.extract(server.src)
        if (server.name.contains("VOSTFR")) {
            video.subtitles.forEach { sub ->
                if (sub.initialDefault) {
                    sub.default = true
                }
            }
        } else if (video.subtitles.isNotEmpty() && !server.name.contains("VO")) {
            return video.copy(subtitles = emptyList())
        }
        return video
    }

    // ==================== PEOPLE ====================

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = id)
    }

    // ==================== URL CONFIG ====================

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            service = VoirAnimeService.build(baseUrl)
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

    // ==================== HELPERS ====================

    private fun improveImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // Remplacer le suffixe de redimensionnement WordPress par -193x278 (meilleure qualité dispo)
        return url.replace(Regex("""-\d+x\d+\."""), "-193x278.")
    }

    private fun getInfoValue(document: Document, label: String): String? {
        return document.select(".post-content_item").firstOrNull { item ->
            item.selectFirst(".summary-heading h5")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        }?.selectFirst(".summary-content")?.text()?.trim()
    }

    private fun parseHomeItem(element: Element): Show? {
        val thumb = element.selectFirst(".item-thumb")
        val summary = element.selectFirst(".item-summary")

        val titleLink = summary?.selectFirst("h3 a")
            ?: thumb?.selectFirst("a")
            ?: return null

        val href = titleLink.attr("href").takeIf { it.isNotBlank() } ?: return null

        val id = href.trimEnd('/').substringAfter("/anime/").trimEnd('/')
            .takeIf { it.isNotBlank() && !it.contains("://") }
            ?: href.trimEnd('/').substringAfterLast("/").takeIf { it.isNotBlank() }
            ?: return null

        val title = titleLink.text().trim().takeIf { it.isNotBlank() }
            ?: titleLink.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null

        // Filtrer les items sans contenu (pas d'épisodes/chapitres)
        val listChapter = element.selectFirst(".list-chapter")
        if (listChapter != null && listChapter.select(".chapter-item").isEmpty()) return null

        val img = thumb?.selectFirst("img") ?: element.selectFirst("img")
        val rawPoster = img?.let {
            it.attr("data-src").ifEmpty {
                it.attr("src")
            }
        }?.takeIf { it.isNotBlank() && it.startsWith("http") }
        val poster = improveImageUrl(rawPoster)

        return TvShow(id = id, title = title, poster = poster)
    }

    private fun parseSearchResults(document: Document): List<AppAdapter.Item> {
        return document.select(".c-tabs-item__content").mapNotNull { item ->
            val link = item.selectFirst(".post-title a, h3 a, h4 a, a[href*=/anime/]")
                ?: return@mapNotNull null

            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val id = href.trimEnd('/').substringAfter("/anime/").trimEnd('/')
                .takeIf { it.isNotBlank() && !it.contains("://") }
                ?: href.trimEnd('/').substringAfterLast("/").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val title = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val img = item.selectFirst("img")
            val rawPoster = img?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }?.takeIf { it.isNotBlank() && it.startsWith("http") }
            val poster = improveImageUrl(rawPoster)

            TvShow(id = id, title = title, poster = poster) as AppAdapter.Item
        }.distinctBy { (it as? TvShow)?.id ?: (it as? Movie)?.id }
    }

    // ==================== SERVICE ====================

    private interface VoirAnimeService {
        companion object {
            private val client = NetworkClient.default.newBuilder().build()

            fun build(baseUrl: String): VoirAnimeService {
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(VoirAnimeService::class.java)
            }
        }

        @GET
        suspend fun getPage(@Url url: String): Document

        @GET(".")
        suspend fun search(
            @Query("s") query: String,
            @Query("page") page: Int = 1,
            @Query("post_type") postType: String = "wp-manga"
        ): Document
    }
}
