package com.streamflixreborn.streamflix.fragments.movies

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentMoviesMobileBinding
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import com.streamflixreborn.streamflix.utils.CacheUtils
import kotlinx.coroutines.launch

class MoviesMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentMoviesMobileBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { MoviesViewModel(database) }

    private val appAdapter = AppAdapter()

    private var currentHasMore: Boolean = false
    private var shouldScrollToTop: Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoviesMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        shouldScrollToTop = true
        initializeLanguageTabs()
        initializeMovies()
        initializeIptvActions()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MoviesViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    MoviesViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is MoviesViewModel.State.SuccessLoading -> {
                        displayMovies(state.movies, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is MoviesViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getMovies()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                val doRetry = { viewModel.getMovies() }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    doRetry()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val spanCount = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) 6 else 3
        (binding.rvMovies.layoutManager as? GridLayoutManager)?.spanCount = spanCount
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 2026-05-13 (user "il y a pas de filtre de catégorie et de langue en
     *  film et série") : initialise les 2 boutons IPTV — catégories filtre
     *  + filtre langue. Visibles uniquement sur Mon IPTV. */
    private fun initializeIptvActions() {
        val provider = UserPreferences.currentProvider
        if (provider is com.streamflixreborn.streamflix.providers.MyIptvProvider) {
            binding.llIptvActions.visibility = View.VISIBLE
            binding.ivIptvCategories.visibility = View.VISIBLE
            binding.ivIptvLanguage.visibility = View.VISIBLE
            binding.ivIptvCategories.setOnClickListener {
                showIptvMoviesCategoryPicker()
            }
            binding.ivIptvLanguage.setOnClickListener {
                showIptvLanguageFilterPicker()
            }
        } else {
            // 2026-05-26 : pour les providers genre-supported (Cloudstream, AnimeSama…),
            // le genre picker s'ouvre par re-clic sur le tab actif — pas d'icône.
            binding.llIptvActions.visibility = View.GONE
        }
    }

    private fun showIptvMoviesCategoryPicker() {
        val provider = com.streamflixreborn.streamflix.providers.MyIptvProvider
        val type = com.streamflixreborn.streamflix.utils.IptvClassifier.ContentType.MOVIE
        val categoriesWithCount = provider.availableCategoriesWithCount(type)
        if (categoriesWithCount.isEmpty()) {
            android.widget.Toast.makeText(
                requireContext(),
                "Aucune catégorie en cache — recharge la source d'abord.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val totalCount = categoriesWithCount.sumOf { it.second }
        val displayItems = arrayOf("Toutes les catégories ($totalCount)") +
            categoriesWithCount.map { (n, c) -> "${provider.prettyCategoryName(n)}  ($c)" }
                .toTypedArray()
        val rawNames = arrayOf<String?>(null) +
            categoriesWithCount.map { it.first }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Choisir une catégorie")
            .setItems(displayItems) { _, idx ->
                provider.selectedCategoryMovie = rawNames[idx]
                com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                    requireContext().applicationContext, provider,
                )
                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                // 2026-05-13 (user "des fois il faut cliquer plusieurs fois
                // pour changer la catégorie") : double reload + delay pour
                // s'assurer que la viewModel ne race pas avec la pagination
                // précédente. Le 1er reload reset l'état, le 2e (après 100ms)
                // garantit que le nouveau filtre est appliqué même si le
                // viewModel avait un job en cours.
                viewModel.getMovies()
                binding.root.postDelayed({ viewModel.getMovies() }, 150L)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showIptvLanguageFilterPicker() {
        val provider = com.streamflixreborn.streamflix.providers.MyIptvProvider
        val options = arrayOf(
            "Auto (recommandé)" to "auto",
            "Toutes les langues" to "all",
            "Français uniquement" to "fr",
        )
        val current = provider.getLanguageFilterMode()
        val currentIdx = options.indexOfFirst { it.second == current }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Filtrer par langue")
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), currentIdx) { dlg, idx ->
                val newMode = options[idx].second
                if (newMode != current) {
                    provider.setLanguageFilterMode(newMode)
                    provider.selectedCategoryLive = null
                    provider.selectedCategoryMovie = null
                    provider.selectedCategorySeries = null
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                        requireContext().applicationContext, provider,
                    )
                    com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                    viewModel.getMovies()
                    android.widget.Toast.makeText(
                        requireContext().applicationContext,
                        "Filtre langue : ${options[idx].first}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                dlg.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-05-26 : filtre par genre TMDB (Action, Comédie…) pour les providers TMDB. */
    private fun showGenreFilterPicker() {
        val provider = UserPreferences.currentProvider ?: return
        val entries = com.streamflixreborn.streamflix.utils.GenreFilter.genresForProvider()
        val current = com.streamflixreborn.streamflix.utils.GenreFilter.get(provider.name)
        val labels = arrayOf("Tous les genres") + entries.map { it.name }.toTypedArray()
        val currentIdx = if (current == null) 0 else entries.indexOfFirst { it.id == current.id } + 1
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Filtrer par genre")
            .setSingleChoiceItems(labels, currentIdx.coerceAtLeast(0)) { dlg, idx ->
                val newGenre = if (idx == 0) null else entries[idx - 1]
                val changed = newGenre?.id != current?.id
                if (changed) {
                    com.streamflixreborn.streamflix.utils.GenreFilter.set(provider.name, newGenre)
                    viewModel.setGenreFilter(newGenre?.id)
                    android.widget.Toast.makeText(
                        requireContext(),
                        if (newGenre != null) "Genre : ${newGenre.name}" else "Genre : tous",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                dlg.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-05-26 : filtre langue VF/VOSTFR pour AnimeSama */
    private fun showAnimeSamaLanguagePicker() {
        val provider = UserPreferences.currentProvider ?: return
        val options = arrayOf(
            "Toutes les langues" to null as String?,
            "VF uniquement" to "vf",
            "VOSTFR uniquement" to "vostfr",
        )
        val current = com.streamflixreborn.streamflix.utils.GenreFilter.getLang(provider.name)
        val currentIdx = options.indexOfFirst { it.second == current }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Filtrer par langue")
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), currentIdx) { dlg, idx ->
                val newLang = options[idx].second
                if (newLang != current) {
                    com.streamflixreborn.streamflix.utils.GenreFilter.setLang(provider.name, newLang)
                    viewModel.getMovies()
                    android.widget.Toast.makeText(
                        requireContext(),
                        if (newLang != null) "Langue : ${options[idx].first}" else "Langue : toutes",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                dlg.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Track du tab actif pour détecter un re-clic → genre picker */
    private var activeTab: android.widget.TextView? = null

    private fun initializeLanguageTabs() {
        val providerName = UserPreferences.currentProvider?.name ?: return
        val genreSupported = com.streamflixreborn.streamflix.utils.GenreFilter.isSupported(providerName)

        if (viewModel.isTypeFilterable) {
            // Providers like AnimeSama: "Série" / "Film" tabs — reload from server on each tab
            binding.tabLanguage.visibility = View.VISIBLE
            binding.tabFr.text = "Série"
            binding.tabVostfr.text = "Film"
            selectTab(binding.tabFr, binding.tabVostfr)
            activeTab = binding.tabFr
            binding.tabFr.setOnClickListener {
                if (activeTab == binding.tabFr && genreSupported) {
                    showGenreFilterPicker()
                } else {
                    selectTab(binding.tabFr, binding.tabVostfr)
                    activeTab = binding.tabFr
                    viewModel.setLanguageFilter("serie")
                }
            }
            binding.tabVostfr.setOnClickListener {
                if (activeTab == binding.tabVostfr && genreSupported) {
                    showGenreFilterPicker()
                } else {
                    selectTab(binding.tabVostfr, binding.tabFr)
                    activeTab = binding.tabVostfr
                    viewModel.setLanguageFilter("film")
                }
            }
        } else if (viewModel.isFilterable) {
            // Language-filterable providers: "FR" / "VOSTFR" tabs
            binding.tabLanguage.visibility = View.VISIBLE
            binding.tabFr.text = "FR"
            binding.tabVostfr.text = "VOSTFR"
            selectTab(binding.tabFr, binding.tabVostfr)
            activeTab = binding.tabFr
            binding.tabFr.setOnClickListener {
                if (activeTab == binding.tabFr && genreSupported) {
                    showGenreFilterPicker()
                } else {
                    selectTab(binding.tabFr, binding.tabVostfr)
                    activeTab = binding.tabFr
                    viewModel.setLanguageFilter("vf")
                }
            }
            binding.tabVostfr.setOnClickListener {
                if (activeTab == binding.tabVostfr && genreSupported) {
                    showGenreFilterPicker()
                } else {
                    selectTab(binding.tabVostfr, binding.tabFr)
                    activeTab = binding.tabVostfr
                    viewModel.setLanguageFilter("vostfr")
                }
            }
        }
    }

    private fun selectTab(selected: android.widget.TextView, other: android.widget.TextView) {
        selected.setTextColor(0xFFFFFFFF.toInt())
        selected.setTypeface(null, android.graphics.Typeface.BOLD)
        selected.setBackgroundColor(0x33E50914)
        other.setTextColor(0x80FFFFFF.toInt())
        other.setTypeface(null, android.graphics.Typeface.NORMAL)
        other.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private fun initializeMovies() {
        binding.rvMovies.apply {
            setHasFixedSize(true)
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 6 else 3
            (layoutManager as? GridLayoutManager)?.spanCount = spanCount
            addItemDecoration(
                SpacingItemDecoration(10.dp(requireContext()))
            )
        }
    }

    private fun displayMovies(movies: List<Movie>, hasMore: Boolean) {
        currentHasMore = hasMore

        appAdapter.submitList(movies.onEach {
            it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
        })

        if (shouldScrollToTop) {
            shouldScrollToTop = false
            binding.rvMovies.post { binding.rvMovies.scrollToPosition(0) }
        }

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreMovies() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}