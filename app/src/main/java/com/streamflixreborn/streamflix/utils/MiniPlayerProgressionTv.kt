package com.streamflixreborn.streamflix.utils

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Avancement du MINI-LECTEUR **TV** (téléviseur / Chromecast uniquement).
 *
 * ── 2026-08-18 (user) ────────────────────────────────────────────────────────
 *   « Le mini-lecteur TV a sa barre décalée sur la gauche pour un meilleur focus,
 *     ça c'est bien, mais je voudrais aussi intégrer la barre de progression à
 *     celle-ci. »
 *
 * La ligne d'avancement fait donc partie du MÊME bandeau que ⛶ ⏸ ✕ : elle hérite
 * de son décalage à gauche et de son fond. Elle n'est PAS focusable — le parcours
 * D-pad reste exactement celui d'avant.
 *
 * Sur un DIRECT (durée inconnue), la ligne est masquée : il n'y a rien à avancer.
 * Rien ici ne touche au mobile ni aux grands lecteurs.
 */
object MiniPlayerProgressionTv {

    private const val PERIODE_MAJ_MS = 500L

    /** Un seul chronomètre par bandeau, même si le fragment recâble le mini-lecteur. */
    private val dejaInstalle = java.util.WeakHashMap<View, Boolean>()

    private fun format(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    fun installer(
        ligne: View,
        progression: ProgressBar,
        tempsCourant: TextView,
        tempsTotal: TextView,
        proprietaire: androidx.lifecycle.LifecycleOwner,
    ) {
        if (dejaInstalle[ligne] == true) return
        dejaInstalle[ligne] = true

        // ── 2026-08-18 (user : « la barre ne sera atteignable que si on fait le tour
        //   en passant par la gauche, ça ne risque pas d'interférer dans le maniement
        //   des jaquettes ») ────────────────────────────────────────────────────────
        //   Quand la ligne a le focus : GAUCHE/DROITE reculent/avancent de 10 s.
        //   HAUT/BAS ne sont PAS consommés → on ressort du bandeau normalement, et
        //   le parcours des jaquettes reste celui d'avant.
        ligne.setOnKeyListener { _, code, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val duree = MiniPlayerController.dureeMs()
            if (duree <= 0) return@setOnKeyListener false
            val pas = 10_000L
            when (code) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    MiniPlayerController.allerA((MiniPlayerController.positionMs() - pas).coerceAtLeast(0L))
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    MiniPlayerController.allerA((MiniPlayerController.positionMs() + pas).coerceAtMost(duree - 1_000L))
                    true
                }
                else -> false
            }
        }

        val main = Handler(Looper.getMainLooper())
        val tic = object : Runnable {
            override fun run() {
                val duree = MiniPlayerController.dureeMs()
                if (duree > 0) {
                    val position = MiniPlayerController.positionMs().coerceIn(0L, duree)
                    if (ligne.visibility != View.VISIBLE) ligne.visibility = View.VISIBLE
                    progression.progress = (position * 1000 / duree).toInt()
                    tempsCourant.text = format(position)
                    tempsTotal.text = format(duree)
                } else {
                    // Direct (ou rien en lecture) : pas de fin connue, donc pas de barre.
                    if (ligne.visibility != View.GONE) ligne.visibility = View.GONE
                }
                main.postDelayed(this, PERIODE_MAJ_MS)
            }
        }
        main.post(tic)

        proprietaire.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                main.removeCallbacks(tic)
                dejaInstalle.remove(ligne)
            }
        })
    }
}
