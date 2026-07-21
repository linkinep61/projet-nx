package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * 2026-07-17 — AnonMP4 (anonmp4.help).
 *
 * Nouveau hoster apparu côté Flemmix (ex-Wiflix) / FrenchStream — signalé
 * « No extractors found » dans les logs.
 *
 * EMBED : https://anonmp4.help/embed/<code>   (code ~15 chars)
 * Player : ArtPlayer + hls.js  (`hls_url: json.hls` dans le JS de la page)
 *
 * EXTRACTION 100 % NATIVE (aucune WebView) — validé en direct dans Chrome :
 *   1. GET la page embed.
 *   2. La page contient EN DUR (rendu serveur, aucun calcul JS) l'URL de l'API :
 *        https://cryoapi.shadowapi.<tld>/load/<token>
 *      (vérifié : aucun atob/btoa/charCodeAt autour du fetch → rien à reverser)
 *   3. GET cette URL → JSON :
 *        succès  : { "success": true,  "hls": "<master m3u8>", ... }
 *        échec   : { "success": false, "type": "remotepending",
 *                    "error": "Video queued for processing" }
 *
 * Le champ lu est `hls` (le JS fait `hls_url: json.hls`). On tolère quelques
 * alias de clé au cas où l'API évolue.
 */
class AnonMp4Extractor : Extractor() {

    override val name = "AnonMP4"
    override val mainUrl = "https://anonmp4.help"

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** L'API de résolution est sur un domaine tiers (shadowapi), TLD variable. */
    private val apiRegex = Regex("""https?://cryoapi\.shadowapi\.[a-z0-9-]+/load/[A-Za-z0-9_\-]+""")

    private fun get(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .build()
        return sharedClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("AnonMP4: HTTP ${resp.code} sur $url")
            resp.body?.string().orEmpty()
        }
    }

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val origin = Regex("^(https?://[^/]+)").find(link)?.groupValues?.get(1)
            ?: mainUrl

        // 1) page embed
        val html = get(link, "$origin/")
        if (html.isBlank()) throw Exception("AnonMP4: page embed vide")

        // 2) URL de l'API écrite en dur dans la page
        val apiUrl = apiRegex.find(html)?.value
            ?: throw Exception("AnonMP4: URL cryoapi introuvable dans la page embed")

        // 3) résolution
        val body = get(apiUrl, "$origin/")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw Exception("AnonMP4: réponse API non-JSON")

        // ── Cas ÉCHEC : {"success":false,"type":"remotepending","error":"..."} ──
        // ⚠️ On préfixe "source unavailable" car classifyError() (Extractor.kt)
        //   mappe cette formule sur "dead-content" : la SOURCE est morte, pas
        //   l'extracteur. Sans ça, quelques fichiers non encodés suffisent à
        //   faire blacklister AnonMP4 en entier ("marked broken, N failures").
        if (json.has("success") && !json.optBoolean("success", true)) {
            val err = json.optString("error").ifBlank { json.optString("type") }
                .ifBlank { "indisponible" }
            throw Exception("AnonMP4: source unavailable — $err")
        }

        // ── Cas SUCCÈS ──
        // 2026-07-17 : la réponse réelle N'EST PAS {success, hls} (ce que laissait
        //   croire le JS `hls_url: json.hls`, visiblement une forme héritée) mais :
        //     { "status": "ok",
        //       "tracks": [ { "track_name": "French",
        //                     "track_url": "https://n0x.cipherx.life/load/multi/<token>" } ] }
        //   `track_url` sert directement le manifeste HLS — on le passe tel quel à
        //   ExoPlayer, qui le fetch avec NOS headers (Referer obligatoire : sans lui
        //   l'API répond {"success":false,"error":"Invalid request or content not found"}).
        val tracks = json.optJSONArray("tracks")
        if (tracks == null || tracks.length() == 0) {
            throw Exception("AnonMP4: source unavailable — aucune piste dans la réponse API")
        }

        val names = (0 until tracks.length())
            .mapNotNull { tracks.optJSONObject(it)?.optString("track_name") }
            .filter { it.isNotBlank() }
        Log.d("AnonMp4Extractor", "source prête (${tracks.length()} piste(s) : $names) → overlay WebView")

        // ── Lecture : OVERLAY WEBVIEW, pas d'extraction directe ──
        // 2026-07-17 — Pourquoi on ne renvoie PAS `tracks[].track_url` comme m3u8,
        //   alors qu'on l'a sous la main : il N'EST PAS servable hors du contexte
        //   de la page. Vérifié exhaustivement (ne pas refaire) :
        //     • ExoPlayer + Referer            → JSON d'erreur → MANIFEST_MALFORMED
        //     • sans Origin (comme hls.js)     → idem
        //     • rejeu navigateur même origine  → {"success":false,"error":
        //                                         "Invalid request or content not found"}
        //     • aucun cookie de session à reprendre (document.cookie vide)
        //     • token régénéré à chaque appel → échoue quand même
        //   Seule la requête émise par le player de la page obtient un 200, et ce
        //   player ne s'initialise QUE sur un vrai geste utilisateur (aucun <video>
        //   ni instance ArtPlayer avant tap). C'est le profil Abyss/Hydrax, que la
        //   mémoire projet documente comme un puits sans fond en extraction headless.
        //   → On applique la conclusion déjà payée : overlay WebView joué par le
        //     VRAI tap de l'utilisateur (même chemin que SeekPlayer / Abyss).
        //
        //   L'appel cryoapi ci-dessus reste utile comme PRÉ-CHECK : si la source
        //   n'est pas encodée, on échoue vite et proprement (dead-content →
        //   auto-switch) au lieu d'ouvrir un overlay sur une vidéo morte.
        Video(
            source = link,
            webViewUrl = link,
            needsWebViewClick = true,
        )
    }
}
