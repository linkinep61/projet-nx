package com.streamflixreborn.streamflix.providers

import android.util.Log
import androidx.media3.common.MimeTypes
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * PurstreamProvider — backup NATIF par tmdbId (purstream.store, 2026-08-16).
 *
 * Source FR qui heberge SES PROPRES flux : le `stream_url` rendu est un master HLS DIRECT
 * (free.finepulfe.xyz/...m3u8) lu NATIVEMENT par ExoPlayer — aucun extracteur a ecrire, aucun
 * hebergeur tiers, aucune page d'embed. Aucune protection : ni compte, ni Cloudflare, ni jeton.
 *
 * Reverse en direct dans le Chrome du user (api.purstream.store, enveloppe commune
 * {apiVersion, type, data:{count, items}}) :
 *
 *  1. RECHERCHE  GET /api/v1/search-bar/search/<titre>
 *       -> data.items.movies.items[] = {id, title, type:"movie"|"tv"}
 *       PIEGE : le terme se met dans le CHEMIN, PAS en parametre. ?q= ?query= ?search= ?s=
 *         ?term= ?title= sont TOUS IGNORES et renvoient une liste generique identique (elle
 *         rendait « Deadman Wonderland » pour « game of thrones ») — j'ai d'abord cru l'API
 *         cassee a cause de ca. Le chemin, lui, filtre correctement.
 *
 *  2. FICHE      GET /api/v1/media/<id>/sheet
 *       -> data.items = {id, tmdbId, type, isAnime, title, seasons:Int, episodes:Int}
 *       SEULE source du tmdbId (absent des resultats de recherche) -> on confirme chaque
 *       candidat ici. Aucun rapprochement flou : on n'accepte QUE l'egalite EXACTE du tmdbId,
 *       donc jamais le mauvais titre (« pas de serveur plutot que le mauvais film »).
 *       ATTENTION : `seasons`/`episodes` sont des COMPTEURS (Int), pas des listes.
 *
 *  3. FLUX       GET /api/v1/stream/<id>
 *       -> data.items.sources[] = {stream_url, source_name, format}
 *       `source_name` = « pulse | 1080p | MULTI » (qualite + langue, reutilise a l'affichage).
 *       FILM  : 1 source, /movies/<tmdb>-<hash>/<qualite>/playlist.m3u8
 *       SERIE : TOUTES les sources de la serie d'un coup (73 pour Game of Thrones), la saison
 *               et l'episode etant portes PAR LE CHEMIN : /tv/<tmdb>-<hash>/S02/E04/...m3u8
 *               -> un seul appel reseau quelle que soit la saison, on filtre sur S../E..
 *               Verifie : un episode inexistant (S99E99) ne matche rien -> 0 serveur, pas de
 *               serveur fantome.
 *
 * DOMAINE : pas d'auto-decouverte possible. Le portail purstream.wiki annonce bien l'adresse du
 *   moment, mais elle est injectee PAR JAVASCRIPT — absente du HTML servi, des bundles JS et des
 *   pages /servers-status/ et /fai-status/. Un fetch OkHttp ne la verrait pas. On garde donc une
 *   constante + repli ; si le domaine bouge, c'est ICI qu'on patche.
 *
 * Source BACKUP uniquement (pas browsable) : appelee par BackupRegistry par tmdbId + titres.
 * `getVideo` n'a rien a re-resoudre (URL finale, sans jeton) -> route directe.
 */
object PurstreamProvider {
    private const val TAG = "PurstreamProvider"

    /** Hotes API, dans l'ordre d'essai. Le premier qui repond est memorise. */
    private val API_HOSTS = listOf(
        "https://api.purstream.store",
        "https://api.purstream.wiki",
    )

    @Volatile private var lastGoodHost: String = API_HOSTS.first()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // -- DTOs (enveloppe {apiVersion,type,data:{count,items}}) ---------------
    private data class Env<T>(val data: Data<T>? = null)
    private data class Data<T>(val count: Int? = null, val items: T? = null)

    private data class SearchItems(val movies: Bucket? = null)
    private data class Bucket(val count: Int? = null, val items: List<Hit>? = null)
    private data class Hit(val id: Long? = null, val title: String? = null, val type: String? = null)

    private data class Sheet(
        val id: Long? = null,
        val tmdbId: Long? = null,
        val type: String? = null,
        val title: String? = null,
    )

    private data class StreamItems(val sources: List<Source>? = null)
    private data class Source(
        val stream_url: String? = null,
        val source_name: String? = null,
        val format: String? = null,
    )

    /** Parse typé de l'enveloppe : renvoie `data.items` ou null. */
    private inline fun <reified T> parse(body: String): T? = runCatching {
        val type = TypeToken.getParameterized(Env::class.java, T::class.java).type
        gson.fromJson<Env<T>>(body, type)?.data?.items
    }.getOrNull()

    // -- HTTP ---------------------------------------------------------------
    private fun get(path: String): String? {
        val hosts = buildList {
            add(lastGoodHost)
            API_HOSTS.forEach { if (it != lastGoodHost) add(it) }
        }
        for (host in hosts) {
            try {
                val req = Request.Builder()
                    .url(host + path)
                    .header("Accept", "application/json")
                    .header("User-Agent", UA)
                    .header("Referer", "https://purstream.store/")
                    .header("Accept-Language", "fr-FR,fr;q=0.9")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val body = r.body?.string()
                        if (!body.isNullOrBlank() && body.trimStart().startsWith("{")) {
                            lastGoodHost = host
                            return body
                        }
                    }
                }
            } catch (_: Exception) { /* hote suivant */ }
        }
        return null
    }

    // -- Point d'entree BackupRegistry --------------------------------------
    suspend fun fetchPurstreamBackupServers(
        tmdbId: String,
        videoType: Video.Type,
        season: Int = 0,
        episode: Int = 0,
        titles: List<String> = emptyList(),
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        val wanted = tmdbId.trim().toLongOrNull() ?: return@withContext emptyList()

        // Titres a essayer : celui du contenu courant + les titres alternatifs connus.
        val titreCandidats = LinkedHashSet<String>()
        when (videoType) {
            is Video.Type.Movie -> videoType.title?.let { titreCandidats.add(it) }
            is Video.Type.Episode -> videoType.tvShow.title?.let { titreCandidats.add(it) }
        }
        titles.forEach { t -> t.takeIf { it.isNotBlank() }?.let(titreCandidats::add) }
        if (titreCandidats.isEmpty()) return@withContext emptyList()

        val estEpisode = videoType is Video.Type.Episode
        val typeAttendu = if (estEpisode) "tv" else "movie"

        // 1) chercher par titre, 2) CONFIRMER par tmdbId exact sur la fiche.
        var mediaId: Long? = null
        val vus = HashSet<Long>()
        boucle@ for (titre in titreCandidats.take(4)) {
            val q = URLEncoder.encode(titre.trim(), "UTF-8").replace("+", "%20")
            val body = get("/api/v1/search-bar/search/$q") ?: continue
            val hits = parse<SearchItems>(body)?.movies?.items ?: continue

            for (h in hits.filter { it.type.equals(typeAttendu, true) }.take(6)) {
                val id = h.id ?: continue
                if (!vus.add(id)) continue
                val sheetBody = get("/api/v1/media/$id/sheet") ?: continue
                val sheet = parse<Sheet>(sheetBody) ?: continue
                if (sheet.tmdbId == wanted) {
                    mediaId = id
                    Log.d(TAG, "match tmdb=$wanted -> id=$id ('${sheet.title?.take(40)}')")
                    break@boucle
                }
            }
        }

        val id = mediaId ?: run {
            Log.d(TAG, "aucun media Purstream pour tmdb=$wanted")
            return@withContext emptyList()
        }

        // 3) flux — UN seul appel, meme pour une serie entiere.
        val streamBody = get("/api/v1/stream/$id") ?: return@withContext emptyList()
        val sources = parse<StreamItems>(streamBody)?.sources.orEmpty()
        if (sources.isEmpty()) return@withContext emptyList()

        // Episode : filtrer sur /S../E../ (zero de tete tolere des deux cotes).
        val retenues = if (estEpisode) {
            val re = Regex("/S0*$season/E0*$episode/", RegexOption.IGNORE_CASE)
            sources.filter { s -> s.stream_url?.let { re.containsMatchIn(it) } == true }
        } else sources

        if (retenues.isEmpty()) {
            Log.d(TAG, "id=$id : aucune source pour S${season}E$episode")
            return@withContext emptyList()
        }

        retenues.mapIndexedNotNull { i, s ->
            val url = s.stream_url?.takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
            // « pulse | 1080p | MULTI » -> « 1080p · MULTI »
            val label = s.source_name
                ?.split("|")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() && !it.equals("pulse", true) }
                ?.joinToString(" · ")
                ?.takeIf { it.isNotBlank() }
                ?: "Purstream"
            Video.Server(
                id = "purstream_$i",
                name = "Purstream · $label",
                src = url,
            )
        }.also { Log.d(TAG, "tmdb=$wanted -> ${it.size} serveurs") }
    }

    /**
     * L'URL emise est deja le master HLS final (pas de jeton, pas de peremption observee) :
     * rien a re-resoudre a la lecture.
     */
    fun getVideo(server: Video.Server): Video = Video(
        source = server.src,
        type = MimeTypes.APPLICATION_M3U8,
        headers = mapOf(
            "Referer" to "https://purstream.store/",
            "Origin" to "https://purstream.store",
            "User-Agent" to UA,
        ),
    )
}
