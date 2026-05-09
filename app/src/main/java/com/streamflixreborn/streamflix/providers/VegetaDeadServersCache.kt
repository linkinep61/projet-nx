package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.content.SharedPreferences
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * Cache persistant des serveurs IPTV morts (m3u empty, no token, HTML error,
 * proxies fail, etc.). Partagé entre Vegeta et OlaTV.
 *
 * 2026-05-08 : ajouté après plainte user "j'ai pas un nouveau server qui se charge
 * quand je bannis". Diagnostic logs : le backfill scanne agressivement, mais 80%+
 * retournent des erreurs. On gaspille 8-10s par scan inutile.
 *
 * Format SharedPrefs : `<providerKey>::<serverId>` → timestamp d'échec (ms).
 *   - providerKey : "vegeta" | "ola"
 *   - serverId : index numérique pour Vegeta, baseUrl/cid pour Ola
 *
 * TTL 24h : passé ce délai, on retente (le serveur peut revenir).
 *
 * Note : le pool Vegeta a 71 positions ; OlaTV a une centaine de cids. Beaucoup
 * sont morts en permanence — ce cache évite de les ré-essayer.
 */
object IptvDeadServersCache {

    private const val PREFS_NAME = "iptv_dead_servers"
    private const val TTL_MS = 24L * 60 * 60 * 1000  // 24h

    private val prefs: SharedPreferences by lazy {
        StreamFlixApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun makeKey(providerKey: String, serverId: String): String =
        "$providerKey::$serverId"

    /** True si le serveur a été marqué mort dans les dernières 24h. */
    fun isDead(providerKey: String, serverId: String): Boolean {
        val key = makeKey(providerKey, serverId)
        val ts = prefs.getLong(key, 0L)
        if (ts == 0L) return false
        val age = System.currentTimeMillis() - ts
        if (age > TTL_MS) {
            // Expired → cleanup et retente
            prefs.edit().remove(key).apply()
            return false
        }
        return true
    }

    /** Marque un serveur comme mort (m3u fetch failed, no token, etc.). */
    fun markDead(providerKey: String, serverId: String) {
        prefs.edit().putLong(makeKey(providerKey, serverId), System.currentTimeMillis()).apply()
    }

    /** Retire la marque mort (au cas où serveur ressuscite via probe). */
    fun unmarkDead(providerKey: String, serverId: String) {
        prefs.edit().remove(makeKey(providerKey, serverId)).apply()
    }

    // Convenience overloads avec serverIdx Int (Vegeta usage typique)
    fun isDead(providerKey: String, serverIdx: Int): Boolean =
        isDead(providerKey, serverIdx.toString())
    fun markDead(providerKey: String, serverIdx: Int) =
        markDead(providerKey, serverIdx.toString())
    fun unmarkDead(providerKey: String, serverIdx: Int) =
        unmarkDead(providerKey, serverIdx.toString())

    /** Stats pour debug : combien de serveurs morts pour un provider. */
    fun deadCount(providerKey: String): Int {
        val now = System.currentTimeMillis()
        val pfx = "$providerKey::"
        return prefs.all.count { (key, value) ->
            key.startsWith(pfx) && value is Long && (now - value) <= TTL_MS
        }
    }
}
