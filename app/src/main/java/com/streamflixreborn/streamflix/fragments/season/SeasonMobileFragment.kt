package com.streamflixreborn.streamflix.fragments.season

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentSeasonMobileBinding
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class SeasonMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentSeasonMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<SeasonMobileFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory {
        SeasonViewModel(
            args.seasonId,
            args.tvShowId,
            database
        )
    }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSeasonMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSeason()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    SeasonViewModel.State.LoadingEpisodes -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is SeasonViewModel.State.SuccessLoadingEpisodes -> {
                        displaySeason(state.episodes)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is SeasonViewModel.State.FailedLoadingEpisodes -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getSeasonEpisodes(args.seasonId)
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                            binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                                val doRetry = { viewModel.getSeasonEpisodes(args.seasonId) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    doRetry()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun initializeSeason() {
        binding.tvSeasonTitle.text = args.seasonTitle

        binding.rvEpisodes.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
        }
    }

    private var episodeLoadJob: kotlinx.coroutines.Job? = null
    private var loadedSignature: String? = null

    private fun displaySeason(episodes: List<Episode>) {
        val preparedEpisodes = episodes.onEach { episode ->
            episode.itemType = AppAdapter.Type.EPISODE_MOBILE_ITEM
        }
        // Le combine ré-émet à chaque changement DB : on évite de tout recharger si rien
        // n'a changé (pas de re-render coûteux ni de saut de scroll).
        val signature = "${episodes.size}:${episodes.firstOrNull()?.id}:${episodes.lastOrNull()?.id}"
        if (signature == loadedSignature) return

        val episodeIndex = episodes
            .sortedByDescending { it.watchHistory?.lastEngagementTimeUtcMillis }
            .firstOrNull { it.watchHistory != null }
            ?.let { episodes.indexOf(it) }
            ?: episodes.indexOfLast { it.isWatched }
                .takeIf { it != -1 && it + 1 < episodes.size }
                ?.let { it + 1 }

        fun focusTarget() {
            if (episodeIndex != null) {
                val layoutManager = binding.rvEpisodes.layoutManager as? LinearLayoutManager
                layoutManager?.scrollToPositionWithOffset(
                    episodeIndex,
                    binding.rvEpisodes.height / 2 - 100.dp(requireContext())
                )
            }
        }

        episodeLoadJob?.cancel()

        // Petites saisons : envoi direct. Grosses séries (One Piece, 1000+ épisodes) :
        //   CHARGEMENT PROGRESSIF par lots → plus de blocage du thread principal = plus d'ANR.
        if (preparedEpisodes.size <= 60) {
            appAdapter.submitList(preparedEpisodes)
            loadedSignature = signature
            focusTarget()
            return
        }

        episodeLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            appAdapter.clearItems()
            val chunkSize = 24
            var i = 0
            var scrolled = false
            while (i < preparedEpisodes.size) {
                val end = minOf(i + chunkSize, preparedEpisodes.size)
                appAdapter.appendItems(preparedEpisodes.subList(i, end))
                i = end
                if (!scrolled && (episodeIndex == null || episodeIndex < i)) {
                    focusTarget()
                    scrolled = true
                }
                kotlinx.coroutines.delay(16)
            }
            loadedSignature = signature
        }
    }
}