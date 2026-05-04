package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Resolves the `embed.maz.quest/tv/api/{tmdbId}/{ssPad2}/{epPad2}` JSON endpoint
 * used by allostreaming.one + waaatch.art (same backend) for FR TV episodes.
 *
 * URL format expected:
 *   https://embed.maz.quest/tv/api/{tmdbId}/{ssPad2}/{epPad2}
 *
 * API response (verified 2026-05-04):
 *   { "error": false, "links": ["https://yadi.sk/i/HASH", "..."] }
 *
 * Pipeline:
 *   1. GET tv/api/...                   → list of upstream links
 *   2. For each link, route to a known sub-extractor:
 *        - yadi.sk / disk.yandex.* → YandexDiskExtractor → MP4 direct
 *        - others (videa.hu, sibnet.ru, photos.google, .mp4) → not yet supported
 *   3. Return the first one that resolves successfully
 *
 * Why this is gold: the catalog is heavy on French dubs of older series
 * (NY911, Friends, etc.) that Movix's upstream APIs no longer cover. Yandex Disk
 * hosts these because it's a generous free hosting service that doesn't get
 * DMCA'd much.
 *
 * Auth/CORS bypass: their JS picks `/v/tvip.php` (authorized) vs `/go/tvip.php`
 * (unauthorized) based on `document.referrer`. With NO Referer header
 * (server-to-server call), `checkReferrer()` sets `isFromAuthorizedDomain = true`
 * (the `s = !e` branch in their code), so we hit the same code path as a real
 * allostreaming visitor. No need to spoof the Referer.
 */
class MazQuestExtractor : Extractor() {

    override val name = "Maz Quest"
    override val mainUrl = "https://embed.maz.quest"
    // 2026-05-04 : si embed.maz.quest meurt et que rediscover() trouve
    // embed.X.X, l'URL stockée dans Video.Server.src sera réécrite par
    // extract(). Cette regex garantit que les URLs reécrites routent toujours
    // vers nous via le mécanisme rotatingDomain de Extractor.kt.
    override val rotatingDomain = listOf(
        Regex("""^https?://embed\.[^/]+/tv/api/""")
    )

    private val yandexExtractor = YandexDiskExtractor()

    companion object {
        // 2026-05-04 : URL backend qui peut changer (les opérateurs déplacent
        // régulièrement embed.maz.quest -> embed.X.X pour échapper aux DMCA).
        // On démarre sur la valeur connue et on rebascule auto si elle meurt.
        private const val DEFAULT_BACKEND = "https://embed.maz.quest"

        // Sites frontend qui hébergent maintv.js avec la VRAIE URL backend
        // courante. Si embed.maz.quest meurt, on parse leur JS pour récupérer
        // la nouvelle URL.
        private val FRONTEND_DISCOVERY_URLS = listOf(
            "https://allostreaming.one/maintv.js",
            "https://waaatch.art/maintv.js",
        )

        // Regex pour extraire `https://embed.X.X` depuis la ligne :
        //   VIDEO_URLS:{authorized:"https://embed.maz.quest/v/tvip.php?id="}
        private val BACKEND_REGEX = Regex("""authorized:"(https?://[^"]+?)/v/tvip\.php""")

        @Volatile
        private var cachedBackend: String = DEFAULT_BACKEND

        @Volatile
        private var lastDiscoveryAttemptMs: Long = 0L
        private const val DISCOVERY_COOLDOWN_MS = 10L * 60L * 1000L // 10 min

        /** L'URL backend à utiliser MAINTENANT. Lazy, jamais bloquant. */
        fun currentBackend(): String = cachedBackend

        /** Reconstruit l'URL tv/api/... avec le backend courant.
         *  Utilisé par MovixProvider/TmdbProvider pour fabriquer le `src` du Server. */
        fun tvApiUrl(tmdbId: String, season: Int, episode: Int): String {
            val ssPad = "%02d".format(season)
            val epPad = "%02d".format(episode)
            return "${currentBackend()}/tv/api/$tmdbId/$ssPad/$epPad"
        }

        /** Pré-validation utilisée par MovixProvider/TmdbProvider au moment du
         *  getServers() pour ne pas afficher "Yandex VF" sur les épisodes qui
         *  n'ont pas de source. Gère elle-même la rediscovery si le backend
         *  primaire échoue. Retourne `true` si l'API renvoie au moins 1 link. */
        suspend fun checkAvailable(tmdbId: String, season: Int, episode: Int): Boolean {
            val client = com.streamflixreborn.streamflix.utils.NetworkClient.default

            suspend fun probe(backendBase: String): Boolean? {
                val ssPad = "%02d".format(season)
                val epPad = "%02d".format(episode)
                val url = "$backendBase/tv/api/$tmdbId/$ssPad/$epPad"
                return try {
                    val resp = client.newCall(
                        okhttp3.Request.Builder().url(url)
                            .header("Accept", "application/json").build()
                    ).execute()
                    if (resp.code == 404) return null  // signal "backend dead, try rediscover"
                    if (!resp.isSuccessful) return false
                    val body = resp.body?.string() ?: return false
                    val json = org.json.JSONObject(body)
                    val ok = !json.optBoolean("error", true)
                        && (json.optJSONArray("links")?.length() ?: 0) > 0
                    ok
                } catch (e: Exception) {
                    null  // network error -> try rediscover
                }
            }

            // 1ère tentative sur le backend en cache
            probe(currentBackend())?.let { return it }

            // Échec → rediscover puis retry
            val moved = rediscover()
            if (!moved) return false
            return probe(currentBackend()) ?: false
        }

        /** Tente de redécouvrir le backend en parsant maintv.js des frontends
         *  connus. Cooldown 10 min pour éviter spam si tous les frontends sont
         *  down. Retourne true si une nouvelle URL a été détectée. */
        suspend fun rediscover(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastDiscoveryAttemptMs < DISCOVERY_COOLDOWN_MS) {
                return false
            }
            lastDiscoveryAttemptMs = now

            val client = com.streamflixreborn.streamflix.utils.NetworkClient.default
            for (frontUrl in FRONTEND_DISCOVERY_URLS) {
                try {
                    val req = okhttp3.Request.Builder().url(frontUrl).build()
                    val resp = client.newCall(req).execute()
                    val body = resp.body?.string() ?: continue
                    val match = BACKEND_REGEX.find(body) ?: continue
                    val newBackend = match.groupValues[1]
                    if (newBackend.isNotBlank() && newBackend != cachedBackend) {
                        android.util.Log.i("MazQuest", "Backend rediscovery: $cachedBackend -> $newBackend (via $frontUrl)")
                        cachedBackend = newBackend
                        return true
                    }
                    // Même URL, pas de changement nécessaire
                    return false
                } catch (e: Exception) {
                    android.util.Log.w("MazQuest", "Discovery via $frontUrl failed: ${e.message}")
                }
            }
            return false
        }
    }

    override suspend fun extract(link: String): Video {
        // Tentative #1 sur le link d'origine. Si on prend un 404 / network err
        // (signe que le backend a déménagé), on déclenche la rediscovery et on
        // retape avec le nouveau host.
        return try {
            extractOnce(link)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            // Heuristique : 404, "Not Found", "empty body" -> backend probablement mort
            val looksLikeDeadBackend = msg.contains("404") ||
                msg.contains("empty body", ignoreCase = true) ||
                msg.contains("Not Found", ignoreCase = true) ||
                msg.contains("UnknownHost", ignoreCase = true) ||
                e is java.net.SocketTimeoutException
            if (!looksLikeDeadBackend) throw e

            android.util.Log.w("MazQuest", "Primary failed (${msg.take(80)}), attempting backend rediscovery")
            val rediscovered = rediscover()
            if (!rediscovered) throw e

            // Reconstruit le link avec le nouveau backend (remplace l'host)
            val newBackend = currentBackend()
            val rewrittenLink = rewriteWithBackend(link, newBackend)
            android.util.Log.i("MazQuest", "Retrying with $rewrittenLink")
            extractOnce(rewrittenLink)
        }
    }

    private suspend fun extractOnce(link: String): Video {
        val client = NetworkClient.default

        // 1. Hit the JSON API
        val req = Request.Builder()
            .url(link)
            .header("Accept", "application/json")
            // We deliberately do NOT set a Referer: empty referer triggers their
            // "authorized" branch in checkReferrer() (the `s = !e` case).
            .build()

        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw Exception("MazQuest: HTTP ${resp.code} for $link")
        }
        val body = resp.body?.string()
            ?: throw Exception("MazQuest: empty body from $link")

        val json = JSONObject(body)
        if (json.optBoolean("error", true)) {
            throw Exception("MazQuest: API returned error=true for $link")
        }
        val linksArray = json.optJSONArray("links")
            ?: throw Exception("MazQuest: no `links` array")
        if (linksArray.length() == 0) {
            throw Exception("MazQuest: empty links array (no source for this episode)")
        }

        // 2. Try each upstream link. First-wins, fall through on per-link error.
        var lastError: Exception? = null
        for (i in 0 until linksArray.length()) {
            val upstream = linksArray.optString(i).takeIf { it.isNotBlank() } ?: continue
            try {
                return resolveUpstream(upstream)
            } catch (e: Exception) {
                lastError = e
                // Continue to next link
            }
        }
        throw lastError ?: Exception("MazQuest: no resolvable upstream link")
    }

    /** Replace the scheme://host of `link` with `newBackend`. Path/query préservés. */
    private fun rewriteWithBackend(link: String, newBackend: String): String {
        val hostEnd = link.indexOf("/", startIndex = link.indexOf("://") + 3)
        return if (hostEnd > 0) newBackend + link.substring(hostEnd) else newBackend
    }

    /** Route an upstream URL to the right resolver. Currently Yandex Disk only;
     *  videa.hu / sibnet.ru / photos.google / direct .mp4 to be added if we
     *  observe them in the wild. */
    private suspend fun resolveUpstream(upstream: String): Video {
        return when {
            upstream.contains("yadi.sk") || upstream.contains("disk.yandex") ->
                yandexExtractor.extract(upstream)
            // Direct MP4 (would just play but might need a Referer)
            upstream.endsWith(".mp4") || upstream.contains(".mp4?") ->
                Video(source = upstream, type = androidx.media3.common.MimeTypes.VIDEO_MP4)
            else -> throw Exception("MazQuest: unsupported upstream host: $upstream")
        }
    }

    // Like Dailymotion, the inner Yandex token expires fast. Don't cache the
    // tv/api response either since the linked yadi.sk file might be removed
    // upstream. 60s is a good middle ground.
    override val cacheTtlMs: Long = 60_000L
}
