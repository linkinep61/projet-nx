package com.streamflixreborn.streamflix.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.activities.main.MainTvActivity

/**
 * Point d'entrée de l'APK universel (manifeste par défaut) : choisit l'écran télé ou téléphone.
 *
 * 2026-10-28 (user : « on enlève l'autre menu qui sert à rien ») : ce n'est plus un écran
 *   animé de 3,4 s. Les préchargements partent de StreamFlixApp.onCreate de toute façon, et
 *   l'interrupteur à distance est vérifié par MainMobileActivity / MainTvActivity
 *   (EtatApp.garder). Il ne reste que l'écran de démarrage d'Android (icône de l'appli), puis
 *   l'accueil directement — comme l'APK télé. Pendant un événement (Halloween, Noël…), l'animation
 *   avec le logo de l'événement s'affiche par-dessus l'écran principal (cf. AnimationEvenement).
 */
class SplashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cls = try {
            if (com.streamflixreborn.streamflix.utils.DetectionTv.interfaceTv(this))
                MainTvActivity::class.java else MainMobileActivity::class.java
        } catch (_: Throwable) { MainMobileActivity::class.java }
        try {
            startActivity(Intent(this, cls))
            overridePendingTransition(0, 0)
        } catch (_: Throwable) {}
        finish()
    }
}
