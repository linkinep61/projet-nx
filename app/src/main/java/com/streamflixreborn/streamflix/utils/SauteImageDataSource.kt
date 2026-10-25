package com.streamflixreborn.streamflix.utils

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream

/**
 * 2026-10-25 (user : chaînes France de damitv « Télévision en direct », qui lisent dans Chrome) :
 * certains flux HLS DÉGUISENT leurs morceaux en images hébergées chez TikTok (`….image`).
 *
 * Format mesuré sur beIN SPORTS 1 et TF1 (damitv `/dl/<id>.m3u8`) : chaque morceau est un VRAI
 * PNG (RGB 8 bits, 512 px de large, ~2,5 Mo) ; ses pixels, une fois décompressés et
 * « défiltrés » (filtres PNG ligne par ligne), contiennent `TIKTIKPX` + longueur (4 octets,
 * gros-boutiste) + un flux gzip, qui est la vidéo MPEG-TS intacte (13 568 paquets, aucune
 * rupture). ExoPlayer, lui, voit un PNG → « Cannot find sync byte ».
 *
 * Deuxième forme gérée (au cas où) : une image collée DEVANT du TS en clair — on saute l'image.
 *
 * Cette source enveloppe n'importe quelle autre et reste TRANSPARENTE pour les flux normaux :
 * elle ne lit d'avance que 8 octets. Ce n'est pas un déchiffrement : la vidéo est en clair,
 * juste emballée dans une image.
 */
@OptIn(UnstableApi::class)
class SauteImageDataSource(private val amont: DataSource) : DataSource {

    class Factory(private val amont: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = SauteImageDataSource(amont.createDataSource())
    }

    private var tampon: ByteArray = ByteArray(0)
    private var posTampon = 0
    private var finTampon = 0
    /** Morceau entièrement en mémoire (PNG décodé) : plus rien à lire en amont. */
    private var toutEnMemoire = false
    private var amontOuvert = false
    private var uriCourante: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = amont.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        posTampon = 0; finTampon = 0; toutEnMemoire = false; amontOuvert = false
        uriCourante = dataSpec.uri
        val cle = dataSpec.uri.toString()
        if (dataSpec.position != 0L) {
            // Reprise en cours de morceau : positions exprimées dans la vidéo DÉCODÉE.
            decodes[cle]?.let { ts -> return servir(ts, dataSpec.position.toInt(), dataSpec.length) }
            val saut = sauts[cle]
            amontOuvert = true
            if (saut != null && saut > 0) return amont.open(dataSpec.buildUpon().setPosition(dataSpec.position + saut).build())
            return amont.open(dataSpec)
        }
        decodes[cle]?.let { ts -> return servir(ts, 0, dataSpec.length) }
        val longueur = amont.open(dataSpec)
        amontOuvert = true
        val debut = ByteArray(8)
        val lus = lireAuMoins(debut, 0, 8)
        if (!estImage(debut, lus)) { garder(debut, 0, lus); return longueur }

        // Image : on lit le morceau en entier (quelques Mo au plus).
        val tout = ByteArrayOutputStream(if (longueur > 0) longueur.toInt() else 3_000_000)
        tout.write(debut, 0, lus)
        val bloc = ByteArray(64 * 1024)
        while (tout.size() < LECTURE_MAX) {
            val k = amont.read(bloc, 0, bloc.size)
            if (k == C.RESULT_END_OF_INPUT) break
            tout.write(bloc, 0, k)
        }
        val octets = tout.toByteArray()
        fermerAmont()

        // 1) Vidéo cachée DANS les pixels du PNG.
        val debutDecodage = System.currentTimeMillis()
        val ts = runCatching { PngTs.decoder(octets) }
            .onFailure { if (journaux < 8) { journaux++; Log.w(TAG, "PNG non décodé : ${it.message}") } }
            .getOrNull()
        if (ts != null) {
            if (journaux < 8) {
                journaux++
                Log.i(TAG, "PNG ${octets.size} o → vidéo TS ${ts.size} o en ${System.currentTimeMillis() - debutDecodage} ms (${dataSpec.uri.host})")
            }
            decodes[cle] = ts
            return servir(ts, 0, dataSpec.length)
        }
        // 2) Vidéo en clair APRÈS l'image.
        val debutTs = chercherTs(octets)
        toutEnMemoire = true
        if (debutTs < 0) { garder(octets, 0, octets.size); return octets.size.toLong() }
        if (journaux < 8) { journaux++; Log.i(TAG, "image de $debutTs o retirée devant la vidéo (${dataSpec.uri.host})") }
        if (sauts.size > 200) sauts.clear()
        sauts[cle] = debutTs
        garder(octets, debutTs, octets.size)
        return (octets.size - debutTs).toLong()
    }

    private fun servir(ts: ByteArray, de: Int, longueurDemandee: Long): Long {
        val debut = de.coerceIn(0, ts.size)
        val fin = if (longueurDemandee == C.LENGTH_UNSET.toLong()) ts.size
            else (debut + longueurDemandee).coerceAtMost(ts.size.toLong()).toInt()
        garder(ts, debut, fin)
        toutEnMemoire = true
        return (fin - debut).toLong()
    }

    private fun fermerAmont() {
        if (amontOuvert) { runCatching { amont.close() }; amontOuvert = false }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (posTampon < finTampon) {
            val k = minOf(length, finTampon - posTampon)
            System.arraycopy(tampon, posTampon, buffer, offset, k)
            posTampon += k
            return k
        }
        if (toutEnMemoire) return C.RESULT_END_OF_INPUT
        return amont.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = if (amontOuvert) amont.uri ?: uriCourante else uriCourante
    override fun getResponseHeaders(): Map<String, List<String>> =
        if (amontOuvert) amont.responseHeaders else emptyMap()
    override fun close() {
        tampon = ByteArray(0); posTampon = 0; finTampon = 0; toutEnMemoire = false
        fermerAmont()
    }

    private fun garder(src: ByteArray, de: Int, a: Int) { tampon = src; posTampon = de; finTampon = a }

    /** Lit jusqu'à [min] octets (moins si fin de flux). */
    private fun lireAuMoins(dest: ByteArray, de: Int, min: Int): Int {
        var n = de
        while (n < min) {
            val k = amont.read(dest, n, dest.size - n)
            if (k == C.RESULT_END_OF_INPUT) break
            n += k
        }
        return n
    }

    private fun estImage(b: ByteArray, n: Int): Boolean {
        if (n < 8) return false
        val png = b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
        val gif = b[0] == 0x47.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() && b[3] == 0x38.toByte()
        // WebP (« RIFF….WEBP ») : streamed.pk met une image de 1 px (42 o) devant le TS.
        val webp = b[0] == 0x52.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() && b[3] == 0x46.toByte()
        return png || jpeg || gif || webp
    }

    /** Premier octet d'une vraie suite TS (0x47 tous les 188 octets, 8 fois de suite). */
    private fun chercherTs(o: ByteArray): Int {
        var i = 0
        while (i + 188 * 8 < o.size) {
            if (o[i] == SYNC && (1..7).all { o[i + 188 * it] == SYNC }) return i
            i++
        }
        return -1
    }

    companion object {
        private const val TAG = "SauteImage"
        private const val SYNC = 0x47.toByte()
        /** Plafond de lecture d'un morceau déguisé (mesuré ~2,6 Mo). */
        private const val LECTURE_MAX = 24 * 1024 * 1024
        @Volatile private var journaux = 0
        /** Taille de l'image retirée, par adresse de morceau (reprises en cours de morceau). */
        private val sauts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        /** Derniers morceaux décodés (reprises sans re-télécharger) : 3 au plus, ~2,5 Mo chacun. */
        private val decodes: MutableMap<String, ByteArray> = java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 3
            }
        )
    }
}

/** PNG → vidéo TS cachée dans ses pixels (`TIKTIKPX` + longueur + gzip). Null si autre chose. */
internal object PngTs {
    private val MAGIE = "TIKTIKPX".toByteArray()

    fun decoder(png: ByteArray): ByteArray? {
        var p = 8
        var largeur = 0; var hauteur = 0; var prof = 0; var couleur = 0
        val idat = ByteArrayOutputStream(png.size)
        while (p + 12 <= png.size) {
            val ln = lireInt(png, p)
            if (ln < 0 || p + 12 + ln > png.size) break
            val type = String(png, p + 4, 4, Charsets.US_ASCII)
            when (type) {
                "IHDR" -> {
                    largeur = lireInt(png, p + 8); hauteur = lireInt(png, p + 12)
                    prof = png[p + 16].toInt() and 0xFF; couleur = png[p + 17].toInt() and 0xFF
                    if (png[p + 20].toInt() != 0) return null // entrelacé : pas ce format
                }
                "IDAT" -> idat.write(png, p + 8, ln)
                "IEND" -> break
            }
            p += 12 + ln
        }
        if (largeur <= 0 || hauteur <= 0 || prof != 8) return null
        val bpp = when (couleur) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> return null }
        val ligne = largeur * bpp
        val brut = gonfler(idat.toByteArray(), hauteur * (ligne + 1)) ?: return null
        // Défiltrage PNG, directement dans un tableau de pixels.
        val px = ByteArray(hauteur * ligne)
        for (r in 0 until hauteur) {
            val f = brut[r * (ligne + 1)].toInt()
            val src = r * (ligne + 1) + 1
            val dst = r * ligne
            val prec = dst - ligne
            when (f) {
                0 -> System.arraycopy(brut, src, px, dst, ligne)
                1 -> for (i in 0 until ligne) {
                    val a = if (i >= bpp) px[dst + i - bpp].toInt() else 0
                    px[dst + i] = (brut[src + i].toInt() + a).toByte()
                }
                2 -> for (i in 0 until ligne) {
                    val b = if (r > 0) px[prec + i].toInt() else 0
                    px[dst + i] = (brut[src + i].toInt() + b).toByte()
                }
                3 -> for (i in 0 until ligne) {
                    val a = if (i >= bpp) px[dst + i - bpp].toInt() and 0xFF else 0
                    val b = if (r > 0) px[prec + i].toInt() and 0xFF else 0
                    px[dst + i] = (brut[src + i].toInt() + ((a + b) ushr 1)).toByte()
                }
                4 -> for (i in 0 until ligne) {
                    val a = if (i >= bpp) px[dst + i - bpp].toInt() and 0xFF else 0
                    val b = if (r > 0) px[prec + i].toInt() and 0xFF else 0
                    val c = if (r > 0 && i >= bpp) px[prec + i - bpp].toInt() and 0xFF else 0
                    val pa = kotlin.math.abs(b - c); val pb = kotlin.math.abs(a - c); val pc = kotlin.math.abs(a + b - 2 * c)
                    val pr = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    px[dst + i] = (brut[src + i].toInt() + pr).toByte()
                }
                else -> return null
            }
        }
        for (i in MAGIE.indices) if (px[i] != MAGIE[i]) return null
        val lg = lireInt(px, 8)
        if (lg <= 0 || 12 + lg > px.size) return null
        val ts = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(px, 12, lg), 64 * 1024).use { it.readBytes() }
        return ts.takeIf { it.size >= 188 && it[0] == 0x47.toByte() }
    }

    private fun lireInt(o: ByteArray, p: Int): Int =
        ((o[p].toInt() and 0xFF) shl 24) or ((o[p + 1].toInt() and 0xFF) shl 16) or
            ((o[p + 2].toInt() and 0xFF) shl 8) or (o[p + 3].toInt() and 0xFF)

    private fun gonfler(z: ByteArray, attendu: Int): ByteArray? {
        val inf = java.util.zip.Inflater()
        return try {
            inf.setInput(z)
            val out = ByteArray(attendu)
            var n = 0
            while (n < attendu && !inf.finished()) {
                val k = inf.inflate(out, n, attendu - n)
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
            if (n == attendu) out else null
        } finally { inf.end() }
    }
}
