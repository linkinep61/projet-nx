package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.json.JSONObject
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class OnRegardeOuExtractor : Extractor() {

    override val name = "OnRegardeOu"
    override val mainUrl = "https://onregardeou.site"

    private val service = Extractor.createJsoupService<Service>(mainUrl)

    // Reliability ranking: lower = better. Servers with lower scores appear first.
    private val reliabilityOrder = mapOf(
        "Vidara" to 1,
        "Vidsonic" to 2,
        "Rpmvid" to 3,
        "StreamWish" to 4,
        "Streamix" to 4,
        "Filemoon" to 10,  // CDN files expire often → last
    )
    private val defaultReliability = 5

    suspend fun expand(link: String, referer: String = mainUrl, suffix: String = ""): List<Video.Server> {
        val doc = service.get(link, referer)

        val script = doc.select("script")
            .firstOrNull { it.html().contains("videoData") }
            ?.html() ?: return emptyList()

        val jsonRaw = script.substringAfter("const videoData=")
            .substringBefore("};") + "}"

        val json = try {
            JSONObject(jsonRaw)
        } catch (e: Exception) {
            return emptyList()
        }
        val servers = json.getJSONArray("servers")

        val list = mutableListOf<Video.Server>()

        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i) ?: continue

            val originalName = server.optString("name", "Server$i")
            val url = server.optString("url").replace("\\/", "/")

            if (url.isEmpty()) continue

            // Detect hosting service from URL
            val serviceName = Extractor.identifyServiceName(url)
            val displayName = if (serviceName != null) {
                "$suffix$serviceName"
            } else {
                "$suffix$originalName"
            }

            list.add(
                Video.Server(
                    "${this.name}_$i",
                    name = displayName,
                    src = url
                )
            )
        }

        // Sort by reliability: best services first
        return list.sortedBy { server ->
            val serviceName = Extractor.identifyServiceName(server.src)
            reliabilityOrder[serviceName] ?: defaultReliability
        }
    }

    override suspend fun extract(link: String): Video {
        throw Exception("Use expand() instead")
    }

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("User-agent") useragent: String = "Mozilla/5.0"
        ): Document
    }
}