package com.streamflixreborn.streamflix.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-07-25 (demande user : « les mettre en favori si on veut, pareil pour les vidéos ») —
 * favoris des VIDÉOS LOCALES du téléphone. Même principe que MusicFavoritesStore : on retient
 * l'URI `content://` + le titre, car une vidéo locale n'appartient à aucun catalogue.
 */
object LocalVideoFavoritesStore {

    data class LocalVideo(val uri: String, val title: String)

    private const val PREFS = "onyx_local_video_favorites"
    private const val KEY = "items"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<LocalVideo> = try {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("uri"); val t = o.optString("title")
            if (u.isBlank()) null else LocalVideo(u, t.ifBlank { u.substringAfterLast('/') })
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun isFavorite(context: Context, uri: String): Boolean = all(context).any { it.uri == uri }

    /** @return true si ajouté, false si retiré. */
    fun toggle(context: Context, uri: String, title: String): Boolean {
        val list = all(context).toMutableList()
        val added: Boolean
        val idx = list.indexOfFirst { it.uri == uri }
        if (idx >= 0) {
            list.removeAt(idx); added = false
        } else {
            list.add(0, LocalVideo(uri, title)); added = true
        }
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.uri).put("title", it.title)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
        return added
    }
}
