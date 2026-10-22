package com.streamflixreborn.streamflix.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import kotlinx.coroutines.launch

/**
 * 2026-10-22 (user : « on a un sérieux décalage avec les sous-titres », « il faut que ce soit
 * accessible : dans les trois petits points, juste sous Sous-titres, idem TV, et seulement si
 * les sous-titres sont activés ») — synchronisation des sous-titres.
 *
 * Deux corrections, appliquées par [ExternalSubtitleOverlay] (le calque qui affiche les
 * sous-titres externes / téléchargés) :
 *  - [decalageMs] : retard constant. Positif = le texte apparaît PLUS TARD.
 *  - [rapport]    : dérive due à une autre cadence d'images (sous-titre calé sur un DVDRip
 *    25 i/s alors que la vidéo est à 23,976 i/s → l'écart grandit au fil du film).
 *
 * Remis à zéro à chaque nouvelle vidéo (changement de serveur / d'épisode).
 */
object SyncSousTitres {
    @Volatile var decalageMs: Long = 0L
    @Volatile var rapport: Double = 1.0
    /** Calage par les voix en cours : le calque n'affiche rien en attendant. */
    @Volatile var enAttenteCalage: Boolean = false
    /** Masquer pendant le calage ? Oui pour un sous-titre TÉLÉCHARGÉ (user) ; non pour le
     *  français OpenSubtitles choisi automatiquement (souvent déjà calé : on l'affiche). */
    @Volatile var masquerProchainCalage: Boolean = true
    /** Bouton « Recaler » du menu Sous-titres → branché par le lecteur en cours. */
    @Volatile var demandeRecalage: (() -> Unit)? = null
    /** 2026-10-22 (user) : sous-titre téléchargé impossible à caler → l'appli passe toute seule
     *  au suivant de la liste. Branché à chaque téléchargement, consommé une seule fois. */
    @Volatile var surCalageEchoue: (() -> Unit)? = null
    /** Vidéo en cours (sous-titres du serveur = références du recalage automatique). */
    @Volatile var videoCourante: com.streamflixreborn.streamflix.models.Video? = null

    /** (rapport, libellé) — ordre du bouton « Dérive ». */
    val DERIVES = listOf(
        1.0 to "aucune",
        23.976 / 25.0 to "sous-titre 25 i/s → vidéo 23,976",
        25.0 / 23.976 to "sous-titre 23,976 i/s → vidéo 25",
        23.976 / 24.0 to "sous-titre 24 i/s → vidéo 23,976",
        24.0 / 23.976 to "sous-titre 23,976 i/s → vidéo 24",
    )

    fun reinitialiser() {
        enAttenteCalage = false
        decalageMs = 0L
        rapport = 1.0
    }

    /** Heure dans le FICHIER de sous-titres qui correspond à la position de la vidéo. */
    fun tempsSousTitre(positionVideoMs: Long): Long =
        (positionVideoMs * rapport).toLong() - decalageMs

    fun libelleDecalage(): String {
        val s = decalageMs / 1000.0
        val txt = String.format(java.util.Locale.FRANCE, "%+.1f s", s)
        return if (decalageMs == 0L) "0,0 s" else txt
    }

    fun libelleDerive(): String =
        DERIVES.minByOrNull { kotlin.math.abs(it.first - rapport) }?.second ?: "aucune"
}

/**
 * Panneau « Synchroniser les sous-titres » — même rendu sur téléphone et TV (boutons
 * focalisables : la télécommande s'y déplace comme dans les autres menus). Placé en HAUT de
 * l'écran, sans assombrir la vidéo : on voit les sous-titres bouger pendant qu'on règle.
 */
object PanneauSyncSousTitres {

    @Volatile private var ouvertA = 0L

    /**
     * @param preparer bascule le sous-titre courant sur le calque (seul endroit où le décalage
     *   s'applique). Renvoie false si la piste ne peut pas être décalée.
     */
    fun afficher(context: Context, player: Player, video: com.streamflixreborn.streamflix.models.Video?, preparer: () -> Boolean) {
        if (!preparer()) {
            Toast.makeText(context, "Cette piste de sous-titres ne peut pas être décalée", Toast.LENGTH_LONG).show()
            return
        }
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        val dialogue = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        val racine = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#E615171C"))
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        val titre = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titre.addView(TextView(context).apply {
            text = "Synchroniser les sous-titres"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val fermer = bouton(context, "✕", dp) { dialogue.dismiss() }
        titre.addView(fermer)
        racine.addView(titre)

        val etat = TextView(context).apply {
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 15f
            setPadding(0, dp(6), 0, dp(2))
        }
        val aide = TextView(context).apply {
            text = "« + » : le texte apparaît plus tard   ·   « − » : plus tôt"
            setTextColor(Color.parseColor("#B0B0B0"))
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        }
        racine.addView(etat)

        var boutonDerive: TextView? = null
        fun rafraichir() {
            etat.text = "Décalage : ${SyncSousTitres.libelleDecalage()}"
            boutonDerive?.text = "Dérive : ${SyncSousTitres.libelleDerive()}"
        }

        val ligne = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((lib, pas) in listOf("−0,5 s" to -500L, "−0,1 s" to -100L, "+0,1 s" to 100L, "+0,5 s" to 500L)) {
            ligne.addView(bouton(context, lib, dp) {
                SyncSousTitres.decalageMs += pas
                rafraichir()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        racine.addView(aide)
        racine.addView(ligne)

        // 2026-10-22 (user : « je suis incapable de me servir du recalage », « on ne sait pas si
        //   la voix parle avant ou après si on ne connaît pas la langue ») : bouton principal
        //   AUTOMATIQUE — recale sur le sous-titre fourni par le serveur (cf. RecalageAuto).
        val portee = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
        dialogue.setOnDismissListener { portee.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        lateinit var bAuto: TextView
        bAuto = bouton(context, "Recaler automatiquement", dp) {
            val calque = ExternalSubtitleOverlay.globalInstance
            val source = calque?.sourceCourante()
            if (calque == null || source == null) {
                Toast.makeText(context, "Aucun sous-titre affiché", Toast.LENGTH_SHORT).show()
                return@bouton
            }
            bAuto.text = "Recalage en cours…"
            if (RecalageAuto.references(video, sauf = source).isEmpty()) {
                // Pas de sous-titre serveur → écoute des voix (≈ 1 min pour un 1er réglage).
                portee.launch {
                    var rep = calque.repliques()
                    var essais = 0
                    while (rep.size < 30 && essais++ < 10) { kotlinx.coroutines.delay(500); rep = calque.repliques() }
                    if (UserPreferences.modeLeger) {
                        Toast.makeText(context, "Calage automatique désactivé en mode léger", Toast.LENGTH_LONG).show()
                    } else {
                        EcouteVoix.lancer(context, player, rep) { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            rafraichir()
                        }
                        // Avancement visible tant que le panneau est ouvert.
                        while (EcouteVoix.enCours) {
                            bAuto.text = "Calage en cours… ${EcouteVoix.secondesEcoutees()} s"
                            rafraichir()
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                    bAuto.text = "Recaler automatiquement"
                    rafraichir()
                }
                return@bouton
            }
            portee.launch {
                val message = RecalageAuto.reglerSurReference(source, video)
                bAuto.text = "Recaler automatiquement"
                rafraichir()
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        racine.addView(bAuto, 2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })

        val ligne3 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        boutonDerive = bouton(context, "", dp) {
            val i = SyncSousTitres.DERIVES.indexOfFirst { kotlin.math.abs(it.first - SyncSousTitres.rapport) < 1e-6 }
            SyncSousTitres.rapport = SyncSousTitres.DERIVES[(i + 1).mod(SyncSousTitres.DERIVES.size)].first
            rafraichir()
        }
        ligne3.addView(boutonDerive, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        ligne3.addView(bouton(context, "Réinitialiser", dp) {
            SyncSousTitres.reinitialiser()
            rafraichir()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        racine.addView(ligne3)
        rafraichir()
        if (EcouteVoix.enCours) portee.launch {
            while (EcouteVoix.enCours) {
                bAuto.text = "Calage en cours… ${EcouteVoix.secondesEcoutees()} s"
                rafraichir()
                kotlinx.coroutines.delay(1000)
            }
            bAuto.text = "Recaler automatiquement"
            rafraichir()
        }

        val conteneur = android.widget.FrameLayout(context).apply {
            setOnClickListener { dialogue.dismiss() }
        }
        val largeur = minOf(dp(560), (context.resources.displayMetrics.widthPixels * 0.92).toInt())
        conteneur.addView(racine, android.widget.FrameLayout.LayoutParams(largeur, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
        racine.isClickable = true
        dialogue.setContentView(conteneur)
        dialogue.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        ouvertA = System.currentTimeMillis()
        dialogue.show()
        bAuto.requestFocus()
    }

    private fun bouton(context: Context, texte: String, dp: (Int) -> Int, action: () -> Unit): TextView =
        TextView(context).apply {
            text = texte
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#5A5F6B"))
                    setStroke(dp(2), Color.WHITE)
                })
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#5A5F6B"))
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#2A2D35"))
                })
            }
            // Le toucher qui a ouvert le panneau ne doit pas « cliquer » un bouton dessous.
            setOnClickListener { if (System.currentTimeMillis() - ouvertA > 400) action() }
        }
}
