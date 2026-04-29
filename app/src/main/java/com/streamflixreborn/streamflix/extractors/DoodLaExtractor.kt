package com.streamflixreborn.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.StringConverterFactory
import org.jsoup.nodes.Document
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.net.URI

open class DoodLaExtractor : Extractor() {

    override val name = "DoodStream"
    override val mainUrl = "https://dood.la"
    override val aliasUrls = listOf(
        "https://dsvplay.com",
        "https://myvidplay.com",
        "https://playmogo.com",
        "https://do7go.com",
        "https://d000d.com",
        "https://dood.work",
        "https://doply.net",
        "https://doodstream.me"
    )

    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    override suspend fun extract(link: String): Video {
        val linkBaseUrl = getBaseUrl(link)
        val retrofit = Retrofit.Builder()
            .baseUrl(linkBaseUrl)
            .client(Extractor.sharedClient)
            .addConverterFactory(JsoupConverterFactory.create())
            .addConverterFactory(StringConverterFactory.create())
            .build()
        val service = retrofit.create(Service::class.java)

        val embedUrl = link.replace("/d/", "/e/")
        val response = service.get(embedUrl, link)
        val document = response.body() ?: throw Exception("Failed to load embed page")
        
        // Get the final URL after redirects to use the correct domain for pass_md5
        val finalUrl = response.raw().request.url.toString()
        val finalBaseUrl = getBaseUrl(finalUrl)

        val md5Path = Regex("""/pass_md5/[^"'\s]+""").find(document.toString())?.value
            ?: throw Exception("Could not find md5 path")

        val md5Url = finalBaseUrl + md5Path

        val videoPrefix = service.getString(md5Url, finalUrl).trim()

        val token = md5Url.substringAfterLast("/")
        val sep = if ('?' in videoPrefix) '&' else '?'
        val url = videoPrefix +
                createHashTable() +
                "${sep}token=${token}&expiry=${System.currentTimeMillis()}"

        return Video(
            source = url,
            headers = mapOf(
                "Referer" to finalBaseUrl
            )
        )
    }

    private fun createHashTable(): String {
        return buildString {
            repeat(10) {
                append(alphabet.random())
            }
        }
    }

    private fun getBaseUrl(url: String) = URI(url).let { "${it.scheme}://${it.host}" }


    class DoodLiExtractor : DoodLaExtractor() {
        override var mainUrl = "https://dood.li"
    }

    class DoodExtractor : DoodLaExtractor() {
        override val mainUrl = "https://vide0.net"
    }


    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
        ): Response<Document>

        @GET
        suspend fun getString(
            @Url url: String,
            @Header("Referer") referer: String,
        ): String
    }
}
