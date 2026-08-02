package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.CatalogFilter
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TMDb3.w780
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.extractors.Extractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * NetMirror — provider qui agrège Netflix, Prime Video, Hotstar et Disney+
 * via le backend miroir net52.cc / net22.cc.
 *
 * **Catalogue** : 100% TMDB filtré par plateformes de streaming (Netflix,
 * Prime Video, Disney+) disponibles en France → contenu international
 * avec audio FR (VF/VOSTFR) quasi systématique.
 *
 * **Lecture** : backend NetMirror (net52.cc) via le système "NewTV" API
 * (domaines rotatifs mobidetect*). On cherche le titre TMDB sur les 4 OTT
 * mirrors et on retourne les liens M3U8 trouvés.
 *
 * IDs :
 *   - Movie / TvShow : TMDB id (entier)
 *   - Season : "<tmdbId>-<seasonNumber>"
 *   - Episode : "<tmdbId>:<seasonNumber>:<episodeNumber>"
 */
object NetMirrorProvider : Provider, ProgressiveServersProvider {

    override val name = "NetMirror"
    override val baseUrl = "https://api.themoviedb.org/3/"
    override val language = "fr"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_netmirror"

    private const val TAG = "NetMirrorProvider"

    // ══════════════════════════════════════════════════════════════════════
    //  NetMirror backend — auth + API
    // ══════════════════════════════════════════════════════════════════════

    private const val MAIN_URL = "https://net52.cc"
    private const val ALT_URL = "https://net22.cc"
    private const val VERIFY_URL = "https://net52.cc/verify.php"
    private const val IMG_CDN = "https://imgcdn.kim"

    /** 2026-07-08 (décompilation app officielle) : l'app officielle suffixe son User-Agent WebView
     *  avec « OS.Gatu v3.0 » (via applicationNameForUserAgent). C'est vraisemblablement LE marqueur
     *  qui l'identifie au serveur et débloque le vrai film (pas de masquage, pas d'err 1003, pas de
     *  Turnstile dur). On l'ajoute donc à NOTRE UA partout où on parle au backend NetMirror. */
    private val NM_UA = com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_UA + " /OS.Gatu v3.0"

    /** 2026-07-08 : le serveur NetMirror natif ouvre le lecteur WebView embarqué (player officiel)
     *  au lieu d'ExoPlayer. Mis à FALSE = retour à l'état audio+pub UTILISABLE (le lecteur WebView
     *  charge bien net52/mobile/home?app=1 via le bootstrap check.php, MAIS cette page = « Page Not
     *  Found » — recette web-app OK (UA /OS.Gatu + X-Requested-With vide + pont ReactNativeWebView),
     *  MAIS vidéo encore masquée (token serveur). false = état audio+pub utilisable en attendant. */
    private const val NM_WEBVIEW_PLAYER = false

    /** Cookie de session t_hash_t (durée de vie ~15h). */
    @Volatile private var cachedCookie: String? = null
    @Volatile private var cookieExpiry: Long = 0L
    private const val COOKIE_TTL_MS = 54_000_000L // 15h

    /** URL de l'API NewTV résolue via checknewtv.php. */
    @Volatile private var resolvedApiUrl: String? = null
    @Volatile private var apiUrlExpiry: Long = 0L
    private const val API_URL_TTL_MS = 3_600_000L // 1h

    /** OTT platforms avec leur cookie ott + préfixes d'URL. */
    enum class OttPlatform(
        val ottCookie: String,
        val label: String,
        val searchPath: String,
        val postPath: String,
        val episodesPath: String,
        val playlistPath: String,
        val posterPrefix: String,
        val bannerPrefix: String,
        val epImgPrefix: String,
    ) {
        NETFLIX(
            ottCookie = "nf",
            label = "Netflix",
            searchPath = "/mobile/search.php",
            postPath = "/mobile/post.php",
            episodesPath = "/mobile/episodes.php",
            playlistPath = "/playlist.php",
            posterPrefix = "/poster/v/",
            bannerPrefix = "/poster/h/",
            epImgPrefix = "/epimg/150/",
        ),
        PRIME_VIDEO(
            ottCookie = "pv",
            label = "Prime Video",
            searchPath = "/mobile/pv/search.php",
            postPath = "/mobile/pv/post.php",
            episodesPath = "/mobile/pv/episodes.php",
            playlistPath = "/pv/playlist.php",
            posterPrefix = "/pv/v/",
            bannerPrefix = "/pv/h/",
            epImgPrefix = "/pvepimg/",
        ),
        HOTSTAR(
            ottCookie = "hs",
            label = "Hotstar",
            searchPath = "/mobile/hs/search.php",
            postPath = "/mobile/hs/post.php",
            episodesPath = "/mobile/hs/episodes.php",
            playlistPath = "/hs/playlist.php",
            posterPrefix = "/hs/v/",
            bannerPrefix = "/hs/h/",
            epImgPrefix = "/hsepimg/150/",
        ),
        DISNEY_PLUS(
            ottCookie = "dp",
            label = "Disney+",
            searchPath = "/mobile/hs/search.php",
            postPath = "/mobile/hs/post.php",
            episodesPath = "/mobile/hs/episodes.php",
            playlistPath = "/hs/playlist.php",
            posterPrefix = "/hs/v/",
            bannerPrefix = "/hs/h/",
            epImgPrefix = "/hsepimg/150/",
        );
    }

    /** Domaines NewTV à essayer pour résoudre l'API de streaming.
     *  .art vérifié fonctionnel (2026-05-22). Les autres peuvent timeout
     *  ou NXDOMAIN selon la géo — on itère jusqu'au 1er qui répond. */
    private val NEWTV_DOMAINS: List<String> by lazy {
        val priorityTlds = listOf(".art", ".cc", ".ink", ".pro", ".vip", ".wiki")
        val otherTlds = listOf(".com", ".app", ".click", ".live", ".shop",
            ".site", ".space", ".store", ".xyz")
        val bases = listOf("mobidetect", "mobidetects")
        // .art en premier (confirmé OK), puis les autres
        bases.flatMap { b -> (priorityTlds + otherTlds).map { t -> "https://$b$t" } }
    }

    private val httpClient: OkHttpClient by lazy {
        Extractor.sharedClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private val httpClientFollowRedirects: OkHttpClient by lazy {
        httpClient.newBuilder().followRedirects(true).build()
    }

    // ── Auth : bypass reCAPTCHA ─────────────────────────────────────────

    /** 2026-07-08 (anti « Too Many Requests ») : sérialise verify.php. Les 4 plateformes
     *  OTT appellent ensureCookie() EN PARALLÈLE → sans mutex, 4 POST verify.php partent
     *  d'un coup (rafale) et déclenchent l'anti-abus de NetMirror (« STOP Abuse »), même
     *  sur une IP fraîche. Avec le mutex : 1 seul verify.php, les 3 autres réutilisent le
     *  cache. Divise le volume de requêtes par ~2 à chaque ouverture de titre. */
    private val cookieMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Obtient un cookie de session t_hash_t en postant un faux token reCAPTCHA.
     * Le cookie est mis en cache 15h.
     */
    private suspend fun ensureCookie(): String = withContext(Dispatchers.IO) {
        cachedCookie?.let { if (System.currentTimeMillis() < cookieExpiry) return@withContext it }

        cookieMutex.withLock {
            // Re-check après acquisition : un appel parallèle a pu déjà récupérer le cookie.
            cachedCookie?.let { if (System.currentTimeMillis() < cookieExpiry) return@withLock it }

            // 2026-07-08 (anti « STOP Abuse ») : cookie PERSISTÉ en SharedPrefs. En mémoire il
            //   était perdu à chaque redémarrage/réinstall → on refaisait verify.php à CHAQUE
            //   ouverture (des dizaines par jour). Comme l'app officielle garde sa session, on
            //   recharge un cookie encore valide (<15h) du disque → 1 seul verify.php / 15h.
            runCatching {
                val sp = com.streamflixreborn.streamflix.StreamFlixApp.instance
                    .getSharedPreferences("netmirror_session", android.content.Context.MODE_PRIVATE)
                val saved = sp.getString("t_hash_t", null)
                val savedExp = sp.getLong("expiry", 0L)
                if (saved != null && System.currentTimeMillis() < savedExp) {
                    cachedCookie = saved
                    cookieExpiry = savedExp
                    Log.d(TAG, "cookie rechargé du disque (valide, pas de verify.php)")
                }
            }
            cachedCookie?.let { if (System.currentTimeMillis() < cookieExpiry) return@withLock it }

            val now = System.currentTimeMillis()
            val sp = com.streamflixreborn.streamflix.StreamFlixApp.instance
                .getSharedPreferences("netmirror_session", android.content.Context.MODE_PRIVATE)
            // Dernier cookie connu SANS regarder l'expiry (fallback si verify.php bloqué).
            val stalePersisted = runCatching { sp.getString("t_hash_t", null) }.getOrNull()

            // 2026-07-08 (DÉCOUVERTE CLÉ, vérifiée en direct) : verify.php est le SEUL endpoint
            //   rate-limité (→ 403 « STOP Abuse »). search.php / playlist.php répondent 200 avec
            //   un cookie même ANCIEN. L'app officielle ne rappelle jamais verify.php ; nous, on
            //   le martelait → 403 → plus de cookie → tout cassait. FIX : si verify.php échoue,
            //   on RETOMBE sur le dernier cookie persisté (search/playlist continuent de marcher).
            val fresh: String? = runCatching {
                val body = FormBody.Builder()
                    .add("g-recaptcha-response", UUID.randomUUID().toString())
                    .build()
                val req = Request.Builder()
                    .url(VERIFY_URL)
                    .post(body)
                    .header("User-Agent", NM_UA)
                    .header("Origin", MAIN_URL)
                    .header("Referer", "$MAIN_URL/")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null
                    else r.headers("Set-Cookie").firstNotNullOfOrNull { c ->
                        if (c.startsWith("t_hash_t=")) c.substringBefore(";") else null
                    }
                }
            }.getOrNull()

            // 2026-07-08 (FIX RACINE) : l'OkHttp verify.php se prend un 403 (CF managed challenge
            //   sur verify.php, contexte non-navigateur). search.php/playlist.php, EUX, passent en
            //   OkHttp (code 200 vérifié). Donc : si l'OkHttp verify échoue, on récupère t_hash_t
            //   via une WebView invisible (moteur navigateur → passe le challenge, POST verify.php
            //   avec un faux token = accepté, prouvé dans Chrome). C'est EXACTEMENT ce que fait
            //   l'app officielle en interne (WebView anti-bot, pas de compte). Une fois t_hash_t
            //   récupéré, search/playlist repartent en OkHttp direct.
            val harvested: String? = if (fresh == null) {
                Log.d(TAG, "verify.php OkHttp KO → harvest t_hash_t via WebView…")
                runCatching { harvestViaWebView() }.getOrNull()
            } else null

            when {
                fresh != null -> {
                    cachedCookie = fresh
                    cookieExpiry = now + COOKIE_TTL_MS
                    runCatching {
                        sp.edit().putString("t_hash_t", fresh).putLong("expiry", now + COOKIE_TTL_MS).apply()
                    }
                    Log.d(TAG, "bypass() OK — cookie frais OkHttp (persisté 15h)")
                    fresh
                }
                // t_hash_t récupéré via WebView (chaîne "t_hash_t=…"). Persisté 15h comme un frais.
                harvested != null -> {
                    val clean = harvested.substringBefore(";")
                    cachedCookie = clean
                    cookieExpiry = now + COOKIE_TTL_MS
                    runCatching {
                        sp.edit().putString("t_hash_t", clean).putLong("expiry", now + COOKIE_TTL_MS).apply()
                    }
                    Log.d(TAG, "cookie t_hash_t via WebView (persisté 15h)")
                    clean
                }
                // Dernier recours : cookie persisté même périmé (search/playlist tolèrent).
                stalePersisted != null -> {
                    cachedCookie = stalePersisted
                    cookieExpiry = now + 3_600_000L
                    Log.w(TAG, "verify.php 403 + harvest KO → réutilisation cookie persisté")
                    stalePersisted
                }
                else -> throw IllegalStateException(
                    "NetMirror: verify.php 403 et harvest WebView KO et aucun cookie persisté — réessaie"
                )
            }
        }
    }

    /**
     * Récolte t_hash / t_hash_p en chargeant net77 dans une WebView PROPRE (pas de stealth —
     * le Turnstile la traite comme un vrai navigateur, ce qui maximise le passage). Attachée
     * à l'activité (le Turnstile a besoin d'être rendu). Poll CookieManager jusqu'à 60s.
     * Retourne "t_hash=…; t_hash_p=…" ou null.
     */
    private suspend fun harvestViaWebView(): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            // 2026-07-08 (PROUVÉ EN DIRECT) : charger net52.cc RACINE dans la WebView REDIRIGE
            //   vers net77.cc (le site "web" avec login) où verify.php = 403. Le chemin qui NE
            //   redirige PAS et reste anonyme = /mobile/ (c'est là que l'onglet Chrome du user
            //   marchait). On charge donc net52.cc/mobile/ → la WebView reste sur net52 → le
            //   fetch same-origin '/verify.php' pose t_hash_t. cm.getCookie(harvestBase) lit
            //   les cookies du domaine net52.cc.
            val harvestBase = "https://net52.cc/mobile/"
            mainHandler.post {
                var webView: android.webkit.WebView? = null
                var finished = false
                val cm = android.webkit.CookieManager.getInstance()
                fun finish(result: String?) {
                    if (finished) return
                    finished = true
                    runCatching {
                        webView?.let { w ->
                            w.stopLoading()
                            (w.parent as? android.view.ViewGroup)?.removeView(w)
                            w.destroy()
                        }
                    }
                    webView = null
                    if (cont.isActive) cont.resume(result) {}
                }
                try {
                    cm.setAcceptCookie(true)
                    val wv = android.webkit.WebView(ctx)
                    webView = wv
                    runCatching { cm.setAcceptThirdPartyCookies(wv, true) }
                    // Config IDENTIQUE à WebViewResolver (qui passe le CF de Wiflix en invisible) :
                    //   UA stealth Chrome 131 + STEALTH_JS injecté au démarrage ET à la fin (React
                    //   ré-hydrate → il faut ré-injecter). Avec ce fingerprint, Cloudflare traite
                    //   la WebView comme un vrai navigateur → Turnstile en mode INVISIBLE (pas de
                    //   case interactive), donc pas besoin de cliquer.
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = NM_UA
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        loadWithOverviewMode = true
                        useWideViewPort = true
                    }
                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageStarted(view: android.webkit.WebView?, u: String?, f: android.graphics.Bitmap?) {
                            runCatching { view?.evaluateJavascript(com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_JS, null) }
                        }
                        override fun onPageFinished(view: android.webkit.WebView?, u: String?) {
                            runCatching { view?.evaluateJavascript(com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_JS, null) }
                        }
                    }
                    // 2026-07-08 (user : « la pub s'affiche devant le film ») : l'approche faux-token
                    //   n'a PAS besoin que la WebView soit rendue/visible/cliquable (aucun Turnstile
                    //   à résoudre — verify.php accepte le faux token). On l'attache donc en 1×1 px,
                    //   alpha 0 (totalement transparente) ET hors-écran → l'user ne voit RIEN, mais
                    //   la WebView exécute quand même son JS/fetch et pose t_hash_t.
                    runCatching {
                        val activity = com.streamflixreborn.streamflix.StreamFlixApp.currentActivity
                        activity?.addContentView(wv, android.widget.FrameLayout.LayoutParams(1, 1))
                        wv.alpha = 0f              // transparent → invisible même si à l'écran
                        wv.translationX = -100000f // + hors-écran (ceinture + bretelles)
                        wv.translationY = -100000f
                    }
                    val density = ctx.resources.displayMetrics.density
                    // Tap natif à des coords CSS (dans le repère LOCAL de la WebView, indépendant
                    //   de sa position écran → marche même hors-écran).
                    fun tapCss(cssX: Float, cssY: Float) {
                        val px = cssX * density
                        val py = cssY * density
                        val t = android.os.SystemClock.uptimeMillis()
                        val d = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, px, py, 0)
                        val u = android.view.MotionEvent.obtain(t, t + 90, android.view.MotionEvent.ACTION_UP, px, py, 0)
                        runCatching { wv.dispatchTouchEvent(d); wv.dispatchTouchEvent(u) }
                        d.recycle(); u.recycle()
                    }
                    val start = System.currentTimeMillis()
                    val poll = object : Runnable {
                        override fun run() {
                            if (finished) return
                            val raw = cm.getCookie(harvestBase) ?: ""
                            val tHashT = raw.split(";").map { it.trim() }
                                .firstOrNull { it.startsWith("t_hash_t=") }
                            if (tHashT != null) {
                                cm.flush()
                                Log.d(TAG, "harvest OK — t_hash_t récupéré via WebView")
                                finish(tHashT)
                                return
                            }
                            // 2026-07-08 (VÉRIFIÉ EN DIRECT DANS CHROME) : verify.php accepte un
                            //   FAUX token et pose t_hash_t (il ne valide PAS le recaptcha). Le 403
                            //   OkHttp vient du contexte non-navigateur, PAS du token. Depuis la
                            //   WebView, le faux token passe. evaluateJavascript NE PEUT PAS await
                            //   une Promise (retour '{}'), donc on FIRE le POST en fire-and-forget et
                            //   on stocke le statut dans window.__vs. On RE-POSTE chaque tour tant que
                            //   t_hash_t n'est pas posé (le 1er POST peut tomber pendant que net52
                            //   n'est pas encore chargé / challenge CF) — PAS de garde one-shot. Le
                            //   retour synchrone donne host|readyState|statut pour diagnostiquer.
                            wv.evaluateJavascript(
                                """
                                (function(){try{
                                  if(window.__vs!=='OK'){
                                    var fake=(Math.random()+'').slice(2)+(Math.random()+'').slice(2)+(Math.random()+'').slice(2);
                                    var b=new URLSearchParams();b.set('g-recaptcha-response',fake);
                                    fetch('/verify.php',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b.toString(),credentials:'include'})
                                      .then(function(r){window.__vs=(r.status===200?'OK':('P'+r.status));})
                                      .catch(function(e){window.__vs='ERR';});
                                  }
                                  return (location.host||'?')+'|'+document.readyState+'|'+(window.__vs||'-');
                                }catch(e){return 'JSERR'}})()
                                """.trimIndent()
                            ) { r -> if (!r.isNullOrBlank()) Log.d(TAG, "harvest state → ${r.trim('"').take(40)}") }
                            // AUTO-CLIC MASQUÉ de la case CF/Turnstile : on localise l'iframe
                            //   Cloudflare (interstitiel « Vérification de sécurité » OU turnstile)
                            //   et on tape la case (~30px depuis la gauche, centre vertical) via un
                            //   VRAI événement tactile natif. Répété à chaque tour jusqu'à résolution.
                            wv.evaluateJavascript(
                                "(function(){var f=document.querySelector('iframe[src*=\"challenges.cloudflare\"]')||document.querySelector('iframe[title*=\"Cloudflare\"]')||document.querySelector('iframe[title*=\"humain\"]')||document.querySelector('iframe[title*=\"human\"]')||document.querySelector('iframe');if(!f)return '';var r=f.getBoundingClientRect();return r.left+','+r.top+','+r.width+','+r.height;})()"
                            ) { res ->
                                val p = (res ?: "").trim('"').split(",")
                                if (p.size == 4) {
                                    val left = p[0].toFloatOrNull() ?: 0f
                                    val top = p[1].toFloatOrNull() ?: 0f
                                    val hh = p[3].toFloatOrNull() ?: 65f
                                    if (hh > 5f) tapCss(left + 30f, top + hh / 2f)
                                }
                            }
                            // Faux token accepté immédiatement → t_hash_t sous ~1-2s. Timeout 25s.
                            if (System.currentTimeMillis() - start > 25_000L) finish(null)
                            else mainHandler.postDelayed(this, 1_000L)
                        }
                    }
                    mainHandler.postDelayed(poll, 3_000L)
                    wv.loadUrl(harvestBase)
                    cont.invokeOnCancellation { mainHandler.post { finish(null) } }
                } catch (e: Throwable) {
                    Log.w(TAG, "harvestViaWebView error: ${e.message}")
                    finish(null)
                }
            }
        }

    /** Construit la chaîne de cookies pour une plateforme OTT donnée.
     *  2026-07-08 (RE-VÉRIFIÉ EN DIRECT DANS CHROME — correction d'une fausse piste) :
     *  contrairement à ce que je croyais, net52.cc/search.php N'EST PAS anonyme au sens utile.
     *  SANS le cookie de session t_hash_t, search.php IGNORE la requête et renvoie TOUJOURS
     *  la liste "Top Searches" par défaut (Vincenzo, If W…) → aucun match → aucun serveur.
     *  AVEC t_hash_t (obtenu via verify.php, un simple faux token recaptcha = ACCOUNTLESS,
     *  PAS un compte), head passe à "Movies & TV" et on obtient les VRAIS résultats par requête
     *  (prouvé : "Batman" → Batman Ninja/… ; "Vincenzo" → Vincenzo). L'app officielle garde
     *  cette session. ensureCookie() met en cache 15h + persiste sur disque + Mutex → verify.php
     *  n'est appelé qu'une fois (pas de rafale → pas de 403). Ordre : session AVANT ott/hd. */
    /** 2026-07-08 : cookie de session t_hash_t exposé pour le lecteur WebView (net52/mobile/home
     *  a besoin de la session sinon « Page Not Found »). Accountless. */
    suspend fun sessionCookie(): String? = runCatching { ensureCookie() }.getOrNull()

    private suspend fun buildCookies(platform: OttPlatform): String {
        val session = ensureCookie()
        return "$session; ott=${platform.ottCookie}; hd=on"
    }

    /**
     * 2026-07-09 : force la re-récupération du cookie de session (t_hash_t) quand le serveur le
     * refuse (réponse « Top Searches » / « Enter Some Words to Search » = requête ignorée). On vide
     * le cache mémoire ET l'expiry persistée (sinon ensureCookie rechargerait le cookie périmé du
     * disque) → le prochain ensureCookie repasse par verify.php / harvest WebView.
     */
    private suspend fun invalidateCookie() {
        cookieMutex.withLock {
            cachedCookie = null
            cookieExpiry = 0L
            runCatching {
                com.streamflixreborn.streamflix.StreamFlixApp.instance
                    .getSharedPreferences("netmirror_session", android.content.Context.MODE_PRIVATE)
                    .edit().putLong("expiry", 0L).apply()
            }
        }
    }

    // ── Résolution API NewTV ────────────────────────────────────────────

    /**
     * Résout l'URL de l'API NewTV en itérant sur les domaines mobidetect*.
     * Cache le résultat 1h.
     */
    private suspend fun resolveApiUrl(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        resolvedApiUrl?.let { if (now < apiUrlExpiry) return@withContext it }

        for (domain in NEWTV_DOMAINS) {
            try {
                val url = "$domain/checknewtv.php"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("X-Requested-With", "NetmirrorNewTV v1.0")
                    .build()

                val resp = httpClientFollowRedirects.newCall(req).execute()
                resp.use { r ->
                    if (!r.isSuccessful) return@use
                    val body = r.body?.string() ?: return@use
                    val json = runCatching { JSONObject(body) }.getOrNull() ?: return@use
                    val tokenHash = json.optString("token_hash").takeIf { it.isNotBlank() }
                        ?: return@use
                    // Decode base64 → URL de l'API réelle
                    val decoded = String(Base64.decode(tokenHash, Base64.DEFAULT)).trim()
                    if (decoded.startsWith("http")) {
                        resolvedApiUrl = decoded
                        apiUrlExpiry = now + API_URL_TTL_MS
                        Log.d(TAG, "resolveApiUrl() OK via $domain → $decoded")
                        return@withContext decoded
                    }
                }
            } catch (e: Exception) {
                // Essayer le domaine suivant
            }
        }
        throw IllegalStateException("NetMirror: impossible de résoudre l'API NewTV (24 domaines testés)")
    }

    // ── Recherche sur le backend NetMirror ─────────────────────────────

    /** Normalise un titre pour la comparaison (lowercase, trim, strip unicode). */
    private fun normalizeTitle(raw: String): String =
        TitleNormalizer.stripUnicodeArtifacts(raw).lowercase().trim()

    /** Cache des IDs NetMirror par titre : "titre_normalisé" → Map<OttPlatform, netmirrorId> */
    private val idCache = ConcurrentHashMap<String, Map<OttPlatform, String>>()

    /**
     * Cherche un titre sur toutes les plateformes OTT du backend NetMirror.
     * Retourne une map plateforme → ID NetMirror pour les plateformes qui ont ce contenu.
     */
    private suspend fun searchOnNetMirror(
        title: String,
        year: Int? = null,
    ): Map<OttPlatform, String> = coroutineScope {
        val cacheKey = normalizeTitle(title) + (year?.let { "_$it" } ?: "")
        idCache[cacheKey]?.let { return@coroutineScope it }

        val results = ConcurrentHashMap<OttPlatform, String>()
        val ts = System.currentTimeMillis() / 1000

        OttPlatform.entries.map { platform ->
            async(Dispatchers.IO) {
                try {
                    val cookies = buildCookies(platform)
                    val url = "$MAIN_URL${platform.searchPath}?s=${java.net.URLEncoder.encode(title, "UTF-8")}&t=$ts"
                    val req = Request.Builder()
                        .url(url)
                        .header("Cookie", cookies)
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Referer", "$MAIN_URL/")
                        .build()

                    val resp = httpClientFollowRedirects.newCall(req).execute()
                    resp.use { r ->
                        if (!r.isSuccessful) return@async
                        val body = r.body?.string() ?: return@async
                        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@async
                        val searchResults = json.optJSONArray("searchResult") ?: return@async

                        // 2026-07-07 : matching strict via BackupRegistry.titleMatches
                        // (remplace contains bidir sans garde + fallback single-result aveugle)
                        for (i in 0 until searchResults.length()) {
                            val item = searchResults.optJSONObject(i) ?: continue
                            val resultTitle = item.optString("t")
                            val resultId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                            if (com.streamflixreborn.streamflix.utils.BackupRegistry.titleMatches(resultTitle, title)) {
                                results[platform] = resultId
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "searchOnNetMirror ${platform.label} error: ${e.message}")
                }
            }
        }.awaitAll()

        if (results.isNotEmpty()) {
            idCache[cacheKey] = results.toMap()
        }
        Log.d(TAG, "searchOnNetMirror '$title' → ${results.map { "${it.key.label}:${it.value}" }}")
        results.toMap()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Catalogue — TMDB (copie conforme de CloudstreamProvider)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Filtre par plateforme de streaming dispo en France (Netflix, Prime, Disney+).
     * Contrairement aux autres providers qui filtrent par langue d'origine, NetMirror
     * filtre par "disponible sur ces plateformes en France" — ces contenus ont
     * quasi-toujours le VF/VOSTFR car Netflix/Prime/Disney+ doublent en FR.
     */
    private fun nmWatchProviders(): TMDb3.Params.WithBuilder<TMDb3.Provider.WatchProviderId> =
        TMDb3.Params.WithBuilder<TMDb3.Provider.WatchProviderId>(TMDb3.Provider.WatchProviderId.NETFLIX)
            .or(TMDb3.Provider.WatchProviderId.AMAZON_PRIME_VIDEO_TIER_B)
            .or(TMDb3.Provider.WatchProviderId.DISNEY_PLUS)

    /** Filtre additionnel par langue d'origine (CatalogFilter choisi par l'user).
     *  null = pas de restriction de langue (= contenu mondial). Se combine
     *  AVEC le filtre watch providers ci-dessus. */
    private fun nmOriginalLanguageBuilder(): TMDb3.Params.WithBuilder<String>? {
        val lang = CatalogFilter.originalLanguage(name)
        return lang?.let { TMDb3.Params.WithBuilder(it) }
    }

    private val mapMovie: (TMDb3.Movie) -> Movie = { m ->
        Movie(
            id = m.id.toString(),
            title = m.title,
            overview = m.overview,
            released = m.releaseDate,
            rating = m.voteAverage.toDouble(),
            poster = m.posterPath?.w500,
            banner = m.backdropPath?.w1280,
            providerName = name,
        )
    }
    private val mapTv: (TMDb3.Tv) -> TvShow = { t ->
        TvShow(
            id = t.id.toString(),
            title = t.name,
            overview = t.overview,
            released = t.firstAirDate,
            rating = t.voteAverage.toDouble(),
            poster = t.posterPath?.w500,
            banner = t.backdropPath?.w1280,
            providerName = name,
        )
    }

    private fun TMDb3.MultiItem.toAppItem(): AppAdapter.Item? = when (this) {
        is TMDb3.Movie -> Movie(
            id = id.toString(),
            title = title,
            overview = overview,
            released = releaseDate,
            rating = voteAverage.toDouble(),
            poster = posterPath?.w500,
            banner = backdropPath?.w1280,
        )
        is TMDb3.Tv -> TvShow(
            id = id.toString(),
            title = name,
            overview = overview,
            released = firstAirDate,
            rating = voteAverage.toDouble(),
            poster = posterPath?.w500,
            banner = backdropPath?.w1280,
        )
        else -> null
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        // 2026-07-08 : plus de warm-up cookie — net52 est anonyme, aucun verify.php nécessaire.
        val trendingD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = 1, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                    voteCount = TMDb3.Params.Range(50, null),
                ).results
            }.getOrDefault(emptyList())
        }
        val popularMoviesD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = 1, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                ).results
            }.getOrDefault(emptyList())
        }
        val topMoviesD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = 1, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.VOTE_AVERAGE_DESC,
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                    voteCount = TMDb3.Params.Range(200, null),
                ).results
            }.getOrDefault(emptyList())
        }
        val popularTvD = async {
            runCatching {
                TMDb3.Discover.tv(
                    page = 1, language = language,
                    sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                ).results
            }.getOrDefault(emptyList())
        }
        val topTvD = async {
            runCatching {
                TMDb3.Discover.tv(
                    page = 1, language = language,
                    sortBy = TMDb3.Params.SortBy.Tv.VOTE_AVERAGE_DESC,
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                    voteCount = TMDb3.Params.Range(50, null),
                ).results
            }.getOrDefault(emptyList())
        }
        val newMoviesD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = 1, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                    voteCount = TMDb3.Params.Range(10, null),
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                ).results
            }.getOrDefault(emptyList())
        }
        val newTvD = async {
            runCatching {
                TMDb3.Discover.tv(
                    page = 1, language = language,
                    sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,
                    voteCount = TMDb3.Params.Range(10, null),
                    watchRegion = "FR",
                    withWatchProviders = nmWatchProviders(),
                    withOriginalLanguage = nmOriginalLanguageBuilder(),
                ).results
            }.getOrDefault(emptyList())
        }

        val categories = mutableListOf<Category>()

        // Featured (carrousel)
        val trending = trendingD.await()
        if (trending.isNotEmpty()) {
            categories.add(Category(name = Category.FEATURED, list = trending.take(15).map(mapMovie)))
        }

        // Nouveaux films
        val newMovies = newMoviesD.await()
        if (newMovies.isNotEmpty()) {
            categories.add(Category(name = "Nouveaux films", list = newMovies.map(mapMovie)))
        }

        // Nouvelles séries
        val newTv = newTvD.await()
        if (newTv.isNotEmpty()) {
            categories.add(Category(name = "Nouvelles séries", list = newTv.map(mapTv)))
        }

        // Films populaires
        val popularMovies = popularMoviesD.await()
        if (popularMovies.isNotEmpty()) {
            categories.add(Category(name = "Films populaires", list = popularMovies.map(mapMovie)))
        }

        // Films les mieux notés
        val topMovies = topMoviesD.await()
        if (topMovies.isNotEmpty()) {
            categories.add(Category(name = "Films les mieux notés", list = topMovies.map(mapMovie)))
        }

        // Séries populaires
        val popularTv = popularTvD.await()
        if (popularTv.isNotEmpty()) {
            categories.add(Category(name = "Séries populaires", list = popularTv.map(mapTv)))
        }

        // Séries les mieux notées
        val topTv = topTvD.await()
        if (topTv.isNotEmpty()) {
            categories.add(Category(name = "Séries les mieux notées", list = topTv.map(mapTv)))
        }

        categories
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return runCatching {
            // 2026-08-04 — filtre d'année (second clic sur « Films », cf. YearFilter).
            val plage = com.streamflixreborn.streamflix.utils.YearFilter
                .get(name, com.streamflixreborn.streamflix.utils.YearFilter.Type.FILMS)
            TMDb3.Discover.movie(
                page = page, language = language, region = "FR",
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                primaryReleaseDate = TMDb3.Params.Range(
                    gte = com.streamflixreborn.streamflix.utils.YearFilter.borneBasse(plage),
                    lte = com.streamflixreborn.streamflix.utils.YearFilter.borneHaute(plage),
                ),
                watchRegion = "FR",
                withWatchProviders = nmWatchProviders(),
                withOriginalLanguage = nmOriginalLanguageBuilder(),
            ).results.map(mapMovie)
        }.getOrDefault(emptyList())
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return runCatching {
            val plage = com.streamflixreborn.streamflix.utils.YearFilter
                .get(name, com.streamflixreborn.streamflix.utils.YearFilter.Type.SERIES)
            TMDb3.Discover.tv(
                page = page, language = language,
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                firstAirDate = TMDb3.Params.Range(
                    gte = com.streamflixreborn.streamflix.utils.YearFilter.borneBasse(plage),
                    lte = com.streamflixreborn.streamflix.utils.YearFilter.borneHaute(plage),
                ),
                watchRegion = "FR",
                withWatchProviders = nmWatchProviders(),
                withOriginalLanguage = nmOriginalLanguageBuilder(),
            ).results.map(mapTv)
        }.getOrDefault(emptyList())
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            // 2026-08-04 (demande user) : plateformes + Nouveautés AVANT les genres.
            //   Logique partagée avec Cloudstream et Movix — cf. VodCategories.
            return com.streamflixreborn.streamflix.utils.VodCategories.enTete() + listOf(
                Genre(id = "28", name = "Action"),
                Genre(id = "12", name = "Aventure"),
                Genre(id = "16", name = "Animation"),
                Genre(id = "35", name = "Comédie"),
                Genre(id = "80", name = "Crime"),
                Genre(id = "99", name = "Documentaire"),
                Genre(id = "18", name = "Drame"),
                Genre(id = "10751", name = "Famille"),
                Genre(id = "14", name = "Fantaisie"),
                Genre(id = "27", name = "Horreur"),
                Genre(id = "10402", name = "Musique"),
                Genre(id = "9648", name = "Mystère"),
                Genre(id = "10749", name = "Romance"),
                Genre(id = "878", name = "Science-Fiction"),
                Genre(id = "53", name = "Thriller"),
                Genre(id = "10752", name = "Guerre"),
                Genre(id = "37", name = "Western"),
            )
        }
        return runCatching {
            val cleanQuery = TitleNormalizer.cleanForTmdbSearch(query).ifBlank { query }
            TMDb3.Search.multi(cleanQuery, language = language, page = page).results.mapNotNull { item ->
                when (item) {
                    is TMDb3.Movie -> mapMovie(item)
                    is TMDb3.Tv -> mapTv(item)
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Détails — TMDB (copie conforme de CloudstreamProvider)
    // ══════════════════════════════════════════════════════════════════════

    override suspend fun getMovie(id: String): Movie {
        val tmdbId = id.toIntOrNull() ?: return Movie(id = id, title = "", providerName = name)
        return runCatching {
            val m = TMDb3.Movies.details(
                movieId = tmdbId,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Movie.CREDITS,
                    TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                    TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
                ),
                language = language,
            )
            Movie(
                id = m.id.toString(),
                title = m.title,
                overview = m.overview,
                released = m.releaseDate,
                runtime = m.runtime,
                trailer = m.videos?.results?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = m.voteAverage.toDouble(),
                poster = m.posterPath?.w780,
                banner = m.backdropPath?.w1280,
                imdbId = m.externalIds?.imdbId,
                genres = m.genres.map { Genre(it.id.toString(), it.name) },
                cast = m.credits?.cast?.map { c ->
                    People(id = c.id.toString(), name = c.name, image = c.profilePath?.w500)
                } ?: emptyList(),
                recommendations = m.recommendations?.results?.mapNotNull {
                    it.toAppItem() as? com.streamflixreborn.streamflix.models.Show
                } ?: emptyList(),
                providerName = name,
            )
        }.getOrElse { Movie(id = id, title = "", providerName = name) }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val tmdbId = id.toIntOrNull() ?: return TvShow(id = id, title = "", providerName = name)
        return runCatching {
            val tv = TMDb3.TvSeries.details(
                seriesId = tmdbId,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Tv.CREDITS,
                    TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                    TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
                ),
                language = language,
            )
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                trailer = tv.videos?.results?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.w780,
                banner = tv.backdropPath?.w1280,
                imdbId = tv.externalIds?.imdbId,
                seasons = tv.seasons.map { s ->
                    Season(
                        id = "${tv.id}-${s.seasonNumber}",
                        number = s.seasonNumber,
                        title = s.name,
                        poster = s.posterPath?.w500,
                    )
                },
                genres = tv.genres.map { Genre(it.id.toString(), it.name) },
                cast = tv.credits?.cast?.map { c ->
                    People(id = c.id.toString(), name = c.name, image = c.profilePath?.w500)
                } ?: emptyList(),
                recommendations = tv.recommendations?.results?.mapNotNull {
                    it.toAppItem() as? com.streamflixreborn.streamflix.models.Show
                } ?: emptyList(),
                providerName = name,
            )
        }.getOrElse { TvShow(id = id, title = "", providerName = name) }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("-")
        if (parts.size != 2) return emptyList()
        val tvId = parts[0].toIntOrNull() ?: return emptyList()
        val seNum = parts[1].toIntOrNull() ?: return emptyList()
        return runCatching {
            TMDb3.TvSeasons.details(seriesId = tvId, seasonNumber = seNum, language = language)
                .episodes?.map {
                    Episode(
                        id = "$tvId:$seNum:${it.episodeNumber}",
                        number = it.episodeNumber,
                        title = it.name ?: "",
                        released = it.airDate,
                        poster = it.stillPath?.w500,
                    )
                } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        // 2026-08-04 : catégories plateformes/Nouveautés — traitées avant la logique de genre.
        if (com.streamflixreborn.streamflix.utils.VodCategories.estCategorieSpeciale(id)) {
            return com.streamflixreborn.streamflix.utils.VodCategories.charger(id, page, language)
        }
        val originCountry = when (id.lowercase()) {
            "k-drama", "drama-coreen" -> "KR"
            else -> null
        }
        return runCatching {
            val tmdbGenreId = id.toIntOrNull()
            val withOrigin: TMDb3.Params.WithBuilder<String>? = originCountry?.let {
                TMDb3.Params.WithBuilder(it)
            }
            // 2026-08-05 — le genre ne doit plus écraser le filtre d'année (cf. le commentaire
            //   détaillé dans MovixProvider.getGenre). On reprend ici les mêmes bornes que
            //   `getMovies()`/`getTvShows()`, mémorisées séparément pour films et séries.
            val plageFilmsG = com.streamflixreborn.streamflix.utils.YearFilter
                .get(name, com.streamflixreborn.streamflix.utils.YearFilter.Type.FILMS)
            val plageSeriesG = com.streamflixreborn.streamflix.utils.YearFilter
                .get(name, com.streamflixreborn.streamflix.utils.YearFilter.Type.SERIES)
            val moviesD = coroutineScope {
                async {
                    TMDb3.Discover.movie(
                        page = page, language = language,
                        sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                        primaryReleaseDate = TMDb3.Params.Range(
                            gte = com.streamflixreborn.streamflix.utils.YearFilter.borneBasse(plageFilmsG),
                            lte = com.streamflixreborn.streamflix.utils.YearFilter.borneHaute(plageFilmsG),
                        ),
                        withGenres = tmdbGenreId?.let { TMDb3.Params.WithBuilder(TMDb3.Genre.Movie.entries.find { g -> g.id == it } ?: return@async emptyList()) },
                        withOriginCountry = withOrigin,
                        watchRegion = "FR",
                        withWatchProviders = if (originCountry == null) nmWatchProviders() else null,
                        withOriginalLanguage = if (originCountry == null) nmOriginalLanguageBuilder() else null,
                    ).results.map(mapMovie)
                }
            }
            val tvD = coroutineScope {
                async {
                    TMDb3.Discover.tv(
                        page = page, language = language,
                        sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                        firstAirDate = TMDb3.Params.Range(
                            gte = com.streamflixreborn.streamflix.utils.YearFilter.borneBasse(plageSeriesG),
                            lte = com.streamflixreborn.streamflix.utils.YearFilter.borneHaute(plageSeriesG),
                        ),
                        withGenres = tmdbGenreId?.let { TMDb3.Params.WithBuilder(TMDb3.Genre.Tv.entries.find { g -> g.id == it } ?: return@async emptyList()) },
                        withOriginCountry = withOrigin,
                        watchRegion = "FR",
                        withWatchProviders = if (originCountry == null) nmWatchProviders() else null,
                        withOriginalLanguage = if (originCountry == null) nmOriginalLanguageBuilder() else null,
                    ).results.map(mapTv)
                }
            }
            val movies = moviesD.await()
            val tvShows = tvD.await()
            val genreName = TMDb3.Genre.Movie.entries.find { it.id == tmdbGenreId }?.name
                ?: originCountry ?: id
            Genre(id = id, name = genreName, shows = movies + tvShows)
        }.getOrElse { Genre(id = id, name = id) }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val personId = id.toIntOrNull() ?: return People(id = id, name = "")
        return runCatching {
            val p = TMDb3.People.details(personId = personId, language = language)
            People(
                id = p.id.toString(),
                name = p.name,
                image = p.profilePath?.w500,
            )
        }.getOrElse { People(id = id, name = "") }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Lecture — getServers / getVideo via NetMirror backend
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Pour un contenu TMDB, cherche sur toutes les plateformes OTT du backend
     * NetMirror et retourne un serveur par plateforme trouvée.
     *
     * Format du server.src : "nm::<ott>::<netmirrorId>[::s<season>::e<episode>]"
     */
    /** Parse l'ID TMDB + saison/épisode depuis l'id et le videoType. */
    private data class NmIds(
        val tmdbId: String,
        val title: String,
        /** Titre original (EN) résolu via TMDB — fallback si le titre FR ne matche pas. */
        val originalTitle: String?,
        val year: Int?,
        val seasonNum: Int?,
        val episodeNum: Int?,
    )

    private fun parseNmIds(id: String, videoType: Video.Type): NmIds {
        return when (videoType) {
            is Video.Type.Movie -> NmIds(
                tmdbId = id,
                title = videoType.title,
                originalTitle = null, // résolu après via resolveOriginalTitle()
                year = videoType.releaseDate.take(4).toIntOrNull(),
                seasonNum = null,
                episodeNum = null,
            )
            is Video.Type.Episode -> NmIds(
                tmdbId = id.substringBefore(":"),
                title = videoType.tvShow.title,
                originalTitle = null, // résolu après via resolveOriginalTitle()
                year = videoType.tvShow.releaseDate?.take(4)?.toIntOrNull(),
                seasonNum = videoType.season.number,
                episodeNum = videoType.number,
            )
        }
    }

    /**
     * Résout le titre original (EN) via TMDB pour le fallback de recherche.
     * Le backend NetMirror stocke les titres en anglais — si on cherche avec
     * le titre français (ex: "Ali Baba et les 40 voleurs") on ne trouve rien.
     */
    private suspend fun resolveOriginalTitle(ids: NmIds): NmIds {
        val tmdbIdInt = ids.tmdbId.toIntOrNull() ?: return ids
        return try {
            val originalTitle = if (ids.seasonNum != null) {
                // Série → originalName
                TMDb3.TvSeries.details(seriesId = tmdbIdInt).originalName
            } else {
                // Film → originalTitle
                TMDb3.Movies.details(movieId = tmdbIdInt).originalTitle
            }
            if (originalTitle.isNotBlank() && normalizeTitle(originalTitle) != normalizeTitle(ids.title)) {
                ids.copy(originalTitle = originalTitle)
            } else {
                ids // même titre FR/EN, pas besoin de fallback
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveOriginalTitle failed: ${e.message}")
            ids
        }
    }

    // ── Serveur natif par plateforme OTT (indépendant) ────────────────

    /**
     * Cherche + crée le serveur pour UNE plateforme OTT.
     * Retourne null si pas de résultat ou erreur.
     * Chaque plateforme est isolée : un échec Netflix ne bloque pas Prime.
     */
    /**
     * Recherche un titre sur le backend NetMirror pour une plateforme donnée.
     * Retourne l'ID NetMirror ou null si pas trouvé.
     */
    /** Un résultat de search.php, avec ce qu'on sait de lui. */
    private data class NmCandidat(
        val id: String,
        val titre: String,
        /** Année (champ `y`) — absent du catalogue Netflix. */
        val annee: Int?,
        /** `true` = série, `false` = film, `null` = inconnu (champ `r` absent). */
        val estSerie: Boolean?,
    )

    /**
     * Fiche post.php réduite à ce qui sert au désambiguïsage : (année, estSerie).
     * Utilisé uniquement quand search.php ne renvoie ni `y` ni `r` (cas Netflix).
     */
    private suspend fun ficheAnneeEtType(
        platform: OttPlatform,
        netmirrorId: String,
    ): Pair<Int?, Boolean?>? = withContext(Dispatchers.IO) {
        runCatching {
            val ts = System.currentTimeMillis() / 1000
            val req = Request.Builder()
                .url("$MAIN_URL${platform.postPath}?id=$netmirrorId&t=$ts")
                .header("Cookie", buildCookies(platform))
                .header("User-Agent", NM_UA)
                .header("Referer", "$MAIN_URL/")
                .build()
            val body = httpClientFollowRedirects.newCall(req).execute().use { it.body?.string() }
                ?: return@runCatching null
            val j = JSONObject(body)
            val annee = j.optString("year").takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull()
            val estSerie = when (j.optString("type").lowercase()) {
                "s", "t", "tv", "series" -> true
                "m", "movie" -> false
                else -> null
            }
            annee to estSerie
        }.getOrNull()
    }

    private suspend fun searchOnPlatform(
        platform: OttPlatform,
        searchTitle: String,
        /** Année attendue (TMDB) — sert à écarter les homonymes. */
        anneeAttendue: Int? = null,
        /** `true` si on cherche une série, `false` pour un film. */
        serieAttendue: Boolean? = null,
        retry: Boolean = true,
    ): String? = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis() / 1000
        val cookies = buildCookies(platform)
        val url = "$MAIN_URL${platform.searchPath}?s=${java.net.URLEncoder.encode(searchTitle, "UTF-8")}&t=$ts"
        val req = Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("User-Agent", NM_UA)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$MAIN_URL/")
            .build()

        val resp = httpClientFollowRedirects.newCall(req).execute()
        resp.use { r ->
            val body = r.body?.string()
            // 2026-07-08 DIAG : voir EXACTEMENT ce que net52 renvoie au téléphone
            //   (CF challenge HTML ? 403 ? JSON ?). first120 = début du corps.
            val first120 = body?.take(120)?.replace("\n", " ") ?: "<null>"
            Log.d(TAG, "searchOnPlatform.RESP ${platform.label} code=${r.code} len=${body?.length ?: -1} body120=[$first120]")
            if (!r.isSuccessful) return@withContext null
            if (body == null) return@withContext null
            // 2026-07-09 (user « il apparaît plus sur Enola ») : COOKIE PÉRIMÉ. Signature serveur =
            //   search.php IGNORE la requête et renvoie « Top Searches » (Netflix) ou
            //   « Enter Some Words to Search » (Hotstar/Disney) alors qu'on a bien envoyé un titre.
            //   L'expiry 15h en cache peut mentir (session invalidée côté serveur avant). → on
            //   INVALIDE le cookie (re-harvest verify.php/WebView) et on réessaie UNE fois.
            if (retry && (body.contains("Top Searches") || body.contains("Enter Some Words to Search"))) {
                Log.w(TAG, "searchOnPlatform ${platform.label} '$searchTitle' : cookie périmé (réponse bidon) → re-harvest + retry")
                invalidateCookie()
                return@withContext searchOnPlatform(
                    platform, searchTitle, anneeAttendue, serieAttendue, retry = false,
                )
            }
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
            val searchResults = json.optJSONArray("searchResult") ?: return@withContext null

            // 2026-08-08 (user : « c'est pas le vrai Star Trek qui est diffusé — on a un
            //   problème de mauvais match ») : on ne peut PAS prendre le 1er homonyme.
            //
            //   Cas réel, relevé en direct sur net52 : l'user regarde « Star Trek » S01E01
            //   (la série de 1966). search.php répond :
            //     pv → [ Star Trek 2009 (r="2h 1m"), Section 31 2025, Lower Decks 2021 (r="Series"), … ]
            //     nf → [ 70101276 "Star Trek", 70136140 "Star Trek", … ]  (ni année ni type)
            //   L'ancien code prenait le PREMIER titre qui matche → le FILM de 2009 côté
            //   Prime Video, alors que l'app NetMirror officielle, elle, joue le bon épisode.
            //
            //   Nouvelle sélection, du signal le plus sûr au moins sûr :
            //     1. `r` == "Series" → série, sinon film (présent sur pv/hs/dp)
            //     2. `y` == année TMDB à ±1 an
            //     3. si search.php ne dit rien (Netflix), on demande la fiche post.php de
            //        chaque candidat (`type` m/s + `year`) — 1 requête par homonyme, borné à 4.
            //   Si RIEN ne colle, on renvoie null : pas de serveur vaut mieux qu'un serveur
            //   qui diffuse un autre film.
            val candidats = mutableListOf<NmCandidat>()
            for (i in 0 until searchResults.length()) {
                val item = searchResults.optJSONObject(i) ?: continue
                val resultTitle = item.optString("t")
                val resultId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                if (!com.streamflixreborn.streamflix.utils.BackupRegistry.titleMatches(resultTitle, searchTitle)) continue
                val r = item.optString("r")
                candidats += NmCandidat(
                    id = resultId,
                    titre = resultTitle,
                    annee = item.optString("y").takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull(),
                    estSerie = if (r.isBlank()) null else r.equals("Series", ignoreCase = true),
                )
            }
            if (candidats.isEmpty()) return@withContext null

            var restants: List<NmCandidat> = candidats
            // 1) type (série / film) quand search.php le donne
            if (serieAttendue != null && restants.any { it.estSerie != null }) {
                val f = restants.filter { it.estSerie == null || it.estSerie == serieAttendue }
                if (f.isEmpty()) {
                    Log.d(TAG, "searchOnPlatform ${platform.label} '$searchTitle' : ${candidats.size} homonyme(s), aucun du bon type (série=$serieAttendue) → écarté")
                    return@withContext null
                }
                restants = f
            }
            // 2) année quand search.php la donne
            if (anneeAttendue != null && restants.any { it.annee != null }) {
                val f = restants.filter {
                    it.annee == null || kotlin.math.abs(it.annee - anneeAttendue) <= 1
                }
                if (f.isEmpty()) {
                    Log.d(TAG, "searchOnPlatform ${platform.label} '$searchTitle' : homonymes ${restants.map { "${it.titre} ${it.annee}" }} ≠ $anneeAttendue → écarté")
                    return@withContext null
                }
                restants = f
            }

            // 3) rien d'exploitable dans search.php (Netflix) → fiche post.php par candidat
            val besoinDeVerif = (anneeAttendue != null || serieAttendue != null) &&
                restants.any { it.annee == null && it.estSerie == null }
            if (besoinDeVerif) {
                for (c in restants.take(4)) {
                    if (c.annee != null || c.estSerie != null) {
                        Log.d(TAG, "searchOnPlatform ${platform.label} '$searchTitle' → ID ${c.id} (${c.titre})")
                        return@withContext c.id
                    }
                    val fiche = ficheAnneeEtType(platform, c.id) ?: continue
                    val (annee, estSerie) = fiche
                    val typeOk = serieAttendue == null || estSerie == null || estSerie == serieAttendue
                    val anneeOk = anneeAttendue == null || annee == null ||
                        kotlin.math.abs(annee - anneeAttendue) <= 1
                    Log.d(TAG, "searchOnPlatform ${platform.label} fiche ${c.id} '${c.titre}' : année=$annee série=$estSerie (attendu $anneeAttendue/$serieAttendue) → ${if (typeOk && anneeOk) "OK" else "écarté"}")
                    if (typeOk && anneeOk) return@withContext c.id
                }
                Log.d(TAG, "searchOnPlatform ${platform.label} '$searchTitle' : aucun homonyme ne correspond à $anneeAttendue/série=$serieAttendue → écarté")
                return@withContext null
            }

            val choisi = restants.first()
            Log.d(TAG, "searchOnPlatform ${platform.label} '$searchTitle' → ID ${choisi.id} (${choisi.titre} ${choisi.annee ?: "?"})")
            choisi.id
        }
    }

    /** Infos structurées d'une langue dispo sur le backend. */
    private data class LangInfo(val code: String, val label: String, val langId: String?)

    /**
     * Cherche + crée les serveurs pour UNE plateforme OTT.
     * Retourne une liste : si le FR est dispo on crée un serveur [VF] dédié
     * qui sera auto-play (trié en premier par orderByFrenchBuckets).
     * Les autres langues restent accessibles en serveurs secondaires.
     */
    private suspend fun fetchServersForPlatform(
        platform: OttPlatform,
        ids: NmIds,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        try {
            // 1) Recherche sur cette plateforme — titre FR d'abord, puis fallback titre original EN
            val cacheKey = normalizeTitle(ids.title) + (ids.year?.let { "_$it" } ?: "")
            val cachedAll = idCache[cacheKey]
            val netmirrorId = cachedAll?.get(platform) ?: run {
                // 2026-08-08 : on transmet année + type pour écarter les homonymes
                //   (« Star Trek » série 1966 vs film 2009). cf. searchOnPlatform.
                val serieAttendue = ids.seasonNum != null && ids.episodeNum != null
                // Essai 1 : titre français (TMDB fr-FR)
                var foundId = searchOnPlatform(platform, ids.title, ids.year, serieAttendue)
                // Essai 2 : titre original anglais (fallback si FR ne matche pas)
                if (foundId == null && ids.originalTitle != null) {
                    Log.d(TAG, "${platform.label} : titre FR '${ids.title}' non trouvé, essai EN '${ids.originalTitle}'")
                    foundId = searchOnPlatform(platform, ids.originalTitle, ids.year, serieAttendue)
                }
                foundId
            } ?: return@withContext emptyList()

            // 2) Langues disponibles (structurées)
            val langInfos = withTimeoutOrNull(5_000) {
                fetchLanguageInfos(platform, netmirrorId)
            } ?: emptyList()

            // 2 bis) 2026-08-08 (user, capture de LEUR lecteur à l'appui : « S1 • E1 —
            //   Star Trek – Pilot: The Cage ») — NUMÉROTATION D'ÉPISODES DIFFÉRENTE.
            //   Netflix compte « The Cage » (le pilote inédit de 1965) comme S1E1 de Star Trek,
            //   donc leur saison 1 fait 30 épisodes là où TMDB en compte 29 et démarre à
            //   « The Man Trap ». En demandant S1E1 on jouait donc l'épisode d'AVANT — tout est
            //   décalé d'un cran. Même problème sur toutes les vieilles séries où un pilote est
            //   inséré, ou deux parties fusionnées.
            //   Parade : on emporte le TITRE ORIGINAL (anglais) de l'épisode ; resolveEpisodeId
            //   le cherche d'abord par titre, et ne retombe sur le numéro que s'il ne trouve pas.
            //   NetMirror libelle ses épisodes en anglais, d'où `language = "en-US"`.
            val titreEpisodeVo: String? =
                if (ids.seasonNum != null && ids.episodeNum != null) {
                    runCatching {
                        val tvId = ids.tmdbId.toIntOrNull() ?: return@runCatching null
                        TMDb3.TvSeasons.details(
                            seriesId = tvId,
                            seasonNumber = ids.seasonNum,
                            language = "en-US",
                        ).episodes?.firstOrNull { it.episodeNumber == ids.episodeNum }?.name
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                } else null

            // 3) Construire les serveurs — un PAR LANGUE, VF en premier
            val baseSrc = buildString {
                append("nm::${platform.ottCookie}::$netmirrorId")
                if (ids.seasonNum != null && ids.episodeNum != null) {
                    append("::s${ids.seasonNum}::e${ids.episodeNum}")
                    titreEpisodeVo?.let {
                        append("::titre").append(java.net.URLEncoder.encode(it, "UTF-8"))
                    }
                }
            }

            if (langInfos.isEmpty()) {
                // Pas de détail langue → serveur unique neutre
                return@withContext listOf(Video.Server(
                    id = "netmirror_${platform.ottCookie}_$netmirrorId",
                    name = "NetMirror ${platform.label}",
                    src = baseSrc,
                ))
            }

            // 2026-05-22 : on ne crée des serveurs QUE pour VF et VOSTFR.
            // Les langues étrangères (EN, HI, JA…) → UN SEUL serveur [VO]
            // en fallback, uniquement si aucun VF/VOSTFR n'existe.
            // Évite de polluer la liste avec 5x "Netflix [VO]".
            val frLangs = langInfos.filter { it.label == "VF" || it.label == "VOSTFR" }
            val servers = mutableListOf<Video.Server>()

            if (frLangs.isNotEmpty()) {
                // On a du FR → un serveur par piste FR (VF en premier)
                frLangs.sortedBy { if (it.label == "VF") 0 else 1 }.forEach { lang ->
                    val langSrc = if (lang.langId != null) "$baseSrc::lang${lang.langId}" else baseSrc
                    servers.add(Video.Server(
                        id = "netmirror_${platform.ottCookie}_${netmirrorId}_${lang.code}",
                        name = "NetMirror ${platform.label} [${lang.label}]",
                        src = langSrc,
                    ))
                }
            }
            // 2026-08-08 (user : « prends le serveur VOSTFR aussi », « faudra qu'ils soient bien
            //   marqués VOSTFR ») — SOUS-TITRES FRANÇAIS.
            //   `post.php` ne liste que les pistes AUDIO (champ `lang`) : c'est pour ça qu'on ne
            //   proposait que du VF ou du VO. Les sous-titres, eux, sont dans `playlist.php`
            //   (champ `tracks`). VÉRIFIÉ en direct sur Star Trek S1 (id 70109436) : 27 pistes
            //   `captions`, dont `70109436-fr.srt` libellée « French ».
            //   On ajoute donc UN serveur VOSTFR, marqué explicitement `[VOSTFR]`, placé après
            //   le VF. Le marqueur `::subfr` dit à getVideo d'aller chercher la piste française
            //   dans `tracks` et de l'attacher ; s'il n'y en a pas pour ce contenu, getVideo
            //   échoue franchement plutôt que de servir de la VO en la faisant passer pour du
            //   sous-titré (principe projet : pas de serveur plutôt qu'un serveur menteur).
            servers.add(Video.Server(
                id = "netmirror_${platform.ottCookie}_${netmirrorId}_subfr",
                name = "NetMirror ${platform.label} [VOSTFR]",
                src = "$baseSrc::subfr",
            ))

            if (frLangs.isEmpty()) {
                // Pas de FR → UN SEUL serveur [VO] en dernier recours
                // (prend la 1re langue dispo pour le langId)
                val fallbackLang = langInfos.firstOrNull()
                val langSrc = if (fallbackLang?.langId != null) "$baseSrc::lang${fallbackLang.langId}" else baseSrc
                val allLangs = langInfos.map { it.label }.distinct().joinToString(", ")
                servers.add(Video.Server(
                    id = "netmirror_${platform.ottCookie}_${netmirrorId}_vo",
                    name = "NetMirror ${platform.label} [VO] ($allLangs)",
                    src = langSrc,
                ))
            }
            servers
        } catch (e: Exception) {
            Log.w(TAG, "fetchServersForPlatform ${platform.label} failed: ${e.message}")
            emptyList()
        }
    }

    /** Batch : récupère TOUTES les plateformes en parallèle (fallback pour getServers batch). */
    private suspend fun fetchNativeNetMirrorServers(
        ids: NmIds,
    ): List<Video.Server> = coroutineScope {
        OttPlatform.entries.map { platform ->
            async { fetchServersForPlatform(platform, ids) }
        }.awaitAll().flatten()
    }

    // ── Backups (Cloudstream, Movix, Moviebox, Papa, Coflix) ───────────

    private suspend fun fetchNetMirrorBackups(
        ids: NmIds,
        videoType: Video.Type,
    ): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        val tmdbIdInt = ids.tmdbId.toIntOrNull()

        // 1) Cloudstream backup — MovieBox+ via /resource (bons streams MP4)
        try {
            val csId = when (videoType) {
                is Video.Type.Movie -> ids.tmdbId
                is Video.Type.Episode -> "${ids.tmdbId}:${ids.seasonNum}:${ids.episodeNum}"
            }
            val cs = CloudstreamProvider.getServers(csId, videoType)
            if (cs.isNotEmpty()) {
                Log.d(TAG, "+ Cloudstream backup : ${cs.size} sources")
                servers.addAll(cs.map { it.copy(
                    id = "nm_cs__${it.id}",
                    name = "Cloudstream — ${it.name}",
                )})
            }
        } catch (e: Exception) {
            Log.d(TAG, "Cloudstream backup failed: ${e.message}")
        }

        // 2) Movix backup — multi-hosters (Filemoon, Doodstream, VOE, etc.)
        try {
            val movixId = when (videoType) {
                is Video.Type.Movie -> ids.tmdbId
                is Video.Type.Episode -> "${ids.tmdbId}-${ids.seasonNum}-${ids.episodeNum}"
            }
            // Anti-récursion : dire à Movix de ne pas appeler ses propres backups
            MovixProvider.skipBackupsForBackupCall = true
            try {
                val mx = MovixProvider.getServers(movixId, videoType)
                if (mx.isNotEmpty()) {
                    Log.d(TAG, "+ Movix backup : ${mx.size} sources")
                    servers.addAll(mx.map { it.copy(
                        id = "nm_movix__${it.id}",
                        name = it.name,
                    )})
                }
            } finally {
                MovixProvider.skipBackupsForBackupCall = false
            }
        } catch (e: Exception) {
            Log.d(TAG, "Movix backup failed: ${e.message}")
        }

        // 3) Moviebox backup — recherche par TMDB ID
        if (tmdbIdInt != null) {
            try {
                val mb = MovieboxProvider.getMovieboxSourcesByTmdbId(tmdbIdInt, videoType)
                if (mb.isNotEmpty()) {
                    Log.d(TAG, "+ Moviebox backup : ${mb.size} sources")
                    servers.addAll(mb.map { it.copy(
                        id = "nm_mb__${it.id}",
                        name = "Moviebox — ${it.name}",
                    )})
                }
            } catch (e: Exception) {
                Log.d(TAG, "Moviebox backup failed: ${e.message}")
            }
        }

        // 4) Papadustream DÉSACTIVÉ (2026-06-02) — captcha CF intrusif (user request)
        // try {
        //     val papa = PapadustreamProvider.getPapaSourcesByTmdbId(ids.tmdbId, videoType)
        //     if (papa.isNotEmpty()) {
        //         Log.d(TAG, "+ Papa backup : ${papa.size} sources")
        //         servers.addAll(papa.map { it.copy(
        //             id = "nm_papa__${it.id}",
        //             name = "Papa — ${it.name}",
        //         )})
        //     }
        // } catch (e: Exception) {
        //     Log.d(TAG, "Papa backup failed: ${e.message}")
        // }

        // 5) Coflix backup — multi-hosters FR
        if (tmdbIdInt != null) {
            try {
                val (title, year) = when (videoType) {
                    is Video.Type.Movie -> {
                        val det = TMDb3.Movies.details(movieId = tmdbIdInt, language = "fr-FR")
                        (det.title.takeIf { it.isNotBlank() } ?: det.originalTitle) to
                            det.releaseDate?.take(4)?.toIntOrNull()
                    }
                    is Video.Type.Episode -> {
                        val det = TMDb3.TvSeries.details(seriesId = tmdbIdInt, language = "fr-FR")
                        (det.name.takeIf { it.isNotBlank() } ?: det.originalName) to
                            det.firstAirDate?.take(4)?.toIntOrNull()
                    }
                }
                val coflix = when (videoType) {
                    is Video.Type.Movie -> CoflixSourceProvider.getMovieSources(title, year)
                    is Video.Type.Episode ->
                        CoflixSourceProvider.getEpisodeSources(title, year, ids.seasonNum ?: 1, ids.episodeNum ?: 1)
                }
                if (coflix.isNotEmpty()) {
                    Log.d(TAG, "+ Coflix backup : ${coflix.size} sources")
                    servers.addAll(coflix.map { it.copy(
                        id = "nm_coflix__${it.id}",
                        name = "Coflix — ${it.name}",
                    )})
                }
            } catch (e: Exception) {
                Log.d(TAG, "Coflix backup failed: ${e.message}")
            }
        }

        // 6) FrenchStream backup — recherche par titre FR, gros catalogue FR
        try {
            val frTitle = ids.title
            val fsResults = FrenchStreamProvider.search(frTitle, 1)
            val bestFs = fsResults.firstOrNull { item ->
                val t = when (item) {
                    is Movie -> item.title
                    is TvShow -> item.title
                    else -> ""
                }
                com.streamflixreborn.streamflix.utils.BackupRegistry.titleMatches(t, frTitle)
            }
            if (bestFs != null) {
                val fsId = when (bestFs) { is Movie -> bestFs.id; is TvShow -> bestFs.id; else -> null }
                if (fsId != null) {
                    val fs = FrenchStreamProvider.getServers(
                        when (videoType) {
                            is Video.Type.Movie -> fsId
                            is Video.Type.Episode -> "$fsId/${ids.episodeNum ?: 1}"
                        },
                        videoType,
                    )
                    if (fs.isNotEmpty()) {
                        Log.d(TAG, "+ FrenchStream backup : ${fs.size} sources")
                        servers.addAll(fs.map { it.copy(
                            id = "nm_fs__${it.id}",
                            name = "FrenchStream — ${it.name}",
                        )})
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "FrenchStream backup failed: ${e.message}")
        }

        // 7) Wiflix backup — recherche par titre FR, catalogue FR
        try {
            val wfTitle = ids.title
            val wfResults = WiflixProvider.search(wfTitle, 1)
            val bestWf = wfResults.firstOrNull { item ->
                val t = when (item) {
                    is Movie -> item.title
                    is TvShow -> item.title
                    else -> ""
                }
                com.streamflixreborn.streamflix.utils.BackupRegistry.titleMatches(t, wfTitle)
            }
            if (bestWf != null) {
                val wfId = when (bestWf) { is Movie -> bestWf.id; is TvShow -> bestWf.id; else -> null }
                if (wfId != null) {
                    val wf = WiflixProvider.getServers(
                        when (videoType) {
                            is Video.Type.Movie -> wfId
                            is Video.Type.Episode -> "$wfId/${ids.episodeNum ?: 1}"
                        },
                        videoType,
                    )
                    if (wf.isNotEmpty()) {
                        Log.d(TAG, "+ Wiflix backup : ${wf.size} sources")
                        servers.addAll(wf.map { it.copy(
                            id = "nm_wf__${it.id}",
                            name = "Wiflix — ${it.name}",
                        )})
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Wiflix backup failed: ${e.message}")
        }

        return servers
    }

    // ── getServers batch (fallback) ───────────────────────────────────

    /** Backups inline désactivés le temps de régler les serveurs NetMirror natifs.
     *  2026-07-04 : branché sur le kill-switch central → backups gérés par le registre. */
    private val BACKUPS_ENABLED = !com.streamflixreborn.streamflix.utils.BackupRegistry.INLINE_BACKUPS_DISABLED

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = coroutineScope {
        val ids = resolveOriginalTitle(parseNmIds(id, videoType))
        Log.d(TAG, "getServers '${ids.title}' (original: '${ids.originalTitle ?: "="}') tmdb=${ids.tmdbId}")
        val native = fetchNativeNetMirrorServers(ids)
        val backups = if (BACKUPS_ENABLED) fetchNetMirrorBackups(ids, videoType) else emptyList()
        val all = native + backups
        Log.d(TAG, "getServers '${ids.title}' → ${native.size} natifs + ${backups.size} backups = ${all.size}")
        all
    }

    // ── getServersProgressive (affichage au fur et à mesure) ──────────

    override fun getServersProgressive(
        id: String,
        videoType: Video.Type,
    ): Flow<List<Video.Server>> = channelFlow {
        val ids = resolveOriginalTitle(parseNmIds(id, videoType))
        Log.d(TAG, "getServersProgressive '${ids.title}' (original: '${ids.originalTitle ?: "="}') tmdb=${ids.tmdbId}")
        // 2026-07-08 (anti « STOP Abuse ») : les 4 plateformes OTT sont interrogées
        //   SÉQUENTIELLEMENT avec un délai entre chaque, au lieu d'une RAFALE parallèle.
        //   L'app officielle NetMirror n'interroge qu'une plateforme à la fois → l'anti-abus
        //   du backend ne se déclenche pas. En parallèle, nos 4 search (×2 avec retry FR/EN)
        //   partaient d'un coup → « Too Many Requests ». On garde l'émission progressive
        //   (chaque plateforme émet ses serveurs dès qu'ils sont prêts), juste espacée.
        //   Les backups (ci-dessous) restent parallèles : ils tapent d'AUTRES domaines.
        launch {
            for (platform in OttPlatform.entries) {
                try {
                    val servers = fetchServersForPlatform(platform, ids)
                    if (servers.isNotEmpty()) send(servers)
                } catch (e: Exception) {
                    Log.w(TAG, "Progressive ${platform.label} failed: ${e.message}")
                }
                // Espacer les appels API NetMirror (~600ms) pour ne pas déclencher l'anti-abus.
                kotlinx.coroutines.delay(600)
            }
        }
        // Backups PARALLÈLES — chaque backup émet son lot dès qu'il est prêt
        // (au lieu d'un fetchNetMirrorBackups séquentiel qui attend TOUS les 7)
        if (BACKUPS_ENABLED) {
            val tmdbIdInt = ids.tmdbId.toIntOrNull()

            // 1) Cloudstream — rapide (cache fréquent)
            launch {
                try {
                    val csId = when (videoType) {
                        is Video.Type.Movie -> ids.tmdbId
                        is Video.Type.Episode -> "${ids.tmdbId}:${ids.seasonNum}:${ids.episodeNum}"
                    }
                    val cs = CloudstreamProvider.getServers(csId, videoType)
                    if (cs.isNotEmpty()) {
                        Log.d(TAG, "+ Cloudstream backup (prog) : ${cs.size} sources")
                        send(cs.map { it.copy(id = "nm_cs__${it.id}", name = "Cloudstream — ${it.name}") })
                    }
                } catch (e: Exception) { Log.d(TAG, "Prog Cloudstream failed: ${e.message}") }
            }

            // 2) Movix — rapide (cache fréquent)
            launch {
                try {
                    val movixId = when (videoType) {
                        is Video.Type.Movie -> ids.tmdbId
                        is Video.Type.Episode -> "${ids.tmdbId}-${ids.seasonNum}-${ids.episodeNum}"
                    }
                    MovixProvider.skipBackupsForBackupCall = true
                    try {
                        val mx = MovixProvider.getServers(movixId, videoType)
                        if (mx.isNotEmpty()) {
                            Log.d(TAG, "+ Movix backup (prog) : ${mx.size} sources")
                            send(mx.map { it.copy(id = "nm_movix__${it.id}") })
                        }
                    } finally { MovixProvider.skipBackupsForBackupCall = false }
                } catch (e: Exception) { Log.d(TAG, "Prog Movix failed: ${e.message}") }
            }

            // 3) Moviebox
            if (tmdbIdInt != null) launch {
                try {
                    val mb = MovieboxProvider.getMovieboxSourcesByTmdbId(tmdbIdInt, videoType)
                    if (mb.isNotEmpty()) {
                        Log.d(TAG, "+ Moviebox backup (prog) : ${mb.size} sources")
                        send(mb.map { it.copy(id = "nm_mb__${it.id}", name = "Moviebox — ${it.name}") })
                    }
                } catch (e: Exception) { Log.d(TAG, "Prog Moviebox failed: ${e.message}") }
            }

            // 4) Papa DÉSACTIVÉ (2026-06-02) — captcha CF intrusif (user request)
            // launch {
            //     try {
            //         val papa = PapadustreamProvider.getPapaSourcesByTmdbId(ids.tmdbId, videoType)
            //         if (papa.isNotEmpty()) {
            //             Log.d(TAG, "+ Papa backup (prog) : ${papa.size} sources")
            //             send(papa.map { it.copy(id = "nm_papa__${it.id}", name = "Papa — ${it.name}") })
            //         }
            //     } catch (e: Exception) { Log.d(TAG, "Prog Papa failed: ${e.message}") }
            // }

            // 5) Coflix
            if (tmdbIdInt != null) launch {
                try {
                    val (title, year) = when (videoType) {
                        is Video.Type.Movie -> {
                            val det = TMDb3.Movies.details(movieId = tmdbIdInt, language = "fr-FR")
                            (det.title.takeIf { it.isNotBlank() } ?: det.originalTitle) to
                                det.releaseDate?.take(4)?.toIntOrNull()
                        }
                        is Video.Type.Episode -> {
                            val det = TMDb3.TvSeries.details(seriesId = tmdbIdInt, language = "fr-FR")
                            (det.name.takeIf { it.isNotBlank() } ?: det.originalName) to
                                det.firstAirDate?.take(4)?.toIntOrNull()
                        }
                    }
                    val coflix = when (videoType) {
                        is Video.Type.Movie -> CoflixSourceProvider.getMovieSources(title, year)
                        is Video.Type.Episode ->
                            CoflixSourceProvider.getEpisodeSources(title, year, ids.seasonNum ?: 1, ids.episodeNum ?: 1)
                    }
                    if (coflix.isNotEmpty()) {
                        Log.d(TAG, "+ Coflix backup (prog) : ${coflix.size} sources")
                        send(coflix.map { it.copy(id = "nm_coflix__${it.id}", name = "Coflix — ${it.name}") })
                    }
                } catch (e: Exception) { Log.d(TAG, "Prog Coflix failed: ${e.message}") }
            }

            // 6) FrenchStream — recherche + getServers
            launch {
                try {
                    val frTitle = ids.title
                    val fsResults = FrenchStreamProvider.search(frTitle, 1)
                    val bestFs = fsResults.firstOrNull { item ->
                        val t = when (item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }
                        com.streamflixreborn.streamflix.utils.BackupRegistry.titleMatches(t, frTitle)
                    }
                    if (bestFs != null) {
                        val fsId = when (bestFs) { is Movie -> bestFs.id; is TvShow -> bestFs.id; else -> null }
                        if (fsId != null) {
                            val fs = FrenchStreamProvider.getServers(
                                when (videoType) { is Video.Type.Movie -> fsId; is Video.Type.Episode -> "$fsId/${ids.episodeNum ?: 1}" },
                                videoType,
                            )
                            if (fs.isNotEmpty()) {
                                Log.d(TAG, "+ FrenchStream backup (prog) : ${fs.size} sources")
                                send(fs.map { it.copy(id = "nm_fs__${it.id}", name = "FrenchStream — ${it.name}") })
                            }
                        }
                    }
                } catch (e: Exception) { Log.d(TAG, "Prog FrenchStream failed: ${e.message}") }
            }

            // 7) Wiflix — recherche + getServers
            launch {
                try {
                    val wfTitle = ids.title
                    val wfResults = WiflixProvider.search(wfTitle, 1)
                    val bestWf = wfResults.firstOrNull { item ->
                        val t = when (item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }
                        com.streamflixreborn.streamflix.utils.BackupRegistry.titleMatches(t, wfTitle)
                    }
                    if (bestWf != null) {
                        val wfId = when (bestWf) { is Movie -> bestWf.id; is TvShow -> bestWf.id; else -> null }
                        if (wfId != null) {
                            val wf = WiflixProvider.getServers(
                                when (videoType) { is Video.Type.Movie -> wfId; is Video.Type.Episode -> "$wfId/${ids.episodeNum ?: 1}" },
                                videoType,
                            )
                            if (wf.isNotEmpty()) {
                                Log.d(TAG, "+ Wiflix backup (prog) : ${wf.size} sources")
                                send(wf.map { it.copy(id = "nm_wf__${it.id}", name = "Wiflix — ${it.name}") })
                            }
                        }
                    }
                } catch (e: Exception) { Log.d(TAG, "Prog Wiflix failed: ${e.message}") }
            }
        }
    }

    /**
     * Résout le lien M3U8 via le système NewTV API.
     *
     * 1. Parse le server.src pour extraire la plateforme OTT + l'ID NetMirror
     * 2. Résout l'URL de l'API NewTV (via mobidetect* → checknewtv.php)
     * 3. Appelle player.php?id=<contentId> avec le header Ott approprié
     * 4. Retourne le lien M3U8
     */
    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        // ── Délégation backup ──
        if (server.id.startsWith("nm_cs__")) {
            val original = server.copy(id = server.id.removePrefix("nm_cs__"))
            return@withContext try {
                CloudstreamProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "Cloudstream getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }
        if (server.id.startsWith("nm_movix__")) {
            val original = server.copy(id = server.id.removePrefix("nm_movix__"))
            return@withContext try {
                MovixProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "Movix getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }
        if (server.id.startsWith("nm_mb__")) {
            val original = server.copy(id = server.id.removePrefix("nm_mb__"))
            return@withContext try {
                MovieboxProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "Moviebox getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }
        if (server.id.startsWith("nm_papa__")) {
            val original = server.copy(id = server.id.removePrefix("nm_papa__"))
            return@withContext try {
                PapadustreamProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "Papa getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }
        if (server.id.startsWith("nm_coflix__")) {
            val original = server.copy(id = server.id.removePrefix("nm_coflix__"))
            return@withContext try {
                // Coflix servers contiennent directement l'URL embed dans src
                val extracted = Extractor.extract(original.src)
                extracted ?: Video(source = original.src)
            } catch (e: Exception) {
                Log.w(TAG, "Coflix getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }
        if (server.id.startsWith("nm_fs__")) {
            val original = server.copy(id = server.id.removePrefix("nm_fs__"))
            return@withContext try {
                FrenchStreamProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "FrenchStream getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }
        if (server.id.startsWith("nm_wf__")) {
            val original = server.copy(id = server.id.removePrefix("nm_wf__"))
            return@withContext try {
                WiflixProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "Wiflix getVideo failed: ${e.message}")
                Video(source = original.src)
            }
        }

        // ── Serveur natif NetMirror ──
        val parts = server.src.split("::")
        if (parts.size < 3 || parts[0] != "nm") {
            throw IllegalArgumentException("NetMirror: format src invalide: ${server.src}")
        }

        val ottCode = parts[1] // nf, pv, hs, dp
        val netmirrorId = parts[2]
        // Extraire season/episode et langId depuis le src encodé
        // Format : nm::ott::id[::sN::eN][::langXXX]
        var seasonNum: Int? = null
        var episodeNum: Int? = null
        var langId: String? = null
        var titreEpisodeVo: String? = null
        // 2026-08-08 : `::subfr` = serveur VOSTFR (audio d'origine + sous-titres français).
        var vostfrDemande = false
        for (p in parts.drop(3)) {
            when {
                p == "subfr" -> vostfrDemande = true
                p.startsWith("titre") -> titreEpisodeVo = runCatching {
                    java.net.URLDecoder.decode(p.removePrefix("titre"), "UTF-8")
                }.getOrNull()
                p.startsWith("lang") -> langId = p.removePrefix("lang")
                p.startsWith("s") && p.drop(1).toIntOrNull() != null -> seasonNum = p.drop(1).toInt()
                p.startsWith("e") && p.drop(1).toIntOrNull() != null -> episodeNum = p.drop(1).toInt()
            }
        }

        // Construire l'ID de contenu pour le player
        val contentId = if (seasonNum != null && episodeNum != null) {
            // Pour les séries : d'abord récupérer l'ID de l'épisode via le backend
            val episodeId = resolveEpisodeId(ottCode, netmirrorId, seasonNum, episodeNum, titreEpisodeVo)
            episodeId ?: netmirrorId
        } else {
            netmirrorId
        }

        // 2026-07-08 (user : « tu prends une partie de leur application qu'on met dans la nôtre ») :
        //   le vrai film NetMirror est verrouillé (vidéo masquée + token signé serveur + net52/mobile
        //   gaté par l'UA OS.Gatu). La SEULE façon = faire comme l'app officielle → ouvrir leur player
        //   web dans une WebView OS.Gatu. On lance donc le lecteur WebView NetMirror et on interrompt
        //   le flux ExoPlayer (le vrai film joue dans la WebView, token/player gérés tout seuls).
        if (NM_WEBVIEW_PLAYER) {
            // Cookie de session t_hash_t (accountless) pour que net52/mobile/home serve la vraie web-app.
            val session = runCatching { ensureCookie() }.getOrNull()
            withContext(Dispatchers.Main) {
                com.streamflixreborn.streamflix.activities.tools.NetMirrorWebPlayerActivity.launch(
                    context = com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext,
                    cookie = session,
                )
            }
            throw IllegalStateException("NetMirror: lecture dans le lecteur WebView embarqué")
        }

        // 2026-07-08 (user : « fais en sorte que ça soit le VRAI film qui soit lu ») — RECETTE
        //   VÉRIFIÉE EN DIRECT (Chrome + OPPO du user) : en anonyme, /hls/ et /playlist.php servent
        //   une VIDÉO MASQUÉE (preview 10 min, asset 220884) + l'audio réel. Le VRAI film (asset =
        //   contentId, 108 min) s'obtient ainsi, SANS COMPTE :
        //     1. POST net77.cc/play.php  body id=<contentId>  → JSON { "h": "in=<token>::fp::p::…" }
        //        (émet le token même en anonyme total — vérifié credentials:omit).
        //     2. host CDN = celui de la piste AUDIO réelle du master (/hls/<id>.m3u8 → files/<id>).
        //     3. URL réelle = https://<host>/files/<contentId>/1080p/1080p.m3u8?<token>  (200, 108 min).
        //   Sans token → "Only Valid Users Allowed". Si la recette échoue, on retombe sur l'ancien
        //   chemin (playlist.php + gate anti-preview) → backups.
        // 2026-07-08 (user : « remets l'état audio+pub, on creuse depuis là ») : la résolution du
        //   VRAI film (resolveRealFilm/harvest) est DÉSACTIVÉE temporairement — elle gelait 20s puis
        //   échouait. On revient au master masqué (audio réel + vidéo preview) qui joue l'audio, le
        //   temps de craquer le token vidéo. Réactiver quand le harvest capte le vrai m3u8.
        // val realFilmUrl = runCatching { resolveRealFilm(contentId, ottCode) }.getOrNull()
        // if (realFilmUrl != null) { … return Video(realFilmUrl) }

        // 2026-07-08 (fix « serveur rouge ») : l'ancienne API NewTV (mobidetect →
        //   newtv/player.php) est MORTE. Le site NetMirror lit désormais le flux via
        //   /playlist.php?id=<id> (endpoint racine), qui renvoie
        //   { "0": { sources: [ { file, label, type } ] } } où file = m3u8 HLS
        //   (relatif /hls/<id>… ou absolu). VÉRIFIÉ en direct dans la session du user :
        //   playlist.php → sources[0].file → <domaine>/hls/… = m3u8 valide (200, #EXTM3U).
        //   2026-07-08 (corrigé) : comme search.php, playlist.php a besoin du cookie de session
        //   t_hash_t (accountless via verify.php) — l'app officielle l'envoie toujours. On inclut
        //   donc la session AVANT ott/hd/lang, et l'UA furtif pour passer Cloudflare.
        val session = ensureCookie()
        val langCookie = if (langId != null) "; lang=$langId" else ""
        val fullCookie = "$session; ott=$ottCode; hd=on$langCookie"
        // 2026-08-08 (user : « on a un serveur à réparer, NetMirror Prime Video ») :
        //   playlist.php est PAR PLATEFORME, comme search.php / post.php / episodes.php.
        //   La racine /playlist.php ne connaît QUE le catalogue Netflix ; avec un id Prime
        //   Video elle répond 200 + le texte « Video ID not found! » → getVideo jetait et
        //   ONYX basculait aussitôt sur un autre serveur.
        //   VÉRIFIÉ EN DIRECT (Star Trek 2009, id 0KN466ML8YRT1CXIABHR9N2L9T, cookie
        //   t_hash_t de l'appareil) :
        //     /playlist.php?id=<pv>      → 200, 19 o, « Video ID not found! »   ✗
        //     /pv/playlist.php?id=<pv>   → 200, sources[0].file=/pv/hls/<id>.m3u8?in=…  ✓
        //     ce m3u8 → 200 #EXTM3U avec les pistes audio dont 2× French (fra).
        //   hs/dp suivent le même schéma que leurs searchPath/postPath (/hs/…), non
        //   vérifiés en direct faute d'id sous la main.
        val plateforme = OttPlatform.entries.find { it.ottCookie == ottCode }
        val playlistPath = plateforme?.playlistPath ?: "/playlist.php"
        val playlistUrl = "$MAIN_URL$playlistPath?id=$contentId&t=${System.currentTimeMillis()}"
        val req = Request.Builder()
            .url(playlistUrl)
            .header("User-Agent", NM_UA)
            .header("Ott", ottCode)
            .header("Cookie", fullCookie)
            .header("Referer", "$MAIN_URL/")
            .build()

        val resp = httpClientFollowRedirects.newCall(req).execute()
        resp.use { r ->
            if (!r.isSuccessful) {
                throw IllegalStateException("NetMirror playlist.php returned ${r.code}")
            }
            val body = r.body?.string()
                ?: throw IllegalStateException("NetMirror playlist.php empty body")

            // playlist.php renvoie soit un TABLEAU [ { … } ] (net52), soit un objet
            //   { "0": { … } } (variantes) → on gère les deux. Chaque entrée =
            //   { title, image, sources: [ {file,label,type} ] }.
            val trimmed = body.trimStart()
            val entry = runCatching {
                if (trimmed.startsWith("[")) {
                    org.json.JSONArray(body).optJSONObject(0)
                } else {
                    JSONObject(body).optJSONObject("0")
                }
            }.getOrNull()
                ?: throw IllegalStateException("NetMirror playlist.php JSON inattendu: ${body.take(120)}")
            val sources = entry.optJSONArray("sources")
                ?: throw IllegalStateException("NetMirror playlist.php: pas de sources")
            if (sources.length() == 0) {
                throw IllegalStateException("NetMirror playlist.php: sources vide")
            }
            // 1re source = meilleure qualité (Full HD).
            val file = sources.optJSONObject(0)?.optString("file")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("NetMirror playlist.php: pas de file")

            // 2026-08-08 — SOUS-TITRES. `tracks` = [{kind:"captions", file, label, language}, …]
            //   Les chemins sont protocol-relative (`//subscdn.top/…`) → on préfixe en https.
            //
            //   2026-08-08, 2ᵉ passe (user : « sur NetMirror on devrait être capable d'avoir
            //   TOUTES les langues et toutes les traductions possibles, leur site est vraiment
            //   complet là-dessus » + capture du menu montrant une piste nommée « Inconnu ») :
            //     • on ne gardait QUE le français → 26 pistes jetées ;
            //     • le libellé passé au lecteur venait tel quel de leur JSON (« French »), et
            //       la piste ressortait « Inconnu » dans le menu. On envoie donc un nom FR
            //       explicite, tiré du code langue ISO, avec repli sur leur libellé.
            //   Vérifié en direct sur l'épisode (id 70109436) : 27 pistes `captions`
            //   (ar, da, de, el, en[CC], es-ES, es, fi, fr, he, id, it, ja, ko, nb, nl, pl,
            //   pt-BR, pt, ro, ru, sv, th, tr, vi, zh-Hans, zh-Hant) + 1 `thumbnails` ignorée.
            //   Le français est marqué `default` UNIQUEMENT sur le serveur [VOSTFR] : sur le
            //   serveur [VF] les pistes restent disponibles mais aucune n'est activée d'office.
            val sousTitres = mutableListOf<Video.Subtitle>()
            var aDuFrancais = false
            entry.optJSONArray("tracks")?.let { tr ->
                for (i in 0 until tr.length()) {
                    val t = tr.optJSONObject(i) ?: continue
                    if (!t.optString("kind").equals("captions", ignoreCase = true)) continue
                    var f = t.optString("file")
                    if (f.isBlank()) continue
                    if (f.startsWith("//")) f = "https:$f"
                    val lg = t.optString("language").lowercase()
                    val estFr = lg == "fr" || lg.startsWith("fr-") || lg.startsWith("fr.")
                    if (estFr) aDuFrancais = true
                    sousTitres.add(
                        Video.Subtitle(
                            label = nomLangueSousTitre(lg, t.optString("label")),
                            file = f,
                            default = estFr && vostfrDemande,
                        )
                    )
                }
            }
            if (vostfrDemande && !aDuFrancais) {
                // Pas de piste FR ici → on échoue au lieu de servir de la VO étiquetée VOSTFR.
                throw IllegalStateException("NetMirror: aucun sous-titre français pour ce contenu")
            }
            // Français EN PREMIER dans la liste, puis les autres par ordre alphabétique.
            sousTitres.sortWith(compareBy({ if (it.default || it.label.startsWith("Français")) 0 else 1 }, { it.label }))
            Log.d(TAG, "sous-titres : ${sousTitres.size} piste(s)${if (aDuFrancais) " (dont FR)" else ""}, vostfr=$vostfrDemande → ${sousTitres.take(4).joinToString { it.label }}")
            // 2026-07-08 (fix « pub sur la vidéo ») : le champ `file` est RELATIF (/hls/<id>…).
            //   VÉRIFIÉ en direct : sur net77 le m3u8 = 200 #EXTM3U (vrai CDN freecdn200), alors
            //   que sur net52 (notre ancien MAIN_URL) le /hls/ sert la page « STOP Abuse ». Le
            //   token du file est basé sur l'id (domaine-agnostique) → on colle le domaine COURANT
            //   qui marche (net77) devant le path relatif.
            val videoBase = MAIN_URL // net52.cc (domaine de l'app officielle)
            val absUrl = when {
                file.startsWith("http") -> file
                file.startsWith("/") -> "$videoBase$file"
                else -> "$videoBase/$file"
            }

            val vidHeaders = buildMap {
                put("User-Agent", NM_UA)
                put("Cookie", fullCookie)
                put("Referer", "$MAIN_URL/")
            }

            // 2026-07-08 (user : « rejette les m3u8 trop courts ») : en accès ANONYME, NetMirror
            //   sert parfois un PREVIEW de ~10 min (reel d'affiches + audio) au lieu du film/épisode
            //   complet. On mesure la durée réelle du m3u8 (master → 1re variante → somme des
            //   #EXTINF) et on REJETTE si c'est trop court pour un film/épisode → le player bascule
            //   automatiquement sur le serveur suivant (backups Cloudstream/Movix/… qui ont le vrai
            //   contenu). Fail-open : si la mesure échoue (null), on NE rejette PAS (on garde le flux).
            // 2026-07-08 (user : « remets l'état audio+pub ») : gate anti-preview DÉSACTIVÉ — on
            //   laisse jouer le master masqué (audio réel + vidéo preview) pour retrouver l'audio.
            // 2026-07-08 (SOLUTION token-free) : on tente de reconstruire le VRAI film à partir du
            //   master masqué (audio réel + segments vidéo libres). Si ça marche → vrai film dans
            //   ExoPlayer. Sinon → fallback audio+pub.
            val realUri = runCatching { reconstructRealFilm(absUrl, vidHeaders) }.getOrNull()
            if (realUri != null) {
                Log.d(TAG, "getVideo OK (VRAI film reconstruit token-free): $realUri")
                return@use Video(
                    source = realUri,
                    headers = vidHeaders,
                    // 2026-08-08 : les 27 pistes sont attachées au serveur [VOSTFR] uniquement,
                    //   français EN TÊTE et marqué `default` (user : « pour le VOSTFR on choisit
                    //   d'abord et en priorité VOSTFR »). On ne les met PAS sur le serveur [VF] :
                    //   le lecteur force la sélection de la 1ʳᵉ piste dès qu'il y en a une, on
                    //   allumerait donc des sous-titres sur une lecture déjà doublée.
                    subtitles = if (vostfrDemande) sousTitres else emptyList(),
                )
            }
            // 2026-07-09 (user "pas le droit à l'échec, pas le droit à la pub") : si la
            //   reconstruction échoue, on NE renvoie PLUS le master masqué (audio+pub). On lève une
            //   erreur → le player passe au serveur suivant au lieu d'afficher la pub.
            Log.w(TAG, "getVideo : reconstruction KO → PAS de pub, on échoue: ${absUrl.take(70)}…")
            throw Exception("NetMirror: reconstruction du vrai film impossible (pas de pub)")
        }
    }

    /**
     * 2026-07-08 — Récupère l'URL du VRAI film NetMirror (asset = contentId, non masqué), SANS
     * COMPTE. Recette vérifiée en direct :
     *   1. host CDN réel = host de la piste AUDIO du master /hls/<id>.m3u8 (files/<id>).
     *   2. token via POST net77.cc/play.php (id=<contentId>) → { "h": "in=…::fp::p::…" } (anonyme).
     *   3. URL = https://<host>/files/<contentId>/1080p/1080p.m3u8?<token>.
     * Retourne null si un maillon manque (→ fallback preview/gate côté appelant).
     */
    private suspend fun resolveRealFilm(contentId: String, ottCode: String): String? = withContext(Dispatchers.IO) {
        try {
            val ua = NM_UA
            // 1) token via net77.cc/play.php (POST id=<id>, accountless — vérifié)
            val playReq = Request.Builder()
                .url("https://net77.cc/play.php")
                .post(FormBody.Builder().add("id", contentId).build())
                .header("User-Agent", ua)
                .header("Ott", ottCode)
                .header("Origin", "https://net77.cc")
                .header("Referer", "https://net77.cc/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val playBody = httpClientFollowRedirects.newCall(playReq).execute().use {
                if (!it.isSuccessful) return@withContext null
                it.body?.string()
            } ?: return@withContext null
            val h = runCatching { JSONObject(playBody).optString("h") }.getOrNull()
                ?.takeIf { it.contains("::fp") } ?: return@withContext null

            // 2) La page player net52.cc/play.php?id&<h> RE-SIGNE le token et charge le vrai m3u8
            //    (asset = contentId), MAIS elle refuse hors iframe (err 1003). On la charge donc
            //    DANS un iframe (base net77) au sein d'une WebView cachée, et on INTERCEPTE le
            //    m3u8 réel qu'elle charge (façon "Downloader"). C'est CE lien qu'on donne à ExoPlayer.
            harvestRealM3u8(contentId, h)
        } catch (e: Exception) {
            Log.w(TAG, "resolveRealFilm KO: ${e.message}")
            null
        }
    }

    /**
     * Charge la page player imbriquée (net52.cc/play.php?id&in=<h>) dans un iframe au sein d'une
     * WebView cachée, et intercepte via shouldInterceptRequest le VRAI m3u8 (path /files/<id>/…m3u8
     * avec token) que le player charge. Retourne cette URL (à jouer en natif ExoPlayer) ou null.
     */
    private suspend fun harvestRealM3u8(contentId: String, h: String): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                var webView: android.webkit.WebView? = null
                var finished = false
                fun finish(result: String?) {
                    if (finished) return
                    finished = true
                    runCatching {
                        webView?.let { w -> w.stopLoading(); (w.parent as? android.view.ViewGroup)?.removeView(w); w.destroy() }
                    }
                    webView = null
                    if (cont.isActive) cont.resume(result) {}
                }
                try {
                    val wv = android.webkit.WebView(ctx)
                    webView = wv
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = NM_UA
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    // Le VRAI m3u8 porte l'asset = contentId (le preview porte un autre id).
                    val realPattern = Regex("/files/$contentId/[^?\"']*\\.m3u8\\?[^\"']*in=", RegexOption.IGNORE_CASE)
                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        // Injection STEALTH_JS (userAgentData/WebGL/deviceMemory) au document-start
                        //   ET à la fin → Cloudflare/Turnstile passe INVISIBLE (comme le harvest cookie),
                        //   pas de captcha affiché. Injecté aussi dans l'iframe (frame net52).
                        override fun onPageStarted(view: android.webkit.WebView?, u: String?, f: android.graphics.Bitmap?) {
                            runCatching { view?.evaluateJavascript(com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_JS, null) }
                        }
                        override fun onPageFinished(view: android.webkit.WebView?, u: String?) {
                            runCatching { view?.evaluateJavascript(com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_JS, null) }
                            Log.d(TAG, "harvest page finished: ${u?.take(50)}")
                            // 2026-07-08 : net77 reste Turnstile-bloqué même avec OS.Gatu+DNS off. Retour
                            //   à net52/mobile (qui PASSE le CF) comme page hôte + pari sur le marqueur UA
                            //   OS.Gatu v3.0 pour que play.php renvoie le vrai player (pas err 1003).
                            if (u != null && u.startsWith("https://net52.cc") && !u.contains("play.php")) {
                                val js = "(function(){var f=document.createElement('iframe');" +
                                    "f.src='https://net52.cc/play.php?id=$contentId&$h';" +
                                    "f.width=360;f.height=200;f.allow='autoplay';f.setAttribute('allowfullscreen','');" +
                                    "document.body.appendChild(f);return 'injected';})()"
                                runCatching { view?.evaluateJavascript(js) { r -> Log.d(TAG, "harvest iframe inject → ${r?.take(20)}") } }
                            }
                        }
                        override fun shouldInterceptRequest(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ): android.webkit.WebResourceResponse? {
                            val u = request?.url?.toString()
                            if (u != null) {
                                // DIAG : logguer tout ce qui ressemble à un flux/player/challenge
                                if (u.contains("m3u8") || u.contains("/files/") || u.contains("play.php") || u.contains("challenges.cloudflare")) {
                                    Log.d(TAG, "harvest req: ${u.take(90)}")
                                }
                                if (realPattern.containsMatchIn(u)) {
                                    Log.d(TAG, "harvestRealM3u8 INTERCEPTÉ: ${u.take(70)}…")
                                    mainHandler.post { finish(u) }
                                }
                                // 2026-07-08 : on NE re-fetch PLUS la page player en OkHttp (ça donnait
                                //   toujours err 1003). On la laisse se charger NATIVEMENT dans le contexte
                                //   net77 autorisé (vrais Sec-Fetch/cookies navigateur) → elle doit passer.
                            }
                            return null
                        }
                    }
                    // WebView de TAILLE RÉELLE mais HORS-ÉCRAN (translationX très négatif) → le player
                    //   de l'iframe se rend et CHARGE le m3u8 (un 1×1 ne chargeait rien). Invisible.
                    val activity = com.streamflixreborn.streamflix.StreamFlixApp.currentActivity
                    Log.d(TAG, "harvest: currentActivity=${if (activity == null) "NULL(!)" else activity.javaClass.simpleName}")
                    runCatching {
                        val w = ctx.resources.displayMetrics.widthPixels.coerceAtLeast(720)
                        val hgt = ctx.resources.displayMetrics.heightPixels.coerceAtLeast(1280)
                        activity?.addContentView(wv, android.widget.FrameLayout.LayoutParams(w, hgt))
                        wv.translationX = -100000f
                    }
                    // On charge une VRAIE page net77 (loadUrl → CF passé via stealth), puis onPageFinished
                    //   injecte l'iframe player net52 (contexte iframe valide). shouldInterceptRequest
                    //   capte alors le vrai m3u8 que le player charge.
                    wv.loadUrl("https://net52.cc/mobile/")
                    mainHandler.postDelayed({ if (!finished) { Log.w(TAG, "harvestRealM3u8 timeout"); finish(null) } }, 22_000L)
                    cont.invokeOnCancellation { mainHandler.post { finish(null) } }
                } catch (e: Throwable) {
                    Log.w(TAG, "harvestRealM3u8 error: ${e.message}")
                    finish(null)
                }
            }
        }

    /**
     * 2026-07-08 (SOLUTION TOKEN-FREE, prouvée sur Enola+PEDDI) — reconstruit le VRAI film sans
     * aucun token : les SEGMENTS vidéo `files/<asset>/1080p/<prefix>_N.jpg` sont LIBRES, et le
     * `<prefix>` = celui des segments AUDIO (manifeste audio libre, présent dans le master masqué).
     * Étapes : master masqué → URI audio réelle (asset + host CDN courant) → manifeste audio libre
     * → prefix + durée totale → énumère les segments vidéo (recherche binaire du dernier) → écrit
     * un manifeste vidéo local (segments absolus) + un master (audio réel + vidéo) → file:// pour
     * ExoPlayer. La sync A/V se fait par les PTS des segments (les #EXTINF approx n'y changent rien).
     */
    /** Wrapper public : reconstruit le MASTER en CONTENU (vidéo token-free en data: URI + audio réel)
     *  pour l'injecter dans le player web (WebView) → lecture sans pub dans le dossier TV hub. */
    suspend fun reconstructMasterForWebView(masterUrl: String, headers: Map<String, String>): String? =
        reconstructRealFilm(masterUrl, headers, webViewMode = true)

    /** 2026-07-08 (WebView TV hub) : construit le manifeste VIDÉO du vrai film token-free à partir du
     *  host CDN + asset RÉEL (obtenus de la requête audio interceptée). Le manifeste audio (CDN, libre)
     *  donne le préfixe des segments ; on énumère les segments vidéo `files/<asset>/<q>/<prefix>_N.jpg`
     *  (libres). Renvoie le m3u8 vidéo (segments absolus) à injecter à la place de la vidéo preview. */
    suspend fun buildRealVideoManifest(host: String, asset: String, headers: Map<String, String>, quality: String = "1080p"): String? = withContext(Dispatchers.IO) {
        try {
            fun httpGet(u: String): String? = runCatching {
                val b = Request.Builder().url(u); headers.forEach { (k, v) -> b.header(k, v) }
                httpClientFollowRedirects.newCall(b.build()).execute().use { if (!it.isSuccessful) null else it.body?.string() }
            }.getOrNull()
            fun segExists(u: String): Boolean = runCatching {
                val b = Request.Builder().url(u).header("Range", "bytes=0-1"); headers.forEach { (k, v) -> b.header(k, v) }
                httpClientFollowRedirects.newCall(b.build()).execute().use { it.code == 200 || it.code == 206 }
            }.getOrDefault(false)

            // 1) manifeste audio (libre) → préfixe + durée totale
            var audioManifest: String? = null
            for (t in 0..3) { audioManifest = httpGet("https://$host/files/$asset/a/$t/$t.m3u8"); if (audioManifest != null && audioManifest!!.contains("#EXTINF")) break }
            val am = audioManifest ?: return@withContext null
            val prefix = am.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.substringBefore("_") ?: return@withContext null
            var totalDur = 0.0
            am.lineSequence().forEach { if (it.startsWith("#EXTINF:")) totalDur += it.substringAfter(":").substringBefore(",").trim().toDoubleOrNull() ?: 0.0 }

            val base = "https://$host/files/$asset/$quality/"
            fun segUrl(i: Int) = "$base${prefix}_${i.toString().padStart(3, '0')}.jpg"
            if (!segExists(segUrl(0))) return@withContext null
            var lo = 0; var hi = 1
            while (hi < 20000 && segExists(segUrl(hi))) { lo = hi; hi *= 2 }
            while (lo + 1 < hi) { val mid = (lo + hi) / 2; if (segExists(segUrl(mid))) lo = mid else hi = mid }
            val count = lo + 1
            if (count < 30) return@withContext null
            val avg = if (totalDur > 0) totalDur / count else 4.0
            val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n")
                .append("#EXT-X-TARGETDURATION:").append(kotlin.math.ceil(avg * 3).toInt().coerceAtLeast(12)).append("\n#EXT-X-MEDIA-SEQUENCE:0\n")
            for (i in 0 until count) sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", avg)).append(",\n").append(segUrl(i)).append("\n")
            sb.append("#EXT-X-ENDLIST\n")
            Log.d(TAG, "buildRealVideoManifest: asset=$asset prefix=$prefix count=$count q=$quality")
            sb.toString()
        } catch (e: Exception) { Log.w(TAG, "buildRealVideoManifest KO: ${e.message}"); null }
    }

    private suspend fun reconstructRealFilm(masterUrl: String, headers: Map<String, String>, webViewMode: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            fun httpGet(u: String): String? = runCatching {
                val b = Request.Builder().url(u); headers.forEach { (k, v) -> b.header(k, v) }
                httpClientFollowRedirects.newCall(b.build()).execute().use { if (!it.isSuccessful) null else it.body?.string() }
            }.getOrNull()
            fun segExists(u: String): Boolean = runCatching {
                val b = Request.Builder().url(u).header("Range", "bytes=0-1")
                headers.forEach { (k, v) -> b.header(k, v) }
                httpClientFollowRedirects.newCall(b.build()).execute().use { it.code == 200 || it.code == 206 }
            }.getOrDefault(false)

            val master = httpGet(masterUrl) ?: run { Log.w(TAG, "reconstruct DIAG: master fetch KO $masterUrl"); return@withContext null }
            // Toutes les pistes audio du master : #EXT-X-MEDIA:TYPE=AUDIO,LANGUAGE="fra",NAME="French",…,URI="…/files/<asset>/a/…"
            data class ATrack(val lang: String, val name: String, val uri: String)
            val audioTracks = master.lineSequence()
                .filter { it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=AUDIO") }
                .mapNotNull { line ->
                    val uri = Regex("URI=\"(https?://[^\"]+/files/\\d+/a/\\d+/\\d+\\.m3u8)\"").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
                    val lang = Regex("LANGUAGE=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?.lowercase() ?: ""
                    val name = Regex("NAME=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?.lowercase() ?: ""
                    ATrack(lang, name, uri)
                }.toList()
            if (audioTracks.isEmpty()) { Log.w(TAG, "reconstruct DIAG: 0 piste audio. masterLen=${master.length} head=${master.take(160).replace("\n"," ")}"); return@withContext null }
            // Sélection langue : français d'abord, sinon anglais, sinon la 1re.
            val chosen = audioTracks.firstOrNull { it.lang.startsWith("fr") || it.name.contains("french") || it.name.contains("français") }
                ?: audioTracks.firstOrNull { it.lang.startsWith("en") || it.name.contains("english") }
                ?: audioTracks.first()
            val audioUrl = chosen.uri
            val hostAssetM = Regex("https?://([^/]+)/files/(\\d+)/a/").find(audioUrl) ?: return@withContext null
            val host = hostAssetM.groupValues[1]; val asset = hostAssetM.groupValues[2]
            Log.d(TAG, "reconstructRealFilm audio: lang=${chosen.lang} name=${chosen.name} (sur ${audioTracks.size} pistes)")
            val audioManifest = httpGet(audioUrl) ?: return@withContext null
            val firstAudioSeg = audioManifest.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@withContext null
            val prefix = firstAudioSeg.substringBefore("_")
            var totalDur = 0.0
            audioManifest.lineSequence().forEach { if (it.startsWith("#EXTINF:")) totalDur += it.substringAfter(":").substringBefore(",").trim().toDoubleOrNull() ?: 0.0 }

            // 2026-07-09 (user "multi matching — VOSTFR/autre langue doit aussi se reconstruire") :
            //   la vidéo n'est PAS toujours en 1080p (séries VOSTFR souvent 720p/480p). On essaie
            //   les qualités par ordre décroissant → plus d'échec « pas de 1080p » = plus de pub.
            var videoBase: String? = null
            for (q in listOf("1080p", "720p", "480p", "360p", "240p")) {
                val base = "https://$host/files/$asset/$q/"
                if (segExists("$base${prefix}_000.jpg")) { videoBase = base; Log.d(TAG, "reconstructRealFilm quality=$q"); break }
            }
            if (videoBase == null) { Log.w(TAG, "reconstruct DIAG: 0 segment vidéo (host=$host asset=$asset prefix=$prefix) — aucune qualité 1080p→240p"); return@withContext null }
            fun segUrl(i: Int) = "$videoBase${prefix}_${i.toString().padStart(3, '0')}.jpg"
            if (!segExists(segUrl(0))) return@withContext null
            // recherche binaire du dernier segment
            var lo = 0; var hi = 1
            while (hi < 20000 && segExists(segUrl(hi))) { lo = hi; hi *= 2 }
            while (lo + 1 < hi) { val mid = (lo + hi) / 2; if (segExists(segUrl(mid))) lo = mid else hi = mid }
            val count = lo + 1
            if (count < 30) { Log.w(TAG, "reconstruct DIAG: count=$count <30 (host=$host asset=$asset)"); return@withContext null }
            val avg = if (totalDur > 0) totalDur / count else 4.0

            // manifeste vidéo (segments absolus, token-free)
            val vsb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n")
                .append("#EXT-X-TARGETDURATION:").append(kotlin.math.ceil(avg * 3).toInt().coerceAtLeast(12)).append("\n#EXT-X-MEDIA-SEQUENCE:0\n")
            for (i in 0 until count) vsb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", avg)).append(",\n").append(segUrl(i)).append("\n")
            vsb.append("#EXT-X-ENDLIST\n")

            // Mode WebView : master AUTONOME (vidéo en data: URI, audio réel absolu) renvoyé en CONTENU.
            if (webViewMode) {
                val videoDataUri = "data:application/vnd.apple.mpegurl;base64," +
                    android.util.Base64.encodeToString(vsb.toString().toByteArray(), android.util.Base64.NO_WRAP)
                val master = "#EXTM3U\n#EXT-X-VERSION:3\n" +
                    "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"$audioUrl\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=3000000,AUDIO=\"aud\"\n$videoDataUri\n"
                Log.d(TAG, "reconstructMasterForWebView: asset=$asset prefix=$prefix count=$count dur=${totalDur.toInt()}s")
                return@withContext master
            }

            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            val vidFile = java.io.File(ctx.cacheDir, "nm_vid_$asset.m3u8").apply { writeText(vsb.toString()) }

            // master (audio réel + vidéo reconstruite)
            val msb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n")
                .append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"").append(audioUrl).append("\"\n")
                .append("#EXT-X-STREAM-INF:BANDWIDTH=3000000,AUDIO=\"aud\"\n").append(vidFile.toURI().toString()).append("\n")
            val masterFile = java.io.File(ctx.cacheDir, "nm_master_$asset.m3u8").apply { writeText(msb.toString()) }
            Log.d(TAG, "reconstructRealFilm: asset=$asset prefix=$prefix count=$count dur=${totalDur.toInt()}s → ${masterFile.toURI()}")
            masterFile.toURI().toString()
        } catch (e: Exception) {
            Log.w(TAG, "reconstructRealFilm KO: ${e.message}")
            null
        }
    }

    /**
     * 2026-07-08 — Récupère l'URL du VRAI manifeste m3u8 (les SEGMENTS sont sans token, seul le
     * manifeste l'est). Flux officiel reproduit en OkHttp avec les headers exacts :
     *   1. POST net77.cc/play.php id=<id> → { "h": "in=…::i::" } (token intermédiaire).
     *   2. GET net52.cc/play.php?id=<id>&<h> (player imbriqué) avec X-Requested-With='' + UA /OS.Gatu
     *      + Referer net77 + cookie t_hash_t → la page re-signe → contient l'URL du manifeste ::p.
     *   3. On extrait `…/files/<id>/<q>/<q>.m3u8?in=…::p`. ExoPlayer lira ce manifeste (token valide
     *      ~minutes) puis les segments RELATIFS résolus SANS token.
     * Retourne l'URL du manifeste ou null.
     */
    private suspend fun probeRealManifest(contentId: String, ottCode: String): String? = withContext(Dispatchers.IO) {
        try {
            val session = ensureCookie()
            // 1) play.php → h
            val playReq = Request.Builder()
                .url("https://net77.cc/play.php")
                .post(FormBody.Builder().add("id", contentId).build())
                .header("User-Agent", NM_UA)
                .header("Ott", ottCode)
                .header("X-Requested-With", "")
                .header("Origin", "https://net77.cc")
                .header("Referer", "https://net77.cc/")
                .build()
            val h = httpClientFollowRedirects.newCall(playReq).execute().use {
                if (!it.isSuccessful) return@withContext null
                runCatching { JSONObject(it.body?.string() ?: "").optString("h") }.getOrNull()
            }?.takeIf { it.contains("::fp") } ?: return@withContext null

            // 2) player imbriqué net52/play.php?id&h avec les headers officiels
            val nestReq = Request.Builder()
                .url("https://net52.cc/play.php?id=$contentId&$h")
                .header("User-Agent", NM_UA)
                .header("Ott", ottCode)
                .header("X-Requested-With", "")
                .header("Referer", "https://net77.cc/")
                .header("Cookie", "$session; ott=$ottCode; hd=on")
                .build()
            val nestBody = httpClientFollowRedirects.newCall(nestReq).execute().use { it.body?.string() } ?: return@withContext null
            val is1003 = nestBody.contains("1003")
            val manifest = Regex("https?://[^\"'\\s<>]+/files/$contentId/[^\"'\\s<>]*\\.m3u8\\?[^\"'\\s<>]*in=[^\"'\\s<>]*")
                .find(nestBody)?.value
            Log.d(TAG, "probeRealManifest: nestLen=${nestBody.length} 1003=$is1003 manifest=${manifest?.take(60) ?: "NULL"}")
            manifest
        } catch (e: Exception) {
            Log.w(TAG, "probeRealManifest KO: ${e.message}")
            null
        }
    }

    /**
     * Mesure la durée totale (secondes) d'un flux HLS m3u8, en suivant un master vers sa
     * 1re variante puis en sommant les #EXTINF. Retourne null si indéterminable (fail-open :
     * l'appelant ne doit PAS rejeter sur null). Utilisé pour écarter les previews courts.
     */
    private fun measureM3u8DurationSec(url: String, headers: Map<String, String>): Double? {
        fun fetchText(u: String): String? = runCatching {
            val b = Request.Builder().url(u)
            headers.forEach { (k, v) -> b.header(k, v) }
            httpClientFollowRedirects.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        }.getOrNull()

        fun sumExtinf(body: String): Double {
            var total = 0.0
            for (line in body.lineSequence()) {
                if (line.startsWith("#EXTINF:")) {
                    val v = line.substringAfter("#EXTINF:").substringBefore(",").trim()
                    total += v.toDoubleOrNull() ?: 0.0
                }
            }
            return total
        }

        fun resolveRel(base: String, rel: String): String = when {
            rel.startsWith("http") -> rel
            rel.startsWith("/") -> {
                val m = Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1) ?: return rel
                "$m$rel"
            }
            else -> base.substringBeforeLast("/") + "/" + rel
        }

        val body = fetchText(url) ?: return null
        if (!body.contains("#EXTM3U")) return null
        // Master → suivre la 1re variante (celle qui porte les #EXTINF).
        if (body.contains("#EXT-X-STREAM-INF")) {
            val variant = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
            val vurl = resolveRel(url, variant)
            val vbody = fetchText(vurl) ?: return null
            if (vbody.contains("#EXT-X-STREAM-INF")) return null // master imbriqué → on renonce (fail-open)
            val d = sumExtinf(vbody)
            return if (d > 0.0) d else null
        }
        val d = sumExtinf(body)
        return if (d > 0.0) d else null
    }

    /**
     * Résout l'ID d'un épisode spécifique via le backend NetMirror.
     *
     * L'API post.php retourne les épisodes dans un tableau plat avec le champ
     * "s" = "S1"/"S2"/etc. et "ep" = "E1"/"E2"/etc. Chaque entrée a un "id"
     * qui est l'ID de contenu à passer à player.php.
     *
     * Si post.php n'a pas les épisodes (certains providers les paginent via
     * episodes.php), on tombe en fallback sur episodes.php.
     */
    /**
     * Nom FRANÇAIS d'une piste de sous-titres à partir de son code ISO.
     *
     * 2026-08-08 : sans ça le menu du lecteur affichait « Inconnu » (capture user) — il se base
     *   sur le libellé qu'on lui passe, et « French » brut ne lui suffisait pas. On garde le
     *   suffixe éventuel de leur libellé ([CC], Brésil, Simplifié…) pour distinguer les
     *   variantes d'une même langue.
     */
    private fun nomLangueSousTitre(code: String, libelleSource: String): String {
        val base = code.substringBefore('-').substringBefore('.').lowercase()
        val nom = when (base) {
            "fr" -> "Français"; "en" -> "Anglais"; "es" -> "Espagnol"; "de" -> "Allemand"
            "it" -> "Italien"; "pt" -> "Portugais"; "nl" -> "Néerlandais"; "pl" -> "Polonais"
            "ru" -> "Russe"; "ar" -> "Arabe"; "he" -> "Hébreu"; "tr" -> "Turc"
            "el" -> "Grec"; "da" -> "Danois"; "sv" -> "Suédois"; "nb", "no" -> "Norvégien"
            "fi" -> "Finnois"; "ro" -> "Roumain"; "cs" -> "Tchèque"; "hu" -> "Hongrois"
            "ja" -> "Japonais"; "ko" -> "Coréen"; "zh" -> "Chinois"; "th" -> "Thaï"
            "vi" -> "Vietnamien"; "id" -> "Indonésien"; "ms" -> "Malais"; "hi" -> "Hindi"
            "ta" -> "Tamoul"; "te" -> "Télougou"; "ca" -> "Catalan"; "eu" -> "Basque"
            "tl", "fil" -> "Filipino"; "uk" -> "Ukrainien"; "bg" -> "Bulgare"; "hr" -> "Croate"
            "sr" -> "Serbe"; "sk" -> "Slovaque"; "sl" -> "Slovène"; "et" -> "Estonien"
            "lv" -> "Letton"; "lt" -> "Lituanien"; "fa" -> "Persan"; "bn" -> "Bengali"
            else -> libelleSource.takeIf { it.isNotBlank() } ?: code.uppercase()
        }
        // Variante régionale ou mention entre crochets → on la garde pour ne pas avoir
        // deux entrées « Espagnol » indiscernables (es / es-ES) ou « Anglais » / « [CC] ».
        val precision = when {
            libelleSource.contains("[CC]", ignoreCase = true) -> " [CC]"
            code.contains('-') -> " (" + code.substringAfter('-').uppercase() + ")"
            else -> ""
        }
        return nom + precision
    }

    /** Normalise un titre d'épisode pour la comparaison (casse, ponctuation, accents). */
    private fun cleTitreEpisode(t: String): String =
        java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "")

    private suspend fun resolveEpisodeId(
        ottCode: String,
        showId: String,
        seasonNum: Int,
        episodeNum: Int,
        /** Titre original (anglais) de l'épisode côté TMDB — prioritaire sur le numéro. */
        titreVo: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val platform = OttPlatform.entries.find { it.ottCookie == ottCode } ?: return@withContext null
        try {
            val cookies = buildCookies(platform)
            val ts = System.currentTimeMillis() / 1000

            // 1) post.php — contient souvent TOUS les épisodes en tableau plat
            val postUrl = "$MAIN_URL${platform.postPath}?id=$showId&t=$ts"
            val postReq = Request.Builder()
                .url(postUrl)
                .header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "$MAIN_URL/")
                .build()

            val postResp = httpClientFollowRedirects.newCall(postReq).execute()
            val postBody = postResp.use { it.body?.string() } ?: return@withContext null
            val postJson = runCatching { JSONObject(postBody) }.getOrNull() ?: return@withContext null

            // Les épisodes sont un tableau plat : [{id, t, s:"S3", ep:"E1", time}, ...]
            val episodes = postJson.optJSONArray("episodes")
            if (episodes != null) {
                val targetS = "S$seasonNum"
                val targetE = "E$episodeNum"
                // 2026-08-08 : TITRE D'ABORD. Sur Star Trek (1966) leur S1E1 = « Pilot: The Cage »
                //   alors que TMDB S01E01 = « The Man Trap » → tout décalé d'un cran. On cherche
                //   donc l'épisode par son titre original, dans la bonne saison, avant de se
                //   rabattre sur le numéro.
                if (!titreVo.isNullOrBlank()) {
                    val cible = cleTitreEpisode(titreVo)
                    for (i in 0 until episodes.length()) {
                        val ep = episodes.optJSONObject(i) ?: continue
                        if (ep.optString("s", "").uppercase() != targetS) continue
                        val t = ep.optString("t", "")
                        if (t.isBlank() || cleTitreEpisode(t) != cible) continue
                        val id = ep.optString("id").takeIf { it.isNotBlank() } ?: continue
                        Log.d(TAG, "resolveEpisodeId via TITRE: '$titreVo' → $id (${ep.optString("s")}${ep.optString("ep")})")
                        return@withContext id
                    }
                    Log.d(TAG, "resolveEpisodeId : titre '$titreVo' introuvable dans $targetS → repli sur le numéro")
                }
                for (i in 0 until episodes.length()) {
                    val ep = episodes.optJSONObject(i) ?: continue
                    val s = ep.optString("s", "").uppercase()
                    val e = ep.optString("ep", "").uppercase()
                    if (s == targetS && e == targetE) {
                        val id = ep.optString("id").takeIf { it.isNotBlank() }
                        if (id != null) {
                            Log.d(TAG, "resolveEpisodeId via post.php: $targetS$targetE → $id")
                            return@withContext id
                        }
                    }
                }
                // Fallback : parfois ep="1" au lieu de "E1"
                for (i in 0 until episodes.length()) {
                    val ep = episodes.optJSONObject(i) ?: continue
                    val s = ep.optString("s", "").uppercase().removePrefix("S")
                    val e = ep.optString("ep", "").uppercase().removePrefix("E")
                    if (s == seasonNum.toString() && e == episodeNum.toString()) {
                        val id = ep.optString("id").takeIf { it.isNotBlank() }
                        if (id != null) {
                            Log.d(TAG, "resolveEpisodeId via post.php (fallback): S${seasonNum}E${episodeNum} → $id")
                            return@withContext id
                        }
                    }
                }
            }

            // 2) Fallback : episodes.php (paginé, pour les providers qui séparent)
            // Repli par NUMÉRO gardé de côté : on ne s'en sert que si aucune page ne contient
            // le titre recherché (sinon on renverrait le mauvais épisode dès la 1ʳᵉ page).
            var replliParNumero: String? = null
            val seasonData = postJson.optJSONArray("season")
            if (seasonData != null) {
                for (i in 0 until seasonData.length()) {
                    val s = seasonData.optJSONObject(i) ?: continue
                    val sNum = s.optString("s", "").uppercase().removePrefix("S")
                    if (sNum == seasonNum.toString()) {
                        val seasonId = s.optString("id").takeIf { it.isNotBlank() } ?: continue
                        // Paginer episodes.php
                        var page = 1
                        while (page <= 10) {
                            val epUrl = "$MAIN_URL${platform.episodesPath}?s=$seasonId&series=$showId&t=$ts&page=$page"
                            val epReq = Request.Builder()
                                .url(epUrl)
                                .header("Cookie", cookies)
                                .header("User-Agent", "Mozilla/5.0")
                                .header("Referer", "$MAIN_URL/")
                                .build()
                            val epResp = httpClientFollowRedirects.newCall(epReq).execute()
                            val epBody = epResp.use { it.body?.string() } ?: break
                            val epJson = runCatching { JSONObject(epBody) }.getOrNull() ?: break
                            val epArr = epJson.optJSONArray("episodes") ?: break
                            // 2026-08-08 (2ᵉ passe) : le TITRE d'abord, ici aussi.
                            //   Relevé dans les logs : « titre 'The Man Trap' introuvable dans S1
                            //   → repli sur le numéro ». La recherche par titre n'existait que sur
                            //   la branche post.php ; or sur Star Trek les épisodes ne sont PAS
                            //   dans post.php (`episodes` vide) — ils arrivent par episodes.php,
                            //   qui n'avait droit qu'au numéro. D'où le pilote joué à la place du
                            //   1er épisode, malgré le correctif.
                            //   Les pages étant paginées, on mémorise le repli par numéro et on
                            //   ne le rend QU'À LA FIN, une fois toutes les pages parcourues
                            //   sans avoir trouvé le titre.
                            if (!titreVo.isNullOrBlank()) {
                                val cible = cleTitreEpisode(titreVo)
                                for (j in 0 until epArr.length()) {
                                    val ep = epArr.optJSONObject(j) ?: continue
                                    val t = ep.optString("t", "")
                                    if (t.isBlank() || cleTitreEpisode(t) != cible) continue
                                    val id = ep.optString("id").takeIf { it.isNotBlank() } ?: continue
                                    Log.d(TAG, "resolveEpisodeId via TITRE (episodes.php): '$titreVo' → $id (${ep.optString("s")}${ep.optString("ep")})")
                                    return@withContext id
                                }
                            }
                            for (j in 0 until epArr.length()) {
                                val ep = epArr.optJSONObject(j) ?: continue
                                val eNum = ep.optString("ep", "").uppercase().removePrefix("E")
                                if (eNum == episodeNum.toString()) {
                                    val id = ep.optString("id").takeIf { it.isNotBlank() }
                                    if (id != null && replliParNumero == null) replliParNumero = id
                                }
                            }
                            val next = epJson.optString("nextPageShow")
                            if (next.isBlank() || next == "null") break
                            page++
                        }
                        break
                    }
                }
            }
            if (replliParNumero != null) {
                Log.d(TAG, "resolveEpisodeId via NUMÉRO (episodes.php): S${seasonNum}E$episodeNum → $replliParNumero" +
                    if (!titreVo.isNullOrBlank()) " — titre '$titreVo' introuvable" else "")
            }
            replliParNumero
        } catch (e: Exception) {
            Log.w(TAG, "resolveEpisodeId error: ${e.message}")
            null
        }
    }

    /**
     * Récupère les langues audio disponibles pour un contenu via post.php.
     * Retourne une liste structurée [LangInfo] avec le code ISO, le label
     * lisible (VF/EN/etc.) et l'ID de langue du backend (pour forcer l'audio).
     */
    private suspend fun fetchLanguageInfos(
        platform: OttPlatform,
        contentId: String,
    ): List<LangInfo> = withContext(Dispatchers.IO) {
        try {
            val cookies = buildCookies(platform)
            val ts = System.currentTimeMillis() / 1000
            val url = "$MAIN_URL${platform.postPath}?id=$contentId&t=$ts"
            val req = Request.Builder()
                .url(url)
                .header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "$MAIN_URL/")
                .build()

            val resp = httpClientFollowRedirects.newCall(req).execute()
            val body = resp.use { it.body?.string() } ?: return@withContext emptyList()
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyList()
            val langArr = json.optJSONArray("lang") ?: return@withContext emptyList()

            val result = mutableListOf<LangInfo>()
            for (i in 0 until langArr.length()) {
                val l = langArr.optJSONObject(i) ?: continue
                val code = l.optString("s", "").take(3).uppercase()
                val langId = l.optString("id").takeIf { it.isNotBlank() }
                    ?: l.optString("lang_id").takeIf { it.isNotBlank() }
                val label = when (code) {
                    "FRA" -> "VF"
                    "ENG" -> "EN"
                    "HIN" -> "HI"
                    "JPN" -> "JA"
                    "SPA" -> "ES"
                    "DEU" -> "DE"
                    "ITA" -> "IT"
                    "KOR" -> "KO"
                    "TAM" -> "TA"
                    "TEL" -> "TE"
                    else -> code.takeIf { it.isNotBlank() } ?: continue
                }
                result.add(LangInfo(code = code, label = label, langId = langId))
            }
            result.distinctBy { it.code }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
