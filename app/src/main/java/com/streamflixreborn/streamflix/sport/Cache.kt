package com.streamflixreborn.streamflix.sport

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cache TTL générique (brief § 3 : « cache 60 s obligatoire »). Deux appels à moins de [ttlMs]
 * d'écart = un seul chargement réseau ; des appels simultanés attendent le même chargement.
 * Un échec ne vide jamais une valeur déjà en cache : on la rend, périmée, plutôt que rien.
 */
class TtlCache<T : Any>(private val ttlMs: Long = 60_000L) {
    @Volatile private var valeur: T? = null
    @Volatile private var horodatage = 0L
    private val verrou = Mutex()

    /** Nombre de chargements réels (pour vérifier que le cache sert, cf. SportSelfTest). */
    @Volatile var chargements = 0
        private set

    fun fraiche(): T? = valeur?.takeIf { System.currentTimeMillis() - horodatage < ttlMs }

    /** Dernière valeur chargée, même périmée. */
    fun derniere(): T? = valeur

    suspend fun get(charger: suspend () -> T): T {
        fraiche()?.let { return it }
        return verrou.withLock {
            fraiche()?.let { return@withLock it }
            try {
                chargements++
                charger().also { valeur = it; horodatage = System.currentTimeMillis() }
            } catch (e: Exception) {
                valeur ?: throw e
            }
        }
    }
}
