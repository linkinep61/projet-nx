package com.streamflixreborn.streamflix.download

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.streamflixreborn.streamflix.utils.MediaStoreHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Singleton that captures HLS segment bytes flowing through ExoPlayer's DataSource
 * and writes them to a .ts file. Active only while [startRecording] has been called
 * and [stopRecording] hasn't.
 *
 * The user must keep the player open for the recording to continue (the player's
 * DataSource is what we tap into). When the user stops recording or leaves the
 * channel, we finalize the file and publish to MediaStore.
 *
 * Avoids the auth problems of fetching segments standalone with OkHttp — we ride
 * on top of the same DataSource ExoPlayer uses, so any auth that works for playback
 * also works for the recording copy.
 */
object LiveRecorder {

    private const val TAG = "LiveRecorder"
    /** Notification ID for the persistent "recording in progress" notification with Stop action */
    const val NOTIFICATION_ID = 9101
    /** Dedicated channel — high importance so the notif shows clearly (esp. on Oppo
     *  ColorOS which silently hides LOW-importance notifs from the status bar). */
    private const val NOTIFICATION_CHANNEL = "streamflix_live_recording"

    @Volatile private var outputStream: FileOutputStream? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var displayName: String = ""
    @Volatile private var bytesWritten: Long = 0L
    @Volatile private var segmentsCaptured: Int = 0
    @Volatile private var startedAt: Long = 0L
    /** Downloads-DB row id for this recording (so it shows in the Téléchargements list) */
    @Volatile private var dbId: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressUpdateJob: kotlinx.coroutines.Job? = null

    val isRecording: Boolean get() = outputStream != null

    fun status(): String {
        val o = outputStream ?: return "not recording"
        val mins = (System.currentTimeMillis() - startedAt) / 60_000
        val secs = ((System.currentTimeMillis() - startedAt) / 1_000) % 60
        val mb = bytesWritten / 1024 / 1024
        return "$displayName — ${mins}m${secs}s · ${mb}MB · $segmentsCaptured segments"
    }

    /**
     * Begin capturing. Returns the output file or null if a recording is already
     * in progress (we only allow one at a time).
     *
     * Also creates a row in the Downloads DB so the recording shows in the user's
     * Téléchargements list with live progress (segments + MB captured).
     */
    fun startRecording(
        context: Context,
        channelDisplayName: String,
        providerName: String = "Live IPTV",
        sourceUrl: String = "",
        poster: String? = null,
    ): File? {
        if (outputStream != null) {
            Log.w(TAG, "startRecording: already recording, ignoring")
            return null
        }
        val safeName = channelDisplayName.replace(Regex("[^A-Za-z0-9_]+"), "_").take(40)
        val ts = System.currentTimeMillis()
        val outDir = File(context.filesDir, "live_recordings")
        outDir.mkdirs()
        val f = File(outDir, "${safeName}_${ts}.ts")
        outputFile = f
        outputStream = FileOutputStream(f)
        displayName = channelDisplayName
        bytesWritten = 0L
        segmentsCaptured = 0
        startedAt = System.currentTimeMillis()

        // Insert a Downloads row so it shows in the user's "Téléchargements" UI
        val rowId = "live_${providerName}_${safeName}_${ts}"
        dbId = rowId
        scope.launch {
            try {
                val dao = DownloadManager.getDao()
                dao.insert(DownloadEntity(
                    id = rowId,
                    title = "🔴 $channelDisplayName (Live)",
                    subtitle = "Enregistrement en cours…",
                    poster = poster,
                    type = "movie",
                    providerName = providerName,
                    sourceUrl = sourceUrl,
                    headersJson = null,
                    filePath = f.absolutePath,
                    status = DownloadEntity.Status.DOWNLOADING,
                    totalBytes = -1L,
                    downloadedBytes = 0L,
                    mimeType = "video/mp2t",
                ))
                Log.d(TAG, "Downloads DB row inserted: $rowId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to insert Downloads row: ${e.message}")
            }
        }

        // Periodic progress update so the UI shows MB count + segment count
        progressUpdateJob?.cancel()
        progressUpdateJob = scope.launch {
            while (outputStream != null) {
                kotlinx.coroutines.delay(2000)
                val rid = dbId ?: continue
                val bytes = bytesWritten
                val segs = segmentsCaptured
                try {
                    DownloadManager.getDao().updateProgress(
                        rid, DownloadEntity.Status.DOWNLOADING,
                        bytes, -1L
                    )
                    // Update notification text with live progress
                    showOrUpdateNotification(context, channelDisplayName, bytes, segs)
                } catch (_: Exception) {}
            }
        }

        // Initial notification with Stop action
        showOrUpdateNotification(context, channelDisplayName, 0L, 0)

        Log.d(TAG, "Recording started: ${f.absolutePath}")
        return f
    }

    /** Show or update the persistent notification with a "Arrêter" action button.
     *  Uses a HIGH importance channel so it stays visible in the status bar
     *  (Oppo ColorOS hides LOW-importance notifs by default). */
    private fun showOrUpdateNotification(context: Context, channelName: String, bytes: Long, segs: Int) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val existing = nm.getNotificationChannel(NOTIFICATION_CHANNEL)
                if (existing == null) {
                    val ch = android.app.NotificationChannel(
                        NOTIFICATION_CHANNEL,
                        "Enregistrement live",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Notification persistante pendant l'enregistrement d'une chaîne live"
                        setShowBadge(true)
                        enableLights(true)
                        enableVibration(false)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                    nm.createNotificationChannel(ch)
                }
            }
            val mb = bytes / 1024 / 1024
            val secs = (System.currentTimeMillis() - startedAt) / 1000
            val mins = secs / 60; val rem = secs % 60
            val stopIntent = android.content.Intent(context, LiveRecorderStopReceiver::class.java).apply {
                action = LiveRecorderStopReceiver.ACTION
            }
            val stopPi = android.app.PendingIntent.getBroadcast(
                context, 0, stopIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notif = androidx.core.app.NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("🔴 Enregistrement live: $channelName")
                .setContentText("${mins}m${rem}s · ${mb} MB · $segs segments")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_PROGRESS)
                .addAction(0, "ARRÊTER", stopPi)
                .build()
            nm.notify(NOTIFICATION_ID, notif)
        } catch (e: Exception) {
            Log.w(TAG, "Notification update failed: ${e.message}")
        }
    }

    /**
     * Stop the current recording, finalize the file, publish to MediaStore so the
     * user can find it in /Movies/StreamFlix/. Returns the resulting file path
     * (or null if nothing was being recorded or no bytes were written).
     */
    /**
     * Stop the recording. Returns IMMEDIATELY (non-blocking) — does the file close
     * + MediaStore publish + DB row finalization in a background coroutine so the
     * UI / player isn't frozen at the moment the user taps Stop.
     *
     * Returns the eventual file path (best-guess, before publish) so the caller
     * can show a confirmation toast.
     */
    fun stopRecording(context: Context): String? {
        val os = outputStream ?: return null
        val f = outputFile ?: return null
        val name = displayName
        val totalBytes = bytesWritten
        val totalSegs = segmentsCaptured
        val rowId = dbId
        // Snap state to "not recording" RIGHT NOW so any ongoing read in
        // TeeDataSource sees outputStream == null and returns immediately.
        outputStream = null
        outputFile = null
        displayName = ""
        bytesWritten = 0L
        segmentsCaptured = 0
        dbId = null
        progressUpdateJob?.cancel()
        progressUpdateJob = null
        // Cancel notification immediately on UI thread (cheap)
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        Log.d(TAG, "Recording stopped: $totalSegs segments, $totalBytes bytes — finalizing in background")

        // Heavy I/O happens in the background scope (Dispatchers.IO) — do NOT
        // block the UI thread or the player will visibly stutter/freeze.
        val appCtx = context.applicationContext
        scope.launch {
            try { os.flush() } catch (_: Exception) {}
            try { os.close() } catch (_: Exception) {}

            if (totalBytes <= 0 || !f.exists()) {
                try { f.delete() } catch (_: Exception) {}
                if (rowId != null) {
                    try { DownloadManager.getDao().markFailed(rowId, "Aucun segment capturé — le player n'était pas en lecture") } catch (_: Exception) {}
                }
                return@launch
            }

            val finalName = "${name.replace(Regex("[^A-Za-z0-9_ ]+"), "_")}_${System.currentTimeMillis()}.ts"
            val finalPath = try {
                val publishedUri = MediaStoreHelper.publish(
                    context = appCtx,
                    srcFile = f,
                    displayName = finalName,
                    mimeType = "video/mp2t",
                )
                if (publishedUri != null) {
                    try { f.delete() } catch (_: Exception) {}
                    publishedUri.toString()
                } else {
                    f.absolutePath
                }
            } catch (e: Exception) {
                Log.w(TAG, "Publish to MediaStore failed: ${e.message}")
                f.absolutePath
            }

            if (rowId != null) {
                try {
                    val dao = DownloadManager.getDao()
                    dao.updateFilePath(rowId, finalPath)
                    dao.updateProgress(rowId, DownloadEntity.Status.COMPLETED, totalBytes, totalBytes)
                    dao.markCompleted(rowId)
                    Log.d(TAG, "Downloads row $rowId marked COMPLETED → $finalPath")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update Downloads row: ${e.message}")
                }
            }
        }

        // Best-guess synchronous return so the caller can show a "saved" toast
        return f.absolutePath
    }

    /** Called by [TeeDataSource] for each successful read. */
    internal fun appendBytes(buffer: ByteArray, offset: Int, length: Int) {
        val os = outputStream ?: return
        try {
            os.write(buffer, offset, length)
            bytesWritten += length
        } catch (e: Exception) {
            Log.w(TAG, "Write failed: ${e.message} — stopping recording")
            try { os.close() } catch (_: Exception) {}
            outputStream = null
        }
    }

    /** Called by [TeeDataSource.open] each time a new segment starts. */
    internal fun noteNewSegment() {
        if (outputStream != null) segmentsCaptured++
    }
}

/**
 * Wraps a [DataSource], forwarding all calls to it but additionally tee-writing
 * each successful read to [LiveRecorder] (when recording is active). When not
 * recording, this adds essentially zero overhead.
 */
class TeeDataSource(private val delegate: DataSource) : DataSource {
    override fun open(dataSpec: DataSpec): Long {
        val len = delegate.open(dataSpec)
        if (LiveRecorder.isRecording) LiveRecorder.noteNewSegment()
        return len
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = delegate.read(buffer, offset, length)
        if (n != C.RESULT_END_OF_INPUT && n > 0 && LiveRecorder.isRecording) {
            LiveRecorder.appendBytes(buffer, offset, n)
        }
        return n
    }
    override fun addTransferListener(transferListener: TransferListener) =
        delegate.addTransferListener(transferListener)
    override fun getUri() = delegate.uri
    override fun getResponseHeaders(): MutableMap<String, MutableList<String>> = delegate.responseHeaders
    override fun close() = delegate.close()
}

/** Factory wrapper that produces [TeeDataSource] instances. */
class TeeDataSourceFactory(private val delegateFactory: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = TeeDataSource(delegateFactory.createDataSource())
}
