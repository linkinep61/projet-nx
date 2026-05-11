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
        val urlPath = dataSpec.uri.toString().substringBefore('?').lowercase()
        // Détection : live MPEG-TS Progressive (Xtream-codes pattern).
        enableReconnect = urlPath.contains("/live/") && urlPath.endsWith(".ts")
        reconnectAttempts = 0
        if (enableReconnect) {
            Log.d(TAG, "Live progressive detected, auto-reconnect enabled for ${dataSpec.uri}")
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
