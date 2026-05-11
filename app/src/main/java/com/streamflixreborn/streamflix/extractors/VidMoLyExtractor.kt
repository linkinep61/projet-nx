package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Message
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

open class VidMoLyExtractor : Extractor() {
    override val name = "VidMoLy"
    override val mainUrl = "https://vidmoly.me/"
    override val aliasUrls = listOf(
        "https://vidmoly.net",
        "https://vidmoly.biz",
        "https://vidmoly.to",
    )

    private val context = StreamFlixApp.instance.applicationContext

    override suspend fun extract(link: String): Video {
        // 2026-05-04 : vidmoly.biz a Cloudflare Turnstile (challenge auto-solve
        // ~8s). vidmoly.to redirige TOUS les nouveaux visiteurs vers une page
        // de pub (survey-smiles.com) au premier hit (302) — donc on ne peut
        // pas l'utiliser comme fast-path. Verdict : on force .biz et on gère
        // le challenge CF dans extractByIntercepting (timeout 45s + poll 1s).
        val target = if (link.contains("vidmoly"))
            link.replace(Regex("vidmoly\\.(to|me|net)"), "vidmoly.biz")
        else link

        // 2026-05-09 v2 : timeout 45s → 12s → 25s.
        //   - 45s = laissait des hangs visibles user
        //   - 12s = trop court, certains CF challenges + WebView init prennent
        //     15-20s sur device modeste
        //   - 25s = compromis OK pour CF challenge (5-15s) + un peu de marge
        val hlsUrl = try { extractByIntercepting(target, timeoutMs = 25_000L) }
            catch (e: Exception) {
                throw Exception("VidMoLy: extraction failed for $link (${e.message})")
            }
        if (hlsUrl != null) return buildVideo(hlsUrl, target)

        throw Exception("VidMoLy: Could not find HLS source in: $link")
    }

    private fun buildVideo(hlsUrl: String, pageUrl: String): Video {
        val host = Uri.parse(pageUrl).host ?: "vidmoly.to"
        return Video(
            source = hlsUrl,
            headers = mapOf(
                "Referer" to "https://$host/",
                "Origin" to "https://$host",
                "User-Agent" to Extractor.DEFAULT_USER_AGENT,
            )
        )
    }

    /**
     * Load the actual embed page in WebView with JS enabled.
     * Intercept network requests to capture m3u8 URL when JWPlayer loads it.
     * Also runs JS extraction as backup after page load.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractByIntercepting(url: String, timeoutMs: Long = 45_000L): String? =
        withContext(Dispatchers.Main) {
            val refererHost = Uri.parse(url).host ?: "vidmoly.to"
            val referer = "https://$refererHost/"

            // 2026-05-04 : vidmoly.biz a ajouté un challenge Cloudflare. Le
            // challenge s'auto-solve en ~8s avec UA Chrome + JS puis redirige
            // vers la même URL avec ?cfp=TOKEN. À ce moment onPageFinished
            // refire et le m3u8 est dispo. timeoutMs configurable selon
            // qu'on tente .to (rapide, 12s) ou .biz avec CF (lent, 45s).
            withTimeoutOrNull(timeoutMs) {
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
                        // 2026-05-04 : Android Chrome UA (matche le runtime
                        // WebView) pour mieux passer Cloudflare Turnstile.
                        settings.userAgentString = ANDROID_CHROME_UA
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        @Suppress("DEPRECATION")
                        settings.allowFileAccess = true
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
                            val reqUrl = request?.url?.toString() ?: return true
                            val host = request.url?.host ?: ""
                            // Allow main vidmoly domains, block ww1/ww2/etc ad redirects
                            val isMainVidmoly = host.matches(Regex("vidmoly\\.(to|biz|me|net)"))
                            if (isMainVidmoly) return false
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null

                            if (reqUrl.contains(".m3u8")) {
                                android.util.Log.d("VidMoLyExtractor", "m3u8 INTERCEPTED via shouldInterceptRequest: $reqUrl")
                                view?.post { resolve(reqUrl) }
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            val host = request.url?.host ?: ""
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return

                            android.util.Log.d("VidMoLyExtractor", "onPageFinished: $finishedUrl")

                            // 2026-05-04 : 3 cas possibles à la fin de chargement :
                            //  1) Mini page JS-redirect ancienne (h<1000 + window.location.replace)
                            //  2) Page Cloudflare Turnstile/challenge — auto-solve en ~8s, on attend
                            //  3) Page player normale avec m3u8 dans le HTML
                            view.evaluateJavascript(
                                "(function(){\n" +
                                "  var h = document.documentElement.outerHTML;\n" +
                                "  // Cas 1 : JS-redirect mini page\n" +
                                "  if (h.length < 1000 && h.indexOf('window.location') > -1) {\n" +
                                "    var m = h.match(/window\\.location\\.replace\\(['\"]([^'\"]+)['\"]/);\n" +
                                "    if (m) return 'REDIRECT:' + m[1];\n" +
                                "  }\n" +
                                "  // Cas 2 : challenge Cloudflare\n" +
                                "  if (h.indexOf('challenges.cloudflare.com') > -1\n" +
                                "      || h.indexOf('Verification de securite') > -1\n" +
                                "      || h.indexOf('Vérification de sécurité') > -1\n" +
                                "      || h.indexOf('Just a moment') > -1\n" +
                                "      || h.indexOf('cf-turnstile') > -1) {\n" +
                                "    return 'CF_CHALLENGE';\n" +
                                "  }\n" +
                                "  return null;\n" +
                                "})()"
                            ) { result ->
                                val cleaned = result?.trim()?.removeSurrounding("\"")
                                android.util.Log.d("VidMoLyExtractor", "page state: $cleaned")
                                when {
                                    cleaned?.startsWith("REDIRECT:") == true -> {
                                        val redirectUrl = cleaned.removePrefix("REDIRECT:")
                                        android.util.Log.d("VidMoLyExtractor", "REDIRECT detected → loading $redirectUrl")
                                        if (redirectUrl.startsWith("http")) {
                                            view.loadUrl(redirectUrl, mapOf("Referer" to referer))
                                        }
                                        return@evaluateJavascript
                                    }
                                    cleaned == "CF_CHALLENGE" -> {
                                        android.util.Log.d("VidMoLyExtractor", "CF challenge detected → polling for m3u8")
                                        scheduleCfPoll(view, attempt = 0, ::resolve)
                                        return@evaluateJavascript
                                    }
                                }

                                // Cas 3 : page normale, on cherche le m3u8
                                if (resolved) return@evaluateJavascript
                                android.util.Log.d("VidMoLyExtractor", "normal page → tryExtractWithRetry")
                                tryExtractWithRetry(view, ::resolve)
                            }

                            // Filet de sécurité : timeout après 30s sans m3u8 capturé.
                            // Bumped de 12s à 30s pour laisser le temps au CF challenge.
                            view.postDelayed({
                                if (!resolved) {
                                    resolve(null)
                                }
                            }, 30000)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            // Main frame errors handled silently
                        }
                    }

                    webView.loadUrl(url, mapOf("Referer" to referer))

                    cont.invokeOnCancellation {
                        resolved = true
                        // 2026-05-10 : invokeOnCancellation tourne sur kotlinx
                        // DefaultExecutor (background) → WebView.stopLoading() et
                        // destroy() doivent être posté sur le main thread, sinon
                        // crash "WebView method called on wrong thread" qui plante
                        // l'app entière (ex: AnimeSama / Assassination Classroom).
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                webView.stopLoading()
                                webView.destroy()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

    /** Tente une extraction immédiate puis un retry à 3s puis 6s.
     *  Permet à JWPlayer d'avoir le temps d'init même sur connexion lente. */
    private fun tryExtractWithRetry(view: WebView, resolve: (String?) -> Unit) {
        view.evaluateJavascript(EXTRACT_M3U8_JS) { result ->
            val extracted = result?.trim()
                ?.removeSurrounding("\"")
                ?.takeIf { it != "null" && it.contains(".m3u8") }
            if (extracted != null) {
                resolve(extracted)
                return@evaluateJavascript
            }
            view.postDelayed({
                view.evaluateJavascript(EXTRACT_M3U8_JS) { r2 ->
                    val e2 = r2?.trim()?.removeSurrounding("\"")
                        ?.takeIf { it != "null" && it.contains(".m3u8") }
                    if (e2 != null) resolve(e2)
                    else view.postDelayed({
                        view.evaluateJavascript(EXTRACT_M3U8_JS) { r3 ->
                            val e3 = r3?.trim()?.removeSurrounding("\"")
                                ?.takeIf { it != "null" && it.contains(".m3u8") }
                            if (e3 != null) resolve(e3)
                        }
                    }, 3000)
                }
            }, 3000)
        }
    }

    /** Poll la page toutes les 1s pendant ~25s en attendant que le challenge
     *  Cloudflare Turnstile se résolve (auto-solve typique : 5-15s) et que
     *  le m3u8 apparaisse dans le HTML. Premier check immédiat (delay=0)
     *  puis 1s entre chaque retry — minimise la latence quand le challenge
     *  passe vite. */
    private fun scheduleCfPoll(view: WebView, attempt: Int, resolve: (String?) -> Unit) {
        if (attempt > 25) return // max 25 polls × 1s = 25s
        val delay = if (attempt == 0) 0L else 1000L
        view.postDelayed({
            view.evaluateJavascript(EXTRACT_M3U8_JS) { r ->
                val extracted = r?.trim()?.removeSurrounding("\"")
                    ?.takeIf { it != "null" && it.contains(".m3u8") }
                if (extracted != null) {
                    resolve(extracted)
                } else {
                    scheduleCfPoll(view, attempt + 1, resolve)
                }
            }
        }, delay)
    }

    class ToDomain : VidMoLyExtractor() {
        override val mainUrl: String = "https://vidmoly.to/"
    }

    companion object {
        // UA Android Chrome — matche le runtime de la WebView Android, donc
        // moins de chance que Cloudflare le flag comme bot vs un UA Windows
        // qui crée un mismatch suspect.
        private const val ANDROID_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val BLOCKED_HOSTS = listOf(
            "ww1.vidmoly", "ww2.vidmoly", "ww3.vidmoly", "ww4.vidmoly", "ww5.vidmoly",
            "googlesyndication", "doubleclick", "adservice",
            "popads", "searchresultsworld", "ad.plus",
            "juicyads", "exoclick", "trafficjunky",
            "popunder", "popcash", "propellerads",
            "bvtpk.com", "videocdnshop.com",
            "mc.yandex.ru", "clksite.com", "clkurl.com",
        )

        /**
         * Synchronous JS to extract m3u8 from page DOM.
         */
        private const val EXTRACT_M3U8_JS = """
            (function() {
                try {
                    var scripts = document.querySelectorAll('script');
                    for (var i = 0; i < scripts.length; i++) {
                        var text = scripts[i].textContent;
                        if (text && text.indexOf('m3u8') > -1) {
                            var match = text.match(/sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]*\.m3u8[^'"]*)['"]/);
                            if (match) return match[1];
                            match = text.match(/file\s*:\s*['"]([^'"]*\.m3u8[^'"]*)['"]/);
                            if (match) return match[1];
                            match = text.match(/['"]([^'"]*\.m3u8[^'"]*)['"]/);
                            if (match) return match[1];
                        }
                    }
                    var html = document.documentElement.innerHTML;
                    var match = html.match(/['"]([^'"]*\.m3u8[^'"]*)['"]/);
                    if (match) return match[1];
                } catch(e) {
                    return 'ERROR:' + e.message;
                }
                return null;
            })();
        """
    }
}
