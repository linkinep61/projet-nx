package com.streamflixreborn.streamflix.providers

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * 2026-05-28 : Picker miroir Vavoo. L'user choisit quel site utiliser en
 * priorité (certains marchent sans VPN selon la région).
 *
 * Le miroir préféré passe EN PREMIER dans la chaîne de fallback
 * (catalog + resolve). Les autres restent en backup.
 */
object VavooMirrorSettings {

    private const val PREF_KEY = "vavoo_preferred_mirror"

    data class Mirror(val url: String, val label: String)

    val list = listOf(
        Mirror("https://vavoo.to", "vavoo.to (défaut)"),
        Mirror("https://kool.to", "kool.to"),
        Mirror("https://oha.to", "oha.to"),
        Mirror("https://huhu.to", "huhu.to"),
    )

    fun getCurrent(context: Context): Mirror {
        val url = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, list.first().url) ?: list.first().url
        return list.find { it.url == url } ?: list.first()
    }

    fun setCurrent(context: Context, mirror: Mirror) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_KEY, mirror.url)
            .apply()
    }

    /**
     * Retourne BASE_SITES réordonnées : miroir préféré en premier,
     * puis les autres dans l'ordre original.
     */
    fun getOrderedSites(context: Context): List<String> {
        val preferred = getCurrent(context).url
        val others = list.map { it.url }.filter { it != preferred }
        return listOf(preferred) + others
    }
}
