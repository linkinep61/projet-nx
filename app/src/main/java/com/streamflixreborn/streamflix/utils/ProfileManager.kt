package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Profile

/**
 * 2026-05-12 : façade pour le profil actif courant. C'est l'API que le reste
 * du code utilise pour savoir "qui regarde maintenant".
 *
 * **NE PAS** stocker l'objet Profile en cache mémoire — toujours relire depuis
 * [ProfileStore] pour que les modifs (renommage, emoji change) soient visibles
 * immédiatement.
 *
 * Quand le profil change ([setCurrentProfile]), on RESET la cache DB
 * AppDatabase : le filename DB dépend de profileId, donc le nouveau profil
 * va builder une autre instance Room pointant sur un autre fichier.
 */
object ProfileManager {
    private const val TAG = "ProfileManager"

    /** Lit le profil actif. Null si aucun profil sélectionné (1re ouverture
     *  ou switch en cours). Le caller doit afficher le ProfilePicker dans ce cas. */
    fun currentProfile(): Profile? = ProfileStore.getCurrentProfile()

    /** ID du profil actif, ou [Profile.DEFAULT_ID] si null — garantit qu'on a
     *  toujours une valeur safe pour les filenames DB. À utiliser quand on
     *  veut construire un nom de fichier sans branchement nullable. */
    fun currentProfileIdOrDefault(): String = ProfileStore.getCurrentProfileId() ?: Profile.DEFAULT_ID

    /** Switch vers un autre profil. Reset la DB cache pour que les prochaines
     *  queries pointent sur le bon fichier DB. */
    fun setCurrentProfile(profile: Profile) {
        val previousId = ProfileStore.getCurrentProfileId()
        if (previousId == profile.id) {
            Log.d(TAG, "setCurrentProfile: already on ${profile.name} (${profile.id}), no-op")
            return
        }
        Log.d(TAG, "setCurrentProfile: $previousId → ${profile.id} (${profile.name})")
        ProfileStore.setCurrentProfileId(profile.id)
        try {
            AppDatabase.resetInstance()
        } catch (e: Exception) {
            Log.w(TAG, "AppDatabase reset failed during profile switch: ${e.message}")
        }
    }

    /** Clear le profil actif (force le ProfilePicker au prochain launch).
     *  Appelé par le bouton "Changer de profil" depuis la home. */
    fun clearCurrentProfile() {
        Log.d(TAG, "clearCurrentProfile called")
        ProfileStore.setCurrentProfileId(null)
        try {
            AppDatabase.resetInstance()
        } catch (e: Exception) {
            Log.w(TAG, "AppDatabase reset failed during profile clear: ${e.message}")
        }
    }
}
