package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import androidx.media3.common.MimeTypes
import okhttp3.MediaType.Companion.toMediaType
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class VideasyExtractor : Extractor() {
    override val name = "Videasy"
    override val mainUrl = "https://api.videasy.net"

    data class ServerConfig(
        val name: String,
        val endpoint: String,
        val movieOnly: Boolean = false
    )

    // 2026-05-04 : grand ménage — sur 8 endpoints "english" historiques de
    // api.videasy.net, 6 sont morts (404 / fetch failed / TypeError) :
    //   moviebox (Cypher), 1movies (Sage), primesrcme (Jett),
    //   primewire (Reyna), m4uhd (Breach), hdmovie (Vyse)
    // Restent vivants :
    //   - mb-flix       -> Phoenix (nouveau, ce que player.videasy.net utilise)
    //   - myflixerzupcloud -> Neon (filtré côté Movix car sources Netflix random)
    //   - cdn           -> Yoru (movies only)
    // Vérifié manuellement le 4 mai 2026.
    private val englishServers = listOf(
        ServerConfig("Phoenix", "mb-flix"),
        ServerConfig("Neon", "myflixerzupcloud"),
        ServerConfig("Yoru", "cdn", movieOnly = true)
    )

    fun servers(videoType: Video.Type, language: String): List<Video.Server> {
        return when (language) {
            "en" -> {
                englishServers.mapNotNull { config ->
                    if (config.movieOnly && videoType !is Video.Type.Movie) return@mapNotNull null
                    
                    val url = when (videoType) {
                        is Video.Type.Movie -> {
                            val year = videoType.releaseDate.split("-").firstOrNull() ?: ""
                            "$mainUrl/${config.endpoint}/sources-with-title?title=${videoType.title}&mediaType=movie&year=$year&tmdbId=${videoType.id}&imdbId=${videoType.imdbId ?: ""}"
                        }
                        is Video.Type.Episode -> {
                            val year = videoType.tvShow.releaseDate?.split("-")?.firstOrNull() ?: ""
                            "$mainUrl/${config.endpoint}/sources-with-title?title=${videoType.tvShow.title}&mediaType=tv&year=$year&tmdbId=${videoType.tvShow.id}&imdbId=${videoType.tvShow.imdbId ?: ""}&episodeId=${videoType.number}&seasonId=${videoType.season.number}"
                        }
                    }
                    
                    // 2026-05-03 : ces servers sont les players "english"
                    // de Videasy. Audio anglais original = "VO". Le label
                    // est explicite pour que l'utilisateur voit clairement
                    // ce qu'il choisit (avant tout marqué juste "Videasy").
                    Video.Server(
                        id = "${config.name} (Videasy VO)",
                        name = "${config.name} (Videasy VO)",
                        src = url
                    )
                }
            }
            else -> {
                // 2026-05-03 : FR+TV activé (avant : return emptyList()).
                // Movix sert les épisodes VOSTFR via Videasy (player.videasy.net),
                // sans cet appel on perdait toutes ces sources sur les vieilles
                // séries TV (ex: New York 911 S1E1).
                // Label langue explicite : Videasy "meine"/"cuevana-spanish"
                // sert généralement les pistes audio/sous-titres dans la
                // langue cible donc on tag comme tel.
                val serverName = when (language) {
                    "de" -> "Killjoy (Videasy DE)"
                    "it" -> "Harbor (Videasy IT)"
                    "fr" -> "Chamber (Videasy VOSTFR)"
                    "es" -> "Kayo (Videasy ES)"
                    else -> return emptyList()
                }
                
                val videasyLang = when (language) {
                    "de" -> "german"
                    "it" -> "italian"
                    "fr" -> "french"
                    "es" -> "spanish"
                    else -> return emptyList()
                }

                val endpoint = when (language) {
                    "es" -> "cuevana-spanish"
                    else -> "meine"
                }

                val url = when (videoType) {
                    is Video.Type.Movie -> {
                        val year = videoType.releaseDate.split("-").firstOrNull() ?: ""
                        "$mainUrl/$endpoint/sources-with-title?title=${videoType.title}&mediaType=movie&year=$year&tmdbId=${videoType.id}&imdbId=${videoType.imdbId ?: ""}&language=$videasyLang"
                    }
                    is Video.Type.Episode -> {
                        val year = videoType.tvShow.releaseDate?.split("-")?.firstOrNull() ?: ""
                        "$mainUrl/$endpoint/sources-with-title?title=${videoType.tvShow.title}&mediaType=tv&year=$year&tmdbId=${videoType.tvShow.id}&imdbId=${videoType.tvShow.imdbId ?: ""}&episodeId=${videoType.number}&seasonId=${videoType.season.number}&language=$videasyLang"
                    }
                }

                listOf(Video.Server(
                    id = serverName,
                    name = serverName,
                    src = url
                ))
            }
        }
    }

    fun server(videoType: Video.Type, language: String): Video.Server? {
        return servers(videoType, language).firstOrNull()
    }

    override suspend fun extract(link: String): Video {
        val client = NetworkClient.default

        // 1. Get encrypted data from api.videasy.net
        val request = Request.Builder()
            .url(link)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
            .build()

        val response = client.newCall(request).execute()
        val encData = response.body?.string() ?: throw Exception("Videasy: empty body from api.videasy.net")
        // 2026-05-03 : sur les contenus mal indexés (vieilles séries type
        // NY911 1999) api.videasy.net répond 200 avec body vide. Sans ce
        // check on partait tete baissée vers enc-dec.app qui renvoyait
        // result="" -> JSONObject("") -> JSONException pénible (8 stack
        // traces dans logcat avant qu'ExoPlayer abandonne).
        if (encData.isBlank()) throw Exception("Videasy: no encrypted data (source not indexed)")

        // 2. Extract tmdbId from link to use it for decryption
        val tmdbId = link.split("tmdbId=").getOrNull(1)?.split("&")?.getOrNull(0) ?: ""

        // 3. Post to decryption API
        val json = JSONObject()
        json.put("text", encData)
        json.put("id", tmdbId)

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val decRequest = Request.Builder()
            .url("https://enc-dec.app/api/dec-videasy")
            .post(body)
            .build()

        val decResponse = client.newCall(decRequest).execute()
        val decBody = decResponse.body?.string() ?: "{}"
        val decJson = JSONObject(decBody)

        // 2026-05-04 : enc-dec.app retourne maintenant `result` en OBJET
        // (`{status:200, result:{sources:[...], subtitles:[...]}}`),
        // plus en string. L'ancien `optString("result")` -> `JSONObject(result)`
        // crashait silencieusement avec "End of input at character 0".
        // On supporte les deux formats au cas où l'API change encore.
        val resultJson = decJson.optJSONObject("result")
            ?: decJson.optString("result").takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            ?: throw Exception("Videasy: enc-dec returned no result (source unavailable)")
        val sources = resultJson.optJSONArray("sources")
        val subtitles = mutableListOf<Video.Subtitle>()
        
        val tracks = resultJson.optJSONArray("subtitles")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val track = tracks.getJSONObject(i)
                val label = track.optString("lang", "Unknown")
                val url = track.optString("url")
                if (url.isNotEmpty()) {
                    subtitles.add(Video.Subtitle(
                        label = label,
                        file = url
                    ))
                }
            }
        }

        if (sources != null && sources.length() > 0) {
            val source = sources.getJSONObject(0)
            val srcUrl = source.optString("url")

            // 2026-05-04 : Reyna/Cypher (MP4) sont morts upstream, on a viré
            // les ServerConfig. Tous les endpoints restants (Phoenix/Neon/Yoru/
            // Chamber FR…) servent du HLS. On laisse une détection .mp4
            // dans l'URL au cas où l'API renverrait du MP4 inattendu.
            val mimeType = if (srcUrl.contains(".mp4")) MimeTypes.VIDEO_MP4
                            else MimeTypes.APPLICATION_M3U8

            return Video(
                source = srcUrl,
                type = mimeType,
                subtitles = subtitles,
                headers = mapOf("Referer" to "https://player.videasy.net/")
            )
        }

        throw Exception("No video source found")
    }
}
