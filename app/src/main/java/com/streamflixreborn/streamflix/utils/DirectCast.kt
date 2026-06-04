package com.streamflixreborn.streamflix.utils

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * 2026-06-01 : Cast v2 direct — bypass Google Play Services.
 * Se connecte directement au port 8009 de la Chromecast via TLS,
 * lance le Default Media Receiver et envoie un flux HLS.
 *
 * Protocole : protobuf CastMessage + JSON payloads.
 * Ref: node-castv2, gcast PROTOCOL.md
 */
object DirectCast {

    private const val TAG = "DirectCast"
    private const val CAST_PORT = 8009
    private const val DEFAULT_RECEIVER = "CC1AD845"
    private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    private const val SENDER_ID = "sender-0"
    private const val RECEIVER_ID = "receiver-0"

    private var socket: SSLSocket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    private var requestId = 1
    private var transportId: String? = null

    // Callback quand le cast est prêt
    private var onReady: (() -> Unit)? = null
    private var pendingMediaUrl: String? = null
    private var pendingHeaders: Map<String, String>? = null
    private var pendingContentType: String? = null
    private var pendingTitle: String? = null

    /**
     * Connecte à la Chromecast et envoie un flux vidéo.
     * @param ip IP de la Chromecast
     * @param videoUrl URL du flux (m3u8, mp4)
     * @param title Titre à afficher
     * @param contentType MIME type
     */
    fun castTo(
        ip: String,
        videoUrl: String,
        title: String = "Streamflix",
        contentType: String = "application/x-mpegURL",
        headers: Map<String, String>? = null,
    ) {
        pendingMediaUrl = videoUrl
        pendingHeaders = headers
        pendingTitle = title
        pendingContentType = contentType

        CoroutineScope(Dispatchers.IO).launch {
            try {
                disconnect()
                Log.d(TAG, "Connecting to $ip:$CAST_PORT...")

                // TLS sans vérification de cert (Chromecast utilise self-signed)
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }), SecureRandom())

                val sock = sslContext.socketFactory.createSocket() as SSLSocket
                sock.connect(InetSocketAddress(ip, CAST_PORT), 5000)
                sock.soTimeout = 10000
                sock.startHandshake()
                socket = sock
                output = DataOutputStream(sock.outputStream)
                input = DataInputStream(sock.inputStream)
                Log.d(TAG, "TLS connected to $ip")

                // Start read loop
                startReadLoop()
                // Start heartbeat
                startHeartbeat()

                // Step 1: CONNECT to receiver
                sendMessage(NS_CONNECTION, RECEIVER_ID, """{"type":"CONNECT","origin":{}}""")

                // Step 2: LAUNCH Default Media Receiver
                val launchId = requestId++
                sendMessage(NS_RECEIVER, RECEIVER_ID,
                    """{"type":"LAUNCH","appId":"$DEFAULT_RECEIVER","requestId":$launchId}""")

                Log.d(TAG, "LAUNCH sent, waiting for RECEIVER_STATUS...")

            } catch (e: Exception) {
                Log.e(TAG, "Cast connection failed: ${e.message}")
                disconnect()
            }
        }
    }

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val inp = input ?: return@launch
                while (isActive) {
                    // Read 4-byte big-endian length
                    val len = inp.readInt()
                    if (len <= 0 || len > 1_000_000) continue
                    val buf = ByteArray(len)
                    inp.readFully(buf)

                    // Parse protobuf manually (simple approach without protobuf-lite)
                    val msg = parseCastMessage(buf)
                    if (msg != null) handleMessage(msg)
                }
            } catch (e: Exception) {
                if (isActive) Log.w(TAG, "Read loop ended: ${e.message}")
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(5000)
                try {
                    sendMessage(NS_HEARTBEAT, RECEIVER_ID, """{"type":"PING"}""")
                } catch (_: Exception) { break }
            }
        }
    }

    private fun handleMessage(msg: CastMsg) {
        val payload = msg.payload
        try {
            val json = JSONObject(payload)
            val type = json.optString("type", "")

            when {
                type == "PING" -> {
                    // Reply PONG
                    try {
                        sendMessage(NS_HEARTBEAT, msg.sourceId, """{"type":"PONG"}""")
                    } catch (_: Exception) {}
                }
                type == "RECEIVER_STATUS" -> {
                    // Extract transportId from the first app
                    val apps = json.optJSONObject("status")?.optJSONArray("applications")
                    if (apps != null && apps.length() > 0) {
                        val app = apps.getJSONObject(0)
                        val tid = app.optString("transportId", "")
                        if (tid.isNotBlank() && transportId == null) {
                            transportId = tid
                            Log.d(TAG, "Got transportId: $tid")

                            // Step 4: CONNECT to the app session
                            sendMessage(NS_CONNECTION, tid, """{"type":"CONNECT","origin":{}}""")

                            // Step 5: LOAD media via proxy local
                            val rawUrl = pendingMediaUrl ?: return
                            val title = pendingTitle ?: "Streamflix"
                            val rawCt = pendingContentType ?: "application/x-mpegURL"
                            // Le proxy sert un m3u8 réécrit — garder le contentType HLS
                            val ct = if (rawCt.contains("mpegurl", true)) "application/x-mpegurl" else rawCt
                            val url = CastProxy.start(rawUrl, pendingHeaders, ct) ?: rawUrl
                            // VOD = BUFFERED, IPTV live = LIVE
                            val isLive = rawUrl.contains("/live/") || rawUrl.contains("linkip")
                            val streamType = if (isLive) "LIVE" else "BUFFERED"
                            val loadId = requestId++
                            val loadPayload = """
                                {
                                    "type": "LOAD",
                                    "requestId": $loadId,
                                    "media": {
                                        "contentId": "$url",
                                        "contentType": "$ct",
                                        "streamType": "$streamType"
                                    },
                                    "currentTime": 0,
                                    "autoplay": true
                                }
                            """.trimIndent()
                            sendMessage(NS_MEDIA, tid, loadPayload)
                            Log.d(TAG, "LOAD sent: $title → $url")
                        }
                    }
                }
                type == "MEDIA_STATUS" -> {
                    // Log le status complet pour debug
                    val statuses = json.optJSONArray("status")
                    if (statuses != null && statuses.length() > 0) {
                        val s = statuses.getJSONObject(0)
                        val state = s.optString("playerState", "?")
                        val reason = s.optString("idleReason", "")
                        val errMsg = s.optJSONObject("media")?.optString("contentId", "")
                        Log.d(TAG, "MEDIA: state=$state reason=$reason url=${errMsg?.take(80)}")
                        if (reason == "ERROR") {
                            Log.e(TAG, "Cast playback ERROR — full: ${s.toString().take(500)}")
                        }
                    }
                }
                type == "CLOSE" -> {
                    Log.d(TAG, "Session closed by Chromecast")
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleMessage error: ${e.message}")
        }
    }

    /**
     * Envoie un CastMessage encodé en protobuf minimaliste.
     * On encode le protobuf manuellement sans dépendance protobuf-lite.
     */
    @Synchronized
    private fun sendMessage(namespace: String, destinationId: String, payload: String) {
        val out = output ?: throw Exception("Not connected")
        val msg = buildCastMessage(SENDER_ID, destinationId, namespace, payload)
        out.writeInt(msg.size)
        out.write(msg)
        out.flush()
    }

    /** Encode un CastMessage en protobuf manuellement. */
    private fun buildCastMessage(sourceId: String, destId: String, namespace: String, payload: String): ByteArray {
        val baos = java.io.ByteArrayOutputStream()

        // Field 1: protocol_version = 0 (CASTV2_1_0), varint
        writeProtoField(baos, 1, 0) // field 1, wire type 0 (varint), value 0

        // Field 2: source_id (string)
        writeProtoString(baos, 2, sourceId)

        // Field 3: destination_id (string)
        writeProtoString(baos, 3, destId)

        // Field 4: namespace (string)
        writeProtoString(baos, 4, namespace)

        // Field 5: payload_type = 0 (STRING), varint
        writeProtoField(baos, 5, 0)

        // Field 6: payload_utf8 (string)
        writeProtoString(baos, 6, payload)

        return baos.toByteArray()
    }

    private fun writeProtoField(baos: java.io.ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        // Wire type 0 = varint
        val tag = (fieldNumber shl 3) or 0
        writeVarint(baos, tag)
        writeVarint(baos, value)
    }

    private fun writeProtoString(baos: java.io.ByteArrayOutputStream, fieldNumber: Int, value: String) {
        // Wire type 2 = length-delimited
        val tag = (fieldNumber shl 3) or 2
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(baos, tag)
        writeVarint(baos, bytes.size)
        baos.write(bytes)
    }

    private fun writeVarint(baos: java.io.ByteArrayOutputStream, value: Int) {
        var v = value
        while (v > 0x7F) {
            baos.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        baos.write(v and 0x7F)
    }

    /** Parse un CastMessage protobuf manuellement. */
    private fun parseCastMessage(data: ByteArray): CastMsg? {
        var sourceId = ""
        var destId = ""
        var namespace = ""
        var payload = ""
        var pos = 0

        try {
            while (pos < data.size) {
                val tag = readVarint(data, pos)
                pos = tag.second
                val fieldNumber = tag.first ushr 3
                val wireType = tag.first and 0x07

                when (wireType) {
                    0 -> { // varint
                        val v = readVarint(data, pos)
                        pos = v.second
                    }
                    2 -> { // length-delimited
                        val len = readVarint(data, pos)
                        pos = len.second
                        val bytes = data.copyOfRange(pos, pos + len.first)
                        pos += len.first
                        when (fieldNumber) {
                            2 -> sourceId = String(bytes, Charsets.UTF_8)
                            3 -> destId = String(bytes, Charsets.UTF_8)
                            4 -> namespace = String(bytes, Charsets.UTF_8)
                            6 -> payload = String(bytes, Charsets.UTF_8)
                        }
                    }
                    else -> break // unknown wire type
                }
            }
        } catch (_: Exception) {}

        return if (payload.isNotBlank()) CastMsg(sourceId, destId, namespace, payload) else null
    }

    private fun readVarint(data: ByteArray, startPos: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = startPos
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return Pair(result, pos)
    }

    fun disconnect() {
        readJob?.cancel()
        heartbeatJob?.cancel()
        readJob = null
        heartbeatJob = null
        transportId = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        output = null
        input = null
    }

    fun isConnected(): Boolean = socket?.isConnected == true && transportId != null

    data class CastMsg(
        val sourceId: String,
        val destId: String,
        val namespace: String,
        val payload: String,
    )
}
