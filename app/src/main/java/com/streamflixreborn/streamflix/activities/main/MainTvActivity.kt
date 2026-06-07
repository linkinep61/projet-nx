package com.streamflixreborn.streamflix.activities.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import com.streamflixreborn.streamflix.providers.AnimeSamaProvider
import com.streamflixreborn.streamflix.providers.WiflixProvider
import com.streamflixreborn.streamflix.providers.FranimeProvider
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
     *    OK pause comme attendu, OK sur settings ouvre settings, etc.).
     *
     *  2026-05-13 (user "quand je fais gauche je peux pas aller directement sur
     *  mon iptv TV et quand je fais haut ça fait rien") : intercept DPAD_LEFT
     *  et DPAD_UP au niveau Activity quand le focus est sur une tile dans
     *  hgv_category. La résolution `nextFocusLeft/Up` du XML ne s'applique
     *  qu'au container, pas aux ViewHolders enfants → on force ici.
     *  - LEFT depuis tile → focus sur le menu courant de la sidebar
     *  - UP depuis tile → focus sur iv_iptv_categories si visible (Mon IPTV) */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // 2026-06-04 : overlay « À SUIVRE » — intercept Activity-level pour
        //   forcer OK → Lire maintenant même si le focus visuel est resté
        //   sur exoSettings (controller). PRIORITÉ MAXIMALE sinon le bouton
        //   focused du controller consomme OK.
        // 2026-06-07 (BUG v1.7.209 : « provider reste en background quand on
        //   fait retour », rapporté Freebox mini 4K + MiBox) : garde-fou anti-
        //   orphan — si isVisible reste à true alors que les callbacks ont été
        //   GC (PlayerTvFragment détruit sans passer par hideNextEpisodeOverlay)
        //   on consommait TOUS les BACK suivants → écrans s'empilaient. Auto-
        //   reset si on détecte l'état orphan (callbacks null), puis on n'inter-
        //   cepte plus.
        val nextEpOverlay = com.streamflixreborn.streamflix.utils.NextEpisodeOverlayState
        if (nextEpOverlay.isVisible &&
            nextEpOverlay.onConfirm == null &&
            nextEpOverlay.onDismiss == null) {
            android.util.Log.w(
                "MainTvActivity",
                "NextEpisodeOverlayState orphan détecté (isVisible=true, callbacks null) → reset"
            )
            nextEpOverlay.isVisible = false
        }
        if (nextEpOverlay.isVisible) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        nextEpOverlay.onConfirm?.invoke()
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_BACK -> {
                        nextEpOverlay.onDismiss?.invoke()
                        return true
                    }
                }
            } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                // Avale l'ACTION_UP qui suit, sinon le controller le reçoit isolé.
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                    android.view.KeyEvent.KEYCODE_BACK -> return true
                }
            }
        }

        // 2026-05-31 : liste des chaînes IPTV — callbacks globaux
        if (com.streamflixreborn.streamflix.utils.ChannelListState.isOpen) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        com.streamflixreborn.streamflix.utils.ChannelListState.onOkPressed?.invoke()
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        com.streamflixreborn.streamflix.utils.ChannelListState.onUpPressed?.invoke()
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                        com.streamflixreborn.streamflix.utils.ChannelListState.onDownPressed?.invoke()
                    android.view.KeyEvent.KEYCODE_BACK,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                        com.streamflixreborn.streamflix.utils.ChannelListState.onCloseRequested?.invoke()
                }
            }
            return true
        }

        // Abyss/Hydrax overlay: route remote keys to the WebView cursor
        // (the overlay never receives key focus on TV).
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val pf = getCurrentFragment() as? PlayerTvFragment
            if (pf != null && pf.handleOverlayKey(event.keyCode, event.repeatCount)) return true
        }
        // === LEFT/UP override pour Mon IPTV (ou tout provider) sur les tiles ===
        if (event.action == android.view.KeyEvent.ACTION_DOWN && (
            event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
            event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP)) {
            try {
                val focused = currentFocus
                if (focused != null) {
                    // Détecte si on est dans une tile (descendant de hgv_category)
                    var ancestor: android.view.ViewParent? = focused.parent
                    var insideHgv = false
                    var insideTabFR = false
                    while (ancestor != null) {
                        if (ancestor is View && ancestor.id == R.id.hgv_category) {
                            insideHgv = true
                            break
                        }
                        // Don't intercept if we're already on the language tabs
                        if (ancestor is View && (
                            ancestor.id == R.id.tab_fr || ancestor.id == R.id.tab_vostfr ||
                            ancestor.id == R.id.tab_language)) {
                            insideTabFR = true
                            break
                        }
                        ancestor = ancestor.parent
                    }
                    if (insideHgv && !insideTabFR) {
                        if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                            // LEFT → focus sur sidebar SI on est sur la 1ère tile.
                            // 2026-05-13 (user "tu as fait la même chose pour
                            // série et fin et home tu arrives pas") : la
                            // détection RecyclerView doit walker la chaîne de
                            // parents — sur Home la hiérarchie est plus
                            // profonde (vgv_home > row > hgv_category > tile
                            // wrapper > tile content). On remonte jusqu'à
                            // trouver un RecyclerView et on demande la position.
                            var node: android.view.View = focused
                            var rv: androidx.recyclerview.widget.RecyclerView? = null
                            var rvChild: android.view.View = focused
                            for (lvl in 0..6) {
                                val p = node.parent ?: break
                                if (p is androidx.recyclerview.widget.RecyclerView) {
                                    rv = p
                                    rvChild = node
                                    break
                                }
                                if (p is android.view.View) node = p else break
                            }
                            val pos = rv?.getChildAdapterPosition(rvChild) ?: -1
                            if (pos == 0) {
                                val ids = listOf(R.id.home, R.id.movies, R.id.tv_shows, R.id.iptv_favorites, R.id.search)
                                for (id in ids) {
                                    val target = binding.navMain.findViewById<View>(id)
                                    if (target != null && target.isShown && target.requestFocus()) return true
                                }
                            }
                        } else if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                            // UP depuis tile → focus sur iv_iptv_categories si visible
                            val ivCat = (currentFocus?.rootView as? View)
                                ?.findViewById<View>(R.id.iv_iptv_categories)
                            if (ivCat != null && ivCat.isShown && ivCat.visibility == View.VISIBLE) {
                                if (ivCat.requestFocus()) return true
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

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
                // 2026-05-31 : ne pas intercepter OK quand la liste des chaînes est ouverte
                val channelListPanel2 = window.decorView.findViewById<View>(R.id.layout_channel_list)
                val channelListOpen = channelListPanel2 != null && channelListPanel2.visibility == View.VISIBLE
                if (!channelListOpen && !settingsPanelVisible && playerView != null && !playerView.isControllerFullyVisible) {
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
        try { AnimeSamaProvider.init(this) } catch (_: Throwable) {}
        // 2026-05-16 : DessinAnime DÉSACTIVÉ (serveur dessinanime.cc instable).
        // try { DessinAnimeProvider.init(this) } catch (_: Throwable) {}
        try { FranimeProvider.init(this) } catch (_: Throwable) {}
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
        // 2026-05-15 (user "éviter que les écrans mettre en veille tous les
        // 5 minutes") : applique le flag KEEP_SCREEN_ON si l'option est ON.
        if (UserPreferences.keepScreenOnApp) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        // 2026-05-12 : si aucun profil n'est actif, redirect vers "Qui regarde ?"
        // 2026-05-22 : si le ProfilePicker est désactivé dans les préfs, on set
        // le profil principal automatiquement (pas de picker).
        if (com.streamflixreborn.streamflix.utils.ProfileManager.currentProfile() == null) {
            if (com.streamflixreborn.streamflix.utils.UserPreferences.profilePickerEnabled) {
                finish()
                startActivity(Intent(this, com.streamflixreborn.streamflix.activities.profile.ProfilePickerTvActivity::class.java))
                return
            } else {
                // Picker désactivé → profil principal auto
                val defaultProfile = com.streamflixreborn.streamflix.utils.ProfileStore.getAll().firstOrNull()
                if (defaultProfile != null) {
                    com.streamflixreborn.streamflix.utils.ProfileManager.setCurrentProfile(defaultProfile)
                }
            }
        }

        // Per user request: cold start lands on the Providers home with NO
        // active provider. This avoids auto-loading a memory-heavy provider
        // (which can crash low-RAM devices) and forces the user to consciously
        // pick which category/provider they want to use today.
        val savedInstanceState = savedStateForDeferredInit
        // 2026-05-14 (user "à l'ouverture d'un profil j'ai que le home"
        // [fournisseur]) : extra envoyé par ProfilePickerTvActivity quand l'user
        // vient de pick un profil. Force le Home Fournisseur même si le profil
        // a déjà un currentProvider mémorisé.
        val forceProvidersScreen = intent?.getBooleanExtra("FORCE_PROVIDERS_SCREEN", false) == true
        if (forceProvidersScreen) {
            UserPreferences.currentProvider = null
            isFreshProcessLaunch = false
            // Stay on R.id.providers (the start destination).
        } else if (savedInstanceState == null && isFreshProcessLaunch) {
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

        // 2026-05-14 (user "les icônes de la barre de gauche ne correspondent pas
        // au provider en général pour les animés tu m'as mis des icônes pour un
        // provider IPTV") : observe les changements de provider pour refresh la
        // visibilité des items IPTV (Favoris/Catégories/Pays-langue) dans la
        // sidebar. Sans ça, switch MyIptv → DessinAnime gardait les icônes IPTV
        // jusqu'à navigation manuelle.
        lifecycleScope.launch {
            com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
                .providerChangeFlow
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect {
                    updateNavigationVisibility()
                }
        }

        // 2026-05-12 (user "faire un bouton dédié") : item Catégories dans la sidebar.
        // Clic → ouvre AlertDialog avec catégories du fragment courant (LIVE/MOVIE/SERIES).
        // 2026-05-13 (user "ça éviterait d'avoir des icônes partout") : items
        // iptv_categories/iptv_sources retirés du menu. Le picker s'ouvre auto
        // au clic sur TV/Films/Séries pour Mon IPTV.
        binding.navMain.setOnItemSelectedListener { menuItem ->
            // 2026-05-13 (user "non dans la bar de gauche y a la place") :
            // intercepte les 2 items IPTV (catégories + filtre langue) AVANT
            // NavigationUI — ils n'ont pas de destination, juste un dialog à
            // ouvrir.
            when (menuItem.itemId) {
                R.id.iptv_categories_menu -> {
                    // 2026-05-13 (user "et ça s'active que le 2e clic" +
                    // wrong categories shown) : utilise la destination COURANTE
                    // pour choisir le bon type de catégories (LIVE/MOVIE/SERIES).
                    val currentDestId = navController.currentDestination?.id ?: R.id.home
                    val pickerType = when (currentDestId) {
                        R.id.movies -> R.id.movies
                        R.id.tv_shows -> R.id.tv_shows
                        else -> R.id.home
                    }
                    showIptvCategoryPickerPublic(pickerType)
                    return@setOnItemSelectedListener false
                }
                R.id.iptv_language_menu -> {
                    val provider = UserPreferences.currentProvider
                    Log.d("MainTv", "iptv_language_menu clicked, provider=${provider?.name}, isVavoo=${provider is com.streamflixreborn.streamflix.providers.VavooProvider}")
                    if (provider is com.streamflixreborn.streamflix.providers.VavooProvider) {
                        showVavooCountryPicker()
                    } else {
                        showIptvLanguageFilterPicker()
                    }
                    return@setOnItemSelectedListener false
                }
                R.id.catalog_filter_menu -> {
                    showCatalogFilterPicker()
                    return@setOnItemSelectedListener false
                }
            }
            val handled = androidx.navigation.ui.NavigationUI.onNavDestinationSelected(menuItem, navController)
            val provider = UserPreferences.currentProvider
            if (handled && provider is com.streamflixreborn.streamflix.providers.MyIptvProvider &&
                menuItem.itemId in setOf(R.id.home, R.id.movies, R.id.tv_shows)) {
                binding.navMain.postDelayed({ showIptvCategoryPicker(menuItem.itemId) }, 350L)
            }
            handled
        }

        // 2026-05-26 : re-clic sur Films ou Séries → ouvre le filtre genre TMDB.
        // Pas d'icône supplémentaire dans la sidebar, juste un double-clic.
        binding.navMain.setOnItemReselectedListener { menuItem ->
            Log.d("MainTv", "RESELECT item=${menuItem.title} id=${menuItem.itemId} provider=${UserPreferences.currentProvider?.name}")
            when (menuItem.itemId) {
                R.id.movies, R.id.tv_shows -> {
                    val supported = com.streamflixreborn.streamflix.utils.GenreFilter.isSupported(
                        UserPreferences.currentProvider?.name
                    )
                    Log.d("MainTv", "Genre supported=$supported")
                    if (supported) {
                        showGenreFilterPicker()
                    }
                }
            }
            true
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
                    // 2026-05-13 (user "tu as vu du bouton paramètre on peut pas
                    // retourner sur le home en haut") : click intelligent —
                    // depuis settings/movies/tv_shows/favoris → ramène au Home
                    // (raccourci utile sur Mon IPTV). Depuis le Home → providers
                    // picker (comportement original). Navigazione manuale per
                    // evitare dipendenza da Safe Args Directions non generate.
                    val currentId = navController.currentDestination?.id
                    val targetId = if (currentId == R.id.home || UserPreferences.currentProvider == null) {
                        R.id.providers
                    } else {
                        R.id.home
                    }
                    try { navController.navigate(targetId) } catch (_: Throwable) {
                        navController.navigate(R.id.providers)
                    }
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
        // 2026-05-17 : touch timestamp pour qu'une recréation de process
        // dans les 30 prochaines minutes ne re-bounce pas vers ProfilePicker
        // (cf StreamFlixApp.onCreate logique smart-cold-start).
        com.streamflixreborn.streamflix.utils.ProfileStore.touchLastActiveTimestamp()

        // 2026-05-22 : verrouillage auto au retour du background (Home TV).
        // Si le ProfilePicker est activé dans les préfs ET que l'app revient
        // du background → redirect vers le picker "Qui regarde ?".
        if (com.streamflixreborn.streamflix.StreamFlixApp.shouldLockOnResume
            && com.streamflixreborn.streamflix.utils.UserPreferences.profilePickerEnabled) {
            com.streamflixreborn.streamflix.StreamFlixApp.shouldLockOnResume = false
            com.streamflixreborn.streamflix.utils.ProfileStore.clearLastActiveTimestamp()
            com.streamflixreborn.streamflix.utils.ProfileStore.setCurrentProfileId(null)
            UserPreferences.currentProvider = null
            finish()
            startActivity(Intent(this, com.streamflixreborn.streamflix.activities.profile.ProfilePickerTvActivity::class.java))
            return
        }
        com.streamflixreborn.streamflix.StreamFlixApp.shouldLockOnResume = false
    }

    override fun onDestroy() {
        // 2026-05-22 : si l'user ferme volontairement l'app (back/swipe),
        // on efface le timestamp pour que le prochain lancement retombe
        // sur le ProfilePicker (Netflix-style).
        if (isFinishing) {
            com.streamflixreborn.streamflix.utils.ProfileStore.clearLastActiveTimestamp()
        }
        super.onDestroy()
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
    
    /** 2026-05-13 (user "il faut que tu fasses la même chose pour la TV") :
     *  exposé en public pour que HomeTvFragment puisse l'appeler depuis ses
     *  boutons IPTV top-left. */
    fun showIptvCategoryPickerPublic(menuItemId: Int) = showIptvCategoryPicker(menuItemId)

    /** 2026-05-13 : picker rapide du filtre langue (Auto / Toutes / FR strict).
     *  Modifie le pref `pref_iptv_language_filter`, reset les filtres catégorie,
     *  invalide HomeCacheStore et notifie le viewModel pour rafraîchir le home. */
    fun showIptvLanguageFilterPicker() {
        val provider = com.streamflixreborn.streamflix.providers.MyIptvProvider
        val options = arrayOf(
            "Auto (recommandé)" to "auto",
            "Toutes les langues" to "all",
            "Français uniquement" to "fr",
        )
        val current = provider.getLanguageFilterMode()
        val currentIdx = options.indexOfFirst { it.second == current }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Filtrer par langue")
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), currentIdx) { dlg, idx ->
                val newMode = options[idx].second
                if (newMode != current) {
                    provider.setLanguageFilterMode(newMode)
                    provider.selectedCategoryLive = null
                    provider.selectedCategoryMovie = null
                    provider.selectedCategorySeries = null
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                        applicationContext, provider,
                    )
                    com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                    Toast.makeText(
                        applicationContext,
                        "Filtre langue : ${options[idx].first}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                dlg.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-05-21 : picker filtre catalogue (item sidebar sous Paramètres,
     *  providers TMDB type Cloudstream). Sauve le mode PAR provider, invalide le
     *  HomeCacheStore et notifie pour rafraîchir le home. */
    fun showCatalogFilterPicker() {
        val provider = UserPreferences.currentProvider ?: return
        val modes = com.streamflixreborn.streamflix.utils.CatalogFilter.Mode.entries
        val current = com.streamflixreborn.streamflix.utils.CatalogFilter.get(provider.name)
        val currentIdx = modes.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Filtrer le catalogue")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), currentIdx) { dlg, idx ->
                val newMode = modes[idx]
                if (newMode != current) {
                    com.streamflixreborn.streamflix.utils.CatalogFilter.set(provider.name, newMode)
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(applicationContext, provider)
                    com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                    Toast.makeText(applicationContext, "Catalogue : ${newMode.label}", Toast.LENGTH_SHORT).show()
                }
                dlg.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-05-26 : filtre par genre TMDB (Action, Comédie, Drame…).
     *  Sauve le genre PAR provider et notifie pour rafraîchir Films/Séries. */
    fun showGenreFilterPicker() {
        val provider = UserPreferences.currentProvider ?: return
        showGenreFilterPickerInner(provider)
    }

    private fun showGenreFilterPickerInner(provider: com.streamflixreborn.streamflix.providers.Provider) {
        val entries = com.streamflixreborn.streamflix.utils.GenreFilter.genresForProvider()
        val current = com.streamflixreborn.streamflix.utils.GenreFilter.get(provider.name)
        // "Tous" en 1ère position (= pas de filtre), puis les genres
        val labels = arrayOf("Tous les genres") + entries.map { it.name }.toTypedArray()
        val currentIdx = if (current == null) 0 else entries.indexOfFirst { it.id == current.id } + 1
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Filtrer par genre")
            .setSingleChoiceItems(labels, currentIdx.coerceAtLeast(0)) { dlg, idx ->
                val newGenre = if (idx == 0) null else entries[idx - 1]
                val changed = newGenre?.id != current?.id
                if (changed) {
                    com.streamflixreborn.streamflix.utils.GenreFilter.set(provider.name, newGenre)
                    com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                    Toast.makeText(
                        applicationContext,
                        if (newGenre != null) "Genre : ${newGenre.name}" else "Genre : tous",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dlg.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-05-26 : filtre langue VF/VOSTFR pour AnimeSama (TV). */
    private fun showAnimeSamaLanguagePicker(provider: com.streamflixreborn.streamflix.providers.Provider) {
        val options = arrayOf(
            "Toutes les langues" to null as String?,
            "VF uniquement" to "vf",
            "VOSTFR uniquement" to "vostfr",
        )
        val current = com.streamflixreborn.streamflix.utils.GenreFilter.getLang(provider.name)
        val currentIdx = options.indexOfFirst { it.second == current }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Filtrer par langue")
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), currentIdx) { dlg, idx ->
                val newLang = options[idx].second
                if (newLang != current) {
                    com.streamflixreborn.streamflix.utils.GenreFilter.setLang(provider.name, newLang)
                    com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                    Toast.makeText(
                        applicationContext,
                        if (newLang != null) "Langue : ${options[idx].first}" else "Langue : toutes",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dlg.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
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
            // v62 (user "au lieu d'un message, recharge la source automatiquement") :
            //   classificationCache vide = source pas encore chargée dans cette
            //   session. On lance le load en background, on affiche un dialog de
            //   progression (alimenté par MyIptvProvider.loadingStatus), puis on
            //   re-ouvre le picker une fois prêt.
            triggerSourceReloadAndShowPicker(menuItemId, type)
            return
        }
        val totalCount = categoriesWithCount.sumOf { it.second }
        // 2026-05-13 (user "traduit l'anglais des catégories") : applique
        // prettyCategoryName() pour traduire les tags iptv-org (General →
        // Général, News → Actualités, etc.). Les noms RAW restent utilisés
        // en interne comme valeur de filtre.
        // 2026-05-13 (user "CHARGE MAL[E]") : étiquette "Toutes les catégories"
        // sans "FR" — le picker liste TOUTES les catégories (ARABIC, ALBANIA,
        // FRANCE HD, etc.), pas que les françaises. Le filtre langue FR est
        // géré séparément via le bouton Pays/langue.
        val displayItems = arrayOf("Toutes les catégories ($totalCount)") +
            categoriesWithCount.map { (name, count) ->
                "${provider.prettyCategoryName(name)}  ($count)"
            }.toTypedArray()
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
                // 2026-05-13 (user "quand on clique sur une catégorie il faut
                // que le Home change") : invalide le HomeCacheStore (sinon
                // TTL 5 min skip le refresh réseau et le filtre n'est pas
                // appliqué visuellement).
                com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                    applicationContext,
                    provider,
                )
                // 2026-05-14 (user "tu cliques une fois il se passe rien") :
                // toast immédiat + notif flow. Sans le toast, l'user pense que
                // son click n'est pas pris en compte pendant les 2-3s de fetch.
                android.widget.Toast.makeText(
                    this,
                    "Chargement…",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** v62 : charge la(les) source(s) IPTV en background puis re-ouvre le
     *  picker catégories dès que la classification est prête. Évite le
     *  message "recharge la source d'abord" en faisant le job automatiquement. */
    private fun triggerSourceReloadAndShowPicker(
        menuItemId: Int,
        type: com.streamflixreborn.streamflix.utils.IptvClassifier.ContentType,
    ) {
        val provider = com.streamflixreborn.streamflix.providers.MyIptvProvider
        val lastSourceId = getSharedPreferences("iptv_last_source", MODE_PRIVATE)
            .getString("last_id", null)
        // Dialog de progression
        val progressView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(android.widget.ProgressBar(this@MainTvActivity).apply {
                isIndeterminate = true
            })
            addView(android.widget.TextView(this@MainTvActivity).apply {
                id = android.R.id.text1
                text = "Chargement…"
                setPadding(0, 32, 0, 0)
                textSize = 14f
            })
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Préparation des catégories")
            .setView(progressView)
            .setCancelable(true)
            .create()
        dialog.show()
        val statusView = progressView.findViewById<android.widget.TextView>(android.R.id.text1)
        // Observe loadingStatus → update text
        val statusJob = lifecycleScope.launch {
            provider.loadingStatus.collect { status ->
                if (status.isNotEmpty() && dialog.isShowing) {
                    statusView.text = status
                }
            }
        }
        // Lance le load et re-ouvre le picker
        lifecycleScope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    provider.countByTypeFR(lastSourceId)
                }
            }
            statusJob.cancel()
            provider.loadingStatus.value = ""
            if (dialog.isShowing) dialog.dismiss()
            // Re-tente d'ouvrir le picker maintenant que le cache est rempli
            val refreshed = provider.availableCategoriesWithCount(type)
            if (refreshed.isEmpty()) {
                Toast.makeText(
                    this@MainTvActivity,
                    "Source vide ou inaccessible — vérifie tes sources IPTV.",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                showIptvCategoryPicker(menuItemId)
            }
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
            binding.navMain.menu.findItem(R.id.iptv_categories_menu)?.isVisible = false
            binding.navMain.menu.findItem(R.id.iptv_language_menu)?.isVisible = false
            binding.navMain.menu.findItem(R.id.catalog_filter_menu)?.isVisible = false
            return
        }
        // Provider sélectionné : restaure la visibilité des onglets pertinents.
        val isIptvForHome = provider is com.streamflixreborn.streamflix.providers.IptvProvider
        binding.navMain.menu.findItem(R.id.home)?.apply {
            isVisible = true
            // 2026-05-14 (user "les logos à gauche ne correspondent pas du tout
            // au provider") : pour les providers NON-IPTV (animes, films, séries),
            // l'onglet Home affiche "Accueil" + icône maison plutôt que "TV" +
            // icône télé (qui ne correspond qu'aux IPTV).
            if (isIptvForHome) {
                setTitle(R.string.main_menu_home)  // "TV" en FR
                setIcon(R.drawable.ic_menu_tv)
            } else {
                title = "Accueil"
                setIcon(R.drawable.ic_menu_home)
            }
        }
        binding.navMain.menu.findItem(R.id.search)?.isVisible = true
        // Anime providers : titles/icons spécifiques (Films→Séries VOSTFR, etc.)
        val animeOnlyProviders = setOf(
            "VoirDrama", "VoirAnime", "FrenchAnime", "AnimeSama", "FrenchManga",
        )
        val isAnimeOnly = provider.name in animeOnlyProviders
        binding.navMain.menu.findItem(R.id.movies)?.apply {
            isVisible = Provider.supportsMovies(provider)
            title = if (isAnimeOnly) {
                getString(R.string.main_menu_series_fr)
            } else {
                getString(R.string.main_menu_movies)
            }
            setIcon(if (isAnimeOnly) R.drawable.ic_menu_tv else R.drawable.ic_menu_movie)
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
                isAnimeOnly ->
                    getString(R.string.main_menu_series)  // "VOSTFR" (anime providers VF/VOSTFR split)
                // 2026-05-14 (user "il est censé s'appeler série et il a pas
                // icone qui correspond") : DessinAnime/AnimeSite affichent "Série"
                // (singulier, comme main_menu_series_tab) + icône ic_menu_series
                // pour matcher le nom. Pas "VOSTFR" (label langue), pas "Séries TV"
                // (sonne IPTV).
                provider.name == "DessinAnime" ->
                    getString(R.string.main_menu_series_tab)
                else -> getString(R.string.main_menu_tv_shows)
            }
            // Force l'icône série (ic_menu_series) car le default est ic_menu_tv qui collide avec TV
            if (isMyIptv || provider.name == "DessinAnime") {
                setIcon(R.drawable.ic_menu_series)
            }
        }
        // Favoris tab — IPTV providers only.
        binding.navMain.menu.findItem(R.id.iptv_favorites)?.isVisible = isIptv
        // 2026-05-13 (user "non dans la bar de gauche y a la place") : items
        // IPTV (catégories + filtre langue) visibles dans la sidebar quand
        // MyIptv ou Vavoo est actif. Catégories seulement pour MyIptv (Vavoo
        // n'a pas de catégories user-pickable).
        val isVavoo = provider is com.streamflixreborn.streamflix.providers.VavooProvider
        binding.navMain.menu.findItem(R.id.iptv_categories_menu)?.isVisible = isMyIptv
        binding.navMain.menu.findItem(R.id.iptv_language_menu)?.isVisible = isMyIptv || isVavoo
        // 2026-05-21 : filtre catalogue (sous Paramètres) uniquement pour les
        //   providers TMDB compatibles (Cloudstream).
        binding.navMain.menu.findItem(R.id.catalog_filter_menu)?.isVisible =
            com.streamflixreborn.streamflix.utils.CatalogFilter.isSupported(provider.name)
        // 2026-05-26 : filtre genre TMDB = re-clic sur Films/Séries
        // (pas d'item sidebar, géré par setOnItemReselectedListener)
    }

    /** 2026-05-13 : picker pays Vavoo (item sidebar). */
    fun showVavooCountryPicker() {
        Log.d("MainTv", "showVavooCountryPicker called")
        val current = com.streamflixreborn.streamflix.providers.VavooCountrySettings.getCurrent(this)
        val list = com.streamflixreborn.streamflix.providers.VavooCountrySettings.list
        val items = list.map {
            "${it.flag} ${it.label}${if (it.code == current.code) "  ✓" else ""}"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Pays Vavoo")
            .setItems(items) { _, idx ->
                val picked = list[idx]
                if (picked.code == current.code) return@setItems
                com.streamflixreborn.streamflix.providers.VavooCountrySettings.setCurrent(this, picked)
                com.streamflixreborn.streamflix.providers.VavooProvider.setCountryFilter(picked.filterValue)
                // Force aussi le clear du cache home disque (sinon le home
                // affiche les anciennes chaînes du pays précédent).
                kotlin.runCatching {
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                        this,
                        com.streamflixreborn.streamflix.providers.VavooProvider,
                    )
                }
                android.widget.Toast.makeText(
                    this,
                    "Vavoo : ${picked.flag} ${picked.label} — chargement…",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                // Notifie le home pour qu'il reload.
                kotlin.runCatching {
                    com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                }
            }
            .setNeutralButton("Miroir") { _, _ -> showVavooMirrorPicker() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** 2026-05-28 : picker miroir Vavoo (TV). */
    fun showVavooMirrorPicker() {
        val current = com.streamflixreborn.streamflix.providers.VavooMirrorSettings.getCurrent(this)
        val mirrors = com.streamflixreborn.streamflix.providers.VavooMirrorSettings.list
        val items = mirrors.map {
            "${it.label}${if (it.url == current.url) "  ✓" else ""}"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Miroir Vavoo")
            .setItems(items) { _, idx ->
                val picked = mirrors[idx]
                if (picked.url == current.url) return@setItems
                com.streamflixreborn.streamflix.providers.VavooMirrorSettings.setCurrent(this, picked)
                kotlin.runCatching {
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                        this,
                        com.streamflixreborn.streamflix.providers.VavooProvider,
                    )
                }
                android.widget.Toast.makeText(
                    this,
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
