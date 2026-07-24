package com.streamflixreborn.streamflix.car

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamflixreborn.streamflix.providers.NewPipeAudio

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

    private fun ensurePlayer(context: Context): ExoPlayer =
        player ?: ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(youtubeAwareDataSource(context)))
            .build().also {
            it.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            // 2026-07-24 : playlist musique → le titre affiché (« ▶ en lecture ») suit la piste.
            it.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val t = mediaItem?.mediaMetadata?.title?.toString()
                    if (!t.isNullOrBlank()) currentName = t
                }
            })
            player = it
        }

    /**
     * 2026-07-24 : les URL musique peuvent être des liens YouTube (NewPipe) NON jouables tels quels.
     * On les résout PARESSEUSEMENT à l'ouverture de chaque piste (comme le mini-lecteur téléphone) →
     * l'enchaînement/boucle/aléatoire natif marche, chaque piste résolue à sa lecture. Les URL
     * directes (mp3 FileSearch, flux radio) passent inchangées.
     */
    private fun youtubeAwareDataSource(context: Context): ResolvingDataSource.Factory {
        val base = DefaultDataSource.Factory(context.applicationContext)
        return ResolvingDataSource.Factory(base) { dataSpec ->
            val u = dataSpec.uri.toString()
            if (NewPipeAudio.isYouTubeUrl(u)) {
                val real = runCatching { NewPipeAudio.resolveAudioUrl(u) }.getOrNull()
                if (!real.isNullOrBlank()) dataSpec.withUri(android.net.Uri.parse(real)) else dataSpec
            } else {
                dataSpec
            }
        }
    }

    fun play(context: Context, name: String, url: String, fallbacks: List<String>) {
        val urls = (listOf(url) + fallbacks).filter { it.isNotBlank() }
        if (urls.isEmpty()) return
        val p = ensurePlayer(context)
        p.shuffleModeEnabled = false
        p.setMediaItems(urls.map { MediaItem.fromUri(it) })
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.prepare()
        p.playWhenReady = true
        currentName = name
        Log.i(TAG, "play: $name")
    }

    /**
     * 2026-07-24 (user « les playlists musique dans Android Auto ») : lecture d'une PLAYLIST
     *   musique (FileSearch favoris) — enchaînement + boucle + aléatoire optionnel. tracks =
     *   (url directe, titre). Comme sur le téléphone : ne s'arrête pas à la fin, le titre suit.
     */
    fun playPlaylist(context: Context, tracks: List<Pair<String, String>>, startIndex: Int, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        val start = startIndex.coerceIn(0, tracks.size - 1)
        val p = ensurePlayer(context)
        val items = tracks.map { (url, title) ->
            MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        }
        p.shuffleModeEnabled = shuffle
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.setMediaItems(items, start, 0L)
        p.prepare()
        p.playWhenReady = true
        currentName = tracks[start].second
        Log.i(TAG, "playPlaylist: ${tracks.size} pistes start=$start shuffle=$shuffle")
    }

    fun pause() { runCatching { player?.playWhenReady = false } }
    fun resume() { runCatching { player?.playWhenReady = true } }
    fun isPlaying(): Boolean = runCatching { player?.playWhenReady == true }.getOrDefault(false)
    fun skipNext() { runCatching { player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } } }
    fun skipPrevious() { runCatching { player?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() } } }

    fun stop() {
        runCatching { player?.release() }
        player = null
        currentName = null
        Log.i(TAG, "stop")
    }

    private const val TAG = "CarRadioCtrl"
}
