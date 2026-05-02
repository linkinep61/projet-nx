package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url

class LamovieExtractor : Extractor() {

    override val name = "Lamovie"
    override val mainUrl = "https://lamovie.link"
    override val aliasUrls = listOf("https://vimeos.net")

    override suspend fun extract(link: String): Video {
        throw Exception("[Lamovie] is offline (domain lamovie.link inaccessible/timeout, last checked 2026-05-01)")
    }


    private interface Service {
        @GET
        suspend fun get(@Url url: String): Document
    }
}