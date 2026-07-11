package com.streamflixreborn.streamflix.fragments.movies

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.providers.FilterableProvider
import com.streamflixreborn.streamflix.utils.GenreFilter
import com.streamflixreborn.streamflix.utils.ParentalControlUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class MoviesViewModel(database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    
    init {
        // Listen for provider changes and reload data
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                languageFilter = when {
                    UserPreferences.currentProvider?.name in typeFilterProviders -> "serie"
                    UserPreferences.currentProvider is FilterableProvider -> "vf"
                    else -> "all"
                }
                // 2026-05-26 : lire le genre SAUVÉ pour le provider courant.
                // GenreFilter stocke par provider → changement de provider = null
                // (nouveau provider n'a pas de genre sauvé). Changement de genre
                // depuis la sidebar TV = le bon ID est lu.
                genreId = GenreFilter.currentGenreId()
                getMovies()
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    if (state.movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.movieDao().getByIds(state.movies.map { it.id }))
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        },
    ) { state, moviesDb ->
        when (state) {
            is State.SuccessLoading -> {
                val moviesById = moviesDb.associateBy { it.id }
                State.SuccessLoading(
                    movies = state.movies.map { movie ->
                        moviesById[movie.id]
                            ?.takeIf { !movie.isSame(it) }
                            ?.let { movie.copy().merge(it) }
                            ?: movie
                    },
                    hasMore = state.hasMore
                )
            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    private val typeFilterProviders = listOf("VoirDrama", "VoirAnime", "AnimeSama", "FrenchManga")

    private var page = 1
    private var languageFilter: String = when {
        UserPreferences.currentProvider?.name in typeFilterProviders -> "serie"
        UserPreferences.currentProvider is FilterableProvider -> "vf"
        else -> "all"
    }

    val isFilterable: Boolean
        get() = UserPreferences.currentProvider is FilterableProvider

    val isTypeFilterable: Boolean
        get() = UserPreferences.currentProvider?.name in typeFilterProviders

    /** Genre TMDB sélectionné (null = tout). Seuls les providers TMDB le supportent. */
    private var genreId: String? = null

    fun setGenreFilter(newGenreId: String?) {
        if (genreId != newGenreId) {
            genreId = newGenreId
            getMovies()
        }
    }

    fun setLanguageFilter(language: String) {
        if (languageFilter != language) {
            languageFilter = language
            // 2026-06-20 : rafraîchit le contexte AnimeSama AVANT getMovies
            // sinon getGenre() utilise un vieux type/langue quand un genre est actif.
            if (UserPreferences.currentProvider?.name == "AnimeSama") {
                com.streamflixreborn.streamflix.providers.AnimeSamaProvider
                    .setActiveTabContext(language, fromMovies = true)
            }
            getMovies()
        }
    }

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(val movies: List<Movie>, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getMovies()
    }


    /** 2026-05-14 (user "des fois il faut cliquer plusieurs fois pour changer
     *  de catégorie") : track le job de fetch actuel pour pouvoir le canceler
     *  AVANT de lancer un nouveau getMovies. Sans ça, un loadMoreMovies en
     *  cours pouvait terminer APRÈS getMovies et écraser le state avec la
     *  pagination de l'ancienne catégorie → l'user voyait l'ancienne data
     *  jusqu'au prochain clic. */
    @Volatile private var loadJob: Job? = null

    fun getMovies() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.emit(State.Loading)
            try {
                val provider = UserPreferences.currentProvider ?: return@launch
                // 2026-06-20 : (re)pose le contexte AnimeSama AVANT chaque fetch.
                // L'enrichment HomeViewModel peut avoir écrasé les flags entre 2 fetchs.
                if (provider.name == "AnimeSama") {
                    com.streamflixreborn.streamflix.providers.AnimeSamaProvider
                        .setActiveTabContext(languageFilter, fromMovies = true)
                }
                Log.d("MoviesViewModel", "getMovies: provider=${provider.name}, isFilterable=${provider is FilterableProvider}, languageFilter=$languageFilter, genreId=$genreId")
                var movies = if (genreId != null && GenreFilter.isSupported(provider.name)) {
                    Log.d("MoviesViewModel", "getMovies: using GENRE filter id=$genreId")
                    ParentalControlUtils.filterItems(
                        provider.getGenre(genreId!!, 1).shows
                    ).filterIsInstance<Movie>()
                } else if (provider is FilterableProvider && languageFilter != "all") {
                    Log.d("MoviesViewModel", "getMovies: using FILTERED with language=$languageFilter")
                    ParentalControlUtils.filterItems(
                        provider.getFilteredMovies(languageFilter)
                    ).filterIsInstance<Movie>()
                } else {
                    Log.d("MoviesViewModel", "getMovies: using STANDARD (no filter)")
                    ParentalControlUtils.filterItems(
                        provider.getMovies()
                    ).filterIsInstance<Movie>()
                }
                if (provider.name in typeFilterProviders && provider !is FilterableProvider) {
                    movies = when (languageFilter) {
                        "serie" -> movies.filter { it.isSeries }
                        "film" -> movies.filter { !it.isSeries }
                        else -> movies
                    }
                }
                Log.d("MoviesViewModel", "getMovies: got ${movies.size} movies")
                page = 1
                _state.emit(State.SuccessLoading(movies, true))
            } catch (e: Exception) {
                Log.e("MoviesViewModel", "getMovies: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }

    fun loadMoreMovies() {
        // Pas de cancel ici — laisse une loadMore en cours finir, mais ne lance
        // pas un 2e si y a déjà un fetch actif (évite double pagination).
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val currentState = _state.value
            if (currentState is State.SuccessLoading) {
                _state.emit(State.LoadingMore)
                try {
                    val provider = UserPreferences.currentProvider ?: return@launch
                    var movies = if (genreId != null && GenreFilter.isSupported(provider.name)) {
                        ParentalControlUtils.filterItems(
                            provider.getGenre(genreId!!, page + 1).shows
                        ).filterIsInstance<Movie>()
                    } else if (provider is FilterableProvider && languageFilter != "all") {
                        ParentalControlUtils.filterItems(
                            provider.getFilteredMovies(languageFilter, page + 1)
                        ).filterIsInstance<Movie>()
                    } else {
                        ParentalControlUtils.filterItems(
                            provider.getMovies(page + 1)
                        ).filterIsInstance<Movie>()
                    }
                    if (provider.name in typeFilterProviders && provider !is FilterableProvider) {
                        movies = when (languageFilter) {
                            "serie" -> movies.filter { it.isSeries }
                            "film" -> movies.filter { !it.isSeries }
                            else -> movies
                        }
                    }
                    page += 1
                    // 2026-05-14 (user "j'ai les jaquettes en double dans le film") :
                    // dédup par ID quand on concatène les pages. Évite que pagination
                    // ou race avec une refresh redonne les mêmes items.
                    val merged = (currentState.movies + movies).distinctBy { it.id.ifBlank { it.title } }
                    _state.emit(
                        State.SuccessLoading(
                            movies = merged,
                            hasMore = movies.isNotEmpty(),
                        )
                    )
                } catch (e: Exception) {
                    Log.e("MoviesViewModel", "loadMoreMovies: ", e)
                    _state.emit(State.FailedLoading(e))
                }
            }
        }
    }
}
