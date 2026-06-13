package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject

/**
 * 2026-06-13 (user "From apparaît dans la home Wiflix mais la search ramène
 *  rien à cause du bot shield" + capture site Wiflix navigateur OK) :
 *  Cache local title-normalisé → URL Wiflix complète.
 *
 *  Le bot shield de flemmix.city bloque les POST search depuis l'app (TLS
 *  fingerprint, User-Agent strict ou JA3). Mais la HOME Wiflix charge sans
 *  problème et liste From + autres séries avec leur URL complète
 *  (`/saison-complete/27119-from-2022-saison-2.html`).
 *
 *  Stratégie : à chaque fois que le main provider Wiflix scrape une carte
 *  (home, genre, films, séries), on appelle put(title, href) pour stocker
 *  le mapping. Le backup Cloudstream interroge ce cache via lookup(query)
 *  AVANT de tenter searchRaw → si la série a déjà été aperçue, on a son URL,
 *  on bypass totalement le search bot-shieldé.
 *
 *  Storage : SharedPrefs JSON (un seul key, set de paires). TTL infini —
 *  le mapping reste valide tant que Wiflix garde la série au même URL.
 *  Si l'URL change, le re-scrape l'écrasera.
 */
object WiflixUrlCache {
    private const val PREF_KEY = "wiflix_url_cache"
    /** Cache mémoire pour éviter de re-parser le JSON à chaque lookup. */
    @Volatile private var memCache: MutableMap<String, String>? = null

    private fun loadMem(context: Context): MutableMap<String, String> {
        memCache?.let { return it }
        synchronized(this) {
            memCache?.let { return it }
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            val raw = sp.getString(PREF_KEY, null) ?: ""
            val map = mutableMapOf<String, String>()
            if (raw.isNotBlank()) {
                try {
                    val o = JSONObject(raw)
                    o.keys().forEach { k -> map[k] = o.optString(k) }
                } catch (_: Throwable) {}
            }
            memCache = map
            return map
        }
    }

    private fun persist(context: Context) {
        val map = memCache ?: return
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREF_KEY, o.toString()).apply()
    }

    /** Normalise un titre pour la clé du cache (lowercase, sans accents/ponct). */
    private fun norm(title: String): String =
        java.text.Normalizer.normalize(title.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Stocke un mapping title → URL complète. À appeler depuis le scraping
     *  du main provider quand il parse les pages. */
    fun put(context: Context, title: String, fullUrl: String) {
        if (title.isBlank() || fullUrl.isBlank()) return
        if (!fullUrl.startsWith("http")) return
        val map = loadMem(context)
        val key = norm(title)
        if (key.isBlank()) return
        // Évite la persistance inutile si pas de changement
        if (map[key] == fullUrl) return
        map[key] = fullUrl
        persist(context)
    }

    /** Cherche une URL depuis un titre (= match exact normalisé ou prefix mot-à-mot). */
    fun lookup(context: Context, query: String): String? {
        val map = loadMem(context)
        if (map.isEmpty()) return null
        val n = norm(query)
        // Match exact d'abord
        map[n]?.let { return it }
        // Match : la clé commence par le query suivi d'un séparateur naturel
        //   (ex: "from" → "from saison 2"). Pour éviter "from" → "from the
        //   cold" on exige que les mots de query soient en TÊTE des mots de
        //   la clé (= prefix word-aligned).
        val qWords = n.split(" ").filter { it.isNotBlank() }
        if (qWords.isEmpty()) return null
        val matches = map.entries.filter { (k, _) ->
            val kWords = k.split(" ")
            kWords.size >= qWords.size && kWords.take(qWords.size) == qWords
        }
        // Préfère la clé la PLUS COURTE (= la moins suffixée)
        return matches.minByOrNull { it.key.length }?.value
    }

    /** Récupère toutes les entrées (debug/info). */
    fun snapshot(context: Context): Map<String, String> =
        loadMem(context).toMap()

    /** Vide le cache (utile pour debug ou si l'user veut forcer un re-scrape). */
    fun clear(context: Context) {
        synchronized(this) {
            memCache?.clear()
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit().remove(PREF_KEY).apply()
        }
    }
}
