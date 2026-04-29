package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

class ShareCloudyExtractor : Extractor() {

    override val name = "ShareCloudy"
    override val mainUrl = "https://sharecloudy.com"

    override suspend fun extract(link: String): Video {
        val service = Extractor.createGsonService<Service>(mainUrl)

        val doc = service.get(link).body() ?: ""
        val regex = Regex("""file:\s*"([^"]+)"""")
        val match = regex.find(doc)
        val url = match?.groupValues?.get(1)
        if (url == null) throw Exception("Cannot find video")

        return Video(url, headers = mapOf("Referer" to mainUrl))
    }

    private interface Service {
        @GET
        suspend fun get(@Url url: String): Response<String>
    }
}