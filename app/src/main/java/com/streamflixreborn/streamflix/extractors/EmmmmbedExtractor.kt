package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.JavascriptInterface
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
 * 2026-07-13 — Emmmmbed / "PlayerBx" (mirror du player RNVIDS lecteurvideo.com,
 *   un des serveurs de Coflix Boston).
 *
 * URL embed : `https://emmmmbed.com/embed/<id>?t=<JWT>` (JWT signé par lecteurvideo/coflix,
 *   pid = id du contenu, exp ~30 min). La page charge video.js + crypto-js et DÉCHIFFRE
 *   la source CÔTÉ CLIENT (AES) : le vrai flux est un **.mp4 direct sur `*.cdn.rumble.cloud`**
 *   (vérifié en direct : le player lit `hugh.cdn.rumble.cloud/video/.../X.aaa.mp4`, durée =
 *   celle du film). Rien d'exploitable dans `/api/player-ads` (juste la config pub).
 *
 * Le déchiffrement étant 100% JS (crypto-js), on ne le reproduit pas en Kotlin (fragile) :
 *   on charge la page dans une WebView headless (Referer = lecteurvideo pour passer la porte
 *   JWT), on laisse le JS déchiffrer, et on CAPTE l'URL du .mp4 rumble.cloud via
 *   shouldInterceptRequest (+ fallback polling de `<video>.currentSrc`). ExoPlayer joue ensuite
 *   le mp4 en natif avec Referer=emmmmbed. Pattern calqué sur FranimeExtractor.
 */
class EmmmmbedExtractor : Extractor() {

    override val name = "Emmmmbed"
    override val mainUrl = "https://emmmmbed.com"

    // JWT éphémère dans l'URL → jamais de cache (re-résoudre à chaque clic).
    override val cacheTtlMs: Long = 0L

    private val context = StreamFlixApp.instance.applicationContext

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Referer/Origin de l'agrégateur RNVIDS qui embarque emmmmbed. La page emmmmbed est
     *  servie via ce contexte ; on le rejoue pour éviter tout gating anti-hotlink. */
    private val RNVIDS_REFERER = "https://lecteurvideo.com/"

    /** Hosts pub/tracking à couper dans la WebView (perf + pas de pop). */
    private val BLOCKED_HOSTS = listOf(
        "googlesyndication", "doubleclick", "googleads", "imasdk.googleapis",
        "popads", "popunder", "popcash", "propellerads",
        "exoclick", "juicyads", "trafficjunky",
        "googletagmanager", "google-analytics",
        "mc.yandex.ru", "yandex.ru", "a-ads.com", "translate.googleapis",
    )

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract($link)")
        val mp4 = captureMp4InWebView(link, timeoutMs = 45_000L)
            ?: throw Exception("Emmmmbed: failed to capture mp4 from $link")
        Log.d(TAG, "captured mp4=${mp4.take(80)}")
        return Video(
            source = mp4,
            headers = mapOf(
                "Referer" to "https://emmmmbed.com/",
                "Origin" to "https://emmmmbed.com",
                "User-Agent" to ANDROID_CHROME_UA,
            ),
        )
    }

    private fun isTargetMedia(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        return lower.contains("rumble.cloud") ||
            (lower.contains(".mp4") && !BLOCKED_HOSTS.any { lower.contains(it) })
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
    private suspend fun captureMp4InWebView(url: String, timeoutMs: Long): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    var resolved = false
                    var destroyHook: (() -> Unit)? = null
                    fun resolve(value: String?) {
                        if (!resolved && cont.isActive) {
                            resolved = true
                            cont.resume(value)
                        }
                        destroyHook?.invoke()
                        destroyHook = null
                    }

                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = ANDROID_CHROME_UA
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                    }
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    // Pont JS→Android : le polling DOM renvoie la src du <video>.
                    webView.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onSrc(src: String) {
                            if (resolved) return
                            if (isTargetMedia(src)) {
                                Log.d(TAG, "MP4 via <video>.src poll: $src")
                                android.os.Handler(android.os.Looper.getMainLooper()).post { resolve(src) }
                            }
                        }
                    }, "Android")

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host?.lowercase() ?: ""
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            if (isTargetMedia(reqUrl)) {
                                Log.d(TAG, "MP4 CAPTURED (intercept): $reqUrl")
                                resolve(reqUrl)
                                // On laisse passer la requête (null) n'est pas nécessaire :
                                // on a l'URL, on bloque pour éviter de streamer dans la WebView.
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (resolved || view == null) return
                            // Fallback : PlayerBx pose la src déchiffrée sur le <video> ;
                            // on la lit toutes les 1,5 s pendant 40 s (au cas où le .mp4
                            // serait chargé via blob/MSE et pas interceptable en réseau).
                            val startMs = System.currentTimeMillis()
                            val maxPollMs = 40_000L
                            fun poll() {
                                if (resolved) return
                                view.evaluateJavascript(
                                    "(function(){var v=document.querySelector('video');return v?(v.currentSrc||v.src||''):'';})();"
                                ) { result ->
                                    val src = result?.trim('"')?.replace("\\/", "/") ?: ""
                                    if (isTargetMedia(src)) {
                                        Log.d(TAG, "MP4 from <video> poll: $src")
                                        resolve(src)
                                    } else if (System.currentTimeMillis() - startMs < maxPollMs) {
                                        view.postDelayed({ poll() }, 1_500L)
                                    }
                                }
                            }
                            view.postDelayed({ poll() }, 1_500L)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) { /* ignore */ }
                    }

                    // Charge la page emmmmbed avec le Referer RNVIDS (porte JWT).
                    Log.d(TAG, "Loading emmmmbed page: $url")
                    webView.loadUrl(url, mapOf("Referer" to RNVIDS_REFERER))

                    destroyHook = {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                webView.stopLoading()
                                webView.removeJavascriptInterface("Android")
                                webView.destroy()
                            } catch (_: Exception) { }
                        }
                    }
                    cont.invokeOnCancellation {
                        resolved = true
                        destroyHook?.invoke()
                        destroyHook = null
                    }
                }
            }
        }

    companion object {
        private const val TAG = "EmmmmbedExtractor"
    }
}
