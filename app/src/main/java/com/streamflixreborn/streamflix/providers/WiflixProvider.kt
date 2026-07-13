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
    // 2026-07-07 (user "wiflix backup ne suit plus dans les autres groupes, sa
    //   recherche est morte" + "moins de VOE") : flemmix.city n'est PLUS listé
    //   sur ww1.wiflix-adresses.fun (mort), flemmix.fast est passé "Ancien domaine
    //   principal - bloqué". Nouveau LIEN PRINCIPAL officiel = flemmix.gold (badge
    //   "Domaine actif - Utilisez toujours ce lien", vérifié vivant + sert le vrai
    //   template flemmixnew). L'app restait coincée sur flemmix.city (pas dans
    //   knownBlockedDomains → pas de force-refresh) → recherche Wiflix vide → plus
    //   de backup Wiflix + moins de serveurs VOE (Wiflix en fournit beaucoup).
    // 2026-07-09 : flemmix.gold MORT (redirige vers neufneuf.space →
    //   "Redirection sécurisée vers flemmix.garden"). Nouveau domaine actif.
    override val defaultBaseUrl: String = "https://flemmix.garden/"
    // Liste des domaines morts/bloqués connus. Si le cache PROVIDER_URL est sur
    // un de ceux-ci, on force un refresh au prochain initializeService() au lieu
    // de rester coincé dessus à vie. Mettre à jour quand le portail évolue.
    private val knownBlockedDomains = listOf(
        "flemmix.win", "flemmix.prof", "flemmix.irish", "flemmix.wales",
        "flemmix.vip", "flemmix.casa", "wiflix.re", "wiflix.eu", "wiflix.org",
        "flemmix.farm", "flemmix.team",
        // 2026-07-07 : morts/bloqués selon ww1.wiflix-adresses.fun (flemmix.city
        //   n'est plus listé du tout ; flemmix.fast + flemmix.cafe = "bloqués").
        //   Les mettre ici force un refresh du cache PROVIDER_URL coincé dessus.
        "flemmix.city", "flemmix.fast", "flemmix.cafe", "flemmix.gold",
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
    // 2026-06-29 (REPAIR — règle user) : captcha invisible par défaut, visible
    //   seulement après 3 échecs silencieux consécutifs.
    @Volatile
    private var silentFailStreak = 0
    private const val VISIBLE_AFTER_STREAK = 3

    // 2026-06-23 (user "Error 1015 You are being rate limited") : Cloudflare
    //   ban temporaire si trop de requêtes simultanées. Avant : home faisait
    //   ~11 requêtes parallèles (1 home + 2 listings + 5 genres + 8 categories)
    //   = burst → CF rate-limit kick in. Maintenant : Semaphore(3) limite à
    //   3 requêtes simultanées max = pas de burst → CF content. Petit délai
    //   au prix d'une latence légèrement plus longue mais zéro ban.
    // 2026-07-04 (user "à froid le film met 10s, à chaud 2s ; c'est pareil tous providers") :
    //   3 → 8. Ce semaphore SÉRIALISE les accès réseau/CF de CE provider. À froid, la HOME
    //   (scrape des 5-6 genres via getDocument) tenait les 3 permits → le getServers du PLAYER
    //   attendait 6-7s un permit libre = tout le retard. En montant à 8, le player passe sans
    //   attendre la home. (Reste borné pour ne pas burst CF.)
    private val networkSemaphore = Semaphore(8)

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

        // Tentative 1 : OkHttp direct UNIQUEMENT si le cookie cf_clearance est
        //   déjà présent (sinon CF renvoie un 403 challenge garanti → on saute
        //   direct au WebView bypass). Pattern aligné FrenchAnime / APK v1.7.226.
        val okHttpDoc = if (hasCfClearanceCookie()) tryOkHttp(url) else null
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
        // 2026-06-29 (RÈGLE user) : captcha INVISIBLE par défaut. À chaque échec
        //   silencieux on incrémente le streak ; on n'affiche le captcha VISIBLE
        //   qu'à partir de 3 échecs consécutifs (résolution manuelle). Réussite
        //   → streak remis à 0.
        if (silentBypass) {
            if (bypassFailed) {
                silentFailStreak++
                if (silentFailStreak >= VISIBLE_AFTER_STREAK &&
                    System.currentTimeMillis() - lastVisibleBypassAt > VISIBLE_BYPASS_COOLDOWN_MS) {
                    lastVisibleBypassAt = System.currentTimeMillis()
                    Log.d(TAG, "[Provider] $silentFailStreak échecs silencieux → captcha VISIBLE pour $url")
                    html = getResolver().get(url, silent = false)
                    bypassFailed = isBypassFailed(html)
                    if (!bypassFailed) { silentFailStreak = 0 }
                } else {
                    Log.d(TAG, "[Provider] Échec silencieux #$silentFailStreak (captcha reste invisible) pour $url")
                }
            } else {
                silentFailStreak = 0
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
            // 2026-06-30 : OkHttp+cookie ne marche PAS sur flemmix (CF cf-mitigated:
            //   challenge → 403, l'empreinte TLS d'OkHttp n'est pas celle d'un
            //   navigateur). Le contenu DOIT venir du WebView. On abandonne ici (le
            //   WebView a son budget de polls élargi pour rendre le contenu lui-même).
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

    /** 2026-06-29 (REPAIR — recopié du décompilé v1.7.226) : true si le cookie
     *  cf_clearance est déjà présent dans le CookieManager pour ce domaine. */
    private fun hasCfClearanceCookie(): Boolean = try {
        val cookies = android.webkit.CookieManager.getInstance().getCookie(baseUrl) ?: ""
        cookies.contains("cf_clearance=")
    } catch (_: Throwable) { false }

    /** Tente un fetch OkHttp. Retourne le Document si succès + pas de challenge, null sinon. */
    private fun tryOkHttp(url: String): Document? {
        return try {
            // 2026-06-28 : MÊME UA que le WebView stealth (Chrome 131).
            //   Le cookie cf_clearance est lié au UA : s'il a été posé par
            //   le WebView avec Chrome/131 et qu'OkHttp envoie Chrome/116,
            //   Cloudflare rejette → HTML retourné = challenge → fallback
            //   WebView HTML avec \t dans les titres + images bloquées.
            // 2026-06-27 (REPAIR_HANDOFF #2.1) : injecter le cookie cf_clearance
            //   (CookieManager WebView) — sinon Cloudflare bloque TOUT OkHttp.
            val webCookie = try {
                android.webkit.CookieManager.getInstance().getCookie(baseUrl) ?: ""
            } catch (_: Throwable) { "" }
            val request = Request.Builder()
                .url(url)
                .header("Referer", baseUrl)
                .header("User-Agent", WebViewResolver.STEALTH_UA)
                .apply { if (webCookie.isNotBlank()) header("Cookie", webCookie) }
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
                getDocument(baseUrl, silentBypass = true)
            } else doc
        } catch (e: Exception) {
            Log.d(TAG, "[getHome] Retrofit failed: ${e.message}, using bypass")
            try {
                getDocument(baseUrl, silentBypass = true)
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
                getDocument(baseUrl, silentBypass = true)
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

        // 2026-07-04 : DÉDUP GLOBALE inter-catégories — un film n'apparaît QUE dans
        //   sa 1ère catégorie (ordre : FEATURED > TOP Séries > TOP Films > Films
        //   Anciens > genres). Corrige "5× Pitch Black dans Action/Thriller/SF/…".
        val seenHome = mutableSetOf<String>()
        fun itemId(it: AppAdapter.Item): String = when(it) { is TvShow -> it.id; is Movie -> it.id; else -> "" }
        // Ajouter les ids du carousel dans seenHome
        finalCarousel.forEach { seenHome.add(itemId(it)) }

        val topSeriesFiltered = topSeriesAll.filter { seenHome.add(it.id) }
        categories.add(Category(name = "TOP Séries", list = topSeriesFiltered))

        val topFilmsFiltered = topFilmsAll.filter { seenHome.add(itemId(it)) }
        categories.add(Category(name = "TOP Films", list = topFilmsFiltered))

        // 2026-06-23 (user "supprimes les 2 premières catégories Derniers films
        //   ajoutés / Dernières séries ajoutées = doublons des TOP enrichis") :
        //   on les vire car le contenu est maintenant dans TOP Séries / TOP Films.

        val filmsAnciens = document.select("div.block-main").getOrNull(2)?.select("div.mov")?.mapNotNull {
            val id = it.selectFirst("a.mov-t")
                ?.attr("href")?.substringAfterLast("/")
                ?.takeIf { id -> id.isNotBlank() } ?: return@mapNotNull null
            Movie(
                id = id,
                title = it.selectFirst("a.mov-t")?.ownText()?.trim() ?: return@mapNotNull null,
                poster = it.selectFirst("img")
                    ?.attr("src")?.let { src -> baseUrl + src },
            )
        }?.filter { seenHome.add(it.id) } ?: emptyList()
        if (filmsAnciens.isNotEmpty()) {
            categories.add(Category(name = "Films Anciens", list = filmsAnciens))
        }

        // Genres populaires (avec posters) — dédup via seenHome
        fun addGenreCategory(label: String, items: List<Movie>) {
            val unique = items.filter { seenHome.add(it.id) }
            if (unique.isNotEmpty()) {
                categories.add(Category(name = label, list = unique))
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

                val uniqueShows = shows.filter { seenHome.add(itemId(it)) }
                if (uniqueShows.isNotEmpty()) {
                    categories.add(Category(name = headerText, list = uniqueShows))
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

        // 2026-07-04 : MOTEUR TMDB — la recherche native flemmix est bloquée
        //   par CloudFlare (POST search → bot shield → résultats aléatoires).
        //   On utilise TMDb3.Search.multi comme moteur PRINCIPAL :
        //   - Rapide, fiable, zéro CF
        //   - Retourne un id TMDB numérique → le downstream le gère déjà
        //     (resolveTmdbMovieDetail/TvShowDetail par id direct, getServers
        //      délègue à searchServersByTitle pour trouver les vrais serveurs)
        //   - Posters TMDB haute qualité
        //   POST-FILTRE : TMDB Search.multi est très large (matche sur titres
        //   alternatifs, mots-clés, synonymes dans TOUTES les langues). On filtre
        //   les résultats : le query doit apparaître comme MOT (word boundary)
        //   dans le titre FR OU le titre original. Ex : "one" matche "One Piece"
        //   mais PAS "Lone Star" (substring, pas word boundary).
        return try {
            val tmdbResults = TMDb3.Search.multi(
                query = query,
                language = "fr-FR",
                page = page,
            )
            hasMore = page < tmdbResults.totalPages

            // Regex word-boundary : \bquery → "one" matche "One Piece", "One-Punch Man"
            // mais PAS "Lone" (le L avant empêche le word boundary).
            val qRegex = Regex("\\b${Regex.escape(query.trim())}", RegexOption.IGNORE_CASE)

            tmdbResults.results.mapNotNull { item ->
                when (item) {
                    is TMDb3.Movie -> {
                        val titleMatch = qRegex.containsMatchIn(item.title)
                                || qRegex.containsMatchIn(item.originalTitle)
                        if (!titleMatch) return@mapNotNull null
                        Movie(
                            id = item.id.toString(),
                            title = item.title,
                            poster = item.posterPath?.w500,
                        )
                    }
                    is TMDb3.Tv -> {
                        val titleMatch = qRegex.containsMatchIn(item.name)
                                || qRegex.containsMatchIn(item.originalName)
                        if (!titleMatch) return@mapNotNull null
                        TvShow(
                            id = item.id.toString(),
                            title = item.name,
                            poster = item.posterPath?.w500,
                        )
                    }
                    else -> null // skip Person results
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TMDB search failed, fallback natif", e)
            hasMore = false
            searchNative(query, page)
        }
    }

    /**
     * 2026-07-04 : ancien moteur de recherche natif flemmix, gardé en FALLBACK
     * si TMDB est injoignable. Bloqué par CF la plupart du temps.
     */
    private suspend fun searchNative(query: String, page: Int): List<AppAdapter.Item> {
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
        // 2026-07-09 : si id purement numérique (= TMDB, vient de search TMDB),
        // construire la fiche via TMDB directement (zéro CF, zéro scraping flemmix).
        // Un slug flemmix contient toujours des lettres ("36373-supergirl-2026.html").
        if (id.all { it.isDigit() }) {
            val tmdbMovie = com.streamflixreborn.streamflix.utils.TmdbUtils.getMovieById(id.toInt(), "fr-FR")
            if (tmdbMovie != null) return tmdbMovie
            // fallback : tenter quand même le scraping (ne marchera probablement pas)
        }
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

        // 2026-07-03 (user "il manque les jaquettes des acteurs sur les fiches Wiflix") :
        //   le cast vient du scraping flemmix (noms seuls, aucune photo). On récupère les
        //   PORTRAITS via les crédits TMDB (getMovie par titre+année) et on les attache par
        //   correspondance de nom. On GARDE l'id/nom scrapés → le clic acteur reste le flux
        //   Wiflix qui fonctionne. Pas de match → silhouette (inchangé).
        val castTitleTmdb = document.selectFirst("header.full-title h1")?.text()?.trim().orEmpty()
        val castYearTmdb = Regex("(19|20)\\d{2}").find(id)?.value?.toIntOrNull()
        val tmdbCastPhotos: Map<String, String?> = runCatching {
            com.streamflixreborn.streamflix.utils.TmdbUtils.getMovie(castTitleTmdb, castYearTmdb, "fr-FR")
                ?.cast?.associate { normPersonName(it.name) to it.image }
        }.getOrNull().orEmpty()

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
                        image = tmdbCastPhotos[normPersonName(it.text())],
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
        // 2026-07-09 : si id purement numérique (= TMDB, vient de search TMDB),
        // construire la fiche via TMDB directement (zéro CF). Un slug flemmix
        // contient toujours des lettres ("12345-from-saison-2.html").
        // TMDB renvoie TOUTES les saisons (mieux que flemmix qui en a une par page).
        // Le seasonId sera "tmdbId-seasonNum" → getEpisodesBySeason le gère.
        if (id.all { it.isDigit() }) {
            val tmdbShow = com.streamflixreborn.streamflix.utils.TmdbUtils.getTvShowById(id.toInt(), "fr-FR")
            if (tmdbShow != null) return tmdbShow
        }
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
        // 2026-07-03 (user "jaquettes acteurs manquantes") : photos du cast via crédits TMDB
        //   (getTvShow par titre sans "Saison N"), attachées par nom. id/nom scrapés gardés.
        val castShowTitleTmdb = title.substringBefore("Saison").trim().ifBlank { title }
        val castYearTmdbTv = Regex("(19|20)\\d{2}").find(id)?.value?.toIntOrNull()
        val tmdbCastPhotos: Map<String, String?> = runCatching {
            com.streamflixreborn.streamflix.utils.TmdbUtils.getTvShow(castShowTitleTmdb, castYearTmdbTv, "fr-FR")
                ?.cast?.associate { normPersonName(it.name) to it.image }
        }.getOrNull().orEmpty()

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
                        image = tmdbCastPhotos[normPersonName(it.text())],
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
        // 2026-07-09 : si le seasonId est au format TMDB "tmdbId-seasonNum"
        // (pas de "/" = pas un slug flemmix), résoudre via TMDB directement.
        if (!seasonId.contains("/")) {
            val parts = seasonId.split("-")
            val tmdbId = parts.getOrNull(0)
            val seasonNum = parts.getOrNull(1)?.toIntOrNull()
            if (tmdbId != null && seasonNum != null) {
                return com.streamflixreborn.streamflix.utils.TmdbUtils
                    .getEpisodesBySeason(tmdbId, seasonNum, "fr-FR")
            }
        }
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

    /** Normalise un nom d'acteur pour matcher scraping flemmix ↔ crédits TMDB
     *  (minuscules, sans accents ni ponctuation). */
    private fun normPersonName(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]"), "")

    override suspend fun getPeople(id: String, page: Int): People {
        // 2026-07-09 : si id purement numérique (= TMDB, vient de reco/cast TMDB),
        // résoudre la filmographie via TMDB People.details (zéro CF).
        if (id.all { it.isDigit() }) {
            try {
                val person = TMDb3.People.details(
                    personId = id.toInt(),
                    appendToResponse = listOfNotNull(
                        if (page > 1) null else TMDb3.Params.AppendToResponse.Person.COMBINED_CREDITS,
                    ),
                    language = "fr-FR"
                )
                return People(
                    id = person.id.toString(),
                    name = person.name,
                    image = person.profilePath?.w500,
                    biography = person.biography,
                    placeOfBirth = person.placeOfBirth,
                    birthday = person.birthday,
                    deathday = person.deathday,
                    filmography = person.combinedCredits?.cast?.mapNotNull { multi ->
                        when (multi) {
                            is TMDb3.Movie -> Movie(
                                id = multi.id.toString(),
                                title = multi.title,
                                poster = multi.posterPath?.w500,
                            )
                            is TMDb3.Tv -> TvShow(
                                id = multi.id.toString(),
                                title = multi.name,
                                poster = multi.posterPath?.w500,
                            )
                            else -> null
                        }
                    }?.distinctBy { when (it) { is Movie -> it.id; is TvShow -> it.id; else -> it } }
                     ?: listOf(),
                )
            } catch (_: Exception) { /* fallback scraping ci-dessous */ }
        }
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

    // 2026-07-07 : re-implémentation de searchServersByTitle (perdu au restore 08:43).
    // Quand le backup envoie un id TMDB numérique (pas un slug Wiflix), on
    // résout via : TMDB titre → recherche NATIVE flemmix → slug → getServers(slug/tab).
    private suspend fun searchServersByTitle(
        tmdbId: String,
        videoType: Video.Type,
    ): List<Video.Server> {
        // 2026-07-09 : pour les épisodes, l'id passé peut être celui de l'ÉPISODE
        // TMDB (pas de la série). On extrait l'id de la SÉRIE depuis videoType.tvShow.id
        // et le titre depuis videoType.tvShow.title (déjà peuplé par getTvShow TMDB).
        val title: String?
        if (videoType is Video.Type.Episode) {
            // Priorité : titre déjà dans le videoType (évite un appel TMDB)
            title = videoType.tvShow.title.takeIf { it.isNotBlank() }
                ?: try {
                    val showId = videoType.tvShow.id.toIntOrNull() ?: tmdbId.substringBefore("/").toIntOrNull()
                    if (showId != null) TMDb3.TvSeries.details(showId, language = "fr-FR").name else null
                } catch (e: Exception) {
                    Log.w("Wiflix", "searchServersByTitle TMDB lookup failed: ${e.message?.take(100)}")
                    null
                }
        } else {
            val intId = tmdbId.substringBefore("/").toIntOrNull()
            if (intId == null) {
                Log.w("Wiflix", "searchServersByTitle($tmdbId) → pas un entier, skip")
                return emptyList()
            }
            title = try {
                TMDb3.Movies.details(intId, language = "fr-FR").title
            } catch (e: Exception) {
                Log.w("Wiflix", "searchServersByTitle TMDB lookup failed: ${e.message?.take(100)}")
                null
            }
        }
        if (title.isNullOrBlank()) {
            Log.w("Wiflix", "searchServersByTitle($tmdbId) → titre TMDB vide")
            return emptyList()
        }
        Log.d("Wiflix", "searchServersByTitle($tmdbId) titre='$title'")

        // 2. Recherche NATIVE flemmix (POST search). CF → fallback getDocument.
        val searchDoc = try {
            val doc = service.search(title)
            if (isCloudflareChallenge(doc)) {
                getDocument("${baseUrl}index.php?do=search&subaction=search&story=${java.net.URLEncoder.encode(title, "UTF-8")}")
            } else doc
        } catch (e: Exception) {
            try {
                getDocument("${baseUrl}index.php?do=search&subaction=search&story=${java.net.URLEncoder.encode(title, "UTF-8")}")
            } catch (e2: Exception) {
                Log.w("Wiflix", "searchServersByTitle search failed: ${e2.message?.take(100)}")
                return emptyList()
            }
        }

        // 3. Trouver le résultat qui matche le titre
        //    2026-07-09 (audit strict) : + GATE ANNÉE pour les films. Le slug Wiflix porte
        //    souvent l'année (ex « 36373-supergirl-2026.html ») → on rejette un remake homonyme
        //    (« Dune 1984 » ≠ « Dune 2021 »). Ne gate QUE si l'année cible est connue ET qu'une
        //    année est trouvée dans le lien. Séries : pas de gate (année peu fiable).
        val targetYear = (videoType as? Video.Type.Movie)?.releaseDate?.take(4)?.toIntOrNull()
        val targetSeason = (videoType as? Video.Type.Episode)?.season?.number
        val resultLinks = searchDoc.select("div.mov a[href], div.short-item a[href], article a[href]")
        // Candidats qui matchent le TITRE (+ gate année pour les films).
        val titleMatched = resultLinks.filter { a ->
            val linkTitle = a.selectFirst("span.title1, h3, .title")?.text()
                ?: a.attr("title").ifBlank { null }
                ?: a.text()
            if (!com.streamflixreborn.streamflix.utils.BackupRegistry.titleMatches(linkTitle, title)) return@filter false
            if (videoType is Video.Type.Movie && targetYear != null) {
                val href = a.attr("href")
                val candYear = Regex("(19|20)\\d{2}").findAll(href).map { it.value.toInt() }.lastOrNull()
                if (candYear != null && kotlin.math.abs(candYear - targetYear) > 1) return@filter false
            }
            true
        }
        // 2026-07-09 (user « FROM S2E2 : Wiflix absent ») — CIBLAGE SAISON. Wiflix indexe CHAQUE
        //   saison comme une entrée distincte (slug « …-saison-N.html »). Le fallback prenait la
        //   1ʳᵉ entrée titre (ex « from-2022-saison-4 » pour une demande S2) → mauvaise saison →
        //   0 serveur. On prend la saison DEMANDÉE ; sinon un slug SANS numéro de saison (série
        //   entière) ; JAMAIS une autre saison (mieux vaut 0 serveur que le mauvais épisode).
        val matchedHref = if (targetSeason != null && targetSeason > 0) {
            (titleMatched.firstOrNull { it.attr("href").contains("saison-$targetSeason", ignoreCase = true) }
                ?: titleMatched.firstOrNull { !Regex("(?i)saison-?\\d+").containsMatchIn(it.attr("href")) }
                )?.attr("href")
        } else {
            titleMatched.firstOrNull()?.attr("href")
        }

        if (matchedHref.isNullOrBlank()) {
            Log.w("Wiflix", "searchServersByTitle('$title') → 0 résultat natif")
            return emptyList()
        }
        val slug = matchedHref.substringAfterLast("/").takeIf { it.isNotBlank() }
            ?: return emptyList()
        Log.d("Wiflix", "searchServersByTitle('$title') → slug=$slug")

        // 4. Résoudre l'épisode ou le film
        if (videoType is Video.Type.Movie) {
            return try { getServers(slug, videoType) } catch (e: Exception) {
                Log.w("Wiflix", "searchServersByTitle getServers(movie/$slug) failed: ${e.message?.take(100)}")
                emptyList()
            }
        }

        // Episode : ouvrir la page série, trouver les tabs VF/VOSTFR, extraire
        val showDoc = try {
            val doc = service.getTvShow(slug)
            if (isCloudflareChallenge(doc)) getDocument("${baseUrl}serie-en-streaming/$slug") else doc
        } catch (e: Exception) {
            try { getDocument("${baseUrl}serie-en-streaming/$slug") }
            catch (_: Exception) { return emptyList() }
        }
        // 2026-07-09 (user « FROM S2E2 : Wiflix absent → 0 serveur ») — STRUCTURE RÉELLE des
        //   séries Wiflix (vérifiée en direct) : les serveurs sont dans UN bloc PAR ÉPISODE et
        //   PAR LANGUE : div.ep<N>vf (VF) / div.ep<N>vs (VOSTFR). L'ancien blocvostfr/blocfr
        //   n'existe plus sur les pages saison → le fallback tombait sur 0 et repartait en film.
        //   On construit donc les blocs à partir du NUMÉRO D'ÉPISODE demandé.
        val epNum = (videoType as? Video.Type.Episode)?.number
        val tabs = mutableListOf<String>()
        if (epNum != null) {
            for (rel in listOf("ep${epNum}vf", "ep${epNum}vs")) {
                if (showDoc.select("div.$rel a").isNotEmpty()) tabs.add(rel)
            }
        }
        // Fallback ANCIEN format (blocvostfr/blocfr) si la page n'a pas les blocs par épisode.
        if (tabs.isEmpty()) {
            if (showDoc.select("div.blocvostfr a").isNotEmpty()) tabs.add("blocvostfr")
            if (showDoc.select("div.blocfr a").isNotEmpty()) tabs.add("blocfr")
        }
        if (tabs.isEmpty()) {
            // Dernier recours : format film (tabs-sel) réutilisé par certaines séries.
            return try { getServers(slug, Video.Type.Movie(id = slug, title = title, releaseDate = "", poster = "", imdbId = null)) } catch (_: Exception) { emptyList() }
        }
        val allServers = mutableListOf<Video.Server>()
        for (tab in tabs) {
            val tabServers = try { getServers("$slug/$tab", videoType) } catch (_: Exception) { emptyList() }
            allServers.addAll(tabServers)
        }
        Log.d("Wiflix", "searchServersByTitle('$title') S${(videoType as? Video.Type.Episode)?.season?.number}E$epNum → ${allServers.size} serveurs (tabs=$tabs)")
        return allServers
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val _tGS = System.currentTimeMillis()
        Log.i("ServDiagT", "GETSERVERS entrée")
        initializeService()
        Log.i("ServDiagT", "GETSERVERS après initializeService à ${System.currentTimeMillis()-_tGS}ms")
        val servers = when (videoType) {
            is Video.Type.Episode -> {
                // 2026-07-07 : garde contre ids TMDB numériques sans "/" (crash
                // IndexOutOfBoundsException: Index: 1, Size: 1). Quand le backup
                // passe un id TMDB pur, on délègue à searchServersByTitle.
                val parts = id.split("/")
                if (parts.size < 2) {
                    Log.w("Wiflix", "getServers($id) → id sans '/' (TMDB numérique?), tentative recherche par titre")
                    return searchServersByTitle(id, videoType)
                }
                val (tvShowId, rel) = parts

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
                // 2026-07-07 : id TMDB numérique → searchServersByTitle
                if (id.matches(Regex("^\\d+$"))) {
                    Log.w("Wiflix", "getServers(movie $id) → TMDB numérique, tentative recherche par titre")
                    return searchServersByTitle(id, videoType)
                }
                val document = try {
                    val doc = service.getMovie(id)
                    Log.i("ServDiagT", "GETSERVERS service.getMovie fini à ${System.currentTimeMillis()-_tGS}ms (challenge=${isCloudflareChallenge(doc)})")
                    if (isCloudflareChallenge(doc)) getDocument("${baseUrl}film-en-streaming/$id") else doc
                } catch (e: Exception) { getDocument("${baseUrl}film-en-streaming/$id") }
                Log.i("ServDiagT", "GETSERVERS document prêt à ${System.currentTimeMillis()-_tGS}ms")

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

        Log.i("ServDiagT", "GETSERVERS servers parsés (${servers.size}) à ${System.currentTimeMillis()-_tGS}ms")
        // Remove duplicate servers (same service on different domains)
        val unique = deduplicateServers(servers)
        Log.i("ServDiagT", "GETSERVERS dedup fait à ${System.currentTimeMillis()-_tGS}ms")

        // Sort: VF/FR first, then by service reliability, VOSTFR last
        val _sorted = unique.sortedWith(compareBy<Video.Server> { server ->
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
        Log.i("ServDiagT", "GETSERVERS sort fait à ${System.currentTimeMillis()-_tGS}ms")
        return _sorted
    }

    override fun getServersProgressive(
        id: String, videoType: Video.Type,
    ): Flow<List<Video.Server>> = channelFlow {
        initializeService()
        // 2026-06-03 (user "il devrait y avoir QUE des serveurs normal qui
        //   arrive normalement") : backups Movix/Cloudstream DÉSACTIVÉS.
        //   On garde uniquement le scrape natif Wiflix qui est fiable (ID
        //   newsid → page Wiflix → embeds réels du bon film).
        launch {
            try {
                val native = getServers(id, videoType)
                if (native.isNotEmpty()) send(native)
            } catch (e: Exception) { Log.w("Wiflix", "Progressive native failed: ${e.message}") }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
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
                // 2026-07-07 : on VIDE le cache bloqué AVANT onChangeUrl. Sinon, si le
                //   scraping du portail échoue (CF challenge sur le portail = cas connu),
                //   le catch garde l'ancien cache mort et le getter baseUrl continue de
                //   renvoyer le domaine bloqué → provider cassé à vie. En vidant, le getter
                //   retombe sur defaultBaseUrl (flemmix.gold) même si le scrape échoue.
                UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, "")
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
