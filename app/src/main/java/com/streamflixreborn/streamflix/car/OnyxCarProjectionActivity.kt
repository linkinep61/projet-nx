package com.streamflixreborn.streamflix.car

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 2026-07-25 — ÉCRAN VIDÉO D'ONYX SUR ANDROID AUTO (recette validée en VRAIE VOITURE).
 *
 * Pourquoi ça marche (décompilation du validateur d'Android Auto + diff avec AABrowser) : une app
 * hors Play Store ne peut PAS se déclarer « projection »/« template » (refusé par le validateur),
 * mais les JEUX (apps natives autorisées à l'arrêt) sont projetés sur l'écran voiture. D'où
 * `android:appCategory="game"` sur <application> + cette activité déclarée
 * CAR_LAUNCHER/NAVIGATION/APP_MAPS avec `<layout gravity="fill">`, et AUCUN CarAppService.
 *
 * MIROIR : on ne relance aucune lecture. On donne notre surface au lecteur DÉJÀ en cours sur le
 * téléphone via CarPlaybackBridge (callbacks génériques : marche pour Media3 ET l'ExoPlayer 2.x des
 * chaînes live). Même flux, mêmes sous-titres, même position, sans re-résoudre la source.
 *
 * ⚠️ Usage passager / véhicule à l'arrêt.
 */
class OnyxCarProjectionActivity : Activity() {

    private var surfaceView: SurfaceView? = null
    private var hint: TextView? = null
    private var surfaceReady = false
    private var controlsBar: android.widget.LinearLayout? = null
    private var playPauseButton: android.widget.Button? = null
    private var zoom = 1f
    private val hideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideRunnable = Runnable { controlsBar?.visibility = View.GONE }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ⚠️ NE PAS ajouter de garde-fou « display == 0 → rediriger vers l'app téléphone » : testé
        // le 2026-07-25, Android Auto lance l'activité AVANT que le display voiture soit résolu →
        // elle se terminait aussitôt et l'écran voiture retombait sur l'accueil.
        Log.i(TAG, "onCreate — écran voiture ONYX")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val sv = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                attachMirror()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                attachMirror()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                runCatching { CarPlaybackBridge.setSurface?.invoke(null) }
                runCatching { CarPlaybackBridge.reattachPhoneSurface?.invoke() }
            }
        })
        root.addView(sv)

        val tv = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER }
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            text = "ONYX\n\nLancez une vidéo sur le téléphone :\nelle s'affichera ici."
        }
        root.addView(tv)

        // ── Contrôles voiture : Play/Pause + redimensionnement de l'image ──────────────────
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            setPadding(16, 8, 16, 16)
        }
        fun carButton(label: String, onClick: () -> Unit) = android.widget.Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 20f
            minWidth = 140
            setPadding(28, 12, 28, 12)
            // Toute action relance le compte à rebours (la barre ne s'efface pas sous les doigts).
            setOnClickListener { onClick(); showControls() }
        }
        playPauseButton = carButton("⏯") { togglePlayPause() }
        bar.addView(playPauseButton)
        bar.addView(carButton("➖") { changeZoom(-0.1f) })
        bar.addView(carButton("➕") { changeZoom(+0.1f) })
        bar.addView(carButton("⤢") { resetZoom() })
        root.addView(bar)
        controlsBar = bar

        // 2026-07-25 (user « la barre du lecteur ne se masque pas automatiquement ») :
        //   masquage auto après 4 s ; un appui n'importe où sur l'image la fait revenir.
        root.isClickable = true
        root.setOnClickListener { showControls() }
        sv.setOnClickListener { showControls() }
        showControls()

        setContentView(root)
        surfaceView = sv
        hint = tv

        // Se rebranche automatiquement quand une lecture démarre/change sur le téléphone.
        scope.launch {
            CarPlaybackBridge.version.collectLatest { attachMirror() }
        }
    }

    override fun onResume() {
        super.onResume()
        attachMirror()
    }

    /** Donne la surface de l'écran voiture au lecteur en cours sur le téléphone. */
    private fun attachMirror() {
        val holder = surfaceView?.holder ?: return
        if (!surfaceReady) return
        val setSurface = CarPlaybackBridge.setSurface
        if (setSurface == null) {
            hint?.visibility = View.VISIBLE
            Log.i(TAG, "aucun lecteur téléphone actif")
            return
        }
        runCatching {
            setSurface(holder.surface)
            hint?.visibility = View.GONE
            Log.i(TAG, "miroir OK : ${CarPlaybackBridge.title}")
        }.onFailure { Log.e(TAG, "attachMirror KO: ${it.message}", it) }
    }

    /** Affiche les contrôles et relance le masquage automatique. */
    private fun showControls() {
        controlsBar?.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, CONTROLS_TIMEOUT_MS)
    }

    /** Play/Pause du lecteur téléphone depuis l'écran voiture. */
    private fun togglePlayPause() {
        runCatching {
            val playing = CarPlaybackBridge.getIsPlaying?.invoke() ?: return
            CarPlaybackBridge.setPlaying?.invoke(!playing)
            playPauseButton?.text = if (!playing) "⏸" else "▶"
        }.onFailure { Log.w(TAG, "play/pause KO: ${it.message}") }
    }

    /** Redimensionne l'image projetée (utile quand l'écran voiture rogne ou laisse des bandes). */
    private fun changeZoom(delta: Float) {
        zoom = (zoom + delta).coerceIn(0.5f, 2f)
        applyZoom()
    }

    private fun resetZoom() {
        zoom = 1f
        applyZoom()
    }

    private fun applyZoom() {
        surfaceView?.apply {
            scaleX = zoom
            scaleY = zoom
        }
        Log.i(TAG, "zoom = $zoom")
    }

    override fun onDestroy() {
        scope.cancel()
        // Rend l'image au téléphone (un lecteur n'alimente qu'une surface à la fois).
        runCatching { CarPlaybackBridge.setSurface?.invoke(null) }
        runCatching { CarPlaybackBridge.reattachPhoneSurface?.invoke() }
        surfaceView = null
        hint = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OnyxCarProjection"
        private const val CONTROLS_TIMEOUT_MS = 4000L
    }
}
