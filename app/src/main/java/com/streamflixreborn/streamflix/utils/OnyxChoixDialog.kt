package com.streamflixreborn.streamflix.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 2026-08-04 (user : « quand on clique sur Films, l'affichage n'est pas le même que celui
 * quand on clique sur Trier par année ; il faudrait que le premier soit aussi joli »).
 *
 * Feuille de choix COMMUNE, au style maison — celui du menu radio, du sélecteur de dossiers
 * et du sélecteur d'années. Elle remplace les `AlertDialog` système, dont l'apparence tranchait
 * nettement dès qu'on enchaînait les deux.
 *
 * Mutualisée volontairement : le sélecteur de genre existe en TROIS exemplaires (menu TV,
 * Films mobile, Séries mobile). Recopier le style dans chacun garantissait qu'ils divergent
 * à la première retouche.
 *
 * ⚠ LISERÉ DE FOCUS PERMANENT en `StateListDrawable`, jamais conditionné à une détection
 *   « appareil TV » : les box AOSP/RockChip ne se déclarent pas comme téléviseurs, et gater
 *   le liseré sur `UI_MODE_TYPE_TELEVISION` rendait le curseur invisible à la télécommande
 *   (bug constaté le 29 juillet). Posé en `foreground` sur `focused`/`pressed`, il n'apparaît
 *   jamais au doigt.
 */
object OnyxChoixDialog {

    private const val SURFACE = 0xFF15171C.toInt()
    private const val CARTE = 0xFF1E2128.toInt()
    private const val ACCENT = 0xFFE23B3B.toInt()
    private const val TEXTE = 0xFFF2F3F5.toInt()
    private const val TEXTE_FAIBLE = 0xFF9AA0A6.toInt()

    /**
     * @param libelle texte de la ligne.
     * @param selectionne pose la coche et colore la ligne à l'accent.
     * @param separateurApres trait fin sous la ligne — sert à isoler une entrée d'une autre
     *   nature (par exemple l'année en tête d'une liste de genres).
     */
    data class Option(
        val libelle: String,
        val selectionne: Boolean = false,
        val separateurApres: Boolean = false,
        /** Petit intitulé affiché AU-DESSUS de la ligne (« SOURCE », « TRIER PAR »…). */
        val section: String? = null,
    )

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, ctx: Context) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dp(radiusDp).toFloat()
    }

    private fun focusRing(ctx: Context): StateListDrawable {
        fun ring() = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = ctx.dp(12).toFloat()
            setStroke(ctx.dp(2), Color.WHITE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ring())
            addState(intArrayOf(android.R.attr.state_pressed), ring())
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
    }

    /**
     * @param icone pastille d'en-tête (emoji).
     * @param onChoisi reçoit l'INDEX dans [options]. La feuille se ferme avant l'appel.
     */
    fun show(
        context: Context,
        icone: String,
        titre: String,
        sousTitre: String? = null,
        options: List<Option>,
        onChoisi: (Int) -> Unit,
    ) {
        val ctx = context

        val racine = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 20, ctx)
            setPadding(ctx.dp(18), ctx.dp(16), ctx.dp(18), ctx.dp(12))
        }

        val dialog = Dialog(ctx).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }

        // ── En-tête : pastille + titre + sous-titre + compteur ────────────────
        val entete = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        entete.addView(TextView(ctx).apply {
            text = icone
            textSize = 20f
            background = rounded(CARTE, 10, ctx)
            setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
        })
        entete.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(12), 0, 0, 0)
            addView(TextView(ctx).apply {
                text = titre
                textSize = 17f
                setTextColor(TEXTE)
            })
            if (!sousTitre.isNullOrBlank()) addView(TextView(ctx).apply {
                text = sousTitre
                textSize = 12f
                setTextColor(TEXTE_FAIBLE)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        entete.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 15f
            setTextColor(TEXTE_FAIBLE)
            background = rounded(CARTE, 10, ctx)
            foreground = focusRing(ctx)
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            isFocusable = true
            isFocusableInTouchMode = false
            setOnClickListener { dialog.dismiss() }
        })
        racine.addView(entete)

        // ── Lignes ────────────────────────────────────────────────────────────
        val liste = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(12), 0, 0)
        }
        var aFocaliser: View? = null
        var focusSurSelection = false
        options.forEachIndexed { index, option ->
            if (!option.section.isNullOrBlank()) liste.addView(TextView(ctx).apply {
                text = option.section.uppercase()
                textSize = 11f
                letterSpacing = 0.14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(TEXTE_FAIBLE)
                setPadding(ctx.dp(4), ctx.dp(2), 0, ctx.dp(6))
            })
            val ligne = TextView(ctx).apply {
                text = if (option.selectionne) "✓  ${option.libelle}" else option.libelle
                textSize = 16f
                setTextColor(if (option.selectionne) ACCENT else TEXTE)
                background = rounded(CARTE, 12, ctx)
                foreground = focusRing(ctx)
                setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
                isFocusable = true
                isFocusableInTouchMode = false
                setOnClickListener {
                    dialog.dismiss()
                    onChoisi(index)
                }
            }
            liste.addView(ligne, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ctx.dp(8) })

            if (option.separateurApres) {
                liste.addView(View(ctx).apply {
                    setBackgroundColor(0xFF2A2E36.toInt())
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)
                ).apply { bottomMargin = ctx.dp(10) })
            }
            // Focus sur la PREMIÈRE ligne cochée (une feuille peut en cocher une par partie).
            if (aFocaliser == null || (option.selectionne && !focusSurSelection)) {
                aFocaliser = ligne
                if (option.selectionne) focusSurSelection = true
            }
        }

        // 2026-10-26 (user : « sur mobile, le menu est tout écrasé ») : 62 % × 80 % convient à
        //   un écran en largeur (TV), pas à un téléphone tenu droit — fenêtre étroite, titre sur
        //   deux lignes, grand vide sous la liste. En portrait : presque toute la largeur et une
        //   hauteur ajustée au contenu (la liste défile si elle dépasse l'écran).
        val dm = ctx.resources.displayMetrics
        val portrait = dm.heightPixels > dm.widthPixels
        racine.addView(ScrollView(ctx).apply {
            isFillViewport = true
            addView(liste)
        }, if (portrait) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
           else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dialog.setContentView(racine)
        dialog.window?.setLayout(
            (dm.widthPixels * (if (portrait) 0.92f else 0.62f)).toInt(),
            if (portrait) ViewGroup.LayoutParams.WRAP_CONTENT else (dm.heightPixels * 0.80f).toInt()
        )
        dialog.show()
        // Focus sur l'élément sélectionné : à la télécommande, on repart d'où on en est.
        aFocaliser?.post { aFocaliser?.requestFocus() }
    }
}
