package com.streamflixreborn.streamflix.activities.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.bumptech.glide.Glide
import com.tanasi.navigation.widget.setupWithNavController
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.ActivityMainTvBinding
import com.streamflixreborn.streamflix.databinding.ContentHeaderMenuMainTvBinding
import com.streamflixreborn.streamflix.fragments.player.PlayerTvFragment
import com.streamflixreborn.streamflix.ui.UpdateAppTvDialog
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.WiflixProvider
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import kotlinx.coroutines.launch

class MainTvActivity : FragmentActivity() {

    private companion object {
        // True the first time the JVM/process starts. Survives activity restarts
        // (provider clicks re-launch with FLAG_ACTIVITY_NEW_TASK|CLEAR_TASK) but
        // resets on a true cold start (process kill).
        private var isFreshProcessLaunch = true
    }

    /** 2026-05-09 v3 : intercept OK key au niveau activity pour TOUS providers.
     *  - Si controls cachés → show + focus sur SETTINGS (bouton "fictif" neutre,
     *    OK suivant ouvre les paramètres, pas pause).
     *  - Si controls visibles → comportement natif (D-pad vers play/pause +
     *    OK pause comme attendu, OK sur settings ouvre settings, etc.). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
             event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
            try {
                val navHost = supportFragmentManager.findFragmentById(binding.navMainFragment.id)
                val currentFragment = navHost?.childFragmentManager?.fragments?.lastOrNull()
                val playerView = currentFragment?.view?.findViewById<androidx.media3.ui.PlayerView>(R.id.pv_player)
                // 2026-05-09 v19 : NE PAS intercepter quand le panel Settings est ouvert
                // (sinon clic OK dans le picker = redirige sur le bouton settings → cassé).
                val settingsPanel = currentFragment?.view?.findViewById<View>(R.id.settings)
                val settingsPanelVisible = settingsPanel != null && settingsPanel.visibility == View.VISIBLE
                if (!settingsPanelVisible && playerView != null && !playerView.isControllerFullyVisible) {
                    val settingsBtn = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
                    playerView.showController()
                    settingsBtn?.requestFocus()
                    return true  // consume → pas de pause
                }
                // Controls visibles ou panel Settings ouvert : on laisse passer.
            } catch (_: Throwable) {}
        }
        return super.dispatchKeyEvent(event)
    }

    private var _binding: ActivityMainTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()

    private lateinit var updateAppDialog: UpdateAppTvDialog

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Il setup delle preferenze è già avvenuto in StreamFlixApp
        setTheme(ThemeManager.tvThemeRes(UserPreferences.selectedTheme))
        
        super.onCreate(savedInstanceState)
        
        // Inizializza il provider con il context dell'attività per gestire eventuali bypass visibili
        WiflixProvider.init(this)

        _binding = ActivityMainTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeNavigationChrome()

        binding.ivSplashOverlay.animate()
            .alpha(0f)
            .setDuration(800)
            .setStartDelay(400)
            .withEndAction {
                binding.ivSplashOverlay.visibility = View.GONE
            }

        val navHostFragment = this.supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as NavHostFragment
        val navController = navHostFragment.navController

        adjustLayoutDelta(null, null)

        if (BuildConfig.APP_LAYOUT == "mobile" || (BuildConfig.APP_LAYOUT != "tv" && !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))) {
            finish()
            startActivity(Intent(this, MainMobileActivity::class.java))
            return
        }

        // Per user request: cold start lands on the Providers home with NO
        // active provider. This avoids auto-loading a memory-heavy provider
        // (which can crash low-RAM devices) and forces the user to consciously
        // pick which category/provider they want to use today.
        if (savedInstanceState == null && isFreshProcessLaunch) {
            UserPreferences.currentProvider = null
            isFreshProcessLaunch = false
            // Stay on R.id.providers (the start destination).
        } else if (savedInstanceState == null) {
            // Activity restart from a provider click — jump to home if a
            // provider is now selected so the user actually lands on it.
            isFreshProcessLaunch = false
            if (UserPreferences.currentProvider != null) {
                navController.navigate(R.id.home)
            }
        } else {
            // On activity recreation (e.g. Chromecast process kill), ensure we're not
            // stuck on the providers screen if a provider is already selected.
            val currentDest = navController.currentDestination?.id
            if (currentDest == R.id.providers && UserPreferences.currentProvider != null) {
                navController.navigate(R.id.home)
            }
        }

        binding.navMain.setupWithNavController(navController)
        updateNavigationVisibility()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.navMainFragment.isFocusedByDefault = true
        }

        var previousDestinationId: Int? = null
        navController.addOnDestinationChangedListener { _, destination, _ ->
            // Clear Glide memory cache when switching between main tabs to free memory
            if (previousDestinationId != null && previousDestinationId != destination.id) {
                CacheUtils.clearMemoryCache(this)
            }
            previousDestinationId = destination.id

            binding.navMain.headerView?.apply {
                val header = ContentHeaderMenuMainTvBinding.bind(this)

                Glide.with(context)
                    .load(UserPreferences.currentProvider?.logo?.takeIf { it.isNotEmpty() } ?: R.drawable.ic_provider_default_logo)
                    .error(R.drawable.ic_provider_default_logo)
                    .into(header.ivNavigationHeaderIcon)
                header.tvNavigationHeaderTitle.text = UserPreferences.currentProvider?.name
                header.tvNavigationHeaderSubtitle.text = getString(R.string.main_menu_change_provider)
                val palette = ThemeManager.palette(UserPreferences.selectedTheme)
                header.tvNavigationHeaderTitle.setTextColor(palette.tvHeaderPrimary)
                header.tvNavigationHeaderSubtitle.setTextColor(palette.tvHeaderSecondary)
                setBackgroundColor(palette.tvNavBackground)

                setOnOpenListener {
                    header.tvNavigationHeaderTitle.visibility = View.VISIBLE
                    header.tvNavigationHeaderSubtitle.visibility = View.VISIBLE
                }
                setOnCloseListener {
                    header.tvNavigationHeaderTitle.visibility = View.GONE
                    header.tvNavigationHeaderSubtitle.visibility = View.GONE
                }

                setOnClickListener {
                    // Navigazione manuale per evitare dipendenza da Safe Args Directions non generate
                    navController.navigate(R.id.providers)
                }
            }

            when (destination.id) {
                R.id.search, R.id.home, R.id.movies, R.id.tv_shows, R.id.downloads, R.id.settings -> {
                    binding.navMain.visibility = View.VISIBLE
                    updateNavigationVisibility()
                }
                else -> binding.navMain.visibility = View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        if (!viewModel.updateDismissed) {
                            updateAppDialog = UpdateAppTvDialog(this@MainTvActivity, state.newReleases).also {
                                it.setOnUpdateClickListener { _ ->
                                    if (!it.isLoading) viewModel.downloadUpdate(this@MainTvActivity, state.asset)
                                }
                                it.setOnCancelClickListener {
                                    viewModel.updateDismissed = true
                                }
                                it.show()
                            }
                        }
                    }
                    MainViewModel.State.DownloadingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainTvActivity, state.apk)
                        if (::updateAppDialog.isInitialized) updateAppDialog.hide()
                    }
                    MainViewModel.State.InstallingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        Toast.makeText(this@MainTvActivity, state.error.message ?: "Update failed", Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (navController.currentDestination?.id) {
                    R.id.home -> {
                        // Back from a provider's home = return to the
                        // Providers selection (so the user can pick another
                        // one) instead of exiting to the TV launcher.
                        if (binding.navMain.hasFocus()) {
                            UserPreferences.currentProvider = null
                            navController.navigate(
                                R.id.providers,
                                null,
                                navOptions {
                                    launchSingleTop = true
                                    popUpTo(R.id.home) { inclusive = true }
                                }
                            )
                        } else {
                            binding.navMain.requestFocus()
                        }
                    }
                    R.id.settings, R.id.search, R.id.movies, R.id.tv_shows, R.id.downloads -> {
                        navigateToProviderHome(navController)
                        binding.navMain.requestFocus()
                    }
                    else -> {
                        val handled = (getCurrentFragment() as? PlayerTvFragment)?.onBackPressed() ?: false
                        if (!handled && !navController.navigateUp()) finish()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkUpdate()
    }

    private fun applyThemeNavigationChrome() {
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        binding.navMain.setBackgroundColor(palette.tvNavBackground)
        binding.navMain.headerView?.let { headerView ->
            headerView.setBackgroundColor(palette.tvNavBackground)
            val header = ContentHeaderMenuMainTvBinding.bind(headerView)
            header.tvNavigationHeaderTitle.setTextColor(palette.tvHeaderPrimary)
            header.tvNavigationHeaderSubtitle.setTextColor(palette.tvHeaderSecondary)
        }
    }
    
    private fun updateNavigationVisibility() {
        val provider = UserPreferences.currentProvider
        if (provider == null) {
            // 2026-05-09 : aucun provider sélectionné (cold start fresh) → cache
            // tous les onglets dépendants de la DB pour éviter le crash
            // "Current provider is not set" quand l'user clique avant d'avoir
            // pris un provider. Seul l'onglet Providers reste visible.
            binding.navMain.menu.findItem(R.id.home)?.isVisible = false
            binding.navMain.menu.findItem(R.id.search)?.isVisible = false
            binding.navMain.menu.findItem(R.id.movies)?.isVisible = false
            binding.navMain.menu.findItem(R.id.tv_shows)?.isVisible = false
            binding.navMain.menu.findItem(R.id.iptv_favorites)?.isVisible = false
            return
        }
        // Provider sélectionné : restaure la visibilité des onglets pertinents.
        binding.navMain.menu.findItem(R.id.home)?.isVisible = true
        binding.navMain.menu.findItem(R.id.search)?.isVisible = true
        binding.navMain.menu.findItem(R.id.movies)?.apply {
            isVisible = Provider.supportsMovies(provider)
            title = when (provider.name) {
                "VoirDrama", "VoirAnime", "FrenchAnime", "AnimeSama", "FrenchManga" -> getString(R.string.main_menu_series_fr)
                else -> getString(R.string.main_menu_movies)
            }
            if (provider.name in listOf("VoirDrama", "VoirAnime", "FrenchAnime", "AnimeSama", "FrenchManga")) {
                setIcon(R.drawable.ic_menu_tv)
            }
        }
        val isIptv = provider is com.streamflixreborn.streamflix.providers.IptvProvider
        binding.navMain.menu.findItem(R.id.tv_shows)?.apply {
            isVisible = Provider.supportsTvShows(provider)
            title = when {
                isIptv || provider.name in setOf("CableVisionHD", "TvporinternetHD") ->
                    getString(R.string.main_menu_all_channels)
                provider.name in setOf("VoirDrama", "VoirAnime", "FrenchAnime", "AnimeSama", "FrenchManga") ->
                    getString(R.string.main_menu_series)
                else -> getString(R.string.main_menu_tv_shows)
            }
        }
        // Favoris tab — IPTV providers only.
        binding.navMain.menu.findItem(R.id.iptv_favorites)?.isVisible = isIptv
    }

    fun adjustLayoutDelta(deltaX: Int?, deltaY: Int?) {
        val uDeltaX = deltaX ?: UserPreferences.paddingX
        val uDeltaY = deltaY ?: UserPreferences.paddingY
        binding.root.setPadding(uDeltaX, uDeltaY, uDeltaX, uDeltaY)
    }

    private fun navigateToProviderHome(navController: androidx.navigation.NavController) {
        if (!navController.popBackStack(R.id.home, false)) {
            navController.navigate(
                R.id.home,
                null,
                navOptions {
                    launchSingleTop = true
                    popUpTo(R.id.providers) {
                        inclusive = true
                    }
                }
            )
        }
    }
}
