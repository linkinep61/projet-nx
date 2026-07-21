package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Auto-discovery du miroir Coflix actif.
 *
 * 2026-07-17 — Bascule du registre coflix.blog (mort/bloqué FAI) vers
 * coflix.domains, le nouveau "registre officiel" du site.
 *
 * coflix.domains est un WordPress qui redirige (JS, ~10s) vers le domaine
 * actif du moment. La cible est embarquée EN CLAIR dans le HTML sous forme
 * `"redirect_url":"http://coflix.cloud"` (config du plugin de redirection).
 * On fetch la page en OkHttp (sans exécuter le JS), on extrait ce champ,
 * on normalise en https sans slash final, et on cache 6h.
 *
 * Quand Coflix migre (coflix.cloud → coflix.xyz demain), coflix.domains met
 * à jour son `redirect_url` → l'app suit automatiquement, sans nouvelle release.
 *
 * Fallbacks en cascade :
 *   1) `redirect_url` (signal le plus fiable)
 *   2) heuristique "domaine coflix le plus répété" dans le HTML (hors registre)
 *   3) DEFAULT_MIRROR (coflix.cloud) codé en dur = dernier filet connu-bon
 */
object CoflixMirrorDiscovery {

    private const val TAG = "CoflixMirrorDiscovery"
    private const val DISCOVERY_URL = "https://coflix.domains/"
    private const val DISCOVERY_TTL_MS = 6 * 60 * 60 * 1000L // 6h

    /** Dernier domaine actif connu-bon (au 17/07/2026). Sert de défaut si la
     *  découverte échoue (registre injoignable, HTML sans redirect_url…). */
    const val DEFAULT_MIRROR = "https://coflix.cloud"

    /** Registres à exclure de l'heuristique de repli (ce ne sont PAS des
     *  miroirs de contenu). */
    private val EXCLUDED = setOf(
        "https://coflix.domains", // registre lui-même
        "https://coflix.blog",    // ancien registre
        "https://coflix.plus",    // host des assets
    )

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile private var cachedMirror: String? = null
    @Volatile private var cachedAtMs: Long = 0L

    private val httpClient by lazy { Extractor.sharedClient }

    /** Normalise un domaine en `https://host` (force https, retire path/slash). */
    private fun normalize(raw: String): String? {
        val m = Regex("""^https?://([^/"'\s]+)""").find(raw.trim()) ?: return null
        val host = m.groupValues[1].lowercase()
        return "https://$host"
    }

    /**
     * Retourne le miroir Coflix actif. Ne renvoie JAMAIS null : au pire,
     * DEFAULT_MIRROR. Cache 6h.
     */
    suspend fun discover(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val hit = cachedMirror
        if (hit != null && (now - cachedAtMs) < DISCOVERY_TTL_MS) return@withContext hit

        val found = try {
            val req = Request.Builder()
                .url(DISCOVERY_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .build()
            val html = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "coflix.domains HTTP ${resp.code}")
                    null
                } else resp.body?.string()
            }
            if (html == null) null
            else {
                // 1) signal fiable : "redirect_url":"http://coflix.cloud"
                val redirect = Regex(""""redirect_url"\s*:\s*"(https?:\\?/\\?/[^"]+)"""")
                    .find(html)?.groupValues?.get(1)
                    ?.replace("\\/", "/")
                    ?.let { normalize(it) }
                    ?.takeIf { it !in EXCLUDED }

                redirect ?: run {
                    // 2) repli : domaine coflix le plus répété (hors registres)
                    val counts = Regex("""https?://coflix\.[a-zA-Z0-9-]+""")
                        .findAll(html)
                        .mapNotNull { normalize(it.value) }
                        .filter { it !in EXCLUDED }
                        .toList()
                        .groupingBy { it }.eachCount()
                    counts.maxByOrNull { it.value }?.key
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "discover failed: ${e.message}")
            null
        }

        val winner = found ?: DEFAULT_MIRROR
        cachedMirror = winner
        cachedAtMs = now
        Log.d(TAG, "miroir actif = $winner${if (found == null) " (défaut)" else ""} (cache 6h)")
        winner
    }
}
