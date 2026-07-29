package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video

/**
 * 2026-07-29 — Extracteur pour lpayer.embed4me.com (« Lecteur embed4me »), utilisé par anime-sama
 * (Lecteur 2).
 *
 * embed4me est un player **vidstack** avec une API CHIFFRÉE (`/api/v1/info` → hex déchiffré par un
 * JS obfusqué) : impossible d'extraire le m3u8 en headless de façon fiable/rapide. On passe donc en
 * **mode MANUEL** (comme upbolt/Abyss) : on affiche l'embed dans une WebView du player et
 * l'utilisateur clique sur play — le player du site tourne alors normalement. Instantané, pas
 * d'attente ni d'échec d'extraction.
 */
class Embed4meExtractor : Extractor() {
    override val name = "Embed4me"
    override val mainUrl = "https://lpayer.embed4me.com"
    override val aliasUrls = listOf(
        "https://embed4me.com",
        "https://player.embed4me.com",
    )
    override val cacheTtlMs: Long = 0L

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract($link) → mode WebView manuel (clic play)")
        return Video(source = link, webViewUrl = link, needsWebViewClick = true)
    }

    companion object {
        private const val TAG = "Embed4meExtractor"
    }
}
