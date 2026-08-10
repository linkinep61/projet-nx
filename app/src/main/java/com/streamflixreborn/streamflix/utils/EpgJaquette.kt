package com.streamflixreborn.streamflix.utils

import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.streamflixreborn.streamflix.R

/**
 * EpgJaquette — remplissage du calque « guide » posé sur une jaquette (mobile).
 *
 * 2026-08-11 (user : « tu prends toute la jaquette pour le faire, mais la forme de mes
 * jaquettes ne bouge pas » puis « ça s'affiche que quand on a cliqué sur la chaîne »).
 *
 * ── POURQUOI CE FICHIER EXISTE PLUTÔT QU'UN BOUT DE CODE DANS LE VIEWHOLDER ──────────
 * Le calque doit être rafraîchi à DEUX moments qui n'ont rien à voir :
 *   1. quand RecyclerView lie la vue (défilement, changement de liste) → côté ViewHolder ;
 *   2. toutes les 30 s et au changement de chaîne, SANS relier quoi que ce soit → côté
 *      fragment, qui parcourt les calques déjà à l'écran.
 *
 * Faire passer le second cas par `notifyItemChanged` relancerait une liaison complète —
 * donc un rechargement Glide de l'affiche — pour ne changer qu'une ligne de texte, et sur
 * des listes imbriquées ça casse la position de défilement. On met donc le remplissage ici,
 * appelable depuis les deux endroits, et le fragment agit directement sur les vues visibles.
 */
object EpgJaquette {

    /** Clé de tag : la chaîne à laquelle appartient ce calque (id + titre affiché). */
    private val CLE_TAG = R.id.ll_epg_poster

    private data class Chaine(val id: String, val titre: String)

    /**
     * Remplit (ou masque) le calque pour cette chaîne.
     *
     * Le calque n'apparaît QUE si les trois conditions sont réunies :
     *  • c'est la chaîne en cours de lecture — les autres gardent leur logo ;
     *  • le guide est chargé ;
     *  • le guide connaît un programme en cours.
     *
     * Un film ou une série n'ayant jamais de programme en cours, le calque ne s'affiche
     * jamais dessus : aucun test de type de contenu n'est nécessaire.
     */
    fun appliquer(calque: View, idChaine: String, titreChaine: String) {
        calque.setTag(CLE_TAG, Chaine(idChaine, titreChaine))

        if (idChaine != MiniPlayerController.currentChannelId || !EpgStore.estPret()) {
            calque.isVisible = false
            return
        }

        val maintenant = System.currentTimeMillis()
        val enCours = EpgStore.enCours(titreChaine, maintenant)
        if (enCours == null) {
            calque.isVisible = false
            return
        }

        calque.isVisible = true
        calque.findViewById<TextView>(R.id.tv_epg_poster_title)?.text = enCours.titre

        val restantMin = ((enCours.finMs - maintenant) / 60_000L).coerceAtLeast(0L)
        calque.findViewById<TextView>(R.id.tv_epg_poster_time)?.text =
            "${heure(enCours.debutMs)} – ${heure(enCours.finMs)}\n· reste ${duree(restantMin)}"

        val etendue = (enCours.finMs - enCours.debutMs).coerceAtLeast(1L)
        calque.findViewById<ProgressBar>(R.id.pb_epg_poster)?.progress =
            (((maintenant - enCours.debutMs) * 1000L) / etendue).coerceIn(0L, 1000L).toInt()

        // Le résumé comble l'espace laissé libre par un titre court. Le guide le fournit dans
        // <desc> — on le lisait déjà sans jamais l'afficher.
        calque.findViewById<TextView>(R.id.tv_epg_poster_desc)?.let { resume ->
            val texte = enCours.description?.trim().orEmpty()
            resume.isVisible = texte.isNotEmpty()
            if (texte.isNotEmpty()) {
                resume.text = texte
                ajusterResume(resume)
            }
        }

        val aSuivre = EpgStore.aSuivre(titreChaine, maintenant)
        val montre = aSuivre != null
        calque.findViewById<View>(R.id.v_epg_poster_sep)?.isVisible = montre
        calque.findViewById<TextView>(R.id.tv_epg_poster_next_label)?.let {
            it.isVisible = montre
            if (aSuivre != null) it.text = "À SUIVRE ${heure(aSuivre.debutMs)}"
        }
        calque.findViewById<TextView>(R.id.tv_epg_poster_next)?.let {
            it.isVisible = montre
            // Deux créneaux qui se suivent portent souvent le MÊME titre (TF1 enchaîne deux
            // épisodes de « Familles nombreuses »). Répéter le titre à l'identique donne
            // l'impression d'un bug d'affichage, alors que la donnée est juste.
            if (aSuivre != null) {
                it.text = if (aSuivre.titre.equals(enCours.titre, ignoreCase = true)) {
                    "Épisode suivant"
                } else {
                    aSuivre.titre
                }
            }
        }
    }

    /**
     * Coupe le résumé sur « … » au lieu de le laisser tranché en plein milieu d'une ligne.
     *
     * Le champ prend l'espace restant via `layout_weight`, donc sa hauteur dépend de la
     * longueur du titre au-dessus : « Émission du mardi 11 août 2026 » sur deux lignes ne
     * laisse pas la même place qu'un titre d'une ligne. Comme cette hauteur n'est connue
     * qu'après la mesure, on calcule `maxLines` à ce moment-là — sans quoi `ellipsize` reste
     * sans effet et Android rogne la dernière ligne à la moitié des lettres.
     *
     * La garde `!=` évite la boucle : redéfinir maxLines relance une passe de layout.
     */
    private fun ajusterResume(resume: TextView) {
        resume.post {
            val hauteurLigne = resume.lineHeight
            if (hauteurLigne <= 0 || resume.height <= 0) return@post
            val lignes = (resume.height / hauteurLigne).coerceAtLeast(1)
            if (resume.maxLines != lignes) resume.maxLines = lignes
        }
    }

    /**
     * Rafraîchit tous les calques présents à l'écran, sans passer par les adaptateurs.
     * Coût : un parcours de l'arbre des vues visibles + un accès table de hachage par
     * calque trouvé. Appelé au changement de chaîne et toutes les 30 s.
     */
    fun rafraichirTout(racine: View) {
        parcourir(racine)
    }

    private fun parcourir(vue: View) {
        if (vue.id == R.id.ll_epg_poster) {
            (vue.getTag(CLE_TAG) as? Chaine)?.let { appliquer(vue, it.id, it.titre) }
            return
        }
        if (vue is ViewGroup) {
            for (i in 0 until vue.childCount) parcourir(vue.getChildAt(i))
        }
    }

    private fun heure(ms: Long): String = java.text.SimpleDateFormat(
        "HH:mm", java.util.Locale.FRANCE,
    ).format(java.util.Date(ms))

    /** « 43 min », « 2 h 13 » — sur une jaquette, chaque caractère compte. */
    private fun duree(minutes: Long): String =
        if (minutes < 60) "$minutes min"
        else "${minutes / 60} h ${"%02d".format(minutes % 60)}"
}
