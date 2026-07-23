package com.streamflixreborn.streamflix.providers

import android.annotation.SuppressLint
import android.util.Log
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
 * 2026-07-23 — AfterDark ad-gate resolver.
 *
 * L'API `/sources` d'afterdark06.mom est gardée par un AD-GATE : « Cliquer sur la publicité (0/2) ».
 * Reverse (Chrome du user) : la gate ne compte QUE les clics (les vraies pubs `window.open` peuvent
 * être neutralisées) ; après 2 clics elle tombe et `/sources` renvoie 200. Le déblocage est GLOBAL
 * pour la session (tous les tmdbId marchent ensuite).
 *
 * Ce resolver charge une page `/video/<uuid>` (déclenche la gate + passe CF via STEALTH), neutralise
 * `window.open`, clique le bouton jusqu'à ce que la gate tombe, PUIS fetch l'URL `/sources` demandée
 * DANS la WebView (cookies + JA3 navigateur) et renvoie le NDJSON.
 */
object AfterDarkResolver {
    private const val TAG = "AfterDarkResolver"

    // Page /video servant à déclencher l'ad-gate (n'importe quel film ; le déblocage est global).
    // À mettre à jour comme un domaine si l'UUID meurt.
    private const val GATE_PAGE = "https://afterdark06.mom/video/77de319e-8d60-58b3-c4a2-bce69678b15e"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchSourcesNdjson(sourcesUrl: String): String? = withTimeoutOrNull(45_000L) {
        // 1) pré-chauffer cf_clearance (WebViewResolver a l'injection stealth qui passe CF) → notre
        //    WebView brute charge ensuite avec le cookie déjà posé (CookieManager partagé) → pas de
        //    challenge, et le clic de gate peut se faire.
        runCatching {
            WebViewResolver(StreamFlixApp.instance).get(GATE_PAGE, silent = true, clearanceOnly = true, markerTimeoutMs = 12_000L)
        }.onFailure { Log.w(TAG, "warmup cf_clearance KO: ${it.message}") }
        Log.i(TAG, "gate flow → $sourcesUrl")
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String?> { cont ->
                val ctx = StreamFlixApp.instance
                var webView: WebView? = null
                var attachedRoot: android.view.ViewGroup? = null
                var finished = false

                fun finish(result: String?) {
                    if (finished) return
                    finished = true
                    runCatching {
                        attachedRoot?.removeView(webView)
                        webView?.stopLoading()
                        webView?.loadUrl("about:blank")
                        webView?.destroy()
                    }
                    attachedRoot = null
                    webView = null
                    if (cont.isActive) cont.resume(result)
                }

                val wv = WebView(ctx)
                webView = wv
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = WebViewResolver.STEALTH_UA
                    mediaPlaybackRequiresUserGesture = true
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                }
                wv.addJavascriptInterface(object {
                    @JavascriptInterface fun result(s: String?) {
                        runCatching { wv.post { finish(s) } }
                    }
                    @JavascriptInterface fun log(s: String?) { Log.d(TAG, "js: $s") }
                }, "AFD")

                val js = """
                    (function(){
                      try{ window.open=function(){return {closed:true,close:function(){},focus:function(){}};}; }catch(e){}
                      var clicks=0, ticks=0;
                      var target=${jsString(sourcesUrl)};
                      function grabAndDone(){
                        try{
                          fetch(target,{headers:{accept:'application/x-ndjson'}})
                            .then(function(r){return r.text();})
                            .then(function(t){ AFD.result(t); })
                            .catch(function(e){ AFD.result(''); });
                        }catch(e){ AFD.result(''); }
                      }
                      var iv=setInterval(function(){
                        ticks++;
                        var btn=null, bs=document.querySelectorAll('button');
                        for(var i=0;i<bs.length;i++){ if(/publicit/i.test(bs[i].innerText||'')){ btn=bs[i]; break; } }
                        if(btn){ btn.click(); clicks++; }
                        else if(clicks>=1){ clearInterval(iv); AFD.log('gate ok clicks='+clicks); grabAndDone(); }
                        if(ticks>50){ clearInterval(iv); AFD.log('timeout clicks='+clicks); grabAndDone(); }
                      }, 450);
                    })();
                """.trimIndent()

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.i(TAG, "onPageFinished: ${url?.take(60)}")
                        if (finished) return
                        // laisser React monter la gate avant d'injecter
                        wv.postDelayed({ if (!finished) wv.evaluateJavascript(js, null) }, 1500)
                    }
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        Log.w(TAG, "onReceivedError $errorCode $description ${failingUrl?.take(40)}")
                    }
                }

                // Attacher la WebView (invisible) à la fenêtre courante → sinon setInterval/clics ne
                // tournent pas (WebView sans surface). Fallback layout si pas d'activité.
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
                        wv.layout(0, 0, 1280, 720)
                        Log.w(TAG, "pas d'activité courante → WebView non attachée (timers peuvent ne pas tourner)")
                    }
                }

                val headers = HashMap<String, String>()
                headers["Referer"] = "https://afterdark06.mom/"
                wv.loadUrl(GATE_PAGE, headers)

                cont.invokeOnCancellation { runCatching { wv.post { finish(null) } } }
            }
        }
    }

    private fun jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
