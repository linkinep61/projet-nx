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
                            // Si c'est du RSS / XML / HTML, fetch le body en GET pour parser
                            //   les patterns universels (script fetch, m3u8 dans le HTML…).
                            //   Sinon c'est probablement un manifest = URL finale.
                            if (ctype.contains("xml") || ctype.contains("html") || ctype.contains("text")) {
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
        //    Le token vient du UA via `~tpol<token>~`.
        val token = if (ua.contains("~tpol")) {
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
                val windowEnd = (tokenIdx + 5000).coerceAtMost(body.length)
                val window = body.substring(tokenIdx, windowEnd)
                // Stratégie A : URL directe
                val mediaInWindow = Regex(
                    """(https?:[^"'\s<>]*?\.(?:m3u8|mpd)(?:\?[^"'\s<>]*)?)""",
                    RegexOption.IGNORE_CASE,
                ).find(window)
                if (mediaInWindow != null) {
                    val u = mediaInWindow.value.replace("\\/", "/")
                    // 2026-06-12 — rejette les URLs vers hosts empoisonnés.
                    if (!isPoisonedHost(u)) return u
                }
                // Stratégie B : déobfuscation atob+literal
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

    private fun deobfuscateAtobUrl(window: String): String? {
        val fetchStart = window.indexOf("fetch((")
        if (fetchStart < 0) return null
        // Borne supérieure : la fin du bloc de concaténation se reconnaît
        // au `).replaceAll` ou à `)).then` ou simplement à `))` après une
        // séquence de + et de littéraux/atob. On prend large.
        val concatEnd = listOf(
            window.indexOf(".replaceAll", fetchStart),
            window.indexOf(").then", fetchStart),
            window.indexOf("))", fetchStart + 7),
        ).filter { it > 0 }.minOrNull() ?: return null

        val concatRegion = window.substring(fetchStart + "fetch((".length, concatEnd)
        // Match alternance : `atob("BASE64")` OU `"literal"`
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
        // 2026-06-12 — Si l'URL extraite pointe vers un host empoisonné
        // (adultiptv.net etc.), on retourne null pour ne PAS jouer du XXX.
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
