package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import org.jsoup.nodes.Document
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.regex.Pattern

open class VidMoLyExtractor : Extractor() {
    override val name = "VidMoLy"
    override val mainUrl = "https://vidmoly.me/"
    override val aliasUrls = listOf("https://vidmoly.net", "https://vidmoly.biz")
    private val redirectUrl = "https://vidmoly.to/"
    val context = StreamFlixApp.instance.applicationContext

    override suspend fun extract(link: String): Video {
        val normalizedLink = link.replace(Regex("vidmoly\\.(me|net|biz)"), "vidmoly.to")

        // Use WebView to resolve JS redirects and get the final page URL
        val resolvedUrl = resolveRedirectWithWebView(context, normalizedLink, redirectUrl)

        val service = Service.build(redirectUrl)
        val document = service.get(resolvedUrl, redirectUrl)

        val hlsUrl = extractHlsUrl(document)
            ?: throw Exception("Could not find HLS source in the webpage")

        return Video(
            source = hlsUrl,
            headers = mapOf(
                "Referer" to redirectUrl,
                "User-Agent" to USER_AGENT
            )
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveRedirectWithWebView(context: Context, url: String, mainUrl: String): String =
        withContext(Dispatchers.Main) {
            val result = withTimeoutOrNull(15000) {
                suspendCancellableCoroutine { cont ->
                    val webView = WebView(context)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val newUrl = request?.url.toString()
                            if (newUrl.contains("vidmoly") && newUrl.contains("ch=")) {
                                // This is the JWT redirect, let it load
                                return false
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (finishedUrl != null && finishedUrl.contains("vidmoly") && finishedUrl.contains("ch=")) {
                                if (cont.isActive) cont.resume(finishedUrl)
                                webView.destroy()
                            }
                        }
                    }

                    webView.loadUrl(url)

                    cont.invokeOnCancellation {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
            result ?: url
        }

    private fun extractHlsUrl(document: Document): String? {
        val html = document.toString()

        val patterns = listOf(
            Pattern.compile("sources:\\s*\\[\\{\\s*file:\\s*\"([^\"]+)\""),
            Pattern.compile("sources:\\s*\\[\\{\\s*src:\\s*\"([^\"]+)\""),
            Pattern.compile("file:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\""),
            Pattern.compile("src:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\""),
            Pattern.compile("\"(https?://[^\"]+\\.m3u8[^\"]*)\""),
            Pattern.compile("source\\s*=\\s*['\"]([^'\"]+\\.m3u8[^'\"]*)"),
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val url = matcher.group(1)
                if (url != null && (url.contains(".m3u8") || url.startsWith("http"))) {
                    return url
                }
            }
        }

        return null
    }

    class ToDomain: VidMoLyExtractor(){
        override val mainUrl: String = "https://vidmoly.to/"
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(OkHttpClient.Builder().build())
                .addConverterFactory(JsoupConverterFactory.create())
                .build()
                .create(Service::class.java)
        }

        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("Accept") accept: String = "text/html",
            @Header("User-Agent") userAgent: String = USER_AGENT,
        ): Document
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
