package com.streamflixreborn.streamflix.extractors

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume

class AfterDarkExtractor(var newUrl: String = "") : Extractor() {
    val defaultUrl = "https://afterdark.best"
    override var mainUrl = newUrl.ifBlank { defaultUrl }
    override val name = "AfterDark"

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val TAG = "AfterDarkExtractor"
        private const val TURNSTILE_SITEKEY = "0x4AAAAAACtM3fCPnvQcAGIA"

        // Gate state shared across instances — retry after cooldown on failure
        private const val GATE_RETRY_COOLDOWN_MS = 3 * 60 * 1000L // 3 minutes before retrying a failed gate

        @Volatile
        private var gateAttempted = false
        @Volatile
        private var gateSolved = false
        @Volatile
        private var gateLastAttemptTime = 0L

        /** Reset gate state so next call will retry. Called when gate fails or after cooldown. */
        fun resetGateIfStale() {
            if (gateAttempted && !gateSolved) {
                val elapsed = System.currentTimeMillis() - gateLastAttemptTime
                if (elapsed > GATE_RETRY_COOLDOWN_MS) {
                    Log.d(TAG, "Gate cooldown expired (${elapsed}ms) — resetting for retry")
                    gateAttempted = false
                }
            }
        }
    }

    override suspend fun extract(link: String): Video {
        throw Exception("Direct extraction not supported. Use servers(videoType).")
    }

    private fun buildApiUrl(videoType: Video.Type): String {
        val queryParams = when (videoType) {
            is Video.Type.Movie -> "tmdbId=${videoType.id}&type=movie&imdbId=${videoType.imdbId ?: ""}&title=${Uri.encode(videoType.title)}&releaseYear=${videoType.releaseDate.split("-").firstOrNull() ?: ""}"
            is Video.Type.Episode -> "tmdbId=${videoType.tvShow.id}&type=tv&imdbId=${videoType.tvShow.imdbId ?: ""}&title=${Uri.encode(videoType.tvShow.title)}&releaseYear=${videoType.tvShow.releaseDate?.split("-")?.firstOrNull() ?: ""}&season=${videoType.season.number}&episode=${videoType.number}"
        }
        return "${mainUrl.trimEnd('/')}/api/v1/sources?$queryParams"
    }

    /**
     * Calls the /api/v1/sources endpoint.
     * Returns (data JSONObject, hasFullAccess) — hasFullAccess is true if we got categories
     * beyond the ungated ionnae/omaya.
     */
    private fun fetchSources(apiUrl: String): Pair<JSONObject?, Boolean> {
        return try {
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$mainUrl/")
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", mainUrl.trimEnd('/'))
                .build()

            val response = NetworkClient.default.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: return Pair(null, false)

            if (code != 200) {
                val preview = body.take(300)
                Log.w(TAG, "API returned HTTP $code — blocked or error. Body preview: $preview")
                return Pair(null, false)
            }

            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: run {
                Log.w(TAG, "API response has no 'data' key: ${body.take(200)}")
                return Pair(null, false)
            }

            // Check if we have categories beyond the ungated ones
            val categories = mutableListOf<String>()
            val keys = data.keys()
            while (keys.hasNext()) { categories.add(keys.next()) }

            val hasFullAccess = categories.any { it !in listOf("ionnae", "omaya") }
            Log.d(TAG, "API returned categories: $categories (fullAccess=$hasFullAccess)")
            Pair(data, hasFullAccess)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching sources: ${e.message}")
            Pair(null, false)
        }
    }

    private fun parseServers(data: JSONObject): List<Video.Server> {
        val allServers = mutableListOf<Video.Server>()

        val keys = data.keys()
        while (keys.hasNext()) {
            val category = keys.next()
            val sources = data.optJSONArray(category) ?: continue
            Log.d(TAG, "Category '$category': ${sources.length()} sources")

            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue

                val url = source.optString("url", "")
                if (url.isBlank() || !url.startsWith("http")) continue

                val service = source.optString("service", "").lowercase()
                val quality = source.optString("quality", "HD")
                val language = source.optString("language", "VF")
                val provider = source.optString("provider", "")
                val type = source.optString("type", "")
                val proxied = source.optBoolean("proxied", false)

                Log.d(TAG, "  Source: provider=$provider service=$service quality=$quality lang=$language proxied=$proxied type=$type")

                // Accept proxied sources, Metal (HLS), unknown, and direct MP4/HLS links
                // Only skip non-proxied embed services that need their own extractor
                val isDirectPlayable = proxied || service == "metal" || service == "unknown" || service.isBlank()
                        || type == "hls" || type == "mp4" || url.contains(".m3u8") || url.contains(".mp4")
                if (!isDirectPlayable) continue

                val serverName = buildString {
                    append(provider)
                    if (service.isNotBlank() && service != "metal") append(" ($service)")
                    append(" • $quality • $language")
                }

                if (allServers.any { it.video?.source == url }) continue

                val mimeType = when {
                    type == "hls" || url.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
                    else -> MimeTypes.APPLICATION_MP4
                }

                allServers.add(
                    Video.Server(
                        id = "afd_${allServers.size}",
                        name = serverName,
                    ).apply {
                        video = Video(
                            source = url,
                            type = mimeType,
                            headers = mapOf(
                                "Referer" to "$mainUrl/",
                                "User-Agent" to USER_AGENT
                            )
                        )
                    }
                )
            }
        }

        // Sort: VF/French first, VOSTFR/VO last
        return allServers.sortedBy { server ->
            val name = server.name.uppercase()
            when {
                name.contains("VFF") -> 0
                name.contains("VF") && !name.contains("VOSTFR") -> 1
                name.contains("FRENCH") && !name.contains("VOSTFR") -> 2
                name.contains("VOSTFR") || name.contains("VOST") -> 5
                name.contains("VO") -> 6
                else -> 3
            }
        }
    }

    /**
     * Solves the AfterDark gate by rendering a Cloudflare Turnstile widget in a visible WebView.
     *
     * Turnstile REQUIRES a visible, layout-attached WebView to solve. A headless/hidden WebView
     * will always return empty tokens. We use an AlertDialog (like WebViewResolver does for
     * Cloudflare challenges) to briefly display the WebView.
     *
     * Flow:
     *   1. Show a small transparent dialog with the WebView
     *   2. Load afterdark.best homepage (correct origin for Turnstile)
     *   3. Inject Turnstile widget (sitekey 0x4AAAAAACtM3fCPnvQcAGIA)
     *   4. Turnstile auto-solves → token
     *   5. POST /api/v1/gate {token} → cookie set
     *   6. Dismiss dialog
     */
    private suspend fun solveGate(): Boolean {
        val activity = StreamFlixApp.currentActivity
        if (activity == null) {
            Log.w(TAG, "[Gate] No foreground Activity — cannot show Turnstile dialog")
            return false
        }

        Log.d(TAG, "Attempting gate bypass via visible Turnstile dialog...")
        val baseUrl = mainUrl.trimEnd('/')

        val result = withTimeoutOrNull(35_000L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val handler = Handler(Looper.getMainLooper())
                    var dialog: AlertDialog? = null

                    val webView = WebView(activity).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = USER_AGENT
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }
                    }

                    fun cleanup() {
                        handler.post {
                            try { dialog?.dismiss() } catch (_: Exception) {}
                            try {
                                (webView.parent as? ViewGroup)?.removeView(webView)
                                webView.stopLoading()
                                webView.destroy()
                            } catch (_: Exception) {}
                        }
                    }

                    val timeoutRunnable = Runnable {
                        if (continuation.isActive) {
                            Log.w(TAG, "[Gate] Timeout — Turnstile did not solve in time")
                            continuation.resume(false)
                            cleanup()
                        }
                    }
                    handler.postDelayed(timeoutRunnable, 33_000)

                    continuation.invokeOnCancellation {
                        handler.removeCallbacks(timeoutRunnable)
                        cleanup()
                    }

                    webView.webViewClient = object : WebViewClient() {
                        private var injected = false

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (injected) return
                            injected = true
                            Log.d(TAG, "[Gate] Page loaded in dialog: $url — injecting Turnstile")

                            val injectJs = """
                                (function() {
                                    var s = document.createElement('script');
                                    s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?onload=_onTsLoad';
                                    s.async = true;
                                    window._onTsLoad = function() {
                                        var div = document.createElement('div');
                                        div.id = 'ts-solver';
                                        div.style.cssText = 'position:fixed;bottom:0;left:0;width:310px;height:70px;z-index:99999;';
                                        document.body.appendChild(div);
                                        turnstile.render('#ts-solver', {
                                            sitekey: '$TURNSTILE_SITEKEY',
                                            theme: 'dark',
                                            callback: function(token) {
                                                window.__tsToken = token;
                                            }
                                        });
                                    };
                                    document.head.appendChild(s);
                                })();
                            """.trimIndent()

                            view?.evaluateJavascript(injectJs, null)

                            handler.postDelayed({
                                pollForTurnstileToken(webView, handler, continuation, timeoutRunnable, 0) { cleanup() }
                            }, 4000)
                        }
                    }

                    // Create a minimal dialog with the WebView — Turnstile needs to be rendered
                    // in a real, layout-attached view hierarchy to solve.
                    // We use a tiny 1x1 dialog positioned off-screen to avoid visual artifacts.
                    try {
                        val container = FrameLayout(activity).apply {
                            setBackgroundColor(Color.TRANSPARENT)
                            // WebView needs at least ~320x80 for Turnstile to render
                            addView(webView, FrameLayout.LayoutParams(320, 80))
                        }

                        dialog = AlertDialog.Builder(activity, android.R.style.Theme_Translucent_NoTitleBar)
                            .setView(container)
                            .setCancelable(false)
                            .create()

                        dialog?.show()

                        // Make the dialog window tiny and move it off-screen
                        dialog?.window?.let { w ->
                            w.setBackgroundDrawableResource(android.R.color.transparent)
                            w.setDimAmount(0f) // no background dim
                            val lp = w.attributes
                            lp.width = 1
                            lp.height = 1
                            lp.x = -9999
                            lp.y = -9999
                            lp.alpha = 0.01f
                            w.attributes = lp
                        }

                        Log.d(TAG, "[Gate] Dialog shown (minimal) — loading $baseUrl")
                    } catch (e: Exception) {
                        Log.e(TAG, "[Gate] Failed to show dialog: ${e.message}")
                        if (continuation.isActive) continuation.resume(false)
                        return@suspendCancellableCoroutine
                    }

                    webView.loadUrl(baseUrl)
                }
            }
        }

        return result == true
    }

    private fun pollForTurnstileToken(
        webView: WebView,
        handler: Handler,
        continuation: kotlinx.coroutines.CancellableContinuation<Boolean>,
        timeoutRunnable: Runnable,
        attempt: Int,
        cleanup: () -> Unit
    ) {
        if (!continuation.isActive || attempt > 25) return

        webView.evaluateJavascript("""
            (function() {
                if (window.__tsToken && window.__tsToken.length > 20) return window.__tsToken;
                if (typeof turnstile !== 'undefined' && typeof turnstile.getResponse === 'function') {
                    var resp = turnstile.getResponse();
                    if (resp && resp.length > 20) return resp;
                }
                return '';
            })()
        """.trimIndent()) { result ->
            val token = result?.trim()?.removeSurrounding("\"") ?: ""
            Log.d(TAG, "[Gate] Poll #$attempt: token=${if (token.length > 20) "${token.take(20)}..." else "empty"}")

            if (token.length > 20) {
                postGateToken(webView, handler, continuation, timeoutRunnable, token, cleanup)
            } else {
                handler.postDelayed({
                    pollForTurnstileToken(webView, handler, continuation, timeoutRunnable, attempt + 1, cleanup)
                }, 1000)
            }
        }
    }

    private fun postGateToken(
        webView: WebView,
        handler: Handler,
        continuation: kotlinx.coroutines.CancellableContinuation<Boolean>,
        timeoutRunnable: Runnable,
        token: String,
        cleanup: () -> Unit
    ) {
        val gateUrl = "${mainUrl.trimEnd('/')}/api/v1/gate"
        Log.d(TAG, "[Gate] Posting Turnstile token to $gateUrl")

        val escapedToken = token.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("""
            fetch('$gateUrl', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({token: '$escapedToken'}),
                credentials: 'include'
            }).then(function(r) { return r.text(); })
              .catch(function(e) { return 'error:' + e.message; })
        """.trimIndent()) { result ->
            val body = result?.trim()?.removeSurrounding("\"") ?: "unknown"
            Log.d(TAG, "[Gate] POST response: $body")

            handler.removeCallbacks(timeoutRunnable)

            val success = body.contains("\"success\":true") || body.contains("success:true")
            handler.postDelayed({
                CookieManager.getInstance().flush()
                Log.d(TAG, "[Gate] Cookie flushed. Gate bypass ${if (success) "SUCCESS" else "FAILED"}")
                if (continuation.isActive) continuation.resume(success)
                cleanup()
            }, 1500)
        }
    }

    suspend fun servers(videoType: Video.Type): List<Video.Server> {
        val apiUrl = buildApiUrl(videoType)

        // Allow retry if previous gate attempt failed and cooldown expired
        resetGateIfStale()

        // First attempt — may return limited sources without gate cookie
        val (data, hasFullAccess) = fetchSources(apiUrl)

        if (data != null) {
            val servers = parseServers(data)
            Log.d(TAG, "Initial fetch: ${servers.size} servers, fullAccess=$hasFullAccess, gateSolved=$gateSolved")

            // If we have full access or gate already solved, return immediately
            if (hasFullAccess || gateSolved) {
                return servers
            }

            // Return ungated servers immediately — don't block on gate bypass
            if (servers.isNotEmpty()) {
                // Fire gate bypass in background for subsequent calls
                if (!gateAttempted) {
                    gateAttempted = true
                    gateLastAttemptTime = System.currentTimeMillis()
                    Log.d(TAG, "Returning ${servers.size} ungated servers now; gate bypass running in background")
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            if (solveGate()) {
                                gateSolved = true
                                Log.d(TAG, "Background gate bypass succeeded — next call will have full access")
                            } else {
                                Log.w(TAG, "Background gate bypass failed — will retry after cooldown")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Background gate bypass error: ${e.message}")
                        }
                    }
                }
                return servers
            }
        } else {
            Log.w(TAG, "fetchSources returned null — site may be blocked or unreachable")
        }

        // No servers at all — try gate bypass synchronously as last resort
        if (!gateAttempted) {
            gateAttempted = true
            gateLastAttemptTime = System.currentTimeMillis()
            Log.d(TAG, "No servers from ungated API — trying synchronous gate bypass...")

            if (solveGate()) {
                gateSolved = true
                val (retryData, _) = fetchSources(apiUrl)
                if (retryData != null) {
                    val retryServers = parseServers(retryData)
                    if (retryServers.isNotEmpty()) return retryServers
                }
            } else {
                Log.w(TAG, "Synchronous gate bypass failed — will retry after ${GATE_RETRY_COOLDOWN_MS / 1000}s cooldown")
            }
        }

        // Fallback: return whatever the first attempt gave us
        return data?.let { parseServers(it) } ?: emptyList()
    }
}
