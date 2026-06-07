package com.streamflixreborn.streamflix.utils

/**
 * Compteur statique pour limiter les sauts d'épisode anime en cascade
 * (= dernier recours quand AUCUN serveur d'un épisode ne marche).
 *
 * Sans ce garde-fou, si une saison entière est cassée chez le provider anime,
 * l'app enchaînerait les `playNextEpisode()` à l'infini ; on plafonne à 5
 * sauts consécutifs et on auto-reset le compteur après 60 s d'inactivité (ou
 * dès qu'un épisode charge avec succès — cf onSuccess()).
 *
 * Usage côté fragments :
 *  - `tryConsumeSkip()` AVANT de déclencher `playNextEpisodeAcrossSeasons`
 *    → renvoie `false` si on a déjà atteint le plafond ; dans ce cas le
 *    fragment doit conserver son comportement actuel (Toast + navigateUp).
 *  - `onSuccess()` quand `SuccessLoadingVideo` est émis → reset le compteur.
 */
object AnimeAutoSkipState {

    private const val MAX_CONSECUTIVE_SKIPS = 5
    private const val RESET_AFTER_MS = 60_000L

    private var consecutiveSkips = 0
    private var lastSkipTimestamp = 0L

    /**
     * Tente de consommer un saut. Renvoie `true` si le saut est autorisé
     * (compteur incrémenté), `false` si on a atteint le plafond.
     *
     * Auto-reset le compteur si le dernier saut date de plus de 60 s.
     */
    @Synchronized
    fun tryConsumeSkip(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSkipTimestamp > RESET_AFTER_MS) {
            consecutiveSkips = 0
        }
        if (consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
            return false
        }
        consecutiveSkips++
        lastSkipTimestamp = now
        return true
    }

    /**
     * Reset le compteur dès qu'un épisode charge avec succès.
     */
    @Synchronized
    fun onSuccess() {
        consecutiveSkips = 0
        lastSkipTimestamp = 0L
    }
}
