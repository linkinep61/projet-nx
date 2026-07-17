package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.WebViewResolver
import java.net.URL

/**
 * 2026-07-09 / 2026-07-16 — Player "SeekStreaming" de Movix (seekplayer.vip / seekplayer.me).
 *
 * `/api/v1/info?id=<id>` renvoie une config chiffrée AES-CBC déchiffrée CÔTÉ CLIENT → l'URL d'un
 * **master.m3u8 proxifié** (`…/hlsmod/<tiktokcdn>/…/tt/master.m3u8`) dont les segments sont du
 * MPEG-TS caché dans des images PNG sur le CDN TikTok (cf. OnlyFlixResolver + SeekStreamPngDataSource).
 *
 * 2026-07-16 (user « fais un truc comme SwiftFlow, pas du WebView ») : on EXTRAIT désormais le
 * master.m3u8 via une WebView HEADLESS (OnlyFlixResolver laisse le JS déchiffrer, on capte l'URL
 * SANS lecture visible) et on le joue en NATIF dans ExoPlayer (le DataSource strippe les en-têtes
 * PNG des segments). Si l'extraction échoue (timeout, page KO), FALLBACK sur l'ancien overlay
 * WebView (`needsWebViewClick` → path `isSeekPlayer` des fragments), qui reste fonctionnel.
 */
class SeekPlayerExtractor : Extractor() {

    override val name = "SeekStreaming"
    override val mainUrl = "https://seekplayer.vip"
    override val rotatingDomain: List<Regex> = listOf(
        Regex("""[a-z0-9]+\.seekplayer\.(vip|me)"""),
        Regex("""seekplayer\.(vip|me)"""),
    )

    override val cacheTtlMs: Long = 0L

    override suspend fun extract(link: String): Video {
        val master = try { OnlyFlixResolver.resolveMasterM3u8(link) } catch (e: Exception) {
            Log.w("SeekPlayerExtractor", "headless resolve error: ${e.message}"); null
        }
        if (master != null) {
            val origin = try { val u = URL(link); "${u.protocol}://${u.host}/" } catch (_: Exception) { "$mainUrl/" }
            Log.d("SeekPlayerExtractor", "resolved master.m3u8 → native ExoPlayer")
            return Video(
                source = master,
                type = MimeTypes.APPLICATION_M3U8,
                headers = mapOf(
                    "User-Agent" to WebViewResolver.STEALTH_UA,
                    "Referer" to origin,
                    "Origin" to origin.trimEnd('/'),
                ),
            )
        }
        // Fallback : ancien overlay WebView (toujours câblé dans les fragments).
        Log.d("SeekPlayerExtractor", "headless resolve failed → WebView overlay fallback")
        return Video(source = link, webViewUrl = link, needsWebViewClick = true)
    }
}
