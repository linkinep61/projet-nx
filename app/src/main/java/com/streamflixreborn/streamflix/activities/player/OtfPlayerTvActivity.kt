package com.streamflixreborn.streamflix.activities.player

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource

/**
 * 2026-06-04 (user "faut faire la même chose pour la version TV mais que pour
 * OTF bien sûr") :
 *
 * Activity TV dédiée OTF qui utilise ExoPlayer 2.19.1 (package
 * com.google.android.exoplayer2.*) strict pattern OTF TV V3.2 — pas Media3 1.8.0.
 *
 * Adaptée pour Android TV / Chromecast : gestion D-pad, fullscreen permanent,
 * pas de rotation forcée (TV toujours en landscape).
 *
 * Identique fonctionnellement à OtfPlayerActivity (mobile) : reproduit l'exact
 * comportement de l'app OTF TV V3.2 officielle, qui est laxiste sur les
 * discontinuities timestamp audio des flux OTF (France 5 etc.).
 */
class OtfPlayerTvActivity : Activity() {

    companion object {
        const val EXTRA_URL = "extra_otf_url"
        const val EXTRA_TITLE = "extra_otf_title"
        private const val TAG = "OtfPlayerTvActivity"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: StyledPlayerView
    private lateinit var channelsPanel: android.widget.LinearLayout
    private lateinit var channelsList: androidx.recyclerview.widget.RecyclerView
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retryInFlight = false
    private val recentRetries = ArrayDeque<Long>()
    private val MAX_RETRIES_IN_WINDOW = 5
    private val RETRY_WINDOW_MS = 60_000L
    private val RETRY_DELAY_MS = 1_500L
    private var otfChannels: List<com.streamflixreborn.streamflix.utils.OtfTvService.OtfChannel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = StyledPlayerView(this).apply {
            useController = true
            controllerAutoShow = false
            controllerHideOnTouch = false
            controllerShowTimeoutMs = 3_500
            setShowBuffering(StyledPlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            isFocusable = true
            isFocusableInTouchMode = true
            // 2026-06-04 (user "ceux aux extrémités → chaîne suivante/précédente") :
            //   On garde les boutons skip-prev/skip-next (aux extrémités) et on
            //   les transformera en switch channel. Rewind/forward masqués
            //   (au milieu, sans sens en LIVE).
            setShowRewindButton(false)
            setShowFastForwardButton(false)
            setShowPreviousButton(true)
            setShowNextButton(true)
        }
        // 2026-06-04 (user "reprends le menu noir qui s'affichait pour afficher
        //   les chaînes en plein écran") : panel latéral 360dp largeur, fond
        //   noir transparent, cohérent avec PlayerTvFragment.
        val density = resources.displayMetrics.density
        channelsList = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@OtfPlayerTvActivity)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            clipToPadding = false
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        val channelsTitle = android.widget.TextView(this).apply {
            text = "Chaînes"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
        }
        channelsPanel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#CC000000"))
            visibility = View.GONE
            addView(channelsTitle, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(channelsList, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            ))
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
            val panelLP = FrameLayout.LayoutParams(
                (360 * density).toInt(), // 360dp comme dans PlayerTvFragment
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            addView(channelsPanel, panelLP)
        }
        setContentView(root)

        // Hide system UI (immersive fullscreen TV)
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
        Log.d(TAG, "Starting OTF TV playback (ExoPlayer 2.19.1): $url")
        startPlayback(url)
        // Charge le catalogue OTF en background pour alimenter le panel chaînes
        loadChannelsAsync()
        // Hook les boutons skip-prev / skip-next du PlayerView pour qu'ils
        //   fassent switch channel (au lieu de leur action native).
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
    }

    /** Switch chaîne via les boutons skip-prev/skip-next (utilisé aussi par le panel chaînes). */
    private fun switchChannel(delta: Int) {
        if (otfChannels.isEmpty()) {
            Log.d(TAG, "switchChannel: catalogue pas chargé")
            return
        }
        val cur = otfChannels.firstOrNull { it.name.equals(currentTitle, ignoreCase = true) }
            ?: otfChannels.firstOrNull()
            ?: return
        // 2026-06-04 : tri TNT FR pour cohérence avec le panel
        val sameGroup = com.streamflixreborn.streamflix.utils.OtfTvService
            .sortChannelsFrenchTntOrder(otfChannels.filter { it.group == cur.group })
        val idx = sameGroup.indexOfFirst { it.name.equals(cur.name, ignoreCase = true) }
        if (idx < 0 || sameGroup.isEmpty()) return
        val size = sameGroup.size
        val newIdx = ((idx + delta) % size + size) % size
        val ch = sameGroup[newIdx]
        val newUrl = ch.urls.firstOrNull() ?: return
        Log.d(TAG, "switchChannel delta=$delta : ${cur.name} → ${ch.name}")
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

    private fun loadChannelsAsync() {
        Thread {
            try {
                // fetchChannels est suspend — on utilise runBlocking depuis ce Thread.
                val fetched = kotlinx.coroutines.runBlocking {
                    com.streamflixreborn.streamflix.utils.OtfTvService.fetchChannels()
                }
                otfChannels = fetched
                handler.post {
                    val sameGroupChannels = if (currentTitle != null) {
                        val cur = otfChannels.firstOrNull { it.name.equals(currentTitle, ignoreCase = true) }
                        if (cur != null) otfChannels.filter { it.group == cur.group }
                        else otfChannels
                    } else otfChannels
                    // 2026-06-04 : tri ordre TNT France (TF1, France 2, ... puis alpha).
                    val sortedChannels = com.streamflixreborn.streamflix.utils.OtfTvService
                        .sortChannelsFrenchTntOrder(sameGroupChannels)
                    channelsList.adapter = ChannelsAdapter(sortedChannels, currentTitle) { ch ->
                        ch.urls.firstOrNull()?.let { newUrl ->
                            Log.d(TAG, "User picked channel: ${ch.name}")
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
                            hideChannelsPanel()
                        }
                    }
                    (channelsPanel.getChildAt(0) as? android.widget.TextView)?.text =
                        "Chaînes (${sortedChannels.size})"
                }
            } catch (e: Exception) {
                Log.w(TAG, "loadChannelsAsync failed: ${e.message}")
            }
        }.start()
    }

    private fun showChannelsPanel() {
        channelsPanel.visibility = View.VISIBLE
        channelsList.requestFocus()
    }

    private fun hideChannelsPanel() {
        channelsPanel.visibility = View.GONE
        playerView.requestFocus()
    }

    /** Adapter pour la liste des chaînes — utilise R.layout.item_channel_list
     *  pour cohérence visuelle avec le panel chaînes de PlayerTvFragment. */
    private class ChannelsAdapter(
        private val items: List<com.streamflixreborn.streamflix.utils.OtfTvService.OtfChannel>,
        private val currentName: String?,
        private val onPick: (com.streamflixreborn.streamflix.utils.OtfTvService.OtfChannel) -> Unit,
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<ChannelsAdapter.VH>() {
        class VH(val view: android.view.View) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val logo: android.widget.ImageView = view.findViewById(com.streamflixreborn.streamflix.R.id.iv_channel_logo)
            val name: android.widget.TextView = view.findViewById(com.streamflixreborn.streamflix.R.id.tv_channel_name)
            val indicator: android.view.View = view.findViewById(com.streamflixreborn.streamflix.R.id.indicator_current)
        }

        override fun onCreateViewHolder(
            parent: android.view.ViewGroup,
            viewType: Int,
        ): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(com.streamflixreborn.streamflix.R.layout.item_channel_list, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            holder.name.text = ch.name
            val isCurrent = ch.name.equals(currentName, ignoreCase = true)
            holder.indicator.visibility = if (isCurrent) android.view.View.VISIBLE else android.view.View.GONE
            holder.name.setTextColor(if (isCurrent) 0xFF4A90E2.toInt() else 0xFFFFFFFF.toInt())
            if (!ch.logo.isNullOrBlank()) {
                com.bumptech.glide.Glide.with(holder.logo).load(ch.logo)
                    .placeholder(com.streamflixreborn.streamflix.R.drawable.glide_fallback_cover)
                    .error(com.streamflixreborn.streamflix.R.drawable.glide_fallback_cover)
                    .into(holder.logo)
            } else {
                holder.logo.setImageResource(com.streamflixreborn.streamflix.R.drawable.glide_fallback_cover)
            }
            holder.view.setOnClickListener { onPick(ch) }
            holder.view.setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundColor(if (hasFocus) 0x33FFFFFF.toInt() else 0x00000000)
            }
        }

        override fun getItemCount() = items.size
    }

    private fun startPlayback(url: String) {
        currentUrl = url
        // 2026-06-04 (user "améliorer la reprise du buffering pour éviter que
        //   le flux se fasse rattraper par la lecture") : LoadControl custom
        //   qui exige 5s d'avance après un re-buffering (au lieu du défaut très
        //   faible) → la lecture ne rattrape pas instantanément le live edge.
        val loadControl = com.google.android.exoplayer2.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
        player = exoPlayer
        playerView.player = exoPlayer

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

        playerView.requestFocus()
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 2026-06-04 v2 (user "D-pad gauche n'ouvre pas le menu chaînes ça
        //   active l'écran") : intercept AVANT le PlayerView via dispatchKeyEvent
        //   (sinon ExoPlayer consomme la touche pour seek).
        if (event.action == KeyEvent.ACTION_DOWN) {
            // 2026-06-04 v3 (user "quand on est sur le Player si je fais
            //   gauche ça affiche la liste des chaînes au lieu de se
            //   déplacer sur la gauche pour faire la chaîne précédente") :
            //   on n'ouvre le panel que si le controller est CACHÉ. Sinon
            //   on laisse passer pour que l'user navigue librement entre
            //   les boutons (skip-prev / play-pause / skip-next).
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (channelsPanel.visibility != View.VISIBLE
                        && !playerView.isControllerFullyVisible) {
                        showChannelsPanel()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (channelsPanel.visibility == View.VISIBLE) {
                        hideChannelsPanel()
                        return true
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (channelsPanel.visibility == View.VISIBLE) {
                        hideChannelsPanel()
                        return true
                    }
                    finish()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    switchChannel(+1); return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    switchChannel(-1); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
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
