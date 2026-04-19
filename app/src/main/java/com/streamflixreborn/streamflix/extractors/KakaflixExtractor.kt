package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class KakaflixExtractor : Extractor() {

    override val name = "Kakaflix"
    override val mainUrl = "https://kakaflix.lol"

    override suspend fun extract(link: String): Video {
        // Kakaflix is a redirect proxy - follow the redirect to get the actual embed URL
        val client = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val resolvedUrl = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(link)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .build()

            client.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        }

        if (resolvedUrl == link || resolvedUrl.contains("kakaflix.lol")) {
            throw Exception("Kakaflix redirect failed: $resolvedUrl")
        }

        // Delegate to the appropriate extractor based on the resolved URL
        return Extractor.extract(resolvedUrl)
    }
}
