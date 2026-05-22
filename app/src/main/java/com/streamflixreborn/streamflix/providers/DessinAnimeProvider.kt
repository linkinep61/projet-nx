package com.streamflixreborn.streamflix.providers

import android.util.Log
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
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DessinAnime (dessinanime.cc) — provider FR dessins animés + animes.
 *
 * 2026-05-18 v84 : recodé après suppression (le user dit que le site
 *   remarche). Catalogue classiques (Dragon Ball, Sailor Moon, Astérix,
 *   Tintin, Les Simpson…) + animes modernes + films Disney/Pixar.
 *
 * Architecture du site :
 *   - Next.js App Router (pas de __NEXT_DATA__ JSON ; SSR rendering)
 *   - API JSON propre : /api/search?q=<query> → liste {id,slug,title,
 *     posterPath,mediaType:MOVIE|TV,releaseYear,voteAverage}
 *   - Detail :
 *       Film    : /movie/<slug>
 *       Série   : /tv/<slug>
 *       Épisode : /tv/<slug>/<season>/<episode>
 *   - Chaque page contient un <iframe src="..."> unique vers l'host
 *     vidéo (uqload, sendvid, minochinos, etc.). Pas de sélecteur lecteur
 *     multi-source : un seul iframe par page → un seul serveur par item.
 *
 * Extraction :
 *   - getServers retourne 1 Server avec src = URL iframe
 *   - getVideo délègue à Extractor.extract qui choisit l'extractor
 *     selon le host (uqload, sendvid, minochinos…)
 */
object DessinAnimeProvider : Provider, ProgressiveServersProvider {

    override val name = "DessinAnime"
    override val baseUrl = "https://dessinanime.cc"
    override val logo = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_dessinanime"
    override val language = "fr"

    private const val TAG = "DessinAnime"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    // CookieJar partagé pour éviter les boucles redirect Cloudflare
    //   (sans cookie persistant, le serveur boucle home -> challenge -> home
    //   et OkHttp atteint sa limite de 20 follow-ups).
    private val cookieJar = object : okhttp3.CookieJar {
        private val store = mutableMapOf<String, MutableList<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            synchronized(store) {
                val list = store.getOrPut(url.host) { mutableListOf() }
                cookies.forEach { c ->
                    list.removeAll { it.name == c.name }
                    list.add(c)
                }
            }
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = synchronized(store) {
            store[url.host]?.toList() ?: emptyList()
        }
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .build()
    }

    // ── HTTP helper ────────────────────────────────────────────────────

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", baseUrl)
                .header("Accept", "text/html,application/xhtml+xml,application/json,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET $url → ${resp.code}")
                    null
                } else resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        }
    }

    // ── Parsing helpers ────────────────────────────────────────────────

    /** Convertit un JSONObject de l'API search en Movie ou TvShow. */
    private fun jsonToItem(j: JSONObject): AppAdapter.Item? {
        val slug = j.optString("slug").ifBlank { return null }
        val title = j.optString("title").ifBlank { return null }
        val mediaType = j.optString("mediaType")
        val poster = j.optString("posterPath").takeIf { it.isNotBlank() }
        val year = j.optInt("releaseYear", 0).takeIf { it > 0 }?.toString()
        val rating = j.optDouble("voteAverage", 0.0).takeIf { it > 0 }
        return when (mediaType) {
            "MOVIE" -> Movie(
                id = "movie::$slug",
                title = title,
                released = year,
                poster = poster,
                rating = rating,
            )
            "TV" -> TvShow(
                id = "tv::$slug",
                title = title,
                released = year,
                poster = poster,
                rating = rating,
            )
            else -> null
        }
    }

    /** Métadonnées propres d'un item, extraites du RSC ou des cartes HTML. */
    private data class RichInfo(
        val title: String?,
        val poster: String?,
        val year: String?,
        val rating: Double?,
    )

    /**
     * Construit une table slug -> métadonnées propres en lisant les DEUX sources
     * fiables présentes dans les pages dessinanime.cc :
     *
     *   1. Payload RSC Next.js — objets {id,slug,title,releaseYear,voteAverage,
     *      posterPath,mediaType} IDENTIQUES à /api/search. Présent sur la home
     *      (≈60 items). Source la plus riche : titre réel + poster TMDB absolu
     *      + année + note. Les guillemets y sont échappés (\") dans
     *      self.__next_f.push, d'où l'unescape préalable.
     *   2. Cartes HTML du /catalogue — <img alt="titre" src="posterTMDB"> SUIVI
     *      de <a href="/movie|tv/slug">. CRITIQUE : l'<img> précède le <a>, ce
     *      que l'ancien parseur ratait (il cherchait le poster APRÈS le lien →
     *      posters absents/décalés + titre dérivé du slug).
     *
     * Pur parsing string : pas de Jsoup, pas d'appel TMDB de masse (mémoire
     *   Chromecast / ANR — cf. notes getTvShow).
     */
    private fun buildRichMap(html: String): Map<String, RichInfo> {
        val map = HashMap<String, RichInfo>()
        // 1) Objets RSC (flat, sans accolades imbriquées) une fois déséchappés.
        val un = html.replace("\\\"", "\"")
        Regex("""\{[^{}]*?"slug"\s*:\s*"([^"]+)"[^{}]*?"mediaType"\s*:\s*"(?:MOVIE|TV)"[^{}]*?\}""")
            .findAll(un).forEach { m ->
                try {
                    val o = JSONObject(m.value)
                    val slug = o.optString("slug").ifBlank { return@forEach }
                    map[slug] = RichInfo(
                        title = o.optString("title").takeIf { it.isNotBlank() },
                        poster = o.optString("posterPath").takeIf { it.isNotBlank() },
                        year = o.optInt("releaseYear", 0).takeIf { it > 0 }?.toString(),
                        rating = o.optDouble("voteAverage", 0.0).takeIf { it > 0 },
                    )
                } catch (_: Exception) {}
            }
        // 2) Cartes HTML <img alt src> ... href=/type/slug (catalogue & home HTML).
        Regex("""<img\b[^>]*\balt="([^"]*)"[^>]*\bsrc="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"[\s\S]{0,600}?href="/(?:movie|tv)/([^"/]+)"""")
            .findAll(html).forEach { m ->
                val slug = m.groupValues[3]
                if (map.containsKey(slug)) return@forEach   // RSC déjà plus riche
                val alt = m.groupValues[1].trim()
                map[slug] = RichInfo(
                    title = alt.takeIf { it.isNotBlank() },
                    poster = m.groupValues[2],
                    year = null,
                    rating = null,
                )
            }
        return map
    }

    /** Parse une page (home ou catalogue) en items propres : titre réel +
     *  poster TMDB + année + note (via buildRichMap). L'ordre suit l'ordre DOM
     *  des liens ; fallback titre dérivé du slug si l'item n'est ni dans le RSC
     *  ni dans une carte HTML (rare). */
    private fun parseHomeItems(html: String): List<AppAdapter.Item> {
        val rich = buildRichMap(html)
        val out = mutableListOf<AppAdapter.Item>()
        val seen = mutableSetOf<String>()
        val itemRegex = Regex("""href="/(movie|tv)/([^"/]+)"""")
        for (m in itemRegex.findAll(html)) {
            val type = m.groupValues[1]
            val slug = m.groupValues[2]
            if (!seen.add(slug)) continue
            val info = rich[slug]
            val title = info?.title
                ?: slug.replace(Regex("^\\d+-"), "").replace("-", " ").replaceFirstChar { it.uppercase() }
            val item: AppAdapter.Item = when (type) {
                "movie" -> Movie(id = "movie::$slug", title = title, poster = info?.poster, released = info?.year, rating = info?.rating)
                "tv" -> TvShow(id = "tv::$slug", title = title, poster = info?.poster, released = info?.year, rating = info?.rating)
                else -> continue
            }
            out.add(item)
        }
        return out
    }

    // ── Provider API ────────────────────────────────────────────────────

    /** Récupère une page du catalogue (/catalogue?page=N, 14 items mixés films+tv). */
    private suspend fun fetchCatalogPage(page: Int): List<AppAdapter.Item> {
        val html = httpGet("$baseUrl/catalogue?page=$page") ?: return emptyList()
        return parseHomeItems(html)
    }

    override suspend fun getHome(): List<Category> = kotlinx.coroutines.coroutineScope {
        // Home : page d'accueil (récents) + 4 premières pages catalogue (mix).
        //   Fetch en parallèle pour rester rapide depuis Tahiti.
        val home = async { parseHomeItems(httpGet("$baseUrl/") ?: "") }
        val cat1 = async { fetchCatalogPage(1) }
        val pages = listOf(home.await(), cat1.await())  // v98: home rapide, le reste via enrichment differe

        val seen = mutableSetOf<String>()
        val homeItems = pages[0]
        val homeFilms = homeItems.filterIsInstance<Movie>()
        val homeTvs = homeItems.filterIsInstance<TvShow>()
        homeItems.forEach { seen.add(it.idKey()) }

        val catalogItems = pages.drop(1).flatten().filter { seen.add(it.idKey()) }
        val catalogFilms = catalogItems.filterIsInstance<Movie>()
        val catalogTvs = catalogItems.filterIsInstance<TvShow>()

        val categories = mutableListOf<Category>()
        if (homeFilms.isNotEmpty()) categories.add(Category(name = "Films récents", list = homeFilms.take(30)))
        if (homeTvs.isNotEmpty()) categories.add(Category(name = "Séries récentes", list = homeTvs.take(30)))
        if (catalogFilms.isNotEmpty()) categories.add(Category(name = "Films", list = catalogFilms.take(30)))
        if (catalogTvs.isNotEmpty()) categories.add(Category(name = "Séries", list = catalogTvs.take(30)))
        if (catalogFilms.size > 30) categories.add(Category(name = "Plus de films", list = catalogFilms.drop(30).take(30)))
        if (catalogTvs.size > 30) categories.add(Category(name = "Plus de séries", list = catalogTvs.drop(30).take(30)))

        // 2026-05-20 (user "un seul backdrop du carrousel qui défile derrière le
        //   home, pas un par film/série — sinon ça bouffe la mémoire") : carrousel
        //   ALLÉGÉ. Versus l'ancien (10 items × backdrop ORIGINAL = saturation du
        //   cache 24 Mo de la Chromecast), ici : 6 items max + backdrop w1280
        //   (≈2× plus léger qu'original). Le swiper FEATURED alimente le fond
        //   d'écran (ivHomeBackground) qu'on voit défiler derrière les rangées.
        val featuredSource = (if (homeItems.isNotEmpty()) homeItems else catalogItems)
            .filter { it is Movie || it is TvShow }
            .take(10)
        if (featuredSource.isNotEmpty() && UserPreferences.enableTmdb) {
            val featured = featuredSource.map { item ->
                when (item) {
                    is Movie -> Movie(id = item.id, title = item.title, banner = item.poster, poster = item.poster)
                    is TvShow -> TvShow(id = item.id, title = item.title, banner = item.poster, poster = item.poster)
                    else -> item
                }
            }
            // 2026-05-22 (user "les catégories du home DessinAnime sont longues à
            //   arriver") : l'enrichissement TMDB du carrousel (jusqu'à 10 recherches)
            //   bloquait getHome SANS timeout → tout le home retardé si TMDB rame.
            //   On le borne à 2,5s ; au timeout, les items gardent leur poster comme
            //   bannière (fallback déjà géré plus bas) → home rapide.
            kotlinx.coroutines.withTimeoutOrNull(2_500L) {
            featured.map { item ->
                async(Dispatchers.IO) {
                    try {
                        val title = when (item) {
                            is Movie -> item.title
                            is TvShow -> item.title
                            else -> return@async
                        }
                        val norm = TitleNormalizer.cleanForTmdbSearch(title)
                        val results = TMDb3.Search.multi(norm.ifBlank { title })
                        val match = results.results.firstOrNull { r ->
                            when (r) {
                                is TMDb3.Movie -> r.backdropPath != null
                                is TMDb3.Tv -> r.backdropPath != null
                                else -> false
                            }
                        }
                        val backdrop = when (match) {
                            is TMDb3.Movie -> match.backdropPath?.w1280
                            is TMDb3.Tv -> match.backdropPath?.w1280
                            else -> null
                        }
                        if (backdrop != null) when (item) {
                            is Movie -> item.banner = backdrop
                            is TvShow -> item.banner = backdrop
                        }
                    } catch (_: Exception) {}
                }
            }.awaitAll()
            }  // withTimeoutOrNull : carrousel non bloquant si TMDB rame
            // Priorité aux items avec backdrop TMDB paysage, mais si trop peu
            // (< 3), on complète avec les posters comme banner pour avoir un
            // carrousel qui défile (mieux qu'une seule image fixe).
            val withBackdrop = featured.filter { item ->
                val b = when (item) { is Movie -> item.banner; is TvShow -> item.banner; else -> null }
                b != null && b.contains("/t/p/w1280/")
            }
            val carouselItems = if (withBackdrop.size >= 3) {
                withBackdrop
            } else {
                // Fallback : tous les items avec au moins un poster (utilisé comme banner)
                featured.onEach { item ->
                    val b = when (item) { is Movie -> item.banner; is TvShow -> item.banner; else -> null }
                    if (b == null || !b.contains("/t/p/")) {
                        // Pas de backdrop TMDB → utiliser le poster comme banner
                        when (item) {
                            is Movie -> if (item.poster != null) item.banner = item.poster
                            is TvShow -> if (item.poster != null) item.banner = item.poster
                        }
                    }
                }.filter { item ->
                    val b = when (item) { is Movie -> item.banner; is TvShow -> item.banner; else -> null }
                    !b.isNullOrBlank()
                }
            }
            if (carouselItems.isNotEmpty()) {
                categories.add(0, Category(name = Category.FEATURED, list = carouselItems))
            }
        }

        Log.d(TAG, "getHome → ${categories.size} categories, ${pages.flatten().size} raw items")
        categories
    }

    private fun AppAdapter.Item.idKey(): String = when (this) {
        is Movie -> id
        is TvShow -> id
        else -> hashCode().toString()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = withContext(Dispatchers.IO) {
        // Empty query : expose genre picker (peu de genres exposés par le site,
        //   on retourne juste les sections home en attendant de mapper /api/categories).
        if (query.isBlank()) {
            if (page > 1) return@withContext emptyList()
            return@withContext listOf(
                Genre(id = "all", name = "Tout le catalogue"),
                Genre(id = "movies", name = "Films"),
                Genre(id = "tv", name = "Séries"),
            )
        }
        // API search — pas de pagination native, on retourne tout.
        if (page > 1) return@withContext emptyList()
        val url = "$baseUrl/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val body = httpGet(url) ?: return@withContext emptyList()
        try {
            val arr = JSONArray(body)
            val results = mutableListOf<AppAdapter.Item>()
            for (i in 0 until arr.length()) {
                val item = jsonToItem(arr.optJSONObject(i) ?: continue) ?: continue
                results.add(item)
            }
            Log.d(TAG, "search('$query') → ${results.size} results")
            results
        } catch (e: Exception) {
            Log.w(TAG, "search parse failed: ${e.message}")
            emptyList()
        }
    }

    /** 2026-05-19 v85d (user "il y a meme pas 0,1 % des films dans l'onglet
     *  film et dans serie") : 2 pages catalogue par page user ramenait souvent
     *  0 films (catalog mixe) -> MoviesViewModel set hasMore=false ->
     *  pagination stoppee a la 1ere page. Fix : fetch 8 pages catalogue en
     *  parallele par page user (~112 items, ~40 films garantis) -> la
     *  pagination peut continuer jusqu'a atteindre la fin du catalogue
     *  (~300 pages catalog = ~38 pages user). */
    private val PAGES_PER_USER_PAGE = 8

    override suspend fun getMovies(page: Int): List<Movie> = kotlinx.coroutines.coroutineScope {
        val start = (page - 1) * PAGES_PER_USER_PAGE + 1
        val end = start + PAGES_PER_USER_PAGE - 1
        val pages = (start..end).map { p -> async { fetchCatalogPage(p) } }.map { it.await() }
        val movies = pages.flatten().filterIsInstance<Movie>().distinctBy { it.id }
        Log.d(TAG, "getMovies(page=$page) fetched catalog $start..$end -> ${movies.size} movies")
        movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = kotlinx.coroutines.coroutineScope {
        val start = (page - 1) * PAGES_PER_USER_PAGE + 1
        val end = start + PAGES_PER_USER_PAGE - 1
        val pages = (start..end).map { p -> async { fetchCatalogPage(p) } }.map { it.await() }
        val shows = pages.flatten().filterIsInstance<TvShow>().distinctBy { it.id }
        Log.d(TAG, "getTvShows(page=$page) fetched catalog $start..$end -> ${shows.size} shows")
        shows
    }

    /** Extrait slug + type depuis id "movie::SLUG" ou "tv::SLUG". */
    private fun parseInternalId(id: String): Pair<String, String>? {
        val parts = id.split("::")
        if (parts.size < 2) return null
        if (parts[0] !in setOf("movie", "tv")) return null
        return parts[0] to parts[1]
    }

    override suspend fun getMovie(id: String): Movie = withContext(Dispatchers.IO) {
        val (type, slug) = parseInternalId(id) ?: return@withContext Movie(id = id, title = id)
        if (type != "movie") return@withContext Movie(id = id, title = slug)
        val html = httpGet("$baseUrl/movie/$slug")
        val title = html?.let { Regex("""<h1[^>]*>([^<]+)</h1>""").find(it)?.groupValues?.get(1)?.trim() }
            ?: slug.replace(Regex("^\\d+-"), "").replace("-", " ").replaceFirstChar { it.uppercase() }
        val overview = html?.let {
            Regex("""<p[^>]*>([^<]{80,})</p>""").find(it)?.groupValues?.get(1)?.trim()
        }
        val poster = html?.let {
            Regex("""<meta property="og:image" content="([^"]+)"""").find(it)?.groupValues?.get(1)
        }
        val year = html?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value }
        Movie(id = id, title = title, overview = overview, poster = poster, released = year)
    }

    override suspend fun getTvShow(id: String): TvShow = withContext(Dispatchers.IO) {
        val (type, slug) = parseInternalId(id) ?: return@withContext TvShow(id = id, title = id)
        if (type != "tv") return@withContext TvShow(id = id, title = slug)
        val html = httpGet("$baseUrl/tv/$slug")
        val title = html?.let { Regex("""<h1[^>]*>([^<]+)</h1>""").find(it)?.groupValues?.get(1)?.trim() }
            ?: slug.replace(Regex("^\\d+-"), "").replace("-", " ").replaceFirstChar { it.uppercase() }
        val overview = html?.let {
            Regex("""<p[^>]*>([^<]{80,})</p>""").find(it)?.groupValues?.get(1)?.trim()
        }
        val poster = html?.let {
            Regex("""<meta property="og:image" content="([^"]+)"""").find(it)?.groupValues?.get(1)
        }
        val year = html?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value }
        // 2026-05-20 : parsing LÉGER par regex (PAS de Jsoup.parse sur 355 Ko).
        //   L'enrichment "Continuer à regarder" du home appelle getTvShow +
        //   getEpisodesBySeason en parallèle pour TOUS les shows ; un Jsoup
        //   full-doc x N saturait la Chromecast (384 Mo) → ANR. Le regex
        //   extrait quand même l'image propre de chaque saison (fallback poster).
        val seasons = if (html != null) {
            val nums = Regex("""href="/tv/${Regex.escape(slug)}/(\d+)/1"""")
                .findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it > 0 }.toSet().sorted()
            val imgByNum = HashMap<Int, String>()
            Regex("""href="/tv/${Regex.escape(slug)}/(\d+)/1"[\s\S]{0,350}?<img\b[^>]*?\bsrc="([^"]+)"""")
                .findAll(html).forEach { m ->
                    val n = m.groupValues[1].toIntOrNull() ?: return@forEach
                    if (!imgByNum.containsKey(n)) imgByNum[n] = m.groupValues[2]
                }
            nums.map { n ->
                val raw = imgByNum[n] ?: ""
                val img = when {
                    raw.isBlank() -> poster
                    raw.startsWith("http") -> raw
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("/") -> "$baseUrl$raw"
                    else -> raw
                }
                Season(id = "tv::$slug::s$n", number = n, title = "Saison $n", poster = img)
            }
        } else emptyList<Season>()
        if (seasons.isNotEmpty()) Log.d(TAG, "getTvShow: ${seasons.size} saisons — ex poster=${seasons.first().poster?.take(110)}")
        TvShow(id = id, title = title, overview = overview, poster = poster, released = year, seasons = seasons)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = withContext(Dispatchers.IO) {
        // Format : tv::<slug>::sN
        val parts = seasonId.split("::")
        if (parts.size != 3 || parts[0] != "tv") return@withContext emptyList()
        val slug = parts[1]
        val seasonNum = parts[2].removePrefix("s").toIntOrNull() ?: return@withContext emptyList()
        val html = httpGet("$baseUrl/tv/$slug/$seasonNum/1") ?: return@withContext emptyList()
        // 2026-05-20 : parsing LÉGER regex (PAS de Jsoup full-doc → évite l'ANR
        //   quand l'enrichment home appelle getEpisodesBySeason en parallèle).
        //   Chaque carte = <a href="/tv/slug/season/ep">…<img alt="titre" src="img">.
        val epData = LinkedHashMap<Int, Pair<String?, String?>>()  // num -> (alt, src)
        Regex("""href="/tv/${Regex.escape(slug)}/$seasonNum/(\d+)"[\s\S]{0,500}?<img\b([^>]*)>""")
            .findAll(html).forEach { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@forEach
                if (n <= 0 || epData.containsKey(n)) return@forEach
                val attrs = m.groupValues[2]
                val src = Regex("""\bsrc="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                val alt = Regex("""\balt="([^"]*)"""").find(attrs)?.groupValues?.get(1)
                epData[n] = alt to src
            }
        // Garantir TOUS les épisodes même sans image trouvée
        Regex("""href="/tv/${Regex.escape(slug)}/$seasonNum/(\d+)"""").findAll(html).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@forEach
            if (n > 0 && !epData.containsKey(n)) epData[n] = null to null
        }
        val episodes = epData.entries.sortedBy { it.key }.map { (n, data) ->
            val (alt, rawSrc) = data
            val imgUrl = when {
                rawSrc.isNullOrBlank() -> null
                rawSrc.startsWith("http") -> rawSrc
                rawSrc.startsWith("//") -> "https:$rawSrc"
                rawSrc.startsWith("/") -> "$baseUrl$rawSrc"
                else -> rawSrc
            }
            val altTitle = alt?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("episode", true) && !it.startsWith("Épisode", true) }
            Episode(
                id = "tv::$slug::s$seasonNum::e$n",
                number = n,
                title = altTitle ?: "Épisode $n",
                poster = imgUrl,
            )
        }
        if (episodes.isNotEmpty()) {
            Log.d(TAG, "getEpisodesBySeason: ${episodes.size} eps — ex: '${episodes.first().title}' poster=${episodes.first().poster?.take(110)}")
        }
        episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext Genre(id = id, name = id, shows = emptyList())
        val html = httpGet("$baseUrl/") ?: return@withContext Genre(id = id, name = id, shows = emptyList())
        val all = parseHomeItems(html)
        val filtered: List<com.streamflixreborn.streamflix.models.Show> = when (id) {
            "movies" -> all.filterIsInstance<Movie>()
            "tv" -> all.filterIsInstance<TvShow>()
            else -> all.mapNotNull { it as? com.streamflixreborn.streamflix.models.Show }
        }
        Genre(id = id, name = when (id) {
            "movies" -> "Films"
            "tv" -> "Séries"
            else -> "Tout"
        }, shows = filtered)
    }

    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = id)

    // 2026-05-20 (user "rattacher un provider qui peut contenir plein de dessins
    //   animés au cas où") : DessinAnime = serveurs natifs PUIS deux backups
    //   lancés en parallèle, plafonnés en temps, ajoutés en fin de liste :
    //     • Cloudstream  → couvre les films/séries occidentaux (Super Mario,
    //       Zootopie…) via recherche titre→TMDB ; embarque déjà MovieBox+ + Movix
    //       + Nakios. Préfixe d'id "csbackup__" → délégué à CloudstreamProvider.
    //     • AnimeSama    → couvre l'anime japonais (séries + films). Helper natif
    //       sans ses propres backups. Préfixe "asbackup__" → délégué à AnimeSama.
    //   Garde anti-récursion : Cloudstream/AnimeSama appelés ici ne rappellent
    //   jamais DessinAnime, et AnimeSama tourne avec skipBackupsForBackupCall=true.
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = withContext(Dispatchers.IO) {
        val native = try {
            fetchNativeServers(id, videoType)
        } catch (e: Exception) {
            Log.w(TAG, "native servers KO: ${e.message}"); emptyList()
        }

        // 2026-05-21 (user "ça charge plus les serveurs, remets les appels comme avant ;
        //   ça a toujours bien chargé") : si on a déjà des serveurs natifs, on les renvoie
        //   IMMÉDIATEMENT. Les secours croisés (Cloudstream → Movix avec id "0") restaient
        //   bloqués sans réponse (fstream/0, wiflix/0) → l'écran de chargement ne finissait
        //   jamais. Les secours ne servent QUE quand le natif est vide.
        if (native.isNotEmpty()) {
            Log.d(TAG, "getServers: ${native.size} natifs (secours ignorés, natif présent)")
            return@withContext native
        }

        val title = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }.trim()
        if (title.isBlank()) return@withContext native

        val backups = try {
            coroutineScope {
                // Backup #1 : Cloudstream (films/séries FR via TMDB, inclut Movix)
                val csD = async {
                    try {
                        val csType: Video.Type = when (videoType) {
                            is Video.Type.Movie -> Video.Type.Movie(
                                id = "0", title = title, releaseDate = videoType.releaseDate,
                                poster = videoType.poster, imdbId = videoType.imdbId,
                            )
                            is Video.Type.Episode -> Video.Type.Episode(
                                id = "0", number = videoType.number, title = videoType.title,
                                poster = videoType.poster, overview = videoType.overview,
                                tvShow = videoType.tvShow.copy(id = "0", title = title),
                                season = videoType.season,
                            )
                        }
                        (kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                            CloudstreamProvider.getServers("0", csType)
                        } ?: emptyList()).map {
                            it.copy(id = "csbackup__${it.id}", name = "CS · ${it.name}")
                        }
                    } catch (e: Exception) { Log.w(TAG, "CS backup KO: ${e.message}"); emptyList() }
                }
                // Backup #2 : AnimeSama (anime natif uniquement)
                val asD = async {
                    try {
                        (kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                            AnimeSamaProvider.getAnimeSamaSourcesByTitle(title, videoType)
                        } ?: emptyList()).map {
                            it.copy(id = "asbackup__${it.id}", name = "AS · ${it.name}")
                        }
                    } catch (e: Exception) { Log.w(TAG, "AS backup KO: ${e.message}"); emptyList() }
                }
                csD.await() + asD.await()
            }
        } catch (e: Exception) { Log.w(TAG, "backups KO: ${e.message}"); emptyList() }

        Log.d(TAG, "getServers: ${native.size} natifs + ${backups.size} backups (CS+AS)")
        native + backups
    }

    // ── Chargement PROGRESSIF (2026-05-21) ─────────────────────────────
    //   Natifs émis tout de suite, puis Cloudstream et AnimeSama émis
    //   dès qu'ils répondent, en parallèle — pas d'attente bloquante.
    override fun getServersProgressive(id: String, videoType: Video.Type): Flow<List<Video.Server>> = channelFlow {
        // 1) Natifs — émis immédiatement
        val native = try { fetchNativeServers(id, videoType) }
        catch (e: Exception) { Log.w(TAG, "progressive native KO: ${e.message}"); emptyList() }
        if (native.isNotEmpty()) {
            // 2026-05-21 (user "remets les appels comme avant, ça a toujours bien chargé") :
            //   on a du natif → on l'émet et on s'ARRÊTE. Les secours croisés
            //   (Cloudstream → Movix id "0") restaient bloqués (fstream/0, wiflix/0) et
            //   maintenaient le flux ouvert → chargement infini. Secours seulement si vide.
            send(native)
            return@channelFlow
        }

        val title = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }.trim()
        if (title.isBlank()) return@channelFlow

        // VideoType pour Cloudstream (id="0" car recherche par titre)
        val csType: Video.Type = when (videoType) {
            is Video.Type.Movie -> Video.Type.Movie(
                id = "0", title = title, releaseDate = videoType.releaseDate,
                poster = videoType.poster, imdbId = videoType.imdbId,
            )
            is Video.Type.Episode -> Video.Type.Episode(
                id = "0", number = videoType.number, title = videoType.title,
                poster = videoType.poster, overview = videoType.overview,
                tvShow = videoType.tvShow.copy(id = "0", title = title),
                season = videoType.season,
            )
        }

        // 2) Backups en parallèle — chacun émet dès qu'il finit
        val csJob = launch(Dispatchers.IO) {
            try {
                val servers = (kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                    CloudstreamProvider.getServers("0", csType)
                } ?: emptyList()).map { it.copy(id = "csbackup__${it.id}", name = "CS · ${it.name}") }
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive CS backup KO: ${e.message}") }
        }
        val asJob = launch(Dispatchers.IO) {
            try {
                val servers = (kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    AnimeSamaProvider.getAnimeSamaSourcesByTitle(title, videoType)
                } ?: emptyList()).map { it.copy(id = "asbackup__${it.id}", name = "AS · ${it.name}") }
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive AS backup KO: ${e.message}") }
        }
        csJob.join()
        asJob.join()
    }

    /**
     * Serveurs natifs DessinAnime (RSC + miroirs empilés). Renvoie emptyList
     *   en cas d'échec (pas d'exception) pour que les backups prennent le relais.
     */
    private suspend fun fetchNativeServers(id: String, videoType: Video.Type): List<Video.Server> = withContext(Dispatchers.IO) {
        // Pour Movie : id = "movie::<slug>" → fetch /movie/<slug>
        // Pour Episode : id reçu = celui de l'épisode "tv::<slug>::sN::eM"
        val pageUrl: String = when (videoType) {
            is Video.Type.Movie -> {
                val (type, slug) = parseInternalId(id) ?: return@withContext emptyList()
                if (type != "movie") return@withContext emptyList()
                "$baseUrl/movie/$slug"
            }
            is Video.Type.Episode -> {
                // L'id peut être de l'épisode ou du show ; on cherche le slug
                // depuis le videoType qui contient la séquence saison/épisode.
                val slug = parseInternalId(id)?.second
                    ?: id.split("::").getOrNull(1)
                    ?: return@withContext emptyList()
                val s = videoType.season.number
                val e = videoType.number
                "$baseUrl/tv/$slug/$s/$e"
            }
        }
        Log.d(TAG, "getServers fetch $pageUrl")
        val html = httpGet(pageUrl) ?: return@withContext emptyList()

        // v85d 2026-05-19 (user "il trouve qu'un serveur par source alors qu'il
        //   devrait y en avoir plusieurs") : la page est Next.js + RSC. Le HTML
        //   rendu a un seul <iframe> par défaut, mais le payload RSC sérialisé
        //   plus bas contient TOUS les players (uqload, mixdrop, vidhide,
        //   player4me, hydrax, etc.) avec embedId + iframeTemplate. Parse ça
        //   → 6 serveurs au lieu d'1. Fallback iframe<src> si parse échoue.
        val unescaped = html.replace("\\\"", "\"")
        // 2026-05-19 v85d2 (user "il manque bien un serveur") : language est
        //   parfois null (pas un objet) -> on ne l'exige plus dans le regex
        //   principal. On la cherche ensuite dans la fenetre du match.
        val playerRegex = Regex(
            "\"embedId\":\"([^\"]+)\".{0,1500}?" +
            "\"host\":\\{[^}]*?\"name\":\"([^\"]+)\"[^}]*?\"iframeTemplate\":\"([^\"]+)\"[^}]*\\}"
        )
        val langInWindowRegex = Regex(
            "\"effectiveLanguage\":\"([^\"]+)\"|\"sourceLanguage\":\"([^\"]+)\"|\"language\":\\{[^}]*?\"name\":\"([^\"]+)\""
        )
        val seenUrls = mutableSetOf<String>()
        val nameToUrls = LinkedHashMap<String, MutableList<String>>()
        playerRegex.findAll(unescaped).forEach { m ->
            val embedId = m.groupValues[1]
            val hostName = m.groupValues[2]
            val iframeTemplate = m.groupValues[3]
            val url = iframeTemplate.replace("{{slug}}", embedId)
            if (!url.startsWith("http")) return@forEach
            if (!seenUrls.add(url)) return@forEach
            val ws = maxOf(0, m.range.first - 250)
            val we = minOf(unescaped.length, m.range.last + 50)
            val window = unescaped.substring(ws, we)
            val langMatch = langInWindowRegex.find(window)
            val rawLang = listOfNotNull(
                langMatch?.groupValues?.getOrNull(1),
                langMatch?.groupValues?.getOrNull(2),
                langMatch?.groupValues?.getOrNull(3),
            ).firstOrNull { it.isNotBlank() && it != "null" } ?: ""
            val langUp = rawLang.uppercase()
            if (!(langUp.isBlank() || langUp == "MULTI" || langUp.contains("FR") || langUp.contains("VF"))) return@forEach  // user: FR uniquement
            val pretty = hostName.replaceFirstChar { it.uppercase() }
            val name = if (rawLang.isNotBlank() && rawLang != "MULTI") "$pretty ($rawLang)" else pretty
            // On EMPILE tous les miroirs du meme nom (pas de dedup par nom) :
            //   1 entree visible, mais src contient tous les miroirs -> fallback.
            nameToUrls.getOrPut(name) { mutableListOf() }.add(url)
        }
        if (nameToUrls.isNotEmpty()) {
            val rscServers = nameToUrls.map { (name, urls) ->
                Video.Server(id = urls.first(), name = name, src = urls.first(), mirrors = urls)
            }
            Log.d(TAG, "getServers RSC: ${rscServers.size} players (${rscServers.joinToString { "${it.name} x${it.mirrors.size}" }})")
            return@withContext rscServers
        }

        // Fallback iframe<src> — pages anciennes sans payload RSC.
        val iframes = Regex("""<iframe[^>]+src="([^"]+)"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()
        if (iframes.isEmpty()) {
            Log.w(TAG, "No iframe found on $pageUrl (RSC parse aussi vide)")
            return@withContext emptyList()
        }
        // Empile les miroirs du meme host sous 1 seule entree (idem RSC).
        val ifNameToUrls = LinkedHashMap<String, MutableList<String>>()
        iframes.forEach { iframeUrl ->
            val host = try { java.net.URI(iframeUrl).host ?: "Lecteur" } catch (_: Exception) { "Lecteur" }
            val cleanHost = host.removePrefix("www.")
                .substringBefore('.')
                .replaceFirstChar { it.uppercase() }
            ifNameToUrls.getOrPut(cleanHost) { mutableListOf() }.add(iframeUrl)
        }
        Log.d(TAG, "getServers fallback iframe: ${ifNameToUrls.size} server(s) (${ifNameToUrls.entries.joinToString { "${it.key} x${it.value.size}" }})")
        ifNameToUrls.map { (name, urls) ->
            Video.Server(
                id = urls.first(),
                name = name,
                src = urls.first(),
                mirrors = urls,
            )
        }
    }
    override suspend fun getVideo(server: Video.Server): Video {
        // Backups : on délègue au provider d'origine (qui sait extraire/headers).
        if (server.id.startsWith("csbackup__")) {
            return CloudstreamProvider.getVideo(server.copy(id = server.id.removePrefix("csbackup__")))
        }
        if (server.id.startsWith("asbackup__")) {
            return AnimeSamaProvider.getVideo(server.copy(id = server.id.removePrefix("asbackup__")))
        }
        // Un host peut avoir PLUSIEURS miroirs (ex 3 liens Uqload pour 1 film).
        //   On essaie chacun jusqu'au premier qui sort une vraie source -> un
        //   miroir mort (page 931 octets / timeout) est saute automatiquement.
        val mirrors = (if (server.mirrors.isNotEmpty()) server.mirrors else listOf(server.src))
            .map { it.trim() }.filter { it.isNotEmpty() }
        Log.d(TAG, "getVideo(${server.name}) mirrors=${mirrors.size}")
        if (mirrors.size <= 1) {
            return Extractor.extract(server.src, server)
        }
        var lastError: Throwable? = null
        for ((i, url) in mirrors.withIndex()) {
            try {
                Log.d(TAG, "getVideo(${server.name}) miroir ${i + 1}/${mirrors.size}: $url")
                return Extractor.extract(url, server)
            } catch (e: Throwable) {
                Log.w(TAG, "getVideo(${server.name}) miroir ${i + 1} KO: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: Exception("${server.name}: tous les miroirs ont echoue")
    }
}
