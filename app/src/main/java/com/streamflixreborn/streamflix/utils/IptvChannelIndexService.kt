package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Index communautaire IPTV via Firebase Firestore.
 *
 * Structure Firestore :
 *   iptv_channels/{channelKey} → {
 *     cids: ["42","107",...],
 *     cidStats: { "42": { ms: 850, ts: serverTimestamp }, "107": { ms: 1200, ts: ... } },
 *     updatedAt: timestamp
 *   }
 *
 * channelKey = clé normalisée de la chaîne (ex: "tf1", "france2", "canal")
 * cids       = liste des CIDs OLA qui contiennent cette chaîne
 * cidStats   = latence moyenne (ms) + dernier vu (ts) par CID
 *
 * Fonctionnement :
 *   1. Un appareil découvre TF1 sur CID 42 en 850ms → reportCidLatency("tf1","42",850)
 *      → Firestore met à jour la latence + timestamp
 *   2. Tous les autres appareils → fetchAllChannels() → CIDs triés par vitesse
 *   3. CIDs pas vus depuis 72h → retirés automatiquement de la liste
 *   4. Si Firebase est vide pour une chaîne → fallback sur le scan classique
 */
object IptvChannelIndexService {

    private const val TAG = "IptvChannelIndex"
    private const val COLLECTION = "iptv_channels"

    /** Seuil de fraîcheur : un CID pas vu depuis 72h est considéré mort. */
    private const val STALE_THRESHOLD_MS = 72 * 3600 * 1000L

    /** Throttle : on ne re-report la latence d'un CID que toutes les heures. */
    private const val REPORT_THROTTLE_MS = 3600 * 1000L

    /** Latence par défaut pour les CIDs sans mesure (triés en dernier). */
    private const val DEFAULT_LATENCY_MS = 5000L

    /** Durée de validité du cache SharedPrefs (6h) — au-delà, on refetch Firestore. */
    private const val LOCAL_CACHE_TTL_MS = 6 * 3600 * 1000L
    private const val PREF_INDEX_CACHE = "iptv_channel_index_cache"
    private const val PREF_INDEX_CACHE_TS = "iptv_channel_index_cache_ts"

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    // ── Cache mémoire : channelKey → Set<CID> ──
    private val channelIndex = ConcurrentHashMap<String, MutableSet<String>>()

    // ── Latence communautaire : channelKey → { cid → avgMs } ──
    private val cidLatencyMap = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    // ── Dernière vue communautaire : channelKey → { cid → epochMs } ──
    private val cidLastSeenMap = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    // ── Stream URLs pré-résolues : channelKey → { cid → m3u8Url } ──
    // Stockées par le scanner Python, lues par l'app pour play INSTANTANÉ (pas de MAC portal)
    private val streamUrlMap = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    // ── Throttle local : "channelKey::cid" → dernier report timestamp ──
    private val lastReportTime = ConcurrentHashMap<String, Long>()

    /**
     * Charge l'index depuis SharedPrefs (instantané, ~5ms) pour éviter le fetch Firestore 5s+.
     * Retourne true si un cache valide a été chargé, false sinon.
     */
    fun loadLocalCache(): Boolean {
        try {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(
                com.streamflixreborn.streamflix.StreamFlixApp.instance
            )
            val ts = prefs.getLong(PREF_INDEX_CACHE_TS, 0L)
            if (System.currentTimeMillis() - ts > LOCAL_CACHE_TTL_MS) return false

            val json = prefs.getString(PREF_INDEX_CACHE, null) ?: return false
            val root = org.json.JSONObject(json)
            var totalCids = 0

            for (key in root.keys()) {
                val chObj = root.getJSONObject(key)
                val cidsArr = chObj.getJSONArray("cids")
                val set = java.util.Collections.newSetFromMap(
                    ConcurrentHashMap<String, Boolean>()
                )
                for (i in 0 until cidsArr.length()) set.add(cidsArr.getString(i))
                if (set.isEmpty()) continue
                channelIndex[key] = set
                totalCids += set.size

                // Latences
                if (chObj.has("lat")) {
                    val latObj = chObj.getJSONObject("lat")
                    val latMap = ConcurrentHashMap<String, Long>()
                    for (cid in latObj.keys()) latMap[cid] = latObj.getLong(cid)
                    if (latMap.isNotEmpty()) cidLatencyMap[key] = latMap
                }

                // Stream URLs pré-résolues
                if (chObj.has("urls")) {
                    val urlObj = chObj.getJSONObject("urls")
                    val urlMap = ConcurrentHashMap<String, String>()
                    for (cid in urlObj.keys()) urlMap[cid] = urlObj.getString(cid)
                    if (urlMap.isNotEmpty()) streamUrlMap[key] = urlMap
                }
            }
            Log.d(TAG, "loadLocalCache: ${channelIndex.size} chaînes, $totalCids CIDs (cache age ${(System.currentTimeMillis() - ts) / 1000}s)")
            return channelIndex.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "loadLocalCache failed: ${e.message}")
            return false
        }
    }

    /**
     * Sauvegarde l'index courant en SharedPrefs pour les prochains lancements.
     */
    private fun saveLocalCache() {
        try {
            val root = org.json.JSONObject()
            for ((key, cids) in channelIndex) {
                val chObj = org.json.JSONObject()
                chObj.put("cids", org.json.JSONArray(cids.toList()))
                val latencies = cidLatencyMap[key]
                if (latencies != null && latencies.isNotEmpty()) {
                    val latObj = org.json.JSONObject()
                    for ((cid, ms) in latencies) latObj.put(cid, ms)
                    chObj.put("lat", latObj)
                }
                val urls = streamUrlMap[key]
                if (urls != null && urls.isNotEmpty()) {
                    val urlObj = org.json.JSONObject()
                    for ((cid, url) in urls) urlObj.put(cid, url)
                    chObj.put("urls", urlObj)
                }
                root.put(key, chObj)
            }
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(
                com.streamflixreborn.streamflix.StreamFlixApp.instance
            )
            prefs.edit()
                .putString(PREF_INDEX_CACHE, root.toString())
                .putLong(PREF_INDEX_CACHE_TS, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "saveLocalCache: ${channelIndex.size} chaînes saved")
        } catch (e: Exception) {
            Log.w(TAG, "saveLocalCache failed: ${e.message}")
        }
    }

    /**
     * Retourne les CIDs connus pour une chaîne, TRIÉS par latence (le plus rapide en premier).
     * Filtre les CIDs morts (pas vus depuis 72h).
     * Si aucun CID connu → retourne une liste vide → le provider fait le scan classique.
     */
    fun getKnownCids(channelKey: String): List<String> {
        val cids = channelIndex[channelKey] ?: return emptyList()
        val latencies = cidLatencyMap[channelKey]
        val lastSeens = cidLastSeenMap[channelKey]
        val now = System.currentTimeMillis()

        // Filtre les CIDs morts (pas vus depuis STALE_THRESHOLD)
        val alive = if (lastSeens != null && lastSeens.isNotEmpty()) {
            cids.filter { cid ->
                val ls = lastSeens[cid]
                // Pas de données lastSeen = CID nouveau (on le garde)
                ls == null || (now - ls) < STALE_THRESHOLD_MS
            }
        } else {
            cids.toList()
        }

        // Tri par latence : le plus rapide en premier, inconnu en dernier
        return if (latencies != null && latencies.isNotEmpty()) {
            alive.sortedBy { cid -> latencies[cid] ?: DEFAULT_LATENCY_MS }
        } else {
            alive
        }
    }

    /** Retourne l'index complet (depuis le cache mémoire). */
    fun getFullIndex(): Map<String, Set<String>> = channelIndex.toMap()

    /** Nombre de chaînes indexées en mémoire. */
    fun indexSize(): Int = channelIndex.size

    /** Retourne la latence moyenne connue pour un CID d'une chaîne (null si inconnue). */
    fun getLatency(channelKey: String, cid: String): Long? = cidLatencyMap[channelKey]?.get(cid)

    /**
     * Retourne les URLs de stream pré-résolues pour une chaîne, triées par latence CID.
     * Ces URLs viennent du scanner Python et sont des m3u8 directs — pas besoin de MAC portal.
     * Retourne une liste de Pair(cid, url) triée par latence (le plus rapide en premier).
     */
    fun getCachedStreamUrls(channelKey: String): List<Pair<String, String>> {
        val urls = streamUrlMap[channelKey]
        if (urls == null || urls.isEmpty()) {
            Log.d(TAG, "getCachedStreamUrls($channelKey): AUCUNE URL (streamUrlMap keys=${streamUrlMap.keys.take(5)})")
            return emptyList()
        }
        val latencies = cidLatencyMap[channelKey]
        val sorted = urls.entries
            .sortedBy { (cid, _) -> latencies?.get(cid) ?: DEFAULT_LATENCY_MS }
            .map { (cid, url) -> Pair(cid, url) }
        Log.d(TAG, "getCachedStreamUrls($channelKey): ${sorted.size} URLs disponibles")
        return sorted
    }

    /**
     * Met à jour l'URL de stream pour un CID après vérification (HEAD OK).
     * Appelé par l'app quand elle découvre une URL fonctionnelle via MAC portal.
     */
    fun reportStreamUrl(channelKey: String, cid: String, url: String) {
        val map = streamUrlMap.getOrPut(channelKey) { ConcurrentHashMap() }
        map[cid] = url
        // Fire-and-forget Firebase update
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val data = mapOf(
                    "streamUrls" to mapOf(cid to url),
                    "urlTs" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                db.collection(COLLECTION).document(channelKey)
                    .set(data, SetOptions.merge())
                    .await()
                Log.d(TAG, "reportStreamUrl: $channelKey CID $cid → $url")
            } catch (e: Exception) {
                Log.w(TAG, "reportStreamUrl failed: ${e.message}")
            }
        }
    }

    /**
     * Invalide une URL de stream (HEAD a échoué → URL morte).
     */
    fun invalidateStreamUrl(channelKey: String, cid: String) {
        streamUrlMap[channelKey]?.remove(cid)
    }

    /**
     * Récupère l'index COMPLET depuis Firestore (1 seul appel réseau).
     * Lit les CIDs + leurs stats (latence, dernière vue).
     * Supprime automatiquement les CIDs morts (pas vus depuis 72h) de Firestore.
     */
    suspend fun fetchAllChannels(): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        try {
            val t0 = System.currentTimeMillis()
            val snapshot = db.collection(COLLECTION).get().await()
            val result = mutableMapOf<String, Set<String>>()
            val now = System.currentTimeMillis()
            var totalCids = 0
            var removedStale = 0

            for (doc in snapshot.documents) {
                val key = doc.id
                @Suppress("UNCHECKED_CAST")
                val cids = doc.get("cids") as? List<String> ?: continue
                if (cids.isEmpty()) continue

                val set = java.util.Collections.newSetFromMap(
                    ConcurrentHashMap<String, Boolean>()
                ).apply { addAll(cids) }

                // ── Lire cidStats (latence + dernière vue) ──
                @Suppress("UNCHECKED_CAST")
                val statsRaw = doc.get("cidStats") as? Map<String, Map<String, Any>>
                val staleCids = mutableListOf<String>()

                if (statsRaw != null) {
                    val latencies = ConcurrentHashMap<String, Long>()
                    val lastSeens = ConcurrentHashMap<String, Long>()

                    for ((cid, vals) in statsRaw) {
                        val ms = (vals["ms"] as? Number)?.toLong()
                        val ts = vals["ts"]
                        val tsMs = when (ts) {
                            is Timestamp -> ts.toDate().time
                            is Number -> ts.toLong()
                            else -> null
                        }

                        if (ms != null) latencies[cid] = ms
                        if (tsMs != null) {
                            lastSeens[cid] = tsMs
                            // Détecter les CIDs morts
                            if ((now - tsMs) > STALE_THRESHOLD_MS) {
                                staleCids.add(cid)
                            }
                        }
                    }

                    if (latencies.isNotEmpty()) cidLatencyMap[key] = latencies
                    if (lastSeens.isNotEmpty()) cidLastSeenMap[key] = lastSeens
                }

                // Retirer les CIDs morts du set mémoire
                for (stale in staleCids) {
                    set.remove(stale)
                }

                // Nettoyage Firebase : retirer les CIDs morts de la DB (fire-and-forget)
                if (staleCids.isNotEmpty()) {
                    removedStale += staleCids.size
                    val docKey = key // capture pour le lambda
                    val staleToRemove = staleCids.toList()
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val cleanupData = mutableMapOf<String, Any>(
                                "cids" to FieldValue.arrayRemove(*staleToRemove.toTypedArray())
                            )
                            for (cid in staleToRemove) {
                                cleanupData["cidStats.$cid"] = FieldValue.delete()
                            }
                            db.collection(COLLECTION).document(docKey).update(cleanupData).await()
                            Log.d(TAG, "cleanup: removed ${staleToRemove.size} stale CIDs from '$docKey': $staleToRemove")
                        } catch (e: Exception) {
                            Log.w(TAG, "cleanup failed for '$docKey': ${e.message}")
                        }
                    }
                }

                // ── Lire streamUrls (URLs pré-résolues par le scanner) ──
                @Suppress("UNCHECKED_CAST")
                val urlsRaw = doc.get("streamUrls") as? Map<String, Any>
                if (urlsRaw != null && urlsRaw.isNotEmpty()) {
                    val urlMap = ConcurrentHashMap<String, String>()
                    for ((cid, urlVal) in urlsRaw) {
                        val url = urlVal?.toString() ?: continue
                        if (url.startsWith("http")) urlMap[cid] = url
                    }
                    if (urlMap.isNotEmpty()) {
                        streamUrlMap[key] = urlMap
                        Log.d(TAG, "  streamUrls loaded for '$key': ${urlMap.size} URLs")
                    }
                }

                if (set.isNotEmpty()) {
                    channelIndex[key] = set
                    result[key] = set
                    totalCids += set.size
                }
            }

            val elapsed = System.currentTimeMillis() - t0
            Log.d(TAG, "fetchAllChannels: ${result.size} chaînes, $totalCids CIDs (${elapsed}ms)" +
                    if (removedStale > 0) " — $removedStale stale CIDs removed" else "")

            // Sauvegarder en cache local pour les prochains lancements (évite 5s+ Firestore)
            if (result.isNotEmpty()) saveLocalCache()

            result
        } catch (e: Exception) {
            Log.e(TAG, "fetchAllChannels failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Signale qu'un CID contient une chaîne donnée + sa latence de connexion.
     * Combine reportCid + latence en 1 seul appel Firebase.
     * Throttlé : max 1 report par CID par heure.
     */
    suspend fun reportCidLatency(
        channelKey: String,
        cid: String,
        latencyMs: Long,
    ) = withContext(Dispatchers.IO) {
        // ── Mise à jour du cache mémoire (toujours, même si throttlé) ──
        val set = channelIndex.getOrPut(channelKey) {
            java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        }
        set.add(cid)

        // Latence : moyenne pondérée (70% nouvelle mesure, 30% ancienne)
        val latMap = cidLatencyMap.getOrPut(channelKey) { ConcurrentHashMap() }
        val prev = latMap[cid]
        val newAvg = if (prev != null && prev > 0) {
            (prev * 0.3 + latencyMs * 0.7).toLong()
        } else {
            latencyMs
        }
        latMap[cid] = newAvg

        // Dernière vue
        val lsMap = cidLastSeenMap.getOrPut(channelKey) { ConcurrentHashMap() }
        lsMap[cid] = System.currentTimeMillis()

        // ── Throttle Firebase (1 report / CID / heure) ──
        val throttleKey = "$channelKey::$cid"
        val now = System.currentTimeMillis()
        val lastReport = lastReportTime[throttleKey] ?: 0L
        if (now - lastReport < REPORT_THROTTLE_MS) return@withContext
        lastReportTime[throttleKey] = now

        // ── Écriture Firebase ──
        try {
            val data = mapOf(
                "cids" to FieldValue.arrayUnion(cid),
                "cidStats" to mapOf(
                    cid to mapOf(
                        "ms" to newAvg,
                        "ts" to FieldValue.serverTimestamp(),
                    )
                ),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            db.collection(COLLECTION).document(channelKey)
                .set(data, SetOptions.merge())
                .await()
            Log.d(TAG, "reportCidLatency: $channelKey CID $cid → ${newAvg}ms")
        } catch (e: Exception) {
            Log.w(TAG, "reportCidLatency failed ($channelKey, $cid): ${e.message}")
        }
    }

    /**
     * Signale qu'un CID contient une chaîne donnée (SANS latence).
     * Rétro-compatible avec les appels existants.
     */
    suspend fun reportCid(channelKey: String, cid: String) = withContext(Dispatchers.IO) {
        try {
            val set = channelIndex.getOrPut(channelKey) {
                java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
            }
            if (!set.add(cid)) return@withContext

            val data = mapOf(
                "cids" to FieldValue.arrayUnion(cid),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            db.collection(COLLECTION).document(channelKey)
                .set(data, SetOptions.merge())
                .await()
            Log.d(TAG, "reportCid: $channelKey → CID $cid (total: ${set.size})")
        } catch (e: Exception) {
            Log.w(TAG, "reportCid failed ($channelKey, $cid): ${e.message}")
        }
    }

    /**
     * Signale PLUSIEURS CIDs pour une chaîne en un seul appel Firebase.
     */
    suspend fun reportCids(channelKey: String, cids: Collection<String>) = withContext(Dispatchers.IO) {
        if (cids.isEmpty()) return@withContext
        try {
            val set = channelIndex.getOrPut(channelKey) {
                java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
            }
            val newCids = cids.filter { set.add(it) }
            if (newCids.isEmpty()) return@withContext

            val data = mapOf(
                "cids" to FieldValue.arrayUnion(*newCids.toTypedArray()),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            db.collection(COLLECTION).document(channelKey)
                .set(data, SetOptions.merge())
                .await()
            Log.d(TAG, "reportCids: $channelKey → ${newCids.size} new CIDs (total: ${set.size})")
        } catch (e: Exception) {
            Log.w(TAG, "reportCids failed ($channelKey, ${cids.size} CIDs): ${e.message}")
        }
    }

    /** Vide le cache mémoire (pas Firestore). Appelé si changement de langue/provider. */
    fun clearCache() {
        channelIndex.clear()
        cidLatencyMap.clear()
        cidLastSeenMap.clear()
        lastReportTime.clear()
        streamUrlMap.clear()
        Log.d(TAG, "cache cleared")
    }
}
