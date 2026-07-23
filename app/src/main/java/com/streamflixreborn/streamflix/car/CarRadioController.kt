package com.streamflixreborn.streamflix.car

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * 2026-07-23 (projet Android Auto, LAB) — CONTRÔLEUR RADIO persistant.
 *
 * La lecture radio est découplée des écrans Android Auto : un seul ExoPlayer vit ici (singleton),
 * donc revenir à la liste / changer de station NE COUPE PAS le son (demande user). Stop explicite
 * uniquement.
 */
@androidx.media3.common.util.UnstableApi
object CarRadioController {

    private var player: ExoPlayer? = null

    @Volatile
    var currentName: String? = null
        private set

    fun play(context: Context, name: String, url: String, fallbacks: List<String>) {
        val urls = (listOf(url) + fallbacks).filter { it.isNotBlank() }
        if (urls.isEmpty()) return
        val p = player ?: ExoPlayer.Builder(context.applicationContext).build().also {
            it.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            player = it
        }
        p.setMediaItems(urls.map { MediaItem.fromUri(it) })
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.prepare()
        p.playWhenReady = true
        currentName = name
        Log.i(TAG, "play: $name")
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
        currentName = null
        Log.i(TAG, "stop")
    }

    private const val TAG = "CarRadioCtrl"
}
