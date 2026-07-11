package com.streamflixreborn.streamflix.fragments.tv_shows

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.TvShow
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

class TvShowsViewModel(database: AppDatabase) : ViewModel() {

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
                getTvShows()
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    if (state.tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.tvShowDao().getByIds(state.tvShows.map { it.id }))
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                val tvShowsById = tvShowsDb.associateBy { it.id }
                State.SuccessLoading(
                    tvShows = state.tvShows.map { tvShow ->
                        tvShowsById[tvShow.id]
                            ?.takeIf { !tvShow.isSame(it) }
                            ?.let { tvShow.copy().merge(it) }
                            ?: tvShow
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
            getTvShows()
        }
    }

    fun setLanguageFilter(language: String) {
        if (languageFilter != language) {
            languageFilter = language
            // 2026-06-20 : rafraîchit le contexte AnimeSama AVANT getTvShows
            // sinon getGenre() utilise un vieux type/langue quand un genre est actif.
            if (UserPreferences.currentProvider?.name == "AnimeSama") {
                com.streamflixreborn.streamflix.providers.AnimeSamaProvider
                    .setActiveTabContext(language, fromMovies = false)
            }
            getTvShows()
        }
    }

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(val tvShows: List<TvShow>, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getTvShows()
    }


    /** 2026-05-14 (user "des fois il faut cliquer plusieurs fois pour changer
     *  de catégorie") : track le job de fetch actuel pour pouvoir le canceler
     *  AVANT de lancer un nouveau getTvShows. Sans ça, un loadMoreTvShows en
     *  cours pouvait terminer APRÈS getTvShows et écraser le state. */
    @Volatile private var loadJob: Job? = null

    fun getTvShows() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.emit(State.Loading)
            try {
                val provider = UserPreferences.currentProvider ?: return@launch
                // 2026-06-20 : (re)pose le contexte AnimeSama AVANT chaque fetch.
                // L'enrichment HomeViewModel peut avoir écrasé les flags entre 2 fetchs.
                if (provider.name == "AnimeSama") {
                    com.streamflixreborn.streamflix.providers.AnimeSamaProvider
                        .setActiveTabContext(languageFilter, fromMovies = false)
                }
                Log.d("TvShowsViewModel", "getTvShows: provider=${provider.name}, isFilterable=${provider is FilterableProvider}, languageFilter=$languageFilter, genreId=$genreId")
                var tvShows = if (genreId != null && GenreFilter.isSupported(provider.name)) {
                    Log.d("TvShowsViewModel", "getTvShows: using GENRE filter id=$genreId")
                    ParentalControlUtils.filterItems(
                        provider.getGenre(genreId!!, 1).shows
                    ).filterIsInstance<TvShow>()
                } else if (provider is FilterableProvider && languageFilter != "all") {
                    Log.d("TvShowsViewModel", "getTvShows: using FILTERED with language=$languageFilter")
                    ParentalControlUtils.filterItems(
                        provider.getFilteredTvShows(languageFilter)
                    ).filterIsInstance<TvShow>()
                } else {
                    Log.d("TvShowsViewModel", "getTvShows: using STANDARD (no filter)")
                    ParentalControlUtils.filterItems(
                        provider.getTvShows()
                    ).filterIsInstance<TvShow>()
                }
                if (provider.name in typeFilterProviders && provider !is FilterableProvider) {
                    tvShows = when (languageFilter) {
                        "serie" -> tvShows.filter { !it.isMovie }
                        "film" -> tvShows.filter { it.isMovie }
                        else -> tvShows
                    }
                }
                Log.d("TvShowsViewModel", "getTvShows: got ${tvShows.size} tvShows")
                page = 1
                _state.emit(State.SuccessLoading(tvShows, true))
            } catch (e: Exception) {
                Log.e("TvShowsViewModel", "getTvShows: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }

    fun loadMoreTvShows() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val currentState = _state.value
            if (currentState is State.SuccessLoading) {
                _state.emit(State.LoadingMore)
                try {
                    val provider = UserPreferences.currentProvider ?: return@launch
                    var tvShows = if (genreId != null && GenreFilter.isSupported(provider.name)) {
                        ParentalControlUtils.filterItems(
                            provider.getGenre(genreId!!, page + 1).shows
                        ).filterIsInstance<TvShow>()
                    } else if (provider is FilterableProvider && languageFilter != "all") {
                        ParentalControlUtils.filterItems(
                            provider.getFilteredTvShows(languageFilter, page + 1)
                        ).filterIsInstance<TvShow>()
                    } else {
                        ParentalControlUtils.filterItems(
                            provider.getTvShows(page + 1)
                        ).filterIsInstance<TvShow>()
                    }
                    if (provider.name in typeFilterProviders && provider !is FilterableProvider) {
                        tvShows = when (languageFilter) {
                            "serie" -> tvShows.filter { !it.isMovie }
                            "film" -> tvShows.filter { it.isMovie }
                            else -> tvShows
                        }
                    }
                    page += 1
                    // 2026-05-14 (user "jaquettes en double") : dédup par ID.
                    val merged = (currentState.tvShows + tvShows).distinctBy { it.id.ifBlank { it.title } }
                    _state.emit(
                        State.SuccessLoading(
                            tvShows = merged,
                            hasMore = tvShows.isNotEmpty(),
                        )
                    )
                } catch (e: Exception) {
                    Log.e("TvShowsViewModel", "loadMoreTvShows: ", e)
                    _state.emit(State.FailedLoading(e))
                }
            }
        }
    }
}
