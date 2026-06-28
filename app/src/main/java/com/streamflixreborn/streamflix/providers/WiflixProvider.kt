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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    // 2026-06-23 (user "Wiflix ne relance pas son captcha quand cookie périme,
    //   c'est juste un bug de cash") : quand le silent bypass timeout (= 30s
    //   sans résolution Turnstile), on escalade vers le captcha VISIBLE qui
    //   ouvre une WebView avec le Turnstile résolvable manuellement par l'user.
    //   Cooldown 60s pour éviter d'ouvrir 8 captchas si 8 catégories home
    //   échouent en parallèle.
    @Volatile
    private var lastVisibleBypassAt = 0L
    private const val VISIBLE_BYPASS_COOLDOWN_MS = 60_000L

    // 2026-06-23 (user "Error 1015 You are being rate limited") : Cloudflare
    //   ban temporaire si trop de requêtes simultanées. Avant : home faisait
    //   ~11 requêtes parallèles (1 home + 2 listings + 5 genres + 8 categories)
    //   = burst → CF rate-limit kick in. Maintenant : Semaphore(3) limite à
    //   3 requêtes simultanées max = pas de burst → CF content. Petit délai
    //   au prix d'une latence légèrement plus longue mais zéro ban.
    private val networkSemaphore = Semaphore(3)

    // Cooldown si on détecte un Error 1015 dans la réponse → on attend 15 min
    //   avant de retenter, sinon on aggrave le ban CF.
    @Volatile
    private var rateLimit1015Until = 0L
    private const val RATE_LIMIT_COOLDOWN_MS = 15 * 60 * 1000L  // 15 minutes

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
     *
     * 2026-06-22 fix (user "il oublie de relancer le scrap après") :
     *   - Après bypass WebView, retry OkHttp avec le cookie frais
     *   - Ne PAS cacher les documents vides/challenge (sinon cache pourri 5 min)
     */
    private suspend fun getDocument(url: String, silentBypass: Boolean = true): Document = networkSemaphore.withPermit {
        // Check cache first
        getCachedDocument(url)?.let {
            Log.d(TAG, "[Provider] Cache HIT for $url")
            return@withPermit it
        }

        // 2026-06-23 : si on a détecté un Error 1015 récemment, on n'envoie
        //   plus aucune requête pendant le cooldown 15min pour pas aggraver le ban.
        val now = System.currentTimeMillis()
        if (now < rateLimit1015Until) {
            val left = (rateLimit1015Until - now) / 1000
            Log.w(TAG, "[Provider] Rate limit cooldown active (${left}s left) — skipping $url")
            return@withPermit Jsoup.parse("<html><!-- rate limited 1015 --></html>").apply { setBaseUri(baseUrl) }
        }

        // Tentative 1 : OkHttp direct (cookie CF déjà en place ?)
        val okHttpDoc = tryOkHttp(url)
        if (okHttpDoc != null) return@withPermit okHttpDoc

        // Tentative 2 : WebView bypass (Turnstile)
        Log.d(TAG, "[Provider] Launching WebView Bypass for $url (silent=$silentBypass)")
        var html = getResolver().get(url, silent = silentBypass)

        // 2026-06-23 (user "captcha apparaît sans demander de captcha, 1 clic
        //   fait disparaître") : avant on détectait "Timeout" qui matchait
        //   du contenu page légitime (= ex titre série "Timeout") → escalade
        //   inutile vers visible. Maintenant on cherche les markers HTML
        //   COMMENT exacts générés par WebViewResolver (lignes 170, 212) :
        //     "<html><!-- silent fail --></html>"
        //     "<html><!-- no live activity --></html>"
        //   ces strings n'apparaissent JAMAIS dans une page Wiflix normale.
        // 2026-06-28 : détection enrichie — si le HTML retourné contient
        //   encore des marqueurs de challenge (Turnstile, Bot shield, etc.)
        //   c'est un échec du bypass, PAS un succès. Avant : seuls les
        //   markers <!-- silent fail --> étaient détectés → le HTML du
        //   challenge se faisait cacher 5 min et empoisonnait tout.
        fun isBypassFailed(h: String): Boolean {
            if (h.contains("<!-- silent fail -->") ||
                h.contains("<!-- no live activity -->") ||
                h.contains("<!-- rate limited 1015 -->") ||
                h.contains("User cancelled")) return true
            // Le HTML retourné est un challenge CF ou Bot shield non résolu
            val looksLikeChallenge = challengeKeywords.any { h.contains(it, ignoreCase = true) }
            val hasRealContent = h.contains("mov-t") || h.contains("posterimg") ||
                h.contains("mov-list") || h.contains("href=\"/film/") ||
                h.contains("href=\"/serie/")
            // Challenge détecté SANS contenu réel = bypass raté
            return looksLikeChallenge && !hasRealContent
        }
        var bypassFailed = isBypassFailed(html)
        if (bypassFailed && silentBypass) {
            val nowEscalate = System.currentTimeMillis()
            if (nowEscalate - lastVisibleBypassAt > VISIBLE_BYPASS_COOLDOWN_MS) {
                lastVisibleBypassAt = nowEscalate
                Log.d(TAG, "[Provider] Silent fail → ESCALATING to visible captcha for $url")
                html = getResolver().get(url, silent = false)
                bypassFailed = isBypassFailed(html)
                if (!bypassFailed) {
                    Log.d(TAG, "[Provider] Visible captcha SUCCESS for $url")
                }
            } else {
                Log.d(TAG, "[Provider] Visible bypass cooldown active (${(VISIBLE_BYPASS_COOLDOWN_MS - (nowEscalate - lastVisibleBypassAt)) / 1000}s left) — skipping for $url")
            }
        }

        // 2026-06-23 : détection Error 1015 (rate-limit Cloudflare) → ouvre
        //   cooldown 15min global pour ne plus envoyer aucune requête le temps
        //   que CF déban l'IP.
        if (html.contains("Error 1015") || html.contains("You are being rate limited")) {
            rateLimit1015Until = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
            Log.w(TAG, "[Provider] CLOUDFLARE 1015 detected for $url — cooldown 15min activated")
        }

        if (bypassFailed) {
            Log.d(TAG, "[Provider] WebView bypass failed for $url — NOT caching")
            cloudflareActive = true
            return@withPermit Jsoup.parse(html).apply { setBaseUri(baseUrl) }
        }

        // Bypass WebView a réussi → le cookie cf_clearance est dans le CookieManager.
        // RELANCER via OkHttp pour un HTML propre du serveur.
        Log.d(TAG, "[Provider] WebView bypass succeeded — retrying OkHttp with fresh cookie")
        cloudflareActive = false
        val freshDoc = tryOkHttp(url)
        if (freshDoc != null) {
            Log.d(TAG, "[Provider] OkHttp retry after bypass SUCCESS for $url")
            return@withPermit freshDoc
        }

        // Fallback : utiliser le HTML du WebView
        Log.d(TAG, "[Provider] OkHttp retry after bypass FAILED — using WebView HTML")
        val doc = Jsoup.parse(html).apply { setBaseUri(baseUrl) }
        // 2026-06-28 : ne PAS cacher le fallback si c'est du challenge résiduel
        //   (= empoisonnement cache 5 min → tout bloque, obligé de kill l'app)
        if (!isCloudflareChallenge(doc) && !html.contains("Bot shield active", ignoreCase = true)) {
            cacheDocument(url, doc)
            populateUrlCache(doc)
        } else {
            Log.w(TAG, "[Provider] Fallback HTML is still a challenge — NOT caching for $url")
        }
        return@withPermit doc
    }

    /** Tente un fetch OkHttp. Retourne le Document si succès + pas de challenge, null sinon. */
    private fun tryOkHttp(url: String): Document? {
        return try {
            // 2026-06-28 : MÊME UA que le WebView stealth (Chrome 131).
            //   Le cookie cf_clearance est lié au UA : s'il a été posé par
            //   le WebView avec Chrome/131 et qu'OkHttp envoie Chrome/116,
            //   Cloudflare rejette → HTML retourné = challenge → fallback
            //   WebView HTML avec \t dans les titres + images bloquées.
            val request = Request.Builder()
                .url(url)
                .header("Referer", baseUrl)
                .header("User-Agent", WebViewResolver.STEALTH_UA)
                .build()
            val response = bypassClient.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                if (challengeKeywords.none { html.contains(it, ignoreCase = true) }) {
                    val doc = Jsoup.parse(html).apply { setBaseUri(baseUrl) }
                    cacheDocument(url, doc)
                    populateUrlCache(doc)
                    cloudflareActive = false
                    doc
                } else {
                    Log.d(TAG, "[Provider] Cloudflare challenge in HTML for $url")
                    cloudflareActive = true
                    null
                }
            } else {
                Log.d(TAG, "[Provider] HTTP ${response.code} for $url")
                cloudflareActive = true
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "[Provider] OkHttp failed for $url: ${e.message}")
            null
        }
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

    override suspend fun getHome(): List<Category> = coroutineScope {
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

        var document = try {
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
                return@coroutineScope emptyList()
            }
        }

        // 2026-06-22 fix (user "il oublie de relancer le scrap après") :
        // Si le document est vide (challenge/timeout), retry via OkHttp.
        // Le bypass WebView a pu résoudre le Turnstile → cookie cf_clearance
        // est maintenant dans le CookieManager → OkHttp devrait passer.
        if (document.select("div.block-main").isEmpty() && document.select("div.mov").isEmpty()) {
            Log.d(TAG, "[getHome] document vide après bypass — retry OkHttp (cookie frais)")
            documentCache.remove(baseUrl)
            kotlinx.coroutines.delay(500)
            document = try {
                getDocument(baseUrl, silentBypass = false)
            } catch (_: Exception) { document }
        }

        // 2026-06-23 : lancer les fetches des pages listing en parallèle pendant
        // qu'on parse la home page (= 0 latence supplémentaire)
        val latestFilmsD = async {
            runCatching {
                val doc = try {
                    val d = service.getMovies(1)
                    if (isCloudflareChallenge(d)) getDocument("${baseUrl}film-en-streaming/page/1") else d
                } catch (_: Exception) { getDocument("${baseUrl}film-en-streaming/page/1") }
                doc.select("div.mov").mapNotNull {
                    val id = it.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/")
                        ?.takeIf { id -> id.isNotBlank() } ?: return@mapNotNull null
                    Movie(
                        id = id,
                        title = it.selectFirst("a.mov-t")?.ownText()?.trim() ?: return@mapNotNull null,
                        poster = it.selectFirst("img")?.attr("src")?.let { src -> baseUrl + src },
                    )
                }
            }.getOrDefault(emptyList())
        }
        val latestSeriesD = async {
            runCatching {
                val doc = try {
                    val d = service.getTvShows(1)
                    if (isCloudflareChallenge(d)) getDocument("${baseUrl}serie-en-streaming/page/1") else d
                } catch (_: Exception) { getDocument("${baseUrl}serie-en-streaming/page/1") }
                doc.select("div.mov").mapNotNull {
                    val id = it.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/")
                        ?.takeIf { id -> id.isNotBlank() } ?: return@mapNotNull null
                    val rawTitle = it.selectFirst("a.mov-t")?.ownText()?.trim() ?: return@mapNotNull null
                    val season = it.selectFirst("span.block-sai")?.text()?.trim()?.takeIf { s -> s.isNotBlank() }
                    TvShow(
                        id = id,
                        title = if (season != null) "$rawTitle - $season" else rawTitle,
                        poster = it.selectFirst("img")?.attr("src")?.let { src -> baseUrl + src },
                    )
                }
            }.getOrDefault(emptyList())
        }

        // Genres populaires — 1 page chacun, en parallèle
        fun asyncGenreMovies(genre: String) = async {
            runCatching {
                val doc = try {
                    val d = service.getGenre(genre, 1)
                    if (isCloudflareChallenge(d)) getDocument("${baseUrl}film-en-streaming/$genre/page/1") else d
                } catch (_: Exception) { getDocument("${baseUrl}film-en-streaming/$genre/page/1") }
                doc.select("div.mov").take(20).mapNotNull {
                    val id = it.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/")
                        ?.takeIf { id -> id.isNotBlank() } ?: return@mapNotNull null
                    Movie(
                        id = id,
                        title = it.selectFirst("a.mov-t")?.ownText()?.trim() ?: return@mapNotNull null,
                        poster = it.selectFirst("img")?.attr("src")?.let { src -> baseUrl + src },
                    )
                }
            }.getOrDefault(emptyList())
        }
        val actionD   = asyncGenreMovies("action")
        val comedieD  = asyncGenreMovies("comedie")
        val thrillerD = asyncGenreMovies("thriller")
        val sfD       = asyncGenreMovies("science-fiction")
        val horreurD  = asyncGenreMovies("horreur")

        val categories = mutableListOf<Category>()

        // 2026-06-23 (user "top film à nous il est pas pareil" + "respecter les
        //   sites") : avant on faisait getOrNull(0) / getOrNull(1) = fragile
        //   car le site Wiflix a 3 `div.block-main` (TOP Séries / TOP Films /
        //   Films Anciens) ET l'ordre peut changer. Maintenant on matche par
        //   TITRE EXACT du `div.block-title` (= "TOP Séries", "TOP Films").
        //   1 seul fetch home = on respecte le site sans le bombarder.
        fun findBlockByTitlePrefix(prefix: String): org.jsoup.nodes.Element? {
            return document.select("div.block-main").firstOrNull { block ->
                val titleText = block.selectFirst("div.block-title")?.text()?.trim() ?: ""
                titleText.startsWith(prefix, ignoreCase = true)
            }
        }
        val topSeriesBlock = findBlockByTitlePrefix("TOP Séries")
        val topFilmsBlock = findBlockByTitlePrefix("TOP Films")

        val topSeries = topSeriesBlock?.select("div.mov")?.mapNotNull {
            val id = it.selectFirst("a.mov-t")
                ?.attr("href")?.substringAfterLast("/")
                ?.takeIf { id -> id.isNotBlank() } ?: return@mapNotNull null
            val rawTitle = it.selectFirst("a.mov-t")?.ownText()?.trim() ?: return@mapNotNull null
            val season = it.selectFirst("span.block-sai")?.text()?.trim()?.takeIf { s -> s.isNotBlank() }
            TvShow(
                id = id,
                title = if (season != null) "$rawTitle - $season" else rawTitle,
                poster = it.selectFirst("img")
                    ?.attr("src")?.let { src -> baseUrl + src },
                banner = it.selectFirst("img")
                    ?.attr("src")?.let { src -> baseUrl + src },
            )
        } ?: emptyList()

        val topFilms = topFilmsBlock?.select("div.mov")?.mapNotNull {
            val id = it.selectFirst("a.mov-t")
                ?.attr("href")?.substringAfterLast("/")
                ?.takeIf { id -> id.isNotBlank() } ?: return@mapNotNull null
            Movie(
                id = id,
                title = it.selectFirst("a.mov-t")?.ownText()?.trim() ?: return@mapNotNull null,
                poster = it.selectFirst("img")
                    ?.attr("src")?.let { src -> baseUrl + src },
            )
        } ?: emptyList()

        Log.d(TAG, "[getHome] TOP Séries (block home): ${topSeries.size} items, TOP Films (block home): ${topFilms.size} items")

        // 2026-06-23 (user "pour avoir le top série tu prends la première page
        //   des wifi séries /serie-en-streaming/, pour top film
        //   /film-en-streaming/") : on enrichit les 2 sections TOP en y ajoutant
        //   les ~25 items de la 1ère page du listing complet. Dédup par ID
        //   pour pas dupliquer ce qui était déjà dans le block home.
        //   Ces fetches utilisent latestSeriesD/latestFilmsD déjà launched en
        //   parallèle au début → on attend leur résultat ici (= 0 latence
        //   supplémentaire car déjà en cours).
        val moreSeries = latestSeriesD.await()
        val moreFilms = latestFilmsD.await()
        val topSeriesIds = topSeries.map { it.id }.toSet()
        val topFilmsIds = topFilms.map { it.id }.toSet()
        val topSeriesAll = topSeries + moreSeries.filter { it.id !in topSeriesIds }
        val topFilmsAll = topFilms + moreFilms.filter { it.id !in topFilmsIds }
        Log.d(TAG, "[getHome] TOP Séries enrichi: ${topSeriesAll.size} items (+${moreSeries.size - (moreSeries.count { it.id in topSeriesIds })}), TOP Films enrichi: ${topFilmsAll.size} items")

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
            // 2026-06-23 : matching strict — type (TvShow→Tv, Movie→Movie) + titre similaire.
            // Avant : prenait le 1er résultat avec backdrop → "From" matchait n'importe quoi.
            for (item in featuredItems.take(15)) {
                try {
                    val title = when (item) {
                        is TvShow -> item.title.substringBefore(" - ")
                        is Movie -> item.title
                        else -> continue
                    }
                    val cleanTitle = title.trim()
                    if (cleanTitle.length < 2) continue // titres trop courts = matching impossible
                    val results = TMDb3.Search.multi(cleanTitle, language = "fr-FR")
                    // Matcher par type + vérifier que le titre TMDb ressemble au nôtre
                    val match = results.results.firstOrNull { result ->
                        when {
                            item is TvShow && result is TMDb3.Tv && result.backdropPath != null -> {
                                val tmdbName = result.name?.trim() ?: ""
                                tmdbName.equals(cleanTitle, ignoreCase = true)
                                    || tmdbName.contains(cleanTitle, ignoreCase = true)
                                    || cleanTitle.contains(tmdbName, ignoreCase = true)
                            }
                            item is Movie && result is TMDb3.Movie && result.backdropPath != null -> {
                                val tmdbTitle = result.title?.trim() ?: ""
                                tmdbTitle.equals(cleanTitle, ignoreCase = true)
                                    || tmdbTitle.contains(cleanTitle, ignoreCase = true)
                                    || cleanTitle.contains(tmdbTitle, ignoreCase = true)
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
        }
        // Ne garder dans le carrousel que les items avec backdrop TMDb HD (min 5 sinon liste complète)
        val carouselItems = featuredItems.filter { item ->
            val b = when (item) { is Movie -> item.banner; is TvShow -> item.banner; else -> null }
            b != null && b.contains("/t/p/")
        }
        val finalCarousel = if (carouselItems.size >= 5) carouselItems else featuredItems
        if (finalCarousel.isNotEmpty()) {
            categories.add(Category(name = Category.FEATURED, list = finalCarousel))
        }

        // Retirer du TOP les items déjà dans le carousel pour éviter les doublons
        val featuredIds = finalCarousel.map { when (it) { is TvShow -> it.id; is Movie -> it.id; else -> "" } }.toSet()
        // 2026-06-23 (user "incrémenter notre TOP Série + une autre TOP Film") :
        //   on utilise topSeriesAll / topFilmsAll (= block home + page listing
        //   complet, dédupliqué) → ~25-30 items par section au lieu de 6-10.
        categories.add(Category(name = "TOP Séries", list = topSeriesAll.filter { it.id !in featuredIds }))
        categories.add(Category(name = "TOP Films", list = topFilmsAll.filter { (it as? Movie)?.id !in featuredIds }))

        // 2026-06-23 (user "supprimes les 2 premières catégories Derniers films
        //   ajoutés / Dernières séries ajoutées = doublons des TOP enrichis") :
        //   on les vire car le contenu est maintenant dans TOP Séries / TOP Films.

        categories.add(
            Category(
                name = "Films Anciens",
                list = document.select("div.block-main").getOrNull(2)?.select("div.mov")?.mapNotNull {
                    val id = it.selectFirst("a.mov-t")
                        ?.attr("href")?.substringAfterLast("/")
                        ?.takeIf { id -> id.isNotBlank() } ?: return@mapNotNull null
                    Movie(
                        id = id,
                        title = it.selectFirst("a.mov-t")?.ownText()?.trim() ?: return@mapNotNull null,
                        poster = it.selectFirst("img")
                            ?.attr("src")?.let { src -> baseUrl + src },
                    )
                } ?: emptyList(),
            )
        )

        // Genres populaires (avec posters)
        fun addGenreCategory(label: String, items: List<Movie>) {
            if (items.isNotEmpty()) {
                categories.add(Category(name = label, list = items))
            }
        }
        addGenreCategory("Action",         actionD.await())
        addGenreCategory("Comédie",        comedieD.await())
        addGenreCategory("Thriller",       thrillerD.await())
        addGenreCategory("Science-Fiction", sfD.await())
        addGenreCategory("Horreur",        horreurD.await())

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

        categories
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
                val rawTitle = it.selectFirst("a.mov-t")?.ownText()?.trim() ?: ""
                val season = it.selectFirst("span.block-sai")?.text()?.trim()?.takeIf { s -> s.isNotBlank() }
                TvShow(
                    id = showId,
                    title = if (season != null) "$rawTitle - $season" else rawTitle,
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
            val rawTitle = it.selectFirst("a.mov-t")?.ownText()?.trim() ?: ""
            val season = it.selectFirst("span.block-sai")?.text()?.trim()?.takeIf { s -> s.isNotBlank() }
            TvShow(
                id = it.selectFirst("a.mov-t")
                    ?.attr("href")?.substringAfterLast("/")
                    ?: "",
                title = if (season != null) "$rawTitle - $season" else rawTitle,
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

        // 2026-06-23 (user "pas de jaquette sur les épisodes Wiflix") :
        //   cascade de selectors pour récupérer le poster de la série, utilisé
        //   comme miniature de chaque épisode. Avant : QUE `img#posterimg` →
        //   null si la structure HTML a changé → épisodes sans image.
        //   Maintenant : essaye 4 sélecteurs + 2 patterns d'URL (relative et
        //   absolue), puis fallback TMDB via le titre du show.
        fun resolveSrc(src: String?): String? {
            if (src.isNullOrBlank()) return null
            if (src.startsWith("http")) return src
            if (src.startsWith("//")) return "https:$src"
            return baseUrl.trimEnd('/') + "/" + src.trimStart('/')
        }
        var showPoster: String? = resolveSrc(document.selectFirst("img#posterimg")?.attr("src"))
            ?: resolveSrc(document.selectFirst("div.movie-poster img, div.mov-info img, div.entry-poster img")?.attr("src"))
            ?: resolveSrc(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: resolveSrc(document.selectFirst("div.mov img")?.attr("src"))

        // Fallback TMDB : si toujours null, search par titre + récupère poster TMDB
        if (showPoster == null) {
            try {
                val title = document.selectFirst("h1, div.mov-t, span.mov-t")?.text()?.trim()
                if (!title.isNullOrBlank()) {
                    val results = TMDb3.Search.multi(title.take(60))
                    val tvMatch = results.results.firstOrNull { it is TMDb3.Tv } as? TMDb3.Tv
                    showPoster = tvMatch?.posterPath?.w500
                }
            } catch (_: Exception) {}
        }

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
                    val rawTitle = it.selectFirst("a.mov-t")?.ownText()?.trim() ?: ""
                    val season = it.selectFirst("span.block-sai")?.text()?.trim()?.takeIf { s -> s.isNotBlank() }
                    TvShow(
                        id = showId,
                        title = if (season != null) "$rawTitle - $season" else rawTitle,
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
