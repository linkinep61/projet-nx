package com.streamflixreborn.streamflix.fragments.home

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentHomeMobileBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.MiniPlayerController
import com.streamflixreborn.streamflix.utils.UserPreferences
import androidx.transition.TransitionManager
import androidx.transition.AutoTransition
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.providers.IptvProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView

class HomeMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentHomeMobileBinding? = null
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
    private var shouldScrollToTop: Boolean = true
    private var homeLayoutState: android.os.Parcelable? = null  // v91 : position verticale du Home

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Garde : si aucun provider sélectionné (cold start, restauration fragment),
        // revenir au picker au lieu de crasher sur AppDatabase.getInstance().
        if (com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider == null) {
            Log.w("HomeMobileFragment", "currentProvider is null — navigating back to providers")
            try {
                androidx.navigation.fragment.NavHostFragment.findNavController(this)
                    .navigate(R.id.providers)
            } catch (_: Exception) {}
            return
        }

        // v91 : ne PAS forcer le scroll en haut ici (sinon retour depuis un detail
        //   remonte tout en haut). shouldScrollToTop reste a sa valeur (true au 1er
        //   load via le defaut du champ ; remis a true uniquement sur provider/filtre).
        initializeHome()
        initializeMiniPlayer()

        // Lightweight refresh when provider changes
        viewLifecycleOwner.lifecycleScope.launch {
            com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.providerChangeFlow.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { shouldScrollToTop = true; viewModel.getHome() }
        }

        // Initial load
        viewModel.getHome()

        // 2026-05-13 (user "fais quelque chose de visuel pour faire voir qu'il y a
        // bien un chargement et un refresh") : pour Mon IPTV, on affiche un toast
        // au début et à la fin du load pour rassurer l'utilisateur.
        val isIptvProvider = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider is
            com.streamflixreborn.streamflix.providers.MyIptvProvider
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    HomeViewModel.State.Loading -> {
                        binding.isLoading.apply {
                            root.visibility = View.VISIBLE
                            pbIsLoading.visibility = View.VISIBLE
                            gIsLoadingRetry.visibility = View.GONE
                        }
                        if (isIptvProvider) {
                            // Toast appliCtx + LONG : survit aux transitions fragment
                            Toast.makeText(
                                requireContext().applicationContext,
                                "🔄 Chargement de ta playlist IPTV…",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        displayHome(state.categories)
                        binding.isLoading.root.visibility = View.GONE
                        if (isIptvProvider) {
                            val total = state.categories.sumOf { it.list.size }
                            // Délai 800ms pour laisser le toast Loading s'afficher d'abord
                            binding.root.postDelayed({
                                if (isAdded) Toast.makeText(
                                    requireContext().applicationContext,
                                    "✓ $total chaînes IPTV chargées",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }, 800L)
                        }
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
                            val doRetry = { viewModel.getHome() }
                            btnIsLoadingRetry.setOnClickListener { doRetry() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                doRetry()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.rvHome)
        // v91 : sauve la position verticale du Home pour la restaurer au retour.
        homeLayoutState = binding.rvHome.layoutManager?.onSaveInstanceState()
        // Don't clear onIptvChannelClick — the new fragment's onViewCreated sets it,
        // but this onDestroyView can fire AFTER, causing a race condition.
        // Don't release the player — it survives view recreation (e.g. rotation)
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        // Keep the mini player running in background — don't pause.
        // Only detach the PlayerView so the surface is freed.
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
        // 2026-06-09 (user "bouton carrousel mobile désactive pas") : refresh
        //   adapter pour que displayMobileSwiper soit re-bind avec nouvelle
        //   valeur de carouselAsBackground.
        try { binding.rvHome.adapter?.notifyDataSetChanged() } catch (_: Throwable) {}
        // 2026-06-21 (user "3 modes background") : refresh fond au retour
        //   sur le home après changement de pref dans Settings.
        try { updateMobileBackground(null) } catch (_: Throwable) {}
        // 2026-06-15 (user "quand tu mets en plein écran et que tu reviens en
        //   arrière ça remet le truc super large") : configChanges="orientation"
        //   est actif → quand on revient du fullscreen player, l'activity n'a
        //   pas été recréée et le layout mini player n'est pas réappliqué.
        //   Re-appliquer manuellement la bonne géométrie selon l'orientation.
        if (binding.miniPlayerContainer.visibility == View.VISIBLE) {
            try { updateMiniPlayerLayout(resources.configuration.orientation) } catch (_: Throwable) {}
        }
        val channelId = MiniPlayerController.currentChannelId ?: return

        // If the player was released (e.g. went to fullscreen), re-init and replay
        if (MiniPlayerController.getPlayer() == null) {
            Log.d("HomeMobile", "onResume: player was released, re-initializing for $channelId")
            MiniPlayerController.initPlayer(requireContext())
            binding.miniPlayerView.player = MiniPlayerController.getPlayer()
            MiniPlayerController.playChannel(
                channelId,
                MiniPlayerController.currentChannelName ?: channelId,
                MiniPlayerController.currentChannelPoster
            )
        } else {
            // Just re-attach the surface
            binding.miniPlayerView.player = MiniPlayerController.getPlayer()
        }

        com.streamflixreborn.streamflix.utils.MiniPlayerController.applyMiniPlayerVisibility(binding.miniPlayerContainer, View.VISIBLE)
        binding.miniPlayerChannelName.text = MiniPlayerController.currentChannelName ?: ""
        MiniPlayerController.currentChannelPoster?.let { poster ->
            Glide.with(this).load(poster).into(binding.miniPlayerChannelLogo)
        }
        updatePauseButton()

        // Re-set the interceptor (it may have been cleared in onDestroyView)
        if (MiniPlayerController.onIptvChannelClick == null) {
            MiniPlayerController.onIptvChannelClick = { tvShow ->
                if (tvShow.id == MiniPlayerController.currentChannelId) {
                    // 2026-07-05 : garder le player vivant pour le transfer seamless
                    Log.d("HomeMobile", "Same channel, transition to fullscreen (keep player alive, onResume): ${tvShow.title}")
                    MiniPlayerController.transitioningToFullscreen = true
                    if (_binding != null) { binding.miniPlayerView.player = null }
                    false
                } else {
                    Log.d("HomeMobile", "Mini player intercept (onResume): ${tvShow.title}")
                    MiniPlayerController.playChannel(tvShow.id, tvShow.title, tvShow.poster)
                    true
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null && binding.miniPlayerContainer.visibility == View.VISIBLE) {
            updateMiniPlayerLayout(newConfig.orientation)
        }
    }


    private fun initializeMiniPlayer() {
        val provider = UserPreferences.currentProvider
        val isIptv = provider is IptvProvider
        Log.d("HomeMobile", "initializeMiniPlayer: provider=${provider?.name} (${provider?.javaClass?.simpleName}), isIptv=$isIptv, miniPlayerEnabled=${UserPreferences.miniPlayerEnabled}")
        if (!isIptv || !UserPreferences.miniPlayerEnabled) {
            binding.miniPlayerContainer.visibility = View.GONE
            MiniPlayerController.onIptvChannelClick = null
            // 2026-05-31 : TOUJOURS observer le state du MiniPlayer, même quand le
            // provider courant n'est pas IPTV. Les chaînes OTF dans le TV Hub peuvent
            // lancer le MiniPlayer via handleDirectPlay → MiniPlayerController.playChannel
            // (qui résout le provider depuis l'ID). Sans cet observer, le container reste
            // GONE et la PlayerView n'est jamais attachée → écran noir.
            observeMiniPlayerState()
            return
        }

        // Initialize ExoPlayer for mini player
        MiniPlayerController.initPlayer(requireContext())
        binding.miniPlayerView.player = MiniPlayerController.getPlayer()
        // 2026-06-16 : attach playerView + freeze overlay au controller pour
        //   le swap_freeze_overlay (mirror grand PlayerTvFragment 478-527)
        try {
            val freezeOv = binding.root.findViewById<android.widget.ImageView>(
                com.streamflixreborn.streamflix.R.id.mini_swap_freeze_overlay
            )
            MiniPlayerController.attachPlayerView(binding.miniPlayerView, freezeOv)
        } catch (_: Throwable) {}

        // If a channel was already playing (e.g. after rotation), restore the UI
        if (MiniPlayerController.currentChannelId != null) {
            com.streamflixreborn.streamflix.utils.MiniPlayerController.applyMiniPlayerVisibility(binding.miniPlayerContainer, View.VISIBLE)
            binding.miniPlayerChannelName.text = MiniPlayerController.currentChannelName ?: ""
            MiniPlayerController.currentChannelPoster?.let { poster ->
                Glide.with(this)
                    .load(poster)
                    .into(binding.miniPlayerChannelLogo)
            }
        }

        // Observe mini player state
        observeMiniPlayerState()

        // Close button
        binding.miniPlayerClose.setOnClickListener {
            MiniPlayerController.stop()
        }

        // Pause/Play toggle button
        binding.miniPlayerPause.setOnClickListener {
            MiniPlayerController.togglePause()
            updatePauseButton()
        }

        // Fullscreen button — navigate to full player
        binding.miniPlayerFullscreen.setOnClickListener {
            navigateToFullPlayer()
        }

        // Tap on video area — also go fullscreen
        binding.miniPlayerView.setOnClickListener {
            navigateToFullPlayer()
        }

        // Set the IPTV click interceptor
        MiniPlayerController.onIptvChannelClick = { tvShow ->
            // 2026-06-04 (user "il y a bien un bug avec le mini Player et pour
            //   OTF faire un clic et ça ouvre directement le plein écran") :
            //   OTF n'utilise pas le mini player (= OtfPlayerActivity full screen
            //   ne peut pas cohabiter avec un mini player). Clic = direct fullscreen.
            if (tvShow.id.startsWith("livehub::otf::")) {
                Log.d("HomeMobile", "OTF: bypass mini player → fullscreen direct (${tvShow.title})")
                MiniPlayerController.stopAsync()
                try { binding.miniPlayerContainer.visibility = View.GONE } catch (_: Exception) {}
                false  // false = pas intercepté → flux nav classique → PlayerMobileFragment → OtfPlayerActivity
            } else if (tvShow.id == MiniPlayerController.currentChannelId) {
                // 2026-07-05 : NE PAS appeler stopAsync() ici — ça détruit le player
                //   avant que PlayerMobileFragment.transferPlayer() ne puisse le récupérer.
                //   Même pattern que navigateToFullPlayer() : on met le flag de transition
                //   et on détache la surface, le player reste VIVANT pour le transfer.
                Log.d("HomeMobile", "Same channel, transition to fullscreen (keep player alive): ${tvShow.title}")
                MiniPlayerController.transitioningToFullscreen = true
                if (_binding != null) { binding.miniPlayerView.player = null }
                false
            } else {
                Log.d("HomeMobile", "Mini player intercept: ${tvShow.title} (${tvShow.id})")
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
        val container = binding.miniPlayerContainer
        val recycler = binding.rvHome
        val root = binding.root as ConstraintLayout
        val cs = ConstraintSet()
        cs.clone(root)

        // 2026-06-29 (REPAIR — user "la radio ne doit PAS déclencher l'écrasement
        //   du mini-lecteur, c'est réservé au mini-lecteur VIDÉO ; ça se passe sur
        //   tous les providers") : pour une radio (audio seul, joué par
        //   RadioPlaybackService), on cache le conteneur vidéo → la grille reste
        //   PLEIN ÉCRAN, pas d'écrasement.
        if (com.streamflixreborn.streamflix.utils.MiniPlayerController.isRadioChannel(
                com.streamflixreborn.streamflix.utils.MiniPlayerController.currentChannelId)) {
            container.visibility = View.GONE
            cs.connect(recycler.id, ConstraintSet.TOP, R.id.barrier_home_top, ConstraintSet.BOTTOM)
            cs.connect(recycler.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(recycler.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.connect(recycler.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            cs.applyTo(root)
            return
        }

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape: mini player on the right 1/3
            cs.connect(container.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            cs.connect(container.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.connect(container.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            cs.clear(container.id, ConstraintSet.START)
            cs.constrainPercentWidth(container.id, 0.33f)
            cs.constrainHeight(container.id, ConstraintSet.MATCH_CONSTRAINT)

            // RecyclerView: fill left 2/3
            cs.connect(recycler.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            cs.connect(recycler.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(recycler.id, ConstraintSet.END, container.id, ConstraintSet.START)
            cs.connect(recycler.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)

            // Smaller player view height in landscape (fill the container)
            binding.miniPlayerView.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            // Portrait: container SOUS les icônes (forcé explicitement)
            cs.clear(container.id, ConstraintSet.TOP)
            cs.connect(container.id, ConstraintSet.TOP, R.id.iv_provider_logo, ConstraintSet.BOTTOM)
            cs.connect(container.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(container.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.clear(container.id, ConstraintSet.BOTTOM)
            cs.constrainPercentWidth(container.id, 1f)
            cs.constrainHeight(container.id, ConstraintSet.WRAP_CONTENT)

            // MUR : rv_home TOUJOURS sous la barrier — rien ne passe au-dessus
            cs.connect(recycler.id, ConstraintSet.TOP, R.id.barrier_home_top, ConstraintSet.BOTTOM)
            cs.connect(recycler.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(recycler.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.connect(recycler.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)

            // Reset player height to 200dp
            val dp = resources.displayMetrics.density
            binding.miniPlayerView.layoutParams.height = (200 * dp).toInt()
        }
        cs.applyTo(root)
    }

    /**
     * 2026-05-31 : Observer le state du MiniPlayer. Extrait de initializeMiniPlayer
     * pour pouvoir être appelé AUSSI quand le provider courant n'est pas IPTV
     * (ex: chaînes OTF dans le TV Hub cliquées depuis Cloudstream).
     * Quand le state passe à Loading/Playing, on attache dynamiquement la
     * PlayerView et les boutons si ce n'est pas déjà fait.
     */
    private var miniPlayerObserverJob: kotlinx.coroutines.Job? = null
    private fun observeMiniPlayerState() {
        if (miniPlayerObserverJob?.isActive == true) return
        miniPlayerObserverJob = viewLifecycleOwner.lifecycleScope.launch {
            MiniPlayerController.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                if (_binding == null) return@collect
                // 2026-06-22 : animation fluide — la liste se comprime
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
                        // rv_home stays below barrier_home_top — barrier auto-adjusts
                        // when container goes GONE (falls back to icons height)
                    }
                    is MiniPlayerController.State.Loading -> {
                        ensureMiniPlayerAttached()
                        if (!wasVisible) {
                            TransitionManager.beginDelayedTransition(
                                binding.root as ViewGroup,
                                AutoTransition().apply { duration = 300 }
                            )
                        }
                        com.streamflixreborn.streamflix.utils.MiniPlayerController.applyMiniPlayerVisibility(binding.miniPlayerContainer, View.VISIBLE)
                        updateMiniPlayerLayout(resources.configuration.orientation)
                        binding.miniPlayerChannelName.text = state.channelName
                        binding.miniPlayerLoading.visibility = View.VISIBLE
                    }
                    is MiniPlayerController.State.Playing -> {
                        ensureMiniPlayerAttached()
                        if (!wasVisible) {
                            TransitionManager.beginDelayedTransition(
                                binding.root as ViewGroup,
                                AutoTransition().apply { duration = 300 }
                            )
                        }
                        com.streamflixreborn.streamflix.utils.MiniPlayerController.applyMiniPlayerVisibility(binding.miniPlayerContainer, View.VISIBLE)
                        updateMiniPlayerLayout(resources.configuration.orientation)
                        binding.miniPlayerChannelName.text = state.channelName
                        binding.miniPlayerLoading.visibility = View.GONE
                        updatePauseButton()
                        state.channelPoster?.let { poster ->
                            Glide.with(this@HomeMobileFragment)
                                .load(poster)
                                .into(binding.miniPlayerChannelLogo)
                        }
                    }
                    is MiniPlayerController.State.Error -> {
                        binding.miniPlayerLoading.visibility = View.GONE
                        Log.e("HomeMobile", "Mini player error: ${state.message}")
                        Toast.makeText(requireContext(),
                            "Stream indisponible — essaie une autre chaîne",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * 2026-05-31 : Attache la PlayerView au MiniPlayerController si pas encore fait.
     * Appelé dynamiquement quand une chaîne IPTV lance le MiniPlayer depuis un
     * provider non-IPTV (TV Hub OTF depuis Cloudstream). Setup aussi les boutons.
     */
    private var miniPlayerWired = false
    private fun ensureMiniPlayerAttached() {
        if (_binding == null) return
        val player = MiniPlayerController.getPlayer() ?: return
        if (binding.miniPlayerView.player !== player) {
            Log.d("HomeMobile", "ensureMiniPlayerAttached: attaching player to view")
            binding.miniPlayerView.player = player
        }
        if (miniPlayerWired) return
        miniPlayerWired = true
        // Wire buttons une seule fois
        binding.miniPlayerClose.setOnClickListener { MiniPlayerController.stop() }
        binding.miniPlayerPause.setOnClickListener {
            MiniPlayerController.togglePause()
            updatePauseButton()
        }
        binding.miniPlayerFullscreen.setOnClickListener { navigateToFullPlayer() }
        binding.miniPlayerView.setOnClickListener { navigateToFullPlayer() }
        // Set interceptor if not set
        if (MiniPlayerController.onIptvChannelClick == null) {
            MiniPlayerController.onIptvChannelClick = { tvShow ->
                if (tvShow.id == MiniPlayerController.currentChannelId) {
                    // 2026-07-05 : même fix que ci-dessus — garder le player vivant
                    MiniPlayerController.transitioningToFullscreen = true
                    if (_binding != null) { binding.miniPlayerView.player = null }
                    false
                } else {
                    MiniPlayerController.playChannel(tvShow.id, tvShow.title, tvShow.poster)
                    true
                }
            }
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

    /**
     * 2026-06-21 (user "3 modes : original, carrousel comme fond, mode noir,
     *   et en plus on peut mettre un fond d'écran à part comme d'habitude") :
     * Met à jour iv_home_background selon les prefs :
     *  - blackBackground ON → fond noir uni (override tout, y compris custom)
     *  - sinon carouselAsBackground ON → bannière courante du swiper
     *  - sinon → ne touche à RIEN (= AppearanceManager.applyTo a déjà mis le
     *    custom wallpaper user, ou le default bg_wallpaper_mobile).
     */
    private fun updateMobileBackground(bannerUrl: String?) {
        if (_binding == null) return
        val ctx = context ?: return
        try {
            val prefs = com.streamflixreborn.streamflix.utils.UserPreferences
            // Mode noir → override tout (custom wallpaper + carrousel + default)
            if (prefs.blackBackground) {
                com.bumptech.glide.Glide.with(ctx).clear(binding.ivHomeBackground)
                binding.ivHomeBackground.setImageDrawable(null)
                binding.ivHomeBackground.setBackgroundColor(android.graphics.Color.BLACK)
                return
            }
            // Reset bg color (= au cas où on revient de mode noir)
            binding.ivHomeBackground.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // Mode carrousel → bannière courante
            if (prefs.carouselAsBackground && !bannerUrl.isNullOrBlank()
                && prefs.currentProvider !is com.streamflixreborn.streamflix.providers.IptvProvider
            ) {
                com.bumptech.glide.Glide.with(ctx)
                    .load(com.streamflixreborn.streamflix.utils.optimizeArtworkUrl(bannerUrl, 1280))
                    .centerCrop()
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(400))
                    .into(binding.ivHomeBackground)
                return
            }
            // Mode original : NE PAS TOUCHER au fond. AppearanceManager.applyTo
            //   a déjà appliqué le custom wallpaper user (ou default si rien).
            //   Si on revient de carrousel/noir → on RE-applique pour restaurer.
            com.streamflixreborn.streamflix.utils.AppearanceManager.applyTo(binding.root)
        } catch (_: Throwable) {}
    }

    private fun navigateToFullPlayer() {
        val channelId = MiniPlayerController.currentChannelId ?: return
        val channelName = MiniPlayerController.currentChannelName ?: channelId
        val channelPoster = MiniPlayerController.currentChannelPoster

        // Flag for transfer — PlayerMobileFragment.onViewCreated will steal the player
        // (même logique que HomeTvFragment : on NE release PAS le player ici,
        //  on le laisse vivant pour que le fullscreen le récupère sans coupure)
        MiniPlayerController.transitioningToFullscreen = true
        if (_binding != null) { binding.miniPlayerView.player = null }

        val videoType = Video.Type.Episode(
            id = channelId,
            number = 1,
            title = channelName,
            poster = channelPoster,
            overview = null,
            tvShow = Video.Type.Episode.TvShow(
                id = channelId,
                title = channelName,
                poster = channelPoster,
                banner = null,
                releaseDate = null,
                imdbId = null,
            ),
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
            android.util.Log.e("HomeMobile", "navigateToFullPlayer failed: ${e.message}", e)
        }
    }

    private fun initializeHome() {
        // 2026-06-21 (user "3 modes : original (défaut), carrousel comme
        //   fond d'écran (opt-in), mode noir (opt-in)") :
        //   - Si blackBackground=true → fond noir uni
        //   - Sinon si carouselAsBackground=true → bannière courante
        //   - Sinon → wallpaper statique (= mode original)
        appAdapter.onSwiperPageChanged = { bannerUrl ->
            updateMobileBackground(bannerUrl)
        }
        // Initial state au boot (= avant que le swiper émette) :
        updateMobileBackground(null)

        binding.rvHome.apply {
            setHasFixedSize(true)
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
        }

        binding.ivProviderLogo.apply {
            Glide.with(context)
                .load(UserPreferences.currentProvider?.logo?.takeIf { it.isNotEmpty() }
                    ?: R.drawable.ic_provider_default_logo)
                .error(R.drawable.ic_provider_default_logo)
                .fitCenter()
                .into(this)

            setOnClickListener {
                findNavController().navigate(R.id.providers)
            }
        }

        binding.ivDownloads.setOnClickListener {
            findNavController().navigate(R.id.downloads)
        }

        // 2026-05-13 (user "le bouton en haut à gauche pour retourner vers les
        // providers actuellement il est bloqué pour aller sur les sources IPTV
        // au lieu d'aller sur le Home provider") : le clic sur le logo provider
        // RAMÈNE au picker des providers (R.id.providers). La gestion des
        // sources reste accessible via Paramètres → Mon IPTV → Mes sources.
        val currentProv = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
        val isMyIptv = currentProv is com.streamflixreborn.streamflix.providers.MyIptvProvider
        val isVavoo = currentProv is com.streamflixreborn.streamflix.providers.VavooProvider
        // 2026-06-09 (user "y a pas la planète sur mobile pour changer de
        //   catégorie World Live") : ajout du picker catégorie World Live sur
        //   le bouton globe mobile (équivalent du sidebar TV).
        val isWorldLive = currentProv is com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider
        // 2026-06-10 (user "il faut le rajouter sur la version mobile aussi") :
        //   bouton catégorie aussi sur TV Hub principal mobile.
        val isTvHub = currentProv is com.streamflixreborn.streamflix.providers.LiveTvHubProvider

        if (isMyIptv) {
            binding.ivProviderLogo.setOnClickListener {
                try {
                    findNavController().navigate(R.id.providers)
                } catch (_: Throwable) {}
            }
            binding.ivProviderLogo.isClickable = true
            binding.ivProviderLogo.isFocusable = true

            // Bouton picker de catégorie visible uniquement sur Mon IPTV.
            binding.ivIptvCategories.visibility = View.VISIBLE
            binding.ivIptvCategories.setOnClickListener {
                showIptvCategoryPicker()
            }
        } else {
            binding.ivIptvCategories.visibility = View.GONE
        }

        // 2026-05-23 : bouton globe langue visible sur MyIptv et Vavoo
        // (aligné avec le comportement TV — sidebar iptv_language_menu).
        // 2026-06-10 (user "bouton apparent dans la barre") : bouton sources
        //   visible UNIQUEMENT sur World TV (LiveTvHubPlus).
        if (isWorldLive) {
            binding.ivWorldLiveSources.visibility = View.VISIBLE
            binding.ivWorldLiveSources.setOnClickListener {
                com.streamflixreborn.streamflix.providers.WorldLiveSourcesDialog
                    .showManager(requireContext())
            }
        } else {
            binding.ivWorldLiveSources.visibility = View.GONE
        }

        if (isMyIptv || isVavoo || isWorldLive || isTvHub) {
            binding.ivIptvLanguage.visibility = View.VISIBLE
            binding.ivIptvLanguage.setOnClickListener {
                showIptvLanguageFilterPicker()
            }
        } else {
            binding.ivIptvLanguage.visibility = View.GONE
        }

        // 2026-06-18 → 2026-06-19 (user "ajoute le bouton refresh aussi sur
        //   tous les home VOD, ça permettrait de rafraîchir le contenu de
        //   temps en temps") : icône Refresh visible pour TOUS les providers.
        //   Clic = clear cache home + notify reload. TV Hub : refresh aussi le
        //   M3U replay (= comportement étendu seulement pour TV Hub).
        binding.ivTvhubRefresh.visibility = View.VISIBLE
        binding.ivTvhubRefresh.setOnClickListener {
            val ctxRef = requireContext()
            android.widget.Toast.makeText(ctxRef,
                "Actualisation du home…", android.widget.Toast.LENGTH_SHORT).show()
            // 2026-07-04 : TOUT en Dispatchers.IO. Avant = Dispatchers.Main (lifecycleScope
            //   par défaut) → forceRefreshReplay + onChangeUrl faisaient du réseau sur le
            //   main thread → ANR → crash (user "le bouton refresh gèle puis crashe").
            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (isTvHub) {
                    val n = try {
                        com.streamflixreborn.streamflix.providers.LiveTvHubProvider
                            .forceRefreshReplay()
                    } catch (_: Throwable) { 0 }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(ctxRef,
                            "TV Hub : $n catégories chargées",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider?.let { p ->
                    try { com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(ctxRef, p) }
                    catch (_: Throwable) {}
                    // 2026-06-19 (user "le bouton refresh ça efface les données
                    //   du home, c'est pour ce genre de cas Wiflix") : clear
                    //   aussi le cache PROVIDER_URL pour les providers à
                    //   domaine dynamique (Wiflix, FrenchStream, Movix, etc.).
                    //   Au prochain initializeService(), onChangeUrl() va
                    //   re-fetcher le portail et trouver le bon domaine actif.
                    if (p is com.streamflixreborn.streamflix.providers.ProviderConfigUrl) {
                        try {
                            com.streamflixreborn.streamflix.utils.UserPreferences
                                .setProviderCache(p, com.streamflixreborn.streamflix.utils.UserPreferences.PROVIDER_URL, "")
                            (p as? com.streamflixreborn.streamflix.providers.ProviderConfigUrl)?.onChangeUrl(forceRefresh = true)
                        } catch (_: Throwable) {}
                    }
                }
                try { com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged() }
                catch (_: Throwable) {}
            }
        }

        // 2026-06-17 : bouton tunnel Vavoo visible uniquement sur Vavoo TV.
        if (isVavoo) {
            binding.ivVavooTunnel.visibility = View.VISIBLE
            binding.ivVavooTunnel.setOnClickListener { showVavooTunnelPicker() }
        } else {
            binding.ivVavooTunnel.visibility = View.GONE
        }

        // 2026-05-20 : bouton filtre catalogue (langue/contenu) — uniquement sur
        //   les providers TMDB compatibles (Cloudstream). Permet de choisir
        //   "Productions françaises / Populaire international / Anime / US / …".
        if (com.streamflixreborn.streamflix.utils.CatalogFilter.isSupported(UserPreferences.currentProvider?.name)) {
            binding.ivCatalogFilter.visibility = View.VISIBLE
            binding.ivCatalogFilter.setOnClickListener { showCatalogFilterPicker() }
        } else {
            binding.ivCatalogFilter.visibility = View.GONE
        }

        // Fond wallpaper fixe (bg_wallpaper_mobile via XML)
    }

    /** 2026-05-13 : ouvre un AlertDialog avec la liste des catégories LIVE
     *  disponibles (avec count) + option "Toutes". Stocke le choix dans
     *  MyIptvProvider.selectedCategoryLive, INVALIDE le HomeCacheStore
     *  (sinon TTL 5 min skip le refresh), puis notifie le viewModel pour
     *  recharger getHome() avec le filtre appliqué.
     *  2026-05-13 (user "traduit l'anglais des catégories") : noms affichés
     *  via prettyCategoryName() — mapping anglais → français. Le nom RAW
     *  reste utilisé en interne pour le filtre. */
    private fun showIptvCategoryPicker() {
        val provider = com.streamflixreborn.streamflix.providers.MyIptvProvider
        val type = com.streamflixreborn.streamflix.utils.IptvClassifier.ContentType.LIVE
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
                provider.selectedCategoryLive = rawNames[idx]
                com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                    requireContext().applicationContext,
                    provider,
                )
                // 2026-05-14 (user "tu cliques une fois il se passe rien") :
                // appel direct + notif. Le direct trigger getHome immédiatement
                // sans attendre que le flow ProviderChangeNotifier propage.
                // Sans ça, latence 1-3s avant que le viewModel ne réagisse.
                viewModel.getHome()
                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                android.widget.Toast.makeText(
                    requireContext(),
                    "Chargement…",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-06-17 (user "il faut faire un bouton de bascule") : picker mode Vavoo
     *  Direct ↔ Tunnel VYPN (= Shadowsocks gratuit via serveurs DE/CZ/etc.).
     *  2026-06-18 (user "On peut essayer voir si ça fonctionne" + "Tu l'appelles
     *    VPN 2" + "faudra faire attention que les 2 boutons puissent pas se
     *    connecter en même temps") : 3 modes mutuellement exclusifs.
     *    - "Direct" = pas de tunnel (= comportement standard, geoblock FR)
     *    - "Tunnel VPN" = notre tunnel VYPN intégré (= Shadowsocks DE)
     *    - "VPN 2" = SOCKS5 vers app PlanetVPN externe (= Frankfurt 162.19.234.202)
     *  Le mutex est garanti par construction (single-choice + pref enum). */
    private fun showVavooTunnelPicker() {
        val ctx = requireContext()
        val prefs = com.streamflixreborn.streamflix.utils.UserPreferences
        val current = try { prefs.vavooTunnelMode } catch (_: Throwable) { prefs.TUNNEL_MODE_OFF }
        val items = arrayOf(
            "Direct (= comportement standard)",
            "Tunnel VPN",
            "VPN 2 (serveur alternatif)"
        )
        val checked = when (current) {
            prefs.TUNNEL_MODE_VYPN -> 1
            prefs.TUNNEL_MODE_PLANETVPN -> 2
            else -> 0
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Tunnel VPN")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                val newMode = when (which) {
                    1 -> prefs.TUNNEL_MODE_VYPN
                    2 -> prefs.TUNNEL_MODE_PLANETVPN
                    else -> prefs.TUNNEL_MODE_OFF
                }
                if (newMode == current) { dialog.dismiss(); return@setSingleChoiceItems }
                dialog.dismiss()
                viewLifecycleOwner.lifecycleScope.launch {
                    switchVavooTunnelMode(ctx, prevMode = current, newMode = newMode)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Bascule de mode tunnel Vavoo. Stoppe l'ancien tunnel, démarre/check le
     *  nouveau, persiste la pref, invalide les caches client + home. Mutex
     *  garanti : un seul mode actif à la fois. */
    private suspend fun switchVavooTunnelMode(
        ctx: android.content.Context,
        prevMode: String,
        newMode: String
    ) {
        val prefs = com.streamflixreborn.streamflix.utils.UserPreferences
        try {
            // 1. Stoppe l'ancien tunnel si l'un des 2 modes-tunnel était actif
            if (prevMode == prefs.TUNNEL_MODE_VYPN || prevMode == prefs.TUNNEL_MODE_PLANETVPN) {
                try { com.streamflixreborn.streamflix.utils.VavooTunnel.stop() } catch (_: Throwable) {}
            }
            // 2. Met à jour la pref AVANT de démarrer le nouveau (pour que
            //    invalidateClientCache puis getClient() relisent le bon mode)
            prefs.vavooTunnelMode = newMode

            // 3. Démarre le nouveau si nécessaire
            //   Les 2 modes "Tunnel VPN" ET "VPN 2" utilisent notre tunnel
            //   intégré VavooTunnel (= proxy SOCKS5 in-process).
            //   - "Tunnel VPN" : sélection AUTO du meilleur serveur ami
            //   - "VPN 2"     : 2e dialog → user choisit MANUELLEMENT son serveur
            val displayMsg: String = when (newMode) {
                prefs.TUNNEL_MODE_VYPN -> {
                    val ok = try {
                        com.streamflixreborn.streamflix.utils.VavooTunnel.start(skipBestN = 0)
                    } catch (_: Throwable) { false }
                    if (!ok) {
                        prefs.vavooTunnelMode = prefs.TUNNEL_MODE_OFF
                        "Tunnel VPN : démarrage échoué"
                    } else "Tunnel VPN actif"
                }
                prefs.TUNNEL_MODE_PLANETVPN -> {
                    // Cas spécial : on ouvre le picker MANUEL au lieu de start auto
                    showVpn2ServerPicker(ctx)
                    "Sélectionne ton serveur dans la liste"
                }
                else -> "Tunnel VPN désactivé"
            }

            // 4. Invalide les caches OkHttp Vavoo + home (pour rafraîchir avec
            //    le nouveau routing)
            com.streamflixreborn.streamflix.providers.VavooProvider.invalidateClientCache()
            prefs.currentProvider?.let { p ->
                try { com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(ctx, p) } catch (_: Throwable) {}
            }
            try { com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged() } catch (_: Throwable) {}

            android.widget.Toast.makeText(ctx, displayMsg, android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            android.widget.Toast.makeText(
                ctx,
                "Bascule échouée : ${e.javaClass.simpleName} ${e.message?.take(80)}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 2026-06-18 (user "les gens ils choisissent leur serveur") : sur clic
     *  VPN 2, affiche un 2e dialog listant tous les serveurs free du pool
     *  VYPN. L'user pick son serveur, ONYX démarre le tunnel précisément
     *  sur cette IP. */
    private fun showVpn2ServerPicker(ctx: android.content.Context) {
        val prefs = com.streamflixreborn.streamflix.utils.UserPreferences
        viewLifecycleOwner.lifecycleScope.launch {
            val loading = android.app.AlertDialog.Builder(ctx)
                .setTitle("VPN 2")
                .setMessage("Chargement de la liste des serveurs…")
                .setCancelable(false)
                .show()
            val pool = try {
                com.streamflixreborn.streamflix.utils.VavooTunnel.listFreeServers()
            } catch (_: Throwable) { emptyList() }
            loading.dismiss()
            if (pool.isEmpty()) {
                android.widget.Toast.makeText(ctx,
                    "VPN 2 : pas de serveur disponible (= VYPN ping a échoué)",
                    android.widget.Toast.LENGTH_LONG).show()
                prefs.vavooTunnelMode = prefs.TUNNEL_MODE_OFF
                return@launch
            }
            val labels = pool.map { it.displayLabel() }.toTypedArray()
            android.app.AlertDialog.Builder(ctx)
                .setTitle("VPN 2 — Choisis ton serveur")
                .setItems(labels) { dlg, idx ->
                    dlg.dismiss()
                    val chosen = pool[idx]
                    viewLifecycleOwner.lifecycleScope.launch {
                        val ok = try {
                            com.streamflixreborn.streamflix.utils.VavooTunnel.startWithServer(chosen)
                        } catch (_: Throwable) { false }
                        if (ok) {
                            com.streamflixreborn.streamflix.providers.VavooProvider.invalidateClientCache()
                            prefs.currentProvider?.let { p ->
                                try { com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(ctx, p) } catch (_: Throwable) {}
                            }
                            try { com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged() } catch (_: Throwable) {}
                            android.widget.Toast.makeText(ctx,
                                "VPN 2 actif : ${chosen.country} / ${chosen.city}",
                                android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            prefs.vavooTunnelMode = prefs.TUNNEL_MODE_OFF
                            android.widget.Toast.makeText(ctx,
                                "VPN 2 : échec démarrage sur ${chosen.country}/${chosen.city}",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Annuler") { _, _ -> prefs.vavooTunnelMode = prefs.TUNNEL_MODE_OFF }
                .show()
        }
    }

    /** 2026-05-13 : picker rapide du filtre langue.
     *  2026-05-23 : route vers le bon picker selon le provider actif
     *  (Vavoo = pays, WiTV v2 = groupe/langue, MyIptv = filtre auto/all/fr). */
    private fun showIptvLanguageFilterPicker() {
        val currentProv = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
        when {
            currentProv is com.streamflixreborn.streamflix.providers.VavooProvider -> showVavooCountryPicker()
            currentProv is com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider -> showWorldLiveCategoryPicker()
            currentProv is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> showTvHubCategoryPicker()
            else -> showMyIptvLanguageFilterPicker()
        }
    }

    /** 2026-06-10 — picker catégorie TV Hub principal (mobile). */
    private fun showTvHubCategoryPicker() {
        val ctx = requireContext()
        val currentCode = com.streamflixreborn.streamflix.providers
            .BoxXtemusCategorySettings.getCurrentCode(ctx)
        viewLifecycleOwner.lifecycleScope.launch {
            // 2026-06-19 v35 (user "ce ne sont pas les bonnes catégories,
            //   ça devrait être tout ce qu'il y a dans le TV Hub en catégorie") :
            //   le picker utilisait BoxXtemusProvider (= source brute) au lieu
            //   de LiveTvHubProvider (= ce qui s'affiche réellement à l'écran).
            //   Fix : prendre les sections VISIBLES dans le home TV Hub.
            val realSections = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.streamflixreborn.streamflix.providers.LiveTvHubProvider.getHome()
                }
            } catch (_: Throwable) { emptyList() }
            val codes = mutableListOf(
                com.streamflixreborn.streamflix.providers.BoxXtemusCategorySettings.ALL_CODE
            )
            codes.addAll(
                realSections.map { it.name }
                    .filter { it.isNotBlank() && !it.equals("Favoris", ignoreCase = true) }
                    .distinct()
            )
            val labels = codes.map { code ->
                val label = if (code == com.streamflixreborn.streamflix.providers
                        .BoxXtemusCategorySettings.ALL_CODE) {
                    com.streamflixreborn.streamflix.providers.BoxXtemusCategorySettings.ALL_LABEL
                } else code
                "${label}${if (code.equals(currentCode, ignoreCase = true)) "  ✓" else ""}"
            }.toTypedArray()
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Catégorie TV Hub")
                .setItems(labels) { _, idx ->
                    val pickedCode = codes[idx]
                    if (pickedCode.equals(currentCode, ignoreCase = true)) return@setItems
                    com.streamflixreborn.streamflix.providers.BoxXtemusCategorySettings.setCurrent(ctx, pickedCode)
                    kotlin.runCatching {
                        com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                            ctx,
                            com.streamflixreborn.streamflix.providers.LiveTvHubProvider,
                        )
                    }
                    kotlin.runCatching {
                        com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    /** 2026-06-09 — picker catégorie World Live (mobile, copie de la TV).
     *  Liste DYNAMIQUE = les vraies sections de la playlist. */
    private fun showWorldLiveCategoryPicker() {
        val ctx = requireContext()
        val currentCode = com.streamflixreborn.streamflix.providers.WorldLiveCategorySettings.getCurrentCode(ctx)
        // 2026-06-10 (user "le picker met longtemps à s'ouvrir") : tente
        //   d'abord le cache mémoire/disque (= instantané). Fallback getHome
        //   uniquement si rien n'est cached.
        val fastNames = com.streamflixreborn.streamflix.providers
            .WorldLiveTvProvider.getCategoryNamesFast()
        if (fastNames.isNotEmpty()) {
            openCategoryPickerDialog(ctx, currentCode, fastNames)
            return
        }
        val loadingToast = android.widget.Toast.makeText(ctx,
            "Chargement des catégories…", android.widget.Toast.LENGTH_SHORT)
        loadingToast.show()
        viewLifecycleOwner.lifecycleScope.launch {
            val realSections = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.streamflixreborn.streamflix.providers.WorldLiveTvProvider.getHome()
                }
            } catch (_: Throwable) { emptyList() }
            loadingToast.cancel()
            openCategoryPickerDialog(ctx, currentCode,
                realSections.map { it.name }.filter { it.isNotBlank() }.distinct())
        }
    }

    private fun openCategoryPickerDialog(
        ctx: android.content.Context,
        currentCode: String,
        sectionNames: List<String>,
    ) {
        run {
            val codes = mutableListOf(
                com.streamflixreborn.streamflix.providers.WorldLiveCategorySettings.ALL_CODE
            )
            codes.addAll(sectionNames)
            val labels = codes.map { code ->
                val label = if (code == com.streamflixreborn.streamflix.providers
                        .WorldLiveCategorySettings.ALL_CODE) {
                    com.streamflixreborn.streamflix.providers.WorldLiveCategorySettings.ALL_LABEL
                } else code
                "${label}${if (code.equals(currentCode, ignoreCase = true)) "  ✓" else ""}"
            }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Catégorie World Live")
                .setItems(labels) { _, idx ->
                    val pickedCode = codes[idx]
                    if (pickedCode.equals(currentCode, ignoreCase = true)) return@setItems
                    com.streamflixreborn.streamflix.providers.WorldLiveCategorySettings.setCurrent(ctx, pickedCode)
                    val displayLabel = if (pickedCode == com.streamflixreborn.streamflix.providers
                            .WorldLiveCategorySettings.ALL_CODE) {
                        com.streamflixreborn.streamflix.providers.WorldLiveCategorySettings.ALL_LABEL
                    } else pickedCode
                    kotlin.runCatching {
                        com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                            ctx.applicationContext,
                            com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider,
                        )
                    }
                    android.widget.Toast.makeText(ctx,
                        "World Live : $displayLabel — chargement…",
                        android.widget.Toast.LENGTH_SHORT).show()
                    kotlin.runCatching {
                        com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
                            .notifyProviderChanged(forceRelaunch = true)
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    private fun showMyIptvLanguageFilterPicker() {
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

    /** 2026-05-23 : picker pays Vavoo (mobile, même logique que MainTvActivity). */
    private fun showVavooCountryPicker() {
        val ctx = requireContext()
        val current = com.streamflixreborn.streamflix.providers.VavooCountrySettings.getCurrent(ctx)
        val list = com.streamflixreborn.streamflix.providers.VavooCountrySettings.list
        val items = list.map {
            "${it.flag} ${it.label}${if (it.code == current.code) "  ✓" else ""}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Pays Vavoo")
            .setItems(items) { _, idx ->
                val picked = list[idx]
                if (picked.code == current.code) return@setItems
                com.streamflixreborn.streamflix.providers.VavooCountrySettings.setCurrent(ctx, picked)
                com.streamflixreborn.streamflix.providers.VavooProvider.setCountryFilter(picked.filterValue)
                kotlin.runCatching {
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                        ctx.applicationContext,
                        com.streamflixreborn.streamflix.providers.VavooProvider,
                    )
                }
                android.widget.Toast.makeText(
                    ctx,
                    "Vavoo : ${picked.flag} ${picked.label} — chargement…",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                kotlin.runCatching {
                    com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                }
            }
            .setNeutralButton("Miroir") { _, _ -> showVavooMirrorPicker() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-05-28 : picker miroir Vavoo — choisir quel site utiliser en priorité. */
    private fun showVavooMirrorPicker() {
        val ctx = requireContext()
        val current = com.streamflixreborn.streamflix.providers.VavooMirrorSettings.getCurrent(ctx)
        val mirrors = com.streamflixreborn.streamflix.providers.VavooMirrorSettings.list
        val items = mirrors.map {
            "${it.label}${if (it.url == current.url) "  ✓" else ""}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Miroir Vavoo")
            .setItems(items) { _, idx ->
                val picked = mirrors[idx]
                if (picked.url == current.url) return@setItems
                com.streamflixreborn.streamflix.providers.VavooMirrorSettings.setCurrent(ctx, picked)
                // Vider le cache catalog pour forcer un rechargement depuis le nouveau miroir
                kotlin.runCatching {
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                        ctx.applicationContext,
                        com.streamflixreborn.streamflix.providers.VavooProvider,
                    )
                }
                android.widget.Toast.makeText(
                    ctx,
                    "Miroir Vavoo : ${picked.label} — chargement…",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                kotlin.runCatching {
                    com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-05-20 : picker du filtre catalogue (providers TMDB type Cloudstream).
     *  Sauve le mode PAR provider, invalide le HomeCacheStore (sinon le TTL 5 min
     *  re-sert l'ancien home) puis recharge getHome(). Même patron que le picker
     *  langue IPTV. */
    /**
     * 2026-06-24 : appelé par MainMobileActivity pendant le drag du bouton
     * au bout de la barre de navigation. Ajuste le paddingTop de rv_home
     * en temps réel (le contenu descend/monte).
     */
    private fun showCatalogFilterPicker() {
        val provider = UserPreferences.currentProvider ?: return
        val modes = com.streamflixreborn.streamflix.utils.CatalogFilter.Mode.entries
        val current = com.streamflixreborn.streamflix.utils.CatalogFilter.get(provider.name)
        val currentIdx = modes.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Filtrer le catalogue")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), currentIdx) { dlg, idx ->
                val newMode = modes[idx]
                if (newMode != current) {
                    com.streamflixreborn.streamflix.utils.CatalogFilter.set(provider.name, newMode)
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                        requireContext().applicationContext, provider,
                    )
                    viewModel.getHome()
                    com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                    android.widget.Toast.makeText(
                        requireContext().applicationContext,
                        "Catalogue : ${newMode.label}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                dlg.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun displayHome(categories: List<Category>) {
        // 2026-06-13 : revert du fallback carrousel (asm242) — l'user a
        //   signalé une régression DessinAnime. Retour au comportement
        //   d'origine v1.7.220.
        categories
            .find { it.name == Category.FEATURED }
            ?.also {
                it.list.forEach { show ->
                    when (show) {
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_SWIPER_MOBILE_ITEM
                        is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_SWIPER_MOBILE_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.CONTINUE_WATCHING }
            ?.also {
                it.name = getString(R.string.home_continue_watching)
                // 2026-06-27 (user "corbeille devant Continuer à regarder, focusable
                //   télécommande, tous providers") : réutilise la corbeille du header
                //   (setupClearButton) → vide tout le continue-watching.
                it.onClearSection = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                // Vide réellement le continue-watching en base (la rangée
                                //   home vient de la DB, pas du set "dismissed" du cœur).
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
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM
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
                    if (category.name != Category.FEATURED && category.name != getString(R.string.home_continue_watching)) {
                        category.list.onEach { show ->
                            when (show) {
                                is Episode -> show.itemType = AppAdapter.Type.EPISODE_MOBILE_ITEM
                                is Movie -> show.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                                is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                            }
                        }
                    }
                    category.itemSpacing = 10.dp(requireContext())
                    category.itemType = when (category.name) {
                        Category.FEATURED -> AppAdapter.Type.CATEGORY_MOBILE_SWIPER
                        else -> AppAdapter.Type.CATEGORY_MOBILE_ITEM
                    }
                }
        )

        if (shouldScrollToTop) {
            shouldScrollToTop = false
            homeLayoutState = null
            _binding?.rvHome?.let { rv -> rv.post { if (_binding != null) rv.scrollToPosition(0) } }
        } else if (homeLayoutState != null) {
            // v91 : restaure la position verticale du Home apres retour depuis un detail
            val st = homeLayoutState
            homeLayoutState = null
            _binding?.rvHome?.let { rv -> rv.post { if (_binding != null) rv.layoutManager?.onRestoreInstanceState(st) } }
        }
    }
}
