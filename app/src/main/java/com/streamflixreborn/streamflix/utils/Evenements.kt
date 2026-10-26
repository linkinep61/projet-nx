package com.streamflixreborn.streamflix.utils

import java.util.Calendar

/**
 * 2026-10-28 (user : « on peut le réutiliser pour les événements. Noël, etc. » — « pour tous
 *   les appareils, mais pour tous les événements ») — remplace Halloween.kt.
 *
 * Pendant un événement, l'appli s'ouvre sur une petite animation avec le logo de l'événement
 *   (cf. AnimationEvenement), sur téléphone comme sur télé. Le reste de l'année : démarrage
 *   direct, rien ne s'affiche. Retour à la normale AUTOMATIQUE selon les dates.
 *
 * AJOUTER UN ÉVÉNEMENT : une ligne dans [LISTE] + son logo (image carrée ~512 px, fond
 *   transparent, dans res/drawable-nodpi), sur le modèle de halloween_logo.
 * Pour tester hors période : [FORCE] = nom de l'événement (null = selon la date).
 */
object Evenements {

    /** null = selon la date ; sinon nom d'un événement de [LISTE] à forcer (test). */
    private val FORCE: String? = null

    /** Dates incluses ; un événement peut chevaucher le Nouvel An (fin avant début). */
    class Evenement(
        val nom: String,
        val moisDebut: Int, val jourDebut: Int,
        val moisFin: Int, val jourFin: Int,
        val logo: Int,
    )

    private val LISTE = listOf(
        Evenement("Halloween", Calendar.OCTOBER, 25, Calendar.NOVEMBER, 2,
            com.streamflixreborn.streamflix.R.drawable.halloween_logo),
        // Noël : ajouter un logo (ex. R.drawable.noel_logo) puis :
        // Evenement("Noël", Calendar.DECEMBER, 15, Calendar.DECEMBER, 31, R.drawable.noel_logo),
    )

    /** Événement en cours, ou null. */
    fun actuel(): Evenement? {
        FORCE?.let { f -> return LISTE.firstOrNull { it.nom == f } }
        val c = Calendar.getInstance()
        val jour = c.get(Calendar.MONTH) * 100 + c.get(Calendar.DAY_OF_MONTH)
        return LISTE.firstOrNull { e ->
            val debut = e.moisDebut * 100 + e.jourDebut
            val fin = e.moisFin * 100 + e.jourFin
            if (debut <= fin) jour in debut..fin else jour >= debut || jour <= fin
        }
    }
}
