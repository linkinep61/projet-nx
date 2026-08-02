package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Liens (serveurs) désactivés à la main par l'utilisateur.
 *
 * ── 2026-08-06 — demande d'un testeur, amendée par le user ───────────────────────────────
 *   Avant : l'appui long sur un serveur SIGNALAIT le lien (envoi GitHub). Cette fonction est
 *   supprimée. Désormais l'appui long **désactive** le lien : il disparaît de la liste du
 *   lecteur et n'est plus proposé, mais reste récupérable depuis
 *   « Paramètres → Gérer les sources ».
 *   Le testeur proposait un menu « signaler / désactiver » ; le user a tranché pour désactiver
 *   seul — un appui long, pas de dialogue.
 *
 *   On mémorise l'id ET le nom : l'id sert au filtrage, le nom à l'affichage dans l'écran de
 *   gestion (un id comme `bkreg::Coflix Boston::coflix_14` ne dit rien à l'utilisateur).
 */
object LiensDesactives {

    private const val CLE = "liens_desactives_v1"

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    /** id → nom lisible. */
    fun tous(ctx: Context): Map<String, String> {
        val brut = prefs(ctx).getString(CLE, null) ?: return emptyMap()
        return try {
            val tableau = JSONArray(brut)
            buildMap {
                for (i in 0 until tableau.length()) {
                    val o = tableau.getJSONObject(i)
                    put(o.getString("id"), o.optString("nom", o.getString("id")))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun estDesactive(ctx: Context, id: String): Boolean = tous(ctx).containsKey(id)

    fun desactiver(ctx: Context, id: String, nom: String) {
        if (id.isBlank()) return
        ecrire(ctx, tous(ctx).toMutableMap().apply { put(id, nom) })
    }

    fun reactiver(ctx: Context, id: String) {
        ecrire(ctx, tous(ctx).toMutableMap().apply { remove(id) })
    }

    fun toutReactiver(ctx: Context) = ecrire(ctx, emptyMap())

    private fun ecrire(ctx: Context, carte: Map<String, String>) {
        val tableau = JSONArray()
        carte.forEach { (id, nom) ->
            tableau.put(JSONObject().put("id", id).put("nom", nom))
        }
        prefs(ctx).edit().putString(CLE, tableau.toString()).apply()
    }
}
