package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 2026-06-02 — Auto-discovery du miroir Coflix actif via coflix.blog.
 *
 * coflix.blog est le "registre officiel" du site : la page d'accueil annonce
 * le miroir Coflix courant (ex : coflix.cymru au 28/05/2026, autre chose la
 * prochaine fois). On la fetch, on parse, on cache 6h.
 *
 * Quand Coflix migre, l'app suit automatiquement sans nouvelle release.
 *
 * Extrait dans un fichier séparé parce que CoflixSourceProvider.kt est juste
 * sous une limite de taille du tool d'édition (~12 ko) — l'ajouter en ligne
 * tronquait silencieusement le fichier.
 */
object CoflixMirrorDiscovery {

    private const val TAG = "CoflixMirrorDiscovery"
    private const val DISCOVERY_URL = "https://coflix.blog/"
    private const val DISCOVERY_TTL_MS = 6 * 60 * 60 * 1000L // 6h
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile private var cachedMirror: String? = null
    @Volatile private var cachedAtMs: Long = 0L

    private val httpClient by lazy { Extractor.sharedClient }

    /**
     * Retourne le miroir actuel annoncé par coflix.blog, ou null si la page
     * est unreachable ou ne contient pas de domaine parsable. Cache 6h.
     *
     * Heuristique de parsing : on prend toutes les occurrences de
     * `https://coflix.<tld>` dans le HTML, on exclut `coflix.blog`
     * (auto-référence) et `coflix.plus` (host des assets), puis on garde
     * le domaine LE PLUS RÉPÉTÉ. Le miroir annoncé apparaît typiquement
     * 5-10 fois (titre, liens, partage, dates) alors qu'un domaine cité
     * de passage n'apparaît qu'une fois.
     */
    suspend fun discover(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val hit = cachedMirror
        if (hit != null && (now - cachedAtMs) < DISCOVERY_TTL_MS) return@withContext hit

        try {
            val req = Request.Builder()
                .url(DISCOVERY_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .build()
            val html = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "coflix.blog HTTP ${resp.code}")
                    return@withContext null
                }
                resp.body?.string() ?: return@withContext null
            }
            val matches = Regex("""https?://coflix\.([a-zA-Z0-9-]+)""")
                .findAll(html)
                .map { "https://coflix.${it.groupValues[1].lowercase()}" }
                .filter { it != "https://coflix.blog" && it != "https://coflix.plus" }
                .toList()
            if (matches.isEmpty()) {
                Log.d(TAG, "aucun miroir trouvé dans coflix.blog")
                return@withContext null
            }
            val winner = matches.groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key
            if (winner != null) {
                cachedMirror = winner
                cachedAtMs = now
                Log.d(TAG, "miroir actif = $winner (cache 6h)")
            }
            winner
        } catch (e: Exception) {
            Log.d(TAG, "failed: ${e.message}")
            null
        }
    }
}
