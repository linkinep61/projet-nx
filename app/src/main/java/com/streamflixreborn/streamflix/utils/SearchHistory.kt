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

    private fun key(): String = "history_${ProfileManager.currentProfileIdOrDefault()}"

    /** Ajoute une requête en tête de liste (dédupliquée, insensible à la casse). */
    fun add(context: Context, query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        val list = getAll(context).toMutableList()
        // Retirer l'entrée existante (case-insensitive) pour la remonter en tête
        list.removeAll { it.equals(trimmed, ignoreCase = true) }
        list.add(0, trimmed)

        save(context, list)
    }

    /** Retourne toutes les recherches (les plus récentes en premier). */
    fun getAll(context: Context): List<String> {
        val json = prefs(context).getString(key(), null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Supprime une entrée précise. */
    fun remove(context: Context, query: String) {
        val list = getAll(context).toMutableList()
        list.removeAll { it.equals(query, ignoreCase = true) }
        save(context, list)
    }

    /** Efface tout l'historique du profil courant. */
    fun clear(context: Context) {
        prefs(context).edit().remove(key()).apply()
    }

    private fun save(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(context).edit().putString(key(), arr.toString()).apply()
    }
}
