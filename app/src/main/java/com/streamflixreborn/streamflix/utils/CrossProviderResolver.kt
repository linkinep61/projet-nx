package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 2026-06-28 (user "ça doit être générique tu vois faut que tu répares tes
 * recherches pour vraiment que ça matche avec n'importe quoi et pas que pour
 * ce provider") : utilitaire de résolution cross-provider.
 *
 * Problème :
 *   - On a un tvShow.id (= retourné par `provider.search`) + un videoType.Episode
 *     (= saison N, épisode M).
 *   - On appelle `provider.getServers(tvShowId, videoType)` → 0 servers
 *     car le provider attend un EPISODE id (= avec saison + ep encodés dans
 *     son format propre), PAS un tvShow id.
 *
 * Solution générique : appeler `provider.getTvShow(tvShowId)` →
 *   parcourir les saisons → trouver la bonne → `getEpisodesBySeason(season.id)` →
 *   trouver l'épisode N → retourner son `episode.id`.
 *
 * Coût : 2 appels réseau supplémentaires par provider. Acceptable car c'est
 * une fois par lookup et fait en parallèle entre tous les providers.
 */
object CrossProviderResolver {

    private const val TAG = "CrossProviderResolver"

    /**
     * Résout l'ID exact à passer à `provider.getServers(id, videoType)`.
     *
     * @param provider le provider de backup (= AnimeSama, FRAnime, etc.)
     * @param match résultat d'une recherche (Movie ou TvShow du provider)
     * @param videoType type demandé (Movie ou Episode avec saison+ep)
     * @return ID à passer à getServers, ou null si non résolu
     */
    suspend fun resolveServerId(
        provider: Provider,
        match: Any,
        videoType: Video.Type,
    ): String? {
        // Films : l'id direct du match suffit (= movie.id)
        if (videoType is Video.Type.Movie || match is Movie) {
            return when (match) {
                is Movie -> match.id
                is TvShow -> match.id
                else -> null
            }
        }
        // Série + Episode → résoudre vers episode.id
        if (videoType !is Video.Type.Episode || match !is TvShow) {
            return when (match) {
                is Movie -> match.id
                is TvShow -> match.id
                else -> null
            }
        }
        val tvShowId = match.id
        val originalSeason = videoType.season.number
        val originalEpisode = videoType.number
        return try {
            val full = withTimeoutOrNull(10_000) { provider.getTvShow(tvShowId) }
            if (full == null) {
                android.util.Log.w(TAG, "[${provider.name}] getTvShow($tvShowId) TIMEOUT/null")
                return tvShowId
            }
            val targetSeason = full.seasons.firstOrNull { it.number == originalSeason }
                ?: full.seasons.firstOrNull()
            if (targetSeason == null) {
                android.util.Log.w(TAG, "[${provider.name}] no season found for $tvShowId (need S$originalSeason)")
                return tvShowId
            }
            val episodes = withTimeoutOrNull(10_000) { provider.getEpisodesBySeason(targetSeason.id) } ?: emptyList()
            if (episodes.isEmpty()) {
                android.util.Log.w(TAG, "[${provider.name}] no episodes in season ${targetSeason.id}")
                return tvShowId
            }
            val ep = episodes.firstOrNull { it.number == originalEpisode }
                ?: episodes.firstOrNull()
            if (ep == null) {
                android.util.Log.w(TAG, "[${provider.name}] no episode found in season ${targetSeason.id}")
                return tvShowId
            }
            android.util.Log.i(TAG, "[${provider.name}] resolved S${originalSeason}E${originalEpisode} → episode id=${ep.id}")
            ep.id
        } catch (e: Exception) {
            android.util.Log.w(TAG, "[${provider.name}] resolve failed: ${e.message}")
            tvShowId
        }
    }

    /**
     * Helper : convertit le résultat d'un `provider.search(title)` en serveurs,
     * en faisant automatiquement la résolution episode si nécessaire.
     *
     * Pour usage dans n'importe quel pipeline de cross-provider backup
     * (= WebJsProvider, DessinAnimeProvider native, Cloudstream, etc.).
     */
    suspend fun resolveAndFetchServers(
        provider: Provider,
        match: Any,
        videoType: Video.Type,
        timeoutMs: Long = 15_000,
    ): List<Video.Server> {
        val resolvedId = resolveServerId(provider, match, videoType) ?: return emptyList()
        return try {
            withTimeoutOrNull(timeoutMs) { provider.getServers(resolvedId, videoType) } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "[${provider.name}] getServers($resolvedId) failed: ${e.message}")
            emptyList()
        }
    }
}
