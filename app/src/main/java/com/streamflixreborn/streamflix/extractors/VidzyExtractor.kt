package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.streamflixreborn.streamflix.utils.UserPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class VidzyExtractor : Extractor() {

    override val name = "Vidzy"
    override val mainUrl = "https://vidzy.org"
    // 2026-07-31 : `vidzy.cc` (et `vidzy.to`) = domaines d'EMBED réellement servis par
    //   l'iframe des pages vidzy.org/{movie,serie}/… (et par CoflixWiki). Sans ces alias,
    //   AUCUN extracteur n'était sélectionné pour un lien vidzy.cc → serveur mort.
    override val aliasUrls = listOf("https://vidzy.live", "https://vidzy.cc", "https://vidzy.to")

    // Match rotating subdomains like u14.vidzy.live
    override val rotatingDomain: List<Regex> = listOf(
        Regex("""u\d+\.vidzy\.(live|org)""")
    )

    // 2026-08-01 (user : « depuis quand ça apparaît comme ça, Vidzy ? », capture de l'overlay
    //   WebView) : NE PAS METTRE EN CACHE le résultat de cet extracteur.
    //   Diagnostic : les logs ne montraient AUCUNE trace de VidzyExtractor, seulement
    //   « [EXTRACTOR] -> Cache HIT for vidzy.cc/embed-… » — l'extraction n'était jamais
    //   rejouée. Un ancien échec avait été mémorisé sous sa forme de REPLI
    //   (`needsWebViewClick = true`), et ce repli était resservi pendant 10 min : l'overlay
    //   WebView revenait donc systématiquement, même une fois le vrai flux redevenu
    //   extractible. Le résultat de Vidzy est de toute façon éphémère (URL déchiffrée en JS,
    //   liée à la session), donc le cache n'apportait rien.
    //   Même famille de piège que LuluVdo et DoodStream, corrigés pareil aujourd'hui.
    override val cacheTtlMs: Long = 0L

    companion object {
        private const val TAG = "VidzyExtractor"

        private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

        val client: OkHttpClient = run {
            val cookieJar = object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore[url.host] = cookies
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            }
            Extractor.sharedClient.newBuilder()
                .cookieJar(cookieJar)
                .build()
        }
    }

    fun extractSubtitles(text: String): List<Video.Subtitle> {
        // 2026-06-09 v2 (user "ne s'active pas") : la page Vidzy a changé.
        //   Plusieurs patterns possibles selon les versions du player JS :
        //   - loadTracks([...])        ← ancien
        //   - tracks:[...]             ← jwplayer style
        //   - subtitle:'...'           ← player older
        //   - subtitles:[...]          ← plyr style
        //   On essaie en cascade et on log ce qu'on trouve.
        var tracksContent: String? = null
        val patterns = listOf(
            "loadTracks" to Regex("""loadTracks\s*\(\s*\[(.*?)]\s*\)""", RegexOption.DOT_MATCHES_ALL),
            "tracks:[]" to Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL),
            "subtitles:[]" to Regex("""subtitles\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL),
            "captions:[]" to Regex("""captions\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL),
        )
        for ((name, rx) in patterns) {
            val m = rx.find(text)
            if (m != null) {
                tracksContent = m.groupValues[1]
                Log.d(TAG, "extractSubtitles: matched pattern '$name' (len=${tracksContent.length})")
                break
            }
        }
        if (tracksContent == null) {
            Log.d(TAG, "extractSubtitles: NO subtitle pattern matched (autoSubDis=${UserPreferences.serverAutoSubtitlesDisabled})")
            // Diagnostic : portions de la page autour des mots clés sub.
            val hints = listOf("loadTracks", "_fre", "_fra", ".vtt", "srtproxy", "dxsrv", "addTextTrack", "srclang", "kind=", "kind:")
            for (h in hints) {
                val idx = text.indexOf(h, ignoreCase = true)
                if (idx >= 0) {
                    val s = maxOf(0, idx - 80)
                    val e = minOf(text.length, idx + 300)
                    Log.d(TAG, "extractSubtitles diag '$h' @$idx: ${text.substring(s, e).replace("\n", "\\n")}")
                }
            }
            return emptyList()
        }

        val objectRegex = Regex("""\{(.*?)\}""")

        val result = objectRegex.findAll(tracksContent).mapNotNull { match ->
            val obj = match.groupValues[1]

            val label = Regex("""label:'([^']+)'""").find(obj)?.groupValues?.get(1)
            val file = Regex("""src:'([^']+)'""").find(obj)?.groupValues?.get(1)
            val default = Regex("""default:(true|false)""").find(obj)?.groupValues?.get(1)?.toBoolean() ?: false

            if (label == null || file == null || !file.startsWith("http")) return@mapNotNull null
            Video.Subtitle(
                file = file,
                label = label,
                initialDefault = default,
                default = if (UserPreferences.serverAutoSubtitlesDisabled) false else default
            )
        }.toList()
        Log.d(TAG, "extractSubtitles: found ${result.size} subs (autoSubDis=${UserPreferences.serverAutoSubtitlesDisabled}) — labels=${result.joinToString { "${it.label}(def=${it.default})" }}")
        return result
    }

    // 2026-10-12 (repris de l'équipe d'origine, v1.7.236) : Vidzy n'utilise plus de Packed JS.
    //   La page contient : sources: [{src: (function(s){ … })("<base64>")}]
    //   Déchiffrement côté navigateur : clé = somme des codes du hostname (&255)
    //   + BC (largeur en px d'un div à 50% de « calc(1in + Npx) », soit (96+N)/2),
    //   puis base64 → inversé → XOR (0x3d + i*89 + clé) & 255.
    //   Une mauvaise clé donne le leurre « …/troll/master.m3u8 » : on n'accepte qu'une
    //   URL http(s) sans « /troll/ ». On essaie l'arrondi bas ET haut de BC (N impair).
    private fun dechiffrerNouveauFormat(html: String, link: String): String? {
        val fonction = Regex("""src:\s*\(function\([a-zA-Z]+\).*?\}\)\(["']([^"']+)["']\)""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        val payload = fonction.groupValues[1]
        val px = Regex("""width:calc\(1in\s*\+\s*(\d+)px\)""").find(fonction.value)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""width:calc\(1in\s*\+\s*(\d+)px\)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 156
        // 2026-10-21 (user : Vidzy rouge, flux 403) : la largeur du div ENFANT a changé — avant
        //   « 50% » (BC = (96+N)/2), maintenant « calc(100% - 11px) » (BC = 96+N-11 = 171, mesuré
        //   dans le navigateur). On lit donc la formule de l'enfant (2ᵉ « width: » de la fonction)
        //   au lieu de la supposer : X% du parent, ± Y px.
        val parent = 96 + px
        val candidatsBc = mutableListOf<Int>()
        Regex("""width:\s*(?:calc\(\s*)?(\d+(?:\.\d+)?)%\s*(?:([+-])\s*(\d+)px)?""").findAll(fonction.value).forEach { m ->
            val base = parent * (m.groupValues[1].toDoubleOrNull() ?: return@forEach) / 100.0
            val dec = m.groupValues[3].toIntOrNull() ?: 0
            val v = if (m.groupValues[2] == "-") base - dec else base + dec
            candidatsBc += kotlin.math.floor(v).toInt()
            candidatsBc += kotlin.math.ceil(v).toInt()
        }
        candidatsBc += listOf(parent / 2, (parent + 1) / 2)
        val octets = try {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT).reversedArray()
        } catch (e: Exception) { return null }
        val hotes = listOfNotNull(
            try { URL(link).host } catch (_: Exception) { null }, "vidzy.cc", "vidzy.live", "vidzy.to"
        ).distinct()
        for (hote in hotes) {
            var h = 0
            for (c in hote) h = (h + c.code) and 255
            for (bc in candidatsBc.distinct()) {
                val sb = StringBuilder(octets.size)
                for (i in octets.indices) {
                    val kk = (0x3d + i * 89 + h + bc) and 255
                    sb.append(((octets[i].toInt() and 0xFF) xor kk).toChar())
                }
                val url = sb.toString()
                if (url.startsWith("http") && !url.contains("/troll/", true) && url.all { it.code in 33..126 }) {
                    Log.i(TAG, "Nouveau format déchiffré (hôte=$hote, BC=$bc)")
                    return url
                }
            }
        }
        Log.w(TAG, "Nouveau format présent mais déchiffrement impossible")
        return null
    }

    override suspend fun extract(link: String): Video {
        // If already a direct HLS URL from a vidzy subdomain, use it directly
        if (link.contains(".m3u8") || link.contains("/hls")) {
            Log.d(TAG, "Direct HLS URL detected: $link")
            return Video(
                source = link,
                type = MimeTypes.APPLICATION_M3U8,
                headers = mapOf("Referer" to "https://vidzy.live/")
            )
        }

        Log.d(TAG, "Fetching embed page: $link")

        val html = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(link)
                .build()

            // First request — may return a cookie-setting landing page
            val response1 = client.newCall(request).execute()
            val body1 = response1.body?.string() ?: throw Exception("Empty response from Vidzy")
            Log.d(TAG, "Request 1 - code: ${response1.code}, length: ${body1.length}, cookies: ${cookieStore.size}")
            // 2026-10-21 (user : « quand Vidzy renvoie cette page-là, on skip ») — page d'attente
            //   « Chargement vidéo / moins de 3 minutes » : fichier coincé en traitement chez Vidzy.
            //   Vérifié dans le navigateur : le compte à rebours repart de zéro et ne lit jamais.
            //   On abandonne tout de suite (serveur en rouge) au lieu de 2 essais + 20 s de WebView.
            if (body1.contains("<title>Chargement vidéo</title>") ||
                (body1.contains("Chargement de la vidéo") && body1.contains("moins de 3 minutes"))) {
                Log.w(TAG, "Page d'attente « Chargement vidéo » → vidéo indisponible chez Vidzy, on passe")
                throw Exception("Vidzy: vidéo en cours de traitement chez l'hébergeur (page d'attente)")
            }

            // Check if we got the real page (has packed JS)
            val hasPacked = body1.contains("function(p,a,c,k,e,d)") || body1.contains("eval(") || body1.contains("atob(s)")
            if (hasPacked) {
                Log.d(TAG, "Got full page on first request!")
                body1
            } else {
                // Second request with cookies from first response
                Log.d(TAG, "Landing page detected, retrying with cookies...")
                delay(1000) // Small delay to mimic browser behavior
                val response2 = client.newCall(request).execute()
                val body2 = response2.body?.string() ?: throw Exception("Empty response on retry")
                Log.d(TAG, "Request 2 - code: ${response2.code}, length: ${body2.length}")

                // If still no packed JS, try with different referer
                val hasPacked2 = body2.contains("function(p,a,c,k,e,d)") || body2.contains("eval(") || body2.contains("atob(s)")
                if (hasPacked2) {
                    body2
                } else {
                    // Third attempt with self-referer
                    Log.d(TAG, "Still no packed JS, trying with self-referer...")
                    val request3 = Request.Builder()
                        .url(link)
                        .header("Referer", link)
                        .build()
                    val response3 = client.newCall(request3).execute()
                    val body3 = response3.body?.string() ?: throw Exception("Empty response on 3rd try")
                    Log.d(TAG, "Request 3 - code: ${response3.code}, length: ${body3.length}")
                    body3
                }
            }
        }

        // Check if we got a Cloudflare challenge page
        if (html.contains("Just a moment") || html.contains("cf-browser-verification")) {
            Log.e(TAG, "Cloudflare challenge detected!")
            throw Exception("Cloudflare challenge - Vidzy blocked")
        }

        // Debug: log snippets to understand page structure
        val hasEval = html.contains("eval(")
        val hasPack = html.contains("function(p,a,c,k,e,d)")
        val hasSplit = html.contains(".split('|')")
        Log.d(TAG, "Page analysis: hasEval=$hasEval, hasPack=$hasPack, hasSplit=$hasSplit")

        // Try multiple approaches to find packed JS
        var unPacked: String? = null

        // Approach 1: Original method - find packed JS after function declaration
        if (hasPack) {
            val packedJS = Regex("""(\}\s*\('.*?'\.split\('\|'\))""")
                .find(html.substringAfter("function(p,a,c,k,e,d)"))
                ?.groupValues?.get(1)
            if (packedJS != null) {
                Log.d(TAG, "Found packed JS (approach 1), unpacking...")
                unPacked = JsUnpacker(packedJS).unpack()
            }
        }

        // Approach 2: Use JsUnpacker directly on the whole HTML
        if (unPacked == null && hasEval) {
            Log.d(TAG, "Trying JsUnpacker on full HTML...")
            // Find eval(function(p,a,c,k,e,d) blocks
            val evalRegex = Regex("""eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.*?)',\s*(\d+),\s*(\d+),\s*'(.*?)'\s*\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            val evalMatch = evalRegex.find(html)
            if (evalMatch != null) {
                Log.d(TAG, "Found eval block, trying to unpack...")
                val fullBlock = html.substring(evalMatch.range.first)
                    .substringAfter("eval(")
                    .let { "(" + it.substringBefore("})") + "})" }
                unPacked = try { JsUnpacker(fullBlock).unpack() } catch (e: Exception) { null }
            }
        }

        // Approach 3: Direct regex on the HTML for the p,a,c,k format
        if (unPacked == null && hasSplit) {
            Log.d(TAG, "Trying direct packed extraction...")
            val directRegex = Regex("""'([^']{100,})'\s*\.split\('\|'\)""")
            val directMatch = directRegex.find(html)
            if (directMatch != null) {
                Log.d(TAG, "Found split block, attempting manual unpack...")
                // Try feeding it to JsUnpacker differently
                val codeBlock = html.substring(
                    maxOf(0, directMatch.range.first - 2000),
                    minOf(html.length, directMatch.range.last + 10)
                )
                unPacked = try { JsUnpacker(codeBlock).unpack() } catch (e: Exception) {
                    Log.e(TAG, "Manual unpack failed: ${e.message}")
                    null
                }
            }
        }

        val searchText = unPacked ?: run {
            Log.d(TAG, "No packed JS unpacked, searching raw HTML")
            // Log first 500 chars for debugging
            Log.d(TAG, "HTML preview: ${html.take(500).replace("\n", " ")}")
            html
        }

        Log.d(TAG, "Searching for stream URL in content (length: ${searchText.length})")

        // Try multiple patterns to find the stream URL
        // 2026-10-12 : nouveau format Vidzy déchiffré d'abord (voir dechiffrerNouveauFormat).
        val streamUrl = dechiffrerNouveauFormat(html, link) ?: listOf(
            Regex("""src\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""source\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https?://u\d+\.vidzy\.[^"'\s]+master\.m3u8[^"'\s]*)"""),
            Regex("""(https?://[^"'\s]+vidzy[^"'\s]+\.m3u8[^"'\s]*)"""),
            Regex("""src\s*:\s*["'](https?://[^"']+)["']"""),
            Regex("""file\s*:\s*["'](https?://[^"']+)["']""")
        ).firstNotNullOfOrNull { regex ->
            regex.find(searchText)?.groupValues?.get(1)
        }
            // 2026-07-31 (user « pas fonctionné », Silo S3E5) : Vidzy CHIFFRE désormais
            //   l'URL du flux (base64 → binaire, déchiffré à l'exécution) et laisse un
            //   LEURRE en clair dans le JS (« …fsvid.lol/troll/master.m3u8 ») pour piéger
            //   les scrapers. Aucune regex statique ne peut plus marcher.
            //   → repli : on exécute la page dans une WebView et on CAPTE la vraie requête
            //     sortante (u<N>.vidzy.cc/hls2/…/master.m3u8?t=…), comme AnonMP4/Filemoon.
            ?.takeIf { !it.contains("/troll/", true) && !it.contains("fsvid.lol", true) }
            ?: run {
                // 2026-07-31 (user, Silo S3E5) : Vidzy CHIFFRE l'URL du flux (base64 → binaire,
                //   déchiffrée en JS) et laisse un LEURRE en clair (« …fsvid.lol/troll/… »).
                //   → on capte la VRAIE URL en exécutant la page dans une WebView.
                Log.i(TAG, "URL en clair absente/leurre → résolution WebView")
                val resolved = com.streamflixreborn.streamflix.utils.WebViewStreamResolver.resolve(
                    entryUrl = link,
                    referer = link,
                    timeoutMs = 20_000L,
                    clickPlay = true,
                    attach = true,
                    // 2026-10-19 : clé de segments (cf. utils/CleVidzy) — sans elle, 403 dès le 10ᵉ segment.
                    lireJsApresCapture = "(window.__vzgk||null)",
                )
                if (resolved != null) {
                    val gk = resolved.valeurJs
                    if (gk != null) {
                        com.streamflixreborn.streamflix.utils.CleVidzy.memoriser(gk)
                        Log.i(TAG, "clé de segments Vidzy (gk) récupérée (${gk.length} car.)")
                    } else {
                        Log.w(TAG, "clé de segments Vidzy (gk) absente de la page → 403 probable après ~90 s")
                    }
                    // ⚠️ Les headers captés par WebResourceRequest ne contiennent PAS les
                    //   Sec-Fetch-*. Sans eux, l'intercepteur NetworkClient pose
                    //   « document/navigate/none » = signature d'une NAVIGATION de page →
                    //   le CDN vidzy répond 403 (exactement ce que dit déjà le commentaire du
                    //   chemin nominal plus bas). On rejoue donc avec les MÊMES headers que
                    //   le chemin nominal (empty/cors/same-site), qui eux fonctionnent.
                    val host = try { URL(link).host } catch (_: Exception) { "vidzy.cc" }
                    val finalHeaders = HashMap(resolved.headers).apply {
                        put("Referer", link)
                        put("Origin", "https://$host")
                        put("Accept", "*/*")
                        put("Sec-Fetch-Dest", "empty")
                        put("Sec-Fetch-Mode", "cors")
                        put("Sec-Fetch-Site", "same-site")
                    }
                    Log.i(TAG, "WebView a capté le flux — rejeu avec Sec-Fetch empty/cors/same-site")
                    return Video(
                        source = resolved.url,
                        type = if (resolved.url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null,
                        subtitles = extractSubtitles(searchText),
                        headers = finalHeaders,
                        useServerSubtitleSetting = true,
                    )
                }
                // 2026-08-01 (DÉCISION user : « je préfère pas avoir de repli plutôt que
                //   d'avoir cette *** là ») : l'overlay WebView est SUPPRIMÉ.
                //   Il servait de filet quand la capture du flux échouait, mais il impose une
                //   page web par-dessus le lecteur — inutilisable au confort à la télécommande,
                //   et l'utilisateur le refuse explicitement.
                //   On échoue donc PROPREMENT : le serveur est marqué mort et le player
                //   enchaîne tout seul sur le suivant, au lieu d'imposer cette interface.
                Log.w(TAG, "WebView n'a rien capté → échec (pas d'overlay, on passe au serveur suivant)")
                throw Exception("Vidzy: flux non extractible (overlay WebView désactivé)")
            }

        Log.i(TAG, "Stream URL found: $streamUrl")

        val isM3u8 = streamUrl.contains(".m3u8")
        val baseHost = try { URL(link).host } catch (_: Exception) { "vidzy.live" }

        // Collect cookies from our cookie store for the stream host
        val streamHost = try { URL(streamUrl).host } catch (_: Exception) { baseHost }
        // Use the full embed URL as Referer (CDN checks this) + Origin header
        // Override Sec-Fetch headers: NetworkClient.default interceptor sets
        // "document"/"navigate"/"none" which tells the CDN "page navigation" →
        // CDN redirects to homepage. We need "empty"/"cors"/"same-site" like
        // a real browser JS player would send for XHR/fetch requests.
        val headers = mutableMapOf(
            "Referer" to link,
            "Origin" to "https://$baseHost",
            "Accept" to "*/*",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-site",
            "Upgrade-Insecure-Requests" to ""
        )

        // Add cookies for both the embed host and stream host
        val allCookies = mutableListOf<String>()
        cookieStore[baseHost]?.forEach { allCookies.add("${it.name}=${it.value}") }
        if (streamHost != baseHost) {
            cookieStore[streamHost]?.forEach { allCookies.add("${it.name}=${it.value}") }
        }
        if (allCookies.isNotEmpty()) {
            headers["Cookie"] = allCookies.joinToString("; ")
            Log.d(TAG, "Passing ${allCookies.size} cookies to player")
        }

        // 2026-06-09 (user "j'ai pas les sous-titres VOSTFR") : Vidzy a
        //   désactivé l'injection inline `loadTracks([...])` au profit d'un
        //   appel `player.loadTracks(...)` situé après notre fenêtre de
        //   parsing du packed JS. Heureusement le sub est accessible via
        //   un endpoint stable `srtproxy` qui se déduit du stream URL :
        //
        //   Stream  : https://{srv}.vidzy.cc/hls2/{disk}/{dx}/,{vid}_n,.urlset/master.m3u8
        //   Sub fr  : https://vidzy.live/srtproxy/{vid}_fre.vtt?dx={dx}&srv={srv}&disk={disk}
        //
        //   Le proxy redirige (302) vers le vrai .vtt hébergé (testé OK
        //   2026-06-09 : 31KB WEBVTT FR sur dc9ldrhhieke). ExoPlayer suit
        //   la 302 automatiquement.
        val parsedSubs = extractSubtitles(searchText).toMutableList()
        if (parsedSubs.isEmpty()) {
            try {
                val rx = Regex("""https://([^./]+)\.vidzy\.cc/hls2?/(\d+)/(\d+)/,([a-zA-Z0-9]+)_""")
                val m = rx.find(streamUrl)
                if (m != null) {
                    val srv = m.groupValues[1]
                    val disk = m.groupValues[2]
                    val dx = m.groupValues[3]
                    val vid = m.groupValues[4]
                    // 2026-06-09 v4 (user "404 sur certains films") : le
                    //   srtproxy fait du re-routing (disk varie côté cible).
                    //   On peut PAS deviner la cible. On passe l'URL srtproxy
                    //   et OkHttp (côté overlay) follow la 302 automatiquement
                    //   et récupère le VTT final.
                    val resolvedUrl = "https://vidzy.live/srtproxy/${vid}_fre.vtt?dx=$dx&srv=$srv&disk=$disk"
                    Log.d(TAG, "extractSubtitles: srtproxy sub URL: $resolvedUrl")
                    parsedSubs.add(
                        Video.Subtitle(
                            file = resolvedUrl,
                            label = "French",
                            initialDefault = true,
                            default = if (UserPreferences.serverAutoSubtitlesDisabled) false else true,
                        )
                    )
                } else {
                    Log.d(TAG, "extractSubtitles: stream URL does not match expected pattern → cannot derive sub")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "extractSubtitles: derive sub URL failed: ${t.message}")
            }
        }

        return Video(
            source = streamUrl,
            type = if (isM3u8) MimeTypes.APPLICATION_M3U8 else null,
            headers = headers,
            subtitles = parsedSubs,
            useServerSubtitleSetting = true
        )
    }
}
