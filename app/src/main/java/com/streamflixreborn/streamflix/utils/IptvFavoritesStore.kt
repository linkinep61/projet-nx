package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * Per-provider favorites store for IPTV channels.
 *
 * Each provider gets its own Set<String> of favorited channel IDs, keyed by
 * `iptv_favorites_<providerName>`. We deliberately keep favorites scoped to a
 * single provider (rather than global) because:
 *   - Channel IDs are NOT portable across providers (e.g. `ch::tf1` on WiTV vs
 *     `ola::tf1` on OLA TV vs `vegeta::tf1`). Cross-provider matching would
 *     require name-based fuzzy matching, which is brittle.
 *   - Users typically pick one IPTV provider that works well for them and stick
 *     with it; favoriting per-provider matches that mental model.
 *
 * Backed by a dedicated SharedPreferences file so we don't pollute the main
 * preferences file with per-channel keys.
 */
object IptvFavoritesStore {

    private const val PREFS_NAME = "iptv_favorites"

    private val prefs: SharedPreferences by lazy {
        val ctx: Context = StreamFlixApp.instance.applicationContext
        ctx.getSharedPreferences("${BuildConfig.APPLICATION_ID}.$PREFS_NAME", Context.MODE_PRIVATE)
    }

    private fun keyFor(providerName: String) = "favorites_${providerName.replace(' ', '_')}"

    /** Read all favorited channel IDs for a provider. Empty set if none. */
    fun getFavorites(providerName: String): Set<String> {
        // Defensive copy — SharedPreferences returns a reference that must not be mutated.
        return prefs.getStringSet(keyFor(providerName), emptySet())?.toSet() ?: emptySet()
    }

    fun isFavorite(providerName: String, channelId: String): Boolean {
        return getFavorites(providerName).contains(channelId)
    }

    /**
     * Toggle favorite state. Returns the NEW state:
     *   - `true`  → channel is now a favorite (was added)
     *   - `false` → channel is no longer a favorite (was removed)
     */
    fun toggle(providerName: String, channelId: String): Boolean {
        val current = getFavorites(providerName).toMutableSet()
        val nowFavorite = if (current.contains(channelId)) {
            current.remove(channelId)
            false
        } else {
            current.add(channelId)
            true
        }
        prefs.edit { putStringSet(keyFor(providerName), current) }
        return nowFavorite
    }

    fun add(providerName: String, channelId: String) {
        val current = getFavorites(providerName).toMutableSet()
        if (current.add(channelId)) {
            prefs.edit { putStringSet(keyFor(providerName), current) }
        }
    }

    fun remove(providerName: String, channelId: String) {
        val current = getFavorites(providerName).toMutableSet()
        if (current.remove(channelId)) {
            prefs.edit { putStringSet(keyFor(providerName), current) }
        }
    }
}
