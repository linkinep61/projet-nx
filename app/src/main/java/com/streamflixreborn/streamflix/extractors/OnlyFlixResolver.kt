package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 2026-07-16 — Résolveur headless pour les players « OnlyFlix » de Movix
 *   (SeekStreaming = seekplayer.vip/.me, EmbedSeek = *.embedseek.com).
 *
 * Contexte (décortiqué en direct dans Chrome sur L'Odyssée) :
 *   `/api/v1/info?id=<id>` renvoie une config chiffrée AES-CBC (WebCrypto), déchiffrée CÔTÉ
 *   CLIENT. Le résultat = l'URL d'un **master.m3u8 proxifié par le player lui-même** :
 *     `https://<host>/hlsmod/<tiktokcdn-host>/<path>/tt/master.m3u8?v=…`
 *   Les SEGMENTS de ce HLS sont du **MPEG-TS caché dans des images PNG** hébergées sur le CDN
 *   de TikTok (`*.tiktokcdn.com/…​.image`) : chaque segment = ~120 octets d'en-tête PNG (jusqu'à
 *   IEND) PUIS le TS pur (sync 0x47 tous les 188 octets, longueur exactement multiple de 188).
 *   hls.js strippe le PNG côté client. → cf. SeekStreamPngDataSource pour rejouer ça dans ExoPlayer.
 *
 * Le déchiffrement AES étant 100% JS (clé rotative, non exfiltrable), on ne le reproduit pas en
 *   Kotlin : on charge la page dans une WebView HEADLESS (invisible), on laisse le JS déchiffrer,
 *   et on capte le master.m3u8 par DEUX voies : (a) interception réseau de la requête
 *   `…/hlsmod/…master.m3u8`, (b) polling de l'attribut `src` du `<media-player>` (posé dès le
 *   déchiffrement, sans lecture). ExoPlayer joue ensuite le m3u8 en natif via le DataSource
 *   qui strippe les en-têtes PNG. Aucune WebView VISIBLE (contrairement à l'ancien overlay).
 */
object OnlyFlixResolver {

    private const val TAG = "OnlyFlixResolver"

    /** Force le player à démarrer pour déclencher le chargement du master.m3u8 par hls.js. */
    private const val PLAY_JS = """
        (function(){try{
            try{window.open=function(){return null;};}catch(e){}
            var mp=document.querySelector('media-player');
            if(mp){try{mp.muted=true;}catch(e){}
                   try{mp.setAttribute&&mp.setAttribute('load','eager');mp.setAttribute&&mp.setAttribute('posterLoad','eager');}catch(e){}
                   try{mp.load='eager';}catch(e){}
                   try{if(mp.startLoading)mp.startLoading();}catch(e){}
                   try{if(mp.startLoadingPoster)mp.startLoadingPoster();}catch(e){}}
            // clic réel sur le gros bouton play (déclenche la lecture P2P sans play() direct)
            var b=document.querySelector('.vds-play-button')||document.querySelector('media-play-button')
                 ||document.querySelector('button[aria-label*="lay"]')||document.querySelector('[class*="play"]');
            if(b){try{var r=b.getBoundingClientRect();var x=(r.left+r.width/2)||5,y=(r.top+r.height/2)||5;
                var o={bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,view:window,button:0};
                b.dispatchEvent(new PointerEvent('pointerdown',o));b.dispatchEvent(new MouseEvent('mousedown',o));
                b.dispatchEvent(new PointerEvent('pointerup',o));b.dispatchEvent(new MouseEvent('mouseup',o));
                b.dispatchEvent(new MouseEvent('click',o));b.click&&b.click();}catch(e){}}
        }catch(e){console.log('PLAY_JS err '+e);}})();
    """

    /** Cherche le master.m3u8 (…/hlsmod/…master.m3u8) via plusieurs sources DOM + regex. */
    private const val PROBE_JS = """
        (function(){try{
            var cands=[];
            var mp=document.querySelector('media-player');
            if(mp){cands.push(mp.src,mp.currentSrc,(mp.getAttribute&&mp.getAttribute('src')));
                   try{if(mp.state){cands.push(mp.state.currentSrc,(mp.state.source&&mp.state.source.src));}}catch(e){}}
            var v=document.querySelector('video'); if(v){cands.push(v.currentSrc,v.src);}
            document.querySelectorAll('source').forEach(function(s){cands.push(s.src||(s.getAttribute&&s.getAttribute('src')));});
            for(var i=0;i<cands.length;i++){var c=cands[i];
                if(c&&typeof c==='object')c=c.src||c.url;
                if(typeof c==='string'&&c.indexOf('/tt/master.m3u8')>=0)return c;}
            var h=document.documentElement.innerHTML;
            var m=h.match(/https?:\/\/[^"'\s\\]*\/tt\/master\.m3u8[^"'\s\\]*/);
            if(m)return m[0];
            return '';
        }catch(e){return '';}})();
    """

    private val BLOCKED_HOSTS = listOf(
        "googlesyndication", "doubleclick", "googleads", "imasdk.googleapis",
        "popads", "popunder", "popcash", "propellerads", "exoclick", "juicyads",
        "trafficjunky", "googletagmanager", "google-analytics", "mc.yandex.ru",
        "yandex.ru", "a-ads.com", "translate.googleapis", "cloudflareinsights",
    )

    /** Vrai geste tactile (MotionEvent down+up) → déclenche la lecture P2P que le player OnlyFlix
     *  exige (isTrusted). Sans ça, seul le leurre preload.m3u8 se charge, jamais le vrai master. */
    private fun realTap(view: WebView, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
        try { view.dispatchTouchEvent(down); view.dispatchTouchEvent(up) } catch (_: Exception) {}
        down.recycle(); up.recycle()
    }

    private fun isMasterM3u8(url: String): Boolean {
        val l = url.lowercase()
        // Signature OnlyFlix : master.m3u8 proxifié depuis le CDN TikTok → segment path se
        //   termine par `/tt/master.m3u8`. Le host/prefix varie (mhd.seekplayer.me/hlsmod/,
        //   flemmix.upns.pro/hls/, *.embedseek.com/…) → on matche la signature `/tt/master.m3u8`.
        return l.contains("/tt/master.m3u8") || (l.contains("/hlsmod/") && l.contains("master.m3u8"))
    }

    /** Charge la page OnlyFlix en headless et renvoie l'URL du master.m3u8 (ou null si échec). */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveMasterM3u8(pageUrl: String, timeoutMs: Long = 15_000L): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val context = StreamFlixApp.instance.applicationContext
                    var resolved = false
                    var destroyHook: (() -> Unit)? = null
                    var attachedRoot: android.view.ViewGroup? = null
                    fun resolve(value: String?) {
                        if (!resolved && cont.isActive) {
                            resolved = true
                            cont.resume(value)
                        }
                        destroyHook?.invoke()
                        destroyHook = null
                    }

                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = WebViewResolver.STEALTH_UA
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                    }
                    // Attache la WebView DERRIÈRE l'UI (index 0, alpha ~0) sur l'activité courante :
                    //   elle a alors une VRAIE fenêtre/surface, sinon le player vidstack reste
                    //   « media not ready » et ne déclenche jamais le chargement du master.m3u8.
                    //   Invisible pour l'utilisateur. Fallback measure/layout si pas d'activité.
                    try {
                        val act = StreamFlixApp.currentActivity
                        val root = act?.findViewById<android.view.ViewGroup>(android.R.id.content)
                        if (root != null) {
                            webView.alpha = 0.02f
                            root.addView(webView, 0, android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                            attachedRoot = root
                        } else {
                            webView.layout(0, 0, 1280, 720)
                        }
                    } catch (_: Exception) {
                        try { webView.layout(0, 0, 1280, 720) } catch (_: Exception) {}
                    }
                    webView.webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                            Log.d(TAG, "JS: ${m?.message()?.take(120)}")
                            return true
                        }
                        override fun onCreateWindow(v: WebView?, d: Boolean, u: Boolean, msg: android.os.Message?): Boolean = false
                    }
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?, request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host?.lowercase() ?: ""
                            val low = reqUrl.lowercase()
                            if (low.contains("/api/v1/") || low.contains("m3u8") || low.contains("/tt/") || low.contains(".txt")) {
                                Log.d(TAG, "REQ: ${reqUrl.take(100)}")
                            }
                            // (a) interception réseau du master.m3u8
                            if (isMasterM3u8(reqUrl)) {
                                Log.d(TAG, "master.m3u8 CAPTURED (intercept): ${reqUrl.take(90)}")
                                resolve(reqUrl)
                                // on bloque : pas besoin de streamer dans la WebView
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            // on coupe le chargement des segments PNG (lourds) dans la WebView
                            if (host.contains("tiktokcdn") || reqUrl.lowercase().contains(".image")) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (resolved || view == null) return
                            // Force le player à démarrer (→ hls.js fetch le master.m3u8 qu'on intercepte)
                            //   + poll multi-stratégie (src du media-player / video / regex DOM).
                            val startMs = System.currentTimeMillis()
                            val maxPollMs = 14_000L
                            fun poll() {
                                if (resolved) return
                                // relance le déclenchement (le player vidstack s'hydrate progressivement)
                                view.evaluateJavascript(PLAY_JS, null)
                                // VRAI geste tactile au centre = démarre la lecture P2P (→ vrai master.m3u8)
                                val w = if (view.width > 0) view.width else 720
                                val h = if (view.height > 0) view.height else 1280
                                realTap(view, w / 2f, h / 2f)
                                view.evaluateJavascript(PROBE_JS) { result ->
                                    val src = result?.trim('"')?.replace("\\/", "/")?.replace("\\u0026", "&") ?: ""
                                    if (isMasterM3u8(src)) {
                                        Log.d(TAG, "master.m3u8 via DOM probe: ${src.take(90)}")
                                        resolve(src)
                                    } else if (System.currentTimeMillis() - startMs < maxPollMs) {
                                        view.postDelayed({ poll() }, 800L)
                                    }
                                }
                            }
                            view.postDelayed({ poll() }, 700L)
                        }

                        override fun onReceivedError(
                            view: WebView?, request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) { /* ignore */ }
                    }

                    Log.d(TAG, "Loading OnlyFlix page headless: ${pageUrl.take(90)}")
                    webView.loadUrl(pageUrl)

                    destroyHook = {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                webView.stopLoading()
                                attachedRoot?.removeView(webView)
                                attachedRoot = null
                                webView.destroy()
                            } catch (_: Exception) {}
                        }
                    }
                    cont.invokeOnCancellation {
                        resolved = true
                        destroyHook?.invoke()
                        destroyHook = null
                    }
                }
            }
        }
}
