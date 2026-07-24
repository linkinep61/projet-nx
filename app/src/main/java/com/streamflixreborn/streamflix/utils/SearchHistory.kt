package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Historique de recherche persisté par profil via SharedPreferences.
 * Illimité en taille, avec possibilité d'effacer tout.
 */
object SearchHistory {

    private const val PREFS_NAME = "search_history"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 2026-07-24 : bucket optionnel pour séparer des historiques (ex "music"), rétrocompatible.
    private fun key(bucket: String = "default"): String {
        val base = "history_${ProfileManager.currentProfileIdOrDefault()}"
        return if (bucket == "default") base else "${base}_$bucket"
    }

    /** Ajoute une requête en tête de liste (dédupliquée, insensible à la casse). */
    fun add(context: Context, query: String, bucket: String = "default") {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        val list = getAll(context, bucket).toMutableList()
        // Retirer l'entrée existante (case-insensitive) pour la remonter en tête
        list.removeAll { it.equals(trimmed, ignoreCase = true) }
        list.add(0, trimmed)

        save(context, list, bucket)
    }

    /** Retourne toutes les recherches (les plus récentes en premier). */
    fun getAll(context: Context, bucket: String = "default"): List<String> {
        val json = prefs(context).getString(key(bucket), null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Supprime une entrée précise. */
    fun remove(context: Context, query: String, bucket: String = "default") {
        val list = getAll(context, bucket).toMutableList()
        list.removeAll { it.equals(query, ignoreCase = true) }
        save(context, list, bucket)
    }

    /** Efface tout l'historique du profil courant. */
    fun clear(context: Context, bucket: String = "default") {
        prefs(context).edit().remove(key(bucket)).apply()
    }

    private fun save(context: Context, list: List<String>, bucket: String = "default") {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(context).edit().putString(key(bucket), arr.toString()).apply()
    }
}
