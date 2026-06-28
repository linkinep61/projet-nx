package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-06-20 (user "pouvoir mettre La série en favoris En restant appuyé
 *   longtemps dessus Et qu'elle apparaisse directement sur le TV hub
 *   Et bien sûr dans les cœurs") : favoris REPLAY (séries + films).
 *
 * Stocké en SharedPreferences (JSON), PAR PROFIL. Chaque entrée garde
 * de quoi afficher la carte dans l'écran Favoris (cœur) ET dans le
 * home TV Hub, ET de quoi rouvrir la fiche synopsis / lancer le film.
 *
 * Clé d'unicité = l'id complet du TvShow (= "livehub::replay::<path>").
 */
object ReplayFavoritesStore {
    private const val TAG = "ReplayFavStore"
    private const val PREFS = "replay_favorites"

    /** Préfixe d'id synthétique pour reconnaître un favori replay dans les
     *  Cœurs (GlobalFavorites) et le router correctement au clic. */
    const val SYNTHETIC_ID_PREFIX = "replayfav::"

    data class Entry(
        val id: String,           // ex: "livehub::replay::tmc/show-slug"
        val title: String,
        val poster: String?,
        val banner: String?,
        val isMovie: Boolean,
        val favoritedAt: Long,
    ) {
        /** id synthétique pour la grille des Cœurs. */
        fun syntheticId(): String = "$SYNTHETIC_ID_PREFIX$id"
    }

    private fun prefs(): android.content.SharedPreferences =
        StreamFlixApp.instance.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): String = "favs_${ProfileManager.currentProfileIdOrDefault()}"

    private fun readAll(): MutableList<Entry> {
        val raw = prefs().getString(key(), "[]") ?: "[]"
        val out = mutableListOf<Entry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Entry(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    poster = o.optString("poster").takeIf { it.isNotBlank() },
                    banner = o.optString("banner").takeIf { it.isNotBlank() },
                    isMovie = o.optBoolean("isMovie", false),
                    favoritedAt = o.optLong("favoritedAt"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "readAll parse error: ${e.message}")
        }
        return out
    }

    private fun writeAll(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("title", e.title)
                put("poster", e.poster ?: "")
                put("banner", e.banner ?: "")
                put("isMovie", e.isMovie)
                put("favoritedAt", e.favoritedAt)
            })
        }
        prefs().edit().putString(key(), arr.toString()).apply()
    }

    fun isFavorite(id: String): Boolean =
        readAll().any { it.id == id }

    /** Toggle. Retourne le nouvel état (true = désormais favori). */
    fun toggle(id: String, title: String, poster: String?, banner: String?, isMovie: Boolean): Boolean {
        val list = readAll()
        val existing = list.indexOfFirst { it.id == id }
        return if (existing >= 0) {
            list.removeAt(existing)
            writeAll(list)
            Log.d(TAG, "Retiré des favoris: $title ($id)")
            false
        } else {
            list.add(Entry(
                id = id, title = title, poster = poster, banner = banner,
                isMovie = isMovie, favoritedAt = System.currentTimeMillis(),
            ))
            writeAll(list)
            Log.d(TAG, "Ajouté aux favoris: $title ($id)")
            true
        }
    }

    /** Retire par id synthétique (depuis l'écran cœur). */
    fun removeBySyntheticId(syntheticId: String) {
        val realId = syntheticId.removePrefix(SYNTHETIC_ID_PREFIX)
        val list = readAll().filterNot { it.id == realId }
        writeAll(list)
    }

    /** Retrouve l'entrée complète depuis un id synthétique. */
    fun findBySyntheticId(syntheticId: String): Entry? {
        val realId = syntheticId.removePrefix(SYNTHETIC_ID_PREFIX)
        return readAll().firstOrNull { it.id == realId }
    }

    /** Tous les favoris du profil courant, plus récents d'abord. */
    fun all(): List<Entry> = readAll().sortedByDescending { it.favoritedAt }

    /** 2026-06-22 : vide tous les replays favoris du profil courant. */
    fun clearAll() = writeAll(emptyList())
}
