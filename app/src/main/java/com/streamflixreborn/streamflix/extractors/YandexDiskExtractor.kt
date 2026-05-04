package com.streamflixreborn.streamflix.extractors

import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Resolves a Yandex Disk public-share video link to a direct MP4 URL via the
 * official public API (`cloud-api.yandex.net`), no auth required.
 *
 * Input link  :  https://yadi.sk/i/HASH        (or https://disk.yandex.ru/i/HASH)
 * Yandex API  :  GET cloud-api.yandex.net/v1/disk/public/resources?public_key=<link>
 *                → JSON { name, file: "https://downloader.disk.yandex.ru/disk/...mp4", mime_type: "video/mp4", size: ... }
 *
 * The `file` URL is a direct MP4 (typically 200-500 MB for a TV episode), playable
 * by ExoPlayer without further extraction. Lots of FR series are stored on Yandex
 * Disk because it's a generous free hosting service that doesn't get DMCA'd much.
 *
 * Discovered 2026-05-04 via reverse-engineering allostreaming.one + waaatch.art's
 * embed.maz.quest backend, which serves vieilles séries FR via yadi.sk links.
 */
class YandexDiskExtractor : Extractor() {

    override val name = "Yandex Disk"
    override val mainUrl = "https://yadi.sk"
    override val aliasUrls = listOf(
        "https://disk.yandex.ru",
        "https://disk.yandex.com",
    )

    override suspend fun extract(link: String): Video {
        // Normalize disk.yandex.* to yadi.sk for the public_key param. Both work
        // server-side but yadi.sk is the canonical short form.
        val publicKey = link
            .replace("disk.yandex.ru", "yadi.sk")
            .replace("disk.yandex.com", "yadi.sk")

        val apiUrl = "https://cloud-api.yandex.net/v1/disk/public/resources" +
            "?public_key=" + URLEncoder.encode(publicKey, "UTF-8") +
            "&fields=file,name,mime_type,size,type"

        val client = NetworkClient.default
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()
            ?: throw Exception("Yandex Disk: empty body from cloud-api.yandex.net")

        val json = JSONObject(body)

        // Yandex returns { error: "..." } on missing/private files
        json.optString("error").takeIf { it.isNotBlank() }?.let { err ->
            throw Exception("Yandex Disk: $err")
        }

        val type = json.optString("type")
        if (type != "file") {
            throw Exception("Yandex Disk: shared resource is not a file (type=$type)")
        }

        val fileUrl = json.optString("file").takeIf { it.isNotBlank() }
            ?: throw Exception("Yandex Disk: no `file` URL in API response")

        // The downloader.disk.yandex.ru URL is signed and IP-bound — but unlike
        // Dailymotion's sec= token, it's tolerant enough that ExoPlayer on the
        // user's device can stream it after the resolve succeeds from the same
        // device. No special headers needed.
        val mime = json.optString("mime_type", "video/mp4")
        val isMp4 = mime.contains("mp4", ignoreCase = true) || fileUrl.contains(".mp4")

        return Video(
            source = fileUrl,
            type = if (isMp4) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8,
        )
    }

    // Token Yandex Disk peut périmer en quelques minutes -> cache court.
    override val cacheTtlMs: Long = 60_000L
}
