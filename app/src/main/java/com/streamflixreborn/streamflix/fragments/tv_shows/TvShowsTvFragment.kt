package com.streamflixreborn.streamflix.fragments.tv_shows

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentTvShowsTvBinding
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.utils.MiniPlayerController
import com.streamflixreborn.streamflix.utils.UserPreferences
import androidx.transition.TransitionManager
import androidx.transition.AutoTransition
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class TvShowsTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentTvShowsTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { TvShowsViewModel(database) }

    private val appAdapter = AppAdapter()

    private var currentHasMore: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvShowsTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
                        binding.vgvTvShows.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is TvShowsViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
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
                                btnIsLoadingRetry.setOnClickListener { viewModel.getTvShows() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    viewModel.getTvShows()
                                }
                                binding.vgvTvShows.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Don't clear onIptvChannelClick — race condition with new fragment's onViewCreated
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        if (MiniPlayerController.transitioningToFullscreen) {
            Log.d("TvShowsTv", "onPause: skipping cleanup (transitioning to fullscreen)")
            return
        }
        if (_binding != null) {
            binding.miniPlayerView.player = null
        }
        MiniPlayerController.releaseDetachedPlayer()
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        // 2026-06-09 : applique le fond d'écran personnalisé.
        com.streamflixreborn.streamflix.utils.AppearanceManager.applyTo(binding.root)
        val channelId = MiniPlayerController.currentChannelId ?: return

        if (MiniPlayerController.getPlayer() == null) {
            Log.d("TvShowsTv", "onResume: player was released, re-initializing for $channelId")
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

        com.streamflixreborn.streamflix.utils.MiniPlayerController.applyMiniPlayerVisibility(binding.miniPlayerContainer, View.VISIBLE)
        syncOverlayVisibility()
        binding.miniPlayerChannelName.text = MiniPlayerController.currentChannelName ?: ""
        MiniPlayerController.currentChannelPoster?.let { poster ->
            Glide.with(this).load(poster).into(binding.miniPlayerChannelLogo)
        }
        updatePauseButton()

        if (MiniPlayerController.onIptvChannelClick == null) {
            MiniPlayerController.onIptvChannelClick = { tvShow ->
                if (tvShow.id == MiniPlayerController.currentChannelId) {
                    Log.d("TvShowsTv", "Same channel → TRANSFER mini → fullscreen (onResume): ${tvShow.title}")
                    MiniPlayerController.transitioningToFullscreen = true
                    if (_binding != null) { binding.miniPlayerView.player = null; binding.miniPlayerContainer.visibility = View.GONE; syncOverlayVisibility() }
                    false
                } else {
                    Log.d("TvShowsTv", "Mini player intercept (onResume): ${tvShow.title}")
                    MiniPlayerController.playChannel(tvShow.id, tvShow.title, tvShow.poster)
                    true
                }
            }
        }
    }


    /** 2026-05-14 (user "j'ai deux icônes en haut en trop car ils sont dans la
     *  barre de gauche") : les boutons IPTV sont dans la sidebar nav_main. Le
     *  LinearLayout en haut est caché en permanence pour pas faire doublon. */
    private fun initializeIptvActions() {
        binding.llIptvActions.visibility = View.GONE
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
            // Language-filterable providers: "FR" / "VOSTFR" tabs
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
                // (selectTab will be called on click, so just dim slightly)
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

    private fun initializeTvShows() {
        binding.vgvTvShows.apply {
            val spacing = requireContext().resources.getDimension(R.dimen.tv_shows_spacing).toInt()
            setItemSpacing(spacing)
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
        }

        binding.root.requestFocus()
    }

    private fun initializeMiniPlayer() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        if (!isIptv || !UserPreferences.miniPlayerEnabled) {
            binding.miniPlayerContainer.visibility = View.GONE
            syncOverlayVisibility()
            MiniPlayerController.onIptvChannelClick = null
            return
        }

        MiniPlayerController.initPlayer(requireContext())
        binding.miniPlayerView.player = MiniPlayerController.getPlayer()

        if (MiniPlayerController.currentChannelId != null) {
            com.streamflixreborn.streamflix.utils.MiniPlayerController.applyMiniPlayerVisibility(binding.miniPlayerContainer, View.VISIBLE)
            syncOverlayVisibility()
            binding.miniPlayerChannelName.text = MiniPlayerController.currentChannelName ?: ""
            MiniPlayerController.currentChannelPoster?.let { poster ->
                Glide.with(this).load(poster).into(binding.miniPlayerChannelLogo)
            }
            updateGridForMiniPlayer(MiniPlayerController.shouldShrinkGridForCurrent())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MiniPlayerController.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                val wasVisible = binding.miniPlayerContainer.visibility == View.VISIBLE
                when (state) {
                    is MiniPlayerController.State.Idle -> {
                        if (wasVisible) {
                            TransitionManager.beginDelayedTransition(
                                binding.root as ViewGroup,
                                AutoTransition().apply { duration = 300 }
                            )
                        }
                        binding.miniPlayerContainer.visibility = View.GONE
                        syncOverlayVisibility()
                        updateGridForMiniPlayer(false)
                    }
                    is MiniPlayerController.State.Loading -> {
                        if (!wasVisible) {
                            TransitionManager.beginDelayedTransition(
                                binding.root as ViewGroup,
                                AutoTransition().apply { duration = 300 }
                            )
                        }
                        com.streamflixreborn.streamflix.utils.MiniPlayerController.applyMiniPlayerVisibility(binding.miniPlayerContainer, View.VISIBLE)
                        syncOverlayVisibility()
                        binding.miniPlayerChannelName.text = state.channelName
                        binding.miniPlayerLoading.visibility = View.VISIBLE
                        updateGridForMiniPlayer(MiniPlayerController.shouldShrinkGridForCurrent())
                    }
                    is MiniPlayerController.State.Playing -> {
                        if (!wasVisible) {
                            TransitionManager.beginDelayedTransition(
                                binding.root as ViewGroup,
                                AutoTransition().apply { duration = 300 }
                            )
                        }
                        com.streamflixreborn.streamflix.utils.MiniPlayerController.applyMiniPlayerVisibility(binding.miniPlayerContainer, View.VISIBLE)
                        syncOverlayVisibility()
                        binding.miniPlayerChannelName.text = state.channelName
                        binding.miniPlayerLoading.visibility = View.GONE
                        updatePauseButton()
                        updateGridForMiniPlayer(MiniPlayerController.shouldShrinkGridForCurrent())
                        state.channelPoster?.let { poster ->
                            Glide.with(this@TvShowsTvFragment).load(poster).into(binding.miniPlayerChannelLogo)
                        }
                    }
                    is MiniPlayerController.State.Error -> {
                        binding.miniPlayerLoading.visibility = View.GONE
                        Log.e("TvShowsTv", "Mini player error: ${state.message}")
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
                Log.d("TvShowsTv", "Same channel → TRANSFER mini → fullscreen: ${tvShow.title}")
                MiniPlayerController.transitioningToFullscreen = true
                if (_binding != null) { binding.miniPlayerView.player = null; binding.miniPlayerContainer.visibility = View.GONE; syncOverlayVisibility() }
                false
            } else {
                Log.d("TvShowsTv", "Mini player intercept: ${tvShow.title} (${tvShow.id})")
                MiniPlayerController.playChannel(tvShow.id, tvShow.title, tvShow.poster)
                true
            }
        }

        setupMiniPlayerDragAndResize()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMiniPlayerDragAndResize() {
        val container = binding.miniPlayerContainer
        val parent = container.parent as? View ?: return

        var dragStartX = 0f
        var dragStartY = 0f
        var origMarginEnd = 0
        var origMarginTop = 0
        var isDragging = false

        // Drag via the overlay bar (bottom bar with channel name)
        binding.miniPlayerOverlay.setOnTouchListener { _, event ->
            val lp = container.layoutParams as ConstraintLayout.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    origMarginEnd = lp.marginEnd
                    origMarginTop = lp.topMargin
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (!isDragging && (dx * dx + dy * dy) > 100) isDragging = true
                    if (isDragging) {
                        lp.marginEnd = (origMarginEnd - dx.toInt()).coerceIn(0, parent.width - container.width)
                        lp.topMargin = (origMarginTop + dy.toInt()).coerceIn(0, parent.height - container.height)
                        container.layoutParams = lp
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = isDragging
                    isDragging = false
                    wasDragging
                }
                else -> false
            }
        }

        // Resize via scroll-wheel on the container
        container.setOnGenericMotionListener { _, event ->
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val lp = container.layoutParams as ConstraintLayout.LayoutParams
                val currentPercent = lp.matchConstraintPercentWidth
                val newPercent = (currentPercent + scrollY * 0.03f).coerceIn(0.2f, 0.7f)
                lp.matchConstraintPercentWidth = newPercent
                container.layoutParams = lp
                true
            } else false
        }
    }

    /** Synchro visibilité overlay (barre boutons) avec le container vidéo. */
    private fun syncOverlayVisibility() {
        if (_binding == null) return
        binding.miniPlayerOverlay.visibility = binding.miniPlayerContainer.visibility
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
        if (!isAdded || _binding == null) return
        val channelId = MiniPlayerController.currentChannelId ?: return
        val channelName = MiniPlayerController.currentChannelName ?: channelId
        val channelPoster = MiniPlayerController.currentChannelPoster

        // Flag for transfer — PlayerTvFragment.onViewCreated will steal the player
        MiniPlayerController.transitioningToFullscreen = true
        if (_binding != null) { binding.miniPlayerView.player = null }

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
            Log.e("TvShowsTv", "navigateToFullPlayer failed: ${e.message}", e)
        }
    }

    private var miniPlayerLayoutApplied = false

    /** 2026-06-23 (user "sur la télé quand on affiche le mini lecteur il faut
     *  descendre le panel pour rester bien visible — regarde sur la
     *  chromecast — ça a pas marché sur vavoo") :
     *  Auparavant on rétrécissait la grille à 52% width pour la garder à
     *  GAUCHE du mini-player. L'user préfère que la grille descende SOUS
     *  le mini (= même comportement que le home TV). Push vertical via
     *  paddingTop dynamique calculé sur la hauteur du mini-player
     *  (50% width × 9/16 + 8dp marge), avec restauration du paddingTop
     *  de base (@dimen/tv_shows_spacing = 16dp) quand le mini est masqué. */
    private fun updateGridForMiniPlayer(miniPlayerVisible: Boolean) {
        if (_binding == null) return
        if (miniPlayerVisible == miniPlayerLayoutApplied) return
        miniPlayerLayoutApplied = miniPlayerVisible
        val grid = binding.vgvTvShows
        val density = resources.displayMetrics.density
        val basePaddingTop = (16 * density).toInt()  // @dimen/tv_shows_spacing = 16dp
        if (!miniPlayerVisible) {
            grid.setPadding(grid.paddingLeft, basePaddingTop, grid.paddingRight, grid.paddingBottom)
            try {
                val lp = grid.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                // 2026-06-23 v3 fix : restaurer la width pleine (au cas où une
                // ancienne v2 aurait laissé matchConstraintPercentWidth=0.52f).
                lp.matchConstraintPercentWidth = 1f
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.horizontalBias = 0.5f
                if (lp.topMargin != 0) {
                    lp.topMargin = 0
                }
                grid.layoutParams = lp
            } catch (_: Throwable) {}
            grid.requestLayout()
            android.util.Log.d("TvShowsTv", "updateGridForMiniPlayer(false): RESET topMargin=0 width=1f paddingTop=$basePaddingTop")
            return
        }
        // 2026-06-23 v3 (user "C'est toujours pas bon sur TV — descendre tout
        //   ce qui est derrière, comme un glissement") : on n'utilise plus le
        //   paddingTop (= ignoré visuellement par le VerticalGridView Leanback
        //   en runtime). On utilise le topMargin du LayoutParams pour pousser
        //   tout le grid vers le bas comme un véritable glissement.
        //   IMPORTANT : on FORCE aussi width=1f (= pleine largeur) au cas où
        //   une ancienne version aurait laissé matchConstraintPercentWidth=0.52f
        //   (= seul TF1 visible au lieu des 770 chaînes Vavoo).
        val fallbackH = (resources.displayMetrics.widthPixels * 0.50 * 9.0 / 16.0).toInt()
        val initialPadding = fallbackH + (8 * density).toInt()
        grid.setPadding(grid.paddingLeft, basePaddingTop, grid.paddingRight, grid.paddingBottom)
        try {
            val lp = grid.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            lp.matchConstraintPercentWidth = 1f
            lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.horizontalBias = 0.5f
            lp.topMargin = initialPadding
            grid.layoutParams = lp
        } catch (_: Throwable) {}
        grid.requestLayout()
        android.util.Log.d("TvShowsTv", "updateGridForMiniPlayer(true): topMargin=$initialPadding px width=1f (fallbackH=$fallbackH, density=$density, widthPx=${resources.displayMetrics.widthPixels})")
        // Raffinement via .post quand la vraie hauteur est mesurée.
        binding.miniPlayerContainer.post {
            if (_binding == null) return@post
            val miniH = binding.miniPlayerContainer.height
            if (miniH > 0 && miniH != fallbackH) {
                val refinedPadding = miniH + (8 * density).toInt()
                binding.vgvTvShows.setPadding(
                    binding.vgvTvShows.paddingLeft,
                    refinedPadding,
                    binding.vgvTvShows.paddingRight,
                    binding.vgvTvShows.paddingBottom,
                )
                android.util.Log.d("TvShowsTv", "updateGridForMiniPlayer refined: paddingTop=$refinedPadding px (miniH=$miniH)")
            }
        }
    }

    private fun displayTvShows(tvShows: List<TvShow>, hasMore: Boolean) {
        currentHasMore = hasMore

        appAdapter.submitList(tvShows.onEach {
            it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
        })

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreTvShows() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}