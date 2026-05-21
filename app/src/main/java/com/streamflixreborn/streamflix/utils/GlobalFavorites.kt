package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.database.AppDatabase
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
}
