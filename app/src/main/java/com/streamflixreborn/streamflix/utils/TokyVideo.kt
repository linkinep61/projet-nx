package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Tokyvideo — SOURCE DE SECOURS « vieilles séries et films en VF » (tokyvideo.com).
 *
 * 2026-09-06 (user : « un site avec des vieilles séries, ça vaut vraiment le coup… c'est pas plus
 * simple de faire juste un backup ? quand les gens cherchent une série sur Movix, ça matche en
 * tant que serveur supplémentaire »). Le site héberge des playlists (« séries ») d'uploadeurs :
 * Columbo, X-Files, Code Quantum, Magnum, L'Agence tous risques, La Quatrième Dimension,
 * Starsky & Hutch, Goldorak, Capitaine Flam, Happy Days, Alf… et des collections de films
 * (Louis de Funès, westerns, SF…). Qualité DVDRip, VF.
 *
 * Pourquoi un index publié (nx-data, `scripts/tokyvideo/refresh_tokyvideo.py`, chaque nuit) :
 * la recherche du site N'INDEXE PAS ces vidéos (« columbo » ne rend que le générique), donc
 * impossible de chercher à la volée. L'index liste ~600 playlists → séries (saisons/épisodes
 * reconnus dans les titres « COLUMBO.S01.E01… ») et films (durée ≥ 55 min).
 *
 * Lecture : la page d'une vidéo contient un MP4 signé sur leur CDN
 * (`cdnst30.tokyvideo.com/…/mp4/<hash>.mp4?secure=…`, valable ~24 h, pas de Referer requis,
 * requêtes partielles OK → avance/recul). On la lit AU CLIC, jamais à l'avance.
 *
 * Rattachement : pas d'identifiant TMDB chez eux → titre normalisé (exact, puis préfixe ≥ 5
 * caractères) + année ±1 quand les deux sont connues ; épisode par (saison, numéro).
 */
object TokyVideo {

    private const val TAG = "TokyVideo"
    private const val SITE = "https://www.tokyvideo.com"
    private const val INDEX_URL = "https://raw.githubusercontent.com/xdata-mix/nx-data/main/data/tokyvideo/index.json"
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    const val PREFIX_SRV = "tokyvideo::"
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    data class Ep(val num: Int, val vid: String, val slug: String, val titre: String)
    data class Serie(val id: String, val titre: String, val annee: Int, val cle: String, val img: String,
                     val saisons: Map<Int, List<Ep>>)
    data class Film(val vid: String, val slug: String, val titre: String, val annee: Int, val cle: String,
                    val img: String, val collection: String)
    class Index(val series: List<Serie>, val films: List<Film>)

    @Volatile private var cache: Index? = null
    @Volatile private var cacheTs = 0L
    private val verrou = Mutex()

    suspend fun index(): Index? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache?.let { if (now - cacheTs < TTL_MS) return@withContext it }
        verrou.withLock {
            cache?.let { if (System.currentTimeMillis() - cacheTs < TTL_MS) return@withLock it }
            val idx = runCatching { telecharger() }.getOrElse { Log.w(TAG, "index illisible : ${it.message}"); null }
                ?: return@withLock cache
            if (idx.series.isEmpty() && idx.films.isEmpty()) return@withLock cache
            cache = idx
            cacheTs = System.currentTimeMillis()
            Log.i(TAG, "index chargé : ${idx.series.size} séries, ${idx.films.size} films")
            idx
        }
    }

    /** Pré-chauffage silencieux (appelé au démarrage de BackupRegistry si on veut éviter la latence du 1er clic). */
    fun prechauffer() {
        CoroutineScope(Dispatchers.IO).launch { runCatching { index() } }
    }

    private fun telecharger(): Index? {
        val req = okhttp3.Request.Builder()
            .url("$INDEX_URL?h=${System.currentTimeMillis() / 3_600_000L}")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .build()
        return NetworkClient.default.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "http ${r.code} sur l'index"); return@use null }
            val corps = r.body ?: return@use null
            android.util.JsonReader(java.io.BufferedReader(corps.charStream(), 64 * 1024)).use { analyser(it) }
        }
    }

    /** Lecture en flux (cf. format dans refresh_tokyvideo.py). */
    private fun analyser(jr: android.util.JsonReader): Index {
        val series = ArrayList<Serie>(600)
        val films = ArrayList<Film>(3000)
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "series" -> {
                    jr.beginArray()
                    while (jr.hasNext()) {
                        var id = ""; var t = ""; var y = 0; var k = ""; var img = ""
                        val saisons = HashMap<Int, List<Ep>>()
                        jr.beginObject()
                        while (jr.hasNext()) {
                            when (jr.nextName()) {
                                "id" -> id = jr.nextString(); "t" -> t = jr.nextString()
                                "y" -> y = jr.nextInt(); "k" -> k = jr.nextString(); "img" -> img = jr.nextString()
                                "s" -> {
                                    jr.beginObject()
                                    while (jr.hasNext()) {
                                        val num = jr.nextName().toIntOrNull() ?: 0
                                        val eps = ArrayList<Ep>()
                                        jr.beginArray()
                                        while (jr.hasNext()) {
                                            jr.beginArray()
                                            val e = jr.nextInt(); val vid = jr.nextString(); val slug = jr.nextString()
                                            val titre = if (jr.hasNext()) jr.nextString() else ""
                                            while (jr.hasNext()) jr.skipValue()
                                            jr.endArray()
                                            eps += Ep(e, vid, slug, titre)
                                        }
                                        jr.endArray()
                                        saisons[num] = eps
                                    }
                                    jr.endObject()
                                }
                                else -> jr.skipValue()
                            }
                        }
                        jr.endObject()
                        if (t.isNotBlank() && saisons.isNotEmpty())
                            series += Serie(id, t, y, k.ifBlank { normaliser(t) }, img, saisons)
                    }
                    jr.endArray()
                }
                "films" -> {
                    jr.beginArray()
                    while (jr.hasNext()) {
                        var vid = ""; var slug = ""; var t = ""; var y = 0; var k = ""; var img = ""; var col = ""
                        jr.beginObject()
                        while (jr.hasNext()) {
                            when (jr.nextName()) {
                                "vid" -> vid = jr.nextString(); "slug" -> slug = jr.nextString(); "t" -> t = jr.nextString()
                                "y" -> y = jr.nextInt(); "k" -> k = jr.nextString(); "img" -> img = jr.nextString()
                                "col" -> col = jr.nextString()
                                else -> jr.skipValue()
                            }
                        }
                        jr.endObject()
                        if (vid.isNotBlank() && slug.isNotBlank() && t.isNotBlank())
                            films += Film(vid, slug, t, y, k.ifBlank { normaliser(t) }, img, col)
                    }
                    jr.endArray()
                }
                else -> jr.skipValue()
            }
        }
        jr.endObject()
        return Index(series, films)
    }

    fun normaliser(s: String): String {
        val d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase()
        return d.replace(Regex("[^a-z0-9]"), "")
    }

    private fun anneeCompatible(a: Int?, b: Int, tolerance: Int = 1): Boolean =
        a == null || a <= 0 || b <= 0 || kotlin.math.abs(a - b) <= tolerance

    /**
     * 2026-09-06 (user : « faut juste que ça fasse pas de mauvais match ») — RATTACHEMENT STRICT.
     * Clé exacte (titre normalisé) d'abord. Le préfixe n'est accepté QUE si l'année est connue
     * des deux côtés et compatible, et si les deux clés ont des longueurs proches (ratio ≤ 1,6) :
     * « startrek » ≠ « startrekdiscovery », « magnum » ≠ « magnumpi » passe (ratio 1,3, même
     * année). Sans année côté app, aucun préfixe.
     */
    private fun <T> chercher(candidats: List<T>, cles: Set<String>, annee: Int?, tolerance: Int,
                             cleDe: (T) -> String, anneeDe: (T) -> Int): List<T> {
        val exacts = candidats.filter { cleDe(it) in cles }
        if (exacts.isNotEmpty()) {
            // Plusieurs homonymes (Batman 1966 / 1989…) : le plus proche de l'année demandée d'abord.
            return if (annee != null && annee > 0) exacts.sortedBy { val y = anneeDe(it); if (y > 0) kotlin.math.abs(y - annee) else 99 }
                   else exacts
        }
        if (annee == null || annee <= 0) return emptyList()
        val longues = cles.filter { it.length >= 6 }
        return candidats.filter { c ->
            val k = cleDe(c)
            val y = anneeDe(c)
            y > 0 && kotlin.math.abs(y - annee) <= tolerance && k.length >= 6 && longues.any {
                (it.startsWith(k) || k.startsWith(it)) &&
                    maxOf(it.length, k.length).toDouble() / minOf(it.length, k.length) <= 1.6
            }
        }.sortedByDescending { cleDe(it).length }
    }

    private fun serveur(vid: String, slug: String, nom: String) =
        Video.Server(id = "$PREFIX_SRV$vid", name = nom, src = "$SITE/fr/video/$slug")

    /**
     * Source de secours (BackupRegistry, emit « Tokyvideo ») : film ou épisode s'il existe chez eux.
     * Rend jusqu'à 2 serveurs, vide sinon.
     */
    suspend fun serveursPour(
        titresConnus: Collection<String>,
        annee: Int?,
        estUnFilm: Boolean,
        saison: Int = 0,
        episode: Int = 0,
    ): List<Video.Server> {
        val idx = try { index() } catch (e: Exception) { null } ?: return emptyList()
        val cles = titresConnus.map { normaliser(it) }.filter { it.length >= 3 }.toSet()
        if (cles.isEmpty()) return emptyList()
        if (estUnFilm) {
            // Films : clé exacte ; année ±1 quand elle est connue ; sans année, on n'accepte
            //   qu'un candidat UNIQUE (« Batman » 1966 / 1989 / 2004 : ambigu → rien).
            var films = chercher(idx.films, cles, annee, 1, { it.cle }, { it.annee })
            films = if (annee != null && annee > 0) films.filter { anneeCompatible(annee, it.annee, 1) }
                    else if (films.size == 1) films else emptyList()
            films = films.take(2)
            if (films.isNotEmpty()) Log.i(TAG, "backup film « ${titresConnus.firstOrNull()} » (${annee ?: "?"}) : ${films.joinToString { "${it.titre} (${it.annee})" }}")
            // Libellé « serveur N » (BackupRegistry affiche « Tokyvideo · serveur N »).
            return films.mapIndexed { i, f -> serveur(f.vid, f.slug, "serveur ${i + 1}") }
        }
        if (saison <= 0 || episode <= 0) return emptyList()
        // Séries : l'année de la playlist est souvent celle du pilote (« Columbo 1968 » alors que
        //   TMDB dit 1971) → clé exacte sans regarder l'année ; préfixe = ±3 ans + longueurs proches.
        val series = chercher(idx.series, cles, annee, 3, { it.cle }, { it.annee }).take(3)
        val out = ArrayList<Video.Server>()
        for (s in series) {
            val ep = s.saisons[saison]?.firstOrNull { it.num == episode } ?: continue
            out += serveur(ep.vid, ep.slug, "serveur ${out.size + 1}")
            if (out.size >= 2) break
        }
        if (out.isNotEmpty()) Log.i(TAG, "backup série « ${titresConnus.firstOrNull()} » S${saison}E$episode : ${out.size} serveur(s)")
        return out
    }

    /** Lecture : la page de la vidéo → `<source src="…mp4?secure=…">` (signé ~24 h). */
    fun video(server: Video.Server): Video {
        val req = okhttp3.Request.Builder().url(server.src)
            .header("User-Agent", UA)
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .build()
        val html = NetworkClient.default.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Tokyvideo : http ${r.code} sur ${server.src}")
            r.body?.string() ?: ""
        }
        val mp4 = Regex("""<source[^>]+src="([^"]+\.mp4[^"]*)"""").find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?: throw Exception("Tokyvideo : pas de MP4 dans la page ${server.src}")
        Log.i(TAG, "mp4 résolu pour ${server.id} : ${mp4.substringBefore("?").takeLast(40)}")
        return Video(
            source = mp4,
            type = androidx.media3.common.MimeTypes.VIDEO_MP4,
            headers = mapOf("User-Agent" to UA),
        )
    }
}
