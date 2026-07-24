package com.streamflixreborn.streamflix.car

import android.content.Intent
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * 2026-07-23 — Session Android Auto d'ONYX.
 * Ouvre le menu voiture ET démarre l'app ONYX sur le téléphone (le miroir a besoin du player
 * téléphone → on évite au user d'aller lancer l'app manuellement à chaque fois).
 */
@androidx.media3.common.util.UnstableApi
class OnyxCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        launchPhoneApp()
        // 2026-07-24 (user « quand on tape l'icône ONYX dans Android Auto ça désactive la vidéo »)
        //   Comme CarStream : si le téléphone a déjà un lecteur (on regardait un truc), taper ONYX
        //   rouvre DIRECT la projection vidéo au lieu du menu. Sinon (rien en cours) → le menu.
        return if (CarPlaybackBridge.hasPlayer) OnyxCarVideoScreen(carContext)
        else OnyxCarHomeScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        launchPhoneApp()
        // App déjà active + on re-tape l'icône : si ça joue, remonter à la vidéo.
        if (CarPlaybackBridge.hasPlayer) {
            runCatching {
                val sm = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
                if (sm.top !is OnyxCarVideoScreen) sm.push(OnyxCarVideoScreen(carContext))
            }
        }
    }

    /** Démarre (ou ramène au premier plan) l'app ONYX sur le téléphone. */
    private fun launchPhoneApp() {
        runCatching {
            val ctx = carContext.applicationContext
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (launch != null) {
                ctx.startActivity(launch)
                Log.i("OnyxCarSession", "app téléphone démarrée depuis la voiture")
            }
        }.onFailure { Log.w("OnyxCarSession", "launchPhoneApp KO: ${it.message}") }
    }
}
