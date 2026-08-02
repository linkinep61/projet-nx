package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * FireStream — `firestream.to/e/<slug>`
 *
 * 2026-08-07 : ajouté après relevé du user sur Wiflix/flemmix. Le « Lecteur 1 » de
 *   certains épisodes (ex. Silo S03E06 VF) pointe sur firestream.to, hébergeur qu'on ne
 *   savait pas lire du tout — le serveur arrivait dans le picker sans extracteur capable
 *   de le résoudre.
 *
 * RECETTE, vérifiée EN DIRECT dans le navigateur du user (pas déduite) :
 *
 *   1. `GET https://firestream.to/e/<slug>` → la page embed contient un élément
 *      `id="token-blob"` dont le texte est un jeton opaque (~88 caractères).
 *   2. `POST https://firestream.to/api/videos/<slug>/resolve`
 *      corps JSON `{"blob":"<jeton>"}`
 *      → `{"signedVideoUrl":"…", "signedVideoSdUrl":"…"}`
 *   3. `signedVideoUrl` est un m3u8 signé (`?md5=…&expires=…`) sur
 *      `us-cdn-<n>.firestream.to/encodings/<uuid>/…/video.m3u8`.
 *      Testé : HTTP 200, corps `#EXTM3U …` — lisible tel quel.
 *
 * ⚠ Le player web exécute AUSSI une preuve de travail — `SHA-256(nonce + compteur)` avec
 *   16 bits de tête à zéro, servie par `/api/videos/<slug>/challenge`. Elle N'EST PAS
 *   nécessaire ici : `/resolve` accepte le seul `blob` de la page (vérifié, 200 + m3u8
 *   valide). C'est un garde-fou anti-adblock côté page, pas une condition de l'API. On ne
 *   la calcule donc pas — inutile de brûler ~65 000 hachages sur la Chromecast.
 *   Si un jour `/resolve` répond 4xx en réclamant le challenge, la porte d'entrée est là.
 *
 * `signedVideoSdUrl` était null sur l'échantillon (source 720p unique) : on le garde en
 *   secours si le HD manque.
 */
class FireStreamExtractor : Extractor() {

    override val name = "FireStream"
    override val mainUrl = "https://firestream.to"

    /** Timeouts courts : 2 requêtes légères, un hébergeur sain répond bien en dessous. */
    private val fastClient: OkHttpClient by lazy {
        sharedClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** `https://firestream.to/e/zBmK9Tus` → `zBmK9Tus`. */
    private fun slugDe(link: String): String =
        link.substringAfterLast("/e/").substringBefore('?').substringBefore('#').trim()

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val slug = slugDe(link).takeIf { it.isNotBlank() }
            ?: throw Exception("FireStream: slug introuvable dans $link")

        // ── 1. Page embed ────────────────────────────────────────────────────────────
        val html = try {
            fastClient.newCall(
                Request.Builder()
                    .url(link)
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Referer", "$mainUrl/")
                    .build()
            ).execute().use { r ->
                if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            Log.d(TAG, "page embed injoignable: ${e.message}")
            throw Exception("FireStream: page embed injoignable (${e.message})")
        }

        // ── 2. Jeton `token-blob` ────────────────────────────────────────────────────
        //   L'élément est un simple conteneur texte ; on reste sur une regex plutôt que
        //   Jsoup pour ne pas dépendre du nom de la balise (span/div selon les pages).
        val blob = Regex("""id=["']token-blob["'][^>]*>([\s\S]*?)<""")
            .find(html)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("FireStream: token-blob absent de la page embed")

        // ── 3. Résolution ────────────────────────────────────────────────────────────
        val corps = JSONObject().put("blob", blob).toString()
            .toRequestBody("application/json".toMediaType())

        val reponse = try {
            fastClient.newCall(
                Request.Builder()
                    .url("$mainUrl/api/videos/$slug/resolve")
                    .post(corps)
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Referer", link)
                    .header("Origin", mainUrl)
                    .build()
            ).execute().use { r ->
                if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolve KO: ${e.message}")
            throw Exception("FireStream: /resolve a échoué (${e.message})")
        }

        val json = try { JSONObject(reponse) } catch (e: Exception) {
            throw Exception("FireStream: réponse /resolve illisible : ${reponse.take(120)}")
        }

        val url = json.optString("signedVideoUrl").takeIf { it.isNotBlank() }
            ?: json.optString("signedVideoSdUrl").takeIf { it.isNotBlank() }
            ?: throw Exception("FireStream: aucune URL signée dans la réponse")

        Log.d(TAG, "OK slug=$slug → ${url.substringBefore('?').takeLast(60)}")

        Video(
            source = url,
            // Toujours du HLS sur cet hébergeur (chemin `…/video.mp4/video.m3u8`).
            type = if (url.contains(".m3u8")) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to DEFAULT_USER_AGENT,
            ),
        )
    }

    private companion object {
        const val TAG = "FireStreamExtractor"
    }
}
