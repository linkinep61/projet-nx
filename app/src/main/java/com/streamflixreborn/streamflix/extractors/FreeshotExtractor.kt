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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Freeshot / popcdn — extracteur pour les chaînes TV freeshot.live.
 *
 * Architecture initiale :
 *   freeshot.live/live-tv/<slug>/<id>  ─→  iframe embed/<CHANNEL>.php
 *                                            └─→ iframe popcdn.day/go.php?stream=<CHANNEL>
 *                                                  └─→ JS charge le m3u8 dynamiquement
 *
 * Problème observé (2026-05-15) : freeshot.live a une protection anti-bot très
 * agressive qui :
 *  - rejette OkHttp Android avec SSLHandshakeException: connection closed
 *  - rejette même le WebView Chromium avec ERR_CONNECTION_CLOSED (-6)
 *  - les DNS lookups peuvent échouer sur certains réseaux (Chromecast Sabrina)
 *  - tous les TLS non-Chrome-full-browser sont coupés au handshake
 *
 * Diagnostic confirmé par :
 *  - DebugFreeshotActivity tests sur emulator (ERR_CONNECTION_CLOSED systematique)
 *  - DebugFreeshotActivity sur Chromecast (ERR_NAME_NOT_RESOLVED)
 *  - PowerShell Invoke-WebRequest depuis Windows (SSL connection could not be established)
 *
 * Conclusion : freeshot.live est INACCESSIBLE depuis l'app StreamFlix. Le seul
 * client qui passe est un VRAI navigateur Chrome desktop (cf. scraping initial
 * via Chrome MCP + allorigins.win proxy).
 *
 * Workflow actuel (best-effort) :
 *  1. OkHttp HTTP GET → habituellement rejeté en TLS handshake
 *  2. Si null, WebView fallback → habituellement page vide (CONNECTION_CLOSED)
 *  3. Timeout 30s → retourne null à l'appelant
 *
 * L'extracteur est donc présent mais ne capture jamais de m3u8 en pratique.
 * Les chaînes freeshot dans LiveTvHubProvider qui ont un fallback Vavoo/WiTV/
 * OLA/Vegeta restent utilisables via ces providers. Celles UNIQUES à freeshot
 * (BFM régionales, DAZN, Tébéo, etc.) ne joueront pas.
 */
open class FreeshotExtractor : Extractor() {
    override val name = "Freeshot"
    override val mainUrl = "https://www.freeshot.live"
    override val aliasUrls = listOf(
        "https://freeshot.live",
        "https://popcdn.day",
    )

    override val cacheTtlMs: Long = 0L  // live stream, pas de cache

    private val context = StreamFlixApp.instance.applicationContext

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    // OkHttp client réutilisable. Android natif passe le TLS check de freeshot.live
    // sans problème (vs PowerShell/curl Windows qui échouent JA3).
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override suspend fun extract(link: String): Video {
        val streamUrl = extractByIntercepting(link)
            ?: throw Exception("Freeshot: Could not capture stream URL from $link")
        val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
        return Video(
            source = streamUrl,
            headers = mapOf(
                "Referer" to "https://freeshot.live/",
                "Origin" to "https://freeshot.live",
                "User-Agent" to ANDROID_CHROME_UA,
            ),
            type = if (isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
        )
    }

    /** Resolve la page freeshot.live/live-tv/... vers l'iframe embed PHP URL.
     *  Retourne null si pas trouvé (chaîne supprimée, format changé, etc.). */
    private suspend fun resolveEmbedUrl(pageUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", ANDROID_CHROME_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                .header("Cache-Control", "no-cache")
                .build()
            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            val html = resp.use { it.body?.string() ?: "" }
            android.util.Log.d("FreeshotExtractor", "resolveEmbedUrl: HTTP=$code len=${html.length}")
            // Pattern 1: <iframe ... src="https://freeshot.live/embed/<CHANNEL>.php">
            val rx1 = Regex("""<iframe[^>]+src=["'](https?://(?:www\.)?freeshot\.live/embed/[^"']+\.php)["']""", RegexOption.IGNORE_CASE)
            val match1 = rx1.find(html)?.groupValues?.get(1)
            if (match1 != null) {
                android.util.Log.d("FreeshotExtractor", "resolveEmbedUrl: matched pattern 1 -> $match1")
                return@runCatching match1
            }
            // Pattern 2: src protocol-relative (//freeshot.live/embed/...)
            val rx2 = Regex("""<iframe[^>]+src=["'](?://)?((?:www\.)?freeshot\.live/embed/[^"']+\.php)["']""", RegexOption.IGNORE_CASE)
            val match2 = rx2.find(html)?.groupValues?.get(1)
            if (match2 != null) {
                val full = "https://$match2"
                android.util.Log.d("FreeshotExtractor", "resolveEmbedUrl: matched pattern 2 -> $full")
                return@runCatching full
            }
            // Pattern 3: just /embed/CHANNEL.php (relative path)
            val rx3 = Regex("""<iframe[^>]+src=["']((?:/)?embed/[^"']+\.php)["']""", RegexOption.IGNORE_CASE)
            val match3 = rx3.find(html)?.groupValues?.get(1)
            if (match3 != null) {
                val full = "https://www.freeshot.live/" + match3.trimStart('/')
                android.util.Log.d("FreeshotExtractor", "resolveEmbedUrl: matched pattern 3 -> $full")
                return@runCatching full
            }
            // Pattern 4: dump une snippet pour debug
            val ifrIdx = html.indexOf("iframe", ignoreCase = true)
            if (ifrIdx >= 0) {
                val snippet = html.substring(ifrIdx, minOf(ifrIdx + 300, html.length))
                android.util.Log.w("FreeshotExtractor", "resolveEmbedUrl: no pattern matched. iframe snippet=$snippet")
            } else {
                android.util.Log.w("FreeshotExtractor", "resolveEmbedUrl: no iframe at all in html. start=${html.take(400)}")
            }
            null
        }.onFailure {
            android.util.Log.e("FreeshotExtractor", "resolveEmbedUrl threw: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractByIntercepting(link: String): String? {
        // Étape 1 : résoudre l'iframe embed via OkHttp (évite WebView crash sur wrapper)
        val embedUrl = resolveEmbedUrl(link)
        val targetUrl = embedUrl ?: link  // fallback à la page wrapper si embed introuvable
        android.util.Log.d("FreeshotExtractor", "Resolved embed: $embedUrl (input=$link)")

        // Étape 2 : WebView sur l'embed → intercept m3u8.
        // 2026-05-15 : timeout réduit de 30s → 10s. ERR_CONNECTION_CLOSED
        // se produit dans les 3-5s, donc 10s est plenty pour fail-fast. Avec
        // 30s, le MiniPlayer bloque inutilement les serveurs de fallback.
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(10_000L) {
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

                        override fun onConsoleMessage(
                            consoleMessage: android.webkit.ConsoleMessage?
                        ): Boolean {
                            val msg = consoleMessage?.message() ?: return false
                            val m = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(msg)
                            if (m != null) {
                                android.util.Log.d("FreeshotExtractor", "M3U8 from console: ${m.value}")
                                resolve(m.value)
                                return true
                            }
                            return false
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val host = request?.url?.host ?: return true
                            // Block tout ce qui n'est pas un host légitime stream/player
                            return BLOCKED_HOSTS.any { host.contains(it) }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host ?: ""

                            // Block aggressivement les ads/trackers AVANT match.
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                android.util.Log.v("FreeshotExtractor", "BLOCKED ad: $host")
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            // Intercept m3u8 SUR TOUS LES HOSTS (le CDN final est
                            // un host inconnu — pas dans le whitelist). On capture
                            // PUIS on laisse la requête passer (return null) pour
                            // ne pas casser la lecture si le test échoue ailleurs.
                            val path = request.url?.path?.lowercase() ?: ""
                            val isHls = path.endsWith(".m3u8") ||
                                path.contains("master.m3u8") ||
                                path.contains("playlist.m3u8") ||
                                path.contains("/hls/") ||
                                reqUrl.contains(".m3u8", ignoreCase = true)
                            if (isHls) {
                                android.util.Log.d("FreeshotExtractor", "HLS INTERCEPTED: $reqUrl")
                                resolve(reqUrl)
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            // Log key sub-resource requests for debugging the chain.
                            // Embed iframe = .php / popcdn = popcdn.* / m3u8 = video.
                            if (path.endsWith(".php") || host.contains("popcdn") ||
                                host.contains("freeshot") || path.contains("hls") ||
                                path.contains("stream") || path.contains("go.php")) {
                                android.util.Log.d("FreeshotExtractor", "REQ: $reqUrl")
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return
                            android.util.Log.d("FreeshotExtractor", "onPageFinished: $finishedUrl")
                            // Trigger autoplay via synthetic click — certains players
                            // attendent une interaction même avec mediaPlayback...=false
                            view.evaluateJavascript("""
                                (function(){
                                    try{
                                        document.body && document.body.click && document.body.click();
                                        document.querySelectorAll('video').forEach(v=>{try{v.muted=true;v.play();}catch(e){}});
                                    }catch(e){}
                                })();
                            """.trimIndent(), null)

                            view.postDelayed({
                                if (!resolved) {
                                    android.util.Log.w("FreeshotExtractor", "Timeout — no stream captured")
                                    resolve(null)
                                }
                            }, 6_000L)  // 2026-05-15 : 20s → 6s pour fail-fast
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            val url = request?.url?.toString() ?: "?"
                            val code = error?.errorCode ?: -1
                            val desc = error?.description ?: ""
                            android.util.Log.w("FreeshotExtractor", "onReceivedError [$code] $desc — $url")
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?
                        ) {
                            val url = request?.url?.toString() ?: "?"
                            val code = errorResponse?.statusCode ?: -1
                            android.util.Log.w("FreeshotExtractor", "onReceivedHttpError [$code] — $url")
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            android.util.Log.w("FreeshotExtractor", "onReceivedSslError ${error?.primaryError} — ${error?.url}")
                            handler?.proceed()  // Ignore SSL errors (matches MIXED_CONTENT_ALWAYS_ALLOW)
                        }
                    }

                    webView.loadUrl(targetUrl, mapOf("Referer" to "https://freeshot.live/"))

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
    }

    companion object {
        /** Whitelist : seuls ces hosts sont permis dans le WebView. Bloque tout
         *  le reste (ads, trackers, analytics, popunders) — ça évite les SSL
         *  handshake fails qui crashaient le renderer Chromium. */
        private val ALLOWED_HOSTS = listOf(
            "freeshot.live",
            "popcdn.day",
            "popcdn.net",
            "popcdn.dev",
            // CDN m3u8 communs côté freeshot
            "cloudfront.net",
            "akamaized.net",
            "cdn.jsdelivr.net",  // hls.js
            "fastly.net",
            "cdn77.org",
            "wasabisys.com",
            "googlevideo.com",
            "fluxcdn.com",
            "edgecastcdn.net",
        )

        private val BLOCKED_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice",
            "popads", "popunder", "popcash", "propellerads",
            "exoclick", "juicyads", "trafficjunky",
            "googletagmanager", "google-analytics",
            "mc.yandex.ru", "yandex.ru", "metrica.yandex",
            "alwingulla.com", "tag.min.js",
            "histatsv.com", "histats.com",
            "adsterra.com",
            "cloudflareinsights",
            // 2026-05-15 : élargi pour bloquer plus de réseaux pub
            // détectés sur le wrapper freeshot.live.
            "outbrain.com", "taboola.com", "criteo.com",
            "amazon-adsystem.com", "rubiconproject.com",
            "openx.net", "adnxs.com", "pubmatic.com",
            "adsymptotic.com", "adform.net",
            "scorecardresearch.com", "quantserve.com",
            "facebook.com/tr", "facebook.net",
            "snigelweb.com", "smartadserver.com",
            "tpc.googlesyndication", "googleadservices",
            "adskeeper.com", "mgid.com",
            "revcontent.com", "zergnet.com",
            "bidswitch.net", "casalemedia.com",
            "yieldmo.com", "pubguru.com",
            "adsafeprotected.com", "moatads.com",
            "vidible.tv", "spotxchange.com",
            "everesttech.net", "rlcdn.com",
            "agkn.com", "demdex.net", "krxd.net",
        )
    }
}
