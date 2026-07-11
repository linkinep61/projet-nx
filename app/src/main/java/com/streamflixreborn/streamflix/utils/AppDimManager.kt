package com.streamflixreborn.streamflix.utils

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference

/**
 * 2026-07-09 (user "dans Paramètres > Apparence, mets un bouton pour réduire la
 * luminosité de l'application ENTIÈRE, comme le slider Luminosité du carrousel") :
 *
 * Assombrit TOUTE l'application (et pas juste le carrousel ou le lecteur) en posant
 * un overlay noir semi-transparent sur le `decorView` de CHAQUE activité. Le niveau
 * vient de [UserPreferences.appDim] (0-90). 0 = normal, 90 = très sombre.
 *
 * L'overlay :
 *   - couvre tout le decorView (MATCH_PARENT) → status bar + contenu inclus ;
 *   - est NON cliquable / NON focusable → laisse passer touches et D-pad (TV) ;
 *   - est ré-appliqué à chaque `onActivityResumed` (StreamFlixApp) → toute nouvelle
 *     activité hérite du réglage.
 *
 * [apply] est aussi rappelé en direct depuis les Paramètres pour un aperçu immédiat.
 */
object AppDimManager {
    private const val TAG_OVERLAY = "app_dim_overlay"
    private var current: WeakReference<Activity>? = null

    /** Pose/actualise/retire l'overlay sur l'activité donnée selon UserPreferences.appDim. */
    @JvmStatic
    fun apply(activity: Activity) {
        current = WeakReference(activity)
        try {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val dim = UserPreferences.appDim.coerceIn(0, 90)
            val existing = decor.findViewWithTag<View>(TAG_OVERLAY)

            if (dim <= 0) {
                if (existing != null) decor.removeView(existing)
                return
            }

            val overlay = existing ?: View(activity).apply {
                tag = TAG_OVERLAY
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                // 2026-07-09 : ne jamais intercepter les évènements — l'overlay est
                //   purement décoratif ; touches et D-pad doivent traverser.
                setOnTouchListener { _, _ -> false }
            }.also {
                decor.addView(
                    it,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }

            // alpha 0-90 % → 0-229 (jamais 255 = jamais totalement noir).
            val alpha = (dim * 255 / 100).coerceIn(0, 229)
            overlay.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
            overlay.bringToFront() // reste au-dessus du contenu ajouté ensuite
        } catch (_: Throwable) {
            // decorView pas prêt / activité en cours de destruction : ignorer.
        }
    }

    /** Ré-applique sur l'activité courante (aperçu live quand on bouge le slider). */
    @JvmStatic
    fun reapply() {
        current?.get()?.let { apply(it) }
    }
}
