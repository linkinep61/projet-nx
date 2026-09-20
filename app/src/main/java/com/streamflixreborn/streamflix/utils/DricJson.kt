package com.streamflixreborn.streamflix.utils

import android.util.Log

/**
 * 2026-09-24 (user « la source Dric4rTV ne fonctionne plus ») : le fichier
 * dric4rt.free.fr/1.json a été publié mal formé par son auteur (MAJ du 22/09) :
 * la dernière chaîne du fichier n'a pas son « } » de fermeture
 * (`"url": "…" ]` au lieu de `"url": "…" } ]`). org.json refuse alors TOUT le
 * fichier → plus aucune chaîne Dric4rTV (TV Hub, Musique, World Live).
 *
 * [repare] ne touche à rien si le JSON est valide. Sinon il essaie, une par une,
 * d'insérer l'accolade manquante devant chaque `]` qui suit directement une
 * valeur texte, et garde la première correction qui rend le fichier lisible.
 */
object DricJson {
    private const val TAG = "DricJson"
    private val CANDIDAT = Regex("\"(?:[^\"\\\\]|\\\\.)*\"(\\s*)\\]")

    private fun lisible(t: String): Boolean = try {
        val s = t.trim()
        if (s.startsWith("[")) org.json.JSONArray(s) else org.json.JSONObject(s)
        true
    } catch (_: Throwable) { false }

    fun repare(texte: String): String {
        if (texte.isBlank() || lisible(texte)) return texte
        val candidats = CANDIDAT.findAll(texte).toList().asReversed()
        for (m in candidats) {
            val pos = m.range.last  // position du « ] »
            val essai = texte.substring(0, pos) + "}" + texte.substring(pos)
            if (lisible(essai)) {
                Log.w(TAG, "JSON Dric4rTV mal formé réparé (accolade ajoutée en $pos)")
                return essai
            }
        }
        Log.w(TAG, "JSON Dric4rTV mal formé, réparation impossible")
        return texte
    }
}