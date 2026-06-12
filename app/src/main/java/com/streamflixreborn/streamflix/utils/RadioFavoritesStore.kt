package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * 2026-06-08 (user "long-press sur la radio ça la met en favoris") :
 * persistance des radios favorites. Stocké par identifiant de station.
 */
object RadioFavoritesStore {

    private const val PREFS_NAME = "radio_favorites"
    private const val KEY_FAVORITES = "radio_fav_ids"

    private val prefs: SharedPreferences by lazy {
        StreamFlixApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun all(): Set<String> = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()

    fun isFavorite(id: String): Boolean = all().contains(id)

    fun toggle(id: String): Boolean {
        val s = all().toMutableSet()
        val newState = if (s.contains(id)) {
            s.remove(id); false
        } else {
            s.add(id); true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, s).apply()
        return newState
    }
}
