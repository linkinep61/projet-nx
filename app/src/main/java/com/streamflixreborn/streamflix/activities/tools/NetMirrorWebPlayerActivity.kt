package com.streamflixreborn.streamflix.activities.tools

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.WebViewResolver

/**
 * 2026-07-08 — Lecteur WebView NetMirror (embarque le player web officiel).
 *
 * NetMirror masque la vidéo en anonyme et son token vidéo est signé côté serveur (non
 * reproductible). La SEULE façon de jouer le VRAI film = faire comme l'app officielle :
 * une WebView dont l'UA porte le marqueur « OS.Gatu v3.0 », qui charge la web-app
 * `net52.cc/mobile/` (gatée par cet UA → sinon « Page Not Found ») et laisse le player web
 * gérer tout seul token + lecture. On récupère ainsi le vrai film sans rien extraire.
 *
 * Plein écran immersif paysage, fullscreen HTML5 (onShowCustomView), STEALTH_JS injecté
 * pour passer Cloudflare, lifecycle propre.
 */
class NetMirrorWebPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NetMirrorWebPlayer"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_COOKIE = "extra_cookie"

        /** UA officiel : STEALTH Chrome + le marqueur qui débloque NetMirror. */
        val NM_UA: String = WebViewResolver.STEALTH_UA + " OS.Gatu v3.0"

        /** DIAG 2026-07-08 : mirrors joignables = mobidetect.art / mobiledetect.app / netmirror.app.
         *  On charge check.php d'un mirror vivant pour lire la config (vraie URL d'entrée + domaines). */
        // Entrée directe (token_hash de check.php = stable). Évite la boucle/JSON de check.php.
        const val DEFAULT_URL = "https://net52.cc/mobile/home?app=1"

        fun launch(context: Context, url: String = DEFAULT_URL, title: String? = null, cookie: String? = null) {
            context.startActivity(
                Intent(context, NetMirrorWebPlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_COOKIE, cookie)
            )
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var errorOverlay: LinearLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var targetUrl: String = DEFAULT_URL
    private var bootstrapped = false
    private var currentUa: String = "" // UA lu sur le main thread, réutilisé dans shouldInterceptRequest
    @Volatile private var realHost: String? = null // host CDN + asset RÉEL captés depuis la requête audio
    @Volatile private var realAsset: String? = null

    /** DISCRIMINANT OFFICIEL (désassemblage Hermes) : l'app officielle écrase le header
     *  `X-Requested-With` par une chaîne VIDE. La WebView Android y met sinon le package
     *  (`com.streamfr.app.debug`) → net52 renvoie « Page Not Found ». Vide → sert la vraie web-app. */
    private val nmHeaders = mapOf("X-Requested-With" to "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_player)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        enterImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        webView = findViewById(R.id.webview_player)
        progressBar = findViewById(R.id.webview_progress)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        errorOverlay = findViewById(R.id.error_overlay)

        targetUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
        findViewById<TextView>(R.id.btn_retry).setOnClickListener { load() }

        setupWebView()
        // Si lancé sans cookie (ex : dossier TV Hub), on récupère la session t_hash_t d'abord
        //   (sinon net52/mobile/home = « Page Not Found »), puis on charge.
        if (!intent.getStringExtra(EXTRA_COOKIE).isNullOrBlank()) {
            load()
        } else {
            lifecycleScope.launch {
                val c = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { com.streamflixreborn.streamflix.providers.NetMirrorProvider.sessionCookie() }.getOrNull()
                }
                intent.putExtra(EXTRA_COOKIE, c)
                load()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.evaluateJavascript("if(typeof jwplayer!=='undefined')try{jwplayer().pause()}catch(e){}", null)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        enterImmersiveMode()
    }

    override fun onDestroy() {
        runCatching { webView.stopLoading(); webView.destroy() }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) { customViewCallback?.onCustomViewHidden(); return }
        if (webView.canGoBack()) { webView.goBack(); return }
        finish()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun load() {
        errorOverlay.visibility = View.GONE
        webView.visibility = View.VISIBLE
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        // Cookie de session t_hash_t (comme search.php) → net52/mobile/home sert la vraie web-app
        //   au lieu de « Page Not Found ». Fourni par NetMirrorProvider (ensureCookie).
        val cookie = intent.getStringExtra(EXTRA_COOKIE)
        if (!cookie.isNullOrBlank()) {
            runCatching {
                cookie.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { c ->
                    cm.setCookie("https://net52.cc", "$c; path=/")
                    cm.setCookie("https://net77.cc", "$c; path=/")
                }
                cm.flush()
                Log.d(TAG, "session cookie posé (net52/net77)")
            }
        }
        if (targetUrl == "probe://mirrors") {
            Log.d(TAG, "PROBE : sonde des mirrors mobidetect…")
            val doms = listOf(
                "mobidetect.art","mobidetect.live","mobidetect.pro","mobidetect.shop",
                "mobidetect.site","mobidetect.vip","mobidetect.wiki","mobidetect.click","mobidetect.xyz",
                "mobidetects.art","mobidetects.ink","mobidetects.pro","mobidetects.store",
                "mobidetects.top","mobidetects.xyz","mobiledetect.app","netmirror.app"
            )
            val arr = doms.joinToString(",") { "'$it'" }
            val html = """<!doctype html><html><body><script>
                var doms=[$arr];
                doms.forEach(function(d){
                  var url='https://'+d+'/check.php';
                  var ctrl=new AbortController(); var to=setTimeout(function(){ctrl.abort();},6000);
                  fetch(url,{mode:'no-cors',signal:ctrl.signal}).then(function(r){clearTimeout(to);NmProbe.log(d+' REACHABLE');})
                    .catch(function(e){clearTimeout(to);NmProbe.log(d+' '+(e&&e.name==='AbortError'?'TIMEOUT':'ERR'));});
                });
                </script></body></html>""".trimIndent()
            webView.loadDataWithBaseURL("https://net52.cc/", html, "text/html", "utf-8", null)
            return
        }
        Log.d(TAG, "Loading NetMirror web player: $targetUrl (UA OS.Gatu + X-Requested-With officiel)")
        webView.loadUrl(targetUrl, nmHeaders)
    }

    @SuppressLint("ClickableViewAccessibility", "JavascriptInterface", "AddJavascriptInterface")
    private fun setupWebView() {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // DIAG : pont JS→Kotlin pour la sonde de mirrors.
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun log(s: String) { Log.d(TAG, "PROBE $s") }
        }, "NmProbe")

        // 2026-07-08 (DISCRIMINANT MANQUANT) : la web-app NetMirror détecte l'app officielle via
        //   `window.ReactNativeWebView` (objet injecté par react-native-webview). Sans lui, elle
        //   croit être dans un navigateur → sert le preview/pub. On réplique ce pont natif → la
        //   web-app pense tourner dans l'app officielle → vrai film.
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun postMessage(msg: String) { Log.d(TAG, "RNbridge postMessage: ${msg.take(80)}") }
        }, "NmRNBridge")
        webView.setOnTouchListener { v, _ -> v.performClick(); false }
        webView.isClickable = true
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // 2026-07-08 : l'app officielle fait `applicationNameForUserAgent = "OS.Gatu v3.0"`, ce
            //   qui donne l'UA WebView PAR DÉFAUT (avec le marqueur "wv"/Version/4.0) + " OS.Gatu v3.0".
            //   net52/mobile check ce pattern EXACT → on réplique : UA défaut + le marqueur (pas un UA
            //   custom, sinon « Page Not Found »).
            userAgentString = (userAgentString ?: "") + " /OS.Gatu v3.0"
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        currentUa = webView.settings.userAgentString ?: ""

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                progressBar.progress = p
                progressBar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            }
            override fun onShowCustomView(view: View?, cb: CustomViewCallback?) {
                if (customView != null) { cb?.onCustomViewHidden(); return }
                customView = view
                customViewCallback = cb
                webView.visibility = View.GONE
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                enterImmersiveMode()
            }
            override fun onHideCustomView() {
                fullscreenContainer.removeAllViews()
                fullscreenContainer.visibility = View.GONE
                customView = null
                customViewCallback = null
                webView.visibility = View.VISIBLE
                enterImmersiveMode()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            // STEALTH_JS au document-start ET fin → Cloudflare/Turnstile invisible.
            override fun onPageStarted(view: WebView?, url: String?, f: Bitmap?) {
                super.onPageStarted(view, url, f)
                errorOverlay.visibility = View.GONE
                // Simule l'environnement react-native-webview (window.ReactNativeWebView) → la web-app
                //   croit tourner dans l'app officielle (et pas un navigateur) → débloque le vrai film.
                runCatching {
                    view?.evaluateJavascript(
                        "window.ReactNativeWebView = window.ReactNativeWebView || " +
                        "{ postMessage: function(d){ try{ NmRNBridge.postMessage(''+d); }catch(e){} } };" +
                        "true;", null
                    )
                }
                runCatching { view?.evaluateJavascript(WebViewResolver.STEALTH_JS, null) }
            }
            // DIAG : logguer les m3u8/segments/play.php que le player charge → voir si le VRAI
            //   manifeste (files/<id>/…1080p.m3u8?in=) passe dans notre contexte WebView.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString()
                if (u != null) {
                    if (u.contains(".m3u8") || u.contains("/files/") || u.contains("hls")) {
                        Log.d(TAG, "DIAG req: ${u.take(110)}")
                    }
                    // 0) Master (arrive en 1er) → asset réel = l'id dans l'URL ; on reset le host (attend l'audio).
                    Regex("net52\\.cc/mobile/hls/(\\d+)\\.m3u8").find(u)?.let {
                        realAsset = it.groupValues[1]; realHost = null
                    }
                    // 1) Requête AUDIO → host CDN réel + asset (files/<asset>/a/<n>/<n>.m3u8).
                    Regex("https?://([^/]+)/files/(\\d+)/a/\\d+/\\d+\\.m3u8").find(u)?.let {
                        realHost = it.groupValues[1]; realAsset = it.groupValues[2]
                    }
                    // 2) Remplace la variante VIDÉO PREVIEW par la VRAIE vidéo token-free (segments libres).
                    //    La vidéo arrive AVANT l'audio → on attend brièvement le host (audio) si besoin.
                    if (Regex("/files/\\d+/(1080p|720p|480p)/[^?]*\\.m3u8").containsMatchIn(u)) {
                        val asset = realAsset
                        if (asset != null) {
                            var waited = 0
                            while (realHost == null && waited < 3000) { Thread.sleep(50); waited += 50 }
                        }
                        val host = realHost
                        if (host != null && asset != null) {
                            val quality = Regex("/(1080p|720p|480p)/").find(u)?.groupValues?.get(1) ?: "1080p"
                            val hdrs = mapOf("User-Agent" to currentUa, "Referer" to "https://net52.cc/")
                            val vid = runCatching {
                                kotlinx.coroutines.runBlocking {
                                    com.streamflixreborn.streamflix.providers.NetMirrorProvider.buildRealVideoManifest(host, asset, hdrs, quality)
                                }
                            }.getOrNull()
                            if (!vid.isNullOrBlank()) {
                                Log.d(TAG, "WebView vidéo preview → VRAIE vidéo token-free ($quality, asset=$asset)")
                                // CORS : hls.js fetch ce manifeste cross-origin (net52 → cdn) ; sans
                                //   Access-Control-Allow-Origin il ne peut PAS le lire → spinner. On l'ajoute.
                                val corsHeaders = mapOf(
                                    "Access-Control-Allow-Origin" to "*",
                                    "Access-Control-Allow-Headers" to "*",
                                    "Access-Control-Allow-Methods" to "GET, OPTIONS",
                                    "Cache-Control" to "no-cache"
                                )
                                return WebResourceResponse(
                                    "application/vnd.apple.mpegurl", "utf-8", 200, "OK",
                                    corsHeaders,
                                    java.io.ByteArrayInputStream(vid.toByteArray(Charsets.UTF_8))
                                )
                            }
                            Log.w(TAG, "WebView vidéo : reconstruction KO → preview laissé")
                        }
                    }
                }
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme ?: return true
                if (scheme == "javascript" || scheme == "blob" || scheme == "data") return false
                val host = request.url?.host ?: return true
                val allowed = isAllowedHost(host)
                if (!allowed) Log.d(TAG, "Blocked nav: $host")
                return !allowed
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page finished: ${url?.take(60)}")
                runCatching { view?.evaluateJavascript(WebViewResolver.STEALTH_JS, null) }
                // injectedJavaScript EXACT de l'app officielle : appelle pressfromAPP() si la web-app
                //   l'a défini. C'est le handshake « je suis l'app officielle » qui débloque le vrai film.
                runCatching {
                    view?.evaluateJavascript(
                        "(function(){try{if(typeof pressfromAPP==='function'){pressfromAPP();return 'CALLED';}return 'ABSENT';}catch(e){return 'ERR';}})()"
                    ) { r -> Log.d(TAG, "pressfromAPP → ${r?.trim('"')}") }
                }
                // DIAG : UA réellement envoyé + contenu de la page (pour trouver la bonne entrée).
                // BOOTSTRAP officiel : check.php renvoie {token_hash: base64(URL d'entrée mobile)}.
                //   On décode et on charge cette entrée (ex: net52.cc/mobile/home?app=1) → vraie web-app.
                if (url != null && url.contains("check.php") && !bootstrapped) {
                    view?.evaluateJavascript("(document.body?document.body.innerText:'')") { b ->
                        val raw = (b ?: "").trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
                        val th = Regex("\"token_hash\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
                        val entry = th?.let {
                            runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrNull()
                        }
                        Log.d(TAG, "bootstrap token_hash → entrée=$entry")
                        if (!entry.isNullOrBlank() && entry.startsWith("http")) {
                            bootstrapped = true // casse la boucle home→check.php→home
                            runOnUiThread { webView.loadUrl(entry, nmHeaders) }
                        } else {
                            Log.w(TAG, "bootstrap : token_hash illisible")
                        }
                    }
                }
                // DIAG : contenu de la page d'entrée (Page Not Found vs vraie web-app).
                if (url != null && url.contains("/mobile/home")) {
                    view?.evaluateJavascript("(document.body?document.body.innerText:'').slice(0,80)") { b ->
                        Log.d(TAG, "DIAG home body=${b?.take(80)}")
                    }
                }
                // DIAG echo : voir le header X-Requested-With RÉELLEMENT envoyé.
                if (url != null && url.contains("httpbin")) {
                    view?.evaluateJavascript("(document.body?document.body.innerText:'')") { b ->
                        val s = (b ?: "").trim('"').replace("\\n"," ").replace("\\\"","\"")
                        val xrw = Regex("X-Requested-With[^,}]*").find(s)?.value ?: "ABSENT"
                        Log.d(TAG, "DIAG echo X-Requested-With=[$xrw]")
                    }
                }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "Main frame error: ${error?.description}")
                    webView.visibility = View.GONE
                    errorOverlay.visibility = View.VISIBLE
                }
            }
            @Suppress("DEPRECATION")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                val host = error?.url ?: ""
                if (isAllowedHost(host)) handler?.proceed() else handler?.cancel()
            }
        }
    }

    /** Domaines NetMirror + CDN + player autorisés (le reste = pubs/redirects bloqués). */
    private fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        return h.contains("net52") || h.contains("net77") || h.contains("net22") ||
            h.contains("nm-cdn") || h.contains("freecdn") || h.contains("mobidetect") ||
            h.contains("mobiledetect") || h.contains("netmirror") || h.contains("imgcdn") ||
            h.contains("jwpcdn") || h.contains("jwplayer") ||
            h.contains("challenges.cloudflare") || h.contains("cloudflare") ||
            h.contains("gstatic") || h.contains("google")
    }
}
