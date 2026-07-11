package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * MediaDrmCallback pour RMC BFM Play — Widevine license via ws-backendtv.
 *
 * La licence DRM BFM utilise un header `customdata` contenant le token SSO,
 * l'accountId et l'entitlementId. Le serveur retourne directement les bytes
 * Widevine bruts (pas de wrapper JSON comme DRMtoday/M6).
 *
 * Pipeline :
 *   POST licenseUrl
 *   Headers: customdata=<...>&tokenSSO=<BFM_xxx>&accountId=<...>&entitlementId=<...>
 *   Body: Widevine challenge (application/octet-stream)
 *   Response: Widevine license bytes bruts
 */
@UnstableApi
class BfmDrmCallback(
    private val licenseUrl: String,
    private val headers: Map<String, String>
) : MediaDrmCallback {

    companion object {
        private const val TAG = "BfmDrmCallback"
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    override fun executeKeyRequest(
        uuid: UUID,
        request: ExoMediaDrm.KeyRequest
    ): ByteArray {
        val challenge = request.data
        Log.d(TAG, "executeKeyRequest: challenge=${challenge.size}B → POST $licenseUrl")

        val body = challenge.toRequestBody(null)
        val reqBuilder = Request.Builder()
            .url(licenseUrl)
            .post(body)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        val resp = client.newCall(reqBuilder.build()).execute()
        val respBytes = resp.body?.bytes() ?: ByteArray(0)

        if (!resp.isSuccessful) {
            val preview = String(respBytes, Charsets.UTF_8).take(300)
            Log.e(TAG, "License HTTP ${resp.code}: $preview")
            throw RuntimeException("BFM DRM license failed: HTTP ${resp.code}")
        }

        Log.d(TAG, "License OK: ${respBytes.size}B")
        return respBytes
    }

    override fun executeProvisionRequest(
        uuid: UUID,
        request: ExoMediaDrm.ProvisionRequest
    ): ByteArray {
        // Provisioning standard Google — pas spécifique BFM
        val url = request.defaultUrl + "&signedRequest=" +
            String(request.data, Charsets.UTF_8)
        val req = Request.Builder().url(url).build()
        val resp = client.newCall(req).execute()
        return resp.body?.bytes() ?: ByteArray(0)
    }
}
