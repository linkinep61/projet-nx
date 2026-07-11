package com.streamflixreborn.streamflix.utils

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * Store des extracteurs désactivés + favoris par provider.
 *
 * **Désactivés** : extracteurs que l'user ne veut plus voir dans le picker.
 * Filtrés par [ExtractorRanker.rankServers].
 *
 * **Favoris par provider** : extracteurs "cœur" sur un provider donné.
 * Reçoivent un gros bonus (+80) dans [ExtractorRanker.computeScore] pour
 * passer en premier quand l'user est sur ce provider. Plusieurs cœurs
 * possibles par provider.
 *
 * Persisté en SharedPreferences `extractor_toggles` :
 *  - `disabled_extractors` : Set<String> des noms lowercase désactivés
 *  - `fav_<providerName>` : Set<String> des noms lowercase favoris pour ce provider
 */
object ExtractorToggleStore {

    private const val PREFS_NAME = "extractor_toggles"
    private const val PREF_KEY_DISABLED = "disabled_extractors"
    private const val PREF_FAV_PREFIX = "fav_"

    // 2026-07-04 (user "WAAW1.TV : ça ouvre un player en écran noir avec l'IP en filigrane +
    //   captcha, c'est trop chiant ; désactive l'extracteur dédié à cette source") :
    //   extracteurs FORCÉS désactivés en dur. "netu" = NetuExtractor (waaw1.tv / netu.tv /
    //   hqq.tv / waaw.*), source dédiée qui ne sert rien d'autre → filtrée hors du picker par
    //   rankServers. Retirer "netu" de ce set pour la réactiver.
    private val FORCE_DISABLED = setOf("netu")

    /** Bonus score pour un extracteur favori sur le provider actuel.
     *  Valeur très élevée pour supplanter TOUT le tri automatique :
     *  un cœur = toujours en premier, quoi qu'il arrive. */
    const val FAVORITE_BONUS = 500

    private fun prefs() = StreamFlixApp.instance.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── Désactivés ──────────────────────────────────────────────────────

    /** Retourne le Set des noms d'extracteurs désactivés (lowercase). */
    fun getDisabled(): Set<String> {
        // + FORCE_DISABLED : extracteurs désactivés en dur (WAAW/Netu) — toujours filtrés.
        return (prefs().getStringSet(PREF_KEY_DISABLED, emptySet()) ?: emptySet()) + FORCE_DISABLED
    }

    /** Met à jour l'ensemble complet des extracteurs désactivés. */
    fun setDisabled(names: Set<String>) {
        prefs().edit().putStringSet(PREF_KEY_DISABLED, names).apply()
    }

    /** Vérifie si un extracteur est activé (= PAS dans la liste disabled). */
    fun isEnabled(extractorNameLower: String): Boolean {
        return extractorNameLower !in getDisabled()
    }

    // ─── Favoris par provider ────────────────────────────────────────────

    /** Clé SharedPrefs pour les favoris d'un provider. */
    private fun favKey(providerName: String): String =
        PREF_FAV_PREFIX + providerName.lowercase().replace(" ", "_")

    /** Retourne le Set des extracteurs favoris pour un provider (lowercase). */
    fun getFavorites(providerName: String): Set<String> {
        return prefs().getStringSet(favKey(providerName), emptySet()) ?: emptySet()
    }

    /** Met à jour les favoris pour un provider. */
    fun setFavorites(providerName: String, names: Set<String>) {
        prefs().edit().putStringSet(favKey(providerName), names).apply()
    }

    /** Vérifie si un extracteur est favori pour le provider actuel. */
    fun isFavorite(extractorNameLower: String, providerName: String): Boolean {
        return extractorNameLower in getFavorites(providerName)
    }

    /** Toggle un favori pour un provider. Retourne le nouvel état. */
    fun toggleFavorite(extractorNameLower: String, providerName: String): Boolean {
        val favs = getFavorites(providerName).toMutableSet()
        val nowFav = if (extractorNameLower in favs) {
            favs.remove(extractorNameLower)
            false
        } else {
            favs.add(extractorNameLower)
            true
        }
        setFavorites(providerName, favs)
        return nowFav
    }

    /** Retourne la liste de TOUS les extracteurs connus (noms uniques, triés). */
    fun allExtractorNames(): List<String> =
        com.streamflixreborn.streamflix.extractors.Extractor.allExtractorNames()
}
