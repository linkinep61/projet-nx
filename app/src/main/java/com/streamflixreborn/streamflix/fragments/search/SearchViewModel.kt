package com.streamflixreborn.streamflix.fragments.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.ParentalControlUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

// DEFINICIONES DE ESTADO Y RESULTADOS (Fuera de la clase para mejor acceso)
sealed class State {
    data object Searching : State()
    data object SearchingMore : State()
    data class SuccessSearching(val results: List<AppAdapter.Item>, val hasMore: Boolean) : State()
    data class FailedSearching(val error: Exception) : State()
    data object GlobalSearching : State()
    data class SuccessGlobalSearching(val providerResults: List<ProviderResult>) : State()
}

data class ProviderResult(
    val provider: Provider,
    val state: State,
) {
    sealed class State {
        data object Loading : State()
        data class Success(val results: List<AppAdapter.Item>) : State()
        data class Error(val error: Exception) : State()
    }
}


class SearchViewModel(database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Searching)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessSearching -> {
                    val movies = state.results
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
                is State.SuccessSearching -> {
                    val tvShows = state.results
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
            is State.SuccessSearching -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }

                State.SuccessSearching(
                    results = state.results.map { item ->
                        when (item) {
                            is Movie -> moviesById[item.id]
                                ?.takeIf { !item.isSame(it) }
                                ?.let { item.copy().merge(it) }
                                ?: item
                            is TvShow -> tvShowsById[item.id]
                                ?.takeIf { !item.isSame(it) }
                                ?.let { item.copy().merge(it) }
                                ?: item
                            else -> item
                        }
                    },
                    hasMore = state.hasMore
                )
            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    var query = ""
    private var page = 1

    init {
        search(query)
    }

    fun search(query: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Searching)

        try {
            val provider = UserPreferences.currentProvider ?: return@launch
            val results = ParentalControlUtils.filterItems(provider.search(query))
            this@SearchViewModel.query = query
            page = 1
            _state.emit(State.SuccessSearching(results, results.isNotEmpty()))
        } catch (e: Exception) {
            Log.e("SearchViewModel", "search: ", e)
            _state.emit(State.FailedSearching(e))
        }
    }

    fun loadMore() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessSearching) {
            _state.emit(State.SearchingMore)
            try {
                val provider = UserPreferences.currentProvider ?: return@launch
                val results = ParentalControlUtils.filterItems(
                    provider.search(query, page + 1)
                )
                val existingKeys = currentState.results
                    .asSequence()
                    .map { it.searchIdentityKey() }
                    .toHashSet()
                val newUniqueResults = results.filterNot { it.searchIdentityKey() in existingKeys }
                page += 1
                _state.emit(
                    State.SuccessSearching(
                        results = currentState.results + newUniqueResults,
                        hasMore = newUniqueResults.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("SearchViewModel", "loadMore: ", e)
                _state.emit(State.FailedSearching(e))
            }
        }
    }

    // FUNCIÓN DE BÚSQUEDA GLOBAL AÑADIDA
    // 2026-05-18 : binary partition IPTV vs non-IPTV — quand on est sur un
    //   provider IPTV, on cherche QUE dans les IPTV ; sinon, dans tout le reste
    //   (FILMS_SERIES + ANIME mélangés). Aucun mélange entre IPTV et film/série.
    /** 2026-07-02 : filtre GÉNÉRIQUE de la recherche globale (tous providers). Garde les
     *  résultats dont le TITRE commence par la requête ; sinon ceux qui la contiennent ;
     *  sinon tout (au cas où le match est sur le titre original). Élimine le "bazar". */
    private fun strictNameFilter(
        items: List<com.streamflixreborn.streamflix.adapters.AppAdapter.Item>,
        query: String
    ): List<com.streamflixreborn.streamflix.adapters.AppAdapter.Item> {
        val nq = query.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (nq.length < 2) return items
        fun norm(it: com.streamflixreborn.streamflix.adapters.AppAdapter.Item): String = (when (it) {
            is Movie -> it.title
            is TvShow -> it.title
            else -> ""
        }).lowercase().replace(Regex("[^a-z0-9]"), "")
        val starts = items.filter { norm(it).startsWith(nq) }
        if (starts.isNotEmpty()) return starts
        val contains = items.filter { norm(it).contains(nq) }
        return if (contains.isNotEmpty()) contains else items
    }

    fun searchGlobal(query: String, currentLanguage: String, group: Provider.Companion.ProviderGroup? = null) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.GlobalSearching)

        val isIptvScope = group == Provider.Companion.ProviderGroup.IPTV
        val targetProviders = Provider.providers.keys
            .filter { it.language == currentLanguage }
            .filter { p ->
                val pIsIptv = Provider.getGroup(p) == Provider.Companion.ProviderGroup.IPTV
                if (isIptvScope) pIsIptv else !pIsIptv
            }
            .toList()
        Log.d("SearchViewModel", "searchGlobal scope=${if (isIptvScope) "IPTV" else "Films/Séries+Anime"} targets=${targetProviders.size}")

        if (targetProviders.isEmpty()) {
            _state.emit(State.SuccessGlobalSearching(emptyList()))
            return@launch
        }

        val initialResults = targetProviders.map { provider ->
            ProviderResult(provider, ProviderResult.State.Loading)
        }
        _state.emit(State.SuccessGlobalSearching(initialResults))

        val mutableResults = initialResults.toMutableList()

        val stateComparator = compareBy<ProviderResult> { providerResult ->
            when (val state = providerResult.state) {
                is ProviderResult.State.Success -> if (state.results.isNotEmpty()) 1 else 3
                is ProviderResult.State.Loading -> 2
                is ProviderResult.State.Error -> 4
            }
        }

        targetProviders.forEachIndexed { index, provider ->
            launch {
                try {
                    val rawResults = ParentalControlUtils.filterItems(provider.search(query).onEach { item ->
                        // ========= ¡AQUÍ ESTÁ LA MAGIA! =========
                        // Le ponemos el sello a cada resultado
                        when (item) {
                            is Movie -> item.providerName = provider.name
                            is TvShow -> item.providerName = provider.name
                        }
                        // =======================================
                    })
                    // 2026-07-02 (user "recherche globale trop de bazar, plus stricte") :
                    //   filtre GÉNÉRIQUE par NOM sur chaque provider — garde les titres qui
                    //   COMMENCENT par la requête (sinon ceux qui la contiennent). Élimine
                    //   les faux positifs (Spider-Man Far From Home… pour "from").
                    val results = strictNameFilter(rawResults, query)
                    mutableResults[index] = ProviderResult(provider, ProviderResult.State.Success(results))
                } catch (e: Exception) {
                    Log.e("SearchViewModel", "searchGlobal for ${provider.name}: ", e)
                    mutableResults[index] = ProviderResult(provider, ProviderResult.State.Error(e))
                }

                _state.emit(State.SuccessGlobalSearching(mutableResults.sortedWith(stateComparator)))
            }
        }
    }
}

private fun AppAdapter.Item.searchIdentityKey(): String = when (this) {
    is Movie -> "movie:$id"
    is TvShow -> "tvshow:$id"
    else -> "${this::class.java.name}:${hashCode()}"
}
