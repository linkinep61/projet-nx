package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 2026-07-23 — AnonMP4 (anonmp4.help/embed/<id>).
 *
 * Player ArtPlayer + Hls.js dont l'URL m3u8 est protégée par un module **WebAssembly** (le HTML brut
 * ne contient qu'un blob base64 `\0asm` ; `playM3u8` décode l'URL au runtime). Impossible à calculer
 * en Kotlin → on charge l'embed dans une **WebView ATTACHÉE** (sinon le WASM/JS ne tourne pas), on
 * laisse le player résoudre, et on **capture l'URL m3u8** (config `times` / requêtes Hls).
 *
 * Le m3u8 est un media playlist standard `m3cloud.shadowcloud.<tld>/hls/playlist/<token>.m3u8` dont les
 * segments sont du **TS BRUT déguisé en `.webp`** (octet de sync 0x47, pas d'en-tête image) → ExoPlayer
 * les joue directement en HLS (aucun DataSource custom nécessaire, contrairement à SeekStream/PNG).
 */
class AnonMp4Extractor : Extractor() {
    override val name = "AnonMP4"
    override val mainUrl = "https://anonmp4.help"
    override val aliasUrls = listOf("https://anonmp4.com", "https://anonmp4.net")

    override suspend fun extract(link: String): Video {
        val m3u8 = resolveM3u8(link)
            ?: throw Exception("AnonMP4: URL m3u8 non capturée pour $link")
        val headers = hashMapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to WebViewResolver.STEALTH_UA,
        )
        return Video(
            source = m3u8,
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            headers = headers,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveM3u8(embedUrl: String): String? = withTimeoutOrNull(25_000L) {
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
                    if (cont.isActive) cont.resume(result?.takeIf { it.startsWith("http") })
                }

                val wv = WebView(ctx)
                webView = wv
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = WebViewResolver.STEALTH_UA
                    mediaPlaybackRequiresUserGesture = true
                }
                wv.addJavascriptInterface(object {
                    @JavascriptInterface fun result(s: String?) { runCatching { wv.post { finish(s) } } }
                    @JavascriptInterface fun log(s: String?) { Log.d(TAG, "js: $s") }
                }, "AMP")

                val js = """
                    (function(){
                      var done=false;
                      function report(u){
                        if(done||!u) return;
                        var s=''+u;
                        if(!/shadowcloud|m3cloud|\.m3u8/i.test(s)) return;
                        var m=s.match(/https?:\/\/[^"'\s\\]+\.m3u8[^"'\s\\]*/i);
                        if(m){ done=true; AMP.log('m3u8 capté'); AMP.result(m[0]); }
                      }
                      try{ var of=window.fetch; window.fetch=function(){var a=arguments[0]; report(typeof a==='string'?a:(a&&a.url)); return of.apply(this,arguments);}; }catch(e){}
                      try{ var oo=XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open=function(m,u){ report(u); return oo.apply(this,arguments);}; }catch(e){}
                      var ticks=0;
                      var iv=setInterval(function(){
                        ticks++;
                        try{ for(var k in window){ var o; try{o=window[k];}catch(e){continue;}
                          if(o&&typeof o==='object'){ for(var kk in o){ try{ var v=o[kk];
                            if(typeof v==='string'&&/shadowcloud|m3cloud/i.test(v)){ report(v); } }catch(e){} } } } }catch(e){}
                        if(done||ticks>55){ clearInterval(iv); if(!done){ AMP.log('timeout'); AMP.result(''); } }
                      }, 400);
                    })();
                """.trimIndent()

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (finished) return
                        wv.postDelayed({ if (!finished) wv.evaluateJavascript(js, null) }, 700)
                    }
                }

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
                    }
                }

                val headers = HashMap<String, String>()
                headers["Referer"] = "$mainUrl/"
                wv.loadUrl(embedUrl, headers)

                cont.invokeOnCancellation { runCatching { wv.post { finish(null) } } }
            }
        }
    }

    companion object { private const val TAG = "AnonMp4Extractor" }
}
