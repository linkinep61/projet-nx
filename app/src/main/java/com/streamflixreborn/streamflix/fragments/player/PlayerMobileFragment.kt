package com.streamflixreborn.streamflix.fragments.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.SubtitleView
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.tools.BypassWebViewActivity
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.ContentExoControllerMobileBinding
import com.streamflixreborn.streamflix.databinding.FragmentPlayerMobileBinding
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.ui.PlayerMobileView
import com.streamflixreborn.streamflix.utils.MediaServer
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.UserDataCache
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.getFileName
import com.streamflixreborn.streamflix.utils.next
import com.streamflixreborn.streamflix.utils.plus
import com.streamflixreborn.streamflix.utils.setMediaServerId
import com.streamflixreborn.streamflix.utils.setMediaServers
import com.streamflixreborn.streamflix.utils.toSubtitleMimeType
import com.streamflixreborn.streamflix.providers.WiTvProvider
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import androidx.core.net.toUri
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import org.chromium.net.CronetEngine
import com.google.android.gms.net.CronetProviderInstaller
import com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
import java.util.Base64 
import java.io.File
import java.io.FileOutputStream
import android.graphics.Color
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.navigation.NavOptions
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.PlayerGestureHelper
import com.streamflixreborn.streamflix.utils.UserDataCache.toEpisode
import com.streamflixreborn.streamflix.utils.UserDataCache.toMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
// Removed: import okhttp3.internal.userAgent — it resolves to "okhttp/4.12.0"
import java.util.Locale

class PlayerMobileFragment : Fragment() {
    companion object {
        private const val NEXT_EPISODE_PREFETCH_THRESHOLD_MS = 60_000L
        private const val NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS = 30_000L

        /** Ad / tracking / popup domains blocked in DaddyLive WebView embeds */
        private val AD_BLOCK_PATTERNS = listOf(
            "doubleclick", "googlesyndication", "googleadservices",
            "adservice.google", "pagead2.googlesyndication",
            "trafficjunky", "exoclick", "juicyads", "clickadu",
            "popads", "popcash", "propellerads", "adsterra",
            "hilltopads", "richads", "pushground", "a-ads",
            "ad-maven", "admaven", "revcontent", "mgid",
            "taboola", "outbrain", "criteo", "amazon-adsystem",
            "bidswitch", "openx", "pubmatic", "rubiconproject",
            "spotxchange", "smartadserver",
            "betrad", "bluekai", "bongacams", "chaturbate",
            "livejasmin", "stripchat", "cam4",
            "pushwoosh", "onesignal", "pushengage",
            "notify", "notix", "gravitec",
            "acdn.adnxs", "adnxs.com", "adsrvr.org",
            "serving-sys.com", "zedo.com", "yieldmanager",
            "disqusads", "revdeepak", "pushance",
        )

        /** JS injected into DaddyLive embeds to kill popup ads and overlays */
        private const val DADDYLIVE_AD_KILL_JS = """
            (function(){
                // 1. Kill window.open (popup ads)
                window.open = function(){ return null; };

                // 2. Kill alert/confirm/prompt (annoying dialogs)
                window.alert = function(){};
                window.confirm = function(){ return false; };
                window.prompt = function(){ return null; };

                // 3. Periodic DOM cleanup — remove popup/overlay elements
                function killAds() {
                    // Remove elements with high z-index that overlay the video
                    document.querySelectorAll('div,iframe,section,aside').forEach(function(el){
                        var s = getComputedStyle(el);
                        var z = parseInt(s.zIndex) || 0;
                        var pos = s.position;
                        // Skip the video container / player
                        if (el.querySelector('video') || el.closest('video')) return;
                        if (el.id && (el.id.includes('player') || el.id.includes('video'))) return;
                        if (el.className && typeof el.className === 'string'
                            && (el.className.includes('player') || el.className.includes('video')
                                || el.className.includes('hls'))) return;
                        // Kill fixed/absolute overlays with high z-index
                        if ((pos === 'fixed' || pos === 'absolute') && z > 100) {
                            el.remove();
                        }
                    });
                    // Remove iframes that are NOT the player
                    document.querySelectorAll('iframe').forEach(function(f){
                        var src = f.src || '';
                        if (!src.includes('bolaloca') && !src.includes('player')
                            && !src.includes('embed') && src.length > 0) {
                            f.remove();
                        }
                    });
                }
                killAds();
                setInterval(killAds, 2000);

                // 4. Inject CSS to hide common ad patterns
                var css = document.createElement('style');
                css.textContent = [
                    '[id*="ad-"],[id*="ad_"],[class*="ad-overlay"]',
                    ',[class*="popup"],[class*="modal"],[class*="interstitial"]',
                    ',[id*="popup"],[id*="modal"]',
                    '{ display:none !important; }'
                ].join('');
                document.head.appendChild(css);
            })();
        """
    }

    private var _binding: FragmentPlayerMobileBinding? = null
    private val binding get() = _binding!!
    private var isSetupDone = false

    private val PlayerControlView.binding
        get() = ContentExoControllerMobileBinding.bind(this.findViewById(R.id.cl_exo_controller))

    private val args by navArgs<PlayerMobileFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { PlayerViewModel(args.videoType, args.id) }

    private lateinit var player: ExoPlayer
    private lateinit var httpDataSource: HttpDataSource.Factory
    private lateinit var dataSourceFactory: DataSource.Factory
    private lateinit var mediaSession: MediaSession
    private lateinit var progressHandler: android.os.Handler
    private lateinit var progressRunnable: Runnable
    private lateinit var gestureHelper: PlayerGestureHelper

    private var servers = listOf<Video.Server>()
    private var zoomToast: Toast? = null

    private var currentVideo: Video? = null
    private var currentServer: Video.Server? = null
    private var usingCronet = false
    private var usingDoH = false
    private var usingBrowserOkHttp = false
    private var usingWebView = false

    // ── WebView overlay (Netu anti-bot bypass — touch-friendly for mobile) ──
    private var webViewOverlay: FrameLayout? = null
    private var overlayWebView: WebView? = null
    private var pendingWebViewVideo: Video? = null
    private var pendingWebViewServer: Video.Server? = null
    @Volatile private var m3u8Intercepted = false
    /** CDN iframe page URL captured from shouldInterceptRequest — needed to establish the CDN session */
    @Volatile private var daddyLiveCdnPageUrl: String? = null

    /** Hidden WebView on file:/// for DaddyLive WebViewDataSource (Chrome TLS + no CORS) */
    private var daddyLiveProxyWebView: WebView? = null

    /** Cached CronetEngine using Play Services' Chrome TLS stack */
    private var cronetEngine: CronetEngine? = null
    /** Shared bounded executor for Cronet — avoids unbounded newCachedThreadPool */
    private val cronetExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)
    private var isIgnoringPip = false
    private var waitingForBypass = false
    private var bypassDone = false
    private var nextEpisodePrefetchTargetId: String? = null
    private var nextEpisodePrefetchJob: Job? = null
    private var nextEpisodeOverlayDismissed = false

    private val bypassWebViewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cookies =
                result.data?.getStringExtra(BypassWebViewActivity.EXTRA_COOKIE_HEADER)?.trim()

            if (result.resultCode != android.app.Activity.RESULT_OK || cookies.isNullOrBlank()) {
                waitingForBypass = false
                return@registerForActivityResult
            }

            val bypassUrl = servers.firstOrNull { isSerienStreamBypassUrl(it.id) }?.id
            if (bypassUrl.isNullOrBlank()) {
                waitingForBypass = false
                return@registerForActivityResult
            }

            applyBypassCookies(bypassUrl, cookies)
            waitingForBypass = false
            bypassDone = true

            lifecycleScope.launch {
                delay(300)
                viewModel.reloadServersAfterBypass()
            }
        }

    private val chooserReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val clickedComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, android.content.ComponentName::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
                }
                Log.i("ExternalPlayer", "Mobile - App selezionata: ${clickedComponent?.packageName ?: "Sconosciuta"}")
            }
        }
    }

    private val pickLocalSubtitle = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val fileName = uri.getFileName(requireContext()) ?: uri.toString()

        val currentPosition = player.currentPosition
        val currentSubtitleConfigurations =
            player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                MediaItem.SubtitleConfiguration.Builder(it.uri)
                    .setMimeType(it.mimeType)
                    .setLabel(it.label)
                    .setLanguage(it.language)
                    .setSelectionFlags(0)
                    .build()
            } ?: listOf()
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                .setSubtitleConfigurations(
                    currentSubtitleConfigurations
                            + MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(fileName.toSubtitleMimeType())
                        .setLabel(fileName)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
                .setMediaMetadata(player.mediaMetadata)
                .build()
        )
        player.seekTo(currentPosition)
        player.play()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (!isSetupDone) {
            requireActivity().requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            
            val window = requireActivity().window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            isSetupDone = true
        }
        isIgnoringPip = false
        if (::player.isInitialized) {
            binding.pvPlayer.useController = true
            // Resume playback after returning from bypass or any pause
            if (!player.isPlaying) {
                player.play()
            }
        }
        
        try {
            val filter = IntentFilter("ACTION_PLAYER_CHOSEN")
            ContextCompat.registerReceiver(
                requireContext(),
                chooserReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (ignored: Exception) {}
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Pre-install Play Services Cronet provider asynchronously so it's ready
        // by the time a LuluVdo/vidzy video needs Chrome's TLS stack
        initCronetEngine()
        initializePlayer(false)
        initializeVideo()
        gestureHelper = PlayerGestureHelper(
            requireContext(), 
            binding.pvPlayer, 
            binding.llBrightness, 
            binding.pbBrightness, 
            binding.tvBrightnessPercentage,
            binding.llVolume, 
            binding.pbVolume, 
            binding.tvVolumePercentage
        )

        // Stato Video
        viewLifecycleOwner.lifecycleScope.launch { 
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED).collect { state ->
                when (state) {
                    PlayerViewModel.State.LoadingServers -> {}
                    is PlayerViewModel.State.SuccessLoadingServers -> {
                        servers = state.servers
                        val sToServer = servers.firstOrNull {
                            isSerienStreamBypassUrl(it.id)
                        }

                        if (sToServer != null && !waitingForBypass && !bypassDone) {
                            val bypassUrl = buildSerienStreamBypassUrl()
                            if (bypassUrl.isNullOrBlank()) {
                                waitingForBypass = false
                                Toast.makeText(requireContext(), "Unable to open s.to bypass page.", Toast.LENGTH_SHORT).show()
                                return@collect
                            }

                            waitingForBypass = true
                            bypassWebViewLauncher.launch(
                                Intent(requireContext(), BypassWebViewActivity::class.java)
                                    .putExtra(BypassWebViewActivity.EXTRA_URL, bypassUrl)
                            )
                        } else {
                            val providerName = UserPreferences.currentProvider?.name ?: ""
                            val isTmdb = providerName.contains("TMDb", ignoreCase = true)

                            if (servers.isEmpty()) {
                                // For WiTV provider, don't exit — OLA CID servers may arrive progressively
                                if (providerName == "WiTV") {
                                    Log.d("PlayerMobileFragment", "No initial servers, waiting for OLA CID servers...")
                                    PlayerSettingsView.Settings.ChannelVariant.list.clear()
                                    binding.settings.refreshChannelVariantList()
                                    return@collect
                                }
                                val message = if (isTmdb) {
                                    val langCode = providerName.substringAfter("(").substringBefore(")")
                                    val locale = Locale.forLanguageTag(langCode)
                                    val langDisplayName = locale.getDisplayLanguage(Locale.getDefault())
                                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                                    getString(R.string.player_not_available_lang_message, langDisplayName)
                                } else {
                                    "No servers found for this content."
                                }
                                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                                findNavController().navigateUp()
                                return@collect
                            }

                            player.playlistMetadata = MediaMetadata.Builder()
                                .setTitle(state.toString())
                                .setMediaServers(state.servers.map {
                                    MediaServer(
                                        id = it.id,
                                        name = it.name,
                                    )
                                })
                                .build()
                            binding.settings.setOnServerSelectedListener { server ->
                                viewModel.getVideo(state.servers.find { server.id == it.id }!!)
                            }

                            // Chaîne starts empty — clear old entries from previous channel
                            PlayerSettingsView.Settings.ChannelVariant.list.clear()
                            binding.settings.refreshChannelVariantList()

                            viewModel.getVideo(state.servers.first())
                        }

                    }

                    is PlayerViewModel.State.FailedLoadingServers -> {
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_LONG
                        ).show()
                        findNavController().navigateUp()
                    }

                    is PlayerViewModel.State.LoadingVideo -> {
                        // Don't set a MediaItem with empty URI — it causes
                        // FileNotFoundException and puts the player in ERROR state
                        // before extraction finishes. displayVideo() will set the
                        // real MediaItem when SuccessLoadingVideo arrives.
                    }

                    is PlayerViewModel.State.SuccessLoadingVideo -> {
                        // Channel works — unmark as failed if it was
                        UserPreferences.unmarkChannelFailed(args.id)
                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)
                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)
                        displayVideo(state.video, state.server)
                    }

                    is PlayerViewModel.State.FailedLoadingVideo -> {
                        val nextServer = servers.getOrNull(servers.indexOf(state.server) + 1)
                        if (nextServer != null) {
                            viewModel.getVideo(nextServer)
                        } else if (tryNextChannelVariant(state.server)) {
                            // OLA channel variant fallback succeeded — playing next variant
                        } else {
                            // Mark channel as failed for WiTV so it's hidden next time
                            val provider = UserPreferences.currentProvider
                            if (provider is com.streamflixreborn.streamflix.providers.WiTvProvider) {
                                UserPreferences.markChannelFailed(args.id)
                            }

                            val providerName = provider?.name ?: ""
                            val isTmdb = providerName.contains("TMDb", ignoreCase = true)

                            val message = if (isTmdb) {
                                val langCode = providerName.substringAfter("(").substringBefore(")")
                                val locale = Locale.forLanguageTag(langCode)
                                val langDisplayName = locale.getDisplayLanguage(Locale.getDefault())
                                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                                getString(R.string.player_not_available_lang_message, langDisplayName)
                            } else {
                                "All servers failed to load the video."
                            }

                            Toast.makeText(
                                requireContext(),
                                message,
                                Toast.LENGTH_LONG
                            ).show()
                            findNavController().navigateUp()
                        }
                    }
                }
            }
        }

        // Progressive additional servers (OLA TV streams go to Chaîne, others to Serveurs)
        // Extract channel key from args.id (e.g. "ch::france2" → "france2")
        val currentChannelKey = args.id.removePrefix("ch::").removePrefix("sport::")

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.additionalServer.collect { server ->
                servers = servers + server

                if (server.name.startsWith("OLA[")) {
                    // Parse "OLA[key] label" format
                    val closeBracket = server.name.indexOf(']')
                    if (closeBracket < 0) return@collect
                    val olaKey = server.name.substring(4, closeBracket)
                    val label = server.name.substring(closeBracket + 2) // skip "] "

                    // Filter: ignore streams from a different channel (stale emissions)
                    if (olaKey != currentChannelKey) {
                        Log.d("PlayerMobileFragment", "Ignoring stale OLA stream: ${server.name} (expected key=$currentChannelKey)")
                        return@collect
                    }

                    // OLA TV stream → add to Chaîne page (max 3 per same name)
                    val sameNameCount = PlayerSettingsView.Settings.ChannelVariant.list.count { it.name == label }
                    if (sameNameCount < 3) {
                        PlayerSettingsView.Settings.ChannelVariant.list.add(
                            PlayerSettingsView.Settings.ChannelVariant(id = server.id, name = label)
                        )
                        if (PlayerSettingsView.Settings.ChannelVariant.list.size == 1) {
                            PlayerSettingsView.Settings.ChannelVariant.list.first().isSelected = true
                        }
                        binding.settings.refreshChannelVariantList()
                    }

                    // Set up variant click handler
                    binding.settings.setOnChannelVariantSelectedListener { variant ->
                        viewModel.getVideo(Video.Server(variant.id, variant.name))
                    }

                    // Auto-play first OLA stream if nothing else is playing
                    val hasNonOla = servers.any { !it.name.startsWith("OLA[") }
                    if (!hasNonOla && PlayerSettingsView.Settings.ChannelVariant.list.size == 1) {
                        Log.d("PlayerMobileFragment", "No non-OLA servers — auto-playing first OLA stream")
                        viewModel.getVideo(server)
                    }

                    Log.d("PlayerMobileFragment", "OLA stream added to Chaîne: $label")
                } else {
                    // Regular server → add to Serveurs page
                    player.playlistMetadata = MediaMetadata.Builder()
                        .setTitle(player.playlistMetadata?.title?.toString() ?: "")
                        .setMediaServers(servers.filter { !it.name.startsWith("OLA[") }.map {
                            MediaServer(id = it.id, name = it.name)
                        })
                        .build()
                    PlayerSettingsView.Settings.Server.list.add(
                        PlayerSettingsView.Settings.Server(id = server.id, name = server.name)
                    )
                    binding.settings.refreshServerList()
                    Log.d("PlayerMobileFragment", "Additional server added: ${server.name}")
                }

                // Update server selection listener (regular servers only)
                binding.settings.setOnServerSelectedListener { sel ->
                    servers.find { sel.id == it.id }?.let { viewModel.getVideo(it) }
                }
            }
        }

        // Stato Sottotitoli
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.subtitleState.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED).collect { state ->
                when (state) {
                    PlayerViewModel.SubtitleState.Loading -> {}
                    is PlayerViewModel.SubtitleState.SuccessOpenSubtitles -> {
                        binding.settings.openSubtitles = state.subtitles
                    }
                    is PlayerViewModel.SubtitleState.FailedOpenSubtitles -> {}

                    PlayerViewModel.SubtitleState.DownloadingOpenSubtitle -> {}
                    is PlayerViewModel.SubtitleState.SuccessDownloadingOpenSubtitle -> {
                        val fileName = state.uri.getFileName(requireContext()) ?: state.uri.toString()
                        val currentPosition = player.currentPosition
                        val currentSubtitleConfigurations = player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                            MediaItem.SubtitleConfiguration.Builder(it.uri)
                                .setMimeType(it.mimeType)
                                .setLabel(it.label)
                                .setLanguage(it.language)
                                .setSelectionFlags(0)
                                .build()
                        } ?: listOf()
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                .setSubtitleConfigurations(
                                    currentSubtitleConfigurations + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                        .setMimeType(fileName.toSubtitleMimeType())
                                        .setLabel(fileName)
                                        .setLanguage(state.subtitle.languageName)
                                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                        .build()
                                )
                                .setMediaMetadata(player.mediaMetadata)
                                .build()
                        )
                        UserPreferences.subtitleName = (state.subtitle.languageName ?: fileName).substringBefore(" ")
                        player.seekTo(currentPosition)
                        player.play()
                    }
                    is PlayerViewModel.SubtitleState.FailedDownloadingOpenSubtitle -> {
                        Toast.makeText(requireContext(), "${state.subtitle.subFileName}: ${state.error.message}", Toast.LENGTH_LONG).show()
                    }

                    is PlayerViewModel.SubtitleState.SuccessSubDLSubtitles -> {
                        binding.settings.subDLSubtitles = state.subtitles
                    }
                    is PlayerViewModel.SubtitleState.FailedSubDLSubtitles -> {}

                    PlayerViewModel.SubtitleState.DownloadingSubDLSubtitle -> {}
                    is PlayerViewModel.SubtitleState.SuccessDownloadingSubDLSubtitle -> {
                        val fileName = state.uri.getFileName(requireContext()) ?: state.uri.toString()
                        val currentPosition = player.currentPosition
                        val currentSubtitleConfigurations = player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                            MediaItem.SubtitleConfiguration.Builder(it.uri)
                                .setMimeType(it.mimeType)
                                .setLabel(it.label)
                                .setLanguage(it.language)
                                .setSelectionFlags(0)
                                .build()
                        } ?: listOf()
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                .setSubtitleConfigurations(
                                    currentSubtitleConfigurations + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                        .setMimeType(fileName.toSubtitleMimeType())
                                        .setLabel(state.subtitle.releaseName ?: state.subtitle.name ?: fileName)
                                        .setLanguage(state.subtitle.lang ?: state.subtitle.language ?: "Unknown")
                                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                        .build()
                                )
                                .setMediaMetadata(player.mediaMetadata)
                                .build()
                        )
                        UserPreferences.subtitleName = (state.subtitle.releaseName ?: state.subtitle.name ?: fileName).substringBefore(" ")
                        player.seekTo(currentPosition)
                        player.play()
                    }
                    is PlayerViewModel.SubtitleState.FailedDownloadingSubDLSubtitle -> {
                        Toast.makeText(requireContext(), "${state.subtitle.name}: ${state.error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.playPreviousOrNextEpisode.collect { nextEpisode ->
                    releasePlayer()
                    isSetupDone = false
                    val action = PlayerMobileFragmentDirections
                        .actionPlayerMobileFragmentSelf(
                            id = nextEpisode.id,
                            videoType = nextEpisode,
                            title = nextEpisode.tvShow.title,
                            subtitle = "S${nextEpisode.season.number} E${nextEpisode.number}  •  ${nextEpisode.title}"
                        )

                    hideNextEpisodeOverlay()
                    findNavController().navigate(
                        action,
                        NavOptions.Builder()
                            .setPopUpTo(
                                findNavController().currentDestination?.id ?: return@collect, true
                            )
                            .setLaunchSingleTop(false) 
                            .build()
                    )
                }
            }
        }


    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        binding.pvPlayer.useController = !isInPictureInPictureMode
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    fun onUserLeaveHint() {
        if (!isIgnoringPip && ::player.isInitialized && player.isPlaying) {
            enterPIPMode()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            player.pause()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        hideWebViewOverlay()
        nextEpisodePrefetchJob?.cancel()
        val window = requireActivity().window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        WindowCompat.getInsetsController(
            window,
            window.decorView
        ).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            show(WindowInsetsCompat.Type.systemBars())
        }
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        releasePlayer()
        try {
            requireContext().unregisterReceiver(chooserReceiver)
        } catch (ignored: Exception) {}
        _binding = null
        isSetupDone = false
    }

    fun onBackPressed(): Boolean = when {
        webViewOverlay != null -> {
            hideWebViewOverlay()
            true
        }
        binding.pvPlayer.isManualZoomEnabled -> {
            binding.pvPlayer.exitManualZoomMode()
            true
        }
        binding.settings.isVisible -> {
            binding.settings.onBackPressed()
        }
        else -> false
    }


    private fun initializeVideo() {
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        when (val type = args.videoType) {
            is Video.Type.Episode -> {
                nextEpisodeOverlayDismissed = false
                nextEpisodePrefetchTargetId = null
                if (EpisodeManager.listIsEmpty(type)) {
                    EpisodeManager.clearEpisodes()
                    lifecycleScope.launch(Dispatchers.IO) {
                        EpisodeManager.addEpisodesFromDb(type, database)
                        withContext(Dispatchers.Main) {
                            EpisodeManager.setCurrentEpisode(type)
                            updatePlayerHeader(type)
                            setupEpisodeNavigationButtons()
                            refreshEpisodeNavigation(type)
                        }
                    }
                } else {
                    EpisodeManager.setCurrentEpisode(type)
                    setupEpisodeNavigationButtons()
                    refreshEpisodeNavigation(type)
                }
            }
            is Video.Type.Movie -> {
                nextEpisodeOverlayDismissed = false
                nextEpisodePrefetchTargetId = null
                EpisodeManager.clearEpisodes()
                hideNextEpisodeOverlay()
            }
        }


        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }
        binding.settings.setOnSoftwareDecoderSelectedListener { useSoftware ->
            currentSoftwareDecoder = useSoftware
            displayVideo(
                currentVideo ?: return@setOnSoftwareDecoderSelectedListener,
                currentServer ?: return@setOnSoftwareDecoderSelectedListener
            )
        }
        binding.pvPlayer.resizeMode = UserPreferences.playerResize.resizeMode
        binding.pvPlayer.subtitleView?.apply {
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize)
            setStyle(UserPreferences.captionStyle)
            setPadding(0, 0, 0, UserPreferences.captionMargin.dp(context))
        }
        setupEpisodeNavigationButtons()

        binding.pvPlayer.controller.binding.btnExoBack.setOnClickListener {
            findNavController().navigateUp()
        }

        updatePlayerHeader()

        binding.pvPlayer.controller.binding.btnExoExternalPlayer.setOnClickListener {
            Toast.makeText(
                requireContext(),
                getString(R.string.player_external_player_error_video),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.pvPlayer.controller.binding.exoReplay.setOnClickListener {
            player.seekTo(0)
        }

        binding.pvPlayer.controller.binding.btnExoLock.setOnClickListener {
            binding.pvPlayer.controller.binding.gControlsLock.isGone = true
            binding.pvPlayer.controller.binding.btnExoUnlock.isVisible = true
        }

        binding.pvPlayer.controller.binding.btnExoUnlock.setOnClickListener {
            binding.pvPlayer.controller.binding.gControlsLock.isVisible = true
            binding.pvPlayer.controller.binding.btnExoUnlock.isGone = true
        }

        binding.pvPlayer.controller.binding.btnExoPictureInPicture.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.player_picture_in_picture_not_supported),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                enterPIPMode()
            }
        }

        binding.pvPlayer.controller.binding.btnExoAspectRatio.setOnClickListener {
            val newResize = UserPreferences.playerResize.next()
            zoomToast?.cancel()
            zoomToast = Toast.makeText(requireContext(), newResize.stringRes, Toast.LENGTH_SHORT)
            zoomToast?.show()

            UserPreferences.playerResize = newResize
            binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
            updatePlayerScale()
        }

        binding.pvPlayer.controller.binding.exoSettings.setOnClickListener {
            binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
            binding.settings.show()
        }

        binding.settings.setOnLocalSubtitlesClickedListener {
            isIgnoringPip = true
            pickLocalSubtitle.launch(
                arrayOf(
                    "text/plain",
                    "text/str",
                    "application/octet-stream",
                    MimeTypes.TEXT_UNKNOWN,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.TEXT_SSA,
                    MimeTypes.APPLICATION_TTML,
                    MimeTypes.APPLICATION_MP4VTT,
                    MimeTypes.APPLICATION_SUBRIP,
                )
            )
        }

        binding.settings.setOnOpenSubtitleSelectedListener { subtitle ->
            viewModel.downloadSubtitle(subtitle.openSubtitle)
        }

        binding.settings.setOnSubDLSubtitleSelectedListener { subtitle ->
            viewModel.downloadSubDLSubtitle(subtitle.subDLSubtitle)
        }

        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }

        binding.pvPlayer.controller.binding.btnSkipIntro.setOnClickListener {
            player.seekTo(player.currentPosition + 85000)
            it.isGone = true
        }

        binding.btnNextEpisodeAction.setOnClickListener {
            hideNextEpisodeOverlay()
            playNextEpisodeAcrossSeasons()
        }
        binding.btnNextEpisodeDismiss.setOnClickListener {
            nextEpisodeOverlayDismissed = true
            hideNextEpisodeOverlay()
        }

        binding.settings.onManualZoomClicked = {
            binding.settings.hide()
            binding.pvPlayer.hideController()
            binding.pvPlayer.enterManualZoomMode()
        }
    }

 private fun updatePlayerScale() {
        val videoSurfaceView = binding.pvPlayer.videoSurfaceView
        val playerResize = UserPreferences.playerResize 

        binding.pvPlayer.resizeMode = playerResize.resizeMode 

        when (playerResize) { 
            UserPreferences.PlayerResize.Stretch43 -> {
                val scale = 1.33f 
                videoSurfaceView?.scaleX = scale
                videoSurfaceView?.scaleY = 1f
            }
            UserPreferences.PlayerResize.StretchVertical -> {
                videoSurfaceView?.scaleX = 1f
                videoSurfaceView?.scaleY = 1.25f
            }
            UserPreferences.PlayerResize.SuperZoom -> {
                videoSurfaceView?.scaleX = 1.5f
                videoSurfaceView?.scaleY = 1.5f
            }
            else -> {
                videoSurfaceView?.scaleX = 1f
                videoSurfaceView?.scaleY = 1f
            }
        }
    }

    fun setupEpisodeNavigationButtons() {
        val btnPrevious = binding.pvPlayer.controller.binding.btnCustomPrev
        val btnNext = binding.pvPlayer.controller.binding.btnCustomNext

        // IPTV channel navigation: prev/next channel buttons
        val isIptvChannel = args.id.startsWith("ch::") || args.id.startsWith("sport::")
        if (isIptvChannel) {
            setupChannelNavigationButtons(btnPrevious, btnNext)
            return
        }

        fun handleNavigationButton(
            button: ImageView,
            hasEpisode: () -> Boolean,
            playEpisode: () -> Unit
        ) {
            if (!hasEpisode()) {
                button.isGone = true
                return
            }

            button.isGone = false
            button.setOnClickListener listener@{
                if (!hasEpisode()) return@listener

                val videoType = args.videoType

                val watchItem: WatchItem? = when (videoType) {
                    is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                    is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                }

                when (videoType) {
                    is Video.Type.Movie -> {
                        val provider = UserPreferences.currentProvider ?: return@listener
                        val movie = watchItem as? Movie
                        movie?.let { database.movieDao().update(it) }
                        movie?.let { UserDataCache.addMovieToContinueWatching(requireContext(), provider, it) }
                    }

                    is Video.Type.Episode -> {
                        val provider = UserPreferences.currentProvider ?: return@listener
                        val episode = watchItem as? Episode
                        episode?.let {
                            if (player.hasFinished()) {
                                database.episodeDao().resetProgressionFromEpisode(videoType.id)
                                UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, it.id)
                            }
                            database.episodeDao().update(it)

                            if (!player.hasFinished()) {
                                UserDataCache.addEpisodeToContinueWatching(requireContext(), provider, it)
                            }

                            it.tvShow?.let { tvShow ->
                                database.tvShowDao().getById(tvShow.id)
                            }?.let { tvShow ->
                                val episodeDao = database.episodeDao()
                                val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                                database.tvShowDao().save(tvShow.copy().apply {
                                    merge(tvShow)
                                    isWatching = !player.hasReallyFinished() || isStillWatching
                                })
                            }
                        }
                    }
                }

                playEpisode()
            }
        }

        handleNavigationButton(
            btnPrevious,
            EpisodeManager::hasPreviousEpisode,
            viewModel::playPreviousEpisode
        )
        handleNavigationButton(btnNext, EpisodeManager::hasNextEpisode, ::playNextEpisodeAcrossSeasons)
    }

    private fun setupChannelNavigationButtons(btnPrevious: ImageView, btnNext: ImageView) {
        val provider = UserPreferences.currentProvider
        if (provider !is WiTvProvider) {
            btnPrevious.isGone = true
            btnNext.isGone = true
            return
        }

        val prevId = provider.getPreviousChannelId(args.id)
        val nextId = provider.getNextChannelId(args.id)

        btnPrevious.isGone = prevId == null
        btnNext.isGone = nextId == null

        fun navigateToChannel(channelId: String) {
            val channelName = provider.getChannelDisplayName(channelId) ?: channelId
            val channelPoster = provider.getChannelPoster(channelId)

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
                season = Video.Type.Episode.Season(
                    number = 1,
                    title = "Live",
                ),
            )
            val navArgs = android.os.Bundle().apply {
                putString("id", channelId)
                putString("title", channelName)
                putString("subtitle", channelName)
                putSerializable("videoType", videoType)
            }
            findNavController().navigate(
                R.id.player,
                navArgs,
                androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.player, true)
                    .build()
            )
        }

        if (prevId != null) {
            btnPrevious.setOnClickListener { navigateToChannel(prevId) }
        }
        if (nextId != null) {
            btnNext.setOnClickListener { navigateToChannel(nextId) }
        }
    }

    /** Try the next untried OLA channel variant. Returns true if a variant was found and is being tried. */
    private var triedChannelVariantIds = mutableSetOf<String>()

    private fun tryNextChannelVariant(failedServer: Video.Server?): Boolean {
        val variants = PlayerSettingsView.Settings.ChannelVariant.list
        if (variants.isEmpty()) return false

        // Mark the failed server's variant as tried
        if (failedServer != null) triedChannelVariantIds.add(failedServer.id)

        // Find the first untried variant
        val nextVariant = variants.firstOrNull { it.id !in triedChannelVariantIds }
        if (nextVariant != null) {
            triedChannelVariantIds.add(nextVariant.id)
            Log.d("PlayerMobileFragment", "Fallback → trying channel variant: ${nextVariant.name}")
            viewModel.getVideo(Video.Server(nextVariant.id, nextVariant.name))
            return true
        }

        Log.d("PlayerMobileFragment", "No more channel variants to try (tried ${triedChannelVariantIds.size})")
        return false
    }

    private fun refreshEpisodeNavigation(type: Video.Type.Episode) {
        lifecycleScope.launch(Dispatchers.IO) {
            EpisodeManager.ensureNextEpisodeAvailable(type, database)
            withContext(Dispatchers.Main) {
                setupEpisodeNavigationButtons()
            }
        }
    }

    private fun playNextEpisodeAcrossSeasons(autoplay: Boolean = false) {
        val type = args.videoType as? Video.Type.Episode ?: return

        lifecycleScope.launch {
            val hasNextEpisode = withContext(Dispatchers.IO) {
                EpisodeManager.ensureNextEpisodeAvailable(type, database)
            }

            setupEpisodeNavigationButtons()

            if (!hasNextEpisode) return@launch
            if (autoplay && !UserPreferences.autoplay) return@launch

            viewModel.playNextEpisode()
        }
    }

    private fun decodeBase64Uri(uri: String): String? {
        return try {
            val parts = uri.split(",")
            if (parts.size == 2 && parts[0].contains(";base64")) {
                val base64Data = parts[1]
                val decodedBytes = Base64.getDecoder().decode(base64Data)
                String(decodedBytes, Charsets.UTF_8)
            } else {
                null
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun extractUrlFromPlaylist(playlist: String): String? {
        return try {
            val lines = playlist.lines().map { it.trim() }
            lines.firstOrNull { it.startsWith("http") }
                ?: lines.firstNotNullOfOrNull { line ->
                    val regex = """URI=["'](http[^"']+)["']""".toRegex()
                    regex.find(line)?.groupValues?.get(1)
                }
        } catch (ignored: Exception) {
            null
        }
    }


    /**
     * Returns true if this video source is a LuluVdo/LuluStream CDN URL
     * that will always 403 with any non-WebView HTTP client (including Cronet).
     */
    private fun isLuluVdoCdn(video: Video): Boolean {
        if (video.webViewUrl.isNullOrBlank()) return false
        val src = video.source.lowercase()
        val wvUrl = video.webViewUrl!!.lowercase()
        return src.contains("tnmr.org") || src.contains("luluvdo") || src.contains("lulustream")
                || src.contains("luluvid") || src.contains("luluvdoo") || src.contains("lulucdn")
                || wvUrl.contains("luluvdo") || wvUrl.contains("lulustream")
                || wvUrl.contains("luluvid") || wvUrl.contains("luluvdoo")
    }

    /** Netu cfglobalcdn — ISP blocks the IP, WebView bypasses it */
    private fun isNetuCfglobalcdn(video: Video): Boolean {
        return video.webViewUrl != null &&
               com.streamflixreborn.streamflix.extractors.NetuExtractor.sharedWebView != null &&
               (video.source.startsWith("data:") || video.source.contains("cfglobalcdn.com"))
    }

    /**
     * Sync cookies from Android's WebView CookieManager to Java's default CookieHandler.
     * This allows DefaultHttpDataSource (which uses HttpURLConnection) to send WebView cookies.
     */
    private fun syncWebViewCookies(sourceUrl: String) {
        try {
            val uri = java.net.URI(sourceUrl)
            val host = uri.host ?: return

            // Ensure Java's CookieHandler is set up
            if (java.net.CookieHandler.getDefault() == null) {
                java.net.CookieHandler.setDefault(java.net.CookieManager())
            }
            val cookieHandler = java.net.CookieHandler.getDefault() as? java.net.CookieManager ?: return

            // Get cookies from WebView's CookieManager for this domain
            val webViewCookies = android.webkit.CookieManager.getInstance().getCookie("https://$host")
            if (webViewCookies.isNullOrBlank()) {
                Log.d("PlayerNetwork", "No WebView cookies for $host")
                return
            }

            // Parse and add each cookie to Java's CookieManager
            webViewCookies.split(";").forEach { cookie ->
                val trimmed = cookie.trim()
                if (trimmed.isNotBlank()) {
                    try {
                        val httpCookie = java.net.HttpCookie.parse("Set-Cookie: $trimmed")
                        httpCookie.forEach { c ->
                            c.domain = host
                            c.path = "/"
                            cookieHandler.cookieStore.add(uri, c)
                        }
                    } catch (_: Exception) {}
                }
            }
            Log.d("PlayerNetwork", "Synced WebView cookies to CookieHandler for $host: ${webViewCookies.take(100)}")
        } catch (e: Exception) {
            Log.w("PlayerNetwork", "Cookie sync failed: ${e.message}")
        }
    }

    private fun displayVideo(video: Video, server: Video.Server) {
        currentVideo = video
        currentServer = server
        updatePlayerHeader()

        // Clean up any existing WebView overlay (e.g. switching servers)
        if (webViewOverlay != null && !(video.needsWebViewClick && !video.webViewUrl.isNullOrBlank())) {
            hideWebViewOverlay()
        }

        Log.d("PlayerDebug", "displayVideo: server=${server.name}, source=${video.source.take(100)}, type=${video.type}, headers=${video.headers}")

        // ── Netu anti-bot: show WebView overlay so user can tap the play button ──
        if (video.needsWebViewClick && !video.webViewUrl.isNullOrBlank()) {
            // Clean up any existing overlay first (prevents second player stacking)
            if (webViewOverlay != null) {
                Log.d("PlayerMobile", "Cleaning previous overlay before new one: ${video.webViewUrl?.take(60)}")
                hideWebViewOverlay()
            }
            // Clean up DaddyLive proxy if any
            daddyLiveProxyWebView?.let { old ->
                try { old.stopLoading(); old.destroy() } catch (_: Exception) {}
                (binding.root as? ViewGroup)?.removeView(old)
            }
            daddyLiveProxyWebView = null

            Log.d("PlayerMobile", "needsWebViewClick → showing WebView overlay for: ${video.webViewUrl}")
            // Stop current playback before showing overlay
            if (::player.isInitialized) {
                player.stop()
                player.clearMediaItems()
            }
            pendingWebViewVideo = video
            pendingWebViewServer = server
            showWebViewOverlay(video.webViewUrl!!)
            return
        }

        // WebView bypass: LuluVdo (TLS fingerprint) or Netu (ISP IP block)
        // If we have a shared WebView from extraction, use WebViewDataSource
        // to route ExoPlayer's HTTP requests through the WebView's network stack.
        val needsWebViewDs = (isLuluVdoCdn(video)
            && video.source.startsWith("data:")
            && com.streamflixreborn.streamflix.extractors.LuluVdoExtractor.sharedWebView != null)
            || isNetuCfglobalcdn(video)
        if (isLuluVdoCdn(video) || isNetuCfglobalcdn(video)) {
            Log.d("PlayerNetwork", "WebView bypass: source is ${if (video.source.startsWith("data:")) "data URI" else "CDN URL"}, webViewDs=$needsWebViewDs, lulu=${isLuluVdoCdn(video)}, netu=${isNetuCfglobalcdn(video)}")
        }

        val extraBuffering = PlayerSettingsView.Settings.ExtraBuffering.isEnabled
        val softwareDecoder = PlayerSettingsView.Settings.SoftwareDecoder.isEnabled

        // Switch DataSource if the video URL needs a different engine
        val urlNeedsCronet = needsCronet(video.source)
        val urlNeedsDoH = needsDoH(video.source)
        val urlNeedsBrowserOkHttp = needsBrowserOkHttp(video.source)
        val dataSourceMismatch = (urlNeedsCronet && !usingCronet) || (!urlNeedsCronet && usingCronet)
            || (urlNeedsDoH && !usingDoH) || (!urlNeedsDoH && usingDoH)
            || (urlNeedsBrowserOkHttp && !usingBrowserOkHttp) || (!urlNeedsBrowserOkHttp && usingBrowserOkHttp)
        val needsReinit =
            extraBuffering != currentExtraBuffering || softwareDecoder != currentSoftwareDecoder || dataSourceMismatch

        if (dataSourceMismatch) {
            Log.d("PlayerNetwork", "DataSource mismatch: needsCronet=$urlNeedsCronet(was=$usingCronet), needsDoH=$urlNeedsDoH(was=$usingDoH), needsBrowserOkHttp=$urlNeedsBrowserOkHttp(was=$usingBrowserOkHttp) → switching")
            httpDataSource = createHttpDataSourceFactory(video.source)
            Log.d("PlayerNetwork", "After factory creation: httpDataSource=${httpDataSource.javaClass.simpleName}, usingCronet=$usingCronet, usingDoH=$usingDoH, usingBrowserOkHttp=$usingBrowserOkHttp")
        }

        if (needsReinit) {
            initializePlayer(extraBuffering, softwareDecoder, video.source)
            Log.d("PlayerNetwork", "After initializePlayer: httpDataSource=${httpDataSource.javaClass.simpleName}, usingCronet=$usingCronet")
            player.playlistMetadata = MediaMetadata.Builder()
                .setTitle(resolvePlayerTitle())
                .setMediaServers(servers.map {
                    MediaServer(
                        id = it.id,
                        name = it.name,
                    )
                })
                .build()
        }

        val currentPosition = player.currentPosition

        if (!needsWebViewDs) {
            httpDataSource.setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to NetworkClient.USER_AGENT,
                ) + (video.headers ?: emptyMap())
            )
        }

        val mediaItem = MediaItem.Builder()
            .setUri(video.source.toUri())
            .setMimeType(video.type)
            .setSubtitleConfigurations(video.subtitles.map { subtitle ->
                MediaItem.SubtitleConfiguration.Builder(subtitle.file.toUri())
                    .setMimeType(subtitle.file.toSubtitleMimeType())
                    .setLabel(subtitle.label)
                    .setSelectionFlags(if (subtitle.default) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            })
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setMediaServerId(server.id)
                    .build()
            )
            .build()

        if (needsWebViewDs) {
            // Route .ts segment requests through WebView's network stack
            val wv = com.streamflixreborn.streamflix.extractors.LuluVdoExtractor.sharedWebView
                ?: com.streamflixreborn.streamflix.extractors.NetuExtractor.sharedWebView
                ?: error("needsWebViewDs but no sharedWebView available")
            val webViewDsFactory = DefaultDataSource.Factory(
                requireContext(),
                com.streamflixreborn.streamflix.utils.WebViewDataSource.Factory(wv)
            )
            val hlsSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(webViewDsFactory)
                .createMediaSource(mediaItem)
            player.setMediaSource(hlsSource)
            usingWebView = true
            Log.d("PlayerNetwork", "WebView bypass: using WebViewDataSource for HLS playback (${if (isLuluVdoCdn(video)) "LuluVdo" else "Netu"})")
        } else {
            player.setMediaItem(mediaItem)
            usingWebView = false
        }

        binding.pvPlayer.controller.binding.btnExoExternalPlayer.setOnClickListener {
            isIgnoringPip = true
            
            val videoTitle = when (val type = args.videoType) {
                is Video.Type.Movie -> type.title
                is Video.Type.Episode -> "${type.tvShow.title} • S${type.season.number} E${type.number}"
            }
            
            var sourceUri: Uri
            val mimeType = "video/*"
            
            val initialSource = video.source

            if (initialSource.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
                val playlistContent = decodeBase64Uri(initialSource)
                val extractedUrl = if (playlistContent != null) extractUrlFromPlaylist(playlistContent) else null
                
                if (extractedUrl != null) {
                    sourceUri = extractedUrl.toUri()
                    Log.i("ExternalPlayer", "Link reale estratto: $sourceUri")
                } else {
                    try {
                        val file = File(requireContext().cacheDir, "stream.m3u8")
                        FileOutputStream(file).use { it.write(playlistContent?.toByteArray() ?: ByteArray(0)) }
                        sourceUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
                    } catch (ignored: Exception) {
                        sourceUri = initialSource.toUri()
                    }
                }
            } else {
                sourceUri = initialSource.toUri()
            }

            Log.i("ExternalPlayer", "Avvio intent con URI: $sourceUri e MIME: $mimeType")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(sourceUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                
                putExtra("title", videoTitle)
                putExtra("position", player.currentPosition.toInt())
                putExtra("return_result", true)
                
                putExtra("extra_headers", video.headers?.map { "${it.key}: ${it.value}" }?.toTypedArray())
                
                if (video.headers != null) {
                    val headersArray = video.headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
                    putExtra("headers", headersArray)
                }
            }

            try {
                val receiverIntent = Intent("ACTION_PLAYER_CHOSEN").apply {
                    setPackage(requireContext().packageName)
                }
                
                val pendingIntent = PendingIntent.getBroadcast(
                    requireContext(), 0, receiverIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    startActivity(
                        Intent.createChooser(
                            intent,
                            getString(R.string.player_external_player_title),
                            pendingIntent.intentSender
                        )
                    )
                } else {
                    startActivity(Intent.createChooser(intent, getString(R.string.player_external_player_title)))
                }
            } catch (e: Exception) {
                Log.e("ExternalPlayer", "Errore selettore app", e)
                startActivity(Intent.createChooser(intent, getString(R.string.player_external_player_title)))
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                Log.d("PlayerDebug", "onPlaybackStateChanged: $stateName, uri=${player.currentMediaItem?.localConfiguration?.uri?.toString()?.take(80)}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                binding.pvPlayer.keepScreenOn = isPlaying || UserPreferences.keepScreenOnWhenPaused

                if (isPlaying) {
                    startProgressHandler()
                } else {
                    stopProgressHandler()
                }

                val hasUri = player.currentMediaItem?.localConfiguration?.uri
                    ?.toString()?.isNotEmpty()
                    ?: false

                if (!isPlaying && hasUri) {
                    val videoType = args.videoType
                    val watchItem: WatchItem? = when (videoType) {
                        is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                        is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                    }

                    when {
                        player.hasStarted() && !player.hasFinished() -> {
                            watchItem?.isWatched = false
                            watchItem?.watchedDate = null
                            watchItem?.watchHistory = WatchItem.WatchHistory(
                                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                                lastPlaybackPositionMillis = player.currentPosition,
                                durationMillis = player.duration,
                            )
                        }

                        player.hasFinished() -> {
                            watchItem?.isWatched = true
                            watchItem?.watchedDate = Calendar.getInstance()
                            watchItem?.watchHistory = null
                        }
                    }

                            when (videoType) {
                                is Video.Type.Movie -> {
                                    val provider = UserPreferences.currentProvider ?: return
                                    val movie = watchItem as? Movie
                                    movie?.let {
                                        database.movieDao().update(it)
                                        UserDataCache.syncMovieToCache(requireContext(), provider, it)
                                    }
                                }

                                is Video.Type.Episode -> {
                                    val provider = UserPreferences.currentProvider ?: return
                                    val episode = watchItem as? Episode
                                    episode?.let {
                                        if (player.hasFinished()) {
                                            database.episodeDao().resetProgressionFromEpisode(videoType.id)
                                            UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, it.id)
                                            queueNextEpisodeForContinueWatching(provider)
                                        }
                                        database.episodeDao().update(it)
                                        if (!player.hasFinished()) {
                                            UserDataCache.syncEpisodeToCache(requireContext(), provider, it)
                                        }

                                        it.tvShow?.let { tvShow ->
                                            database.tvShowDao().getById(tvShow.id)
                                        }?.let { tvShow ->
                                            val episodeDao = database.episodeDao()
                                            val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)
                                            
                                            database.tvShowDao().save(tvShow.copy().apply {
                                                merge(tvShow)
                                                isWatching = !player.hasReallyFinished() || isStillWatching
                                            })
                                        }
                                    }
                                }
                            }
                    if (player.hasReallyFinished()) {
                        if (UserPreferences.autoplay) {
                            playNextEpisodeAcrossSeasons(autoplay = true)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                Log.e("PlayerDebug", "onPlayerError: code=${error.errorCode}, msg=${error.message}")
                Log.e("PlayerDebug", "  cause: ${error.cause}")
                Log.e("PlayerDebug", "  cause.cause: ${error.cause?.cause}")
                Log.e("PlayerDebug", "  uri: ${player.currentMediaItem?.localConfiguration?.uri?.toString()?.take(100)}")
                Log.e("PlayerMobileFragment", "onPlayerError: ", error)

                val cause = error.cause?.cause
                val causeMsg = cause?.message ?: ""
                val errorCauseMsg = error.cause?.message ?: ""

                // Fallback 0: Cronet network errors (NOT 403) → retry with DefaultHttp
                val is403 = errorCauseMsg.contains("403") || causeMsg.contains("403")
                val isCronetNetworkError = causeMsg.contains("ERR_CONNECTION_TIMED_OUT")
                        || causeMsg.contains("ERR_CONNECTION_REFUSED")
                        || causeMsg.contains("ERR_CONNECTION_RESET")
                        || causeMsg.contains("ERR_NAME_NOT_RESOLVED")
                        || causeMsg.contains("ERR_SSL")
                        || causeMsg.contains("ERR_NETWORK")
                if (usingCronet && isCronetNetworkError && !is403) {
                    Log.w("PlayerNetwork", "Cronet network error ($causeMsg), retrying with DefaultHttp fallback")
                    val video = currentVideo ?: return
                    val server = currentServer ?: return
                    httpDataSource = createDefaultHttpDataSourceFactory()
                    dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)
                    initializePlayer(currentExtraBuffering, currentSoftwareDecoder, video.source)
                    displayVideo(video, server)
                    return
                }

                // Fallback 1: if 403, try next server or channel variant
                if (is403) {
                    Log.e("PlayerNetwork", "403 error! usingCronet=$usingCronet, source=${player.currentMediaItem?.localConfiguration?.uri?.toString()?.take(80)}")
                    val server = currentServer ?: return
                    val nextServer = servers.getOrNull(servers.indexOf(server) + 1)
                    if (nextServer != null) {
                        Log.d("PlayerNetwork", "403 → trying next server: ${nextServer.name}")
                        viewModel.getVideo(nextServer)
                    } else if (!tryNextChannelVariant(server)) {
                        Log.e("PlayerNetwork", "No more servers or channel variants to try after 403")
                    }
                    return
                }

                // Fallback 2: if connection timed out, ISP-blocked, or WebView fetch failed,
                // automatically try the next server or channel variant
                val isConnectionTimeout = causeMsg.contains("SocketTimeoutException")
                        || causeMsg.contains("failed to connect")
                        || causeMsg.contains("Connection timed out")
                        || causeMsg.contains("ERR_CONNECTION_TIMED_OUT")
                        || causeMsg.contains("ISP blocked")
                        || causeMsg.contains("cfglobalcdn IP")
                        || cause is java.net.SocketTimeoutException
                        || error.cause is java.net.SocketTimeoutException
                        || errorCauseMsg.contains("WebView fetch failed")
                        || errorCauseMsg.contains("WebView fetch timed out")
                if (isConnectionTimeout) {
                    val server = currentServer ?: return
                    val nextServer = servers.getOrNull(servers.indexOf(server) + 1)
                    if (nextServer != null) {
                        Log.w("PlayerNetwork", "Connection timeout on ${server.name}, auto-switching to ${nextServer.name}")
                        viewModel.getVideo(nextServer)
                    } else if (!tryNextChannelVariant(server)) {
                        Log.e("PlayerNetwork", "Connection timeout on ${server.name}, no more servers or variants to try")
                    }
                }
            }
        })

        if (currentPosition == 0L) {
            val videoType = args.videoType
            val provider = UserPreferences.currentProvider
            
            val watchItem: WatchItem? = when (videoType) {
                is Video.Type.Movie -> {
                    // Try cache first, then DB
                    var movie = if (provider != null) {
                        UserDataCache.read(requireContext(), provider)?.continueWatchingMovies
                            ?.find { it.id == videoType.id }?.toMovie()
                    } else null
                    movie ?: database.movieDao().getById(videoType.id)
                }
                is Video.Type.Episode -> {
                    // Try cache first, then DB
                    var episode = if (provider != null) {
                        UserDataCache.read(requireContext(), provider)?.continueWatchingEpisodes
                            ?.find { it.id == videoType.id }?.toEpisode()
                    } else null
                    episode ?: database.episodeDao().getById(videoType.id)
                }
            }
            
            val lastPlaybackPositionMillis = watchItem?.watchHistory
                ?.let { it.lastPlaybackPositionMillis - 10.seconds.inWholeMilliseconds }

            player.seekTo(lastPlaybackPositionMillis ?: 0)
        } else {
            player.seekTo(currentPosition)
        }

        player.prepare()
        player.play()
    }

    private fun enterPIPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.pvPlayer.useController = false
            requireActivity().enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .build()
            )
        }
    }


    private fun ExoPlayer.hasStarted(): Boolean {
        return (this.currentPosition > (this.duration * 0.005) || this.currentPosition > 20.seconds.inWholeMilliseconds)
    }

    private fun ExoPlayer.hasFinished(): Boolean {
        return (this.currentPosition > (this.duration * 0.90))
    }

    private fun ExoPlayer.hasReallyFinished(): Boolean {
        return this.duration > 0 &&
                this.currentPosition >= (this.duration - UserPreferences.autoplayBuffer * 1000)
    }

    private fun currentVideoTypeForUi(): Video.Type = when (val type = args.videoType) {
        is Video.Type.Episode -> EpisodeManager.getCurrentEpisode()
            ?.takeIf { currentEpisode -> currentEpisode.id == type.id }
            ?: type
        is Video.Type.Movie -> type
    }

    private fun resolvePlayerTitle(videoType: Video.Type = currentVideoTypeForUi()): String {
        return when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title.ifBlank { args.title }
        }
    }

    private fun resolvePlayerSubtitle(videoType: Video.Type = currentVideoTypeForUi()): String {
        return when (videoType) {
            is Video.Type.Movie -> args.subtitle
            is Video.Type.Episode -> {
                val episodeTitle = videoType.title?.takeUnless { it.isBlank() } ?: args.subtitle
                "S${videoType.season.number} E${videoType.number}  •  $episodeTitle"
            }
        }
    }

    private fun updatePlayerHeader(videoType: Video.Type = currentVideoTypeForUi()) {
        binding.pvPlayer.controller.binding.tvExoTitle.text = resolvePlayerTitle(videoType)
        binding.pvPlayer.controller.binding.tvExoSubtitle.text = resolvePlayerSubtitle(videoType)
    }

    private fun queueNextEpisodeForContinueWatching(provider: com.streamflixreborn.streamflix.providers.Provider) {
        val nextEpisode = EpisodeManager.peekNextEpisode() ?: return
        val episodeDao = database.episodeDao()
        val persistedNextEpisode = episodeDao.getById(nextEpisode.id)?.apply {
            isWatched = false
            watchedDate = null
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                lastPlaybackPositionMillis = 0L,
                durationMillis = 0L,
            )
        } ?: Episode(
            id = nextEpisode.id,
            number = nextEpisode.number,
            title = nextEpisode.title,
            poster = nextEpisode.poster,
            overview = nextEpisode.overview,
            tvShow = database.tvShowDao().getById(nextEpisode.tvShow.id) ?: TvShow(
                id = nextEpisode.tvShow.id,
                title = nextEpisode.tvShow.title,
                poster = nextEpisode.tvShow.poster,
                banner = nextEpisode.tvShow.banner,
            ),
            season = Season(
                number = nextEpisode.season.number,
                title = nextEpisode.season.title,
            ),
        ).apply {
            isWatched = false
            watchedDate = null
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                lastPlaybackPositionMillis = 0L,
                durationMillis = 0L,
            )
        }

        episodeDao.save(persistedNextEpisode)
        UserDataCache.syncEpisodeToCache(requireContext(), provider, persistedNextEpisode)
    }
    private fun startProgressHandler() {
        progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        progressRunnable = Runnable {
            if (player.isPlaying) {
                val show = player.currentPosition in 3000..120000
                showSkipIntroButton(show)
                updateNextEpisodeOverlay()
            }
            progressHandler.postDelayed(progressRunnable, 1000)
        }
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressHandler() {
        if (::progressHandler.isInitialized) {
            progressHandler.removeCallbacks(progressRunnable)
        }
    }

    private fun updateNextEpisodeOverlay() {
        val currentEpisode = currentVideoTypeForUi() as? Video.Type.Episode ?: run {
            hideNextEpisodeOverlay()
            return
        }
        val duration = player.duration.takeIf { it > 0 } ?: run {
            hideNextEpisodeOverlay()
            return
        }
        val remainingMs = (duration - player.currentPosition).coerceAtLeast(0L)

        if (nextEpisodeOverlayDismissed) {
            hideNextEpisodeOverlay()
            return
        }

        if (remainingMs <= NEXT_EPISODE_PREFETCH_THRESHOLD_MS) {
            ensureNextEpisodePrepared(currentEpisode)
        }

        val nextEpisode = EpisodeManager.peekNextEpisode()
        val overlayThresholdMs = maxOf(
            NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS,
            UserPreferences.autoplayBuffer * 1000L
        )
        if (nextEpisode == null || remainingMs == 0L || remainingMs > overlayThresholdMs) {
            hideNextEpisodeOverlay()
            return
        }

        showNextEpisodeOverlay(nextEpisode, remainingMs)
    }

    private fun ensureNextEpisodePrepared(currentEpisode: Video.Type.Episode) {
        if (EpisodeManager.peekNextEpisode() != null) return
        if (nextEpisodePrefetchTargetId == currentEpisode.id && nextEpisodePrefetchJob?.isActive == true) {
            return
        }

        nextEpisodePrefetchTargetId = currentEpisode.id
        nextEpisodePrefetchJob?.cancel()
        nextEpisodePrefetchJob = lifecycleScope.launch(Dispatchers.IO) {
            val loaded = EpisodeManager.ensureNextEpisodeAvailable(currentEpisode, database)
            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                setupEpisodeNavigationButtons()
                if (loaded && player.isPlaying) {
                    updateNextEpisodeOverlay()
                }
            }
        }
    }

    private fun showNextEpisodeOverlay(nextEpisode: Video.Type.Episode, remainingMs: Long) {
        binding.tvNextEpisodeMeta.text = getString(
            R.string.tv_show_item_season_number_episode_number,
            nextEpisode.season.number,
            nextEpisode.number
        )
        binding.tvNextEpisodeTitle.text = nextEpisode.title
            ?: getString(R.string.episode_number, nextEpisode.number)
        binding.tvNextEpisodeCountdown.text = if (UserPreferences.autoplay) {
            getString(
                R.string.player_next_episode_autoplay_in,
                ((remainingMs + 999L) / 1000L).toInt()
            )
        } else {
            getString(R.string.player_next_episode_ready)
        }

        Glide.with(this)
            .load(nextEpisode.poster ?: nextEpisode.tvShow.poster)
            .error(R.drawable.glide_fallback_cover)
            .fallback(R.drawable.glide_fallback_cover)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivNextEpisodePoster)

        if (binding.layoutNextEpisodeOverlay.isGone) {
            val fadeIn = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in)
            binding.layoutNextEpisodeOverlay.startAnimation(fadeIn)
            binding.layoutNextEpisodeOverlay.isVisible = true
        }
    }

    private fun hideNextEpisodeOverlay() {
        if (_binding == null) return
        if (binding.layoutNextEpisodeOverlay.isVisible) {
            val fadeOut = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out)
            binding.layoutNextEpisodeOverlay.startAnimation(fadeOut)
            binding.layoutNextEpisodeOverlay.isGone = true
        }
    }

    private fun showSkipIntroButton(show: Boolean) {
        val btnSkipIntro = binding.pvPlayer.controller.binding.btnSkipIntro
        if (show && btnSkipIntro.isGone) {
            val fadeIn = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in)
            btnSkipIntro.startAnimation(fadeIn)
            btnSkipIntro.isVisible = true
        } else if (!show && btnSkipIntro.isVisible) {
            val fadeOut = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out)
            btnSkipIntro.startAnimation(fadeOut)
            btnSkipIntro.isGone = true
        }
    }



    override fun onPause() {
        super.onPause()
        stopProgressHandler()
        hideNextEpisodeOverlay()
    }

    private var currentExtraBuffering = false
    private var currentSoftwareDecoder = false

    private fun buildPlayer(extraBuffering: Boolean): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                if (extraBuffering) 300_000 else DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        val baseBuilder = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 && !currentSoftwareDecoder) {
            ExoPlayer.Builder(requireContext())
        } else {
            val renderersFactory = DefaultRenderersFactory(requireContext()).apply {
                setEnableDecoderFallback(true)
                if (currentSoftwareDecoder) {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                }
            }
            ExoPlayer.Builder(requireContext(), renderersFactory)
        }

        return baseBuilder
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
    }

    /**
     * Pre-install Cronet from Play Services so CronetEngine.Builder uses
     * Chrome's real BoringSSL stack (identical JA3 fingerprint to Chrome).
     * Called once in onViewCreated — by the time a video loads, it's ready.
     */
    private fun initCronetEngine() {
        CronetProviderInstaller.installProvider(requireContext())
            .addOnSuccessListener {
                try {
                    cronetEngine = CronetEngine.Builder(requireContext())
                        .enableQuic(true)
                        .enableHttp2(true)
                        .build()
                    Log.d("PlayerNetwork", "Cronet engine pre-initialized: ${cronetEngine?.javaClass?.name}")
                } catch (e: Exception) {
                    Log.e("PlayerNetwork", "Cronet engine build failed after provider install: ${e.message}")
                }
            }
            .addOnFailureListener { e ->
                Log.w("PlayerNetwork", "CronetProviderInstaller failed: ${e.message} — will try native Cronet on demand")
            }
    }

    private fun needsCronet(url: String): Boolean {
        // Cronet uses Chrome's TLS stack (JA3 fingerprint matches real browsers)
        // These CDNs reject non-Chromium TLS fingerprints (OkHttp → 404)
        return url.contains("vidzy.live", ignoreCase = true)
            || url.contains("cfglobalcdn.com", ignoreCase = true)
    }

    private fun needsDoH(url: String): Boolean {
        // sprintcdn/r66nv9ed.com: Filemoon CDN — system DNS resolves to wrong edge,
        // token is edge-bound so we need DoH to hit the correct server
        return url.contains("sprintcdn", ignoreCase = true)
            || url.contains("r66nv9ed.com", ignoreCase = true)
    }

    private fun needsBrowserOkHttp(url: String): Boolean {
        return false // luluvdo/tnmr.org now handled by Cronet
    }

    /**
     * Creates the right DataSource factory for [videoUrl].
     * - vidzy.live → Cronet (Chrome network stack, needed for JA3 bypass)
     * - cfglobalcdn.com → OkHttp + DoH (ISP DNS blocks, need CNAME chain resolution)
     * - tnmr.org/luluvdo → OkHttp with full browser headers (CDN requires browser-like requests)
     * - everything else → DefaultHttpDataSource (system DNS, most compatible)
     */
    private fun createHttpDataSourceFactory(videoUrl: String = ""): HttpDataSource.Factory {
        if (!needsCronet(videoUrl)) {
            if (needsDoH(videoUrl)) {
                Log.d("PlayerNetwork", "URL needs DoH for CNAME resolution ($videoUrl)")
                return createDoHOkHttpDataSourceFactory()
            }
            if (needsBrowserOkHttp(videoUrl)) {
                Log.d("PlayerNetwork", "URL needs browser OkHttp ($videoUrl)")
                return createBrowserOkHttpDataSourceFactory()
            }
            Log.d("PlayerNetwork", "URL does not need Cronet ($videoUrl), using DefaultHttp")
            return createDefaultHttpDataSourceFactory()
        }
        // Use pre-initialized engine from Play Services, or build one on-demand
        val engine = cronetEngine ?: try {
            Log.d("PlayerNetwork", "Cronet engine not pre-initialized, building on demand...")
            CronetEngine.Builder(requireContext())
                .enableQuic(true)
                .enableHttp2(true)
                .build()
        } catch (e: Exception) {
            Log.e("PlayerNetwork", "Cronet completely unavailable: ${e.message}", e)
            null
        }

        if (engine == null) {
            Log.w("PlayerNetwork", "No Cronet engine available, falling back to OkHttp")
            return createDefaultHttpDataSourceFactory()
        }

        Log.d("PlayerNetwork", "Using CronetDataSource (${engine.javaClass.simpleName}) for: ${videoUrl.take(80)}")
        usingCronet = true
        usingDoH = false
        usingBrowserOkHttp = false
        return CronetDataSource.Factory(engine, cronetExecutor)
            .setUserAgent(NetworkClient.USER_AGENT)
            .setConnectionTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
    }

    /**
     * OkHttp DataSource with a custom DNS resolver that uses Cloudflare's JSON
     * DoH API to follow CNAME chains + TCP pre-check to detect ISP-blocked IPs fast.
     */
    private fun createDoHOkHttpDataSourceFactory(): HttpDataSource.Factory {
        usingCronet = false
        usingDoH = true
        usingBrowserOkHttp = false

        val jsonDohDns = object : okhttp3.Dns {
            private val fallback = DnsResolver.doh
            private val dnsClient = OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            /** Multiple DoH providers — different providers may return different IPs */
            private val dohProviders = listOf(
                "https://cloudflare-dns.com/dns-query",
                "https://dns.google/resolve",
                "https://dns.quad9.net:5053/dns-query"
            )

            /** Query a DoH provider and return all A record IPs + any CNAME targets found */
            private fun queryDoH(provider: String, name: String): Pair<List<String>, List<String>> {
                val request = okhttp3.Request.Builder()
                    .url("$provider?name=$name&type=A")
                    .header("Accept", "application/dns-json")
                    .build()
                val body = dnsClient.newCall(request).execute().use { it.body?.string() }
                    ?: return Pair(emptyList(), emptyList())

                val json = org.json.JSONObject(body)
                val answers = json.optJSONArray("Answer")
                    ?: return Pair(emptyList(), emptyList())

                val ips = mutableListOf<String>()
                val cnames = mutableListOf<String>()
                for (i in 0 until answers.length()) {
                    val answer = answers.getJSONObject(i)
                    when (answer.optInt("type")) {
                        1 -> ips.add(answer.optString("data"))   // A record
                        5 -> cnames.add(answer.optString("data").trimEnd('.')) // CNAME
                    }
                }
                return Pair(ips, cnames)
            }

            override fun lookup(hostname: String): List<java.net.InetAddress> {
                if (!hostname.contains("cfglobalcdn.com", ignoreCase = true)) {
                    return fallback.lookup(hostname)
                }
                Log.d("PlayerNetwork", "Multi-DoH lookup for: $hostname")
                try {
                    val allIps = linkedSetOf<String>() // preserve order, no duplicates
                    var cnameTarget: String? = null

                    // Phase 1: query all DoH providers for the cfglobalcdn hostname
                    for (provider in dohProviders) {
                        try {
                            val (ips, cnames) = queryDoH(provider, hostname)
                            Log.d("PlayerNetwork", "DoH ($provider): IPs=$ips, CNAMEs=$cnames")
                            allIps.addAll(ips)
                            if (cnames.isNotEmpty() && cnameTarget == null) {
                                cnameTarget = cnames.first()
                            }
                        } catch (e: Exception) {
                            Log.w("PlayerNetwork", "DoH provider $provider failed: ${e.message}")
                        }
                    }

                    // Phase 2: if CNAME found, also resolve CNAME target directly
                    // (might give different IPs than the flattened chain)
                    if (cnameTarget != null) {
                        Log.d("PlayerNetwork", "CNAME chain: $hostname → $cnameTarget, resolving target...")
                        for (provider in dohProviders) {
                            try {
                                val (ips, _) = queryDoH(provider, cnameTarget!!)
                                if (ips.isNotEmpty()) {
                                    Log.d("PlayerNetwork", "CNAME target $cnameTarget via $provider: $ips")
                                    allIps.addAll(ips)
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    if (allIps.isEmpty()) throw Exception("No IPs from any DoH provider")
                    Log.d("PlayerNetwork", "All candidate IPs for $hostname: $allIps")

                    // Phase 3: TCP pre-check all unique IPs
                    val reachable = mutableListOf<java.net.InetAddress>()
                    val unreachable = mutableListOf<java.net.InetAddress>()
                    for (ipStr in allIps) {
                        val ip = java.net.InetAddress.getByName(ipStr)
                        try {
                            val socket = java.net.Socket()
                            socket.connect(java.net.InetSocketAddress(ip, 443), 3000)
                            socket.close()
                            Log.d("PlayerNetwork", "TCP OK: $hostname → ${ip.hostAddress}:443")
                            reachable.add(ip)
                            break // one reachable is enough
                        } catch (e: Exception) {
                            Log.w("PlayerNetwork", "TCP FAIL: $hostname → ${ip.hostAddress}:443")
                            unreachable.add(ip)
                        }
                    }

                    val ordered = reachable + unreachable
                    if (reachable.isEmpty()) {
                        Log.w("PlayerNetwork", "ALL ${allIps.size} IPs blocked for $hostname")
                    }
                    return ordered
                } catch (e: Exception) {
                    Log.w("PlayerNetwork", "Multi-DoH failed for $hostname: ${e.message}, trying wire DoH")
                    return fallback.lookup(hostname)
                }
            }
        }

        val dohClient = OkHttpClient.Builder()
            .dns(jsonDohDns)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        Log.d("PlayerNetwork", "Using OkHttpDataSource with Multi-DoH (cfglobalcdn resolution)")
        return OkHttpDataSource.Factory(dohClient)
            .setUserAgent(NetworkClient.USER_AGENT)
    }

    /**
     * Clean OkHttp DataSource for CDNs like tnmr.org that reject inconsistent headers.
     * Uses DoH DNS + cookie jar from NetworkClient, but NO browser-navigation interceptor
     * (which would add Upgrade-Insecure-Requests and Sec-Fetch-Dest:document that conflict
     * with the media-fetch headers the player sets via setDefaultRequestProperties).
     */
    private fun createBrowserOkHttpDataSourceFactory(): HttpDataSource.Factory {
        usingCronet = false
        usingDoH = false
        usingBrowserOkHttp = true
        val cleanClient = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .cookieJar(NetworkClient.cookieJar)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        Log.d("PlayerNetwork", "Using clean OkHttpDataSource (DoH + cookies, no interceptor)")
        return OkHttpDataSource.Factory(cleanClient)
            .setUserAgent(NetworkClient.USER_AGENT)
    }

    private fun createDefaultHttpDataSourceFactory(): HttpDataSource.Factory {
        usingCronet = false
        usingDoH = false
        usingBrowserOkHttp = false
        Log.d("PlayerNetwork", "Using DefaultHttpDataSource (Java HttpURLConnection, system DNS)")
        return DefaultHttpDataSource.Factory()
            .setUserAgent(NetworkClient.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
    }

    private fun initializePlayer(extraBuffering: Boolean, softwareDecoder: Boolean = currentSoftwareDecoder, videoUrl: String = "") {
        releasePlayer()
        currentExtraBuffering = extraBuffering
        currentSoftwareDecoder = softwareDecoder

        httpDataSource = createHttpDataSourceFactory(videoUrl)

        dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)

        player = buildPlayer(extraBuffering).also { player ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )

                val lang = UserPreferences.currentProvider?.language?.substringBefore("-")
                if (lang == "es") {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setPreferredAudioLanguage("spa")
                        .build()
                }

                mediaSession = MediaSession.Builder(requireContext(), player)
                    .setId("player_mobile_${System.nanoTime()}")
                    .build()
            }

        binding.pvPlayer.player = player
        binding.settings.player = player
        binding.settings.subtitleView = binding.pvPlayer.subtitleView
        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // WebView overlay — Netu anti-bot bypass (touch-friendly for mobile)
    // ═══════════════════════════════════════════════════════════════════

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun showWebViewOverlay(embedUrl: String) {
        if (webViewOverlay != null) return
        val ctx = requireContext()
        val rootView = binding.root as ViewGroup
        m3u8Intercepted = false
        daddyLiveCdnPageUrl = null

        // ── Overlay container ──
        val overlay = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            elevation = 30f
        }

        // ── WebView (user can touch/tap directly) ──
        val wv = WebView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = NetworkClient.USER_AGENT
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                loadWithOverviewMode = true
                useWideViewPort = true
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        // Detect if this is a DaddyLive/bolaloca embed
        val isDaddyLiveEmbed = embedUrl.contains("bolaloca")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // ── DaddyLive: block ads + popups ──
                if (isDaddyLiveEmbed) {
                    val host = request?.url?.host ?: ""
                    // Block known ad / tracking / popup domains
                    val isAd = AD_BLOCK_PATTERNS.any { host.contains(it, ignoreCase = true) }
                        || url.contains("/ads/") || url.contains("/ad.")
                        || url.contains("popunder") || url.contains("pop.js")
                        || url.contains("trafficjunky") || url.contains("exoclick")
                        || url.contains("juicyads") || url.contains("clickadu")
                        || url.contains("/prebid") || url.contains("adserver")
                        || url.contains("syndication") || url.contains("banner")
                        || url.contains("/vast") || url.contains("vpaid")
                    if (isAd) {
                        Log.d("PlayerMobile", "DaddyLive AD BLOCKED: ${host}/${url.takeLast(60)}")
                        return WebResourceResponse(
                            "text/plain", "UTF-8",
                            java.io.ByteArrayInputStream("".toByteArray())
                        )
                    }
                }

                // ── DaddyLive: LOG ALL non-ad requests for CDN iframe detection ──
                if (isDaddyLiveEmbed && !m3u8Intercepted) {
                    val reqHost = request?.url?.host ?: ""
                    val isBolaloca = reqHost.contains("bolaloca") || reqHost.contains("daddylive")
                    if (!isBolaloca) {
                        val accept = request?.requestHeaders?.get("Accept") ?: ""
                        Log.d("PlayerMobile", "DaddyLive REQ [${request?.method}] host=$reqHost accept=${accept.take(40)} url=${url.take(160)}")
                    }

                    // Capture the CDN iframe page URL (the HTML page, not assets)
                    if (daddyLiveCdnPageUrl == null) {
                        val isCdnDomain = reqHost.contains("58103793") || reqHost.contains("lulustream")
                            || reqHost.contains("luluvdo") || reqHost.contains("lulucdn")
                            || reqHost.contains("cdn-tnmr") || reqHost.contains("hlsbot")
                        val isStaticAsset = url.contains(".m3u8") || url.contains(".ts")
                            || url.contains(".js") || url.contains(".css")
                            || url.contains(".png") || url.contains(".jpg")
                            || url.contains(".svg") || url.contains(".ico")
                            || url.contains(".woff") || url.contains(".gif")
                            || url.contains(".woff2") || url.contains(".ttf")
                        val accept = request?.requestHeaders?.get("Accept") ?: ""
                        val looksLikeHtml = accept.contains("text/html") || (!isStaticAsset && request?.method == "GET")
                        if (isCdnDomain && looksLikeHtml && !isStaticAsset) {
                            daddyLiveCdnPageUrl = url
                            Log.d("PlayerMobile", "DaddyLive CDN iframe URL captured: ${url.take(160)}")
                        }
                    }
                }

                // ── DaddyLive: intercept m3u8, play via ExoPlayer + WebViewDataSource ──
                if (isDaddyLiveEmbed && url.contains(".m3u8")) {
                    if (!m3u8Intercepted) {
                        Log.d("PlayerMobile", "DaddyLive M3U8 intercepted: ${url.take(140)}")
                        m3u8Intercepted = true
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onDaddyLiveM3u8Intercepted(m3u8Url = url, embedUrl = embedUrl)
                        }
                    }
                    return WebResourceResponse(
                        "application/vnd.apple.mpegurl", "UTF-8",
                        java.io.ByteArrayInputStream("#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray())
                    ).apply { responseHeaders = mapOf("Access-Control-Allow-Origin" to "*") }
                }

                // ── Netu/cfglobalcdn interception (existing logic) ──
                if (url.contains("cfglobalcdn.com")) {
                    if (m3u8Intercepted) {
                        Log.d("PlayerMobile", "Blocking cfglobalcdn request: ${url.takeLast(60)}")
                        return WebResourceResponse(
                            "text/plain", "UTF-8",
                            java.io.ByteArrayInputStream("".toByteArray())
                        ).apply {
                            responseHeaders = mapOf(
                                "Access-Control-Allow-Origin" to "*"
                            )
                        }
                    }
                    if (url.contains("silverlight") || url.contains("hls-vod")
                        || url.contains(".m3u8")
                    ) {
                        Log.d("PlayerMobile", "M3U8 intercepted from WebView: ${url.take(120)}")
                        m3u8Intercepted = true
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onM3u8Intercepted(url)
                        }
                        return WebResourceResponse(
                            "application/vnd.apple.mpegurl", "UTF-8",
                            java.io.ByteArrayInputStream(
                                "#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray()
                            )
                        ).apply {
                            responseHeaders = mapOf(
                                "Access-Control-Allow-Origin" to "*"
                            )
                        }
                    }
                }

                return null // let WebView handle all other requests natively
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("PlayerMobile", "Overlay WebView loaded: ${url?.take(80)}")

                // ── DaddyLive: inject anti-popup/ad JS ──
                if (isDaddyLiveEmbed) {
                    view?.evaluateJavascript(DADDYLIVE_AD_KILL_JS, null)
                }
            }
        }

        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                Log.d("PlayerMobile", "JS console [${consoleMessage?.messageLevel()}]: ${consoleMessage?.message()?.take(200)}")
                return true
            }
            // Block ALL popup windows (window.open)
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean {
                Log.d("PlayerMobile", "BLOCKED popup window (isDaddyLive=$isDaddyLiveEmbed)")
                return false
            }
        }

        // ── Hint text ──
        val hint = TextView(ctx).apply {
            text = if (isDaddyLiveEmbed) "Chargement du flux DaddyLive..." else "Appuyez sur le bouton play pour lancer la vidéo"
            setTextColor(Color.WHITE)
            textSize = 14f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(24, 12, 24, 12)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = 48 }
        }

        overlay.addView(wv)
        overlay.addView(hint)
        rootView.addView(overlay)

        webViewOverlay = overlay
        overlayWebView = wv

        if (isDaddyLiveEmbed) {
            // DaddyLive: load embed URL DIRECTLY (no iframe wrapper) so our
            // ad-kill JS runs in the same context as the popup-creating scripts.
            Log.d("PlayerMobile", "Loading DaddyLive embed directly: ${embedUrl.take(100)}")
            wv.loadUrl(embedUrl)
        } else {
            // Other embeds: use iframe wrapper (page expects to be in an iframe)
            val baseHost = "https://frembed.cyou/"
            val iframeWrapper = """
                <!DOCTYPE html>
                <html><head>
                <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
                <style>*{margin:0;padding:0}html,body{width:100%;height:100%;overflow:hidden;background:#000}
                iframe{width:100%;height:100%;border:none}</style>
                </head><body>
                <iframe src="$embedUrl" allow="autoplay;fullscreen;encrypted-media" allowfullscreen
                        referrerpolicy="origin"></iframe>
                </body></html>
            """.trimIndent()
            Log.d("PlayerMobile", "Loading iframe wrapper for: ${embedUrl.take(100)} (base=$baseHost)")
            wv.loadDataWithBaseURL(baseHost, iframeWrapper, "text/html", "UTF-8", null)
        }

        // Auto-fade hint after 5 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            hint.animate().alpha(0f).setDuration(600).start()
        }, 5000)
    }

    private fun hideWebViewOverlay() {
        val overlay = webViewOverlay ?: return
        val wv = overlayWebView
        webViewOverlay = null
        overlayWebView = null
        pendingWebViewVideo = null
        pendingWebViewServer = null

        wv?.let {
            try { it.stopLoading(); it.destroy() } catch (_: Exception) {}
        }
        (binding.root as? ViewGroup)?.removeView(overlay)
        Log.d("PlayerMobile", "WebView overlay hidden")
    }

    /**
     * Called when shouldInterceptRequest detects a cfglobalcdn M3U8 URL.
     * Navigate the WebView to the M3U8 URL to extract content, then play via ExoPlayer.
     */
    private fun onM3u8Intercepted(m3u8Url: String) {
        val video = pendingWebViewVideo ?: return
        val server = pendingWebViewServer ?: return

        Log.d("PlayerMobile", "onM3u8Intercepted: ${m3u8Url.take(100)}")

        // The netu embed URL is the correct Referer for cfglobalcdn
        // (not frembed.cyou — cfglobalcdn validates Referer against the netu origin)
        val netuEmbedUrl = video.webViewUrl ?: "https://netu.frembed.bond/"
        val netuOrigin = try {
            val u = java.net.URL(netuEmbedUrl); "${u.protocol}://${u.host}"
        } catch (_: Exception) { "https://netu.frembed.bond" }

        // Extract cookies from the WebView session BEFORE destroying it.
        // The anti-bot click sets session cookies that cfglobalcdn validates.
        val cookieManager = CookieManager.getInstance()
        val cfgCookies = cookieManager.getCookie(m3u8Url) ?: ""
        val netuCookies = cookieManager.getCookie(netuEmbedUrl) ?: ""
        Log.d("PlayerMobile", "Cookies for cfglobalcdn: ${cfgCookies.take(80)}")
        Log.d("PlayerMobile", "Cookies for netu: ${netuCookies.take(80)}")

        // Destroy the overlay WebView — CronetDataSource handles TLS
        hideWebViewOverlay()
        com.streamflixreborn.streamflix.extractors.NetuExtractor.sharedWebView = null

        Log.d("PlayerMobile", "Using CronetDataSource for Netu/cfglobalcdn (referer=$netuEmbedUrl)")

        // Build headers with correct Referer, Origin, and cookies
        val headers = mutableMapOf(
            "Referer" to netuEmbedUrl,
            "Origin" to netuOrigin,
        )
        if (cfgCookies.isNotBlank()) {
            headers["Cookie"] = cfgCookies
        }

        val newVideo = Video(
            source = m3u8Url,
            type = MimeTypes.APPLICATION_M3U8,
            headers = headers,
            webViewUrl = null,
            subtitles = video.subtitles
        )
        displayVideo(newVideo, server)
    }

    /**
     * Called when a DaddyLive/bolaloca WebView intercepts an m3u8 URL.
     * Creates a hidden proxy WebView that navigates to the CDN's REAL URL
     * (not loadDataWithBaseURL — that creates a synthetic origin the CDN
     * rejects). By doing loadUrl("cdnOrigin/"), the WebView gets a genuine
     * same-origin context: XHR has correct Origin/Referer headers, Chrome
     * TLS fingerprint, and shared cookies via CookieManager.
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun onDaddyLiveM3u8Intercepted(m3u8Url: String, embedUrl: String) {
        val video = pendingWebViewVideo ?: return
        val server = pendingWebViewServer ?: return

        // Extract CDN origin from the m3u8 URL INCLUDING port (e.g. https://xxx.58103793.net:8443)
        val cdnOrigin = try {
            val u = java.net.URL(m3u8Url)
            val port = if (u.port != -1 && u.port != u.defaultPort) ":${u.port}" else ""
            "${u.protocol}://${u.host}$port"
        } catch (_: Exception) { "" }

        Log.d("PlayerMobile", "DaddyLive M3U8 → ExoPlayer via WebViewDataSource (CDN origin=$cdnOrigin): ${m3u8Url.take(120)}")

        // ── 0. Clean up any previous DaddyLive proxy (prevents second player) ──
        daddyLiveProxyWebView?.let { old ->
            Log.d("PlayerMobile", "Cleaning up previous DaddyLive proxy WebView")
            try { old.stopLoading(); old.destroy() } catch (_: Exception) {}
            (binding.root as? ViewGroup)?.removeView(old)
        }
        daddyLiveProxyWebView = null

        // ── 1. Create a hidden proxy WebView ──
        val ctx = requireContext()
        val proxyWv = WebView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1) // invisible
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = NetworkClient.USER_AGENT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Allow XHR from loadDataWithBaseURL context
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
            }
        }

        // Ensure cookies are shared
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(proxyWv, true)

        // Add to view tree (required for WebView to work) but invisible
        (binding.root as ViewGroup).addView(proxyWv)
        daddyLiveProxyWebView = proxyWv

        val cdnPageUrl = daddyLiveCdnPageUrl
        Log.d("PlayerMobile", "DaddyLive proxy: cdnPageUrl=$cdnPageUrl, cdnOrigin=$cdnOrigin")

        proxyWv.webViewClient = object : WebViewClient() {
            private var started = false

            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                // BEFORE page loaded: block scripts so hls.js doesn't start its own playback
                // AFTER page loaded (started=true): let EVERYTHING through for WebViewDataSource fetch()
                if (!started && (reqUrl.endsWith(".js") || reqUrl.contains("hls.min")
                            || reqUrl.contains("hls.js") || reqUrl.contains("/js/"))) {
                    Log.d("PlayerMobile", "DaddyLive proxy: blocking script: ${reqUrl.takeLast(60)}")
                    return WebResourceResponse(
                        "text/plain", "UTF-8",
                        java.io.ByteArrayInputStream("".toByteArray())
                    )
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (started) return
                started = true
                Log.d("PlayerMobile", "DaddyLive proxy WebView loaded CDN page, url=$url")

                // ── 3. Hide the overlay WebView (stop its playback) ──
                hideWebViewOverlay()

                // ── 4. Build HLS source with WebViewDataSource ──
                val webViewDsFactory = DefaultDataSource.Factory(
                    ctx,
                    com.streamflixreborn.streamflix.utils.WebViewDataSource.Factory(proxyWv)
                )

                val mediaItem = MediaItem.Builder()
                    .setUri(m3u8Url.toUri())
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setMediaServerId(server.id)
                            .build()
                    )
                    .build()

                val hlsSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(webViewDsFactory)
                    .createMediaSource(mediaItem)

                // ── 5. Play in ExoPlayer ──
                if (!::player.isInitialized) return
                player.setMediaSource(hlsSource)
                player.prepare()
                player.playWhenReady = true
                usingWebView = true

                Log.d("PlayerMobile", "DaddyLive → ExoPlayer playing via WebViewDataSource (cdnPage=$url)")
            }
        }

        // ── 2. Load the CDN iframe page to establish the player session ──
        // The CDN validates .ts segment requests against an active session
        // created when its iframe page loads. Without this, .ts gets 403.
        if (cdnPageUrl != null) {
            Log.d("PlayerMobile", "DaddyLive proxy: loading CDN iframe page: ${cdnPageUrl.take(120)}")
            proxyWv.loadUrl(cdnPageUrl)
        } else {
            // Fallback: use loadDataWithBaseURL (m3u8 will work but .ts may 403)
            Log.w("PlayerMobile", "DaddyLive proxy: no CDN page URL captured, using loadDataWithBaseURL origin=$cdnOrigin")
            proxyWv.loadDataWithBaseURL(
                "$cdnOrigin/",
                "<html><head></head><body></body></html>",
                "text/html", "UTF-8", null
            )
        }
    }

    private fun releasePlayer() {
        stopProgressHandler()
        binding.pvPlayer.player = null
        binding.settings.player = null
        binding.settings.subtitleView = null
        if (::player.isInitialized) {
            player.release()
        }
        if (::mediaSession.isInitialized) {
            mediaSession.release()
        }
        // Release shared WebView used by WebViewDataSource
        if (usingWebView) {
            com.streamflixreborn.streamflix.extractors.LuluVdoExtractor.releaseSharedWebView()
            com.streamflixreborn.streamflix.extractors.NetuExtractor.releaseSharedWebView()
            usingWebView = false
        }
        // Release DaddyLive proxy WebView
        daddyLiveProxyWebView?.let {
            try { it.stopLoading(); it.destroy() } catch (_: Exception) {}
            (binding.root as? ViewGroup)?.removeView(it)
        }
        daddyLiveProxyWebView = null
    }

    private fun isSerienStreamBypassUrl(url: String): Boolean {
        return runCatching {
            Uri.parse(url).host.equals("s.to", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun buildSerienStreamBypassUrl(): String? {
        return null
    }

    private fun applyBypassCookies(url: String, cookieHeader: String) {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        val targets = linkedSetOf<String>().apply {
            add(url)
            if (host.isNotBlank()) {
                add("https://$host/")
                add("http://$host/")
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieHeader.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .forEach { cookie ->
                targets.forEach { target ->
                    cookieManager.setCookie(target, cookie)
                }
            }
        cookieManager.flush()
    }
}
