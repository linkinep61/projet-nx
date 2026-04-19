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
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
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

object AnimeSamaProvider : Provider, ProviderConfigUrl, ProviderPortalUrl {

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
        get() = "${baseUrl}img/icon.png"
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private const val TAG = "AnimeSama"
    private const val IMG_BASE = "https://raw.githubusercontent.com/Anime-Sama/IMG/img/contenu/"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
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

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .build()
        val response = client.newCall(request).execute()
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
            // Skip scans section
            if (sectionTitle.contains("Scans", ignoreCase = true)) continue
            // Skip "Reprenez votre visionnage" — requires user cookies (not available server-side)
            if (sectionTitle.contains("Reprenez", ignoreCase = true)) continue

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

                // Type badge: "Film" → Movie, else TvShow
                val badgeText = card.selectFirst(".badge-text")?.text()?.trim() ?: ""
                if (badgeText.equals("Film", ignoreCase = true)) {
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

        // FEATURED: Build hero slider from "Derniers contenus sortis"
        val derniersContenus = categories.firstOrNull {
            it.name.lowercase().contains("derniers contenus")
        }
        if (derniersContenus != null) {
            val featured = derniersContenus.list.take(15).map { item ->
                when (item) {
                    is Movie -> Movie(
                        id = item.id,
                        title = item.title,
                        banner = item.poster, // Use poster as banner
                        poster = item.poster
                    )
                    is TvShow -> TvShow(
                        id = item.id,
                        title = item.title,
                        banner = item.poster, // Use poster as banner
                        poster = item.poster
                    )
                    else -> item
                }
            }
            if (featured.isNotEmpty()) {
                categories.add(0, Category(name = Category.FEATURED, list = featured))
            }
        }

        // Reorder: put FEATURED first, then "Derniers épisodes ajoutés" and "Derniers contenus sortis"
        val priority = listOf("derniers épisodes", "derniers contenus")
        val sorted = categories.sortedWith(compareByDescending { cat ->
            val lower = cat.name.lowercase()
            when {
                cat.name == Category.FEATURED -> 100
                priority.any { lower.contains(it) } -> priority.indexOfFirst { lower.contains(it) }.let { 10 - it }
                else -> 0
            }
        })

        return sorted
    }

    // ========== SEARCH ==========

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            // Return genres so they appear in the search screen (like other providers)
            return listOf(
                "Action", "Aventure", "Comédie", "Drame", "Fantastique",
                "Horreur", "Mystère", "Romance", "Science-fiction", "Thriller",
                "Isekai", "Shônen", "Seinen", "Shôjo", "Ecole",
                "Magie", "Crime", "Psychologique", "Sport", "Musique"
            ).map { Genre(id = it, name = it) }
        }

        val html = searchPost(query)
        val doc = Jsoup.parse(html)

        return doc.select("a.asn-search-result").mapNotNull { result ->
            val href = result.attr("href")
            val slug = href.substringAfter("/catalogue/")
                .removeSuffix("/")
                .split("/").firstOrNull() ?: return@mapNotNull null
            val title = result.selectFirst("h3.asn-search-result-title")?.text()?.trim() ?: return@mapNotNull null
            val img = result.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${slug}.jpg"

            TvShow(
                id = slug,
                title = title,
                poster = img,
            )
        }
    }

    // ========== MOVIES ==========

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = "${baseUrl}catalogue/?type[]=Film&page=$page"
        val document = fetchDocument(url)
        return document.select(".catalog-card").mapNotNull { card ->
            val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
            val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                .split("/").firstOrNull() ?: return@mapNotNull null
            val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val img = card.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${slug}.jpg"
            Movie(id = slug, title = title, poster = img)
        }
    }

    // ========== TV SHOWS ==========

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = "${baseUrl}catalogue/?type[]=Anime&page=$page"
        val document = fetchDocument(url)
        return document.select(".catalog-card").mapNotNull { card ->
            val link = card.selectFirst("a[href*=/catalogue/]") ?: return@mapNotNull null
            val slug = link.attr("href").substringAfter("/catalogue/").removeSuffix("/")
                .split("/").firstOrNull() ?: return@mapNotNull null
            val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val img = card.selectFirst("img")?.attr("src") ?: "${IMG_BASE}${slug}.jpg"
            TvShow(id = slug, title = title, poster = img)
        }
    }

    // ========== MOVIE DETAIL ==========

    override suspend fun getMovie(id: String): Movie {
        val document = fetchDocument("${baseUrl}catalogue/$id/")
        val html = document.html()

        val title = document.title().substringBefore(" |").trim()
        val synopsisMatch = Regex("SYNOPSIS[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(html)
        val synopsis = synopsisMatch?.groupValues?.get(1)?.let { Jsoup.parse(it).text() } ?: ""
        val genresMatch = Regex("GENRES[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(html)
        val genresText = genresMatch?.groupValues?.get(1)?.let { Jsoup.parse(it).text() } ?: ""

        return Movie(
            id = id,
            title = title,
            overview = synopsis,
            poster = "${IMG_BASE}${id}.jpg",
            genres = genresText.split(",").map { Genre(id = it.trim().lowercase(), name = it.trim()) }
        )
    }

    // ========== TV SHOW DETAIL ==========

    override suspend fun getTvShow(id: String): TvShow {
        val document = fetchDocument("${baseUrl}catalogue/$id/")
        val html = document.html()

        val title = document.title().substringBefore(" |").trim()
        val synopsisMatch = Regex("SYNOPSIS[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(html)
        val synopsis = synopsisMatch?.groupValues?.get(1)?.let { Jsoup.parse(it).text() } ?: ""
        val genresMatch = Regex("GENRES[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(html)
        val genresText = genresMatch?.groupValues?.get(1)?.let { Jsoup.parse(it).text() } ?: ""

        // Season links are generated by JavaScript (document.write) so they
        // don't appear in the server-side HTML.  Instead, we probe for seasons
        // by trying to fetch episodes.js for each potential season number.
        // If both VF and VOSTFR exist, show language folders first.
        // If only one language, show seasons directly.
        val seasons = mutableListOf<Season>()
        val languages = listOf("vostfr", "vf")
        val langLabels = mapOf("vostfr" to "VOSTFR", "vf" to "VF")

        // Discover which (season_num, lang) combos exist
        val langSeasons = mutableMapOf<String, MutableList<Int>>() // lang -> season numbers

        for (num in 1..20) {
            var anyFound = false
            for (lang in languages) {
                try {
                    val probeUrl = "${baseUrl}catalogue/$id/saison$num/$lang/episodes.js"
                    val text = fetchText(probeUrl)
                    if (text.contains("var eps1") && text.contains("http")) {
                        // Count actual episode URLs to filter out generic fallback templates
                        // (the site returns HTTP 200 with a 1-URL template for non-existent seasons)
                        val eps1Content = Regex("""var\s+eps1\s*=\s*\[([^\]]*)\]""").find(text)?.groupValues?.get(1) ?: ""
                        val urlCount = Regex("""['"]https?://[^'"]+['"]""").findAll(eps1Content).count()
                        if (urlCount >= 2) {
                            langSeasons.getOrPut(lang) { mutableListOf() }.add(num)
                            anyFound = true
                        } else {
                            Log.d(TAG, "[Seasons] Skipping $id/saison$num/$lang: only $urlCount URL(s) — likely fallback template")
                        }
                    }
                } catch (_: Exception) {}
            }
            if (!anyFound && langSeasons.isNotEmpty()) break
        }

        // Fallback: try without "saison" prefix (single-season anime)
        if (langSeasons.isEmpty()) {
            for (lang in languages) {
                try {
                    val probeUrl = "${baseUrl}catalogue/$id/$lang/episodes.js"
                    val text = fetchText(probeUrl)
                    if (text.contains("var eps1") && text.contains("http")) {
                        langSeasons.getOrPut(lang) { mutableListOf() }.add(0) // 0 = no season prefix
                    }
                } catch (_: Exception) {}
            }
        }

        val animePoster = "${IMG_BASE}${id}.jpg"

        if (langSeasons.size > 1) {
            // Multiple languages: show language folders (VOSTFR / VF)
            var idx = 0
            for (lang in languages) {
                val nums = langSeasons[lang] ?: continue
                val label = langLabels[lang] ?: lang.uppercase()
                idx++
                val folderId = "$id/@$lang/${nums.joinToString(",")}"
                seasons.add(Season(
                    id = folderId,
                    number = idx,
                    title = label,
                    poster = animePoster,
                ))
            }
        } else if (langSeasons.size == 1) {
            // Single language: show seasons directly
            val (lang, nums) = langSeasons.entries.first()
            for ((idx, num) in nums.withIndex()) {
                val seasonId = if (num == 0) "$id/$lang" else "$id/saison$num/$lang"
                seasons.add(Season(
                    id = seasonId,
                    number = idx + 1,
                    title = if (num == 0) "Épisodes" else "Saison $num",
                    poster = animePoster,
                ))
            }
        }

        // Ultimate fallback
        if (seasons.isEmpty()) {
            seasons.add(Season(id = "$id/saison1/vostfr", number = 1, title = "Saison 1", poster = animePoster))
        }

        return TvShow(
            id = id,
            title = title,
            overview = synopsis,
            poster = "${IMG_BASE}${id}.jpg",
            genres = genresText.split(",").filter { it.isNotBlank() }.map { Genre(id = it.trim().lowercase(), name = it.trim()) },
            seasons = seasons,
        )
    }

    // ========== EPISODES ==========

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // Two formats:
        // 1. Language folder: "slug/@vostfr/1,2,3" → fetch episodes from multiple real seasons
        // 2. Direct season:   "slug/saison1/vostfr" or "slug/vostfr" → single season
        val langFolderRegex = Regex("""(.+)/@(\w+)/(.+)""")
        val langMatch = langFolderRegex.find(seasonId)

        // Extract anime slug for episode poster (use the anime's cover image)
        val animeSlug = langMatch?.groupValues?.get(1)
            ?: seasonId.substringBefore("/saison").substringBefore("/vostfr").substringBefore("/vf")
        val episodePoster = "${IMG_BASE}${animeSlug}.jpg"

        if (langMatch != null) {
            // Language folder mode: return sub-seasons as fake episodes
            val slug = langMatch.groupValues[1]
            val lang = langMatch.groupValues[2]
            val seasonNums = langMatch.groupValues[3].split(",").mapNotNull { it.toIntOrNull() }
            Log.d(TAG, "[Episodes] Language folder: lang=$lang, seasons=$seasonNums")

            val episodes = mutableListOf<Episode>()
            for ((idx, num) in seasonNums.withIndex()) {
                val seasonId = if (num == 0) "$slug/$lang" else "$slug/saison$num/$lang"
                val title = if (seasonNums.size == 1 && num == 0) "Épisodes"
                    else "Saison $num"
                episodes.add(Episode(
                    id = "@subfolder:$seasonId",
                    number = idx + 1,
                    title = title,
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

        val eps1Regex = Regex("""var\s+eps1\s*=\s*\[([^\]]*)\]""")
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
        val epsRegex = Regex("""var\s+(eps\w+)\s*=\s*\[([^\]]*)\]""")

        if (videoType is Video.Type.Movie) {
            // Movies: probe film/vostfr and film/vf, episode index is always 0
            Log.d(TAG, "[Movie Servers] Starting for id=$id videoType=$videoType")
            val languages = listOf("vf", "vostfr")
            val langLabels = mapOf("vostfr" to "VOSTFR", "vf" to "VF")

            for (lang in languages) {
                val fetchUrl = "${baseUrl}catalogue/$id/film/$lang/episodes.js"
                Log.d(TAG, "[Movie Servers] Fetching: $fetchUrl")
                val episodesJs = try {
                    fetchText(fetchUrl)
                } catch (e: Exception) {
                    Log.e(TAG, "[Movie Servers] Fetch failed for $lang: ${e.message}")
                    continue
                }
                Log.d(TAG, "[Movie Servers] $lang response: length=${episodesJs.length}, hasEps1=${episodesJs.contains("var eps1")}")
                if (episodesJs.isBlank() || !episodesJs.contains("var eps1")) continue

                val matchCount = epsRegex.findAll(episodesJs).count()
                Log.d(TAG, "[Movie Servers] $lang epsRegex matches: $matchCount")

                for (match in epsRegex.findAll(episodesJs)) {
                    val varName = match.groupValues[1]
                    val urls = urlRegex.findAll(match.groupValues[2])
                        .map { it.groupValues[1] }.toList()
                    Log.d(TAG, "[Movie Servers] $lang $varName: ${urls.size} urls")
                    if (urls.isNotEmpty()) {
                        val url = urls[0]
                        val serverName = getServerName(varName, url)
                        val langSuffix = if (languages.size > 1) " (${langLabels[lang]})" else ""
                        servers.add(Video.Server(
                            id = "${varName}_$lang",
                            name = "$serverName$langSuffix",
                            src = url,
                        ))
                    }
                }
            }
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

        // Sort: Sibnet first (most reliable), then others
        servers.sortWith(compareByDescending<Video.Server> {
            when {
                it.name.contains("Sibnet", ignoreCase = true) -> 3
                it.name.contains("SendVid", ignoreCase = true) -> 2
                it.name.contains("VidMoLy", ignoreCase = true) -> 1
                // Lpayer en dernier: WebView + décryptage = lent (~5-10s)
                it.name.contains("Lpayer", ignoreCase = true) -> -1
                else -> 0
            }
        })

        return servers
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

        // Direct MP4 links (anime-sama.fr hosting)
        if (url.contains("anime-sama.fr") || url.endsWith(".mp4")) {
            return Video(
                source = url,
                headers = mapOf(
                    "Referer" to baseUrl,
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
