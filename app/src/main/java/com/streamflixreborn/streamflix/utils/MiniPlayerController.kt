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
    // 2026-06-15 REVERT (user "faut pas toucher à tout c'est générique") :
    //   les retry cycles touchent TOUS les providers IPTV. Les bump à 3 cycles
    //   sur 9s ne fixe pas prime-tv (= 403 reste côté serveur) ET ralentit
    //   la récupération sur tous les autres providers. Retour au comportement
    //   original 1 cycle × 10s = comportement éprouvé.
    private const val RETRY_DELAY_MS = 10_000L  // 10s avant retry (était 30s)
    private const val MAX_RETRY_CYCLES = 1       // 1 cycle max (était 3)
    // If a stream stays BUFFERING for this long without reaching READY, force-failover
    // to the next server. ExoPlayer may otherwise stay buffering forever on a dead URL.
    // 2026-05-14 (user "tu vois pas que la vidéo mouline depuis tout à l'heure") :
    // réduit 10s → 6s pour fail-over plus rapide quand le stream est mort.
    private const val BUFFERING_WATCHDOG_MS = 6_000L

    private var player: ExoPlayer? = null
    // 2026-06-16 (user "le grand player dans le mini a l exactitude") : mirror du
    //   grand swap_freeze_overlay (PlayerTvFragment 478-527). Refs vers la PlayerView
    //   active + ImageView overlay, setees par HomeTvFragment/HomeMobileFragment
    //   au boot via attachPlayerView(). Permet captureLastFrameToOverlay() avant
    //   le JUMELAGE swap = masque la coupure codec flush ~500ms.
    @Volatile private var miniPlayerView: androidx.media3.ui.PlayerView? = null
    @Volatile private var freezeOverlayView: android.widget.ImageView? = null
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

    /** 2026-06-20 (user "valable pour tous les providers qui ont un mini lecteur") :
     *  callback global appelé chaque fois que le mini démarre/arrête une chaîne.
     *  MainTvActivity s'y abonne pour montrer/cacher l'item sidebar "🎦 Plein écran". */
    @JvmField var onChannelStateChanged: (() -> Unit)? = null
    private fun fireChannelStateChanged() {
        try { onChannelStateChanged?.invoke() } catch (_: Throwable) {}
    }

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
    // 2026-06-15 (user "le mini lecteur a pas le même pattern que le grand
    //   lecteur") : aligné sur le grand (PlayerTvFragment line 3436 "stuck
    //   buffering 500ms → swapToNextWithAudioFade"). Avant : 2000ms = user
    //   voit 2s de coupure avant swap. Après : 500ms = swap imperceptible.
    // 2026-06-16 FINAL (apres audit 5 fixes) : 500L = MIRROR EXACT du grand.
    //   Plus de boucle car PROACTIVE prepare et speed=1.1 et backup SAME-URL
    //   etaient les vraies causes du drain. Maintenant le buffer reste stable
    //   donc swap rare comme le grand.
    private const val BUFFERING_SWAP_THRESHOLD_MS = 500L

    // 2026-06-15 (user "le mini lecteur doit être autonome") : forced periodic
    //   swap toutes les 45s pour rafraîchir le primary anti-dégradation CDN.
    //   Aligné sur PlayerTvFragment.startPeriodicForcedSwap() (ligne 5216).
    //   Sans ce mécanisme, un primary qui se dégrade lentement (rate-limit,
    //   token vieillissant) finit par couper sans que le mini ne réagisse.
    //   Avec : on swap vers le backup AVANT que le primary ne meure.
    private var periodicSwapJob: Job? = null
    @Volatile private var lastSwapTimestampMs: Long = 0L

    /**
     * 2026-06-17 (user "quand je swap de chaîne il a tendance à garder la même
     *  chaîne en mémoire") : compteur de génération atomique incrémenté à CHAQUE
     *  playChannel. Toutes les coroutines (loadJob, progressiveServerJob,
     *  playServerAtIndex) capturent leur génération locale au lancement et
     *  l'inspectent avant chaque écriture sur le player (setMediaSource, prepare,
     *  play). Si la génération a changé entre temps → abort immédiat.
     *
     *  Pourquoi le check `currentChannelId != channelId` seul est insuffisant :
     *   - User clique TF1 → playChannel("tf1") → loadJob1 lance getServers + setMediaSource("tf1")
     *   - User clique TMC AVANT que loadJob1 termine → playChannel("tmc")
     *      → cancel loadJob1 (async coopératif) + reset state + lance loadJob2("tmc")
     *   - loadJob1 termine son getVideo() AVANT de checker `currentChannelId`,
     *      puis setMediaSource("tf1_url") sur le player → joue TF1 au lieu de TMC
     *   - Pire : user revient sur TF1 → playChannel("tf1") → currentChannelId=="tf1"
     *      → l'ancien loadJob1 ne se sait pas annulé via le check de channelId
     *      → race entre loadJob1 et loadJob3 → comportement non-déterministe
     *      qui matche la description user ("parfois oui parfois non, tout dépend").
     */
    @Volatile private var playChannelGeneration: Int = 0
    // 2026-06-22 (user "changement rapide de chaîne → erreur bloquante") :
    //   génération du MEDIA actuellement chargé dans le player. Posé juste
    //   avant setMediaSource/setMediaItem. onPlayerError vérifie que sa
    //   génération matche playChannelGeneration — sinon l'erreur est stale
    //   (vient de l'ancienne chaîne interrompue) et on l'ignore.
    @Volatile private var activeMediaGeneration: Int = 0
    private const val PERIODIC_SWAP_INTERVAL_MS = 45_000L
    // 2026-06-15 v6 : aligné PlayerTvFragment.kt SWAP_COOLDOWN_MS=5_000L (ligne 5193).
    //   Avant : 20_000L (4× plus permissif que le grand → swaps trop rares → cuts).
    private const val SWAP_RECENT_COOLDOWN_MS = 5_000L

    // 2026-06-15 (user "regarde les logs il y a des défauts dans le mini") :
    //   Porté de PlayerMobileFragment ligne 3640-3669. Quand le stream live
    //   IPTV STALL en BUFFERING long (= prime-tv, Vavoo edge lent), seek live
    //   edge + prepare() le fait repartir. Limite anti-flap : 3 reloads max
    //   sur fenêtre 15s sinon STOP (= évite boucle infinie sur stream cassé).
    private var liveBufferingRecoveryJob: Job? = null
    // 2026-06-16 : watchdog stuck-BUFFERING continu (porté du grand)
    private var stuckBufferingWatchdogJob: Job? = null
    @Volatile private var liveRecoveryActive: Boolean = false
    // 2026-06-16 (Transfer-recovery porté du grand TV ligne 6113) : marqueur
    //   set à true quand le stream IPTV a atteint STATE_READY au moins une fois.
    //   Sert à la branche ENDED/IDLE pour ne reload QUE si on a déjà joué.
    @Volatile private var iptvCurrentStreamHasWorked: Boolean = false
    private val recentReloadTimestamps = mutableListOf<Long>()
    private const val LIVE_BUFFERING_RECOVERY_MS = 12_000L
    private const val RELOAD_FLAP_THRESHOLD = 3
    private const val RELOAD_FLAP_WINDOW_MS = 15_000L

    // 2026-06-15 (user "il faut tout porter") : Server Scout HEAD 10s +
    //   addBackupPreventively. Porté de PlayerTvFragment ligne 5252-5326.
    //   Sonde le manifest URL toutes les 10s en HEAD. Si 509/429/5xx détecté,
    //   ajoute un backup MediaItem en queue AVANT que ExoPlayer ne crashe.
    //   Anticipe les coupures côté serveur.
    private var scoutJob: Job? = null
    @Volatile private var lastScoutBadResponseMs: Long = 0L

    // 2026-06-15 (user "le mini coupe sur ParaTV TNT FR alors que le grand
    //   tient, applique tous les empoints") : LAZY BACKUP just-in-time porté
    //   de PlayerTvFragment ligne 4952-4984. Surveille buffer ahead toutes
    //   les 5s. Quand ahead >= 15s ET pas de backup → ajoute backup MediaItem
    //   pour garantir que JUMELAGE swap est toujours possible.
    private var lazyBackupJob: Job? = null
    private const val LAZY_BACKUP_AHEAD_THRESHOLD_S = 15
    // 2026-06-15 (user "applique tous les empoints du grand au mini") :
    //   ÉTAT supplémentaire pour le reload identique dans onPlayerError
    //   et le watchdog flux corrompu (PORTÉ PlayerTvFragment ligne 4780-4802).
    //   recentReloadTimestamps / RELOAD_FLAP_* déjà déclarés plus haut.
    @Volatile private var preemptiveReloadInFlight: Boolean = false
    @Volatile private var lastStuckPosKey: Int = -1
    @Volatile private var stuckCounter: Int = 0
    // 2026-06-16 (user "au 404 d'une chaine IPTV, l'app re-fetche la playlist
    //   source mais faut pas que ca fasse trop de retrail") : auto-refresh
    //   de la playlist source IPTV (livehub::worldlivetv) au 404, avec throttle
    //   5 min pour ne pas spammer le CDN/GitHub raw. ParaTV rote ses paths
    //   obfusques plusieurs fois par heure → notre data.m3u cache local
    //   contient parfois des URLs deja mortes. Au lieu de "reload identique"
    //   sur URL morte, on invalide le cache + relance la chaine (= re-fetch
    //   la playlist + re-build channels + re-prepare avec la nouvelle URL).
    @Volatile private var lastPlaylistRefreshMs: Long = 0L
    private const val PLAYLIST_REFRESH_THROTTLE_MS = 5 * 60_000L  // 5 min
    // 2026-06-16 (user "porte exactement ce que fait le grand") : watchdog
    //   stuck-BUFFERING porté de PlayerTvFragment ligne 5165-5173. Quand
    //   ExoPlayer reste en BUFFERING avec position figée >8s, les retries
    //   internes ont probablement échoué silencieusement (ExoPlayer ne fire
    //   pas toujours onPlayerError). Le watchdog force alors un re-fetch via
    //   invalidateCache + replay channel (= équivalent viewModel.getVideo
    //   pour le grand). Combiné avec auto-refresh 404, garantit que les URLs
    //   roteees ParaTV sont detectees immediatement.
    @Volatile private var lastObservedPositionMs: Long = -1L
    @Volatile private var stuckBufferingTicks: Int = 0
    private const val STUCK_BUFFER_THRESHOLD_TICKS = 8
    // 2026-06-16 : flux corrompu (position bloquée avec buffer plein >8s) →
    //   seekToDefault + prepare. Porté PlayerTvFragment ligne 4780-4802.
    @Volatile private var stuckPosCounter: Int = 0
    @Volatile private var lastStuckPosKey2s: Int = -1
    // 2026-06-15 v11 (CAUSE TROUVÉE des coupures mini via logcat) :
    //   les logs montrent que LAZY BACKUP swap fail avec
    //   `UnrecognizedInputFormatException` car addMediaItem() utilise
    //   l'auto-detect et tente du Progressive/MP4 au lieu de HLS.
    //   Fix : stocker la DataSource.Factory IPTV courante et construire
    //   tous les backups via IptvPlayerSetup.createIptvHlsMediaSource
    //   (= même pipeline que le primary) puis addMediaSource (PAS
    //   addMediaItem).
    @Volatile private var currentIptvDataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null
    @Volatile private var currentIptvIsHls: Boolean = false
    private const val LAZY_BACKUP_CHECK_INTERVAL_MS = 5_000L

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

    /**
     * 2026-06-15 (user "M6 bug sur le mini lecteur") : BUG CRITIQUE diagnostiqué
     * via logcat. Avant ce helper, MiniPlayerController.onPlayerError appelait
     * OlaTvProvider.reportBrokenStreamUrl() inconditionnellement, ce qui
     * blacklistait le HOST (ex 185.160.192.14) pour TOUTE la session. Quand
     * un segment TS prime-tv (World Live TV) retournait 403 (= JWT expiré,
     * segment éphémère), OlaTvProvider blacklistait 185.160.192.14 → TOUTES
     * les chaînes prime-tv du host (M6, W9, ...) devenaient inaccessibles.
     *
     * Fix : ne déléguer au tracker OlaTvProvider que SI la chaîne est Ola.
     * Pour les autres providers (World Live TV, Vavoo, Movix LiveTV, etc.),
     * on garde uniquement le recordHostFail LOCAL (cooldown mini-player only).
     */
    private fun isCurrentChannelOla(): Boolean {
        val id = currentChannelId ?: return false
        return id.startsWith("ola::") || id.startsWith("ola_ep::") ||
            id.startsWith("ola_stream::") || id.startsWith("ola_fasttrack::")
    }


    fun getPlayer(): ExoPlayer? = player

    /**
     * 2026-06-16 : appele par HomeTvFragment / HomeMobileFragment au boot pour
     *   donner au MiniPlayerController une reference vers la PlayerView active
     *   + ImageView overlay. Permet le freeze frame avant JUMELAGE swap.
     */
    fun attachPlayerView(
        playerView: androidx.media3.ui.PlayerView?,
        freezeOverlay: android.widget.ImageView?,
    ) {
        // 2026-06-23 (user "le mini lecteur doit comprendre quel onglet on
        //   est actif — sinon image noire dans l'autre onglet") : quand on
        //   switche d'un fragment à un autre (= 2 PlayerView au home et tv_shows
        //   par exemple), seule la NOUVELLE PlayerView doit être attachée au
        //   Player. On détache d'abord l'ancienne pour libérer son Surface,
        //   puis on attache la nouvelle. Comme ça l'image va toujours sur le
        //   PlayerView du fragment courant.
        val oldView = miniPlayerView
        if (oldView != null && oldView !== playerView) {
            try {
                if (oldView.player != null) {
                    oldView.player = null
                    Log.d(TAG, "attachPlayerView: detached player from previous PlayerView")
                }
            } catch (_: Throwable) {}
        }
        miniPlayerView = playerView
        freezeOverlayView = freezeOverlay
        // Attache le player à la nouvelle PlayerView (si player vivant)
        val p = player
        if (playerView != null && p != null) {
            try {
                playerView.player = p
                Log.d(TAG, "attachPlayerView: attached player to NEW PlayerView")
            } catch (t: Throwable) {
                Log.w(TAG, "attachPlayerView: failed to attach: ${t.message}")
            }
        }
        Log.d(TAG, "attachPlayerView: pv=${playerView != null} freeze=${freezeOverlay != null}")
    }

    /**
     * 2026-06-22 : ré-attache la PlayerView du home (= celle enregistrée via
     *   attachPlayerView) après la fermeture d'un dialog qui avait temporairement
     *   attaché sa propre PlayerView au player. Appelé dans le onDismiss des
     *   LiveHubFolderDialog / WorldLiveFolderDialog.
     */
    fun reattachHomePlayerView() {
        val p = player ?: return
        miniPlayerView?.let { v ->
            if (v.player != p) {
                v.player = p
                Log.d(TAG, "reattachHomePlayerView: re-bound player to home PlayerView")
            }
        }
    }

    /**
     * 2026-06-16 : mirror grand PlayerTvFragment 478-506. PixelCopy la derniere
     *   frame du SurfaceView vers un Bitmap et affiche dans freezeOverlayView.
     *   Pendant le seek+codec flush (~500-1000ms ecran noir naturel), le user
     *   voit la frame figee au lieu du noir. Hide au prochain STATE_READY.
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.N)
    private suspend fun captureLastFrameToOverlay() {
        try {
            val pv = miniPlayerView
            val overlay = freezeOverlayView
            if (pv == null || overlay == null) {
                Log.w(TAG, "captureLastFrameToOverlay: pv=${pv != null} overlay=${overlay != null} — SKIP")
                return
            }
            val w = pv.width
            val h = pv.height
            if (w <= 0 || h <= 0) {
                Log.w(TAG, "captureLastFrameToOverlay: dimensions invalides ${w}x${h}")
                return
            }
            // 2026-06-16 : layout = TextureView (= sync rendering, plus reactif que
            //   SurfaceView). textureView.getBitmap() retourne directement le bitmap
            //   actuel (= la derniere frame decoder). Plus rapide que PixelCopy + plus
            //   fiable (pas de race condition pre-flush).
            val tv = findTextureView(pv)
            val bmp: android.graphics.Bitmap? = if (tv != null) {
                try { tv.getBitmap(w, h) } catch (e: Exception) {
                    Log.w(TAG, "textureView.getBitmap failed: ${e.message}")
                    null
                }
            } else {
                val sv = findSurfaceView(pv) ?: run {
                    Log.w(TAG, "captureLastFrameToOverlay: ni TextureView ni SurfaceView trouve")
                    return
                }
                val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    try {
                        android.view.PixelCopy.request(sv, b, { result ->
                            if (cont.isActive) cont.resume(result == android.view.PixelCopy.SUCCESS) {}
                        }, android.os.Handler(android.os.Looper.getMainLooper()))
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(false) {}
                    }
                }
                if (ok) b else null
            }
            if (bmp != null) {
                withContext(Dispatchers.Main) {
                    overlay.setImageBitmap(bmp)
                    overlay.visibility = android.view.View.VISIBLE
                    overlay.alpha = 1f
                    overlay.bringToFront()
                }
                Log.d(TAG, "captureLastFrameToOverlay: SUCCESS ${w}x${h} (mode=${if (tv != null) "TextureView" else "PixelCopy"})")
            } else {
                Log.w(TAG, "captureLastFrameToOverlay: bitmap null")
            }
        } catch (e: Exception) {
            Log.w(TAG, "captureLastFrameToOverlay: ${e.message}")
        }
    }

    private fun findTextureView(root: android.view.View): android.view.TextureView? {
        if (root is android.view.TextureView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                val found = findTextureView(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findSurfaceView(root: android.view.View): android.view.SurfaceView? {
        if (root is android.view.SurfaceView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                val found = findSurfaceView(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun hideFreezeOverlay() {
        try {
            val overlay = freezeOverlayView ?: return
            overlay.visibility = android.view.View.GONE
            overlay.setImageBitmap(null)
        } catch (_: Exception) {}
    }

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
            // 2026-06-22 (user "changement rapide → erreur bloquante") :
            //   si le media qui a causé l'erreur appartient à une ancienne
            //   génération (= chaîne déjà abandonnée par un playChannel plus
            //   récent), on IGNORE l'erreur complètement. Sans ça, l'erreur
            //   stale de la chaîne A corrompait l'itération serveurs de la
            //   chaîne B → scheduleRetryOrFail → State.Error terminal.
            if (activeMediaGeneration != playChannelGeneration) {
                Log.d(TAG, "onPlayerError: STALE error ignored (media gen=$activeMediaGeneration, current gen=$playChannelGeneration) — ${error.message}")
                return
            }
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
                    // 2026-06-15 : ne déléguer à OlaTvProvider QUE si chaîne Ola.
                    if (isCurrentChannelOla()) {
                        try {
                            com.streamflixreborn.streamflix.providers.OlaTvProvider.reportBrokenStreamUrl(playingUri)
                        } catch (_: Throwable) {}
                    }
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
                // 2026-06-15 : ne déléguer à OlaTvProvider QUE si chaîne Ola.
                if (isCurrentChannelOla()) {
                    try {
                        com.streamflixreborn.streamflix.providers.OlaTvProvider.reportBrokenStreamUrl(playingUri)
                    } catch (_: Throwable) { }
                }
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
            // 2026-06-15 (user "applique tous les empoints du grand au mini") :
            //   PORTÉ PlayerTvFragment ligne 3742-3817. AVANT de sauter au
            //   server suivant, on tente un reload identique du MediaItem
            //   (clearMediaItems + setMediaItem + prepare = fresh connexion
            //   HTTP). Beaucoup d'erreurs IPTV viennent d'une connexion HTTP
            //   morte et NON d'une URL morte → reload résout en 1 RTT au lieu
            //   de tryNextServer qui re-extract une URL fresh (5-10s). Anti-flap:
            //   3 reloads max dans 15s → bascule serveur si échec persistant.
            val curChIdForReload = currentChannelId
            val isLiveIptvForReload = curChIdForReload != null && (
                curChIdForReload.startsWith("ch::") || curChIdForReload.startsWith("sport::") ||
                curChIdForReload.startsWith("ola::") || curChIdForReload.startsWith("ola_ep::") ||
                curChIdForReload.startsWith("vegeta::") || curChIdForReload.startsWith("vegeta_ep::") ||
                curChIdForReload.startsWith("livehub::") || curChIdForReload.startsWith("sportlive::") ||
                curChIdForReload.startsWith("match::") || curChIdForReload.startsWith("vavoo::") ||
                curChIdForReload.startsWith("myiptv-live::")
            )
            val uriObj = player?.currentMediaItem?.localConfiguration?.uri
            // 2026-06-16 (user "au 404 d'une chaine IPTV l'app re-fetche la
            //   playlist source mais faut pas que ca fasse trop de refresh") :
            //   Specifique au HTTP 404 sur chaine World Live TV (mix m3u, paradis,
            //   etc.) → la playlist M3U a probablement une URL roteee. Au lieu
            //   du reload identique, on invalide le cache + replay la chaine
            //   (= getChannel re-fetchera la playlist M3U et trouvera la nouvelle
            //   URL). Throttle 5 min global pour ne pas spammer.
            val isWorldLiveIptv = curChIdForReload?.startsWith("livehub::worldlivetv::") == true
            if (httpCode == 404 && isWorldLiveIptv) {
                val now404 = System.currentTimeMillis()
                val sinceLast = now404 - lastPlaylistRefreshMs
                if (sinceLast > PLAYLIST_REFRESH_THROTTLE_MS) {
                    lastPlaylistRefreshMs = now404
                    Log.w(TAG, "Live IPTV 404 (worldlivetv) — invalidate cache + replay channel ${curChIdForReload?.take(60)}")
                    try {
                        com.streamflixreborn.streamflix.providers.WorldLiveTvProvider.invalidateCache()
                    } catch (t: Throwable) {
                        Log.w(TAG, "WorldLiveTvProvider.invalidateCache failed: ${t.message}")
                    }
                    // Relance la chaine via playChannel — re-fetchera la playlist
                    // avec URL fraiche (= re-resolution depuis le M3U).
                    val freshChId = curChIdForReload!!
                    val freshName = currentChannelName ?: ""
                    val freshPoster = currentChannelPoster
                    scope.launch {
                        delay(800) // delai pour invalidation
                        playChannel(freshChId, freshName, freshPoster)
                    }
                    return
                } else {
                    Log.d(TAG, "Live IPTV 404 throttled (last refresh ${sinceLast/1000}s ago, need ${PLAYLIST_REFRESH_THROTTLE_MS/1000}s) — fallback to reload identique")
                }
            }
            // 2026-06-16 (user "mini coupures vs grand stable") : PORTÉ du grand
            //   PlayerTvFragment ligne 3748-3770. AVANT de faire le reload
            //   destructif (clearMediaItems + setMediaItem + prepare = ~2-5s de
            //   coupure), si on a un backup en queue (LazyBackup ou JUMELAGE),
            //   on swap dessus = swap instantané sans coupure visible. C'est
            //   ce que faisait le grand et que le mini ne faisait PAS.
            // 2026-06-18 (user "Végéta TV se coupe et plus rien, change de
            //   serveur auto au lieu de retry sur le même lien, ça **** tout
            //   le Game") : VegetaTV n'a souvent qu'1-2 serveurs valides parmi
            //   les 71. Le JUMELAGE swap envoie vers un backup pré-chargé
            //   souvent mort → encore plus d'erreurs. Pour VegetaTV (et OTF/
            //   OLA qui ont la même topologie), on SKIP le JUMELAGE swap et
            //   on tente d'abord un sticky retry prepare() sur le même flux.
            val isVegetaCh = curChIdForReload?.startsWith("vegeta::") == true ||
                             curChIdForReload?.startsWith("vegeta_ep::") == true ||
                             curChIdForReload?.startsWith("livehub::vegeta::") == true
            val pForSwap = player
            if (isLiveIptvForReload && !isVegetaCh && pForSwap != null && !preemptiveReloadInFlight) {
                val hasBackupQueued = try {
                    pForSwap.mediaItemCount > 1 && pForSwap.hasNextMediaItem()
                } catch (_: Exception) { false }
                val sinceLastSwap = System.currentTimeMillis() - lastSwapTimestampMs
                if (hasBackupQueued && sinceLastSwap >= SWAP_RECENT_COOLDOWN_MS) {
                    Log.w(TAG, "JUMELAGE: error avec backup → swapToNextWithAudioFade (no reset)")
                    preemptiveReloadInFlight = true
                    scope.launch {
                        try {
                            swapToNextWithAudioFade(pForSwap)
                            lastSwapTimestampMs = System.currentTimeMillis()
                        } catch (e: Exception) {
                            Log.w(TAG, "swap on error failed: ${e.message}")
                        }
                        delay(8_000L)
                        preemptiveReloadInFlight = false
                    }
                    return
                }
            }
            if (isLiveIptvForReload && uriObj != null && !preemptiveReloadInFlight) {
                val nowFlap = System.currentTimeMillis()
                recentReloadTimestamps.removeAll { (nowFlap - it) > RELOAD_FLAP_WINDOW_MS }
                // 2026-06-18 (user "Végéta TV se coupe, switch auto **** tout
                //   le game") : VegetaTV nécessite jusqu'à 10 retries sur le
                //   même flux avant abandon. Le switch serveur sur VegetaTV
                //   tombe presque toujours sur un serveur dead car la
                //   topologie a beaucoup de serveurs morts.
                val effectiveThreshold = if (isVegetaCh) 10 else RELOAD_FLAP_THRESHOLD
                if (recentReloadTimestamps.size < effectiveThreshold) {
                    recentReloadTimestamps.add(nowFlap)
                    Log.w(TAG, "Live IPTV error — sticky retry (prepare seul, mirror grand) ${recentReloadTimestamps.size}/$effectiveThreshold")
                    preemptiveReloadInFlight = true
                    scope.launch {
                        try {
                            val pReload = player ?: return@launch
                            pReload.prepare()
                            pReload.playWhenReady = true
                        } catch (e: Exception) {
                            Log.w(TAG, "Sticky retry failed: ${e.message}")
                        }
                        delay(5_000L)
                        preemptiveReloadInFlight = false
                    }
                    return
                } else {
                    Log.w(TAG, "Reload flap detected (3 reloads/15s) → fallback tryNextServer")
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
                    bufferingStartMs = 0L
                    // 2026-06-16 (user "copie identique du grand") : hideFreezeOverlay
                    //   RETIRE du STATE_READY. Le grand n'utilise PAS le freeze overlay
                    //   dans son swap (swapToNextWithAudioFade TEST9 minimal ligne 555-563
                    //   = juste seekToNextMediaItem + seekToDefaultPosition). On reste
                    //   identique au grand.
                    // 2026-06-16 (Transfer-recovery du grand ligne 6113-6115) :
                    //   marqueur set à true au 1er READY pour autoriser le reload
                    //   sur ENDED/IDLE plus tard. Sans ça, le flag reste false et
                    //   la branche ENDED/IDLE ne se déclenche jamais.
                    iptvCurrentStreamHasWorked = true
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
                            // 2026-06-15 : ne pousser vers OlaTvProvider QUE si chaîne Ola.
                            if (isCurrentChannelOla()) {
                                com.streamflixreborn.streamflix.providers.OlaTvProvider.reportWorkingStreamUrl(
                                    playingUri,
                                    if (isDirect) channelKey else null,
                                    cid = if (isDirect) extractedCid else null,
                                    latencyMs = if (isDirect) latencyMs else null,
                                )
                            }
                        } catch (_: Throwable) { }
                    }
                    // 2026-06-15 (audit grand-vs-mini) : porté de
                    //   PlayerTvFragment.kt ligne 3607 — démarre le server
                    //   scout HEAD 10s qui détecte 509/429/5xx au manifest et
                    //   ajoute un backup préventif. Sans ça, addBackupPreventively
                    //   n'était JAMAIS appelé côté mini.
                    startServerScout()
                    // 2026-06-16 (user "analyse le grand et reproduit le pattern") :
                    //   demarre un Handler tick 1s qui LOG le buffer ahead toutes les
                    //   5s comme le grand (PlayerTvFragment.startProgressHandler). Permet
                    //   de comparer le drain du buffer mini vs grand.
                    startMiniBufferLogger()
                    // 2026-06-16 (user "tu enleves tout ce qui est mini, je veux du grand
                    //   a l exactitude") : startStuckBufferingWatchdog DESACTIVE.
                    //   Le grand TV PlayerTvFragment ligne 4720 met le watchdog dans
                    //   un Runnable derriere if (player.isPlaying), avec un check
                    //   !player.isPlaying interieur qui est de fait IMPOSSIBLE = le
                    //   stuck du grand ne se declenche JAMAIS pour live IPTV. Le mini
                    //   l appelait, ca declenchait STUCK BUFFERING 8s + replay channel
                    //   en boucle. armLiveBufferingRecovery (seek+prepare 12s en
                    //   STATE_BUFFERING) + Transfer-recovery (ENDED/IDLE) suffisent.
                    // startStuckBufferingWatchdog()  // DISABLED — mismatch avec grand
                }
                Player.STATE_BUFFERING -> {
                    // 2026-06-16 (user "je ne veux pas qu'il y ait de difference, je veux
                    //   exactement la meme chose que le grand") : porte CONDITION EXACTE
                    //   du grand PlayerTvFragment ligne 3480 : armBufferingWatchdog
                    //   UNIQUEMENT pour NON-live-IPTV. Le grand ne fait JAMAIS le
                    //   "stuck >15s switching" pour live IPTV (= il s'appuie sur JUMELAGE
                    //   swap + stuckBufferingTicks). Sans cette condition, le mini bouclait
                    //   sur "tryNextServer + retry cycle" toutes les 15s.
                    val curChIdForBuf = currentChannelId
                    val isLiveIptvForBuf = curChIdForBuf != null && (
                        curChIdForBuf.startsWith("ch::") || curChIdForBuf.startsWith("sport::") ||
                        curChIdForBuf.startsWith("ola::") || curChIdForBuf.startsWith("ola_ep::") ||
                        curChIdForBuf.startsWith("vegeta::") || curChIdForBuf.startsWith("vegeta_ep::") ||
                        curChIdForBuf.startsWith("livehub::") || curChIdForBuf.startsWith("sportlive::") ||
                        curChIdForBuf.startsWith("match::") || curChIdForBuf.startsWith("vavoo::") ||
                        curChIdForBuf.startsWith("myiptv-live::")
                    )
                    if (!isLiveIptvForBuf) {
                        // Start a watchdog: if buffering doesn't reach READY within
                        // BUFFERING_WATCHDOG_MS, force-failover. ExoPlayer can otherwise hang
                        // on a stream that returns headers but no segments.
                        armBufferingWatchdog()
                    }
                    // 2026-06-16 (audit 3 agents) : armLiveBufferingRecovery DESACTIVE.
                    //   Le grand TV n'execute le seek+prepare 12s EN BUFFERING
                    //   QUE dans attachTransferRecoveryListener (= attache
                    //   uniquement au handoff mini->grand, pas en lecture normale).
                    //   Le mini, lui, l'activait systematiquement = BOUCLE TF1
                    //   (BUFFERING -> seek+prepare 12s -> repart BUFFERING -> ...).
                    //   Le JUMELAGE swap (avec backup) + handleEndedOrIdle (ENDED/IDLE)
                    //   suffisent pour recovery, comme sur le grand.
                    // if (isLiveIptvForBuf) {
                    //     armLiveBufferingRecovery()
                    // }
                    // 2026-06-16 (audit #43) : MIRROR EXACT grand PlayerTvFragment 3386 —
                    //   bufferingStartTimestampMs set UNIQUEMENT si == 0L (= au 1er
                    //   tick BUFFERING). Avant le mini reset systematiquement chaque
                    //   tick = timer constamment a 0 = le double-check 500ms passait
                    //   immediatement = JUMELAGE swap declenchait trop vite.
                    if (bufferingStartMs == 0L) {
                        bufferingStartMs = System.currentTimeMillis()
                    }
                    val pAtBuffer = player ?: return
                    val hasBackup = try { pAtBuffer.mediaItemCount > 1 } catch (_: Exception) { false }
                    // 2026-06-22 : replays = VOD seekable → le rebuffering
                    //   après un seek est normal, pas un stream mort.
                    val isReplayMini = currentChannelId?.contains("replay") == true
                    val sinceLastSwapAtBuf = System.currentTimeMillis() - lastSwapTimestampMs
                    if (!isReplayMini && hasBackup && sinceLastSwapAtBuf >= SWAP_RECENT_COOLDOWN_MS) {
                        scope.launch {
                            delay(BUFFERING_SWAP_THRESHOLD_MS)
                            val p2 = player ?: return@launch
                            if (p2.playbackState == Player.STATE_BUFFERING &&
                                System.currentTimeMillis() - bufferingStartMs >= BUFFERING_SWAP_THRESHOLD_MS &&
                                p2.hasNextMediaItem() &&
                                System.currentTimeMillis() - lastSwapTimestampMs >= SWAP_RECENT_COOLDOWN_MS) {
                                Log.w(TAG, "JUMELAGE: primary stuck buffering ${BUFFERING_SWAP_THRESHOLD_MS}ms swap to backup")
                                try {
                                    // 2026-06-16 (user "copie identique du grand") :
                                    //   captureLastFrameToOverlay RETIRE. Le grand fait
                                    //   swap minimal TEST9 (PlayerTvFragment 555-563) :
                                    //   juste seekToNextMediaItem + seekToDefaultPosition.
                                    //   Pas de capture frame, pas de freeze overlay.
                                    p2.seekToNextMediaItem()
                                    p2.seekToDefaultPosition()
                                    lastSwapTimestampMs = System.currentTimeMillis()
                                    Log.d(TAG, "JUMELAGE: seekToDefaultPosition after swap (anti-replay)")
                                } catch (e: Exception) {
                                    Log.w(TAG, "seekToNextMediaItem failed: ${e.message}")
                                }
                            }
                        }
                    }
                }
                Player.STATE_ENDED, Player.STATE_IDLE -> {
                    cancelBufferingWatchdog()
                    handleEndedOrIdle(playbackState)
                }
            }
        }
    }

    // 2026-06-16 (Transfer-recovery du grand TV ligne 6152-6208) :
    //   sur live IPTV qui a déjà joué (iptvCurrentStreamHasWorked=true),
    //   STATE_ENDED/IDLE = stream coupé → full MediaItem reload pour
    //   forcer ExoPlayer à re-ouvrir HTTP au CDN. Anti-flap 3/15s.
    private fun handleEndedOrIdle(playbackState: Int) {
        val curChIdForEnd = currentChannelId ?: return
        val isLiveIptvForEnd = curChIdForEnd.startsWith("ch::") || curChIdForEnd.startsWith("sport::") ||
            curChIdForEnd.startsWith("ola::") || curChIdForEnd.startsWith("ola_ep::") ||
            curChIdForEnd.startsWith("vegeta::") || curChIdForEnd.startsWith("vegeta_ep::") ||
            curChIdForEnd.startsWith("livehub::") || curChIdForEnd.startsWith("sportlive::") ||
            curChIdForEnd.startsWith("match::") || curChIdForEnd.startsWith("vavoo::") ||
            curChIdForEnd.startsWith("myiptv-live::")
        if (!isLiveIptvForEnd || !iptvCurrentStreamHasWorked || preemptiveReloadInFlight) return
        val pForReload = player ?: return
        val uri = pForReload.currentMediaItem?.localConfiguration?.uri ?: return
        // 2026-06-16 (audit agent #3) : MIRROR EXACT grand PlayerTvFragment 3752-3770 —
        //   AVANT le reload destructif, tenter le JUMELAGE swap si backup disponible.
        //   Le grand fait toujours le swap d'abord (~500ms) avant le reload (2-5s).
        val sinceLastSwapEnd = System.currentTimeMillis() - lastSwapTimestampMs
        val hasBackupForEnd = try { pForReload.mediaItemCount > 1 && pForReload.hasNextMediaItem() } catch (_: Exception) { false }
        if (hasBackupForEnd && sinceLastSwapEnd >= SWAP_RECENT_COOLDOWN_MS) {
            Log.w(TAG, "Transfer-recovery: Live IPTV $playbackState avec backup → JUMELAGE swap (mirror grand)")
            try {
                // Mirror grand : capture la frame avant le swap
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    scope.launch { captureLastFrameToOverlay() }
                }
                pForReload.seekToNextMediaItem()
                pForReload.seekToDefaultPosition()
                lastSwapTimestampMs = System.currentTimeMillis()
                return
            } catch (e: Exception) {
                Log.w(TAG, "JUMELAGE swap on ENDED/IDLE failed: ${e.message}")
            }
        }
        val nowFlap2 = System.currentTimeMillis()
        recentReloadTimestamps.removeAll { (nowFlap2 - it) > RELOAD_FLAP_WINDOW_MS }
        if (recentReloadTimestamps.size >= RELOAD_FLAP_THRESHOLD) {
            Log.e(TAG, "Transfer-recovery: reload flap (${recentReloadTimestamps.size} en 15s) — STOP")
            return
        }
        recentReloadTimestamps.add(nowFlap2)
        Log.w(TAG, "Transfer-recovery: Live IPTV $playbackState — full MediaItem reload")
        preemptiveReloadInFlight = true
        scope.launch {
            try {
                val freshItem = MediaItem.Builder()
                    .setUri(uri)
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(45_000)
                            .setMinOffsetMs(20_000)
                            .setMaxOffsetMs(120_000)
                            .setMinPlaybackSpeed(0.95f)
                            .setMaxPlaybackSpeed(1.0f)
                            .build()
                    )
                    .build()
                pForReload.clearMediaItems()
                pForReload.setMediaItem(freshItem)
                pForReload.prepare()
                pForReload.playWhenReady = true
            } catch (e: Exception) {
                Log.w(TAG, "Transfer-recovery MediaItem reload failed: ${e.message}")
            }
            delay(5_000L)
            preemptiveReloadInFlight = false
        }
    }

    // 2026-06-16 (user "mini lecteur subit toujours des coupures par rapport au
    //   grand qui est stable et fluide — pas le meme Pattern") : porte de
    //   PlayerTvFragment.kt ligne 555. Au lieu du reload identique destructif
    //   (clearMediaItems + setMediaItem + prepare = ~2-5s de coupure), si on a
    //   un MediaItem backup en queue (= LazyBackup ou JUMELAGE), on swap dessus
    //   instantanement. seekToNextMediaItem + seekToDefaultPosition force le
    //   live edge sur le backup pour ne pas rejouer du contenu deja vu.
    private suspend fun swapToNextWithAudioFade(p: ExoPlayer) {
        try {
            p.seekToNextMediaItem()
            p.seekToDefaultPosition()
            Log.d(TAG, "swapToNextWithAudioFade: swap minimal (no fade)")
        } catch (e: Exception) {
            Log.w(TAG, "swapToNextWithAudioFade failed: ${e.message}")
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
                    // 2026-06-15 : ne déléguer à OlaTvProvider QUE si chaîne Ola.
                    if (isCurrentChannelOla()) {
                        try {
                            com.streamflixreborn.streamflix.providers.OlaTvProvider.reportBrokenStreamUrl(playingUri)
                        } catch (_: Throwable) { }
                    }
                }
                tryNextServer()
            }
        }
    }

    private fun cancelBufferingWatchdog() {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = null
    }

    /**
     * 2026-06-15 (user "regarde les logs, détecte les défauts si on peut les
     * corriger") : porté de PlayerMobileFragment ligne 3640-3669.
     *
     * Quand le stream live STALL en BUFFERING (= no data flowing, manifest
     * coupé, segments lents, etc.), faire un seek live edge + prepare() le
     * force à refetch un manifest frais et reprendre. Borné par anti-flap
     * (3 reloads max sur 15s) pour éviter boucle infinie.
     */
    private fun armLiveBufferingRecovery() {
        if (liveRecoveryActive) return
        liveRecoveryActive = true
        liveBufferingRecoveryJob = scope.launch {
            try {
                while (kotlinx.coroutines.currentCoroutineContext().isActive &&
                    (try { player?.playbackState == Player.STATE_BUFFERING } catch (_: Exception) { false })) {
                    delay(LIVE_BUFFERING_RECOVERY_MS)
                    val stillBuffering = try {
                        player?.playbackState == Player.STATE_BUFFERING
                    } catch (_: Exception) { false }
                    if (!stillBuffering) break
                    val now = System.currentTimeMillis()
                    recentReloadTimestamps.removeAll { (now - it) > RELOAD_FLAP_WINDOW_MS }
                    if (recentReloadTimestamps.size >= RELOAD_FLAP_THRESHOLD) {
                        // 2026-06-16 (user "je suis parti 5 min je suis revenu
                        //   la video etait a l'arret en train de boulinais") :
                        //   AVANT : on STOP juste → mini reste fige indefiniment.
                        //   APRES : cooldown 60s puis reset compteur + un dernier
                        //   essai full pipeline (= refresh playlist au 404, ou
                        //   tryNextServer si on a d'autres serveurs). Ca permet
                        //   au mini de se reveiller tout seul si l'user revient
                        //   plus tard sans toucher a l'app.
                        Log.e(TAG, "Live BUFFERING: reload flap — pause 60s puis retry full pipeline")
                        delay(60_000L)
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
                        val finalCheck = try { player?.playbackState == Player.STATE_BUFFERING } catch (_: Exception) { false }
                        if (!finalCheck) break
                        // Reset le compteur anti-flap
                        recentReloadTimestamps.clear()
                        // Trigger un retry pipeline complet (re-fetch servers + playlist)
                        val chId = currentChannelId
                        if (chId != null) {
                            Log.w(TAG, "Live BUFFERING flap recovery: scheduleRetryOrFail apres 60s pause")
                            scheduleRetryOrFail(chId, "Buffering 60s+")
                        }
                        break
                    }
                    recentReloadTimestamps.add(now)
                    Log.w(TAG, "Live BUFFERING >${LIVE_BUFFERING_RECOVERY_MS / 1000}s → seek live edge + prepare() (aligned with PlayerMobileFragment)")
                    try {
                        val p = player ?: break
                        p.seekToDefaultPosition()
                        p.prepare()
                        p.playWhenReady = true
                    } catch (_: Exception) {}
                }
            } finally {
                liveRecoveryActive = false
            }
        }
    }

    private fun cancelLiveBufferingRecovery() {
        liveBufferingRecoveryJob?.cancel()
        liveBufferingRecoveryJob = null
        liveRecoveryActive = false
    }

    // 2026-06-16 (user "mini lecteur c'est n'importe quoi il respecte pas le
    //   grand") : porté PlayerTvFragment ligne 4743-4802. 2 watchdogs en 1 :
    //   (1) stuck-BUFFERING : position figée + BUFFERING >8s → force reload
    //       via invalidateCache + replay channel (= équivalent viewModel.getVideo
    //       du grand qui re-extrait l'URL fraîche).
    //   (2) flux corrompu : position bloquée avec buffer plein (>10s ahead)
    //       8 ticks consécutifs → seekToDefault + prepare (= decoder coincé
    //       sur frames corrompues).
    //   Le watchdog tourne TOUTES les 1s tant que la chaîne est active.
    private fun startStuckBufferingWatchdog() {
        stuckBufferingWatchdogJob?.cancel()
        // Reset state pour nouvelle chaîne
        stuckBufferingTicks = 0
        stuckPosCounter = 0
        lastObservedPositionMs = -1L
        lastStuckPosKey2s = -1
        val channelAtStart = currentChannelId
        stuckBufferingWatchdogJob = scope.launch {
            try {
                while (kotlinx.coroutines.currentCoroutineContext().isActive &&
                       currentChannelId == channelAtStart) {
                    delay(1000L)
                    val p = player ?: continue
                    val state = try { p.playbackState } catch (_: Exception) { -1 }
                    val curPos = try { p.currentPosition } catch (_: Exception) { -1L }
                    val isPlaying = try { p.isPlaying } catch (_: Exception) { false }
                    val buf = try { p.bufferedPosition } catch (_: Exception) { -1L }

                    // 2026-06-16 (user "copie exacte du grand lecteur") : MIRROR
                    //   EXACT PlayerTvFragment ligne 4748-4767. Conditions :
                    //   - !isPlaying (= si player joue, position figee normale pas stuck)
                    //   - curPos == lastObserved
                    //   - curPos >= 0
                    //   Action = seekToDefault + prepare local (= equivalent au getVideo
                    //   du grand pour un mini sans currentServer). PAS de playChannel
                    //   complet, PAS d invalidate cache. Le replay channel boucle infini
                    //   parce que ParaTV manifest HLS statique est mis a jour toutes les
                    //   ~5 min cote GitHub raw cache, on attend pas le re-fetch dans 5 min.
                    val isStuck = !isPlaying && curPos == lastObservedPositionMs && curPos >= 0
                    if (isStuck) {
                        stuckBufferingTicks++
                        if (stuckBufferingTicks >= STUCK_BUFFER_THRESHOLD_TICKS && !preemptiveReloadInFlight) {
                            stuckBufferingTicks = 0
                            preemptiveReloadInFlight = true
                            Log.w(TAG, "STUCK BUFFERING ${STUCK_BUFFER_THRESHOLD_TICKS}s (!isPlaying) seekToDefault + prepare")
                            try {
                                p.seekToDefaultPosition()
                                p.prepare()
                                p.playWhenReady = true
                            } catch (_: Exception) {}
                            launch {
                                delay(8_000L)
                                preemptiveReloadInFlight = false
                            }
                        }
                    } else {
                        stuckBufferingTicks = 0
                    }
                    lastObservedPositionMs = curPos

                    // (2) flux corrompu : position avance pas mais buffer plein
                    val ahead = (buf - curPos).coerceAtLeast(0)
                    val aheadSec = (ahead / 1000).toInt()
                    if (aheadSec > 10 && isPlaying) {
                        val posKey = (curPos / 2000).toInt()
                        if (posKey == lastStuckPosKey2s) {
                            stuckPosCounter++
                            if (stuckPosCounter >= 8) {
                                Log.w(TAG, "Flux corrompu (pos bloquée ${curPos/1000}s avec ${aheadSec}s buffer) → auto-refresh")
                                stuckPosCounter = 0
                                try {
                                    p.seekToDefaultPosition()
                                    p.prepare()
                                    p.playWhenReady = true
                                } catch (_: Exception) {}
                            }
                        } else {
                            stuckPosCounter = 0
                            lastStuckPosKey2s = posKey
                        }
                    } else {
                        stuckPosCounter = 0
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // normal
            } catch (e: Exception) {
                Log.w(TAG, "Stuck buffering watchdog crashed: ${e.message}")
            }
        }
    }

    private fun cancelStuckBufferingWatchdog() {
        stuckBufferingWatchdogJob?.cancel()
        stuckBufferingWatchdogJob = null
        stuckBufferingTicks = 0
        stuckPosCounter = 0
    }

    /**
     * 2026-06-15 (user "il faut tout porter") : porté de PlayerTvFragment
     * ligne 5252-5299. Sonde le manifest URL toutes les 10s en HEAD pour
     * détecter 509/429/5xx AVANT que ExoPlayer y arrive. Si dégradation
     * détectée, ajoute préventivement un backup MediaItem en queue pour
     * absorber le cut imminent.
     */
    /**
     * 2026-06-16 : mirror du grand PlayerTvFragment.startProgressHandler ligne 4699-4719.
     * Log "Live buffer: pos=Xs buf=Ys ahead=Zs" toutes les 5s pour comparer avec le grand.
     */
    private var bufferLoggerJob: Job? = null
    private fun startMiniBufferLogger() {
        bufferLoggerJob?.cancel()
        bufferLoggerJob = scope.launch {
            var counter = 0
            var lastReseekMs = 0L
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(1000L)
                counter++
                val p = player ?: continue
                try {
                    if (!p.isPlaying) continue
                    val pos = p.currentPosition
                    val buf = p.bufferedPosition
                    val ahead = ((buf - pos) / 1000).toInt()
                    if (counter % 5 == 0) {
                        Log.d(TAG, "Live buffer mini: pos=${pos/1000}s buf=${buf/1000}s ahead=${ahead}s")
                    }
                    // 2026-06-16 (FIX #1 audit) : PROACTIVE prepare RETIRE.
                    //   Mon ancien fix mais cause exactement le drain : prepare() invalide
                    //   le buffer = ahead descend. ExoPlayer gere naturellement.
                } catch (_: Exception) {}
            }
        }
    }

    private fun cancelMiniBufferLogger() {
        bufferLoggerJob?.cancel()
        bufferLoggerJob = null
    }

    private fun startServerScout() {
        scoutJob?.cancel()
        scoutJob = scope.launch(Dispatchers.IO) {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                try {
                    delay(10_000L)
                    val uri = withContext(Dispatchers.Main) {
                        player?.currentMediaItem?.localConfiguration?.uri
                    } ?: continue
                    val request = okhttp3.Request.Builder()
                        .url(uri.toString())
                        .head()
                        .header("User-Agent", com.streamflixreborn.streamflix.utils.NetworkClient.USER_AGENT)
                        .build()
                    val resp = try {
                        client.newCall(request).execute()
                    } catch (e: Exception) {
                        Log.d("MiniScout", "Scout request failed: ${e.message}")
                        null
                    }
                    if (resp != null) {
                        val code = resp.code
                        resp.close()
                        if (code == 509 || code == 429 || code in 500..503) {
                            lastScoutBadResponseMs = System.currentTimeMillis()
                            Log.w("MiniScout", "⚠️ Serveur en difficulté: HTTP $code → trigger backup préventif")
                            withContext(Dispatchers.Main) {
                                addBackupPreventively()
                            }
                        } else if (code == 200) {
                            if (System.currentTimeMillis() - lastScoutBadResponseMs < 30_000L) {
                                Log.d("MiniScout", "✅ Serveur recovered: HTTP 200")
                            }
                        }
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.d("MiniScout", "Scout loop error: ${e.message}")
                }
            }
        }
    }

    private fun cancelServerScout() {
        scoutJob?.cancel()
        scoutJob = null
    }

    /**
     * 2026-06-15 (user "applique tous les empoints") : LAZY BACKUP just-in-time
     * porté de PlayerTvFragment ligne 4952-4984. Surveille buffer ahead toutes
     * les 5s. Quand ahead >= 15s ET pas de backup en queue, ajoute un backup
     * MediaItem aligné sur les offsets du primary (anti-déjà-vu). Garantit
     * que le JUMELAGE swap a toujours un backup à utiliser.
     */
    private fun startLazyBackupWatcher() {
        lazyBackupJob?.cancel()
        // 2026-06-15 v6 (aligné PlayerTvFragment.kt ligne 4947-4951) :
        //   les chaînes Stalker `myiptv-live::` enforcent 1 connexion par MAC
        //   côté serveur — ajouter un backup MediaItem ouvre une 2e connexion
        //   → serveur rejette les DEUX → écran noir + retry loop. Le grand
        //   exclut explicitement myiptv-live de la lazy backup. On fait pareil.
        val chIdLazy = currentChannelId
        val excludeFromLazyBackup = chIdLazy != null && chIdLazy.startsWith("myiptv-live::")
        if (excludeFromLazyBackup) {
            Log.d(TAG, "LAZY BACKUP désactivé pour chaîne Stalker $chIdLazy (1-conn par MAC)")
            return
        }
        lazyBackupJob = scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(LAZY_BACKUP_CHECK_INTERVAL_MS)
                try {
                    val p = player ?: continue
                    val ahead = try {
                        ((p.bufferedPosition - p.currentPosition) / 1000).toInt()
                    } catch (_: Exception) { -1 }
                    val hasBackup = try {
                        p.mediaItemCount > 1 && p.hasNextMediaItem()
                    } catch (_: Exception) { false }
                    // 2026-06-16 (audit 3 agents) : watchdog flux corrompu DESACTIVE
                    //   ici (doublonnait avec armLiveBufferingRecovery, qui est aussi
                    //   desactive). Le grand TV a ce watchdog dans progressRunnable
                    //   (PlayerTvFragment 4780-4802) mais avec un Handler 1s, pas dans
                    //   le lazyBackupWatcher. Pour le mini, le JUMELAGE swap (qui se
                    //   declenche AVANT 12s) + handleEndedOrIdle suffisent.
                    if (ahead >= LAZY_BACKUP_AHEAD_THRESHOLD_S && !hasBackup) {
                        val curUri = p.currentMediaItem?.localConfiguration?.uri
                        val curMime = p.currentMediaItem?.localConfiguration?.mimeType
                        if (curUri != null) {
                            try {
                                val backupLazy = MediaItem.Builder()
                                    .setUri(curUri)
                                    .apply { curMime?.let { setMimeType(it) } }
                                    .setLiveConfiguration(
                                        MediaItem.LiveConfiguration.Builder()
                                            .setTargetOffsetMs(55_000)
                                            .setMaxOffsetMs(120_000)
                                            .setMinOffsetMs(30_000)
                                            .setMinPlaybackSpeed(0.95f)
                                            .setMaxPlaybackSpeed(1.0f)
                                            .build()
                                    )
                                    .build()
                                addBackupItemSafely(p, backupLazy)
                                Log.d(TAG, "LAZY BACKUP ajouté just-in-time — buffer ${ahead}s (aligned grand lecteur)")
                            } catch (e: Exception) {
                                Log.w(TAG, "LAZY BACKUP add failed: ${e.message}")
                            }
                        }
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Lazy backup loop error: ${e.message}")
                }
            }
        }
    }

    private fun cancelLazyBackupWatcher() {
        lazyBackupJob?.cancel()
        lazyBackupJob = null
    }

    /**
     * 2026-06-15 v11 (CAUSE des coupures mini identifiée via logcat) :
     * helper qui ajoute un backup au player en utilisant la MÊME factory HLS
     * que le primary (sinon ExoPlayer auto-detect échoue avec
     * UnrecognizedInputFormatException sur HLS sans Content-Type fiable).
     *
     * @param p le player
     * @param backupItem MediaItem à ajouter en backup
     * @return true si ajout via addMediaSource HLS réussi, false si fallback
     *   addMediaItem (= non-IPTV ou factory non dispo).
     */
    private fun addBackupItemSafely(p: ExoPlayer, backupItem: MediaItem): Boolean {
        val chId = currentChannelId ?: return tryAddMediaItem(p, backupItem)
        val dsFactory = currentIptvDataSourceFactory
        if (!currentIptvIsHls || dsFactory == null) {
            return tryAddMediaItem(p, backupItem)
        }
        return try {
            // 2026-06-16 (user "IptvPlayerSetup c'est le code du grand reutilise par
            //   le mini, du coup quand tu modifies le mini, ce code la est pas modifie") :
            //   INLINE du createIptvHlsMediaSource (= contenait de IptvPlayerSetup.kt
            //   ligne 136-160). Maintenant tout est dans MiniPlayerController.
            val hlsExtractorFactory = androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
                true,
            )
            val needsStuckTolerance30x = !chId.startsWith("livehub::francetv::")
            val playlistTrackerFactory = androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker.Factory {
                dsFactory2, loadPolicy, parserFactory, cmcdConfig ->
                androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker(
                    dsFactory2, loadPolicy, parserFactory, cmcdConfig, 30.0,
                )
            }
            val hlsSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dsFactory)
                .setAllowChunklessPreparation(true)
                .setExtractorFactory(hlsExtractorFactory)
                .apply { if (needsStuckTolerance30x) setPlaylistTrackerFactory(playlistTrackerFactory) }
                .createMediaSource(backupItem)
            p.addMediaSource(hlsSource)
            Log.d(TAG, "Backup ajouté via addMediaSource HLS (inline mini, mirror grand)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "addMediaSource HLS backup failed: ${e.message}, fallback addMediaItem")
            tryAddMediaItem(p, backupItem)
        }
    }

    private fun tryAddMediaItem(p: ExoPlayer, item: MediaItem): Boolean {
        return try {
            p.addMediaItem(item)
            true
        } catch (e: Exception) {
            Log.w(TAG, "addMediaItem failed: ${e.message}")
            false
        }
    }

    /**
     * 2026-06-15 : Porté de PlayerTvFragment ligne 5301-5326. Ajoute le backup
     * en queue préventivement quand le scout détecte 509/429/5xx sur le
     * manifest. Pas ajouté si déjà 2+ MediaItems (= éviter doublons).
     */
    private fun addBackupPreventively() {
        try {
            val p = player ?: return
            if (p.mediaItemCount > 1) return
            val curUri = p.currentMediaItem?.localConfiguration?.uri ?: return
            val curMime = p.currentMediaItem?.localConfiguration?.mimeType
            val backupItem = MediaItem.Builder()
                .setUri(curUri)
                .apply { curMime?.let { setMimeType(it) } }
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(55_000)
                        .setMaxOffsetMs(120_000)
                        .setMinOffsetMs(30_000)
                        .setMinPlaybackSpeed(0.95f)
                        .setMaxPlaybackSpeed(1.0f)
                        .build()
                )
                .build()
            addBackupItemSafely(p, backupItem)
            Log.d(TAG, "MiniScout: backup préventif ajouté (server en difficulté détecté)")
        } catch (e: Exception) {
            Log.w(TAG, "MiniScout addBackupPreventively failed: ${e.message}")
        }
    }

    /**
     * 2026-06-15 (user "le mini lecteur doit être autonome") : porté depuis
     * PlayerTvFragment.startPeriodicForcedSwap() (ligne 5216).
     *
     * Toutes les 45s, si un backup MediaItem est dispo et qu'aucun swap n'a
     * eu lieu dans les 20s précédentes, on force un seekToNextMediaItem
     * suivi de seekToDefaultPosition (= live edge) puis on re-ajoute un
     * nouveau backup pour le cycle suivant.
     *
     * But : rafraîchir le primary AVANT que la dégradation CDN (rate-limit,
     * token vieillissant, sliding window manifest qui dérive) ne provoque
     * une vraie coupure. La fenêtre 45s est suffisamment longue pour ne pas
     * gêner l'user mais courte par rapport au timeout typique d'un primary
     * dégradé (~60-90s avant cut).
     */
    private fun startPeriodicForcedSwap() {
        periodicSwapJob?.cancel()
        periodicSwapJob = scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(PERIODIC_SWAP_INTERVAL_MS)
                try {
                    val p = player ?: continue
                    val hasBackup = try {
                        p.mediaItemCount > 1 && p.hasNextMediaItem()
                    } catch (_: Exception) { false }
                    val sinceLastSwap = System.currentTimeMillis() - lastSwapTimestampMs
                    if (hasBackup && sinceLastSwap >= SWAP_RECENT_COOLDOWN_MS) {
                        val ahead = try {
                            ((p.bufferedPosition - p.currentPosition) / 1000).toInt()
                        } catch (_: Exception) { -1 }
                        Log.w(TAG, "FORCED PERIODIC SWAP (timer ${PERIODIC_SWAP_INTERVAL_MS / 1000}s, ahead=${ahead}s) — seekToNextMediaItem")
                        try {
                            p.seekToNextMediaItem()
                            p.seekToDefaultPosition()
                            lastSwapTimestampMs = System.currentTimeMillis()
                            // Re-ARM un backup pour le prochain cycle.
                            // Sinon après le 1er forced swap, plus aucun backup
                            // n'est dispo → 2e forced swap skip → mini reste sur
                            // ce qui est devenu le nouveau primary (= ex-backup).
                            val curUri = p.currentMediaItem?.localConfiguration?.uri
                            val curMime = p.currentMediaItem?.localConfiguration?.mimeType
                            if (curUri != null && p.mediaItemCount == 1) {
                                try {
                                    val newBackup = MediaItem.Builder()
                                        .setUri(curUri)
                                        .apply { curMime?.let { setMimeType(it) } }
                                        .setLiveConfiguration(
                                            MediaItem.LiveConfiguration.Builder()
                                                .setTargetOffsetMs(55_000)
                                                .setMaxOffsetMs(120_000)
                                                .setMinOffsetMs(30_000)
                                                .setMinPlaybackSpeed(0.95f)
                                                .setMaxPlaybackSpeed(1.0f)
                                                .build()
                                        )
                                        .build()
                                    addBackupItemSafely(p, newBackup)
                                    Log.d(TAG, "Backup re-armé après FORCED SWAP")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Re-arm backup failed: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "FORCED PERIODIC SWAP seekToNextMediaItem failed: ${e.message}")
                        }
                    } else if (!hasBackup) {
                        Log.d(TAG, "FORCED SWAP skipped: no backup ready")
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Periodic swap loop error: ${e.message}")
                }
            }
        }
    }

    private fun cancelPeriodicForcedSwap() {
        periodicSwapJob?.cancel()
        periodicSwapJob = null
    }

    // 2026-06-16 (user "IptvPlayerSetup c'est le code du grand reutilise") :
    //   INLINE de createResilientLoadErrorPolicy (= contenait de IptvPlayerSetup.kt
    //   ligne 74-94). Retry exponentiel 500ms → 16s (max 6 retries) sur 403/5xx HLS.
    private val resilientLoadErrorPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy =
        object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(
                loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
            ): Long {
                val ex = loadErrorInfo.exception
                if (ex is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    val code = ex.responseCode
                    if (code == 403 || code == 500 || code == 502 || code == 503 || code == 509 || code == 429) {
                        val attempt = loadErrorInfo.errorCount
                        if (attempt < 6) {
                            val delay = 500L * (1L shl attempt.coerceAtMost(5))
                            Log.d(TAG, "LoadError retry $attempt/6 for HTTP $code, delay=${delay}ms")
                            return delay.coerceAtMost(16_000L)
                        }
                        Log.w(TAG, "LoadError gave up after 6 attempts on HTTP $code")
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
        // 2026-06-15 (user "le mini lecteur a pas le même pattern que le grand
        //   lecteur") : bufferForPlaybackAfterRebufferMs 2000 → 500ms pour
        //   aligner sur PlayerTvFragment IPTV (lines 5612-5618). Avant : après
        //   une mini-coupure, le mini attendait 2s de buffer avant de reprendre
        //   = user voit la coupure. Après : 0.5s = reprise quasi-instantanée.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 60_000,
                /* maxBufferMs */ 300_000,
                /* bufferForPlaybackMs */ 1_000,
                /* bufferForPlaybackAfterRebufferMs */ 500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 2026-05-17 : MediaSourceFactory avec retry policy résilient (cf
        //   resilientLoadErrorPolicy déclaré au niveau classe).
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(resilientLoadErrorPolicy)

        // 2026-06-15 v8 (user "se coupe moins mais lag") : EXTENSION_RENDERER_MODE_ON
        //   = HW decoder prioritaire avec fallback software. EXACTEMENT le pattern
        //   du grand (PlayerTvFragment ligne 5662). HW = pas de lag CPU Chromecast.
        //   La cause des cuts v6 = la policy MONO-SERVER/HARD FAIL (supprimée
        //   en v7), PAS le decoder. Maintenant strict miroir grand sur tous les
        //   aspects.
        val miniRenderersFactory = try {
            Log.d(TAG, "RenderersFactory: EXTENSION_RENDERER_MODE_ON (HW prefer, sw fallback = strict miroir grand)")
            io.github.anilbeesetti.nextlib.media3ext.ffdecoder
                .NextRenderersFactory(context).apply {
                    setEnableDecoderFallback(true)
                    setExtensionRendererMode(
                        androidx.media3.exoplayer.DefaultRenderersFactory
                            .EXTENSION_RENDERER_MODE_ON
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
                // 2026-06-15 v6 (aligné PlayerTvFragment.kt ligne 6035-6039) :
                //   handleAudioFocus = false pour le mini car il joue de l'IPTV
                //   en permanence (= comportement TV). Avant : true → une notif
                //   système ou un autre player coupe le live. Maintenant : false
                //   → le live IPTV continue de jouer (= identique au grand TV).
                /* handleAudioFocus */ false
            )
            .build().apply {
                playWhenReady = true
                addListener(playerListener)
                // 2026-06-16 (PREUVE LOGS CODEC) : setMaxVideoSize REMIS DEFINITIVEMENT.
                //   Test sans cap = yoyo ABR 576p<->720p en 30s = codec reset visible
                //   = "boucle". Cap a 480p = un seul variant = pas de switch codec.
                //   Le grand utilise AdaptiveQualityGovernor.applyCap (ligne 116) avec
                //   setMaxVideoSize aussi quand qualite adaptative ON. Donc le mini
                //   PEUT utiliser setMaxVideoSize sans diverger du grand.
                try {
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setMaxVideoSize(854, 480)
                        .build()
                    Log.d(TAG, "Mini cap 854x480 (anti-ABR-yoyo, mirror grand AdaptiveQualityGovernor)")
                } catch (e: Exception) {
                    Log.w(TAG, "setMaxVideoSize failed: ${e.message}")
                }
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
        fireChannelStateChanged()
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
        // 2026-06-17 (user "image figée mobile") : CRITIQUE — reset le flag
        //   iptvCurrentStreamHasWorked AVANT player.stop(). Sinon le stop()
        //   déclenche onPlaybackStateChanged(STATE_IDLE) → handleEndedOrIdle()
        //   voit le flag à true ET un backup MediaItem → swap vers l'ancienne
        //   chaîne backup → le player se figure sur l'ancien rendu. Quand
        //   playServerAtIndex arrive ensuite à setMediaSource(nouvelle), il
        //   est trop tard car le player a déjà été corrompu par le swap.
        iptvCurrentStreamHasWorked = false
        // 2026-05-09 : STOP le player avant de switcher de canal, sinon si le
        // fetch du nouveau canal foire, la chaîne précédente continue de jouer
        // (bug : "je clique BFMTV et le player joue Canal+ que j'avais cliqué avant").
        try {
            player?.let { p ->
                p.stop()
                p.clearMediaItems()
            }
        } catch (_: Throwable) {}
        // 2026-06-22 (user "changement rapide → erreur bloquante") :
        //   invalider la génération media pour que onPlayerError ignore
        //   toute erreur stale de l'ancien flux interrompu par le stop().
        activeMediaGeneration = 0
        // 2026-06-22 : si le player a été détruit (par un stop() complet
        //   ou une erreur précédente), le re-créer pour que playServerAtIndex
        //   ne tombe pas sur `player ?: return` silencieux.
        if (player == null) {
            val ctx = appContext
            if (ctx != null) {
                Log.d(TAG, "playChannel: player was null — reinitializing")
                initPlayer(ctx)
            } else {
                Log.e(TAG, "playChannel: player null AND no appContext — cannot play")
                _state.value = State.Error(channelId, "Player not initialized")
                return
            }
        }
        // 2026-05-17 (user "le cache 100 Mo est vidé à chaque changement de
        //   chaîne, il y a intérêt") : clear le DVR cache pour dédier les
        //   100 Mo à la nouvelle chaîne uniquement.
        if (currentChannelId != channelId) {
            try {
                com.streamflixreborn.streamflix.StreamFlixApp.clearLiveCache()
            } catch (_: Exception) { }
        }
        // 2026-06-17 (user "quand je swap de chaîne ça garde la même en mémoire") :
        //   incrémente la génération AVANT de toucher au state. Les anciennes
        //   coroutines en cours (loadJob, progressiveServerJob, playServerAtIndex)
        //   qui n'ont pas encore terminé verront leur génération locale différer
        //   de cette nouvelle valeur et abandonneront leur setMediaSource.
        val newGen = ++playChannelGeneration
        Log.d(TAG, "playChannel: gen=$newGen $channelName ($channelId)")
        currentChannelId = channelId
        currentChannelName = channelName
        currentChannelPoster = channelPoster
        // 2026-06-20 (user "le bouton plein écran apparaît pour OTF mais pas
        //   pour les autres") : déclenche le callback APRÈS que currentChannelId
        //   soit set — sinon updateNavigationVisibility() lit l'ancienne valeur
        //   (null) et cache l'item.
        fireChannelStateChanged()
        availableServers.clear()
        triedServerUrls.clear()
        hostFailCounts.clear()
        currentServerIndex = 0
        retryCycle = 0
        serversExhausted = false
        retryJob?.cancel()
        progressiveServerJob?.cancel()
        cancelBufferingWatchdog()
        cancelStuckBufferingWatchdog()
        cancelLiveBufferingRecovery()
        cancelPeriodicForcedSwap()
        cancelLazyBackupWatcher()
        // 2026-06-16 (audit agent #3) : cancelServerScout manquait dans playChannel,
        //   le job HEAD-probe survivait a la transition et continuait a prober
        //   l URI de l ancienne chaine.
        cancelServerScout()
        // 2026-06-16 : reset le flag iptvCurrentStreamHasWorked (= la branche
        //   Transfer-recovery ENDED/IDLE ne se déclenche que si on a déjà joué).
        iptvCurrentStreamHasWorked = false
        // Reset bufferingStartMs (= sinon timestamp ancien dans la nouvelle chaîne
        //   déclenche un swap immédiat dès le 1er STATE_BUFFERING).
        bufferingStartMs = 0L
        // 2026-06-16 (audit #72/#84) : reset state variables a new channel pour
        //   miroir grand (= fragment lifecycle reset implicite).
        lastSwapTimestampMs = 0L
        lastObservedPositionMs = -1L
        stuckBufferingTicks = 0
        stuckPosCounter = 0
        lastStuckPosKey2s = -1
        // Reset recentReloadTimestamps (= sinon l'anti-flap de la chaîne
        //   précédente bloque le mécanisme sur la nouvelle chaîne).
        recentReloadTimestamps.clear()
        _state.value = State.Loading(channelId, channelName)

        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                // 2026-06-17 : capture la génération LOCALE pour cette coroutine. Si
                //   un playChannel ultérieur incrémente playChannelGeneration, on
                //   abandonne (= jamais de setMediaSource pour une chaîne stale).
                val gen = newGen
                if (gen != playChannelGeneration) return@launch

                // 2026-05-31 : résoudre le provider IPTV depuis le préfixe de l'ID
                // plutôt que UserPreferences.currentProvider. Quand le user est sur
                // un provider Films/Séries (Cloudstream, Movix) et navigue dans le
                // TV Hub, currentProvider n'est PAS un IptvProvider → playback échoue.
                val provider = resolveIptvProvider(channelId)
                    ?: run {
                        _state.value = State.Error(channelId, "Not an IPTV provider")
                        return@launch
                    }
                if (gen != playChannelGeneration) return@launch

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
                // 2026-06-17 : re-check génération après le getServers() suspend point.
                if (gen != playChannelGeneration) {
                    Log.d(TAG, "loadJob[gen=$gen]: aborting after getServers — newer playChannel (gen=${playChannelGeneration}) took over")
                    return@launch
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
                playServerAtIndex(channelId, provider, startIndex, gen)

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
    /**
     * 2026-06-17 (user "quand je swap de chaîne ça garde la même en mémoire") :
     * paramètre `gen` ajouté pour le check de génération atomique. Le caller
     * passe la valeur de `playChannelGeneration` qu'il a capturée à son lancement.
     * Si entre temps un nouveau playChannel a incrémenté la génération → on
     * abandonne le setMediaSource pour éviter d'écraser le player avec une
     * ancienne URL.
     */
    private suspend fun playServerAtIndex(channelId: String, provider: Provider, index: Int, gen: Int = playChannelGeneration) {
        if (currentChannelId != channelId || gen != playChannelGeneration) return // channel changed or generation expired
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

            // 2026-06-17 : re-check génération après getVideo() suspend point. Sans ça,
            //   un playChannel intermédiaire pouvait laisser ce code finir setMediaSource
            //   avec l'URL de la chaîne précédente → mini lecteur jouait l'ancienne chaîne.
            if (currentChannelId != channelId || gen != playChannelGeneration) {
                Log.d(TAG, "playServerAtIndex[gen=$gen]: aborting after getVideo (current=${playChannelGeneration})")
                return
            }

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
            // 2026-06-15 v10 (user "même pipeline mais mini coupe, pas le grand") :
            //   le grand crée un NOUVEAU player à chaque entrée Fragment → état neuf.
            //   Le mini est un singleton qui réutilise le SAME ExoPlayer → entre 2
            //   chaînes, des frames decoder + buffer obsolète peuvent persister →
            //   micro-coupures. Fix : avant le setMediaSource, on STOP + CLEAR pour
            //   simuler un player frais. Aucun crash visible (le player est OK pour
            //   réutilisation après stop+prepare).
            try {
                p.stop()
                p.clearMediaItems()
            } catch (_: Throwable) {}
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
                        // 2026-06-15 v5 (user "le mini bugue toujours, applique
                        //   le MÊME pattern") : aligné EXACT sur PlayerTvFragment
                        //   ligne 3796-3801 — targetOffsetMs 55_000 → 45_000,
                        //   minOffsetMs 30_000 → 20_000. Le mini jouait 10s plus
                        //   loin du live edge → tombait en fin de fenêtre ParaTV
                        //   ~80s → segments expirés → micro-coupures à chaque
                        //   bord. À 45s on a 30s+ de marge fenêtre → fluide.
                        setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(45_000)
                                .setMaxOffsetMs(120_000)
                                .setMinOffsetMs(20_000)
                                .setMinPlaybackSpeed(0.95f)
                                // 2026-06-16 (FIX #2 audit) : 1.1 → 1.0 = mirror grand.
                                //   1.1 etait la CAUSE du drain : ExoPlayer accelerait pour
                                //   rattraper le live edge ET consommait 10% plus vite que
                                //   le reseau ne livrait = drain linaire 20s→0s en 30s.
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
            // 2026-06-15 (user "le mini lecteur doit être complet comme si
            //   c'était le grand lecteur point barre") : audit a montré que le
            //   grand utilise DefaultHttpDataSource + LiveReconnectingHttpDataSource
            //   wrapper (PlayerTvFragment.kt:5997-6013), alors que le mini
            //   utilisait OkHttp/Cronet brut sans wrapper. LiveReconnecting
            //   détecte les /live/.../N.ts (Xtream + ParaTV TNT) et rouvre
            //   auto la connexion sur EOF que ces serveurs envoient via
            //   Connection: close après chaque segment → primary cause des
            //   cuts ParaTV TF1/M6 alors que le grand tient. Fix : pour
            //   toutes les chaînes IPTV NON-Vavoo (= Vavoo a besoin de
            //   Cronet pour son JA3 fingerprint anti-pub VYPN), on bascule
            //   sur le même pattern que le grand. Vavoo garde OkHttp+Cronet.
            val isVavoo = channelId.startsWith("vavoo::")
            val isIptvNonVavoo = isLiveChannel && !isVavoo
            val dsFactory: androidx.media3.datasource.DataSource.Factory? = if (isIptvNonVavoo) {
                val ua = perVideoHeaders["User-Agent"]
                    ?: com.streamflixreborn.streamflix.utils.NetworkClient.USER_AGENT
                val base = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent(ua)
                    .setConnectTimeoutMs(20_000)
                    .setReadTimeoutMs(30_000)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(perVideoHeaders.filterKeys { it != "User-Agent" })
                Log.d(TAG, "Using DefaultHttpDataSource + LiveReconnecting (aligné grand lecteur) ua=$ua")
                com.streamflixreborn.streamflix.utils.LiveReconnectingHttpDataSource.Factory(base)
            } else {
                httpDataSourceFactory
            }
            // 2026-06-22 : marquer la génération du media qui va être chargé.
            //   onPlayerError ne traitera que les erreurs de CETTE génération.
            activeMediaGeneration = gen

            if (isHls && dsFactory != null) {
                // 2026-06-16 (FIX #4 audit) : CacheDataSource RETIRE pour live IPTV.
                //   Le grand n utilise pas CacheDataSource. La cache disque serialise
                //   les lectures via single-writer lock = throttle download HLS = drain
                //   buffer mini. Pour live, utiliser directement le dsFactory sans wrap.
                val finalDataSource = dsFactory
                val cache: Any? = null
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
                // 2026-06-16 (user "IptvPlayerSetup c'est le code du grand reutilise") :
                //   INLINE direct du HlsMediaSource.Factory (= IptvPlayerSetup.kt
                //   createIptvHlsMediaSource est INUTILE car le code etait deja
                //   inline juste au-dessus avec hlsExtractorFactory + playlistTrackerFactory).
                //   Maintenant TOUT est dans MiniPlayerController.
                currentIptvDataSourceFactory = finalDataSource
                currentIptvIsHls = true
                val needsStuckTolerance30x = !channelId.startsWith("livehub::francetv::")
                val hlsSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(finalDataSource)
                    .setAllowChunklessPreparation(true)
                    .setExtractorFactory(hlsExtractorFactory)
                    .apply { if (needsStuckTolerance30x) setPlaylistTrackerFactory(playlistTrackerFactory) }
                    .createMediaSource(mediaItem)
                // 2026-06-17 : LAST barrier avant l'écriture sur le player —
                //   protège contre une race entre cancellation et setMediaSource.
                if (currentChannelId != channelId || gen != playChannelGeneration) {
                    Log.d(TAG, "Aborting setMediaSource — gen mismatch ($gen vs ${playChannelGeneration})")
                    return
                }
                p.setMediaSource(hlsSource)
                Log.d(TAG, "HlsMediaSource inline mini (mirror grand, cid=$channelId, cache=${cache != null})")
            } else if (isDash && dsFactory != null) {
                val dashFactory = androidx.media3.exoplayer.dash.DashMediaSource.Factory(dsFactory)
                // v38 : Widevine DRM pour TF1+ VOD aussi dans le mini-lecteur.
                //   Sans ça, films TF1+ écran noir + son crypté (cf v34/v37 fullscreen).
                // 2026-06-19 : étendu pour M6+ (même pattern).
                // 2026-06-27 : Pluto DASH ne doit PAS passer par le DRM TF1/M6/BFM
                //   (faux match → message « connecte-toi à M6 »). Pluto = clair (joue
                //   direct) ou DRM Pluto natif (≠ M6, à faire plus tard).
                val isPlutoDash = video.source.contains("pluto.tv")
                val widevineUrl = if (isPlutoDash)
                    com.streamflixreborn.streamflix.utils.PlutoTvResolver.getWidevineLicenseUrl(video.source)
                else
                    (com.streamflixreborn.streamflix.utils.TF1Resolver
                    .getWidevineLicenseUrl(video.source)
                    ?: com.streamflixreborn.streamflix.utils.M6Resolver
                        .getWidevineLicenseUrl(video.source)
                    ?: com.streamflixreborn.streamflix.utils.BfmResolver
                        .getWidevineLicenseUrl(video.source))
                // Pluto = HttpMediaDrmCallback standard (jwt dans l'URL) → drmHeaders null.
                val drmHeaders = if (isPlutoDash) null else
                    (com.streamflixreborn.streamflix.utils.M6Resolver
                    .getWidevineHeaders(video.source)
                    ?: com.streamflixreborn.streamflix.utils.BfmResolver
                        .getWidevineHeaders(video.source))
                if (widevineUrl != null) {
                    Log.d(TAG, "Mini DASH+Widevine DRM: license=${widevineUrl.take(80)}... headers=${drmHeaders?.keys}")
                    // 2026-06-19 v17 : si M6+ → M6DrmCallback (JSON wrapper)
                    // 2026-06-20 : BFM Play → BfmDrmCallback (raw Widevine bytes)
                    val isM6 = drmHeaders?.containsKey("x-dt-auth-token") == true
                    val isBfm = drmHeaders?.containsKey("customdata") == true
                    val drmCallback: androidx.media3.exoplayer.drm.MediaDrmCallback = if (isM6) {
                        Log.d(TAG, "Mini using M6DrmCallback (DRMtoday JSON wrapper)")
                        com.streamflixreborn.streamflix.utils.M6DrmCallback(widevineUrl, drmHeaders!!)
                    } else if (isBfm) {
                        Log.d(TAG, "Mini using BfmDrmCallback (raw Widevine)")
                        com.streamflixreborn.streamflix.utils.BfmDrmCallback(widevineUrl, drmHeaders!!)
                    } else {
                        val cb = androidx.media3.exoplayer.drm.HttpMediaDrmCallback(
                            widevineUrl,
                            androidx.media3.datasource.DefaultHttpDataSource.Factory()
                                .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                        )
                        drmHeaders?.forEach { (k, v) ->
                            try { cb.setKeyRequestProperty(k, v) } catch (_: Throwable) {}
                        }
                        cb
                    }
                    val l3Provider = androidx.media3.exoplayer.drm.ExoMediaDrm.Provider { uuid ->
                        val drm = androidx.media3.exoplayer.drm.FrameworkMediaDrm.newInstance(uuid)
                        try { drm.setPropertyString("securityLevel", "L3") } catch (_: Exception) {}
                        drm
                    }
                    val drmSessionManager = androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(androidx.media3.common.C.WIDEVINE_UUID, l3Provider)
                        .setMultiSession(false)
                        .build(drmCallback)
                    dashFactory.setDrmSessionManagerProvider { drmSessionManager }
                }
                val dashSource = dashFactory.createMediaSource(mediaItem)
                p.setMediaSource(dashSource)
                Log.d(TAG, "Using DashMediaSource (DRM=${widevineUrl != null})")
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
            // 2026-06-27 (user "Plex films/séries en anglais") : audio FR préféré
            //   pour le VOD Plex/Pluto dans le mini-lecteur. Le catalogue France
            //   (géo XFF=FR) a souvent une piste VF dans le manifeste (ex "The 2nd"
            //   = 8 EN + 1 FR) qu'ExoPlayer ne sélectionnait pas faute de langue
            //   audio préférée. Scopé Plex/Pluto VOD (pas le live, pas les animes).
            try {
                val cidVod = currentChannelId ?: ""
                if (cidVod.contains("plexvod_") || cidVod.contains("plexep::") ||
                    cidVod.contains("plutomovie_") || cidVod.contains("plutoep::")) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setPreferredAudioLanguages("fr", "fre", "fra")
                        .build()
                    Log.d(TAG, "Plex/Pluto VOD → audio FR préféré (mini)")
                }
            } catch (_: Throwable) {}
            // 2026-05-17 v7 : on laisse ExoPlayer gérer le speed via la
            //   LiveConfiguration range 0.95-1.0x. PAS de setPlaybackParameters
            //   manuel — ça override la LiveConfiguration et cause des sauts
            //   de phase. Le VOD reste à 1.0x naturel.
            p.prepare()
            // 2026-06-16 (audit #52) : MIRROR grand PlayerTvFragment 4492-4498 —
            //   force seek live edge au start pour les chaines live IPTV. Sans
            //   ca, ExoPlayer peut demarrer a un offset random (debut sliding
            //   window HLS) qui peut etre derriere le live edge cible et causer
            //   des BUFFERING immediat pour rattraper.
            if (isLiveChannel) {
                try { p.seekToDefaultPosition() } catch (_: Exception) {}
            }
            // 2026-06-17 FIX BUG mini mobile : "image figée audio change" entre 2
            //   chaînes. Le `p.stop()` + `p.clearMediaItems()` ligne 2443-2444
            //   détache la SurfaceView vidéo du player (l'audio se ré-attache
            //   automatiquement via AudioTrack mais pas la vidéo). Symptôme :
            //   son change OK mais image reste figée sur la dernière frame.
            //   Fix : re-attacher PlayerView au player ici (= force ExoPlayer
            //   à reconnecter le video renderer au SurfaceView de la mini PlayerView).
            try {
                miniPlayerView?.let { v ->
                    if (v.player != p) v.player = p
                    else {
                        // même player object → force re-attach surface
                        v.player = null
                        v.player = p
                    }
                }
            } catch (_: Throwable) {}
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
                // 2026-06-16 (user "mini lecteur a toujours des micro coupures
                //   mais c'est mieux") : MIRROR EXACT du grand PlayerTvFragment
                //   ligne 3608 — "v47 FORCED PERIODIC SWAP désactivé : causait
                //   2x plus de decoder resets = 2x plus de cuts. PREEMPTIVE swap
                //   natif suffit". Le mini, lui, l'appelait toujours toutes les
                //   45s → forçait un seekToNextMediaItem inutile sur un stream
                //   stable → coupure visible toutes les 45s. Désactivé pour
                //   matcher le comportement du grand.
                // Reset du timestamp pour cohérence avec le JUMELAGE swap.
                lastSwapTimestampMs = System.currentTimeMillis()
                // 2026-06-16 (FIX #3 audit) : pour worldlivetv, le backup SAME-URL ouvre
                //   une 2e connexion ParaTV qui se PARTAGE la bande passante avec primary
                //   = drain. Désactivé. Pour les autres providers (OLA, Vegeta, etc.),
                //   le backup reste actif comme avant.
                val isWorldLive = channelId.startsWith("livehub::worldlivetv::")
                if (!isWorldLive) {
                    startLazyBackupWatcher()
                }
                backupJob?.cancel()
                if (!isWorldLive) backupJob = scope.launch {
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
                                    // 2026-06-16 (audit) : minOffsetMs 40s -> 30s pour matcher
                                    //   le grand backup (PlayerTvFragment 4977-4985). Le mini
                                    //   etait 10s plus loin du live edge = potentiel deja-vu
                                    //   visuel au swap, contredisant le commentaire "aligne".
                                    .setMinOffsetMs(30_000)
                                    .setMinPlaybackSpeed(0.95f)
                                    .setMaxPlaybackSpeed(1.0f)
                                    .build()
                            )
                            .build()
                        withContext(Dispatchers.Main) {
                            try {
                                if (currentChannelId == channelId && p == player && p.mediaItemCount == 1) {
                                    addBackupItemSafely(p, backupMediaItem)
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
        activeMediaGeneration = 0 // 2026-06-22 : invalider pour ignorer erreurs stale
        fireChannelStateChanged()
        // 2026-06-19 (user "sur mobile quand je ferme le mini lecteur ça
        //   continue en arrière plan sur World Live") : stop() ne faisait
        //   PAS `player.release()` ni `stopRadioForegroundService()` →
        //   l'instance ExoPlayer restait vivante avec son audio renderer
        //   (MediaCodec garde l'audio actif) ET le service radio foreground
        //   gardait la notification = audio continue en background. Tous
        //   les call sites de stop() sont des vraies fermetures (clic
        //   close, change provider, settings), aucun ne fait de pause/reuse
        //   après → on peut renforcer stop() en place sans risque.
        stopRadioForegroundService()
        radioOriginProviderName = null
        loadJob?.cancel()
        retryJob?.cancel()
        progressiveServerJob?.cancel()
        cancelBufferingWatchdog()
        cancelStuckBufferingWatchdog()  // 2026-06-16 (audit) : manquait dans stop()
        cancelLiveBufferingRecovery()
        cancelPeriodicForcedSwap()
        cancelLazyBackupWatcher()
        cancelServerScout()             // 2026-06-16 (audit) : manquait dans stop()
        backupJob?.cancel()
        hideFreezeOverlay()             // mirror grand
        val p = player
        player = null
        if (p != null) {
            try { p.removeListener(playerListener) } catch (_: Exception) {}
            try { p.stop() } catch (_: Exception) {}
            try { p.clearMediaItems() } catch (_: Exception) {}
            try { p.release() } catch (e: Exception) {
                Log.w(TAG, "stop: release error: ${e.message}")
            }
        }
        // Au cas où un detachedPlayer (= transition fullscreen abandonnée)
        //   serait encore présent, on le coupe aussi.
        detachedPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
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
        cancelLiveBufferingRecovery()
        cancelPeriodicForcedSwap()
        cancelLazyBackupWatcher()
        backupJob?.cancel()
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
        activeMediaGeneration = 0 // 2026-06-22 : invalider pour ignorer erreurs stale
        // 2026-06-09 : arrêter le keep-alive radio si actif.
        stopRadioForegroundService()
        radioOriginProviderName = null
        loadJob?.cancel()
        retryJob?.cancel()
        progressiveServerJob?.cancel()
        cancelLiveBufferingRecovery()
        cancelPeriodicForcedSwap()
        cancelLazyBackupWatcher()
        backupJob?.cancel()
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
        cancelLiveBufferingRecovery()
        cancelPeriodicForcedSwap()
        cancelLazyBackupWatcher()
        backupJob?.cancel()
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
        cancelLiveBufferingRecovery()
        cancelPeriodicForcedSwap()
        cancelLazyBackupWatcher()
        backupJob?.cancel()
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
