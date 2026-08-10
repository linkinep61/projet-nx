package com.streamflixreborn.streamflix.activities

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import com.streamflixreborn.streamflix.utils.EtatApp

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
    @Volatile private var bloque = false

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

        // ── INTERRUPTEUR GÉNÉRAL À DISTANCE (2026-08-11) ────────────────
        // 1) Coupure DÉJÀ connue → on bloque tout de suite, sans réseau ni attente.
        val memorise = EtatApp.etatMemorise(this)
        if (!memorise.actif) afficherBlocage(memorise.message)

        // 2) DANS TOUS LES CAS on interroge le serveur en fond — y compris quand
        //    on vient d'afficher le blocage. ⚠ 2026-08-11 : ma 1ʳᵉ version faisait
        //    un `return` ici, donc une app bloquée ne redemandait plus jamais son
        //    autorisation : réactiver côté serveur n'aurait rien débloqué sans un
        //    appui manuel sur « Réessayer ». La réactivation doit être aussi
        //    automatique que la coupure.
        //    Les 3,4 s du splash couvrent le délai max de 4 s de l'appel, donc le
        //    démarrage normal n'est pas rallongé.
        Thread {
            val etat = EtatApp.interroger(applicationContext)
            runOnUiThread {
                when {
                    !etat.actif && !bloque -> afficherBlocage(etat.message)
                    etat.actif && bloque -> relancerApresReactivation()
                }
            }
        }.apply { isDaemon = true }.start()

        // 3) Route vers le Main après ~3,4 s (anim + avance aux warms).
        Handler(Looper.getMainLooper()).postDelayed({ goToMain(logo) }, 3400L)
    }

    /**
     * Écran de blocage : l'app s'arrête ici, aucune activité suivante n'est
     * lancée. `routed = true` neutralise le routage programmé à 3,4 s.
     * Vue construite en code pour ne dépendre d'aucun thème ni layout.
     */
    private fun afficherBlocage(message: String?) {
        if (bloque) return
        bloque = true
        routed = true
        try {
            val densite = resources.displayMetrics.density
            fun dp(v: Int) = (v * densite).toInt()

            val racine = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#0B0D10"))
                setPadding(dp(32), dp(32), dp(32), dp(32))
            }
            racine.addView(TextView(this).apply {
                text = getString(R.string.app_name)
                setTextColor(Color.parseColor("#EEF2F6"))
                textSize = 21f
                gravity = Gravity.CENTER
            })
            racine.addView(TextView(this).apply {
                text = message?.takeIf { it.isNotBlank() }
                    ?: "L'application est momentanément indisponible."
                setTextColor(Color.parseColor("#9AA6B2"))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(26))
            })
            racine.addView(Button(this).apply {
                text = "Réessayer"
                setOnClickListener {
                    isEnabled = false
                    text = "Vérification…"
                    Thread {
                        val etat = EtatApp.interroger(applicationContext)
                        runOnUiThread {
                            if (etat.actif) {
                                relancerApresReactivation()
                            } else {
                                isEnabled = true
                                text = "Réessayer"
                            }
                        }
                    }.apply { isDaemon = true }.start()
                }
            })
            setContentView(racine)
        } catch (_: Throwable) {
            finish()   // même si l'écran ne s'affiche pas, on n'entre pas dans l'app
        }
    }

    /**
     * Le serveur a redonné son feu vert alors qu'on affichait le blocage :
     * on relance le splash à neuf, qui repartira sur le chemin normal.
     */
    private fun relancerApresReactivation() {
        bloque = false
        routed = false
        try {
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
        } catch (_: Throwable) { /* on reste sur place plutôt que de planter */ }
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
        if (routed || bloque) return
        // Dernier garde-fou : si la réponse « coupée » est arrivée juste avant
        // l'échéance des 3,4 s, on bloque au lieu d'entrer dans l'app.
        if (!EtatApp.etatMemorise(this).actif) {
            afficherBlocage(EtatApp.etatMemorise(this).message)
            return
        }
        // Auto-détection : TV si leanback, sinon mobile.
        val cls = try {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
                MainTvActivity::class.java else MainMobileActivity::class.java
        } catch (_: Throwable) { MainMobileActivity::class.java }
        route(logo, cls)
    }

    private fun route(logo: View, cls: Class<*>) {
        if (routed) return
        routed = true
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
