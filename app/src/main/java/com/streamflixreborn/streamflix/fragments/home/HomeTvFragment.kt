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
import androidx.transition.TransitionManager
import androidx.transition.AutoTransition
import com.streamflixreborn.streamflix.providers.IptvProvider
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
    /** 2026-06-08 : dernier state observé via le collect, pour pouvoir
     *  détecter au onResume si on est resté stuck en Loading et relancer. */
    private var lastObservedState: HomeViewModel.State? = null

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

    // 2026-06-09 — paging World Live grid (60 items/chunk).
    private var worldLivePagedItems: List<AppAdapter.Item> = emptyList()
    private var worldLivePagedLoaded: Int = 0
    private val worldLivePageSize: Int = 60
    private var worldLiveScrollListener: androidx.recyclerview.widget.RecyclerView.OnScrollListener? = null

    private fun loadMoreWorldLive() {
        if (worldLivePagedLoaded >= worldLivePagedItems.size) return
        val next = (worldLivePagedLoaded + worldLivePageSize).coerceAtMost(worldLivePagedItems.size)
        appAdapter.submitList(worldLivePagedItems.subList(0, next))
        worldLivePagedLoaded = next
    }

    private fun attachWorldLivePagingScrollListener() {
        if (worldLiveScrollListener != null) return
        val listener = object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager ?: return
                val total = lm.itemCount
                val lastVisible = when (lm) {
                    is androidx.recyclerview.widget.GridLayoutManager -> lm.findLastVisibleItemPosition()
                    is androidx.recyclerview.widget.LinearLayoutManager -> lm.findLastVisibleItemPosition()
                    is androidx.leanback.widget.VerticalGridView -> {
                        // Leanback : pas de findLastVisibleItemPosition direct, on
                        //   approxime avec rv.childCount + position du premier child.
                        val first = rv.getChildAdapterPosition(rv.getChildAt(0) ?: return)
                        first + rv.childCount - 1
                    }
                    else -> return
                }
                if (lastVisible >= total - 10) loadMoreWorldLive()
            }
        }
        worldLiveScrollListener = listener
        try { binding.vgvHome.addOnScrollListener(listener) } catch (_: Throwable) {}
    }

    private fun detachWorldLivePagingScrollListener() {
        val listener = worldLiveScrollListener ?: return
        try { binding.vgvHome.removeOnScrollListener(listener) } catch (_: Throwable) {}
        worldLiveScrollListener = null
    }

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
                lastObservedState = state
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
        // 2026-07-04 (user "à froid le film met 12s à s'ouvrir, l'app est en crise mémoire :
        //   heap 214MB, GC de 2s en boucle") : quand on entre dans le player, on MET GLIDE EN
        //   PAUSE. Sinon la home (derrière) continue à décoder ses jaquettes (TMDB + flemmix)
        //   → allocation massive → GC thrashing sur la Chromecast low-RAM → le scrape natif des
        //   serveurs est affamé (parse Jsoup à 8s au lieu de 0,4s). Repris dans onResume.
        // 2026-07-12 : la pause Glide EFFECTIVE (niveau activité, couvre la grille) est faite dans
        //   le PLAYER (à l'entrée de la vidéo), PAS ici — sinon elle couperait aussi les jaquettes
        //   casting / recommandations de la fiche synopsis (même manager d'activité). Ici on garde
        //   juste la pause fragment historique (inoffensive).
        try { com.bumptech.glide.Glide.with(this).pauseAllRequestsRecursive() } catch (_: Throwable) {}
        if (MiniPlayerController.transitioningToFullscreen) {
            // Skip ALL ExoPlayer/PlayerView ops — stopAsync handles deferred release
            Log.d("HomeTv", "onPause: Glide EN PAUSE + skipping cleanup (transitioning to fullscreen)")
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
        // 2026-07-04 : reprise Glide (pause fragment historique).
        try { com.bumptech.glide.Glide.with(this).resumeRequestsRecursive() } catch (_: Throwable) {}
        if (_binding == null) return

        // 2026-06-29 RESTAURÉ depuis APK v1.7.226 : luminosité carrousel/fond TV.
        try { applyCarouselDim() } catch (_: Throwable) {}

        // 2026-06-09 : applique le fond d'écran personnalisé (s'il existe) à
        //   chaque retour sur l'écran, au cas où il a été changé.
        com.streamflixreborn.streamflix.utils.AppearanceManager.applyTo(binding.root)

        // 2026-06-09 v3 : si toggle OFF, clear iv_home_background pour révéler
        //   le wallpaper.
        if (!com.streamflixreborn.streamflix.utils.UserPreferences.carouselAsBackground) {
            try {
                com.bumptech.glide.Glide.with(requireContext()).clear(binding.ivHomeBackground)
                binding.ivHomeBackground.setImageDrawable(null)
                binding.ivHomeBackground.background = null
                isBackgroundPinned = false
                swiperHasLastFocus = false
            } catch (_: Throwable) {}
        }
        // 2026-06-09 (user "ça ne désactive pas") : force le refresh de
        //   l'adapter pour que displayTvSwiper soit re-appelé avec la nouvelle
        //   valeur de carouselAsBackground (sinon le swiper item garde son
        //   état précédent jusqu'à un scroll/refocus).
        try { binding.vgvHome.adapter?.notifyDataSetChanged() } catch (_: Throwable) {}

        // 2026-06-08 (user "des fois quand on lance une lecture et qu'on revient
        //   sur le home du TV Hub il n'y a plus de chaînes affichées et un long
        //   chargement, obligé de quitter le hub et revenir pour réparer") :
        //   si le state observé est resté coincé sur Loading (job précédent
        //   cancellé sans émission, ou ProviderChangeNotifier qui n'a pas re-tiré),
        //   on FORCE un nouveau getHome() au retour. Quitter le Hub déclenchait
        //   un providerChangeFlow → ce fix obtient le même effet sans navigation.
        try {
            if (lastObservedState is HomeViewModel.State.Loading) {
                Log.d("HomeTv", "onResume: state stuck Loading → retrigger getHome()")
                viewModel.getHome()
            }
        } catch (_: Throwable) {}

        // 2026-06-16 (FIX) : attachPlayerView APPELE INCONDITIONNELLEMENT a chaque
        //   onResume, AVANT le ?: return. Sans ca, au demarrage froid (=
        //   currentChannelId == null), on returnait avant et le freezeOverlay
        //   restait null → captureLastFrameToOverlay skipait silencieusement.
        try {
            val freezeOv = binding.root.findViewById<android.widget.ImageView>(
                com.streamflixreborn.streamflix.R.id.mini_swap_freeze_overlay
            )
            MiniPlayerController.attachPlayerView(binding.miniPlayerView, freezeOv)
        } catch (_: Throwable) {}

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

        com.streamflixreborn.streamflix.utils.MiniPlayerController.applyMiniPlayerVisibility(binding.miniPlayerContainer, View.VISIBLE)
        syncOverlayVisibility()
        // 2026-06-29 (user "la radio écrase toujours" sur TV) : ne PAS forcer
        //   l'écrasement en dur ; adjustMiniPlayerForRadio() cache le bloc vidéo
        //   et garde la grille pleine pour une radio, restaure le 16:9 pour une TV.
        adjustMiniPlayerForRadio()
        binding.miniPlayerChannelName.text = MiniPlayerController.currentChannelName ?: ""
        MiniPlayerController.currentChannelPoster?.let { poster ->
            Glide.with(this).load(poster).into(binding.miniPlayerChannelLogo)
        }
        updatePauseButton()

        // Re-set interceptor if cleared
        if (MiniPlayerController.onIptvChannelClick == null) {
            MiniPlayerController.onIptvChannelClick = { tvShow ->
                // 2026-06-04 (user "le mini Player est toujours activé quand
                //   on clique sur otf TV") : OTF bypass mini player → fullscreen
                //   direct = OtfPlayerTvActivity. (Rollback du test 2026-06-20.)
                if (tvShow.id.startsWith("livehub::otf::")) {
                    Log.d("HomeTv", "OTF: bypass mini player (onResume) → fullscreen direct (${tvShow.title})")
                    MiniPlayerController.stopAsync()
                    try { binding.miniPlayerContainer.visibility = View.GONE; syncOverlayVisibility() } catch (_: Exception) {}
                    false
                } else if (tvShow.id == MiniPlayerController.currentChannelId) {
                    // 2026-06-08 : radio re-cliquée = no-op (mini-bar reste,
                    //   pas de fullscreen — pas la peine pour le son seul).
                    if (MiniPlayerController.isRadioChannel(tvShow.id)) {
                        Log.d("HomeTv", "Radio same channel (onResume) — no-op: ${tvShow.title}")
                        @Suppress("LiftReturnOrAssignment")
                        true
                    } else {
                        Log.d("HomeTv", "Same channel clicked → TRANSFER mini → fullscreen (onResume): ${tvShow.title}")
                        MiniPlayerController.transitioningToFullscreen = true
                        if (_binding != null) { binding.miniPlayerView.player = null; binding.miniPlayerContainer.visibility = View.GONE; syncOverlayVisibility() }
                        false
                    }
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
            // 2026-06-29 (user "la radio écrase toujours" sur TV) : idem chemin attach.
            adjustMiniPlayerForRadio()
            binding.miniPlayerChannelName.text = MiniPlayerController.currentChannelName ?: ""
            MiniPlayerController.currentChannelPoster?.let { poster ->
                Glide.with(this).load(poster).into(binding.miniPlayerChannelLogo)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MiniPlayerController.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                // 2026-06-22 : animation fluide — la grille se comprime
                // quand le mini player apparaît (glissement smooth)
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
                        updateHomeGridForMiniPlayer(false)
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
                        adjustMiniPlayerForRadio()
                        binding.miniPlayerChannelName.text = state.channelName
                        binding.miniPlayerLoading.visibility = View.VISIBLE
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
                        adjustMiniPlayerForRadio()
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
            // 2026-06-04 : OTF bypass mini → fullscreen direct (OtfPlayerTvActivity).
            if (tvShow.id.startsWith("livehub::otf::")) {
                Log.d("HomeTv", "OTF: bypass mini player → fullscreen direct (${tvShow.title})")
                MiniPlayerController.stopAsync()
                try { binding.miniPlayerContainer.visibility = View.GONE; syncOverlayVisibility() } catch (_: Exception) {}
                false
            } else if (tvShow.id == MiniPlayerController.currentChannelId) {
                // 2026-06-08 : radio re-cliquée = no-op (pas de fullscreen).
                if (MiniPlayerController.isRadioChannel(tvShow.id)) {
                    Log.d("HomeTv", "Radio same channel — no-op: ${tvShow.title}")
                    true
                } else {
                    Log.d("HomeTv", "Same channel clicked → TRANSFER mini → fullscreen: ${tvShow.title}")
                    MiniPlayerController.transitioningToFullscreen = true
                    if (_binding != null) { binding.miniPlayerView.player = null; binding.miniPlayerContainer.visibility = View.GONE; syncOverlayVisibility() }
                    false
                }
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

    /** Synchro visibilité overlay (barre boutons) avec le container vidéo. */
    private fun syncOverlayVisibility() {
        if (_binding == null) return
        binding.miniPlayerOverlay.visibility = binding.miniPlayerContainer.visibility
    }

    /**
     * 2026-06-28 : pour les radios, la surface vidéo (PlayerView) montre un gros
     * rectangle noir 16:9 inutile. On cache le container vidéo et on ne garde que
     * la barre de boutons overlay. Pour une chaîne TV normale, on restaure le
     * container 16:9 classique.
     */
    private fun adjustMiniPlayerForRadio() {
        if (_binding == null) return
        val isRadio = MiniPlayerController.isRadioChannel(MiniPlayerController.currentChannelId)
        if (isRadio) {
            // Radio : cacher le bloc vidéo, pas besoin de la surface noire
            binding.miniPlayerContainer.visibility = View.GONE
            // Pas de fullscreen pour les radios (audio-only)
            binding.miniPlayerFullscreen.visibility = View.GONE
            // La grille n'a pas besoin de laisser de la place pour le container
            updateHomeGridForMiniPlayer(false)
        } else {
            // Chaîne TV : restaurer le container vidéo 16:9
            binding.miniPlayerContainer.visibility = View.VISIBLE
            binding.miniPlayerFullscreen.visibility = View.VISIBLE
            updateHomeGridForMiniPlayer(true)
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
            Log.e("HomeTv", "navigateToFullPlayer failed: ${e.message}", e)
        }
    }

    private var swiperHasLastFocus: Boolean = false
    fun updateBackground(uri: String?, swiperHasFocus: Boolean? = false) {
        // 2026-06-21 (user "mode black à un bouton pour mettre tous les fonds
        //   en noir") : si blackBackground ON, override tout = fond noir uni.
        if (UserPreferences.blackBackground) {
            try {
                Glide.with(requireContext()).clear(binding.ivHomeBackground)
                binding.ivHomeBackground.setImageDrawable(null)
                binding.ivHomeBackground.setBackgroundColor(android.graphics.Color.BLACK)
            } catch (_: Throwable) {}
            return
        }
        // 2026-06-09 v3 : toggle OFF = l'image est gérée par
        //   CategoryViewHolder.displayTvSwiper qui la met DANS le swiper item
        //   (ivSwiperBannerInline). Ici on clear iv_home_background fullscreen.
        if (!UserPreferences.carouselAsBackground) {
            try {
                Glide.with(requireContext()).clear(binding.ivHomeBackground)
                binding.ivHomeBackground.setImageDrawable(null)
                binding.ivHomeBackground.background = null
            } catch (_: Throwable) {}
            return
        }
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
        // 2026-06-21 : mode noir override tout.
        if (UserPreferences.blackBackground) {
            try {
                Glide.with(requireContext()).clear(binding.ivHomeBackground)
                binding.ivHomeBackground.setImageDrawable(null)
                binding.ivHomeBackground.setBackgroundColor(android.graphics.Color.BLACK)
            } catch (_: Throwable) {}
            return
        }
        // 2026-06-09 : même garde que updateBackground.
        if (!UserPreferences.carouselAsBackground) {
            try {
                Glide.with(requireContext()).clear(binding.ivHomeBackground)
                binding.ivHomeBackground.setImageDrawable(null)
                binding.ivHomeBackground.background = null
            } catch (_: Throwable) {}
            return
        }
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

    /** 2026-06-23 v2 (user "sur la télé quand on affiche le mini lecteur il
     *  faut que tu descendes le panel — regarde sur la chromecast") :
     *  Pousse le grid VERS LE BAS quand le mini-player est visible pour que
     *  la 1re row arrive SOUS le mini-player. La v1 attendait `.post{}` pour
     *  prendre la hauteur réelle, ce qui laissait le grid à l'ancienne
     *  position pendant ~1 frame (visible à l'œil). Maintenant on apply le
     *  fallback IMMÉDIATEMENT (calculé sur widthPixels × 0.5 × 9/16, = le
     *  ratio exact du mini_player_container) puis on raffine via .post si la
     *  hauteur réelle diffère. Plus aucun "flash" du grid à l'ancienne
     *  position au moment où le mini-player apparaît. */
    private fun updateHomeGridForMiniPlayer(miniPlayerVisible: Boolean) {
        if (_binding == null) return
        val grid = binding.vgvHome
        val density = resources.displayMetrics.density
        val basePaddingTop = (16 * density).toInt()
        if (!miniPlayerVisible) {
            grid.setPadding(grid.paddingLeft, basePaddingTop, grid.paddingRight, grid.paddingBottom)
            // Reset topMargin à 0 quand le mini est caché.
            try {
                val lp = grid.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                if (lp.topMargin != 0) {
                    lp.topMargin = 0
                    grid.layoutParams = lp
                }
            } catch (_: Throwable) {}
            grid.requestLayout()
            Log.d("HomeTv", "updateHomeGridForMiniPlayer(false): RESET topMargin=0 paddingTop=$basePaddingTop")
            return
        }
        // 2026-06-23 v3 (user "C'est toujours pas bon sur TV — quand le mini
        //   s'affiche tout ce qui est derrière doit redescendre comme un
        //   glissement") : setPadding seul ne déplaçait pas visuellement le
        //   grid (le VerticalGridView Leanback semble ignorer le runtime
        //   paddingTop quand clipToPadding=false). On force AUSSI le
        //   topMargin de la layoutParams ConstraintLayout du grid + on appelle
        //   requestLayout() pour reflow immédiat.
        val fallbackH = (resources.displayMetrics.widthPixels * 0.50 * 9.0 / 16.0).toInt()
        val initialPadding = fallbackH + (8 * density).toInt()
        grid.setPadding(grid.paddingLeft, (16 * density).toInt(), grid.paddingRight, grid.paddingBottom)
        // Forcer le topMargin via LayoutParams (= déplace le grid sous le mini-player
        // sans toucher au paddingTop interne qui est utilisé pour les item-spacing).
        try {
            val lp = grid.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            lp.topMargin = initialPadding
            grid.layoutParams = lp
        } catch (_: Throwable) {}
        grid.requestLayout()
        Log.d("HomeTv", "updateHomeGridForMiniPlayer(true): topMargin=$initialPadding px (fallbackH=$fallbackH, density=$density, widthPx=${resources.displayMetrics.widthPixels}, after: paddingTop=${grid.paddingTop} topMargin=${(grid.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.topMargin})")
        // Raffinement via .post quand la vraie hauteur est mesurée.
        binding.miniPlayerContainer.post {
            if (_binding == null) return@post
            val miniH = binding.miniPlayerContainer.height
            if (miniH > 0 && miniH != fallbackH) {
                val refinedPadding = miniH + (8 * density).toInt()
                binding.vgvHome.setPadding(
                    binding.vgvHome.paddingLeft,
                    refinedPadding,
                    binding.vgvHome.paddingRight,
                    binding.vgvHome.paddingBottom,
                )
            }
        }
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
                // Clear le HomeCacheStore pour forcer un vrai refresh réseau
                // (sinon le cache 30 min IPTV sert les anciennes catégories FR).
                val provider = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
                if (provider != null) {
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(ctx, provider)
                }
                android.widget.Toast.makeText(
                    ctx,
                    "Vavoo : ${picked.flag} ${picked.label} — chargement…",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                // Force home reload pour récupérer le nouveau pays.
                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
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
        // 2026-06-09 (user "t'as abusé sur l'affichage et on voit même plus
        //   les favoris") : revert du mode grille pour World Live — ça
        //   cassait la section Favoris et l'affichage était trop gros.
        //   Comportement standard (row horizontale) pour tous les providers.
        try { binding.vgvHome.setNumColumns(1) } catch (_: Throwable) {}
        detachWorldLivePagingScrollListener()
        worldLivePagedItems = emptyList()
        worldLivePagedLoaded = 0
        // 2026-06-13 : revert du fallback carrousel (asm243) — régression
        //   DessinAnime. Retour au comportement d'origine v1.7.220.
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
                // 2026-06-27 (user "corbeille devant Continuer à regarder, focusable
                //   télécommande") : corbeille du header → vide le continue-watching.
                it.onClearSection = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val db = com.streamflixreborn.streamflix.database.AppDatabase.getInstance(requireContext())
                                db.movieDao().clearContinueWatching()
                                db.episodeDao().clearContinueWatching()
                                db.tvShowDao().clearWatching()
                                com.streamflixreborn.streamflix.utils.GlobalFavorites.dismissAllContinueWatching()
                            }
                            com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider?.let { p ->
                                com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(requireContext(), p)
                            }
                        } catch (_: Throwable) {}
                        viewModel.getHome()
                    }
                }
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

    // 2026-06-29 RESTAURÉ depuis APK v1.7.226 — système de luminosité du carrousel/fond TV.
    //   view_carousel_dim = un View noir à alpha variable au-dessus du swiper/carousel
    //   (ou du iv_home_background si en mode fond d'écran).
    //   alpha=0 → image normale. alpha=1.0 → écran noir total.
    //   Contrôlé par UserPreferences.carouselDim (SeekBar CAROUSEL_DIM dans Paramètres TV).
    private fun applyCarouselDim() {
        if (_binding == null) return
        try {
            val dim = com.streamflixreborn.streamflix.utils.UserPreferences.carouselDim
            // 2026-06-29 (REPAIR — user "assombrir QUE le carrousel, pas le fond
            //   d'écran") : on assombrit le FOND (iv_home_background) UNIQUEMENT si
            //   le carrousel EST le fond (carouselAsBackground). Sinon iv_home_background
            //   = wallpaper user → on n'y touche PAS ; c'est la bannière inline du
            //   carrousel (ivSwiperBannerInline) qui est assombrie dans CategoryViewHolder.
            binding.viewCarouselDim.alpha =
                if (com.streamflixreborn.streamflix.utils.UserPreferences.carouselAsBackground) dim / 100f else 0f
        } catch (_: Throwable) {
            // viewCarouselDim peut ne pas être encore régénéré dans le binding (= XML pas re-buildé).
        }
    }
}
