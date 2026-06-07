package com.streamflixreborn.streamflix.utils

/**
 * 2026-06-04 (user "à l'ouverture de ce machin le focus doit être entièrement
 *   dessus quoi qu'il arrive" + "ça va sur focus settings au lieu de Lire
 *   maintenant") :
 *
 * État global de l'overlay « À SUIVRE » (épisode suivant). Accessible depuis
 * MainTvActivity pour intercepter OK/BACK AVANT que le PlayerView ou le
 * controller (exoSettings notamment) ne consomment l'event.
 *
 * Même pattern que ChannelListState — pas de référence View, simple callbacks
 * statiques posés/dépossés par PlayerTvFragment dans show/hideNextEpisodeOverlay.
 */
object NextEpisodeOverlayState {
    @Volatile var isVisible = false
    @Volatile var onConfirm: (() -> Unit)? = null
    @Volatile var onDismiss: (() -> Unit)? = null
}
