package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * 2026-05-10 : DataSource wrapper qui reconnecte automatiquement sur EOF
 * pour les URLs live MPEG-TS (Xtream-codes Vegeta/Ola/WiTv...).
 *
 * Problème : ces serveurs envoient `Connection: close` après ~1-2 secondes
 * de données. ExoPlayer ProgressiveMediaSource reçoit EOF → STATE_ENDED.
 * VLC et les autres lecteurs gèrent ça en réouvrant automatiquement une
 * nouvelle requête HTTP. Ce wrapper fait pareil.
 *
 * Activation : on détecte au moment du `open()` si l'URL est une live
 * progressive (contient /live/ et finit par .ts). Si oui, sur EOF on
 * ferme + réouvre + retry read. Sinon (HLS segments, VOD, etc.), on
 * laisse l'EOF passer normalement.
 */
class LiveReconnectingHttpDataSource(
    private val wrapped: HttpDataSource,
) : HttpDataSource by wrapped {

    private var currentSpec: DataSpec? = null
    private var enableReconnect = false
    private var reconnectAttempts = 0

    override fun open(dataSpec: DataSpec): Long {
        currentSpec = dataSpec
        val urlFull = dataSpec.uri.toString().lowercase()
        val urlPath = urlFull.substringBefore('?')

        // 2026-05-11 : Détection RESTRICTIVE Xtream IPTV continu.
        //
        // Avant : on activait reconnect dès qu'on voyait `/live/` + `.ts`. Mais
        // les segments HLS individuels (TF1 via netplus.ch, etc.) ont aussi ce
        // pattern. Résultat : après chaque segment HLS d'1 MB, le wrapper essayait
        // de re-fetch le même segment → ExoPlayer bloqué en BUFFERING.
        //
        // Nouvelle heuristique : un VRAI Xtream IPTV continu a :
        //   - `/live/USER/PASS/STREAM_ID.ts` (credentials en path)
        //   - PAS de marqueurs HLS (`seq=`, `chunk-`, `_seg_`, `.m3u8`, `tok_`)
        //   - Souvent sur port custom (8080, 25461, 80 IP-based)
        //
        // Un segment HLS Live a typiquement :
        //   - `seq=NNN` ou `chunk-NNN` dans le path/query
        //   - `tok_XXXX/` token signé en début de path
        //   - Hosts CDN classiques (cloudfront, akamaized, cdn-*, cache*.host.tld)
        //
        // Si MARQUEUR HLS détecté → désactive reconnect, ExoPlayer gère la
        // séquence des segments lui-même via HlsMediaSource.
        val hasHlsMarker = urlFull.contains("seq=") ||
            urlPath.contains("/tok_") ||
            urlPath.contains("/chunk-") ||
            urlPath.contains("/chunk_") ||
            urlPath.contains("_seg_") ||
            urlPath.contains("/seg-") ||
            urlPath.contains("/segment-") ||
            urlPath.contains(".m4s") ||
            // HLS hosts CDN connus
            urlFull.contains("akamaized.net") ||
            urlFull.contains("cloudfront.net") ||
            urlFull.contains("netplus.ch") ||
            urlFull.contains("diff.tf1.fr") ||
            urlFull.contains("ftven.fr") ||
            urlFull.contains("bct.nextradiotv.com")

        enableReconnect = !hasHlsMarker &&
            urlPath.contains("/live/") &&
            urlPath.endsWith(".ts")
        reconnectAttempts = 0
        if (enableReconnect) {
            Log.d(TAG, "Live progressive (Xtream) detected, auto-reconnect enabled for ${dataSpec.uri}")
        } else if (urlPath.contains("/live/") && urlPath.endsWith(".ts")) {
            // Pattern /live/.ts mais avec marqueurs HLS → log pour debug
            Log.v(TAG, "HLS segment (not Xtream) detected, reconnect DISABLED for ${dataSpec.uri}")
        }
        return wrapped.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        var result = try {
            wrapped.read(buffer, offset, readLength)
        } catch (e: Exception) {
            // 2026-05-10 v2 : ÉLARGI à toutes les exceptions réseau (pas juste
            // HttpDataSourceException). Connection: close peut déclencher
            // SocketException, IOException, EOFException, etc. — toutes doivent
            // déclencher le reconnect sur live progressive.
            if (enableReconnect) {
                Log.d(TAG, "Read exception (${e.javaClass.simpleName}) on live URL → treating as EOF for reconnect: ${e.message}")
                C.RESULT_END_OF_INPUT
            } else throw e
        }

        if (result == C.RESULT_END_OF_INPUT && enableReconnect && reconnectAttempts < MAX_RECONNECTS) {
            val spec = currentSpec ?: return result
            reconnectAttempts++
            try {
                Log.d(TAG, "EOF on live URL — reconnecting (attempt #$reconnectAttempts)")
                try { wrapped.close() } catch (_: Exception) {}
                // Nouvelle requête sur la même URL — le serveur ouvre une nouvelle session.
                // Pas de Range header — on veut le live edge frais.
                val freshSpec = DataSpec.Builder()
                    .setUri(spec.uri)
                    .setHttpRequestHeaders(spec.httpRequestHeaders)
                    .setHttpMethod(DataSpec.HTTP_METHOD_GET)
                    .build()
                wrapped.open(freshSpec)
                // Retry the read with the fresh connection.
                result = wrapped.read(buffer, offset, readLength)
                if (result > 0) {
                    Log.d(TAG, "Reconnect success — read $result bytes")
                    reconnectAttempts = 0
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect failed: ${e.message}")
                // Don't throw — let ExoPlayer handle EOF normally if reconnect fails.
                return C.RESULT_END_OF_INPUT
            }
        }
        return result
    }

    companion object {
        private const val TAG = "LiveReconnectDS"
        private const val MAX_RECONNECTS = 100  // gros budget : 100 reconnects = ~100s de stream si chaque connexion = 1s
    }

    /** Factory wrapper. */
    class Factory(private val wrapped: HttpDataSource.Factory) : HttpDataSource.Factory {
        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
            wrapped.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }

        override fun createDataSource(): HttpDataSource =
            LiveReconnectingHttpDataSource(wrapped.createDataSource())
    }
}
