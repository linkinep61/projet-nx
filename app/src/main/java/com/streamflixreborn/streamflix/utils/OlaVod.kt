package com.streamflixreborn.streamflix.utils

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 2026-09-26 (user : « si on peut choper du VOD avec OLA TV, on crée le même script que Vegeta,
 * on le range au même endroit que Vegeta et on s'en sert comme serveur supplémentaire » +
 * « deux dossiers supplémentaires dans Ciné Films, mais tu gardes le nom comme si c'était
 * Ciné Films ») — films FR des portails OLA TV (Stalker/MAG).
 *
 * Index : data/olatv/ola-vod-fr.json (scripts/olatv/refresh_ola_vod.py, onyxia-data).
 *   groupes : { "<g>": [ {b: base, m: mac} ] }   — portails servant le MÊME catalogue
 *   films   : [ {g, cmd, t, y, tmdb, img, c} ]   — cmd = commande Stalker du film
 *
 * Lecture : l'URL d'un film Stalker n'est PAS constructible à la main (jeton de session) :
 *   handshake (MAC) → get_profile → type=vod&action=create_link&cmd=<cmd> → /play/movie.php?…
 *   On essaie les portails du groupe l'un après l'autre. Jamais le nom « OLA » à l'écran :
 *   dossier « Plus de films » dans Ciné Films, serveurs « Cinélux · serveur N » (2026-09-26, user :
 *   « leur trouver un nom différent, car ils ne sont pas au même endroit, comme Cinélux »).
 */
object OlaVod {
    private const val TAG = "OlaVod"
    private const val INDEX_URL = "https://raw.githubusercontent.com/rikital/onyxia-data/main/data/olatv/ola-vod-fr.json"
    // Séries : fichier et script À PART (user : « indépendant, pour pas qu'il y ait de casse »).
    private const val SERIES_URL = "https://raw.githubusercontent.com/rikital/onyxia-data/main/data/olatv/ola-series-fr.json"
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    const val PREFIX_FILM = "livehub::olavod::film::"
    const val PREFIX_SRC = "livehub::olavod::src::"
    const val PREFIX_SERIE = "livehub::olavod::serie::"
    // 2026-09-26 : PAS de « livehub:: » pour un épisode — le lecteur classe tout id
    //   « livehub:: » comme une chaîne en direct (⏭ = zapping, pas d'épisode suivant).
    const val PREFIX_EP = "olavodep::"
    const val PREFIX_EP_ANCIEN = "livehub::olavod::ep::"
    private const val MAG_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    private const val UA_LECTURE = "Mozilla/5.0"

    data class Portail(val base: String, val mac: String)
    data class Film(
        val id: String, val titre: String, val annee: Int, val tmdb: Int, val img: String?,
        val cats: Set<String>,
        /** (groupe, commande Stalker) */
        val sources: List<Pair<Int, String>>,
    )
    data class Serie(
        val id: String, val titre: String, val annee: Int, val tmdb: Int, val img: String?,
        val cats: Set<String>,
        /** (groupe, id série Stalker « 34414:34414 ») */
        val sources: List<Pair<Int, String>>,
    )
    /** Saison lue sur le portail : commande Stalker de la saison + numéros d'épisodes. */
    data class Saison(val num: Int, val groupe: Int, val cmd: String, val episodes: List<Int>)
    class Index(
        val groupes: Map<Int, List<Portail>>, val films: List<Film>, val series: List<Serie> = emptyList(),
        /** Groupes de portails des SÉRIES (numérotation propre au fichier des séries). */
        val sgroupes: Map<Int, List<Portail>> = emptyMap(),
    ) {
        val seriesParId: Map<String, Serie> = series.associateBy { it.id }
        val seriesParTmdb: Map<Int, List<Serie>> = series.filter { it.tmdb > 0 }.groupBy { it.tmdb }
        val parId: Map<String, Film> = films.associateBy { it.id }
        val parTmdb: Map<Int, List<Film>> = films.filter { it.tmdb > 0 }.groupBy { it.tmdb }
    }

    @Volatile private var cache: Index? = null
    @Volatile private var cacheTs = 0L
    private val verrou = Mutex()
    private val prechauffage = AtomicBoolean(false)

    fun indexSiCharge(): Index? = cache

    suspend fun index(forcer: Boolean = false): Index? = withContext(Dispatchers.IO) {
        cache?.let { if (!forcer && System.currentTimeMillis() - cacheTs < TTL_MS) return@withContext it }
        verrou.withLock {
            cache?.let { if (!forcer && System.currentTimeMillis() - cacheTs < TTL_MS) return@withLock it }
            val h = System.currentTimeMillis() / 3_600_000L
            // Films et séries chargés séparément : l'un peut manquer sans bloquer l'autre.
            val f = runCatching { telecharger("$INDEX_URL?h=$h") }
                .getOrElse { Log.w(TAG, "index films illisible : ${it.message}"); null }
            val s = runCatching { telecharger("$SERIES_URL?h=$h") }
                .getOrElse { Log.w(TAG, "index séries illisible : ${it.message}"); null }
            if (f == null && s == null) return@withLock cache
            val idx = Index(
                groupes = f?.groupes ?: cache?.groupes.orEmpty(),
                films = f?.films ?: cache?.films.orEmpty(),
                series = s?.series ?: cache?.series.orEmpty(),
                sgroupes = s?.groupes ?: cache?.sgroupes.orEmpty(),
            )
            if (idx.films.isEmpty() && idx.series.isEmpty() && cache != null) return@withLock cache
            cache = idx
            cacheTs = System.currentTimeMillis()
            Log.i(TAG, "index chargé : ${idx.films.size} films, ${idx.series.size} séries")
            idx
        }
    }

    fun prechauffer() {
        if (cache != null && System.currentTimeMillis() - cacheTs < TTL_MS) return
        if (!prechauffage.compareAndSet(false, true)) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { index() } catch (e: Exception) { Log.w(TAG, "préchauffage KO : ${e.message}") }
            finally { prechauffage.set(false) }
        }
    }

    private fun telecharger(url: String): Index? {
        val req = okhttp3.Request.Builder().url(url)
            .header("Cache-Control", "no-cache").header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json").build()
        return NetworkClient.default.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "http ${r.code} sur $url"); return@use null }
            val corps = r.body ?: return@use null
            // Index de ~9 Mo : lecture EN FLUX (cf. VegetaVod), jamais un JSONObject géant.
            android.util.JsonReader(java.io.BufferedReader(corps.charStream(), 64 * 1024)).use { analyser(it) }
        }
    }

    private class Brut(val g: Int, val cmd: String, val t: String, val y: Int, val tmdb: Int, val img: String, val c: String)

    private fun analyser(jr: android.util.JsonReader): Index {
        val groupes = HashMap<Int, List<Portail>>()
        val bruts = ArrayList<Brut>(32_000)
        val sbruts = ArrayList<Brut>(8_000)
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "groupes" -> {
                    jr.beginObject()
                    while (jr.hasNext()) {
                        val g = jr.nextName().toIntOrNull() ?: -1
                        val liste = ArrayList<Portail>()
                        jr.beginArray()
                        while (jr.hasNext()) {
                            var b = ""; var m = ""
                            jr.beginObject()
                            while (jr.hasNext()) {
                                when (jr.nextName()) { "b" -> b = jr.nextString(); "m" -> m = jr.nextString(); else -> jr.skipValue() }
                            }
                            jr.endObject()
                            if (b.isNotBlank() && m.isNotBlank()) liste += Portail(b.trimEnd('/'), m)
                        }
                        jr.endArray()
                        if (g >= 0 && liste.isNotEmpty()) groupes[g] = liste
                    }
                    jr.endObject()
                }
                "films" -> {
                    jr.beginArray()
                    while (jr.hasNext()) {
                        var g = -1; var cmd = ""; var t = ""; var y = 0; var tmdb = 0; var img = ""; var c = ""
                        jr.beginObject()
                        while (jr.hasNext()) {
                            when (jr.nextName()) {
                                "g" -> g = jr.nextInt()
                                "cmd" -> cmd = jr.nextString()
                                "t" -> t = jr.nextString()
                                "y" -> y = runCatching { jr.nextInt() }.getOrDefault(0)
                                "tmdb" -> tmdb = runCatching { jr.nextInt() }.getOrDefault(0)
                                "img" -> img = jr.nextString()
                                "c" -> c = jr.nextString()
                                else -> jr.skipValue()
                            }
                        }
                        jr.endObject()
                        if (g >= 0 && cmd.isNotBlank() && t.isNotBlank()) bruts += Brut(g, cmd, t, y, tmdb, img, c)
                    }
                    jr.endArray()
                }
                "series" -> {
                    jr.beginArray()
                    while (jr.hasNext()) {
                        var g = -1; var sid = ""; var t = ""; var y = 0; var tmdb = 0; var img = ""; var c = ""
                        jr.beginObject()
                        while (jr.hasNext()) {
                            when (jr.nextName()) {
                                "g" -> g = jr.nextInt()
                                "sid" -> sid = jr.nextString()
                                "t" -> t = jr.nextString()
                                "y" -> y = runCatching { jr.nextInt() }.getOrDefault(0)
                                "tmdb" -> tmdb = runCatching { jr.nextInt() }.getOrDefault(0)
                                "img" -> img = jr.nextString()
                                "c" -> c = jr.nextString()
                                else -> jr.skipValue()
                            }
                        }
                        jr.endObject()
                        if (g >= 0 && sid.isNotBlank() && t.isNotBlank()) sbruts += Brut(g, sid, t, y, tmdb, img, c)
                    }
                    jr.endArray()
                }
                else -> jr.skipValue()
            }
        }
        jr.endObject()
        val sParCle = LinkedHashMap<String, MutableList<Brut>>()
        for (b in sbruts) {
            if (b.g !in groupes) continue
            val cle = if (b.tmdb > 0) "t${b.tmdb}" else "n${normaliser(b.t)}"
            sParCle.getOrPut(cle) { ArrayList(2) } += b
        }
        val series = sParCle.map { (cle, liste) ->
            val ref = liste.firstOrNull { it.img.isNotBlank() } ?: liste.first()
            Serie(
                id = cle, titre = ref.t, annee = liste.maxOf { it.y }, tmdb = ref.tmdb,
                img = ref.img.ifBlank { null },
                cats = liste.map { categorie(it.c) }.filter { it.isNotBlank() }.toSet(),
                sources = liste.map { it.g to it.cmd }.distinct(),
            )
        }
        // Un même film est servi par plusieurs catalogues : une seule tuile, plusieurs sources.
        val parCle = LinkedHashMap<String, MutableList<Brut>>()
        for (b in bruts) {
            if (b.g !in groupes) continue
            val cle = if (b.tmdb > 0) "t${b.tmdb}" else "n${normaliser(b.t)}_${b.y}"
            parCle.getOrPut(cle) { ArrayList(2) } += b
        }
        val films = parCle.map { (cle, liste) ->
            val ref = liste.firstOrNull { it.img.isNotBlank() } ?: liste.first()
            Film(
                id = cle, titre = ref.t, annee = liste.maxOf { it.y }, tmdb = ref.tmdb,
                img = ref.img.ifBlank { null },
                cats = liste.map { categorie(it.c) }.filter { it.isNotBlank() }.toSet(),
                sources = liste.map { it.g to it.cmd }.distinct(),
            )
        }
        return Index(groupes, films, series)
    }

    // ─────────────────────────────────────────────── catégories & navigation
    private val RE_DECOR = Regex("[^\\p{L}\\p{N}&+'’ /.-]")
    private val RE_PREFIXE_FR = Regex("^(?i)(\\s*(FR|VF|VOD|FRANCE|FRENCH)\\s*[-:|]?\\s*)+")

    /** « FR - ACTION » → « Action », « FR ⭐ VISION 2022 » → « Vision 2022 ». */
    fun categorie(brut: String): String {
        var s = RE_DECOR.replace(brut, " ").replace(Regex("\\s+"), " ").trim()
        s = RE_PREFIXE_FR.replace(s, "").trim(' ', '-', '|')
        if (s.isBlank()) return "Films"
        return if (s == s.uppercase()) s.lowercase().replaceFirstChar { it.titlecase() } else s
    }

    fun categories(): List<Pair<String, Int>> {
        val idx = cache ?: return emptyList()
        val n = HashMap<String, Int>()
        for (f in idx.films) for (c in f.cats) n[c] = (n[c] ?: 0) + 1
        return n.entries.filter { it.value >= 5 }.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun filmsDe(cat: String): List<Film> =
        (cache?.films ?: emptyList()).filter { cat in it.cats }
            .sortedWith(compareByDescending<Film> { it.annee }.thenBy { it.titre.lowercase() })

    fun filmDe(id: String): Film? = cache?.parId?.get(id)
    fun serieDe(id: String): Serie? = cache?.seriesParId?.get(id)

    fun categoriesSeries(): List<Pair<String, Int>> {
        val idx = cache ?: return emptyList()
        val n = HashMap<String, Int>()
        for (s in idx.series) for (c in s.cats) n[c] = (n[c] ?: 0) + 1
        return n.entries.filter { it.value >= 3 }.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun seriesDe(cat: String): List<Serie> =
        (cache?.series ?: emptyList()).filter { cat in it.cats }
            .sortedWith(compareByDescending<Serie> { it.annee }.thenBy { it.titre.lowercase() })

    fun tuileSerie(s: Serie): TvShow =
        TvShow(id = "$PREFIX_SERIE${s.id}", title = if (s.annee > 0) "${s.titre} (${s.annee})" else s.titre)
            .copy(poster = s.img, banner = s.img).apply { providerName = "TV Hub" }

    fun titreFilm(f: Film): String = if (f.annee > 0) "${f.titre} (${f.annee})" else f.titre

    fun tuileFilm(f: Film): TvShow =
        TvShow(id = "$PREFIX_FILM${f.id}", title = titreFilm(f))
            .copy(poster = f.img, banner = f.img).apply { providerName = "TV Hub" }

    fun normaliser(s: String): String {
        val d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase()
        return d.replace(Regex("[^a-z0-9]"), "")
    }

    // ─────────────────────────────────────────────── serveurs
    /** Serveur = PREFIX_SRC + "<groupe>::<cmd>" ; la vraie URL est demandée au portail à la lecture. */
    fun serveursFilm(f: Film, depart: Int = 1): List<Video.Server> =
        f.sources.take(8).mapIndexed { i, (g, cmd) ->
            Video.Server(id = "$PREFIX_SRC$g::$cmd", name = "Cinélux · serveur ${depart + i}", src = cmd)
        }

    /** Source de secours générale (BackupRegistry) : même film par TMDB, sinon titre + année. */
    suspend fun serveursPour(tmdbId: String?, titresConnus: Collection<String>, annee: Int?, depart: Int = 1): List<Video.Server> {
        val idx = try { index() } catch (e: Exception) { null } ?: return emptyList()
        val id = tmdbId?.trim()?.toIntOrNull() ?: 0
        var films = if (id > 0) idx.parTmdb[id].orEmpty() else emptyList()
        if (films.isEmpty()) {
            val cles = titresConnus.map { normaliser(it) }.filter { it.length >= 3 }.toSet()
            if (cles.isNotEmpty()) films = idx.films.filter { f ->
                normaliser(f.titre) in cles &&
                    (annee == null || annee <= 0 || f.annee <= 0 || kotlin.math.abs(f.annee - annee) <= 1)
            }.take(2)
        }
        val out = films.flatMap { it.sources }.distinct().take(8).mapIndexed { i, (g, cmd) ->
            Video.Server(id = "$PREFIX_SRC$g::$cmd", name = "Cinélux · serveur ${depart + i}", src = cmd)
        }
        if (out.isNotEmpty()) Log.i(TAG, "backup film « ${titresConnus.firstOrNull()} » (tmdb $tmdbId) : ${out.size} serveur(s)")
        return out
    }

    // ─────────────────────────────────────────────── lecture
    private fun extension(cmd: String): String = try {
        val js = JSONObject(String(Base64.decode(cmd, Base64.DEFAULT), Charsets.UTF_8))
        Regex("[a-z0-9]{2,4}").find(js.optString("target_container").lowercase())?.value ?: "mkv"
    } catch (_: Exception) { "mkv" }

    private fun client() = NetworkClient.default.newBuilder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .build()

    /** Session Stalker : (fonction d'appel js(qs) déjà authentifiée) ou null. */
    private fun session(p: Portail): ((String) -> Any?)? {
        val client = client()
        val portail = "${p.base}/portal.php"
        val cookie = "mac=${java.net.URLEncoder.encode(p.mac, "UTF-8")}; stb_lang=en; timezone=Europe%2FLondon"
        var jeton: String? = null
        fun js(qs: String): Any? {
            val rb = okhttp3.Request.Builder().url("$portail?$qs&JsHttpRequest=1-xml")
                .header("User-Agent", MAG_UA).header("Cookie", cookie)
            jeton?.let { rb.header("Authorization", "Bearer $it") }
            return client.newCall(rb.build()).execute().use { r ->
                runCatching { JSONObject(r.body?.string().orEmpty()).opt("js") }.getOrNull()
            }
        }
        jeton = (js("type=stb&action=handshake&token=") as? JSONObject)?.optString("token")?.takeIf { it.isNotBlank() }
            ?: return null
        js("type=stb&action=get_profile")
        return ::js
    }

    /** Saisons d'une série, demandées au portail (premier portail/groupe qui répond). */
    suspend fun saisons(serie: Serie): List<Saison> = withContext(Dispatchers.IO) {
        val idx = cache ?: index() ?: return@withContext emptyList()
        for ((g, sid) in serie.sources) {
            for (p in idx.sgroupes[g].orEmpty().shuffled().take(3)) {
                val js = runCatching { session(p) }.getOrNull() ?: continue
                val r = runCatching {
                    js("type=series&action=get_ordered_list&movie_id=${java.net.URLEncoder.encode(sid, "UTF-8")}&season_id=0&episode_id=0&p=1")
                }.getOrNull() as? JSONObject ?: continue
                val data = r.optJSONArray("data") ?: continue
                val out = ArrayList<Saison>()
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val cmd = o.optString("cmd").takeIf { it.isNotBlank() } ?: continue
                    val num = runCatching {
                        JSONObject(String(Base64.decode(cmd, Base64.DEFAULT), Charsets.UTF_8)).optInt("season_num", 0)
                    }.getOrDefault(0).takeIf { it > 0 }
                        ?: Regex("\\d+").find(o.optString("name"))?.value?.toIntOrNull() ?: (i + 1)
                    val eps = ArrayList<Int>()
                    o.optJSONArray("series")?.let { a -> for (k in 0 until a.length()) a.optInt(k).takeIf { it > 0 }?.let { eps += it } }
                    if (eps.isNotEmpty()) out += Saison(num, g, cmd, eps.sorted())
                }
                if (out.isNotEmpty()) return@withContext out.sortedBy { it.num }
            }
        }
        emptyList()
    }

    fun idEpisode(s: Saison, ep: Int): String = "$PREFIX_EP${s.groupe}::${s.cmd}::$ep"

    fun serveurEpisode(idEp: String, nom: String = "Cinélux · serveur 1"): Video.Server {
        val reste = idEp.removePrefix(PREFIX_EP).removePrefix(PREFIX_EP_ANCIEN)
        return Video.Server(id = "$PREFIX_SRC$reste", name = nom, src = reste.substringAfter("::"))
    }

    /** Secours pour un épisode d'un autre provider : même série (TMDB puis titre). */
    suspend fun serveursEpisodePour(tmdbId: String?, titresConnus: Collection<String>, saison: Int, episode: Int, depart: Int = 1): List<Video.Server> {
        if (saison <= 0 || episode <= 0) return emptyList()
        val idx = try { index() } catch (e: Exception) { null } ?: return emptyList()
        val id = tmdbId?.trim()?.toIntOrNull() ?: 0
        var series = if (id > 0) idx.seriesParTmdb[id].orEmpty() else emptyList()
        if (series.isEmpty()) {
            val cles = titresConnus.map { normaliser(it) }.filter { it.length >= 3 }.toSet()
            series = idx.series.filter { normaliser(it.titre) in cles }.take(2)
        }
        val out = ArrayList<Video.Server>()
        for (s in series) {
            val sa = runCatching { saisons(s) }.getOrDefault(emptyList()).firstOrNull { it.num == saison } ?: continue
            if (episode !in sa.episodes) continue
            out += serveurEpisode(idEpisode(sa, episode), "Cinélux · serveur ${depart + out.size}")
            if (out.size >= 2) break
        }
        return out
    }

    private fun lien(p: Portail, cmd: String, ep: String = ""): String? {
        val client = NetworkClient.default.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()
        val portail = "${p.base}/portal.php"
        val cookie = "mac=${java.net.URLEncoder.encode(p.mac, "UTF-8")}; stb_lang=en; timezone=Europe%2FLondon"
        fun js(qs: String, jeton: String?): Any? {
            val rb = okhttp3.Request.Builder().url("$portail?$qs&JsHttpRequest=1-xml")
                .header("User-Agent", MAG_UA).header("Cookie", cookie)
            if (jeton != null) rb.header("Authorization", "Bearer $jeton")
            return client.newCall(rb.build()).execute().use { r ->
                val corps = r.body?.string().orEmpty()
                runCatching { JSONObject(corps).opt("js") }.getOrNull()
            }
        }
        val jeton = (js("type=stb&action=handshake&token=", null) as? JSONObject)?.optString("token")
            ?.takeIf { it.isNotBlank() } ?: return null
        js("type=stb&action=get_profile", jeton)
        val r = js("type=vod&action=create_link&cmd=${java.net.URLEncoder.encode(cmd, "UTF-8")}" +
            "&series=$ep&forced_storage=&disable_ad=0&download=0", jeton) as? JSONObject ?: return null
        return r.optString("cmd").removePrefix("ffmpeg ").trim().takeIf { it.startsWith("http") }
    }

    /** Portails du groupe essayés l'un après l'autre jusqu'à obtenir un lien. */
    suspend fun video(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val reste = server.id.removePrefix(PREFIX_SRC)
        val parts = reste.split("::")
        val g = parts.getOrNull(0)?.toIntOrNull() ?: -1
        val cmd = parts.getOrNull(1).orEmpty()
        val ep = parts.getOrNull(2).orEmpty()   // vide pour un film
        val idx = cache ?: index()
        val portails = (if (ep.isNotEmpty()) idx?.sgroupes?.get(g) else idx?.groupes?.get(g)).orEmpty().shuffled()
        for (p in portails) {
            val url = runCatching { lien(p, cmd, ep) }.getOrNull()
            if (url != null) {
                Log.i(TAG, "lien obtenu (${p.base.substringAfter("//").substringBefore("/")})")
                return@withContext Video(
                    source = url,
                    // Extension réelle lue dans le lien (« stream=1418154.mkv »), sinon celle du cmd.
                    type = VegetaVod.mimeDe(Regex("stream=[^&.]+\\.([a-z0-9]{2,4})", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1) ?: extension(cmd)),
                    headers = mapOf("User-Agent" to UA_LECTURE),
                )
            }
        }
        throw Exception("Aucun portail n'a fourni ce film")
    }
}
