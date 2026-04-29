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
                        topBar.visibility = View.VISIBLE
                        topBar.animate().alpha(1f).setDuration(200).start()
                    } else {
                        topBar.animate().alpha(0f).setDuration(200).withEndAction {
                            topBar.visibility = View.GONE
                        }.start()
                    }
                }
            )

            initPlayer(filePath)
        } catch (e: Exception) {
            android.util.Log.e("LocalPlayer", "onCreate failed", e)
            fallbackToSystemPlayer(filePath)
        }
    }

    private fun initPlayer(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            android.widget.Toast.makeText(this, "Fichier introuvable", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val uri = Uri.fromFile(file)

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

                // Hint MIME type based on extension for proper extractor selection
                // .ts files = HLS downloads (concatenated TS segments)
                // .mp4 files = direct downloads (real MP4 container)
                val mimeType = if (filePath.endsWith(".ts", ignoreCase = true)) "video/mp2t" else null
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
            val file = File(filePath)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.provider", file
            )
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
