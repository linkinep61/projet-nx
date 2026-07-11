package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.models.Video.Type.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EpisodeManager {
    private val episodes = mutableListOf<Episode>()
    var currentIndex = 0
        private set

    fun addEpisodes(list: List<Episode>) {
        episodes.clear()
        episodes.addAll(list)
        currentIndex = 0
    }

    private fun mergeEpisodes(list: List<Episode>) {
        if (list.isEmpty()) return

        val currentEpisodeId = getCurrentEpisode()?.id
        val merged = (episodes + list)
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.season.number }, { it.number }))

        episodes.clear()
        episodes.addAll(merged)

        currentIndex = currentEpisodeId
            ?.let { id -> episodes.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            ?: 0
    }

    suspend fun addEpisodesFromDb(type: Video.Type.Episode, database: AppDatabase) {
        val tvShowId = type.tvShow.id
        val seasonNumber = type.season.number
        var episodesFromDb = withContext(Dispatchers.IO) {
            database.episodeDao().getByTvShowIdAndSeasonNumber(tvShowId, seasonNumber)
        }
        val tvShowContext = withContext(Dispatchers.IO) {
            database.tvShowDao().getById(tvShowId)
        }?.let { storedTvShow ->
            TvShow(
                id = storedTvShow.id,
                title = storedTvShow.title,
                poster = storedTvShow.poster,
                banner = storedTvShow.banner,
                released = storedTvShow.released?.format("yyyy-MM-dd"),
                imdbId = storedTvShow.imdbId
            )
        } ?: TvShow(
            id = type.tvShow.id,
            title = type.tvShow.title,
            poster = type.tvShow.poster,
            banner = type.tvShow.banner,
            imdbId = type.tvShow.imdbId
        )
        val seasonContext = Season(id = "", number = seasonNumber, title = type.season.title).apply {
            tvShow = tvShowContext
        }
        val provider = UserPreferences.currentProvider
        if (provider != null) {
            try {
                val tvShow = provider.getTvShow(tvShowId)
                val season = tvShow.seasons.find { it.number == seasonNumber }
                if (season != null) {
                    var fetchedEpisodes = provider.getEpisodesBySeason(season.id)
                    // 2026-07-05 (testeur "AnimeSama : reprise via 'Continuer à regarder'
                    //   après fermeture de l'appli → flèche épisode précédent ABSENTE, suivant
                    //   inactif, pas de focus sur l'épisode courant ; le contournement = repasser
                    //   par la fiche → VF → Saison 1") : pour AnimeSama, la saison renvoyée par
                    //   getTvShow est un DOSSIER DE LANGUE (id "slug/@vostfr/…"), donc
                    //   getEpisodesBySeason retourne des SOUS-SAISONS (id "@subfolder:", overview
                    //   "@subfolder") au lieu des vrais épisodes. Cette liste de dossiers écrasait
                    //   les bons épisodes → le numéro de l'épisode repris (ex 41) était introuvable
                    //   → currentIndex restait 0 → hasPreviousEpisode()=false. Fix : si on détecte
                    //   des dossiers, on dérive la VRAIE sous-saison depuis l'id de l'épisode repris
                    //   ("slug/saison1/vf/41" → "slug/saison1/vf") et on re-fetch les vrais épisodes
                    //   (mode saison directe → mêmes IDs que l'épisode stocké → match par ID OK).
                    //   Ne se déclenche QUE pour le cas dossier (marqueur "@subfolder:" propre à
                    //   AnimeSama) → zéro impact sur les autres providers.
                    val looksLikeFolders = fetchedEpisodes.isNotEmpty() && fetchedEpisodes.all {
                        it.id.startsWith("@subfolder:") || it.overview == "@subfolder"
                    }
                    if (looksLikeFolders) {
                        val subSeasonId = type.id.substringBeforeLast("/", "")
                        if (subSeasonId.isNotBlank() && subSeasonId != type.id) {
                            fetchedEpisodes = runCatching {
                                provider.getEpisodesBySeason(subSeasonId)
                            }.getOrDefault(emptyList()).filterNot {
                                it.id.startsWith("@subfolder:") || it.overview == "@subfolder"
                            }
                        } else {
                            fetchedEpisodes = emptyList()
                        }
                    }
                    if (fetchedEpisodes.isNotEmpty()) {
                        fetchedEpisodes.forEach { episode ->
                            episode.tvShow = episode.tvShow ?: tvShow
                            episode.season = episode.season ?: season
                        }
                        withContext(Dispatchers.IO) {
                            database.episodeDao().insertAll(fetchedEpisodes)
                        }
                        episodesFromDb = fetchedEpisodes
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (episodesFromDb.isNotEmpty()) {
            episodesFromDb.forEach { episode ->
                episode.tvShow = episode.tvShow ?: tvShowContext
                episode.season = episode.season ?: seasonContext
            }
            addEpisodes(convertToVideoTypeEpisodes(episodesFromDb, database, seasonNumber))
        }
    }

    suspend fun ensureNextEpisodeAvailable(type: Video.Type.Episode, database: AppDatabase): Boolean {
        if (hasNextEpisode()) return true

        val currentEpisode = getCurrentEpisode()
            ?.takeIf { current -> current.id == type.id }
            ?: type
        val provider = UserPreferences.currentProvider ?: return false
        val tvShowId = currentEpisode.tvShow.id
        val currentSeasonNumber = currentEpisode.season.number

        fun nextSeasonFrom(seasons: List<Season>): Season? =
            seasons
                .filter { season -> season.number > currentSeasonNumber }
                .sortedBy { season -> season.number }
                .firstOrNull()

        var nextSeason = withContext(Dispatchers.IO) {
            nextSeasonFrom(database.seasonDao().getByTvShowId(tvShowId))
        }

        if (nextSeason == null) {
            runCatching { provider.getTvShow(tvShowId) }
                .getOrNull()
                ?.also { tvShow ->
                    withContext(Dispatchers.IO) {
                        database.tvShowDao().save(tvShow)
                        tvShow.seasons.forEach { season ->
                            season.tvShow = tvShow
                        }
                        database.seasonDao().insertAll(tvShow.seasons)
                    }
                    nextSeason = nextSeasonFrom(tvShow.seasons)
                }
        }

        val seasonToLoad = nextSeason ?: return false
        var nextSeasonEpisodes = withContext(Dispatchers.IO) {
            database.episodeDao()
                .getByTvShowIdAndSeasonNumber(tvShowId, seasonToLoad.number)
        }

        if (nextSeasonEpisodes.isEmpty() && seasonToLoad.id.isNotBlank()) {
            nextSeasonEpisodes = runCatching {
                provider.getEpisodesBySeason(seasonToLoad.id)
            }.getOrDefault(emptyList()).also { fetchedEpisodes ->
                if (fetchedEpisodes.isNotEmpty()) {
                    fetchedEpisodes.forEach { episode ->
                        episode.tvShow = episode.tvShow ?: seasonToLoad.tvShow
                        episode.season = episode.season ?: seasonToLoad
                    }
                    withContext(Dispatchers.IO) {
                        database.episodeDao().insertAll(fetchedEpisodes)
                    }
                }
            }
        }

        if (nextSeasonEpisodes.isEmpty()) return false

        nextSeasonEpisodes.forEach { episode ->
            episode.tvShow = episode.tvShow ?: seasonToLoad.tvShow
            episode.season = episode.season ?: seasonToLoad
        }

        mergeEpisodes(convertToVideoTypeEpisodes(nextSeasonEpisodes, database, seasonToLoad.number))
        return hasNextEpisode()
    }
    /** 2026-06-20 (user "quand on regarde une série et qu'on fait gauche
     *  ça affiche tous les épisodes") : accès read-only à la liste pour le
     *  panel latéral de PlayerTvFragment + bottom-sheet PlayerMobileFragment. */
    fun getAllEpisodes(): List<Episode> = episodes.toList()

    fun clearEpisodes(){
        episodes.clear()
        currentIndex = 0
    }
    fun setCurrentEpisode(episode: Episode) {
        var index = episodes.indexOfFirst { it.id == episode.id }
        if (index < 0) {
            // 2026-06-30 (user "AnimeSama/FrenchManga : à la reprise après fermeture
            //   de l'appli, la flèche 'épisode précédent' est absente ; elle réapparaît
            //   quand on passe au suivant") : à la reprise à froid, ces providers
            //   re-fetchent la liste via un wrapper VF/VOSTFR → les IDs diffèrent de
            //   l'épisode courant stocké → indexOfFirst par ID échoue → currentIndex
            //   reste 0 → hasPreviousEpisode()=false. Repli : matcher par NUMÉRO
            //   d'épisode (+ saison si connue) pour retrouver le bon index.
            val targetNum = episode.number
            val targetSeason = episode.season?.number
            if (targetNum > 0) {
                index = episodes.indexOfFirst { e ->
                    e.number == targetNum &&
                        (targetSeason == null || e.season?.number == null || e.season?.number == targetSeason)
                }
            }
        }
        if (index >= 0) {
            currentIndex = index
        }
    }

    fun getCurrentEpisode(): Episode? =
        episodes.getOrNull(currentIndex)

    fun peekNextEpisode(): Episode? =
        episodes.getOrNull(currentIndex + 1)

    fun getNextEpisode(): Episode? {
        if (currentIndex + 1 < episodes.size) {
            currentIndex++
            return episodes[currentIndex]
        }
        return null
    }
    fun getPreviousEpisode(): Episode? {
        if (currentIndex -1 >= 0){
            currentIndex--
            return episodes[currentIndex]
        }
        return null
    }
    fun hasPreviousEpisode(): Boolean {
        return currentIndex > 0
    }

    fun hasNextEpisode(): Boolean {
        return currentIndex < episodes.size - 1
    }

    fun listIsEmpty(episode: Episode): Boolean{
        return episodes.isEmpty() || episodes.none { it.id == episode.id }
    }

    suspend fun convertToVideoTypeEpisodes(episodes: List<com.streamflixreborn.streamflix.models.Episode>, database: AppDatabase, seasonNumber: Int): List<Episode> {
        // Pre-load all needed seasons and tvShows in one batch on IO thread
        val seasonIds = episodes.mapNotNull { it.season?.id }.distinct()
        val tvShowIds = episodes.mapNotNull { it.tvShow?.id }.distinct()
        val (seasonsMap, tvShowsMap) = withContext(Dispatchers.IO) {
            val seasons = seasonIds.mapNotNull { id -> database.seasonDao().getById(id)?.let { id to it } }.toMap()
            val tvShows = tvShowIds.mapNotNull { id -> database.tvShowDao().getById(id)?.let { id to it } }.toMap()
            seasons to tvShows
        }

        val videoEpisodes = episodes.map { ep ->
            val seasonId = ep.season?.id ?: ""
            val tvShowId = ep.tvShow?.id ?: ""
            val seasonFromDb = seasonsMap[seasonId]
            val tvShowFromDb = tvShowsMap[tvShowId]
            val tvShowTitle = tvShowFromDb?.title?.takeUnless { it.isBlank() }
                ?: ep.tvShow?.title
                ?: ""
            Episode(
                id = ep.id,
                number = ep.number,
                title = ep.title,
                poster = ep.poster,
                overview = ep.overview,
                tvShow = Episode.TvShow(
                    id = tvShowId,
                    title = tvShowTitle,
                    poster = tvShowFromDb?.poster ?: ep.tvShow?.poster,
                    banner = tvShowFromDb?.banner ?: ep.tvShow?.banner,
                    releaseDate = tvShowFromDb?.released?.format("yyyy-MM-dd") ?: ep.tvShow?.released?.format("yyyy-MM-dd"),
                    imdbId = tvShowFromDb?.imdbId ?: ep.tvShow?.imdbId
                ),
                season = Episode.Season(
                    number = seasonFromDb?.number ?: seasonNumber,
                    title = seasonFromDb?.title ?: ep.season?.title
                )
            )
        }
        return videoEpisodes
    }



}
