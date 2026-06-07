package com.streamflixreborn.streamflix.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 2026-06-03 (user "les gif ne s'affichent pas tous https://tenor.com/bVkh5.gif") :
 *
 * Les liens partagés depuis Tenor / Giphy / Imgur sont des URLs de PAGES HTML,
 * pas les fichiers image eux-mêmes. Glide tente de charger le HTML comme image
 * → échec, avatar vide.
 *
 * Ce helper résout l'URL réelle du média en fetch la page HTML et en extrayant
 * la balise `<meta property="og:image" content="...">` (ouvert graph standard).
 *
 * Patterns reconnus :
 *   tenor.com/bVkh5.gif       → media.tenor.com/.../tenor.gif
 *   tenor.com/view/...        → media.tenor.com/.../tenor.gif
 *   giphy.com/gifs/xxx        → media.giphy.com/.../giphy.gif
 *   imgur.com/abc             → i.imgur.com/abc.{png,jpg,gif}
 *
 * Si l'URL est déjà directe (i.imgur.com, media.tenor.com, media.giphy.com,
 * ou tout autre host se terminant par .png/.jpg/.gif/.webp), pas de fetch.
 */
object AvatarUrlResolver {

    private const val TAG = "AvatarUrlResolver"
    private const val TIMEOUT_MS = 5000L
    private const val MAX_HTML_BYTES = 200_000  // 200KB max (les pages Tenor font ~50KB)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Hosts considérés comme "déjà directs" — pas de fetch HTML. */
    private val DIRECT_HOSTS = setOf(
        "media.tenor.com", "c.tenor.com",
        "media.giphy.com", "media0.giphy.com", "media1.giphy.com",
        "media2.giphy.com", "media3.giphy.com", "media4.giphy.com",
        "i.imgur.com",
    )

    /** Extensions image valides — pas besoin de fetch si l'URL se termine par. */
    private val IMAGE_EXTS = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif")

    /**
     * Résolution async. Appelle [onResolved] avec l'URL directe sur le main thread
     * (ou l'URL d'origine si pas de résolution nécessaire / échec).
     */
    fun resolveAsync(url: String, onResolved: (resolved: String) -> Unit) {
        // 2026-06-05 : si l'URL est directe (media.tenor.com/...AAAAd|AAAAC|AAAAi/...)
        //   on applique le patch suffixe deprecie avant de retourner. Couvre les
        //   URLs collees direct par l'user, sans og:image necessaire.
        val patched = patchTenorDeadSuffixes(url)
        if (!needsResolution(patched)) {
            onResolved(patched)
            return
        }
        GlobalScope.launch(Dispatchers.IO) {
            val resolved = try {
                resolveBlocking(patched) ?: patched
            } catch (e: Exception) {
                Log.w(TAG, "resolveAsync failed for $patched: ${e.message}")
                patched
            }
            withContext(Dispatchers.Main) {
                onResolved(patchTenorDeadSuffixes(resolved))
            }
        }
    }

    /** Patch URLs Tenor dont le suffixe est deprecie (renvoie 404 sur les
     *  GIFs recents). On force AAAAd|AAAAC vers AAAAM (Medium GIF, leger ~400KB
     *  et stable). AAAAi laisse tel quel : sur les GIFs qui le servent encore
     *  il preserve la transparence (ex Deadpool). */
    private fun patchTenorDeadSuffixes(url: String): String {
        if (!url.contains("tenor.com")) return url
        val deadPattern = Regex("""https?://media\d*\.tenor\.com/(?:m/)?([A-Za-z0-9_-]+?)(AAAAd|AAAAC)(/[^?#]+)""")
        deadPattern.find(url)?.let { m ->
            val baseId = m.groupValues[1]
            val suffix = m.groupValues[3]
            val patched = "https://media.tenor.com/${baseId}AAAAM${suffix}"
            Log.d(TAG, "Tenor patch dead suffix: $url -> $patched")
            return patched
        }
        return url
    }

    /**
     * True si l'URL nécessite résolution HTML (= host non-direct ET pas d'extension image).
     */
    private fun needsResolution(url: String): Boolean {
        return try {
            val u = java.net.URI(url)
            val host = u.host?.lowercase() ?: return false
            if (DIRECT_HOSTS.contains(host)) return false
            val path = u.path?.lowercase() ?: ""
            if (IMAGE_EXTS.any { path.endsWith(it) }) {
                // Cas spécial : tenor.com/bVkh5.gif a une extension mais c'est du HTML !
                // Si le host est tenor.com/giphy.com/imgur.com → toujours fetch.
                if (host == "tenor.com" || host == "www.tenor.com" ||
                    host == "giphy.com" || host == "www.giphy.com" ||
                    host == "imgur.com" || host == "www.imgur.com") {
                    return true
                }
                return false
            }
            // Hosts connus à résoudre
            host == "tenor.com" || host == "www.tenor.com" ||
                host == "giphy.com" || host == "www.giphy.com" ||
                host == "imgur.com" || host == "www.imgur.com"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fetch HTML + extract og:image. Retourne null si échec ou pas trouvé.
     * Public pour usage depuis ProfileEmojiArt.cacheLocally (re-résolution
     * d'une URL Tenor stockée AVANT le fix du resolveAsync).
     * ATTENTION : blocking, à appeler depuis un context background uniquement.
     */
    fun resolveBlocking(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} for $url")
                return null
            }
            val body = resp.body ?: return null
            // Limite à MAX_HTML_BYTES pour ne pas DDoS la mémoire.
            val html = body.source().let { src ->
                src.request(MAX_HTML_BYTES.toLong())
                src.buffer.snapshot(minOf(MAX_HTML_BYTES, src.buffer.size.toInt())).utf8()
            }
            return extractOgImage(html)
                ?.let { upgradeTenorToFullQuality(it) }
                ?.also { Log.d(TAG, "Resolved $url → $it") }
        }
    }

    /**
     * 2026-06-03 (user "la 2e met un fond blanc pourquoi") :
     *   Tenor og:image pointe sur le format "AAAAC" = preview compressée pour
     *   social media (Facebook/Twitter), souvent avec fond opaque ajouté.
     *   Le format "AAAAi" sur media.tenor.com (pas media1) est l'original HD
     *   avec transparence préservée.
     *
     *   URL preview : https://media1.tenor.com/m/XXX_AAAAC/name.gif
     *   URL native  : https://media.tenor.com/XXX_AAAAi/name.gif
     *
     *   Transformation : remplace "media1.tenor.com/m/" → "media.tenor.com/" et
     *   suffixe "AAAAC" → "AAAAi". Si l'URL ne match pas le pattern, retourne tel quel.
     */
    private fun upgradeTenorToFullQuality(imageUrl: String): String {
        // 2026-06-05 : AAAAi (Full HD) est 404 sur la majorite des GIFs Tenor
        //   recents. On delegue la transformation au patchTenorDeadSuffixes qui
        //   force vers AAAAM (Medium GIF, leger et stable, marche partout).
        return patchTenorDeadSuffixes(imageUrl)
    }

    /**
     * Extract <meta property="og:image" content="URL"> du HTML.
     * Regex tolérante : ordre attribut variable, simple/double quotes.
     */
    private fun extractOgImage(html: String): String? {
        // Pattern 1: <meta property="og:image" content="URL">
        val r1 = Regex(
            """<meta\s+[^>]*property\s*=\s*["']og:image["'][^>]*content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        r1.find(html)?.groupValues?.get(1)?.let { return decodeEntities(it) }
        // Pattern 2: content avant property (ordre inverse)
        val r2 = Regex(
            """<meta\s+[^>]*content\s*=\s*["']([^"']+)["'][^>]*property\s*=\s*["']og:image["']""",
            RegexOption.IGNORE_CASE,
        )
        r2.find(html)?.groupValues?.get(1)?.let { return decodeEntities(it) }
        // Pattern 3: twitter:image fallback (Tenor met souvent les deux)
        val r3 = Regex(
            """<meta\s+[^>]*name\s*=\s*["']twitter:image["'][^>]*content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        r3.find(html)?.groupValues?.get(1)?.let { return decodeEntities(it) }
        return null
    }

    private fun decodeEntities(s: String): String =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
}
