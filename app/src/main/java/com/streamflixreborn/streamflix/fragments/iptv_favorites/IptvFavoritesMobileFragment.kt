package com.streamflixreborn.streamflix.fragments.iptv_favorites

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.FragmentTvShowsMobileBinding
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.IptvFavoritesStore
import com.streamflixreborn.streamflix.utils.MiniPlayerController
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
 * 2026-05-10 : ajout du mini-player (cohérent avec le tab "Chaînes TV"). Cliquer
 * une chaîne favorite l'ouvre dans le mini-player, contrôles pause/close/full.
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

        initializeMiniPlayer()
        loadFavorites()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            val channelId = MiniPlayerController.currentChannelId
            if (channelId != null && UserPreferences.miniPlayerEnabled) {
                if (MiniPlayerController.getPlayer() == null) {
                    MiniPlayerController.initPlayer(requireContext())
                }
                binding.miniPlayerView.player = MiniPlayerController.getPlayer()
                binding.miniPlayerContainer.visibility = View.VISIBLE
                binding.miniPlayerChannelName.text = MiniPlayerController.currentChannelName ?: ""
                MiniPlayerController.currentChannelPoster?.let { poster ->
                    Glide.with(this).load(poster).into(binding.miniPlayerChannelLogo)
                }
            }
            if (MiniPlayerController.onIptvChannelClick == null) {
                MiniPlayerController.onIptvChannelClick = { tvShow ->
                    if (tvShow.id == MiniPlayerController.currentChannelId) {
                        MiniPlayerController.stopAsync()
                        false
                    } else if (tvShow.id.startsWith("bxt::")) {
                        false
                    } else {
                        MiniPlayerController.playChannel(tvShow.id, tvShow.title, tvShow.poster)
                        true
                    }
                }
            }
        }
        loadFavorites()
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            binding.miniPlayerView.player = null
        }
        MiniPlayerController.releaseDetachedPlayer()
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

    private fun initializeMiniPlayer() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        if (!isIptv || !UserPreferences.miniPlayerEnabled) {
            binding.miniPlayerContainer.visibility = View.GONE
            return
        }

        MiniPlayerController.initPlayer(requireContext())
        binding.miniPlayerView.player = MiniPlayerController.getPlayer()

        if (MiniPlayerController.currentChannelId != null) {
            binding.miniPlayerContainer.visibility = View.VISIBLE
            binding.miniPlayerChannelName.text = MiniPlayerController.currentChannelName ?: ""
            MiniPlayerController.currentChannelPoster?.let { poster ->
                Glide.with(this).load(poster).into(binding.miniPlayerChannelLogo)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MiniPlayerController.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                if (_binding == null) return@collect
                when (state) {
                    is MiniPlayerController.State.Idle -> {
                        binding.miniPlayerContainer.visibility = View.GONE
                    }
                    is MiniPlayerController.State.Loading -> {
                        binding.miniPlayerContainer.visibility = View.VISIBLE
                        binding.miniPlayerChannelName.text = state.channelName
                        binding.miniPlayerLoading.visibility = View.VISIBLE
                    }
                    is MiniPlayerController.State.Playing -> {
                        binding.miniPlayerContainer.visibility = View.VISIBLE
                        binding.miniPlayerChannelName.text = state.channelName
                        binding.miniPlayerLoading.visibility = View.GONE
                        updatePauseButton()
                        state.channelPoster?.let { poster ->
                            Glide.with(this@IptvFavoritesMobileFragment).load(poster).into(binding.miniPlayerChannelLogo)
                        }
                    }
                    is MiniPlayerController.State.Error -> {
                        binding.miniPlayerLoading.visibility = View.GONE
                        Log.e(TAG, "Mini player error: ${state.message}")
                    }
                }
            }
        }

        binding.miniPlayerClose.setOnClickListener { MiniPlayerController.stop() }
        binding.miniPlayerPause.setOnClickListener {
            MiniPlayerController.togglePause()
            updatePauseButton()
        }
        binding.miniPlayerFullscreen.setOnClickListener { navigateToFullPlayer() }
        binding.miniPlayerView.setOnClickListener { navigateToFullPlayer() }

        MiniPlayerController.onIptvChannelClick = { tvShow ->
            if (tvShow.id == MiniPlayerController.currentChannelId) {
                MiniPlayerController.stopAsync()
                false
            } else if (tvShow.id.startsWith("bxt::")) {
                false
            } else {
                Log.d(TAG, "Mini player intercept (favorites): ${tvShow.title} (${tvShow.id})")
                MiniPlayerController.playChannel(tvShow.id, tvShow.title, tvShow.poster)
                true
            }
        }
    }

    private fun updatePauseButton() {
        if (_binding == null) return
        val icon = if (MiniPlayerController.isPaused()) {
            R.drawable.ic_mini_player_play
        } else {
            R.drawable.ic_mini_player_pause
        }
        binding.miniPlayerPause.setImageResource(icon)
    }

    private fun navigateToFullPlayer() {
        if (!isAdded || _binding == null) return
        val channelId = MiniPlayerController.currentChannelId ?: return
        val channelName = MiniPlayerController.currentChannelName ?: channelId
        val channelPoster = MiniPlayerController.currentChannelPoster

        val videoType = Video.Type.Episode(
            id = channelId, number = 1, title = channelName, poster = channelPoster,
            overview = null,
            tvShow = Video.Type.Episode.TvShow(
                id = channelId, title = channelName, poster = channelPoster,
                banner = null, releaseDate = null, imdbId = null
            ),
            season = Video.Type.Episode.Season(number = 1, title = "Live"),
        )

        val args = Bundle().apply {
            putString("id", channelId)
            putString("title", channelName)
            putString("subtitle", channelName)
            putSerializable("videoType", videoType)
        }
        try {
            findNavController().navigate(R.id.action_global_player, args)
        } catch (e: Exception) {
            Log.e(TAG, "navigateToFullPlayer failed: ${e.message}", e)
        }
    }

    private fun loadFavorites() {
        val provider = UserPreferences.currentProvider ?: return
        if (provider !is IptvProvider) {
            appAdapter.submitList(emptyList())
            return
        }

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
