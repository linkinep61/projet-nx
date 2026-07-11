package com.streamflixreborn.streamflix.fragments.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 2026-07-09 (user « duplique notre interface et branche-la sur ces lecteurs ») — lecteur MIROIR.
 *
 * Certains lecteurs (SeekStreaming/seekplayer, Abyss, Player4me) ne peuvent jouer que DANS une
 * WebView (flux P2P/AES/CDN masqué). Pour que l'utilisateur garde NOTRE interface native (le même
 * controller ExoPlayer : play/pause, seek, ±10s… navigable à la télécommande sur TV), on branche
 * ce faux lecteur sur la `PlayerView` à la place d'ExoPlayer. Il :
 *   - REÇOIT les commandes du controller (play/pause, seek) et les FORWARDE à la <video> de la
 *     WebView (via les lambdas onPlayPause / onSeekTo qui exécutent du JS) ;
 *   - EXPOSE la position/durée/état lus dans la WebView (mis à jour par un poll → [update]).
 * Le controller natif « croit » piloter un vrai lecteur → tous les boutons marchent, mobile ET TV.
 */
class WebPlayerMirror(
    looper: Looper,
    private val onPlayPause: (play: Boolean) -> Unit,
    private val onSeekTo: (positionMs: Long) -> Unit,
) : SimpleBasePlayer(looper) {

    @Volatile private var positionMs: Long = 0L
    @Volatile private var durationMs: Long = C.TIME_UNSET
    @Volatile private var playing: Boolean = false

    /** Appelé par le poll de la WebView pour refléter l'état réel de la <video>. */
    fun update(posMs: Long, durMs: Long, isPlaying: Boolean) {
        positionMs = posMs
        durationMs = if (durMs > 0) durMs else C.TIME_UNSET
        playing = isPlaying
        invalidateState()
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
            )
            .build()
        val item = MediaItemData.Builder("web")
            .setMediaItem(MediaItem.Builder().setMediaId("web").build())
            .setDurationUs(if (durationMs != C.TIME_UNSET) durationMs * 1000 else C.TIME_UNSET)
            .setIsSeekable(true)
            .build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(positionMs)
            .setPlaylist(ImmutableList.of(item))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        onPlayPause(playWhenReady)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        this.positionMs = if (positionMs == C.TIME_UNSET) 0L else positionMs
        onSeekTo(this.positionMs)
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}
