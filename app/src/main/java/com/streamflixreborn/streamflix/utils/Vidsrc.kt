package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video

/**
 * 2026-10-22 (user : « moviesfree.cv… on essaie de l'ajouter pour voir si on matche du contenu
 * qu'on n'avait pas, gros catalogue, ça nous ferait du VOSTFR traduit en plus ») — lecteur
 * VIDSRC (celui de moviesfree.cv), par identifiant TMDB :
 *   vidsrc.sh/embed/movie/{tmdb}  ·  vidsrc.sh/embed/tv/{tmdb}/{saison}/{épisode}
 *
 * Vérifié au navigateur (Fight Club, Game of Thrones) : audio anglais ; la page fabrique
 * l'adresse de sa playlist par un script chiffré (WASM), mais la playlist elle-même
 * (nectarandnectary…/pl/H4sI…) se lit SANS en-tête particulier, et ses morceaux
 * (« page-N.html ») sont du flux TS ordinaire. Donc : une WebView invisible lance leur lecteur
 * (un toucher au centre, le bouton play est dans un cadre d'un autre site), on capte la
 * playlist, et c'est notre lecteur qui joue. Sous-titres : ceux de l'appli (OpenSubtitles
 * français recalé, sinon traduction).
 */
object Vidsrc {
    private const val TAG = "Vidsrc"
    const val BASE = "https://vidsrc.sh"

    fun lien(tmdb: String, estUnFilm: Boolean, saison: Int, episode: Int): String =
        if (estUnFilm) "$BASE/embed/movie/$tmdb" else "$BASE/embed/tv/$tmdb/${saison.coerceAtLeast(1)}/$episode"

    suspend fun video(server: Video.Server): Video {
        val r = WebViewStreamResolver.resolve(
            entryUrl = server.src.substringBefore('#'),
            referer = "https://moviesfree.cv/",
            timeoutMs = 25_000L,
            attach = true,
            toucherCentre = true,
            motifsFlux = listOf("/pl/H4sI"),
            // 2026-10-22 (user : « il arrive vite mais parfois il met du temps à se lancer, comme
            //   s'il arrivait même s'il n'avait pas de contenu ») — Vidsrc est proposé sans savoir si
            //   l'épisode existe chez lui (ex. The Iceblade Sorcerer S02E02, diffusé mais absent). Vérifié
            //   au navigateur : la page prend le titre de l'œuvre (« … · S01 E01 ») quand elle l'a, et
            //   garde « Player » quand elle ne l'a pas. → abandon au bout de 12 s au lieu de 25.
            abandonSiTitre = Regex("""(?i)(player|joueur|lecteur)?"""),
            delaiAbandonMs = 12_000L,
        ) ?: throw Exception("Vidsrc : flux non capté (titre inconnu ou lecteur indisponible)")
        Log.i(TAG, "flux capté : ${r.url.substringBefore("/pl/")}/pl/…")
        return Video(
            source = r.url,
            type = MimeTypes.APPLICATION_M3U8,
            headers = mapOfNotNull("User-Agent" to r.headers["User-Agent"]),
        )
    }

    private fun mapOfNotNull(vararg p: Pair<String, String?>): Map<String, String> =
        p.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
}
