package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.TvShow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-10-27 (user : « mettre un dossier en favori… qu'il apparaisse en favori et ensuite qu'on
 * clique dessus pour avoir les choses à l'intérieur » ; « sur une nouvelle ligne, pas avec les
 * autres favoris » ; « valable pour tous les dossiers qui ont des dossiers ») : dossiers favoris
 * du TV Hub. N'importe quelle carte ou tuile-dossier (`livehub::folder::…`, premier niveau ou
 * sous-dossier) peut y être mise par appui long ; elle apparaît dans la rangée
 * « 📁 Dossiers favoris » du TV Hub, et un clic la rouvre comme d'habitude.
 *
 * Magasin à part (les favoris de chaînes et de replays n'en voient rien), PAR PROFIL.
 */
object DossiersFavorisStore {
    private const val TAG = "DossiersFavoris"
    private const val PREFS = "dossiers_favoris"
    const val NOM_RANGEE = "📁 Dossiers favoris"

    data class Entree(val id: String, val titre: String, val poster: String?)

    fun estDossier(id: String) = id.startsWith("livehub::folder::")

    private fun prefs() = StreamFlixApp.instance.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cle() = "dossiers_${ProfileManager.currentProfileIdOrDefault()}"

    fun tous(): List<Entree> = try {
        val arr = JSONArray(prefs().getString(cle(), "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entree(o.optString("id"), o.optString("titre"), o.optString("poster").takeIf { it.isNotBlank() })
        }.filter { it.id.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "lecture : ${e.message}")
        emptyList()
    }

    private fun ecrire(liste: List<Entree>) {
        val arr = JSONArray()
        liste.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("titre", e.titre).put("poster", e.poster ?: ""))
        }
        prefs().edit().putString(cle(), arr.toString()).apply()
    }

    fun estFavori(id: String) = tous().any { it.id == id }

    /** Ajoute ou retire ; rend true si le dossier est maintenant en favori. */
    fun basculer(show: TvShow): Boolean {
        val liste = tous().toMutableList()
        val i = liste.indexOfFirst { it.id == show.id }
        return if (i >= 0) {
            liste.removeAt(i); ecrire(liste); false
        } else {
            val titre = (show.title ?: "Dossier").removePrefix("📁 ").substringBeforeLast(" (").trim()
            liste.add(Entree(show.id, titre, show.poster)); ecrire(liste); true
        }
    }

    /** Signature (cache du TV Hub). */
    fun signature(): String = tous().joinToString(",") { it.id }

    /** Cartes de la rangée « Dossiers favoris ». */
    fun cartes(): List<TvShow> = tous().map { e ->
        TvShow(id = e.id, title = e.titre, poster = e.poster, banner = e.poster).apply {
            providerName = "TV Hub"
        }
    }
}
