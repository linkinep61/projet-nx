package com.streamflixreborn.streamflix.providers

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * 2026-06-10 — Catégorie courante pour TV Hub (BoxXtemusProvider).
 *
 * UX : pareil que World Live — un bouton qui ouvre un picker des catégories
 * disponibles dans le catalogue 3box-tv (TF1+, France TV, M6+, Canal+, etc).
 * "all" = montre toutes (= comportement actuel).
 */
object BoxXtemusCategorySettings {

    private const val PREF_KEY = "boxxtemus_category"
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

    fun getCurrentLabel(context: Context): String {
        val code = getCurrentCode(context)
        return if (code == ALL_CODE) ALL_LABEL else code
    }

    fun matches(context: Context, sectionName: String): Boolean {
        val code = getCurrentCode(context)
        if (code == ALL_CODE) return true
        return sectionName.equals(code, ignoreCase = true)
    }
}
