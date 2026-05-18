package com.streamflixreborn.streamflix.utils

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Utility class to notify all ViewModels/fragments when the current provider
 * changes (or when a category filter is applied).
 *
 * 2026-05-14 (user "il faut cliquer plusieurs fois pour que la catégorie
 * change") : passé de Channel (single-consumer) à MutableSharedFlow
 * (multi-consumer). Le Channel précédent ne livrait l'event qu'à UN SEUL
 * collector parmi les 6 qui souscrivaient (HomeViewModel, HomeMobile/TvFragment,
 * MoviesViewModel, TvShowsViewModel, GenreViewModel). Roulette russe → seuls
 * certains écrans se rafraîchissaient au click. Avec SharedFlow, TOUS reçoivent
 * la même event simultanément.
 *
 * replay=0 : un nouveau collector qui s'abonne après l'emit ne reçoit pas
 * d'event passé (pas de fausse refresh au resume d'un fragment).
 *
 * extraBufferCapacity=1 + DROP_OLDEST : si emit appelé pendant que les
 * collectors traitent encore l'event précédent, on garde uniquement le plus
 * récent (équivalent CONFLATED). Évite la file de notif qui s'accumulerait
 * si l'user clique rapidement.
 */
object ProviderChangeNotifier {
    private val _flow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val providerChangeFlow: Flow<Unit> = _flow.asSharedFlow()

    /**
     * Notify all listeners that the provider has changed.
     * Non-suspending — utilise tryEmit qui retourne true si l'emit a été
     * accepté (toujours true ici grâce au buffer + DROP_OLDEST).
     */
    fun notifyProviderChanged() {
        _flow.tryEmit(Unit)
    }
}
