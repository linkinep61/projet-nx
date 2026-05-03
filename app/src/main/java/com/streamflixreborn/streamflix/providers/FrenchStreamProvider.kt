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
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
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
import kotlin.math.round
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

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Extract newsid from href like /index.php?newsid=15126799.
     *  Falls back to slug (last path segment) for legacy URLs. */
    private fun extractNewsId(href: String): String? {
        if (href.contains("newsid=")) {
            return href.substringAfter("newsid=").substringBefore("&").takeIf { it.isNotBlank() }
        }
        return href.substringAfterLast("/").takeIf { it.isNotBlank() }
    }

    /** Fetch a detail page, routing numeric IDs through ?newsid= and slugs
     *  through the legacy /{slug} path. */
    private suspend fun fetchDetailPage(id: String): Document {
        return if (id.all { it.isDigit() }) {
            service.getItemByNewsId(id)
        } else {
            // Legacy slug — try /film/ then /serie/ then bare /{id}
            try { service.getMovie(id) }
            catch (_: Exception) {
                try { service.getTvShow(id) }
                catch (_: Exception) { service.getItem(id) }
            }
        }
    }

    fun ignoreSource(source: String, href: String): Boolean {
        if (source.trim().equals("Dood.Stream", ignoreCase = true) && href.contains("/bigwar5/")) return true
        // kakaflix.lol is a phantom domain that FrenchStream lists for the
        // VFF/VFQ/VOSTFR variants of Dood/Voe/Filmoon but every endpoint
        // returns a clean 404 from the LiteSpeed server (verified across
        // multiple URLs, methods, headers, networks). Filter them out so
        // the user only sees working sources (typically the Default entry
        // which routes through kokoflix.lol → playmogo.com).
        if (href.contains("kakaflix.lol", ignoreCase = true)) return true
        return false
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

    // ── Home ─────────────────────────────────────────────────────────────

    override suspend fun getHome(): List<Category> {
        initializeService()
        val document = service.getHome()
        val categories = mutableListOf<Category>()
        val allItems = mutableListOf<AppAdapter.Item>()

        // 2026-05: site redesign — sections are now <div class="sect">
        // wrapping a header <div class="sect-t">. The clean title lives in
        // <a class="st-capt"> (e.g. "Nouveautés Films", "Nouveautés Séries");
        // the rest of .sect-t is filled with tab spans ("Par Défault",
        // "Animation", "Comédie"…) which we MUST NOT include in the title.
        // Each card is a div.short with a poster link, no .film class —
        // movie/series detection has to come from URL/title heuristics and
        // the parent section's "kind".
        document.select("div.sect").forEach { section ->
            val capt = section.selectFirst(".sect-t a.st-capt")
            val sectionTitle = capt?.ownText()?.trim()
                ?: section.selectFirst(".sect-t .st-capt")?.ownText()?.trim()
                ?: return@forEach
            val sectionHref = capt?.attr("href") ?: ""
            // A section is "series-only" when its capt link points to /s-tv/
            // or /serie/. Sections like /films/ or /film-commu/ are movies.
            val sectionIsSeries = sectionHref.contains("/s-tv/")
                || sectionHref.contains("/serie/")
                || sectionTitle.contains("Série", ignoreCase = true)

            val items = section.select("div.short").mapNotNull { item ->
                val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                val href = linkEl.attr("href")
                val id = extractNewsId(href) ?: return@mapNotNull null
                val title = item.selectFirst("div.short-title")?.text() ?: ""
                val poster = item.selectFirst("img")?.attr("src") ?: ""
                // Series detection: parent section first (most reliable),
                // then per-card URL/title fallbacks (in case the section
                // mixes types — e.g. "Ajouts de la Commu").
                val isSeries = sectionIsSeries
                    || title.contains("Saison", ignoreCase = true)
                    || href.contains("/s-tv/") || href.contains("/serie/")
                    || id.contains("-saison-")
                if (isSeries)
                    TvShow(id = id, title = title, poster = poster)
                else
                    Movie(id = id, title = title, poster = poster)
            }
            if (items.isNotEmpty()) {
                categories.add(Category(name = sectionTitle, list = items))
                allItems.addAll(items)
            }
        }

        // Featured carousel from the first items across all sections.
        // The site only has the small portrait poster (no wide backdrop),
        // so when the carousel renders fullscreen the upscaled portrait
        // looks ugly and eats RAM. Pull a proper landscape backdrop from
        // TMDb when enabled — same pattern as WiflixProvider does.
        if (allItems.isNotEmpty()) {
            val featured = allItems.take(15).map { item ->
                when (item) {
                    is Movie -> Movie(id = item.id, title = item.title,
                        poster = item.poster, banner = item.poster)
                    is TvShow -> TvShow(id = item.id, title = item.title,
                        poster = item.poster, banner = item.poster)
                    else -> item
                }
            }
            // Parallel TMDb lookup for backdrops (capped to 15 items so we
            // never block home loading on slow TMDb responses; failures are
            // silent — falls back to portrait poster).
            if (UserPreferences.enableTmdb) {
                runCatching {
                    coroutineScope {
                        featured.map { item ->
                            async {
                                runCatching {
                                    val title = when (item) {
                                        is Movie -> item.title
                                        is TvShow -> item.title.substringBefore(" - ")
                                        else -> return@async
                                    }
                                    val results = TMDb3.Search.multi(title, language = "fr-FR")
                                    val tmdbBanner = results.results
                                        .firstNotNullOfOrNull { r ->
                                            when (r) {
                                                is TMDb3.Movie -> r.backdropPath?.original
                                                is TMDb3.Tv -> r.backdropPath?.original
                                                else -> null
                                            }
                                        }
                                    if (tmdbBanner != null) {
                                        when (item) {
                                            is Movie -> item.banner = tmdbBanner
                                            is TvShow -> item.banner = tmdbBanner
                                        }
                                    }
                                }
                            }
                        }.forEach { it.await() }
                    }
                }
            }
            categories.add(0, Category(name = Category.FEATURED, list = featured))
        }

        return categories
    }

    // ── Search ───────────────────────────────────────────────────────────

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
        return document.select("div.search-item, div.short").mapNotNull { item ->
            val linkEl = item.selectFirst("a.short-poster, a") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            val id = extractNewsId(href)
            if (id.isNullOrBlank()) return@mapNotNull null
            val title = (item.selectFirst("div.short-title")?.text()
                ?: item.selectFirst("div.search-title")?.text())
                ?.replace("\\'", "'") ?: ""
            val poster = item.selectFirst("img")?.attr("src") ?: ""
            val isSeries = title.contains("Saison", ignoreCase = true)
                || href.contains("/s-tv/") || href.contains("/serie/")
                || id.contains("-saison-")
            if (isSeries)
                TvShow(id = id, title = title, poster = poster)
            else
                Movie(id = id, title = title, poster = poster)
        }
    }

    // ── Catalog (Movies / TV Shows) ──────────────────────────────────────

    override suspend fun getMovies(page: Int): List<Movie> {
        if (page > 1) return emptyList()
        initializeService()
        fun looksLikeSeries(href: String, title: String, id: String): Boolean {
            return href.contains("/serie/")
                || href.contains("/s-tv/")
                || id.contains("-saison-")
                || title.contains("Saison", ignoreCase = true)
                || title.matches(Regex(".*\\bS\\d{1,2}\\b.*", RegexOption.IGNORE_CASE))
        }
        // /films endpoint may return MySQL errors on the redesigned site.
        // Try it first, fall back to home page movies.
        return try {
            val document = service.getMovies(page)
            val items = document.select("div.short").mapNotNull { item ->
                val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                val href = linkEl.attr("href")
                val id = extractNewsId(href) ?: return@mapNotNull null
                val title = item.selectFirst("div.short-title")?.text() ?: ""
                // Defensive: drop series that occasionally leak into /films/ (e.g.
                // a series titled "X - Saison 1" can be mis-tagged on the site).
                if (looksLikeSeries(href, title, id)) return@mapNotNull null
                Movie(id = id, title = title, poster = item.selectFirst("img")?.attr("src"))
            }
            items.ifEmpty { throw Exception("empty") }
        } catch (_: Exception) {
            // Fallback: films from home page (div.short.film, then heuristic for redesigned site)
            try {
                val doc = service.getHome()
                val list = doc.select("div.short.film").mapNotNull { item ->
                    val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                    val id = extractNewsId(linkEl.attr("href")) ?: return@mapNotNull null
                    Movie(
                        id = id,
                        title = item.selectFirst("div.short-title")?.text() ?: "",
                        poster = item.selectFirst("img")?.attr("src"),
                    )
                }
                if (list.isNotEmpty()) list else doc.select("div.short").mapNotNull { item ->
                    val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                    val href = linkEl.attr("href")
                    val id = extractNewsId(href) ?: return@mapNotNull null
                    val title = item.selectFirst("div.short-title")?.text() ?: ""
                    if (looksLikeSeries(href, title, id)) return@mapNotNull null
                    Movie(id = id, title = title,
                        poster = item.selectFirst("img")?.attr("src"))
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page > 1) return emptyList()
        initializeService()
        // Helper: looks like an actual TV series (not a film mis-classified).
        // The redesigned site doesn't use .film class anymore, so we have to
        // detect series by URL/title heuristics — otherwise films leak into
        // the "Séries Récentes" carousel and the type badge ends up flipping.
        fun looksLikeSeries(href: String, title: String, id: String): Boolean {
            return href.contains("/serie/")
                || href.contains("/s-tv/")
                || id.contains("-saison-")
                || title.contains("Saison", ignoreCase = true)
                || title.matches(Regex(".*\\bS\\d{1,2}\\b.*", RegexOption.IGNORE_CASE))
        }
        return try {
            val document = service.getTvShows(page)
            // /series/ endpoint returns ONLY series — trust it (filtering would
            // drop legitimate series whose title doesn't have "Saison" / "S1").
            val items = document.select("div.short").mapNotNull { item ->
                val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                val href = linkEl.attr("href")
                val id = extractNewsId(href) ?: return@mapNotNull null
                val title = item.selectFirst("div.short-title")?.text() ?: ""
                TvShow(id = id, title = title,
                    poster = item.selectFirst("img")?.attr("src"))
            }
            items.ifEmpty { throw Exception("empty") }
        } catch (_: Exception) {
            // Fallback: home page mixes types — must filter to only keep series.
            try {
                val doc = service.getHome()
                doc.select("div.short").mapNotNull { item ->
                    val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
                    val href = linkEl.attr("href")
                    val id = extractNewsId(href) ?: return@mapNotNull null
                    val title = item.selectFirst("div.short-title")?.text() ?: ""
                    if (!looksLikeSeries(href, title, id)) return@mapNotNull null
                    TvShow(id = id, title = title,
                        poster = item.selectFirst("img")?.attr("src"))
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    // ── Movie detail ─────────────────────────────────────────────────────

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        initializeService()
        val document = fetchDetailPage(id)
        val title = document.selectFirst("h1#s-title")?.ownText()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val overview = document.selectFirst("div#s-desc")?.also {
            it.selectFirst("p.desc-text")?.remove()
        }?.text()?.trim()
            ?: document.selectFirst("div.fdesc p, p.fdesc-text")?.text()?.trim().orEmpty()
        val poster = document.selectFirst(".fposter img, img.dvd-thumbnail")?.attr("src")
        val releaseYear = document.selectFirst("span.release_date")?.text()
            ?.substringAfter("-")?.trim()
        val runtime = document.selectFirst("span.runtime")?.text()?.let { rt ->
            val hours = rt.substringBefore("h").trim().toIntOrNull() ?: 0
            val minutes = rt.substringAfter("h").trim().toIntOrNull() ?: 0
            (hours * 60 + minutes).takeIf { it > 0 }
        }
        val genres = document.select("span.genres a").map {
            Genre(
                id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                name = it.text().trim(),
            )
        }
        val directors = document.select("ul#s-list li")
            .find { it.selectFirst("span")?.text()?.contains("alisateur") == true }
            ?.select("a")?.mapIndexedNotNull { index, el ->
                People(id = "director$index", name = el.text())
            } ?: emptyList()
        val votes = document.selectFirst("div.fr-votes")
        val rating = if (votes != null) getRating(votes) else null

        Movie(
            id = id,
            title = title,
            overview = overview,
            released = releaseYear,
            runtime = runtime,
            poster = poster,
            banner = poster,
            genres = genres.ifEmpty { listOf(Genre(id = "unknown", name = "")) },
            directors = directors,
            rating = rating,
        )
    }

    // ── TV Show detail ───────────────────────────────────────────────────

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        initializeService()
        val document = fetchDetailPage(id)
        val title = document.selectFirst("h1#s-title")?.ownText()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val cleanTitle = title.substringBeforeLast("- Saison").trim().ifBlank { title }
        val overview = document.selectFirst("div#s-desc")?.also {
            it.selectFirst("p.desc-text")?.remove()
        }?.text()?.trim()
            ?: document.selectFirst("div.fdesc p, p.fdesc-text")?.text()?.trim().orEmpty()
        val poster = document.selectFirst(".fposter img, img.dvd-thumbnail")?.attr("src")
        val releaseYear = document.selectFirst("span.release_date")?.text()
            ?.substringBefore("-")?.trim()
        val genres = document.select("span.genres a").map {
            Genre(
                id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                name = it.text().trim(),
            )
        }
        val seasonNumber = title.substringAfter("Saison ", "").trim()
            .substringBefore(" ").toIntOrNull() ?: 1
        val votes = document.selectFirst("div.fr-votes")
        val rating = if (votes != null) getRating(votes) else null

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
            rating = rating,
        )
    }

    // ── Episodes ─────────────────────────────────────────────────────────

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        initializeService()
        return try {
            val document = fetchDetailPage(seasonId)
            parseEpisodesFromPage(document, seasonId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Parse episodes from div#episodeN.fullsfeature blocks.
     *  Each block is delimited by <!-- episode N --> comments in the HTML
     *  and contains a div.selink with the episode title and embed links.
     *
     *  If the page has no episode blocks but DOES have movie players, we
     *  synthesize a single "episode 1" so the user can still launch playback
     *  — this happens when FrenchStream lists a movie on its /series catalog
     *  (e.g. "Le Mage du Kremlin") and the item gets typed as TvShow. */
    private fun parseEpisodesFromPage(document: Document, seasonId: String): List<Episode> {
        val out = mutableListOf<Episode>()
        document.select("div.fullsfeature[id^=episode]").forEach { epDiv ->
            val epId = epDiv.id() // e.g. "episode3"
            val number = epId.removePrefix("episode").toIntOrNull() ?: return@forEach
            val titleText = epDiv.selectFirst("div.selink span")?.text()?.trim()
            val title = if (!titleText.isNullOrBlank()) titleText else "Épisode $number"
            out.add(
                Episode(
                    id = "$seasonId/$number",
                    number = number,
                    title = title,
                )
            )
        }
        if (out.isEmpty()) {
            val hasMoviePlayers = document.selectFirst("button.player-option") != null
            if (hasMoviePlayers) {
                Log.d("FrenchStream",
                    "No episode blocks but page has movie players — " +
                    "synthesizing fake episode 1 for $seasonId")
                val movieTitle = document.selectFirst("h1#s-title")?.ownText()?.trim()
                    ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
                out.add(
                    Episode(
                        id = "$seasonId/1",
                        number = 1,
                        title = movieTitle.ifBlank { "Film" },
                    )
                )
            }
        }
        return out.sortedBy { it.number }
    }

    // ── Servers / Players ────────────────────────────────────────────────

    /** Parse players from the detail page HTML.
     *
     *  **Movies**: button.player-option elements carry data-player (name) and
     *  data-url-default (embed URL). Each button may contain children
     *  div.version-option with data-version (language) and data-url.
     *
     *  **Series episodes**: the episode block div#episodeN.fullsfeature contains
     *  a href="https://embed-url" links — one per available server. */
    private fun parsePlayersFromPage(
        document: Document,
        forEpisodeNumber: Int?
    ): List<Video.Server> {
        val out = mutableListOf<Video.Server>()
        val seen = HashSet<String>()

        if (forEpisodeNumber != null) {
            // ── Series episode: extract embed links from the episode block ──
            val epDiv = document.selectFirst("div#episode${forEpisodeNumber}.fullsfeature")
            if (epDiv == null) {
                // The /series catalog on FrenchStream sometimes lists movies
                // (e.g. "Le Mage du Kremlin", "Aventures Croisées") which then
                // get classified as TvShow upstream. The detail page has NO
                // episode blocks — but it has the movie button.player-option
                // structure. Fall through to the movie-parsing branch so the
                // user still gets the players instead of an empty list.
                Log.d("FrenchStream",
                    "Episode #$forEpisodeNumber not found in detail page — " +
                    "falling back to movie player parsing")
                // (don't return; let the movie branch run below)
            } else {
                epDiv.select("a[href]").forEachIndexed { i, link ->
                    val href = link.attr("href").trim()
                    if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript")) return@forEachIndexed
                    if (!href.startsWith("http")) return@forEachIndexed
                    if (!seen.add(href)) return@forEachIndexed
                    if (ignoreSource("", href)) return@forEachIndexed
                    val serviceName = Extractor.identifyServiceName(href)
                        ?: href.substringAfter("//").substringBefore("/")
                            .substringBeforeLast(".").substringAfterLast(".")
                    val displayName = serviceName.replaceFirstChar { it.uppercase() }
                    out.add(Video.Server(
                        id = "fs_ep${forEpisodeNumber}_$i",
                        name = displayName,
                        src = href,
                    ))
                }
                return out
            }
        }
        // Movie OR fallback when episode block was missing (movie wrongly
        // classified as series upstream). Both paths land here.
        run {
            // ── Movie: parse the JS `playerUrls = {...}` dictionary ──
            // The HTML <button class="player-option"> elements only carry
            // data-url-default for legacy players (ViDZY, Dood). Newer
            // players (Uqload, Voe, Filmoon, Premium, Netu) have empty
            // version-options and rely entirely on the JS dictionary.
            // Parsing the dict gives us every player + every version URL.
            val htmlText = document.toString()
            val dictMatch = Regex(
                """playerUrls\s*=\s*(\{[\s\S]*?\});""",
                RegexOption.MULTILINE
            ).find(htmlText)
            val playerOrder = mutableListOf<String>()
            document.select("button.player-option").forEach { button ->
                val name = button.attr("data-player").trim()
                if (name.isNotBlank()) playerOrder.add(name)
            }
            if (dictMatch != null) {
                try {
                    val json = org.json.JSONObject(dictMatch.groupValues[1])
                    val keys = json.keys().asSequence().toList()
                    // Iterate in HTML button order if available, else dict order
                    val orderedKeys = if (playerOrder.isNotEmpty())
                        playerOrder.filter { json.has(it) } +
                            keys.filter { it !in playerOrder }
                    else keys
                    orderedKeys.forEachIndexed { i, playerName ->
                        val versions = json.optJSONObject(playerName) ?: return@forEachIndexed
                        // Order versions: Default → VFF → VFQ → VOSTFR
                        val versionOrder = listOf("Default", "VFF", "VFQ", "VOSTFR")
                        val allKeys = versions.keys().asSequence().toList()
                        val orderedVersions = versionOrder.filter { allKeys.contains(it) } +
                            allKeys.filter { it !in versionOrder }
                        // Track URLs already added per player to dedupe Default
                        // when it equals one of the versions.
                        val seenForPlayer = HashSet<String>()
                        orderedVersions.forEach { versionName ->
                            val url = versions.optString(versionName, "").trim()
                            if (url.isBlank()) return@forEach
                            // Skip the Multiup placeholder Netu URL (vid= empty)
                            if (url.contains("vid=&", ignoreCase = true)) return@forEach
                            // Dedupe within this player (Default often dupes a version)
                            if (!seenForPlayer.add(url)) return@forEach
                            // Global dedupe across players too
                            if (!seen.add(url)) return@forEach
                            if (ignoreSource(playerName, url)) return@forEach
                            val displayName = if (versionName == "Default")
                                playerName else "$playerName ($versionName)"
                            out.add(Video.Server(
                                id = "fs_player_${i}_${versionName}",
                                name = displayName,
                                src = url,
                            ))
                        }
                    }
                } catch (e: Exception) {
                    Log.w("FrenchStream", "playerUrls JSON parse failed: ${e.message} — falling back to HTML buttons")
                    // Fall through to HTML parsing below
                }
            }
            // Legacy / fallback: parse button.player-option from HTML.
            // Runs only when JS dict was missing or unparseable.
            if (out.isEmpty()) {
                document.select("button.player-option").forEachIndexed { i, button ->
                    val playerName = button.attr("data-player").trim()
                        .ifBlank { "Player ${i + 1}" }
                    val defaultUrl = button.attr("data-url-default").trim()
                    val versionOptions = button.select("div.version-option")
                    if (versionOptions.isNotEmpty()) {
                        versionOptions.forEach { version ->
                            val versionName = version.attr("data-version").trim()
                            val versionUrl = version.attr("data-url").trim()
                            if (versionUrl.isNotBlank() && seen.add(versionUrl)) {
                                if (ignoreSource(playerName, versionUrl)) return@forEach
                                val displayName = if (versionName.isNotBlank())
                                    "$playerName ($versionName)" else playerName
                                out.add(Video.Server(
                                    id = "fs_player_${i}_${versionName}",
                                    name = displayName,
                                    src = versionUrl,
                                ))
                            }
                        }
                    } else if (defaultUrl.isNotBlank() && seen.add(defaultUrl)) {
                        if (!ignoreSource(playerName, defaultUrl)) {
                            out.add(Video.Server(
                                id = "fs_player_$i",
                                name = playerName,
                                src = defaultUrl,
                            ))
                        }
                    }
                }
            }
        }
        return out
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()

        val servers = when (videoType) {
            is Video.Type.Episode -> {
                val parts = id.split("/")
                val tvShowId = parts.getOrElse(0) { id }
                val episodeNum = parts.getOrElse(1) { videoType.number.toString() }
                    .toIntOrNull() ?: videoType.number
                val document = try { fetchDetailPage(tvShowId) }
                    catch (_: Exception) { return emptyList() }
                parsePlayersFromPage(document, forEpisodeNumber = episodeNum)
            }
            is Video.Type.Movie -> {
                val document = try { fetchDetailPage(id) }
                    catch (_: Exception) { return emptyList() }
                parsePlayersFromPage(document, forEpisodeNumber = null)
            }
        }

        // Sort: VF/TrueFrench first, then by service reliability, VOSTFR/VO last
        return servers.sortedWith(compareBy<Video.Server> { server ->
            val name = server.name.uppercase()
            when {
                name.contains("TRUEFRENCH") || name.contains("VFF") -> 0
                name.contains("VFQ") || (name.contains("FRENCH") && !name.contains("VOSTFR")) -> 1
                name.contains("VF") && !name.contains("VOSTFR") -> 2
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
        return Extractor.extract(finalUrl)
    }

    // ── Genre ────────────────────────────────────────────────────────────

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService()
        val document = try {
            service.getGenre(id, page)
        } catch (_: Exception) {
            return Genre(id = id, name = "", shows = emptyList())
        }
        val shows = document.select("div.short").mapNotNull { item ->
            val linkEl = item.selectFirst("a.short-poster") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            val itemId = extractNewsId(href) ?: return@mapNotNull null
            val title = item.selectFirst("div.short-title")?.text() ?: ""
            val poster = item.selectFirst("img")?.attr("src")
            val isSeries = !item.hasClass("film")
                && (title.contains("Saison", ignoreCase = true)
                || href.contains("/serie/") || href.contains("/s-tv/"))
            if (isSeries)
                TvShow(id = itemId, title = title, poster = poster)
            else
                Movie(id = itemId, title = title, poster = poster)
        }
        return Genre(id = id, name = "", shows = shows)
    }

    // ── People ───────────────────────────────────────────────────────────

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id, "")
    }

    // ── URL management ───────────────────────────────────────────────────

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                val addressService = Service.buildAddressFetcher()
                try {
                    val document = addressService.getHome()
                    val newUrl = document.select("div.container > div.url-card")
                        .selectFirst("a")
                        ?.attr("href")
                        ?.trim()
                    if (!newUrl.isNullOrEmpty()) {
                        val finalUrl = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, finalUrl)
                        UserPreferences.setProviderCache(
                            this,
                            UserPreferences.PROVIDER_LOGO,
                            finalUrl + "favicon-96x96.png"
                        )
                    }
                } catch (_: Exception) {
                    // Fallback to default URL
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

    // ── Retrofit Service ─────────────────────────────────────────────────

    private interface Service {

        companion object {
            private val client = OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .dns(DnsResolver.doh)
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
                return Retrofit.Builder()
                    .baseUrl(portalUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }

            fun build(baseUrl: String): Service {
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET(".")
        suspend fun getHome(
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        @FormUrlEncoded
        @POST("engine/ajax/search.php")
        suspend fun search(
            @Field("query") query: String,
            @Field("page") page: Int = 1
        ): Document

        // Catalog pages — may return MySQL errors on redesigned site
        @GET("films")
        suspend fun getMovies(@Query("page") page: Int): Document

        @GET("series")
        suspend fun getTvShows(@Query("page") page: Int): Document

        // Detail page by newsid (new URL scheme: /index.php?newsid=XXXXX)
        @GET("index.php")
        suspend fun getItemByNewsId(
            @Query("newsid") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

        // Legacy detail pages (slug-based, kept for backwards compatibility)
        @GET("/{id}")
        suspend fun getItem(
            @Path("id") id: String,
            @Header("Cookie") cookie: String = "dle_skin=VFV1"
        ): Document

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

        // Genre listing
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

        @GET
        suspend fun getRedirectLink(@Url url: String): Response<ResponseBody>
    }
}
