package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * FRAnime (franime.fr) — Provider anime FR, animes-only.
 *
 * Architecture v2 (2026-05-16) : **JSON-driven**.
 *
 * Le site expose `https://api.franime.fr/api/animes` qui retourne la liste
 * COMPLÈTE des animes (2370 entrées) en un seul JSON ~3 MB avec pour chaque
 * anime : id, title, titleO, titles, banner, affiche, description, note,
 * themes, format, startDate, endDate, status, nsfw, **saisons:[{title,
 * episodes:[{title, lang:{vo:{lecteurs:[…]}, vf:{lecteurs:[…]}}}]}]**,
 * updatedDate, updatedDateVF.
 *
 * Du coup :
 *  - getHome / search / getTvShow / getEpisodesBySeason : tous servis
 *    INSTANTANÉMENT depuis le cache JSON (1er load 3-5s pour fetch + parse,
 *    ensuite memory cache + disk cache 24h)
 *  - getServers : liste les lecteurs (sibnet, sendvid, vidmoly, filemoon)
 *    avec src = URL API resolver `/api/anime/<id>/<s>/<ep>/<lang>/<idx>`
 *  - getVideo : appel API resolver → URL `franime.fr/watch2/?a=<hex>` →
 *    FranimeExtractor (WebView) résout via Turnstile + iframe intercept →
 *    URL embed finale → Extractor.extract() délègue au SibnetExtractor /
 *    SendvidExtractor / FilemoonExtractor / VidMoLyExtractor existants.
 *
 * ID schemes pour Streamflix :
 *  - TvShow   = "<anime_id>"               ex: "8147"
 *  - Season   = "<anime_id>/<season>"      ex: "8147/1"
 *  - Episode  = "<anime_id>/<season>/<ep>" ex: "8147/1/1"
 *  - Server   = "<episodeId>|<lang>|<idx>" ex: "8147/1/1|vo|0"
 */
object FranimeProvider : Provider {

    override val name = "FRAnime"
    override val baseUrl = "https://franime.fr/"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_franime"
    override val language = "fr"

    private const val TAG = "FRAnime"
    private const val API_BASE = "https://api.franime.fr/"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient by lazy {
        NetworkClient.default.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val prefs by lazy {
        StreamFlixApp.instance.applicationContext
            .getSharedPreferences("franime_cache", Context.MODE_PRIVATE)
    }

    // ── Cache JSON catalogue complet ────────────────────────────────────
    private val CATALOGUE_TTL_MS = 24L * 60L * 60L * 1000L
    @Volatile private var catalogue: List<JSONObject>? = null
    // 2026-05-16 v8 : Long au lieu de Int. Certains IDs FRAnime dépassent
    // Int.MAX_VALUE (ex: 2,864,154,715) → overflow donnait des anime_id négatifs
    // dans l'URL (anime_id=-1430812581) → 404 garanti.
    @Volatile private var catalogueIndex: Map<Long, JSONObject>? = null  // id → anime
    // 2026-05-18 : mutex pour SÉRIALISER les calls à loadCatalogue.
    //   Sans ça, 3-4 threads parallèles (preExtract, getHome, search…) parsaient
    //   chacun ~6MB de JSON → OOM sur Chromecast (heap 384MB).
    //   Maintenant : un seul parse, les autres attendent + récupèrent le cache.
    private val loadCatalogueMutex = kotlinx.coroutines.sync.Mutex()

    fun init(context: Context) {
        // 2026-05-16 v9 : pre-warm DÉSACTIVÉ — le catalogue 3MB causait OOM
        // au démarrage. Chargement à la demande maintenant.
        // Aussi : purge l'ancien `catalogue_json` qui était stocké dans prefs
        // (3MB en mémoire pour tout le cycle de vie de l'app).
        try {
            if (prefs.contains("catalogue_json")) {
                prefs.edit().remove("catalogue_json").apply()
                Log.d(TAG, "init: purged old SharedPreferences catalogue_json")
            }
        } catch (_: Exception) {}
    }

    private suspend fun loadCatalogue(): List<JSONObject> {
        // Fast-path : si déjà en mémoire, retourner immédiatement (no mutex).
        catalogue?.let { return it }
        // Sinon, serialize : un seul thread à la fois lit/parse le catalogue.
        //   Les autres attendent et récupèrent le résultat depuis le memory cache.
        return loadCatalogueMutex.withLock {
            // Re-check après acquisition du lock (autre thread a peut-être chargé).
            catalogue?.let { return@withLock it }
            doLoadCatalogue()
        }
    }

    private suspend fun doLoadCatalogue(): List<JSONObject> {
        // 1) Disk cache via fichier (PAS SharedPreferences — 3MB en prefs
        //    garde la string en mémoire pour tout le cycle de vie de l'app).
        val now = System.currentTimeMillis()
        val cacheFile = java.io.File(
            StreamFlixApp.instance.applicationContext.cacheDir,
            "franime_catalogue.json"
        )
        val cachedAt = prefs.getLong("catalogue_at", 0L)
        if (cacheFile.exists() && now - cachedAt < CATALOGUE_TTL_MS) {
            try {
                val cachedJson = cacheFile.readText()
                val parsed = parseCatalogue(cachedJson)
                Log.d(TAG, "loadCatalogue from disk file (${parsed.size} animes, age=${(now-cachedAt)/1000}s)")
                catalogue = parsed
                catalogueIndex = parsed.associateBy { it.optLong("id") }
                return parsed
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "loadCatalogue OOM reading cache, deleting file")
                cacheFile.delete()
                System.gc()
                return emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "loadCatalogue disk parse failed: ${e.message}")
            }
        }
        // 2) Network fetch
        Log.d(TAG, "loadCatalogue fetching from network")
        val body = try {
            fetchText("${API_BASE}api/animes", timeoutMs = 45_000L)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "loadCatalogue OOM fetching network")
            System.gc()
            return emptyList()
        }
        if (body.isBlank() || body.length < 100) {
            Log.w(TAG, "loadCatalogue empty response")
            return emptyList()
        }
        val parsed = try {
            parseCatalogue(body)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "loadCatalogue OOM parsing, giving up")
            System.gc()
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "loadCatalogue parse failed: ${e.message}")
            return emptyList()
        }
        catalogue = parsed
        catalogueIndex = parsed.associateBy { it.optLong("id") }
        // Write to file (not prefs) to avoid keeping 3MB in memory forever
        try {
            cacheFile.writeText(body)
            prefs.edit().putLong("catalogue_at", now).apply()
        } catch (_: Exception) {}
        Log.d(TAG, "loadCatalogue fetched + cached (${parsed.size} animes)")
        return parsed
    }

    private fun parseCatalogue(json: String): List<JSONObject> {
        val arr = JSONArray(json)
        val list = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        return list
    }

    // ── HTTP helpers ────────────────────────────────────────────────────
    private suspend fun fetchText(url: String, timeoutMs: Long = 20_000L): String =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", baseUrl)
                    .header("Accept", "application/json,text/plain,*/*")
                    .build()
                withTimeoutOrNull(timeoutMs) {
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) ""
                        else resp.body?.string() ?: ""
                    }
                } ?: ""
            } catch (e: Exception) {
                Log.w(TAG, "fetchText($url) failed: ${e.message}")
                ""
            }
        }

    // ── Helpers conversion JSONObject → models ──────────────────────────
    private fun JSONObject.bestTitle(): String =
        optString("titleO").ifBlank {
            optString("title").ifBlank {
                optJSONObject("titles")?.let { it.optString("en_jp").ifBlank { it.optString("en_us").ifBlank { it.optString("ja_jp") } } } ?: ""
            }
        }.ifBlank { "Anime #${optLong("id")}" }

    private fun JSONObject.bestPoster(): String? {
        return optString("affiche").takeIf { it.startsWith("http") }
            ?: optString("affiche_small").takeIf { it.startsWith("http") }
    }

    private fun JSONObject.bestBanner(): String? =
        optString("banner").takeIf { it.startsWith("http") }

    private fun JSONObject.bestYear(): String? =
        optString("startDate").takeIf { it.isNotBlank() }
            ?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1) }

    private fun JSONObject.bestOverview(): String? =
        optString("description").takeIf { it.isNotBlank() }

    private fun JSONObject.toTvShow(): TvShow = TvShow(
        id = optLong("id").toString(),
        title = bestTitle(),
        poster = bestPoster(),
        banner = bestBanner() ?: bestPoster(),
        released = bestYear(),
        overview = bestOverview(),
    )

    /** 2026-05-17 (user "Les films sont considérés comme des séries") :
     *  certains animes ont format="Film"/"movie"/"FILM" — ce sont de vrais
     *  long-métrages (1 épisode/1 saison). Détection insensible à la casse.
     *  Les ONA/OAV/OVA/Special restent classés en séries (catalogue anime classique). */
    private fun JSONObject.isFilm(): Boolean {
        val format = optString("format").lowercase().trim()
        return format == "film" || format == "movie"
    }

    private fun JSONObject.toMovie(): Movie = Movie(
        id = optLong("id").toString(),
        title = bestTitle(),
        poster = bestPoster(),
        banner = bestBanner() ?: bestPoster(),
        released = bestYear(),
        overview = bestOverview(),
    )

    /** Retourne soit Movie (si format=film) soit TvShow (sinon). Utilisé par
     *  les list builders (home, search, browse) pour que l'AppAdapter pose
     *  le bon viewholder + route le clic vers la bonne fragment. */
    private fun JSONObject.toItem(): com.streamflixreborn.streamflix.adapters.AppAdapter.Item =
        if (isFilm()) toMovie() else toTvShow()

    // ── HOME ─────────────────────────────────────────────────────────────

    override suspend fun getHome(): List<Category> {
        // 2026-05-16 v10 : Bootstrap la session FRAnime au premier accès du
        // provider — ouvre franime.fr/ dans une WebView background UNE FOIS
        // pour faire jouer le "Chargement profil/préférences/animes" qui
        // établit la session Next.js. Toutes les extractions d'épisodes
        // suivantes réutiliseront cette même WebView.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { com.streamflixreborn.streamflix.extractors.FranimeSession.bootstrap() } catch (_: Exception) {}
        }
        val all = loadCatalogue()
        if (all.isEmpty()) return emptyList()

        val categories = mutableListOf<Category>()

        // Featured : 8 derniers mis à jour (mix films/séries)
        val recent = all.sortedByDescending { it.optString("updatedDate") }.take(8)
            .map { it.toItem() }
        if (recent.isNotEmpty()) categories.add(Category(name = Category.FEATURED, list = recent))

        // Derniers ajouts (par updatedDate, mix films/séries)
        val latest = all.sortedByDescending { it.optString("updatedDate") }.take(40)
            .map { it.toItem() }
        if (latest.isNotEmpty()) categories.add(Category(name = "Derniers ajouts", list = latest))

        // 2026-05-17 : section dédiée Films (long-métrages anime)
        val films = all.filter { it.isFilm() }
            .sortedByDescending { it.optString("updatedDate") }
            .take(40)
            .map { it.toMovie() }
        if (films.isNotEmpty()) categories.add(Category(name = "Films", list = films))

        // Les plus aimés (par note décroissante, mix)
        val topRated = all.sortedByDescending { it.optDouble("note", 0.0) }.take(40)
            .map { it.toItem() }
        if (topRated.isNotEmpty()) categories.add(Category(name = "Les plus aimés", list = topRated))

        // En cours (status = EN COURS) — séries uniquement (les films n'ont pas de statut "en cours")
        val ongoing = all.filter { !it.isFilm() && it.optString("status").contains("EN COURS", true) }.take(40)
            .map { it.toTvShow() }
        if (ongoing.isNotEmpty()) categories.add(Category(name = "En cours de diffusion", list = ongoing))

        // Catégories par thème (top thèmes)
        val themeCount = mutableMapOf<String, Int>()
        all.forEach { a ->
            val themes = a.optJSONArray("themes") ?: return@forEach
            for (i in 0 until themes.length()) {
                val t = themes.optString(i).ifBlank { continue }
                themeCount[t] = (themeCount[t] ?: 0) + 1
            }
        }
        val topThemes = themeCount.entries.sortedByDescending { it.value }.take(5)
        for ((theme, _) in topThemes) {
            val items = all.filter { a ->
                val themes = a.optJSONArray("themes") ?: return@filter false
                (0 until themes.length()).any { themes.optString(it) == theme }
            }.take(30).map { it.toItem() }
            if (items.isNotEmpty()) categories.add(Category(name = theme, list = items))
        }

        Log.d(TAG, "getHome → ${categories.size} categories from ${all.size} animes")
        return categories
    }

    // ── SEARCH ───────────────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        // 2026-05-18 v81 : empty query → expose genre picker (anime FR).
        //   Pagination interne 60/page pour les query non-vides.
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return listOf(
                Genre(id = "action", name = "Action"),
                Genre(id = "aventure", name = "Aventure"),
                Genre(id = "comedie", name = "Comédie"),
                Genre(id = "drame", name = "Drame"),
                Genre(id = "ecchi", name = "Ecchi"),
                Genre(id = "fantastique", name = "Fantastique"),
                Genre(id = "horreur", name = "Horreur"),
                Genre(id = "isekai", name = "Isekai"),
                Genre(id = "mecha", name = "Mecha"),
                Genre(id = "musique", name = "Musique"),
                Genre(id = "mystere", name = "Mystère"),
                Genre(id = "psychologique", name = "Psychologique"),
                Genre(id = "romance", name = "Romance"),
                Genre(id = "school-life", name = "Tranches de vie"),
                Genre(id = "science-fiction", name = "Science-Fiction"),
                Genre(id = "seinen", name = "Seinen"),
                Genre(id = "shonen", name = "Shōnen"),
                Genre(id = "shojo", name = "Shōjo"),
                Genre(id = "sport", name = "Sport"),
                Genre(id = "surnaturel", name = "Surnaturel"),
            )
        }
        val all = loadCatalogue()
        if (all.isEmpty()) return emptyList()
        val q = query.lowercase().trim()
        val filtered = all.filter { a ->
            a.bestTitle().lowercase().contains(q) ||
                    a.optString("titleO").lowercase().contains(q) ||
                    a.optJSONObject("titles")?.let { titles ->
                        titles.keys().asSequence().any { titles.optString(it).lowercase().contains(q) }
                    } == true
        }
        // Pagination interne 60/page
        val pageSize = 60
        val start = (page - 1) * pageSize
        if (start >= filtered.size) return emptyList()
        val results = filtered.drop(start).take(pageSize).map { it.toItem() }
        Log.d(TAG, "search('$query', page=$page) → ${results.size} results (total=${filtered.size})")
        return results
    }

    // ── MOVIES / TV SHOWS BROWSE ─────────────────────────────────────────

    override suspend fun getMovies(page: Int): List<Movie> {
        // 2026-05-17 : retourne les animes long-métrages (format=Film/movie).
        val all = loadCatalogue()
        if (all.isEmpty()) return emptyList()
        val films = all.filter { it.isFilm() }
        if (films.isEmpty()) return emptyList()
        val pageSize = 30
        val start = (page - 1) * pageSize
        if (start >= films.size) return emptyList()
        val sorted = films.sortedBy { it.bestTitle().lowercase() }
        return sorted.drop(start).take(pageSize).map { it.toMovie() }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val all = loadCatalogue()
        if (all.isEmpty()) return emptyList()
        // 2026-05-17 : exclure les films (mappés via getMovies).
        val series = all.filter { !it.isFilm() }
        if (series.isEmpty()) return emptyList()
        val pageSize = 30
        val start = (page - 1) * pageSize
        if (start >= series.size) return emptyList()
        // Tri alphabétique par title
        val sorted = series.sortedBy { it.bestTitle().lowercase() }
        return sorted.drop(start).take(pageSize).map { it.toTvShow() }
    }

    // ── DETAIL ───────────────────────────────────────────────────────────

    private suspend fun getAnimeById(id: Long): JSONObject? {
        loadCatalogue()
        return catalogueIndex?.get(id)
    }

    override suspend fun getTvShow(id: String): TvShow {
        val animeId = id.toLongOrNull() ?: return fallback(id)
        val anime = getAnimeById(animeId) ?: return fallback(id)
        val saisons = anime.optJSONArray("saisons") ?: JSONArray()
        val poster = anime.bestPoster()
        val seasons = (0 until saisons.length()).map { i ->
            val s = saisons.optJSONObject(i)
            val sNum = i + 1
            Season(
                id = "$id/$sNum",
                number = sNum,
                title = s?.optString("title")?.ifBlank { "Saison $sNum" } ?: "Saison $sNum",
                poster = poster,
            )
        }.ifEmpty {
            listOf(Season(id = "$id/1", number = 1, title = "Saison 1", poster = poster))
        }
        return TvShow(
            id = id,
            title = anime.bestTitle(),
            poster = poster,
            banner = anime.bestBanner() ?: poster,
            overview = anime.bestOverview(),
            released = anime.bestYear(),
            seasons = seasons,
        )
    }

    private fun fallback(id: String): TvShow = TvShow(
        id = id,
        title = "Anime #$id",
        seasons = listOf(Season(id = "$id/1", number = 1, title = "Saison 1")),
    )

    override suspend fun getMovie(id: String): Movie {
        // 2026-05-17 : récupère le film par id depuis le catalogue.
        val animeId = id.toLongOrNull() ?: return Movie(id = id, title = "Anime #$id")
        val anime = getAnimeById(animeId) ?: return Movie(id = id, title = "Anime #$id")
        val poster = anime.bestPoster()
        return Movie(
            id = id,
            title = anime.bestTitle(),
            poster = poster,
            banner = anime.bestBanner() ?: poster,
            overview = anime.bestOverview(),
            released = anime.bestYear(),
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // seasonId = "<anime_id>/<season>"
        val parts = seasonId.split("/")
        if (parts.size < 2) return emptyList()
        val animeId = parts[0].toLongOrNull() ?: return emptyList()
        val sNum = parts[1].toIntOrNull() ?: return emptyList()
        val anime = getAnimeById(animeId) ?: return emptyList()
        val saisons = anime.optJSONArray("saisons") ?: return emptyList()
        if (sNum > saisons.length()) return emptyList()
        val saison = saisons.optJSONObject(sNum - 1) ?: return emptyList()
        val episodes = saison.optJSONArray("episodes") ?: return emptyList()
        return (0 until episodes.length()).map { i ->
            val ep = episodes.optJSONObject(i)
            val n = i + 1
            Episode(
                id = "${animeId}/$sNum/$n",
                number = n,
                title = ep?.optString("title")?.ifBlank { "Épisode $n" } ?: "Épisode $n",
            )
        }
    }

    // ── SERVERS / VIDEO ─────────────────────────────────────────────────

    /** Slugifie un titre anime pour générer l'URL d'épisode FRAnime.
     *  Ex: "Needy Girl Overdose" → "needy-girl-overdose" */
    private fun slugify(s: String): String {
        if (s.isBlank()) return ""
        return s.lowercase()
            // Translittération accents
            .replace(Regex("[àáâäãå]"), "a")
            .replace(Regex("[èéêë]"), "e")
            .replace(Regex("[ìíîï]"), "i")
            .replace(Regex("[òóôöõ]"), "o")
            .replace(Regex("[ùúûü]"), "u")
            .replace(Regex("[ýÿ]"), "y")
            .replace(Regex("[ñ]"), "n")
            .replace(Regex("[ç]"), "c")
            .replace("&", "and")
            .replace("'", "")
            .replace("’", "")
            // Remplace tout caractère non alphanumérique par un tiret
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        // 2026-05-17 : id format dépend du videoType :
        //   - Series/episode : "<anime_id>/<season>/<ep>"
        //   - Film (videoType=Movie) : "<anime_id>" — on extrait l'épisode 1
        //     de la saison 1 (les films ont 1 saison avec 1 épisode unique).
        val parts = id.split("/")
        val animeId: Long
        val sNum: Int
        val epNum: Int
        if (parts.size >= 3) {
            animeId = parts[0].toLongOrNull() ?: return emptyList()
            sNum = parts[1].toIntOrNull() ?: return emptyList()
            epNum = parts[2].toIntOrNull() ?: return emptyList()
        } else if (parts.size == 1 && videoType is Video.Type.Movie) {
            // Film : route vers saison 1 / épisode 1
            animeId = parts[0].toLongOrNull() ?: return emptyList()
            sNum = 1
            epNum = 1
        } else {
            return emptyList()
        }
        val anime = getAnimeById(animeId) ?: return emptyList()
        val saisons = anime.optJSONArray("saisons") ?: return emptyList()
        if (sNum > saisons.length()) return emptyList()
        val saison = saisons.optJSONObject(sNum - 1) ?: return emptyList()
        val episodes = saison.optJSONArray("episodes") ?: return emptyList()
        if (epNum > episodes.length()) return emptyList()
        val episode = episodes.optJSONObject(epNum - 1) ?: return emptyList()
        val langObj = episode.optJSONObject("lang") ?: return emptyList()

        // 2026-05-16 v3 : la vraie URL d'épisode FRAnime est
        //   https://franime.fr/anime/{slug}?s={s}&ep={ep}&lang={lang}&anime_id={id}
        // PAS /watch2/?a=hex (qui était l'iframe interne, pas la page).
        // Cette page contient un bouton "Regarder l'épisode" qui injecte
        // l'iframe vidéo après clic — FranimeExtractor gère le clic auto.
        val slug = slugify(anime.bestTitle()).ifBlank { "anime" }

        val servers = mutableListOf<Video.Server>()
        for (lang in listOf("vostfr", "vo", "vf")) {
            val l = langObj.optJSONObject(lang) ?: continue
            val lecteurs = l.optJSONArray("lecteurs") ?: continue
            val langLabel = when (lang) { "vo" -> "VOSTFR"; "vf" -> "VF"; else -> lang.uppercase() }
            for (i in 0 until lecteurs.length()) {
                val hostName = lecteurs.optString(i).ifBlank { continue }
                if (hostName.contains("TELECHARGEMENT", ignoreCase = true)) continue
                val display = "${hostName.replaceFirstChar { it.uppercase() }} ($langLabel)"
                // src = URL page épisode réelle. Le fragment `#lecteur={nom}`
                // est lu par FranimeSession JS pour cliquer la bonne option
                // dans le dropdown FRAnime ("Lecteur SIBNET", "Lecteur FILEMOON"…).
                // L'index `&l=$i` est gardé en fallback.
                val src = "${baseUrl}anime/$slug?s=$sNum&ep=$epNum&lang=$lang&anime_id=$animeId&l=$i#lecteur=${hostName.lowercase()}"
                servers.add(Video.Server(id = "$id|$lang|$i", name = display, src = src))
            }
        }
        Log.d(TAG, "getServers($id) → ${servers.size} servers (slug=$slug)")
        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        Log.d(TAG, "getVideo(${server.name}) pageUrl=${server.src}")
        // Plus d'appel API : on charge directement la page d'épisode dans
        // FranimeExtractor qui clique le bouton "Regarder l'épisode" et
        // capture l'URL iframe (sibnet/sendvid/etc.).
        return Extractor.extract(server.src, server)
    }

    // ── GENRE / PEOPLE ──────────────────────────────────────────────────

    // 2026-05-18 : map des slugs exposés dans search() empty vers les tokens
    //   à matcher dans le champ "themes" de chaque anime. Match permissif :
    //   case-insensitive + tolérance accents (â→a, é→e, etc.).
    private val genreSlugAliases = mapOf(
        "action" to listOf("action"),
        "aventure" to listOf("aventure", "adventure"),
        "comedie" to listOf("comedie", "comédie", "comedy"),
        "drame" to listOf("drame", "drama"),
        "ecchi" to listOf("ecchi"),
        "fantastique" to listOf("fantastique", "fantasy", "fantaisie"),
        "horreur" to listOf("horreur", "horror"),
        "isekai" to listOf("isekai"),
        "mecha" to listOf("mecha"),
        "musique" to listOf("musique", "music"),
        "mystere" to listOf("mystere", "mystère", "mystery"),
        "psychologique" to listOf("psychologique", "psychological"),
        "romance" to listOf("romance"),
        "school-life" to listOf("school", "tranche de vie", "tranches de vie", "slice of life", "school life"),
        "science-fiction" to listOf("science-fiction", "sci-fi", "scifi", "science fiction"),
        "seinen" to listOf("seinen"),
        "shonen" to listOf("shonen", "shōnen", "shonen"),
        "shojo" to listOf("shojo", "shōjo", "shoujo"),
        "sport" to listOf("sport", "sports"),
        "surnaturel" to listOf("surnaturel", "supernatural"),
    )

    private fun normalizeText(s: String): String = s.lowercase()
        .replace('à', 'a').replace('â', 'a').replace('ä', 'a')
        .replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('ë', 'e')
        .replace('î', 'i').replace('ï', 'i')
        .replace('ô', 'o').replace('ö', 'o').replace('ō', 'o')
        .replace('ù', 'u').replace('û', 'u').replace('ü', 'u')
        .replace('ç', 'c')

    override suspend fun getGenre(id: String, page: Int): Genre {
        val all = loadCatalogue()
        val tokens = genreSlugAliases[id]?.map { normalizeText(it) } ?: listOf(normalizeText(id))
        val matching = all.filter { a ->
            // Vérifier dans themes ET genres (parfois l'un, parfois l'autre selon l'item)
            val themesArr = a.optJSONArray("themes")
            val genresArr = a.optJSONArray("genres")
            val pool = mutableListOf<String>()
            if (themesArr != null) for (i in 0 until themesArr.length()) pool.add(themesArr.optString(i))
            if (genresArr != null) for (i in 0 until genresArr.length()) pool.add(genresArr.optString(i))
            pool.any { item ->
                val n = normalizeText(item)
                tokens.any { tok -> n.contains(tok) }
            }
        }
        val pageSize = 60
        val start = (page - 1) * pageSize
        val pageItems = if (start >= matching.size) emptyList()
            else matching.drop(start).take(pageSize)
        // toItem retourne Movie ou TvShow ; tous deux implémentent Show.
        val shows = pageItems.mapNotNull { it.toItem() as? com.streamflixreborn.streamflix.models.Show }
        Log.d(TAG, "getGenre($id, page=$page) → ${shows.size} matches (total=${matching.size})")
        return Genre(id = id, name = id, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People =
        People(id = id, name = id)
}
