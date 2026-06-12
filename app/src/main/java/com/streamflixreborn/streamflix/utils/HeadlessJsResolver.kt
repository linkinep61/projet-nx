package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Résolveur Wiseplay-style : exécute le JS du body dans une WebView headless,
 * intercepte les requêtes HTTP via shouldInterceptRequest et retourne la
 * première URL m3u8/mpd/ts demandée par le script.
 *
 * Avantage par rapport au pattern matching Kotlin :
 *  - Aucun pattern hardcodé. Quand la playlist change son format (atob,
 *    Nxt(), regex, AES, peu importe), le JS exécute la nouvelle logique et
 *    on capture juste la requête finale.
 *  - Marche pour TOUS les pipelines qui finissent par un `fetch(url)` ou
 *    `window.location.assign(url)` dans le JS.
 *
 * Implémentation :
 *  - WebView créé sur le main thread (obligatoire)
 *  - JS activé + UA custom + base URL pour que `document.referrer` ne soit
 *    pas vide (= certains scripts vérifient `/http/.test(document.referrer)`).
 *  - Le WebViewClient intercepte chaque requête réseau ; dès qu'une URL
 *    ressemble à un manifest media, on l'enregistre et on coupe.
 *  - Timeout 8s par défaut (= si le JS plante ou redirige vers du HTML, on
 *    s'arrête).
 */
object HeadlessJsResolver {

    private const val TAG = "HeadlessJs"

    /**
     * @param html Contenu HTML/RSS à exécuter (avec ses `<script>` à l'intérieur).
     * @param baseUrl URL de base pour `document.referrer` et résolution des
     *   chemins relatifs. Idéalement = l'URL d'où le body a été fetché.
     * @param userAgent User-Agent à utiliser pour `navigator.userAgent` et les
     *   requêtes sortantes. Le UA porte souvent l'identifiant chaîne (~tpoltf1~).
     * @param timeoutMs Délai max d'attente d'une URL média.
     * @return URL m3u8/mpd/ts trouvée, ou null si rien intercepté.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(
        ctx: Context,
        html: String,
        baseUrl: String,
        userAgent: String,
        timeoutMs: Long = 8000,
    ): String? {
        val deferred = CompletableDeferred<String?>()
        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.post {
            try {
                val webView = WebView(ctx)
                webView.settings.javaScriptEnabled = true
                webView.settings.userAgentString = userAgent
                webView.settings.domStorageEnabled = true
                webView.settings.loadsImagesAutomatically = false // perf
                webView.settings.mediaPlaybackRequiresUserGesture = false

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        if (looksLikeMedia(url)) {
                            Log.d(TAG, "intercepted media URL: $url")
                            if (!deferred.isCompleted) {
                                deferred.complete(url)
                                mainHandler.post {
                                    try {
                                        view.stopLoading()
                                        view.destroy()
                                    } catch (_: Throwable) {}
                                }
                            }
                            // Renvoyer une réponse vide pour éviter que le WebView
                            // télécharge le segment et expose notre IP.
                            return WebResourceResponse(
                                "video/mp2t",
                                "utf-8",
                                java.io.ByteArrayInputStream(ByteArray(0)),
                            )
                        }
                        return null // laisse le WebView faire sa requête normale
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        // Si le JS fait window.location.assign(media_url), on capture.
                        if (looksLikeMedia(url)) {
                            Log.d(TAG, "intercepted nav URL: $url")
                            if (!deferred.isCompleted) deferred.complete(url)
                            return true
                        }
                        return false
                    }
                }

                webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", baseUrl)
            } catch (t: Throwable) {
                Log.w(TAG, "WebView creation failed: ${t.message}")
                if (!deferred.isCompleted) deferred.complete(null)
            }
        }

        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    private fun looksLikeMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mpd") ||
                lower.contains("/index.m3u8") || lower.contains("/manifest") ||
                lower.endsWith(".ts") || lower.contains(".ts?") ||
                // Patterns CDN live courants
                lower.contains("/live/") && (lower.contains(".m3u8") || lower.contains(".mpd"))
    }
}
