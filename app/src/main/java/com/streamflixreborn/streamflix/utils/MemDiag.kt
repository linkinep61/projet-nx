package com.streamflixreborn.streamflix.utils

import android.os.Debug
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 2026-10-03 — DIAGNOSTIC MÉMOIRE (build debug UNIQUEMENT, jamais actif chez les utilisateurs).
 *
 * But : repérer les sources gourmandes pour le futur « mode léger ». En temps normal ~40
 *   sources de backup partent en parallèle : impossible de savoir laquelle coûte quoi.
 *   Quand le diag est actif, chaque source passe SEULE (mutex), après un GC, et on relève :
 *     - le pic de mémoire Java + natif pendant son exécution (échantillon toutes les 40 ms),
 *     - ce qui reste retenu après (delta à la fin),
 *     - la durée.
 *
 * Activation (téléphone branché) :  adb shell setprop debug.onyx.memdiag 1
 * Désactivation                  :  adb shell setprop debug.onyx.memdiag 0
 * Lecture                        :  adb logcat -s MemDiag
 */
object MemDiag {
    private const val TAG = "MemDiag"
    private val mutex = Mutex()

    val actif: Boolean
        get() {
            if (!BuildConfig.DEBUG) return false
            return try {
                val c = Class.forName("android.os.SystemProperties")
                val v = c.getMethod("get", String::class.java, String::class.java)
                    .invoke(null, "debug.onyx.memdiag", "0") as String
                v == "1"
            } catch (_: Throwable) { false }
        }

    private fun javaUsed(): Long { val r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory() }
    private fun nativeUsed(): Long = Debug.getNativeHeapAllocatedSize()
    private fun mo(b: Long) = String.format("%.1f", b / 1048576.0)

    /** Marqueur « déjà dans une mesure » : une mesure imbriquée s'exécute directement (le mutex
     *  n'est pas réentrant — sans ça, une mesure dans une mesure se bloquerait à vie). */
    private object EnCours : kotlin.coroutines.AbstractCoroutineContextElement(Cle) {
        object Cle : kotlin.coroutines.CoroutineContext.Key<EnCours>
    }

    /** Exécute [bloc] seul et journalise son coût mémoire. Sans diag : exécution directe. */
    suspend fun <T> mesurer(nom: String, bloc: suspend () -> T): T {
        if (!actif) return bloc()
        if (kotlin.coroutines.coroutineContext[EnCours.Cle] != null) return bloc()
        return mutex.withLock { kotlinx.coroutines.withContext(EnCours) {
            Runtime.getRuntime().gc(); System.runFinalization(); Runtime.getRuntime().gc()
            delay(150)
            val j0 = javaUsed(); val n0 = nativeUsed()
            var picJ = j0; var picN = n0
            val t0 = System.currentTimeMillis()
            var resultat: T? = null
            coroutineScope {
                val echantillon = launch(Dispatchers.Default) {
                    while (isActive) {
                        val j = javaUsed(); val n = nativeUsed()
                        if (j > picJ) picJ = j
                        if (n > picN) picN = n
                        delay(40)
                    }
                }
                try { resultat = bloc() } finally { echantillon.cancel() }
            }
            val ms = System.currentTimeMillis() - t0
            val j1 = javaUsed(); val n1 = nativeUsed()
            Log.i(TAG, "SOURCE=$nom | pic java +${mo(picJ - j0)} Mo, natif +${mo(picN - n0)} Mo" +
                " | pic total +${mo((picJ - j0) + (picN - n0))} Mo" +
                " | reste java ${mo(j1 - j0)} natif ${mo(n1 - n0)} Mo | ${ms} ms")
            @Suppress("UNCHECKED_CAST")
            resultat as T
        } }
    }
}
