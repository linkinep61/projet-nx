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
    /** 2026-05-12 : cached navController pour showIptvCategoryPicker hors onCreate. */
    private var cachedNavController: androidx.navigation.NavController? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    // 2026-05-12 : sur les TV à CPU lent (Sharp Aquos TVE19A confirmé), l'init
    // synchronique de onCreate prend ~5 secondes (dont 2.2s pour Conscrypt seul),
    // ce qui dépasse le seuil ANR Android (5s sans yield main thread). L'OS tue
    // l'activité silencieusement → splash figé sans crash.
    // Solution : différer chaque étape via Handler.postDelayed pour yield le main
    // thread entre les étapes. Le CPU total est identique mais l'ANR watchdog
    // est satisfait à chaque frame.
    private var savedStateForDeferredInit: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // try-catch sur setTheme : sur certains firmwares custom (Sharp TVE19A),
        // un attribut Material non supporté peut crasher. On retombe sur le
        // theme par défaut Android.
        try {
            setTheme(ThemeManager.tvThemeRes(UserPreferences.selectedTheme))
        } catch (e: Throwable) {
            android.util.Log.e("MainTvActivity", "setTheme failed: ${e.message}")
        }

        super.onCreate(savedInstanceState)
        savedStateForDeferredInit = savedInstanceState

        // 2026-05-12 (user "fameux changement de profil TV", inflate fail
        // Chromecast) : l'ancien design postDelayait l'inflate de ~200ms total
        // sur 4 étapes. Sur Chromecast, ce délai laissait Android appeler
        // onSaveInstanceState avant l'inflate → IllegalStateException
        // "Can not perform this action after onSaveInstanceState" quand
        // FragmentContainerView (NavHostFragment) tente d'enqueue une transaction.
        // Solution : faire les steps SYNCHRO dans onCreate (avant que le système
        // ait l'occasion de save state). Seul step4 (nav + listeners) reste
        // déferré pour laisser le splash s'afficher.
        try { WiflixProvider.init(this) } catch (_: Throwable) {}
        try {
            _binding = ActivityMainTvBinding.inflate(layoutInflater)
        } catch (e: Throwable) {
            android.util.Log.e("MainTvActivity", "inflate failed: ${e.message}", e)
            return
        }
        try {
            setContentView(binding.root)
            applyThemeNavigationChrome()
        } catch (e: Throwable) {
            android.util.Log.e("MainTvActivity", "setContentView failed: ${e.message}", e)
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runInitStep4() }, 100)
    }

    private fun runInitStep4() {
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
        cachedNavController = navController

        // 2026-05-12 (user "des retours tout le temps") : guard contre crash
        // "Current provider is not set" — si on est restauré sur home/movies/tv_shows
        // mais que currentProvider est null (process kill + restore), redirige vers
        // l'écran providers AVANT que HomeTvFragment ne s'instancie et crashe.
        if (UserPreferences.currentProvider == null) {
            val dest = navController.currentDestination?.id
            if (dest != null && dest != R.id.providers) {
                runCatching {
                    navController.navigate(
                        R.id.providers,
                        null,
                        androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(navController.graph.startDestinationId, inclusive = false)
                            .build(),
                    )
                }
            }
        }

        adjustLayoutDelta(null, null)

        if (BuildConfig.APP_LAYOUT == "mobile" || (BuildConfig.APP_LAYOUT != "tv" && !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))) {
            finish()
            startActivity(Intent(this, MainMobileActivity::class.java))
            return
        }

        // 2026-05-12 (user "on peut s'attaquer à la version télé") : si aucun
        // profil n'est actif, redirect vers l'écran "Qui regarde ?" TV avant
        // d'afficher la home. StreamFlixApp clear le profil au cold start →
        // garantit que le picker apparaît à chaque cold launch (Netflix-style).
        if (com.streamflixreborn.streamflix.utils.ProfileManager.currentProfile() == null) {
            finish()
            startActivity(Intent(this, com.streamflixreborn.streamflix.activities.profile.ProfilePickerTvActivity::class.java))
            return
        }

        // Per user request: cold start lands on the Providers home with NO
        // active provider. This avoids auto-loading a memory-heavy provider
        // (which can crash low-RAM devices) and forces the user to consciously
        // pick which category/provider they want to use today.
        val savedInstanceState = savedStateForDeferredInit
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

        // 2026-05-12 (user "faire un bouton dédié") : item Catégories dans la sidebar.
        // Clic → ouvre AlertDialog avec catégories du fragment courant (LIVE/MOVIE/SERIES).
        binding.navMain.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.iptv_categories -> {
                    val currentDestId = navController.currentDestination?.id ?: R.id.home
                    showIptvCategoryPicker(currentDestId)
                    return@setOnItemSelectedListener false
                }
                R.id.iptv_sources -> {
                    startActivity(android.content.Intent(
                        this,
                        com.streamflixreborn.streamflix.activities.iptv.IptvSourcesActivity::class.java,
                    ))
                    return@setOnItemSelectedListener false
                }
            }
            // 2026-05-12 (user "le bouton accueil TV ne sert à rien, seules les
            // catégories l'alimentent") : pour Mon IPTV, clic sur TV/Films/Séries
            // ouvre directement le picker au lieu de juste naviguer.
            val handled = androidx.navigation.ui.NavigationUI.onNavDestinationSelected(menuItem, navController)
            val provider = UserPreferences.currentProvider
            if (handled && provider is com.streamflixreborn.streamflix.providers.MyIptvProvider &&
                menuItem.itemId in setOf(R.id.home, R.id.movies, R.id.tv_shows)) {
                binding.navMain.postDelayed({ showIptvCategoryPicker(menuItem.itemId) }, 350L)
            }
            handled
        }

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
                // 2026-05-12 (user "il faut une touche pour retourner au menu changement
                // de source") : long-press sur le header → ouvre directement le tableau
                // des sources IPTV pour switch (sans repasser par la liste des providers).
                setOnLongClickListener {
                    if (UserPreferences.currentProvider is com.streamflixreborn.streamflix.providers.MyIptvProvider) {
                        startActivity(android.content.Intent(
                            this@MainTvActivity,
                            com.streamflixreborn.streamflix.activities.iptv.IptvSourcesActivity::class.java,
                        ))
                        true
                    } else false
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
                    R.id.settings, R.id.search, R.id.movies, R.id.tv_shows, R.id.downloads, R.id.iptv_favorites -> {
                        // 2026-05-10 : ajout iptv_favorites — back depuis l'onglet
                        // ❤ Favoris doit retourner au home du provider courant
                        // (avant : tombait dans else → navigateUp → pop start
                        // destination = providers, donc l'user revenait au
                        // sélecteur de provider au lieu du home Vavoo/Vegeta/etc.)
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
    
    /** 2026-05-12 (user "quand on clique TV, menu déroulant avec catégories") :
     *  ouvre un AlertDialog listant toutes les catégories disponibles pour le type
     *  donné (LIVE = home, MOVIE = movies, SERIES = tv_shows). Sur sélection,
     *  stocke le filtre dans MyIptvProvider et reload le fragment courant. */
    private fun showIptvCategoryPicker(menuItemId: Int) {
        val provider = com.streamflixreborn.streamflix.providers.MyIptvProvider
        val type = when (menuItemId) {
            R.id.home -> com.streamflixreborn.streamflix.utils.IptvClassifier.ContentType.LIVE
            R.id.movies -> com.streamflixreborn.streamflix.utils.IptvClassifier.ContentType.MOVIE
            R.id.tv_shows -> com.streamflixreborn.streamflix.utils.IptvClassifier.ContentType.SERIES
            else -> return
        }
        val categoriesWithCount = provider.availableCategoriesWithCount(type)
        if (categoriesWithCount.isEmpty()) {
            Toast.makeText(this, "Aucune catégorie en cache — recharge la source d'abord.", Toast.LENGTH_SHORT).show()
            return
        }
        val totalCount = categoriesWithCount.sumOf { it.second }
        // Format display : "FR| FRANCE FHD HD  (105)"
        val displayItems = arrayOf("Toutes les catégories FR ($totalCount)") +
            categoriesWithCount.map { (name, count) -> "$name  ($count)" }.toTypedArray()
        // Garde les noms bruts pour le filtre (sans le compteur)
        val rawNames = arrayOf<String?>(null) + categoriesWithCount.map { it.first }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Choisir une catégorie")
            .setItems(displayItems) { _, idx ->
                val selected = rawNames[idx]
                when (type) {
                    com.streamflixreborn.streamflix.utils.IptvClassifier.ContentType.LIVE -> provider.selectedCategoryLive = selected
                    com.streamflixreborn.streamflix.utils.IptvClassifier.ContentType.MOVIE -> provider.selectedCategoryMovie = selected
                    com.streamflixreborn.streamflix.utils.IptvClassifier.ContentType.SERIES -> provider.selectedCategorySeries = selected
                }
                // 2026-05-12 (user "quand tu cliques une catégorie ça change pas le home") :
                // notifie le viewModel via ProviderChangeNotifier — HomeTvFragment écoute
                // ce flow et rappelle viewModel.getHome() qui va re-calculer avec le filtre.
                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
            }
            .setNegativeButton("Annuler", null)
            .show()
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
            binding.navMain.menu.findItem(R.id.iptv_categories)?.isVisible = false
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
        val isMyIptv = provider is com.streamflixreborn.streamflix.providers.MyIptvProvider
        binding.navMain.menu.findItem(R.id.tv_shows)?.apply {
            isVisible = Provider.supportsTvShows(provider)
            title = when {
                // 2026-05-12 : Mon IPTV expose vraies séries via Xtream API → garde "Séries TV"
                isMyIptv -> getString(R.string.main_menu_tv_shows)
                isIptv || provider.name in setOf("CableVisionHD", "TvporinternetHD") ->
                    getString(R.string.main_menu_all_channels)
                provider.name in setOf("VoirDrama", "VoirAnime", "FrenchAnime", "AnimeSama", "FrenchManga") ->
                    getString(R.string.main_menu_series)
                else -> getString(R.string.main_menu_tv_shows)
            }
            // Force l'icône série (ic_menu_series) car le default est ic_menu_tv qui collide avec TV
            if (isMyIptv) setIcon(R.drawable.ic_menu_series)
        }
        // Favoris tab — IPTV providers only.
        binding.navMain.menu.findItem(R.id.iptv_favorites)?.isVisible = isIptv
        // 2026-05-12 : Catégories + Mes sources visibles uniquement pour Mon IPTV
        binding.navMain.menu.findItem(R.id.iptv_categories)?.isVisible = isMyIptv
        binding.navMain.menu.findItem(R.id.iptv_sources)?.isVisible = isMyIptv
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
