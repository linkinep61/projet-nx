package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.streamflixreborn.streamflix.utils.UserPreferences
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.net.URL

/**
 * Smoothpre — embed host utilisé par DessinAnime.
 *
 * URL pattern : https://smoothpre.com/embed/<slug>
 *
 * Le HTML contient un script packed `eval(function(p,a,c,k,e,d)...)` qui une
 * fois unpacké révèle une config JWPlayer classique :
 *   var XXX = {"hls2":"https://CDN/.../playlist.m3u8?t=TOKEN", ...};
 *   ...sources:[{file:XXX.hls2, type:"hls", ...}]
 *   ...tracks:[{file:".../thumbnails.vtt", kind:"thumbnails"}]
 *
 * Pattern identique à VidHide → on réutilise la même logique : JsUnpacker
 * puis regex sur `["'](hls\d*|file|src|source)["']\s*:\s*["']([^"']+)["']` en
 * priorisant hls2 > hls4 > hls3 > hls > file.
 *
 * 2026-05-16 : ajouté pour DessinAnime (smoothpre.com référencé dans
 * supportedHosts du provider, mais aucun extracteur dédié → fallback au
 * StreamWishExtractor alias était fragile car StreamWish utilise
 * `resolveRedirectWithWebView` non nécessaire ici).
 */
class SmoothpreExtractor : Extractor() {
    override val name = "Smoothpre"
    override val mainUrl = "https://smoothpre.com"

    companion object {
        private const val TAG = "SmoothpreExtractor"
    }

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract() link=$link")
        val mainLink = URL(link).protocol + "://" + URL(link).host
        val fallback = mainLink
        val referer = try {
            UserPreferences.currentProvider?.baseUrl ?: fallback
        } catch (_: Throwable) {
            fallback
        }
        val origin = referer
        Log.d(TAG, "referer=$referer origin=$origin")

        val service = Extractor.createJsoupService<Service>(mainLink, referer)
        val document = service.getSource(
            url = link,
            referer = referer,
            origin = origin,
            userAgent = Extractor.DEFAULT_USER_AGENT,
        )
        val html = document.toString()
        Log.d(TAG, "HTML length=${html.length}")

        val packedJS = Regex("(eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?)</script>")
            .find(html)
            ?.let { it.groupValues[1] }
            ?: run {
                Log.e(TAG, "Packed JS not found! preview=${html.take(400)}")
                throw Exception("Smoothpre: packed JS not found")
            }
        Log.d(TAG, "Packed JS found, length=${packedJS.length}")

        val unPacked = JsUnpacker(packedJS).unpack()
            ?: throw Exception("Smoothpre: JsUnpacker returned null")
        Log.d(TAG, "Unpacked JS length=${unPacked.length}")

        // Collecte tous les couples clé→URL plausibles (hls2/hls4/hls/file/src).
        val links = mutableMapOf<String, String>()
        Regex("""["'](hls\d*|file|src|source)["']\s*:\s*["']([^"']+)["']""")
            .findAll(unPacked)
            .forEach { links[it.groupValues[1]] = it.groupValues[2] }
        Log.d(TAG, "Found ${links.size} link candidates: keys=${links.keys}")

        // Priorité : hls2 > hls4 > hls3 > hls > file > src > source.
        val streamUrl = links["hls2"] ?: links["hls4"] ?: links["hls3"]
            ?: links["hls"] ?: links["file"] ?: links["src"] ?: links["source"]
            ?: throw Exception("Smoothpre: no playable URL found in unpacked JS")

        val absoluteUrl = if (streamUrl.startsWith("/")) mainLink + streamUrl else streamUrl
        Log.d(TAG, "Final URL: $absoluteUrl")

        // Sous-titres : tracks:[{file:"...vtt", label:"...", kind:"captions"}]
        val subtitles = mutableListOf<Video.Subtitle>()
        val tracksBlock = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(unPacked)?.groupValues?.get(1).orEmpty()
        if (tracksBlock.isNotEmpty()) {
            Regex("""file\s*:\s*["']([^"']+)["'](?:[^}]*?label\s*:\s*["']([^"']*)["'])?[^}]*?kind\s*:\s*["']([^"']+)["']""")
                .findAll(tracksBlock)
                .forEach { m ->
                    val file = m.groupValues[1]
                    val label = m.groupValues[2].ifEmpty { "Sous-titres" }
                    val kind = m.groupValues[3]
                    if (kind.equals("captions", true) || kind.equals("subtitles", true)) {
                        subtitles.add(Video.Subtitle(label = label, file = file))
                    }
                }
        }
        Log.d(TAG, "Subtitles found: ${subtitles.size}")

        return Video(source = absoluteUrl, subtitles = subtitles)
    }

    private interface Service {
        @GET
        suspend fun getSource(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("Origin") origin: String,
            @Header("User-Agent") userAgent: String = Extractor.DEFAULT_USER_AGENT,
        ): Document
    }
}
