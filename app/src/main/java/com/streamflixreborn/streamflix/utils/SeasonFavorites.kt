package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-05-21 (user "j'aimerais qu'on puisse mettre la saison d'une série en
 *   favoris, sur tous les providers sauf IPTV") : favoris au niveau SAISON.
 *
 * Stocké en SharedPreferences (JSON), PAR PROFIL (clé inclut le profileId comme
 * les DB providers `{profile}_{provider}.db`). Chaque entrée garde de quoi
 * afficher la carte dans l'écran Favoris (cœur) ET de quoi rouvrir la saison
 * dans le bon provider.
 *
 * Clé d'unicité d'une saison = provider + showId + seasonNumber.
 */
object SeasonFavorites {
    private const val TAG = "SeasonFavorites"
    private const val PREFS = "season_favorites"

    /** Préfixe d'id synthétique utilisé pour représenter une saison-favorite
     *  comme une fausse "série" dans la grille du cœur, et la reconnaître au clic. */
    const val SYNTHETIC_ID_PREFIX = "seasonfav::"

    data class Entry(
        val provider: String,
        val showId: String,
        val showTitle: String,
        val showPoster: String?,
        val showBanner: String?,
        val seasonId: String,
        val seasonNumber: Int,
        val seasonTitle: String?,
        val favoritedAt: Long,
    ) {
        /** id synthétique : `seasonfav::<provider>::<showId>::<seasonNumber>`. */
        fun syntheticId(): String = "$SYNTHETIC_ID_PREFIX$provider::$showId::$seasonNumber"
    }

    private fun prefs(): android.content.SharedPreferences =
        StreamFlixApp.instance.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): String = "favs_${ProfileManager.currentProfileIdOrDefault()}"

    private fun uniqueKey(provider: String, showId: String, seasonNumber: Int): String =
        "$provider|$showId|$seasonNumber"

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
                put("favoritedAt", e.favoritedAt)
            })
        }
        prefs().edit().putString(key(), arr.toString()).apply()
    }

    fun isFavorite(provider: String, showId: String, seasonNumber: Int): Boolean {
        val target = uniqueKey(provider, showId, seasonNumber)
        return readAll().any { uniqueKey(it.provider, it.showId, it.seasonNumber) == target }
    }

    /** Toggle. Retourne le nouvel état (true = désormais favori). */
    fun toggle(entry: Entry): Boolean {
        val list = readAll()
        val target = uniqueKey(entry.provider, entry.showId, entry.seasonNumber)
        val existing = list.indexOfFirst { uniqueKey(it.provider, it.showId, it.seasonNumber) == target }
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

    fun remove(provider: String, showId: String, seasonNumber: Int) {
        val target = uniqueKey(provider, showId, seasonNumber)
        val list = readAll().filterNot { uniqueKey(it.provider, it.showId, it.seasonNumber) == target }
        writeAll(list)
    }

    /** Retire par id synthétique (depuis l'écran cœur). */
    fun removeBySyntheticId(syntheticId: String) {
        parseSyntheticId(syntheticId)?.let { (provider, showId, seasonNumber) ->
            remove(provider, showId, seasonNumber)
        }
    }

    /** Toutes les saisons favorites du profil courant, plus récentes d'abord. */
    fun all(): List<Entry> = readAll().sortedByDescending { it.favoritedAt }

    /** Parse `seasonfav::<provider>::<showId>::<seasonNumber>` → (provider, showId, seasonNumber). */
    fun parseSyntheticId(syntheticId: String): Triple<String, String, Int>? {
        if (!syntheticId.startsWith(SYNTHETIC_ID_PREFIX)) return null
        val rest = syntheticId.removePrefix(SYNTHETIC_ID_PREFIX)
        // provider et showId peuvent en théorie contenir "::" ? showId non (ids
        // providers sans "::"). On split en 3 depuis la fin pour le seasonNumber.
        val lastSep = rest.lastIndexOf("::")
        if (lastSep < 0) return null
        val seasonNumber = rest.substring(lastSep + 2).toIntOrNull() ?: return null
        val head = rest.substring(0, lastSep)
        val firstSep = head.indexOf("::")
        if (firstSep < 0) return null
        val provider = head.substring(0, firstSep)
        val showId = head.substring(firstSep + 2)
        return Triple(provider, showId, seasonNumber)
    }

    /** Retrouve l'entrée complète depuis un id synthétique. */
    fun findBySyntheticId(syntheticId: String): Entry? {
        val (provider, showId, seasonNumber) = parseSyntheticId(syntheticId) ?: return null
        val target = uniqueKey(provider, showId, seasonNumber)
        return readAll().firstOrNull { uniqueKey(it.provider, it.showId, it.seasonNumber) == target }
    }
}
