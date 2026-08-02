package com.streamflixreborn.streamflix.utils

import android.app.AlertDialog
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
import com.streamflixreborn.streamflix.providers.MyIptvProvider

/**
 * 2026-08-03 (demande user, capture d'un concurrent à l'appui) — GESTIONNAIRE DE GROUPES IPTV.
 *
 * Remplace l'ancien `showIptvCategoryPicker()`, qui n'était qu'un `AlertDialog.setItems` : une
 * liste plate, sans ordre, sans masquage, et uniquement pour les chaînes TV.
 *
 * Apporte les quatre manques relevés sur la capture :
 *  - une bascule **TV / Films / Séries** (seul le LIVE était géré) ;
 *  - des flèches pour **réordonner** ;
 *  - un œil pour **masquer** un groupe, plus un « Tout masquer / Tout afficher » ;
 *  - un rendu cohérent avec le reste de l'app plutôt qu'une boîte système.
 *
 * Style repris du menu radio (feuille sombre #15171C, bascule segmentée, lignes en cartes) —
 * gabarit déjà validé, donc rien de neuf à apprendre pour l'utilisateur.
 *
 * ⚠ Focus TV : le liseré est posé en `StateListDrawable` permanent plutôt que conditionné à
 * `UI_MODE_TYPE_TELEVISION`. Les box AOSP/RockChip ne se déclarent PAS comme téléviseurs — le
 * gating par mode UI rendait le curseur invisible à la télécommande (incident déjà rencontré
 * sur le menu radio et le sélecteur de vidéos locales).
 */
object IptvGroupManagerDialog {

    private const val FOND = 0xFF15171C.toInt()
    private const val CARTE = 0xFF1E2128.toInt()
    private const val ACCENT = 0xFFE23B3B.toInt()
    private const val TEXTE = 0xFFECEFF4.toInt()
    private const val TEXTE_FAIBLE = 0xFF9AA0AC.toInt()

    private val TYPES = listOf(
        IptvClassifier.ContentType.LIVE to "TV",
        IptvClassifier.ContentType.MOVIE to "Films",
        IptvClassifier.ContentType.SERIES to "Séries",
    )

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun arrondi(couleur: Int, rayon: Int, c: Context) = GradientDrawable().apply {
        setColor(couleur)
        cornerRadius = dp(c, rayon).toFloat()
    }

    /** Liseré de focus visible seulement au D-pad — invisible au doigt. */
    private fun avecFocus(v: View, c: Context, rayon: Int) {
        val focus = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(c, rayon).toFloat()
            setStroke(dp(c, 2), Color.WHITE)
        }
        v.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focus)
            addState(intArrayOf(android.R.attr.state_pressed), focus)
            addState(intArrayOf(), null)
        }
        v.isFocusable = true
    }

    /**
     * @param onChanged appelé dès qu'un réglage bouge, pour que l'appelant vide le cache d'accueil
     *   et relance `getHome()`. Sans ça le TTL de 5 minutes masquerait le changement.
     */
    fun show(
        context: Context,
        onChanged: () -> Unit,
        onGroupeChoisi: (IptvClassifier.ContentType, String?) -> Unit = { _, _ -> },
    ) {
        var type = IptvClassifier.ContentType.LIVE

        val racine = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = arrondi(FOND, 20, context)
            setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 14))
        }

        // ── En-tête ──────────────────────────────────────────────────────────
        racine.addView(TextView(context).apply {
            text = "🗂  Gérer les groupes"
            setTextColor(TEXTE)
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val sousTitre = TextView(context).apply {
            setTextColor(TEXTE_FAIBLE)
            textSize = 12f
            setPadding(0, dp(context, 3), 0, dp(context, 12))
        }
        racine.addView(sousTitre)

        // ── Bascule TV / Films / Séries ──────────────────────────────────────
        val barre = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = arrondi(CARTE, 12, context)
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
        }
        val onglets = mutableListOf<TextView>()
        racine.addView(barre, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // ── Actions ──────────────────────────────────────────────────────────
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 12), 0, dp(context, 8))
        }
        val btnMasquerTout = TextView(context).apply {
            setTextColor(TEXTE); textSize = 13f
            gravity = Gravity.CENTER
            background = arrondi(CARTE, 10, context)
            setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
            avecFocus(this, context, 10)
        }
        val btnToutes = TextView(context).apply {
            text = "📡  Tout voir"
            setTextColor(TEXTE); textSize = 13f
            gravity = Gravity.CENTER
            background = arrondi(CARTE, 10, context)
            setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
            avecFocus(this, context, 10)
        }
        val btnReinit = TextView(context).apply {
            text = "↺  Réinitialiser"
            setTextColor(TEXTE); textSize = 13f
            gravity = Gravity.CENTER
            background = arrondi(CARTE, 10, context)
            setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
            avecFocus(this, context, 10)
        }
        actions.addView(btnToutes)
        actions.addView(View(context), LinearLayout.LayoutParams(dp(context, 8), 1))
        actions.addView(btnMasquerTout)
        actions.addView(View(context), LinearLayout.LayoutParams(dp(context, 8), 1))
        actions.addView(btnReinit)
        racine.addView(actions)

        // ── Liste ────────────────────────────────────────────────────────────
        val liste = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        racine.addView(ScrollView(context).apply {
            isFillViewport = true
            addView(liste)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 340),
        ))

        val dialog = AlertDialog.Builder(context).setView(racine).create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        )

        // ── Rendu ────────────────────────────────────────────────────────────
        fun redessiner() {
            onglets.forEachIndexed { i, tv ->
                val actif = TYPES[i].first == type
                tv.background = if (actif) arrondi(ACCENT, 9, context) else null
                tv.setTextColor(if (actif) Color.WHITE else TEXTE_FAIBLE)
            }

            val tous = MyIptvProvider.availableCategoriesWithCount(type, inclureMasques = true)
            val masques = IptvGroupPrefsStore.masques(type.name)
            val ordonnes = IptvGroupPrefsStore.ordonner(type.name, tous) { it.first }
            val noms = ordonnes.map { it.first }

            sousTitre.text = if (ordonnes.isEmpty()) "Aucun groupe en cache — recharge la source"
            else "${ordonnes.size} groupes · ${masques.size} masqué${if (masques.size > 1) "s" else ""}"
            btnMasquerTout.text =
                if (masques.size >= ordonnes.size && ordonnes.isNotEmpty()) "👁  Tout afficher"
                else "🚫  Tout masquer"

            liste.removeAllViews()
            ordonnes.forEachIndexed { index, (nom, nb) ->
                val masque = nom in masques
                val ligne = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = arrondi(CARTE, 12, context)
                    setPadding(dp(context, 12), dp(context, 10), dp(context, 10), dp(context, 10))
                    alpha = if (masque) 0.45f else 1f
                }

                // Le libellé est CLIQUABLE : il sélectionne le groupe comme filtre et ferme.
                //   C'est ce qui remplace l'ancien sélecteur — on garde donc les deux usages
                //   dans un seul écran : choisir un groupe à consulter, ou le régler.
                ligne.addView(TextView(context).apply {
                    text = "${MyIptvProvider.prettyCategoryName(nom)}\n$nb chaîne${if (nb > 1) "s" else ""}"
                    setTextColor(if (masque) TEXTE_FAIBLE else TEXTE)
                    textSize = 14f
                    setLineSpacing(0f, 1.15f)
                    avecFocus(this, context, 8)
                    setOnClickListener {
                        onGroupeChoisi(type, nom)
                        dialog.dismiss()
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                fun bouton(libelle: String, action: () -> Unit) = TextView(context).apply {
                    text = libelle
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
                    avecFocus(this, context, 8)
                    setOnClickListener { action(); onChanged(); redessiner() }
                }

                if (index > 0) ligne.addView(bouton("⌃") {
                    IptvGroupPrefsStore.deplacer(type.name, noms, nom, versLeHaut = true)
                })
                if (index < ordonnes.size - 1) ligne.addView(bouton("⌄") {
                    IptvGroupPrefsStore.deplacer(type.name, noms, nom, versLeHaut = false)
                })
                ligne.addView(bouton(if (masque) "🚫" else "👁") {
                    IptvGroupPrefsStore.basculerMasque(type.name, nom)
                })

                liste.addView(ligne, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(context, 8) })
            }
        }

        TYPES.forEachIndexed { i, (t, libelle) ->
            val tv = TextView(context).apply {
                text = libelle
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(context, 10), dp(context, 9), dp(context, 10), dp(context, 9))
                avecFocus(this, context, 9)
                setOnClickListener { type = t; redessiner() }
            }
            onglets.add(tv)
            barre.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        btnToutes.setOnClickListener {
            // 2026-08-04 : valeur EXPLICITE « tout », et non `null`. `null` signifie « aucun
            //   choix » et fait retomber l'accueil sur le premier groupe — c'était le bug.
            onGroupeChoisi(type, MyIptvProvider.TOUS_LES_GROUPES)
            dialog.dismiss()
        }
        btnMasquerTout.setOnClickListener {
            val tous = MyIptvProvider.availableCategoriesWithCount(type, inclureMasques = true)
                .map { it.first }
            val dejaTout = IptvGroupPrefsStore.masques(type.name).size >= tous.size && tous.isNotEmpty()
            IptvGroupPrefsStore.masquerTout(type.name, tous, masquer = !dejaTout)
            onChanged(); redessiner()
        }
        btnReinit.setOnClickListener {
            IptvGroupPrefsStore.reinitialiser(type.name)
            onChanged(); redessiner()
        }

        redessiner()
        dialog.show()
    }
}
