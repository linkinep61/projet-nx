package com.streamflixreborn.streamflix.utils

import java.util.concurrent.ConcurrentHashMap

/**
 * Statut des serveurs PAR TITRE (couleurs du picker).
 *
 * 2026-05-21 (user "les couleurs sont liées à l'épisode/au film en cours, faut pas
 *   que ça aille sur les autres") : le résultat réel (a joué / a échoué) d'un serveur
 *   est mémorisé PAR TITRE (clé = id du contenu courant = args.id du player). Un échec
 *   sur l'épisode 1 ne doit PAS rendre le serveur rouge sur l'épisode 2.
 *
 * En mémoire (durée de session). Reset implicite au changement de titre (nouvelle clé).
 *
 * VERIFIED = a réellement atteint la lecture (READY) sur ce titre.
 * DEAD     = a réellement échoué (onPlayerError / extraction KO) sur ce titre.
 * UNSURE   = essayé mais douteux (buffering/lent) sur ce titre.
 */
object TitleServerStatus {

    /** Réutilise l'enum d'ExtractorRanker pour rester cohérent avec le picker. */
    @Volatile
    var currentTitleKey: String = ""
        private set

    // titleKey -> (serverId -> statut)
    private val byTitle = ConcurrentHashMap<String, ConcurrentHashMap<String, ExtractorRanker.ServerStatus>>()

    fun setCurrentTitle(key: String) {
        if (key.isNotBlank()) currentTitleKey = key
    }

    fun record(serverId: String, status: ExtractorRanker.ServerStatus, titleKey: String = currentTitleKey) {
        if (titleKey.isBlank() || serverId.isBlank()) return
        val m = byTitle.getOrPut(titleKey) { ConcurrentHashMap() }
        // Un DEAD réel ne doit pas être réécrit en UNSURE par un signal plus faible,
        // mais un VERIFIED ultérieur (le serveur a fini par jouer) prime sur tout.
        val prev = m[serverId]
        when {
            status == ExtractorRanker.ServerStatus.VERIFIED -> m[serverId] = status
            status == ExtractorRanker.ServerStatus.DEAD -> m[serverId] = status
            status == ExtractorRanker.ServerStatus.UNSURE &&
                prev != ExtractorRanker.ServerStatus.DEAD &&
                prev != ExtractorRanker.ServerStatus.VERIFIED -> m[serverId] = status
        }
    }

    /** Statut connu pour CE titre, ou null si le serveur n'a pas encore été essayé ici. */
    fun statusOf(serverId: String, titleKey: String = currentTitleKey): ExtractorRanker.ServerStatus? {
        if (titleKey.isBlank() || serverId.isBlank()) return null
        return byTitle[titleKey]?.get(serverId)
    }
}
