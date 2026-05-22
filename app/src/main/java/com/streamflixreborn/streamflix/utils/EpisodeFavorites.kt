package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-05-22 (user "mettre les épisodes des saisons en favoris en restant
 *   appuyé dessus") : favoris au niveau ÉPISODE.
 *
 * Même pattern que [SeasonFavorites] : SharedPreferences (JSON), PAR PROFIL.
 * Chaque entrée garde de quoi afficher la carte dans le cœur ET rouvrir
 * la série dans le bon provider à la bonne saison.
 *
 * Clé d'unicité = provider + showId + seasonNumber + episodeNumber.
 */
object EpisodeFavorites {
    private const val TAG = "EpisodeFavorites"
    private const val PREFS = "episode_favorites"

    /** Préfixe d'id synthétique pour représenter un épisode-favori dans le cœur. */
    const val SYNTHETIC_ID_PREFIX = "epfav::"

    data class Entry(
        val provider: String,
        val showId: String,
        val showTitle: String,
        val showPoster: String?,
        val showBanner: String?,
        val seasonId: String,
        val seasonNumber: Int,
        val seasonTitle: String?,
        val episodeId: String,
        val episodeNumber: Int,
        val episodeTitle: String?,
        val episodePoster: String?,
        val favoritedAt: Long,
    ) {
        /** id synthétique : `epfav::<provider>::<showId>::<seasonNumber>::<episodeNumber>`. */
        fun syntheticId(): String =
            "$SYNTHETIC_ID_PREFIX$provider::$showId::$seasonNumber::$episodeNumber"
    }

    private fun prefs(): android.content.SharedPreferences =
        StreamFlixApp.instance.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): String = "favs_${ProfileManager.currentProfileIdOrDefault()}"

    private fun uniqueKey(provider: String, showId: String, seasonNumber: Int, episodeNumber: Int): String =
        "$provider|$showId|$seasonNumber|$episodeNumber"

    private fun readAll(): MutableList<Entry> {
        val raw = prefs().getString(key(), "[]") ?: "[]"
        val out = mutableListOf<Entry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Entry(
                    provider = o.optString("provider"),
                    showId = o.optString("showId"),
                    showTitle = o.optString("showTitle"),
                    showPoster = o.optString("showPoster").takeIf { it.isNotBlank() },
                    showBanner = o.optString("showBanner").takeIf { it.isNotBlank() },
                    seasonId = o.optString("seasonId"),
                    seasonNumber = o.optInt("seasonNumber"),
                    seasonTitle = o.optString("seasonTitle").takeIf { it.isNotBlank() },
                    episodeId = o.optString("episodeId"),
                    episodeNumber = o.optInt("episodeNumber"),
                    episodeTitle = o.optString("episodeTitle").takeIf { it.isNotBlank() },
                    episodePoster = o.optString("episodePoster").takeIf { it.isNotBlank() },
                    favoritedAt = o.optLong("favoritedAt"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "readAll parse error: ${e.message}")
        }
        return out
    }

    private fun writeAll(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("provider", e.provider)
                put("showId", e.showId)
                put("showTitle", e.showTitle)
                put("showPoster", e.showPoster ?: "")
                put("showBanner", e.showBanner ?: "")
                put("seasonId", e.seasonId)
                put("seasonNumber", e.seasonNumber)
                put("seasonTitle", e.seasonTitle ?: "")
                put("episodeId", e.episodeId)
                put("episodeNumber", e.episodeNumber)
                put("episodeTitle", e.episodeTitle ?: "")
                put("episodePoster", e.episodePoster ?: "")
                put("favoritedAt", e.favoritedAt)
            })
        }
        prefs().edit().putString(key(), arr.toString()).apply()
    }

    fun isFavorite(provider: String, showId: String, seasonNumber: Int, episodeNumber: Int): Boolean {
        val target = uniqueKey(provider, showId, seasonNumber, episodeNumber)
        return readAll().any {
            uniqueKey(it.provider, it.showId, it.seasonNumber, it.episodeNumber) == target
        }
    }

    /** Toggle. Retourne le nouvel état (true = désormais favori). */
    fun toggle(entry: Entry): Boolean {
        val list = readAll()
        val target = uniqueKey(entry.provider, entry.showId, entry.seasonNumber, entry.episodeNumber)
        val existing = list.indexOfFirst {
            uniqueKey(it.provider, it.showId, it.seasonNumber, it.episodeNumber) == target
        }
        return if (existing >= 0) {
            list.removeAt(existing)
            writeAll(list)
            false
        } else {
            list.add(entry.copy(favoritedAt = System.currentTimeMillis()))
            writeAll(list)
            true
        }
    }

    fun remove(provider: String, showId: String, seasonNumber: Int, episodeNumber: Int) {
        val target = uniqueKey(provider, showId, seasonNumber, episodeNumber)
        val list = readAll().filterNot {
            uniqueKey(it.provider, it.showId, it.seasonNumber, it.episodeNumber) == target
        }
        writeAll(list)
    }

    /** Retire par id synthétique (depuis l'écran cœur). */
    fun removeBySyntheticId(syntheticId: String) {
        parseSyntheticId(syntheticId)?.let { (provider, showId, seasonNumber, episodeNumber) ->
            remove(provider, showId, seasonNumber, episodeNumber)
        }
    }

    /** Toutes les épisodes favorites du profil courant, plus récentes d'abord. */
    fun all(): List<Entry> = readAll().sortedByDescending { it.favoritedAt }

    /** data class pour le résultat du parse. */
    data class ParsedId(val provider: String, val showId: String, val seasonNumber: Int, val episodeNumber: Int)

    /** Parse `epfav::<provider>::<showId>::<seasonNumber>::<episodeNumber>`. */
    fun parseSyntheticId(syntheticId: String): ParsedId? {
        if (!syntheticId.startsWith(SYNTHETIC_ID_PREFIX)) return null
        val rest = syntheticId.removePrefix(SYNTHETIC_ID_PREFIX)
        // Split from the end: episodeNumber :: seasonNumber :: showId :: provider
        val parts = rest.split("::")
        if (parts.size < 4) return null
        val episodeNumber = parts.last().toIntOrNull() ?: return null
        val seasonNumber = parts[parts.size - 2].toIntOrNull() ?: return null
        val provider = parts[0]
        val showId = parts.subList(1, parts.size - 2).joinToString("::")
        return ParsedId(provider, showId, seasonNumber, episodeNumber)
    }

    /** Retrouve l'entrée complète depuis un id synthétique. */
    fun findBySyntheticId(syntheticId: String): Entry? {
        val parsed = parseSyntheticId(syntheticId) ?: return null
        val target = uniqueKey(parsed.provider, parsed.showId, parsed.seasonNumber, parsed.episodeNumber)
        return readAll().firstOrNull {
            uniqueKey(it.provider, it.showId, it.seasonNumber, it.episodeNumber) == target
        }
    }
}
