package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// Gestionnaire generique de proxy de redirection (kokoflix/kakaflix/newPlayer).
// Suit le redirect HTTP puis delegue a l'extracteur du domaine resolu.
// Calque sur upstream streamflix-reborn FrenchStreamProvider L814.
class KakaflixExtractor : Extractor() {

    override val name = "RedirectProxy"
    override val mainUrl = "https://kokoflix.lol"

    override val aliasUrls = listOf(
        "https://kakaflix.lol"
    )

    override val rotatingDomain: List<Regex> = listOf(
        Regex("""newPlayer\.php""")
    )

    override suspend fun extract(link: String): Video {
        Log.d("RedirectProxy", "Resolving proxy: $link")

        val client = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val resolvedUrl = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(link)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .header("Referer", "https://www.frenchstream.re/")
                .build()

            client.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        }

        Log.d("RedirectProxy", "Resolved: $link -> $resolvedUrl")

        if (resolvedUrl == link ||
            resolvedUrl.contains("kokoflix.lol", ignoreCase = true) ||
            resolvedUrl.contains("kakaflix.lol", ignoreCase = true)) {
            throw Exception("Redirect proxy failed: $link -> $resolvedUrl")
        }

        return Extractor.extract(resolvedUrl)
    }
}
