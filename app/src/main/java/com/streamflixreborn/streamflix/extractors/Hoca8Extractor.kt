package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Hoca8 / bolaloca / cartelive / embedme — extracteur WebView pour chaînes sport
 * et Canal+ Live.
 *
 * Architecture :
 *   bolaloca.my/player/2/<id>  ─┐
 *   cartelive.club/player/2/<id> ├─→ iframe → hoca8.com/footy.php?live=<feedId>
 *   embedme.click/player/2/<id> ─┘                  └→ JS charge le m3u8 dynamiquement
 *
 * Le m3u8 final est délivré par un CDN externe (cloudflare-hosted typiquement).
 * On laisse WebView faire le chargement complet et on intercepte la requête m3u8.
 */
open class Hoca8Extractor : Extractor() {
    override val name = "Hoca8"
    override val mainUrl = "https://hoca8.com"
    override val aliasUrls = listOf(
        "https://bolaloca.my",
        "https://cartelive.club",
        "https://embedme.click",
    )

    // Pas de cache — best-effort sur stream live, le m3u8 peut tokeniser.
    override val cacheTtlMs: Long = 0L

    private val context = StreamFlixApp.instance.applicationContext

    /** 2026-08-22 : Referer réellement employé par la WebView pour la requête
     *  m3u8 interceptée. La même façade (cartelive/bolaloca/embedme) sert
     *  PLUSIEURS CDN selon le 1er segment de l'URL — /player/2/ passe par
     *  hoca8, /player/1/ par barecrop, /player/4/ par instreams. Renvoyer un
     *  Referer hoca8 figé faisait refuser les deux autres. Null = non capté
     *  (chemin JS bridge) → on retombe sur l'ancien comportement. */
    @Volatile private var dernierReferer: String? = null

    /** 2026-08-22 : URL de la page d'embed réellement chargée dans l'iframe
     *  (hoca8/footy.php, barecrop/embed, lockpop/embed, instream/hlsspanich).
     *  Quand le m3u8 est capté par le PONT JS et non par l'interception réseau,
     *  aucun en-tête Referer n'est disponible — on retombait alors sur hoca8.com
     *  et le CDN répondait 403. C'est cette page-là que le navigateur enverrait
     *  comme Referer, donc c'est elle qu'on rejoue. */
    @Volatile private var dernierEmbedUrl: String? = null
    /** 2026-09-05 : page (location.href) de la frame qui a réellement demandé le m3u8,
     *  transmise par le pont JS. C'est LE Referer que le CDN attend. */
    @Volatile private var pageCapture: String? = null

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video {
        dernierReferer = null
        dernierEmbedUrl = null
        pageCapture = null
        val streamUrl = extractByIntercepting(link)
            ?: throw Exception("Hoca8: Could not capture stream URL from $link")
        val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
        // 2026-08-22 : Referer/Origin dérivés de la requête réellement
        //   interceptée (cf. dernierReferer). Repli sur hoca8.com si rien n'a
        //   été capté, pour garder EXACTEMENT le comportement d'avant.
        //   Priorité : la page d'embed chargée (valable pour les DEUX chemins de
        //   capture, pont JS compris) > le Referer de la requête interceptée >
        //   hoca8 en dernier recours.
        // 2026-09-05 : avec la règle générique d'injection (toute iframe), dernierEmbedUrl
        //   pouvait être la DERNIÈRE iframe injectée — une pub (hmtraff, adsco.re…) — et le
        //   CDN répondait 403 aux segments. Priorité désormais à la page qui a VRAIMENT
        //   demandé le m3u8 (pont JS), puis au Referer de la requête interceptée, puis à
        //   la dernière page d'embed CONNUE, puis hoca8.
        val referer = pageCapture?.takeIf { it.startsWith("http") }
            ?: dernierReferer?.takeIf { it.isNotBlank() }
            ?: dernierEmbedUrl?.takeIf { it.isNotBlank() }
            ?: "https://hoca8.com/"
        val origine = runCatching {
            java.net.URL(referer).let { u -> "${u.protocol}://${u.host}" }
        }.getOrDefault("https://hoca8.com")
        return Video(
            source = streamUrl,
            headers = mapOf(
                "Referer" to referer,
                "Origin" to origine,
                "User-Agent" to ANDROID_CHROME_UA,
            ),
            type = if (isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractByIntercepting(url: String): String? =
        withContext(Dispatchers.Main) {
            // 2026-11-15 (user) : délai de garde 20 → 25 s — quand le lecteur passe par sa vérification,
            //   l'adresse n'arrive qu'après 8 s et plus sur un Wi-Fi chargé, la marge était trop courte.
            withTimeoutOrNull(25_000L) {
                suspendCancellableCoroutine { cont ->
                    var resolved = false

                    fun resolve(value: String?) {
                        if (!resolved && cont.isActive) {
                            resolved = true
                            cont.resume(value)
                        }
                    }

                    /** 2026-11-15 : un m3u8 peut arriver dans le CORPS d'une réponse
                     *  (le lecteur SportsOnline le renvoie en JSON après vérification).
                     *  On le sort nous-mêmes, sans dépendre du JS de la page qui a pu
                     *  déjà abandonner. */
                    fun resolveDepuisCorps(texte: String, base: String) {
                        val absolu = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""")
                            .find(texte)?.value
                        val relatif = if (absolu == null)
                            Regex("""(/[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*)""").find(texte)?.value
                        else null
                        val trouve = absolu ?: relatif?.let {
                            runCatching {
                                java.net.URL(java.net.URL(base), it).toString()
                            }.getOrNull()
                        }
                        if (trouve != null) {
                            android.util.Log.d("Hoca8Extractor", "m3u8 lu dans le corps de la réponse")
                            android.os.Handler(android.os.Looper.getMainLooper()).post { resolve(trouve) }
                        }
                    }

                    /** 2026-11-15 (user, durcissement) : l'adresse de la vidéo est choisie par NOM DE CLÉ dans
                     *  la réponse, jamais par position (l'ordre et le nombre d'adresses peuvent changer). Ordre
                     *  de préférence ci-dessous : les versions « sans P2P » d'abord — le manifeste P2P a donné
                     *  « format illisible » au lecteur ONYX. `\/` et `\u0026` sont déséchappés et l'adresse
                     *  rendue absolue. Rend (clé, adresse), ou null si aucune de ces clés n'est renseignée. */
                    fun adresseVideo(texte: String, base: String): Pair<String, String>? {
                        val json = runCatching { org.json.JSONObject(texte) }.getOrNull()
                        for (cle in CLES_ADRESSE_VIDEO) {
                            val brut = json?.optString(cle)?.takeIf { it.isNotBlank() && it != "null" }
                                // Réponse qui n'est pas du JSON propre : la même clé, lue dans le texte.
                                ?: Regex("\"" + cle + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"").find(texte)?.groupValues?.get(1)
                                ?: continue
                            val net = brut.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim()
                            val absolue = runCatching { java.net.URL(java.net.URL(base), net).toString() }.getOrNull()
                                ?.takeIf { it.startsWith("http") } ?: continue
                            return cle to absolue
                        }
                        return null
                    }

                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = ANDROID_CHROME_UA
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        // 2026-11-15 (SportsOnline) : la vérification invisible vit dans une
                        //   iframe TIERCE (challenges.cloudflare.com). Sans cookies tiers, le
                        //   widget ne garde pas son état et ne rend jamais son jeton → la page
                        //   n'envoie jamais son POST et la vidéo n'arrive pas. Chrome les accepte,
                        //   la WebView les refuse par défaut. Ce n'est PAS un réglage « comme
                        //   Chrome » suffisant à lui seul : il faut aussi le viewport ci-dessous.
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    }
                    // 2026-11-15 (SportsOnline) : la WebView n'est jamais attachée à une fenêtre.
                    //   Sans mesure ni layout son viewport reste 0×0 ; un widget INVISIBLE se croit
                    //   hors écran et ne s'exécute pas. On lui donne une taille réelle.
                    webView.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                    )
                    webView.layout(0, 0, 1080, 1920)

                    // 2026-05-16 v6 : bridge JS pour capturer l'URL .m3u8 in-page
                    // (HLS.js la fetch en mémoire, jamais visible par shouldInterceptRequest).
                    webView.addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun onM3u8Found(url: String, page: String) {
                            if (page.startsWith("http")) pageCapture = page
                            android.util.Log.d("Hoca8Extractor", "JS captured m3u8 (page $page)")
                            onM3u8Found(url)
                        }

                        @android.webkit.JavascriptInterface
                        fun onM3u8Found(url: String) {
                            android.util.Log.d("Hoca8Extractor", "JS captured m3u8: $url")
                            if (url.isNotBlank()) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    resolve(url)
                                }
                            }
                        }

                        /**
                         * 2026-11-15 (user, SportsOnline : « il n'y a aucun CF, c'est un autre truc… on n'a
                         * peut-être pas l'extracteur pour ce nouveau lecteur ») — par moments, la page du
                         * lecteur ne contient pas la vidéo : elle l'obtient par un envoi `POST /turnstile/<id>`
                         * (jeton de la vérification invisible, que la WebView passe bien). MESURÉ : cet envoi
                         * reçoit « 403 » dès qu'il porte l'en-tête « X-Requested-With: <nom de l'appli> »,
                         * que la WebView ajoute d'office, et une vraie réponse sans lui (depuis le PC du
                         * user : 403 avec, réponse du serveur sans ; sur l'Oppo : 403). C'est le même refus
                         * que pour la page du lecteur, que cet extracteur va déjà chercher lui-même pour
                         * cette raison. On fait donc pareil pour cet envoi : même adresse, même contenu,
                         * même type de contenu, mêmes cookies, sans l'en-tête. Réservé à l'adresse
                         * `/turnstile/<id>` du lecteur qui l'appelle (même hôte que sa page), pour qu'un
                         * cadre de pub ne s'en serve pas.
                         *
                         * 2026-11-15 (correctif) : trois ajouts — le type de contenu envoyé par la page est
                         * respecté (le lecteur poste du JSON, pas du formulaire) ; le garde-fou de route
                         * accepte tout identifiant ; et le m3u8 présent dans la réponse est remonté
                         * directement, sans attendre que la page le lise.
                         *
                         * Retour : « <code HTTP>\n<corps> », ou vide si rien n'a été envoyé.
                         */
                        @android.webkit.JavascriptInterface
                        fun poster(adresse: String, corps: String, typeContenu: String, page: String): String {
                            return try {
                                val u = java.net.URL(adresse); val p = java.net.URL(page)
                                if (u.protocol != "https" || !u.host.equals(p.host, ignoreCase = true) ||
                                    !Regex("^/turnstile/[^/?#]+$").matches(u.path)) return ""
                                val origine = "${p.protocol}://${p.host}"
                                val type = typeContenu.ifBlank {
                                    if (corps.trimStart().startsWith("{")) "application/json"
                                    else "application/x-www-form-urlencoded"
                                }
                                val b = okhttp3.Request.Builder().url(adresse)
                                    .header("User-Agent", ANDROID_CHROME_UA)
                                    .header("Referer", page).header("Origin", origine)
                                    .header("Accept", "application/json, text/plain, */*")
                                    .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                                    .post(corps.toRequestBody(type.toMediaType()))
                                runCatching { android.webkit.CookieManager.getInstance().getCookie(adresse) }
                                    .getOrNull()?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
                                val client = okhttp3.OkHttpClient.Builder()
                                    .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()
                                client.newCall(b.build()).execute().use { r ->
                                    val texte = r.peekBody(200_000L).string()
                                    android.util.Log.d("Hoca8Extractor", "envoi /turnstile/ relayé → HTTP ${r.code} (${texte.length} car.)")
                                    if (r.code in 200..299) {
                                        if (page.startsWith("http")) pageCapture = page
                                        // 2026-11-15 (user : « par nom de clé, pas par position ») — cf. adresseVideo.
                                        val parCle = adresseVideo(texte, adresse)
                                        if (parCle != null) {
                                            android.util.Log.d("Hoca8Extractor", "adresse vidéo prise dans « ${parCle.first} »")
                                            android.os.Handler(android.os.Looper.getMainLooper()).post { resolve(parCle.second) }
                                        } else resolveDepuisCorps(texte, adresse)   // dernier recours
                                    }
                                    "${r.code}\n$texte"
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("Hoca8Extractor", "envoi /turnstile/ relayé : ${e.message}")
                                ""
                            }
                        }
                    }, "Hoca8Bridge")

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean,
                            isUserGesture: Boolean, resultMsg: Message?
                        ): Boolean = false
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val host = request?.url?.host ?: return true
                            // Block popunder ads qui essayent d'ouvrir des onglets
                            return BLOCKED_HOSTS.any { host.contains(it) }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val host = request.url?.host ?: ""

                            if (BLOCKED_HOSTS.any { host.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            val path = request.url?.path?.lowercase() ?: ""
                            val hostLower = host.lowercase()
                            // 2026-05-16 v5 : exclure les CDN de scripts/libs.
                            val isCdnLib = hostLower.contains("jsdelivr.net") ||
                                hostLower.contains("cdnjs.cloudflare.com") ||
                                hostLower.contains("unpkg.com") ||
                                hostLower.contains("googleapis.com") ||
                                hostLower.contains("googletagmanager") ||
                                hostLower.contains("google-analytics")
                            if (isCdnLib) return null

                            // 2026-05-17 v7 : log toutes les requêtes pour debug
                            //   (sauf images / fonts / css qui spamment)
                            if (!path.endsWith(".png") && !path.endsWith(".jpg") &&
                                !path.endsWith(".jpeg") && !path.endsWith(".gif") &&
                                !path.endsWith(".webp") && !path.endsWith(".svg") &&
                                !path.endsWith(".woff") && !path.endsWith(".woff2") &&
                                !path.endsWith(".ttf") && !path.endsWith(".css") &&
                                !path.endsWith(".ico")) {
                                android.util.Log.d("Hoca8Extractor", "REQ: $reqUrl")
                            }

                            // 2026-05-17 v9 : intercepter la réponse HTML de
                            //   hoca8.com/footy.php (l'iframe) et y injecter
                            //   notre hook JS au début du <head>. Comme ça
                            //   HLS.js s'initialise dans un contexte où XHR
                            //   et fetch sont déjà hookés → capture du m3u8.
                            //   On évite le re-route v8 qui menait à /sorry.
                            // 2026-08-22 : MÊME injection pour les autres hébergeurs
                            //   servis par la MÊME façade cartelive/bolaloca/embedme :
                            //   /player/1/ → barecrop.net, /player/3/ → lockpop.net,
                            //   /player/4/ → instream.click. Sans elle, HLS.js y récupère
                            //   le m3u8 en mémoire et shouldInterceptRequest ne voit
                            //   jamais passer la playlist → extraction en échec, écran
                            //   noir. Diagnostic du 22/08 : l'iframe barecrop se chargeait
                            //   bien, mais aucun "HLS PLAYLIST CAPTURED" ne suivait.
                            // 2026-09-05 (user : « Multi Live ne marche plus ») : la façade
                            //   /player/1/ ne pointe plus vers barecrop.net mais vers
                            //   cuttingfame.net/embed/<id> — hôte inconnu de cette liste, donc
                            //   pas d'injection, HLS.js garde le m3u8 en mémoire, écran noir
                            //   (le site, lui, joue). La liste blanche par nom d'hôte casse à
                            //   CHAQUE changement d'hébergeur ; on la garde, mais on y ajoute
                            //   une règle GÉNÉRIQUE : tout document HTML, hors pubs
                            //   (BLOCKED_HOSTS, déjà écartées plus haut) et hors façade
                            //   cartelive/bolaloca/embedme, est une page de lecteur → hook.
                            //   Le hook ne fait que surveiller XHR/fetch pour repérer un
                            //   .m3u8 : inoffensif dans une iframe qui n'en charge pas.
                            // 2026-11-15 (SportsOnline, vu au journal) : le lecteur affiche la vérification
                            //   Cloudflare dans une sous-frame ; la règle générique y injectait notre
                            //   script et la vérification ne se terminait jamais (aucun flux capté).
                            //   On laisse cette frame telle quelle.
                            // 2026-11-15 (correctif) : la règle ne visait que les SOUS-frames
                            //   (`!isForMainFrame`). La page principale de SportsOnline prend le même
                            //   403 que ses iframes : on la refait aussi passer par OkHttp.
                            val accept = request.requestHeaders?.get("Accept").orEmpty()
                            val estFacade = hostLower.contains("cartelive") ||
                                hostLower.contains("bolaloca") || hostLower.contains("embedme")
                            val estVerification = hostLower.contains("challenges.cloudflare.com")
                            val estDocHtml = !estVerification && !estFacade &&
                                accept.contains("text/html") && !path.endsWith(".js") &&
                                !path.endsWith(".json")
                            val estPageEmbed =
                                (hostLower.contains("hoca8.com") && path.contains("/footy.php")) ||
                                (hostLower.contains("barecrop.net") && path.contains("/embed/")) ||
                                (hostLower.contains("cuttingfame.net") && path.contains("/embed/")) ||
                                (hostLower.contains("lockpop.net") && path.contains("/embed/")) ||
                                (hostLower.contains("instream.click") &&
                                    (path.contains("hlsspanich.php") || path.contains("player.php"))) ||
                                estDocHtml
                            if (estDocHtml) android.util.Log.d("Hoca8Extractor", "document HTML refait sans en-tête d'appli : $reqUrl")
                            if (estPageEmbed && !estDocHtml) dernierEmbedUrl = reqUrl   // hôtes connus seulement
                            if (estPageEmbed && request.method?.uppercase() == "GET") {
                                try {
                                    val client = okhttp3.OkHttpClient.Builder()
                                        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                        .followRedirects(true)
                                        .build()
                                    val req = okhttp3.Request.Builder()
                                        .url(reqUrl)
                                        // hoca8 attend le referer historique ; les
                                        //   autres hébergeurs attendent la page de
                                        //   façade réellement chargée (sinon 403).
                                        .header(
                                            "Referer",
                                            if (hostLower.contains("hoca8.com"))
                                                "https://bolaloca.my/" else url
                                        )
                                        .header("User-Agent", ANDROID_CHROME_UA)
                                        .header("Accept", "text/html,application/xhtml+xml")
                                        .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                                        .build()
                                    val resp = client.newCall(req).execute()
                                    // 2026-10-25 : réponse lue SANS LIMITE → un « iframe » qui renvoyait
                                    //   en fait un flux remplissait la mémoire (OutOfMemoryError de 250 Mo,
                                    //   appli fermée — vu sur l'émulateur avec beIN de Multi Live). On ne
                                    //   lit que les pages HTML, et 3 Mo au plus.
                                    val typeRep = resp.header("Content-Type").orEmpty().lowercase()
                                    val body = resp.use { r ->
                                        if (typeRep.isNotEmpty() && !typeRep.contains("html")) null
                                        else r.peekBody(3_000_000L).string()
                                    }
                                    if (body != null && body.isNotEmpty()) {
                                        // Inject hook au début du <head> (ou
                                        // tout début du HTML si pas de <head>).
                                        val scriptTag = "<script>$HOOK_M3U8_JS</script>"
                                        val injected = when {
                                            body.contains("<head>", ignoreCase = true) ->
                                                body.replaceFirst(
                                                    Regex("(?i)<head>"),
                                                    "<head>$scriptTag"
                                                )
                                            body.contains("<html", ignoreCase = true) ->
                                                body.replaceFirst(
                                                    Regex("(?i)(<html[^>]*>)"),
                                                    "$1$scriptTag"
                                                )
                                            else -> scriptTag + body
                                        }
                                        android.util.Log.d("Hoca8Extractor", "INJECT HOOK into $reqUrl (${body.length}b)")
                                        return WebResourceResponse(
                                            "text/html",
                                            "utf-8",
                                            injected.byteInputStream(Charsets.UTF_8)
                                        )
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("Hoca8Extractor", "iframe inject failed: ${e.message}")
                                }
                            }

                            // Case 1 : la vraie playlist .m3u8 directement (rare ici car
                            // P2P HLS la manipule en mémoire avant le video player).
                            val hasM3u8 = path.endsWith(".m3u8") ||
                                path.contains(".m3u8?") ||
                                path.contains(".m3u8/") ||
                                reqUrl.contains(".m3u8", ignoreCase = true)
                            if (hasM3u8) {
                                android.util.Log.d("Hoca8Extractor", "HLS PLAYLIST CAPTURED: $reqUrl")
                                dernierReferer = request.requestHeaders?.get("Referer")
                                resolve(reqUrl)
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            // v6 : la dérivation depuis .ts ne marche pas (403 sur les
                            // m3u8 dérivés — le serveur exige un token de session que
                            // seul HLS.js détient). On compte sur le JS bridge injecté
                            // (Hoca8Bridge.onM3u8Found) qui hook XHR/fetch.
                            return null
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            if (view == null || resolved) return
                            android.util.Log.d("Hoca8Extractor", "onPageStarted: $url")
                            // 2026-05-16 v6 : inject JS hook AVANT que HLS.js charge.
                            // Hook XMLHttpRequest.prototype.open ET window.fetch pour
                            // capturer toute requête .m3u8 + notifier Kotlin.
                            view.evaluateJavascript(HOOK_M3U8_JS, null)
                        }

                        // 2026-11-15 (diagnostic SportsOnline) : réponses en erreur reçues par la WebView.
                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                            android.util.Log.d("Hoca8Extractor", "HTTP ${errorResponse?.statusCode} ← ${request?.method} ${request?.url.toString().substringBefore('?').take(110)}")
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (view == null || resolved) return
                            android.util.Log.d("Hoca8Extractor", "onPageFinished: $finishedUrl")
                            // Re-inject au cas où la page nav reset les hooks
                            view.evaluateJavascript(HOOK_M3U8_JS, null)
                            // 2026-05-17 v7 : démarre un poll qui scanne window.*
                            //   variables où HLS.js / players P2P stockent l'URL.
                            view.evaluateJavascript(POLL_VARS_JS, null)
                            // 2026-11-15 : si le jeton est arrivé tard et que la page a
                            //   déjà renoncé à son envoi, on le rejoue nous-mêmes.
                            view.postDelayed({
                                if (!resolved) {
                                    view.evaluateJavascript(
                                        "try{window.__hoca8_turnstile_retry&&window.__hoca8_turnstile_retry()}catch(e){}",
                                        null
                                    )
                                }
                            }, 8_000L)
                            view.postDelayed({
                                if (!resolved) {
                                    android.util.Log.w("Hoca8Extractor", "Timeout — no stream captured")
                                    resolve(null)
                                }
                            // 15 → 20 s après la fin du chargement : c'est CE délai-ci qui tombait en premier
                            //   (page finie en ~1 s), il doit suivre le délai de garde ci-dessus.
                            }, 20_000L)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) { /* ignore */ }
                    }

                    // Referer freeshot.live = origin légitime (les mirrors checkent
                    // l'origin pour anti-hotlink).
                    webView.loadUrl(url, mapOf("Referer" to "https://www.freeshot.live/"))

                    cont.invokeOnCancellation {
                        resolved = true
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                webView.stopLoading()
                                webView.destroy()
                            } catch (_: Exception) { }
                        }
                    }
                }
            }
        }

    companion object {
        /** 2026-05-16 v6 : JS injecté dans la page pour hook XHR + fetch et
         *  capturer l'URL .m3u8 que HLS.js charge en mémoire. Notifie Kotlin
         *  via Hoca8Bridge.onM3u8Found(url). Idempotent (peut être ré-injecté). */
        /** 2026-05-17 v7 : poll JS qui scanne window.* variables + DOM
         *  pour trouver une URL .m3u8. Tente toutes les 500ms pendant
         *  10s. Patterns testés :
         *   - window.hls.url / window.hls.media.src
         *   - window.player.src / window.video.src
         *   - <video>.src / <source src=...>
         *   - window.streamUrl, window.m3u8Url, window.playerUrl
         *   - window.HLSConfig.url
         *  Si trouvé, notifie Hoca8Bridge.onM3u8Found(url). */
        private val POLL_VARS_JS = """
            (function() {
                if (window.__hoca8_polled) return;
                window.__hoca8_polled = true;
                var attempts = 0;
                var maxAttempts = 20;
                function scan() {
                    attempts++;
                    var candidates = [];
                    try {
                        if (window.hls && window.hls.url) candidates.push(window.hls.url);
                        if (window.hls && window.hls.media && window.hls.media.src) candidates.push(window.hls.media.src);
                        if (window.hls && window.hls.media && window.hls.media.currentSrc) candidates.push(window.hls.media.currentSrc);
                        if (window.player && window.player.src) candidates.push(window.player.src);
                        if (window.player && window.player.src_) candidates.push(window.player.src_);
                        if (window.player && window.player.options_ && window.player.options_.sources) {
                            var srcs = window.player.options_.sources;
                            if (Array.isArray(srcs)) srcs.forEach(function(s){ if(s && s.src) candidates.push(s.src); });
                        }
                        if (window.video && window.video.src) candidates.push(window.video.src);
                        if (window.streamUrl) candidates.push(window.streamUrl);
                        if (window.m3u8Url) candidates.push(window.m3u8Url);
                        if (window.playerUrl) candidates.push(window.playerUrl);
                        if (window.HLSConfig && window.HLSConfig.url) candidates.push(window.HLSConfig.url);
                        var vids = document.querySelectorAll('video');
                        for (var i = 0; i < vids.length; i++) {
                            if (vids[i].src) candidates.push(vids[i].src);
                            if (vids[i].currentSrc) candidates.push(vids[i].currentSrc);
                        }
                        var sources = document.querySelectorAll('source');
                        for (var j = 0; j < sources.length; j++) {
                            if (sources[j].src) candidates.push(sources[j].src);
                        }
                    } catch(e) {}
                    for (var k = 0; k < candidates.length; k++) {
                        var u = candidates[k];
                        if (typeof u === 'string' && u.indexOf('.m3u8') !== -1) {
                            try {
                                if (typeof Hoca8Bridge !== 'undefined') {
                                    Hoca8Bridge.onM3u8Found(u, String(location.href));
                                }
                            } catch(e) {}
                            return;
                        }
                    }
                    if (attempts < maxAttempts) {
                        setTimeout(scan, 500);
                    }
                }
                setTimeout(scan, 300);
            })();
        """.trimIndent()

        /** 2026-11-15 (SportsOnline) : le relais du POST de vérification ne couvrait
         *  que `fetch`. Le lecteur passe par XMLHttpRequest → le POST partait avec
         *  l'en-tête de l'appli, prenait 403, et le relais n'était jamais appelé.
         *  On couvre désormais les DEUX, en respectant le corps et le type de
         *  contenu réellement envoyés par la page, et on note la dernière adresse
         *  de vérification pour pouvoir la rejouer si le jeton arrive en retard. */
        private val HOOK_M3U8_JS = """
            (function() {
                if (window.__hoca8_hooked) return;
                window.__hoca8_hooked = true;

                function report(u) {
                    if (typeof u !== 'string') return;
                    if (!u || u.indexOf('.m3u8') === -1) return;
                    if (u.indexOf('jsdelivr.net') !== -1 || u.indexOf('cdnjs.cloudflare') !== -1) return;
                    try {
                        if (typeof Hoca8Bridge !== 'undefined') {
                            Hoca8Bridge.onM3u8Found(u, String(location.href));
                        }
                    } catch(e) {}
                }

                function absolue(u) {
                    try { return new URL(String(u), location.href).href } catch (e) { return '' }
                }

                function estVerif(u) {
                    return typeof u === 'string' && u.indexOf('/turnstile/') !== -1;
                }

                function corpsTexte(c) {
                    try {
                        if (c == null) return '';
                        if (typeof c === 'string') return c;
                        if (typeof URLSearchParams !== 'undefined' && c instanceof URLSearchParams) return c.toString();
                        if (typeof FormData !== 'undefined' && c instanceof FormData) {
                            var o = [];
                            c.forEach(function (v, k) { o.push(encodeURIComponent(k) + '=' + encodeURIComponent(v)); });
                            return o.join('&');
                        }
                    } catch (e) {}
                    return String(c);
                }

                function relayer(u, corps, type) {
                    try {
                        if (typeof Hoca8Bridge === 'undefined' || !Hoca8Bridge.poster) return null;
                        var abs = absolue(u);
                        if (!abs) return null;
                        window.__hoca8_turnstile = abs;
                        var rep = String(Hoca8Bridge.poster(abs, corps == null ? '' : corps, type || '', String(location.href)) || '');
                        var coupe = rep.indexOf('\n');
                        if (coupe <= 0) return null;
                        var code = parseInt(rep.slice(0, coupe), 10) || 0;
                        if (code < 100) return null;
                        return { code: code, corps: rep.slice(coupe + 1) };
                    } catch (e) { return null; }
                }

                function typeEntete(h) {
                    try {
                        if (!h) return '';
                        if (typeof h.forEach === 'function' && !Array.isArray(h)) {
                            var t = '';
                            h.forEach(function (v, k) { if (String(k).toLowerCase() === 'content-type') t = v; });
                            return t;
                        }
                        if (Array.isArray(h)) {
                            for (var i = 0; i < h.length; i++) {
                                if (h[i] && String(h[i][0]).toLowerCase() === 'content-type') return h[i][1];
                            }
                            return '';
                        }
                        for (var k in h) if (String(k).toLowerCase() === 'content-type') return h[k];
                    } catch (e) {}
                    return '';
                }

                // Hook fetch
                try {
                    var origFetch = window.fetch;
                    if (origFetch) {
                        window.fetch = function(input, init) {
                            var u = (typeof input === 'string') ? input : (input && input.url) || '';
                            report(u);
                            if (estVerif(u)) {
                                var corps = init && init.body != null ? corpsTexte(init.body) : '';
                                var type = init ? typeEntete(init.headers) : '';
                                var r = relayer(u, corps, type);
                                if (r) {
                                    return Promise.resolve(new Response(r.corps, {
                                        status: r.code,
                                        headers: { 'Content-Type': type || 'application/json' }
                                    }));
                                }
                            }
                            return origFetch.apply(this, arguments);
                        };
                    }
                } catch(e) {}

                // Hook XMLHttpRequest (c'est ce chemin qu'utilise le lecteur SportsOnline)
                try {
                    var origOpen = XMLHttpRequest.prototype.open;
                    var origSend = XMLHttpRequest.prototype.send;
                    var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;

                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__h_meth = method;
                        this.__h_url = url;
                        this.__h_type = '';
                        report(url);
                        return origOpen.apply(this, arguments);
                    };

                    XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
                        if (String(k).toLowerCase() === 'content-type') this.__h_type = v;
                        return origSetHeader.apply(this, arguments);
                    };

                    XMLHttpRequest.prototype.send = function(corps) {
                        var self = this;
                        if (estVerif(self.__h_url) && String(self.__h_meth).toUpperCase() === 'POST') {
                            var r = relayer(self.__h_url, corpsTexte(corps), self.__h_type || '');
                            if (r) {
                                Object.defineProperty(self, 'readyState', { get: function () { return 4 }, configurable: true });
                                Object.defineProperty(self, 'status', { get: function () { return r.code }, configurable: true });
                                Object.defineProperty(self, 'statusText', { get: function () { return '' }, configurable: true });
                                Object.defineProperty(self, 'responseText', { get: function () { return r.corps }, configurable: true });
                                Object.defineProperty(self, 'response', { get: function () { return r.corps }, configurable: true });
                                self.getAllResponseHeaders = function () { return 'content-type: application/json' + String.fromCharCode(13, 10) };
                                self.getResponseHeader = function (n) {
                                    return String(n).toLowerCase() === 'content-type' ? 'application/json' : null;
                                };
                                setTimeout(function () {
                                    try { self.dispatchEvent(new Event('readystatechange')); } catch (e) {}
                                    try { if (self.onreadystatechange) self.onreadystatechange(); } catch (e) {}
                                    try { self.dispatchEvent(new ProgressEvent('load')); } catch (e) {}
                                    try { if (self.onload) self.onload(); } catch (e) {}
                                    try { self.dispatchEvent(new ProgressEvent('loadend')); } catch (e) {}
                                }, 0);
                                return;
                            }
                        }
                        return origSend.apply(this, arguments);
                    };
                } catch(e) {}

                // Rejeu si le jeton arrive après l'abandon de la page.
                window.__hoca8_turnstile_retry = function () {
                    try {
                        if (!window.__hoca8_turnstile) return;
                        var el = document.querySelector('input[name="cf-turnstile-response"], textarea[name="cf-turnstile-response"]');
                        var jeton = el && el.value;
                        if (!jeton) return;
                        relayer(window.__hoca8_turnstile,
                            'cf-turnstile-response=' + encodeURIComponent(jeton),
                            'application/x-www-form-urlencoded');
                    } catch (e) {}
                };
            })();
        """.trimIndent()

        /** Clés de la réponse du lecteur qui portent l'adresse de la vidéo, par ordre de préférence. */
        private val CLES_ADRESSE_VIDEO = listOf("stream_url_nop2p", "stream_url", "url_nop2p", "url", "m3u8")

        private val BLOCKED_HOSTS = listOf(
            "googlesyndication", "doubleclick", "adservice",
            "popads", "popunder", "popcash", "propellerads",
            "exoclick", "juicyads", "trafficjunky",
            "googletagmanager", "google-analytics",
            "mc.yandex.ru", "yandex.ru", "metrica.yandex",
            "cloudflareinsights.com", "static.cloudflareinsights",
            // Popunder ads spécifiques observées sur hoca8/bolaloca
            "histatsv.com", "histats.com", "popcash.net",
            "adsterra.com", "redirect.adsafe",
        )

        /** 2026-05-16 : déduit l'URL de la playlist .m3u8 à partir d'une URL
         *  de segment .ts. Patterns observés sur Hoca8 :
         *   /hls/wsmkmlfeed01-4920.ts → /hls/wsmkmlfeed01.m3u8
         *   /hls/feed_42/seg-12.ts → /hls/feed_42/index.m3u8 OR playlist.m3u8
         *   /hls/abc/123-456.ts → /hls/abc/123.m3u8 (last seg num stripped)
         *  Retourne null si pas de pattern reconnu. */
        fun deriveM3u8FromSegment(segmentUrl: String): String? {
            return try {
                val uri = android.net.Uri.parse(segmentUrl)
                val pathSegments = uri.pathSegments ?: return null
                if (pathSegments.isEmpty()) return null
                val lastSeg = pathSegments.last()
                if (!lastSeg.endsWith(".ts", ignoreCase = true)) return null
                // Strip .ts extension
                val withoutExt = lastSeg.removeSuffix(".ts").removeSuffix(".TS")
                // Si le nom est `name-NNNN` (segment numéroté), strip `-NNNN`
                // pour récupérer la base `name`. Ex: wsmkmlfeed01-4920 → wsmkmlfeed01.
                val baseName = Regex("""^(.+?)[-_]\d+$""").find(withoutExt)?.groupValues?.get(1)
                    ?: withoutExt  // si pas numéroté, garde le nom tel quel
                val builder = uri.buildUpon().clearQuery()
                builder.path("")
                // Reconstruit le path : tous les segments sauf le dernier + <baseName>.m3u8
                val parentSegments = pathSegments.dropLast(1)
                parentSegments.forEach { builder.appendPath(it) }
                builder.appendPath("$baseName.m3u8")
                builder.build().toString()
            } catch (_: Exception) { null }
        }
    }
}
