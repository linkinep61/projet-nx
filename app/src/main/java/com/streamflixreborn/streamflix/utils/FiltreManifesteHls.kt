package com.streamflixreborn.streamflix.utils

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 2026-08-08 (user : « ils passent plus en rouge mais ils marchent toujours pas ») —
 *   ON RETIRE LA PISTE I-FRAME DU MANIFESTE.
 *
 * PROUVÉ PAR LE JOURNAL, après avoir fait demander le flux par l'extracteur lui-même :
 *     OnRegardeOu: upbolt CDN master → HTTP 200, 0 cookie(s) posés par le CDN
 *     DIAG-403 uri=…/ovdb7pxvsoym_h/iframes-v1-a1.m3u8
 * L'application atteint donc parfaitement le CDN : le master passe, les qualités vidéo
 * passent. SEUL le fichier `iframes-…` est refusé — celui des vignettes de la barre de
 * progression, qu'upbolt déclare mais ne sert pas.
 *
 * Refuser de le retenter ne suffit pas : pour une PLAYLIST, ExoPlayer ne sait pas « ignorer »,
 * l'échec devient fatal et toute la source est abandonnée. La seule parade fiable est de ne
 * jamais lui montrer cette piste — on retire les lignes `#EXT-X-I-FRAME-STREAM-INF` du
 * manifeste à la volée, avant qu'il ne le lise.
 *
 * Les vraies variantes sont intactes (vérifié sur ce master : 540p, 720p, 1080p conservées).
 * Ce qu'on perd : les vignettes de prévisualisation au défilement, sur les hébergeurs qui
 * déclarent cette piste sans la servir. Ils ne les fournissaient de toute façon pas.
 */
@UnstableApi
class FiltreManifesteHls(private val delegue: DataSource) : DataSource {

    private var donnees: ByteArray? = null
    private var position = 0
    private var passePlat = true
    private var uriCourante: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        delegue.addTransferListener(transferListener)
    }

    override fun getUri(): Uri? = uriCourante ?: delegue.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegue.responseHeaders

    override fun open(dataSpec: DataSpec): Long {
        uriCourante = dataSpec.uri
        val estManifeste = dataSpec.uri.path?.endsWith(".m3u8", ignoreCase = true) == true
        if (!estManifeste) {
            passePlat = true
            val spec = avecCleVidzy(dataSpec)
            return try {
                delegue.open(spec)
            } catch (e: Throwable) {
                // Sans ça, `DefaultDataSource` garde sa source interne assignée après un open
                // raté (403, timeout…). Son `open` suivant part sur `checkState(dataSource == null)`
                // et lève un IllegalStateException opaque — c'est ce qui tuait upbolt.
                runCatching { delegue.close() }
                throw e
            }
        }
        passePlat = false
        val brut = try {
            delegue.open(dataSpec)
            DataSourceUtil.readToEnd(delegue)
        } catch (e: Throwable) {
            runCatching { delegue.close() }
            throw e
        }
        runCatching { delegue.close() }
        val texte = String(brut, Charsets.UTF_8)
        val filtre = if (texte.contains("EXT-X-I-FRAME-STREAM-INF")) {
            val avant = texte.lineSequence().count { it.startsWith("#EXT-X-I-FRAME-STREAM-INF") }
            Log.d(TAG, "manifeste nettoyé : $avant piste(s) I-frame retirée(s) — ${dataSpec.uri}")
            texte.lineSequence()
                .filterNot { it.startsWith("#EXT-X-I-FRAME-STREAM-INF") }
                .joinToString("\n")
        } else {
            texte
        }
        val octets = ivExplicites(filtre, dataSpec.uri).toByteArray(Charsets.UTF_8)
        donnees = octets
        position = 0
        return octets.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passePlat) return delegue.read(buffer, offset, length)
        if (length == 0) return 0
        val d = donnees ?: return C.RESULT_END_OF_INPUT
        if (position >= d.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, d.size - position)
        System.arraycopy(d, position, buffer, offset, n)
        position += n
        return n
    }

    override fun close() {
        // Toujours, quel que soit le mode : `close()` est idempotent côté media3 et c'est la
        // seule garantie que le délégué reparte propre pour l'`open()` suivant.
        runCatching { delegue.close() }
        donnees = null
        position = 0
        passePlat = true
        uriCourante = null
    }

    /**
     * 2026-10-19 : ajoute la clé `gk` (cf. utils/CleVidzy) aux segments Vidzy qui ne l'ont pas.
     *   Sans elle le CDN refuse (403) tout segment au-delà du 9ᵉ → lecture en boucle.
     */
    private fun avecCleVidzy(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        val hote = uri.host ?: return dataSpec
        // 2026-10-22 : même mécanisme chez FSVid (« FS · Premium »), avec sa propre clé.
        val cle = when {
            hote.contains("vidzy", ignoreCase = true) -> CleVidzy.cle
            hote.contains("fsvid", ignoreCase = true) -> CleVidzy.cleFsvid
            else -> null
        } ?: return dataSpec
        if (uri.path?.endsWith(".ts", ignoreCase = true) != true) return dataSpec
        if (runCatching { uri.getQueryParameter("gk") }.getOrNull() != null) return dataSpec
        return dataSpec.withUri(uri.buildUpon().appendQueryParameter("gk", cle).build())
    }

    /**
     * 2026-11-03 (banc : Livavid / Nakios « Premium 1 » — le début joue, le saut à 90 % reste
     *   bloqué, 56 s en mémoire mais aucune image) — IV DES MORCEAUX CHIFFRÉS.
     *
     * Mesuré sur le flux tnmr.org (famille Lulustream) : une seule clé AES-128 SANS IV, morceaux
     * nommés `seg-N-…`, mais la liste a des TROUS (seg-49, 653, 732, 756 absents). L'hébergeur
     * chiffre chaque morceau avec IV = N (son numéro), alors que la norme HLS — que suit
     * ExoPlayer — prend IV = numéro de séquence (= position dans la liste). Les deux divergent
     * après le premier trou. Preuve sur seg-699 (position 697) : seul l'IV 699 donne une table
     * PAT valide (CRC bon) ; 696, 697 et 698 non.
     * En lecture continue ça passe (l'extracteur garde la PAT du 1er morceau) ; après un saut,
     * il relit la PAT du morceau, mal déchiffrée → plus aucun échantillon.
     * On écrit donc l'IV de chaque morceau explicitement, UNIQUEMENT dans ce cas précis
     * (1 clé AES-128 sans IV, tous les morceaux `seg-N`, numérotation alignée au départ puis
     * décalée). Toute autre liste ressort intacte.
     */
    private fun ivExplicites(texte: String, uri: Uri): String {
        if (!texte.contains("#EXT-X-KEY")) return texte
        val lignes = texte.lines()
        val cles = lignes.filter { it.startsWith("#EXT-X-KEY") }
        if (cles.size != 1) return texte
        val cle = cles[0].trimEnd()
        if (!cle.contains("METHOD=AES-128") || cle.contains("IV=", ignoreCase = true)) return texte
        val seq = lignes.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        val numero = Regex("""^seg-(\d+)-""")
        val nums = lignes.filter { it.isNotBlank() && !it.startsWith("#") }.map { l ->
            numero.find(l.substringBefore('?').substringAfterLast('/'))?.groupValues?.get(1)?.toLongOrNull()
                ?: return texte
        }
        if (nums.isEmpty() || nums.first() != seq) return texte
        if (nums.withIndex().all { (i, n) -> n == seq + i }) return texte
        val sortie = StringBuilder(texte.length + nums.size * (cle.length + 40))
        var i = 0
        for (l in lignes) {
            if (l.startsWith("#EXT-X-KEY")) continue
            if (l.isNotBlank() && !l.startsWith("#")) {
                sortie.append(cle).append(",IV=0x").append("%032x".format(nums[i])).append('\n')
                i++
            }
            sortie.append(l).append('\n')
        }
        Log.d(TAG, "IV explicites : ${nums.size} morceaux, numérotation à trous (${nums.last()} pour ${nums.size}) — ${uri.host}")
        return sortie.toString()
    }

    /** Enveloppe une fabrique existante sans rien changer à sa configuration. */
    @UnstableApi
    class Fabrique(private val delegue: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = FiltreManifesteHls(delegue.createDataSource())
    }

    private companion object {
        const val TAG = "FiltreManifesteHls"
    }
}
