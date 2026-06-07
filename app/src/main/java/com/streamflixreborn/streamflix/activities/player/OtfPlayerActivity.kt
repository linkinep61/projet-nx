package com.streamflixreborn.streamflix.activities.player

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.ui.StyledPlayerView

/**
 * 2026-06-04 (user "garde le pattern OTF comme l'application originelle jusqu'à
 * l'ExoPlayer de la vidéo") :
 *
 * Activity dédiée OTF qui utilise ExoPlayer 2.19.1 (package
 * com.google.android.exoplayer2.*) strict pattern OTF TV V3.2 — pas Media3 1.8.0.
 *
 * Raison : Media3 1.8.0 a un AudioSink strict qui rejette les discontinuities
 * timestamp audio des flux OTF (France 5 etc.) → boucle MediaCodec → OOM →
 * crash. ExoPlayer 2.19.1 (utilisé par OTF TV V3.2 officielle) est laxiste,
 * absorbe ces discontinuities silencieusement → lecture fluide.
 *
 * Les 2 ExoPlayer coexistent dans l'APK car packages distincts.
 */
class OtfPlayerActivity : Activity() {

    companion object {
        const val EXTRA_URL = "extra_otf_url"
        const val EXTRA_TITLE = "extra_otf_title"
        private const val TAG = "OtfPlayerActivity"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: StyledPlayerView
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retryInFlight = false
    private val recentRetries = ArrayDeque<Long>()
    private val MAX_RETRIES_IN_WINDOW = 5
    private val RETRY_WINDOW_MS = 60_000L
    private val RETRY_DELAY_MS = 1_500L
    // 2026-06-04 (user "chaîne suivante/précédente à la place des 4 boutons inutiles") :
    //   liste des chaînes OTF du MÊME GROUPE pour navigation prev/next.
    private var groupChannels: List<com.streamflixreborn.streamflix.utils.OtfTvService.OtfChannel> = emptyList()
    private var currentChannelIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // PlayerView programmatique (pas de layout XML pour éviter clash R)
        playerView = StyledPlayerView(this).apply {
            useController = true
            setShowBuffering(StyledPlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            // 2026-06-04 (user "ceux qui sont déjà sur chaque extrémité tu fais
            //   en sorte qu'ils fassent chaîne suivante et chaîne précédente") :
            //   on garde les boutons skip-prev/skip-next standards (aux extrémités)
            //   et on les transformera en switch channel. Rewind/forward (au
            //   milieu, qui n'ont pas de sens en live) restent masqués.
            setShowRewindButton(false)
            setShowFastForwardButton(false)
            setShowPreviousButton(true)
            setShowNextButton(true)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                playerView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(root)
        // Hook les boutons exo_prev / exo_next pour qu'ils fassent switch channel
        //   au lieu de leur action native (qui n'a aucun sens ici).
        playerView.post {
            try {
                playerView.findViewById<android.view.View>(
                    com.google.android.exoplayer2.ui.R.id.exo_prev
                )?.setOnClickListener { switchChannel(-1) }
                playerView.findViewById<android.view.View>(
                    com.google.android.exoplayer2.ui.R.id.exo_next
                )?.setOnClickListener { switchChannel(+1) }
            } catch (e: Exception) {
                Log.w(TAG, "hook prev/next failed: ${e.message}")
            }
        }

        // Hide system UI for fullscreen
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        val url = intent.getStringExtra(EXTRA_URL)
        currentTitle = intent.getStringExtra(EXTRA_TITLE)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "EXTRA_URL missing, finish")
            finish()
            return
        }
        Log.d(TAG, "Starting OTF playback (ExoPlayer 2.19.1): $url")
        startPlayback(url)
        loadGroupChannelsAsync()
    }

    /** Charge la liste des chaînes du même groupe (langue/pays) pour navigation prev/next. */
    private fun loadGroupChannelsAsync() {
        Thread {
            try {
                val all = kotlinx.coroutines.runBlocking {
                    com.streamflixreborn.streamflix.utils.OtfTvService.fetchChannels()
                }
                val cur = all.firstOrNull { it.name.equals(currentTitle, ignoreCase = true) }
                val rawList = if (cur != null) all.filter { it.group == cur.group } else all
                // 2026-06-04 (user "trier les chaînes OTF dans l'ordre des
                //   chaînes France") : tri TNT FR pour cohérence prev/next.
                val list = com.streamflixreborn.streamflix.utils.OtfTvService
                    .sortChannelsFrenchTntOrder(rawList)
                val idx = list.indexOfFirst { it.name.equals(currentTitle, ignoreCase = true) }
                handler.post {
                    groupChannels = list
                    currentChannelIndex = idx
                    Log.d(TAG, "Loaded ${list.size} channels in group (TNT order), current idx=$idx")
                }
            } catch (e: Exception) {
                Log.w(TAG, "loadGroupChannelsAsync failed: ${e.message}")
            }
        }.start()
    }

    /** delta = -1 → précédente, +1 → suivante. Wrap autour de la liste. */
    private fun switchChannel(delta: Int) {
        if (groupChannels.isEmpty() || currentChannelIndex < 0) {
            Log.d(TAG, "switchChannel: catalogue pas encore chargé, ignore")
            return
        }
        val size = groupChannels.size
        val newIdx = ((currentChannelIndex + delta) % size + size) % size
        val ch = groupChannels[newIdx]
        val newUrl = ch.urls.firstOrNull() ?: return
        Log.d(TAG, "switchChannel delta=$delta : ${currentTitle} → ${ch.name}")
        currentChannelIndex = newIdx
        currentTitle = ch.name
        currentUrl = newUrl
        recentRetries.clear()
        val dsf = DefaultHttpDataSource.Factory()
            .setUserAgent("ExoPlayerLib/2.19.1")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        val mi = MediaItem.fromUri(Uri.parse(newUrl))
        val ms = HlsMediaSource.Factory(dsf).createMediaSource(mi)
        player?.setMediaSource(ms)
        player?.prepare()
        player?.playWhenReady = true
        try {
            android.widget.Toast.makeText(this, ch.name, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun startPlayback(url: String) {
        currentUrl = url
        // 2026-06-04 (user "améliorer la reprise du buffering pour éviter que
        //   le flux se fasse rattraper par la lecture") : LoadControl custom
        //   qui exige 5s d'avance après un re-buffering. La lecture ne rattrape
        //   pas instantanément le live edge → moins de boucles cuts.
        val loadControl = com.google.android.exoplayer2.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
        player = exoPlayer
        playerView.player = exoPlayer

        // DataSource avec UA OTF (CDN stm.linkip.org accepte ce UA).
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("ExoPlayerLib/2.19.1")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaItem = MediaItem.fromUri(Uri.parse(url))
        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                Log.e(TAG, "Playback error: ${error.errorCodeName} — ${error.message}", error)
                scheduleRetry(reason = "onPlayerError ${error.errorCodeName}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    scheduleRetry(reason = "playbackState=$playbackState")
                }
                // 2026-06-04 (user "ça se fait rattraper et ça repart plus du
                //   tout") : watchdog BUFFERING — si on reste bloqué en
                //   buffering >20s, c'est anormal pour un live → on relance.
                if (playbackState == Player.STATE_BUFFERING) {
                    handler.removeCallbacks(bufferingWatchdog)
                    handler.postDelayed(bufferingWatchdog, 20_000L)
                } else {
                    handler.removeCallbacks(bufferingWatchdog)
                }
            }
        })
    }

    private val bufferingWatchdog = Runnable {
        Log.w(TAG, "Stuck in BUFFERING >20s — auto retry")
        scheduleRetry(reason = "stuck buffering 20s")
    }

    /** 2026-06-04 : retry auto avec anti-flap (max 5 retries / 60s, 1.5s entre). */
    private fun scheduleRetry(reason: String) {
        if (retryInFlight) return
        val url = currentUrl ?: return
        val now = System.currentTimeMillis()
        recentRetries.removeAll { (now - it) > RETRY_WINDOW_MS }
        if (recentRetries.size >= MAX_RETRIES_IN_WINDOW) {
            Log.e(TAG, "Too many retries (${recentRetries.size} in ${RETRY_WINDOW_MS}ms) — stop")
            return
        }
        recentRetries.addLast(now)
        retryInFlight = true
        Log.w(TAG, "Auto retry scheduled (${recentRetries.size}/$MAX_RETRIES_IN_WINDOW) — reason: $reason")
        handler.postDelayed({
            try {
                val p = player ?: return@postDelayed
                val dsf = DefaultHttpDataSource.Factory()
                    .setUserAgent("ExoPlayerLib/2.19.1")
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(true)
                val mi = MediaItem.fromUri(Uri.parse(url))
                val ms = HlsMediaSource.Factory(dsf).createMediaSource(mi)
                p.setMediaSource(ms)
                p.prepare()
                p.playWhenReady = true
                Log.d(TAG, "Retry done")
            } catch (e: Exception) {
                Log.w(TAG, "Retry failed: ${e.message}")
            } finally {
                retryInFlight = false
            }
        }, RETRY_DELAY_MS)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {}
        player = null
    }
}
