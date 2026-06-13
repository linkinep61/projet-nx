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

    /** 2026-06-13 : zone de focus actuelle (0=Retour, 1=Search, 2=Liste).
     *  Permet a MainTvActivity de NE PAS consommer toutes les touches quand
     *  l'EditText de recherche a le focus (= les lettres tapees doivent passer
     *  pour saisie). Pareil pour BACKSPACE / texte.
     *  Pour le bouton Retour et la Liste, on consomme tout normalement. */
    @Volatile var focusZone: Int = 2
}
