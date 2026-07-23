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
        /** 2026-07-20 : liste COMPLÈTE des URLs de la chaîne (plusieurs CDN / qualités).
         *  Le player bascule sur la suivante quand une URL échoue, au lieu de re-tenter
         *  indéfiniment la même (cause de l'écran noir quand un CDN meurt). */
        const val EXTRA_URLS = "extra_otf_urls"
        const val EXTRA_TITLE = "extra_otf_title"
        /** 2026-07-20 : clé EXACTE (normalizedKey) de la chaîne. Sans elle, le player se
         *  localisait dans le catalogue par le TITRE ; si le titre affiché différait du nom du
         *  catalogue, l'index valait -1 et **prev/next ne faisait plus rien** (« quand je veux
         *  changer de chaîne ça change pas »). */
        const val EXTRA_KEY = "extra_otf_key"
        private const val TAG = "OtfPlayerActivity"
    }

    private var currentKey: String? = null

    /** Toutes les URLs candidates de la chaîne courante, triées CDN vivant d'abord. */
    private var urlList: List<String> = emptyList()
    private var urlIndex = 0

    private var player: ExoPlayer? = null
    private lateinit var playerView: StyledPlayerView
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retryInFlight = false
    private val recentRetries = ArrayDeque<Long>()
    // 2026-07-20 : relevé de 5 → 12 car chaque retry teste désormais un CDN DIFFÉRENT
    //   (une chaîne peut avoir 7-8 URLs) : il faut pouvoir tous les essayer.
    private val MAX_RETRIES_IN_WINDOW = 12
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

        val single = intent.getStringExtra(EXTRA_URL)
        val many = intent.getStringArrayListExtra(EXTRA_URLS)?.filter { it.isNotBlank() } ?: emptyList()
        currentTitle = intent.getStringExtra(EXTRA_TITLE)
        currentKey = intent.getStringExtra(EXTRA_KEY)
        urlList = com.streamflixreborn.streamflix.utils.OtfTvService.orderUrlsByCdnHealth(
            (listOfNotNull(single) + many),
        )
        urlIndex = 0
        val url = urlList.firstOrNull()
        if (url.isNullOrBlank()) {
            Log.w(TAG, "EXTRA_URL missing, finish")
            finish()
            return
        }
        Log.d(TAG, "Starting OTF playback (ExoPlayer 2.19.1): ${urlList.size} URL(s) candidates — 1re: $url")
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
                // 2026-07-20 : on se localise d'abord par la CLÉ exacte (fiable), puis par le titre.
                val cur = currentKey?.let { k -> all.firstOrNull { it.normalizedKey == k } }
                    ?: all.firstOrNull { it.name.equals(currentTitle, ignoreCase = true) }
                val rawList = if (cur != null) all.filter { it.group == cur.group } else all
                // 2026-06-04 (user "trier les chaînes OTF dans l'ordre des
                //   chaînes France") : tri TNT FR pour cohérence prev/next.
                val list = com.streamflixreborn.streamflix.utils.OtfTvService
                    .sortChannelsFrenchTntOrder(
                        com.streamflixreborn.streamflix.utils.OtfTvService.dedupeChannels(rawList),
                    )
                val idx = list.indexOfFirst {
                    (currentKey != null && it.normalizedKey == currentKey) ||
                        it.name.equals(currentTitle, ignoreCase = true)
                }
                handler.post {
                    groupChannels = list
                    // Si on ne se retrouve pas dans la liste, on se place quand même au début :
                    //   prev/next doit RESTER utilisable (avant : index -1 = boutons morts).
                    currentChannelIndex = if (idx >= 0) idx else 0
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
        // 2026-07-20 : on saute les chaînes sans AUCUNE source vivante (CDN mort) — sinon
        //   « chaîne suivante » s'arrêtait sur une chaîne injouable, bouclait sur des erreurs
        //   réseau, et le user avait l'impression que le zapping ne marchait pas.
        var newIdx = ((currentChannelIndex + delta) % size + size) % size
        var guard = 0
        while (guard < size && !com.streamflixreborn.streamflix.utils.OtfTvService
                .hasLiveSource(groupChannels[newIdx])
        ) {
            newIdx = ((newIdx + delta) % size + size) % size
            guard++
        }
        val ch = groupChannels[newIdx]
        urlList = com.streamflixreborn.streamflix.utils.OtfTvService.orderUrlsByCdnHealth(ch.urls)
        urlIndex = 0
        val newUrl = urlList.firstOrNull() ?: return
        Log.d(TAG, "switchChannel delta=$delta : ${currentTitle} → ${ch.name}")
        currentChannelIndex = newIdx
        currentTitle = ch.name
        currentKey = ch.normalizedKey
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

        // Android Auto : publier ce lecteur OTF (ExoPlayer 2.19.1) au pont voiture → projection miroir.
        com.streamflixreborn.streamflix.car.CarPlaybackBridge.attach(
            token = exoPlayer,
            title = intent.getStringExtra(EXTRA_TITLE) ?: "Live",
            setSurface = { s -> try { exoPlayer.setVideoSurface(s) } catch (_: Exception) {} },
            getIsPlaying = { runCatching { exoPlayer.playWhenReady }.getOrDefault(false) },
            setPlaying = { pw -> runCatching { exoPlayer.playWhenReady = pw } },
            reattach = { try { playerView.player = null; playerView.player = exoPlayer } catch (_: Exception) {} },
        )

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
        // 2026-07-20 : on ne re-tente PLUS la même URL en boucle. À chaque échec on passe au CDN
        //   suivant de la chaîne — c'est ce qui débloque l'écran noir quand un CDN meurt.
        val url = if (urlList.size > 1) {
            urlIndex = (urlIndex + 1) % urlList.size
            urlList[urlIndex].also {
                currentUrl = it
                Log.w(TAG, "Bascule CDN → URL ${urlIndex + 1}/${urlList.size}: ${it.take(70)}")
            }
        } else currentUrl ?: return
        val now = System.currentTimeMillis()
        recentRetries.removeAll { (now - it) > RETRY_WINDOW_MS }
        // 2026-07-20 : si AUCUNE URL de cette chaîne n'est sur un CDN vivant, inutile d'insister
        //   12 fois — on abandonne vite et on le DIT, au lieu de laisser un écran noir muet.
        val noLiveSource = com.streamflixreborn.streamflix.utils.OtfTvService
            .orderUrlsByCdnHealth(urlList).none { u ->
                !u.contains("dencreak.com", true) && !u.contains("stm.linkip.org", true)
            }
        if (noLiveSource && recentRetries.size >= 2) {
            Log.e(TAG, "Chaîne sans CDN vivant — abandon")
            try {
                android.widget.Toast.makeText(
                    this,
                    "${currentTitle ?: "Cette chaîne"} est indisponible (serveur hors ligne)",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } catch (_: Exception) {}
            return
        }
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
            player?.let { com.streamflixreborn.streamflix.car.CarPlaybackBridge.detach(it) }
            player?.stop()
            player?.release()
        } catch (_: Exception) {}
        player = null
    }
}
