package com.streamflixreborn.streamflix.utils

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 2026-10-28 (user : « pour tous les appareils, mais pour tous les événements ») — l'ancienne
 *   animation de l'écran de démarrage (logo qui apparaît en grossissant, puis va-et-vient deux
 *   fois), affichée PAR-DESSUS l'écran principal (téléphone ou télé) pendant un événement
 *   (cf. Evenements). L'accueil se charge derrière pendant ce temps.
 *
 * Par-dessus l'écran principal plutôt qu'un écran à part : l'APK télé démarre directement sur
 *   MainTvActivity (changer son point d'entrée ferait perdre l'appli des favoris de Projectivy).
 *
 * Une seule fois par lancement de l'appli (pas à chaque rotation / recréation de l'écran).
 * Pendant l'animation, les touches de la télécommande sont ignorées ([enCours], cf.
 * dispatchKeyEvent des écrans principaux) et les appuis tactiles aussi.
 */
object AnimationEvenement {

    @Volatile private var dejaMontree = false
    @Volatile var enCours = false
        private set

    fun afficherSiEvenement(activite: Activity) {
        if (dejaMontree) return
        dejaMontree = true
        val ev = Evenements.actuel() ?: return
        try {
            val decor = activite.window.decorView as ViewGroup
            val d = activite.resources.displayMetrics.density
            val logo = ImageView(activite).apply {
                setImageResource(ev.logo)
                scaleX = 0.6f; scaleY = 0.6f; alpha = 0f
            }
            val voile = FrameLayout(activite).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true          // avale les appuis tactiles
                addView(logo, FrameLayout.LayoutParams((240 * d).toInt(), (240 * d).toInt(), Gravity.CENTER))
            }
            decor.addView(voile, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            enCours = true

            // 1) Entrée : fondu + zoom jusqu'à 1.0, puis 2) va-et-vient deux fois.
            logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(550L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { pulsation(logo) }
                .start()

            // 3) À ~3,4 s : fondu de sortie et retrait (mêmes durées que l'ancien écran).
            voile.postDelayed({
                voile.animate().alpha(0f).setDuration(200L).withEndAction { retirer(decor, voile) }.start()
            }, 3400L)
            // Filet de sécurité : jamais bloqué sous le voile si l'animation ne finit pas.
            voile.postDelayed({ retirer(decor, voile) }, 4500L)
        } catch (_: Throwable) {
            enCours = false
        }
    }

    private fun retirer(decor: ViewGroup, voile: View) {
        enCours = false
        runCatching { if (voile.parent != null) decor.removeView(voile) }
    }

    private fun pulsation(logo: View) {
        try {
            ObjectAnimator.ofPropertyValuesHolder(
                logo,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.13f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.13f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -24f, 0f),
            ).apply {
                duration = 1150L
                repeatCount = 1
                interpolator = AccelerateDecelerateInterpolator()
            }.start()
        } catch (_: Throwable) {}
    }
}
