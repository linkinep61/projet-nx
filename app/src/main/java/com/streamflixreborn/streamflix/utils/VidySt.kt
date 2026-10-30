package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 2026-10-30 (user : « vidy.st — on fait déjà celui-là ») — trouvé via Piradex/hydrahd.
 * Relevé dans le navigateur :
 *   · page de lecture par identifiant TMDB : www.vidy.st/movie/{tmdb} · www.vidy.st/tv/{tmdb}/{s}/{e}
 *   · 17 serveurs nommés par ville (Miami par défaut … Paris …). Sur « Verity » (2026), le serveur
 *     PARIS a les pistes audio Anglais + FRANÇAIS + Occitan (les autres : anglais seul, beaucoup
 *     de sous-titres dont le français) ;
 *   · les sources viennent de `api.wecollege.net/<ville>/sources?…` avec un jeton chiffré
 *     (`/seed` + décodage maison) → on ne refait pas l'API : page cachée qui lit le film.
 * La page démarre toujours sur Miami et ne permet pas de choisir le serveur par l'adresse : on
 * réécrit donc dans la page cachée chaque appel `/<ville>/sources` vers `/paris/sources`, puis on
 * capte la playlist. Le français est choisi par le lecteur quand la piste existe (cf. fileName).
 */
object VidySt {
    private const val TAG = "VidySt"
    const val BASE = "https://www.vidy.st"
    const val PREFIXE = "vidy::"
    private val VILLE_SOURCES = Regex("""(wecollege\.net/)([a-z]+)(/sources)""")

    fun lien(tmdb: String, estUnFilm: Boolean, saison: Int, episode: Int): String =
        if (estUnFilm) "$BASE/movie/$tmdb" else "$BASE/tv/$tmdb/${saison.coerceAtLeast(1)}/$episode"

    /** Un serveur par titre (le site couvre presque tout le catalogue TMDB). */
    fun serveurs(tmdb: String, estUnFilm: Boolean, saison: Int, episode: Int): List<Video.Server> =
        listOf(Video.Server(
            id = "$PREFIXE$tmdb:${if (estUnFilm) "f" else "s$saison:e$episode"}",
            name = "[MULTI]",   // affiché « Vidy · [MULTI] » (le registre préfixe la source)
            src = lien(tmdb, estUnFilm, saison, episode),
        ))

    suspend fun video(server: Video.Server): Video {
        var reecrites = 0
        val r = WebViewStreamResolver.resolve(
            entryUrl = server.src, referer = "$BASE/", timeoutMs = 25_000L, attach = true, clickPlay = true,
            reecrireRequete = { u ->
                val m = VILLE_SOURCES.find(u)
                if (m != null && m.groupValues[2] != "paris") { reecrites++; u.replaceFirst(VILLE_SOURCES, "$1paris$3") } else null
            },
        ) ?: throw Exception("Vidy : aucun flux capté (${server.src})")
        // Pistes audio : le master HLS déclare-t-il du français ?
        val master = withContext(Dispatchers.IO) {
            runCatching {
                NetworkClient.default.newCall(Request.Builder().url(r.url).apply {
                    r.headers.forEach { (k, v) -> if (!k.equals("Accept-Encoding", true)) header(k, v) }
                }.build()).execute().use { rep -> if (rep.isSuccessful) rep.body?.string() else null }
            }.getOrNull()
        }
        val audios = master?.lines()?.filter { it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=AUDIO") }
            ?.mapNotNull { Regex("""LANGUAGE="([^"]+)"""").find(it)?.groupValues?.get(1) }.orEmpty()
        // 2026-10-30 (user, sur Clayface, piste unique : « ce nouveau serveur a carrément du
        //   français ») : le serveur Paris sert la VF ; une piste UNIQUE non étiquetée = VF. On ne
        //   passe en VO que si le master déclare ses langues SANS le français.
        val francais = audios.isEmpty() || audios.any { it.lowercase() in setOf("fr", "fre", "fra", "fr-fr") }
        Log.i(TAG, "flux capté (${r.url.substringBefore('?').takeLast(40)}), appels réécrits vers Paris=$reecrites, " +
            "audio=${audios.ifEmpty { listOf("1 piste non étiquetée") }} → ${if (francais) "VF" else "VO"}")
        return Video(
            source = r.url,
            type = if (r.url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null,
            headers = r.headers + mapOf("Referer" to "$BASE/", "Origin" to BASE),
            // Le lecteur choisit l'audio (français d'abord) et coupe les sous-titres si VF ;
            //   sinon VO → sous-titres français (OpenSubtitles / traduction) comme d'habitude.
            fileName = if (francais) "Vidy.MULTI" else "Vidy.VOSTFR",
        )
    }
}
