package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Vegeta VOD — films et séries FR des serveurs Xtream de Vegeta TV.
 *
 * 2026-09-05 (user, après un retour Telegram : « sur Vegeta TV le serveur 31
 * possèderait des films et des séries ») : sondage des 55 serveurs → 8 panels ont un
 * vrai catalogue FR (jusqu'à 25 000 films / 7 000 séries, catégories « MY TF1 »,
 * « Télé-réalité », Netflix…). Deux usages :
 *   1. Dossier « Vegeta VOD » dans Autres Replays (Films / Séries, par catégorie).
 *   2. SECOURS des replays TF1+/M6+ : quand un épisode existe chez Vegeta, on ajoute
 *      un serveur « Vegeta (secours) » derrière TF1+/M6+ (cf. [secours]).
 *
 * ⚠ POURQUOI UN INDEX PUBLIÉ ET PAS L'API DEPUIS LE TÉLÉPHONE : depuis un réseau
 * français, `player_api.php` / `get.php` de ces panels sont COUPÉS (connexion fermée
 * sans réponse) alors que les flux `/movie/…` et `/series/…` passent. Les proxies CORS
 * de VegetaTvProvider répondent 522 pour ces hôtes. Le runner nx-data, lui, y accède :
 * `scripts/vegetatv/refresh_vegeta_vod.py` construit tout, l'app ne lit que du JSON.
 *
 * Fichiers (nx-data) :
 *   data/vegetatv/vegeta-vod-fr.json   index (serveurs + films + séries), ~quelques Mo
 *   data/vegetatv/vod-ep/<00..3f>.json épisodes, 64 shards, clé "<serveur>:<series_id>"
 * Lecture : URL Xtream directe `<base>/movie/<user>/<pass>/<stream_id>.<ext>` et
 *   `<base>/series/<user>/<pass>/<episode_id>.<ext>` (vérifié : 206 + redirection CDN).
 */
object VegetaVod {

    private const val TAG = "VegetaVod"
    private const val RAW = "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data/vegetatv"
    private const val INDEX_URL = "$RAW/vegeta-vod-fr.json"
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private const val NB_SHARDS = 64
    const val PREFIX_FILM = "livehub::vegetavod::film::"
    const val PREFIX_SERIE = "livehub::vegetavod::serie::"
    const val PREFIX_SAISON = "livehub::vegetavod::saison::"
    const val PREFIX_SRC = "livehub::vegetavod::src::"
    const val PREFIX_DOSSIER = "livehub::folder::vegetavod_"
    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    data class Serveur(val pos: Int, val base: String, val user: String, val pass: String)
    data class Film(
        val id: String, val titre: String, val annee: Int, val tmdb: Int, val img: String?,
        val cat: String, val langue: String,
        /** (serveur, stream_id, extension) */
        val sources: List<Triple<Int, Int, String>>,
    )
    data class Serie(
        val id: String, val titre: String, val annee: Int, val tmdb: Int, val img: String?,
        val cat: String, val cle: String,
        /** (serveur, series_id) */
        val sources: List<Pair<Int, Int>>,
    )
    data class Episode(val num: Int, val id: Int, val ext: String, val titre: String)
    class Index(
        val serveurs: Map<Int, Serveur>,
        val films: List<Film>,
        val series: List<Serie>,
    ) {
        val filmsParId: Map<String, Film> = films.associateBy { it.id }
        val seriesParId: Map<String, Serie> = series.associateBy { it.id }
    }

    @Volatile private var cache: Index? = null
    @Volatile private var cacheTs = 0L
    private val verrou = Mutex()

    /** Index déjà en mémoire (le dialogue est synchrone). */
    fun indexSiCharge(): Index? = cache

    /** Télécharge l'index (ou rend le cache s'il est frais). Un échec ne vide jamais un cache garni. */
    suspend fun index(forcer: Boolean = false): Index? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache?.let { if (!forcer && now - cacheTs < TTL_MS) return@withContext it }
        verrou.withLock {
            cache?.let { if (!forcer && System.currentTimeMillis() - cacheTs < TTL_MS) return@withLock it }
            // ⚠ Index de ~10 Mo (3 Mo gzip) : lecture EN FLUX (JsonReader), jamais via un
            //   JSONObject géant — sur une box 1 Go, la chaîne + l'arbre org.json = OOM.
            val idx = runCatching { telechargerIndex("$INDEX_URL?h=${System.currentTimeMillis() / 3_600_000L}") }
                .getOrElse { Log.w(TAG, "index illisible : ${it.message}"); null }
                ?: return@withLock cache
            if (idx.films.isEmpty() && idx.series.isEmpty() && cache != null) return@withLock cache
            cache = idx
            cacheTs = System.currentTimeMillis()
            Log.i(TAG, "index chargé : ${idx.films.size} films, ${idx.series.size} séries, ${idx.serveurs.size} serveurs")
            idx
        }
    }

    private fun telecharger(url: String): String? = try {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .build()
        NetworkClient.default.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "http ${r.code} sur $url"); null }
            else r.body?.string()
        }
    } catch (e: Exception) {
        Log.w(TAG, "téléchargement KO ($url) : ${e.message}"); null
    }

    /** L'index compacte les affiches TMDB en « /abc.jpg » ; on reconstruit l'URL. */
    private fun affiche(brut: String): String? {
        if (brut.isBlank()) return null
        return if (brut.startsWith("/")) "https://image.tmdb.org/t/p/w342$brut" else brut
    }

    private fun telechargerIndex(url: String): Index? {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .build()
        return NetworkClient.default.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "http ${r.code} sur $url"); return@use null }
            val corps = r.body ?: return@use null
            android.util.JsonReader(java.io.BufferedReader(corps.charStream(), 64 * 1024)).use { analyser(it) }
        }
    }

    /** Lecture en flux de l'index (cf. format dans refresh_vegeta_vod.py). */
    private fun analyser(jr: android.util.JsonReader): Index {
        val serveurs = HashMap<Int, Serveur>()
        val films = ArrayList<Film>(50_000)
        val series = ArrayList<Serie>(15_000)
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "servers" -> {
                    jr.beginObject()
                    while (jr.hasNext()) {
                        val pos = jr.nextName().toIntOrNull()
                        var b = ""; var u = ""; var p = ""
                        jr.beginObject()
                        while (jr.hasNext()) {
                            when (jr.nextName()) {
                                "b" -> b = jr.nextString(); "u" -> u = jr.nextString(); "p" -> p = jr.nextString()
                                else -> jr.skipValue()
                            }
                        }
                        jr.endObject()
                        if (pos != null) serveurs[pos] = Serveur(pos, b, u, p)
                    }
                    jr.endObject()
                }
                "films" -> {
                    jr.beginArray()
                    var i = 0
                    while (jr.hasNext()) {
                        var id = ""; var t = ""; var y = 0; var tmdb = 0; var img = ""; var c = ""; var l = ""
                        val src = ArrayList<Triple<Int, Int, String>>(3)
                        jr.beginObject()
                        while (jr.hasNext()) {
                            when (jr.nextName()) {
                                "id" -> id = jr.nextString(); "t" -> t = jr.nextString()
                                "y" -> y = jr.nextInt(); "tmdb" -> tmdb = jr.nextInt()
                                "img" -> img = jr.nextString(); "c" -> c = jr.nextString(); "l" -> l = jr.nextString()
                                "s" -> {
                                    jr.beginArray()
                                    while (jr.hasNext()) {
                                        jr.beginArray()
                                        val pos = jr.nextInt(); val sid = jr.nextInt()
                                        val ext = if (jr.hasNext()) jr.nextString() else "mp4"
                                        while (jr.hasNext()) jr.skipValue()
                                        jr.endArray()
                                        src += Triple(pos, sid, ext)
                                    }
                                    jr.endArray()
                                }
                                else -> jr.skipValue()
                            }
                        }
                        jr.endObject()
                        if (src.isNotEmpty()) films += Film(
                            id = id.ifBlank { "f$i" }, titre = t, annee = y, tmdb = tmdb, img = affiche(img),
                            cat = c.ifBlank { "Films" }, langue = l, sources = src,
                        )
                        i++
                    }
                    jr.endArray()
                }
                "series" -> {
                    jr.beginArray()
                    var i = 0
                    while (jr.hasNext()) {
                        var id = ""; var t = ""; var y = 0; var tmdb = 0; var img = ""; var c = ""; var k = ""
                        val src = ArrayList<Pair<Int, Int>>(2)
                        jr.beginObject()
                        while (jr.hasNext()) {
                            when (jr.nextName()) {
                                "id" -> id = jr.nextString(); "t" -> t = jr.nextString()
                                "y" -> y = jr.nextInt(); "tmdb" -> tmdb = jr.nextInt()
                                "img" -> img = jr.nextString(); "c" -> c = jr.nextString(); "k" -> k = jr.nextString()
                                "s" -> {
                                    jr.beginArray()
                                    while (jr.hasNext()) {
                                        jr.beginArray()
                                        val pos = jr.nextInt(); val sid = jr.nextInt()
                                        while (jr.hasNext()) jr.skipValue()
                                        jr.endArray()
                                        src += pos to sid
                                    }
                                    jr.endArray()
                                }
                                else -> jr.skipValue()
                            }
                        }
                        jr.endObject()
                        if (src.isNotEmpty()) series += Serie(
                            id = id.ifBlank { "s$i" }, titre = t, annee = y, tmdb = tmdb, img = affiche(img),
                            cat = c.ifBlank { "Séries" }, cle = k.ifBlank { normaliser(t) }, sources = src,
                        )
                        i++
                    }
                    jr.endArray()
                }
                else -> jr.skipValue()
            }
        }
        jr.endObject()
        return Index(serveurs, films, series)
    }

    private val prechauffageEnCours = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Charge l'index en fond (au premier affichage d'Autres Replays). */
    fun prechauffer() {
        if (cache != null && System.currentTimeMillis() - cacheTs < TTL_MS) return
        if (!prechauffageEnCours.compareAndSet(false, true)) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { index() } catch (e: Exception) { Log.w(TAG, "préchauffage KO : ${e.message}") }
            finally { prechauffageEnCours.set(false) }
        }
    }

    // ─────────────────────────────────────────────────────────── navigation
    private val ORDRE_FILMS = listOf("Nouveautés", "Films 2025", "Films 2024", "Films 2023", "Films 4K",
        "Netflix", "Disney+ / Marvel", "Prime Video", "Apple TV+", "Canal+ / MyTF1",
        "Action & Aventure", "Comédie", "Drame & Romance", "Thriller & Horreur",
        "Science-fiction & Fantastique", "Animation & Jeunesse", "Documentaires",
        "Spectacles & Concerts", "Noël", "Classiques", "Monde", "Films")
    private val ORDRE_SERIES = listOf("MyTF1", "Télé-réalité", "Nouveautés", "Séries 2025", "Séries 2024",
        "Netflix", "Disney+ / Marvel", "Prime Video", "Apple TV+", "Canal+ / HBO",
        "Action & Thriller", "Comédie", "Drame & Romance", "Animation & Jeunesse", "Anime & Manga",
        "Documentaires", "Monde", "Classiques", "VOSTFR", "Séries")

    private fun rang(cat: String, ordre: List<String>): Int =
        ordre.indexOf(cat).let { if (it < 0) ordre.size else it }

    /** Catégories de films (libellé, nombre), dans l'ordre d'affichage. */
    fun categoriesFilms(): List<Pair<String, Int>> {
        val idx = cache ?: return emptyList()
        return idx.films.groupingBy { it.cat }.eachCount().entries
            .sortedWith(compareBy({ rang(it.key, ORDRE_FILMS) }, { it.key }))
            .map { it.key to it.value }
    }

    fun categoriesSeries(): List<Pair<String, Int>> {
        val idx = cache ?: return emptyList()
        return idx.series.groupingBy { it.cat }.eachCount().entries
            .sortedWith(compareBy({ rang(it.key, ORDRE_SERIES) }, { it.key }))
            .map { it.key to it.value }
    }

    fun filmsDe(cat: String): List<Film> =
        (cache?.films ?: emptyList()).filter { it.cat == cat }
            .sortedWith(compareByDescending<Film> { it.annee }.thenBy { it.titre.lowercase() })

    fun seriesDe(cat: String): List<Serie> =
        (cache?.series ?: emptyList()).filter { it.cat == cat }
            .sortedWith(compareByDescending<Serie> { it.annee }.thenBy { it.titre.lowercase() })

    fun filmDe(id: String): Film? = cache?.filmsParId?.get(id)
    fun serieDe(id: String): Serie? = cache?.seriesParId?.get(id)

    /** Tuile de dossier : rouvre le dialogue un cran plus bas (cf. LiveHubFolderDialog « vegetavod_ »). */
    fun tuileDossier(chemin: String, libelle: String, nb: Int): TvShow =
        TvShow(
            id = "$PREFIX_DOSSIER$chemin",
            title = if (nb >= 0) "📁 $libelle ($nb)" else "📁 $libelle",
        ).apply { providerName = "TV Hub" }

    fun tuileFilm(f: Film): TvShow =
        TvShow(
            id = "$PREFIX_FILM${f.id}",
            title = titreFilm(f),
        ).copy(poster = f.img, banner = f.img).apply { providerName = "TV Hub" }

    fun tuileSerie(s: Serie): TvShow =
        TvShow(
            id = "$PREFIX_SERIE${s.id}",
            title = if (s.annee > 0) "${s.titre} (${s.annee})" else s.titre,
        ).copy(poster = s.img, banner = s.img).apply { providerName = "TV Hub" }

    fun titreFilm(f: Film): String {
        val sb = StringBuilder(f.titre)
        if (f.annee > 0) sb.append(" (").append(f.annee).append(')')
        if (f.langue == "VOSTFR") sb.append(" · VOSTFR")
        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────── épisodes
    private val shards = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, JSONObject>>()

    private fun numeroShard(cle: String): Int {
        val md = java.security.MessageDigest.getInstance("MD5").digest(cle.toByteArray(Charsets.UTF_8))
        return (md[0].toInt() and 0xff) % NB_SHARDS
    }

    /** Épisodes d'une série chez un serveur : saison → liste, depuis le shard publié. */
    suspend fun episodes(pos: Int, seriesId: Int): Map<Int, List<Episode>>? = withContext(Dispatchers.IO) {
        val cle = "$pos:$seriesId"
        val n = numeroShard(cle)
        val now = System.currentTimeMillis()
        var shard = shards[n]?.takeIf { now - it.first < TTL_MS }?.second
        if (shard == null) {
            val corps = telecharger("$RAW/vod-ep/${"%02x".format(n)}.json?h=${now / 3_600_000L}")
                ?: return@withContext null
            shard = runCatching { JSONObject(corps) }.getOrNull() ?: return@withContext null
            shards[n] = now to shard
        }
        val o = shard.optJSONObject(cle) ?: return@withContext null
        val out = java.util.TreeMap<Int, List<Episode>>()
        for (k in o.keys()) {
            val saison = k.toIntOrNull() ?: continue
            val arr = o.optJSONArray(k) ?: continue
            val liste = ArrayList<Episode>(arr.length())
            for (i in 0 until arr.length()) {
                val t = arr.optJSONArray(i) ?: continue
                liste += Episode(t.optInt(0), t.optInt(1), t.optString(2, "mp4"), t.optString(3))
            }
            if (liste.isNotEmpty()) out[saison] = liste
        }
        out
    }

    // ─────────────────────────────────────────────────────────── lecture
    fun urlFilm(pos: Int, streamId: Int, ext: String): String? {
        val s = cache?.serveurs?.get(pos) ?: return null
        return "${s.base}/movie/${s.user}/${s.pass}/$streamId.$ext"
    }

    fun urlEpisode(pos: Int, episodeId: Int, ext: String): String? {
        val s = cache?.serveurs?.get(pos) ?: return null
        return "${s.base}/series/${s.user}/${s.pass}/$episodeId.$ext"
    }

    /** Serveur = "livehub::vegetavod::src::<pos>::<movie|series>::<id>::<ext>" ; src = URL directe. */
    private fun serveur(pos: Int, genre: String, id: Int, ext: String, nom: String): Video.Server? {
        val url = if (genre == "movie") urlFilm(pos, id, ext) else urlEpisode(pos, id, ext)
        return url?.let {
            Video.Server(id = "$PREFIX_SRC$pos::$genre::$id::$ext", name = nom, src = it)
        }
    }

    fun serveursFilm(f: Film): List<Video.Server> =
        f.sources.mapNotNull { (pos, id, ext) -> serveur(pos, "movie", id, ext, "Vegeta · serveur $pos") }

    fun serveurEpisode(pos: Int, ep: Episode, nom: String = "Vegeta · serveur $pos"): Video.Server? =
        serveur(pos, "series", ep.id, ep.ext, nom)

    fun mimeDe(ext: String): String = when (ext.lowercase()) {
        "mkv", "webm" -> androidx.media3.common.MimeTypes.VIDEO_MATROSKA
        "m3u8" -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
        "ts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        else -> androidx.media3.common.MimeTypes.VIDEO_MP4
    }

    /** Lecture d'un serveur émis ci-dessus : URL directe, UA de navigateur mobile. */
    fun video(server: Video.Server): Video {
        val ext = server.id.substringAfterLast("::")
        return Video(
            source = server.src,
            type = mimeDe(ext),
            headers = mapOf("User-Agent" to UA),
        )
    }

    // ─────────────────────────────────────────────────────────── secours replay
    fun normaliser(s: String): String {
        val d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase()
        return d.replace(Regex("[^a-z0-9]"), "")
    }

    /** Séries dont la clé correspond au titre demandé (exact, puis préfixe ≥ 6 caractères). */
    fun seriesPour(titre: String): List<Serie> {
        val idx = cache ?: return emptyList()
        val k = normaliser(titre)
        if (k.length < 3) return emptyList()
        val exacts = idx.series.filter { it.cle == k }
        if (exacts.isNotEmpty()) return exacts
        return idx.series.filter { s ->
            s.cle.length >= 6 && k.length >= 6 &&
                (k.startsWith(s.cle) || s.cle.startsWith(k))
        }.sortedByDescending { minOf(it.cle.length, k.length) }.take(4)
    }

    /**
     * 2026-09-06 (user : « sur Movix, Les Anges de la téléréalité, les serveurs Vegeta ne
     * s'affichent pas sur les saisons ») — SOURCE DE BACKUP GÉNÉRALE (BackupRegistry, emit
     * « Vegeta VOD ») : pour n'importe quel provider, on propose le film / l'épisode s'il existe
     * chez Vegeta. Rattachement : identifiant TMDB d'abord (les panels le fournissent), sinon
     * titre normalisé exact parmi les titres connus (+ année ±1 pour un film), sinon préfixe
     * de titre ≥ 6 caractères pour une série (« Les Anges » ⊂ « Les Anges de la téléréalité »).
     */
    suspend fun serveursPour(
        tmdbId: String?,
        titresConnus: Collection<String>,
        annee: Int?,
        estUnFilm: Boolean,
        saison: Int = 0,
        episode: Int = 0,
    ): List<Video.Server> {
        val idx = try { index() } catch (e: Exception) { null } ?: return emptyList()
        val id = tmdbId?.trim()?.toIntOrNull() ?: 0
        val cles = titresConnus.map { normaliser(it) }.filter { it.length >= 3 }.toSet()
        if (estUnFilm) {
            var films = if (id > 0) idx.films.filter { it.tmdb == id } else emptyList()
            if (films.isEmpty() && cles.isNotEmpty()) {
                films = idx.films.filter { f ->
                    normaliser(f.titre) in cles &&
                        (annee == null || annee <= 0 || f.annee <= 0 || kotlin.math.abs(f.annee - annee) <= 1)
                }
            }
            val out = films.take(2).flatMap { serveursFilm(it) }
            if (out.isNotEmpty()) Log.i(TAG, "backup film « ${titresConnus.firstOrNull()} » (tmdb $tmdbId) : ${out.size} serveur(s)")
            return out
        }
        if (saison <= 0 || episode <= 0) return emptyList()
        var series = if (id > 0) idx.series.filter { it.tmdb == id } else emptyList()
        if (series.isEmpty() && cles.isNotEmpty()) series = idx.series.filter { it.cle in cles }
        if (series.isEmpty()) {
            series = cles.filter { it.length >= 6 }
                .flatMap { k -> idx.series.filter { s -> s.cle.length >= 6 && (k.startsWith(s.cle) || s.cle.startsWith(k)) } }
                .distinctBy { it.id }
                .sortedByDescending { it.cle.length }
                .take(3)
        }
        val out = ArrayList<Video.Server>()
        for (s in series) {
            for ((pos, sid) in s.sources) {
                if (out.size >= 4) break
                val eps = try { episodes(pos, sid) } catch (e: Exception) { null } ?: continue
                val ep = eps[saison]?.firstOrNull { it.num == episode } ?: continue
                serveurEpisode(pos, ep)?.let { out += it }
            }
        }
        if (out.isNotEmpty()) Log.i(TAG, "backup série « ${titresConnus.firstOrNull()} » S${saison}E$episode : ${out.size} serveur(s)")
        return out
    }

    /**
     * SECOURS d'un épisode replay (TF1+/M6+) : mêmes titre, saison, épisode chez Vegeta.
     * Rend jusqu'à 3 serveurs, vide si rien (l'appelant garde alors ses serveurs officiels).
     */
    suspend fun secours(titre: String, saison: Int, episode: Int): List<Video.Server> {
        if (saison <= 0 || episode <= 0) return emptyList()
        val idx = try { index() } catch (e: Exception) { null } ?: return emptyList()
        if (idx.series.isEmpty()) return emptyList()
        val candidats = seriesPour(titre)
        if (candidats.isEmpty()) return emptyList()
        val out = ArrayList<Video.Server>()
        for (s in candidats) {
            for ((pos, sid) in s.sources) {
                if (out.size >= 3) return out
                val eps = try { episodes(pos, sid) } catch (e: Exception) { null } ?: continue
                val ep = eps[saison]?.firstOrNull { it.num == episode } ?: continue
                serveurEpisode(pos, ep, "Vegeta (secours) · $pos")?.let { out += it }
            }
        }
        if (out.isNotEmpty()) Log.i(TAG, "secours « $titre » S${saison}E$episode : ${out.size} serveur(s)")
        return out
    }
}
