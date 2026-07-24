package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * 2026-07-24 (user « mise en favori pour faire des playlists ») :
 * playlist musique unique « Ma playlist ★ ». Contrairement aux radios (qui ont un
 * catalogue), un morceau FileSearch n'existe nulle part ailleurs → on persiste
 * l'URL DIRECTE + le titre (JSON). Ordre = ajout le plus récent en tête.
 */
object MusicFavoritesStore {

    private const val PREFS_NAME = "music_favorites"
    private const val KEY_TRACKS = "music_tracks"

    data class Track(val url: String, val title: String)

    private val gson = Gson()
    private val prefs: SharedPreferences by lazy {
        StreamFlixApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun all(): List<Track> = try {
        gson.fromJson(prefs.getString(KEY_TRACKS, "[]"), Array<Track>::class.java)?.toList() ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun isFavorite(url: String): Boolean = all().any { it.url == url }

    /** Ajoute/retire un morceau. Retourne true si ajouté. */
    fun toggle(url: String, title: String): Boolean {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.url == url }
        val added: Boolean
        if (idx >= 0) { list.removeAt(idx); added = false }
        else { list.add(0, Track(url, title)); added = true }
        prefs.edit().putString(KEY_TRACKS, gson.toJson(list)).apply()
        return added
    }
}
