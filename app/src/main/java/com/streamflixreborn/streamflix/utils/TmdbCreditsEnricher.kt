package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-06-08 (user "sur Wiflix il y a les acteurs en médaillon, si on clique
 * on a les autres films de cet acteur. Cette fonction n'est pas dispo sur
 * tous les providers. Tu peux la faire pour tous ?") : helper centralisé
 * qui enrichit le cast et la filmographie via TMDB pour tous les providers
 * qui ne fournissent pas ces données nativement.
 *
 * Stratégie :
 *  - Si un Movie/TvShow a un `id` parseable en Int → on suppose que c'est un
 *    TMDB ID (vrai pour Movix, Frembed, Cloudstream, TMDb, etc.).
 *  - On appelle TMDB Movies.details + AppendToResponse.CREDITS → cast complet.
 *  - getPersonDetails(personId) → People avec filmography (combined_credits).
 *  - Cache en mémoire pour éviter les requêtes répétées sur la même session.
 *
 * Tous les appels sont safe : retournent emptyList ou null en cas d'erreur.
 */
object TmdbCreditsEnricher {

    private val movieCastCache = ConcurrentHashMap<Int, List<People>>()
    private val tvCastCache = ConcurrentHashMap<Int, List<People>>()
    private val personCache = ConcurrentHashMap<Int, People>()

    /** Cap à 20 acteurs pour éviter les listes interminables. */
    private const val CAST_LIMIT = 20

    /** Fetch le cast d'un film via TMDB Credits. Retourne emptyList si erreur
     *  ou si l'id n'est pas parseable en Int. */
    suspend fun fetchMovieCast(idStr: String, language: String = "fr-FR"): List<People> {
        val id = idStr.toIntOrNull() ?: return emptyList()
        movieCastCache[id]?.let { return it }
        return try {
            val details = TMDb3.Movies.details(
                movieId = id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Movie.CREDITS),
                language = language,
            )
            val cast = details.credits?.cast.orEmpty()
                .take(CAST_LIMIT)
                .map { c ->
                    People(
                        id = c.id.toString(),
                        name = c.name,
                        image = c.profilePath?.w500,
                    )
                }
            movieCastCache[id] = cast
            cast
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Idem pour une série. */
    suspend fun fetchTvShowCast(idStr: String, language: String = "fr-FR"): List<People> {
        val id = idStr.toIntOrNull() ?: return emptyList()
        tvCastCache[id]?.let { return it }
        return try {
            val details = TMDb3.TvSeries.details(
                seriesId = id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Tv.CREDITS),
                language = language,
            )
            val cast = details.credits?.cast.orEmpty()
                .take(CAST_LIMIT)
                .map { c ->
                    People(
                        id = c.id.toString(),
                        name = c.name,
                        image = c.profilePath?.w500,
                    )
                }
            tvCastCache[id] = cast
            cast
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Fetch les détails complets d'une personne (TMDB personId) avec sa
     *  filmography (combined_credits → films + séries). */
    suspend fun fetchPersonDetails(
        personIdStr: String,
        language: String = "fr-FR",
    ): People? {
        val personId = personIdStr.toIntOrNull() ?: return null
        personCache[personId]?.let { return it }
        return try {
            val person = TMDb3.People.details(
                personId = personId,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Person.COMBINED_CREDITS),
                language = language,
            )
            val filmography: List<Show> = person.combinedCredits?.cast
                ?.mapNotNull { multi ->
                    when (multi) {
                        is TMDb3.Movie -> Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.w1280,
                        )
                        is TMDb3.Tv -> TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.w1280,
                        )
                        else -> null
                    }
                } ?: emptyList()
            val result = People(
                id = person.id.toString(),
                name = person.name,
                image = person.profilePath?.w500,
                biography = person.biography,
                placeOfBirth = person.placeOfBirth,
                birthday = person.birthday,
                deathday = person.deathday,
                filmography = filmography,
            )
            personCache[personId] = result
            result
        } catch (_: Throwable) {
            null
        }
    }
}
