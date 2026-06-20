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

    /** 2026-06-19 : invoque le toggle (show/hide) du panel chaînes. Permet à
     *  MainTvActivity d'ouvrir/fermer le panel sur D-pad LEFT quand le player
     *  est en plein écran et que le controller est MASQUÉ. Si le controller
     *  est visible (= 5 boutons restart/refresh/pause/next/settings), LEFT
     *  doit naviguer dans les boutons normalement → retourne false.
     *  Retourne true si LEFT a été consommé (= panel s'est ouvert/fermé),
     *  false si LEFT doit suivre son chemin normal. */
    @Volatile var onLeftToggleRequested: (() -> Boolean)? = null

    /** 2026-06-13 : zone de focus actuelle (0=Retour, 1=Search, 2=Liste).
     *  Permet a MainTvActivity de NE PAS consommer toutes les touches quand
     *  l'EditText de recherche a le focus (= les lettres tapees doivent passer
     *  pour saisie). Pareil pour BACKSPACE / texte.
     *  Pour le bouton Retour et la Liste, on consomme tout normalement. */
    @Volatile var focusZone: Int = 2
}
