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
 * Hoca8 / bolaloca / cartelive / embedme — extracteur WebView pour chaînes sport
 * et Canal+ Live.
 *
 * Architecture :
 *   bolaloca.my/player/2/<id>  ─┐
 *   cartelive.club/player/2/<id> ├─→ iframe → hoca8.com/footy.php?live=<feedId>
 *   embedme.click/player/2/<id> ─┘                  └→ JS charge le m3u8 dynamiquement
 *
 * Le m3u8 final est délivré par un CDN externe (cloudflare-hosted typiquement).
 * On laisse WebView faire le chargement complet et on intercepte la requête m3u8.
 */
open class Hoca8Extractor : Extractor() {
    override val name = "Hoca8"
    override val mainUrl = "https://hoca8.com"
    override val aliasUrls = listOf(
        "https://bolaloca.my",
        "https://cartelive.club",
        "https://embedme.click",
    )

    // Pas de cache — best-effort sur stream live, le m3u8 peut tokeniser.
    override val cacheTtlMs: Long = 0L

    private val context = StreamFlixApp.instance.applicationContext

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video {
        val streamUrl = extractByIntercepting(link)
            ?: throw Exception("Hoca8: Could not capture stream URL from $link")
        val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
        return Video(
            source = streamUrl,
            headers = mapOf(
                "Referer" to "https://hoca8.com/",
                "Origin" to "https://hoca8.com",
                "User-Agent" to ANDROID_CHROME_UA,
            ),
            type = if (isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractByIntercepting(url: String): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000L) {
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
                    // 2026-05-16 v6 : bridge JS pour capturer l'URL .m3u8 in-page
                    // (HLS.js la fetch en mémoire, jamais visible par shouldInterceptRequest).
                    webView.addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun onM3u8Found(url: String) {
                            android.util.Log.d("Hoca8Extractor", "JS captured m3u8: $url")
                            if (url.isNotBlank()) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    resolve(url)
                                }
                            }
                        }
                    }, "Hoca8Bridge")

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
                            // Block popunder ads qui essayent d'ouvrir des onglets
                            return BLOCKED_HOSTS.any { host.contains(it) }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host ?: ""

                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            val path = request.url?.path?.lowercase() ?: ""
                            val hostLower = host.lowercase()
                            // 2026-05-16 v5 : exclure les CDN de scripts/libs.
                            val isCdnLib = hostLower.contains("jsdelivr.net") ||
                                hostLower.contains("cdnjs.cloudflare.com") ||
                                hostLower.contains("unpkg.com") ||
                                hostLower.contains("googleapis.com") ||
                                hostLower.contains("googletagmanager") ||
                                hostLower.contains("google-analytics")
                            if (isCdnLib) return null

                            // 2026-05-17 v7 : log toutes les requêtes pour debug
                            //   (sauf images / fonts / css qui spamment)
                            if (!path.endsWith(".png") && !path.endsWith(".jpg") &&
                                !path.endsWith(".jpeg") && !path.endsWith(".gif") &&
                                !path.endsWith(".webp") && !path.endsWith(".svg") &&
                                !path.endsWith(".woff") && !path.endsWith(".woff2") &&
                                !path.endsWith(".ttf") && !path.endsWith(".css") &&
                                !path.endsWith(".ico")) {
                                android.util.Log.d("Hoca8Extractor", "REQ: $reqUrl")
                            }

                            // 2026-05-17 v9 : intercepter la réponse HTML de
                            //   hoca8.com/footy.php (l'iframe) et y injecter
                            //   notre hook JS au début du <head>. Comme ça
                            //   HLS.js s'initialise dans un contexte où XHR
                            //   et fetch sont déjà hookés → capture du m3u8.
                            //   On évite le re-route v8 qui menait à /sorry.
                            if (hostLower.contains("hoca8.com") &&
                                path.contains("/footy.php") &&
                                request.method?.uppercase() == "GET") {
                                try {
                                    val client = okhttp3.OkHttpClient.Builder()
                                        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                        .followRedirects(true)
                                        .build()
                                    val req = okhttp3.Request.Builder()
                                        .url(reqUrl)
                                        .header("Referer", "https://bolaloca.my/")
                                        .header("User-Agent", ANDROID_CHROME_UA)
                                        .header("Accept", "text/html,application/xhtml+xml")
                                        .build()
                                    val resp = client.newCall(req).execute()
                                    val body = resp.body?.string()
                                    if (body != null && body.isNotEmpty()) {
                                        // Inject hook au début du <head> (ou
                                        // tout début du HTML si pas de <head>).
                                        val scriptTag = "<script>$HOOK_M3U8_JS</script>"
                                        val injected = when {
                                            body.contains("<head>", ignoreCase = true) ->
                                                body.replaceFirst(
                                                    Regex("(?i)<head>"),
                                                    "<head>$scriptTag"
                                                )
                                            body.contains("<html", ignoreCase = true) ->
                                                body.replaceFirst(
                                                    Regex("(?i)(<html[^>]*>)"),
                                                    "$1$scriptTag"
                                                )
                                            else -> scriptTag + body
                                        }
                                        android.util.Log.d("Hoca8Extractor", "INJECT HOOK into $reqUrl (${body.length}b)")
                                        return WebResourceResponse(
                                            "text/html",
                                            "utf-8",
                                            injected.byteInputStream(Charsets.UTF_8)
                                        )
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("Hoca8Extractor", "iframe inject failed: ${e.message}")
                                }
                            }

                            // Case 1 : la vraie playlist .m3u8 directement (rare ici car
                            // P2P HLS la manipule en mémoire avant le video player).
                            val hasM3u8 = path.endsWith(".m3u8") ||
                                path.contains(".m3u8?") ||
                                path.contains(".m3u8/") ||
                                reqUrl.contains(".m3u8", ignoreCase = true)
                            if (hasM3u8) {
                                android.util.Log.d("Hoca8Extractor", "HLS PLAYLIST CAPTURED: $reqUrl")
                                resolve(reqUrl)
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            // v6 : la dérivation depuis .ts ne marche pas (403 sur les
                            // m3u8 dérivés — le serveur exige un token de session que
                            // seul HLS.js détient). On compte sur le JS bridge injecté
                            // (Hoca8Bridge.onM3u8Found) qui hook XHR/fetch.
                            return null
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            if (view == null || resolved) return
                            android.util.Log.d("Hoca8Extractor", "onPageStarted: $url")
                            // 2026-05-16 v6 : inject JS hook AVANT que HLS.js charge.
                            // Hook XMLHttpRequest.prototype.open ET window.fetch pour
                            // capturer toute requête .m3u8 + notifier Kotlin.
                            view.evaluateJavascript(HOOK_M3U8_JS, null)
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return
                            android.util.Log.d("Hoca8Extractor", "onPageFinished: $finishedUrl")
                            // Re-inject au cas où la page nav reset les hooks
                            view.evaluateJavascript(HOOK_M3U8_JS, null)
                            // 2026-05-17 v7 : démarre un poll qui scanne window.*
                            //   variables où HLS.js / players P2P stockent l'URL.
                            view.evaluateJavascript(POLL_VARS_JS, null)
                            view.postDelayed({
                                if (!resolved) {
                                    android.util.Log.w("Hoca8Extractor", "Timeout — no stream captured")
                                    resolve(null)
                                }
                            }, 15_000L)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) { /* ignore */ }
                    }

                    // Referer freeshot.live = origin légitime (les mirrors checkent
                    // l'origin pour anti-hotlink).
                    webView.loadUrl(url, mapOf("Referer" to "https://www.freeshot.live/"))

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
        /** 2026-05-16 v6 : JS injecté dans la page pour hook XHR + fetch et
         *  capturer l'URL .m3u8 que HLS.js charge en mémoire. Notifie Kotlin
         *  via Hoca8Bridge.onM3u8Found(url). Idempotent (peut être ré-injecté). */
        /** 2026-05-17 v7 : poll JS qui scanne window.* variables + DOM
         *  pour trouver une URL .m3u8. Tente toutes les 500ms pendant
         *  10s. Patterns testés :
         *   - window.hls.url / window.hls.media.src
         *   - window.player.src / window.video.src
         *   - <video>.src / <source src=...>
         *   - window.streamUrl, window.m3u8Url, window.playerUrl
         *   - window.HLSConfig.url
         *  Si trouvé, notifie Hoca8Bridge.onM3u8Found(url). */
        private val POLL_VARS_JS = """
            (function() {
                if (window.__hoca8_polled) return;
                window.__hoca8_polled = true;
                var attempts = 0;
                var maxAttempts = 20;
                function scan() {
                    attempts++;
                    var candidates = [];
                    try {
                        if (window.hls && window.hls.url) candidates.push(window.hls.url);
                        if (window.hls && window.hls.media && window.hls.media.src) candidates.push(window.hls.media.src);
                        if (window.hls && window.hls.media && window.hls.media.currentSrc) candidates.push(window.hls.media.currentSrc);
                        if (window.player && window.player.src) candidates.push(window.player.src);
                        if (window.player && window.player.src_) candidates.push(window.player.src_);
                        if (window.player && window.player.options_ && window.player.options_.sources) {
                            var srcs = window.player.options_.sources;
                            if (Array.isArray(srcs)) srcs.forEach(function(s){ if(s && s.src) candidates.push(s.src); });
                        }
                        if (window.video && window.video.src) candidates.push(window.video.src);
                        if (window.streamUrl) candidates.push(window.streamUrl);
                        if (window.m3u8Url) candidates.push(window.m3u8Url);
                        if (window.playerUrl) candidates.push(window.playerUrl);
                        if (window.HLSConfig && window.HLSConfig.url) candidates.push(window.HLSConfig.url);
                        var vids = document.querySelectorAll('video');
                        for (var i = 0; i < vids.length; i++) {
                            if (vids[i].src) candidates.push(vids[i].src);
                            if (vids[i].currentSrc) candidates.push(vids[i].currentSrc);
                        }
                        var sources = document.querySelectorAll('source');
                        for (var j = 0; j < sources.length; j++) {
                            if (sources[j].src) candidates.push(sources[j].src);
                        }
                    } catch(e) {}
                    for (var k = 0; k < candidates.length; k++) {
                        var u = candidates[k];
                        if (typeof u === 'string' && u.indexOf('.m3u8') !== -1) {
                            try {
                                if (typeof Hoca8Bridge !== 'undefined') {
                                    Hoca8Bridge.onM3u8Found(u);
                                }
                            } catch(e) {}
                            return;
                        }
                    }
                    if (attempts < maxAttempts) {
                        setTimeout(scan, 500);
                    }
                }
                setTimeout(scan, 300);
            })();
        """.trimIndent()

        private val HOOK_M3U8_JS = """
            (function() {
                if (window.__hoca8_hooked) return;
                window.__hoca8_hooked = true;
                function report(u) {
                    if (typeof u !== 'string') return;
                    if (!u || u.indexOf('.m3u8') === -1) return;
                    // Skip jsdelivr/cdnjs (lib URLs)
                    if (u.indexOf('jsdelivr.net') !== -1 || u.indexOf('cdnjs.cloudflare') !== -1) return;
                    try {
                        if (typeof Hoca8Bridge !== 'undefined') {
                            Hoca8Bridge.onM3u8Found(u);
                        }
                    } catch(e) {}
                }
                // Hook XMLHttpRequest.open
                try {
                    var origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        report(url);
                        return origOpen.apply(this, arguments);
                    };
                } catch(e) {}
                // Hook fetch
                try {
                    var origFetch = window.fetch;
                    if (origFetch) {
                        window.fetch = function(input, init) {
                            var u = (typeof input === 'string') ? input : (input && input.url) || '';
                            report(u);
                            return origFetch.apply(this, arguments);
                        };
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        private val BLOCKED_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice",
            "popads", "popunder", "popcash", "propellerads",
            "exoclick", "juicyads", "trafficjunky",
            "googletagmanager", "google-analytics",
            "mc.yandex.ru", "yandex.ru", "metrica.yandex",
            "cloudflareinsights.com", "static.cloudflareinsights",
            // Popunder ads spécifiques observées sur hoca8/bolaloca
            "histatsv.com", "histats.com", "popcash.net",
            "adsterra.com", "redirect.adsafe",
        )

        /** 2026-05-16 : déduit l'URL de la playlist .m3u8 à partir d'une URL
         *  de segment .ts. Patterns observés sur Hoca8 :
         *   /hls/wsmkmlfeed01-4920.ts → /hls/wsmkmlfeed01.m3u8
         *   /hls/feed_42/seg-12.ts → /hls/feed_42/index.m3u8 OR playlist.m3u8
         *   /hls/abc/123-456.ts → /hls/abc/123.m3u8 (last seg num stripped)
         *  Retourne null si pas de pattern reconnu. */
        fun deriveM3u8FromSegment(segmentUrl: String): String? {
            return try {
                val uri = android.net.Uri.parse(segmentUrl)
                val pathSegments = uri.pathSegments ?: return null
                if (pathSegments.isEmpty()) return null
                val lastSeg = pathSegments.last()
                if (!lastSeg.endsWith(".ts", ignoreCase = true)) return null
                // Strip .ts extension
                val withoutExt = lastSeg.removeSuffix(".ts").removeSuffix(".TS")
                // Si le nom est `name-NNNN` (segment numéroté), strip `-NNNN`
                // pour récupérer la base `name`. Ex: wsmkmlfeed01-4920 → wsmkmlfeed01.
                val baseName = Regex("""^(.+?)[-_]\d+$""").find(withoutExt)?.groupValues?.get(1)
                    ?: withoutExt  // si pas numéroté, garde le nom tel quel
                val builder = uri.buildUpon().clearQuery()
                builder.path("")
                // Reconstruit le path : tous les segments sauf le dernier + <baseName>.m3u8
                val parentSegments = pathSegments.dropLast(1)
                parentSegments.forEach { builder.appendPath(it) }
                builder.appendPath("$baseName.m3u8")
                builder.build().toString()
            } catch (_: Exception) { null }
        }
    }
}
