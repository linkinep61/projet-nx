package com.streamflixreborn.streamflix.utils

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-08-03 (demande user, capture d'un concurrent à l'appui) — ORDRE ET MASQUAGE DES GROUPES
 * de Mon IPTV.
 *
 * Le problème : `getHome()` déversait jusqu'à 2 400 chaînes d'un bloc, découpées en 300 rangées.
 * Impossible de s'y retrouver. Les groupes existaient déjà (`group-title` du M3U, exposés par
 * `MyIptvProvider.availableCategoriesWithCount`), mais seulement comme filtre déroulant.
 *
 * Ce magasin ajoute les deux réglages qui manquaient, **par type de contenu** :
 *  - un ORDRE personnalisé (les groupes qu'on remonte en tête) ;
 *  - un ensemble de groupes MASQUÉS (qui disparaissent de l'accueil et du sélecteur).
 *
 * ⚠ Pourquoi séparé par type : les groupes de TV (« France FHD », « France Sport »), de films
 * (« Nouveautés », « Netflix », « 4K UHD ») et de séries (« Séries FR », « Anime ») sont des
 * ensembles DISJOINTS. Un réglage commun n'aurait aucun sens — un groupe masqué en films ne
 * correspond à rien en TV. D'où une configuration indépendante par onglet.
 *
 * Stockage : un seul JSON dans les préférences, `{ "LIVE": {"ordre":[…], "masques":[…]}, … }`.
 * Volume attendu : quelques dizaines de noms par type — le JSON reste trivial.
 */
object IptvGroupPrefsStore {

    private const val PREFS = "iptv_group_prefs"
    private const val KEY = "config"
    private const val CHAMP_ORDRE = "ordre"
    private const val CHAMP_MASQUES = "masques"

    private fun prefs() = StreamFlixApp.instance
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun racine(): JSONObject = try {
        JSONObject(prefs().getString(KEY, "{}") ?: "{}")
    } catch (_: Throwable) {
        JSONObject()
    }

    private fun ecrire(racine: JSONObject) {
        try {
            prefs().edit().putString(KEY, racine.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    private fun bloc(racine: JSONObject, type: String): JSONObject =
        racine.optJSONObject(type) ?: JSONObject().also { racine.put(type, it) }

    private fun liste(o: JSONObject, champ: String): MutableList<String> {
        val a = o.optJSONArray(champ) ?: return mutableListOf()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) a.optString(i)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    private fun poser(o: JSONObject, champ: String, valeurs: List<String>) {
        o.put(champ, JSONArray().apply { valeurs.forEach { put(it) } })
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    /** Groupes masqués pour ce type. Vide = tout est visible (comportement d'origine). */
    fun masques(type: String): Set<String> = liste(bloc(racine(), type), CHAMP_MASQUES).toSet()

    fun estMasque(type: String, groupe: String): Boolean = groupe in masques(type)

    /**
     * Réordonne [groupes] selon l'ordre mémorisé.
     *
     * Les groupes connus prennent leur position enregistrée ; **les inconnus sont conservés
     * à la suite**, dans leur ordre d'origine. C'est volontaire : quand l'utilisateur change de
     * bouquet IPTV, de nouveaux groupes apparaissent — les perdre serait pire que de les mettre
     * en fin de liste, où il les verra et pourra les remonter.
     */
    fun <T> ordonner(type: String, groupes: List<T>, nomDe: (T) -> String): List<T> {
        val ordre = liste(bloc(racine(), type), CHAMP_ORDRE)
        if (ordre.isEmpty()) return groupes
        val rang = ordre.withIndex().associate { (i, nom) -> nom to i }
        return groupes.sortedBy { rang[nomDe(it)] ?: Int.MAX_VALUE }
    }

    // ── Écriture ─────────────────────────────────────────────────────────────

    fun basculerMasque(type: String, groupe: String) {
        val r = racine()
        val b = bloc(r, type)
        val m = liste(b, CHAMP_MASQUES)
        if (!m.remove(groupe)) m.add(groupe)
        poser(b, CHAMP_MASQUES, m)
        ecrire(r)
    }

    /** Masque ou démasque tout d'un coup (bouton « Tout masquer » / « Tout afficher »). */
    fun masquerTout(type: String, groupes: List<String>, masquer: Boolean) {
        val r = racine()
        val b = bloc(r, type)
        poser(b, CHAMP_MASQUES, if (masquer) groupes.distinct() else emptyList())
        ecrire(r)
    }

    /**
     * Déplace un groupe d'un cran. [tousLesGroupes] doit être la liste AFFICHÉE (donc déjà
     * ordonnée) : on repart d'elle pour figer l'ordre complet, sinon un déplacement sur une
     * liste partielle écraserait la position des groupes non listés.
     */
    fun deplacer(type: String, tousLesGroupes: List<String>, groupe: String, versLeHaut: Boolean) {
        val courant = tousLesGroupes.toMutableList()
        val i = courant.indexOf(groupe)
        if (i < 0) return
        val j = if (versLeHaut) i - 1 else i + 1
        if (j < 0 || j >= courant.size) return
        courant[i] = courant[j].also { courant[j] = courant[i] }
        val r = racine()
        poser(bloc(r, type), CHAMP_ORDRE, courant)
        ecrire(r)
    }

    /** Remet ce type à zéro : ordre d'origine, plus aucun masquage. */
    fun reinitialiser(type: String) {
        val r = racine()
        r.remove(type)
        ecrire(r)
    }

    /** Vrai si l'utilisateur a personnalisé quoi que ce soit pour ce type. */
    fun estPersonnalise(type: String): Boolean {
        val b = bloc(racine(), type)
        return liste(b, CHAMP_ORDRE).isNotEmpty() || liste(b, CHAMP_MASQUES).isNotEmpty()
    }
}
