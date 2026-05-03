package com.streamflixreborn.streamflix.download

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Broadcast receiver triggered by the "Arrêter" action button on the LiveRecorder
 * persistent notification. Calls [LiveRecorder.stopRecording] and dismisses the notif.
 */
class LiveRecorderStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("LiveRecorder", "Stop action received")
        val savedPath = LiveRecorder.stopRecording(context.applicationContext)
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.cancel(LiveRecorder.NOTIFICATION_ID)
        val msg = if (savedPath != null) {
            "✅ Enregistrement sauvegardé"
        } else {
            "Aucun segment capturé"
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION = "com.streamflixreborn.streamflix.LIVE_RECORDER_STOP"
    }
}
