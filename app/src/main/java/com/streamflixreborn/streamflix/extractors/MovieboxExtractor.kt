package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * MovieboxExtractor — extraction des streams depuis themoviebox.org / moviebox.ph
 * (frontends Nuxt SPA tous deux backed par `aoneroom.com`).
 *
 * 2026-05-05 v3 : extraction VIA API DIRECTE (instantanée, < 500 ms).
 * Le backend `aoneroom.com/wefeed-h5api-bff/subject/play` retourne les URLs
 * MP4 signées en JSON quand on envoie les bons headers (`x-project-name`,
 * `x-client-info`). Plus besoin de charger une WebView de 5-8 secondes.
 *
 * Fallback WebView gardé au cas où l'API change ou répond `hasResource=false`.
 *
 * URLs reconnues :
 *   - https://themoviebox.org/fr/movies/<slug>?id=<subjectId>&detailSe=<S>&detailEp=<E>
 *   - https://moviebox.ph/fr/movies/<slug>?id=<subjectId>&...
 */
open class MovieboxExtractor : Extractor() {

    override val name = "Moviebox"
    override val mainUrl = "https://themoviebox.org"
    override val aliasUrls = listOf(
        "https://moviebox.ph",
    )

    companion object {
        private const val TAG = "MovieboxExtractor"
        private const val ANDROID_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val API_BASE = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    }

    override suspend fun extract(link: String): Video {
        // Tente d'abord l'API directe (rapide). Fallback WebView si ça foire.
        extractViaApi(link)?.let {
            Log.i(TAG, "Extraction via API directe ✓")
            return it
        }
        Log.w(TAG, "API directe a échoué, fallback WebView (lent)")
        val primaryResult = extractViaWebView(link)
        if (primaryResult != null) return primaryResult

        val fallbackUrl = when {
            link.contains("themoviebox.org") -> link.replace("themoviebox.org", "moviebox.ph")
            link.contains("moviebox.ph") -> link.replace("moviebox.ph", "themoviebox.org")
            else -> null
        }
        if (fallbackUrl != null) {
            Log.w(TAG, "Primary WebView failed, retry on mirror: $fallbackUrl")
            extractViaWebView(fallbackUrl)?.let { return it }
        }
        throw Exception("Moviebox: extraction failed for $link (API + WebView + fallback miroir)")
    }

    /**
     * Extraction via l'API JSON directe — appel REST GET unique.
     *
     * URL : `/wefeed-h5api-bff/subject/play?subjectId=X&se=N&ep=M`
     * Headers obligatoires :
     *   - `x-project-name: Moviebox`
     *   - `x-client-info: lang=fr;hostName=themoviebox.org`
     *   - `Origin` / `Referer` du frontend
     *
     * Réponse :
     * ```
     * { "data": { "streams": [{format, resolutions, url, ...}], "hasResource": true } }
     * ```
     *
     * On pioche le stream 1080p si dispo, sinon le plus haut. Renvoie null si
     * pas de stream (la WebView prendra le relais en fallback).
     */
    private suspend fun extractViaApi(playerUrl: String): Video? = withContext(Dispatchers.IO) {
        try {
            // Parse playerUrl pour extraire subjectId / detailSe / detailEp
            val uri = android.net.Uri.parse(playerUrl)
            val subjectId = uri.getQueryParameter("id")?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            val se = uri.getQueryParameter("detailSe")?.takeIf { it.isNotBlank() } ?: ""
            val ep = uri.getQueryParameter("detailEp")?.takeIf { it.isNotBlank() } ?: ""

            // Build API URL — pour les films `se`/`ep` sont vides, l'API renvoie
            // quand même les streams.
            val apiUrl = buildString {
                append("$API_BASE/subject/play?subjectId=$subjectId")
                if (se.isNotBlank()) append("&se=$se")
                if (ep.isNotBlank()) append("&ep=$ep")
            }

            val req = Request.Builder()
                .url(apiUrl)
                .header("Origin", "https://themoviebox.org")
                .header("Referer", "https://themoviebox.org/fr")
                .header("User-Agent", ANDROID_CHROME_UA)
                .header("x-client-info", "lang=fr;hostName=themoviebox.org")
                .header("x-project-name", "Moviebox")
                .build()

            val body = sharedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "API play HTTP ${resp.code} pour $apiUrl")
                    return@withContext null
                }
                resp.body?.string()
            } ?: return@withContext null

            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return@withContext null
            if (!data.optBoolean("hasResource", false)) {
                Log.w(TAG, "API play: hasResource=false pour subjectId=$subjectId se=$se ep=$ep")
                return@withContext null
            }
            val streams = data.optJSONArray("streams") ?: return@withContext null
            if (streams.length() == 0) {
                Log.w(TAG, "API play: streams[] vide pour subjectId=$subjectId")
                return@withContext null
            }

            // Pioche le meilleur : 1080p > 720p > 480p > 360p
            var best: JSONObject? = null
            var bestRes = -1
            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                val res = s.optString("resolutions").toIntOrNull() ?: 0
                if (res > bestRes) {
                    bestRes = res
                    best = s
                }
            }
            val url = best?.optString("url")?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            Log.i(TAG, "API play OK: ${bestRes}p stream → ${url.take(80)}...")
            Video(source = url, headers = streamHeaders())
        } catch (e: Exception) {
            Log.w(TAG, "extractViaApi failed: ${e.message}")
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractViaWebView(playerUrl: String): Video? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine<Video?> { cont ->
                val context = StreamFlixApp.instance.applicationContext
                var resolved = false
                lateinit var webView: WebView
                val handler = Handler(Looper.getMainLooper())

                fun resume(video: Video?) {
                    if (resolved) return
                    resolved = true
                    handler.post {
                        try {
                            webView.stopLoading()
                            webView.loadUrl("about:blank")
                            webView.destroy()
                        } catch (_: Throwable) { /* ignore */ }
                    }
                    if (cont.isActive) cont.resume(video)
                }

                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = ANDROID_CHROME_UA
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                }
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        // Patterns Moviebox : /bt/<hash>.mp4 ou .m3u8 avec ?sign=X&t=Y
                        val isStream = (url.contains("/bt/") &&
                            (url.contains(".mp4") || url.contains(".m3u8"))) ||
                            url.contains(".m3u8")
                        if (isStream) {
                            Log.i(TAG, "[Moviebox-WV] stream captured: $url")
                            resume(Video(source = url, headers = streamHeaders()))
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        scheduleVideoSrcPoll(view, attempt = 0)
                    }

                    private fun scheduleVideoSrcPoll(view: WebView?, attempt: Int) {
                        if (resolved || view == null || attempt > 30) return
                        handler.postDelayed({
                            if (resolved) return@postDelayed
                            view.evaluateJavascript(
                                "(()=>{var v=document.querySelector('video');return v&&v.currentSrc?v.currentSrc:''})()"
                            ) { result ->
                                val cleaned = result?.trim()?.trim('"').orEmpty()
                                if (cleaned.startsWith("http")) {
                                    Log.i(TAG, "[Moviebox-WV] currentSrc captured: $cleaned")
                                    resume(Video(source = cleaned, headers = streamHeaders()))
                                } else {
                                    scheduleVideoSrcPoll(view, attempt + 1)
                                }
                            }
                        }, 500)
                    }
                }

                cont.invokeOnCancellation {
                    handler.post {
                        try {
                            webView.stopLoading()
                            webView.destroy()
                        } catch (_: Throwable) { /* ignore */ }
                    }
                }

                Log.d(TAG, "[Moviebox-WV] loading $playerUrl")
                webView.loadUrl(playerUrl)
            }
        }
    }

    private fun streamHeaders(): MutableMap<String, String> = mutableMapOf(
        "Referer" to "https://themoviebox.org/",
        "Origin" to "https://themoviebox.org",
        "User-Agent" to ANDROID_CHROME_UA,
    )
}
