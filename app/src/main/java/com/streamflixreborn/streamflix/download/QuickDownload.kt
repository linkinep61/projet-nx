package com.streamflixreborn.streamflix.download

import android.content.Context
import android.util.Log
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One-shot "download this directly" entry point used by the long-press handler on
 * Episode / Movie cards. Picks the first available server (the provider's own
 * priority ordering, e.g. VoirAnime: vidmoly → voe → stape → moon → …) and queues
 * a download. Tries the next server if the chosen one's extractor fails.
 *
 * Runs on the supplied [scope] (typically the fragment's lifecycleScope) and posts
 * its toasts back on the main thread.
 */
object QuickDownload {

    private const val TAG = "QuickDownload"

    fun downloadEpisode(context: Context, scope: CoroutineScope, episode: Episode) {
        val provider = UserPreferences.currentProvider ?: run {
            toast(context, "Aucun provider sélectionné")
            return
        }
        val tvShow = episode.tvShow ?: run {
            toast(context, "Métadonnées épisode incomplètes")
            return
        }
        val season = episode.season ?: run {
            toast(context, "Métadonnées saison manquantes")
            return
        }
        val videoType = Video.Type.Episode(
            id = episode.id,
            number = episode.number,
            title = episode.title,
            poster = episode.poster ?: tvShow.poster,
            overview = episode.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = tvShow.id,
                title = tvShow.title,
                poster = tvShow.poster,
                banner = tvShow.banner,
                releaseDate = tvShow.released?.toString(),
                imdbId = null,
            ),
            season = Video.Type.Episode.Season(
                number = season.number,
                title = season.title,
            ),
        )
        toast(context, "Recherche d'une source…")
        scope.launch(Dispatchers.IO) {
            try {
                val servers = provider.getServers(episode.id, videoType)
                if (servers.isEmpty()) {
                    toast(context, "Aucune source disponible")
                    return@launch
                }
                resolveAndEnqueue(context, provider, servers, videoType)
            } catch (e: Exception) {
                Log.e(TAG, "downloadEpisode failed", e)
                toast(context, "Erreur : ${e.message ?: "inconnue"}")
            }
        }
    }

    fun downloadMovie(context: Context, scope: CoroutineScope, movie: Movie) {
        val provider = UserPreferences.currentProvider ?: run {
            toast(context, "Aucun provider sélectionné")
            return
        }
        val videoType = Video.Type.Movie(
            id = movie.id,
            title = movie.title,
            releaseDate = movie.released?.toString() ?: "",
            poster = movie.poster ?: "",
            imdbId = movie.imdbId,
        )
        toast(context, "Recherche d'une source…")
        scope.launch(Dispatchers.IO) {
            try {
                val servers = provider.getServers(movie.id, videoType)
                if (servers.isEmpty()) {
                    toast(context, "Aucune source disponible")
                    return@launch
                }
                resolveAndEnqueue(context, provider, servers, videoType)
            } catch (e: Exception) {
                Log.e(TAG, "downloadMovie failed", e)
                toast(context, "Erreur : ${e.message ?: "inconnue"}")
            }
        }
    }

    /** Walk the server list in order, try to resolve a Video URL, and enqueue.
     *  Stops at the first server that yields a non-empty source. */
    private suspend fun resolveAndEnqueue(
        context: Context,
        provider: com.streamflixreborn.streamflix.providers.Provider,
        servers: List<Video.Server>,
        videoType: Video.Type,
    ) {
        for (server in servers) {
            try {
                val video = provider.getVideo(server)
                if (video.source.isBlank()) continue
                DownloadManager.enqueue(
                    video = video,
                    videoType = videoType,
                    providerName = provider.name,
                    serverName = server.name,
                )
                toast(context, "Téléchargement ajouté (${server.name})")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Server ${server.name} failed (${e.message}), trying next…")
            }
        }
        toast(context, "Aucun serveur n'a pu être résolu")
    }

    private fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
