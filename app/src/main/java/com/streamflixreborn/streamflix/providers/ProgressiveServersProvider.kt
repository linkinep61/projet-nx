package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.flow.Flow

/**
 * Opt-in (2026-05-21, user "affiche ce qu'il récupère au fur et à mesure, les
 * autres arrivent ensuite sans attendre").
 *
 * Un provider qui implémente cette interface sait renvoyer ses serveurs
 * PROGRESSIVEMENT — source par source — au lieu d'attendre que tous les backups
 * aient répondu (`awaitAll`) avant de rendre la liste complète.
 *
 * Le PlayerViewModel détecte l'interface : il affiche le 1er lot dès qu'il arrive
 * (débloque l'écran de chargement), puis ajoute les lots suivants au fur et à
 * mesure, dans l'ORDRE D'ARRIVÉE (pas de re-tri global qui ferait sauter la liste
 * sous le curseur/la télécommande). Les providers qui n'implémentent PAS cette
 * interface gardent le chemin batch classique (`getServers`) — zéro changement.
 */
interface ProgressiveServersProvider {
    /**
     * Émet un lot de serveurs à chaque fois qu'une source/backup finit. Le flow
     * se termine quand toutes les sources ont répondu. Peut ne rien émettre
     * (aucune source trouvée) → le ViewModel bascule alors sur le fallback batch
     * puis l'erreur "aucune source".
     */
    fun getServersProgressive(id: String, videoType: Video.Type): Flow<List<Video.Server>>
}
