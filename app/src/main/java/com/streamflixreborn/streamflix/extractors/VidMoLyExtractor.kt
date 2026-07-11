package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Message
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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
        val target = if (link.contains("vidmoly"))
            link.replace(Regex("vidmoly\\.(to|me|net)"), "vidmoly.biz")
        else link

        // 2026-07-06 v3 : FAST-PATH OkHttp — le m3u8 est dans le HTML source brut
        //   (script inline `player.setup({sources:[{file:"...m3u8..."}]})`), PAS besoin
        //   de WebView si CF ne challenge pas. Sur Chromecast le WebView headless crash/
        //   timeout en <300ms (coroutine cancelled + main thread busy). L'OkHttp avec
        //   DoH et UA Chrome passe dans ~90% des cas. Fallback WebView seulement si CF.
        val fastResult = try { extractViaOkHttp(target) } catch (e: Exception) {
            Log.d("VidMoLyExtractor", "OkHttp fast-path failed: ${e.message?.take(100)}")
            null
        }
        if (fastResult != null) {
            Log.d("VidMoLyExtractor", "m3u8 FOUND via OkHttp fast-path (len=${fastResult.length})")
            return buildVideo(fastResult, target)
        }

        // Fallback WebView (CF challenge, pub redirect, etc.)
        Log.d("VidMoLyExtractor", "OkHttp failed → fallback WebView pour $target")
        val hlsUrl = try { extractByIntercepting(target, timeoutMs = 25_000L) }
            catch (e: Exception) {
                throw Exception("VidMoLy: extraction failed for $link (${e.message?.take(200)})")
            }
        if (hlsUrl != null) return buildVideo(hlsUrl, target)

        throw Exception("VidMoLy: Could not find HLS source in: $link")
    }

    /**
     * 2026-07-06 : Fast-path OkHttp — GET la page embed, regex le m3u8 dans le HTML.
     * Marche quand CF ne challenge pas (= majorité des cas). ~500ms au lieu de 25s.
     * Retourne null si CF (403/503), redirect pub, ou m3u8 introuvable.
     */
    private suspend fun extractViaOkHttp(url: String): String? = withContext(Dispatchers.IO) {
        val host = Uri.parse(url).host ?: "vidmoly.biz"
        val client = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)   // on veut détecter les redirections pub
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ANDROID_CHROME_UA)
            .header("Referer", "https://$host/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.5")
            .get()
            .build()
        val resp = client.newCall(req).execute()
        val code = resp.code
        Log.d("VidMoLyExtractor", "OkHttp GET $url → $code")

        // CF challenge ou redirection pub → on laisse le WebView gérer
        if (code == 403 || code == 503 || code == 302 || code == 301) {
            resp.close()
            return@withContext null
        }
        if (code != 200) {
            resp.close()
            return@withContext null
        }

        val html = resp.body?.string() ?: return@withContext null
        Log.d("VidMoLyExtractor", "OkHttp HTML received: ${html.length} chars")

        // CF challenge dans le body ?
        if (html.contains("challenges.cloudflare.com") ||
            html.contains("Just a moment") ||
            html.contains("cf-turnstile")) {
            Log.d("VidMoLyExtractor", "OkHttp: CF challenge detected in body → fallback WebView")
            return@withContext null
        }

        // Regex m3u8 — mêmes patterns que EXTRACT_M3U8_JS
        val m3u8Regex = listOf(
            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]*\.m3u8[^'"]*)['"]"""),
            Regex("""file\s*:\s*['"]([^'"]*\.m3u8[^'"]*)['"]"""),
            Regex("""['"]([^'"]*\.m3u8[^'"]*)['"]"""),
        )
        for (regex in m3u8Regex) {
            val match = regex.find(html)
            if (match != null) {
                val m3u8 = match.groupValues[1]
                Log.d("VidMoLyExtractor", "OkHttp regex match: ${m3u8.take(80)}")
                return@withContext m3u8
            }
        }

        Log.d("VidMoLyExtractor", "OkHttp: no m3u8 found in HTML (${html.length} chars)")
        null
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
                                android.util.Log.d("VidMoLyExtractor", "normal page → forcePlay + pollForM3u8")
                                // 2026-07-06 : Force JWPlayer à démarrer le m3u8.
                                view.evaluateJavascript(FORCE_PLAY_JS, null)
                                // 2026-07-06 v2 : Polling agressif (40×500ms=20s) qui
                                // combine API JWPlayer + regex HTML. Sur Chromecast,
                                // JWPlayer met >3s à s'initialiser dans le WebView
                                // headless → les 3 retries de tryExtractWithRetry
                                // rataient systématiquement. Le poll vérifie aussi
                                // l'API jwplayer().getConfig().sources qui est remplie
                                // dès que .setup() tourne, même sans autoplay.
                                pollForM3u8(view, 0, ::resolve)
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

    /**
     * 2026-07-06 v2 : Polling robuste pour extraire le m3u8.
     * Combine l'API JWPlayer (getConfig().sources) et le scan regex HTML.
     * Toutes les 500ms pendant max 20s (40 tentatives).
     * Force play toutes les 2s pour relancer JWPlayer si idle.
     * Le shouldInterceptRequest tourne en parallèle comme filet de sécurité.
     */
    private fun pollForM3u8(view: WebView, attempt: Int, resolve: (String?) -> Unit) {
        if (attempt > 40) return // 40 × 500ms = 20s max
        val delay = if (attempt == 0) 0L else 500L
        view.postDelayed({
            // Force play toutes les 4 tentatives (~2s) pour relancer JWPlayer
            if (attempt > 0 && attempt % 4 == 0) {
                view.evaluateJavascript(FORCE_PLAY_JS, null)
            }
            // Diagnostic aux tentatives 0, 5, 10, 20, 40 pour comprendre l'état
            if (attempt == 0 || attempt == 5 || attempt == 10 || attempt == 20 || attempt == 40) {
                view.evaluateJavascript(DIAG_JS) { diag ->
                    android.util.Log.d("VidMoLyExtractor", "poll #$attempt DIAG: ${diag?.take(300)}")
                }
            }
            // Plan B : au poll #2 (~1s), lancer le fetch XHR du HTML source brut.
            //   Pas au #0 (la page vient de finir de charger, laissons les scripts s'exécuter).
            //   Le résultat est stocké dans window.__vidmoly_raw_html pour les polls suivants.
            if (attempt == 2) {
                view.evaluateJavascript(FETCH_RAW_HTML_JS) { fetchResult ->
                    android.util.Log.d("VidMoLyExtractor", "XHR raw HTML: ${fetchResult?.take(80)}")
                }
            }
            view.evaluateJavascript(EXTRACT_M3U8_JS) { r ->
                val extracted = r?.trim()?.removeSurrounding("\"")
                    ?.takeIf { it != "null" && it.contains(".m3u8") }
                if (extracted != null) {
                    android.util.Log.d("VidMoLyExtractor", "poll #$attempt → m3u8 FOUND (len=${extracted.length})")
                    resolve(extracted)
                } else {
                    pollForM3u8(view, attempt + 1, resolve)
                }
            }
        }, delay)
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
         * 2026-07-06 v2 : Force JWPlayer à démarrer la lecture.
         * Appel API + click bouton play + video.play() muted.
         */
        private const val FORCE_PLAY_JS =
            "(function(){try{" +
            "if(typeof jwplayer==='function'){var p=jwplayer();if(p&&p.play)p.play();}" +
            "var b=document.querySelector('.jw-icon-display,.jw-display-icon-container,.play-btn,.vjs-big-play-button,button[aria-label*=\"Play\"]');" +
            "if(b)b.click();" +
            "var v=document.querySelector('video');if(v){v.muted=true;v.play();}" +
            "}catch(e){}})();"

        /**
         * Diagnostic JS : retourne un JSON compact décrivant l'état de la page
         * pour comprendre pourquoi l'extraction échoue sur Chromecast.
         */
        private const val DIAG_JS =
            "(function(){try{var d={};" +
            "d.jw=(typeof jwplayer);" +                           // "function" ou "undefined"
            "d.scripts=document.querySelectorAll('script').length;" + // combien de scripts
            "var m3u8Count=0;var m3u8Script=-1;" +
            "var ss=document.querySelectorAll('script');" +
            "for(var i=0;i<ss.length;i++){" +
            "if(ss[i].textContent&&ss[i].textContent.indexOf('m3u8')>-1){m3u8Count++;m3u8Script=i;}}" +
            "d.m3u8Scripts=m3u8Count;" +                          // combien contiennent 'm3u8'
            "d.m3u8Idx=m3u8Script;" +                             // index du dernier
            "d.hasSetup=false;" +
            "if(m3u8Script>=0){d.hasSetup=ss[m3u8Script].textContent.indexOf('setup')>-1;}" +
            "d.video=document.querySelectorAll('video').length;" + // éléments <video>
            "d.title=document.title.substring(0,40);" +
            "d.url=location.hostname;" +
            "if(typeof jwplayer==='function'){" +
            "var p=jwplayer();" +
            "d.jwState=p&&p.getState?p.getState():'noGetState';" +
            "d.jwHasConfig=!!(p&&p.getConfig);" +
            "if(p&&p.getConfig){var c=p.getConfig();d.jwSources=c&&c.sources?c.sources.length:0;}" +
            "}else{d.jwState='N/A';}" +
            "return JSON.stringify(d);" +
            "}catch(e){return '{\"error\":\"'+e.message+'\"}';}})();"

        /**
         * 2026-07-06 v2 : Extraction m3u8 combinant l'API JWPlayer ET le scan
         * regex des <script> tags. L'API JWPlayer est prioritaire car elle
         * fonctionne même quand le m3u8 est chargé dynamiquement (pas dans le
         * HTML source). Sur Chromecast headless, c'est souvent le seul chemin
         * qui marche : le force-play ne déclenche pas de requête réseau, mais
         * setup() a déjà mis la source dans la config.
         */
        private const val EXTRACT_M3U8_JS =
            "(function(){try{" +
            // Prio 1 : API JWPlayer getConfig().sources
            "if(typeof jwplayer==='function'){" +
            "var p=jwplayer();" +
            "if(p&&p.getConfig){var cfg=p.getConfig();" +
            "if(cfg&&cfg.sources){for(var i=0;i<cfg.sources.length;i++){" +
            "var f=cfg.sources[i].file||'';" +
            "if(f.indexOf('.m3u8')>-1)return f;}}}" +
            // Prio 2 : getPlaylistItem().file / .sources
            "if(p&&p.getPlaylistItem){var it=p.getPlaylistItem();" +
            "if(it){if(it.file&&it.file.indexOf('.m3u8')>-1)return it.file;" +
            "if(it.sources){for(var j=0;j<it.sources.length;j++){" +
            "var g=it.sources[j].file||'';" +
            "if(g.indexOf('.m3u8')>-1)return g;}}}}" +
            "}" +
            // Prio 3 : scan regex des <script> tags (textContent)
            "var scripts=document.querySelectorAll('script');" +
            "for(var i=0;i<scripts.length;i++){" +
            "var text=scripts[i].textContent;" +
            "if(text&&text.indexOf('m3u8')>-1){" +
            "var m=text.match(/sources\\s*:\\s*\\[\\s*\\{\\s*file\\s*:\\s*['\"]([^'\"]*\\.m3u8[^'\"]*)['\"]/);" +
            "if(m)return m[1];" +
            "m=text.match(/file\\s*:\\s*['\"]([^'\"]*\\.m3u8[^'\"]*)['\"]/);" +
            "if(m)return m[1];" +
            "m=text.match(/['\"]([^'\"]*\\.m3u8[^'\"]*)['\"]/);" +
            "if(m)return m[1];}}" +
            // Prio 4 : innerHTML complet (couvre les scripts injectés dynamiquement)
            "var html=document.documentElement.innerHTML;" +
            "var m=html.match(/['\"]([^'\"]*\\.m3u8[^'\"]*)['\"]/);" +
            "if(m)return m[1];" +
            // Prio 5 : XHR synchrone de la page elle-même (bypass WebView rendering)
            //   Sur Chromecast headless, le DOM peut ne pas contenir le m3u8 si les
            //   scripts ne s'exécutent pas (CDN JWPlayer bloqué/lent). Un fetch XHR
            //   de la même URL retourne le HTML source brut (avec le m3u8 en clair).
            "if(window.__vidmoly_raw_html){" +
            "var rr=window.__vidmoly_raw_html.match(/sources\\s*:\\s*\\[\\s*\\{\\s*file\\s*:\\s*['\"]([^'\"]*\\.m3u8[^'\"]*)['\"]/);" +
            "if(rr)return rr[1];" +
            "rr=window.__vidmoly_raw_html.match(/file\\s*:\\s*['\"]([^'\"]*\\.m3u8[^'\"]*)['\"]/);" +
            "if(rr)return rr[1];}" +
            "}catch(e){return 'ERROR:'+e.message;}" +
            "return null;})();"

        /**
         * XHR synchrone qui récupère le HTML source brut de la page courante
         * et le stocke dans window.__vidmoly_raw_html. Exécuté une seule fois
         * au début du polling (attempt 0) comme plan B pour l'extraction.
         */
        private const val FETCH_RAW_HTML_JS =
            "(function(){try{" +
            "if(window.__vidmoly_raw_html)return 'already_fetched';" +
            "var x=new XMLHttpRequest();" +
            "x.open('GET',location.href,false);" +    // sync = bloquant mais petit
            "x.send();" +
            "if(x.status===200){" +
            "window.__vidmoly_raw_html=x.responseText;" +
            "return 'fetched_'+x.responseText.length;}" +
            "return 'status_'+x.status;" +
            "}catch(e){return 'error_'+e.message;}})()"
    }
}
