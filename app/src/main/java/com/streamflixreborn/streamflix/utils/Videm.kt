package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-10-26 (user : « je suis partant pour que tu ajoutes Videm, c'est un truc qu'on n'a pas ») —
 * Videm (videm.xyz), lecteur par identifiant TMDB repris de l'app amont (streamflix-reborn2,
 * VidemExtractor, 12-19/10/2026 : elle l'utilise pour le français à côté de Frembed).
 *   Page : videm.xyz/embed/movie/{tmdb}  ·  videm.xyz/embed/tv/{tmdb}/{saison}/{épisode}
 *   La page contient `var Q = {…}` : `t` (jeton) + `ssr.servers[]` (name, lang, ref).
 *   On ne garde QUE les serveurs `lang = French` → étiquetés [VF].
 *   Lecture : GET api.php?a=play&ref=…&t=… → JSON `url` (chemin relatif) → m3u8.
 * Le jeton `t` est relu au moment de la lecture (page rechargée), pour ne pas jouer un jeton
 * périmé si l'utilisateur lance le serveur longtemps après l'affichage de la liste.
 */
object Videm {
    private const val TAG = "Videm"
    const val BASE = "https://videm.xyz"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // 2026-10-26 : vérifié depuis le PC du user (même connexion) — la page embed répond 403 SANS
    //   Referer ou avec son PROPRE domaine (ce que faisait l'app amont → elle ne marche pas chez
    //   nous), et 200 avec un site intégrateur. Spider-Man : Un jour nouveau (969681) → serveur
    //   « SWM1-French » → api.php → master m3u8 200. Inception / Big Bang : aucun serveur French.
    private const val REFERER = "https://www.themoviedb.org/"

    private val client: OkHttpClient by lazy {
        com.streamflixreborn.streamflix.extractors.Extractor.sharedClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun lien(tmdb: String, estUnFilm: Boolean, saison: Int, episode: Int): String =
        if (estUnFilm) "$BASE/embed/movie/$tmdb" else "$BASE/embed/tv/$tmdb/${saison.coerceAtLeast(1)}/$episode"

    private fun get(url: String): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.i(TAG, "GET ${url.substringAfter(BASE)} → HTTP ${r.code}"); null }
            else r.body?.string()
        }
    }

    /** Lit `var Q = {…}` de la page embed. */
    private fun lireQ(page: String): JSONObject? {
        val brut = Regex("var Q = (\\{.*?\\});", RegexOption.DOT_MATCHES_ALL).find(page)?.groupValues?.get(1)
            ?: return null
        return runCatching { JSONObject(brut) }.getOrNull()
    }

    /** Serveurs FRANÇAIS de la page (vide si aucun → pas de serveur affiché). */
    suspend fun serveursFrancais(tmdb: String, estUnFilm: Boolean, saison: Int, episode: Int): List<Video.Server> =
        withContext(Dispatchers.IO) {
            val page = lien(tmdb, estUnFilm, saison, episode)
            val q = runCatching { get(page) }.getOrNull()?.let { lireQ(it) }
            if (q == null) { Log.i(TAG, "pas de données de lecteur pour $page"); return@withContext emptyList() }
            val liste = q.optJSONObject("ssr")?.optJSONArray("servers") ?: return@withContext emptyList()
            val toutes = (0 until liste.length()).mapNotNull { liste.optJSONObject(it) }
            Log.d(TAG, "$page : ${toutes.size} serveur(s), langues = ${toutes.map { it.optString("lang") }.distinct()}")
            toutes.filter { it.optString("lang").equals("French", ignoreCase = true) && it.optString("ref").isNotBlank() }
                .mapIndexed { i, s ->
                    val ref = s.optString("ref")
                    Video.Server(
                        id = "videm_${if (estUnFilm) "movie_$tmdb" else "tv_${tmdb}_${saison}_$episode"}_$i",
                        // « Server SWM1-French » → « SWM1 [VF] »
                        name = s.optString("name").removePrefix("Server ")
                            .replace(Regex("-?French", RegexOption.IGNORE_CASE), "").trim()
                            .ifBlank { "Serveur ${i + 1}" } + " [VF]",
                        src = "$page#ref=$ref",
                    )
                }
        }

    suspend fun video(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val page = server.src.substringBefore('#')
        val ref = server.src.substringAfter("#ref=", "").substringBefore('#')
        require(ref.isNotBlank()) { "Videm : référence de serveur absente" }
        val t = get(page)?.let { lireQ(it) }?.optString("t").orEmpty()
        require(t.isNotBlank()) { "Videm : jeton de lecture introuvable" }
        val rep = get("$BASE/api.php?a=play&ref=$ref&t=$t") ?: throw Exception("Videm : lecture refusée")
        val chemin = runCatching { JSONObject(rep).optString("url") }.getOrNull().orEmpty()
        require(chemin.isNotBlank()) { "Videm : pas d'adresse de flux dans la réponse" }
        val url = if (chemin.startsWith("http")) chemin else "$BASE$chemin"
        Log.i(TAG, "flux : ${url.substringBefore('?').takeLast(60)}")
        Video(
            source = url,
            type = MimeTypes.APPLICATION_M3U8,
            headers = mapOf("User-Agent" to UA, "Referer" to REFERER),
        )
    }
}
