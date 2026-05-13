package com.streamflixreborn.streamflix.fragments.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.util.Rational
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
import com.streamflixreborn.streamflix.providers.IptvProvider
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
import com.streamflixreborn.streamflix.fragments.player.settings.IptvFavorites
import com.streamflixreborn.streamflix.fragments.player.settings.IptvBannedServers
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

        private const val PIP_ACTION_PLAY = "com.streamfr.app.PIP_PLAY"
        private const val PIP_ACTION_PAUSE = "com.streamfr.app.PIP_PAUSE"
        private const val PIP_ACTION_REWIND = "com.streamfr.app.PIP_REWIND"
        private const val PIP_ACTION_FORWARD = "com.streamfr.app.PIP_FORWARD"

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

    /** Flag : a-t-on déjà auto-sélectionné un sous-titre OpenSubtitles ?
     *  Évite de re-déclencher le download à chaque emit du subtitleState. */
    private var autoSubtitleApplied = false
    private var _binding: FragmentPlayerMobileBinding? = null
    private val binding get() = _binding!!
    private var isSetupDone = false

    private val PlayerControlView.binding
        get() = ContentExoControllerMobileBinding.bind(this.findViewById(R.id.cl_exo_controller))

    private val args by navArgs<PlayerMobileFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { PlayerViewModel(args.videoType, args.id) }

    /** Visual channel key (e.g. "France 4") — used for IPTV favorites persistence. */
    private val currentChannelKey: String by lazy {
        args.id
            .removePrefix("ch::")
            .removePrefix("sport::")
            .removePrefix("ola_ep::")
            .removePrefix("ola::")
    }

    private lateinit var player: ExoPlayer
    private lateinit var httpDataSource: HttpDataSource.Factory
    private lateinit var dataSourceFactory: DataSource.Factory
    private lateinit var mediaSession: MediaSession
    private lateinit var progressHandler: android.os.Handler
    private lateinit var progressRunnable: Runnable
    private lateinit var gestureHelper: PlayerGestureHelper

    private var servers = listOf<Video.Server>()
    private var zoomToast: Toast? = null

    // IPTV: when all initial servers fail but progressive (OLA) servers may still arrive,
    // keep the player open and wait for additionalServer emissions instead of navigating up.
    private var awaitingMoreServers = false
    private var awaitTimeoutHandler: android.os.Handler? = null
    private var awaitTimeoutRunnable: Runnable? = null

    // 2026-05-04 : message d'attente affiché pendant l'extraction quand
    // ça traîne (typiquement Cloudflare challenge sur vidmoly). 3 paliers :
    //  - 5s : "Chargement..."
    //  - 12s : "Vérification CF en cours, peut prendre 30s..."
    //  - 25s : "Toujours en cours, patience..."
    private var patienceHandler: android.os.Handler? = null
    private val patienceRunnables = mutableListOf<Runnable>()

    private var currentVideo: Video? = null
    private var currentServer: Video.Server? = null
    // 2026-05-05 : watchdog buffering — fallback automatique au serveur suivant
    // si le player reste bloqué en STATE_BUFFERING > N secondes (Darkibox & co
    // qui renvoient une URL valide mais ne délivrent pas de données).
    private var bufferingWatchdog: kotlinx.coroutines.Job? = null
    // 2026-05-09 v2 : 10s → 20s → 45s.
    // Règle user : "il faut que la source aille jusqu'à l'échec avant de
    // changer". Le watchdog ne doit PAS kill prématurément une source qui
    // charge lentement — ExoPlayer fire ses propres erreurs (TCP timeout,
    // 404, 403, format unsupported) en 15-30s typiquement. 45s = filet de
    // sécurité absolu pour les vraies pannes silencieuses (genre le CDN
    // accepte la connexion mais ne renvoie jamais de bytes).
    private val BUFFERING_TIMEOUT_MS = 45_000L
    private var usingCronet = false
    private var usingDoH = false
    private var usingBrowserOkHttp = false
    private var usingWebView = false

    // Track the active Player.Listener so we can remove it before attaching a new one.
    // Without this, every retry/displayVideo() call piled a new listener on top of the
    // previous, causing onPlayerError to fire N times per error and accumulating
    // ExoPlayer/MediaSession resources until the OS killed the process.
    private var activePlayerListener: androidx.media3.common.Player.Listener? = null

    /** IPTV sticky-server: once the current stream has reached STATE_READY, we never
     *  auto-switch on transient errors — we just re-prepare. The user can always
     *  switch manually. Reset when the user changes server or channel. */
    private var iptvRetryCount = 0
    private val IPTV_MAX_RETRIES_SAME_STREAM = 3
    private var iptvCurrentStreamHasWorked = false
    // 2026-05-11 : pour VOD aussi — true dès STATE_READY pour différencier
    // "petite coupure pendant lecture" (swap rapide) vs "n'a jamais démarré"
    // (sticky, l'user veut choisir manuellement).
    private var vodCurrentStreamHasWorked = false

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

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!::player.isInitialized) return
            when (intent?.action) {
                PIP_ACTION_PLAY -> player.play()
                PIP_ACTION_PAUSE -> player.pause()
                PIP_ACTION_REWIND -> player.seekTo(maxOf(0, player.currentPosition - 10_000))
                PIP_ACTION_FORWARD -> player.seekTo(minOf(player.duration, player.currentPosition + 10_000))
            }
            // Update PiP actions to reflect new play/pause state
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                updatePipParams()
            }
        }
    }
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

        // 2026-05-09 v17 : pose tout de suite la channelKey IPTV partagée pour
        // que le picker (favoris/coche) marche dès la 1re ouverture.
        run {
            val isIptvCtx = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                args.id.startsWith("movixlivetv::") ||
                args.id.startsWith("livehub::") ||
                args.id.startsWith("sportlive::") ||
                args.id.startsWith("match::")
            com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
                .Settings.Server.currentIptvChannelKey =
                if (isIptvCtx) args.id else null
        }

        // Pre-install Play Services Cronet provider asynchronously so it's ready
        // by the time a LuluVdo/vidzy video needs Chrome's TLS stack
        initCronetEngine()
        initializePlayer(false)
        initializeVideo()

        // For IPTV (WiTv / OlaTv) extraction can take 5-15s; the PlayerView
        // controller doesn't auto-show until a MediaItem is set, so the user
        // is left staring at a black screen with no buttons. Force the
        // controller visible immediately and disable auto-hide; once playback
        // actually starts (STATE_READY) we restore the normal 2s timeout.
        run {
            val provider = UserPreferences.currentProvider
            val isIptv = provider is com.streamflixreborn.streamflix.providers.IptvProvider
            if (isIptv) {
                binding.pvPlayer.useController = true
                binding.pvPlayer.controllerShowTimeoutMs = 0
                binding.pvPlayer.showController()
            }
        }
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
                    PlayerViewModel.State.LoadingServers -> {
                        // 2026-05-09 : afficher l'overlay de chargement dès le
                        // début pour pas laisser un écran noir vide. La barre
                        // de progression fictive simule un chargement de 5s.
                        showLoadingOverlay()
                    }
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

                            // 2026-05-08 : pose la channelKey IPTV partagée pour
                            // que Settings.Server.isIptv puisse calculer son channelKey
                            // (utilisé par les boutons croix/cœur du picker).
                            val isIptvCtx = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                                args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                                args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                                args.id.startsWith("movixlivetv::") ||
                                args.id.startsWith("livehub::") ||
                                args.id.startsWith("sportlive::") ||
                                args.id.startsWith("match::")
                            PlayerSettingsView.Settings.Server.currentIptvChannelKey =
                                if (isIptvCtx) args.id else null

                            // 2026-05-08 : tri par priorité IPTV : favoris (par ordre user)
                            // → non-favoris non-bannis → bannis. Garantit que le fallback
                            // onPlayerError essaie fav#1, puis fav#2, etc., avant les autres.
                            val orderedServers = if (isIptvCtx) {
                                val favIds = IptvFavorites.getFavoritesForChannel(args.id)
                                val favIdSet = favIds.toSet()
                                val favRank: (String) -> Int = { id ->
                                    val r = favIds.indexOf(id)
                                    if (r >= 0) r else Int.MAX_VALUE
                                }
                                state.servers.sortedWith(compareBy(
                                    { IptvBannedServers.isBanned(args.id, it.id) },  // false avant true → non-bannis d'abord
                                    { !favIdSet.contains(it.id) },                    // false avant true → favoris d'abord
                                    { favRank(it.id) }                                // ordre user dans favoris
                                ))
                            } else state.servers

                            player.playlistMetadata = MediaMetadata.Builder()
                                .setTitle(state.toString())
                                .setMediaServers(orderedServers.map {
                                    MediaServer(
                                        id = it.id,
                                        name = it.name,
                                    )
                                })
                                .build()
                            binding.settings.setOnServerSelectedListener { server ->
                                // 2026-05-10 : pas de !! qui crashait sur serveurs ajoutés
                                // dynamiquement (kick + replacement).
                                val target = state.servers.find { server.id == it.id }
                                    ?: Video.Server(id = server.id, name = server.name)
                                viewModel.getVideo(target)
                            }

                            // IPTV ban callback: remove source and replace from pool
                            binding.settings.onChannelVariantBanned = { bannedVariant ->
                                val provider = UserPreferences.currentProvider
                                if (provider is com.streamflixreborn.streamflix.providers.OlaTvProvider) {
                                    val replacement = provider.requestSingleReplacement(bannedVariant.id)
                                    if (replacement != null) {
                                        val closeBracket = replacement.name.indexOf(']')
                                        val label = if (closeBracket >= 0) replacement.name.substring(closeBracket + 2) else replacement.name
                                        val newVariant = PlayerSettingsView.Settings.ChannelVariant(
                                            id = replacement.id,
                                            name = label,
                                            channelKey = currentChannelKey,
                                        )
                                        PlayerSettingsView.Settings.ChannelVariant.addReplacement(newVariant)
                                        // Also add to servers list so getVideo can find it
                                        servers = servers + replacement
                                    }
                                    binding.settings.refreshChannelVariantList()
                                }
                            }

                            // IPTV favorite callback: just refresh UI (persistence handled by IptvFavorites)
                            binding.settings.onChannelVariantFavoriteToggled = { _ ->
                                binding.settings.refreshChannelVariantList()
                            }

                            // 2026-05-08 : ban d'un Settings.Server IPTV → trigger
                            // reload des servers pour que le backfill compense
                            // (provider voit nonBannedCount < 5 → scanne nouveaux).
                            binding.settings.onServerBanned = {
                                viewModel.reloadServersAfterBypass()
                            }
                            binding.settings.onServerFavoriteToggled = {
                                // Re-trigger : le tri par favoris est appliqué au prochain
                                // setMediaServers, donc on relance pour que la liste se réordonne.
                                viewModel.reloadServersAfterBypass()
                            }

                            // Chaîne starts empty — clear old entries from previous channel
                            PlayerSettingsView.Settings.ChannelVariant.list.clear()
                            binding.settings.refreshChannelVariantList()

                            // 2026-05-08 : continuité mini→fullscreen + favoris multi.
                            //  1. Si on vient du mini, reprendre le SAME server.
                            //  2. Sinon, prendre le 1er favori marqué par l'user
                            //     (par ordre de marquage = priorité utilisateur).
                            //  3. Sinon servers.first() par défaut.
                            val miniServer = if (com.streamflixreborn.streamflix.utils.MiniPlayerController.transitioningToFullscreen) {
                                com.streamflixreborn.streamflix.utils.MiniPlayerController.currentMiniServer()
                            } else null
                            val matchedMiniServer = miniServer?.let { mini ->
                                state.servers.firstOrNull { it.id == mini.id }
                            }
                            // Favoris multi-server (max 5, ordre = priorité)
                            val orderedFavIds = IptvFavorites.getFavoritesForChannel(currentChannelKey)
                            val favServer = orderedFavIds.firstNotNullOfOrNull { favId ->
                                state.servers.firstOrNull { it.id == favId }
                            }
                            // 2026-05-08 : skip les bannis pour le démarrage auto.
                            // L'user ne veut pas qu'un server grisé soit joué.
                            val firstNonBanned = state.servers.firstOrNull { srv ->
                                !IptvBannedServers.isBanned(currentChannelKey, srv.id)
                            }
                            val initialServer = matchedMiniServer ?: favServer ?: firstNonBanned ?: state.servers.first()
                            if (matchedMiniServer != null) {
                                Log.d("PlayerMobileFragment", "Mini→fullscreen : reprise sur ${matchedMiniServer.name}")
                            } else if (favServer != null) {
                                Log.d("PlayerMobileFragment", "Favori prioritaire : ${favServer.name} (${orderedFavIds.size}/${IptvFavorites.MAX_FAVORITES_PER_CHANNEL})")
                            }
                            viewModel.getVideo(initialServer)
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
                        schedulePatienceMessages()
                    }

                    is PlayerViewModel.State.SuccessLoadingVideo -> {
                        // Channel works — unmark as failed if it was, cancel any pending wait
                        UserPreferences.unmarkChannelFailed(args.id)
                        cancelAwaitMoreServers()
                        cancelPatienceMessages()
                        // Cache l'overlay de chargement — on a un Video prêt,
                        // ExoPlayer va prendre le relais visuellement.
                        hideLoadingOverlay()
                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)
                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)
                        displayVideo(state.video, state.server)
                    }

                    is PlayerViewModel.State.FailedLoadingVideo -> {
                        cancelPatienceMessages()
                        // Re-afficher l'overlay : on tente le serveur suivant,
                        // donc on revient en mode chargement (chargement à 0).
                        showLoadingOverlay()
                        // Drop this broken variant from the visible Chaîne page so the user
                        // doesn't see piling up dead entries. Re-emitted next session.
                        pruneBrokenVariant(state.server)
                        // IPTV (live channels): NEVER auto-advance to a different server on
                        // extractor failure. Same sticky policy as onPlayerError. Without
                        // this, OLA/Vegeta jumped from variant to variant during initial
                        // loading which churned the buffer and broke playback. The
                        // tryNextChannelVariant call below still handles same-channel
                        // variants, and the IPTV ingestion keeps running in the background
                        // so additional sources show up in the Chaîne page.
                        val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
                        // 2026-05-09 : nextAutoFallbackServer skip VOSTFR/VO si on
                        // était en VF — évite de jouer du sub contre la volonté user.
                        val nextServer = if (isLiveIptv) null
                            else nextAutoFallbackServer(servers, state.server)
                        if (nextServer != null) {
                            viewModel.getVideo(nextServer)
                        } else if (tryNextChannelVariant(state.server)) {
                            // OLA channel variant fallback succeeded — playing next variant
                        } else {
                            // 2026-05-09 : tous les serveurs ont été tentés et tous
                            // ont fail. Demande au ViewModel de marquer ce film comme
                            // "présumé sans source" SI le pattern d'échecs le justifie
                            // (≥2 fails dont la majorité dead-content). Ça pousse le
                            // film en bas du home au prochain refresh.
                            viewModel.markFilmEmptyIfAllDeadContent()
                            val provider = UserPreferences.currentProvider
                            // For IPTV providers (WiTv / OlaTv) keep the player open and wait for
                            // additional progressive servers instead of closing immediately.
                            val isIptv = provider is com.streamflixreborn.streamflix.providers.IptvProvider
                            if (isIptv) {
                                if (!awaitingMoreServers) {
                                    Log.d("PlayerMobileFragment", "All initial servers failed — awaiting progressive sources…")
                                    Toast.makeText(
                                        requireContext(),
                                        "Recherche d'autres sources…",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                startAwaitMoreServers()
                            } else {
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
        }

        // Progressive additional servers (OLA TV streams go to Chaîne, others to Serveurs)

        // Coalesce many rapid emissions into a single UI refresh — without this,
        // 50+ progressive emissions in a few hundred ms saturate the main thread
        // (sort + notifyDataSetChanged per emit) and trigger an ANR.
        val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var refreshChannelPending = false
        val refreshChannelRunnable = Runnable {
            refreshChannelPending = false
            if (_binding != null) binding.settings.refreshChannelVariantList()
        }
        fun scheduleChannelRefresh() {
            if (refreshChannelPending) return
            refreshChannelPending = true
            refreshHandler.postDelayed(refreshChannelRunnable, 200)
        }
        var refreshServerPending = false
        val refreshServerRunnable = Runnable {
            refreshServerPending = false
            if (_binding != null) binding.settings.refreshServerList()
        }
        fun scheduleServerRefresh() {
            if (refreshServerPending) return
            refreshServerPending = true
            refreshHandler.postDelayed(refreshServerRunnable, 200)
        }

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
                    var addedVariant: PlayerSettingsView.Settings.ChannelVariant? = null
                    if (sameNameCount < 3) {
                        addedVariant = PlayerSettingsView.Settings.ChannelVariant(
                            id = server.id, name = label, channelKey = currentChannelKey,
                        )
                        // Restore favorite state from persistence
                        if (IptvFavorites.isFavorite(currentChannelKey, server.id)) {
                            addedVariant.isFavorite = true
                        }
                        PlayerSettingsView.Settings.ChannelVariant.list.add(addedVariant)
                        if (PlayerSettingsView.Settings.ChannelVariant.list.size == 1) {
                            PlayerSettingsView.Settings.ChannelVariant.list.first().isSelected = true
                        }
                        scheduleChannelRefresh()
                    }

                    // Set up variant click handler (idempotent — same listener every time)
                    binding.settings.setOnChannelVariantSelectedListener { variant ->
                        viewModel.getVideo(Video.Server(variant.id, variant.name))
                    }

                    // Auto-play logic:
                    // 1. If this stream is the favorite → play it immediately (switch to it)
                    // 2. If no favorite exists → play the first OLA stream that arrives
                    // 3. If a favorite exists but this isn't it → don't auto-play (wait for fav)
                    val isFav = IptvFavorites.isFavorite(currentChannelKey, server.id)
                    val hasNonOla = servers.any { !it.name.startsWith("OLA[") }
                    val firstOla = !hasNonOla && PlayerSettingsView.Settings.ChannelVariant.list.size == 1
                    val hasFavoriteForChannel = server.id.startsWith("ola_stream::") &&
                        IptvFavorites.getFavoriteForChannel(currentChannelKey) != null

                    if (isFav) {
                        // This is the favorite → play it NOW, even if something else is already playing
                        Log.d("PlayerMobileFragment", "★ Favorite OLA stream arrived — switching to: $label")
                        PlayerSettingsView.Settings.ChannelVariant.list.forEach { it.isSelected = false }
                        // Mark the variant in the list (may be the one just added, or an existing one)
                        val favVariant = addedVariant
                            ?: PlayerSettingsView.Settings.ChannelVariant.list.find { it.id == server.id }
                        favVariant?.isSelected = true
                        viewModel.getVideo(server)
                    } else if (firstOla && !hasFavoriteForChannel) {
                        // No favorite set for this channel → play first available
                        Log.d("PlayerMobileFragment", "No non-OLA servers, no favorite — auto-playing first OLA stream")
                        viewModel.getVideo(server)
                    } else if (awaitingMoreServers) {
                        Log.d("PlayerMobileFragment", "Awaiting more servers — trying newly arrived OLA stream: $label")
                        cancelAwaitMoreServers()
                        triedChannelVariantIds.add(server.id)
                        viewModel.getVideo(server)
                    } else if (firstOla && hasFavoriteForChannel) {
                        // First stream but a favorite exists — play this temporarily,
                        // the favorite will auto-switch when it arrives
                        Log.d("PlayerMobileFragment", "Playing first OLA stream while waiting for favorite")
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
                    scheduleServerRefresh()
                    Log.d("PlayerMobileFragment", "Additional server added: ${server.name}")

                    // If we were waiting for more servers after a failure, try this one now.
                    if (awaitingMoreServers) {
                        Log.d("PlayerMobileFragment", "Awaiting more servers — trying newly arrived server: ${server.name}")
                        cancelAwaitMoreServers()
                        viewModel.getVideo(server)
                    }
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
                        // 2026-05-07 : auto-download OpenSubtitles DÉSACTIVÉ.
                        // Le user active manuellement dans le menu Sous-titres s'il en
                        // veut un. Trop de cas où ça forçait un sub sur de l'audio FR.
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
        if (!isInPictureInPictureMode) {
            // Exiting PiP — unregister the broadcast receiver
            try { requireContext().unregisterReceiver(pipActionReceiver) } catch (_: Exception) {}
        }
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    fun onUserLeaveHint() {
        if (!isIgnoringPip && ::player.isInitialized && player.isPlaying) {
            enterPIPMode()
        }
    }

    override fun onStop() {
        super.onStop()
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                requireActivity().isInPictureInPictureMode
        if (::player.isInitialized && !inPip) {
            player.pause()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        hideWebViewOverlay()

        // Cleanup Handler leaks
        if (::progressHandler.isInitialized && ::progressRunnable.isInitialized) {
            progressHandler.removeCallbacks(progressRunnable)
        }
        cancelAwaitMoreServers()
        cancelPatienceMessages()

        // Cleanup DaddyLive proxy WebView
        daddyLiveProxyWebView?.let {
            try { it.stopLoading(); it.destroy() } catch (_: Exception) {}
        }
        daddyLiveProxyWebView = null

        // Shutdown Cronet executor
        cronetExecutor.shutdownNow()

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
        binding.settings.setOnServerDownloadClickedListener { serverSetting ->
            val server = servers.find { it.id == serverSetting.id } ?: return@setOnServerDownloadClickedListener
            downloadFromServer(server)
        }
        binding.settings.onDownloadsClicked = {
            com.streamflixreborn.streamflix.download.DownloadsBottomSheet()
                .show(childFragmentManager, com.streamflixreborn.streamflix.download.DownloadsBottomSheet.TAG)
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
        // 2026-05-08 : ajout vegeta::/vegeta_ep:: oubliés (le code ola:: était
        // là, mais vegeta absent → boutons prev/next ne marchaient pas pour VegetaTV).
        // 2026-05-08 : ajout livehub:: pour TV Hub (zap entre favoris).
        val isIptvChannel = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
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
                val hasFinished = player.hasFinished()
                val hasReallyFinished = player.hasReallyFinished()
                val ctx = requireContext()
                val provider = UserPreferences.currentProvider ?: return@listener

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val watchItem: WatchItem? = when (videoType) {
                        is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                        is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                    }

                    when (videoType) {
                        is Video.Type.Movie -> {
                            val movie = watchItem as? Movie
                            movie?.let { database.movieDao().update(it) }
                            movie?.let { UserDataCache.addMovieToContinueWatching(ctx, provider, it) }
                        }

                        is Video.Type.Episode -> {
                            val episode = watchItem as? Episode
                            episode?.let {
                                if (hasFinished) {
                                    database.episodeDao().resetProgressionFromEpisode(videoType.id)
                                    UserDataCache.removeEpisodeFromContinueWatching(ctx, provider, it.id)
                                }
                                database.episodeDao().update(it)

                                if (!hasFinished) {
                                    UserDataCache.addEpisodeToContinueWatching(ctx, provider, it)
                                }

                                it.tvShow?.let { tvShow ->
                                    database.tvShowDao().getById(tvShow.id)
                                }?.let { tvShow ->
                                    val episodeDao = database.episodeDao()
                                    val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                                    database.tvShowDao().save(tvShow.copy().apply {
                                        merge(tvShow)
                                        isWatching = !hasReallyFinished || isStillWatching
                                    })
                                }
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

        // Resolve prev/next IDs depending on provider type
        val prevId: String?
        val nextId: String?
        val resolveDisplayName: (String) -> String?
        val resolvePoster: (String) -> String?

        when (provider) {
            is WiTvProvider -> {
                prevId = provider.getPreviousChannelId(args.id)
                nextId = provider.getNextChannelId(args.id)
                resolveDisplayName = { provider.getChannelDisplayName(it) }
                resolvePoster = { provider.getChannelPoster(it) }
            }
            is com.streamflixreborn.streamflix.providers.OlaTvProvider -> {
                prevId = provider.getPreviousChannelId(args.id)
                nextId = provider.getNextChannelId(args.id)
                resolveDisplayName = { provider.getChannelDisplayName(it) }
                resolvePoster = { provider.getChannelPoster(it) }
            }
            is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> {
                prevId = provider.getPreviousChannelId(args.id)
                nextId = provider.getNextChannelId(args.id)
                resolveDisplayName = { provider.getChannelDisplayName(it) }
                resolvePoster = { provider.getChannelPoster(it) }
            }
            is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> {
                prevId = provider.getPreviousChannelId(args.id)
                nextId = provider.getNextChannelId(args.id)
                resolveDisplayName = { provider.getChannelDisplayName(it) }
                resolvePoster = { provider.getChannelPoster(it) }
            }
            is com.streamflixreborn.streamflix.providers.VavooProvider -> {
                prevId = provider.getPreviousChannelId(args.id)
                nextId = provider.getNextChannelId(args.id)
                resolveDisplayName = { provider.getChannelDisplayName(it) }
                resolvePoster = { provider.getChannelPoster(it) }
            }
            is com.streamflixreborn.streamflix.providers.BoxXtemusProvider -> {
                prevId = provider.getPreviousChannelId(args.id)
                nextId = provider.getNextChannelId(args.id)
                resolveDisplayName = { provider.getChannelDisplayName(it) }
                resolvePoster = { provider.getChannelPoster(it) }
            }
            else -> {
                btnPrevious.isGone = true
                btnNext.isGone = true
                return
            }
        }

        btnPrevious.isGone = prevId == null
        btnNext.isGone = nextId == null

        fun navigateToChannel(channelId: String) {
            val channelName = resolveDisplayName(channelId) ?: channelId
            val channelPoster = resolvePoster(channelId)

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

    /**
     * Keep the player open while progressive (OLA) servers are still being fetched.
     * Default timeout: 90s — after that, give up and navigate up.
     */
    private fun startAwaitMoreServers(timeoutMs: Long = 90_000L) {
        awaitingMoreServers = true
        cancelAwaitTimeoutOnly()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val r = Runnable {
            if (!awaitingMoreServers) return@Runnable
            awaitingMoreServers = false
            if (isAdded) {
                Toast.makeText(
                    requireContext(),
                    "Aucune source disponible.",
                    Toast.LENGTH_LONG
                ).show()
                try { findNavController().navigateUp() } catch (_: Exception) { }
            }
        }
        awaitTimeoutHandler = handler
        awaitTimeoutRunnable = r
        handler.postDelayed(r, timeoutMs)
    }

    /** Cancel the timeout AND clear the awaiting flag (call when a server succeeds or user leaves). */
    private fun cancelAwaitMoreServers() {
        awaitingMoreServers = false
        cancelAwaitTimeoutOnly()
    }

    private fun cancelAwaitTimeoutOnly() {
        awaitTimeoutRunnable?.let { awaitTimeoutHandler?.removeCallbacks(it) }
        awaitTimeoutHandler = null
        awaitTimeoutRunnable = null
    }

    /** 2026-05-04 : messages "patience" désactivés à la demande de l'utilisateur.
     *  Ils apparaissaient pour tous les extracteurs et étaient trompeurs (mention
     *  Cloudflare hardcodée + apparition aléatoire). La fonction est conservée
     *  mais ne fait rien — pour pouvoir les réactiver facilement plus tard. */
    private fun schedulePatienceMessages() {
        cancelPatienceMessages()
        // no-op : tous les toasts désactivés
    }

    private fun cancelPatienceMessages() {
        val h = patienceHandler ?: return
        patienceRunnables.forEach { h.removeCallbacks(it) }
        patienceRunnables.clear()
        patienceHandler = null
    }

    /**
     * Retourne le prochain serveur à essayer en failover auto, avec
     * dégradation de langue contrôlée.
     *
     *  Règles :
     *  1. Cherche d'abord le prochain serveur de la MÊME langue que le current
     *     (si current=VF → cherche le prochain VF dispo dans la liste)
     *  2. Si aucun de la même langue → DÉGRADE :
     *     - VF épuisés → essaie le 1er VOSTFR de la liste (sub français = OK)
     *     - VOSTFR épuisés → essaie le 1er VO de la liste (mieux que rien)
     *  3. VO épuisés → STOP (vraiment plus rien à essayer)
     *
     *  Cette dégradation contrôlée évite le piège qu'on avait observé :
     *  "stop dès que les VF foirent" laissait l'user devant un message
     *  d'erreur alors qu'il y avait peut-être un VOSTFR/VO qui marchait.
     *
     *  L'user peut toujours cliquer manuellement n'importe quel server
     *  dans le picker (qui montre la liste complète).
     */
    private fun nextAutoFallbackServer(allServers: List<Video.Server>, current: Video.Server?): Video.Server? {
        if (current == null) return null
        val curIdx = allServers.indexOf(current)
        if (curIdx < 0) return null

        val currentLang = detectServerLanguage(current.name)

        // 2026-05-12 : skip les serveurs flaggés broken par le pre-extract
        // HEAD check. Match par "core extractor" (ex: "VidMoLy") pour que
        // les variantes avec/sans "Movix —" préfix matchent.
        fun extractorCore(name: String): String {
            val stripped = name.substringAfter(" — ").substringAfter(" · ").trim()
            return stripped.substringBefore(" - ").substringBefore(" (").substringBefore(" [").trim().uppercase()
        }
        val brokenCores = com.streamflixreborn.streamflix.extractors.Extractor.brokenServerNames()
            .map { extractorCore(it) }.filter { it.isNotBlank() }.toSet()
        fun isBroken(srv: Video.Server): Boolean {
            if (brokenCores.isEmpty()) return false
            return extractorCore(srv.name) in brokenCores
        }

        // Étape 1 : cherche le prochain server NON-BROKEN de la MÊME langue.
        for (i in (curIdx + 1) until allServers.size) {
            val candidate = allServers[i]
            if (detectServerLanguage(candidate.name) == currentLang && !isBroken(candidate)) {
                return candidate
            }
        }

        // Étape 2 : dégradation contrôlée. Si on était en VF → on accepte VOSTFR.
        // Si on était en VOSTFR → on accepte VO. Si on était en VO → STOP.
        val fallbackLang = when (currentLang) {
            "vf" -> "vostfr"
            "vostfr" -> "vo"
            else -> return null  // VO épuisés → vraiment STOP
        }
        // On scanne TOUTE la liste (pas juste après curIdx) pour trouver le 1er
        // server NON-BROKEN du fallback lang — il peut être avant curIdx dans l'ordre brut.
        val nextNonBroken = allServers.firstOrNull {
            detectServerLanguage(it.name) == fallbackLang && !isBroken(it)
        }
        if (nextNonBroken != null) return nextNonBroken
        // Dernier recours : aucun serveur sain, on accepte un broken (HEAD peut mentir)
        return allServers.firstOrNull { detectServerLanguage(it.name) == fallbackLang }
    }

    /** Détection langue cohérente avec ExtractorRanker. Retourne "vf", "vostfr", "vo". */
    private fun detectServerLanguage(name: String): String {
        val lower = name.lowercase()
        // VOSTFR check FIRST car contient "FR" qui matcherait VF.
        if (lower.contains(Regex("\\b(vostfr|vost|sub|subbed)\\b"))) return "vostfr"
        if (lower.contains(Regex("\\b(vff|vfq|vfi|vf|french|francais|français)\\b"))) return "vf"
        if (lower.contains(Regex("\\b(vo|raw|multi|eng|english|spa|ita|german|deu|jap)\\b"))) return "vo"
        // Pas de marker → assume VF (cas IPTV / chaînes sport)
        return "vf"
    }

    /** Animateur de la barre de progression fictive (0 → 95% sur 5s, stagne à 95%). */
    private var loadingBarAnimator: android.animation.ObjectAnimator? = null
    /** Handler pour le delay de 250ms avant affichage — évite de flasher
     *  l'overlay sur les chargements rapides (cache HIT du pre-extract). */
    private val loadingShowHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var loadingShowRunnable: Runnable? = null

    /** Délai avant affichage de l'overlay. Sous ce délai, l'overlay ne flash
     *  pas — utile quand le pre-extract a déjà caché et que le chargement
     *  est instantané. Au-dessus, l'user voit le spinner et la barre. */
    private val LOADING_OVERLAY_SHOW_DELAY_MS = 250L

    /**
     * Affiche l'overlay de chargement : spinner centré + barre de progression
     * fictive. Appelé sur LoadingServers + LoadingVideo (échec d'un serveur,
     * tentative du suivant).
     *
     *  Délai 250ms avant affichage : si le chargement complète AVANT
     *  (cas du cache HIT du pre-extract), l'overlay ne s'affiche jamais.
     *  C'est le pattern "delayed display" recommandé pour les loaders qui
     *  évite de flasher pour rien.
     */
    /** True si on est sur une chaîne IPTV — pour skip le loading overlay
     *  (le buffer ExoPlayer gère déjà le chargement) qui bloque l'accès
     *  au menu Settings sur ces providers. */
    private fun isIptvChannelContext(): Boolean {
        val id = args.id
        return id.startsWith("ch::") || id.startsWith("sport::") ||
            id.startsWith("ola::") || id.startsWith("ola_ep::") ||
            id.startsWith("vegeta::") || id.startsWith("vegeta_ep::") ||
            id.startsWith("livehub::") || id.startsWith("movixlivetv::") ||
            id.startsWith("sportlive::") || id.startsWith("match::") ||
            id.startsWith("bxt::")
    }

    private fun showLoadingOverlay() {
        // 2026-05-08 : skip sur IPTV — l'overlay plein écran bloque l'accès
        // au menu Serveurs/Settings, et le buffer ExoPlayer gère déjà la
        // sensation de chargement.
        if (isIptvChannelContext()) return
        if (_binding == null) return
        val overlay = binding.loadingOverlay
        if (overlay.isVisible) return  // déjà affiché, ne pas reset l'animation

        // Annule un éventuel show précédent en attente.
        loadingShowRunnable?.let { loadingShowHandler.removeCallbacks(it) }

        val runnable = Runnable {
            if (_binding == null) return@Runnable
            val ov = binding.loadingOverlay
            val bar = binding.loadingOverlayBar
            ov.visibility = View.VISIBLE
            bar.progress = 0
            loadingBarAnimator?.cancel()
            // Animate from 0 to 95 over 5s. On stagne à 95% pour ne pas mentir
            // (l'attente réelle peut dépasser 5s, surtout quand un extracteur
            // foire et qu'on en essaye un autre). Le hideLoadingOverlay() snap
            // à 100% quand le chargement vrai est terminé.
            loadingBarAnimator = android.animation.ObjectAnimator
                .ofInt(bar, "progress", 0, 95)
                .apply {
                    duration = 5_000L
                    interpolator = android.view.animation.DecelerateInterpolator()
                    start()
                }
        }
        loadingShowRunnable = runnable
        loadingShowHandler.postDelayed(runnable, LOADING_OVERLAY_SHOW_DELAY_MS)
    }

    /** Cache l'overlay, annule le delay et stoppe l'animation. Appelé sur
     *  SuccessLoadingVideo. Si l'overlay n'a jamais été affiché (chargement
     *  plus rapide que LOADING_OVERLAY_SHOW_DELAY_MS), c'est un no-op visuel. */
    private fun hideLoadingOverlay() {
        if (_binding == null) return
        // Annule le show en attente si pas encore exécuté.
        loadingShowRunnable?.let { loadingShowHandler.removeCallbacks(it) }
        loadingShowRunnable = null
        loadingBarAnimator?.cancel()
        loadingBarAnimator = null
        if (binding.loadingOverlay.isVisible) {
            binding.loadingOverlayBar.progress = 100
            binding.loadingOverlay.visibility = View.GONE
        }
    }

    /** Mark a server as tried and remove it from the Chaîne page so broken variants
     *  don't pollute the visible list. They'll re-emit at next session if Phase 3
     *  finds them again and they happen to work that time. Also reports the resolved
     *  upstream URL to OlaTvProvider so other variants pointing to the same dead URL
     *  fail fast. */
    private fun pruneBrokenVariant(server: Video.Server?) {
        if (server == null) return
        triedChannelVariantIds.add(server.id)
        // Report the upstream URL we were just playing — multiple variants may resolve
        // to the same dead URL (different cmd, same upstream).
        val playingUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (!playingUri.isNullOrBlank()) {
            try {
                com.streamflixreborn.streamflix.providers.OlaTvProvider.reportBrokenStreamUrl(playingUri)
            } catch (_: Throwable) { /* WiTv lacks this method, ignore */ }
        }
        val variants = PlayerSettingsView.Settings.ChannelVariant.list
        val removed = variants.removeAll { it.id == server.id }
        if (removed && _binding != null) {
            Log.d("PlayerMobileFragment", "Pruned broken variant: ${server.name}")
            binding.settings.refreshChannelVariantList()
        }
    }

    private fun tryNextChannelVariant(failedServer: Video.Server?): Boolean {
        val variants = PlayerSettingsView.Settings.ChannelVariant.list
        if (variants.isEmpty()) return false

        // Mark the failed server's variant as tried AND remove it from the visible list
        // so the user doesn't see broken entries piling up. Re-add happens automatically
        // on the next session (Phase 3 will re-emit working variants).
        if (failedServer != null) {
            triedChannelVariantIds.add(failedServer.id)
            val removed = variants.removeAll { it.id == failedServer.id }
            if (removed && _binding != null) {
                Log.d("PlayerMobileFragment", "Removed broken variant from Chaîne page: ${failedServer.name}")
                binding.settings.refreshChannelVariantList()
            }
        }

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

    /**
     * Download from a specific server: resolve the video URL, then enqueue it.
     * Blocks WebView-only sources (LuluVdo, Netu/cfglobalcdn) that need a
     * browser network stack and would 403 via direct OkHttp download.
     */
    private fun downloadFromServer(server: Video.Server) {
        // Ensure notification permission is granted (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val perm = android.Manifest.permission.POST_NOTIFICATIONS
            if (requireContext().checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(perm), 1001)
            }
        }

        // Live IPTV: tap into the player's DataSource to record while playing
        // (avoids segment-auth issues from separate OkHttp fetches).
        val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
        if (isLiveIptv) {
            handleLiveRecord(server)
            return
        }

        val providerName = UserPreferences.currentProvider?.name ?: "unknown"
        val videoType = currentVideoTypeForUi()

        Toast.makeText(requireContext(), "Résolution du lien…", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val provider = UserPreferences.currentProvider ?: return@launch
                val video = provider.getVideo(server)
                if (video.source.isEmpty()) throw Exception("No source found")

                // ── Block WebView-only sources ──
                if (isVideoWebViewOnly(video)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            "Ce serveur nécessite un navigateur, téléchargement impossible (${server.name})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                Log.d("PlayerMobileFragment", "Enqueuing download: server=${server.name} source=${video.source.take(100)} headers=${video.headers?.keys}")

                com.streamflixreborn.streamflix.download.DownloadManager.enqueue(
                    video = video,
                    videoType = videoType,
                    providerName = providerName,
                    serverName = server.name,
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Téléchargement ajouté : ${server.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("PlayerMobileFragment", "Download failed for server ${server.name}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Erreur : ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Returns true if this video requires a WebView network stack and cannot
     * be downloaded directly with OkHttp.
     * Covers: LuluVdo/LuluStream CDN (TLS fingerprint), Netu/cfglobalcdn (ISP block),
     * and any source that sets needsWebViewClick.
     */
    private fun isVideoWebViewOnly(video: Video): Boolean {
        // Needs user interaction in WebView (anti-bot click)
        if (video.needsWebViewClick && !video.webViewUrl.isNullOrBlank()) return true

        // LuluVdo CDN — 403s without Chromium TLS fingerprint
        if (isLuluVdoCdn(video)) return true

        // data: URI sources can't be downloaded directly (base64-encoded m3u8 with
        // segments behind WebView-protected CDN)
        if (video.source.startsWith("data:")) return true

        // cfglobalcdn — ISP blocks, needs WebView Chromium stack
        if (video.source.contains("cfglobalcdn.com", ignoreCase = true)) return true

        return false
    }

    /** Show the inline REC button on the player controls. Visible on ANY video
     *  (live IPTV + VOD): the user can capture a portion of any stream by
     *  toggling start/stop. The icon goes RED when recording so the state is
     *  visible at a glance. Called whenever the playing video changes. */
    private fun updateLiveRecordButton() {
        val btn = binding.pvPlayer.findViewById<android.widget.ImageView>(R.id.btn_live_record)
            ?: return
        btn.visibility = View.VISIBLE
        val recording = com.streamflixreborn.streamflix.download.LiveRecorder.isRecording
        val tint = if (recording) {
            android.graphics.Color.parseColor("#FF3B30")
        } else {
            android.graphics.Color.WHITE
        }
        androidx.core.widget.ImageViewCompat.setImageTintList(
            btn, android.content.res.ColorStateList.valueOf(tint)
        )
        btn.setOnClickListener {
            val server = currentServer ?: return@setOnClickListener
            handleLiveRecord(server)
            // Refresh icon tint after toggling.
            updateLiveRecordButton()
        }
    }

    /** Live IPTV recording: tap into ExoPlayer's DataSource (no separate auth-failing
     *  fetch). Toggle: 1st click starts recording, 2nd click stops. The user MUST
     *  keep the channel playing — closing the player ends the recording. */
    private fun handleLiveRecord(server: Video.Server) {
        if (com.streamflixreborn.streamflix.download.LiveRecorder.isRecording) {
            // Stop and finalize
            val channel = currentChannelKey.ifBlank { server.name }
            val savedPath = com.streamflixreborn.streamflix.download.LiveRecorder.stopRecording(requireContext().applicationContext)
            if (savedPath != null) {
                Toast.makeText(requireContext(),
                    "✅ Enregistrement sauvegardé dans Movies/StreamFlix",
                    Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(),
                    "Aucun segment capturé — restez sur la chaîne pendant l'enregistrement",
                    Toast.LENGTH_LONG).show()
            }
            return
        }
        // Start recording
        val channelDisplayName = when (val type = args.videoType) {
            is Video.Type.Episode -> type.tvShow.title
            is Video.Type.Movie -> type.title
        }.ifBlank { server.name }
        val outFile = com.streamflixreborn.streamflix.download.LiveRecorder.startRecording(
            context = requireContext().applicationContext,
            channelDisplayName = channelDisplayName,
        )
        if (outFile == null) {
            Toast.makeText(requireContext(),
                "Un enregistrement est déjà en cours",
                Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(),
            "🔴 Enregistrement démarré — RESTEZ sur la chaîne. Re-tapez le bouton pour arrêter.",
            Toast.LENGTH_LONG).show()
        // Force the buffer to drain to live edge so new segments are fetched and
        // captured right away (with our big 30s/120s buffer, otherwise we could
        // wait minutes before any new fetch happens).
        try {
            if (::player.isInitialized) {
                player.seekToDefaultPosition()
            }
        } catch (_: Exception) {}
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
        // Reset IPTV stickiness when switching to a different server (manual or auto).
        if (currentServer?.id != server.id) {
            iptvRetryCount = 0
            iptvCurrentStreamHasWorked = false
            vodCurrentStreamHasWorked = false
        }
        currentServer = server
        updatePlayerHeader()
        updateLiveRecordButton()

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

        // 2026-05-09 : pour IPTV live HLS, on configure un offset cible de 60s
        // derrière le live edge. Sans ça l'user dit que "le download va jusqu'au
        // bout de la barre, ce qui crée des coupures intempestives". Avec un
        // targetOffset 60s, le playback head reste stable au milieu du buffer.
        val isLiveIptvChannel = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
        // 2026-05-09 : roue de chargement masquée pour IPTV uniquement.
        // keepContentOnPlayerReset reset à false — sera mis à true uniquement
        // juste avant les reloads auto-recovery (sinon casse mini→fullscreen).
        try {
            binding.pvPlayer.setShowBuffering(
                if (isLiveIptvChannel) androidx.media3.ui.PlayerView.SHOW_BUFFERING_NEVER
                else androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING
            )
            binding.pvPlayer.setKeepContentOnPlayerReset(false)
        } catch (_: Exception) {}
        val mediaItemBuilder = MediaItem.Builder()
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
        if (isLiveIptvChannel) {
            // 2026-05-09 v19 : cible 30s derrière live edge (safe partout).
            // Évite BEHIND_LIVE_WINDOW sur flux M3U courts. ExoPlayer ajuste
            // vitesse ±3% imperceptible pour maintenir offset.
            mediaItemBuilder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(30_000L)
                    .setMinOffsetMs(10_000L)
                    .setMaxOffsetMs(90_000L)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
        }
        val mediaItem = mediaItemBuilder.build()

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
            // Route HLS sources (live IPTV AND VOD .m3u8) through TeeDataSource so
            // LiveRecorder can capture played segments on demand. This lets the
            // user tap REC during ANY HLS playback to capture a portion. For
            // non-HLS sources (mp4, etc.) the TeeDataSource still wraps fine.
            // 2026-05-04 : MoiflixExtractor sert un master HLS via xs1.php?data=XXX
            // (sans extension .m3u8 dans l'URL) avec type=APPLICATION_M3U8. On
            // teste donc le `type` en plus de la source URL pour router vers
            // HlsMediaSource au lieu de ProgressiveMediaSource.
            // 2026-05-10 : .ts force Progressive (MPEG-TS continu), même si l'URL
            // contient /live/. Sinon HlsPlaylistParser tente de parser le binaire
            // comme playlist HLS et crashe (#EXTM3U manquant).
            val urlEndsWithTs = video.source.substringBefore('?').endsWith(".ts", ignoreCase = true)
            val isHls = !urlEndsWithTs && (
                video.source.contains(".m3u8")
                || video.source.contains("/live/")
                || video.type == androidx.media3.common.MimeTypes.APPLICATION_M3U8
            )
            // 2026-05-11 : détection DASH (.mpd) — utilisé par 3BoxTV (LCI live etc.).
            // Sans cette branche, le DASH tombe dans ProgressiveMediaSource qui
            // ne sait pas lire un manifest XML → "UnrecognizedInputFormatException".
            val isDash = !isHls && !urlEndsWithTs && (
                video.source.substringBefore('?').endsWith(".mpd", ignoreCase = true)
                || video.type == androidx.media3.common.MimeTypes.APPLICATION_MPD
            )
            val teeFactory = com.streamflixreborn.streamflix.download.TeeDataSourceFactory(dataSourceFactory)
            if (isHls) {
                // 2026-05-09 v23 : retry 403 sur HLS Live (cf PlayerTvFragment).
                val errorPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
                    override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                        val ex = info.exception
                        if (ex is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && ex.responseCode == 403) {
                            Log.d("PlayerMobileFragment", "HLS 403 → retry 1.5s (manifest refresh)")
                            return 1500L
                        }
                        return super.getRetryDelayMsFor(info)
                    }
                    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 6
                }
                val hlsSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(teeFactory)
                    .setAllowChunklessPreparation(true)
                    .setLoadErrorHandlingPolicy(errorPolicy)
                    .createMediaSource(mediaItem)
                player.setMediaSource(hlsSource)
                Log.d("PlayerDebug", "HLS v23: TeeDataSource + retry-403 policy")
            } else if (isDash) {
                // DASH (.mpd) — utilisé par les flux live français (TF1, France TV, etc.)
                // via le pipeline 3BoxTV (RSS feed → URL signée vers .mpd).
                val dashSource = androidx.media3.exoplayer.dash.DashMediaSource.Factory(teeFactory)
                    .createMediaSource(mediaItem)
                player.setMediaSource(dashSource)
                Log.d("PlayerDebug", "DASH: DashMediaSource + TeeDataSource")
            } else {
                // Non-HLS / Non-DASH (mp4 progressive). Wrap with TeeDataSource for REC.
                val progressiveSource = androidx.media3.exoplayer.source.ProgressiveMediaSource
                    .Factory(teeFactory)
                    .createMediaSource(mediaItem)
                player.setMediaSource(progressiveSource)
                Log.d("PlayerDebug", "Progressive: using TeeDataSource (REC button can tap)")
            }
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
        // Detach previous listener (if any) before adding the new one — otherwise each
        // displayVideo() call leaks a listener and onPlayerError fires multiple times.
        activePlayerListener?.let { try { player.removeListener(it) } catch (_: Exception) {} }
        val newListener = object : Player.Listener {
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

                // 2026-05-05 : watchdog buffering — fallback auto si bloqué > 10s
                if (playbackState != Player.STATE_BUFFERING) {
                    bufferingWatchdog?.cancel()
                    bufferingWatchdog = null
                } else {
                    val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                        args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                        args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                        args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                        args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
                    // 2026-05-11 (user) : 2 règles selon état du stream :
                    //
                    // (A) PRÉ-READY (épisode jamais lancé, extracteur silencieux) :
                    //     SKIP au server suivant après 30s. Sinon l'user reste planté
                    //     indéfiniment sur un extracteur qui rend une URL morte.
                    //
                    // (B) POST-READY (épisode déjà joué, juste une coupure réseau) :
                    //     ZÉRO swap. À 15s buffering, on auto-active ExtraBuffering
                    //     (maxBufferMs 120s→300s) + re-init MÊME serveur avec seekTo
                    //     pour reprendre où l'user était. L'épisode marchait donc
                    //     le server est OK, juste un blip réseau.
                    if (!isLiveIptv && bufferingWatchdog == null) {
                        val initialPosition = player.currentPosition
                        bufferingWatchdog = viewLifecycleOwner.lifecycleScope.launch {
                            if (!vodCurrentStreamHasWorked) {
                                // (A) Pré-READY : 10s puis skip server
                                // 2026-05-12 : baissé de 30s→10s pour accélérer
                                // le fallback en série quand plein de serveurs morts.
                                // Le HEAD check pre-extract filtre déjà en amont.
                                kotlinx.coroutines.delay(10_000L)
                                if (player.playbackState == Player.STATE_BUFFERING &&
                                    player.currentPosition == initialPosition &&
                                    !vodCurrentStreamHasWorked) {
                                    val server = currentServer
                                    val nextServer = nextAutoFallbackServer(servers, server)
                                    Log.w("PlayerNetwork",
                                        "Pre-READY 10s freeze on ${server?.name} → skip to ${nextServer?.name}")
                                    if (server != null) {
                                        pruneBrokenVariant(server)
                                        // 2026-05-12 : flag instantanément le serveur broken
                                        // → fallback suivant le skip → cascade rapide
                                        com.streamflixreborn.streamflix.extractors.Extractor
                                            .recordFailureExternal(server.name, "pre-ready-freeze")
                                    }
                                    if (nextServer != null) viewModel.getVideo(nextServer)
                                }
                            } else {
                                // (B) Post-READY : 15s puis super-buffer, jamais de swap
                                kotlinx.coroutines.delay(15_000L)
                                if (player.playbackState == Player.STATE_BUFFERING &&
                                    player.currentPosition == initialPosition &&
                                    !currentExtraBuffering) {
                                    val server = currentServer
                                    val video = currentVideo
                                    if (server != null && video != null) {
                                        val savedPos = player.currentPosition
                                        Log.w("PlayerNetwork",
                                            "Post-READY 15s freeze on ${server.name} → ExtraBuffering ON @${savedPos}ms")
                                        PlayerSettingsView.Settings.ExtraBuffering.init(true)
                                        initializePlayer(true, currentSoftwareDecoder, video.source)
                                        displayVideo(video, server)
                                        try { player.seekTo(savedPos) } catch (_: Exception) {}
                                    }
                                }
                            }
                            bufferingWatchdog = null
                        }
                    }
                    // 2026-05-10 : BUFFERING watchdog IPTV désactivé (cf PlayerTvFragment).
                    // Crash natif MediaCodec sur Chromecast à cause de l'empilement de reloads.
                }

                // Once we actually reach READY, restore the normal 2s auto-hide
                // (we forced it to 0 during IPTV extraction so the controls
                // would be visible immediately). Also mark IPTV server as sticky.
                if (playbackState == Player.STATE_READY) {
                    if (binding.pvPlayer.controllerShowTimeoutMs == 0) {
                        binding.pvPlayer.controllerShowTimeoutMs = 2000
                    }
                    if (iptvRetryCount > 0) {
                        Log.d("PlayerMobileFragment", "Stream recovered, resetting IPTV retry counter")
                        iptvRetryCount = 0
                    }
                    if (!iptvCurrentStreamHasWorked) {
                        iptvCurrentStreamHasWorked = true
                        Log.d("PlayerMobileFragment", "IPTV stream marked as working — sticky server enabled")
                        // 2026-05-09 v13 : refresh proactif du token Stalker toutes les 3 min.
                        scheduleProactiveTokenRefresh()
                    }
                    // 2026-05-11 : VOD too — flag pour swap rapide sur "petite coupure"
                    if (!vodCurrentStreamHasWorked) {
                        vodCurrentStreamHasWorked = true
                        Log.d("PlayerMobileFragment", "VOD stream marked as working — fast-swap on glitch enabled")
                    }
                }

                // Live IPTV auto-resume: re-prepare on STATE_ENDED (playlist tail
                // exhausted) AND on STATE_IDLE (transient blip with no error).
                val isLiveIptvStream = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                    args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                    args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                    args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                    args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
                if (isLiveIptvStream && (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE)) {
                    if (playbackState == Player.STATE_IDLE && !iptvCurrentStreamHasWorked) return
                    // 2026-05-10 : guard anti-rentrance (cf PlayerTvFragment).
                    if (preemptiveReloadInFlight) return
                    Log.w("PlayerMobileFragment", "Live IPTV stuck in $playbackState — FULL RELOAD via displayVideo")
                    preemptiveReloadInFlight = true
                    try {
                        val cs = currentServer
                        val cv = currentVideo
                        if (cs != null && cv != null) {
                            // 2026-05-10 : keep_content retiré (cf PlayerTvFragment).
                            try {
                                binding.pvPlayer.controller.hide()
                            } catch (_: Exception) {}
                            viewLifecycleOwner.lifecycleScope.launch {
                                kotlinx.coroutines.delay(50L)
                                if (_binding != null) {
                                    try {
                                        displayVideo(cv, cs)
                                    } catch (e: Exception) {
                                        Log.w("PlayerMobileFragment", "displayVideo reload failed: ${e.message}")
                                    }
                                }
                                kotlinx.coroutines.delay(25_000L)
                                preemptiveReloadInFlight = false
                            }
                        } else {
                            preemptiveReloadInFlight = false
                            player.prepare()
                            player.playWhenReady = true
                        }
                    } catch (e: Exception) {
                        preemptiveReloadInFlight = false
                        Log.w("PlayerMobileFragment", "Auto-resume failed: ${e.message}")
                    }
                }
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
                    val hasStarted = player.hasStarted()
                    val hasFinished = player.hasFinished()
                    val hasReallyFinished = player.hasReallyFinished()
                    val currentPos = player.currentPosition
                    val duration = player.duration
                    val ctx = requireContext()
                    val provider = UserPreferences.currentProvider ?: return

                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val watchItem: WatchItem? = when (videoType) {
                            is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                            is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                        }

                        when {
                            hasStarted && !hasFinished -> {
                                watchItem?.isWatched = false
                                watchItem?.watchedDate = null
                                watchItem?.watchHistory = WatchItem.WatchHistory(
                                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                                    lastPlaybackPositionMillis = currentPos,
                                    durationMillis = duration,
                                )
                            }

                            hasFinished -> {
                                watchItem?.isWatched = true
                                watchItem?.watchedDate = Calendar.getInstance()
                                watchItem?.watchHistory = null
                            }
                        }

                        when (videoType) {
                            is Video.Type.Movie -> {
                                val movie = watchItem as? Movie
                                movie?.let {
                                    database.movieDao().update(it)
                                    UserDataCache.syncMovieToCache(ctx, provider, it)
                                }
                            }

                            is Video.Type.Episode -> {
                                val episode = watchItem as? Episode
                                episode?.let {
                                    if (hasFinished) {
                                        database.episodeDao().resetProgressionFromEpisode(videoType.id)
                                        UserDataCache.removeEpisodeFromContinueWatching(ctx, provider, it.id)
                                        queueNextEpisodeForContinueWatching(provider)
                                    }
                                    database.episodeDao().update(it)
                                    if (!hasFinished) {
                                        UserDataCache.syncEpisodeToCache(ctx, provider, it)
                                    }

                                    it.tvShow?.let { tvShow ->
                                        database.tvShowDao().getById(tvShow.id)
                                    }?.let { tvShow ->
                                        val episodeDao = database.episodeDao()
                                        val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                                        database.tvShowDao().save(tvShow.copy().apply {
                                            merge(tvShow)
                                            isWatching = !hasReallyFinished || isStillWatching
                                        })
                                    }
                                }
                            }
                        }
                    }
                    // 2026-05-09 : IPTV ne déclenche jamais l'autoplay
                    // next-episode (bug BFMTV qui auto-skipait sur live).
                    val isLiveIptvNoAutoSkip = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                        args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                        args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                        args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                        args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
                    if (player.hasReallyFinished() && !isLiveIptvNoAutoSkip) {
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
                    pruneBrokenVariant(server)
                    val nextServer = nextAutoFallbackServer(servers, server)
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
                    // 2026-05-08 : pour IPTV, sticky absolu APRÈS 1er READY.
                    // L'user a explicitement choisi (ou démarré sur un favori) —
                    // pas de switch auto. Avant 1er READY, on cherche encore une
                    // source utilisable comme avant.
                    val isLiveIptvNow = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                        args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                        args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                        args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                        args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
                    if (isLiveIptvNow && iptvCurrentStreamHasWorked) {
                        Log.w("PlayerNetwork", "Connection timeout IPTV sticky (already worked) → re-prepare same server")
                        try { player.prepare(); player.playWhenReady = true } catch (_: Exception) {}
                        return
                    }
                    pruneBrokenVariant(server)
                    val nextServer = nextAutoFallbackServer(servers, server)
                    if (nextServer != null) {
                        Log.w("PlayerNetwork", "Connection timeout on ${server.name}, auto-switching to ${nextServer.name}")
                        viewModel.getVideo(nextServer)
                    } else if (!tryNextChannelVariant(server)) {
                        Log.e("PlayerNetwork", "Connection timeout on ${server.name}, no more servers or variants to try")
                    }
                    return
                }

                // Fallback 3 (IPTV): for live channels — sticky server pattern.
                // Switch quand :
                //   (a) le stream n'a JAMAIS fonctionné ET retry > MAX_RETRIES_BEFORE_SWITCH
                //   (b) erreur HTTP permanente (403/404/410/451/456 = blocked/dead)
                // Sinon : re-prepare le même server (sticky pour erreurs transitoires
                // après que le stream a déjà marché une fois).
                val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                    args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                    args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                    args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                    args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
                if (isLiveIptv) {
                    val server = currentServer ?: return
                    val errCodeName = error.errorCodeName
                    iptvRetryCount++

                    // 2026-05-09 v3 : STICKY ABSOLU si stream a marché.
                    val errMsg = (error.cause?.message ?: error.message ?: "").lowercase()
                    val isPermanentHttpError = errMsg.contains("response code: 403") ||
                        errMsg.contains("response code: 404") ||
                        errMsg.contains("response code: 410") ||
                        errMsg.contains("response code: 451") ||
                        errMsg.contains("response code: 456")
                    val MAX_RETRIES_BEFORE_SWITCH = 3
                    val shouldSwitch = !iptvCurrentStreamHasWorked &&
                        (isPermanentHttpError || iptvRetryCount >= MAX_RETRIES_BEFORE_SWITCH)

                    // 2026-05-10 : cooldown anti-cascade (cf PlayerTvFragment).
                    val nowMs = System.currentTimeMillis()
                    val sinceLastSwitch = nowMs - lastAutoSwitchTime
                    val switchCooldownMs = 10_000L
                    // 2026-05-10 : favori → JAMAIS auto-switch.
                    val isFavoriteServer = try {
                        com.streamflixreborn.streamflix.fragments.player.settings.IptvFavorites
                            .isFavorite(args.id, server.id)
                    } catch (_: Exception) { false }
                    if (shouldSwitch && isFavoriteServer) {
                        Log.d("PlayerMobileFragment", "IPTV switch demandé mais server est favori — on retry à la place")
                    } else if (shouldSwitch && sinceLastSwitch < switchCooldownMs) {
                        Log.d("PlayerMobileFragment", "IPTV switch demandé mais cooldown ${(switchCooldownMs - sinceLastSwitch)/1000}s — on retry à la place")
                    } else if (shouldSwitch) {
                        Log.w("PlayerMobileFragment", "IPTV switch on ${server.name} ($errCodeName, retry=$iptvRetryCount, hasWorked=$iptvCurrentStreamHasWorked, permanentHttp=$isPermanentHttpError)")
                        pruneBrokenVariant(server)
                        val nextServer = servers.getOrNull(servers.indexOf(server) + 1)
                        if (nextServer != null) {
                            iptvRetryCount = 0
                            iptvCurrentStreamHasWorked = false
                            lastAutoSwitchTime = nowMs
                            viewModel.getVideo(nextServer)
                            return
                        } else if (tryNextChannelVariant(server)) {
                            iptvRetryCount = 0
                            iptvCurrentStreamHasWorked = false
                            lastAutoSwitchTime = nowMs
                            return
                        }
                        Log.e("PlayerMobileFragment", "IPTV switch demandé mais pas de server suivant disponible")
                    }

                    // 2026-05-12 (user) : HARD CAP pour éviter boucle infinie.
                    // Cf PlayerTvFragment pour le contexte (BoxXtemus HTML embed
                    // → ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED en boucle 33+ fois).
                    val HARD_RETRY_CAP = 5
                    val isParseError = errCodeName == "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" ||
                        errCodeName == "ERROR_CODE_PARSING_CONTAINER_MALFORMED" ||
                        errCodeName == "ERROR_CODE_PARSING_MANIFEST_MALFORMED" ||
                        errCodeName == "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED"
                    val noFallbackAvailable = servers.getOrNull(servers.indexOf(server) + 1) == null
                    if (!iptvCurrentStreamHasWorked && iptvRetryCount >= HARD_RETRY_CAP && (isParseError || noFallbackAvailable)) {
                        Log.e("PlayerMobileFragment",
                            "IPTV hard cap atteint sur ${server.name} ($errCodeName, retry=$iptvRetryCount, hasWorked=false, noFallback=$noFallbackAvailable) — abandon")
                        try {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Lecture impossible (${errCodeName.removePrefix("ERROR_CODE_")})",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } catch (_: Exception) {}
                        try { player.stop() } catch (_: Exception) {}
                        return
                    }

                    Log.w("PlayerMobileFragment", "IPTV retry on ${server.name} ($errCodeName) — retry #$iptvRetryCount, sticky (hasWorked=$iptvCurrentStreamHasWorked)")
                    try {
                        // 2026-05-09 v13 : Stalker + 403 → fresh handshake.
                        val isStalker = server.id.startsWith("vegeta_stream::") ||
                            server.id.startsWith("ola_stream::")
                        if (isStalker && isPermanentHttpError) {
                            Log.d("PlayerMobileFragment", "Stalker token expired → fresh handshake")
                            iptvRetryCount = 0
                            viewLifecycleOwner.lifecycleScope.launch {
                                val newServer = withContext(Dispatchers.IO) {
                                    when {
                                        server.id.startsWith("vegeta_stream::") ->
                                            com.streamflixreborn.streamflix.providers.VegetaTvProvider.refreshServerUrl(server)
                                        server.id.startsWith("ola_stream::") ->
                                            com.streamflixreborn.streamflix.providers.OlaTvProvider.refreshServerUrl(server)
                                        else -> null
                                    }
                                }
                                if (newServer != null && _binding != null) {
                                    viewModel.getVideo(newServer)
                                } else if (_binding != null) {
                                    // 2026-05-09 v19 : refresh impossible — prune + auto-switch
                                    // vers le server suivant au lieu de boucler en prepare().
                                    Log.w("PlayerMobileFragment",
                                        "Refresh impossible (no Stalker context) — pruning ${server.name} and auto-switching")
                                    pruneBrokenVariant(server)
                                    val nextServer = servers.getOrNull(servers.indexOf(server) + 1)
                                        ?: servers.firstOrNull { it.id != server.id }
                                    if (nextServer != null) {
                                        viewModel.getVideo(nextServer)
                                    } else {
                                        player.prepare()
                                        player.playWhenReady = true
                                    }
                                }
                            }
                        } else {
                            player.prepare()
                            player.playWhenReady = true
                        }
                    } catch (e: Exception) {
                        Log.w("PlayerMobileFragment", "Sticky retry failed: ${e.message}")
                    }
                    return
                }

                // Fallback 4 (catch-all VOD): any other error code we didn't match
                // explicitly above (404 Not Found, 410 Gone, manifest malformed,
                // decoding failure, generic IO, etc.) used to leave the screen frozen
                // black with no auto-advance — the user had to back out and pick
                // another server manually. For anime/film providers we now always
                // try the next server when an error fires, so a dead first lecteur
                // (e.g. vidmoly with an expired token) auto-fails over to the
                // following one.
                // 2026-05-11 (user) : RÈGLE 403/serveur HS uniquement.
                // Avant : tout error non-handled (incluant decoder error, glitch
                // transitoire) → auto-switch serveur. User : "si la vidéo bug au lieu
                // de reprendre sur le même serveur s'auto swap, c'est pas bon".
                // Nouveau : seulement swap si l'erreur indique vraiment un serveur
                // HS (404/410/451/456/500/502/503). Sinon STICKY = re-prepare le
                // même server (l'user a explicitement choisi, on respecte).
                run {
                    val server = currentServer ?: return
                    val errCodeName = error.errorCodeName
                    val msg = (error.message ?: "") + " " + (error.cause?.message ?: "")
                    val isServerDead = Regex("\\b(404|410|451|456|500|502|503|504)\\b").containsMatchIn(msg) ||
                        errCodeName.contains("HTTP_DATA_SOURCE_FORBIDDEN") ||
                        errCodeName.contains("DECODING_FAILED")
                    if (isServerDead) {
                        pruneBrokenVariant(server)
                        val nextServer = nextAutoFallbackServer(servers, server)
                        if (nextServer != null) {
                            Log.w("PlayerNetwork",
                                "Server HS on ${server.name} ($errCodeName: ${error.message}) — auto-switching to ${nextServer.name}")
                            viewModel.getVideo(nextServer)
                        } else if (!tryNextChannelVariant(server)) {
                            Log.e("PlayerNetwork", "Server HS on ${server.name} ($errCodeName) and no more servers")
                        }
                    } else {
                        // Erreur transitoire (glitch décodeur, network blip) — STICKY.
                        // L'user a choisi ce serveur, on garde. Re-prepare pour reprendre.
                        Log.w("PlayerNetwork",
                            "Transient error on ${server.name} ($errCodeName: ${error.message}) — STICKY (re-prepare same server)")
                        try { player.prepare(); player.playWhenReady = true } catch (_: Exception) {}
                    }
                }
            }
        }
        player.addListener(newListener)
        activePlayerListener = newListener

        // 2026-05-09 v12 : pour IPTV live, seekToDefaultPosition (= live edge
        // moins targetOffsetMs = mid-bar). Évite stagne en timeout après refresh.
        val isLiveIptvStream = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
        if (isLiveIptvStream) {
            player.seekToDefaultPosition()
        } else if (currentPosition == 0L) {
            val videoType = args.videoType
            val provider = UserPreferences.currentProvider
            val ctx = requireContext()

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val watchItem: WatchItem? = when (videoType) {
                    is Video.Type.Movie -> {
                        val movie = if (provider != null) {
                            UserDataCache.read(ctx, provider)?.continueWatchingMovies
                                ?.find { it.id == videoType.id }?.toMovie()
                        } else null
                        movie ?: database.movieDao().getById(videoType.id)
                    }
                    is Video.Type.Episode -> {
                        val episode = if (provider != null) {
                            UserDataCache.read(ctx, provider)?.continueWatchingEpisodes
                                ?.find { it.id == videoType.id }?.toEpisode()
                        } else null
                        episode ?: database.episodeDao().getById(videoType.id)
                    }
                }

                val lastPlaybackPositionMillis = watchItem?.watchHistory
                    ?.let { it.lastPlaybackPositionMillis - 10.seconds.inWholeMilliseconds }

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    player.seekTo(lastPlaybackPositionMillis ?: 0)
                }
            }
        } else {
            player.seekTo(currentPosition)
        }

        player.prepare()
        player.play()

        // Enable auto-PiP on Android 12+ once playback starts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updatePipParams()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val isPlaying = ::player.isInitialized && player.isPlaying
        val actions = mutableListOf<RemoteAction>()

        // Rewind 10s
        actions.add(
            RemoteAction(
                Icon.createWithResource(requireContext(), R.drawable.exo_styled_controls_rewind),
                getString(R.string.player_rewind),
                getString(R.string.player_rewind),
                PendingIntent.getBroadcast(
                    requireContext(), 3,
                    Intent(PIP_ACTION_REWIND),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        )

        // Play or Pause
        if (isPlaying) {
            actions.add(
                RemoteAction(
                    Icon.createWithResource(requireContext(), R.drawable.exo_styled_controls_pause),
                    getString(R.string.player_pause),
                    getString(R.string.player_pause),
                    PendingIntent.getBroadcast(
                        requireContext(), 1,
                        Intent(PIP_ACTION_PAUSE),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
            )
        } else {
            actions.add(
                RemoteAction(
                    Icon.createWithResource(requireContext(), R.drawable.exo_styled_controls_play),
                    getString(R.string.player_play),
                    getString(R.string.player_play),
                    PendingIntent.getBroadcast(
                        requireContext(), 2,
                        Intent(PIP_ACTION_PLAY),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
            )
        }

        // Forward 10s
        actions.add(
            RemoteAction(
                Icon.createWithResource(requireContext(), R.drawable.exo_styled_controls_fastforward),
                getString(R.string.player_forward),
                getString(R.string.player_forward),
                PendingIntent.getBroadcast(
                    requireContext(), 4,
                    Intent(PIP_ACTION_FORWARD),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
        }

        return builder.build()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParams() {
        try {
            requireActivity().setPictureInPictureParams(buildPipParams())
        } catch (_: Exception) {}
    }

    private fun enterPIPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.pvPlayer.useController = false

            // Register PiP action receiver
            val filter = IntentFilter().apply {
                addAction(PIP_ACTION_PLAY)
                addAction(PIP_ACTION_PAUSE)
                addAction(PIP_ACTION_REWIND)
                addAction(PIP_ACTION_FORWARD)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requireContext().registerReceiver(pipActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    requireContext().registerReceiver(pipActionReceiver, filter)
                }
            } catch (_: Exception) {}

            requireActivity().enterPictureInPictureMode(buildPipParams())
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
        var liveCheckCounter = 0
        // 2026-05-09 PISTE A : tracking du drain buffer (cf PlayerTvFragment).
        var lastAheadSec = -1
        var consecutiveDrainTicks = 0
        // 2026-05-10 : préemptif requiert buffer sain (>=15s) au moins une fois.
        var bufferEverHealthy = false
        progressRunnable = Runnable {
            if (player.isPlaying) {
                val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                    args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                    args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                    args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                    args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
                if (!isLiveIptv) {
                    val show = player.currentPosition in 3000..120000
                    showSkipIntroButton(show)
                    updateNextEpisodeOverlay()
                } else {
                    liveCheckCounter++
                    val pos = player.currentPosition
                    val buf = player.bufferedPosition
                    val ahead = (buf - pos).coerceAtLeast(0)
                    val aheadSec = (ahead / 1000).toInt()
                    if (liveCheckCounter >= 5) {
                        liveCheckCounter = 0
                        Log.d("PlayerMobileFragment", "Live buffer: pos=${pos/1000}s buf=${buf/1000}s ahead=${aheadSec}s")
                    }
                    // PISTE A : détection drain buffer (5 ticks consécutifs en baisse + ahead<25s)
                    if (lastAheadSec >= 0 && aheadSec < lastAheadSec) {
                        consecutiveDrainTicks++
                    } else {
                        consecutiveDrainTicks = 0
                    }
                    lastAheadSec = aheadSec
                    if (aheadSec >= 15) bufferEverHealthy = true
                    if (consecutiveDrainTicks >= 5 && aheadSec < 25 && bufferEverHealthy &&
                        iptvCurrentStreamHasWorked && !preemptiveReloadInFlight) {
                        preemptiveReloadInFlight = true
                        consecutiveDrainTicks = 0
                        val cs = currentServer
                        val cv = currentVideo
                        if (cs != null && cv != null) {
                            Log.w("PlayerMobileFragment",
                                "PREEMPTIVE reload — buffer drain ${aheadSec}s, 5 ticks de baisse continue")
                            try {
                                binding.pvPlayer.controller.hide()
                            } catch (_: Exception) {}
                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    displayVideo(cv, cs)
                                } catch (e: Exception) {
                                    Log.w("PlayerMobileFragment", "Preemptive displayVideo failed: ${e.message}")
                                }
                                kotlinx.coroutines.delay(20_000L)
                                preemptiveReloadInFlight = false
                            }
                        } else {
                            preemptiveReloadInFlight = false
                        }
                    }
                }
            }
            progressHandler.postDelayed(progressRunnable, 1000)
        }
        progressHandler.post(progressRunnable)
    }

    /** 2026-05-09 v3 : best practices HLS live — speed subtil 0.95-1.05,
     *  cible 25s derrière live edge. */
    private fun adjustLivePlaybackSpeed() {
        val offset = player.currentLiveOffset
        if (offset == androidx.media3.common.C.TIME_UNSET) return
        val targetSpeed = when {
            offset < 10_000 -> 0.95f
            offset < 20_000 -> 0.98f
            offset > 50_000 -> 1.05f
            offset > 35_000 -> 1.02f
            else -> 1.0f
        }
        val current = player.playbackParameters.speed
        if (kotlin.math.abs(current - targetSpeed) > 0.005f) {
            player.setPlaybackSpeed(targetSpeed)
            Log.d("PlayerLiveOffset", "offset=${offset/1000}s → speed=${targetSpeed}x")
        }
    }

    private fun stopProgressHandler() {
        if (::progressHandler.isInitialized) {
            progressHandler.removeCallbacks(progressRunnable)
        }
        proactiveRefreshHandler?.removeCallbacksAndMessages(null)
        proactiveRefreshHandler = null
    }

    // 2026-05-09 v13 : refresh proactif du token Stalker (Vegeta/Ola) toutes
    // les 3 min, AVANT expiry → cut court et prévisible vs 25s subi.
    private var proactiveRefreshHandler: android.os.Handler? = null
    @Volatile private var emergencyRefreshInFlight = false
    // 2026-05-09 PISTE A : flag anti-reload-en-rafale pour le préemptif drain.
    @Volatile private var preemptiveReloadInFlight = false
    @Volatile private var lastAutoSwitchTime = 0L

    private fun scheduleProactiveTokenRefresh() {
        val server = currentServer ?: return
        val isStalker = server.id.startsWith("vegeta_stream::") ||
            server.id.startsWith("ola_stream::")
        if (!isStalker) return

        proactiveRefreshHandler?.removeCallbacksAndMessages(null)
        proactiveRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
        proactiveRefreshHandler?.postDelayed({
            val current = currentServer
            if (current != null && _binding != null) {
                Log.d("PlayerMobileFragment", "Proactive token refresh (60s) — fresh handshake")
                viewLifecycleOwner.lifecycleScope.launch {
                    val newServer = withContext(Dispatchers.IO) {
                        when {
                            current.id.startsWith("vegeta_stream::") ->
                                com.streamflixreborn.streamflix.providers.VegetaTvProvider.refreshServerUrl(current)
                            current.id.startsWith("ola_stream::") ->
                                com.streamflixreborn.streamflix.providers.OlaTvProvider.refreshServerUrl(current)
                            else -> null
                        }
                    }
                    if (newServer != null && _binding != null) {
                        viewModel.getVideo(newServer)
                    }
                }
                scheduleProactiveTokenRefresh()
            }
        }, 60 * 1000L)
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
        // 2026-05-09 : ajout des préfixes IPTV oubliés (livehub::/sportlive::/match::)
        // 2026-05-11 : ajout vavoo:: — sans ça l'image figeait 4s sur Oppo car
        // buildPlayer utilisait le LoadControl VOD (bufferForPlayback 1.5s) au lieu
        // du LoadControl IPTV (10s) → quand le buffer vidéo se vidait brièvement,
        // décodeur c2.mtk.avc.decoder à 0 fps pendant 4s.
        val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
        // Per user request: precharge as much as possible so the live stream
        // never cuts. Bigger buffer windows + longer rebuffer threshold.
        // 2026-05-09 v11 : recovery ULTRA RAPIDE après cut (1s).
        val loadControl = if (isLiveIptv) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    30_000,
                    300_000,
                    10_000,
                    1_000  // rebuffer threshold ultra court
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    30_000,
                    if (extraBuffering) 300_000 else 120_000,
                    1_500,
                    3_000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }

        val baseBuilder = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 && !currentSoftwareDecoder) {
            ExoPlayer.Builder(requireContext())
        } else {
            // 2026-05-09 v16 : NextRenderersFactory pour IPTV (nextlib FFmpeg)
            val renderersFactory = if (isLiveIptv) {
                Log.d("PlayerMobileFragment", "Using NextRenderersFactory (nextlib FFmpeg) for IPTV")
                io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(requireContext()).apply {
                    setEnableDecoderFallback(true)
                    // 2026-05-09 v19 : MODE_ON (pas PREFER) — vidéo H264 sur HW,
                    // FFmpeg uniquement en fallback audio.
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                }
            } else {
                DefaultRenderersFactory(requireContext()).apply {
                    setEnableDecoderFallback(true)
                    if (currentSoftwareDecoder) {
                        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    }
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
        // anime-sama.fr : DoH résout mais TCP/TLS échoue sur certains réseaux ;
        // Cronet (HTTP/3, ECH) débloque ces cas.
        return url.contains("vidzy.live", ignoreCase = true)
            || url.contains("cfglobalcdn.com", ignoreCase = true)
            || url.contains("anime-sama.", ignoreCase = true)
    }

    private fun needsDoH(url: String): Boolean {
        // sprintcdn/r66nv9ed.com: Filemoon CDN — system DNS resolves to wrong edge,
        // token is edge-bound so we need DoH to hit the correct server
        // cloudatacdn.com: Dood final CDN — DefaultHttpDataSource breaks after
        // ~4s with "UnknownHostException (no network)" mid-stream because the
        // HttpURLConnection keepalive drops; OkHttp + DoH keeps the connection
        // alive and retries cleanly.
        // cdndirector.dailymotion.com: Dailymotion HLS — token `sec=` est signé
        // sur la route IP/DNS qui a fait l'appel JSON. Notre extracteur passe
        // par OkHttp+DoH mais le DefaultHttpDataSource (HttpURLConnection +
        // DNS système) résout sur un autre edge -> token rejeté avec 403
        // (vu sur OPPO + NordVPN actif, 4 mai 2026).
        // anime-sama.* : storage CDN (s5/s22.anime-sama.fr) DNS-bloqué chez
        // certains FAI (Tahiti satellite). DoH by-passe ça (vu en log Chromecast :
        // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED + UnknownHostException).
        return url.contains("sprintcdn", ignoreCase = true)
            || url.contains("r66nv9ed.com", ignoreCase = true)
            || url.contains("cloudatacdn.com", ignoreCase = true)
            || url.contains("cdndirector.dailymotion.com", ignoreCase = true)
            || url.contains("dmcdn.net", ignoreCase = true)
            || url.contains("anime-sama.", ignoreCase = true)
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
        Log.d("PlayerNetwork", "Using DefaultHttpDataSource + LiveReconnecting wrapper")
        val base = DefaultHttpDataSource.Factory()
            .setUserAgent(NetworkClient.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        // 2026-05-10 : wrapper auto-reconnect sur EOF pour live MPEG-TS (cf PlayerTvFragment).
        return com.streamflixreborn.streamflix.utils.LiveReconnectingHttpDataSource.Factory(base)
    }

    private fun initializePlayer(extraBuffering: Boolean, softwareDecoder: Boolean = currentSoftwareDecoder, videoUrl: String = "") {
        releasePlayer()
        currentExtraBuffering = extraBuffering
        currentSoftwareDecoder = softwareDecoder

        httpDataSource = createHttpDataSourceFactory(videoUrl)

        dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)

        // 2026-05-09 : handleAudioFocus=false pour IPTV Live (cf PlayerTvFragment).
        // Empêche les pauses auto sur Bluetooth/notif/audio focus loss.
        val isLiveIptvHere = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
        player = buildPlayer(extraBuffering).also { player ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    !isLiveIptvHere,
                )

                val lang = UserPreferences.currentProvider?.language?.substringBefore("-")
                val tsBuilder = player.trackSelectionParameters.buildUpon()
                if (lang == "es") {
                    tsBuilder.setPreferredAudioLanguage("spa")
                }
                // 2026-05-09 : pour IPTV, prioriser AAC > AC3 > MP3 (anti EAC-3
                // qui n'est pas décodé par tous les hardware → "pas de son sur
                // Canal+ Live").
                val isLiveIptvCh = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                    args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                    args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                    args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                    args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
                if (isLiveIptvCh) {
                    tsBuilder.setPreferredAudioMimeTypes(
                        androidx.media3.common.MimeTypes.AUDIO_AAC,
                        androidx.media3.common.MimeTypes.AUDIO_AC3,
                        androidx.media3.common.MimeTypes.AUDIO_MPEG,
                    )
                }
                player.trackSelectionParameters = tsBuilder.build()

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
            activePlayerListener?.let { try { player.removeListener(it) } catch (_: Exception) {} }
            player.release()
        }
        activePlayerListener = null
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
