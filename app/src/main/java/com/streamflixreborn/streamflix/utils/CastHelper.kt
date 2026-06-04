package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import android.net.Uri

/**
 * 2026-05-31 : Helper pour Google Cast.
 * Envoie le flux vidéo (m3u8/mp4) directement à la Chromecast.
 * La Chromecast lit le flux elle-même (Default Media Receiver).
 */
object CastHelper {

    private const val TAG = "CastHelper"

    // Pending cast — quand l'utilisateur clique Cast puis sélectionne un appareil,
    // la session met quelques secondes à se connecter. On stocke le flux à envoyer
    // et on l'envoie dès que la session est prête.
    private var pendingVideoUrl: String? = null
    private var pendingTitle: String? = null
    private var pendingContentType: String? = null
    private var sessionListener: SessionManagerListener<CastSession>? = null

    /** Vérifie si une session Cast est active et connectée. */
    fun isCasting(context: Context): Boolean {
        return try {
            val session = CastContext.getSharedInstance(context)
                .sessionManager.currentCastSession
            session != null && session.isConnected
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 2026-06-03 : true si une session Cast est ACTIVE, EN COURS, ou en ATTENTE.
     * Utilisé par OlaTvProvider pour skip le probe HEAD parallèle (qui ouvre 30
     * connexions OkHttp) pendant qu'une session Cast s'établit — sinon le
     * handshake TLS Chromecast peut traîner par saturation réseau/pool.
     */
    fun isCastingOrPending(context: Context): Boolean {
        if (pendingVideoUrl != null) return true
        return try {
            val session = CastContext.getSharedInstance(context)
                .sessionManager.currentCastSession
            session != null && (session.isConnected || session.isConnecting || session.isResuming)
        } catch (_: Exception) {
            false
        }
    }

    /** Envoie un flux vidéo à la Chromecast connectée. */
    fun castVideo(
        context: Context,
        videoUrl: String,
        title: String,
        posterUrl: String? = null,
        contentType: String = "application/x-mpegURL",
    ) {
        try {
            val castContext = CastContext.getSharedInstance(context)
            val castSession = castContext.sessionManager.currentCastSession

            if (castSession != null && castSession.isConnected) {
                // Session déjà connectée → envoyer maintenant
                sendToCast(castSession, videoUrl, title, posterUrl, contentType)
            } else {
                // Session pas encore prête → stocker et attendre
                Log.d(TAG, "Cast session not ready, storing pending: $title")
                pendingVideoUrl = videoUrl
                pendingTitle = title
                pendingContentType = contentType
                registerSessionListener(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "castVideo failed: ${e.message}")
        }
    }

    private fun sendToCast(
        session: CastSession,
        videoUrl: String,
        title: String,
        posterUrl: String?,
        contentType: String,
    ) {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            if (!posterUrl.isNullOrBlank()) {
                addImage(WebImage(Uri.parse(posterUrl)))
            }
        }

        // Détecter si c'est du live ou du VOD
        val streamType = if (contentType.contains("mpegurl", true) ||
            videoUrl.contains(".m3u8", true)) {
            MediaInfo.STREAM_TYPE_LIVE
        } else {
            MediaInfo.STREAM_TYPE_BUFFERED
        }

        val mediaInfo = MediaInfo.Builder(videoUrl)
            .setContentType(contentType)
            .setStreamType(streamType)
            .setMetadata(metadata)
            .build()

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        session.remoteMediaClient?.load(loadRequest)
        Log.d(TAG, "Cast sent: $title → ${videoUrl.take(80)} ($contentType)")
    }

    /** Écoute les changements de session pour envoyer le flux en attente. */
    private fun registerSessionListener(context: Context) {
        val castContext = CastContext.getSharedInstance(context)
        // Retirer l'ancien listener
        sessionListener?.let { castContext.sessionManager.removeSessionManagerListener(it, CastSession::class.java) }

        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                Log.d(TAG, "Cast session started — sending pending video")
                val url = pendingVideoUrl
                val title = pendingTitle
                val type = pendingContentType
                if (url != null && title != null) {
                    sendToCast(session, url, title, null, type ?: "application/x-mpegURL")
                    clearPending()
                }
            }
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                val url = pendingVideoUrl
                val title = pendingTitle
                val type = pendingContentType
                if (url != null && title != null) {
                    sendToCast(session, url, title, null, type ?: "application/x-mpegURL")
                    clearPending()
                }
            }
            override fun onSessionEnded(session: CastSession, error: Int) {
                Log.d(TAG, "Cast session ended (error=$error)")
                clearPending()
            }
            override fun onSessionStartFailed(session: CastSession, error: Int) {
                Log.w(TAG, "Cast session start failed (error=$error)")
                clearPending()
            }
            override fun onSessionStarting(session: CastSession) {}
            override fun onSessionResuming(session: CastSession, sessionId: String) {}
            override fun onSessionResumeFailed(session: CastSession, error: Int) { clearPending() }
            override fun onSessionSuspended(session: CastSession, reason: Int) {}
            override fun onSessionEnding(session: CastSession) {}
        }
        sessionListener = listener
        castContext.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
    }

    private fun clearPending() {
        pendingVideoUrl = null
        pendingTitle = null
        pendingContentType = null
    }

    /** Arrête la lecture Cast. */
    fun stopCast(context: Context) {
        try {
            val castSession = CastContext.getSharedInstance(context)
                .sessionManager.currentCastSession
            castSession?.remoteMediaClient?.stop()
        } catch (_: Exception) {}
    }
}
