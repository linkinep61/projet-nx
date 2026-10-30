package com.streamflixreborn.streamflix.providers

import android.text.Html
import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.BuildConfig
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import retrofit2.HttpException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import com.streamflixreborn.streamflix.utils.NetworkClient
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

object UnJourUnFilmProvider : Provider, ProviderPortalUrl, ProviderConfigUrl, ProgressiveServersProvider {
    override val name = "1Jour1Film"

    const val USER_AGENT = NetworkClient.USER_AGENT
    // 2026-06-19 (user "l'adresse de redirection a changé") : 1jour1film-officiel.site
    //   est mort, nouveau portail = https://1jour1film2026.site/
    // ── 2026-08-28 (user « ca fait tres longtemps que je vois plus ce backup ») ─────────
    //   La RACINE du portail est morte (page d'erreur dans le navigateur, echec TLS depuis
    //   le PC). La page vivante est /go/ : titre « 1JOUR1FILM – Page Officielle », avec le
    //   lien « ACCEDER A L'ADRESSE ACTUELLE » et une redirection auto en 5 s — exactement
    //   les deux motifs que `onChangeUrl` sait deja lire (cf. commentaire du 19/06).
    //   Verifie en direct : /go/ retombe sur https://1jour1film0826.online/
    //
    //   CONSEQUENCE DE LA PANNE : `onChangeUrl` ne pouvait plus decouvrir l'adresse
    //   courante, l'app restait donc sur le domaine code en dur de JUIN, qui repond 403.
    //   Mesure du 28/08 sur « Projet Derniere Chance » :
    //     DIAG [1Jour1Film] search(...) EXCEPTION en 148ms: HttpException: HTTP 403
    //     DIAG [1Jour1Film] AUCUN MATCH -> 0 serveurs
    //   Ce n'etait donc PAS un echec de correspondance de titre (ce que le journal laissait
    //   croire) mais un refus reseau : la source etait morte sur TOUS les titres.
    //   Pointer le portail sur /go/ rend l'auto-mise-a-jour fonctionnelle -> la source se
    //   repare seule a chaque rotation de domaine, sans nouvelle version de l'app.
    override val defaultPortalUrl: String = "https://1jour1film2026.site/go/"

    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty { field }
        }

    // 2026-08-28 : repli passe de 1jour1film0626c.site (juin, repond 403) a l'adresse
    //   d'aout, relevee via le portail. Ce n'est qu'un FILET : en marche normale c'est
    //   `onChangeUrl` qui fixe l'adresse. Mais si le portail tombe a son tour, l'app
    //   demarrera sur une adresse valide au lieu d'un 403 immediat.
    override val defaultBaseUrl: String = "https://1jour1film0826.online/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }

    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_unjourunfilm"
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    /** Mots génériques que le site met dans .j1f-card__title au lieu du vrai nom */
    private val TYPE_LABELS = setOf("film", "série", "serie", "movie", "tv show", "anime", "épisode", "episode", "saison")

    /** Extrait le vrai titre d'une carte j1f-card.
     *  Priorité : alt img > title/aria-label du lien > slug URL.
     *  On ignore .j1f-card__title si c'est juste un label de type ("Film"/"Série"). */
    private fun extractCardTitle(card: Element, href: String): String {
        // 1. j1f-card__title — seulement si c'est un VRAI titre (pas "Film"/"Série")
        val rawTitle = card.selectFirst(".j1f-card__title")?.text()?.trim()
        if (!rawTitle.isNullOrBlank() && rawTitle.lowercase() !in TYPE_LABELS) {
            return rawTitle
        }
        // 2. alt de l'image poster (souvent le vrai titre)
        val alt = (card.selectFirst(".j1f-card__poster img") ?: card.selectFirst("img"))
            ?.attr("alt")?.trim()
        if (!alt.isNullOrBlank() && alt.lowercase() !in TYPE_LABELS) {
            return alt
        }
        // 3. attribut title ou aria-label sur le lien <a>
        val aTitle = card.attr("title")?.trim()
        if (!aTitle.isNullOrBlank()) return aTitle
        val ariaLabel = card.attr("aria-label")?.trim()
        if (!ariaLabel.isNullOrBlank()) return ariaLabel
        // 4. Dernier fallback : slug URL humanisé (ex: "mon-film-2024" → "Mon film 2024")
        return href.substringBeforeLast("/").substringAfterLast("/")
            .replace("-", " ")
            .replaceFirstChar { it.uppercaseChar() }
    }

    /** Extrait le poster d'une carte j1f-card. */
    private fun extractCardPoster(card: Element): String {
        val posterImg = card.selectFirst(".j1f-card__poster img") ?: card.selectFirst("img")
        return posterImg?.attr("src")?.ifBlank { posterImg.attr("data-src") } ?: ""
    }

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
                    val title = extractCardTitle(card, href)
                    val poster = extractCardPoster(card)
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

        // Log category names for debugging
        categories.forEach { cat -> Log.d("UnJourUnFilm", "Category: '${cat.name}' (${cat.list.size} items)") }

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

        // La page publique « ?s= » renvoie 403 (WAF) : on passe d'abord par l'API REST.
        val apiResults = searchViaApi(query)
        if (apiResults.isNotEmpty()) return apiResults

        val document = try {
            service.search( query )
        } catch (_: Exception) {
            return emptyList()
        }

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
                    val title = extractCardTitle(card, href)
                    val poster = extractCardPoster(card)
                    val id = href.substringBeforeLast("/").substringAfterLast("/")

                    if (href.contains("/films/")) {
                        Movie(id = id, title = title, poster = poster)
                    } else if (href.contains("/tvshows/")) {
                        TvShow(id = id, title = title, poster = poster)
                    } else null
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

        // 2026-10-31 : API d'abord (/films/page/N est derrière Cloudflare).
        getMoviesFromApi(page).takeIf { it.isNotEmpty() }?.let {
            Log.i("UnJourUnFilm", "API films p$page : ${it.size}")
            return it
        }

        val document = service.getMovies(page)

        // Primary: new j1f-card structure
        val movies = document.select("a.j1f-card")
            .mapNotNull { card ->
                val href = card.attr("href")
                if (!href.contains("/films/")) return@mapNotNull null
                Movie(
                    id = href.substringBeforeLast("/").substringAfterLast("/"),
                    title = extractCardTitle(card, href),
                    poster = extractCardPoster(card)
                )
            }

        // REST API fallback if Jsoup sees nothing (JS-rendered pages)
        return movies.ifEmpty { getMoviesFromApi(page) }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        initializeService()
        val document = service.getTvShows(page)

        // Primary: new j1f-card structure
        val tvshows = document.select("a.j1f-card")
            .mapNotNull { card ->
                val href = card.attr("href")
                if (!href.contains("/tvshows/")) return@mapNotNull null
                TvShow(
                    id = href.substringBeforeLast("/").substringAfterLast("/"),
                    title = extractCardTitle(card, href),
                    poster = extractCardPoster(card)
                )
            }

        // REST API fallback if Jsoup sees nothing (JS-rendered pages)
        return tvshows.ifEmpty { getTvShowsFromApi(page) }
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
        // 2026-10-31 : API d'abord (la page /films/ est derrière Cloudflare). Pas de résumé
        //   dans l'API pour les films.
        getMovieApi(id)?.let {
            Log.i("UnJourUnFilm", "API film $id → « ${it.title} »")
            return it
        }
        val document = service.getMovie(id)

        val scriptdoc = document.head().selectFirst("script[type=application/ld+json]:not([class])")?.data().orEmpty()

        // Parse JSON-LD properly instead of naive string manipulation
        val jsonLd = try { JSONObject(scriptdoc) } catch (_: Exception) { JSONObject() }
        val title = decodeHtml(jsonLd.optString("name", "")).ifBlank {
            document.selectFirst("h1.entry-title, h1, .data h1")?.text() ?: ""
        }
        // Extract overview: JSON-LD description first, fallback to page content
        var overview = decodeHtml(jsonLd.optString("description", "")).let { desc ->
            if (desc.isBlank()) ""
            else {
                val cleaned = if (desc.contains(": ")) desc.substringAfter(": ") else desc
                if (cleaned.contains(" Voir ")) cleaned.substringBeforeLast(" Voir ").trim() else cleaned.trim()
            }
        }
        // Fallback: try wp-content div if JSON-LD was empty
        if (overview.isBlank()) {
            overview = decodeHtml(document.selectFirst("div.wp-content")?.text() ?: "").let { text ->
                if (text.startsWith("Regarder ") && text.contains(": ")) text.substringAfter(": ").trim()
                else text.trim()
            }
        }
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

        // 2026-10-31 : l'API ne donne aucune image d'épisode → on garde l'affiche de chaque saison
        //   (page série, non bloquée) pour les vignettes des épisodes venus de l'API.
        seasons.forEach { s -> s.poster?.takeIf { it.isNotBlank() }?.let { saisonsAffiche[s.id] = it } }

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

        // 2026-10-31 : API d'abord (la page /saisons/ est derrière Cloudflare).
        //   Id d'épisode « J1FAPI:<id saison>:<id épisode> » → getServers interroge admin-ajax.
        if (seasonId.startsWith("saisons/")) {
            saisonApi(seasonId)?.let { (idSaison, eps) ->
                return eps.map { (n, idEp) ->
                    Episode(id = "J1FAPI:$idSaison:$idEp", number = n, title = "Épisode $n", overview = "",
                        poster = saisonsAffiche[seasonId])
                }
            }
        }

        // New format: fetch season page and parse j1fEpsData JS variable
        try {
            val seasonDocument = service.getSeason(seasonId)
            // j1fEpsData is in base64-encoded data URL scripts, decode them
            val decodedScripts = extractDecodedScripts(seasonDocument)
            val inlineJs = if (decodedScripts.isEmpty()) {
                seasonDocument.select("script:not([src])").joinToString("\n") { it.data() }
            } else ""
            val searchText = decodedScripts.ifEmpty { inlineJs }

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

    // ── 2026-10-31 : PASSAGE PAR L'API wp-json ────────────────────────────────────────────
    //   Les pages /films/ et /saisons/ sont derrière un challenge Cloudflare (lent : WebView,
    //   ~11 s la 1re fois sur télé). L'API wp-json et admin-ajax ne le sont PAS (vérifié sur la
    //   télé sans cookie ni WebView). On les utilise en premier ; la page reste en secours.
    private suspend fun apiGet(chemin: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val req = okhttp3.Request.Builder()
                .url(baseUrl.trimEnd('/') + chemin)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            NetworkClient.default.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else { Log.w("UnJourUnFilm", "API $chemin → ${r.code}"); null }
            }
        }.onFailure { Log.w("UnJourUnFilm", "API $chemin → ${it.message}") }.getOrNull()
    }

    private val filmsApiCache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    /** Fiche film de l'API (avec affiche et termes intégrés) ; id = slug. */
    private suspend fun filmApi(slug: String): JSONObject? {
        filmsApiCache[slug]?.let { return it }
        val o = apiGet("/wp-json/wp/v2/movies?slug=${java.net.URLEncoder.encode(slug, "UTF-8")}&_embed=wp:featuredmedia,wp:term")
            ?.let { runCatching { JSONArray(it).optJSONObject(0) }.getOrNull() } ?: return null
        filmsApiCache[slug] = o
        return o
    }

    private fun termesApi(o: JSONObject, taxonomie: String): List<JSONObject> {
        val groupes = o.optJSONObject("_embedded")?.optJSONArray("wp:term") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until groupes.length()) {
            val g = groupes.optJSONArray(i) ?: continue
            for (j in 0 until g.length()) g.optJSONObject(j)?.takeIf { it.optString("taxonomy") == taxonomie }?.let { out += it }
        }
        return out
    }

    private suspend fun getMovieApi(id: String): Movie? {
        val o = filmApi(id) ?: return null
        val poster = o.optJSONObject("_embedded")?.optJSONArray("wp:featuredmedia")?.optJSONObject(0)?.optString("source_url").orEmpty()
        val annee = termesApi(o, "dtyear").firstOrNull()?.optString("name")
        return Movie(
            id = id,
            title = decodeHtml(o.optJSONObject("title")?.optString("rendered").orEmpty()),
            overview = "",
            released = annee ?: id.substringAfterLast("-"),
            quality = termesApi(o, "dtquality").firstOrNull()?.optString("name"),
            poster = poster,
            genres = termesApi(o, "genres").map { Genre(id = it.optString("slug"), name = decodeHtml(it.optString("name"))) },
            directors = termesApi(o, "dtdirector").map { People(id = it.optString("slug"), name = decodeHtml(it.optString("name"))) },
            cast = termesApi(o, "dtcast").map { People(id = it.optString("slug"), name = decodeHtml(it.optString("name"))) },
        )
    }

    /** Saison de l'API → (id de saison, [(n° d'épisode, id d'épisode)]). seasonId = "saisons/<slug>". */
    private val saisonsApiCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, List<Pair<Int, Int>>>>()
    private val saisonsAffiche = java.util.concurrent.ConcurrentHashMap<String, String>()
    private suspend fun saisonApi(seasonId: String): Pair<Int, List<Pair<Int, Int>>>? {
        saisonsApiCache[seasonId]?.let { return it }
        val slug = seasonId.trimEnd('/').substringAfterLast('/')
        val s = apiGet("/wp-json/wp/v2/seasons?slug=${java.net.URLEncoder.encode(slug, "UTF-8")}&_fields=id,title,_links,_embedded&_embed=wp:featuredmedia")
            ?.let { runCatching { JSONArray(it).optJSONObject(0) }.getOrNull() } ?: return null
        val idSaison = s.optInt("id").takeIf { it > 0 } ?: return null
        s.optJSONObject("_embedded")?.optJSONArray("wp:featuredmedia")?.optJSONObject(0)?.optString("source_url")
            ?.takeIf { it.isNotBlank() }?.let { saisonsAffiche[seasonId] = it }
        val titre = decodeHtml(s.optJSONObject("title")?.optString("rendered").orEmpty())
        val num = Regex("(?i)saison\\s*(\\d+)").find(titre)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("saison-(\\d+)").find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val nom = titre.replace(Regex("(?i)\\s*saison\\s*\\d+.*$"), "").trim().ifBlank { return null }
        // Épisodes : « <Nom> S<n> Episode <e> » / slug « <base>-s<n>-episode-<e> ».
        val base = slug.substringBefore("-saison-")
        // Deux formats vus : « …-s2-episode-10 » et « …-s1-e3 » / « …-s1-e9-vf-fin ».
        val motif = Regex("-s$num-(?:episode-|e)(\\d+)(?:-|$)")
        val eps = LinkedHashMap<Int, Int>()
        for (page in 1..3) {
            val q = java.net.URLEncoder.encode("$nom S$num", "UTF-8").replace("+", "%20")
            val tab = apiGet("/wp-json/wp/v2/episodes?search=$q&per_page=100&page=$page&_fields=id,slug")
                ?.let { runCatching { JSONArray(it) }.getOrNull() } ?: break
            for (i in 0 until tab.length()) {
                val e = tab.optJSONObject(i) ?: continue
                val es = e.optString("slug")
                if (!es.startsWith("$base-s")) continue
                val n = motif.find(es)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                eps.putIfAbsent(n, e.optInt("id"))
            }
            if (tab.length() < 100) break
        }
        Log.i("UnJourUnFilm", "API saison $slug → id=$idSaison « $nom » S$num : ${eps.size} épisode(s)")
        if (eps.isEmpty()) return null
        val r = idSaison to eps.entries.sortedBy { it.key }.map { it.key to it.value }
        saisonsApiCache[seasonId] = r
        return r
    }

    /** Sources demandées à admin-ajax pour les numéros 0..7 (en parallèle), dans l'ordre. */
    private suspend fun sonderSources(params: (Int) -> String): List<String> {
        val nonce = nonceJ1f() ?: return emptyList()
        return coroutineScope {
            (0..7).map { i -> async { sourceJ1f(nonce, params(i)) } }.awaitAll().filterNotNull()
        }.distinct()
    }

    /** Range une adresse comme le fait la lecture des pages (OnRegardeOu / ApiVoirFilm / autre). */
    private fun rangerSource(url: String, servers: MutableList<Video.Server>, apiUrls: MutableList<String>, onregardeUrls: MutableList<String>) {
        if (url.isEmpty() || ignoreSource("", url)) return
        val apivoirfilm = ApiVoirFilmExtractor()
        val onregadeou = OnRegardeOuExtractor()
        val estOnRegardeOu = url.startsWith(onregadeou.mainUrl, ignoreCase = true) ||
            onregadeou.aliasUrls.any { a ->
                val hote = try { android.net.Uri.parse(a).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
                hote.isNotBlank() && url.contains(hote, ignoreCase = true)
            }
        when {
            url.startsWith(apivoirfilm.mainUrl) -> apiUrls.add(url)
            estOnRegardeOu -> onregardeUrls.add(url)
            else -> {
                val nom = Extractor.identifyServiceName(url)
                    ?: runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull()
                    ?: "Server ${servers.size + 1}"
                servers.add(Video.Server(id = "srv${servers.size}", name = nom, src = url))
            }
        }
    }

    // ── 2026-10-21 (user : « sur 1Jour1Film… pas de serveur ») ─────────────────────────────
    //   Le site ne met PLUS les adresses dans la page : J1F_SRV (films) et j1fEpsData (épisodes)
    //   ne portent que label/type/flags (+ « oi » = numéro de la source). L'adresse est demandée
    //   au clic à admin-ajax.php :
    //     1) action=j1f_get_nonce → {success, data:{nonce}}
    //     2) film    : action=j1f_get_source&nonce&post_id=<J1F_POST_ID>&idx=<oi>
    //        épisode : action=j1f_get_ep_source&nonce&season_id=<J1F_SEASON_ID>&ep_id=<id>&idx=<oi>
    //     → {success, data:{url, type}}. Vérifié sans cookie (Spider-Man, Sacrifice S1).
    private suspend fun ajaxJ1f(corps: String): JSONObject? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val req = okhttp3.Request.Builder()
                .url(baseUrl.trimEnd('/') + "/wp-admin/admin-ajax.php")
                .post(okhttp3.FormBody.Builder().apply {
                    corps.split('&').forEach { p -> add(p.substringBefore('='), p.substringAfter('=', "")) }
                }.build())
                .header("User-Agent", USER_AGENT)
                .header("Referer", baseUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            NetworkClient.default.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private suspend fun nonceJ1f(): String? =
        ajaxJ1f("action=j1f_get_nonce")?.takeIf { it.optBoolean("success") }?.optJSONObject("data")?.optString("nonce")?.ifBlank { null }

    /** Adresse d'une source demandée au site ; null si indisponible. */
    private suspend fun sourceJ1f(nonce: String?, params: String): String? {
        if (nonce == null) return null
        val res = ajaxJ1f("$params&nonce=$nonce") ?: return null
        if (!res.optBoolean("success")) return null
        return res.optJSONObject("data")?.optString("url")?.takeIf { it.startsWith("http", true) }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()

        val apivoirfilm = ApiVoirFilmExtractor()
        val onregadeou = OnRegardeOuExtractor()
        // 2026-07-01 (user "4 serveurs sur le site, 3 dans l'app") : ces URLs étaient
        //   de simples variables + un `if/else if` en fin → si le film avait À LA FOIS
        //   un serveur ApiVoirFilm ET un OnRegardeOu, un seul était gardé (l'autre
        //   jeté) ; et 2 serveurs du même type → seul le dernier survivait. Passées en
        //   LISTES → on étend TOUS les serveurs spéciaux, aucun n'est perdu.
        val apiUrls = mutableListOf<String>()
        val onregardeUrls = mutableListOf<String>()

        val servers = mutableListOf<Video.Server>()

        // 2026-10-31 : épisode venu de l'API → sources demandées directement à admin-ajax.
        if (id.startsWith("J1FAPI:")) {
            val (idSaison, idEp) = id.removePrefix("J1FAPI:").split(":").let { it.getOrNull(0) to it.getOrNull(1) }
            val urls = sonderSources { i -> "action=j1f_get_ep_source&season_id=$idSaison&ep_id=$idEp&idx=$i" }
            Log.i("UnJourUnFilm", "API épisode $idEp (saison $idSaison) : ${urls.size} source(s)")
            urls.forEach { rangerSource(it, servers, apiUrls, onregardeUrls) }
            val other = apiUrls.flatMap { apivoirfilm.expand(it, baseUrl, "FR ") } +
                onregardeUrls.flatMap { onregadeou.expand(it, baseUrl, "FR ") }
            return sortServersByLanguage(servers + other)
        }

        // 2026-10-31 : film → id WordPress par l'API, sources par admin-ajax (sans la page).
        if (videoType is Video.Type.Movie) {
            val idPost = filmApi(id)?.optInt("id")?.takeIf { it > 0 }
            if (idPost != null) {
                val urls = sonderSources { i -> "action=j1f_get_source&post_id=$idPost&idx=$i" }
                Log.i("UnJourUnFilm", "API film $id (post $idPost) : ${urls.size} source(s)")
                if (urls.isNotEmpty()) {
                    urls.forEach { rangerSource(it, servers, apiUrls, onregardeUrls) }
                    val other = apiUrls.flatMap { apivoirfilm.expand(it, baseUrl, "FR ") } +
                        onregardeUrls.flatMap { onregadeou.expand(it, baseUrl, "FR ") }
                    return sortServersByLanguage(servers + other)
                }
            }
        }

        // Handle new season-based episode format: SEASON:seasonSlug:epIndex
        if (id.startsWith("SEASON:")) {
            val parts = id.split(":")
            if (parts.size >= 3) {
                val seasonSlug = parts[1]
                val epIndex = parts[2].toIntOrNull() ?: 0

                val seasonDocument = service.getSeason(seasonSlug)
                val decodedScripts = extractDecodedScripts(seasonDocument)
                val seasonInlineJs = if (decodedScripts.isEmpty()) {
                    seasonDocument.select("script:not([src])").joinToString("\n") { it.data() }
                } else ""
                val seasonHtml = decodedScripts.ifEmpty { seasonInlineJs }

                val epsDataRegex = Regex("""j1fEpsData\s*=\s*(\[.*?]);\s*""", RegexOption.DOT_MATCHES_ALL)
                val epsMatch = epsDataRegex.find(seasonHtml)
                Log.i("UnJourUnFilm", "SEASON $seasonSlug ep#$epIndex : j1fEpsData=${epsMatch != null} (scripts décodés=${decodedScripts.length})")

                if (epsMatch != null) {
                    try {
                        val jsonArray = JSONArray(epsMatch.groupValues[1])
                        if (epIndex < jsonArray.length()) {
                            val epObj = jsonArray.getJSONObject(epIndex)
                            val epServers = epObj.optJSONArray("servers")
                            val idSaison = Regex("""J1F_SEASON_ID\s*=\s*(\d+)""").find(seasonHtml)?.groupValues?.get(1)
                                ?: Regex("""J1F_SEASON_ID\s*=\s*(\d+)""").find(seasonDocument.select("script:not([src])").joinToString("\n") { it.data() })?.groupValues?.get(1)
                            val nonceEp = if (idSaison != null) nonceJ1f() else null
                            Log.i("UnJourUnFilm", "SEASON $seasonSlug ep#$epIndex : season_id=$idSaison nonce=${nonceEp != null} sources=${epServers?.length()}")

                            if (epServers != null) {
                                for (i in 0 until epServers.length()) {
                                    val srv = epServers.getJSONObject(i)
                                    val label = srv.optString("label", "")
                                    // 2026-07-01 : décodage base64 (cf boucle film) → routage onregardeou correct.
                                    // ⚠ FIX : dans j1fEpsData (épisodes de série) le champ URL
                                    //   s'appelle "u" (base64), PAS "url" (qui, lui, est le champ
                                    //   des FILMS dans J1F_SRV). Sans ça → url vide → tous les
                                    //   serveurs d'épisodes ignorés → « aucune source » (ex From).
                                    val url = srv.optString("u", "").ifBlank { srv.optString("url", "") }.let { raw ->
                                        if (raw.isBlank()) return@let raw
                                        if (raw.startsWith("http", true)) raw
                                        else try {
                                            val d = String(android.util.Base64.decode(raw.trim(), android.util.Base64.DEFAULT))
                                            if (d.startsWith("http", true)) d else raw
                                        } catch (_: Exception) { raw }
                                    }.ifBlank {
                                        if (idSaison == null) "" else sourceJ1f(nonceEp,
                                            "action=j1f_get_ep_source&season_id=$idSaison&ep_id=${epObj.optString("id")}&idx=${srv.optInt("oi", i)}") ?: ""
                                    }
                                    val flags = srv.optString("flags", "")

                                    if (url.isNotEmpty() && !ignoreSource("", url)) {
                                        val flagPrefix = if (flags.isNotEmpty()) "$flags " else ""

                                        if (url.startsWith(apivoirfilm.mainUrl)) {
                                            apiUrls.add(url)
                                        } else if (url.startsWith(onregadeou.mainUrl)) {
                                            onregardeUrls.add(url)
                                        } else {
                                            val serviceName = Extractor.identifyServiceName(url)
                                            val displayName = if (serviceName != null) {
                                                if (label.isNotBlank()) "$flagPrefix$label ($serviceName)" else "$flagPrefix$serviceName"
                                            } else {
                                                "$flagPrefix${label.ifBlank { "Server ${i + 1}" }}"
                                            }
                                            servers.add(
                                                Video.Server(
                                                    id = "srv$i",
                                                    name = displayName,
                                                    src = url
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { Log.w("UnJourUnFilm", "SEASON $seasonSlug ep#$epIndex : ${e.javaClass.simpleName} ${e.message}") }
                }

                // Return early with season-based results
                val other = apiUrls.flatMap { apivoirfilm.expand(it, baseUrl, "FR ") } +
                    onregardeUrls.flatMap { onregadeou.expand(it, baseUrl, "FR ") }

                return sortServersByLanguage(servers + other)
            }
        }

        // Fetch the page (movie or episode)
        val document = when (videoType) {
            is Video.Type.Episode -> service.getEpisode(id)
            is Video.Type.Movie -> service.getMovie(id)
        }

        // Try to extract J1F_SRV servers from the page
        // Data is in base64-encoded data URL scripts first, then inline <script> tags.
        // Avoid document.html() which re-serializes the entire DOM — expensive.
        val decodedMovieScripts = extractDecodedScripts(document)
        val inlineScripts by lazy {
            document.select("script:not([src])").joinToString("\n") { it.data() }
        }
        val jsContent = decodedMovieScripts.ifEmpty { inlineScripts }
        val srvRegex = Regex("""J1F_SRV\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL)
        val match = srvRegex.find(jsContent)

        if (match != null) {
            try {
                val jsonArrayStr = match.groupValues[1]
                val jsonArray = JSONArray(jsonArrayStr)
                val idPost = Regex("""J1F_POST_ID\s*=\s*(\d+)""").find(jsContent)?.groupValues?.get(1)
                    ?: Regex("""J1F_POST_ID\s*=\s*(\d+)""").find(inlineScripts)?.groupValues?.get(1)
                val nonceFilm = if (idPost != null) nonceJ1f() else null

                for (i in 0 until jsonArray.length()) {
                    val serverObj = jsonArray.getJSONObject(i)
                    val label = serverObj.optString("label", "")
                    // 2026-07-01 : les URLs J1F_SRV arrivent en BASE64 (ex "aHR0…" =
                    //   onregardeou.site/...). On DÉCODE ici, sinon le test
                    //   `startsWith(onregadeou.mainUrl)` échouait → le serveur VF HD ++
                    //   tombait en "serveur normal" et expand() (qui expose les 4
                    //   mirrors internes) n'était JAMAIS appelé.
                    val url = serverObj.optString("url", "").let { raw ->
                        if (raw.isBlank()) return@let raw
                        if (raw.startsWith("http", true)) raw
                        else try {
                            val d = String(android.util.Base64.decode(raw.trim(), android.util.Base64.DEFAULT))
                            if (d.startsWith("http", true)) d else raw
                        } catch (_: Exception) { raw }
                    }.ifBlank {
                        if (idPost == null) "" else sourceJ1f(nonceFilm,
                            "action=j1f_get_source&post_id=$idPost&idx=${serverObj.optInt("oi", i)}") ?: ""
                    }

                    if (url.isNotEmpty() && !ignoreSource("", url)) {
                        // ── 2026-08-05 — ROUTAGE OnRegardeOu ÉLARGI AUX ALIAS ─────────────
                        //   Bug user : DEUX serveurs « 1Jour1Film · VF HD (OnRegardeOu) »
                        //   au libellé IDENTIQUE, dont un mort.
                        //   Cause : ce test n'acceptait que les URLs commençant par
                        //   `onregardeou.site`. Or le site livre maintenant les miroirs EN
                        //   DIRECT (`bysezoxexe.com`, `dismoiceline.uns.bio`). Le test
                        //   échouait, les deux tombaient dans la branche générique ci-dessous,
                        //   et `identifyServiceName` les reconnaissant tous deux via les alias
                        //   de l'extracteur, ils recevaient LE MÊME nom.
                        //   Pire : ils ne passaient donc jamais par `expand()`, qui est le seul
                        //   endroit où `DISABLED_MIRROR_HOSTS` écarte `uns.bio` — un lecteur
                        //   vidstack à PoW « Verifying human… », injouable en mode invisible,
                        //   volontairement désactivé depuis juillet.
                        //   On route désormais vers `expand()` dès que l'URL appartient à
                        //   l'extracteur, alias compris : les miroirs retrouvent des noms
                        //   distincts (leur hôte réel) et le filtre s'applique.
                        val estOnRegardeOu = url.startsWith(onregadeou.mainUrl, ignoreCase = true) ||
                            onregadeou.aliasUrls.any { a ->
                                val hote = try { android.net.Uri.parse(a).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
                                hote.isNotBlank() && url.contains(hote, ignoreCase = true)
                            }
                        if (url.startsWith(apivoirfilm.mainUrl)) {
                            apiUrls.add(url)
                        } else if (estOnRegardeOu) {
                            onregardeUrls.add(url)
                        } else {
                            val serviceName = Extractor.identifyServiceName(url)
                            val displayName = if (serviceName != null) {
                                if (label.isNotBlank()) "$label ($serviceName)" else serviceName
                            } else {
                                label.ifBlank { "Server ${servers.size + 1}" }
                            }
                            servers.add(
                                Video.Server(
                                    id = "srv${servers.size}",
                                    name = displayName,
                                    src = url
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // J1F_SRV parsing failed, will fallback to DooPlay AJAX
            }
        }

        // Legacy DooPlay AJAX fallback (post-ID probe, max 10 requests)
        if (servers.isEmpty() && apiUrls.isEmpty() && onregardeUrls.isEmpty()) {
            // Extract WordPress post ID from body/article class (e.g. "postid-12345")
            // without re-serializing the DOM via document.html().
            val bodyClasses = document.body().className() + " " +
                (document.selectFirst("article[id]")?.className() ?: "")
            val postIdMatch = Regex("""postid-(\d+)""").find(bodyClasses)
                ?: Regex("""post-(\d+)""").find(bodyClasses)
            val postId = postIdMatch?.groupValues?.get(1) ?: ""
            val isEpisode = videoType is Video.Type.Episode

            if (postId.isNotEmpty()) {
                val typeStr = if (isEpisode) "tv" else "movie"
                val results = coroutineScope {
                    (1..10).map { nume ->
                        async {
                            try {
                                val link = service.getServers(
                                    num = nume.toString(),
                                    post = postId,
                                    type = typeStr
                                )
                                if (link.embed_url.isNullOrEmpty()) null
                                else nume to link.embed_url
                            } catch (_: Exception) { null }
                        }
                    }.awaitAll()
                }
                for (pair in results) {
                    if (pair == null) continue
                    val (nume, embedUrl) = pair
                    if (ignoreSource("", embedUrl)) continue

                    if (embedUrl.startsWith(apivoirfilm.mainUrl)) {
                        apiUrls.add(embedUrl)
                    } else if (embedUrl.startsWith(onregadeou.mainUrl)) {
                        onregardeUrls.add(embedUrl)
                    } else {
                        val serviceName = Extractor.identifyServiceName(embedUrl)
                        servers.add(Video.Server(
                            id = "srv$nume",
                            name = serviceName ?: "Serveur $nume",
                            src = embedUrl
                        ))
                    }
                }
            }
        }

        val other = apiUrls.flatMap { apivoirfilm.expand(it, baseUrl, "FR ") } +
            onregardeUrls.flatMap { onregadeou.expand(it, baseUrl, "FR ") }

        return sortServersByLanguage(servers + other)
    }

    /**
     * Sort servers: first by language (FR first, VOSTFR last),
     * then by hosting service reliability within each language group.
     * Language priority: VFF > VF > FR > (no tag) > VOSTFR > VO
     * Reliability: Vidara > Vidsonic > Rpmvid > (unknown) > Filemoon
     */
    private val reliabilityOrder = mapOf(
        "Vidara" to 1,
        "Vidsonic" to 2,
        "Rpmvid" to 3,
        "StreamWish" to 4,
        "Streamix" to 4,
        "Filemoon" to 10,
        "Uqload" to 15,
    )
    private val defaultReliability = 5

    private fun sortServersByLanguage(servers: List<Video.Server>): List<Video.Server> {
        return servers.sortedWith(
            // Servers that have been failing recently get pushed to the bottom
            // (Extractor.healthScore returns 0f for "broken", 1f for healthy).
            // We invert so 0f sorts higher in compareBy → at the bottom of the list.
            compareByDescending<Video.Server> { server ->
                val serviceName = Extractor.identifyServiceName(server.src) ?: ""
                Extractor.healthScore(serviceName)
            }.thenBy { server ->
            val name = server.name.uppercase()
            when {
                name.contains("VFF") -> 0
                name.contains("VF") && !name.contains("VOSTFR") -> 1
                name.contains("FR") && !name.contains("VOSTFR") -> 2
                name.contains("VF") && name.contains("VOSTFR") -> 3
                name.contains("VOSTFR") || name.contains("VOST") -> 5
                name.contains("VO") -> 6
                else -> 4
            }
        }.thenBy { server ->
            val serviceName = Extractor.identifyServiceName(server.src)
            reliabilityOrder[serviceName] ?: defaultReliability
        })
    }

    // 2026-07-02 : BackupRegistry central — 1Jour1Film n'avait AUCUN backup. On ajoute
    //   les backups en PROGRESSIF (le natif d'abord, puis le registre au fil de l'eau) →
    //   toujours du contenu même si le natif 1jour1film échoue. Zéro doublon (dédup registre).
    override fun getServersProgressive(id: String, videoType: Video.Type): kotlinx.coroutines.flow.Flow<List<Video.Server>> =
        kotlinx.coroutines.flow.channelFlow {
            // 1) Serveurs NATIFS 1Jour1Film (émis en premier).
            launch {
                try {
                    val native = getServers(id, videoType)
                    if (native.isNotEmpty()) trySend(native)
                } catch (e: Exception) {
                    android.util.Log.w("UnJourUnFilm", "native getServers: ${e.message}")
                }
            }
            // 2026-07-02 (refonte v2) : les backups arrivent via le REGISTRE central
            //   (PlayerViewModel.launchCentralRegistry) pour TOUS les providers. Ici on
            //   n'émet QUE le natif 1Jour1Film.
            awaitClose { }
        }

    override suspend fun getVideo(server: Video.Server): Video {
        // (Hook BackupRegistry retiré : registre non réintégré pour l'instant.)
        return Extractor.extract(server.src)
    }

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                val addressService = Service.buildAddressFetcher()
                try {
                    val document = addressService.getHome()

                    // 2026-06-19 (user "ça ne m'a pas redirigé auto") : ancien
                    //   pattern `window.location.href = "..."` ne marche plus.
                    //   Le portail 1jour1film2026.site utilise désormais :
                    //   (1) un lien HTML "Accéder à l'adresse actuelle" pointant
                    //       vers la vraie URL, et
                    //   (2) un meta http-equiv="refresh" ou setTimeout/replace.
                    //   On essaye dans l'ordre : <a> link → <meta refresh> →
                    //   script window.location.* → fallback HTML brut.
                    val htmlText = document.html()
                    val newUrl = run {
                        // 1) Le 1er <a href=...> qui pointe vers un domaine 1jour1film*
                        //    (= le bouton "Accéder à l'adresse actuelle")
                        val anchorUrl = document.select("a[href]").asSequence()
                            .map { it.attr("href").trim() }
                            .firstOrNull {
                                it.startsWith("http") &&
                                (it.contains("1jour1film", ignoreCase = true) ||
                                 it.contains("/film", ignoreCase = true)) &&
                                !it.contains("2026.site") &&  // skip self-portal
                                // 2026-08-28 : le filtre "/go" visait les liens
                                //   intermediaires du portail. Maintenant que le portail
                                //   EST /go/, il ne doit ecarter que les liens qui pointent
                                //   vers une page /go/ (boucle), pas une adresse de site qui
                                //   contiendrait "go" ailleurs (ex. un domaine en .gold).
                                !it.contains("/go/", ignoreCase = true)
                            }
                        if (anchorUrl != null) return@run anchorUrl
                        // 2) <meta http-equiv="refresh" content="N;url=...">
                        val metaRefresh = document.select("meta[http-equiv=refresh]")
                            .firstOrNull()?.attr("content")
                            ?.substringAfter("url=", "")
                            ?.trim()
                            ?.takeIf { it.startsWith("http") }
                        if (metaRefresh != null) return@run metaRefresh
                        // 3) Anciennes patterns JS : window.location.href / .replace / .assign
                        val jsPatterns = listOf(
                            "window.location.href = \"", "window.location.href='",
                            "window.location = \"", "window.location='",
                            "window.location.replace(\"", "window.location.replace('",
                            "window.location.assign(\"", "window.location.assign('",
                            "location.href = \"", "location.href='",
                            "location.replace(\"", "location.replace('",
                        )
                        for (pattern in jsPatterns) {
                            val closing = if (pattern.endsWith("\"")) "\"" else "'"
                            val candidate = htmlText.substringAfter(pattern, "")
                                .substringBefore(closing).trim()
                            if (candidate.startsWith("http")) return@run candidate
                        }
                        ""
                    }
                    if (!newUrl.isNullOrEmpty() && newUrl.startsWith("http")) {
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

    /**
     * Recherche via l'API REST WordPress.
     * La page publique « ?s= » est protegee par un WAF qui renvoie 403 ;
     * « wp-json/wp/v2/{movies,tvshows}?search= » reste accessible.
     */
    private suspend fun searchViaApi(query: String): List<AppAdapter.Item> {
        val results = mutableListOf<AppAdapter.Item>()

        try {
            val response = service.searchMoviesApi(search = query)
            val jsonArray = JSONArray(response.string())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val slug = obj.optString("slug", "")
                val title = obj.optJSONObject("title")?.optString("rendered", "") ?: ""
                val poster = try {
                    obj.getJSONObject("_embedded")
                        .getJSONArray("wp:featuredmedia")
                        .getJSONObject(0)
                        .optString("source_url", "")
                } catch (_: Exception) { "" }
                if (slug.isNotEmpty()) {
                    results.add(Movie(id = slug, title = decodeHtml(title), poster = poster))
                }
            }
        } catch (_: Exception) { }

        try {
            val response = service.searchTvShowsApi(search = query)
            val jsonArray = JSONArray(response.string())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val slug = obj.optString("slug", "")
                val title = obj.optJSONObject("title")?.optString("rendered", "") ?: ""
                val poster = try {
                    obj.getJSONObject("_embedded")
                        .getJSONArray("wp:featuredmedia")
                        .getJSONObject(0)
                        .optString("source_url", "")
                } catch (_: Exception) { "" }
                if (slug.isNotEmpty()) {
                    results.add(TvShow(id = slug, title = decodeHtml(title), poster = poster))
                }
            }
        } catch (_: Exception) { }

        return results
    }
    private suspend fun initializeService() {
        initializationMutex.withLock {
            if (serviceInitialized) return
            onChangeUrl()
        }
    }

    private interface Service {

        companion object {
            // 2026-05-22 : timeouts augmentés — le site 1Jour1Film est souvent
            // lent (Cloudflare, CDN variable). 10s causait des SocketTimeoutException
            // fréquentes sur le home. 20s laisse le temps au site de répondre.
            // 2026-10-28 (user : « 1Jour1Film ne revient pas ») : les pages /films/ répondaient
            //   403 en 47 ms à l'appli (pare-feu du site) alors que Chrome les ouvre, même sans
            //   cookie ; l'API wp-json passait. On se présente comme un navigateur récent.
            private val client = NetworkClient.default.newBuilder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val r = chain.request()
                    val page = r.method == "GET" && !r.url.encodedPath.contains("/wp-json/")
                    val req = r.newBuilder()
                        .header("User-Agent", com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_UA)
                        .apply {
                            if (page) {
                                header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                                header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                                header("Sec-Fetch-Dest", "document")
                                header("Sec-Fetch-Mode", "navigate")
                                header("Sec-Fetch-Site", "none")
                                header("Upgrade-Insecure-Requests", "1")
                            }
                        }.build()
                    val cookieAvant = cookiesDe(req.url.toString())
                    val rep = chain.proceed(req)
                    if (!page || rep.code != 403 || rep.header("cf-mitigated") != "challenge") return@addInterceptor rep
                    // 2026-10-30 (signalement « 1 jour 1 film HTTP 403 ») : Cloudflare pose
                    //   maintenant un challenge sur /films/ et /saisons/. On récupère le
                    //   cookie cf_clearance par une WebView invisible, puis on relance.
                    rep.close()
                    synchronized(cfVerrou) {
                        val cookieMaintenant = cookiesDe(req.url.toString())
                        val dejaRafraichi = cookieMaintenant != cookieAvant && cookieMaintenant.contains("cf_clearance=")
                        val maintenant = System.currentTimeMillis()
                        if (!dejaRafraichi && maintenant >= cfPauseJusqua) {
                            Log.i("UnJourUnFilm", "CF challenge sur ${req.url.encodedPath} → WebView invisible")
                            try {
                                kotlinx.coroutines.runBlocking {
                                    com.streamflixreborn.streamflix.utils.WebViewResolver(com.streamflixreborn.streamflix.StreamFlixApp.instance)
                                        .get(req.url.toString(), silent = true, clearanceOnly = true)
                                }
                            } catch (e: Throwable) { Log.w("UnJourUnFilm", "WebView CF : ${e.message}") }
                            val ok = cookiesDe(req.url.toString()).contains("cf_clearance=")
                            Log.i("UnJourUnFilm", "CF cf_clearance ${if (ok) "obtenu" else "ABSENT"}")
                            if (!ok) cfPauseJusqua = maintenant + 60_000L
                        }
                    }
                    // Le cookie cf_clearance est lié au User-Agent ; la WebView a pu normaliser
                    //   STEALTH_UA (vraie version) APRÈS la 1re requête → on relance avec l'UA à jour.
                    val uaAvant = req.header("User-Agent")
                    val uaMaintenant = com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_UA
                    val rep2 = chain.proceed(req.newBuilder().header("User-Agent", uaMaintenant).build())
                    Log.i("UnJourUnFilm", "CF relance ${req.url.encodedPath} → ${rep2.code} (UA ${if (uaAvant == uaMaintenant) "inchangé" else "MIS À JOUR : ${uaAvant?.substringAfter("Chrome/")?.take(14)} → ${uaMaintenant.substringAfter("Chrome/").take(14)}"}, cookie cf=${cookiesDe(req.url.toString()).contains("cf_clearance=")})")
                    if (rep2.code != 403 || rep2.header("cf-mitigated") != "challenge") return@addInterceptor rep2
                    // Télé 2026-10-30 : même avec cookie + bon UA, CF refuse OkHttp (empreinte réseau).
                    //   → la page est chargée PAR la WebView (vrai navigateur), comme Wiflix.
                    rep2.close()
                    val html = synchronized(cfVerrou) {
                        try {
                            kotlinx.coroutines.runBlocking {
                                com.streamflixreborn.streamflix.utils.WebViewResolver(com.streamflixreborn.streamflix.StreamFlixApp.instance)
                                    .get(req.url.toString(), silent = true)
                            }
                        } catch (e: Throwable) { Log.w("UnJourUnFilm", "WebView page : ${e.message}"); "" }
                    }
                    val titre = Regex("<title>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1) ?: ""
                    val pageOk = html.length > 5_000 && !titre.contains("moment", true) &&
                        !titre.contains("instant", true) && !html.contains("<body>Timeout</body>")
                    Log.i("UnJourUnFilm", "CF page via WebView ${req.url.encodedPath} → ${if (pageOk) "OK" else "ÉCHEC"} (${html.length} car., titre « ${titre.take(40)} »)")
                    if (!pageOk) return@addInterceptor chain.proceed(req.newBuilder().header("User-Agent", uaMaintenant).build())
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .header("Content-Type", "text/html; charset=UTF-8")
                        .body(html.toResponseBody("text/html; charset=UTF-8".toMediaTypeOrNull()))
                        .build()
                }
                .build()

            private val cfVerrou = Any()
            @Volatile private var cfPauseJusqua = 0L
            private fun cookiesDe(url: String): String = try {
                android.webkit.CookieManager.getInstance().getCookie(url) ?: ""
            } catch (_: Throwable) { "" }

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

        @GET("wp-json/wp/v2/movies")
        suspend fun searchMoviesApi(
            @Query("search") search: String,
            @Query("per_page") perPage: Int = 20,
            @Query("_fields") fields: String = "id,slug,title,featured_media,_links",
            @Query("_embed") embed: String = "wp:featuredmedia",
            @Header("User-agent") user_agent: String = USER_AGENT
        ): okhttp3.ResponseBody

        @GET("wp-json/wp/v2/tvshows")
        suspend fun searchTvShowsApi(
            @Query("search") search: String,
            @Query("per_page") perPage: Int = 20,
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
