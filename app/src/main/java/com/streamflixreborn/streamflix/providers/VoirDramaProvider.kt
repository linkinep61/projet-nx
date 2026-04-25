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
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

object VoirDramaProvider : Provider, ProviderConfigUrl {

    override val name = "VoirDrama"

    override val defaultBaseUrl: String = "https://voirdrama.to/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }

    override val logo: String
        get() = "${baseUrl}wp-content/uploads/2022/07/voirdrama-logo.png"

    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: VoirDramaService
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
                    try { service.getPage("${baseUrl}drama/?m_orderby=views") } catch (_: Exception) { null }
                }

                val document = homeDeferred.await()
                val categories = mutableListOf<Category>()

                // Section principale "EN COURS" — .page-item-detail items
                val allItems = document.select(".page-item-detail").mapNotNull { item ->
                    parseHomeItem(item)
                }

                // Featured carousel : les premiers items de la page d'accueil
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

                    // "En cours" = tous les items de la page d'accueil
                    categories.add(Category(name = "En cours", list = allItems))
                }

                // Nouveaux ajouts
                val recentDoc = recentDeferred.await()
                if (recentDoc != null) {
                    val recentShows = recentDoc.select(".page-item-detail").mapNotNull { item ->
                        parseHomeItem(item)
                    }
                    if (recentShows.isNotEmpty()) {
                        categories.add(Category(name = "Nouveau", list = recentShows))
                    }
                }

                // Séries populaires (par vues)
                val popularDoc = popularDeferred.await()
                if (popularDoc != null) {
                    val popularShows = popularDoc.select(".page-item-detail").mapNotNull { item ->
                        parseHomeItem(item)
                    }
                    if (popularShows.isNotEmpty()) {
                        categories.add(Category(name = "Séries Populaires", list = popularShows))
                    }
                }

                categories
            }
        } catch (e: Exception) {
            Log.e("VoirDramaProvider", "getHome error: ", e)
            emptyList()
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            // Genres réels du site voirdrama.to
            return listOf(
                Genre(id = "action", name = "Action"),
                Genre(id = "affaires", name = "Affaires"),
                Genre(id = "amitie", name = "Amitié"),
                Genre(id = "arts-martiaux", name = "Arts martiaux"),
                Genre(id = "aventure", name = "Aventure"),
                Genre(id = "comedie", name = "Comédie"),
                Genre(id = "crime", name = "Crime"),
                Genre(id = "drame", name = "Drame"),
                Genre(id = "famille", name = "Famille"),
                Genre(id = "fantastique", name = "Fantastique"),
                Genre(id = "historique", name = "Historique"),
                Genre(id = "horreur", name = "Horreur"),
                Genre(id = "melodrame", name = "Mélodrame"),
                Genre(id = "mystere", name = "Mystère"),
                Genre(id = "psychologique", name = "Psychologique"),
                Genre(id = "romance", name = "Romance"),
                Genre(id = "sf", name = "SF"),
                Genre(id = "surnaturel", name = "Surnaturel"),
                Genre(id = "thriller", name = "Thriller"),
                Genre(id = "vie-quotidienne", name = "Vie quotidienne"),
                Genre(id = "wuxia", name = "Wuxia"),
                Genre(id = "k-drama", name = "K-Drama"),
            )
        }

        initializeService()
        return try {
            val document = service.search(query, page)
            parseSearchResults(document)
        } catch (e: Exception) {
            Log.e("VoirDramaProvider", "search error: ", e)
            emptyList()
        }
    }

    // ==================== MOVIES / TV SHOWS ====================

    override suspend fun getMovies(page: Int): List<Movie> {
        // Onglet "Série FR" — contenu VF via liste-dramas/?filter=dubbed
        initializeService()
        return try {
            val url = if (page > 1) "${baseUrl}liste-dramas/page/$page/?filter=dubbed"
                      else "${baseUrl}liste-dramas/?filter=dubbed"
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
            Log.e("VoirDramaProvider", "getMovies error: ", e)
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        // Onglet "Série VOSTFR" — via liste-dramas/?filter=subbed
        initializeService()
        return try {
            val url = if (page > 1) "${baseUrl}liste-dramas/page/$page/?filter=subbed"
                      else "${baseUrl}liste-dramas/?filter=subbed"
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
            Log.e("VoirDramaProvider", "getTvShows error: ", e)
            emptyList()
        }
    }

    // ==================== DETAIL ====================

    override suspend fun getMovie(id: String): Movie {
        initializeService()
        val url = if (id.startsWith("http")) id else "${baseUrl}drama/$id/"
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

        // Extraire la note depuis .score
        val rating = document.selectFirst(".score.font-meta.total_votes")
            ?.text()?.trim()?.toDoubleOrNull()

        // Extraire les infos depuis .post-content_item
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
        val url = if (id.startsWith("http")) id else "${baseUrl}drama/$id/"
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

        // Récupérer les épisodes — sur VoirDrama ils sont chargés inline dans .listing-chapters_wrap
        val episodes = document.select("li.wp-manga-chapter")

        // Sur VoirDrama, pas de saisons multiples — une seule saison avec tous les épisodes
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
        val url = if (seasonId.startsWith("http")) seasonId else "${baseUrl}drama/$seasonId/"
        val document = service.getPage(url)

        // Récupérer le poster de la série pour l'utiliser comme thumbnail des épisodes
        val showPoster = improveImageUrl(
            document.selectFirst(".summary_image img")?.attr("src")
        )

        val episodeElements = document.select("li.wp-manga-chapter")

        // Les épisodes sont en ordre décroissant sur le site, on les inverse
        return episodeElements.reversed().mapIndexed { index, el ->
            val link = el.selectFirst("a")
            val epUrl = link?.attr("href") ?: ""
            val epTitle = link?.text()?.trim() ?: "Épisode ${index + 1}"
            // L'ID inclut le drama slug: "climax/climax-2026-10-vostfr"
            // pour reconstruire l'URL: ${baseUrl}drama/{epId}/
            val epId = epUrl.trimEnd('/').substringAfter("/drama/").trimEnd('/')
                .takeIf { it.isNotBlank() }
                ?: "$seasonId/ep-${index + 1}"

            // Extraire le numéro d'épisode — format: "Drama (2026) - 10 VOSTFR - 10"
            val epNumber = Regex("""[\s-]+(\d+)\s*(?:VOSTFR|VF|VO)""", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\s*$""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)

            val releaseDate = el.selectFirst(".chapter-release-date i")
                ?.text()?.trim()

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
            val url = if (page > 1) "${baseUrl}drama-genre/$id/page/$page/"
                      else "${baseUrl}drama-genre/$id/"
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
            Log.e("VoirDramaProvider", "getGenre error: ", e)
            Genre(id = id, name = id, shows = emptyList())
        }
    }

    // ==================== SERVERS / VIDEO ====================

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()
        return try {
            // L'ID d'un épisode est le chemin complet sous /drama/ ex: "climax/climax-2026-10-vostfr"
            val url = when (videoType) {
                is Video.Type.Movie -> if (id.startsWith("http")) id else "${baseUrl}drama/$id/"
                is Video.Type.Episode -> if (id.startsWith("http")) id else "${baseUrl}drama/$id/"
            }
            val document = service.getPage(url)

            val servers = mutableListOf<Video.Server>()

            // Sur VoirDrama : iframe unique dans div.chapter-video-frame
            document.select(".chapter-video-frame iframe, .reading-content iframe, .entry-content iframe, iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() && it.startsWith("http") } ?: return@forEach
                val serverName = try {
                    java.net.URL(src).host.split(".").first { it != "www" }
                        .replaceFirstChar { it.uppercase() }
                } catch (_: Exception) { "Lecteur" }
                servers.add(Video.Server(id = src, name = serverName, src = src))
            }

            // Détecter la langue dans le titre/URL de la page
            val pageTitle = document.title().lowercase()
            val pageUrl = url.lowercase()
            val lang = when {
                pageTitle.contains("vostfr") || pageUrl.contains("vostfr") -> "VOSTFR"
                pageTitle.contains("-vf") || pageUrl.contains("-vf") -> "VF"
                pageTitle.contains("vo ") || pageUrl.contains("-vo-") -> "VO"
                else -> "VOSTFR" // Par défaut pour les dramas coréens
            }

            servers.map { server ->
                if (!server.name.contains("VF") && !server.name.contains("VOSTFR") && !server.name.contains("VO")) {
                    Video.Server(id = server.id, name = "${server.name} ($lang)", src = server.src)
                } else server
            }.distinctBy { it.id }
        } catch (e: Exception) {
            Log.e("VoirDramaProvider", "getServers error: ", e)
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val video = Extractor.extract(server.src)
        // Pour les dramas VOSTFR, garder les sous-titres activés par défaut
        if (server.name.contains("VOSTFR")) {
            video.subtitles.forEach { sub ->
                if (sub.initialDefault) {
                    sub.default = true
                }
            }
        } else if (video.subtitles.isNotEmpty() && !server.name.contains("VO")) {
            // VF : désactiver les sous-titres
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
            // VoirDrama n'a pas de mécanisme d'auto-update de domaine
            // L'utilisateur peut changer l'URL manuellement dans les paramètres
            service = VoirDramaService.build(baseUrl)
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

    /**
     * Vérifie si un contenu est en VF (doublé en français).
     * Les versions VF ont "(VF)" dans le titre ou "-vf" dans le slug.
     */
    private fun isVf(show: Show): Boolean {
        val title = when (show) {
            is TvShow -> show.title
            is Movie -> show.title
            else -> return false
        }
        val id = when (show) {
            is TvShow -> show.id
            is Movie -> show.id
            else -> return false
        }
        return title.contains("(VF)", ignoreCase = true)
            || title.endsWith(" VF", ignoreCase = true)
            || id.endsWith("-vf")
            || id.contains("-vf-")
    }

    /**
     * Améliore la qualité d'une URL d'image WordPress.
     * Les thumbnails homepage sont au format "-110x150.jpg", "-175x238.jpg" etc (trop petit).
     * On les remplace par "-193x278" (taille poster du site, bonne qualité).
     * Si l'URL contient un redimensionnement de taille, on essaie le format -193x278
     * avant de retomber sur l'URL originale.
     * NB: la version sans suffixe peut être bloquée par Cloudflare.
     */
    private fun improveImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // Remplacer le suffixe de redimensionnement WordPress par -193x278 (meilleure qualité dispo)
        return url.replace(Regex("""-\d+x\d+\."""), "-193x278.")
    }

    /**
     * Extraire une valeur d'info depuis les .post-content_item de la page détail.
     * Structure : .summary-heading h5 contient le label, .summary-content contient la valeur.
     */
    private fun getInfoValue(document: Document, label: String): String? {
        return document.select(".post-content_item").firstOrNull { item ->
            item.selectFirst(".summary-heading h5")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        }?.selectFirst(".summary-content")?.text()?.trim()
    }

    /**
     * Parse un item de la page d'accueil ou de genre (.page-item-detail).
     * Structure : .item-thumb (id=manga-item-{id}, data-post-id) > a > img
     *             .item-summary > h3 > a (titre + lien)
     *             .score (note)
     *             .chapter-item > a (épisodes récents)
     */
    private fun parseHomeItem(element: Element): Show? {
        val thumb = element.selectFirst(".item-thumb")
        val summary = element.selectFirst(".item-summary")

        // Lien et titre depuis .item-summary h3 a (prioritaire) ou .item-thumb a
        val titleLink = summary?.selectFirst("h3 a")
            ?: thumb?.selectFirst("a")
            ?: return null

        val href = titleLink.attr("href").takeIf { it.isNotBlank() } ?: return null

        // Extraire le slug comme ID — format: https://voirdrama.to/drama/{slug}/
        val id = href.trimEnd('/').substringAfter("/drama/").trimEnd('/')
            .takeIf { it.isNotBlank() && !it.contains("://") }
            ?: href.trimEnd('/').substringAfterLast("/").takeIf { it.isNotBlank() }
            ?: return null

        val title = titleLink.text().trim().takeIf { it.isNotBlank() }
            ?: titleLink.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null

        // Filtrer les items sans contenu (pas d'épisodes/chapitres)
        val listChapter = element.selectFirst(".list-chapter")
        if (listChapter != null && listChapter.select(".chapter-item").isEmpty()) return null

        // Poster depuis .item-thumb img (src direct, pas de data-src sur ce site)
        val img = thumb?.selectFirst("img") ?: element.selectFirst("img")
        val rawPoster = img?.let {
            it.attr("data-src").ifEmpty {
                it.attr("src")
            }
        }?.takeIf { it.isNotBlank() && it.startsWith("http") }
        val poster = improveImageUrl(rawPoster)

        // Sur VoirDrama, tout est série (drama) sauf indication contraire
        return TvShow(id = id, title = title, poster = poster)
    }

    /**
     * Parse un seul élément de résultat de recherche (.c-tabs-item__content).
     */
    private fun parseSearchItem(element: Element): Show? {
        val link = element.selectFirst(".post-title a, h3 a, h4 a, a[href*=/drama/]")
            ?: return null

        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null

        val id = href.trimEnd('/').substringAfter("/drama/").trimEnd('/')
            .takeIf { it.isNotBlank() && !it.contains("://") }
            ?: href.trimEnd('/').substringAfterLast("/").takeIf { it.isNotBlank() }
            ?: return null

        val title = link.text().trim().takeIf { it.isNotBlank() } ?: return null

        val img = element.selectFirst("img")
        val rawPoster = img?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }?.takeIf { it.isNotBlank() && it.startsWith("http") }
        val poster = improveImageUrl(rawPoster)

        return TvShow(id = id, title = title, poster = poster)
    }

    /**
     * Parse les résultats de recherche.
     */
    private fun parseSearchResults(document: Document): List<AppAdapter.Item> {
        return document.select(".c-tabs-item__content").mapNotNull { item ->
            parseSearchItem(item) as? AppAdapter.Item
        }.distinctBy { (it as? TvShow)?.id ?: (it as? Movie)?.id }
    }

    // ==================== SERVICE ====================

    private interface VoirDramaService {
        companion object {
            private val client = NetworkClient.default.newBuilder().build()

            fun build(baseUrl: String): VoirDramaService {
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(VoirDramaService::class.java)
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
