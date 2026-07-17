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

    // 2026-07-16 (user « sur vavoo pas besoin de VPN ») : vavoo.net + kool.ws EN TÊTE
    //   (priorité + défaut). Vérifié en direct : les deux servent l'API mediahubmx
    //   (mediahubmx-catalog/resolve.json → 400 « Validation error » = endpoints valides)
    //   et fonctionnent sans le bypass VYPN. Les .to restent en fallback.
    val list = listOf(
        Mirror("https://vavoo.net", "vavoo.net (défaut, sans VPN)"),
        Mirror("https://kool.ws", "kool.ws (sans VPN)"),
        Mirror("https://vavoo.to", "vavoo.to"),
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
