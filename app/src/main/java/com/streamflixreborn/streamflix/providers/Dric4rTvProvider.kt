package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Dric4rTvProvider — playlist Dric4rTV (alternative à 3TVBOX V2).
 *
 * 2026-06-08 (user "appareil non compatible avec l'API complexe de 3TVBOX V2,
 *   essayer Dric4rTV plus simple") :
 *
 *   3TVBOX V2 (= notre BoxXtemus) utilise un pipeline JS html.bet anti-bot
 *   qui exige geo.6play.fr + mangaraiku + u301 + SFR CDN signature. Sur
 *   Chromecast à Tahiti, WebView Android natif n'arrive pas à reproduire
 *   ce pipeline → la plupart des chaînes Canal+/Sports ne marchent pas.
 *
 *   Dric4rTV est l'alternative officielle (Big Up à Dric4rt + Jeremy de
 *   3box-tv.tumblr.com) : URLs directes (pas d'orchestre JS), parseable
 *   simplement, fonctionne sur la plupart des appareils.
 *
 *   Ce provider expose les catégories LIVE (channels TV en direct) :
 *     - Ligue1+ (Ligue 1+ 1 à 10)
 *     - US Sports & NBA
 *     - Horse (chaînes équestres)
 *     - Nature & Découverte (Ushuaia TV, Nat Geo, …)
 *     - Live & DJ set (DJ live streams)
 *     - Muzik (MTV, MCM Top, Trace TV, …)
 *     - R4di0 (radios)
 *     - Locales TV (chaînes locales FR)
 *
 *   Skip Canal+/Sports rebrand.ly du 1.json car ils pointent vers les mêmes
 *   Google Sheets TSV que BoxXtemus utilise déjà (= même pipeline cassé).
 *
 *   Pas un Provider standalone : intégré au LiveTvHubProvider comme sections
 *   "Dric4rTV - <cat>" avec IDs préfixés `livehub::dric4rtv::`.
 */
object Dric4rTvProvider {

    private const val TAG = "Dric4rTvProvider"
    private const val PLAYLIST_URL = "http://dric4rt.free.fr/1.json"
    private const val CACHE_DURATION_MS = 30 * 60 * 1000L  // 30 min

    // Catégories LIVE à fetcher (URLs JSON directes free.fr).
    // On EXCLUT Canal+/Sports rebrand.ly (= mêmes Google Sheets TSV que
    // BoxXtemus, pas d'amélioration). On EXCLUT aussi Films/Series/
    // Animations (format VOD nested différent — à faire plus tard si besoin).
    private val LIVE_CATEGORIES = setOf(
        "Ligue1+",
        "US Sports & NBA",
        "Horse",
        "Nature & Découverte",
        "Live & DJ set",
        "Muzik",
        "R4di0",
        "Locales TV",
    )

    // ───────── Models ─────────

    data class DricStation(
        val name: String,
        val url: String,
        val image: String,
        val info: String,
        val referer: String,
        val userAgent: String,
        val headers: Map<String, String>,
    )

    /** Une chaîne = N stations (sources alternatives — SD/HD/FHD/etc.) groupées par nom de base. */
    data class DricChannel(
        val id: String,       // "livehub::dric4rtv::<catSlug>::<channelSlug>"
        val name: String,
        val image: String,
        val category: String,
        val stations: List<DricStation>,
    )

    // ───────── Registry + cache ─────────

    private val channelRegistry = mutableListOf<DricChannel>()
    private val registryLock = Any()
    private val registryMutex = Mutex()
    @Volatile private var loaded = false
    @Volatile private var lastLoad = 0L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ───────── Public API ─────────

    suspend fun getHome(): List<Category> = withContext(Dispatchers.IO) {
        ensureLoaded()
        val sections = mutableListOf<Category>()
        val byCat = synchronized(registryLock) { channelRegistry.groupBy { it.category } }
        // Conserve l'ordre déclaré dans LIVE_CATEGORIES
        for (cat in LIVE_CATEGORIES) {
            val channels = byCat[cat] ?: continue
            val tvShows = channels.map { channelToTvShow(it) }
            if (tvShows.isNotEmpty()) {
                sections.add(Category(name = cat, list = tvShows))
            }
        }
        sections
    }

    suspend fun getTvShow(id: String): TvShow {
        ensureLoaded()
        val ch = synchronized(registryLock) {
            channelRegistry.firstOrNull { it.id == id }
        }
        if (ch == null) {
            return TvShow(id = id, title = "Chaîne inconnue")
        }
        return channelToTvShow(ch)
    }

    suspend fun getServers(id: String, videoType: Video.Type?): List<Video.Server> {
        ensureLoaded()
        val ch = synchronized(registryLock) {
            channelRegistry.firstOrNull { it.id == id }
        } ?: return emptyList()
        return ch.stations.mapIndexed { i, st ->
            Video.Server(
                id = "dric-st::${ch.id}::$i",
                name = if (st.name.isNotBlank()) "${ch.name} — ${st.name}" else ch.name,
                src = st.url,
            )
        }
    }

    suspend fun getVideo(server: Video.Server): Video {
        val id = server.id
        // ID format : "dric-st::<channelId>::<stationIdx>"
        if (!id.startsWith("dric-st::")) {
            return Video(source = server.src, headers = emptyMap())
        }
        val rest = id.removePrefix("dric-st::")
        // channelId contient "::" (livehub::dric4rtv::...), donc splitter
        // par "::" et reconstruire le channelId à partir des N-1 premiers.
        val parts = rest.split("::")
        if (parts.size < 2) {
            return Video(source = server.src, headers = emptyMap())
        }
        val stIdx = parts.last().toIntOrNull() ?: return Video(source = server.src, headers = emptyMap())
        val channelId = parts.dropLast(1).joinToString("::")

        ensureLoaded()
        val ch = synchronized(registryLock) {
            channelRegistry.firstOrNull { it.id == channelId }
        } ?: return Video(source = server.src, headers = emptyMap())
        val st = ch.stations.getOrNull(stIdx) ?: return Video(source = server.src, headers = emptyMap())

        val headers = mutableMapOf<String, String>()
        // headers du JSON Dric4rTV en priorité (case-insensitive)
        for ((k, v) in st.headers) {
            if (v.isBlank()) continue
            when {
                k.equals("User-Agent", ignoreCase = true) -> headers["User-Agent"] = v
                k.equals("Referer", ignoreCase = true) -> headers["Referer"] = v
                k.equals("Origin", ignoreCase = true) -> headers["Origin"] = v
                else -> headers[k] = v
            }
        }
        // Fallback : userAgent / referer top-level si pas dans headers
        if (!headers.containsKey("User-Agent") && st.userAgent.isNotBlank()) {
            headers["User-Agent"] = st.userAgent
        }
        if (!headers.containsKey("Referer") && st.referer.isNotBlank()) {
            headers["Referer"] = st.referer
        }
        // 2026-06-08 (user "fix chaînes qui ne fonctionnent pas") : force le
        //   MIME type explicite selon l'extension URL. ExoPlayer sans MIME
        //   essaie de deviner via Content-Type du serveur — sur les radios
        //   icecast ou m3u8 sans extension dans le path, il se trompe et
        //   abandonne. Force explicitement (cf OTF qui set
        //   "application/vnd.apple.mpegurl" inconditionnellement).
        // 2026-06-08 : default = HLS pour les URLs IPTV style (port Xtream
        //   :8880, :25461, :8000, etc.) sans extension explicite. Beaucoup
        //   de chaînes Dric4rTV utilisent ces URLs où le path est juste
        //   "/USER/PASS/CHANNEL_ID" sans .m3u8. ExoPlayer sans MIME
        //   abandonne. Force HLS par défaut.
        val mimeType = when {
            st.url.contains(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
            st.url.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
            // Audio par extension finale ou path pattern
            st.url.endsWith(".mp3", ignoreCase = true) ||
                st.url.contains("/mp3", ignoreCase = true) -> "audio/mpeg"
            st.url.endsWith(".aac", ignoreCase = true) -> "audio/aac"
            st.url.endsWith(".ts", ignoreCase = true) -> "video/mp2t"
            // Radio FM streams (icecast typique)
            st.url.contains("icecast", ignoreCase = true) ||
                st.url.contains("radiofrance", ignoreCase = true) -> "audio/mpeg"
            // Default : HLS pour les URLs IPTV sans extension
            else -> "application/vnd.apple.mpegurl"
        }
        return Video(
            source = st.url,
            type = mimeType,
            headers = headers,
        )
    }

    /** Returns la liste des channelIds Dric4rTV. Sert au panel D-pad LEFT
     *  du fullscreen (livehub::dric4rtv::*). */
    suspend fun getAllChannelIds(): List<Pair<String, String>> {
        ensureLoaded()
        return synchronized(registryLock) {
            channelRegistry.map { it.id to it.name }
        }
    }

    // ───────── Implementation ─────────

    private fun channelToTvShow(ch: DricChannel): TvShow {
        return TvShow(
            id = ch.id,
            title = ch.name,
        ).apply {
            poster = ch.image
            banner = ch.image
            providerName = "Dric4rTV"
        }
    }

    private suspend fun ensureLoaded() {
        val now = System.currentTimeMillis()
        synchronized(registryLock) {
            if (loaded && (now - lastLoad) < CACHE_DURATION_MS) return
        }
        registryMutex.withLock {
            if (loaded && (System.currentTimeMillis() - lastLoad) < CACHE_DURATION_MS) return
            loadAllChannels()
        }
    }

    private suspend fun loadAllChannels() = withContext(Dispatchers.IO) {
        try {
            // 1) Récupère la playlist principale (1.json)
            val playlistJson = httpFetch(PLAYLIST_URL) ?: return@withContext
            val obj = JSONObject(playlistJson.trim())
            val groups = obj.optJSONArray("groups") ?: return@withContext

            // 2) Filter les catégories LIVE et lance les fetchs en parallèle
            coroutineScope {
                val tasks = mutableListOf<Deferred<List<DricChannel>>>()
                for (i in 0 until groups.length()) {
                    val grp = groups.optJSONObject(i) ?: continue
                    val catName = grp.optString("name", "").trim()
                    if (catName !in LIVE_CATEGORIES) continue
                    val catUrl = grp.optString("url", "").trim()
                    if (catUrl.isBlank()) continue
                    // Skip rebrand.ly (= duplicate de 3TVBOX V2)
                    if (catUrl.contains("rebrand.ly", ignoreCase = true)) {
                        Log.d(TAG, "Skip $catName (rebrand.ly = duplicate of 3TVBOX V2)")
                        continue
                    }

                    tasks.add(async { fetchCategoryChannels(catName, catUrl) })
                }
                val baseChannels = tasks.awaitAll().flatten()
                // 2026-06-13 (user "tu peux ajouter cette radio vidéo dans le
                //   bouquet France TV Muzik ?") : injection de chaînes custom
                //   après le fetch du JSON Dric4rTV. Permet d'ajouter une
                //   chaîne hardcodée dans n'importe quel bouquet sans toucher
                //   au JSON externe.
                // 2026-06-13 (user "tu l'as pas mis devant je la vois pas") :
                //   on met les custom EN TÊTE pour qu'elles apparaissent en
                //   1er dans leur bouquet (groupBy préserve l'ordre).
                val customChannels = buildCustomChannels()
                val allChannels = customChannels + baseChannels

                synchronized(registryLock) {
                    channelRegistry.clear()
                    channelRegistry.addAll(allChannels)
                    loaded = true
                    lastLoad = System.currentTimeMillis()
                }
                Log.d(TAG, "Loaded ${baseChannels.size} channels + ${customChannels.size} custom = ${allChannels.size} total across ${allChannels.map { it.category }.distinct().size} categories")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadAllChannels failed: ${t.message}")
        }
    }

    /** 2026-06-13 (user "tu peux ajouter cette radio vidéo dans le bouquet
     *  France TV Muzik ?") : chaînes custom hardcodées, injectées en plus
     *  des chaînes du JSON Dric4rTV.
     *  Format URL pour le pipeline : `coolstream://<channelId>` — sera
     *  intercepté par le pipeline IPTV (LiveTvHubProvider.getVideo) qui
     *  lancera l'extracteur coolstreaming approprié. */
    private fun buildCustomChannels(): List<DricChannel> {
        return listOf(
            DricChannel(
                id = "livehub::dric4rtv::muzik::90isgoodit",
                name = "90 Is Good (IT)",
                image = "https://www.coolstreaming.us/img/ch/image40300666727.jpg",
                category = "Muzik",
                stations = listOf(
                    DricStation(
                        name = "90 Is Good (IT)",
                        // 2026-06-13 : URL m3u8 directe Wowza (sniffée depuis
                        //   l'iframe coolstreaming.us/blog/player/consolle_flash19.php).
                        //   Pas de token, pas de Referer requis (testé via HTTP HEAD).
                        //   Si elle casse un jour, refaire le sniff sur la page
                        //   coolstreaming.us/channelnew/75846/90IsGood.html.
                        url = "https://64b16f23efbee.streamlock.net/isgoodforyou/isgoodforyou/playlist.m3u8",
                        image = "https://www.coolstreaming.us/img/ch/image40300666727.jpg",
                        info = "Chaîne italienne musique 90s (Pop/Dance/Rock/Hip-Hop) — IsGoodForYou × Fascino TV",
                        referer = "",
                        userAgent = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                        headers = emptyMap(),
                    ),
                ),
            ),
        )
    }

    /** Fetch une catégorie de Dric4rTV. Supporte 2 formats :
     *  - stations[]: direct (Ligue1+, Sports, Muzik, etc.) — chaque entrée
     *    est UNE source. On groupe par nom de base.
     *  - groups[].stations[]: nested (Series, Animations VOD) — chaque
     *    groupe = une chaîne. (NB: pas inclus dans LIVE_CATEGORIES pour
     *    l'instant — VOD à faire plus tard.)
     */
    private fun fetchCategoryChannels(catName: String, catUrl: String): List<DricChannel> {
        return try {
            val body = httpFetch(catUrl) ?: return emptyList()
            val obj = JSONObject(body.trim())

            val stationsArr = obj.optJSONArray("stations")
            if (stationsArr != null) {
                return parseStationsArray(catName, stationsArr)
            }

            val groupsArr = obj.optJSONArray("groups")
            if (groupsArr != null) {
                return parseGroupsArray(catName, groupsArr)
            }

            Log.w(TAG, "$catName : no stations[] nor groups[] in JSON")
            emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "fetchCategoryChannels($catName) failed: ${t.message}")
            emptyList()
        }
    }

    private fun parseStationsArray(catName: String, stationsArr: org.json.JSONArray): List<DricChannel> {
        // Groupe par nom de base (= sans suffix SD/HD/FHD/4K/ALT) pour
        // qu'une "Ligue 1+ SD" et "Ligue 1+ HD" deviennent UNE chaîne avec
        // 2 sources.
        val byBase = mutableMapOf<String, MutableList<DricStation>>()
        val orderedBaseNames = mutableListOf<String>()
        for (j in 0 until stationsArr.length()) {
            val stObj = stationsArr.optJSONObject(j) ?: continue
            val st = parseStation(stObj) ?: continue
            val baseName = baseChannelName(st.name)
            if (baseName !in byBase) {
                byBase[baseName] = mutableListOf()
                orderedBaseNames.add(baseName)
            }
            byBase[baseName]!!.add(st)
        }

        val channels = mutableListOf<DricChannel>()
        for (name in orderedBaseNames) {
            val stations = byBase[name] ?: continue
            if (stations.isEmpty()) continue  // toutes filtrées (dead hosts)
            val img = stations.firstOrNull { it.image.isNotBlank() }?.image.orEmpty()
            channels.add(DricChannel(
                id = "livehub::dric4rtv::${slugify(catName)}::${slugify(name)}",
                name = name,
                image = img,
                category = catName,
                stations = stations,
            ))
        }
        return channels
    }

    private fun parseGroupsArray(catName: String, groupsArr: org.json.JSONArray): List<DricChannel> {
        val channels = mutableListOf<DricChannel>()
        for (j in 0 until groupsArr.length()) {
            val grp = groupsArr.optJSONObject(j) ?: continue
            val grpName = grp.optString("name", "").trim().ifBlank { continue }
            val grpImage = grp.optString("image", "")
            val grpStations = grp.optJSONArray("stations") ?: continue
            val stations = mutableListOf<DricStation>()
            for (k in 0 until grpStations.length()) {
                val stObj = grpStations.optJSONObject(k) ?: continue
                val st = parseStation(stObj) ?: continue
                stations.add(st)
            }
            if (stations.isNotEmpty()) {
                channels.add(DricChannel(
                    id = "livehub::dric4rtv::${slugify(catName)}::${slugify(grpName)}",
                    name = grpName,
                    image = grpImage,
                    category = catName,
                    stations = stations,
                ))
            }
        }
        return channels
    }

    /**
     * 2026-06-08 (user "fix chaînes qui ne fonctionnent pas") : hôtes connus
     * comme MORTS ou qui requièrent une extraction WebView impossible en
     * arrière-plan (= même pipeline html.bet anti-bot que 3TVBOX V2). On
     * skip ces URLs au parsing → la chaîne sera affichée seulement si elle
     * a au moins UNE source viable. Si plus aucune source → on skip la
     * chaîne complète.
     */
    private val DEAD_OR_UNSUPPORTED_HOSTS = listOf(
        "runginfect.net",     // host mort (HTTP 000)
        "c3v9.short.gy",      // pipeline html.bet anti-bot cassé sur Chromecast
        "html.bet",           // idem
    )

    private fun isDeadOrUnsupported(url: String): Boolean {
        return DEAD_OR_UNSUPPORTED_HOSTS.any { url.contains(it, ignoreCase = true) }
    }

    private fun parseStation(stObj: JSONObject): DricStation? {
        val url = stObj.optString("url", "").trim()
        if (url.isBlank()) return null
        if (isDeadOrUnsupported(url)) return null

        val headers = mutableMapOf<String, String>()
        val hdrObj = stObj.optJSONObject("headers")
        if (hdrObj != null) {
            val keys = hdrObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = hdrObj.optString(k, "")
                if (v.isNotBlank()) headers[k] = v
            }
        }

        return DricStation(
            name = stObj.optString("name", "").trim().ifBlank { "Source" },
            url = url,
            image = stObj.optString("image", ""),
            info = stObj.optString("info", ""),
            referer = stObj.optString("referer", ""),
            userAgent = stObj.optString("userAgent", ""),
            headers = headers,
        )
    }

    /** Strip qualité (SD/HD/FHD/4K/ALT) du nom pour grouper les sources d'UNE chaîne. */
    private fun baseChannelName(name: String): String {
        var n = name.trim()
        // suffixes à retirer
        n = n.replace(Regex("\\s+(FULL\\s*HD|FHD|UHD|4K|HD|SD|ALT|HQ|HEVC)$", RegexOption.IGNORE_CASE), "")
        return n.trim()
    }

    private fun slugify(s: String): String {
        return s.lowercase()
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâä]"), "a")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace(Regex("[ûüù]"), "u")
            .replace(Regex("ç"), "c")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "x" }
    }

    private fun httpFetch(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                .build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string()
            val ok = resp.isSuccessful
            resp.close()
            if (ok) body else {
                Log.w(TAG, "httpFetch($url) : HTTP ${resp.code}")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "httpFetch($url) failed: ${t.message}")
            null
        }
    }
}
