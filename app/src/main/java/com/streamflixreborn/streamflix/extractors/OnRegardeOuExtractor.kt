package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject

/**
 * OnRegardeOuExtractor — extrait le flux des pages onregardeou.site.
 *
 * 2026-07-01 : l'hébergeur est passé en résolution DYNAMIQUE + gate anti-bot.
 *   - La page /video/<slug>/ = player video.js avec `const videoData={servers:[...]}`.
 *   - Un overlay « Confirmez que vous êtes humain pour lancer la vidéo » exige un
 *     clic ; les serveurs pointent vers des hôtes qui changent souvent
 *     (bysezoxexe/uns.bio/chuckle-tube/upbolt…) + pop-unders pub.
 *   → L'ancien parsing statique (`videoData` → hôtes → extracteurs in-app) ne joue
 *     plus (hôtes inconnus/gatés).
 *
 * Nouvelle stratégie (modèle MoiflixExtractor) : WebView headless qui charge la
 *   page, auto-clique le bouton play (passe le gate humain), BLOQUE les pop-unders
 *   (onCreateWindow=false) et INTERCEPTE le m3u8/mp4 réel via shouldInterceptRequest.
 *   Robuste aux changements de domaine des hôtes (on capte le flux, peu importe
 *   d'où il vient).
 */
class OnRegardeOuExtractor : Extractor() {

    override val name = "OnRegardeOu"
    override val mainUrl = "https://onregardeou.site"
    // 2026-07-01 : les mirrors internes d'onregardeou qui n'ont PAS d'extracteur
    //   dédié (bysezoxexe→player q8y5z, upbolt, uns.bio) sont routés ICI (WebView
    //   intercept générique). PAS chuckle-tube → c'est du VOE, géré par VoeExtractor
    //   (rapide, sans WebView) via son rotatingDomain. OnRegardeOu est enregistré
    //   AVANT Filemoon dans la liste → il gagne bysezoxexe (sinon Filemoon+PoW).
    override val aliasUrls = listOf(
        "https://bysezoxexe.com", "https://upbolt.to", "https://dismoiceline.uns.bio",
    )

    private val context = StreamFlixApp.instance.applicationContext

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Le provider appelle expand() avec l'URL embed onregardeou. La page contient
     *  `videoData.servers` = plusieurs MIRRORS (bysezoxexe/uns.bio/chuckle-tube/upbolt…).
     *  On les expose TOUS comme serveurs distincts (le user voyait « SERVEUR 1/2/3/4 »
     *  sur le site). Chaque mirror route ensuite vers le bon extracteur via
     *  Extractor.extract (VOE pour chuckle-tube, WebView OnRegardeOu pour les autres). */
    // 2026-07-02 (user "ce genre de serveur qui affiche une page à cliquer, on les désactive
    //   maintenant qu'on a plein de backups") : mirrors OnRegardeOu à NE PAS exposer.
    //   uns.bio = player vidstack (PoW "Verifying human…" à cliquer, ne joue pas headless) ;
    //   upbolt = DataDome + ad-gate (coupe en cours de lecture). bysezoxexe/VOE gardés.
    private val DISABLED_MIRROR_HOSTS = listOf("uns.bio", "upbolt")

    suspend fun expand(link: String, referer: String = mainUrl, suffix: String = ""): List<Video.Server> {
        if (link.isBlank()) return emptyList()
        val mirrors = parseMirrors(link).filterNot { (_, mUrl) ->
            val h = try { android.net.Uri.parse(mUrl).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
            DISABLED_MIRROR_HOSTS.any { h.contains(it) }
        }
        if (mirrors.isEmpty()) {
            // Fallback : ancien comportement (1 serveur = la page onregardeou → WebView).
            return listOf(Video.Server(id = "${name}_0", name = "${suffix}OnRegardeOu", src = link))
        }
        return mirrors.mapIndexed { i, (mName, mUrl) ->
            val svc = Extractor.identifyServiceName(mUrl)
            // Nom DISTINCT. Les mirrors bysezoxexe/upbolt/uns.bio routent tous vers
            //   CET extracteur (aliasUrls) → identifyServiceName renvoie "OnRegardeOu"
            //   pour les 3 → « 3 fois le même » dans le picker. On préfère : le vrai
            //   service tiers (VOE…) sinon le nom videoData ("Serveur N", = le site)
            //   sinon le host.
            // Host "propre" pour l'affichage : full host sans www. Pour un
            //   sous-domaine rotatif (dismoiceline.uns.bio) on garde le domaine
            //   enregistrable (2 derniers labels : uns.bio) = stable entre rotations.
            val host = try {
                val h = android.net.Uri.parse(mUrl).host?.removePrefix("www.").orEmpty()
                val parts = h.split(".")
                if (parts.size >= 3) parts.takeLast(2).joinToString(".") else h
            } catch (_: Exception) { "" }
            // User (2026-07-01) : « il devrait être nommé par leur nom d'origine
            //   pour qu'on sache ». → priorité : service tiers reconnu (VOE/Vidara…)
            //   sinon le HOST réel (bysezoxexe.com / uns.bio / upbolt.to) — PLUS le
            //   « Serveur N » générique qui masquait l'identité du mirror.
            val label = when {
                svc != null && !svc.equals(name, ignoreCase = true) -> svc
                host.isNotBlank() -> host
                mName.isNotBlank() -> mName
                else -> "Serveur ${i + 1}"
            }
            Video.Server(id = "${name}_$i", name = "$suffix$label", src = mUrl)
        }
    }

    /** Parse TOUS les mirrors de `videoData.servers` → [(nom, url)]. */
    private suspend fun parseMirrors(onregardeouUrl: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(onregardeouUrl)
                .header("User-Agent", ANDROID_CHROME_UA)
                .header("Referer", "$mainUrl/").build()
            val html = NetworkClient.default.newCall(req).execute().use { it.body?.string() }
                ?: return@withContext emptyList()
            val m = Regex("videoData\\s*=\\s*(\\{[\\s\\S]*?\\});").find(html) ?: return@withContext emptyList()
            val servers = JSONObject(m.groupValues[1]).optJSONArray("servers") ?: return@withContext emptyList()
            (0 until servers.length()).mapNotNull { idx ->
                val o = servers.getJSONObject(idx)
                val url = o.optString("url").replace("\\/", "/").takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
                val nm = o.optString("name").ifBlank { o.optString("label") }
                nm to url
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Récupère l'URL de l'hôte réel (bysezoxexe/uns.bio…) depuis le `videoData`
     *  de la page onregardeou. Ces hôtes s'auto-lancent → chargés directement dans
     *  la WebView, ils déclenchent la lecture sans le gate « humain » d'onregardeou. */
    private suspend fun resolveHostUrl(onregardeouUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(onregardeouUrl)
                .header("User-Agent", ANDROID_CHROME_UA)
                .header("Referer", "$mainUrl/").build()
            val html = NetworkClient.default.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            val m = Regex("const videoData\\s*=\\s*(\\{[\\s\\S]*?\\});").find(html) ?: return@withContext null
            val servers = JSONObject(m.groupValues[1]).optJSONArray("servers") ?: return@withContext null
            if (servers.length() == 0) return@withContext null
            servers.getJSONObject(0).optString("url").replace("\\/", "/").takeIf { it.startsWith("http") }
        } catch (_: Exception) { null }
    }

    override suspend fun extract(link: String): Video {
        // onregardeou → URL de l'HÔTE réel (bysezoxexe = player Filemoon).
        val hostUrl = resolveHostUrl(link) ?: link
        // On charge l'HÔTE directement (bysezoxexe.com/e/<id>). C'EST LUI qui crée
        //   l'iframe du player (q8y5z) avec le bon parent + handshake postMessage :
        //   le player démarre SEUL, résout son propre anti-bot (pow.js), et le m3u8
        //   part dans les frames imbriquées → intercepté par la WebView (qui, sur
        //   device, voit TOUTES les frames, contrairement à Chrome).
        //   ⚠ NE PAS descendre jusqu'à q8y5z : chargé seul c'est une page de
        //   redirection sans parent → le player ne s'initialise jamais (écran noir).
        val (streamUrl, streamReferer) = extractByIntercepting(hostUrl, "$mainUrl/")
            ?: throw Exception("OnRegardeOu: aucun flux capté pour $hostUrl")
        // HLS si .m3u8 OU master .txt/.urlset OU chemin /hls (uns.bio, streamwish…).
        //   Sinon (mp4 direct) → type null (auto-détecté par ExoPlayer).
        val isHls = streamUrl.contains(".m3u8") || streamUrl.contains(".txt") ||
            streamUrl.contains("master") || streamUrl.contains(".urlset") || streamUrl.contains("/hls")
        val originHost = Regex("(https?://[^/]+)").find(streamReferer)?.groupValues?.get(1) ?: mainUrl
        return Video(
            source = streamUrl,
            type = if (isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
            headers = mapOf(
                // Referer = la frame qui a réellement demandé le m3u8 (q8y5z/bysezoxexe),
                //   sinon le CDN (sprintcdn) refuse la lecture dans ExoPlayer.
                "Referer" to streamReferer,
                "Origin" to originHost,
                "User-Agent" to ANDROID_CHROME_UA,
            )
        )
    }

    /** Vrai geste tactile (MotionEvent down+up) au point donné → démarre le player
     *  q8y5z (qui exige une interaction). La whitelist de navigation empêche les
     *  popunders déclenchés par ce geste de détourner la frame. */
    private fun realTap(view: WebView, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
        try { view.dispatchTouchEvent(down); view.dispatchTouchEvent(up) } catch (_: Exception) {}
        down.recycle(); up.recycle()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractByIntercepting(url: String, referer: String = "$mainUrl/"): Pair<String, String>? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(28_000L) {
                suspendCancellableCoroutine { cont ->
                    var resolved = false
                    fun resolve(value: Pair<String, String>?) {
                        if (!resolved && cont.isActive) { resolved = true; cont.resume(value) {} }
                    }

                    // Whitelist de navigation DYNAMIQUE : la chaîne fixe (onregardeou/
                    //   bysezoxexe/q8y5z) + le domaine du mirror réellement chargé
                    //   (upbolt.to, uns.bio…) → le player du mirror peut naviguer chez
                    //   lui, mais les popunders pub (autres domaines) restent bloqués.
                    val targetDomain = try {
                        val h = android.net.Uri.parse(url).host ?: ""
                        val parts = h.split(".")
                        if (parts.size >= 2) parts.takeLast(2).joinToString(".") else h
                    } catch (_: Exception) { "" }
                    val navAllowed = ALLOWED_NAV_HOSTS + listOfNotNull(targetDomain.takeIf { it.isNotBlank() })

                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = ANDROID_CHROME_UA
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                        // CLÉ (2026-07-01) : force le WebView à RASTER même hors écran /
                        //   caché → le player JW considère la page "visible" et AUTOPLAY
                        //   muet (comme dans Chrome), sans aucun geste. Le m3u8 part alors
                        //   à l'init du player → intercepté. Sans ça, un WebView caché ne
                        //   raster pas → le player attend un geste et ne joue jamais.
                        try { settings.offscreenPreRaster = true } catch (_: Exception) {}
                    }
                    // Attache la WebView DERRIÈRE l'UI (index 0, quasi invisible) sur
                    //   l'activité courante → elle a une vraie fenêtre, donc les
                    //   dispatchTouchEvent sont de VRAIS gestes que le player accepte
                    //   (le PoW pow.js ne démarre qu'après une interaction humaine).
                    //   Invisible pour l'user (alpha ~0 + derrière tout).
                    var attachedRoot: android.view.ViewGroup? = null
                    try {
                        val act = StreamFlixApp.currentActivity
                        val root = act?.findViewById<android.view.ViewGroup>(android.R.id.content)
                        if (root != null) {
                            webView.alpha = 0.02f
                            root.addView(webView, 0, android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                            attachedRoot = root
                        } else {
                            webView.layout(0, 0, 1280, 720)
                        }
                    } catch (_: Exception) { webView.layout(0, 0, 1280, 720) }

                    // Bloque toutes les fenêtres (pop-unders pub) + log console (debug).
                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
                        ): Boolean = false
                        override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                            android.util.Log.d("OnRegardeOuJS", "${cm?.message()}".take(180))
                            return true
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val host = request?.url?.host?.lowercase() ?: return true
                            // WHITELIST STRICTE de navigation : on n'autorise QUE la chaîne
                            //   du player (onregardeou → bysezoxexe → q8y5z). TOUTE autre
                            //   navigation = popunder pub (torontocasbahs, pieshopweedish…)
                            //   qui détourne la frame principale et éjecte le player.
                            //   (Le m3u8/les assets passent par XHR/hls.js → shouldInterceptRequest,
                            //    PAS par ce hook navigation, donc les bloquer ici est sans risque.)
                            val allowed = navAllowed.any { host.contains(it) }
                            if (!allowed) android.util.Log.d("OnRegardeOu", "NAV bloquée (pub): $host")
                            return !allowed  // true = on bloque la navigation
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host?.lowercase() ?: ""
                            // DEBUG : log les requêtes média/API/CDN pour voir ce que la
                            //   WebView reçoit réellement (frames profondes comprises).
                            if (reqUrl.contains(".m3u8") || reqUrl.contains(".mp4") || reqUrl.contains(".ts") ||
                                reqUrl.contains("master") || reqUrl.contains("/api/") || reqUrl.contains("sprintcdn") ||
                                reqUrl.contains("hls") || reqUrl.contains("stream") || reqUrl.contains("q8y5z") ||
                                reqUrl.contains(".m3u") || reqUrl.contains("playlist") || reqUrl.contains("cdn")) {
                                android.util.Log.d("OnRegardeOuNet", reqUrl.take(160))
                            }
                            // Capture le flux : m3u8 (master) ou mp4 direct.
                            val isM3u8 = reqUrl.contains(".m3u8")
                            val isMp4 = reqUrl.contains(".mp4") && !reqUrl.contains("thumb")
                            // Certains mirrors servent le MASTER HLS en .txt (uns.bio →
                            //   vinturastudios `/v4/epu/<id>/cf-master.<ts>.txt`). On le
                            //   capte aussi (ExoPlayer le lira en HLS via le MimeType forcé).
                            val isTxtMaster = reqUrl.contains(".txt") &&
                                (reqUrl.contains("master") || reqUrl.contains("/epu/") || reqUrl.contains("/hls"))
                            if (isM3u8 || isMp4 || isTxtMaster) {
                                // Referer réel de la frame qui demande le flux (q8y5z/
                                //   bysezoxexe) → indispensable pour que le CDN accepte
                                //   la lecture ExoPlayer. Fallback : origin de l'URL.
                                val reqReferer = request.requestHeaders?.get("Referer")
                                    ?: request.requestHeaders?.get("referer")
                                    ?: (Regex("(https?://[^/]+)").find(reqUrl)?.groupValues?.get(1)?.plus("/") ?: referer)
                                android.util.Log.d("OnRegardeOu", "flux INTERCEPTÉ: ${reqUrl.take(120)} | ref=$reqReferer")
                                resolve(Pair(reqUrl, reqReferer))
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            // Bloque les requêtes pub/tracker.
                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return
                            android.util.Log.d("OnRegardeOu", "onPageFinished: $finishedUrl")
                            // GESTE nécessaire pour démarrer le player q8y5z (il n'autoplay
                            //   pas). Les popunders déclenchés par ce geste sont neutralisés
                            //   par la whitelist de navigation (shouldOverrideUrlLoading) →
                            //   ils ne peuvent plus détourner la frame. On tape au centre +
                            //   video.play() en secours.
                            val kick = Runnable {
                                if (resolved) return@Runnable
                                view.evaluateJavascript(AUTO_PLAY_JS, null)
                                val cx = (if (view.width > 0) view.width else 720) / 2f
                                val cy = (if (view.height > 0) view.height else 1280) / 2f
                                realTap(view, cx, cy)
                            }
                            view.postDelayed(kick, 2_000L)
                            view.postDelayed(kick, 5_000L)
                            view.postDelayed(kick, 9_000L)
                            view.postDelayed(kick, 14_000L)
                        }
                    }

                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    // Garde le WebView "actif" même caché → la lecture média n'est pas
                    //   mise en pause par le système (sinon autoplay bloqué).
                    try { webView.onResume(); webView.resumeTimers() } catch (_: Exception) {}
                    webView.loadUrl(url, mapOf("Referer" to referer))

                    cont.invokeOnCancellation {
                        resolved = true
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try { attachedRoot?.removeView(webView) } catch (_: Exception) {}
                            try { webView.stopLoading(); webView.destroy() } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

    companion object {
        // UNIQUEMENT des régies pub / trackers. NE PAS mettre les hôtes vidéo
        //   (bysezoxexe, chuckle-tube, uns.bio, upbolt…) : ce sont les serveurs réels.
        private val BLOCKED_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice", "popads", "popunder",
            "popcash", "propellerads", "exoclick", "juicyads", "trafficjunky",
            "googletagmanager", "google-analytics", "sewarsremeets", "frs2c", "jnbhi",
        )

        /** WHITELIST de navigation : SEULS ces hôtes (la chaîne du player) peuvent
         *  charger une page/frame. Tout le reste = popunder pub → bloqué. Le flux
         *  vidéo (m3u8 sprintcdn) passe par XHR/hls.js → shouldInterceptRequest, donc
         *  il n'est PAS concerné par ce filtre de navigation. */
        private val ALLOWED_NAV_HOSTS = listOf(
            "onregardeou", "bysezoxexe", "q8y5z",
        )

        /** Lance la lecture SANS cliquer d'overlay (les clics d'overlay q8y5z
         *  déclenchent les popunders pub). On appelle juste video.play() sur la
         *  balise <video> — le player autoplay de toute façon (gesture désactivé).
         *  Le m3u8 part à l'init du player → intercepté. */
        private const val AUTO_PLAY_JS = """
            (function(){
                try {
                    var v = document.querySelector('video');
                    if (v){ try{ v.muted=true; v.play(); }catch(e){} }
                    // idem dans les iframes same-origin accessibles (best effort)
                    var ifr = document.querySelectorAll('iframe');
                    for (var i=0;i<ifr.length;i++){
                        try{
                            var d = ifr[i].contentDocument;
                            if (d){ var vv = d.querySelector('video'); if (vv){ vv.muted=true; vv.play(); } }
                        }catch(e){}
                    }
                } catch(e){}
            })();
        """
    }
}
