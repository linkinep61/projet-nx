package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-06-08 (user "on a oublié ces 2 playlists, tu les ajoutes en bas du
 * TV hub") : provider léger pour la playlist WORLD Live TV (Wiseplay JSON
 * style 3BoxTV mais sans le pipeline anti-bot c3v9 — toutes les URLs sont
 * des .m3u/.m3u8 directs).
 *
 * Source : https://box.xtemus.com/?playlist=y274y486q2x2841586r2
 * (résolue depuis https://cutt.ly/worldlivetv)
 *
 * 30 catégories : Github IPTV, SamsungTVPlus, XUMO Play, RedBox TV, Tubi,
 * DistroTV, Roku Channel, Plex, PlutoTV, Fire TV, Cineverse, KlowdTV, etc.
 *
 * Chaque catégorie pointe vers une .m3u/.m3u8 standard. On fetch chaque
 * playlist, on parse les `#EXTINF` lines pour extraire nom/logo/group-title
 * et l'URL stream juste après.
 *
 * Intégré au TV Hub via LiveTvHubProvider.getHome() en bas.
 */
object WorldLiveTvProvider : Provider, IptvProvider {

    override val name = "World Live TV"
    override val baseUrl = "https://box.xtemus.com"
    override val logo = "https://i.goopics.net/1h3apa.png"
    override val language = "fr"

    private const val TAG = "WorldLiveTv"
    // 2026-06-11 (user "mettre 3boxTv en priorité pour les nouveaux users") :
    //   défaut = 3boxTv v2 (mainteneur 3box-tv.tumblr.com, le plus actif et
    //   compatible avec notre GenericStreamResolver asm168). L'ancien URL
    //   y274y486q2x2841586r2 reste accessible via le picker source si l'user
    //   l'avait déjà ajouté.
    private const val DEFAULT_PLAYLIST_URL = "https://box.xtemus.com/?playlist=u256y494u21596x2"
    private val PLAYLIST_URL: String
        get() = try {
            WorldLiveSourcesStore.getActiveUrl(
                com.streamflixreborn.streamflix.StreamFlixApp.instance
            )
        } catch (_: Throwable) { DEFAULT_PLAYLIST_URL }
    // 2026-06-15 v2 (user "pour pas avoir de coupures faudrait qu'on réduise
    //   alors") : cache 10 → 5 min combiné avec workflow CI 30 min →
    //   delay max entre fix CI et app = 35 min (= quasi temps réel pour
    //   un service M3U). Coût bande passante négligeable (~180KB / 5 min).
    private const val CACHE_TTL_MS = 5 * 60 * 1000L  // 5 min

    private val _additionalServersFlow = MutableSharedFlow<Video.Server>()
    override val additionalServersFlow: SharedFlow<Video.Server> = _additionalServersFlow.asSharedFlow()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // 2026-06-14 : client séparé avec timeouts plus longs pour les panels
    //   Xtream Codes (player_api.php peut retourner 5-50 MB selon le serveur).
    //   N'IMPACTE PAS le client M3U générique au-dessus.
    private val xtreamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    // ───────── Models ─────────

    data class WlGroup(
        val name: String,
        val image: String?,
        val url: String,
    )

    data class WlChannel(
        val id: String,           // "wltv::<groupSlug>::<chSlug>"
        val name: String,
        val logo: String?,
        val groupName: String,
        val streamUrl: String,
        val userAgent: String?,
        val referer: String?,
        val tvgLanguage: String?,  // capturé depuis tvg-language="xx" du M3U
        val isFolder: Boolean = false,  // 2026-06-10 (Wiseplay-style folders)
        val folderPath: String? = null,  // si isFolder, chemin pour lookup folderContents
        // 2026-06-15 (user "ajouter un 2e serveur en backup pour booster comme
        //   font les autres liens") : URLs additionnelles servant de fallback
        //   auto. Le parser M3U les remplit quand il rencontre plusieurs EXTINF
        //   avec le même (tvg-id, group-title). Chaque triple = (URL, UA, Referer).
        //   getServers expose primary + chacune en Video.Server, le mini lecteur
        //   fait tryNextServer() automatiquement si le primary fail.
        val extraServers: List<Triple<String, String?, String?>> = emptyList(),
    )

    /** 2026-06-10 (user "comme Wiseplay : dossiers explorables, pas tout
     *  dans 1 paquet de 800 items") : storage en mémoire des sous-bouquets
     *  pour navigation hiérarchique. Key = folderPath unique, value = liste
     *  des items (chaînes ou sous-dossiers). */
    val folderContents = java.util.concurrent.ConcurrentHashMap<String, List<WlChannel>>()

    // ───────── Cache ─────────

    @Volatile private var channelRegistry: List<WlChannel> = emptyList()
    @Volatile private var groupsCache: List<WlGroup> = emptyList()
    @Volatile private var lastLoad = 0L
    @Volatile private var lastLoadedUrl: String? = null

    /** 2026-06-10 (user "le picker catégorie met longtemps à s'ouvrir") :
     *  retourne instantanément les noms top-level depuis le cache (mémoire
     *  ou disque) — SI la source active n'a pas changé entre-temps.
     *
     *  2026-06-10 fix (user "playlist sur téléphone pas bonne") : vérifie
     *  que `lastLoadedUrl == PLAYLIST_URL` avant de servir le cache mémoire.
     *  Sans ça, le picker affichait les catégories de l'ancienne source
     *  après un changement de source. */
    fun getCategoryNamesFast(): List<String> {
        val currentUrl = PLAYLIST_URL
        // 2026-06-14 (user "les catégories ne fonctionnent pas — le picker
        //   n'affiche que 'Toutes les chaînes' + nom de la playlist au lieu
        //   des 32 catégories du M3U") : on retourne les group-title=
        //   distincts du M3U (= les VRAIES sections que l'user voit dans le
        //   home) au lieu des group top-level de la playlist JSON. Pour
        //   l'user actuel qui a UNE playlist M3U "primtv" sur Codeberg :
        //   groupsCache.map = ["primtv"] = inutile.
        //   channelRegistry.map { it.groupName }.distinct() = 32 catégories
        //   (Animation, Auto, Comedy, Culture, Horse, Locales TV, Muzik,
        //   Nature & Decouverte, etc.) — c'est ce que l'user attend.
        //   Préserve l'ordre d'apparition dans le M3U via .distinct() sur
        //   une liste ordonnée.
        if (channelRegistry.isNotEmpty() && lastLoadedUrl == currentUrl) {
            return channelRegistry.map { it.groupName }.distinct()
        }
        if (restoreFolderContentsFromDisk() && lastLoadedUrl == currentUrl) {
            if (channelRegistry.isNotEmpty()) {
                return channelRegistry.map { it.groupName }.distinct()
            }
            // Fallback : si registry pas restauré mais groupsCache oui,
            // on tombe sur les noms top-level (= comportement historique).
            return groupsCache.map { it.name }
        }
        return emptyList()
    }

    /** 2026-06-10 (user "le changement des sources ne fonctionne pas") :
     *  invalide le cache mémoire ET disque quand l'URL active change. */
    fun invalidateCache() {
        channelRegistry = emptyList()
        folderContents.clear()
        groupsCache = emptyList()  // 2026-06-10 fix : oublié dans le 1er pass
        lastLoad = 0L
        lastLoadedUrl = null
        try {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            java.io.File(ctx.filesDir, FOLDER_CACHE_FILE).delete()
        } catch (_: Throwable) {}
    }

    private suspend fun ensureRegistry() {
        val now = System.currentTimeMillis()
        val currentUrl = PLAYLIST_URL
        // 2026-06-10 : invalide automatiquement si l'URL a changé.
        if (lastLoadedUrl != null && lastLoadedUrl != currentUrl) {
            Log.d(TAG, "Source URL changed ($lastLoadedUrl → $currentUrl), invalidating cache")
            channelRegistry = emptyList()
            folderContents.clear()
            groupsCache = emptyList()  // 2026-06-10 fix : oublié
            lastLoad = 0L
        }
        if (channelRegistry.isNotEmpty() && now - lastLoad < CACHE_TTL_MS) return
        // 2026-06-10 (user "ouverture instantanée") : tente le restore disque
        //   en premier (= <100ms). Si TTL valide et URL identique → on évite
        //   le fetch HTTP des 12 sub-bouquets (5-15s).
        if (channelRegistry.isEmpty() && restoreFolderContentsFromDisk()) {
            return  // restore OK depuis disque
        }
        loadAllChannels()
        lastLoadedUrl = currentUrl
    }

    private suspend fun loadAllChannels() = withContext(Dispatchers.IO) {
        try {
            val groups = fetchTopLevel()
            if (groups.isEmpty()) return@withContext
            groupsCache = groups
            // 2026-06-17 (user "playlist crash sur appareils peu performants") :
            //   Semaphore(3) pour limiter le parallélisme. AVANT : tous les
            //   groupes fetchaient EN MÊME TEMPS, chaque M3U body.string()
            //   pouvait peser 2-5 MB → 40-100 MB peak RAM si 20 groupes →
            //   OOM crash sur appareils 1-2 GB. APRÈS : max 3 groupes en
            //   parallèle → peak ~15 MB acceptable partout.
            val fetchSem = kotlinx.coroutines.sync.Semaphore(3)
            val all = coroutineScope {
                groups.map { g ->
                    async {
                        fetchSem.acquire()
                        try {
                            fetchM3uChannels(g)
                        } catch (t: Throwable) {
                            Log.w(TAG, "fetch ${g.name} failed: ${t.message}")
                            emptyList()
                        } finally {
                            fetchSem.release()
                        }
                    }
                }.awaitAll()
            }.flatten()
            // 2026-06-17 (user "optimiser pour appareils faibles") : cap total
            //   global. Au-delà de 10 000 chaînes, c'est trop pour la RAM des
            //   appareils 1-2 GB (= chaque WlChannel = ~200 bytes → 10k = 2 MB
            //   en registre permanent + duplication dans folderContents, JSON
            //   sérialisation au persist, etc.). Tronque pour rester safe.
            val MAX_TOTAL_CHANNELS = 10_000
            val capped = if (all.size > MAX_TOTAL_CHANNELS) {
                Log.w(TAG, "Total channels ${all.size} > $MAX_TOTAL_CHANNELS → tronque (cap RAM appareils faibles)")
                all.subList(0, MAX_TOTAL_CHANNELS)
            } else all
            channelRegistry = capped
            lastLoad = System.currentTimeMillis()
            // 2026-06-10 (user "ouverture instantanée") : persiste sur disque
            //   le registry + folderContents pour qu'au prochain lancement,
            //   le home s'affiche en <100ms sans re-fetch HTTP.
            persistFolderContents()
            Log.d(TAG, "Loaded ${capped.size}/${all.size} channels across ${groups.size} groups (cached to disk)")
        } catch (t: Throwable) {
            Log.w(TAG, "loadAllChannels failed: ${t.message}")
        }
    }

    private const val FOLDER_CACHE_FILE = "world_live_folders.json"

    /** Persiste le registry + folderContents + groupsCache sur disque. */
    private fun persistFolderContents() {
        try {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            val foldersMap = org.json.JSONObject()
            folderContents.forEach { (path, items) ->
                foldersMap.put(path, channelsToJsonArray(items))
            }
            val groupsArr = org.json.JSONArray()
            groupsCache.forEach { g ->
                groupsArr.put(org.json.JSONObject().apply {
                    put("name", g.name)
                    if (g.image != null) put("image", g.image)
                    put("url", g.url)
                })
            }
            val payload = org.json.JSONObject().apply {
                put("url", PLAYLIST_URL)
                put("timestamp", System.currentTimeMillis())
                put("registry", channelsToJsonArray(channelRegistry))
                put("folders", foldersMap)
                put("groups", groupsArr)
            }
            java.io.File(ctx.filesDir, FOLDER_CACHE_FILE)
                .writeText(payload.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "persistFolderContents failed: ${t.message}")
        }
    }

    /** Restaure depuis disque si cache présent + TTL valide + URL identique. */
    private fun restoreFolderContentsFromDisk(): Boolean {
        return try {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            val file = java.io.File(ctx.filesDir, FOLDER_CACHE_FILE)
            if (!file.exists()) return false
            val payload = org.json.JSONObject(file.readText())
            val ts = payload.optLong("timestamp", 0L)
            val url = payload.optString("url", "")
            if (url != PLAYLIST_URL) return false
            if (System.currentTimeMillis() - ts > CACHE_TTL_MS) return false
            val foldersMap = payload.optJSONObject("folders")
            val registryArr = payload.optJSONArray("registry")
            val groupsArr = payload.optJSONArray("groups")
            if (registryArr == null || groupsArr == null) return false
            folderContents.clear()
            foldersMap?.keys()?.forEach { path ->
                val arr = foldersMap.optJSONArray(path) ?: return@forEach
                folderContents[path] = jsonArrayToChannels(arr)
            }
            // 2026-06-12 — Re-filtre les chaînes restaurées du cache disque
            //   au cas où l'ancien cache aurait été persisté AVANT que le
            //   filtre adulte ne soit ajouté. Garantit qu'au prochain boot
            //   les anciennes chaînes XXX cachées sont éliminées.
            channelRegistry = jsonArrayToChannels(registryArr).filter { ch ->
                !isAdultChannelOrGroup(ch.name, ch.groupName)
            }
            groupsCache = (0 until groupsArr.length()).mapNotNull { i ->
                val obj = groupsArr.optJSONObject(i) ?: return@mapNotNull null
                WlGroup(
                    name = obj.optString("name"),
                    image = obj.optString("image").takeIf { it.isNotBlank() },
                    url = obj.optString("url"),
                )
            }
            lastLoad = ts
            lastLoadedUrl = url
            Log.d(TAG, "Restored from disk: ${channelRegistry.size} channels, ${folderContents.size} folders, age=${(System.currentTimeMillis() - ts) / 1000}s")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "restoreFolderContentsFromDisk failed: ${t.message}"); false
        }
    }

    private fun channelsToJsonArray(items: List<WlChannel>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        items.forEach { ch ->
            arr.put(org.json.JSONObject().apply {
                put("id", ch.id)
                put("name", ch.name)
                if (ch.logo != null) put("logo", ch.logo)
                put("groupName", ch.groupName)
                put("streamUrl", ch.streamUrl)
                if (ch.userAgent != null) put("userAgent", ch.userAgent)
                if (ch.referer != null) put("referer", ch.referer)
                if (ch.tvgLanguage != null) put("tvgLanguage", ch.tvgLanguage)
                if (ch.isFolder) put("isFolder", true)
                if (ch.folderPath != null) put("folderPath", ch.folderPath)
            })
        }
        return arr
    }

    private fun jsonArrayToChannels(arr: org.json.JSONArray): List<WlChannel> {
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            WlChannel(
                id = obj.optString("id"),
                name = obj.optString("name"),
                logo = obj.optString("logo").takeIf { it.isNotBlank() },
                groupName = obj.optString("groupName"),
                streamUrl = obj.optString("streamUrl"),
                userAgent = obj.optString("userAgent").takeIf { it.isNotBlank() },
                referer = obj.optString("referer").takeIf { it.isNotBlank() },
                tvgLanguage = obj.optString("tvgLanguage").takeIf { it.isNotBlank() },
                isFolder = obj.optBoolean("isFolder", false),
                folderPath = obj.optString("folderPath").takeIf { it.isNotBlank() },
            )
        }
    }

    /** 2026-06-10 (user "https://cutt.ly/worldlivetv ça doit marcher comme
     *  Wiseplay") : suit les redirections HTTP des URL shortener (cutt.ly,
     *  bit.ly, tinyurl, ...) en envoyant un UA navigateur (sinon certains
     *  retournent une page interstitial au lieu d'un 30x). OkHttp suit déjà
     *  les 30x par défaut → on a juste à mettre l'UA navigateur. */
    private fun fetchTopLevel(): List<WlGroup> {
        return try {
            val url = PLAYLIST_URL
            // 2026-06-10 (user "option pour télécharger des fichiers JSON
            //   dans l'application") : support des URLs file:// (= fichiers
            //   stockés en local par le bouton "Télécharger" ou "Importer
            //   fichier local" du manager Sources).
            val body = if (url.startsWith("file://")) {
                try {
                    java.io.File(java.net.URI(url)).readText(Charsets.UTF_8)
                } catch (e: Throwable) {
                    Log.w(TAG, "fetchTopLevel: local file read failed: ${e.message}")
                    return emptyList()
                }
            } else {
                val req = Request.Builder().url(sanitizeUrlPath(url))
                    .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:115.0) " +
                                "Gecko/20100101 Firefox/115.0")
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                client.newCall(req).execute().use {
                    if (!it.isSuccessful) return emptyList()
                    it.body?.string() ?: return emptyList()
                }
            }
            // 2026-06-10 : si le body commence par <!DOCTYPE / <html (= page
            //   interstitial du shortener), on tente d'extraire l'URL cible
            //   depuis une meta refresh ou un window.location dans le HTML.
            val resolved = resolveHtmlRedirectIfAny(body)
            val finalBody = if (resolved != null) {
                Log.d(TAG, "Short link interstitial detected, refetched: $resolved")
                val req2 = Request.Builder().url(resolved)
                    .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:115.0) " +
                                "Gecko/20100101 Firefox/115.0")
                    .build()
                client.newCall(req2).execute().use {
                    if (!it.isSuccessful) return emptyList()
                    it.body?.string() ?: return emptyList()
                }
            } else body
            // 2026-06-10 (user "j'ai ajouté une source manuelle, marche sur
            //   Wiseplay pas chez nous") : si le body n'est pas du JSON top-
            //   level `{groups:[...]}`, on tente de le traiter directement
            //   comme un M3U / playlist directe (= toute la source = 1
            //   catégorie virtuelle). Wiseplay fait pareil pour les sources
            //   qui sont juste un .m3u/.m3u8 brut sans wrapper JSON.
            val trimmed = finalBody.trim()
            val looksLikeM3uOrText = trimmed.startsWith("#EXTM3U") ||
                    trimmed.startsWith("#EXTINF") ||
                    trimmed.startsWith("http://") ||
                    trimmed.startsWith("https://")
            if (looksLikeM3uOrText) {
                Log.d(TAG, "Source is a direct M3U/playlist (no JSON wrapper), wrapping as 1 group")
                // On retourne 1 seul WlGroup qui pointera vers PLAYLIST_URL —
                //   fetchM3uChannels va re-fetch et parser comme M3U.
                return listOf(
                    WlGroup(
                        name = WorldLiveSourcesStore.getActiveName(
                            com.streamflixreborn.streamflix.StreamFlixApp.instance
                        ),
                        image = null,
                        url = PLAYLIST_URL,
                    )
                )
            }
            // Tableau JSON top-level direct `[{name, url}, ...]` (= certains
            //   exports Wiseplay).
            if (trimmed.startsWith("[")) {
                try {
                    val rootArr = org.json.JSONArray(trimmed)
                    val out = ArrayList<WlGroup>(rootArr.length())
                    for (i in 0 until rootArr.length()) {
                        val g = rootArr.optJSONObject(i) ?: continue
                        val url = g.optString("url").trim()
                        if (url.isBlank()) continue
                        out.add(
                            WlGroup(
                                name = g.optString("name").trim().ifBlank { "Catégorie ${i + 1}" },
                                image = g.optString("image").takeIf { s -> s.isNotBlank() },
                                url = url,
                            )
                        )
                    }
                    if (out.isNotEmpty()) return out
                } catch (_: Throwable) { /* fall through */ }
            }
            val obj = try { JSONObject(trimmed) } catch (e: Throwable) {
                Log.w(TAG, "fetchTopLevel: body not JSON nor M3U, first 200 chars: ${trimmed.take(200)}")
                return emptyList()
            }
            // Supporte plusieurs schémas : groups[], categories[], playlists[],
            //   channels[] (= tableau aplati, on construit 1 catégorie unique).
            val arr = obj.optJSONArray("groups")
                ?: obj.optJSONArray("categories")
                ?: obj.optJSONArray("playlists")
            if (arr != null) {
                val out = ArrayList<WlGroup>(arr.length())
                for (i in 0 until arr.length()) {
                    val g = arr.optJSONObject(i) ?: continue
                    val url = g.optString("url").trim()
                    if (url.isBlank()) continue
                    out.add(
                        WlGroup(
                            name = g.optString("name").trim(),
                            image = g.optString("image").takeIf { s -> s.isNotBlank() },
                            url = url,
                        )
                    )
                }
                return out
            }
            // Pas de schéma reconnu → log et abandon
            Log.w(TAG, "fetchTopLevel: unknown JSON schema, keys=${obj.keys().asSequence().toList()}")
            emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "fetchTopLevel failed: ${t.message}")
            emptyList()
        }
    }

    /** Parse un fichier M3U standard. Format :
     *   #EXTM3U
     *   #EXTINF:-1 tvg-logo="..." group-title="...",Channel Name
     *   [#EXTVLCOPT:http-user-agent=...]
     *   [#EXTVLCOPT:http-referrer=...]
     *   https://stream.url/playlist.m3u8
     */
    private fun fetchM3uChannels(g: WlGroup, depth: Int = 0): List<WlChannel> {
        // 2026-06-14 : si URL = Xtream Codes panel
        //   (host:port/get.php?username=X&password=Y), on bascule sur le
        //   parser dédié (= player_api.php), qui donne les catégories
        //   officielles du panel + URLs streams pré-formatées. Si le
        //   parser Xtream renvoie 0 chaînes (= URL n'est pas vraiment
        //   Xtream malgré le pattern), on retombe sur le parser M3U
        //   classique ci-dessous. ADDITIF — ne casse pas les .m3u/.m3u8
        //   génériques existants.
        if (isXtreamUrl(g.url)) {
            val xtream = fetchXtreamChannels(g)
            if (xtream.isNotEmpty()) {
                Log.d(TAG, "${g.name}: Xtream parser found ${xtream.size} channels")
                return xtream
            }
            Log.w(TAG, "${g.name}: Xtream parser returned 0 → fallback to M3U")
        }
        // 2026-06-10 (user "3box TV") : les sources 3box utilisent rebrand.ly
        //   avec q=base64 → Google Sheets TSV. On décode l'URL avant fetch
        //   (rebrand.ly retourne souvent 403 pour les UA non-navigateur).
        val rawEffectiveUrl = if (g.url.contains("rebrand.ly")) {
            com.streamflixreborn.streamflix.utils
                .PlaylistParserHelpers.decodeRebrandlyUrl(g.url) ?: g.url
        } else g.url
        // 2026-06-10 (user "catégories Live & DJ set absentes") : URL-encode
        //   les espaces et chars spéciaux dans le path (OkHttp les rejette
        //   sinon → "Failed to lookup host" silencieux → 0 channels).
        val effectiveUrl = sanitizeUrlPath(rawEffectiveUrl)
        val req = Request.Builder().url(effectiveUrl)
            .header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:115.0) " +
                        "Gecko/20100101 Firefox/115.0")
            .build()
        // 2026-06-17 (user "optimiser pour appareils faibles") : cap taille
        //   body M3U à 30 MB. Au-delà, on skip → empêche un seul M3U géant
        //   d'allouer 50-100 MB en RAM et causer un OOM sur appareils 1-2 GB.
        //   Le Semaphore(3) limite déjà le parallélisme mais ne protège pas
        //   contre 1 énorme M3U.
        val MAX_BODY_BYTES = 30L * 1024 * 1024  // 30 MB
        val body = client.newCall(req).execute().use {
            if (!it.isSuccessful) return emptyList()
            val contentLength = it.body?.contentLength() ?: -1L
            if (contentLength > MAX_BODY_BYTES) {
                Log.w(TAG, "${g.name}: M3U body too large (${contentLength / 1024 / 1024} MB > 30 MB) — skipping")
                return emptyList()
            }
            it.body?.string() ?: return emptyList()
        }
        // Garde-fou supplémentaire : si Content-Length n'était pas annoncé mais
        //   le body matérialisé dépasse la limite, on abort.
        if (body.length > MAX_BODY_BYTES) {
            Log.w(TAG, "${g.name}: M3U body materialized > 30 MB (${body.length / 1024 / 1024} MB) — skipping")
            return emptyList()
        }
        // 2026-06-10 : 3 formats supportés :
        //   - M3U standard (#EXTM3U / #EXTINF)
        //   - Google Sheets JSON-per-line (format 3box TV)
        //   - JSON structuré {groups:[{stations:[]}]}  (format Dric4rTV)
        if (!body.startsWith("#EXTM3U") && !body.contains("#EXTINF")) {
            val trimmed = body.trim()
            // 2026-06-10 (user "il doit nous manquer plein de choses sur
            //   d'autres sources") : tente parseNestedJson strict d'abord.
            //   Si le résultat est trop pauvre (1-2 items pour un body
            //   plus de 2000 chars), on bascule sur parser tolérant.
            if (trimmed.startsWith("{") && (trimmed.contains("\"stations\"") || trimmed.contains("\"groups\""))) {
                val strictResult = parseNestedJsonChannels(trimmed, g, depth)
                // Si le strict parse a très peu trouvé mais le body est gros,
                //   tente le tolérant qui scanne ligne-par-ligne.
                if (strictResult.size <= 2 && body.length > 2000) {
                    val tolerant = parseTolerantJsonFragments(body, g, depth)
                    if (tolerant.size > strictResult.size) {
                        Log.d(TAG, "${g.name}: tolerant parser found ${tolerant.size} (vs strict ${strictResult.size})")
                        return tolerant
                    }
                }
                return strictResult
            }
            return parseGoogleSheetsChannels(body, g)
        }

        val out = mutableListOf<WlChannel>()
        val seen = HashSet<String>()
        val groupSlug = slugify(g.name)
        var pendingExtinf: String? = null
        var pendingUa: String? = null
        var pendingReferer: String? = null
        // 2026-06-15 (user "ajouter 2e serveur backup pour booster") : map
        //   (tvgId, groupTitle) -> index dans out pour grouper les EXTINF en
        //   doublon (= même chaîne servie par plusieurs URLs). Le 2e/3e EXTINF
        //   ajoute son URL aux extraServers de la 1ère WlChannel au lieu de
        //   créer une chaîne distincte. Mini lecteur fait fallback auto.
        //   Garde : seulement si tvg-id présent et non-vide. Sinon comportement
        //   actuel (= chId-suffix pour les doublons par nom).
        val dedupByTvgId = HashMap<Pair<String, String>, Int>()

        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF:") -> {
                    pendingExtinf = line
                    pendingUa = null
                    pendingReferer = null
                }
                line.startsWith("#EXTVLCOPT:") -> {
                    val kv = line.removePrefix("#EXTVLCOPT:").split("=", limit = 2)
                    if (kv.size == 2) {
                        when (kv[0].lowercase()) {
                            "http-user-agent" -> pendingUa = kv[1]
                            "http-referrer", "http-referer" -> pendingReferer = kv[1]
                        }
                    }
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    // C'est une URL stream
                    val extinf = pendingExtinf
                    if (extinf != null) {
                        val name = extinf.substringAfterLast(",").trim()
                        val logo = Regex("""tvg-logo="([^"]+)"""").find(extinf)?.groupValues?.get(1)
                        val groupTitle = Regex("""group-title="([^"]+)"""").find(extinf)
                            ?.groupValues?.get(1) ?: g.name
                        val tvgLanguage = Regex("""tvg-language="([^"]+)"""").find(extinf)?.groupValues?.get(1)
                        val tvgId = Regex("""tvg-id="([^"]*)"""").find(extinf)?.groupValues?.get(1)
                        val chSlug = slugify(name)

                        // 2026-06-15 : si tvg-id présent ET (tvg-id, group-title)
                        //   déjà parsé dans ce flux → ajouter en backup au lieu
                        //   de créer une nouvelle chaîne.
                        val dedupKey = if (!tvgId.isNullOrBlank()) Pair(tvgId, groupTitle) else null
                        val existingIdx = dedupKey?.let { dedupByTvgId[it] }
                        if (existingIdx != null) {
                            val cur = out[existingIdx]
                            out[existingIdx] = cur.copy(
                                extraServers = cur.extraServers + Triple(line, pendingUa, pendingReferer)
                            )
                            Log.d(TAG, "M3U dedup: '$name' (tvg-id=$tvgId, group=$groupTitle) — added as backup #${cur.extraServers.size + 1}")
                        } else {
                            var chId = "wltv::$groupSlug::$chSlug"
                            if (!seen.add(chId)) {
                                // Doublon par NAME (= pas de tvg-id) — ajoute un suffixe index
                                chId = "$chId-${out.size}"
                                seen.add(chId)
                            }
                            out.add(
                                WlChannel(
                                    id = chId,
                                    name = name,
                                    logo = logo,
                                    groupName = groupTitle,
                                    streamUrl = line,
                                    userAgent = pendingUa,
                                    referer = pendingReferer,
                                    tvgLanguage = tvgLanguage,
                                )
                            )
                            if (dedupKey != null) {
                                dedupByTvgId[dedupKey] = out.size - 1
                            }
                        }
                    }
                    pendingExtinf = null
                    pendingUa = null
                    pendingReferer = null
                }
            }
        }
        // 2026-06-17 (user "optimiser pour appareils faibles") : cap channels
        //   par groupe. Au-delà de 5000, c'est une playlist "monstre" (= type
        //   DrewLive 16k) qui va saturer la RAM des appareils faibles. On
        //   tronque pour rester safe. Les chaînes prioritaires sont en tête
        //   (= ordre du M3U source) donc le head 5000 garde le contenu utile.
        val MAX_CHANNELS_PER_GROUP = 5000
        if (out.size > MAX_CHANNELS_PER_GROUP) {
            Log.w(TAG, "${g.name}: ${out.size} channels → tronque à $MAX_CHANNELS_PER_GROUP (cap RAM appareils faibles)")
            return out.subList(0, MAX_CHANNELS_PER_GROUP).toList()
        }
        return out
    }

    /** Parse le format Dric4rTV (JSON structuré `{groups:[{stations:[]}]}`)
     *  OU sous-bouquet 3box TV style Cinéma/IPTV Daily (`{groups:[{url:...}]}`
     *  qui pointe vers d'autres playlists). Dans ce cas on récurse en
     *  fetchant chaque url. */
    private fun parseNestedJsonChannels(body: String, g: WlGroup, depth: Int = 0): List<WlChannel> {
        val out = mutableListOf<WlChannel>()
        val seen = HashSet<String>()
        val groupSlug = slugify(g.name)
        try {
            val root = JSONObject(body)
            // 2026-06-10 (user "Dric4rTV n'affiche que 3 catégories sur 9") :
            //   format Dric4rTV `{stations:[...]}` direct top-level (sans
            //   wrapper groups). On parse stations comme si c'était dans
            //   un seul sub-group nommé g.name.
            val topStations = root.optJSONArray("stations")
            if (topStations != null) {
                return parseStationsArray(topStations, g, groupSlug, seen)
            }
            val groupsArr = root.optJSONArray("groups") ?: return emptyList()
            for (gi in 0 until groupsArr.length()) {
                val sub = groupsArr.optJSONObject(gi) ?: continue
                val subName = sub.optString("name").trim().ifBlank { g.name }
                val stations = sub.optJSONArray("stations")
                val subUrl = sub.optString("url").trim()
                val nestedGroups = sub.optJSONArray("groups")
                // 2026-06-10 (user "https://pastebin.com/PtWUG302 format
                //   3 niveaux Series → Saison → Episodes") : si le sub a
                //   `groups[]` inline (pas via url) → c'est un folder qui
                //   contient des sub-folders ou stations. On crée un folder
                //   et on récurse sur ces nested groups en mémoire.
                if (stations == null && subUrl.isBlank() && nestedGroups != null && depth < 3) {
                    val folderPath = "${slugify(g.name)}/${slugify(subName)}/$gi"
                    // Reconstruit un body JSON intermédiaire pour récurser
                    //   directement sur les nested groups.
                    val syntheticBody = org.json.JSONObject().apply {
                        put("name", subName)
                        put("groups", nestedGroups)
                    }.toString()
                    val syntheticGroup = WlGroup(
                        name = subName,
                        image = sub.optString("image").takeIf { it.isNotBlank() },
                        url = "",
                    )
                    val nested = parseNestedJsonChannels(syntheticBody, syntheticGroup, depth + 1)
                    folderContents[folderPath] = nested
                    val folderId = "wltv::$groupSlug::folder::${slugify(subName)}-$gi"
                    out.add(
                        WlChannel(
                            id = folderId,
                            name = subName,
                            logo = sub.optString("image").takeIf { it.isNotBlank() },
                            groupName = g.name,
                            streamUrl = "",
                            userAgent = null,
                            referer = null,
                            tvgLanguage = null,
                            isFolder = true,
                            folderPath = folderPath,
                        )
                    )
                    Log.d(TAG, "${g.name} inline folder '$subName' → $folderPath (${nested.size} items)")
                    continue
                }
                // 2026-06-10 : si le sub-group a une `url` (pas de stations),
                //   c'est un sous-bouquet récursif (format Cinéma 3box) — on
                //   fetch et on parse cette URL comme une sous-playlist.
                //   Limite de récursion : depth=2 (évite les loops).
                if (stations == null && subUrl.isNotBlank() && depth < 3) {
                    // 2026-06-10 (user "comme Wiseplay multi-niveau") : crée
                    //   un FOLDER (= dossier explorable) pour chaque sub-bouquet
                    //   récursif. On fetch le contenu et on stocke dans
                    //   folderContents avec un path unique. Le folder est
                    //   exposé comme un WlChannel `isFolder=true` qui ouvrira
                    //   un fragment de sous-folder quand cliqué.
                    val folderPath = "${slugify(g.name)}/${slugify(subName)}/$gi"
                    val subGroup = WlGroup(
                        name = subName,  // le folder garde son nom propre
                        image = sub.optString("image").takeIf { it.isNotBlank() },
                        url = subUrl,
                    )
                    val nested = try { fetchM3uChannels(subGroup, depth + 1) } catch (_: Throwable) { emptyList() }
                    folderContents[folderPath] = nested
                    val folderId = "wltv::$groupSlug::folder::${slugify(subName)}-$gi"
                    out.add(
                        WlChannel(
                            id = folderId,
                            name = subName,
                            logo = sub.optString("image").takeIf { it.isNotBlank() },
                            groupName = g.name,
                            streamUrl = "",  // folder, pas de stream
                            userAgent = null,
                            referer = null,
                            tvgLanguage = null,
                            isFolder = true,
                            folderPath = folderPath,
                        )
                    )
                    Log.d(TAG, "${g.name} folder '$subName' → $folderPath (${nested.size} items)")
                    continue
                }
                if (stations == null) continue
                for (si in 0 until stations.length()) {
                    val st = stations.optJSONObject(si) ?: continue
                    val url = st.optString("url").trim()
                    if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) continue
                    val name = st.optString("name").trim().ifBlank { "Chaîne ${si + 1}" }
                    val image = st.optString("image").trim().takeIf { it.isNotBlank() }
                    val ua = st.optString("userAgent").trim().takeIf { it.isNotBlank() }
                    val ref = st.optString("referer").trim().takeIf { it.isNotBlank() }
                    val slug = name.lowercase()
                        .replace(Regex("[^a-z0-9]"), "").take(40)
                        .ifBlank { "$gi-$si" }
                    var chId = "wltv::$groupSlug::$slug"
                    if (!seen.add(chId)) { chId = "$chId-$si"; seen.add(chId) }
                    out.add(
                        WlChannel(
                            id = chId,
                            name = name,
                            logo = image,
                            // 2026-06-10 (user "trop granulaire") : on garde
                            //   QUE le nom top-level pour la cohérence Wiseplay.
                            groupName = g.name,
                            streamUrl = url,
                            userAgent = ua,
                            referer = ref,
                            tvgLanguage = null,
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parseNestedJson ${g.name} failed: ${t.message}")
        }
        Log.d(TAG, "${g.name}: nested-JSON → ${out.size} channels")
        return out
    }

    /** 2026-06-10 (user "il doit nous manquer plein de choses sur d'autres
     *  sources") : parser TOLÉRANT qui scanne le body ligne-par-ligne et
     *  extrait CHAQUE fragment JSON `{...}` même si le body global est mal
     *  formé (cas IPTV Daily 3box TV avec `#N/A` au milieu et mixage
     *  `groups[]` + `stations[]` + orphelins).
     *
     *  Pour chaque fragment, si {name, url} est trouvé :
     *  - URL ressemble à playlist (`.json`/`.m3u`/`playlist=`/`output=tsv`/
     *    `.txt`/`.w3u`) → folder explorable (récursion)
     *  - Sinon → chaîne directe (stream)
     */
    private fun parseTolerantJsonFragments(
        body: String,
        g: WlGroup,
        depth: Int,
    ): List<WlChannel> {
        val out = mutableListOf<WlChannel>()
        val seenIds = HashSet<String>()
        val seenFolderPaths = HashSet<String>()
        val groupSlug = slugify(g.name)
        val lines = body.split("\n", "\r\n").map { it.trim() }
        var counter = 0
        for (rawLine in lines) {
            val cleaned = rawLine.trimStart('[', ',').trimEnd(',', ']').trim()
            if (cleaned.isBlank() || cleaned == "#N/A" || !cleaned.startsWith("{")) continue
            val jsonStr = com.streamflixreborn.streamflix.utils
                .PlaylistParserHelpers.extractFirstJsonObject(cleaned) ?: continue
            val obj = try { JSONObject(jsonStr) } catch (_: Exception) { continue }
            val url = obj.optString("url").trim()
            val name = obj.optString("name").trim()
            // Skip si pas de name/url ou si c'est l'objet racine (= name="IPTV Daily" sans url stream)
            if (name.isBlank() || url.isBlank()) continue
            if (!url.startsWith("http", ignoreCase = true)) continue
            val image = obj.optString("image").trim().takeIf { it.isNotBlank() }
            val ua = obj.optString("userAgent").trim().takeIf { it.isNotBlank() }
            val referer = obj.optString("referer").trim().takeIf { it.isNotBlank() }
            val isPlaylistUrl = isPlaylistLikeUrl(url)
            if (isPlaylistUrl && depth < 3) {
                // Folder explorable
                // 2026-06-10 (user "téléchargement super long, ça boucle") :
                //   clé STABLE basée sur URL hash pour que le cache
                //   folderContents soit réutilisé entre appels successifs
                //   (sinon Kool 9032 chaînes re-fetch 5×).
                val urlKey = kotlin.math.abs(url.hashCode())
                val folderPath = "${slugify(g.name)}/${slugify(name)}/u$urlKey"
                if (!seenFolderPaths.add(folderPath)) continue
                val cachedNested = folderContents[folderPath]
                val nested = if (cachedNested != null && cachedNested.isNotEmpty()) {
                    Log.d(TAG, "${g.name} tolerant cache HIT '$name' → ${cachedNested.size} items")
                    cachedNested
                } else {
                    val subGroup = WlGroup(name = name, image = image, url = url)
                    val fetched = try { fetchM3uChannels(subGroup, depth + 1) } catch (_: Throwable) { emptyList() }
                    folderContents[folderPath] = fetched
                    fetched
                }
                counter++
                val folderId = "wltv::$groupSlug::folder::${slugify(name)}-u$urlKey"
                if (!seenIds.add(folderId)) continue
                out.add(
                    WlChannel(
                        id = folderId,
                        name = name,
                        logo = image,
                        groupName = g.name,
                        streamUrl = "",
                        userAgent = null,
                        referer = null,
                        tvgLanguage = null,
                        isFolder = true,
                        folderPath = folderPath,
                    )
                )
                Log.d(TAG, "${g.name} tolerant folder '$name' → ${nested.size} items")
            } else {
                // Chaîne directe
                val slug = name.lowercase().replace(Regex("[^a-z0-9]"), "").take(40)
                    .ifBlank { counter.toString() }
                var chId = "wltv::$groupSlug::$slug-$counter"
                counter++
                if (!seenIds.add(chId)) continue
                out.add(
                    WlChannel(
                        id = chId,
                        name = name,
                        logo = image,
                        groupName = g.name,
                        streamUrl = url,
                        userAgent = ua,
                        referer = referer,
                        tvgLanguage = null,
                    )
                )
            }
        }
        Log.d(TAG, "${g.name}: tolerant parser → ${out.size} items (${out.count { it.isFolder }} folders + ${out.count { !it.isFolder }} channels)")
        return out
    }

    /** Détecte si une URL ressemble à une playlist (= contient des sub-bouquets
     *  qu'on doit récurser) plutôt qu'à un stream final. */
    private fun isPlaylistLikeUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".json") || u.endsWith(".m3u") || u.endsWith(".m3u8") ||
                u.endsWith(".txt") || u.endsWith(".w3u") || u.endsWith(".xspf") ||
                u.contains("playlist=") || u.contains("output=tsv") ||
                u.contains("?filetype=") || u.contains("box.xtemus.com/?") ||
                u.contains("rebrand.ly") || u.contains(".dropbox.com/")
    }

    /** 2026-06-10 (user "Dric4rTV n'affiche que 3 catégories sur 9") :
     *  parse un JSONArray de stations `[{name, image, url}, ...]` en
     *  WlChannel. Utilisé pour le format `{stations:[]}` top-level (Dric4rTV
     *  NBA/horse/muzik/radio/LocalTV) où il n'y a pas de wrapper groups. */
    private fun parseStationsArray(
        stations: org.json.JSONArray,
        g: WlGroup,
        groupSlug: String,
        seen: HashSet<String>,
    ): List<WlChannel> {
        val out = mutableListOf<WlChannel>()
        for (si in 0 until stations.length()) {
            val st = stations.optJSONObject(si) ?: continue
            val url = st.optString("url").trim()
            if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) continue
            val name = st.optString("name").trim().ifBlank { "Chaîne ${si + 1}" }
            val image = st.optString("image").trim().takeIf { it.isNotBlank() }
            val ua = st.optString("userAgent").trim().takeIf { it.isNotBlank() }
            val ref = st.optString("referer").trim().takeIf { it.isNotBlank() }
            val slug = name.lowercase()
                .replace(Regex("[^a-z0-9]"), "").take(40)
                .ifBlank { si.toString() }
            var chId = "wltv::$groupSlug::$slug"
            if (!seen.add(chId)) { chId = "$chId-$si"; seen.add(chId) }
            out.add(
                WlChannel(
                    id = chId,
                    name = name,
                    logo = image,
                    groupName = g.name,
                    streamUrl = url,
                    userAgent = ua,
                    referer = ref,
                    tvgLanguage = null,
                )
            )
        }
        Log.d(TAG, "${g.name}: top-level stations → ${out.size} channels")
        return out
    }

    /** Parse le format 3box TV (Google Sheets JSON-per-line). Chaque ligne =
     *  un fragment JSON `{"url":"...","name":"...","image":"..."}`. */
    private fun parseGoogleSheetsChannels(body: String, g: WlGroup): List<WlChannel> {
        val lines = body.split("\n", "\r\n").map { it.trim() }
            .filter { it.isNotBlank() && it != "#N/A" }
        val out = mutableListOf<WlChannel>()
        val seen = HashSet<String>()
        val groupSlug = slugify(g.name)
        for ((idx, rawLine) in lines.withIndex()) {
            val cleaned = rawLine.trim().trimStart('[', ',').trimEnd(',', ']').trim()
            if (cleaned.isBlank() || !cleaned.startsWith("{")) continue
            val jsonObjStr = com.streamflixreborn.streamflix.utils
                .PlaylistParserHelpers.extractFirstJsonObject(cleaned) ?: continue
            val obj = try { JSONObject(jsonObjStr) } catch (_: Exception) { continue }
            val url = obj.optString("url").trim()
            val rawName = obj.optString("name").trim()
            val infold = obj.optString("infold").trim()
            val image = obj.optString("image").trim()
                .replace("/i/480x480/", "/i/120x120/")
                .replace("/i/720x720/", "/i/120x120/")
            // Skip header row (a 'stations' field, no url)
            if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) continue
            val cleanedName = rawName
                .replace("🌑", "").replace("🌒", "").replace("🌓", "").replace("🌔", "")
                .replace("🌕", "").replace("🌖", "").replace("🌗", "").replace("🌘", "")
                .replace("🌙", "").replace("🌚", "").replace("🌛", "").replace("🌜", "")
                .replace("›", "").replace("•", "").trim()
                .ifBlank { "Chaîne ${idx + 1}" }
            val channelName = infold.ifBlank { cleanedName }
            val slug = (infold.ifBlank { cleanedName })
                .lowercase().replace(Regex("[^a-z0-9]"), "").take(40)
                .ifBlank { idx.toString() }
            var chId = "wltv::$groupSlug::$slug"
            if (!seen.add(chId)) { chId = "$chId-$idx"; seen.add(chId) }
            val userAgent = obj.optString("userAgent").trim().takeIf { it.isNotBlank() }
            val referer = obj.optString("referer").trim().takeIf { it.isNotBlank() }
            out.add(
                WlChannel(
                    id = chId,
                    name = channelName,
                    logo = image.takeIf { it.isNotBlank() },
                    groupName = g.name,
                    streamUrl = url,
                    userAgent = userAgent,
                    referer = referer,
                    tvgLanguage = null,
                )
            )
        }
        Log.d(TAG, "${g.name}: parsed ${out.size} channels from ${lines.size} sheet lines")
        return out
    }

    /** Cherche une URL cible dans une page interstitial HTML :
     *  - <meta http-equiv="refresh" content="0; url=https://...">
     *  - window.location = "https://..."
     *  - window.location.href = "https://..."
     *  Retourne null si rien trouvé. */
    private fun resolveHtmlRedirectIfAny(body: String): String? {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("<", ignoreCase = true)) return null
        // meta refresh
        val metaRefresh = Regex(
            """<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]+content\s*=\s*["'][^"']*url\s*=\s*([^"'>\s]+)""",
            RegexOption.IGNORE_CASE,
        ).find(body)?.groupValues?.get(1)
        if (!metaRefresh.isNullOrBlank()) return metaRefresh
        // window.location[.href] = "..."
        val winLoc = Regex(
            """window\.location(?:\.href)?\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(body)?.groupValues?.get(1)
        if (!winLoc.isNullOrBlank()) return winLoc
        return null
    }

    private fun slugify(s: String): String {
        return s.lowercase().trim()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "x" }
    }

    // ───────── Provider API ─────────

    /** 2026-06-10 (user "chargement différé qui arrive au fur et à mesure") :
     *  flow progressif qui émet :
     *   1) Restore disque immédiat si cache valide (= 1 émission instantanée)
     *   2) Sinon : fetch top-level → émet sections vides → puis chaque sub
     *      ajoute ses chaînes au registry → re-émet snapshot à chaque batch.
     *  HomeViewModel collecte ce flow et update l'UI à chaque émission. */
    fun getHomeProgressive(): kotlinx.coroutines.flow.Flow<List<Category>> =
        kotlinx.coroutines.flow.channelFlow {
            // 0) Si registry déjà rempli et frais → 1 émission directe
            val now = System.currentTimeMillis()
            if (channelRegistry.isNotEmpty() && now - lastLoad < CACHE_TTL_MS) {
                send(buildSectionsFromRegistry()); return@channelFlow
            }
            // 1) Restore disque si valide
            if (restoreFolderContentsFromDisk()) {
                send(buildSectionsFromRegistry()); return@channelFlow
            }
            // 2) Fetch top-level seulement (rapide ~500ms) → émet sections vides
            val groups = withContext(Dispatchers.IO) { fetchTopLevel() }
            if (groups.isEmpty()) {
                send(emptyList()); return@channelFlow
            }
            groupsCache = groups
            send(groups.map { Category(name = it.name, list = emptyList()) })
            // 3) Fetch chaque groupe en parallèle, append au registry à chaque
            //    batch fini, et émet le snapshot via la channel outer.
            // 2026-06-11 (user "toutes les chaînes sont longues à lancer" +
            //   "chargement progressif pour éviter crash vieux appareils") :
            //   - timeout par groupe → un sub-bouquet lent ne plombe plus
            //     le boot.
            //   - parallélisme limité (Semaphore) → vieux appareils
            //     (Honor 200, Chromecast 1G) ne saturent plus la RAM/CPU.
            //   - groupes traités dans l'ordre du JSON top-level → l'user
            //     voit d'abord TF1+, France TV, Canal+, M6+ (= bouquets
            //     mainstream FR) avant les sub-bouquets exotiques.
            //
            // 2026-06-12 (user "World TV crache, il charge plus") :
            //   asm176 desserre les contraintes pour World TV (= 18k chaînes
            //   répartis en ~50 sub-bouquets). Sur Chromecast, Semaphore(2)
            //   + timeout 8s étranglait trop fort → certains sub-bouquets
            //   timeout sans rien remonter → registry incomplète et progressive
            //   END ne tombait jamais.
            //   - Semaphore 2 → 5 (Chromecast tient 5 en parallèle sans saturer)
            //   - timeout 8s → 18s (laisse le temps aux sub-bouquets lents
            //     d'origines variées)
            val producer = this  // capture ProducerScope outer pour send()
            val mutableRegistry = mutableListOf<WlChannel>()
            val flowMutex = kotlinx.coroutines.sync.Mutex()
            val parallelismLimit = kotlinx.coroutines.sync.Semaphore(5)
            coroutineScope {
                groups.forEach { g ->
                    launch(Dispatchers.IO) {
                        parallelismLimit.acquire()
                        try {
                            val channels = try {
                                kotlinx.coroutines.withTimeoutOrNull(18_000) {
                                    fetchM3uChannels(g)
                                } ?: emptyList()
                            } catch (_: Throwable) { emptyList() }
                            // 2026-06-12 (user "sur World TV, TF1 et n'importe
                            //   quelle chaîne joue maintenant du porno") : on
                            //   filtre les chaînes ET les groupes adultes pour
                            //   éviter que le contenu XXX se mélange aux
                            //   chaînes mainstream.
                            val safeChannels = channels.filter { ch ->
                                !isAdultChannelOrGroup(ch.name, ch.groupName)
                            }
                            flowMutex.lock()
                            try {
                                mutableRegistry.addAll(safeChannels)
                                channelRegistry = mutableRegistry.toList()
                                producer.send(buildSectionsFromRegistry())
                            } finally {
                                flowMutex.unlock()
                            }
                        } finally {
                            parallelismLimit.release()
                        }
                    }
                }
            }  // coroutineScope attend tous les launch enfants implicitement
            lastLoad = System.currentTimeMillis()
            lastLoadedUrl = PLAYLIST_URL
            persistFolderContents()
        }

    /** 2026-06-12 (user "sur World TV TF1 et autres chaines jouent du
     *  porno") : filtre par mots-cles adultes appliques au NOM de chaine ET
     *  au NOM de groupe. Couvre les mots-cles generiques + reseaux XXX
     *  connus FR/intl. Espaces aux extremites pour eviter les faux positifs
     *  (ex: "Sexy" mais pas "Essex"). */
    private val ADULT_KEYWORDS = listOf(
        "xxx", "adult", "porn", "porno", "erotic", "erotik", "erotique",
        "dorcel", "xxl", "hustler", "brazzers", "private channel",
        "playboy", "penthouse", "vivid", "hot tv", "hottv",
        " sex ", "sex tv", "sextv", " teen ", "teens", " anal ",
        "venus", "spice tv", "blue hustler", "redlight",
        "exotica", "passion x", "passionx",
    )

    private fun isAdultChannelOrGroup(channelName: String?, groupName: String?): Boolean {
        val cName = " ${(channelName ?: "").lowercase().trim()} "
        val gName = " ${(groupName ?: "").lowercase().trim()} "
        return ADULT_KEYWORDS.any { kw ->
            cName.contains(kw) || gName.contains(kw)
        }
    }

    private fun buildSectionsFromRegistry(): List<Category> {
        val byGroup = channelRegistry.groupBy { it.groupName }
        val orderedGroupNames = groupsCache.map { it.name }
        val sections = mutableListOf<Category>()
        for (gName in orderedGroupNames) {
            val list = byGroup[gName] ?: continue
            if (list.isEmpty()) continue
            sections.add(Category(name = gName, list = list.map { channelToTvShow(it) }))
        }
        for ((gName, list) in byGroup) {
            if (orderedGroupNames.any { it.equals(gName, ignoreCase = true) }) continue
            if (list.isEmpty()) continue
            sections.add(Category(name = gName, list = list.map { channelToTvShow(it) }))
        }
        return sections
    }

    override suspend fun getHome(): List<Category> {
        ensureRegistry()
        // 2026-06-10 (user "il faut que t'enlèves tous les filtres faut qu'on
        //   puisse voir tout le contenu — imagine je veux ajouter une catégorie
        //   anglaise et ben je verrai rien") : FILTRE LANGUE RETIRÉ par défaut.
        //   Tout passe. Si l'user veut filtrer manuellement, il pourra via le
        //   picker langue (qui reste branché côté UI).
        Log.d(TAG, "getHome → ${channelRegistry.size} channels (pas de filtre)")
        val byGroup = channelRegistry.groupBy { it.groupName }
        // Préserver l'ordre original des catégories du JSON.
        val orderedGroupNames = groupsCache.map { it.name }
        val sections = mutableListOf<Category>()
        // 1. Sections matchant exactement le nom d'un group top-level
        for (gName in orderedGroupNames) {
            val list = byGroup[gName] ?: continue
            if (list.isEmpty()) continue
            sections.add(
                Category(name = gName, list = list.map { channelToTvShow(it) })
            )
        }
        // 2. Catégories crées par tvg group-title qui ne matchent pas un group top-level
        for ((gName, list) in byGroup) {
            if (orderedGroupNames.any { it.equals(gName, ignoreCase = true) }) continue
            if (list.isEmpty()) continue
            sections.add(
                Category(name = gName, list = list.map { channelToTvShow(it) })
            )
        }
        return sections
    }

    private fun channelToTvShow(ch: WlChannel): TvShow {
        return TvShow(id = ch.id, title = ch.name).apply {
            providerName = name
            poster = ch.logo
            banner = ch.logo
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page > 1) return emptyList()
        ensureRegistry()
        return channelRegistry.map { channelToTvShow(it) }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun search(query: String, page: Int): List<TvShow> {
        if (page > 1) return emptyList()
        ensureRegistry()
        val q = query.trim().lowercase()
        if (q.isBlank()) return channelRegistry.map { channelToTvShow(it) }
        return channelRegistry
            .filter { it.name.lowercase().contains(q) }
            .map { channelToTvShow(it) }
    }

    override suspend fun getMovie(id: String): Movie =
        throw UnsupportedOperationException("WorldLiveTv n'a pas de films")

    override suspend fun getTvShow(id: String): TvShow {
        ensureRegistry()
        val ch = channelById(id)
            ?: return TvShow(id = id, title = id).apply { providerName = name }
        return channelToTvShow(ch)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()
    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id = id, name = id)
    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = "")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        ensureRegistry()
        val ch = channelById(id) ?: return emptyList()
        val servers = mutableListOf(
            Video.Server(
                id = "wltv-ch::${ch.id}",
                name = "${ch.name} [World Live TV]",
                src = ch.streamUrl,
            )
        )
        // 2026-06-15 (user "ajouter 2e serveur backup pour booster") : si la
        //   chaîne a des extraServers (= doublons EXTINF parsés par même
        //   tvg-id+group-title), les expose comme servers additionnels. Le
        //   mini lecteur enchaînera tryNextServer() automatiquement si le
        //   primary fail.
        ch.extraServers.forEachIndexed { idx, (url, _, _) ->
            servers.add(
                Video.Server(
                    id = "wltv-ch::${ch.id}::backup${idx + 1}",
                    name = "${ch.name} [World Live TV backup ${idx + 1}]",
                    src = url,
                )
            )
        }
        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        ensureRegistry()
        // 2026-06-15 : server.id format "wltv-ch::<chId>" OU "wltv-ch::<chId>::backup<N>"
        //   pour les backup servers (= multi-URL avec dedup tvg-id). Parse les
        //   deux formats pour récupérer le bon channel + le bon backup index.
        val stripped = server.id.removePrefix("wltv-ch::")
        val backupMatch = Regex("""^(.+)::backup(\d+)$""").find(stripped)
        val channelId = backupMatch?.groupValues?.get(1) ?: stripped
        val backupIdx = backupMatch?.groupValues?.get(2)?.toIntOrNull()
        val ch = channelById(channelId)
        // Si server = backup, prends l'URL/UA/Referer du backup correspondant.
        //   Sinon = primary, prends streamUrl/userAgent/referer de la WlChannel.
        val backupTriple = backupIdx?.let { idx -> ch?.extraServers?.getOrNull(idx - 1) }
        val rawSrc = backupTriple?.first ?: ch?.streamUrl ?: server.src
        val customUa = backupTriple?.second
            ?: ch?.userAgent
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:115.0) Gecko/20100101 Firefox/115.0"
        val customReferer = backupTriple?.third
            ?: ch?.referer
            ?: "https://box.xtemus.com/"
        val channelName = ch?.name ?: ""

        // 2026-06-14 (user "TF1 Premium FR ne marche pas malgré UA M3U") :
        //   le UA du M3U (ex: ExoPlayer/2.19.1 pour prime-tv) n'est PAS
        //   appliqué côté Cronet (UA hardcoded "MediaHubMX/2"). On ré-active
        //   le hook IptvProxyServer SEULEMENT pour les .ts directs (= URLs
        //   prime-tv live.aab1.top) afin de respecter strictement les headers
        //   du M3U sans casser les .m3u8 (= ParaTV/iptv-org qui marchent
        //   avec le pipeline classique).
        //
        //   Le proxy local fetch le .ts avec les bons headers et stream
        //   à ExoPlayer qui reçoit du MPEG-TS valide → décodage OK.
        //
        //   Pour les .m3u8 : pas de proxy (le proxy téléchargerait la
        //   playlist mais les segments TS référencés dedans seraient fetchés
        //   en direct par ExoPlayer sans headers). Pipeline classique.
        val srcLower = rawSrc.lowercase().substringBefore('?')
        val isTsDirect = srcLower.endsWith(".ts")
        val hasM3uHeaders = !ch?.userAgent.isNullOrBlank() || !ch?.referer.isNullOrBlank()
        if (isTsDirect && hasM3uHeaders) {
            val proxied = com.streamflixreborn.streamflix.utils.IptvProxyServer.wrap(
                url = rawSrc,
                ua = ch?.userAgent,
                referer = ch?.referer,
            )
            Log.d(TAG, "Proxied $channelName (.ts) via IptvProxyServer (UA=${ch?.userAgent?.take(40)}, Ref=${ch?.referer?.take(40)})")
            return Video(source = proxied, type = "video/mp2t", headers = emptyMap())
        }

        // 2026-06-10 (user "tu compliques les choses, importe tous les
        //   réglages du TV Hub directement sur World TV") : DÉLÉGUE le
        //   pipeline COMPLET à BoxXtemus.resolveExternalChannel — il
        //   contient toute la logique testée (ftven + TF1 mediainfo + c3v9
        //   WebView + RSS xtemus + Extractor.extract pour iframes + reset
        //   headers + cache pré-extract). Plus de duplication.
        return BoxXtemusProvider.resolveExternalChannel(
            streamUrl = rawSrc,
            customUa = customUa,
            customReferer = customReferer,
            channelName = channelName,
            channelKey = server.id,
        )
    }

    private fun channelById(id: String): WlChannel? {
        // 2026-06-10 (user "20 Min TV marche sur Wiseplay pas chez nous") :
        //   les chaînes dans les sub-bouquets sont stockées dans folderContents
        //   (pas dans channelRegistry top-level). On scanne aussi cette map.
        channelRegistry.firstOrNull { it.id == id }?.let { return it }
        for (items in folderContents.values) {
            items.firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    /** 2026-06-10 (user "Live & DJ set fail") : encode les espaces et chars
     *  spéciaux dans le path d'une URL (OkHttp rejette `http://x/a b.json`).
     *  Garde le scheme/host/query intacts.
     *
     *  2026-06-10 fix v2 (user "IPTV Daily ne montre qu'1 sub sur 17") :
     *  early-return si l'URL n'a pas de chars problématiques dans le path
     *  (= 99% des cas). Évite tout effet de bord de la reconstruction. */

    // ──────── 2026-06-14 Xtream Codes support (ADDITIF) ────────
    // Pattern URL Xtream : http(s)://host:port/get.php?username=X&password=Y&type=m3u
    // Format API panel (player_api.php) :
    //   - action=get_live_categories → JSON [{category_id, category_name}]
    //   - action=get_live_streams → JSON [{stream_id, name, stream_icon, category_id, ...}]
    // URL stream finale : http://host:port/live/<USER>/<PASS>/<STREAM_ID>.ts
    private fun isXtreamUrl(url: String): Boolean {
        return url.contains("/get.php") &&
                url.contains("username=") &&
                url.contains("password=")
    }

    private data class XtreamCreds(val baseUrl: String, val user: String, val pass: String)

    private fun parseXtreamCreds(url: String): XtreamCreds? {
        return try {
            val u = java.net.URI(url)
            val host = u.host ?: return null
            val port = u.port
            val scheme = u.scheme ?: "http"
            val portSuffix = if (port > 0 && port != u.toURL().defaultPort) ":$port" else ""
            val base = "$scheme://$host$portSuffix"
            // Parse query string username=X&password=Y
            val q = u.rawQuery ?: return null
            val params = q.split("&").mapNotNull {
                val kv = it.split("=", limit = 2)
                if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
            }.toMap()
            val user = params["username"] ?: return null
            val pass = params["password"] ?: return null
            XtreamCreds(base, user, pass)
        } catch (_: Throwable) { null }
    }

    private fun fetchXtreamString(url: String): String? {
        return try {
            val req = Request.Builder().url(url)
                // UA "Xtream-friendly" : okhttp est l'UA par défaut des apps
                //   IPTV (TiviMate, IPTV Smarters, Sparkle…). Les serveurs
                //   Xtream le reconnaissent. Firefox est souvent rejeté.
                .header("User-Agent", "okhttp/4.9.3")
                .build()
            xtreamClient.newCall(req).execute().use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "Xtream HTTP ${it.code} on ${url.take(80)}")
                    null
                } else it.body?.string()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Xtream fetch failed: ${t.message}")
            null
        }
    }

    private fun fetchXtreamChannels(g: WlGroup): List<WlChannel> {
        val creds = parseXtreamCreds(g.url) ?: run {
            Log.w(TAG, "${g.name}: not a valid Xtream URL")
            return emptyList()
        }
        val authQs = "username=${java.net.URLEncoder.encode(creds.user, "UTF-8")}" +
                "&password=${java.net.URLEncoder.encode(creds.pass, "UTF-8")}"
        // 1. Get live categories → map<category_id, category_name>
        val catsUrl = "${creds.baseUrl}/player_api.php?$authQs&action=get_live_categories"
        val catsJson = fetchXtreamString(catsUrl) ?: return emptyList()
        val catMap = HashMap<String, String>()
        try {
            val arr = org.json.JSONArray(catsJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                catMap[o.optString("category_id", "")] = o.optString("category_name", "")
            }
            Log.d(TAG, "${g.name}: Xtream ${catMap.size} categories")
        } catch (t: Throwable) {
            Log.w(TAG, "${g.name}: Xtream categories JSON parse failed: ${t.message}")
            return emptyList()
        }
        // 2. Get live streams (toutes catégories en 1 appel)
        val streamsUrl = "${creds.baseUrl}/player_api.php?$authQs&action=get_live_streams"
        val streamsJson = fetchXtreamString(streamsUrl) ?: return emptyList()
        val out = mutableListOf<WlChannel>()
        val seen = HashSet<String>()
        try {
            val arr = org.json.JSONArray(streamsJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val streamId = when {
                    o.has("stream_id") -> o.get("stream_id").toString()
                    else -> continue
                }
                val name = o.optString("name", "").trim()
                if (name.isEmpty()) continue
                val logo = o.optString("stream_icon", "").ifBlank { null }
                val catId = o.optString("category_id", "0")
                val catName = catMap[catId] ?: g.name
                // Xtream streams au format :
                //   http://host:port/live/USER/PASS/STREAMID.ts (ou .m3u8)
                val streamUrl = "${creds.baseUrl}/live/" +
                        "${java.net.URLEncoder.encode(creds.user, "UTF-8")}/" +
                        "${java.net.URLEncoder.encode(creds.pass, "UTF-8")}/" +
                        "$streamId.ts"
                val chSlug = slugify(name)
                var chId = "wltv::xtream::$chSlug::$streamId"
                if (!seen.add(chId)) {
                    chId = "$chId-${out.size}"
                    seen.add(chId)
                }
                out.add(
                    WlChannel(
                        id = chId,
                        name = name,
                        logo = logo,
                        groupName = catName,
                        streamUrl = streamUrl,
                        // UA okhttp par défaut pour les segments .ts (sinon
                        //   certains serveurs Xtream refusent).
                        userAgent = "okhttp/4.9.3",
                        referer = null,
                        tvgLanguage = null,
                    )
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "${g.name}: Xtream streams JSON parse failed: ${t.message}")
            return emptyList()
        }
        Log.d(TAG, "${g.name}: Xtream ${out.size} channels (${catMap.size} cats)")
        return out
    }

    private fun sanitizeUrlPath(url: String): String {
        // Détection rapide : si pas d'espace, pas de & ni de chars >127 dans
        //   le path/query, on ne touche pas.
        val hasProblematicChar = url.any { c ->
            c == ' ' || c.code > 127
        }
        if (!hasProblematicChar) return url
        return try {
            val u = java.net.URL(url)
            val path = u.path ?: ""
            val encodedPath = if (path.isBlank() || path == "/") path
                else path.split("/").joinToString("/") { segment ->
                    java.net.URLEncoder.encode(segment, "UTF-8")
                        .replace("+", "%20")
                }
            val query = if (u.query != null) "?${u.query}" else ""
            "${u.protocol}://${u.host}${if (u.port > 0) ":${u.port}" else ""}$encodedPath$query"
        } catch (_: Throwable) { url }
    }

    // 2026-06-10 (user "code mort à supprimer") : fonctions retirées suite
    //   à la délégation complète à BoxXtemus.resolveExternalChannel :
    //   - resolveTf1Mediainfo : géré par BoxXtemus.getVideo (mediainfo.tf1.fr)
    //   - resolveFtvenAuth : géré par BoxXtemus.getVideo (hdfauth.ftven.fr)
    //   - is3boxAntibotUrl : géré par BoxXtemus.getVideo (c3v9.short.gy/me)
    //   - isSeriesEpisodeName : heuristique abandonnée avec le folder system
    //     (toutes les récursions deviennent des folders explorables).
}

