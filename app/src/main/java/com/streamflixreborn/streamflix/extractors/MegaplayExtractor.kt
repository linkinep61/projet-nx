package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * MegaPlay (kisskh.megaplay.su) — lecteur des dramas asiatiques servis par Dramacool.
 *
 * 2026-09-15 — Relevé EN DIRECT sur l'Oppo du user (logcat, PID 20976) :
 *     D/PlayerViewModel: Inizio estrazione video dal server: Fast Server (DC)
 *     D/Extractor: extract() called with link=https://kisskh.megaplay.su/kisskh/211446?autoplay=true
 *     W/Extractor: HOTE NON COUVERT: kisskh.megaplay.su (aucun extracteur)
 *     E/PlayerViewModel: No extractors found for URL: https://kisskh.megaplay.su/kisskh/211446
 *   Autrement dit le serveur « Fast Server (DC) » de VoirDrama/Dramacool remontait bien
 *   dans la liste, mais mourait systématiquement à l'extraction. Aucun drama Dramacool
 *   n'était lisible.
 *
 * RECETTE (aucune obfuscation, aucun chiffrement) :
 *   La page d'embed contient le manifeste EN CLAIR dans une balise JSON :
 *       <script id="player-payload" type="application/json">
 *         {"source":"https://kisskh.megaplay.su/vid/<blob>/<blob>/<id>.m3u8",
 *          "tracks":[{"file":"…/sub/…/xxx.en.srt","label":"English","kind":"captions",
 *                     "default":true}, …],
 *          "autoplay":true,"playbackRates":[…],"color":"#cccccc"}
 *       </script>
 *   Le JS d'à côté ne fait que passer ce blob à jwplayer().setup() — il n'y a donc rien
 *   à dépaqueter ni à déchiffrer, on lit le JSON et c'est fini.
 *
 * ⚠ DEUX PIÈGES vérifiés au curl, ne pas les retirer :
 *   1. Le CDN renvoie **403** sur le .m3u8 sans `Referer: https://kisskh.megaplay.su/`.
 *      D'où les headers posés sur le Video retourné — le lecteur les rejoue sur le
 *      manifeste ET sur les segments.
 *   2. Le JS de la page fait `if (window.top === window.self) → écran "blocked"`. C'est
 *      purement côté navigateur : le payload est DÉJÀ dans le HTML servi. Inutile de
 *      simuler un iframe, un simple GET suffit.
 *
 * Bonus : ce lecteur expose 6 pistes de sous-titres (en/ar/id/ms/km/nl) que l'on
 * récupère gratuitement, là où les autres serveurs de VoirDrama n'en donnent aucune.
 */
class MegaplayExtractor : Extractor() {

    override val name = "MegaPlay"
    override val mainUrl = "https://kisskh.megaplay.su"

    override val aliasUrls = listOf(
        "https://megaplay.su",
        // 2026-09-15 (audit complet des hôtes Dramacool) : megavid.buzz sert exactement le
        //   même lecteur, avec les MÊMES 6 pistes de sous-titres, sur la même forme d'URL
        //   `/kisskh/<id>`. Seule différence : il n'inline pas `source`, il renvoie
        //   `sourceUrl` → cf. la variante gérée plus bas.
        "https://megavid.buzz",
    )

    companion object {
        private const val TAG = "MegaplayExtractor"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        /** Le bloc JSON du lecteur. `id="player-payload"` est le marqueur stable. */
        private val PAYLOAD = Regex(
            """<script[^>]*id=["']player-payload["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
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

        /** GET simple, en se faisant passer pour l'iframe du lecteur. */
        suspend fun recupere(url: String, referer: String): String = withContext(Dispatchers.IO) {
            val requete = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .header("Accept", "text/html,application/json,*/*;q=0.8")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(requete).execute().use { reponse ->
                if (!reponse.isSuccessful) {
                    throw Exception("MegaPlay HTTP ${reponse.code} sur $url")
                }
                reponse.body?.string().orEmpty()
            }
        }

        val html = recupere(link, "$origine/")
        Log.d(TAG, "HTML length=${html.length}")

        val brut = PAYLOAD.find(html)?.groupValues?.get(1)?.trim()
            ?: throw Exception("MegaPlay: balise player-payload absente (page=${html.length} o)")

        var json = JSONObject(brut)

        // Le lecteur remplit `error`/`code` quand l'épisode n'est plus servi.
        json.optString("error").takeIf { it.isNotBlank() }?.let {
            throw Exception("MegaPlay a refusé: $it (code=${json.optString("code", "?")})")
        }

        // ── VARIANTE `sourceUrl` (megavid.buzz, 2026-09-15) ────────────────────────────
        //   megaplay.su met le m3u8 directement dans `source`. megavid.buzz, lui, ne
        //   renvoie que `{"sourceUrl":"/kisskh/<id>/source", …}` : il faut appeler cet
        //   endpoint, qui répond `{"status":"ok","source":"…m3u8","tracks":[…]}`.
        //   On la gère ici pour les deux hôtes — si megaplay.su bascule un jour sur ce
        //   schéma (c'est visiblement la même équipe), rien ne cassera.
        json.optString("sourceUrl").takeIf { it.isNotBlank() }?.let { chemin ->
            val absolu = if (chemin.startsWith("http")) chemin else origine + chemin
            Log.d(TAG, "variante sourceUrl -> $absolu")
            val corps = recupere(absolu, link)
            val secondaire = JSONObject(corps)
            secondaire.optString("error").takeIf { it.isNotBlank() }?.let { err ->
                throw Exception("MegaPlay (sourceUrl) a refusé: $err")
            }
            json = secondaire
        }

        val source = json.optString("source").takeIf { it.isNotBlank() }
            ?: throw Exception("MegaPlay: champ `source` vide")
        Log.d(TAG, "source=$source")

        val sousTitres = mutableListOf<Video.Subtitle>()
        json.optJSONArray("tracks")?.let { pistes ->
            for (i in 0 until pistes.length()) {
                val piste = pistes.optJSONObject(i) ?: continue
                // `kind` vaut "captions" pour les sous-titres, "thumbnails" pour la
                // frise de prévisualisation — on ne veut que les premiers.
                if (!piste.optString("kind").equals("captions", true)) continue
                val fichier = piste.optString("file").takeIf { it.isNotBlank() } ?: continue
                sousTitres.add(
                    Video.Subtitle(
                        label = piste.optString("label").ifBlank { "Sous-titres" },
                        file = fichier,
                        default = piste.optBoolean("default", false),
                    )
                )
            }
        }
        Log.d(TAG, "sous-titres=${sousTitres.size} -> ${sousTitres.map { it.label }}")

        return Video(
            source = source,
            subtitles = sousTitres,
            headers = mapOf(
                "Referer" to "$origine/",
                "Origin" to origine,
                "User-Agent" to UA,
            ),
        )
    }
}
