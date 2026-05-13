package com.streamflixreborn.streamflix.models

import java.io.Serializable

/**
 * 2026-05-12 (user "profil multi-utilisateur") : profil utilisateur style Netflix.
 *
 * Stocké en JSON dans SharedPreferences (cf [com.streamflixreborn.streamflix.utils.ProfileStore]).
 * Pas en DB Room — la DB elle-même est segmentée par profil (filename
 * `{profileId}_{providerName}.db`) donc les favoris/historique sont nativement
 * isolés par profil sans schema migration.
 *
 * @property id identifiant stable utilisé pour le nom de fichier DB. "default"
 *   pour le profil créé automatiquement à la 1re migration (hérite des DBs
 *   existantes via renommage atomique).
 * @property name nom affiché à l'utilisateur (ex: "Guillaume", "Enfants").
 * @property emoji avatar — un emoji unicode (ex: "🎬", "👧", "🍿"). v1 : pas
 *   d'images custom, juste emoji pour rester léger.
 * @property isAdmin si true, ce profil peut créer/modifier/supprimer d'autres
 *   profils. Au moins 1 admin doit toujours exister.
 * @property pinHash hash SHA-256 du PIN (optionnel). null = profil ouvert.
 * @property maxAge âge max contenu autorisé (cf ParentalControlUtils). null =
 *   pas de restriction (sera utilisé en Phase 2).
 */
data class Profile(
    val id: String,
    val name: String,
    val emoji: String,
    val isAdmin: Boolean = false,
    val pinHash: String? = null,
    val maxAge: Int? = null,
) : Serializable {
    companion object {
        /** ID du profil créé automatiquement lors de la 1re ouverture après
         *  l'update multi-profil. Garanti d'exister tant que le user n'a pas
         *  supprimé manuellement tous les profils. */
        const val DEFAULT_ID = "default"
    }
}
