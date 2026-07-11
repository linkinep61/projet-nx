package com.streamflixreborn.streamflix.utils

import android.util.Base64
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Custom DRM callback pour M6+ Widevine via DRMtoday (lic.drmtoday.com).
 *
 * Le serveur DRMtoday `/license-proxy-widevine/cenc/` retourne un JSON wrapper
 * `{"license": "base64_encoded_widevine_key_response"}`. ExoPlayer
 * `HttpMediaDrmCallback` standard passe le response body brut à
 * `MediaDrm.provideKeyResponse()` qui attend des bytes raw → erreur
 * `ERROR_DRM_LICENSE_PARSE`.
 *
 * Ce callback :
 *   1. POST le challenge Widevine au serveur DRMtoday avec
 *      `x-dt-auth-token: <upfront-token>` header.
 *   2. Parse le response JSON pour extraire le champ `license`.
 *   3. Base64-decode → bytes raw → MediaDrm peut les traiter.
 */
class M6DrmCallback(
    private val licenseUrl: String,
    private val headers: Map<String, String>,
) : MediaDrmCallback {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    override fun executeProvisionRequest(
        uuid: UUID,
        request: ExoMediaDrm.ProvisionRequest
    ): ByteArray {
        // Provision request URL standard Google. Pas spécifique M6.
        val url = request.defaultUrl + "&signedRequest=" + String(request.data, Charsets.UTF_8)
        val req = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("Provision HTTP ${resp.code}")
            }
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    override fun executeKeyRequest(
        uuid: UUID,
        request: ExoMediaDrm.KeyRequest
    ): ByteArray {
        if (uuid != C.WIDEVINE_UUID) {
            throw java.io.IOException("Unsupported DRM UUID: $uuid")
        }
        val challenge = request.data
        Log.d(TAG, "executeKeyRequest: challenge=${challenge.size}B → $licenseUrl")
        val reqBuilder = Request.Builder()
            .url(licenseUrl)
            .post(challenge.toRequestBody("application/octet-stream".toMediaType()))
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                val bodyText = String(body, Charsets.UTF_8)
                Log.e(TAG, "License HTTP ${resp.code} headers=${resp.headers}")
                Log.e(TAG, "License HTTP ${resp.code} body=${bodyText.take(500)}")
                throw java.io.IOException("License HTTP ${resp.code}: ${bodyText.take(80)}")
            }
            // DRMtoday renvoie JSON {"license": "base64..."}. Parse + decode.
            val bodyStr = String(body, Charsets.UTF_8)
            return try {
                val json = JSONObject(bodyStr)
                val licenseB64 = json.optString("license", "")
                if (licenseB64.isBlank()) {
                    Log.e(TAG, "License JSON has no 'license' field: ${bodyStr.take(200)}")
                    throw java.io.IOException("Missing 'license' field in DRMtoday response")
                }
                val licenseBytes = Base64.decode(licenseB64, Base64.DEFAULT)
                Log.d(TAG, "License extracted from JSON wrapper (${licenseBytes.size}B raw)")
                licenseBytes
            } catch (e: org.json.JSONException) {
                // Pas un JSON → response déjà raw bytes
                Log.d(TAG, "License is raw bytes (${body.size}B, not JSON-wrapped)")
                body
            }
        }
    }

    companion object {
        private const val TAG = "M6DrmCallback"
    }
}
