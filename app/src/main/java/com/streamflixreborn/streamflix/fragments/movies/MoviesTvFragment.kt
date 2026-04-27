package com.streamflixreborn.streamflix.fragments.movies

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentMoviesTvBinding
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class MoviesTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentMoviesTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { MoviesViewModel(database) }

    private val appAdapter = AppAdapter()

    private var currentHasMore: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoviesTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeLanguageTabs()
        initializeMovies()

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
                        binding.vgvMovies.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is MoviesViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
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
                                btnIsLoadingRetry.setOnClickListener { viewModel.getMovies() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    viewModel.getMovies()
                                }
                                binding.vgvMovies.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun initializeLanguageTabs() {
        val providerName = UserPreferences.currentProvider?.name ?: return

        if (viewModel.isTypeFilterable) {
            // Providers like AnimeSama: "Série" / "Film" tabs — reload from server on each tab
            binding.tabLanguage.visibility = View.VISIBLE
            binding.tabFr.text = "Série"
            binding.tabVostfr.text = "Film"
            selectTab(binding.tabFr, binding.tabVostfr)
            setupTabFocus(binding.tabFr, binding.tabVostfr)
            binding.tabFr.setOnClickListener {
                selectTab(binding.tabFr, binding.tabVostfr)
                viewModel.setLanguageFilter("serie")
            }
            binding.tabVostfr.setOnClickListener {
                selectTab(binding.tabVostfr, binding.tabFr)
                viewModel.setLanguageFilter("film")
            }
        } else if (viewModel.isFilterable) {
            binding.tabLanguage.visibility = View.VISIBLE
            binding.tabFr.text = "FR"
            binding.tabVostfr.text = "VOSTFR"
            selectTab(binding.tabFr, binding.tabVostfr)
            setupTabFocus(binding.tabFr, binding.tabVostfr)
            binding.tabFr.setOnClickListener {
                selectTab(binding.tabFr, binding.tabVostfr)
                viewModel.setLanguageFilter("vf")
            }
            binding.tabVostfr.setOnClickListener {
                selectTab(binding.tabVostfr, binding.tabFr)
                viewModel.setLanguageFilter("vostfr")
            }
        }
    }

    private fun setupTabFocus(tab1: android.widget.TextView, tab2: android.widget.TextView) {
        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            val tv = v as android.widget.TextView
            if (hasFocus) {
                tv.setBackgroundColor(0x66E50914.toInt()) // brighter highlight when focused
                tv.setTextColor(0xFFFFFFFF.toInt())
            } else {
                // Restore proper background based on whether this tab is selected
                tv.setBackgroundColor(if (tv.typeface?.isBold == true) 0x33E50914.toInt() else android.graphics.Color.TRANSPARENT)
            }
        }
        tab1.onFocusChangeListener = focusListener
        tab2.onFocusChangeListener = focusListener
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
        binding.vgvMovies.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(requireContext().resources.getDimension(R.dimen.movies_spacing).toInt())
        }

        binding.root.requestFocus()
    }

    private fun displayMovies(movies: List<Movie>, hasMore: Boolean) {
        currentHasMore = hasMore

        appAdapter.submitList(movies.onEach {
            it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
        })

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreMovies() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}