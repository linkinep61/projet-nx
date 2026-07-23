package com.streamflixreborn.streamflix.car

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 2026-07-23 (projet Android Auto) — PONT DE LECTURE téléphone → écran voiture.
 *
 * Le service Android Auto tourne dans le MÊME process que les lecteurs d'ONYX. Un lecteur (peu importe
 * son type : Media3 pour films/séries/Vavoo, ExoPlayer 2.19.1 pour les chaînes OTF live…) se publie ici
 * via des CALLBACKS génériques (attacher la surface, lire/mettre en pause). L'écran voiture s'y branche
 * → la MÊME lecture s'affiche sur l'écran de la voiture (miroir), quel que soit le lecteur.
 *
 * ⚠ Un lecteur ne rend que sur UNE surface : pendant la projection voiture, la zone vidéo du téléphone
 * se vide (son/contrôles OK). Au détachement, `reattachPhoneSurface` rebranche la vue du téléphone.
 */
object CarPlaybackBridge {

    /** Attache/détache la surface de rendu sur le lecteur courant (null = détacher). */
    @Volatile
    var setSurface: ((Surface?) -> Unit)? = null
        private set

    /** Lit l'état lecture (true = en lecture). */
    @Volatile
    var getIsPlaying: (() -> Boolean)? = null
        private set

    /** Met en lecture / pause. */
    @Volatile
    var setPlaying: ((Boolean) -> Unit)? = null
        private set

    @Volatile
    var title: String? = null
        private set

    /** Rebranche la vue du téléphone quand la voiture rend la surface. */
    @Volatile
    var reattachPhoneSurface: (() -> Unit)? = null

    @Volatile
    private var token: Any? = null

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    val hasPlayer: Boolean get() = setSurface != null

    /**
     * @param token identité du lecteur (pour que `detach` ne détache que SON lecteur).
     */
    fun attach(
        token: Any,
        title: String?,
        setSurface: (Surface?) -> Unit,
        getIsPlaying: () -> Boolean,
        setPlaying: (Boolean) -> Unit,
        reattach: (() -> Unit)?,
    ) {
        this.token = token
        this.title = title
        this.setSurface = setSurface
        this.getIsPlaying = getIsPlaying
        this.setPlaying = setPlaying
        this.reattachPhoneSurface = reattach
        _version.value = _version.value + 1
    }

    fun detach(token: Any) {
        if (this.token === token) {
            this.token = null
            this.title = null
            this.setSurface = null
            this.getIsPlaying = null
            this.setPlaying = null
            this.reattachPhoneSurface = null
            _version.value = _version.value + 1
        }
    }
}
