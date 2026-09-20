package com.streamflixreborn.streamflix.providers

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
 * 2026-09-25 — Franchisseur du nouvel anti-bot **BotBlocker** de Coflix (coflix.ac / .esq / .cloud).
 *
 * BotBlocker sert un interstitiel « Checking your browser before accessing the site / Loading… »
 * qui exécute une **preuve-de-travail JavaScript** (timers / requestAnimationFrame), pose un cookie
 * de passage valable pour TOUT le domaine, puis recharge la vraie page. Ce n'est PAS du Cloudflare
 * (cookie différent de cf_clearance).
 *
 * Pourquoi un résolveur dédié plutôt que WebViewResolver : en mode silencieux, WebViewResolver
 * garde son WebView **détaché** de la fenêtre. Cloudflare passe quand même détaché, mais la PoW de
 * BotBlocker a besoin d'une surface de rendu (les timers/rAF ne tournent pas sur un WebView sans
 * fenêtre — même piège que AfterDark et AnonMP4). Ici on ATTACHE le WebView (alpha 0,02, invisible)
 * à l'activité courante, on laisse le challenge tomber, et le cookie posé dans [CookieManager] est
 * ensuite relu par OkHttp (voir CoflixSourceProvider.okGet). Isolé → aucun risque pour le bypass
 * Cloudflare partagé (Wiflix, DessinAnime…).
 */
object CoflixBotBlockerResolver {
    private const val TAG = "CoflixBotBlocker"

    /** Attache un WebView invisible, charge [homeUrl], attend que BotBlocker tombe (cookie posé).
     *  Renvoie true si la vraie page est apparue (challenge franchi). Cap 35 s. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun ensurePass(homeUrl: String): Boolean = withTimeoutOrNull(35_000L) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val ctx = StreamFlixApp.currentActivity ?: StreamFlixApp.instance
                var webView: WebView? = null
                var attachedRoot: android.view.ViewGroup? = null
                var finished = false

                fun finish(ok: Boolean) {
                    if (finished) return
                    finished = true
                    runCatching {
                        CookieManager.getInstance().flush()
                        attachedRoot?.removeView(webView)
                        webView?.stopLoading()
                        webView?.loadUrl("about:blank")
                        webView?.destroy()
                    }
                    attachedRoot = null
                    webView = null
                    if (cont.isActive) cont.resume(ok)
                }

                val wv = WebView(ctx)
                webView = wv
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = WebViewResolver.STEALTH_UA
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                }
                wv.addJavascriptInterface(object {
                    @JavascriptInterface fun result(s: String?) { runCatching { wv.post { finish(s == "ok") } } }
                    @JavascriptInterface fun log(s: String?) { Log.d(TAG, "js: $s") }
                }, "CFX")

                // Poller JS : dès que la page n'est plus l'interstitiel ET qu'il y a du vrai contenu
                //   Coflix (liens /film//serie//movie ou body volumineux), c'est franchi.
                val js = """
                    (function(){
                      var ticks=0;
                      var iv=setInterval(function(){
                        ticks++;
                        try{
                          var t=((document.body&&document.body.innerText)||'').toLowerCase();
                          var title=(document.title||'').toLowerCase();
                          var challenge = t.indexOf('checking your browser')>=0
                            || t.indexOf('botblocker')>=0
                            || t.indexOf('please wait')>=0
                            || title.indexOf('just a moment')>=0;
                          var hasSite = !!document.querySelector('a[href*="/film/"],a[href*="/serie/"],a[href*="/movie/"],a[href*="/tv/"]');
                          if(!challenge && (hasSite || t.length>800)){ clearInterval(iv); CFX.log('passe ticks='+ticks+' len='+t.length); CFX.result('ok'); }
                          else if(ticks>70){ clearInterval(iv); CFX.log('timeout ticks='+ticks+' challenge='+challenge); CFX.result('timeout'); }
                        }catch(e){ if(ticks>70){ clearInterval(iv); CFX.result('timeout'); } }
                      }, 450);
                    })();
                """.trimIndent()

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (finished) return
                        // Réinjecté à CHAQUE load : l'interstitiel BotBlocker recharge vers la vraie
                        //   page, donc onPageFinished tombe plusieurs fois.
                        wv.postDelayed({ if (!finished) wv.evaluateJavascript(js, null) }, 600)
                    }
                }

                // Attache invisible → sinon les timers/rAF de la PoW ne tournent pas.
                runCatching {
                    val root = StreamFlixApp.currentActivity?.findViewById<android.view.ViewGroup>(android.R.id.content)
                    if (root != null) {
                        wv.alpha = 0.02f
                        root.addView(
                            wv, 0,
                            android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        attachedRoot = root
                    } else {
                        wv.layout(0, 0, 1080, 1920)
                        Log.w(TAG, "pas d'activité → WebView non attachée (timers peuvent ne pas tourner)")
                    }
                }

                Log.d(TAG, "ensurePass → $homeUrl")
                wv.loadUrl(homeUrl)
                cont.invokeOnCancellation { runCatching { wv.post { finish(false) } } }
            }
        }
    } ?: false
}
