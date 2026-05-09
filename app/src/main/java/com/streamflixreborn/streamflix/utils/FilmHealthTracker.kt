package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import org.json.JSONObject

/**
 * Tracker persistant des films "présumés sans source disponible".
 *
 *  But
 *  ---
 *  Quand un user clique un film, que TOUS les serveurs échouent, et que
 *  TOUS les fails sont classifiés `dead-content` ou `source unavailable`,
 *  on marque le film comme "présumé vide" pour ce provider. Au prochain
 *  chargement du home, ce film est rétrogradé EN BAS de sa section
 *  (jamais caché — l'user peut toujours cliquer dessus pour retenter).
 *
 *  Auto-correction : un succès sur ce film le retire immédiatement de la
 *  liste vide. TTL 7 jours pour forcer un re-test périodique au cas où le
 *  film s'indexerait entre temps.
 *
 *  Modèle de données
 *  -----------------
 *  SharedPreferences `film_health` :
 *  - clé `empty_films_json` → JSON :
 *    ```
 *    {
 *      "movix:1669050": 1778900000000,    // timestamp expiration ms
 *      "movix:1314481": 1778800000000,
 *      "frenchstream:9876": ...,
 *      ...
 *    }
 *    ```
 *
 *  Clé = `<providerName>:<filmId>` — important pour ne pas masquer un film
 *  sur FrenchStream juste parce qu'il foire sur Movix.
 *
 *  Comportement à NE PAS confondre avec [ExtractorFailureTracker]
 *  --------------------------------------------------------------
 *  - ExtractorFailureTracker : mesure la SANTÉ d'un EXTRACTEUR (Voe,
 *    Filemoon, etc.) → utilisé par ExtractorRanker pour ordonner les
 *    SERVEURS dans le picker.
 *  - FilmHealthTracker : mesure la SANTÉ d'un FILM (par provider) →
 *    utilisé pour ordonner les FILMS dans le home.
 *  Deux couches indépendantes qui se complètent.
 */
object FilmHealthTracker {

    private const val TAG = "FilmHealthTracker"
    private const val PREFS = "film_health"
    private const val KEY_EMPTY_FILMS = "empty_films_json"

    /** TTL avant de retenter un film présumé vide. 7 jours = laisse le temps
     *  aux nouveaux films de s'indexer chez les hosts (typiquement 3-7 jours
     *  après sortie pour les nouveautés). */
    private const val EMPTY_TTL_MS = 7L * 24 * 3600 * 1000

    private fun prefs(): android.content.SharedPreferences =
        StreamFlixApp.instance.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun makeKey(providerName: String, filmId: String): String =
        "${providerName}:${filmId}"

    /** Lazy load du cache en mémoire. */
    @Volatile private var cache: MutableMap<String, Long>? = null

    @Synchronized
    private fun ensureCacheLoaded(): MutableMap<String, Long> {
        cache?.let { return it }
        val raw = prefs().getString(KEY_EMPTY_FILMS, "{}") ?: "{}"
        val map = mutableMapOf<String, Long>()
        try {
            val root = JSONObject(raw)
            val keys = root.keys()
            val now = System.currentTimeMillis()
            while (keys.hasNext()) {
                val key = keys.next()
                val expiresAt = root.optLong(key, 0L)
                // Skip les entries expirées (au passage on les nettoie de la prochaine sauvegarde)
                if (expiresAt > now) {
                    map[key] = expiresAt
                }
            }
            // Si on a viré des entries expirées, persister la version nettoyée.
            if (map.size != root.length()) {
                Log.d(TAG, "Cleaned ${root.length() - map.size} expired entries on load")
                persistAsync(map)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache load failed (corrupt JSON?), starting fresh: ${e.message}")
        }
        cache = map
        return map
    }

    private fun persistAsync(map: Map<String, Long> = ensureCacheLoaded()) {
        try {
            val root = JSONObject()
            for ((key, expiresAt) in map) {
                root.put(key, expiresAt)
            }
            prefs().edit().putString(KEY_EMPTY_FILMS, root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Persist failed: ${e.message}")
        }
    }

    /**
     * Marque un film comme présumé sans source pour un provider donné.
     * À appeler quand TOUS les serveurs ont échoué ET la majorité des
     * fails étaient `dead-content` / `source unavailable`.
     *
     * Idempotent : refresh le timestamp d'expiration si déjà marqué.
     */
    @Synchronized
    fun markEmpty(providerName: String, filmId: String) {
        if (providerName.isBlank() || filmId.isBlank()) return
        val key = makeKey(providerName, filmId)
        val expiresAt = System.currentTimeMillis() + EMPTY_TTL_MS
        ensureCacheLoaded()[key] = expiresAt
        persistAsync()
        Log.d(TAG, "markEmpty: $key (expires in 7 days)")
    }

    /**
     * Retire la marque "vide" pour un film. À appeler quand au moins un
     * serveur a réussi à servir une vidéo jouable. Auto-correction.
     */
    @Synchronized
    fun unmark(providerName: String, filmId: String) {
        if (providerName.isBlank() || filmId.isBlank()) return
        val key = makeKey(providerName, filmId)
        val map = ensureCacheLoaded()
        if (map.remove(key) != null) {
            persistAsync()
            Log.d(TAG, "unmark: $key (success recorded)")
        }
    }

    /** Vrai si le film est marqué présumé vide ET que la marque n'a pas expiré. */
    @Synchronized
    fun isEmpty(providerName: String, filmId: String): Boolean {
        if (providerName.isBlank() || filmId.isBlank()) return false
        val key = makeKey(providerName, filmId)
        val expiresAt = ensureCacheLoaded()[key] ?: return false
        return System.currentTimeMillis() < expiresAt
    }

    /**
     * Sort une liste d'items par "santé" : items présumés vides à la fin,
     * autres dans leur ordre original (sort stable).
     *
     * @param providerName nom du provider courant (pour la clé de lookup)
     * @param items la liste à trier
     * @param getId fonction qui extrait l'ID du film de chaque item
     *
     * Exemple d'usage dans MovixProvider.getHome :
     * ```
     * list = FilmHealthTracker.sortByHealth(name, films) { it.id }
     * ```
     */
    fun <T> sortByHealth(providerName: String, items: List<T>, getId: (T) -> String): List<T> {
        if (items.size <= 1) return items
        // sortBy stable : les items présumés vides (true → 1) vont après les sains (false → 0).
        // Préserve l'ordre relatif d'origine pour les items de même statut.
        return items.sortedBy { item ->
            if (isEmpty(providerName, getId(item))) 1 else 0
        }
    }

    /** Reset manuel — pour bouton dans Settings si jamais utile. */
    @Synchronized
    fun resetAll() {
        prefs().edit().remove(KEY_EMPTY_FILMS).apply()
        cache = null
        Log.d(TAG, "resetAll")
    }

    /** Nombre d'entries actuellement marquées comme vides (pour debug/affichage). */
    @Synchronized
    fun size(): Int = ensureCacheLoaded().size
}
