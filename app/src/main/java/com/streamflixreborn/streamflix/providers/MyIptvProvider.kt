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

    // 2026-05-17 v60 (user "savoir à combien de % on est du chargement") :
    //   StateFlow public pour le statut de chargement de la source IPTV
    //   (Stalker, M3U, Xtream). L'UI IptvSourcesActivity le collecte et
    //   met à jour le loadingText avec le step en cours (chaînes, films,
    //   séries). Vide quand pas de chargement en cours.
    val loadingStatus: kotlinx.coroutines.flow.MutableStateFlow<String> =
        kotlinx.coroutines.flow.MutableStateFlow("")

    /** Cache des channels parsées par sourceId. TTL 30min. */
    private const val CACHE_TTL_MS = 30L * 60L * 1000L
    private data class CachedChannels(
        val channels: List<M3uParser.M3uChannel>,
        val expiresAtMs: Long,
    )
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedChannels>()

    /** 2026-05-18 v85 : vide le cache parsé + classification (anti-OOM).
     *  Aussi appelé par IptvCacheManager quand on switch de provider IPTV. */
    fun clearCache() {
        try {
            val count = cache.size
            val classifCount = classificationCache.size
            cache.clear()
            classificationCache.clear()
            // Reset filtres user (sinon on garde un filtre catégorie qui ne match plus la nouvelle source)
            selectedCategoryLive = null
            selectedCategoryMovie = null
            selectedCategorySeries = null
            android.util.Log.d(TAG, "clearCache: MyIPTV vidé ($count parsed + $classifCount classified)")
        } catch (_: Throwable) {}
    }

    /** 2026-05-19 v85b (user "à partir du moment où on change de source il faut
     *  faire un petit lavage qu'on retourne pas sur les anciennes qui étaient
     *  sur une autre source") : vide TOUTES les sources sauf [activeSourceId].
     *  Appelé par IptvSourcesActivity au moment où l'user clique une nouvelle
     *  source dans le tableau — garantit que les chaînes/films/séries de la
     *  source précédente n'apparaissent plus dans les rails Home/Catalogue
     *  pendant et après la transition. */
    fun clearChannelsExcept(activeSourceId: String) {
        try {
            val toRemove = cache.keys.filter { it != activeSourceId }
            val toRemoveClassif = classificationCache.keys.filter { it != activeSourceId }
            toRemove.forEach { cache.remove(it) }
            toRemoveClassif.forEach { classificationCache.remove(it) }
            selectedCategoryLive = null
            selectedCategoryMovie = null
            selectedCategorySeries = null
            android.util.Log.d(
                TAG,
                "clearChannelsExcept($activeSourceId): purge ${toRemove.size} parsed + ${toRemoveClassif.size} classified"
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "clearChannelsExcept failed: ${t.message}")
        }
    }

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
    /** 2026-05-13 (user "quand tu changes de source ils affichent les mêmes
     *  chaînes en nombre en film et en série") : ajout du paramètre `sourceId`
     *  pour ne compter QUE cette source-là. Si null = toutes les sources cumulées
     *  (comportement original). Permet à IptvSourcesActivity d'afficher un toast
     *  spécifique à la source qu'on vient d'ajouter, pas le total cumulé. */
    suspend fun countByTypeFR(
        sourceId: String? = null,
    ): Triple<Int, Int, Int> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // 2026-05-13 (bug "0 TV 0 films 0 séries malgré 158 chaînes chargées") :
        // loadChannels seul ne classifie PAS, il faut appeler collectByType (ou
        // équivalent) pour peupler classificationCache. On déclenche les 3 types.
        val sources = if (sourceId != null) {
            IptvSourceStore.getAll().filter { it.id == sourceId }
        } else {
            IptvSourceStore.getAll()
        }
        for (s in sources) {
            if (classificationCache[s.id] == null) {
                val channels = loadChannels(s)
                if (channels.isNotEmpty()) {
                    // 2026-05-13 : essaie d'abord le cache classification disque
                    val diskCached = loadClassificationFromDisk(s, channels)
                    classificationCache[s.id] = diskCached ?: run {
                        val byType = mutableMapOf<IptvClassifier.ContentType, MutableList<ClassifiedItem>>()
                        channels.forEachIndexed { idx, ch ->
                            val t = IptvClassifier.classify(ch)
                            byType.getOrPut(t) { mutableListOf() }.add(ClassifiedItem(s, ch, idx, t))
                        }
                        val cc = CachedClassification(byType)
                        saveClassificationToDisk(s.id, channels.size, byType)
                        cc
                    }
                }
            }
        }
        var live = 0; var movie = 0; var series = 0
        // 2026-05-13 : itère uniquement sur les sources demandées, pas tout
        // classificationCache.values (qui peut contenir d'autres sources).
        for (s in sources) {
            val cached = classificationCache[s.id] ?: continue
            cached.byType[IptvClassifier.ContentType.LIVE]?.let { items ->
                live += filterFrOrFallback(items).size
            }
            cached.byType[IptvClassifier.ContentType.MOVIE]?.let { items ->
                movie += filterFrOrFallback(items).size
            }
            cached.byType[IptvClassifier.ContentType.SERIES]?.let { items ->
                series += filterFrOrFallback(items).size
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
            val items = cached.byType[type] ?: continue
            // Applique le même filter FR-ou-fallback que collectByType
            val toCount = filterFrOrFallback(items)
            toCount.forEach { item ->
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

    /** 2026-05-13 (user "traduit l'anglais des catégories") : traduit les noms
     *  de catégories anglais courants (iptv-org, etc.) en français pour
     *  affichage. Le nom RAW reste utilisé comme filtre — c'est seulement
     *  l'affichage qui est traduit. Les noms non mappés sont retournés tels
     *  quels (e.g. noms perso d'un Xtream Codes user). */
    private val CATEGORY_FR_MAP = mapOf(
        "general" to "Général",
        "news" to "Actualités",
        "undefined" to "Non classés",
        "music" to "Musique",
        "entertainment" to "Divertissement",
        "movies" to "Films",
        "kids" to "Enfants",
        "lifestyle" to "Mode de vie",
        "sports" to "Sport",
        "series" to "Séries",
        "documentary" to "Documentaire",
        "animation" to "Animation",
        "religious" to "Religieux",
        "legislative" to "Législatif",
        "education" to "Éducation",
        "auto" to "Auto",
        "comedy" to "Comédie",
        "culture" to "Culture",
        "business" to "Économie",
        "science" to "Science",
        "travel" to "Voyage",
        "relax" to "Détente",
        "interactive" to "Interactif",
        "health" to "Santé",
        "weather" to "Météo",
        "shop" to "Téléachat",
        "outdoor" to "Plein air",
        "family" to "Famille",
        "history" to "Histoire",
        "cooking" to "Cuisine",
        "regional" to "Régional",
        "local" to "Local",
        "autres" to "Autres",
    )

    fun prettyCategoryName(raw: String): String {
        if (raw.isBlank()) return "Autres"
        // 2026-05-13 : gère les noms multi-tags séparés par ';' (e.g.
        // "News;Sports" → "Actualités · Sport").
        return raw.split(';').joinToString(" · ") { part ->
            val key = part.trim().lowercase()
            CATEGORY_FR_MAP[key] ?: part.trim()
        }
    }

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
            .callTimeout(90, TimeUnit.SECONDS)
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
        val sources = IptvSourceStore.getActiveOrAll()
        if (sources.isEmpty()) {
            return@withContext listOf(Category(
                name = "Aucune source IPTV — va dans Paramètres → Paramètres du fournisseur → Mon IPTV pour en ajouter une",
                list = emptyList(),
            ))
        }

        val (liveItems, errors) = collectByType(sources, IptvClassifier.ContentType.LIVE)
        val sections = mutableListOf<Category>()
        errors.forEach { sections.add(Category(name = it, list = emptyList())) }

            // ─── ★ Favoris EN TÊTE ───
            // 2026-05-28 : section "Favoris" pour que IptvFavoritesMobileFragment /
            // IptvFavoritesTvFragment trouvent les chaînes favorites via getHome().
            try {
                val favKeys = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                    .getAllCanonicalFavorites()
                Log.d(TAG, "getHome Favoris: favKeys=$favKeys liveItems=${liveItems.size}")
                if (favKeys.isNotEmpty() && liveItems.isNotEmpty()) {
                    val favItems = liveItems.filter { item ->
                        val key = normalizeChannelKey(item.channel.name)
                        val match = key in favKeys
                        if (match) Log.d(TAG, "getHome Favoris MATCH: '${item.channel.name}' → key='$key'")
                        match
                    }.map { itemToTvShowLive(it) }
                    Log.d(TAG, "getHome Favoris: ${favItems.size} matches")
                    if (favItems.isNotEmpty()) {
                        sections.add(Category(name = "Favoris", list = favItems))
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "getHome Favoris ERROR", t)
            }
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
            val titlePrefix = if (selected != null) "📡 ${prettyCategoryName(selected)}" else {
                // 2026-05-13 : titre dynamique selon le filtre langue.
                when (getLanguageFilterMode()) {
                    "all" -> "📡 Toutes les chaînes"
                    "fr"  -> "📡 Toutes les chaînes FR"
                    else  -> "📡 Toutes les chaînes" // "auto" : on ne sait pas ce qui a été filtré, label neutre
                }
            }
            // 2026-05-13 (user "je croyais que tu voulais me faire un layout
            // descendant pour éviter de défiler à l'infini, on a de la place
            // encore en bas") : quand l'user a filtré sur UNE catégorie précise
            // (FRANCE SD, BEIN SPORT, etc.) avec un nombre raisonnable de
            // chaînes, on chunke en rangées de 8 → grille verticale qui remplit
            // l'écran. Au-delà de ~200 chaînes ou si pas de filtre, on garde
            // 1 row horizontale infinie pour éviter le freeze Chromecast
            // (cf l'incident 511 rows × 8 tiles = OOM).
            val rowSize = 8
            // Cap à 2400 chaînes (= 300 rows) pour éviter le freeze Chromecast
            // sur les bouquets énormes (4000+ chaînes). Le user a typiquement
            // ~1700 chaînes FR, ça passe largement. Au-delà, fallback à 1 row
            // infinie qui scroll horizontalement.
            val maxChunkedChannels = 2400
            val shouldChunk = filtered.size <= maxChunkedChannels
            if (shouldChunk && filtered.isNotEmpty()) {
                val tvShows = filtered.map { itemToTvShowLive(it) }
                tvShows.chunked(rowSize).forEachIndexed { idx, chunk ->
                    // 2026-05-13 (user "vire ça de mon IPTV" : la swiper FEATURED
                    // avec "Série" / "Regarder maintenant" / dots apparaissait
                    // sur les chunks de rangée 2+. Cause : Category.FEATURED ==
                    // "" donc nom vide trigger le rendu swiper. On utilise un
                    // espace invisible (caractère zero-width) pour les rangées
                    // suivantes — non-vide donc pas FEATURED, mais visuellement
                    // pas de header rendu.
                    sections.add(Category(
                        name = if (idx == 0) "$titlePrefix (${filtered.size})" else "​",
                        list = chunk,
                    ))
                }
            } else {
                sections.add(Category(
                    name = "$titlePrefix (${filtered.size})",
                    list = filtered.map { itemToTvShowLive(it) },
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
        val sources = IptvSourceStore.getActiveOrAll()
        val (allMovies, _) = collectByType(sources, IptvClassifier.ContentType.MOVIE)
        // 2026-05-13 (user "quand on change de catégorie le home ne change pas") :
        // applique le filtre selectedCategoryMovie. Sans ça, le picker change le
        // state mais getMovies() retourne tous les films, le picker semble cassé.
        val selected = selectedCategoryMovie
        val filtered = if (selected != null) {
            allMovies.filter { (it.channel.group ?: "").trim() == selected }
        } else allMovies
        // Tri alphabétique par nom puis pagination
        val sorted = filtered.sortedBy { it.channel.name.lowercase() }
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
        val sources = IptvSourceStore.getActiveOrAll()
        val (allEpisodes, _) = collectByType(sources, IptvClassifier.ContentType.SERIES)
        // 2026-05-13 (user "quand on change de catégorie le home ne change pas") :
        // applique le filtre selectedCategorySeries avant le groupage par show.
        val selected = selectedCategorySeries
        val filtered = if (selected != null) {
            allEpisodes.filter { (it.channel.group ?: "").trim() == selected }
        } else allEpisodes
        // Grouper par nom de show + tri alpha
        val byShow = filtered.groupBy { IptvClassifier.extractShowName(it.channel.name) }
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

    /** 2026-05-15 (user "il affiche pas le nombre d'épisodes exactes l'épisode
     *  est de saison") : pour les séries Stalker (xtream-type="series"), on
     *  drill-down via StalkerClient.getSeasons() pour récupérer les VRAIES
     *  saisons et épisodes côté serveur. Avant, on extrayait SXXEXX du nom
     *  via regex, mais Stalker retourne 1 ENTRÉE PAR SÉRIE (pas par épisode)
     *  → on tombait sur 1 fake épisode en Saison 1.
     *
     *  Helper : extrait l'id numérique de la série depuis le stalker-cmd.
     *  Retourne null si pas extractible (cmd non standard ou base64 sans id). */
    private fun extractStalkerSeriesId(cmd: String): String? {
        val stripped = cmd.removePrefix("series-cmd::").removePrefix("series::")
        val id = stripped.substringBefore(":")
        return id.takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
    }

    override suspend fun getTvShow(id: String): TvShow = withContext(Dispatchers.IO) {
        // 2026-05-12 : ArtworkRepair iterate sur tous les TvShows en DB, y compris
        // les chaînes Live (id `myiptv-live::`) qui ne sont PAS des séries. On
        // renvoie un TvShow stub pour éviter le spam d'exceptions dans le logcat.
        if (id.startsWith("myiptv-live::")) {
            return@withContext TvShow(id = id, title = "Live")
        }
        val showName = decodeId(id.removePrefix("myiptv-show::"))
        val sources = IptvSourceStore.getActiveOrAll()
        val (allEpisodes, _) = collectByType(sources, IptvClassifier.ContentType.SERIES)
        val matchingEpisodes = allEpisodes.filter {
            IptvClassifier.extractShowName(it.channel.name).equals(showName, ignoreCase = true)
        }
        if (matchingEpisodes.isEmpty()) throw Exception("Série '$showName' introuvable")

        // 2026-05-15 : drill-down Stalker pour vrais saisons/épisodes.
        val firstItem = matchingEpisodes.first()
        val firstCh = firstItem.channel
        val isStalkerSeries = firstCh.options["xtream-type"] == "series" &&
            firstCh.options.containsKey("stalker-cmd")
        if (isStalkerSeries) {
            val seriesId = extractStalkerSeriesId(firstCh.options["stalker-cmd"] ?: "")
            if (seriesId != null) {
                Log.d(TAG, "getTvShow Stalker drill-down : $showName seriesId=$seriesId")
                val seasons = runCatching {
                    com.streamflixreborn.streamflix.utils.StalkerClient.getSeasons(
                        portalUrl = firstItem.source.url,
                        mac = firstItem.source.mac ?: "",
                        seriesNumericId = seriesId,
                        userAgent = firstItem.source.userAgent,
                    )
                }.getOrElse {
                    Log.w(TAG, "getTvShow Stalker getSeasons fail: ${it.message}")
                    emptyList()
                }
                if (seasons.isNotEmpty()) {
                    // 2026-05-15 (user "cliquer Regarder S1 E1 lance pas la lecture
                    // direct, suivant/précédent puis ça marche") : il faut populer
                    // season.episodes avec les vrais Episode objects, sinon
                    // tvShow.episodeToWatch (qui dérive de seasons.flatMap{episodes})
                    // retourne null → bouton invisible OU avec un mauvais id (qui
                    // tombe sur handleDirectPlay → tvShow.id passe au player →
                    // myiptv-show:: ne match aucun case getServers → écran noir).
                    // Maintenant, chaque Season a sa liste Episode prête à être
                    // jouée directement.
                    val seasonObjs = seasons.map { season ->
                        val episodeObjs = season.episodeNumbers.map { epNum ->
                            Episode(
                                id = "myiptv-stalkerep::${firstItem.source.id}::${firstItem.index}::${season.seasonNumber}::$epNum",
                                number = epNum,
                                title = "Épisode $epNum",
                                poster = season.poster ?: firstCh.logo,
                            )
                        }
                        Season(
                            id = "myiptv-season::${encodeId(showName)}::${season.seasonNumber}",
                            number = season.seasonNumber,
                            title = season.name,
                            episodes = episodeObjs,
                        )
                    }
                    val totalEps = seasons.sumOf { it.episodeNumbers.size }
                    Log.d(TAG, "getTvShow Stalker '$showName' → ${seasons.size} saisons, $totalEps épisodes (Episode list popule)")
                    return@withContext TvShow(
                        id = id,
                        title = showName,
                        overview = "$totalEps épisode${if (totalEps > 1) "s" else ""} sur ${seasons.size} saison${if (seasons.size > 1) "s" else ""}",
                        poster = firstCh.logo,
                        banner = firstCh.logo,
                        seasons = seasonObjs,
                    )
                }
            }
        }

        // Fallback : extraction regex SXXEXX depuis le nom (pour les sources
        // M3U où chaque épisode = 1 ligne, donc la regex SXXEXX a du sens).
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

        val sources = IptvSourceStore.getActiveOrAll()
        val (allEpisodes, _) = collectByType(sources, IptvClassifier.ContentType.SERIES)
        val matching = allEpisodes.filter {
            IptvClassifier.extractShowName(it.channel.name).equals(showName, ignoreCase = true)
        }
        if (matching.isEmpty()) return@withContext emptyList()

        // 2026-05-15 : drill-down Stalker pour vrais épisodes (cf getTvShow).
        val firstItem = matching.first()
        val firstCh = firstItem.channel
        val isStalkerSeries = firstCh.options["xtream-type"] == "series" &&
            firstCh.options.containsKey("stalker-cmd")
        if (isStalkerSeries) {
            val seriesId = extractStalkerSeriesId(firstCh.options["stalker-cmd"] ?: "")
            if (seriesId != null) {
                val seasons = runCatching {
                    com.streamflixreborn.streamflix.utils.StalkerClient.getSeasons(
                        portalUrl = firstItem.source.url,
                        mac = firstItem.source.mac ?: "",
                        seriesNumericId = seriesId,
                        userAgent = firstItem.source.userAgent,
                    )
                }.getOrElse { emptyList() }
                val season = seasons.firstOrNull { it.seasonNumber == seasonNum }
                if (season != null) {
                    Log.d(TAG, "getEpisodesBySeason Stalker '$showName' S$seasonNum → ${season.episodeNumbers.size} épisodes")
                    return@withContext season.episodeNumbers.map { epNum ->
                        Episode(
                            // Format: myiptv-stalkerep::sourceId::channelIdx::seasonNum::epNum
                            // → getServers/getVideo l'identifie et drill-down à nouveau
                            //   au moment du play pour récupérer le seasonCmd à jour.
                            id = "myiptv-stalkerep::${firstItem.source.id}::${firstItem.index}::$seasonNum::$epNum",
                            number = epNum,
                            title = "Épisode $epNum",
                            poster = season.poster ?: firstCh.logo,
                        )
                    }.sortedBy { it.number }
                }
            }
        }

        // Fallback regex (M3U sources).
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
        // 2026-05-15 : nouveau format pour les épisodes Stalker drill-down :
        // myiptv-stalkerep::sourceId::channelIdx::seasonNum::epNum
        // → encode dans le server id un suffixe ::s<N>::e<N> que getVideo parsera.
        if (id.startsWith("myiptv-stalkerep::")) {
            val p = id.removePrefix("myiptv-stalkerep::").split("::")
            if (p.size != 4) return emptyList()
            val sourceId = p[0]
            val channelIdx = p[1].toIntOrNull() ?: return emptyList()
            val seasonNum = p[2].toIntOrNull() ?: return emptyList()
            val epNum = p[3].toIntOrNull() ?: return emptyList()
            val source = IptvSourceStore.getById(sourceId) ?: return emptyList()
            return listOf(
                Video.Server(
                    id = "myiptv-stream::${source.id}::$channelIdx::s$seasonNum::e$epNum",
                    name = source.name,
                )
            )
        }
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
        if (parts.size < 2) throw Exception("Bad server id: ${server.id}")
        val sourceId = parts[0]
        val channelIdx = parts[1].toIntOrNull() ?: throw Exception("Bad channel idx")
        // 2026-05-15 : support du suffixe ::s<N>::e<N> pour les épisodes Stalker
        // drill-down. Format attendu : myiptv-stream::sourceId::chIdx::sN::eN
        val explicitSeasonNum = parts.getOrNull(2)?.takeIf { it.startsWith("s") }?.removePrefix("s")?.toIntOrNull()
        val explicitEpNum = parts.getOrNull(3)?.takeIf { it.startsWith("e") }?.removePrefix("e")?.toIntOrNull()
        val source = IptvSourceStore.getById(sourceId) ?: throw Exception("Source disparue")
        val channels = loadChannels(source)
        val channel = channels.getOrNull(channelIdx) ?: throw Exception("Chaîne #$channelIdx introuvable")

        val headers = mutableMapOf<String, String>()
        source.userAgent?.let { headers["User-Agent"] = it }
        source.referer?.let { headers["Referer"] = it }
        channel.options["http-user-agent"]?.let { headers["User-Agent"] = it }
        channel.options["http-referrer"]?.let { headers["Referer"] = it }

        // 2026-05-12 : Stalker → résoudre l'URL réelle via create_link au moment du play
        // 2026-05-14 (user "j'arrive pas à lire mes épisodes de série") : pour
        // les Séries Stalker (cmd vide ou "series::id"), fetch d'abord les
        // SAISONS puis joue saison 1 / épisode 1 (v1 — sans picker UI).
        val streamUrl = if (source.type == IptvSource.Type.STALKER && channel.options.containsKey("stalker-cmd")) {
            val cmd = channel.options["stalker-cmd"] ?: ""
            val xtreamType = channel.options["xtream-type"] ?: "?"
            // 2026-05-15 : détection robuste des prefixes — l'ordre de check
            // compte ! "series-cmd::" doit être checké AVANT "series::" sinon
            // removePrefix("series::") ne fait rien sur "series-cmd::"
            val isStalkerSeries = cmd.startsWith("series-cmd::") || cmd.startsWith("series::")
            val isStalkerVod = cmd.startsWith("vod-cmd::") || cmd.startsWith("vod::")
            Log.d(TAG, "getVideo Stalker '${channel.name}' : type=$xtreamType cmd=${cmd.take(60)}… (series=$isStalkerSeries, vod=$isStalkerVod)")
            val resolved = withContext(Dispatchers.IO) {
                if (isStalkerSeries) {
                    // 2026-05-15 (user FoxBleu) : extraire l'id numérique en
                    // strippant les DEUX prefixes possibles. Avant, seul
                    // "series::" était stripé → "series-cmd::base64..." restait
                    // et substringBefore(":") retournait "series-cmd" → drill-down KO.
                    val stripped = cmd.removePrefix("series-cmd::").removePrefix("series::")
                    val seriesId = stripped.substringBefore(":")
                    if (seriesId.isBlank() || !seriesId.all { it.isDigit() }) {
                        Log.w(TAG, "getVideo SERIES : seriesId='$seriesId' invalide (pas un nombre) — drill-down impossible")
                        return@withContext null
                    }
                    Log.d(TAG, "getVideo Stalker SERIES drill-down : seriesId=$seriesId, target S${explicitSeasonNum ?: "?"}E${explicitEpNum ?: "?"}")
                    val seasons = com.streamflixreborn.streamflix.utils.StalkerClient.getSeasons(
                        portalUrl = source.url,
                        mac = source.mac ?: "",
                        seriesNumericId = seriesId,
                        userAgent = source.userAgent,
                    )
                    if (seasons.isEmpty()) {
                        Log.w(TAG, "getVideo SERIES : aucune saison Stalker pour seriesId=$seriesId")
                        return@withContext null
                    }
                    // 2026-05-15 (user "il affiche pas le nombre d'épisodes exactes
                    // l'épisode est de saison") : utiliser le seasonNum + epNum
                    // EXPLICITES depuis le server.id si présent (route normale
                    // depuis getEpisodesBySeason → myiptv-stalkerep), sinon
                    // fallback sur S1E1 (route legacy depuis myiptv-ep ou
                    // myiptv-show direct).
                    val season = explicitSeasonNum?.let { sn -> seasons.firstOrNull { it.seasonNumber == sn } }
                        ?: seasons.first()
                    val episode = explicitEpNum ?: season.episodeNumbers.firstOrNull() ?: 1
                    Log.d(TAG, "getVideo SERIES : play saison ${season.seasonNumber} épisode $episode (cmd=${season.cmd.take(40)}…)")
                    val r = com.streamflixreborn.streamflix.utils.StalkerClient.createStreamLinkSeriesEpisode(
                        portalUrl = source.url,
                        mac = source.mac ?: "",
                        seasonCmd = season.cmd,
                        episodeNumber = episode,
                        userAgent = source.userAgent,
                    )
                    Log.d(TAG, "getVideo SERIES : createStreamLinkSeriesEpisode → ${r?.take(80) ?: "NULL"}")
                    r
                } else {
                    val r = com.streamflixreborn.streamflix.utils.StalkerClient.createStreamLink(
                        portalUrl = source.url,
                        mac = source.mac ?: "",
                        cmd = cmd.ifBlank { channel.url },
                        userAgent = source.userAgent,
                    )
                    Log.d(TAG, "getVideo ${if (isStalkerVod) "VOD" else "LIVE"} : createStreamLink → ${r?.take(80) ?: "NULL"}")
                    r
                }
            }
            resolved ?: throw Exception("Stalker create_link a échoué pour ${channel.name} (type=$xtreamType, cmd-prefix=${cmd.substringBefore("::").take(20)})")
        } else {
            channel.url
        }

        // 2026-05-13 (user "fonctionne en plein écran mais pas en mini player" —
        // le mini-player démarre lentement parce qu'ExoPlayer auto-détecte le format
        // depuis le contenu. On lui donne explicitement le MimeType pour skip cette
        // étape et démarrer du 1er coup. */
        val mimeType = when {
            streamUrl.contains(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
            streamUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
            streamUrl.contains(".ts", ignoreCase = true) -> "video/mp2t"
            streamUrl.contains(".mp4", ignoreCase = true) -> "video/mp4"
            streamUrl.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
            else -> null
        }
        return Video(
            source = streamUrl,
            type = mimeType,
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
                var classified = classificationCache[source.id]
                if (classified == null) {
                    // 2026-05-13 (user "Big lag sur Chromecast") : tente le cache
                    // disque AVANT de re-classifier. La classification de 4088
                    // chaînes prend 2-3s sur ARM, donc on persiste pour cold
                    // starts rapides.
                    val channels = loadChannels(source)
                    if (channels.isEmpty()) {
                        errors.add("⚠ '${source.name}' : aucune chaîne (URL invalide ?)")
                        continue
                    }
                    val diskCached = loadClassificationFromDisk(source, channels)
                    classified = diskCached ?: run {
                        // Classifie une fois pour tous types et cache
                        val byType = mutableMapOf<IptvClassifier.ContentType, MutableList<ClassifiedItem>>()
                        channels.forEachIndexed { idx, ch ->
                            val t = IptvClassifier.classify(ch)
                            val item = ClassifiedItem(source, ch, idx, t)
                            byType.getOrPut(t) { mutableListOf() }.add(item)
                        }
                        val cc = CachedClassification(byType)
                        saveClassificationToDisk(source.id, channels.size, byType)
                        cc
                    }
                    classificationCache[source.id] = classified
                }
                if (classified != null) {
                    classified.byType[type]?.let { items -> all.addAll(filterFrOrFallback(items)) }
                    continue
                }
                // Code legacy (now dead but kept for safety) — should not reach here
                val channels = loadChannels(source)
                val byType = mutableMapOf<IptvClassifier.ContentType, MutableList<ClassifiedItem>>()
                channels.forEachIndexed { idx, ch ->
                    val t = IptvClassifier.classify(ch)
                    val item = ClassifiedItem(source, ch, idx, t)
                    byType.getOrPut(t) { mutableListOf() }.add(item)
                }
                classificationCache[source.id] = CachedClassification(byType)
                // 2026-05-13 (debug user "19/158 chaînes c'est trop peu") : log breakdown
                val nLive = byType[IptvClassifier.ContentType.LIVE]?.size ?: 0
                val nMovie = byType[IptvClassifier.ContentType.MOVIE]?.size ?: 0
                val nSeries = byType[IptvClassifier.ContentType.SERIES]?.size ?: 0
                Log.d(TAG, "collectByType '${source.name}': total=${channels.size} → LIVE=$nLive MOVIE=$nMovie SERIES=$nSeries")
                // Log les 5 premiers MOVIE pour voir pourquoi ils sont classifiés ainsi
                byType[IptvClassifier.ContentType.MOVIE]?.take(5)?.forEach { item ->
                    Log.d(TAG, "  → MOVIE: name='${item.channel.name}' group='${item.channel.group}' url='${item.channel.url.take(80)}'")
                }
                byType[IptvClassifier.ContentType.SERIES]?.take(5)?.forEach { item ->
                    Log.d(TAG, "  → SERIES: name='${item.channel.name}' group='${item.channel.group}' url='${item.channel.url.take(80)}'")
                }
                byType[type]?.let { items ->
                    val frFiltered = filterFrOrFallback(items)
                    Log.d(TAG, "collectByType '$type' fr-filter: ${items.size} → ${frFiltered.size}")
                    if (items.size > frFiltered.size && frFiltered.size < items.size) {
                        // Log les items rejetés par le filtre FR
                        val rejected = items.filterNot { isFrenchChannel(it.channel) }.take(10)
                        rejected.forEach { item ->
                            Log.d(TAG, "  ✗ FR-rejected: name='${item.channel.name}' group='${item.channel.group}'")
                        }
                    }
                    all.addAll(frFiltered)
                }
            } catch (e: Exception) {
                errors.add("❌ '${source.name}' : ${e.message?.take(80)}")
            }
        }
        return all to errors
    }

    /** 2026-05-13 (user "ça serait bien comme ça on peut avoir vraiment tout le
     *  contenu") : mode du filtre langue — défini dans Paramètres → Mon IPTV
     *  → Filtrer par langue, ou via le bouton globe du home.
     *  - "auto" : comportement smart (seuil 50%) — défaut
     *  - "all"  : pas de filtre, montre tout
     *  - "fr"   : strict FR uniquement (cache les étrangères même si <50%) */
    fun getLanguageFilterMode(): String {
        return try {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString("pref_iptv_language_filter", "auto") ?: "auto"
        } catch (_: Throwable) { "auto" }
    }

    fun setLanguageFilterMode(mode: String) {
        try {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putString("pref_iptv_language_filter", mode).apply()
        } catch (_: Throwable) {}
    }

    /** 2026-05-12 (user iptv-org/fr.m3u test : 158 chaînes FR avec group-title en
     *  anglais "Entertainment", "News", etc. → filtre FR rejette tout) :
     *  intelligent filter — applique le filtre FR. Si le résultat matche peu
     *  d'items (<50%), c'est qu'on est sur un M3U déjà curé par région (genre
     *  iptv-org/countries/fr.m3u où les channels n'ont pas "France" dans le
     *  nom car le fichier ENTIER est français) → renvoie tous les items sans
     *  filtre.
     *
     *  2026-05-13 (bug "19/158 chaînes") : seuil 0 trop strict — iptv-org/fr.m3u
     *  a ~18 channels avec "France" dans le nom (sur 158), donc fallback ne se
     *  déclenche pas et on perd 139 chaînes. Nouveau seuil : si <50% matchent,
     *  fallback. Le filtre FR reste utile pour les gros Xtream Codes multi-pays
     *  où >50% des channels ont "FR|" en préfixe explicite.
     *
     *  2026-05-13 (user "comme ça on peut avoir vraiment tout le contenu") :
     *  override via pref `pref_iptv_language_filter` : "all" = bypass total,
     *  "fr" = FR strict (même <50%), "auto" = comportement par défaut ci-dessus. */
    private fun filterFrOrFallback(items: List<ClassifiedItem>): List<ClassifiedItem> {
        if (items.isEmpty()) return items
        when (getLanguageFilterMode()) {
            "all" -> {
                Log.d(TAG, "Filtre langue = 'all' (toutes langues) — bypass filtre FR")
                return items
            }
            "fr" -> {
                val filtered = items.filter { isFrenchChannel(it.channel) }
                // 2026-05-13 (user "pas de film ni de série chargé") : si le
                // mode strict retourne 0 alors qu'il y a des items, c'est
                // probablement une catégorie VOD/Série Stalker dont les noms
                // n'ont pas de marqueur "FR" (déjà filtrés par catégorie
                // côté serveur). Fallback à tous les items pour pas masquer
                // tout le contenu.
                if (filtered.isEmpty() && items.isNotEmpty()) {
                    Log.d(TAG, "Filtre langue = 'fr' (strict) — 0/${items.size} match, fallback tout (catégorie déjà filtrée par source)")
                    return items
                }
                Log.d(TAG, "Filtre langue = 'fr' (strict) — ${filtered.size}/${items.size} chaînes FR")
                return filtered
            }
            // "auto" → comportement smart (logique 50% threshold)
        }
        val filtered = items.filter { isFrenchChannel(it.channel) }
        val frRatio = filtered.size.toDouble() / items.size
        return if (frRatio < 0.5) {
            Log.d(TAG, "Filtre FR matche peu (${filtered.size}/${items.size} = ${(frRatio * 100).toInt()}%) — source déjà curée par région, fallback no-filter")
            items
        } else filtered
    }

    /** 2026-05-12 (user "que les chaînes françaises qui apparaissent") : détecte si
     *  une chaîne est française par son group-title ou son nom. */
    private fun isFrenchChannel(channel: M3uParser.M3uChannel): Boolean {
        return isFrenchGroupName(channel.group ?: "") || isFrenchGroupName(channel.name)
    }

    /** Vrai si le nom contient des marqueurs FR. Utilisé pour pré-filtrer les
     *  catégories Xtream et éviter de télécharger l'allemand/arabe/etc.
     *  2026-05-13 (user "qu'un film français ni de série") : ajout détection
     *  " FR " comme mot autonome (word boundary) — les catégories Stalker VOD
     *  ressemblent à "NOUVEAUTES FR 2026", "ACTION FR", "SERIES NETFLIX FR" :
     *  "fr" y est un token séparé, pas un préfixe. Sans ce check, le filtre
     *  strict masquait tout sauf 1 film par hasard. */
    internal fun isFrenchGroupName(name: String): Boolean {
        val s = name.lowercase().trim()
        // Préfixes courants des bouquets IPTV : "FR|", "FR -", "FR ", "FR_", etc.
        val frPrefixes = listOf("fr|", "fr -", "fr ", "fr-", "fr_", "fr.", "fr:", "fr/")
        if (frPrefixes.any { s.startsWith(it) }) return true
        // Mots-clés FR n'importe où dans le nom
        val frKeywords = listOf("france", "français", "francais", "french", "🇫🇷", "vf", "vff")
        if (frKeywords.any { s.contains(it) }) return true
        // "FR" comme mot autonome n'importe où : "NOUVEAUTES FR 2026",
        // "ACTION FR", "SERIES NETFLIX FR", "VOD-FR-HD", etc.
        if (FR_WORD_REGEX.containsMatchIn(s)) return true
        return false
    }

    private val FR_WORD_REGEX = Regex("\\bfr\\b")

    /** 2026-05-13 (user "FR:TF1.SD c'est moche") : nettoie les noms de chaînes
     *  affichés dans les tuiles. Les bouquets IPTV utilisent souvent les
     *  `tvg-id` techniques en `name` : "FR:TF1.SD", "FR:France2.SD", "AR:MBC.HD".
     *  - strip préfixe code langue 2 lettres + ":" : "FR:", "EN:", "AR:", etc.
     *  - strip suffixe qualité : ".SD", ".HD", ".FHD", ".UHD", ".4K", ".HEVC", ".H265"
     *  - remplace "." et "_" restants par espaces
     *  - normalise les espaces multiples
     *  Si le nom ne match aucun pattern → retourné tel quel (no-op safe). */
    private fun prettyChannelName(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return raw
        // Préfixe ISO 2 lettres + ":" (insensible à la casse)
        s = s.replace(Regex("^[A-Za-z]{2}\\s*:\\s*"), "")
        // Suffixes qualité courants (peuvent s'enchaîner : ".HEVC.HD")
        val qualitySuffixRegex = Regex("(?i)\\.(sd|hd|fhd|uhd|4k|hevc|h265|h264|hd2|hq|lq|raw|backup|live)\\b")
        var prev: String
        do {
            prev = s
            s = s.replace(qualitySuffixRegex, "")
        } while (s != prev)
        // Restant : remplace . et _ par espaces, sauf si c'est un nom du genre "BFM.TV"
        // → garde les points qui restent au milieu de mots, mais remplace "_" par espace
        s = s.replace("_", " ")
        // Compresse espaces
        s = s.replace(Regex("\\s+"), " ").trim()
        return if (s.isEmpty()) raw else s
    }

    private fun itemToTvShow(item: ClassifiedItem): TvShow = TvShow(
        id = "myiptv::${item.source.id}::${item.index}",
        title = prettyChannelName(item.channel.name),
        poster = item.channel.logo,
        banner = item.channel.logo,
        providerName = name,
    )

    /** 2026-05-12 : LIVE channels exposés comme TvShow avec prefixe `myiptv-live::`.
     *  TvShowViewHolder a un fast-path "direct play" pour les IDs IPTV → bypass page
     *  detail + active mini-player. */
    private fun itemToTvShowLive(item: ClassifiedItem): TvShow = TvShow(
        id = "myiptv-live::${item.source.id}::${item.index}",
        title = prettyChannelName(item.channel.name),
        overview = item.channel.group?.let { "📡 $it" },
        poster = item.channel.logo,
        banner = item.channel.logo,
        providerName = name,
    )

    private fun itemToMovie(item: ClassifiedItem): Movie = Movie(
        id = "myiptv-movie::${item.source.id}::${item.index}",
        title = prettyChannelName(item.channel.name),
        overview = item.channel.group,
        poster = item.channel.logo,
        banner = item.channel.logo,
    )

    // ═══════════════════════════════════════════════════════════════
    //   FAVORIS — bridge channelId ↔ clé canonique cross-provider
    // ═══════════════════════════════════════════════════════════════

    /** 2026-05-28 : normalise un nom de chaîne en clé canonique cross-provider.
     *  Compatible avec les clés Vavoo/WiTV/OLA/Vegeta (ex: "tf1", "france2",
     *  "canalplus"). Strip préfixes, qualité, diacritiques, symboles. */
    fun normalizeChannelKey(name: String): String {
        return prettyChannelName(name)
            .lowercase()
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâä]"), "a")
            .replace(Regex("[ùûü]"), "u")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace("ç", "c")
            .replace("+", "plus")
            .replace("&", "et")
            .replace(Regex("[^a-z0-9]"), "")
    }

    /** Convertit un channelId Mon IPTV (ex: "myiptv-live::src123::42") en clé
     *  canonique (ex: "tf1"). Utilisé par IptvFavoritesStore.normalize(). */
    fun getCanonicalKeyForChannelId(channelId: String): String? {
        val cleaned = channelId
            .removePrefix("myiptv-live::")
            .removePrefix("myiptv-movie::")
            .removePrefix("myiptv-ep::")
            .removePrefix("myiptv::")
        val parts = cleaned.split("::")
        if (parts.size != 2) {
            Log.w(TAG, "getCanonicalKey: parts=${parts.size} for '$cleaned' (from '$channelId')")
            return null
        }
        val sourceId = parts[0]
        val channelIdx = parts[1].toIntOrNull() ?: run {
            Log.w(TAG, "getCanonicalKey: idx not int '${parts[1]}'")
            return null
        }
        val cached = cache[sourceId] ?: run {
            Log.w(TAG, "getCanonicalKey: cache MISS for sourceId='$sourceId' (cache keys=${cache.keys})")
            return null
        }
        val channel = cached.channels.getOrNull(channelIdx) ?: run {
            Log.w(TAG, "getCanonicalKey: idx $channelIdx out of bounds (size=${cached.channels.size})")
            return null
        }
        val key = normalizeChannelKey(channel.name)
        Log.d(TAG, "getCanonicalKey: '$channelId' → name='${channel.name}' → key='$key'")
        return key
    }

    /** 2026-05-28 : résout les favoris SANS appeler getHome() — utilise le
     *  classificationCache interne (déjà chargé si l'onglet TV a été vu au moins
     *  une fois). Si le cache est vide, charge les channels + classifie juste ce
     *  qu'il faut (pas de rebuild home complet).
     *  Appelé par IptvFavoritesMobileFragment / TvFragment pour éviter le
     *  chargement interminable quand on switch d'onglet. */
    suspend fun resolveFavoriteItems(): List<TvShow> = withContext(Dispatchers.IO) {
        val favKeys = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
            .getAllCanonicalFavorites()
        if (favKeys.isEmpty()) return@withContext emptyList()

        val sources = IptvSourceStore.getActiveOrAll()
        if (sources.isEmpty()) return@withContext emptyList()

        val liveItems = mutableListOf<ClassifiedItem>()
        for (source in sources) {
            try {
                var classified = classificationCache[source.id]
                if (classified == null) {
                    val channels = loadChannels(source)
                    if (channels.isEmpty()) continue
                    val diskCached = loadClassificationFromDisk(source, channels)
                    classified = diskCached ?: run {
                        val byType = mutableMapOf<IptvClassifier.ContentType, MutableList<ClassifiedItem>>()
                        channels.forEachIndexed { idx, ch ->
                            val t = IptvClassifier.classify(ch)
                            val item = ClassifiedItem(source, ch, idx, t)
                            byType.getOrPut(t) { mutableListOf() }.add(item)
                        }
                        val cc = CachedClassification(byType)
                        saveClassificationToDisk(source.id, channels.size, byType)
                        cc
                    }
                    classificationCache[source.id] = classified
                }
                classified.byType[IptvClassifier.ContentType.LIVE]?.let { items ->
                    liveItems.addAll(filterFrOrFallback(items))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "resolveFavoriteItems source '${source.name}' error", t)
            }
        }

        liveItems.filter { item ->
            normalizeChannelKey(item.channel.name) in favKeys
        }.map { itemToTvShowLive(it) }
    }

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
        // 2026-07-22 (user « plusieurs testeurs crashent au chargement des playlists ») :
        //   on ENROBE tout le fetch/parse dans un catch (Throwable) qui rattrape AUSSI les
        //   OutOfMemoryError (grosses playlists 176k chaînes) — le `catch (Exception)` interne du
        //   parseur les laissait passer → crash dur non signalé. Sur échec : rapport GitHub dédié
        //   (contexte mémoire/hôte/exception) + on renvoie une liste vide au lieu de planter l'app.
        return try {
            // 3. Xtream Codes (get.php?username=X&password=Y) → player_api.php, classification serveur.
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
            channels
        } catch (t: Throwable) {
            Log.e(TAG, "loadChannels CRASH ${source.name}: ${t.javaClass.simpleName}: ${t.message}", t)
            // Libère un peu de RAM au cas où c'est un OOM (évite un 2e crash en cascade).
            runCatching { System.gc() }
            com.streamflixreborn.streamflix.utils.BrokenSourceReporter.reportIptvLoadCrash(
                sourceName = source.name,
                url = source.url,
                error = t,
            )
            emptyList()
        }
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

    /** 2026-05-13 (user "Big lag sur Chromecast") : persiste la classification
     *  des channels sur disque pour éviter le re-travail au cold start.
     *  Format : 1 ligne par chaîne, contient juste le ContentType ("L"/"M"/"S")
     *  dans l'ordre du fichier .tsv. Le total doit matcher pour qu'on accepte
     *  le cache (sinon source a changé, on re-classifie). */
    /** Version de l'extension : bumper à chaque changement de IptvClassifier
     *  ou de format de classification pour invalider les anciens caches.
     *  v3 = options préservées sur disque, donc xtream-type tag fonctionne au
     *  reload, donc classification fiable. Les .classif2 d'avant peuvent avoir
     *  des classifications fausses (xtream-type perdu → fallback heuristique). */
    private fun classificationFile(sourceId: String): java.io.File? {
        val ctx = try { com.streamflixreborn.streamflix.StreamFlixApp.instance } catch (_: Throwable) { return null }
        val dir = java.io.File(ctx.cacheDir, "iptv")
        if (!dir.exists()) dir.mkdirs()
        return java.io.File(dir, "${sourceId}.classif3")
    }

    private fun saveClassificationToDisk(
        sourceId: String,
        totalChannels: Int,
        byType: Map<IptvClassifier.ContentType, MutableList<ClassifiedItem>>,
    ) {
        val file = classificationFile(sourceId) ?: return
        try {
            // Build a flat array indexed by channel position : "L" / "M" / "S"
            val classifications = CharArray(totalChannels) { 'L' }
            byType.forEach { (type, items) ->
                val ch = when (type) {
                    IptvClassifier.ContentType.LIVE -> 'L'
                    IptvClassifier.ContentType.MOVIE -> 'M'
                    IptvClassifier.ContentType.SERIES -> 'S'
                }
                items.forEach { it -> classifications[it.index] = ch }
            }
            file.writeText(String(classifications))
            Log.d(TAG, "Classification sauvée disque $sourceId : $totalChannels chaînes (${file.length()}B)")
        } catch (e: Exception) {
            Log.w(TAG, "Save classification fail: ${e.message}")
        }
    }

    private fun loadClassificationFromDisk(
        source: IptvSource,
        channels: List<M3uParser.M3uChannel>,
    ): CachedClassification? {
        val file = classificationFile(source.id) ?: return null
        if (!file.exists()) return null
        return try {
            val str = file.readText()
            if (str.length != channels.size) {
                Log.d(TAG, "Classification cache size mismatch (${str.length} vs ${channels.size}) — invalide, re-classifie")
                return null
            }
            val byType = mutableMapOf<IptvClassifier.ContentType, MutableList<ClassifiedItem>>()
            channels.forEachIndexed { idx, ch ->
                val t = when (str[idx]) {
                    'M' -> IptvClassifier.ContentType.MOVIE
                    'S' -> IptvClassifier.ContentType.SERIES
                    else -> IptvClassifier.ContentType.LIVE
                }
                byType.getOrPut(t) { mutableListOf() }.add(ClassifiedItem(source, ch, idx, t))
            }
            Log.d(TAG, "Classification rechargée depuis disque pour ${source.name} (${channels.size} chaînes, skip classification regex)")
            CachedClassification(byType)
        } catch (e: Exception) {
            Log.w(TAG, "Load classification fail: ${e.message}")
            null
        }
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
                    // 2026-05-13 (user "TF1 ne se lance pas / fais attention ça
                    // se trouve le grand lecteur aussi") : ajout 6e colonne
                    // pour les options. Sans ça, après cold start (chargement
                    // depuis disque), stalker-cmd est perdu → getVideo lit
                    // l'URL brute "ffmpeg http://localhost/ch/940_" → ExoPlayer
                    // crash. Format : "k1=v1k2=v2" (séparateur unit-sep).
                    writer.write("\t")
                    // Format options : key1value1key2value2
                    //    (Unit Separator) entre clé et valeur
                    //    (Record Separator) entre paires
                    writer.write(ch.options.entries.joinToString("") {
                        "${esc(it.key)}${esc(it.value)}"
                    })
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
                    // 2026-05-13 : 6e colonne = options serializees
                    // "k1v1k2v2". Format binary-safe.
                    val options = if (parts.size >= 6 && parts[5].isNotEmpty()) {
                        parts[5].split('').mapNotNull { kv ->
                            val sep = kv.indexOf('')
                            if (sep > 0) kv.substring(0, sep) to kv.substring(sep + 1) else null
                        }.toMap()
                    } else emptyMap()
                    out += M3uParser.M3uChannel(
                        name = parts[0],
                        url = parts[1],
                        logo = parts[2].takeIf { it.isNotEmpty() },
                        group = parts[3].takeIf { it.isNotEmpty() },
                        tvgId = parts[4].takeIf { it.isNotEmpty() },
                        options = options,
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
        var trimmed = raw.trim()
        // 2026-05-14 (user bug "Unable to resolve host http") : strip les
        // double-prefixes "http://http://" ou "https://https://" (typique
        // copier-coller depuis form où le user inclut http:// alors que le
        // form le préfixe déjà). Boucle pour gérer http://http://http://.
        while (trimmed.startsWith("http://http://", ignoreCase = true)) {
            trimmed = trimmed.removePrefix("http://")
        }
        while (trimmed.startsWith("https://https://", ignoreCase = true)) {
            trimmed = trimmed.removePrefix("https://")
        }
        while (trimmed.startsWith("http://https://", ignoreCase = true)) {
            trimmed = trimmed.removePrefix("http://")
        }
        while (trimmed.startsWith("https://http://", ignoreCase = true)) {
            trimmed = trimmed.removePrefix("https://")
        }
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
        loadingStatus.value = "Téléchargement de la playlist M3U…"
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

    /** 2026-05-12 : fetch d'un portail Stalker MAG. Délégué à StalkerClient.
     *  2026-05-13 (user "rien en série alors je suis sûr il y en a plein") :
     *  fetch également les VOD (films) et séries du Stalker en plus du live TV.
     *  Tag explicite via `xtream-type` pour bypass la classification heuristique
     *  (qui se trompe souvent sur les bouquets type "ARABE 4K" classifés MOVIE
     *  à cause du "4K"). */
    private suspend fun fetchStalker(source: IptvSource): List<M3uParser.M3uChannel> = withContext(Dispatchers.IO) {
        val mac = source.mac?.takeIf { it.isNotBlank() } ?: run {
            Log.e(TAG, "Stalker ${source.name} : MAC manquante")
            return@withContext emptyList()
        }
        val Stalker = com.streamflixreborn.streamflix.utils.StalkerClient
        val portalUrl = sanitizeUrl(source.url)
        // v60 : émet status pour l'UI loadingText
        loadingStatus.value = "Connexion au portail Stalker…"
        // 2026-05-13 (user "Les catégories film et n'apparaissent pas comme elles
        // devraient apparaître") : fetch 3 maps de catégories séparées (ITV/VOD/
        // Series) car Stalker utilise des category_id différents par type.
        val liveGenres = Stalker.getGenres(portalUrl, mac, source.userAgent, source.serial)
        loadingStatus.value = "Catégories TV chargées (${liveGenres.size})"
        val vodCategories = Stalker.getVodCategories(portalUrl, mac, source.userAgent, source.serial)
        loadingStatus.value = "Catégories films chargées (${vodCategories.size})"
        val seriesCategories = Stalker.getSeriesCategories(portalUrl, mac, source.userAgent, source.serial)
        loadingStatus.value = "Catégories séries chargées (${seriesCategories.size})"
        val out = mutableListOf<M3uParser.M3uChannel>()
        // === LIVE TV ===
        loadingStatus.value = "Téléchargement des chaînes TV…"
        val live = Stalker.getAllChannels(portalUrl, mac, source.userAgent, source.serial)
        loadingStatus.value = "Chaînes TV : ${live.size}"
        Log.d(TAG, "Stalker ${source.name} live : ${live.size} chaînes")
        live.forEach { sc ->
            val friendlyGroup = sc.group?.let { liveGenres[it] ?: it }
            out += M3uParser.M3uChannel(
                name = sc.name, url = sc.cmd, logo = sc.logo, group = friendlyGroup, tvgId = sc.tvgId,
                options = mapOf(
                    "stalker-cmd" to sc.cmd, "stalker-mac" to mac, "stalker-portal" to source.url,
                    "xtream-type" to "live", // tag explicite → IptvClassifier le respecte
                ),
            )
        }
        // === VOD (films) ===
        loadingStatus.value = "Téléchargement des films…"
        runCatching {
            val vodRaw = Stalker.getAllVod(portalUrl, mac, source.userAgent, source.serial)
            // 2026-05-14 (user "j'ai les jaquettes en double dans le film") :
            // dédup par cmd (chaque VOD a un cmd unique côté serveur Stalker).
            // Si la même entrée revient dans plusieurs pages ou catégories,
            // on la garde une seule fois pour pas avoir 2 fois la jaquette.
            val vod = vodRaw.distinctBy { it.cmd.ifBlank { it.name } }
            loadingStatus.value = "Films : ${vod.size}"
            Log.d(TAG, "Stalker ${source.name} VOD : ${vod.size} films (raw=${vodRaw.size} avant dédup)")
            vod.forEach { sc ->
                val friendlyGroup = sc.group?.let { vodCategories[it] ?: it } ?: "Films"
                out += M3uParser.M3uChannel(
                    name = sc.name, url = sc.cmd, logo = sc.logo, group = friendlyGroup, tvgId = sc.tvgId,
                    options = mapOf(
                        "stalker-cmd" to sc.cmd, "stalker-mac" to mac, "stalker-portal" to source.url,
                        "xtream-type" to "movie",
                    ),
                )
            }
        }.onFailure { Log.w(TAG, "Stalker VOD fetch fail: ${it.message}") }
        // === SERIES ===
        loadingStatus.value = "Téléchargement des séries…"
        runCatching {
            val seriesRaw = Stalker.getAllSeries(portalUrl, mac, source.userAgent, source.serial)
            // 2026-05-14 (user "jaquettes en double") : même dédup que VOD.
            // Pour les séries Stalker cmd est souvent vide → dédup par nom + groupe.
            val series = seriesRaw.distinctBy {
                if (it.cmd.isNotBlank()) it.cmd
                else "${it.name}::${it.group ?: ""}"
            }
            loadingStatus.value = "Séries : ${series.size}"
            Log.d(TAG, "Stalker ${source.name} Series : ${series.size} séries (raw=${seriesRaw.size} avant dédup)")
            series.forEach { sc ->
                val friendlyGroup = sc.group?.let { seriesCategories[it] ?: it } ?: "Séries"
                out += M3uParser.M3uChannel(
                    name = sc.name, url = sc.cmd, logo = sc.logo, group = friendlyGroup, tvgId = sc.tvgId,
                    options = mapOf(
                        "stalker-cmd" to sc.cmd, "stalker-mac" to mac, "stalker-portal" to source.url,
                        "xtream-type" to "series",
                    ),
                )
            }
        }.onFailure { Log.w(TAG, "Stalker Series fetch fail: ${it.message}") }
        Log.d(TAG, "Stalker ${source.name} : ${out.size} items au total (live+vod+series)")
        out
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
            try { classificationFile(sourceId)?.delete() } catch (_: Exception) {}
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

    /** 2026-05-13 (user "la recherche ne fonctionne pas") : implémentation
     *  recherche full-text sur les noms de chaînes (case-insensitive). Recherche
     *  dans les 3 types (LIVE/MOVIE/SERIES) en parallèle. Retourne TvShow pour
     *  les LIVE (pour qu'ils soient lus directement via le mini-player), Movie
     *  pour les VOD. Paginé via PAGE_SIZE pour fluidité. */
    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()
        val sources = IptvSourceStore.getActiveOrAll()
        if (sources.isEmpty()) return@withContext emptyList()
        // Force la classification sur toutes les sources si pas en cache
        val (liveItems, _) = collectByType(sources, IptvClassifier.ContentType.LIVE)
        val (movieItems, _) = collectByType(sources, IptvClassifier.ContentType.MOVIE)
        val (seriesItems, _) = collectByType(sources, IptvClassifier.ContentType.SERIES)
        // Filtre par query
        val matches = mutableListOf<AppAdapter.Item>()
        liveItems.forEach { item ->
            if (item.channel.name.lowercase().contains(q)) {
                matches += itemToTvShowLive(item)
            }
        }
        movieItems.forEach { item ->
            if (item.channel.name.lowercase().contains(q)) {
                matches += itemToMovie(item)
            }
        }
        seriesItems.forEach { item ->
            if (item.channel.name.lowercase().contains(q)) {
                // Évite les doublons par show name pour les séries
                val showName = IptvClassifier.extractShowName(item.channel.name)
                val showId = "myiptv-show::${item.source.id}::${encodeId(showName)}"
                if (matches.none { it is TvShow && it.id == showId }) {
                    matches += TvShow(
                        id = showId,
                        title = showName,
                        overview = item.channel.group,
                        poster = item.channel.logo,
                        banner = item.channel.logo,
                        providerName = name,
                    )
                }
            }
        }
        // Pagination
        val from = (page - 1) * PAGE_SIZE
        if (from >= matches.size) return@withContext emptyList()
        val to = minOf(from + PAGE_SIZE, matches.size)
        Log.d(TAG, "search('$q') page $page → ${matches.size} matches total, retourne ${to - from}")
        matches.subList(from, to)
    }
    override suspend fun getGenre(id: String, page: Int): Genre = throw UnsupportedOperationException()
    override suspend fun getPeople(id: String, page: Int): People = throw UnsupportedOperationException()

    // ═══════════════════════════════════════════════════════════════
    //   v61 : Navigation chaîne précédente / suivante (IPTV Live)
    //   Permet aux boutons prev/next du player de zapper entre les
    //   chaînes live sans repasser par la grille. Utilise le cache
    //   de classification (rempli au home/getMovies/getTvShows),
    //   donc accès O(1) après premier load.
    // ═══════════════════════════════════════════════════════════════

    /** Retourne la liste ordonnée des IDs `myiptv-live::SOURCEID::IDX`
     *  pour TOUTES les sources actuellement chargées en cache de
     *  classification, dans l'ordre où elles s'affichent dans getHome
     *  (= filterFrOrFallback appliqué).
     *  Retourne liste vide si aucune source classifiée — l'UI cachera
     *  alors les boutons. */
    fun getOrderedLiveChannelIds(): List<String> {
        val sources = try { IptvSourceStore.getActiveOrAll() } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<String>()
        for (source in sources) {
            val classified = classificationCache[source.id]
            if (classified != null) {
                val liveItems = classified.byType[IptvClassifier.ContentType.LIVE] ?: continue
                val filtered = filterFrOrFallback(liveItems)
                val sel = selectedCategoryLive
                val finalList = if (sel != null) {
                    filtered.filter { (it.channel.group ?: "").trim() == sel }
                } else filtered
                finalList.forEach { item ->
                    out.add("myiptv-live::${item.source.id}::${item.index}")
                }
            } else {
                // 2026-05-31 : fallback sur le cache parsé (M3U brut) quand la
                // classification n'est pas encore faite ou a été vidée par IptvCacheManager.
                // Permet aux boutons prev/next et à la liste des chaînes de fonctionner
                // même sans classification.
                val parsed = cache[source.id]?.channels ?: continue
                parsed.forEachIndexed { index, ch ->
                    out.add("myiptv-live::${source.id}::$index")
                }
            }
        }
        return out
    }

    fun getPreviousChannelId(currentId: String): String? {
        val list = getOrderedLiveChannelIds()
        val idx = list.indexOf(currentId)
        return if (idx > 0) list[idx - 1] else null
    }

    fun getNextChannelId(currentId: String): String? {
        val list = getOrderedLiveChannelIds()
        val idx = list.indexOf(currentId)
        return if (idx in 0 until list.lastIndex) list[idx + 1] else null
    }

    /** Récupère un ClassifiedItem live à partir d'un id myiptv-live::SOURCE::IDX. */
    private fun findLiveItem(channelId: String): ClassifiedItem? {
        val cleaned = channelId.removePrefix("myiptv-live::")
        val parts = cleaned.split("::")
        if (parts.size != 2) return null
        val sourceId = parts[0]
        val idx = parts[1].toIntOrNull() ?: return null
        val classified = classificationCache[sourceId] ?: return null
        val liveItems = classified.byType[IptvClassifier.ContentType.LIVE] ?: return null
        return liveItems.find { it.index == idx }
    }

    /** 2026-05-31 : fallback sur le cache parsé quand la classification est vide. */
    private fun findParsedChannel(channelId: String): M3uParser.M3uChannel? {
        val cleaned = channelId.removePrefix("myiptv-live::")
        val parts = cleaned.split("::")
        if (parts.size != 2) return null
        val sourceId = parts[0]
        val idx = parts[1].toIntOrNull() ?: return null
        return cache[sourceId]?.channels?.getOrNull(idx)
    }

    fun getChannelDisplayName(channelId: String): String? =
        findLiveItem(channelId)?.channel?.name ?: findParsedChannel(channelId)?.name

    fun getChannelPoster(channelId: String): String? =
        findLiveItem(channelId)?.channel?.logo ?: findParsedChannel(channelId)?.logo
}
