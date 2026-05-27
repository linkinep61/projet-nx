package com.streamflixreborn.streamflix.fragments.global_favorites

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
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.EpisodeFavorites
import com.streamflixreborn.streamflix.utils.GlobalFavorites
import kotlinx.coroutines.launch

/**
 * 2026-05-20 : "Cœur" favoris global — agrège les favoris (films + séries) de
 * TOUS les providers non-IPTV.
 * 2026-05-24 : refactoré en sections Category horizontales (comme le home) :
 *   Films, Séries, Saisons, Épisodes, Reprendre.
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

        // 2026-05-24 : spanCount=1 pour afficher des Category (bandeaux horizontaux).
        //   Chaque Category gère son propre RecyclerView horizontal interne.
        binding.rvTvShows.apply {
            setHasFixedSize(true)
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            (layoutManager as? GridLayoutManager)?.spanCount = 1
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

            // --- Préparer les items par catégorie ---
            // 2026-05-24 : utiliser les types du HOME (MOBILE_ITEM), PAS les
            //   GRID_MOBILE_ITEM qui sont faits pour une grille plate 3 colonnes.

            // Films — injecter le providerName d'origine pour que le clic
            // switch au bon provider (sinon la fiche s'ouvre sur le provider
            // actif qui ne connaît pas forcément ce film → clic mort).
            movies.forEach {
                it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                if (it.providerName.isNullOrBlank()) {
                    it.providerName = GlobalFavorites.originByItemId[it.id]
                }
            }

            // Séries
            tvShows.forEach {
                it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                if (it.providerName.isNullOrBlank()) {
                    it.providerName = GlobalFavorites.originByItemId[it.id]
                }
            }

            // Saisons favorites
            val seasonFavs = com.streamflixreborn.streamflix.utils.SeasonFavorites.all().map { e ->
                TvShow(
                    id = e.syntheticId(),
                    title = "${e.showTitle} — Saison ${e.seasonNumber}",
                    poster = e.showPoster,
                    banner = e.showBanner,
                ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
            }

            // Épisodes favoris — poster SÉRIE + titre "Série — S1E3"
            val episodeFavs = EpisodeFavorites.all().map { e ->
                TvShow(
                    id = e.syntheticId(),
                    title = "${e.showTitle} — S${e.seasonNumber}E${e.episodeNumber}",
                    overview = e.episodeTitle,
                    poster = e.showPoster ?: e.episodePoster,
                    banner = e.showBanner,
                ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
            }

            // Reprises de lecture
            val cwMovies = try {
                GlobalFavorites.loadContinueWatchingMovies(requireContext(), 20)
            } catch (_: Exception) { emptyList() }
            cwMovies.forEach { it.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM }
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
                ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
            }

            // --- Construire les sections Category (comme le home) ---
            val categories = mutableListOf<Category>()
            if (movies.isNotEmpty()) {
                categories += Category(name = "Films", list = movies).apply {
                    itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
                }
            }
            if (tvShows.isNotEmpty()) {
                categories += Category(name = "Séries", list = tvShows).apply {
                    itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
                }
            }
            if (seasonFavs.isNotEmpty()) {
                categories += Category(name = "Saisons", list = seasonFavs).apply {
                    itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
                }
            }
            if (episodeFavs.isNotEmpty()) {
                categories += Category(name = "Épisodes", list = episodeFavs).apply {
                    itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
                }
            }
            val resumeItems: List<AppAdapter.Item> = cwMovies + cwSeriesCards
            if (resumeItems.isNotEmpty()) {
                categories += Category(name = "Reprendre", list = resumeItems).apply {
                    itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
                }
            }

            if (_binding == null) return@launch
            appAdapter.submitList(categories)

            if (categories.isEmpty()) {
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
