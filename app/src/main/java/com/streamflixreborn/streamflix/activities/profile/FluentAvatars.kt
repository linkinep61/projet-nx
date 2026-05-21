package com.streamflixreborn.streamflix.activities.profile

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * 2026-05-20 (user "je croyais qu'il y avait 1500, y en a meme pas 50") :
 * catalogue COMPLET des avatars Microsoft Fluent 3D (~1581), genere depuis le
 * repo officiel et embarque en asset texte `fluent_avatars.json` (~66 Ko).
 *
 * Chaque entree = chemin relatif sous /assets/ du repo Fluent (ex:
 * "Fox/3D/fox_3d.png"). [ProfileEmojiArt] sait charger un tel chemin.
 *
 * Les 67 emojis "cures" historiques sont en tete de liste (jolis, par theme),
 * puis le reste alphabetiquement. Aucune image embarquee : tout est charge a la
 * demande via Glide + cache disque => 0 Mo dans l'APK.
 */
object FluentAvatars {

    @Volatile private var cache: List<String>? = null

    fun list(context: Context): List<String> {
        cache?.let { return it }
        return try {
            val json = context.applicationContext.assets
                .open("fluent_avatars.json")
                .bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            cache = out
            out
        } catch (e: Exception) {
            Log.w("FluentAvatars", "load failed, fallback to curated emojis: ${e.message}")
            // Fallback minimal : les emojis curés (caracteres Unicode), que
            // ProfileEmojiArt sait aussi rendre.
            ProfileEmojis.list.also { cache = it }
        }
    }
}
