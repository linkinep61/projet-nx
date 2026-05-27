package com.streamflixreborn.streamflix.fragments.tv_shows

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentTvShowsMobileBinding
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.WiTvProvider
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.MiniPlayerController
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import com.streamflixreborn.streamflix.utils.CacheUtils
import kotlinx.coroutines.launch

class TvShowsMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentTvShowsMobileBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { TvShowsViewModel(database) }

    private val appAdapter = AppAdapter()

    private var currentHasMore: Boolean = false
    private var shouldScrollToTop: Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvShowsMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        shouldScrollToTop = true
        initializeLanguageTabs()
        initializeTvShows()
        initializeMiniPlayer()
        initializeIptvActions()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowsViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    TvShowsViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is TvShowsViewModel.State.SuccessLoading -> {
                        displayTvShows(state.tvShows, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is TvShowsViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getTvShows()
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
                                val doRetry = { viewModel.getTvShows() }
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
        (binding.rvTvShows.layoutManager as? GridLayoutManager)?.spanCount = spanCount
        updateMiniPlayerLayout(newConfig.orientation)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Don't clear onIptvChannelClick — race condition with new fragment's onViewCreated
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            binding.miniPlayerView.player = null
        }
        MiniPlayerController.releaseDetachedPlayer()
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        val channelId = MiniPlayerController.currentChannelId ?: return

        if (MiniPlayerController.getPlayer() == null) {
            Log.d("TvShowsMobile", "onResume: player was released, re-initializing for $channelId")
            MiniPlayerController.initPlayer(requireContext())
            binding.miniPlayerView.player = MiniPlayerController.getPlayer()
            MiniPlayerController.playChannel(
                channelId,
                MiniPlayerController.currentChannelName ?: channelId,
                MiniPlayerController.currentChannelPoster
            )
        } else {
            binding.miniPlayerView.player = MiniPlayerController.getPlayer()
        }

        binding.miniPlayerContainer.visibility = View.VISIBLE
        binding.miniPlayerChannelName.text = MiniPlayerController.currentChannelName ?: ""
        MiniPlayerController.currentChannelPoster?.let { poster ->
            Glide.with(this).load(poster).into(binding.miniPlayerChannelLogo)
        }
        updatePauseButton()

        if (MiniPlayerController.onIptvChannelClick == null) {
            MiniPlayerController.onIptvChannelClick = { tvShow ->
                if (tvShow.id == MiniPlayerController.currentChannelId) {
                    Log.d("TvShowsMobile", "Same channel, stopping mini player for fullscreen (onResume): ${tvShow.title}")
                    MiniPlayerController.stopAsync()
                    false
                } else {
                    Log.d("TvShowsMobile", "Mini player intercept (onResume): ${tvShow.title}")
                    MiniPlayerController.playChannel(tvShow.id, tvShow.title, tvShow.poster)
                    true
                }
            }
        }
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
                showIptvSeriesCategoryPicker()
            }
            binding.ivIptvLanguage.setOnClickListener {
                showIptvLanguageFilterPicker()
            }
        } else if (com.streamflixreborn.streamflix.utils.GenreFilter.isSupported(provider?.name)) {
            // 2026-05-26 : filtre genre TMDB
            binding.llIptvActions.visibility = View.VISIBLE
            binding.ivIptvCategories.visibility = View.VISIBLE
            binding.ivIptvLanguage.visibility = View.GONE
            binding.ivIptvCategories.setOnClickListener {
                showGenreFilterPicker()
            }
        } else {
            binding.llIptvActions.visibility = View.GONE
        }
    }

    private fun showIptvSeriesCategoryPicker() {
        val provider = com.streamflixreborn.streamflix.providers.MyIptvProvider
        val type = com.streamflixreborn.streamflix.utils.IptvClassifier.ContentType.SERIES
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
                provider.selectedCategorySeries = rawNames[idx]
                com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                    requireContext().applicationContext, provider,
                )
                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                // 2026-05-13 (user "des fois il faut cliquer plusieurs fois") :
                // double reload pour pas race avec la pagination précédente.
                viewModel.getTvShows()
                binding.root.postDelayed({ viewModel.getTvShows() }, 150L)
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
                    viewModel.getTvShows()
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
        val entries = com.streamflixreborn.streamflix.utils.GenreFilter.genres
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

    private fun initializeLanguageTabs() {
        val providerName = UserPreferences.currentProvider?.name ?: return

        if (viewModel.isTypeFilterable) {
            // Providers like AnimeSama: "Série" / "Film" tabs — reload from server on each tab
            binding.tabLanguage.visibility = View.VISIBLE
            binding.tabFr.text = "Série"
            binding.tabVostfr.text = "Film"
            selectTab(binding.tabFr, binding.tabVostfr)
            binding.tabFr.setOnClickListener {
                selectTab(binding.tabFr, binding.tabVostfr)
                viewModel.setLanguageFilter("serie")
            }
            binding.tabVostfr.setOnClickListener {
                selectTab(binding.tabVostfr, binding.tabFr)
                viewModel.setLanguageFilter("film")
            }
        } else if (viewModel.isFilterable) {
            // Language-filterable providers: "FR" / "VOSTFR" tabs
            binding.tabLanguage.visibility = View.VISIBLE
            binding.tabFr.text = "FR"
            binding.tabVostfr.text = "VOSTFR"
            selectTab(binding.tabFr, binding.tabVostfr)
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

    private fun selectTab(selected: android.widget.TextView, other: android.widget.TextView) {
        selected.setTextColor(0xFFFFFFFF.toInt())
        selected.setTypeface(null, android.graphics.Typeface.BOLD)
        selected.setBackgroundColor(0x33E50914)
        other.setTextColor(0x80FFFFFF.toInt())
        other.setTypeface(null, android.graphics.Typeface.NORMAL)
        other.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private fun initializeTvShows() {
        binding.rvTvShows.apply {
            setHasFixedSize(true)
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            // Adapt grid columns: 6 in landscape, 3 in portrait
            val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 6 else 3
            (layoutManager as? GridLayoutManager)?.spanCount = spanCount
            addItemDecoration(
                SpacingItemDecoration(10.dp(requireContext()))
            )
        }
    }

    private fun initializeMiniPlayer() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        if (!isIptv || !UserPreferences.miniPlayerEnabled) {
            binding.miniPlayerContainer.visibility = View.GONE
            MiniPlayerController.onIptvChannelClick = null
            return
        }

        MiniPlayerController.initPlayer(requireContext())
        binding.miniPlayerView.player = MiniPlayerController.getPlayer()

        if (MiniPlayerController.currentChannelId != null) {
            binding.miniPlayerContainer.visibility = View.VISIBLE
            binding.miniPlayerChannelName.text = MiniPlayerController.currentChannelName ?: ""
            MiniPlayerController.currentChannelPoster?.let { poster ->
                Glide.with(this).load(poster).into(binding.miniPlayerChannelLogo)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MiniPlayerController.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MiniPlayerController.State.Idle -> {
                        binding.miniPlayerContainer.visibility = View.GONE
                    }
                    is MiniPlayerController.State.Loading -> {
                        binding.miniPlayerContainer.visibility = View.VISIBLE
                        binding.miniPlayerChannelName.text = state.channelName
                        binding.miniPlayerLoading.visibility = View.VISIBLE
                    }
                    is MiniPlayerController.State.Playing -> {
                        binding.miniPlayerContainer.visibility = View.VISIBLE
                        binding.miniPlayerChannelName.text = state.channelName
                        binding.miniPlayerLoading.visibility = View.GONE
                        updatePauseButton()
                        state.channelPoster?.let { poster ->
                            Glide.with(this@TvShowsMobileFragment).load(poster).into(binding.miniPlayerChannelLogo)
                        }
                    }
                    is MiniPlayerController.State.Error -> {
                        binding.miniPlayerLoading.visibility = View.GONE
                        Log.e("TvShowsMobile", "Mini player error: ${state.message}")
                        // 2026-05-14 (user "tu vois pas que la vidéo mouline depuis tout
                        // à l'heure") : feedback visible.
                        Toast.makeText(requireContext(),
                            "Stream indisponible — essaie une autre chaîne",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        binding.miniPlayerClose.setOnClickListener { MiniPlayerController.stop() }
        binding.miniPlayerPause.setOnClickListener {
            MiniPlayerController.togglePause()
            updatePauseButton()
        }
        binding.miniPlayerFullscreen.setOnClickListener { navigateToFullPlayer() }
        binding.miniPlayerView.setOnClickListener { navigateToFullPlayer() }

        MiniPlayerController.onIptvChannelClick = { tvShow ->
            if (tvShow.id == MiniPlayerController.currentChannelId) {
                Log.d("TvShowsMobile", "Same channel, stopping mini player for fullscreen: ${tvShow.title}")
                MiniPlayerController.stopAsync()
                false
            } else {
                Log.d("TvShowsMobile", "Mini player intercept: ${tvShow.title} (${tvShow.id})")
                MiniPlayerController.playChannel(tvShow.id, tvShow.title, tvShow.poster)
                true
            }
        }

        // Apply correct layout for current orientation
        updateMiniPlayerLayout(resources.configuration.orientation)
    }

    /**
     * Adjusts mini player layout based on orientation:
     * - Portrait: full width at top, ~200dp height
     * - Landscape: 1/3 screen on the right side, RecyclerView takes 2/3
     */
    private fun updateMiniPlayerLayout(orientation: Int) {
        if (_binding == null) return
        val container = binding.miniPlayerContainer
        val recycler = binding.rvTvShows
        val tabLang = binding.tabLanguage
        val root = binding.root as ConstraintLayout
        val cs = ConstraintSet()
        cs.clone(root)

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape: mini player on the right 1/3
            cs.connect(container.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            cs.connect(container.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.connect(container.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            cs.clear(container.id, ConstraintSet.START)
            cs.constrainPercentWidth(container.id, 0.33f)
            cs.constrainHeight(container.id, ConstraintSet.MATCH_CONSTRAINT)

            // Tab language: left 2/3
            cs.connect(tabLang.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(tabLang.id, ConstraintSet.END, container.id, ConstraintSet.START)
            cs.connect(tabLang.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)

            // RecyclerView: fill left 2/3 below tabs
            cs.connect(recycler.id, ConstraintSet.TOP, tabLang.id, ConstraintSet.BOTTOM)
            cs.connect(recycler.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(recycler.id, ConstraintSet.END, container.id, ConstraintSet.START)
            cs.connect(recycler.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)

            // Smaller player view height in landscape (fill the container)
            binding.miniPlayerView.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            // Portrait: full width at top
            cs.connect(container.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            cs.connect(container.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(container.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.clear(container.id, ConstraintSet.BOTTOM)
            cs.constrainPercentWidth(container.id, 1f)
            cs.constrainHeight(container.id, ConstraintSet.WRAP_CONTENT)

            // Tab language: full width below mini player
            cs.connect(tabLang.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(tabLang.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.connect(tabLang.id, ConstraintSet.TOP, container.id, ConstraintSet.BOTTOM)

            // RecyclerView: below tabs
            cs.connect(recycler.id, ConstraintSet.TOP, tabLang.id, ConstraintSet.BOTTOM)
            cs.connect(recycler.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(recycler.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.connect(recycler.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)

            // Reset player height to 200dp
            binding.miniPlayerView.layoutParams.height = (200 * resources.displayMetrics.density).toInt()
        }

        cs.applyTo(root)
    }

    private fun updatePauseButton() {
        if (_binding == null) return
        val icon = if (MiniPlayerController.isPaused()) {
            R.drawable.ic_mini_player_play
        } else {
            R.drawable.ic_mini_player_pause
        }
        binding.miniPlayerPause.setImageResource(icon)
    }

    private fun navigateToFullPlayer() {
        val channelId = MiniPlayerController.currentChannelId ?: return
        val channelName = MiniPlayerController.currentChannelName ?: channelId
        val channelPoster = MiniPlayerController.currentChannelPoster

        MiniPlayerController.releasePlayerKeepState()

        val videoType = Video.Type.Episode(
            id = channelId, number = 1, title = channelName, poster = channelPoster,
            overview = null,
            tvShow = Video.Type.Episode.TvShow(id = channelId, title = channelName, poster = channelPoster, banner = null, releaseDate = null, imdbId = null),
            season = Video.Type.Episode.Season(number = 1, title = "Live"),
        )

        val args = Bundle().apply {
            putString("id", channelId)
            putString("title", channelName)
            putString("subtitle", channelName)
            putSerializable("videoType", videoType)
        }
        try {
            findNavController().navigate(R.id.action_global_player, args)
        } catch (e: Exception) {
            android.util.Log.e("TvShowsMobile", "navigateToFullPlayer failed: ${e.message}", e)
        }
    }

    private fun displayTvShows(tvShows: List<TvShow>, hasMore: Boolean) {
        currentHasMore = hasMore

        appAdapter.submitList(tvShows.onEach {
            it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
        })

        if (shouldScrollToTop) {
            shouldScrollToTop = false
            binding.rvTvShows.post { binding.rvTvShows.scrollToPosition(0) }
        }

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreTvShows() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}