package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * TokyVideo (tokyvideo.com) — serveur « Tokyvideo · serveur 1 » de Movix.
 *
 * 2026-09-15 — Relevé sur l'Oppo, sur DEUX films d'affilée :
 *     W/Extractor: HOTE NON COUVERT: www.tokyvideo.com (aucun extracteur)
 *        — https://www.tokyvideo.com/fr/video/vaiana-la-legende-du-bout-du-monde-2026-2
 *     W/Extractor: HOTE NON COUVERT: www.tokyvideo.com (aucun extracteur)
 *        — https://www.tokyvideo.com/fr/video/the-last-sunrise-2026
 *   Movix propose ce serveur systématiquement ; il échouait systématiquement.
 *
 * RECETTE : le .mp4 est EN CLAIR dans le HTML de la page, il n'y a strictement
 * rien à déchiffrer. La balise <video> porte l'identifiant et le CDN suit :
 *     <video id="tokyvideo_player" … data-video-duration="7025" …>
 *     https://cdnst30.tokyvideo.com/videos/912/912074/mp4/<sha256>.mp4
 * (durée relevée 7025 s = 117 min → c'est bien le film entier, pas une bande-annonce.)
 *
 * ⚠ Le CDN (CDN77) filtre par PAYS : depuis un PoP hors zone il répond 403 sur le .mp4
 *   alors que la page HTML, elle, se charge normalement. Vérifié le jour même — un
 *   `x-77-pop: ashburnUSVA` donne 403. Ce n'est donc PAS une signature à reproduire :
 *   inutile d'inventer un token, il n'y en a pas. On pose quand même Referer/Origin,
 *   qui ne coûtent rien et couvrent le cas d'un anti-hotlink ajouté plus tard.
 */
class TokyvideoExtractor : Extractor() {

    override val name = "TokyVideo"
    override val mainUrl = "https://www.tokyvideo.com"

    override val aliasUrls = listOf(
        "https://tokyvideo.com",
    )

    companion object {
        private const val TAG = "TokyvideoExtractor"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        /** Le .mp4 du CDN TokyVideo. Le sous-domaine `cdnst<NN>` change selon le shard. */
        private val MP4 = Regex(
            """https://cdn[a-z0-9]*\.tokyvideo\.com/[^"'\s\\<>]+\.mp4[^"'\s\\<>]*""",
            RegexOption.IGNORE_CASE
        )
    }

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract() link=$link")

        val client = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .followRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        val html = withContext(Dispatchers.IO) {
            val requete = Request.Builder()
                .url(link)
                .header("User-Agent", UA)
                .header("Referer", "$mainUrl/")
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .build()
            client.newCall(requete).execute().use { reponse ->
                if (!reponse.isSuccessful) {
                    throw Exception("TokyVideo HTTP ${reponse.code} sur $link")
                }
                reponse.body?.string().orEmpty()
            }
        }
        Log.d(TAG, "HTML length=${html.length}")

        val source = MP4.find(html)?.value
            ?: throw Exception("TokyVideo: aucun .mp4 dans la page (${html.length} o)")
        Log.d(TAG, "source=$source")

        return Video(
            source = source,
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "User-Agent" to UA,
            ),
        )
    }
}
