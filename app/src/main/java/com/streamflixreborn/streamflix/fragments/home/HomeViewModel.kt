package com.streamflixreborn.streamflix.fragments.home

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
// WarmUpCapable retiré avec WiTV
import com.streamflixreborn.streamflix.ui.UserDataNotifier
import com.streamflixreborn.streamflix.utils.EnrichmentTrigger
import com.streamflixreborn.streamflix.utils.HomeCacheStore
import com.streamflixreborn.streamflix.utils.ParentalControlUtils
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.UserDataCache
import com.streamflixreborn.streamflix.utils.UserDataCache.toCached
import com.streamflixreborn.streamflix.utils.UserDataCache.toEpisode
import com.streamflixreborn.streamflix.utils.UserDataCache.toMovie
import com.streamflixreborn.streamflix.utils.UserDataCache.toTvShow
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class HomeViewModel(database: AppDatabase) : ViewModel() {

    private fun <T> preserveCacheOrder(
        cached: List<T>,
        incoming: List<T>,
        idOf: (T) -> String,
    ): List<T> {
        val incomingById = incoming.associateBy(idOf)
        val orderedExisting = cached.mapNotNull { cachedItem -> incomingById[idOf(cachedItem)] }
        val appendedNew = incoming.filter { incomingItem ->
            cached.none { cachedItem -> idOf(cachedItem) == idOf(incomingItem) }
        }
        return orderedExisting + appendedNew
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    private val continueWatchingTvShowCache = ConcurrentHashMap<String, TvShow>()
    private val continueWatchingSeasonEpisodesCache = ConcurrentHashMap<String, List<Episode>>()
    private val _userDataCache = MutableStateFlow<UserDataCache.UserData?>(null)
    private var currentProvider: Provider? = null

    /** True once getHome() has been called at least once (prevents re-load on view recreation). */
    var hasLoaded: Boolean = false
        private set

    /** Tracks the in-flight getHome() job so we can cancel any previous one
     *  before launching a new one. Without this, two ProviderChangeNotifier
     *  bursts at startup (or any race) would launch concurrent enrichments,
     *  each emitting its own category list — the UI then flickers between
     *  "Séries Récentes" / "Films Récents" / etc. for the same row. */
    private var homeJob: kotlinx.coroutines.Job? = null

    companion object {
        // 2026-07-04 (user "comment ça se fait que le home continue à charger alors qu'on est
        //   déjà dans une vidéo ? à partir du moment où on est dans une vidéo il n'y a plus lieu
        //   de charger un home") : ref GLOBALE du job d'enrichissement home en cours. L'enrichis-
        //   sement (≈150 appels TMDB + scraping genres via le moteur WebJs) tournait ENCORE en
        //   fond pendant la lecture et VOLAIT les ressources (WebView/CPU/réseau) au scrape natif
        //   des serveurs → sur la TV low-RAM le natif sortait en ~15s au lieu de 2s. Le player
        //   appelle pauseEnrichmentForPlayback() au démarrage de la lecture → ressources libérées.
        @Volatile private var activeEnrichmentJob: kotlinx.coroutines.Job? = null

        /** Annule l'enrichissement home en cours (appelé quand on entre dans le player). */
        fun pauseEnrichmentForPlayback() {
            activeEnrichmentJob?.let {
                if (it.isActive) {
                    it.cancel()
                    android.util.Log.i("HomeViewModel", "Enrichissement home ANNULÉ (entrée player) — libère les ressources pour le scrape natif")
                }
            }
            activeEnrichmentJob = null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,

        // CONTINUE WATCHING - Cache-first (faster on slow DB devices), falls back to DB
        combine(
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingMovies.isNotEmpty()) {
                    emit(cache.continueWatchingMovies.map { it.toMovie() })
                } else {
                    // 2026-07-09 : .catch → évite le crash « connection pool has been closed »
                    //   quand la DB est fermée (changement de provider) pendant que ce flow tourne.
                    emitAll(database.movieDao().getWatchingMovies().catch { emit(emptyList()) })
                }
            }.flowOn(Dispatchers.IO),
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingEpisodes.isNotEmpty()) {
                    emit(cache.continueWatchingEpisodes.map { it.toEpisode() })
                } else {
                    emitAll(database.episodeDao().getWatchingEpisodes().catch { emit(emptyList()) })
                }
            }.flowOn(Dispatchers.IO),
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingEpisodes.isNotEmpty()) {
                    emit(cache.continueWatchingEpisodes.map { it.toEpisode() })
                } else {
                    emitAll(database.episodeDao().getNextEpisodesToWatch()
                        .catch { emit(emptyList()) })
                }
            }.flowOn(Dispatchers.IO),
            database.tvShowDao().getAll().flowOn(Dispatchers.IO),
        ) { watchingMovies, watchingEpisodes, watchNextEpisodes, tvShows ->

            val allEpisodes = (watchingEpisodes + watchNextEpisodes)
                .distinctBy { it.id }

            val seasonIds = allEpisodes.mapNotNull { it.season?.id }.distinct()

            val tvShowsMap = tvShows.associateBy { it.id }

            val seasonsMap = if (seasonIds.isEmpty()) {
                emptyMap()
            } else {
                database.seasonDao()
                    .getByIds(seasonIds)
                    .associateBy { it.id }
            }

            val enrichedEpisodes = enrichContinueWatchingEpisodes(
                episodes = allEpisodes.map { episode ->
                    episode.copy(
                        tvShow = episode.tvShow?.id?.let { tvShowsMap[it] } ?: episode.tvShow,
                        season = episode.season?.id?.let { seasonsMap[it] } ?: episode.season,
                    ).apply {
                        merge(episode)
                    }
                }
            )

            val orderIndex = buildMap<String, Int> {
                _userDataCache.value?.continueWatchingMovies?.forEachIndexed { index, cached ->
                    put("movie:${cached.id}", index)
                }
                _userDataCache.value?.continueWatchingEpisodes?.forEachIndexed { index, cached ->
                    put("episode:${cached.id}", index)
                }
            }

            (watchingMovies + enrichedEpisodes)
                .sortedByDescending { item ->
                    when (item) {
                        is Movie -> item.watchHistory?.lastEngagementTimeUtcMillis
                            ?: item.watchedDate?.timeInMillis
                            ?: 0L
                        is Episode -> item.watchHistory?.lastEngagementTimeUtcMillis
                            ?: item.watchedDate?.timeInMillis
                            ?: 0L
                        else -> 0L
                    }
                } as List<AppAdapter.Item>
        }.flowOn(Dispatchers.IO),

        // FAVORITES - from cache first, DB as fallback
        _userDataCache.transformLatest { cache ->
            if (cache != null && cache.favoritesMovies.isNotEmpty()) {
                emit(cache.favoritesMovies.map { it.toMovie() })
            } else {
                emitAll(database.movieDao().getFavorites())
            }
        }.flowOn(Dispatchers.IO),
        _userDataCache.transformLatest { cache ->
            if (cache != null && cache.favoritesTvShows.isNotEmpty()) {
                emit(cache.favoritesTvShows.map { it.toTvShow() })
            } else {
                emitAll(database.tvShowDao().getFavorites())
            }
        }.flowOn(Dispatchers.IO),

        // MOVIES DB
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.categories
                        .flatMap { it.list }
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.movieDao().getByIds(movies.map { it.id }))
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        }.flowOn(Dispatchers.IO),

        // TV SHOWS DB
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val tvShows = state.categories
                        .flatMap { it.list }
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.tvShowDao().getByIds(tvShows.map { it.id }))
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        }.flowOn(Dispatchers.IO),

        ) { state, continueWatching, favoritesMovies, favoriteTvShows, moviesDb, tvShowsDb ->

        when (state) {
            is State.SuccessLoading -> {

                val moviesMap = moviesDb.associateBy { it.id }
                val tvShowsMap = tvShowsDb.associateBy { it.id }

                fun mergeItem(item: AppAdapter.Item): AppAdapter.Item {
                    return when (item) {
                        is Movie -> moviesMap[item.id]
                            ?.takeIf { !item.isSame(it) }
                            ?.let { item.copy().merge(it) }
                            ?: item

                        is TvShow -> tvShowsMap[item.id]
                            ?.takeIf { !item.isSame(it) }
                            ?.let { item.copy().merge(it) }
                            ?: item

                        else -> item
                    }
                }

                val categories = ParentalControlUtils.filterCategories(listOfNotNull(

                    // FEATURED
                    state.categories
                        .find { it.name == Category.FEATURED }
                        ?.let { category ->
                            category.copy(
                                list = category.list.map(::mergeItem)
                            )
                        },

                    // CONTINUE WATCHING
                    Category(
                        name = Category.CONTINUE_WATCHING,
                        list = continueWatching
                            .sortedByDescending {
                                when (it) {
                                    is Episode -> it.watchHistory?.lastEngagementTimeUtcMillis
                                        ?: it.watchedDate?.timeInMillis
                                        ?: 0L

                                    is Movie -> it.watchHistory?.lastEngagementTimeUtcMillis
                                        ?: it.watchedDate?.timeInMillis
                                        ?: 0L

                                    else -> 0L
                                }
                            }
                            .distinctBy {
                                when (it) {
                                    is Episode -> it.tvShow?.id
                                    is Movie -> it.id
                                    else -> null
                                }
                            },
                    ),

                    // FAVORITES
                    Category(
                        name = Category.FAVORITE_MOVIES,
                        list = favoritesMovies.sortedByDescending {
                            when (it) {
                                is Movie -> it.favoritedAtMillis ?: 0L
                                else -> 0L
                            }
                        },
                    ),
                    Category(
                        name = Category.FAVORITE_TV_SHOWS,
                        list = favoriteTvShows.sortedByDescending {
                            when (it) {
                                is TvShow -> it.favoritedAtMillis ?: 0L
                                else -> 0L
                            }
                        },
                    ),
                ) + state.categories
                    .filter { it.name != Category.FEATURED }
                    .map { category ->
                        category.copy(
                            list = category.list.map(::mergeItem)
                        )
                    })

                State.SuccessLoading(categories)
            }

            else -> state
        }
    }.flowOn(Dispatchers.IO)

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val categories: List<Category>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        val initialProvider = UserPreferences.currentProvider
        if (initialProvider != null) {
            currentProvider = initialProvider
            loadUserDataCache(initialProvider)
        }
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getHome()
            }
        }

        viewModelScope.launch {
            UserDataNotifier.updates.collect {
                val provider = UserPreferences.currentProvider ?: return@collect
                loadUserDataCache(provider)
            }
        }
        getHome()
    }

    private suspend fun enrichContinueWatchingEpisodes(episodes: List<Episode>): List<Episode> = coroutineScope {
        val provider = UserPreferences.currentProvider ?: return@coroutineScope episodes

        episodes.map { episode ->
            async {
                val tvShowId = episode.tvShow?.id ?: return@async episode
                val resolvedTvShow = continueWatchingTvShowCache[tvShowId] ?: runCatching {
                    provider.getTvShow(tvShowId)
                }.getOrNull()?.also { fetchedTvShow ->
                    continueWatchingTvShowCache[tvShowId] = fetchedTvShow
                }

                val mergedTvShow = resolvedTvShow?.copy().apply {
                    this?.let { show ->
                        episode.tvShow?.let { existingTvShow -> show.merge(existingTvShow) }
                    }
                } ?: episode.tvShow

                val resolvedSeason = episode.season?.let { season ->
                    mergedTvShow?.seasons?.firstOrNull { it.id == season.id || it.number == season.number }
                        ?: season
                }

                val resolvedEpisode = if (UserPreferences.enableTmdb) {
                    val seasonId = resolvedSeason?.id
                        ?: episode.season?.id
                    seasonId?.let { key ->
                        continueWatchingSeasonEpisodesCache[key] ?: runCatching {
                            provider.getEpisodesBySeason(key)
                        }.getOrDefault(emptyList()).also { fetchedEpisodes ->
                            if (fetchedEpisodes.isNotEmpty()) {
                                continueWatchingSeasonEpisodesCache[key] = fetchedEpisodes
                            }
                        }
                    }?.firstOrNull { seasonEpisode ->
                        seasonEpisode.id == episode.id || seasonEpisode.number == episode.number
                    }
                } else {
                    null
                }

                episode.copy(
                    title = resolvedEpisode?.title ?: episode.title,
                    overview = resolvedEpisode?.overview ?: episode.overview,
                    poster = resolvedEpisode?.poster ?: episode.poster,
                    tvShow = mergedTvShow,
                    season = resolvedSeason,
                ).apply {
                    merge(episode)
                }
            }
        }.awaitAll()
    }

    /** 2026-05-16 : provider en cours de chargement, set DÈS le launch (pas
     *  depuis getHomeInternal qui peut être schedulé après plusieurs calls).
     *  Sans ça, le check `currentProvider == newProvider` retourne false la
     *  1ère fois parce que getHomeInternal n'a pas encore set currentProvider. */
    @Volatile
    private var currentlyLoadingProvider: String? = null

    /** 2026-06-10 : dernière sequence du forceRelaunch acknowledgée par CE
     *  ViewModel (= pattern epoch pour gérer multi-collectors). */
    private var lastSeenForceSequence: Long = 0L

    fun getHome() {
        // Cancel any previous getHome() — without this, init + ProviderChangeNotifier
        // can race and launch 2-4 concurrent enrichments that each rebuild the
        // home with different category labels at the same row, causing the UI
        // to flicker between "Séries Récentes" and "Films Récents" indefinitely.
        //
        // 2026-05-16 (user "dessin animé page noire pas de chargement, autres
        // providers instantanés") : sur DessinAnime le getHome() prend ~30-50s
        // (Cloudflare lent). Si init + ProviderChangeNotifier firent 2-3 events
        // dans cette fenêtre, on cancel-relance en boucle et l'user ne voit
        // JAMAIS le résultat. Fix : si un job est déjà actif pour le MÊME
        // provider, on ne le cancel pas — on le laisse finir.
        val newProviderKey = UserPreferences.currentProvider?.name
        val activeJob = homeJob
        // 2026-05-27 : AnimeSama = TOUJOURS relancer (CF Turnstile exige un
        // cycle frais à chaque ouverture, le job précédent peut avoir émis du
        // vieux cache ou un état CF périmé). Les autres providers gardent le
        // skip pour éviter les cancel-boucles (Cloudflare lent).
        // 2026-06-10 (user "sur mobile la home galère à s'afficher quand on
        //   change de source") : pattern "epoch" — chaque ViewModel garde
        //   sa propre dernière sequence vue. Si la sequence globale a
        //   incrémenté → forceRelaunch. Pas de race condition entre plusieurs
        //   ViewModels qui consomment le même flag.
        val forceFromNotifier = ProviderChangeNotifier.shouldForceRelaunch(lastSeenForceSequence)
        if (forceFromNotifier) lastSeenForceSequence = ProviderChangeNotifier.forceRelaunchSequence
        val forceRelaunch = newProviderKey == "AnimeSama" || forceFromNotifier
        if (!forceRelaunch && activeJob != null && activeJob.isActive && currentlyLoadingProvider == newProviderKey) {
            android.util.Log.d("HomeViewModel", "getHome() skip — job déjà actif pour $newProviderKey")
            return
        }
        currentlyLoadingProvider = newProviderKey
        homeJob?.cancel()
        homeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                getHomeInternal()
            } finally {
                if (activeEnrichmentJob === homeJob) activeEnrichmentJob = null
                // Reset uniquement si on est encore le "currently loading"
                // — sinon un autre call concurrent a déjà repris.
                if (currentlyLoadingProvider == newProviderKey) {
                    currentlyLoadingProvider = null
                }
            }
        }
        // 2026-07-04 : expose le job d'enrichissement pour que le player puisse l'annuler.
        activeEnrichmentJob = homeJob
    }

    private suspend fun getHomeInternal() {
        val t0 = System.currentTimeMillis()
        hasLoaded = true
        val provider = UserPreferences.currentProvider ?: run {
            _state.emit(State.FailedLoading(IllegalStateException("No provider selected")))
            return
        }
        currentProvider = provider

        // 2026-06-23 (user "différer le chargement pour home instantané") :
        //   AVANT, on bloquait sur URL refresh (~3s) AVANT même de lire le cache.
        //   Maintenant : on lit le cache D'ABORD, on émet, et le wait URL ne
        //   s'applique QUE si on n'a pas de cache récent. Si cache récent, le
        //   refresh URL continue en BG sans bloquer.
        val appContext = StreamFlixApp.instance.applicationContext
        Log.d("HomeBoot", "[${provider.name}] start +${System.currentTimeMillis() - t0}ms")
        val cachedCategories = HomeCacheStore.read(appContext, provider)
        val cacheAgeForFastPath = HomeCacheStore.ageMs(appContext, provider)
        val hasRecentCache = !cachedCategories.isNullOrEmpty() && cacheAgeForFastPath != null && cacheAgeForFastPath < 30 * 60 * 1000L
        Log.d("HomeBoot", "[${provider.name}] cache read +${System.currentTimeMillis() - t0}ms (categories=${cachedCategories?.size ?: 0}, age=${cacheAgeForFastPath?.div(1000)}s)")

        // Attendre URL refresh UNIQUEMENT si on n'a pas de cache récent
        val refreshJob = StreamFlixApp.instance.providerUrlRefreshJob
        if (refreshJob != null && refreshJob.isActive) {
            if (hasRecentCache) {
                Log.d("HomeBoot", "[${provider.name}] URL refresh in BG (cache récent, pas d'attente)")
            } else {
                Log.d("HomeBoot", "[${provider.name}] waiting for URL refresh…")
                withTimeoutOrNull(15_000L) { refreshJob.join() }
                Log.d("HomeBoot", "[${provider.name}] URL refresh done (or timed out) +${System.currentTimeMillis() - t0}ms")
            }
        }

        // 2026-05-22 : si le provider a un warm-up (ex. WiTV v2 : scan OLA),
        // NE PAS servir le cache disque tant que le warm-up n'est pas fini.
        // Sinon l'user voit les chaînes (cache) et clique avant que les
        // streams soient pré-cachés → lancement lent.
        val warmUpPending = false // WarmUpCapable retiré avec WiTV

        // 2026-06-23 (user "AnimeSama met toujours du temps à s'ouvrir") :
        //   STALE-WHILE-REVALIDATE. Avant : pour AnimeSama on bloquait sur
        //   Loading pendant le CF bypass (~5-20s). Maintenant : on affiche le
        //   cache (29 cat dispo) instantanément, le bypass + getHome se font
        //   en BG, l'UI update quand getHome finit. Trade-off : si l'user
        //   clique vite sur une fiche avant la fin du bypass, un captcha
        //   peut apparaître à ce moment-là (= comportement existant).
        if (!cachedCategories.isNullOrEmpty() && !warmUpPending) {
            _state.emit(State.SuccessLoading(cachedCategories))
            Log.d("HomeBoot", "[${provider.name}] emit cache +${System.currentTimeMillis() - t0}ms (stale-while-revalidate)")
        } else {
            _state.emit(State.Loading)
            if (warmUpPending) Log.d("HomeBoot", "[${provider.name}] warm-up pending → Loading (no cache)")
            else Log.d("HomeBoot", "[${provider.name}] no cache → Loading")
        }

        loadUserDataCache(provider)

        // Skip le network refresh si le cache home est récent.
        // TTL variable par provider : les sites lents (scrape Cloudflare) ont
        // un TTL plus long pour éviter le re-scrape qui bloque l'ouverture.
        // Pull-to-refresh ou clear cache pour forcer un refresh.
        // NE PAS skip si warm-up pending (le provider.getHome() doit tourner
        // pour que le warm-up bloquant s'exécute).
        val cacheAgeMs = HomeCacheStore.ageMs(appContext, provider)
        // 2026-06-20 (user "le TV Hub ne se rafraîchit pas à son ouverture") :
        //   si le cache TV Hub contient une section critique vide (OTF / Adrar),
        //   considère que le cache est mort → force refresh. Sans ça, un fetch
        //   OTF/Adrar échoué (= timeout 4s) caché 30 min affichait un dossier
        //   vide jusqu'au refresh manuel.
        val tvHubMissingCritical = provider is com.streamflixreborn.streamflix.providers.LiveTvHubProvider &&
            !cachedCategories.isNullOrEmpty() &&
            cachedCategories.none { c -> c.name.startsWith("OTF TV") }
        val cacheTtlMs = when {
            tvHubMissingCritical -> 0L
            // Providers lents (Cloudflare, scrape) → 30 min
            provider.name.contains("Anime", ignoreCase = true) ||
            provider.name.contains("Dessin", ignoreCase = true) ||
            provider.name.contains("Franime", ignoreCase = true) ||
            provider.name.contains("Manga", ignoreCase = true) -> 30 * 60 * 1000L
            // IPTV → 30 min (le provider gère déjà son cache interne 30 min,
            // pas besoin de re-itérer 6000+ chaînes à chaque tab switch)
            provider is com.streamflixreborn.streamflix.providers.IptvProvider -> 30 * 60 * 1000L
            // Providers normaux → 5 min
            else -> 5 * 60 * 1000L
        }
        if (tvHubMissingCritical) {
            Log.w("HomeBoot", "[${provider.name}] cache missing OTF section → force refresh")
        }
        // 2026-06-23 : avec stale-while-revalidate, le cache est servi tout
        //   de suite. On peut skip le réseau pour AnimeSama aussi si cache
        //   récent (le CF check sera fait au prochain refresh).
        // 2026-06-28 (user "c'est redevenu long et plus instantané et quand
        //   je fais retour et que je reviens ça refait des chargements") :
        //   retour au SKIP cache standard pour TOUS les providers (= WebJsProvider
        //   inclus). Plus de re-fetch automatique. Le cache est servi instantanément
        //   sur back-revient. Refresh manuel via bouton si nécessaire.
        // 2026-06-30 (user "un refresh à l'ouverture suffit en cas de jaquette
        //   grise") : les providers CF (Wiflix, French Anime, DessinAnime…) relancent
        //   UN getHome à l'ouverture → bypass → cookie/session frais → les jaquettes
        //   se rechargent. Le cache est déjà affiché (stale-while-revalidate), donc
        //   c'est invisible. ⚠ Le bypass est en mode léger (retry=1) pour ne pas
        //   créer de rafale qui ferait throttler l'IP par Cloudflare.
        // 2026-07-04 (user "tu sors tu reviens tu te retapes une minute de chargement alors que
        //   le bypass est déjà réchauffé, c'est un gros bug") : DessinAnime RETIRÉ de cette liste.
        //   Il forçait un getHome() complet (bypass + rechargement home 378 Ko = ~11s) à CHAQUE
        //   ouverture → jamais de SKIP cache → rechargement visible à chaque retour. Ses jaquettes
        //   sont des images TMDB (zéro CF), donc pas besoin de re-bypasser pour elles ; et le
        //   cf_clearance est déjà pré-chauffé au boot + gardé au chaud. Résultat : re-entrée =
        //   SKIP network = cache instantané.
        val needsFreshCfBypass = (provider.name.contains("Anime", ignoreCase = true) &&
                !provider.name.contains("Dessin", ignoreCase = true)) ||
            provider.name.contains("Wiflix", ignoreCase = true) ||
            provider.name.contains("Franime", ignoreCase = true) ||
            provider.name.contains("Manga", ignoreCase = true)
        if (!needsFreshCfBypass && !warmUpPending && !cachedCategories.isNullOrEmpty() && cacheAgeMs != null && cacheAgeMs < cacheTtlMs) {
            Log.d("HomeBoot", "[${provider.name}] SKIP network (cache age ${cacheAgeMs / 1000}s, TTL ${cacheTtlMs / 60000}min) total=${System.currentTimeMillis() - t0}ms")
            return
        }

        try {
            Log.d("HomeBoot", "[${provider.name}] getHome() START +${System.currentTimeMillis() - t0}ms (cacheAge=${cacheAgeMs?.div(1000)}s)")
            // 2026-06-10 (user "chargement différé qui arrive au fur et à
            //   mesure") : si le provider implémente ProgressiveHomeProvider
            //   on collecte le flow et émet à chaque update incrémentale.
            //   Sinon comportement classique (1 appel bloquant + 1 émission).
            if (provider is com.streamflixreborn.streamflix.providers.ProgressiveHomeProvider) {
                var lastSnapshot: List<com.streamflixreborn.streamflix.models.Category>? = null
                provider.getHomeProgressive().collect { snapshot ->
                    lastSnapshot = snapshot
                    _state.emit(State.SuccessLoading(snapshot.toMutableList()))
                }
                lastSnapshot?.let { HomeCacheStore.write(appContext, provider, it) }
                Log.d("HomeBoot", "[${provider.name}] progressive END +${System.currentTimeMillis() - t0}ms (categories=${lastSnapshot?.size ?: 0})")
                return
            }
            val categories = provider.getHome().toMutableList()
            Log.d("HomeBoot", "[${provider.name}] getHome() END +${System.currentTimeMillis() - t0}ms (categories=${categories.size})")

            // 2026-07-04 : garde-fou PROPORTIONNEL + minimum absolu.
            //   L'ancien seuil fixe (- 3 cats) bloquait TOUT quand le scrape
            //   revenait avec 11 cats au lieu de 19 (gap = 8 > 3).
            //   Nouveau : on accepte le résultat frais si :
            //     (a) pas de cache existant, OU
            //     (b) ≥ 3 catégories fraîches (résultat pas totalement vide), OU
            //     (c) fraîches ≥ 40% du cache (baisse proportionnelle tolérable).
            //   Bloquer QUE les vrais échecs (0-2 cats = erreur réseau pure).
            val shouldWriteCache = cachedCategories.isNullOrEmpty()
                || categories.size >= 3
                || categories.size >= (cachedCategories.size * 2) / 5
            val providerSupport = Provider.Companion.providers[provider]
            val shouldEnrich = providerSupport?.enrichHome ?: true
            val cacheAlreadyShown = !cachedCategories.isNullOrEmpty()

            if (shouldWriteCache) {
                HomeCacheStore.write(appContext, provider, categories)
            } else {
                Log.w("HomeBoot", "[${provider.name}] partial result (${categories.size} cats) < cache (${cachedCategories.size}) — NOT overwriting cache")
            }
            // 2026-07-04 : ne PAS re-émettre le getHome brut si le cache était déjà
            //   affiché ET que l'enrichissement va suivre. Avant, cette émission
            //   intermédiaire faisait disparaître les catégories enrichment du cache
            //   (Films Récents, Séries Populaires…) puis l'enrichissement les ramenait
            //   ~20s plus tard → flickering des jaquettes "apparaître/disparaître/réapparaître".
            //   Le cache reste visible, l'enrichissement émettra le résultat final.
            // 2026-06-28 (user "le home perd ses catégories 4 → 2") : si le
            //   re-fetch est partiel ET qu'on avait un cache émis, on PRÉSERVE
            //   l'état actuel pour éviter le flicker / la perte de catégories.
            if (shouldWriteCache && (!cacheAlreadyShown || !shouldEnrich)) {
                _state.emit(State.SuccessLoading(categories))
            } else if (!shouldWriteCache) {
                Log.w("HomeBoot", "[${provider.name}] SKIP emit du résultat partiel — état actuel préservé")
            } else {
                Log.d("HomeBoot", "[${provider.name}] SKIP intermediate emit (cache shown, enrichment pending)")
            }

            // Enrich carousels in the background — deferred so it doesn't
            // compete with player init or channel loading.
            if (shouldEnrich) try {
                // Short delay so the initial home screen renders first,
                // then enrich with pages 1-5 of movies/series.
                kotlinx.coroutines.delay(1_500)
                Log.d("HomeViewModel", "Enrichment triggered for ${provider.name}")

                val allMovies = mutableListOf<Movie>()
                val allTvShows = mutableListOf<TvShow>()

                // Track FEATURED items by identity so we never share references
                // with enriched categories (sharing causes itemType overwrites in displayHome)
                val featuredItems = categories.firstOrNull()?.list?.toSet() ?: emptySet()

                // Collect items from NON-featured categories first
                for (cat in categories) {
                    for (item in cat.list) {
                        if (item in featuredItems) continue
                        when (item) {
                            is Movie -> allMovies.add(item)
                            is TvShow -> allTvShows.add(item)
                        }
                    }
                }

                // Load page 1 first and emit immediately for fast perceived loading,
                // then load remaining pages progressively.
                coroutineScope {
                    val movieDeferred = async {
                        try { provider.getMovies(1) } catch (_: Exception) { emptyList() }
                    }
                    val tvShowDeferred = async {
                        try { provider.getTvShows(1) } catch (_: Exception) { emptyList() }
                    }
                    allMovies.addAll(movieDeferred.await())
                    allTvShows.addAll(tvShowDeferred.await())
                }

                // Load pages 2-5 progressively in background
                for (page in 2..5) {
                    coroutineScope {
                        val movieDeferred = async {
                            try { provider.getMovies(page) } catch (_: Exception) { emptyList() }
                        }
                        val tvShowDeferred = async {
                            try { provider.getTvShows(page) } catch (_: Exception) { emptyList() }
                        }
                        allMovies.addAll(movieDeferred.await())
                        allTvShows.addAll(tvShowDeferred.await())
                    }
                    kotlinx.coroutines.delay(50)
                }

                // Deduplicate — keep items even with blank id (use title as fallback key)
                // Also exclude any FEATURED item references to avoid shared-object itemType conflicts
                val featuredMovieKeys = featuredItems.filterIsInstance<Movie>().map { it.id.ifBlank { it.title } }.toSet()
                val featuredTvShowKeys = featuredItems.filterIsInstance<TvShow>().map { it.id.ifBlank { it.title } }.toSet()
                val uniqueMovies = allMovies.distinctBy { it.id.ifBlank { it.title } }
                    .filter { (it.id.ifBlank { it.title }) !in featuredMovieKeys }
                    .filter { !it.isSeries } // Exclude series marked as Movie — they belong in "Séries" not "Films"
                val uniqueTvShows = allTvShows.distinctBy { it.id.ifBlank { it.title } }
                    .filter { (it.id.ifBlank { it.title }) !in featuredTvShowKeys }
                    .filter { !it.isMovie } // Exclude films marked as TvShow — they belong in "Films" not "Séries"

                // 2026-05-03: Fix flicker des listes home (signalé sur 9 providers).
                // L'ancien code écrasait TOUTES les catégories du provider et les
                // remplaçait par 4 catégories hardcodées (Films/Séries Récents/Populaires).
                // À l'ouverture le user voyait les vraies sections du site, puis ~1.5s
                // plus tard l'enrichment swap par les 4 génériques → flicker.
                // Nouveau comportement : on PRÉSERVE les catégories du provider et on
                // AJOUTE l'enrichment en fin avec dédup par nom (case-insensitive).
                if (uniqueMovies.size > 5 || uniqueTvShows.size > 5) {
                    val enrichedCategories = mutableListOf<Category>()

                    // 1. FEATURED banner en tête
                    val featured = categories.firstOrNull { it.name == Category.FEATURED }
                    if (featured != null) enrichedCategories.add(featured)

                    // 2. Catégories non-FEATURED du provider — PRÉSERVÉES telles quelles
                    val providerCategories = categories.filter { it.name != Category.FEATURED }
                    enrichedCategories.addAll(providerCategories)

                    // 3. Tracker les noms déjà utilisés pour éviter doublons exacts
                    val usedNames = enrichedCategories
                        .map { it.name.lowercase().trim() }
                        .toMutableSet()

                    // 4. Ajout des chunks enrichment EN SUFFIXE (skip si nom déjà présent)
                    val seriesNames = listOf("Séries Récentes", "Séries Populaires", "Encore plus de Séries", "Séries à Découvrir")
                    val filmNames = listOf("Films Récents", "Films Populaires", "Encore plus de Films", "Films à Découvrir")
                    val tvChunks = uniqueTvShows.chunked(20)
                    val movieChunks = uniqueMovies.chunked(20)
                    // 2026-07-03 : cappé à 4 chunks max (= les 4 noms nommés).
                    // Au-delà on obtenait "Films #5", "Séries #5"… inutile.
                    val maxChunks = minOf(maxOf(tvChunks.size, movieChunks.size), 4)

                    for (i in 0 until maxChunks) {
                        if (i < movieChunks.size) {
                            val name = filmNames.getOrElse(i) { "Films #${i + 1}" }
                            val key = name.lowercase().trim()
                            if (key !in usedNames) {
                                enrichedCategories.add(Category(name = name, list = movieChunks[i]))
                                usedNames += key
                            }
                        }
                        if (i < tvChunks.size) {
                            val name = seriesNames.getOrElse(i) { "Séries #${i + 1}" }
                            val key = name.lowercase().trim()
                            if (key !in usedNames) {
                                enrichedCategories.add(Category(name = name, list = tvChunks[i]))
                                usedNames += key
                            }
                        }
                    }

                    // Update UI with enriched content (provider categories préservées)
                    HomeCacheStore.write(appContext, provider, enrichedCategories)
                    _state.emit(State.SuccessLoading(enrichedCategories))
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Enrich failed for ${provider.name}: ${e.message}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Coroutine was cancelled because a newer getHome() call took
            // over (typical when ProviderChangeNotifier fires twice at
            // startup, or when the user re-clicks the same provider). NOT
            // a real failure — don't flash "Une erreur est survenue" on
            // screen for what's just a normal cancellation. Re-throw so
            // structured concurrency stays correct.
            throw e
        } catch (e: Exception) {
            Log.e("HomeViewModel", "getHome: ", e)
            if (cachedCategories.isNullOrEmpty()) {
                _state.emit(State.FailedLoading(e))
            }
        }
    }

    private fun loadUserDataCache(provider: Provider) {
        val appContext = StreamFlixApp.instance.applicationContext
        val cached = UserDataCache.read(appContext, provider)
        _userDataCache.value = cached

        viewModelScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(appContext)
            val moviesDeferred = async { db.movieDao().getFavorites().first() }
            val tvShowsDeferred = async { db.tvShowDao().getFavorites().first() }
            val watchingMoviesDeferred = async { db.movieDao().getWatchingMovies().first() }
            val watchingEpisodesDeferred = async { db.episodeDao().getWatchingEpisodes().first() }

            val movies = moviesDeferred.await()
            val tvShows = tvShowsDeferred.await()
            val watchingMovies = watchingMoviesDeferred.await()
            val watchingEpisodes = watchingEpisodesDeferred.await()

            val newData = UserDataCache.UserData(
                favoritesMovies = preserveCacheOrder(
                    cached = cached?.favoritesMovies ?: emptyList(),
                    incoming = movies.filter { it.isFavorite }.map { it.toCached() },
                    idOf = { it.id },
                ),
                favoritesTvShows = preserveCacheOrder(
                    cached = cached?.favoritesTvShows ?: emptyList(),
                    incoming = tvShows.filter { it.isFavorite }.map { it.toCached() },
                    idOf = { it.id },
                ),
                continueWatchingMovies = preserveCacheOrder(
                    cached = cached?.continueWatchingMovies ?: emptyList(),
                    incoming = (movies + watchingMovies)
                        .filter { it.watchHistory != null }
                        .map { it.toCached() },
                    idOf = { it.id },
                ),
                continueWatchingEpisodes = preserveCacheOrder(
                    cached = cached?.continueWatchingEpisodes ?: emptyList(),
                    incoming = watchingEpisodes
                        .filter { it.watchHistory != null }
                        .map { it.toCached() },
                    idOf = { it.id },
                ),
            )

            UserDataCache.write(appContext, provider, newData)

            if (_userDataCache.value != newData) {
                _userDataCache.value = newData
            }
        }
    }
}
