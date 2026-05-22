package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * AdaptiveQualityGovernor — plafond de résolution ADAPTATIF basé sur les rebufferings.
 *
 * Demandé par l'user (2026-05-21) : « Baisse auto seulement si ça bufferise » +
 * « si le flux prend de l'avance et redevient stable il faut que ça remonte tout seul ».
 *
 * Principe :
 *  - On garde la PLEINE qualité par défaut (aucun plafond).
 *  - On descend d'un cran (720p puis 480p) UNIQUEMENT après plusieurs rebufferings
 *    rapprochés EN COURS de lecture (pas le buffering initial).
 *  - On REMONTE tout seul d'un cran dès que le flux reste stable assez longtemps.
 *
 * On ne touche QUE `setMaxVideoSize` sur les trackSelectionParameters : c'est un
 * changement ABR fluide qui s'applique à la frontière du prochain segment, JAMAIS
 * un `stop()`/reload. Donc pas de coupure / écran noir. Sur un flux mono-qualité
 * (un seul rendu vidéo) ça n'a tout simplement aucun effet — ni descente, ni coupure.
 *
 * Ne s'active QUE quand [isEnabled] est vrai (typiquement : VOD + qualité = Auto,
 * c.-à-d. `UserPreferences.qualityHeight == null`). En IPTV live ou quand l'user a
 * choisi une qualité manuelle, le governor relâche son plafond et ne fait rien.
 *
 * Pilotage par le fragment :
 *  - [onState] à chaque `onPlaybackStateChanged`.
 *  - [onTick] périodiquement (~10 s) pour la remontée quand c'est stable.
 *  - [reset] sur tout nouveau média / serveur (ou à la création du player).
 */
class AdaptiveQualityGovernor(
    private val player: ExoPlayer,
    private val isEnabled: () -> Boolean,
) {
    // 0 = pleine def (aucun plafond), 1 = 720p, 2 = 480p
    private var capLevel = 0
    private val rebufferTimestamps = ArrayDeque<Long>()
    private var hasPlayed = false          // a-t-on atteint READY au moins une fois ?
    private var readyStableSinceMs = 0L    // depuis quand READY+playing sans rebuffer ?
    private var lastState = Player.STATE_IDLE

    /** Reset complet : nouveau média / nouveau serveur. Relâche le plafond. */
    fun reset() {
        capLevel = 0
        rebufferTimestamps.clear()
        hasPlayed = false
        readyStableSinceMs = 0L
        lastState = Player.STATE_IDLE
        applyCap(0)
    }

    /** À appeler depuis `onPlaybackStateChanged`. */
    fun onState(state: Int) {
        if (!isEnabled()) {
            if (capLevel != 0) { capLevel = 0; applyCap(0) }
            lastState = state
            return
        }
        val now = System.currentTimeMillis()
        when (state) {
            Player.STATE_READY -> {
                hasPlayed = true
                readyStableSinceMs = now
            }
            Player.STATE_BUFFERING -> {
                // Ne compter QUE les rebuffers en cours de lecture (READY -> BUFFERING),
                // pas le buffering de démarrage.
                if (hasPlayed && lastState == Player.STATE_READY) {
                    rebufferTimestamps.addLast(now)
                    while (rebufferTimestamps.isNotEmpty() &&
                        now - rebufferTimestamps.first() > REBUFFER_WINDOW_MS) {
                        rebufferTimestamps.removeFirst()
                    }
                    if (rebufferTimestamps.size >= REBUFFER_THRESHOLD && capLevel < MAX_LEVEL) {
                        capLevel++
                        rebufferTimestamps.clear() // on repart à zéro après une descente
                        Log.w(TAG, "Rebuffers répétés → descente plafond niveau $capLevel (${labelFor(capLevel)})")
                        applyCap(capLevel)
                    }
                }
            }
        }
        lastState = state
    }

    /**
     * À appeler périodiquement. Remonte d'un cran si le flux est stable depuis
     * [STABLE_RECOVERY_MS]. Une seule marche par appel (montée douce).
     */
    fun onTick() {
        if (!isEnabled()) {
            if (capLevel != 0) { capLevel = 0; applyCap(0) }
            return
        }
        if (capLevel == 0) return
        val now = System.currentTimeMillis()
        val playing = try {
            player.playbackState == Player.STATE_READY && player.playWhenReady
        } catch (_: Exception) { false }
        if (playing && readyStableSinceMs > 0L &&
            now - readyStableSinceMs >= STABLE_RECOVERY_MS) {
            capLevel--
            readyStableSinceMs = now      // relance la fenêtre de stabilité
            rebufferTimestamps.clear()
            Log.i(TAG, "Flux stable ${STABLE_RECOVERY_MS / 1000}s → remontée plafond niveau $capLevel (${labelFor(capLevel)})")
            applyCap(capLevel)
        }
    }

    private fun applyCap(level: Int) {
        try {
            val (w, h) = CAP_SIZES[level]
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(w, h)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "applyCap($level) échoué", e)
        }
    }

    private fun labelFor(level: Int): String =
        if (level == 0) "pleine def" else "${CAP_SIZES[level].second}p"

    companion object {
        private const val TAG = "AdaptiveQuality"

        // Nombre de rebuffers dans la fenêtre pour déclencher une descente.
        private const val REBUFFER_THRESHOLD = 3
        private const val REBUFFER_WINDOW_MS = 60_000L

        // Durée de stabilité (READY+playing, sans rebuffer) avant de remonter d'un cran.
        private const val STABLE_RECOVERY_MS = 45_000L

        private const val MAX_LEVEL = 2
        // index 0 = aucun plafond, 1 = 720p, 2 = 480p
        private val CAP_SIZES = arrayOf(
            Int.MAX_VALUE to Int.MAX_VALUE,
            1280 to 720,
            854 to 480,
        )
    }
}
