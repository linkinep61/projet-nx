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
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // 2026-06-09 : UA dynamique = celui du WebView Android pour que les
    //   cookies cf_clearance obtenus dans la WebView soient acceptés par
    //   OkHttp (Cloudflare lie le cookie au User-Agent).
    private val UA: String by lazy {
        try {
            android.webkit.WebSettings.getDefaultUserAgent(StreamFlixApp.instance)
        } catch (_: Throwable) {
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        }
    }

    // 2026-06-09 : CookieJar partagé avec WebView (NetworkClient.cookieJar
    //   proxie vers WebKit CookieManager). Quand l'user complète le Cloudflare
    //   Turnstile dans la WebView, le cookie cf_clearance est automatiquement
    //   disponible pour OkHttp.
    private val cookieJar = NetworkClient.cookieJar

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .cache(null) // 2026-06-09 : pas de cache HTTP (CF Turnstile rend les pages challenge cachées nocives)
            .build()
    }

    // 2026-06-09 : bypass Cloudflare Turnstile (pattern AnimeSama).
    //   cfBypassDone est PERSISTÉ en SharedPrefs avec un TTL pour ne pas
    //   redemander le captcha à chaque restart de l'app (le cookie cf_clearance
    //   reste valide ~30 min-2 h côté serveur CF).
    @Volatile private var cfBypassDoneRam = false
    private val cfBypassMutex = Mutex()
    // 2026-06-09 v3 (OOM-kill VmPeak 1.9 GB) : sérialise toutes les WebView
    //   silent fetches pour éviter 8 WebViews // ~100-200 MB chacune = OOM.
    private val webViewFetchMutex = Mutex()
    private const val PREF_CF_BYPASS = "dessinanime_cf_bypass"
    private const val KEY_CF_BYPASS_TS = "cf_bypass_ts"
    // 2026-06-09 (user "comme Google, garde un moment quand même") : 2 h = max
    //   raisonnable côté Cloudflare (cookie cf_clearance valide 30 min à 2 h
    //   selon config du site). Si CF refuse avant les 2 h → silent retourne
    //   page challenge → re-bypass auto (déjà géré, plus de boucle infinie).
    private const val CF_BYPASS_TTL_MS = 2 * 60 * 60 * 1000L // 2 h

    private val cfBypassDone: Boolean
        get() {
            if (cfBypassDoneRam) return true
            return try {
                val prefs = StreamFlixApp.instance.getSharedPreferences(PREF_CF_BYPASS, android.content.Context.MODE_PRIVATE)
                val ts = prefs.getLong(KEY_CF_BYPASS_TS, 0L)
                val fresh = ts > 0 && (System.currentTimeMillis() - ts) < CF_BYPASS_TTL_MS
                if (fresh) {
                    cfBypassDoneRam = true
                    Log.d(TAG, "cfBypassDone restored from prefs (age=${(System.currentTimeMillis() - ts) / 1000}s)")
                }
                fresh
            } catch (_: Throwable) { false }
        }

    private fun markCfBypassDone() {
        cfBypassDoneRam = true
        try {
            StreamFlixApp.instance
                .getSharedPreferences(PREF_CF_BYPASS, android.content.Context.MODE_PRIVATE)
                .edit().putLong(KEY_CF_BYPASS_TS, System.currentTimeMillis()).apply()
        } catch (_: Throwable) {}
    }
    private var webViewResolver: WebViewResolver? = null
    private val challengeKeywords = listOf(
        "Just a moment...", "cf-browser-verification", "challenge-running",
        "Checking your browser", "cf-turnstile", "cloudflare"
    )

    /**
     * 2026-06-09 : utiliser l'Activity courante (foreground) plutôt que
     * l'ApplicationContext. Sinon dialog.show() throws BadTokenException
     * ("token null is not valid; is your activity running?").
     * Le resolver est re-créé si l'Activity courante a changé depuis.
     */
    private fun getResolver(): WebViewResolver {
        val act = StreamFlixApp.currentActivity ?: StreamFlixApp.instance
        // 2026-06-09 v2 : re-check si l'Activity courante est valide (pas
        //   isFinishing/isDestroyed) → sinon BadTokenException quand on essaye
        //   d'afficher le dialog.
        val activityValid = try {
            (act as? android.app.Activity)?.let { !it.isFinishing && !it.isDestroyed } ?: true
        } catch (_: Throwable) { false }
        val current = webViewResolver
        if (current == null || lastResolverActivity !== act || !activityValid) {
            val safeAct = if (activityValid) act else StreamFlixApp.instance
            webViewResolver = WebViewResolver(safeAct)
            lastResolverActivity = safeAct
        }
        return webViewResolver!!
    }

    @Volatile private var lastResolverActivity: android.content.Context? = null

    /** Bypass CF UNE SEULE FOIS. Va DIRECT au dialog visible si pas déjà fait. */
    private suspend fun ensureCfBypassed() {
        if (cfBypassDone) return
        cfBypassMutex.withLock {
            if (cfBypassDone) return
            // 2026-06-09 (user "le challenge cloud fire est apparu alors que
            //   j'étais plus dans le provider") : ne pas afficher de dialog
            //   si l'user a quitté DessinAnime.
            if (UserPreferences.currentProvider?.name != "DessinAnime") {
                Log.w(TAG, "ensureCfBypassed skipped — l'user a quitté DessinAnime")
                return
            }
            // 2026-06-09 v2 (user "j'attends déjà depuis au moins 30 secondes") :
            //   on saute le test silent qui prenait 30s à timeout sur ce site
            //   (CF demande toujours interaction → silent ne réussit jamais).
            //   Direct au dialog visible.
            Log.d(TAG, "Bypass CF : dialog visible direct (silent skip)")
            val html = getResolver().get(baseUrl, silent = false)
            val cookies = android.webkit.CookieManager.getInstance().getCookie(baseUrl) ?: ""
            val hasClearance = cookies.contains("cf_clearance")
            val hasContent = html.contains("_next/") || html.contains("dessinanime") || html.length > 5000
            if (hasContent || hasClearance) {
                Log.d(TAG, "Bypass CF RÉUSSI (${html.length} chars, clearance=$hasClearance)")
                markCfBypassDone()
            } else {
                Log.w(TAG, "Bypass CF ÉCHOUÉ (${html.length} chars)")
            }
        }
    }

    /**
     * 2026-06-09 (user "les cookies CF doivent rester dans le cache et le home
     *   se recharger à chaque ouverture, vider le cache à la fermeture") :
     *   - GARDE le timestamp cfBypassDone persisté (les cookies CF/cf_clearance
     *     dans WebKit CookieManager restent valides, pas de re-dialog).
     *   - VIDE pageCache + HomeCacheStore pour forcer un re-fetch frais au
     *     prochain getHome (sinon on garde l'ancien home obsolète).
     *   - GARDE cfBypassDoneRam=true si déjà true (pour skip OkHttp direct
     *     au prochain fetch).
     */
    fun resetState() {
        pageCache.clear()
        invalidateCfBypass()
        try { webViewResolver?.cleanup() } catch (_: Throwable) {}
        webViewResolver = null
        lastResolverActivity = null
        // 2026-06-09 v2 (user "chargement progressif, affichage instantané du
        //   home si possible") : on GARDE HomeCacheStore = cache disque
        //   persistant entre sessions. Au prochain accès, le home s'affiche
        //   INSTANT depuis le cache puis refresh en background.
        Log.d(TAG, "State reset (pageCache + cfBypass + WebViewResolver vidés ; HomeCache GARDÉ pour chargement instant)")
    }

    /**
     * 2026-06-09 (user "au lieu d'atteindre un chargement éternel à l'ouverture
     *   faut que ça aille direct sur la page Cloudfire si elle est demandée") :
     *   À appeler au moment où l'user sélectionne DessinAnime depuis le picker.
     *   Si le bypass est encore valide (TTL pas expiré) → no-op silencieux.
     *   Sinon → déclenche immédiatement ensureCfBypassed() en background, ce
     *   qui affiche le dialog Turnstile DIRECTEMENT au lieu d'attendre que le
     *   1er fetch du home échoue (~3-5s de plus visible "chargement éternel").
     */
    fun prefetchCfBypassIfNeeded() {
        if (cfBypassDone) {
            Log.d(TAG, "prefetchCfBypassIfNeeded: déjà fait → skip")
            return
        }
        Log.d(TAG, "prefetchCfBypassIfNeeded: lancement bypass en background")
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try { ensureCfBypassed() }
            catch (e: Exception) { Log.w(TAG, "prefetch bypass KO: ${e.message}") }
        }
    }

    // ── HTTP helper ────────────────────────────────────────────────────

    /**
     * 2026-06-09 v2 (user "j'ai pas l'impression qu'elle va vraiment chercher
     *   des cookies qu'on a déjà téléchargé") : cache HTML PERSISTANT par URL.
     *   - En RAM (ConcurrentHashMap) : accès O(1) instant pendant la session
     *   - Sur disque (SharedPrefs) : survit aux redémarrages → home + synopsis
     *     instant à la prochaine ouverture
     *   TTL 1h pour les pages dynamiques (catalogue), 24h pour les pages
     *   stables (synopsis film/série qui ne changent quasi jamais).
     */
    private data class CachedHtml(val html: String, val ts: Long)
    private val pageCache = java.util.concurrent.ConcurrentHashMap<String, CachedHtml>()
    private val PAGE_CACHE_TTL_MS = 60 * 60 * 1000L // 1h
    private const val PREF_PAGE_CACHE = "dessinanime_page_cache"

    private fun loadPageFromDisk(url: String): String? {
        return try {
            val prefs = StreamFlixApp.instance.getSharedPreferences(PREF_PAGE_CACHE, android.content.Context.MODE_PRIVATE)
            val ts = prefs.getLong("ts_$url", 0L)
            if (ts == 0L) return null
            // TTL 24h pour pages film/série (URL contient /movie/ ou /tv/ avec slug)
            // TTL 1h pour pages catalogue / home
            val ttl = if (url.contains("/movie/") || url.contains("/tv/")) 24 * 60 * 60 * 1000L else PAGE_CACHE_TTL_MS
            if (System.currentTimeMillis() - ts > ttl) return null
            prefs.getString("html_$url", null)
        } catch (_: Throwable) { null }
    }

    private fun savePageToDisk(url: String, html: String) {
        try {
            // Ne cache que les pages utiles (< 500kb pour éviter d'exploser SharedPrefs)
            if (html.length > 500_000) return
            val prefs = StreamFlixApp.instance.getSharedPreferences(PREF_PAGE_CACHE, android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putString("html_$url", html)
                .putLong("ts_$url", System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {}
    }

    private suspend fun httpGet(url: String): String? {
        // 2026-06-13 (user "la recherche dessin animé provider ne fonctionne
        //   pas" + diag log : WebView silent retourne 33 chars sur /api/search,
        //   puis API shortcut retourne 1545 chars mais "search parse failed:
        //   ..." = signature GZIP not decoded) :
        //   Pour les endpoints `/api/...`, on shortcut OkHttp direct ET on
        //   force la decompression GZIP côté lecture (= httpGetRaw envoie
        //   Accept-Encoding: gzip manuellement, donc OkHttp ne decompresse
        //   pas auto). On fait un GET dedie sans Accept-Encoding → OkHttp
        //   gere transparent.
        if (url.contains("/api/")) {
            val direct = httpGetApi(url)
            if (!direct.isNullOrBlank() && direct.length > 20) {
                Log.d(TAG, "httpGet API shortcut OkHttp pour $url (${direct.length} chars)")
                return direct
            }
            Log.w(TAG, "httpGet API shortcut OkHttp echec pour $url, fallback path classique")
        }
        // 2026-06-13 (user "DessinAnime aucun chargement page vide") :
        //   helper soft-block detection. CF/Clerk sert un HTML d'attente
        //   (shell Next.js 28-32 KB sans données) sans challenge keyword →
        //   on l'acceptait à tort dans le cache. Si ce soft block est
        //   cached sur disque (TTL 1h-24h), DessinAnime reste cassé tout
        //   ce temps. On vérifie les markers du contenu pour invalider
        //   le cache si nécessaire.
        fun hasValidContent(html: String): Boolean {
            if (html.length < 500) return false
            if (!html.contains("_next/static/chunks/")) return false
            return html.contains("article") ||
                html.contains("href=\"/movie/") ||
                html.contains("href=\"/tv/") ||
                html.contains("__NEXT_DATA__")
        }

        // Cache hit RAM ?
        pageCache[url]?.let { cached ->
            if (System.currentTimeMillis() - cached.ts < PAGE_CACHE_TTL_MS) {
                if (hasValidContent(cached.html)) return cached.html
                Log.w(TAG, "httpGet $url : cache RAM SOFT BLOCK (${cached.html.length} chars) → invalide")
                pageCache.remove(url)
            }
        }
        // 2026-06-09 v2 : cache DISK ? (persiste entre sessions)
        loadPageFromDisk(url)?.let { diskHtml ->
            if (hasValidContent(diskHtml)) {
                Log.d(TAG, "httpGet $url : cache HIT disque (${diskHtml.length} chars)")
                pageCache[url] = CachedHtml(diskHtml, System.currentTimeMillis())
                return diskHtml
            }
            // Cache disque pollué par du soft block → invalide + continue
            //   vers le fetch HTTP + bypass CF.
            Log.w(TAG, "httpGet $url : cache DISQUE SOFT BLOCK (${diskHtml.length} chars) → invalide + re-fetch")
            try {
                val prefs = StreamFlixApp.instance.getSharedPreferences(PREF_PAGE_CACHE, android.content.Context.MODE_PRIVATE)
                prefs.edit().remove("ts_$url").remove("html_$url").apply()
            } catch (_: Throwable) {}
        }

        // 2026-06-09 : si le bypass CF a déjà été fait, on SKIP OkHttp direct
        //   (qui se prend systématiquement 403 sur dessinanime.cc à cause du
        //   TLS fingerprint OkHttp ≠ Chrome, malgré cookie + UA + headers OK).
        //   On va direct au WebView qui est le seul à passer CF Bot Mgmt.
        if (!cfBypassDone) {
            // 1) Tentative OkHttp directe : rapide si pas de CF actif sur ce site.
            val firstTry = httpGetRaw(url)
            if (firstTry != null) {
                val isChallenge = challengeKeywords.any { firstTry.contains(it, ignoreCase = true) }
                // 2026-06-13 (user "DessinAnime ne fonctionne pas aucun
                //   chargement sur mobile") : détection du soft block CF/Clerk.
                //   Avant, on acceptait toute page sans challenge keyword qui
                //   faisait >500 chars. Mais CF/Clerk sert un HTML d'attente
                //   (= shell Next.js sans données) qui ne contient pas de
                //   challenge keyword → on l'acceptait à tort et le parsing
                //   retournait 0 items. Maintenant on vérifie aussi la
                //   présence de markers du contenu (_next/static/chunks =
                //   le bundle SPA, article/film/série dans le HTML). Si
                //   absent → considéré comme soft block → bypass visible.
                val hasContentMarkers = firstTry.contains("_next/static/chunks/") &&
                    (firstTry.contains("article") || firstTry.contains("href=\"/movie/") ||
                     firstTry.contains("href=\"/tv/") || firstTry.contains("__NEXT_DATA__"))
                if (!isChallenge && firstTry.length > 500 && hasContentMarkers) {
                    pageCache[url] = CachedHtml(firstTry, System.currentTimeMillis())
                    savePageToDisk(url, firstTry)
                    return firstTry
                }
                Log.d(TAG, "CF challenge ou soft block détecté sur $url (isChallenge=$isChallenge, hasContent=$hasContentMarkers, len=${firstTry.length}) → bypass visible")
            }
            // 2) Bypass CF visible (une seule fois — l'user complète le Turnstile).
            ensureCfBypassed()
        }

        // 3) WebView silent (rapide si cookies valides).
        //   2026-06-09 v3 : sérialisé via webViewFetchMutex (anti-OOM).
        val htmlFirst = try {
            Log.d(TAG, "WebView silent pour $url")
            webViewFetchMutex.withLock { getResolver().get(url, silent = true) }
        } catch (e: Exception) {
            Log.w(TAG, "WebView silent échoué pour $url: ${e.message}")
            ""
        }
        // 2026-06-09 v3 : on accepte le HTML dès qu'il est "gros" (>2k chars).
        //   Avant on rejetait à cause du mot "cloudflare" dans les scripts
        //   analytics CF normaux → re-bypass faussement déclenché sur 250 KB
        //   de HTML valide. On checke maintenant uniquement les marqueurs
        //   FORTS de challenge actif (pas juste "cloudflare").
        // 2026-06-09 v7 : detector challenge avec keywords FR + EN. Le challenge
        //   CF sur dessinanime.cc est rendu en FRANÇAIS ("Un instant…") car le
        //   serveur sert la page localisée. Mon check anglais le ratait.
        val strongChallengeKeywords = listOf(
            // EN
            "Just a moment...", "cf-browser-verification", "challenge-running",
            "Checking your browser",
            // FR
            "Un instant", "Veuillez patienter", "Vérification",
            "Vérifie votre navigateur"
        )
        val isActiveChallenge = strongChallengeKeywords.any {
            htmlFirst.contains(it, ignoreCase = true)
        }
        // 2026-06-09 v8 : 3 signaux de VRAI contenu — au moins un suffit.
        //   - hasRealLinks : page catalogue/home avec liens vers movie/tv
        //   - hasNextData : page Next.js correctement rendue (présent partout)
        //   - hasIframe   : page film/épisode avec lecteur vidéo intégré
        //   Avant on exigeait hasRealLinks UNIQUEMENT → dialog jamais fermé
        //   sur les pages film individuelles (qui n'ont pas href="/movie/").
        val hasRealLinks = htmlFirst.contains("href=\"/movie/") || htmlFirst.contains("href=\"/tv/")
        val hasNextData = htmlFirst.contains("__NEXT_DATA__") || htmlFirst.contains("_next/static")
        val hasIframe = htmlFirst.contains("<iframe", ignoreCase = true) &&
            !htmlFirst.contains("challenges.cloudflare.com", ignoreCase = true)
        val hasContent = hasRealLinks || hasNextData || hasIframe
        Log.d(TAG, "WebView silent result for $url: length=${htmlFirst.length} " +
            "challenge=$isActiveChallenge hasLinks=$hasRealLinks hasNextData=$hasNextData hasIframe=$hasIframe")
        // 2026-06-09 v9 : si HTML > 50k chars → c'est la VRAIE page (challenge
        //   ≤ 30k). On accepte peu importe le keyword "Un instant" qui peut
        //   apparaître dans un script footer normal.
        val isHugePage = htmlFirst.length > 50_000
        if (htmlFirst.length > 500 && hasContent && (!isActiveChallenge || isHugePage)) {
            pageCache[url] = CachedHtml(htmlFirst, System.currentTimeMillis())
            savePageToDisk(url, htmlFirst)
            return htmlFirst
        }

        // 4) Silent a renvoyé page challenge (cookie partiellement valide).
        //    Au lieu de boucler en silent, on fait UN fetch direct WebView
        //    visible SUR L'URL exacte qu'on veut.
        // 2026-06-09 (user "le challenge cloud fire est apparu alors que
        //   j'étais plus dans le provider") : ne PAS afficher le dialog si
        //   l'user a quitté DessinAnime entre-temps.
        if (UserPreferences.currentProvider?.name != "DessinAnime") {
            Log.w(TAG, "Silent insuffisant mais l'user a quitté DessinAnime → skip dialog")
            return null
        }
        // 2026-06-09 v3 (user "pourquoi je me tape une page de vérification
        //   cloudfire comme ça") : si silent retourne 33 chars (= Global Timeout
        //   WebView Resolver) ou très peu, c'est que la WebView a timeout, pas
        //   que le cookie est périmé. On abandonne SANS re-bypass pour éviter
        //   la boucle infinie (Timeout → re-bypass → Timeout → re-bypass).
        if (htmlFirst.length < 100) {
            Log.w(TAG, "Silent retourne ${htmlFirst.length} chars (timeout WebView) → abandon, pas de re-bypass")
            return null
        }
        Log.w(TAG, "Silent insuffisant (${htmlFirst.length} chars) → re-bypass sur baseUrl")
        invalidateCfBypass()

        // 5) Re-bypass sur baseUrl puis retry silent (sérialisé anti-OOM).
        try { ensureCfBypassed() } catch (_: Exception) {}
        return try {
            val htmlRetry = webViewFetchMutex.withLock { getResolver().get(url, silent = true) }
            if (htmlRetry.length > 500) {
                pageCache[url] = CachedHtml(htmlRetry, System.currentTimeMillis())
                savePageToDisk(url, htmlRetry)
                htmlRetry
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Retry silent échoué pour $url: ${e.message}")
            null
        }
    }

    /** Invalide le bypass (RAM + persisté) pour forcer un nouveau dialog. */
    private fun invalidateCfBypass() {
        cfBypassDoneRam = false
        try {
            StreamFlixApp.instance
                .getSharedPreferences(PREF_CF_BYPASS, android.content.Context.MODE_PRIVATE)
                .edit().remove(KEY_CF_BYPASS_TS).apply()
        } catch (_: Throwable) {}
    }

    /** 2026-06-13 : GET dedie aux endpoints `/api/...` (= JSON pur, pas de CF
     *  challenge). Sans header Accept-Encoding manuel → OkHttp gere auto la
     *  decompression GZIP. Pas de Sec-Fetch headers (= eviter le filtrage
     *  "navigation" sur un endpoint API). */
    private suspend fun httpGetApi(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", baseUrl)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET API $url → ${resp.code}")
                    null
                } else resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET API $url failed: ${e.message}")
            null
        }
    }

    /** GET OkHttp brut, sans bypass. Helper interne pour httpGet. */
    private suspend fun httpGetRaw(url: String): String? = withContext(Dispatchers.IO) {
        try {
            // 2026-06-09 : headers Chrome COMPLETS pour mimer le navigateur au max
            //   (sinon Cloudflare Bot Management détecte OkHttp via fingerprint).
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", baseUrl)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Sec-Ch-Ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                .header("Sec-Ch-Ua-Mobile", "?1")
                .header("Sec-Ch-Ua-Platform", "\"Android\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .build()
            // Debug : log les cookies envoyés (1 fois sur 10 pour ne pas spam)
            try {
                val ck = android.webkit.CookieManager.getInstance().getCookie(url) ?: "(none)"
                Log.d(TAG, "GET $url cookies=${ck.take(150)}... UA=${UA.take(60)}...")
            } catch (_: Throwable) {}
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
        // 2026-06-09 (user "le chargement du Home après le bypass est toujours
        //   long") : ne fetch QUE la page d'accueil (1 fetch WebView ≈ 6s).
        //   Avant : home + catalogue1 = 2 fetches séquentiels (mutex) = ~20s.
        //   Le catalogue arrive via pagination au scroll → home instant.
        val home = async { parseHomeItems(httpGet("$baseUrl/") ?: "") }
        val pages = listOf(home.await(), emptyList<AppAdapter.Item>())

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
    // 2026-06-09 : réduit de 8 à 3 — en mode WebView (TLS fingerprint
    //   force tout par WebView avec mutex), 8 pages parallèles bloquent
    //   le clic user sur une jaquette pendant ~64s. À 3, c'est ~24s max
    //   et le clic est servi rapidement après.
    private val PAGES_PER_USER_PAGE = 3

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

        // Backups TOUJOURS lancés (même si natifs présents) — l'ancien early-return
        // coupait les backups dès qu'un seul serveur natif existait.

        val title = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }.trim()
        if (title.isBlank()) return@withContext native

        // Extraire le tmdbId du slug DessinAnime (ex: "movie::269149-zootopie" → "269149")
        // pour que les backups Cloudstream/Nakios/Movix cherchent par tmdbId direct.
        val tmdbId = id.split("::").getOrNull(1)
            ?.substringBefore("-")
            ?.takeIf { it.all(Char::isDigit) && it.length >= 2 }
            ?: "0"
        Log.d(TAG, "backups: title='$title', tmdbId=$tmdbId (from id=$id)")

        val backups = try {
            coroutineScope {
                // Backup #1 : Cloudstream (films/séries FR via TMDB, inclut Movix+Nakios)
                val csD = async {
                    try {
                        val csType: Video.Type = when (videoType) {
                            is Video.Type.Movie -> Video.Type.Movie(
                                id = tmdbId, title = title, releaseDate = videoType.releaseDate,
                                poster = videoType.poster, imdbId = videoType.imdbId,
                            )
                            is Video.Type.Episode -> Video.Type.Episode(
                                id = tmdbId, number = videoType.number, title = videoType.title,
                                poster = videoType.poster, overview = videoType.overview,
                                tvShow = videoType.tvShow.copy(id = tmdbId, title = title),
                                season = videoType.season,
                            )
                        }
                        (kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                            CloudstreamProvider.getServers(tmdbId, csType)
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
                // Backup #3 : Wiflix (films/séries FR, VF + VOSTFR)
                val wfD = async {
                    try {
                        fetchWiflixBackup(title, videoType)
                    } catch (e: Exception) { Log.w(TAG, "WF backup KO: ${e.message}"); emptyList() }
                }
                // Backup #4 : NetMirror (Netflix/Prime/Disney+ mirrors)
                val nmD = async {
                    try {
                        (kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                            NetMirrorProvider.getServers(tmdbId, videoType)
                        } ?: emptyList()).map {
                            it.copy(id = "nmbackup__${it.id}", name = "NM · ${it.name}")
                        }
                    } catch (e: Exception) { Log.w(TAG, "NM backup KO: ${e.message}"); emptyList() }
                }
                // 2026-06-09 v2 : retiré FS/MX/MB (doublons — déjà dans CS).
                csD.await() + asD.await() + wfD.await() + nmD.await()
            }
        } catch (e: Exception) { Log.w(TAG, "backups KO: ${e.message}"); emptyList() }

        Log.d(TAG, "getServers: ${native.size} natifs + ${backups.size} backups (CS+AS)")
        native + backups
    }

    // ── Chargement PROGRESSIF (2026-05-21) ─────────────────────────────
    //   Natifs émis tout de suite, puis Cloudstream et AnimeSama émis
    //   dès qu'ils répondent, en parallèle — pas d'attente bloquante.
    override fun getServersProgressive(id: String, videoType: Video.Type): Flow<List<Video.Server>> = channelFlow {
        val title = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }.trim()

        // 2026-06-09 (user "regarde ce que t'as fait sur les autres providers") :
        //   déduplication par URL src — pattern repris de MovixProvider seenUrls.
        //   Chaque backup envoie seulement les sources dont l'URL n'a pas déjà
        //   été émise. Évite Movix·X et CS·Movix·X qui pointent vers la même
        //   vidéo (CS inclut Movix en interne).
        val seenUrls = java.util.Collections.synchronizedSet(HashSet<String>())
        fun List<Video.Server>.dedup(): List<Video.Server> =
            this.filter { srv -> srv.src.isNotBlank() && seenUrls.add(srv.src) }

        // 1) Natifs — lancés en parallèle, émis quand prêts
        val nativeJob = launch(Dispatchers.IO) {
            try {
                val native = fetchNativeServers(id, videoType).dedup()
                if (native.isNotEmpty()) send(native)
            } catch (e: Exception) { Log.w(TAG, "progressive native KO: ${e.message}") }
        }
        // Si pas de titre on s'arrête après le natif.
        if (title.isBlank()) { nativeJob.join(); return@channelFlow }

        // Extraire le tmdbId du slug DessinAnime pour les backups
        val tmdbId = id.split("::").getOrNull(1)
            ?.substringBefore("-")
            ?.takeIf { it.all(Char::isDigit) && it.length >= 2 }
            ?: "0"

        // VideoType pour Cloudstream (tmdbId extrait du slug)
        val csType: Video.Type = when (videoType) {
            is Video.Type.Movie -> Video.Type.Movie(
                id = tmdbId, title = title, releaseDate = videoType.releaseDate,
                poster = videoType.poster, imdbId = videoType.imdbId,
            )
            is Video.Type.Episode -> Video.Type.Episode(
                id = tmdbId, number = videoType.number, title = videoType.title,
                poster = videoType.poster, overview = videoType.overview,
                tvShow = videoType.tvShow.copy(id = tmdbId, title = title),
                season = videoType.season,
            )
        }

        // Backups en parallèle — chacun émet dès qu'il finit
        val csJob = launch(Dispatchers.IO) {
            try {
                val servers = (kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    CloudstreamProvider.getServers(tmdbId, csType)
                } ?: emptyList()).map { it.copy(id = "csbackup__${it.id}", name = "CS · ${it.name}") }.dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive CS backup KO: ${e.message}") }
        }
        val asJob = launch(Dispatchers.IO) {
            try {
                val servers = (kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    AnimeSamaProvider.getAnimeSamaSourcesByTitle(title, videoType)
                } ?: emptyList()).map { it.copy(id = "asbackup__${it.id}", name = "AS · ${it.name}") }.dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive AS backup KO: ${e.message}") }
        }
        // Backup #3 : Wiflix
        val wfJob = launch(Dispatchers.IO) {
            try {
                val servers = fetchWiflixBackup(title, videoType).dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive WF backup KO: ${e.message}") }
        }
        // Backup #4 : NetMirror
        val nmJob = launch(Dispatchers.IO) {
            try {
                val servers = (kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                    NetMirrorProvider.getServers(tmdbId, videoType)
                } ?: emptyList()).map { it.copy(id = "nmbackup__${it.id}", name = "NM · ${it.name}") }.dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive NM backup KO: ${e.message}") }
        }
        // 2026-06-09 v3 : re-ajout FS/MX/MB avec déduplication par URL.
        val fsJob = launch(Dispatchers.IO) {
            try {
                val servers = fetchFrenchStreamBackup(title, videoType).dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive FS backup KO: ${e.message}") }
        }
        val mxJob = launch(Dispatchers.IO) {
            try {
                val servers = (kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                    MovixProvider.getServers(tmdbId, videoType)
                } ?: emptyList()).map { it.copy(id = "mxbackup__${it.id}", name = "MX · ${it.name}") }.dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive MX backup KO: ${e.message}") }
        }
        val mbJob = launch(Dispatchers.IO) {
            try {
                val type = if (videoType is Video.Type.Movie) 1 else 2
                val servers = (kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                    MovieboxProvider.getMovieboxSourcesByTitle(
                        title, null, type,
                        seasonNumber = if (videoType is Video.Type.Episode) videoType.season.number else null,
                        episodeNumber = if (videoType is Video.Type.Episode) videoType.number else null,
                    )
                } ?: emptyList()).map { it.copy(id = "mbbackup__${it.id}", name = "MB · ${it.name}") }.dedup()
                if (servers.isNotEmpty()) send(servers)
            } catch (e: Exception) { Log.w(TAG, "progressive MB backup KO: ${e.message}") }
        }
        nativeJob.join()
        csJob.join()
        asJob.join()
        wfJob.join()
        nmJob.join()
        fsJob.join()
        mxJob.join()
        mbJob.join()
    }

    /**
     * Backup Wiflix : recherche par titre → getServers du 1er match.
     * Timeout 10s. Préfixe "wfbackup__".
     */
    private suspend fun fetchWiflixBackup(
        title: String,
        videoType: Video.Type,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        val results = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            WiflixProvider.search(title, 1)
        } ?: return@withContext emptyList()

        // 2026-06-09 (user "Wiflix Backup match avec des films qui ne sont pas
        //   bons") : matching strict. Avant on prenait le 1er résultat brut →
        //   "Mario" matchait avec n'importe quel film contenant "Mario" dans
        //   le titre. Maintenant on score sur similarité titre + année.
        fun normalizeForCompare(s: String): String = s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        val wantTitle = normalizeForCompare(title)
        val wantYear: Int? = when (videoType) {
            is Video.Type.Movie -> videoType.releaseDate.take(4).toIntOrNull()
            is Video.Type.Episode -> videoType.tvShow.releaseDate?.take(4)?.toIntOrNull()
        }

        fun scoreCandidate(candidateTitle: String?, candidateYear: Int?): Int {
            if (candidateTitle.isNullOrBlank()) return -1000
            val cTitle = normalizeForCompare(candidateTitle)
            // Titre EXACT = +100, contient le souhaité comme mot = +60,
            //   l'un dans l'autre (substring) = +30, sinon 0.
            val titleScore = when {
                cTitle == wantTitle -> 100
                cTitle.split(" ").containsAll(wantTitle.split(" ")) -> 60
                cTitle.contains(wantTitle) || wantTitle.contains(cTitle) -> 30
                else -> {
                    // Vérifie au moins 50% des mots du wantTitle présents dans cTitle.
                    val wantWords = wantTitle.split(" ").filter { it.length >= 3 }
                    if (wantWords.isEmpty()) return -100
                    val matched = wantWords.count { cTitle.contains(it) }
                    if (matched.toDouble() / wantWords.size >= 0.5) 10 else -100
                }
            }
            // Année : ±1 = bonus, ±2 = neutre, ≥3 = grosse pénalité.
            val yearScore = when {
                wantYear == null || candidateYear == null -> 0
                kotlin.math.abs(candidateYear - wantYear) <= 1 -> 30
                kotlin.math.abs(candidateYear - wantYear) == 2 -> 0
                else -> -60 // écart ≥3 ans = faux positif probable
            }
            return titleScore + yearScore
        }

        val matchId: String = when (videoType) {
            is Video.Type.Movie -> {
                val candidates = results.filterIsInstance<Movie>()
                val best = candidates
                    .map { it to scoreCandidate(it.title, it.released?.get(java.util.Calendar.YEAR)) }
                    .filter { it.second >= 50 } // seuil minimum pour valider le match
                    .maxByOrNull { it.second }
                Log.d(TAG, "WF backup match Movie '$title' (year=$wantYear) → " +
                    "${best?.first?.title} score=${best?.second}")
                best?.first?.id
            }
            is Video.Type.Episode -> {
                val candidates = results.filterIsInstance<TvShow>()
                val best = candidates
                    .map { it to scoreCandidate(it.title, it.released?.get(java.util.Calendar.YEAR)) }
                    .filter { it.second >= 50 }
                    .maxByOrNull { it.second }
                Log.d(TAG, "WF backup match TvShow '$title' (year=$wantYear) → " +
                    "${best?.first?.title} score=${best?.second}")
                best?.first?.id
            }
        } ?: run {
            Log.d(TAG, "WF backup: aucun match valable pour '$title'")
            return@withContext emptyList()
        }

        val wfServers = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            WiflixProvider.getServers(matchId, videoType)
        } ?: emptyList()

        wfServers.map {
            it.copy(id = "wfbackup__${it.id}", name = "WF · ${it.name}")
        }
    }

    /**
     * 2026-06-09 : backup FrenchStream — recherche par titre, sélection du
     *   meilleur match (titre+année), getServers. Identique au pattern Wiflix.
     */
    private suspend fun fetchFrenchStreamBackup(
        title: String,
        videoType: Video.Type,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        val results = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            FrenchStreamProvider.search(title, 1)
        } ?: return@withContext emptyList()

        fun normalizeForCompare(s: String): String = s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val wantTitle = normalizeForCompare(title)
        val wantYear: Int? = when (videoType) {
            is Video.Type.Movie -> videoType.releaseDate.take(4).toIntOrNull()
            is Video.Type.Episode -> videoType.tvShow.releaseDate?.take(4)?.toIntOrNull()
        }

        fun scoreCandidate(candidateTitle: String?, candidateYear: Int?): Int {
            if (candidateTitle.isNullOrBlank()) return -1000
            val cTitle = normalizeForCompare(candidateTitle)
            val titleScore = when {
                cTitle == wantTitle -> 100
                cTitle.split(" ").containsAll(wantTitle.split(" ")) -> 60
                cTitle.contains(wantTitle) || wantTitle.contains(cTitle) -> 30
                else -> {
                    val wantWords = wantTitle.split(" ").filter { it.length >= 3 }
                    if (wantWords.isEmpty()) return -100
                    val matched = wantWords.count { cTitle.contains(it) }
                    if (matched.toDouble() / wantWords.size >= 0.5) 10 else -100
                }
            }
            val yearScore = when {
                wantYear == null || candidateYear == null -> 0
                kotlin.math.abs(candidateYear - wantYear) <= 1 -> 30
                kotlin.math.abs(candidateYear - wantYear) == 2 -> 0
                else -> -60
            }
            return titleScore + yearScore
        }

        val matchId: String = when (videoType) {
            is Video.Type.Movie -> {
                val candidates = results.filterIsInstance<Movie>()
                candidates
                    .map { it to scoreCandidate(it.title, it.released?.get(java.util.Calendar.YEAR)) }
                    .filter { it.second >= 50 }
                    .maxByOrNull { it.second }?.first?.id
            }
            is Video.Type.Episode -> {
                val candidates = results.filterIsInstance<TvShow>()
                candidates
                    .map { it to scoreCandidate(it.title, it.released?.get(java.util.Calendar.YEAR)) }
                    .filter { it.second >= 50 }
                    .maxByOrNull { it.second }?.first?.id
            }
        } ?: run {
            Log.d(TAG, "FS backup: aucun match pour '$title'")
            return@withContext emptyList()
        }

        val fsServers = kotlinx.coroutines.withTimeoutOrNull(12_000L) {
            FrenchStreamProvider.getServers(matchId, videoType)
        } ?: emptyList()

        Log.d(TAG, "FS backup: ${fsServers.size} sources pour '$title'")
        fsServers.map {
            it.copy(id = "fsbackup__${it.id}", name = "FS · ${it.name}")
        }
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
            // 2026-05-23 : Player4me désactivé (API "Video not found" sur tout)
            if (url.contains("4meplayer")) return@forEach
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
        if (server.id.startsWith("wfbackup__")) {
            return WiflixProvider.getVideo(server.copy(id = server.id.removePrefix("wfbackup__")))
        }
        if (server.id.startsWith("nmbackup__")) {
            return NetMirrorProvider.getVideo(server.copy(id = server.id.removePrefix("nmbackup__")))
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
