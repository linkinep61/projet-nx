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
 * Player4me — extracteur WebView-based pour les embeds custom 4meplayer.com.
 *
 * 4meplayer est une SPA Vite custom dédiée à dessinanime.cc (et possiblement
 * d'autres sites) :
 *   - URL embed : `https://dessinanime.4meplayer.com/#<slug>`
 *   - JS bundle : `/assets/index-*.js` (~880 KB)
 *   - API interne : `/api/v1/player?t=<token>` → résolution du stream
 *   - Anti-headless explicite (string "Opss! Headless Browser is not allowed"
 *     dans le bundle) → on utilise un WebView Android (Chromium engine, pas
 *     détecté comme headless car settings UA + viewport réels)
 *
 * Stratégie : WebView avec UA Chrome Android réel + Referer dessinanime.cc,
 * intercepte les requêtes pour capturer le m3u8/mp4 final que le player fetch.
 *
 * Note : 4meplayer wrappe parfois un autre host (uqload/sendvid). Dans ce cas
 * le WebView va naviguer vers l'iframe interne, et `shouldInterceptRequest`
 * capture le stream final.
 */
open class Player4meExtractor : Extractor() {
    override val name = "Player4me"
    override val mainUrl = "https://4meplayer.com"
    override val aliasUrls = listOf(
        "https://dessinanime.4meplayer.com",
        "https://player.4meplayer.com",
    )

    // 2026-05-14 (user "repare les extracteurs") : pas de cache. La capture
    // best-effort peut échouer ou attraper la mauvaise URL — si l'extraction
    // réussit la 1ère fois on évite de cacher une URL douteuse pour 10 min.
    override val cacheTtlMs: Long = 0L

    private val context = StreamFlixApp.instance.applicationContext

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video {
        // 2026-05-23 : refonte extracteur — on tente d'abord de capturer le m3u8/mp4
        // via WebView headless (interception réseau + console CORS). Si ça échoue,
        // fallback en webViewUrl + needsWebViewClick (mode player WebView).
        val streamUrl = extractByIntercepting(link)
        if (!streamUrl.isNullOrBlank()) {
            android.util.Log.d("Player4meExtractor", "Stream extrait : $streamUrl")
            return Video(
                source = streamUrl,
                subtitles = listOf(),
            )
        }
        // Fallback : mode player WebView (curseur virtuel, pilotable D-pad)
        android.util.Log.w("Player4meExtractor", "Extraction échouée, fallback webViewUrl")
        return Video(
            source = link,
            webViewUrl = link,
            needsWebViewClick = true,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractByIntercepting(url: String): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(35_000L) {
                suspendCancellableCoroutine { cont ->
                    var resolved = false
                    var wv: WebView? = null

                    fun resolve(value: String?) {
                        if (!resolved && cont.isActive) {
                            resolved = true
                            // 2026-05-20 (fix fuite mémoire) : détruire la WebView sur
                            //   succès ET timeout, pas seulement à l'annulation. Sinon
                            //   chaque extraction Player4me laissait une WebView Chromium
                            //   (+ bundle JS ~880 Ko) en mémoire → OOM Chromecast.
                            //   On POSTe le destroy (resolve() est appelé depuis un
                            //   callback WebView : détruire en synchrone planterait).
                            val toDestroy = wv
                            wv = null
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try { toDestroy?.stopLoading(); toDestroy?.destroy() } catch (_: Throwable) {}
                            }
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
                    wv = webView

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean,
                            isUserGesture: Boolean, resultMsg: Message?
                        ): Boolean = false

                        // 2026-05-15 (BREAKTHROUGH) : le m3u8 final est bloqué par
                        // CORS donc shouldInterceptRequest ne le voit pas. Mais
                        // l'URL apparaît dans le console error "Access to
                        // XMLHttpRequest at 'https://...master.m3u8'". On parse
                        // l'URL depuis les messages console et c'est notre stream !
                        override fun onConsoleMessage(
                            consoleMessage: android.webkit.ConsoleMessage?
                        ): Boolean {
                            val msg = consoleMessage?.message() ?: return false
                            // Log TOUT pour debug
                            if (msg.startsWith("P4M_")) {
                                android.util.Log.d("Player4meExtractor", "CONSOLE: $msg")
                            }
                            // 2026-05-23 : fail-fast si vidéo morte
                            if (msg.startsWith("P4M_API_RESP: ")) {
                                val body = msg.removePrefix("P4M_API_RESP: ")
                                if (body.contains("not found") || body.contains("deleted") || body.contains("error")) {
                                    android.util.Log.w("Player4meExtractor", "Dead content detected: $body")
                                    resolve(null) // fail-fast, pas besoin d'attendre 22s
                                    return true
                                }
                            }
                            // 2026-05-23 : capturer P4M_STREAM: (émis par nos hooks fetch/XHR)
                            if (msg.startsWith("P4M_STREAM: ")) {
                                val url = msg.removePrefix("P4M_STREAM: ").trim()
                                if (url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                                    android.util.Log.d("Player4meExtractor", "STREAM captured via JS hook: $url")
                                    resolve(url)
                                    return true
                                }
                            }
                            // Pattern legacy : "Access to XMLHttpRequest at '<URL>'" ou autre
                            val m3u8Rx = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
                            val match = m3u8Rx.find(msg)
                            if (match != null) {
                                val url = match.value
                                android.util.Log.d("Player4meExtractor", "M3U8 captured from console: $url")
                                resolve(url)
                                return true
                            }
                            return false
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            // 2026-05-15 (user "le build était cassé, l'extracteur
                            // david [Player4me] ne marche pas") : spoof anti-headless
                            // AVANT que le JS du player ne tourne. Player4me check
                            // navigator.webdriver, navigator.plugins.length, etc.
                            // → on les overrides pour qu'ils passent les checks.
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    try {
                                        Object.defineProperty(navigator, 'webdriver', { get: () => false });
                                    } catch(e) {}
                                    try {
                                        Object.defineProperty(navigator, 'plugins', {
                                            get: () => [{name:'Chrome PDF'}, {name:'Chrome PDF Viewer'}, {name:'Native Client'}]
                                        });
                                    } catch(e) {}
                                    try {
                                        Object.defineProperty(navigator, 'languages', { get: () => ['fr-FR','fr','en-US','en'] });
                                    } catch(e) {}
                                    // Hide automation indicators
                                    try { delete navigator.__proto__.webdriver; } catch(e) {}
                                    try {
                                        window.chrome = window.chrome || { runtime: {} };
                                    } catch(e) {}
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val host = request?.url?.host ?: return false
                            // permissif : on bloque seulement les pubs, on laisse passer
                            // toute la chaine de redirection du player (sinon stream bloque)
                            return BLOCKED_HOSTS.any { host.contains(it) }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host ?: ""

                            // Block trackers (mais PAS cloudflareinsights — le player
                            // peut en dépendre pour s'initialiser correctement).
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            // 2026-05-15 (user "auto-test, repare extracteur David") :
                            // intercepte le JS bundle de 4meplayer et prepend le
                            // spoof anti-headless. Comme ça, AVANT que le JS du
                            // player ne tourne, navigator.webdriver=false etc. sont
                            // déjà overrides. C'est plus fiable que evaluateJavascript
                            // qui peut courir trop tard.
                            val path = request.url?.path?.lowercase() ?: ""
                            if (host.contains("4meplayer.com") && path.endsWith(".js") &&
                                path.contains("/assets/index")
                            ) {
                                try {
                                    val originalConn = java.net.URL(reqUrl).openConnection() as java.net.HttpURLConnection
                                    originalConn.setRequestProperty("User-Agent", ANDROID_CHROME_UA)
                                    originalConn.setRequestProperty("Referer", "https://dessinanime.cc/")
                                    val originalJs = originalConn.inputStream.bufferedReader().use { it.readText() }
                                    val spoofPrelude = """
                                        (function(){
                                            // === Anti-headless spoofing ===
                                            try{Object.defineProperty(navigator,'webdriver',{get:()=>undefined,configurable:true});}catch(e){}
                                            try{delete Object.getPrototypeOf(navigator).webdriver;}catch(e){}
                                            try{delete navigator.webdriver;}catch(e){}
                                            try{Object.defineProperty(navigator,'plugins',{get:()=>{const arr=[{name:'Chrome PDF Plugin',description:'Portable Document Format'},{name:'Chrome PDF Viewer',description:''},{name:'Native Client',description:''}];arr.item=i=>arr[i];arr.namedItem=n=>arr.find(p=>p.name===n);arr.refresh=()=>{};return arr;},configurable:true});}catch(e){}
                                            try{Object.defineProperty(navigator,'mimeTypes',{get:()=>{const arr=[{type:'application/pdf'},{type:'application/x-google-chrome-pdf'}];arr.item=i=>arr[i];arr.namedItem=n=>arr.find(m=>m.type===n);return arr;},configurable:true});}catch(e){}
                                            try{Object.defineProperty(navigator,'languages',{get:()=>['fr-FR','fr','en-US','en'],configurable:true});}catch(e){}
                                            try{Object.defineProperty(navigator,'language',{get:()=>'fr-FR',configurable:true});}catch(e){}
                                            try{Object.defineProperty(navigator,'platform',{get:()=>'Linux armv8l',configurable:true});}catch(e){}
                                            try{Object.defineProperty(navigator,'vendor',{get:()=>'Google Inc.',configurable:true});}catch(e){}
                                            try{Object.defineProperty(navigator,'maxTouchPoints',{get:()=>5,configurable:true});}catch(e){}
                                            try{Object.defineProperty(navigator,'hardwareConcurrency',{get:()=>8,configurable:true});}catch(e){}
                                            try{Object.defineProperty(navigator,'deviceMemory',{get:()=>4,configurable:true});}catch(e){}
                                            // window.chrome (commonly checked by anti-bots)
                                            try{
                                                window.chrome={
                                                    runtime:{},
                                                    loadTimes:function(){return{requestTime:Date.now()/1000-1,startLoadTime:Date.now()/1000-0.5,finishLoadTime:Date.now()/1000,firstPaintTime:Date.now()/1000,connectionInfo:'http/1.1'};},
                                                    csi:function(){return{startE:Date.now()-100,onloadT:Date.now(),pageT:100,tran:15};},
                                                    app:{isInstalled:false,InstallState:{DISABLED:'disabled',INSTALLED:'installed',NOT_INSTALLED:'not_installed'},RunningState:{CANNOT_RUN:'cannot_run',READY_TO_RUN:'ready_to_run',RUNNING:'running'}}
                                                };
                                            }catch(e){}
                                            // navigator.permissions.query — some checks expect 'default' for 'notifications'
                                            try{
                                                const origQuery=navigator.permissions&&navigator.permissions.query;
                                                if(navigator.permissions&&origQuery){
                                                    navigator.permissions.query=function(p){
                                                        if(p&&p.name==='notifications')return Promise.resolve({state:'default'});
                                                        return origQuery.call(navigator.permissions,p);
                                                    };
                                                }
                                            }catch(e){}
                                            // navigator.userActivation (player may check this)
                                            try{
                                                Object.defineProperty(navigator,'userActivation',{
                                                    get:()=>({isActive:true,hasBeenActive:true}),
                                                    configurable:true
                                                });
                                            }catch(e){}
                                            // window.outerWidth/Height (headless = 0)
                                            try{Object.defineProperty(window,'outerWidth',{get:()=>1080});}catch(e){}
                                            try{Object.defineProperty(window,'outerHeight',{get:()=>1920});}catch(e){}
                                            // Fake screen object (headless = inconsistent)
                                            // screen properties are read-only on standard browsers but we override
                                            // === Override player's anti-bot regex tests ===
                                            // Force all bot-pattern regex tests to return false on UA
                                            // by overriding RegExp.prototype.test for those patterns
                                            try{
                                                const origTest=RegExp.prototype.test;
                                                RegExp.prototype.test=function(s){
                                                    const src=this.source.toLowerCase();
                                                    const botMarkers=['webdriver','phantomjs','nightmare','casperjs','zombie','slimerjs','playwright','puppeteer','selenium','chromedriver','axios','postman','insomnia','headless'];
                                                    if(botMarkers.some(m=>src.includes(m))){
                                                        return false;
                                                    }
                                                    return origTest.call(this,s);
                                                };
                                            }catch(e){}
                                            // === 2026-05-23 : MUTE RETIRÉ ===
                                            // Le mute (MutationObserver + setInterval) causait des AbortError
                                            // ("play() interrupted by pause()/media removed") qui tuaient la
                                            // chaîne d'extraction. Le son hors-écran est un moindre mal vs
                                            // l'extraction qui échoue. Côté audio focus, Android le gère
                                            // naturellement (la WebView headless perd le focus au profit du
                                            // player principal). On ne force plus rien.
                                        })();
                                    """.trimIndent()
                                    val patchedJs = spoofPrelude + "\n" + originalJs
                                    android.util.Log.d("Player4meExtractor", "Intercepted + patched JS bundle: $reqUrl (${patchedJs.length} bytes)")
                                    return WebResourceResponse(
                                        "application/javascript",
                                        "utf-8",
                                        patchedJs.byteInputStream(Charsets.UTF_8),
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.w("Player4meExtractor", "Failed to patch JS: ${e.message}")
                                }
                            }

                            // Capture m3u8/mp4 — match SEULEMENT sur le PATH.
                            val isHls = path.endsWith(".m3u8") ||
                                path.contains("master.m3u8") ||
                                path.contains("playlist.m3u8") ||
                                path.contains("/hls/")
                            if (isHls) {
                                android.util.Log.d("Player4meExtractor", "HLS INTERCEPTED: $reqUrl")
                                resolve(reqUrl)
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            val isMp4 = path.endsWith(".mp4") &&
                                !path.contains("/ads/") &&
                                !path.contains("/ad/")
                            if (isMp4) {
                                android.util.Log.d("Player4meExtractor", "MP4 INTERCEPTED: $reqUrl")
                                resolve(reqUrl)
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return
                            android.util.Log.d("Player4meExtractor", "onPageFinished: $finishedUrl")

                            // 2026-05-23 : injecter un hook JS qui :
                            // 1) Override window.fetch pour capturer les appels /api/v1/player
                            //    et les m3u8 fetch via JS (invisible pour shouldInterceptRequest)
                            // 2) Monitor video.src et video.currentSrc
                            // 3) Simule un clic pour déclencher la lecture
                            view.evaluateJavascript(
                                """
                                (function(){
                                    // === HOOK FETCH pour capturer le m3u8 ===
                                    var __origFetch = window.fetch;
                                    window.fetch = function() {
                                        var url = (arguments[0] && arguments[0].url) ? arguments[0].url : String(arguments[0]);
                                        // Log TOUTES les fetch pour debug
                                        console.log('P4M_FETCH: ' + url);
                                        // Intercepter la réponse API (player OU video)
                                        if (url.indexOf('/api/') !== -1) {
                                            return __origFetch.apply(this, arguments).then(function(resp) {
                                                return resp.clone().text().then(function(body) {
                                                    console.log('P4M_API_RESP: ' + body.substring(0, 3000));
                                                    // Chercher m3u8/mp4 dans la réponse JSON
                                                    var m = body.match(/https?:[^"'\s\\]+\.m3u8[^"'\s\\]*/);
                                                    if (m) console.log('P4M_STREAM: ' + m[0].replace(/\\/g,''));
                                                    var mp = body.match(/https?:[^"'\s\\]+\.mp4[^"'\s\\]*/);
                                                    if (mp) console.log('P4M_STREAM: ' + mp[0].replace(/\\/g,''));
                                                    // Chercher aussi dans les champs JSON "url", "file", "src", "source"
                                                    try {
                                                        var j = JSON.parse(body);
                                                        var fields = ['url','file','src','source','stream','video','link','embed'];
                                                        for(var k=0;k<fields.length;k++){
                                                            var v = j[fields[k]] || (j.data && j.data[fields[k]]);
                                                            if(v && typeof v === 'string' && v.indexOf('http') !== -1){
                                                                console.log('P4M_STREAM: ' + v);
                                                            }
                                                        }
                                                    } catch(e) {}
                                                    return resp;
                                                });
                                            });
                                        }
                                        // Capturer directement les fetch m3u8/mp4
                                        if (url.indexOf('.m3u8') !== -1 || url.indexOf('.mp4') !== -1) {
                                            console.log('P4M_STREAM: ' + url);
                                        }
                                        return __origFetch.apply(this, arguments);
                                    };
                                    // === HOOK XMLHttpRequest ===
                                    var __origOpen = XMLHttpRequest.prototype.open;
                                    XMLHttpRequest.prototype.open = function(method, url) {
                                        console.log('P4M_XHR: ' + url);
                                        if (url && (url.indexOf('.m3u8') !== -1 || url.indexOf('.mp4') !== -1)) {
                                            console.log('P4M_STREAM: ' + url);
                                        }
                                        if (url && url.indexOf('/api/') !== -1) {
                                            this.addEventListener('load', function() {
                                                try {
                                                    console.log('P4M_API_RESP: ' + this.responseText.substring(0, 2000));
                                                    var m = this.responseText.match(/https?:[^"'\s]+\.m3u8[^"'\s]*/);
                                                    if (m) console.log('P4M_STREAM: ' + m[0]);
                                                } catch(e) {}
                                            });
                                        }
                                        return __origOpen.apply(this, arguments);
                                    };
                                    // === MONITOR video.src ===
                                    setInterval(function(){
                                        var vids = document.querySelectorAll('video');
                                        for(var i=0;i<vids.length;i++){
                                            var s = vids[i].currentSrc || vids[i].src;
                                            if(s && s.indexOf('blob:') === -1 && s.indexOf('data:') === -1 && s.length > 10){
                                                console.log('P4M_STREAM: ' + s);
                                            }
                                        }
                                    }, 2000);
                                    // === CLIC SYNTHETIQUE ===
                                    try{
                                        var evt = new MouseEvent('click', {bubbles:true,cancelable:true,view:window,clientX:window.innerWidth/2,clientY:window.innerHeight/2});
                                        document.body.dispatchEvent(evt);
                                        var candidates = document.querySelectorAll('button,[role=button],[class*=play],[id*=play],video');
                                        for(var j=0;j<candidates.length;j++){
                                            try{ candidates[j].click(); }catch(e){}
                                        }
                                        document.querySelectorAll('video').forEach(function(v){try{v.play();}catch(e){}});
                                    }catch(e){}
                                })();
                                """.trimIndent(),
                                null,
                            )

                            // 25s timeout
                            view.postDelayed({
                                if (!resolved) {
                                    android.util.Log.w("Player4meExtractor", "Timeout — no stream captured")
                                    resolve(null)
                                }
                            }, 22_000L)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) { /* ignore */ }

                        // 2026-05-21 (logs : "handshake failed ... net_error -202" =
                        //   ERR_CERT_AUTHORITY_INVALID) : les hosts 4meplayer + leur CDN
                        //   ont souvent des certs invalides/auto-signés. Par défaut la
                        //   WebView ANNULE la requête → le player ne charge pas le flux →
                        //   extraction échoue. On PROCEED (cohérent avec le trust-all déjà
                        //   en place côté OkHttp/IptvTlsHelper, qui ne couvre PAS la WebView).
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            try { handler?.proceed() } catch (_: Throwable) { handler?.cancel() }
                        }
                    }

                    // 2026-05-15 : top-level load avec Referer dessinanime.cc.
                    // L'iframe wrapper causait document.domain mutation blocked.
                    webView.loadUrl(url, mapOf("Referer" to "https://dessinanime.cc/"))

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
        private val ALLOWED_HOSTS = listOf(
            "4meplayer.com",
            "embed4me.com",
            "cloudflare.com",
            "challenges.cloudflare.com",
            "cloudfront.net",
            "akamaized.net",
            "googlevideo.com",
            "cdn.jsdelivr.net",
            // Hosts probables que player4me wrap (au cas où)
            "sendvid.com",
            "uqload.is",
            "abysscdn.com",
        )
        private val BLOCKED_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice",
            "popads", "popunder", "popcash", "propellerads",
            "exoclick", "juicyads", "trafficjunky",
            "googletagmanager", "google-analytics",
            // Yandex tracker (vu polluer le cache stream)
            "mc.yandex.ru", "yandex.ru", "metrica.yandex",
            // 2026-05-15 : retiré cloudflareinsights — le bundle Player4me en
            // dépendait. Le bloquer cassait l'init du player.
        )

    }
}
