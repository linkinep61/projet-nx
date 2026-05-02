package com.streamflixreborn.streamflix.fragments.player.settings

import android.content.Context
import android.content.SharedPreferences
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * Persists favorite IPTV server IDs per channel.
 * Format: key = "iptv_fav::{channelKey}", value = serverId
 *
 * channelKey is the visual channel name (e.g. "France 4", "M6") — NOT the
 * CID from the server ID.  Different stream variants of the same channel
 * have different CIDs but share the same channelKey, so there is exactly
 * ONE favorite per visual channel.
 *
 * When a user marks a source as favorite in the Chaîne page,
 * that source is played in priority on next startup.
 */
object IptvFavorites {

    private const val PREFS_NAME = "iptv_favorites"
    private const val KEY_PREFIX = "iptv_fav::"

    private val prefs: SharedPreferences by lazy {
        StreamFlixApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Check if a server ID is the current favorite for [channelKey].
     */
    fun isFavorite(channelKey: String, serverId: String): Boolean {
        if (channelKey.isBlank()) return false
        return prefs.getString(KEY_PREFIX + channelKey, null) == serverId
    }

    /**
     * Toggle favorite state for a server within [channelKey].
     * Only one favorite per channel — toggling a new one replaces the old one.
     * Returns true if now favorited, false if unfavorited.
     */
    fun toggleFavorite(channelKey: String, serverId: String): Boolean {
        if (channelKey.isBlank()) return false
        val key = KEY_PREFIX + channelKey
        val current = prefs.getString(key, null)
        return if (current == serverId) {
            // Unfavorite
            prefs.edit().remove(key).apply()
            false
        } else {
            // Set as new favorite (replaces previous)
            prefs.edit().putString(key, serverId).apply()
            true
        }
    }

    /**
     * Remove the favorite for [channelKey] if it matches [serverId]
     * (e.g. when the server is banned).
     */
    fun removeFavorite(channelKey: String, serverId: String) {
        if (channelKey.isBlank()) return
        val key = KEY_PREFIX + channelKey
        if (prefs.getString(key, null) == serverId) {
            prefs.edit().remove(key).apply()
        }
    }

    /**
     * Get the favorite server ID for a channel, if any.
     */
    fun getFavoriteForChannel(channelKey: String): String? {
        if (channelKey.isBlank()) return null
        return prefs.getString(KEY_PREFIX + channelKey, null)
    }
}
