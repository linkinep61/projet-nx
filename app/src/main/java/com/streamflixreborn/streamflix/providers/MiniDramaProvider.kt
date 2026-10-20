package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.Gson
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-10-20 — Mini Drama (minutedrama.com/fr) : micro-séries verticales (« short dramas »),
 * épisodes d'environ 1 min 30, audio anglais + sous-titres français. Provider RÉSERVÉ AU MOBILE
 * (vidéo 720×1280 verticale → masqué de la liste des providers sur TV, cf. ProviderSupport.mobileOnly).
 *
 * Tout est relevé en direct dans Chrome (site sans Cloudflare, sans compte, tous les épisodes
 * gratuits — le « VIP » ne retire que les pubs) :
 *
 *  • CATALOGUE (JSON) : POST /fr/categoryTvs/<idCatégorie>/pageNum/<n>   (corps vide, JSON)
 *      → { resultCode:0, dataResult:{ hasMore, tvs:[ { id, title, desc, episodesCount,
 *          coverUrl (affiche 3:4), coverHoriUrl (bannière 16:9), categories:[{id,name}] } ] } }
 *  • ACCUEIL : /fr/ = bannière (div.md-banner-slide) + rangées <section data-category-id="…"
 *      aria-label="Nom">. On lit l'ordre et les noms dans le HTML, le contenu via l'API JSON.
 *  • FICHE : /fr/tv-desc/<slug>/<id> → acteurs (div.tv-cast-item : img actor/<id>_<n>.jpg + nom).
 *  • ÉPISODES : /fr/tv-detail/<slug>/<id> → `window.videoData = [ { videoId, videoUrl,
 *      bkVideoUrl, textTrackUrl, episodeNumber } … ]` = TOUS les épisodes d'un coup.
 *      videoUrl = MP4 720p signé (?token=…&expires=…, valable ~3 jours) sur cdn3, bkVideoUrl =
 *      même fichier sur cdn4 (secours). textTrackUrl = sous-titres FR en VTT.
 *      Le slug est ignoré par le serveur : /fr/tv-detail/x/<id> marche.
 */
object MiniDramaProvider : Provider {

    private const val TAG = "MiniDrama"
    private const val BASE = "https://minutedrama.com"
    private const val LANG = "fr"
    private const val ID_PREFIX = "minidrama-"

    override val baseUrl = "$BASE/$LANG/"
    override val name = "Mini Drama"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_minidrama"
    override val language = "fr"

    private val gson = Gson()
    private val client get() = NetworkClient.default

    // ───────────────────────── modèles JSON ─────────────────────────

    private data class CatResponse(val resultCode: Int? = null, val dataResult: CatData? = null)
    private data class CatData(val hasMore: Boolean? = null, val tvs: List<Tv>? = null)
    private data class Tv(
        val id: Int? = null,
        val title: String? = null,
        val desc: String? = null,
        val episodesCount: Int? = null,
        val collectCount: Int? = null,
        val coverUrl: String? = null,
        val coverHoriUrl: String? = null,
        val categories: List<Cat>? = null,
    )
    private data class Cat(val id: Int? = null, val name: String? = null)

    private data class Ep(
        val number: Int,
        val url: String,
        val backupUrl: String?,
        val subtitle: String?,
    )

    // ───────────────────────── caches ─────────────────────────

    private const val CACHE_MS = 60 * 60 * 1000L          // catalogue : 1 h
    private const val EPISODES_CACHE_MS = 6 * 60 * 60 * 1000L // liens signés (~3 j) : 6 h

    private val shows = ConcurrentHashMap<Int, Tv>()
    private val categoryNames = ConcurrentHashMap<Int, String>()
    private val categoryContent = ConcurrentHashMap<Int, Pair<Long, List<Int>>>()
    private var homeSections: List<Pair<Int, String>> = emptyList()
    private var bannerIds: List<Int> = emptyList()
    private var homeLoadedAt = 0L
    private var fullCatalogAt = 0L
    private val homeMutex = Mutex()
    private val fullMutex = Mutex()
    private val episodesCache = ConcurrentHashMap<Int, Pair<Long, List<Ep>>>()

    // ───────────────────────── réseau ─────────────────────────

    private fun headers(b: Request.Builder) = b
        .header("User-Agent", NetworkClient.USER_AGENT)
        .header("Referer", "$BASE/$LANG/")
        .header("Accept-Language", "fr-FR,fr;q=0.9")

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(headers(Request.Builder().url(url)).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code} sur $url")
            r.body?.string() ?: ""
        }
    }

    private suspend fun fetchCategoryPage(catId: Int, page: Int): CatData? = withContext(Dispatchers.IO) {
        val req = headers(Request.Builder().url("$BASE/$LANG/categoryTvs/$catId/pageNum/$page"))
            .header("Content-Type", "application/json")
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val res = gson.fromJson(r.body?.string() ?: "", CatResponse::class.java)
                if (res?.resultCode != 0) null else res.dataResult
            }
        }.onFailure { Log.w(TAG, "catégorie $catId p$page : ${it.message}") }.getOrNull()
    }

    /** Toutes les séries d'une catégorie (pagination suivie), mises en cache 1 h. */
    private suspend fun loadCategory(catId: Int): List<Int> {
        categoryContent[catId]?.let { (t, ids) -> if (System.currentTimeMillis() - t < CACHE_MS) return ids }
        val ids = ArrayList<Int>()
        var page = 1
        while (page <= 20) {
            val data = fetchCategoryPage(catId, page) ?: break
            val tvs = data.tvs.orEmpty()
            for (tv in tvs) {
                val id = tv.id ?: continue
                shows[id] = tv
                if (!ids.contains(id)) ids.add(id)
                tv.categories?.forEach { c -> if (c.id != null && !c.name.isNullOrBlank()) categoryNames[c.id] = c.name }
            }
            if (data.hasMore != true || tvs.isEmpty()) break
            page++
        }
        if (ids.isNotEmpty()) categoryContent[catId] = System.currentTimeMillis() to ids
        return ids
    }

    /** Lit l'accueil du site (ordre des rangées + bannière) puis charge chaque rangée en JSON. */
    private suspend fun ensureHome() = homeMutex.withLock {
        if (System.currentTimeMillis() - homeLoadedAt < CACHE_MS && homeSections.isNotEmpty()) return@withLock
        val html = getText("$BASE/$LANG/")
        val sections = Regex("""<section[^>]*aria-label="([^"]+)"[^>]*data-category-id="(\d+)"""")
            .findAll(html).map { it.groupValues[2].toInt() to decodeHtml(it.groupValues[1]) }
            .distinctBy { it.first }.toList()
        bannerIds = Regex("""md-banner-slide[^>]*>\s*<a href="/$LANG/tv-desc/[^"/]+/(\d+)"""").findAll(html)
            .map { it.groupValues[1].toInt() }.distinct().toList()
        sections.forEach { (id, n) -> categoryNames[id] = n }
        coroutineScope { sections.map { (id, _) -> async { loadCategory(id) } }.awaitAll() }
        homeSections = sections
        homeLoadedAt = System.currentTimeMillis()
        Log.i(TAG, "accueil : ${sections.size} rangées, ${shows.size} séries, bannière ${bannerIds.size}")
    }

    /**
     * Catalogue COMPLET. Les rangées de l'accueil couvrent presque tout, mais quelques séries ne
     * sont rangées dans AUCUNE d'elles (relevé 2026-10-20 : 76 sur 80, manquaient 75, 103, 107, 114).
     * Les identifiants du site sont continus → on sonde les trous entre le plus petit et le plus
     * grand id connus (+ quelques-uns au-delà, pour les nouveautés), page fiche par page fiche.
     */
    private suspend fun ensureFullCatalog() {
        ensureHome()
        fullMutex.withLock {
            if (System.currentTimeMillis() - fullCatalogAt < CACHE_MS) return@withLock
            val connus = shows.keys.toSet()
            if (connus.isEmpty()) return@withLock
            val min = connus.min(); val max = connus.max()
            val aSonder = ((min..max).filter { it !in connus } + ((max + 1)..(max + 8)))
            coroutineScope {
                aSonder.map { id -> async { runCatching { loadShowFromPage(id) }.getOrNull() } }.awaitAll()
            }
            fullCatalogAt = System.currentTimeMillis()
            Log.i(TAG, "catalogue complet : ${shows.size} séries (${aSonder.size} ids sondés)")
        }
    }

    /** Fiche d'une série inconnue du cache, reconstruite depuis sa page tv-desc (null si l'id n'existe pas). */
    private suspend fun loadShowFromPage(id: Int): Tv? {
        val html = getText("$BASE/$LANG/tv-desc/x/$id")
        val count = Regex("""(\d+)\s*Épisodes""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val title = Regex("""<h1[^>]*>([^<]+)</h1>""").find(html)?.groupValues?.get(1)?.let(::decodeHtml) ?: return null
        val desc = Regex("""data-full-text="([^"]*)"""").find(html)?.groupValues?.get(1)?.let(::decodeHtml)
            ?: Regex("""<meta name="description" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.let(::decodeHtml)
        val cats = Regex("""subCategoryId/(\d+)"[^>]*class="tv-tag">([^<]+)<""").findAll(html).map {
            Cat(id = it.groupValues[1].toInt(), name = decodeHtml(it.groupValues[2]))
        }.toList()
        cats.forEach { c -> if (c.id != null && !c.name.isNullOrBlank()) categoryNames[c.id] = c.name }
        val tv = Tv(
            id = id, title = title, desc = desc, episodesCount = count, categories = cats,
            coverUrl = "$BASE/cover/${id}_${LANG}.jpg", coverHoriUrl = "$BASE/cover/${id}_${LANG}_h.jpg",
        )
        shows[id] = tv
        return tv
    }

    private suspend fun showOf(id: Int): Tv? = shows[id] ?: run {
        runCatching { ensureHome() }
        shows[id] ?: runCatching { loadShowFromPage(id) }.getOrNull()
    }

    // ───────────────────────── conversions ─────────────────────────

    private fun keyOf(id: Int) = "$ID_PREFIX$id"
    private fun idOf(key: String): Int? = key.removePrefix(ID_PREFIX).substringBefore(":").toIntOrNull()

    private fun Tv.toTvShow(withBanner: Boolean = true) = TvShow(
        id = keyOf(id ?: 0),
        title = title.orEmpty(),
        overview = desc,
        poster = coverUrl ?: "$BASE/cover/${id}_${LANG}.jpg",
        banner = if (withBanner) (coverHoriUrl ?: "$BASE/cover/${id}_${LANG}_h.jpg") else null,
        quality = episodesCount?.let { "$it ép." },
    )

    private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun decodeHtml(s: String) = s.replace("&amp;", "&").replace("&#039;", "'").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim()

    // ───────────────────────── Provider ─────────────────────────

    override suspend fun getHome(): List<Category> {
        // 2026-10-20 (user : « mets plusieurs choses dans le carrousel… incrémente bien la page
        //   d'accueil ») : on charge TOUT le catalogue (≈ 70 catégories, petites réponses JSON en
        //   parallèle, cache 1 h) pour composer un accueil riche, façon TMDB.
        runCatching { ensureFullCatalog() }.onFailure { Log.w(TAG, "catalogue complet : ${it.message}") }
        if (shows.isEmpty()) ensureHome()
        val tous = shows.values.toList()
        val out = ArrayList<Category>()
        val dejaVus = HashSet<Int>()
        fun rangee(nom: String, liste: List<Tv>, max: Int = 20) {
            val l = liste.distinctBy { it.id }.take(max)
            if (l.size >= 4) out.add(Category(nom, l.map { it.toTvShow() }))
        }
        val populaires = tous.sortedByDescending { it.collectCount ?: 0 }

        // 1) Carrousel : la bannière du site + les plus populaires + quelques découvertes (12).
        val carrousel = (bannerIds.mapNotNull { shows[it] } + populaires.take(6) + tous.shuffled().take(6))
            .distinctBy { it.id }.take(12)
        if (carrousel.isNotEmpty()) out.add(Category(Category.FEATURED, carrousel.map { it.toTvShow() }))

        // 2) Rangées « éditoriales ».
        rangee("Les plus populaires", populaires)
        rangee("Nouveautés", tous.sortedByDescending { it.id ?: 0 })
        // 3) Les rangées de l'accueil du site, dans son ordre.
        for ((catId, catName) in homeSections) {
            val ids = categoryContent[catId]?.second ?: continue
            dejaVus.add(catId)
            rangee(catName, ids.mapNotNull { shows[it] }.sortedByDescending { it.collectCount ?: 0 }, 30)
        }
        rangee("Les plus longues sagas", tous.sortedByDescending { it.episodesCount ?: 0 })
        rangee("Vite vues (les plus courtes)", tous.filter { (it.episodesCount ?: 0) > 0 }.sortedBy { it.episodesCount ?: 0 })
        rangee("À découvrir", tous.shuffled())
        // 4) Les autres catégories du site, calculées depuis les tags de chaque série (les plus fournies d'abord).
        tous.flatMap { tv -> tv.categories.orEmpty().mapNotNull { c -> c.id?.let { it to tv } } }
            .groupBy({ it.first }, { it.second })
            .filter { it.key !in dejaVus && it.value.size >= 6 }
            .entries.sortedByDescending { it.value.size }
            .take(16)
            .forEach { (catId, l) -> rangee(categoryNames[catId] ?: return@forEach, l.sortedByDescending { it.collectCount ?: 0 }, 30) }
        return out
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        ensureFullCatalog()
        if (query.isBlank()) {
            // Écran de recherche vide → les catégories du site, comme sur minutedrama.com.
            return categoryNames.entries.sortedBy { it.value }.map { Genre(id = it.key.toString(), name = it.value) }
        }
        val q = normalize(query)
        val words = q.split(" ").filter { it.length > 1 }
        val scored = shows.values.mapNotNull { tv ->
            val t = normalize(tv.title.orEmpty())
            val d = normalize(tv.desc.orEmpty())
            val cats = normalize(tv.categories.orEmpty().joinToString(" ") { it.name.orEmpty() })
            val score = when {
                t == q -> 100
                t.contains(q) -> 80
                words.isNotEmpty() && words.all { t.contains(it) } -> 60
                cats.contains(q) -> 40
                words.isNotEmpty() && words.all { d.contains(it) || cats.contains(it) } -> 20
                else -> 0
            }
            if (score > 0) score to tv else null
        }
        return scored.sortedByDescending { it.first }.map { it.second.toTvShow() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        ensureFullCatalog()
        val pageSize = 30
        val all = shows.values.sortedByDescending { it.id ?: 0 }
        return all.drop((page - 1) * pageSize).take(pageSize).map { it.toTvShow() }
    }

    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "")

    override suspend fun getTvShow(id: String): TvShow {
        val tvId = idOf(id) ?: return TvShow(id = id)
        val tv = showOf(tvId) ?: return TvShow(id = id)

        // Acteurs : page tv-desc (photo + prénom du personnage).
        val cast = runCatching {
            val html = getText("$BASE/$LANG/tv-desc/x/$tvId")
            Regex("""<img src="([^"]*/actor/[^"]+)"\s+alt="([^"]*)"""").findAll(html).mapIndexed { i, m ->
                People(
                    id = "$ID_PREFIX$tvId-actor-$i",
                    name = decodeHtml(m.groupValues[2]),
                    image = m.groupValues[1].replace("//actor/", "/actor/"),
                )
            }.toList()
        }.getOrDefault(emptyList())

        val genres = tv.categories.orEmpty().mapNotNull { c ->
            if (c.id == null || c.name.isNullOrBlank()) null else Genre(id = c.id.toString(), name = c.name)
        }

        // Recommandations : séries qui partagent le plus de catégories.
        val myCats = tv.categories.orEmpty().mapNotNull { it.id }.toSet()
        val recos: List<Show> = shows.values.asSequence()
            .filter { it.id != tvId }
            .map { o -> o to o.categories.orEmpty().count { it.id in myCats } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(16).map { it.first.toTvShow() }.toList()

        val count = tv.episodesCount ?: 0
        return TvShow(
            id = keyOf(tvId),
            title = tv.title.orEmpty(),
            overview = tv.desc,
            quality = "720p",
            poster = tv.coverUrl,
            banner = tv.coverHoriUrl,
            seasons = listOf(
                Season(
                    id = keyOf(tvId),
                    number = 1,
                    title = if (count > 0) "$count épisodes" else "Épisodes",
                    poster = tv.coverUrl,
                )
            ),
            genres = genres,
            cast = cast,
            recommendations = recos,
        )
    }

    /** Lit window.videoData de la page tv-detail : liens signés de TOUS les épisodes. */
    private suspend fun loadEpisodes(tvId: Int, force: Boolean = false): List<Ep> {
        if (!force) episodesCache[tvId]?.let { (t, l) -> if (System.currentTimeMillis() - t < EPISODES_CACHE_MS) return l }
        val html = getText("$BASE/$LANG/tv-detail/x/$tvId")
        val block = html.substringAfter("window.videoData", "").substringBefore("];", "")
        val eps = Regex("""\{([^{}]*?videoUrl[^{}]*?)\}""").findAll(block).mapNotNull { m ->
            val b = m.groupValues[1]
            fun field(n: String) = Regex("""$n\s*:\s*"([^"]*)"""").find(b)?.groupValues?.get(1)
                ?.replace("\\/", "/")?.replace("&amp;", "&")?.takeIf { it.isNotBlank() }
            val url = field("videoUrl") ?: return@mapNotNull null
            val num = Regex("""episodeNumber\s*:\s*(\d+)""").find(b)?.groupValues?.get(1)?.toIntOrNull()
                ?: field("videoId")?.toIntOrNull() ?: return@mapNotNull null
            Ep(number = num, url = url, backupUrl = field("bkVideoUrl"), subtitle = field("textTrackUrl"))
        }.distinctBy { it.number }.sortedBy { it.number }.toList()
        if (eps.isNotEmpty()) episodesCache[tvId] = System.currentTimeMillis() to eps
        Log.i(TAG, "série $tvId : ${eps.size} épisodes lus")
        return eps
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val tvId = idOf(seasonId) ?: return emptyList()
        val tv = showOf(tvId)
        val banner = tv?.coverHoriUrl ?: tv?.coverUrl
        val eps = runCatching { loadEpisodes(tvId) }.getOrDefault(emptyList())
        val numbers = if (eps.isNotEmpty()) eps.map { it.number } else (1..(tv?.episodesCount ?: 0)).toList()
        return numbers.map { n ->
            Episode(
                id = "${keyOf(tvId)}:$n",
                number = n,
                title = "Épisode $n",
                poster = banner,
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val catId = id.toIntOrNull() ?: return Genre(id = id, name = id)
        if (page > 1) return Genre(id = id, name = categoryNames[catId] ?: id)
        val ids = loadCategory(catId)
        return Genre(id = id, name = categoryNames[catId] ?: id, shows = ids.mapNotNull { shows[it]?.toTvShow() })
    }

    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = "")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val tvId = idOf(id) ?: return emptyList()
        val num = id.substringAfterLast(":").toIntOrNull()
            ?: (videoType as? Video.Type.Episode)?.number ?: return emptyList()
        var ep = loadEpisodes(tvId).firstOrNull { it.number == num }
        // Lien expiré (cache > durée de validité) : on relit la page une fois.
        if (ep == null || isExpired(ep.url)) ep = loadEpisodes(tvId, force = true).firstOrNull { it.number == num }
        ep ?: return emptyList()
        val servers = ArrayList<Video.Server>()
        servers.add(Video.Server(id = "md|$tvId|$num|main", name = "Mini Drama · 720p [VOSTFR]", src = ep.url))
        ep.backupUrl?.let { servers.add(Video.Server(id = "md|$tvId|$num|bk", name = "Mini Drama · 720p secours [VOSTFR]", src = it)) }
        servers.forEach { it.quality = "720p"; it.language = "VOSTFR" }
        return servers
    }

    private fun isExpired(url: String): Boolean {
        val exp = Regex("""[?&]expires=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: return false
        return exp * 1000 < System.currentTimeMillis() + 10 * 60 * 1000
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val parts = server.id.split("|")
        val tvId = parts.getOrNull(1)?.toIntOrNull()
        val num = parts.getOrNull(2)?.toIntOrNull()
        val sub = if (tvId != null && num != null) episodesCache[tvId]?.second?.firstOrNull { it.number == num }?.subtitle else null
        return Video(
            source = server.src,
            subtitles = listOfNotNull(sub?.let { Video.Subtitle(label = "Français", file = it, default = true, initialDefault = true) }),
            headers = mapOf("Referer" to "$BASE/", "User-Agent" to NetworkClient.USER_AGENT),
            type = "video/mp4",
        )
    }
}
