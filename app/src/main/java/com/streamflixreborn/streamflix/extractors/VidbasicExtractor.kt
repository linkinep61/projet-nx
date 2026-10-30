package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TraductionSousTitres
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Vidbasic (vidbasic.top) — le SECOND lecteur servi par Dramacool.
 *
 * 2026-09-15 : en sondant dramacool9.com.ro on tombe sur deux hôtes selon le titre —
 *   `kisskh.megaplay.su` (cf. [MegaplayExtractor]) et `vidbasic.top`. Les deux étaient
 *   des « HOTE NON COUVERT », donc aucun épisode Dramacool n'était lisible.
 *
 * Vidbasic n'héberge RIEN lui-même : c'est un hub. Sa page d'embed ne montre qu'un seul
 * bouton, mais son HTML liste tous les miroirs en clair :
 *     <ul class="list-server-items">
 *       <li class="linkserver" data-provider="streamwish" data-video="https://hglink.to/e/…">
 *       <li class="linkserver" data-provider="vidhide"    data-video="https://minochinos.com/v/…">
 *       <li class="linkserver" data-provider="mixdrop"    data-video="https://mixdrop.ps/e/…">
 *       <li class="linkserver" data-provider="upnshare"   data-video="https://…upns.pro/#…">
 *       …
 *     </ul>
 * On récupère donc la liste et on DÉLÈGUE au premier miroir qu'un de nos extracteurs
 * sait traiter (même schéma que [KakaflixExtractor]).
 *
 * Relevé au curl le 2026-09-15 sur un épisode réel :
 *   - vidhide / minochinos.com  → page packed jwplayer + m3u8   → OK
 *   - mixdrop  / mixdrop.ps     → page MDCore                    → OK (alias .ps ajouté)
 *   - streamwish / hglink.to    → OK
 *   - upnshare / …upns.pro      → OK
 *   - streamtape / watchadsontape.com → **404**, mort côté hôte
 *   - « Standard Server » (/3rdplayer.html?key=…) → interne vidbasic, ignoré : c'est le
 *     même flux que les autres, derrière une clé signée qui expire.
 *
 * L'ORDRE compte : on tente d'abord les hôtes les plus fiables sur cette source.
 */
class VidbasicExtractor : Extractor() {

    private fun dechiffrer(b64: String): String? = runCatching {
        val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(CLE.toByteArray(Charsets.UTF_8), "AES"),
            javax.crypto.spec.IvParameterSpec(IV.toByteArray(Charsets.UTF_8)),
        )
        String(c.doFinal(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)), Charsets.UTF_8).trim()
    }.getOrNull()

    private suspend fun lecteurInterne(html: String, link: String, origine: String, client: OkHttpClient): Video? {
        val chemin = Regex("""data-video=["'](/3rdplayer\.html\?[^"']+)["']""").find(html)
            ?.groupValues?.get(1)?.replace("&amp;", "&") ?: return null
        val url = origine + chemin
        val page = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", UA).header("Referer", link).build()
            ).execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else "" }
        }
        val chiffre = Regex("""data-name=["']crypto["'][^>]*data-value=["']([^"']+)["']""").find(page)?.groupValues?.get(1)
            ?: Regex("""data-value=["']([^"']+)["'][^>]*data-name=["']crypto["']""").find(page)?.groupValues?.get(1)
            ?: run { Log.w(TAG, "lecteur interne : données chiffrées absentes (${page.length} o)"); return null }
        val flux = dechiffrer(chiffre)?.takeIf { it.startsWith("http") }
            ?: run { Log.w(TAG, "lecteur interne : déchiffrement KO (clé changée ?)"); return null }
        val sousTitres = Regex("""[?&]sub=([^&]+)""").find(chemin)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.let { dechiffrer(it) }?.takeIf { it.startsWith("http") }
            ?.let { sousTitresDechiffres(it, origine, client) }?.let { listOf(it) }.orEmpty()
        Log.d(TAG, "lecteur interne OK (${if (flux.contains(".m3u8")) "hls" else "mp4"}), sous-titres=${sousTitres.size}")
        return Video(
            source = flux,
            subtitles = sousTitres,
            type = if (flux.contains(".m3u8")) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
            headers = mapOf("Referer" to "$origine/", "Origin" to origine, "User-Agent" to UA),
        )
    }

    /**
     * 2026-10-30 (user : sous-titres en charabia sur le lecteur interne) — le VTT servi par
     * kk.vidbasic.top garde ses timings en clair mais CHIFFRE chaque réplique (base64 puis
     * AES/CBC/PKCS5Padding, exactement la clé/IV du flux — vérifié dans le navigateur) :
     * "vcIk4z/9oA1nKf7uEPDuVg==" → "=Bionic Trace=". On télécharge le VTT, on déchiffre
     * réplique par réplique et on écrit un VTT LOCAL en clair (cache `st_vidbasic`, même
     * ménage 30 jours que `st_traduits`). Renvoie la piste locale, ou null si ça échoue :
     * pas de sous-titre plutôt que du charabia (sinon le lecteur affiche le base64 et la
     * traduction traduit du charabia).
     */
    private suspend fun sousTitresDechiffres(url: String, origine: String, client: OkHttpClient): Video.Subtitle? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dossier = File(StreamFlixApp.instance.applicationContext.cacheDir, "st_vidbasic").apply { mkdirs() }
                TraductionSousTitres.menage(dossier)
                // le jeton de l'URL expire à chaque lecture : la clé de cache ne le garde pas (cf. st_traduits)
                val empreinte = MessageDigest.getInstance("MD5")
                    .digest(url.substringBefore('?').toByteArray()).joinToString("") { "%02x".format(it) }
                val fichier = File(dossier, "$empreinte.vtt")
                if (fichier.exists() && fichier.length() > 20L) {
                    Log.d(TAG, "sous-titres déjà déchiffrés (cache) : ${fichier.path}")
                    return@runCatching Video.Subtitle(label = "Anglais", file = "file://" + fichier.absolutePath)
                }
                val brut = client.newCall(
                    Request.Builder().url(url)
                        .header("User-Agent", UA).header("Referer", "$origine/").header("Origin", origine)
                        .build()
                ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: run {
                    Log.w(TAG, "sous-titres : téléchargement KO ($url)")
                    return@runCatching null
                }
                // Timings, numéros et en-têtes recopiés tels quels ; chaque ligne de texte
                // déchiffrée est remplacée, une ligne qui ne se déchiffre pas est gardée
                // d'origine (le site serait repassé en clair).
                val clair = StringBuilder()
                var repliques = 0; var dechiffrees = 0; var charabia = 0; var premiere: String? = null
                for (ligne in brut.lineSequence()) {
                    val l = ligne.trim()
                    val estStructure = l.isEmpty() || l.contains("-->") || l.startsWith("WEBVTT") ||
                        l.startsWith("Kind:") || l.startsWith("Language:") || l.startsWith("NOTE") ||
                        l.startsWith("Style:") || l.startsWith("Region:") || NUMERO.matches(l)
                    if (estStructure) { clair.appendLine(ligne); continue }
                    repliques++
                    val texte = dechiffrer(l)
                    if (texte != null) {
                        dechiffrees++
                        if (premiere == null) premiere = texte
                        clair.appendLine(texte)
                    } else {
                        if (BASE64.matches(l)) charabia++
                        clair.appendLine(ligne)
                    }
                }
                if (dechiffrees == 0 && charabia > 0) {
                    Log.w(TAG, "sous-titres : $charabia réplique(s) restent chiffrées (clé changée ?) → piste ignorée")
                    return@runCatching null
                }
                fichier.writeText(clair.toString(), Charsets.UTF_8)
                Log.d(
                    TAG, "sous-titres déchiffrés : $dechiffrees/$repliques réplique(s)" +
                        ", 1re=\"${premiere?.take(60)}\", fichier=${fichier.path}"
                )
                Video.Subtitle(label = "Anglais", file = "file://" + fichier.absolutePath)
            }.getOrNull()
        }

    override val name = "Vidbasic"
    override val mainUrl = "https://vidbasic.top"

    companion object {
        private const val TAG = "VidbasicExtractor"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        /** `data-provider="x" … data-video="y"` — l'ordre des deux attributs est stable. */
        private val MIROIR = Regex(
            """data-provider=["']([^"']*)["'][^>]*data-video=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        /** Du plus fiable au moins fiable, mesuré en direct. Le reste passe après. */
        private val PRIORITE = listOf("vidhide", "streamwish", "mixdrop", "upnshare")

        /** Clé / IV du lecteur interne (écrits en clair dans 3rdplayer.html, relevés le 2026-10-22). */
        private const val CLE = "94588293375053432799222445521289"
        private const val IV = "5259228356829423"

        /** 2026-10-30 : identifiant de bloc VTT (numéro seul) et ligne qui ressemble à du base64 chiffré. */
        private val NUMERO = Regex("""\d+""")
        private val BASE64 = Regex("""[A-Za-z0-9+/]{8,}={0,2}""")
    }

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract() link=$link")

        val origine = URL(link).let { "${it.protocol}://${it.host}" }

        val client = OkHttpClient.Builder()
            .connectionPool(com.streamflixreborn.streamflix.utils.NetworkClient.sharedConnectionPool)
            .dns(DnsResolver.doh)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        val html = withContext(Dispatchers.IO) {
            val requete = Request.Builder()
                .url(link)
                .header("User-Agent", UA)
                .header("Referer", "$origine/")
                .build()
            client.newCall(requete).execute().use { reponse ->
                if (!reponse.isSuccessful) {
                    throw Exception("Vidbasic HTTP ${reponse.code} sur $link")
                }
                reponse.body?.string().orEmpty()
            }
        }
        Log.d(TAG, "HTML length=${html.length}")

        val miroirs = MIROIR.findAll(html)
            .map { it.groupValues[1].lowercase().trim() to it.groupValues[2].trim() }
            // On écarte le lecteur interne : `/3rdplayer.html?key=…` est relatif et
            // sa clé expire — les miroirs externes sont bien plus durables.
            .filter { (_, url) -> url.startsWith("http") }
            .toList()

        if (miroirs.isEmpty()) {
            // 2026-10-22 (user : « Standard Server (DC) » rouge) : Vidbasic a retiré ses miroirs
            //   externes, il ne reste que son lecteur interne `/3rdplayer.html?key=…&id=…&sub=…`.
            //   Relevé dans le navigateur : la page porte le flux CHIFFRÉ dans
            //   `<script data-name="crypto" data-value="…">`, déchiffré par CryptoJS en AES-CBC
            //   (clé et IV écrits en clair dans le script, PKCS7). Le sous-titre est le paramètre
            //   `sub`, chiffré pareil. On refait le déchiffrement ici — aucune WebView.
            return lecteurInterne(html, link, origine, client)
                ?: throw Exception("Vidbasic: aucun miroir dans la page (${html.length} o)")
        }
        Log.d(TAG, "miroirs=${miroirs.map { it.first }}")

        val ordonnes = miroirs.sortedBy { (fournisseur, _) ->
            val rang = PRIORITE.indexOfFirst { fournisseur.contains(it) }
            if (rang >= 0) rang else PRIORITE.size
        }

        var derniereErreur: Exception? = null
        for ((fournisseur, url) in ordonnes) {
            try {
                Log.d(TAG, "tentative $fournisseur -> $url")
                return Extractor.extract(url)
            } catch (e: Exception) {
                Log.w(TAG, "miroir $fournisseur KO: ${e.message}")
                derniereErreur = e
            }
        }

        lecteurInterne(html, link, origine, client)?.let { return it }
        throw Exception(
            "Vidbasic: les ${ordonnes.size} miroirs ont échoué " +
                "(${ordonnes.joinToString { it.first }}) — dernier: ${derniereErreur?.message}"
        )
    }
}
