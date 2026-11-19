package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-11-23 (user : « voir si on peut les intégrer comme backup, c'est du TMDB ») - geil.to,
 * module « Movies & Series » (même système MediaHubMX que Vavoo).
 *   Film  : GET {BASE}/source/video/tmdb:{tmdb}.json?region=FR&language=fr
 *   Série : GET {BASE}/source/series/tmdb:{tmdb}:{saison}:{épisode}.json?region=FR&language=fr
 *   Réponse : {"sources":[{"url":"https://voe.sx/...","name":"Server C1","languages":["fr"]}, ...]}
 * Les liens sont ceux d'hébergeurs que l'appli lit déjà (voe, dood, supervideo, vidoza...) : on ne
 * garde QUE ceux marqués « fr » et la lecture passe par nos extracteurs habituels.
 * Mesuré le 23/11 sur 15 films : 14 ont au moins un lien VF (1 ou 2, surtout voe et dood) ;
 * Game of Thrones S1E1 : 4 liens VF sur 23. Le reste est surtout en allemand.
 */
object Geil {
    private const val TAG = "Geil"
    const val BASE = "https://www.geil.to/vod"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val client: OkHttpClient by lazy {
        com.streamflixreborn.streamflix.extractors.Extractor.sharedClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** Liens VF du film / de l'épisode (vide si aucun → pas de serveur affiché). */
    suspend fun serveursFrancais(tmdb: String, estUnFilm: Boolean, saison: Int, episode: Int): List<Video.Server> =
        withContext(Dispatchers.IO) {
            val chemin = if (estUnFilm) "video/tmdb:$tmdb"
                else "series/tmdb:$tmdb:${saison.coerceAtLeast(1)}:$episode"
            val url = "$BASE/source/$chemin.json?region=FR&language=fr"
            val corps = runCatching {
                client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else { Log.i(TAG, "$chemin : HTTP ${r.code}"); null }
                }
            }.onFailure { Log.i(TAG, "$chemin : ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
                ?: return@withContext emptyList()
            val liste = runCatching { JSONObject(corps).optJSONArray("sources") }.getOrNull()
                ?: return@withContext emptyList()
            val toutes = (0 until liste.length()).mapNotNull { liste.optJSONObject(it) }
            val vf = toutes.filter { s ->
                val langues = s.optJSONArray("languages")
                val fr = langues != null && (0 until langues.length()).any { langues.optString(it).equals("fr", true) }
                fr && s.optString("url").startsWith("http")
            }
            Log.d(TAG, "$chemin : ${toutes.size} source(s), dont ${vf.size} en VF")
            vf.mapIndexed { i, s ->
                val lien = s.optString("url")
                val hote = lien.substringAfter("://").substringBefore('/').removePrefix("www.").substringBefore('.')
                Video.Server(
                    id = "geil_${chemin.replace(':', '_').replace('/', '_')}_$i",
                    name = "${hote.ifBlank { "Serveur ${i + 1}" }} [VF]",
                    src = lien,
                )
            }
        }
}
