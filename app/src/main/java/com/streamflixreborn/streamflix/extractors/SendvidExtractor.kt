package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url

// Extractor pour sendvid.com / embed page.
//
// Format : la page contient un <source src="https://videos2.sendvid.com/X/Y/Z.mp4?validfrom=...">
// directement dans le HTML. URL signée avec hash + ip + validity window (~4h).
// 2026-05-12 : ajouté pour supporter AnimeSiteProvider (2 lecteurs sur 5 utilisent sendvid).
class SendvidExtractor : Extractor() {
    override val name = "SendVid"
    override val mainUrl = "https://sendvid.com"

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<Service>(mainUrl, mainUrl)
        val document = service.getEmbed(url = link)

        // Le tag <source src="..."> contient l'URL MP4 directement
        val sourceUrl = document.selectFirst("source[src]")?.attr("src")
            ?: document.selectFirst("video source")?.attr("src")
            // Fallback : regex sur tout le HTML au cas où le DOM est différent
            ?: run {
                val html = document.html()
                Regex("""<source[^>]+src="([^"]+\.mp4[^"]*)"""").find(html)?.groupValues?.get(1)
                    ?: Regex("""(https?://[^"'\s]+sendvid\.com/[^"'\s]+\.mp4[^"'\s]*)""").find(html)?.value
            }
            ?: throw Exception("SendVid: aucune source MP4 trouvée dans la page embed")

        return Video(
            source = sourceUrl,
            type = "video/mp4",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to DEFAULT_USER_AGENT,
            ),
        )
    }

    private interface Service {
        @GET
        suspend fun getEmbed(@Url url: String): Document
    }
}
