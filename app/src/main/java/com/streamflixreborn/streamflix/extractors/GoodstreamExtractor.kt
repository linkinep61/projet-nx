package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class GoodstreamExtractor : Extractor() {

    override val name = "Goodstream"
    override val mainUrl = "https://goodstream.one"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Langue annoncée : la même que les autres extracteurs du projet (public FR). */
    private val acceptLanguage = "fr-FR,fr;q=0.9,en;q=0.8"

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<Service>(mainUrl)

        // 2026-09-19 (repris de streamflix-reborn2, commit fa41285) : la page était demandée
        //   SANS le moindre en-tête. Un hébergeur qui ne voit ni langue ni référent sert
        //   volontiers une page d'erreur au lieu du player — et ici l'échec est muet, on
        //   ne trouve simplement aucun `jwplayer` dans les scripts. On se présente donc
        //   comme un navigateur : UA + langue + référent du site lui-même.
        val document = service.get(link, userAgent, acceptLanguage, "$mainUrl/")
        val scriptTags = document.select("script[type*=javascript]")

        var m3u8: String? = null

        for (script in scriptTags) {
            val scriptData = script.data()
            if ("jwplayer" in scriptData && "sources" in scriptData && "file" in scriptData) {
                val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
                val match = fileRegex.find(scriptData)
                if (match != null) {
                    m3u8 = match.groupValues[1]
                    break
                }
            }
        }

        return Video(
            source = m3u8 ?: throw Exception("Can't retrieve source"),
            // Le lecteur rejoue le flux avec CES en-têtes : le CDN de Goodstream vérifie
            //   le référent, comme la page qui le sert.
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept-Language" to acceptLanguage,
                "Referer" to "$mainUrl/",
            )
        )
    }

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("User-Agent") userAgent: String,
            @Header("Accept-Language") acceptLanguage: String,
            @Header("Referer") referer: String,
        ): Document
    }
}
