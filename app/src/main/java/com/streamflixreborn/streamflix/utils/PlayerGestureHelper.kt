package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.streamflixreborn.streamflix.ui.PlayerMobileView
import com.streamflixreborn.streamflix.ui.PlayerTvView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class PlayerGestureHelper(
    private val context: Context,
    private val playerView: PlayerView,
    private val brightnessLayout: View,
    private val brightnessBar: ProgressBar,
    private val brightnessText: TextView,
    private val volumeLayout: View,
    private val volumeBar: ProgressBar,
    private val volumeText: TextView,
    /** 2026-06-21 (user "sur mobile quand on double-clic à gauche ou à droite,
     *  ça active retour 10s / avant 10s. Est-ce que c'est faisable chez nous") :
     *  callback appelé sur double-tap. side = "left" → reculer 10s,
     *  side = "right" → avancer 10s. Le caller (PlayerMobileFragment) execute
     *  player.seekTo + affiche l'overlay visuel. */
    private val onDoubleTapSeek: ((side: String) -> Unit)? = null,
) {

    private val gestureDetector: GestureDetector
    private val scaleGestureDetector: ScaleGestureDetector
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var hideJob: Job? = null
    
    private val sensitivity = 1.2f
    private var isScrolling = false
    private var isScaling = false
    private var currentVolumeFloat = 0f
    private var maxVolume = 0

    /** 2026-06-12 (user "verrou enfant : qu'on puisse pas toucher à rien
     *  à part le cadenas") : flag global qui désactive TOUS les gestures
     *  (swipe brightness/volume, double-tap seek, pinch-zoom) tant qu'on est
     *  en mode locked. Le code touch listener fait early-return true (=
     *  consomme l'event sans le traiter) → ExoPlayer ne reçoit même pas le
     *  tap → controller ne s'affiche pas. Seul le btnExoUnlock reste
     *  cliquable car il vit au-dessus en z-order.
     */
    var isLocked: Boolean = false

    /**
     * 2026-07-11 : callback pour toggler le controller en mode VLC.
     * Le GestureDetector interne consomme les taps avant que PlayerView.onTouchEvent
     * ne puisse appeler toggleControllerVisibility(). Ce callback est appelé sur
     * onSingleTapConfirmed (= tap confirmé, pas un double-tap) et permet au fragment
     * de show/hide le controller explicitement.
     * Null = désactivé (mode ExoPlayer normal).
     */
    var vlcTapCallback: (() -> Unit)? = null

    init {
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Rilevatore per il Pinch-to-Zoom (Dita)
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val videoView = playerView.videoSurfaceView ?: return false
                isScaling = true
                
                // Applichiamo lo zoom in tempo reale (come per la TV)
                videoView.scaleX *= detector.scaleFactor
                videoView.scaleY *= detector.scaleFactor
                
                // Limiti minimi e massimi per evitare di perdere il video
                if (videoView.scaleX < 0.25f) videoView.scaleX = 0.25f
                if (videoView.scaleY < 0.25f) videoView.scaleY = 0.25f
                if (videoView.scaleX > 4.0f) videoView.scaleX = 4.0f
                if (videoView.scaleY > 4.0f) videoView.scaleY = 4.0f
                
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                isScrolling = false
                currentVolumeFloat = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                return true 
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null || isScaling) return false
                if (e1.y < 100) return false

                if (!isScrolling) {
                    val deltaX = e2.x - e1.x
                    val deltaY = e2.y - e1.y
                    if (abs(deltaY) > abs(deltaX) && abs(deltaY) > 10) {
                        isScrolling = true
                    } else {
                        return false
                    }
                }

                if (e1.x < playerView.width / 2) {
                    handleBrightness(distanceY / playerView.height)
                } else {
                    handleVolume(distanceY / playerView.height)
                }
                
                return true
            }
            
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 2026-07-11 : en mode VLC, le controller ne toggle pas via
                // PlayerView.onTouchEvent (le GestureDetector consomme les taps).
                // On le fait ici explicitement sur tap confirmé (pas double-tap).
                val cb = vlcTapCallback
                if (cb != null) {
                    cb.invoke()
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 2026-06-21 (user "sur mobile quand on double-clic à gauche
                //   ou à droite, ça active retour 10s / avant 10s") :
                //   Double-tap seek ±10s selon le côté de l'écran tapé.
                //   Tap zone centrale (~25%) = reset zoom (= comportement
                //   précédent préservé pour les users qui zoomaient).
                val width = playerView.width
                if (width <= 0) return false
                val xRatio = e.x / width
                val callback = onDoubleTapSeek
                return when {
                    xRatio < 0.40f && callback != null -> {
                        callback("left")
                        true
                    }
                    xRatio > 0.60f && callback != null -> {
                        callback("right")
                        true
                    }
                    else -> {
                        // Zone centrale → reset zoom (ancien comportement)
                        val videoView = playerView.videoSurfaceView ?: return false
                        videoView.scaleX = 1.0f
                        videoView.scaleY = 1.0f
                        true
                    }
                }
            }
        })

        playerView.setOnTouchListener { _, event ->
            // Abilitiamo le gesture solo se l'input proviene da un dispositivo di puntamento (Touch, Mouse, AirMouse)
            val isPointing = (event.source and InputDevice.SOURCE_CLASS_POINTER) != 0
            if (!isPointing) return@setOnTouchListener false

            // Controllo Zoom Manuale universale (sia per Mobile che per TV)
            val isManualZoom = when (playerView) {
                is PlayerMobileView -> playerView.isManualZoomEnabled
                is PlayerTvView -> playerView.isManualZoomEnabled
                else -> false
            }
            if (isManualZoom) return@setOnTouchListener false

            // 2026-06-12 — Verrou enfant : on SAUTE les detectors (pas de
            // swipe brightness/volume/seek, pas de double-tap, pas de
            // pinch-zoom) MAIS on laisse le tap simple propager vers
            // ExoPlayer pour qu'un tap affiche le controller (= seul le
            // btnExoUnlock devient visible car g_controls_lock est GONE).
            // Le btnExoUnlock reste alors cliquable et permet de déverrouiller.
            if (isLocked) return@setOnTouchListener false

            if (!UserPreferences.playerGestures) return@setOnTouchListener false

            scaleGestureDetector.onTouchEvent(event)
            
            if (!isScaling) {
                gestureDetector.onTouchEvent(event)
            }
            
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                isScrolling = false
                isScaling = false
                hideBars()
            }
            
            isScrolling || isScaling
        }
    }

    private fun handleBrightness(delta: Float) {
        hideJob?.cancel()
        brightnessLayout.visibility = View.VISIBLE
        volumeLayout.visibility = View.GONE

        val window = (context as? android.app.Activity)?.window ?: return
        val layoutParams = window.attributes
        
        var currentBrightness = layoutParams.screenBrightness
        if (currentBrightness < 0) {
            currentBrightness = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            } catch (e: Settings.SettingNotFoundException) {
                0.5f
            }
        }

        var newBrightness = currentBrightness + (delta / sensitivity)
        if (newBrightness < 0f) newBrightness = 0f
        if (newBrightness > 1f) newBrightness = 1f

        layoutParams.screenBrightness = newBrightness
        window.attributes = layoutParams
        
        val progress = (newBrightness * 100).toInt()
        brightnessBar.progress = progress
        brightnessText.text = "$progress%"
    }

    private fun handleVolume(delta: Float) {
        hideJob?.cancel()
        volumeLayout.visibility = View.VISIBLE
        brightnessLayout.visibility = View.GONE

        val volumeChange = (delta / sensitivity) * maxVolume
        currentVolumeFloat += volumeChange
        
        if (currentVolumeFloat < 0f) currentVolumeFloat = 0f
        if (currentVolumeFloat > maxVolume.toFloat()) currentVolumeFloat = maxVolume.toFloat()

        val newVolume = currentVolumeFloat.toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
        
        val progress = (currentVolumeFloat / maxVolume * 100).toInt()
        volumeBar.progress = progress
        volumeText.text = "$progress%"
    }

    private fun hideBars() {
        hideJob?.cancel()
        hideJob = CoroutineScope(Dispatchers.Main).launch {
            delay(1000)
            brightnessLayout.visibility = View.GONE
            volumeLayout.visibility = View.GONE
        }
    }
}
