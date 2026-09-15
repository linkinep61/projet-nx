package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
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
    }

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract() link=$link")

        val origine = URL(link).let { "${it.protocol}://${it.host}" }

        val client = OkHttpClient.Builder()
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
            throw Exception("Vidbasic: aucun miroir dans la page (${html.length} o)")
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

        throw Exception(
            "Vidbasic: les ${ordonnes.size} miroirs ont échoué " +
                "(${ordonnes.joinToString { it.first }}) — dernier: ${derniereErreur?.message}"
        )
    }
}
