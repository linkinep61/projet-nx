package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.WiTvProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri

/**
 * Singleton controller for the mini player on the WiTV home page.
 * Manages ExoPlayer lifecycle independently from the home fragment.
 */
object MiniPlayerController {

    private const val TAG = "MiniPlayer"

    /** Global interceptor for IPTV channel clicks.
     *  Return true = click handled (play in mini player), false = let normal player handle it. */
    var onIptvChannelClick: ((TvShow) -> Boolean)? = null

    sealed class State {
        data object Idle : State()
        data class Loading(val channelId: String, val channelName: String) : State()
        data class Playing(val channelId: String, val channelName: String, val channelPoster: String?) : State()
        data class Error(val channelId: String, val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var player: ExoPlayer? = null
    private var loadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Current channel info
    var currentChannelId: String? = null
        private set
    var currentChannelName: String? = null
        private set
    var currentChannelPoster: String? = null
        private set

    fun getPlayer(): ExoPlayer? = player

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}", error)
            val chId = currentChannelId ?: return
            _state.value = State.Error(chId, error.message ?: "Playback error")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val chId = currentChannelId ?: return
                    _state.value = State.Playing(chId, currentChannelName ?: "", currentChannelPoster)
                }
                Player.STATE_BUFFERING -> {
                    // keep Loading state
                }
                Player.STATE_ENDED, Player.STATE_IDLE -> {
                    // no-op
                }
            }
        }
    }

    fun initPlayer(context: Context) {
        if (player != null) return

        val httpDataSource = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSource)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 15_000,
                /* maxBufferMs */ 50_000,
                /* bufferForPlaybackMs */ 2_500,
                /* bufferForPlaybackAfterRebufferMs */ 5_000
            )
            .build()

        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus */ true
            )
            .build().apply {
                playWhenReady = true
                addListener(playerListener)
            }
        Log.d(TAG, "ExoPlayer initialized")
    }

    fun playChannel(channelId: String, channelName: String, channelPoster: String?) {
        Log.d(TAG, "playChannel: $channelName ($channelId)")
        currentChannelId = channelId
        currentChannelName = channelName
        currentChannelPoster = channelPoster
        _state.value = State.Loading(channelId, channelName)

        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val provider = UserPreferences.currentProvider
                if (provider !is WiTvProvider) {
                    _state.value = State.Error(channelId, "Not a WiTV provider")
                    return@launch
                }

                // Build VideoType for the channel
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
                    season = Video.Type.Episode.Season(number = 1, title = "Live"),
                )

                // Get servers
                val servers = withContext(Dispatchers.IO) {
                    provider.getServers(channelId, videoType)
                }
                if (servers.isEmpty()) {
                    _state.value = State.Error(channelId, "No servers found")
                    return@launch
                }

                // Get video from first server
                val video = withContext(Dispatchers.IO) {
                    provider.getVideo(servers.first())
                }
                if (video.source.isEmpty()) {
                    _state.value = State.Error(channelId, "No source found")
                    return@launch
                }

                // Ensure we're still on the same channel
                if (currentChannelId != channelId) {
                    Log.d(TAG, "Channel changed while loading, ignoring result")
                    return@launch
                }

                // Play
                val p = player ?: return@launch
                val headers = video.headers ?: emptyMap()
                if (headers.isNotEmpty()) {
                    // Re-create data source with headers
                    val httpDs = DefaultHttpDataSource.Factory()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .setDefaultRequestProperties(
                            mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36") + headers
                        )
                        .setConnectTimeoutMs(15_000)
                        .setReadTimeoutMs(15_000)
                        .setAllowCrossProtocolRedirects(true)
                    // We can't change the data source factory mid-flight on same player,
                    // so we set default request properties
                }

                val mediaItem = MediaItem.Builder()
                    .setUri(video.source.toUri())
                    .setMimeType(video.type)
                    .build()

                p.setMediaItem(mediaItem)
                p.prepare()
                p.play()

                Log.d(TAG, "Playing: ${video.source.take(80)}")

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading channel: ${e.message}", e)
                if (currentChannelId == channelId) {
                    _state.value = State.Error(channelId, e.message ?: "Unknown error")
                }
            }
        }
    }

    fun stop() {
        loadJob?.cancel()
        player?.stop()
        player?.clearMediaItems()
        currentChannelId = null
        currentChannelName = null
        currentChannelPoster = null
        _state.value = State.Idle
    }

    /**
     * Transfers the live ExoPlayer to the fullscreen player WITHOUT stopping or
     * releasing it.  The fullscreen fragment attaches it to its own PlayerView,
     * so playback continues seamlessly with zero codec re-init cost.
     *
     * Returns the ExoPlayer (or null if nothing is playing).
     * After this call MiniPlayerController is Idle and holds no player reference.
     */
    @Volatile var transitioningToFullscreen = false

    fun transferPlayer(): ExoPlayer? {
        loadJob?.cancel()
        transitioningToFullscreen = true
        val p = player ?: return null.also { transitioningToFullscreen = false }
        // Detach listener so mini player state changes don't fire
        try { p.removeListener(playerListener) } catch (_: Exception) {}
        player = null
        currentChannelId = null
        currentChannelName = null
        currentChannelPoster = null
        _state.value = State.Idle
        Log.d(TAG, "Player transferred to fullscreen")
        // transitioningToFullscreen will be cleared by the fullscreen fragment
        // once it has attached the player
        return p
    }

    /** Clear the transition flag (called by fullscreen player after attach). */
    fun clearTransitionFlag() {
        transitioningToFullscreen = false
    }

    fun stopAsync() {
        loadJob?.cancel()
        transitioningToFullscreen = true
        val p = player
        player = null
        if (p != null) {
            try { p.removeListener(playerListener) } catch (_: Exception) {}
        }
        // Store for deferred release — don't touch ExoPlayer at all now
        detachedPlayer = p
        currentChannelId = null
        currentChannelName = null
        currentChannelPoster = null
        _state.value = State.Idle
        Log.d(TAG, "Player stopped (stopAsync), deferred release pending")
        // Release AFTER the fullscreen player has fully initialized (3s)
        if (p != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    p.release()
                    Log.d(TAG, "Detached player released (stopAsync deferred)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing in stopAsync: ${e.message}")
                }
                transitioningToFullscreen = false
            }, 3000)
        } else {
            transitioningToFullscreen = false
        }
    }

    /**
     * Detaches the ExoPlayer reference and keeps channel info (id, name, poster)
     * so the mini player can restart the same channel when returning
     * from full-screen mode.
     *
     * The ExoPlayer is NOT released here — call releaseDetachedPlayer()
     * after navigation has completed to avoid blocking the main thread.
     */
    private var detachedPlayer: ExoPlayer? = null

    fun releasePlayerKeepState() {
        loadJob?.cancel()
        detachedPlayer = player
        player = null
        // Keep currentChannelId / currentChannelName / currentChannelPoster
        Log.d(TAG, "Player detached for channel: $currentChannelName")
    }

    /**
     * Releases a previously detached player. Posts the heavy stop()/release()
     * work with a delay so it doesn't block navigation transitions.
     */
    fun releaseDetachedPlayer() {
        val p = detachedPlayer ?: return
        detachedPlayer = null
        // Immediately mute and pause to stop audio (fast operations)
        try {
            p.volume = 0f
            p.playWhenReady = false
        } catch (_: Exception) {}
        // Post the heavy stop()+release() with delay to avoid ANR during navigation
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                p.stop()
                p.release()
                Log.d(TAG, "Detached player released (deferred)")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing detached player: ${e.message}")
            }
        }, 500)
    }

    fun release() {
        loadJob?.cancel()
        player?.stop()
        player?.release()
        player = null
        detachedPlayer?.stop()
        detachedPlayer?.release()
        detachedPlayer = null
        currentChannelId = null
        currentChannelName = null
        currentChannelPoster = null
        _state.value = State.Idle
        onIptvChannelClick = null
        Log.d(TAG, "ExoPlayer released")
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun isPlaying(): Boolean {
        return player?.isPlaying == true || player?.playbackState == Player.STATE_BUFFERING
    }

    fun isPaused(): Boolean {
        val p = player ?: return false
        return !p.playWhenReady && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
    }

    fun togglePause() {
        val p = player ?: return
        if (p.playWhenReady) {
            p.pause()
        } else {
            p.play()
        }
    }
}
