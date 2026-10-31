package com.streamflixreborn.streamflix.utils

/**
 * 2026-10-31 (user : « l'affichage où c'est marqué 1Jour1Film, il faut le renommer en Firestorm ») :
 *   nom montré à l'ÉCRAN uniquement. Le nom interne (`provider.name`, clés de cache, favoris,
 *   historique, réglages) reste « 1Jour1Film » pour ne rien perdre chez les utilisateurs.
 */
object NomAffiche {
    private val renommages = listOf("1Jour1Film" to "Firestorm")

    fun de(nom: String?): String {
        if (nom.isNullOrEmpty()) return nom.orEmpty()
        var r: String = nom
        for ((avant, apres) in renommages) r = r.replace(avant, apres, ignoreCase = true)
        return r
    }

    /** Sources dont on ne montre pas les adresses dans les réglages (elles contiennent l'ancien nom).
     *  La mise à jour automatique de l'adresse continue de fonctionner. */
    fun adresseMasquee(provider: Any?): Boolean =
        (provider as? com.streamflixreborn.streamflix.providers.Provider)?.name == "1Jour1Film"
}
