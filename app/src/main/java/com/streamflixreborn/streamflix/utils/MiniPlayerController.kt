package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
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

    private const val RETRY_DELAY_MS = 30_000L  // 30s before retrying full cycle
    private const val MAX_RETRY_CYCLES = 3       // max full cycles before giving up
    // If a stream stays BUFFERING for this long without reaching READY, force-failover
    // to the next server. ExoPlayer may otherwise stay buffering forever on a dead URL.
    private const val BUFFERING_WATCHDOG_MS = 10_000L

    private var player: ExoPlayer? = null
    // Promoted to field so we can apply per-channel headers (Referer/Origin)
    // before each play. Necessary for sources like meritend.net that 403
    // sans Referer correct.
    private var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null
    private var loadJob: Job? = null
    private var retryJob: Job? = null
    private var progressiveServerJob: Job? = null
    private var bufferingWatchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Current channel info
    var currentChannelId: String? = null
        private set
    var currentChannelName: String? = null
        private set
    var currentChannelPoster: String? = null
        private set

    // Server fallback tracking
    private val availableServers: MutableList<Video.Server> = mutableListOf()
    private val triedServerUrls: MutableSet<String> = mutableSetOf()
    private var currentServerIndex: Int = 0
    private var retryCycle: Int = 0
    @Volatile private var serversExhausted: Boolean = false

    // Host-level failure tracking: if a host fails >= HOST_FAIL_THRESHOLD times,
    // skip all remaining servers on that host immediately instead of wasting 5s each.
    private val hostFailCounts: MutableMap<String, Int> = mutableMapOf()
    private const val HOST_FAIL_THRESHOLD = 2

    private fun extractHost(url: String): String {
        return try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
    }

    private fun recordHostFail(url: String) {
        val host = extractHost(url)
        if (host.isNotBlank()) {
            hostFailCounts[host] = (hostFailCounts[host] ?: 0) + 1
        }
    }

    fun getPlayer(): ExoPlayer? = player

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val serverName = availableServers.getOrNull(currentServerIndex)?.name ?: "?"
            Log.e(TAG, "Player error on server [$currentServerIndex] $serverName: ${error.message}")
            cancelBufferingWatchdog()
            // Report the dead URL so OlaTvProvider blacklists it for the rest of the session.
            val playingUri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
            if (!playingUri.isNullOrBlank()) {
                recordHostFail(playingUri)
                try {
                    com.streamflixreborn.streamflix.providers.OlaTvProvider.reportBrokenStreamUrl(playingUri)
                } catch (_: Throwable) { }
            }
            tryNextServer()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val chId = currentChannelId ?: return
                    val serverName = availableServers.getOrNull(currentServerIndex)?.name ?: ""
                    Log.d(TAG, "Playback ready on server [$currentServerIndex] $serverName")
                    cancelBufferingWatchdog()
                    retryCycle = 0 // reset cycle counter on success
                    _state.value = State.Playing(chId, currentChannelName ?: "", currentChannelPoster)
                    // Stop background server emission — no need to keep loading more
                    // servers when we already have a working stream. This avoids
                    // unnecessary network calls and potential interference with playback.
                    progressiveServerJob?.cancel()
                    com.streamflixreborn.streamflix.providers.OlaTvProvider.stopEmission()
                    Log.d(TAG, "Stopped progressive server emission — playback is working")
                    // Report success to OlaTvProvider so this host gets prioritized.
                    // Only persist the working URL to disk if the stream is DIRECT (not
                    // MAC-portal-resolved). MAC portal URLs contain ephemeral tokens that
                    // expire within minutes — caching them causes channels to play the wrong
                    // content on next launch (the expired token may redirect to a default
                    // stream, so TF1 and France 2 end up playing the same thing).
                    val playingUri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
                    if (!playingUri.isNullOrBlank()) {
                        try {
                            val serverId = availableServers.getOrNull(currentServerIndex)?.id ?: ""
                            // A MAC portal server's cmd contains "localhost" or "127.0.0.1".
                            // Fast-track servers (ola_fasttrack::) played a previously-cached
                            // URL — don't re-cache to avoid perpetuating stale entries.
                            val isMacPortal = serverId.contains("localhost") || serverId.contains("127.0.0.1")
                            val isFastTrack = serverId.startsWith("ola_fasttrack::")
                            val isDirect = !isMacPortal && !isFastTrack
                            val channelKey = chId.removePrefix("ola_ep::").removePrefix("ola::")
                            // Always report for host scoring (probeOkCache), but only
                            // persist to disk (channelKey) for direct streams.
                            com.streamflixreborn.streamflix.providers.OlaTvProvider.reportWorkingStreamUrl(
                                playingUri,
                                if (isDirect) channelKey else null
                            )
                        } catch (_: Throwable) { }
                    }
                }
                Player.STATE_BUFFERING -> {
                    // Start a watchdog: if buffering doesn't reach READY within
                    // BUFFERING_WATCHDOG_MS, force-failover. ExoPlayer can otherwise hang
                    // on a stream that returns headers but no segments.
                    armBufferingWatchdog()
                }
                Player.STATE_ENDED, Player.STATE_IDLE -> {
                    cancelBufferingWatchdog()
                }
            }
        }
    }

    private fun armBufferingWatchdog() {
        cancelBufferingWatchdog()
        val channelAtArm = currentChannelId
        val indexAtArm = currentServerIndex
        bufferingWatchdogJob = scope.launch {
            delay(BUFFERING_WATCHDOG_MS)
            // Only fire if we're still on the same channel + same server still buffering.
            if (currentChannelId == channelAtArm && currentServerIndex == indexAtArm) {
                val serverName = availableServers.getOrNull(currentServerIndex)?.name ?: "?"
                Log.w(TAG, "Buffering watchdog: $serverName stuck >${BUFFERING_WATCHDOG_MS / 1000}s, switching")
                val playingUri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
                if (!playingUri.isNullOrBlank()) {
                    recordHostFail(playingUri)
                    try {
                        com.streamflixreborn.streamflix.providers.OlaTvProvider.reportBrokenStreamUrl(playingUri)
                    } catch (_: Throwable) { }
                }
                tryNextServer()
            }
        }
    }

    private fun cancelBufferingWatchdog() {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = null
    }

    fun initPlayer(context: Context) {
        if (player != null) return

        val httpDataSource = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        httpDataSourceFactory = httpDataSource

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
        // 2026-05-12 : disable mini-player pour les chaînes 3BoxTV (bxt::).
        // L'extraction WebView (userinfo bypass anti-bot) prend 20-40s pour
        // les chaînes TF1/M6/etc. → le mini paraît cassé. User demande à
        // laisser uniquement le grand player gérer ces chaînes.
        if (channelId.startsWith("bxt::")) {
            Log.d(TAG, "playChannel: skip mini-player for bxt:: channel $channelId")
            return
        }
        // 2026-05-09 : STOP le player avant de switcher de canal, sinon si le
        // fetch du nouveau canal foire, la chaîne précédente continue de jouer
        // (bug : "je clique BFMTV et le player joue Canal+ que j'avais cliqué avant").
        try {
            player?.let { p ->
                p.stop()
                p.clearMediaItems()
            }
        } catch (_: Throwable) {}
        currentChannelId = channelId
        currentChannelName = channelName
        currentChannelPoster = channelPoster
        availableServers.clear()
        triedServerUrls.clear()
        hostFailCounts.clear()
        currentServerIndex = 0
        retryCycle = 0
        serversExhausted = false
        retryJob?.cancel()
        progressiveServerJob?.cancel()
        cancelBufferingWatchdog()
        _state.value = State.Loading(channelId, channelName)

        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val provider = UserPreferences.currentProvider
                if (provider !is IptvProvider) {
                    _state.value = State.Error(channelId, "Not an IPTV provider")
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

                // Get servers (initial batch: OTF, WiTV, Vavoo)
                val rawServers = withContext(Dispatchers.IO) {
                    provider.getServers(channelId, videoType)
                }
                // 2026-05-09 : tri par fiabilité observée — les variantes
                // qui ont récemment foiré (Vegeta server X down, OTF cert
                // expiré, etc.) tombent au fond. La 1ère tentative tape
                // donc le server le plus probable de marcher.
                val servers = ExtractorRanker.rankServers(rawServers)

                // Start collecting progressive OLA TV servers (variants) — works for any
                // IPTV provider since IptvProvider declares additionalServersFlow.
                startCollectingProgressiveServers(channelId, provider)

                if (servers.isEmpty()) {
                    Log.w(TAG, "No initial servers for $channelName, waiting for progressive servers...")
                    // Don't fail immediately — OLA TV servers may arrive shortly
                    serversExhausted = true
                    // Give progressive servers 5s to arrive before scheduling retry
                    delay(5000)
                    if (currentChannelId == channelId && serversExhausted && availableServers.isEmpty()) {
                        scheduleRetryOrFail(channelId, "No servers found")
                    }
                    return@launch
                }

                Log.d(TAG, "Got ${servers.size} initial servers for $channelName: ${servers.map { it.name }}")
                availableServers.addAll(servers)
                currentServerIndex = 0

                // Try first server
                playServerAtIndex(channelId, provider, 0)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading channel: ${e.message}", e)
                if (currentChannelId == channelId) {
                    scheduleRetryOrFail(channelId, e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Collects progressive OLA TV servers (channel variants like TF1 HD, TF1 FHD)
     * from any IptvProvider's additionalServersFlow.
     * When a new server arrives and we've exhausted current servers, try it immediately.
     */
    private fun startCollectingProgressiveServers(channelId: String, provider: IptvProvider) {
        progressiveServerJob?.cancel()
        progressiveServerJob = scope.launch {
            provider.additionalServersFlow.collect { server ->
                if (currentChannelId != channelId) {
                    progressiveServerJob?.cancel()
                    return@collect
                }

                // Deduplicate: skip if already in list or already tried
                val serverUrl = server.id
                if (serverUrl in triedServerUrls || availableServers.any { it.id == serverUrl }) {
                    Log.d(TAG, "Progressive server duplicate, skipping: ${server.name}")
                    return@collect
                }

                val newIndex = availableServers.size
                availableServers.add(server)
                Log.d(TAG, "Progressive server added [$newIndex]: ${server.name}")

                // If we're in "exhausted" state, try this new server immediately
                if (serversExhausted) {
                    serversExhausted = false
                    retryJob?.cancel() // cancel pending retry delay
                    Log.d(TAG, "Servers were exhausted — trying new progressive server: ${server.name}")
                    _state.value = State.Loading(channelId, currentChannelName ?: "")
                    val prov = UserPreferences.currentProvider as? Provider ?: return@collect
                    loadJob?.cancel()
                    loadJob = scope.launch {
                        playServerAtIndex(channelId, prov, newIndex)
                    }
                }
            }
        }
    }

    /**
     * Try to play the server at [index]. If extraction fails or source is empty,
     * automatically moves to the next server.
     */
    private suspend fun playServerAtIndex(channelId: String, provider: Provider, index: Int) {
        if (currentChannelId != channelId) return // channel changed
        if (index >= availableServers.size) {
            Log.w(TAG, "All ${availableServers.size} servers exhausted for $channelId — requesting renewal batch")
            serversExhausted = true

            // Server renewal: ask OlaTvProvider for the next batch from the remaining pool.
            // This is seamless — the user doesn't see any interruption.
            val provider2 = UserPreferences.currentProvider
            if (provider2 is com.streamflixreborn.streamflix.providers.OlaTvProvider) {
                val channelKey = channelId.removePrefix("ola_ep::").removePrefix("ola::")
                val newCount = provider2.requestNextBatch(channelKey, triedServerUrls)
                if (newCount > 0) {
                    Log.d(TAG, "Renewal: $newCount new servers incoming — waiting for progressive delivery")
                    // Servers are emitted via additionalServersFlow and will be picked up
                    // by the progressive server collector, which auto-tries when exhausted.
                    // Give it a moment to arrive.
                    delay(500)
                    if (currentChannelId == channelId && serversExhausted) {
                        // If still exhausted after 500ms, wait a bit more for progressive servers
                        delay(2500)
                    }
                    if (currentChannelId == channelId && serversExhausted) {
                        scheduleRetryOrFail(channelId, "All servers failed after renewal")
                    }
                    return
                }
                Log.d(TAG, "Renewal: pool fully exhausted — no more servers available")
            }

            // No renewal available — wait a bit for progressive OLA TV servers
            delay(3000)
            if (currentChannelId == channelId && serversExhausted) {
                scheduleRetryOrFail(channelId, "All servers failed")
            }
            return
        }

        serversExhausted = false
        currentServerIndex = index
        val server = availableServers[index]

        triedServerUrls.add(server.id)
        Log.d(TAG, "Trying server [$index/${availableServers.size}] ${server.name}")

        // Pre-flight host check: extract the host from the server ID (which embeds the
        // stream cmd URL) BEFORE calling getVideo. This avoids wasting 250ms+ on a
        // create_link call to a host we already know is dead.
        if (server.id.startsWith("ola_stream::")) {
            val cmdPart = server.id.substringAfterLast("::", "")
            val rawCmd = cmdPart.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
            val cmdHost = extractHost(rawCmd)
            if (cmdHost.isNotBlank() && (hostFailCounts[cmdHost] ?: 0) >= HOST_FAIL_THRESHOLD) {
                Log.d(TAG, "Pre-skip server [$index] ${server.name} — cmd host $cmdHost already failed ${hostFailCounts[cmdHost]} times")
                playServerAtIndex(channelId, provider, index + 1)
                return
            }
        }

        _state.value = State.Loading(channelId, currentChannelName ?: "")

        try {
            val video = withContext(Dispatchers.IO) {
                provider.getVideo(server)
            }

            if (currentChannelId != channelId) return

            if (video.source.isEmpty()) {
                Log.w(TAG, "Server [$index] ${server.name} returned empty source, trying next")
                playServerAtIndex(channelId, provider, index + 1)
                return
            }

            // Host-level skip: if this resolved URL's host has already failed >= threshold,
            // skip it immediately instead of wasting 5s waiting for ExoPlayer to time out.
            val resolvedHost = extractHost(video.source)
            if (resolvedHost.isNotBlank() && (hostFailCounts[resolvedHost] ?: 0) >= HOST_FAIL_THRESHOLD) {
                Log.d(TAG, "Skipping server [$index] ${server.name} — host $resolvedHost already failed ${hostFailCounts[resolvedHost]} times")
                playServerAtIndex(channelId, provider, index + 1)
                return
            }

            // Play
            val p = player ?: return
            // Apply per-video request headers (Referer/Origin/UA) BEFORE prepare
            // so sources like meritend.net (403 sans bon Referer + UA Android)
            // reçoivent les bonnes credentials. Sans ça, ExoPlayer envoie juste
            // son UA par défaut (Windows) qui ne matche pas le UA WebView Android
            // utilisé pour générer le token → fingerprint mismatch → 403.
            val perVideoHeaders = video.headers ?: emptyMap()
            // CRITIQUE : User-Agent doit être appliqué via setUserAgent (setDefault-
            // RequestProperties l'ignore pour ce header spécifique dans Media3).
            val perVideoUA = perVideoHeaders["User-Agent"]
            if (perVideoUA != null) {
                httpDataSourceFactory?.setUserAgent(perVideoUA)
            } else {
                httpDataSourceFactory?.setUserAgent(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            }
            // Headers SANS User-Agent (déjà géré ci-dessus)
            httpDataSourceFactory?.setDefaultRequestProperties(
                perVideoHeaders.filterKeys { it != "User-Agent" }
            )
            val mediaItem = MediaItem.Builder()
                .setUri(video.source.toUri())
                .setMimeType(video.type)
                .build()

            p.setMediaItem(mediaItem)
            p.prepare()
            p.play()

            Log.d(TAG, "Playing server [$index] ${server.name}: ${video.source.take(80)}")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Server [$index] ${server.name} failed: ${e.message}")
            recordHostFail(server.id)
            if (currentChannelId == channelId) {
                playServerAtIndex(channelId, provider, index + 1)
            }
        }
    }

    /**
     * Called when the player reports an error during playback.
     * Tries the next server in the list.
     */
    private fun tryNextServer() {
        val channelId = currentChannelId ?: return
        val nextIndex = currentServerIndex + 1
        Log.d(TAG, "tryNextServer: moving to index $nextIndex/${availableServers.size}")

        loadJob?.cancel()
        loadJob = scope.launch {
            val provider = UserPreferences.currentProvider
            if (provider !is IptvProvider) {
                _state.value = State.Error(channelId, "Not an IPTV provider")
                return@launch
            }
            playServerAtIndex(channelId, provider, nextIndex)
        }
    }

    /**
     * Schedule a full retry cycle after [RETRY_DELAY_MS], or emit final error
     * if max cycles reached.
     */
    private fun scheduleRetryOrFail(channelId: String, message: String) {
        retryCycle++
        if (retryCycle > MAX_RETRY_CYCLES) {
            Log.e(TAG, "Max retry cycles ($MAX_RETRY_CYCLES) reached for $channelId")
            _state.value = State.Error(channelId, message)
            return
        }

        Log.d(TAG, "Scheduling retry cycle $retryCycle/$MAX_RETRY_CYCLES in ${RETRY_DELAY_MS / 1000}s")
        _state.value = State.Loading(channelId, currentChannelName ?: "")

        retryJob?.cancel()
        retryJob = scope.launch {
            delay(RETRY_DELAY_MS)
            if (currentChannelId == channelId) {
                Log.d(TAG, "Retry cycle $retryCycle: re-fetching servers for $channelId")
                val savedCycle = retryCycle
                playChannel(channelId, currentChannelName ?: "", currentChannelPoster)
                retryCycle = savedCycle // preserve cycle count across playChannel reset
            }
        }
    }

    fun stop() {
        loadJob?.cancel()
        retryJob?.cancel()
        progressiveServerJob?.cancel()
        cancelBufferingWatchdog()
        player?.stop()
        player?.clearMediaItems()
        currentChannelId = null
        currentChannelName = null
        currentChannelPoster = null
        availableServers.clear()
        triedServerUrls.clear()
        hostFailCounts.clear()
        currentServerIndex = 0
        retryCycle = 0
        serversExhausted = false
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
        progressiveServerJob?.cancel()
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

    /** 2026-05-08 : exposer le server actuellement joué par le mini-player.
     *  Utilisé par PlayerMobileFragment quand on passe mini → fullscreen pour
     *  REPRENDRE EXACTEMENT le même server (sinon le fullscreen reprend le 1er
     *  de la liste, qui peut être un mauvais variant — ex: Arte allemand sur
     *  Vegeta[39] alors que le mini jouait Vegeta[43] HD français).
     *  Returns null si rien ne joue. */
    fun currentMiniServer(): Video.Server? {
        return availableServers.getOrNull(currentServerIndex)
    }

    fun stopAsync() {
        loadJob?.cancel()
        retryJob?.cancel()
        progressiveServerJob?.cancel()
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
        retryJob?.cancel()
        progressiveServerJob?.cancel()
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
        retryJob?.cancel()
        progressiveServerJob?.cancel()
        player?.stop()
        player?.release()
        player = null
        detachedPlayer?.stop()
        detachedPlayer?.release()
        detachedPlayer = null
        currentChannelId = null
        currentChannelName = null
        currentChannelPoster = null
        availableServers.clear()
        triedServerUrls.clear()
        hostFailCounts.clear()
        currentServerIndex = 0
        retryCycle = 0
        serversExhausted = false
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
