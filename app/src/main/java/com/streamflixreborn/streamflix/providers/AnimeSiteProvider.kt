package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

// 2026-05-12 : AnimeSite (animesite.fr) — provider anime français.
//
// Architecture : Next.js SPA. Le contenu serveur-rendered de la home est en HTML
// standard ; les pages series-listing sont en JS client-side mais on parse la home pour
// trending/top-rated/derniers. Les détails de saisons + épisodes sont embarqués
// dans le HTML des pages /play/ via le format __next_f.push (React Server Components).
//
// URL scheme :
//   - Home : https://animesite.fr/
//   - Série : https://animesite.fr/{numeric_id}-{slug}        ex: /1580208-one-punch-man
//   - Episode : https://animesite.fr/play/{id-slug}/{season}/{episode}
//   - Lecteurs : .../{lecteur_num} ou .../{lecteur_num}/{vo|vf}
//     - Lecteur 1 (VOSTFR) -> sendvid.com
//     - Lecteur 3 (VOSTFR) -> myvi.top  (pas d'extractor, sera ignoré pour l'instant)
//     - Lecteur 4 (VOSTFR) -> sbfull.com (pas d'extractor)
//     - Lecteur 5 (VF)     -> sendvid.com
//
// Pour l'instant on supporte uniquement les lecteurs SendVid (L1 VOSTFR + L5 VF).
// MyVi et SbFull peuvent être ajoutés plus tard (extractors séparés).
//
// ID schemes pour Streamflix :
//   - Show ID = `{numeric}-{slug}`           ex: `1580208-one-punch-man`
//   - Season ID = `{show}/{season_num}`      ex: `1580208-one-punch-man/1`
//   - Episode ID = `{show}/{season}/{ep}`    ex: `1580208-one-punch-man/1/1`
//   - Server ID = `{ep}|{lecteur}|{lang}`    ex: `1580208-one-punch-man/1/1|1|vostfr`
object AnimeSiteProvider : Provider {

    override val name = "AnimeSite"
    override val baseUrl = "https://animesite.fr/"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_animesite"
    override val language = "fr"

    private const val TAG = "AnimeSite"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val client: OkHttpClient by lazy {
        // 2026-05-16 (user "anime site ne fonctionne plus / met du temps à
        // répondre / je crois qu'il met encore plus que ça") : timeouts 20s→90s.
        // animesite.fr (Cloudflare) répond TRÈS lentement par moments. Avec
        // 20s, getHome() throw SocketTimeoutException → écran d'erreur. 90s
        // laisse le temps à la réponse même quand le site rame fort.
        OkHttpClient.Builder()
            .connectTimeout(90, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .build()
        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: ""
        Jsoup.parse(html).apply { setBaseUri(baseUrl) }
    }

    private suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .build()
        val response = client.newCall(request).execute()
        response.body?.string() ?: ""
    }

    // ───────── Parsing helpers ─────────

    /**
     * Extrait les anime listés sur une page (home, by-category, etc.).
     * Format des anchors : `<a href="/{numeric}-{slug}" title="Title">...</a>`.
     * On déduplique par slug pour éviter les répétitions inter-sections.
     */
    private fun parseAnimeLinks(document: Document): List<TvShow> {
        val out = mutableListOf<TvShow>()
        val seen = mutableSetOf<String>()
        document.select("a[href]").forEach { a ->
            val href = a.attr("href")
            // Match /{numeric_id}-{slug} (pas /play/...)
            val m = Regex("""^/(\d{7})-([a-z0-9\-]+)$""").find(href) ?: return@forEach
            val slug = m.groupValues[2]
            if (!seen.add(slug)) return@forEach
            val id = "${m.groupValues[1]}-$slug"
            val title = a.attr("title").ifBlank { a.text().trim() }
            // Cherche une img dans ou autour de l'anchor
            val img = a.selectFirst("img")?.attr("src")
                ?: a.parent()?.selectFirst("img")?.attr("src")
                ?: ""
            out.add(
                TvShow(
                    id = id,
                    title = if (title.isBlank()) slug.replace("-", " ").replaceFirstChar { it.uppercase() } else title,
                    poster = img.takeIf { it.startsWith("http") },
                )
            )
        }
        return out
    }

    // ───────── HOME ─────────

    override suspend fun getHome(): List<Category> {
        val document = fetchDocument(baseUrl)
        val categories = mutableListOf<Category>()

        // Parse les sections de la home par les h2 + listes d'anchors qui suivent.
        // Heuristique : chaque h2 est un titre de section, et les anime cards juste
        // après partagent une portée jusqu'au prochain h2.
        val allShows = parseAnimeLinks(document)

        // Fallback simple : toute la liste en une catégorie "Tendances"
        // (la home contient déjà tri par sections : Nouveaux + Tendances + Top notés)
        if (allShows.isNotEmpty()) {
            // Sépare grossièrement en 3 sections en fonction de la position dans le DOM.
            // On prend les 20 premiers comme "Nouveaux", les 20 suivants comme
            // "Tendances", le reste comme "Top notés".
            val nouveaux = allShows.take(20)
            val tendances = allShows.drop(20).take(20)
            val top = allShows.drop(40).take(20)

            if (nouveaux.isNotEmpty()) {
                categories.add(Category(name = "Nouveaux épisodes", list = nouveaux))
            }
            if (tendances.isNotEmpty()) {
                categories.add(Category(name = "Animés tendances", list = tendances))
            }
            if (top.isNotEmpty()) {
                categories.add(Category(name = "Animés les mieux notés", list = top))
            }
        }

        return categories
    }

    // ───────── SEARCH ─────────

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        // AnimeSite a une page /search/{query} qui retourne du HTML serveur-rendered.
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "${baseUrl}search/$encoded"
        return try {
            val document = fetchDocument(url)
            parseAnimeLinks(document).map { it as AppAdapter.Item }
        } catch (e: Exception) {
            Log.w(TAG, "search failed for '$query': ${e.message}")
            emptyList()
        }
    }

    // ───────── BROWSE ─────────

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        // Page /series/all liste tout le catalogue. Pas de pagination simple (SPA).
        // On limite à la home pour avoir des résultats rapidement.
        val url = if (page == 1) baseUrl else "${baseUrl}series/all"
        return try {
            parseAnimeLinks(fetchDocument(url))
        } catch (e: Exception) {
            Log.w(TAG, "getTvShows page=$page failed: ${e.message}")
            emptyList()
        }
    }

    // ───────── DETAILS ─────────

    override suspend fun getMovie(id: String): Movie {
        throw UnsupportedOperationException("AnimeSite ne fournit pas de films séparés (tout est en TvShow).")
    }

    override suspend fun getTvShow(id: String): TvShow {
        // Fetch la page anime pour récupérer poster/backdrop/synopsis/saisons.
        val url = "${baseUrl}$id"
        val html = fetchHtml(url)
        val document = Jsoup.parse(html)

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" |")?.trim()
            ?: id.substringAfter("-").replace("-", " ").replaceFirstChar { it.uppercase() }
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val synopsis = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        // 2026-05-12 fix : le JSON __next_f embarqué dans le HTML est ECHAPPÉ —
        // chaque " devient \" dans la source. On unescape avant de regex.
        val unescaped = html.replace("\\\"", "\"")

        // Extrait les saisons depuis le JSON. Pattern : `,"numero":"N","episodes"`
        // identifie un objet saison (épisodes ont aussi "numero" mais pas suivi
        // de "episodes").
        val seasonNumbers = Regex(""""numero":"(\d+)","episodes"""").findAll(unescaped)
            .map { it.groupValues[1].toIntOrNull() ?: 0 }
            .filter { it > 0 }
            .distinct()
            .toList()
        val seasons = if (seasonNumbers.isNotEmpty()) {
            seasonNumbers.map { n ->
                Season(id = "$id/$n", number = n, title = "Saison $n", poster = poster)
            }
        } else {
            // Fallback : au moins une saison par défaut
            listOf(Season(id = "$id/1", number = 1, title = "Saison 1", poster = poster))
        }

        return TvShow(
            id = id,
            title = title,
            overview = synopsis,
            poster = poster,
            seasons = seasons,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // seasonId = "{show_id}/{season_num}"  ex: "1580208-one-punch-man/1"
        val parts = seasonId.split("/")
        if (parts.size != 2) return emptyList()
        val showId = parts[0]
        val seasonNum = parts[1].toIntOrNull() ?: return emptyList()

        // Fetch l'episode 1 de la saison — la page contient tous les épisodes de la
        // saison dans le JSON __next_f embarqué (format React Server Components).
        val url = "${baseUrl}play/$showId/$seasonNum/1"
        val html = fetchHtml(url)

        // 2026-05-12 fix : le JSON est ECHAPPÉ dans le HTML (`\"id\":N` au lieu
        // de `"id":N`). Unescape AVANT toute regex.
        val unescaped = html.replace("\\\"", "\"")

        // Parse les épisodes. Pattern observé (urlImage peut être null ou une URL) :
        //   {"id":119599,"numero":"1","titre":"L'homme le plus fort du monde",
        //    "urlImage":"https://image.tmdb.org/..."}
        //   {"id":30706476,"numero":"1","titre":"Épisode 1","urlImage":null,"synopsis":null}
        val regex = Regex(""""id":(\d+),"numero":"(\d+)","titre":"([^"]+)","urlImage":(?:"([^"]+)"|null)""")
        val allMatches = regex.findAll(unescaped).toList()
        Log.d(TAG, "getEpisodesBySeason($seasonId): found ${allMatches.size} episode entries in HTML")

        // 2026-05-12 v2 fix : les épisodes sont dans des chunks RSC séparés (1f, 20, 21, ...)
        // qui apparaissent AVANT le seasonMarker. Donc on prend TOUS les matches comme
        // épisodes pour la saison courante (l'URL fetch est spécifique à la saison).
        // On déduplique par numéro d'épisode au cas où chunks apparaissent plusieurs fois.
        val seenNums = mutableSetOf<Int>()
        val filtered = allMatches.filter { match ->
            val num = match.groupValues[2].toIntOrNull() ?: return@filter false
            seenNums.add(num)
        }
        Log.d(TAG, "getEpisodesBySeason($seasonId): ${filtered.size} unique episodes after dedup")

        return filtered.mapIndexed { idx, match ->
            val epNum = match.groupValues[2].toIntOrNull() ?: (idx + 1)
            val title = match.groupValues[3]
                .replace("\\u0027", "'")
                .replace("\\u00e9", "é")
                .replace("\\u00e8", "è")
                .replace("\\u00ea", "ê")
                .replace("\\u00e0", "à")
                .replace("\\u00f4", "ô")
                .replace("\\u00ee", "î")
                .replace("\\u00fb", "û")
                .replace("\\u00e7", "ç")
                .replace("\\n", " ")
            // Group 4 = URL si urlImage non-null, vide si null
            val img = match.groupValues.getOrNull(4) ?: ""
            Episode(
                id = "$showId/$seasonNum/$epNum",
                number = epNum,
                title = title,
                poster = img.takeIf { it.startsWith("http") },
            )
        }
    }

    // ───────── GENRE / PEOPLE ─────────

    override suspend fun getGenre(id: String, page: Int): Genre {
        // AnimeSite : URL /series/by-category/{Genre}
        val url = "${baseUrl}series/by-category/$id"
        return try {
            val shows = parseAnimeLinks(fetchDocument(url))
            Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = shows)
        } catch (e: Exception) {
            Genre(id = id, name = id, shows = emptyList())
        }
    }

    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = id)

    // ───────── SERVERS / VIDEO ─────────

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        // id format : "{show_id}/{season}/{episode}"  ex: "1580208-one-punch-man/1/1"
        // Extrait : (a) les 2 lecteurs SendVid de AnimeSite + (b) les serveurs des 4
        // autres providers anime (AnimeSama / FrenchAnime / VoirAnime / FrenchManga)
        // en parallèle. Si SendVid est en panne, l'user a des alternatives fiables.
        val servers = mutableListOf<Video.Server>()

        // Extract show slug + season + episode from id
        val parts = id.split("/")
        val showId = parts.getOrNull(0) ?: id
        val seasonNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val episodeNum = parts.getOrNull(2)?.toIntOrNull() ?: 1
        // Title from slug : "1580208-one-punch-man" → "One Punch Man"
        val title = showId.substringAfter("-").replace("-", " ")
            .split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }

        // 2026-05-12 : lecteurs 2/3/4 d'AnimeSite pointent vers myvi.top + sbfull.com
        // qui sont des **domaines parkés/expirés** (parklogic.com → ad farm).
        // On les skip. Seuls les lecteurs 1 (VOSTFR) et 5 (VF) via sendvid.com fonctionnent.
        val lecteurs = listOf(
            Triple(1, "vostfr", "SendVid VOSTFR"),
            Triple(5, "vf", "SendVid VF"),
        )

        coroutineScope {
            // (a) Serveurs natifs AnimeSite (SendVid)
            val nativeDeferreds = lecteurs.map { (num, lang, label) ->
                async(Dispatchers.IO) {
                    try {
                        val url = "${baseUrl}play/$id/$num${if (lang == "vf") "/vf" else ""}"
                        val html = fetchHtml(url)
                        val unescaped = html.replace("\\\"", "\"")
                        val iframeUrl = Regex(""""src":"(https?://(?:www\.)?(?:sendvid\.com)/[^"]+)"""")
                            .find(unescaped)?.groupValues?.get(1)
                        if (iframeUrl != null) {
                            Log.d(TAG, "Lecteur $num/$lang ($label): $iframeUrl")
                            Video.Server(
                                id = "$id|$num|$lang",
                                name = label,
                                src = iframeUrl,
                            )
                        } else null
                    } catch (e: Exception) {
                        Log.w(TAG, "Lecteur $num/$lang failed: ${e.message}")
                        null
                    }
                }
            }

            // (b) Serveurs fallback des 4 autres providers anime
            // Chaque provider tournent en parallèle. Échec d'un n'affecte pas les autres.
            val backupProviders = listOf(
                AnimeSamaProvider as Provider,
                FrenchAnimeProvider as Provider,
                VoirAnimeProvider as Provider,
                FrenchMangaProvider as Provider,
            )
            val backupDeferreds = backupProviders.map { p ->
                async(Dispatchers.IO) {
                    findBackupServers(p, title, seasonNum, episodeNum)
                }
            }

            nativeDeferreds.awaitAll().forEach { srv -> srv?.let { servers.add(it) } }
            backupDeferreds.awaitAll().forEach { list -> servers.addAll(list) }
        }

        Log.d(TAG, "getServers($id) → ${servers.size} servers (native + backups)")

        // Tri : SendVid VF / VOSTFR en premier (extracteur fiable), puis fallbacks
        servers.sortWith(
            compareBy<Video.Server> { srv ->
                when {
                    srv.name.startsWith("SendVid VF") -> 0  // VF natif priorité
                    srv.name.startsWith("SendVid VOSTFR") -> 1
                    srv.name.contains("VF", ignoreCase = true) -> 2  // VF backup
                    else -> 3  // VOSTFR backup
                }
            }.thenByDescending { srv ->
                when {
                    srv.name.contains("SendVid", ignoreCase = true) -> 3
                    srv.name.contains("MyVi", ignoreCase = true) -> 2
                    srv.name.contains("SbFull", ignoreCase = true) -> 1
                    else -> 0
                }
            }
        )

        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // Si le server provient d'un backup provider (id contient "::bk::"),
        // délègue au getVideo du provider source pour conserver leur logique
        // d'extraction custom (refs, tokens, headers spéciaux, etc.).
        if (server.id.contains("::bk::")) {
            val (providerName, originalId) = server.id.substringAfter("::bk::").let {
                val sep = it.indexOf('|')
                if (sep > 0) it.substring(0, sep) to it.substring(sep + 1)
                else it to server.src
            }
            val backup = when (providerName) {
                "AnimeSama" -> AnimeSamaProvider
                "FrenchAnime" -> FrenchAnimeProvider
                "VoirAnime" -> VoirAnimeProvider
                "FrenchManga" -> FrenchMangaProvider
                else -> null
            }
            if (backup != null) {
                val srcServer = Video.Server(id = originalId, name = server.name, src = server.src)
                return backup.getVideo(srcServer)
            }
        }
        // SendVid natif → délégation à l'extractor SendvidExtractor.
        return Extractor.extract(server.src, server)
    }

    /**
     * Cherche le même anime/épisode sur un provider backup et retourne ses serveurs
     * avec un préfixe identifiant la source. Best-effort : échec silencieux si pas
     * trouvé ou si le provider est lent/down.
     */
    private suspend fun findBackupServers(
        provider: Provider,
        title: String,
        seasonNum: Int,
        episodeNum: Int,
    ): List<Video.Server> {
        return try {
            // Search by title
            val searchResults = withContext(Dispatchers.IO) { provider.search(title, 1) }
            val match = searchResults.firstNotNullOfOrNull { item ->
                when (item) {
                    is TvShow -> item
                    else -> null
                }
            } ?: return emptyList()

            // Get full TV show (seasons)
            val show = withContext(Dispatchers.IO) { provider.getTvShow(match.id) }
            val season = show.seasons.firstOrNull { it.number == seasonNum }
                ?: show.seasons.firstOrNull()
                ?: return emptyList()

            // Find matching episode
            val episodes = withContext(Dispatchers.IO) { provider.getEpisodesBySeason(season.id) }
            val episode = episodes.firstOrNull { it.number == episodeNum }
                ?: return emptyList()

            // Get servers for that episode
            val servers = withContext(Dispatchers.IO) {
                provider.getServers(
                    episode.id,
                    Video.Type.Episode(
                        id = episode.id,
                        number = episode.number,
                        title = episode.title,
                        poster = episode.poster,
                        overview = null,
                        tvShow = Video.Type.Episode.TvShow(
                            id = show.id,
                            title = show.title,
                            poster = show.poster,
                            banner = show.banner,
                            releaseDate = null,
                            imdbId = null,
                        ),
                        season = Video.Type.Episode.Season(
                            number = season.number,
                            title = season.title ?: "Saison ${season.number}",
                        ),
                    )
                )
            }
            Log.d(TAG, "findBackupServers(${provider.name}, $title S${seasonNum}E${episodeNum}): ${servers.size} servers")

            // Prefix server names with the source provider and tag id with ::bk::
            servers.map { srv ->
                Video.Server(
                    id = "::bk::${provider.name}|${srv.id}",
                    name = "${srv.name} [${provider.name}]",
                    src = srv.src,
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "findBackupServers(${provider.name}): failed: ${e.message}")
            emptyList()
        }
    }
}
