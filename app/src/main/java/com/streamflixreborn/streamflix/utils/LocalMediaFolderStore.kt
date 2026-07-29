package com.streamflixreborn.streamflix.utils

import android.content.Context
import org.json.JSONArray

/**
 * 2026-07-28 (demande user : « choisir un dossier en favori pour éviter de fouiller quand il y en a
 * plein ») — DOSSIERS FAVORIS de la médiathèque locale (musique et vidéo, séparés).
 *
 * On retient le chemin relatif du dossier (RELATIVE_PATH, ex. « Music/Rock/ »). Sert à afficher en
 * priorité les dossiers marqués, et à filtrer la liste sur les favoris.
 */
object LocalMediaFolderStore {

    private const val PREFS = "onyx_local_media_folders"

    private fun key(audio: Boolean) = if (audio) "audio_fav_dirs" else "video_fav_dirs"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context, audio: Boolean): Set<String> = try {
        val raw = prefs(context).getString(key(audio), "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    fun isFavorite(context: Context, audio: Boolean, dir: String): Boolean =
        all(context, audio).contains(dir)

    /** @return true si ajouté, false si retiré. */
    fun toggle(context: Context, audio: Boolean, dir: String): Boolean {
        if (dir.isBlank()) return false
        val set = all(context, audio).toMutableSet()
        val added = if (set.contains(dir)) { set.remove(dir); false } else { set.add(dir); true }
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        prefs(context).edit().putString(key(audio), arr.toString()).apply()
        return added
    }
}
