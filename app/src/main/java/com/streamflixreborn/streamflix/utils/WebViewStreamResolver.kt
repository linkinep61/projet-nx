package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WebViewStreamResolver — résolveur de flux générique basé sur WebView headless.
 *
 * 2026-06-24 — créé après reverse engineering de Wiseplay 8.5.3 (= APK pulled
 * du Honor). Wiseplay utilise la lib open-source `vihosts` qui expose 2
 * parsers : HTML (statique = fetch + regex) et WEB (WebView headless qui
 * intercepte les XHR/redirects JS). Pour les playlists 3box-tv, certaines
 * chaînes (c9v3.s.gy/me/..., c9v3.s.gy/aDqr3F/..., textup.fr streams,
 * scailhol.free.fr) construisent leur URL m3u8 finale via JavaScript (XHR +
 * setTimeout). Un simple follow-redirect HTTP (= GenericStreamResolver) ne
 * suffit pas car le JS n'est jamais exécuté côté serveur.
 *
 * Ce resolver lance une WebView, charge l'URL d'entrée, attend que le JS
 * tourne, et capte la 1re requête sortante vers un .m3u8 / .mpd / .ts.
 * C'est exactement ce que Wiseplay fait via `HostParser.WEB` (= classe
 * vihosts.vp.a dans le DEX). Ça prend 5-12s (= "LCI très lent" sur Wiseplay
 * confirmé par le user).
 *
 * Inspiré directement de YflixExtractor.extractByIntercepting qui utilise le
 * même pattern. Différence : ici c'est purement passif (= aucun click JS),
 * on attend juste que la page initie sa requête XHR vers le CDN.
 *
 * Usage :
 *   val result = WebViewStreamResolver.resolve(
 *       url = "https://c9v3.s.gy/aDqr3F/.../#TF1",
 *       referer = "https://wiseplayapp.com/",
 *       timeoutMs = 12_000L,
 *   )
 *   result?.let { Video(source = it.url, headers = it.headers) }
 */
object WebViewStreamResolver {

    data class Resolved(
        val url: String,
        val headers: Map<String, String>,
    )

    private const val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    // Hosts pub/tracker à bloquer — évite que la WebView se charge inutilement
    // avec des requêtes ad-tech qui peuvent retarder ou poisson la résolution.
    private val BLOCKED_HOSTS = listOf(
        "googlesyndication", "doubleclick", "adservice",
        "popads", "popunder", "popcash", "propellerads",
        "exoclick", "juicyads", "trafficjunky",
        "googletagmanager", "google-analytics",
        "sharethis.com", "platform-api.sharethis.com",
        "translate-pa.googleapis.com", "translate.google.com",
        "facebook.net", "fbcdn.net",
    )

    // Extensions qui marquent un stream live (= ce qu'on cherche à capter)
    private val STREAM_EXTS = listOf(".m3u8", ".mpd", "playlist.m3u8", "manifest.mpd")

    // Hosts blacklistés en tant que cibles finales (faux positifs connus)
    // — ex. doubleclick peut servir un .m3u8 publicitaire qu'on ne veut PAS.
    private val POISONED_TARGET_HOSTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleads.g.doubleclick.net",
        "ads.youtube.com", "imasdk.googleapis.com",
    )

    /**
     * Résout l'URL [entryUrl] en suivant les redirects JS jusqu'à capter le
     * 1er stream (.m3u8/.mpd/.ts). Retourne null si rien capté avant
     * [timeoutMs] ms.
     */
    suspend fun resolve(
        entryUrl: String,
        referer: String? = null,
        userAgent: String = ANDROID_CHROME_UA,
        timeoutMs: Long = 12_000L,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Resolved? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeoutMs) {
            doResolve(entryUrl, referer, userAgent, extraHeaders)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun doResolve(
        entryUrl: String,
        referer: String?,
        userAgent: String,
        extraHeaders: Map<String, String>,
    ): Resolved? = suspendCancellableCoroutine { cont ->
        val context = StreamFlixApp.instance.applicationContext
        var resolved = false

        fun resolve(value: Resolved?) {
            if (!resolved && cont.isActive) {
                resolved = true
                cont.resumeWith(Result.success(value))
            }
        }

        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = userAgent
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            // Désactive le rendering visible pour économiser la GPU
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: Message?
            ): Boolean = false
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val host = request?.url?.host ?: return true
                // Bloque uniquement les pubs ; tout le reste passe (= peut-être
                // un redirect vers le vrai host CDN qu'on veut suivre).
                return BLOCKED_HOSTS.any { host.contains(it) }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                val host = request.url?.host ?: ""

                // 1. Cible trouvée ?
                val isStream = STREAM_EXTS.any { reqUrl.contains(it, ignoreCase = true) }
                if (isStream && !POISONED_TARGET_HOSTS.any { host.contains(it) }) {
                    android.util.Log.d(
                        "WebViewStreamResolver",
                        "stream INTERCEPTED: $reqUrl",
                    )
                    // Headers : on lit ceux que la WebView a envoyés (= avec
                    // tous les cookies/sessions JS appliqués) + on ajoute Referer
                    // qui n'est pas exposé par WebResourceRequest.requestHeaders.
                    val capturedHeaders = HashMap<String, String>()
                    request.requestHeaders?.let { h ->
                        capturedHeaders.putAll(h)
                    }
                    if (!capturedHeaders.containsKey("User-Agent")) {
                        capturedHeaders["User-Agent"] = userAgent
                    }
                    if (!capturedHeaders.containsKey("Referer")) {
                        // Referer = la page en cours dans la WebView (= entryUrl
                        // ou sa redirection — view.url est le plus à jour).
                        val ref = view?.url ?: referer ?: entryUrl
                        capturedHeaders["Referer"] = ref
                    }
                    view?.post {
                        resolve(Resolved(url = reqUrl, headers = capturedHeaders))
                    }
                    // On renvoie une réponse vide pour empêcher la WebView de
                    // continuer à télécharger le stream inutilement.
                    return WebResourceResponse("text/plain", "utf-8", null)
                }

                // 2. Block pub/tracker
                if (BLOCKED_HOSTS.any { host.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                return null
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                // Ignoré silencieusement — les sous-frames peuvent crasher sans
                // que la page principale échoue.
            }
        }

        val headers = HashMap<String, String>()
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        headers.putAll(extraHeaders)
        webView.loadUrl(entryUrl, headers)

        cont.invokeOnCancellation {
            resolved = true
            // 2026-05-11 (cf YflixExtractor) : les méthodes WebView DOIVENT
            // tourner sur le main thread. Le handler de cancellation peut
            // fire depuis DefaultExecutor → wrap obligatoire sinon crash fatal.
            Handler(Looper.getMainLooper()).post {
                try { webView.stopLoading() } catch (_: Throwable) {}
                try { webView.destroy() } catch (_: Throwable) {}
            }
        }
    }
}
