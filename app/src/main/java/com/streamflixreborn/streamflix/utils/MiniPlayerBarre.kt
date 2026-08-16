package com.streamflixreborn.streamflix.utils

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar

/**
 * Barre de commandes du MINI-LECTEUR (mobile uniquement).
 *
 * ── 2026-08-18 (user) ────────────────────────────────────────────────────────
 *   « Pourquoi appuyer sur la vidéo pour mettre en plein écran ? Ça sert à rien.
 *     Vaut mieux avoir un mini-lecteur complet avec un bel affichage : un clic,
 *     ça affiche les boutons — au lieu de gâcher la vidéo tout le temps avec une
 *     barre qui sert à rien. »
 *
 *   Donc : la barre est MASQUÉE par défaut, l'image reste propre. Un PREMIER appui
 *   sur la vidéo la fait apparaître (précédent, lecture/pause, suivant, plein écran,
 *   fermer + barre de progression) ; un DEUXIÈME appui passe en PLEIN ÉCRAN — comme
 *   avant, mais sans agrandir par erreur au premier contact. Elle se referme seule
 *   après quelques secondes, ou tout de suite avec le bouton RETOUR.
 */
object MiniPlayerBarre {

    private const val PERIODE_MAJ_MS = 500L

    /** La barre se referme seule au bout de ce délai (user : « elle est censée se
     *  masquer toute seule »). Le RETOUR la ferme aussi, immédiatement. */
    private const val DELAI_MASQUAGE_MS = 5_000L

    /**
     * 2026-08-18 — « une fois affichée elle ne se masque plus ».
     *
     * `installer` est rappelé à chaque ré-accrochage du mini-lecteur
     * (`ensureMiniPlayerAttached`). Sans garde, on empilait à chaque fois un
     * nouveau chronomètre de masquage ET un nouveau callback RETOUR : la barre
     * se remontrait/se figeait et il fallait appuyer 3 fois sur RETOUR pour
     * sortir. On ne câble donc qu'UNE SEULE FOIS par bandeau ; les appels
     * suivants se contentent de re-brancher le clic sur la (nouvelle) surface
     * vidéo.
     */
    private val dejaCable = java.util.WeakHashMap<View, () -> Unit>()

    /** 1er appui = afficher la barre ; 2e appui (barre déjà à l'écran) = plein écran. */
    private fun brancherClic(
        zoneVideo: View,
        barre: View,
        surPleinEcran: () -> Unit,
        afficher: () -> Unit,
    ) {
        zoneVideo.setOnClickListener {
            if (barre.visibility == View.VISIBLE) {
                // 2026-08-18 (user : « je me souviens pourquoi on fermait le menu
                //   avant : il restait ouvert par-dessus le plein écran — que le
                //   plein écran ferme obligatoirement le menu avec les jaquettes »).
                //   Depuis FLAG_NOT_TOUCH_MODAL, un appui sur la vidéo ne referme
                //   plus le dialogue tout seul : la fermeture de TOUTE la pile de
                //   dialogues se fait donc dans `navigateToFullPlayer()` (règle
                //   d'origine de LiveHubFolderDialog : « seul le passage en plein
                //   écran ferme tout »). Ici on masque juste le bandeau, il n'a
                //   rien à faire dans le grand lecteur.
                barre.visibility = View.GONE
                surPleinEcran()
            } else {
                afficher()
            }
        }
        // Un appui sur la barre elle-même ne doit pas traverser jusqu'à la vidéo
        //   (sinon on passerait en plein écran en visant un bouton).
        barre.setOnClickListener { }
    }

    /**
     * @param zoneVideo  la vue de lecture (zone cliquable)
     * @param barre      le bandeau à afficher/masquer
     * @param progression barre d'avancement ; masquée pour un DIRECT (durée inconnue)
     */
    fun installer(
        zoneVideo: View,
        barre: View,
        boutonPrecedent: View,
        boutonSuivant: View,
        progression: SeekBar,
        surPleinEcran: () -> Unit,
        proprietaire: androidx.lifecycle.LifecycleOwner,
        retour: androidx.activity.OnBackPressedDispatcher,
    ) {
        // Déjà câblé pour ce bandeau : on se contente de rebrancher le clic sur la
        //   surface vidéo (elle, elle change à chaque nouveau flux).
        dejaCable[barre]?.let { afficherExistant ->
            brancherClic(zoneVideo, barre, surPleinEcran, afficherExistant)
            return
        }

        val main = Handler(Looper.getMainLooper())
        var enTrainDeDeplacer = false

        // 2026-08-18 (user : « le retour télécommande ou le bouton retour suffira à
        //   effacer le menu » puis « la barre est censée se masquer toute seule ») :
        //   les deux. Elle se referme au bout de 5 s, et le RETOUR la ferme tout de
        //   suite — sans quitter l'écran tant qu'elle est affichée.
        val fermetureParRetour = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                barre.visibility = View.GONE
                isEnabled = false
            }
        }
        retour.addCallback(proprietaire, fermetureParRetour)

        val masquer = Runnable {
            barre.visibility = View.GONE
            fermetureParRetour.isEnabled = false
        }

        fun afficher() {
            barre.visibility = View.VISIBLE
            fermetureParRetour.isEnabled = true
            main.removeCallbacks(masquer)
            main.postDelayed(masquer, DELAI_MASQUAGE_MS)
        }

        barre.visibility = View.GONE
        fermetureParRetour.isEnabled = false

        val afficherPartage = { afficher() }
        dejaCable[barre] = afficherPartage
        brancherClic(zoneVideo, barre, surPleinEcran, afficherPartage)

        boutonPrecedent.setOnClickListener { MiniPlayerController.precedent() }
        boutonSuivant.setOnClickListener { MiniPlayerController.suivant() }

        progression.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, valeur: Int, parUtilisateur: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {
                enTrainDeDeplacer = true
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                enTrainDeDeplacer = false
                val duree = MiniPlayerController.dureeMs()
                if (duree > 0) {
                    MiniPlayerController.allerA(duree * (sb?.progress ?: 0) / 1000L)
                }
            }
        })

        // Rafraîchissement de l'avancement, uniquement quand la barre est visible :
        //   rien ne tourne pour rien quand elle est masquée.
        val tic = object : Runnable {
            override fun run() {
                if (barre.visibility == View.VISIBLE) {
                    val duree = MiniPlayerController.dureeMs()
                    // Un DIRECT n'a pas de fin : pas de barre d'avancement.
                    progression.visibility = if (duree > 0) View.VISIBLE else View.GONE
                    if (duree > 0 && !enTrainDeDeplacer) {
                        progression.max = 1000
                        progression.progress = (MiniPlayerController.positionMs() * 1000 / duree).toInt()
                    }
                }
                main.postDelayed(this, PERIODE_MAJ_MS)
            }
        }
        main.post(tic)
    }
}
