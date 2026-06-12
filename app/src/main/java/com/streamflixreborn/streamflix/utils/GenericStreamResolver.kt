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
            val tokenEscaped = Regex.escape(token)
            // Le pattern peut contenir du JS optionnel entre `"<token>"` et
            // `) {fetch` (ex: `&&!/http/.test(document.referrer)`). On accepte
            // n'importe quel contenu hors `)` entre les deux.
            val pattern = Regex(
                """=\s*=\s*=\s*"$tokenEscaped"\s*[^)]*\)\s*\{\s*fetch\s*\(\s*\(\s*"([^"]+)"""",
                RegexOption.IGNORE_CASE,
            )
            val match = pattern.find(body)
            if (match != null) {
                return match.groupValues[1].replace("\\/", "/")
            }
        }

        // 3. Fallback : chercher une URL m3u8/mpd directe dans le body.
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
