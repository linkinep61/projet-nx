package com.streamflixreborn.streamflix.activities.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.streamflixreborn.streamflix.R
import java.io.File

/**
 * Lightweight Activity for playing downloaded (local) video files
 * using the in-app ExoPlayer. Supports fullscreen, landscape,
 * and auto-hide controls with seek bar + rewind/forward buttons.
 */
@androidx.media3.common.util.UnstableApi
class LocalPlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_FILE_PATH = "file_path"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val SEEK_INCREMENT_MS = 10_000L

        fun start(context: Context, filePath: String, title: String, subtitle: String? = null) {
            val intent = Intent(context, LocalPlayerActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
            }
            context.startActivity(intent)
        }
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout

    /** 2026-06-12 — Verrou enfant : True quand l'écran est en mode lock.
     *  Lu par onBackPressed() pour ignorer le back tant que pas déverrouillé. */
    private var isScreenLocked: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (filePath.isNullOrBlank()) {
            finish()
            return
        }

        try {
            // Fullscreen immersive
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            setContentView(R.layout.activity_local_player)

            playerView = findViewById(R.id.player_view)
            topBar = findViewById(R.id.layout_top_bar)
            val tvTitle = findViewById<TextView>(R.id.tv_title)
            val tvSubtitle = findViewById<TextView>(R.id.tv_subtitle)
            val btnBack = findViewById<ImageView>(R.id.btn_back)

            // Set title
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Vidéo"
            val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
            tvTitle.text = title
            if (!subtitle.isNullOrBlank()) {
                tvSubtitle.text = subtitle
                tvSubtitle.visibility = View.VISIBLE
            }

            btnBack.setOnClickListener { finish() }

            // Hide system bars
            hideSystemBars()

            // Sync top bar visibility with ExoPlayer controller
            playerView.setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    if (visibility == View.VISIBLE) {
                        // 2026-06-12 — Lecteur download : si écran verrouillé,
                        // on cache le topBar (titre/back) aussi → seul le
                        // btnExoUnlock reste visible (déjà géré côté layout).
                        if (isScreenLocked) {
                            topBar.visibility = View.GONE
                        } else {
                            topBar.visibility = View.VISIBLE
                            topBar.animate().alpha(1f).setDuration(200).start()
                        }
                    } else {
                        topBar.animate().alpha(0f).setDuration(200).withEndAction {
                            topBar.visibility = View.GONE
                        }.start()
                    }
                }
            )

            // 2026-06-12 (user "le lecteur des videos téléchargées doit aussi
            //   pouvoir etre verrouillé pour les enfants") : Verrou enfant
            //   sur le LocalPlayerActivity. Câble les mêmes boutons
            //   btn_exo_lock / btn_exo_unlock que le PlayerMobileFragment,
            //   sans gestureHelper (le LocalPlayer n'a pas de swipe gestures).
            setupScreenLock()

            initPlayer(filePath)
        } catch (e: Exception) {
            android.util.Log.e("LocalPlayer", "onCreate failed", e)
            android.widget.Toast.makeText(this, "Erreur de lecture vidéo", android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * Resolve the input string into a playable Uri.
     * Accepts: content:// URIs (MediaStore), file:// URIs, or plain absolute file paths.
     * Returns null if the resolved file doesn't exist (only checked for plain paths).
     */
    private fun resolveUri(input: String): Uri? {
        return when {
            input.startsWith("content://") || input.startsWith("file://") -> Uri.parse(input)
            else -> {
                val file = File(input)
                if (!file.exists()) null else Uri.fromFile(file)
            }
        }
    }

    private fun initPlayer(filePath: String) {
        val uri = resolveUri(filePath)
        if (uri == null) {
            android.widget.Toast.makeText(this, "Fichier introuvable", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Use TS-aware extractors for .ts files and HLS-downloaded .mp4 files
        // This enables seeking in concatenated TS segment files
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorTimestampSearchBytes(3 * 1500 * 188) // wider search for better seek accuracy

        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer

                // Hint MIME type based on filename for proper extractor selection.
                // .ts = HLS downloads (concatenated TS segments), .mp4 = direct downloads.
                // We sniff the original input string since content:// URIs don't carry
                // the extension in their path.
                val lowerPath = filePath.lowercase()
                val mimeType = when {
                    lowerPath.endsWith(".ts") || lowerPath.contains(".ts?") -> "video/mp2t"
                    lowerPath.endsWith(".mp4") || lowerPath.contains(".mp4?") -> "video/mp4"
                    else -> null
                }
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .apply { if (mimeType != null) setMimeType(mimeType) }
                    .build()

                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.playWhenReady = true
                exoPlayer.prepare()

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            playerView.showController()
                        }
                    }
                })
            }
    }

    private fun fallbackToSystemPlayer(filePath: String) {
        try {
            val uri: Uri = if (filePath.startsWith("content://")) {
                Uri.parse(filePath)
            } else {
                val file = File(filePath)
                androidx.core.content.FileProvider.getUriForFile(
                    this, "${packageName}.provider", file
                )
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Impossible de lire la vidéo", android.widget.Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    /** 2026-06-12 (user "crée une page transparente qui verrouille
     *  carrément le truc") : verrou via overlay plein écran.
     *  - Tap sur le cadenas du controller → layout_screen_lock VISIBLE
     *    (consomme tous les taps) + cadenas flash 3s puis fade out
     *  - Tap sur btn_screen_unlock → overlay GONE → tout revient
     *  - Tap n'importe où sur l'overlay → re-flash le cadenas 3s
     */
    private var unlockFlashRunnable: Runnable? = null

    private fun setupScreenLock() {
        val btnLock = playerView.findViewById<android.widget.ImageButton>(
            com.streamflixreborn.streamflix.R.id.btn_exo_lock,
        )
        val overlay = findViewById<android.widget.FrameLayout>(
            com.streamflixreborn.streamflix.R.id.layout_screen_lock,
        )
        val btnUnlock = findViewById<android.widget.ImageView>(
            com.streamflixreborn.streamflix.R.id.btn_screen_unlock,
        )
        if (btnLock == null || overlay == null || btnUnlock == null) return

        btnLock.setOnClickListener {
            isScreenLocked = true
            playerView.hideController()
            topBar.visibility = View.GONE
            overlay.visibility = View.VISIBLE
            flashUnlockButton(overlay, btnUnlock)
            android.widget.Toast.makeText(
                this,
                "Écran verrouillé — tape le cadenas pour déverrouiller",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }

        overlay.setOnClickListener {
            flashUnlockButton(overlay, btnUnlock)
        }

        btnUnlock.setOnClickListener {
            isScreenLocked = false
            overlay.visibility = View.GONE
            btnUnlock.visibility = View.VISIBLE
            topBar.visibility = View.VISIBLE
            unlockFlashRunnable?.let { playerView.removeCallbacks(it) }
        }
    }

    /** Affiche le cadenas 3 secondes puis le cache (= "lampe torche"). */
    private fun flashUnlockButton(overlay: View, btnUnlock: View) {
        unlockFlashRunnable?.let { playerView.removeCallbacks(it) }
        btnUnlock.alpha = 1f
        btnUnlock.visibility = View.VISIBLE
        unlockFlashRunnable = Runnable {
            btnUnlock.animate().alpha(0f).setDuration(400).withEndAction {
                btnUnlock.visibility = View.INVISIBLE
            }.start()
        }.also { playerView.postDelayed(it, 3_000) }
    }

    /** 2026-06-12 — Bloque le back hardware tant que l'écran est locked. */
    @Suppress("MissingSuperCall", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (isScreenLocked) {
            android.widget.Toast.makeText(
                this,
                "Écran verrouillé — appuie sur le cadenas pour déverrouiller",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
