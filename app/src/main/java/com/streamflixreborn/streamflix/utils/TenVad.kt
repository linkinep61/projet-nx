package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * 2026-10-27 (user : « un truc plus fiable, ou la même chose avec moins de poids, et plus rapide
 *   que les 2 minutes » → « il faut préparer l'essai, ça vaut le coup ») — détecteur de voix TEN VAD
 *   (Agora, Apache 2.0 + conditions : usage dans sa propre appli autorisé, « Powered by ten-vad »).
 *   ~0,4 Mo (32 bits) / ~0,5 Mo (64 bits), contre ~19 Mo pour Silero + ONNX Runtime et ~14 Mo
 *   pour YAMNet + MediaPipe. Son 16 kHz mono, trames de 256 échantillons (16 ms).
 *   La bibliothèque est en C : appelée par JNA (pas besoin du NDK pour compiler l'appli).
 *   Pas de version x86 : sur les émulateurs Intel, [creer] renvoie null.
 */
class TenVad private constructor(private var poignee: Pointer?) {

    private interface Bibli : Library {
        fun ten_vad_create(handle: PointerByReference, hopSize: NativeLong, threshold: Float): Int
        fun ten_vad_process(handle: Pointer, audio: ShortArray, len: NativeLong, prob: FloatByReference, flag: IntByReference): Int
        fun ten_vad_destroy(handle: PointerByReference): Int
        fun ten_vad_get_version(): String
    }

    // size_t = long C : 4 octets en 32 bits, 8 en 64 bits → NativeLong (jamais Long).
    private val longueur = NativeLong(TRAME.toLong())
    private val prob = FloatByReference()
    private val drapeau = IntByReference()

    /** true si la trame (exactement [TRAME] échantillons) contient de la voix. */
    fun voix(trame: ShortArray): Boolean {
        val p = poignee ?: return false
        if (bibli?.ten_vad_process(p, trame, longueur, prob, drapeau) != 0) return false
        return drapeau.value == 1
    }

    fun fermer() {
        val p = poignee ?: return
        poignee = null
        runCatching { bibli?.ten_vad_destroy(PointerByReference(p)) }
    }

    companion object {
        private const val TAG = "TenVad"
        const val TRAME = 256

        private val bibli: Bibli? by lazy {
            try {
                // Chargée d'abord par Android (bibliothèques non extraites de l'APK) : JNA la
                //   retrouve ensuite déjà en mémoire.
                System.loadLibrary("ten_vad")
                Native.load("ten_vad", Bibli::class.java).also {
                    Log.i(TAG, "TEN VAD chargé, version ${runCatching { it.ten_vad_get_version() }.getOrNull()}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "TEN VAD indisponible sur cet appareil : ${t.message}")
                null
            }
        }

        /** Nouveau détecteur (seuil de voix [seuil], 0,5 = réglage du fabricant), ou null. */
        fun creer(seuil: Float = 0.5f): TenVad? {
            val b = bibli ?: return null
            val ref = PointerByReference()
            return try {
                if (b.ten_vad_create(ref, NativeLong(TRAME.toLong()), seuil) != 0 || ref.value == null) null
                else TenVad(ref.value)
            } catch (t: Throwable) {
                Log.w(TAG, "création impossible : ${t.message}")
                null
            }
        }
    }
}
