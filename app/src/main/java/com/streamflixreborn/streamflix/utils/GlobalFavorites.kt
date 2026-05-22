package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-05-20 (user "un cœur centré à la place de Fournisseur qui montre les
 *   favoris de TOUS les providers sauf TV") : agrège les favoris (films + séries)
 *   de chaque base provider non-IPTV. Chaque provider a sa propre DB
 *   (`{profile}_{provider}.db`) ; on lit getFavorites() de chacune et on retient
 *   le provider d'origine de chaque item pour pouvoir rouvrir la fiche dans le
 *   bon provider.
 */
object GlobalFavorites {
    private const val TAG = "GlobalFavorites"

    /** id de l'item -> nom du provider d'origine. Rempli à chaque [load]. */
    val originByItemId = ConcurrentHashMap<String, String>()

    suspend fun load(context: Context): Pair<List<Movie>, List<TvShow>> =
        withContext(Dispatchers.IO) {
            originByItemId.clear()
            val movies = mutableListOf<Movie>()
            val tvShows = mutableListOf<TvShow>()
            val providers = Provider.providers.keys.filter { it !is IptvProvider }
            for (p in providers) {
                if (!AppDatabase.providerDbExists(p.name, context)) continue
                try {
                    val db = AppDatabase.getInstanceForProvider(p.name, context)
                    try {
                        val favMovies = db.movieDao().getFavorites().first()
                        val favTv = db.tvShowDao().getFavorites().first()
                        favMovies.forEach { originByItemId[it.id] = p.name }
                        favTv.forEach { originByItemId[it.id] = p.name }
                        movies += favMovies
                        tvShows += favTv
                    } finally {
                        try { db.close() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "fav read ${p.name}: ${e.message}")
                }
            }
            Log.d(TAG, "loaded ${movies.size} films + ${tvShows.size} séries favoris")
            Pair(
                movies.sortedByDescending { it.favoritedAtMillis ?: 0L },
                tvShows.sortedByDescending { it.favoritedAtMillis ?: 0L },
            )
        }

    /** Avant d'ouvrir un favori : bascule sur son provider d'origine pour que la
     *  fiche détail lise la bonne DB / utilise le bon provider. */
    fun switchToOrigin(itemId: String) {
        val origin = originByItemId[itemId] ?: return
        if (UserPreferences.currentProvider?.name == origin) return
        Provider.findByName(origin)?.let { UserPreferences.currentProvider = it }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Reprises de lecture cross-provider
    // ══════════════════════════════════════════════════════════════════

    /** Films en cours de lecture (position > 0, pas fini) de TOUS les providers. */
    suspend fun loadContinueWatchingMovies(context: Context, limit: Int = 20): List<Movie> =
        withContext(Dispatchers.IO) {
            val all = mutableListOf<Movie>()
            val providers = Provider.providers.keys.filter { it !is IptvProvider }
            for (p in providers) {
                if (!AppDatabase.providerDbExists(p.name, context)) continue
                try {
                    val db = AppDatabase.getInstanceForProvider(p.name, context)
                    try {
                        val movies = db.movieDao().getWatchingMovies().first()
                        movies.forEach { m ->
                            val wh = m.watchHistory
                            // Garder seulement les films avec une position > 5s et pas "finis"
                            // (fini = position > 90% de la durée)
                            if (wh != null && (wh.lastPlaybackPositionMillis ?: 0) > 5_000) {
                                val duration = wh.durationMillis ?: 0
                                val position = wh.lastPlaybackPositionMillis ?: 0
                                val pct = if (duration > 0) position.toDouble() / duration else 0.0
                                if (pct < 0.90) {
                                    originByItemId["resume_movie_${m.id}"] = p.name
                                    all += m
                                }
                            }
                        }
                    } finally {
                        try { db.close() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "continueWatching movies ${p.name}: ${e.message}")
                }
            }
            all.filter { !isContinueWatchingDismissed("resume_movie_${it.id}") }
                .sortedByDescending { it.watchHistory?.lastEngagementTimeUtcMillis ?: 0 }
                .take(limit)
        }

    /** Épisodes en cours de lecture → groupés par série (1 carte = 1 série,
     *  le dernier épisode regardé). Clic → ouvre la saison dans le provider. */
    suspend fun loadContinueWatchingSeries(context: Context, limit: Int = 20): List<ContinueWatchingSeriesItem> =
        withContext(Dispatchers.IO) {
            // tvShowId → (providerName, dernierÉpisode)
            val seriesMap = LinkedHashMap<String, Pair<String, Episode>>()
            val providers = Provider.providers.keys.filter { it !is IptvProvider }
            for (p in providers) {
                if (!AppDatabase.providerDbExists(p.name, context)) continue
                try {
                    val db = AppDatabase.getInstanceForProvider(p.name, context)
                    try {
                        val episodes = db.episodeDao().getWatchingEpisodes().first()
                        for (ep in episodes) {
                            val tvId = ep.tvShow?.id ?: continue
                            val wh = ep.watchHistory ?: continue
                            if ((wh.lastPlaybackPositionMillis ?: 0) <= 5_000) continue
                            val existing = seriesMap[tvId]
                            val existingTs = existing?.second?.watchHistory?.lastEngagementTimeUtcMillis ?: 0
                            val thisTs = wh.lastEngagementTimeUtcMillis ?: 0
                            if (existing == null || thisTs > existingTs) {
                                seriesMap[tvId] = Pair(p.name, ep)
                            }
                        }
                    } finally {
                        try { db.close() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "continueWatching series ${p.name}: ${e.message}")
                }
            }
            seriesMap.values
                .filter { !isContinueWatchingDismissed("resume_series_${it.second.tvShow?.id}") }
                .sortedByDescending { it.second.watchHistory?.lastEngagementTimeUtcMillis ?: 0 }
                .take(limit)
                .map { (providerName, ep) ->
                    originByItemId["resume_series_${ep.tvShow?.id}"] = providerName
                    ContinueWatchingSeriesItem(
                        providerName = providerName,
                        tvShow = ep.tvShow!!,
                        lastEpisode = ep,
                        seasonNumber = ep.season?.number ?: 1,
                        episodeNumber = ep.number,
                    )
                }
        }

    /** Représente une série en cours de lecture pour l'écran cœur. */
    data class ContinueWatchingSeriesItem(
        val providerName: String,
        val tvShow: TvShow,
        val lastEpisode: Episode,
        val seasonNumber: Int,
        val episodeNumber: Int,
    )

    /** Retire un favori directement depuis l'écran cœur : remet isFavorite=false
     *  dans la base du provider D'ORIGINE (pas le provider courant). */
    suspend fun removeFavorite(context: Context, itemId: String, isMovie: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val origin = originByItemId[itemId] ?: return@withContext false
            try {
                val db = AppDatabase.getInstanceForProvider(origin, context)
                try {
                    if (isMovie) db.movieDao().setFavoriteWithLog(itemId, false)
                    else db.tvShowDao().setFavoriteWithLog(itemId, false)
                    originByItemId.remove(itemId)
                    Log.d(TAG, "removed favorite $itemId from $origin (movie=$isMovie)")
                    true
                } finally {
                    try { db.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "removeFavorite $origin/$itemId: ${e.message}")
                false
            }
        }

    // ══════════════════════════════════════════════════════════════════
    //  Masquage des reprises de lecture (2026-05-22)
    // ══════════════════════════════════════════════════════════════════

    private const val PREFS_DISMISSED = "continue_watching_dismissed"

    private fun dismissedPrefs() =
        StreamFlixApp.instance.applicationContext
            .getSharedPreferences(PREFS_DISMISSED, Context.MODE_PRIVATE)

    private fun dismissedKey(): String = "dismissed_${ProfileManager.currentProfileIdOrDefault()}"

    /** Ajoute un id au set masqué (reprise de lecture cachée du cœur). */
    fun dismissContinueWatching(resumeId: String) {
        val prefs = dismissedPrefs()
        val set = prefs.getStringSet(dismissedKey(), emptySet())!!.toMutableSet()
        set.add(resumeId)
        prefs.edit().putStringSet(dismissedKey(), set).apply()
        Log.d(TAG, "dismissed continue watching: $resumeId")
    }

    /** Vérifie si un id de reprise est masqué. */
    fun isContinueWatchingDismissed(resumeId: String): Boolean {
        return dismissedPrefs().getStringSet(dismissedKey(), emptySet())!!.contains(resumeId)
    }
}
