package com.streamflixreborn.streamflix.fragments.player.settings

import android.content.Context
import android.content.SharedPreferences
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * Persists favorite IPTV server IDs per channel.
 *
 * 2026-05-08 : étendu pour supporter PLUSIEURS favoris par chaîne (max 5).
 * Avant : 1 favori unique → maintenant : Set<String> de serverIds + un ordre
 * d'insertion (le 1er ajouté = le plus prioritaire).
 *
 * Format SharedPrefs :
 *   - key = "iptv_fav::{channelKey}", value = StringSet de serverIds
 *   - key = "iptv_fav_order::{channelKey}", value = String CSV "id1,id2,id3..."
 *     (l'ordre est conservé séparément car StringSet n'est pas ordonné)
 *
 * Quand un user marque jusqu'à 5 sources favorites, le code de ranking IPTV
 * les remonte EN TÊTE du picker, dans l'ordre où elles ont été ajoutées.
 * Si le user en marque une 6e, la plus ancienne est éjectée (LRU).
 */
object IptvFavorites {

    private const val PREFS_NAME = "iptv_favorites"
    private const val KEY_PREFIX = "iptv_fav::"
    private const val ORDER_KEY_PREFIX = "iptv_fav_order::"
    const val MAX_FAVORITES_PER_CHANNEL = 20  // 2026-05-08 : 5 → 10 → 20 (cross-provider, large marge)

    /** Normalise un channelKey IPTV pour que les favoris s'appliquent
     *  cross-provider. "vegeta::arte" / "ola::arte" / "ch::arte" / "livehub::arte"
     *  partagent tous la même clé normalisée "arte" → favoris partagés. */
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
            // Strip aussi les sous-IDs (ex: "::1::abc" pour épisodes IPTV)
            .substringBefore("::")
            .lowercase()
            .trim()
    }

    private val prefs: SharedPreferences by lazy {
        StreamFlixApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Check if a server ID is among the favorites for [channelKey].
     * Normalisation : "vegeta::arte" et "livehub::arte" partagent les favoris.
     */
    fun isFavorite(channelKey: String, serverId: String): Boolean {
        val key = normalize(channelKey)
        if (key.isBlank()) return false
        return getFavoritesForChannel(channelKey).contains(serverId)
    }

    /**
     * Toggle favorite state for a server within [channelKey].
     *  - Si pas favori → ajoute (au début de l'ordre = priorité max).
     *    Si déjà MAX favoris, le plus ancien est éjecté.
     *  - Si favori → retire.
     * Returns true si maintenant favorisé, false sinon.
     */
    fun toggleFavorite(channelKey: String, serverId: String): Boolean {
        val key = normalize(channelKey)
        if (key.isBlank()) return false
        val current = getFavoritesForChannel(channelKey).toMutableList()
        return if (current.contains(serverId)) {
            current.remove(serverId)
            saveFavorites(key, current)
            false
        } else {
            current.add(0, serverId)
            while (current.size > MAX_FAVORITES_PER_CHANNEL) {
                current.removeAt(current.size - 1)
            }
            saveFavorites(key, current)
            true
        }
    }

    /**
     * Remove [serverId] des favoris de [channelKey] s'il y est.
     */
    fun removeFavorite(channelKey: String, serverId: String) {
        val key = normalize(channelKey)
        if (key.isBlank()) return
        val current = getFavoritesForChannel(channelKey).toMutableList()
        if (current.remove(serverId)) {
            saveFavorites(key, current)
        }
    }

    /**
     * Returns la liste ordonnée des favoris pour cette chaîne (priorité décroissante).
     * Vide si aucun favori. Normalise la clé pour le lookup cross-provider.
     */
    fun getFavoritesForChannel(channelKey: String): List<String> {
        val key = normalize(channelKey)
        if (key.isBlank()) return emptyList()
        val orderCsv = prefs.getString(ORDER_KEY_PREFIX + key, null) ?: return emptyList()
        if (orderCsv.isBlank()) return emptyList()
        return orderCsv.split(",").filter { it.isNotBlank() }
    }

    /**
     * Compatibilité : retourne le 1er favori (s'il y en a) — utilisé par les
     * call sites qui attendent un single favori.
     */
    fun getFavoriteForChannel(channelKey: String): String? {
        return getFavoritesForChannel(channelKey).firstOrNull()
    }

    /**
     * Combien de favoris pour cette chaîne (0 à MAX_FAVORITES_PER_CHANNEL).
     */
    fun count(channelKey: String): Int = getFavoritesForChannel(channelKey).size

    /**
     * 2026-05-08 : retire TOUS les server-favoris ❤ pour [channelKey].
     * Utile depuis le long-press menu "Retirer des favoris" sur le home TV Hub
     * pour que la chaîne disparaisse vraiment du Hub (sinon un seul ❤ resté
     * actif suffit à la garder dans le Hub).
     */
    fun clearAllForChannel(channelKey: String) {
        val key = normalize(channelKey)
        if (key.isBlank()) return
        prefs.edit()
            .remove(ORDER_KEY_PREFIX + key)
            .remove(KEY_PREFIX + key)
            .apply()
    }

    /** Returns toutes les channelKeys (normalisées) qui ont AU MOINS un server favori.
     *  Utilisé par les providers IPTV pour afficher la chaîne en section Favoris dès
     *  qu'au moins un de ses servers est marqué ❤. */
    fun getAllChannelKeysWithFavorites(): Set<String> {
        return prefs.all.keys
            .filter { it.startsWith(ORDER_KEY_PREFIX) }
            .map { it.removePrefix(ORDER_KEY_PREFIX) }
            .filter { key ->
                val csv = prefs.getString(ORDER_KEY_PREFIX + key, null)
                !csv.isNullOrBlank()
            }
            .toSet()
    }

    private fun saveFavorites(channelKey: String, ordered: List<String>) {
        // channelKey est déjà normalisé par les callers
        val orderKey = ORDER_KEY_PREFIX + channelKey
        val setKey = KEY_PREFIX + channelKey
        if (ordered.isEmpty()) {
            prefs.edit().remove(orderKey).remove(setKey).apply()
        } else {
            prefs.edit()
                .putString(orderKey, ordered.joinToString(","))
                .putStringSet(setKey, ordered.toSet())
                .apply()
        }
    }
}
