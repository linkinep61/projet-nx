package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import com.streamflixreborn.streamflix.BuildConfig

/**
 * 2026-05-05 : Système de contrôle parental basé sur un PIN à 4 chiffres pour
 * verrouiller des providers individuels.
 *
 * Flow utilisateur :
 *   1. Premier clic sur l'icône cadenas → setup d'un PIN à 4 chiffres
 *   2. Clics suivants → demande le PIN existant pour entrer dans la gestion
 *   3. Dans la gestion → toggle on/off pour chaque provider
 *   4. Provider verrouillé apparaît avec icône 🔒 dans la liste
 *   5. Clic sur provider verrouillé → demande le PIN pour le débloquer
 *      (déblocage temporaire pour cette session, OU permanent via la gestion)
 *
 * Stockage : SharedPreferences (file `${appId}.provider_lock`).
 *   - `pin` : String, hash SHA-256 du PIN
 *   - `locked` : Set<String>, noms des providers verrouillés
 *   - `unlocked_session` : Set<String>, providers débloqués pour la session
 *     courante (réinitialisé au démarrage de l'app)
 */
object ProviderLockStore {

    private const val PREFS_FILE = "provider_lock"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_LOCKED_PROVIDERS = "locked_providers"

    /** Providers débloqués pour la session courante. Map provider → timestamp
     *  du déblocage (ms epoch). En mémoire, pas persisté → reset à la
     *  fermeture totale de l'app (process killed). Un provider est considéré
     *  débloqué tant que `now - timestamp < SESSION_TIMEOUT_MS`. */
    private val sessionUnlocked = mutableMapOf<String, Long>()

    /** 2026-05-05 : timeout d'inactivité — un provider déverrouillé se
     *  re-verrouille automatiquement après 30 minutes. C'est un compromis
     *  entre sécurité (pas déverrouillé indéfiniment) et UX (on peut zapper
     *  entre épisodes sans re-saisir le PIN sans arrêt). */
    private const val SESSION_TIMEOUT_MS = 30L * 60L * 1000L  // 30 minutes

    private fun prefs(context: Context): SharedPreferences {
        val name = "${BuildConfig.APPLICATION_ID}.$PREFS_FILE"
        return context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    /** Hash SHA-256 du PIN. */
    private fun hashPin(pin: String): String {
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** True si un PIN a déjà été configuré. */
    fun hasPin(context: Context): Boolean = !prefs(context).getString(KEY_PIN_HASH, null).isNullOrBlank()

    /** Configure un nouveau PIN (premier setup uniquement). Retourne false si
     *  un PIN existe déjà — il faut alors passer par [changePin]. */
    fun setupPin(context: Context, pin: String): Boolean {
        if (hasPin(context)) return false
        require(pin.length in 4..8 && pin.all { it.isDigit() }) { "PIN doit être 4-8 chiffres" }
        prefs(context).edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
        return true
    }

    /** Change le PIN existant. Vérifie l'ancien d'abord. */
    fun changePin(context: Context, oldPin: String, newPin: String): Boolean {
        if (!verifyPin(context, oldPin)) return false
        require(newPin.length in 4..8 && newPin.all { it.isDigit() })
        prefs(context).edit().putString(KEY_PIN_HASH, hashPin(newPin)).apply()
        return true
    }

    /** Vérifie qu'un PIN saisi correspond à celui stocké. */
    fun verifyPin(context: Context, pin: String): Boolean {
        val stored = prefs(context).getString(KEY_PIN_HASH, null) ?: return false
        return stored == hashPin(pin)
    }

    /** Liste des providers verrouillés. */
    fun getLockedProviders(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_LOCKED_PROVIDERS, emptySet()) ?: emptySet()
    }

    /** Verrouille un provider. */
    fun lockProvider(context: Context, providerName: String) {
        val current = getLockedProviders(context).toMutableSet()
        current.add(providerName)
        prefs(context).edit().putStringSet(KEY_LOCKED_PROVIDERS, current).apply()
    }

    /** Déverrouille un provider de manière permanente (retire de la liste). */
    fun unlockProvider(context: Context, providerName: String) {
        val current = getLockedProviders(context).toMutableSet()
        current.remove(providerName)
        prefs(context).edit().putStringSet(KEY_LOCKED_PROVIDERS, current).apply()
        synchronized(sessionUnlocked) { sessionUnlocked.remove(providerName) }
    }

    /** Toggle le verrou d'un provider. Retourne le nouvel état (true = verrouillé). */
    fun toggleLock(context: Context, providerName: String): Boolean {
        val locked = isLocked(context, providerName)
        if (locked) unlockProvider(context, providerName)
        else lockProvider(context, providerName)
        return !locked
    }

    /** True si un provider est verrouillé (PERMANENT — ignore le déblocage de session). */
    fun isLocked(context: Context, providerName: String): Boolean {
        return getLockedProviders(context).contains(providerName)
    }

    /** True si un provider est ACCESSIBLE — soit pas verrouillé, soit débloqué
     *  pour la session courante ET dans la fenêtre de 30 minutes d'inactivité. */
    fun isAccessible(context: Context, providerName: String): Boolean {
        if (!isLocked(context, providerName)) return true
        val unlockedAt = synchronized(sessionUnlocked) {
            sessionUnlocked[providerName]
        } ?: return false
        val elapsed = System.currentTimeMillis() - unlockedAt
        if (elapsed > SESSION_TIMEOUT_MS) {
            // Timeout dépassé : retire le déblocage et redemande le PIN
            synchronized(sessionUnlocked) { sessionUnlocked.remove(providerName) }
            return false
        }
        return true
    }

    /** Débloque un provider pour la session courante. Le déblocage expire
     *  après 30 min d'inactivité (re-verrouillage auto) ou à la fermeture
     *  totale de l'app. */
    fun unlockForSession(providerName: String) {
        synchronized(sessionUnlocked) {
            sessionUnlocked[providerName] = System.currentTimeMillis()
        }
    }

    /** Renouvelle le timestamp d'un provider déjà déverrouillé (refresh
     *  d'inactivité). Appelable depuis le player ou les écrans qui
     *  consomment le contenu, pour ne pas re-locker pendant la lecture. */
    fun touchSessionUnlock(providerName: String) {
        synchronized(sessionUnlocked) {
            if (sessionUnlocked.containsKey(providerName)) {
                sessionUnlocked[providerName] = System.currentTimeMillis()
            }
        }
    }

    /** Réinitialise tous les déblocages de session (force re-PIN partout). */
    fun resetSessionUnlocks() {
        synchronized(sessionUnlocked) { sessionUnlocked.clear() }
    }

    /** Retourne le nombre de minutes restantes avant re-verrouillage auto pour
     *  un provider donné. -1 si pas verrouillé ou pas dans la session. */
    fun minutesUntilRelock(context: Context, providerName: String): Int {
        if (!isLocked(context, providerName)) return -1
        val unlockedAt = synchronized(sessionUnlocked) {
            sessionUnlocked[providerName]
        } ?: return -1
        val remaining = SESSION_TIMEOUT_MS - (System.currentTimeMillis() - unlockedAt)
        return if (remaining > 0) (remaining / 60_000).toInt() else 0
    }
}
