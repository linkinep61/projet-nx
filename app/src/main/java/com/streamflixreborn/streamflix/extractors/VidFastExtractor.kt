package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.WebViewStreamResolver

/**
 * 2026-08-28 (relevé sur 1jour1film0826.online, lecteur maison « vp4 ») — VIDFAST.
 *
 * Agrégateur indexé TMDB : `https://vidfast.pro/movie/{tmdbId}` et
 * `https://vidfast.pro/tv/{tmdbId}/{saison}/{episode}`. Aucun matching de titre,
 * la même URL vaut pour n'importe quel film du catalogue.
 *
 * ⚠️ POURQUOI UNE WEBVIEW ET PAS UN APPEL HTTP DIRECT :
 *   la page est une SPA Next.js dont la résolution passe par un POST vers un chemin
 *   OBFUSQUÉ, reconstruit à l'exécution depuis une table de chaînes chiffrée :
 *     POST https://vidfast.vc/bb0e0433…/dc80b31b/z/{cle}/{charge-utile-chiffrée}
 *   Rien dans la page ne permet de reconstituer ce chemin sans exécuter leur JS.
 *   La réponse donne un jeton qui produit le manifeste final :
 *     https://moon.peakstorm.top/vd/{jeton}/master.m3u8   (segments sur finalkite.top)
 *   Mesuré : ce manifeste répond 200 SANS Referer ni Origin — une fois capté, il se
 *   lit tel quel. On laisse donc la WebView exécuter leur JS et on intercepte le .m3u8.
 *
 * ⚠️ PAS DE CACHE. Le jeton du manifeste est signé et daté (même famille de piège que
 *   Rpmvid, Vidzy, LuluVdo, DoodStream) : un résultat mémorisé devient un 403/404.
 */
/**
 * ⚠️ 2026-08-28 — DELIBEREMENT **PAS ENREGISTRE** dans la liste `Extractor.extractors`,
 *   et AUCUN constructeur `server()` : rien dans l'app ne doit fabriquer un serveur VidFast.
 *
 *   NE PAS LE RAJOUTER. Mesure faite ce jour-la, en direct :
 *     - le master.m3u8 n'expose QU'UNE piste audio, muxee dans chaque variante
 *       (aucun `EXT-X-MEDIA:TYPE=AUDIO`) : aucune selection de langue n'est possible ;
 *     - `TAG:language=eng` sur DEUX films francais d'origine (Intouchables 77338,
 *       Le Comte de Monte-Cristo 800158), confirme a l'oreille par le user : c'est bien
 *       de l'anglais. Leur bibliotheque est une bibliotheque a piste anglaise.
 *
 *   Et l'argument « un site FR qui le reference sert du FR » ne tient pas ici : leur URL
 *   ne contient AUCUN id de fichier, seulement l'id TMDB. Le lien servi par le lecteur
 *   « vp4 » de 1jour1film est `https://vidfast.pro/movie/687163?autoPlay=false` — la meme
 *   adresse que celle qu'on composerait soi-meme. D'ou leur propre etiquette « VO(STFR) ».
 *
 *   Enregistrer cette classe la ferait aussi apparaitre dans « Gerer les sources »
 *   (via `Extractor.allExtractorNames()`) — user : « ca va polluer la liste des serveurs
 *   pour rien ». Elle reste en place, inerte, uniquement pour le jour ou une source FR
 *   nous tendrait un lien vidfast : il suffira alors de la reenregistrer.
 */
class VidFastExtractor : Extractor() {

    override val name = "VidFast"

    // 2026-08-28 : vidfast.pro redirige (301) vers vidfast.vc — migration de TLD
    //   constatée en direct dans le navigateur. On garde les deux : 1jour1film sert
    //   encore des liens en .pro.
    override val mainUrl = "https://vidfast.vc"
    override val aliasUrls = listOf("https://vidfast.pro")

    override val cacheTtlMs: Long = 0L

    companion object {
        private const val TAG = "VidFastExtractor"
    }


    override suspend fun extract(link: String): Video {
        Log.i(TAG, "résolution WebView de $link")

        val resolved = WebViewStreamResolver.resolve(
            entryUrl = link,
            referer = "$mainUrl/",
            timeoutMs = 20_000L,
            clickPlay = true,
            attach = true,
        ) ?: throw Exception("VidFast : aucun flux capté")

        Log.i(TAG, "flux capté : ${resolved.url}")

        // 2026-08-28 — SOUS-TITRES FRANCAIS.
        //   Sans eux cette source ne serait que de la VO brute, donc inutile au regard de la
        //   regle de l'app (« VF ou VOSTFR, rien d'autre »). Mesure faite : le master.m3u8 de
        //   VidFast n'expose QU'UNE piste audio, muxee dans chaque variante (aucun
        //   EXT-X-MEDIA:TYPE=AUDIO) — il n'y a donc aucun doublage francais a esperer.
        //   En revanche leur proxy Wyzie sert 34 langues de sous-titres, francais compris :
        //     /wyzie?id={tmdb}                          (film)
        //     /wyzie?id={tmdb}&season={s}&episode={e}   (episode)
        //   C'est ce qui fait passer cette source de « VO » a un vrai VOSTFR.
        val sousTitres = runCatching { sousTitresFrancais(link) }.getOrDefault(emptyList())

        return Video(
            source = resolved.url,
            headers = resolved.headers.ifEmpty { null },
            type = MimeTypes.APPLICATION_M3U8,
            subtitles = sousTitres,
        )
    }
    /** Rend la piste FR de Wyzie pour le lien `/movie/{tmdb}` ou `/tv/{tmdb}/{s}/{e}` donne. */
    private suspend fun sousTitresFrancais(link: String): List<Video.Subtitle> {
        val morceaux = link.substringAfter("://").substringAfter("/").substringBefore("?").split("/")
        val requete = when {
            morceaux.size >= 4 && morceaux[0] == "tv" ->
                "$mainUrl/wyzie?id=${morceaux[1]}&season=${morceaux[2]}&episode=${morceaux[3]}"
            morceaux.size >= 2 -> "$mainUrl/wyzie?id=${morceaux[1]}"
            else -> return emptyList()
        }

        val service = Extractor.createGsonService<Service>(mainUrl)
        return service.getSubtitles(requete)
            .filter { it.language.equals("fr", ignoreCase = true) }
            .map { Video.Subtitle(file = it.url, label = it.display ?: "Francais") }
    }

    private interface Service {
        @retrofit2.http.GET
        suspend fun getSubtitles(@retrofit2.http.Url url: String): List<WyzieSubtitle>
    }

    data class WyzieSubtitle(
        val display: String?,
        val language: String,
        val url: String,
    )
}