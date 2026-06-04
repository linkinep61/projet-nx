package com.streamflixreborn.streamflix.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * Index LOCAL persistant des chaînes IPTV — accélère la résolution des
 * chaînes (TF1, France2, Canal+, etc.) au fil de l'utilisation.
 *
 * AUCUNE dépendance externe : pas de Firebase, pas de Cloudflare, pas de
 * réseau. Tout est en SharedPreferences sur l'appareil de l'utilisateur.
 *
 * Fonctionnement :
 *   1. Au boot : loadLocalCache() lit l'index sauvegardé (~5ms)
 *   2. Chaque fois qu'on découvre une chaîne sur un CID en X ms :
 *      → reportCidLatency(channelKey, cid, latencyMs)
 *      → la mesure est sauvée en mémoire + flushée en SharedPrefs (throttle 30s)
 *   3. À la prochaine lecture de la chaîne :
 *      → getKnownCids(channelKey) retourne les CIDs TRIÉS par latence
 *      → le provider commence par le plus rapide → play en ~5s au lieu de 30-60s
 *   4. Stream URLs pré-résolues : si on a déjà extrait le m3u8 fonctionnel pour
 *      un CID, on le garde → play instantané (skip MAC portal lookup)
 *   5. Cleanup auto : CIDs pas vus depuis 72h retirés (probablement morts)
 *
 * L'index s'enrichit AUTOMATIQUEMENT au fil de l'utilisation. Le 1er lancement
 * est lent (scan brute), les suivants utilisent l'index → vitesse croissante.
 */
object LocalIptvChannelIndex {

    private const val TAG = "LocalIptvIndex"
    private const val PREF_NAME = "local_iptv_channel_index"
    private const val KEY_DATA = "data"
    private const val KEY_DEAD = "dead_urls"
    private const val KEY_TIMESTAMP = "ts"

    /** Seuil de fraîcheur : un CID pas vu depuis 72h est considéré mort. */
    private const val STALE_THRESHOLD_MS = 72 * 3600 * 1000L

    /** Throttle flush SharedPrefs : ne pas écrire à chaque report (max 1/30s). */
    private const val FLUSH_THROTTLE_MS = 30_000L

    /** Latence par défaut (CIDs sans mesure → triés en dernier). */
    private const val DEFAULT_LATENCY_MS = 5000L

    /** TTL d'un URL marqué mort (HTTP 456/4xx) : 24h.
     *  Au-delà, on autorise un nouvel essai (le rate-limit a pu se débloquer). */
    private const val DEAD_URL_TTL_MS = 24L * 3600L * 1000L

    // ── Index principal : channelKey → Set<CID> ──
    private val channelIndex = ConcurrentHashMap<String, MutableSet<String>>()

    // ── Latences : channelKey → { cid → moyenne ms } ──
    private val cidLatencyMap = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    // ── Dernière vue : channelKey → { cid → epochMs } ──
    private val cidLastSeenMap = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    // ── Stream URLs pré-résolues : channelKey → { cid → m3u8Url } ──
    private val streamUrlMap = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    // ── Banlist URL → epochMs d'expiration. Persistante 24h. ──
    private val deadUrlsMap = ConcurrentHashMap<String, Long>()

    @Volatile private var lastFlushTs: Long = 0L
    @Volatile private var dirty: Boolean = false
    @Volatile private var loaded: Boolean = false

    private fun prefs(): android.content.SharedPreferences =
        com.streamflixreborn.streamflix.StreamFlixApp.instance
            .getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)

    /** Charge l'index : (1) SharedPrefs local user, (2) merge seed bundled
     *  (chaînes communautaires importées de l'ancienne Firebase).
     *  Le local user a la priorité sur le seed (latences plus à jour). */
    @Synchronized
    fun loadLocalCache(): Boolean {
        if (loaded) return channelIndex.isNotEmpty()
        loaded = true

        // ── 1. Local user cache (SharedPrefs) ──
        val userLoaded = try {
            val json = prefs().getString(KEY_DATA, null)
            if (json != null) {
                parseAndMerge(org.json.JSONObject(json), source = "user")
                true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "loadLocalCache user failed: ${e.message}")
            false
        }

        // ── 1bis. Banlist URLs mortes (TTL 24h, on purge à la volée) ──
        try {
            val deadJson = prefs().getString(KEY_DEAD, null)
            if (deadJson != null) {
                val obj = org.json.JSONObject(deadJson)
                val now = System.currentTimeMillis()
                var loaded = 0
                var purged = 0
                for (url in obj.keys()) {
                    val expireAt = obj.getLong(url)
                    if (expireAt > now) {
                        deadUrlsMap[url] = expireAt
                        loaded++
                    } else {
                        purged++
                    }
                }
                Log.d(TAG, "  banlist: +$loaded URLs mortes (TTL valide), -$purged expirées")
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadLocalCache deadlist failed: ${e.message}")
        }

        // ── 2. Seed bundled (gros catalogue communautaire) ──
        try {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            val resId = ctx.resources.getIdentifier("iptv_channel_seed", "raw", ctx.packageName)
            if (resId != 0) {
                val seedJson = ctx.resources.openRawResource(resId)
                    .bufferedReader().use { it.readText() }
                parseAndMerge(org.json.JSONObject(seedJson), source = "seed")
            } else {
                Log.d(TAG, "Pas de seed bundle (iptv_channel_seed.json absent)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadLocalCache seed failed: ${e.message}")
        }

        Log.d(TAG, "loadLocalCache TOTAL: ${channelIndex.size} chaînes, " +
                "${channelIndex.values.sumOf { it.size }} CIDs (user cache=$userLoaded)")
        return channelIndex.isNotEmpty()
    }

    /** Parse un JSON {channelKey: {cids,lat,ls,urls}} et MERGE dans les maps.
     *  Les entrées existantes (priorité user) ne sont pas écrasées sauf si pas définies. */
    private fun parseAndMerge(root: org.json.JSONObject, source: String) {
        var newChannels = 0
        var newCids = 0
        for (key in root.keys()) {
            val chObj = root.getJSONObject(key)
            val cidsArr = chObj.optJSONArray("cids") ?: continue
            val isNewChannel = !channelIndex.containsKey(key)
            val set = channelIndex.getOrPut(key) {
                java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
            }
            for (i in 0 until cidsArr.length()) {
                if (set.add(cidsArr.getString(i))) newCids++
            }
            if (isNewChannel && set.isNotEmpty()) newChannels++

            if (chObj.has("lat")) {
                val latObj = chObj.getJSONObject("lat")
                val map = cidLatencyMap.getOrPut(key) { ConcurrentHashMap() }
                for (cid in latObj.keys()) {
                    // user data prioritaire : on n'écrase pas si déjà présent
                    if (source == "user" || !map.containsKey(cid)) {
                        map[cid] = latObj.getLong(cid)
                    }
                }
            }
            if (chObj.has("ls")) {
                val lsObj = chObj.getJSONObject("ls")
                val map = cidLastSeenMap.getOrPut(key) { ConcurrentHashMap() }
                for (cid in lsObj.keys()) {
                    if (source == "user" || !map.containsKey(cid)) {
                        map[cid] = lsObj.getLong(cid)
                    }
                }
            }
            if (chObj.has("urls")) {
                val urlObj = chObj.getJSONObject("urls")
                val map = streamUrlMap.getOrPut(key) { ConcurrentHashMap() }
                for (cid in urlObj.keys()) {
                    if (source == "user" || !map.containsKey(cid)) {
                        map[cid] = urlObj.getString(cid)
                    }
                }
            }
        }
        Log.d(TAG, "  parseAndMerge[$source]: +$newChannels chaînes, +$newCids CIDs")
    }

    /** Flush en SharedPrefs (throttle 30s sauf si force=true). */
    @Synchronized
    fun saveLocalCache(force: Boolean = false) {
        if (!dirty && !force) return
        val now = System.currentTimeMillis()
        if (!force && (now - lastFlushTs) < FLUSH_THROTTLE_MS) return
        try {
            val root = org.json.JSONObject()
            for ((key, cids) in channelIndex) {
                val chObj = org.json.JSONObject()
                chObj.put("cids", org.json.JSONArray(cids.toList()))
                cidLatencyMap[key]?.takeIf { it.isNotEmpty() }?.let { m ->
                    val o = org.json.JSONObject(); for ((c, v) in m) o.put(c, v); chObj.put("lat", o)
                }
                cidLastSeenMap[key]?.takeIf { it.isNotEmpty() }?.let { m ->
                    val o = org.json.JSONObject(); for ((c, v) in m) o.put(c, v); chObj.put("ls", o)
                }
                streamUrlMap[key]?.takeIf { it.isNotEmpty() }?.let { m ->
                    val o = org.json.JSONObject(); for ((c, v) in m) o.put(c, v); chObj.put("urls", o)
                }
                root.put(key, chObj)
            }
            // Banlist URLs mortes (purgée des TTL expirés à la volée).
            val deadObj = org.json.JSONObject()
            val nowTs = System.currentTimeMillis()
            val expired = mutableListOf<String>()
            for ((url, exp) in deadUrlsMap) {
                if (exp > nowTs) deadObj.put(url, exp) else expired.add(url)
            }
            expired.forEach { deadUrlsMap.remove(it) }

            prefs().edit()
                .putString(KEY_DATA, root.toString())
                .putString(KEY_DEAD, deadObj.toString())
                .putLong(KEY_TIMESTAMP, now)
                .apply()
            lastFlushTs = now
            dirty = false
            Log.d(TAG, "saveLocalCache: ${channelIndex.size} chaînes flushed")
        } catch (e: Exception) {
            Log.w(TAG, "saveLocalCache failed: ${e.message}")
        }
    }

    /**
     * Retourne les CIDs connus pour une chaîne, TRIÉS par latence (le + rapide d'abord).
     * Filtre les CIDs morts (> 72h sans signe de vie).
     * Retourne emptyList() si rien connu → le provider fait son scan classique.
     */
    fun getKnownCids(channelKey: String): List<String> {
        val cids = channelIndex[channelKey] ?: return emptyList()
        val latencies = cidLatencyMap[channelKey]
        val lastSeens = cidLastSeenMap[channelKey]
        val now = System.currentTimeMillis()

        val alive = if (lastSeens != null && lastSeens.isNotEmpty()) {
            cids.filter { cid ->
                val ls = lastSeens[cid]
                ls == null || (now - ls) < STALE_THRESHOLD_MS
            }
        } else cids.toList()

        return if (latencies != null && latencies.isNotEmpty()) {
            alive.sortedBy { latencies[it] ?: DEFAULT_LATENCY_MS }
        } else alive
    }

    /**
     * Retourne les URLs pré-résolues TRIÉES :
     *   1. Alive (pas dans la banlist) → sort by latence ASC (rapides d'abord)
     *   2. Dead (banlist active, < 24h) → en bas, sort by latence ASC
     *   3. Dead expirés (≥ 24h) → automatiquement re-promus en "alive"
     *
     * Garantit que les CIDs ratés récemment ne se replacent JAMAIS au top.
     */
    fun getCachedStreamUrls(channelKey: String): List<Pair<String, String>> {
        val urls = streamUrlMap[channelKey] ?: return emptyList()
        if (urls.isEmpty()) return emptyList()
        val latencies = cidLatencyMap[channelKey]
        val now = System.currentTimeMillis()

        val alive = mutableListOf<Triple<String, String, Long>>()  // cid, url, latency
        val dead = mutableListOf<Triple<String, String, Long>>()

        for ((cid, url) in urls) {
            val latency = latencies?.get(cid) ?: DEFAULT_LATENCY_MS
            val expireAt = deadUrlsMap[url]
            if (expireAt != null && expireAt > now) {
                dead.add(Triple(cid, url, latency))
            } else {
                // Si TTL expiré, on retire de la banlist (resurrection)
                if (expireAt != null) deadUrlsMap.remove(url)
                alive.add(Triple(cid, url, latency))
            }
        }

        return (alive.sortedBy { it.third } + dead.sortedBy { it.third })
            .map { it.first to it.second }
    }

    // ── Probe HEAD parallèle : évite de re-prober pendant qu'un probe est actif ──
    private val probeInflight = ConcurrentHashMap<String, Long>()  // channelKey → epochMs début
    private const val PROBE_REPROBE_THROTTLE_MS = 10 * 60 * 1000L  // 10 min
    private const val PROBE_TIMEOUT_MS = 2500L
    private const val PROBE_MAX_PARALLEL = 8
    private const val PROBE_MAX_URLS_PER_CHANNEL = 30

    /**
     * 2026-06-03 — CI MULTI-SOURCE : sonde HEAD parallèle de toutes les URLs cachées
     * pour la chaîne. Marque DEAD celles qui ne répondent pas 200/206 dans 2.5s.
     *
     * Lancé en BACKGROUND (n'impacte pas la lecture immédiate). Sa valeur arrive
     * à la PROCHAINE visite de la chaîne : la banlist est à jour, les URLs alive
     * sont triées par latence, le 1er essai marche.
     *
     * Throttle : re-probe max toutes les 10 min par chaîne (évite spam).
     */
    fun probeFastTracks(channelKey: String) {
        val now = System.currentTimeMillis()
        val last = probeInflight[channelKey]
        if (last != null && (now - last) < PROBE_REPROBE_THROTTLE_MS) {
            Log.d(TAG, "probeFastTracks[$channelKey] throttled (last=${(now - last) / 1000}s ago)")
            return
        }
        val urls = streamUrlMap[channelKey]?.toMap() ?: return
        if (urls.isEmpty()) return
        probeInflight[channelKey] = now

        // Lance en background sur Dispatchers.IO via une nouvelle coroutine scope
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Ne pas tester les URLs DÉJÀ marquées dead (TTL valide)
                val toProbe = urls.entries
                    .filter { !isUrlDead(it.value) }
                    .take(PROBE_MAX_URLS_PER_CHANNEL)
                Log.d(TAG, "probeFastTracks[$channelKey] : ${toProbe.size}/${urls.size} URLs à sonder (HEAD ${PROBE_TIMEOUT_MS}ms)")
                val sem = Semaphore(PROBE_MAX_PARALLEL)
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .callTimeout(PROBE_TIMEOUT_MS + 500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .followRedirects(true)
                    .build()
                var alive = 0
                var dead = 0
                val jobs = toProbe.map { entry ->
                    async {
                        sem.acquire()
                        try {
                            val req = okhttp3.Request.Builder()
                                .url(entry.value)
                                .head()  // HEAD only, pas de body download
                                .header("User-Agent", "Mozilla/5.0 IPTVProbe/1.0")
                                .build()
                            val code = try {
                                client.newCall(req).execute().use { it.code }
                            } catch (e: Exception) { -1 }
                            // 200/206/301/302 → OK ; 405 = Method Not Allowed (server refuse HEAD, on assume alive)
                            // 403/404/410/456/5xx → mort
                            if (code in 200..399 || code == 405) {
                                alive++
                            } else {
                                markUrlDead(entry.value)
                                dead++
                            }
                        } finally { sem.release() }
                    }
                }
                jobs.awaitAll()
                Log.d(TAG, "probeFastTracks[$channelKey] DONE : $alive vivants, $dead morts (banlist=${deadUrlsMap.size})")
                // 2026-06-03 : NE PAS forcer le save (lourd: ~1MB JSON synchrone).
                //   markUrlDead() à l'intérieur a déjà set dirty=true → le prochain
                //   save throttlé (30s) flushera. Évite l'ANR sur input pendant write.
            } catch (e: Exception) {
                Log.w(TAG, "probeFastTracks[$channelKey] failed: ${e.message}")
            } finally {
                probeInflight.remove(channelKey)
            }
        }
    }

    /** Marque une URL comme morte pour [DEAD_URL_TTL_MS] (par défaut 24h).
     *  PAS de saveLocalCache ici : 30 appels en parallèle (du probe) sérialiseraient
     *  sur @Synchronized → bloque le main thread quand il lit getCachedStreamUrls
     *  → ANR sur input. Le throttle naturel (autre reportXxx) flushera. */
    fun markUrlDead(url: String, ttlMs: Long = DEAD_URL_TTL_MS) {
        if (url.isBlank()) return
        deadUrlsMap[url] = System.currentTimeMillis() + ttlMs
        dirty = true
    }

    /** True si l'URL est dans la banlist active (TTL non expiré). */
    fun isUrlDead(url: String): Boolean {
        val exp = deadUrlsMap[url] ?: return false
        if (exp <= System.currentTimeMillis()) {
            deadUrlsMap.remove(url)
            return false
        }
        return true
    }

    /** Nombre d'URLs dans la banlist (debug). */
    fun deadUrlCount(): Int = deadUrlsMap.size

    /** Signale qu'un CID contient une chaîne + sa latence de connexion. */
    fun reportCidLatency(channelKey: String, cid: String, latencyMs: Long) {
        val set = channelIndex.getOrPut(channelKey) {
            java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        }
        set.add(cid)

        val latMap = cidLatencyMap.getOrPut(channelKey) { ConcurrentHashMap() }
        val prev = latMap[cid]
        latMap[cid] = if (prev != null && prev > 0) {
            (prev * 0.3 + latencyMs * 0.7).toLong()  // moyenne pondérée
        } else latencyMs

        val lsMap = cidLastSeenMap.getOrPut(channelKey) { ConcurrentHashMap() }
        lsMap[cid] = System.currentTimeMillis()

        dirty = true
        saveLocalCache()  // throttle interne, écrit max 1/30s
    }

    /** Signale juste qu'un CID a une chaîne (sans latence). */
    fun reportCid(channelKey: String, cid: String) {
        val set = channelIndex.getOrPut(channelKey) {
            java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        }
        if (set.add(cid)) {
            cidLastSeenMap.getOrPut(channelKey) { ConcurrentHashMap() }[cid] = System.currentTimeMillis()
            dirty = true
            saveLocalCache()
        }
    }

    /** Batch : signale plusieurs CIDs pour une chaîne. */
    fun reportCids(channelKey: String, cids: Collection<String>) {
        if (cids.isEmpty()) return
        val set = channelIndex.getOrPut(channelKey) {
            java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        }
        val now = System.currentTimeMillis()
        val lsMap = cidLastSeenMap.getOrPut(channelKey) { ConcurrentHashMap() }
        var added = false
        for (cid in cids) {
            if (set.add(cid)) added = true
            lsMap[cid] = now
        }
        if (added) {
            dirty = true
            saveLocalCache()
        }
    }

    /** Mémorise une URL de stream fonctionnelle pour un CID. */
    fun reportStreamUrl(channelKey: String, cid: String, url: String) {
        val m = streamUrlMap.getOrPut(channelKey) { ConcurrentHashMap() }
        m[cid] = url
        dirty = true
        saveLocalCache()
    }

    /** Invalide une URL de stream (HEAD a échoué). */
    fun invalidateStreamUrl(channelKey: String, cid: String) {
        if (streamUrlMap[channelKey]?.remove(cid) != null) {
            dirty = true
            saveLocalCache()
        }
    }

    /**
     * 2026-06-03 : invalidate par URL (sert quand on a la URL qui a échoué mais
     * pas le CID directement — ex. via player error sur un ola_fasttrack::).
     * Scanne tous les CIDs de la chaîne, retire ceux dont l'URL match.
     */
    fun invalidateStreamUrlByUrl(channelKey: String, url: String) {
        if (url.isBlank()) return
        val urls = streamUrlMap[channelKey] ?: return
        val toRemove = urls.entries.filter { it.value == url }.map { it.key }
        if (toRemove.isNotEmpty()) {
            toRemove.forEach { urls.remove(it) }
            dirty = true
            saveLocalCache()
        }
    }

    /** Latence connue d'un CID, null si jamais mesurée. */
    fun getLatency(channelKey: String, cid: String): Long? = cidLatencyMap[channelKey]?.get(cid)

    /** Nombre de chaînes indexées. */
    fun indexSize(): Int = channelIndex.size

    /** Nombre total de CIDs indexés (toutes chaînes confondues). */
    fun totalCids(): Int = channelIndex.values.sumOf { it.size }

    /** Vide tout (ne touche pas SharedPrefs sauf force=true). */
    @Synchronized
    fun clearCache(persistOnDisk: Boolean = false) {
        channelIndex.clear()
        cidLatencyMap.clear()
        cidLastSeenMap.clear()
        streamUrlMap.clear()
        deadUrlsMap.clear()
        if (persistOnDisk) {
            prefs().edit().remove(KEY_DATA).remove(KEY_DEAD).remove(KEY_TIMESTAMP).apply()
            lastFlushTs = 0L
            dirty = false
        }
    }
}
