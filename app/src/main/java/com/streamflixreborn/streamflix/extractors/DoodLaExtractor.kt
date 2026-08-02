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

    // 2026-08-01 (user : « j'essaye un serveur Dood et ça fonctionne pas ») : NE JAMAIS
    //   METTRE EN CACHE le résultat de cet extracteur.
    //   Diagnostic : l'extraction RÉUSSIT (log `Cache HIT for playmogo.com/e/…`) mais la
    //   lecture échoue en ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED — ExoPlayer ne reçoit
    //   pas une vidéo mais une page d'erreur du CDN.
    //   Cause : l'URL produite est JETABLE. Elle porte `expiry=System.currentTimeMillis()`
    //   (l'instant de l'extraction) et un `pass_md5` signé + horodaté. Resservie depuis le
    //   cache quelques minutes plus tard, elle est déjà périmée → le CDN refuse.
    //   Même piège que LuluVdo (corrigé pareil). Chaque lecture doit refaire le pass_md5.
    override val cacheTtlMs: Long = 0L
    override val aliasUrls = listOf(
        "https://dsvplay.com",
        "https://myvidplay.com",
        "https://playmogo.com",
        "https://do7go.com",
        "https://d000d.com",
        "https://doply.net",
        "https://doodstream.me",
        // 2026-07-27 (audit domaines morts, vérifié dans Chrome) : doodstream.com est le
        //   domaine VIVANT actuel (doply.net y redirige) — il manquait dans la reconnaissance,
        //   donc un lien doodstream.com n'était pas routé (« No extractors found »).
        "https://doodstream.com",
        // 2026-07-23 (audit serveurs Movix) : ds2video.com = domaine Doodstream vu chez Movix
        //   mais non routé (« No extractors found »). Même moteur pass_md5.
        "https://ds2video.com",
        // 2026-08-07 (audit DNS) : `dood.work` retiré (NXDOMAIN). Ces quatre-là répondent ;
        //   `doods.pro` et `dood.li` sont ceux servis aujourd'hui par FrenchAnime et CoflixWiki.
        "https://dood.li",
        "https://doods.pro",
        "https://dood.yt",
        "https://dood.re",
        // 2026-06-02 : kokoflix.lol RETIRÉ — c'est un proxy multi-host de FS
        //   qui route selon le suffix /<XXX>_go.php : osaka_go.php = VOE (pas
        //   Dood !), grandline_go.php = autre host.
        //   2026-07-13 : géré par KakaflixExtractor (proxy générique) qui suit
        //   le redirect puis délègue à l'extracteur du domaine résolu.
    )

    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    override suspend fun extract(rawLink: String): Video {
        // 2026-07-27 : dood.work / d000d.org sont MORTS (NXDOMAIN, vérifié dans Chrome). Les IDs
        //   DoodStream sont PORTABLES entre domaines → on réécrit ces domaines morts vers le
        //   domaine VIVANT (doodstream.com) AVANT de fetch, sinon dns-fail garanti (issue #93).
        val link = rawLink
            .replace("dood.work", "doodstream.com")
            .replace("d000d.org", "doodstream.com")
        android.util.Log.d("DoodExtractor", "extract() input link=$link (raw=$rawLink)")
        val linkBaseUrl = getBaseUrl(link)
        val retrofit = Retrofit.Builder()
            .baseUrl(linkBaseUrl)
            .client(Extractor.sharedClient)
            .addConverterFactory(JsoupConverterFactory.create())
            .addConverterFactory(StringConverterFactory.create())
            .build()
        val service = retrofit.create(Service::class.java)

        val embedUrl = link.replace("/d/", "/e/")
        android.util.Log.d("DoodExtractor", "GET embedUrl=$embedUrl referer=$link")
        val response = service.get(embedUrl, link)
        val document = response.body()
            ?: throw Exception("Failed to load embed page (HTTP ${response.code()})")

        // Get the final URL after redirects to use the correct domain for pass_md5
        val finalUrl = response.raw().request.url.toString()
        val finalBaseUrl = getBaseUrl(finalUrl)
        android.util.Log.d("DoodExtractor", "finalUrl after redirects=$finalUrl baseUrl=$finalBaseUrl")

        val md5Path = Regex("""/pass_md5/[^"'\s]+""").find(document.toString())?.value
            ?: run {
                val htmlSnippet = document.toString().take(500)
                android.util.Log.w("DoodExtractor",
                    "No pass_md5 in embed page from $finalUrl. First 500 chars: $htmlSnippet")
                throw Exception("Could not find md5 path on $finalUrl")
            }
        android.util.Log.d("DoodExtractor", "md5Path=$md5Path")

        val md5Url = finalBaseUrl + md5Path

        val videoPrefix = service.getString(md5Url, finalUrl).trim()
        android.util.Log.d("DoodExtractor", "videoPrefix len=${videoPrefix.length} starts=${videoPrefix.take(60)}")

        val token = md5Url.substringAfterLast("/")
        val sep = if ('?' in videoPrefix) '&' else '?'
        val url = videoPrefix +
                createHashTable() +
                "${sep}token=${token}&expiry=${System.currentTimeMillis()}"
        android.util.Log.d("DoodExtractor", "final video URL=${url.take(120)}")

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
