package com.streamflixreborn.streamflix.utils

import androidx.preference.PreferenceManager
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * 2026-05-26 — Filtre par genre TMDB pour les écrans Films/Séries.
 * Genres stables (IDs TMDB ne changent pas). Les providers TMDB
 * (Cloudstream, Movix, TMDb, NetMirror) supportent getGenre(id, page).
 * Les autres providers (Wiflix, FrenchStream, Papa, IPTV…) sont exclus.
 *
 * Le choix est mémorisé PAR PROVIDER (clé pref_genre_filter_<provider>).
 * null = pas de filtre genre (affiche tout, comportement par défaut).
 */
object GenreFilter {

    data class GenreEntry(val id: String, val name: String)

    /** Liste des genres TMDB en français (IDs stables, identiques films/séries) */
    val genres = listOf(
        GenreEntry("28", "Action"),
        GenreEntry("12", "Aventure"),
        GenreEntry("16", "Animation"),
        GenreEntry("35", "Comédie"),
        GenreEntry("80", "Crime"),
        GenreEntry("99", "Documentaire"),
        GenreEntry("18", "Drame"),
        GenreEntry("10751", "Famille"),
        GenreEntry("14", "Fantaisie"),
        GenreEntry("27", "Horreur"),
        GenreEntry("10402", "Musique"),
        GenreEntry("9648", "Mystère"),
        GenreEntry("10749", "Romance"),
        GenreEntry("878", "Science-Fiction"),
        GenreEntry("53", "Thriller"),
        GenreEntry("10752", "Guerre"),
        GenreEntry("37", "Western"),
    )

    /** Le filtre genre n'est dispo que pour les providers TMDB-based.
     *  Même liste que CatalogFilter.isSupported(). */
    fun isSupported(providerName: String?): Boolean =
        providerName == "Cloudstream" ||
        providerName == "Movix" ||
        providerName == "NetMirror" ||
        providerName?.startsWith("TMDb") == true

    fun isSupported(): Boolean = isSupported(UserPreferences.currentProvider?.name)

    private fun key(providerName: String) = "pref_genre_filter_$providerName"

    /** Genre sélectionné pour ce provider, ou null = pas de filtre (tout). */
    fun get(providerName: String): GenreEntry? = try {
        val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
        val savedId = prefs.getString(key(providerName), null)
        if (savedId == null) null else genres.find { it.id == savedId }
    } catch (_: Exception) {
        null
    }

    /** Sauvegarde le genre sélectionné. null = tout (efface la pref). */
    fun set(providerName: String, genre: GenreEntry?) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
            if (genre == null) {
                prefs.edit().remove(key(providerName)).apply()
            } else {
                prefs.edit().putString(key(providerName), genre.id).apply()
            }
        } catch (_: Exception) {
        }
    }

    /** Genre ID courant pour le provider actif, ou null. */
    fun currentGenreId(): String? {
        val name = UserPreferences.currentProvider?.name ?: return null
        return get(name)?.id
    }

    /** Label du genre courant pour le provider actif, ou null. */
    fun currentLabel(): String? {
        val name = UserPreferences.currentProvider?.name ?: return null
        return get(name)?.name
    }
}
