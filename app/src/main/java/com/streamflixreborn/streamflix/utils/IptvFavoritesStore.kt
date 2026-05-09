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
    /** 2026-05-08 : clé GLOBALE (cross-provider) — un favori est un nom de chaîne
     *  normalisé (ex: "tf1") plutôt qu'un id spécifique au provider. Marquer
     *  TF1 favori sur Vegeta → visible sur Ola/WiTV/TV Hub/etc. */
    private const val GLOBAL_KEY = "favorites_iptv_global"

    private val prefs: SharedPreferences by lazy {
        val ctx: Context = StreamFlixApp.instance.applicationContext
        ctx.getSharedPreferences("${BuildConfig.APPLICATION_ID}.$PREFS_NAME", Context.MODE_PRIVATE)
    }

    /** Strip le préfixe provider du channelId pour avoir la clé canonique. */
    private fun normalize(channelId: String): String {
        if (channelId.isBlank()) return channelId
        return channelId
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

    /** Reconstruit le préfixe provider à partir du nom pour reformer un channelId. */
    private fun prefixFor(providerName: String): String = when (providerName) {
        "WiTV" -> "ch::"
        "OLA TV" -> "ola::"
        "Vegeta TV" -> "vegeta::"
        "Movix LiveTV" -> "movixlivetv::"
        "Sport Live" -> "sportlive::"
        "TV Hub" -> "livehub::"
        else -> ""
    }

    /** Returns all favorited channel IDs SPÉCIFIQUES au provider courant.
     *  En interne le store est cross-provider — on reformate les ids avec le
     *  préfixe du provider pour matcher les channelIds qu'il retourne. */
    fun getFavorites(providerName: String): Set<String> {
        val canonicalKeys = prefs.getStringSet(GLOBAL_KEY, emptySet()) ?: emptySet()
        val prefix = prefixFor(providerName)
        return canonicalKeys.map { prefix + it }.toSet()
    }

    fun isFavorite(providerName: String, channelId: String): Boolean {
        val key = normalize(channelId)
        if (key.isBlank()) return false
        return (prefs.getStringSet(GLOBAL_KEY, emptySet()) ?: emptySet()).contains(key)
    }

    /** Toggle favorite state cross-provider. Returns the NEW state. */
    fun toggle(providerName: String, channelId: String): Boolean {
        val key = normalize(channelId)
        if (key.isBlank()) {
            android.util.Log.w("IptvFavStore", "toggle($providerName, $channelId): key BLANK after normalize → no-op")
            return false
        }
        val current = (prefs.getStringSet(GLOBAL_KEY, emptySet()) ?: emptySet()).toMutableSet()
        val before = current.toSet()
        val nowFavorite = if (current.contains(key)) {
            current.remove(key)
            false
        } else {
            current.add(key)
            true
        }
        prefs.edit { putStringSet(GLOBAL_KEY, current) }
        android.util.Log.d("IptvFavStore",
            "toggle($providerName, $channelId): key='$key' nowFav=$nowFavorite " +
            "before=$before after=${current}")
        return nowFavorite
    }

    fun add(providerName: String, channelId: String) {
        val key = normalize(channelId)
        if (key.isBlank()) return
        val current = (prefs.getStringSet(GLOBAL_KEY, emptySet()) ?: emptySet()).toMutableSet()
        if (current.add(key)) {
            prefs.edit { putStringSet(GLOBAL_KEY, current) }
        }
    }

    fun remove(providerName: String, channelId: String) {
        val key = normalize(channelId)
        if (key.isBlank()) return
        val current = (prefs.getStringSet(GLOBAL_KEY, emptySet()) ?: emptySet()).toMutableSet()
        if (current.remove(key)) {
            prefs.edit { putStringSet(GLOBAL_KEY, current) }
        }
    }

    /** Returns toutes les keys canoniques favorites (set global, cross-provider).
     *  Utilisé par TV Hub pour afficher uniquement les chaînes favorites. */
    fun getAllCanonicalFavorites(): Set<String> =
        prefs.getStringSet(GLOBAL_KEY, emptySet())?.toSet() ?: emptySet()
}
