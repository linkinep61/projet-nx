package com.streamflixreborn.streamflix.utils

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Signal-based trigger for home enrichment.
 *
 * Enrichment (20 network requests, 1000+ objects) is deferred until the player
 * has opened and the first channel has started loading.  PlayerTvFragment calls
 * [notifyPlayerReady] when player.prepare() is invoked; HomeViewModel waits on
 * [playerReadyFlow] before launching the enrichment coroutines.
 */
object EnrichmentTrigger {
    private val _channel = Channel<Unit>(Channel.CONFLATED)
    val playerReadyFlow: Flow<Unit> = _channel.receiveAsFlow()

    /**
     * Call from PlayerTvFragment once the player starts loading the first source.
     */
    fun notifyPlayerReady() {
        _channel.trySend(Unit)
    }
}
