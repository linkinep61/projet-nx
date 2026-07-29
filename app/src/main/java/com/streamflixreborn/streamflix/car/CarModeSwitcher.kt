package com.streamflixreborn.streamflix.car

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * 2026-07-25 — BASCULE « RADIO ↔ VIDÉO » POUR ANDROID AUTO.
 *
 * Constat établi par l'expérience (les deux sens testés sur vraie voiture + DHU) :
 *  • avec un `MediaBrowserService` actif, Android Auto classe ONYX en app AUDIO → on a la radio et
 *    les favoris, mais AUCUNE entrée vidéo (l'activité n'est jamais projetée) ;
 *  • sans ce service (et avec `android:appCategory="game"`), l'activité EST projetée → on a la
 *    vidéo sur l'écran voiture, mais plus la radio.
 * Les deux sont donc EXCLUSIFS : impossible de les avoir en même temps dans la même app.
 *
 * Plutôt que d'imposer un choix, on laisse l'utilisateur décider : ce commutateur active ou
 * désactive le composant `OnyxMediaBrowserService` à chaud (PackageManager), ce qui fait basculer
 * Android Auto d'un mode à l'autre.
 */
object CarModeSwitcher {

    const val PREF_KEY = "pref_car_video_mode"

    /** Applique le mode enregistré dans les préférences (appelé au démarrage de l'app). */
    fun applyFromPreferences(context: Context) {
        val videoMode = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_KEY, false)
        apply(context, videoMode)
    }

    /**
     * @param videoMode true = mode VIDÉO (service radio désactivé → l'écran voiture projette la
     *                  vidéo) ; false = mode RADIO (service média actif → radio + favoris).
     */
    fun apply(context: Context, videoMode: Boolean) {
        val pm = context.packageManager
        val service = ComponentName(context, OnyxMediaBrowserService::class.java)
        val wanted = if (videoMode) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        runCatching {
            if (pm.getComponentEnabledSetting(service) != wanted) {
                pm.setComponentEnabledSetting(service, wanted, PackageManager.DONT_KILL_APP)
                Log.i(TAG, "mode voiture = ${if (videoMode) "VIDÉO" else "RADIO"} (service média ${if (videoMode) "OFF" else "ON"})")
            }
        }.onFailure { Log.w(TAG, "bascule KO: ${it.message}") }
    }

    private const val TAG = "CarModeSwitcher"
}
