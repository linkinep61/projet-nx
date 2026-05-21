package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video

/**
 * Hydrax / Abyss (abysscdn.com / abyssa.cc) -- player WebView dedie.
 *
 * v93 2026-05-20 (user "reconstruire un Player exactement pour cet extracteur") :
 *   Abyss est volontairement inextractible : sa source est un MP4 sur
 *   storage.googleapis.com servi UNIQUEMENT a la requete de son propre player
 *   (le bucket renvoie "billing account disabled / UserProjectAccountProblem"
 *   a toute requete externe -- ExoPlayer ou navigation directe). Impossible
 *   donc de jouer l'URL hors du player abyss.
 *
 *   => On joue abyss DANS une WebView dediee (le seul contexte autorise), mais
 *   BLINDEE cote PlayerMobileFragment.showWebViewOverlay (path isAbyssEmbed) :
 *     - navigation hors hosts abyss/player bloquee (tue les redirects pub
 *       type aliexpress/decafeligiblyhad qu'abyss declenche au 1er clic),
 *     - window.open (popups) bloque,
 *     - hosts de pub/tracker bloques.
 *   Resultat : la video joue, zero page de pub.
 *
 *   On renvoie donc une Video qui declenche cet overlay (pas d'extraction URL).
 *   URL embed : https://abysscdn.com/?v=<slug>
 */
open class HydraxExtractor : Extractor() {
    override val name = "Hydrax"
    override val mainUrl = "https://abysscdn.com"
    override val aliasUrls = listOf(
        "https://abyss.to",
        "https://hls.abyssa.cc",
        "https://abyssa.cc",
        "https://abyssplayer.com",
        "https://hydrax.net",
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
