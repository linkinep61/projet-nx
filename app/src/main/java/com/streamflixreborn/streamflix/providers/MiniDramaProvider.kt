package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.extractors.DailymotionExtractor
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
import java.net.URLEncoder
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
 *
 * 2026-10-20 (user : « 80 séries c'est pas assez… on reste sur Dailymotion, mobile uniquement ») :
 * deuxième source, Dailymotion. Des chaînes y publient des short dramas DOUBLÉS EN FRANÇAIS en
 * un seul fichier (« Série Complète », 1 h à 2 h 30, vidéo verticale). Relevé dans Chrome : plus
 * de 680 séries VF distinctes, souvent publiées par plusieurs chaînes à la fois.
 *  • API publique sans clé : api.dailymotion.com/videos?search=…&longer_than=40&sort=recent|visited|relevance
 *  • Une série = un Movie (id « minidrama-dm-<xid> » → lecteur en portrait, cf. orientationLecteur).
 *  • Les copies d'une même série (même titre nettoyé, chaînes différentes) deviennent autant de
 *    SERVEURS : si une chaîne est supprimée, les autres copies prennent le relais.
 *  • Lecture via DailymotionExtractor (déjà utilisé pour les chaînes TV).
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

    // ───────────────────────── Dailymotion (séries complètes VF) ─────────────────────────

    private const val DM_API = "https://api.dailymotion.com"
    private const val DM_PREFIX = "minidrama-dm-"
    private const val DM_FIELDS = "id,title,description,duration,owner.screenname,thumbnail_720_url,views_total,created_time,aspect_ratio,language"
    private const val DM_BASE_QUERY = "doublage"

    private data class DmList(val list: List<DmVideo>? = null, @SerializedName("has_more") val hasMore: Boolean? = null)
    private data class DmVideo(
        val id: String? = null,
        val title: String? = null,
        val description: String? = null,
        val duration: Int? = null,
        @SerializedName("owner.screenname") val owner: String? = null,
        @SerializedName("thumbnail_720_url") val thumbnail: String? = null,
        @SerializedName("views_total") val views: Long? = null,
        @SerializedName("created_time") val created: Long? = null,
        @SerializedName("aspect_ratio") val aspect: Double? = null,
        val language: String? = null,
    )

    /** Une série = toutes les copies Dailymotion qui portent le même titre (nettoyé). */
    private class DmSerie(val cle: String, val titre: String, var resume: String?, var affiche: String?, var duree: Int) {
        val copies = ArrayList<DmVideo>()
        var vues = 0L
        var date = 0L
        /** xid de référence (le premier vu) : sert d'identifiant stable au Movie. */
        var xid: String = ""
    }

    /** Rangées thématiques (nom affiché → recherche Dailymotion). */
    private val DM_THEMES = listOf(
        "Milliardaires et PDG" to "doublage milliardaire PDG",
        "Vengeance" to "doublage vengeance",
        "Mafia et parrains" to "doublage mafia",
        "Loups-garous et alphas" to "doublage alpha loup",
        "Mariages et trahisons" to "doublage mariage",
        "Renaissance et destin" to "doublage renaissance",
        "Fantastique et dieux" to "doublage dieu dragon",
        "Romance" to "doublage amour",
    )

    private val dmSeries = ConcurrentHashMap<String, DmSerie>()
    private val dmParXid = ConcurrentHashMap<String, String>()
    private var dmRangees: List<Pair<String, List<String>>> = emptyList()
    private var dmLoadedAt = 0L
    private val dmMutex = Mutex()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun dmPage(params: String): DmList? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$DM_API/videos?$params&fields=$DM_FIELDS")
            .header("User-Agent", NetworkClient.USER_AGENT).build()
        runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else gson.fromJson(r.body?.string() ?: "", DmList::class.java)
            }
        }.onFailure { Log.w(TAG, "Dailymotion : ${it.message}") }.getOrNull()
    }

    /** Recherche Dailymotion, vidéos de plus de 40 min uniquement. */
    private suspend fun dmSearch(q: String, sort: String = "relevance", page: Int = 1): List<DmVideo> =
        dmPage("search=${enc(q)}&longer_than=40&sort=$sort&limit=100&page=$page")?.list.orEmpty()

    private suspend fun dmVideo(xid: String): DmVideo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$DM_API/video/$xid?fields=$DM_FIELDS")
            .header("User-Agent", NetworkClient.USER_AGENT).build()
        runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else gson.fromJson(r.body?.string() ?: "", DmVideo::class.java)
            }
        }.getOrNull()
    }

    private val RE_PUB = Regex("(?i)subscribe|abonne|enjoy this|plot & highlights|highlights|heart-touching|" +
        "emotional depth|bite-sized|hooked|edge of your seat|like and share|commente|watch the full|full movie|" +
        "dailymotion|english sub|don't forget|n'oubliez pas|follow us|suivez-nous|copyright|disclaimer")
    // 2026-10-20 (user : « pourquoi c'est pas français ? ») : « doubl » attrapait « Double Life »,
    //   et des chaînes titrent « Français | Full Short Drama | English Sub » sur une vidéo en
    //   anglais sous-titrée. On exige un vrai marqueur VF et on rejette tout marqueur de sous-titres.
    // 2026-10-20 (test du user sur 9 vidéos) : seules celles marquées « Doublage » étaient en
    //   français (5/5) ; les « Série Complète | Short Drama Français » sans ce marqueur sont des
    //   réuploads en VO avec la fiche française copiée. On exige donc « Doublage / Doublé / VF ».
    private val RE_VF = Regex("(?i)doublage|doubl[ée]e?s?\\b|\\bvf\\b")
    private val RE_FILM_COMPLET = Regex("(?i)film\\s+complet")
    private val RE_VO = Regex("(?i)english|eng\\s*sub|\\bensub|\\bsub(s|bed|titles?)?\\b|sous[- ]?titr|vostfr|\\bvost\\b|español|espanol|subtitul|legendado|\\bhindi\\b")

    /** Garde uniquement les séries complètes en français, au format vertical. */
    private fun estVf(v: DmVideo): Boolean {
        val t = v.title ?: return false
        if ((v.aspect ?: 0.56) > 1.0) return false
        if ((v.duration ?: 0) < 40 * 60) return false
        // 2026-10-20 (user : « les trois premières que j'ai cliquées, rien de français ») : les titres
        //   « … | Film Complet en Français » sont un appât (audio anglais, langue déclarée « en »,
        //   même chez des chaînes qui déclarent « fr »). On ne garde que les vraies VF : langue
        //   déclarée française ET marqueur « Doublage / Doublé / VF / Série Complète », sans « Film complet ».
        if (v.language != null && v.language != "fr") return false
        if (RE_FILM_COMPLET.containsMatchIn(t)) return false
        return RE_VF.containsMatchIn(t) && !RE_VO.containsMatchIn(t)
    }

    /** « (Doublage) LE TYRAN A PRIS SON MAÎTRE | Série Complète | Short Drama Français » → « Le tyran a pris son maître ». */
    private fun nettoyerTitre(brut: String): String {
        // Caractères invisibles (espaces de largeur nulle) glissés dans certains titres.
        var t = decodeHtml(brut).replace(Regex("[\\u200B-\\u200D\\uFEFF\\u2060]"), "")
        t = t.replace(Regex("(?i)\\((doublage|doublé|vf|fr|full|complet)\\)|\\[[^\\]]*]"), " ")
        t = t.split("|", "｜").first()
        // Variantes sans parenthèses : « DoublageHÉRITIÈRE… », « Doublage LE QUARTERBACK… », « …Épisode complet ».
        t = t.replace(Regex("(?i)doublage"), " ")
        t = t.replace(Regex("(?i)\\b[ée]pisode\\s+complet.*$"), " ")
        t = t.replace(Regex("(?i)\\s+fran[cç]ais\\s*$"), " ")
        t = t.replace(Regex("(?i)\\b(film|s[ée]rie)\\s+compl[eè]te?\\b.*$"), " ")
        t = t.replace(Regex("(?i)short\\s*drama|doubl[ée]e?s?\\s+en\\s+fran[cç]ais|en\\s+fran[cç]ais|\\bfull\\b"), " ")
        t = t.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', ':', ',', '.', '·')
        if (t.any { it.isLetter() } && t == t.uppercase()) t = t.lowercase().replaceFirstChar { it.titlecase() }
        return t
    }

    private fun nettoyerResume(d: String?): String? {
        if (d.isNullOrBlank()) return null
        // Beaucoup de chaînes collent un texte publicitaire en anglais (« Enjoy this captivating
        //   drama… Subscribe now… ») : on retire ces lignes, le gras markdown et les émojis de tête.
        val t = decodeHtml(d.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), ""))
            .replace("**", "")
            .lines()
            .map { it.trim().replace(Regex("^[^\\p{L}\\p{N}«\"(]+"), "").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !RE_PUB.containsMatchIn(it) && !it.contains("|") }
            .joinToString("\n").replace(Regex("#\\S+"), "").trim()
        return t.take(700).takeIf { it.length >= 30 }
    }

    /** Range une vidéo dans sa série (créée au besoin). force = accepter même hors filtre (lien direct). */
    private fun dmAjouter(v: DmVideo, force: Boolean = false): DmSerie? {
        val xid = v.id ?: return null
        if (!force && !estVf(v)) return null
        val titre = nettoyerTitre(v.title ?: return null)
        if (titre.length < 2) return null
        val cle = normalize(titre)
        if (cle.isBlank()) return null
        val s = dmSeries.getOrPut(cle) {
            DmSerie(cle, titre, nettoyerResume(v.description), v.thumbnail, v.duration ?: 0).also { it.xid = xid }
        }
        synchronized(s) {
            if (s.copies.none { it.id == xid }) s.copies.add(v)
            s.copies.sortByDescending { it.views ?: 0 }
            s.vues = maxOf(s.vues, v.views ?: 0)
            s.date = maxOf(s.date, v.created ?: 0)
            if (s.resume == null) s.resume = nettoyerResume(v.description)
            if (s.affiche == null) s.affiche = v.thumbnail
        }
        dmParXid[xid] = cle
        return s
    }

    private fun dmRanger(videos: List<DmVideo>): List<String> =
        videos.mapNotNull { dmAjouter(it)?.cle }.distinct()

    /** Charge les rangées Dailymotion de l'accueil (≈ 13 petites requêtes en parallèle, cache 1 h). */
    private suspend fun ensureDm() = dmMutex.withLock {
        if (System.currentTimeMillis() - dmLoadedAt < CACHE_MS && dmRangees.isNotEmpty()) return@withLock
        val rangees = coroutineScope {
            // Relevé 2026-10-20 : la recherche « doublage » seule ramène ~400 séries VF distinctes.
            val nouveautes = async { (1..3).flatMap { p -> dmSearch(DM_BASE_QUERY, "recent", p) } }
            val vues = async { (1..3).flatMap { p -> dmSearch(DM_BASE_QUERY, "visited", p) } }
            val doublage = async { (1..3).flatMap { p -> dmSearch(DM_BASE_QUERY, "relevance", p) } }
            val themes = DM_THEMES.map { (nom, q) -> nom to async { dmSearch(q) } }
            listOf(
                "Séries complètes en VF · nouveautés" to dmRanger(nouveautes.await()),
                "Les plus vues en VF" to dmRanger(vues.await()),
                "Doublées en français" to dmRanger(doublage.await()),
            ) + themes.map { (nom, d) -> "$nom · VF" to dmRanger(d.await()) }
        }
        if (dmSeries.isNotEmpty()) {
            dmRangees = rangees
            dmLoadedAt = System.currentTimeMillis()
        }
        Log.i(TAG, "Dailymotion : ${dmSeries.size} séries VF, ${rangees.size} rangées")
    }

    private fun DmSerie.toMovie() = Movie(
        id = DM_PREFIX + xid,
        title = titre,
        overview = resume,
        runtime = duree / 60,
        quality = "VF",
        poster = affiche,
        banner = affiche,
    )

    private suspend fun dmSerieDe(id: String): DmSerie? {
        val xid = id.removePrefix(DM_PREFIX)
        dmParXid[xid]?.let { dmSeries[it] }?.let { return it }
        val v = dmVideo(xid) ?: return null
        return dmAjouter(v, force = true)
    }

    private suspend fun dmGetMovie(id: String): Movie {
        val s = dmSerieDe(id) ?: return Movie(id = id, title = "")
        // Autres copies du même titre (autres chaînes) → serveurs de secours.
        runCatching { dmSearch("${s.titre} doublage").forEach { dmAjouter(it) } }
        runCatching { ensureDm() }
        val recos: List<Show> = dmRangees.filter { (_, cles) -> s.cle in cles }
            .flatMap { it.second }.ifEmpty { dmRangees.firstOrNull()?.second.orEmpty() }
            .distinct().filter { it != s.cle }.shuffled().take(16)
            .mapNotNull { dmSeries[it]?.toMovie() }
        val genres = dmRangees.withIndex().filter { (i, r) -> i >= 3 && s.cle in r.second }
            .map { (i, r) -> Genre(id = "dm:${i - 3}", name = r.first) }
        val nb = synchronized(s) { s.copies.size }
        return Movie(
            id = id,
            title = s.titre,
            overview = listOfNotNull(s.resume, if (nb > 1) "Disponible sur $nb chaînes Dailymotion." else null).joinToString("\n\n"),
            runtime = s.duree / 60,
            quality = "VF",
            poster = s.affiche,
            banner = s.affiche,
            genres = genres,
            recommendations = recos,
        )
    }

    private suspend fun dmServers(id: String): List<Video.Server> {
        val s = dmSerieDe(id) ?: return emptyList()
        val xid = id.removePrefix(DM_PREFIX)
        val copies = synchronized(s) { s.copies.toList() }.sortedByDescending { if (it.id == xid) 1 else 0 }
        return copies.take(8).map { c ->
            Video.Server(
                id = "dmv|${c.id}",
                name = "Dailymotion · ${c.owner ?: "chaîne"} [VF]",
                src = "https://www.dailymotion.com/video/${c.id}",
            ).also { it.language = "VF" }
        }
    }

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
        runCatching { ensureDm() }.onFailure { Log.w(TAG, "Dailymotion : ${it.message}") }
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
            .distinctBy { it.id }.take(10)
        // + 5 séries complètes VF de Dailymotion (les plus vues du moment).
        val dmCarrousel = dmRangees.getOrNull(1)?.second.orEmpty().take(15).shuffled().take(5).mapNotNull { dmSeries[it] }
        val itemsCarrousel: List<Show> = carrousel.map { it.toTvShow() } + dmCarrousel.map { it.toMovie() }
        if (itemsCarrousel.isNotEmpty()) out.add(Category(Category.FEATURED, itemsCarrousel.shuffled()))

        // 2) Dailymotion : les grandes rangées VF en tête (le plus gros du catalogue).
        fun rangeeDm(nom: String, cles: List<String>) {
            val l = cles.mapNotNull { dmSeries[it] }.take(30)
            if (l.size >= 4) out.add(Category(nom, l.map { it.toMovie() }))
        }
        dmRangees.take(2).forEach { (nom, cles) -> rangeeDm(nom, cles) }

        // 3) Rangées « éditoriales » Mini Drama.
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
        // Rangées thématiques Dailymotion (VF).
        dmRangees.drop(2).forEach { (nom, cles) -> rangeeDm(nom, cles) }
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
        runCatching { ensureFullCatalog() }
        if (query.isBlank()) {
            // Écran de recherche vide → thèmes VF (Dailymotion) + catégories du site minutedrama.
            val dm = DM_THEMES.mapIndexed { i, (nom, _) -> Genre(id = "dm:$i", name = "$nom · VF") }
            return dm + categoryNames.entries.sortedBy { it.value }.map { Genre(id = it.key.toString(), name = it.value) }
        }
        // Dailymotion : recherche en ligne + séries déjà connues dont le titre correspond.
        val qn = normalize(query)
        val dmEnLigne = runCatching { dmRanger(dmSearch("$query doublage")) }.getOrDefault(emptyList())
        val dmLocal = dmSeries.values.filter { normalize(it.titre).contains(qn) }.map { it.cle }
        val dmItems = (dmLocal + dmEnLigne).distinct().mapNotNull { dmSeries[it]?.toMovie() }
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
        return scored.sortedByDescending { it.first }.map { it.second.toTvShow() } + dmItems
    }

    /** Onglet Films = séries complètes VF de Dailymotion, des plus récentes aux plus anciennes. */
    override suspend fun getMovies(page: Int): List<Movie> {
        // 2026-10-20 (user : « dans Films il n'y a plus rien de Dailymotion ») : depuis le filtre VF
        //   strict, une seule recherche par page ne suffisait plus à remplir l'écran. Page 1 = tout ce
        //   que l'accueil a déjà trouvé (du plus récent au plus ancien) ; pages suivantes = deux
        //   recherches « récentes » de plus, sans redonner ce qui a déjà été affiché.
        if (page == 1) {
            filmsDejaDonnes.clear()
            runCatching { ensureDm() }
            val l = dmSeries.values.sortedByDescending { it.date }
            filmsDejaDonnes.addAll(l.map { it.cle })
            return l.map { it.toMovie() }
        }
        val cles = coroutineScope {
            listOf(
                async { dmSearch(DM_BASE_QUERY, "recent", page) },
                async { dmSearch(DM_BASE_QUERY, "visited", page) },
                async { dmSearch(DM_BASE_QUERY, "relevance", page) },
            ).awaitAll().flatten()
        }.let { dmRanger(it) }
        return cles.filter { filmsDejaDonnes.add(it) }.mapNotNull { dmSeries[it]?.toMovie() }
    }
    private val filmsDejaDonnes = java.util.Collections.synchronizedSet(HashSet<String>())

    override suspend fun getTvShows(page: Int): List<TvShow> {
        ensureFullCatalog()
        val pageSize = 30
        val all = shows.values.sortedByDescending { it.id ?: 0 }
        return all.drop((page - 1) * pageSize).take(pageSize).map { it.toTvShow() }
    }

    override suspend fun getMovie(id: String): Movie =
        if (id.startsWith(DM_PREFIX)) dmGetMovie(id) else Movie(id = id, title = "")

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
        if (id.startsWith("dm:")) {
            val (nom, q) = DM_THEMES.getOrNull(id.removePrefix("dm:").toIntOrNull() ?: -1) ?: return Genre(id = id, name = id)
            val items = dmRanger(dmSearch(q, "relevance", page)).mapNotNull { dmSeries[it]?.toMovie() }
            return Genre(id = id, name = "$nom · VF", shows = items)
        }
        val catId = id.toIntOrNull() ?: return Genre(id = id, name = id)
        if (page > 1) return Genre(id = id, name = categoryNames[catId] ?: id)
        val ids = loadCategory(catId)
        return Genre(id = id, name = categoryNames[catId] ?: id, shows = ids.mapNotNull { shows[it]?.toTvShow() })
    }

    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = "")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (id.startsWith(DM_PREFIX)) return dmServers(id)
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
        if (server.id.startsWith("dmv|")) return DailymotionExtractor().extract(server.src)
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
