package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class MoviesapiExtractor : Extractor() {

    override val name = "Moviesapi"
    // 2026-05-21 : moviesapi.club est NXDOMAIN — le service a migré vers moviesapi.to
    //   (vérifié 200, embed /movie/<tmdbId> OK). Le .club est aussi visé par un
    //   blocage judiciaire. Le user : "elles ont juste changé de nom de domaine".
    override val mainUrl = "https://moviesapi.to/"

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Movie -> "$mainUrl/movie/${videoType.id}"
                is Video.Type.Episode -> ""
            },
        )
    }

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<Service>(mainUrl)

        val iframe = service.get(link, referer = "https://pressplay.top/")
            .selectFirst("iframe")
            ?.attr("src")
            ?: throw Exception("Can't retrieve iframe")

        return VidoraExtractor().extract(iframe)
    }

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("referer") referer: String = "",
        ): Document
    }
}