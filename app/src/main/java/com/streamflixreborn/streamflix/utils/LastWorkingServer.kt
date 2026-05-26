package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Mémorise le dernier serveur qui a réellement joué pour un contenu donné.
 * Clé = contentId (movie id ou episode id). Valeur = server.id (String).
 *
 * Au resume, si le serveur est toujours dans la liste, on le met en premier
 * pour que l'auto-play reprenne dessus directement sans refaire tout le tri.
 *
 * Store persisté (SharedPrefs) pour survivre aux kills de process.
 * Max 200 entrées (LRU implicite via ordre d'insertion).
 */
object LastWorkingServer {

    private const val PREFS_NAME = "last_working_server"
    private const val MAX_ENTRIES = 200

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Enregistre le serveur qui a joué avec succès pour ce contenu. */
    fun save(context: Context, contentId: String, serverId: String) {
        if (contentId.isBlank() || serverId.isBlank()) return
        val p = prefs(context)
        val editor = p.edit()
        editor.putString(contentId, serverId)
        // Nettoyage LRU simple : si trop d'entrées, on vide tout (rare)
        if (p.all.size > MAX_ENTRIES) {
            editor.clear()
            editor.putString(contentId, serverId)
        }
        editor.apply()
    }

    /** Récupère le serverId du dernier serveur fonctionnel pour ce contenu, ou null. */
    fun get(context: Context, contentId: String): String? {
        if (contentId.isBlank()) return null
        return prefs(context).getString(contentId, null)
    }
}
