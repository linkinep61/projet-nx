package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.IptvSource
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.IptvClassifier
import com.streamflixreborn.streamflix.utils.IptvSourceStore
import com.streamflixreborn.streamflix.utils.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 2026-05-12 (user "fait un nouveau provider sur lequel on pourra ajouter
 * nos sources nous-mêmes") : provider IPTV où l'utilisateur configure ses
 * propres sources via le settings.
 *
 * Phase 1 : M3U seul. Stalker MAG = TODO Phase 2.
 *
 * Architecture refactor 2026-05-12 (user "tu mets TV film série quand tu
 * cliques sur TV ça charge que les chaînes TV") :
 * - Provider en groupe FILMS_SERIES (movies=true, tvShows=true)
 * - getHome() = section TV Live (chaînes streaming)
 * - getMovies(page) = grille des films VOD classifiés
 * - getTvShows(page) = grille des séries (1 tile par show name)
 * - getTvShow(id) = détail show + saisons + épisodes
 */
object MyIptvProvider : Provider, IptvProvider {

    override val name = "Mon IPTV"
    override val language = "fr"
    override val baseUrl = ""
    override val logo = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_myiptv"

    private const val TAG = "MyIptvProvider"
    private const val PAGE_SIZE = 60

    /** Cache des channels parsées par sourceId. TTL 30min. */
    private const val CACHE_TTL_MS = 30L * 60L * 1000L
    private data class CachedChannels(
        val channels: List<M3uParser.M3uChannel>,
        val expiresAtMs: Long,
    )
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedChannels>()

    /** 2026-05-12 : cache classification (très lourd : 176k regex/source).
     *  Clé = sourceId. Valeur = list classifiés. Invalidé en même temps que cache. */
    private data class CachedClassification(
        val byType: Map<IptvClassifier.ContentType, List<ClassifiedItem>>,
    )
    private val classificationCache = java.util.concurrent.ConcurrentHashMap<String, CachedClassification>()

    /** 2026-05-12 (user "double-click TV → dropdown catégories à filtrer") : sélection
     *  user d'une catégorie par type. null = pas de filtre (toutes catégories FR). */
    @Volatile var selectedCategoryLive: String? = null
    @Volatile var selectedCategoryMovie: String? = null
    @Volatile var selectedCategorySeries: String? = null

    /** 2026-05-12 (user "au démarrage il met 0 film 0 série 0 TV alors qu'il trouve
     *  des chaînes") : compte direct depuis le cache classification (filtré FR).
     *  Utilisé par IptvSourcesActivity pour le toast "X TV • Y films • Z séries". */
    suspend fun countByTypeFR(): Triple<Int, Int, Int> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Force le chargement du cache si vide
        val sources = IptvSourceStore.getAll()
        for (s in sources) {
            if (classificationCache[s.id] == null) loadChannels(s)
        }
        var live = 0; var movie = 0; var series = 0
        for (cached in classificationCache.values) {
            cached.byType[IptvClassifier.ContentType.LIVE]?.let { items ->
                live += items.count { isFrenchChannel(it.channel) }
            }
            cached.byType[IptvClassifier.ContentType.MOVIE]?.let { items ->
                movie += items.count { isFrenchChannel(it.channel) }
            }
            cached.byType[IptvClassifier.ContentType.SERIES]?.let { items ->
                series += items.count { isFrenchChannel(it.channel) }
            }
        }
        Triple(live, movie, series)
    }

    /** Liste les catégories disponibles avec leur nombre de chaînes (e.g. "FR| FRANCE
     *  FHD HD (105)"). Utilisé par MainTvActivity pour peupler le dropdown.
     *  Renvoie une List<Pair<groupName, count>> triée par count desc puis alpha. */
    fun availableCategoriesWithCount(type: IptvClassifier.ContentType): List<Pair<String, Int>> {
        val counts = mutableMapOf<String, Int>()
        for (cached in classificationCache.values) {
            cached.byType[type]?.forEach { item ->
                // Skip non-French entries
                if (!isFrenchChannel(item.channel)) return@forEach
                val g = item.channel.group?.trim()?.ifBlank { "Autres" } ?: "Autres"
                counts[g] = (counts[g] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }

    /** Variante simple sans compteurs (compat). */
    fun availableCategories(type: IptvClassifier.ContentType): List<String> =
        availableCategoriesWithCount(type).map { it.first }

    /** OkHttpClient permissif — copie le comportement VU IPTV / TiviMate :
     *  trust-all certs + accept all hostnames. Nécessaire parce que les
     *  serveurs IPTV utilisent souvent des certs self-signed / expirés /
     *  mauvais CN. Sécurité OK car ces URLs sont fournies par l'utilisateur
     *  lui-même (confiance explicite), pas par un tiers. */
    /** 2026-05-12 (CC source returned malformed redirect → "Invalid URL port: \"8080") :
     *  - followRedirects=false : on gère les redirections nous-mêmes pour fix les
     *    Location headers cassés (e.g. `Location: http://srv:"8080/...`)
     *  - hostnameVerifier no-op, trust-all certs : copie VU IPTV. */
    private val client by lazy {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })
        val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(sslCtx.socketFactory, trustAll[0] as javax.net.ssl.X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    // IptvProvider : pas de progressive discovery — tout vient du parse initial.
    private val _additionalServersFlow = MutableSharedFlow<Video.Server>()
    override val additionalServersFlow: SharedFlow<Video.Server> = _additionalServersFlow

    // ═══════════════════════════════════════════════════════════════
    //   HOME (= TV Live chaines)
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getHome(): List<Category> = withContext(Dispatchers.IO) {
        val sources = IptvSourceStore.getAll()
        if (sources.isEmpty()) {
            return@withContext listOf(Category(name = "Aucune source — clic provider Mon IPTV pour configurer", list = emptyList()))
        }

        val (liveItems, errors) = collectByType(sources, IptvClassifier.ContentType.LIVE)
        val sections = mutableListOf<Category>()
        errors.forEach { sections.add(Category(name = it, list = emptyList())) }
        if (liveItems.isNotEmpty()) {
            // 2026-05-12 (user "il faudrait pouvoir descendre vers le bas et avoir
            // un gros paquet de chaînes") : split en plusieurs rangées de 8 pour
            // remplir l'écran verticalement style grille (au lieu d'1 seule rangée).
            val selected = selectedCategoryLive
            val filtered = if (selected != null) {
                liveItems.filter { (it.channel.group ?: "").trim() == selected }
            } else liveItems
            Log.d(TAG, "getHome filter='${selected ?: "ALL FR"}' → ${filtered.size}/${liveItems.size} chaînes")
            if (filtered.isEmpty() && selected != null) {
                // Le filtre ne matche rien — log les 3 premiers group-titles existants pour debug
                val sampleGroups = liveItems.take(20).map { it.channel.group ?: "(null)" }.distinct().take(5)
                Log.w(TAG, "Filter '$selected' MATCH 0 items. Sample groups in pool: $sampleGroups")
            }
            val titlePrefix = if (selected != null) "📡 $selected" else "📡 Toutes les chaînes FR"
            val rowSize = 8
            val totalRows = (filtered.size + rowSize - 1) / rowSize
            for (rowIdx in 0 until totalRows) {
                val from = rowIdx * rowSize
                val to = minOf(from + rowSize, filtered.size)
                val rowItems = filtered.subList(from, to)
                // Pour la 1re ligne, on affiche le titre principal + count. Pour les
                // suivantes, juste un espace pour ne pas spammer le même titre.
                val rowTitle = if (rowIdx == 0) "$titlePrefix (${filtered.size})"
                               else "  " // espace minimal pour séparer visuellement
                sections.add(Category(
                    name = rowTitle,
                    list = rowItems.map { itemToTvShowLive(it) },
                ))
            }
        } else if (errors.isEmpty()) {
            sections.add(Category(name = "Aucune chaîne TV — vérifie tes sources", list = emptyList()))
        }
        sections
    }

    // ═══════════════════════════════════════════════════════════════
    //   FILMS (onglet bottom nav "Films")
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getMovies(page: Int): List<Movie> = withContext(Dispatchers.IO) {
        val sources = IptvSourceStore.getAll()
        val (allMovies, _) = collectByType(sources, IptvClassifier.ContentType.MOVIE)
        // Tri alphabétique par nom puis pagination
        val sorted = allMovies.sortedBy { it.channel.name.lowercase() }
        val from = (page - 1) * PAGE_SIZE
        if (from >= sorted.size) return@withContext emptyList()
        val to = minOf(from + PAGE_SIZE, sorted.size)
        sorted.subList(from, to).map { itemToMovie(it) }
    }

    override suspend fun getMovie(id: String): Movie = withContext(Dispatchers.IO) {
        // 2026-05-12 : accepte 2 prefixes — `myiptv-live::` (live TV) et `myiptv-movie::` (VOD)
        val cleaned = id.removePrefix("myiptv-live::").removePrefix("myiptv-movie::")
        val parts = cleaned.split("::")
        if (parts.size != 2) throw Exception("Bad movie id: $id")
        val sourceId = parts[0]
        val channelIdx = parts[1].toIntOrNull() ?: throw Exception("Bad channel idx")
        val source = IptvSourceStore.getById(sourceId) ?: throw Exception("Source disparue")
        val channels = loadChannels(source)
        val channel = channels.getOrNull(channelIdx) ?: throw Exception("Chaîne introuvable")
        val isLive = id.startsWith("myiptv-live::")
        Movie(
            id = id,
            title = channel.name,
            overview = channel.group?.let { if (isLive) "📡 $it" else "Catégorie : $it" },
            poster = channel.logo,
            banner = channel.logo,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //   SÉRIES (onglet bottom nav "Séries")
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getTvShows(page: Int): List<TvShow> = withContext(Dispatchers.IO) {
        val sources = IptvSourceStore.getAll()
        val (allEpisodes, _) = collectByType(sources, IptvClassifier.ContentType.SERIES)
        // Grouper par nom de show + tri alpha
        val byShow = allEpisodes.groupBy { IptvClassifier.extractShowName(it.channel.name) }
        val shows = byShow.entries.sortedBy { it.key.lowercase() }
        val from = (page - 1) * PAGE_SIZE
        if (from >= shows.size) return@withContext emptyList()
        val to = minOf(from + PAGE_SIZE, shows.size)
        shows.subList(from, to).map { (showName, episodes) ->
            // Poster = celui du 1er épisode disponible (logo M3U), fallback null
            val poster = episodes.firstNotNullOfOrNull { it.channel.logo }
            TvShow(
                id = "myiptv-show::${encodeId(showName)}",
                title = showName,
                overview = "${episodes.size} épisode${if (episodes.size > 1) "s" else ""}",
                poster = poster,
                banner = poster,
            )
        }
    }

    override suspend fun getTvShow(id: String): TvShow = withContext(Dispatchers.IO) {
        // 2026-05-12 : ArtworkRepair iterate sur tous les TvShows en DB, y compris
        // les chaînes Live (id `myiptv-live::`) qui ne sont PAS des séries. On
        // renvoie un TvShow stub pour éviter le spam d'exceptions dans le logcat.
        if (id.startsWith("myiptv-live::")) {
            return@withContext TvShow(id = id, title = "Live")
        }
        val showName = decodeId(id.removePrefix("myiptv-show::"))
        val sources = IptvSourceStore.getAll()
        val (allEpisodes, _) = collectByType(sources, IptvClassifier.ContentType.SERIES)
        val matchingEpisodes = allEpisodes.filter {
            IptvClassifier.extractShowName(it.channel.name).equals(showName, ignoreCase = true)
        }
        if (matchingEpisodes.isEmpty()) throw Exception("Série '$showName' introuvable")

        // Toutes les saisons et épisodes regroupés. On essaye d'extraire la
        // saison via regex SXX du nom de l'épisode. Si absent, on met tout
        // dans Saison 1.
        val seasonPattern = Regex("""[sS](\d{1,2})""")
        val episodes = matchingEpisodes.mapIndexed { idx, item ->
            val seasonNum = seasonPattern.find(item.channel.name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epNum = Regex("""[eE](\d{1,3})""").find(item.channel.name)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
            Triple(item, seasonNum, epNum)
        }
        val seasons = episodes.groupBy { it.second }.entries.sortedBy { it.key }.map { (seasonNum, _) ->
            Season(
                id = "myiptv-season::${encodeId(showName)}::$seasonNum",
                number = seasonNum,
                title = "Saison $seasonNum",
            )
        }
        val poster = matchingEpisodes.firstNotNullOfOrNull { it.channel.logo }
        TvShow(
            id = id,
            title = showName,
            overview = "${matchingEpisodes.size} épisode${if (matchingEpisodes.size > 1) "s" else ""}",
            poster = poster,
            banner = poster,
            seasons = seasons,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = withContext(Dispatchers.IO) {
        val parts = seasonId.removePrefix("myiptv-season::").split("::")
        if (parts.size != 2) return@withContext emptyList()
        val showName = decodeId(parts[0])
        val seasonNum = parts[1].toIntOrNull() ?: return@withContext emptyList()

        val sources = IptvSourceStore.getAll()
        val (allEpisodes, _) = collectByType(sources, IptvClassifier.ContentType.SERIES)
        val matching = allEpisodes.filter {
            IptvClassifier.extractShowName(it.channel.name).equals(showName, ignoreCase = true)
        }

        val seasonPattern = Regex("""[sS](\d{1,2})""")
        val epPattern = Regex("""[eE](\d{1,3})""")
        matching.mapIndexed { idx, item ->
            val s = seasonPattern.find(item.channel.name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val e = epPattern.find(item.channel.name)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
            Triple(item, s, e)
        }.filter { it.second == seasonNum }.map { (item, _, epNum) ->
            Episode(
                id = "myiptv-ep::${item.source.id}::${item.index}",
                number = epNum,
                title = item.channel.name,
                poster = item.channel.logo,
            )
        }.sortedBy { it.number }
    }

    // ═══════════════════════════════════════════════════════════════
    //   SERVERS / VIDEO
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val (sourceId, channelIdx) = when {
            id.startsWith("myiptv-live::") -> {
                val p = id.removePrefix("myiptv-live::").split("::")
                if (p.size != 2) return emptyList()
                p[0] to (p[1].toIntOrNull() ?: return emptyList())
            }
            id.startsWith("myiptv::") -> {
                val p = id.removePrefix("myiptv::").split("::")
                if (p.size != 2) return emptyList()
                p[0] to (p[1].toIntOrNull() ?: return emptyList())
            }
            id.startsWith("myiptv-movie::") -> {
                val p = id.removePrefix("myiptv-movie::").split("::")
                if (p.size != 2) return emptyList()
                p[0] to (p[1].toIntOrNull() ?: return emptyList())
            }
            id.startsWith("myiptv-ep::") -> {
                val p = id.removePrefix("myiptv-ep::").split("::")
                if (p.size != 2) return emptyList()
                p[0] to (p[1].toIntOrNull() ?: return emptyList())
            }
            else -> return emptyList()
        }
        val source = IptvSourceStore.getById(sourceId) ?: return emptyList()
        return listOf(
            Video.Server(
                id = "myiptv-stream::${source.id}::$channelIdx",
                name = source.name,
            )
        )
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val parts = server.id.removePrefix("myiptv-stream::").split("::")
        if (parts.size != 2) throw Exception("Bad server id: ${server.id}")
        val sourceId = parts[0]
        val channelIdx = parts[1].toIntOrNull() ?: throw Exception("Bad channel idx")
        val source = IptvSourceStore.getById(sourceId) ?: throw Exception("Source disparue")
        val channels = loadChannels(source)
        val channel = channels.getOrNull(channelIdx) ?: throw Exception("Chaîne #$channelIdx introuvable")

        val headers = mutableMapOf<String, String>()
        source.userAgent?.let { headers["User-Agent"] = it }
        source.referer?.let { headers["Referer"] = it }
        channel.options["http-user-agent"]?.let { headers["User-Agent"] = it }
        channel.options["http-referrer"]?.let { headers["Referer"] = it }

        // 2026-05-12 : Stalker → résoudre l'URL réelle via create_link au moment du play
        val streamUrl = if (source.type == IptvSource.Type.STALKER && channel.options.containsKey("stalker-cmd")) {
            val resolved = withContext(Dispatchers.IO) {
                com.streamflixreborn.streamflix.utils.StalkerClient.createStreamLink(
                    portalUrl = source.url,
                    mac = source.mac ?: "",
                    cmd = channel.options["stalker-cmd"] ?: channel.url,
                    userAgent = source.userAgent,
                )
            }
            resolved ?: throw Exception("Stalker create_link a échoué pour ${channel.name}")
        } else {
            channel.url
        }

        return Video(
            source = streamUrl,
            headers = if (headers.isEmpty()) null else headers,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //   HELPERS internes
    // ═══════════════════════════════════════════════════════════════

    /** Item classifié interne — porte la source + channel + index + type. */
    private data class ClassifiedItem(
        val source: IptvSource,
        val channel: M3uParser.M3uChannel,
        val index: Int,
        val type: IptvClassifier.ContentType,
    )

    /** Charge toutes les sources et collecte les channels du type demandé.
     *  Retourne aussi les erreurs (messages utilisateur) pour affichage. */
    private suspend fun collectByType(
        sources: List<IptvSource>,
        type: IptvClassifier.ContentType,
    ): Pair<List<ClassifiedItem>, List<String>> {
        val all = mutableListOf<ClassifiedItem>()
        val errors = mutableListOf<String>()
        for (source in sources) {
            try {
                // 2026-05-12 : cache classification — évite re-classifier 176k items à chaque tab switch
                val classified = classificationCache[source.id]
                if (classified != null) {
                    classified.byType[type]?.let { all.addAll(it.filter { isFrenchChannel(it.channel) }) }
                    continue
                }
                val channels = loadChannels(source)
                if (channels.isEmpty()) {
                    errors.add("⚠ '${source.name}' : aucune chaîne (URL invalide ?)")
                    continue
                }
                // Classifie une fois pour tous types et cache
                val byType = mutableMapOf<IptvClassifier.ContentType, MutableList<ClassifiedItem>>()
                channels.forEachIndexed { idx, ch ->
                    val t = IptvClassifier.classify(ch)
                    val item = ClassifiedItem(source, ch, idx, t)
                    byType.getOrPut(t) { mutableListOf() }.add(item)
                }
                classificationCache[source.id] = CachedClassification(byType)
                // 2026-05-12 (user "que les chaînes françaises") : filtre FR au moment du return
                byType[type]?.let { all.addAll(it.filter { isFrenchChannel(it.channel) }) }
            } catch (e: Exception) {
                errors.add("❌ '${source.name}' : ${e.message?.take(80)}")
            }
        }
        return all to errors
    }

    /** 2026-05-12 (user "que les chaînes françaises qui apparaissent") : détecte si
     *  une chaîne est française par son group-title ou son nom. */
    private fun isFrenchChannel(channel: M3uParser.M3uChannel): Boolean {
        return isFrenchGroupName(channel.group ?: "") || isFrenchGroupName(channel.name)
    }

    /** Vrai si le nom contient des marqueurs FR. Utilisé pour pré-filtrer les
     *  catégories Xtream et éviter de télécharger l'allemand/arabe/etc. */
    internal fun isFrenchGroupName(name: String): Boolean {
        val s = name.lowercase().trim()
        // Préfixes courants des bouquets IPTV : "FR|", "FR -", "FR ", "FR_", etc.
        val frPrefixes = listOf("fr|", "fr -", "fr ", "fr-", "fr_", "fr.", "fr:", "fr/")
        if (frPrefixes.any { s.startsWith(it) }) return true
        // Mots-clés FR n'importe où dans le nom
        val frKeywords = listOf("france", "français", "francais", "french", "🇫🇷")
        if (frKeywords.any { s.contains(it) }) return true
        return false
    }

    private fun itemToTvShow(item: ClassifiedItem): TvShow = TvShow(
        id = "myiptv::${item.source.id}::${item.index}",
        title = item.channel.name,
        poster = item.channel.logo,
        banner = item.channel.logo,
        providerName = name,
    )

    /** 2026-05-12 : LIVE channels exposés comme TvShow avec prefixe `myiptv-live::`.
     *  TvShowViewHolder a un fast-path "direct play" pour les IDs IPTV → bypass page
     *  detail + active mini-player. */
    private fun itemToTvShowLive(item: ClassifiedItem): TvShow = TvShow(
        id = "myiptv-live::${item.source.id}::${item.index}",
        title = item.channel.name,
        overview = item.channel.group?.let { "📡 $it" },
        poster = item.channel.logo,
        banner = item.channel.logo,
        providerName = name,
    )

    private fun itemToMovie(item: ClassifiedItem): Movie = Movie(
        id = "myiptv-movie::${item.source.id}::${item.index}",
        title = item.channel.name,
        overview = item.channel.group,
        poster = item.channel.logo,
        banner = item.channel.logo,
    )

    /** Encode un show name pour usage en ID (gère caractères spéciaux). */
    private fun encodeId(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)

    private fun decodeId(s: String): String =
        try {
            String(android.util.Base64.decode(s, android.util.Base64.URL_SAFE), Charsets.UTF_8)
        } catch (_: Exception) { s }

    /** Charge les channels d'une source (mémoire → disque → HTTP). */
    private suspend fun loadChannels(source: IptvSource): List<M3uParser.M3uChannel> {
        // 1. Cache mémoire
        cache[source.id]?.let { cached ->
            if (System.currentTimeMillis() < cached.expiresAtMs) return cached.channels
            cache.remove(source.id)
        }
        // 2. 2026-05-12 (user "TiviMate garde les chaînes en mémoire une fois chargées") :
        //    cache disque persistant entre sessions. Lit instantanément depuis disque
        //    si présent au lieu de re-télécharger 176k chaînes.
        loadFromDisk(source.id)?.let { channels ->
            Log.d(TAG, "M3U ${source.name} chargé depuis disque : ${channels.size} chaînes")
            cache[source.id] = CachedChannels(channels, System.currentTimeMillis() + CACHE_TTL_MS)
            return channels
        }
        // 3. 2026-05-12 (user "VU IPTV charge live/movies/series séparément") : si l'URL
        //    est du format Xtream Codes (get.php?username=X&password=Y), on appelle
        //    player_api.php au lieu de parser le M3U entier. Classification fiable +
        //    rapide car serveur classifie déjà.
        if (source.type == IptvSource.Type.M3U) {
            val xtream = tryFetchXtream(source)
            if (xtream != null) {
                cache[source.id] = CachedChannels(xtream, System.currentTimeMillis() + CACHE_TTL_MS)
                if (xtream.isNotEmpty()) saveToDisk(source.id, xtream)
                return xtream
            }
        }
        // 4. Fallback : parse M3U classique
        val channels = when (source.type) {
            IptvSource.Type.M3U -> fetchM3u(source)
            IptvSource.Type.STALKER -> fetchStalker(source)
        }
        cache[source.id] = CachedChannels(channels, System.currentTimeMillis() + CACHE_TTL_MS)
        if (channels.isNotEmpty()) saveToDisk(source.id, channels)
        return channels
    }

    /** 2026-05-12 : tente Xtream Codes API. Retourne null si pas Xtream ou si l'API
     *  ne répond pas. Sinon retourne les 3 listes (live + vod + series) déjà tagged
     *  via le champ `options` (`xtream-type=live/movie/series`) pour classification rapide.
     *  Note : pour les séries on émet 1 M3uChannel par série (placeholder) — les épisodes
     *  sont fetched à la demande dans getEpisodesBySeason. */
    private suspend fun tryFetchXtream(source: IptvSource): List<M3uParser.M3uChannel>? = withContext(Dispatchers.IO) {
        val cleanUrl = sanitizeUrl(source.url)
        if (!cleanUrl.contains("get.php", ignoreCase = true)) return@withContext null
        val creds = com.streamflixreborn.streamflix.utils.XtreamCodesClient.parseCreds(cleanUrl) ?: return@withContext null
        if (!com.streamflixreborn.streamflix.utils.XtreamCodesClient.probeApi(creds, source.userAgent)) {
            Log.d(TAG, "Xtream API non disponible sur ${creds.baseUrl} — fallback M3U")
            return@withContext null
        }
        Log.d(TAG, "Xtream API détectée — fetch catégories FR puis streams filtrés")
        val Xtream = com.streamflixreborn.streamflix.utils.XtreamCodesClient
        // 1. Fetch les listes de catégories (légères : ~100 entries chacune)
        val allLiveCats = Xtream.getLiveCategories(creds, source.userAgent)
        val allVodCats = Xtream.getVodCategories(creds, source.userAgent)
        val allSeriesCats = Xtream.getSeriesCategories(creds, source.userAgent)
        // 2. Filtre uniquement les catégories FR (user "que les films français, évite de
        //    chercher dans tout le paquet")
        val liveCategories = allLiveCats.filter { isFrenchGroupName(it.name) }.associateBy { it.id }
        val vodCategories = allVodCats.filter { isFrenchGroupName(it.name) }.associateBy { it.id }
        val seriesCategories = allSeriesCats.filter { isFrenchGroupName(it.name) }.associateBy { it.id }
        Log.d(TAG, "Catégories FR : ${liveCategories.size} Live / ${vodCategories.size} VOD / ${seriesCategories.size} Séries")
        val out = mutableListOf<M3uParser.M3uChannel>()
        // Live : fetch que les catégories FR
        for (live in Xtream.getLiveStreamsForCategories(creds, liveCategories.keys, source.userAgent)) {
            val groupName = live.categoryId?.let { liveCategories[it]?.name } ?: "TV Live"
            out += M3uParser.M3uChannel(
                name = live.name,
                url = live.streamUrl(creds),
                logo = live.logo,
                group = groupName,
                tvgId = live.epgChannelId,
                options = mapOf("xtream-type" to "live"),
            )
        }
        // VOD : fetch que les catégories FR
        for (vod in Xtream.getVodStreamsForCategories(creds, vodCategories.keys, source.userAgent)) {
            val groupName = vod.categoryId?.let { vodCategories[it]?.name } ?: "Films"
            out += M3uParser.M3uChannel(
                name = vod.name,
                url = vod.streamUrl(creds),
                logo = vod.logo,
                group = groupName,
                tvgId = null,
                options = mapOf("xtream-type" to "movie"),
            )
        }
        // Series : fetch que les catégories FR
        for (series in Xtream.getSeriesForCategories(creds, seriesCategories.keys, source.userAgent)) {
            val groupName = series.categoryId?.let { seriesCategories[it]?.name } ?: "Séries"
            out += M3uParser.M3uChannel(
                name = series.name,
                url = "xtream-series://${series.seriesId}", // placeholder — résolu plus tard
                logo = series.cover,
                group = groupName,
                tvgId = null,
                options = mapOf(
                    "xtream-type" to "series",
                    "xtream-series-id" to series.seriesId,
                    "xtream-host" to creds.host,
                    "xtream-user" to creds.user,
                    "xtream-pass" to creds.pass,
                ),
            )
        }
        Log.d(TAG, "Xtream total : ${out.size} entries (live + vod + series placeholders)")
        out
    }

    /** Format TSV simple : 1 ligne par channel, colonnes name\turl\tlogo\tgroup\ttvgId.
     *  Beaucoup plus rapide que JSON et zéro dépendance externe. */
    private fun cacheFile(sourceId: String): java.io.File? {
        val ctx = try { com.streamflixreborn.streamflix.StreamFlixApp.instance } catch (_: Throwable) { return null }
        val dir = java.io.File(ctx.cacheDir, "iptv")
        if (!dir.exists()) dir.mkdirs()
        return java.io.File(dir, "${sourceId}.tsv")
    }

    private fun saveToDisk(sourceId: String, channels: List<M3uParser.M3uChannel>) {
        val file = cacheFile(sourceId) ?: return
        try {
            file.bufferedWriter().use { writer ->
                for (ch in channels) {
                    // Escape tab/newline dans les fields
                    fun esc(s: String?) = (s ?: "").replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                    writer.write(esc(ch.name))
                    writer.write("\t")
                    writer.write(esc(ch.url))
                    writer.write("\t")
                    writer.write(esc(ch.logo))
                    writer.write("\t")
                    writer.write(esc(ch.group))
                    writer.write("\t")
                    writer.write(esc(ch.tvgId))
                    writer.newLine()
                }
            }
            Log.d(TAG, "M3U sauvé disque ${file.absolutePath} (${channels.size} chaînes, ${file.length()/1024}KB)")
        } catch (e: Exception) {
            Log.w(TAG, "Save disk fail: ${e.message}")
        }
    }

    private fun loadFromDisk(sourceId: String): List<M3uParser.M3uChannel>? {
        val file = cacheFile(sourceId) ?: return null
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val out = mutableListOf<M3uParser.M3uChannel>()
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split('\t')
                    if (parts.size < 5) continue
                    out += M3uParser.M3uChannel(
                        name = parts[0],
                        url = parts[1],
                        logo = parts[2].takeIf { it.isNotEmpty() },
                        group = parts[3].takeIf { it.isNotEmpty() },
                        tvgId = parts[4].takeIf { it.isNotEmpty() },
                    )
                }
            }
            if (out.isNotEmpty()) out else null
        } catch (e: Exception) {
            Log.w(TAG, "Load disk fail: ${e.message}")
            null
        }
    }

    /** 2026-05-12 : si l'utilisateur a collé un bloc multi-lignes dans le champ
     *  URL (ex: panel admin entier), on extrait le bon URL Xtream Codes :
     *
     *  Cas 1 : ligne avec `get.php?username=X&password=Y` complète → on la prend.
     *  Cas 2 : URL serveur sur ligne 1 + username/password sur lignes suivantes
     *          → on construit `{server}/get.php?username={u}&password={p}&type=m3u_plus`.
     *  Cas 3 : juste une URL mono-ligne → return tel quel.
     *
     *  Sinon OkHttp crashe avec "Invalid URL port" sur le contenu après le port. */
    /** Exposé pour que IptvSourcesActivity puisse nettoyer l'URL à la sauvegarde. */
    fun sanitizeUrlPublic(raw: String): String = sanitizeUrl(raw)

    private fun sanitizeUrl(raw: String): String {
        val trimmed = raw.trim()
        // Si déjà mono-ligne propre, retourner tel quel
        if (!trimmed.contains('\n') && !trimmed.contains('\r')) return trimmed

        // Cas 1 : 1ère URL get.php complète (avec username= et password=) → priorité
        val getPhpRegex = Regex("""https?://[^\s]+get\.php\?[^\s]*username=[^\s&]+[^\s]*password=[^\s&]+[^\s]*""")
        getPhpRegex.find(trimmed)?.let { return it.value.trim('.', ',', ';', ')', '(', '"', '\'') }

        // Cas 2 : URL serveur seule sur 1ère ligne + username/password sur lignes suivantes
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val serverUrl = lines.firstOrNull()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        if (serverUrl != null && !serverUrl.contains("get.php", ignoreCase = true)) {
            // Trouve username/password : 2 lignes consécutives plausibles (8-32 chars alphanumériques)
            // après la ligne du serveur. Pattern courant : `<host>` `<blank>` `<username>` `<password>` `<date>`
            for (i in 1 until lines.size - 1) {
                val u = lines[i]
                val p = lines[i + 1]
                if (u.length in 4..40 && p.length in 4..40 &&
                    u.matches(Regex("[A-Za-z0-9]+")) && p.matches(Regex("[A-Za-z0-9]+"))) {
                    val base = serverUrl.trimEnd('/')
                    val built = "$base/get.php?username=$u&password=$p&type=m3u_plus"
                    Log.d(TAG, "URL Xtream reconstruite : '$built' depuis (server,$u,$p)")
                    return built
                }
            }
        }

        // Cas 3 fallback : 1ère URL http(s):// dans le texte
        val urlRegex = Regex("""https?://\S+""")
        urlRegex.find(trimmed)?.let { return it.value.trim('.', ',', ';', ')', '(', '"', '\'') }
        return lines.firstOrNull() ?: trimmed
    }

    private suspend fun fetchM3u(source: IptvSource): List<M3uParser.M3uChannel> = withContext(Dispatchers.IO) {
        // 2026-05-12 (user "ça marche pas dans l'APK" avec URL https://srv:80) :
        // détecte le mismatch protocole/port. Beaucoup de serveurs IPTV exposent
        // l'URL en `https://srv:80/...` alors que le port 80 parle en HTTP plain.
        // OkHttp plante avec "Unable to parse TLS packet header". VU IPTV / TiviMate
        // corrigent silencieusement. On fait pareil + on retry en HTTP si TLS échoue.
        // UA générique compatible avec la plupart des serveurs IPTV qui filtrent
        // les requêtes sans UA (les serveurs IPTV mainstream attendent un UA).
        // 2026-05-12 : on essaie plusieurs UAs comme VU IPTV / TiviMate.
        // L'UA par défaut Mozilla est ce qu'utilise VU IPTV (cf decompilation).
        // Si l'user fournit un UA dans la source, on l'utilise et c'est tout.
        // Sinon on essaie : Mozilla Android (VU IPTV style) puis VLC (fallback).
        val userUa = source.userAgent?.takeIf { it.isNotBlank() }
        val userAgents = if (userUa != null) listOf(userUa) else listOf(
            "Mozilla/5.0 (Linux; U; Android 14; en-us; Pixel Build/AP1A.240505.005) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Safari/534.30",
            "VLC/3.0.20 LibVLC/3.0.20",
            "IPTVSmarters",
        )
        val cleanUrl = sanitizeUrl(source.url)
        if (cleanUrl != source.url) {
            Log.w(TAG, "URL ${source.name} nettoyée : avait ${source.url.length}c multi-lignes → '$cleanUrl'")
        }
        val candidateUrls = buildCandidateUrls(cleanUrl)
        var lastError: Exception? = null
        for (ua in userAgents) {
            for (url in candidateUrls) {
                try {
                    // 2026-05-12 (OOM crash on 35MB+ M3U) : stream-parse directement
                    // depuis la response body sans loader la string entière en RAM.
                    val parsed = fetchAndStreamParseM3u(url, ua, source.referer)
                    if (parsed == null) {
                        Log.w(TAG, "M3U ${source.name} [${ua.take(20)}] fetch null sur $url")
                        continue
                    }
                    if (parsed.isNotEmpty()) {
                        Log.d(TAG, "M3U ${source.name} OK avec UA=${ua.take(30)}, url=$url, ${parsed.size} chaînes")
                        return@withContext parsed
                    } else {
                        Log.w(TAG, "M3U ${source.name} [${ua.take(20)}] → 200 mais 0 chaînes parsées sur $url")
                    }
                } catch (e: Exception) {
                    val msg = (e.message ?: "(no msg)").take(120).replace("\n", "↵")
                    Log.w(TAG, "M3U ${source.name} [${ua.take(20)}] échec $url: $msg")
                    lastError = e
                }
            }
        }
        val lastMsg = (lastError?.message ?: "all attempts failed").take(120).replace("\n", "↵")
        Log.e(TAG, "M3U fetch ${source.name}: tous UA × URL ont foiré. Last: $lastMsg")
        emptyList()
    }

    /** 2026-05-12 (OOM fix) : fetch + redirect manuel + parse streaming en 1 fonction.
     *  Au lieu de charger toute la M3U en RAM (35MB+ → OOM Chromecast), on stream
     *  directement la response body via BufferedReader vers le parser ligne-par-ligne.
     *
     *  Retourne List<M3uChannel> si succès, null si fetch/redirect a foiré. */
    private fun fetchAndStreamParseM3u(initialUrl: String, ua: String, referer: String?): List<M3uParser.M3uChannel>? {
        var currentUrl = initialUrl
        for (hop in 0 until 5) {
            val req = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .apply {
                    if (!referer.isNullOrBlank()) header("Referer", referer)
                }
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body
                if (body == null) {
                    resp.close()
                    return null
                }
                // Stream-parse via BufferedReader. parseStream() ferme le reader
                // via .use{} interne ; ça ferme aussi le body et la response.
                return M3uParser.parseStream(body.charStream().buffered())
            }
            if (resp.code in 300..399) {
                val rawLoc = resp.header("Location")
                resp.close()
                if (rawLoc == null) {
                    Log.w(TAG, "Redirect ${resp.code} sans Location header sur $currentUrl")
                    return null
                }
                // Fix les ports malformés : `host:"8080` → `host:8080`
                val fixedLoc = rawLoc.replace(Regex(""":(?:["'`])(\d+)"""), ":$1")
                    .replace(Regex("""(\d+)(?:["'`])"""), "$1")
                    .trim()
                currentUrl = try {
                    if (fixedLoc.startsWith("http://", true) || fixedLoc.startsWith("https://", true)) {
                        fixedLoc
                    } else {
                        java.net.URI(currentUrl).resolve(fixedLoc).toString()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Redirect Location invalide '$rawLoc' (fixé: '$fixedLoc'): ${e.message}")
                    return null
                }
                Log.d(TAG, "Redirect hop $hop: $rawLoc → $currentUrl")
                continue
            }
            // 4xx/5xx
            Log.w(TAG, "HTTP ${resp.code} sur $currentUrl")
            resp.close()
            return null
        }
        Log.w(TAG, "Trop de redirects pour $initialUrl")
        return null
    }

    /** 2026-05-12 : fetch d'un portail Stalker MAG. Délégué à StalkerClient. */
    private suspend fun fetchStalker(source: IptvSource): List<M3uParser.M3uChannel> = withContext(Dispatchers.IO) {
        val mac = source.mac?.takeIf { it.isNotBlank() } ?: run {
            Log.e(TAG, "Stalker ${source.name} : MAC manquante")
            return@withContext emptyList()
        }
        val Stalker = com.streamflixreborn.streamflix.utils.StalkerClient
        val portalUrl = sanitizeUrl(source.url)
        // 2026-05-12 (user "Des numéros c'est pas parlant") : fetch get_genres pour
        // mapper les category_id (numériques) → noms friendly ("FR| GENERAL" etc.)
        val genres = Stalker.getGenres(portalUrl, mac, source.userAgent, source.serial)
        val stalkerChannels = Stalker.getAllChannels(
            portalUrl = portalUrl,
            mac = mac,
            userAgent = source.userAgent,
            serial = source.serial,
        )
        Log.d(TAG, "Stalker ${source.name} : ${stalkerChannels.size} chaînes, ${genres.size} genres mapped")
        stalkerChannels.map { sc ->
            // Resolve le category_id numérique en nom friendly si possible
            val friendlyGroup = sc.group?.let { genres[it] ?: it }
            M3uParser.M3uChannel(
                name = sc.name,
                url = sc.cmd,
                logo = sc.logo,
                group = friendlyGroup,
                tvgId = sc.tvgId,
                options = mapOf("stalker-cmd" to sc.cmd, "stalker-mac" to mac, "stalker-portal" to source.url),
            )
        }
    }

    /** Construit la liste des URLs à essayer dans l'ordre, en corrigeant les
     *  mismatch protocole/port classiques :
     *  - `https://host:80/...` → essaie aussi `http://host:80/...`
     *  - `http://host:443/...` → essaie aussi `https://host:443/...`
     *  - `https://host:8080/...` → essaie aussi `http://host:8080/...`
     *  Toujours essaie l'URL originale en premier (au cas où elle marche). */
    private fun buildCandidateUrls(url: String): List<String> {
        val candidates = mutableListOf(url)
        val uri = try { java.net.URI(url) } catch (_: Exception) { return candidates }
        val port = uri.port
        val scheme = uri.scheme?.lowercase()

        // HTTPS sur port HTTP-typique (80, 8080, 8000, 8081) → essaie HTTP
        if (scheme == "https" && port in listOf(80, 8080, 8000, 8081, 8888)) {
            candidates.add(url.replaceFirst("https://", "http://"))
        }
        // HTTP sur port HTTPS-typique (443, 8443) → essaie HTTPS
        if (scheme == "http" && port in listOf(443, 8443)) {
            candidates.add(url.replaceFirst("http://", "https://"))
        }
        return candidates
    }

    /** Invalide le cache d'une source (refresh manuel). Efface aussi le disque. */
    fun invalidateCache(sourceId: String? = null) {
        if (sourceId != null) {
            cache.remove(sourceId)
            classificationCache.remove(sourceId)
            try { cacheFile(sourceId)?.delete() } catch (_: Exception) {}
        } else {
            cache.clear()
            classificationCache.clear()
            try {
                val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
                java.io.File(ctx.cacheDir, "iptv").listFiles()?.forEach { it.delete() }
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //   NON IMPLÉMENTÉ
    // ═══════════════════════════════════════════════════════════════

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = emptyList()
    override suspend fun getGenre(id: String, page: Int): Genre = throw UnsupportedOperationException()
    override suspend fun getPeople(id: String, page: Int): People = throw UnsupportedOperationException()
}
