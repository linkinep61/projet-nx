package com.streamflixreborn.streamflix.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamflixreborn.streamflix.utils.CastProxy

/**
 * 2026-10-03 (signalement Nvidia Shield : VLC/Nova ne lisaient pas certains serveurs) :
 *
 * Pendant qu'un lecteur externe lit une vidéo qui passe par le relais interne (CastProxy en
 * mode local), ONYX est en arrière-plan. Sans service de premier plan, Android peut fermer
 * ONYX pour libérer de la mémoire (fréquent sur Shield / Oppo) → le relais s'arrête et la
 * vidéo se coupe dans VLC. Ce service garde ONYX en vie le temps de la lecture, avec une
 * notification discrète. Il s'arrête au retour dans ONYX (LecteurExterne.retour) ou, au
 * plus tard, au bout de 4 h.
 */
class ServiceLecteurExterne : Service() {

    companion object {
        private const val TAG = "ServiceLecteurExterne"
        private const val CANAL = "lecteur_externe"
        private const val NOTIF_ID = 4247
        private const val DUREE_MAX_MS = 4L * 60 * 60 * 1000

        fun demarrer(ctx: Context) {
            try {
                val i = Intent(ctx, ServiceLecteurExterne::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "démarrage impossible : ${e.message}")
            }
        }

        fun arreter(ctx: Context) {
            try { ctx.stopService(Intent(ctx, ServiceLecteurExterne::class.java)) } catch (_: Exception) {}
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val finForcee = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = construireNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground KO : ${e.message}")
        }
        handler.removeCallbacks(finForcee)
        handler.postDelayed(finForcee, DUREE_MAX_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(finForcee)
        // Le relais ne sert plus à rien une fois la lecture externe terminée.
        try { CastProxy.stop() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun construireNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CANAL) == null) {
                nm?.createNotificationChannel(
                    NotificationChannel(CANAL, "Lecteur externe", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val ouvrir = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(
                this, 0, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Lecture dans un lecteur externe")
            .setContentText("La vidéo passe par l'application pendant la lecture.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { ouvrir?.let { setContentIntent(it) } }
            .build()
    }
}
