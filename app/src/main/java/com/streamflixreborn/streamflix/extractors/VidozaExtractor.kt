package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url

class VidozaExtractor : Extractor() {

    override val name = "Vidoza"

    override val mainUrl = "https://vidoza.net"
    override val aliasUrls = listOf<String>("https://videzz.net")

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<VidozaService>(mainUrl)
        val source = service.getSource(link.replace(mainUrl, ""))

        // 2026-05-09 : Vidoza retourne "File was deleted" (16 bytes) pour les
        // vidéos supprimées. Sans détection, on retournait Video(source="")
        // qui force le PlayerViewModel à throw "No source found" 1-2s plus tard
        // mais sans logger l'échec côté FailureTracker → Vidoza restait "sain"
        // dans le ranker même quand 3 URLs d'affilée étaient mortes.
        // On détecte le marker explicite + le body trop court pour throw tôt
        // et permettre au tracker de dégrader Vidoza dans le sort des servers.
        val bodyText = source.text().trim()
        if (bodyText == "File was deleted" || (bodyText.length < 50 && bodyText.contains("deleted", ignoreCase = true))) {
            throw Exception("Vidoza: video deleted (CDN tombstone)")
        }

        val videoUrl = source.select("source").attr("src")
        if (videoUrl.isBlank()) {
            // Cas où la page existe mais ne contient pas de tag <source>.
            // Peut arriver si Vidoza change son template ou si la page est
            // un anti-bot challenge. Throw pour que ce soit détecté comme
            // un échec d'extraction (et pas une vidéo "vide" qui ferait
            // bugger ExoPlayer en silence).
            throw Exception("Vidoza: source URL not found in page (template changed?)")
        }
        return Video(
            source = videoUrl,
            subtitles = listOf()
        )
    }


    private interface VidozaService {
        @GET
        suspend fun getSource(@Url url: String): Document
    }
}