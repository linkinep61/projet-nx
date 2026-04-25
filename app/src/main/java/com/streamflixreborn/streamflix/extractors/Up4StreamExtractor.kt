package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Message
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.JsUnpacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class Up4StreamExtractor : Extractor() {
    override val name = "Up4Stream"
    override val mainUrl = "https://up4fun.top"
    override val aliasUrls = listOf(
        "https://up4stream.com"
    )

    companion object {
        private const val TAG = "Up4StreamExtractor"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val AD_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice",
            "popads", "popunder", "popcash", "propellerads",
            "juicyads", "exoclick", "trafficjunky",
            "bvtpk.com", "clksite.com", "clkurl.com",
            "searchresultsworld"
        )
    }

    private val context = StreamFlixApp.instance.applicationContext

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract() link=$link")
        val host = Uri.parse(link).host ?: "up4fun.top"
        val referer = "https://$host/"

        // Strategy 1: Quick OkHttp fetch + parse (10s max)
        val okHttpResult = withTimeoutOrNull(10_000) {
            try {
                val baseUrl = URL(link).protocol + "://" + URL(link).host
                val service = Service.build(baseUrl)
                val document = service.getSource(
                    url = link,
                    referer = referer,
                    userAgent = USER_AGENT
                )
                val html = document.toString()
                Log.d(TAG, "HTML fetched, length=${html.length}")

                // 1a: Look for packed JS (eval(function(p,a,c,k,e,d)...))
                val packedJS = Regex("(eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?)</script>")
                    .find(html)?.groupValues?.get(1)
                if (packedJS != null) {
                    Log.d(TAG, "Packed JS found, length=${packedJS.length}")
                    val unpacked = JsUnpacker(packedJS).unpack()
                    if (unpacked != null) {
                        Log.d(TAG, "Unpacked JS: ${unpacked.take(300)}")
                        val m3u8 = findM3u8InText(unpacked)
                        if (m3u8 != null) {
                            Log.d(TAG, "M3U8 from packed JS: $m3u8")
                            return@withTimeoutOrNull m3u8
                        }
                        val mp4 = findMp4InText(unpacked)
                        if (mp4 != null) {
                            Log.d(TAG, "MP4 from packed JS: $mp4")
                            return@withTimeoutOrNull mp4
                        }
                    }
                }

                // 1b: Direct m3u8 in page HTML
                val m3u8Direct = findM3u8InText(html)
                if (m3u8Direct != null) {
                    Log.d(TAG, "M3U8 found directly in HTML: $m3u8Direct")
                    return@withTimeoutOrNull m3u8Direct
                }

                // 1c: Direct MP4 in page HTML
                val mp4Direct = findMp4InText(html)
                if (mp4Direct != null) {
                    Log.d(TAG, "MP4 found directly in HTML: $mp4Direct")
                    return@withTimeoutOrNull mp4Direct
                }

                Log.d(TAG, "No stream found via OkHttp, HTML preview: ${html.take(500)}")
                null
            } catch (e: Exception) {
                Log.e(TAG, "OkHttp fetch failed: ${e.message}")
                null
            }
        }

        if (okHttpResult != null) {
            return buildVideo(okHttpResult, referer)
        }

        // Strategy 2: WebView interception (handles JS-rendered content)
        Log.d(TAG, "OkHttp timed out or failed, trying WebView...")
        val hlsUrl = interceptWithWebView(link)
        if (hlsUrl != null) {
            Log.d(TAG, "Stream from WebView: $hlsUrl")
            return buildVideo(hlsUrl, referer)
        }

        throw Exception("Up4Stream: Could not find HLS source in page: $link")
    }

    private fun findM3u8InText(text: String): String? {
        // JWPlayer sources format
        Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]*\.m3u8[^'"]*)['"]""")
            .find(text)?.let { return it.groupValues[1] }

        // file: "url" format
        Regex("""file\s*:\s*['"]([^'"]*\.m3u8[^'"]*)['"]""")
            .find(text)?.let { return it.groupValues[1] }

        // source/src: "url" format
        Regex("""(?:source|src)\s*:\s*['"]([^'"]*\.m3u8[^'"]*)['"]""")
            .find(text)?.let { return it.groupValues[1] }

        // hls variants
        Regex("""["']?hls\d*["']?\s*[:=]\s*["']([^"']*\.m3u8[^"']*)["']""")
            .find(text)?.let { return it.groupValues[1] }

        // Generic m3u8 URL
        Regex("""['"]([^'"]*\.m3u8[^'"]*)['"]""")
            .find(text)?.let { return it.groupValues[1] }

        return null
    }

    private fun findMp4InText(text: String): String? {
        // file: "url.mp4" format (common in video hosts)
        Regex("""file\s*:\s*['"]([^'"]*\.mp4[^'"]*)['"]""")
            .find(text)?.let { return it.groupValues[1] }

        // source/src with mp4
        Regex("""(?:source|src)\s*:\s*['"]([^'"]*\.mp4[^'"]*)['"]""")
            .find(text)?.let { return it.groupValues[1] }

        return null
    }

    private fun buildVideo(sourceUrl: String, referer: String): Video {
        return Video(
            source = sourceUrl,
            headers = mapOf(
                "Referer" to referer,
                "Origin" to referer.trimEnd('/'),
                "User-Agent" to USER_AGENT
            )
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun interceptWithWebView(url: String): String? =
        withContext(Dispatchers.Main) {
            val host = Uri.parse(url).host ?: "up4fun.top"
            val referer = "https://$host/"

            withTimeoutOrNull(20_000) {
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
                        settings.userAgentString = USER_AGENT
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                    }

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean,
                            isUserGesture: Boolean, resultMsg: Message?
                        ): Boolean = false
                    }

                    webView.webViewClient = object : WebViewClient() {

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null

                            // Intercept m3u8 or mp4 stream requests
                            if (reqUrl.contains(".m3u8") || (reqUrl.contains(".mp4") && !reqUrl.contains(".mp4?"))) {
                                Log.d(TAG, "Stream intercepted from WebView: $reqUrl")
                                view?.post { resolve(reqUrl) }
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            // Block ad domains
                            val reqHost = request.url?.host ?: ""
                            if (AD_HOSTS.any { reqHost.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return
                            Log.d(TAG, "WebView page finished: $finishedUrl")

                            // JS extraction backup after page loads
                            view.postDelayed({
                                if (resolved) return@postDelayed
                                view.evaluateJavascript("""
                                    (function() {
                                        try {
                                            var scripts = document.querySelectorAll('script');
                                            for (var i = 0; i < scripts.length; i++) {
                                                var text = scripts[i].textContent;
                                                if (text && (text.indexOf('m3u8') > -1 || text.indexOf('.mp4') > -1)) {
                                                    var match = text.match(/['"]([^'"]*\.m3u8[^'"]*)['"]/);
                                                    if (match) return match[1];
                                                    match = text.match(/file\s*:\s*['"]([^'"]*\.mp4[^'"]*)['"]/);
                                                    if (match) return match[1];
                                                }
                                            }
                                        } catch(e) {}
                                        return null;
                                    })();
                                """.trimIndent()) { result ->
                                    val stream = result?.trim()
                                        ?.removeSurrounding("\"")
                                        ?.takeIf { it != "null" && (it.contains(".m3u8") || it.contains(".mp4")) }
                                    if (stream != null) {
                                        Log.d(TAG, "Stream from WebView JS: $stream")
                                        resolve(stream)
                                    }
                                }
                            }, 3000)

                            // Final timeout
                            view.postDelayed({
                                if (!resolved) {
                                    Log.d(TAG, "WebView timeout - no stream found")
                                    resolve(null)
                                }
                            }, 15000)
                        }
                    }

                    Log.d(TAG, "Loading URL in WebView: $url")
                    webView.loadUrl(url, mapOf("Referer" to referer))

                    cont.invokeOnCancellation {
                        resolved = true
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
        }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                val retrofit = Retrofit.Builder()
                    .baseUrl("$baseUrl/")
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                return retrofit.create(Service::class.java)
            }
        }

        @GET
        suspend fun getSource(
            @Url url: String,
            @Header("Referer") referer: String = "",
            @Header("User-Agent") userAgent: String = USER_AGENT
        ): Document
    }
}
