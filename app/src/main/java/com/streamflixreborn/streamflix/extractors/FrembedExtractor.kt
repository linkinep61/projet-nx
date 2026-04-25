package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.text.replaceFirstChar
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FrembedExtractor (var newUrl: String = "") : Extractor() {

    override val name = "Frembed"
    val defaultUrl = "https://frembed.cyou"
    override var mainUrl = newUrl.ifBlank { defaultUrl }

    data class listLinks (
        val link1: String?=null,
        val link2: String?=null,
        val link3: String?=null,
        val link4: String?=null,
        val link5: String?=null,
        val link6: String?=null,
        val link7: String?=null,
        val link1vostfr: String?=null,
        val link2vostfr: String?=null,
        val link3vostfr: String?=null,
        val link4vostfr: String?=null,
        val link5vostfr: String?=null,
        val link6vostfr: String?=null,
        val link7vostfr: String?=null,
        val link1vo: String?=null,
        val link2vo: String?=null,
        val link3vo: String?=null,
        val link4vo: String?=null,
        val link5vo: String?=null,
        val link6vo: String?=null,
        val link7vo: String?=null,
    )

    private fun getExtractorName(url: String): String {
        // Try to identify using the central extractor registry first
        val serviceName = Extractor.identifyServiceName(url)
        if (serviceName != null) return serviceName

        return url.substringAfter("://")
            .substringBefore("/")
            .substringBefore(".")
            .replace("crystaltreatmenteast", "voe")
            .replace("lauradaydo", "voe")
            .replace("lancewhosedifficult", "voe")
            .replace("dianaavoidthey", "voe")
            .replace("jefferycontrolmodel", "voe")
            .replace("myvidplay", "dood")
            .replace("playmogo", "dood")
            .replaceFirstChar { it.uppercase() }
    }

    fun listLinks.toServers(): List<Video.Server> {
        return listOf(link1, link2, link3, link4, link5, link6, link7,
                                         link1vostfr, link2vostfr, link3vostfr, link4vostfr, link5vostfr, link6vostfr, link7vostfr,
                                         link1vo, link2vo, link3vo, link4vo, link5vo, link6vo, link7vo)
            .mapIndexedNotNull { index, data ->
                if (data.isNullOrEmpty()) return@mapIndexedNotNull null
                val lang = when { index < 7 -> "French"
                                  index < 14 -> "VOSTFR"
                                  else -> "VO" }
                (if (data.startsWith("/")) mainUrl.removeSuffix("/") + data else data).let {
                    Video.Server(id = "link$index", name = "${getExtractorName(it)} ($lang)", src = it)
                }
            }
    }

    private interface Service {
        companion object {
            private const val USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

            /** Client for API calls — follows redirects, sends Origin header */
            fun build(baseUrl: String): Service {
                val apiClient = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val url = request.url
                        val origin = "${url.scheme}://${url.host}"
                        val newRequest = request.newBuilder()
                            .header("Referer", "$origin/")
                            .header("Origin", origin)
                            .build()
                        chain.proceed(newRequest)
                    }
                    .dns(DnsResolver.doh)
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(apiClient)
                    .build()
                    .create(Service::class.java)
            }

            /** Client for stream link resolution — no redirects, captures Location header */
            fun buildStreamResolver(baseUrl: String): Service {
                val resolverClient = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val url = request.url
                        val referer = "${url.scheme}://${url.host}/"
                        val newRequest = request.newBuilder()
                            .header("Referer", referer)
                            .build()
                        chain.proceed(newRequest)
                    }
                    .dns(DnsResolver.doh)
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(resolverClient)
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET("api/films")
        suspend fun getMovieLinks(
            @Query("id") id: String,
            @Query("idType") idType: String = "tmdb",
            @Header("User-Agent") userAgent: String = USER_AGENT,
            @Header("Content-Type") contentType: String = "application/json"
        ): listLinks

        @GET("api/series")
        suspend fun getTvShowLinks(
            @Query("id") id: String,
            @Query("sa") sa: Int,
            @Query("epi") epi: Int,
            @Query("idType") idType: String = "tmdb",
            @Header("User-Agent") userAgent: String = USER_AGENT,
            @Header("Content-Type") contentType: String = "application/json"
        ): listLinks

        @GET
        suspend fun getStreamLinks(
            @Url url: String,
            @Header("User-Agent") userAgent: String = USER_AGENT
        ): Response<listLinks>
    }

    private val service = Service.build(mainUrl)
    private val streamResolver = Service.buildStreamResolver(mainUrl)

    override suspend fun extract(link: String): Video {
        throw Exception("None")
    }

    // Reliability ranking: lower = better
    private val reliabilityOrder = mapOf(
        "Vidara" to 1,
        "Vidsonic" to 2,
        "Rpmvid" to 3,
        "StreamWish" to 4,
        "Streamix" to 4,
        "Voe" to 5,
        "Dood" to 6,
        "Filemoon" to 10,
        "Netu" to 99,   // Last: cfglobalcdn CDN often blocked by French ISPs
    )
    private val defaultReliability = 7

    suspend fun servers(videoType: Video.Type): List<Video.Server> {
        Log.d("FrembedExtractor", "servers() called, mainUrl=$mainUrl, videoType=$videoType")
        return try {
            val ret = when(videoType) { is Video.Type.Movie -> service.getMovieLinks( videoType.id)
                                        is Video.Type.Episode -> service.getTvShowLinks(videoType.tvShow.id, videoType.season.number, videoType.number) }
            Log.d("FrembedExtractor", "API response: link1=${ret.link1?.take(60)}, link2=${ret.link2?.take(60)}, link3=${ret.link3?.take(60)}")
            val initialServers = ret.toServers()
            Log.d("FrembedExtractor", "Initial servers: ${initialServers.size} found")

            val resolvedServers = coroutineScope {
                initialServers.map { server ->
                    async(Dispatchers.IO) {
                        try {
                            val response = streamResolver.getStreamLinks(server.src)
                            val redirect = response.headers()["Location"]
                            if (!redirect.isNullOrEmpty()) {
                                val fullRedirect = if (redirect.startsWith("//")) "https:$redirect" else redirect
                                val lang = server.name.substringAfter(" (").substringBefore(")")
                                server.copy(
                                    name = "${getExtractorName(fullRedirect)} ($lang)",
                                    src = fullRedirect
                                )
                            } else {
                                server
                            }
                        } catch (e: Exception) {
                            server
                        }
                    }
                }.awaitAll()
            }

            // Compound sort: language priority (French > VOSTFR > VO), then reliability
            resolvedServers.sortedWith(compareBy<Video.Server> { server ->
                val name = server.name.uppercase()
                when {
                    name.contains("FRENCH") || (name.contains("VF") && !name.contains("VOSTFR")) -> 0
                    name.contains("VOSTFR") || name.contains("VOST") -> 2
                    name.contains("VO") -> 3
                    else -> 1
                }
            }.thenBy { server ->
                val serviceName = Extractor.identifyServiceName(server.src)
                reliabilityOrder[serviceName] ?: defaultReliability
            })
        } catch (e: Exception) {
            Log.e("FrembedExtractor", "servers() FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun server(videoType: Video.Type): Video.Server {
        return this.servers(videoType).first()
    }
}