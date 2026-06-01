package com.streamflixreborn.streamflix.fragments.home

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentHomeTvBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.MiniPlayerController
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.WiTvProvider
import kotlinx.coroutines.Runnable
import com.streamflixreborn.streamflix.utils.CacheUtils
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier

class HomeTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentHomeTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by lazy {
        val providerKey = UserPreferences.currentProvider?.name ?: "default"
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(AppDatabase.getInstance(requireContext())) as T
            }
        }
        ViewModelProvider(this, factory).get(providerKey, HomeViewModel::class.java)
    }

    private val appAdapter = AppAdapter()

    private val swiperHandler = Handler(Looper.getMainLooper())
    private var isBackgroundPinned = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 2026-05-12 (user "des retours tout le temps") : si on est restauré
        // sans provider sélectionné, on évite de toucher au viewModel (qui
        // crashe à AppDatabase.getInstance). Naviguer vers providers screen.
        if (UserPreferences.currentProvider == null) {
            try {
                androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.providers)
            } catch (_: Throwable) {}
            return
        }

        initializeHome()
        initializeMiniPlayer()
        initializeIptvActions()

        // Lightweight refresh when provider changes
        viewLifecycleOwner.lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect {
                viewModel.getHome()
            }
        }

        // 2026-05-13 (user "vire ça de mon iptv" — la swiper FEATURED apparaissait
        // sur les chunks IPTV à cause de Category.FEATURED == "". Le fix code
        // est appliqué, mais le HomeCache disque a déjà serializé les vieux
        // chunks. One-shot migration : clear le cache home des providers IPTV
        // une fois pour évacuer les mauvaises catégories. Le flag est posé
        // une seule fois, prochaines lectures normales.
        kotlin.runCatching {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
            val MIGRATION_KEY = "home_cache_iptv_chunked_v3_cleared"
            if (!prefs.getBoolean(MIGRATION_KEY, false)) {
                val current = UserPreferences.currentProvider
                if (current is com.streamflixreborn.streamflix.providers.IptvProvider) {
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(requireContext(), current)
                    Log.d("HomeTv", "One-shot: cleared home cache for ${current.name}")
                }
                prefs.edit().putBoolean(MIGRATION_KEY, true).apply()
            }
        }

        // Initial load — only if not already loaded (avoids flicker on view recreation)
        if (!viewModel.hasLoaded) {
            viewModel.getHome()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                Log.d("HomeTv", "State: ${state::class.simpleName} ${if (state is HomeViewModel.State.SuccessLoading) "(${state.categories.size} cats)" else ""}")
                when (state) {
                    HomeViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        displayHome(state.categories)
                        binding.vgvHome.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is HomeViewModel.State.FailedLoading -> {
                        // Pas de toast ni UI retry si aucun provider sélectionné (retour sur écran providers)
                        if (state.error.message == "No provider selected") {
                            binding.isLoading.root.visibility = View.GONE
                            return@collect
                        }

                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getHome()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener { viewModel.getHome() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.getHome()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                            binding.vgvHome.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        // Riavvia il carosello se i dati sono già stati caricati e il fragment è visibile
        appAdapter.items
            .filterIsInstance<Category>()
            .firstOrNull { it.name == Category.FEATURED }
            ?.let {
                resetSwiperSchedule()
            }
    }

    override fun onStop() {
        super.onStop()
        swiperHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.vgvHome)
        // Don't clear onIptvChannelClick — the new fragment's onViewCreated sets it,
        // but this onDestroyView can fire AFTER, causing a race condition.
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        if (MiniPlayerController.transitioningToFullscreen) {
            // Skip ALL ExoPlayer/PlayerView ops — stopAsync handles deferred release
            Log.d("HomeTv", "onPause: skipping cleanup (transitioning to fullscreen)")
            return
        }
        // Keep the mini player running — only detach the surface
        if (_binding != null) {
            binding.miniPlayerView.player = null
        }
        // Release any detached player (from navigateToFullPlayer) now that navigation happened
        MiniPlayerController.releaseDetachedPlayer()
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        val channelId = MiniPlayerController.currentChannelId ?: return

        // If the player was released (e.g. went to fullscreen), re-init and replay
        if (MiniPlayerController.getPlayer() == null) {
            Log.d("HomeTv", "onResume: player was released, re-initializing for $channelId")
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

        // Re-set interceptor if cleared
        if (MiniPlayerController.onIptvChannelClick == null) {
            MiniPlayerController.onIptvChannelClick = { tvShow ->
                if (tvShow.id == MiniPlayerController.currentChannelId) {
                    if (MiniPlayerController.isPlaying()) {
                        Log.d("HomeTv", "Same channel clicked (READY), flagging for transfer (onResume): ${tvShow.title}")
                        MiniPlayerController.transitioningToFullscreen = true
                        if (_binding != null) { binding.miniPlayerView.player = null }
                    } else {
                        Log.d("HomeTv", "Same channel clicked (NOT READY), stopping mini player (onResume): ${tvShow.title}")
                        MiniPlayerController.stopAsync()
                    }
                    false
                } else {
                    Log.d("HomeTv", "Mini player intercept (onResume): ${tvShow.title}")
                    MiniPlayerController.playChannel(tvShow.id, tvShow.title, tvShow.poster)
                    true
                }
            }
        }
    }


    private fun initializeMiniPlayer() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        // WiTV v2 : pas de mini-player, 1 clic = fullscreen direct
        val isWiTv = UserPreferences.currentProvider?.name?.contains("WiTV") == true
        if (!isIptv || !UserPreferences.miniPlayerEnabled || isWiTv) {
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
                        updateHomeGridForMiniPlayer(false)
                    }
                    is MiniPlayerController.State.Loading -> {
                        binding.miniPlayerContainer.visibility = View.VISIBLE
                        updateHomeGridForMiniPlayer(true)
                        binding.miniPlayerChannelName.text = state.channelName
                        binding.miniPlayerLoading.visibility = View.VISIBLE
                    }
                    is MiniPlayerController.State.Playing -> {
                        binding.miniPlayerContainer.visibility = View.VISIBLE
                        updateHomeGridForMiniPlayer(true)
                        binding.miniPlayerChannelName.text = state.channelName
                        binding.miniPlayerLoading.visibility = View.GONE
                        updatePauseButton()
                        state.channelPoster?.let { poster ->
                            Glide.with(this@HomeTvFragment).load(poster).into(binding.miniPlayerChannelLogo)
                        }
                    }
                    is MiniPlayerController.State.Error -> {
                        binding.miniPlayerLoading.visibility = View.GONE
                        Log.e("HomeTv", "Mini player error: ${state.message}")
                        // 2026-05-14 (user "tu vois pas que la vidéo mouline depuis tout
                        // à l'heure") : feedback visible pour ne pas laisser le user
                        // dans le flou.
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
                // 2026-05-31 : ne transférer que si le mini player est READY.
                // Si encore en chargement, stopAsync et laisser le fullscreen
                // charger depuis zéro (évite le stuck "plus charger").
                if (MiniPlayerController.isPlaying()) {
                    Log.d("HomeTv", "Same channel clicked (READY), flagging for transfer: ${tvShow.title}")
                    MiniPlayerController.transitioningToFullscreen = true
                    if (_binding != null) { binding.miniPlayerView.player = null }
                } else {
                    Log.d("HomeTv", "Same channel clicked (NOT READY), stopping mini player: ${tvShow.title}")
                    MiniPlayerController.stopAsync()
                }
                false
            } else {
                Log.d("HomeTv", "Mini player intercept: ${tvShow.title} (${tvShow.id})")
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
                        // marginEnd decreases when moving right, increases when moving left
                        lp.marginEnd = (origMarginEnd - dx.toInt()).coerceIn(0, parent.width - container.width)
                        lp.topMargin = (origMarginTop + dy.toInt()).coerceIn(0, parent.height - container.height)
                        container.layoutParams = lp
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = isDragging
                    isDragging = false
                    wasDragging // consume only if we were dragging
                }
                else -> false
            }
        }

        // Resize via pinch or scroll-wheel on the container itself
        var resizeStartWidth = 0
        var resizeStartY = 0f

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

        // v70 : CUT propre — on COUPE le mini-player au lieu de transférer le player.
        // Le fullscreen se reconnecte de zéro (plus fiable, pas de bug de transition).
        MiniPlayerController.stopAsync()
        if (_binding != null) {
            binding.miniPlayerView.player = null
        }

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
            Log.e("HomeTv", "navigateToFullPlayer failed: ${e.message}", e)
        }
    }

    private var swiperHasLastFocus: Boolean = false
    fun updateBackground(uri: String?, swiperHasFocus: Boolean? = false) {
        // IPTV channel logos don't work as backgrounds — skip
        if (UserPreferences.currentProvider is IptvProvider) return
        if (swiperHasFocus == null && isBackgroundPinned) return
        if (swiperHasFocus == null && !swiperHasLastFocus) return

        Glide.with(requireContext())
            .load(com.streamflixreborn.streamflix.utils.optimizeArtworkUrl(uri, 1280))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivHomeBackground)
        swiperHasLastFocus = swiperHasFocus ?: swiperHasLastFocus
    }

    fun pinBackground(uri: String?) {
        // IPTV channel logos don't work as backgrounds — skip
        if (UserPreferences.currentProvider is IptvProvider) return
        isBackgroundPinned = true
        Glide.with(requireContext())
            .load(com.streamflixreborn.streamflix.utils.optimizeArtworkUrl(uri, 1280))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivHomeBackground)
    }

    fun releasePinnedBackground() {
        if (!isBackgroundPinned) return
        isBackgroundPinned = false
        syncFeaturedBackground()
    }

    private var homeGridConstrained = false

    /**
     * Constrain the home grid width so horizontal swipers stop
     * at the left edge of the mini player instead of scrolling behind it.
     */
    /** 2026-05-13 (user "fait en sorte que les chaînes ne restent pas cachées
     *  derrière le mini lecteur") : au lieu de rétrécir la largeur (qui faisait
     *  que les chaînes du côté droit disparaissaient = "cachées"), on pousse le
     *  grid VERS LE BAS pour que la 1re row arrive sous le mini-player. Comme
     *  ça toutes les chaînes restent affichées en pleine largeur, juste un peu
     *  plus bas. Le mini-player occupe le coin top-right au-dessus du vide. */
    private fun updateHomeGridForMiniPlayer(miniPlayerVisible: Boolean) {
        if (_binding == null) return
        if (miniPlayerVisible == homeGridConstrained) return
        homeGridConstrained = miniPlayerVisible
        val grid = binding.vgvHome
        val params = grid.layoutParams as ConstraintLayout.LayoutParams
        // Reset largeur à 100% (on garde plus le rétrécissement)
        params.matchConstraintPercentWidth = 1f
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        // Padding top dynamique : mini-player height ≈ 45% width × 9/16. Sur
        // 1080p TV ça fait ~280-300dp avec sa marge top de 16dp. On met 320dp
        // de top padding quand visible pour être safe sur toutes résolutions.
        val density = resources.displayMetrics.density
        val basePaddingTop = (16 * density).toInt()
        val miniPlayerSafePadding = (320 * density).toInt()
        grid.setPadding(
            grid.paddingLeft,
            if (miniPlayerVisible) miniPlayerSafePadding else basePaddingTop,
            grid.paddingRight,
            grid.paddingBottom,
        )
        grid.layoutParams = params
    }

    private fun initializeHome() {
        binding.vgvHome.apply {
            setHasFixedSize(true)
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.home_spacing).toInt() * 2)
        }

        binding.root.requestFocus()
    }

    /** 2026-05-13 (user "il faut que tu fasses la même chose pour la TV") :
     *  initialise les boutons IPTV top-left :
     *   - MyIptvProvider : catégories + filtre langue (2 boutons)
     *   - VavooProvider  : picker pays (globe seul, catégories cachées)
     *   - Autres         : tout caché */
    private fun initializeIptvActions() {
        val current = UserPreferences.currentProvider
        val isMyIptv = current is com.streamflixreborn.streamflix.providers.MyIptvProvider
        val isVavoo = current is com.streamflixreborn.streamflix.providers.VavooProvider
        if (!isMyIptv && !isVavoo) {
            binding.llIptvActions.visibility = View.GONE
            return
        }
        // 2026-05-13 (user "non dans la bar de gauche y a la place") : les
        // boutons IPTV sont maintenant dans la sidebar nav_main (items
        // iptv_categories_menu et iptv_language_menu). On garde le LinearLayout
        // GONE pour ne pas occuper d'espace dans le fragment.
        binding.llIptvActions.visibility = View.GONE
        // 2026-05-13 : sync le filtre pays Vavoo depuis prefs persistées au cas
        // où l'app a été relancée (le @Volatile var dans VavooProvider repart
        // à "France" sinon).
        if (isVavoo) {
            val saved = com.streamflixreborn.streamflix.providers.VavooCountrySettings
                .getCurrent(requireContext())
            com.streamflixreborn.streamflix.providers.VavooProvider.setCountryFilter(saved.filterValue)
        }
    }

    /** 2026-05-13 (user "tu peux pas faire comme MyIPTV et choisir la langue") :
     *  picker pays pour Vavoo. Refresh le home après changement. */
    private fun showVavooCountryPicker() {
        val ctx = requireContext()
        val current = com.streamflixreborn.streamflix.providers.VavooCountrySettings.getCurrent(ctx)
        val list = com.streamflixreborn.streamflix.providers.VavooCountrySettings.list
        val items = list.map {
            "${it.flag} ${it.label}${if (it.code == current.code) "  ✓" else ""}"
        }.toTypedArray()
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Pays Vavoo")
            .setItems(items) { _, idx ->
                val picked = list[idx]
                if (picked.code == current.code) return@setItems
                com.streamflixreborn.streamflix.providers.VavooCountrySettings.setCurrent(ctx, picked)
                com.streamflixreborn.streamflix.providers.VavooProvider.setCountryFilter(picked.filterValue)
                android.widget.Toast.makeText(
                    ctx,
                    "Vavoo : ${picked.flag} ${picked.label} — chargement…",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                // Force home reload pour récupérer le nouveau pays.
                viewModel.getHome()
            }
            .setNeutralButton("Miroir") { _, _ -> showVavooMirrorPicker() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-05-28 : picker miroir Vavoo (TV fragment). */
    private fun showVavooMirrorPicker() {
        val ctx = requireContext()
        val current = com.streamflixreborn.streamflix.providers.VavooMirrorSettings.getCurrent(ctx)
        val mirrors = com.streamflixreborn.streamflix.providers.VavooMirrorSettings.list
        val items = mirrors.map {
            "${it.label}${if (it.url == current.url) "  ✓" else ""}"
        }.toTypedArray()
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Miroir Vavoo")
            .setItems(items) { _, idx ->
                val picked = mirrors[idx]
                if (picked.url == current.url) return@setItems
                com.streamflixreborn.streamflix.providers.VavooMirrorSettings.setCurrent(ctx, picked)
                android.widget.Toast.makeText(
                    ctx,
                    "Miroir Vavoo : ${picked.label} — chargement…",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                viewModel.getHome()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun displayHome(categories: List<Category>) {
        categories
            .find { it.name == Category.FEATURED }
            ?.also {
                val index = appAdapter.items
                    .filterIsInstance<Category>()
                    .find { item -> item.name == Category.FEATURED }
                    ?.selectedIndex
                    ?: 0
                it.selectedIndex = index
                
                // Initialize background with first item from featured category immediately
                val firstItem = it.list.getOrNull(index)
                val poster = when (firstItem) {
                    is Movie -> firstItem.banner
                    is TvShow -> firstItem.banner
                    else -> null
                }
                // Force background update without waiting for focus
                if (poster != null) {
                    updateBackground(poster, null)
                }
                
                resetSwiperSchedule()
            }

        categories
            .find { it.name == Category.CONTINUE_WATCHING }
            ?.also {
                it.name = getString(R.string.home_continue_watching)
                it.list.forEach { show ->
                    when (show) {
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.FAVORITE_MOVIES }
            ?.also { it.name = getString(R.string.home_favorite_movies) }

        categories
            .find { it.name == Category.FAVORITE_TV_SHOWS }
            ?.also { it.name = getString(R.string.home_favorite_tv_shows) }

        appAdapter.submitList(
            categories
                .filter { it.list.isNotEmpty() }
                .onEach { category ->
                    if (category.name != getString(R.string.home_continue_watching)) {
                        category.list.forEach { show ->
                            when (show) {
                                is Episode -> show.itemType = AppAdapter.Type.EPISODE_TV_ITEM
                                is Movie -> show.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                                is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                            }
                        }
                    }
                    category.itemSpacing = resources.getDimension(R.dimen.home_spacing).toInt()
                    category.itemType = when (category.name) {
                        Category.FEATURED -> AppAdapter.Type.CATEGORY_TV_SWIPER
                        else -> AppAdapter.Type.CATEGORY_TV_ITEM
                    }
                }
        )
    }

    fun resetSwiperSchedule() {
        swiperHandler.removeCallbacksAndMessages(null)
        swiperHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isBackgroundPinned) {
                    swiperHandler.postDelayed(this, 15_000)
                    return
                }

                val position = appAdapter.items
                    .filterIsInstance<Category>()
                    .find { it.name == Category.FEATURED }
                    ?.let { category ->
                        category.selectedIndex = (category.selectedIndex + 1) % category.list.size
                        
                        // Update background when swiper rotates automatically
                        val currentItem = category.list.getOrNull(category.selectedIndex)
                        val poster = when (currentItem) {
                            is Movie -> currentItem.banner
                            is TvShow -> currentItem.banner
                            else -> null
                        }
                        // Update background if it's not null
                        if (poster != null) {
                            updateBackground(poster, null)
                        }

                        appAdapter.items.indexOf(category)
                    }
                    ?.takeIf { it != -1 }

                if (position == null) {
                    swiperHandler.removeCallbacksAndMessages(null)
                    return
                }

                appAdapter.notifyItemChanged(position)
                swiperHandler.postDelayed(this, 15_000)
            }
        }, 15_000)
    }

    private fun syncFeaturedBackground() {
        val featured = appAdapter.items
            .filterIsInstance<Category>()
            .find { it.name == Category.FEATURED }
            ?: return

        val currentItem = featured.list.getOrNull(featured.selectedIndex)
        val poster = when (currentItem) {
            is Movie -> currentItem.banner
            is TvShow -> currentItem.banner
            else -> null
        }

        if (poster != null) {
            updateBackground(poster, null)
        }
    }
}
