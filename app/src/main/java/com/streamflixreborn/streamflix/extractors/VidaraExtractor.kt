package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 2026-07-17 — Vidara (viewdara.com / vidara.to / vidara.so).
 *
 * Nouveau hoster apparu côté FrenchStream / Flemmix(Wiflix) — signalé
 * « No extractors found » dans les logs. viewdara.com est une FAÇADE : le
 * backend réel est Vidara (cf. https://viewdara.com/api qui documente
 * api.vidara.so). Tous ces domaines servent la même plateforme.
 *
 * EMBED : https://<host>/e/<filecode>   (filecode ~13 chars)
 *
 * EXTRACTION 100 % NATIVE (aucune WebView) — validé en direct dans Chrome :
 *   POST https://<host>/api/stream
 *        Content-Type: application/json
 *        {"filecode":"<code>","device":"android"}
 *   → 200 JSON EN CLAIR :
 *     {
 *       "filecode": "...", "title": "...", "thumbnail": "...",
 *       "streaming_url": "<master m3u8 signé ~247 chars>",
 *       "subtitles": {...}, "default_sub_lang": "...", "vast_ads": ""
 *     }
 *
 * ⚠️ PIÈGE ÉVITÉ : la page embed charge crypto-js + pako et contient un blob
 *   base64 de ~2000 chars manipulé via atob/charCodeAt. C'est un LEURRE : la
 *   source vient simplement de /api/stream en clair. Ne PAS réimplémenter le
 *   déchiffrement JS.
 *
 * `device` est dérivé du User-Agent par le player (ios/android/web) ; on envoie
 * "android" puisque c'est notre cas.
 */
class VidaraExtractor : Extractor() {

    override val name = "Vidara"
    override val mainUrl = "https://vidara.to"

    // Le matching d'extracteur est un startsWith sur l'URL sans protocole :
    // il faut donc lister chaque host exact.
    override val aliasUrls = listOf(
        "https://viewdara.com",
        "https://vidara.so",
        "https://vidara.cc",
        "https://vidara.net",
    )

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        // Origine réelle du lien (on reste sur le domaine fourni : les fronts
        // sont interchangeables mais le filecode est servi par celui-là).
        val origin = Regex("^(https?://[^/]+)").find(link)?.groupValues?.get(1)
            ?: mainUrl

        // /e/<filecode>  (le player fait exactement pathname.split('/').pop())
        val filecode = link.substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
        if (filecode.isBlank()) {
            throw Exception("Vidara: filecode introuvable dans $link")
        }

        val payload = JSONObject()
            .put("filecode", filecode)
            .put("device", "android")
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$origin/api/stream")
            .post(payload)
            .header("User-Agent", userAgent)
            .header("Referer", "$origin/")
            .header("Origin", origin)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val body = sharedClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Vidara: /api/stream HTTP ${resp.code}")
            }
            resp.body?.string().orEmpty()
        }
        if (body.isBlank()) throw Exception("Vidara: réponse /api/stream vide")

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw Exception("Vidara: réponse /api/stream non-JSON")

        // Erreur explicite renvoyée par l'API (fichier supprimé, en cours…).
        // ⚠️ "source unavailable" est reconnu par classifyError() (Extractor.kt)
        //   → catégorie "dead-content" : c'est la SOURCE qui est morte, pas
        //   l'extracteur. Sans ça, quelques fichiers HS suffisent à faire
        //   blacklister Vidara en entier ("marked broken, N failures").
        json.optString("error").takeIf { it.isNotBlank() }?.let {
            throw Exception("Vidara: source unavailable — $it")
        }

        val streamUrl = json.optString("streaming_url")
            .takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: throw Exception("Vidara: source unavailable — streaming_url absent (filecode=$filecode)")

        Log.d("VidaraExtractor", "resolved $filecode → m3u8 (${streamUrl.length} chars)")

        Video(
            source = streamUrl,
            type = MimeTypes.APPLICATION_M3U8,
            headers = mapOf(
                "Referer" to "$origin/",
                "Origin" to origin,
                "User-Agent" to userAgent,
            ),
        )
    }
}
