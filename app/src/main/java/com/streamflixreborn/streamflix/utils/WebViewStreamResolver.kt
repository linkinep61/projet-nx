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
        // 2026-07-29 : Yandex Metrica (mc.yandex.com/.ru) — spamme des requêtes de tracking dont
        //   l'URL contient le titre « ….mp4 » en paramètre → faux positif de capture. Bloqué.
        "yandex.com", "yandex.ru", "mc.yandex",
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
        // 2026-07-29 : certains lecteurs (embed4me) servent un MP4 progressif, pas du HLS. On peut
        //   alors demander à capter aussi les .mp4. Désactivé par défaut (les .mp4 pub sont fréquents).
        captureMp4: Boolean = false,
        // 2026-07-29 : certains lecteurs (embed4me) attendent un CLIC sur le bouton play avant de
        //   charger le flux. Si true, on injecte un auto-clic play + video.play() en boucle.
        clickPlay: Boolean = false,
        // 2026-07-29 : une WebView NON attachée à la fenêtre ne fait pas tourner le player (JS/
        //   timers/rendu) → le flux n'est jamais demandé. Si true, on l'attache (invisible, alpha
        //   0.02) le temps de la résolution — comme les extracteurs Filemoon/upbolt.
        attach: Boolean = false,
    ): Resolved? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeoutMs) {
            doResolve(entryUrl, referer, userAgent, extraHeaders, captureMp4, clickPlay, attach)
        }
    }

    private val CLICK_PLAY_JS = """
        (function(){try{
            // vidstack (embed4me) : élément <media-player> avec sa propre méthode play().
            var mp=document.querySelector('media-player'); if(mp){ try{mp.muted=true;}catch(e){} try{mp.play();}catch(e){} }
            var v=document.querySelector('video'); if(v){ try{v.muted=true;}catch(e){} try{v.play();}catch(e){} }
            var sels=['media-play-button','.vds-play-button','[data-media-button]','.play','.vjs-big-play-button',
                '.jw-icon-display','#player_play','.plyr__control--overlaid','button[aria-label*="play" i]','.play-button',
                '#play','.playbtn','.vp-center','.jw-display-icon-container','.bmpui-ui-playbacktogglebutton','.start','#start'];
            for(var i=0;i<sels.length;i++){ var b=document.querySelector(sels[i]); if(b){ try{b.click();}catch(e){} } }
        }catch(e){}})();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun doResolve(
        entryUrl: String,
        referer: String?,
        userAgent: String,
        extraHeaders: Map<String, String>,
        captureMp4: Boolean,
        clickPlay: Boolean,
        attach: Boolean,
    ): Resolved? = suspendCancellableCoroutine { cont ->
        val context = StreamFlixApp.instance.applicationContext
        var resolved = false
        var attachedParent: android.view.ViewGroup? = null

        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = userAgent
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            // Rendering désactivé SEULEMENT si non attachée (mode headless passif). Attachée, on
            //   laisse le rendu normal pour que le player s'initialise.
            settings.loadsImagesAutomatically = attach
            settings.blockNetworkImage = !attach
        }

        // Nettoyage : détache + détruit la WebView (sur succès ET timeout).
        fun cleanup() {
            Handler(Looper.getMainLooper()).post {
                try { attachedParent?.removeView(webView); attachedParent = null } catch (_: Throwable) {}
                try { webView.stopLoading() } catch (_: Throwable) {}
                try { webView.destroy() } catch (_: Throwable) {}
            }
        }
        fun resolve(value: Resolved?) {
            if (!resolved && cont.isActive) {
                resolved = true
                cont.resumeWith(Result.success(value))
            }
            cleanup()
        }

        // Attache la WebView à la fenêtre (invisible) pour que le player tourne réellement.
        if (attach) {
            try {
                val act = StreamFlixApp.currentActivity
                val root = act?.findViewById<android.view.ViewGroup>(android.R.id.content)
                if (root != null) {
                    webView.alpha = 0.02f
                    // Taille RÉELLE (pas 1×1) : sinon des players modernes (vidstack) ne s'initialisent
                    //   pas → jamais de requête flux. Invisible via alpha, retirée à la résolution.
                    val d = context.resources.displayMetrics.density
                    root.addView(webView, android.view.ViewGroup.LayoutParams((320 * d).toInt(), (180 * d).toInt()))
                    attachedParent = root
                }
            } catch (_: Throwable) {}
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

                // 1. Cible trouvée ? On matche l'extension dans le CHEMIN uniquement (pas les query
                //   params) — sinon un tracker avec « ?t=titre.mp4 » déclenche un faux positif.
                val exts = if (captureMp4) STREAM_EXTS + ".mp4" else STREAM_EXTS
                val reqPath = try { android.net.Uri.parse(reqUrl).path ?: "" } catch (_: Throwable) { reqUrl.substringBefore("?") }
                val isStream = exts.any { reqPath.contains(it, ignoreCase = true) }
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

            override fun onPageFinished(view: WebView?, url: String?) {
                if (resolved || view == null || !clickPlay) return
                // Auto-clic play en boucle (le lecteur charge le flux seulement après un clic).
                var n = 0
                fun kick() {
                    if (resolved || n++ > 12) return
                    view.evaluateJavascript(CLICK_PLAY_JS, null)
                    view.postDelayed({ kick() }, 1500L)
                }
                view.postDelayed({ kick() }, 800L)
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?,
            ) {
                // 2026-07-29 : beaucoup de CDN pirates ont des certs douteux (ERR_CERT_AUTHORITY_INVALID)
                //   → sinon le player ne charge pas et le flux n'est jamais demandé. On accepte.
                try { handler?.proceed() } catch (_: Throwable) { handler?.cancel() }
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
            // 2026-05-11 (cf YflixExtractor) : les méthodes WebView DOIVENT tourner sur le main
            //   thread (cleanup poste déjà sur le main + détache la vue attachée).
            cleanup()
        }
    }
}
