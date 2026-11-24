package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URI

class DroploadExtractor : Extractor() {

    companion object {
        /** WebView cachée (file:///android_asset/blank.html) par laquelle le lecteur fait passer
         *  les requêtes du flux dropcdn — voir needsWebViewDs dans PlayerMobile/TvFragment. */
        @Volatile
        var sharedWebView: android.webkit.WebView? = null
            private set

        @Volatile
        private var origineWebView: String? = null

        // 2026-11-03 (serveur rouge « Movix · Dropload », 403 sur ds2.dropcdn.io) : dans Chrome le
        //   flux est demandé DEPUIS la page du lecteur (Origin/Referer https://dr0pstream.com) et
        //   passe ; notre WebView était sur file:///android_asset/blank.html (origine « null ») →
        //   403. Elle est désormais posée sur l'adresse du lecteur (page vide, rien de chargé du site).
        @android.annotation.SuppressLint("SetJavaScriptEnabled")
        suspend fun preparerWebViewPartagee(ua: String, origine: String? = null) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (sharedWebView != null && origineWebView == origine) return@withContext
            if (sharedWebView != null) {
                val ancienne = sharedWebView
                sharedWebView = null
                try { ancienne?.stopLoading(); ancienne?.destroy() } catch (_: Exception) {}
            }
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    val wv = android.webkit.WebView(com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext)
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = ua
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = true
                    }
                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                            if (cont.isActive) cont.resume(Unit) {}
                        }
                    }
                    sharedWebView = wv
                    origineWebView = origine
                    if (origine != null) {
                        wv.loadDataWithBaseURL("$origine/", "<html><body></body></html>", "text/html", "utf-8", null)
                    } else {
                        wv.loadUrl("file:///android_asset/blank.html")
                    }
                }
            }
            android.util.Log.d("DroploadExtractor", "WebView de lecture prête (origine ${origine ?: "file://"})")
        }

        fun releaseSharedWebView() {
            val wv = sharedWebView ?: return
            sharedWebView = null
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
            }
            origineWebView = null
        }
    }
    override val name = "Dropload"
    // 2026-06-02 : dropload.tv et .io redirigent vers .pro (le main actif).
    override val mainUrl = "https://dropload.pro"
    override val aliasUrls = listOf("https://dropload.io", "https://dropload.tv", "https://dr0pstream.com")

    private val service = Extractor.createGsonService<DroploadService>(mainUrl)

    // 2026-10-26 (serveur rouge « Movix · Dropload », dr0pstream.com : « Unpack failed ») : la
    //   page vue dans Chrome contient bien le script compressé attendu (245 mots / 245) — c'est
    //   l'app qui recevait une AUTRE page (requête sans allure de navigateur). On se présente
    //   donc comme le lecteur intégré qu'on est (même méthode que FSVid).
    private interface DroploadService {
        @GET
        suspend fun get(
            @Url url: String,
            @retrofit2.http.Header("User-Agent") ua: String = DEFAULT_USER_AGENT,
            @retrofit2.http.Header("Referer") referer: String = "",
            @retrofit2.http.Header("Sec-Fetch-Dest") secDest: String = "iframe",
            @retrofit2.http.Header("Sec-Fetch-Mode") secMode: String = "navigate",
            @retrofit2.http.Header("Sec-Fetch-Site") secSite: String = "cross-site",
            @retrofit2.http.Header("Upgrade-Insecure-Requests") upgrade: String = "1",
            @retrofit2.http.Header("Accept") accept: String =
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            @retrofit2.http.Header("Accept-Language") langue: String = "fr-FR,fr;q=0.9",
        ): Response<String>
    }

    override suspend fun extract(link: String): Video {
        val origineLien = URI(link).let { "${it.scheme}://${it.host}/" }
        val response = service.get(link, referer = origineLien)
        val html = response.body() ?: throw Exception("Dropload: page HTTP ${response.code()}")

        // Use the final URL after redirects as referer
        val finalUrl = response.raw().request.url.toString()
        val referer = URI(finalUrl).let { "${it.scheme}://${it.host}" }

        if (html.contains("File Not Found", true) || html.contains("file was deleted", true) ||
            html.contains("no longer available", true)) {
            throw Exception("Dropload: source unavailable — fichier supprimé")
        }
        val debutPack = html.indexOf("eval(function(p,a,c,k,e,d)")
        if (debutPack < 0) {
            // 2026-10-26 : page de VÉRIFICATION Cloudflare Turnstile (« I'm not a robot » + POST
            //   op=embed) au lieu du lecteur. On n'y touche pas : la page est chargée dans un vrai
            //   navigateur intégré (WebView attachée, aucun clic, aucun maquillage). Si Cloudflare
            //   la valide tout seul — comme dans Chrome — on prend le flux que le lecteur demande ;
            //   s'il exige un clic, le serveur reste rouge et l'app passe au suivant.
            if (html.contains("turnstile", true) || html.contains("g-recaptcha", true)) {
                val capte = runCatching {
                    com.streamflixreborn.streamflix.utils.WebViewStreamResolver.resolve(
                        entryUrl = link,
                        referer = origineLien,
                        // 2026-10-26 : l'identité RÉELLE de la WebView (pas le « Pixel 8 / Chrome 131 »
                        //   par défaut) : le site fabrique le jeton du flux d'après le navigateur qui
                        //   ouvre la page ; une identité incohérente donnait un jeton refusé (403).
                        userAgent = android.webkit.WebSettings.getDefaultUserAgent(
                            com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext),
                        timeoutMs = 20_000L,
                        clickPlay = false,
                        attach = true,
                    )
                }.getOrNull()
                val url = capte?.url?.takeIf { it.startsWith("http") && (it.contains(".m3u8") || it.contains(".mp4")) }
                if (url != null) {
                    // 2026-10-26 : le lien est fabriqué POUR le navigateur intégré (refusé au lecteur,
                    //   et même rejoué dans Chrome). On le fait donc lire PAR un navigateur intégré :
                    //   le lecteur passe toutes ses requêtes par cette WebView (même mécanisme que
                    //   LuluVdo / Netu, voir needsWebViewDs dans les fragments du lecteur).
                    preparerWebViewPartagee(android.webkit.WebSettings.getDefaultUserAgent(
                        com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext), referer)
                    android.util.Log.d("DroploadExtractor", "vérification passée dans le navigateur intégré → flux du lecteur")
                    // En-têtes ESSENTIELS seulement : la WebView annonce « Android WebView 155 »
                    //   dans sec-ch-ua mais « Chrome/131 » dans User-Agent — incohérence qu'un vrai
                    //   Chrome n'envoie jamais (403 sur ds*.dropcdn.io côté lecteur).
                    val entetes = LinkedHashMap<String, String>()
                    entetes["User-Agent"] = capte.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                        ?: DEFAULT_USER_AGENT
                    entetes["Referer"] = "$referer/"
                    entetes["Origin"] = referer
                    android.util.Log.d("DroploadExtractor", "en-têtes lecteur : UA=${entetes["User-Agent"]?.takeLast(40)}")
                    return Video(source = url, headers = entetes, extraBuffering = true)
                }
                android.util.Log.w("DroploadExtractor", "vérification anti-robot non passée sans intervention")
                throw Exception("Dropload: vérification anti-robot (Cloudflare) non passée")
            }
            android.util.Log.w("DroploadExtractor", "aucun script compressé (HTTP ${response.code()}, ${html.length} car.) : " +
                html.take(200).replace("\n", " "))
            throw Exception("Dropload: Packed JS not found")
        }
        val scriptData = html.substring(debutPack).substringBefore("</script>")
        val direct = depuisScript(scriptData, referer)
        // 2026-11-24 (banc : 403 sur r1/ds1.dropcdn.io, alors que Chrome lit) : le site fabrique
        //   pour une requête « hors navigateur » un lien que son CDN refuse ensuite (refusé même
        //   rejoué dans Chrome). On vérifie donc le lien ; s'il est refusé, on ouvre la page dans
        //   le navigateur intégré (comme pour la page de vérification) et on lit à travers lui.
        val code = codeHttp(direct.source, direct.headers ?: emptyMap())
        android.util.Log.d("DroploadExtractor", "vérif. du flux direct : HTTP $code")
        if (code != 401 && code != 403) return direct
        return viaNavigateur(link, origineLien, referer)
            ?: throw Exception("Dropload: flux refusé (HTTP $code) et non capté par le navigateur intégré")
    }

    private suspend fun codeHttp(url: String, entetes: Map<String, String>): Int =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 6_000; c.readTimeout = 6_000
                entetes.forEach { (k, v) -> c.setRequestProperty(k, v) }
                try { c.responseCode } finally { c.disconnect() }
            }.getOrDefault(-1)
        }

    /** Page ouverte dans un vrai navigateur intégré ; le lecteur lira ensuite À TRAVERS lui. */
    private suspend fun viaNavigateur(link: String, origineLien: String, referer: String): Video? {
        val uaWebView = android.webkit.WebSettings.getDefaultUserAgent(
            com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext)
        val capte = runCatching {
            com.streamflixreborn.streamflix.utils.WebViewStreamResolver.resolve(
                entryUrl = link,
                referer = origineLien,
                userAgent = uaWebView,
                timeoutMs = 20_000L,
                clickPlay = true,
                attach = true,
            )
        }.getOrNull()
        val url = capte?.url?.takeIf { it.startsWith("http") && (it.contains(".m3u8") || it.contains(".mp4")) }
        android.util.Log.d("DroploadExtractor", "navigateur intégré : ${url?.substringBefore('?')?.takeLast(60) ?: "rien capté"}")
        if (url == null) return null
        preparerWebViewPartagee(uaWebView, referer)
        val entetes = LinkedHashMap<String, String>()
        entetes["User-Agent"] = capte.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: uaWebView
        entetes["Referer"] = "$referer/"
        entetes["Origin"] = referer
        return Video(source = url, headers = entetes, extraBuffering = true)
    }

    private fun depuisScript(scriptData: String, referer: String): Video {
        val unpacked = JsUnpacker(scriptData).unpack() ?: run {
            android.util.Log.w("DroploadExtractor", "décompression KO, fin du script : " +
                scriptData.takeLast(160).replace("\n", " "))
            throw Exception("Unpack failed")
        }

        val fileRegex = Regex("""file\s*:\s*[\"']([^\"']+)[\"']""")
        val streamUrl = fileRegex.find(unpacked)?.groupValues?.get(1)
            ?: throw Exception("Stream URL not found in file field")

        // Parse tracks -> captions (exclude thumbnails)
        val tracksBlock = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(unpacked)
            ?.groupValues?.get(1)
            ?: ""
        val captionMatches = Regex("""file\s*:\s*\"(.*?)\"\s*,\s*label\s*:\s*\"(.*?)\"\s*,\s*kind\s*:\s*\"captions\"""")
            .findAll(tracksBlock)
        val subtitles = captionMatches.map {
            Video.Subtitle(
                label = it.groupValues[2],
                file = it.groupValues[1]
            )
        }.toList()

        return Video(
            source = streamUrl,
            subtitles = subtitles,
            headers = mapOf(
                "Referer" to referer
            ),
            extraBuffering = true
        )
    }
}
