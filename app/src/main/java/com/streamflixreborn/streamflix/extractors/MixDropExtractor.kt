package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

class MixDropExtractor : Extractor() {

    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.co"
    override val aliasUrls = listOf(
        "https://mixdrop.bz",
        "https://mixdrop.ag",
        "https://mixdrop.ch",
        "https://mixdrop.to",
        "https://mixdrop.cv",
        "https://mxdrop.to",
        "https://mixdrop.club",
        "https://m1xdrop.net",
        // 2026-05-03: nouveaux miroirs vérifiés UP (HTTP 200)
        "https://mixdrop.is",
        "https://mixdrop.gl",
        "https://mixdrop.sx",
        // 2026-05-14 (dessinanime.cc utilise ce miroir spécifique)
        "https://mxdrop.sx",
        // 2026-09-15 : miroir servi par vidbasic.top (hub de Dramacool). Vérifié au
        //   curl le jour même : 200 + page `MDCore` classique, donc même recette.
        "https://mixdrop.ps",
        // 2026-06-13 (porté upstream v1.7.220-v1.7.221) : miroirs avec typo
        //   (i en plus) — protègent si MixDrop change pour ces variantes.
        "https://miixdrop.net",
        "https://miiixdrop.net",
        // 2026-10-26 (vérifié depuis le PC du user) : mxdrop.to et mixdrop.si redirigent
        //   vers mxdrop.top ; mxdrop.click répond avec le lecteur (MDCore).
        //   mixdrop.vip répond 404 → pas repris.
        "https://mxdrop.top",
        "https://mxdrop.click",
        "https://mixdrop.si"
    )
    override val rotatingDomain = listOf(
        Regex("^md[3bfyz][a-z0-9]*\\.[a-z0-9]+", RegexOption.IGNORE_CASE)
    )

    override suspend fun extract(link: String): Video {
        val service = Extractor.createJsoupService<Service>(mainUrl)

        val document = service.get(
            url = link
                .replace("/f/", "/e/")
                // 2026-10-26 : variante de lien « /emb/ » = même lecteur que « /e/ ».
                .replace("/emb/", "/e/")
                .replace(".club/", ".ag/")
                .replace(Regex("^(https?://[^/]+/e/[^/?#]+).*$", RegexOption.IGNORE_CASE), "$1")
        )

        val html = document.toString()

        val packedJS = Regex("(eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?)</script>")
            .find(html)
            ?.groupValues?.get(1)

        val script = packedJS?.let { JsUnpacker(it).unpack() } ?: html

        val srcRegex = Regex("""wurl.*?=.*?"(.*?)";""")
        val sourceUrl = srcRegex.find(script)?.groupValues?.get(1)
            ?: run {
                // 2026-11-02 (banc Dune 2, mxdrop.click vérifié depuis le PC) : « WE ARE SORRY -
                //   We can't find the video you are looking for » → lien MORT, pas un raté d'analyse.
                if (Regex("""(?i)can't find the video|we are sorry|file not found|has been removed""").containsMatchIn(html)) {
                    throw Exception("MixDrop: video not found - file removed (we can't find the video)")
                }
                throw Exception("Source not found")
            }

        val finalUrl = when {
            sourceUrl.startsWith("//") -> "https:$sourceUrl"
            sourceUrl.startsWith("http") -> sourceUrl
            else -> "https://$sourceUrl"
        }

        // 2026-11-02 : le jeton (s=…&e=…) du CDN mxcontent est lié au User-Agent qui a chargé la
        //   page embed. Or sharedClient écrase l'UA des @Headers (Chrome 124) par
        //   Extractor.DEFAULT_USER_AGENT (Chrome 131) → le lecteur doit renvoyer CE même UA,
        //   sinon 403 systématique (vérifié depuis le PC : jeton 131 + UA 124 = 403, + UA 131 = 206).
        return Video(
            source = finalUrl,
            headers = mapOf(
                "User-Agent" to Extractor.DEFAULT_USER_AGENT
            ),
            extraBuffering = true
        )
    }

    private interface Service {
        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "X-Requested-With: XMLHttpRequest"
        )
        suspend fun get(
            @Url url: String
        ): Document
    }
}


