package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import androidx.media3.common.MimeTypes
import com.google.gson.JsonParser
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.Locale
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RpmvidExtractor : Extractor() {
    override val name = "Rpmvid"

    /**
     * 2026-08-05 — PAS DE MISE EN CACHE. Ne pas retirer.
     *
     * Ce player sert des manifestes SIGNÉS ET DATÉS, sur des hôtes tiers :
     *   `…/v4/il/1hrz5g/cf-master.1767614617.txt?t=<jeton>&e=<expiration>`
     * Le lien est donc périssable par construction. Or l'extracteur héritait du cache par
     * défaut — dix minutes — et rejouait un résultat périmé :
     *   `[EXTRACTOR] -> Cache HIT for https://doremifasol.ezplayer.me/#bdwzb`
     *   `DIAG-403 code=404 uri=…/cf-master.1776655035.txt` (sans jeton) → lecture impossible
     * Un lien valide relevé le même jour portait bien `?t=…&e=…` et répondait, lui.
     *
     * ⚠ Même piège que Vidzy, LuluVdo, DoodStream et EmbedSeek, tous corrigés de la même
     *   façon : quand un extracteur produit une URL à durée de vie limitée, le cache n'apporte
     *   rien et transforme un succès en échec dès que le jeton expire.
     */
    override val cacheTtlMs: Long = 0L

    override val mainUrl = "https://rpmvid.com"
    override val aliasUrls = listOf("https://cubeembed.rpmvid.com", "https://bummi.upns.xyz", "https://loadm.cam", "https://anibum.playerp2p.online", "https://pelisplus.upns.pro", "https://pelisplus.rpmstream.live", "https://pelisplus.strp2p.com", "https://flemmix.upns.pro", "https://moflix.rpmplay.xyz", "https://moflix.upns.xyz", "https://flix2day.xyz", "https://primevid.click",
        "https://totocoutouno.rpmlive.online", "https://dismoiceline.uns.bio", "https://doremifasol.ezplayer.me", "https://marcus.p2pstream.vip","https://animeav1.uns.bio",
        "https://serix.upns.live",
        "https://coflix.upn.one",
        "https://flemmix.farm", "https://flemmix.rpmlive.online", "https://flemmix.upns.xyz", "https://flemmix.rpmstream.live", "https://flemmix.strp2p.com", "https://flemmix.ezplayer.me", "https://flemmix.uns.bio", "https://flemmix.upns.live", "https://flemmix.upn.one", "https://flemmix.p2pstream.vip", "https://flemmix.prof")

    override val rotatingDomain = listOf(
        Regex("flemmix\\.[a-z]+(?:\\.[a-z]+)?/embed"),
        Regex("flemmix\\.[a-z]+(?:\\.[a-z]+)?/e/"),
    )

    companion object {
        private val KEY = "kiemtienmua911ca".toByteArray()
        private val IV = "1234567890oiuytr".toByteArray()
    }

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Query("id") id: String,
            @Query("w") w: String,
            @Query("h") h: String,
            @Query("r") r: String = "",
        ): String
    }

    override suspend fun extract(link: String): Video {
        val id = extractId(link) ?: throw Exception("Invalid link: missing id after #")
        val url = URL(link)
        val mainLink = "${url.protocol}://${url.host}"
        val service = Extractor.createGsonService<Service>(mainLink)
        val apiUrl = "$mainLink/api/v1/video"

        val hexResponse = service.get(
            url = apiUrl,
            referer = mainLink,
            id = id,
            w = "1920",
            h = "1080",
        )

        val decryptedJson = decryptHexPayloadSafe(hexResponse)
        val json = JsonParser.parseString(decryptedJson).asJsonObject
        val hlsPath = json.get("hls")?.asString?.takeIf { it.isNotEmpty() }
        val hlsTiktok = json.get("hlsVideoTiktok")?.asString?.takeIf { it.isNotEmpty() }
        var cfPath = json.get("cf")?.asString?.takeIf { it.isNotEmpty() }
        val cfExpire = json.get("cfExpire")?.asString?.takeIf { it.isNotEmpty() }

        // 2026-08-01 (user : « le serveur Rpmvid ne marche pas, plusieurs tests ») : le CDN
        //   `/v4/<x>/<y>/cf-master.<ts>.txt` renvoie **403** — y compris via Cronet, donc ce
        //   n'est PAS le fingerprint TLS. Vérifié en direct sur `flemmix.upns.pro` : ce
        //   manifeste se charge SANS token, mais UNIQUEMENT depuis le domaine du lecteur —
        //   depuis une origine tierce il est refusé. C'est un contrôle d'ORIGINE.
        //   Or on n'envoyait que `Referer`, jamais `Origin` : le CDN nous voyait donc comme
        //   un tiers → 403. On ajoute `Origin` (+ les `Sec-Fetch-*` d'une requête de lecteur,
        //   même recette que celle qui a débloqué Vidzy).
        //   Ce CDN est PARTAGÉ : le même 403 frappait aussi 1Jour1Film/Movix
        //   (astroliteonline.online) et Coflix Boston (viatrix.space) → correctif commun.
        val enTetesLecteur = mapOf(
            "Referer" to "$mainLink/",
            "Origin" to mainLink,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
        )
        val (finalUrl, headers) = when {
            !hlsPath.isNullOrEmpty() -> {
                toAbsoluteUrl(mainLink, hlsPath) to enTetesLecteur
            }
            !hlsTiktok.isNullOrEmpty() -> {
                val v = extractTiktokV(json)
                val query = if (!v.isNullOrEmpty()) "?v=$v" else ""
                toAbsoluteUrl(mainLink, "$hlsTiktok$query") to enTetesLecteur
            }
            !cfPath.isNullOrEmpty() -> {
                cfPath = buildCloudFlareUrl(cfPath, cfExpire, json)
                val urlCf = toAbsoluteUrl(mainLink, cfPath ?: "")
                // ── 2026-08-05 — MANIFESTE NON SIGNÉ → ON LAISSE LA PAGE LE SIGNER ────────
                //   Constaté sur « The Yeti » (flemmix.upns.pro), même film et même minute :
                //     · Rpmvid  → `…/v4/us/ocqvf6/cf-master.1775818354.txt`  SANS paramètre → 403
                //     · EmbedSeek → `…/v4/vz1/hz6vf/cf-master.1782521317.txt?k=…&kx=…` → lit
                //   La sonde a montré pourquoi : `adjust` ne contient plus que `[Tiktok, Google]`,
                //   le bloc `Cloudflare` (d'où sortaient les paramètres de signature) a disparu
                //   de la configuration. `buildCloudFlareUrl` n'a donc plus rien à poser.
                //
                //   Or ces deux hôtes tournent sur LE MÊME lecteur (vidstack + `ima3.js` + les
                //   mêmes chaînes « Adblock Detected » / « /cf-master. » dans le bundle). Plutôt
                //   que de rejouer une signature obfusquée en Kotlin — table de chaînes indexée,
                //   donc fragile et à refaire à chaque rotation — on demande l'URL au lecteur
                //   lui-même, par le chemin déjà éprouvé sur EmbedSeek.
                //
                //   ⚠ Ce repli ne se déclenche QUE si l'URL n'a aucun paramètre. Les hôtes dont
                //     la configuration porte encore le jeton gardent la voie native (~1,7 s).
                if (!urlCf.contains("?")) {
                    val signee = try {
                        OnlyFlixResolver.resolveMasterM3u8(link)
                    } catch (e: Exception) {
                        android.util.Log.w("RpmvidExtractor", "repli headless KO: ${e.message}")
                        null
                    }
                    if (!signee.isNullOrBlank()) {
                        android.util.Log.d("RpmvidExtractor", "manifeste sans jeton → URL signée obtenue via le lecteur")
                        val origine = try {
                            val u = java.net.URL(link); "${u.protocol}://${u.host}"
                        } catch (_: Exception) { mainLink }
                        signee to mapOf(
                            "User-Agent" to com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_UA,
                            "Referer" to "$origine/",
                            "Origin" to origine,
                        )
                    } else {
                        // ⚠⚠ 2026-08-06 — NE JAMAIS RENVOYER UN MANIFESTE NON SIGNÉ.
                        //   Symptôme user sur `totocoutouno.rpmlive.online` : « il dure 12
                        //   secondes et se coupe complètement ». CloudFront sert le manifeste
                        //   depuis son cache quelques secondes, puis refuse les segments faute
                        //   de signature — la lecture meurt en pleine scène.
                        //   Un échec franc vaut mieux : le lecteur bascule aussitôt sur un
                        //   serveur qui marche, au lieu de faire croire que ça démarre.
                        //   (Le repli headless avait bien tourné ici, mais il a été ANNULÉ par
                        //   un changement de serveur avant d'aboutir — d'où l'URL nue.)
                        android.util.Log.w(
                            "RpmvidExtractor",
                            "manifeste sans signature et repli infructueux → serveur déclaré mort"
                        )
                        throw Exception("Rpmvid: manifeste non signé (lecture impossible)")
                    }
                } else urlCf to enTetesLecteur
            }
            else -> throw Exception("Missing hls, hlsVideoTiktok or cf in response")
        }

        val defaultSub = json.getAsJsonObject("defaultSubtitle")
                                ?.get("defaultSubtitle")?.asString.orEmpty()
        val subtitles = json.getAsJsonObject("subtitle")
            ?.entrySet()
            ?.map { (label, file) ->
                Video.Subtitle(
                    label = label,
                    file = file.asString.orEmpty(),
                    default = defaultSub.isNotEmpty() && label.equals(defaultSub, ignoreCase = true)
                )
            }.orEmpty()

        // 2026-08-01 (user : « Movix Rpmvid VF est en réalité en VOSTFR ») : cet hébergeur
        //   expose le NOM DE FICHIER réel — vérifié en direct, le lecteur l'affiche en titre :
        //     serix.upns.live/#k8rxrg → « In.the.Grey.2026.VOSTFR.1080p.WEBRip.x264.mp4 »
        //   alors que Movix annonçait ce lien en « (VF) ». Le nom de fichier, lui, ne ment
        //   pas : on le remonte pour corriger l'étiquette de langue du serveur.
        val nomFichier = sequenceOf("title", "name", "filename", "fileName", "file_name")
            .mapNotNull { k -> runCatching { json.get(k)?.asString }.getOrNull() }
            .firstOrNull { it.isNotBlank() }
        if (nomFichier != null) {
            android.util.Log.d("RpmvidExtractor", "nom de fichier réel = $nomFichier")
        }

        return Video(
            source = finalUrl,
            subtitles,
            headers = headers,
            type = MimeTypes.APPLICATION_M3U8,
            fileName = nomFichier,
        )
    }

    private fun extractId(link: String): String? {
        // Format 1: https://domain/embed#VIDEO_ID
        val idx = link.indexOf('#')
        if (idx != -1 && idx != link.lastIndex) {
            return link.substring(idx + 1).substringBefore("&")
        }
        // Format 2: https://domain/e/VIDEO_ID or https://domain/embed/VIDEO_ID
        val pathMatch = Regex("/(?:e|embed)/([a-zA-Z0-9_-]+)").find(link)
        if (pathMatch != null) {
            return pathMatch.groupValues[1]
        }
        // Format 3: ?id=VIDEO_ID
        val queryMatch = Regex("[?&]id=([a-zA-Z0-9_-]+)").find(link)
        if (queryMatch != null) {
            return queryMatch.groupValues[1]
        }
        return null
    }

    private fun decryptHexPayloadSafe(hex: String): String {
        val cleaned = hex.trim()
        if (cleaned.isEmpty()) throw Exception("Empty encrypted payload")
        return try {
            val bytes = hexToBytes(cleaned)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
            val decrypted = cipher.doFinal(bytes)
            decrypted.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw Exception("Failed to decrypt payload: ${e.message}")
        }
    }

    private fun hexToBytes(input: String): ByteArray {
        val cleaned = input.lowercase(Locale.US).replace(Regex("[^0-9a-f]"), "")
        require(cleaned.length >= 2) { "Invalid hex payload" }
        val even = if (cleaned.length % 2 == 0) cleaned else "0$cleaned"
        val out = ByteArray(even.length / 2)
        var i = 0
        var j = 0
        while (i < even.length) {
            out[j++] = ((even[i].digitToInt(16) shl 4) or even[i + 1].digitToInt(16)).toByte()
            i += 2
        }
        return out
    }

    private fun toAbsoluteUrl(origin: String, path: String): String {
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            if (path.startsWith("/")) "$origin$path" else "$origin/$path"
        }
    }

    private fun extractTiktokV(json: com.google.gson.JsonObject): String? = try {
        val configStr = json.get("streamingConfig")?.asString ?: return null
        val config = JsonParser.parseString(configStr).asJsonObject
        config.getAsJsonObject("adjust")
            ?.getAsJsonObject("Tiktok")
            ?.getAsJsonObject("params")
            ?.get("v")?.asString
    } catch (_: Exception) {
        null
    }

    private fun buildCloudFlareUrl(cfPath: String, cfExpire: String?, json: com.google.gson.JsonObject): String {
        var t: String? = null
        var e: String? = null
        try {
            val configStr = json.get("streamingConfig")?.asString
            if (configStr != null) {
                val streamingConfig = JsonParser.parseString(configStr).asJsonObject
                val cloudflare = streamingConfig
                    .getAsJsonObject("adjust")
                    ?.getAsJsonObject("Cloudflare")
                val disabled = cloudflare
                    ?.get("disabled")
                    ?.takeIf { !it.isJsonNull }
                    ?.asBoolean ?: true
                if (!disabled) {
                    val params = cloudflare.getAsJsonObject("params")
                    t = params?.get("t")?.takeIf { !it.isJsonNull }?.asString
                    e = params?.get("e")?.takeIf { !it.isJsonNull }?.asString
                }
            }
        } catch (_: Exception) { }

        // Sonde du 5 août retirée : elle a répondu. Sur `flemmix.upns.pro`, la configuration
        //   déchiffrée ne contient plus que `adjust = [Tiktok, Google]` — le bloc `Cloudflare`,
        //   d'où venaient `t` et `e`, a disparu. D'où un manifeste sans signature, refusé par le
        //   CDN. Le repli est en place plus haut, dans la branche `cf` d'`extract()`.
        return when {
            !e.isNullOrEmpty() && !t.isNullOrEmpty() -> "$cfPath?t=$t&e=$e"
            !cfExpire.isNullOrEmpty() -> {
                val parts = cfExpire.split("::")
                if (parts.size >= 2) "$cfPath?t=${parts[0]}&e=${parts[1]}" else cfPath
            }
            else -> cfPath
        }
    }
}
