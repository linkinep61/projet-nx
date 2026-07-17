package com.streamflixreborn.streamflix.utils

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 2026-07-16 — DataSource ExoPlayer pour les players « OnlyFlix » (SeekStreaming / EmbedSeek).
 *
 * Leurs segments HLS sont du **MPEG-TS caché dans des images PNG** (CDN TikTok) : chaque segment
 * = un mini en-tête PNG (jusqu'au chunk IEND) PUIS le TS pur. Vérifié en direct : après avoir jeté
 * les octets jusqu'à `IEND`+8, on obtient un TS impeccable (longueur multiple exact de 188, sync
 * 0x47 à 100 %). hls.js fait ce strip côté navigateur ; ici on le fait dans le pipeline ExoPlayer.
 *
 * Ce DataSource enveloppe un upstream (HTTP) et, UNIQUEMENT quand la réponse commence par la
 * signature PNG (`89 50 4E 47`), retire l'en-tête PNG à la volée. Les playlists m3u8 (texte) et
 * toute autre ressource passent INCHANGÉES. On ne strippe qu'à `position == 0` (cas des segments
 * TS complets ; les seeks intra-segment n'existent pas en TS). Sûr à appliquer globalement pour
 * une source seek, sans risque pour les autres formats.
 */
class SeekStreamPngDataSource(private val upstream: DataSource) : DataSource {

    private var pending: ByteArray? = null
    private var pendingPos = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        pending = null
        pendingPos = 0

        // On ne strippe qu'en début de ressource (segment TS complet).
        if (dataSpec.position != 0L) return length

        // Peek jusqu'à 1024 octets pour détecter le PNG + localiser IEND.
        val peek = ByteArray(1024)
        var got = 0
        while (got < peek.size) {
            val r = upstream.read(peek, got, peek.size - got)
            if (r == C.RESULT_END_OF_INPUT) break
            got += r
        }
        if (got <= 0) {
            pending = ByteArray(0)
            return length
        }

        var skip = 0
        val isPng = got >= 8 &&
            peek[0] == 0x89.toByte() && peek[1] == 0x50.toByte() &&
            peek[2] == 0x4E.toByte() && peek[3] == 0x47.toByte()
        if (isPng) {
            val iend = indexOfIEND(peek, got)
            if (iend >= 0) skip = iend + 8   // "IEND" (4) + CRC (4)
        }
        if (skip > got) skip = got

        pending = peek.copyOfRange(skip, got)
        pendingPos = 0

        return if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
        else (length - skip).coerceAtLeast(0L)
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        val p = pending
        if (p != null && pendingPos < p.size) {
            val n = minOf(readLength, p.size - pendingPos)
            System.arraycopy(p, pendingPos, buffer, offset, n)
            pendingPos += n
            if (pendingPos >= p.size) pending = null
            return n
        }
        return upstream.read(buffer, offset, readLength)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        pending = null
        upstream.close()
    }

    /** Cherche la séquence ASCII "IEND" (49 45 4E 44) dans les [len] premiers octets. */
    private fun indexOfIEND(buf: ByteArray, len: Int): Int {
        var i = 0
        while (i < len - 4) {
            if (buf[i] == 0x49.toByte() && buf[i + 1] == 0x45.toByte() &&
                buf[i + 2] == 0x4E.toByte() && buf[i + 3] == 0x44.toByte()
            ) return i
            i++
        }
        return -1
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SeekStreamPngDataSource(upstreamFactory.createDataSource())
    }
}
