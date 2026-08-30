package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UserPreferences
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Url

class VidnestExtractor : Extractor() {

    override val name = "Vidnest"
    override val mainUrl = "https://vidnest.io"

    // 2026-08-28 (relevé sur 1jour1film0826.online) : `vidnest.fun` sert le MÊME service
    //   mais sous une forme totalement différente — agrégateur indexé TMDB
    //   (`/movie/{tmdbId}`), SPA Next.js, flux HLS signé et daté servi par un CDN tournant
    //   (`cdn100.streamraiwind.stream/img/…/…m3u8?token=…&expires=…`). La page N'EST PAS un
    //   jwplayer : le chemin historique ci-dessous ne s'y applique pas, d'où la branche
    //   WebView dans extract(). Sans cet alias, aucun extracteur n'était sélectionné pour un
    //   lien vidnest.fun → serveur mort.
    override val aliasUrls = listOf("https://vidnest.fun")

    // Le manifeste porte `token=` + `expires=` : le mettre en cache transforme un succès
    //   en 403 dès l'expiration (même piège que Rpmvid, Vidzy, LuluVdo, DoodStream).
    override val cacheTtlMs: Long = 0L

    fun extractSubtitles(text: String): List<Video.Subtitle> {
        val tracksBlock = Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: return emptyList()

        val objectRegex = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)

        return objectRegex.findAll(tracksBlock).mapNotNull { match ->
            val obj = match.groupValues[1]

            val kind = Regex("""kind\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
            if (kind != "captions") return@mapNotNull null

            val rawFile = Regex("""file\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
            val label = Regex("""label\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
            val default = Regex(""""default"\s*:\s*(true|false)""")
                .find(obj)?.groupValues?.get(1)?.toBoolean() ?: false

            if (rawFile == null || label == null) return@mapNotNull null

            val file = Regex("""https://[^\s"']+""")
                .find(rawFile)?.value ?: return@mapNotNull null

            Video.Subtitle(
                file = file,
                label = label,
                initialDefault = default,
                default = if (UserPreferences.serverAutoSubtitlesDisabled) false else default
            )
        }.toList()
    }


    override suspend fun extract(link: String): Video {
        // 2026-08-28 : vidnest.fun (agrégateur TMDB) résout son flux en JS, dans une SPA.
        //   Rien à parser en HTTP direct → on laisse la WebView exécuter la page et on
        //   intercepte le .m3u8. vidnest.io (lecteur jwplayer classique) garde le chemin
        //   historique juste en dessous.
        if (link.contains("vidnest.fun", ignoreCase = true)) {
            val resolved = com.streamflixreborn.streamflix.utils.WebViewStreamResolver.resolve(
                entryUrl = link,
                referer = "https://vidnest.fun/",
                timeoutMs = 20_000L,
                clickPlay = true,
                attach = true,
            ) ?: throw Exception("Vidnest (fun) : aucun flux capté")

            return Video(
                source = resolved.url,
                headers = resolved.headers.ifEmpty { null },
                type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            )
        }
        val service = Extractor.createJsoupService<Service>(mainUrl)
        val doc = service.get(link)

        val scriptTags = doc.select("script[type=text/javascript]")

        var m3u8: String? = null

        var subtitles: List<Video.Subtitle> = emptyList()

        for (script in scriptTags) {
            val scriptData = script.data()
            if ("jwplayer" in scriptData && "sources" in scriptData && "file" in scriptData) {
                val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
                val match = fileRegex.find(scriptData)
                if (match != null) {
                    m3u8 = match.groupValues[1]
                    subtitles = extractSubtitles(scriptData)
                    break
                }
            }
        }

        if (m3u8 == null) {
            throw Exception("Stream URL not found in script tags")
        }

        return Video(
            source = m3u8,
            subtitles = subtitles,
            useServerSubtitleSetting = true
        )
    }

    private interface Service {
        @GET
        suspend fun get(@Url url: String): Document
    }
}
