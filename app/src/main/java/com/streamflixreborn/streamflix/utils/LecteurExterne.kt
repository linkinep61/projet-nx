package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.services.ServiceLecteurExterne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 2026-10-03 (signalement Nvidia Shield, VLC / Nova) — lecteur externe, partagé TV + mobile :
 *
 *  1. LIEN : les flux HLS passent par le relais interne (CastProxy en mode local). Il retire
 *     le faux en-tête image des segments déguisés (VLC : « Failed to create demuxer », mesuré
 *     sur un film Coflix) et ajoute lui-même les en-têtes d'accès que VLC ignore. Un service
 *     de premier plan garde l'app en vie pendant la lecture (sinon le relais meurt avec elle).
 *     Les liens directs (MP4…) partent tels quels, comme avant.
 *  2. RETOUR : VLC (extra_position / extra_duration) et MX Player (position / duration /
 *     end_by) renvoient où la lecture s'est arrêtée. Avant, l'app ne lisait jamais cette
 *     réponse → aucun épisode « vu », aucune reprise. On enregistre maintenant la reprise,
 *     ou « vu » au-delà de 90 %.
 */
object LecteurExterne {

    private const val TAG = "LecteurExterne"

    /** Lien à confier au lecteur externe. */
    fun preparerLien(ctx: Context, source: String, headers: Map<String, String>?, type: String?): String {
        if (!source.startsWith("http")) {
            Log.i(TAG, "lien non http, envoyé tel quel"); return source
        }
        val estHls = type?.contains("mpegurl", ignoreCase = true) == true ||
            source.substringBefore("?").endsWith(".m3u8", ignoreCase = true) ||
            source.contains(".m3u8", ignoreCase = true)
        if (!estHls) {
            Log.i(TAG, "lien direct (pas HLS), envoyé tel quel"); return source
        }
        // Ne pas couper un Cast EN COURS (le relais est le même). Seule une session réellement
        //   connectée compte : une demande de Cast restée « en attente » ne doit pas bloquer.
        val castActif = try {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(ctx)
                .sessionManager.currentCastSession?.isConnected == true
        } catch (_: Throwable) { false }
        if (castActif) {
            Log.i(TAG, "Cast connecté : lien direct pour ne pas couper le Cast"); return source
        }
        val relais = try { CastProxy.startLocal(source, headers) } catch (e: Throwable) {
            Log.w(TAG, "relais indisponible (${e.message}) — lien direct"); null
        } ?: return source
        ServiceLecteurExterne.demarrer(ctx)
        Log.i(TAG, "lecteur externe via le relais : $relais ← ${source.take(100)}")
        return relais
    }

    data class Retour(val positionMs: Long, val dureeMs: Long, val fini: Boolean)

    /** Lit la réponse du lecteur externe (null si le lecteur ne renvoie rien). */
    @Suppress("DEPRECATION")
    fun lireRetour(data: Intent?): Retour? {
        val ex = data?.extras ?: return null
        fun nombre(vararg cles: String): Long? {
            for (k in cles) {
                when (val v = ex.get(k)) {
                    is Long -> return v
                    is Int -> return v.toLong()
                    is String -> v.toLongOrNull()?.let { return it }
                }
            }
            return null
        }
        val position = nombre("extra_position", "position") ?: return null
        val duree = nombre("extra_duration", "duration") ?: 0L
        val finMx = data.getStringExtra("end_by") == "playback_completion"
        val fini = finMx || (duree > 0 && position >= duree * 9 / 10)
        return Retour(position, duree, fini)
    }

    /** Fin de la lecture externe : arrête le service (et le relais avec lui). */
    fun fin(ctx: Context) = ServiceLecteurExterne.arreter(ctx)

    /** Enregistre la reprise, ou « vu » si la lecture est allée au bout (≥ 90 %). */
    suspend fun enregistrer(ctx: Context, database: AppDatabase, videoType: Video.Type, r: Retour) =
        withContext(Dispatchers.IO) {
            if (!r.fini && r.positionMs < 5_000) return@withContext  // juste ouvert puis refermé
            val provider = UserPreferences.currentProvider ?: return@withContext
            try {
                when (videoType) {
                    is Video.Type.Movie -> {
                        val film = database.movieDao().getById(videoType.id) ?: return@withContext
                        marquer(film, r)
                        database.movieDao().update(film)
                        if (r.fini) UserDataCache.removeMovieFromContinueWatching(ctx, provider, film.id)
                        else UserDataCache.addMovieToContinueWatching(ctx, provider, film)
                    }
                    is Video.Type.Episode -> {
                        val ep = database.episodeDao().getById(videoType.id) ?: return@withContext
                        marquer(ep, r)
                        if (r.fini) database.episodeDao().resetProgressionFromEpisode(videoType.id)
                        database.episodeDao().update(ep)
                        if (r.fini) UserDataCache.removeEpisodeFromContinueWatching(ctx, provider, ep.id)
                        else UserDataCache.addEpisodeToContinueWatching(ctx, provider, ep)
                        ep.tvShow?.let { database.tvShowDao().getById(it.id) }?.let { serie ->
                            val enCours = if (r.fini) database.episodeDao().hasAnyWatchHistoryForTvShow(serie.id) else true
                            database.tvShowDao().save(serie.copy().apply {
                                merge(serie)
                                isWatching = enCours
                            })
                        }
                    }
                }
                Log.i(TAG, "retour lecteur externe enregistré : ${videoType.javaClass.simpleName} " +
                    "${r.positionMs / 1000}s / ${r.dureeMs / 1000}s, vu=${r.fini}")
            } catch (e: Exception) {
                Log.w(TAG, "enregistrement du retour KO : ${e.message}")
            }
        }

    private fun marquer(item: WatchItem, r: Retour) {
        if (r.fini) {
            item.isWatched = true
            item.watchedDate = Calendar.getInstance()
            item.watchHistory = null
        } else {
            item.isWatched = false
            item.watchedDate = null
            item.watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                lastPlaybackPositionMillis = r.positionMs,
                durationMillis = r.dureeMs,
            )
        }
    }
}
