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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
object FranimeProvider : Provider, ProgressiveServersProvider {

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
    // 2026-08-02 (user : « pourquoi il manque VIDMOLY alors qu'il devrait y être »,
    //   Hajime no Ippo E3) : le TTL de 24 h figeait la liste des LECTEURS.
    //   Vérifié en direct sur l'API : elle déclare bien
    //     vo → [filemoon, sibnet, sendvid, vidmoly]
    //     vf → [sendvid, filemoon, sibnet, vidmoly]
    //   alors que l'app n'affichait que Sibnet + SendVid : notre copie disque datait
    //   d'avant l'ajout de Filemoon et Vidmoly. Le code de lecture, lui, est correct
    //   (il ne filtre que « TELECHARGEMENT »).
    //   TTL MAINTENU À 24 h — le passage à 6 h a été testé puis ANNULÉ le 2026-08-02 :
    //   recharger ce catalogue coûte très cher sur Chromecast. Mesuré juste après la purge :
    //     GC freed 43MB … total 4.117s   /   GC freed 19MB … total 2.225s
    //     HANGDUMP → FranimeProvider.fetchText (thread bloqué sur le parse)
    //   Pendant ce temps FRAnime dépasse son délai et ne rend AUCUN serveur : réduire le TTL
    //   multipliait donc ces gels sans rien gagner. La bonne réponse à un catalogue périmé
    //   est de purger le fichier ponctuellement (`files/franime_catalogue.json`), pas de le
    //   retélécharger en boucle.
    private val CATALOGUE_TTL_MS = 24L * 60L * 60L * 1000L

    /**
     * 2026-10-03 — MÉMOIRE (mesuré sur l'Oppo avec l'outil MemDiag) : le catalogue complet était
     *   gardé en `List<JSONObject>` avec TOUTES les saisons, tous les épisodes et tous les lecteurs
     *   des 2 370 animes → ~103 Mo de mémoire Java retenus à vie, +127 Mo de pic à CHAQUE recherche
     *   globale (la loupe interroge FRAnime). Sur une box au tas Java de 128-192 Mo = plantage.
     *   Désormais :
     *   - le JSON est téléchargé DIRECTEMENT dans le fichier cache (plus de String de plusieurs Mo) ;
     *   - il est lu EN FLUX (JsonReader) en une fiche compacte par anime, SANS les saisons ;
     *   - les saisons/épisodes/lecteurs d'UN anime sont relus à la demande dans le fichier
     *     (position mémorisée), avec un petit cache des 6 derniers animes ouverts.
     *   Comportement inchangé : mêmes listes, mêmes fiches, mêmes serveurs.
     */
    private class Anime(
        // 2026-05-16 v8 : Long (certains ids dépassent Int.MAX_VALUE → anime_id négatif → 404).
        val id: Long,
        /** Position de l'anime dans le tableau JSON du fichier cache (relecture à la demande). */
        val pos: Int,
        val titre: String,
        /** Tous les titres connus (titre, titre original, variantes), en minuscules. */
        val titresRecherche: String,
        val affiche: String?,
        val afficheSmall: String?,
        val banner: String?,
        val annee: String?,
        val description: String?,
        val film: Boolean,
        val status: String,
        val note: Double,
        val updated: String,
        val themes: List<String>,
        val genres: List<String>,
    )

    @Volatile private var catalogue: List<Anime>? = null
    @Volatile private var catalogueIndex: Map<Long, Anime>? = null  // id → anime
    // 2026-05-18 : mutex pour SÉRIALISER les calls à loadCatalogue (un seul parse à la fois).
    private val loadCatalogueMutex = kotlinx.coroutines.sync.Mutex()
    /** Fiches complètes (saisons comprises) des derniers animes ouverts. */
    private val detailCache = object : LinkedHashMap<Long, JSONObject>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, JSONObject>?): Boolean = size > 6
    }
    private val cacheFile: java.io.File
        get() = java.io.File(StreamFlixApp.instance.applicationContext.cacheDir, "franime_catalogue.json")

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

    private suspend fun loadCatalogue(): List<Anime> {
        // Fast-path : si déjà en mémoire, retourner immédiatement (no mutex).
        catalogue?.let { return it }
        // Sinon, serialize : un seul thread à la fois lit/parse le catalogue.
        return loadCatalogueMutex.withLock {
            catalogue?.let { return@withLock it }
            doLoadCatalogue()
        }
    }

    private fun publier(list: List<Anime>): List<Anime> {
        catalogue = list
        catalogueIndex = list.associateBy { it.id }
        return list
    }

    private suspend fun doLoadCatalogue(): List<Anime> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val file = cacheFile
        // 1) Cache disque encore frais.
        val cachedAt = prefs.getLong("catalogue_at", 0L)
        if (file.exists() && now - cachedAt < CATALOGUE_TTL_MS) {
            val parsed = lireCatalogue(file)
            if (!parsed.isNullOrEmpty()) {
                Log.d(TAG, "loadCatalogue from disk file (${parsed.size} animes, age=${(now - cachedAt) / 1000}s)")
                return@withContext publier(parsed)
            }
        }
        // 2) Réseau. Téléchargé dans un fichier temporaire, validé, PUIS substitué au cache :
        //   une réponse tronquée n'écrase jamais un bon catalogue.
        // 2026-05-22 : délai 20 s (une réponse OK fait ~4 s) pour échouer vite.
        Log.d(TAG, "loadCatalogue fetching from network")
        val tmp = java.io.File(file.parentFile, "franime_catalogue.tmp")
        if (telechargerCatalogue(tmp, timeoutMs = 20_000L)) {
            val parsed = lireCatalogue(tmp)
            if (!parsed.isNullOrEmpty()) {
                synchronized(detailCache) { detailCache.clear() }
                file.delete()
                if (tmp.renameTo(file)) {
                    prefs.edit().putLong("catalogue_at", now).apply()
                    Log.d(TAG, "loadCatalogue fetched + cached (${parsed.size} animes)")
                    return@withContext publier(parsed)
                }
            }
        }
        tmp.delete()
        // 3) 2026-05-22 : repli sur le cache disque MÊME PÉRIMÉ quand le réseau échoue.
        if (file.exists()) {
            val parsed = lireCatalogue(file)
            if (!parsed.isNullOrEmpty()) {
                Log.d(TAG, "loadCatalogue STALE fallback (${parsed.size} animes)")
                return@withContext publier(parsed)
            }
        }
        Log.w(TAG, "loadCatalogue : aucun catalogue disponible")
        emptyList()
    }

    /** Télécharge le catalogue directement dans [dest] (aucune String en mémoire). */
    private suspend fun telechargerCatalogue(dest: java.io.File, timeoutMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("${API_BASE}api/animes")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", baseUrl)
                    .header("Accept", "application/json,text/plain,*/*")
                    .build()
                withTimeoutOrNull(timeoutMs) {
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use false
                        dest.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
                        dest.length() > 100
                    }
                } ?: false
            } catch (e: Exception) {
                Log.w(TAG, "telechargerCatalogue failed: ${e.message}")
                false
            }
        }

    private fun lecteurJson(file: java.io.File) = android.util.JsonReader(
        java.io.InputStreamReader(java.io.BufferedInputStream(file.inputStream(), 64 * 1024), Charsets.UTF_8)
    )

    /** Lecture EN FLUX du catalogue : une fiche compacte par anime, saisons ignorées. */
    private fun lireCatalogue(file: java.io.File): List<Anime>? = try {
        lecteurJson(file).use { r ->
            val out = ArrayList<Anime>(2600)
            val pool = HashMap<String, String>()   // thèmes/genres partagés entre animes
            r.beginArray()
            var pos = 0
            while (r.hasNext()) {
                lireAnime(r, pos, pool)?.let { out.add(it) }
                pos++
            }
            r.endArray()
            out
        }
    } catch (e: OutOfMemoryError) {
        Log.e(TAG, "lireCatalogue OOM")
        System.gc()
        null
    } catch (e: Exception) {
        Log.w(TAG, "lireCatalogue failed: ${e.message}")
        null
    }

    private fun lireTexte(r: android.util.JsonReader): String = when (r.peek()) {
        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> r.nextString()
        android.util.JsonToken.BOOLEAN -> r.nextBoolean().toString()
        android.util.JsonToken.NULL -> { r.nextNull(); "" }
        else -> { r.skipValue(); "" }
    }

    private fun lireListe(r: android.util.JsonReader, pool: HashMap<String, String>): List<String> {
        if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) { r.skipValue(); return emptyList() }
        val l = ArrayList<String>(4)
        r.beginArray()
        while (r.hasNext()) {
            val t = lireTexte(r)
            if (t.isNotBlank()) l.add(pool.getOrPut(t) { t })
        }
        r.endArray()
        return l
    }

    private fun lireAnime(r: android.util.JsonReader, pos: Int, pool: HashMap<String, String>): Anime? {
        if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
        var id = 0L
        var title = ""; var titleO = ""; var affiche = ""; var afficheSmall = ""; var banner = ""
        var startDate = ""; var description = ""; var format = ""; var status = ""; var updated = ""
        var note = 0.0
        var titles: Map<String, String> = emptyMap()
        var themes: List<String> = emptyList()
        var genres: List<String> = emptyList()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = lireTexte(r).let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() ?: 0L }
                "title" -> title = lireTexte(r)
                "titleO" -> titleO = lireTexte(r)
                "titles" -> titles = if (r.peek() == android.util.JsonToken.BEGIN_OBJECT) {
                    val m = HashMap<String, String>()
                    r.beginObject()
                    while (r.hasNext()) { val k = r.nextName(); m[k] = lireTexte(r) }
                    r.endObject()
                    m
                } else { r.skipValue(); emptyMap() }
                "affiche" -> affiche = lireTexte(r)
                "affiche_small" -> afficheSmall = lireTexte(r)
                "banner" -> banner = lireTexte(r)
                "startDate" -> startDate = lireTexte(r)
                "description" -> description = lireTexte(r)
                "format" -> format = lireTexte(r)
                "status" -> status = lireTexte(r).let { s -> pool.getOrPut(s) { s } }
                "note" -> note = lireTexte(r).toDoubleOrNull() ?: 0.0
                "updatedDate" -> updated = lireTexte(r)
                "themes" -> themes = lireListe(r, pool)
                "genres" -> genres = lireListe(r, pool)
                else -> r.skipValue()   // saisons, épisodes, lecteurs… relus à la demande
            }
        }
        r.endObject()
        val titre = titleO.ifBlank {
            title.ifBlank {
                titles["en_jp"].orEmpty().ifBlank { titles["en_us"].orEmpty().ifBlank { titles["ja_jp"].orEmpty() } }
            }
        }.ifBlank { "Anime #$id" }
        val f = format.lowercase().trim()
        return Anime(
            id = id,
            pos = pos,
            titre = titre,
            titresRecherche = (listOf(titre, titleO) + titles.values).joinToString("\n").lowercase(),
            affiche = affiche.takeIf { it.startsWith("http") },
            afficheSmall = afficheSmall.takeIf { it.startsWith("http") },
            banner = banner.takeIf { it.startsWith("http") },
            annee = startDate.takeIf { it.isNotBlank() }?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1) },
            description = description.takeIf { it.isNotBlank() },
            film = f == "film" || f == "movie",
            status = status,
            note = note,
            updated = updated,
            themes = themes,
            genres = genres,
        )
    }

    /** Relit la fiche COMPLÈTE (saisons comprises) de l'anime en position [pos] du fichier. */
    private fun lireDetail(file: java.io.File, pos: Int, id: Long): JSONObject? = try {
        if (!file.exists()) null else lecteurJson(file).use { r ->
            r.beginArray()
            var i = 0
            while (i < pos && r.hasNext()) { r.skipValue(); i++ }
            if (!r.hasNext()) null
            else (lireValeur(r) as? JSONObject)?.takeIf { it.optLong("id") == id }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "lireDetail($id) failed: ${e.message}")
        null
    }

    private fun lireValeur(r: android.util.JsonReader): Any = when (r.peek()) {
        android.util.JsonToken.BEGIN_OBJECT -> {
            val o = JSONObject()
            r.beginObject()
            while (r.hasNext()) { val k = r.nextName(); o.put(k, lireValeur(r)) }
            r.endObject()
            o
        }
        android.util.JsonToken.BEGIN_ARRAY -> {
            val a = JSONArray()
            r.beginArray()
            while (r.hasNext()) a.put(lireValeur(r))
            r.endArray()
            a
        }
        android.util.JsonToken.STRING -> r.nextString()
        android.util.JsonToken.NUMBER -> r.nextString().let { s -> s.toLongOrNull() ?: s.toDoubleOrNull()?.takeIf { !it.isNaN() } ?: s }
        android.util.JsonToken.BOOLEAN -> r.nextBoolean()
        android.util.JsonToken.NULL -> { r.nextNull(); JSONObject.NULL }
        else -> { r.skipValue(); JSONObject.NULL }
    }

    // ── Helpers conversion JSONObject → models ──────────────────────────
    private fun JSONObject.bestTitle(): String =
        optString("titleO").ifBlank {
            optString("title").ifBlank {
                optJSONObject("titles")?.let { it.optString("en_jp").ifBlank { it.optString("en_us").ifBlank { it.optString("ja_jp") } } } ?: ""
            }
        }.ifBlank { "Anime #${optLong("id")}" }

    /** Version HD pour la fiche détail uniquement (= image grande, mieux rendue). */
    private fun JSONObject.bestPosterFull(): String? {
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

    /** 2026-06-23 (user "FRAnime, jaquettes longues à charger") : la grille utilise
     *  `affiche_small` (~50 KB) au lieu de `affiche` (1-2 MB HD). La fiche détail garde la HD. */
    private fun Anime.poster(): String? = afficheSmall ?: affiche

    private fun Anime.toTvShow(): TvShow = TvShow(
        id = id.toString(),
        title = titre,
        poster = poster(),
        banner = banner ?: poster(),
        released = annee,
        overview = description,
    )

    /** 2026-05-17 (user "Les films sont considérés comme des séries") : format Film/movie
     *  = vrai long-métrage ; ONA/OAV/OVA/Special restent des séries. */
    private fun Anime.toMovie(): Movie = Movie(
        id = id.toString(),
        title = titre,
        poster = poster(),
        banner = banner ?: poster(),
        released = annee,
        overview = description,
    )

    /** Movie (film) ou TvShow (sinon) : l'AppAdapter pose le bon viewholder. */
    private fun Anime.toItem(): com.streamflixreborn.streamflix.adapters.AppAdapter.Item =
        if (film) toMovie() else toTvShow()

    // ── HOME ─────────────────────────────────────────────────────────────

    override suspend fun getHome(): List<Category> {
        // 2026-05-16 v10 : Bootstrap la session FRAnime au premier accès du
        // provider — ouvre franime.fr/ dans une WebView background UNE FOIS
        // pour faire jouer le "Chargement profil/préférences/animes" qui
        // établit la session Next.js. Toutes les extractions d'épisodes
        // suivantes réutiliseront cette même WebView.
        // 2026-10-03 : en mode léger, pas de WebView franime.fr en arrière-plan (~390 Mo de moteur
        //   web mesurés sur l'Oppo) — elle s'ouvrira au moment de lire un épisode (FranimeExtractor).
        if (!com.streamflixreborn.streamflix.utils.UserPreferences.modeLeger) {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try { com.streamflixreborn.streamflix.extractors.FranimeSession.bootstrap() } catch (_: Exception) {}
            }
        }
        val all = loadCatalogue()
        if (all.isEmpty()) return emptyList()

        val categories = mutableListOf<Category>()

        // Featured : 8 derniers mis à jour (mix films/séries)
        val recent = all.sortedByDescending { it.updated }.take(8)
            .map { it.toItem() }
        if (recent.isNotEmpty()) categories.add(Category(name = Category.FEATURED, list = recent))

        // Derniers ajouts (par updatedDate, mix films/séries) — sans les 8 du carousel
        val recentIds = recent.mapNotNull { when (it) { is TvShow -> it.id; is Movie -> it.id; else -> null } }.toSet()
        val latest = all.sortedByDescending { it.updated }.take(48)
            .map { it.toItem() }
            .filter { item -> val id = when (item) { is TvShow -> item.id; is Movie -> item.id; else -> "" }; id !in recentIds }
            .take(40)
        if (latest.isNotEmpty()) categories.add(Category(name = "Derniers ajouts", list = latest))

        // 2026-05-17 : section dédiée Films (long-métrages anime)
        val films = all.filter { it.film }
            .sortedByDescending { it.updated }
            .take(40)
            .map { it.toMovie() }
        if (films.isNotEmpty()) categories.add(Category(name = "Films", list = films))

        // Les plus aimés (par note décroissante, mix)
        val topRated = all.sortedByDescending { it.note }.take(40)
            .map { it.toItem() }
        if (topRated.isNotEmpty()) categories.add(Category(name = "Les plus aimés", list = topRated))

        // En cours (status = EN COURS) — séries uniquement (les films n'ont pas de statut "en cours")
        val ongoing = all.filter { !it.film && it.status.contains("EN COURS", true) }.take(40)
            .map { it.toTvShow() }
        if (ongoing.isNotEmpty()) categories.add(Category(name = "En cours de diffusion", list = ongoing))

        // Catégories par thème (top thèmes)
        val themeCount = mutableMapOf<String, Int>()
        all.forEach { a -> a.themes.forEach { t -> themeCount[t] = (themeCount[t] ?: 0) + 1 } }
        val topThemes = themeCount.entries.sortedByDescending { it.value }.take(5)
        for ((theme, _) in topThemes) {
            val items = all.filter { theme in it.themes }.take(30).map { it.toItem() }
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
        val filtered = all.filter { it.titresRecherche.contains(q) }
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
        val films = all.filter { it.film }
        if (films.isEmpty()) return emptyList()
        val pageSize = 30
        val start = (page - 1) * pageSize
        if (start >= films.size) return emptyList()
        val sorted = films.sortedBy { it.titre.lowercase() }
        return sorted.drop(start).take(pageSize).map { it.toMovie() }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val all = loadCatalogue()
        if (all.isEmpty()) return emptyList()
        // 2026-05-17 : exclure les films (mappés via getMovies).
        val series = all.filter { !it.film }
        if (series.isEmpty()) return emptyList()
        val pageSize = 30
        val start = (page - 1) * pageSize
        if (start >= series.size) return emptyList()
        // Tri alphabétique par title
        val sorted = series.sortedBy { it.titre.lowercase() }
        return sorted.drop(start).take(pageSize).map { it.toTvShow() }
    }

    // ── DETAIL ───────────────────────────────────────────────────────────

    /** Fiche COMPLÈTE (saisons, épisodes, lecteurs) relue à la demande dans le fichier cache. */
    private suspend fun getAnimeById(id: Long): JSONObject? {
        synchronized(detailCache) { detailCache[id] }?.let { return it }
        repeat(2) { essai ->
            loadCatalogue()
            val a = catalogueIndex?.get(id) ?: return null
            val obj = withContext(Dispatchers.IO) { lireDetail(cacheFile, a.pos, id) }
            if (obj != null) {
                synchronized(detailCache) { detailCache[id] = obj }
                return obj
            }
            if (essai == 0) {
                // Fichier remplacé ou supprimé entre-temps : on recharge le catalogue une fois.
                Log.w(TAG, "détail $id introuvable dans le fichier → rechargement du catalogue")
                catalogue = null
                catalogueIndex = null
            }
        }
        return null
    }

    override suspend fun getTvShow(id: String): TvShow {
        val animeId = id.toLongOrNull() ?: return fallback(id)
        val anime = getAnimeById(animeId) ?: return fallback(id)
        val saisons = anime.optJSONArray("saisons") ?: JSONArray()
        // Fiche détail = HD (affiche original ~1 MB), c'est OK car 1 seul fetch.
        val poster = anime.bestPosterFull()
        val title = anime.bestTitle()

        val seasons = (0 until saisons.length()).map { i ->
            val s = saisons.optJSONObject(i)
            val posNum = i + 1   // positional → pour l'ID (= index array dans getEpisodesBySeason/getServers)
            val label = s?.optString("title")?.ifBlank { "Saison $posNum" } ?: "Saison $posNum"
            // 2026-07-12 : numéroter par le LABEL, PAS par la position.
            //   Franime peut avoir des saisons intermédiaires (ex "Saison 2.5") qui décalent
            //   la numérotation positionnelle ("Saison 3" = position 4 au lieu de 3).
            //   L'ID garde la position (accès tableau), mais number = label pour le matching TMDB.
            val labelNum = Regex("""(?i)saison\s*(\d+)""").find(label)
                ?.groupValues?.get(1)?.toIntOrNull()
            Season(
                id = "$id/$posNum",
                number = labelNum ?: posNum,
                title = label,
                poster = poster,
            )
        }.ifEmpty {
            listOf(Season(id = "$id/1", number = 1, title = "Saison 1", poster = poster))
        }

        return TvShow(
            id = id,
            title = title,
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
        // Fiche détail = HD (affiche original ~1 MB), OK car 1 seul fetch.
        val poster = anime.bestPosterFull()
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

        // 2026-07-13 : le site Franime attend s=<label> (ex: s=3 pour "Saison 3"),
        //   PAS s=<position> (qui serait 4 si "Saison 2.5" décale la numérotation).
        //   sNum = positional (pour accéder au JSON array), sUrlParam = label (pour l'URL).
        val saisonTitle = saison.optString("title") ?: ""
        val sUrlParam = Regex("""(?i)saison\s*(\d+(?:\.\d+)?)""").find(saisonTitle)
            ?.groupValues?.get(1) ?: sNum.toString()

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
                val src = "${baseUrl}anime/$slug?s=$sUrlParam&ep=$epNum&lang=$lang&anime_id=$animeId&l=$i#lecteur=${hostName.lowercase()}"
                servers.add(Video.Server(id = "$id|$lang|$i", name = display, src = src))
            }
        }
        Log.d(TAG, "getServers($id) → ${servers.size} servers (slug=$slug)")
        return servers
    }

    override fun getServersProgressive(id: String, videoType: Video.Type): Flow<List<Video.Server>> = channelFlow {
        try {
            val servers = withContext(Dispatchers.IO) { getServers(id, videoType) }
            if (servers.isNotEmpty()) send(servers)
        } catch (e: Exception) { Log.w(TAG, "progressive native KO: ${e.message}") }
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
            (a.themes + a.genres).any { item ->
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
