package com.streamflixreborn.streamflix.utils

import android.app.Activity
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference

/**
 * 2026-07-09 (user "dans Paramètres > Apparence, mets un bouton pour réduire la
 * luminosité de l'application ENTIÈRE") — réglage [UserPreferences.appDim] (0-90).
 *
 * 2026-10-27 (user : « Moi j'aurais pas assombri les textes de l'interface, ça aurait permis
 * d'assombrir le fond d'écran mais pas les textes et les logos ») — CHANGEMENT DE PRINCIPE :
 * l'ancien voile noir posé PAR-DESSUS tout le decorView est supprimé. Désormais seules les
 * IMAGES DE FOND sont assombries (filtre MULTIPLY sur l'ImageView) :
 *   - le fond d'écran perso posé par AppearanceManager (tag "appearance_wallpaper_bg") ;
 *   - iv_home_background, iv_providers_default_bg, iv_profile_picker_default_bg.
 * Textes, logos, jaquettes et boutons gardent leur pleine luminosité.
 *
 * Les écrans changent à l'intérieur d'une même activité (fragments) : un écouteur de mise en
 * page sur le decorView repasse sur l'arbre. Le parcours ne descend PAS dans les listes
 * (RecyclerView / ListView / GridView) → coût négligeable même sur les petites box.
 */
object AppDimManager {
    private const val TAG_OVERLAY = "app_dim_overlay"            // ancien voile, retiré s'il traîne
    private const val TAG_WALLPAPER_BG = "appearance_wallpaper_bg"
    private val NOMS_FONDS = listOf("iv_home_background", "iv_providers_default_bg", "iv_profile_picker_default_bg")
    private var current: WeakReference<Activity>? = null
    private val ecouteurs = java.util.WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>()

    @JvmStatic
    fun apply(activity: Activity) {
        current = WeakReference(activity)
        try {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            decor.findViewWithTag<View>(TAG_OVERLAY)?.let { decor.removeView(it) }
            assombrirFonds(decor)
            if (!ecouteurs.containsKey(decor)) {
                // Référence faible : si l'écouteur (valeur de la WeakHashMap) tenait le decorView
                // (sa clé), l'entrée ne serait jamais libérée → l'activité fermée restait en mémoire.
                val decorFaible = WeakReference<View>(decor)
                val l = ViewTreeObserver.OnGlobalLayoutListener { decorFaible.get()?.let { assombrirFonds(it) } }
                decor.viewTreeObserver.addOnGlobalLayoutListener(l)
                ecouteurs[decor] = l
            }
        } catch (_: Throwable) {
            // decorView pas prêt / activité en cours de destruction : ignorer.
        }
    }

    /** Ré-applique sur l'activité courante (aperçu live quand on bouge le slider). */
    @JvmStatic
    fun reapply() {
        current?.get()?.let { apply(it) }
    }

    /** Assombrit les images de fond trouvées sous [racine] selon UserPreferences.appDim. */
    @JvmStatic
    fun assombrirFonds(racine: View) {
        try {
            val dim = UserPreferences.appDim.coerceIn(0, 90)
            val ctx = racine.context
            val ids = NOMS_FONDS.map { ctx.resources.getIdentifier(it, "id", ctx.packageName) }
                .filter { it != 0 }.toSet()
            val v = 255 - dim * 255 / 100
            parcourir(racine) { iv ->
                if (iv.tag == TAG_WALLPAPER_BG || iv.id in ids) {
                    val voulu = if (dim <= 0) null else v
                    if (iv.getTag(TAG_ID) != voulu) {
                        iv.setTag(TAG_ID, voulu)
                        if (voulu == null) iv.clearColorFilter()
                        else iv.setColorFilter(Color.rgb(voulu, voulu, voulu), PorterDuff.Mode.MULTIPLY)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private val TAG_ID = com.streamflixreborn.streamflix.R.id.app_dim_niveau

    private fun parcourir(v: View, action: (ImageView) -> Unit) {
        if (v is ImageView) { action(v); return }
        if (v !is ViewGroup || v is RecyclerView || v is AbsListView) return
        for (i in 0 until v.childCount) parcourir(v.getChildAt(i), action)
    }
}
