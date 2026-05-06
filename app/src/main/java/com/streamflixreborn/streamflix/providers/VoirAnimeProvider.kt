package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
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
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_voiranime"

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
                val popularFilmsDeferred = async {
                    try { service.getPage("${baseUrl}liste-danimes/?filter=dubbed&m_orderby=views") } catch (_: Exception) { null }
                }
                val topRatedDeferred = async {
                    try { service.getPage("${baseUrl}anime/?m_orderby=rating") } catch (_: Exception) { null }
                }

                val document = homeDeferred.await()
                val categories = mutableListOf<Category>()

                // 1. En cours
                val allItems = document.select(".page-item-detail").mapNotNull { item ->
                    parseHomeItem(item)
                }
                if (allItems.isNotEmpty()) {
                    // FEATURED carousel — keep poster as a SAFE fallback for banner
                    // so the carousel slot is never empty. TMDb enrichment below
                    // will overwrite with a proper landscape backdrop when a match
                    // exists; a stretched poster still looks better than an empty
                    // "not found" placeholder.
                    val featuredItems = allItems.take(10).map { item ->
                        when (item) {
                            is Movie -> item.copy(banner = item.banner ?: item.poster)
                            is TvShow -> item.copy(banner = item.banner ?: item.poster)
                            else -> item
                        }
                    }
                    // Enhance banners with TMDB HD backdrops in parallel — sequential
                    // was slow enough to stall the home for several seconds while
                    // featuredItems resolved one-by-one. async + awaitAll keeps the
                    // home fast, and any individual lookup failure is silently
                    // swallowed so a slow TMDb call doesn't block the others.
                    if (UserPreferences.enableTmdb) {
                        // Prefer Japanese / Korean / Chinese matches first (real anime
                        // backdrops), but accept any match as a fallback so titles
                        // licensed by Western studios (Netflix originals, etc.) still
                        // get a proper backdrop instead of the portrait poster.
                        val animeLanguages = setOf("ja", "ko", "zh")
                        coroutineScope {
                            featuredItems.map { item ->
                                async {
                                    try {
                                        val title = when (item) {
                                            is Movie -> item.title
                                            is TvShow -> item.title
                                            else -> null
                                        } ?: return@async
                                        // 2026-05-05 : normalise (apostrophe typo, annotations VF/saison) avant TMDB
                                        val normalized = com.streamflixreborn.streamflix.utils.TitleNormalizer.cleanForTmdbSearch(title)
                                        val results = TMDb3.Search.multi(normalized.ifBlank { title })
                                        val animeMatch = results.results.firstOrNull { result ->
                                            when (result) {
                                                is TMDb3.Movie -> result.originalLanguage in animeLanguages && result.backdropPath != null
                                                is TMDb3.Tv -> result.originalLanguage in animeLanguages && result.backdropPath != null
                                                else -> false
                                            }
                                        }
                                        val anyMatch = animeMatch ?: results.results.firstOrNull { result ->
                                            when (result) {
                                                is TMDb3.Movie -> result.backdropPath != null
                                                is TMDb3.Tv -> result.backdropPath != null
                                                else -> false
                                            }
                                        }
                                        val banner = when (anyMatch) {
                                            is TMDb3.Movie -> anyMatch.backdropPath?.original
                                            is TMDb3.Tv -> anyMatch.backdropPath?.original
                                            else -> null
                                        }
                                        if (banner != null) {
                                            when (item) {
                                                is Movie -> item.banner = banner
                                                is TvShow -> item.banner = banner
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }.awaitAll()
                        }
                    }
                    categories.add(Category(name = Category.FEATURED, list = featuredItems))
                    categories.add(Category(name = "En cours", list = allItems))
                }

                // 2. Nouveau
                val recentDoc = recentDeferred.await()
                if (recentDoc != null) {
                    val recentShows = recentDoc.select(".page-item-detail").mapNotNull { item ->
                        parseHomeItem(item)
                    }
                    if (recentShows.isNotEmpty()) {
                        categories.add(Category(name = "Nouveau", list = recentShows))
                    }
                }

                // 3. Animes Populaires (par vues)
                val popularDoc = popularDeferred.await()
                if (popularDoc != null) {
                    val popularShows = popularDoc.select(".page-item-detail").mapNotNull { item ->
                        parseHomeItem(item)
                    }
                    if (popularShows.isNotEmpty()) {
                        categories.add(Category(name = "Animes Populaires", list = popularShows))
                    }
                }

                // 4. Films Populaires (VF, triés par vues)
                val popularFilmsDoc = popularFilmsDeferred.await()
                if (popularFilmsDoc != null) {
                    val popularFilms = popularFilmsDoc.select(".page-item-detail").mapNotNull { item ->
                        val show = parseHomeItem(item) ?: return@mapNotNull null
                        when (show) {
                            is TvShow -> Movie(id = show.id, title = show.title, poster = show.poster).apply { isSeries = true }
                            is Movie -> show
                            else -> null
                        }
                    }
                    if (popularFilms.isNotEmpty()) {
                        categories.add(Category(name = "Films Populaires", list = popularFilms))
                    }
                }

                // 5. Mieux Notés (par note)
                val topRatedDoc = topRatedDeferred.await()
                if (topRatedDoc != null) {
                    val topRated = topRatedDoc.select(".page-item-detail").mapNotNull { item ->
                        parseHomeItem(item)
                    }
                    if (topRated.isNotEmpty()) {
                        categories.add(Category(name = "Mieux Notés", list = topRated))
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

    override suspend fun getMovies(page: Int): List<Movie> = coroutineScope {
        // Onglet "FR" — contenu VF via liste-danimes/?filter=dubbed
        initializeService()
        try {
            val url = if (page > 1) "${baseUrl}liste-danimes/page/$page/?filter=dubbed"
                      else "${baseUrl}liste-danimes/?filter=dubbed"
            val document = service.getPage(url)
            val items = document.select(".page-item-detail").mapNotNull { item ->
                val show = parseHomeItem(item) ?: return@mapNotNull null
                val id = when (show) { is Movie -> show.id; is TvShow -> show.id; else -> return@mapNotNull null }
                val title = when (show) { is Movie -> show.title; is TvShow -> show.title; else -> return@mapNotNull null }
                val poster = when (show) { is Movie -> show.poster; is TvShow -> show.poster; else -> return@mapNotNull null }
                Triple(id, title, poster)
            }

            // Vérifier chaque page détail en parallèle (batches de 5)
            // >1 épisode = série, ≤1 épisode = film
            items.chunked(5).flatMap { batch ->
                batch.map { (id, title, poster) ->
                    async {
                        val hasMultipleEpisodes = try {
                            val detailDoc = service.getPage("${baseUrl}anime/$id/")
                            detailDoc.select("li.wp-manga-chapter").size > 1
                        } catch (e: Exception) { false }
                        Movie(id = id, title = title, poster = poster).apply { isSeries = hasMultipleEpisodes }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            Log.e("VoirAnimeProvider", "getMovies error: ", e)
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = coroutineScope {
        // Onglet "VOSTFR" — via liste-danimes/?filter=subbed
        initializeService()
        try {
            val url = if (page > 1) "${baseUrl}liste-danimes/page/$page/?filter=subbed"
                      else "${baseUrl}liste-danimes/?filter=subbed"
            val document = service.getPage(url)
            val items = document.select(".page-item-detail").mapNotNull { item ->
                val show = parseHomeItem(item) ?: return@mapNotNull null
                val id = when (show) { is Movie -> show.id; is TvShow -> show.id; else -> return@mapNotNull null }
                val title = when (show) { is Movie -> show.title; is TvShow -> show.title; else -> return@mapNotNull null }
                val poster = when (show) { is Movie -> show.poster; is TvShow -> show.poster; else -> return@mapNotNull null }
                Triple(id, title, poster)
            }

            // Vérifier chaque page détail en parallèle (batches de 5)
            items.chunked(5).flatMap { batch ->
                batch.map { (id, title, poster) ->
                    async {
                        val hasMultipleEpisodes = try {
                            val detailDoc = service.getPage("${baseUrl}anime/$id/")
                            detailDoc.select("li.wp-manga-chapter").size > 1
                        } catch (e: Exception) { true }
                        TvShow(id = id, title = title, poster = poster).apply { isMovie = !hasMultipleEpisodes }
                    }
                }.awaitAll()
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
            val seenSrcs = mutableSetOf<String>()

            // 1) Parse the JS `thisChapterSources` map — the page stores ALL
            //    available players (LECTEUR MOON, SB, VOE, Stape, mail.ru,
            //    YourUpload, …) in this map and only displays the first one
            //    in an iframe. Without this, we miss 6/7 of the working
            //    players and fall back to a single dead vidmoly link.
            //    We use the RAW HTML (outerHtml) — Jsoup's pretty-printed
            //    document.html() may already collapse the JS `\"` escapes.
            val html = document.outerHtml()
            // Find ALL "LECTEUR X" : "<iframe ... src=...XXX..." patterns.
            // Tolerant to:
            //   - escaped quotes (`\"`) and forward slashes (`\/`)
            //   - extra whitespace
            //   - single OR double quotes around src
            //   - any iframe attribute order
            // The JS value is `"<iframe src=\"https:\/\/host\/...\"…"`.
            // Two traps:
            //  (a) the value contains escaped quotes `\"`, so a naive lazy
            //      match `[^"]*?` stops at the first `"` and the regex fails;
            //      use a JS-aware lazy match `(?:[^"\\]|\\.)*?`.
            //  (b) the URL itself contains `\/` (escaped slashes) but is
            //      terminated by `\"` (the closing iframe-src quote). If we
            //      allow ANY escape (`\\.`) inside the URL group, the
            //      trailing `\"` gets sucked into the URL → every extractor
            //      gets a URL with a literal `"` at the end and 404s/fails.
            //      So we ONLY allow `\/` inside the URL group.
            val srcRegex = Regex(
                "\"([^\"]*LECTEUR[^\"]*)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*?src=\\\\?[\"']?(https?:(?:[^\"'\\\\\\s]|\\\\/)+)",
                RegexOption.IGNORE_CASE
            )
            srcRegex.findAll(html).forEach { match ->
                val rawName = match.groupValues[1].trim()
                val rawSrc = match.groupValues[2]
                    .replace("\\/", "/")
                    .replace("\\\"", "\"")
                    .takeIf { it.startsWith("http") } ?: return@forEach
                if (!seenSrcs.add(rawSrc)) return@forEach
                val hostShort = try {
                    java.net.URL(rawSrc).host.split(".").first { it != "www" }
                        .replaceFirstChar { it.uppercase() }
                } catch (_: Exception) { "Lecteur" }
                val displayName = "$rawName ($hostShort)"
                servers.add(Video.Server(id = rawSrc, name = displayName, src = rawSrc))
                // Log.w (preserved by R8 in release) so we can diagnose on
                // production devices via `adb logcat | grep VoirAnime`.
                Log.w("VoirAnimeProvider", "JS source matched: $rawName -> $rawSrc")
            }
            Log.w("VoirAnimeProvider", "After JS parse: ${servers.size} servers")

            // 2) Fallback / fill-in from the DOM iframes (catches pages that
            //    don't have thisChapterSources, e.g. older episodes or movies).
            document.select(".chapter-video-frame iframe, .reading-content iframe, .entry-content iframe, iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() && it.startsWith("http") } ?: return@forEach
                if (!seenSrcs.add(src)) return@forEach
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

            // Sort by host reliability so the auto-play tries proven hosts
            // first. Order chosen with the user after testing: vidmoly first
            // (when it works it's the smoothest), VOE / Streamtape next as
            // proven fallbacks, MOON (Filemoon/weneverbeenfree) fourth — now
            // that the Byse-Frontend headers are wired in — then the rest.
            val priority = mapOf(
                "vidmoly" to 0,
                "voe" to 1, "voe.sx" to 1,
                "streamtape" to 2,
                "filemoon" to 3, "weneverbeenfree" to 3,
                "yourupload" to 4, "www.yourupload.com" to 4,
                "mail.ru" to 5, "my.mail.ru" to 5,
                "streamhide" to 6
            )
            val withLang = servers.map { server ->
                if (!server.name.contains("VF") && !server.name.contains("VOSTFR") && !server.name.contains("VO")) {
                    Video.Server(id = server.id, name = "${server.name} ($lang)", src = server.src)
                } else server
            }.distinctBy { it.id }
            val sorted = withLang.sortedBy { server ->
                val host = try { java.net.URL(server.src).host.lowercase() } catch (_: Exception) { "" }
                priority.entries.firstOrNull { host.contains(it.key) }?.value ?: 50
            }

            // 2026-05-05 : Moviebox backup pour les animes (gros catalogue d'anime
            // VOSTFR/VF dispo sur Moviebox).
            val showTitle = document.selectFirst("h1.entry-title")?.text()?.trim()
                ?.replace(Regex("""\s*VOSTFR|\s*VF$|\s*\(\d{4}\)""", RegexOption.IGNORE_CASE), "")
                ?.trim()
            val movieboxBackup = if (!showTitle.isNullOrBlank()) {
                try {
                    val type = if (videoType is Video.Type.Movie) 1 else 2
                    MovieboxProvider.getMovieboxSourcesByTitle(
                        showTitle, null, type,
                        seasonNumber = if (videoType is Video.Type.Episode) videoType.season.number else null,
                        episodeNumber = if (videoType is Video.Type.Episode) videoType.number else null,
                    )
                        .also { if (it.isNotEmpty()) Log.d("VoirAnimeProvider", "+ Moviebox: ${it.size}") }
                } catch (_: Exception) { emptyList() }
            } else emptyList()

            sorted + movieboxBackup
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

    /**
     * Parse un item de la page d'accueil ou de genre (.page-item-detail).
     *
     * Détection film vs série :
     *   1) Badge Madara type (.manga-type-badge, .type-label, span[class*=type]) : "MOVIE","TV","OVA"…
     *   2) Genres dans l'item (.mg_genres a, .genres a, .post-on span) : contient "Movie"/"Film"
     *   3) Nombre de chapitres listés : 0-1 = Movie, >1 = TvShow
     *   4) Titre contient "(Film)" ou "(Movie)"
     */
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

        // --- Détection film vs série ---

        // 1) Badge de type Madara (span contenant MOVIE, TV, OVA, ONA, SPECIAL, TV SHORT…)
        val typeBadge = element.selectFirst(".manga-type-badge, .type-label, span[class*=type]")
            ?.text()?.trim()?.uppercase()

        // 2) Texte de tous les genres / labels dans l'item
        val genreTexts = element.select(".mg_genres a, .genres a, .post-on span, .item-summary .font-meta")
            .joinToString(" ") { it.text() }.lowercase()

        // 3) Nombre de chapitres — essayer plusieurs sélecteurs Madara
        val chapterItems = listChapter?.select(".chapter-item")
            ?.ifEmpty { element.select(".listing-chapters_wrap .wp-manga-chapter") }
            ?.ifEmpty { element.select("li.wp-manga-chapter") }
            ?: emptyList()
        val chapterCount = chapterItems.size

        // 4) Texte d'épisode — récupérer le texte du 1er chapitre listé
        val firstChapterText = (chapterItems.firstOrNull()?.selectFirst("a")?.text()
            ?: chapterItems.firstOrNull()?.text())?.trim()?.lowercase() ?: ""

        val isMovie = when {
            // Badge de type explicite
            typeBadge == "MOVIE" || typeBadge == "FILM" -> true
            typeBadge == "TV" || typeBadge == "TV SHORT" || typeBadge == "OVA" || typeBadge == "ONA" || typeBadge == "SPECIAL" -> false
            // Genres contiennent "movie" ou "film"
            genreTexts.contains("movie") || genreTexts.contains("film") -> true
            // Titre contient "(Film)" ou "(Movie)"
            title.contains("(Film)", ignoreCase = true) || title.contains("(Movie)", ignoreCase = true) -> true
            // Texte de chapitre contient "film" ou "movie"
            firstChapterText.contains("film") || firstChapterText.contains("movie") -> true
            // Texte de chapitre contient "episode" ou "épisode" → série
            firstChapterText.contains("episode") || firstChapterText.contains("épisode") || firstChapterText.contains("saison") -> false
            // Fallback : nombre de chapitres
            chapterCount > 1 -> false   // Plusieurs épisodes = série
            chapterCount == 1 -> true   // 1 seul chapitre = probablement un film
            else -> false               // Pas d'info chapitres = série par défaut
        }

        Log.d("VoirAnime_Parse", "id=$id title=$title type=${if (isMovie) "MOVIE" else "TVSHOW"} badge=$typeBadge genres='$genreTexts' chapters=$chapterCount firstCh='$firstChapterText' listChapter=${listChapter != null}")

        return if (isMovie) {
            Movie(id = id, title = title, poster = poster)
        } else {
            TvShow(id = id, title = title, poster = poster)
        }
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

        @GET(".")
        suspend fun searchFiltered(
            @Query("s") query: String = "",
            @Query("post_type") postType: String = "wp-manga",
            @Query("type") type: String,
            @Query("language") language: String,
            @Query("m_orderby") orderBy: String = "views",
            @Query("paged") page: Int = 1
        ): Document
    }
}
