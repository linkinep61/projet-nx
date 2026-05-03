package com.streamflixreborn.streamflix.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.extractors.Extractor
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * Foreground service that handles downloading video files (MP4 direct + HLS/m3u8).
 * Processes the download queue one at a time.
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "streamflix_downloads"
        private const val NOTIFICATION_ID = 9001
        private const val COMPLETED_NOTIFICATION_ID = 9002
        private const val BUFFER_SIZE = 8192

        /**
         * Set of download IDs that have been requested to pause.
         * Checked by the download loop to interrupt the active download.
         */
        val pauseRequests = mutableSetOf<String>()
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentDownloadId: String? = null
    @Volatile
    private var isCancelled = false
    private var isProcessing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        createNotificationChannel()
    }

    private fun showToast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() isProcessing=$isProcessing scopeActive=${scope.isActive}")
        showToast("⬇ Service démarré")

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Préparation…", 0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Préparation…", 0))
            }
            Log.d(TAG, "startForeground() succeeded")
            showToast("⬇ Foreground OK")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground() FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            showToast("⬇ ERREUR: ${e.javaClass.simpleName}")
        }

        // Re-create scope if it was cancelled (service reuse after stopSelf)
        if (!scope.isActive) {
            Log.d(TAG, "Re-creating cancelled scope")
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        // Only launch processQueue if not already running
        if (!isProcessing) {
            Log.d(TAG, "Launching processQueue()")
            scope.launch { processQueue() }
        } else {
            Log.d(TAG, "processQueue already running, skipping launch")
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        scope.cancel()
        isProcessing = false
        super.onDestroy()
    }

    /**
     * Check if the current download has been requested to pause.
     */
    private fun isPauseRequested(): Boolean {
        val id = currentDownloadId ?: return false
        return synchronized(pauseRequests) { pauseRequests.contains(id) }
    }

    private suspend fun processQueue() {
        isProcessing = true
        Log.d(TAG, "processQueue() STARTED")
        val dao = DownloadManager.getDao()

        // Reset any downloads stuck in DOWNLOADING (from a previous crash)
        val stuckDownloads = dao.getActiveDownloads()
        Log.d(TAG, "Active downloads found: ${stuckDownloads.size} (${stuckDownloads.map { "${it.id}:${it.status}" }})")
        for (dl in stuckDownloads) {
            if (dl.isDownloading) {
                Log.w(TAG, "Resetting stuck download: ${dl.id}")
                dao.updateStatus(dl.id, DownloadEntity.Status.PENDING)
            }
        }

        try {
        while (true) {
            val next = dao.getNextPending()
            if (next == null) {
                Log.d(TAG, "No more pending downloads, stopping service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            Log.d(TAG, "Processing download: id=${next.id} title=${next.title} url=${next.sourceUrl.take(120)}")
            showToast("⬇ Téléchargement: ${next.title}")
            currentDownloadId = next.id
            isCancelled = false
            // Clear any stale pause request for this ID
            synchronized(pauseRequests) { pauseRequests.remove(next.id) }

            try {
                dao.updateStatus(next.id, DownloadEntity.Status.DOWNLOADING)
                updateNotification("Téléchargement : ${next.title}", 0)
                Log.d(TAG, "Starting download: ${next.id} url=${next.sourceUrl.take(120)} headers=${next.headersJson != null}")

                val isHls = next.sourceUrl.contains(".m3u8") ||
                        next.sourceUrl.contains("m3u8", ignoreCase = true) ||
                        next.mimeType?.contains("mpegURL", ignoreCase = true) == true ||
                        next.mimeType?.contains("m3u8", ignoreCase = true) == true

                if (isHls) {
                    downloadHls(next)
                } else {
                    downloadDirect(next)
                }

                if (isPauseRequested()) {
                    // Download was paused — status already set by DownloadManager.pause()
                    Log.d(TAG, "Download paused: ${next.id}")
                    synchronized(pauseRequests) { pauseRequests.remove(next.id) }
                    updateNotification("En pause : ${next.title}", 0)
                } else if (!isCancelled) {
                    dao.markCompleted(next.id)
                    showCompletedNotification(next.title)
                    Log.d(TAG, "Download completed: ${next.id}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${next.id}", e)
                dao.markFailed(next.id, e.message ?: "Unknown error")
                updateNotification("Échec : ${next.title}", 0)
            }

            currentDownloadId = null
            delay(500) // Brief pause between downloads
        }
        } finally {
            isProcessing = false
        }
    }

    /**
     * Download a direct MP4/video file with progress tracking and resume support.
     */
    private suspend fun downloadDirect(download: DownloadEntity) {
        val dao = DownloadManager.getDao()
        val file = File(download.filePath)
        file.parentFile?.mkdirs()

        val existingLength = if (file.exists()) file.length() else 0L

        val requestBuilder = Request.Builder()
            .url(download.sourceUrl)
            .header("User-Agent", Extractor.DEFAULT_USER_AGENT)

        // Add custom headers
        download.headersJson?.let { json ->
            try {
                val headers = com.google.gson.Gson().fromJson(
                    json, Map::class.java
                ) as? Map<String, String>
                headers?.forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }
            } catch (_: Exception) {}
        }

        // Support resume
        if (existingLength > 0) {
            requestBuilder.header("Range", "bytes=$existingLength-")
        }

        val response = Extractor.sharedClient.newCall(requestBuilder.build()).execute()

        if (!response.isSuccessful && response.code != HttpURLConnection.HTTP_PARTIAL) {
            response.close()
            throw Exception("HTTP ${response.code}: ${response.message}")
        }

        val body = response.body ?: throw Exception("Empty response body")
        val contentLength = body.contentLength()
        val totalBytes = if (response.code == HttpURLConnection.HTTP_PARTIAL) {
            existingLength + contentLength
        } else {
            contentLength
        }

        val append = response.code == HttpURLConnection.HTTP_PARTIAL
        val outputStream = FileOutputStream(file, append)
        val inputStream = body.byteStream()

        try {
            copyStream(inputStream, outputStream, download.id, existingLength, totalBytes, dao)
        } finally {
            inputStream.close()
            outputStream.close()
            response.close()
        }
    }

    /**
     * Download HLS/m3u8 stream by downloading all segments and concatenating them.
     */
    private suspend fun downloadHls(download: DownloadEntity) {
        val dao = DownloadManager.getDao()
        val file = File(download.filePath)
        file.parentFile?.mkdirs()

        // Fetch the m3u8 playlist
        val requestBuilder = Request.Builder()
            .url(download.sourceUrl)
            .header("User-Agent", Extractor.DEFAULT_USER_AGENT)

        download.headersJson?.let { json ->
            try {
                val headers = com.google.gson.Gson().fromJson(
                    json, Map::class.java
                ) as? Map<String, String>
                headers?.forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }
            } catch (_: Exception) {}
        }

        val playlistResponse = Extractor.sharedClient.newCall(requestBuilder.build()).execute()
        val playlistBody = playlistResponse.body?.string()
            ?: throw Exception("Empty HLS playlist")
        playlistResponse.close()

        // Parse m3u8 - find segment URLs
        val baseUrl = download.sourceUrl.substringBeforeLast("/") + "/"
        val segments = mutableListOf<String>()

        // If this is a master playlist (contains #EXT-X-STREAM-INF), pick the best quality
        if (playlistBody.contains("#EXT-X-STREAM-INF")) {
            val bestStreamUrl = parseMasterPlaylist(playlistBody, baseUrl)
                ?: throw Exception("No streams found in master playlist")

            // Fetch the actual segment playlist
            val segResponse = Extractor.sharedClient.newCall(
                Request.Builder().url(bestStreamUrl)
                    .header("User-Agent", Extractor.DEFAULT_USER_AGENT).build()
            ).execute()
            val segBody = segResponse.body?.string() ?: throw Exception("Empty segment playlist")
            segResponse.close()

            val segBaseUrl = bestStreamUrl.substringBeforeLast("/") + "/"
            segments.addAll(parseSegmentPlaylist(segBody, segBaseUrl))
        } else {
            segments.addAll(parseSegmentPlaylist(playlistBody, baseUrl))
        }

        if (segments.isEmpty()) throw Exception("No segments found in HLS playlist")

        // Detect LIVE stream: VOD playlists end with #EXT-X-ENDLIST. Live ones don't.
        val effectivePlaylistBody = if (playlistBody.contains("#EXT-X-STREAM-INF")) {
            // We resolved a master playlist — re-fetch the chosen variant to check ENDLIST
            try {
                val bestUrl = parseMasterPlaylist(playlistBody, baseUrl)
                if (bestUrl != null) {
                    Extractor.sharedClient.newCall(
                        Request.Builder().url(bestUrl).header("User-Agent", Extractor.DEFAULT_USER_AGENT).build()
                    ).execute().use { it.body?.string() ?: "" }
                } else playlistBody
            } catch (_: Exception) { playlistBody }
        } else playlistBody
        val isLive = !effectivePlaylistBody.contains("#EXT-X-ENDLIST")
        val targetDurationSec = Regex("#EXT-X-TARGETDURATION:(\\d+)")
            .find(effectivePlaylistBody)?.groupValues?.get(1)?.toLongOrNull() ?: 6L
        val livePlaylistUrl = if (playlistBody.contains("#EXT-X-STREAM-INF"))
            (parseMasterPlaylist(playlistBody, baseUrl) ?: download.sourceUrl)
        else download.sourceUrl

        Log.d(TAG, "HLS download: ${segments.size} initial segments, isLive=$isLive, target=${targetDurationSec}s for ${download.id}")

        // Download all segments into the output file
        val outputStream = FileOutputStream(file)
        try {
            if (!isLive) {
                // VOD: fixed segment count, classic progress reporting
                segments.forEachIndexed { index, segmentUrl ->
                    if (isCancelled || isPauseRequested()) return
                    appendSegment(segmentUrl, outputStream)
                    val progress = ((index + 1) * 100) / segments.size
                    dao.updateProgress(
                        download.id, DownloadEntity.Status.DOWNLOADING,
                        (index + 1).toLong(), segments.size.toLong()
                    )
                    updateNotification("Téléchargement : ${download.title}", progress)
                }
            } else {
                // LIVE: poll the playlist forever, download NEW segments only.
                // Stops when user pauses/cancels (isPauseRequested/isCancelled).
                // Hard safety cap: 4 hours of recording.
                val seenSegments = mutableSetOf<String>()
                segments.forEach { seenSegments.add(it) }
                Log.d(TAG, "LIVE recording started — write segments as they arrive, stop on user action")

                // Write initial segments first
                segments.forEachIndexed { index, segmentUrl ->
                    if (isCancelled || isPauseRequested()) return
                    appendSegment(segmentUrl, outputStream)
                }
                var totalSegments = segments.size
                val recordingStart = System.currentTimeMillis()
                val MAX_RECORDING_MS = 4L * 60L * 60L * 1000L // 4 hours

                while (!isCancelled && !isPauseRequested()) {
                    // Safety: stop after 4 hours
                    if (System.currentTimeMillis() - recordingStart > MAX_RECORDING_MS) {
                        Log.w(TAG, "LIVE recording: hit 4h cap — stopping")
                        break
                    }
                    // Wait one segment duration (or 4s min) before re-polling
                    val waitMs = (targetDurationSec * 1000L).coerceAtLeast(4000L)
                    Thread.sleep(waitMs)

                    // Re-fetch the playlist
                    val refreshBody = try {
                        Extractor.sharedClient.newCall(
                            Request.Builder().url(livePlaylistUrl)
                                .header("User-Agent", Extractor.DEFAULT_USER_AGENT).build()
                        ).execute().use { it.body?.string() ?: "" }
                    } catch (e: Exception) {
                        Log.w(TAG, "LIVE refresh failed: ${e.message}, retrying")
                        continue
                    }
                    val refreshBaseUrl = livePlaylistUrl.substringBeforeLast("/") + "/"
                    val newSegList = parseSegmentPlaylist(refreshBody, refreshBaseUrl)
                    val newSegments = newSegList.filter { seenSegments.add(it) }
                    if (newSegments.isEmpty()) continue

                    for (segUrl in newSegments) {
                        if (isCancelled || isPauseRequested()) break
                        try {
                            appendSegment(segUrl, outputStream)
                            totalSegments++
                        } catch (e: Exception) {
                            Log.w(TAG, "LIVE segment failed: ${e.message}")
                        }
                    }

                    val elapsedSec = (System.currentTimeMillis() - recordingStart) / 1000
                    dao.updateProgress(
                        download.id, DownloadEntity.Status.DOWNLOADING,
                        totalSegments.toLong(), -1L  // -1 = unknown total (live)
                    )
                    val mins = elapsedSec / 60; val secs = elapsedSec % 60
                    updateNotification(
                        "Enregistrement live : ${download.title} — ${mins}m${secs}s",
                        -1  // indeterminate progress
                    )
                }
                Log.d(TAG, "LIVE recording stopped: $totalSegments segments captured")
            }
        } finally {
            outputStream.close()
        }
    }

    /** Helper: download one HLS segment and append its bytes to outputStream. */
    private fun appendSegment(segmentUrl: String, outputStream: FileOutputStream) {
        Extractor.sharedClient.newCall(
            Request.Builder().url(segmentUrl)
                .header("User-Agent", Extractor.DEFAULT_USER_AGENT).build()
        ).execute().use { resp ->
            resp.body?.byteStream()?.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled || isPauseRequested()) return
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    private fun parseMasterPlaylist(content: String, baseUrl: String): String? {
        var bestBandwidth = 0L
        var bestUrl: String? = null

        val lines = content.lines()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                val bandwidthMatch = Regex("BANDWIDTH=(\\d+)").find(lines[i])
                val bandwidth = bandwidthMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
                if (bandwidth >= bestBandwidth && i + 1 < lines.size) {
                    bestBandwidth = bandwidth
                    val url = lines[i + 1].trim()
                    bestUrl = if (url.startsWith("http")) url else baseUrl + url
                }
            }
        }
        return bestUrl
    }

    private fun parseSegmentPlaylist(content: String, baseUrl: String): List<String> {
        return content.lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { url ->
                if (url.startsWith("http")) url else baseUrl + url.trim()
            }
    }

    private suspend fun copyStream(
        input: InputStream,
        output: FileOutputStream,
        downloadId: String,
        startBytes: Long,
        totalBytes: Long,
        dao: DownloadDao,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var downloadedBytes = startBytes
        var lastNotifyTime = 0L
        var bytesRead: Int

        while (input.read(buffer).also { bytesRead = it } != -1) {
            if (isCancelled || isPauseRequested()) return

            output.write(buffer, 0, bytesRead)
            downloadedBytes += bytesRead

            val now = System.currentTimeMillis()
            if (now - lastNotifyTime > 500) { // Update every 500ms
                lastNotifyTime = now
                val progress = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                dao.updateProgress(downloadId, DownloadEntity.Status.DOWNLOADING, downloadedBytes, totalBytes)
                updateNotification("Téléchargement…", progress)
            }
        }

        // Final progress update
        dao.updateProgress(downloadId, DownloadEntity.Status.DOWNLOADING, downloadedBytes, totalBytes)
    }

    // ── Notifications ──

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Téléchargements",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifications de progression des téléchargements"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_downloads)
            .setContentTitle("StreamFlix")
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    /**
     * Show a separate "download complete" notification that is not ongoing.
     */
    private fun showCompletedNotification(title: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_downloads)
            .setContentTitle("Téléchargement terminé")
            .setContentText(title)
            .setAutoCancel(true)
            .setSilent(false)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(COMPLETED_NOTIFICATION_ID + title.hashCode(), notification)
    }
}
