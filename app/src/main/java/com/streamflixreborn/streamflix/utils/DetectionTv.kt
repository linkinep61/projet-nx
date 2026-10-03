package com.streamflixreborn.streamflix.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.streamflixreborn.streamflix.BuildConfig

/**
 * 2026-10-04 (retour testeur : boîtiers Android TV génériques qui recevaient l'interface MOBILE
 *   avec la version universelle → aucun cadre de focus, navigation à l'aveugle à la télécommande).
 *   Avant, seul FEATURE_LEANBACK décidait ; beaucoup de boîtiers ne le déclarent pas.
 *   Un seul endroit décide désormais de l'interface ; les builds TV / mobile forcés (APP_LAYOUT)
 *   gardent la priorité.
 */
object DetectionTv {

    private const val PREFS = "detection_interface"
    private const val CLE_CHOIX = "choix"   // "auto" | "tv" | "mobile"

    /** Choix de l'utilisateur (paramètres ou question « télécommande ») : auto, tv ou mobile. */
    fun choix(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLE_CHOIX, "auto") ?: "auto"

    fun enregistrerChoix(ctx: Context, valeur: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CLE_CHOIX, valeur).apply()
    }

    /** Build universel (ni TV ni mobile forcé) : seul lui laisse le choix de l'interface. */
    fun buildUniversel(): Boolean = BuildConfig.APP_LAYOUT != "tv" && BuildConfig.APP_LAYOUT != "mobile"

    /** true = interface TV. Ordre : build forcé (APP_LAYOUT) > choix mémorisé > détection matérielle. */
    fun interfaceTv(ctx: Context): Boolean = when (BuildConfig.APP_LAYOUT) {
        "tv" -> true
        "mobile" -> false
        else -> when (choix(ctx)) {
            "tv" -> true
            "mobile" -> false
            else -> estUneTv(ctx)
        }
    }

    /** Détection matérielle : Leanback, type télévision, mode TV d'Android, ou pas d'écran tactile. */
    fun estUneTv(ctx: Context): Boolean = try {
        val pm = ctx.packageManager
        when {
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> true
            pm.hasSystemFeature("android.software.leanback_only") -> true
            pm.hasSystemFeature("android.hardware.type.television") -> true
            (ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType ==
                Configuration.UI_MODE_TYPE_TELEVISION -> true
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) -> true
            else -> false
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * 2026-10-04 (user : « si on essaie de bouger que la télécommande, ça règle tout ») : filet de
     *   sécurité pour les box qui échappent à la détection. Interface mobile ouverte, aucun appui
     *   au doigt, et la 1re action est une flèche / OK de télécommande → on propose l'interface TV.
     *   Une seule fois : la réponse est mémorisée (modifiable dans Paramètres → Interface).
     */
    fun peutProposerTv(ctx: Context): Boolean = buildUniversel() && choix(ctx) == "auto"

    fun estToucheTelecommande(code: Int): Boolean = code == android.view.KeyEvent.KEYCODE_DPAD_UP ||
        code == android.view.KeyEvent.KEYCODE_DPAD_DOWN || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
        code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
        code == android.view.KeyEvent.KEYCODE_ENTER

    fun proposerInterfaceTv(activite: android.app.Activity) {
        try {
            android.app.AlertDialog.Builder(activite)
                .setTitle("Télécommande détectée")
                .setMessage("Cet appareil semble branché sur une TV. Passer à l'interface TV, faite pour la télécommande ?")
                .setPositiveButton("Interface TV") { _, _ ->
                    enregistrerChoix(activite, "tv")
                    activite.startActivity(android.content.Intent(activite,
                        com.streamflixreborn.streamflix.activities.main.MainTvActivity::class.java))
                    activite.finish()
                }
                .setNegativeButton("Rester en mobile") { _, _ -> enregistrerChoix(activite, "mobile") }
                .setCancelable(false)
                .show()
        } catch (_: Throwable) {}
    }

    /** Paramètres → Interface : Automatique / TV / Mobile, puis redémarrage sur la bonne interface. */
    fun choisirDansParametres(activite: android.app.Activity) {
        val valeurs = arrayOf("auto", "tv", "mobile")
        val libelles = arrayOf("Automatique", "Interface TV (télécommande)", "Interface mobile (tactile)")
        val actuel = valeurs.indexOf(choix(activite)).coerceAtLeast(0)
        android.app.AlertDialog.Builder(activite)
            .setTitle("Interface")
            .setSingleChoiceItems(libelles, actuel) { dlg, i ->
                dlg.dismiss()
                if (valeurs[i] != choix(activite)) {
                    enregistrerChoix(activite, valeurs[i])
                    activite.startActivity(android.content.Intent(activite,
                        com.streamflixreborn.streamflix.activities.SplashActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    activite.finish()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    fun libelleChoix(ctx: Context): String = when (choix(ctx)) {
        "tv" -> "Interface TV"
        "mobile" -> "Interface mobile"
        else -> if (estUneTv(ctx)) "Automatique (TV détectée)" else "Automatique (mobile détecté)"
    }
}