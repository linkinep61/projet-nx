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
import com.streamflixreborn.streamflix.fragments.player.settings.IptvFavorites
import com.streamflixreborn.streamflix.fragments.player.settings.IptvBannedServers
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
import com.streamflixreborn.streamflix.utils.EnrichmentTrigger
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
import com.streamflixreborn.streamflix.providers.IptvProvider
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

    /** Flag : a-t-on déjà auto-sélectionné un sous-titre OpenSubtitles ? */
    private var autoSubtitleApplied = false
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

    // IPTV: when all initial servers fail but progressive (OLA) servers may still arrive,
    // keep the player open and wait for additionalServer emissions instead of navigating up.
    private var awaitingMoreServers = false
    private var awaitTimeoutHandler: android.os.Handler? = null
    private var awaitTimeoutRunnable: Runnable? = null

    // Track the active Player.Listener so we can remove it before attaching a new one,
    // preventing listener leaks across retries (each old listener used to keep firing).
    private var activePlayerListener: androidx.media3.common.Player.Listener? = null

    /** IPTV retry counter — when a brief stall triggers onPlayerError, we re-prepare
     *  the SAME stream up to N times before giving up and switching servers. Resets
     *  to 0 on successful playback (STATE_READY) or when the user changes server. */
    private var iptvRetryCount = 0
    private val IPTV_MAX_RETRIES_SAME_STREAM = 3
    /** True once the current IPTV stream has reached STATE_READY at least once.
     *  When true, transient errors NEVER cause a server switch — we keep trying
     *  the same stream (the user explicitly asked for sticky servers). Reset when
     *  the user manually changes server or channel. */
    private var iptvCurrentStreamHasWorked = false
    // 2026-05-11 : pour VOD aussi — true dès STATE_READY pour différencier
    // "extracteur foireux jamais démarré" (skip 30s) vs "coupure pendant
    // lecture" (super-buffer 15s, no swap).
    private var vodCurrentStreamHasWorked = false

    private var currentVideo: Video? = null
    private var currentServer: Video.Server? = null
    // 2026-05-05 : watchdog buffering — déclenche le fallback au serveur suivant
    // si le player reste bloqué en STATE_BUFFERING > N secondes sans atteindre
    // STATE_READY. Indispensable pour les hosters genre Darkibox qui renvoient
    // une URL HTTP valide mais ne délivrent jamais de données vidéo (Cloudflare
    // block silencieux). Sans ce watchdog onPlayerError n'est jamais déclenché
    // et le player reste bloqué indéfiniment.
    private var bufferingWatchdog: kotlinx.coroutines.Job? = null
    private val BUFFERING_TIMEOUT_MS = 10_000L
    private var usingCronet = false
    /** Shared bounded executor for Cronet — avoids unbounded newCachedThreadPool */
    private val cronetExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)
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

    // 2026-05-12 : flag set in attachTransferredPlayer. Quand on vient du mini-player
    // (taper sur la mini pour passer en grand), le player est déjà en train de jouer
    // et on a juste swap les surfaces. Il NE FAUT PAS re-fetch les servers ni appeler
    // displayVideo, sinon le player se RESET avec un nouveau MediaItem et perd la
    // position de lecture en cours. Sans ce flag, mini→grand donne un grand player
    // qui se ré-initialise → metadata duration 0:05 → ENDED state → écran noir.
    private var attachedFromMiniPlayer = false
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

    // 2026-05-11 : overlay chargement TV (calqué sur PlayerMobileFragment).
    // Skip pour IPTV — buffer ExoPlayer gère déjà la sensation de chargement,
    // et l'overlay bloque le menu Settings sur ces providers.
    private val LOADING_OVERLAY_SHOW_DELAY_MS = 250L
    private val loadingShowHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var loadingShowRunnable: Runnable? = null
    private var loadingBarAnimator: android.animation.ObjectAnimator? = null

    private fun isIptvChannelContext(): Boolean {
        val id = args.id
        return id.startsWith("ch::") || id.startsWith("sport::") ||
            id.startsWith("ola::") || id.startsWith("ola_ep::") ||
            id.startsWith("vegeta::") || id.startsWith("vegeta_ep::") ||
            id.startsWith("livehub::") || id.startsWith("movixlivetv::") ||
            id.startsWith("sportlive::") || id.startsWith("match::") ||
            id.startsWith("vavoo::") || id.startsWith("bxt::")
    }

    private fun showLoadingOverlay() {
        if (isIptvChannelContext()) return
        if (_binding == null) return
        val overlay = binding.loadingOverlay
        if (overlay.isVisible) return

        loadingShowRunnable?.let { loadingShowHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (_binding == null) return@Runnable
            val ov = binding.loadingOverlay
            val bar = binding.loadingOverlayBar
            ov.visibility = View.VISIBLE
            bar.progress = 0
            loadingBarAnimator?.cancel()
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

    private fun hideLoadingOverlay() {
        if (_binding == null) return
        loadingShowRunnable?.let { loadingShowHandler.removeCallbacks(it) }
        loadingShowRunnable = null
        loadingBarAnimator?.cancel()
        loadingBarAnimator = null
        if (binding.loadingOverlay.isVisible) {
            binding.loadingOverlayBar.progress = 100
            binding.loadingOverlay.visibility = View.GONE
        }
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
                args.id.startsWith("match::") ||
                args.id.startsWith("vavoo::")
            com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
                .Settings.Server.currentIptvChannelKey =
                if (isIptvCtx) args.id else null
        }

        // Defer ExoPlayer creation to allow the layout to inflate and render first.
        // Codec enumeration is pre-warmed in StreamFlixApp so build() is fast (~100ms).
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (isAdded && _binding != null) {
                // 2026-05-12 : BoxXtemus (bxt::) — le transfer mini→grand pose
                // problème (leopard.ts a une métadonnée de durée finie qui fait
                // ENDED state au surface swap). Comportement comme les autres
                // providers : on STOP le mini et on RECHARGE depuis zéro dans
                // le grand player. C'est ce que le user attend.
                val isBoxXtemus = args.id.startsWith("bxt::")
                if (isBoxXtemus) {
                    MiniPlayerController.stop()
                    initializePlayer(false)
                } else {
                    val transferred = MiniPlayerController.transferPlayer()
                    if (transferred != null) {
                        attachTransferredPlayer(transferred)
                    } else {
                        initializePlayer(false)
                    }
                }
            }
        }
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
                // 2026-05-09 v3 : timeout 5s (au lieu de 0 qui les forçait toujours
                // visibles). ExoPlayer gère NATIVEMENT le bon comportement OK :
                //  - controls hidden + OK → show controls (consume, pas de pause)
                //  - controls visible + OK → focused button reçoit (= pause)
                // Avec timeout 5s, controls auto-hide → 1er OK les ré-affiche.
                binding.pvPlayer.controllerShowTimeoutMs = 5000
                binding.pvPlayer.controllerHideOnTouch = true
                binding.pvPlayer.controllerAutoShow = true
            }
        }
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
                    PlayerViewModel.State.LoadingServers -> {
                        // 2026-05-11 : afficher overlay chargement pendant
                        // l'extraction (skip pour IPTV — buffer ExoPlayer gère déjà).
                        showLoadingOverlay()
                    }
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

                        // 2026-05-12 : state.servers peut arriver AVANT que le lateinit
                        // player ne soit construit (Handler.post différe le init pour
                        // perf, mais la flow StateFlow réplay la valeur courante au
                        // subscriber dès .collect() — donc défile avant le post message).
                        // Avant : return@collect → handler jamais re-déclenché → écran
                        // bloqué (scénario double-click sans mini intermediate).
                        // Maintenant : on attend activement que le player soit init
                        // (poll 50ms, normalement < 500ms total).
                        if (!::player.isInitialized) {
                            Log.d("PlayerTvFragment", "state.servers received but player not init yet — waiting")
                            var waited = 0
                            while (!::player.isInitialized && isAdded && waited < 10_000) {
                                delay(50)
                                waited += 50
                            }
                            if (!isAdded || !::player.isInitialized) {
                                Log.w("PlayerTvFragment", "Gave up waiting for player init (added=$isAdded init=${::player.isInitialized})")
                                return@collect
                            }
                            Log.d("PlayerTvFragment", "Player init done after ${waited}ms, continuing state.servers handling")
                        }

                        // 2026-05-08 : pose la channelKey IPTV partagée pour le picker.
                        val isIptvCtxTv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                            args.id.startsWith("movixlivetv::") ||
                            args.id.startsWith("livehub::") ||
                            args.id.startsWith("sportlive::") ||
                            args.id.startsWith("match::") || args.id.startsWith("bxt::")
                        PlayerSettingsView.Settings.Server.currentIptvChannelKey =
                            if (isIptvCtxTv) args.id else null

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
                            // 2026-05-10 : feedback INSTANTANÉ au clic manuel pour
                            // éviter l'impression de "ça met une plombe à se déclencher".
                            // Sans ça : on continue à lire le serveur courant pendant
                            // toute la durée d'extraction du nouveau (jusqu'à 10s pour
                            // VidMoLy/Lpayer/etc.) → l'user pense que rien ne se passe.
                            // Avec ça : audio coupé immédiatement + Toast confirme.
                            try { player.stop() } catch (_: Exception) {}
                            try {
                                Toast.makeText(requireContext(),
                                    "Chargement ${server.name}…",
                                    Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                            // 2026-05-10 : fallback safe (le server cliqué peut être
                            // ajouté dynamiquement et absent de state.servers → NPE).
                            // Pas de !! qui crashait sur serveurs ajoutés dynamiquement
                            // (kick + replacement). Si pas dans state.servers, on
                            // construit un Video.Server à partir des données Settings.Server.
                            val target = state.servers.find { server.id == it.id }
                                ?: Video.Server(id = server.id, name = server.name)
                            viewModel.getVideo(target)
                        }
                        // Downloads disabled on TV — not enough storage
                        // binding.settings.setOnServerDownloadClickedListener { ... }
                        // binding.settings.onDownloadsClicked = { ... }

                        // IPTV ban callback: remove source and replace from pool
                        binding.settings.onChannelVariantBanned = { bannedVariant ->
                            val provider = UserPreferences.currentProvider
                            if (provider is com.streamflixreborn.streamflix.providers.OlaTvProvider) {
                                val replacement = provider.requestSingleReplacement(bannedVariant.id)
                                if (replacement != null) {
                                    val closeBracket = replacement.name.indexOf(']')
                                    val variantItem = PlayerSettingsView.Settings.ChannelVariant(
                                        id = replacement.id,
                                        name = if (closeBracket >= 0) replacement.name.substring(closeBracket + 1).trim()
                                               else replacement.name,
                                        channelKey = bannedVariant.channelKey,
                                    ).apply {
                                        isFavorite = IptvFavorites.isFavorite(bannedVariant.channelKey, replacement.id)
                                    }
                                    PlayerSettingsView.Settings.ChannelVariant.list.add(variantItem)
                                }
                                binding.settings.refreshChannelVariantList()
                            }
                        }

                        // 2026-05-09 v25 : Server (picker) ban callback.
                        // Logique demandée user :
                        //   1. Enlever le banni de la liste active (vraiment disparu)
                        //   2. Ajouter un remplaçant fresh à la place
                        //   3. Re-ajouter le banni TOUT EN BAS (= dossier Bannis)
                        //   4. Ainsi : N actifs en haut + Bannis en bas, séparés visuellement
                        binding.settings.onServerBanned = { bannedServer ->
                            val provider = UserPreferences.currentProvider
                            val srvChannelKey = (bannedServer as? PlayerSettingsView.Settings.Server)?.channelKey
                            if (srvChannelKey != null) {
                                // Étape 1 : enlever TOUS les bannis de la liste (clean slate)
                                val activeOnly = PlayerSettingsView.Settings.Server.list.filter { item ->
                                    !com.streamflixreborn.streamflix.fragments.player.settings.IptvBannedServers
                                        .isBanned(srvChannelKey, item.id)
                                }.toMutableList()
                                val bannedOnly = PlayerSettingsView.Settings.Server.list.filter { item ->
                                    com.streamflixreborn.streamflix.fragments.player.settings.IptvBannedServers
                                        .isBanned(srvChannelKey, item.id)
                                }
                                // Étape 2 : ajouter un remplaçant aux actifs (du pool provider)
                                val replacement: Video.Server? = when {
                                    provider is com.streamflixreborn.streamflix.providers.VegetaTvProvider &&
                                        bannedServer.id.startsWith("vegeta_stream::") -> {
                                        val allCurrentIds = (activeOnly + bannedOnly).map { it.id }.toSet()
                                        provider.requestSingleReplacement(bannedServer.id, allCurrentIds)
                                    }
                                    provider is com.streamflixreborn.streamflix.providers.OlaTvProvider &&
                                        bannedServer.id.startsWith("ola_stream::") -> {
                                        provider.requestSingleReplacement(bannedServer.id)
                                    }
                                    else -> null
                                }
                                if (replacement != null) {
                                    Log.d("PlayerTvFragment", "Server ban → fresh replacement: ${replacement.name}")
                                    activeOnly.add(
                                        PlayerSettingsView.Settings.Server(
                                            id = replacement.id,
                                            name = replacement.name,
                                        )
                                    )
                                }
                                // Étape 3 : reconstruire la liste : actifs d'abord, bannis tout en bas
                                PlayerSettingsView.Settings.Server.list.clear()
                                PlayerSettingsView.Settings.Server.list.addAll(activeOnly)
                                PlayerSettingsView.Settings.Server.list.addAll(bannedOnly)
                                Log.d("PlayerTvFragment", "Server picker reorganized: ${activeOnly.size} actifs + ${bannedOnly.size} bannis")
                            }
                            binding.settings.refreshServerList()
                        }

                        // IPTV favorite callback: just refresh UI (persistence handled by IptvFavorites)
                        binding.settings.onChannelVariantFavoriteToggled = { _ ->
                            binding.settings.refreshChannelVariantList()
                        }

                        // Chaîne starts empty — clear old entries from previous channel
                        PlayerSettingsView.Settings.ChannelVariant.list.clear()
                        binding.settings.refreshChannelVariantList()

                        // 2026-05-08 : favoris multi-server (max 5) + skip bannis
                        val orderedFavIds = IptvFavorites.getFavoritesForChannel(args.id)
                        val favServer = orderedFavIds.firstNotNullOfOrNull { favId ->
                            state.servers.firstOrNull { it.id == favId }
                        }
                        // Skip les bannis — l'user ne veut pas qu'un server grisé soit joué
                        val firstNonBanned = state.servers.firstOrNull { srv ->
                            !IptvBannedServers.isBanned(args.id, srv.id)
                        }
                        val initialServer = favServer ?: firstNonBanned ?: state.servers.first()
                        if (favServer != null) {
                            Log.d("PlayerTvFragment", "Favori prioritaire : ${favServer.name} (${orderedFavIds.size}/${IptvFavorites.MAX_FAVORITES_PER_CHANNEL})")
                        }
                        // 2026-05-12 : SKIP le re-fetch quand le player a été transféré depuis le
                        // mini-player. Le player joue déjà — re-fetch + displayVideo() reset le
                        // MediaItem, perd la position, et fini sur "00:05 / 00:05 ENDED".
                        if (attachedFromMiniPlayer) {
                            Log.d("PlayerTvFragment", "Skip viewModel.getVideo() — player already running (transferred from mini)")
                        } else {
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
                        }

                        is PlayerViewModel.State.SuccessLoadingVideo -> {
                            // Channel works — unmark as failed if it was, cancel any pending wait
                            UserPreferences.unmarkChannelFailed(args.id)
                            cancelAwaitMoreServers()
                            // 2026-05-10 : reset flag anti-rentrance car nouveau serveur OK
                            vodAutoSwitchInFlight = false
                            // 2026-05-11 : cache overlay chargement, ExoPlayer prend le relais.
                            hideLoadingOverlay()
                            PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)
                            PlayerSettingsView.Settings.SoftwareDecoder.init(false)
                            displayVideo(state.video, state.server)
                        }

                        is PlayerViewModel.State.FailedLoadingVideo -> {
                            // 2026-05-11 : ré-afficher overlay (on tente next server)
                            showLoadingOverlay()
                            // Drop the broken variant from the Chaîne page so dead entries
                            // don't pile up. Re-emitted next session if Phase 3 finds it.
                            pruneBrokenVariant(state.server)
                            // 2026-05-12 : flag broken instant + skip-broken pour le next
                            // (extraction failed = mort certaine).
                            com.streamflixreborn.streamflix.extractors.Extractor
                                .recordFailureExternal(state.server.name, "extraction-failed")
                            // IPTV: never auto-advance on extractor failure (same sticky
                            // policy as onPlayerError). Auto-jumping between OLA/Vegeta
                            // variants during initial loading was breaking playback.
                            val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                                args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                                args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                                args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                                args.id.startsWith("match::") || args.id.startsWith("bxt::")
                            // Skip-broken pour next : core matching pour zapper aussi
                            // les variantes (Movix — VidMoLy vs VidMoLy).
                            fun extractorCore(name: String): String {
                                val stripped = name.substringAfter(" — ").substringAfter(" · ").trim()
                                return stripped.substringBefore(" - ").substringBefore(" (").substringBefore(" [").trim().uppercase()
                            }
                            val brokenCoresFailed = com.streamflixreborn.streamflix.extractors.Extractor.brokenServerNames()
                                .map { extractorCore(it) }.filter { it.isNotBlank() }.toSet()
                            fun isBrokenSrv(srv: com.streamflixreborn.streamflix.models.Video.Server): Boolean {
                                if (brokenCoresFailed.isEmpty()) return false
                                return extractorCore(srv.name) in brokenCoresFailed
                            }
                            val currentIdx = servers.indexOf(state.server)
                            val nextServer = if (isLiveIptv) null
                                else if (currentIdx >= 0) {
                                    servers.drop(currentIdx + 1).firstOrNull { !isBrokenSrv(it) }
                                        ?: servers.getOrNull(currentIdx + 1)  // fallback brut si plus aucun sain
                                } else null
                            if (nextServer != null) {
                                viewModel.getVideo(nextServer)
                            } else if (tryNextChannelVariant(state.server)) {
                                // OLA channel variant fallback succeeded
                            } else {
                                val provider = UserPreferences.currentProvider
                                // For IPTV providers (WiTv / OlaTv) keep the player open and wait
                                // for additional progressive servers instead of closing immediately.
                                val isIptv = provider is com.streamflixreborn.streamflix.providers.IptvProvider
                                if (isIptv) {
                                    if (!awaitingMoreServers) {
                                        Log.d("PlayerTvFragment", "All initial servers failed — awaiting progressive sources…")
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
            }

            // Progressive additional servers (OLA TV streams go to Chaîne, others to Serveurs)
            val currentChannelKeyTv = args.id
                .removePrefix("ch::")
                .removePrefix("sport::")
                .removePrefix("ola_ep::")
                .removePrefix("ola::")

            // Coalesce many rapid emissions into a single UI refresh — without this,
            // dozens of progressive emissions saturate the main thread and trigger an ANR.
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
                        val label = server.name.substring(closeBracket + 2)

                        // Filter: ignore streams from a different channel
                        if (olaKey != currentChannelKeyTv) {
                            Log.d("PlayerTvFragment", "Ignoring stale OLA stream: ${server.name} (expected key=$currentChannelKeyTv)")
                            return@collect
                        }

                        val sameNameCount = PlayerSettingsView.Settings.ChannelVariant.list.count { it.name == label }
                        if (sameNameCount < 3) {
                            val newVariant = PlayerSettingsView.Settings.ChannelVariant(
                                id = server.id, name = label, channelKey = currentChannelKeyTv,
                            )
                            // Restore favorite state from persistence
                            if (IptvFavorites.isFavorite(currentChannelKeyTv, server.id)) {
                                newVariant.isFavorite = true
                            }
                            PlayerSettingsView.Settings.ChannelVariant.list.add(newVariant)
                            if (PlayerSettingsView.Settings.ChannelVariant.list.size == 1) {
                                PlayerSettingsView.Settings.ChannelVariant.list.first().isSelected = true
                            }
                            scheduleChannelRefresh()
                        }

                        binding.settings.setOnChannelVariantSelectedListener { variant ->
                            viewModel.getVideo(Video.Server(variant.id, variant.name))
                        }

                        val hasNonOla = servers.any { !it.name.startsWith("OLA[") }
                        val firstOla = !hasNonOla && PlayerSettingsView.Settings.ChannelVariant.list.size == 1
                        if (firstOla) {
                            viewModel.getVideo(server)
                        } else if (awaitingMoreServers) {
                            Log.d("PlayerTvFragment", "Awaiting more servers — trying newly arrived OLA stream: $label")
                            cancelAwaitMoreServers()
                            triedChannelVariantIds.add(server.id)
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
                        scheduleServerRefresh()
                        Log.d("PlayerTvFragment", "Additional server added: ${server.name}")

                        if (awaitingMoreServers) {
                            Log.d("PlayerTvFragment", "Awaiting more servers — trying newly arrived server: ${server.name}")
                            cancelAwaitMoreServers()
                            viewModel.getVideo(server)
                        }
                    }

                    binding.settings.setOnServerSelectedListener { sel ->
                        // 2026-05-10 : feedback INSTANTANÉ au clic manuel.
                        try { player.stop() } catch (_: Exception) {}
                        try {
                            Toast.makeText(requireContext(),
                                "Chargement ${sel.name}…",
                                Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                        val target = servers.find { sel.id == it.id }
                            ?: Video.Server(id = sel.id, name = sel.name)
                        viewModel.getVideo(target)
                    }
                    // Downloads disabled on TV
                }
            }

            // Stato Sottotitoli
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.subtitleState.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
                    .collect { state ->
                        when (state) {
                            PlayerViewModel.SubtitleState.Loading -> {}
                            is PlayerViewModel.SubtitleState.SuccessOpenSubtitles -> {
                                // 2026-05-07 : auto-download OpenSubtitles DÉSACTIVÉ.
                                // Le user active manuellement dans le menu Sous-titres.
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

            // Cleanup Handler leaks
            if (::progressHandler.isInitialized && ::progressRunnable.isInitialized) {
                progressHandler.removeCallbacks(progressRunnable)
            }
            cancelAwaitMoreServers()

            // Cleanup DaddyLive proxy WebView
            daddyLiveProxyWebView?.let {
                try { it.stopLoading(); it.destroy() } catch (_: Exception) {}
            }
            daddyLiveProxyWebView = null

            // Shutdown Cronet executor
            cronetExecutor.shutdownNow()

            channelZapJob?.cancel()
            channelOverlayHideJob?.cancel()
            binding.pvPlayer.onChannelUp = null
            binding.pvPlayer.onChannelDown = null
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

            // Hide external player button on TV — Projectivy Launcher intercepts ACTION_VIEW intents
            binding.pvPlayer.controller.binding.btnExoExternalPlayer.visibility = View.GONE

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
            // 2026-05-08 : ajout livehub:: pour TV Hub.
            // 2026-05-10 : ajout vavoo:: pour réactivation Vavoo standalone.
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
                    button.visibility = View.GONE
                    return
                }

                button.visibility = View.VISIBLE
                button.setOnClickListener {
                    if (!hasEpisode()) return@setOnClickListener

                    val videoType = args.videoType
                    val currentPos = player.currentPosition
                    val duration = player.duration
                    val hasFinished = player.hasFinished()
                    val ctx = requireContext()
                    val provider = UserPreferences.currentProvider ?: return@setOnClickListener

                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val watchItem: WatchItem? = when (videoType) {
                            is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                            is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                        }

                        watchItem?.apply {
                            isWatched = false
                            watchedDate = null
                            watchHistory = WatchItem.WatchHistory(
                                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                                lastPlaybackPositionMillis = currentPos,
                                durationMillis = duration
                            )
                        }

                        when (videoType) {
                            is Video.Type.Movie -> {
                                (watchItem as? Movie)?.let { database.movieDao().update(it) }
                                (watchItem as? Movie)?.let { UserDataCache.addMovieToContinueWatching(ctx, provider, it) }
                            }

                            is Video.Type.Episode -> {
                                (watchItem as? Episode)?.let { episode ->
                                    if (hasFinished) {
                                        episode.isWatched = true
                                        episode.watchedDate = Calendar.getInstance()
                                        episode.watchHistory = null
                                        database.episodeDao().resetProgressionFromEpisode(videoType.id)
                                        UserDataCache.removeEpisodeFromContinueWatching(ctx, provider, episode.id)
                                    }

                                    database.episodeDao().update(episode)
                                    if (!hasFinished) {
                                        (watchItem as? Episode)?.let { UserDataCache.addEpisodeToContinueWatching(ctx, provider, it) }
                                    }

                                    episode.tvShow?.let { tvShow ->
                                        database.tvShowDao().getById(tvShow.id)
                                    }?.let { tvShow ->
                                        val isWatchingValue = if (hasFinished) {
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

            // Resolve prev/next IDs depending on provider type
            val prevId: String?
            val nextId: String?

            when (provider) {
                is WiTvProvider -> {
                    prevId = provider.getPreviousChannelId(args.id)
                    nextId = provider.getNextChannelId(args.id)
                }
                is com.streamflixreborn.streamflix.providers.OlaTvProvider -> {
                    prevId = provider.getPreviousChannelId(args.id)
                    nextId = provider.getNextChannelId(args.id)
                }
                is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> {
                    prevId = provider.getPreviousChannelId(args.id)
                    nextId = provider.getNextChannelId(args.id)
                }
                is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> {
                    prevId = provider.getPreviousChannelId(args.id)
                    nextId = provider.getNextChannelId(args.id)
                }
                is com.streamflixreborn.streamflix.providers.VavooProvider -> {
                    prevId = provider.getPreviousChannelId(args.id)
                    nextId = provider.getNextChannelId(args.id)
                }
                is com.streamflixreborn.streamflix.providers.BoxXtemusProvider -> {
                    prevId = provider.getPreviousChannelId(args.id)
                    nextId = provider.getNextChannelId(args.id)
                }
                else -> {
                    btnPrevious.visibility = View.GONE
                    btnNext.visibility = View.GONE
                    return
                }
            }

            // Setup D-pad zapping (UP/DOWN channel switch with overlay)
            setupChannelZapping()

            btnPrevious.visibility = if (prevId != null) View.VISIBLE else View.GONE
            btnNext.visibility = if (nextId != null) View.VISIBLE else View.GONE

            if (prevId != null) {
                btnPrevious.setOnClickListener { navigateToChannel(prevId, provider) }
            }
            if (nextId != null) {
                btnNext.setOnClickListener { navigateToChannel(nextId, provider) }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // D-pad channel zapping with overlay
        // ═══════════════════════════════════════════════════════════════════

        private var channelZapJob: Job? = null
        private var pendingZapChannelId: String? = null
        private var channelOverlayHideJob: Job? = null
        private val CHANNEL_ZAP_DEBOUNCE_MS = 800L
        private val CHANNEL_OVERLAY_DISPLAY_MS = 3000L

        /**
         * Set up D-pad UP/DOWN channel zapping for IPTV.
         * Called from setupChannelNavigationButtons after the provider check.
         */
        private fun setupChannelZapping() {
            // 2026-05-09 v19 : zapping D-pad UP/DOWN DÉSACTIVÉ — l'user a demandé
            // de virer ce comportement (faisait n'importe quoi sur TV : changement
            // de chaîne involontaire à chaque interaction D-pad). On force les
            // callbacks à null pour que UP/DOWN passent au focus chain natif.
            binding.pvPlayer.onChannelUp = null
            binding.pvPlayer.onChannelDown = null
        }

        private fun scheduleChannelZap(channelId: String, channelNumber: Int, provider: Any?) {
            pendingZapChannelId = channelId
            showChannelOverlay(channelId, channelNumber, provider)

            // Cancel previous debounce and schedule new one
            channelZapJob?.cancel()
            channelZapJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(CHANNEL_ZAP_DEBOUNCE_MS)
                // Actually navigate to the channel
                navigateToChannel(channelId, provider)
            }
        }

        private fun showChannelOverlay(channelId: String, channelNumber: Int, provider: Any?) {
            val channelName = when (provider) {
                is WiTvProvider -> provider.getChannelDisplayName(channelId)
                is com.streamflixreborn.streamflix.providers.OlaTvProvider -> provider.getChannelDisplayName(channelId)
                is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> provider.getChannelDisplayName(channelId)
                is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> provider.getChannelDisplayName(channelId)
                is com.streamflixreborn.streamflix.providers.VavooProvider -> provider.getChannelDisplayName(channelId)
                is com.streamflixreborn.streamflix.providers.BoxXtemusProvider -> provider.getChannelDisplayName(channelId)
                else -> null
            } ?: channelId.removePrefix("ch::").removePrefix("sport::")
                .removePrefix("ola::").removePrefix("vegeta::").removePrefix("livehub::")
                .removePrefix("vavoo::").removePrefix("bxt::")
            val channelLogo = when (provider) {
                is WiTvProvider -> provider.getChannelPoster(channelId)
                is com.streamflixreborn.streamflix.providers.OlaTvProvider -> provider.getChannelPoster(channelId)
                is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> provider.getChannelPoster(channelId)
                is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> provider.getChannelPoster(channelId)
                is com.streamflixreborn.streamflix.providers.VavooProvider -> provider.getChannelPoster(channelId)
                is com.streamflixreborn.streamflix.providers.BoxXtemusProvider -> provider.getChannelPoster(channelId)
                else -> null
            }

            binding.tvChannelNumber.text = channelNumber.toString()
            binding.tvChannelName.text = channelName

            if (!channelLogo.isNullOrBlank()) {
                Glide.with(this)
                    .load(channelLogo)
                    .centerInside()
                    .into(binding.ivChannelLogo)
                binding.ivChannelLogo.visibility = View.VISIBLE
            } else {
                binding.ivChannelLogo.visibility = View.GONE
            }

            if (binding.layoutChannelOverlay.visibility != View.VISIBLE) {
                binding.layoutChannelOverlay.alpha = 0f
                binding.layoutChannelOverlay.visibility = View.VISIBLE
                binding.layoutChannelOverlay.animate().alpha(1f).setDuration(200).start()
            }

            // Auto-hide after delay
            channelOverlayHideJob?.cancel()
            channelOverlayHideJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(CHANNEL_OVERLAY_DISPLAY_MS)
                hideChannelOverlay()
            }
        }

        private fun hideChannelOverlay() {
            if (binding.layoutChannelOverlay.visibility == View.VISIBLE) {
                binding.layoutChannelOverlay.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction {
                        if (_binding != null) binding.layoutChannelOverlay.visibility = View.GONE
                    }
                    .start()
            }
        }

        private fun navigateToChannel(channelId: String, provider: Any?) {
            val channelName = when (provider) {
                is WiTvProvider -> provider.getChannelDisplayName(channelId)
                is com.streamflixreborn.streamflix.providers.OlaTvProvider -> provider.getChannelDisplayName(channelId)
                is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> provider.getChannelDisplayName(channelId)
                is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> provider.getChannelDisplayName(channelId)
                is com.streamflixreborn.streamflix.providers.VavooProvider -> provider.getChannelDisplayName(channelId)
                is com.streamflixreborn.streamflix.providers.BoxXtemusProvider -> provider.getChannelDisplayName(channelId)
                else -> null
            } ?: channelId
            val channelPoster = when (provider) {
                is WiTvProvider -> provider.getChannelPoster(channelId)
                is com.streamflixreborn.streamflix.providers.OlaTvProvider -> provider.getChannelPoster(channelId)
                is com.streamflixreborn.streamflix.providers.VegetaTvProvider -> provider.getChannelPoster(channelId)
                is com.streamflixreborn.streamflix.providers.LiveTvHubProvider -> provider.getChannelPoster(channelId)
                is com.streamflixreborn.streamflix.providers.VavooProvider -> provider.getChannelPoster(channelId)
                is com.streamflixreborn.streamflix.providers.BoxXtemusProvider -> provider.getChannelPoster(channelId)
                else -> null
            }

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

        /** Try the next untried OLA channel variant. Returns true if a variant was found and is being tried. */
        private val triedChannelVariantIds = mutableSetOf<String>()

        /** Keep the player open while progressive (OLA) servers are still being fetched. */
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

        /** Cancel the timeout AND clear the awaiting flag. */
        private fun cancelAwaitMoreServers() {
            awaitingMoreServers = false
            cancelAwaitTimeoutOnly()
        }

        private fun cancelAwaitTimeoutOnly() {
            awaitTimeoutRunnable?.let { awaitTimeoutHandler?.removeCallbacks(it) }
            awaitTimeoutHandler = null
            awaitTimeoutRunnable = null
        }

        /** Mark a server as tried and remove it from the Chaîne page (broken streams
         *  shouldn't pollute the visible list — they reappear next session if Phase 3
         *  finds them again). Also reports the URL so other variants pointing to the
         *  same dead upstream fail immediately. */
        private fun pruneBrokenVariant(server: Video.Server?) {
            if (server == null) return
            triedChannelVariantIds.add(server.id)
            val playingUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
            if (!playingUri.isNullOrBlank()) {
                try {
                    com.streamflixreborn.streamflix.providers.OlaTvProvider.reportBrokenStreamUrl(playingUri)
                } catch (_: Throwable) { }
            }
            val variants = PlayerSettingsView.Settings.ChannelVariant.list
            val removed = variants.removeAll { it.id == server.id }
            if (removed && _binding != null) {
                Log.d("PlayerTvFragment", "Pruned broken variant: ${server.name}")
                binding.settings.refreshChannelVariantList()
            }
        }

        private fun tryNextChannelVariant(failedServer: Video.Server?): Boolean {
            val variants = PlayerSettingsView.Settings.ChannelVariant.list
            if (variants.isEmpty()) return false

            // Mark the failed variant as tried AND remove it from the Chaîne page so
            // broken entries don't pile up. They reappear next session if Phase 3
            // finds them again.
            if (failedServer != null) {
                triedChannelVariantIds.add(failedServer.id)
                val removed = variants.removeAll { it.id == failedServer.id }
                if (removed && _binding != null) {
                    Log.d("PlayerTvFragment", "Removed broken variant from Chaîne page: ${failedServer.name}")
                    binding.settings.refreshChannelVariantList()
                }
            }

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
            // Reset IPTV stickiness when the user (or auto-failover) selects a NEW server.
            // This way the new server gets its own retry budget and can also become sticky.
            if (currentServer?.id != server.id) {
                iptvRetryCount = 0
                iptvCurrentStreamHasWorked = false
                vodCurrentStreamHasWorked = false
            }
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
            // Only rebuild the player for buffering/decoder changes.
            // DataSource mismatches are handled via explicit MediaSource — no costly release().
            val needsPlayerRebuild =
                extraBuffering != currentExtraBuffering || softwareDecoder != currentSoftwareDecoder

            if (dataSourceMismatch) {
                Log.d("PlayerNetwork", "DataSource mismatch: needsCronet=$urlNeedsCronet(was=$usingCronet), needsDoH=$urlNeedsDoH(was=$usingDoH) → hot-swap (no player rebuild)")
                httpDataSource = createHttpDataSourceFactory(video.source)
                dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)
            }

            if (needsPlayerRebuild) {
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

            // 2026-05-09 : pour IPTV live HLS, on configure un offset cible de 60s
            // derrière le live edge. Sans ça la barre de progression M3U va
            // jusqu'au bout (le playback head rattrape le live), créant des
            // coupures intempestives. Avec targetOffset 60s + speed 0.97-1.03,
            // le playback head reste stable au milieu du buffer.
            val isLiveIptvChannel = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
            // 2026-05-09 : roue de chargement masquée pour IPTV uniquement.
            // keepContentOnPlayerReset reset à false ici — il sera mis à true
            // uniquement juste avant les reloads auto-recovery pour ne pas casser
            // le transfert mini→fullscreen (view fraîche sans frame précédente).
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
            // 2026-05-09 v25 SIMPLIFICATION RADICALE : aucune LiveConfiguration custom.
            // ExoPlayer utilise les défauts du manifest HLS — comme TiviMate, comme notre
            // mini player. 200 lignes de "fixes" empilés aujourd'hui causaient les bugs
            // au lieu de les fixer. Less is more.
            val mediaItem = mediaItemBuilder.build()

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
            } else if (dataSourceMismatch && !needsPlayerRebuild) {
                // DataSource changed — create MediaSource with the updated factory
                // instead of rebuilding the entire player (avoids 5-14s release() ANR).
                val source = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
                player.setMediaSource(source)
                usingWebView = false
                Log.d("PlayerNetwork", "DataSource hot-swap: explicit MediaSource (no player rebuild)")
            } else {
                // 2026-05-11 : détection EXPLICITE HLS/DASH (parité PlayerMobileFragment).
                // L'auto-detect via setMediaItem marche dans 90% des cas mais peut louper
                // les .mpd quand le URL contient JWT/queryparams. Force le choix correct
                // de MediaSource pour 3BoxTV (DASH LCI live + HLS GitHub) et Vavoo.
                val urlEndsWithTs = video.source.substringBefore('?').endsWith(".ts", ignoreCase = true)
                val srcLowerNoQuery = video.source.lowercase().substringBefore('?')
                val isHls = !urlEndsWithTs && (
                    srcLowerNoQuery.contains(".m3u8")
                    || video.source.contains(".m3u8")
                    || video.type == androidx.media3.common.MimeTypes.APPLICATION_M3U8
                )
                val isDash = !isHls && !urlEndsWithTs && (
                    srcLowerNoQuery.endsWith(".mpd")
                    || video.type == androidx.media3.common.MimeTypes.APPLICATION_MPD
                )
                if (isHls) {
                    val hlsSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dataSourceFactory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(mediaItem)
                    player.setMediaSource(hlsSource)
                    Log.d("PlayerDebug", "TV: HlsMediaSource (explicit)")
                } else if (isDash) {
                    val dashSource = androidx.media3.exoplayer.dash.DashMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(mediaItem)
                    player.setMediaSource(dashSource)
                    Log.d("PlayerDebug", "TV: DashMediaSource (explicit, .mpd)")
                } else {
                    player.setMediaItem(mediaItem)
                    Log.d("PlayerDebug", "TV: setMediaItem (auto-detect + ExoPlayer defaults)")
                }
                usingWebView = false
            }

            // Hide external player button on TV to prevent Projectivy Launcher
            // (or any other launcher) from intercepting the ACTION_VIEW intent
            // and overlaying on top of our built-in ExoPlayer.
            binding.pvPlayer.controller.binding.btnExoExternalPlayer.visibility = View.GONE

            // Remove previous listener (if any) to avoid leaks across displayVideo() retries.
            activePlayerListener?.let { try { player.removeListener(it) } catch (_: Exception) {} }
            val newListener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)

                    // 2026-05-05 : watchdog buffering. Annule le timer dès qu'on
                    // sort de BUFFERING (READY, ENDED, IDLE).
                    if (playbackState != Player.STATE_BUFFERING) {
                        bufferingWatchdog?.cancel()
                        bufferingWatchdog = null
                    } else {
                        // Démarre le watchdog seulement pour VOD (pas IPTV qui a
                        // sa propre logique sticky).
                        val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
                        // 2026-05-11 (user) : 2 règles selon état du stream :
                        // (A) Pré-READY (extracteur silencieux) → skip 10s
                        //     (était 30s, baissé 2026-05-12 pour accélérer le fallback
                        //     quand plein de serveurs morts en série. Le HEAD check
                        //     pre-extract filtre déjà beaucoup en amont.)
                        // (B) Post-READY (coupure réseau) → 15s super-buffer same server
                        if (!isLiveIptv && bufferingWatchdog == null) {
                            val initialPosition = player.currentPosition
                            bufferingWatchdog = viewLifecycleOwner.lifecycleScope.launch {
                                if (!vodCurrentStreamHasWorked) {
                                    kotlinx.coroutines.delay(10_000L)
                                    if (player.playbackState == Player.STATE_BUFFERING &&
                                        player.currentPosition == initialPosition &&
                                        !vodCurrentStreamHasWorked) {
                                        val server = currentServer
                                        // 2026-05-12 : skip directement aux serveurs NON-broken.
                                        // Match par "core extractor" (ex: "VidMoLy") pour que
                                        // les variantes avec/sans "Movix —" préfix matchent.
                                        fun extractorCore(name: String): String {
                                            val stripped = name.substringAfter(" — ").substringAfter(" · ").trim()
                                            return stripped.substringBefore(" - ").substringBefore(" (").substringBefore(" [").trim().uppercase()
                                        }
                                        val brokenCores = com.streamflixreborn.streamflix.extractors.Extractor.brokenServerNames()
                                            .map { extractorCore(it) }.filter { it.isNotBlank() }.toSet()
                                        fun isServerBroken(srv: com.streamflixreborn.streamflix.models.Video.Server): Boolean {
                                            if (brokenCores.isEmpty()) return false
                                            return extractorCore(srv.name) in brokenCores
                                        }
                                        val currentIdx = server?.let { servers.indexOf(it) } ?: -1
                                        val nextServer = if (currentIdx >= 0) {
                                            // Cherche le prochain serveur NON-broken
                                            servers.drop(currentIdx + 1).firstOrNull { !isServerBroken(it) }
                                                ?: servers.getOrNull(currentIdx + 1)  // fallback brut si plus aucun sain
                                        } else null
                                        Log.w("PlayerNetwork",
                                            "Pre-READY 10s freeze on ${server?.name} → skip to ${nextServer?.name} (skipping broken)")
                                        if (server != null) {
                                            pruneBrokenVariant(server)
                                            // 2026-05-12 : flag instantanément le serveur broken
                                            // (10s playback freeze = mort certaine côté ExoPlayer).
                                            // Au prochain fallback, ce serveur sera skip → cascade rapide.
                                            com.streamflixreborn.streamflix.extractors.Extractor
                                                .recordFailureExternal(server.name, "pre-ready-freeze")
                                        }
                                        if (nextServer != null) viewModel.getVideo(nextServer)
                                    }
                                } else {
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
                        // 2026-05-10 : BUFFERING watchdog IPTV désactivé.
                        // Causait crash natif MediaCodec (pression heap JVM Chromecast)
                        // en empilant des reloads sur des chaînes instables comme TF1
                        // Vegeta. Le STATE_ENDED handler + préemptif drain couvrent
                        // déjà les cas critiques sans surcharger les codecs.
                    }

                    if (playbackState == Player.STATE_READY) {
                        // Stream playing successfully → reset IPTV retry counter
                        // and mark this server as "known good" so we never auto-switch
                        // away from it (only retry on transient errors).
                        if (iptvRetryCount > 0) {
                            Log.d("PlayerTvFragment", "Stream recovered, resetting IPTV retry counter")
                            iptvRetryCount = 0
                        }
                        if (!iptvCurrentStreamHasWorked) {
                            iptvCurrentStreamHasWorked = true
                            Log.d("PlayerTvFragment", "IPTV stream marked as working — sticky server enabled")
                            // 2026-05-09 v13 : refresh proactif du token Stalker toutes les 3 min,
                            // AVANT qu'il expire. Évite le 403 réactif qui cause un cut de 25s.
                            scheduleProactiveTokenRefresh()
                        }
                        // 2026-05-11 : VOD aussi flag pour différencier pre/post-READY
                        if (!vodCurrentStreamHasWorked) {
                            vodCurrentStreamHasWorked = true
                            Log.d("PlayerTvFragment", "VOD stream marked as working — no more auto-swap")
                        }
                        // Restore the normal 2s auto-hide for the controller
                        // (we forced it to 0 during IPTV extraction so the
                        // controls would be visible immediately).
                        if (binding.pvPlayer.controllerShowTimeoutMs == 0) {
                            binding.pvPlayer.controllerShowTimeoutMs = 2000
                        }
                        binding.pvPlayer.controller.binding.exoPlayPause.nextFocusDownId = -1
                        val videoFormat = player.videoFormat
                        updatePlayerScale()

                        // Preload adjacent channel servers for fast zapping
                        val isIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                            args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
                        if (isIptv) {
                            val provider = UserPreferences.currentProvider
                            if (provider is WiTvProvider) {
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                    provider.preloadAdjacentChannels(args.id)
                                }
                            }
                        }
                    }

                    // Live IPTV auto-resume: re-prepare on STATE_ENDED (playlist tail
                    // exhausted) AND on STATE_IDLE (transient network blip with no
                    // explicit error). Both states leave the player stuck until we
                    // re-trigger preparation.
                    val isLiveIptvStream = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                        args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                        args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                        args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                        args.id.startsWith("match::") || args.id.startsWith("bxt::")
                    if (isLiveIptvStream && (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE)) {
                        // Don't re-prepare immediately on STATE_IDLE if we never reached
                        // READY (initial load) — that would loop. Only auto-resume if
                        // the stream had been playing.
                        if (playbackState == Player.STATE_IDLE && !iptvCurrentStreamHasWorked) return
                        // 2026-05-10 : guard anti-rentrance. Pendant un displayVideo de
                        // recovery, le player passe par STATE_IDLE qui re-déclenchait ce
                        // handler → boucle de switches rapides. Si un reload est en cours,
                        // on ignore les transitions de player du reload lui-même.
                        if (preemptiveReloadInFlight) return
                        Log.w("PlayerTvFragment", "Live IPTV stuck in $playbackState — FULL RELOAD via displayVideo")
                        preemptiveReloadInFlight = true
                        // 2026-05-09 : viewModel.getVideo() ne marche PAS pour relancer
                        // le même flux car le StateFlow ignore l'émission identique
                        // (distinctUntilChanged) → displayVideo n'est jamais ré-appelé.
                        // Solution : appeler displayVideo() directement avec le current
                        // Video + currentServer. Ça refait toute la chaîne (player reset,
                        // setMediaItem, prepare) sans passer par le ViewModel.
                        try {
                            val cs = currentServer
                            val cv = currentVideo
                            if (cs != null && cv != null) {
                                // Masque les contrôles + active keep_content pour que la
                                // dernière frame reste figée pendant le re-prepare (au lieu
                                // d'écran noir).
                                // 2026-05-10 : keep_content RETIRÉ — causait image figée
                                // sur l'ancienne frame quand la SurfaceView ne se rafraîchit
                                // pas après le reset. Mieux vaut un bref flash noir qu'une
                                // image figée + flux qui continue derrière.
                                try {
                                    binding.pvPlayer.controller.hide()
                                } catch (_: Exception) {}
                                viewLifecycleOwner.lifecycleScope.launch {
                                    kotlinx.coroutines.delay(50L)
                                    if (_binding != null) {
                                        try {
                                            displayVideo(cv, cs)
                                        } catch (e: Exception) {
                                            Log.w("PlayerTvFragment", "displayVideo reload failed: ${e.message}")
                                        }
                                    }
                                    // 2026-05-10 : reset keep_content après 2s pour libérer
                                    // la surface — sinon l'image reste figée sur la dernière
                                    // frame même si le nouveau stream est en train de jouer.
                                    kotlinx.coroutines.delay(2_000L)
                                    if (_binding != null) {
                                        try { binding.pvPlayer.setKeepContentOnPlayerReset(false) } catch (_: Exception) {}
                                    }
                                    // Cooldown additionnel — laisse le stream se stabiliser.
                                    kotlinx.coroutines.delay(23_000L)
                                    preemptiveReloadInFlight = false
                                }
                            } else {
                                preemptiveReloadInFlight = false
                                player.prepare()
                                player.playWhenReady = true
                            }
                        } catch (e: Exception) {
                            preemptiveReloadInFlight = false
                            Log.w("PlayerTvFragment", "Auto-resume failed: ${e.message}")
                        }
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

                    // 2026-05-09 : pas de watchdog onIsPlayingChanged custom — un
                    // BUFFERING naturel met isPlaying=false, le watchdog déclenchait
                    // un getVideo() qui CRÉAIT la pause visible qu'il prétendait fixer.
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
                                    (watchItem as? Movie)?.let {
                                        database.movieDao().update(it)
                                        UserDataCache.syncMovieToCache(ctx, provider, it)
                                    }
                                }

                                is Video.Type.Episode -> {
                                    (watchItem as? Episode)?.let { episode ->
                                        if (hasFinished) {
                                            database.episodeDao()
                                                .resetProgressionFromEpisode(videoType.id)
                                            UserDataCache.removeEpisodeFromContinueWatching(ctx, provider, episode.id)
                                            queueNextEpisodeForContinueWatching(provider)
                                        }
                                        database.episodeDao().update(episode)
                                        if (!hasFinished) {
                                            UserDataCache.syncEpisodeToCache(ctx, provider, episode)
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
                                                    !hasReallyFinished || isStillWatching
                                            })
                                        }
                                    }
                                }
                            }
                        }
                        // 2026-05-09 : IPTV ne déclenche JAMAIS l'autoplay
                        // next-episode (bug BFMTV qui s'auto-skipait quand
                        // ExoPlayer croyait le live "fini").
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
                        // 2026-05-08 : sticky absolu IPTV après 1er READY.
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
                        val nextServer = servers.getOrNull(servers.indexOf(server) + 1)
                        if (nextServer != null) {
                            Log.w("PlayerNetwork", "Connection timeout on ${server.name}, auto-switching to ${nextServer.name}")
                            viewModel.getVideo(nextServer)
                        } else if (!tryNextChannelVariant(server)) {
                            Log.e("PlayerNetwork", "Connection timeout on ${server.name}, no more servers or variants to try")
                        }
                        return
                    }

                    // Fallback 3 (IPTV): for live channels, retry the same stream first.
                    // Sticky server: une fois qu'un server a fonctionné (STATE_READY)
                    // on NE BASCULE JAMAIS sur erreur transitoire — on re-prépare juste.
                    // Switch quand :
                    //   (a) le stream n'a jamais fonctionné ET retry > MAX_RETRIES_BEFORE_SWITCH
                    //   (b) erreur HTTP permanente (403/404/410/456 = blocked/dead)
                    //   (c) ERROR_CODE_IO_BAD_HTTP_STATUS répétée (= server bloqué)
                    val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                        args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                        args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                        args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                        args.id.startsWith("match::") || args.id.startsWith("bxt::")
                    if (isLiveIptv) {
                        val server = currentServer ?: return
                        val errCodeName = error.errorCodeName
                        iptvRetryCount++

                        // 2026-05-09 v3 : STICKY ABSOLU si stream a marché.
                        // Stream a fonctionné une fois → JAMAIS de switch auto,
                        // retry indéfiniment le même server (token expiry est
                        // souvent transitoire). User switche manuellement s'il veut.
                        // Switch SEULEMENT si stream n'a jamais marché.
                        val errMsg = (error.cause?.message ?: error.message ?: "").lowercase()
                        val isPermanentHttpError = errMsg.contains("response code: 403") ||
                            errMsg.contains("response code: 404") ||
                            errMsg.contains("response code: 410") ||
                            errMsg.contains("response code: 451") ||
                            errMsg.contains("response code: 456")
                        val MAX_RETRIES_BEFORE_SWITCH = 3
                        val shouldSwitch = !iptvCurrentStreamHasWorked &&
                            (isPermanentHttpError || iptvRetryCount >= MAX_RETRIES_BEFORE_SWITCH)

                        // 2026-05-10 : cooldown anti-cascade. Si un auto-switch s'est fait
                        // il y a moins de 10s, on ne switch pas encore — on laisse le retry
                        // sur le même serveur. Sinon on cycle entre tous les serveurs en
                        // ne laissant à chacun que ~1-2s pour démarrer.
                        val nowMs = System.currentTimeMillis()
                        val sinceLastSwitch = nowMs - lastAutoSwitchTime
                        val switchCooldownMs = 10_000L
                        // 2026-05-10 : si le server est un favori (cœur marqué), JAMAIS
                        // auto-switch même en phase initiale. L'user a explicitement
                        // marqué ce server comme préféré, on respecte son choix.
                        val isFavoriteServer = try {
                            com.streamflixreborn.streamflix.fragments.player.settings.IptvFavorites
                                .isFavorite(args.id, server.id)
                        } catch (_: Exception) { false }
                        if (shouldSwitch && isFavoriteServer) {
                            Log.d("PlayerTvFragment", "IPTV switch demandé mais server est favori — on retry à la place")
                        } else if (shouldSwitch && sinceLastSwitch < switchCooldownMs) {
                            Log.d("PlayerTvFragment", "IPTV switch demandé mais cooldown ${(switchCooldownMs - sinceLastSwitch)/1000}s — on retry à la place")
                        } else if (shouldSwitch) {
                            Log.w("PlayerTvFragment", "IPTV switch on ${server.name} ($errCodeName, retry=$iptvRetryCount, hasWorked=$iptvCurrentStreamHasWorked, permanentHttp=$isPermanentHttpError)")
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
                            Log.e("PlayerTvFragment", "IPTV switch demandé mais pas de server suivant disponible")
                        }

                        // 2026-05-12 (user) : HARD CAP pour éviter boucle infinie.
                        // Bug observé : BoxXtemus "Remarkably Bright Creatures" → URL
                        // résolue = page HTML (frembed.one/embed/movie/...), ExoPlayer
                        // renvoie ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED en boucle
                        // (33+ retry/30s), pas de server suivant → on bouclait sans fin.
                        // Si le stream n'a JAMAIS marché, retry >= 5, et pas de fallback,
                        // on abandonne avec un toast plutôt que de boucler à l'infini.
                        val HARD_RETRY_CAP = 5
                        val isParseError = errCodeName == "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" ||
                            errCodeName == "ERROR_CODE_PARSING_CONTAINER_MALFORMED" ||
                            errCodeName == "ERROR_CODE_PARSING_MANIFEST_MALFORMED" ||
                            errCodeName == "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED"
                        val noFallbackAvailable = servers.getOrNull(servers.indexOf(server) + 1) == null
                        if (!iptvCurrentStreamHasWorked && iptvRetryCount >= HARD_RETRY_CAP && (isParseError || noFallbackAvailable)) {
                            Log.e("PlayerTvFragment",
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

                        Log.w("PlayerTvFragment", "IPTV retry on ${server.name} ($errCodeName) — retry #$iptvRetryCount, sticky (hasWorked=$iptvCurrentStreamHasWorked)")
                        try {
                            // 2026-05-09 v13 : Stalker + 403 → fresh handshake via
                            // refreshServerUrl (re-handshake complet, pas juste cache).
                            val isStalker = server.id.startsWith("vegeta_stream::") ||
                                server.id.startsWith("ola_stream::")
                            if (isStalker && isPermanentHttpError) {
                                Log.d("PlayerTvFragment", "Stalker token expired → fresh handshake")
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
                                        // 2026-05-09 v23 : rotation avec cooldown 30s au lieu
                                        // de prune permanent. Server qui 403 = cooldown 30s,
                                        // pendant ce temps on essaie un autre. Après 30s, le
                                        // server redevient candidat → token a eu le temps de
                                        // se refresh upstream → rotation A → B → A → B
                                        // fonctionne. Si tous en cooldown, prend le moins récent.
                                        val now = System.currentTimeMillis()
                                        serverFailedAt[server.id] = now
                                        Log.w("PlayerTvFragment",
                                            "Server ${server.name} → cooldown 30s, rotation auto")
                                        val available = servers.filter { srv ->
                                            srv.id != server.id &&
                                            (now - (serverFailedAt[srv.id] ?: 0L)) >= SERVER_COOLDOWN_MS
                                        }
                                        val nextServer = available.firstOrNull()
                                            ?: servers.filter { it.id != server.id }
                                                .minByOrNull { serverFailedAt[it.id] ?: 0L }
                                            ?: servers.firstOrNull()
                                        if (nextServer != null) {
                                            lastAutoSwitchTime = now
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
                            Log.w("PlayerTvFragment", "Sticky retry prepare failed: ${e.message}")
                        }
                        return
                    }

                    // Fallback 4 (catch-all VOD): runs ONLY for film/anime/drama because
                    // the IPTV branch above already returned for ch::/ola::/vegeta::/etc.
                    // Any other error code (404, 410, manifest malformed, decoding failure)
                    // used to freeze the screen black; now we auto-advance to the next
                    // lecteur so a dead first server (vidmoly with expired token, etc.)
                    // hands off to VOE/Stape automatically.
                    run {
                        val server = currentServer ?: return
                        val errCodeName = error.errorCodeName
                        pruneBrokenVariant(server)
                        // 2026-05-11 (user) : RÈGLE 403/SERVEUR HS UNIQUEMENT pour TOUS
                        // les providers VOD (avant : seulement AnimeSama).
                        // User : "si la vidéo bug au lieu de reprendre sur le même
                        // serveur s'auto swap, c'est pas bon du tout". Donc on switche
                        // uniquement sur erreur PERMANENTE :
                        //   - HTTP 403/404/410/451/456/500/502/503 (serveur refuse/HS)
                        //   - UnknownHostException (DNS résout pas → host mort)
                        //   - ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (réseau bloque)
                        // Pour transitoire (parse, decoder, timeout, blip réseau) → STICKY
                        // (re-prepare le même server).
                        val errMsgLower = (error.cause?.message ?: error.message ?: "").lowercase()
                        val causeName = error.cause?.javaClass?.simpleName ?: ""
                        val isPermanentFailure =
                            errMsgLower.contains("response code: 403") ||
                            errMsgLower.contains("response code: 404") ||
                            errMsgLower.contains("response code: 410") ||
                            errMsgLower.contains("response code: 451") ||
                            errMsgLower.contains("response code: 456") ||
                            errMsgLower.contains("response code: 500") ||
                            errMsgLower.contains("response code: 502") ||
                            errMsgLower.contains("response code: 503") ||
                            errMsgLower.contains("unknownhostexception") ||
                            errMsgLower.contains("no network") ||
                            causeName == "UnknownHostException" ||
                            errCodeName == "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" ||
                            errCodeName == "ERROR_CODE_IO_DNS_FAILED"
                        val nextServer = if (!isPermanentFailure) {
                            // STICKY : transitoire → re-prepare le même server, pas de swap auto.
                            Log.w("PlayerNetwork",
                                "Transient VOD error on ${server.name} ($errCodeName: ${error.message}) — STICKY (re-prepare same server)")
                            try { player.prepare(); player.playWhenReady = true } catch (_: Exception) {}
                            null
                        } else {
                            servers.getOrNull(servers.indexOf(server) + 1)
                        }
                        if (nextServer != null) {
                            // 2026-05-10 v3 : flag anti-rentrance + player.stop().
                            // Sans ça, pendant que l'extracteur du nextServer tourne
                            // (jusqu'à 30s pour VidMoLy WebView), le player reste sur
                            // l'ancien MediaItem mort et replante en boucle, déclenchant
                            // des switches en cascade vers le même nextServer.
                            if (vodAutoSwitchInFlight) {
                                Log.d("PlayerNetwork",
                                    "Switch vers ${nextServer.name} déjà en cours, on ignore")
                                return@run
                            }
                            vodAutoSwitchInFlight = true
                            try { player.stop() } catch (_: Exception) {}
                            try { player.clearMediaItems() } catch (_: Exception) {}
                            Log.w(
                                "PlayerNetwork",
                                "Unhandled VOD player error on ${server.name} ($errCodeName: ${error.message}) — auto-switching to ${nextServer.name}"
                            )
                            viewModel.getVideo(nextServer)
                            // Reset le flag après 35s (max temps extraction WebView)
                            viewLifecycleOwner.lifecycleScope.launch {
                                kotlinx.coroutines.delay(35_000L)
                                vodAutoSwitchInFlight = false
                            }
                        } else if (!tryNextChannelVariant(server)) {
                            Log.e(
                                "PlayerNetwork",
                                "Unhandled VOD player error on ${server.name} ($errCodeName) and no more servers to try"
                            )
                        }
                    }
                }
            }
            player.addListener(newListener)
            activePlayerListener = newListener

            // 2026-05-09 v12 : pour IPTV live, seekToDefaultPosition (= live edge
            // moins targetOffsetMs = mid-bar). Évite le "stagne en timeout"
            // après URL refresh quand currentPosition pointe vers une position
            // qui n'existe plus dans le nouveau manifest.
            val isLiveIptvStream = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
            if (isLiveIptvStream) {
                player.seekToDefaultPosition()
            } else if (startPositionMs != null) {
                player.seekTo(startPositionMs)
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
            player.playWhenReady = shouldPlay

            // Signal HomeViewModel that the player is loading — safe to start enrichment now
            EnrichmentTrigger.notifyPlayerReady()
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

        private fun downloadFromServer(server: Video.Server) {
            // Ensure notification permission is granted (Android 13+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val perm = android.Manifest.permission.POST_NOTIFICATIONS
                if (requireContext().checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(perm), 1001)
                }
            }

            val providerName = UserPreferences.currentProvider?.name ?: "unknown"
            val videoType = currentVideoTypeForUi()

            Toast.makeText(requireContext(), "Résolution du lien…", Toast.LENGTH_SHORT).show()

            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val provider = UserPreferences.currentProvider ?: return@launch
                    val video = provider.getVideo(server)
                    if (video.source.isEmpty()) throw Exception("No source found")

                    // Block WebView-only sources
                    if (isVideoWebViewOnly(video)) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                "Ce serveur nécessite un navigateur, téléchargement impossible (${server.name})",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@launch
                    }

                    com.streamflixreborn.streamflix.download.DownloadManager.enqueue(
                        video = video,
                        videoType = videoType,
                        providerName = providerName,
                        serverName = server.name,
                    )
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            "Téléchargement ajouté : ${server.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    Log.e("PlayerTvFragment", "Download failed for server ${server.name}", e)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            "Erreur : ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        private fun isVideoWebViewOnly(video: Video): Boolean {
            if (video.needsWebViewClick && !video.webViewUrl.isNullOrBlank()) return true
            if (isLuluVdoCdn(video)) return true
            if (video.source.startsWith("data:")) return true
            if (video.source.contains("cfglobalcdn.com", ignoreCase = true)) return true
            return false
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
            // 2026-05-09 PISTE A v2 : tracking du drain buffer pour reload préemptif.
            // L'idée : sur MPEG-TS Progressive sain, le buffer ahead reste ~stable
            // (download = lecture). Si la connexion HTTP meurt côté serveur,
            // ExoPlayer reste isLoading=true (timeout TCP en cours) mais le buffer
            // commence à drainer linéairement. Quand on voit ahead diminuer 5s
            // d'affilée + descendre sous 25s, on déclenche le reload préemptif
            // pendant que l'ancien player a encore ~25s de buffer pour jouer.
            var lastAheadSec = -1
            var consecutiveDrainTicks = 0
            // 2026-05-10 : flag pour éviter le préemptif sur les streams qui ne
            // buffent jamais (serveurs lents qui livrent à 1:1 = ahead toujours 0/1).
            // Le préemptif ne tire QUE si on a vu un buffer sain (>= 15s) au moins
            // une fois — sinon c'est un faux positif (on ne peut pas anticiper un
            // drain sur un stream qui n'avait jamais d'avance).
            var bufferEverHealthy = false
            progressRunnable = Runnable {
                if (player.isPlaying) {
                    val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                        args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                        args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                        args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                        args.id.startsWith("match::") || args.id.startsWith("bxt::")
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
                            Log.d("PlayerTvFragment", "Live buffer: pos=${pos/1000}s buf=${buf/1000}s ahead=${aheadSec}s")
                        }
                        // Détection drain : ahead diminue strictement vs le tick précédent
                        if (lastAheadSec >= 0 && aheadSec < lastAheadSec) {
                            consecutiveDrainTicks++
                        } else {
                            consecutiveDrainTicks = 0
                        }
                        lastAheadSec = aheadSec
                        if (aheadSec >= 15) bufferEverHealthy = true
                        // Trigger : 5 ticks consécutifs en drain + ahead<25s
                        // + iptvCurrentStreamHasWorked + pas déjà un reload en cours
                        // + bufferEverHealthy (= on a vu >=15s d'avance au moins une fois).
                        if (consecutiveDrainTicks >= 5 && aheadSec < 25 && bufferEverHealthy &&
                            iptvCurrentStreamHasWorked && !preemptiveReloadInFlight) {
                            preemptiveReloadInFlight = true
                            consecutiveDrainTicks = 0
                            val cs = currentServer
                            val cv = currentVideo
                            if (cs != null && cv != null) {
                                Log.w("PlayerTvFragment",
                                    "PREEMPTIVE reload — buffer drain ${aheadSec}s, 5 ticks de baisse continue")
                                try {
                                    binding.pvPlayer.controller.hide()
                                } catch (_: Exception) {}
                                viewLifecycleOwner.lifecycleScope.launch {
                                    try {
                                        displayVideo(cv, cs)
                                    } catch (e: Exception) {
                                        Log.w("PlayerTvFragment", "Preemptive displayVideo failed: ${e.message}")
                                    }
                                    // Cooldown 20s pour pas redéclencher pendant que la
                                    // nouvelle connexion remplit son buffer.
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

        /** 2026-05-09 v3 : best practices HLS live — speed range 0.95-1.05
         *  (subtil, pas perceptible). Cible 25s derrière live edge. */
        private fun adjustLivePlaybackSpeed() {
            val offset = player.currentLiveOffset
            if (offset == androidx.media3.common.C.TIME_UNSET) return
            val targetSpeed = when {
                offset < 10_000 -> 0.95f      // < 10s : ralenti subtil
                offset < 20_000 -> 0.98f      // < 20s : ralenti très léger
                offset > 50_000 -> 1.05f      // > 50s : rattrape
                offset > 35_000 -> 1.02f      // > 35s : rattrape léger
                else -> 1.0f                  // 20-35s = cible
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

        // 2026-05-09 v13 : refresh proactif du token Stalker (Vegeta/Ola).
        // Schedule un refresh toutes les 3 min — avant que le token n'expire
        // naturellement, on récupère une fresh URL et on la set sur le player.
        // Le cut est court (~3-5s) et anticipé, pas un 25s subi.
        private var proactiveRefreshHandler: android.os.Handler? = null
        @Volatile private var emergencyRefreshInFlight = false
        @Volatile private var criticalReloadInFlight = false
        // 2026-05-09 PISTE A : flag pour empêcher plusieurs reloads préemptifs simultanés.
        @Volatile private var preemptiveReloadInFlight = false
        // 2026-05-09 v23 : timestamp dernier auto-switch pour éviter le ping-pong cascade.
        @Volatile private var lastAutoSwitchTime = 0L
        // 2026-05-10 : flag anti-rentrance pendant l'auto-switch VOD (Fallback 4).
        // Pendant l'extraction du nouveau serveur (jusqu'à ~30s pour VidMoLy WebView),
        // le player resterait sur l'ancien MediaItem mort et replanterait en boucle,
        // déclenchant des switches multiples vers le même nextServer. Ce flag bloque
        // les switches additionnels jusqu'au reset (35s).
        @Volatile private var vodAutoSwitchInFlight = false
        // 2026-05-09 v23 : cooldown par serveur (30s) après un échec — au lieu de
        // prune permanent. Permet la rotation A → B → A → B (les tokens se rechargent
        // pendant que l'autre joue). Map<serverId, timestamp last failed>.
        private val serverFailedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val SERVER_COOLDOWN_MS = 30_000L

        private fun scheduleProactiveTokenRefresh() {
            val server = currentServer ?: return
            // 2026-05-09 : seulement OlaTv (vrai Stalker portal). Vegeta utilise
            // Xtream-codes en MPEG-TS Progressive — un refresh casse la connexion
            // HTTP active (le serveur Xtream limite à 1 session par user et ferme
            // l'ancienne quand on en ouvre une nouvelle). Buffer drain à t=60s →
            // STATE_ENDED à t=90s. C'est exactement ce qui causait la pause à 1m30.
            val isStalker = server.id.startsWith("ola_stream::")
            if (!isStalker) return

            proactiveRefreshHandler?.removeCallbacksAndMessages(null)
            proactiveRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
            proactiveRefreshHandler?.postDelayed({
                val current = currentServer
                if (current != null && _binding != null) {
                    Log.d("PlayerTvFragment", "Proactive token refresh (60s) — fresh handshake")
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
                            Log.d("PlayerTvFragment", "Got fresh URL — switching seamlessly")
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
            // 2026-05-09 : ajout des préfixes IPTV oubliés (livehub::/sportlive::/match::)
            val isLiveIptv = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
            // 2026-05-09 : retour aux valeurs qui marchaient avant (30s/10s) — sur
            // Tahiti satellite, 15s/3s c'était trop fin et causait des BUFFERING fréquents.
            val loadControl = if (isLiveIptv) {
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        30_000,    // minBuffer
                        300_000,   // maxBuffer
                        10_000,    // playback start
                        1_000      // rebuffer threshold ultra court
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
                // 2026-05-09 v16 : nextlib namespace =
                // io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
                // (pas androidx.media3.decoder.ffmpeg). Pour IPTV on utilise
                // NextRenderersFactory qui enregistre auto FFmpeg AC3/E-AC3/MP2…
                val renderersFactory = if (isLiveIptv) {
                    Log.d("PlayerTvFragment", "Using NextRenderersFactory (nextlib FFmpeg) for IPTV")
                    io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(requireContext()).apply {
                        setEnableDecoderFallback(true)
                        // 2026-05-09 v19 : MODE_ON (pas PREFER) — la vidéo H264 reste
                        // sur le décodeur hardware Amlogic (sinon écran noir car
                        // FFmpeg software H264 trop lent sur Chromecast). FFmpeg
                        // sert UNIQUEMENT en fallback audio quand le HW refuse
                        // (AC3/E-AC3/MP2 non supportés en HW).
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
            // anime-sama.fr : DoH résout le DNS mais la connexion TCP/TLS échoue
            // sur certains réseaux (Tahiti satellite). Cronet utilise le stack
            // réseau Chromium (HTTP/3 QUIC, ECH, retry logic spécifique) qui
            // arrive à se connecter là où OkHttp échoue.
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
            // cdndirector.dailymotion.com / dmcdn.net : token `sec=` est signé
            // sur la route IP/DNS qui a fait l'appel JSON. Sans DoH ici, le
            // DefaultHttpDataSource résout sur un edge différent de celui qu'a
            // utilisé l'extracteur (OkHttp+DoH) -> 403. Vu sur OPPO + NordVPN,
            // s'applique aussi en TV (ChromeCast/AndroidTV).
            // anime-sama.* : storage CDN (s5/s22.anime-sama.fr) résout uniquement
            // sur .fr et le DNS système peut être DNS-bloqué chez certains FAI
            // (Tahiti satellite par ex). DoH by-passe ça. Vu en log :
            //   ERROR_CODE_IO_NETWORK_CONNECTION_FAILED + UnknownHostException (no network)
            // sur s22.anime-sama.fr → fix = utiliser OkHttp+DoH pour ce host.
            return url.contains("sprintcdn", ignoreCase = true)
                || url.contains("r66nv9ed.com", ignoreCase = true)
                || url.contains("cloudatacdn.com", ignoreCase = true)
                || url.contains("cdndirector.dailymotion.com", ignoreCase = true)
                || url.contains("dmcdn.net", ignoreCase = true)
                || url.contains("anime-sama.", ignoreCase = true)
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
                val cronetEngine = com.streamflixreborn.streamflix.StreamFlixApp.getCronetEngine(requireContext())
                    ?: throw IllegalStateException("CronetEngine not available")
                Log.d("PlayerNetwork", "Using CronetDataSource for vidzy.live (Chrome network stack)")
                usingCronet = true
                usingDoH = false
                CronetDataSource.Factory(cronetEngine as CronetEngine, cronetExecutor)
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
            Log.d("PlayerNetwork", "Using DefaultHttpDataSource + LiveReconnecting wrapper")
            val base = DefaultHttpDataSource.Factory()
                .setUserAgent(NetworkClient.USER_AGENT)
                .setConnectTimeoutMs(20_000)        // 2026-05-09 v25 : 15s → 20s (tolérant Tahiti/satellite)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true)
            // 2026-05-10 : wrapper qui reconnecte auto sur EOF pour live MPEG-TS
            // (Xtream-codes Vegeta/Ola/WiTv qui envoient Connection: close après
            // chaque chunk). Pour les segments HLS/VOD, le wrapper laisse l'EOF
            // passer normalement (détection URL au runtime).
            return com.streamflixreborn.streamflix.utils.LiveReconnectingHttpDataSource.Factory(base)
        }

        /**
         * Attach a transferred ExoPlayer from the mini player — zero codec re-init.
         */
        private fun attachTransferredPlayer(transferred: ExoPlayer) {
            Log.d("PlayerTvFragment", "Attaching transferred ExoPlayer from mini player")
            attachedFromMiniPlayer = true
            player = transferred
            if (!::httpDataSource.isInitialized) {
                httpDataSource = createDefaultHttpDataSourceFactory()
            }
            dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)
            // 2026-05-09 : pour IPTV Live, handleAudioFocus=false → le player ignore
            // les events audio focus (Bluetooth qui se connecte, notif système, autre
            // app qui joue un son) et continue à jouer comme une vraie TV. Pour VOD
            // on laisse true (normal qu'un film pause sur une notif).
            val isLiveIptvHere = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                args.id.startsWith("match::") || args.id.startsWith("vavoo::") || args.id.startsWith("bxt::")
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(), !isLiveIptvHere)
            val lang = UserPreferences.currentProvider?.language?.substringBefore("-")
            if (lang == "es") {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage("spa").build()
            }
            mediaSession = MediaSession.Builder(requireContext(), player).build()
            binding.pvPlayer.player = player
            binding.settings.player = player
            binding.settings.subtitleView = binding.pvPlayer.subtitleView
            binding.settings.onSubtitlesClicked = { viewModel.getSubtitles(args.videoType) }
            MiniPlayerController.clearTransitionFlag()
            // 2026-05-12 : pour les IPTV Live, le .ts a une métadonnée de durée
            // (5s pour leopard 3BoxTV, finite pour Xtream) — ExoPlayer end-of-streams
            // dès qu'il atteint cette durée. REPEAT_MODE_ONE force le re-loop sur
            // EOF (l'URL re-fetch potentiellement de nouvelles données). Force play
            // au cas où l'attach aurait pause.
            if (isLiveIptvHere) {
                player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            }
            if (!player.playWhenReady) {
                player.playWhenReady = true
            }
            Log.d("PlayerTvFragment", "Transferred player attached — no codec re-init needed (repeat=${player.repeatMode} playWhenReady=${player.playWhenReady})")
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

            // 2026-05-09 : handleAudioFocus=false pour IPTV Live (cf attachTransferredPlayer).
            val isLiveIptvHere2 = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
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
                        !isLiveIptvHere2,
                    )

                    val lang = UserPreferences.currentProvider?.language?.substringBefore("-")
                    val builder = player.trackSelectionParameters.buildUpon()
                    if (lang == "es") {
                        builder.setPreferredAudioLanguage("spa")
                    }
                    // 2026-05-09 : pour IPTV, prioriser AAC > AC3 > MP3 (plutôt
                    // qu'EAC-3 que le décodeur hardware Chromecast ne supporte
                    // pas → "pas de son sur Canal+ Live"). Si le stream a une
                    // piste AAC, ExoPlayer la préfère et le son marche.
                    val isLiveIptvCh = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
                        args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
                        args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
                        args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
                        args.id.startsWith("match::") || args.id.startsWith("bxt::")
                    if (isLiveIptvCh) {
                        builder.setPreferredAudioMimeTypes(
                            androidx.media3.common.MimeTypes.AUDIO_AAC,
                            androidx.media3.common.MimeTypes.AUDIO_AC3,
                            androidx.media3.common.MimeTypes.AUDIO_MPEG,
                        )
                    }
                    player.trackSelectionParameters = builder.build()

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
                // Detach then release — release() can block 5-14s on Chromecast
                // waiting for hardware codec slots to free. Since the player runs
                // on its own playback thread, release() is asynchronous and won't
                // block the main thread.
                activePlayerListener?.let { try { player.removeListener(it) } catch (_: Exception) {} }
                player.release()
            }
            activePlayerListener = null
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

        // 2026-05-09 : handleAudioFocus=false pour IPTV Live (cf initializePlayer).
        val isLiveIptvHere3 = args.id.startsWith("ch::") || args.id.startsWith("sport::") ||
            args.id.startsWith("ola::") || args.id.startsWith("ola_ep::") ||
            args.id.startsWith("vegeta::") || args.id.startsWith("vegeta_ep::") ||
            args.id.startsWith("livehub::") || args.id.startsWith("sportlive::") ||
            args.id.startsWith("match::") || args.id.startsWith("bxt::")
        player = buildPlayer(extraBuffering).also { player ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    !isLiveIptvHere3,
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
