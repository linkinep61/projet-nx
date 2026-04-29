package com.streamflixreborn.streamflix.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Singleton that orchestrates downloads.
 * Downloads are processed directly in coroutines (no foreground service needed).
 * Provides UI-facing flows for download state.
 */
object DownloadManager {

    private const val TAG = "DownloadManager"
    private const val CHANNEL_ID = "streamflix_downloads"
    private const val NOTIFICATION_ID = 9001
    private const val COMPLETED_NOTIFICATION_ID = 9002
    private const val BUFFER_SIZE = 32_768 // 32KB buffer for faster I/O

    /** Number of parallel connections for IDM-style multi-connection downloads */
    private const val PARALLEL_CONNECTIONS = 4

    /** Minimum file size (bytes) to use parallel download for direct files */
    private const val PARALLEL_MIN_SIZE = 5_000_000L

    private val gson = Gson()

    private lateinit var appContext: Context
    private lateinit var dao: DownloadDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val MAX_CONCURRENT = 2

    /** Set of download IDs that have been requested to pause. */
    private val pauseRequests = mutableSetOf<String>()

    /** Currently active download IDs. */
    private val activeDownloads = mutableSetOf<String>()

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            dao = DownloadDatabase.getInstance(appContext).downloadDao()
            createNotificationChannel()
            Log.d(TAG, "init() success — DAO ready")

            // Resume any stuck downloads from a previous session
            scope.launch {
                val stuck = dao.getActiveDownloads()
                for (dl in stuck) {
                    if (dl.isDownloading) {
                        Log.w(TAG, "Resetting stuck download from previous session: ${dl.id}")
                        dao.updateStatus(dl.id, DownloadEntity.Status.PENDING)
                    }
                }
                if (stuck.any { it.isDownloading || it.isPending }) {
                    triggerProcessQueue()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "init() FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    // ── UI Flows ──

    fun getAllDownloads(): Flow<List<DownloadEntity>> = dao.getAll()

    fun getCompletedCount(): Flow<Int> = dao.getCompletedCount()

    // ── Actions ──

    /**
     * Enqueue a new download from a resolved Video.
     * @param video The resolved Video with source URL
     * @param videoType The video type (Movie or Episode) for metadata
     * @param providerName Current provider name
     */
    suspend fun enqueue(
        video: Video,
        videoType: Video.Type,
        providerName: String,
        serverName: String = "",
    ) {
        val serverTag = sanitizeFileName(serverName).take(30)
        val id: String
        val title: String
        val subtitle: String?
        val poster: String?
        val type: String
        val fileName: String

        // Use .ts extension for HLS downloads (concatenated TS segments need TS container)
        val isHls = video.type?.contains("mpegURL", ignoreCase = true) == true
                || video.type?.contains("m3u8", ignoreCase = true) == true
                || video.source.contains(".m3u8", ignoreCase = true)
        val ext = if (isHls) "ts" else "mp4"

        when (videoType) {
            is Video.Type.Movie -> {
                id = "${providerName}_movie_${videoType.id}_$serverTag"
                title = videoType.title
                subtitle = serverName.ifBlank { null }
                poster = videoType.poster
                type = "movie"
                fileName = sanitizeFileName("${videoType.title}_${serverTag}.$ext")
            }
            is Video.Type.Episode -> {
                id = "${providerName}_ep_${videoType.tvShow.id}_S${videoType.season.number}E${videoType.number}_$serverTag"
                title = videoType.tvShow.title
                subtitle = "S${videoType.season.number} E${videoType.number}" +
                        (videoType.title?.let { " - $it" } ?: "") +
                        if (serverName.isNotBlank()) " ($serverName)" else ""
                poster = videoType.poster ?: videoType.tvShow.poster
                type = "episode"
                fileName = sanitizeFileName(
                    "${videoType.tvShow.title}_S${videoType.season.number}E${videoType.number}_${serverTag}.$ext"
                )
            }
        }

        // Check if already exists
        val existing = dao.getById(id)
        if (existing != null && (existing.isCompleted || existing.isDownloading)) {
            Log.d(TAG, "Download already exists: $id (${existing.status})")
            showToast("Téléchargement déjà en cours ou terminé")
            return
        }

        val downloadDir = getDownloadDir()
        val filePath = File(downloadDir, fileName).absolutePath

        val headersJson = video.headers?.let { gson.toJson(it) }

        val entity = DownloadEntity(
            id = id,
            title = title,
            subtitle = subtitle,
            poster = poster,
            type = type,
            providerName = providerName,
            sourceUrl = video.source,
            headersJson = headersJson,
            filePath = filePath,
            mimeType = video.type,
        )

        dao.insert(entity)
        Log.d(TAG, "Enqueued download: $id → ${video.source}")
        showToast("⬇ Téléchargement ajouté : $title")

        // Start processing the queue directly
        triggerProcessQueue()
    }

    fun requestPause(id: String) {
        synchronized(pauseRequests) {
            pauseRequests.add(id)
        }
        scope.launch {
            dao.updateStatus(id, DownloadEntity.Status.PAUSED)
        }
    }

    suspend fun pause(id: String) {
        synchronized(pauseRequests) {
            pauseRequests.add(id)
        }
        dao.updateStatus(id, DownloadEntity.Status.PAUSED)
    }

    suspend fun resume(id: String) {
        dao.updateStatus(id, DownloadEntity.Status.PENDING)
        triggerProcessQueue()
    }

    suspend fun cancel(id: String) {
        val download = dao.getById(id) ?: return
        synchronized(pauseRequests) { pauseRequests.add(id) } // stop if active
        delay(200) // let the download loop notice
        File(download.filePath).delete()
        dao.deleteById(id)
    }

    suspend fun retry(id: String) {
        dao.updateStatus(id, DownloadEntity.Status.PENDING)
        triggerProcessQueue()
    }

    suspend fun deleteCompleted(id: String) {
        val download = dao.getById(id) ?: return
        File(download.filePath).delete()
        dao.deleteById(id)
    }

    // ── Internal ──

    internal fun getDao(): DownloadDao = dao

    /**
     * Try to fill available download slots with pending items.
     * Launches up to MAX_CONCURRENT workers concurrently.
     */
    private fun triggerProcessQueue() {
        scope.launch {
            while (true) {
                val currentCount = synchronized(activeDownloads) { activeDownloads.size }
                if (currentCount >= MAX_CONCURRENT) break

                val next = dao.getNextPending() ?: break

                // Claim this download — skip if already active
                val claimed = synchronized(activeDownloads) { activeDownloads.add(next.id) }
                if (!claimed) continue

                // Mark as DOWNLOADING immediately to prevent double-pick
                dao.updateStatus(next.id, DownloadEntity.Status.DOWNLOADING)

                // Launch a worker for this download
                scope.launch { processDownload(next) }

                delay(100) // small gap before filling next slot
            }

            // If nothing is active anymore, clear the notification
            val isEmpty = synchronized(activeDownloads) { activeDownloads.isEmpty() }
            if (isEmpty) clearNotification()
        }
    }

    /**
     * Process a single download. Runs in its own coroutine.
     */
    private suspend fun processDownload(download: DownloadEntity) {
        Log.d(TAG, "Processing download: id=${download.id} title=${download.title}")
        showToast("⬇ Téléchargement : ${download.title}")
        synchronized(pauseRequests) { pauseRequests.remove(download.id) }

        try {
            updateNotification("Téléchargement : ${download.title}", 0)

            val isHls = download.sourceUrl.contains(".m3u8") ||
                    download.sourceUrl.contains("m3u8", ignoreCase = true) ||
                    download.mimeType?.contains("mpegURL", ignoreCase = true) == true ||
                    download.mimeType?.contains("m3u8", ignoreCase = true) == true

            if (isHls) {
                downloadHls(download)
            } else {
                downloadDirect(download)
            }

            if (isPauseRequested(download.id)) {
                Log.d(TAG, "Download paused: ${download.id}")
                synchronized(pauseRequests) { pauseRequests.remove(download.id) }
                updateNotification("En pause : ${download.title}", 0)
            } else {
                dao.markCompleted(download.id)
                showCompletedNotification(download.title)
                showToast("✅ Téléchargement terminé : ${download.title}")
                Log.d(TAG, "Download completed: ${download.id}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${download.id}", e)
            dao.markFailed(download.id, e.message ?: "Unknown error")
            showToast("❌ Échec : ${download.title}")
        } finally {
            synchronized(activeDownloads) {
                activeDownloads.remove(download.id)
            }
            // Try to fill the freed slot with next pending
            triggerProcessQueue()
        }
    }

    // ── Download Methods ──

    /**
     * Download a direct MP4/video file. Routes to parallel or single-connection mode.
     * Uses IDM-style multi-connection download when the server supports Range requests.
     */
    private suspend fun downloadDirect(download: DownloadEntity) {
        val file = File(download.filePath)
        file.parentFile?.mkdirs()
        val headers = parseHeaders(download.headersJson)

        // Probe server for Range support and file size
        val headRequest = Request.Builder()
            .url(download.sourceUrl)
            .head()
            .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        var totalBytes = -1L
        var supportsRange = false

        try {
            val headResponse = withContext(Dispatchers.IO) {
                Extractor.sharedClient.newCall(headRequest).execute()
            }
            totalBytes = headResponse.header("Content-Length")?.toLongOrNull() ?: -1
            supportsRange = headResponse.header("Accept-Ranges").equals("bytes", ignoreCase = true)
            headResponse.close()
        } catch (e: Exception) {
            Log.w(TAG, "HEAD request failed, falling back to single connection: ${e.message}")
        }

        if (totalBytes > PARALLEL_MIN_SIZE && supportsRange) {
            Log.d(TAG, "Direct parallel download: $PARALLEL_CONNECTIONS connections for ${totalBytes / 1_000_000}MB file")
            downloadDirectParallel(download, file, headers, totalBytes)
        } else {
            Log.d(TAG, "Direct single-connection download (size=${totalBytes}, range=$supportsRange)")
            downloadDirectSingle(download, file, headers)
        }
    }

    /**
     * Single-connection direct download with resume support (original behavior).
     */
    private suspend fun downloadDirectSingle(
        download: DownloadEntity,
        file: File,
        headers: Map<String, String>,
    ) {
        val existingLength = if (file.exists()) file.length() else 0L

        val requestBuilder = Request.Builder()
            .url(download.sourceUrl)
            .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }

        if (existingLength > 0) {
            requestBuilder.header("Range", "bytes=$existingLength-")
        }

        val response = withContext(Dispatchers.IO) {
            Extractor.sharedClient.newCall(requestBuilder.build()).execute()
        }

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
            copyStream(inputStream, outputStream, download.id, existingLength, totalBytes)
        } finally {
            inputStream.close()
            outputStream.close()
            response.close()
        }
    }

    /**
     * IDM-style parallel chunk download for direct files.
     * Splits the file into PARALLEL_CONNECTIONS chunks, downloads each with Range headers,
     * writing directly to the correct file offset via RandomAccessFile.
     */
    private suspend fun downloadDirectParallel(
        download: DownloadEntity,
        file: File,
        headers: Map<String, String>,
        totalBytes: Long,
    ) {
        // Progress tracking directory
        val tempDir = File(file.parent, ".dlp_${download.id.hashCode().toUInt()}")
        tempDir.mkdirs()

        // Pre-allocate file if not already done
        if (!file.exists() || file.length() != totalBytes) {
            withContext(Dispatchers.IO) {
                RandomAccessFile(file, "rw").use { it.setLength(totalBytes) }
            }
        }

        // Calculate chunk boundaries
        val chunkSize = totalBytes / PARALLEL_CONNECTIONS
        data class Chunk(val index: Int, val start: Long, val end: Long)

        val chunks = (0 until PARALLEL_CONNECTIONS).map { i ->
            val start = i * chunkSize
            val end = if (i == PARALLEL_CONNECTIONS - 1) totalBytes - 1 else (i + 1) * chunkSize - 1
            Chunk(i, start, end)
        }

        val downloadedTotal = AtomicLong(0L)

        // Check resume progress for each chunk
        for (chunk in chunks) {
            val progressFile = File(tempDir, "chunk_${chunk.index}")
            if (progressFile.exists()) {
                val resumed = progressFile.readText().trim().toLongOrNull()
                if (resumed != null && resumed > chunk.start && resumed <= chunk.end + 1) {
                    downloadedTotal.addAndGet(resumed - chunk.start)
                }
            }
        }

        val resumedBytes = downloadedTotal.get()
        if (resumedBytes > 0) {
            Log.d(TAG, "Direct parallel resume: ${resumedBytes / 1_000_000}MB already downloaded for ${download.id}")
        }

        coroutineScope {
            chunks.map { chunk ->
                launch(Dispatchers.IO) {
                    if (isPauseRequested(download.id)) return@launch

                    // Check resume progress for this chunk
                    val progressFile = File(tempDir, "chunk_${chunk.index}")
                    var currentStart = chunk.start
                    if (progressFile.exists()) {
                        val resumed = progressFile.readText().trim().toLongOrNull()
                        if (resumed != null && resumed > chunk.start && resumed <= chunk.end + 1) {
                            currentStart = resumed
                        }
                    }

                    // Skip if chunk already complete
                    if (currentStart > chunk.end) return@launch

                    val request = Request.Builder()
                        .url(download.sourceUrl)
                        .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                        .header("Range", "bytes=$currentStart-${chunk.end}")
                        .apply { headers.forEach { (k, v) -> header(k, v) } }
                        .build()

                    val response = Extractor.sharedClient.newCall(request).execute()
                    try {
                        if (!response.isSuccessful && response.code != HttpURLConnection.HTTP_PARTIAL) {
                            throw Exception("Chunk ${chunk.index}: HTTP ${response.code}")
                        }

                        val input = response.body?.byteStream()
                            ?: throw Exception("Empty body for chunk ${chunk.index}")

                        val raf = RandomAccessFile(file, "rw")
                        raf.seek(currentStart)

                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        var position = currentStart
                        var lastUpdateTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (isPauseRequested(download.id)) {
                                progressFile.writeText(position.toString())
                                raf.close()
                                return@launch
                            }

                            raf.write(buffer, 0, bytesRead)
                            position += bytesRead
                            val totalDone = downloadedTotal.addAndGet(bytesRead.toLong())

                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime > 500) {
                                lastUpdateTime = now
                                progressFile.writeText(position.toString())
                                val progress = ((totalDone * 100) / totalBytes).toInt().coerceIn(0, 100)
                                dao.updateProgress(download.id, DownloadEntity.Status.DOWNLOADING, totalDone, totalBytes)
                                updateNotification("${download.title}", progress)
                            }
                        }

                        raf.close()
                        progressFile.writeText((chunk.end + 1).toString())
                    } finally {
                        response.close()
                    }
                }
            }
        }

        if (isPauseRequested(download.id)) return

        // Final progress update
        dao.updateProgress(download.id, DownloadEntity.Status.DOWNLOADING, totalBytes, totalBytes)

        // Clean up progress files
        tempDir.listFiles()?.forEach { it.delete() }
        tempDir.delete()
        Log.d(TAG, "Direct parallel download complete: ${download.id}")
    }

    /**
     * Download HLS/m3u8 stream using IDM-style parallel segment downloads.
     * Downloads N segments concurrently to temp files, then concatenates in order.
     * Supports resume by checking which temp segment files already exist.
     */
    private suspend fun downloadHls(download: DownloadEntity) {
        val file = File(download.filePath)
        file.parentFile?.mkdirs()
        val headers = parseHeaders(download.headersJson)

        // Fetch and parse HLS playlist
        val segments = fetchHlsSegments(download.sourceUrl, headers)
        if (segments.isEmpty()) throw Exception("No segments found in HLS playlist")

        Log.d(TAG, "HLS parallel download: ${segments.size} segments × $PARALLEL_CONNECTIONS connections for ${download.id}")

        // Temp directory for individual segment files
        val tempDir = File(file.parent, ".hls_${download.id.hashCode().toUInt()}")
        tempDir.mkdirs()

        val completedSegments = AtomicInteger(0)

        // Check which segments are already downloaded (resume support)
        for (i in segments.indices) {
            val segFile = File(tempDir, "seg_${String.format("%05d", i)}")
            if (segFile.exists() && segFile.length() > 0) {
                completedSegments.incrementAndGet()
            }
        }

        val alreadyDone = completedSegments.get()
        if (alreadyDone > 0) {
            Log.d(TAG, "HLS resume: $alreadyDone/${segments.size} segments already downloaded for ${download.id}")
            val progress = (alreadyDone * 100) / segments.size
            dao.updateProgress(download.id, DownloadEntity.Status.DOWNLOADING, alreadyDone.toLong(), segments.size.toLong())
            updateNotification("${download.title} ($alreadyDone/${segments.size})", progress)
        }

        // Build work queue of segments that still need downloading
        val workChannel = Channel<Pair<Int, String>>(Channel.UNLIMITED)
        for (i in segments.indices) {
            val segFile = File(tempDir, "seg_${String.format("%05d", i)}")
            if (!segFile.exists() || segFile.length() == 0L) {
                workChannel.send(i to segments[i])
            }
        }
        workChannel.close()

        // Launch PARALLEL_CONNECTIONS workers consuming from the channel
        coroutineScope {
            repeat(PARALLEL_CONNECTIONS) { workerIndex ->
                launch(Dispatchers.IO) {
                    for ((index, segUrl) in workChannel) {
                        if (isPauseRequested(download.id)) break

                        val segFile = File(tempDir, "seg_${String.format("%05d", index)}")
                        val request = Request.Builder()
                            .url(segUrl)
                            .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                            .apply { headers.forEach { (k, v) -> header(k, v) } }
                            .build()

                        val response = Extractor.sharedClient.newCall(request).execute()
                        try {
                            if (!response.isSuccessful) {
                                throw Exception("Segment $index: HTTP ${response.code}")
                            }

                            response.body?.byteStream()?.use { input ->
                                // Write to a .tmp file first, rename when complete (atomic)
                                val tmpFile = File(tempDir, "seg_${String.format("%05d", index)}.tmp")
                                FileOutputStream(tmpFile).use { output ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        if (isPauseRequested(download.id)) {
                                            tmpFile.delete() // partial segment, discard
                                            return@launch
                                        }
                                        output.write(buffer, 0, bytesRead)
                                    }
                                }
                                tmpFile.renameTo(segFile) // atomic completion marker
                            }
                        } finally {
                            response.close()
                        }

                        val done = completedSegments.incrementAndGet()
                        val progress = (done * 100) / segments.size
                        dao.updateProgress(download.id, DownloadEntity.Status.DOWNLOADING, done.toLong(), segments.size.toLong())
                        updateNotification("${download.title} ($done/${segments.size})", progress)
                    }
                }
            }
        }

        if (isPauseRequested(download.id)) return

        // Verify all segments are present
        val missing = (0 until segments.size).filter { i ->
            val segFile = File(tempDir, "seg_${String.format("%05d", i)}")
            !segFile.exists() || segFile.length() == 0L
        }
        if (missing.isNotEmpty()) {
            throw Exception("Missing ${missing.size} segments after download: first=${missing.first()}")
        }

        // Concatenate all segments into final file in order
        Log.d(TAG, "HLS: concatenating ${segments.size} segments → ${file.name}")
        withContext(Dispatchers.IO) {
            FileOutputStream(file).use { output ->
                for (i in segments.indices) {
                    val segFile = File(tempDir, "seg_${String.format("%05d", i)}")
                    segFile.inputStream().use { input ->
                        input.copyTo(output, BUFFER_SIZE)
                    }
                }
            }
        }

        // Clean up temp directory
        tempDir.listFiles()?.forEach { it.delete() }
        tempDir.delete()
        Log.d(TAG, "HLS parallel download complete: ${download.id}")
    }

    /**
     * Fetch and parse HLS playlist, resolving master playlists to segment lists.
     */
    private suspend fun fetchHlsSegments(sourceUrl: String, headers: Map<String, String>): List<String> {
        val playlistResponse = withContext(Dispatchers.IO) {
            Extractor.sharedClient.newCall(
                Request.Builder()
                    .url(sourceUrl)
                    .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .build()
            ).execute()
        }
        val playlistBody = playlistResponse.body?.string() ?: throw Exception("Empty HLS playlist")
        playlistResponse.close()

        val baseUrl = sourceUrl.substringBeforeLast("/") + "/"

        return if (playlistBody.contains("#EXT-X-STREAM-INF")) {
            // Master playlist — pick highest quality, then parse segment playlist
            val bestStreamUrl = parseMasterPlaylist(playlistBody, baseUrl)
                ?: throw Exception("No streams found in master playlist")

            val segResponse = withContext(Dispatchers.IO) {
                Extractor.sharedClient.newCall(
                    Request.Builder().url(bestStreamUrl)
                        .header("User-Agent", Extractor.DEFAULT_USER_AGENT).build()
                ).execute()
            }
            val segBody = segResponse.body?.string() ?: throw Exception("Empty segment playlist")
            segResponse.close()

            val segBaseUrl = bestStreamUrl.substringBeforeLast("/") + "/"
            parseSegmentPlaylist(segBody, segBaseUrl)
        } else {
            parseSegmentPlaylist(playlistBody, baseUrl)
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
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var downloadedBytes = startBytes
        var lastNotifyTime = 0L
        var bytesRead: Int

        while (input.read(buffer).also { bytesRead = it } != -1) {
            if (isPauseRequested(downloadId)) return

            output.write(buffer, 0, bytesRead)
            downloadedBytes += bytesRead

            val now = System.currentTimeMillis()
            if (now - lastNotifyTime > 500) {
                lastNotifyTime = now
                val progress = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                dao.updateProgress(downloadId, DownloadEntity.Status.DOWNLOADING, downloadedBytes, totalBytes)
                updateNotification("Téléchargement…", progress)
            }
        }

        // Final progress update
        dao.updateProgress(downloadId, DownloadEntity.Status.DOWNLOADING, downloadedBytes, totalBytes)
    }

    // ── Helpers ──

    /**
     * Parse download headers JSON into a Map.
     */
    private fun parseHeaders(headersJson: String?): Map<String, String> {
        if (headersJson == null) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(headersJson, Map::class.java) as? Map<String, String> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun isPauseRequested(id: String): Boolean {
        return synchronized(pauseRequests) { pauseRequests.contains(id) }
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
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_downloads)
            .setContentTitle("StreamFlix")
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    private fun clearNotification() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }

    private fun showCompletedNotification(title: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_downloads)
            .setContentTitle("Téléchargement terminé")
            .setContentText(title)
            .setAutoCancel(true)
            .setSilent(false)
            .build()
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.notify(COMPLETED_NOTIFICATION_ID + title.hashCode(), notification)
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun getDownloadDir(): File {
        val dir = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "StreamFlix"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace("[^a-zA-Z0-9._\\-àâéèêëïîôùûüçÀÂÉÈÊËÏÎÔÙÛÜÇ ]".toRegex(), "_")
            .replace("\\s+".toRegex(), "_")
            .take(200)
    }
}
