package com.streamflixreborn.streamflix.extractors

import java.util.Locale
import java.util.UUID
import com.streamflixreborn.streamflix.models.Video
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import com.google.gson.JsonParser

class DailymotionExtractor : Extractor() {

    override val name = "Dailymotion"
    override val mainUrl = "https://www.dailymotion.com"
    override val aliasUrls = listOf("https://geo.dailymotion.com")

    // Token `sec=` dans l'URL m3u8 expire vite (~quelques secondes !) et
    // est signé sur la route IP/DNS de l'appel JSON. Comme l'utilisateur
    // reclique souvent < 30s après un fail, même TTL=30s servait du périmé.
    // TTL=0 = re-extraction systématique (~500ms surcoût mais fiable).
    override val cacheTtlMs: Long = 0L

    override suspend fun extract(link: String): Video {
        val id = link.substringAfterLast("/").substringAfter("video=")

        val service = Extractor.createGsonService<Service>(aliasUrls[0])
        val response = service.getJson(
            id = id,
            referer = "${aliasUrls[0]}/player/xtv3w.html?",
            locale = Locale.getDefault().language,
            v1st = UUID.randomUUID().toString(),
            ts = (System.currentTimeMillis() / 1000).toString(),
            viewId = List(19) { (('a'..'z') + ('0'..'9')).random() }.joinToString("")
        )

        val json = JsonParser.parseString(response.string()).asJsonObject
        val manifestUrl = json.getAsJsonObject("qualities")
            ?.getAsJsonArray("auto")
            ?.get(0)?.asJsonObject
            ?.get("url")?.asString
            ?: throw Exception("Manifest URL not found")

        // 2026-09-16 (user « la chaîne Fun Radio n'est pas lue ») : ExoPlayer se
        //   prenait un HTTP 403 sur le manifeste ALORS QUE QualityProbe, lui, le
        //   lisait très bien 2 secondes plus tôt (2179 kb/s, 720p). Ce n'est donc
        //   ni l'URL ni le jeton qui sont en cause, mais l'identité de l'appelant :
        //   la sonde passe par OkHttp (User-Agent de l'app), le lecteur par
        //   DefaultHttpDataSource, qui n'envoyait QUE le Referer. Le CDN
        //   Dailymotion refuse une requête sans User-Agent navigateur ni Origin.
        //   On fournit les trois, pour que le lecteur se présente comme la sonde.
        return Video(
            source = manifestUrl,
            headers = mapOf(
                "Referer" to "${aliasUrls[0]}/",
                "Origin" to aliasUrls[0],
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            )
        )
    }

    private interface Service {
        @GET("video/{id}.json?legacy=true&player-id=xtv3w&is_native_app=0&app=com.dailymotion.neon&client_type=website&section_type=player&component_style=_&parallelCalls=1")
        suspend fun getJson(
            @Path("id") id: String,
            @Header("Referer") referer: String,
            @Query("locale") locale: String,
            @Query("dmV1st") v1st: String,
            @Query("dmTs") ts: String,
            @Query("dmViewId") viewId: String
        ): ResponseBody
    }
}
