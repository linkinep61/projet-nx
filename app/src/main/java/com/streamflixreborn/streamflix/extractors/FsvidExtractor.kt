package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class FsvidExtractor : Extractor() {
    override val name = "FSVid"
    override val mainUrl = "https://fsvid.lol"

    private val service = Extractor.createGsonService<FsvidService>(mainUrl)

    private interface FsvidService {
        // 2026-07-31 (user, « serveur FS rouge ») : FSVid sert DEUX pages différentes selon
        //   les en-têtes de la requête — vérifié en direct, même URL, même cookie, même
        //   Referer :
        //     • requête type XHR (Sec-Fetch-Dest: empty)   → 93 535 car., source = LEURRE
        //       `https://s1.fsvid.lol/troll/master.m3u8` (c'est la « pub » que l'app jouait)
        //     • requête type IFRAME (Dest: iframe, Mode: navigate) → 147 059 car., AUCUN
        //       leurre, et le vrai flux `…/hls2/01/00029/<id>_o/master.m3u8?t=…`
        //   L'app se présentait comme un XHR → elle recevait toujours la page piégée. On se
        //   déclare donc comme le lecteur embarqué qu'on est réellement.
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String = "",
            @Header("Sec-Fetch-Dest") secDest: String = "iframe",
            @Header("Sec-Fetch-Mode") secMode: String = "navigate",
            @Header("Sec-Fetch-Site") secSite: String = "same-origin",
            @Header("Upgrade-Insecure-Requests") upgrade: String = "1",
            @Header("Accept") accept: String =
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        ): String
    }

    /**
     * 2026-10-19 (repris de StreamFlix Reborn v1.7.237, vérifié en direct dans Chrome) : la page
     * embed n'est servie (sinon 403) et le vrai film n'est joué que si l'on VIENT de French Stream.
     * Referer = adresse actuelle de French Stream (pages ET flux vidéo).
     */
    private fun refererFrenchStream(): String = try {
        com.streamflixreborn.streamflix.providers.FrenchStreamProvider.baseUrl
            .takeIf { it.isNotBlank() }?.let { if (it.endsWith("/")) it else "$it/" }
    } catch (_: Throwable) { null } ?: "https://fs26.lol/"

    /**
     * 2026-10-19 : nouveau format FSVid (cf. StreamFlix Reborn v1.7.237). Le script contient
     * `(function(s){…})("<base64>")` : base64 → octets INVERSÉS → XOR avec une clé qui varie
     * par vidéo : (base + i*pas + somme des caractères de l'hôte + bc) & 0xFF, où base/pas sont
     * lus dans `kk=(0x..+i*..)` et bc = (96 + px) * pct, lus dans `calc(1in + Npx)` / `width:N%`.
     * Ne renvoie qu'une vraie URL http (jamais le leurre /troll/).
     */
    private fun dechiffrerNouveauFormat(fnCode: String, s: String, link: String): String? = try {
        val hote = Uri.parse(link).host.orEmpty()
        var h = 0
        for (ch in hote) h = (h + ch.code) and 0xFF
        // BC = largeur (px) d'un div enfant mesurée dans le navigateur : parent `calc(1in + Npx)`
        //   (1in = 96 px), enfant `calc(100% - Mpx)` / `calc(100% + Mpx)` / `N%`.
        //   2026-10-19, vérifié sur une vraie page : parent 1in+45px = 141, enfant 100%-26px
        //   → BC = 115 (le script contrôle d'ailleurs `BC!==115`) → vraie URL hls2 720p.
        val parent = 96 + (Regex("""calc\(1in\s*\+\s*(\d+)px\)""").find(fnCode)?.groupValues?.get(1)?.toIntOrNull() ?: 56)
        val bc = Regex("""calc\(100%\s*([+-])\s*(\d+)px\)""").find(fnCode)?.let { m ->
            val n = m.groupValues[2].toInt()
            if (m.groupValues[1] == "-") parent - n else parent + n
        } ?: Regex("""width:(\d+)%""").findAll(fnCode).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            ?.let { parent * it / 100 }
        ?: parent
        val kk = Regex("""kk\s*=\s*\(\s*(0x[0-9a-fA-F]+|\d+)\s*\+\s*i\s*\*\s*(\d+)""").find(fnCode)
        val base = kk?.groupValues?.get(1)?.let {
            if (it.startsWith("0x", true)) it.substring(2).toIntOrNull(16) else it.toIntOrNull()
        } ?: 0x3d
        val pas = kk?.groupValues?.get(2)?.toIntOrNull() ?: 89
        val octets = android.util.Base64.decode(s, android.util.Base64.DEFAULT).reversedArray()
        val sb = StringBuilder()
        for (i in octets.indices) {
            val k = (base + i * pas + h + bc) and 0xFF
            sb.append(((octets[i].toInt() and 0xFF) xor k).toChar())
        }
        sb.toString().trim().takeIf { it.startsWith("http") && !it.contains("/troll/") }
    } catch (_: Throwable) { null }

    override suspend fun extract(link: String): Video {
        val uri = Uri.parse(link)
        val host = "${uri.scheme}://${uri.host}"
        val refFs = refererFrenchStream()
        val origineFs = refFs.removeSuffix("/")

        // 2026-07-31 (user, FSVid) : un fichier SUPPRIMÉ renvoie 403 sur /embed-xxx.html
        //   alors que le site (fsvid.lol/) répond normalement — vérifié en direct : ce n'est
        //   ni un blocage d'IP ni une limitation, juste le contenu qui n'existe plus.
        //   On lève alors une erreur classée « dead-content » (marqueur reconnu par
        //   Extractor.classifyError) → l'EXTRACTEUR n'est pas pénalisé, donc FSVid reste
        //   disponible pour les autres titres. Sans ça il était blacklisté ~10 min après
        //   quelques fichiers morts (constaté : « 9 failures in window »).
        // 2026-07-31 (user, « serveur FS rouge ») : fsvid.lol est derrière Cloudflare et sert
        //   une page DÉGRADÉE (source = leurre « /troll/ », d'où la pub jouée à la place du
        //   film) quand le client n'a pas la signature TLS de Chrome. Mesuré sur la MÊME URL,
        //   mêmes cookies, même Referer : OkHttp → script de 14 488 car. (leurre) ; fetch
        //   Chrome → 23 040 (leurre aussi) ; iframe Chrome → page complète, vrai flux
        //   `…/hls2/01/00029/<id>_o/master.m3u8`. Il faut donc CUMULER le TLS Chrome (Cronet)
        //   et les en-têtes d'une navigation d'iframe. Repli sur OkHttp si Cronet indisponible.
        val enTetesNavigation = mapOf(
            "User-Agent" to DEFAULT_USER_AGENT,
            "Referer" to refFs,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Upgrade-Insecure-Requests" to "1",
        )
        val viaCronet = try {
            com.streamflixreborn.streamflix.utils.CronetPost.get(
                com.streamflixreborn.streamflix.StreamFlixApp.instance,
                link,
                enTetesNavigation,
            )
        } catch (_: Throwable) {
            null
        }
        if (viaCronet != null && viaCronet.first in 400..499) {
            Log.d("FsvidExtractor", "page embed refusée (${viaCronet.first}) → contenu supprimé")
            throw Exception("FSVid: video not found — file removed or expired")
        }
        val htmlCronet = viaCronet?.takeIf { it.first in 200..299 }?.second
            ?.takeIf { it.isNotBlank() && !it.contains("/troll/") }
        if (htmlCronet != null) Log.d("FsvidExtractor", "page complète obtenue via Cronet (TLS Chrome)")

        val html = htmlCronet ?: try {
            Log.d("FsvidExtractor", "repli OkHttp (Cronet indisponible ou page leurre)")
            service.get(link, refFs, secSite = "cross-site")
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("403") || msg.contains("404") || msg.contains("410")) {
                Log.d("FsvidExtractor", "page embed refusée (${msg.take(40)}) → contenu supprimé")
                throw Exception("FSVid: video not found — file removed or expired")
            }
            throw e
        }
        if (html.isBlank() || html.contains("File was deleted", true) ||
            html.contains("no longer available", true) || html.contains("File Not Found", true)
        ) {
            throw Exception("FSVid: video not found — file removed or expired")
        }

        val scriptData = html
            .substringAfter("eval(function(p,a,c,k,e,d)")
            .substringBefore("</script>")
            .let { "eval(function(p,a,c,k,e,d)$it" }

        if (!scriptData.startsWith("eval")) throw Exception("Packed JS not found")
        val unpacked = JsUnpacker(scriptData).unpack() ?: throw Exception("Unpack failed")

        // 2026-07-31 (user : « le serveur joue une PUB à la place de la vidéo », FSVid) :
        //   l'ancien ordre prenait le TOUT PREMIER `src:` du script — or sur ces lecteurs le
        //   premier `src:`/`file:` est très souvent le PRÉ-ROLL PUBLICITAIRE (VAST), le vrai
        //   flux venant après. Le motif `.m3u8`, lui, n'était essayé qu'en DERNIER recours.
        //   → On cherche maintenant D'ABORD un vrai flux (.m3u8/.mp4), et on écarte
        //     explicitement les URLs de régie pub et les leurres anti-scraping (« /troll/ »).
        fun estPub(u: String): Boolean {
            val s = u.lowercase()
            return s.contains("/troll/") ||
                s.contains("doubleclick") || s.contains("googlesyndication") ||
                s.contains("imasdk") || s.contains("googleads") ||
                s.contains("/vast") || s.contains("vast.xml") ||
                s.contains("/ads/") || s.contains("advert") || s.contains("preroll")
        }
        // 2026-07-31 (user, « serveur FS rouge ») : le diagnostic a montré `m3u8=true` alors
        //   qu'AUCUNE regex ne trouvait rien. Raison : le script déballé est ÉCHAPPÉ — les URLs
        //   y sont écrites `https:\/\/host\/…\/index.m3u8` (et parfois `/`), or les motifs
        //   exigent `https://` avec de VRAIS slashes → zéro correspondance malgré une URL bien
        //   présente. On dés-échappe donc AVANT de chercher (slashes + unicode + guillemets).
        val js = unpacked
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\\"", "\"")
            .replace("\\'", "'")

        fun premierValide(vararg rx: Regex): String? = rx.firstNotNullOfOrNull { r ->
            r.findAll(js)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .firstOrNull { it.startsWith("http", true) && !estPub(it) }
        }

        // ── FSVid chiffre la vraie URL (2026-07-31) ────────────────────────────────────
        //   Vérifié en direct sur la page qui joue RÉELLEMENT la vidéo : `_fsvHls` vaut
        //   TOUJOURS le leurre `https://s1.fsvid.lol/troll/master.m3u8` — c'est lui que l'app
        //   jouait, d'où la « pub à la place du film ». La vraie URL n'est JAMAIS écrite en
        //   clair : elle est stockée en base64 et chiffrée par XOR avec une clé de 8 octets
        //   présente dans le script sous forme de tableau décimal. Aucun appel réseau
        //   supplémentaire n'est fait par le lecteur — tout est dans la page.
        //   Recette validée : base64 → XOR(clé[i % 8]) → URL (déchiffrement confirmé exact,
        //   148 octets, redonnant `https://…/hls2/01/00029/<id>_o/master.m3u8?t=…`).
        //   On essaie chaque paire (tableau × charge) et on ne retient que le résultat qui
        //   EST une URL de flux → auto-validant, donc résistant aux petits remaniements.
        fun dechiffrerUrl(): String? {
            val cles = Regex("""\[\s*(\d{1,3}(?:\s*,\s*\d{1,3}){5,31})\s*]""")
                .findAll(js)
                .map { m -> m.groupValues[1].split(",").map { it.trim().toInt() } }
                .filter { bytes -> bytes.all { it in 0..255 } }
                .toList()
            if (cles.isEmpty()) return null
            val charges = Regex("""["']([A-Za-z0-9+/=]{60,})["']""")
                .findAll(js).map { it.groupValues[1] }.toList()

            for (charge in charges) {
                val brut = try {
                    android.util.Base64.decode(charge, android.util.Base64.DEFAULT)
                } catch (_: Throwable) {
                    continue
                }
                for (cle in cles) {
                    val clair = String(
                        ByteArray(brut.size) { i ->
                            (brut[i].toInt() xor cle[i % cle.size]).toByte()
                        },
                        Charsets.ISO_8859_1,
                    )
                    if (clair.startsWith("http") && clair.contains(".m3u8") &&
                        !clair.contains("/troll/")
                    ) {
                        Log.d("FsvidExtractor", "URL déchiffrée (XOR ${cle.size} octets)")
                        return clair.trim()
                    }
                }
            }
            return null
        }

        // 2026-10-19 : nouveau format d'abord (fonction de déchiffrement + charge base64).
        val nouveau = (Regex("""sources:\s*\[\s*\{\s*src\s*:\s*\((function\(s\)\{.*?\})\)\s*\(\s*["']([^"']+)["']""").find(js)
            ?: Regex("""\((function\(s\)\{.*?\})\)\s*\(\s*["']([A-Za-z0-9+/=]+)["']""").find(js))
            ?.let { dechiffrerNouveauFormat(it.groupValues[1], it.groupValues[2], link) }
        (nouveau ?: dechiffrerUrl())?.let { urlClaire ->
            Log.d("FsvidExtractor", "flux FSVid trouvé (${if (nouveau != null) "nouveau format" else "XOR 8 octets"})")
            return Video(
                source = urlClaire,
                headers = mapOf(
                    "User-Agent" to DEFAULT_USER_AGENT,
                    "Referer" to refFs,
                    "Origin" to origineFs,
                ),
            )
        }

        val m3u8 = premierValide(
            // 1) un vrai flux d'abord (peu importe la clé qui le porte)
            Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)"""),
            Regex("""(https?://[^\s"']+\.mp4[^\s"']*)"""),
            // 2) sinon les clés habituelles, en ignorant les URLs publicitaires
            Regex("""sources\s*:\s*\[\s*["']([^"']+)["']"""),
            Regex("""file\s*:\s*["']([^"']+)["']"""),
            Regex("""src\s*:\s*["']([^"']+)["']"""),
        )
        // 2026-07-31 (user : « je n'ai plus la pub mais ça ne marche plus ») : le filtre anti-pub
        //   pouvait ne RIEN laisser passer → « Stream URL not found », donc plus de lecture du
        //   tout. On élargit alors la recherche à TOUTE URL du JS (hors régie pub), puis en
        //   dernier recours on reprend l'ancien comportement — mieux vaut la pub que rien.
            ?: Regex("""["'](https?://[^"'\s]{20,})["']""").findAll(js)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .firstOrNull { !estPub(it) }
                ?.also { Log.d("FsvidExtractor", "flux trouvé via recherche élargie") }
            // filet ultime : une URL .m3u8 même NON entourée de guillemets (concaténée/nue)
            ?: Regex("""https?://[^\s"'\\)]+\.m3u8[^\s"'\\)]*""").find(js)?.value
                ?.takeIf { !estPub(it) }
                ?.also { Log.d("FsvidExtractor", "flux .m3u8 trouvé en clair après dés-échappement") }
            ?: Regex("""(?:src|file)\s*:\s*["']([^"']+)["']""").find(js)?.groupValues?.get(1)
                // ne JAMAIS reprendre le leurre : c'est lui qui jouait une pub à la place du film
                ?.takeIf { !it.contains("/troll/") }
                ?.also { Log.w("FsvidExtractor", "aucune URL non-pub trouvée → repli sur la 1re source") }
            ?: run {
                // 2026-07-31 (user, « serveur FS rouge ») : DIAGNOSTIC. L'app reçoit bien la
                //   page et déballe le script, mais aucune URL n'en sort. On journalise la
                //   STRUCTURE (longueur, clés portant une valeur, présence de marqueurs) pour
                //   savoir si c'est le filtre anti-pub qui écarte tout, ou si le format a changé.
                val cles = Regex("""(\w+)\s*:\s*["']""").findAll(js)
                    .map { it.groupValues[1] }.distinct().take(15).joinToString(",")
                val nbUrls = Regex("""https?://""").findAll(js).count()
                Log.w(
                    "FsvidExtractor",
                    "AUCUNE source — len=${js.length}, urls=$nbUrls, clés=[$cles], " +
                        "m3u8=${js.contains(".m3u8")}, mp4=${js.contains(".mp4")}",
                )
                // si « .m3u8 » est présent sans être capturé, montrer son CONTEXTE exact :
                //   c'est lui qui dira sous quelle forme l'URL est écrite (concaténation, etc.)
                if (js.contains("/troll/")) {
                    Log.w(
                        "FsvidExtractor",
                        "page LEURRE reçue (source /troll/ uniquement) → les en-têtes iframe " +
                            "n'ont pas suffi, le site a dû durcir sa détection",
                    )
                }
                val i = js.indexOf(".m3u8")
                if (i >= 0) {
                    Log.w(
                        "FsvidExtractor",
                        "contexte m3u8: ${js.substring(maxOf(0, i - 160), minOf(js.length, i + 40))
                            .replace("\n", " ")}",
                    )
                } else {
                    Log.w("FsvidExtractor", "extrait JS: ${js.take(220).replace("\n", " ")}")
                }
                throw Exception("Stream URL not found in unpacked JS")
            }

        return Video(
            source = m3u8,
            headers = mapOf(
                "User-Agent" to DEFAULT_USER_AGENT,
                "Referer" to refFs,
                "Origin" to origineFs,
            )
        )
    }
}
