package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 2026-08-02 — playmate.to (« Playmate »), servi par le provider J1F / 1Jour1Film.
 *
 * Ce serveur n'échouait pas : il n'existait tout simplement pas chez nous. Les logs le disaient
 * explicitement — `No extractors found for URL: https://playmate.to/embed/<code>` — donc aucune
 * tentative n'était même faite, et le serveur était écarté avant d'être essayé.
 *
 * ── Fonctionnement, relevé en direct dans le navigateur ────────────────────────────────────
 * La page d'embed est un lecteur **jwplayer** qui appelle une API interne :
 *
 *     POST https://playmate.to/api/s
 *     Content-Type: application/json          ← IMPÉRATIF : en `form-urlencoded` → 400
 *     {"filecode":"<code>"}
 *
 * Réponse (JSON en clair, aucun chiffrement) :
 *     { "ax":"", "cx":"<code>", "ix":"<vignette>", "kx":null,
 *       "lx":"French",           ← langue de la piste
 *       "sx":"https://<cdn>/hls/…/master.txt",   ← LE FLUX
 *       "tx":"" }
 *
 * `sx` est exactement l'URL que joue le lecteur du site, et elle répond 200 **sans cookie ni
 * session** (vérifié). L'extraction est donc purement HTTP : pas de WebView, pas de clic simulé,
 * pas de déchiffrement — deux requêtes et c'est lu nativement par ExoPlayer.
 *
 * ⚠ Le manifeste s'appelle `master.txt` et non `.m3u8` : c'est bien du HLS (contenu `#EXTM3U`),
 * simplement renommé pour déjouer les filtres. On force donc le type HLS explicitement, sans quoi
 * ExoPlayer se fierait à l'extension et choisirait le mauvais lecteur.
 */
class PlaymateExtractor : Extractor() {
    override val name = "Playmate"
    override val mainUrl = "https://playmate.to"

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        // …/embed/<code>, …/e/<code> ou …/<code>
        val code = Regex("""/(?:embed|e|d|v|f)/([A-Za-z0-9_-]+)""").find(link)?.groupValues?.get(1)
            ?: link.trimEnd('/').substringAfterLast('/').substringBefore('.')
        if (code.isBlank()) throw Exception("Playmate : code de fichier introuvable dans l'URL")

        val hote = runCatching { android.net.Uri.parse(link).host }.getOrNull() ?: "playmate.to"

        val corps = JSONObject().put("filecode", code).toString()
            .toRequestBody("application/json".toMediaType())
        // ⚠ 2026-08-02 : l'API renvoie 403 {"error":"forbidden"} si la requête ne RESSEMBLE pas à
        //   celle du lecteur. Vérifié dans un navigateur : ni cookie ni session ne sont en cause
        //   (200 avec `credentials:'omit'`, et la page ne pose aucun cookie) — ce sont les en-têtes
        //   que Chrome ajoute d'office qui font la différence, `Sec-Fetch-Site: same-origin` en
        //   tête, contrôle classique d'anti-hotlink (l'accès direct à /api/s est d'ailleurs refusé).
        val req = Request.Builder()
            .url("https://$hote/api/s")
            .post(corps)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Referer", link)
            .header("Origin", "https://$hote")
            .header("Accept", "*/*")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .header("sec-ch-ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"131\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .build()

        var codeHttp = 0
        val json = NetworkClient.default.newCall(req).execute().use { rep ->
            codeHttp = rep.code
            rep.body?.string().orEmpty()
        }

        var flux = runCatching { JSONObject(json).optString("sx") }.getOrNull()
            ?.takeIf { it.startsWith("http") }
        var langue = runCatching { JSONObject(json).optString("lx") }.getOrNull()

        // Repli : si l'API refuse (403 anti-hotlink, ou format modifié), le flux reste présent
        //   dans la page d'embed — c'est de là que leur propre jwplayer le tire. Plus lent
        //   qu'un appel d'API, mais toujours sans WebView, et ça évite de perdre le serveur
        //   pour un simple durcissement de leur contrôle d'en-têtes.
        if (flux == null) {
            Log.w(TAG, "/api/s a répondu $codeHttp ${json.take(60)} → repli sur la page d'embed")
            val page = NetworkClient.default.newCall(
                Request.Builder().url(link)
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Referer", "https://$hote/")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                    .build()
            ).execute().use { it.body?.string().orEmpty() }

            flux = Regex("""https?://[^"'\s\\<>]+/hls/[^"'\s\\<>]+""").find(page)?.value
                ?: Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""").find(page)?.value
        }

        if (flux == null) {
            throw Exception(
                "Playmate : flux introuvable (api=$codeHttp ${json.take(60)}) — vidéo supprimée ou API modifiée"
            )
        }

        Log.d(TAG, "extraction native OK (code=$code, langue=${langue ?: "?"}, api=$codeHttp)")

        // Le titre de la page d'embed EST le nom de fichier (« …MULTi.TRUEFRENCH… ») : il fait
        //   autorité sur la langue, contrairement à l'étiquette du provider. On le récupère au
        //   passage — la requête est légère et évite un serveur annoncé VF qui joue du VOSTFR.
        val nomFichier = runCatching {
            val page = NetworkClient.default.newCall(
                Request.Builder().url(link)
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Referer", "https://$hote/")
                    .build()
            ).execute().use { it.body?.string().orEmpty() }
            Regex("<title>([^<]{3,200})</title>", RegexOption.IGNORE_CASE)
                .find(page)?.groupValues?.get(1)?.trim()
        }.getOrNull()

        Video(
            source = flux,
            // `master.txt` : extension trompeuse, contenu HLS → on l'annonce explicitement.
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            fileName = nomFichier,
            headers = mapOf(
                "User-Agent" to DEFAULT_USER_AGENT,
                "Referer" to "https://$hote/",
                "Origin" to "https://$hote",
            ),
        )
    }

    companion object {
        private const val TAG = "PlaymateExtractor"
    }
}
