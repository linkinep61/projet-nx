package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.WebViewStreamResolver

/**
 * 2026-11-24 (banc Zootopie 2 / The Last of Us, 1Jour1Film « fr.splayer.site » : « No extractors
 *   found ») — SPlayer : `https://fr.splayer.site/movie/<tmdb>` ou `/tv/<tmdb>/<saison>/<épisode>`.
 *
 * Vu dans Chrome : la page lance la lecture TOUTE SEULE (hls.js) et demande un master.m3u8 sur
 *   `free.finepulfe.xyz` (VF + piste audio française) — le même serveur que « Frembed Premium »,
 *   qui se lit déjà sans réglage particulier. Son API (`/api/v1/movie/<id>?source=…`) refuse
 *   (403 « Forbidden ») tout appel qui ne vient pas de son script, et ce script est volontairement
 *   brouillé : plutôt que de le décortiquer, on charge la page dans un navigateur intégré
 *   invisible et on capte le flux qu'elle demande elle-même (même mécanisme que Vidsrc / Vidy).
 */
class SplayerExtractor : Extractor() {

    override val name = "SPlayer"
    override val mainUrl = "https://fr.splayer.site"
    override val aliasUrls = listOf("https://splayer.site")

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract $link")
        val capte = WebViewStreamResolver.resolve(
            entryUrl = link,
            referer = "$mainUrl/",
            timeoutMs = 22_000L,
            attach = true,
            // Chrome la lance seule, mais la WebView exige un GESTE pour démarrer une vidéo :
            //   toucher au centre + clic « lecture ».
            toucherCentre = true,
            clickPlay = true,
        ) ?: throw Exception("SPlayer : flux non capté (titre inconnu ou lecteur indisponible)")
        Log.d(TAG, "flux capté : ${capte.url.substringBefore('?').take(90)}")
        val entetes = LinkedHashMap<String, String>()
        capte.headers.forEach { (k, v) ->
            if (k.equals("User-Agent", true) || k.equals("Referer", true) || k.equals("Origin", true)) entetes[k] = v
        }
        entetes.putIfAbsent("Referer", "$mainUrl/")
        entetes.putIfAbsent("Origin", mainUrl)
        return Video(source = capte.url, headers = entetes)
    }

    private companion object { const val TAG = "SplayerExtractor" }
}
