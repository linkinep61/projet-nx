package com.streamflixreborn.streamflix.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-09-17 (user « on m'a trouvé une source de meilleure qualité pour la
 * radio, audiophile.fm ; à intégrer un peu à part, un petit dossier au-dessus
 * de Canal B, présent aussi sur Android Auto ») : client pour le catalogue
 * audiophile.fm.
 *
 * audiophile.fm = radios LOSSLESS (FLAC HiFi/CD), ~79 stations curées. Le site
 * est propulsé par Sanity CMS (projet orhkaa59, dataset production) dont l'API
 * de requête GROQ est publique et sans auth. On récupère donc le catalogue en
 * direct, exactement comme RadioBrowser :
 *   - flux PRINCIPAL   = streams.primary.url   (FLAC)
 *   - flux de SECOURS  = streams.secondary.url (MPEG/MP3) → fallbackUrls, joué
 *     automatiquement par RadioPlaybackService si le FLAC coupe (4G/réseau faible).
 *
 * Ces stations sont gardées À PART du gros catalogue (~2000 RadioBrowser) : elles
 * ne sont PAS fusionnées dans la liste plate, mais exposées comme un dossier
 * dédié (cf. RadioCatalog.audiophileStations()).
 */
object AudiophileFmClient {

    private const val TAG = "AudiophileFm"

    // API GROQ Sanity (publique). Projet + dataset relevés sur audiophile.fm.
    private const val SANITY_BASE =
        "https://orhkaa59.api.sanity.io/v2021-10-21/data/query/production"
    // Ne récupère que les stations actives, projetées sur le strict nécessaire,
    //   triées par titre. `->url` résout l'asset image en URL directe.
    private const val GROQ =
        "*[_type==\"station\" && active==true]{title,\"slug\":slug.current," +
            "\"primary\":streams.primary,\"secondary\":streams.secondary," +
            "\"logo\":logo.asset->url}|order(title asc)"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class AudiophileStation(
        val slug: String,
        val title: String,
        val flacUrl: String,
        val mp3Url: String?,
        val logo: String?,
    )

    @Volatile private var cache: List<AudiophileStation> = emptyList()
    @Volatile private var lastLoad = 0L
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L  // 6 h (catalogue quasi statique)

    /** Récupère le catalogue audiophile.fm (FLAC). Cache 6 h. Safe : renvoie le
     *  dernier cache connu (puis emptyList) en cas d'échec réseau. */
    suspend fun fetch(): List<AudiophileStation> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - lastLoad < CACHE_TTL_MS) return@withContext cache
        try {
            val url = SANITY_BASE + "?query=" + java.net.URLEncoder.encode(GROQ, "UTF-8")
            val req = Request.Builder().url(url)
                .header("User-Agent", "Streamflix/1.0").build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP ${resp.code}")
                    return@use null
                }
                resp.body?.string()
            } ?: return@withContext cache
            val arr = JSONObject(body).optJSONArray("result") ?: return@withContext cache
            val out = ArrayList<AudiophileStation>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").trim()
                val slug = o.optString("slug").trim()
                if (title.isBlank() || slug.isBlank()) continue
                val primary = o.optJSONObject("primary")
                val flac = primary?.optString("url")?.trim().orEmpty()
                if (flac.isBlank()) continue  // sans flux principal, station inutile
                val mp3 = o.optJSONObject("secondary")?.optString("url")?.trim()
                    ?.takeIf { it.isNotBlank() }
                val logo = o.optString("logo").takeIf { it.isNotBlank() }
                out.add(AudiophileStation(slug = slug, title = title, flacUrl = flac,
                    mp3Url = mp3, logo = logo))
            }
            if (out.isNotEmpty()) {
                cache = out
                lastLoad = now
                Log.d(TAG, "fetched ${out.size} audiophile.fm stations (FLAC)")
            }
            if (out.isNotEmpty()) out else cache
        } catch (t: Throwable) {
            Log.w(TAG, "fetch failed: ${t.message}")
            cache
        }
    }
}
