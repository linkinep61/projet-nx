package com.streamflixreborn.streamflix.download

import androidx.room.*

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey
    val id: String, // unique ID: "{providerName}_{movieId}" or "{providerName}_{tvShowId}_S{season}E{episode}"

    val title: String,
    val subtitle: String? = null, // e.g. "S1 E3 - Episode Title"
    val poster: String? = null,

    /** "movie" or "episode" */
    val type: String,

    val providerName: String,

    /** Resolved video source URL (for re-download) */
    val sourceUrl: String,

    /** HTTP headers needed (JSON map) */
    val headersJson: String? = null,

    /** Local file path */
    val filePath: String,

    /** Download status: PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED */
    val status: String = Status.PENDING,

    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,

    /** Error message if failed */
    val errorMessage: String? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,

    /** MIME type of the source (e.g. "application/x-mpegURL" for HLS) */
    val mimeType: String? = null,
) {
    object Status {
        const val PENDING = "PENDING"
        const val DOWNLOADING = "DOWNLOADING"
        const val PAUSED = "PAUSED"
        const val COMPLETED = "COMPLETED"
        const val FAILED = "FAILED"
    }

    val progress: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

    val isCompleted get() = status == Status.COMPLETED
    val isDownloading get() = status == Status.DOWNLOADING
    val isPending get() = status == Status.PENDING
    val isPaused get() = status == Status.PAUSED
    val isFailed get() = status == Status.FAILED
}
