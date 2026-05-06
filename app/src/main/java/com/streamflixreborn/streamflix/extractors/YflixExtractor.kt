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
        val hlsUrl = extractByIntercepting(link)
            ?: throw Exception("Yflix: Could not find HLS source in $link")
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
    private suspend fun extractByIntercepting(url: String): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(35_000L) {
                suspendCancellableCoroutine { cont ->
                    var resolved = false

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
                            // Allow yflix + rapidshareee + common HLS hosts.
                            // Block anything else (ads, popups, ww1/ww2 redirects)
                            val isAllowed = ALLOWED_HOSTS.any { host.endsWith(it) }
                            return !isAllowed
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

                            // Filet de sécurité : si après 25s rien capté, abandon
                            view.postDelayed({
                                if (!resolved) {
                                    android.util.Log.w("YflixExtractor", "Timeout — no m3u8 captured")
                                    resolve(null)
                                }
                            }, 25_000L)
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
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
        }

    companion object {
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
