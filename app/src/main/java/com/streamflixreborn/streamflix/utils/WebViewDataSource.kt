package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Custom ExoPlayer DataSource that routes HTTP requests through a WebView's
 * network stack via JavaScript XHR.
 *
 * Why: Some CDNs (e.g. cdn-tnmr.org used by LuluVdo/LuluStream) block all
 * non-WebView HTTP clients with 403 Forbidden, likely via TLS fingerprinting.
 *
 * How it works:
 * 1. A hidden WebView is on file:///android_asset/blank.html with
 *    allowUniversalAccessFromFileURLs=true (CORS bypass from file:// origin)
 * 2. When ExoPlayer calls open(), we inject JS that does an XHR fetch
 * 3. Binary data is converted to base64 in JS and stored in a window variable
 * 4. Kotlin polls for the result via evaluateJavascript (same pattern as
 *    LuluVdoExtractor's M3U8 fetch — proven to work)
 * 5. CountDownLatch synchronizes ExoPlayer's loader thread with the main thread
 * 6. The fetched bytes are buffered and served to ExoPlayer via read()
 */
class WebViewDataSource(
    private val webView: WebView,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val TAG = "WebViewDataSource"
        private const val FETCH_TIMEOUT_MS = 20_000L
        private const val POLL_INTERVAL_MS = 150L
        private val instanceCounter = AtomicInteger(0)
    }

    private val fetchId = "wvds${instanceCounter.incrementAndGet()}"
    private var dataBuffer: ByteArray? = null
    private var readPosition = 0
    private var currentUri: Uri? = null
    private var opened = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        currentUri = dataSpec.uri
        Log.d(TAG, "[$fetchId] open: ${url.take(120)}")

        val latch = CountDownLatch(1)
        var fetchedData: ByteArray? = null
        var fetchError: String? = null

        mainHandler.post {
            try {
                val escapedUrl = url.replace("\\", "\\\\").replace("'", "\\'")

                // Inject XHR that stores base64 result in window variable
                // (same proven pattern as LuluVdoExtractor's M3U8 fetch)
                webView.evaluateJavascript("""
                    (function() {
                        window.__${fetchId} = null;
                        window.__${fetchId}_err = null;
                        try {
                            var xhr = new XMLHttpRequest();
                            xhr.open('GET', '$escapedUrl', true);
                            xhr.responseType = 'arraybuffer';
                            xhr.withCredentials = true;
                            xhr.setRequestHeader('Accept', '*/*');
                            xhr.timeout = ${FETCH_TIMEOUT_MS};
                            xhr.onload = function() {
                                if (xhr.status >= 200 && xhr.status < 400) {
                                    var bytes = new Uint8Array(xhr.response);
                                    var binary = '';
                                    var chunk = 8192;
                                    for (var i = 0; i < bytes.length; i += chunk) {
                                        binary += String.fromCharCode.apply(null,
                                            bytes.subarray(i, Math.min(i + chunk, bytes.length)));
                                    }
                                    window.__${fetchId} = btoa(binary);
                                } else {
                                    window.__${fetchId}_err = 'HTTP ' + xhr.status;
                                }
                            };
                            xhr.onerror = function() { window.__${fetchId}_err = 'XHR error'; };
                            xhr.ontimeout = function() { window.__${fetchId}_err = 'XHR timeout'; };
                            xhr.send();
                        } catch(e) {
                            window.__${fetchId}_err = 'JS: ' + e.message;
                        }
                    })();
                """.trimIndent(), null)

                // Start polling for result (on main thread via postDelayed)
                pollResult(0) { data, error ->
                    fetchedData = data
                    fetchError = error
                    latch.countDown()
                }

            } catch (e: Exception) {
                fetchError = "WebView error: ${e.message}"
                latch.countDown()
            }
        }

        // Block ExoPlayer's loader thread until WebView delivers the data
        if (!latch.await(FETCH_TIMEOUT_MS + 5000, TimeUnit.MILLISECONDS)) {
            cleanup()
            throw HttpDataSource.HttpDataSourceException(
                "WebView fetch timed out: ${url.take(80)}",
                dataSpec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }

        if (fetchError != null) {
            cleanup()
            throw HttpDataSource.HttpDataSourceException(
                "WebView fetch failed: $fetchError",
                dataSpec,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }

        dataBuffer = fetchedData
        readPosition = 0

        val totalSize = dataBuffer?.size?.toLong() ?: 0L
        Log.d(TAG, "[$fetchId] fetched $totalSize bytes for ${url.takeLast(60)}")

        // Handle position offset from DataSpec (for range requests)
        if (dataSpec.position > 0 && dataBuffer != null) {
            readPosition = dataSpec.position.toInt().coerceAtMost(dataBuffer!!.size)
        }

        opened = true
        transferInitializing(dataSpec)
        transferStarted(dataSpec)

        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalSize - readPosition
        }
    }

    /**
     * Poll for XHR result stored in window variable.
     * Runs on main thread via Handler.postDelayed.
     */
    private fun pollResult(
        attempt: Int,
        callback: (ByteArray?, String?) -> Unit
    ) {
        val maxAttempts = (FETCH_TIMEOUT_MS / POLL_INTERVAL_MS).toInt() + 10

        if (attempt > maxAttempts) {
            Log.w(TAG, "[$fetchId] polling exhausted after $attempt attempts")
            callback(null, "Polling timeout")
            return
        }

        mainHandler.postDelayed({
            webView.evaluateJavascript("""
                (function() {
                    if (window.__${fetchId}_err) return 'ERR:' + window.__${fetchId}_err;
                    if (window.__${fetchId} !== null) return 'OK';
                    return '';
                })();
            """.trimIndent()) { result ->
                val raw = result?.trim()?.removeSurrounding("\"") ?: ""

                if (raw.startsWith("ERR:")) {
                    val error = raw.substringAfter("ERR:")
                    Log.e(TAG, "[$fetchId] XHR error: $error")
                    cleanup()
                    callback(null, error)
                    return@evaluateJavascript
                }

                if (raw == "OK") {
                    // Data ready — fetch the base64 string
                    webView.evaluateJavascript("window.__${fetchId}") { b64Result ->
                        try {
                            val b64 = b64Result?.trim()?.removeSurrounding("\"") ?: ""
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            Log.d(TAG, "[$fetchId] received ${bytes.size} bytes (attempt $attempt)")
                            cleanup()
                            callback(bytes, null)
                        } catch (e: Exception) {
                            Log.e(TAG, "[$fetchId] base64 decode failed: ${e.message}")
                            cleanup()
                            callback(null, "Base64 decode: ${e.message}")
                        }
                    }
                    return@evaluateJavascript
                }

                // Not ready yet — poll again
                pollResult(attempt + 1, callback)
            }
        }, if (attempt == 0) 100L else POLL_INTERVAL_MS)
    }

    /** Clean up window variables */
    private fun cleanup() {
        mainHandler.post {
            try {
                webView.evaluateJavascript(
                    "window.__${fetchId} = null; window.__${fetchId}_err = null;", null
                )
            } catch (_: Exception) {}
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = dataBuffer ?: return C.RESULT_END_OF_INPUT
        if (readPosition >= data.size) return C.RESULT_END_OF_INPUT

        val bytesToRead = minOf(length, data.size - readPosition)
        System.arraycopy(data, readPosition, buffer, offset, bytesToRead)
        readPosition += bytesToRead
        bytesTransferred(bytesToRead)

        return bytesToRead
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        dataBuffer = null
        readPosition = 0
        if (opened) {
            opened = false
            transferEnded()
        }
        currentUri = null
    }

    /**
     * Factory that creates WebViewDataSource instances sharing a WebView.
     * The WebView must be on file:///android_asset/blank.html with
     * allowUniversalAccessFromFileURLs=true for CORS-free access.
     */
    class Factory(private val webView: WebView) : DataSource.Factory {
        override fun createDataSource(): DataSource = WebViewDataSource(webView)
    }
}
