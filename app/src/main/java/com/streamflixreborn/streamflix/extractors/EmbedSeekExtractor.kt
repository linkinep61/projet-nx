package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URI

/**
 * Extracteur pour `*.embedseek.com` (alias "Bll" dans les listes Movix).
 *
 *  Architecture
 *  ------------
 *  embedseek.com est une SPA Vite minimaliste qui :
 *   - URL : `https://<sub>.embedseek.com/#TOKEN` (token dans le hash, pas la query)
 *   - JS bundle fait un fetch vers `/api/v1/player?t=TOKEN`
 *   - Réponse JSON contient l'URL m3u8 (champ exact varie : `url`, `src`,
 *     `file`, `source`, `hls`, `stream`, `playUrl`)
 *
 *  Pourquoi cet extracteur existe (2026-05-09)
 *  -------------------------------------------
 *  Movix index `bll.embedseek.com` pour les **nouveautés** (films sortis dans
 *  les 7 derniers jours) où les gros providers FR (Wiflix, FStream, Cpasmal)
 *  n'ont pas encore indexé. Sans cet extracteur, l'app retourne "No
 *  extractors found" et perd 1-2 sources VF par film récent.
 *
 *  Comportement
 *  ------------
 *  1. Parse l'URL pour extraire le token (après le `#`)
 *  2. Appel `GET /api/v1/player?t=<token>` avec Referer = base URL
 *  3. Cherche le m3u8 dans plusieurs champs JSON courants (l'API d'embedseek
 *     n'est pas documentée, on essaie les patterns standards)
 *  4. Throw "dead-content" si l'API retourne `{"error": "Token is invalid"}`
 *     (token expiré, video supprimée) — pour ne pas pénaliser l'extracteur
 */
class EmbedSeekExtractor : Extractor() {

    override val name = "EmbedSeek"
    override val mainUrl = "https://bll.embedseek.com"
    override val aliasUrls = listOf(
        "https://embedseek.com",
        // Si embedseek déploie d'autres sous-domaines (ll, cc, dd…), les
        // ajouter ici. Pour l'instant on a observé "bll" uniquement.
    )

    private val USER_AGENT = Extractor.DEFAULT_USER_AGENT

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        // Le token est dans le fragment (après #) de l'URL embedseek.
        // Ex: https://bll.embedseek.com/#k9zrt → token = "k9zrt"
        val uri = try { URI(link) } catch (e: Exception) {
            throw Exception("EmbedSeek: malformed URL: $link")
        }
        val token = uri.fragment?.takeIf { it.isNotBlank() }
            ?: throw Exception("EmbedSeek: no token in URL fragment: $link")

        // Détermine la base (scheme + host) — pour appeler la bonne API
        // (bll.embedseek.com vs embedseek.com vs autre sous-domaine).
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: "bll.embedseek.com"
        val baseUrl = "$scheme://$host"
        val apiUrl = "$baseUrl/api/v1/player?t=$token"
        val referer = "$baseUrl/"

        Log.d(TAG, "Extracting token=$token from $host → $apiUrl")

        val response = try {
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "application/json")
                .build()
            Extractor.sharedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("EmbedSeek: API returned HTTP ${resp.code}")
                }
                resp.body?.string() ?: throw Exception("EmbedSeek: empty response body")
            }
        } catch (e: Exception) {
            throw Exception("EmbedSeek: API call failed: ${e.message}")
        }

        // Parse JSON. Format possible :
        //   { "error": "Token is invalid" }    ← contenu mort
        //   { "url": "https://.../master.m3u8" }
        //   { "src": "..." }
        //   { "file": "..." }
        //   { "sources": [{"file": "...", "type": "hls"}] }
        //   { "data": { "url": "..." } }
        val json = try { JSONObject(response) } catch (e: Exception) {
            throw Exception("EmbedSeek: response not JSON: ${response.take(80)}")
        }

        // Détecte les erreurs explicites de l'API
        val error = json.optString("error", "").takeIf { it.isNotBlank() }
        if (error != null) {
            // "Token is invalid" / "expired" / "not found" → dead-content
            val lower = error.lowercase()
            val isDeadContent = lower.contains("invalid")
                || lower.contains("expired")
                || lower.contains("not found")
                || lower.contains("removed")
            if (isDeadContent) {
                throw Exception("EmbedSeek: video deleted ($error)")
            }
            throw Exception("EmbedSeek: API error: $error")
        }

        // Cherche le m3u8 dans les champs courants (top-level d'abord, puis
        // dans data/result/payload imbriqués si présents).
        val m3u8 = findM3u8InJson(json)
            ?: throw Exception("EmbedSeek: m3u8 URL not found in response: ${response.take(120)}")

        Log.d(TAG, "Found m3u8: ${m3u8.take(80)}")

        // ExoPlayer mime type explicite + Referer pour ne pas se faire
        // 403-er par le CDN qui valide souvent l'origin.
        Video(
            source = m3u8,
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
                "Origin" to baseUrl,
            ),
        )
    }

    /**
     * Cherche récursivement une URL m3u8 dans un JSONObject.
     * Tente les champs : url, src, file, source, hls, stream, playUrl,
     * videoUrl, m3u8, manifestUrl, link, href.
     * Si un champ contient un JSONObject ou JSONArray, descend dedans.
     * Profondeur max 4 pour éviter une boucle infinie sur cycles JSON.
     */
    private fun findM3u8InJson(obj: Any?, depth: Int = 0): String? {
        if (depth > 4 || obj == null) return null
        when (obj) {
            is JSONObject -> {
                // Check les champs string courants
                for (key in URL_FIELD_KEYS) {
                    val value = obj.optString(key, "").takeIf { it.isNotBlank() }
                    if (value != null && value.contains(".m3u8")) return value
                    if (value != null && value.startsWith("http") && depth == 0) {
                        // top-level URL "url" sans .m3u8 explicite : peut quand même
                        // être valide (HLS sans extension). On l'essaie.
                        return value
                    }
                }
                // Descente récursive dans tous les enfants
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = obj.opt(key) ?: continue
                    val found = findM3u8InJson(child, depth + 1)
                    if (found != null) return found
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until obj.length()) {
                    val found = findM3u8InJson(obj.opt(i), depth + 1)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "EmbedSeekExtractor"

        /** Champs JSON où on cherche typiquement une URL m3u8. */
        private val URL_FIELD_KEYS = listOf(
            "url", "src", "file", "source", "hls", "stream",
            "playUrl", "videoUrl", "m3u8", "manifestUrl",
            "link", "href",
        )
    }
}
