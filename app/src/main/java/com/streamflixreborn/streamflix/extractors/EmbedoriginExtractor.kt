package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * 2026-10-26 (serveur rouge « Embedorigin », relevé sur l'Oppo : « HOTE NON COUVERT:
 * embedorigin.cc ») — lecteur « Stream Origin » (site Next.js + jwplayer).
 *
 * Vérifié dans Chrome sur `embedorigin.cc/e/<code>` : le lecteur appelle
 *   GET <front>/app/v1/play/<code>?embed=1   → JSON
 *     { hls: "https://s1.<cdn>/file/<expire>/<signature>/<uuid>/master.m3u8",
 *       mp4, subtitles: [...], title, duration, expires_at, ads: [...], … }
 * L'adresse du flux est EN CLAIR dans `hls` (signée, avec date d'expiration) : aucun
 * déchiffrement. Les segments sont servis depuis lh3.googleusercontent.com, déguisés en
 * images — c'est le lecteur HLS de l'app (SauteImageDataSource) qui s'en charge.
 * `ads` = pré-rolls publicitaires du site : ignorés.
 */
class EmbedoriginExtractor : Extractor() {

    override val name = "Embedorigin"
    override val mainUrl = "https://embedorigin.cc"

    // URL signée avec expiration : ne pas la resservir depuis le cache.
    override val cacheTtlMs: Long = 0L

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val front = Regex("^(https?://[^/]+)").find(link)?.groupValues?.get(1) ?: mainUrl
        val code = link.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (code.isBlank()) throw Exception("Embedorigin: code introuvable dans $link")

        val requete = Request.Builder()
            .url("$front/app/v1/play/$code?embed=1")
            .header("User-Agent", userAgent)
            .header("Referer", "$front/e/$code")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .build()
        val (http, corps) = sharedClient.newCall(requete).execute().use { r ->
            r.code to (r.body?.string().orEmpty())
        }
        // 404/410 = fichier supprimé → « source unavailable » (classé dead-content : la santé
        //   de l'extracteur n'est pas pénalisée, cf. Extractor.classifyError).
        if (http == 404 || http == 410) throw Exception("Embedorigin: source unavailable — HTTP $http")
        if (http !in 200..299) throw Exception("Embedorigin: /app/v1/play HTTP $http")
        val json = runCatching { JSONObject(corps) }.getOrNull()
            ?: throw Exception("Embedorigin: réponse non-JSON")
        json.optString("error").takeIf { it.isNotBlank() }?.let {
            throw Exception("Embedorigin: source unavailable — $it")
        }

        val hls = json.optString("hls").takeIf { it.startsWith("http") }
        val mp4 = json.optString("mp4").takeIf { it.startsWith("http") }
        val source = hls ?: mp4 ?: throw Exception("Embedorigin: source unavailable — ni hls ni mp4")

        // Format des sous-titres non encore observé (liste vide sur les fichiers relevés) :
        //   lecture tolérante des clés usuelles.
        val sousTitres = json.optJSONArray("subtitles")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = listOf("file", "url", "src", "path").map { o.optString(it) }
                    .firstOrNull { it.startsWith("http") } ?: return@mapNotNull null
                val label = listOf("label", "lang", "language", "name").map { o.optString(it) }
                    .firstOrNull { it.isNotBlank() } ?: "Sous-titres"
                Video.Subtitle(label = label, file = url)
            }
        }.orEmpty()

        Log.d("EmbedoriginExtractor", "$code → ${if (hls != null) "hls" else "mp4"}, ${sousTitres.size} sous-titre(s)")
        Video(
            source = source,
            subtitles = sousTitres,
            fileName = json.optString("title").takeIf { it.isNotBlank() },
            type = if (hls != null) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$front/",
                "Origin" to front,
            ),
        )
    }
}
