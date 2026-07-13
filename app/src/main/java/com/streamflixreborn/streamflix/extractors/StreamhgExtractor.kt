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
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 2026-07-13 — Streamhg (host EarnVids, mirror RNVIDS lecteurvideo, serveur Coflix Boston).
 *
 * Embed = `https://streamhg.com/e/<id>`. EarnVids (JS packé jwplayer → m3u8 CDN hors-CF) MAIS
 *   page /e/ derrière Cloudflare Turnstile.
 *
 * v4 (diag on-device) : le CF **passe** (Content/Clearance true) À CONDITION de NE PAS mettre
 *   de Referer=lecteurvideo (il faisait rejeter le challenge). MAIS le passeur PARTAGÉ
 *   (WebViewResolver, WebView unique) est en contention avec les ~13 backups qui résolvent leur
 *   CF en parallèle → il renvoyait un timeout (len=33) alors que le challenge était résolu.
 *
 * → On utilise une **WebView DÉDIÉE** (zéro contention), sans Referer, STEALTH_JS injecté au
 *   document-start (passe le Turnstile comme un vrai navigateur), on déclenche le jwplayer et on
 *   **intercepte le m3u8** du CDN EarnVids. ExoPlayer le streame ensuite (Referer=streamhg).
 */
class StreamhgExtractor : Extractor() {

    override val name = "Streamhg"
    override val mainUrl = "https://streamhg.com"

    override val cacheTtlMs: Long = 0L

    private val context = StreamFlixApp.instance.applicationContext

    private val BLOCKED_HOSTS = listOf(
        "googlesyndication", "doubleclick", "googleads", "imasdk.googleapis",
        "popads", "popunder", "popcash", "propellerads",
        "exoclick", "juicyads", "trafficjunky",
        "googletagmanager", "google-analytics", "mc.yandex.ru", "a-ads.com",
    )

    private val TRIGGER_PLAY_JS = """
        (function(){
            try { if (window.jwplayer) { var p = jwplayer(); if (p && p.play) p.play(true); } } catch(e){}
            try { var v=document.querySelector('video'); if(v){v.muted=true; var pr=v.play(); if(pr&&pr.catch)pr.catch(function(){});} } catch(e){}
            try { var b=document.querySelector('.jw-icon-display,.vjs-big-play-button,[aria-label*="lay"]'); if(b)b.click(); } catch(e){}
        })();
    """.trimIndent()

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract($link)")
        val m3u8 = captureM3u8(link, timeoutMs = 40_000L)
            ?: throw Exception("Streamhg: m3u8 non capté (CF/Turnstile pas passé dans la WebView dédiée)")
        Log.d(TAG, "m3u8=${m3u8.take(90)}")
        return Video(
            source = m3u8,
            headers = mapOf(
                "Referer" to "https://streamhg.com/",
                "Origin" to "https://streamhg.com",
                "User-Agent" to WebViewResolver.STEALTH_UA,
            ),
        )
    }

    private fun isTargetMedia(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        if (BLOCKED_HOSTS.any { lower.contains(it) }) return false
        return lower.contains(".m3u8")
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
    private suspend fun captureM3u8(url: String, timeoutMs: Long): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    var resolved = false
                    var destroyHook: (() -> Unit)? = null
                    fun resolve(value: String?) {
                        if (!resolved && cont.isActive) { resolved = true; cont.resume(value) }
                        destroyHook?.invoke(); destroyHook = null
                    }

                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = WebViewResolver.STEALTH_UA
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                    }
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    webView.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onSrc(src: String) {
                            if (!resolved && isTargetMedia(src)) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post { resolve(src) }
                            }
                        }
                    }, "Android")

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?, request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host?.lowercase() ?: ""
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            if (isTargetMedia(reqUrl)) {
                                Log.d(TAG, "M3U8 CAPTURED (intercept): $reqUrl")
                                resolve(reqUrl)
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return null
                        }

                        override fun onPageStarted(
                            view: WebView?, startedUrl: String?, favicon: android.graphics.Bitmap?
                        ) {
                            // STEALTH_JS AVANT le JS de la page → Turnstile s'auto-résout.
                            view?.evaluateJavascript(WebViewResolver.STEALTH_JS, null)
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (resolved || view == null) return
                            view.evaluateJavascript(WebViewResolver.STEALTH_JS, null)
                            val startMs = System.currentTimeMillis()
                            val maxPollMs = 38_000L
                            fun poll() {
                                if (resolved) return
                                view.evaluateJavascript(TRIGGER_PLAY_JS, null)
                                view.evaluateJavascript(
                                    "(function(){try{if(window.jwplayer){var s=jwplayer().getPlaylistItem();if(s&&s.file)return s.file;}}catch(e){}" +
                                        "var v=document.querySelector('video');return v?(v.currentSrc||v.src||''):'';})();"
                                ) { result ->
                                    val src = result?.trim('"')?.replace("\\/", "/") ?: ""
                                    if (isTargetMedia(src)) { Log.d(TAG, "M3U8 poll: $src"); resolve(src) }
                                    else if (System.currentTimeMillis() - startMs < maxPollMs) {
                                        view.postDelayed({ poll() }, 1_500L)
                                    }
                                }
                            }
                            view.postDelayed({ poll() }, 2_500L)
                        }

                        override fun onReceivedError(
                            view: WebView?, request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) { /* ignore */ }
                    }

                    // PAS de Referer (le CF rejette avec Referer=lecteurvideo — prouvé on-device).
                    Log.d(TAG, "Loading streamhg (dedicated WebView, no referer): $url")
                    webView.loadUrl(url)

                    destroyHook = {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try { webView.stopLoading(); webView.removeJavascriptInterface("Android"); webView.destroy() } catch (_: Exception) {}
                        }
                    }
                    cont.invokeOnCancellation { resolved = true; destroyHook?.invoke(); destroyHook = null }
                }
            }
        }

    companion object {
        private const val TAG = "StreamhgExtractor"
    }
}
