package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Message
import android.webkit.WebChromeClient
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
import kotlin.coroutines.resume

/**
 * MoiflixExtractor — extrait le m3u8 final des pages moiflix.com.
 *
 * Architecture moiflix :
 *   moiflix.com/movie/{slug}                            ← page film
 *   moiflix.com/episode/{slug}/season-N-episode-N       ← page épisode
 *     └─> bouton "Continuer" (gate user click)
 *           └─> <iframe src="lecteur1.xtremestream.xyz/player/index.php?...">
 *                 └─> player JS qui charge le m3u8
 *
 * Le bouton "Continuer" est nécessaire pour que l'iframe se charge — sans
 * clic, le DOM ne contient pas l'iframe player.
 *
 * Stratégie : WebView qui charge la page moiflix, auto-click "Continuer",
 * attend l'iframe xtremestream.xyz, intercepte le m3u8 via shouldInterceptRequest.
 *
 * Pas de Cloudflare challenge, pas de login. Audio FR garanti (le site est
 * 100% FR : titres traduits, synopsis FR).
 *
 * Accepte :
 *   - https://moiflix.com/movie/{slug}
 *   - https://moiflix.com/episode/{slug}/season-N-episode-N
 *   - https://moiflix.org/(slug) (alias)
 */
open class MoiflixExtractor : Extractor() {
    override val name = "Moiflix"
    override val mainUrl = "https://moiflix.com/"
    override val aliasUrls = listOf("https://moiflix.org")

    private val context = StreamFlixApp.instance.applicationContext

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video {
        val hlsUrl = extractByIntercepting(link)
            ?: throw Exception("Moiflix: Could not find HLS source in $link")
        // L'URL master peut être .m3u8 (cas générique) OU xs1.php?data=XXX
        // (cas xtremestream — content-type text/plain mais body HLS valide).
        // On force le MIME type APPLICATION_M3U8 pour que ExoPlayer utilise
        // HlsMediaSource direct, sans tomber dans l'auto-detect par extension
        // qui rate sur PlayerTvFragment.
        return Video(
            source = hlsUrl,
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            headers = mapOf(
                "Referer" to "https://lecteur1.xtremestream.xyz/",
                "Origin" to "https://lecteur1.xtremestream.xyz",
                "User-Agent" to ANDROID_CHROME_UA,
            )
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractByIntercepting(url: String): String? =
        withContext(Dispatchers.Main) {
            // 60s pour gérer CF challenge sur emmmmbed.com (fallback path).
            // Le fast path xtremestream résout en ~1s donc pas affecté.
            withTimeoutOrNull(60_000L) {
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
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                    }

                    // JavascriptInterface pour communication JS→Kotlin sans passer
                    // par console.log (qui est intercepté → ralentit tous les
                    // console.log de la page → certains players (emmmmbed) le
                    // détectent comme DevTools open et refusent de fire le m3u8).
                    val jsBridge = object {
                        @android.webkit.JavascriptInterface
                        fun onM3u8(url: String) {
                            android.util.Log.d("MoiflixExtractor", "m3u8 RESOLVED (fast via bridge): $url")
                            resolve(url)
                        }

                        @android.webkit.JavascriptInterface
                        fun onUnsupported(provider: String) {
                            // Non-xtremestream provider (emmmmbed, etc.) — bloqué par CF
                            // Turnstile. On échoue fast (1s) plutôt que d'attendre 50s.
                            android.util.Log.w("MoiflixExtractor", "Unsupported provider, abandon: $provider")
                            resolve(null)
                        }

                        @android.webkit.JavascriptInterface
                        fun log(msg: String) {
                            android.util.Log.d("MoiflixExtractor", "JS_LOG: $msg")
                        }
                    }
                    webView.addJavascriptInterface(jsBridge, "_mfxBridge")

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean,
                            isUserGesture: Boolean, resultMsg: Message?
                        ): Boolean = false
                        // PAS d'onConsoleMessage override : on laisse les console.log
                        // de la page passer naturellement (sinon emmmmbed détecte
                        // le hook comme DevTools open et bloque le m3u8).
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

                            // Capture m3u8 :
                            //   1. URLs avec extension .m3u8 (cas standard)
                            //   2. xtremestream sert le master playlist HLS via
                            //      `xs1.php?data=XXX` (sans `q=`) avec content-type
                            //      text/plain mais body #EXTM3U → c'est BIEN un master
                            //      m3u8 que ExoPlayer peut lire si on lui dit.
                            //      Les variants `xs1.php?data=XXX&q=720` sont à ignorer
                            //      (ExoPlayer fera ses propres requêtes variants).
                            val isM3u8Ext = reqUrl.contains(".m3u8")
                            val isXtremeMaster =
                                reqUrl.contains("xs1.php?data=") && !reqUrl.contains("q=")
                            if (isM3u8Ext || isXtremeMaster) {
                                android.util.Log.d("MoiflixExtractor", "m3u8 INTERCEPTED: $reqUrl")
                                // 2026-05-04 BUG FIX : ne pas utiliser view.post() —
                                // le Main thread peut être occupé par les retries du
                                // player iframe (qui boucle sur "manifestLoadError" car
                                // on bloque sa réponse), repoussant le resolve au-delà
                                // du timeout de 30s. cont.resume est thread-safe via
                                // suspendCancellableCoroutine, donc on l'appelle direct.
                                resolve(reqUrl)
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            // Block ad/tracker hosts
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return
                            android.util.Log.d("MoiflixExtractor", "onPageFinished: $finishedUrl")

                            // Auto-click sur .play-btn (le gros bouton play violet)
                            // qui est dans #zpub-wrap. Son click handler injecte
                            // directement l'iframe player xtremestream.xyz, et la
                            // popup ad est bloquée par onCreateWindow=false.
                            view.evaluateJavascript(AUTO_CLICK_PLAY_JS, null)

                            // Filet de sécurité — 50s pour laisser le CF challenge
                            // d'emmmmbed.com auto-valider et le player charger ses
                            // assets avant de demander le m3u8.
                            view.postDelayed({
                                if (!resolved) {
                                    android.util.Log.w("MoiflixExtractor", "Timeout — no m3u8 captured")
                                    resolve(null)
                                }
                            }, 50_000L)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            // ignored
                        }
                    }

                    webView.loadUrl(url, mapOf("Referer" to "https://moiflix.com/"))

                    cont.invokeOnCancellation {
                        resolved = true
                        // 2026-05-10 : WebView méthodes doivent tourner sur main thread,
                        // sinon crash "WebView method called on wrong thread".
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

    companion object {
        private val ALLOWED_HOSTS = listOf(
            "moiflix.com",
            "moiflix.org",
            "xtremestream.xyz",
            "xtremestream.com",
            "emmmmbed.com", // alternative provider utilisé par moiflix (CF challenge)
            "embed.com",
            "papadustream.dad", // matomo tracker, harmless mais peut être nécessaire pour pas casser le JS
            "challenges.cloudflare.com",
            "ajax.googleapis.com",
            "cdn.fluidplayer.com",
            "cdnjs.cloudflare.com",
            "googleapis.com",
            "gstatic.com",
            "jsdelivr.net",
            "cloudfront.net",
        )

        private val BLOCKED_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice",
            "popads", "popunder", "popcash", "propellerads",
            "exoclick", "juicyads", "trafficjunky",
            "googletagmanager", "google-analytics",
        )

        /** JS qui SKIPPE le click sur .play-btn et fait directement la requête
         *  AJAX que le click handler de moiflix ferait :
         *
         *    POST https://moiflix.com/ajax/embed   (Content-Type: form-urlencoded)
         *    Body: id={data-embed}&self={data-embed}
         *
         *  Le serveur répond avec du HTML qui contient l'iframe :
         *    <iframe src="https://lecteur1.xtremestream.xyz/player/index.php?data=XXX">
         *
         *  On injecte ce HTML dans <div id="player">. La WebView charge alors
         *  l'iframe player, qui fait sa requête m3u8 → shouldInterceptRequest
         *  capture l'URL HLS finale.
         *
         *  Pourquoi ne PAS cliquer .play-btn directement :
         *    - En desktop Chrome, .click() programmatique fonctionne
         *    - En WebView Android (mobile UA), le click handler jQuery ne se
         *      déclenche pas (timing/lifecycle différent)
         *    - Faire l'XHR direct est plus rapide ET plus fiable. */
        private const val AUTO_CLICK_PLAY_JS = """
            (function(){
                var B = window._mfxBridge;
                if (B && B.log) B.log('script started, url=' + location.href);

                function findEmbedId(attempts) {
                    var pb = document.querySelector('#zpub-wrap .play-btn')
                        || document.querySelector('.embed-play .play-btn')
                        || document.querySelector('.play-btn:not(.list-media .play-btn)');
                    if (!pb) {
                        if (attempts < 20) {
                            setTimeout(function(){ findEmbedId(attempts+1); }, 500);
                        } else if (B && B.log) {
                            B.log('no .play-btn after 10s');
                        }
                        return;
                    }
                    var embedId = pb.getAttribute('data-embed');
                    if (!embedId) {
                        if (B && B.log) B.log('play-btn has no data-embed attr');
                        return;
                    }
                    if (B && B.log) B.log('data-embed=' + embedId + ', POSTing /ajax/embed');

                    var xhr = new XMLHttpRequest();
                    xhr.open('POST', '/ajax/embed', true);
                    xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
                    xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
                    xhr.onload = function() {
                        if (xhr.status !== 200) {
                            if (B && B.log) B.log('xhr status=' + xhr.status);
                            if (B && B.onUnsupported) B.onUnsupported('xhr-error');
                            return;
                        }
                        var resp = xhr.responseText || '';

                        // FAST PATH xtremestream : pattern index.php → xs1.php
                        var xtreme = resp.match(/src=["']([^"']*xtremestream[^"']*\/index\.php[^"']*)["']/);
                        if (xtreme) {
                            var m3u8 = xtreme[1].replace('/index.php', '/xs1.php');
                            if (B && B.onM3u8) B.onM3u8(m3u8);
                            return;
                        }

                        // Autres providers (emmmmbed, etc.) — bloqués par CF Turnstile
                        // dans WebView (about:srcdoc + CSP nonce + WebGPU absent +
                        // Private Access Token absent). Pas faisable sans solveur
                        // captcha externe. On échoue fast pour que ExoPlayer puisse
                        // basculer rapidement vers un autre server.
                        var anyIframe = resp.match(/src=["']([^"']+)["']/);
                        var providerHost = 'unknown';
                        if (anyIframe) {
                            try { providerHost = new URL(anyIframe[1]).hostname; } catch(e) {}
                        }
                        if (B && B.onUnsupported) B.onUnsupported(providerHost);
                    };
                    xhr.onerror = function(){
                        if (B && B.log) B.log('xhr network error');
                        if (B && B.onUnsupported) B.onUnsupported('xhr-network');
                    };
                    xhr.send('id=' + encodeURIComponent(embedId) + '&self=' + encodeURIComponent(embedId));
                }

                findEmbedId(0);
            })();
        """
    }
}
