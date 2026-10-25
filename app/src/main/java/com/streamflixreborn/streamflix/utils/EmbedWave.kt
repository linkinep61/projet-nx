package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video

/**
 * 2026-10-25 (user : « autant rajouter la source, si ça permet de compléter des trucs où il n'y a
 * rien ») — EmbedWave (embedwave.cc), lecteur par identifiant TMDB dont le vrai lecteur est
 * web.nxsha.app :
 *   web.nxsha.app/embed/movie/{tmdb}  ·  web.nxsha.app/embed/tv/{tmdb}/{saison}/{épisode}
 *
 * Vérifié au navigateur : WWE Raw S34E01 (absent de toutes nos autres sources) se lance seul,
 * 1 h 45, 720p, audio anglais. La page démarre la lecture d'elle-même ; la playlist vient d'un
 * CDN signé (…/hls_mps/…/index_N.m3u8?Expires=…&Signature=…, morceaux déguisés en .jpg).
 * Son API (/api/sources) est chiffrée et le titre de la page vient de TMDB (« S99:E1 » s'affiche
 * aussi pour une saison inexistante) → pas de contrôle léger possible avant d'afficher le serveur.
 * Pour ne pas faire attendre quand il n'a rien : abandon court (12 s sans flux → serveur rouge).
 * Sous-titres : ceux de l'appli (OpenSubtitles français, sinon traduction), comme Vidnest.
 */
object EmbedWave {
    private const val TAG = "EmbedWave"
    const val BASE = "https://web.nxsha.app"

    fun lien(tmdb: String, estUnFilm: Boolean, saison: Int, episode: Int): String =
        if (estUnFilm) "$BASE/embed/movie/$tmdb" else "$BASE/embed/tv/$tmdb/${saison.coerceAtLeast(1)}/$episode"

    suspend fun video(server: Video.Server): Video {
        val r = WebViewStreamResolver.resolve(
            entryUrl = server.src.substringBefore('#'),
            referer = "https://embedwave.cc/",
            timeoutMs = 15_000L,
            attach = true,
            clickPlay = true,
            // Vérifié au navigateur (Raw S1E1) : sur certains épisodes la page ne démarre pas seule,
            //   elle attend un vrai clic sur le gros bouton play AU CENTRE (le clic JS ne suffit pas).
            toucherCentre = true,
            // La page a une route « Télécharger » (/dl/…) : un clic ne doit jamais l'ouvrir.
            cheminsBloques = listOf("/dl/"),
            // Relevé sur l'Oppo (Raw S1E1) : leur 1er serveur (itsnitrox) renvoie une erreur réseau
            //   même dans leur propre page, qui passe alors au suivant → on attend le suivant.
            hotesIgnores = listOf("itsnitrox"),
        ) ?: throw Exception("EmbedWave : aucun flux capté (épisode absent ou lecteur indisponible)")
        Log.i(TAG, "flux capté : ${r.url.substringBefore("/hls_mps/")}/hls_mps/…")
        return Video(
            source = r.url,
            type = MimeTypes.APPLICATION_M3U8,
            headers = r.headers.ifEmpty { null },
        )
    }
}
