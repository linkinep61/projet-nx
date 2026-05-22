package com.streamflixreborn.streamflix.fragments.global_favorites

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.FragmentTvShowsTvBinding
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.GlobalFavorites
import kotlinx.coroutines.launch

/**
 * 2026-05-20 : "Cœur" favoris global (TV). Agrège les favoris (films + séries) de
 * TOUS les providers non-IPTV dans une grille VerticalGridView. Le clic est géré
 * dans MovieViewHolder / TvShowViewHolder (cas GlobalFavoritesTvFragment).
 */
class GlobalFavoritesTvFragment : Fragment() {

    private var _binding: FragmentTvShowsTvBinding? = null
    private val binding get() = _binding!!

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvShowsTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tabLanguage.visibility = View.GONE

        binding.vgvTvShows.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setHasFixedSize(true)
        }

        loadFavorites()
    }

    override fun onResume() {
        super.onResume()
        loadFavorites()
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

            movies.forEach { it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM }
            tvShows.forEach { it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM }
            // 2026-05-21 : saisons favorites (toggle par appui long sur une saison),
            //   présentées comme des cartes "Série — Saison N" dans le même écran.
            val seasonFavs = com.streamflixreborn.streamflix.utils.SeasonFavorites.all().map { e ->
                TvShow(
                    id = e.syntheticId(),
                    title = "${e.showTitle} — Saison ${e.seasonNumber}",
                    poster = e.showPoster,
                    banner = e.showBanner,
                ).apply { itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM }
            }
            val items = (movies + tvShows + seasonFavs)
            appAdapter.submitList(items)
            binding.vgvTvShows.visibility = View.VISIBLE

            if (items.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "Aucun favori — ajoute des films/séries en favori dans les providers",
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
        private const val TAG = "GlobalFavoritesTv"
    }
}
