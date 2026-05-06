package com.streamflixreborn.streamflix.activities.main

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.setupWithNavController
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.tools.BypassWebViewActivity
import com.streamflixreborn.streamflix.databinding.ActivityMainMobileBinding
import com.streamflixreborn.streamflix.fragments.player.PlayerMobileFragment
import com.streamflixreborn.streamflix.providers.WiflixProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.ui.UpdateAppMobileDialog
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Base64
import kotlin.coroutines.resume

class MainMobileActivity : FragmentActivity() {

    private companion object {
        const val RESOLVER_TIMEOUT_MS = 12_000L
        // True the first time the JVM/process starts. Static fields survive
        // activity restarts (FLAG_ACTIVITY_NEW_TASK|CLEAR_TASK from a provider
        // click) but reset on a true cold start (process kill).
        private var isFreshProcessLaunch = true
    }

    private data class ResolverPayload(
        val url: String,
    )

    private var _binding: ActivityMainMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()
    private val resolverWebSocketClient by lazy { NetworkClient.default }
    private val bypassWebViewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val wsUrl = pendingWs
            val token = pendingToken
            val cookies =
                result.data?.getStringExtra(BypassWebViewActivity.EXTRA_COOKIE_HEADER)?.trim()

            clearResolverState()

            if (result.resultCode != Activity.RESULT_OK || wsUrl.isNullOrBlank() || token.isNullOrBlank()) {
                return@registerForActivityResult
            }

            lifecycleScope.launch {
                sendWebSocketDone(wsUrl, token, cookies)
                showPostBypassCloseDialog()
            }
        }

    private var pendingWs: String? = null
    private var pendingToken: String? = null

    private var updateAppDialog: UpdateAppMobileDialog? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.mobileThemeRes(UserPreferences.selectedTheme))

        super.onCreate(savedInstanceState)

        WiflixProvider.init(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar

        _binding = ActivityMainMobileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeNavigationChrome()

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val currentFragment = navHostFragment?.childFragmentManager?.primaryNavigationFragment

            val isPlayer = currentFragment is PlayerMobileFragment
            val isBottomNavVisible = binding.bnvMain.visibility == View.VISIBLE

            val bottomPadding = if (isPlayer || isBottomNavVisible) 0 else insets.bottom
            val topPadding = if (isPlayer) 0 else insets.top

            view.setPadding(insets.left, topPadding, insets.right, bottomPadding)
            windowInsets
        }


        updateImmersiveMode()

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as NavHostFragment
        val navController = navHost.navController

        if (BuildConfig.APP_LAYOUT == "tv" ||
            (BuildConfig.APP_LAYOUT != "mobile" &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        ) {
            finish()
            startActivity(Intent(this, MainTvActivity::class.java))
            return
        }

        // Per user request: cold start lands on the Providers home with NO
        // active provider. This avoids auto-loading a memory-heavy provider
        // (which can crash low-RAM devices) and forces the user to consciously
        // pick which category/provider they want to use today.
        if (savedInstanceState == null && isFreshProcessLaunch) {
            // First activity in this OS process → wipe selection so we land on
            // the Providers home (avoids auto-loading a memory-heavy provider
            // that crashes low-RAM devices). The static flag survives activity
            // restarts (provider clicks re-launch with FLAG_ACTIVITY_NEW_TASK
            // |CLEAR_TASK) but is reset by the JVM on a real cold start.
            UserPreferences.currentProvider = null
            isFreshProcessLaunch = false
            // Stay on R.id.providers (the start destination of the nav graph).
        } else if (savedInstanceState == null) {
            // Activity restart inside the same process — typically triggered by
            // a provider click (ProviderViewHolder restarts MainMobileActivity
            // with FLAG_ACTIVITY_NEW_TASK|CLEAR_TASK after setting
            // currentProvider). If a provider is now selected, jump to home so
            // the user actually lands on the provider they picked.
            isFreshProcessLaunch = false
            if (UserPreferences.currentProvider != null) {
                navController.navigate(R.id.home)
            }
        }

        viewModel.checkUpdate()

        binding.bnvMain.setupWithNavController(navController)
        updateNavigationVisibility()
        updateBottomNavigationVisibility(navController.currentDestination?.id)

        var previousDestinationId: Int? = null
        navController.addOnDestinationChangedListener { _, destination, _ ->
            // Clear Glide memory cache when switching between main tabs to free memory
            if (previousDestinationId != null && previousDestinationId != destination.id
                && isTopLevelProviderDestination(previousDestinationId)
                && isTopLevelProviderDestination(destination.id)) {
                CacheUtils.clearMemoryCache(this)
            }
            previousDestinationId = destination.id

            updateNavigationVisibility(destination.id)
            updateBottomNavigationVisibility(destination.id)
            binding.mainContent.post { binding.mainContent.requestApplyInsets() }
        }

        lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect {
                    updateNavigationVisibility(navController.currentDestination?.id)
                }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        if (!viewModel.updateDismissed) showUpdateDialog(state)
                    }

                    MainViewModel.State.DownloadingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainMobileActivity, state.apk)
                        dismissUpdateDialog()
                    }

                    MainViewModel.State.InstallingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        updateAppDialog?.isLoading = false
                        Toast.makeText(
                            this@MainMobileActivity,
                            state.error.message ?: "Update failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    else -> {}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val handled =
                    (getCurrentFragment() as? PlayerMobileFragment)?.onBackPressed() ?: false
                if (handled) return

                val currentDestinationId = navController.currentDestination?.id

                if (currentDestinationId == R.id.settings) {
                    navigateToProviderHome(navController)
                    return
                }

                if (UserPreferences.currentProvider != null && currentDestinationId == R.id.home) {
                    // Back from a provider's home = return to the Providers
                    // selection screen (so the user can pick a different
                    // provider) instead of exiting to the phone launcher.
                    UserPreferences.currentProvider = null
                    navController.navigate(
                        R.id.providers,
                        null,
                        navOptions {
                            launchSingleTop = true
                            popUpTo(R.id.home) { inclusive = true }
                        }
                    )
                    return
                }

                if (UserPreferences.currentProvider != null &&
                    isTopLevelProviderDestination(currentDestinationId)
                ) {
                    navigateToProviderHome(navController)
                    return
                }

                if (!navController.navigateUp()) finish()
            }
        })

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        dismissUpdateDialog()
        _binding = null
        super.onDestroy()
    }

    private fun clearResolverState() {
        pendingWs = null
        pendingToken = null
    }

    private fun showUpdateDialog(state: MainViewModel.State.SuccessCheckingUpdate) {
        if (isFinishing || isDestroyed) return

        dismissUpdateDialog()
        updateAppDialog = UpdateAppMobileDialog(this, state.newReleases).also { dialog ->
            dialog.setOnUpdateClickListener {
                if (!dialog.isLoading) {
                    viewModel.downloadUpdate(this@MainMobileActivity, state.asset)
                }
            }
            dialog.setOnCancelClickListener {
                viewModel.updateDismissed = true
            }
            dialog.show()
        }
    }

    private fun dismissUpdateDialog() {
        updateAppDialog?.takeIf { it.isShowing }?.dismiss()
        updateAppDialog = null
    }

    private fun updateBottomNavigationVisibility(destinationId: Int?) {
        val showBottomNav =
            UserPreferences.currentProvider != null && isTopLevelProviderDestination(destinationId)
        binding.bnvMain.visibility = if (showBottomNav) View.VISIBLE else View.GONE
    }

    private fun updateNavigationVisibility(currentDestinationId: Int? = null) {
        val provider = UserPreferences.currentProvider ?: return
        val supportsMovies = Provider.supportsMovies(provider)
        val supportsTvShows = Provider.supportsTvShows(provider)

        binding.bnvMain.menu.findItem(R.id.movies)?.apply {
            isVisible = supportsMovies
            title = when (provider.name) {
                "VoirDrama", "VoirAnime", "FrenchAnime", "AnimeSama", "FrenchManga" -> getString(R.string.main_menu_series_fr)
                else -> getString(R.string.main_menu_movies)
            }
            if (provider.name in listOf("VoirDrama", "VoirAnime", "FrenchAnime", "AnimeSama", "FrenchManga")) {
                setIcon(R.drawable.ic_menu_tv)
            }
        }
        val isIptv = provider is com.streamflixreborn.streamflix.providers.IptvProvider
        binding.bnvMain.menu.findItem(R.id.tv_shows)?.apply {
            isVisible = supportsTvShows
            // IPTV providers (WiTv, OlaTv, …) show "Toutes les chaînes" instead of "Séries TV"
            // — covers any provider implementing IptvProvider, no need to maintain a list.
            title = when {
                isIptv || provider.name in setOf("CableVisionHD", "TvporinternetHD") ->
                    getString(R.string.main_menu_all_channels)
                provider.name in setOf("VoirDrama", "VoirAnime", "FrenchAnime", "AnimeSama", "FrenchManga") ->
                    getString(R.string.main_menu_series)
                else -> getString(R.string.main_menu_tv_shows)
            }
        }
        // Favoris tab — IPTV providers only. Sits between "Toutes les chaînes" and "Paramètres".
        binding.bnvMain.menu.findItem(R.id.iptv_favorites)?.isVisible = isIptv

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        val navController = navHost?.navController ?: return
        when {
            currentDestinationId == R.id.movies && !supportsMovies -> {
                navController.navigate(R.id.tv_shows)
            }

            currentDestinationId == R.id.tv_shows && !supportsTvShows -> {
                navController.navigate(R.id.home)
            }
        }
    }

    private fun isTopLevelProviderDestination(destinationId: Int?): Boolean {
        return destinationId in setOf(
            R.id.search,
            R.id.home,
            R.id.movies,
            R.id.tv_shows,
            R.id.iptv_favorites,
            R.id.settings,
        )
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

    private fun closeTask() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finishAffinity()
        }
    }

    private suspend fun requestResolverPayload(wsUrl: String, token: String): ResolverPayload? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(RESOLVER_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val request = Request.Builder()
                        .url(wsUrl)
                        .build()

                    val socket =
                        resolverWebSocketClient.newWebSocket(request, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                Log.d("ResolverWS", "Connected -> requesting URL")
                                webSocket.send("resolve:$token")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                when {
                                    text.startsWith("payload:") -> {
                                        val payload = text.substringAfter("payload:").trim()
                                        val parsed = runCatching {
                                            val json = JSONObject(payload)
                                            ResolverPayload(
                                                url = json.optString("url"),
                                            )
                                        }.getOrNull()

                                        if (continuation.isActive) {
                                            continuation.resume(
                                                parsed?.takeUnless {
                                                    it.url.isBlank() || it.url.equals("null", ignoreCase = true)
                                                }
                                            )
                                        }
                                        webSocket.close(1000, null)
                                    }

                                    text.startsWith("url:") -> {
                                        val url = text.substringAfter("url:").trim()
                                        if (continuation.isActive) {
                                            continuation.resume(
                                                url.takeUnless {
                                                    it.isEmpty() || it.equals("null", ignoreCase = true)
                                                }?.let { ResolverPayload(url = it) }
                                            )
                                        }
                                        webSocket.close(1000, null)
                                    }

                                    text.startsWith("error:") -> {
                                        Log.e("ResolverWS", "Resolver returned error: $text")
                                        if (continuation.isActive) {
                                            continuation.resume(null)
                                        }
                                        webSocket.close(1000, null)
                                    }
                                }
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                if (!continuation.isActive) {
                                    Log.d("ResolverWS", "WS resolve cancelled or timed out")
                                    return
                                }
                                Log.e("ResolverWS", "WS resolve failed", t)
                                continuation.resume(null)
                            }
                        })

                    continuation.invokeOnCancellation {
                        socket.cancel()
                    }
                }
            }
        }

    private suspend fun sendWebSocketDone(wsUrl: String, token: String, cookies: String?) {
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(RESOLVER_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val request = Request.Builder()
                        .url(wsUrl)
                        .build()

                    val socket =
                        resolverWebSocketClient.newWebSocket(request, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                Log.d("ResolverWS", "Connected -> sending DONE")
                                val encodedCookies = cookies
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        Base64.getEncoder().encodeToString(
                                            it.toByteArray(Charsets.UTF_8)
                                        )
                                    }
                                val message = if (encodedCookies.isNullOrBlank()) {
                                    "done:$token"
                                } else {
                                    "done:$token:$encodedCookies"
                                }
                                webSocket.send(message)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (text == "ack:$token" && continuation.isActive) {
                                    continuation.resume(Unit)
                                    webSocket.close(1000, null)
                                }
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                if (!continuation.isActive) {
                                    Log.d("ResolverWS", "WS done cancelled or timed out")
                                    return
                                }
                                Log.e("ResolverWS", "WS failed", t)
                                continuation.resume(Unit)
                            }
                        })

                    continuation.invokeOnCancellation {
                        socket.cancel()
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent): Boolean {
        val data = intent.data ?: return false

        if (data.scheme == "streamflix" && data.host == "resolve") {
            val ws = data.getQueryParameter("ws") ?: return false
            val token = data.getQueryParameter("token") ?: return false

            Log.d("ResolverWS", "WS: $ws")

            resolve(ws, token)
            return true
        }

        return false
    }

    private fun resolve(ws: String, token: String) {
        pendingWs = ws
        pendingToken = token

        lifecycleScope.launch {
            val payload = requestResolverPayload(ws, token)
            if (payload == null) {
                showResolverConnectionErrorDialog(ws, token)
                return@launch
            }

            bypassWebViewLauncher.launch(
                Intent(this@MainMobileActivity, BypassWebViewActivity::class.java)
                    .putExtra(BypassWebViewActivity.EXTRA_URL, payload.url)
            )
        }
    }

    private fun showResolverConnectionErrorDialog(ws: String, token: String) {
        if (isFinishing || isDestroyed) return

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage("Unable to reach the TV bypass websocket. Retry?")
            .setPositiveButton("Retry") { _, _ ->
                resolve(ws, token)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                clearResolverState()
            }
            .setOnCancelListener {
                clearResolverState()
            }
            .show()
    }

    private fun showPostBypassCloseDialog() {
        if (isFinishing || isDestroyed) return

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage("Bypass completed. Do you want to close the app?")
            .setPositiveButton("Close app") { _, _ ->
                closeTask()
            }
            .setNegativeButton("Keep open", null)
            .setOnCancelListener(null)
            .show()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        (getCurrentFragment() as? PlayerMobileFragment)?.onUserLeaveHint()
    }

    fun updateImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (UserPreferences.immersiveMode) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyThemeNavigationChrome() {
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        val navColors = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(
                palette.mobileNavActive,
                palette.mobileNavInactive,
            )
        )

        binding.bnvMain.setBackgroundColor(palette.mobileNavBackground)
        binding.bnvMain.itemIconTintList = navColors
        binding.bnvMain.itemTextColor = navColors

        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
