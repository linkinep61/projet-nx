package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tracker persistant de latence d'extraction par extracteur.
 *
 *  But
 *  ---
 *  Mesurer le temps RÉEL que met chaque extracteur à retourner une URL m3u8
 *  jouable, sur le device + le réseau de l'utilisateur. Ces mesures
 *  remplacent progressivement la table [ExtractorRanker.SPEED_BIAS]
 *  hardcodée — qui sert seulement de fallback bootstrap les premiers jours
 *  d'usage avant qu'on ait assez de samples.
 *
 *  Modèle de données
 *  -----------------
 *  Stocké en SharedPreferences `extractor_latency` :
 *  - clé `version_code` → versionCode au moment du dernier wipe (Int)
 *  - clé `latency_json` → JSON :
 *    ```
 *    {
 *      "Voe":      { "samples": [820, 951, 780, ...], "totalCount": 47 },
 *      "Filemoon": { "samples": [2640, 3120, 2480, ...], "totalCount": 23 },
 *      ...
 *    }
 *    ```
 *
 *  La moyenne mobile se calcule sur les `samples` (cap [MAX_SAMPLES] derniers,
 *  fenêtre glissante). `totalCount` est purement informatif (combien d'éch.
 *  total accumulé).
 *
 *  Comportement
 *  ------------
 *  - À chaque extraction RÉUSSIE, [recordExtraction] ajoute la durée mesurée.
 *    Les échecs ne sont pas enregistrés ici (ils sont déjà trackés par
 *    [ExtractorFailureTracker]).
 *  - Au démarrage, si versionCode ≠ stocké → wipe (purge des données
 *    obsolètes après une release qui peut avoir changé les extracteurs).
 *  - Thread-safe via `@Synchronized` — pas de ConcurrentMod sous load.
 *
 *  Trade-off CPU/IO
 *  ----------------
 *  - Mesure : 2 × `System.currentTimeMillis()` = nanosecondes, gratuit.
 *  - Lecture (`getAvgMs`) : déserialise le JSON et calcule la moyenne =
 *    quelques µs. Acceptable car appelé seulement 1 fois par tri (pas
 *    par décodage de frame ou autre).
 *  - Écriture : SharedPreferences `apply()` = async, non-bloquant.
 *
 *  Aucun impact sur la latence de lecture vidéo elle-même : la mesure est
 *  passive, l'écriture est async, la lecture est cachée en mémoire après
 *  le 1er appel via Map en lazy-load.
 */
object ExtractorLatencyTracker {

    private const val TAG = "ExtractorLatency"
    private const val PREFS = "extractor_latency"
    private const val KEY_VERSION = "version_code"
    private const val KEY_LATENCY = "latency_json"

    /** Cap du nombre de samples par extracteur (fenêtre glissante).
     *  20 samples = ~1 semaine d'usage normal pour un extracteur populaire,
     *  ce qui couvre des variations réseau (heures pleines/creuses, switch
     *  Wi-Fi/4G…) sans trop "se souvenir" de patterns obsolètes. */
    private const val MAX_SAMPLES = 20

    /** Seuil minimum de samples avant qu'une moyenne soit considérée
     *  "fiable". En dessous, le ranker préfère le fallback hardcodé pour
     *  ne pas rétrograder un extracteur sur 1-2 mesures pas représentatives
     *  (genre un timeout de DNS exceptionnel). */
    const val MIN_SAMPLES_FOR_RELIABLE_AVG = 3

    /** Cap absolu sur une mesure individuelle. Au-dessus, on assume que
     *  c'était un échec masqué (timeout extracteur qui a fini par sortir
     *  une URL malgré tout, ou throttling temporaire) et on n'enregistre
     *  pas pour ne pas polluer la moyenne. */
    private const val MAX_REASONABLE_LATENCY_MS = 30_000L

    /** Cache mémoire des données. Hydrate lazily à la 1ère lecture. */
    @Volatile private var cache: MutableMap<String, ExtractorStats>? = null

    /** Stats internes par extracteur. */
    private data class ExtractorStats(
        val samples: ArrayDeque<Long>,
        var totalCount: Int,
    )

    private fun prefs(): android.content.SharedPreferences =
        StreamFlixApp.instance.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Si versionCode change → reset (samples obsolètes après release). */
    private fun ensureFreshVersion() {
        val p = prefs()
        val saved = p.getInt(KEY_VERSION, 0)
        val current = BuildConfig.VERSION_CODE
        if (saved != current) {
            Log.d(TAG, "App update detected ($saved → $current), wiping latency data")
            p.edit()
                .putInt(KEY_VERSION, current)
                .putString(KEY_LATENCY, "{}")
                .apply()
            cache = null
        }
    }

    /** Lazy load du cache depuis prefs. Idempotent. */
    @Synchronized
    private fun ensureCacheLoaded(): MutableMap<String, ExtractorStats> {
        cache?.let { return it }
        ensureFreshVersion()
        val raw = prefs().getString(KEY_LATENCY, "{}") ?: "{}"
        val map = mutableMapOf<String, ExtractorStats>()
        try {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val obj = root.optJSONObject(name) ?: continue
                val samplesArr = obj.optJSONArray("samples") ?: continue
                val deque = ArrayDeque<Long>()
                for (i in 0 until samplesArr.length()) {
                    deque.add(samplesArr.optLong(i))
                }
                map[name] = ExtractorStats(
                    samples = deque,
                    totalCount = obj.optInt("totalCount", deque.size),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache load failed (corrupt JSON?), starting fresh: ${e.message}")
        }
        cache = map
        return map
    }

    /** Persiste le cache vers SharedPreferences. Async via apply(). */
    private fun persistAsync() {
        val map = cache ?: return
        try {
            val root = JSONObject()
            for ((name, stats) in map) {
                val obj = JSONObject()
                val arr = JSONArray()
                for (s in stats.samples) arr.put(s)
                obj.put("samples", arr)
                obj.put("totalCount", stats.totalCount)
                root.put(name, obj)
            }
            prefs().edit().putString(KEY_LATENCY, root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Persist failed: ${e.message}")
        }
    }

    /**
     * Enregistre la latence d'une extraction réussie.
     *  - Filtre les outliers (>30s = sans doute un échec masqué).
     *  - Ajoute au sample buffer (FIFO si plein).
     *  - Persiste async.
     */
    @Synchronized
    fun recordExtraction(extractorName: String, durationMs: Long) {
        if (extractorName.isBlank()) return
        if (durationMs <= 0 || durationMs > MAX_REASONABLE_LATENCY_MS) return

        val map = ensureCacheLoaded()
        val stats = map.getOrPut(extractorName) {
            ExtractorStats(samples = ArrayDeque(), totalCount = 0)
        }
        // Cap fenêtre glissante à MAX_SAMPLES.
        if (stats.samples.size >= MAX_SAMPLES) stats.samples.removeFirst()
        stats.samples.addLast(durationMs)
        stats.totalCount++

        persistAsync()
        Log.d(
            TAG,
            "Recorded $extractorName=${durationMs}ms (avg=${stats.samples.average().toInt()}ms over ${stats.samples.size} samples, total=${stats.totalCount})",
        )
    }

    /**
     * Renvoie la moyenne mobile de latence pour un extracteur, ou `null`
     * si pas assez de samples ([MIN_SAMPLES_FOR_RELIABLE_AVG]).
     *
     * Le caller (ExtractorRanker) DOIT vérifier null et fallback sur
     * speed_bias hardcodé dans ce cas.
     */
    @Synchronized
    fun getAvgMs(extractorName: String): Long? {
        if (extractorName.isBlank()) return null
        val stats = ensureCacheLoaded()[extractorName] ?: return null
        if (stats.samples.size < MIN_SAMPLES_FOR_RELIABLE_AVG) return null
        return stats.samples.average().toLong()
    }

    /**
     * Renvoie le nombre total de mesures (utile pour le rapport debug).
     * Pas la même chose que `samples.size` qui est la fenêtre glissante.
     */
    @Synchronized
    fun getTotalCount(extractorName: String): Int =
        ensureCacheLoaded()[extractorName]?.totalCount ?: 0

    /**
     * Snapshot de tout le tracker pour debug / rapport bug.
     * Retourne List<(name, avgMs, sampleCount)> trié par avgMs croissant
     * (les plus rapides en haut).
     */
    @Synchronized
    fun snapshot(): List<Triple<String, Long, Int>> {
        val map = ensureCacheLoaded()
        return map.entries
            .filter { it.value.samples.isNotEmpty() }
            .map { (name, stats) ->
                Triple(name, stats.samples.average().toLong(), stats.totalCount)
            }
            .sortedBy { it.second }
    }

    /** Reset manuel (debug). */
    @Synchronized
    fun resetAll() {
        prefs().edit()
            .putInt(KEY_VERSION, BuildConfig.VERSION_CODE)
            .putString(KEY_LATENCY, "{}")
            .apply()
        cache = null
    }
}
