package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class VidflixExtractor : Extractor() {

    override val name = "Vidflix"
    override val mainUrl = "https://vidflix.club"

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Episode -> "$mainUrl/api/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
                is Video.Type.Movie -> "$mainUrl/api/movie/${videoType.id}"
            },
        )
    }

    override suspend fun extract(link: String): Video {
        val service = Extractor.createGsonService<Service>(mainUrl)
        val referer = link.replace("/api/", "/")

        val response = service.getVideoData(link, referer = referer)
        val videoUrl = response.video_url

        // Delegate to RpmvidExtractor as requested
        return RpmvidExtractor().extract(videoUrl).copy(
            subtitles = response.subtitles.map {
                Video.Subtitle(it.label, it.url, it.default)
            }
        )
    }

    private interface Service {
        @GET
        suspend fun getVideoData(
            @Url url: String,
            @Header("Referer") referer: String
        ): Response
    }

    data class Response(
        val video_url: String,
        val subtitles: List<Subtitle> = emptyList()
    )

    data class Subtitle(
        val label: String,
        val url: String,
        val default: Boolean = false
    )
}
