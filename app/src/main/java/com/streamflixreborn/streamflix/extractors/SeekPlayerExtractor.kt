package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video

/**
 * 2026-07-09 — Player "SeekStreaming" de Movix (seekplayer.vip / seekplayer.me).
 *
 * Le site a tourné son domaine (`seekplays.pro` → `seekplayer.vip/.me`) ET son chiffrement :
 * `/api/v1/info?id=<id>` renvoie une config chiffrée AES-CBC déchiffrée CÔTÉ CLIENT. Le VRAI
 * flux (manifeste HLS sur un CDN tournant type novacrestventures.space, servi en `.txt`) n'est
 * révélé QUE pendant une lecture réelle (P2P/WebRTC + hls.js, via un player vidstack qui fait du
 * lazy-load et un leurre `preload.m3u8`). Une WebView headless ne reproduit pas cette lecture →
 * extraction pure impossible de façon fiable.
 *
 * Comme Hydrax/Abyss, on JOUE donc seekplayer DANS une WebView dédiée (le seul contexte où le
 * flux se révèle) : on renvoie une Video qui déclenche l'overlay WebView du player (path
 * `isSeekPlayer` dans PlayerMobileFragment/PlayerTvFragment.showWebViewOverlay) — navigation
 * hors seekplayer/CDN bloquée, pubs coupées, autoplay + plein écran. Zéro extraction d'URL.
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
        return Video(
            source = link,
            webViewUrl = link,
            needsWebViewClick = true,
        )
    }
}
