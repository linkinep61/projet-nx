package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
// ⚠ `w500` / `w1280` sont des extensions de TMDb3 : sans ces imports, elles ne résolvent pas.
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════════════════════════════════════════
 *  « POUR VOUS » — suggestions bâties sur ce qui vient d'être regardé  (2026-08-06)
 * ══════════════════════════════════════════════════════════════════════════════════════════
 *
 *  Demande d'un testeur, arbitrée avec le user : une rangée qui se construit toute seule au
 *  fil des visionnages, placée JUSTE SOUS « Continuer à regarder », et réinitialisable depuis
 *  les réglages d'affichage.
 *
 *  Trois principes repris de Netflix, parce qu'ils font la différence entre une rangée utile
 *  et une rangée qu'on ignore :
 *
 *   1. **On part de PLUSIEURS titres récents, pas d'un seul.** Sinon, quelqu'un qui vient de
 *      voir un polar reçoit vingt polars. En puisant dans les quatre derniers, une personne
 *      qui a regardé une série policière ET un dessin animé retrouve les deux.
 *   2. **On alterne les sources.** Les recommandations sont entrelacées (une du 1er titre,
 *      une du 2e, une du 3e…) au lieu d'être mises bout à bout : le haut de la rangée est
 *      donc varié dès le premier écran, là où l'utilisateur regarde vraiment.
 *   3. **On n'y remet jamais ce qui est déjà vu ni ce qui est déjà à l'écran.** Une suggestion
 *      qu'on a déjà terminée, ou qui figure déjà dans « Continuer à regarder », détruit la
 *      confiance dans la rangée entière.
 *
 *  Coût : QUATRE appels TMDB en parallèle, cinq titres retenus par appel. Vingt titres au
 *  total — l'ordre de grandeur de ce que Netflix laisse voir avant de devoir insister, sans
 *  ralentir l'accueil sur une box modeste.
 *
 *  ⚠ Réservé aux providers TMDB (Movix, Cloudstream, NetMirror, TMDb). Ailleurs, les titres
 *    n'ont pas d'identifiant TMDB : la rangée resterait vide, mieux vaut ne pas la promettre.
 */
object SuggestionsPourVous {

    private const val TAG = "SuggestionsPourVous"

    /** Nombre de titres récents utilisés comme graines. */
    private const val GRAINES = 4

    /** Suggestions retenues par graine. 4 × 5 = 20. */
    private const val PAR_GRAINE = 5

    /** Taille finale de la rangée. */
    const val TAILLE_RANGEE = 20

    /**
     * Construit la rangée.
     *
     * @param recents  ce qui vient d'être regardé, le plus récent en tête (déjà trié par
     *                 l'appelant, qui possède la même liste pour « Continuer à regarder »).
     * @param aExclure identifiants TMDB déjà présents ailleurs sur l'accueil ou déjà terminés.
     */
    suspend fun construire(
        recents: List<AppAdapter.Item>,
        aExclure: Set<String>,
    ): List<AppAdapter.Item> = withContext(Dispatchers.IO) {
        if (!UserPreferences.suggestionsPourVous) return@withContext emptyList()

        // Réinitialisation : on ignore tout ce qui a été regardé AVANT la remise à zéro.
        val depuis = UserPreferences.suggestionsReinitialiseesLe
        val retenus = if (depuis <= 0L) recents else recents.filter { item ->
            val vuLe = when (item) {
                is Movie -> item.watchHistory?.lastEngagementTimeUtcMillis
                is Episode -> item.watchHistory?.lastEngagementTimeUtcMillis
                else -> null
            } ?: 0L
            vuLe > depuis
        }

        // Une graine = un identifiant TMDB + son type. Pour un épisode, c'est la SÉRIE qui
        //   nous intéresse : les recommandations d'un épisode n'existent pas côté TMDB.
        val graines = retenus.mapNotNull { item ->
            when (item) {
                is Movie -> item.id.toIntOrNull()?.let { it to false }
                is TvShow -> item.id.toIntOrNull()?.let { it to true }
                is Episode -> item.tvShow?.id?.toIntOrNull()?.let { it to true }
                else -> null
            }
        }.distinct().take(GRAINES)

        if (graines.isEmpty()) return@withContext emptyList()

        val parGraine = coroutineScope {
            graines.map { (id, estSerie) ->
                async {
                    runCatching { recommandations(id, estSerie) }
                        .onFailure { Log.w(TAG, "recommandations KO pour $id : ${it.message}") }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        // Entrelacement : une suggestion de chaque graine, à tour de rôle. C'est ce qui rend
        //   le DÉBUT de la rangée varié — la seule partie que la plupart des gens voient.
        val entrelace = mutableListOf<AppAdapter.Item>()
        var rang = 0
        while (entrelace.size < TAILLE_RANGEE && rang < PAR_GRAINE) {
            for (liste in parGraine) {
                liste.getOrNull(rang)?.let { entrelace.add(it) }
                if (entrelace.size >= TAILLE_RANGEE) break
            }
            rang++
        }

        val dejaVu = aExclure + graines.map { it.first.toString() }
        entrelace
            .distinctBy { it.identifiant() }
            .filterNot { it.identifiant() in dejaVu }
            .take(TAILLE_RANGEE)
            .also { Log.d(TAG, "rangée « Pour vous » : ${it.size} titres depuis ${graines.size} graines") }
    }

    private fun AppAdapter.Item.identifiant(): String = when (this) {
        is Movie -> id
        is TvShow -> id
        else -> ""
    }

    /** Recommandations TMDB d'un titre, converties en éléments d'accueil. */
    private suspend fun recommandations(id: Int, estSerie: Boolean): List<AppAdapter.Item> {
        val resultats = if (estSerie) {
            TMDb3.TvSeries.details(
                seriesId = id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS),
            ).recommendations?.results
        } else {
            TMDb3.Movies.details(
                movieId = id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS),
            ).recommendations?.results
        } ?: return emptyList()

        // ⚠ `TMDb3.Movie` / `TMDb3.Tv` HÉRITENT de `MultiItem`, elles ne sont pas imbriquées
        //   dedans. Et il faut les qualifier : `Movie` tout court désigne ici notre modèle.
        return resultats.mapNotNull { item ->
            when (item) {
                is TMDb3.Movie -> Movie(
                    id = item.id.toString(),
                    title = item.title,
                    overview = item.overview,
                    released = item.releaseDate,
                    rating = item.voteAverage.toDouble(),
                    poster = item.posterPath?.w500,
                    banner = item.backdropPath?.w1280,
                )

                is TMDb3.Tv -> TvShow(
                    id = item.id.toString(),
                    title = item.name,
                    overview = item.overview,
                    released = item.firstAirDate,
                    rating = item.voteAverage.toDouble(),
                    poster = item.posterPath?.w500,
                    banner = item.backdropPath?.w1280,
                )

                else -> null
            }
        }.take(PAR_GRAINE)
    }
}
