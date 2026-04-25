package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import androidx.media3.common.MimeTypes
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.utils.DnsResolver
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.Locale
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RpmvidExtractor : Extractor() {
    override val name = "Rpmvid"
    override val mainUrl = "https://rpmvid.com"
    override val aliasUrls = listOf("https://cubeembed.rpmvid.com", "https://bummi.upns.xyz", "https://loadm.cam", "https://anibum.playerp2p.online", "https://pelisplus.upns.pro", "https://pelisplus.rpmstream.live", "https://pelisplus.strp2p.com", "https://flemmix.upns.pro", "https://moflix.rpmplay.xyz", "https://moflix.upns.xyz", "https://flix2day.xyz", "https://primevid.click",
        "https://totocoutouno.rpmlive.online", "https://dismoiceline.uns.bio", "https://doremifasol.ezplayer.me", "https://marcus.p2pstream.vip","https://animeav1.uns.bio",
        "https://serix.upns.live",
        "https://coflix.upn.one",
        "https://flemmix.farm", "https://flemmix.rpmlive.online", "https://flemmix.upns.xyz", "https://flemmix.rpmstream.live", "https://flemmix.strp2p.com", "https://flemmix.ezplayer.me", "https://flemmix.uns.bio", "https://flemmix.upns.live", "https://flemmix.upn.one", "https://flemmix.p2pstream.vip")

    override val rotatingDomain = listOf(
        Regex("flemmix\\.[a-z]+(?:\\.[a-z]+)?/embed"),
        Regex("flemmix\\.[a-z]+(?:\\.[a-z]+)?/e/"),
    )

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
        private val KEY = "kiemtienmua911ca".toByteArray()
        private val IV = "1234567890oiuytr".toByteArray()
    }

    private val client = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .addInterceptor(object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request().newBuilder()
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .build()
                return chain.proceed(request)
            }
        })
        .build()

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Query("id") id: String,
            @Query("w") w: String,
            @Query("h") h: String,
            @Query("r") r: String = "",
        ): String

        companion object {
            fun build(baseUrl: String, client: OkHttpClient): Service {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .client(client)
                    .build()
                return retrofit.create(Service::class.java)
            }
        }
    }

    override suspend fun extract(link: String): Video {
        val id = extractId(link) ?: throw Exception("Invalid link: missing id after #")
        val url = URL(link)
        val mainLink = "${url.protocol}://${url.host}"
        val service = Service.build(mainLink, client)
        val apiUrl = "$mainLink/api/v1/video"

        val hexResponse = service.get(
            url = apiUrl,
            referer = mainLink,
            id = id,
            w = "1920",
            h = "1080",
        )

        val decryptedJson = decryptHexPayloadSafe(hexResponse)
        val json = JsonParser.parseString(decryptedJson).asJsonObject
        val hlsPath = json.get("hls")?.asString?.takeIf { it.isNotEmpty() }
        val hlsTiktok = json.get("hlsVideoTiktok")?.asString?.takeIf { it.isNotEmpty() }
        var cfPath = json.get("cf")?.asString?.takeIf { it.isNotEmpty() }
        val cfExpire = json.get("cfExpire")?.asString?.takeIf { it.isNotEmpty() }

        val (finalUrl, headers) = when {
            !hlsPath.isNullOrEmpty() -> {
                toAbsoluteUrl(mainLink, hlsPath) to mapOf("Referer" to mainLink)
            }
            !hlsTiktok.isNullOrEmpty() -> {
                val v = extractTiktokV(json)
                val query = if (!v.isNullOrEmpty()) "?v=$v" else ""
                toAbsoluteUrl(mainLink, "$hlsTiktok$query") to mapOf("Referer" to mainLink)
            }
            !cfPath.isNullOrEmpty() -> {
                cfPath = buildCloudFlareUrl(cfPath, cfExpire, json)
                toAbsoluteUrl(mainLink, cfPath!!) to mapOf("Referer" to mainLink)
            }
            else -> throw Exception("Missing hls, hlsVideoTiktok or cf in response")
        }

        val defaultSub = json.getAsJsonObject("defaultSubtitle")
                                ?.get("defaultSubtitle")?.asString.orEmpty()
        val subtitles = json.getAsJsonObject("subtitle")
            ?.entrySet()
            ?.map { (label, file) ->
                Video.Subtitle(
                    label = label,
                    file = file.asString.orEmpty(),
                    default = defaultSub.isNotEmpty() && label.equals(defaultSub, ignoreCase = true)
                )
            }.orEmpty()

        return Video(
            source = finalUrl,
            subtitles,
            headers = headers,
            type = MimeTypes.APPLICATION_M3U8
        )
    }

    private fun extractId(link: String): String? {
        // Format 1: https://domain/embed#VIDEO_ID
        val idx = link.indexOf('#')
        if (idx != -1 && idx != link.lastIndex) {
            return link.substring(idx + 1).substringBefore("&")
        }
        // Format 2: https://domain/e/VIDEO_ID or https://domain/embed/VIDEO_ID
        val pathMatch = Regex("/(?:e|embed)/([a-zA-Z0-9_-]+)").find(link)
        if (pathMatch != null) {
            return pathMatch.groupValues[1]
        }
        // Format 3: ?id=VIDEO_ID
        val queryMatch = Regex("[?&]id=([a-zA-Z0-9_-]+)").find(link)
        if (queryMatch != null) {
            return queryMatch.groupValues[1]
        }
        return null
    }

    private fun decryptHexPayloadSafe(hex: String): String {
        val cleaned = hex.trim()
        if (cleaned.isEmpty()) throw Exception("Empty encrypted payload")
        return try {
            val bytes = hexToBytes(cleaned)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
            val decrypted = cipher.doFinal(bytes)
            decrypted.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw Exception("Failed to decrypt payload: ${e.message}")
        }
    }

    private fun hexToBytes(input: String): ByteArray {
        val cleaned = input.lowercase(Locale.US).replace(Regex("[^0-9a-f]"), "")
        require(cleaned.length >= 2) { "Invalid hex payload" }
        val even = if (cleaned.length % 2 == 0) cleaned else "0$cleaned"
        val out = ByteArray(even.length / 2)
        var i = 0
        var j = 0
        while (i < even.length) {
            out[j++] = ((even[i].digitToInt(16) shl 4) or even[i + 1].digitToInt(16)).toByte()
            i += 2
        }
        return out
    }

    private fun toAbsoluteUrl(origin: String, path: String): String {
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            if (path.startsWith("/")) "$origin$path" else "$origin/$path"
        }
    }

    private fun extractTiktokV(json: com.google.gson.JsonObject): String? = try {
        val configStr = json.get("streamingConfig")?.asString ?: return null
        val config = JsonParser.parseString(configStr).asJsonObject
        config.getAsJsonObject("adjust")
            ?.getAsJsonObject("Tiktok")
            ?.getAsJsonObject("params")
            ?.get("v")?.asString
    } catch (_: Exception) {
        null
    }

    private fun buildCloudFlareUrl(cfPath: String, cfExpire: String?, json: com.google.gson.JsonObject): String {
        var t: String? = null
        var e: String? = null
        try {
            val configStr = json.get("streamingConfig")?.asString
            if (configStr != null) {
                val streamingConfig = JsonParser.parseString(configStr).asJsonObject
          