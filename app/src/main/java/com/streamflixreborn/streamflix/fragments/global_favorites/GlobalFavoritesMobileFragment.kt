package com.streamflixreborn.streamflix.fragments.global_favorites

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.FragmentTvShowsMobileBinding
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.EpisodeFavorites
import com.streamflixreborn.streamflix.utils.GlobalFavorites
import com.streamflixreborn.streamflix.utils.dp
import kotlinx.coroutines.launch

/**
 * 2026-05-20 : "Cœur" favoris global — agrège les favoris (films + séries) de
 * TOUS les providers non-IPTV dans une seule grille. Réutilise la grille
 * [FragmentTvShowsMobileBinding]. Le clic sur un item est géré dans
 * MovieViewHolder / TvShowViewHolder (cas GlobalFavoritesMobileFragment) :
 * bascule sur le provider d'origine puis ouvre la fiche.
 */
class GlobalFavoritesMobileFragment : Fragment() {

    private var _binding: FragmentTvShowsMobileBinding? = null
    private val binding get() = _binding!!

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvShowsMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tabLanguage.visibility = View.GONE

        binding.rvTvShows.apply {
            setHasFixedSize(true)
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 6 else 3
            (layoutManager as? GridLayoutManager)?.spanCount = spanCount
            addItemDecoration(SpacingItemDecoration(10.dp(requireContext())))
        }

        loadFavorites()
    }

    override fun onResume() {
        super.onResume()
        loadFavorites()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val spanCount = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) 6 else 3
        (binding.rvTvShows.layoutManager as? GridLayoutManager)?.spanCount = spanCount
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadFavorites() {
        binding.isLoading.root.visibility = View.VISIBLE
        binding.isLoading.pbIsLoading.visibility = View.VISIBLE
        binding.isLoading.gIsLoadingRetry.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val (movies, tvShows) = try {
                GlobalFavorites.load(requireContext())
            } catch (e: Exception) {
                Log.e(TAG, "loadFavorites failed", e)
                emptyList<Movie>() to emptyList<TvShow>()
            }
            if (_binding == null) return@launch
            binding.isLoading.root.visibility = View.GONE

            movies.forEach { it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM }
            tvShows.forEach { it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM }
            // 2026-05-21 : saisons favorites
            val seasonFavs = com.streamflixreborn.streamflix.utils.SeasonFavorites.all().map { e ->
                TvShow(
                    id = e.syntheticId(),
                    title = "${e.showTitle} — Saison ${e.seasonNumber}",
                    poster = e.showPoster,
                    banner = e.showBanner,
                ).apply { itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM }
            }
            // 2026-05-22 : épisodes favoris
            val episodeFavs = EpisodeFavorites.all().map { e ->
                TvShow(
                    id = e.syntheticId(),
                    title = "${e.showTitle} — S${e.seasonNumber}E${e.episodeNumber}",
                    overview = e.episodeTitle,
                    poster = e.episodePoster ?: e.showPoster,
                    banner = e.showBanner,
                ).apply { itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM }
            }
            // 2026-05-22 : reprises de lecture films
            val cwMovies = try {
                GlobalFavorites.loadContinueWatchingMovies(requireContext(), 20)
            } catch (_: Exception) { emptyList() }
            cwMovies.forEach { it.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM }
            // 2026-05-22 : reprises de lecture séries
            val cwSeries = try {
                GlobalFavorites.loadContinueWatchingSeries(requireContext(), 20)
            } catch (_: Exception) { emptyList() }
            val cwSeriesCards = cwSeries.map { cw ->
                val title = "${cw.tvShow.title} — S${cw.seasonNumber}E${cw.episodeNumber}"
                TvShow(
                    id = "resume_series_${cw.tvShow.id}",
                    title = title,
                    overview = cw.lastEpisode.title,
                    poster = cw.tvShow.poster,
                    banner = cw.tvShow.banner,
                ).apply { itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM }
            }

            // Ordre : favoris en haut, reprises films, reprises séries
            val items = (movies + tvShows + seasonFavs + episodeFavs) + cwMovies + cwSeriesCards
            appAdapter.submitList(items)

            if (items.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "Aucun favori ni reprise de lecture",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Appui long sur un favori : le retirer + recharger la grille. */
    fun removeFavorite(itemId: String, isMovie: Boolean) {
        // 2026-05-21 : saison favorite (id synthétique) → store dédié.
        if (itemId.startsWith(com.streamflixreborn.streamflix.utils.SeasonFavorites.SYNTHETIC_ID_PREFIX)) {
            com.streamflixreborn.streamflix.utils.SeasonFavorites.removeBySyntheticId(itemId)
            Toast.makeText(requireContext(), "Saison retirée des favoris", Toast.LENGTH_SHORT).show()
            loadFavorites()
            return
        }
        // 2026-05-22 : épisode favori (id synthétique)
        if (itemId.startsWith(EpisodeFavorites.SYNTHETIC_ID_PREFIX)) {
            EpisodeFavorites.removeBySyntheticId(itemId)
            Toast.makeText(requireContext(), "Épisode retiré des favoris", Toast.LENGTH_SHORT).show()
            loadFavorites()
            return
        }
        // 2026-05-22 : reprises de lecture → masquer du cœur
        if (itemId.startsWith("resume_")) {
            GlobalFavorites.dismissContinueWatching(itemId)
            Toast.makeText(requireContext(), "Reprise retirée", Toast.LENGTH_SHORT).show()
            loadFavorites()
            return
        }
        // 2026-05-22 : reprises de lecture films (id original sans préfixe resume_)
        // → vérifier si c'est un film en reprise via originByItemId
        if (isMovie && GlobalFavorites.originByItemId.containsKey("resume_movie_$itemId")) {
            GlobalFavorites.dismissContinueWatching("resume_movie_$itemId")
            Toast.makeText(requireContext(), "Reprise retirée", Toast.LENGTH_SHORT).show()
            loadFavorites()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = GlobalFavorites.removeFavorite(requireContext(), itemId, isMovie)
            if (_binding == null) return@launch
            if (ok) {
                Toast.makeText(requireContext(), "Retiré des favoris", Toast.LENGTH_SHORT).show()
                loadFavorites()
            }
        }
    }

    companion object {
        private const val TAG = "GlobalFavoritesMobile"
    }
}
