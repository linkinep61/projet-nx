package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import org.json.JSONObject

/**
 * Tracker persistant des échecs d'extraction par extracteur.
 *
 *  Modèle de données (v2 — 2026-05-08)
 *  -----------------------------------
 *  Stocké en SharedPreferences `extractor_failures` :
 *  - clé `version_code`  → versionCode au moment du dernier wipe (Int)
 *  - clé `failures_json` → JSON détaillé par extracteur :
 *    ```
 *    {
 *      "Filemoon": {
 *        "count": 5,
 *        "errors":    {"timeout": 3, "403": 2},
 *        "providers": {"Movix": 4, "FrenchStream": 1}
 *      },
 *      "Vidoza": {...}
 *    }
 *    ```
 *
 *  L'écran "Extracteurs" peut donc afficher :
 *   - **type d'erreur** : timeout / 403 / 404 / parsing / dns-fail / ssl-fail / 5xx / other
 *     → permet de distinguer "le serveur est mort" vs "ils ont changé leur HTML"
 *   - **provider source** : par où l'utilisateur a appelé cet extracteur
 *     → permet de cibler le fix au bon endroit (Movix backup chain ? FrenchStream ?)
 *
 *  Comportement
 *  ------------
 *  - À chaque échec d'extraction (vraie tentative qui throw) on incrémente.
 *  - Au démarrage, si [BuildConfig.VERSION_CODE] ≠ versionCode stocké → wipe.
 *    Garantit "Le reste de l'onglet doit être fait à chaque mise à jour"
 *    du user : nouvelle release = compteurs remis à zéro.
 *  - Thread-safe via `synchronized` — pas de ConcurrentMod.
 *  - **Compat ascendante** : si on lit l'ancien format (valeur Int au lieu de
 *    Object), on le considère comme `{count: N, errors:{}, providers:{}}`.
 *
 *  Filtrage "pas les échecs si on n'a pas la vidéo à l'affiche"
 *  -----------------------------------------------------------
 *  L'appel à [recordFailure] se fait UNIQUEMENT depuis Extractor.kt
 *  dans le catch de `foundExtractor.extract(finalLink)`. Donc on capte
 *  juste les exceptions de la vraie tentative d'extraction. Si la source
 *  n'existe pas (pas d'extractor matché → "No extractors found") ce throw
 *  ne passe PAS par [recordFailure]. Si l'extractor retourne sans
 *  exception (Video valide), pas non plus.
 */
object ExtractorFailureTracker {

    private const val TAG = "ExtractorFailures"
    private const val PREFS = "extractor_failures"
    private const val KEY_VERSION = "version_code"
    private const val KEY_FAILURES = "failures_json"
    private const val KEY_LAST_RESET = "last_reset_at_ms"

    /**
     * Détail d'un extracteur en échec, prêt à afficher dans l'UI ou le
     * rapport bug.
     *
     * @property name nom de l'extracteur (ex: "Filemoon")
     * @property count nombre d'échecs CONSÉCUTIFS depuis le dernier succès
     * @property errors breakdown par type d'erreur, trié décroissant
     *                  (ex: [timeout to 3, 403 to 2])
     * @property providers breakdown par provider source qui a appelé cet
     *                     extracteur, trié décroissant
     *                     (ex: [Movix to 4, FrenchStream to 1])
     */
    data class FailureEntry(
        val name: String,
        val count: Int,
        val errors: List<Pair<String, Int>>,
        val providers: List<Pair<String, Int>>,
    )

    private fun prefs(): android.content.SharedPreferences =
        StreamFlixApp.instance.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Vérifie si versionCode change depuis le dernier wipe. Si oui → reset. */
    private fun ensureFreshVersion() {
        val p = prefs()
        val saved = p.getInt(KEY_VERSION, 0)
        val current = BuildConfig.VERSION_CODE
        if (saved != current) {
            Log.d(TAG, "App update detected ($saved → $current), wiping failure counters")
            p.edit()
                .putInt(KEY_VERSION, current)
                .putString(KEY_FAILURES, "{}")
                .putLong(KEY_LAST_RESET, System.currentTimeMillis())
                .apply()
        }
    }

    /**
     * Incrémente le compteur d'échec pour un extracteur, en taggant le type
     * d'erreur et le provider source.
     *
     * @param extractorName nom de l'extracteur (Extractor.name)
     * @param errorType label court : "timeout", "403", "404", "5xx",
     *                  "dns-fail", "ssl-fail", "parsing", "connect-fail",
     *                  "other". Cf [classifyError] dans Extractor.kt.
     * @param providerName nom du provider qui a appelé l'extracteur
     *                     (UserPreferences.currentProvider?.name). Peut être
     *                     null si le contexte n'est pas connu — dans ce cas
     *                     on n'incrémente pas la map providers.
     */
    @Synchronized
    fun recordFailure(extractorName: String, errorType: String?, providerName: String?) {
        // 2026-07-07 (user « y a pas de serveur qui doivent être marqués 7 jours et bloqué,
        //   ça doit pas exister non plus ») : tracking PERSISTANT des échecs de serveur
        //   DÉSACTIVÉ. On ne persiste plus aucun échec → aucun serveur n'est rétrogradé /
        //   pénalisé au tri sur un échec passé (qui survivait au force-stop). Chaque session
        //   repart propre. (Le disjoncteur IN-MÉMOIRE Extractor.serverHealth reste, lui, mais
        //   il meurt au redémarrage — rien de persistant.)
        return
    }

    /**
     * Compat ascendante : ancien overload qui ne connaît ni le type d'erreur
     * ni le provider. Utilisé tant que tous les call sites n'ont pas migré.
     * Préfère l'overload à 3 args.
     */
    @Synchronized
    fun recordFailure(extractorName: String) =
        recordFailure(extractorName, errorType = null, providerName = null)

    /**
     * Lit l'entrée pour un extracteur dans le JSON racine.
     * Migre transparentement l'ancien format `{Filemoon: 5}` (valeur Int) en
     * `{Filemoon: {count: 5, errors: {}, providers: {}}}` pour qu'on puisse
     * continuer à l'incrémenter sans perdre les compteurs accumulés avant la
     * v2 du tracker.
     */
    private fun readOrInitEntry(root: JSONObject, name: String): JSONObject {
        val existing = root.opt(name)
        return when (existing) {
            is JSONObject -> existing
            is Number -> JSONObject().apply {
                put("count", existing.toInt())
                put("errors", JSONObject())
                put("providers", JSONObject())
            }
            else -> JSONObject().apply {
                put("count", 0)
                put("errors", JSONObject())
                put("providers", JSONObject())
            }
        }
    }

    /**
     * Retourne la liste des extracteurs en échec, avec leur breakdown
     * complète, triée par count décroissant.
     */
    // 2026-07-04 (perf TV) : lecture LOCK-FREE. AVANT @Synchronized → pendant qu'un
    //   provider probe ~20 sources, recordFailure()/recordSuccess() (mêmes @Synchronized)
    //   tenaient le verrou en rafale (parse+réécriture disque du JSON complet à chaque échec).
    //   getFailures() (appelé par le tri du player) attendait DERRIÈRE toutes ces écritures →
    //   orderByFrenchBuckets bloqué ~8-11s. Les lectures SharedPreferences.getString sont déjà
    //   thread-safe et .apply() commit de façon atomique → une lecture non synchronisée voit
    //   toujours une valeur complète (ancienne ou nouvelle, jamais partielle). On retire donc
    //   le verrou : le tri ne se fait plus affamer. (Le parse JSON local reste en ms.)
    fun getFailures(): List<FailureEntry> {
        // 2026-07-07 (user « le tri se fait qu'avec la langue, les favoris et la qualité,
        //   tout le reste on s'en fout ») : tracking persistant désactivé → aucune pénalité
        //   de tri basée sur des échecs persistés (neutralise aussi les données déjà écrites).
        return emptyList()
    }

    /** Convertit un JSONObject {key: int} en List<Pair<key, int>> trié décroissant. */
    private fun jsonObjectToSortedPairs(o: JSONObject?): List<Pair<String, Int>> {
        if (o == null) return emptyList()
        val pairs = mutableListOf<Pair<String, Int>>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.optInt(k, 0)
            if (v > 0) pairs += k to v
        }
        return pairs.sortedByDescending { it.second }
    }

    /** Reset le compteur d'UN extracteur — appelé sur succès d'extraction.
     *  Ainsi le compteur reflète les échecs CONSÉCUTIFS (pas cumulés) :
     *  un succès "remet à zéro la confiance". Si l'extracteur remarche →
     *  compteur invisible dans l'UI. S'il continue à échouer après le succès →
     *  on repart de 1 et le compteur monte à nouveau.
     *
     *  Note : on enlève AUSSI les sous-compteurs errors/providers pour ne pas
     *  laisser de breakdown fantôme sur un extracteur qui marche maintenant. */
    @Synchronized
    fun recordSuccess(extractorName: String) {
        if (extractorName.isBlank()) return
        ensureFreshVersion()
        val p = prefs()
        val raw = p.getString(KEY_FAILURES, "{}") ?: "{}"
        val json = try { JSONObject(raw) } catch (_: Exception) { return }
        if (!json.has(extractorName)) return
        json.remove(extractorName)
        p.edit().putString(KEY_FAILURES, json.toString()).apply()
        Log.d(TAG, "Success → counter reset for $extractorName")
    }

    /** Reset manuel (bouton "Effacer" dans l'UI). */
    @Synchronized
    fun resetAll() {
        prefs().edit()
            .putInt(KEY_VERSION, BuildConfig.VERSION_CODE)
            .putString(KEY_FAILURES, "{}")
            .putLong(KEY_LAST_RESET, System.currentTimeMillis())
            .apply()
    }

    /** Timestamp ms du dernier reset (manuel ou auto sur update). */
    fun lastResetAtMs(): Long = prefs().getLong(KEY_LAST_RESET, 0L)
}
