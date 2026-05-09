package com.streamflixreborn.streamflix.fragments.player.settings

import android.content.Context
import android.content.SharedPreferences
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * Persists banned IPTV server IDs per channel.
 *
 * 2026-05-08 : avant ce store, le ban était juste un retrait UI mémoire →
 * le variant banni revenait au prochain ouverture de la chaîne. User s'est
 * plaint "faut pas qu'il se recharge à chaque fois" → on persiste maintenant
 * en SharedPrefs et on filtre les bannis dans getServers de chaque provider.
 *
 * Format SharedPrefs :
 *   key = "iptv_ban::{channelKey}", value = StringSet de serverIds bannis
 *
 * Pas de limite max (l'user peut bannir autant qu'il veut). Le ban est
 * définitif jusqu'à un "unban" explicite (toggleBan ou clearBans).
 *
 * Logique d'utilisation :
 *  - PlayerSettingsView.Settings.ChannelVariant.ban() → call recordBan() ici
 *  - Provider.getServers() → filtre via isBanned() pour exclure les variants
 *    bannis du pool (sinon ils reviendraient au reload).
 */
object IptvBannedServers {

    private const val PREFS_NAME = "iptv_banned_servers"
    private const val KEY_PREFIX = "iptv_ban::"

    private val prefs: SharedPreferences by lazy {
        StreamFlixApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Normalise un channelKey IPTV pour bans cross-provider (cf IptvFavorites). */
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
            .substringBefore("::")
            .lowercase()
            .trim()
    }

    /** True si ce serverId a été banni pour cette chaîne (cross-provider). */
    fun isBanned(channelKey: String, serverId: String): Boolean {
        val key = normalize(channelKey)
        if (key.isBlank()) return false
        return prefs.getStringSet(KEY_PREFIX + key, emptySet())?.contains(serverId) == true
    }

    /** Enregistre un ban persistant. */
    fun recordBan(channelKey: String, serverId: String) {
        val key = normalize(channelKey)
        if (key.isBlank() || serverId.isBlank()) return
        val storeKey = KEY_PREFIX + key
        val set = prefs.getStringSet(storeKey, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.add(serverId)) {
            prefs.edit().putStringSet(storeKey, set).apply()
        }
    }

    /** Retire un ban (unban manuel). */
    fun unban(channelKey: String, serverId: String) {
        val key = normalize(channelKey)
        if (key.isBlank()) return
        val storeKey = KEY_PREFIX + key
        val set = prefs.getStringSet(storeKey, emptySet())?.toMutableSet() ?: return
        if (set.remove(serverId)) {
            if (set.isEmpty()) {
                prefs.edit().remove(storeKey).apply()
            } else {
                prefs.edit().putStringSet(storeKey, set).apply()
            }
        }
    }

    /** Returns la liste des serverIds bannis pour cette chaîne. */
    fun getBansForChannel(channelKey: String): Set<String> {
        val key = normalize(channelKey)
        if (key.isBlank()) return emptySet()
        return prefs.getStringSet(KEY_PREFIX + key, emptySet()) ?: emptySet()
    }

    /** Clear ALL bans for a channel (button "réactiver tous"). */
    fun clearBansForChannel(channelKey: String) {
        val key = normalize(channelKey)
        if (key.isBlank()) return
        prefs.edit().remove(KEY_PREFIX + key).apply()
    }

    /** Combien de bans pour cette chaîne. */
    fun count(channelKey: String): Int = getBansForChannel(channelKey).size
}
