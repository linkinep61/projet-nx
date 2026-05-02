package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url

class USTRExtractor: Extractor() {
    override val name = "USTR"
    override val mainUrl = "https://ups2up.fun"
    override val aliasUrls = listOf("https://up4stream.com", "https://up4fun.top")

    override suspend fun extract(link: String): Video {
        throw Exception("[USTR] is offline (domain ups2up.fun dead, last checked 2026-05-01)")
    }


    private interface Service {
        @GET
        suspend fun getSource(@Url url: String): Document
    }

}