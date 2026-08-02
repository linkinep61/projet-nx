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
            return try {
                delegue.open(dataSpec)
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
        val octets = filtre.toByteArray(Charsets.UTF_8)
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

    /** Enveloppe une fabrique existante sans rien changer à sa configuration. */
    @UnstableApi
    class Fabrique(private val delegue: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = FiltreManifesteHls(delegue.createDataSource())
    }

    private companion object {
        const val TAG = "FiltreManifesteHls"
    }
}
