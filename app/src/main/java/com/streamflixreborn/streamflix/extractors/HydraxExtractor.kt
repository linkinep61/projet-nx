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
 * Hydrax / abysscdn — extracteur WebView-based.
 *
 * Hydrax (abyss.to / abysscdn.com / hls.abyssa.cc) protège ses embeds avec :
 *   - Cloudflare 403 sur les GET HTTP directs (sans cookies de session)
 *   - JS player obfusqué qui dérive l'URL HLS via plusieurs requêtes
 *
 * Solution : on charge l'embed dans un WebView (vraie session Chromium passe
 * Cloudflare naturellement), on intercepte le réseau pour capturer le `.m3u8`
 * ou `.mp4` quand le player le fetch.
 *
 * URL embed format : `https://abysscdn.com/?v=<slug>`
 * Stream final attendu : `https://hls.abyssa.cc/<...>.m3u8` ou variante.
 *
 * Pattern identique à MeritendExtractor / YflixExtractor.
 */
open class HydraxExtractor : Extractor() {
    override val name = "Hydrax"
    override val mainUrl = "https://abysscdn.com"
    override val aliasUrls = listOf(
        "https://abyss.to",
        "https://hls.abyssa.cc",
        "https://hydrax.net",
    )

    // 2026-05-14 (user "repare les extracteurs") : pas de cache (best-effort,
    // Cloudflare/anti-bot peut casser à tout moment).
    override val cacheTtlMs: Long = 0L

    private val context = StreamFlixApp.instance.applicationContext

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video {
        val streamUrl = extractByIntercepting(link)
            ?: throw Exception("Hydrax: Could not capture stream URL from $link")
        val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
        return Video(
            source = streamUrl,
            headers = mapOf(
                "Referer" to "https://abysscdn.com/",
                "Origin" to "https://abysscdn.com",
                "User-Agent" to ANDROID_CHROME_UA,
            ),
            type = if (isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractByIntercepting(url: String): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(25_000L) {
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
                            val isAllowed = ALLOWED_HOSTS.any { host.endsWith(it) }
                            return !isAllowed
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host ?: ""

                            // 2026-05-14 fix : block trackers AVANT de matcher.
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            // Match strict sur le path (pas l'URL complète) pour
                            // éviter les faux positifs (query strings avec page-url=).
                            val path = request.url?.path?.lowercase() ?: ""
                            val isHls = path.endsWith(".m3u8") ||
                                path.contains("master.m3u8") ||
                                path.contains("playlist.m3u8") ||
                                path.contains("/hls/")
                            if (isHls) {
                                android.util.Log.d("HydraxExtractor", "HLS INTERCEPTED: $reqUrl")
                                resolve(reqUrl)
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            val isMp4 = path.endsWith(".mp4") &&
                                !path.contains("/ads/") &&
                                !path.contains("/ad/")
                            if (isMp4) {
                                android.util.Log.d("HydraxExtractor", "MP4 INTERCEPTED: $reqUrl")
                                resolve(reqUrl)
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return
                            android.util.Log.d("HydraxExtractor", "onPageFinished: $finishedUrl")

                            // 2026-05-14 fix : Hydrax/abysscdn rejette les bots et
                            // redirige vers https://abyss.to/ root (page d'accueil
                            // sans slug). Dans ce cas pas la peine d'attendre 12s,
                            // on abandonne immédiatement.
                            val u = finishedUrl ?: ""
                            if (u == "https://abyss.to/" ||
                                u == "https://abysscdn.com/" ||
                                u.endsWith("//abyss.to/") ||
                                u.endsWith("//abysscdn.com/")
                            ) {
                                android.util.Log.w("HydraxExtractor", "Redirected to root — anti-bot rejection, abandoning")
                                resolve(null)
                                return
                            }

                            // Filet de sécurité : 8s après page load, abandon
                            // (réduit de 12s → 8s pour fail plus vite si rien capté)
                            view.postDelayed({
                                if (!resolved) {
                                    android.util.Log.w("HydraxExtractor", "Timeout — no stream captured")
                                    resolve(null)
                                }
                            }, 8_000L)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) { /* ignore */ }
                    }

                    // Referer dessinanime.cc = origin légitime, requis pour passer
                    // les checks de Cloudflare/anti-hotlink côté hydrax.
                    webView.loadUrl(url, mapOf("Referer" to "https://dessinanime.cc/"))

                    cont.invokeOnCancellation {
                        resolved = true
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                webView.stopLoading()
                                webView.destroy()
                            } catch (_: Exception) { }
                        }
                    }
                }
            }
        }

    companion object {
        private val ALLOWED_HOSTS = listOf(
            "abysscdn.com",
            "abyss.to",
            "abyssa.cc",
            "hydrax.net",
            "cloudflare.com",
            "challenges.cloudflare.com",
            "cloudfront.net",
            "akamaized.net",
            "googlevideo.com",
            "cdn.jsdelivr.net",
        )
        private val BLOCKED_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice",
            "popads", "popunder", "popcash", "propellerads",
            "exoclick", "juicyads", "trafficjunky",
            "googletagmanager", "google-analytics",
            "mc.yandex.ru", "yandex.ru", "metrica.yandex",
            "cloudflareinsights.com", "static.cloudflareinsights",
        )
    }
}
