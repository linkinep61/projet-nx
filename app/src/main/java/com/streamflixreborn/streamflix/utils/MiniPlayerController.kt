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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.LiveTvHubProvider
import com.streamflixreborn.streamflix.providers.OlaTvProvider
import com.streamflixreborn.streamflix.providers.VegetaTvProvider
import com.streamflixreborn.streamflix.providers.VavooProvider
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

    /** 2026-06-08 (user "pour les radios on est obligé d'ouvrir le lecteur en
     *  vrai juste pour avoir le son") : helper public — détecte les chaînes
     *  RADIO. Les radios doivent jouer en mini-bar audio uniquement, jamais
     *  en fullscreen.
     *  2026-06-08 bis : ajout des stations RadioBrowser API (préfixe radio::). */
    fun isRadioChannel(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return id.startsWith("livehub::dric4rtv::r4di0::") ||
            id.startsWith("radio::")
    }

    sealed class State {
        data object Idle : State()
        data class Loading(val channelId: String, val channelName: String) : State()
        data class Playing(val channelId: String, val channelName: String, val channelPoster: String?) : State()
        data class Error(val channelId: String, val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    // 2026-05-14 (user "tu vois pas que la vidéo mouline depuis tout à l'heure") :
    // réduit drastiquement les retry delays. Avant: 30s × 3 cycles = 90s avant
    // d'abandonner. Maintenant: 10s × 1 cycle = 10s. Si le stream ne charge
    // pas en 6s (BUFFERING_WATCHDOG), c'est probable qu'il est down →
    // pas la peine de re-tenter 3x avec 30s d'attente entre chaque.
    private const val RETRY_DELAY_MS = 10_000L  // 10s avant retry (était 30s)
    private const val MAX_RETRY_CYCLES = 1       // 1 cycle max (était 3)
    // If a stream stays BUFFERING for this long without reaching READY, force-failover
    // to the next server. ExoPlayer may otherwise stay buffering forever on a dead URL.
    // 2026-05-14 (user "tu vois pas que la vidéo mouline depuis tout à l'heure") :
    // réduit 10s → 6s pour fail-over plus rapide quand le stream est mort.
    private const val BUFFERING_WATCHDOG_MS = 6_000L

    private var player: ExoPlayer? = null
    // 2026-06-09 : appContext stocké pour pouvoir lancer/arrêter le
    //   RadioPlaybackService depuis n'importe quelle fonction.
    @Volatile private var appContext: Context? = null
    @Volatile private var radioServiceRunning: Boolean = false
    // Promoted to field so we can apply per-channel headers (Referer/Origin)
    // before each play. Necessary for sources like meritend.net that 403
    // sans Referer correct.
    private var httpDataSourceFactory: OkHttpDataSource.Factory? = null
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

    // 2026-06-09 (user "le mini player est ouvert dans les providers pour la
    //   radio") : provider d'origine quand on a démarré une radio. Permet aux
    //   fragments de masquer le mini-player VISUEL quand on est sur un autre
    //   provider (l'audio continue via le RadioPlaybackService).
    @Volatile
    var radioOriginProviderName: String? = null
        private set

    // 2026-06-09 (user "quand on va cliquer dessus il joue directement la
    //   dernière radio qui a été utilisée") : mémoire de la dernière radio
    //   jouée, persistée pour reprise rapide depuis le bouton Radio.
    data class LastRadio(
        val id: String,
        val name: String,
        val poster: String?,
        val streamUrl: String?
    )

    private const val PREF_LAST_RADIO = "mini_player_last_radio"
    private const val KEY_LAST_RADIO_ID = "last_radio_id"
    private const val KEY_LAST_RADIO_NAME = "last_radio_name"
    private const val KEY_LAST_RADIO_POSTER = "last_radio_poster"
    private const val KEY_LAST_RADIO_STREAM = "last_radio_stream"

    private fun lastRadioPrefs(ctx: android.content.Context) =
        ctx.getSharedPreferences(PREF_LAST_RADIO, android.content.Context.MODE_PRIVATE)

    fun rememberLastRadio(ctx: android.content.Context, id: String, name: String, poster: String?, streamUrl: String?) {
        try {
            lastRadioPrefs(ctx).edit()
                .putString(KEY_LAST_RADIO_ID, id)
                .putString(KEY_LAST_RADIO_NAME, name)
                .putString(KEY_LAST_RADIO_POSTER, poster)
                .putString(KEY_LAST_RADIO_STREAM, streamUrl)
                .apply()
        } catch (_: Throwable) {}
    }

    fun getLastRadio(ctx: android.content.Context): LastRadio? {
        val p = lastRadioPrefs(ctx)
        val id = p.getString(KEY_LAST_RADIO_ID, null) ?: return null
        val name = p.getString(KEY_LAST_RADIO_NAME, null) ?: return null
        return LastRadio(
            id = id,
            name = name,
            poster = p.getString(KEY_LAST_RADIO_POSTER, null),
            streamUrl = p.getString(KEY_LAST_RADIO_STREAM, null)
        )
    }

    /** True si le mini-player VISUEL doit être masqué sur l'écran courant :
     *  = la chaîne en cours est une radio démarrée sur UN AUTRE provider que
     *  le provider sélectionné actuellement. L'audio continue, seul l'overlay
     *  est masqué pour ne pas gêner la navigation. Renvoie false pour toutes
     *  les chaînes IPTV/TV normales (= comportement inchangé). */
    fun shouldHideMiniPlayerVisualOnCurrentScreen(): Boolean {
        val id = currentChannelId ?: return false
        if (!isRadioChannel(id)) return false
        // 2026-06-09 v2 : pour les radios, on ne montre JAMAIS le visuel du
        //   mini-player sauf si on est sur le provider qui a démarré la radio.
        //   (Avant : si origin OU current = null on renvoyait false → mini-player
        //   visible partout. Fix : si null on cache aussi, l'audio suffit.)
        val origin = radioOriginProviderName ?: return true
        val current = UserPreferences.currentProvider?.name ?: return true
        return origin != current
    }

    /** Helper utilisé par les fragments hosts du mini-player. Si on est dans
     *  le cas "radio jouant dans un autre provider", on force GONE. Sinon on
     *  applique la visibility demandée. Sécurise tous les sites en un point. */
    fun applyMiniPlayerVisibility(view: android.view.View, requested: Int) {
        view.visibility = if (shouldHideMiniPlayerVisualOnCurrentScreen())
            android.view.View.GONE
        else requested
    }

    // Server fallback tracking
    private val availableServers: MutableList<Video.Server> = mutableListOf()

    /** 2026-05-17 : ID du server actuellement joué par le mini. Permet à
     *  PlayerTvFragment de savoir s'il est sur le favori ou pas après transfer. */
    fun getCurrentPlayingServerId(): String? = availableServers.getOrNull(currentServerIndex)?.id
    private val triedServerUrls: MutableSet<String> = mutableSetOf()
    private var currentServerIndex: Int = 0
    private var retryCycle: Int = 0
    @Volatile private var serversExhausted: Boolean = false

    /**
     * 2026-06-03 — Le server actuellement joué est-il un fast-track pré-caché ?
     * Si oui, HTTP 403/404/410/5xx = mort confirmé → skip immédiat (pas de retry
     * exponentiel 31 sec). Pour les autres servers (Xtream natif), on garde le
     * retry exponentiel qui masque les rate-limit transitoires.
     */
    @Volatile private var currentServerIsFastTrack: Boolean = false

    // 2026-05-17 v18 (user "faire un jumelage pour éviter la coupure") :
    //   pré-buffer d'un BACKUP MediaItem dans la queue ExoPlayer. Quand
    //   le primary cut (STATE_BUFFERING extended), on bascule INSTANTANÉMENT
    //   via seekToNextMediaItem. Le backup est déjà chargé/préparé.
    private var backupJob: Job? = null
    private var bufferingStartMs: Long = 0L
    private const val BACKUP_PREFETCH_DELAY_MS = 10_000L
    private const val BUFFERING_SWAP_THRESHOLD_MS = 2_000L

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
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // 2026-06-10 (debug user "j'ai activé sous-titres j'en vois pas") :
            //   dump les text tracks dispo pour diagnostiquer la présence ou
            //   l'absence de subs dans le flux IPTV. Visible dans logcat.
            try {
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                Log.d(TAG, "Tracks changed: ${textGroups.size} text track groups for $currentChannelName")
                textGroups.forEachIndexed { gi, group ->
                    for (i in 0 until group.length) {
                        val f = group.getTrackFormat(i)
                        Log.d(TAG, "  [text $gi/$i] lang=${f.language} label=${f.label} role=${f.roleFlags} sampleMime=${f.sampleMimeType} selected=${group.isTrackSelected(i)}")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "onTracksChanged dump failed: ${e.message}")
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            val serverName = availableServers.getOrNull(currentServerIndex)?.name ?: "?"
            Log.e(TAG, "Player error on server [$currentServerIndex] $serverName: ${error.message}")
            cancelBufferingWatchdog()
            // v64 (user "Mon IPTV trop de crash retour home") : détecte 456
            //   Stalker rate-limit → STOP IMMÉDIATEMENT, pas de retry boucle.
            //   Sinon onPlayerError → tryNextServer → playChannel → re-handshake
            //   → server 456 → onPlayerError → loop CPU 250% → ANR → crash.
            val cause = error.cause
            val httpCode = if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause.responseCode else -1
            // 2026-06-03 : sur HTTP 456/401/402/451 = HARD FAIL, on saute au server
            //   SUIVANT au lieu de stop(). Le palier resilientLoadErrorPolicy a déjà
            //   empêché le retry-loop sur le MÊME server (C.TIME_UNSET).
            //   Le cap availableServers.size dans tryNextServer empêche la boucle.
            //   Sinon : 1 seul fast-track dead du seed Firebase → toute la chaîne
            //   bloque → user voit "Serveur saturé" alors qu'il y a 56 fast-tracks
            //   viables en attente derrière.
            if (httpCode == 456 || httpCode == 401 || httpCode == 402 || httpCode == 451) {
                Log.w(TAG, "HARD FAIL HTTP $httpCode on [$currentServerIndex/${availableServers.size}] — skip to next server (avoid loop via DefaultLoadErrorHandlingPolicy)")
                val playingUri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
                if (!playingUri.isNullOrBlank()) {
                    recordHostFail(playingUri)
                    try {
                        com.streamflixreborn.streamflix.providers.OlaTvProvider.reportBrokenStreamUrl(playingUri)
                    } catch (_: Throwable) {}
                    // Invalide le fast-track pré-caché : ce CID est dead côté CDN.
                    // 2026-06-03 : on N'EFFACE PAS l'URL du cache (le 456 = rate-limit
                    //   transitoire). On la marque "morte" 24h → getCachedStreamUrls()
                    //   la rétrograde en bas de la liste. Au bout des 24h, on re-tente.
                    try {
                        com.streamflixreborn.streamflix.utils.LocalIptvChannelIndex.markUrlDead(playingUri)
                    } catch (_: Throwable) {}
                }
                tryNextServer()
                return
            }
            // 2026-05-31 : OTF TV — le CDN coupe la connexion après ~60s.
            // Au lieu de passer au serveur suivant (il n'y en a qu'un),
            // on relance le même flux (reconnexion silencieuse).
            val chId = currentChannelId
            if (chId != null && chId.startsWith("livehub::otf::")) {
                Log.d(TAG, "OTF reconnect: source error on $serverName — relaunching same stream")
                scope.launch {
                    delay(1000) // petit délai avant reconnexion
                    if (currentChannelId == chId) {
                        val p = player ?: return@launch
                        try {
                            p.seekToDefaultPosition()
                            p.prepare()
                            p.play()
                        } catch (e: Exception) {
                            Log.w(TAG, "OTF reconnect failed: ${e.message}")
                            // En dernier recours, relancer via playChannel
                            playChannel(chId, currentChannelName ?: "", currentChannelPoster)
                        }
                    }
                }
                return
            }
            // Report the dead URL so OlaTvProvider blacklists it for the rest of the session.
            val playingUri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
            if (!playingUri.isNullOrBlank()) {
                recordHostFail(playingUri)
                try {
                    com.streamflixreborn.streamflix.providers.OlaTvProvider.reportBrokenStreamUrl(playingUri)
                } catch (_: Throwable) { }
                // 2026-06-03 : si c'était un fast-track + HTTP 403/404/410 = mort
                //   confirmé. markUrlDead 24h pour rétrograder à la prochaine visite.
                if (currentServerIsFastTrack &&
                    (httpCode == 403 || httpCode == 404 || httpCode == 410 ||
                     httpCode == 500 || httpCode == 502 || httpCode == 503)) {
                    try {
                        com.streamflixreborn.streamflix.utils.LocalIptvChannelIndex.markUrlDead(playingUri)
                    } catch (_: Throwable) {}
                }
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
                    // 2026-05-17 (user "il a changé que après") : si l'user a un
                    //   favori qui n'est PAS le server actuellement joué, on
                    //   GARDE l'emission progressive active pour que le favori
                    //   puisse arriver et déclencher le switch automatique.
                    val playingId = availableServers.getOrNull(currentServerIndex)?.id
                    fun canonId(rawId: String): String {
                        val parts = rawId.split("::")
                        return if (parts.size >= 4 && parts[0].endsWith("_stream")) {
                            "${parts[0]}::${parts[1]}::${parts[2]}"
                        } else rawId
                    }
                    val favs = try {
                        com.streamflixreborn.streamflix.fragments.player.settings
                            .IptvFavorites.getFavoritesForChannel(chId).map { canonId(it) }
                    } catch (_: Exception) { emptyList() }
                    val playingIsFav = playingId != null && favs.contains(canonId(playingId))
                    if (favs.isEmpty() || playingIsFav) {
                        // Stop background emission — no need to keep loading more servers
                        // when we already play the favorite (or no favorite).
                        progressiveServerJob?.cancel()
                        com.streamflixreborn.streamflix.providers.OlaTvProvider.stopEmission()
                        Log.d(TAG, "Stopped progressive server emission — playback is working (no fav to wait)")
                    } else {
                        Log.d(TAG, "Playback OK on non-fav server, CONTINUING progressive emission to find fav ${favs}")
                    }
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
                            val isMacPortal = serverId.contains("localhost") || serverId.contains("127.0.0.1")
                            val isFastTrack = serverId.startsWith("ola_fasttrack::")
                            val isDirect = !isMacPortal && !isFastTrack
                            val channelKey = chId.removePrefix("ola_ep::").removePrefix("ola::")
                            // 2026-06-03 : extraction CID + mesure latence pour alimenter
                            //   LocalIptvChannelIndex. Format des serverIds OLA :
                            //   ola_stream::<cid>::<label>::<url>  → cid = parts[1]
                            //   ola_fasttrack::<key>::<url>        → pas de CID (skip)
                            val extractedCid = if (serverId.startsWith("ola_stream::")) {
                                serverId.removePrefix("ola_stream::").substringBefore("::")
                            } else null
                            // Latence = temps entre BUFFERING et READY (warm-up du stream)
                            val latencyMs = if (bufferingStartMs > 0) {
                                System.currentTimeMillis() - bufferingStartMs
                            } else null
                            com.streamflixreborn.streamflix.providers.OlaTvProvider.reportWorkingStreamUrl(
                                playingUri,
                                if (isDirect) channelKey else null,
                                cid = if (isDirect) extractedCid else null,
                                latencyMs = if (isDirect) latencyMs else null,
                            )
                        } catch (_: Throwable) { }
                    }
                }
                Player.STATE_BUFFERING -> {
                    // Start a watchdog: if buffering doesn't reach READY within
                    // BUFFERING_WATCHDOG_MS, force-failover. ExoPlayer can otherwise hang
                    // on a stream that returns headers but no segments.
                    armBufferingWatchdog()
                    // 2026-05-17 v18 (user "jumelage") : si on a un backup dans la
                    //   queue, swap après BUFFERING_SWAP_THRESHOLD_MS de buffering.
                    //   ExoPlayer pré-buffer le backup → swap quasi-instant.
                    bufferingStartMs = System.currentTimeMillis()
                    val pAtBuffer = player ?: return
                    val hasBackup = try { pAtBuffer.mediaItemCount > 1 } catch (_: Exception) { false }
                    if (hasBackup) {
                        scope.launch {
                            delay(BUFFERING_SWAP_THRESHOLD_MS)
                            val p2 = player ?: return@launch
                            if (p2.playbackState == Player.STATE_BUFFERING &&
                                System.currentTimeMillis() - bufferingStartMs >= BUFFERING_SWAP_THRESHOLD_MS &&
                                p2.hasNextMediaItem()) {
                                Log.w(TAG, "JUMELAGE: primary stuck buffering ${BUFFERING_SWAP_THRESHOLD_MS}ms → swap vers backup")
                                try {
                                    p2.seekToNextMediaItem()
                                    // 2026-05-20 (user "retours sur écran avec son et image
                                    //   qui se répètent") : après le swap, le backup a été
                                    //   pré-bufferisé à une position PLUS ANCIENNE que le
                                    //   primary → l'user revoit du contenu déjà joué.
                                    //   seekToDefaultPosition force un saut au live edge
                                    //   (targetOffset derrière) → élimine le replay.
                                    p2.seekToDefaultPosition()
                                    Log.d(TAG, "JUMELAGE: seekToDefaultPosition après swap (anti-replay)")
                                } catch (e: Exception) {
                                    Log.w(TAG, "seekToNextMediaItem failed: ${e.message}")
                                }
                            }
                        }
                    }
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
        // 2026-06-12 (user "à Tahiti on est déjà considéré comme VPN — TF1
        //   ne marche pas") : la latence Pacifique vers les CDN FR (250-400ms
        //   par RTT vs 30ms métropole) fait que le démarrage TLS + 1ère
        //   playlist HLS peut prendre 8-12s. Le watchdog 6s tue le flux
        //   AVANT que la lecture démarre. Pour les IPTV live qui n'ont
        //   souvent qu'1 seul serveur (= aucun fallback de toute façon), on
        //   donne 15s pour laisser le temps de démarrer. Sinon le default
        //   6s reste actif (= cas VOD multi-extracteurs où on peut basculer
        //   vite sur un autre serveur).
        val effectiveWatchdogMs = if (availableServers.size <= 1) 15_000L
            else BUFFERING_WATCHDOG_MS
        bufferingWatchdogJob = scope.launch {
            delay(effectiveWatchdogMs)
            // Only fire if we're still on the same channel + same server still buffering.
            if (currentChannelId == channelAtArm && currentServerIndex == indexAtArm) {
                val serverName = availableServers.getOrNull(currentServerIndex)?.name ?: "?"
                Log.w(TAG, "Buffering watchdog: $serverName stuck >${effectiveWatchdogMs / 1000}s, switching")
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

    // 2026-05-17 : LoadErrorHandlingPolicy partagé pour le mini-player. Retry
    //   403/5xx HLS manifest 6x avec backoff exponentiel avant fire onPlayerError
    //   → masque les rate-limits transitoires Xtream sans cut visible.
    private val resilientLoadErrorPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val ex = loadErrorInfo.exception
            if (ex is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                val code = ex.responseCode
                // 2026-05-17 v64 (user "Mon IPTV trop de crash, retour home") :
                //   HTTP 456 (Stalker MAC rate-limit) → FAIL FAST, pas de retry.
                //   Sinon ExoPlayer retry 6× au défaut → CPU 250% → ANR 5s → crash.
                //   401/402/403 même chose : credentials invalides, ne sert à rien
                //   de retrier. 451 = unavailable for legal reasons.
                if (code == 456 || code == 401 || code == 402 || code == 451) {
                    Log.w(TAG, "Mini LoadError HARD FAIL HTTP $code (rate-limit/auth) — no retry")
                    return C.TIME_UNSET // signal "don't retry"
                }
                // 2026-06-03 : si fast-track pré-caché → 403/404/410/5xx = mort
                //   confirmé → skip immédiat (pas de retry 31 sec). Pour les
                //   serveurs natifs Xtream, on garde le retry exponentiel.
                if (currentServerIsFastTrack &&
                    (code == 403 || code == 404 || code == 410 ||
                     code == 500 || code == 502 || code == 503)) {
                    Log.w(TAG, "Mini LoadError FAST-TRACK HARD FAIL HTTP $code — no retry, skip fast")
                    return C.TIME_UNSET
                }
                // 2026-06-12 (user "ça charge en boucle sur TF1 World Live") :
                //   pour les IPTV live mono-serveur, le retry n a aucun sens
                //   sur 4xx (token signé, géo-block, auth) puisqu il n y a
                //   personne sur qui basculer. On fail fast.
                if (availableServers.size <= 1 &&
                    (code == 403 || code == 404 || code == 410)) {
                    Log.w(TAG, "Mini LoadError MONO-SERVER HARD FAIL HTTP $code — no retry, abandon")
                    return C.TIME_UNSET
                }
                if (code == 403 || code == 500 || code == 502 || code == 503 || code == 509 || code == 429) {
                    val attempt = loadErrorInfo.errorCount
                    if (attempt < 6) {
                        val delay = 500L * (1L shl attempt.coerceAtMost(5))
                        Log.d(TAG, "Mini LoadError retry $attempt/6 for HTTP $code, delay=${delay}ms")
                        return delay.coerceAtMost(16_000L)
                    }
                }
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 6
    }

    fun initPlayer(context: Context) {
        // 2026-06-09 : stocker l'appContext pour le foreground service.
        appContext = context.applicationContext
        if (player != null) return

        // 2026-05-31 : OkHttpDataSource au lieu de DefaultHttpDataSource.
        // Raison : sur Chromecast (Android TV), Google Play Services impose
        // son propre conscrypt qui IGNORE le IptvTlsHelper global de l'app.
        // DefaultHttpDataSource utilise HttpsURLConnection → GMS conscrypt
        // rejette le cert expiré du CDN Vavoo → écran noir.
        // OkHttpDataSource utilise OkHttp qui a son propre SSLContext
        // configuré DANS le client → immunisé contre GMS override.
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllManager), java.security.SecureRandom())
        }
        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // 2026-06-12 (user "Vavoo sert la pub VYPN même avec MediaHubMX/2") :
            //   forcer HTTP/1.1 (au lieu d'HTTP/2 par défaut). Test : curl en
            //   HTTP/2 sert le vrai TF1 → c'est probablement le fingerprint
            //   HTTP/2 settings frames d'OkHttp Android que Vavoo détecte
            //   pour servir le clip pub VYPN. HTTP/1.1 = pas de SETTINGS,
            //   fingerprint moins distinctif.
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()

        val httpDataSource = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        httpDataSourceFactory = httpDataSource

        // 2026-06-12 (user "Vavoo sert toujours la pub VYPN même avec HTTP/1.1") :
        //   Vavoo détecte le fingerprint TLS de Conscrypt (la lib SSL/TLS d'Android
        //   utilisée par OkHttp). En swap vers CronetDataSource qui utilise BoringSSL
        //   (= la lib SSL de Chrome), le JA3 fingerprint matche un browser standard
        //   → Vavoo nous voit comme un browser légitime → pas de pub.
        //   Fallback OkHttp si Cronet pas dispo (= Chromecast vieux, etc.).
        val cronetEngine = try {
            com.streamflixreborn.streamflix.StreamFlixApp.getCronetEngine(context)
        } catch (_: Throwable) { null }
        val baseHttpFactory: androidx.media3.datasource.DataSource.Factory = if (cronetEngine != null) {
            try {
                val cronetClass = Class.forName("androidx.media3.datasource.cronet.CronetDataSource\$Factory")
                val engineClass = Class.forName("org.chromium.net.CronetEngine")
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                val ctor = cronetClass.getConstructor(engineClass, java.util.concurrent.Executor::class.java)
                val factory = ctor.newInstance(cronetEngine, executor)
                cronetClass.getMethod("setUserAgent", String::class.java)
                    .invoke(factory, "MediaHubMX/2")
                Log.d(TAG, "Using CronetDataSource (BoringSSL TLS) for streams")
                factory as androidx.media3.datasource.DataSource.Factory
            } catch (e: Throwable) {
                Log.w(TAG, "CronetDataSource init failed (${e.message}), fallback to OkHttp")
                httpDataSource
            }
        } else {
            Log.d(TAG, "CronetEngine unavailable, using OkHttpDataSource")
            httpDataSource
        }

        val dataSourceFactory = DefaultDataSource.Factory(context, baseHttpFactory)

        // 2026-05-13 (user "le mini lecteur charge longtemps") : démarrage
        // ultra rapide — on commence à jouer dès qu'1s de buffer est dispo
        // (au lieu de 2.5s). Pour un flux live HLS avec segments de 6s, ça
        // évite d'attendre 1 segment complet avant le 1er frame.
        //
        // 2026-05-17 (user "avance constante / 2e barre") :
        //   - minBufferMs 5s → 60s : le player tente toujours d'avoir 60s
        //     d'avance derrière la "barre" visible. Bonne marge pour absorber
        //     les 403 rate-limit Xtream.
        //   - maxBufferMs 30s → 300s : pas de cap artificiel sur le buffer.
        //   - bufferForPlaybackMs reste à 1s pour démarrage rapide du mini.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 60_000,
                /* maxBufferMs */ 300_000,
                /* bufferForPlaybackMs */ 1_000,
                /* bufferForPlaybackAfterRebufferMs */ 2_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 2026-05-17 : MediaSourceFactory avec retry policy résilient (cf
        //   resilientLoadErrorPolicy déclaré au niveau classe).
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(resilientLoadErrorPolicy)

        // 2026-06-10 (user "le mini-player perd l'image au bout d'un moment,
        //   le son reste") : hypothèse hardware decoder qui se met en sleep
        //   en surface petite (MTK/Amlogic Chromecast). Fix : utilise
        //   NextRenderersFactory + EXTENSION_RENDERER_MODE_PREFER pour les
        //   chaînes IPTV mini-player → software FFmpeg en priorité = pas
        //   d'optimisation power-saving qui kill la surface vidéo.
        val miniRenderersFactory = try {
            io.github.anilbeesetti.nextlib.media3ext.ffdecoder
                .NextRenderersFactory(context).apply {
                    setEnableDecoderFallback(true)
                    setExtensionRendererMode(
                        androidx.media3.exoplayer.DefaultRenderersFactory
                            .EXTENSION_RENDERER_MODE_PREFER
                    )
                }
        } catch (e: Throwable) {
            Log.w(TAG, "NextRenderersFactory unavailable, fallback default: ${e.message}")
            null
        }
        val playerBuilder = if (miniRenderersFactory != null) {
            ExoPlayer.Builder(context, miniRenderersFactory)
        } else {
            ExoPlayer.Builder(context)
        }
        player = playerBuilder
            .setMediaSourceFactory(mediaSourceFactory)
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
                // 2026-05-17 v56 : PreloadConfiguration désactivé pour live IPTV.
                //   Causait déjà-vu de 30s post-swap (backup pré-bufferisé avec
                //   contenu OLDER que target live edge).
                try {
                    preloadConfiguration = androidx.media3.exoplayer.ExoPlayer.PreloadConfiguration.DEFAULT
                    Log.d(TAG, "v56: PreloadConfiguration disabled")
                } catch (e: Exception) {
                    Log.w(TAG, "PreloadConfiguration not available: ${e.message}")
                }
                // 2026-06-10 (user "chaîne de dessin animé sous-titré, les
                //   subs n'y sont pas") : toggle iptvShowSubtitlesFr :
                //   - OFF (par défaut) → désactive CEA-608/708 + autres
                //     text tracks (= ancien comportement HUGO! HUGO! fix)
                //   - ON → préfère FR auto si dispo, ne désactive rien
                try {
                    val showSubs = UserPreferences.iptvShowSubtitlesFr
                    trackSelectionParameters = if (showSubs) {
                        // 2026-06-10 v2 (logs : `selected=false` malgré
                        //   preferredTextLanguage(fr)) : faut EXPLICITEMENT
                        //   désactiver le TrackTypeDisabled + clear overrides
                        //   + autoriser undetermined + listes "fr"/"fre"/"fra".
                        trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setPreferredTextLanguages("fr", "fre", "fra")
                            .setSelectUndeterminedTextLanguage(true)
                            .build().also {
                                Log.d(TAG, "IPTV subs ENABLED (FR preferred, undetermined OK)")
                            }
                    } else {
                        trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build().also {
                                Log.d(TAG, "IPTV subs DISABLED (toggle off)")
                            }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not configure text tracks: ${e.message}")
                }
            }
        Log.d(TAG, "ExoPlayer initialized")
    }

    /**
     * 2026-06-08 (user "ajoute l'API RadioBrowser") : lance directement une URL
     * de stream audio (radio web) sans passer par un provider IPTV. Utilisé
     * pour les stations RadioBrowser (préfixe `radio::browser::`).
     */
    /** 2026-06-09 : helpers publics pour les boutons de la notif radio
     *  (Play/Pause, Suivant, Précédent). Appelés par RadioPlaybackService. */
    fun toggleRadioPlayPause() {
        try {
            player?.let { p ->
                p.playWhenReady = !p.playWhenReady
                Log.d(TAG, "toggleRadioPlayPause: playWhenReady=${p.playWhenReady}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "toggleRadioPlayPause failed: ${t.message}")
        }
    }

    fun isRadioPlaying(): Boolean = try { player?.playWhenReady == true } catch (_: Throwable) { false }

    fun nextRadio() = jumpRadio(+1)
    fun previousRadio() = jumpRadio(-1)

    private fun jumpRadio(delta: Int) {
        val currentId = currentChannelId ?: return
        if (!isRadioChannel(currentId)) return
        scope.launch {
            try {
                val list = com.streamflixreborn.streamflix.utils.RadioCatalog.list()
                if (list.isEmpty()) return@launch
                val idx = list.indexOfFirst { it.id == currentId }
                if (idx < 0) return@launch
                val target = (idx + delta + list.size) % list.size
                val next = list[target]
                val url = next.streamUrl
                if (!url.isNullOrBlank()) {
                    playRadioDirect(next.id, next.name, next.poster, url)
                } else {
                    // Pas d'URL directe (= Dric4rTV) : déléguer au flow normal.
                    playChannel(next.id, next.name, next.poster)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "jumpRadio failed: ${t.message}")
            }
        }
    }

    /** 2026-06-09 : démarre/maintient le RadioPlaybackService (foreground)
     *  pour empêcher Android de suspendre l'app écran éteint. */
    private fun startRadioForegroundService(title: String, subtitle: String?) {
        val ctx = appContext ?: return
        try {
            val intent = android.content.Intent(
                ctx,
                com.streamflixreborn.streamflix.services.RadioPlaybackService::class.java,
            ).apply {
                action = com.streamflixreborn.streamflix.services
                    .RadioPlaybackService.ACTION_START
                putExtra(
                    com.streamflixreborn.streamflix.services
                        .RadioPlaybackService.EXTRA_TITLE,
                    title,
                )
                subtitle?.let {
                    putExtra(
                        com.streamflixreborn.streamflix.services
                            .RadioPlaybackService.EXTRA_SUBTITLE,
                        it,
                    )
                }
            }
            androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
            radioServiceRunning = true
            Log.d(TAG, "RadioPlaybackService started ($title)")
        } catch (t: Throwable) {
            Log.w(TAG, "startRadioForegroundService failed: ${t.message}")
        }
    }

    /** Arrête le RadioPlaybackService si actif. No-op sinon. */
    private fun stopRadioForegroundService() {
        if (!radioServiceRunning) return
        val ctx = appContext ?: return
        try {
            ctx.stopService(
                android.content.Intent(
                    ctx,
                    com.streamflixreborn.streamflix.services.RadioPlaybackService::class.java,
                )
            )
            radioServiceRunning = false
            Log.d(TAG, "RadioPlaybackService stopped")
        } catch (t: Throwable) {
            Log.w(TAG, "stopRadioForegroundService failed: ${t.message}")
        }
    }

    fun playRadioDirect(channelId: String, channelName: String, channelPoster: String?, streamUrl: String) {
        Log.d(TAG, "playRadioDirect: $channelName ($channelId) → $streamUrl")
        // 2026-06-09 : keep-alive foreground pour audio écran éteint.
        startRadioForegroundService(channelName, "Radio")
        // 2026-06-09 : retenir le provider d'origine pour pouvoir masquer
        //   le mini-player VISUEL quand on switche de provider.
        radioOriginProviderName = UserPreferences.currentProvider?.name
        // 2026-06-09 : mémoriser la dernière radio jouée pour la reprise rapide
        //   via le bouton Radio (clic court = relance la dernière).
        try {
            appContext?.let { rememberLastRadio(it, channelId, channelName, channelPoster, streamUrl) }
        } catch (_: Throwable) {}
        // Stop l'ancien stream
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
        _state.value = State.Playing(channelId, channelName, channelPoster)

        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val p = player ?: return@launch
                val mediaItem = MediaItem.Builder()
                    .setUri(streamUrl.toUri())
                    .build()
                p.setMediaItem(mediaItem)
                p.prepare()
                p.playWhenReady = true
                Log.d(TAG, "playRadioDirect: prepared $channelName")
            } catch (t: Throwable) {
                Log.w(TAG, "playRadioDirect failed: ${t.message}")
                _state.value = State.Error(channelId, t.message ?: "Erreur lecture radio")
            }
        }
    }

    /** 2026-06-10 (user "2e clic = fullscreen depuis le folder dialog") :
     *  navigate vers le fullscreen player avec la chaîne actuellement
     *  jouée dans le mini-player. Appelable depuis n'importe quel context
     *  (= sans avoir besoin du Fragment Home). */
    fun navigateToFullscreenForCurrent() {
        val activity = com.streamflixreborn.streamflix.StreamFlixApp.currentActivity
            as? androidx.fragment.app.FragmentActivity ?: run {
            Log.w(TAG, "navigateToFullscreenForCurrent: no FragmentActivity")
            return
        }
        val channelId = currentChannelId ?: run {
            Log.w(TAG, "navigateToFullscreenForCurrent: no current channel")
            return
        }
        val channelName = currentChannelName ?: channelId
        val channelPoster = currentChannelPoster

        // Cut le mini-player (= comme HomeTvFragment.navigateToFullPlayer).
        stopAsync()

        val videoType = com.streamflixreborn.streamflix.models.Video.Type.Episode(
            id = channelId, number = 1, title = channelName, poster = channelPoster,
            overview = null,
            tvShow = com.streamflixreborn.streamflix.models.Video.Type.Episode.TvShow(
                id = channelId, title = channelName, poster = channelPoster,
                banner = null, releaseDate = null, imdbId = null,
            ),
            season = com.streamflixreborn.streamflix.models.Video.Type.Episode.Season(
                number = 1, title = "Live",
            ),
        )
        val args = android.os.Bundle().apply {
            putString("id", channelId)
            putString("title", channelName)
            putString("subtitle", channelName)
            putSerializable("videoType", videoType)
        }
        try {
            val navHost = activity.supportFragmentManager
                .findFragmentById(com.streamflixreborn.streamflix.R.id.nav_main_fragment)
                as? androidx.navigation.fragment.NavHostFragment
            navHost?.navController?.navigate(
                com.streamflixreborn.streamflix.R.id.action_global_player, args,
            ) ?: Log.w(TAG, "navigateToFullscreenForCurrent: no NavHostFragment")
        } catch (e: Exception) {
            Log.w(TAG, "navigateToFullscreenForCurrent failed: ${e.message}")
        }
    }

    /** 2026-06-10 : lookup folderPath en cherchant dans folderContents par
     *  ID. Le folder ID est de la forme `livehub::worldlivetv::wltv::<group>::folder::<sub>-<idx>`
     *  → on doit retrouver le folderPath qui a stocké ce folder. */
    private fun resolveFolderPathFromId(id: String): String? {
        // Format folderPath : `<groupSlug>/<subSlug>/<idx>` OU `<groupSlug>/<subSlug>/u<hash>`
        // Format nativeId : `wltv::<groupSlug>::folder::<subSlug>-<idx>`
        val nativeId = id.removePrefix("livehub::worldlivetv::")
        val nidParts = nativeId.removePrefix("wltv::").split("::folder::")
        if (nidParts.size != 2) return null
        val groupSlug = nidParts[0]
        val subAndIdx = nidParts[1]
        val lastDash = subAndIdx.lastIndexOf('-')
        if (lastDash <= 0) return null
        val subSlug = subAndIdx.substring(0, lastDash)
        val idx = subAndIdx.substring(lastDash + 1)
        val folderContents = com.streamflixreborn.streamflix.providers
            .WorldLiveTvProvider.folderContents
        // 1. Tente exact match (= mêmes format ID et folderPath)
        val exactPath = "$groupSlug/$subSlug/$idx"
        if (folderContents.containsKey(exactPath)) return exactPath
        // 2. 2026-06-10 (user "IPTV-Org ne ouvre plus la liste") : si le
        //    folder ID est ancien format mais folderContents a été regen
        //    avec nouveau format (u<hash>), fallback par PREFIX. Cherche le
        //    1er path qui commence par "<groupSlug>/<subSlug>/".
        val expectedPrefix = "$groupSlug/$subSlug/"
        for ((path, items) in folderContents) {
            if (path.startsWith(expectedPrefix) && items.isNotEmpty()) {
                Log.d(TAG, "resolveFolderPathFromId: fallback prefix match ($id → $path)")
                return path
            }
        }
        Log.w(TAG, "resolveFolderPathFromId: no match for $id (expected prefix=$expectedPrefix)")
        return null
    }

    fun playChannel(channelId: String, channelName: String, channelPoster: String?) {
        Log.d(TAG, "playChannel: $channelName ($channelId)")
        // 2026-06-10 (user "j'ai activé sous-titres j'en vois pas") : applique
        //   le toggle iptvShowSubtitlesFr À CHAQUE switch de chaîne. initPlayer
        //   skip si player existe déjà → les params de track selection
        //   restaient bloqués sur l'ancien state. Maintenant on les force ici
        //   sur le player vivant.
        try {
            val p = player
            if (p != null) {
                val showSubs = UserPreferences.iptvShowSubtitlesFr
                p.trackSelectionParameters = if (showSubs) {
                    p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setPreferredTextLanguages("fr", "fre", "fra")
                        .setSelectUndeterminedTextLanguage(true)
                        .build().also {
                            Log.d(TAG, "playChannel: IPTV subs ENABLED for $channelName")
                        }
                } else {
                    p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build().also {
                            Log.d(TAG, "playChannel: IPTV subs DISABLED for $channelName")
                        }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "playChannel: could not configure text tracks: ${e.message}")
        }
        // 2026-06-10 (user "comme Wiseplay : dossiers explorables") : si
        //   l'ID est un folder World Live → ouvre le dialog explorer au lieu
        //   de tenter de jouer (un folder n'a pas de stream).
        if (channelId.contains("::folder::")) {
            val folderPath = resolveFolderPathFromId(channelId)
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.currentActivity
            if (folderPath != null && ctx != null) {
                com.streamflixreborn.streamflix.providers.WorldLiveFolderDialog.showFolder(
                    ctx, folderPath, channelName,
                ) { ch ->
                    // Quand l'user clique sur une chaîne feuille du folder,
                    //   on relance playChannel avec son id préfixé.
                    val leafId = "livehub::worldlivetv::${ch.id}"
                    playChannel(leafId, ch.name, ch.logo)
                }
            } else {
                Log.w(TAG, "playChannel: folder id but no path/activity ($channelId)")
            }
            return
        }
        // 2026-06-09 (user "ça joue sur mon téléphone et j'ai pas de
        //   notification persistante pour l'audio") : les radios Dric4rTV
        //   passent par playChannel (pas par playRadioDirect). Si la
        //   nouvelle chaîne EST une radio → démarrer le keep-alive.
        //   Si c'est une chaîne TV normale → arrêter le keep-alive radio
        //   si actif (= switch radio → TV).
        if (isRadioChannel(channelId)) {
            startRadioForegroundService(channelName, "Radio")
            // 2026-06-09 : retenir le provider d'origine pour le visual hide.
            radioOriginProviderName = UserPreferences.currentProvider?.name
            // 2026-06-09 : mémoriser la dernière radio jouée. streamUrl est
            //   inconnu ici (résolu plus tard par getServers) → null, le
            //   replay passera par playChannel à nouveau (résoudra à nouveau).
            try {
                appContext?.let { rememberLastRadio(it, channelId, channelName, channelPoster, null) }
            } catch (_: Throwable) {}
        } else {
            stopRadioForegroundService()
            radioOriginProviderName = null
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
        // 2026-05-17 (user "le cache 100 Mo est vidé à chaque changement de
        //   chaîne, il y a intérêt") : clear le DVR cache pour dédier les
        //   100 Mo à la nouvelle chaîne uniquement.
        if (currentChannelId != channelId) {
            try {
                com.streamflixreborn.streamflix.StreamFlixApp.clearLiveCache()
            } catch (_: Exception) { }
        }
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
                // 2026-05-31 : résoudre le provider IPTV depuis le préfixe de l'ID
                // plutôt que UserPreferences.currentProvider. Quand le user est sur
                // un provider Films/Séries (Cloudstream, Movix) et navigue dans le
                // TV Hub, currentProvider n'est PAS un IptvProvider → playback échoue.
                val provider = resolveIptvProvider(channelId)
                    ?: run {
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
                startCollectingProgressiveServers(channelId, provider as IptvProvider)

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

                // 2026-05-17 (user "la chaîne ne reprend pas sur le favori") :
                //   le mini ne respectait pas les favoris IPTV. Fix: chercher
                //   le favori dans la liste de servers et jouer en priorité.
                //
                // 2026-05-17 v2 : les IDs Vegeta contiennent l'URL signée qui
                //   CHANGE à chaque session. Le favori stocké avec l'ancienne
                //   URL ne matchait plus le server courant. Fix: matcher sur
                //   la PARTIE STABLE de l'ID (avant la partie URL = 4ème ::).
                //   Format Vegeta : vegeta_stream::N::Quality::URL
                fun canonicalId(rawId: String): String {
                    val parts = rawId.split("::")
                    return if (parts.size >= 4 && parts[0].endsWith("_stream")) {
                        // Garde "vegeta_stream::6::Server 6", retire l'URL
                        "${parts[0]}::${parts[1]}::${parts[2]}"
                    } else rawId
                }
                val favIds = try {
                    com.streamflixreborn.streamflix.fragments.player.settings.IptvFavorites
                        .getFavoritesForChannel(channelId)
                } catch (_: Exception) { emptyList<String>() }
                val canonicalFavs = favIds.map { canonicalId(it) }

                // 2026-05-17 (user "pourquoi le favori se charge pas en premier") :
                //   si le favori est connu mais pas encore dans la liste de servers
                //   initiaux, on attend jusqu'à 3s pour qu'il arrive via
                //   additionalServersFlow avant de fallback sur index 0.
                var favIndex = if (canonicalFavs.isNotEmpty()) {
                    availableServers.indexOfFirst { srv ->
                        canonicalFavs.contains(canonicalId(srv.id))
                    }
                } else -1
                if (favIndex < 0 && canonicalFavs.isNotEmpty()) {
                    // 2026-05-17 v2 : wait 3s → 8s. Vegeta provider prend 5-10s
                    //   pour exposer ses serveurs Xtream tiers. 8s couvre la
                    //   plupart des cas tout en restant tolérable au démarrage.
                    Log.d(TAG, "Favori pas encore dans servers initiaux — wait 8s pour additionalServersFlow")
                    var waited = 0L
                    while (waited < 8_000L && currentChannelId == channelId) {
                        delay(200L)
                        waited += 200L
                        favIndex = availableServers.indexOfFirst { srv ->
                            canonicalFavs.contains(canonicalId(srv.id))
                        }
                        if (favIndex >= 0) {
                            Log.d(TAG, "Favori arrivé après ${waited}ms : ${availableServers[favIndex].name}")
                            break
                        }
                    }
                }
                val startIndex = if (favIndex >= 0) {
                    Log.d(TAG, "Favori prioritaire (canonical match): ${availableServers[favIndex].name} index=$favIndex")
                    favIndex
                } else {
                    if (canonicalFavs.isNotEmpty()) {
                        Log.d(TAG, "Favori présent ($canonicalFavs) mais pas arrivé en 3s — start index 0")
                    }
                    0
                }
                currentServerIndex = startIndex
                playServerAtIndex(channelId, provider, startIndex)

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

                // 2026-05-17 (user "TF1 a repris mais pas sur le bon serveur") :
                //   le favori arrive PROGRESSIVEMENT après le démarrage. Au start
                //   on n'avait que Vegeta[37], on a fallback dessus. Maintenant que
                //   le favori (e.g. Vegeta[6] Server 6) arrive, on doit switcher
                //   automatiquement dessus.
                fun canonicalIdProg(rawId: String): String {
                    val parts = rawId.split("::")
                    return if (parts.size >= 4 && parts[0].endsWith("_stream")) {
                        "${parts[0]}::${parts[1]}::${parts[2]}"
                    } else rawId
                }
                try {
                    val favIds = com.streamflixreborn.streamflix.fragments.player.settings
                        .IptvFavorites.getFavoritesForChannel(channelId)
                    val canonicalFavs = favIds.map { canonicalIdProg(it) }
                    if (canonicalFavs.isNotEmpty() && canonicalFavs.contains(canonicalIdProg(server.id))) {
                        val currentPlayingId = availableServers.getOrNull(currentServerIndex)?.id
                        val currentIsFav = currentPlayingId != null && canonicalFavs.contains(canonicalIdProg(currentPlayingId))
                        if (!currentIsFav) {
                            Log.w(TAG, "Favori ARRIVED progressively (${server.name}) — switching player from ${currentPlayingId ?: "none"} to favori")
                            currentServerIndex = newIndex
                            val prov = UserPreferences.currentProvider as? Provider
                            if (prov != null) {
                                loadJob?.cancel()
                                loadJob = scope.launch {
                                    playServerAtIndex(channelId, prov, newIndex)
                                }
                            }
                            return@collect
                        }
                    }
                } catch (_: Exception) { }

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

        // 2026-06-03 : marquer si c'est un fast-track pour que le LoadErrorHandling-
        //   Policy fasse skip rapide sur 403/404/410 au lieu de retry exponentiel.
        currentServerIsFastTrack = server.id.startsWith("ola_fasttrack::")

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
            // 2026-05-17 (user "2e barre de chargement") : LiveConfiguration
            //   pour qu'ExoPlayer reste 60s derrière le live edge sur les
            //   chaînes IPTV. Le player ne pourra jamais "rattraper" le flux
            //   → cushion permanent absorbe les 403 rate-limit Xtream.
            val isLiveChannel = channelId.startsWith("ch::") || channelId.startsWith("sport::") ||
                channelId.startsWith("ola::") || channelId.startsWith("ola_ep::") ||
                channelId.startsWith("vegeta::") || channelId.startsWith("vegeta_ep::") ||
                channelId.startsWith("livehub::") || channelId.startsWith("sportlive::") ||
                channelId.startsWith("match::") || channelId.startsWith("vavoo::") || channelId.startsWith("myiptv-live::")
            val mediaItem = MediaItem.Builder()
                .setUri(video.source.toUri())
                .setMimeType(video.type)
                .apply {
                    if (isLiveChannel) {
                        setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(55_000)
                                .setMaxOffsetMs(120_000)
                                .setMinOffsetMs(30_000)
                                // 2026-05-17 v46 : range 0.95-1.0 (imperceptible).
                                //   Le swap PREEMPTIVE (ahead<25s) gère la recovery, pas besoin
                                //   de slowdown agressif. ExoPlayer auto-adjust dans cette plage.
                                .setMinPlaybackSpeed(0.95f)
                                .setMaxPlaybackSpeed(1.0f)
                                .build()
                        )
                    }
                }
                .build()

            // 2026-05-13 (user "le mini lecteur charge longtemps sans rien
            // faire alors que le grand lecteur est instantané") : le grand
            // player utilise HlsMediaSource.Factory().setAllowChunkless-
            // Preparation(true) qui permet à ExoPlayer de démarrer SANS
            // télécharger toutes les sous-playlists. Le mini utilisait
            // setMediaItem qui passe par auto-detection (lente). On bascule
            // sur la même factory que le grand player pour les flux HLS.
            val urlBeforeQuery = video.source.substringBefore('?')
            val urlEndsWithTs = urlBeforeQuery.endsWith(".ts", ignoreCase = true)
            val isHls = !urlEndsWithTs && (
                urlBeforeQuery.endsWith(".m3u8", ignoreCase = true) ||
                video.type == "application/vnd.apple.mpegurl" ||
                video.type == "application/x-mpegURL"
            )
            val isDash = urlBeforeQuery.endsWith(".mpd", ignoreCase = true) ||
                video.type == "application/dash+xml"
            val dsFactory = httpDataSourceFactory
            if (isHls && dsFactory != null) {
                // 2026-05-17 : wrap avec CacheDataSource pour DVR 100 Mo persistant.
                //   Bénéfices : channel-switch rapide, manifest cache, anti-fetch-fail.
                val ctx = currentChannelId?.let { _ -> player?.let { it as Any? } as Any? }
                val cache = try {
                    com.streamflixreborn.streamflix.StreamFlixApp
                        .getLiveCache(com.streamflixreborn.streamflix.StreamFlixApp.instance)
                } catch (_: Exception) { null }
                val finalDataSource = if (cache != null && isLiveChannel) {
                    androidx.media3.datasource.cache.CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(dsFactory)
                        .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                } else dsFactory
                // 2026-05-17 (user "à chaque coupure c'est là où ça renvoie le boost
                //   et le chargement du nouveau flux") : DefaultHlsExtractorFactory
                //   avec FLAG_ALLOW_NON_IDR_KEYFRAMES + FLAG_DETECT_ACCESS_UNITS.
                //   Évite le flush décodeur à chaque jonction de chunk .ts MPEG-TS
                //   (la cause des micro coupures perçues comme "boost" et resync).
                //   Sans ces flags, ExoPlayer attend strictement un keyframe IDR
                //   au début de chaque segment et flushe le décodeur si manquant
                //   → freeze frame visible à chaque chunk boundary.
                val hlsExtractorFactory = androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
                    true // exposeCea608WhenMissingDeclarations
                )
                // 2026-05-17 v8 (logs montrent HlsPlaylistTracker$PlaylistStuckException
                //   → 3s BUFFERING = la coupure user) : custom PlaylistTracker avec
                //   tolérance 30x targetDuration au lieu de 3.5x (default). Pour un
                //   playlist target=6s, stuck threshold passe de 21s à 180s. Xtream
                //   Vegeta servers retournent souvent le même playlist 60s+ → la
                //   tolérance étendue évite l'exception et donne le temps à notre
                //   préemptive reload de fetch une URL fraîche AVANT que ExoPlayer
                //   ne throw l'erreur.
                val playlistTrackerFactory = androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker.Factory {
                    dsFactory2, loadPolicy, parserFactory, cmcdConfig ->
                    androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker(
                        dsFactory2, loadPolicy, parserFactory, cmcdConfig, 30.0
                    )
                }
                val hlsSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(finalDataSource)
                    .setAllowChunklessPreparation(true)
                    .setExtractorFactory(hlsExtractorFactory)
                    .setLoadErrorHandlingPolicy(resilientLoadErrorPolicy)
                    .setPlaylistTrackerFactory(playlistTrackerFactory)
                    .createMediaSource(mediaItem)
                p.setMediaSource(hlsSource)
                Log.d(TAG, "Using HlsMediaSource + chunklessPreparation + smoothChunkJoin + DVR cache + stuckTolerance30x (live=${isLiveChannel}, cache=${cache != null})")
            } else if (isDash && dsFactory != null) {
                val dashSource = androidx.media3.exoplayer.dash.DashMediaSource.Factory(dsFactory)
                    .createMediaSource(mediaItem)
                p.setMediaSource(dashSource)
                Log.d(TAG, "Using DashMediaSource")
            } else {
                // v65 (user "ça rame sur Mon IPTV") :
                //   Pour les streams progressifs TS (Stalker, Xtream sans HLS),
                //   utilise ProgressiveMediaSource avec un Extractor TS optimisé
                //   et un DataSource avec timeout/buffer plus large pour absorber
                //   les hiccups réseau quand le serveur delivre juste-in-time.
                if (dsFactory != null) {
                    val tsExtractorFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                        .setTsExtractorFlags(
                            androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
                        )
                        .setTsExtractorTimestampSearchBytes(3 * 188 * 1024) // 564KB recherche timestamp (vs 600KB default)
                    val progressiveSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                        dsFactory, tsExtractorFactory
                    )
                        .setLoadErrorHandlingPolicy(resilientLoadErrorPolicy)
                        .createMediaSource(mediaItem)
                    p.setMediaSource(progressiveSource)
                    Log.d(TAG, "Using ProgressiveMediaSource + tuned TS extractor (live=${isLiveChannel})")
                } else {
                    p.setMediaItem(mediaItem)
                }
            }
            // 2026-05-17 v7 : on laisse ExoPlayer gérer le speed via la
            //   LiveConfiguration range 0.95-1.0x. PAS de setPlaybackParameters
            //   manuel — ça override la LiveConfiguration et cause des sauts
            //   de phase. Le VOD reste à 1.0x naturel.
            p.prepare()
            p.play()

            Log.d(TAG, "Playing server [$index] ${server.name}: ${video.source.take(80)} (speed=${p.playbackParameters.speed}x)")

            // 2026-05-17 v20 (user "il est censé scanner 2 fois le même") :
            //   MÊME URL primary, joué 2 fois avec offsets différents. Primary
            //   à 45s derrière live, backup à 55s. Pas de 2e serveur, pas de
            //   2e handshake — juste 2 positions dans le même flux. Quand primary
            //   cut au bord, backup a 10s de marge → swap instant.
            // 2026-05-17 v63 (user "rien à l'image ni de son" sur Mon IPTV
            //   Stalker) : SKIP le backup pour les streams TS progressifs
            //   (non-HLS, non-DASH). Le serveur Stalker enforce 1 connexion
            //   par MAC → l'ajout d'un 2e MediaItem ouvre une 2e connexion
            //   qui fait rejeter les DEUX par le serveur → playback stuck
            //   → watchdog 6s → source error → écran noir. Pour les TS
            //   progressifs, on garde 1 seul MediaItem, sans JUMELAGE.
            //   HLS (Vegeta, Ola, Vavoo) reste avec JUMELAGE.
            // 2026-05-31 : SKIP le backup SAME URL pour les chaînes OTF (livehub::otf::).
            // Le CDN stm.linkip.org ne supporte pas 2 connexions simultanées avec offset
            // → le backup cause un Source error → servers exhausted → retry loop infini.
            val isOtfChannel = channelId.startsWith("livehub::otf::")
            if (isLiveChannel && (isHls || isDash) && !isOtfChannel) {
                backupJob?.cancel()
                backupJob = scope.launch {
                    try {
                        delay(5_000L)  // Délai pour laisser primary se stabiliser
                        if (currentChannelId != channelId) return@launch
                        val backupMediaItem = MediaItem.Builder()
                            .setUri(video.source.toUri())
                            .setMimeType(video.type)
                            .setLiveConfiguration(
                                MediaItem.LiveConfiguration.Builder()
                                    .setTargetOffsetMs(55_000)
                                    .setMaxOffsetMs(120_000)
                                    .setMinOffsetMs(40_000)
                                    .setMinPlaybackSpeed(0.95f)
                                    .setMaxPlaybackSpeed(1.0f)
                                    .build()
                            )
                            .build()
                        withContext(Dispatchers.Main) {
                            try {
                                if (currentChannelId == channelId && p == player && p.mediaItemCount == 1) {
                                    p.addMediaItem(backupMediaItem)
                                    Log.d(TAG, "Backup SAME URL ajouté (offset 55s vs primary 45s)")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "addMediaItem backup failed: ${e.message}")
                            }
                        }
                    } catch (_: CancellationException) {
                    } catch (e: Exception) {
                        Log.w(TAG, "Backup prefetch failed: ${e.message}")
                    }
                }
            }

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
        // 2026-05-17 (user "à partir du moment où la chaîne ne tourne plus
        //   ça doit être vidé") : clear le cache DVR quand le mini stop.
        try {
            com.streamflixreborn.streamflix.StreamFlixApp.clearLiveCache()
        } catch (_: Exception) { }
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
        // 2026-06-09 : arrêter le keep-alive radio si actif.
        stopRadioForegroundService()
        radioOriginProviderName = null
        loadJob?.cancel()
        retryJob?.cancel()
        progressiveServerJob?.cancel()
        transitioningToFullscreen = true
        val p = player
        player = null
        if (p != null) {
            try { p.removeListener(playerListener) } catch (_: Exception) {}
            // 2026-05-20 : couper IMMÉDIATEMENT la lecture et les connexions HTTP
            // pour libérer les sessions CDN (Vavoo, etc.) AVANT que le fullscreen
            // player ne tente de résoudre de nouveaux streams. Sans ça, l'ancien
            // player garde les connexions pendant 3s → conflit de session CDN.
            try {
                p.stop()
                p.clearMediaItems()
                Log.d(TAG, "stopAsync: playback stopped + media cleared immediately")
            } catch (e: Exception) {
                Log.w(TAG, "stopAsync: error stopping playback: ${e.message}")
            }
        }
        // Store for deferred release
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

    /**
     * 2026-05-31 : Résout le bon provider IPTV depuis le préfixe du channelId.
     * Permet de jouer les chaînes TV Hub, OLA, Vegeta, etc. même quand le
     * provider courant (UserPreferences.currentProvider) est un provider
     * Films/Séries (Cloudstream, Movix, etc.).
     */
    /**
     * Retourne un Provider qui implémente aussi IptvProvider.
     * Le cast IptvProvider est vérifié ; les appelants peuvent appeler getServers()
     * (méthode de Provider) ET additionalServersFlow (méthode de IptvProvider).
     */
    private fun resolveIptvProvider(channelId: String): Provider? {
        // D'abord, essayer le provider courant (cas normal : on est déjà sur un IPTV)
        val current = UserPreferences.currentProvider
        if (current is IptvProvider) return current

        // Sinon, résoudre par le préfixe de l'ID
        val resolved: Provider? = when {
            channelId.startsWith("livehub::") -> LiveTvHubProvider
            channelId.startsWith("ola::") || channelId.startsWith("ola_ep::") -> OlaTvProvider
            channelId.startsWith("vegeta::") || channelId.startsWith("vegeta_ep::") -> VegetaTvProvider
            channelId.startsWith("vavoo::") -> VavooProvider
            channelId.startsWith("myiptv-") -> {
                Provider.providers.keys.find { it is com.streamflixreborn.streamflix.providers.MyIptvProvider }
            }
            channelId.startsWith("sportlive::") -> {
                Provider.providers.keys.find { it.name == "Sport Live" }
            }
            else -> null
        }
        Log.d(TAG, "resolveIptvProvider: channelId=$channelId → current=${current?.name}, resolved=${resolved?.name}")
        // Vérifier que le provider résolu est bien IPTV
        return if (resolved is IptvProvider) resolved else null
    }
}
