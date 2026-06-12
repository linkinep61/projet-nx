package com.streamflixreborn.streamflix.providers

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * 2026-06-09 — Catégorie courante du provider World Live.
 *
 * UX (idée user) : "comme les IPTV — une liste, on clique, on choisit la
 * catégorie". Remplace l'empilement de 11+ sections "World - X" par UNE
 * seule section dont le contenu dépend de la catégorie choisie via un
 * dialog picker.
 *
 * 2026-06-09 v2 (user "il est censé avoir les vraies catégories qui y
 * sont disponibles") : la liste de catégories n'est PAS hardcodée — elle
 * est calculée à partir des noms de sections retournées par
 * `WorldLiveTvProvider.getHome()` au moment d'ouvrir le picker. On stocke
 * juste le code (= nom de la section) choisi.
 *
 * "all" reste la valeur spéciale pour "Toutes les chaînes".
 */
object WorldLiveCategorySettings {

    private const val PREF_KEY = "world_live_category"
    const val ALL_CODE = "all"
    const val ALL_LABEL = "Toutes les chaînes"

    fun getCurrentCode(context: Context): String {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getString(PREF_KEY, ALL_CODE) ?: ALL_CODE
    }

    fun setCurrent(context: Context, code: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREF_KEY, code).apply()
    }

    /** Label affiché pour la catégorie courante (utilisé comme nom de
     *  section dans le home). */
    fun getCurrentLabel(context: Context): String {
        val code = getCurrentCode(context)
        return if (code == ALL_CODE) ALL_LABEL else code
    }

    /**
     * Une section match la catégorie si son nom == code stocké.
     * "all" matche tout.
     */
    fun matches(context: Context, sectionName: String): Boolean {
        val code = getCurrentCode(context)
        if (code == ALL_CODE) return true
        return sectionName.equals(code, ignoreCase = true)
    }
}
