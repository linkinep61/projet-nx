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

        return Video(
            source = manifestUrl,
            headers = mapOf("Referer" to aliasUrls[0])
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
