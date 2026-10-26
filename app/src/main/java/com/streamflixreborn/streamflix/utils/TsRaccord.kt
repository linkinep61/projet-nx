package com.streamflixreborn.streamflix.utils

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 2026-10-27 (user : « freeze avec coupure de son — la vidéo continue mais plus de son ») — mesuré
 * sur la TV (Kombat Sport, Dric4rTV) :
 *   DecoderAudioRenderer: Audio sink error — UnexpectedDiscontinuityException:
 *   « Unexpected audio track timestamp discontinuity »
 *
 * Cause : le serveur Xtream coupe la connexion toutes les ~30 s ; LiveReconnectingHttpDataSource
 * en rouvre une. Mais la NOUVELLE connexion commence par le tampon du serveur (quelques secondes
 * DÉJÀ reçues) : les horodatages (PTS) reculent → image figée, son perdu.
 *
 * Raccord : on retient le dernier PTS reçu pour chaque piste (PID). Après une reconnexion, pour
 * chaque piste on JETTE les paquets TS jusqu'au premier début de PES dont le PTS est postérieur
 * au dernier reçu ; ensuite cette piste repasse telle quelle. Les paquets de service (PAT/PMT…)
 * passent toujours. Garde-fous : flux sans PTS connu → rien n'est filtré ; écart > 5 min (flux
 * remis à zéro par le serveur) → on laisse passer ; au-delà de 16 Mo filtrés → on abandonne le
 * filtrage et on laisse tout passer.
 */
class TsRaccord {
    private val dernier = HashMap<Int, Long>()
    private val obs = ByteArray(TAILLE)
    private var obsLen = 0
    private var bloques: MutableSet<Int>? = null
    private var budget = 0L
    private var jetes = 0L
    private val entree = ByteArray(256 * 1024)
    private var entreeLen = 0
    private var sortie = ByteArray(0)
    private var sortiePos = 0
    private var sortieFin = 0

    fun enFiltrage() = bloques != null
    fun aSortie() = sortiePos < sortieFin

    fun servir(b: ByteArray, off: Int, len: Int): Int {
        val n = minOf(len, sortieFin - sortiePos)
        System.arraycopy(sortie, sortiePos, b, off, n)
        sortiePos += n
        return n
    }

    /** Suit les PTS de ce qui part vers le lecteur (flux direct). */
    fun observer(b: ByteArray, off: Int, len: Int) {
        var i = off
        val fin = off + len
        if (obsLen > 0) {
            val k = minOf(TAILLE - obsLen, len)
            System.arraycopy(b, i, obs, obsLen, k)
            obsLen += k
            i += k
            if (obsLen < TAILLE) return
            if (obs[0] == SYNC) noterPts(obs, 0)
            obsLen = 0
        }
        while (fin - i >= TAILLE) {
            if (b[i] != SYNC) { i++; continue }
            noterPts(b, i)
            i += TAILLE
        }
        if (i < fin && b[i] == SYNC) {
            System.arraycopy(b, i, obs, 0, fin - i)
            obsLen = fin - i
        }
    }

    /** À appeler juste après une reconnexion réussie. */
    fun commencer() {
        obsLen = 0
        entreeLen = 0
        budget = 0
        jetes = 0
        bloques = if (dernier.isEmpty()) null else dernier.keys.toMutableSet()
    }

    /** Lit la nouvelle connexion et prépare la sortie filtrée. -1 = fin de flux ; 0 = tout jeté. */
    fun filtrer(lire: (ByteArray, Int, Int) -> Int): Int {
        val bl = bloques ?: return 0
        val n = lire(entree, entreeLen, entree.size - entreeLen)
        if (n < 0) return -1
        entreeLen += n
        budget += n
        val out = ByteArrayOutputStream(entreeLen)
        var i = 0
        while (entreeLen - i >= TAILLE) {
            if (entree[i] != SYNC) { i++; continue }
            val pid = ((entree[i + 1].toInt() and 0x1f) shl 8) or (entree[i + 2].toInt() and 0xff)
            if (pid in bl) {
                val pts = ptsDe(entree, i)
                val avant = dernier[pid]
                if (pts != null && avant != null) {
                    val d = ecart(pts, avant)
                    if (d > 0 || kotlin.math.abs(d) > ECART_REMISE_A_ZERO) {
                        bl.remove(pid)
                        Log.d(TAG, "piste $pid raccordée (écart ${d / 90} ms, ${jetes / 1024} Ko de doublons jetés)")
                        out.write(entree, i, TAILLE)
                        dernier[pid] = pts
                    } else jetes += TAILLE
                } else jetes += TAILLE
            } else {
                out.write(entree, i, TAILLE)
                ptsDe(entree, i)?.let { dernier[pid] = it }
            }
            i += TAILLE
        }
        val reste = entreeLen - i
        System.arraycopy(entree, i, entree, 0, reste)
        entreeLen = reste
        if (bl.isEmpty() || budget > BUDGET_MAX) {
            if (bl.isNotEmpty()) Log.w(TAG, "raccord abandonné après ${budget / 1024} Ko — flux laissé tel quel")
            bloques = null
            out.write(entree, 0, entreeLen)
            if (entreeLen > 0 && entree[0] == SYNC && entreeLen < TAILLE) {
                System.arraycopy(entree, 0, obs, 0, entreeLen)
                obsLen = entreeLen
            }
            entreeLen = 0
        }
        sortie = out.toByteArray()
        sortiePos = 0
        sortieFin = sortie.size
        return sortieFin
    }

    private fun noterPts(b: ByteArray, i: Int) {
        val pts = ptsDe(b, i) ?: return
        val pid = ((b[i + 1].toInt() and 0x1f) shl 8) or (b[i + 2].toInt() and 0xff)
        dernier[pid] = pts
    }

    companion object {
        private const val TAG = "TsRaccord"
        private const val TAILLE = 188
        private const val SYNC = 0x47.toByte()
        private const val ECART_REMISE_A_ZERO = 90_000L * 300
        private const val BUDGET_MAX = 16L * 1024 * 1024
        private const val MODULO = 1L shl 33

        /** Écart signé a − b sur l'horloge PTS 33 bits (gère le bouclage). */
        fun ecart(a: Long, b: Long): Long {
            var d = (a - b) % MODULO
            if (d < 0) d += MODULO
            if (d > MODULO / 2) d -= MODULO
            return d
        }

        /** PTS du paquet s'il commence un PES qui en porte un, sinon null. */
        fun ptsDe(b: ByteArray, i: Int): Long? {
            if ((b[i + 1].toInt() and 0x40) == 0) return null // pas de début d'unité
            val afc = (b[i + 3].toInt() shr 4) and 3
            if (afc == 0 || afc == 2) return null
            var p = i + 4
            if (afc == 3) p += 1 + (b[i + 4].toInt() and 0xff)
            if (p + 14 > i + TAILLE) return null
            if (b[p] != 0.toByte() || b[p + 1] != 0.toByte() || b[p + 2] != 1.toByte()) return null
            val sid = b[p + 3].toInt() and 0xff
            if (sid < 0xC0 || sid > 0xEF) return null // seulement audio / vidéo
            if (((b[p + 7].toInt() shr 6) and 3) < 2) return null
            val b9 = b[p + 9].toLong() and 0xff
            val b10 = b[p + 10].toLong() and 0xff
            val b11 = b[p + 11].toLong() and 0xff
            val b12 = b[p + 12].toLong() and 0xff
            val b13 = b[p + 13].toLong() and 0xff
            return (((b9 shr 1) and 7L) shl 30) or (b10 shl 22) or ((b11 shr 1) shl 15) or (b12 shl 7) or (b13 shr 1)
        }
    }
}
