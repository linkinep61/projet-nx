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
 * 2026-10-16 (user : la petite box Bouygues plante EN COURS de vidéo sur VOE & co) :
 *   le tampon normal (300 s d'avance, « temps prioritaire sur les octets ») peut dépasser
 *   300 Mo sur un serveur à gros débit — au-delà du plafond mémoire d'une box à 2 Go,
 *   Android tue l'appli sans message. Donc :
 *   • en mode léger, le tampon réduit s'applique MÊME si « Buffering étendu » est coché
 *     (le mode léger prime : c'est lui qui évite le plantage) ;
 *   • sur un appareil à faible mémoire (isLowRamDevice ou ≤ 256 Mo par appli), le tampon
 *     réduit s'applique automatiquement, mode léger ou pas, en plus serré (24 / 12 Mo).
 * Mode normal sur un appareil normal : retourne toujours null, rien ne change.
 */
object TamponLeger {
    private const val MO = 1024 * 1024

    /** Appareil à faible mémoire : isLowRamDevice, ou classe mémoire ≤ 256 Mo (box TV d'entrée de gamme). */
    val appareilFaible: Boolean by lazy {
        runCatching {
            val am = com.streamflixreborn.streamflix.StreamFlixApp.instance
                .getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.isLowRamDevice == true || (am?.memoryClass ?: 512) <= 256
        }.getOrDefault(false)
    }

    /**
     * 2026-10-17 (user, box Bouygues 4K : 1,5 Go, plafond 128 Mo par appli, l'appli tuée au lancement
     *   d'un film) : appareil TRÈS faible = isLowRamDevice, ou plafond mémoire par appli ≤ 128 Mo.
     *   Sur ces appareils : mode léger activé d'office (modifiable dans Paramètres), journaux réseau
     *   coupés, tampon vidéo 12 Mo. Les téléphones/box normaux (256 Mo et plus) ne sont pas concernés.
     */
    val appareilTresFaible: Boolean by lazy {
        runCatching {
            val am = com.streamflixreborn.streamflix.StreamFlixApp.instance
                .getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.isLowRamDevice == true || (am?.memoryClass ?: 512) <= 128
        }.getOrDefault(false)
    }

    fun loadControl(mini: Boolean, extraBuffering: Boolean, rebufferMs: Int): LoadControl? {
        val leger = UserPreferences.modeLeger
        val faible = appareilFaible
        if (!leger && !faible) return null
        val mo = when {
            appareilTresFaible -> if (mini) 8 else 12
            faible -> if (mini) 12 else 24
            else -> if (mini) 16 else 32
        }
        Log.i("TamponLeger", "tampon réduit ($mo Mo, 20-50 s) — modeLeger=$leger faibleMemoire=$faible" +
            (if (extraBuffering) " (Buffering étendu ignoré)" else ""))
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000,
                50_000,
                1_000,
                rebufferMs
            )
            .setTargetBufferBytes(mo * MO)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }
}
