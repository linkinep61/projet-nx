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

    /** 2026-08-22 : Referer réellement employé par la WebView pour la requête
     *  m3u8 interceptée. La même façade (cartelive/bolaloca/embedme) sert
     *  PLUSIEURS CDN selon le 1er segment de l'URL — /player/2/ passe par
     *  hoca8, /player/1/ par barecrop, /player/4/ par instreams. Renvoyer un
     *  Referer hoca8 figé faisait refuser les deux autres. Null = non capté
     *  (chemin JS bridge) → on retombe sur l'ancien comportement. */
    @Volatile private var dernierReferer: String? = null

    /** 2026-08-22 : URL de la page d'embed réellement chargée dans l'iframe
     *  (hoca8/footy.php, barecrop/embed, lockpop/embed, instream/hlsspanich).
     *  Quand le m3u8 est capté par le PONT JS et non par l'interception réseau,
     *  aucun en-tête Referer n'est disponible — on retombait alors sur hoca8.com
     *  et le CDN répondait 403. C'est cette page-là que le navigateur enverrait
     *  comme Referer, donc c'est elle qu'on rejoue. */
    @Volatile private var dernierEmbedUrl: String? = null
    /** 2026-09-05 : page (location.href) de la frame qui a réellement demandé le m3u8,
     *  transmise par le pont JS. C'est LE Referer que le CDN attend. */
    @Volatile private var pageCapture: String? = null

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video {
        dernierReferer = null
        dernierEmbedUrl = null
        pageCapture = null
        val streamUrl = extractByIntercepting(link)
            ?: throw Exception("Hoca8: Could not capture stream URL from $link")
        val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
        // 2026-08-22 : Referer/Origin dérivés de la requête réellement
        //   interceptée (cf. dernierReferer). Repli sur hoca8.com si rien n'a
        //   été capté, pour garder EXACTEMENT le comportement d'avant.
        //   Priorité : la page d'embed chargée (valable pour les DEUX chemins de
        //   capture, pont JS compris) > le Referer de la requête interceptée >
        //   hoca8 en dernier recours.
        // 2026-09-05 : avec la règle générique d'injection (toute iframe), dernierEmbedUrl
        //   pouvait être la DERNIÈRE iframe injectée — une pub (hmtraff, adsco.re…) — et le
        //   CDN répondait 403 aux segments. Priorité désormais à la page qui a VRAIMENT
        //   demandé le m3u8 (pont JS), puis au Referer de la requête interceptée, puis à
        //   la dernière page d'embed CONNUE, puis hoca8.
        val referer = pageCapture?.takeIf { it.startsWith("http") }
            ?: dernierReferer?.takeIf { it.isNotBlank() }
            ?: dernierEmbedUrl?.takeIf { it.isNotBlank() }
            ?: "https://hoca8.com/"
        val origine = runCatching {
            java.net.URL(referer).let { u -> "${u.protocol}://${u.host}" }
        }.getOrDefault("https://hoca8.com")
        return Video(
            source = streamUrl,
            headers = mapOf(
                "Referer" to referer,
                "Origin" to origine,
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
                        fun onM3u8Found(url: String, page: String) {
                            if (page.startsWith("http")) pageCapture = page
                            android.util.Log.d("Hoca8Extractor", "JS captured m3u8 (page $page)")
                            onM3u8Found(url)
                        }

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
                            // 2026-08-22 : MÊME injection pour les autres hébergeurs
                            //   servis par la MÊME façade cartelive/bolaloca/embedme :
                            //   /player/1/ → barecrop.net, /player/3/ → lockpop.net,
                            //   /player/4/ → instream.click. Sans elle, HLS.js y récupère
                            //   le m3u8 en mémoire et shouldInterceptRequest ne voit
                            //   jamais passer la playlist → extraction en échec, écran
                            //   noir. Diagnostic du 22/08 : l'iframe barecrop se chargeait
                            //   bien, mais aucun "HLS PLAYLIST CAPTURED" ne suivait.
                            // 2026-09-05 (user : « Multi Live ne marche plus ») : la façade
                            //   /player/1/ ne pointe plus vers barecrop.net mais vers
                            //   cuttingfame.net/embed/<id> — hôte inconnu de cette liste, donc
                            //   pas d'injection, HLS.js garde le m3u8 en mémoire, écran noir
                            //   (le site, lui, joue). La liste blanche par nom d'hôte casse à
                            //   CHAQUE changement d'hébergeur ; on la garde, mais on y ajoute
                            //   une règle GÉNÉRIQUE : tout document HTML chargé dans une
                            //   SOUS-FRAME (Accept text/html, pas la frame principale), hors
                            //   pubs (BLOCKED_HOSTS, déjà écartées plus haut) et hors façade
                            //   cartelive/bolaloca/embedme, est une page de lecteur → hook.
                            //   Le hook ne fait que surveiller XHR/fetch pour repérer un
                            //   .m3u8 : inoffensif dans une iframe qui n'en charge pas.
                            val accept = request.requestHeaders?.get("Accept").orEmpty()
                            val estFacade = hostLower.contains("cartelive") ||
                                hostLower.contains("bolaloca") || hostLower.contains("embedme")
                            val estIframeHtml = !request.isForMainFrame &&
                                accept.contains("text/html") && !estFacade &&
                                !path.endsWith(".js") && !path.endsWith(".json")
                            val estPageEmbed =
                                (hostLower.contains("hoca8.com") && path.contains("/footy.php")) ||
                                (hostLower.contains("barecrop.net") && path.contains("/embed/")) ||
                                (hostLower.contains("cuttingfame.net") && path.contains("/embed/")) ||
                                (hostLower.contains("lockpop.net") && path.contains("/embed/")) ||
                                (hostLower.contains("instream.click") &&
                                    (path.contains("hlsspanich.php") || path.contains("player.php"))) ||
                                estIframeHtml
                            if (estIframeHtml) android.util.Log.d("Hoca8Extractor", "iframe lecteur (règle générique) : $reqUrl")
                            if (estPageEmbed && !estIframeHtml) dernierEmbedUrl = reqUrl   // hôtes connus seulement
                            if (estPageEmbed && request.method?.uppercase() == "GET") {
                                try {
                                    val client = okhttp3.OkHttpClient.Builder()
                                        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                        .followRedirects(true)
                                        .build()
                                    val req = okhttp3.Request.Builder()
                                        .url(reqUrl)
                                        // hoca8 attend le referer historique ; les
                                        //   autres hébergeurs attendent la page de
                                        //   façade réellement chargée (sinon 403).
                                        .header(
                                            "Referer",
                                            if (hostLower.contains("hoca8.com"))
                                                "https://bolaloca.my/" else url
                                        )
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
                                dernierReferer = request.requestHeaders?.get("Referer")
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
                                    Hoca8Bridge.onM3u8Found(u, String(location.href));
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
                            Hoca8Bridge.onM3u8Found(u, String(location.href));
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
