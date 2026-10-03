package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * Tampon vidéo réduit pour le MODE LÉGER (2026-10-04).
 *
 * Mesuré sur la télé (Smart TV Pro, 2,4 Go) : en lecture normale le tampon ExoPlayer
 * monte à ~155 Mo (min 60 s, limite d'octets par défaut ~145 Mo dépassable).
 * En mode léger : 20 s / 50 s d'avance, limite STRICTE de 32 Mo (16 Mo pour le mini-lecteur)
 * → ~-120 Mo pendant toute la lecture.
 *
 * Si l'utilisateur a activé « Buffering étendu » dans le lecteur, il demande explicitement
 * plus de tampon : on ne réduit PAS (retourne null → le lecteur garde ses réglages normaux).
 * Mode normal : retourne toujours null, rien ne change.
 */
object TamponLeger {
    private const val MO = 1024 * 1024

    fun loadControl(mini: Boolean, extraBuffering: Boolean, rebufferMs: Int): LoadControl? {
        if (!UserPreferences.modeLeger || extraBuffering) return null
        Log.i("TamponLeger", "mode léger : tampon réduit (${if (mini) 16 else 32} Mo, 20-50 s)")
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000,
                50_000,
                1_000,
                rebufferMs
            )
            .setTargetBufferBytes((if (mini) 16 else 32) * MO)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }
}
