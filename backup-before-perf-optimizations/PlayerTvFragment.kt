package com.streamflixreborn.streamflix.fragments.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.google.android.gms.net.CronetProviderInstaller
import org.chromium.net.CronetEngine
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.SubtitleView
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.ContentExoControllerTvBinding
import com.streamflixreborn.streamflix.databinding.FragmentPlayerTvBinding
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.ui.PlayerTvView
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.MediaServer
import com.streamflixreborn.streamflix.utils.PlayerGestureHelper
import com.streamflixreborn.streamflix.utils.MiniPlayerController
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
import okhttp3.OkHttpClient
// Removed: import okhttp3.internal.userAgent — it resolves to "okhttp/4.12.0"
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import java.util.Base64
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.streamflixreborn.streamflix.utils.BypassWebSocketServer
import com.streamflixreborn.streamflix.utils.BypassWebSocketEndpointHelper
import com.streamflixreborn.streamflix.utils.QrUtils
import com.streamflixreborn.streamflix.utils.UserDataCache.toEpisode
import com.streamflixreborn.streamflix.utils.UserDataCache.toMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class PlayerTvFragment : Fragment() {
    companion object {
        private const val NEXT_EPISODE_PREFETCH_THRESHOLD_MS = 60_000L
        private const val NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS = 30_000L
        private const val NEXT_EPISODE_OVERLAY_ALPHA_UNFOCUSED = 0.72f
        private const val NEXT_EPISODE_OVERLAY_ALPHA_FOCUSED = 0.96f
        private const val CURSOR_STEP = 18f    // base pixels per D-pad press
        private const val CURSOR_SIZE_DP = 28

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
                window.open = function(){ return null; };
                window.alert = function(){};
                window.confirm = function(){ return false; };
                window.prompt = function(){ return null; };
                function killAds() {
                    document.querySelectorAll('div,iframe,section,aside').forEach(function(el){
                        var s = getComputedStyle(el);
                        var z = parseInt(s.zIndex) || 0;
                        var pos = s.position;
                        if (el.querySelector('video') || el.closest('video')) return;
                        if (el.id && (el.id.includes('player') || el.id.includes('video'))) return;
                        if (el.className && typeof el.className === 'string'
                            && (el.className.includes('player') || el.className.includes('video')
                                || el.className.includes('hls'))) return;
                        if ((pos === 'fixed' || pos === 'absolute') && z > 100) {
                            el.remove();
                        }
                    });
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

    private data class BypassSession(
        val token: String,
        val serverUrl: String,
        val bypassUrl: String,
    )

    private var _binding: FragmentPlayerTvBinding? = null
    private val binding get() = _binding!!
    private var isSetupDone = false

    private val PlayerControlView.binding
        get() = ContentExoControllerTvBinding.bind(this.findViewById(R.id.cl_exo_controller))

    private val args by navArgs<PlayerTvFragmentArgs>()
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
    private var usingWebView = false

    // ── WebView overlay with virtual cursor (Netu anti-bot bypass on TV) ──
    private var webViewOverlay: FrameLayout? = null
    private var overlayWebView: WebView? = null
    private var virtualCursorView: View? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var pendingWebViewVideo: Video? = null
    private var pendingWebViewServer: Video.Server? = null
    @Volatile private var m3u8Intercepted = false
    @Volatile private var daddyLiveCdnPageUrl: String? = null

    /** Hidden WebView on file:/// for DaddyLive WebViewDataSource (Chrome TLS + no CORS) */
    private var daddyLiveProxyWebView: WebView? = null

    private var waitingForBypass = false
    private var bypassDone = false
    private var activeBypassSession: BypassSession? = null
    private var qrDialog: androidx.appcompat.app.AlertDialog? = null
    private var wsServer: BypassWebSocketServer? = null
    private var nextEpisodePrefetchTargetId: String? = null
    private var nextEpisodePrefetchJob: Job? = null
    private var nextEpisodeOverlayDismissed = false
    private val chooserReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val clickedComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(
                        Intent.EXTRA_CHOSEN_COMPONENT,
                        android.content.ComponentName::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
                }
                Log.i(
                    "ExternalPlayer",
                    "TV - App selezionata: ${clickedComponent?.packageName ?: "Sconosciuta"}"
                )
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

    override fun onResume() {
        super.onResume()
        if (!isSetupDone) {
            requireActivity().requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val window = requireActivity().window
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            isSetupDone = true
        }

        try {
            val filter = IntentFilter("ACTION_PLAYER_CHOSEN_TV")
            ContextCompat.registerReceiver(
                requireContext(),
                chooserReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (ignored: Exception) {}
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Defer ExoPlayer creation to allow the layout to inflate and render first.
        // On Chromecast (ARM), buildPlayer() + codec enumeration can block for seconds,
        // causing ANR if done synchronously in onViewCreated.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (isAdded && _binding != null) {
                initializePlayer(false)
            }
        }
        initializeVideo()
        binding.pvPlayer.onMediaPreviousClicked = ::handleMediaPrevious
        binding.pvPlayer.onMediaNextClicked = ::handleMediaNext
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
                            waitingForBypass = true

                            val bypassUrl = buildSerienStreamBypassUrl()
                            if (bypassUrl.isNullOrBlank()) {
                                waitingForBypass = false
                                Toast.makeText(
                                    requireContext(),
                                    "Unable to prepare TV bypass page.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@collect
                            }

                            val session = BypassSession(
                                token = UUID.randomUUID().toString(),
                                serverUrl = sToServer.id,
                                bypassUrl = bypassUrl,
                            )
                            activeBypassSession = session

                            val actualPort = startWebSocketServer()
                            if (actualPort == -1) {
                                clearBypassSession()
                                Toast.makeText(
                                    requireContext(),
                                    "Unable to start TV bypass. Please try again.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@collect
                            }

                            val wsUrl = BypassWebSocketEndpointHelper.getAdvertisedWsUrl(actualPort)
                                ?: return@collect

                            val qrContent = "streamflix://resolve?ws=${Uri.encode(wsUrl)}&token=${Uri.encode(session.token)}"

                            wsServer?.registerSession(
                                session.token,
                                JSONObject()
                                    .put("url", session.bypassUrl)
                                    .toString()
                            )
                            requireActivity().runOnUiThread {
                                showQrDialog(qrContent)
                                Log.d("Bypass", "Advertised WS URL: $wsUrl")
                            }

                            return@collect
                        }



                        val providerName = UserPreferences.currentProvider?.name ?: ""
                        val isTmdb = providerName.contains("TMDb", ignoreCase = true)

                        if (servers.isEmpty()) {
                            if (providerName == "WiTV") {
                                Log.d("PlayerTvFragment", "No initial servers, waiting for OLA CID servers...")
                                PlayerSettingsView.Settings.ChannelVariant.list.clear()
                                binding.settings.refreshChannelVariantList()
                                return@collect
                            }
                            val message = if (isTmdb) {
                                val langCode = providerName.substringAfter("(").substringBefore(")")
                                val locale = Locale.forLanguageTag(langCode)
                                val langDisplayName = locale.getDisplayLanguage(Locale.getDefault())
                                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                                getString(
                                    R.string.player_not_available_lang_message,
                                    langDisplayName
                                )
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
                                // OLA channel variant fallback succeeded
                            } else {
                                // Mark channel as failed for WiTV so it's hidden next time
                                val provider = UserPreferences.currentProvider
                                if (provider is com.streamflixreborn.streamflix.providers.WiTvProvider) {
                                    UserPreferences.markChannelFailed(args.id)
                                }

                                val providerName = provider?.name ?: ""
                                val isTmdb = providerName.contains("TMDb", ignoreCase = true)

                                val message = if (isTmdb) {
                                    val langCode =
                                        providerName.substringAfter("(").substringBefore(")")
                                    val locale = Locale.forLanguageTag(langCode)
                                    val langDisplayName =
                                        locale.getDisplayLanguage(Locale.getDefault())
                                            .replaceFirstChar {
                                                if (it.isLowerCase()) it.titlecase(
                                                    Locale.getDefault()
                                                ) else it.toString()
                                            }
                                    getString(
                                        R.string.player_not_available_lang_message,
                                        langDisplayName
                                    )
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
            val currentChannelKeyTv = args.id.removePrefix("ch::").removePrefix("sport::")

            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.additionalServer.collect { server ->
                    servers = servers + server

                    if (server.name.startsWith("OLA[")) {
                        // Parse "OLA[key] label" format
                        val closeBracket = server.name.indexOf(']')
                        if (closeBracket < 0) return@collect
                        val olaKey = server.name.substring(4, closeBracket)
                        val label = server.name.substring(closeBracket + 2)

                        // Filter: ignore streams from a different channel
                        if (olaKey != currentChannelKeyTv) {
                            Log.d("PlayerTvFragment", "Ignoring stale OLA stream: ${server.name} (expected key=$currentChannelKeyTv)")
                            return@collect
                        }

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

                        binding.settings.setOnChannelVariantSelectedListener { variant ->
                            viewModel.getVideo(Video.Server(variant.id, variant.name))
                        }

                        val hasNonOla = servers.any { !it.name.startsWith("OLA[") }
                        if (!hasNonOla && PlayerSettingsView.Settings.ChannelVariant.list.size == 1) {
                            viewModel.getVideo(server)
                        }
                        Log.d("PlayerTvFragment", "OLA stream added to Chaîne: $label")
                    } else {
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
                        Log.d("PlayerTvFragment", "Additional server added: ${server.name}")
                    }

                    binding.settings.setOnServerSelectedListener { sel ->
                        servers.find { sel.id == it.id }?.let { viewModel.getVideo(it) }
                    }
                }
            }

            // Stato Sottotitoli
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.subtitleState.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
                    .collect { state ->
                        when (state) {
                            PlayerViewModel.SubtitleState.Loading -> {}
                            is PlayerViewModel.SubtitleState.SuccessOpenSubtitles -> {
                                binding.settings.openSubtitles = state.subtitles
                            }

                            is PlayerViewModel.SubtitleState.FailedOpenSubtitles -> {}

                            PlayerViewModel.SubtitleState.DownloadingOpenSubtitle -> {}
                            is PlayerViewModel.SubtitleState.SuccessDownloadingOpenSubtitle -> {
                                val fileName =
                                    state.uri.getFileName(requireContext()) ?: state.uri.toString()
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
                                                    + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                                .setMimeType(fileName.toSubtitleMimeType())
                                                .setLabel(fileName)
                                                .setLanguage(state.subtitle.languageName)
                                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                                .build()
                                        )
                                        .setMediaMetadata(player.mediaMetadata)
                                        .build()
                                )
                                UserPreferences.subtitleName =
                                    (state.subtitle.languageName ?: fileName).substringBefore(" ")
                                player.seekTo(currentPosition)
                                player.play()
                            }

                            is PlayerViewModel.SubtitleState.FailedDownloadingOpenSubtitle -> {
                                Toast.makeText(
                                    requireContext(),
                                    "${state.subtitle.subFileName}: ${state.error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            is PlayerViewModel.SubtitleState.SuccessSubDLSubtitles -> {
                                binding.settings.subDLSubtitles = state.subtitles
                            }

                            is PlayerViewModel.SubtitleState.FailedSubDLSubtitles -> {}

                            PlayerViewModel.SubtitleState.DownloadingSubDLSubtitle -> {}
                            is PlayerViewModel.SubtitleState.SuccessDownloadingSubDLSubtitle -> {
                                val fileName =
                                    state.uri.getFileName(requireContext()) ?: state.uri.toString()
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
                                                    + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                                .setMimeType(fileName.toSubtitleMimeType())
                                                .setLabel(
                                                    state.subtitle.releaseName
                                                        ?: state.subtitle.name ?: fileName
                                                )
                                                .setLanguage(
                                                    state.subtitle.lang ?: state.subtitle.language
                                                    ?: "Unknown"
                                                )
                                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                                .build()
                                        )
                                        .setMediaMetadata(player.mediaMetadata)
                                        .build()
                                )
                                UserPreferences.subtitleName =
                                    (state.subtitle.releaseName ?: state.subtitle.name
                                    ?: fileName).substringBefore(" ")
                                player.seekTo(currentPosition)
                                player.play()
                            }

                            is PlayerViewModel.SubtitleState.FailedDownloadingSubDLSubtitle -> {
                                Toast.makeText(
                                    requireContext(),
                                    "${state.subtitle.name}: ${state.error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
            }

            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.playPreviousOrNextEpisode.collect { nextEpisode ->
                        releasePlayer()
                        isSetupDone = false

                        val args = Bundle().apply {
                            putString("id", nextEpisode.id)
                            putSerializable("videoType", nextEpisode)
                            putString("title", nextEpisode.tvShow.title)
                            putString(
                                "subtitle",
                                "S${nextEpisode.season.number} E${nextEpisode.number}  •  ${nextEpisode.title}"
                            )
                        }

                        hideNextEpisodeOverlay()
                        findNavController().navigate(
                            R.id.player,
                            args,
                            NavOptions.Builder()
                                .setPopUpTo(
                                    findNavController().currentDestination?.id ?: return@collect,
                                    true
                                )
                                .setLaunchSingleTop(false)
                                .build()
                        )
                    }
                }
            }


        }

    override fun onPause() {
        super.onPause()

        if (::player.isInitialized) {
            try {
                player.pause()
            } catch (e: Exception) {
                Log.w("Player", "pause() ignored, player already released")
            }
        }

        stopProgressHandler()
        hideNextEpisodeOverlay()
    }

        override fun onDestroyView() {
            super.onDestroyView()
            hideWebViewOverlay()
            nextEpisodePrefetchJob?.cancel()
            clearBypassSession(dismissDialog = true)
            releasePlayer()
            try {
                requireContext().unregisterReceiver(chooserReceiver)
            } catch (ignored: Exception) {
            }
            _binding = null
            isSetupDone = false
        }

    fun onBackPressed(): Boolean = when {

        webViewOverlay != null -> {
            hideWebViewOverlay()
            true
        }

        (binding.pvPlayer as? PlayerTvView)?.isManualZoomEnabled == true -> {
            (binding.pvPlayer as? PlayerTvView)?.exitManualZoomMode()
            true
        }

        binding.settings.isVisible -> {
            binding.settings.onBackPressed()
        }

        binding.pvPlayer.controller.isVisible -> {
            binding.pvPlayer.hideController()
            true
        }

        else -> false
    }

    private fun handleMediaPrevious(): Boolean {
        return when (args.videoType) {
            is Video.Type.Episode -> {
                if (!EpisodeManager.hasPreviousEpisode()) return false
                viewModel.playPreviousEpisode()
                true
            }
            is Video.Type.Movie -> false
        }
    }

    private fun handleMediaNext(): Boolean {
        return when (args.videoType) {
            is Video.Type.Episode -> {
                playNextEpisodeAcrossSeasons()
                true
            }
            is Video.Type.Movie -> false
        }
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


        private fun updatePlayerScale() {
            val videoSurfaceView = binding.pvPlayer.videoSurfaceView
            val playerResize = UserPreferences.playerResize

            // Let PlayerView handle aspect ratio changes via resizeMode. Manual scale transforms on the
            // underlying surface can leave stale geometry behind after a quality switch, which is what
            // causes smaller variants to render in the top-left corner.
            binding.pvPlayer.resizeMode = playerResize.resizeMode

            videoSurfaceView?.apply {
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
                pivotX = width / 2f
                pivotY = height / 2f

                (layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                    if (
                        params.width != FrameLayout.LayoutParams.MATCH_PARENT ||
                        params.height != FrameLayout.LayoutParams.MATCH_PARENT ||
                        params.gravity != Gravity.CENTER
                    ) {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                    }
                }

                requestLayout()
            }
            binding.pvPlayer.requestLayout()
        }

        private fun reloadCurrentVideoForQualityChange() {
            val video = currentVideo ?: return
            val server = currentServer ?: return
            val resumePosition = player.currentPosition
            val shouldPlay = player.isPlaying || player.playWhenReady

            initializePlayer(currentExtraBuffering, currentSoftwareDecoder, video.source)
            player.playlistMetadata = MediaMetadata.Builder()
                .setTitle(resolvePlayerTitle())
                .setMediaServers(servers.map {
                    MediaServer(
                        id = it.id,
                        name = it.name,
                    )
                })
                .build()

            displayVideo(
                video = video,
                server = server,
                startPositionMs = resumePosition,
                shouldPlay = shouldPlay,
            )
        }

        private fun initializeVideo() {
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
            setupEpisodeNavigationButtons()
            binding.pvPlayer.resizeMode = UserPreferences.playerResize.resizeMode
            binding.pvPlayer.subtitleView?.apply {
                setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize)
                setStyle(UserPreferences.captionStyle)
                setPadding(0, 0, 0, UserPreferences.captionMargin.dp(context))
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

            binding.pvPlayer.controller.binding.exoProgress.setKeyTimeIncrement(10_000)

            binding.pvPlayer.controller.binding.btnExoAspectRatio.setOnClickListener {
                val newResize = UserPreferences.playerResize.next()
                zoomToast?.cancel()
                zoomToast =
                    Toast.makeText(requireContext(), newResize.stringRes, Toast.LENGTH_SHORT)
                zoomToast?.show()

                UserPreferences.playerResize = newResize
                binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
                updatePlayerScale()
            }

            binding.pvPlayer.controller.binding.exoSettings.setOnClickListener {
                binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
                binding.settings.show()
            }

            binding.pvPlayer.controller.binding.btnSkipIntro.setOnClickListener {
                player.seekTo(player.currentPosition + 85000)
                it.visibility = View.GONE
            }

            binding.btnNextEpisodeAction.setOnClickListener {
                hideNextEpisodeOverlay()
                playNextEpisodeAcrossSeasons()
            }
            binding.btnNextEpisodeDismiss.setOnClickListener {
                nextEpisodeOverlayDismissed = true
                hideNextEpisodeOverlay()
            }
            binding.btnNextEpisodeAction.setOnFocusChangeListener { _, hasFocus ->
                updateNextEpisodeOverlayAlpha(hasFocus || binding.btnNextEpisodeDismiss.hasFocus())
            }
            binding.btnNextEpisodeDismiss.setOnFocusChangeListener { _, hasFocus ->
                updateNextEpisodeOverlayAlpha(hasFocus || binding.btnNextEpisodeAction.hasFocus())
            }

            binding.settings.setOnLocalSubtitlesClickedListener {
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
            binding.settings.setOnQualitySelectedListener {
                reloadCurrentVideoForQualityChange()
            }
            binding.settings.setOnExtraBufferingSelectedListener {
                displayVideo(
                    currentVideo ?: return@setOnExtraBufferingSelectedListener,
                    currentServer ?: return@setOnExtraBufferingSelectedListener
                )
            }
            binding.settings.onManualZoomClicked = {
                binding.settings.hide()
                binding.pvPlayer.hideController()
                (binding.pvPlayer as? PlayerTvView)?.enterManualZoomMode()
                binding.pvPlayer.requestFocus()
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
                    button.visibility = View.GONE
                    return
                }

                button.visibility = View.VISIBLE
                button.setOnClickListener {
                    if (!hasEpisode()) return@setOnClickListener

                    val videoType = args.videoType
                    val watchItem: WatchItem? = when (videoType) {
                        is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                        is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                    }

                    watchItem?.apply {
                        isWatched = false
                        watchedDate = null
                        watchHistory = WatchItem.WatchHistory(
                            lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                            lastPlaybackPositionMillis = player.currentPosition,
                            durationMillis = player.duration
                        )
                    }

                    when (videoType) {
                        is Video.Type.Movie -> {
                            val provider = UserPreferences.currentProvider ?: return@setOnClickListener
                            (watchItem as? Movie)?.let { database.movieDao().update(it) }
                            (watchItem as? Movie)?.let { UserDataCache.addMovieToContinueWatching(requireContext(), provider, it) }
                        }

                        is Video.Type.Episode -> {
                            val provider = UserPreferences.currentProvider ?: return@setOnClickListener
                            (watchItem as? Episode)?.let { episode ->
                                if (player.hasFinished()) {
                                    episode.isWatched = true
                                    episode.watchedDate = Calendar.getInstance()
                                    episode.watchHistory = null
                                    database.episodeDao().resetProgressionFromEpisode(videoType.id)
                                    UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, episode.id)
                                }

                                database.episodeDao().update(episode)
                                if (!player.hasFinished()) {
                                    (watchItem as? Episode)?.let { UserDataCache.addEpisodeToContinueWatching(requireContext(), provider, it) }
                                }

                                episode.tvShow?.let { tvShow ->
                                    database.tvShowDao().getById(tvShow.id)
                                }?.let { tvShow ->

                                    val isWatchingValue = if (player.hasFinished()) {
                                        database.episodeDao().hasAnyWatchHistoryForTvShow(tvShow.id)
                                    } else {
                                        true
                                    }

                                    database.tvShowDao().save(tvShow.copy().apply {
                                        merge(tvShow)
                                        isWatching = isWatchingValue
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
            handleNavigationButton(
                btnNext,
                EpisodeManager::hasNextEpisode,
                ::playNextEpisodeAcrossSeasons
            )
        }

        private fun setupChannelNavigationButtons(btnPrevious: ImageView, btnNext: ImageView) {
            val provider = UserPreferences.currentProvider
            if (provider !is WiTvProvider) {
                btnPrevious.visibility = View.GONE
                btnNext.visibility = View.GONE
                return
            }

            val prevId = provider.getPreviousChannelId(args.id)
            val nextId = provider.getNextChannelId(args.id)

            btnPrevious.visibility = if (prevId != null) View.VISIBLE else View.GONE
            btnNext.visibility = if (nextId != null) View.VISIBLE else View.GONE

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
        private val triedChannelVariantIds = mutableSetOf<String>()

        private fun tryNextChannelVariant(failedServer: Video.Server?): Boolean {
            val variants = PlayerSettingsView.Settings.ChannelVariant.list
            if (variants.isEmpty()) return false

            if (failedServer != null) triedChannelVariantIds.add(failedServer.id)

            val nextVariant = variants.firstOrNull { it.id !in triedChannelVariantIds }
            if (nextVariant != null) {
                triedChannelVariantIds.add(nextVariant.id)
                Log.d("PlayerTvFragment", "Fallback → trying channel variant: ${nextVariant.name}")
                viewModel.getVideo(Video.Server(nextVariant.id, nextVariant.name))
                return true
            }

            Log.d("PlayerTvFragment", "No more channel variants to try (tried ${triedChannelVariantIds.size})")
            return false
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
         * that will always 403 with any non-WebView HTTP client.
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

        private fun isNetuCfglobalcdn(video: Video): Boolean {
            return video.webViewUrl != null &&
                   com.streamflixreborn.streamflix.extractors.NetuExtractor.sharedWebView != null &&
                   (video.source.startsWith("data:") || video.source.contains("cfglobalcdn.com"))
        }

        private fun syncWebViewCookies(sourceUrl: String) {
            try {
                val uri = java.net.URI(sourceUrl)
                val host = uri.host ?: return
                if (java.net.CookieHandler.getDefault() == null) {
                    java.net.CookieHandler.setDefault(java.net.CookieManager())
                }
                val cookieHandler = java.net.CookieHandler.getDefault() as? java.net.CookieManager ?: return
                val webViewCookies = android.webkit.CookieManager.getInstance().getCookie("https://$host")
                if (webViewCookies.isNullOrBlank()) return
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
                Log.d("PlayerNetwork", "Synced WebView cookies for $host")
            } catch (e: Exception) {
                Log.w("PlayerNetwork", "Cookie sync failed: ${e.message}")
            }
        }

        private fun displayVideo(
            video: Video,
            server: Video.Server,
            startPositionMs: Long? = null,
            shouldPlay: Boolean = true,
        ) {
            currentVideo = video
            currentServer = server
            updatePlayerHeader()

            // Clean up any existing WebView overlay (e.g. switching servers)
            if (webViewOverlay != null && !(video.needsWebViewClick && !video.webViewUrl.isNullOrBlank())) {
                hideWebViewOverlay()
            }

            // ── Netu anti-bot: show WebView overlay so user can click the play button ──
            if (video.needsWebViewClick && !video.webViewUrl.isNullOrBlank()) {
                // Clean up any existing overlay first (prevents second player stacking)
                if (webViewOverlay != null) {
                    Log.d("PlayerTV", "Cleaning previous overlay before new one: ${video.webViewUrl?.take(60)}")
                    hideWebViewOverlay()
                }
                // Clean up DaddyLive proxy if any
                daddyLiveProxyWebView?.let { old ->
                    try { old.stopLoading(); old.destroy() } catch (_: Exception) {}
                    (binding.root as? ViewGroup)?.removeView(old)
                }
                daddyLiveProxyWebView = null

                Log.d("PlayerTV", "needsWebViewClick → showing WebView overlay for: ${video.webViewUrl}")
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
            val needsWebViewDs = (isLuluVdoCdn(video)
                && video.source.startsWith("data:")
                && com.streamflixreborn.streamflix.extractors.LuluVdoExtractor.sharedWebView != null)
                || isNetuCfglobalcdn(video)
            if (isLuluVdoCdn(video) || isNetuCfglobalcdn(video)) {
                Log.d("PlayerNetwork", "WebView bypass: source is ${if (video.source.startsWith("data:")) "data URI" else "CDN URL"}, webViewDs=$needsWebViewDs, lulu=${isLuluVdoCdn(video)}, netu=${isNetuCfglobalcdn(video)}")
            }

            val extraBuffering = PlayerSettingsView.Settings.ExtraBuffering.isEnabled
            val softwareDecoder = PlayerSettingsView.Settings.SoftwareDecoder.isEnabled

            // Switch DataSource if the video URL needs a different engine.
            // Cronet for vidzy.live (JA3 bypass); DoH for cfglobalcdn; Default for rest.
            val urlNeedsCronet = needsCronet(video.source)
            val urlNeedsDoH = needsDoH(video.source)
            val dataSourceMismatch = (urlNeedsCronet && !usingCronet) || (!urlNeedsCronet && usingCronet)
                || (urlNeedsDoH && !usingDoH) || (!urlNeedsDoH && usingDoH)
            val needsReinit =
                extraBuffering != currentExtraBuffering || softwareDecoder != currentSoftwareDecoder || dataSourceMismatch

            if (dataSourceMismatch) {
                Log.d("PlayerNetwork", "DataSource mismatch: needsCronet=$urlNeedsCronet(was=$usingCronet), needsDoH=$urlNeedsDoH(was=$usingDoH) → switching")
                httpDataSource = createHttpDataSourceFactory(video.source)
            }

            if (needsReinit) {
                initializePlayer(extraBuffering, softwareDecoder, video.source)
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

            val currentPosition = startPositionMs ?: player.currentPosition

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

            if (!needsWebViewDs) {
                httpDataSource.setDefaultRequestProperties(
                    mapOf(
                        "User-Agent" to NetworkClient.USER_AGENT,
                    ) + (video.headers ?: emptyMap())
                )
            }

            if (needsWebViewDs) {
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
                Log.d("PlayerNetwork", "WebView bypass: using WebViewDataSource for HLS playback")
            } else {
                player.setMediaItem(mediaItem)
                usingWebView = false
            }

            binding.pvPlayer.controller.binding.btnExoExternalPlayer.setOnClickListener {
                val videoTitle = when (val type = args.videoType) {
                    is Video.Type.Movie -> type.title
                    is Video.Type.Episode -> "${type.tvShow.title} • S${type.season.number} E${type.number}"
                }

                var sourceUri: Uri
                val mimeType = "video/*"

                val initialSource = video.source

                if (initialSource.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
                    val playlistContent = decodeBase64Uri(initialSource)
                    val extractedUrl =
                        if (playlistContent != null) extractUrlFromPlaylist(playlistContent) else null

                    if (extractedUrl != null) {
                        sourceUri = extractedUrl.toUri()
                        Log.i("ExternalPlayer", "Link reale estratto TV: $sourceUri")
                    } else {
                        try {
                            val file = File(requireContext().cacheDir, "stream.m3u8")
                            FileOutputStream(file).use {
                                it.write(
                                    playlistContent?.toByteArray() ?: ByteArray(0)
                                )
                            }
                            sourceUri = FileProvider.getUriForFile(
                                requireContext(),
                                "${requireContext().packageName}.provider",
                                file
                            )
                        } catch (ignored: Exception) {
                            sourceUri = initialSource.toUri()
                        }
                    }
                } else {
                    sourceUri = initialSource.toUri()
                }

                Log.i("ExternalPlayer", "Avvio intent TV con URI: $sourceUri e MIME: $mimeType")

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(sourceUri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                    putExtra("title", videoTitle)
                    putExtra("position", player.currentPosition.toInt())
                    putExtra("return_result", true)

                    video.headers?.forEach { (key, value) ->
                        putExtra(key, value)
                    }

                    putExtra(
                        "extra_headers",
                        video.headers?.map { "${it.key}: ${it.value}" }?.toTypedArray()
                    )

                    if (video.headers != null) {
                        val headersArray =
                            video.headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
                        putExtra("headers", headersArray)
                    }
                }

                try {
                    val receiverIntent = Intent("ACTION_PLAYER_CHOSEN_TV").apply {
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
                        startActivity(
                            Intent.createChooser(
                                intent,
                                getString(R.string.player_external_player_title)
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("ExternalPlayer", "Errore selettore app TV", e)
                    startActivity(
                        Intent.createChooser(
                            intent,
                            getString(R.string.player_external_player_title)
                        )
                    )
                }
            }

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)

                    if (playbackState == Player.STATE_READY) {
                        binding.pvPlayer.controller.binding.exoPlayPause.nextFocusDownId = -1
                        val videoFormat = player.videoFormat
                        updatePlayerScale()
                    }
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    super.onTracksChanged(tracks)
                    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    val videoTracks = videoGroups.sumOf { it.length }
                    val selectedHeights = buildList {
                        videoGroups.forEach { group ->
                            for (i in 0 until group.length) {
                                if (group.isTrackSelected(i)) {
                                    add(group.getTrackFormat(i).height)
                                }
                            }
                        }
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    super.onVideoSizeChanged(videoSize)
                    updatePlayerScale()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.pvPlayer.keepScreenOn = isPlaying

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
                                (watchItem as? Movie)?.let {
                                    database.movieDao().update(it)
                                    UserDataCache.syncMovieToCache(requireContext(), provider, it)
                                }
                            }

                            is Video.Type.Episode -> {
                                val provider = UserPreferences.currentProvider ?: return
                                (watchItem as? Episode)?.let { episode ->
                                    if (player.hasFinished()) {
                                        database.episodeDao()
                                            .resetProgressionFromEpisode(videoType.id)
                                        UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, episode.id)
                                        queueNextEpisodeForContinueWatching(provider)
                                    }
                                    database.episodeDao().update(episode)
                                    if (!player.hasFinished()) {
                                        UserDataCache.syncEpisodeToCache(requireContext(), provider, episode)
                                    }

                                    episode.tvShow?.let { tvShow ->
                                        database.tvShowDao().getById(tvShow.id)
                                    }?.let { tvShow ->
                                        val episodeDao = database.episodeDao()
                                        val isStillWatching =
                                            episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                                        database.tvShowDao().save(tvShow.copy().apply {
                                            merge(tvShow)
                                            isWatching =
                                                !player.hasReallyFinished() || isStillWatching
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
                    Log.e("PlayerTvFragment", "onPlayerError: ", error)

                    val cause = error.cause?.cause
                    val causeMsg = cause?.message ?: ""
                    val errorCauseMsg = error.cause?.message ?: ""

                    // Fallback 1: if Cronet hit a network error, retry with OkHttp
                    val isCronetNetworkError = causeMsg.contains("ERR_CONNECTION_TIMED_OUT")
                            || causeMsg.contains("ERR_CONNECTION_REFUSED")
                            || causeMsg.contains("ERR_CONNECTION_RESET")
                            || causeMsg.contains("ERR_NAME_NOT_RESOLVED")
                            || causeMsg.contains("ERR_SSL")
                            || causeMsg.contains("ERR_NETWORK")
                            || cause is CronetDataSource.OpenException
                    if (usingCronet && isCronetNetworkError) {
                        Log.w("PlayerNetwork", "Cronet network error ($causeMsg), retrying with OkHttp fallback")
                        val video = currentVideo ?: return
                        val server = currentServer ?: return
                        httpDataSource = createDefaultHttpDataSourceFactory()
                        dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)
                        initializePlayer(currentExtraBuffering, currentSoftwareDecoder, video.source)
                        displayVideo(video, server)
                        return
                    }

                    // Fallback 2: if connection timed out, ISP-blocked, or WebView fetch failed,
                    // automatically try the next server
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

            if (startPositionMs != null) {
                player.seekTo(startPositionMs)
            } else if (currentPosition == 0L) {
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
            player.playWhenReady = shouldPlay
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
        updateNextEpisodeOverlayFocusBindings(true)
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
            val fadeIn = android.view.animation.AnimationUtils.loadAnimation(
                requireContext(),
                R.anim.fade_in
            )
            updateNextEpisodeOverlayAlpha(
                binding.btnNextEpisodeAction.hasFocus() || binding.btnNextEpisodeDismiss.hasFocus()
            )
            binding.layoutNextEpisodeOverlay.startAnimation(fadeIn)
            binding.layoutNextEpisodeOverlay.isVisible = true
            binding.btnNextEpisodeAction.post {
                if (_binding == null || !binding.layoutNextEpisodeOverlay.isVisible) return@post
                binding.btnNextEpisodeAction.requestFocus()
            }
        }
    }

    private fun hideNextEpisodeOverlay() {
        if (_binding == null) return
        updateNextEpisodeOverlayFocusBindings(false)
        if (binding.layoutNextEpisodeOverlay.isVisible) {
            val fadeOut = android.view.animation.AnimationUtils.loadAnimation(
                requireContext(),
                R.anim.fade_out
            )
            binding.layoutNextEpisodeOverlay.startAnimation(fadeOut)
            binding.layoutNextEpisodeOverlay.isGone = true
        }
    }

    private fun updateNextEpisodeOverlayAlpha(hasFocus: Boolean) {
        if (_binding == null) return
        binding.layoutNextEpisodeOverlay.alpha =
            if (hasFocus) NEXT_EPISODE_OVERLAY_ALPHA_FOCUSED
            else NEXT_EPISODE_OVERLAY_ALPHA_UNFOCUSED
    }

    private fun updateNextEpisodeOverlayFocusBindings(overlayVisible: Boolean) {
        val controllerBinding = binding.pvPlayer.controller.binding
        val overlayActionId = binding.btnNextEpisodeAction.id
        val overlayDismissId = binding.btnNextEpisodeDismiss.id

        controllerBinding.exoSettings.nextFocusUpId = if (overlayVisible) overlayActionId else View.NO_ID
        controllerBinding.btnExoAspectRatio.nextFocusUpId = if (overlayVisible) overlayActionId else View.NO_ID
        controllerBinding.exoProgress.nextFocusUpId = View.NO_ID
        controllerBinding.btnCustomNext.nextFocusDownId = R.id.exo_progress
        controllerBinding.exoPlayPause.nextFocusDownId = R.id.exo_progress

        controllerBinding.btnSkipIntro.nextFocusLeftId = if (overlayVisible) overlayActionId else View.NO_ID
        controllerBinding.btnSkipIntro.nextFocusUpId = if (overlayVisible) overlayActionId else View.NO_ID
        controllerBinding.btnSkipIntro.nextFocusDownId = if (overlayVisible) overlayActionId else View.NO_ID

        binding.btnNextEpisodeAction.nextFocusLeftId = overlayDismissId
        binding.btnNextEpisodeAction.nextFocusRightId = overlayDismissId
        binding.btnNextEpisodeAction.nextFocusUpId = controllerBinding.exoPlayPause.id
        binding.btnNextEpisodeAction.nextFocusDownId =
            if (controllerBinding.btnSkipIntro.isVisible) controllerBinding.btnSkipIntro.id
            else controllerBinding.exoSettings.id

        binding.btnNextEpisodeDismiss.nextFocusLeftId = overlayActionId
        binding.btnNextEpisodeDismiss.nextFocusRightId = overlayActionId
        binding.btnNextEpisodeDismiss.nextFocusUpId = controllerBinding.exoPlayPause.id
        binding.btnNextEpisodeDismiss.nextFocusDownId =
            if (controllerBinding.btnSkipIntro.isVisible) controllerBinding.btnSkipIntro.id
            else controllerBinding.exoSettings.id
    }

        private fun showSkipIntroButton(show: Boolean) {
            val btnSkipIntro = binding.pvPlayer.controller.binding.btnSkipIntro
            if (show && btnSkipIntro.isGone) {
                val fadeIn = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(),
                    R.anim.fade_in
                )
                btnSkipIntro.startAnimation(fadeIn)
                btnSkipIntro.isVisible = true
                if (binding.layoutNextEpisodeOverlay.isVisible) {
                    updateNextEpisodeOverlayFocusBindings(true)
                }
            } else if (!show && btnSkipIntro.isVisible) {
                val fadeOut = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(),
                    R.anim.fade_out
                )
                btnSkipIntro.startAnimation(fadeOut)
                btnSkipIntro.isGone = true
                if (binding.layoutNextEpisodeOverlay.isVisible) {
                    updateNextEpisodeOverlayFocusBindings(true)
                }
            }
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
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setLoadControl(loadControl)
                .build()
        }

        private val isEmulator: Boolean by lazy {
            // Standard Build property checks (works for most emulators)
            val buildCheck = (android.os.Build.FINGERPRINT.contains("generic", ignoreCase = true)
                    || android.os.Build.MODEL.contains("Emulator", ignoreCase = true)
                    || android.os.Build.MODEL.contains("Android SDK", ignoreCase = true)
                    || android.os.Build.MANUFACTURER.contains("BlueStacks", ignoreCase = true)
                    || android.os.Build.BOARD.contains("goldfish", ignoreCase = true)
                    || android.os.Build.HARDWARE.contains("ranchu", ignoreCase = true)
                    || android.os.Build.PRODUCT.contains("sdk", ignoreCase = true)
                    || android.os.Build.PRODUCT.contains("vbox", ignoreCase = true))
            if (buildCheck) return@lazy true

            // BlueStacks spoofs Build properties as real device (e.g. OnePlus NE2211).
            // Detect via its always-present system packages.
            val blueStacksPackages = listOf(
                "com.bluestacks.BstCommandProcessor",
                "com.bluestacks.settings",
                "com.bluestacks.home",
                "com.bluestacks.appmart"
            )
            val pm = try { requireContext().packageManager } catch (_: Exception) { null }
            val isBlueStacks = pm != null && blueStacksPackages.any { pkg ->
                try {
                    pm.getPackageInfo(pkg, 0)
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (isBlueStacks) {
                Log.d("PlayerNetwork", "BlueStacks detected via package check")
            }

            // Also check system property ro.bst.version (BlueStacks-specific)
            val bstVersion = try {
                @Suppress("PrivateApi")
                val clazz = Class.forName("android.os.SystemProperties")
                val get = clazz.getMethod("get", String::class.java, String::class.java)
                (get.invoke(null, "ro.bst.version", "") as? String)?.isNotEmpty() == true
            } catch (_: Exception) { false }
            if (bstVersion) {
                Log.d("PlayerNetwork", "BlueStacks detected via ro.bst.version property")
            }

            isBlueStacks || bstVersion
        }

        /** Only vidzy.live requires Cronet (JA3 fingerprint bypass). */
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

        /**
         * Creates the right DataSource factory for [videoUrl].
         * - vidzy.live → Cronet (Chrome network stack, needed for JA3 bypass)
         * - cfglobalcdn.com / netu.tv → OkHttp + DoH (ISP DNS can't resolve CNAME chains)
         * - everything else → DefaultHttpDataSource (system DNS, most compatible)
         */
        private fun createHttpDataSourceFactory(videoUrl: String = ""): HttpDataSource.Factory {
            if (!needsCronet(videoUrl)) {
                if (needsDoH(videoUrl)) {
                    Log.d("PlayerNetwork", "URL needs DoH for CNAME resolution ($videoUrl)")
                    return createDoHOkHttpDataSourceFactory()
                }
                Log.d("PlayerNetwork", "URL does not need Cronet ($videoUrl), using DefaultHttp")
                return createDefaultHttpDataSourceFactory()
            }
            // On emulators, Cronet often can't reach CDNs — fall back
            if (isEmulator) {
                Log.d("PlayerNetwork", "Emulator detected (${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}), using fallback")
                return if (needsDoH(videoUrl)) createDoHOkHttpDataSourceFactory() else createDefaultHttpDataSourceFactory()
            }
            return try {
                val cronetEngine = CronetEngine.Builder(requireContext()).build()
                Log.d("PlayerNetwork", "Using CronetDataSource for vidzy.live (Chrome network stack)")
                usingCronet = true
                usingDoH = false
                CronetDataSource.Factory(cronetEngine, java.util.concurrent.Executors.newCachedThreadPool())
                    .setUserAgent(NetworkClient.USER_AGENT)
                    .setConnectionTimeoutMs(30_000)
                    .setReadTimeoutMs(30_000)
            } catch (e: Exception) {
                Log.w("PlayerNetwork", "Cronet unavailable, using fallback: ${e.message}")
                createDefaultHttpDataSourceFactory()
            }
        }

        /**
         * OkHttp DataSource with a custom DNS resolver that uses Cloudflare's JSON
         * DoH API to follow CNAME chains. Required for cfglobalcdn.com subdomains:
         * ISP DNS blocks them, and OkHttp's wire-format DoH may not follow CNAMEs.
         * This resolver queries the JSON API, follows CNAME → A, and returns the IP.
         * The original hostname is kept in the URL for correct TLS/SNI/Host headers.
         */
        private fun createDoHOkHttpDataSourceFactory(): HttpDataSource.Factory {
            usingCronet = false
            usingDoH = true

            val jsonDohDns = object : okhttp3.Dns {
                private val fallback = DnsResolver.doh
                private val dnsClient = OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                private val dohProviders = listOf(
                    "https://cloudflare-dns.com/dns-query",
                    "https://dns.google/resolve",
                    "https://dns.quad9.net:5053/dns-query"
                )

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
                            1 -> ips.add(answer.optString("data"))
                            5 -> cnames.add(answer.optString("data").trimEnd('.'))
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
                        val allIps = linkedSetOf<String>()
                        var cnameTarget: String? = null

                        for (provider in dohProviders) {
                            try {
                                val (ips, cnames) = queryDoH(provider, hostname)
                                Log.d("PlayerNetwork", "DoH ($provider): IPs=$ips, CNAMEs=$cnames")
                                allIps.addAll(ips)
                                if (cnames.isNotEmpty() && cnameTarget == null) cnameTarget = cnames.first()
                            } catch (e: Exception) {
                                Log.w("PlayerNetwork", "DoH provider $provider failed: ${e.message}")
                            }
                        }

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
                                break
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

        private fun createDefaultHttpDataSourceFactory(): HttpDataSource.Factory {
            usingCronet = false
            usingDoH = false
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

            // Domain-aware DataSource: Cronet only for vidzy.live (JA3 bypass),
            // OkHttp (system DNS) for everything else.
            // If displayVideo already set httpDataSource (dataSourceMismatch path),
            // don't overwrite it.
            if (!::httpDataSource.isInitialized) {
                httpDataSource = createHttpDataSourceFactory(videoUrl)
            }

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
                        player.trackSelectionParameters =
                            player.trackSelectionParameters.buildUpon()
                                .setPreferredAudioLanguage("spa")
                                .build()
                    }

                    mediaSession = MediaSession.Builder(requireContext(), player)
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
        // WebView overlay with virtual cursor — Netu anti-bot bypass on TV
        // ═══════════════════════════════════════════════════════════════════

        @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
        private fun showWebViewOverlay(embedUrl: String) {
            if (webViewOverlay != null) return // already showing
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
                isFocusable = true
                isFocusableInTouchMode = true
            }

            // ── WebView ──
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
                        val isAd = AD_BLOCK_PATTERNS.any { host.contains(it, ignoreCase = true) }
                            || url.contains("/ads/") || url.contains("/ad.")
                            || url.contains("popunder") || url.contains("pop.js")
                            || url.contains("trafficjunky") || url.contains("exoclick")
                            || url.contains("juicyads") || url.contains("clickadu")
                            || url.contains("/prebid") || url.contains("adserver")
                            || url.contains("syndication") || url.contains("banner")
                            || url.contains("/vast") || url.contains("vpaid")
                        if (isAd) {
                            Log.d("PlayerTV", "DaddyLive AD BLOCKED: ${host}/${url.takeLast(60)}")
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
                            Log.d("PlayerTV", "DaddyLive REQ [${request?.method}] host=$reqHost accept=${accept.take(40)} url=${url.take(160)}")
                        }

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
                                Log.d("PlayerTV", "DaddyLive CDN iframe URL captured: ${url.take(160)}")
                            }
                        }
                    }

                    // ── DaddyLive: intercept m3u8, play via ExoPlayer + WebViewDataSource ──
                    if (isDaddyLiveEmbed && url.contains(".m3u8")) {
                        if (!m3u8Intercepted) {
                            Log.d("PlayerTV", "DaddyLive M3U8 intercepted: ${url.take(140)}")
                            m3u8Intercepted = true
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onDaddyLiveM3u8Intercepted(m3u8Url = url, embedUrl = embedUrl)
                            }
                        }
                        return WebResourceResponse(
                            "application/vnd.apple.mpegurl", "UTF-8",
                            java.io.ByteArrayInputStream(
                                "#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray()
                            )
                        ).apply {
                            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                        }
                    }

                    if (url.contains("cfglobalcdn.com")) {
                        // Once M3U8 is captured, block ALL cfglobalcdn requests to
                        // prevent HLS.js from consuming the single-use token.
                        if (m3u8Intercepted) {
                            Log.d("PlayerTV", "Blocking cfglobalcdn request: ${url.takeLast(60)}")
                            return WebResourceResponse(
                                "text/plain", "UTF-8",
                                java.io.ByteArrayInputStream("".toByteArray())
                            ).apply {
                                responseHeaders = mapOf(
                                    "Access-Control-Allow-Origin" to "*"
                                )
                            }
                        }
                        // Capture the first HLS URL (contains silverlight/hls-vod path).
                        // Non-HLS resources (thumbnails, posters) go through normally.
                        if (url.contains("silverlight") || url.contains("hls-vod")
                            || url.contains(".m3u8")
                        ) {
                            Log.d("PlayerTV", "M3U8 intercepted from WebView: ${url.take(120)}")
                            m3u8Intercepted = true
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onM3u8Intercepted(url)
                            }
                            // Return a fake empty M3U8 with CORS headers so HLS.js
                            // doesn't retry (CORS error triggers manifestLoadError retries)
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
                    Log.d("PlayerTV", "Overlay WebView loaded: ${url?.take(80)}")

                    // ── DaddyLive: inject anti-popup/ad JS ──
                    if (isDaddyLiveEmbed) {
                        view?.evaluateJavascript(DADDYLIVE_AD_KILL_JS, null)
                    }
                }
            }

            // Block ALL popup windows (window.open)
            wv.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean,
                    isUserGesture: Boolean, resultMsg: android.os.Message?
                ): Boolean {
                    Log.d("PlayerTV", "BLOCKED popup window")
                    return false
                }
            }

            // ── Virtual cursor ──
            val cursorSizePx = (CURSOR_SIZE_DP * ctx.resources.displayMetrics.density).toInt()
            val cursor = View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(cursorSizePx, cursorSizePx)
                background = createCursorDrawable(ctx)
                elevation = 35f
            }

            // ── Hint text ──
            val hint = TextView(ctx).apply {
                text = if (isDaddyLiveEmbed) "Chargement du flux DaddyLive..." else "Utilisez les flèches pour déplacer, OK pour cliquer"
                setTextColor(Color.WHITE)
                textSize = 14f
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                setBackgroundColor(Color.parseColor("#99000000"))
                setPadding(24, 12, 24, 12)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = 32 }
            }

            overlay.addView(wv)
            overlay.addView(cursor)
            overlay.addView(hint)
            rootView.addView(overlay)

            // Center cursor initially
            overlay.post {
                cursorX = overlay.width / 2f
                cursorY = overlay.height / 2f
                updateCursorPosition(cursor)
            }

            // D-pad key handling
            overlay.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                // Acceleration when holding the key
                val speed = when {
                    event.repeatCount > 30 -> CURSOR_STEP * 5f
                    event.repeatCount > 15 -> CURSOR_STEP * 3f
                    event.repeatCount > 5 -> CURSOR_STEP * 2f
                    else -> CURSOR_STEP
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        cursorY = (cursorY - speed).coerceAtLeast(0f)
                        updateCursorPosition(cursor); true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        cursorY = (cursorY + speed).coerceAtMost((overlay.height).toFloat())
                        updateCursorPosition(cursor); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        cursorX = (cursorX - speed).coerceAtLeast(0f)
                        updateCursorPosition(cursor); true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        cursorX = (cursorX + speed).coerceAtMost((overlay.width).toFloat())
                        updateCursorPosition(cursor); true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        dispatchClickToWebView(wv, cursorX, cursorY)
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        hideWebViewOverlay()
                        true
                    }
                    else -> false
                }
            }

            overlay.requestFocus()
            webViewOverlay = overlay
            overlayWebView = wv
            virtualCursorView = cursor

            if (isDaddyLiveEmbed) {
                // DaddyLive: load embed URL DIRECTLY (no iframe wrapper) so our
                // ad-kill JS runs in the same context as the popup-creating scripts.
                Log.d("PlayerTV", "Loading DaddyLive embed directly: ${embedUrl.take(100)}")
                wv.loadUrl(embedUrl)
            } else {
                // Other embeds: use iframe wrapper (page expects to be in an iframe)
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
                val baseHost = "https://frembed.cyou/"
                Log.d("PlayerTV", "Loading iframe wrapper for: ${embedUrl.take(100)} (base=$baseHost)")
                wv.loadDataWithBaseURL(baseHost, iframeWrapper, "text/html", "UTF-8", null)
            }

            // Auto-fade hint after 6 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                hint.animate().alpha(0f).setDuration(600).start()
            }, 6000)
        }

        private fun hideWebViewOverlay() {
            val overlay = webViewOverlay ?: return
            val wv = overlayWebView
            webViewOverlay = null
            overlayWebView = null
            virtualCursorView = null
            pendingWebViewVideo = null
            pendingWebViewServer = null

            wv?.let {
                try { it.stopLoading(); it.destroy() } catch (_: Exception) {}
            }
            (binding.root as? ViewGroup)?.removeView(overlay)
            Log.d("PlayerTV", "WebView overlay hidden")
        }

        private fun updateCursorPosition(cursor: View) {
            cursor.translationX = cursorX - cursor.width / 2f
            cursor.translationY = cursorY - cursor.height / 2f
        }

        private fun dispatchClickToWebView(wv: WebView, x: Float, y: Float) {
            val downTime = SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0
            )
            val upEvent = MotionEvent.obtain(
                downTime, downTime + 80, MotionEvent.ACTION_UP, x, y, 0
            )
            wv.dispatchTouchEvent(downEvent)
            wv.dispatchTouchEvent(upEvent)
            downEvent.recycle()
            upEvent.recycle()
            Log.d("PlayerTV", "Virtual click dispatched at ($x, $y)")
        }

        private fun createCursorDrawable(ctx: android.content.Context): android.graphics.drawable.Drawable {
            // Outer ring (white with semi-transparent fill)
            val outerRing = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#30FFFFFF"))
                setStroke(
                    (2 * ctx.resources.displayMetrics.density).toInt(),
                    Color.WHITE
                )
            }
            // Inner dot (red)
            val innerDot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFFF4444"))
            }
            val sizePx = (CURSOR_SIZE_DP * ctx.resources.displayMetrics.density).toInt()
            val dotSize = (6 * ctx.resources.displayMetrics.density).toInt()
            val inset = (sizePx - dotSize) / 2
            return LayerDrawable(arrayOf(outerRing, innerDot)).apply {
                setLayerInset(0, 0, 0, 0, 0)
                setLayerInset(1, inset, inset, inset, inset)
            }
        }

        /**
         * Called when shouldInterceptRequest detects a cfglobalcdn M3U8 URL.
         * We keep the overlay WebView alive, navigate it to the M3U8 URL
         * (so it ends up on cfglobalcdn origin for same-origin segment XHR),
         * extract the M3U8 content, then play via ExoPlayer + WebViewDataSource.
         */
        private fun onM3u8Intercepted(m3u8Url: String) {
            val video = pendingWebViewVideo ?: return
            val server = pendingWebViewServer ?: return

            Log.d("PlayerTV", "onM3u8Intercepted: ${m3u8Url.take(100)}")

            // The netu embed URL is the correct Referer for cfglobalcdn
            val netuEmbedUrl = video.webViewUrl ?: "https://netu.frembed.bond/"
            val netuOrigin = try {
                val u = java.net.URL(netuEmbedUrl); "${u.protocol}://${u.host}"
            } catch (_: Exception) { "https://netu.frembed.bond" }

            // Extract cookies from the WebView session BEFORE destroying it
            val cookieManager = CookieManager.getInstance()
            val cfgCookies = cookieManager.getCookie(m3u8Url) ?: ""
            Log.d("PlayerTV", "Cookies for cfglobalcdn: ${cfgCookies.take(80)}")

            hideWebViewOverlay()
            com.streamflixreborn.streamflix.extractors.NetuExtractor.sharedWebView = null

            Log.d("PlayerTV", "Using CronetDataSource for Netu/cfglobalcdn (referer=$netuEmbedUrl)")

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
         * rejects). loadUrl("cdnOrigin/") gives a genuine same-origin context
         * with correct Origin/Referer headers, Chrome TLS, and shared cookies.
         */
        @SuppressLint("SetJavaScriptEnabled")
        private fun onDaddyLiveM3u8Intercepted(m3u8Url: String, embedUrl: String) {
            val video = pendingWebViewVideo ?: return
            val server = pendingWebViewServer ?: return

            // Include port (e.g. :8443) — without it, origin mismatch causes CORS failure
            val cdnOrigin = try {
                val u = java.net.URL(m3u8Url)
                val port = if (u.port != -1 && u.port != u.defaultPort) ":${u.port}" else ""
                "${u.protocol}://${u.host}$port"
            } catch (_: Exception) { "" }

            Log.d("PlayerTV", "DaddyLive M3U8 → ExoPlayer via WebViewDataSource (CDN=$cdnOrigin): ${m3u8Url.take(120)}")

            // Clean up any previous DaddyLive proxy (prevents second player)
            daddyLiveProxyWebView?.let { old ->
                Log.d("PlayerTV", "Cleaning up previous DaddyLive proxy WebView")
                try { old.stopLoading(); old.destroy() } catch (_: Exception) {}
                (binding.root as? ViewGroup)?.removeView(old)
            }
            daddyLiveProxyWebView = null

            val ctx = requireContext()
            val proxyWv = WebView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(1, 1)
                setBackgroundColor(Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = NetworkClient.USER_AGENT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = true
                }
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(proxyWv, true)

            (binding.root as ViewGroup).addView(proxyWv)
            daddyLiveProxyWebView = proxyWv

            val cdnPageUrl = daddyLiveCdnPageUrl
            Log.d("PlayerTV", "DaddyLive proxy: cdnPageUrl=$cdnPageUrl, cdnOrigin=$cdnOrigin")

            proxyWv.webViewClient = object : WebViewClient() {
                private var started = false

                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    // BEFORE page loaded: block scripts so hls.js doesn't start its own playback
                    // AFTER page loaded (started=true): let EVERYTHING through for WebViewDataSource
                    if (!started && (reqUrl.endsWith(".js") || reqUrl.contains("hls.min")
                                || reqUrl.contains("hls.js") || reqUrl.contains("/js/"))) {
                        Log.d("PlayerTV", "DaddyLive proxy: blocking script: ${reqUrl.takeLast(60)}")
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
                    Log.d("PlayerTV", "DaddyLive proxy WebView loaded CDN page, url=$url")

                    hideWebViewOverlay()

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

                    if (!::player.isInitialized) return
                    player.setMediaSource(hlsSource)
                    player.prepare()
                    player.playWhenReady = true
                    usingWebView = true

                    Log.d("PlayerTV", "DaddyLive → ExoPlayer playing via WebViewDataSource (cdnPage=$url)")
                }
            }

            if (cdnPageUrl != null) {
                Log.d("PlayerTV", "DaddyLive proxy: loading CDN iframe page: ${cdnPageUrl.take(120)}")
                proxyWv.loadUrl(cdnPageUrl)
            } else {
                Log.w("PlayerTV", "DaddyLive proxy: no CDN page URL captured, using loadDataWithBaseURL origin=$cdnOrigin")
                proxyWv.loadDataWithBaseURL(
                    "$cdnOrigin/",
                    "<html><head></head><body></body></html>",
                    "text/html", "UTF-8", null
                )
            }
        }

        private fun releasePlayer() {
            stopProgressHandler()
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
            binding.pvPlayer.player = null
            binding.settings.player = null
            binding.settings.subtitleView = null
            if (::player.isInitialized) {
                player.release()
            }
            if (::mediaSession.isInitialized) {
                mediaSession.release()
            }
        }

    private fun showQrDialog(content: String) {
        val displayMetrics: DisplayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val dialogWidth = (displayMetrics.widthPixels * 0.72f).toInt()
        val qrSize = minOf(
            (dialogWidth - (density * 64).toInt()).coerceAtLeast((density * 240).toInt()),
            (displayMetrics.heightPixels * 0.45f).toInt().coerceAtLeast((density * 240).toInt()),
        )
        val bitmap = QrUtils.generate(content, qrSize) ?: return

        val imageView = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }

        val instructionsView = TextView(requireContext()).apply {
            text = buildString {
                append("Solve captcha on phone")
                if (BypassWebSocketEndpointHelper.isProbablyEmulator()) {
                    append("\n\nEmulator note: set 'Bypass advertised host' in TV settings to your PC LAN IP and forward TCP 8081 to the emulator.")
                }
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (density * 24).toInt(),
                (density * 16).toInt(),
                (density * 24).toInt(),
                (density * 12).toInt(),
            )
        }
        container.addView(
            imageView,
            LinearLayout.LayoutParams(qrSize, qrSize)
        )
        container.addView(
            instructionsView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        )

        val scrollView = ScrollView(requireContext()).apply {
            isFillViewport = true
            addView(container)
        }

        qrDialog = androidx.appcompat.app.AlertDialog.Builder(requireActivity())
            .setTitle("Scan with phone")
            .setView(scrollView)
            .setCancelable(true)
            .setOnCancelListener {
                Log.d("Bypass", "QR dialog cancelled")
                clearBypassSession(dismissDialog = false)
            }
            .create()

        qrDialog?.show()
        qrDialog?.window?.setLayout(dialogWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun isSerienStreamBypassUrl(url: String): Boolean {
        return runCatching {
            Uri.parse(url).host.equals("s.to", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun buildSerienStreamBypassUrl(): String? {
        return null
    }

    private fun startWebSocketServer(): Int {
        if (wsServer != null) return wsServer?.address?.port ?: 8081

        val ports = listOf(8081, 8082, 8887, 0)
        for (port in ports) {
            val server = BypassWebSocketServer(port) { token, cookies ->
                requireActivity().runOnUiThread {
                    Log.d("BypassWS", "DONE received for token: $token")
                    onBypassCompleted(token, cookies)
                }
            }
            wsServer = server
            try {
                server.start()
                if (server.awaitStart(5_000)) {
                    val actualPort = server.address.port
                    Log.d("BypassWS", "WebSocket server started on port $actualPort")
                    return actualPort
                } else {
                    val error = server.getStartError()
                    Log.e("BypassWS", "Server failed to start on port $port: ${error?.message}")
                    stopWebSocketServer()
                }
            } catch (e: Exception) {
                Log.e("BypassWS", "Failed to start on port $port", e)
                stopWebSocketServer()
            }
        }
        return -1
    }
    private fun stopWebSocketServer() {
        try {
            wsServer?.stop()
        } catch (_: Exception) {}
        wsServer = null
    }

    private fun clearBypassSession(
        dismissDialog: Boolean = true,
        resetBypassDone: Boolean = false,
    ) {
        activeBypassSession?.let { session ->
            wsServer?.clearSession(session.token)
        }
        activeBypassSession = null
        waitingForBypass = false
        if (resetBypassDone) {
            bypassDone = false
        }
        if (dismissDialog) {
            qrDialog?.dismiss()
        }
        qrDialog = null
        stopWebSocketServer()
    }


    private fun onBypassCompleted(token: String, cookies: String?) {
        val session = activeBypassSession
        if (session == null || session.token != token) {
            Log.w("BypassWS", "Ignoring bypass completion for stale token: $token")
            return
        }

        val extraBuffering = PlayerSettingsView.Settings.ExtraBuffering.isEnabled
        currentExtraBuffering = extraBuffering

        // Respect OkHttp fallback if already triggered
        val videoUrl = currentVideo?.source ?: ""
        if (usingCronet || !::httpDataSource.isInitialized) {
            httpDataSource = createHttpDataSourceFactory(videoUrl)
        }

        dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)

        player = buildPlayer(extraBuffering).also { player ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
            }

        // Bind new player to UI view
        binding.pvPlayer.player = player
        binding.settings.player = player
        binding.settings.subtitleView = binding.pvPlayer.subtitleView

        bypassDone = true
        waitingForBypass = false
        activeBypassSession = null

        clearBypassSession(dismissDialog = true)
        applyBypassCookies(session.serverUrl, cookies)

        lifecycleScope.launch {
            delay(300)

            // 🔴 restore episode context BEFORE reload
            when (val type = args.videoType) {
                is Video.Type.Episode -> {
                    EpisodeManager.setCurrentEpisode(type)
                }
                else -> {}
            }

            viewModel.reloadServersAfterBypass()
        }
    }

    private fun applyBypassCookies(url: String, cookieHeader: String?) {
        val cookies = cookieHeader?.trim().orEmpty()
        if (cookies.isBlank()) return

        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        val targets = linkedSetOf<String>().apply {
            if (url.isNotBlank()) add(url)
            if (host.isNotBlank()) {
                add("https://$host/")
                add("http://$host/")
            }
        }
        if (targets.isEmpty()) return

        val cookieManager = CookieManager.getInstance()
        cookies.split(";")
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
