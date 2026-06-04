package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
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
 * Meritend / SportsOnline embed extractor — capture m3u8 via WebView interception.
 *
 *  Workflow
 *  --------
 *  Le réseau sportsonline.vc / sportssonline.click sert maintenant ses streams via
 *  des embeds chez meritend.net (clappr player + p2p-media-loader). La config m3u8
 *  est cachée dans `window._econfig` (base64 multi-couches encrypté côté JS), donc
 *  pas extractable en HTTP plain. Solution : on charge la page dans un WebView,
 *  on attend que clappr fasse la requête m3u8 et on l'intercepte via
 *  `shouldInterceptRequest`.
 *
 *  Pattern identique à `YflixExtractor` (qui fait la même chose pour yflix.to).
 *
 *  Hosts gérés : meritend.net, sportssonline.click, sportsonline.* (génériques),
 *  + toutes les CDN m3u8 connues (cloudflare/wasabi/edge), + clappr et
 *  p2p-media-loader CDN jsdelivr.
 */
open class MeritendExtractor : Extractor() {
    override val name = "Meritend"
    // 2026-06-02 : meritend.net renomme en xstream.to (301 redirect, meme contenu).
    override val mainUrl = "https://xstream.to/"
    override val aliasUrls = listOf("https://meritend.net",
        "https://v3.sportssonline.click/",
        "https://sportssonline.click/",
        "https://sportsonline.vc/",
    )

    private val context = StreamFlixApp.instance.applicationContext

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video {
        val hlsUrl = extractByIntercepting(link)
            ?: throw Exception("Meritend: Could not find HLS source in $link")
        return Video(
            source = hlsUrl,
            headers = mapOf(
                "Referer" to "https://meritend.net/",
                "Origin" to "https://meritend.net",
                "User-Agent" to ANDROID_CHROME_UA,
            ),
            // Force HLS — l'URL contient `.m3u8` mais avec query string `?s=...&e=...`
            // qui empêche l'auto-détection ExoPlayer (qui essaie Mp4/Mp3 etc.).
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractByIntercepting(url: String): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000L) {
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
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
                            // Allow embeds ecosystem + clappr CDNs.
                            // Block ads/popups/redirects.
                            val isAllowed = ALLOWED_HOSTS.any { host.endsWith(it) }
                            return !isAllowed
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host ?: ""

                            // Capture m3u8 dès qu'il apparaît.
                            // CRITIQUE : on call resolve() DIRECT depuis ce thread worker,
                            // pas via Handler(Main) — clappr bloque le UI thread avec son
                            // p2p-media-loader pendant 10+ sec, donc Main.post timeout.
                            // cont.resume est thread-safe pour CancellableContinuation.
                            if (reqUrl.contains(".m3u8")) {
                                android.util.Log.d("MeritendExtractor", "m3u8 INTERCEPTED: $reqUrl")
                                resolve(reqUrl)
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
                            android.util.Log.d("MeritendExtractor", "onPageFinished: $finishedUrl")

                            // Filet de sécurité : si après 10s rien capté, abandon
                            view.postDelayed({
                                if (!resolved) {
                                    android.util.Log.w("MeritendExtractor", "Timeout — no m3u8 captured")
                                    resolve(null)
                                }
                            }, 10_000L)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            // Erreurs main frame ignorées silencieusement
                        }
                    }

                    // Referer fait office d'authentification soft chez meritend
                    webView.loadUrl(url, mapOf("Referer" to "https://v3.sportssonline.click/"))

                    // CRITIQUE : invokeOnCancellation est appelé sur le thread coroutine
                    // (DefaultExecutor au timeout). Les méthodes WebView DOIVENT tourner
                    // sur le main thread sinon crash. On post via Handler(MainLooper).
                    cont.invokeOnCancellation {
                        resolved = true
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                webView.stopLoading()
                                webView.destroy()
                            } catch (_: Exception) { /* déjà détruite */ }
                        }
                    }
                }
            }
        }

    companion object {
        // Hosts qu'on autorise à charger pour la chaîne d'extraction
        private val ALLOWED_HOSTS = listOf(
            "meritend.net",
            "sportssonline.click",
            "sportsonline.vc",
            "sportsonline.app",
            "cdn.jsdelivr.net",      // clappr + p2p-media-loader
            "cloudflare.com",
            "challenges.cloudflare.com",
            // CDN m3u8 communs côté sport
            "cloudfront.net",
            "akamaized.net",
            "cdn77.org",
            "wasabisys.com",
            "googlevideo.com",
        )
        // Hosts ad/tracker à bloquer
        private val BLOCKED_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice",
            "popads", "popunder", "popcash", "propellerads",
            "exoclick", "juicyads", "trafficjunky",
            "googletagmanager", "google-analytics",
            "awistats.com",          // analytics meritend
            "greatdexchange.com",    // popup ad
            "exposestrat.com",       // ad lib
            "cdn-lab.shop",          // tracker
        )
    }
}
