package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.http.GET
import retrofit2.http.Url

class EinschaltenExtractor : Extractor() {

    override val name = "Einschalten"
    override val mainUrl = "https://einschalten.in"

    private interface Service {
        @GET
        suspend fun getWatch(@Url url: String): ResponseBody
    }

    private val service = Extractor.createGsonService<Service>(mainUrl)

    fun server(videoType: Video.Type): Video.Server? {
        return when (videoType) {
            is Video.Type.Movie -> Video.Server(
                id = name,
                name = name,
                src = "$mainUrl/api/movies/${videoType.id}/watch"
            )
            is Video.Type.Episode -> null // Einschalten does not support episodes
        }
    }

    override suspend fun extract(link: String): Video {
        throw Exception("[Einschalten] is offline (domain einschalten.in inaccessible/SSL timeout, last checked 2026-05-01)")
    }
}
