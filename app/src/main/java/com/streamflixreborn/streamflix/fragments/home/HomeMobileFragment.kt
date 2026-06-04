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
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.providers.IptvProvider
import kotlinx.coroutines.launch
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

        binding.miniPlayerContainer.visibility = View.VISIBLE
        binding.miniPlayerChannelName.text = MiniPlayerController.currentChannelName ?: ""
        MiniPlayerController.currentChannelPoster?.let { poster ->
            Glide.with(this).load(poster).into(binding.miniPlayerChannelLogo)
        }
        updatePauseButton()

        // Re-set the interceptor (it may have been cleared in onDestroyView)
        if (MiniPlayerController.onIptvChannelClick == null) {
            MiniPlayerController.onIptvChannelClick = { tvShow ->
                if (tvShow.id == MiniPlayerController.currentChannelId) {
                    Log.d("HomeMobile", "Same channel, stopping mini player for fullscreen (onResume): ${tvShow.title}")
                    MiniPlayerController.stopAsync()
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
        if (_binding != null) {
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

        // If a channel was already playing (e.g. after rotation), restore the UI
        if (MiniPlayerController.currentChannelId != null) {
            binding.miniPlayerContainer.visibility = View.VISIBLE
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
            if (tvShow.id == MiniPlayerController.currentChannelId) {
                Log.d("HomeMobile", "Same channel, stopping mini player for fullscreen: ${tvShow.title}")
                MiniPlayerController.stopAsync()
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

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape: mini player on the right 1/3
            // Container: top-right, width = 0 with 0.33 horizontal weight
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
            // Portrait: full width at top
            cs.connect(container.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            cs.connect(container.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(container.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.clear(container.id, ConstraintSet.BOTTOM)
            cs.constrainPercentWidth(container.id, 1f)
            cs.constrainHeight(container.id, ConstraintSet.WRAP_CONTENT)

            // RecyclerView: below the mini player
            cs.connect(recycler.id, ConstraintSet.TOP, container.id, ConstraintSet.BOTTOM)
            cs.connect(recycler.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(recycler.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.connect(recycler.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)

            // Reset player height to 200dp
            binding.miniPlayerView.layoutParams.height = (200 * resources.displayMetrics.density).toInt()
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
                when (state) {
                    is MiniPlayerController.State.Idle -> {
                        binding.miniPlayerContainer.visibility = View.GONE
                    }
                    is MiniPlayerController.State.Loading -> {
                        ensureMiniPlayerAttached()
                        binding.miniPlayerContainer.visibility = View.VISIBLE
                        binding.miniPlayerChannelName.text = state.channelName
                        binding.miniPlayerLoading.visibility = View.VISIBLE
                    }
                    is MiniPlayerController.State.Playing -> {
                        ensureMiniPlayerAttached()
                        binding.miniPlayerContainer.visibility = View.VISIBLE
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
                    MiniPlayerController.stopAsync()
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

    private fun navigateToFullPlayer() {
        val channelId = MiniPlayerController.currentChannelId ?: return
        val channelName = MiniPlayerController.currentChannelName ?: channelId
        val channelPoster = MiniPlayerController.currentChannelPoster

        // Release the ExoPlayer so the full player can create its own,
        // but keep the channel info so we can restart on return.
        MiniPlayerController.releasePlayerKeepState()

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
        // Fond wallpaper fixe sur mobile — même fond que l'écran providers
        appAdapter.onSwiperPageChanged = { _ -> /* no-op sur mobile */ }

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
        if (isMyIptv || isVavoo) {
            binding.ivIptvLanguage.visibility = View.VISIBLE
            binding.ivIptvLanguage.setOnClickListener {
                showIptvLanguageFilterPicker()
            }
        } else {
            binding.ivIptvLanguage.visibility = View.GONE
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

    /** 2026-05-13 : picker rapide du filtre langue.
     *  2026-05-23 : route vers le bon picker selon le provider actif
     *  (Vavoo = pays, WiTV v2 = groupe/langue, MyIptv = filtre auto/all/fr). */
    private fun showIptvLanguageFilterPicker() {
        val currentProv = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
        when {
            currentProv is com.streamflixreborn.streamflix.providers.VavooProvider -> showVavooCountryPicker()
            else -> showMyIptvLanguageFilterPicker()
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
            binding.rvHome.post { binding.rvHome.scrollToPosition(0) }
        } else if (homeLayoutState != null) {
            // v91 : restaure la position verticale du Home apres retour depuis un detail
            val st = homeLayoutState
            homeLayoutState = null
            binding.rvHome.post { binding.rvHome.layoutManager?.onRestoreInstanceState(st) }
        }
    }
}
