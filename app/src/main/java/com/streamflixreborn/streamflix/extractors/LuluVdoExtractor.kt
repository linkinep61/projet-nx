package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * LuluVdo/LuluStream extractor — uses a hidden WebView to bypass CDN protection.
 *
 * CDN (cdn-tnmr.org) blocks all non-WebView clients with 403.
 * Strategy: fetch M3U8 content via WebView's JS fetch() API, then serve
 * it to ExoPlayer as a data URI. ExoPlayer reads the manifest locally
 * and fetches .ts segments from CDN (which aren't token-protected).
 */
class LuluVdoExtractor : Extractor() {

    override val name = "LuluVdo"
    // 2026-06-02 : luluvdo.com redirige vers lulustream.com (rename).
    override val mainUrl = "https://lulustream.com/"
    override val aliasUrls = listOf("https://luluvdo.com", "https://luluvdoo.com", "https://luluvid.com")

    companion object {
        private const val TAG = "LuluVdoExtractor"
        private const val TIMEOUT_MS = 25_000L

        /**
         * Shared WebView kept alive after extraction so that WebViewDataSource
         * can reuse it for fetching .ts segments from the same CDN origin.
         * The WebView has the correct origin, cookies, and TLS fingerprint.
         */
        @Volatile
        var sharedWebView: WebView? = null
            private set

        /** Release the shared WebView (call from player on destroy). */
        fun releaseSharedWebView() {
            val wv = sharedWebView ?: return
            sharedWebView = null
            Handler(Looper.getMainLooper()).post {
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {}
            }
            Log.d(TAG, "Shared WebView released")
        }
    }

    // 2026-07-31 (user « je pense que t'as un problème avec Lulu ») : PAS DE CACHE.
    //   L'extracteur héritait du TTL par défaut (10 min) → l'URL extraite était rejouée telle
    //   quelle, ce qui posait DEUX problèmes : (1) les correctifs d'extraction n'étaient jamais
    //   repris (le code ne repassait pas — d'où l'absence de log « KVS: … » et l'échec) ;
    //   (2) le jeton `t=` de ce CDN a une durée de vie courte → une URL rejouée plus tard est
    //   refusée (ERROR_CODE_IO_BAD_HTTP_STATUS). Comme Hydrax/Embed4me/Emmmmbed : TTL = 0.
    override val cacheTtlMs: Long = 0L

    /**
     * 2026-07-31 (user, LuLuTV → onPlayerError) : sur ce CDN KVS, quand la VARIANTE est déjà
     * dans le chemin (`…/<id>_h/master.m3u8`), c'est la playlist `index-v1-a1.m3u8` qui joue —
     * le `master.m3u8` de ce même dossier est refusé par le player.
     * Vérifié en direct : `…/95jtgebwe93n_h/index-v1-a1.m3u8?t=…` → 200 + playlist complète.
     * No-op si la forme ne correspond pas (on ne touche pas aux vrais masters `,a,b,c,.urlset/`).
     */
    private fun preferVariantPlaylist(url: String): String = try {
        val q = url.indexOf('?')
        val path = if (q >= 0) url.substring(0, q) else url
        val query = if (q >= 0) url.substring(q) else ""
        val rx = Regex("""/([^/,]+_(?:h|n|l|fre|eng|vf|vo))/master\.m3u8$""", RegexOption.IGNORE_CASE)
        if (rx.containsMatchIn(path)) {
            val newPath = path.removeSuffix("master.m3u8") + "index-v1-a1.m3u8"
            Log.d(TAG, "KVS: master → index-v1-a1 (variante déjà dans le chemin)")
            newPath + query
        } else url
    } catch (_: Exception) { url }

    /** 2026-07-31 : vrai si le dernier échec WebView vient d'une vidéo SUPPRIMÉE (page "/dl",
     *  « no longer available »…) et non d'un défaut d'extraction. Sert à lever une exception
     *  classée « dead-content » → l'extracteur n'est pas blacklisté à tort. */
    @Volatile private var lastWasDeadContent = false

    /**
     * 2026-07-31 : extraction 100 % NATIVE (aucune WebView), analysée en direct dans Chrome.
     *   1. GET la page embed (OkHttp).
     *   2. Déballe le packed JS `eval(p,a,c,k,e,d)` → le m3u8 y est EN CLAIR.
     *   3. Le master KVS `…/,<a>,<b>,<c>,.urlset/master.m3u8` est souvent gaté (403) alors que
     *      la playlist de VARIANTE `<v>/index-v1-a1.m3u8` passe avec le seul token `t=` (même
     *      constat que pour upbolt, cf. OnRegardeOuExtractor). On teste donc le master, et on
     *      bascule sur la variante (FR prioritaire) s'il est refusé.
     * Retourne null si quoi que ce soit échoue → l'appelant repasse par la WebView.
     */
    private suspend fun tryNativeExtract(link: String, linkHost: String): Video? = withContext(Dispatchers.IO) {
        try {
            val headers = mapOf(
                "User-Agent" to NetworkClient.USER_AGENT,
                "Referer" to "$linkHost/",
                "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
            )
            val pageReq = okhttp3.Request.Builder().url(link).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            val html = sharedClient.newCall(pageReq).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                r.body?.string().orEmpty()
            }
            if (html.isBlank()) return@withContext null

            // packed JS → m3u8 en clair
            val packed = Regex("""\}\s*\('.*?'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
                .find(html.substringAfter("function(p,a,c,k,e,d)", ""))?.value
            val unpacked = packed?.let {
                runCatching { com.streamflixreborn.streamflix.utils.JsUnpacker(it).unpack() }.getOrNull()
            }
            val searchIn = unpacked ?: html
            val master = Regex("""(https?://[^\s"'`]+\.m3u8[^\s"'`]*)""").find(searchIn)?.groupValues?.get(1)
                ?: return@withContext null
            Log.d(TAG, "native: master m3u8 trouvé (${master.length} chars)")

            // 2026-07-31 (user, LuLuTV → 403 persistant) — MESURE À L'APPUI :
            //   la MÊME URL répond 200 en accès DIRECT (testé dans Chrome, aucun Referer ni
            //   Origin) mais 403 quand on l'accompagne de « Referer/Origin: luluvdo.com ».
            //   Ce CDN refuse donc les requêtes estampillées « venant d'un autre site » —
            //   comportement inverse de Vidzy, où le Referer était au contraire exigé.
            //   → on n'envoie QUE l'User-Agent, comme le ferait un accès direct.
            val streamHeaders = mapOf(
                "User-Agent" to NetworkClient.USER_AGENT,
                "Accept" to "*/*",
            )

            // 2026-07-31 (user « aucun Lulu ne fonctionne ») — LEÇON IMPORTANTE :
            //   je vérifiais l'URL par un GET avant de la rendre… ce qui CONSOMMAIT le jeton.
            //   Ces CDN n'honorent le `t=` qu'UNE SEULE FOIS : le contrôle renvoyait 200, puis
            //   le player recevait 403 sur la même URL (constaté noir sur blanc dans les logs :
            //   « native probe 200 → OK » suivi de « Response code: 403 »).
            //   → PLUS AUCUNE requête de vérification ici. On applique directement la
            //     réécriture KVS (master → variante) et on laisse le player faire LA requête.
            val url = rewriteKvsMasterToVariant(master) ?: master

            Video(
                source = preferVariantPlaylist(url),
                type = MimeTypes.APPLICATION_M3U8,
                headers = streamHeaders,
            )
        } catch (e: Exception) {
            Log.d(TAG, "native KO: ${e.message}")
            null
        }
    }

    /** GET court : l'URL renvoie-t-elle bien une playlist HLS (et pas un 403) ? */
    private fun probeOk(url: String, headers: Map<String, String>): Boolean = try {
        val req = okhttp3.Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        sharedClient.newCall(req).execute().use { r ->
            val ok = r.isSuccessful && (r.body?.string()?.contains("#EXTM3U") == true)
            Log.d(TAG, "native probe ${r.code} → ${if (ok) "OK" else "refusé"}")
            ok
        }
    } catch (_: Exception) { false }

    /**
     * KVS : `…/04086/,<id>_h,lang/fre/<id>_fre,.urlset/master.m3u8?t=…`
     *   → `…/04086/lang/fre/<id>_fre/index-v1-a1.m3u8?t=…` (le token couvre tout le préfixe).
     * Priorité au FRANÇAIS, puis à la meilleure qualité (`_h`), sinon la dernière variante.
     */
    private fun rewriteKvsMasterToVariant(url: String): String? = try {
        val q = url.indexOf('?')
        val query = if (q >= 0) url.substring(q) else ""
        val path = if (q >= 0) url.substring(0, q) else url
        val mi = path.indexOf(".urlset/master.m3u8")
        if (mi < 0) null else {
            val before = path.substring(0, mi)               // …/04086/,<a>,<b>,<c>,
            val lastSlashBeforeList = before.indexOf(",").let { c ->
                if (c < 0) -1 else before.lastIndexOf('/', c)
            }
            if (lastSlashBeforeList < 0) null else {
                val prefix = before.substring(0, lastSlashBeforeList + 1)
                val names = before.substring(lastSlashBeforeList + 1)
                    .trim(',').split(',').filter { it.isNotBlank() }
                val chosen = names.firstOrNull { it.contains("/fre", true) || it.endsWith("_fre") }
                    ?: names.lastOrNull { it.endsWith("_h") }
                    ?: names.lastOrNull()
                if (chosen == null) null else "$prefix$chosen/index-v1-a1.m3u8$query"
            }
        }
    } catch (_: Exception) { null }

    override suspend fun extract(link: String): Video {
        val linkHost = try {
            java.net.URL(link).let { "${it.protocol}://${it.host}" }
        } catch (_: Exception) { mainUrl.trimEnd('/') }

        val fileCode = link.trimEnd('/').substringAfterLast("/").substringBefore(".")
        Log.d(TAG, "Extracting file_code=$fileCode from $linkHost")

        // 2026-07-31 (user « avoir le serveur sans passer par le site, c'est le mieux ») :
        //   on tente D'ABORD une extraction 100 % NATIVE (aucune WebView). Vérifié en direct :
        //   la page LuluVdo contient un packed JS `eval(p,a,c,k,e,d)` qui, déballé, expose le
        //   m3u8 EN CLAIR (pas de chiffrement ni de leurre, contrairement à Vidzy). L'URL est
        //   au format KVS `…/,<id>_h,lang/fre/<id>_fre,.urlset/master.m3u8?t=…` — la même
        //   famille qu'upbolt, où le MASTER est gaté (403) mais la playlist de VARIANTE passe
        //   avec le seul token. Si cette voie échoue → repli sur la WebView (comportement
        //   historique, rien n'est cassé).
        // 2026-07-31 — VOIE NATIVE DÉSACTIVÉE (et le commentaire d'en-tête avait RAISON).
        //   J'avais ajouté une extraction directe (page → packed JS → m3u8) en croyant le
        //   commentaire « CDN blocks all non-WebView clients » périmé, parce qu'un test
        //   NAVIGATEUR renvoyait 200. C'était une erreur de méthode : le jeton `t=` de ce CDN
        //   est lié à la SESSION qui a chargé la page. Un client HTTP tiers (OkHttp/ExoPlayer,
        //   avec ou sans Referer/Origin, avec ou sans vérification préalable) se prend un 403 —
        //   constaté sur 4 essais consécutifs.
        //   Pire : cette voie « réussissait » l'extraction, donc elle COURT-CIRCUITAIT le
        //   mécanisme WebView + data-URI ci-dessous, qui lui fonctionne (la WebView lit le
        //   manifeste dans SA session, puis on le sert à ExoPlayer en data-URI ; seuls les
        //   segments .ts, non protégés, sont ensuite téléchargés normalement).
        //   Le code de `tryNativeExtract` est conservé, inutilisé, au cas où ce CDN
        //   s'assouplirait — mais il ne doit PAS être rebranché sans preuve hors navigateur.

        lastWasDeadContent = false
        val result = resolveViaWebView(linkHost, fileCode)
            ?: if (lastWasDeadContent) {
                // Message contenant un marqueur reconnu par Extractor.classifyError → "dead-content".
                //   L'extracteur n'est alors PAS pénalisé (c'est la vidéo qui a été supprimée),
                //   donc LuluVdo reste disponible pour les liens suivants.
                throw Exception("LuluVdo: video not found — file removed or expired")
            } else {
                throw Exception("WebView extraction timed out or failed for LuluVdo")
            }

        val headers = mutableMapOf(
            "Referer" to "$linkHost/",
            "Origin" to linkHost,
            "Accept" to "*/*",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
        )

        val source: String
        val mimeType: String?

        if (result.m3u8Content != null) {
            Log.d(TAG, "M3U8 content captured (${result.m3u8Content.length} chars) → data URI")
            Log.d(TAG, "Raw M3U8:\n${result.m3u8Content}")

            // Rewrite relative URLs to absolute so ExoPlayer can resolve them
            // from the data: URI (which has no base path)
            val baseUrl = result.m3u8Url.substringBefore("?").substringBeforeLast("/") + "/"
            val rewrittenContent = result.m3u8Content.lines().joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("#") && !trimmed.startsWith("http")) {
                    baseUrl + trimmed
                } else {
                    line
                }
            }
            Log.d(TAG, "Rewritten M3U8:\n$rewrittenContent")

            val encoded = Base64.encodeToString(rewrittenContent.toByteArray(), Base64.NO_WRAP)
            source = "data:application/vnd.apple.mpegurl;base64,$encoded"
            mimeType = MimeTypes.APPLICATION_M3U8
        } else {
            Log.w(TAG, "No M3U8 content captured, using URL: ${result.m3u8Url.take(80)}")
            // 2026-07-31 : on NE touche PAS à l'URL rendue par la WebView (ma réécriture
            //   master→variante est retirée d'ici) — comportement d'origine restauré.
            source = result.m3u8Url
            mimeType = if (source.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null
        }

        return Video(
            source = source,
            subtitles = result.subtitles,
            type = mimeType,
            headers = headers,
            webViewUrl = link
        )
    }

    private data class ExtractionResult(
        val m3u8Url: String,
        val m3u8Content: String? = null,
        val subtitles: List<Video.Subtitle> = emptyList()
    )

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveViaWebView(host: String, fileCode: String): ExtractionResult? {
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val mainHandler = Handler(Looper.getMainLooper())
                    var webView: WebView? = null
                    var resolved = false
                    var capturedM3u8Url: String? = null  // Only process first M3U8 URL
                    var allowXhrThrough = false  // When true, let our XHR request pass through
                    var capturedSubtitles: List<Video.Subtitle> = emptyList()
                    var pendingM3u8Fetch = false  // Set true after navigating to blank.html
                    var inPlaybackMode = false  // When true, allow all requests through

                    fun cleanup(keepWebView: Boolean = false) {
                        if (keepWebView) {
                            // Keep the WebView alive for WebViewDataSource to reuse
                            // Don't reset WebViewClient — just switch to playback mode
                            // so shouldInterceptRequest allows all requests through
                            inPlaybackMode = true
                            Log.d(TAG, "Keeping WebView alive for segment fetching (playback mode, file:// origin)")
                            sharedWebView = webView
                        } else {
                            mainHandler.post {
                                try {
                                    webView?.stopLoading()
                                    webView?.destroy()
                                    webView = null
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    fun resolve(result: ExtractionResult) {
                        if (resolved) return
                        resolved = true
                        Log.d(TAG, "Resolved: url=${result.m3u8Url.take(80)}, hasContent=${result.m3u8Content != null}")
                        // Keep WebView alive if we captured M3U8 content (data URI mode)
                        // WebViewDataSource will use it to fetch .ts segments
                        cleanup(keepWebView = result.m3u8Content != null)
                        if (continuation.isActive) continuation.resume(result)
                    }

                    continuation.invokeOnCancellation { cleanup() }

                    try {
                        val wv = WebView(com.streamflixreborn.streamflix.StreamFlixApp.instance)
                        webView = wv

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            userAgentString = NetworkClient.USER_AGENT
                            mediaPlaybackRequiresUserGesture = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            @Suppress("DEPRECATION")
                            allowUniversalAccessFromFileURLs = true
                        }

                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url?.toString() ?: return null

                                // In playback mode, let ALL requests through
                                // (WebViewDataSource needs .ts and .m3u8 to pass)
                                if (inPlaybackMode) return null

                                // Block .ts segments (don't let WebView download video)
                                if (url.contains(".ts") && url.contains("tnmr.org")) {
                                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                }

                                // Capture FIRST M3U8 URL only
                                if (url.contains(".m3u8") && capturedM3u8Url == null) {
                                    capturedM3u8Url = url
                                    Log.d(TAG, "M3U8 URL captured: ${url.take(100)}")

                                    // Set flag BEFORE starting XHR so shouldInterceptRequest
                                    // lets our XHR request pass through
                                    allowXhrThrough = true

                                    // Extract subtitles while JWPlayer page is still loaded,
                                    // then navigate to file:// page for CORS-free XHR
                                    mainHandler.post {
                                        extractSubtitles(view) { subs ->
                                            capturedSubtitles = subs
                                            pendingM3u8Fetch = true
                                            Log.d(TAG, "Subtitles extracted (${subs.size}), navigating to blank.html for CORS-free fetch")
                                            view?.loadUrl("file:///android_asset/blank.html")
                                        }
                                    }

                                    // Block the original JWPlayer request (don't consume the token)
                                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                }

                                // Block duplicate M3U8 requests (JWPlayer retries)
                                // BUT allow our own XHR request through!
                                if (url.contains(".m3u8") && !allowXhrThrough) {
                                    Log.d(TAG, "Blocking duplicate M3U8 request")
                                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                }
                                if (url.contains(".m3u8") && allowXhrThrough) {
                                    Log.d(TAG, "Allowing XHR M3U8 request through: ${url.take(80)}")
                                }

                                return null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Log.d(TAG, "Page finished: $url")

                                // 2026-06-02 (Coflix Zootopie 2) : fast-fail si la page hoster
                                // affiche "File is no longer available as it expired or has been
                                // deleted" (HTML ~1.2 ko). Sans ce check, l'extracteur attendait
                                // les 25s du timeout pour un m3u8 qui ne viendra jamais → la
                                // rotation auto vers le serveur suivant prend 25s+ au lieu de <1s.
                                if (url != null && (url.contains("luluvdo") || url.contains("lulustream"))) {
                                    view?.evaluateJavascript(
                                        "(function(){var t=(document.body&&document.body.innerText)||'';" +
                                        "return /no longer available|has been deleted|file expired|video not found|file not found/i.test(t)?'DEAD':'OK';})();"
                                    ) { result ->
                                        if (result?.contains("DEAD") == true && !resolved) {
                                            Log.w(TAG, "fast-fail: LuluVdo dead-content détecté → resume(null) immédiat")
                                            // 2026-07-31 (user « fais en sorte que ça n'interfère pas ») :
                                            //   on MÉMORISE la raison. Sans ça, extract() levait
                                            //   « WebView extraction timed out… » → le mot « timed out »
                                            //   classait l'échec en TIMEOUT → LuluVdo était marqué
                                            //   « broken » 10 min après 3 fichiers morts, et n'était
                                            //   PLUS essayé même sur des liens valides.
                                            lastWasDeadContent = true
                                            resolved = true
                                            cleanup()
                                            if (continuation.isActive) continuation.resume(null)
                                        }
                                    }
                                }

                                // blank.html loaded → fetch M3U8 via CORS-free XHR (file:// origin)
                                if (pendingM3u8Fetch && url?.startsWith("file:") == true) {
                                    pendingM3u8Fetch = false
                                    val m3u8Url = capturedM3u8Url ?: return
                                    Log.d(TAG, "blank.html ready, starting CORS-free M3U8 fetch from file:// origin")
                                    startM3u8Fetch(view, m3u8Url, mainHandler) { content ->
                                        allowXhrThrough = false
                                        resolve(ExtractionResult(m3u8Url, content, capturedSubtitles))
                                    }
                                }
                            }
                        }

                        val postData = "op=embed&file_code=$fileCode&auto=1&referer="
                        wv.postUrl("$host/dl", postData.toByteArray())

                    } catch (e: Exception) {
                        Log.e(TAG, "WebView setup failed: ${e.message}", e)
                        cleanup()
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }
    }

    /**
     * Fetch M3U8 content using WebView's JS fetch() API via async polling.
     * evaluateJavascript can't await Promises, so we store the result
     * in a window variable and poll for it.
     */
    private fun startM3u8Fetch(
        view: WebView?,
        m3u8Url: String,
        handler: Handler,
        callback: (String?) -> Unit
    ) {
        if (view == null) { callback(null); return }

        Log.d(TAG, "Starting async M3U8 fetch via JS...")

        // Inject fetch call that stores result in a window variable
        view.evaluateJavascript("""
            (function() {
                window.__m3u8 = null;
                window.__m3u8err = null;

                var xhr = new XMLHttpRequest();
                xhr.open('GET', '$m3u8Url', true);
                xhr.withCredentials = true;
                xhr.setRequestHeader('Accept', '*/*');
                xhr.onload = function() {
                    if (xhr.status === 200) {
                        window.__m3u8 = xhr.responseText;
                    } else {
                        window.__m3u8err = 'HTTP ' + xhr.status;
                    }
                };
                xhr.onerror = function() {
                    window.__m3u8err = 'XHR error';
                };
                xhr.send();
            })();
        """.trimIndent(), null)

        // Poll for result
        pollM3u8Result(view, handler, 0, callback)
    }

    private fun pollM3u8Result(
        view: WebView?,
        handler: Handler,
        attempt: Int,
        callback: (String?) -> Unit
    ) {
        if (view == null || attempt > 20) {
            Log.w(TAG, "M3U8 fetch polling exhausted (attempt $attempt)")
            callback(null)
            return
        }

        handler.postDelayed({
            view.evaluateJavascript("""
                (function() {
                    if (window.__m3u8err) return 'ERR:' + window.__m3u8err;
                    if (window.__m3u8) return window.__m3u8;
                    return '';
                })();
            """.trimIndent()) { result ->
                val raw = result?.trim()?.removeSurrounding("\"") ?: ""

                if (raw.startsWith("ERR:")) {
                    Log.e(TAG, "M3U8 XHR failed: $raw")
                    callback(null)
                    return@evaluateJavascript
                }

                if (raw.isNotEmpty() && raw != "null") {
                    val content = raw
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\/", "/")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")

                    if (content.contains("#EXTM3U") || content.contains("#EXT-X-")) {
                        Log.d(TAG, "M3U8 content fetched OK (${content.length} chars, attempt $attempt)")
                        callback(content)
                    } else {
                        Log.w(TAG, "Content not M3U8 (attempt $attempt): ${content.take(100)}")
                        // Keep polling — might not be ready yet
                        pollM3u8Result(view, handler, attempt + 1, callback)
                    }
                    return@evaluateJavascript
                }

                // Not ready yet
                pollM3u8Result(view, handler, attempt + 1, callback)
            }
        }, 500)
    }

    private fun extractSubtitles(view: WebView?, callback: (List<Video.Subtitle>) -> Unit) {
        if (view == null) { callback(emptyList()); return }

        view.evaluateJavascript("""
            (function() {
                if (typeof jwplayer !== 'undefined') {
                    try {
                        var config = jwplayer().getConfig();
                        var tracks = config.tracks || [];
                        return JSON.stringify(tracks.filter(function(t) {
                            return t.kind === 'captions' || t.kind === 'subtitles';
                        }));
                    } catch(e) {}
                }
                return '[]';
            })();
        """.trimIndent()) { tracksJson ->
            callback(parseSubtitles(tracksJson))
        }
    }

    private fun parseSubtitles(jsonStr: String?): List<Video.Subtitle> {
        if (jsonStr.isNullOrBlank() || jsonStr == "null" || jsonStr == "[]") return emptyList()
        return try {
            val clean = jsonStr.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            val arr = org.json.JSONArray(clean)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val file = obj.optString("file", "")
                val label = obj.optString("label", "")
                if (file.isNotBlank() && label != "Upload captions") {
                    Video.Subtitle(label = label, file = file)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Subtitle parsing failed: ${e.message}")
            emptyList()
        }
    }
}
