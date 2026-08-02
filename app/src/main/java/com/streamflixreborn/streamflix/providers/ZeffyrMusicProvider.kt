package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.providers.FileSearchProvider.AudioResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 2026-08-02 (demande user : « ce site nous sort des albums complets, fais en sorte que ce soit
 * arrangé, pas en vrac ») — ZeffyrMusic : source d'ALBUMS ORDONNÉS pour le mode musique.
 *
 * Ce que ce provider apporte (et que NewPipe seul ne sait pas faire) :
 *   NewPipe cherche par mots-clés et renvoie des titres isolés. ZeffyrMusic, lui, expose un
 *   catalogue ÉDITORIALISÉ : albums complets, dans l'ordre, avec le nom de l'album et la durée.
 *
 * Ce qu'il n'apporte PAS : le son. Vérifié en direct — son lecteur est une iframe YouTube
 * (aucun flux mp3/m3u8 propre). Le champ `key` de son API est un ID YouTube (11 caractères,
 * vérifié sur l'ensemble des résultats) → on le transforme en URL `youtube.com/watch?v=…`,
 * que la chaîne EXISTANTE sait déjà résoudre (NewPipeAudio.resolveAudioUrl via
 * ResolvingDataSource). Aucun extracteur nouveau n'est nécessaire.
 *
 * Points d'entrée réels du site (relevés en observant ses propres appels) :
 *   • `GET /api/fullsearch2/<requête>` → JSON `{tab_video:[…]}` — LE seul endpoint JSON public.
 *   • `GET /playlist/<id>` → page Angular dont le bloc d'hydratation `ng-state` contient les
 *     pistes de l'album. Il n'existe PAS d'API album (`/api/playlist/<id>` → 404), d'où la
 *     lecture de ce bloc.
 *
 * Champs utiles d'une piste : `key` (ID YouTube), `titre`, `artiste`, `ordre` (rang dans
 * l'album — c'est lui qui évite le « vrac »), `duree` (secondes), `titre_album`, `id_playlist`.
 */
object ZeffyrMusicProvider {

    private const val TAG = "ZeffyrMusic"
    private const val BASE = "https://www.zeffyrmusic.com"

    /** Album identifié par son `id_playlist` — stable, donc utilisable comme FAVORI. */
    data class Album(
        val id: String,
        val titre: String,
        val artiste: String,
        val nbPistes: Int,
        /** Pochette (dérivée de la 1ʳᵉ piste) — c'est elle qui signale « album complet ». */
        val pochette: String? = null,
    )

    /**
     * 2026-08-02 (user : « fais-le en 3 profondeurs — on cherche, on trouve l'artiste avec sa
     * petite jaquette, ensuite les albums, ensuite les musiques »).
     * Artiste identifié par son id de fiche — stable, donc utilisable comme FAVORI lui aussi.
     */
    data class Artiste(
        val id: String,
        val nom: String,
        val image: String? = null,
    )

    /**
     * Niveau 1 — ARTISTES correspondant à la recherche.
     * Il n'y a pas d'API pour ça : la page `/search/<q>` est rendue côté serveur et porte les
     * fiches artiste (`/artist/<id>`) avec leur portrait (servi par Deezer). On lit donc le HTML.
     */
    suspend fun searchArtists(query: String): List<Artiste> = withContext(Dispatchers.IO) {
        val html = get("$BASE/search/${URLEncoder.encode(query, "UTF-8")}") ?: return@withContext emptyList()
        val out = LinkedHashMap<String, Artiste>()
        // Bloc <a href="/artist/<id>"> … <img src="…"> … <nom> </a>
        Regex("""<a[^>]+href="/artist/(\d+)"[^>]*>([\s\S]{0,600}?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { m ->
                val id = m.groupValues[1]
                val bloc = m.groupValues[2]
                val img = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(bloc)?.groupValues?.get(1)
                val nom = bloc.replace(Regex("<[^>]+>"), " ")
                    .replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
                    .removeSuffix("Artiste").trim()
                if (nom.isNotBlank()) out.putIfAbsent(id, Artiste(id, nom, img))
            }
        Log.i(TAG, "artistes '$query' → ${out.size}")
        out.values.toList()
    }

    /**
     * Niveau 2 — ALBUMS d'un artiste (sa discographie, telle que le site la classe).
     * Même principe : la fiche `/artist/<id>` est rendue côté serveur et liste ses albums avec
     * leur vraie pochette (servie par Apple Music), bien meilleure qu'une vignette vidéo.
     */
    suspend fun getArtistAlbums(idArtiste: String): List<Album> = withContext(Dispatchers.IO) {
        val html = get("$BASE/artist/$idArtiste") ?: return@withContext emptyList()
        val albums = albumsDepuisHtml(html)
        Log.i(TAG, "artiste $idArtiste → ${albums.size} albums")
        albums
    }

    /**
     * Extrait les cartes d'album d'une page (recherche ou fiche artiste).
     * Le libellé d'une carte concatène titre + artiste + année (ex. « MercuryImagine Dragons2022 ») :
     * on isole donc l'année en fin, et on garde le reste comme intitulé.
     */
    private fun albumsDepuisHtml(html: String): List<Album> {
        val out = LinkedHashMap<String, Album>()
        Regex("""<a[^>]+href="/playlist/(\d+)"[^>]*>([\s\S]{0,800}?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { m ->
                val id = m.groupValues[1]
                val bloc = m.groupValues[2]
                val img = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(bloc)?.groupValues?.get(1)
                val texte = bloc.replace(Regex("<[^>]+>"), " ")
                    .replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
                if (texte.isBlank()) return@forEach
                val annee = Regex("""(19|20)\d{2}$""").find(texte)?.value
                val sansAnnee = if (annee != null) texte.removeSuffix(annee).trim() else texte
                out.putIfAbsent(
                    id,
                    Album(
                        id = id,
                        titre = sansAnnee.ifBlank { "Album $id" },
                        artiste = annee?.let { "($it)" } ?: "",
                        nbPistes = 0, // inconnu à ce stade : rempli à l'ouverture de l'album
                        pochette = img,
                    ),
                )
            }
        return out.values.toList()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): String? = try {
        val rq = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                com.streamflixreborn.streamflix.extractors.Extractor.DEFAULT_USER_AGENT,
            )
            .header("Referer", "$BASE/")
            .build()
        client.newCall(rq).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        Log.d(TAG, "GET échoué ($url) : ${e.message}")
        null
    }

    /** `key` (ID YouTube) → URL watch, forme que NewPipeAudio sait résoudre à la lecture. */
    private fun urlDepuisKey(key: String) = "https://www.youtube.com/watch?v=$key"

    /**
     * Pochette d'une piste. Le site n'expose AUCUN champ image dans son API — ses propres
     * vignettes sont dérivées de la clé (`img.youtube.com/vi/<key>/mqdefault.jpg`), on fait
     * donc pareil : zéro requête supplémentaire.
     * N'est renseignée que pour les pistes d'ALBUM : c'est le repère visuel qui distingue un
     * album complet d'un titre isolé (demande user).
     */
    private fun pochetteDepuisKey(key: String) = "https://img.youtube.com/vi/$key/mqdefault.jpg"

    /** Durée en secondes → « 3:45 » (affichage). */
    private fun dureeLisible(sec: String?): String {
        val s = sec?.toIntOrNull() ?: return ""
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ── RECHERCHE ────────────────────────────────────────────────────────────────────
    /**
     * Recherche de morceaux. Renvoie des [AudioResult] compatibles avec le mode musique
     * existant (mêmes champs que FileSearch/NewPipe), donc lisibles sans rien changer ailleurs.
     * Le champ `size` sert de sous-titre : on y met l'album et la durée.
     */
    suspend fun searchAudio(query: String): List<AudioResult> = withContext(Dispatchers.IO) {
        val pistes = fetchPistes("$BASE/api/fullsearch2/${URLEncoder.encode(query, "UTF-8")}")
        Log.i(TAG, "recherche '$query' → ${pistes.size} morceaux")
        pistes.map { p ->
            AudioResult(
                title = listOfNotNull(p.artiste.takeIf { it.isNotBlank() }, p.titre)
                    .joinToString(" — "),
                url = urlDepuisKey(p.key),
                size = listOfNotNull(
                    p.titreAlbum.takeIf { it.isNotBlank() },
                    dureeLisible(p.duree).takeIf { it.isNotBlank() },
                ).joinToString(" • "),
            )
        }
    }

    /** Albums distincts trouvés pour une recherche (pour proposer « l'album entier »). */
    suspend fun searchAlbums(query: String): List<Album> = withContext(Dispatchers.IO) {
        val pistes = fetchPistes("$BASE/api/fullsearch2/${URLEncoder.encode(query, "UTF-8")}")
        pistes.filter { it.idPlaylist.isNotBlank() && it.titreAlbum.isNotBlank() }
            .groupBy { it.idPlaylist }
            .map { (id, g) ->
                val premiere = g.minByOrNull { it.ordre?.toIntOrNull() ?: Int.MAX_VALUE } ?: g.first()
                Album(
                    id = id,
                    titre = g.first().titreAlbum,
                    artiste = g.first().artiste,
                    nbPistes = g.size,
                    pochette = pochetteDepuisKey(premiere.key),
                )
            }
            .also { Log.i(TAG, "recherche '$query' → ${it.size} albums") }
    }

    // ── ALBUM COMPLET ────────────────────────────────────────────────────────────────
    /**
     * Pistes d'un album, DANS L'ORDRE (tri sur `ordre`).
     *
     * Il n'y a pas d'API album (404) : les données sont dans le bloc d'hydratation Angular
     * (`<script id="ng-state" type="application/json">`) de la page `/playlist/<id>`.
     * On extrait donc ce bloc et on y lit les mêmes champs que dans `fullsearch2`.
     */
    suspend fun getAlbumTracks(idPlaylist: String): List<AudioResult> = withContext(Dispatchers.IO) {
        val html = get("$BASE/playlist/$idPlaylist") ?: return@withContext emptyList()
        val bloc = Regex(
            """<script[^>]*id="ng-state"[^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)
            ?: Regex(
                """<script[^>]*type="application/json"[^>]*>([\s\S]*?)</script>""",
                RegexOption.IGNORE_CASE,
            ).find(html)?.groupValues?.get(1)
            ?: run {
                Log.w(TAG, "album $idPlaylist : bloc d'hydratation absent (le site a changé ?)")
                return@withContext emptyList()
            }

        val pistes = pistesDepuisJson(bloc)
            .sortedBy { it.ordre?.toIntOrNull() ?: Int.MAX_VALUE }
        Log.i(TAG, "album $idPlaylist → ${pistes.size} pistes ordonnées")
        pistes.map { p ->
            AudioResult(
                title = listOfNotNull(p.artiste.takeIf { it.isNotBlank() }, p.titre)
                    .joinToString(" — "),
                url = urlDepuisKey(p.key),
                size = dureeLisible(p.duree),
                // pochette = signal « ceci vient d'un album complet, pas d'un titre isolé »
                thumbnail = pochetteDepuisKey(p.key),
            )
        }
    }

    // ── PARSING ──────────────────────────────────────────────────────────────────────
    private data class Piste(
        val key: String,
        val titre: String,
        val artiste: String,
        val titreAlbum: String,
        val duree: String?,
        val ordre: String?,
        val idPlaylist: String,
    )

    private fun fetchPistes(url: String): List<Piste> =
        get(url)?.let { pistesDepuisJson(it) } ?: emptyList()

    /**
     * Récupère toutes les pistes d'un JSON, quelle que soit sa profondeur.
     * `fullsearch2` renvoie `{tab_video:[…]}` ; le bloc d'hydratation imbrique les mêmes objets
     * plus profondément → on parcourt l'arbre et on retient tout objet portant une `key` de
     * 11 caractères (signature d'un ID YouTube) accompagnée d'un titre.
     */
    private fun pistesDepuisJson(json: String): List<Piste> = try {
        val out = LinkedHashMap<String, Piste>()
        fun visiter(e: com.google.gson.JsonElement) {
            when {
                e.isJsonArray -> e.asJsonArray.forEach { visiter(it) }
                e.isJsonObject -> {
                    val o = e.asJsonObject
                    val key = o.get("key")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    val titre = o.get("titre")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    if (key.length == 11 && titre.isNotBlank()) {
                        out.putIfAbsent(
                            key,
                            Piste(
                                key = key,
                                titre = titre,
                                artiste = o.get("artiste")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                                titreAlbum = o.get("titre_album")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                                duree = o.get("duree")?.takeIf { it.isJsonPrimitive }?.asString,
                                ordre = o.get("ordre")?.takeIf { it.isJsonPrimitive }?.asString,
                                idPlaylist = o.get("id_playlist")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                            ),
                        )
                    }
                    o.entrySet().forEach { visiter(it.value) }
                }
            }
        }
        visiter(JsonParser.parseString(json))
        out.values.toList()
    } catch (e: Exception) {
        Log.w(TAG, "parsing JSON échoué : ${e.message}")
        emptyList()
    }
}
