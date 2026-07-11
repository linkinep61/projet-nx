package com.streamflixreborn.streamflix.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.MiniPlayerController

/**
 * 2026-06-09 (user "est-ce qu'on pourrait forcer l'audio même quand on
 * éteint l'écran, sur les téléphones au bout d'une minute ça se coupe")
 *
 * Foreground Service "keep-alive" pour la lecture radio en arrière-plan.
 *
 *  But — sans Foreground Service, Android suspend les processus dont l'UI
 *  est en arrière-plan (~30s à 1min). Conséquence : l'ExoPlayer du
 *  MiniPlayerController s'arrête. Pour que la radio continue de jouer
 *  écran éteint, le système doit considérer le process comme "en lecture
 *  média" → on déclare un foreground service de type MEDIA_PLAYBACK.
 *
 *  Ce service NE POSSÈDE PAS l'ExoPlayer. Le player reste dans
 *  MiniPlayerController. Le service sert juste à :
 *   - Empêcher Android de suspendre le process via startForeground
 *   - Afficher une notification persistante (obligatoire sur Android 8+)
 *   - Permettre Play/Pause/Suivant/Précédent/Stop via la notif
 *
 *  2026-06-09 v2 (user "y'a pas la notification avec le Player Post play
 *  au suivant") : notification enrichie MediaStyle avec 4 boutons :
 *  ⏮ Précédent · ⏯ Play/Pause · ⏭ Suivant · ⏹ Arrêter.
 */
class RadioPlaybackService : Service() {

    companion object {
        private const val TAG = "RadioPlaybackService"
        private const val CHANNEL_ID = "radio_playback"
        private const val NOTIFICATION_ID = 4231

        const val ACTION_START = "com.streamflixreborn.streamflix.RADIO_START"
        const val ACTION_STOP = "com.streamflixreborn.streamflix.RADIO_STOP"
        const val ACTION_TOGGLE = "com.streamflixreborn.streamflix.RADIO_TOGGLE"
        const val ACTION_NEXT = "com.streamflixreborn.streamflix.RADIO_NEXT"
        const val ACTION_PREV = "com.streamflixreborn.streamflix.RADIO_PREV"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
    }

    private var currentTitle: String = "Radio en cours"
    private var currentSubtitle: String = "Streamflix"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        Log.d(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand action=$action")
        when (action) {
            ACTION_STOP -> {
                try { MiniPlayerController.stopAsync() } catch (_: Throwable) {}
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                try { MiniPlayerController.toggleRadioPlayPause() } catch (_: Throwable) {}
                // Recompose la notif pour refléter le nouvel état play/pause.
                refreshNotification()
                return START_NOT_STICKY
            }
            ACTION_NEXT -> {
                try { MiniPlayerController.nextRadio() } catch (_: Throwable) {}
                return START_NOT_STICKY
            }
            ACTION_PREV -> {
                try { MiniPlayerController.previousRadio() } catch (_: Throwable) {}
                return START_NOT_STICKY
            }
            else -> {
                currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: currentTitle
                currentSubtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: currentSubtitle
                startForegroundCompat(buildNotification(currentTitle, currentSubtitle))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    /**
     * 2026-07-11 (user "quand on ferme l'application la radio ne se quitte pas") :
     * appelé quand le user swipe l'app depuis les récents. Sans cet override,
     * le foreground service survit à la fermeture de l'app et l'audio continue
     * indéfiniment. On stoppe le player + le service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved — stopping radio")
        try { MiniPlayerController.stopAsync() } catch (_: Throwable) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val chan = NotificationChannel(
            CHANNEL_ID, "Lecture radio",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Lecture audio en arrière-plan"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        mgr.createNotificationChannel(chan)
    }

    private fun refreshNotification() {
        try {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            mgr.notify(NOTIFICATION_ID, buildNotification(currentTitle, currentSubtitle))
        } catch (_: Throwable) {}
    }

    private fun pending(action: String, reqCode: Int): PendingIntent {
        val intent = Intent(this, RadioPlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPending = openAppIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val isPlaying = try { MiniPlayerController.isRadioPlaying() } catch (_: Throwable) { true }
        val playPauseIcon = if (isPlaying)
            android.R.drawable.ic_media_pause
        else
            android.R.drawable.ic_media_play
        val playPauseLabel = if (isPlaying) "Pause" else "Lecture"

        // MediaStyle : indique à Android d'afficher 3 actions max dans la
        //   notif compacte (lockscreen / drawer collapsed). On choisit
        //   Prev / Play-Pause / Next.
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply { openPending?.let { setContentIntent(it) } }
            .addAction(
                android.R.drawable.ic_media_previous,
                "Précédent",
                pending(ACTION_PREV, 1),
            )
            .addAction(
                playPauseIcon,
                playPauseLabel,
                pending(ACTION_TOGGLE, 2),
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Suivant",
                pending(ACTION_NEXT, 3),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Arrêter",
                pending(ACTION_STOP, 4),
            )
            .setStyle(mediaStyle)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
