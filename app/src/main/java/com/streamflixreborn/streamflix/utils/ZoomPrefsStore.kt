package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video

/**
 * 2026-07-06 : Persistance du zoom manuel par serveur (hostname).
 *
 * Quand l'utilisateur ajuste manuellement le zoom (scaleX/scaleY) sur un serveur
 * donné (ex: Sibnet), les valeurs sont enregistrées et automatiquement ré-appliquées
 * la prochaine fois qu'un flux de ce même serveur est lu.
 *
 * Clé = hostname du serveur (extrait de server.src ou video.source).
 * Valeur = "scaleX,scaleY" (ex: "1.25,1.40").
 *
 * Un reset (double-tap mobile / BACK TV → 1.0) supprime l'entrée.
 */
object ZoomPrefsStore {

    private const val TAG = "ZoomPrefsStore"
    private const val PREFS_NAME = "zoom_prefs"

    /**
     * 2026-07-10 (user « quand je règle l'écran, la vidéo suivante garde le même zoom jusqu'à
     *   modification ») : clé GLOBALE du dernier zoom manuel réglé. Sert de zoom « collant » appliqué
     *   à TOUTES les vidéos suivantes (quel que soit le serveur) tant que l'utilisateur ne le change
     *   pas / ne le remet pas à zéro. Utilisée avec save()/load() comme n'importe quelle clé.
     */
    const val LAST_KEY = "__last_manual_zoom__"

    private val prefs: SharedPreferences by lazy {
        val ctx: Context = StreamFlixApp.instance.applicationContext
        ctx.getSharedPreferences("${BuildConfig.APPLICATION_ID}.$PREFS_NAME", Context.MODE_PRIVATE)
    }

    /** Extrait la clé de zoom depuis un Video.Server (hostname de l'embed URL). */
    fun extractKey(server: Video.Server?, videoSource: String? = null): String? {
        // 1. Essayer server.src (URL embed, ex: https://video.sibnet.ru/shell.php?...)
        val src = server?.src?.takeIf { it.isNotBlank() } ?: videoSource
        if (src.isNullOrBlank()) return null
        return try {
            val host = Uri.parse(src).host ?: return null
            // Normaliser : retirer "www." / "video." pour regrouper
            host.lowercase()
                .removePrefix("www.")
                .removePrefix("video.")
                .removePrefix("embed.")
                .removePrefix("player.")
        } catch (e: Exception) {
            Log.w(TAG, "extractKey failed for src=${src.take(60)}: ${e.message}")
            null
        }
    }

    /** Sauvegarde le zoom pour un serveur. Si scaleX==1.0 ET scaleY==1.0, supprime l'entrée. */
    fun save(key: String, scaleX: Float, scaleY: Float) {
        if (scaleX == 1.0f && scaleY == 1.0f) {
            // Reset = supprimer la pref
            prefs.edit().remove(key).apply()
            Log.d(TAG, "Zoom cleared for $key")
        } else {
            prefs.edit().putString(key, "$scaleX,$scaleY").apply()
            Log.d(TAG, "Zoom saved for $key: scaleX=$scaleX, scaleY=$scaleY")
        }
    }

    /** Charge le zoom sauvegardé. Retourne null si aucun zoom n'est enregistré. */
    fun load(key: String): Pair<Float, Float>? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val parts = raw.split(",")
            if (parts.size == 2) {
                val sx = parts[0].toFloat()
                val sy = parts[1].toFloat()
                // Sanity check
                if (sx in 0.1f..10f && sy in 0.1f..10f) {
                    Pair(sx, sy)
                } else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "load parse failed for $key: $raw")
            null
        }
    }

    /** Supprime le zoom pour un serveur. */
    fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** Supprime tous les zooms enregistrés. */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
