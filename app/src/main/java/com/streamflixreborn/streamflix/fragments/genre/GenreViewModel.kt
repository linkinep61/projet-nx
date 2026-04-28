package com.streamflixreborn.streamflix.fragments.genre

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.ParentalControlUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

class GenreViewModel(private val id: String, private val genreName: String = "", database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    
    init {
        // Listen for provider changes and reload data
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getGenre(id)
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.genre.shows
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.movieDao().getByIds(movies.map { it.id }))
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        },
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val tvShows = state.genre.shows
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.tvShowDao().getByIds(tvShows.map { it.id }))
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }
                State.SuccessLoading(
                    genre = state.genre.copy(
                        shows = state.genre.shows.map { item ->
                            when (item) {
                                is Movie -> moviesById[item.id]
                                    ?.takeIf { !item.isSame(it) }
                                    ?.let { item.copy().merge(it) }
                                    ?: item
                                is TvShow -> tvShowsById[item.id]
                                    ?.takeIf { !item.isSame(it) }
                                    ?.let { item.copy().merge(it) }
                                    ?: item
                            }
                        }
                    ),
                    hasMore = state.hasMore
                )
            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    private var page = 1

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(val genre: Genre, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getGenre(id)
    }


    fun getGenre(id: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            if (UserPreferences.isGlobalSearchEnabled) {
                getGenreGlobal(id)
            } else {
                val provider = UserPreferences.currentProvider ?: return@launch
                val searchKeyword = genreSearchKeywords[id]
                if (searchKeyword != null) {
                    // Genre spécial (K-Drama) → recherche textuelle progressive
                    page = 1
                    getShowsBySearchProgressive(provider, searchKeyword, id)
                } else {
                    // Genre standard → utiliser getGenre(id)
                    val genre = provider.getGenre(id).let {
                        it.copy(shows = ParentalControlUtils.filterShows(it.shows))
                    }

                    page = 1

                    _state.emit(State.SuccessLoading(genre, true))
                }
            }
        } catch (e: Exception) {
            Log.e("GenreViewModel", "getGenre: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    // Genres spéciaux : au lieu d'utiliser getGenre (slug introuvable sur certains sites),
    // on fait une recherche textuelle avec ces mots-clés pour trouver le contenu correspondant
    private val kdramaKeywords = listOf(
        "coréenne", "coréen", "Série coréenne", "Film coréenne",
        "Coréens", "corée", "k-drama", "korean", "sud-coréen",
        "kdrama", "drama coréen", "cinéma coréen", "séoul",
        "korea", "hallyu", "Corée du Sud"
    )
    private val genreSearchKeywords = mapOf(
        "drama-coreen" to kdramaKeywords,
        "k-drama" to kdramaKeywords,
        "K-Drama|" to kdramaKeywords,
    )

    /**
     * Recherche progressive pour un seul provider (mode normal).
     * Émet les résultats au fur et à mesure que chaque mot-clé retourne des résultats.
     */
    private suspend fun getShowsBySearchProgressive(provider: Provider, keywords: List<String>, genreId: String) {
        val allShows = mutableListOf<Show>()
        for (keyword in keywords) {
            try {
                val results = withTimeoutOrNull(15_000L) {
                    provider.search(keyword)
                }
                if (results == null) continue
                val filtered = ParentalControlUtils.filterItems(results)
                val shows = filtered.filterIsInstance<Show>()
                shows.forEach { show ->
                    when (show) {
                        is Movie -> show.providerName = provider.name
                        is TvShow -> show.providerName = provider.name
                    }
                }
                allShows.addAll(shows)
                // Émettre les résultats progressivement (dédupliqués)
                val unique = allShows.distinctBy { show ->
                    when (show) {
                        is Movie -> "movie:${show.id}"
                        is TvShow -> "tvshow:${show.id}"
                    }
                }
                _state.emit(
                    State.SuccessLoading(
                        genre = Genre(id = genreId, name = genreName.ifEmpty { genreId }, shows = unique),
                        hasMore = false
                    )
                )
            } catch (e: Exception) {
                Log.e("GenreViewModel", "getShowsBySearch '$keyword' for ${provider.name}: ", e)
            }
        }
    }

    /**
     * Recherche par mots-clés pour un provider (utilisé en mode global).
     * Retourne tous les résultats sans émettre d'état intermédiaire.
     */
    private suspend fun getShowsBySearch(provider: Provider, keywords: List<String>): List<Show> {
        val allShows = mutableListOf<Show>()
        for (keyword in keywords) {
            try {
                val results = withTimeoutOrNull(15_000L) {
                    provider.search(keyword)
                }
                if (results == null) continue
                val filtered = ParentalControlUtils.filterItems(results)
                val shows = filtered.filterIsInstance<Show>()
                allShows.addAll(shows)
            } catch (e: Exception) {
                Log.e("GenreViewModel", "getShowsBySearch '$keyword' for ${provider.name}: ", e)
            }
        }
        // Dédupliquer par id
        return allShows.distinctBy { show ->
            when (show) {
                is Movie -> "movie:${show.id}"
                is TvShow -> "tvshow:${show.id}"
            }
        }
    }

    private suspend fun getGenreGlobal(id: String) {
        val currentProvider = UserPreferences.currentProvider ?: return
        val currentLanguage = currentProvider.language
        // Tous les providers de la même langue
        val providers = Provider.providers.keys.filter { it.language == currentLanguage }
        val searchKeyword = genreSearchKeywords[id]

        val allShows = mutableListOf<Show>()
        page = 1

        // Timeout global de 90s pour éviter les blocages en recherche globale
        withTimeoutOrNull(90_000L) {
            supervisorScope {
                val deferreds = providers.map { provider ->
                    async {
                        try {
                            val shows = if (searchKeyword != null) {
                                // Genre spécial (K-Drama) → recherche textuelle
                                getShowsBySearch(provider, searchKeyword)
                            } else {
                                // Genre standard → utiliser getGenre(id)
                                val genre = provider.getGenre(id)
                                ParentalControlUtils.filterShows(genre.shows)
                            }
                            // Tagger avec le nom du provider
                            shows.forEach { show ->
                                when (show) {
                                    is Movie -> show.providerName = provider.name
                                    is TvShow -> show.providerName = provider.name
                                }
                            }
                            // Chargement progressif : émettre au fur et à mesure
                            synchronized(allShows) {
                                allShows.addAll(shows)
                                val unique = allShows.distinctBy { show ->
                                    when (show) {
                                        is Movie -> "movie:${show.id}"
                                        is TvShow -> "tvshow:${show.id}"
                                    }
                                }
                                unique
                            }
                        } catch (e: Exception) {
                            Log.e("GenreViewModel", "getGenreGlobal for ${provider.name}: ", e)
                            null
                        }
                    }
                }

                // Émettre progressivement dès qu'un provider termine
                for (deferred in deferreds) {
                    val unique = deferred.await() ?: continue
                    if (unique.isNotEmpty()) {
                        _state.emit(
                            State.SuccessLoading(
                                genre = Genre(id = id, name = genreName.ifEmpty { id }, shows = unique),
                                hasMore = false
                            )
                        )
                    }
                }
            }
        }

        // Émission finale avec tous les résultats
        val unique = allShows.distinctBy { show ->
            when (show) {
                is Movie -> "movie:${show.id}"
                is TvShow -> "tvshow:${show.id}"
            }
        }
        if (unique.isNotEmpty()) {
            _state.emit(
                State.SuccessLoading(
                    genre = Genre(id = id, name = genreName.ifEmpty { id }, shows = unique),
                    hasMore = false
                )
            )
        }
    }

    fun loadMoreGenreShows() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)

            try {
                val provider = UserPreferences.currentProvider ?: return@launch
                val genre = provider.getGenre(id, page + 1).let {
                    it.copy(shows = ParentalControlUtils.filterShows(it.shows))
                }

                page += 1

                _state.emit(
                    State.SuccessLoading(
                        genre = Genre(
                            id = genre.id,
                            name = genre.name,

                            shows = currentState.genre.shows + genre.shows,
                        ),
                        hasMore = genre.shows.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("GenreViewModel", "loadMoreGenreShows: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
