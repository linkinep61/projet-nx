package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.children
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

/**
 * 2026-06-09 (user "il y aurait été préférable que dans paramètres apparence
 * que les utilisateurs puissent personnaliser l'application comme ils veulent
 * mettre des fonds d'écran qu'ils ont envie") : système de fond d'écran
 * personnalisé.
 *
 * 3 sources d'image (toutes optionnelles) :
 *   - URI persistante via Intent.ACTION_OPEN_DOCUMENT (picker système)
 *   - Auto-scan du dossier Pictures/Streamflix/wallpapers/
 *   - (à venir) galerie intégrée CDN
 *
 * L'image est appliquée comme couche de fond du root layout. Tout ce qui
 * existe déjà (carrousel, listes, cards) reste devant. Sur les écrans IPTV
 * qui n'ont pas de carrousel, l'image devient visible.
 *
 * Le toggle "Carrousel comme fond" reste indépendant : s'il est activé sur
 * un provider VOD, la bannière en focus prend la priorité.
 */
object AppearanceManager {
    private const val TAG = "AppearanceManager"
    private const val PREF_NAME = "appearance"
    private const val KEY_WALLPAPER_URI = "wallpaper_uri"
    private const val KEY_WALLPAPER_DIM = "wallpaper_dim"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Sauvegarde l'URI choisie via le picker. takePersistableUriPermission
     *  est appelé séparément par l'appelant (a besoin du Intent.flags). */
    fun setWallpaperUri(ctx: Context, uri: Uri?) {
        prefs(ctx).edit { putString(KEY_WALLPAPER_URI, uri?.toString()) }
    }

    fun getWallpaperUri(ctx: Context): Uri? {
        val s = prefs(ctx).getString(KEY_WALLPAPER_URI, null) ?: return null
        return try { Uri.parse(s) } catch (_: Throwable) { null }
    }

    fun clearWallpaper(ctx: Context) {
        val uri = getWallpaperUri(ctx)
        if (uri != null) {
            // 2026-06-09 : libérer la permission persistée si on en avait pris une.
            try {
                ctx.contentResolver.releasePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {}
        }
        prefs(ctx).edit { remove(KEY_WALLPAPER_URI) }
    }

    /** Niveau d'assombrissement choisi par l'user. */
    enum class DimLevel(val alpha: Int) {
        NONE(0), LIGHT(77), MEDIUM(128), STRONG(178);

        companion object {
            fun fromString(s: String?): DimLevel = when (s) {
                "light" -> LIGHT
                "medium" -> MEDIUM
                "strong" -> STRONG
                else -> NONE
            }
        }
    }

    fun getDimLevel(ctx: Context): DimLevel =
        DimLevel.fromString(prefs(ctx).getString(KEY_WALLPAPER_DIM, "none"))

    fun setDimLevel(ctx: Context, level: String) {
        prefs(ctx).edit { putString(KEY_WALLPAPER_DIM, level) }
    }

    /** True si un wallpaper est configuré. */
    fun hasWallpaper(ctx: Context): Boolean = getWallpaperUri(ctx) != null

    /**
     * Applique le fond d'écran perso sur le root du fragment. Crée (ou
     * réutilise) un ImageView "appearance_wallpaper_bg" en INDEX 0 du parent
     * (= derrière tout le reste). Si pas de wallpaper configuré → retire.
     *
     * Pour les writers : le rootView doit être un FrameLayout ou un
     * ConstraintLayout (= un ViewGroup qui supporte addView(view, 0)).
     */
    @JvmStatic
    fun applyTo(rootView: View) {
        val ctx = rootView.context.applicationContext
        val parent = rootView as? android.view.ViewGroup ?: return

        val uri = getWallpaperUri(ctx)

        // 2026-06-09 (user "fond d'écran sur OPPO pas appliqué") : sur mobile,
        //   il existe un iv_home_background dans les XML avec src par défaut
        //   (bg_wallpaper_mobile) qui MASQUAIT notre wallpaper personnel.
        //   On le cible directement pour y écrire le wallpaper perso (ou
        //   restaurer le default si pas de wallpaper).
        val ivHomeBgId = ctx.resources.getIdentifier("iv_home_background", "id", ctx.packageName)
        val ivHomeBg = if (ivHomeBgId != 0) rootView.findViewById<ImageView>(ivHomeBgId) else null
        if (ivHomeBg != null) {
            try {
                if (uri != null) {
                    Glide.with(rootView).load(uri)
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .into(ivHomeBg)
                } else {
                    // 2026-07-11 (user "on supprime notre fond d'écran, fond noir tout simplement") :
                    //   plus de JPG bundle → fond noir uni quand aucun wallpaper perso n'est choisi.
                    ivHomeBg.setImageResource(com.streamflixreborn.streamflix.R.drawable.bg_app_black)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to apply wallpaper to iv_home_background: ${e.message}")
            }
            // gère aussi le dim ci-dessous (continuera vers la logique dim)
        }

        // Cherche un ImageView de fond déjà installé par nous.
        var bg = parent.children.firstOrNull { it.tag == TAG_WALLPAPER_BG } as? ImageView
        var dim = parent.children.firstOrNull { it.tag == TAG_WALLPAPER_DIM } as? View

        if (uri == null) {
            // Pas de wallpaper → nettoie si présent.
            if (bg != null) parent.removeView(bg)
            if (dim != null) parent.removeView(dim)
            return
        }

        if (bg == null) {
            bg = ImageView(ctx).apply {
                tag = TAG_WALLPAPER_BG
                // 2026-06-10 (crash rotation : "All children of ConstraintLayout
                //   must have ids to use ConstraintSet") : ID obligatoire pour
                //   éviter le crash quand updateMiniPlayerLayout fait ConstraintSet.clone().
                id = android.view.View.generateViewId()
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val lp = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            parent.addView(bg, 0, lp)
        }

        try {
            Glide.with(rootView)
                .load(uri)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(bg)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load wallpaper: ${e.message}")
        }

        // Couche d'assombrissement (juste après le bg).
        val dimLevel = getDimLevel(ctx)
        if (dimLevel == DimLevel.NONE) {
            if (dim != null) parent.removeView(dim)
        } else {
            if (dim == null) {
                dim = View(ctx).apply {
                    tag = TAG_WALLPAPER_DIM
                    id = android.view.View.generateViewId()
                }
                val lp = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Insère juste après le bg (index 1).
                parent.addView(dim, 1, lp)
            }
            dim.background = ColorDrawable(Color.argb(dimLevel.alpha, 0, 0, 0))
        }
    }

    private const val TAG_WALLPAPER_BG = "appearance_wallpaper_bg"
    private const val TAG_WALLPAPER_DIM = "appearance_wallpaper_dim"

    private fun isTvLayout(ctx: android.content.Context): Boolean = try {
        ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    } catch (_: Throwable) { false }

    /**
     * 2026-06-09 : auto-scan du dossier dédié. Retourne la liste des images
     * trouvées dans Pictures/Streamflix/wallpapers/. Pas utilisé encore pour
     * v1 simple — l'user passe par le picker système. À utiliser plus tard
     * pour une grille de preview interne.
     */
    fun scanDedicatedFolder(ctx: Context): List<Uri> {
        val results = mutableListOf<Uri>()
        try {
            val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            )
            val streamflixDir = java.io.File(picturesDir, "Streamflix/wallpapers")
            if (streamflixDir.isDirectory) {
                streamflixDir.listFiles { f ->
                    val n = f.name.lowercase()
                    n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                        n.endsWith(".png") || n.endsWith(".webp")
                }?.forEach { results.add(Uri.fromFile(it)) }
            }
        } catch (_: Throwable) {}
        return results
    }
}
