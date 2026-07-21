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

    // ─────────────────────────────────────────────────────────────────────────────
    // 2026-07-20 (testeur « One Piece » : Classroom of the Elite, S2E13 → aucune
    //   détection des saisons suivantes ; S3E1 lancée via l'icône saisons → le
    //   lecteur ne relie plus rien, « suivant » inactif et l'icône saisons ne
    //   trouve rien) :
    //
    //   CAUSE — sur AnimeSama, quand une série existe en VF **et** VOSTFR,
    //   `getTvShow` ne renvoie PAS les vraies saisons mais 2 ENVELOPPES de langue
    //   (`slug/@vostfr/…` numérotée 1, `slug/@vf/…` numérotée 2). Or l'épisode en
    //   cours porte le VRAI numéro de saison (3). Donc
    //   `tvShow.seasons.find { it.number == 3 }` → null → aucun épisode chargé,
    //   et `ensureNextEpisodeAvailable` compare 3 aux numéros factices 1/2 → rien.
    //
    //   FIX — on dérive la vraie saison depuis l'ID de l'épisode (format AnimeSama
    //   `slug/saison3/vostfr/1`), et on sait déplier une enveloppe de langue pour
    //   obtenir la liste des VRAIES sous-saisons.
    // ─────────────────────────────────────────────────────────────────────────────

    private val ANIMESAMA_TAIL = Regex("""/(vostfr|vf|va|vo)/\d+$""", RegexOption.IGNORE_CASE)

    /** `slug/saison3/vostfr/1` → `slug/saison3/vostfr` (= un Season.id AnimeSama valide). */
    private fun animeSamaSeasonIdOf(episodeId: String): String? =
        if (ANIMESAMA_TAIL.containsMatchIn(episodeId)) episodeId.substringBeforeLast("/") else null

    /** Vrai numéro de saison lu dans l'ID (`saison3` → 3), indépendant des enveloppes de langue. */
    private fun animeSamaSeasonNumberOf(episodeId: String): Int? =
        Regex("""(?i)saison\s*(\d+)""").find(episodeId)?.groupValues?.get(1)?.toIntOrNull()

    private fun animeSamaLangOf(episodeId: String): String? =
        ANIMESAMA_TAIL.find(episodeId)?.groupValues?.get(1)?.lowercase()

    /**
     * Déplie les enveloppes de langue AnimeSama pour obtenir les VRAIES sous-saisons.
     * `getEpisodesBySeason("slug/@vostfr/…")` retourne des pseudo-épisodes `@subfolder:` dont
     * l'ID est le vrai Season.id et le numéro le vrai numéro de saison.
     * Les Film/OAV sont numérotés 900+ par le provider → on les exclut du chaînage.
     */
    private suspend fun resolveAnimeSamaSubSeasons(
        provider: com.streamflixreborn.streamflix.providers.Provider,
        tvShowId: String,
        episodeId: String,
    ): List<Season> {
        val lang = animeSamaLangOf(episodeId) ?: return emptyList()
        val tvShow = runCatching { provider.getTvShow(tvShowId) }.getOrNull() ?: return emptyList()
        val wrappers = tvShow.seasons.filter { it.id.contains("/@$lang/", ignoreCase = true) }
            .ifEmpty { tvShow.seasons.filter { it.id.contains("/@") } }
        if (wrappers.isEmpty()) return emptyList()

        val out = mutableListOf<Season>()
        for (wrapper in wrappers) {
            val subs = runCatching { provider.getEpisodesBySeason(wrapper.id) }
                .getOrDefault(emptyList())
            subs.filter { it.id.startsWith("@subfolder:") }.forEach { pseudo ->
                val subSeasonId = pseudo.id.removePrefix("@subfolder:")
                // On ne garde que les sous-saisons de la langue en cours de lecture.
                if (subSeasonId.endsWith("/$lang", ignoreCase = true)) {
                    out.add(
                        Season(
                            id = subSeasonId,
                            number = pseudo.number,
                            title = pseudo.title ?: "Saison ${pseudo.number}",
                        ).apply { this.tvShow = tvShow },
                    )
                }
            }
        }
        return out.filter { it.number in 1..899 }.distinctBy { it.number }
    }

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
                // 2026-07-20 : si la saison courante n'est pas dans `tvShow.seasons` (cas
                //   AnimeSama VF+VOSTFR : ce sont des enveloppes de langue numérotées 1/2, donc
                //   la saison 3 est introuvable), on dérive son ID directement depuis l'épisode.
                val seasonIdToFetch = season?.id ?: animeSamaSeasonIdOf(type.id)
                if (seasonIdToFetch != null) {
                    var fetchedEpisodes = provider.getEpisodesBySeason(seasonIdToFetch)
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
                            episode.season = episode.season ?: (season ?: seasonContext)
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
        // 2026-07-20 : le numéro porté par l'objet Season peut être un numéro d'ENVELOPPE de
        //   langue AnimeSama (1=VOSTFR, 2=VF). L'ID de l'épisode, lui, contient le vrai numéro
        //   (`slug/saison3/vostfr/1`) → on le privilégie, sinon le chaînage compare 3 à 1/2.
        val currentSeasonNumber = animeSamaSeasonNumberOf(currentEpisode.id)
            ?: currentEpisode.season.number

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

        // 2026-07-20 : dernier recours AnimeSama — les saisons du provider sont des enveloppes de
        //   langue, on les déplie pour obtenir les VRAIES sous-saisons et trouver la suivante.
        //   C'est ce qui manquait pour passer de S2E13 à S3E1 (testeur « One Piece »).
        if (nextSeason == null) {
            val realSeasons = resolveAnimeSamaSubSeasons(provider, tvShowId, currentEpisode.id)
            if (realSeasons.isNotEmpty()) nextSeason = nextSeasonFrom(realSeasons)
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
