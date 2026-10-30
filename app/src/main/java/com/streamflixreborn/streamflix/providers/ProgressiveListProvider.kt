package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.flow.Flow

/**
 * 2026-10-30 (user : « il faut un affichage progressif ») : comme ProgressiveHomeProvider,
 *  mais pour les onglets Films / Séries. Le flow émet la liste CUMULÉE d'une page à chaque
 *  source qui répond (la plus rapide s'affiche tout de suite, les autres s'ajoutent).
 *  MoviesViewModel / TvShowsViewModel l'utilisent pour la 1re page sans filtre ni genre.
 */
interface ProgressiveListProvider : Provider {
    fun getMoviesProgressive(page: Int): Flow<List<Movie>>
    fun getTvShowsProgressive(page: Int): Flow<List<TvShow>>
}
