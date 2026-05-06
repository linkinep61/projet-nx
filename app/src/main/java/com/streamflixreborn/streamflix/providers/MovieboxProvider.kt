package com.streamflixreborn.streamflix.providers

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TmdbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// ─── Retrofit service + DTOs (top-level pour résoudre les forward references
//     dans le délégate `lazy` de `service` ci-dessous) ─────────────────────

private interface MovieboxService {
    @GET("subject/trending")
    suspend fun getTrending(
        @HeaderMap headers: Map<String, String>,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): MovieboxTrendingResp

    @POST("subject/search")
    suspend fun search(
        @HeaderMap headers: Map<String, String>,
        @Body body: MovieboxSearchBody,
    ): MovieboxSearchResp

    @GET("detail")
    suspend fun getDetail(
        @HeaderMap headers: Map<String, String>,
        @Query("detailPath") detailPath: String,
    ): MovieboxDetailResp

    @GET("detail")
    suspend fun getDetailById(
        @HeaderMap headers: Map<String, String>,
        @Query("subjectId") subjectId: String,
    ): MovieboxDetailResp

    @GET("subject/detail-rec")
    suspend fun getRecommendations(
        @HeaderMap headers: Map<String, String>,
        @Query("subjectId") subjectId: String,
        @Query("page") page: Int = 1,
        @Query("perPage") perPage: Int = 12,
    ): MovieboxRecommendationsResp
}

data class MovieboxSearchBody(
    val keyword: String,
    val page: Int = 0,
    val perPage: Int = 20,
    val subjectType: Int? = null,
)

data class MovieboxSearchResp(val code: Int = 0, val message: String? = null, val data: MovieboxSearchData? = null)
data class MovieboxSearchData(val pager: MovieboxPager? = null, val items: List<MovieboxSubject>? = null)

data class MovieboxTrendingResp(val code: Int = 0, val message: String? = null, val data: MovieboxTrendingData? = null)
data class MovieboxTrendingData(val subjectList: List<MovieboxSubject>? = null)

data class MovieboxDetailResp(val code: Int = 0, val message: String? = null, val data: MovieboxDetailData? = null)
data class MovieboxDetailData(
    val subject: MovieboxSubject? = null,
    val stars: List<MovieboxStaff>? = null,
    val resource: MovieboxResource? = null,
)

data class MovieboxRecommendationsResp(val code: Int = 0, val message: String? = null, val data: MovieboxSearchData? = null)

data class MovieboxPager(
    val hasMore: Boolean = false,
    val nextPage: String? = null,
    val page: String? = null,
    val perPage: Int = 0,
    val totalCount: Int = 0,
)

data class MovieboxSubject(
    val subjectId: String? = null,
    val subjectType: Int = 1,
    val title: String? = null,
    val description: String? = null,
    val releaseDate: String? = null,
    val duration: Int? = 0,
    val genre: String? = null,
    val cover: MovieboxCover? = null,
    // 2026-05-05 : fallbacks poster — selon l'endpoint, l'API expose le poster
    // sous différents noms. On prend tous les candidats connus, et `bestPoster()`
    // pioche le premier non-null.
    val posterUrl: String? = null,
    val imageUrl: String? = null,
    val coverUrl: String? = null,
    val verticalCover: MovieboxCover? = null,
    val poster: MovieboxCover? = null,
    val countryName: String? = null,
    val imdbRatingValue: String? = null,
    val subtitles: String? = null,
    val hasResource: Boolean = false,
    val detailPath: String? = null,
    val dubs: List<MovieboxDub>? = null,
    val trailer: MovieboxTrailer? = null,
) {
    /** Renvoie la meilleure URL de poster dispo dans cet objet. */
    fun bestPoster(): String? {
        return cover?.url
            ?: poster?.url
            ?: verticalCover?.url
            ?: posterUrl?.takeIf { it.isNotBlank() }
            ?: coverUrl?.takeIf { it.isNotBlank() }
            ?: imageUrl?.takeIf { it.isNotBlank() }
            ?: trailer?.cover?.url
    }
}

data class MovieboxCover(
    val url: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val format: String? = null,
    val blurHash: String? = null,
)

data class MovieboxDub(
    val subjectId: String? = null,
    val lanName: String? = null,   // "French dub", "Original Audio", "Arabic sub"
    val lanCode: String? = null,   // "fr", "en", "ar", ...
    val original: Boolean = false,
    val type: Int = 0,             // 0 = dub, 1 = sub
    val detailPath: String? = null,
)

data class MovieboxTrailer(
    val videoAddress: MovieboxVideoAddress? = null,
    val cover: MovieboxCover? = null,
)

data class MovieboxVideoAddress(
    val videoId: String? = null,
    val url: String? = null,
    val duration: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
)

data class MovieboxStaff(
    val staffId: String? = null,
    val staffType: Int = 1,
    val name: String? = null,
    val character: String? = null,
    val avatarUrl: String? = null,
    val detailPath: String? = null,
)

data class MovieboxResource(
    val seasons: List<MovieboxResourceSeason>? = null,
    val source: String? = null,
    val uploadBy: String? = null,
)

data class MovieboxResourceSeason(
    val se: Int = 0,
    val maxEp: Int = 0,
    val allEp: String? = null,
    val resolutions: List<MovieboxResolution>? = null,
)

data class MovieboxResolution(
    val resolution: Int = 0,
    val epNum: Int = 0,
)

/**
 * MovieboxProvider — themoviebox.org (Nuxt SSR + wefeed-h5api-bff API hébergée
 * sur aoneroom.com).
 *
 * **Politique stricte FR uniquement.** L'API expose un catalogue international
 * (Hollywood, K-Dramas, animes, séries asiatiques) avec sous-titres et doublages
 * variés. On filtre côté client pour ne montrer QUE les titres où :
 *   - `subtitles` contient `"Français"` (sous-titres FR disponibles), OU
 *   - `dubs[]` contient un item avec `lanCode == "fr"` (doublage FR disponible)
 * Tout le reste est exclu — cohérent avec la politique french-only de Streamflix.
 *
 * **Ressources cross-provider** : [searchByTitle] est public pour permettre
 * aux autres providers (FrenchStream, Movix, AnimeSama, VoirAnime, etc.) de
 * proposer Moviebox comme source complémentaire quand un titre matche.
 *
 * **API endpoints (reverse-eng 2026-05-05) :**
 *   - GET  /wefeed-h5api-bff/subject/trending?page=N&perPage=M → liste tendances
 *   - POST /wefeed-h5api-bff/subject/search body={keyword,page,perPage,subjectType?} → search
 *   - GET  /wefeed-h5api-bff/detail?detailPath=X → fiche complète (subject + dubs + résolutions)
 *   - GET  /wefeed-h5api-bff/detail?subjectId=X → idem mais via subjectId
 *   - GET  /wefeed-h5api-bff/subject/detail-rec?subjectId=X → recommendations
 *
 * Headers requis (CORS) :
 *   - Origin: https://themoviebox.org
 *   - Referer: https://themoviebox.org/fr
 *   - x-client-info: lang=fr;hostName=themoviebox.org
 *
 * Subject types : 1 = MOVIE, 2 = SERIES.
 *
 * **getServers/extraction streams** : pas encore implémenté côté Streamflix.
 * Le stream se charge via Nuxt SSR + appels JS dynamiques (URL signée
 * `/bt/{hash}.mp4?sign=X&t=Y` sur un CDN aoneroom dont le host est obfusqué
 * par JWT token côté client). À reverse-eng en v2.
 */
object MovieboxProvider : Provider {

    override val name = "Moviebox"
    override val baseUrl = "https://themoviebox.org"
    override val logo = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_moviebox"
    override val language = "fr"

    private const val TAG = "MovieboxProvider"
    private const val API_BASE = "https://h5-api.aoneroom.com/wefeed-h5api-bff/"

    private const val SUBJECT_MOVIE = 1
    private const val SUBJECT_SERIES = 2

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // ─── HTTP ──────────────────────────────────────────────────────────────

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val service: MovieboxService by lazy {
        Retrofit.Builder()
            .baseUrl(API_BASE)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MovieboxService::class.java)
    }

    private fun apiHeaders(): Map<String, String> = mapOf(
        "Origin" to "https://themoviebox.org",
        "Referer" to "https://themoviebox.org/fr",
        "x-client-info" to "lang=fr;hostName=themoviebox.org",
        "User-Agent" to USER_AGENT,
    )

    // ─── Filtrage français ─────────────────────────────────────────────────

    /**
     * Politique stricte : un subject est conservé UNIQUEMENT si :
     *   - son champ `subtitles` mentionne "Français" (sous-titres FR), OU
     *   - sa liste `dubs[]` contient un dub avec `lanCode == "fr"`.
     *
     * Application : trending/search ne renvoient PAS la liste `dubs` (toujours
     * null), donc le filtre se base sur `subtitles`. La liste détaillée des dubs
     * est récupérée via `/detail` quand on accède à la fiche du titre.
     */
    private fun isFrenchAvailable(s: MovieboxSubject): Boolean {
        val subs = s.subtitles.orEmpty()
        if (subs.contains("Français", ignoreCase = true)) return true
        val dubs = s.dubs.orEmpty()
        return dubs.any { it.lanCode.equals("fr", ignoreCase = true) }
    }

    // ─── Mapping ───────────────────────────────────────────────────────────

    private fun parseGenres(raw: String?): List<Genre> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { Genre(id = "mvbx::g::$it", name = it) }
    }

    private fun subjectToMovie(s: MovieboxSubject): Movie {
        // 2026-05-05 : utilise bestPoster() qui pioche le premier champ non-null
        // parmi cover/posterUrl/imageUrl/etc. Évite les jaquettes vides quand
        // l'API renvoie le poster sous un nom différent selon l'endpoint.
        val posterUrl = s.bestPoster()
        return Movie(
            id = "mvbx::m::${s.subjectId.orEmpty()}",
            title = s.title.orEmpty(),
            overview = s.description?.takeIf { it.isNotBlank() },
            released = s.releaseDate?.takeIf { it.isNotBlank() },
            runtime = s.duration?.takeIf { it > 0 }?.div(60),
            trailer = s.trailer?.videoAddress?.url,
            rating = s.imdbRatingValue?.toDoubleOrNull(),
            poster = posterUrl,
            banner = posterUrl,
            providerName = name,
            genres = parseGenres(s.genre),
        )
    }

    private fun subjectToTvShow(s: MovieboxSubject): TvShow {
        val posterUrl = s.bestPoster()
        return TvShow(
            id = "mvbx::s::${s.subjectId.orEmpty()}",
            title = s.title.orEmpty(),
            overview = s.description?.takeIf { it.isNotBlank() },
            released = s.releaseDate?.takeIf { it.isNotBlank() },
            trailer = s.trailer?.videoAddress?.url,
            rating = s.imdbRatingValue?.toDoubleOrNull(),
            poster = posterUrl,
            banner = posterUrl,
            providerName = name,
            genres = parseGenres(s.genre),
        )
    }

    /**
     * Reconstruit les saisons d'une série à partir de `resource.seasons[]`.
     * Format : `id = "mvbx::season::<subjectId>::<seasonNumber>"`
     */
    private fun buildSeasons(detailPath: String, subjectId: String, resource: MovieboxResource?): List<Season> {
        val seasons = resource?.seasons.orEmpty()
        return seasons.map { rs ->
            Season(
                id = "mvbx::season::$subjectId::${rs.se}",
                number = rs.se.takeIf { it > 0 } ?: 1,
                title = if (rs.se > 0) "Saison ${rs.se}" else "Saison 1",
            )
        }
    }

    // ─── Provider API ──────────────────────────────────────────────────────

    /**
     * Home — sections construites depuis /subject/trending puis filtrées FR.
     * Pas d'appel à /home (380 KB de méta UI non pertinent).
     *
     * Sections retournées (dans l'ordre) :
     *   0. FEATURED carousel (top 10 trending FR, swiper grand format)
     *   1. Tendances (mixte films + séries, top 18)
     *   2. Films populaires (subjectType=1, top 18)
     *   3. Séries populaires (subjectType=2, top 18)
     *
     * Pour le carousel FEATURED on préfère `trailer.cover.url` (landscape 16:9)
     * en banner si dispo, sinon fallback sur le cover portrait. Sans backdrop
     * landscape, le swiper plein-écran upscale moche le poster portrait.
     */
    override suspend fun getHome(): List<Category> = try {
        // 2026-05-05 v2 : sections diversifiées. Chaque rangée a son propre
        // contenu pour éviter le doublon Tendances/Séries populaires (qui
        // étaient juste du trending re-filtré).
        //
        // Stratégie : on fetch 4 pages de trending en parallèle (60 items/page
        // = 240 items au total) et on découpe en buckets par genre/type afin
        // d'avoir des rangées vraiment distinctes.
        val pages = mutableListOf<List<MovieboxSubject>>()
        for (p in 0..3) {
            pages += runCatching {
                service.getTrending(apiHeaders(), page = p, perPage = 60).data?.subjectList.orEmpty()
            }.getOrNull().orEmpty()
        }
        val all = pages.flatten().filter { isFrenchAvailable(it) }
            .distinctBy { it.subjectId }  // dédup au cas où les pages ont des chevauchements

        if (all.isEmpty()) {
            emptyList()
        } else {
            val sections = mutableListOf<Category>()

            fun matchesGenre(s: MovieboxSubject, vararg keywords: String): Boolean {
                val g = (s.genre ?: "").lowercase()
                return keywords.any { g.contains(it.lowercase()) }
            }
            // Pool d'items déjà placés pour éviter qu'un même show apparaisse en double
            val used = mutableSetOf<String>()
            fun take(filter: (MovieboxSubject) -> Boolean, max: Int = 18): List<MovieboxSubject> {
                val picked = all.filter { filter(it) && it.subjectId !in used }.take(max)
                picked.forEach { it.subjectId?.let(used::add) }
                return picked
            }

            // 0. FEATURED carousel (top 10 trending) — landscape backdrop si dispo
            val featuredItems = all.take(10).map { s ->
                val landscape = s.trailer?.cover?.url ?: s.cover?.url
                s.subjectId?.let(used::add)
                if (s.subjectType == SUBJECT_MOVIE) {
                    subjectToMovie(s).apply { banner = landscape }
                } else {
                    subjectToTvShow(s).apply { banner = landscape }
                }
            }
            if (featuredItems.isNotEmpty()) {
                sections.add(Category(name = Category.FEATURED, list = featuredItems))
            }

            // 1. Tendances (mix films + séries — items 11+)
            val trendsRest = take({ true }, 18)
            if (trendsRest.isNotEmpty()) {
                sections.add(Category(
                    name = "Tendances",
                    list = trendsRest.map {
                        if (it.subjectType == SUBJECT_MOVIE) subjectToMovie(it) else subjectToTvShow(it)
                    }
                ))
            }

            // 2. Films d'action
            val action = take({ it.subjectType == SUBJECT_MOVIE && matchesGenre(it, "Action", "Adventure") })
            if (action.size >= 4) {
                sections.add(Category(name = "Films Action & Aventure", list = action.map { subjectToMovie(it) }))
            }

            // 3. Animation / Anime
            val anim = take({ matchesGenre(it, "Animation", "Anime") })
            if (anim.size >= 4) {
                sections.add(Category(name = "Animation & Anime", list = anim.map {
                    if (it.subjectType == SUBJECT_MOVIE) subjectToMovie(it) else subjectToTvShow(it)
                }))
            }

            // 4. Séries Drame
            val drama = take({ it.subjectType == SUBJECT_SERIES && matchesGenre(it, "Drama") })
            if (drama.size >= 4) {
                sections.add(Category(name = "Séries Drame", list = drama.map { subjectToTvShow(it) }))
            }

            // 5. Comédies
            val comedy = take({ matchesGenre(it, "Comedy") })
            if (comedy.size >= 4) {
                sections.add(Category(name = "Comédies", list = comedy.map {
                    if (it.subjectType == SUBJECT_MOVIE) subjectToMovie(it) else subjectToTvShow(it)
                }))
            }

            // 6. Thriller / Crime
            val thriller = take({ matchesGenre(it, "Thriller", "Crime", "Mystery") })
            if (thriller.size >= 4) {
                sections.add(Category(name = "Thriller & Crime", list = thriller.map {
                    if (it.subjectType == SUBJECT_MOVIE) subjectToMovie(it) else subjectToTvShow(it)
                }))
            }

            // 7. Horreur
            val horror = take({ matchesGenre(it, "Horror") })
            if (horror.size >= 4) {
                sections.add(Category(name = "Horreur", list = horror.map {
                    if (it.subjectType == SUBJECT_MOVIE) subjectToMovie(it) else subjectToTvShow(it)
                }))
            }

            // 8. Science-Fiction & Fantastique
            val scifi = take({ matchesGenre(it, "Sci-Fi", "Science", "Fantasy") })
            if (scifi.size >= 4) {
                sections.add(Category(name = "Science-Fiction & Fantastique", list = scifi.map {
                    if (it.subjectType == SUBJECT_MOVIE) subjectToMovie(it) else subjectToTvShow(it)
                }))
            }

            // 9. K-Drama / Asian (par pays)
            val kdrama = take({ it.subjectType == SUBJECT_SERIES &&
                ((it.countryName ?: "").contains("Korea", true) ||
                 (it.countryName ?: "").contains("Japan", true) ||
                 (it.countryName ?: "").contains("Coré", true)) })
            if (kdrama.size >= 4) {
                sections.add(Category(name = "K-Drama & Asian", list = kdrama.map { subjectToTvShow(it) }))
            }

            // 10. Films récents catch-all (ce qu'il reste de films pas encore placés)
            val remainingMovies = take({ it.subjectType == SUBJECT_MOVIE })
            if (remainingMovies.size >= 4) {
                sections.add(Category(name = "Plus de films", list = remainingMovies.map { subjectToMovie(it) }))
            }

            // 11. Séries catch-all
            val remainingSeries = take({ it.subjectType == SUBJECT_SERIES })
            if (remainingSeries.size >= 4) {
                sections.add(Category(name = "Plus de séries", list = remainingSeries.map { subjectToTvShow(it) }))
            }

            sections
        }
    } catch (e: Exception) {
        Log.e(TAG, "getHome failed", e)
        emptyList()
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        if (page > 3) return emptyList()
        return try {
            val resp = service.getTrending(apiHeaders(), page = page - 1, perPage = 60)
            resp.data?.subjectList.orEmpty()
                .filter { it.subjectType == SUBJECT_MOVIE && isFrenchAvailable(it) }
                .map { subjectToMovie(it) }
        } catch (e: Exception) {
            Log.e(TAG, "getMovies($page) failed", e)
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page > 3) return emptyList()
        return try {
            val resp = service.getTrending(apiHeaders(), page = page - 1, perPage = 60)
            resp.data?.subjectList.orEmpty()
                .filter { it.subjectType == SUBJECT_SERIES && isFrenchAvailable(it) }
                .map { subjectToTvShow(it) }
        } catch (e: Exception) {
            Log.e(TAG, "getTvShows($page) failed", e)
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        // 2026-05-05 : query vide → liste les genres dispo (comme Movix/Papadustream).
        // Permet à l'utilisateur de browser par catégorie depuis l'écran de recherche.
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return MOVIEBOX_GENRES.map { (slug, fr) ->
                Genre(id = "mvbx::g::$slug", name = fr)
            }
        }
        if (page > 1) return emptyList()
        // 2026-05-05 : normalise la query (apostrophe typo, annotations parasites)
        val cleanQuery = com.streamflixreborn.streamflix.utils.TitleNormalizer
            .cleanForTmdbSearch(query).ifBlank { query }
        return try {
            val resp = service.search(
                apiHeaders(),
                MovieboxSearchBody(keyword = cleanQuery, page = 0, perPage = 30)
            )
            val items = resp.data?.items.orEmpty().filter { isFrenchAvailable(it) }
            // 2026-05-05 : debug log pour traquer les jaquettes manquantes
            if (items.isNotEmpty()) {
                val first = items.first()
                Log.d(TAG, "search('$cleanQuery'): ${items.size} items. First poster=${first.bestPoster()} cover=${first.cover?.url}")
            }
            items.map { s ->
                if (s.subjectType == SUBJECT_MOVIE) subjectToMovie(s) else subjectToTvShow(s)
            }
        } catch (e: Exception) {
            Log.e(TAG, "search($query) failed", e)
            emptyList()
        }
    }

    /**
     * 2026-05-05 : Liste des genres disponibles sur Moviebox (extraits de l'API
     * — le champ `genre` des subjects est une liste CSV en anglais). On affiche
     * les noms en français à l'utilisateur, mais on garde les slugs anglais
     * dans l'ID pour matcher l'API. Le filtrage par genre se fait dans
     * [getGenre] qui scanne trending et filtre par `subject.genre.contains(slug)`.
     */
    private val MOVIEBOX_GENRES = listOf(
        "Action" to "Action",
        "Adventure" to "Aventure",
        "Animation" to "Animation",
        "Biography" to "Biographie",
        "Comedy" to "Comédie",
        "Crime" to "Crime",
        "Documentary" to "Documentaire",
        "Drama" to "Drame",
        "Family" to "Famille",
        "Fantasy" to "Fantastique",
        "History" to "Historique",
        "Horror" to "Horreur",
        "Music" to "Musique",
        "Mystery" to "Mystère",
        "Romance" to "Romance",
        "Sci-Fi" to "Science-Fiction",
        "Sport" to "Sport",
        "Thriller" to "Thriller",
        "War" to "Guerre",
        "Western" to "Western",
    )

    /**
     * Fiche film. Récupère le détail complet via /detail?subjectId=X et
     * si un dub français existe, on switch sur le subjectId du dub FR pour
     * que les vidéos jouées soient en français natif.
     */
    override suspend fun getMovie(id: String): Movie {
        val subjectId = id.removePrefix("mvbx::m::")
        return try {
            val resp = service.getDetailById(apiHeaders(), subjectId).data?.subject
                ?: return Movie(id = id, title = "", providerName = name)

            // Si un dub français existe, on bascule potentiellement sur la version FR.
            // Pour l'instant on garde l'ID original et on expose la liste des dubs
            // dans `cast` (workaround : pas de champ dédié dans le model Movie).
            val frDub = resp.dubs?.firstOrNull { it.lanCode.equals("fr", ignoreCase = true) }
            val effective = if (frDub?.detailPath != null) {
                runCatching {
                    service.getDetail(apiHeaders(), frDub.detailPath).data?.subject
                }.getOrNull() ?: resp
            } else resp

            // Recommendations
            val recs = runCatching {
                service.getRecommendations(apiHeaders(), subjectId).data?.items.orEmpty()
                    .filter { isFrenchAvailable(it) }
                    .map { s ->
                        if (s.subjectType == SUBJECT_MOVIE) subjectToMovie(s) else subjectToTvShow(s)
                    }
            }.getOrNull().orEmpty()

            subjectToMovie(effective).apply {
                // Recopie sur le model Movie ne supporte pas .recommendations en setter
                // — workaround : on retourne le movie sans recommendations, l'écran
                // détail ré-exécutera getMovie si besoin (TODO si pertinent).
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMovie($id) failed", e)
            Movie(id = id, title = "", providerName = name)
        }
    }

    /**
     * Normalise un titre avant la lookup TMDB ou la search Moviebox.
     *
     * Vire :
     *   - Annotations crochets : "[Version française]", "[Dub]", etc.
     *   - Annotations parens : "(VF)", "(VOSTFR)", "(2024)", "(Saison 1)"
     *   - Suffixes langue : "VF", "VOSTFR", "VO" en fin de titre
     *   - Saison/Épisode : "Saison X", "Épisode Y", "S01E05"
     *   - Caractères Unicode typographiques (apostrophes, tirets, guillemets)
     *
     * Cette normalisation EST idempotente — on peut l'appeler plusieurs fois.
     */
    private fun normalizeTitleForTmdb(raw: String): String {
        // 2026-05-05 v3 : délègue à l'utilitaire partagé TitleNormalizer pour
        // qu'on ait UNE seule source de vérité utilisée par tous les providers.
        return com.streamflixreborn.streamflix.utils.TitleNormalizer.cleanForTmdbSearch(raw)
    }

    override suspend fun getTvShow(id: String): TvShow {
        val subjectId = id.removePrefix("mvbx::s::")
        return try {
            val data = service.getDetailById(apiHeaders(), subjectId).data
                ?: return TvShow(id = id, title = "", providerName = name)
            val subject = data.subject
                ?: return TvShow(id = id, title = "", providerName = name)

            // Préfère version FR-dub si dispo
            val frDub = subject.dubs?.firstOrNull { it.lanCode.equals("fr", ignoreCase = true) }
            val effective = if (frDub?.detailPath != null) {
                runCatching {
                    service.getDetail(apiHeaders(), frDub.detailPath).data?.subject
                }.getOrNull() ?: subject
            } else subject

            val mvbxSeasons = buildSeasons(
                detailPath = effective.detailPath ?: subject.detailPath ?: subjectId,
                subjectId = effective.subjectId ?: subjectId,
                resource = data.resource,
            )

            // 2026-05-05 : Enrichissement TMDB. La lookup TMDB se fait sur le
            // titre NORMALISÉ (sans annotations type "[Version française]"
            // qui cassent le match). Quand TMDB a + de saisons que Moviebox
            // (cas fréquent : Moviebox référence parfois 1 seule saison
            // alors que la série en a plusieurs), on adopte les saisons TMDB
            // pour offrir une navigation complète à l'utilisateur.
            // 2026-05-05 v3 : robuste — on essaie plusieurs variantes de titre
            // (normalisé, brut, original-vs-FR-dub) ET avec/sans année. Permet
            // de matcher TMDB même quand Moviebox renvoie un titre légèrement
            // différent (ex: "Wednesday" vs "Mercredi" selon endpoint).
            val rawTitle = effective.title.orEmpty()
            val normalizedTitle = normalizeTitleForTmdb(rawTitle)
            val year = effective.releaseDate?.take(4)?.toIntOrNull()
            val titleVariants = listOf(normalizedTitle, rawTitle, subject.title.orEmpty())
                .filter { it.isNotBlank() }
                .distinct()
            Log.d(TAG, "TMDB lookup attempt for '$rawTitle' (subjectId=$subjectId), variants=$titleVariants, year=$year")
            var tmdbShow: TvShow? = null
            for (variant in titleVariants) {
                tmdbShow = runCatching { TmdbUtils.getTvShow(title = variant, year = year, language = "fr-FR") }.getOrNull()
                if (tmdbShow != null) { Log.d(TAG, "TMDB matched on variant='$variant'"); break }
            }
            if (tmdbShow == null && year != null) {
                // Retry sans year filter — match parfois échoue quand Moviebox
                // a une mauvaise releaseDate (ex: 2022 vs TMDB 2023).
                for (variant in titleVariants) {
                    tmdbShow = runCatching { TmdbUtils.getTvShow(title = variant, year = null, language = "fr-FR") }.getOrNull()
                    if (tmdbShow != null) { Log.d(TAG, "TMDB matched on variant='$variant' (no year)"); break }
                }
            }

            if (tmdbShow != null) {
                tmdbShow.id.toIntOrNull()?.let { tmdbIdInt ->
                    synchronized(tmdbCacheLock) { movieboxToTmdbId[subjectId] = tmdbIdInt }
                }
                Log.d(TAG, "TMDB matched → tmdbId=${tmdbShow.id}, ${tmdbShow.seasons.size} seasons")
            } else {
                Log.w(TAG, "TMDB NO MATCH for any variant of '$rawTitle' (year=$year)")
            }

            // Stratégie saisons :
            //   - Si TMDB a >= 1 saison ET ≥ ce que Moviebox a, on adopte la liste
            //     TMDB (plus complète, posters par saison).
            //   - Sinon on garde Moviebox + on enrichit avec poster TMDB si match.
            val seasons = if (tmdbShow != null && tmdbShow.seasons.size >= mvbxSeasons.size) {
                tmdbShow.seasons.map { tmdbSeason ->
                    Season(
                        // Format ID Moviebox-compatible pour que getEpisodesBySeason
                        // route correctement. Le seasonNumber TMDB est préservé.
                        id = "mvbx::season::$subjectId::${tmdbSeason.number}",
                        number = tmdbSeason.number,
                        title = tmdbSeason.title?.takeIf { it.isNotBlank() }
                            ?: "Saison ${tmdbSeason.number}",
                        poster = tmdbSeason.poster,
                    )
                }
            } else {
                // Fallback : enrichit les Moviebox seasons avec posters TMDB par numéro
                val tmdbSeasonsByNumber = tmdbShow?.seasons?.associateBy { it.number }.orEmpty()
                mvbxSeasons.map { mvbxSeason ->
                    val tmdbSeason = tmdbSeasonsByNumber[mvbxSeason.number]
                    if (tmdbSeason != null) {
                        Season(
                            id = mvbxSeason.id,
                            number = mvbxSeason.number,
                            title = mvbxSeason.title,
                            poster = tmdbSeason.poster,
                        )
                    } else mvbxSeason
                }
            }

            TvShow(
                id = id,
                title = effective.title.orEmpty(),
                overview = (effective.description?.takeIf { it.isNotBlank() }) ?: tmdbShow?.overview,
                released = effective.releaseDate?.takeIf { it.isNotBlank() },
                trailer = effective.trailer?.videoAddress?.url,
                rating = effective.imdbRatingValue?.toDoubleOrNull() ?: tmdbShow?.rating,
                poster = effective.cover?.url ?: tmdbShow?.poster,
                banner = tmdbShow?.banner ?: effective.cover?.url,  // backdrop landscape preferred
                providerName = name,
                genres = parseGenres(effective.genre).ifEmpty { tmdbShow?.genres ?: emptyList() },
                seasons = seasons,
            )
        } catch (e: Exception) {
            Log.e(TAG, "getTvShow($id) failed", e)
            TvShow(id = id, title = "", providerName = name)
        }
    }

    /** Cache : Moviebox subjectId → TMDB id. Permet à getEpisodesBySeason et
     *  getServers de réutiliser le mapping établi par getTvShow sans refaire la
     *  recherche TMDB par title/year. */
    private val tmdbCacheLock = Any()
    private val movieboxToTmdbId = mutableMapOf<String, Int>()

    /**
     * Episodes par saison. Format season ID : `mvbx::season::<subjectId>::<seasonNumber>`.
     * On synthétise les épisodes depuis Moviebox (nombre d'épisodes via
     * `resource.seasons[].resolutions[].epNum`) puis on enrichit chaque épisode
     * avec son still + name + overview via TMDB (Moviebox API ne les expose pas).
     */
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // Parse "mvbx::season::<subjectId>::<seasonNumber>"
        val parts = seasonId.removePrefix("mvbx::season::").split("::")
        if (parts.size != 2) return emptyList()
        val subjectId = parts[0]
        val seasonNum = parts[1].toIntOrNull() ?: return emptyList()

        return try {
            val resource = service.getDetailById(apiHeaders(), subjectId).data?.resource
                ?: return emptyList()
            val rs = resource.seasons.orEmpty().firstOrNull { it.se == seasonNum }
                ?: return emptyList()

            val maxEp = rs.resolutions?.maxOfOrNull { it.epNum } ?: rs.maxEp
            if (maxEp <= 0) return emptyList()

            // Tente d'enrichir avec TMDB : le tmdbId est cache si getTvShow a été
            // appelé d'abord. Sinon on tente une lookup via subject title NORMALISÉ.
            val tmdbId = synchronized(tmdbCacheLock) { movieboxToTmdbId[subjectId] } ?: run {
                runCatching {
                    val subj = service.getDetailById(apiHeaders(), subjectId).data?.subject
                    val rawTitle = subj?.title.orEmpty()
                    val year = subj?.releaseDate?.take(4)?.toIntOrNull()
                    // 2026-05-05 v3 : essaye variantes normalisée/brute + retry sans year
                    val variants = listOf(normalizeTitleForTmdb(rawTitle), rawTitle).filter { it.isNotBlank() }.distinct()
                    var found: TvShow? = null
                    for (v in variants) { found = TmdbUtils.getTvShow(v, year, "fr-FR"); if (found != null) break }
                    if (found == null && year != null) {
                        for (v in variants) { found = TmdbUtils.getTvShow(v, null, "fr-FR"); if (found != null) break }
                    }
                    val resolvedId = found?.id?.toIntOrNull()
                    if (resolvedId != null) synchronized(tmdbCacheLock) { movieboxToTmdbId[subjectId] = resolvedId }
                    Log.d(TAG, "getEpisodesBySeason TMDB lookup '$rawTitle' year=$year → tmdbId=$resolvedId")
                    resolvedId
                }.getOrNull()
            }

            // Le seasonNumber TMDB peut différer du seasonNum Moviebox quand la
            // série a >1 saison sur TMDB mais 1 seule sur Moviebox. On utilise
            // direct le seasonNum reçu (déjà mappé par buildSeasons via TMDB list).
            val effectiveSeasonNum = seasonNum.takeIf { it > 0 } ?: 1

            val tmdbEpisodes = if (tmdbId != null) {
                runCatching {
                    TmdbUtils.getEpisodesBySeason(
                        tvShowId = tmdbId.toString(),
                        seasonNumber = effectiveSeasonNum,
                        language = "fr-FR",
                    )
                }.getOrNull().orEmpty()
            } else emptyList()

            val tmdbByNumber = tmdbEpisodes.associateBy { it.number }
            Log.d(TAG, "getEpisodesBySeason($seasonId) tmdbId=$tmdbId, " +
                "season=$effectiveSeasonNum, mvbxMaxEp=$maxEp, tmdbEps=${tmdbEpisodes.size}")

            // Si TMDB a des épisodes, on prend le max(maxEp Moviebox, count TMDB).
            // Cela permet de montrer TOUS les épisodes que TMDB connaît, même si
            // Moviebox n'a pas encore tous les épisodes en stream.
            val finalMaxEp = maxOf(maxEp, tmdbEpisodes.size)

            (1..finalMaxEp).map { epNum ->
                val tmdbEp = tmdbByNumber[epNum]
                Episode(
                    id = "mvbx::ep::$subjectId::${rs.se}::$epNum",
                    number = epNum,
                    title = tmdbEp?.title?.takeIf { it.isNotBlank() } ?: "Épisode $epNum",
                    poster = tmdbEp?.poster,  // still TMDB
                    overview = tmdbEp?.overview,
                    released = tmdbEp?.released?.let {
                        try {
                            "%04d-%02d-%02d".format(
                                it.get(java.util.Calendar.YEAR),
                                it.get(java.util.Calendar.MONTH) + 1,
                                it.get(java.util.Calendar.DAY_OF_MONTH),
                            )
                        } catch (_: Exception) { null }
                    },
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodesBySeason($seasonId) failed", e)
            emptyList()
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val genreName = id.removePrefix("mvbx::g::")
        // On récupère trending puis filter par genre + FR
        val items = runCatching {
            service.getTrending(apiHeaders(), page = (page - 1).coerceAtLeast(0), perPage = 60)
                .data?.subjectList.orEmpty()
                .filter { isFrenchAvailable(it) }
                .filter { (it.genre ?: "").contains(genreName, ignoreCase = true) }
                .map { s ->
                    if (s.subjectType == SUBJECT_MOVIE) subjectToMovie(s) else subjectToTvShow(s)
                }
        }.getOrNull().orEmpty()
        return Genre(id = id, name = genreName, shows = items)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        // L'API n'expose pas de page acteur publique — on retourne minimal.
        return People(id = id, name = "", filmography = emptyList())
    }

    /**
     * Construit un `Video.Server` qui pointe vers la page player Moviebox.
     * L'extraction réelle (WebView Nuxt + capture de l'URL signée `/bt/{hash}.mp4`)
     * se fait à la demande via `MovieboxExtractor` — partagé par tous les providers
     * qui pointent un `src` vers themoviebox.org / moviebox.ph.
     */
    private fun buildMovieboxServer(
        detailPath: String,
        subjectId: String,
        label: String = "Moviebox VF",
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): Video.Server {
        // 2026-05-05 v2 : injecte detailSe/detailEp depuis le videoType.
        // Sans ça, Moviebox ouvre la dernière S/E mémorisée par la session
        // web (souvent la dernière saison sortie au lieu du S/E demandé).
        val seParam = seasonNumber?.toString().orEmpty()
        val epParam = episodeNumber?.toString().orEmpty()
        val playerUrl = "https://themoviebox.org/fr/movies/$detailPath" +
            "?id=$subjectId&type=/movie/detail&detailSe=$seParam&detailEp=$epParam&lang=fr"
        // Préfixe l'ID avec saison/épisode pour éviter le cache cross-épisode
        // dans Extractor (sinon S1E1 et S5E1 partageraient le même cache hit).
        val cacheKey = if (seasonNumber != null && episodeNumber != null) {
            "moviebox_${subjectId}_s${seasonNumber}e${episodeNumber}"
        } else {
            "moviebox_${subjectId}"
        }
        return Video.Server(
            id = cacheKey,
            name = label,
            src = playerUrl,
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val subjectId = id
            .removePrefix("mvbx::m::")
            .removePrefix("mvbx::s::")
            .removePrefix("mvbx::ep::")
            .substringBefore("::")
        if (subjectId.isBlank() || subjectId == id) {
            Log.w(TAG, "getServers: subjectId not parseable from $id")
            return emptyList()
        }

        // Récupère detailPath (slug) via /detail pour construire l'URL player.
        // Si un dub FR existe on switch sur son detailPath pour avoir l'audio FR natif.
        val detail = runCatching {
            service.getDetailById(apiHeaders(), subjectId).data?.subject
        }.getOrNull() ?: return emptyList()

        val frDub = detail.dubs?.firstOrNull { it.lanCode.equals("fr", ignoreCase = true) }
        val effectiveDetailPath = frDub?.detailPath ?: detail.detailPath ?: return emptyList()
        val effectiveSubjectId = frDub?.subjectId ?: subjectId

        // Pour les épisodes, on injecte saison/épisode dans l'URL player.
        val (sNum, eNum) = when (videoType) {
            is Video.Type.Episode -> videoType.season.number to videoType.number
            else -> null to null
        }
        val moviebox = listOf(
            buildMovieboxServer(
                effectiveDetailPath,
                effectiveSubjectId,
                "Moviebox VF",
                seasonNumber = sNum,
                episodeNumber = eNum,
            )
        )

        // 2026-05-05 : Movix backup — quand on regarde depuis Moviebox, on ajoute
        // aussi les sources Movix (Filemoon/VOE/Uqload/etc.) si on trouve un match
        // par titre+année via TMDB. Ça donne au minimum 2 sources cliquables au
        // user (Moviebox direct + Movix multi-hosters) au lieu d'une seule.
        //
        // 2026-05-05 v2 : titre NORMALISÉ avant TMDB lookup (vire "[Version
        // française]", "(VF)", etc. sinon TMDB multi-search retourne 0 results
        // pour "Sonic the Hedgehog 3 [Version française]").
        val movixBackup = runCatching {
            val rawTitle = detail.title.orEmpty()
            val normalizedTitle = normalizeTitleForTmdb(rawTitle)
            val year = detail.releaseDate?.take(4)?.toIntOrNull()
            val tmdbId = synchronized(tmdbCacheLock) { movieboxToTmdbId[subjectId] } ?: run {
                // 2026-05-05 v3 : variantes + retry sans year (cf getTvShow)
                val variants = listOf(normalizedTitle, rawTitle).filter { it.isNotBlank() }.distinct()
                var foundId: String? = null
                for (v in variants) {
                    foundId = when (videoType) {
                        is Video.Type.Movie -> TmdbUtils.getMovie(v, year, "fr-FR")?.id
                        is Video.Type.Episode -> TmdbUtils.getTvShow(v, year, "fr-FR")?.id
                    }
                    if (foundId != null) break
                }
                if (foundId == null && year != null) {
                    for (v in variants) {
                        foundId = when (videoType) {
                            is Video.Type.Movie -> TmdbUtils.getMovie(v, null, "fr-FR")?.id
                            is Video.Type.Episode -> TmdbUtils.getTvShow(v, null, "fr-FR")?.id
                        }
                        if (foundId != null) break
                    }
                }
                val foundIdInt = foundId?.toIntOrNull()
                Log.d(TAG, "Movix backup TMDB lookup: raw='$rawTitle' → variants=$variants year=$year → tmdbId=$foundIdInt")
                if (foundIdInt != null) synchronized(tmdbCacheLock) { movieboxToTmdbId[subjectId] = foundIdInt }
                foundIdInt
            }
            if (tmdbId == null) {
                Log.w(TAG, "No TMDB match for '$normalizedTitle' (year=$year) — skip Movix backup")
                emptyList()
            } else when (videoType) {
                is Video.Type.Movie -> MovixProvider.getServers("$tmdbId", videoType)
                is Video.Type.Episode -> {
                    // 2026-05-05 v3 : MovixProvider lit `videoType.tvShow.id` pour
                    // tirer le tmdbId TV — mais ici on a un Episode venant de
                    // Moviebox, donc tvShow.id = `mvbx::s::xxxx` (pas un TMDB id).
                    // On reconstruit un Video.Type.Episode avec tvShow.id = tmdbId
                    // pour que Movix puisse appeler ses endpoints correctement.
                    val movixVideoType = videoType.copy(
                        tvShow = videoType.tvShow.copy(id = "$tmdbId")
                    )
                    val movixEpisodeId = "$tmdbId-s${videoType.season.number}e${videoType.number}"
                    Log.d(TAG, "Movix backup Episode: tmdbId=$tmdbId s${videoType.season.number}e${videoType.number}")
                    MovixProvider.getServers(movixEpisodeId, movixVideoType)
                }
            }
        }.getOrElse {
            Log.d(TAG, "Movix backup failed for moviebox $subjectId: ${it.message}")
            emptyList()
        }

        if (movixBackup.isNotEmpty()) {
            Log.d(TAG, "+ Movix backup pour Moviebox $subjectId : ${movixBackup.size} sources")
        }

        // 2026-05-05 : Coflix backup — site français qui agrège plusieurs hosters
        // (Lulustream, VOE, Vidoza, Darkibox, Veev, Goodstream...) par titre.
        // Ne dépend pas de TMDB (search par titre direct), donc ça marche même
        // quand la lookup TMDB échoue.
        val coflixBackup = runCatching {
            val cleanTitle = normalizeTitleForTmdb(detail.title.orEmpty())
            val year = detail.releaseDate?.take(4)?.toIntOrNull()
            when (videoType) {
                is Video.Type.Movie -> CoflixSourceProvider.getMovieSources(cleanTitle, year)
                is Video.Type.Episode -> CoflixSourceProvider.getEpisodeSources(
                    showTitle = cleanTitle,
                    year = year,
                    seasonNumber = videoType.season.number,
                    episodeNumber = videoType.number,
                )
            }
        }.getOrElse {
            Log.d(TAG, "Coflix backup failed: ${it.message}")
            emptyList()
        }
        if (coflixBackup.isNotEmpty()) {
            Log.d(TAG, "+ Coflix backup pour Moviebox $subjectId : ${coflixBackup.size} sources")
        }

        // 2026-05-05 v2 : ordre = NATIVE Moviebox → Movix backup → Coflix EN DERNIER.
        // Dedup par src (au cas où Movix backup contenait déjà des Coflix).
        val seen = mutableSetOf<String>()
        val ordered = (moviebox + movixBackup + coflixBackup)
            .filter { it.src.isBlank() || seen.add(it.src.lowercase().trim()) }
        return ordered
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // Délègue à l'extracteur générique (MovieboxExtractor) qui matche par URL
        return Extractor.extract(server.src, server)
    }

    // ─── Cross-provider integration ────────────────────────────────────────

    /**
     * Public — recherche par titre. Permet à FrenchStream / Movix / VoirAnime /
     * AnimeSama / Papadustream d'interroger Moviebox et de proposer ses sources
     * en backup quand un titre matche le leur.
     *
     * Filtre FR appliqué : seuls les subjects avec `subtitles` contenant
     * "Français" OU `dubs` avec `lanCode=fr` sont retournés.
     *
     * @param title Le titre du film/série à chercher.
     * @param max Nombre max de résultats (default 10).
     * @return Liste de `MovieboxSubject` matchant. Vide si rien trouvé ou erreur.
     */
    suspend fun searchByTitle(title: String, max: Int = 10): List<MovieboxSubject> {
        if (title.isBlank()) return emptyList()
        return try {
            service.search(apiHeaders(), MovieboxSearchBody(keyword = title, page = 0, perPage = max))
                .data?.items.orEmpty()
                .filter { isFrenchAvailable(it) }
        } catch (e: Exception) {
            Log.e(TAG, "searchByTitle($title) failed", e)
            emptyList()
        }
    }

    /**
     * Helper public pour les autres providers (FrenchStream, Movix, AnimeSama,
     * VoirAnime, etc.) — recherche un titre dans Moviebox et retourne directement
     * les `Video.Server` extractibles via `MovieboxExtractor`.
     *
     * Le matching est simple :
     * - Recherche par title sur Moviebox
     * - Filtre FR strict (subtitles ou dubs)
     * - Préfère le résultat dont le titre est le plus proche (Levenshtein-ish)
     * - Préfère la version avec dub FR si dispo (audio FR natif)
     * - Optionnellement filtre par année si `year` est fourni
     *
     * @param title Le titre à chercher (ex: "Avatar: Fire and Ash")
     * @param year Année de sortie pour disambiguer les remakes (ex: 2025)
     * @param subjectTypeFilter 1 pour movies-only, 2 pour series-only, null pour les deux.
     * @return Liste de `Video.Server` (max 1 par défaut, pour éviter le bruit).
     *         Vide si aucun match exploitable.
     */
    /**
     * Helper pour les providers qui exposent un ID TMDB. Résout le titre via
     * TMDB (langue fr-FR), puis appelle [getMovieboxSourcesByTitle].
     *
     * @param tmdbId L'ID TMDB du film/série
     * @param videoType Le type pour savoir si on cherche un film ou une série
     * @return Sources Moviebox extractibles, ou empty list si aucun match.
     */
    suspend fun getMovieboxSourcesByTmdbId(
        tmdbId: Int,
        videoType: Video.Type,
    ): List<Video.Server> {
        return try {
            val (title, year, type) = when (videoType) {
                is Video.Type.Movie -> {
                    val det = com.streamflixreborn.streamflix.utils.TMDb3.Movies
                        .details(movieId = tmdbId, language = "fr-FR")
                    Triple(
                        det.title.takeIf { it.isNotBlank() } ?: det.originalTitle,
                        det.releaseDate?.take(4)?.toIntOrNull(),
                        SUBJECT_MOVIE
                    )
                }
                is Video.Type.Episode -> {
                    val det = com.streamflixreborn.streamflix.utils.TMDb3.TvSeries
                        .details(seriesId = tmdbId, language = "fr-FR")
                    Triple(
                        det.name.takeIf { it.isNotBlank() } ?: det.originalName,
                        det.firstAirDate?.take(4)?.toIntOrNull(),
                        SUBJECT_SERIES
                    )
                }
            }
            // Pour un Episode on propage saison/épisode pour que l'URL Moviebox
            // ouvre le bon couple (sinon le site web fallback à la dernière S/E
            // mémorisée par la session).
            val (sNum, eNum) = when (videoType) {
                is Video.Type.Episode -> videoType.season.number to videoType.number
                else -> null to null
            }
            if (title.isBlank()) emptyList()
            else getMovieboxSourcesByTitle(title, year, type, seasonNumber = sNum, episodeNumber = eNum)
        } catch (e: Exception) {
            Log.d(TAG, "getMovieboxSourcesByTmdbId($tmdbId) failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getMovieboxSourcesByTitle(
        title: String,
        year: Int? = null,
        subjectTypeFilter: Int? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): List<Video.Server> {
        if (title.isBlank()) return emptyList()
        // 2026-05-05 v2 : self-normalisation. Garantit qu'aucun caller ne passe
        // un titre pollué (annotations Saison/Épisode/VOSTFR/Unicode chars/etc.)
        // qui ferait foirer la search Moviebox côté serveur.
        val normalizedTitle = normalizeTitleForTmdb(title)
        if (normalizedTitle.isBlank()) {
            Log.d(TAG, "getMovieboxSourcesByTitle: title became blank after normalization '$title'")
            return emptyList()
        }
        return try {
            val candidates = service.search(
                apiHeaders(),
                MovieboxSearchBody(keyword = normalizedTitle, page = 0, perPage = 10, subjectType = subjectTypeFilter)
            ).data?.items.orEmpty()
                .filter { isFrenchAvailable(it) }
                .filter { it.hasResource }

            if (candidates.isEmpty()) {
                Log.d(TAG, "getMovieboxSourcesByTitle('$title' year=$year): aucun candidat FR")
                return emptyList()
            }

            // 2026-05-05 v2 : matching STRICT — pareil que Moiflix/Yflix/Coflix.
            // Avant : substring 50 → "The Boys" matchait "The Boyfriend".
            // Maintenant : exact (100) ou word-for-word + longueur ≤30% (90/80).
            // Pénalité -50 si écart d'année > 2.
            val normalizedTarget = title.lowercase().trim()
                .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            val targetWords = normalizedTarget.split(" ").filter { it.length > 1 }.toSet()
            val scored = candidates.map { s ->
                val sTitle = (s.title ?: "").lowercase().trim()
                    .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
                val sWords = sTitle.split(" ").filter { it.length > 1 }.toSet()
                val lenDiffPct = if (kotlin.math.max(sTitle.length, normalizedTarget.length) > 0)
                    kotlin.math.abs(sTitle.length - normalizedTarget.length).toDouble() /
                        kotlin.math.max(sTitle.length, normalizedTarget.length)
                else 0.0
                var score = when {
                    sTitle == normalizedTarget -> 100
                    targetWords.isNotEmpty() && sWords.containsAll(targetWords) && lenDiffPct <= 0.30 -> 90
                    sWords.isNotEmpty() && targetWords.containsAll(sWords) && lenDiffPct <= 0.30 -> 80
                    else -> 0
                }
                val sYear = s.releaseDate?.take(4)?.toIntOrNull()
                if (year != null && sYear == year) score += 30
                else if (year != null && sYear != null && kotlin.math.abs(sYear - year) > 2) score -= 50
                score += (s.imdbRatingValue?.toFloatOrNull() ?: 0f).toInt()
                s to score
            }
            // Seuil 90 : pas de fallback substring approximatif
            val best = scored.filter { it.second >= 90 }
                .sortedByDescending { it.second }
                .firstOrNull()?.first
            if (best == null) {
                val bestRejected = scored.maxByOrNull { it.second }
                Log.d(TAG, "getMovieboxSourcesByTitle('$title' year=$year): pas de match fiable " +
                    "(meilleur='${bestRejected?.first?.title}' score=${bestRejected?.second})")
                return emptyList()
            }

            // Préférer le dub FR si dispo (charge le détail pour récupérer dubs[])
            val frDubMatch = runCatching {
                val full = service.getDetailById(apiHeaders(), best.subjectId.orEmpty()).data?.subject
                full?.dubs?.firstOrNull { it.lanCode.equals("fr", ignoreCase = true) }
            }.getOrNull()

            val effectiveDetailPath = frDubMatch?.detailPath ?: best.detailPath ?: return emptyList()
            val effectiveSubjectId = frDubMatch?.subjectId ?: best.subjectId.orEmpty()

            val label = if (frDubMatch != null) "Moviebox [VF]" else "Moviebox [VOSTFR]"
            listOf(
                buildMovieboxServer(
                    effectiveDetailPath,
                    effectiveSubjectId,
                    label,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "getMovieboxSourcesByTitle('$title') failed", e)
            emptyList()
        }
    }
}
