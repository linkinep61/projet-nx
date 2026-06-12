package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Category
import kotlinx.coroutines.flow.Flow

/**
 * 2026-06-10 (user "chargement différé qui arrive au fur et à mesure") :
 *  interface opt-in pour les providers capables d'émettre leur home par
 *  étapes (= comme `ProgressiveServersProvider` qu'on a déjà pour les
 *  serveurs).
 *
 *  Le flow émet plusieurs `List<Category>` au fur et à mesure que le contenu
 *  est résolu (1ère émission = catégories vides juste avec les noms,
 *  émissions suivantes = updates incrémentales avec chaînes ajoutées).
 *
 *  HomeViewModel détecte cette interface et utilise `getHomeProgressive()`
 *  à la place de `getHome()` pour permettre l'affichage instantané.
 */
interface ProgressiveHomeProvider : Provider {
    fun getHomeProgressive(): Flow<List<Category>>
}
