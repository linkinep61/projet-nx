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

    /** 2026-06-10 (user "sur mobile la home galère à s'afficher quand on
     *  change de source") : pattern "epoch" — compteur monotone incrémenté
     *  à chaque forceRelaunch. Chaque ViewModel garde sa propre dernière
     *  sequence vue et compare. AINSI tous les collectors peuvent détecter
     *  un forceRelaunch indépendamment, sans race condition (ce qui était
     *  le cas avec l'ancien flag Boolean qui se reset au 1er lecteur). */
    @Volatile var forceRelaunchSequence: Long = 0L
        private set

    /** Compat ancien code — vérifie si ma dernière sequence connue est
     *  derrière l'actuelle. Si oui → forceRelaunch détecté. */
    fun shouldForceRelaunch(myLastSeenSeq: Long): Boolean =
        forceRelaunchSequence > myLastSeenSeq

    /**
     * Notify all listeners that the provider has changed.
     * @param forceRelaunch true → incrémente forceRelaunchSequence (tous les
     *   ViewModels qui lisent shouldForceRelaunch() verront true jusqu'à ce
     *   qu'ils acknowledge la sequence).
     */
    fun notifyProviderChanged(forceRelaunch: Boolean = false) {
        if (forceRelaunch) forceRelaunchSequence++
        _flow.tryEmit(Unit)
        purgePreviousProviderArtwork()
        try { com.streamflixreborn.streamflix.providers.AnimeSamaProvider.resetState() } catch (_: Throwable) {}
    }

    /**
     * 2026-05-21 (user "qu'un seul provider garde ses jaquettes à la fois, les
     * autres vidés ; mais un simple retour-home sur le même provider ne doit pas
     * tout recharger") : purge les bitmaps en RAM (cache mémoire Glide) du
     * provider qu'on quitte, dès qu'on change RÉELLEMENT de provider ou de filtre.
     *
     * Cette fonction n'est appelée QUE sur un vrai changement (sélection d'un
     * autre provider, application d'un filtre) — JAMAIS sur un simple retour-home
     * vers le même provider. Donc revenir sur le même provider ne vide rien.
     *
     * clearMemory() ne touche PAS le cache DISQUE (150 Mo) : les jaquettes du
     * nouveau provider reviennent du disque (rapide, pas de re-téléchargement).
     * Les données du home (titres, listes) sont dans HomeCacheStore, indépendant.
     *
     * clearMemory() DOIT s'exécuter sur le main thread.
     */
    private fun purgePreviousProviderArtwork() {
        try {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    com.bumptech.glide.Glide.get(ctx).clearMemory()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }
}
