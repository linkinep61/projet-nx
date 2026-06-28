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
 * Agrège les favoris (films + séries) de chaque provider non-IPTV.
 * Retient le provider d'origine de chaque item pour rouvrir la fiche
 * dans le bon provider.
 */
object GlobalFavorites {
    private const val TAG = "GlobalFavorites"

    /** id de l'item -> nom du provider d'origine. Rempli à chaque [load]. */
    val originByItemId = ConcurrentHashMap<String, String>()

    /** id synthétique "resume_series_..." → données de reprise (pour navigation directe au player). */
    val resumeSeriesData = ConcurrentHashMap<String, ContinueWatchingSeriesItem>()

    suspend fun load(context: Context): Pair<List<Movie>, List<TvShow>> =
        withContext(Dispatchers.IO) {
            originByItemId.clear()
            val movies = mutableListOf<Movie>()
            val tvShows = mutableListOf<TvShow>()
            val providers = Provider.providers.keys.filter { it !is IptvProvider }
            Log.i(TAG, "════ AUDIT FAVORIS — ${providers.size} providers non-IPTV ════")
            for (p in providers) {
                val dbExists = AppDatabase.providerDbExists(p.name, context)
                if (!dbExists) {
                    Log.i(TAG, "  [${p.name}] DB n'existe pas (= jamais utilisé)")
                    continue
                }
                try {
                    val db = AppDatabase.getInstanceForProvider(p.name, context)
                    try {
                        val favMovies = db.movieDao().getFavorites().first()
                        val favTv = db.tvShowDao().getFavorites().first()
                        favMovies.forEach { originByItemId[it.id] = p.name }
                        favTv.forEach { originByItemId[it.id] = p.name }
                        movies += favMovies
                        tvShows += favTv
                        Log.i(TAG, "  [${p.name}] favoris : ${favMovies.size} films + ${favTv.size} séries")
                    } finally {
                        try { db.close() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "  [${p.name}] ERREUR lecture favoris: ${e.message}")
                }
            }
            Log.i(TAG, "════ TOTAL favoris : ${movies.size} films + ${tvShows.size} séries ════")
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
            Log.i(TAG, "════ AUDIT REPRISE FILMS — ${providers.size} providers non-IPTV ════")
            for (p in providers) {
                if (!AppDatabase.providerDbExists(p.name, context)) {
                    Log.i(TAG, "  [${p.name}] DB n'existe pas")
                    continue
                }
                try {
                    val db = AppDatabase.getInstanceForProvider(p.name, context)
                    try {
                        val movies = db.movieDao().getWatchingMovies().first()
                        var kept = 0
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
                                    kept++
                                }
                            }
                        }
                        Log.i(TAG, "  [${p.name}] reprise films : ${movies.size} total, $kept retenus (>5s, <90%)")
                    } finally {
                        try { db.close() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "  [${p.name}] ERREUR reprise films: ${e.message}")
                }
            }
            Log.i(TAG, "════ TOTAL reprise films : ${all.size} ════")
            all.filter { !isContinueWatchingDismissed("resume_movie_${it.id}") }
                .sortedByDescending { it.watchHistory?.lastEngagementTimeUtcMillis ?: 0 }
                .take(limit)
        }

    /** Épisodes en cours de lecture → groupés par série (1 carte = 1 série,
     *  le dernier épisode regardé). Clic → ouvre la saison dans le provider. */
    suspend fun loadContinueWatchingSeries(context: Context, limit: Int = 20): List<ContinueWatchingSeriesItem> =
        withContext(Dispatchers.IO) {
            // tvShowId → (providerName, dernierÉpisode, db)
            data class RawEntry(val providerName: String, val episode: Episode, val db: AppDatabase)
            val seriesMap = LinkedHashMap<String, RawEntry>()
            val openDbs = mutableListOf<AppDatabase>()
            val providers = Provider.providers.keys.filter { it !is IptvProvider }
            Log.i(TAG, "════ AUDIT REPRISE SÉRIES — ${providers.size} providers non-IPTV ════")
            for (p in providers) {
                if (!AppDatabase.providerDbExists(p.name, context)) {
                    Log.i(TAG, "  [${p.name}] DB n'existe pas")
                    continue
                }
                try {
                    val db = AppDatabase.getInstanceForProvider(p.name, context)
                    openDbs += db
                    try {
                        val episodes = db.episodeDao().getWatchingEpisodes().first()
                        var kept = 0
                        var noTvShow = 0
                        for (ep in episodes) {
                            val tvId = ep.tvShow?.id
                            if (tvId == null) { noTvShow++; continue }
                            val wh = ep.watchHistory ?: continue
                            if (wh.lastPlaybackPositionMillis <= 5_000) continue
                            val existing = seriesMap[tvId]
                            val existingTs = existing?.episode?.watchHistory?.lastEngagementTimeUtcMillis ?: 0
                            val thisTs = wh.lastEngagementTimeUtcMillis
                            if (existing == null || thisTs > existingTs) {
                                seriesMap[tvId] = RawEntry(p.name, ep, db)
                            }
                            kept++
                        }
                        Log.i(TAG, "  [${p.name}] reprise séries : ${episodes.size} épisodes, $kept retenus (>5s), $noTvShow sans tvShow lié")
                    } catch (e: Exception) {
                        Log.w(TAG, "  [${p.name}] ERREUR reprise séries: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "  [${p.name}] ERREUR ouverture DB séries: ${e.message}")
                }
            }
            Log.i(TAG, "════ TOTAL reprise séries (groupées) : ${seriesMap.size} ════")

            val result = seriesMap.values
                .filter { entry ->
                    val id = "resume_series_${entry.episode.tvShow?.id}"
                    !isContinueWatchingDismissed(id)
                }
                .sortedByDescending { it.episode.watchHistory?.lastEngagementTimeUtcMillis ?: 0 }
                .take(limit)
                .map { entry ->
                    val ep = entry.episode
                    val db = entry.db
                    val tvShowId = ep.tvShow?.id
                    val fullTvShow = tvShowId?.let {
                        try { db.tvShowDao().getById(it) } catch (_: Exception) { null }
                    } ?: ep.tvShow
                    val seasonId = ep.season?.id
                    val fullSeason = seasonId?.let {
                        try { db.seasonDao().getById(it) } catch (_: Exception) { null }
                    } ?: ep.season
                    ep.tvShow = fullTvShow
                    ep.season = fullSeason
                    if (ep.poster.isNullOrBlank()) {
                        ep.poster = fullTvShow?.poster
                    }

                    val syntheticId = "resume_series_${tvShowId}"
                    originByItemId[syntheticId] = entry.providerName
                    val item = ContinueWatchingSeriesItem(
                        providerName = entry.providerName,
                        tvShow = fullTvShow ?: ep.tvShow!!,
                        lastEpisode = ep,
                        seasonNumber = fullSeason?.number ?: 1,
                        episodeNumber = ep.number,
                    )
                    resumeSeriesData[syntheticId] = item
                    item
                }

            // Fermer les DBs
            for (db in openDbs) {
                try { db.close() } catch (_: Exception) {}
            }
            result
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
    //  Suppression en masse (poubelle par section, 2026-06-22)
    // ══════════════════════════════════════════════════════════════════

    /** Retire TOUS les films des favoris, cross-provider. */
    suspend fun clearAllFavoriteMovies(context: Context) = withContext(Dispatchers.IO) {
        val providers = Provider.providers.keys.filter { it !is IptvProvider }
        for (p in providers) {
            if (!AppDatabase.providerDbExists(p.name, context)) continue
            try {
                val db = AppDatabase.getInstanceForProvider(p.name, context)
                try {
                    val favs = db.movieDao().getFavorites().first()
                    favs.forEach { db.movieDao().setFavoriteWithLog(it.id, false) }
                } finally {
                    try { db.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        Log.d(TAG, "clearAllFavoriteMovies done")
    }

    /** Retire TOUTES les séries des favoris, cross-provider. */
    suspend fun clearAllFavoriteTvShows(context: Context) = withContext(Dispatchers.IO) {
        val providers = Provider.providers.keys.filter { it !is IptvProvider }
        for (p in providers) {
            if (!AppDatabase.providerDbExists(p.name, context)) continue
            try {
                val db = AppDatabase.getInstanceForProvider(p.name, context)
                try {
                    val favs = db.tvShowDao().getFavorites().first()
                    favs.forEach { db.tvShowDao().setFavoriteWithLog(it.id, false) }
                } finally {
                    try { db.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        Log.d(TAG, "clearAllFavoriteTvShows done")
    }

    /** Masque TOUTES les reprises de lecture d'un coup. */
    fun dismissAllContinueWatching() {
        // On ajoute tous les IDs connus au set dismissed
        val prefs = dismissedPrefs()
        val set = prefs.getStringSet(dismissedKey(), emptySet())!!.toMutableSet()
        // Films
        originByItemId.keys.filter { it.startsWith("resume_movie_") }.forEach { set.add(it) }
        // Séries
        resumeSeriesData.keys.forEach { set.add(it) }
        prefs.edit().putStringSet(dismissedKey(), set).apply()
        Log.d(TAG, "dismissAllContinueWatching: ${set.size} IDs dismissed")
    }

    // ══════════════════════════════════════════════════════════════════
    //  Masquage des reprises de lecture
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
