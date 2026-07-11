package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Uqload (uqload.is/cx/co/to/net) -- extracteur Cronet-based.
 *
 * v85x 2026-05-20 (user "regarde a la source comment fonctionne le player") :
 *   Inspection Chrome DevTools confirmee :
 *   - PC Chrome 148 desktop UA = 200 OK sur master.m3u8
 *   - OPPO Cronet 131 mobile UA = 403
 *   - Le serveur filtre sur la combinaison UA + Sec-Fetch headers
 *
 *   Donc on essaie : UA Win64 Chrome 148 desktop (le MEME que PC) + headers
 *   Sec-Fetch-Dest: video (pour imiter une vraie video element), avec
 *   propagation des cookies set par l'embed fetch.
 */
class UqloadExtractor : Extractor() {
    override val name = "Uqload"
    override val mainUrl = "https://uqload.is"
    override val aliasUrls = listOf(
        "https://uqload.cx",
        "https://uqload.co",
        "https://uqload.to",
        "https://uqload.net",
    )

    override val cacheTtlMs: Long = 5L * 60L * 1000L

    companion object {
        private const val TAG = "UqloadExtractor"
        // v85x : UA desktop Win64 Chrome 148 — identique a PC Chrome qui marche.
        const val CHROME_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

        private val BLOCKED_WV = listOf(
            "googlesyndication", "doubleclick", "popads", "popunder", "popcash",
            "propellerads", "exoclick", "juicyads", "trafficjunky", "clickadu",
            "adsterra", "hilltopads", "histats", "google-analytics", "googletagmanager"
        )
        val sharedOkHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        Log.d(TAG, "extract() link=$link")
        val embedResult = fetchEmbedViaCronet(link)
            ?: fetchEmbedViaOkHttpDoH(link)

        if (embedResult == null) {
            // 2026-06-02 : Cronet + OkHttp+DoH ont echoue (typiquement HTTP 522
            //   Cloudflare = CF rejette le TLS fingerprint Cronet alors que le
            //   meme URL marche dans Chrome). Dernier recours : WebView, qui a
            //   le TLS fingerprint Chrome accepte par CF. La WebView intercepte
            //   directement le m3u8/mp4 du player, on bypass tout le pipeline
            //   html→unpack→regex.
            Log.w(TAG, "Cronet+DoH failed, fallback to WebView for $link")
            val directUrl = extractViaWebView(link)
                ?: throw Exception("Uqload: embed fetch failed (Cronet+DoH+WebView) for $link")
            val isHls = directUrl.contains(".m3u8", ignoreCase = true)
            Log.d(TAG, "WebView extracted: $directUrl (hls=$isHls)")
            return@withContext Video(
                source = directUrl,
                headers = mapOf(
                    "Referer" to link,
                    "User-Agent" to CHROME_DESKTOP_UA,
                    "Origin" to "https://uqload.is",
                ),
                type = if (isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
            )
        }
        val fullHtml = embedResult.first
        val cookies = embedResult.second
        Log.d(TAG, "Embed fetched via Cronet: ${fullHtml.length} bytes, cookies=${cookies.take(200)}")

        val packedJs = Regex("(eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?)</script>")
            .find(fullHtml)?.groupValues?.get(1)
        val unpacked = packedJs?.let { JsUnpacker(it).unpack() }
        var sourceUrl: String? = null
        if (unpacked != null) {
            Log.d(TAG, "Packed JS unpacked length=${unpacked.length}")
            val hlsRegex = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            val mp4Regex = Regex("""file\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""")
            val genRegex = Regex("""file\s*:\s*["'](https?://[^"']+)["']""")
            sourceUrl = hlsRegex.find(unpacked)?.groupValues?.get(1)
                ?: mp4Regex.find(unpacked)?.groupValues?.get(1)
                ?: genRegex.find(unpacked)?.groupValues?.get(1)
        }

        if (sourceUrl == null) {
            val patterns = listOf(
                Regex("""sources:\s*\["([^"]+)"\]"""),
                Regex("""file\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
                Regex("""file\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
            )
            for (p in patterns) {
                val m = p.find(fullHtml)
                if (m != null) { sourceUrl = m.groupValues[1]; break }
            }
        }

        if (sourceUrl == null) {
            // Page vide / "video indisponible" (typiquement ~931 octets) = ce
            //   miroir est mort. On echoue VITE pour que getVideo passe au
            //   miroir suivant, au lieu de perdre 25s dans un WebView inutile :
            //   le 931 est un vrai lien supprime (identique tel + Chromecast),
            //   pas un blocage device. Le fallback multi-miroirs gere le reste.
            Log.w(TAG, "Uqload embed sans source (${fullHtml.length}b) -> miroir mort, skip rapide")
            throw Exception("Uqload: could not extract source URL from embed page")
        }
        val isHls = sourceUrl.contains(".m3u8", ignoreCase = true)
        val vParam = Regex("[?&]v=([^&]+)").find(sourceUrl)?.groupValues?.get(1)
        Log.d(TAG, "Final URL: $sourceUrl (hls=$isHls, v=$vParam)")

        val headers = mutableMapOf(
            "Referer" to link,
            "User-Agent" to CHROME_DESKTOP_UA,
            "Origin" to "https://uqload.is",
            "Accept" to "*/*",
            "Accept-Language" to "fr-FR,fr;q=0.9,en;q=0.8",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "same-site",
            "Sec-Ch-Ua" to "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not_A Brand\";v=\"99\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
        )
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies
        }
        Video(
            source = sourceUrl,
            headers = headers,
            type = if (isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
        )
    }

    /** Fallback quand Cronet ne joint pas uqload (ex: Chromecast IPv6 cassé) :
     *  OkHttp + DoH Cloudflare (resout en IPv4 joignable). */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractViaWebView(url: String): String? =
        withContext(Dispatchers.Main) {
            kotlinx.coroutines.withTimeoutOrNull(25_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    var resolved = false
                    var wv: android.webkit.WebView? = null
                    fun done(v: String?) {
                        if (!resolved && cont.isActive) {
                            resolved = true
                            try { wv?.stopLoading(); wv?.destroy() } catch (_: Throwable) {}
                            cont.resume(v)
                        }
                    }
                    val ctx = StreamFlixApp.instance.applicationContext
                    val webView = android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = CHROME_DESKTOP_UA
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                    }
                    wv = webView
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    webView.webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onCreateWindow(v: android.webkit.WebView?, dl: Boolean, ug: Boolean, m: android.os.Message?): Boolean = false
                    }
                    webView.webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            val h = request.url?.host ?: ""
                            if (BLOCKED_WV.any { h.contains(it) }) return android.webkit.WebResourceResponse("text/plain", "utf-8", null)
                            if (u.contains(".m3u8", true) || (u.contains(".mp4", true) && !u.contains("/ad", true) && !u.contains(".js", true))) {
                                Log.d(TAG, "WebView intercepted media: $u")
                                view?.post { done(u) }
                                return android.webkit.WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return null
                        }
                        override fun onPageFinished(view: android.webkit.WebView?, u: String?) {
                            if (resolved) return
                            view?.evaluateJavascript("(function(){try{var v=document.querySelector('video');if(v){v.muted=true;v.play();}var b=document.querySelector('.play,#vplayer,.vjs-big-play-button,.jw-icon-display,#player_play,.plyr__control--overlaid');if(b)b.click();}catch(e){}})();", null)
                            view?.postDelayed({ if (!resolved) done(null) }, 18_000L)
                        }
                    }
                    val wrapper = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>*{margin:0;padding:0}html,body,iframe{width:100%;height:100%;border:0;background:#000}</style></head><body><iframe src=\"" + url + "\" allow=\"autoplay;fullscreen;encrypted-media\" allowfullscreen referrerpolicy=\"origin\"></iframe></body></html>"
                    webView.loadDataWithBaseURL("https://dessinanime.cc/", wrapper, "text/html", "UTF-8", null)
                    cont.invokeOnCancellation {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try { webView.stopLoading(); webView.destroy() } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }

    private fun fetchEmbedViaOkHttpDoH(url: String): Pair<String, String>? {
        return try {
            val client = OkHttpClient.Builder()
                .dns(com.streamflixreborn.streamflix.utils.DnsResolver.doh)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", CHROME_DESKTOP_UA)
                .header("Referer", "https://dessinanime.cc/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                .build()
            client.newCall(req).execute().use { resp ->
                val setCookies = resp.headers("Set-Cookie").joinToString("; ") { it.substringBefore(";").trim() }
                val body = resp.body?.string()
                Log.d(TAG, "Embed via OkHttp+DoH: code=${resp.code} bytes=${body?.length ?: 0}")
                if (!resp.isSuccessful || body == null) null else Pair(body, setCookies)
            }
        } catch (e: Exception) {
            Log.w(TAG, "OkHttp+DoH embed fetch failed: ${e.message}")
            null
        }
    }

    private fun fetchEmbedViaCronet(url: String): Pair<String, String>? {
        val engine = StreamFlixApp.getCronetEngine(
            StreamFlixApp.instance.applicationContext
        ) ?: return null.also { Log.e(TAG, "Cronet engine not available") }
        val urlObj = URL(url)
        val openConn = engine.javaClass.getMethod("openConnection", URL::class.java)
        val conn = openConn.invoke(engine, urlObj) as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            // v85x : EXACT headers as PC Chrome 148 desktop sends
            conn.setRequestProperty("User-Agent", CHROME_DESKTOP_UA)
            conn.setRequestProperty("Referer", "https://dessinanime.cc/")
            conn.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
            )
            conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            // NE PAS forcer Accept-Encoding : si on le met manuellement, Cronet
            //   renvoie le flux compresse SANS le decompresser -> la lecture du
            //   body bloque jusqu'au readTimeout (constate sur Chromecast: 200 OK
            //   en 0.6s puis body stalle 12s). Laisse Cronet gerer + decompresser.
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1")
            conn.setRequestProperty("Sec-Fetch-Dest", "document")
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate")
            conn.setRequestProperty("Sec-Fetch-Site", "cross-site")
            conn.setRequestProperty("Sec-Fetch-User", "?1")
            conn.setRequestProperty(
                "Sec-Ch-Ua",
                "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not_A Brand\";v=\"99\""
            )
            conn.setRequestProperty("Sec-Ch-Ua-Mobile", "?0")
            conn.setRequestProperty("Sec-Ch-Ua-Platform", "\"Windows\"")
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            val code = conn.responseCode
            Log.d(TAG, "Cronet embed responseCode=$code")
            if (code !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
                Log.e(TAG, "Cronet embed HTTP $code body=$errBody")
                return null
            }
            val cookieParts = mutableListOf<String>()
            for (i in 0 until 50) {
                val key = conn.getHeaderFieldKey(i) ?: continue
                val value = conn.getHeaderField(i) ?: continue
                if (key.equals("Set-Cookie", ignoreCase = true)) {
                    val nv = value.substringBefore(";").trim()
                    if (nv.isNotEmpty()) cookieParts.add(nv)
                    Log.d(TAG, "Set-Cookie received: ${value.take(200)}")
                }
            }
            val cookies = cookieParts.joinToString("; ")
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            return Pair(html, cookies)
        } catch (e: Exception) {
            // Timeout / reset pendant la lecture : on renvoie null pour que
            //   extract() bascule sur le fallback OkHttp+DoH au lieu de crasher.
            Log.w(TAG, "Cronet embed read failed: ${e.message}")
            return null
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}
