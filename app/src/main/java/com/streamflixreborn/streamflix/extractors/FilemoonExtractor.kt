package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

open class FilemoonExtractor : Extractor() {

    companion object {
        private const val TAG = "FilemoonExtractor"
        private const val ANDROID_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        // 2026-07-16 : domaines de fallback. Si le TLS handshake échoue sur un domaine
        // (Cloudflare bloque certains domaines par IP/JA3, ex. bysebuho.com depuis Tahiti),
        // on retente le même video ID sur un autre domaine Byse — les IDs sont cross-domain.
        /** Temps maximal accordé à l'ensemble des domaines de repli. Au-delà, on rend la main :
     *  le lecteur bascule sur un autre serveur, ce qui vaut mieux qu'une attente stérile. */
    private const val BUDGET_REPLIS_MS = 12_000L

    /**
     * ── 2026-08-06 : LISTE RAMENÉE À UN SEUL DOMAINE ─────────────────────────────────────
     *   User : « je t'ai dit de choisir un qui fonctionnait ». Vérification faite, il avait
     *   raison : les quatre domaines répondaient EXACTEMENT la même chose (HTTP 428), parce
     *   qu'ils partagent la même infrastructure. Les enchaîner ne pouvait rien donner — ça ne
     *   faisait qu'allonger l'échec de plusieurs secondes.
     *   On ne garde donc que `filemoon.sx`, le seul dont on ait la preuve DIRECTE qu'il
     *   répond correctement : ouvert dans le navigateur, son `/api/videos/<id>/embed/details`
     *   renvoie un HTTP 200 complet (« House Of The Dragon S02E01 … »).
     *   `filemoon.org` est retiré en prime : il renvoyait du JSON malformé, donc une page qui
     *   n'est pas l'API attendue.
     */
    private val FALLBACK_DOMAINS = listOf(
            "https://filemoon.sx"
        )

        private val VIDEO_ID_REGEX = Regex("""/(e|d)/([a-zA-Z0-9]+)""")

    /**
     * 2026-08-25 : sites dont on a la PREUVE MESURÉE qu'ils sont sur la liste blanche
     * d'intégration de Filemoon (`/embed/details` rend 200 en se présentant comme eux,
     * 403 sinon). Sert aux deux chemins : l'appel API et le repli WebView.
     */
    private val PARENTS_AUTORISES = listOf("https://lecteurvideo.com/")

    /** Domaines dont la WebView ne peut pas résoudre le nom (blocage DNS du fournisseur) et
     *  qu'on doit donc servir nous-mêmes via OkHttp + DNS-over-HTTPS. */
    private val DOMAINES_A_SERVIR = listOf(
        "filemoon", "bysebuho", "bysezoxexe", "bysejikuar", "q8y5z", "moflix-stream"
    )

    private fun estDomaineFilemoon(url: String): Boolean {
        val hote = try { java.net.URL(url).host.lowercase() } catch (_: Exception) { return false }
        return DOMAINES_A_SERVIR.any { hote.contains(it) }
    }

    /**
     * Rejoue la requête avec `Extractor.sharedClient` (qui résout en DoH) et rend la réponse
     * à la WebView. Celle-ci n'a alors plus aucun nom à résoudre elle-même.
     * S'exécute sur le fil réseau de la WebView, jamais sur le fil principal.
     */
    private fun servirViaDoh(
        url: String,
        request: WebResourceRequest?
    ): WebResourceResponse? = try {
        val b = okhttp3.Request.Builder().url(url)
        request?.requestHeaders?.forEach { (k, v) ->
            // `Accept-Encoding` retiré : OkHttp gère la compression, et la réponse doit être
            //   rendue en clair à la WebView.
            if (!k.equals("Accept-Encoding", true)) b.header(k, v)
        }
        android.webkit.CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
            ?.let { b.header("Cookie", it) }
        val rep = Extractor.sharedClient.newCall(b.build()).execute()
        rep.headers("Set-Cookie").forEach {
            android.webkit.CookieManager.getInstance().setCookie(url, it)
        }
        val typeComplet = rep.header("Content-Type") ?: "text/html"
        val type = typeComplet.substringBefore(';').trim()
        val charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
            .find(typeComplet)?.groupValues?.get(1) ?: "utf-8"
        val entetes = rep.headers.toMultimap()
            .filterKeys { it.lowercase() !in setOf("content-encoding", "content-length", "transfer-encoding", "set-cookie") }
            .mapValues { it.value.last() }
            .toMutableMap()
        entetes["Access-Control-Allow-Origin"] = "*"
        WebResourceResponse(type, charset, rep.code, rep.message.ifBlank { "OK" }, entetes, rep.body?.byteStream())
    } catch (e: Exception) {
        Log.w(TAG, "[Filemoon-WV] relais DoH échoué pour ${url.take(80)} : ${e.message}")
        null
    }

        /** Vérifie si l'erreur est un problème réseau/SSL (pas un 404 côté serveur). */
        private fun isNetworkError(e: Exception): Boolean {
            val msg = e.message.orEmpty().lowercase()
            return e is javax.net.ssl.SSLHandshakeException ||
                e is javax.net.ssl.SSLException ||
                e is java.net.ConnectException ||
                e is java.net.UnknownHostException ||
                msg.contains("connection closed") ||
                msg.contains("ssl") ||
                msg.contains("handshake") ||
                msg.contains("timed out") ||
                msg.contains("connect")
        }

        /** Domains known to serve the Filemoon verification iframe. */
        private val VERIFICATION_DOMAINS = setOf("q8y5z.com")

        /**
         * JS injected into the verification iframe HTML. Clicks every interactive
         * element it can find (buttons, SVGs, clickable divs) at multiple delays
         * to ensure the play button gets hit even if it renders late.
         */
        private val AUTO_CLICK_SCRIPT = """
            <script>
            (function(){
                function tryClick(){
                    var c=document.querySelectorAll('button,[role="button"],[onclick],a,.play-btn,.btn-play,.play,.vjs-big-play-button');
                    for(var i=0;i<c.length;i++){try{c[i].click();}catch(e){}}
                    var s=document.querySelectorAll('svg,.play-icon,.fa-play,.icon-play');
                    for(var i=0;i<s.length;i++){try{s[i].click();if(s[i].parentElement)s[i].parentElement.click();}catch(e){}}
                    try{
                        var el=document.elementFromPoint(window.innerWidth/2,window.innerHeight/2);
                        if(el){el.click();if(el.parentElement)el.parentElement.click();}
                    }catch(e){}
                    try{
                        var evt=new MouseEvent('click',{bubbles:true,cancelable:true,clientX:window.innerWidth/2,clientY:window.innerHeight/2});
                        var t=document.elementFromPoint(window.innerWidth/2,window.innerHeight/2);
                        if(t)t.dispatchEvent(evt);
                    }catch(e){}
                }
                function schedule(){var d=[600,1500,2500,3800,5200,7000,9000,11500,14000,17000,20000];for(var i=0;i<d.length;i++){setTimeout(tryClick,d[i]);}}
                if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',schedule);}
                else{schedule();}
            })();
            </script>
        """.trimIndent()
    }

    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.org"
    // 2026-06-01 : nettoyé bf0skv.org + filemoon.site (NXDOMAIN)
    override val aliasUrls = listOf("https://bysejikuar.com","https://moflix-stream.link","https://bysezoxexe.com","https://bysebuho.com","https://filemoon.sx","https://bysekoze.com","https://bysesayeveum.com","https://lukefirst.lol","https://weneverbeenfree.com","https://gn1r5n.org")

    /**
     * Two-tier extraction strategy:
     *
     *   1. Fast path — REST API (`/embed/details` + `/embed/playback`).
     *      ~100-300ms when it works. Reverse-engineered from the Vite SPA bundle
     *      `videoPagesBundle-CQv1AfZY.js`. Fragile against API changes (URL
     *      patterns, encryption keys, fingerprint requirements, header tightening).
     *
     *   2. Fallback — headless WebView. Loads `/e/{videoId}` and lets the SPA do
     *      its job (decrypt playback response, build the player). Intercepts the
     *      first `*.m3u8` request via `shouldInterceptRequest` and returns it.
     *      Slower (~3-8s), but resilient to ANY backend change as long as the
     *      site itself works in a browser.
     *
     * On `404 video not found` (genuine link rot — the upload was deleted on
     * Filemoon's side) we skip the WebView fallback to fail fast: WebView would
     * just hang for 8s and then time out anyway. This lets the player jump to
     * the next server immediately.
     */
    override suspend fun extract(link: String): Video {
        return try {
            extractOnDomain(link)
        } catch (e: Exception) {
            // 2026-07-16 : fallback cross-domaine. Les IDs Filemoon/Byse sont partagés
            // entre TOUS les domaines. Si un domaine échoue (SSL, 428, timeout, WAF…),
            // on retente sur un autre. Seul cas où on ne retente PAS : link rot (vidéo
            // supprimée côté Filemoon → inutile d'essayer ailleurs, même ID = même réponse).
            val msg = e.message.orEmpty()
            if (msg.contains("link rot", ignoreCase = true) ||
                msg.contains("video expired", ignoreCase = true)
            ) {
                throw e // Vraiment mort, pas la peine de retenter ailleurs
            }
            val match = VIDEO_ID_REGEX.find(link) ?: throw e
            val linkType = match.groupValues[1]
            val videoId = match.groupValues[2]
            val originalDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1)
                ?: throw e

            Log.w(TAG, "[Filemoon] Domain $originalDomain failed (${e.javaClass.simpleName}: $msg) — trying fallback domains")

            // ── 2026-08-06 : BUDGET DE TEMPS GLOBAL ────────────────────────────────────────
            //   User : « pourquoi il n'a pas réussi la lecture et met autant de temps ».
            //   Mesuré : l'API répond **HTTP 428** (défi exigé), on bascule sur la WebView de
            //   secours qui expire au bout de **30 s**… puis on recommence À L'IDENTIQUE sur
            //   CHAQUE domaine de repli. Avec cette liste, l'échec se comptait en MINUTES,
            //   pendant lesquelles le lecteur restait bloqué sur un serveur condamné.
            //   Deux garde-fous :
            //     · un budget total : passé ce délai, on abandonne et le lecteur bascule sur
            //       un autre serveur — bien plus utile que d'attendre un miracle ;
            //     · sur les domaines de repli, on ne relance PAS la WebView de 30 s. Un 428
            //       est un refus de l'infrastructure, pas du domaine : le rejouer domaine par
            //       domaine ne change rien, seul l'appel d'API rapide vaut d'être retenté.
            val debutReplis = System.currentTimeMillis()
            for (fallback in FALLBACK_DOMAINS) {
                if (fallback.equals(originalDomain, ignoreCase = true)) continue
                if (System.currentTimeMillis() - debutReplis > BUDGET_REPLIS_MS) {
                    Log.w(TAG, "[Filemoon] budget de replis épuisé — on rend la main au lecteur")
                    break
                }
                val fallbackLink = "$fallback/$linkType/$videoId"
                Log.d(TAG, "[Filemoon] Trying fallback domain: $fallbackLink")
                try {
                    return extractOnDomain(fallbackLink, autoriserWebView = false)
                } catch (fe: Exception) {
                    val fMsg = fe.message.orEmpty()
                    if (fMsg.contains("link rot", ignoreCase = true) ||
                        fMsg.contains("video expired", ignoreCase = true)
                    ) {
                        throw fe // Vidéo morte sur ce domaine aussi
                    }
                    Log.w(TAG, "[Filemoon] Fallback $fallback failed: ${fe.javaClass.simpleName}: $fMsg")
                    // Continue vers le prochain domaine
                }
            }
            // Tous les fallbacks ont échoué
            throw e
        }
    }

    /** Extraction sur un seul domaine : API d'abord, puis WebView fallback. */
    /**
     * @param autoriserWebView la WebView de secours coûte 30 s en cas d'échec. On ne la joue
     *   que sur le domaine D'ORIGINE : sur les domaines de repli, elle rejouerait le même
     *   refus d'infrastructure (HTTP 428) pour le même prix. Cf. le budget dans `extract`.
     */
    private suspend fun extractOnDomain(link: String, autoriserWebView: Boolean = true): Video {
        return try {
            extractViaApi(link)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("link rot", ignoreCase = true) ||
                msg.contains("video expired", ignoreCase = true)
            ) {
                Log.w(TAG, "[Filemoon] Link rot detected — failing fast: $msg")
                throw e
            }
            if (!autoriserWebView) throw e
            Log.w(TAG, "[Filemoon] API failed (${e.javaClass.simpleName}: $msg) — trying WebView fallback")
            try {
                extractViaWebView(link)
            } catch (we: Exception) {
                Log.e(TAG, "[Filemoon] WebView fallback also failed: ${we.message}")
                throw e
            }
        }
    }

    private suspend fun extractViaApi(link: String): Video {
        val service = Extractor.createGsonService<Service>(mainUrl)
        // Regex to match /e/ or /d/ and ID
        val matcher = Regex("""/(e|d)/([a-zA-Z0-9]+)""").find(link) 
            ?: throw Exception("Could not extract video ID or type")
        
        val linkType = matcher.groupValues[1]
        val videoId = matcher.groupValues[2]
        
        val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1)
            ?: throw Exception("Could not extract Base URL")

        // Parent provider URL — required by some Filemoon variants that enforce a
        // per-video allowlist of embedding domains (e.g. weneverbeenfree.com — a
        // "Byse Frontend" SPA used by VoirAnime/VoirDrama). Without these headers,
        // the details endpoint returns 403 "embedding from this domain is not allowed".
        // ── 2026-08-06 : NE PLUS INVENTER UN PARENT — LE 428 VENAIT DE LÀ ─────────────────
        //   Preuve obtenue en ouvrant le MÊME lien dans le navigateur, une fois le DNS du PC
        //   dé-filtré :
        //     · navigateur → `/api/videos/<id>/embed/details` répond **HTTP 200** avec le JSON
        //       complet (« House Of The Dragon S02E01 … ») ;
        //     · l'app       → **HTTP 428** sur TOUS les domaines.
        //   Le lien, le domaine et le réseau sont donc hors de cause. Seule différence : le
        //   RÉFÉRENT. Le navigateur interroge l'API depuis la page elle-même, en MÊME ORIGINE.
        //   Nous envoyions une origine ÉTRANGÈRE, prise faute de mieux sur
        //   `currentProvider.baseUrl` — soit `api.movix.fun`, une API qui n'embarque rien.
        //   Le pare-feu refusait, à juste titre.
        //   ⚠ On ne garde donc QUE les parents explicitement connus (table `byseParentUrl`,
        //     établie pour VoirAnime dont le WAF les EXIGE). Sinon on reste en même origine,
        //     comme le navigateur — c'est ce que fait déjà la branche `else` plus bas.
        //   ⚠⚠ NE PAS restaurer le repli `currentProvider.baseUrl` : l'adresse du provider
        //     courant n'a aucune raison d'être une page qui intègre ce lecteur.
        val parentUrl = byseParentUrl(currentDomain)
        val parentOrigin = parentUrl?.trimEnd('/')

        // X-Embed-* trio — required by the Byse SPA in embed mode (videoPagesBundle Lt/Nt).
        // The frontend always sends these on /embed/details and /embed/playback when
        // operating from a parent provider's iframe. Without them, lukefirst.lol /
        // weneverbeenfree.com / filemoon.site WAFs return 403/404 even for valid videos.
        val embedParent = link
        val embedOrigin = currentDomain
        val embedReferer = parentUrl ?: link

        val detailsHeaders = mutableMapOf(
            "User-Agent" to Extractor.DEFAULT_USER_AGENT,
            "Accept" to "application/json",
            "X-Embed-Parent" to embedParent,
            "X-Embed-Origin" to embedOrigin,
            "X-Embed-Referer" to embedReferer
        ).also { it.putAll(entetesNavigateur()) }
        if (parentUrl != null) {
            detailsHeaders["Referer"] = parentUrl
            detailsHeaders["Origin"] = parentOrigin!!
        } else {
            detailsHeaders["Referer"] = link
        }

        val detailsUrl = "$currentDomain/api/videos/$videoId/embed/details"

        /**
         * ── 2026-08-25 : LISTE BLANCHE D'INTÉGRATION (user : « Filemoon part direct chez
         *    eux, pas chez nous ») ─────────────────────────────────────────────────────────
         *
         *   La page ouverte dans Chrome le dit en toutes lettres :
         *     « Intégration bloquée sur ce site — Le propriétaire de la vidéo autorise
         *       uniquement l'intégration de son lecteur sur des domaines autorisés. »
         *
         *   Ce n'est donc NI un blocage réseau, NI nos en-têtes : Filemoon tient une liste
         *   blanche de sites intégrateurs, et le 403 tombe pour tous les autres. Mesuré en
         *   direct sur `zhsq321clbwv`, même requête, seul le référent change :
         *     Referer https://movix.fun/        → 403
         *     Referer https://api.movix.fun/    → 403
         *     Referer https://filemoon.sx/      → 403  (leur propre site !)
         *     Referer https://lecteurvideo.com/ → 200  {"title":"Ca Il est Revenu 1990 …"}
         *
         *   `lecteurvideo.com` est le lecteur que Movix intègre — on l'avait vu passer dans
         *   sa réponse API (`"iframe_src":"https://lecteurvideo.com/embed.php?id=…"`). C'est
         *   pour ça que CloudStream lit ces liens et pas nous.
         *
         *   ⚠ On ne touche PAS au comportement par défaut (même origine, leçon du 06/08) :
         *     on ne se présente comme intégrateur autorisé QU'APRÈS un 403, et uniquement
         *     avec des domaines dont on a la preuve qu'ils sont sur la liste.
         */
        val parentsAutorises = PARENTS_AUTORISES

        /**
         * 2026-08-25, 2e passe — CE SONT NOS `X-Embed-*` QUI NOUS DÉNONÇAIENT.
         *
         *   La 1re version rejouait avec `HashMap(detailsHeaders)` + un Referer autorisé.
         *   Refusé quand même. Test différentiel, même requête, un seul groupe change :
         *     Accept + UA + Referer + Origin (lecteurvideo)        → 200
         *     … + Sec-Fetch-Site: same-origin                      → 200
         *     … + Sec-Fetch-Site: cross-site                       → 200
         *     … + X-Embed-Parent/Origin/Referer                    → 403
         *     jeu complet de l'app                                 → 403
         *
         *   Filemoon lit `X-Embed-Origin` AVANT le Referer pour décider du site
         *   intégrateur. On y déclarait `https://filemoon.sx`, absent de sa liste blanche :
         *   le référent autorisé ne servait à rien, on se dénonçait dans l'en-tête d'à côté.
         *   La tentative de secours part donc avec le strict minimum, et AUCUN `X-Embed-*`.
         */
        fun entetesAvecParent(parent: String): MutableMap<String, String> = mutableMapOf(
            "User-Agent" to Extractor.DEFAULT_USER_AGENT,
            "Accept" to "application/json",
            "Referer" to parent,
            "Origin" to parent.trimEnd('/'),
            // ⚠ Les `X-Embed-*` doivent DIRE LA MÊME CHOSE que le référent. Mesuré :
            //     Referer lecteurvideo + X-Embed-Origin filemoon.sx   → 403
            //     Referer lecteurvideo + X-Embed-Origin lecteurvideo  → 200
            //   Les retirer marche pour `details`, mais `playback` les EXIGE (405 sans).
            //   On les garde donc, alignés sur l'intégrateur autorisé.
            "X-Embed-Parent" to parent,
            "X-Embed-Origin" to parent.trimEnd('/'),
            "X-Embed-Referer" to parent,
        )

        var parentDebloque: String? = null

        val details = try {
            try {
                service.getDetails(detailsUrl, detailsHeaders)
            } catch (e403: retrofit2.HttpException) {
                if (e403.code() != 403) throw e403
                var rescape: DetailsResponse? = null
                for (parent in parentsAutorises) {
                    rescape = try {
                        service.getDetails(detailsUrl, entetesAvecParent(parent)).also {
                            parentDebloque = parent
                            Log.i(TAG, "[Filemoon] 403 contourné via intégrateur autorisé « $parent »")
                        }
                    } catch (_: Exception) {
                        Log.w(TAG, "[Filemoon] intégrateur « $parent » refusé lui aussi")
                        null
                    }
                    if (rescape != null) break
                }
                rescape ?: throw e403
            }
        } catch (e: retrofit2.HttpException) {
            // 404 + "video not found" body = link rot; surface a clearer message
            // than a generic HTTP exception so the player can fall back fast.
            val body = try { e.response()?.errorBody()?.string() } catch (_: Throwable) { null }
            if (e.code() == 404 && body?.contains("video not found", ignoreCase = true) == true) {
                throw Exception("Filemoon: video expired (link rot) — $videoId")
            }
            // 2026-08-06 : le 428 sur `details` a été résolu (en-têtes de navigateur manquants,
            //   cf. `entetesNavigateur`). La sonde de diagnostic est retirée ; on garde une
            //   ligne unique, utile si le refus revenait un jour.
            if (e.code() == 428) Log.w(TAG, "[Filemoon] details refusé (428) : ${body?.take(120)}")
            throw e
        }
        val embedFrameUrl = details.embed_frame_url

        var playbackDomain = ""
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = Extractor.DEFAULT_USER_AGENT
        headers["Accept"] = "application/json"
        headers["Content-Type"] = "application/json"

        if (embedFrameUrl == null) {
            // Byse-frontend variant (e.g. weneverbeenfree.com, lukefirst.lol):
            // no embed_frame_url returned. Playback lives on the same domain as
            // the embed; the WAF requires Referer + Origin = parent provider URL,
            // plus the X-Embed-* trio (same set as for details).
            if (parentUrl == null) {
                throw Exception("embed_frame_url missing and no parent provider URL")
            }
            playbackDomain = currentDomain
            headers["Referer"] = parentUrl
            headers["Origin"] = parentOrigin!!
            headers["X-Embed-Parent"] = embedParent
            headers["X-Embed-Origin"] = embedOrigin
            headers["X-Embed-Referer"] = embedReferer
        } else if (linkType == "d") {
            playbackDomain = currentDomain
            headers["Referer"] = link
        } else {
            playbackDomain = Regex("""(https?://[^/]+)""").find(embedFrameUrl)?.groupValues?.get(1)
                ?: throw Exception("Could not extract domain from embed_frame_url")
            // ── 2026-08-25, 6e passe : LES DEUX APPELS NE VONT PAS AU MÊME SERVEUR ──────
            //   `details` part sur filemoon.sx — c'est LUI qui tient la liste blanche des
            //   intégrateurs, et se présenter comme lecteurvideo.com l'ouvre (mesuré).
            //   `playback`, lui, part sur le domaine rendu par `embed_frame_url`
            //   (ex. dismz4n3wp6xnr3.org) : c'est la page de lecture elle-même, et elle
            //   attend qu'on vienne de chez elle. Lui servir le déguisement « je viens de
            //   lecteurvideo » n'avait aucune raison de marcher — les trois variantes
            //   essayées ont toutes rendu 403 :
            //     Referer lecteurvideo, sans X-Embed                     → 403
            //     Referer lecteurvideo, X-Embed alignés lecteurvideo     → 403
            //     … + jeton de preuve de travail valide                  → 403
            //   Ce second appel garde donc ses en-têtes d'origine. Le déguisement ne sert
            //   qu'à `details`, le seul qui contrôle la liste blanche.
            headers["Referer"] = embedFrameUrl
            // Required by current API (April 2026) — server returns 405 without these.
            // Reverse-engineered from the official SPA Vite bundle (Lt + Nt helpers in
            // videoPagesBundle-CQv1AfZY.js). The X-Embed-* trio identifies the parent
            // frame so the WAF lets the request through.
            headers["X-Embed-Parent"] = link
            headers["X-Embed-Origin"] = playbackDomain
            headers["X-Embed-Referer"] = link
        }

        // POST /api/videos/{id}/embed/playback avec une empreinte d'appareil.
        // ── 2026-08-06 : LES VALEURS BIDON NE PASSENT PLUS ───────────────────────────────
        //   Le commentaire d'avril disait « le serveur ne vérifie pas les valeurs, des
        //   remplisseurs suffisent ». Ce n'est PLUS vrai : il répond désormais
        //   `428 {"error":"captcha_required"}`. Vérifié dans le navigateur du user, qui
        //   reçoit EXACTEMENT le même refus si on lui fait envoyer une empreinte inventée —
        //   le lien, le domaine et le réseau étaient donc hors de cause.
        //   `ByseAcces` négocie une vraie identité (signature ECDSA P-256) puis franchit la
        //   « vérification joueur », qui n'est pas une image mais une preuve de travail.
        //   Le jeton obtenu voyage dans l'en-tête `X-Captcha-Token`.
        //   Si la négociation échoue, on garde l'ancien corps : le repli WebView prendra le
        //   relais comme avant, on ne perd rien.
        // ── 2026-08-25, 4e passe : LE BUDGET ÉTAIT PLUS COURT QUE LA PREUVE ─────────────
        //   Une fois l'intégrateur autorisé accepté, `playback` ne rendait plus 403 mais
        //   428 « captcha_required ». Le journal montre pourquoi — la preuve de travail
        //   ABOUTIT, mais après qu'on a renoncé :
        //     20:43:26  ByseAcces: négociation en cours, on rend la main   (budget 8 s)
        //     20:43:26  playback en attente d'accès : captcha_required     (428)
        //     20:43:30  ByseAcces: preuve résolue en 10817 ms (difficulté 20)
        //   Quatre secondes de trop. On aligne donc le budget sur la difficulté réellement
        //   mesurée sur l'appareil. La négociation continuant de toute façon en tâche de
        //   fond, ce budget n'est qu'un « combien j'attends au premier passage » : une
        //   seconde tentative trouve le jeton en cache.
        val acces = ByseAcces.obtenir(
            playbackDomain,
            videoId,
            headers.filterKeys { it.startsWith("X-Embed-") },
            attenteMaxMs = 14_000L,
        )
        val corpsEmpreinte = if (acces != null) {
            headers["X-Captcha-Token"] = acces.jetonCaptcha
            FingerprintBody(
                Fingerprint(
                    token = acces.token,
                    viewer_id = acces.viewerId,
                    device_id = acces.deviceId,
                    confidence = acces.confiance,
                )
            )
        } else {
            FingerprintBody()
        }

        val playbackUrl = "$playbackDomain/api/videos/$videoId/embed/playback"

        /**
         * ── 2026-08-25, 5e passe : ON N'ESSAIE PLUS DE DEVINER LA DURÉE ─────────────────
         *
         *   Mesures successives de la preuve de travail sur le MÊME appareil et la MÊME
         *   vidéo : 3,9 s — 10,8 s — 13,9 s. C'est une recherche par force brute, sa durée
         *   est aléatoire par nature. Tout budget fixe finit donc par être battu : celui de
         *   8 s l'a été de 4 s, celui de 14 s l'a été d'UNE seconde (« preuve résolue en
         *   13931 ms », juste après le renoncement).
         *
         *   Plutôt que de rallonger indéfiniment l'attente du premier appel — ce qui ferait
         *   patienter tout le monde, même quand la preuve tombe en 4 s — on laisse partir
         *   l'appel, et SI le serveur répond 428 (« il me faut la preuve »), on attend le
         *   jeton et on rejoue. Le calcul tourne déjà en tâche de fond : on ne recommence
         *   rien, on récupère juste son résultat.
         */
        suspend fun rejouerAvecJeton(): PlaybackResponse? {
            val acces2 = ByseAcces.obtenir(
                playbackDomain,
                videoId,
                headers.filterKeys { it.startsWith("X-Embed-") },
                attenteMaxMs = 20_000L,
            ) ?: return null
            headers["X-Captcha-Token"] = acces2.jetonCaptcha
            val corps2 = FingerprintBody(
                Fingerprint(
                    token = acces2.token,
                    viewer_id = acces2.viewerId,
                    device_id = acces2.deviceId,
                    confidence = acces2.confiance,
                )
            )
            Log.i(TAG, "[Filemoon] jeton obtenu après le 428 — on rejoue playback")
            return service.getPlayback(playbackUrl, headers, corps2)
        }

        val playbackResponse = try {
            service.getPlayback(playbackUrl, headers, corpsEmpreinte)
        } catch (e: retrofit2.HttpException) {
            val body = try { e.response()?.errorBody()?.string() } catch (_: Throwable) { null }
            if (e.code() == 404 && body?.contains("video not found", ignoreCase = true) == true) {
                throw Exception("Filemoon: video expired (link rot) — $videoId")
            }
            if (e.code() != 428) throw e
            Log.w(TAG, "[Filemoon] playback 428 (${body?.take(60)}) — on attend la preuve puis on rejoue")
            rejouerAvecJeton() ?: throw e
        }
        val playbackData = playbackResponse.playback
            ?: throw Exception("No playback data")


        val decryptedJson = decryptPlayback(playbackData)
        
        val jsonObject = JSONObject(decryptedJson)
        val sources = jsonObject.optJSONArray("sources")
            ?: throw Exception("No sources found in decrypted data")
            
        if (sources.length() == 0) throw Exception("Empty sources list")
        
        val sourceUrl = sources.getJSONObject(0).getString("url")

        Log.i("StreamFlixES", "[Filemoon] -> Source found: $sourceUrl")

        val videoHeaders = if (embedFrameUrl == null) {
            // Byse variant: hot-link the source with parent-provider headers
            mutableMapOf(
                "Referer" to (parentUrl ?: currentDomain),
                "User-Agent" to Extractor.DEFAULT_USER_AGENT,
                "Origin" to (parentOrigin ?: currentDomain)
            )
        } else {
            mutableMapOf(
                "Referer" to embedFrameUrl,
                "User-Agent" to Extractor.DEFAULT_USER_AGENT,
                "Origin" to playbackDomain
            )
        }
        return Video(
            source = sourceUrl,
            headers = videoHeaders
        )
    }

    /**
     * Headless WebView fallback. Loads `/e/{videoId}` and intercepts the first
     * `*.m3u8` (or `master.m3u8` / `index.m3u8`) request the SPA fires once it has
     * decrypted the playback response.
     *
     * 2026-07-16: Filemoon added a "human verification" step. The embed page now
     * loads a cross-origin iframe (q8y5z.com) that shows a centered play button
     * with "Cliquez sur le bouton de lecture pour vérifier que vous êtes humain".
     * Only after clicking the button does the iframe swap to the actual JW player
     * that fires the m3u8 request.
     *
     * Strategy: intercept the cross-origin iframe HTTP response in
     * shouldInterceptRequest, inject JS that auto-clicks the verification button,
     * and return the modified HTML. The injected JS runs in the iframe's own
     * context (same-origin with the served response), so it CAN access the
     * iframe's DOM — unlike dispatchTouchEvent which can't reach into a
     * cross-origin iframe from a headless (non-windowed) WebView.
     *
     * Timeout: 25s to accommodate verification + player load delay.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractViaWebView(link: String): Video = withContext(Dispatchers.Main) {
        val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1)
            ?: throw Exception("Could not extract base URL")
        // ── 2026-08-06 : NE PLUS INVENTER UN PARENT — LE 428 VENAIT DE LÀ ─────────────────
        //   Preuve obtenue en ouvrant le MÊME lien dans le navigateur, une fois le DNS du PC
        //   dé-filtré :
        //     · navigateur → `/api/videos/<id>/embed/details` répond **HTTP 200** avec le JSON
        //       complet (« House Of The Dragon S02E01 … ») ;
        //     · l'app       → **HTTP 428** sur TOUS les domaines.
        //   Le lien, le domaine et le réseau sont donc hors de cause. Seule différence : le
        //   RÉFÉRENT. Le navigateur interroge l'API depuis la page elle-même, en MÊME ORIGINE.
        //   Nous envoyions une origine ÉTRANGÈRE, prise faute de mieux sur
        //   `currentProvider.baseUrl` — soit `api.movix.fun`, une API qui n'embarque rien.
        //   Le pare-feu refusait, à juste titre.
        //   ⚠ On ne garde donc QUE les parents explicitement connus (table `byseParentUrl`,
        //     établie pour VoirAnime dont le WAF les EXIGE). Sinon on reste en même origine,
        //     comme le navigateur — c'est ce que fait déjà la branche `else` plus bas.
        //   ⚠⚠ NE PAS restaurer le repli `currentProvider.baseUrl` : l'adresse du provider
        //     courant n'a aucune raison d'être une page qui intègre ce lecteur.
        val parentUrl = byseParentUrl(currentDomain)
        val parentOrigin = parentUrl?.trimEnd('/')

        // 2026-08-25 : 30 s → 15 s. Quand la page ne démarre pas, l'attente est stérile et
        //   c'est elle que l'utilisateur ressent (« ça ne part pas ») alors que l'échec est
        //   déjà joué. 15 s = la valeur du WebViewResolver de référence, et le lecteur peut
        //   basculer sur un autre hébergeur bien plus tôt.
        val result = withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine<Video> { cont ->
                val context = StreamFlixApp.instance.applicationContext
                var resolved = false
                lateinit var webView: WebView
                var attachedRoot: android.view.ViewGroup? = null

                fun cleanupAndResume(video: Video?) {
                    if (resolved) return
                    resolved = true
                    Handler(Looper.getMainLooper()).post {
                        try {
                            try { attachedRoot?.removeView(webView) } catch (_: Throwable) {}
                            webView.stopLoading()
                            webView.loadUrl("about:blank")
                            webView.destroy()
                        } catch (_: Throwable) { /* ignore */ }
                    }
                    if (video != null) {
                        if (cont.isActive) cont.resume(video)
                    } else {
                        if (cont.isActive) cont.resume(
                            Video(source = "", headers = emptyMap())
                        )
                    }
                }

                /**
                 * Intercept a verification iframe request: fetch the HTML ourselves,
                 * inject the auto-click script, and return the modified response.
                 * Runs on WebView IO thread (NOT main thread), safe for network I/O.
                 */
                fun interceptVerificationIframe(url: String): WebResourceResponse? {
                    return try {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.setRequestProperty("User-Agent", ANDROID_CHROME_UA)
                        conn.setRequestProperty("Referer", link)
                        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                        // Forward cookies from CookieManager
                        val cookies = android.webkit.CookieManager.getInstance().getCookie(url)
                        if (!cookies.isNullOrBlank()) conn.setRequestProperty("Cookie", cookies)
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.instanceFollowRedirects = true

                        val responseCode = conn.responseCode
                        // Store any Set-Cookie headers back into CookieManager
                        conn.headerFields?.get("Set-Cookie")?.forEach { cookie ->
                            android.webkit.CookieManager.getInstance().setCookie(url, cookie)
                        }

                        val html = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()

                        // Inject auto-click script
                        val modified = if (html.contains("</body>", ignoreCase = true)) {
                            html.replaceFirst("</body>", "$AUTO_CLICK_SCRIPT\n</body>", ignoreCase = true)
                        } else if (html.contains("</html>", ignoreCase = true)) {
                            html.replaceFirst("</html>", "$AUTO_CLICK_SCRIPT\n</html>", ignoreCase = true)
                        } else {
                            html + "\n" + AUTO_CLICK_SCRIPT
                        }

                        Log.d(TAG, "[Filemoon-WV] Injected auto-click into verification iframe ($responseCode, ${modified.length} chars)")

                        // Build response headers — pass through CORS-relevant ones
                        val responseHeaders = mutableMapOf<String, String>()
                        responseHeaders["Access-Control-Allow-Origin"] = "*"
                        responseHeaders["Cache-Control"] = "no-cache"
                        conn.headerFields?.forEach { (key, values) ->
                            if (key != null && values.isNotEmpty() &&
                                key.lowercase() !in setOf("set-cookie", "transfer-encoding", "content-length", "content-encoding")
                            ) {
                                responseHeaders[key] = values.last()
                            }
                        }

                        WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            responseCode,
                            if (responseCode in 200..299) "OK" else "Error",
                            responseHeaders,
                            modified.byteInputStream(Charsets.UTF_8)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "[Filemoon-WV] Failed to intercept verification iframe: ${e.message}")
                        null // Let WebView handle it normally
                    }
                }

                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = ANDROID_CHROME_UA
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                }

                // 2026-07-27 : ATTACHER la WebView à la fenêtre (comme OnRegardeOu/upbolt). Sans
                //   fenêtre réelle, la WebView ne raster pas et ne traite pas les vrais gestes → la
                //   vérif humaine q8y5z (qui exige désormais un geste de confiance) ne passe plus et
                //   l'auto-clic injecté reste sans effet → timeout 25s. Attachée invisible (alpha 0.02,
                //   index 0, derrière l'UI), le player s'initialise et le m3u8 sort. Fallback = ancien
                //   measure/layout si aucune activité courante.
                try {
                    val act = StreamFlixApp.currentActivity
                    val root = act?.findViewById<android.view.ViewGroup>(android.R.id.content)
                    if (root != null) {
                        // ⚠⚠⚠ 2026-08-06 — INVISIBLE PAR LA TAILLE, JAMAIS PAR L'OPACITÉ.
                        //   Cette vue était à `alpha = 0.004f` (passé de 0.02 à 0.004 le 2 août
                        //   pour masquer un voile gris). Or SOUS CE SEUIL, CHROMIUM SUSPEND LE
                        //   RENDU : la page ne se dessine plus, le lecteur ne s'initialise
                        //   jamais, et AUCUN clic — injecté ou réel — ne peut aboutir. D'où les
                        //   30 s de silence puis l'abandon, alors que le défi ne demande qu'un
                        //   simple clic (constat user : « il y a juste à cliquer »).
                        //   Le diagnostic est le même que celui établi ce matin sur
                        //   `OnlyFlixResolver` (EmbedSeek/SeekStreaming), où il avait cassé deux
                        //   extracteurs pendant deux jours.
                        //   Solution éprouvée : on sépare le GABARIT du DESSIN. Les
                        //   LayoutParams restent MATCH_PARENT — la page garde un vrai viewport
                        //   et s'initialise — et seule la transformation de dessin est réduite
                        //   au centième, avec une opacité PLEINE. Chromium considère la vue
                        //   comme visible et continue de rendre ; à l'écran il ne reste qu'une
                        //   dizaine de pixels dans un coin, derrière l'interface.
                        //   ⚠ Ne PAS remplacer par des LayoutParams minuscules : le viewport
                        //     deviendrait réellement minuscule et le lecteur refuserait de
                        //     s'initialiser. C'est l'échelle de DESSIN qu'il faut réduire.
                        webView.alpha = 1f
                        webView.pivotX = 0f
                        webView.pivotY = 0f
                        webView.scaleX = 0.01f
                        webView.scaleY = 0.01f
                        webView.isFocusable = false
                        webView.isFocusableInTouchMode = false
                        try { webView.settings.offscreenPreRaster = true } catch (_: Throwable) {}
                        root.addView(webView, 0, android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                        attachedRoot = root
                    } else {
                        val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
                        val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                        webView.measure(wSpec, hSpec); webView.layout(0, 0, 1080, 1920)
                    }
                } catch (_: Throwable) {
                    try {
                        val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
                        val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                        webView.measure(wSpec, hSpec); webView.layout(0, 0, 1080, 1920)
                    } catch (_: Throwable) {}
                }
                try { webView.onResume(); webView.resumeTimers() } catch (_: Throwable) {}

                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null

                        // 2026-08-06 : la journalisation intégrale des requêtes (posée pour
                        //   diagnostiquer le silence de 30 s) a été RETIRÉE — elle avait donné
                        //   sa réponse : `erreur -2` = ERROR_HOST_LOOKUP, d'où le relais DoH
                        //   décrit juste en dessous. Elle noyait le journal (des centaines de
                        //   lignes par lecture, jusqu'aux images de pages publicitaires).
                        //   Ne la remettre que le temps d'un diagnostic, jamais durablement.

                        // ══ 2026-08-06 : LA WEBVIEW N'ARRIVAIT PAS À RÉSOUDRE LE DOMAINE ══
                        //   Diagnostic obtenu par la trace complète ci-dessus :
                        //     [Filemoon-WV] REQ    https://bysebuho.com/e/…
                        //     [Filemoon-WV] erreur -2  sur https://bysebuho.com/e/…
                        //   Le code -2 est `ERROR_HOST_LOOKUP` : la page ne se charge JAMAIS.
                        //   D'où les 30 s de silence, l'absence de toute requête derrière, et
                        //   l'échec du défi — qui n'a jamais eu l'occasion de s'afficher.
                        //   Cause : le fournisseur d'accès bloque Filemoon au niveau DNS. Nos
                        //   appels d'API passent, eux, car ils utilisent `Extractor.sharedClient`
                        //   qui résout en DNS-over-HTTPS ; mais une WebView utilise TOUJOURS le
                        //   résolveur SYSTÈME, qu'on ne peut pas changer. Même mur que celui
                        //   rencontré le matin même sur `edge1-waw-sprintcdn.r66nv9ed.com`.
                        //   Correctif : on sert nous-mêmes les requêtes vers les domaines
                        //   concernés, via OkHttp + DoH, et on rend la réponse à la WebView.
                        //   Elle n'a alors plus rien à résoudre. L'extraction reste NORMALE :
                        //   aucune page n'est montrée à l'utilisateur.
                        //   ⚠ On ne détourne QUE le document et les scripts. Les flux (.m3u8,
                        //     segments) sont captés plus bas et n'ont pas à transiter par ici.
                        if (!resolved && !url.contains(".m3u8") && estDomaineFilemoon(url)) {
                            servirViaDoh(url, request)?.let { return it }
                        }

                        // 1. Intercept m3u8 requests → we have the video
                        if (url.contains(".m3u8") && !resolved) {
                            Log.i(TAG, "[Filemoon-WV] m3u8 captured: $url")
                            val videoHeaders = mutableMapOf(
                                "Referer" to link,
                                "Origin" to currentDomain,
                                "User-Agent" to ANDROID_CHROME_UA
                            )
                            cleanupAndResume(Video(source = url, headers = videoHeaders))
                        }

                        // 2. Intercept verification iframe → inject auto-click
                        val host = request?.url?.host ?: return null
                        if (!resolved && VERIFICATION_DOMAINS.any { host.contains(it, ignoreCase = true) }) {
                            // Only intercept document requests (HTML), not sub-resources
                            val accept = request.requestHeaders?.get("Accept") ?: ""
                            if (accept.contains("text/html") || accept.isEmpty()) {
                                return interceptVerificationIframe(url)
                            }
                        }

                        return null
                    }

                    // Traces temporaires du même diagnostic : sait-on seulement si la page
                    //   aboutit, si elle est redirigée, ou si elle échoue en silence ?
                    override fun onPageFinished(view: WebView?, u: String?) {
                        Log.d(TAG, "[Filemoon-WV] page chargée : ${u?.take(120)}")
                    }

                    override fun onReceivedError(
                        view: WebView?, request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        Log.w(TAG, "[Filemoon-WV] erreur ${error?.errorCode} sur ${request?.url?.toString()?.take(110)}")
                    }

                    override fun onRenderProcessGone(
                        view: WebView?, detail: android.webkit.RenderProcessGoneDetail?
                    ): Boolean {
                        // ⚠ DOIT renvoyer true, sinon Android tue toute l'application quand le
                        //   moteur de rendu disparaît (mémoire, trop de WebView simultanées).
                        Log.w(TAG, "[Filemoon-WV] moteur de rendu supprimé (crash=${detail?.didCrash()})")
                        cleanupAndResume(null)
                        return true
                    }
                }

                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            try { attachedRoot?.removeView(webView) } catch (_: Throwable) {}
                            webView.stopLoading()
                            webView.destroy()
                        } catch (_: Throwable) { /* ignore */ }
                    }
                }

                val loadHeaders = mutableMapOf<String, String>()
                if (parentUrl != null) {
                    loadHeaders["Referer"] = parentUrl
                    loadHeaders["Origin"] = parentOrigin!!
                } else if (PARENTS_AUTORISES.isNotEmpty()) {
                    // ── 2026-08-25, 7e passe : LA WEBVIEW AUSSI DOIT SE PRÉSENTER ────────────
                    //   Ouvert dans Chrome, `filemoon.sx/e/<id>` affiche « Intégration
                    //   bloquée sur ce site — le propriétaire n'autorise que des domaines
                    //   autorisés ». Notre WebView chargeait la page en disant « je viens de
                    //   filemoon » : elle tombait donc sur CE message, et n'émettait jamais
                    //   de flux — d'où les expirations à répétition, sans la moindre erreur.
                    //   Même principe que pour l'appel API, qui lui est déjà réglé : on se
                    //   présente comme l'intégrateur autorisé.
                    loadHeaders["Referer"] = PARENTS_AUTORISES.first()
                } else {
                    // ── 2026-08-25 (user : « Filemoon part direct chez eux, pas chez nous ») ──
                    //   Le repli WebView partait SANS AUCUN Referer :
                    //     [Filemoon-WV] loading https://filemoon.sx/e/… (referer=null)
                    //     [Filemoon] WebView fallback also failed: timed out (30s)
                    //   Or filemoon.sx ne sert plus de page lecteur : c'est une SPA
                    //   (« <title>Byse Frontend</title> », 1605 octets, ni iframe ni script
                    //   packé — vérifié en direct), et son API `/embed/details` nous rend 403.
                    //   Sans référent, la SPA ne démarre jamais la lecture et on attend 30 s
                    //   pour rien.
                    //
                    //   L'extracteur de référence (FilemoonV2, CloudStream) envoie le lien
                    //   D'EMBED LUI-MÊME comme référent, plus les en-têtes Sec-Fetch d'une
                    //   iframe. C'est cohérent avec la leçon du 06/08 : on reste en MÊME
                    //   ORIGINE, comme le navigateur — on n'invente aucun parent étranger,
                    //   ce qui était la cause du 428.
                    loadHeaders["Referer"] = link
                }
                // Un lecteur intégré est toujours chargé dans une iframe : on le dit.
                loadHeaders["Sec-Fetch-Dest"] = "iframe"
                loadHeaders["Sec-Fetch-Mode"] = "navigate"
                loadHeaders["Sec-Fetch-Site"] = "cross-site"

                Log.d(TAG, "[Filemoon-WV] loading $link (referer=${loadHeaders["Referer"]})")
                webView.loadUrl(link, loadHeaders)

                // Vrai geste tactile au centre (là où est le bouton de vérif q8y5z, iframe centrée) :
                //   un MotionEvent réel TRAVERSE l'iframe cross-origin (contrairement à un .click() JS)
                //   → passe la vérif "humaine". Répété le temps que l'iframe charge.
                // La vérif q8y5z exige PLUSIEURS clics (5-6, confirmé user sur le web) → on tape en
                //   RAFALE toutes les 1,5s (~14 fois) jusqu'à ce que le m3u8 soit capté. Le vrai
                //   MotionEvent traverse l'iframe cross-origin, contrairement au .click() JS.
                val kickHandler = Handler(Looper.getMainLooper())
                var kickCount = 0
                val kick = object : Runnable {
                    override fun run() {
                        if (resolved) return
                        kickCount++
                        try {
                            val w = if (webView.width > 0) webView.width else 1080
                            val h = if (webView.height > 0) webView.height else 1920
                            val x = w / 2f; val y = h / 2f
                            val t = android.os.SystemClock.uptimeMillis()
                            val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                            val up = android.view.MotionEvent.obtain(t, t + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
                            webView.dispatchTouchEvent(down); webView.dispatchTouchEvent(up)
                            down.recycle(); up.recycle()
                        } catch (_: Throwable) {}
                        if (!resolved && kickCount < 14) kickHandler.postDelayed(this, 1500)
                    }
                }
                kickHandler.postDelayed(kick, 2000)
            }
        } ?: throw Exception("Filemoon WebView fallback timed out (30s)")

        if (result.source.isBlank()) {
            throw Exception("Filemoon WebView fallback resolved empty source")
        }
        result
    }

    /**
     * 2026-07-16 : les hôtes « Byse frontend » gn1r5n.org / weneverbeenfree.com sont servis par
     *   VoirAnime (ex. LECTEUR MOON). Quand ils arrivent en BACKUP (l'user est sur un AUTRE
     *   provider — ex. AnimeSama), le parent/référer par défaut (currentProvider = anime-sama.to)
     *   est REFUSÉ par le WAF Filemoon → l'extraction échoue (serveur mort dans l'app alors que le
     *   flux marche dans le navigateur). On force donc le référer sur le vrai site embarquant.
     */
    /**
     * En-têtes que Chrome joint AUTOMATIQUEMENT à toute requête et qu'OkHttp, lui, n'envoie
     * jamais. Ils manquaient : c'est la seule différence restante entre l'appel du navigateur
     * (HTTP 200) et le nôtre (HTTP 428).
     *
     * ── 2026-08-06, établi par comparaison directe dans le Chrome du user ────────────────
     *   J'ai rejoué `/api/videos/<id>/embed/details` depuis la page elle-même :
     *     · sans aucun en-tête ..................... 200
     *     · avec Accept, X-Embed-Parent/Origin/Referer  200
     *     · sans cookies ........................... 200
     *   Donc ni le domaine (bysebuho ET filemoon.sx répondent 200), ni nos en-têtes maison,
     *   ni les cookies ne sont en cause — contrairement à VOE où c'était bien un miroir mort.
     *   Reste ce que seul un vrai navigateur ajoute : les indices client `sec-ch-ua` et les
     *   `sec-fetch-*`. Notre User-Agent annonce Chrome 131 sans jamais les accompagner —
     *   incohérence classique que les pare-feu sanctionnent par un 428 « condition requise ».
     *   Indice concordant dans le journal : la WebView de l'app (vrai navigateur) chargeait
     *   la page sans souci ; seul l'appel OkHttp était refusé.
     *   ⚠ Garder ces valeurs COHÉRENTES avec `Extractor.DEFAULT_USER_AGENT` : si un jour on
     *     change la version de Chrome annoncée, il faut changer `sec-ch-ua` en même temps.
     */
    private fun entetesNavigateur(): Map<String, String> = mapOf(
        "Accept-Language" to "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
        "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
    )

    private fun byseParentUrl(currentDomain: String): String? = when {
        currentDomain.contains("gn1r5n", ignoreCase = true) ||
            currentDomain.contains("weneverbeenfree", ignoreCase = true) -> "https://voir-anime.to"
        // ── 2026-08-06 : LE RÉFÉRENT ENVOYÉ ÉTAIT UNE **API**, PAS UN SITE ────────────────
        //   User : « pourquoi il n'a pas réussi la lecture et met autant de temps ».
        //   Le journal montrait `referer=https://api.movix.fun/` et une réponse **HTTP 428**
        //   sur TOUS les domaines — bysebuho comme filemoon.sx. Ce n'est ni un domaine mort
        //   (vérifié : les six domaines de la liste répondent en DNS) ni une panne réseau :
        //   c'est le pare-feu de Filemoon qui refuse le référent.
        //   Or `api.movix.fun` n'est pas une page qui intègre le lecteur, c'est l'API du
        //   provider — le repli `currentProvider.baseUrl` l'avait prise faute de mieux, ce
        //   domaine ne figurant pas dans cette table. On lui donne donc le vrai site.
        //   ⚠ C'est exactement le cas décrit dans le commentaire ci-dessus : « le référer par
        //     défaut est REFUSÉ par le WAF Filemoon → l'extraction échoue alors que le flux
        //     marche dans le navigateur ». La table était simplement incomplète.
        //   ⚠ Essai ANNULÉ le même jour : j'avais mappé bysebuho/bysezoxexe/bysejikuar sur
        //     `https://movix.show`. Ça ne corrigeait rien — le 428 persistait — parce que le
        //     problème n'est pas QUEL référent étranger on envoie, mais le fait d'en envoyer
        //     un. Ces domaines doivent rester en MÊME ORIGINE, comme le navigateur.
        else -> null
    }

    private fun decryptPlayback(data: PlaybackData): String {
        val iv = Base64.decode(data.iv, Base64.URL_SAFE)
        val payload = Base64.decode(data.payload, Base64.URL_SAFE)

        // ── 2026-08-06 : LES DEUX PREMIERS MORCEAUX NE SONT PLUS LES BONS ────────────────
        //   Erreur observée : « Unsupported key size: 48 bytes ». Le serveur envoie désormais
        //   PLUSIEURS morceaux de clé accompagnés d'un champ `version` qui désigne lesquels
        //   retenir — les positions `version` et `31 − version` (numérotées à partir de 1).
        //   Les autres sont des leurres. Lu dans le bundle du site (fonctions `Ea`/`Qa`/`ws`),
        //   pas deviné. Si `version` manque ou sort de la plage 1-20, le site retombe sur
        //   « tous les morceaux » : on fait pareil, ce qui reproduit l'ancien comportement
        //   quand il n'y en avait que deux.
        val morceaux = data.key_parts
        val v = data.version?.trim()?.toIntOrNull()
        val retenus = if (v != null && v in 1..20 && v <= morceaux.size && (31 - v) in 1..morceaux.size) {
            listOf(morceaux[v - 1], morceaux[31 - v - 1])
        } else {
            morceaux
        }

        val decodes = retenus.map { Base64.decode(it, Base64.URL_SAFE) }
        val key = ByteArray(decodes.sumOf { it.size })
        var pos = 0
        decodes.forEach { System.arraycopy(it, 0, key, pos, it.size); pos += it.size }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(key, "AES")
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        
        val decryptedBytes = cipher.doFinal(payload)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    class Any(hostUrl: String) : FilemoonExtractor() {
        override val mainUrl = hostUrl
    }

    private interface Service {
        @GET
        suspend fun getDetails(
            @Url url: String,
            @HeaderMap headers: Map<String, String>,
        ): DetailsResponse

        @POST
        suspend fun getPlayback(
            @Url url: String,
            @HeaderMap headers: Map<String, String>,
            @Body body: FingerprintBody,
        ): PlaybackResponse
    }

    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(
        val iv: String,
        val payload: String,
        val key_parts: List<String>,
        /** Indique QUELS morceaux de clé retenir — cf. `decryptPlayback`. */
        val version: String? = null,
    )

    /**
     * Body required by the embed/playback endpoint as of April 2026. The server validates
     * the JSON shape (a `fingerprint` object with token/viewer_id/device_id/confidence
     * fields) but doesn't currently check the actual values — placeholders work.
     */
    data class FingerprintBody(
        val fingerprint: Fingerprint = Fingerprint()
    )

    data class Fingerprint(
        val token: String = "x",
        val viewer_id: String = "y",
        val device_id: String = "z",
        val confidence: Double = 0.5,
    )

}
