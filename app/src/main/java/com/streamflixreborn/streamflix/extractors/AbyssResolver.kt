package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 2026-07-17 — Extraction directe d'Abyss/Hydrax (abysscdn.com) : on récupère l'URL MP4 réelle
 *   (sur storage.googleapis.com) pour la jouer en NATIF, comme les autres lecteurs.
 *
 * Mécanique : la page `abysscdn.com/?v=<slug>` est un JW Player qui, à la lecture, mint une URL
 *   googleapis signée (ex `storage.googleapis.com/mediastorage/<ts>/<id>/<n>.mp4`). Cette URL EST
 *   jouable hors du player (vérifié : QualityProbe 360p). Abyss ne lance la lecture qu'à un vrai
 *   geste → on charge l'embed dans une WebView ATTACHÉE (vraie fenêtre = le geste synthétique
 *   `dispatchTouchEvent` est accepté) via un iframe wrapper (l'embed refuse le top-level), on tape
 *   au centre, et on capte la requête googleapis dans `shouldInterceptRequest`.
 *
 * VERSION MINIMALE VOLONTAIRE : pas d'injection HTML, pas de nav-block, pas de WebView visible
 *   (toutes ces « améliorations » faisaient boucler abyss / cafouiller l'UI). On reste sur ce qui
 *   avait capté du premier coup. Un seul resolver à la fois (mutex) + mémoire du dernier succès.
 *   Échec → HydraxExtractor retombe sur l'overlay WebView.
 */
object AbyssResolver {

    private const val TAG = "AbyssResolver"

    private val mutex = Mutex()
    @Volatile private var lastSlug: String? = null
    @Volatile private var lastUrl: String? = null
    @Volatile private var lastAtMs: Long = 0L

    private val BLOCKED_HOSTS = listOf(
        "googlesyndication", "doubleclick", "googleads", "imasdk.googleapis",
        "popads", "popunder", "popcash", "propellerads", "exoclick", "juicyads",
        "trafficjunky", "googletagmanager", "google-analytics", "aliexpress",
        "decafeligibly", "histats", "cloudflareinsights",
    )

    /** Requête média candidate = le vrai flux abyss (MP4 sur googleapis). */
    private fun isMedia(url: String): Boolean {
        val l = url.lowercase()
        if (!l.startsWith("http")) return false
        if (BLOCKED_HOSTS.any { l.contains(it) }) return false
        return l.contains("storage.googleapis.com")
            || l.contains("commondatastorage.googleapis.com")
            || l.contains("googlevideo.com")
            || l.contains("videoplayback")
            || (l.contains(".mp4") && !l.contains("abysscdn.com/?"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveMediaUrl(embedUrl: String, timeoutMs: Long = 14_000L): String? = mutex.withLock {
        if (embedUrl == lastSlug && lastUrl != null && System.currentTimeMillis() - lastAtMs < 120_000L) {
            Log.d(TAG, "reuse last resolved media for same slug")
            return@withLock lastUrl
        }
        val result = withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val context = StreamFlixApp.instance.applicationContext
                    var resolved = false
                    var destroyHook: (() -> Unit)? = null
                    var attachedRoot: android.view.ViewGroup? = null
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
                    // Attachée invisible derrière l'UI = vraie fenêtre (le dispatchTouchEvent est
                    //   accepté par le player) SANS rien afficher à l'utilisateur.
                    try {
                        val root = StreamFlixApp.currentActivity?.findViewById<android.view.ViewGroup>(android.R.id.content)
                        if (root != null) {
                            webView.alpha = 0.02f
                            root.addView(webView, 0, android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                            attachedRoot = root
                        } else webView.layout(0, 0, 1280, 720)
                    } catch (_: Exception) { try { webView.layout(0, 0, 1280, 720) } catch (_: Exception) {} }

                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    webView.webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onCreateWindow(v: WebView?, d: Boolean, u: Boolean, msg: android.os.Message?): Boolean = false
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host?.lowercase() ?: ""
                            if (isMedia(reqUrl)) {
                                Log.d(TAG, "MEDIA CAPTURED: ${reqUrl.take(100)}")
                                resolve(reqUrl)
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            if (BLOCKED_HOSTS.any { host.contains(it) })
                                return WebResourceResponse("text/plain", "utf-8", null)
                            // segments/pub lourds coupés (perf)
                            if (host.contains("tiktokcdn"))
                                return WebResourceResponse("text/plain", "utf-8", null)
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (resolved || view == null) return
                            val startMs = System.currentTimeMillis()
                            fun tap() {
                                if (resolved) return
                                val w = (if (view.width > 0) view.width else 720).toFloat()
                                val h = (if (view.height > 0) view.height else 1280).toFloat()
                                realTap(view, w / 2f, h / 2f)
                                if (System.currentTimeMillis() - startMs < 12_000L) view.postDelayed({ tap() }, 900L)
                            }
                            view.postDelayed({ tap() }, 800L)
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {}
                    }

                    // Abyss refuse le top-level → iframe wrapper base dessinanime (window.top != self).
                    val wrapper = """
                        <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                        <style>*{margin:0;padding:0}html,body{width:100%;height:100%;overflow:hidden;background:#000}
                        iframe{width:100%;height:100%;border:none}</style></head><body>
                        <iframe src="$embedUrl" allow="autoplay;fullscreen;encrypted-media" allowfullscreen referrerpolicy="origin"></iframe>
                        </body></html>
                    """.trimIndent()
                    Log.d(TAG, "Loading abyss embed headless: ${embedUrl.take(90)}")
                    webView.loadDataWithBaseURL("https://dessinanime.cc/", wrapper, "text/html", "UTF-8", null)

                    destroyHook = {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try { webView.stopLoading(); attachedRoot?.removeView(webView); attachedRoot = null; webView.destroy() } catch (_: Exception) {}
                        }
                    }
                    cont.invokeOnCancellation { resolved = true; destroyHook?.invoke(); destroyHook = null }
                }
            }
        }
        if (result != null) { lastSlug = embedUrl; lastUrl = result; lastAtMs = System.currentTimeMillis() }
        return@withLock result
    }

    private fun realTap(view: WebView, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
        try { view.dispatchTouchEvent(down); view.dispatchTouchEvent(up) } catch (_: Exception) {}
        down.recycle(); up.recycle()
    }
}
