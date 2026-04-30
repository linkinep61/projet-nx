package com.streamflixreborn.streamflix.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Publishes downloaded video files into the system MediaStore so they appear in
 * /storage/emulated/0/Movies/StreamFlix/ — visible to VLC, Kodi, gallery, file managers.
 *
 * Strategy: download to the app-private external dir as before (parallel chunks via
 * RandomAccessFile.seek() keep working), then on completion `publish()` the file into
 * MediaStore via ContentResolver. No MANAGE_EXTERNAL_STORAGE, no scoped-storage friction.
 *
 * Android 10+ : uses RELATIVE_PATH + IS_PENDING. ContentResolver writes to the right place.
 * Android 9-  : uses DATA column directly (still requires WRITE_EXTERNAL_STORAGE).
 */
object MediaStoreHelper {

    private const val TAG = "MediaStoreHelper"
    const val SUBFOLDER = "Movies/StreamFlix"

    /**
     * Move a completed download file into MediaStore.
     *
     * @param context   Application context.
     * @param srcFile   The file currently sitting in app-private dir (will be deleted on success).
     * @param displayName Human-readable filename (e.g. "Man_on_Fire_S1E2.ts").
     * @param mimeType  Standard MIME type ("video/mp4", "video/mp2t" for HLS-concatenated TS).
     * @return The MediaStore content URI (e.g. content://media/external/video/media/12345)
     *         or null on failure (caller falls back to using the original srcFile path).
     */
    fun publish(
        context: Context,
        srcFile: File,
        displayName: String,
        mimeType: String,
    ): Uri? {
        if (!srcFile.exists() || srcFile.length() == 0L) {
            Log.w(TAG, "publish() called on missing/empty file: ${srcFile.absolutePath}")
            return null
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, SUBFOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val publicMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val target = File(publicMovies, "StreamFlix/$displayName")
                target.parentFile?.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, target.absolutePath)
            }
        }

        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore.insert failed for $displayName: ${e.message}", e)
            return null
        } ?: run {
            Log.e(TAG, "MediaStore.insert returned null for $displayName")
            return null
        }

        // Stream bytes from app-private file into the MediaStore URI.
        try {
            resolver.openOutputStream(uri)?.use { output ->
                srcFile.inputStream().use { input ->
                    input.copyTo(output, 64 * 1024)
                }
            } ?: run {
                Log.e(TAG, "openOutputStream returned null for $uri")
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Copy to MediaStore failed for $displayName: ${e.message}", e)
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        // Mark the entry as no longer pending (Android 10+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finalize = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            try {
                resolver.update(uri, finalize, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear IS_PENDING on $uri: ${e.message}")
                // Not fatal — the file is still readable, but until pending is cleared
                // it may not show up in some galleries until app process owning it dies.
            }
        }

        // Source file no longer needed.
        runCatching { srcFile.delete() }

        Log.d(TAG, "Published $displayName → $uri (${srcFile.length()} bytes)")
        return uri
    }

    /**
     * Delete an entry that lives in MediaStore.
     * Returns true if the URI was deleted (or wasn't a content URI to begin with —
     * the caller is expected to also handle plain file paths separately).
     */
    fun delete(context: Context, uriOrPath: String): Boolean {
        if (!uriOrPath.startsWith("content://")) return false
        val uri = runCatching { Uri.parse(uriOrPath) }.getOrNull() ?: return false
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore.delete failed for $uri: ${e.message}")
            false
        }
    }
}
