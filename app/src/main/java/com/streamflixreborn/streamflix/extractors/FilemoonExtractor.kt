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

        // 2026-07-16 : domaines de fallback. Si le TLS handshake échoue sur un domaine
        // (Cloudflare bloque certains domaines par IP/JA3, ex. bysebuho.com depuis Tahiti),
        // on retente le même video ID sur un autre domaine Byse — les IDs sont cross-domain.
        private val FALLBACK_DOMAINS = listOf(
            "https://filemoon.sx",
            "https://filemoon.org",
            "https://bysezoxexe.com",
            "https://bysejikuar.com"
        )

        private val VIDEO_ID_REGEX = Regex("""/(e|d)/([a-zA-Z0-9]+)""")

        /** Vérifie si l'erreur est un problème réseau/SSL (pas un 404 côté serveur). */
        private fun isNetworkError(e: Exception): Boolean {
            val msg = e.message.orEmpty().lowercase()
            return e is javax.net.ssl.SSLHandshakeException ||
                e is javax.net.ssl.SSLException ||
                e is java.net.ConnectException ||
                e is java.net.UnknownHostException ||
                msg.contains("connection closed") ||
                msg.contains("ssl") ||
                msg.contains("handshake") ||
                msg.contains("timed out") ||
                msg.contains("connect")
        }

        /** Domains known to serve the Filemoon verification iframe. */
        private val VERIFICATION_DOMAINS = setOf("q8y5z.com")

        /**
         * JS injected into the verification iframe HTML. Clicks every interactive
         * element it can find (buttons, SVGs, clickable divs) at multiple delays
         * to ensure the play button gets hit even if it renders late.
         */
        private val AUTO_CLICK_SCRIPT = """
            <script>
            (function(){
                function tryClick(){
                    var c=document.querySelectorAll('button,[role="button"],[onclick],a,.play-btn,.btn-play,.play,.vjs-big-play-button');
                    for(var i=0;i<c.length;i++){try{c[i].click();}catch(e){}}
                    var s=document.querySelectorAll('svg,.play-icon,.fa-play,.icon-play');
                    for(var i=0;i<s.length;i++){try{s[i].click();if(s[i].parentElement)s[i].parentElement.click();}catch(e){}}
                    try{
                        var el=document.elementFromPoint(window.innerWidth/2,window.innerHeight/2);
                        if(el){el.click();if(el.parentElement)el.parentElement.click();}
                    }catch(e){}
                    try{
                        var evt=new MouseEvent('click',{bubbles:true,cancelable:true,clientX:window.innerWidth/2,clientY:window.innerHeight/2});
                        var t=document.elementFromPoint(window.innerWidth/2,window.innerHeight/2);
                        if(t)t.dispatchEvent(evt);
                    }catch(e){}
                }
                function schedule(){setTimeout(tryClick,600);setTimeout(tryClick,1800);setTimeout(tryClick,3500);}
                if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',schedule);}
                else{schedule();}
            })();
            </script>
        """.trimIndent()
    }

    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.org"
    // 2026-06-01 : nettoyé bf0skv.org + filemoon.site (NXDOMAIN)
    override val aliasUrls = listOf("https://bysejikuar.com","https://moflix-stream.link","https://bysezoxexe.com","https://bysebuho.com","https://filemoon.sx","https://bysekoze.com","https://bysesayeveum.com","https://lukefirst.lol","https://weneverbeenfree.com","https://gn1r5n.org")

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
            extractOnDomain(link)
        } catch (e: Exception) {
            // 2026-07-16 : fallback cross-domaine. Les IDs Filemoon/Byse sont partagés
            // entre TOUS les domaines. Si un domaine échoue (SSL, 428, timeout, WAF…),
            // on retente sur un autre. Seul cas où on ne retente PAS : link rot (vidéo
            // supprimée côté Filemoon → inutile d'essayer ailleurs, même ID = même réponse).
            val msg = e.message.orEmpty()
            if (msg.contains("link rot", ignoreCase = true) ||
                msg.contains("video expired", ignoreCase = true)
            ) {
                throw e // Vraiment mort, pas la peine de retenter ailleurs
            }
            val match = VIDEO_ID_REGEX.find(link) ?: throw e
            val linkType = match.groupValues[1]
            val videoId = match.groupValues[2]
            val originalDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1)
                ?: throw e

            Log.w(TAG, "[Filemoon] Domain $originalDomain failed (${e.javaClass.simpleName}: $msg) — trying fallback domains")

            for (fallback in FALLBACK_DOMAINS) {
                if (fallback.equals(originalDomain, ignoreCase = true)) continue
                val fallbackLink = "$fallback/$linkType/$videoId"
                Log.d(TAG, "[Filemoon] Trying fallback domain: $fallbackLink")
                try {
                    return extractOnDomain(fallbackLink)
                } catch (fe: Exception) {
                    val fMsg = fe.message.orEmpty()
                    if (fMsg.contains("link rot", ignoreCase = true) ||
                        fMsg.contains("video expired", ignoreCase = true)
                    ) {
                        throw fe // Vidéo morte sur ce domaine aussi
                    }
                    Log.w(TAG, "[Filemoon] Fallback $fallback failed: ${fe.javaClass.simpleName}: $fMsg")
                    // Continue vers le prochain domaine
                }
            }
            // Tous les fallbacks ont échoué
            throw e
        }
    }

    /** Extraction sur un seul domaine : API d'abord, puis WebView fallback. */
    private suspend fun extractOnDomain(link: String): Video {
        return try {
            extractViaApi(link)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("link rot", ignoreCase = true) ||
                msg.contains("video expired", ignoreCase = true)
            ) {
                Log.w(TAG, "[Filemoon] Link rot detected — failing fast: $msg")
                throw e
            }
            Log.w(TAG, "[Filemoon] API failed (${e.javaClass.simpleName}: $msg) — trying WebView fallback")
            try {
                extractViaWebView(link)
            } catch (we: Exception) {
                Log.e(TAG, "[Filemoon] WebView fallback also failed: ${we.message}")
                throw e
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
        val parentUrl = byseParentUrl(currentDomain) ?: try {
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
     * decrypted the playback response.
     *
     * 2026-07-16: Filemoon added a "human verification" step. The embed page now
     * loads a cross-origin iframe (q8y5z.com) that shows a centered play button
     * with "Cliquez sur le bouton de lecture pour vérifier que vous êtes humain".
     * Only after clicking the button does the iframe swap to the actual JW player
     * that fires the m3u8 request.
     *
     * Strategy: intercept the cross-origin iframe HTTP response in
     * shouldInterceptRequest, inject JS that auto-clicks the verification button,
     * and return the modified HTML. The injected JS runs in the iframe's own
     * context (same-origin with the served response), so it CAN access the
     * iframe's DOM — unlike dispatchTouchEvent which can't reach into a
     * cross-origin iframe from a headless (non-windowed) WebView.
     *
     * Timeout: 25s to accommodate verification + player load delay.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractViaWebView(link: String): Video = withContext(Dispatchers.Main) {
        val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1)
            ?: throw Exception("Could not extract base URL")
        val parentUrl = byseParentUrl(currentDomain) ?: try {
            UserPreferences.currentProvider?.baseUrl
        } catch (_: Throwable) { null }
        val parentOrigin = parentUrl?.trimEnd('/')

        val result = withTimeoutOrNull(25_000L) {
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

                /**
                 * Intercept a verification iframe request: fetch the HTML ourselves,
                 * inject the auto-click script, and return the modified response.
                 * Runs on WebView IO thread (NOT main thread), safe for network I/O.
                 */
                fun interceptVerificationIframe(url: String): WebResourceResponse? {
                    return try {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.setRequestProperty("User-Agent", ANDROID_CHROME_UA)
                        conn.setRequestProperty("Referer", link)
                        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                        // Forward cookies from CookieManager
                        val cookies = android.webkit.CookieManager.getInstance().getCookie(url)
                        if (!cookies.isNullOrBlank()) conn.setRequestProperty("Cookie", cookies)
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.instanceFollowRedirects = true

                        val responseCode = conn.responseCode
                        // Store any Set-Cookie headers back into CookieManager
                        conn.headerFields?.get("Set-Cookie")?.forEach { cookie ->
                            android.webkit.CookieManager.getInstance().setCookie(url, cookie)
                        }

                        val html = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()

                        // Inject auto-click script
                        val modified = if (html.contains("</body>", ignoreCase = true)) {
                            html.replaceFirst("</body>", "$AUTO_CLICK_SCRIPT\n</body>", ignoreCase = true)
                        } else if (html.contains("</html>", ignoreCase = true)) {
                            html.replaceFirst("</html>", "$AUTO_CLICK_SCRIPT\n</html>", ignoreCase = true)
                        } else {
                            html + "\n" + AUTO_CLICK_SCRIPT
                        }

                        Log.d(TAG, "[Filemoon-WV] Injected auto-click into verification iframe ($responseCode, ${modified.length} chars)")

                        // Build response headers — pass through CORS-relevant ones
                        val responseHeaders = mutableMapOf<String, String>()
                        responseHeaders["Access-Control-Allow-Origin"] = "*"
                        responseHeaders["Cache-Control"] = "no-cache"
                        conn.headerFields?.forEach { (key, values) ->
                            if (key != null && values.isNotEmpty() &&
                                key.lowercase() !in setOf("set-cookie", "transfer-encoding", "content-length", "content-encoding")
                            ) {
                                responseHeaders[key] = values.last()
                            }
                        }

                        WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            responseCode,
                            if (responseCode in 200..299) "OK" else "Error",
                            responseHeaders,
                            modified.byteInputStream(Charsets.UTF_8)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "[Filemoon-WV] Failed to intercept verification iframe: ${e.message}")
                        null // Let WebView handle it normally
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

                // Real layout so the iframe renders with actual dimensions
                val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
                val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                webView.measure(wSpec, hSpec)
                webView.layout(0, 0, 1080, 1920)

                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null

                        // 1. Intercept m3u8 requests → we have the video
                        if (url.contains(".m3u8") && !resolved) {
                            Log.i(TAG, "[Filemoon-WV] m3u8 captured: $url")
                            val videoHeaders = mutableMapOf(
                                "Referer" to link,
                                "Origin" to currentDomain,
                                "User-Agent" to ANDROID_CHROME_UA
                            )
                            cleanupAndResume(Video(source = url, headers = videoHeaders))
                        }

                        // 2. Intercept verification iframe → inject auto-click
                        val host = request?.url?.host ?: return null
                        if (!resolved && VERIFICATION_DOMAINS.any { host.contains(it, ignoreCase = true) }) {
                            // Only intercept document requests (HTML), not sub-resources
                            val accept = request.requestHeaders?.get("Accept") ?: ""
                            if (accept.contains("text/html") || accept.isEmpty()) {
                                return interceptVerificationIframe(url)
                            }
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

                val loadHeaders = mutableMapOf<String, String>()
                if (parentUrl != null) {
                    loadHeaders["Referer"] = parentUrl
                    loadHeaders["Origin"] = parentOrigin!!
                }

                Log.d(TAG, "[Filemoon-WV] loading $link (referer=$parentUrl)")
                webView.loadUrl(link, loadHeaders)
            }
        } ?: throw Exception("Filemoon WebView fallback timed out (25s)")

        if (result.source.isBlank()) {
            throw Exception("Filemoon WebView fallback resolved empty source")
        }
        result
    }

    /**
     * 2026-07-16 : les hôtes « Byse frontend » gn1r5n.org / weneverbeenfree.com sont servis par
     *   VoirAnime (ex. LECTEUR MOON). Quand ils arrivent en BACKUP (l'user est sur un AUTRE
     *   provider — ex. AnimeSama), le parent/référer par défaut (currentProvider = anime-sama.to)
     *   est REFUSÉ par le WAF Filemoon → l'extraction échoue (serveur mort dans l'app alors que le
     *   flux marche dans le navigateur). On force donc le référer sur le vrai site embarquant.
     */
    private fun byseParentUrl(currentDomain: String): String? = when {
        currentDomain.contains("gn1r5n", ignoreCase = true) ||
            currentDomain.contains("weneverbeenfree", ignoreCase = true) -> "https://voir-anime.to"
        else -> null
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
