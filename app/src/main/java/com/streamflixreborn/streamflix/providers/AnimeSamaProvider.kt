package com.streamflixreborn.streamflix.providers

import android.util.Log
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
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

object AnimeSamaProvider : Provider, ProviderConfigUrl, ProviderPortalUrl, FilterableProvider {

    override val name = "AnimeSama"
    override val defaultBaseUrl: String = "https://anime-sama.to/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }
    override val defaultPortalUrl: String = "https://anime-sama.pw/"
    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty { field }
        }
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_animesama"
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private const val TAG = "AnimeSama"
    private const val IMG_BASE = "https://raw.githubusercontent.com/Anime-Sama/IMG/img/contenu/"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Cache of all Film slugs from AnimeSama catalogue — used to exclude films from the Série tab
    @Volatile private var cachedFilmSlugs: Set<String>? = null

    /**
     * Fetches all Film slugs from the AnimeSama catalogue (all pages, up to 20).
     * Cached after first call so subsequent pages/tabs are instant.
     */
    private suspend fun getFilmSlugs(): Set<String> {
        cachedFilmSlugs?.let { return it }
        val slugs = mutableSetOf<String>()
        try {
            var page = 1
            while (page <= 5) { // reduced from 20 — enough for exclusion filter
                val filmUrl = "${baseUrl}catalogue/?type[]=Film&page=$page"
                val cards = fetchDocument(filmUrl).select(".catalog-card")
                if (cards.isEmpty()) break
                cards.forEach { card ->
                    card.selectFirst("a[href*=/catalogue/]")?.attr("href")
                        ?.substringAfter("/catalogue/")?.removeSuffix("/")
                        ?.split("/")?.firstOrNull()?.let { slugs.add(it) }
                }
                page++
            }
        } catch (_: Exception) { }
        cachedFilmSlugs = slugs
        return slugs
    }

    private val client = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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

    // Faster client for season probing (shorter timeouts to avoid long waits on 404s)
    private val probeClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var serviceInitialized = false

    private var service: Service = Service.build(baseUrl)

    private suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .build()
        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: ""
        Jsoup.parse(html).apply { setBaseUri(baseUrl) }
    }

    private suspend fun fetchText(url: String): String = fetchTextWith(url, client)

    private suspend fun probeText(url: String): String = fetchTextWith(url, probeClient)

    private suspend fun fetchTextWith(url: String, httpClient: OkHttpClient): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        val text = response.body?.string() ?: ""
        // Detect 404 pages disguised as 200
        if (text.contains("Page introuvable") || text.contains("Accès Introuvable")) {
            throw Exception("Soft 404 detected")
        }
        text
    }

    private suspend fun searchPost(query: String): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("query", query)
            .build()
        val request = Request.Builder()
            .url("${baseUrl}template-php/defaut/fetch.php")
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        response.body?.string() ?: ""
    }

    // ========== HOME ==========

    override suspend fun getHome(): List<Category> {
        initializeService()
        val document = fetchDocument(baseUrl)
        val categories = mutableListOf<Category>()

        // The homepage has .grabScroll containers, each preceded by a sibling
        // flex div containing an h2 section header.
        // Cards inside have: a[href*=/catalogue/], img with alt=title & src=poster,
        // .badge-text for type (Anime/Film), h2.card-title for title.
        val scrollContainers = document.select(".grabScroll")

        for (container in scrollContainers) {
            // Find section header: walk previous siblings to find an h2
            var sectionTitle = ""
            var prev = container.previousElementSibling()
            var attempts = 0
            while (prev != null && attempts < 5) {
                val h2 = prev.selectFirst(":root > h2")
                    ?: if (prev.tagName() == "h2") prev else null
                if (h2 != null) {
                    val parentClass = h2.parent()?.className() ?: ""
                    // Section headers have parent with class "flex", card titles have parent "card-content"
                    if (!parentClass.contains("card-content")) {
                        sectionTitle = h2.text().trim()
                        break
                    }
                }
                prev = prev.previousElementSibling()
                attempts++
            }

            if (sectionTitle.isBlank()) continue
            // 2026-05-03 : sur anime-sama.to/, après "Derniers scans ajoutés"
            // toutes les sections sont scans/mangas/webtoons. On BREAK la boucle
            // dès qu'on rencontre cette section pour ne plus rien parser ensuite
            // (info confirmée par le user : à partir de "Derniers épisodes
            // ajoutés" après c'est que des scans).
            val sectionLowerCheck = sectionTitle.lowercase()
            if (sectionLowerCheck.contains("derniers scans") || sectionLowerCheck.contains("derniers manga")) break
            // Skip individuelles : sections scans/mangas/webtoons
            if (listOf("scan", "manga", "manhwa", "manhua", "webtoon", "light novel", "ln ").any { sectionLowerCheck.contains(it) }) continue
            // Skip "Reprenez votre visionnage" — requires user cookies (not available server-side)
            if (sectionTitle.contains("Reprenez", ignoreCase = true)) continue
            // Skip unreliable film sections — AnimeSama mixes series into "Films Populaires"/"Films Récents"
            if (sectionLowerCheck.contains("film")) continue

            val cards = container.select("a[href*=/catalogue/]")
            if (cards.isEmpty()) continue

            val shows = cards.mapNotNull { card ->
                val href = card.attr("href")
                if (href.isBlank()) return@mapNotNull null

                val slug = href.substringAfter("/catalogue/")
                    .split("/").firstOrNull()?.trim()
                    ?.removeSuffix("/")
                    ?: return@mapNotNull null
                if (slug.isBlank()) return@mapNotNull null

                // Title: prefer img alt, then h2.card-title, then slug
                val imgEl = card.selectFirst("img")
                val title = imgEl?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: card.selectFirst("h2.card-title")?.text()?.trim()
                    ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

                val img = imgEl?.let { el ->
                    el.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                        ?: el.attr("data-src")?.takeIf { it.isNotBlank() }
                } ?: "${IMG_BASE}${slug}.jpg"

                // 2026-05-03 : ALLOWLIST strict — on ne garde QUE les cards qui
                // sont explicitement marquées vidéo (Anime / Film). Tout le reste
                // (scans, mangas, manhwa, webtoons, light novels, ce qu'on n'a
                // jamais vu) est SKIPPÉ. Plus robuste qu'un denylist qu'il faut
                // maintenir au fil des nouveaux types ajoutés par AnimeSama.
                //
                // Marqueurs vidéo (au moins 1 doit matcher) :
                //   1) badge-text contient "anime" ou "film"
                //   2) parent class contient "anime-badge" ou "film-badge"
                //   3) 2e segment du href est un type vidéo (saison*, film, kai,
                //      oav, ova, special, episodes…) ou une langue (vostfr, vf…)
                //
                // Les scans ont href /catalogue/{slug}/scan/{lang} → segment 2
                // = "scan" qui n'est PAS dans l'allowlist → SKIP automatique.
                val badgeText = card.selectFirst(".badge-text")?.text()?.trim()?.lowercase() ?: ""
                val parentBadgeClasses = card.select(".badge").joinToString(" ") { it.className() }.lowercase()
                val pathSegments = href.substringAfter("/catalogue/").split("/").filter { it.isNotBlank() }
                val typeSegment = pathSegments.getOrNull(1)?.lowercase() ?: ""

                val videoBadgeTokens = listOf("anime", "film", "movie", "série", "serie", "ova", "oav", "special", "épisode", "episode")
                val videoTypeSegments = listOf("saison", "film", "movie", "kai", "oav", "ova", "special", "episodes", "vostfr", "vf", "va", "vo")

                val isVideoByBadge = videoBadgeTokens.any { badgeText.contains(it) } ||
                        listOf("anime-badge", "film-badge", "movie-badge").any { parentBadgeClasses.contains(it) }
                val isVideoByPath = videoTypeSegments.any { token ->
                    typeSegment == token || typeSegment.startsWith("${token}-") || typeSegment.startsWith(token) && typeSegment.length <= token.length + 2
                }

                if (!isVideoByBadge && !isVideoByPath) return@mapNotNull null
                // 2026-05-03 : classification Movie vs TvShow basée sur le BADGE
                // par card (plus fiable que le titre de section, car les sections
                // "Sorties du Dimanche/Lundi/etc" mélangent Anime + Film).
                // Fallback sur le titre de section si pas de badge clair.
                val sectionLower = sectionTitle.lowercase()
                val isFilm = badgeText.contains("film") || (badgeText.isBlank() && sectionLower.contains("film"))
                if (isFilm) {
                    Movie(id = slug, title = title, poster = img)
                } else {
                    TvShow(id = slug, title = title, poster = img)
                }
            }

            if (shows.isNotEmpty()) {
                categories.add(Category(name = sectionTitle, list = shows.distinctBy {
                    when (it) {
                        is Movie -> it.id
                        is TvShow -> it.id
                        else -> ""
                    }
                }))
            }
        }

        // FEATURED: Build hero slider from "Derniers contenus sortis" en priorité,
        // sinon fallback sur la première section vidéo disponible (Sorties du jour,
        // animes populaires, etc.). Sans fallback, le carrousel disparait totalement
        // quand AnimeSama renomme/supprime "Derniers contenus sortis" du home.
        val derniersContenus = categories.firstOrNull {
            it.name.lowercase().contains("derniers contenus")
        } ?: categories.firstOrNull {
            val n = it.name.lowercase()
            n.contains("derniers épisodes") || n.contains("derniers episodes") || n.contains("sorties du") || n.contains("ajoutés") || n.contains("ajoutes")
        } ?: categories.firstOrNull { it.list.isNotEmpty() }
        if (derniersContenus != null) {
            val featured = derniersContenus.list.take(10).map { item ->
                when (item) {
                    is Movie -> Movie(
                        id = item.id,
                        title = item.title,
                        banner = item.poster,
                        poster = item.poster
                    )
                    is TvShow -> TvShow(
                        id = item.id,
                        title = item.title,
                        banner = item.poster,
                        poster = item.poster
                    )
                    else -> item
                }
            }
            if (featured.isNotEmpty()) {
                // Enhance banners with TMDB HD backdrops — parallel
                if (UserPreferences.enableTmdb) {
                    val animeLanguages = setOf("ja", "ko", "zh")
                    coroutineScope {
                        featured.map { item ->
                            async(Dispatchers.IO) {
                                try {
                                    val title = when (item) {
                                        is Movie -> item.title
                                        is TvShow -> item.title
                                        else -> return@async
                                    }
                                    // 2026-05-05 : normalise le titre avant TMDB (apostrophes
                                    // typographiques, annotations VF/saison/etc. cassent les matchs)
                                    val normalized = com.streamflixreborn.streamflix.utils.TitleNormalizer.cleanForTmdbSearch(title)
                                    val results = TMDb3.Search.multi(normalized.ifBlank { title })
                                    val match = results.results.firstOrNull { result ->
                                        when (result) {
                                            is TMDb3.Movie -> result.originalLanguage in animeLanguages && result.backdropPath != null
                                            is TMDb3.Tv -> result.originalLanguage in animeLanguages && result.backdropPath != null
                                            else -> false
                                        }
                                    }
                                    val tmdbBanner = when (match) {
                                        is TMDb3.Movie -> match.backdropPath?.original
                                        is TMDb3.Tv -> match.backdropPath?.original
                                        else -> null
                                    }
                                    if (tmdbBanner != null) {
                                        when (item) {
                                            is Movie -> item.banner = tmdbBanner
                                            is TvShow -> item.banner = tmdbBanner
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }.awaitAll()
                    }
                }
                categories.add(0, Category(name = Category.FEATURED, list = featured))
            }
        }

        // Reorder: 1.FEATURED 2.Épisodes/récents 3.Séries récentes 4.Films récents 5.Séries 6.Films
        return categories.sortedWith(compareBy { cat ->
            val n = cat.name.lowercase()
            val isRecent = n.contains("récen") || n.contains("nouveau") || n.contains("nouvelle") || n.contains("derni") || n.contains("ajouté")
            val isSeries = n.contains("séri") || n.contains("seri") || n.contains("saison") || n.contains("tv") || n.contains("anim")
            val isFilm = n.contains("film") || n.contains("movie") || n.contains("cinéma")
            when {
                cat.name == Category.FEATURED -> 0
                n.contains("épisode") || n.contains("episode") -> 1
                n.contains("derniers contenus") -> 2
                isRecent && isSeries -> 2
                isRecent && isFilm -> 3
                isSeries -> 4
                isFilm -> 5
                isRecent -> 1
                else -> 6
            }
        })
    }

    // ========== SEARCH ==========

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            // Return genres so they appear in the search screen (like other providers)
            return listOf(
                "Action", "Aventure", "Comédie", "Drame", "Fantastique",
                "Horreur", "Mystère", "Romance", "Science-fiction", "Thriller",
                "Isekai", "Shônen", "Seinen", "Shôjo", "Ecole",
                "Magie", "Crime", "Psychologique", "Sport", "Musique",
                "K-Drama"
            ).map { name ->
                if (name == "K-Drama") Genre(id = "k-drama", name = name)
                else Genre(id = name, name = name)
            }
        }

        val html = searchPost(query)
        val doc = Jsoup.parse(html)

        val rawResults = doc.select("a.asn-search-result").mapNotNull { result ->
            val href = result.attr("href")
            val slug = href.substringAfter("/catalogue/")
                .removeSuffix("/")
                .split("/").firstOrNull() ?: return@mapNotNull null
            val title = result.selectFirst("h3.asn-search-result-title")?.text()?.trim() ?: return@mapNotNull null
            val img = result.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${slug}.jpg"
            Triple(slug, title, img)
        }

        // Determine type by checking panneauAnime entries on each result's catalogue page.
        // Show everything: series, individual films, and OAVs as separate results.
        return coroutineScope {
            rawResults.map { (slug, title, img) ->
                async(Dispatchers.IO) {
                    try {
                        val pageHtml = probeText("${baseUrl}catalogue/$slug/")
                        val cleanHtml = pageHtml.replace(Regex("""/\*[\s\S]*?\*/"""), "")
                        val panneauRegex = Regex("""panneauAnime\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")
                        val entriesWithPath = panneauRegex.findAll(cleanHtml).mapNotNull { match ->
                            val label = match.groupValues[1].trim()
                            val path = match.groupValues[2].trim()
                            if (label == "nom" && path == "url") null else (label to path)
                        }.toList()

                        val hasFilm = entriesWithPath.any { it.first.equals("Film", ignoreCase = true) }
                        val hasOav = entriesWithPath.any { it.first.equals("OAV", ignoreCase = true) }
                        val hasSeason = entriesWithPath.any {
                            !it.first.equals("Film", ignoreCase = true) && !it.first.equals("OAV", ignoreCase = true)
                        }

                        val items = mutableListOf<AppAdapter.Item>()

                        // Add TvShow if there are seasons
                        if (hasSeason) {
                            items.add(TvShow(id = slug, title = title, poster = img))
                        }

                        // Helper: count episodes in a folder by probing episodes.js
                        suspend fun countEpisodes(folder: String): Int {
                            for (lang in listOf("vostfr", "vf")) {
                                try {
                                    val epsJs = probeText("${baseUrl}catalogue/$slug/$folder/$lang/episodes.js")
                                    val eps1 = Regex("""var\s+eps1\s*=\s*\[([\s\S]*?)\]""").find(epsJs)?.groupValues?.get(1) ?: ""
                                    val count = Regex("""['"]https?://[^'"]+['"]""").findAll(eps1).count()
                                    if (count > 0) return count
                                } catch (_: Exception) {}
                            }
                            return 1
                        }

                        // Add individual films
                        if (hasFilm) {
                            val filmCount = countEpisodes("film")
                            if (filmCount <= 1) {
                                items.add(Movie(id = "$slug@film0", title = if (hasSeason || hasOav) "$title (Film)" else title, poster = img))
                            } else {
                                for (i in 0 until filmCount) {
                                    items.add(Movie(id = "$slug@film$i", title = "$title - Film ${i + 1}", poster = img))
                                }
                            }
                        }

                        // Add individual OAVs
                        if (hasOav) {
                            val oavCount = countEpisodes("oav")
                            if (oavCount <= 1) {
                                items.add(Movie(id = "$slug@oav0", title = "$title (OAV)", poster = img))
                            } else {
                                for (i in 0 until oavCount) {
                                    items.add(Movie(id = "$slug@oav$i", title = "$title - OAV ${i + 1}", poster = img))
                                }
                            }
                        }

                        // Fallback if nothing found
                        if (items.isEmpty()) {
                            items.add(TvShow(id = slug, title = title, poster = img))
                        }

                        items.map { it as AppAdapter.Item }
                    } catch (_: Exception) {
                        listOf(TvShow(id = slug, title = title, poster = img) as AppAdapter.Item)
                    }
                }
            }.awaitAll().flatten()
        }
    }

    // ========== MOVIES ==========

    override suspend fun getMovies(page: Int): List<Movie> {
        // FR tab: returns BOTH series (isSeries=true) and films (isSeries=false)
        // No per-item probing — classify by catalogue type (2 requests total instead of N+1)
        val movies = mutableListOf<Movie>()

        // 1. Anime series with VF → Movie with isSeries=true
        try {
            val animeUrl = "${baseUrl}catalogue/?type[]=Anime&langue[]=vf&page=$page"
            fetchDocument(animeUrl).select(".catalog-card").mapNotNull { card ->
                val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                    .split("/").firstOrNull() ?: return@mapNotNull null
                val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                val img = card.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${slug}.jpg"
                Movie(id = "$slug@vf", title = title, poster = img).also { it.isSeries = true }
            }.let { movies.addAll(it) }
        } catch (_: Exception) {}

        // 2. Standalone films from catalogue type=Film → Movie with isSeries=false
        try {
            val filmUrl = "${baseUrl}catalogue/?type[]=Film&page=$page"
            fetchDocument(filmUrl).select(".catalog-card").mapNotNull { card ->
                val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                    .split("/").firstOrNull() ?: return@mapNotNull null
                val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                val img = card.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${slug}.jpg"
                Movie(id = "$slug@film0", title = title, poster = img)
            }.let { movies.addAll(it) }
        } catch (_: Exception) {}

        return movies
    }

    // ========== TV SHOWS ==========

    override suspend fun getTvShows(page: Int): List<TvShow> {
        // Default: load only anime series (fast — single request)
        return getFilteredTvShows("serie", page)
    }

    override suspend fun getFilteredTvShows(language: String, page: Int): List<TvShow> {
        // "language" is reused as type filter: "serie" or "film"
        return when (language) {
            "film" -> {
                // Films only — from catalogue type=Film
                try {
                    val filmUrl = "${baseUrl}catalogue/?type[]=Film&page=$page"
                    fetchDocument(filmUrl).select(".catalog-card").mapNotNull { card ->
                        val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                        val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                            .split("/").firstOrNull() ?: return@mapNotNull null
                        val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                        val img = card.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${slug}.jpg"
                        TvShow(id = "$slug@vostfr-film0", title = title, poster = img).also { it.isMovie = true }
                    }
                } catch (_: Exception) { emptyList() }
            }
            else -> {
                // Series only — fetch Anime VOSTFR, exclude anything in the Film catalogue
                try {
                    val filmSlugs = getFilmSlugs()
                    val animeUrl = "${baseUrl}catalogue/?type[]=Anime&langue[]=vostfr&page=$page"
                    fetchDocument(animeUrl).select(".catalog-card").mapNotNull { card ->
                        val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                        val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                            .split("/").firstOrNull() ?: return@mapNotNull null
                        if (slug in filmSlugs) return@mapNotNull null // exclude films
                        val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                        val img = card.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${slug}.jpg"
                        TvShow(id = "$slug@vostfr", title = title, poster = img)
                    }
                } catch (_: Exception) { emptyList() }
            }
        }
    }

    override suspend fun getFilteredMovies(language: String, page: Int): List<Movie> {
        // Fast path: single catalogue request per tab (no per-item probing)
        return when (language) {
            "film" -> {
                // Films only — from catalogue type=Film
                try {
                    val filmUrl = "${baseUrl}catalogue/?type[]=Film&page=$page"
                    fetchDocument(filmUrl).select(".catalog-card").mapNotNull { card ->
                        val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                        val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                            .split("/").firstOrNull() ?: return@mapNotNull null
                        val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                        val img = card.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${slug}.jpg"
                        Movie(id = "$slug@film0", title = title, poster = img)
                    }
                } catch (_: Exception) { emptyList() }
            }
            else -> {
                // Series (Anime VF) — exclude anything in the Film catalogue
                try {
                    val filmSlugs = getFilmSlugs()
                    val animeUrl = "${baseUrl}catalogue/?type[]=Anime&langue[]=vf&page=$page"
                    fetchDocument(animeUrl).select(".catalog-card").mapNotNull { card ->
                        val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                        val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                            .split("/").firstOrNull() ?: return@mapNotNull null
                        if (slug in filmSlugs) return@mapNotNull null // exclude films
                        val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                        val img = card.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${slug}.jpg"
                        Movie(id = "$slug@vf", title = title, poster = img).also { it.isSeries = true }
                    }
                } catch (_: Exception) { emptyList() }
            }
        }
    }

    // ========== MOVIE DETAIL ==========

    override suspend fun getMovie(id: String): Movie {
        val slug = id.substringBefore("@")
        val document = fetchDocument("${baseUrl}catalogue/$slug/")
        val html = document.html()

        val title = document.title().substringBefore(" |").trim()
        val synopsisMatch = Regex("SYNOPSIS[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(html)
        val synopsis = synopsisMatch?.groupValues?.get(1)?.let { Jsoup.parse(it).text() } ?: ""
        val genresMatch = Regex("GENRES[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(html)
        val genresText = genresMatch?.groupValues?.get(1)?.let { Jsoup.parse(it).text() } ?: ""

        // ── Search for related content from the same franchise ──
        val relatedFilms = mutableListOf<Show>()
        try {
            val searchHtml = searchPost(title)
            val searchDoc = Jsoup.parse(searchHtml)
            searchDoc.select("a.asn-search-result").mapNotNull { result ->
                val href = result.attr("href")
                val resultSlug = href.substringAfter("/catalogue/")
                    .removeSuffix("/").split("/").firstOrNull() ?: return@mapNotNull null
                if (resultSlug == slug) return@mapNotNull null // Skip self
                val resultTitle = result.selectFirst("h3.asn-search-result-title")?.text()?.trim()
                    ?: return@mapNotNull null
                val resultImg = result.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${resultSlug}.jpg"
                // Show as a TvShow (clickable → will open detail with seasons/films)
                TvShow(id = resultSlug, title = resultTitle, poster = resultImg)
            }.let { relatedFilms.addAll(it) }
        } catch (_: Exception) {}

        return Movie(
            id = id,
            title = title,
            overview = synopsis,
            poster = "${IMG_BASE}${slug}.jpg",
            genres = genresText.split(",").map { Genre(id = it.trim().lowercase(), name = it.trim()) },
            recommendations = relatedFilms,
        )
    }

    // ========== TV SHOW DETAIL ==========

    override suspend fun getTvShow(id: String): TvShow {
        val slug = id.substringBefore("@")
        val afterAt = id.substringAfter("@", "")
        // Parse forced language: supports "vostfr", "vf", "vostfr-film0", "vf-film2", etc.
        val forcedLang = when {
            afterAt.startsWith("vostfr") -> "vostfr"
            afterAt.startsWith("vf") -> "vf"
            else -> null
        }
        // Detect film/OAV entries: "vostfr-film0", "film0", "vf-oav2", etc.
        val filmEntryMatch = Regex("""(film|oav)(\d+)""").find(afterAt)
        val isFilmEntry = filmEntryMatch != null
        val filmFolder = filmEntryMatch?.groupValues?.get(1) ?: "film"
        val filmIndex = filmEntryMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0

        val document = fetchDocument("${baseUrl}catalogue/$slug/")
        val html = document.html()

        val title = document.title().substringBefore(" |").trim()
        val synopsisMatch = Regex("SYNOPSIS[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(html)
        val synopsis = synopsisMatch?.groupValues?.get(1)?.let { Jsoup.parse(it).text() } ?: ""
        val genresMatch = Regex("GENRES[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(html)
        val genresText = genresMatch?.groupValues?.get(1)?.let { Jsoup.parse(it).text() } ?: ""

        val seasons = mutableListOf<Season>()
        val animePoster = "${IMG_BASE}${slug}.jpg"

        // ── Film/OAV entry shortcut ──
        // When the user clicked a specific film from a tab (FR or VOSTFR),
        // go directly to that film's content — no season probing, no language choice.
        if (isFilmEntry) {
            val lang = forcedLang ?: "vostfr"
            val seasonId = "$slug/@filmentry-$filmFolder-$lang-$filmIndex"
            seasons.add(Season(
                id = seasonId,
                number = 1,
                title = title,
                poster = animePoster,
            ))

            // ── Search for related content from the same franchise ──
            val relatedContent = mutableListOf<Show>()
            try {
                val searchHtml = searchPost(title)
                val searchDoc = Jsoup.parse(searchHtml)
                searchDoc.select("a.asn-search-result").mapNotNull { result ->
                    val href = result.attr("href")
                    val resultSlug = href.substringAfter("/catalogue/")
                        .removeSuffix("/").split("/").firstOrNull() ?: return@mapNotNull null
                    if (resultSlug == slug) return@mapNotNull null // Skip self
                    val resultTitle = result.selectFirst("h3.asn-search-result-title")?.text()?.trim()
                        ?: return@mapNotNull null
                    val resultImg = result.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${resultSlug}.jpg"
                    TvShow(id = resultSlug, title = resultTitle, poster = resultImg)
                }.let { relatedContent.addAll(it) }
            } catch (_: Exception) {}

            return TvShow(
                id = id,
                title = title,
                overview = synopsis,
                poster = animePoster,
                genres = genresText.split(",").filter { it.isNotBlank() }.map { Genre(id = it.trim().lowercase(), name = it.trim()) },
                seasons = seasons,
                recommendations = relatedContent,
            )
        }

        val languages = if (forcedLang != null) listOf(forcedLang) else listOf("vostfr", "vf")
        val langLabels = mapOf("vostfr" to "VOSTFR", "vf" to "VF")

        // ── Step 1: parse panneauAnime() calls from page JavaScript ──
        // AnimeSama uses document.write via JS: panneauAnime("Label", "folder/lang");
        // These are NOT rendered as <a> tags in the raw HTML, so we parse the JS calls.
        // IMPORTANT: strip JS block comments (/* ... */) first — AnimeSama often comments out
        // future/placeholder seasons, and our regex would otherwise pick them up.
        val htmlNoComments = html.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        val excludedPrefixes = listOf("kai", "scans", "manga")
        val panneauRegex = Regex("""panneauAnime\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")
        val scrapedFolders = panneauRegex.findAll(htmlNoComments).mapNotNull { match ->
            val label = match.groupValues[1].trim()
            val rawPath = match.groupValues[2].trim() // e.g. "saison2-2/vostfr"
            // Extract the folder part (before the language suffix)
            val folder = rawPath.split("/").firstOrNull()?.trim() ?: return@mapNotNull null
            if (folder.isBlank()) return@mapNotNull null
            // Skip the template/header entry: panneauAnime("nom", "url")
            if (label == "nom" && rawPath == "url") return@mapNotNull null
            // Skip Kai versions, Scans, Manga (match by label prefix or folder prefix)
            val labelLower = label.lowercase()
            if (excludedPrefixes.any { labelLower.startsWith(it) || folder.startsWith(it) }) return@mapNotNull null
            // Skip "Sans fillers" variants (duplicate of the full version)
            if (labelLower.contains("sans filler")) return@mapNotNull null
            // Clean up "Avec fillers" label → just show as season name
            val cleanLabel = if (label.lowercase().contains("avec filler")) {
                // Try to extract a meaningful name from the folder (e.g. "saison1" → "Saison 1")
                val num = Regex("""saison(\d+)""").find(folder)?.groupValues?.get(1)
                if (num != null) "Saison $num" else label
            } else label
            folder to cleanLabel
        }.toList().distinctBy { it.first }

        Log.d(TAG, "[Seasons] Parsed ${scrapedFolders.size} panneauAnime entries: ${scrapedFolders.map { "${it.second} -> ${it.first}" }}")

        // ── Step 2: build the list of folder paths to probe ──
        // Use scraped folders if found, otherwise fall back to sequential saison1..20
        data class SeasonFolder(val path: String, val label: String)

        val foldersToProbe = if (scrapedFolders.isNotEmpty()) {
            scrapedFolders.map { (folder, label) -> SeasonFolder(folder, label) }
        } else {
            // Fallback: probe saison1..20 sequentially
            (1..20).map { SeasonFolder("saison$it", "Saison $it") }
        }

        // ── Step 3: probe each folder for each language ──
        // langSeasonFolders maps lang -> list of SeasonFolder that have valid episodes
        val langSeasonFolders = mutableMapOf<String, MutableList<SeasonFolder>>()
        var consecutiveMisses = 0
        // Track eps1 content per lang to deduplicate seasons with identical episodes
        val seenEps1PerLang = mutableMapOf<String, MutableSet<String>>()

        for (folder in foldersToProbe) {
            var anyFound = false
            for (lang in languages) {
                try {
                    val probeUrl = "${baseUrl}catalogue/$slug/${folder.path}/$lang/episodes.js"
                    val text = probeText(probeUrl)
                    if (text.contains("var eps1") && text.contains("http")) {
                        val eps1Content = Regex("""var\s+eps1\s*=\s*\[([\s\S]*?)\]""").find(text)?.groupValues?.get(1) ?: ""
                        val urlCount = Regex("""['"]https?://[^'"]+['"]""").findAll(eps1Content).count()
                        if (urlCount >= 1) {
                            // Deduplicate: skip if another season already has identical eps1 URLs
                            val eps1Urls = Regex("""['"]https?://[^'"]+['"]""").findAll(eps1Content)
                                .map { it.value }.sorted().joinToString("|")
                            val seen = seenEps1PerLang.getOrPut(lang) { mutableSetOf() }
                            if (seen.contains(eps1Urls)) {
                                Log.d(TAG, "[Seasons] Skipping duplicate $slug/${folder.path}/$lang (same eps1 as earlier season)")
                                continue
                            }
                            seen.add(eps1Urls)
                            langSeasonFolders.getOrPut(lang) { mutableListOf() }.add(folder)
                            anyFound = true
                        } else {
                            Log.d(TAG, "[Seasons] Skipping $slug/${folder.path}/$lang: only $urlCount URL(s)")
                        }
                    }
                } catch (_: Exception) {}
            }
            if (anyFound) {
                consecutiveMisses = 0
            } else {
                consecutiveMisses++
                // For sequential probing (no scraped folders), stop after 3 consecutive misses
                if (scrapedFolders.isEmpty() && consecutiveMisses >= 3 && langSeasonFolders.isNotEmpty()) break
                if (scrapedFolders.isEmpty() && consecutiveMisses >= 4) break
            }
        }

        // Fallback: try without subfolder (single-season anime: slug/vf/episodes.js)
        if (langSeasonFolders.isEmpty()) {
            for (lang in languages) {
                try {
                    val probeUrl = "${baseUrl}catalogue/$slug/$lang/episodes.js"
                    val text = probeText(probeUrl)
                    if (text.contains("var eps1") && text.contains("http")) {
                        langSeasonFolders.getOrPut(lang) { mutableListOf() }.add(SeasonFolder("", "Épisodes"))
                    }
                } catch (_: Exception) {}
            }
        }

        // If forced language found nothing, try the other language as fallback
        if (langSeasonFolders.isEmpty() && forcedLang != null) {
            val fallbackLang = if (forcedLang == "vf") "vostfr" else "vf"
            val fallbackFolders = if (scrapedFolders.isNotEmpty()) {
                scrapedFolders.map { (folder, label) -> SeasonFolder(folder, label) }
            } else {
                (1..3).map { SeasonFolder("saison$it", "Saison $it") }
            }
            for (folder in fallbackFolders) {
                try {
                    val probeUrl = "${baseUrl}catalogue/$slug/${folder.path}/$fallbackLang/episodes.js"
                    val text = probeText(probeUrl)
                    if (text.contains("var eps1") && text.contains("http")) {
                        val eps1Content = Regex("""var\s+eps1\s*=\s*\[([\s\S]*?)\]""").find(text)?.groupValues?.get(1) ?: ""
                        val urlCount = Regex("""['"]https?://[^'"]+['"]""").findAll(eps1Content).count()
                        if (urlCount >= 1) {
                            langSeasonFolders.getOrPut(fallbackLang) { mutableListOf() }.add(folder)
                        }
                    }
                } catch (_: Exception) {}
            }
            // Also try without subfolder
            if (langSeasonFolders.isEmpty()) {
                try {
                    val probeUrl = "${baseUrl}catalogue/$slug/$fallbackLang/episodes.js"
                    val text = probeText(probeUrl)
                    if (text.contains("var eps1") && text.contains("http")) {
                        langSeasonFolders.getOrPut(fallbackLang) { mutableListOf() }.add(SeasonFolder("", "Épisodes"))
                    }
                } catch (_: Exception) {}
            }
        }

        if (forcedLang != null || langSeasonFolders.size == 1) {
            // Language already chosen (from VF/VOSTFR tab) or only one exists: show seasons directly
            val entry = langSeasonFolders.entries.firstOrNull()
            val lang = entry?.key ?: "vostfr"
            val folders = entry?.value ?: mutableListOf()
            for ((idx, folder) in folders.withIndex()) {
                val seasonId = if (folder.path.isEmpty()) "$slug/$lang" else "$slug/${folder.path}/$lang"
                seasons.add(Season(
                    id = seasonId,
                    number = idx + 1,
                    title = folder.label,
                    poster = animePoster,
                ))
            }
        } else {
            // No forced language and multiple languages: show language folders (VOSTFR / VF)
            var idx = 0
            for (lang in languages) {
                val folders = langSeasonFolders[lang] ?: continue
                val label = langLabels[lang] ?: lang.uppercase()
                idx++
                val folderPaths = folders.joinToString(",") { it.path.ifEmpty { "0" } }
                val folderLabels = folders.joinToString("|") { it.label }
                val folderId = "$slug/@$lang/$folderPaths/$folderLabels"
                seasons.add(Season(
                    id = folderId,
                    number = idx,
                    title = label,
                    poster = animePoster,
                ))
            }
        }

        return TvShow(
            id = id,
            title = title,
            overview = synopsis,
            poster = "${IMG_BASE}${slug}.jpg",
            genres = genresText.split(",").filter { it.isNotBlank() }.map { Genre(id = it.trim().lowercase(), name = it.trim()) },
            seasons = seasons,
        )
    }

    // ========== EPISODES ==========

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // Three formats:
        // 1. Film entry:      "slug/@filmentry-film-vostfr-0" → single film episode
        // 2. Language folder:  "slug/@vostfr/path1,path2/Label1|Label2" → sub-seasons
        // 3. Direct season:    "slug/saison1/vostfr" or "slug/vostfr" → single season

        // ── Film entry shortcut ──
        val filmEntryRegex = Regex("""(.+)/@filmentry-(film|oav)-(\w+)-(\d+)""")
        val filmEntryMatch = filmEntryRegex.find(seasonId)
        if (filmEntryMatch != null) {
            val feSlug = filmEntryMatch.groupValues[1]
            val feFolder = filmEntryMatch.groupValues[2]
            val feLang = filmEntryMatch.groupValues[3]
            val feIndex = filmEntryMatch.groupValues[4].toIntOrNull() ?: 0
            val episodePoster = "${IMG_BASE}${feSlug}.jpg"
            // Return a single episode that points to the specific film
            return listOf(Episode(
                id = "$feSlug/$feFolder/$feLang/${feIndex + 1}",
                number = 1,
                title = "Film${if (feIndex > 0) " ${feIndex + 1}" else ""}",
                poster = episodePoster,
            ))
        }

        val langFolderRegex = Regex("""(.+)/@(\w+)/([^/]+)/(.+)""")
        val langMatch = langFolderRegex.find(seasonId)

        // Extract anime slug for episode poster (use the anime's cover image)
        val animeSlug = langMatch?.groupValues?.get(1)
            ?: seasonId.split("/").firstOrNull() ?: seasonId
        val episodePoster = "${IMG_BASE}${animeSlug}.jpg"

        if (langMatch != null) {
            // Language folder mode: return sub-seasons as fake episodes
            val slug = langMatch.groupValues[1]
            val lang = langMatch.groupValues[2]
            val folderPaths = langMatch.groupValues[3].split(",")
            val folderLabels = langMatch.groupValues[4].split("|")
            Log.d(TAG, "[Episodes] Language folder: lang=$lang, folders=$folderPaths, labels=$folderLabels")

            val episodes = mutableListOf<Episode>()
            for ((idx, path) in folderPaths.withIndex()) {
                val subSeasonId = if (path == "0") "$slug/$lang" else "$slug/$path/$lang"
                val label = folderLabels.getOrElse(idx) { path }
                episodes.add(Episode(
                    id = "@subfolder:$subSeasonId",
                    number = idx + 1,
                    title = label,
                    poster = episodePoster,
                    overview = "@subfolder",
                ))
            }
            Log.d(TAG, "[Episodes] Language folder: ${episodes.size} sub-seasons")
            return episodes
        }

        // Direct season mode
        val url = "${baseUrl}catalogue/$seasonId/episodes.js"
        Log.d(TAG, "[Episodes] Fetching: $url")

        val episodesJs = try {
            val text = fetchText(url)
            if (text.contains("var eps1")) text else ""
        } catch (e: Exception) {
            Log.e(TAG, "[Episodes] Error fetching: ${e.message}")
            ""
        }

        if (episodesJs.isBlank()) {
            Log.w(TAG, "[Episodes] No valid episodes.js for seasonId=$seasonId")
            return emptyList()
        }

        val eps1Regex = Regex("""var\s+eps1\s*=\s*\[([\s\S]*?)\]""")
        val eps1Content = eps1Regex.find(episodesJs)?.groupValues?.get(1) ?: ""
        val urlRegex = Regex("""['"]((https?://[^'"]+))['"]""")
        val urls = urlRegex.findAll(eps1Content).map { it.groupValues[1] }.toList()

        Log.d(TAG, "[Episodes] Found ${urls.size} episodes for $seasonId")

        return urls.mapIndexed { index, _ ->
            Episode(
                id = "$seasonId/${index + 1}",
                number = index + 1,
                title = "Episode ${index + 1}",
                poster = episodePoster,
            )
        }
    }

    // ========== SERVERS ==========

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        val urlRegex = Regex("""['"]((https?://[^'"]+))['"]""")
        val epsRegex = Regex("""var\s+(eps\w+)\s*=\s*\[([\s\S]*?)\]""")

        if (videoType is Video.Type.Movie) {
            // Movies/OAV: ID format is "slug@filmN" or "slug@oavN" where N is the index (0-based)
            // or legacy "slug@vf"/"slug@vostfr"/"slug" (defaults to film index 0)
            val movieSlug = id.substringBefore("@")
            val filmMatch = Regex("""@(film|oav)(\d+)""").find(id)
            val folder = filmMatch?.groupValues?.get(1) ?: "film"
            val filmIndex = filmMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
            Log.d(TAG, "[Movie Servers] Starting for id=$id slug=$movieSlug folder=$folder filmIndex=$filmIndex")
            val languages = listOf("vf", "vostfr")
            val langLabels = mapOf("vostfr" to "VOSTFR", "vf" to "VF")

            val langResults = coroutineScope {
                languages.map { lang ->
                    async {
                        val fetchUrl = "${baseUrl}catalogue/$movieSlug/$folder/$lang/episodes.js"
                        Log.d(TAG, "[Movie Servers] Fetching: $fetchUrl")
                        val episodesJs = try {
                            fetchText(fetchUrl)
                        } catch (e: Exception) {
                            Log.e(TAG, "[Movie Servers] Fetch failed for $lang: ${e.message}")
                            return@async emptyList<Video.Server>()
                        }
                        Log.d(TAG, "[Movie Servers] $lang response: length=${episodesJs.length}, hasEps1=${episodesJs.contains("var eps1")}")
                        if (episodesJs.isBlank() || !episodesJs.contains("var eps1")) return@async emptyList()

                        val langServers = mutableListOf<Video.Server>()
                        for (match in epsRegex.findAll(episodesJs)) {
                            val varName = match.groupValues[1]
                            val urls = urlRegex.findAll(match.groupValues[2])
                                .map { it.groupValues[1] }.toList()
                            Log.d(TAG, "[Movie Servers] $lang $varName: ${urls.size} urls, using index $filmIndex")
                            if (filmIndex < urls.size) {
                                val url = urls[filmIndex]
                                val serverName = getServerName(varName, url)
                                val langSuffix = if (languages.size > 1) " (${langLabels[lang]})" else ""
                                langServers.add(Video.Server(
                                    id = "${varName}_${lang}_$filmIndex",
                                    name = "$serverName$langSuffix",
                                    src = url,
                                ))
                            }
                        }
                        langServers
                    }
                }.awaitAll()
            }
            langResults.forEach { servers.addAll(it) }
            Log.d(TAG, "[Movie Servers] Total servers found: ${servers.size}")
        } else {
            // TV Episodes: id format "slug/saison1/lang/3" or "slug/lang/3"
            val parts = id.split("/")
            val episodeNum: Int
            val jsPath: String

            when {
                parts.size >= 4 && parts.last().toIntOrNull() != null -> {
                    episodeNum = parts.last().toInt()
                    jsPath = parts.dropLast(1).joinToString("/")
                }
                parts.size >= 3 && parts.last().toIntOrNull() != null -> {
                    episodeNum = parts.last().toInt()
                    jsPath = parts.dropLast(1).joinToString("/")
                }
                else -> return emptyList()
            }
            val episodeIndex = episodeNum - 1

            val episodesJs = try {
                fetchText("${baseUrl}catalogue/$jsPath/episodes.js")
            } catch (e: Exception) {
                Log.e(TAG, "[Servers] Error fetching episodes.js: ${e.message}")
                return emptyList()
            }
            if (episodesJs.isBlank()) return emptyList()

            for (match in epsRegex.findAll(episodesJs)) {
                val varName = match.groupValues[1]
                val urls = urlRegex.findAll(match.groupValues[2])
                    .map { it.groupValues[1] }.toList()

                if (episodeIndex < urls.size) {
                    val url = urls[episodeIndex]
                    val serverName = getServerName(varName, url)
                    servers.add(Video.Server(
                        id = varName,
                        name = serverName,
                        src = url,
                    ))
                }
            }
        }

        // 2026-05-10 : RÈGLE STRICTE — tout VO/VOSTFR à la fin, jamais mélangé
        // avec le VF. PRIMARY = langue (VF avant VO/VOSTFR), SECONDARY = priorité
        // extracteur (Sibnet > SendVid > VidMoLy > autres > Lpayer).
        servers.sortWith(
            compareBy<Video.Server> { srv ->
                // 0 = VF (ou aucun tag), 1 = VO/VOSTFR (toujours en bas)
                val n = srv.name
                when {
                    n.contains("VOSTFR", ignoreCase = true) -> 1
                    n.contains("(VO)", ignoreCase = true) -> 1
                    Regex("""\bVO\b""").containsMatchIn(n) -> 1
                    else -> 0
                }
            }.thenByDescending { srv ->
                when {
                    srv.name.contains("Sibnet", ignoreCase = true) -> 3
                    srv.name.contains("SendVid", ignoreCase = true) -> 2
                    srv.name.contains("VidMoLy", ignoreCase = true) -> 1
                    // Lpayer en dernier: WebView + décryptage = lent (~5-10s)
                    srv.name.contains("Lpayer", ignoreCase = true) -> -1
                    else -> 0
                }
            }
        )

        // 2026-05-05 : Moviebox + Cloudstream backups pour les animes.
        val slug = id.substringBefore("@").substringBefore("/")
        val title = slug.replace("-", " ").trim()

        // 2026-05-06 : Cloudstream backup #2 — MovieBox+ via /resource (FR strict).
        val cloudstreamBackup = if (title.isNotBlank()) {
            try {
                val csVideoType: Video.Type = when (videoType) {
                    is Video.Type.Movie -> Video.Type.Movie(
                        id = "0", title = title,
                        releaseDate = videoType.releaseDate, poster = videoType.poster,
                        imdbId = videoType.imdbId,
                    )
                    is Video.Type.Episode -> Video.Type.Episode(
                        id = "0", number = videoType.number, title = videoType.title,
                        poster = videoType.poster, overview = videoType.overview,
                        tvShow = videoType.tvShow.copy(id = "0", title = title),
                        season = videoType.season,
                    )
                }
                CloudstreamProvider.getServers("0", csVideoType)
                    .also { if (it.isNotEmpty()) Log.d(TAG, "+ Cloudstream: ${it.size}") }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        val movieboxBackup = if (title.isNotBlank()) {
            try {
                val type = if (videoType is Video.Type.Movie) 1 else 2
                MovieboxProvider.getMovieboxSourcesByTitle(
                    title, null, type,
                    seasonNumber = if (videoType is Video.Type.Episode) videoType.season.number else null,
                    episodeNumber = if (videoType is Video.Type.Episode) videoType.number else null,
                )
                    .also { if (it.isNotEmpty()) Log.d(TAG, "+ Moviebox: ${it.size}") }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        // 2026-05-06 : Papadustream backup EN DERNIER (captcha CF). Anime providers
        // qui ont aussi du contenu sur Papadustream (séries TV-only) bénéficient.
        val papaBackup = if (title.isNotBlank() && videoType is Video.Type.Episode) {
            try {
                PapadustreamProvider.getPapaSourcesByTitle(
                    title = title,
                    seasonNum = videoType.season.number,
                    episodeNum = videoType.number,
                ).also { if (it.isNotEmpty()) Log.d(TAG, "+ Papa: ${it.size}") }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        // 2026-05-10 v3 : BRUTE FORCE — pénalité -500000 sur tout VO/VOSTFR.
        // Garantit absolument qu'aucun VO/VOSTFR ne peut jamais passer au-dessus
        // d'un VF, peu importe quelle priorité interne il a. sortedByDescending
        // est stable donc préserve l'ordre relatif au sein de chaque groupe.
        val isVoLike: (Video.Server) -> Boolean = { srv ->
            val n = srv.name
            n.contains("VOSTFR", ignoreCase = true) ||
            n.contains("(VO)", ignoreCase = true) ||
            Regex("""\bVO\b""").containsMatchIn(n)
        }
        val all = servers + cloudstreamBackup + movieboxBackup + papaBackup
        val sorted = all.withIndex().sortedByDescending { (idx, srv) ->
            // Score: idx inversé (préserve ordre original) + -500000 si VO/VOSTFR
            val voOffset = if (isVoLike(srv)) -500000 else 0
            (1000 - idx) + voOffset  // les VF gardent leur ordre, les VO chutent
        }.map { it.value }
        return sorted
    }

    // ========== HELPERS ==========

    private fun getServerName(varName: String, url: String): String = when {
        varName == "epsAS" -> "AnimeSama"
        url.contains("anime-sama.fr") -> "AnimeSama"
        url.contains("sibnet") -> "Sibnet"
        url.contains("vidmoly") -> "VidMoLy"
        url.contains("vk.com") || url.contains("vkvideo.ru") -> "VK"
        url.contains("sendvid") -> "SendVid"
        url.contains("myvi") -> "MyVi"
        url.contains("oneupload") -> "OneUpload"
        url.contains("smoothpre", ignoreCase = true) -> "Smoothpre"
        url.contains("filemoon") -> "Filemoon"
        url.contains("minochinos") -> "Minochinos"
        url.contains("lpayer") || url.contains("embed4me") -> "Lpayer"
        else -> {
            try {
                java.net.URL(url).host.substringBefore(".").replaceFirstChar { it.uppercase() }
            } catch (_: Exception) { varName }
        }
    }

    // ========== VIDEO ==========

    override suspend fun getVideo(server: Video.Server): Video {
        val url = server.src
        Log.d(TAG, "[getVideo] Server: ${server.name}, src: $url")

        // 2026-05-10 : Direct MP4 links hébergés sur anime-sama.fr (subdomains
        // s5/s22.anime-sama.fr). Les "redirections" .to/.pw/.si/.tv/.org sont
        // UNIQUEMENT au niveau du site web (page HTML), le CDN de stockage des
        // MP4 n'existe que sur .fr. Donc PAS de rewrite — on garde l'URL .fr
        // d'origine. Le DNS .fr peut être bloqué chez certains FAI (Tahiti) ;
        // PlayerTvFragment.needsDoH() force OkHttp+DoH pour anime-sama.* qui
        // by-passe ce blocage.
        if (url.contains("anime-sama.", ignoreCase = true) || url.endsWith(".mp4")) {
            return Video(
                source = url,
                headers = mapOf(
                    "Referer" to "https://anime-sama.fr/",
                    "User-Agent" to USER_AGENT
                )
            )
        }

        // Use the project's existing extractors (Sibnet, VidMoLy, SendVid, etc.)
        return try {
            val video = Extractor.extract(url)
            Log.d(TAG, "[getVideo] Extractor succeeded for ${server.name}: ${video.source.take(80)}")
            video
        } catch (e: Exception) {
            Log.e(TAG, "[getVideo] Extractor failed for ${server.name}: ${e.message}")
            throw e // Let the player auto-fallback to the next server
        }
    }

    // ========== GENRE ==========

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = "${baseUrl}catalogue/?genre[]=$id&type[]=Anime&type[]=Film&page=$page"
        val document = fetchDocument(url)
        return Genre(
            id = id,
            name = id.replaceFirstChar { it.uppercase() },
            shows = document.select(".catalog-card").mapNotNull { card ->
                val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
                val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                    .split("/").firstOrNull() ?: return@mapNotNull null
                val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
                val img = card.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${slug}.jpg"
                TvShow(id = slug, title = title, poster = img)
            },
        )
    }

    // ========== PEOPLE ==========

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = id)
    }

    // ========== URL MANAGEMENT ==========

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                try {
                    Log.d(TAG, "Refreshing URL from portal: $portalUrl")
                    // Fetch the portal page at anime-sama.pw
                    val portalDoc = withContext(Dispatchers.IO) {
                        val request = Request.Builder()
                            .url(portalUrl)
                            .header("User-Agent", USER_AGENT)
                            .build()
                        val response = client.newCall(request).execute()
                        val html = response.body?.string() ?: ""
                        Jsoup.parse(html)
                    }

                    // Extract the redirect link from "Accéder à Anime-Sama" button
                    val redirectLink = portalDoc.selectFirst("a.btn-primary[href*=anime-sama]")
                        ?.attr("href")

                    if (!redirectLink.isNullOrEmpty()) {
                        // Follow the redirect to get the final active domain
                        val finalUrl = withContext(Dispatchers.IO) {
                            val request = Request.Builder()
                                .url(redirectLink)
                                .header("User-Agent", USER_AGENT)
                                .build()
                            val response = client.newCall(request).execute()
                            // The response URL after following redirects is the active domain
                            val url = response.request.url.toString()
                            response.close()
                            url
                        }

                        if (finalUrl.contains("anime-sama")) {
                            val normalizedUrl = if (finalUrl.endsWith("/")) finalUrl else "$finalUrl/"
                            Log.d(TAG, "Resolved active URL: $normalizedUrl")
                            UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, normalizedUrl)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh URL from portal: ${e.message}")
                    // Keep using current URL on failure
                }
            }
            service = Service.build(baseUrl)
            serviceInitialized = true
        }
        return baseUrl
    }

    private val initializationMutex = Mutex()

    private suspend fun initializeService() {
        initializationMutex.withLock {
            if (serviceInitialized) return
            onChangeUrl()
        }
    }

    // ========== SERVICE INTERFACE ==========

    private interface Service {
        @GET("catalogue/{id}/")
        suspend fun getShow(@Path("id") id: String): Document

        @GET
        suspend fun getPage(@Url url: String): Document

        companion object {
            fun build(baseUrl: String): Service {
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }
    }
}
