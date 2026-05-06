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
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object MovixProvider : Provider, ProviderConfigUrl, ProviderPortalUrl {

    override val name = "Movix"
    override val defaultBaseUrl: String = "https://api.movix.cash/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { field }
        }
    override val defaultPortalUrl: String = "https://movix.cash/"
    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty { field }
        }
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_movix"
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private const val AUTO_UPDATE_URL = "https://movix.health/"

    private lateinit var movixServiceInstance: MovixService
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    private const val TMDB_API_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
    private const val TMDB_BASE_URL = "https://api.themoviedb.org/3/"
    private const val TMDB_IMG_W500 = "https://image.tmdb.org/t/p/w500"
    private const val TMDB_IMG_ORIGINAL = "https://image.tmdb.org/t/p/original"

    /**
     * Formate le nom de la langue pour l'affichage.
     * Ex: "vf" -> "VF", "vostfr" -> "VOSTFR", "vo" -> "VO"
     */
    private fun formatLang(lang: String): String {
        val l = lang.trim().lowercase()
        return when {
            l == "vf" || l == "french" || l == "français" -> "VF"
            l == "vostfr" || l == "vost" -> "VOSTFR"
            l == "vo" || l == "original" -> "VO"
            l == "multi" -> "Multi"
            else -> lang.uppercase()
        }
    }

    // ── 2026-05-04 : Circuit breaker par endpoint ────────────────────────
    // Movix expose 6+ sub-APIs (fstream, links, wiflix, cpasmal, tmdb, drama,
    // purstream...). Plusieurs renvoient 404 systématiquement sur les vieux
    // contenus (cpasmal sur séries pré-2010 par ex.) et nous font perdre des
    // secondes à chaque chargement. On garde l'appel "au cas où" mais avec un
    // timeout strict + désactivation temporaire après N timeouts/erreurs.
    private const val ENDPOINT_TIMEOUT_MS = 4_000L
    private const val ENDPOINT_FAILURE_THRESHOLD = 5
    private const val ENDPOINT_DISABLED_MS = 30L * 60L * 1000L // 30 min
    private data class EndpointHealth(
        var consecutiveFailures: Int = 0,
        var disabledUntilMs: Long = 0L,
    )
    private val endpointHealth = ConcurrentHashMap<String, EndpointHealth>()

    /** Wrap d'appel endpoint Movix : timeout 4s + circuit breaker.
     *  Si ça timeout ou throw, on retourne emptyList(). 5 fails consécutifs
     *  → endpoint désactivé 30 min (skip immédiat). 1 succès → reset le compteur. */
    private suspend inline fun <T : List<Video.Server>> runEndpoint(
        endpointName: String,
        crossinline block: suspend () -> T,
    ): List<Video.Server> {
        val health = endpointHealth.getOrPut(endpointName) { EndpointHealth() }
        val now = System.currentTimeMillis()
        if (now < health.disabledUntilMs) {
            return emptyList()
        }
        val result = try {
            withTimeoutOrNull(ENDPOINT_TIMEOUT_MS) { block() }
        } catch (e: Exception) {
            Log.w("MovixProvider", "Endpoint '$endpointName' threw: ${e.message}")
            null
        }
        synchronized(health) {
            if (result.isNullOrEmpty()) {
                health.consecutiveFailures++
                if (health.consecutiveFailures >= ENDPOINT_FAILURE_THRESHOLD) {
                    health.disabledUntilMs = System.currentTimeMillis() + ENDPOINT_DISABLED_MS
                    Log.w("MovixProvider", "Endpoint '$endpointName' disabled for 30min after ${health.consecutiveFailures} consecutive failures")
                    health.consecutiveFailures = 0
                }
            } else {
                health.consecutiveFailures = 0
            }
        }
        return result ?: emptyList()
    }

    // ── 2026-05-04 : Cache du résultat agrégé getServers ──────────────────
    // L'utilisateur ferme le player et y revient -> on refaisait 6 appels API
    // + l'assemblage à chaque fois (1-3s). Maintenant on cache le résultat
    // par clé (tmdbId, season, episode) pendant 5 min.
    // (Le cache d'EXTRACTION m3u8 est séparé, géré dans Extractor.kt avec
    // sa propre TTL — plus court car les m3u8 expirent vite.)
    private const val SERVERS_CACHE_TTL_MS = 5L * 60L * 1000L
    private data class CachedServers(val servers: List<Video.Server>, val expiresAtMs: Long)
    private val serversCache = ConcurrentHashMap<String, CachedServers>()

    private fun serversCacheKey(videoType: Video.Type): String = when (videoType) {
        is Video.Type.Movie -> "movie:${videoType.id}"
        is Video.Type.Episode -> "tv:${videoType.tvShow.id}:s${videoType.season.number}:e${videoType.number}"
    }

    private val tmdbService: TmdbService by lazy { buildTmdbService() }

    // ── 2026-05-04 : TMDB-iframe backups en QUEUE de liste ───────────────
    // Movix a déjà beaucoup de sources natives FR (fstream, wiflix, cpasmal,
    // etc.) qui marchent très bien. On ajoute ces 5 backups TMDB-iframe à la
    // FIN du sélecteur de servers — ils sont là "au cas où" un film/série
    // n'a aucune source native FR. Ordre = sources clean d'abord, vidsrc.to
    // (CF-prone) en dernier.
    private data class TmdbBackupSource(
        val key: String,
        val displayName: String,
        val movieUrl: (tmdbId: String) -> String,
        val tvUrl: (tmdbId: String, season: Int, episode: Int) -> String,
    )

    private val tmdbBackupSources: List<TmdbBackupSource> = listOf(
        TmdbBackupSource(
            key = "vidsrc-icu",
            displayName = "Vidsrc.icu",
            movieUrl = { id -> "https://vidsrc.icu/embed/movie/$id" },
            tvUrl = { id, s, e -> "https://vidsrc.icu/embed/tv/$id/$s/$e" },
        ),
        TmdbBackupSource(
            key = "2embed-skin",
            displayName = "2Embed",
            movieUrl = { id -> "https://2embed.skin/embed/tmdb-movie-$id" },
            tvUrl = { id, s, e -> "https://2embed.skin/embed/tmdb-tv-$id&s=$s&e=$e" },
        ),
        TmdbBackupSource(
            key = "nontongo",
            displayName = "Nontongo",
            movieUrl = { id -> "https://nontongo.win/embed/movie/$id" },
            tvUrl = { id, s, e -> "https://nontongo.win/embed/tv/$id/$s/$e" },
        ),
        TmdbBackupSource(
            key = "111movies",
            displayName = "111Movies",
            movieUrl = { id -> "https://111movies.net/movie/$id" },
            tvUrl = { id, s, e -> "https://111movies.net/tv/$id/$s/$e" },
        ),
        TmdbBackupSource(
            key = "vidsrc-to",
            displayName = "Vidsrc.to",
            movieUrl = { id -> "https://vidsrc.to/embed/movie/$id" },
            tvUrl = { id, s, e -> "https://vidsrc.to/embed/tv/$id/$s/$e" },
        ),
    )

    /** Construit la liste des Video.Server backups TMDB-iframe pour Movix.
     *  Identique à NakiosProvider mais avec son propre wiring local
     *  (paramètres season/episode en Int car Movix Episode VideoType les a
     *  déjà parsés). */
    private fun buildTmdbBackupServersForMovix(
        tmdbId: String,
        videoType: Video.Type,
    ): List<Video.Server> {
        if (!tmdbId.all { it.isDigit() }) return emptyList()
        return when (videoType) {
            is Video.Type.Movie -> tmdbBackupSources.map { source ->
                Video.Server(
                    id = "${source.key}_movix_movie_$tmdbId",
                    name = source.displayName,
                    src = source.movieUrl(tmdbId),
                )
            }
            is Video.Type.Episode -> {
                val s = videoType.season.number
                val e = videoType.number
                tmdbBackupSources.map { source ->
                    Video.Server(
                        id = "${source.key}_movix_tv_${tmdbId}_${s}_$e",
                        name = source.displayName,
                        src = source.tvUrl(tmdbId, s, e),
                    )
                }
            }
        }
    }

    // ── 2026-05-04 : Yflix.to backup source ──────────────────────────────
    // yflix.to est un aggregator FR (UI traduite via Google Translate côté
    // browser, pas de Cloudflare challenge bloquant). On l'utilise en
    // SOURCE COMPLEMENTAIRE quand Movix natif n'a rien — ou en plus pour
    // augmenter le nombre de servers dispos.
    //
    // Workflow :
    //   1. TMDB id → title+year (déjà fait par tmdbService pour le home)
    //   2. /ajax/film/search?keyword={title} → JSON avec HTML <a class="item">
    //   3. Match best result par (title, year, type)
    //   4. Build Video.Server avec src = yflix.to/watch/{slug}.{id}#ep=S,E
    //   5. YflixExtractor (WebView) intercepte le m3u8 final
    private const val YFLIX_BASE = "https://yflix.to/"

    /** Cherche un titre sur yflix.to via l'AJAX search public.
     *  Retourne le path /watch/{slug}.{id} du meilleur match, ou null si rien.
     *  Match heuristique : titre (case-insensitive contains) + année (±1) si fournie.
     *  Type filter : "Movie" ou "TV" pour préférer le bon kind. */
    private suspend fun searchYflix(
        title: String,
        year: Int? = null,
        preferType: String = "Movie",
    ): String? {
        if (title.isBlank()) return null
        // 2026-05-05 : normalise les apostrophes typographiques (U+2019 → ') avant URL-encoding
        // sinon le keyword devient %E2%80%99 et Yflix renvoie 0 résultats.
        val cleanTitle = com.streamflixreborn.streamflix.utils.TitleNormalizer.stripUnicodeArtifacts(title)
        return try {
            val url = "${YFLIX_BASE}ajax/film/search?keyword=" +
                java.net.URLEncoder.encode(cleanTitle, "UTF-8")
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", YFLIX_BASE)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build()
            val client = Extractor.sharedClient.newBuilder()
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val body = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.body?.string() }
            } ?: return null
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            val resultObj = json.getAsJsonObject("result") ?: return null
            val html = resultObj.get("html")?.asString ?: return null
            // Parse les <a class="item" href="/watch/{slug}.{id}"> avec leurs metadata
            val doc = org.jsoup.Jsoup.parse(html)
            val items = doc.select("a.item").mapNotNull { item ->
                val href = item.attr("href").takeIf { it.startsWith("/watch/") } ?: return@mapNotNull null
                val itemTitle = item.selectFirst("div.title")?.text()?.trim() ?: return@mapNotNull null
                val metadata = item.select("div.metadata span").map { it.text().trim() }
                val itemType = metadata.getOrNull(0) ?: "" // "Movie" ou "TV"
                val itemYear = metadata.getOrNull(1)?.takeIf { it.matches(Regex("\\d{4}")) }?.toIntOrNull()
                Triple(href, itemTitle, Pair(itemType, itemYear))
            }
            if (items.isEmpty()) {
                Log.d("MovixProvider", "Yflix search '$title' : no results")
                return null
            }
            // 2026-05-05 v2 : matching strict (cf searchMoiflix) — pas de
            // substring lâche. Exige match exact OU word-for-word + longueur
            // similaire pour éviter "The Boys" → "The Boyfriend".
            val targetTitleNorm = title.lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val targetWords = targetTitleNorm.split(" ").filter { it.length > 1 }.toSet()
            val scored = items.map { (href, t, meta) ->
                val (itype, iyear) = meta
                val tNorm = t.lowercase()
                    .replace(Regex("[^a-z0-9 ]"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                val candidateWords = tNorm.split(" ").filter { it.length > 1 }.toSet()
                val lenDiffPct = if (kotlin.math.max(tNorm.length, targetTitleNorm.length) > 0)
                    kotlin.math.abs(tNorm.length - targetTitleNorm.length).toDouble() /
                        kotlin.math.max(tNorm.length, targetTitleNorm.length)
                else 0.0
                var score = when {
                    tNorm == targetTitleNorm -> 100
                    targetWords.isNotEmpty() && candidateWords.containsAll(targetWords) && lenDiffPct <= 0.30 -> 90
                    candidateWords.isNotEmpty() && targetWords.containsAll(candidateWords) && lenDiffPct <= 0.30 -> 80
                    else -> 0
                }
                if (year != null && iyear != null && Math.abs(iyear - year) <= 1) score += 30
                if (itype == preferType) score += 10
                Triple(href, score, "$t ($itype, ${iyear ?: "?"})")
            }
            val best = scored.maxByOrNull { it.second }
            // Seuil 90 (était 50) — match strict requis.
            if (best == null || best.second < 90) {
                Log.d("MovixProvider", "Yflix '$title' : pas de match fiable (best=${best?.third} score=${best?.second}), skip")
                return null
            }
            Log.d("MovixProvider", "Yflix '$title' → ${best.third} score=${best.second}")
            best.first // /watch/{slug}.{id}
        } catch (e: Exception) {
            Log.w("MovixProvider", "Yflix search '$title' failed: ${e.message}")
            null
        }
    }

    /** Construit un Video.Server pour yflix avec l'URL /watch/...
     *  Pour un épisode, on ajoute le hash #ep=S,E que yflix attend. */
    private fun buildYflixServer(watchPath: String, season: Int? = null, episode: Int? = null): Video.Server {
        val baseUrl = if (watchPath.startsWith("http")) watchPath
            else YFLIX_BASE.trimEnd('/') + watchPath
        val finalUrl = if (season != null && episode != null) {
            "$baseUrl#ep=$season,${episode.toString().padStart(2, '0')}"
        } else baseUrl
        return Video.Server(
            id = "yflix_${watchPath.substringAfterLast("/")}",
            name = "Yflix [VF/Multi]",
            src = finalUrl,
        )
    }

    // ── 2026-05-04 : Moiflix.com backup source ───────────────────────────
    // Aggregator FR avec catalog complet (films + shows). Architecture :
    //   1. Search via JSON public : /ajax/posts?q={title}
    //   2. Match best result par titre + type (Film / Show)
    //   3. URL : /movie/{slug} ou /show/{slug}
    //   4. Episode : /episode/{slug}/season-N-episode-N
    //   5. Player iframe → lecteur1.xtremestream.xyz/player/index.php?...
    //   6. MoiflixExtractor (WebView) intercepte le m3u8
    //
    // Avantages : audio FR garanti (testé), pas de CF challenge, pas de
    // login, search JSON propre. Le bouton "Continuer" sur la page episode
    // est auto-cliqué par MoiflixExtractor.
    private const val MOIFLIX_BASE = "https://moiflix.com/"

    /** Cherche un titre sur moiflix via l'AJAX search public.
     *  Retourne le path /movie/{slug} ou /show/{slug} du meilleur match, ou null. */
    private suspend fun searchMoiflix(
        title: String,
        year: Int? = null,
        preferType: String = "Film",
    ): String? {
        if (title.isBlank()) return null
        // 2026-05-05 : pareil que Yflix : Moiflix foire sur les apostrophes typographiques.
        val cleanTitle = com.streamflixreborn.streamflix.utils.TitleNormalizer.stripUnicodeArtifacts(title)
        return try {
            val url = "${MOIFLIX_BASE}ajax/posts?q=" +
                java.net.URLEncoder.encode(cleanTitle, "UTF-8")
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", MOIFLIX_BASE)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build()
            val client = Extractor.sharedClient.newBuilder()
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val body = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.body?.string() }
            } ?: return null
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            val items = json.getAsJsonArray("data") ?: return null
            if (items.size() == 0) {
                Log.d("MovixProvider", "Moiflix search '$title' : no results")
                return null
            }
            // 2026-05-05 v2 : matching strict pour éviter les faux positifs.
            // Avant : "The Boys" pouvait matcher "The Boyfriend" via substring
            // (score 50). Maintenant on exige soit un match exact, soit un
            // match WORD-FOR-WORD (tous les mots du target présents dans le
            // candidat + différence de longueur ≤ 30%).
            val targetTitleNorm = title.lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val targetWords = targetTitleNorm.split(" ").filter { it.length > 1 }.toSet()
            var bestUrl: String? = null
            var bestScore = -1
            var bestName: String? = null
            for (i in 0 until items.size()) {
                val item = items[i].asJsonObject
                val itemName = item.get("name")?.asString ?: continue
                val itemUrl = item.get("url")?.asString ?: continue
                val itemType = item.get("type")?.asString ?: ""
                val nNorm = itemName.lowercase()
                    .replace(Regex("[^a-z0-9 ]"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                val candidateWords = nNorm.split(" ").filter { it.length > 1 }.toSet()
                var score = 0
                when {
                    // 1. Match exact (insensible à la casse/ponctuation)
                    nNorm == targetTitleNorm -> score = 100
                    // 2. Tous les mots du target sont dans le candidat ET
                    //    longueur similaire (≤ 30% de différence) → fort match
                    targetWords.isNotEmpty() && candidateWords.containsAll(targetWords) &&
                        kotlin.math.abs(nNorm.length - targetTitleNorm.length).toDouble() /
                        kotlin.math.max(nNorm.length, targetTitleNorm.length) <= 0.30 -> score = 90
                    // 3. Tous les mots du candidat dans target ET longueur similaire
                    candidateWords.isNotEmpty() && targetWords.containsAll(candidateWords) &&
                        kotlin.math.abs(nNorm.length - targetTitleNorm.length).toDouble() /
                        kotlin.math.max(nNorm.length, targetTitleNorm.length) <= 0.30 -> score = 80
                    // Sinon : pas un match valide. Pas de fallback substring.
                    else -> score = 0
                }
                if (itemType == preferType) score += 20
                if (score > bestScore) {
                    bestScore = score
                    bestUrl = itemUrl
                    bestName = itemName
                }
            }
            // Seuil 90 (au lieu de 50) — on exige un VRAI match.
            if (bestUrl == null || bestScore < 90) {
                Log.d("MovixProvider", "Moiflix '$title' : pas de match fiable (best='$bestName' score=$bestScore), skip")
                return null
            }
            Log.d("MovixProvider", "Moiflix '$title' → '$bestName' $bestUrl (score=$bestScore)")
            bestUrl
        } catch (e: Exception) {
            Log.w("MovixProvider", "Moiflix search '$title' failed: ${e.message}")
            null
        }
    }

    /** Construit l'URL complète moiflix pour un movie ou episode.
     *  movie : /movie/{slug}
     *  episode : /episode/{slug}/season-N-episode-N (slug récupéré depuis /show/{slug}) */
    private fun buildMoiflixServer(matchUrl: String, season: Int? = null, episode: Int? = null): Video.Server {
        val finalUrl = if (season != null && episode != null) {
            // matchUrl = "/show/{slug}" → on transforme en "/episode/{slug}/season-N-episode-N"
            val slug = matchUrl.substringAfterLast("/")
            "${MOIFLIX_BASE.trimEnd('/')}/episode/$slug/season-$season-episode-$episode"
        } else {
            "${MOIFLIX_BASE.trimEnd('/')}$matchUrl"
        }
        return Video.Server(
            id = "moiflix_${matchUrl.substringAfterLast("/")}",
            name = "Moiflix [VF]",
            src = finalUrl,
        )
    }

    private val genreNames = mapOf(
        "28" to "Action", "12" to "Aventure", "16" to "Animation",
        "35" to "Comédie", "80" to "Crime", "99" to "Documentaire",
        "18" to "Drame", "10751" to "Famille", "14" to "Fantaisie",
        "27" to "Horreur", "9648" to "Mystère", "10749" to "Romance",
        "878" to "Science-Fiction", "53" to "Thriller", "10752" to "Guerre",
        "37" to "Western"
    )

    // ==================== DATA CLASSES ====================

    // --- Movix API responses ---

    /**
     * 2026-05-03 : l'API Movix /api/search retourne {"results":[...], ...} pas
     * une liste directe. Le retour Retrofit était typé List<MovixSearchItem>
     * → "Expected BEGIN_ARRAY but was BEGIN_OBJECT" au root → searchMovix
     * throw → seriesDlDeferred toujours vide → on perd les m3u8 Darkibox HLS
     * que Movix sert via api/series/download/{movixId}/season/X/episode/Y.
     */
    data class MovixSearchResponse(
        val results: List<MovixSearchItem>?
    )

    data class MovixSearchItem(
        val id: Int?,
        val name: String?,
        val type: String?,
        val tmdb_id: Int?,
        val poster: String?,
        val backdrop: String?,
        val description: String?,
        val release_date: String?,
        val imdb_id: String?
    )

    data class FstreamPlayer(
        val url: String?,
        val type: String?,
        val quality: String?,
        val player: String?
    )

    data class FstreamMovieResponse(
        val success: Boolean?,
        val players: Map<String, List<FstreamPlayer>>?,
        val error: String?,
        val message: String?
    )

    data class FstreamTvEpisode(
        val number: Int?,
        val title: String?,
        val languages: Map<String, List<FstreamPlayer>>?
    )

    data class FstreamTvResponse(
        val success: Boolean?,
        val episodes: Map<String, FstreamTvEpisode>?
    )

    data class LinksMovieData(
        val id: String?,
        val links: List<String>?
    )

    data class LinksMovieResponse(
        val success: Boolean?,
        val data: LinksMovieData?,
        val error: String?,
        val message: String?
    )

    data class LinksTvItem(
        val series_id: String?,
        val season_number: Int?,
        val episode_number: Int?,
        val links: List<String>?
    )

    data class LinksTvResponse(
        val success: Boolean?,
        val data: List<LinksTvItem>?
    )

    data class WiflixEpisodeSource(
        val name: String?,
        val url: String?,
        val episode: Int?,
        val type: String?
    )

    data class WiflixTvResponse(
        val success: Boolean?,
        val episodes: Map<String, Map<String, List<WiflixEpisodeSource>>>?
    )

    data class WiflixMovieResponse(
        val success: Boolean?,
        val players: Map<String, List<WiflixEpisodeSource>>?,
        val error: String?,
        val message: String?
    )

    // --- Cpasmal API responses ---

    data class CpasmalLink(
        val server: String?,
        val url: String?
    )

    data class CpasmalResponse(
        val title: String?,
        val links: Map<String, List<CpasmalLink>>?
    )

    data class CpasmalMovieResponse(
        val title: String?,
        val links: Map<String, List<CpasmalLink>>?
    )

    // --- Series Download API responses ---

    data class SeriesDownloadSource(
        val src: String?,
        val language: String?,
        val quality: String?,
        val m3u8: String?
    )

    data class SeriesDownloadResponse(
        val sources: List<SeriesDownloadSource>?
    )

    // --- TMDB Movix API responses ---

    data class TmdbMovixPlayerLink(
        val decoded_url: String?,
        val quality: String?,
        val language: String?
    )

    data class TmdbMovixEpisode(
        val season_number: Int?,
        val episode_number: Int?,
        val title: String?,
        val iframe_src: String?,
        val player_links: List<TmdbMovixPlayerLink>?
    )

    data class TmdbMovixTvResponse(
        val current_episode: TmdbMovixEpisode?,
        val seasons: List<Any>?
    )

    data class TmdbMovixMovieResponse(
        val iframe_src: String?,
        val player_links: List<TmdbMovixPlayerLink>?
    )

    // --- TMDB API responses ---

    data class TmdbMovieResult(
        val id: Int,
        val title: String?,
        val overview: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val release_date: String?,
        val vote_average: Double?,
        val runtime: Int?,
        val imdb_id: String?,
        val genres: List<TmdbGenre>?,
        val credits: TmdbCredits?
    )

    data class TmdbTvResult(
        val id: Int,
        val name: String?,
        val overview: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val first_air_date: String?,
        val vote_average: Double?,
        val number_of_seasons: Int?,
        val seasons: List<TmdbSeason>?,
        val genres: List<TmdbGenre>?,
        val credits: TmdbCredits?,
        val external_ids: TmdbExternalIds?
    )

    data class TmdbSeason(
        val id: Int?,
        val season_number: Int?,
        val name: String?,
        val poster_path: String?,
        val episode_count: Int?,
        val air_date: String?
    )

    data class TmdbSeasonDetail(
        val id: Int?,
        val season_number: Int?,
        val name: String?,
        val episodes: List<TmdbEpisode>?
    )

    data class TmdbEpisode(
        val id: Int?,
        val episode_number: Int?,
        val name: String?,
        val overview: String?,
        val still_path: String?,
        val air_date: String?
    )

    data class TmdbGenre(
        val id: Int?,
        val name: String?
    )

    data class TmdbCredits(
        val cast: List<TmdbCast>?,
        val crew: List<TmdbCrew>?
    )

    data class TmdbCast(
        val id: Int?,
        val name: String?,
        val profile_path: String?,
        val character: String?
    )

    data class TmdbCrew(
        val id: Int?,
        val name: String?,
        val profile_path: String?,
        val job: String?
    )

    data class TmdbExternalIds(
        val imdb_id: String?
    )

    data class TmdbPageResult<T>(
        val page: Int?,
        val results: List<T>?,
        val total_pages: Int?
    )

    data class TmdbMovieListItem(
        val id: Int,
        val title: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val release_date: String?,
        val vote_average: Double?,
        val overview: String?
    )

    data class TmdbTvListItem(
        val id: Int,
        val name: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val first_air_date: String?,
        val vote_average: Double?,
        val overview: String?
    )

    data class TmdbTrendingItem(
        val id: Int,
        val title: String?,
        val name: String?,
        val media_type: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val release_date: String?,
        val first_air_date: String?,
        val vote_average: Double?,
        val overview: String?
    )

    // ==================== INITIALIZATION ====================

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                try {
                    val activeDomain = fetchActiveDomain()
                    if (!activeDomain.isNullOrEmpty()) {
                        val portalBase = if (activeDomain.endsWith("/")) activeDomain else "$activeDomain/"
                        val apiBase = portalBase.replace("://", "://api.")

                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, apiBase)
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL, portalBase)
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_LOGO, "${portalBase}movix.png")
                        Log.d("MovixProvider", "Auto-update: active domain -> $portalBase (API: $apiBase)")
                    }
                } catch (e: Exception) {
                    Log.e("MovixProvider", "Auto-update failed: ${e.message}")
                }
            }
            movixServiceInstance = buildMovixService()
            serviceInitialized = true
        }
        return baseUrl
    }

    /**
     * Fetches the active Movix domain from movix.health.
     *
     * movix.health is a React SPA that embeds domain data in its JS bundle.
     * The domains are stored as an array of objects with properties:
     *   id, label, url, blocked (boolean), blockedReason (optional)
     *
     * Strategy:
     * 1. Fetch the HTML page to find the JS bundle filename
     * 2. Fetch the JS bundle
     * 3. Regex for the first domain entry with blocked:!1 (= not blocked)
     */
    private fun fetchActiveDomain(): String? {
        val client = OkHttpClient.Builder()
            .readTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()

        // Step 1: Fetch the HTML to find the JS bundle path
        val htmlRequest = Request.Builder()
            .url(AUTO_UPDATE_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val html = client.newCall(htmlRequest).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }

        // Extract JS bundle path: <script ... src="/assets/index-XXXX.js">
        val jsPath = Regex("""src="(/assets/index-[^"]+\.js)"""")
            .find(html)?.groupValues?.get(1)
            ?: return null

        val jsUrl = AUTO_UPDATE_URL.trimEnd('/') + jsPath

        // Step 2: Fetch the JS bundle
        val jsRequest = Request.Builder()
            .url(jsUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val jsContent = client.newCall(jsRequest).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }

        // Step 3: Find the first domain with blocked:!1 (not blocked)
        // Format: url:'https://movix.XXXX',blocked:!1
        val activeDomainUrl = Regex("""url:'(https://movix\.[a-z]+)',blocked:!1""")
            .find(jsContent)?.groupValues?.get(1)

        return activeDomainUrl
    }

    private suspend fun initializeService() {
        initializationMutex.withLock {
            if (serviceInitialized) return
            onChangeUrl()
        }
    }

    // ==================== PROVIDER METHODS ====================

    override suspend fun getHome(): List<Category> {
        initializeService()
        val categories = mutableListOf<Category>()

        try {
            // Featured - trending
            val trending = tmdbService.getTrending(apiKey = TMDB_API_KEY)
            val featuredItems = trending.results?.take(10)?.mapNotNull { item ->
                when (item.media_type) {
                    "movie" -> Movie(
                        id = item.id.toString(),
                        title = item.title ?: item.name ?: "",
                        poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                        banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                        rating = item.vote_average,
                        overview = item.overview,
                        released = item.release_date
                    )
                    "tv" -> TvShow(
                        id = item.id.toString(),
                        title = item.name ?: item.title ?: "",
                        poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                        banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                        rating = item.vote_average,
                        overview = item.overview,
                        released = item.first_air_date
                    )
                    else -> null
                }
            } ?: emptyList()
            if (featuredItems.isNotEmpty()) {
                categories.add(Category(name = Category.FEATURED, list = featuredItems))
            }

            // Séries récentes en premier
            val popularTv = tmdbService.getPopularTvShows(apiKey = TMDB_API_KEY)
            val tvItems = popularTv.results?.map { item ->
                TvShow(
                    id = item.id.toString(),
                    title = item.name ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.first_air_date
                )
            } ?: emptyList()
            if (tvItems.isNotEmpty()) {
                categories.add(Category(name = "Séries populaires", list = tvItems))
            }

            // Top rated TV
            val topTv = tmdbService.getTopRatedTvShows(apiKey = TMDB_API_KEY)
            val topTvItems = topTv.results?.map { item ->
                TvShow(
                    id = item.id.toString(),
                    title = item.name ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.first_air_date
                )
            } ?: emptyList()
            if (topTvItems.isNotEmpty()) {
                categories.add(Category(name = "Séries les mieux notées", list = topTvItems))
            }

            // Puis les films
            val popularMovies = tmdbService.getPopularMovies(apiKey = TMDB_API_KEY)
            val movieItems = popularMovies.results?.map { item ->
                Movie(
                    id = item.id.toString(),
                    title = item.title ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.release_date
                )
            } ?: emptyList()
            if (movieItems.isNotEmpty()) {
                categories.add(Category(name = "Films populaires", list = movieItems))
            }

            // Top rated movies
            val topMovies = tmdbService.getTopRatedMovies(apiKey = TMDB_API_KEY)
            val topMovieItems = topMovies.results?.map { item ->
                Movie(
                    id = item.id.toString(),
                    title = item.title ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.release_date
                )
            } ?: emptyList()
            if (topMovieItems.isNotEmpty()) {
                categories.add(Category(name = "Films les mieux notés", list = topMovieItems))
            }

        } catch (e: Exception) {
            Log.e("MovixProvider", "Error loading home: ${e.message}")
        }

        // Reorder: 1.FEATURED 2.Épisodes/récents 3.Séries récentes 4.Films récents 5.Séries 6.Films
        return categories.sortedWith(compareBy { cat ->
            val n = cat.name.lowercase()
            val isRecent = n.contains("récen") || n.contains("nouveau") || n.contains("nouvelle") || n.contains("derni") || n.contains("ajouté")
            val isSeries = n.contains("séri") || n.contains("seri") || n.contains("saison") || n.contains("tv")
            val isFilm = n.contains("film") || n.contains("movie") || n.contains("cinéma")
            when {
                cat.name == Category.FEATURED -> 0
                n.contains("épisode") || n.contains("episode") -> 1
                isRecent && isSeries -> 2
                isRecent && isFilm -> 3
                isSeries -> 4
                isFilm -> 5
                isRecent -> 1
                else -> 6
            }
        })
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            if (page > 1) return emptyList()
            return listOf(
                Genre(id = "28", name = "Action"),
                Genre(id = "12", name = "Aventure"),
                Genre(id = "16", name = "Animation"),
                Genre(id = "35", name = "Comédie"),
                Genre(id = "80", name = "Crime"),
                Genre(id = "99", name = "Documentaire"),
                Genre(id = "18", name = "Drame"),
                Genre(id = "10751", name = "Famille"),
                Genre(id = "14", name = "Fantaisie"),
                Genre(id = "27", name = "Horreur"),
                Genre(id = "k-drama", name = "K-Drama"),
                Genre(id = "9648", name = "Mystère"),
                Genre(id = "10749", name = "Romance"),
                Genre(id = "878", name = "Science-Fiction"),
                Genre(id = "53", name = "Thriller"),
                Genre(id = "10752", name = "Guerre"),
                Genre(id = "37", name = "Western")
            )
        }

        return try {
            // 2026-05-05 : normalise la query (apostrophes typo, annotations parasites)
            val cleanQuery = com.streamflixreborn.streamflix.utils.TitleNormalizer
                .cleanForTmdbSearch(query).ifBlank { query }
            val tmdbResults = TMDb3.Search.multi(cleanQuery, language = "fr-FR", page = page)
            tmdbResults.results.mapNotNull { item ->
                when (item) {
                    is TMDb3.Movie -> Movie(
                        id = item.id.toString(),
                        title = item.title,
                        poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                        banner = item.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
                        overview = item.overview,
                        released = item.releaseDate
                    )
                    is TMDb3.Tv -> TvShow(
                        id = item.id.toString(),
                        title = item.name,
                        poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                        banner = item.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
                        overview = item.overview,
                        released = item.firstAirDate
                    )
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.e("MovixProvider", "TMDB search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val result = tmdbService.discoverMovies(apiKey = TMDB_API_KEY, page = page)
            result.results?.map { item ->
                Movie(
                    id = item.id.toString(),
                    title = item.title ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.release_date
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("MovixProvider", "getMovies error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val result = tmdbService.discoverTvShows(apiKey = TMDB_API_KEY, page = page)
            result.results?.map { item ->
                TvShow(
                    id = item.id.toString(),
                    title = item.name ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.first_air_date
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("MovixProvider", "getTvShows error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val tmdb = tmdbService.getMovieDetails(
            id = id.toInt(),
            apiKey = TMDB_API_KEY,
            appendToResponse = "credits"
        )

        val directors = tmdb.credits?.crew
            ?.filter { it.job == "Director" }
            ?.map { People(id = it.id.toString(), name = it.name ?: "", image = it.profile_path?.let { p -> "$TMDB_IMG_W500$p" }) }
            ?: emptyList()

        val cast = tmdb.credits?.cast?.take(20)?.map {
            People(
                id = it.id.toString(),
                name = it.name ?: "",
                image = it.profile_path?.let { p -> "$TMDB_IMG_W500$p" }
            )
        } ?: emptyList()

        val genres = tmdb.genres?.map {
            Genre(id = it.id.toString(), name = it.name ?: "")
        } ?: emptyList()

        // Get recommendations
        val recommendations: List<Show> = try {
            val recs = tmdbService.getMovieRecommendations(id = id.toInt(), apiKey = TMDB_API_KEY)
            recs.results?.map { item ->
                Movie(
                    id = item.id.toString(),
                    title = item.title ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average
                )
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }

        return Movie(
            id = tmdb.id.toString(),
            title = tmdb.title ?: "",
            overview = tmdb.overview,
            released = tmdb.release_date,
            runtime = tmdb.runtime,
            rating = tmdb.vote_average,
            poster = tmdb.poster_path?.let { "$TMDB_IMG_W500$it" },
            banner = tmdb.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
            imdbId = tmdb.imdb_id,
            genres = genres,
            directors = directors,
            cast = cast,
            recommendations = recommendations
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val tmdb = tmdbService.getTvDetails(
            id = id.toInt(),
            apiKey = TMDB_API_KEY,
            appendToResponse = "credits,external_ids"
        )

        val seasons = tmdb.seasons
            ?.filter { (it.season_number ?: 0) > 0 }
            ?.map { s ->
                Season(
                    id = "$id/${s.season_number}",
                    number = s.season_number ?: 0,
                    title = s.name ?: "Saison ${s.season_number}",
                    poster = s.poster_path?.let { "$TMDB_IMG_W500$it" }
                )
            } ?: emptyList()

        val cast = tmdb.credits?.cast?.take(20)?.map {
            People(
                id = it.id.toString(),
                name = it.name ?: "",
                image = it.profile_path?.let { p -> "$TMDB_IMG_W500$p" }
            )
        } ?: emptyList()

        val genres = tmdb.genres?.map {
            Genre(id = it.id.toString(), name = it.name ?: "")
        } ?: emptyList()

        val recommendations: List<Show> = try {
            val recs = tmdbService.getTvRecommendations(id = id.toInt(), apiKey = TMDB_API_KEY)
            recs.results?.map { item ->
                TvShow(
                    id = item.id.toString(),
                    title = item.name ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average
                )
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }

        return TvShow(
            id = tmdb.id.toString(),
            title = tmdb.name ?: "",
            overview = tmdb.overview,
            released = tmdb.first_air_date,
            rating = tmdb.vote_average,
            poster = tmdb.poster_path?.let { "$TMDB_IMG_W500$it" },
            banner = tmdb.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
            imdbId = tmdb.external_ids?.imdb_id,
            seasons = seasons,
            genres = genres,
            cast = cast,
            recommendations = recommendations
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("/")
        if (parts.size < 2) return emptyList()
        val tvShowId = parts[0]
        val seasonNumber = parts[1].toIntOrNull() ?: return emptyList()

        return try {
            val detail = tmdbService.getSeasonDetails(
                tvId = tvShowId.toInt(),
                seasonNumber = seasonNumber,
                apiKey = TMDB_API_KEY
            )
            detail.episodes?.map { ep ->
                Episode(
                    id = "$tvShowId-s${seasonNumber}e${ep.episode_number}",
                    number = ep.episode_number ?: 0,
                    title = ep.name ?: "Épisode ${ep.episode_number}",
                    released = ep.air_date,
                    poster = ep.still_path?.let { "$TMDB_IMG_W500$it" },
                    overview = ep.overview
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("MovixProvider", "getEpisodesBySeason error: ${e.message}")
            emptyList()
        }
    }

    // Genres spéciaux basés sur le pays d'origine (pas un ID TMDB numérique)
    private val specialGenres = mapOf(
        "k-drama" to "KR",
        "drama-coreen" to "KR",
        "K-Drama|" to "KR"
    )

    override suspend fun getGenre(id: String, page: Int): Genre {
        return try {
            val shows = mutableListOf<Show>()
            val originCountry = specialGenres[id]
            val genreFilter = if (originCountry != null) null else id

            // Films du genre (ou du pays d'origine)
            try {
                val movieResult = tmdbService.discoverMovies(
                    apiKey = TMDB_API_KEY,
                    page = page,
                    withGenres = genreFilter,
                    withOriginCountry = originCountry
                )
                movieResult.results?.forEach { item ->
                    shows.add(
                        Movie(
                            id = item.id.toString(),
                            title = item.title ?: "",
                            poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                            banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                            rating = item.vote_average,
                            released = item.release_date
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("MovixProvider", "getGenre movies error: ${e.message}")
            }

            // Séries TV du genre (ou du pays d'origine)
            try {
                val tvResult = tmdbService.discoverTvShows(
                    apiKey = TMDB_API_KEY,
                    page = page,
                    withGenres = genreFilter,
                    withOriginCountry = originCountry
                )
                tvResult.results?.forEach { item ->
                    shows.add(
                        TvShow(
                            id = item.id.toString(),
                            title = item.name ?: "",
                            poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                            banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                            rating = item.vote_average,
                            released = item.first_air_date
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("MovixProvider", "getGenre tvShows error: ${e.message}")
            }

            // Mélanger films et séries par popularité (rating décroissant)
            val sorted = shows.sortedByDescending { show ->
                when (show) {
                    is Movie -> show.rating ?: 0.0
                    is TvShow -> show.rating ?: 0.0
                }
            }

            val genreName = when {
                originCountry == "KR" -> "K-Drama"
                else -> genreNames[id] ?: id
            }
            Genre(id = id, name = genreName, shows = sorted)
        } catch (e: Exception) {
            Genre(id = id, name = genreNames[id] ?: id)
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id = id, name = "")
        return try {
            val person = tmdbService.getPersonDetails(id = id.toInt(), apiKey = TMDB_API_KEY)
            People(
                id = id,
                name = person.name ?: "",
                image = person.profile_path?.let { "$TMDB_IMG_W500$it" },
                biography = person.biography,
                birthday = person.birthday,
                deathday = person.deathday,
                placeOfBirth = person.place_of_birth
            )
        } catch (e: Exception) {
            People(id = id, name = "")
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()

        // 2026-05-04 : cache mémoire de l'agrégat (TTL 5 min)
        val cacheKey = serversCacheKey(videoType)
        val now = System.currentTimeMillis()
        serversCache[cacheKey]?.let { cached ->
            if (now < cached.expiresAtMs) {
                Log.d("MovixProvider", "getServers cache HIT for $cacheKey (${cached.servers.size} servers)")
                return cached.servers
            }
            serversCache.remove(cacheKey)
        }

        val servers = mutableListOf<Video.Server>()

        when (videoType) {
            is Video.Type.Movie -> {
                val tmdbId = id
                Log.d("MovixProvider", "getServers Movie tmdbId=$tmdbId")

                // Parallel fetch all 5 API sources (timeout 4s + circuit breaker)
                val allResults = coroutineScope {
                    val fstreamDeferred = async {
                        runEndpoint("fstream-movie") {
                            val fstream = movixServiceInstance.getFstreamMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            if (fstream.success == true) {
                                fstream.players?.forEach { (lang, players) ->
                                    players.forEach { player ->
                                        val url = player.url ?: return@forEach
                                        if (url.isBlank()) return@forEach
                                        val displayLang = formatLang(lang)
                                        val quality = player.quality?.takeIf { it.isNotBlank() } ?: "HD"
                                        val playerName = player.player?.takeIf { it.isNotBlank() }
                                            ?: guessPlayerName(url)
                                        list.add(Video.Server(id = "fstream-$lang-${list.size}", name = "$playerName ($displayLang - $quality)", src = url))
                                    }
                                }
                            }
                            list
                        }
                    }

                    val linksDeferred = async {
                        runEndpoint("links-movie") {
                            val links = movixServiceInstance.getLinksMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            if (links.success == true) {
                                links.data?.links?.forEachIndexed { index, url ->
                                    if (url.isNotBlank()) {
                                        val playerName = guessPlayerName(url)
                                        list.add(Video.Server(id = "links-$index", name = "$playerName (Link ${index + 1})", src = url))
                                    }
                                }
                            }
                            list
                        }
                    }

                    val wiflixDeferred = async {
                        runEndpoint("wiflix-movie") {
                            val wiflix = movixServiceInstance.getWiflixMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            if (wiflix.success == true) {
                                wiflix.players?.forEach { (lang, sources) ->
                                    val displayLang = formatLang(lang)
                                    sources.forEach { source ->
                                        val url = source.url ?: return@forEach
                                        if (url.isBlank()) return@forEach
                                        val playerName = source.name?.takeIf { it.isNotBlank() } ?: guessPlayerName(url)
                                        list.add(Video.Server(id = "wiflix-$lang-${list.size}", name = "$playerName ($displayLang)", src = url))
                                    }
                                }
                            }
                            list
                        }
                    }

                    val cpasmalDeferred = async {
                        runEndpoint("cpasmal-movie") {
                            val cpasmal = movixServiceInstance.getCpasmalMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            cpasmal.links?.forEach { (lang, links) ->
                                val displayLang = formatLang(lang)
                                links.forEach { link ->
                                    val url = link.url ?: return@forEach
                                    if (url.isBlank()) return@forEach
                                    val playerName = link.server?.replaceFirstChar { it.uppercase() } ?: guessPlayerName(url)
                                    list.add(Video.Server(id = "cpasmal-$lang-${list.size}", name = "$playerName ($displayLang)", src = url))
                                }
                            }
                            list
                        }
                    }

                    val tmdbMovixDeferred = async {
                        runEndpoint("tmdb-movie") {
                            val tmdbMovix = movixServiceInstance.getTmdbMovixMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            tmdbMovix.player_links?.forEach { link ->
                                val url = link.decoded_url ?: return@forEach
                                if (url.isBlank()) return@forEach
                                val lang = if (link.language?.lowercase()?.contains("french") == true) "VF"
                                    else link.language ?: ""
                                val qualityLabel = link.quality?.substringBefore("/")?.trim() ?: "HD"
                                val playerName = guessPlayerName(url)
                                list.add(Video.Server(id = "tmdbmovix-${list.size}", name = "$playerName - $qualityLabel ($lang)", src = url))
                            }
                            list
                        }
                    }

                    val videasyDeferred = async {
                        try {
                            val videasy = com.streamflixreborn.streamflix.extractors.VideasyExtractor()
                            val frServers = videasy.servers(videoType, "fr")
                            // 2026-05-03 : Neon (myflixerzupcloud) renvoie souvent
                            // des sources Netflix random (player de fallback) sur
                            // les contenus mal indexés -> on l'exclut côté Movix
                            // pour eviter les "sources fantômes" pénibles à filtrer.
                            val enServers = videasy.servers(videoType, "en")
                                .filterNot { it.id.startsWith("Neon ") }
                            (frServers + enServers).also {
                                Log.d("MovixProvider", "Videasy movie: ${it.size} servers (Neon filtered)")
                            }
                        } catch (e: Exception) {
                            Log.e("MovixProvider", "Videasy movie error: ${e.message}")
                            emptyList()
                        }
                    }

                    // 2026-05-04 : Yflix.to backup (TMDB id → title+year → search → /watch URL)
                    val yflixDeferred = async {
                        runEndpoint("yflix-movie") {
                            val tmdbIdInt = tmdbId.toIntOrNull() ?: return@runEndpoint emptyList()
                            val movie = try {
                                tmdbService.getMovieDetails(tmdbIdInt, TMDB_API_KEY)
                            } catch (_: Exception) { null }
                            val title = movie?.title ?: return@runEndpoint emptyList()
                            val year = movie.release_date?.take(4)?.toIntOrNull()
                            val watchPath = searchYflix(title, year, "Movie") ?: return@runEndpoint emptyList()
                            listOf(buildYflixServer(watchPath))
                        }
                    }
                    // 2026-05-04 : Moiflix backup (TMDB id → title → search → /movie/{slug})
                    val moiflixDeferred = async {
                        runEndpoint("moiflix-movie") {
                            val tmdbIdInt = tmdbId.toIntOrNull() ?: return@runEndpoint emptyList()
                            val movie = try {
                                tmdbService.getMovieDetails(tmdbIdInt, TMDB_API_KEY)
                            } catch (_: Exception) { null }
                            val title = movie?.title ?: return@runEndpoint emptyList()
                            val year = movie.release_date?.take(4)?.toIntOrNull()
                            val matchUrl = searchMoiflix(title, year, "Film") ?: return@runEndpoint emptyList()
                            listOf(buildMoiflixServer(matchUrl))
                        }
                    }
                    awaitAll(fstreamDeferred, linksDeferred, wiflixDeferred, cpasmalDeferred, tmdbMovixDeferred, videasyDeferred, yflixDeferred, moiflixDeferred)
                }

                allResults.forEach { servers.addAll(it) }
                Log.i("MovixProvider", "Total servers for movie $tmdbId: ${servers.size}")
            }

            is Video.Type.Episode -> {
                val tmdbId = videoType.tvShow.id
                val seasonNum = videoType.season.number
                val episodeNum = videoType.number

                // Parallel fetch all 6 API sources (timeout 4s + circuit breaker)
                val allResults = coroutineScope {
                    val fstreamDeferred = async {
                        runEndpoint("fstream-tv") {
                            val fstream = movixServiceInstance.getFstreamTv(tmdbId, seasonNum)
                            val list = mutableListOf<Video.Server>()
                            fstream.episodes?.get(episodeNum.toString())?.languages?.forEach { (lang, players) ->
                                val displayLang = formatLang(lang)
                                players.forEach { player ->
                                    val url = player.url ?: return@forEach
                                    if (url.isBlank()) return@forEach
                                    val quality = player.quality?.takeIf { it.isNotBlank() } ?: "HD"
                                    val playerName = player.player?.takeIf { it.isNotBlank() } ?: guessPlayerName(url)
                                    list.add(Video.Server(id = "fstream-$lang-${list.size}", name = "$playerName ($displayLang - $quality)", src = url))
                                }
                            }
                            list
                        }
                    }

                    val linksDeferred = async {
                        runEndpoint("links-tv") {
                            val links = movixServiceInstance.getLinksTv(tmdbId, seasonNum, episodeNum)
                            val list = mutableListOf<Video.Server>()
                            links.data?.forEach { item ->
                                item.links?.forEachIndexed { index, url ->
                                    if (url.isNotBlank()) {
                                        val playerName = guessPlayerName(url)
                                        list.add(Video.Server(id = "links-tv-$index", name = "$playerName (Link ${index + 1})", src = url))
                                    }
                                }
                            }
                            list
                        }
                    }

                    val wiflixDeferred = async {
                        runEndpoint("wiflix-tv") {
                            val wiflix = movixServiceInstance.getWiflixTv(tmdbId, seasonNum)
                            val list = mutableListOf<Video.Server>()
                            wiflix.episodes?.get(episodeNum.toString())?.forEach { (lang, sources) ->
                                val displayLang = formatLang(lang)
                                sources.forEach { source ->
                                    val url = source.url ?: return@forEach
                                    if (url.isBlank()) return@forEach
                                    val playerName = source.name?.takeIf { it.isNotBlank() } ?: guessPlayerName(url)
                                    list.add(Video.Server(id = "wiflix-$lang-${list.size}", name = "$playerName ($displayLang)", src = url))
                                }
                            }
                            list
                        }
                    }

                    val cpasmalDeferred = async {
                        runEndpoint("cpasmal-tv") {
                            val cpasmal = movixServiceInstance.getCpasmalTv(tmdbId, seasonNum, episodeNum)
                            val list = mutableListOf<Video.Server>()
                            cpasmal.links?.forEach { (lang, links) ->
                                val displayLang = formatLang(lang)
                                links.forEach { link ->
                                    val url = link.url ?: return@forEach
                                    if (url.isBlank()) return@forEach
                                    val playerName = link.server?.replaceFirstChar { it.uppercase() } ?: guessPlayerName(url)
                                    list.add(Video.Server(id = "cpasmal-$lang-${list.size}", name = "$playerName ($displayLang)", src = url))
                                }
                            }
                            list
                        }
                    }

                    val tmdbMovixDeferred = async {
                        runEndpoint("tmdb-tv") {
                            val tmdbMovix = movixServiceInstance.getTmdbMovixTv(tmdbId, seasonNum, episodeNum)
                            val list = mutableListOf<Video.Server>()
                            tmdbMovix.current_episode?.player_links?.forEach { link ->
                                val url = link.decoded_url ?: return@forEach
                                if (url.isBlank()) return@forEach
                                val lang = if (link.language?.lowercase()?.contains("french") == true) "VF"
                                    else link.language ?: ""
                                val qualityLabel = link.quality?.substringBefore("/")?.trim() ?: "HD"
                                val playerName = guessPlayerName(url)
                                list.add(Video.Server(id = "tmdbmovix-${list.size}", name = "$playerName - $qualityLabel ($lang)", src = url))
                            }
                            list
                        }
                    }

                    val seriesDlDeferred = async {
                        runEndpoint("seriesdl-tv") {
                            val showTitle = videoType.tvShow.title
                            val searchResults = movixServiceInstance.searchMovix(showTitle)
                            // 2026-05-03 : Movix renvoie type="series" pour les TV (pas
                            // "tv"). Le filtre précédent excluait TOUS les résultats
                            // série -> seriesDl ne récupérait jamais rien -> on ratait
                            // les sources Darkibox HLS sur des séries comme New York 911.
                            val movixShow = searchResults.results?.firstOrNull {
                                (it.type == "tv" || it.type == "series") && it.tmdb_id?.toString() == tmdbId
                            }
                            val movixId = movixShow?.id?.toString()
                            val list = mutableListOf<Video.Server>()
                            if (movixId != null) {
                                val dl = movixServiceInstance.getSeriesDownload(movixId, seasonNum, episodeNum)
                                dl.sources?.forEach { source ->
                                    val m3u8Url = source.m3u8
                                    val embedUrl = source.src
                                    val url = if (!m3u8Url.isNullOrBlank()) m3u8Url else embedUrl ?: return@forEach
                                    if (url.isBlank()) return@forEach
                                    val displayLang = source.language?.uppercase() ?: "MULTI"
                                    val quality = source.quality ?: "HD"
                                    val playerName = if (!m3u8Url.isNullOrBlank()) "Darkibox HLS" else guessPlayerName(url)
                                    list.add(Video.Server(id = "seriesdl-${list.size}", name = "$playerName ($displayLang - $quality)", src = url))
                                }
                            }
                            list
                        }
                    }

                    // 2026-05-03 : Videasy direct (FR + EN). Movix UI utilise
                    // player.videasy.net pour les sources VO/VOSTFR sur les vieilles
                    // séries, sans cet appel on perdait jusqu'à 4-8 sources par
                    // épisode (Chamber + 8 servers EN type Neon/Yoru/Cypher…).
                    val videasyDeferred = async {
                        try {
                            val videasy = com.streamflixreborn.streamflix.extractors.VideasyExtractor()
                            val frServers = videasy.servers(videoType, "fr")
                            // 2026-05-03 : Neon (myflixerzupcloud) renvoie souvent
                            // des sources Netflix random (player de fallback) sur
                            // les vieilles séries non indexées -> on l'exclut côté
                            // Movix.
                            val enServers = videasy.servers(videoType, "en")
                                .filterNot { it.id.startsWith("Neon ") }
                            (frServers + enServers).also {
                                Log.d("MovixProvider", "Videasy tv: ${it.size} servers (${frServers.size} FR + ${enServers.size} EN, Neon filtered)")
                            }
                        } catch (e: Exception) {
                            Log.e("MovixProvider", "Videasy tv error: ${e.message}")
                            emptyList()
                        }
                    }

                    // 2026-05-04 : MazQuest = backend allostreaming.one + waaatch.art.
                    // Sert des yadi.sk (Yandex Disk) avec gros catalogue VF de
                    // vieilles séries (NY911, Friends, etc.) que Movix ne couvre
                    // plus. Pré-check de l'API ici pour ne pas afficher de
                    // serveur fantôme sur les épisodes qui n'ont pas de source.
                    val mazQuestDeferred = async {
                        runEndpoint("mazquest-tv") {
                            val ssPad = "%02d".format(seasonNum)
                            val epPad = "%02d".format(episodeNum)
                            val url = "https://embed.maz.quest/tv/api/$tmdbId/$ssPad/$epPad"
                            // Pré-check : on hit l'API tout de suite. Si `error=true`
                            // ou `links` vide -> on n'expose pas le serveur. Sinon
                            // on l'ajoute, l'extracteur retapera l'API au play
                            // (plus fiable car le yadi.sk peut périmer entre temps).
                            val req = okhttp3.Request.Builder()
                                .url(url)
                                .header("Accept", "application/json")
                                .build()
                            val resp = com.streamflixreborn.streamflix.utils.NetworkClient.default
                                .newCall(req).execute()
                            val body = resp.body?.string()
                            if (body.isNullOrBlank()) return@runEndpoint emptyList<Video.Server>()
                            val json = org.json.JSONObject(body)
                            val hasSource = !json.optBoolean("error", true)
                                && (json.optJSONArray("links")?.length() ?: 0) > 0
                            if (!hasSource) {
                                Log.d("MovixProvider", "MazQuest tv: no source for $tmdbId S${ssPad}E$epPad")
                                return@runEndpoint emptyList<Video.Server>()
                            }
                            Log.d("MovixProvider", "MazQuest tv: source found for $tmdbId S${ssPad}E$epPad")
                            listOf(
                                Video.Server(
                                    id = "mazquest-tv-$tmdbId-$ssPad-$epPad",
                                    name = "Yandex VF (Maz)",
                                    src = url,
                                )
                            )
                        }
                    }

                    // 2026-05-04 : Yflix.to backup pour TV episode
                    val yflixDeferred = async {
                        runEndpoint("yflix-tv") {
                            val tmdbIdInt = tmdbId.toIntOrNull() ?: return@runEndpoint emptyList()
                            val tv = try {
                                tmdbService.getTvDetails(tmdbIdInt, TMDB_API_KEY)
                            } catch (_: Exception) { null }
                            val title = tv?.name ?: return@runEndpoint emptyList()
                            val year = tv.first_air_date?.take(4)?.toIntOrNull()
                            val watchPath = searchYflix(title, year, "TV") ?: return@runEndpoint emptyList()
                            listOf(buildYflixServer(watchPath, season = seasonNum, episode = episodeNum))
                        }
                    }
                    // 2026-05-04 : Moiflix backup pour TV episode
                    val moiflixDeferred = async {
                        runEndpoint("moiflix-tv") {
                            val tmdbIdInt = tmdbId.toIntOrNull() ?: return@runEndpoint emptyList()
                            val tv = try {
                                tmdbService.getTvDetails(tmdbIdInt, TMDB_API_KEY)
                            } catch (_: Exception) { null }
                            val title = tv?.name ?: return@runEndpoint emptyList()
                            val year = tv.first_air_date?.take(4)?.toIntOrNull()
                            val matchUrl = searchMoiflix(title, year, "Show") ?: return@runEndpoint emptyList()
                            listOf(buildMoiflixServer(matchUrl, season = seasonNum, episode = episodeNum))
                        }
                    }
                    awaitAll(fstreamDeferred, linksDeferred, wiflixDeferred, cpasmalDeferred, tmdbMovixDeferred, seriesDlDeferred, videasyDeferred, mazQuestDeferred, yflixDeferred, moiflixDeferred)
                }

                allResults.forEach { servers.addAll(it) }
            }
        }

        // 2026-05-05 : Papadustream backup ajouté AVANT le tri pour que toutes
        // les sources (Movix + Papa) soient triées ensemble par langue (VF →
        // VOSTFR → VO) au lieu d'avoir Papa appendées sans tri à la fin.
        val papaBackup = try {
            PapadustreamProvider.getPapaSourcesByTmdbId(id, videoType)
        } catch (e: Exception) {
            Log.d("MovixProvider", "Papadustream backup failed for $id: ${e.message}")
            emptyList()
        }
        if (papaBackup.isNotEmpty()) {
            Log.d("MovixProvider", "+ Papadustream backup : ${papaBackup.size} sources (mergées avant tri)")
            servers.addAll(papaBackup)
        }

        // 2026-05-05 : Moviebox backup — recherche par titre TMDB + filtrage FR
        // strict côté Moviebox (subtitles ou dubs FR uniquement). Renvoie 0-1
        // sources selon que Moviebox a le titre ou pas.
        val movieboxBackup = try {
            val tmdbIdInt = when (videoType) {
                is Video.Type.Movie -> id.toIntOrNull()
                is Video.Type.Episode -> id.substringBefore("-").toIntOrNull()
            }
            if (tmdbIdInt != null) MovieboxProvider.getMovieboxSourcesByTmdbId(tmdbIdInt, videoType)
            else emptyList()
        } catch (e: Exception) {
            Log.d("MovixProvider", "Moviebox backup failed for $id: ${e.message}")
            emptyList()
        }
        if (movieboxBackup.isNotEmpty()) {
            Log.d("MovixProvider", "+ Moviebox backup : ${movieboxBackup.size} sources")
            servers.addAll(movieboxBackup)
        }

        // 2026-05-05 : Coflix backup — site français multi-hosters (Lulustream,
        // VOE, Vidoza, Darkibox, Veev, Goodstream...). Recherche par titre via
        // /suggest.php (pas besoin d'ID, ça marche pour tout le catalogue).
        val coflixBackup = try {
            // On a l'ID TMDB ; on récupère le titre/year depuis TMDB pour la recherche
            val tmdbIdInt = when (videoType) {
                is Video.Type.Movie -> id.toIntOrNull()
                is Video.Type.Episode -> id.substringBefore("-").toIntOrNull()
            }
            if (tmdbIdInt != null) {
                val (title, year) = when (videoType) {
                    is Video.Type.Movie -> {
                        val det = TMDb3.Movies.details(movieId = tmdbIdInt, language = "fr-FR")
                        (det.title.takeIf { it.isNotBlank() } ?: det.originalTitle) to
                            det.releaseDate?.take(4)?.toIntOrNull()
                    }
                    is Video.Type.Episode -> {
                        val det = TMDb3.TvSeries.details(seriesId = tmdbIdInt, language = "fr-FR")
                        (det.name.takeIf { it.isNotBlank() } ?: det.originalName) to
                            det.firstAirDate?.take(4)?.toIntOrNull()
                    }
                }
                when (videoType) {
                    is Video.Type.Movie -> CoflixSourceProvider.getMovieSources(title, year)
                    is Video.Type.Episode -> CoflixSourceProvider.getEpisodeSources(
                        showTitle = title,
                        year = year,
                        seasonNumber = videoType.season.number,
                        episodeNumber = videoType.number,
                    )
                }
            } else emptyList()
        } catch (e: Exception) {
            Log.d("MovixProvider", "Coflix backup failed for $id: ${e.message}")
            emptyList()
        }
        if (coflixBackup.isNotEmpty()) {
            Log.d("MovixProvider", "+ Coflix backup : ${coflixBackup.size} sources")
            servers.addAll(coflixBackup)
        }

        // Tri global : VF d'abord, VOSTFR ensuite, VO en dernier (toutes sources confondues)
        val finalList = sortServersByLanguage(servers)

        // 2026-05-04 : on cache l'agrégat (TTL 5 min). Les m3u8 individuels
        // sont re-extracted à la demande (cache séparé Extractor.cacheTtlMs)
        // donc pas de risque de servir un m3u8 périmé depuis ce cache.
        if (finalList.isNotEmpty()) {
            serversCache[cacheKey] = CachedServers(
                servers = finalList,
                expiresAtMs = System.currentTimeMillis() + SERVERS_CACHE_TTL_MS,
            )
        }

        return finalList
    }

    /**
     * Devine le nom du player/extracteur à partir de l'URL (pour le fallback par nom dans Extractor).
     * Ex: "https://filemoon.sx/e/abc123" -> "Filemoon"
     *     "https://streamtape.com/e/xyz" -> "Streamtape"
     */
    private fun guessPlayerName(url: String): String {
        // Try the accurate extractor-based detection first
        Extractor.identifyServiceName(url)?.let { return it }
        // Fallback: derive from domain name
        return try {
            val host = url.substringAfter("://").substringBefore("/").substringBefore(":")
            val domain = host.removePrefix("www.")
            val name = domain.substringBeforeLast(".").substringBeforeLast(".")
                .ifEmpty { domain.substringBeforeLast(".") }
            name.replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            "Serveur"
        }
    }

    /**
     * Trie les serveurs par priorité de langue (request user 2026-05-05) :
     * 1. **VF** (Version Française) en premier — sous-trié par fiabilité extracteur
     * 2. **VOSTFR** (Version Originale Sous-Titrée FR) en deuxième
     * 3. **VO** (Version Originale) en dernier
     *
     * Détection :
     * - VOSTFR : contient "vostfr" ou "sous-titr"
     * - VF : contient "[vf]", "vf]", " vf ", " vf$", "french", "français", "francais"
     *        OU n'a pas de tag explicite (default → traité comme VF)
     * - VO : contient "[vo]", " vo ", " vo$", "(vo)" SANS être déjà classé VF/VOSTFR
     *
     * Dédup par URL en bonus pour éviter qu'une même source remonte deux fois.
     */
    private fun sortServersByLanguage(servers: List<Video.Server>): List<Video.Server> {
        val seen = HashSet<String>()
        val unique = servers.filter { server ->
            val key = server.src.lowercase().trim()
            key.isEmpty() || seen.add(key)
        }

        val vfServers = mutableListOf<Video.Server>()
        val vostfrServers = mutableListOf<Video.Server>()
        val voServers = mutableListOf<Video.Server>()

        // Detection VO robuste : "vo" comme MOT entouré de non-lettres
        // → catche "Phoenix (Videasy VO)" mais pas "vodplay"/"video"
        val voRegex = Regex("""(^|[^a-z])vo([^a-z]|$)""")
        unique.forEach { server ->
            val name = server.name.lowercase()
            when {
                name.contains("vostfr") || name.contains("sous-titr") ->
                    vostfrServers.add(server)
                voRegex.containsMatchIn(name) ->
                    voServers.add(server)
                // Tout le reste → VF (par défaut)
                else -> vfServers.add(server)
            }
        }

        // 2026-05-05 v2 : Natives Movix en premier, BACKUPS en dernier.
        //   (0) Movix natif (fstream, links, wiflix, cpasmal, tmdb, drama,
        //       purstream, seriesDl, videasy, mazQuest, yflix, moiflix)
        //   (1) Backups Papa / Moviebox (catalogue cross-provider sain)
        //   (2) Coflix EN DERNIER (link rot fréquent depuis lecteurvideo.com)
        //   Au sein d'un même bucket, par fiabilité d'extracteur.
        fun isPapa(s: Video.Server) = s.name.contains("Papadustream — ", ignoreCase = false) ||
            s.id.startsWith("papadustream_")
        fun isMoviebox(s: Video.Server) = s.name.startsWith("Moviebox", ignoreCase = false) ||
            s.id.startsWith("moviebox_")
        fun isCoflix(s: Video.Server) = s.name.startsWith("Coflix", ignoreCase = false) ||
            s.id.startsWith("coflix_")
        // 2026-05-05 v3 : bonus qualité — bumps les sources HD/4K/1080p en
        // tête de leur tier respectif. -2 = passe avant les SD, -3 = passe
        // avant tout dans le même score. Permet à "VOE HD" de battre un
        // "VOE SD" du même tier sans casser la hiérarchie inter-tiers.
        fun qualityBonus(s: Video.Server): Int {
            val n = s.name.lowercase()
            return when {
                n.contains("4k") || n.contains("2160") || n.contains("uhd") -> -3
                n.contains("1080") || n.contains("fhd") || n.contains("full hd") -> -2
                n.contains(" hd") || n.endsWith("hd") || n.contains("[hd]") || n.contains("(hd)") -> -1
                n.contains("720") -> 0
                n.contains("480") || n.contains(" sd") -> 1
                else -> 0
            }
        }
        val cmp = compareBy<Video.Server>(
            {
                when {
                    isCoflix(it) -> 2
                    isPapa(it) || isMoviebox(it) -> 1
                    else -> 0
                }
            },
            { serverReliabilityScore(it) },
            { qualityBonus(it) },
        )
        val sortedVf = vfServers.sortedWith(cmp)
        val sortedVostfr = vostfrServers.sortedWith(cmp)
        val sortedVo = voServers.sortedWith(cmp)

        // Order final : VF → VOSTFR → VO (request user)
        val result = mutableListOf<Video.Server>()
        result.addAll(sortedVf)
        result.addAll(sortedVostfr)
        result.addAll(sortedVo)

        Log.d("MovixProvider", "Servers triés (dedup ${servers.size}→${unique.size}) : ${vfServers.size} VF • ${vostfrServers.size} VOSTFR • ${voServers.size} VO")
        return result
    }

    /**
     * 2026-05-05 v2 : score de fiabilité refait en fonction des observations
     * réelles sur la Chromecast (Darkibox hang silencieux, VidMoLy lent via
     * WebView, etc.). Plus bas = mieux = essayé en premier dans le cascade.
     */
    private fun serverReliabilityScore(server: Video.Server): Int {
        val name = server.name.lowercase()
        val src = server.src.lowercase()
        val id = server.id.lowercase()
        return when {
            // === Tier 1 : Direct & rapide (priorité absolue) ===
            // Moviebox VF natif (URL directe themoviebox.org, extracteur WebView rapide)
            id.startsWith("moviebox_") || src.contains("themoviebox") -> 0
            // Filemoon : extracteur API, link rot rare sur contenu frais
            src.contains("filemoon") || name.contains("filemoon") -> 1
            // VOE : streaming rapide, anti-bot OK
            src.contains("voe.sx") || src.contains("voe-")
                || (name.contains("voe") && !name.contains("vovo")) -> 2
            // Uqload, Vidoza, Doodstream, Streamtape : hosters classiques fiables
            src.contains("uqload") || name.contains("uqload") -> 3
            src.contains("vidoza") || name.contains("vidoza") -> 4
            src.contains("dood") || name.contains("doodstream") -> 5
            src.contains("streamtape") || name.contains("streamtape") -> 6

            // === Tier 2 : Fiable mais init plus lente ===
            // Netu/Waaw : Cronet + TLS Chromium, init 1-2s puis stable
            src.contains("netu") || name.contains("netu")
                || src.contains("waaw") || name.contains("waaw") -> 10
            src.contains("frembed") || name.contains("frembed") -> 11
            src.contains("mixdrop") || name.contains("mixdrop") -> 12
            src.contains("mp4upload") || name.contains("mp4upload") -> 13
            src.contains("savefiles") || name.contains("savefiles") -> 14
            src.contains("playmogo") || name.contains("playmogo") -> 15

            // === Tier 3 : WebView ou anti-bot lourd (slow init) ===
            // VidMoLy : WebView extraction 5-10s
            src.contains("vidmoly") || name.contains("vidmoly") || name.contains("vidmoly") -> 20
            // Streamwish/Wishonly : extraction de script JS, parfois échoue
            src.contains("streamwish") || src.contains("wishonly")
                || name.contains("streamwish") -> 21
            src.contains("lulustream") || src.contains("luluvdo")
                || name.contains("lulustream") || name.contains("luluvdo") -> 22
            src.contains("vidsrc") || name.contains("vidsrc") -> 23
            src.contains("vixsrc") -> 24
            src.contains("flemmix") || name.contains("flemmix") -> 25
            src.contains("vidhide") || name.contains("vidhide")
                || src.contains("filelions") -> 26
            src.contains("vidguard") || name.contains("vidguard") -> 27
            src.contains("vidzy") || name.contains("vidzy") -> 28
            src.contains("vidsonic") || name.contains("vidsonic") -> 29
            src.contains("vidara") || name.contains("vidara") -> 30
            src.contains("minochinos") || name.contains("minochinos") -> 31

            // === Tier 4 : Problématiques (Cloudflare hang, link rot) ===
            // 2026-05-05 : DARKIBOX déclasse — observé : répond HTTP 200 mais le
            // stream stalle indéfiniment (Cloudflare drop silencieux). Avec
            // notre watchdog buffering 10s c'est récupérable mais on les met
            // en bas pour ne pas perdre de temps.
            src.contains("darkibox") || name.contains("darkibox") || name.contains("darki") -> 50
            src.contains("veev") || name.contains("veev") -> 51
            src.contains("goodstream") || name.contains("goodstream") -> 52
            src.contains("megaup") || name.contains("megaup") -> 53

            // === Tier 5 : Cassés ===
            src.contains("hgcloud") || name.contains("hgcloud") -> 90
            src.contains("xshotcok") || name.contains("xshotcok") -> 99

            // Défaut (extracteurs inconnus / nouveaux) — milieu de tier 3
            else -> 19
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.video != null) return server.video!!

        var url = server.src.trim()

        // Résolution de redirections courantes avant extraction
        try {
            if (url.contains("/redirect") || url.contains("/go/") || url.contains("/out/")) {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                val response = client.newCall(request).execute()
                val finalUrl = response.request.url.toString()
                response.close()
                if (finalUrl.isNotEmpty() && finalUrl != url) {
                    Log.d("MovixProvider", "Redirect resolved: $url -> $finalUrl")
                    url = finalUrl
                }
            }
        } catch (e: Exception) {
            Log.w("MovixProvider", "Redirect resolution failed: ${e.message}")
        }

        // Gestion des liens vidéo directs (m3u8, mp4, etc.)
        val directExtensions = listOf(".m3u8", ".mp4", ".mkv", ".webm", ".avi")
        val urlLower = url.lowercase().split("?").first()
        if (directExtensions.any { urlLower.endsWith(it) }) {
            val type = when {
                urlLower.endsWith(".m3u8") -> "application/x-mpegURL"
                urlLower.endsWith(".mp4") -> "video/mp4"
                urlLower.endsWith(".mkv") -> "video/x-matroska"
                urlLower.endsWith(".webm") -> "video/webm"
                else -> "video/mp4"
            }
            // Add Referer for darkibox direct HLS URLs
            val headers = if (url.contains("darkibox.com")) {
                mapOf("Referer" to "https://darkibox.com")
            } else {
                emptyMap()
            }
            return Video(
                source = url,
                type = type,
                headers = headers
            )
        }

        // Extraction via les extracteurs enregistrés (avec server pour le fallback par nom)
        return Extractor.extract(url, server)
    }

    // ==================== TMDB PERSON ====================

    data class TmdbPersonDetail(
        val id: Int?,
        val name: String?,
        val biography: String?,
        val birthday: String?,
        val deathday: String?,
        val place_of_birth: String?,
        val profile_path: String?
    )

    // ==================== RETROFIT SERVICES ====================

    private interface MovixService {
        @GET("api/search")
        suspend fun search(
            @Query("title") title: String
        ): List<MovixSearchItem>

        @GET("api/fstream/movie/{tmdbId}")
        suspend fun getFstreamMovie(
            @Path("tmdbId") tmdbId: String
        ): FstreamMovieResponse

        @GET("api/fstream/tv/{tmdbId}/season/{season}")
        suspend fun getFstreamTv(
            @Path("tmdbId") tmdbId: String,
            @Path("season") season: Int
        ): FstreamTvResponse

        @GET("api/links/movie/{tmdbId}")
        suspend fun getLinksMovie(
            @Path("tmdbId") tmdbId: String
        ): LinksMovieResponse

        @GET("api/links/tv/{tmdbId}")
        suspend fun getLinksTv(
            @Path("tmdbId") tmdbId: String,
            @Query("season") season: Int,
            @Query("episode") episode: Int
        ): LinksTvResponse

        @GET("api/wiflix/movie/{tmdbId}")
        suspend fun getWiflixMovie(
            @Path("tmdbId") tmdbId: String
        ): WiflixMovieResponse

        @GET("api/wiflix/tv/{tmdbId}/{season}")
        suspend fun getWiflixTv(
            @Path("tmdbId") tmdbId: String,
            @Path("season") season: Int
        ): WiflixTvResponse

        // --- Cpasmal endpoints ---

        @GET("api/cpasmal/movie/{tmdbId}")
        suspend fun getCpasmalMovie(
            @Path("tmdbId") tmdbId: String
        ): CpasmalMovieResponse

        @GET("api/cpasmal/tv/{tmdbId}/{season}/{episode}")
        suspend fun getCpasmalTv(
            @Path("tmdbId") tmdbId: String,
            @Path("season") season: Int,
            @Path("episode") episode: Int
        ): CpasmalResponse

        // --- Series Download endpoints ---

        @GET("api/series/download/{seriesId}/season/{season}/episode/{episode}")
        suspend fun getSeriesDownload(
            @Path("seriesId") seriesId: String,
            @Path("season") season: Int,
            @Path("episode") episode: Int
        ): SeriesDownloadResponse

        @GET("api/series/download/{movieId}")
        suspend fun getMovieDownload(
            @Path("movieId") movieId: String
        ): SeriesDownloadResponse

        // --- TMDB Movix endpoints ---

        @GET("api/tmdb/tv/{tmdbId}")
        suspend fun getTmdbMovixTv(
            @Path("tmdbId") tmdbId: String,
            @Query("season") season: Int,
            @Query("episode") episode: Int
        ): TmdbMovixTvResponse

        @GET("api/tmdb/movie/{tmdbId}")
        suspend fun getTmdbMovixMovie(
            @Path("tmdbId") tmdbId: String
        ): TmdbMovixMovieResponse

        // --- Search for internal series ID ---

        @GET("api/search")
        suspend fun searchMovix(
            @Query("title") title: String
        ): MovixSearchResponse
    }

    private interface TmdbService {
        @GET("trending/all/day")
        suspend fun getTrending(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR"
        ): TmdbPageResult<TmdbTrendingItem>

        @GET("movie/popular")
        suspend fun getPopularMovies(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbMovieListItem>

        @GET("movie/top_rated")
        suspend fun getTopRatedMovies(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbMovieListItem>

        @GET("tv/popular")
        suspend fun getPopularTvShows(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbTvListItem>

        @GET("tv/top_rated")
        suspend fun getTopRatedTvShows(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbTvListItem>

        @GET("discover/movie")
        suspend fun discoverMovies(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1,
            @Query("sort_by") sortBy: String = "popularity.desc",
            @Query("with_genres") withGenres: String? = null,
            @Query("with_origin_country") withOriginCountry: String? = null,
            @Query("include_adult") includeAdult: Boolean = false
        ): TmdbPageResult<TmdbMovieListItem>

        @GET("discover/tv")
        suspend fun discoverTvShows(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1,
            @Query("sort_by") sortBy: String = "popularity.desc",
            @Query("with_genres") withGenres: String? = null,
            @Query("with_origin_country") withOriginCountry: String? = null,
            @Query("include_adult") includeAdult: Boolean = false
        ): TmdbPageResult<TmdbTvListItem>

        @GET("movie/{id}")
        suspend fun getMovieDetails(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("append_to_response") appendToResponse: String? = null
        ): TmdbMovieResult

        @GET("tv/{id}")
        suspend fun getTvDetails(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("append_to_response") appendToResponse: String? = null
        ): TmdbTvResult

        @GET("tv/{tv_id}/season/{season_number}")
        suspend fun getSeasonDetails(
            @Path("tv_id") tvId: Int,
            @Path("season_number") seasonNumber: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR"
        ): TmdbSeasonDetail

        @GET("movie/{id}/recommendations")
        suspend fun getMovieRecommendations(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR"
        ): TmdbPageResult<TmdbMovieListItem>

        @GET("tv/{id}/recommendations")
        suspend fun getTvRecommendations(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR"
        ): TmdbPageResult<TmdbTvListItem>

        @GET("person/{id}")
        suspend fun getPersonDetails(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("append_to_response") appendToResponse: String? = null
        ): TmdbPersonDetail
    }

    // ==================== SERVICE BUILDERS ====================

    private fun buildMovixService(): MovixService {
        val client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Referer", portalUrl)
                    .header("Origin", portalUrl.trimEnd('/'))
                    .build()
                Log.d("MovixProvider", "API Request: ${request.url}")
                val response = chain.proceed(request)
                if (!response.isSuccessful) {
                    val body = response.peekBody(2048).string()
                    Log.e("MovixProvider", "API Error ${response.code}: ${request.url} -> $body")
                }
                response
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(MovixService::class.java)
    }

    private fun buildTmdbService(): TmdbService {
        val client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()

        return Retrofit.Builder()
            .baseUrl(TMDB_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(TmdbService::class.java)
    }
}
