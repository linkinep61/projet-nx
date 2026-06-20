package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * POST HTTPS via Cronet (= TLS fingerprint Chrome natif BoringSSL).
 *
 * Certains CDN (= CloudFront WAF sur M6 6cloud.fr) bloquent OkHttp/Conscrypt
 * avec 403 + page HTML d'erreur. Cronet utilise exactement les mêmes cipher
 * suites + ClientHello que Chrome → indistinguable d'un browser légitime côté
 * CDN. Réutilise le CronetEngine pré-initialisé au boot par StreamFlixApp
 * (= 0 cost après le 1er call).
 */
object CronetPost {
    private const val TAG = "CronetPost"

    /** POST synchrone avec body + headers. Retourne {status, body} ou null si fail. */
    fun post(
        ctx: Context,
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 15_000L,
    ): Pair<Int, String>? {
        val engine = com.streamflixreborn.streamflix.StreamFlixApp
            .getCronetEngine(ctx) as? CronetEngine ?: run {
            Log.w(TAG, "Cronet engine not available")
            return null
        }
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        val responseBody = StringBuilder()
        var status = 0
        var failed = false

        try {
            val callback = object : UrlRequest.Callback() {
                override fun onRedirectReceived(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    newLocationUrl: String
                ) {
                    request.followRedirect()
                }

                override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                    status = info.httpStatusCode
                    request.read(ByteBuffer.allocateDirect(32 * 1024))
                }

                override fun onReadCompleted(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    byteBuffer: ByteBuffer
                ) {
                    byteBuffer.flip()
                    val bytes = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(bytes)
                    responseBody.append(String(bytes, Charsets.UTF_8))
                    byteBuffer.clear()
                    request.read(byteBuffer)
                }

                override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                    latch.countDown()
                }

                override fun onFailed(
                    request: UrlRequest,
                    info: UrlResponseInfo?,
                    error: CronetException
                ) {
                    failed = true
                    Log.w(TAG, "Cronet onFailed: ${error.message}")
                    latch.countDown()
                }

                override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                    latch.countDown()
                }
            }

            val builder = engine.newUrlRequestBuilder(url, callback, executor)
                .setHttpMethod("POST")
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            builder.setUploadDataProvider(
                UploadDataProviders.create(body.toByteArray(Charsets.UTF_8)),
                executor
            )
            val request = builder.build()
            request.start()

            val gotResp = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            try { executor.shutdownNow() } catch (_: Throwable) {}
            if (!gotResp) {
                Log.w(TAG, "Cronet POST timeout $timeoutMs ms on $url")
                return null
            }
            if (failed) return null
            return Pair(status, responseBody.toString())
        } catch (e: Throwable) {
            Log.e(TAG, "Cronet POST failed: ${e.message}", e)
            try { executor.shutdownNow() } catch (_: Throwable) {}
            return null
        }
    }
}
