package com.streamflixreborn.streamflix.activities

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.streamflixreborn.streamflix.car.CarPlaybackBridge

/**
 * 2026-07-25 (demande user : « récupérer les vidéos enregistrées sur le téléphone ») —
 * lecteur pour les VIDÉOS LOCALES (MediaStore).
 *
 * Il publie sa lecture dans CarPlaybackBridge exactement comme le lecteur principal : la vidéo
 * locale se projette donc aussi sur l'écran de la voiture (mode vidéo Android Auto).
 */
@androidx.media3.common.util.UnstableApi
class LocalVideoPlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lecture d'UNE vidéo, ou d'une PLAYLIST (favoris / dossier) avec enchaînement + aléatoire.
        val uris = intent?.getStringArrayExtra(EXTRA_URIS)
        val titles = intent?.getStringArrayExtra(EXTRA_TITLES)
        val startIndex = intent?.getIntExtra(EXTRA_START_INDEX, 0) ?: 0
        val shuffle = intent?.getBooleanExtra(EXTRA_SHUFFLE, false) ?: false
        val uri = intent?.getStringExtra(EXTRA_URI)
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Vidéo"
        if (uris.isNullOrEmpty() && uri.isNullOrBlank()) { finish(); return }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val pv = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            useController = true
        }
        root.addView(pv)
        setContentView(root)
        playerView = pv

        // 2026-07-29 (user : « vidéo locale AV1 = son sans image ») : beaucoup d'appareils n'ont pas
        //   de décodeur AV1 MATÉRIEL → ExoPlayer joue l'audio mais pas la vidéo. On construit le
        //   lecteur avec NextRenderersFactory (codecs logiciels FFmpeg + dav1d pour l'AV1), en mode
        //   EXTENSION_RENDERER_MODE_ON = matériel d'abord, décodeur logiciel en repli seulement quand
        //   le HW ne gère pas (AV1…). Idem lecteurs streaming principaux.
        val renderersFactory = io.github.anilbeesetti.nextlib.media3ext.ffdecoder
            .NextRenderersFactory(this).apply {
                setExtensionRendererMode(
                    androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                )
                setEnableDecoderFallback(true)
            }
        val p = ExoPlayer.Builder(this, renderersFactory).build()
        pv.player = p
        if (!uris.isNullOrEmpty()) {
            val items = uris.mapIndexed { i, u ->
                MediaItem.Builder()
                    .setUri(Uri.parse(u))
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(titles?.getOrNull(i) ?: "Vidéo").build(),
                    )
                    .build()
            }
            p.shuffleModeEnabled = shuffle
            p.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            p.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), 0L)
        } else {
            p.setMediaItem(MediaItem.fromUri(Uri.parse(uri!!)))
        }
        p.prepare()
        p.playWhenReady = true
        player = p

        // Pont Android Auto : la vidéo locale se projette comme n'importe quelle lecture ONYX.
        CarPlaybackBridge.attach(
            token = p,
            title = title,
            setSurface = { s -> runCatching { p.setVideoSurface(s) } },
            getIsPlaying = { runCatching { p.playWhenReady }.getOrDefault(false) },
            setPlaying = { pw -> runCatching { p.playWhenReady = pw } },
            reattach = { runCatching { pv.player = null; pv.player = p } },
        )
        Log.i(TAG, "lecture locale : $title")
    }

    override fun onDestroy() {
        player?.let { p -> runCatching { CarPlaybackBridge.detach(p) } }
        runCatching { playerView?.player = null }
        runCatching { player?.release() }
        player = null
        playerView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LocalVideoPlayer"
        const val EXTRA_URI = "extra_local_uri"
        const val EXTRA_TITLE = "extra_local_title"
        // Playlist (favoris / dossier) : enchaînement + aléatoire, en boucle.
        const val EXTRA_URIS = "extra_local_uris"
        const val EXTRA_TITLES = "extra_local_titles"
        const val EXTRA_START_INDEX = "extra_local_start"
        const val EXTRA_SHUFFLE = "extra_local_shuffle"
    }
}
