package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

open class FilemoonExtractor : Extractor() {

    companion object {
        private const val TAG = "FilemoonExtractor"
        private const val ANDROID_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.org"
    override val aliasUrls = listOf("https://bf0skv.org","https://bysejikuar.com","https://moflix-stream.link","https://bysezoxexe.com","https://bysebuho.com","https://filemoon.sx","https://bysekoze.com","https://bysesayeveum.com","https://lukefirst.lol","https://filemoon.site","https://weneverbeenfree.com")

    /**
     * Two-tier extraction strategy:
     *
     *   1. Fast path — REST API (`/embed/details` + `/embed/playback`).
     *      ~100-300ms when it works. Reverse-engineered from the Vite SPA bundle
     *      `videoPagesBundle-CQv1AfZY.js`. Fragile against API changes (URL
     *      patterns, encryption keys, fingerprint requirements, header tightening).
     *
     *   2. Fallback — headless WebView. Loads `/e/{videoId}` and lets the SPA do
     *      its job (decrypt playback response, build the player). Intercepts the
     *      first `*.m3u8` request via `shouldInterceptRequest` and returns it.
     *      Slower (~3-8s), but resilient to ANY backend change as long as the
     *      site itself works in a browser.
     *
     * On `404 video not found` (genuine link rot — the upload was deleted on
     * Filemoon's side) we skip the WebView fallback to fail fast: WebView would
     * just hang for 8s and then time out anyway. This lets the player jump to
     * the next server immediately.
     */
    override suspend fun extract(link: String): Video {
        return try {
            extractViaApi(link)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            // Link rot — fail fast, don't waste 8s in WebView for a deleted video.
            if (msg.contains("link rot", ignoreCase = true) ||
                msg.contains("video expired", ignoreCase = true)
            ) {
                Log.w(TAG, "[Filemoon] Link rot detected — failing fast: $msg")
                throw e
            }
            // Anything else — try WebView fallback.
            Log.w(TAG, "[Filemoon] API failed (${e.javaClass.simpleName}: $msg) — trying WebView fallback")
            try {
                extractViaWebView(link)
            } catch (we: Exception) {
                Log.e(TAG, "[Filemoon] WebView fallback also failed: ${we.message}")
                throw e // surface the original API error — more informative
            }
        }
    }

    private suspend fun extractViaApi(link: String): Video {
        val service = Extractor.createGsonService<Service>(mainUrl)
        // Regex to match /e/ or /d/ and ID
        val matcher = Regex("""/(e|d)/([a-zA-Z0-9]+)""").find(link) 
            ?: throw Exception("Could not extract video ID or type")
        
        val linkType = matcher.groupValues[1]
        val videoId = matcher.groupValues[2]
        
        val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1)
            ?: throw Exception("Could not extract Base URL")

        // Parent provider URL — required by some Filemoon variants that enforce a
        // per-video allowlist of embedding domains (e.g. weneverbeenfree.com — a
        // "Byse Frontend" SPA used by VoirAnime/VoirDrama). Without these headers,
        // the details endpoint returns 403 "embedding from this domain is not allowed".
        val parentUrl = try {
            UserPreferences.currentProvider?.baseUrl
        } catch (_: Throwable) { null }
        val parentOrigin = parentUrl?.trimEnd('/')

        // X-Embed-* trio — required by the Byse SPA in embed mode (videoPagesBundle Lt/Nt).
        // The frontend always sends these on /embed/details and /embed/playback when
        // operating from a parent provider's iframe. Without them, lukefirst.lol /
        // weneverbeenfree.com / filemoon.site WAFs return 403/404 even for valid videos.
        val embedParent = link
        val embedOrigin = currentDomain
        val embedReferer = parentUrl ?: link

        val detailsHeaders = mutableMapOf(
            "User-Agent" to Extractor.DEFAULT_USER_AGENT,
            "Accept" to "application/json",
            "X-Embed-Parent" to embedParent,
            "X-Embed-Origin" to embedOrigin,
            "X-Embed-Referer" to embedReferer
        )
        if (parentUrl != null) {
            detailsHeaders["Referer"] = parentUrl
            detailsHeaders["Origin"] = parentOrigin!!
        } else {
            detailsHeaders["Referer"] = link
        }

        val detailsUrl = "$currentDomain/api/videos/$videoId/embed/details"
        val details = try {
            service.getDetails(detailsUrl, detailsHeaders)
        } catch (e: retrofit2.HttpException) {
            // 404 + "video not found" body = link rot; surface a clearer message
            // than a generic HTTP exception so the player can fall back fast.
            val body = try { e.response()?.errorBody()?.string() } catch (_: Throwable) { null }
            if (e.code() == 404 && body?.contains("video not found", ignoreCase = true) == true) {
                throw Exception("Filemoon: video expired (link rot) — $videoId")
            }
            throw e
        }
        val embedFrameUrl = details.embed_frame_url

        var playbackDomain = ""
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = Extractor.DEFAULT_USER_AGENT
        headers["Accept"] = "application/json"
        headers["Content-Type"] = "application/json"

        if (embedFrameUrl == null) {
            // Byse-frontend variant (e.g. weneverbeenfree.com, lukefirst.lol):
            // no embed_frame_url returned. Playback lives on the same domain as
            // the embed; the WAF requires Referer + Origin = parent provider URL,
            // plus the X-Embed-* trio (same set as for details).
            if (parentUrl == null) {
                throw Exception("embed_frame_url missing and no parent provider URL")
            }
            playbackDomain = currentDomain
            headers["Referer"] = parentUrl
            headers["Origin"] = parentOrigin!!
            headers["X-Embed-Parent"] = embedParent
            headers["X-Embed-Origin"] = embedOrigin
            headers["X-Embed-Referer"] = embedReferer
        } else if (linkType == "d") {
            playbackDomain = currentDomain
            headers["Referer"] = link
        } else {
            playbackDomain = Regex("""(https?://[^/]+)""").find(embedFrameUrl)?.groupValues?.get(1)
                ?: throw Exception("Could not extract domain from embed_frame_url")
            headers["Referer"] = embedFrameUrl
            // Required by current API (April 2026) — server returns 405 without these.
            // Reverse-engineered from the official SPA Vite bundle (Lt + Nt helpers in
            // videoPagesBundle-CQv1AfZY.js). The X-Embed-* trio identifies the parent
            // frame so the WAF lets the request through.
            headers["X-Embed-Parent"] = link
            headers["X-Embed-Origin"] = playbackDomain
            headers["X-Embed-Referer"] = link
        }

        // POST /api/videos/{id}/embed/playback with a fingerprint body. The server only
        // validates that `fingerprint` is a JSON object with token/viewer_id/device_id/
        // confidence keys; the actual values are not checked, so we send placeholders.
        // Returns AES-256-GCM encrypted JSON with iv/payload/key_parts → decryptPlayback().
        val playbackUrl = "$playbackDomain/api/videos/$videoId/embed/playback"
        val playbackResponse = try {
            service.getPlayback(playbackUrl, headers, FingerprintBody())
        } catch (e: retrofit2.HttpException) {
            val body = try { e.response()?.errorBody()?.string() } catch (_: Throwable) { null }
            if (e.code() == 404 && body?.contains("video not found", ignoreCase = true) == true) {
                throw Exception("Filemoon: video expired (link rot) — $videoId")
            }
            throw e
        }
        val playbackData = playbackResponse.playback
            ?: throw Exception("No playback data")


        val decryptedJson = decryptPlayback(playbackData)
        
        val jsonObject = JSONObject(decryptedJson)
        val sources = jsonObject.optJSONArray("sources")
            ?: throw Exception("No sources found in decrypted data")
            
        if (sources.length() == 0) throw Exception("Empty sources list")
        
        val sourceUrl = sources.getJSONObject(0).getString("url")

        Log.i("StreamFlixES", "[Filemoon] -> Source found: $sourceUrl")

        val videoHeaders = if (embedFrameUrl == null) {
            // Byse variant: hot-link the source with parent-provider headers
            mutableMapOf(
                "Referer" to (parentUrl ?: currentDomain),
                "User-Agent" to Extractor.DEFAULT_USER_AGENT,
                "Origin" to (parentOrigin ?: currentDomain)
            )
        } else {
            mutableMapOf(
                "Referer" to embedFrameUrl,
                "User-Agent" to Extractor.DEFAULT_USER_AGENT,
                "Origin" to playbackDomain
            )
        }
        return Video(
            source = sourceUrl,
            headers = videoHeaders
        )
    }

    /**
     * Headless WebView fallback. Loads `/e/{videoId}` and intercepts the first
     * `*.m3u8` (or `master.m3u8` / `index.m3u8`) request the SPA fires once it has
     * decrypted the playback response. No user interaction, no JS bridge needed —
     * the network layer alone tells us the source URL.
     *
     * Headers are set on the load URL so the page sees the same Referer/Origin
     * the API path would have sent, in case the site sniffs them. The X-Embed-*
     * trio is NOT added here — those only matter for the API endpoints, not for
     * the user-facing /e/{id} HTML page.
     *
     * Timeout: 12s. The SPA boots its Vite bundle (~350 KB) + decrypts AES-GCM
     * + builds the player; on a fast network this completes in 2-4s, on slow
     * networks 6-10s.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractViaWebView(link: String): Video = withContext(Dispatchers.Main) {
        val parentUrl = try {
            UserPreferences.currentProvider?.baseUrl
        } catch (_: Throwable) { null }
        val parentOrigin = parentUrl?.trimEnd('/')
        val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1)
            ?: throw Exception("Could not extract base URL")

        val result = withTimeoutOrNull(12_000L) {
            suspendCancellableCoroutine<Video> { cont ->
                val context = StreamFlixApp.instance.applicationContext
                var resolved = false
                lateinit var webView: WebView

                fun cleanupAndResume(video: Video?) {
                    if (resolved) return
                    resolved = true
                    Handler(Looper.getMainLooper()).post {
                        try {
                            webView.stopLoading()
                            webView.loadUrl("about:blank")
                            webView.destroy()
                        } catch (_: Throwable) { /* ignore */ }
                    }
                    if (video != null) {
                        if (cont.isActive) cont.resume(video)
                    } else {
                        if (cont.isActive) cont.resume(
                            Video(source = "", headers = emptyMap())
                        )
                    }
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
                        if (url.contains(".m3u8") && !resolved) {
                            Log.i(TAG, "[Filemoon-WV] m3u8 captured: $url")
                            // Use the embed page URL as Referer for downstream playback.
                            // ExoPlayer needs this header set; the SPA sends it natively.
                            val videoHeaders = mutableMapOf(
                                "Referer" to link,
                                "Origin" to currentDomain,
                                "User-Agent" to ANDROID_CHROME_UA
                            )
                            cleanupAndResume(Video(source = url, headers = videoHeaders))
                        }
                        return null
                    }
                }

                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            webView.stopLoading()
                            webView.destroy()
                        } catch (_: Throwable) { /* ignore */ }
                    }
                }

                // Provide Referer/Origin matching the parent provider so the embed
                // page accepts us as a legit iframe load. Without these, some Filemoon
                // variants block the SPA's API calls.
                val loadHeaders = mutableMapOf<String, String>()
                if (parentUrl != null) {
                    loadHeaders["Referer"] = parentUrl
                    loadHeaders["Origin"] = parentOrigin!!
                }

                Log.d(TAG, "[Filemoon-WV] loading $link (referer=$parentUrl)")
                webView.loadUrl(link, loadHeaders)
            }
        } ?: throw Exception("Filemoon WebView fallback timed out (12s)")

        if (result.source.isBlank()) {
            throw Exception("Filemoon WebView fallback resolved empty source")
        }
        result
    }

    private fun decryptPlayback(data: PlaybackData): String {
        val iv = Base64.decode(data.iv, Base64.URL_SAFE)
        val payload = Base64.decode(data.payload, Base64.URL_SAFE)
        val p1 = Base64.decode(data.key_parts[0], Base64.URL_SAFE)
        val p2 = Base64.decode(data.key_parts[1], Base64.URL_SAFE)
        
        val key = ByteArray(p1.size + p2.size)
        System.arraycopy(p1, 0, key, 0, p1.size)
        System.arraycopy(p2, 0, key, p1.size, p2.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(key, "AES")
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        
        val decryptedBytes = cipher.doFinal(payload)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    class Any(hostUrl: String) : FilemoonExtractor() {
        override val mainUrl = hostUrl
    }

    private interface Service {
        @GET
        suspend fun getDetails(
            @Url url: String,
            @HeaderMap headers: Map<String, String>,
        ): DetailsResponse

        @POST
        suspend fun getPlayback(
            @Url url: String,
            @HeaderMap headers: Map<String, String>,
            @Body body: FingerprintBody,
        ): PlaybackResponse
    }

    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(
        val iv: String,
        val payload: String,
        val key_parts: List<String>
    )

    /**
     * Body required by the embed/playback endpoint as of April 2026. The server validates
     * the JSON shape (a `fingerprint` object with token/viewer_id/device_id/confidence
     * fields) but doesn't currently check the actual values — placeholders work.
     */
    data class FingerprintBody(
        val fingerprint: Fingerprint = Fingerprint()
    )

    data class Fingerprint(
        val token: String = "x",
        val viewer_id: String = "y",
        val device_id: String = "z",
        val confidence: Double = 0.5,
    )

}
