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
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w780
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.WebViewResolver
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

object FrenchAnimeProvider : Provider, ProviderConfigUrl, ProgressiveServersProvider {
    override val defaultBaseUrl: String = "https://french-anime.com/"
    override val baseUrl: String = FrenchAnimeProvider.defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }
    override val name = "FrenchAnime"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_frenchanime"

    override val language = "fr"
    override val changeUrlMutex = Mutex()

    // 2026-07-12 : LAZY — l'init eager de FrenchAnimeService.build() tirait
    //   NetworkClient.default → buildClient() au <clinit> de cet objet, SUR LE
    //   MAIN THREAD (via Provider.providers class-loading). Sur Chromecast = +688ms
    //   de fige au boot. Backing field _service + getter lazy = construit au 1er
    //   appel réseau (hors main thread). onChangeUrl réassigne via _service.
    @Volatile private var _service: FrenchAnimeService? = null
    private val service: FrenchAnimeService
        get() = _service ?: synchronized(this) {
            _service ?: FrenchAnimeService.build().also { _service = it }
        }

    // 2026-06-21 (user "french-anime a maintenant le même captcha que Wiflix,
    //   il faut mettre la même chose pour lui") : Cloudflare bypass via
    //   WebViewResolver — même pattern que WiflixProvider. Détection du
    //   challenge + fallback WebView pour récupérer les cookies cf_clearance.
    private const val TAG_BYPASS = "FrenchAnimeBypass"
    @Volatile private var webViewResolver: WebViewResolver? = null
    @Volatile private var cloudflareActive = false
    // 2026-06-29 (REPAIR — règle user, même que Wiflix) : captcha invisible par
    //   défaut ; visible seulement après 3 échecs silencieux consécutifs.
    @Volatile private var silentFailStreak = 0
    @Volatile private var lastVisibleBypassAt = 0L
    private const val VISIBLE_AFTER_STREAK = 3
    private const val VISIBLE_BYPASS_COOLDOWN_MS = 60_000L
    // 2026-06-29 (user "captcha visible au 1er lancement, pages en 403 en cascade") :
    //   verrou qui SÉRIALISE le bypass WebView (un seul à la fois). Le 1er pose le
    //   cookie cf_clearance, les suivants (en attente) repartent en OkHttp direct.
    private val bypassMutex = Mutex()

    private val bypassClient: OkHttpClient by lazy {
        NetworkClient.default.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    // Cache mémoire des documents (5 min) — évite les bypass redondants.
    private const val DOC_CACHE_TTL_MS = 5 * 60 * 1000L
    private val documentCache = mutableMapOf<String, Pair<Document, Long>>()

    private fun getCachedDocument(url: String): Document? {
        val entry = documentCache[url] ?: return null
        if (System.currentTimeMillis() - entry.second > DOC_CACHE_TTL_MS) {
            documentCache.remove(url)
            return null
        }
        return entry.first
    }

    private fun cacheDocument(url: String, doc: Document) {
        if (documentCache.size > 20) {
            val now = System.currentTimeMillis()
            documentCache.entries.removeAll { now - it.value.second > DOC_CACHE_TTL_MS }
        }
        documentCache[url] = Pair(doc, System.currentTimeMillis())
    }

    private val challengeKeywords = listOf(
        "Just a moment...", "cf-browser-verification", "challenge-running",
        "Checking your browser", "cf-turnstile"
    )

    private fun isCloudflareChallenge(doc: Document): Boolean {
        val title = doc.title()
        if (title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Checking your browser", ignoreCase = true)) return true
        if (doc.selectFirst("#challenge-running") != null) return true
        if (doc.selectFirst(".cf-browser-verification") != null) return true
        if (doc.selectFirst("[name=cf-turnstile-response]") != null) return true
        return false
    }

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(StreamFlixApp.instance).also {
            webViewResolver = it
        }
    }

    /**
     * Récupère un document avec bypass Cloudflare automatique.
     * 1) Cache mémoire 5min
     * 2) OkHttp avec cookies CF persistés (si bypass précédent)
     * 3) Fallback WebView (silent par défaut → cf_clearance auto-injecté)
     * 4) Après bypass WebView réussi → retry OkHttp avec le cookie frais
     *    (= relance le scraping proprement, plus fiable que le HTML WebView)
     *
     * 2026-06-22 fix (user "il oublie de relancer le scrap après") :
     *   - Après bypass WebView, retry OkHttp pour re-scraper proprement
     *   - Ne PAS cacher les documents vides/challenge (sinon cache pourri 5 min)
     */
    private suspend fun getDocument(url: String, silentBypass: Boolean = true): Document {
        getCachedDocument(url)?.let {
            Log.d(TAG_BYPASS, "Cache HIT for $url")
            return it
        }
        // Tentative 1 (hors verrou, rapide) : OkHttp direct UNIQUEMENT si le cookie
        //   cf_clearance existe déjà — sinon CF renvoie la page challenge.
        if (hasCfClearanceCookie()) tryOkHttp(url)?.let { return it }

        // 2026-06-29 (user "captcha visible au 1er lancement, 403 en cascade") :
        //   getHome lance ~15 pages en parallèle. Sans verrou, chacune lance son
        //   propre bypass Turnstile AVANT que le cookie soit posé → contention sur
        //   l'unique WebView → la plupart échouent (403) + captcha s'affiche.
        //   Avec bypassMutex : UN SEUL bypass s'exécute ; les autres, en attente,
        //   trouvent ensuite le cookie/cache et repartent en OkHttp direct.
        return bypassMutex.withLock {
            // Re-check : un autre appel a peut-être déjà bypassé pendant l'attente.
            getCachedDocument(url)?.let {
                Log.d(TAG_BYPASS, "Cache HIT (post-lock) for $url")
                return@withLock it
            }
            if (hasCfClearanceCookie()) tryOkHttp(url)?.let { return@withLock it }

            // Tentative 2 : WebView bypass (Turnstile) — un seul à la fois.
            Log.d(TAG_BYPASS, "Launching WebView Bypass for $url (silent=$silentBypass)")
            var html = getResolver().get(url, silent = silentBypass)

            // 2026-06-29 (RÈGLE user, identique Wiflix) : captcha INVISIBLE par défaut ;
            //   après 3 échecs silencieux consécutifs → captcha VISIBLE.
            fun faFailed(h: String): Boolean =
                h.contains("silent fail") || h.contains("Timeout") || h.contains("no live activity")
            if (silentBypass) {
                if (faFailed(html)) {
                    silentFailStreak++
                    if (silentFailStreak >= VISIBLE_AFTER_STREAK &&
                        System.currentTimeMillis() - lastVisibleBypassAt > VISIBLE_BYPASS_COOLDOWN_MS) {
                        lastVisibleBypassAt = System.currentTimeMillis()
                        Log.d(TAG_BYPASS, "$silentFailStreak échecs silencieux → captcha VISIBLE pour $url")
                        html = getResolver().get(url, silent = false)
                        if (!faFailed(html)) { silentFailStreak = 0 }
                    } else {
                        Log.d(TAG_BYPASS, "Échec silencieux #$silentFailStreak (captcha reste invisible) pour $url")
                    }
                } else {
                    silentFailStreak = 0
                }
            }

            // Bypass échoué (silent fail / timeout) → ne PAS cacher, retourner doc vide
            if (html.contains("silent fail") || html.contains("Timeout") || html.contains("no live activity")) {
                Log.d(TAG_BYPASS, "WebView bypass failed for $url — NOT caching")
                cloudflareActive = true
                return@withLock Jsoup.parse(html).apply { setBaseUri(baseUrl) }
            }

            // Bypass réussi → cookie cf_clearance posé. Re-fetch OkHttp pour un HTML propre.
            Log.d(TAG_BYPASS, "WebView bypass succeeded for $url — retrying OkHttp with fresh cookie")
            cloudflareActive = false
            val freshDoc = tryOkHttp(url)
            if (freshDoc != null) {
                Log.d(TAG_BYPASS, "OkHttp retry after bypass SUCCESS for $url")
                return@withLock freshDoc
            }

            // Fallback : HTML du WebView tel quel.
            Log.d(TAG_BYPASS, "OkHttp retry after bypass FAILED for $url — using WebView HTML")
            val doc = Jsoup.parse(html).apply { setBaseUri(baseUrl) }
            cacheDocument(url, doc)
            return@withLock doc
        }
    }

    /** 2026-06-27 (REPAIR — recopié du décompilé v1.7.226) : true si le cookie
     *  cf_clearance est déjà présent dans le CookieManager pour ce domaine. */
    private fun hasCfClearanceCookie(): Boolean = try {
        val cookies = android.webkit.CookieManager.getInstance().getCookie(baseUrl) ?: ""
        cookies.contains("cf_clearance=")
    } catch (_: Throwable) { false }

    /** Tente un fetch OkHttp. Retourne le Document si succès + pas de challenge, null sinon. */
    private fun tryOkHttp(url: String): Document? {
        return try {
            // 2026-06-27 (REPAIR_HANDOFF #3) : injecter le cookie cf_clearance
            //   (lu dans le CookieManager WebView) — sinon Cloudflare bloque OkHttp.
            //   bypassClient (NetworkClient.default) ne proxie pas forcément ces
            //   cookies, donc on les attache explicitement (pattern décompilé).
            val webCookie = try {
                android.webkit.CookieManager.getInstance().getCookie(baseUrl) ?: ""
            } catch (_: Throwable) { "" }
            val request = Request.Builder()
                .url(url)
                .header("Referer", baseUrl)
                // 2026-06-28 : MÊME UA que le WebView stealth (Chrome 131).
                //   Le cookie cf_clearance est lié au UA — mismatch = rejet CF.
                .header("User-Agent", WebViewResolver.STEALTH_UA)
                .apply { if (webCookie.isNotBlank()) header("Cookie", webCookie) }
                .build()
            val response = bypassClient.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                if (challengeKeywords.none { html.contains(it, ignoreCase = true) }) {
                    val doc = Jsoup.parse(html).apply { setBaseUri(baseUrl) }
                    cacheDocument(url, doc)
                    cloudflareActive = false
                    doc
                } else {
                    Log.d(TAG_BYPASS, "Cloudflare challenge detected in HTML for $url")
                    cloudflareActive = true
                    null
                }
            } else {
                Log.d(TAG_BYPASS, "HTTP ${response.code} for $url")
                cloudflareActive = true
                null
            }
        } catch (e: Exception) {
            Log.d(TAG_BYPASS, "OkHttp failed for $url: ${e.message}")
            null
        }
    }

    /** Wrapper safe pour un appel Retrofit : si CF challenge détecté ou
     *  exception réseau → fallback WebView via getDocument(url).
     *  2026-06-21 v3 (user "je voulais le même pattern que Wiflix, le bypass
     *  ne s'affiche pas") : EXACT pattern Wiflix.
     *  - silentBypass param exposé au caller (= getHome force false pour
     *    montrer le captcha la 1ère fois)
     *  - Autres appels (search/episode/etc.) gardent default true. */
    private suspend fun safeGetDoc(
        url: String,
        silentBypass: Boolean = true,
        retrofitCall: suspend () -> Document,
    ): Document {
        if (!cloudflareActive) {
            try {
                val doc = retrofitCall()
                if (!isCloudflareChallenge(doc)) return doc
                Log.d(TAG_BYPASS, "CF challenge in Retrofit response for $url")
                cloudflareActive = true
            } catch (e: Exception) {
                Log.d(TAG_BYPASS, "Retrofit failed for $url: ${e.message}")
                cloudflareActive = true
            }
        }
        return getDocument(url, silentBypass = silentBypass)
    }

    fun init(context: android.content.Context) {
        webViewResolver = WebViewResolver(context)
    }

    /** 2026-07-03 (user "les jaquettes FrenchAnime ne chargent pas au démarrage, pas de
     *  préchauffage CF ; en quittant/revenant elles finissent par charger") : pré-résout le
     *  challenge Cloudflare de french-anime.com AU BOOT → pose le cf_clearance AVANT que
     *  Glide demande les jaquettes (hébergées sur ce domaine CF). Sans ça, au 1er affichage
     *  les posters partent avant le cookie → grises. Le bypass utilise une WebView TRANSITOIRE
     *  (WebViewResolver, créée puis détruite) → aucune WebView persistante (TV OK). */
    suspend fun warmUpCf() {
        try {
            getDocument(baseUrl, silentBypass = true)
            Log.d(TAG_BYPASS, "warmUpCf: cf_clearance FrenchAnime préchauffé au boot")
        } catch (e: Exception) {
            Log.w(TAG_BYPASS, "warmUpCf failed: ${e.message}")
        }
    }

    // Client OkHttp partagé pour searchDirect — basé sur bypassClient pour hériter du cookie jar CF
    private val searchClient: OkHttpClient by lazy {
        bypassClient.newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // Flag to track if more search results are available. Set to false when API returns fewer items than requested.
    // This prevents querying non-existent pages that could return random/incorrect results.
    private var hasMore = true
    private const val SEARCH_PAGE_SIZE = 10
    private val URL_DOMAIN_REGEX = Regex("""(?:https?:)?//(?:www\.)?([^.]+)\.""")

    override suspend fun getHome(): List<Category> {
        // 2026-07-03 (opti #3 — user "un truc plus rapide") : cache disque du home. Si un
        //   cache FRAIS (<30 min) existe → retour INSTANTANÉ, aucun fetch CF. Sinon on fait
        //   le fetch complet et on réécrit le cache (voir fin de fonction). Ouvertures
        //   suivantes = instantanées, comme les WebJS.
        run {
            val ctx = StreamFlixApp.instance
            val cached = com.streamflixreborn.streamflix.utils.HomeCacheStore.read(ctx, this)
            val age = com.streamflixreborn.streamflix.utils.HomeCacheStore.ageMs(ctx, this) ?: Long.MAX_VALUE
            if (!cached.isNullOrEmpty() && age < 30 * 60 * 1000L) return cached
        }

        var document = safeGetDoc(baseUrl, silentBypass = true) { service.getHome() }

        // 2026-06-22 fix (user "il oublie de relancer le scrap après") :
        // Si le document est vide (challenge/timeout), retry via OkHttp.
        // Le bypass WebView a pu résoudre le Turnstile → cookie cf_clearance
        // est maintenant dans le CookieManager → OkHttp devrait passer.
        if (document.select(".block-main").isEmpty() && document.select(".owl-carousel .item").isEmpty()) {
            Log.d(TAG_BYPASS, "getHome: document vide après bypass — retry OkHttp (cookie frais)")
            documentCache.remove(baseUrl) // vider le cache pourri
            kotlinx.coroutines.delay(500) // laisser le cookie se propager
            document = try {
                getDocument(baseUrl, silentBypass = true)
            } catch (_: Exception) { document }
        }

        val categories = mutableListOf<Category>()

        document.select(".owl-carousel .item").map { item ->
            val a = item.selectFirst("a") ?: return@map null
            val link = a.attr("href")
            val img = a.selectFirst("img")
            val bannerUrl = img?.let {
                it.attr("data-src").takeIf { s -> s.isNotEmpty() }
                    ?: it.attr("data-lazy-src").takeIf { s -> s.isNotEmpty() }
                    ?: it.attr("data-original").takeIf { s -> s.isNotEmpty() }
                    ?: it.attr("src")
            }?.toUrl()

            TvShow(
                id = link.substringAfterLast("/").substringBefore(".html"),
                title = a.selectFirst(".title1")?.text() ?: "",
                overview = a.selectFirst(".title0")?.text(),
                banner = bannerUrl
            )
        }.filterNotNull().takeIf { it.isNotEmpty() }?.let { featuredItems ->
            // Enhance banners with TMDB HD backdrops — non-bloquant (timeout 1.5s global)
            if (UserPreferences.enableTmdb) {
                val animeLanguages = setOf("ja", "ko", "zh")
                withTimeoutOrNull(1500) {
                    coroutineScope {
                        featuredItems.map { item ->
                            async {
                                try {
                                    val title = (item as? TvShow)?.title ?: (item as? Movie)?.title ?: return@async
                                    val normalized = com.streamflixreborn.streamflix.utils.TitleNormalizer.cleanForTmdbSearch(title)
                                    val searchQuery = normalized.ifBlank { title }
                                    if (searchQuery.length < 2) return@async
                                    val results = TMDb3.Search.multi(searchQuery, language = "fr-FR")
                                    val match = results.results.firstOrNull { result ->
                                        when (result) {
                                            is TMDb3.Movie -> result.originalLanguage in animeLanguages && result.backdropPath != null && run {
                                                val t = result.title?.trim() ?: ""; t.equals(searchQuery, true) || t.contains(searchQuery, true) || searchQuery.contains(t, true)
                                            }
                                            is TMDb3.Tv -> result.originalLanguage in animeLanguages && result.backdropPath != null && run {
                                                val t = result.name?.trim() ?: ""; t.equals(searchQuery, true) || t.contains(searchQuery, true) || searchQuery.contains(t, true)
                                            }
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
                        }.forEach { it.await() }
                    }
                }
            }
            // Ne garder dans le carrousel que les items avec backdrop TMDb HD (min 5 sinon liste complète)
            val carouselItems = featuredItems.filter { item ->
                val b = when (item) { is Movie -> item.banner; is TvShow -> item.banner; else -> null }
                b != null && b.contains("/t/p/")
            }
            val finalCarousel = if (carouselItems.size >= 5) carouselItems else featuredItems
            if (finalCarousel.isNotEmpty()) {
                categories.add(Category(name = Category.FEATURED, list = finalCarousel))
                // PAS de seenHome pour le FEATURED — c'est un carrousel bannière,
                // les mêmes items peuvent (et doivent) apparaître dans les blocs en dessous.
            }
        }

        document.select(".block-main").forEach { block ->
            val categoryName = block.selectFirst(".block-title .left-ma")?.text() ?: ""
            block.select(".mov").mapNotNull { mov ->
                val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                val link = a.attr("href")
                val id = link.substringAfterLast("/").substringBefore(".html")
                val isFrench = (mov.selectFirst(".block-sai")?.text()
                    ?: mov.selectFirst(".nbloc1")?.text()).isFrench()
                val title = if (categoryName.contains("FILMS", ignoreCase = true))
                    a.text().toTitle(isFrench) else a.text()
                val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl() ?: ""

                val isTvShow = mov.selectFirst(".block-ep") != null
                if (isTvShow) {
                    val seasonText = mov.selectFirst(".block-sai")?.text() ?: ""
                    val seasonNumber = seasonText.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
                    val episodeText = mov.selectFirst(".block-ep")?.text() ?: ""
                    // Handle cases like "Episode 13 et 14 Final" by taking the last number
                    val episodeNumberText = episodeText.split(" ")
                        .lastOrNull { it.toIntOrNull() != null }
                    val episodeNumber = episodeNumberText?.toIntOrNull() ?: 0

                    TvShow(
                        id = id,
                        title = title,
                        poster = poster,
                        seasons = if (seasonNumber > 0 && episodeNumber > 0) {
                            listOf(
                                Season(
                                    id = "",
                                    number = seasonNumber,
                                    episodes = listOf(
                                        Episode(
                                            id = "",
                                            number = episodeNumber
                                        )
                                    )
                                )
                            )
                        } else {
                            emptyList()
                        }
                    )
                } else {
                    Movie(
                        id = id,
                        title = title,
                        poster = poster
                    )
                }
            }.take(20) // Cap 20 items par catégorie — réduit les chargements jaquettes TV
            .takeIf { it.isNotEmpty() }?.let { items ->
                categories.add(Category(name = categoryName, list = items))
            }
        }

        // Retirer "Films" (doublon avec l'onglet Films dédié)
        categories.removeAll { cat ->
            val n = cat.name.lowercase().trim()
            n == "films" || n == "film"
        }

        // 2026-07-04 : genres populaires (lancés en parallèle au début) — comme Wiflix
        // Reorder: 1.FEATURED 2.Épisodes/récents 3.Séries récentes 4.Films récents 5.Séries 6.genres
        val sortedHome = categories.sortedWith(compareBy { cat ->
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
        // 2026-07-03 (opti #3) : écrit le cache disque du home → prochaine ouverture instantanée.
        if (sortedHome.isNotEmpty()) {
            try { com.streamflixreborn.streamflix.utils.HomeCacheStore.write(StreamFlixApp.instance, this, sortedHome) } catch (_: Exception) {}
        }
        return sortedHome
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            val document = safeGetDoc(baseUrl, silentBypass = true) { service.getHome() }

            val genres = document.select("div.side-b nav.side-c ul.flex-row li a").map {
                Genre(
                    id = it.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                    name = it.text()
                )
            }.toMutableList()

            // Ajouter K-Drama (recherche par mots-clés via GenreViewModel)
            if (genres.none { it.id.contains("k-drama", ignoreCase = true) }) {
                genres.add(Genre(id = "k-drama", name = "K-Drama"))
            }

            return genres
        }

        if (page > 1 && !hasMore) return emptyList()

        // Reset hasMore for new searches (page 1)
        if (page == 1) hasMore = true

        android.util.Log.d("FrenchAnime", "search: query='$query' page=$page hasMore=$hasMore")

        // Use manual OkHttp POST instead of Retrofit to properly handle redirects.
        // Retrofit drops the POST body on 301/302 redirects which breaks DLE search.
        val document = searchDirect(query, page)

        android.util.Log.d("FrenchAnime", "search: got document, title='${document.title()}', body=${document.body()?.text()?.take(200)}")

        document.selectFirst("div.berrors")?.let(::checkHasMore)

        val results = document.select("div.mov").mapNotNull { mov ->
            val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
            val link = a.attr("href")
            val id = link.substringAfterLast("/").substringBefore(".html")
            val isFrench = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")
                ?.text()).isFrench()
            val title = a.text().toTitle(isFrench)
            val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()

            val isTvShow = mov.selectFirst(".block-ep") != null
            if (isTvShow) {
                TvShow(
                    id = id,
                    title = title,
                    poster = poster,
                )
            } else {
                Movie(
                    id = id,
                    title = title,
                    poster = poster
                )
            }
        }

        android.util.Log.d("FrenchAnime", "search: found ${results.size} results for '$query'")
        return results
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        // FR tab: must provide BOTH films (isSeries=false) AND series (isSeries=true)
        // The fragment filters by isSeries for the "Serie"/"Film" sub-tabs
        val movies = mutableListOf<Movie>()

        // Films VF/VOSTFR → Movie with isSeries=false (default)
        try {
            safeGetDoc("${baseUrl}films-vf-vostfr/page/$page") { service.getMovies(page) }.select("div.mov.clearfix").mapNotNull { mov ->
                val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                val link = a.attr("href")
                val id = link.substringAfterLast("/").substringBefore(".html")
                val isFrench = (mov.selectFirst(".block-sai")?.text()
                    ?: mov.selectFirst(".nbloc1")?.text()).isFrench()
                if (!isFrench) return@mapNotNull null
                val title = a.text().toTitle(true)
                val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
                Movie(id = id, title = title, poster = poster)
            }.let { movies.addAll(it) }
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }

        // Animes VF → Movie with isSeries=true
        try {
            safeGetDoc("${baseUrl}animes-vf/page/$page/") { service.getTvSeries("animes-vf/page/$page/") }.select("div.mov.clearfix").mapNotNull { mov ->
                val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                val link = a.attr("href")
                val id = link.substringAfterLast("/").substringBefore(".html")
                val isFrench = (mov.selectFirst(".block-sai")?.text()
                    ?: mov.selectFirst(".nbloc1")?.text()).isFrench()
                val title = a.text().toTitle(isFrench)
                val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
                Movie(id = id, title = title, poster = poster).also { it.isSeries = true }
            }.let { movies.addAll(it) }
        } catch (_: Exception) {}

        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        // VOSTFR tab: must provide BOTH series (isMovie=false) AND films (isMovie=true)
        // The fragment filters by isMovie for the "Serie"/"Film" sub-tabs
        val tvShows = mutableListOf<TvShow>()

        // Animes VOSTFR → TvShow with isMovie=false (default)
        try {
            safeGetDoc("${baseUrl}animes-vostfr/page/$page/") { service.getTvSeries("animes-vostfr/page/$page/") }.select("div.mov.clearfix").mapNotNull { mov ->
                val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                val link = a.attr("href")
                val id = link.substringAfterLast("/").substringBefore(".html")
                val isFrench = (mov.selectFirst(".block-sai")?.text()
                    ?: mov.selectFirst(".nbloc1")?.text()).isFrench()
                val title = a.text().toTitle(isFrench)
                val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
                TvShow(id = id, title = title, poster = poster)
            }.let { tvShows.addAll(it) }
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }

        // Films VOSTFR → TvShow with isMovie=true (VOSTFR tab shows VOSTFR films only)
        try {
            safeGetDoc("${baseUrl}films-vf-vostfr/page/$page") { service.getMovies(page) }.select("div.mov.clearfix").mapNotNull { mov ->
                val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                val link = a.attr("href")
                val id = link.substringAfterLast("/").substringBefore(".html")
                val isFrench = (mov.selectFirst(".block-sai")?.text()
                    ?: mov.selectFirst(".nbloc1")?.text()).isFrench()
                if (isFrench) return@mapNotNull null // Skip FR films, keep VOSTFR
                val title = a.text().toTitle(false)
                val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
                TvShow(id = id, title = title, poster = poster).also { it.isMovie = true }
            }.let { tvShows.addAll(it) }
        } catch (_: Exception) {}

        return tvShows
    }

    override suspend fun getMovie(id: String): Movie = withContext(Dispatchers.IO) {
        // Lancer page + recherche en parallèle pour réduire la latence
        val documentDeferred = async { safeGetDoc("${baseUrl}films-vf-vostfr/$id.html") { service.getMovie(id) } }

        // On a besoin du titre pour la recherche, donc on attend le document d'abord
        val document = documentDeferred.await()

        val isFrench = document.selectFirst("ul.mov-list li:contains(Version) .mov-desc span")
            ?.text().isFrench()

        val rawTitle = document.selectFirst("header.full-title h1")?.text() ?: ""
        val title = rawTitle.toTitle(isFrench)

        // ── Search for similar/related films (async) ──
        val baseTitle = rawTitle
            .replace(Regex("""(?i)\s*(film|movie|le film|the movie).*"""), "")
            .replace("FRENCH", "").replace("VOSTFR", "")
            .trim()

        val franchiseName = baseTitle
            .split(Regex("""\s*[:]\s*|\s+[-–—]\s+"""))
            .firstOrNull()?.trim()
            ?.takeIf { it.length >= 3 } ?: baseTitle

        val movieSearchQuery = franchiseName
            .replace(Regex("""[:\-–—·''""\[\]()!?,;]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

        // Lancer la recherche de recommandations en parallèle du parsing
        val relatedDeferred = async {
            if (movieSearchQuery.length < 3) return@async emptyList<Show>()
            try {
                val searchDoc = searchDirect(movieSearchQuery, 1)
                val searchBase = franchiseName.normalizeForCompare()
                searchDoc.select("div.mov").mapNotNull { mov ->
                    val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
                    val link = a.attr("href")
                    val resultId = link.substringAfterLast("/").substringBefore(".html")
                    if (resultId == id) return@mapNotNull null
                    val resultTitle = a.text()
                    val resultPoster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()

                    val resultBase = resultTitle
                        .replace(Regex("""(?i)\s*(film|movie|le film|the movie|saison).*"""), "")
                        .replace("FRENCH", "").replace("VOSTFR", "")
                        .normalizeForCompare()
                    val resultFranchise = resultBase
                        .split(Regex("""\s*[:]\s*|\s+[-–—]\s+"""))
                        .firstOrNull()?.trim() ?: resultBase

                    if (titlesFuzzyMatch(resultBase, searchBase) || titlesFuzzyMatch(resultFranchise, searchBase)) {
                        Movie(id = resultId, title = resultTitle.toTitle(isFrench), poster = resultPoster)
                    } else null
                }
            } catch (_: Exception) { emptyList() }
        }

        val relatedFilms = relatedDeferred.await()

        Movie(
            id = id,
            title = title,
            overview = document.selectFirst("span[itemprop='description']")?.text() ?: "",
            released = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "Date de sortie:" }
                ?.selectFirst("div.mov-desc")?.text()?.substringBefore(" to"),
            runtime = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "Durée:" }
                ?.selectFirst("div.mov-desc")?.text()?.extractRuntime(),
            poster = document.selectFirst("div.mov-img img[itemprop='thumbnailUrl']")
                ?.attr("src")?.toUrl(),
            genres = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "GENRE:" }
                ?.select("span[itemprop='genre'] a")?.map {
                    Genre(
                        id = it.attr("href").substringAfter("/genre/").substringBefore("/"),
                        name = it.text(),
                    )
                } ?: listOf(),
            directors = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "RÉALISATEUR:" }
                ?.selectFirst("div.mov-desc span[itemprop='name']")?.text()
                .toPeople(),
            cast = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "ACTEURS:" }
                ?.selectFirst("div.mov-desc span[itemprop='name']")?.text()
                .toPeople(),
            recommendations = relatedFilms,
        )
    }

    override suspend fun getTvShow(id: String): TvShow = withContext(Dispatchers.IO) {
        val document = safeGetDoc("${baseUrl}$id.html") { service.getTvShow(id) }

        val isFrench = document.selectFirst("ul.mov-list li:contains(Version) .mov-desc span")
            ?.text().isFrench()

        val rawTitle = document.selectFirst("h1[itemprop=name]")?.text() ?: ""
        val title = rawTitle.toTitle(isFrench)
        val poster = document.selectFirst("div.mov-img img[itemprop=thumbnailUrl]")?.attr("src")
            ?.toUrl()

        val baseTitle = rawTitle
            .replace(Regex("""[Ss]aison\s*\d+"""), "")
            .replace("FRENCH", "").replace("VOSTFR", "")
            .trim()

        val searchQuery = baseTitle
            .replace(Regex("""[:\-–—·''""\[\]()!?,;]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // ── Recherche saisons + recommandations en parallèle ──
        val franchiseName = baseTitle
            .split(Regex("""\s*[:]\s*|\s+[-–—]\s+"""))
            .firstOrNull()?.trim()
            ?.takeIf { it.length >= 3 } ?: baseTitle
        val recoQuery = franchiseName
            .replace(Regex("""[:\-–—·''""\[\]()!?,;]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

        // UNE SEULE recherche — saisons + recos extraites du même résultat
        // (la query saisons et recos est quasi identique, inutile de doubler)
        val seasonsAndRecos = async {
            val seasons = mutableListOf<Season>()
            val recommendations = mutableListOf<Show>()
            if (searchQuery.isEmpty()) return@async Pair(seasons, recommendations)

            try {
                var searchBase = baseTitle.normalizeForCompare()
                val currentSlug = id.substringAfterLast("/")
                val seenIds = mutableSetOf<String>()
                android.util.Log.d("FrenchAnime", "getTvShow: id=$id currentSlug=$currentSlug searchQuery='$searchQuery' searchBase='$searchBase'")

                // ── Phase 1 : collecter TOUS les résultats de recherche (multi-pages) ──
                data class SearchHit(
                    val resultId: String, val resultTitle: String, val resultBase: String,
                    val resultPoster: String?, val isTvShow: Boolean, val seasonNumber: Int
                )
                val allHits = mutableListOf<SearchHit>()

                for (searchPage in 1..5) {
                    val searchDoc = searchDirect(searchQuery, searchPage)
                    val movItems = searchDoc.select("div.mov")
                    if (movItems.isEmpty()) break
                    var newOnPage = 0

                    movItems.forEach { mov ->
                        val a = mov.selectFirst("a.mov-t") ?: return@forEach
                        val link = a.attr("href")
                        val resultId = link.substringAfterLast("/").substringBefore(".html")
                        if (!seenIds.add(resultId)) return@forEach
                        newOnPage++
                        val resultTitle = a.text()
                        val resultPoster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()
                        val isTvShow = mov.selectFirst(".block-ep") != null

                        val resultBase = resultTitle
                            .replace(Regex("""[Ss]aison\s*\d+"""), "")
                            .replace("FRENCH", "").replace("VOSTFR", "")
                            .normalizeForCompare()

                        val saisonText = mov.selectFirst(".block-sai")?.text() ?: ""
                        val seasonNumber = Regex("""[Ss]aison\s*(\d+)""").find(saisonText)
                            ?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("""[Ss]aison\s*(\d+)""").find(resultTitle)
                                ?.groupValues?.get(1)?.toIntOrNull()
                            ?: 1

                        allHits.add(SearchHit(resultId, resultTitle, resultBase, resultPoster, isTvShow, seasonNumber))
                        android.util.Log.d("FrenchAnime", "  hit: id=$resultId isTvShow=$isTvShow s=$seasonNumber base='$resultBase' title='$resultTitle'")
                    }
                    if (newOnPage == 0) break
                }
                android.util.Log.d("FrenchAnime", "Phase1: ${allHits.size} hits total")

                // ── Phase 2 : auto-corriger le titre de référence ──
                // Si notre propre page est dans les résultats sous un titre DIFFÉRENT (ex: titre japonais
                // alors que le h1 est en anglais), on utilise le titre du résultat de recherche.
                val selfHit = allHits.find { it.resultId == currentSlug }
                if (selfHit != null && selfHit.resultBase != searchBase) {
                    searchBase = selfHit.resultBase
                }

                // Aussi identifier le titre le plus fréquent dans les résultats (= le cluster principal)
                val titleClusters = allHits.filter { it.isTvShow }
                    .groupBy { it.resultBase }
                val largestCluster = titleClusters.maxByOrNull { it.value.size }
                // Si le plus gros cluster ne matche pas searchBase mais contient notre page → l'adopter
                if (largestCluster != null && largestCluster.key != searchBase
                    && largestCluster.value.any { it.resultId == currentSlug }) {
                    searchBase = largestCluster.key
                }

                android.util.Log.d("FrenchAnime", "Phase2: searchBase='$searchBase' selfHit=${selfHit?.resultId} largestCluster=${largestCluster?.key}(${largestCluster?.value?.size})")

                // ── Phase 3 : extraire saisons + recommandations ──
                allHits.forEach { hit ->
                    if (hit.isTvShow) {
                        val matched = titlesFuzzyMatch(hit.resultBase, searchBase)
                        android.util.Log.d("FrenchAnime", "  Phase3: '${hit.resultBase}' vs '$searchBase' → match=$matched")
                        if (matched) {
                            seasons.add(Season(
                                id = hit.resultId,
                                number = hit.seasonNumber,
                                title = "Saison ${hit.seasonNumber}",
                                poster = hit.resultPoster ?: poster,
                            ))
                        }
                    }
                    // Recommandations (films et séries liés, excluant self)
                    if (hit.resultId != id && hit.resultId != currentSlug) {
                        val resultFranchise = hit.resultBase
                            .replace(Regex("""(?i)\s*(film|movie|le film|the movie|saison).*"""), "")
                            .normalizeForCompare()
                            .split(Regex("""\s*[:]\s*|\s+[-–—]\s+"""))
                            .firstOrNull()?.trim() ?: hit.resultBase
                        val recoBase = franchiseName.normalizeForCompare()

                        if (titlesFuzzyMatch(hit.resultBase, recoBase) || titlesFuzzyMatch(resultFranchise, recoBase)) {
                            recommendations.add(
                                TvShow(id = hit.resultId, title = hit.resultTitle.toTitle(isFrench), poster = hit.resultPoster)
                            )
                        }
                    }
                }

                // Si aucune saison trouvée par fuzzy match, mais le plus gros cluster de résultats
                // a ≥2 entrées avec des saisons différentes → probablement notre anime sous un titre alternatif
                if (seasons.isEmpty() && largestCluster != null && largestCluster.value.size >= 2) {
                    val distinctSeasons = largestCluster.value.map { it.seasonNumber }.distinct()
                    if (distinctSeasons.size >= 1) {
                        largestCluster.value.forEach { hit ->
                            seasons.add(Season(
                                id = hit.resultId,
                                number = hit.seasonNumber,
                                title = "Saison ${hit.seasonNumber}",
                                poster = hit.resultPoster ?: poster,
                            ))
                        }
                    }
                }

                // Dedup + sort
                val deduped = seasons.distinctBy { it.number }.sortedBy { it.number }
                android.util.Log.d("FrenchAnime", "Final: ${deduped.size} seasons (before dedup: ${seasons.size})")
                seasons.clear()
                seasons.addAll(deduped)
            } catch (e: Exception) {
                android.util.Log.e("FrenchAnime", "getTvShow search error", e)
            }

            Pair(seasons, recommendations)
        }

        val (seasons, recommendations) = seasonsAndRecos.await()

        // Fallback: if search found nothing, use current page as single season
        if (seasons.isEmpty()) {
            val seasonNum = Regex("""[Ss]aison\s*(\d+)""").find(rawTitle)?.groupValues?.get(1)
                ?.toIntOrNull() ?: 1
            seasons.add(Season(
                id = id,
                number = seasonNum,
                title = "Saison $seasonNum",
                poster = poster,
            ))
        }

        TvShow(
            id = id,
            title = title,
            overview = document.selectFirst("span[itemprop=description]")?.text(),
            released = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "Date de sortie:" }
                ?.selectFirst("div.mov-desc")?.text()?.substringBefore(" to"),
            runtime = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "Durée:" }
                ?.selectFirst("div.mov-desc")?.text()?.extractRuntime(),
            poster = poster,
            seasons = seasons,
            genres = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "GENRE:" }
                ?.select("span[itemprop='genre'] a")?.map {
                    Genre(
                        id = it.attr("href").substringAfter("/genre/").substringBefore("/"),
                        name = it.text(),
                    )
                } ?: listOf(),
            directors = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "RÉALISATEUR:" }
                ?.selectFirst("div.mov-desc span[itemprop='name']")?.text()
                .toPeople(),
            cast = document.select("ul.mov-list li")
                .find { it.selectFirst("div.mov-label")?.text() == "ACTEURS:" }
                ?.selectFirst("div.mov-desc span[itemprop='name']")?.text()
                .toPeople(),
            recommendations = recommendations,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val document = safeGetDoc("${baseUrl}$seasonId.html") { service.getTvShow(seasonId) }

        // Get poster for episodes
        val episodePoster = document.selectFirst("div.mov-img img[itemprop=thumbnailUrl]")
            ?.attr("src")?.toUrl()

        val epsText = document.selectFirst("div.eps")?.text() ?: return emptyList()
        val episodeLines = epsText.split(" ")
        val episodes = episodeLines.mapNotNull { line ->
            val parts = line.split("!")
            if (parts.size == 2) {
                val episodeNumber = parts[0].toIntOrNull() ?: return@mapNotNull null
                Episode(
                    id = "${seasonId}_${episodeNumber}",
                    number = episodeNumber,
                    poster = episodePoster,
                )
            } else {
                null
            }
        }

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val document = try {
            safeGetDoc("${baseUrl}genre/$id/page/$page") { service.getGenre(id, page) }
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> return Genre(id, "")
                else -> throw e
            }
        }

        val genreName = document.title().substringBefore(" - French Anime").substringBefore(" »")

        val shows = document.select("div.mov").mapNotNull { mov ->
            val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
            val link = a.attr("href")
            val itemId = link.substringAfterLast("/").substringBefore(".html")
            val isFrench = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")
                ?.text()).isFrench()
            val title = a.text().toTitle(isFrench)
            val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()

            val isTvShow = mov.selectFirst(".block-ep") != null
            if (isTvShow) {
                TvShow(
                    id = itemId,
                    title = title,
                    poster = poster
                )
            } else {
                Movie(
                    id = itemId,
                    title = title,
                    poster = poster
                )
            }
        }

        return Genre(id = id, name = genreName, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1 && !hasMore) return People(id = id, name = id)

        val document = searchDirect(id, page)

        document.selectFirst("div.berrors")?.let(::checkHasMore)

        val filmography = document.select("div.mov").mapNotNull { mov ->
            val a = mov.selectFirst("a.mov-t") ?: return@mapNotNull null
            val link = a.attr("href")
            val itemId = link.substringAfterLast("/").substringBefore(".html")
            val isFrench = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")
                ?.text()).isFrench()
            val title = a.text().toTitle(isFrench)
            val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl()

            val isTvShow = mov.selectFirst(".block-ep") != null
            if (isTvShow) {
                TvShow(
                    id = itemId,
                    title = title,
                    poster = poster,
                )
            } else {
                Movie(
                    id = itemId,
                    title = title,
                    poster = poster
                )
            }
        }

        return People(id = id, name = id, filmography = filmography)
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val episodeId = when (videoType) {
            is Video.Type.Movie -> id
            is Video.Type.Episode -> id.substringBeforeLast("_")
        }

        val document = safeGetDoc("${baseUrl}$episodeId.html") { service.getTvShow(episodeId) }

        val epsText = document.selectFirst("div.eps")?.text() ?: return emptyList()
        val episodeLines = epsText.split(" ")
        val idSuffix = id.substringAfterLast("_")
        val servers = episodeLines.firstNotNullOfOrNull { line ->
            val parts = line.split("!")
            if (parts.size == 2 && (parts[0] == idSuffix || videoType is Video.Type.Movie)) {
                parts[1].split(",").filter { it.isNotEmpty() && it.startsWith("http") }
                    .mapIndexed { index, source ->
                        Video.Server(
                            id = index.toString(),
                            name = source.extractUrlDomain(),
                            src = source
                        )
                    }
            } else {
                null
            }
        } ?: emptyList()

        android.util.Log.d("FrenchAnime", "getServers: found ${servers.size} servers")
        servers.forEach { android.util.Log.d("FrenchAnime", "  server: name=${it.name}, src=${it.src}") }

        // Sort by service reliability
        val sorted = servers.sortedBy { server ->
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
        }

        // 2026-07-04 : backups inline DÉSACTIVÉS → registre central (BackupRegistry.fetchAll
        //   dans PlayerViewModel via ProgressiveServersProvider). Les backups Cloudstream/
        //   Moviebox/Papadustream sont maintenant gérés par le registre, plus besoin ici.
        return sorted
    }

    // ── Chargement PROGRESSIF (2026-07-04) ─────────────────────────────
    //   Natifs émis tout de suite → le registre central (BackupRegistry.fetchAll
    //   dans PlayerViewModel) ajoute les backups au fil de l'eau.
    override fun getServersProgressive(id: String, videoType: Video.Type): Flow<List<Video.Server>> = channelFlow {
        // 2026-07-04 : on garde withContext(IO) ici — le fix dispatcher est
        //   centralisé dans PlayerViewModel via .flowOn(IO) sur le nativeFlow.
        try {
            val servers = withContext(Dispatchers.IO) { getServers(id, videoType) }
            if (servers.isNotEmpty()) send(servers)
        } catch (e: Exception) { Log.w("FrenchAnime", "progressive native KO: ${e.message}") }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        android.util.Log.d("FrenchAnime", "getVideo: server=${server.name}, src=${server.src}")
        val video = try {
            Extractor.extract(server.src)
        } catch (e: Exception) {
            android.util.Log.e("FrenchAnime", "getVideo FAILED for ${server.name}: ${e.message}")
            throw e
        }
        android.util.Log.d("FrenchAnime", "getVideo result: ${video.source}")
        return video
    }

    private suspend fun fetchTvShows(path: String): List<TvShow> {
        return try {
            safeGetDoc("${baseUrl}$path") { service.getTvSeries(path) }.let { document ->
                document.select("div.mov.clearfix").mapNotNull { extractTvShows(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractTvShows(mov: Element): TvShow? {
        val a = mov.selectFirst("a.mov-t") ?: return null
        val link = a.attr("href")
        val id = link.substringAfterLast("/").substringBefore(".html")
        val isFrench = (mov.selectFirst(".block-sai")?.text() ?: mov.selectFirst(".nbloc1")
            ?.text()).isFrench()
        val title = a.text().toTitle(isFrench)
        val poster = mov.selectFirst(".mov-i img")?.attr("src")?.toUrl() ?: ""
        val episodeText = mov.selectFirst(".block-ep")?.text() ?: return null
        val episodeNumberText = episodeText.split(" ").lastOrNull { it.toIntOrNull() != null }
        val episodeNumber = episodeNumberText?.toIntOrNull() ?: 0
        val seasonText = mov.selectFirst(".block-sai")?.text() ?: ""
        val seasonNumber = seasonText.split(" ").getOrNull(1)?.toIntOrNull() ?: 0

        return TvShow(
            id = id,
            title = title,
            poster = poster,
            seasons = if (seasonNumber > 0 && episodeNumber > 0) {
                listOf(
                    Season(
                        id = "",
                        number = seasonNumber,
                        episodes = listOf(
                            Episode(
                                id = "",
                                number = episodeNumber
                            )
                        )
                    )
                )
            } else {
                emptyList()
            }
        )
    }

    private fun checkHasMore(berrorsDiv: Element) {
        val resultText = berrorsDiv.text()
        val totalResults = resultText.substringAfter("Trouvé ")
            .substringBefore(" réponses").toIntOrNull() ?: 0
        val currentRange = resultText.substringAfter("Résultats de la requête ")
            .substringBefore(")").split(" - ")
        val receivedItems = currentRange.getOrNull(1)?.toIntOrNull() ?: 0

        hasMore = receivedItems < totalResults
    }

    private fun String.toUrl(): String = if (startsWith("/")) baseUrl.dropLast(1).plus(this) else this

    private fun String?.isFrench(): Boolean = this?.contains("FRENCH", ignoreCase = true) ?: false

    private fun String.toTitle(isFrench: Boolean): String = if (isFrench) {
        "(FR) $this".replace(" FRENCH", "")
    } else {
        this.replace(" VOSTFR", "")
    }

    private fun String?.toPeople(): List<People> {
        return this?.split(", ")
            ?.map { it.replace(("[^\\p{L}\\d ]").toRegex(), " ").trim() }
            ?.filter { it.isNotEmpty() }
            ?.map {
                People(
                    id = it,
                    name = it,
                    image = "",
                )
            } ?: listOf()
    }

    /** Normalize a title for fuzzy comparison: N°→No , remove punctuation, collapse spaces, lowercase */
    private fun String.normalizeForCompare(): String = this
        .replace("N°", "No ")
        .replace("n°", "no ")
        .replace(Regex("""[.:·\-–—_''""\[\]()!?,;]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .lowercase()

    /** Check if two normalized titles refer to the same franchise.
     *  Handles sequels like "Title 2", "Title 3" matching "Title". */
    private fun titlesFuzzyMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length > b.length) a else b
        // "tate no yuusha" matches "tate no yuusha 2" (word boundary after shorter)
        return longer.startsWith(shorter) && longer.length > shorter.length
                && longer[shorter.length] == ' '
    }

    private fun String.extractUrlDomain(): String {
        return URL_DOMAIN_REGEX.find(this)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() }
            ?: this
    }

    private fun String.extractRuntime() = when {
        contains("h") -> {
            val hours = substringBefore("h").toIntOrNull() ?: 0
            val minutes = substringAfter("h ").substringBefore("min").toIntOrNull() ?: 0
            hours * 60 + minutes
        }
        contains("min") -> substringBefore(" min").toIntOrNull()
        contains("mn") -> substringBefore(" mn").toIntOrNull()
        else -> null
    }

    /**
     * Direct OkHttp search that properly preserves POST body across redirects.
     * DLE (DataLife Engine) sites use POST for search, but when the domain
     * redirects (301/302), OkHttp and Retrofit downgrade POST→GET losing the
     * form body.  This method manually follows redirects while keeping POST.
     */
    private suspend fun searchDirect(query: String, page: Int): org.jsoup.nodes.Document {
        // Utiliser GET via getDocument (= bypass CF WebView intégré)
        // DLE supporte la recherche en GET : /index.php?do=search&subaction=search&story=...
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrl = buildString {
            append("${baseUrl}index.php?do=search&subaction=search&story=$encodedQuery")
            if (page > 1) {
                append("&search_start=$page")
                append("&result_from=${(page - 1) * SEARCH_PAGE_SIZE + 1}")
            }
            append("&full_search=0")
        }
        android.util.Log.d("FrenchAnime", "searchDirect: url=$searchUrl")
        return getDocument(searchUrl, silentBypass = true)
    }

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            _service = FrenchAnimeService.build()
        }
        return baseUrl
    }

    private interface FrenchAnimeService {
        companion object {
            fun build(): FrenchAnimeService {
                // 2026-07-03 (réplique du pattern Wiflix qui charge INSTANTANÉ) : le client
                //   part de NetworkClient.default → hérite du cookieJar PARTAGÉ (pont WebView↔
                //   OkHttp). Le cf_clearance posé par le bypass se propage alors automatiquement
                //   à OkHttp ET à Glide (mêmes cookies) → les jaquettes CF chargent direct,
                //   comme Wiflix. Timeouts courts (10/8s) alignés sur Wiflix (au lieu de 30s)
                //   pour ne pas bloquer sur un fetch mort. DoH déjà porté par NetworkClient.default.
                val client = com.streamflixreborn.streamflix.utils.NetworkClient.default.newBuilder()
                    .readTimeout(10, TimeUnit.SECONDS)
                    .connectTimeout(8, TimeUnit.SECONDS)
                    // Preserve POST method & body on redirects (301/302/303).
                    // Without this, OkHttp downgrades POST→GET and drops form
                    // data, which breaks the DLE search endpoint when the site
                    // redirects to a new domain.
                    .followRedirects(false)
                    // 2026-07-03 (opti #1 — user "un truc plus rapide") : le client Retrofit
                    //   n'embarquait NI le cf_clearance NI le STEALTH_UA → CHAQUE fetch se
                    //   prenait un 403 CF et repassait par le bypass WebView (tempête de 403,
                    //   throttle, lenteur). On injecte le MÊME pattern que tryOkHttp : STEALTH_UA
                    //   + cookie cf_clearance (lu du CookieManager). Après UN seul bypass, toutes
                    //   les pages passent direct en OkHttp → chargement bien plus rapide.
                    .addInterceptor { chain ->
                        val orig = chain.request()
                        val cookie = try {
                            android.webkit.CookieManager.getInstance().getCookie(orig.url.toString())
                        } catch (_: Throwable) { null }
                        val b = orig.newBuilder()
                            .header("User-Agent", WebViewResolver.STEALTH_UA)
                            .header("Referer", "https://french-anime.com/")
                        if (!cookie.isNullOrBlank()) b.header("Cookie", cookie)
                        chain.proceed(b.build())
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

                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(FrenchAnimeService::class.java)
            }
        }

        @GET(".")
        suspend fun getHome(): Document

        @POST(".")
        @FormUrlEncoded
        suspend fun search(
            @Field("do") doAction: String = "search",
            @Field("subaction") subAction: String = "search",
            @Field("story") query: String,
            @Field("search_start") searchStart: Int = -1,
            @Field("result_from") resultFrom: Int = -1,
            @Field("full_search") fullSearch: Int = 0,
//            @Field("titleonly") titleOnly: Int = 3, // Narrow to title search
        ): Document

        @GET("films-vf-vostfr/page/{page}")
        suspend fun getMovies(@Path("page") page: Int): Document

        @GET("{path}")
        suspend fun getTvSeries(@Path("path") path: String): Document

        @GET("films-vf-vostfr/{id}.html")
        suspend fun getMovie(@Path("id") id: String): Document

        @GET("{id}.html")
        suspend fun getTvShow(@Path("id") id: String): Document

        @GET("genre/{genre}/page/{page}")
        suspend fun getGenre(
            @Path("genre") genre: String,
            @Path("page") page: Int
        ): Document

        @POST(".")
        @FormUrlEncoded
        suspend fun getPeople(
            @Field("do") doAction: String = "search",
            @Field("subaction") subAction: String = "search",
            @Field("story") query: String,
            @Field("search_start") searchStart: Int = -1,
            @Field("result_from") resultFrom: Int = -1,
            @Field("full_search") fullSearch: Int = 0
        ): Document
    }
}
