package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * YflixExtractor — extrait le m3u8 final des pages yflix.to.
 *
 * yflix.to ne se contente pas d'embed un host externe : ils ont un système
 * anti-scraping qui bloque les calls AJAX directs (token JS computé runtime
 * via window.__$ → token dérivé envoyé en `_=` query param sur les calls
 * /ajax/episodes/list et /ajax/links/list).
 *
 * Architecture côté yflix :
 *   yflix.to/watch/{slug}.{id}                       ← page detail (FR via Google Translate)
 *     └─> <iframe src="yflix.to/iframe/{token}">     ← wrapper yflix interne
 *           └─> <iframe src="rapidshareee.site/e/X"> ← VRAI host externe
 *                 └─> JWPlayer charge le m3u8
 *
 * Stratégie : on utilise WebView pour laisser le JS yflix calculer son
 * token, charger l'iframe, charger le nested iframe, et finalement déclencher
 * la requête m3u8 qu'on intercepte via shouldInterceptRequest.
 *
 * Avantage : pas de Cloudflare challenge sur cette chaîne (testé), donc
 * extraction rapide (~5-10s) comparée à VidMoLy (~20-30s avec CF).
 *
 * Accepte :
 *   - https://yflix.to/watch/{slug}.{id}
 *   - https://yflix.to/iframe/{token}
 *   - URLs avec hash #ep=S,E pour les épisodes
 */
open class YflixExtractor : Extractor() {
    override val name = "Yflix"
    override val mainUrl = "https://yflix.to/"
    override val aliasUrls = listOf<String>()

    private val context = StreamFlixApp.instance.applicationContext

    // UA Android Chrome — match le runtime WebView, moins flagué bot
    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video {
        // 2026-05-21 (user "yflix apparaît encore en multi source — splitte-le par
        //   serveur comme Videasy") : l'index du serveur yflix est encodé via
        //   `yfsrv=N` dans le fragment. Chaque serveur yflix est exposé comme une
        //   source séparée (Yflix Serveur 1/2…) ; ici on cible CE serveur précis.
        val srvIndex = Regex("yfsrv=(\\d+)").find(link)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val cleanLink = link.replace(Regex("[#&]yfsrv=\\d+"), "")
        val hlsUrl = extractByIntercepting(cleanLink, srvIndex)
            ?: throw Exception("Yflix: aucun flux pour serveur $srvIndex de $cleanLink")
        return Video(
            source = hlsUrl,
            headers = mapOf(
                "Referer" to "https://yflix.to/",
                "Origin" to "https://yflix.to",
                "User-Agent" to ANDROID_CHROME_UA,
            )
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractByIntercepting(url: String, srvIndex: Int): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(50_000L) {
                suspendCancellableCoroutine { cont ->
                    var resolved = false
                    var cycleStarted = false

                    fun resolve(value: String?) {
                        if (!resolved && cont.isActive) {
                            resolved = true
                            cont.resume(value)
                        }
                    }

                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = ANDROID_CHROME_UA
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                    }

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean,
                            isUserGesture: Boolean, resultMsg: Message?
                        ): Boolean = false
                    }

                    webView.webViewClient = object : WebViewClient() {

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val host = request?.url?.host ?: return true
                            // 2026-05-21 (user "yflix bugue un peu / se lance mais rien") :
                            // yflix tourne régulièrement son host externe (rapidshareee
                            // puis d'autres). Un whitelist strict (ALLOWED_HOSTS) cassait
                            // toute la chaîne dès qu'ils changeaient de host → aucun m3u8.
                            // On laisse donc passer TOUT sauf les hosts pub/tracker connus ;
                            // le m3u8 est capté dans shouldInterceptRequest quel que soit
                            // le host, donc plus besoin de whitelister la source.
                            return BLOCKED_HOSTS.any { host.contains(it) }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host ?: ""

                            // Capture m3u8 dès qu'il apparaît
                            if (reqUrl.contains(".m3u8")) {
                                android.util.Log.d("YflixExtractor", "m3u8 INTERCEPTED: $reqUrl")
                                view?.post { resolve(reqUrl) }
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            // Block ad/tracker hosts
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return
                            android.util.Log.d("YflixExtractor", "onPageFinished: $finishedUrl")

                            // 2026-05-21 (user "yflix doit afficher TOUTES ses sources sinon
                            //   l'algo de tri ne peut pas bien le classer") : chaque serveur
                            //   yflix est exposé comme une source séparée. Ici on CIBLE le
                            //   serveur demandé (srvIndex, 1-based) en cliquant le bon
                            //   li.server[data-lid]. La liste est peuplée via un ajax token,
                            //   donc on retente le clic quelques fois jusqu'à ce qu'elle
                            //   existe ; le m3u8 est capté dans shouldInterceptRequest.
                            if (finishedUrl != null && finishedUrl.contains("/watch/") && !cycleStarted) {
                                cycleStarted = true
                                val idx = srvIndex - 1
                                for (d in longArrayOf(1_000L, 3_000L, 5_000L, 7_000L)) {
                                    view.postDelayed({
                                        if (resolved) return@postDelayed
                                        view.evaluateJavascript(
                                            "(function(){var s=document.querySelectorAll('li.server[data-lid]');" +
                                                "if(s&&s[$idx]){s[$idx].click();return s.length;}return s?s.length:0;})()",
                                            null,
                                        )
                                    }, d)
                                }
                                // Filet final : abandon si pas de m3u8 pour ce serveur précis.
                                view.postDelayed({
                                    if (!resolved) {
                                        android.util.Log.w("YflixExtractor", "Timeout — pas de m3u8 pour serveur $srvIndex")
                                        resolve(null)
                                    }
                                }, 18_000L)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            // Erreurs main frame ignorées silencieusement
                        }
                    }

                    webView.loadUrl(url, mapOf("Referer" to "https://yflix.to/"))

                    cont.invokeOnCancellation {
                        resolved = true
                        // 2026-05-11 : WebView methods MUST run on main thread (the
                        // cancellation handler may fire from DefaultExecutor when
                        // withTimeout cancels). Sans ce wrap : crash fatal
                        // "WebView method called on thread 'DefaultExecutor'".
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try { webView.stopLoading() } catch (_: Throwable) {}
                            try { webView.destroy() } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }

    companion object {
        // 2026-05-21 : auto-fallback — nb max de serveurs yflix essayés + fenêtre par serveur.
        private const val MAX_SERVERS = 4
        private const val PER_SERVER_MS = 8_000L
        // Hosts qu'on autorise à charger leurs JS/iframes pour la chaîne d'extraction
        private val ALLOWED_HOSTS = listOf(
            "yflix.to",
            "rapidshareee.site",
            "rapidshareee.com",
            "challenges.cloudflare.com", // CF JS analytics passive
        )
        // Hosts à bloquer (ad/tracker)
        private val BLOCKED_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice",
            "popads", "popunder", "popcash", "propellerads",
            "exoclick", "juicyads", "trafficjunky",
            "googletagmanager", "google-analytics",
            "sharethis.com", "platform-api.sharethis.com",
            "translate-pa.googleapis.com", "translate.google.com",
        )
    }
}
