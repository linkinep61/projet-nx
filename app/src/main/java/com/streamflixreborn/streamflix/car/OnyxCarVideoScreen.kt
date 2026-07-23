package com.streamflixreborn.streamflix.car

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.launch

/**
 * 2026-07-23 (projet Android Auto) — PROJECTION VIDÉO sur l'écran voiture (équivalent CarStream).
 *
 * MIROIR (défaut) : la surface voiture affiche la lecture du lecteur TÉLÉPHONE publié dans
 * CarPlaybackBridge (callbacks génériques → marche pour Media3 ET ExoPlayer 2.19.1 des chaînes OTF).
 * DÉMO (videoUrl fourni) : joue un flux de test avec un ExoPlayer local.
 *
 * ZOOM (barre de carte) : compositeur GL → Réduire/Agrandir. Play/Pause (barre du haut) → pilote le
 * lecteur miroir. (AA réserve la barre média du bas aux apps média, incompatible avec la projection.)
 */
@androidx.media3.common.util.UnstableApi
class OnyxCarVideoScreen(
    carContext: CarContext,
    private val videoUrl: String? = null,
    private val label: String = "ONYX",
) : Screen(carContext), SurfaceCallback {

    private var demoPlayer: ExoPlayer? = null
    private var aaSurface: Surface? = null
    private var gl: CarVideoGlRenderer? = null
    private var zoom = 1f
    private var audioFocusRequest: AudioFocusRequest? = null
    private val mirror get() = videoUrl == null

    init {
        Log.i(TAG, "init mirror=$mirror → setSurfaceCallback")
        runCatching {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        }.onFailure { Log.e(TAG, "setSurfaceCallback FAILED: ${it.message}", it) }

        if (mirror) {
            lifecycleScope.launch {
                CarPlaybackBridge.version.collect {
                    Log.i(TAG, "bridge version=$it hasPlayer=${CarPlaybackBridge.hasPlayer}")
                    attachMirror()
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val playing = if (mirror) (CarPlaybackBridge.getIsPlaying?.invoke() ?: false)
        else demoPlayer?.playWhenReady ?: false
        val playPauseIcon = if (playing)
            com.streamflixreborn.streamflix.R.drawable.ic_pause
        else com.streamflixreborn.streamflix.R.drawable.ic_car_play
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder().setIcon(carIcon(playPauseIcon))
                            .setOnClickListener { togglePlayPause() }.build(),
                    )
                    .addAction(Action.BACK)
                    .build(),
            )
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder().setIcon(carIcon(com.streamflixreborn.streamflix.R.drawable.ic_car_minus))
                            .setOnClickListener { changeZoom(-0.06f) }.build(),
                    )
                    .addAction(
                        Action.Builder().setIcon(carIcon(com.streamflixreborn.streamflix.R.drawable.ic_car_plus))
                            .setOnClickListener { changeZoom(+0.06f) }.build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun carIcon(res: Int) =
        androidx.car.app.model.CarIcon.Builder(
            androidx.core.graphics.drawable.IconCompat.createWithResource(carContext, res),
        ).build()

    private fun togglePlayPause() {
        if (mirror) {
            val now = CarPlaybackBridge.getIsPlaying?.invoke() ?: return
            CarPlaybackBridge.setPlaying?.invoke(!now)
        } else {
            demoPlayer?.let { it.playWhenReady = !it.playWhenReady }
        }
        invalidate()
    }

    private fun changeZoom(delta: Float) {
        zoom = (zoom + delta).coerceIn(0.4f, 1f)
        gl?.setScale(zoom)
        Log.i(TAG, "zoom=$zoom")
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val s = surfaceContainer.surface
        val w = surfaceContainer.width
        val h = surfaceContainer.height
        Log.i(TAG, "onSurfaceAvailable: $s ${w}x$h")
        if (s == null) { Log.e(TAG, "surface NULL"); return }
        aaSurface = s
        gl = runCatching { CarVideoGlRenderer(s, w, h) }.getOrElse {
            Log.w(TAG, "GL indispo, surface directe: ${it.message}"); null
        }
        gl?.setScale(zoom)
        if (mirror) attachMirror() else startDemo()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "onSurfaceDestroyed")
        if (mirror) {
            abandonCarAudioFocus()
            runCatching { CarPlaybackBridge.setSurface?.invoke(null) }
            runCatching { CarPlaybackBridge.reattachPhoneSurface?.invoke() }
        } else {
            demoPlayer?.setVideoSurface(null)
            demoPlayer?.release()
            demoPlayer = null
        }
        runCatching { gl?.release() }
        gl = null
        aaSurface = null
    }

    /** Cible de rendu : surface d'entrée du compositeur GL, ou surface AA directe en secours. */
    private fun renderTarget(): Surface? = gl?.inputSurface ?: aaSurface

    private fun attachMirror() {
        val target = renderTarget() ?: return
        val setSurface = CarPlaybackBridge.setSurface ?: run { Log.i(TAG, "attachMirror: aucun lecteur"); return }
        runCatching {
            requestCarAudioFocus()
            setSurface(target)
            Log.i(TAG, "attachMirror: surface attachée (${CarPlaybackBridge.title}) gl=${gl != null}")
        }.onFailure { Log.e(TAG, "attachMirror KO: ${it.message}", it) }
    }

    private fun startDemo() {
        val target = renderTarget() ?: return
        val p = ExoPlayer.Builder(carContext).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { Log.i(TAG, "demo state=$state") }
            override fun onPlayerError(error: PlaybackException) { Log.e(TAG, "demo ERROR ${error.errorCodeName}", error) }
        })
        p.setVideoSurface(target)
        p.setMediaItem(MediaItem.fromUri(videoUrl!!))
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.prepare()
        p.playWhenReady = true
        demoPlayer = p
    }

    // ── Focus audio média (chaînes live : le lecteur téléphone n'ouvre pas le canal audio voiture) ──
    private fun requestCarAudioFocus() {
        if (audioFocusRequest != null) return
        val am = runCatching { carContext.getSystemService(AudioManager::class.java) }.getOrNull() ?: return
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).build()
        runCatching { am.requestAudioFocus(req) }
        audioFocusRequest = req
    }

    private fun abandonCarAudioFocus() {
        val req = audioFocusRequest ?: return
        val am = runCatching { carContext.getSystemService(AudioManager::class.java) }.getOrNull()
        runCatching { am?.abandonAudioFocusRequest(req) }
        audioFocusRequest = null
    }

    companion object {
        private const val TAG = "OnyxCarVideo"
    }
}
