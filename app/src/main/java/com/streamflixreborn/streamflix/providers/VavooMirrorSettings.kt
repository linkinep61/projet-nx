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
    // 2026-09-23 (user : « supprime les miroirs qui ne fonctionnent pas, ça ne sert à rien
    //   de switcher dessus ») — mesuré sur l'Oppo le jour même, seuls DEUX répondent :
    //     vavoo.net ✓   vavoo.to ✓
    //     kool.ws  ✗  connexion impossible, abandon après 15 s (c'était LA cause des chaînes
    //                 lentes : l'app attendait tous les miroirs)
    //     kool.to / oha.to / huhu.to  ✗  « Trust anchor for certification path not found »
    //     oha.online / oha.cx  ✗  404 sur /mediahubmx-resolve.json (autre protocole ; oha.cx est
    //                 celui de VYPN, relevé dans son cache : `https://oha.cx/live/play/<id>`)
    //   Un miroir retiré qui était choisi dans les réglages retombe sur le premier (vavoo.net).
    // 2026-09-23 (suite, vérifié dans le Chrome du user) : kool.ws N'EST PAS MORT. Son API
    //   répond (`400 Validation error` = endpoint valide) et sa page web dit « ce flux
    //   nécessite une app compatible mhub » (= VYPN, ou nous). Sur l'Oppo, il échouait parce
    //   que le DoH (dot.sb) donnait une ANCIENNE adresse (185.254.196.78) ; tous les DNS
    //   interrogés depuis le PC donnent maintenant 131.123.43.240. Remis en liste.
    //   kool.to redirige vers kool.ws (inutile en double) ; oha.to / huhu.to sont passés à
    //   la nouvelle interface web, l'ancienne API y renvoie 404 → toujours retirés.
    val list = listOf(
        Mirror("https://vavoo.net", "vavoo.net (défaut, sans VPN)"),
        Mirror("https://vavoo.to", "vavoo.to"),
        Mirror("https://kool.ws", "kool.ws"),
        // 2026-09-23 (signalé par le user, vérifié dans son Chrome) : API mediahubmx vivante
        //   (`400 Validation error` sur resolve ET catalog).
        Mirror("https://vavoo.top", "vavoo.top"),
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
