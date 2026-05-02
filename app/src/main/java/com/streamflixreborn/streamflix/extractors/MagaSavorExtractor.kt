package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DecryptHelper
import java.util.regex.Pattern
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class MagaSavorExtractor : Extractor() {
    override val name = "MagaSavors"
    override val mainUrl = "https://magasavor.net"

    override suspend fun extract(link: String): Video {
        throw Exception("[MagaSavor] is offline (domain magasavor.net dead, last checked 2026-05-01)")
    }

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("Accept") accept: String = "text/html",
            @Header("User-Agent") userAgent: String = USER_AGENT,
        ): Document
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    }
}
