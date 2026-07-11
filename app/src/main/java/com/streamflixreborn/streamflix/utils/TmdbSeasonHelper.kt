package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow

/**
 * 2026-07-09 : utilitaire partagé pour afficher les saisons TMDB sur les providers anime.
 *
 * User : « Même si le site dessin animé propose La saison 1 la saison 5 et la saison 7
 *   Nous il faut que TMDB propose Toutes les saisons Pour que les backa puissent
 *   S'en nourrir Et affichez vraiment tout le contenu »
 *
 * Principe : on cherche le tmdbId via Search.multi, puis on utilise TvSeries.details
 * pour récupérer TOUTES les saisons (correctement numérotées). Les épisodes viennent
 * de TvSeasons.details. Les IDs suivent le format tv::tmdb::<tmdbId>::s<N> / ::e<M>
 * (compatible avec DessinAnime qui utilise déjà ce format).
 */
object TmdbSeasonHelper {

    private const val TAG = "TmdbSeasonHelper"

    // ── Résolution TMDB ID ──

    /**
     * Cherche un tmdbId pour une série TV à partir du titre (et optionnellement l'année).
     * Retourne null si aucun match fiable.
     */
    suspend fun resolveTmdbIdForTv(title: String, year: Int? = null): Int? {
        if (title.isBlank()) return null
        return try {
            val results = TMDb3.Search.multi(title, language = "fr-FR")
            // Filtrer uniquement les séries TV
            val tvResults = results.results.filterIsInstance<TMDb3.Tv>()
            if (tvResults.isEmpty()) {
                Log.d(TAG, "resolveTmdbIdForTv('$title') → 0 résultats TV")
                return null
            }
            // Match prioritaire : titre exact (insensible à la casse)
            val exact = tvResults.firstOrNull { it.name.equals(title, ignoreCase = true) }
            if (exact != null) {
                Log.d(TAG, "resolveTmdbIdForTv('$title') → exact match: id=${exact.id} '${exact.name}'")
                return exact.id
            }
            // Match secondaire : titre original
            val origMatch = tvResults.firstOrNull { it.originalName.equals(title, ignoreCase = true) }
            if (origMatch != null) {
                Log.d(TAG, "resolveTmdbIdForTv('$title') → originalName match: id=${origMatch.id}")
                return origMatch.id
            }
            // Fallback : premier résultat
            val first = tvResults.first()
            Log.d(TAG, "resolveTmdbIdForTv('$title') → fallback 1er: id=${first.id} '${first.name}'")
            first.id
        } catch (e: Exception) {
            Log.w(TAG, "resolveTmdbIdForTv('$title') ERREUR: ${e.message}")
            null
        }
    }

    // ── Construction des saisons TMDB ──

    /**
     * Construit la liste des saisons TMDB pour une série.
     * Exclut la Saison 0 (Spéciaux) si elle est vide.
     * Format ID saison : tv::tmdb::<tmdbId>::s<N>
     */
    suspend fun buildTmdbSeasons(tmdbId: Int, fallbackPoster: String? = null): List<Season> {
        return try {
            val detail = TMDb3.TvSeries.details(tmdbId, language = "fr-FR")
            val seasons = detail.seasons
                .filter { it.seasonNumber > 0 || (it.episodeCount ?: 0) > 0 }
                .map { s ->
                    val sPoster = s.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" } ?: fallbackPoster
                    Season(
                        id = "tv::tmdb::${tmdbId}::s${s.seasonNumber}",
                        number = s.seasonNumber,
                        title = s.name.ifBlank { "Saison ${s.seasonNumber}" },
                        poster = sPoster,
                    )
                }
            Log.d(TAG, "buildTmdbSeasons($tmdbId) → ${seasons.size} saisons")
            seasons
        } catch (e: Exception) {
            Log.w(TAG, "buildTmdbSeasons($tmdbId) ERREUR: ${e.message}")
            emptyList()
        }
    }

    /**
     * Construit aussi le TvShow complet (titre, overview, poster, saisons) depuis TMDB.
     * Pratique pour le cas où le provider n'a même pas besoin de scraper le titre natif.
     */
    suspend fun buildTmdbTvShow(tmdbId: Int, originalId: String): TvShow? {
        return try {
            val detail = TMDb3.TvSeries.details(tmdbId, language = "fr-FR")
            val poster = detail.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val banner = detail.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
            val seasons = detail.seasons
                .filter { it.seasonNumber > 0 || (it.episodeCount ?: 0) > 0 }
                .map { s ->
                    val sPoster = s.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" } ?: poster
                    Season(
                        id = "tv::tmdb::${tmdbId}::s${s.seasonNumber}",
                        number = s.seasonNumber,
                        title = s.name.ifBlank { "Saison ${s.seasonNumber}" },
                        poster = sPoster,
                    )
                }
            TvShow(
                id = originalId,
                title = detail.name,
                overview = detail.overview,
                poster = poster,
                banner = banner,
                released = detail.firstAirDate?.take(4),
                rating = detail.voteAverage.takeIf { it > 0 }?.toDouble(),
                seasons = seasons,
            )
        } catch (e: Exception) {
            Log.w(TAG, "buildTmdbTvShow($tmdbId) ERREUR: ${e.message}")
            null
        }
    }

    // ── Construction des épisodes TMDB ──

    /**
     * Construit la liste des épisodes TMDB pour une saison.
     * Format ID épisode : tv::tmdb::<tmdbId>::s<N>::e<M>
     */
    suspend fun buildTmdbEpisodes(tmdbId: Int, seasonNumber: Int): List<Episode> {
        return try {
            val seasonDetail = TMDb3.TvSeasons.details(tmdbId, seasonNumber, language = "fr-FR")
            val episodes = (seasonDetail.episodes ?: emptyList()).map { ep ->
                val still = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                val epTitle = ep.name?.takeIf { it.isNotBlank() } ?: "Épisode ${ep.episodeNumber}"
                Episode(
                    id = "tv::tmdb::${tmdbId}::s${seasonNumber}::e${ep.episodeNumber}",
                    number = ep.episodeNumber,
                    title = epTitle,
                    poster = still,
                )
            }
            Log.d(TAG, "buildTmdbEpisodes($tmdbId, s$seasonNumber) → ${episodes.size} épisodes")
            episodes
        } catch (e: Exception) {
            Log.w(TAG, "buildTmdbEpisodes($tmdbId, s$seasonNumber) ERREUR: ${e.message}")
            emptyList()
        }
    }

    // ── Parsing des IDs TMDB ──

    /** Vérifie si un ID est au format TMDB (tv::tmdb::... ou movie::tmdb::...) */
    fun isTmdbId(id: String): Boolean = id.contains("::tmdb::")

    /** Vérifie si un ID de saison est au format TMDB (tv::tmdb::123::s2) */
    fun isTmdbSeasonId(id: String): Boolean = id.startsWith("tv::tmdb::") && id.contains("::s")

    /**
     * Extrait tmdbId et seasonNumber d'un ID de saison TMDB.
     * Format : tv::tmdb::<tmdbId>::s<seasonNumber>
     * @return Pair(tmdbId, seasonNumber) ou null
     */
    fun parseTmdbSeasonId(seasonId: String): Pair<Int, Int>? {
        val parts = seasonId.split("::")
        // tv::tmdb::123::s2 → parts = ["tv", "tmdb", "123", "s2"]
        if (parts.size < 4 || parts[1] != "tmdb") return null
        val tmdbId = parts[2].toIntOrNull() ?: return null
        val seasonNum = parts.last().removePrefix("s").toIntOrNull() ?: return null
        return tmdbId to seasonNum
    }

    /**
     * Extrait tmdbId, seasonNumber et episodeNumber d'un ID d'épisode TMDB.
     * Format : tv::tmdb::<tmdbId>::s<N>::e<M>
     * @return Triple(tmdbId, seasonNumber, episodeNumber) ou null
     */
    fun parseTmdbEpisodeId(episodeId: String): Triple<Int, Int, Int>? {
        val parts = episodeId.split("::")
        // tv::tmdb::123::s2::e5 → parts = ["tv", "tmdb", "123", "s2", "e5"]
        if (parts.size < 5 || parts[1] != "tmdb") return null
        val tmdbId = parts[2].toIntOrNull() ?: return null
        val seasonNum = parts[3].removePrefix("s").toIntOrNull() ?: return null
        val episodeNum = parts[4].removePrefix("e").toIntOrNull() ?: return null
        return Triple(tmdbId, seasonNum, episodeNum)
    }
}
