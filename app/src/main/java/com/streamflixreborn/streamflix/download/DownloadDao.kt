package com.streamflixreborn.streamflix.download

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun getAllSnapshot(): List<DownloadEntity>

    @Query("UPDATE downloads SET filePath = :filePath WHERE id = :id")
    suspend fun updateFilePath(id: String, filePath: String)

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY createdAt DESC")
    fun getByStatus(status: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('PENDING', 'DOWNLOADING') ORDER BY createdAt ASC")
    suspend fun getActiveDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'PENDING' ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextPending(): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Update
    suspend fun update(download: DownloadEntity)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE downloads SET status = :status, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes WHERE id = :id")
    suspend fun updateProgress(id: String, status: String, downloadedBytes: Long, totalBytes: Long)

    @Query("UPDATE downloads SET status = 'COMPLETED', completedAt = :completedAt WHERE id = :id")
    suspend fun markCompleted(id: String, completedAt: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = 'FAILED', errorMessage = :error WHERE id = :id")
    suspend fun markFailed(id: String, error: String)

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'COMPLETED'")
    fun getCompletedCount(): Flow<Int>
}
