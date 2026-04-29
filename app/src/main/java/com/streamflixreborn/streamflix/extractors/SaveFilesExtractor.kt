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
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import retrofit2.http.Url
import java.net.URL
import kotlin.coroutines.resume

class SaveFilesExtractor : Extractor() {

    override val name = "Savefiles"
    override val mainUrl = "https://savefiles.com/"
    override val aliasUrls = listOf("https://streamhls.to")

    companion object {
        private const val TAG = "SaveFilesExtractor"

        private val AD_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice",
            "popads", "popunder", "popcash", "propellerads",
            "juicyads", "exoclick", "trafficjunky",
            "searchresultsworld"
        )
    }

    private val context = StreamFlixApp.instance.applicationContext

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract() link=$link")
        val baseUrl = URL(link).protocol + "://" + URL(link).host
        val referer = "$baseUrl/"

        // Strategy 1: Quick OkHttp fetch + parse (10s max)
        val okHttpResult = withTimeoutOrNull(10_000) {
            try {
                val service = Extractor.createJsoupService<Service>(baseUrl, referer)
                val document = service.getSource(
                    url = link,
                    referer = referer,
                    userAgent = Extractor.DEFAULT_USER_AGENT
                )
                val html = document.toString()
                Log.d(TAG, "HTML fetched, length=${html.length}")

                // 1a: Direct m3u8 in page (JWPlayer setup, sources array, etc.)
                val m3u8Direct = findM3u8InHtml(html)
                if (m3u8Direct != null) {
                    Log.d(TAG, "M3U8 found directly in HTML: $m3u8Direct")
                    return@withTimeoutOrNull m3u8Direct
                }

                // 1b: Packed JS (eval(function(p,a,c,k,e,d)...))
                val packedJS = Regex("(eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?)</script>")
                    .find(html)?.groupValues?.get(1)
                if (packedJS != null) {
                    Log.d(TAG, "Packed JS found, length=${packedJS.length}")
                    val unpacked = JsUnpacker(packedJS).unpack()
                    if (unpacked != null) {
                        Log.d(TAG, "Unpacked JS: ${unpacked.take(300)}")
                        val m3u8 = findM3u8InHtml(unpacked)
                        if (m3u8 != null) {
                            Log.d(TAG, "M3U8 from packed JS: $m3u8")
                            return@withTimeoutOrNull m3u8
                        }
                    }
                }

                // 1c: Try the old /dl API as fallback
                try {
                    val pathParts = URL(link).path.split("/").filter { it.isNotEmpty() }
                    val fileCode = pathParts.last().split("?")[0].trim()
                    if (fileCode.isNotEmpty()) {
                        val dlService = Extractor.createJsoupService<DlService>(baseUrl)
                        val dlDoc = dlService.getDl(
                            op = "embed",
                            fileCode = fileCode,
                            auto = "0",
                            referer = ""
                        )
                        val dlHtml = dlDoc.toString()
                        val m3u8 = findM3u8InHtml(dlHtml)
                        if (m3u8 != null) {
                            Log.d(TAG, "M3U8 from /dl API: $m3u8")
                            return@withTimeoutOrNull m3u8
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "/dl API fallback failed: ${e.message}")
                }

                Log.d(TAG, "No stream found via OkHttp")
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
        val hlsUrl = interceptM3u8WithWebView(link)
        if (hlsUrl != null) {
            Log.d(TAG, "M3U8 from WebView: $hlsUrl")
            return buildVideo(hlsUrl, "https://${URL(link).host}/")
        }

        throw Exception("SaveFiles: Could not find stream URL in page: $link")
    }

    private fun findM3u8InHtml(text: String): String? {
        // Try JWPlayer sources format
        Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]*\.m3u8[^'"]*)['"]""")
            .find(text)?.let { return it.groupValues[1] }

        // Try file: "url" format
        Regex("""file\s*:\s*['"]([^'"]*\.m3u8[^'"]*)['"]""")
            .find(text)?.let { return it.groupValues[1] }

        // Try source/src: "url" format
        Regex("""(?:source|src)\s*:\s*['"]([^'"]*\.m3u8[^'"]*)['"]""")
            .find(text)?.let { return it.groupValues[1] }

        // Try hls variants (hls, hls2, hls4, etc.)
        Regex("""["']?hls\d*["']?\s*[:=]\s*["']([^"']*\.m3u8[^"']*)["']""")
            .find(text)?.let { return it.groupValues[1] }

        // Generic m3u8 URL
        Regex("""['"]([^'"]*\.m3u8[^'"]*)['"]""")
            .find(text)?.let { return it.groupValues[1] }

        return null
    }

    private fun buildVideo(hlsUrl: String, referer: String): Video {
        return Video(
            source = hlsUrl,
            headers = mapOf(
                "Referer" to referer,
                "Origin" to referer.trimEnd('/'),
                "User-Agent" to Extractor.DEFAULT_USER_AGENT
            )
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun interceptM3u8WithWebView(url: String): String? =
        withContext(Dispatchers.Main) {
            val host = Uri.parse(url).host ?: "savefiles.com"
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
                        settings.userAgentString = Extractor.DEFAULT_USER_AGENT
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

                            if (reqUrl.contains(".m3u8")) {
                                Log.d(TAG, "M3U8 intercepted from WebView: $reqUrl")
                                view?.post { resolve(reqUrl) }
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            val reqHost = request.url?.host ?: ""
                            if (AD_HOSTS.any { reqHost.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return
                            Log.d(TAG, "WebView page finished: $finishedUrl")

                            view.postDelayed({
                                if (resolved) return@postDelayed
                                view.evaluateJavascript("""
                                    (function() {
                                        try {
                                            var scripts = document.querySelectorAll('script');
                                            for (var i = 0; i < scripts.length; i++) {
                                                var text = scripts[i].textContent;
                                                if (text && text.indexOf('m3u8') > -1) {
                                                    var match = text.match(/['"]([^'"]*\.m3u8[^'"]*)['"]/);
                                                    if (match) return match[1];
                                                }
                                            }
                                        } catch(e) {}
                                        return null;
                                    })();
                                """.trimIndent()) { result ->
                                    val m3u8 = result?.trim()
                                        ?.removeSurrounding("\"")
                                        ?.takeIf { it != "null" && it.contains(".m3u8") }
                                    if (m3u8 != null) {
                                        Log.d(TAG, "M3U8 from WebView JS: $m3u8")
                                        resolve(m3u8)
                                    }
                                }
                            }, 3000)

                            view.postDelayed({
                                if (!resolved) {
                                    Log.d(TAG, "WebView timeout - no M3U8 found")
                                    resolve(null)
                                }
                            }, 15000)
                        }
                    }

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
        @GET
        suspend fun getSource(
            @Url url: String,
            @Header("Referer") referer: String = "",
            @Header("User-Agent") userAgent: String = Extractor.DEFAULT_USER_AGENT
        ): Document
    }

    private interface DlService {
        @GET("dl")
        suspend fun getDl(
            @Query("op") op: String,
            @Query("file_code") fileCode: String,
            @Query("auto") auto: String,
            @Query("referer") referer: String
        ): Document
    }
}
