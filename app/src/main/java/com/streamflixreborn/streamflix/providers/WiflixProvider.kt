package com.streamflixreborn.streamflix.providers

import android.content.Context
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
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.WebViewResolver
import com.streamflixreborn.streamflix.StreamFlixApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path

object WiflixProvider : Provider, ProviderPortalUrl, ProviderConfigUrl, ProgressiveServersProvider {

    override val name = "Wiflix"

    override val defaultPortalUrl: String = "https://ww1.wiflix-adresses.fun/"
    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty{ field }
        }

    // 2026-05-07 : flemmix.farm était hijacké → redirige vers clictune.com → dlink8.com
    // (système de monétisation pubs). Quand `onChangeUrl()` échoue (Cloudflare bloque
    // le portail, réseau lent au cold-start, ou auto-update désactivé), l'app tombait
    // sur ces redirects → 403/contenu invalide → provider cassé.
    // flemmix.prof est le site principal officiel (icône 🚨 sur wiflix-adresses.fun)
    // et héberge le vrai contenu Wiflix (template `flemmixnew` reconnu par le code).
    // 2026-06-02 : flemmix.win bloqué par les FAI (cf portail wiflix-adresses.fun
    //   qui le liste comme "Ancien domaine - bloqué"). Nouveau lien principal =
    //   flemmix.team (badge "Actif" / "LIEN PRINCIPAL" sur le portail).
    // 2026-06-13 (user "wilflix backup n'apparaît pas") : flemmix.team est
    //   maintenant marqué "Ancien domaine principal - bloqué" sur
    //   wiflix-adresses.fun. Le scraping HTTP brut retourne une page "Accès
    //   sécurisé" (Cloudflare challenge) → fetchWiflixDirectBackup retourne
    //   vide pour TOUTES les séries (pas que From), donc Wiflix backup
    //   n'apparaissait plus sur les fiches Cloudstream. Nouveau lien
    //   principal officiel = flemmix.city (badge "Lien principal" / "Domaine
    //   actif - Utilisez toujours ce lien" sur le portail).
    override val defaultBaseUrl: String = "https://flemmix.city/"
    // Liste des domaines morts/bloqués connus. Si le cache PROVIDER_URL est sur
    // un de ceux-ci, on force un refresh au prochain initializeService() au lieu
    // de rester coincé dessus à vie. Mettre à jour quand le portail évolue.
    private val knownBlockedDomains = listOf(
        "flemmix.win", "flemmix.prof", "flemmix.irish", "flemmix.wales",
        "flemmix.vip", "flemmix.casa", "wiflix.re", "wiflix.eu", "wiflix.org",
        "flemmix.farm", "flemmix.team",
    )
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty{ field }
        }

    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_wiflix"

    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private lateinit var service: Service
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    // Cloudflare bypass via WebViewResolver (same pattern as Cine24hProvider)
    private var webViewResolver: WebViewResolver? = null
    private const val TAG = "WiflixBypass"

    // Si Cloudflare a bloqué un appel Retrofit récemment, on skip Retrofit
    // et on va direct au getDocument (OkHttp+cookies ou WebView bypass).
    // Reset quand getDocument réussit (les cookies CF sont alors injectés).
    @Volatile
    private var cloudflareActive = false

    // In-memory document cache to avoid redundant Cloudflare bypasses.
    // Key = URL, Value = (Document, timestampMs)
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    private val documentCache = mutableMapOf<String, Pair<Document, Long>>()

    private fun getCachedDocument(url: String): Document? {
        val entry = documentCache[url] ?: return null
        if (System.currentTimeMillis() - entry.second > CACHE_TTL_MS) {
            documentCache.remove(url)
            return null
        }
        return entry.first
    }

    private fun cacheDocument(url: String, doc: Document) {
        // Keep cache size reasonable
        if (documentCache.size > 20) {
            val now = System.currentTimeMillis()
            documentCache.entries.removeAll { now - it.value.second > CACHE_TTL_MS }
        }
        documentCache[url] = Pair(doc, System.currentTimeMillis())
    }

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(StreamFlixApp.instance).also {
            webViewResolver = it
        }
    }

    fun init(context: Context) {
        webViewResolver = WebViewResolver(context)
    }

    private val challengeKeywords = listOf(
        "Just a moment...", "cf-browser-verification", "challenge-running",
        "Checking your browser", "cf-turnstile",
        // 2026-06-13 (user autorisation "si ca casse rien") : signature du bot
        //   shield de flemmix.city (~18 bytes "Bot shield active."). Permet a
        //   getDocument de declencher le WebView bypass sur ce cas (= un vrai
        //   navigateur passe le bot shield). N'apparait jamais sur du contenu
        //   legitime de Wiflix → zero faux positif.
        "Bot shield active"
    )

    /**
     * Fast Cloudflare detection on a parsed Document WITHOUT re-serializing
     * the entire DOM to string (which is very expensive on large pages).
     * Checks title + a few lightweight selectors instead.
     */
    private fun isCloudflareChallenge(doc: Document): Boolean {
        val title = doc.title()
        if (title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Checking your browser", ignoreCase = true)) return true
        if (doc.selectFirst("#challenge-running") != null) return true
        if (doc.selectFirst(".cf-browser-verification") != null) return true
        if (doc.selectFirst("[name=cf-turnstile-response]") != null) return true
        return false
    }

    // Shared OkHttpClient for getDocument() — avoids creating a new client per call
    private val bypassClient: OkHttpClient by lazy {
        NetworkClient.default.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Fetches a document with automatic Cloudflare bypass.
     * First checks the in-memory cache. Then tries OkHttp (with cookies
     * from previous WebView bypasses). If Cloudflare is still detected,
     * falls back to WebViewResolver which opens a real WebView.
     */
    private suspend fun getDocument(url: String, silentBypass: Boolean = true): Document {
        // Check cache first
        getCachedDocument(url)?.let {
            Log.d(TAG, "[Provider] Cache HIT for $url")
            return it
        }

        try {
            val request = Request.Builder()
                .url(url)
                .header("Referer", baseUrl)
                .header("User-Agent", NetworkClient.USER_AGENT)
                .build()

            val response = bypassClient.newCall(request).execute()

            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                if (challengeKeywords.none { html.contains(it, ignoreCase = true) }) {
                    val doc = Jsoup.parse(html).apply { setBaseUri(baseUrl) }
                    cacheDocument(url, doc)
                    // 2026-06-13 : populate URL cache pour le backup direct
                    //   (From sur la home, etc.). Helper safe + try-catch.
                    populateUrlCache(doc)
                    cloudflareActive = false
                    return doc
                }
                Log.d(TAG, "[Provider] Cloudflare challenge detected in HTML for $url")
                cloudflareActive = true
            } else {
                Log.d(TAG, "[Provider] HTTP ${response.code} for $url")
            }
        } catch (e: Exception) {
            Log.d(TAG, "[Provider] OkHttp failed for $url: ${e.message}")
        }

        // OkHttp failed or Cloudflare detected -> use WebView bypass
        Log.d(TAG, "[Provider] Launching WebView Bypass for $url (silent=$silentBypass)")
        val html = getResolver().get(url, silent = silentBypass)
        val doc = Jsoup.parse(html).apply { setBaseUri(baseUrl) }
        cacheDocument(url, doc)
        populateUrlCache(doc)
        // WebView bypass a injecté les cookies CF → Retrofit devrait marcher maintenant
        cloudflareActive = false
        return doc
    }

    /** 2026-06-13 : extrait tous les <a class="mov-t" href="..."> du Document
     *  + texte → met dans WiflixUrlCache (= mapping title → URL complete pour
     *  le backup direct Cloudstream qui doit court-circuiter le search bot-
     *  shieldé). No-op si le doc n'a pas de mov-t. try/catch pour ne JAMAIS
     *  faire planter le path principal. */
    private fun populateUrlCache(doc: Document) {
        try {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            val base = baseUrl.trimEnd('/')
            doc.select("a.mov-t[href]").forEach { el ->
                val href = el.attr("href").trim()
                if (href.isBlank()) return@forEach
                val absHref = when {
                    href.startsWith("http") -> href
                    href.startsWith("/") -> base + href
                    else -> "$base/$href"
                }
                val title = el.text().trim()
                if (title.isBlank()) return@forEach
                com.streamflixreborn.streamflix.utils.WiflixUrlCache.put(ctx, title, absHref)
            }
        } catch (_: Throwable) {}
    }

    // Flag to track if more search results are available. Set to false when API returns fewer items than requested.
    // This prevents querying non-existent pages that could return random/incorrect results.
    private var hasMore = true

    override suspend fun getHome(): List<Category> {
        try {
            initializeService()
        } catch (e: Exception) {
            Log.e(TAG, "[getHome] initializeService failed: ${e.message}, rebuilding")
            try {
                serviceInitialized = false
                initializeService()
            } catch (e2: Exception) {
                Log.e(TAG, "[getHome] initializeService retry failed: ${e2.message}")
            }
        }

        val document = try {
            val doc = service.getHome()
            if (isCloudflareChallenge(doc)) {
                Log.d(TAG, "[getHome] Cloudflare challenge in Retrofit response, using bypass")
                cloudflareActive = true
                getDocument(baseUrl, silentBypass = false)
            } else doc
        } catch (e: Exception) {
            Log.d(TAG, "[getHome] Retrofit failed: ${e.message}, using bypass")
            try {
                getDocument(baseUrl, silentBypass = false)
            } catch (e2: Exception) {
                Log.e(TAG, "[getHome] bypass also failed: ${e2.message}")
                return emptyList()
            }
        }

        val categories = mutableListOf<Category>()

        val topSeries = document.select("div.block-main").getOrNull(0)?.select("div.mov")?.mapNotNull {
            val id = it.selectFirst("a.mov-t")
                ?.attr("href")?.substringAfterLast("/")
                ?.takeIf { id -> id.isNotBlank() } ?: return@mapNotNull null
            TvShow(
                id = id,
                title = listOfNotNull(
                    it.selectFirst("a.mov-t")?.text(),
                    it.selectFirst("span.block-sai")?.text(),
                ).joinToString(" - "),
                poster = it.selectFirst("img")
                    ?.attr("src")?.let { src -> baseUrl + src },
                banner = it.selectFirst("img")
                    ?.attr("src")?.let { src -> baseUrl + src },
            )
        } ?: emptyList()

        val topFilms = document.select("div.block-main").getOrNull(1)?.select("div.mov")?.mapNotNull {
            val id = it.selectFirst("a.mov-t")
                ?.attr("href")?.substringAfterLast("/")
                ?.takeIf { id -> id.isNotBlank() } ?: return@mapNotNull null
            Movie(
                id = id,
                title = it.selectFirst("a.mov-t")?.text() ?: return@mapNotNull null,
                poster = it.selectFirst("img")
                    ?.attr("src")?.let { src -> baseUrl + src },
            )
        } ?: emptyList()

        // FEATURED carousel: deep copies from topSeries + topFilms with TMDB HD backdrops
        val featuredItems = mutableListOf<AppAdapter.Item>()
        topSeries.take(4).forEach { show ->
            featuredItems.add(TvShow(
                id = show.id,
                title = show.title,
                poster = show.poster,
                banner = show.banner,
            ))
        }
        topFilms.take(3).forEach { movie ->
            featuredItems.add(Movie(
                id = movie.id,
                title = movie.title,
                poster = movie.poster,
                banner = movie.poster,
            ))
        }
        if (featuredItems.isNotEmpty() && UserPreferences.enableTmdb) {
            // 2026-05-10 : CAP à 15 items pour éviter ~500 appels TMDB en burst au home.
            for (item in featuredItems.take(15)) {
                try {
                    val title = when (item) {
                        is TvShow -> item.title.substringBefore(" - ")
                        is Movie -> item.title
                        else -> continue
                    }
                    val results = TMDb3.Search.multi(title)
                    val match = results.results.firstOrNull { result ->
                        when (result) {
                            is TMDb3.Movie -> result.backdropPath != null
                            is TMDb3.Tv -> result.backdropPath != null
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
        }
        if (featuredItems.isNotEmpty()) {
            categories.add(Category(name = Category.FEATURED, list = featuredItems))
        }

        categories.add(Category(name = "TOP Séries", list = topSeries))
        categories.add(Category(name = "TOP Films", list = topFilms))
        categories.add(
            Category(
                name = "Films Anciens",
                list = document.select("div.block-main").getOrNull(2)?.select("div.mov")?.mapNotNull {
                    val id = it.selectFirst("a.mov-t")
                        ?.attr("href")?.substringAfterLast("/")
                        ?.takeIf { id -> id.isNotBlank() } ?: return@mapNotNull null
                    Movie(
                        id = id,
                        title = it.selectFirst("a.mov-t")?.text() ?: return@mapNotNull null,
                        poster = it.selectFirst("img")
                            ?.attr("src")?.let { src -> baseUrl + src },
                    )
                } ?: emptyList(),
            )
        )

        // Parser les sections "Derniers Episodes Séries-TV ajoutés" et "Séries-TV Saison complète"
        // Ces sections sont dans des blocs avec des en-têtes rouges et des listes de liens
        val lastSections = document.select("div.base")
        for (section in lastSections) {
            val headers = section.select("div.base-hd")
            val lists = section.select("div.base-mn ul.last")

            for (i in headers.indices) {
                val headerText = headers.getOrNull(i)?.text()?.trim() ?: continue
                val listItems = lists.getOrNull(i)?.select("li") ?: continue
                if (listItems.isEmpty()) continue

                val shows = listItems.mapNotNull { li ->
                    val link = li.selectFirst("a") ?: return@mapNotNull null
                    val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val showId = href.substringAfterLast("/").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val title = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null

                    if (href.contains("film-en-streaming/") || href.contains("film-ancien/")) {
                        Movie(id = showId, title = title)
                    } else {
                        TvShow(id = showId, title = title)
                    }
                }

                if (shows.isNotEmpty()) {
                    categories.add(Category(name = headerText, list = shows))
                }
            }
        }

        return categories
    }

    suspend fun ignoreSource(source: String): Boolean {
        if (arrayOf("netu", "vudeo").any { it.equals(source, true)})
            return true
        return false
    }

    /** Remove duplicate servers that resolve to the same service (e.g. luluvdo.com & luluvdoo.com). */
    private fun deduplicateServers(servers: List<Video.Server>): List<Video.Server> {
        val seen = mutableSetOf<String>()
        return servers.filter { server ->
            val serviceName = Extractor.identifyServiceName(server.src)
            if (serviceName != null) seen.add(serviceName) else true
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        initializeService()

        if (query.isEmpty()) {
            val document = try {
                val doc = service.getHome()
                if (isCloudflareChallenge(doc)) getDocument(baseUrl) else doc
            } catch (e: Exception) { getDocument(baseUrl) }

            val genres = document.select("div.side-b").getOrNull(1)?.select("ul li")?.map {
                Genre(
                    id = it.selectFirst("a")
                        ?.attr("href")?.substringBeforeLast("/")?.substringAfterLast("/")
                        ?: "",
                    name = it.selectFirst("a")
                        ?.text()
                        ?: "",
                )
            }?.toMutableList() ?: mutableListOf()

            // Ajouter K-Drama (recherche par mots-clés via GenreViewModel)
            if (genres.none { (it as? Genre)?.id == "k-drama" }) {
                genres.add(Genre(id = "k-drama", name = "K-Drama"))
            }

            return genres
        }

        if (page > 1 && !hasMore) return emptyList()

        // 2026-06-13 : ROLLBACK du silent=false sur search (testé : provoquait
        //   une page blanche bloquée — le WebView poll 79 fois pendant 25s avec
        //   Challenge:false Content:false Clearance:false. Le bot shield de
        //   flemmix.city refuse TOUT WebView Android (= fingerprint TLS/JA3
        //   distingue Chrome desktop d'Android Chromium). Search definitivement
        //   morte côté app, le palliatif = cache URL via la home (cf
        //   WiflixUrlCache populate). On garde l'ancienne logique silencieuse
        //   pour ne pas bloquer l'UI.
        val searchUrl = "${baseUrl}index.php?do=search&subaction=search&story=$query&search_start=$page&result_from=${1 + 20 * (page - 1)}"
        val document = try {
            val doc = service.search(
                story = query,
                searchStart = page,
                resultFrom = 1 + 20 * (page - 1)
            )
            if (isCloudflareChallenge(doc)) getDocument(searchUrl) else doc
        } catch (e: Exception) {
            getDocument(searchUrl)
        }

        document.selectFirst("div.berrors")?.text()?.let { resultText ->
            val totalResults = resultText.substringAfter("trouvé ")
                .substringBefore(" réponses").toIntOrNull() ?: 0
            val currentRange = resultText.substringAfter("Résultats de la requête ")
                .substringBefore(")").split(" - ")
            val receivedItems = currentRange.getOrNull(1)?.toIntOrNull() ?: 0

            hasMore = receivedItems < totalResults
        }

        // 2026-06-13 (user "il a trouvé plein de choses tout sauf la série") :
        //   la page search retourne 30 div.mov dont les 10 PREMIERS sont des
        //   films aleatoires en "recommandations" (= div.no-results-rec /
        //   div.floaters affichee meme quand il y a des resultats search). On
        //   exclut les div.mov descendants de ces 2 containers → on garde
        //   uniquement les vrais resultats search en-dessous.
        val results = document.select("div.mov")
            .filter { el ->
                el.parents().none { p ->
                    p.hasClass("no-results-rec") ||
                    (p.hasClass("floaters") && p.hasClass("grid-thumb"))
                }
            }
            .mapNotNull {
            val showId = it.selectFirst("a.mov-t")
                ?.attr("href")?.substringAfterLast("/")
                ?: ""
            val showPoster = it.selectFirst("img")
                ?.attr("src")?.let { src -> baseUrl + src }

            val href = it.selectFirst("a.mov-t")
                ?.attr("href")
                ?: ""
            if (href.contains("film-en-streaming/") || href.contains("film-ancien/")) {
                Movie(
                    id = showId,
                    title = it.selectFirst("a.mov-t")
                        ?.text()
                        ?: "",
                    poster = showPoster,
                )
            } else if (href.contains("serie-en-streaming/") || href.contains("vf/") || href.contains("saison-complete/")) {
                TvShow(
                    id = showId,
                    title = listOfNotNull(
                        it.selectFirst("a.mov-t")?.text(),
                        it.selectFirst("span.block-sai")?.text(),
                    ).joinToString(" - "),
                    poster = showPoster,
                )
            } else {
                null
            }
        }

        return results
    }

    /**
     * 2026-06-13 (user "tu touches pas a l'officiel provider" autorisation
     *  exceptionnelle "si ca casse rien") :
     *  Methode publique additionnelle (ne modifie pas search() existant) qui
     *  retourne les resultats search BRUTS (href + title), sans filtrer par
     *  film-en-streaming / serie-en-streaming. Sert au backup direct depuis
     *  MovixProvider qui doit pouvoir matcher From hostee sur saison-complete/
     *  (= le filtre actuel de search() rejettait ce slug → no backup Wiflix
     *  sur les series saison-complete).
     *  Reuse exactement le meme path : initializeService() (= pose le CF
     *  bypass cookie) puis service.search() ou getDocument() en fallback.
     *  Zero impact sur search() existant, zero impact sur autre code.
     */
    suspend fun searchRaw(query: String, page: Int = 1): List<Pair<String, String>> {
        if (query.isBlank()) return emptyList()
        initializeService()
        val searchUrl = "${baseUrl}index.php?do=search&subaction=search&story=$query&search_start=$page&result_from=${1 + 20 * (page - 1)}"
        // 2026-06-13 : flemmix.city sert un "Bot shield active." (~18 bytes)
        //   sur les POST search direct via service Retrofit. isCloudflareChallenge
        //   ne le detecte pas (= keyword different). On force le WebView bypass
        //   via getDocument quand on detecte un body trop court ou la signature
        //   bot shield → le main resolver passe le challenge en vrai navigateur.
        val document = try {
            val doc = service.search(
                story = query,
                searchStart = page,
                resultFrom = 1 + 20 * (page - 1),
            )
            val html = doc.outerHtml()
            val botShielded = html.length < 500 ||
                html.contains("Bot shield active", ignoreCase = true) ||
                html.contains("Bot detected", ignoreCase = true)
            if (botShielded || isCloudflareChallenge(doc)) {
                // 2026-06-13 : ROLLBACK silent=false (testé : page blanche
                //   bloquée 25s). Le bot shield ne se résout JAMAIS depuis un
                //   WebView Android. On reste silencieux → fail propre →
                //   le backup direct utilise le cache URL via la home.
                getDocument(searchUrl)
            } else doc
        } catch (e: Exception) {
            getDocument(searchUrl)
        }
        // 2026-06-13 : meme filtre que dans search() — exclut les 10 films
        //   featured de div.no-results-rec / div.floaters.grid-thumb qui
        //   parasitent la page search (= recommandations affichees AVANT les
        //   vrais resultats). On garde uniquement les vrais resultats search.
        return document.select("div.mov")
            .filter { el ->
                el.parents().none { p ->
                    p.hasClass("no-results-rec") ||
                    (p.hasClass("floaters") && p.hasClass("grid-thumb"))
                }
            }
            .mapNotNull {
            val link = it.selectFirst("a.mov-t") ?: return@mapNotNull null
            val href = link.attr("href").trim().ifBlank { return@mapNotNull null }
            // Resolve relative URL contre la baseUrl si besoin
            val absHref = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> baseUrl.trimEnd('/') + href
                else -> baseUrl.trimEnd('/') + "/" + href
            }
            val title = link.text().trim()
            absHref to title
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        initializeService()
        val document = try {
            val doc = service.getMovies(page)
            if (isCloudflareChallenge(doc)) getDocument("${baseUrl}film-en-streaming/page/$page") else doc
        } catch (e: Exception) { getDocument("${baseUrl}film-en-streaming/page/$page") }

        val movies = document.select("div.mov").map {
            Movie(
                id = it.selectFirst("a.mov-t")
                    ?.attr("href")?.substringAfterLast("/")
                    ?: "",
                title = it.selectFirst("a.mov-t")
                    ?.text()
                    ?: "",
                poster = it.selectFirst("img")
                    ?.attr("src")?.let { src -> baseUrl + src },
            )
        }

        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        initializeService()
        val document = try {
            val doc = service.getTvShows(page)
            if (isCloudflareChallenge(doc)) getDocument("${baseUrl}serie-en-streaming/page/$page") else doc
        } catch (e: Exception) { getDocument("${baseUrl}serie-en-streaming/page/$page") }

        val tvShows = document.select("div.mov").map {
            TvShow(
                id = it.selectFirst("a.mov-t")
                    ?.attr("href")?.substringAfterLast("/")
                    ?: "",
                title = listOfNotNull(
                    it.selectFirst("a.mov-t")?.text(),
                    it.selectFirst("span.block-sai")?.text(),
                ).joinToString(" - "),
                poster = it.selectFirst("img")
                    ?.attr("src")?.let { src -> baseUrl + src },
            )
        }

        return tvShows
    }

    override suspend fun getMovie(id: String): Movie {
        initializeService()
        val url = "${baseUrl}film-en-streaming/$id"
        val document = if (cloudflareActive) {
            getDocument(url)
        } else try {
            val doc = service.getMovie(id)
            if (isCloudflareChallenge(doc)) {
                cloudflareActive = true
                getDocument(url)
            } else doc
        } catch (e: Exception) { getDocument(url) }

        val movie = Movie(
            id = id,
            title = document.selectFirst("header.full-title h1")
                ?.text()
                ?: "",
            overview = (document.selectFirst("div.screenshots-full")?.text()
                ?.let { text ->
                    if (text.contains("en Streaming Complet:")) text.substringAfter("en Streaming Complet:").trim()
                    else text.trim()
                }?.takeIf { it.isNotBlank() }
                ?: document.select("ul.mov-list li")
                    .find { it.selectFirst("div.mov-label")?.text()?.contains("Synopsis") == true }
                    ?.selectFirst("div.mov-desc")?.text()?.trim()),
            released = document.select("ul.mov-list li")
                .find {
                    it.selectFirst("div.mov-label")?.text()?.contains("Date de sortie") == true
                }
                ?.selectFirst("div.mov-desc")
                ?.text()?.trim(),
            runtime = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text()?.contains("Durée") == true }
                ?.selectFirst("div.mov-desc")
                ?.text()?.let {
                    val hours = it.substringBefore("h").toIntOrNull() ?: 0
                    val minutes =
                        it.substringBeforeLast("min").substringAfterLast("h").trim().toIntOrNull() ?: 0
                    hours * 60 + minutes
                }?.takeIf { it != 0 },
            quality = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text()?.contains("Qualité") == true }
                ?.selectFirst("div.mov-desc")
                ?.text(),
            poster = document.selectFirst("img#posterimg")
                ?.attr("src")?.let { baseUrl + it },

            genres = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text()?.contains("GENRE") == true }
                ?.select("div.mov-desc a")?.mapNotNull {
                    if (it.text() == "Film") return@mapNotNull null

                    Genre(
                        id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                        name = it.text(),
                    )
                }
                ?: emptyList(),
            directors = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text()?.contains("ALISATEUR") == true }
                ?.selectFirst("div.mov-desc span")
                ?.let { element ->
                    element.text()
                        .split(", ")
                        .mapIndexed { index, name ->
                            People(
                                id = "director$index",
                                name = name,
                            )
                        }
                }
                ?: emptyList(),
            cast = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text()?.contains("ACTEURS") == true }
                ?.select("div.mov-desc a")?.map {
                    People(
                        id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                        name = it.text(),
                    )
                }
                ?: emptyList(),
            recommendations = document.select("div.related div.item").mapNotNull {
                if (it.hasClass("cloned")) return@mapNotNull null

                val showId = it.selectFirst("a")
                    ?.attr("href")?.substringAfterLast("/")
                    ?: ""
                val showTitle = it.selectFirst("span.title1")
                    ?.text()
                    ?: ""
                val showPoster = it.selectFirst("img")
                    ?.attr("src")?.let { src -> baseUrl + src }

                val href = it.selectFirst("a")
                    ?.attr("href")
                    ?: ""
                if (href.contains("film-en-streaming/") || href.contains("film-ancien/")) {
                    Movie(
                        id = showId,
                        title = showTitle,
                        poster = showPoster,
                    )
                } else if (href.contains("serie-en-streaming/") || href.contains("vf/") || href.contains("saison-complete/")) {
                    TvShow(
                        id = showId,
                        title = showTitle,
                        poster = showPoster,
                    )
                } else {
                    null
                }
            }
        )

        // Champ "Version: VOSTFR / VF" de la fiche Wiflix → source autoritaire de langue.
        movie.version = document.select("ul.mov-list li")
            .find { it.selectFirst("div.mov-label")?.text()?.contains("Version", ignoreCase = true) == true }
            ?.selectFirst("div.mov-desc")?.text()?.trim()

        return movie
    }

    override suspend fun getTvShow(id: String): TvShow {
        initializeService()
        val url = "${baseUrl}serie-en-streaming/$id"
        val document = if (cloudflareActive) {
            getDocument(url)
        } else try {
            val doc = service.getTvShow(id)
            if (isCloudflareChallenge(doc)) {
                cloudflareActive = true
                getDocument(url)
            } else doc
        } catch (e: Exception) { getDocument(url) }
        val title = document.selectFirst("header.full-title h1")
            ?.text()
            ?: ""
        val seasonNumber = title.substringAfter("Saison ").trim().toIntOrNull() ?: 0
        val tvShow = TvShow(
            id = id,
            title = title,
            overview = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text()?.contains("Synopsis") == true }
                ?.selectFirst("div.mov-desc")
                ?.text()
                ?.let { text ->
                    if (text.contains("en Streaming Complet:")) text.substringAfter("en Streaming Complet:").trim()
                    else text.trim()
                },
            released = document.select("ul.mov-list li")
                .find {
                    it.selectFirst("div.mov-label")?.text()?.contains("Date de sortie") == true
                }
                ?.selectFirst("div.mov-desc")
                ?.text()?.trim(),
            runtime = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text()?.contains("Durée") == true }
                ?.selectFirst("div.mov-desc")
                ?.text()?.let {
                    val hours = it.substringBefore("h").toIntOrNull() ?: 0
                    val minutes =
                        it.substringBeforeLast(" mn").substringAfterLast(" ").toIntOrNull() ?: 0
                    hours * 60 + minutes
                }?.takeIf { it != 0 },
            poster = document.selectFirst("img#posterimg")
                ?.attr("src")?.let { baseUrl + it },

            seasons = run {
                val seasonPoster = document.selectFirst("img#posterimg")
                    ?.attr("src")?.let { baseUrl + it }
                listOfNotNull(
                    Season(
                        id = "$id/blocvostfr",
                        title = "Épisodes - VOSTFR",
                        number = seasonNumber,
                        poster = seasonPoster,
                    ).takeIf { document.select("div.blocvostfr ul.eplist li").size > 0 },
                    Season(
                        id = "$id/blocfr",
                        title = "Épisodes - VF",
                        number = seasonNumber,
                        poster = seasonPoster,
                    ).takeIf { document.select("div.blocfr ul.eplist li").size > 0 },
                )
            },
            directors = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text()?.contains("ALISATEUR") == true }
                ?.selectFirst("div.mov-desc span")
                ?.let { element ->
                    element.text()
                        .split(", ")
                        .mapIndexed { index, name ->
                            People(
                                id = "director$index",
                                name = name,
                            )
                        }
                }
                ?: emptyList(),
            cast = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text()?.contains("ACTEURS") == true }
                ?.select("div.mov-desc a")?.map {
                    People(
                        id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                        name = it.text(),
                    )
                }
                ?: emptyList(),
            recommendations = document.select("div.related div.item").mapNotNull {
                if (it.hasClass("cloned")) return@mapNotNull null

                val showId = it.selectFirst("a")
                    ?.attr("href")?.substringAfterLast("/")
                    ?: ""
                val showTitle = it.selectFirst("span.title1")
                    ?.text()
                    ?: ""
                val showPoster = it.selectFirst("img")
                    ?.attr("src")?.let { src -> baseUrl + src }

                val href = it.selectFirst("a")
                    ?.attr("href")
                    ?: ""
                if (href.contains("film-en-streaming/") || href.contains("film-ancien/")) {
                    Movie(
                        id = showId,
                        title = showTitle,
                        poster = showPoster,
                    )
                } else if (href.contains("serie-en-streaming/") || href.contains("vf/") || href.contains("saison-complete/")) {
                    TvShow(
                        id = showId,
                        title = showTitle,
                        poster = showPoster,
                    )
                } else {
                    null
                }
            }
        )

        // Champ "Version" éventuel de la fiche série (sinon les libellés de saison
        // blocfr/blocvostfr renseignent déjà la langue via LanguageTag).
        tvShow.version = document.select("ul.mov-list li")
            .find { it.selectFirst("div.mov-label")?.text()?.contains("Version", ignoreCase = true) == true }
            ?.selectFirst("div.mov-desc")?.text()?.trim()

        return tvShow
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        initializeService()
        val (tvShowId, className) = seasonId.split("/")

        val document = try {
            val doc = service.getTvShow(tvShowId)
            if (isCloudflareChallenge(doc)) getDocument("${baseUrl}serie-en-streaming/$tvShowId") else doc
        } catch (e: Exception) { getDocument("${baseUrl}serie-en-streaming/$tvShowId") }

        // Use the show poster as episode thumbnail
        val showPoster = document.selectFirst("img#posterimg")
            ?.attr("src")?.let { baseUrl + it }

        val episodes = document.select("div.$className ul.eplist li").map {
            Episode(
                id = "$tvShowId/${it.attr("rel")}",
                number = it.text().substringAfter("Episode ").toIntOrNull() ?: 0,
                title = it.text(),
                poster = showPoster,
            )
        }

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        initializeService()
        val document = try {
            val doc = service.getGenre(id, page)
            if (isCloudflareChallenge(doc)) getDocument("${baseUrl}film-en-streaming/$id/page/$page") else doc
        } catch (e: Exception) { getDocument("${baseUrl}film-en-streaming/$id/page/$page") }

        val genre = Genre(
            id = id,
            name = "",

            shows = document.select("div.mov").map {
                Movie(
                    id = it.selectFirst("a.mov-t")
                        ?.attr("href")?.substringAfterLast("/")
                        ?: "",
                    title = it.selectFirst("a.mov-t")
                        ?.text()
                        ?: "",
                    poster = it.selectFirst("img")
                        ?.attr("src")?.let { src -> baseUrl + src },
                )
            },
        )

        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        initializeService()
        val document = try {
            val doc = service.getPeople(id, page)
            if (isCloudflareChallenge(doc)) getDocument("${baseUrl}xfsearch/acteurs/$id/page/$page") else doc
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> return People(id, "")
                else -> getDocument("${baseUrl}xfsearch/acteurs/$id/page/$page")
            }
        } catch (e: Exception) { getDocument("${baseUrl}xfsearch/acteurs/$id/page/$page") }


        val people = People(
            id = id,
            name = "",

            filmography = document.select("div.mov").mapNotNull {
                val showId = it.selectFirst("a.mov-t")
                    ?.attr("href")?.substringAfterLast("/")
                    ?: ""
                val showPoster = it.selectFirst("img")
                    ?.attr("src")?.let { src -> baseUrl + src }

                val href = it.selectFirst("a.mov-t")
                    ?.attr("href")
                    ?: ""
                if (href.contains("film-en-streaming/") || href.contains("film-ancien/")) {
                    Movie(
                        id = showId,
                        title = it.selectFirst("a.mov-t")
                            ?.text()
                            ?: "",
                        poster = showPoster,
                    )
                } else if (href.contains("serie-en-streaming/") || href.contains("vf/") || href.contains("saison-complete/")) {
                    TvShow(
                        id = showId,
                        title = listOfNotNull(
                            it.selectFirst("a.mov-t")?.text(),
                            it.selectFirst("span.block-sai")?.text(),
                        ).joinToString(" - "),
                        poster = showPoster,
                    )
                } else {
                    null
                }
            },
        )

        return people
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()
        val servers = when (videoType) {
            is Video.Type.Episode -> {
                val (tvShowId, rel) = id.split("/")

                val document = try {
                    val doc = service.getTvShow(tvShowId)
                    if (isCloudflareChallenge(doc)) getDocument("${baseUrl}serie-en-streaming/$tvShowId") else doc
                } catch (e: Exception) { getDocument("${baseUrl}serie-en-streaming/$tvShowId") }

                document.select("div.$rel a").
                    filter { ignoreSource(it.text().trim() ) == false }.
                    mapIndexedNotNull { index, it ->
                        val onclick = it.attr("onclick")
                        // 2026-06-03 (user "y a un WIFLIX SAVE Ça existe même pas
                        //   ça") : ne ramener un serveur QUE si le onclick contient
                        //   bien "loadVideo('...')". Avant : substringAfter sans
                        //   le "loadVideo('" → retournait un fragment de JS pour
                        //   les <a> qui sont en réalité des boutons "Sauvegarder
                        //   en favoris" ou autres liens parasites du HTML Wiflix.
                        //   Résultat : faux serveur "Save" qui n'existe pas.
                        if (!onclick.contains("loadVideo('")) return@mapIndexedNotNull null
                        val src = onclick.substringAfter("loadVideo('").substringBefore("'")
                        // Filtre robuste : le src doit être une vraie URL HTTP(S),
                        //   sinon c'est un fragment JS qui s'est faufilé.
                        if (src.isBlank() || !src.startsWith("http", ignoreCase = true)) {
                            return@mapIndexedNotNull null
                        }
                        val spanText = it.selectFirst("span")?.text()?.trim() ?: ""
                        val serviceName = Extractor.identifyServiceName(src)
                        val displayName = when {
                            serviceName != null && spanText.isNotEmpty() -> "$serviceName — $spanText"
                            serviceName != null -> serviceName
                            spanText.isNotEmpty() -> spanText
                            else -> "Lecteur ${index + 1}"
                        }
                        Video.Server(
                            id = serviceName ?: spanText.ifEmpty { index.toString() },
                            name = displayName,
                            src = src,
                    )
                }
            }

            is Video.Type.Movie -> {
                val document = try {
                    val doc = service.getMovie(id)
                    if (isCloudflareChallenge(doc)) getDocument("${baseUrl}film-en-streaming/$id") else doc
                } catch (e: Exception) { getDocument("${baseUrl}film-en-streaming/$id") }

                document.select("div.tabs-sel a").
                    filter { ignoreSource(it.text().trim() ) == false }.
                    mapIndexedNotNull { index, it ->
                        val onclick = it.attr("onclick")
                        // 2026-06-03 (user "y a un WIFLIX SAVE Ça existe même pas
                        //   ça") : ne ramener un serveur QUE si le onclick contient
                        //   bien "loadVideo('...')". Avant : substringAfter sans
                        //   le "loadVideo('" → retournait un fragment de JS pour
                        //   les <a> qui sont en réalité des boutons "Sauvegarder
                        //   en favoris" ou autres liens parasites du HTML Wiflix.
                        //   Résultat : faux serveur "Save" qui n'existe pas.
                        if (!onclick.contains("loadVideo('")) return@mapIndexedNotNull null
                        val src = onclick.substringAfter("loadVideo('").substringBefore("'")
                        // Filtre robuste : le src doit être une vraie URL HTTP(S),
                        //   sinon c'est un fragment JS qui s'est faufilé.
                        if (src.isBlank() || !src.startsWith("http", ignoreCase = true)) {
                            return@mapIndexedNotNull null
                        }
                        val spanText = it.selectFirst("span")?.text()?.trim() ?: ""
                        val serviceName = Extractor.identifyServiceName(src)
                        val displayName = when {
                            serviceName != null && spanText.isNotEmpty() -> "$serviceName — $spanText"
                            serviceName != null -> serviceName
                            spanText.isNotEmpty() -> spanText
                            else -> "Lecteur ${index + 1}"
                        }
                        Video.Server(
                            id = serviceName ?: spanText.ifEmpty { index.toString() },
                            name = displayName,
                            src = src,
                    )
                }
            }
        }

        // Remove duplicate servers (same service on different domains)
        val unique = deduplicateServers(servers)

        // Sort: VF/FR first, then by service reliability, VOSTFR last
        return unique.sortedWith(compareBy<Video.Server> { server ->
            val name = server.name.uppercase()
            when {
                name.contains("VFF") || name.contains("TRUEFRENCH") -> 0
                name.contains("VF") && !name.contains("VOSTFR") -> 1
                name.contains("FR") && !name.contains("VOSTFR") -> 2
                name.contains("VOSTFR") || name.contains("VOST") -> 5
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

    // ─────────────────────────────────────────────────────────────────────
    // 2026-05-27 : Backups Movix + Cloudstream (user "ajoute wiflix et
    //   frenchstream en backup pour tous les providers"). Wiflix = scraper
    //   natif, pas de tmdbId → résolution TMDB par titre/année. Pattern
    //   identique à FrenchStreamProvider.
    // ─────────────────────────────────────────────────────────────────────

    /** Résout le tmdbId via TMDB par titre (nécessaire pour les backups).
     *  2026-06-03 (user "j'ai changé carrément de film et c'est encore ce
     *  film là qui revient") : validation STRICTE. Avant : on acceptait
     *  n'importe quel résultat TMDB → un mauvais match (ex: "Intouchables"
     *  → un Marvel) faisait que le backup Movix renvoyait les serveurs d'un
     *  film qui n'a rien à voir → un VOE Marvel dans le picker Intouchables.
     *  Maintenant : on compare le titre TMDB et l'année avec le titre Wiflix
     *  d'origine ; si la similarité est insuffisante ou l'écart d'année ≥3
     *  ans → on retourne null = pas de backup (mieux que faux backup). */
    private suspend fun resolveWiflixTmdbId(videoType: Video.Type): Int? = runCatching {
        val title = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }
        val cleanTitle = TitleNormalizer.cleanForTmdbSearch(title).ifBlank { title }
        val year = when (videoType) {
            is Video.Type.Movie -> videoType.releaseDate.takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull()
            is Video.Type.Episode -> videoType.tvShow.releaseDate?.take(4)?.toIntOrNull()
        }
        val tmdbItem: Any? = when (videoType) {
            is Video.Type.Movie -> TmdbUtils.getMovie(cleanTitle, year, "fr-FR")
            is Video.Type.Episode -> TmdbUtils.getTvShow(cleanTitle, year, "fr-FR")
        }
        // Extraire titre/année du résultat TMDB pour validation.
        //   Movie.released / TvShow.released = Calendar? (pas String), donc on
        //   passe par Calendar.YEAR pour récupérer l'année.
        val (tmdbTitle, tmdbYear, tmdbId) = when (tmdbItem) {
            is Movie -> Triple(
                tmdbItem.title,
                tmdbItem.released?.get(java.util.Calendar.YEAR),
                tmdbItem.id.toIntOrNull(),
            )
            is TvShow -> Triple(
                tmdbItem.title,
                tmdbItem.released?.get(java.util.Calendar.YEAR),
                tmdbItem.id.toIntOrNull(),
            )
            else -> Triple("", null, null)
        }
        if (tmdbId == null) return@runCatching null
        // ── Validation titre (similarité normalisée) ────────────────────
        fun normalize(s: String) = s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val aNorm = normalize(cleanTitle)
        val bNorm = normalize(tmdbTitle)
        if (aNorm.isBlank() || bNorm.isBlank()) {
            Log.w("Wiflix", "Backup TMDB validation: titre vide (a='$aNorm' b='$bNorm') → drop")
            return@runCatching null
        }
        // Similarité simple : tokens partagés / max(tokens). Seuil 0.6 = ≥60%
        //   des tokens du titre Wiflix doivent être dans le titre TMDB.
        val aTokens = aNorm.split(" ").filter { it.length > 1 }.toSet()
        val bTokens = bNorm.split(" ").filter { it.length > 1 }.toSet()
        if (aTokens.isEmpty() || bTokens.isEmpty()) {
            Log.w("Wiflix", "Backup TMDB validation: tokens vides → drop")
            return@runCatching null
        }
        val shared = aTokens.intersect(bTokens).size
        val ratio = shared.toDouble() / maxOf(aTokens.size, bTokens.size)
        if (ratio < 0.6) {
            Log.w("Wiflix", "Backup TMDB validation: titre mismatch '$cleanTitle' ≠ '$tmdbTitle' (ratio=$ratio) → drop")
            return@runCatching null
        }
        // ── Validation année (±3 ans max) ──────────────────────────────
        if (year != null && tmdbYear != null) {
            val diff = kotlin.math.abs(year - tmdbYear)
            if (diff >= 3) {
                Log.w("Wiflix", "Backup TMDB validation: année mismatch $year vs $tmdbYear (diff=$diff) → drop")
                return@runCatching null
            }
        }
        Log.d("Wiflix", "Backup TMDB validation OK : '$cleanTitle' → tmdbId=$tmdbId ('$tmdbTitle' $tmdbYear)")
        tmdbId
    }.getOrNull()

    private suspend fun fetchWiflixMovixBackup(tmdbId: Int, videoType: Video.Type): List<Video.Server> = runCatching {
        val movixVideoType = if (videoType is Video.Type.Episode)
            videoType.copy(tvShow = videoType.tvShow.copy(id = "$tmdbId"))
        else videoType
        val movixId = when (videoType) {
            is Video.Type.Movie -> "$tmdbId"
            is Video.Type.Episode -> "$tmdbId-s${videoType.season.number}e${videoType.number}"
        }
        MovixProvider.getServersAsBackup(movixId, movixVideoType)
            // Filtrer les sources wiflix de Movix (on les a déjà en natif)
            .filter { srv -> !srv.id.startsWith("wiflix-") }
            // 2026-06-03 (user "1 VOE qui s'est mélangé par un film que j'avais
            //   regardé avant") : tagger visuellement les backups Movix pour que
            //   l'user distingue les serveurs natifs Wiflix (fiables, garantis
            //   sur CE film) des backups Movix (résolus par titre/année TMDB,
            //   risque de fausse association si matching imprécis). Avant : pas
            //   de préfixe → un VOE backup d'un autre film ressemblait à un VOE
            //   natif Wiflix → confusion. Pattern identique au backup CS.
            .map { srv -> srv.copy(id = "movix_backup__${srv.id}", name = "Movix — ${srv.name}") }
    }.getOrNull().orEmpty()

    private suspend fun fetchWiflixCloudstreamBackup(tmdbId: Int, videoType: Video.Type): List<Video.Server> = runCatching {
        val csId = when (videoType) {
            is Video.Type.Movie -> "$tmdbId"
            is Video.Type.Episode -> "$tmdbId:${videoType.season.number}:${videoType.number}"
        }
        val csVideoType = if (videoType is Video.Type.Episode)
            videoType.copy(tvShow = videoType.tvShow.copy(id = "$tmdbId"))
        else videoType
        CloudstreamProvider.getServers(csId, csVideoType)
            .map { srv -> srv.copy(id = "cs_backup__${srv.id}", name = "Cloudstream — ${srv.name}") }
    }.getOrNull().orEmpty()

    override fun getServersProgressive(
        id: String, videoType: Video.Type,
    ): Flow<List<Video.Server>> = channelFlow {
        initializeService()
        // 2026-06-03 (user "il devrait y avoir QUE des serveurs normal qui
        //   arrive normalement") : backups Movix/Cloudstream DÉSACTIVÉS. Ils
        //   créaient des faux positifs (ex: VOE Marvel apparaissant dans le
        //   picker Intouchables à cause d'un mauvais match TMDB par titre).
        //   On garde uniquement le scrape natif Wiflix qui est fiable (ID
        //   newsid → page Wiflix → embeds réels du bon film). Les helpers
        //   fetchWiflixMovixBackup / fetchWiflixCloudstreamBackup /
        //   resolveWiflixTmdbId restent dans le fichier au cas où on veut
        //   les ré-activer plus tard (avec un matching TMDB durci).
        launch {
            try {
                val native = getServers(id, videoType)
                if (native.isNotEmpty()) send(native)
            } catch (e: Exception) { Log.w("Wiflix", "Progressive native failed: ${e.message}") }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // Délégation backups
        if (server.id.startsWith("movix_backup__")) {
            val original = server.copy(id = server.id.removePrefix("movix_backup__"))
            return try { MovixProvider.getVideo(original) }
            catch (e: Exception) { Log.w("Wiflix", "Movix getVideo failed: ${e.message}"); Video(source = original.src) }
        }
        if (server.id.startsWith("cs_backup__")) {
            val original = server.copy(id = server.id.removePrefix("cs_backup__"))
            return try { CloudstreamProvider.getVideo(original) }
            catch (e: Exception) { Log.w("Wiflix", "CS getVideo failed: ${e.message}"); Video(source = original.src) }
        }

        val video = Extractor.extract(server.src)

        return video
    }

    /**
     * Initializes the service with the current domain URL.
     * This function is necessary because the provider's domain frequently changes.
     * We fetch the latest URL from a dedicated website that tracks these changes.
     */
    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            // 2026-06-13 (user "elle doit passer d'abord par le site de
            //   redirection puis aller sur le site officiel, rien de complique") :
            //   on supprime la condition `forceRefresh || autoupdate != false`.
            //   Desormais on passe TOUJOURS par le portail wiflix-adresses.fun
            //   pour recuperer le domaine actif (= comportement equivalent au
            //   navigateur qui clique sur le bon lien depuis le portail).
            //   Si le portail est down ou ne contient pas le bon candidat, on
            //   tombe dans le catch et garde le baseUrl cache. Comportement
            //   robuste + cookie h_check=25 injecte via l'interceptor →
            //   search Wiflix fonctionne.
            if (true) {
                val addressService = Service.buildAddressFetcher()

                try {
                    val document = addressService.getHome()

                    // Cerchiamo l'URL tra i vari elementi che solitamente contengono il link attivo
                    // v86b 2026-05-20 : auto-redirection ROBUSTE — ne depend plus de classes CSS
                    //   precises (qui changent a chaque refonte de la page d'adresses).
                    //   On prend TOUS les <a href>, on exclut le portail, les reseaux
                    //   sociaux, le sous-site anime, et les domaines marques
                    //   "bloque"/"faux"/"frauduleux". Parmi les candidats restants
                    //   (= domaines actifs cliquables) on prefere featured/active/
                    //   principal/actif, sinon le premier du DOM. Survit aux refontes.
                    val candidates = document.select("a[href]")
                        .map { el ->
                            val href = el.attr("href").trim()
                            val ctx = (el.className() + " " + el.text() + " " +
                                (el.parent()?.className() ?: "")).lowercase()
                            Pair(href, ctx)
                        }
                        .filter { (link, ctx) ->
                            link.startsWith("http") &&
                            !link.contains("wiflix-adresses") &&
                            !link.contains("french-anime") &&
                            !link.contains("facebook") &&
                            !link.contains("twitter") &&
                            !link.contains("t.me") &&
                            !link.contains("instagram") &&
                            !link.contains("pinterest") &&
                            !link.contains("youtube") &&
                            !ctx.contains("bloqu") &&
                            !ctx.contains("faux") &&
                            !ctx.contains("frauduleux")
                        }
                    val newUrl = (candidates.firstOrNull { (_, ctx) ->
                            ctx.contains("featured") || ctx.contains("active") ||
                            ctx.contains("principal") || ctx.contains("actif")
                        } ?: candidates.firstOrNull())
                        ?.first
                        ?.replace("http://", "https://")

                    if (!newUrl.isNullOrEmpty()) {
                        val formattedUrl = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, formattedUrl)
                        UserPreferences.setProviderCache(
                            this,
                            UserPreferences.PROVIDER_LOGO,
                            formattedUrl + "templates/flemmixnew/images/favicon.png"
                        )
                        Log.i("WiflixProvider",
                            "onChangeUrl OK : nouveau domaine = $formattedUrl (parmi ${candidates.size} candidats)")
                    } else {
                        Log.w("WiflixProvider",
                            "onChangeUrl : aucun candidat trouvé (candidates=${candidates.size}). " +
                                "On reste sur $baseUrl. Le portail wiflix-adresses.fun a peut-être changé de structure.")
                    }
                } catch (e: Exception) {
                    Log.e("WiflixProvider",
                        "onChangeUrl FAIL : ${e.message}. On reste sur $baseUrl.", e)
                    // In case of failure, we'll use the default URL
                    // No need to throw as we already have a fallback URL
                }
            }
            service = Service.build(baseUrl)
            serviceInitialized = true
        }

        // 2026-06-13 (user "la recherche ne fonctionne pas" + diag : POST search
        //   retourne 18 bytes "Bot shield active." sans h_check=25 cookie) :
        //   onChangeUrl est aussi appele directement au boot par StreamFlixApp
        //   (ligne 413) → injection au boot de h_check=25 dans le CookieManager
        //   Android pour le domaine actif. Partage avec OkHttp via la cookieJar
        //   de NetworkClient.default → toutes les requetes (search, getHome,
        //   etc.) portent h_check=25 + PHPSESSID dans le meme header Cookie.
        try {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setCookie(baseUrl, "h_check=25; path=/; max-age=86400")
            cm.flush()
            Log.w("WiflixProvider", "[H_CHECK] cookie h_check=25 pose pour $baseUrl")
        } catch (e: Throwable) {
            Log.w("WiflixProvider", "[H_CHECK] cookie injection failed: ${e.message}", e)
        }

        return baseUrl
    }

    private suspend fun initializeService() {
        initializationMutex.withLock {
            if (serviceInitialized) return

            // 2026-06-02 : si le cache est sur un domaine connu mort/bloqué
            //   (flemmix.win / .prof / .irish etc.), on FORCE un refresh pour
            //   éviter de rester collé dessus à vie. Sans ce check, un user
            //   coincé sur flemmix.win depuis l'install ne se mettait jamais
            //   à jour même si l'auto-update est activé (le cache 1ère valeur
            //   l'emporte sur defaultBaseUrl).
            val cachedUrl = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            val forceRefresh = cachedUrl.isNotBlank() &&
                knownBlockedDomains.any { cachedUrl.contains(it) }
            if (forceRefresh) {
                Log.w("WiflixProvider",
                    "Cache sur domaine bloqué ($cachedUrl) → force refresh")
            }
            Log.w("WiflixProvider", "[H_CHECK] avant onChangeUrl, baseUrl=$baseUrl")
            onChangeUrl(forceRefresh = forceRefresh)
            Log.w("WiflixProvider", "[H_CHECK] apres onChangeUrl, baseUrl=$baseUrl, vais injecter cookie")

            try {
                val cm = android.webkit.CookieManager.getInstance()
                cm.setCookie(baseUrl, "h_check=25; path=/; max-age=86400")
                cm.flush()
                val verif = cm.getCookie(baseUrl) ?: "(null)"
                Log.w("WiflixProvider", "[H_CHECK] cookie pose, verif getCookie($baseUrl) = $verif")
            } catch (e: Throwable) {
                Log.w("WiflixProvider", "[H_CHECK] FAIL: ${e.message}", e)
            }
        }
    }

    private interface Service {

        companion object {
            // Use NetworkClient.default as base — it already has the correct
            // User-Agent, Sec-Fetch headers, cookie jar, and DNS-over-HTTPS
            // that Cloudflare expects.  This means after a single WebView
            // bypass, subsequent Retrofit calls succeed with the cf_clearance cookie.
            private val client = NetworkClient.default.newBuilder()
                .readTimeout(10, TimeUnit.SECONDS)
                .connectTimeout(8, TimeUnit.SECONDS)
                // Preserve POST method & body on redirects (site may change domain)
                .followRedirects(false)
                // 2026-06-13 (user "fais simplement la meme recherche que sur
                //   le site officiel") : flemmix.city pose `h_check=25` via JS
                //   sur la home. Sans ce cookie, le POST search retourne 18
                //   bytes "Bot shield active.". Avec, on a les 114KB de
                //   resultats normaux. On l'injecte via interceptor → toutes
                //   les requetes vers flemmix.* portent h_check=25 dans le
                //   header Cookie automatiquement. Bypass total + 0 code
                //   complexe. Pose pareil que le navigateur ferait.
                .addInterceptor { chain ->
                    val original = chain.request()
                    val isFlemmix = original.url.host.contains("flemmix", ignoreCase = true) ||
                        original.url.host.contains("wiflix", ignoreCase = true)
                    val request = if (isFlemmix) {
                        val existingCookie = original.header("Cookie") ?: ""
                        val newCookie = if (existingCookie.contains("h_check=", ignoreCase = true)) {
                            existingCookie
                        } else if (existingCookie.isBlank()) {
                            "h_check=25"
                        } else {
                            "$existingCookie; h_check=25"
                        }
                        original.newBuilder().header("Cookie", newCookie).build()
                    } else original
                    chain.proceed(request)
                }
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
        suspend fun getHome(): Document

        // 2026-06-13 : Cookie h_check=25 injecte via CookieManager dans
        //   onChangeUrl (= partage avec PHPSESSID dans le meme header Cookie
        //   via la cookieJar de NetworkClient.default). @Headers statique
        //   ecrasait la cookieJar et envoyait h_check sans PHPSESSID → serveur
        //   refusait quand meme.
        @POST("index.php?do=search")
        @FormUrlEncoded
        suspend fun search(
            @Field("story") story: String,
            @Field("do") doo: String = "search",
            @Field("subaction") subaction: String = "search",
            @Field("search_start") searchStart: Int = 0,
            @Field("full_search") fullSearch: Int = 0,
            @Field("result_from") resultFrom: Int = 1,
        ): Document

        @GET("film-en-streaming/page/{page}")
        suspend fun getMovies(@Path("page") page: Int): Document

        @GET("serie-en-streaming/page/{page}")
        suspend fun getTvShows(@Path("page") page: Int): Document

        @GET("film-en-streaming/{id}")
        suspend fun getMovie(@Path("id") id: String): Document

        @GET("serie-en-streaming/{id}")
        suspend fun getTvShow(@Path("id") id: String): Document

        @GET("film-en-streaming/{genre}/page/{page}")
        suspend fun getGenre(
            @Path("genre") genre: String,
            @Path("page") page: Int,
        ): Document

        @GET("xfsearch/acteurs/{id}/page/{page}")
        suspend fun getPeople(
            @Path("id") id: String,
            @Path("page") page: Int,
        ): Document
    }
}
