package com.streamflixreborn.streamflix.utils

/**
 * 2026-05-31 : État global de la liste des chaînes IPTV.
 * Accessible depuis MainTvActivity et PlayerTvFragment sans passer par des Views.
 */
object ChannelListState {
    @Volatile var isOpen = false
    @Volatile var onOkPressed: (() -> Unit)? = null
    @Volatile var onUpPressed: (() -> Unit)? = null
    @Volatile var onDownPressed: (() -> Unit)? = null
    @Volatile var onCloseRequested: (() -> Unit)? = null
}
