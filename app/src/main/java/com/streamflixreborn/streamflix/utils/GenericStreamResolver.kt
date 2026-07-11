package com.streamflixreborn.streamflix.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Résolveur générique de flux IPTV — modèle Wiseplay.
 *
 * Prend une URL + headers fournis par la playlist communautaire (3boxTv,
 * Dric4rTV, etc.) et la transforme en URL m3u8/dash jouable directement,
 * sans aucune logique métier par chaîne.
 *
 * Patterns supportés (universels, observés dans les pipelines communautaires) :
 *
 *  - Redirect HTTP 301/302/303 : on suit (max 10 hops).
 *  - Proxy `?pmpurl=<base64>` (PHP Mini Proxy utilisé par scailhol.free.fr,
 *    rebrand.ly/u3 et compagnie) : on décode le base64, on strip les guillemets
 *    éventuels, on remplace l'URL et on continue.
 *  - Proxy `?u=<base64>` ou `?url=<base64>` : même traitement.
 *  - Décodage des `\/` en `/` dans l'URL (= JSON-escaped paths).
 *
 *  Quand la playlist change ses URLs (= le mainteneur communautaire met à jour
 *  ses Google Sheets / son JSON), notre code n'a RIEN à faire — on suit
 *  bêtement ce que la playlist dit.
 *
 *  Limites :
 *  - Si la chaîne de redirects dépasse 10 hops → on arrête et on retourne ce
 *    qu'on a (probablement OK car les CDN finissent en ≤5 hops).
 *  - Si tous les patterns sont inconnus → on retourne l'URL telle quelle
 *    (laisser le player essayer).
 */
object GenericStreamResolver {

    private const val TAG = "GenericResolver"
    private const val MAX_HOPS = 10

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(false) // on suit manuellement pour intercepter pmpurl
            .followSslRedirects(false)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class Resolved(
        val url: String,
        val headers: Map<String, String>,
    )

    /**
     * Résout l'URL d'une chaîne en suivant les redirects/proxies.
     *
     * @param startUrl URL de départ (= ce que la playlist fournit comme stream URL)
     * @param baseHeaders Headers fournis par la playlist (User-Agent, Referer, Origin, etc.)
     * @return Resolved(url=URL finale jouable, headers=mêmes headers)
     */
    fun resolve(startUrl: String, baseHeaders: Map<String, String> = emptyMap()): Resolved {
        var url = decodeJsonSlashes(startUrl)
        val headers = baseHeaders.toMutableMap()
        if ("User-Agent" !in headers) {
            headers["User-Agent"] =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }

        for (hop in 1..MAX_HOPS) {
            // 1. Cas universel proxy pmpurl AVANT même de faire le HEAD :
            //    une URL en `?pmpurl=base64` est décodable localement.
            val proxied = tryDecodeBase64Proxy(url)
            if (proxied != null && proxied != url) {
                Log.d(TAG, "[hop $hop] proxy decoded: $url -> $proxied")
                url = decodeJsonSlashes(proxied)
                continue
            }

            // 2. Si on a déjà une URL qui ressemble à un manifest playable,
            //    on s'arrête. Évite un HEAD inutile.
            if (looksLikeMediaUrl(url)) {
                Log.d(TAG, "[hop $hop] media URL detected, stop: $url")
                return Resolved(url, headers)
            }

            // 3. HEAD pour découvrir où ça redirige.
            //    Si on tombe sur du contenu (RSS feed, HTML avec <script>fetch>),
            //    on bascule en GET et on parse via les patterns universels.
            val (location, contentBody) = try {
                val reqB = Request.Builder().url(url).head()
                headers.forEach { (k, v) -> reqB.header(k, v) }
                client.newCall(reqB.build()).execute().use { resp ->
                    when {
                        resp.code in 200..299 -> {
                            val ctype = resp.header("Content-Type")?.lowercase().orEmpty()
                            // Si c'est du RSS / XML / HTML / JSON, fetch le body en GET pour
                            //   parser les patterns universels (script fetch, m3u8 dans le HTML,
                            //   champ {"url":"...m3u8"} des réponses ftven/auth…).
                            //   Sinon c'est probablement un manifest = URL finale.
                            // 2026-06-27 : ajout "json" — les liens c9v3.s.gy de la 3boxTv v2
                            //   finissent souvent sur un endpoint d'auth (hdfauth.ftven.fr) qui
                            //   renvoie {"url":"<m3u8 signé>"} ; extractStreamFromBody y trouve
                            //   l'URL média interne.
                            if (ctype.contains("xml") || ctype.contains("html") || ctype.contains("text") || ctype.contains("json")) {
                                val getReq = Request.Builder().url(url).get()
                                headers.forEach { (k, v) -> getReq.header(k, v) }
                                val body = client.newCall(getReq.build()).execute().use { it.body?.string() }
                                Pair(null, body)
                            } else {
                                // URL finale (m3u8/mpd/binaire)
                                Log.d(TAG, "[hop $hop] 200 OK ($ctype), final: $url")
                                return Resolved(url, headers)
                            }
                        }
                        resp.code in 300..399 -> Pair(resp.header("Location"), null)
                        else -> Pair(null, null)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[hop $hop] HEAD failed (${t.message}), keep url as-is")
                Pair(null, null)
            }

            // 3b. Si on a un body texte, essayer d'extraire une URL stream via
            //     les patterns universels.
            if (!contentBody.isNullOrBlank()) {
                val extracted = extractStreamFromBody(contentBody, headers)
                if (extracted != null && extracted != url) {
                    Log.d(TAG, "[hop $hop] extracted from body: $extracted")
                    url = decodeJsonSlashes(extracted)
                    continue
                }
                // 3c. Fallback ULTIME (Wiseplay-style) : exécuter le JS du body
                //     dans un WebView headless. Le JS fera tout le travail
                //     (atob, redirects, decode, etc.) et on intercepte juste
                //     la requête média finale.
                //     ⚠ Synchronous bridge via runBlocking : c'est OK car
                //     GenericStreamResolver est déjà appelé en background
                //     coroutine depuis BoxXtemus.getVideo().
                try {
                    val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext
                    val mediaUrl = kotlinx.coroutines.runBlocking {
                        HeadlessJsResolver.resolve(
                            ctx = ctx,
                            html = contentBody,
                            baseUrl = url,
                            userAgent = headers["User-Agent"] ?: "",
                            timeoutMs = 8000,
                        )
                    }
                    if (mediaUrl != null) {
                        Log.d(TAG, "[hop $hop] WebView intercepted: $mediaUrl")
                        return Resolved(mediaUrl, headers)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "[hop $hop] WebView fallback failed: ${t.message}")
                }
                // Vraiment rien → on retourne l'URL courante.
                Log.d(TAG, "[hop $hop] body had no extractable stream, return as-is: $url")
                return Resolved(url, headers)
            }

            if (location.isNullOrBlank()) {
                // Plus rien à suivre, on retourne ce qu'on a.
                return Resolved(url, headers)
            }

            // 4. Resolve relative Location si besoin
            val absoluteLocation = if (location.startsWith("http", ignoreCase = true)) {
                location
            } else {
                // Relative à url courante
                try {
                    java.net.URI(url).resolve(location).toString()
                } catch (_: Throwable) {
                    location
                }
            }
            Log.d(TAG, "[hop $hop] redirect: $url -> $absoluteLocation")
            url = decodeJsonSlashes(absoluteLocation)
        }

        // Plafond MAX_HOPS atteint, retourne dernière URL connue.
        return Resolved(url, headers)
    }

    /**
     * Hôtes pour lesquels on sait qu'une page exécute du JavaScript pour
     * construire l'URL m3u8 finale via XHR/fetch dynamique. Pour ces hôtes,
     * le pipeline HTTP follow-redirect ne suffit jamais — il faut une WebView
     * headless qui exécute la page complètement et intercepte la requête.
     *
     * C'est ce que Wiseplay fait via son parser `HostParser.WEB` (= classe
     * vihosts.vp.a dans le DEX, reverse-engineered 2026-06-24).
     */
    private val WEBVIEW_REQUIRED_HOSTS = listOf(
        "c9v3.s.gy",         // Rakuten/Sony/etc. — JS construit l'URL CDN finale
        "scailhol.free.fr",  // PHP mini-proxy wrapping ftven/cloudfront
        "textup.fr",         // Pluto TV (quand c'est un stream, pas une sub-list)
        "hdfauth.ftven.fr",  // wrapper France TV avec ?url=<real_m3u8>
        "html.bet",          // FAST channels (Samsung TV+, Pluto TV) — JS redirect vers stream
    )

    /**
     * Résout l'URL [startUrl] en essayant d'abord la pipeline HTTP classique
     * (= follow-redirect + pmpurl + body extract), puis en basculant sur
     * `WebViewStreamResolver` (= WebView headless qui intercepte le 1er
     * .m3u8/.mpd sortant) si :
     *  - le hostname appartient à [WEBVIEW_REQUIRED_HOSTS], OU
     *  - la résolution HTTP n'a pas abouti à une URL playable.
     *
     * 2026-06-24 — calqué sur le pipeline Wiseplay (= parser WEB de vihosts).
     * À utiliser depuis WorldLiveTvProvider.getVideo() pour les patterns
     * c9v3.s.gy/me/..., c9v3.s.gy/aDqr3F/..., textup.fr/...?filetype=txt.
     *
     * Coût : 5-12s (WebView startup + JS execution + 1er XHR). Cohérent
     * avec "LCI très lent" observé sur Wiseplay par le user. Le résultat
     * devrait être caché par le caller (WorldLiveStreamCache 30 min).
     */
    fun resolveWithJsFallback(
        startUrl: String,
        baseHeaders: Map<String, String> = emptyMap(),
        webviewTimeoutMs: Long = 12_000L,
    ): Resolved {
        // 1. Pipeline HTTP classique (rapide, marche pour 95 % des cas)
        val httpResult = resolve(startUrl, baseHeaders)
        val needsWebView = !looksLikeMediaUrl(httpResult.url) ||
            shouldForceWebView(startUrl) ||
            shouldForceWebView(httpResult.url)
        if (!needsWebView) {
            return httpResult
        }

        Log.d(TAG, "fallback WebView for: $startUrl (httpResult=${httpResult.url})")
        return try {
            val wvResult = kotlinx.coroutines.runBlocking {
                WebViewStreamResolver.resolve(
                    entryUrl = startUrl,
                    referer = baseHeaders["Referer"],
                    userAgent = baseHeaders["User-Agent"]
                        ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                    timeoutMs = webviewTimeoutMs,
                    extraHeaders = baseHeaders.filterKeys { it !in setOf("Referer", "User-Agent") },
                )
            }
            if (wvResult != null) {
                Log.d(TAG, "WebView intercepted: ${wvResult.url}")
                Resolved(url = wvResult.url, headers = wvResult.headers)
            } else {
                Log.w(TAG, "WebView timed out, returning HTTP result as-is")
                httpResult
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WebView fallback crashed: ${t.message}")
            httpResult
        }
    }

    private fun shouldForceWebView(url: String): Boolean {
        val lower = url.lowercase()
        return WEBVIEW_REQUIRED_HOSTS.any { lower.contains(it) }
    }

    /**
     * Décode `\/` → `/` (JSON-escaped paths fréquents dans les playlists).
     */
    private fun decodeJsonSlashes(s: String): String = s.replace("\\/", "/")

    /**
     * Détecte les patterns proxy classiques (`?pmpurl=base64`, `?u=base64`,
     * `?url=base64`, `rebrand.ly/u3?q=base64`).
     * Retourne l'URL décodée, ou null si pas un proxy.
     */
    private fun tryDecodeBase64Proxy(url: String): String? {
        return try {
            val uri = android.net.Uri.parse(url)
            val candidateKeys = listOf("pmpurl", "u", "url", "q")
            for (key in candidateKeys) {
                val raw = uri.getQueryParameter(key) ?: continue
                if (raw.length < 8) continue
                val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
                val decoded = try {
                    String(android.util.Base64.decode(padded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
                } catch (_: Throwable) {
                    try {
                        String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
                    } catch (_: Throwable) {
                        continue
                    }
                }
                // Strip guillemets (= scailhol.free.fr embed l'URL entre `"..."`)
                val cleaned = decoded.trim().trim('"').trim('\'').trim()
                // Validate that decoded looks like an URL
                if (cleaned.startsWith("http://", ignoreCase = true) ||
                    cleaned.startsWith("https://", ignoreCase = true)) {
                    return cleaned
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extrait une URL stream depuis le body d'une page (RSS, HTML, etc.).
     * Patterns universels supportés :
     *  - Script JS `if (... "<token>") { fetch(("<url>")...)` :
     *    le token est extrait du User-Agent fourni via `~tpol<token>~`.
     *    C'est le pipeline rsseverything que Wiseplay utilise pour les
     *    chaînes FR — on s'y greffe sans hardcoding par chaîne.
     *  - URL `.m3u8`/`.mpd` brute dans le HTML.
     */
    private fun extractStreamFromBody(body: String, headers: Map<String, String>): String? {
        val ua = headers["User-Agent"] ?: ""

        // 2026-06-25 (user "3box TV : il sait pas lancer les chaînes alors que
        //   Wiseplay oui — ils ont ajouté un nouveau protocole") : nouveau
        //   format RSS direct = `<description><![CDATA[<meta name="str"
        //   content="<vraie_url_mpd>">]]></description>`. Plus de JS Nxt,
        //   plus de atob, plus de token tpol — juste l'URL en clair dans
        //   un meta tag. Wiseplay supporte ce format natif. On l'extrait
        //   en priorité car c'est le plus simple et le plus récent.
        if (body.contains("<meta") && body.contains("name=\"str\"")) {
            val metaPattern = Regex(
                """<meta\s+name=["']str["']\s+content=["'](https?://[^"']+)["']""",
                RegexOption.IGNORE_CASE,
            )
            val match = metaPattern.find(body)
            if (match != null) {
                val url = match.groupValues[1]
                if (!isPoisonedHost(url)) return url
            }
        }

        // 2026-06-25 (TMC "ça mouline rien") : pattern 2e hop TVRadioZap pour
        //   les chaînes hébergées sur s2.callofliberty.fr. Le RSS contient :
        //     fetch("https://s2.callofliberty.fr/streams/"
        //       + UA.split("~col"+ret)[1].split("~")[0].replace("-","/")
        //       + ".m3u8?callofliberty=TOKEN")
        //   Où ret = today's date format DDMYYYY (= "2562026" pour 25/6/2026).
        //   On reproduit ça en Kotlin pour éviter d'exécuter le JS.
        if (body.contains("callofliberty.fr/streams/", ignoreCase = true)) {
            Log.d(TAG, "callofliberty body detected, len=${body.length}, ua_head=${ua.take(80)}")
            val tokenMatch = Regex("""callofliberty=([A-Za-z0-9]{20,})""").find(body)
            val token = tokenMatch?.groupValues?.get(1)
            Log.d(TAG, "callofliberty token=$token, ua.contains(~col)=${ua.contains("~col")}")
            if (token != null) {
                // Try BOTH UTC and local date in case the device timezone differs
                val cals = listOf(
                    java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")),
                    java.util.Calendar.getInstance(),
                )
                for (cal in cals) {
                    val ret = "${cal.get(java.util.Calendar.DAY_OF_MONTH)}${cal.get(java.util.Calendar.MONTH) + 1}${cal.get(java.util.Calendar.YEAR)}"
                    val marker = "~col$ret"
                    val idx = ua.indexOf(marker)
                    Log.d(TAG, "callofliberty try marker='$marker', idx=$idx")
                    if (idx >= 0) {
                        val afterMarker = ua.substring(idx + marker.length)
                        val seg = afterMarker.substringBefore("~").replace("-", "/")
                        if (seg.isNotBlank() && !seg.contains(" ")) {
                            val finalUrl = "https://s2.callofliberty.fr/streams/$seg.m3u8?callofliberty=$token"
                            Log.d(TAG, "callofliberty MATCHED → $finalUrl")
                            return finalUrl
                        }
                    }
                }
                // Last resort : extract ~col<XXXXXXX>YYY~ from UA via regex (date-agnostic)
                val regexUa = Regex("""~col(\d{6,8})([A-Za-z0-9\-_]+)~""")
                val uaMatch = regexUa.find(ua)
                if (uaMatch != null) {
                    val seg = uaMatch.groupValues[2].replace("-", "/")
                    val finalUrl = "https://s2.callofliberty.fr/streams/$seg.m3u8?callofliberty=$token"
                    Log.d(TAG, "callofliberty REGEX fallback → $finalUrl")
                    return finalUrl
                }
            }
        }

        // 1. Pipeline 2-hop avec Nxt() : si le body contient une fonction
        //    `Nxt(navigator.userAgent...)`, le pipeline attend qu'on construise
        //    une URL suivante depuis le UA. Le script JS fait :
        //      nxurl = "https://rsseverything.com/en/feed/" + ua.split("@")[1].split("/")[1].split("#")[0] + ".xml?"
        //    On reproduit ça en Kotlin (pas de "TF1" hardcodé, c'est le UA qui
        //    porte l'info).
        if (body.contains("Nxt(navigator.userAgent")) {
            try {
                val seg = ua.substringAfter("@", "").substringAfter("/", "").substringBefore("#")
                if (seg.length > 8 && !seg.contains("/")) {
                    val nextUrl = "https://rsseverything.com/en/feed/$seg.xml?"
                    return nextUrl
                }
            } catch (_: Throwable) { /* fallthrough */ }
        }

        // 1b. Cascade de fallbacks via atob() : si le body contient des
        //     `atob("base64")` qui décodent en URLs `rsseverything.com/feed/`,
        //     prendre la PREMIÈRE — c'est la stratégie primaire (= le RSS qui
        //     contient le pattern `==="<token>") { fetch(...)` final).
        //     Pas de hardcoding par chaîne : tout est dans le UA et le body.
        if (body.contains("atob(") && body.contains("rsseverything")) {
            val atobPattern = Regex("""atob\("([A-Za-z0-9+/=_\-]+)"\)""")
            for (m in atobPattern.findAll(body)) {
                val raw = m.groupValues[1]
                val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
                val decoded = try {
                    String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
                } catch (_: Throwable) { continue }
                // On cherche un RSS feed cible (rsseverything.com/feed/UUID.xml).
                if (decoded.startsWith("https://rsseverything.com/feed/") &&
                    decoded.contains(".xml")) {
                    return decoded.trimEnd('#').trimEnd('?')
                }
            }
        }

        // 2. Pattern script JS direct : `==="<token>") { fetch(("<url>"...`
        //    Le token vient du UA via `~tnpol<token>~` (ou ancien `~tpol`).
        val token = if (ua.contains("~tnpol")) {
            ua.substringAfter("~tnpol").substringBefore("~").trim()
        } else if (ua.contains("~tpol")) {
            ua.substringAfter("~tpol").substringBefore("~").trim()
        } else null

        if (!token.isNullOrBlank()) {
            // 2026-06-12 (user "on était censé avoir fait un bypass générique
            //   qui change pas tous les quatre matins") : Approche RÉSILIENTE.
            //
            //   1) Cherche le token literal `"<token>"` dans le body
            //   2) Tente la stratégie A : URL directe `.m3u8`/`.mpd` dans
            //      les 5000 chars qui suivent
            //   3) Si A échoue → stratégie B (= "déobfuscation atob+literal")
            //      le RSS a évolué pour cacher les URLs en base64 via des
            //      `atob("...")+"segment"+atob("...")+"..."` concaténés
            //      autour d'un `fetch((...))`. On reconstruit l'URL en
            //      décodant + concaténant tous les segments.
            //
            //   Avec A + B, on est résilient à :
            //   - changements de syntaxe JS (= == === ==== conditions add.)
            //   - allongement du JS entre token et URL
            //   - obfuscation atob() partielle ou totale
            //   - mélange URLs en clair / chiffrées
            //   tant que le token literal reste référencé avant la cible.
            val tokenLiteral = "\"$token\""
            val tokenIdx = body.indexOf(tokenLiteral, ignoreCase = true)
            if (tokenIdx >= 0) {
                // 2026-06-12 v2 : fenêtre 5000 → 15000 chars pour capturer
                //   les éventuels backup fetch après le primary empoisonné.
                val windowEnd = (tokenIdx + 15000).coerceAtMost(body.length)
                val window = body.substring(tokenIdx, windowEnd)
                // Stratégie A : URLs directes (itère TOUTES, prend la 1ère
                //   non-empoisonnée).
                val mediaPattern = Regex(
                    """(https?:[^"'\s<>]*?\.(?:m3u8|mpd)(?:\?[^"'\s<>]*)?)""",
                    RegexOption.IGNORE_CASE,
                )
                for (m in mediaPattern.findAll(window)) {
                    val u = m.value.replace("\\/", "/")
                    if (!isPoisonedHost(u)) return u
                }
                // Stratégie B : déobfuscation atob+literal (itère aussi en
                //   interne sur tous les fetch — voir deobfuscateAtobUrl).
                val deobfuscated = deobfuscateAtobUrl(window)
                if (deobfuscated != null) {
                    return deobfuscated
                }
            }
            // Token attendu mais URL non trouvée à proximité → null pour
            // permettre au caller de tenter d'autres pipelines (bypassSfr,
            // etc.) sans risquer de jouer la mauvaise chaîne.
            return null
        }

        // 3. Fallback : chercher une URL m3u8/mpd directe dans le body, MAIS
        //    UNIQUEMENT quand aucun token n'était attendu (= pipeline générique
        //    sans tpol UA). Si un token était attendu et n'a pas matché, on
        //    aurait déjà retourné null au-dessus.
        val mediaPattern = Regex(
            """https?:[^"'\s<>]*?\.(?:m3u8|mpd)(?:\?[^"'\s<>]*)?""",
            RegexOption.IGNORE_CASE,
        )
        val mediaMatch = mediaPattern.find(body)
        if (mediaMatch != null) {
            return mediaMatch.value.replace("\\/", "/")
        }

        return null
    }

    /**
     * 2026-06-12 — Déobfusque une URL construite par concaténation de
     * `atob("base64")` + segments littéraux dans un bloc JS du style :
     *
     *   fetch((atob("aHR0cHM6...")+"02\/1\/"+atob("aW5kZXg...")+"39a17b..."
     *     ).replaceAll("\/","/"))
     *
     * Algo : on cherche le `fetch((` qui ouvre le bloc, on parse l'expression
     * de concaténation jusqu'à `.replaceAll`, en extrayant alternativement
     * les `atob("X")` (à décoder en base64) et les `"literal"` (à garder
     * tels quels). Concatène le tout, applique `.replaceAll("\\/", "/")`,
     * valide que ça commence par `http`. Retourne null si rien n'est
     * extractable.
     *
     * Indépendant du domaine cible — marche pour latvdefrance, n'importe
     * quel CDN futur, n'importe quel ordre d'atob/literal.
     */
    /** Variante publique pour réutilisation depuis BoxXtemusProvider
     *  (= latvdefranceShortcut). Sinon strictement identique. */
    fun deobfuscateAtobUrlPublic(window: String): String? = deobfuscateAtobUrl(window)

    /** 2026-06-12 (logs OPPO : "FAST-TRACK latvdefrance for TF1: http://
     *  cdn.adultiptv.net/gay.m3u8…") : le RSS communautaire rsseverything
     *  a empoisonné toutes ses URLs en redirigeant TF1/F2/CStar/etc. vers
     *  `cdn.adultiptv.net` (= riposte contre les apps qui exploitent
     *  leur service gratuit). On bloque ces URLs au niveau du résolveur
     *  pour éviter de jouer du contenu adulte sous le nom de TF1.
     *  Liste de hosts pirates connus pour servir du XXX à la place du
     *  contenu mainstream. */
    private val POISONED_HOSTS = listOf(
        "adultiptv", "adultiptv.net", "porniptv", "xxxiptv",
        "mycamtv", "livejasmin", "xcam", "bongacams",
        "chaturbate", "camsoda", "stripchat", "pornhub",
        "xnxx", "xvideos", "redtube", "youporn",
    )

    private fun isPoisonedHost(url: String): Boolean {
        val lower = url.lowercase()
        return POISONED_HOSTS.any { lower.contains(it) }
    }

    /** 2026-06-12 (logs OPPO : 16 fetch empoisonnés du RSS rsseverything,
     *  tous au pattern `cdn.adultiptv.net/gay.m3u8#XX/YY/index.m3u8?token=`) :
     *  le mainteneur du RSS a juste REMPLACÉ le préfixe
     *  `https://www.latvdefrance.com/ca/hls/transcoder` par
     *  `http://cdn.adultiptv.net/gay.m3u8#`. Le segment derrière (`XX/YY/
     *  index.m3u8?token=...`) est CONSERVÉ. On peut donc reconstruire
     *  l URL latvdefrance originale en inversant ce remplacement.
     *
     *  Ce n est PAS une rustine spécifique à TF1/latvdefrance — c est un
     *  undo d une corruption identifiée. Si le pattern change demain on
     *  adaptera le mapping. Mais tant qu il reste tel quel, on récupère
     *  ~60 chaînes FR gratis sans rien hardcoder par chaîne. */
    fun reconstructFromPoisonedPattern(poisonedUrl: String): String? {
        val poisonedPrefix = "://cdn.adultiptv.net/gay.m3u8#"
        if (!poisonedUrl.contains(poisonedPrefix)) return null
        val tail = poisonedUrl.substringAfter(poisonedPrefix)
        if (tail.isBlank()) return null
        // tail = ex "02/1/index.m3u8?token=..."
        val reconstructed = "https://www.latvdefrance.com/ca/hls/transcoder$tail"
        // Sanity check : le résultat ne doit PAS être empoisonné
        return if (!isPoisonedHost(reconstructed)) reconstructed else null
    }

    private fun deobfuscateAtobUrl(window: String): String? {
        // 2026-06-12 v2 (user "continue à chercher") : itère sur TOUS les
        //   `fetch((...))` du window jusqu'à trouver le PREMIER avec URL
        //   non-empoisonnée. Les RSS communautaires peuvent contenir
        //   plusieurs fetch (= primary + fallbacks/backups CDN). Si le
        //   primary est empoisonné, peut-être qu'un fallback est encore
        //   propre.
        var searchStart = 0
        while (true) {
            val fetchStart = window.indexOf("fetch((", searchStart)
            if (fetchStart < 0) return null
            searchStart = fetchStart + "fetch((".length
            // Borne sup du bloc de concaténation
            val concatEnd = listOf(
                window.indexOf(".replaceAll", fetchStart),
                window.indexOf(").then", fetchStart),
                window.indexOf("))", fetchStart + 7),
            ).filter { it > 0 }.minOrNull() ?: continue
            val concatRegion = window.substring(fetchStart + "fetch((".length, concatEnd)
            val pattern = Regex("""atob\("([^"]+)"\)|"([^"]*)"""")
            val parts = mutableListOf<String>()
            for (m in pattern.findAll(concatRegion)) {
                val b64 = m.groupValues[1]
                val lit = m.groupValues[2]
                if (b64.isNotEmpty()) {
                    val decoded = try {
                        String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                    } catch (_: Throwable) { null }
                    if (decoded == null) {
                        parts.clear(); break
                    }
                    parts.add(decoded)
                } else {
                    parts.add(lit)
                }
            }
            if (parts.isEmpty()) continue
            val raw = parts.joinToString("")
            val url = raw.replace("\\/", "/")
            if (isPoisonedHost(url)) {
                // 2026-06-12 v3 (user "France 2 qui fonctionnait avant ne
                //   fonctionne plus") : la reconstruction du pattern
                //   empoisonné NE DOIT PAS être faite ici. Elle kidnappait
                //   France 2/3/4/5/LCI qui devraient passer par ftven.fr ou
                //   mediainfo.tf1.fr via les hops suivants du pipeline. On
                //   skip simplement les fetch empoisonnés ; le GenericResolver
                //   continuera ses hops et trouvera le bon endpoint pour les
                //   chaînes qui en ont un. La reconstruction reste dans le
                //   `latvdefranceShortcut` (BoxXtemusProvider étape 5) en
                //   fallback final pour les chaînes TF1+/Canal+ qui n ont pas
                //   de pipeline officiel.
                android.util.Log.d("GenericResolver", "deobfuscate: skip poisoned fetch → $url")
                continue
            }
            if (url.startsWith("http", ignoreCase = true) &&
                (url.contains(".m3u8") || url.contains(".mpd") || url.contains(".ts"))
            ) {
                return url
            }
            // pas un media valide → continue à chercher
        }
    }

    /** Ancienne implémentation conservée pour référence — non utilisée. */
    @Suppress("unused")
    private fun deobfuscateAtobUrlOldSingleFetch(window: String): String? {
        val fetchStart = window.indexOf("fetch((")
        if (fetchStart < 0) return null
        val concatEnd = listOf(
            window.indexOf(".replaceAll", fetchStart),
            window.indexOf(").then", fetchStart),
            window.indexOf("))", fetchStart + 7),
        ).filter { it > 0 }.minOrNull() ?: return null

        val concatRegion = window.substring(fetchStart + "fetch((".length, concatEnd)
        val pattern = Regex("""atob\("([^"]+)"\)|"([^"]*)"""")
        val parts = mutableListOf<String>()
        for (m in pattern.findAll(concatRegion)) {
            val b64 = m.groupValues[1]
            val lit = m.groupValues[2]
            if (b64.isNotEmpty()) {
                val decoded = try {
                    String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                } catch (_: Throwable) { return null }
                parts.add(decoded)
            } else {
                parts.add(lit)
            }
        }
        if (parts.isEmpty()) return null
        val raw = parts.joinToString("")
        val url = raw.replace("\\/", "/")
        if (isPoisonedHost(url)) return null
        return if (url.startsWith("http", ignoreCase = true) &&
            (url.contains(".m3u8") || url.contains(".mpd") || url.contains(".ts"))
        ) url else null
    }

    /**
     * Heuristique : URL qui ressemble déjà à un manifest playable.
     * Si c'est .m3u8/.mpd/.ts on s'arrête, pas la peine de HEAD.
     */
    private fun looksLikeMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mpd") ||
                lower.contains(".ts?") || lower.endsWith(".ts") ||
                lower.contains("/index.m3u8") || lower.contains("/manifest")
    }
}
