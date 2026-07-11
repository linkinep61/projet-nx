package com.streamflixreborn.streamflix.activities

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.activities.main.MainTvActivity

/**
 * 2026-07-06 (user « splash 2-3 s avec le logo animé — un petit va-et-vient
 *   avance/recule ») : écran de démarrage animé. Il masque le jank de boot ET
 *   donne une AVANCE aux pré-chauffages en fond (cookie CF, challenge des pages
 *   détail DessinAnime, warms providers) déjà lancés depuis StreamFlixApp.onCreate.
 *   Après ~3,4 s, on route vers le Main (TV si leanback, sinon mobile).
 *   Activity « nue » (pas AppCompat) pour rester indépendant du thème choisi.
 */
class SplashActivity : Activity() {

    @Volatile private var routed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_splash)

        val logo = findViewById<ImageView>(R.id.splash_logo)
        // État initial : petit + transparent.
        logo.scaleX = 0.6f
        logo.scaleY = 0.6f
        logo.alpha = 0f

        // 1) Entrée : fondu + zoom jusqu'à 1.0.
        logo.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(550L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { startPulse(logo) }
            .start()

        // 2) Route vers le Main après ~3,4 s (anim + avance aux warms).
        Handler(Looper.getMainLooper()).postDelayed({ goToMain(logo) }, 3400L)
    }

    /** Va-et-vient « avance / recule » : le logo grossit et monte, puis revient — 2 fois. */
    private fun startPulse(logo: View) {
        try {
            val anim = ObjectAnimator.ofPropertyValuesHolder(
                logo,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.13f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.13f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -24f, 0f)
            )
            anim.duration = 1150L
            anim.repeatCount = 1
            anim.interpolator = AccelerateDecelerateInterpolator()
            anim.start()
        } catch (_: Throwable) {}
    }

    private fun goToMain(logo: View) {
        if (routed) return
        routed = true
        val cls = try {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
                MainTvActivity::class.java else MainMobileActivity::class.java
        } catch (_: Throwable) {
            MainMobileActivity::class.java
        }
        val launch = {
            try {
                startActivity(Intent(this, cls))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } catch (_: Throwable) {}
            finish()
        }
        try {
            logo.animate().alpha(0f).setDuration(200L).withEndAction { launch() }.start()
        } catch (_: Throwable) {
            launch()
        }
    }

    // Empêche de revenir sur le splash après coup.
    override fun onBackPressed() { /* no-op pendant le splash */ }
}
