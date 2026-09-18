package com.streamflixreborn.streamflix.activities

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.EtatApp

/**
 * 2026-09-19 — ÉCRAN DE BLOCAGE DE L'INTERRUPTEUR GÉNÉRAL.
 *
 * POURQUOI CETTE ACTIVITÉ EXISTE : jusqu'ici l'interrupteur (EtatApp) n'était
 * consulté QUE par SplashActivity. Or SplashActivity n'est le point d'entrée
 * que du manifeste par défaut (`app/src/main/AndroidManifest.xml`, APP_LAYOUT
 * vide). Les APK réellement distribués utilisent les manifestes `tv/` et
 * `mobile/`, dont le lanceur est MainTvActivity / MainMobileActivity : le
 * splash n'y existe pas, donc l'interrupteur n'était JAMAIS interrogé et
 * couper l'app n'avait aucun effet sur ces versions.
 *
 * Le blocage est désormais posé dans les Main*, qui sont le point d'entrée
 * commun à TOUS les manifestes — c'est le seul endroit qu'aucune variante ne
 * peut contourner. Elles délèguent l'écran ici.
 */
class BlocageActivity : Activity() {

    companion object {
        const val EXTRA_MESSAGE = "message"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        construire(intent?.getStringExtra(EXTRA_MESSAGE))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        construire(intent?.getStringExtra(EXTRA_MESSAGE))
    }

    private fun construire(message: String?) {
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
                ?: EtatApp.etatMemorise(this@BlocageActivity).message
                ?: "L'application est momentanément indisponible."
            setTextColor(Color.parseColor("#9AA6B2"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(26))
        })
        val bouton = Button(this).apply {
            text = "Réessayer"
            // Focusable = navigable à la télécommande sur box TV.
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                isEnabled = false
                text = "Vérification…"
                Thread {
                    val etat = EtatApp.interroger(applicationContext)
                    runOnUiThread {
                        if (etat.actif) {
                            relancerApplication()
                        } else {
                            isEnabled = true
                            text = "Réessayer"
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        racine.addView(bouton)
        setContentView(racine)
        bouton.requestFocus()

        // RÉACTIVATION AUTOMATIQUE — aussi importante que la coupure. Un refus
        // est mémorisé localement (sinon le mode avion suffirait à passer
        // outre) : sans cette interrogation à l'ouverture de l'écran, remettre
        // le serveur sur `actif:true` ne débloquerait rien tant que personne
        // n'appuie sur « Réessayer ». C'est le rôle que tenait SplashActivity.
        Thread {
            val etat = EtatApp.interroger(applicationContext)
            if (etat.actif) {
                runOnUiThread { if (!isFinishing && !isDestroyed) relancerApplication() }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Le serveur a redonné son feu vert : on repart du point d'entrée du
     * lanceur, quel qu'il soit dans cette variante d'APK (MainTvActivity,
     * MainMobileActivity ou SplashActivity), plutôt que d'en coder un en dur.
     */
    private fun relancerApplication() {
        try {
            val depart = packageManager.getLaunchIntentForPackage(packageName)
            if (depart != null) {
                depart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(depart)
            }
        } catch (_: Throwable) { /* on reste sur place plutôt que de planter */ }
        finish()
    }

    /** Le retour ferme l'app : on ne laisse jamais passer derrière cet écran. */
    override fun onBackPressed() {
        finishAffinity()
    }
}
