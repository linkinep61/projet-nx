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

        @android.annotation.SuppressLint("SetJavaScriptEnabled")
        suspend fun preparerWebViewPartagee(ua: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (sharedWebView != null) return@withContext
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
                    wv.loadUrl("file:///android_asset/blank.html")
                }
            }
            android.util.Log.d("DroploadExtractor", "WebView de lecture prête")
        }

        fun releaseSharedWebView() {
            val wv = sharedWebView ?: return
            sharedWebView = null
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
            }
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
                        com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext))
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
        return depuisScript(scriptData, referer)
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
