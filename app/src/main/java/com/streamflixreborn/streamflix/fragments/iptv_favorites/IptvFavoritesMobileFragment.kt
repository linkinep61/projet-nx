package com.streamflixreborn.streamflix.fragments.iptv_favorites

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
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.IptvFavoritesStore
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * IPTV-only "Favoris" tab. Reuses the [FragmentTvShowsMobileBinding] grid layout
 * and pulls favorited channels from the current provider via the same
 * [com.streamflixreborn.streamflix.providers.Provider.getHome] path the home
 * page uses — so the channels look identical (same logos, same cards) and we
 * don't duplicate provider-specific resolution logic.
 *
 * Empty state is handled by simply showing nothing + a Toast suggesting how to
 * add favorites; we deliberately don't build a custom empty illustration view
 * to keep this fragment minimal.
 */
class IptvFavoritesMobileFragment : Fragment() {

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

        // Hide language tabs + mini player — neither is relevant in the Favoris view.
        binding.tabLanguage.visibility = View.GONE
        binding.miniPlayerContainer.visibility = View.GONE

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
        // Reload on each entry — favorites can change in any other tab via long-press.
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
        val provider = UserPreferences.currentProvider ?: return
        if (provider !is IptvProvider) {
            // Defensive — shouldn't reach this fragment for non-IPTV providers,
            // since the menu item is hidden. But if the user somehow arrives here
            // we just bail without crashing.
            appAdapter.submitList(emptyList())
            return
        }

        // Fast path: if the store is empty, don't even hit getHome (which can be
        // slow for OLA TV / Vegeta on cold start).
        val favoriteIds = IptvFavoritesStore.getFavorites(provider.name)
        if (favoriteIds.isEmpty()) {
            appAdapter.submitList(emptyList())
            Toast.makeText(
                requireContext(),
                "Aucun favori — long-press une chaîne pour l'ajouter",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        binding.isLoading.root.visibility = View.VISIBLE
        binding.isLoading.pbIsLoading.visibility = View.VISIBLE
        binding.isLoading.gIsLoadingRetry.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val items: List<TvShow> = try {
                withContext(Dispatchers.IO) {
                    val home = provider.getHome()
                    val favCategory = home.firstOrNull { it.name == "Favoris" }
                    favCategory?.list?.filterIsInstance<TvShow>() ?: emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadFavorites failed", e)
                emptyList()
            }

            if (_binding == null) return@launch
            binding.isLoading.root.visibility = View.GONE

            items.forEach { it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM }
            appAdapter.submitList(items)
        }
    }

    companion object {
        private const val TAG = "IptvFavoritesMobile"
    }
}
