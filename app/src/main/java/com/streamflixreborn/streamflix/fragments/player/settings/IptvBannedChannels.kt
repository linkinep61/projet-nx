package com.streamflixreborn.streamflix.fragments.player.settings

import android.content.Context
import android.content.SharedPreferences
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * Persistance des CHAÎNES IPTV bannies (pas juste des servers).
 *
 * 2026-05-08 : ajouté pour répondre à la demande user "pouvoir ban les chaînes
 * serait bien, ça éviterait des chargements". Permet de cacher complètement
 * une chaîne du catalog (home, listings) sur tous les providers IPTV.
 *
 * Format : SharedPrefs `iptv_banned_channels::<channelKey>` → timestamp ban.
 *
 * Le channelKey est NORMALISÉ (strip prefixes provider) pour que bannir
 * "Arte" sur Vegeta cache aussi Arte sur Ola/WiTV/TVHub. Cohérent avec
 * IptvFavorites/IptvBannedServers.
 *
 * Pas de TTL : un ban de chaîne reste jusqu'à unban explicite par l'user.
 */
object IptvBannedChannels {

    private const val PREFS_NAME = "iptv_banned_channels"
    private const val KEY_PREFIX = "iptv_banchan::"

    private val prefs: SharedPreferences by lazy {
        StreamFlixApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Normalise un channelKey IPTV (strip prefixes provider). Cohérent
     *  avec IptvFavorites/IptvBannedServers.
     *  2026-06-08 (user "active le ban pour tout le TV hub") : changement
     *  substringBefore("::") → substringAfterLast("::"). Les IDs Hub
     *  multi-segments (livehub::francetv::bxt::canal::cstar,
     *  livehub::dric4rtv::ligue1::ligue_1, livehub::otf::canalplus) ont
     *  le NOM DE CHAÎNE en DERNIER segment, pas en premier. Avec
     *  substringBefore, bannir "CStar" bannissait TOUT France TV.
     *  Avec substringAfterLast → ban scoped à la vraie chaîne. */
    private fun normalize(channelKey: String): String {
        if (channelKey.isBlank()) return channelKey
        return channelKey
            .removePrefix("vegeta_ep::")
            .removePrefix("vegeta::")
            .removePrefix("ola_ep::")
            .removePrefix("ola::")
            .removePrefix("livehub::")
            .removePrefix("sportlive::")
            .removePrefix("movixlivetv::")
            .removePrefix("sport::")
            .removePrefix("ch::")
            .substringAfterLast("::")  // = nom chaîne (dernier segment)
            .lowercase()
            .trim()
    }

    /** True si la chaîne est bannie (à cacher du catalog). */
    fun isBanned(channelKey: String): Boolean {
        val key = normalize(channelKey)
        if (key.isBlank()) return false
        return prefs.contains(KEY_PREFIX + key)
    }

    /** Marque la chaîne bannie. */
    fun ban(channelKey: String) {
        val key = normalize(channelKey)
        if (key.isBlank()) return
        prefs.edit().putLong(KEY_PREFIX + key, System.currentTimeMillis()).apply()
    }

    /** Toggle ban : si bannie → unban, sinon ban. Returns true si bannie après toggle. */
    fun toggle(channelKey: String): Boolean {
        return if (isBanned(channelKey)) {
            unban(channelKey)
            false
        } else {
            ban(channelKey)
            true
        }
    }

    /** Retire le ban. */
    fun unban(channelKey: String) {
        val key = normalize(channelKey)
        if (key.isBlank()) return
        prefs.edit().remove(KEY_PREFIX + key).apply()
    }

    /** Returns la liste des channelKeys (normalisés) bannis. */
    fun getAllBannedKeys(): Set<String> {
        return prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
            .toSet()
    }

    /** Combien de chaînes bannies au total. */
    fun count(): Int = getAllBannedKeys().size
}
