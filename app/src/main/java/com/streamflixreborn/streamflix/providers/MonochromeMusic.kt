package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.providers.FileSearchProvider.AudioResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 2026-09-23 (demande testeur relayée par le user) : base Hi-Res FLAC de monochrome.st dans
 * le mode MUSIQUE, en COMPLÉMENT de ZeffyrMusic (décision user : « Monochrome sert de
 * complément, il m'a l'air moins garni que Zeffyr ») → ses artistes, albums et titres
 * s'affichent APRÈS ceux de Zeffyr, avec la mention « FLAC ».
 *
 * ── CE QUI A ÉTÉ MESURÉ DANS LE CHROME DU USER ────────────────────────────────────
 * Tout vient du serveur PUBLIC `tracks.monochrome.st`, sans clé ni cookie :
 *   • `/search?q=`       → `{artists[10], releases[10], tracks[6], …}` (plafonds serveur :
 *                          `limit` / `offset` / `page` sont ignorés)
 *   • `/artists/<id>`    → fiche artiste + `releases[]` (albums ET singles, avec `releaseType`)
 *   • `/releases/<id>`   → album + `tracks[]` (trackId, trackNumber, discNumber, duration ms…)
 *   • `/track/<id>`      → le FICHIER FLAC lui-même (octets `fLaC`, 206 sur Range) : l'URL se
 *                          donne telle quelle à ExoPlayer, aucune résolution.
 *   Chaque piste d'album porte DÉJÀ son identifiant de fichier : contrairement au site (qui
 *   recherche « artiste + titre » avant chaque morceau), on lit directement, sans délai.
 *   Pas de filtrage d'origine (FLAC chargé depuis example.com, recherche ouverte en direct).
 *
 * ── CE QU'ON N'UTILISE VOLONTAIREMENT PAS ─────────────────────────────────────────
 *   Le site affiche ses fiches via `tidal-proxy.monochrome.tf` (relais de l'API TIDAL). Il
 *   répond 401 « Missing auth parameter » sans jeton, et ce jeton est obtenu avec un
 *   identifiant + secret d'application TIDAL écrits dans leur JS. On ne reprend PAS ce secret :
 *   ce n'est pas le nôtre, et il serait extractible de l'APK. Le serveur public suffit.
 *
 * ── LIMITE CONNUE ─────────────────────────────────────────────────────────────────
 *   Serveur de fichiers instable (503 fréquents, un 500 sur Papaoutai). Le mode musique saute
 *   déjà un morceau en erreur, un trou ne bloque donc pas la lecture.
 */
object MonochromeMusic {

    private const val TAG = "MonochromeMusic"
    private const val BASE = "https://tracks.monochrome.st"

    /** Préfixes des lignes non jouables du menu (distincts de ceux de Zeffyr). */
    const val PREFIXE_ARTISTE = "mcartist::"
    const val PREFIXE_ALBUM = "mcalbum::"

    /** Suffixe visible qui distingue une ligne Monochrome d'une ligne Zeffyr. */
    const val MENTION = " · FLAC"

    data class Artiste(val id: String, val nom: String, val image: String?)
    data class Album(
        val id: String,
        val titre: String,
        val artiste: String,
        val annee: String?,
        val type: String?,
        val pochette: String?,
    )
    data class Resultats(
        val artistes: List<Artiste>,
        val albums: List<Album>,
        val titres: List<AudioResult>,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun getJson(url: String): JsonObject? = try {
        val rq = Request.Builder()
            .url(url)
            .header("User-Agent", com.streamflixreborn.streamflix.extractors.Extractor.DEFAULT_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(rq).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "HTTP ${r.code} sur $url"); null }
            else r.body?.string()?.let { JsonParser.parseString(it).asJsonObject }
        }
    } catch (e: Exception) {
        Log.w(TAG, "échec sur $url : ${e.message}")
        null
    }

    private fun JsonObject.txt(cle: String): String? =
        get(cle)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.tab(cle: String): JsonArray? =
        get(cle)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonElement.obj(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    /** URL de lecture d'un titre : c'est le fichier FLAC lui-même. */
    private fun urlLecture(id: String) = "$BASE/track/$id"

    /** Vrai si l'URL vient de cette source. */
    fun estMonochrome(url: String): Boolean = url.startsWith("$BASE/track/")

    /** Durée en millisecondes → « 3:51 ». */
    private fun dureeLisible(ms: Long?): String {
        val s = (ms ?: return "") / 1000
        if (s <= 0) return ""
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Noms d'artistes d'une piste : `artistNames[]` (recherche) ou `artists[].name` (album). */
    private fun artistesDe(o: JsonObject): String {
        o.tab("artistNames")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?.takeIf { it.isNotEmpty() }?.let { return it.joinToString(", ") }
        return o.tab("artists")?.mapNotNull { it.obj()?.txt("name") }?.joinToString(", ").orEmpty()
    }

    private fun lisible(o: JsonObject): Boolean {
        val p = o.get("playable") ?: return true
        return p.isJsonNull || !p.isJsonPrimitive || p.asBoolean
    }

    private fun pisteEnResultat(o: JsonObject, pochette: String?): AudioResult? {
        if (!lisible(o)) return null
        val id = o.txt("trackId") ?: o.txt("id") ?: return null
        val titre = o.txt("title") ?: return null
        val artistes = artistesDe(o)
        val duree = o.get("duration")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asLong
        return AudioResult(
            title = listOfNotNull(artistes.takeIf { it.isNotBlank() }, titre).joinToString(" — ") + MENTION,
            url = urlLecture(id),
            size = listOfNotNull("FLAC", dureeLisible(duree).takeIf { it.isNotBlank() }).joinToString(" • "),
            thumbnail = pochette,
        )
    }

    private fun albumDepuis(o: JsonObject, artisteParDefaut: String = ""): Album? {
        val id = o.txt("releaseId") ?: o.txt("id") ?: return null
        val titre = o.txt("title") ?: return null
        val artiste = o.tab("artistNames")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?.joinToString(", ")?.takeIf { it.isNotBlank() }
            ?: o.tab("artists")?.mapNotNull { it.obj()?.txt("name") }?.joinToString(", ")?.takeIf { it.isNotBlank() }
            ?: artisteParDefaut
        return Album(
            id = id,
            titre = titre,
            artiste = artiste,
            annee = o.txt("releaseDate")?.take(4)?.takeIf { it.all(Char::isDigit) },
            type = o.txt("releaseType"),
            pochette = o.txt("artwork"),
        )
    }

    // ── NIVEAU 0 : RECHERCHE (artistes + albums + titres en UNE requête) ─────────────
    suspend fun rechercher(query: String): Resultats = withContext(Dispatchers.IO) {
        val q = query.trim()
        val vide = Resultats(emptyList(), emptyList(), emptyList())
        if (q.isBlank()) return@withContext vide
        val j = getJson("$BASE/search?q=${URLEncoder.encode(q, "UTF-8").replace("+", "%20")}")
            ?: return@withContext vide
        val artistes = j.tab("artists")?.mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val id = o.txt("artistId") ?: o.txt("id") ?: return@mapNotNull null
            val nom = o.txt("displayName") ?: o.txt("name") ?: return@mapNotNull null
            Artiste(id, nom, o.txt("avatar"))
        }.orEmpty()
        val albums = j.tab("releases")?.mapNotNull { it.obj()?.let { o -> albumDepuis(o) } }.orEmpty()
        val titres = j.tab("tracks")?.mapNotNull { it.obj()?.let { o -> pisteEnResultat(o, null) } }.orEmpty()
        Log.i(TAG, "recherche '$q' → ${artistes.size} artistes, ${albums.size} albums, ${titres.size} titres FLAC")
        Resultats(artistes, albums, titres)
    }

    // ── NIVEAU 1 : DISCOGRAPHIE D'UN ARTISTE ─────────────────────────────────────────
    /** Albums d'abord, puis EP et singles, chacun du plus récent au plus ancien. */
    suspend fun albumsArtiste(idArtiste: String): List<Album> = withContext(Dispatchers.IO) {
        val j = getJson("$BASE/artists/$idArtiste") ?: return@withContext emptyList()
        val nom = j.txt("displayName") ?: j.txt("name") ?: ""
        val tous = j.tab("releases")?.mapNotNull { it.obj()?.let { o -> albumDepuis(o, nom) } }.orEmpty()
        fun rang(t: String?) = when (t?.uppercase()) { "ALBUM" -> 0; "EP" -> 1; "SINGLE" -> 2; else -> 3 }
        val tries = tous.sortedWith(compareBy<Album> { rang(it.type) }.thenByDescending { it.annee ?: "" })
        Log.i(TAG, "artiste $idArtiste → ${tries.size} sorties")
        tries
    }

    // ── NIVEAU 2 : PISTES D'UN ALBUM, DANS L'ORDRE ───────────────────────────────────
    suspend fun pistesAlbum(idAlbum: String): List<AudioResult> = withContext(Dispatchers.IO) {
        val j = getJson("$BASE/releases/$idAlbum") ?: return@withContext emptyList()
        val pochette = j.txt("artwork")
        val pistes = j.tab("tracks")?.mapNotNull { it.obj() }.orEmpty()
            .sortedWith(compareBy<JsonObject>(
                { it.get("discNumber")?.takeIf { e -> e.isJsonPrimitive }?.asInt ?: 1 },
                { it.get("trackNumber")?.takeIf { e -> e.isJsonPrimitive }?.asInt ?: Int.MAX_VALUE },
            ))
            .mapNotNull { pisteEnResultat(it, pochette) }
        Log.i(TAG, "album $idAlbum → ${pistes.size} pistes")
        pistes
    }
}
