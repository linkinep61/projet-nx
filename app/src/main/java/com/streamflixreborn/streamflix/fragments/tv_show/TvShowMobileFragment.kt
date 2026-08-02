package com.streamflixreborn.streamflix.fragments.tv_show

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.models.Video
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentTvShowMobileBinding
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.loadTvShowBanner
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class TvShowMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var hasAutoPlayed: Boolean = false

    private var _binding: FragmentTvShowMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<TvShowMobileFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory {
        TvShowViewModel(
            id = args.id,
            database = database,
            fallbackPoster = args.poster,
            fallbackBanner = args.banner,
        )
    }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvShowMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 2026-07-30 : bouton retour flottant -> menu precedent
        binding.btnBack.setOnClickListener { androidx.navigation.Navigation.findNavController(it).navigateUp() }

        initializeTvShow()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is TvShowViewModel.State.SuccessLoading -> {
                        displayTvShow(state.tvShow)
                        binding.isLoading.root.visibility = View.GONE

                        // IPTV channels: auto-play and pop this detail page
                        if (!hasAutoPlayed && isIptvChannel(state.tvShow)) {
                            hasAutoPlayed = true
                            autoPlayChannel(state.tvShow)
                        }
                    }
                    is TvShowViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getTvShow(args.id)
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
                                val doRetry = { viewModel.getTvShow(args.id) }
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
        appAdapter.onSaveInstanceState(binding.rvTvShow)
        _binding = null
    }


    private fun initializeTvShow() {
        binding.rvTvShow.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
        }
    }

    private fun isIptvChannel(tvShow: TvShow): Boolean {
        // v27 : exception pour replays TF1+/M6 séries → page detail (= liste épisodes), pas auto-play
        // 2026-06-19 : étendu aux 6 services M6+ + check isMovie (= films lancent direct)
        // 2026-06-20 : ajout tmc/ tfx/ tf1-series-films/ lci/
        if ((tvShow.id.startsWith("livehub::replay::tf1/")
            || tvShow.id.startsWith("livehub::replay::tmc/")
            || tvShow.id.startsWith("livehub::replay::tfx/")
            || tvShow.id.startsWith("livehub::replay::tf1-series-films/")
            || tvShow.id.startsWith("livehub::replay::lci/")
            || tvShow.id.startsWith("livehub::replay::m6/")
            || tvShow.id.startsWith("livehub::replay::m6replay/")
            || tvShow.id.startsWith("livehub::replay::w9replay/")
            || tvShow.id.startsWith("livehub::replay::6terreplay/")
            || tvShow.id.startsWith("livehub::replay::gulli/")
            || tvShow.id.startsWith("livehub::replay::tevareplay/")
            || tvShow.id.startsWith("livehub::replay::parispremierereplay/")
            || tvShow.id.startsWith("livehub::replay::program/")
            || tvShow.id.startsWith("livehub::replay::plexshow::")
            || tvShow.id.startsWith("livehub::replay::plutoshow::")) && !tvShow.isMovie) return false
        return tvShow.providerName == "OLA TV"
            || tvShow.providerName == "Vegeta TV"
            || tvShow.providerName == "Vavoo TV"
            || tvShow.providerName == "Sport Live"
            || tvShow.providerName == "Movix LiveTV"
            || tvShow.providerName == "TV Hub"
            || tvShow.id.startsWith("ch::")
            || tvShow.id.startsWith("sport::")
            || tvShow.id.startsWith("ola::")
            || tvShow.id.startsWith("vegeta::")
            || tvShow.id.startsWith("vavoo::")
            || tvShow.id.startsWith("sportlive::")
            || tvShow.id.startsWith("movixlivetv::")
            || tvShow.id.startsWith("livehub::")
    }

    private fun autoPlayChannel(tvShow: TvShow) {
        val videoType = Video.Type.Episode(
            id = tvShow.id,
            number = 1,
            title = tvShow.title,
            poster = tvShow.poster,
            overview = tvShow.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = tvShow.id,
                title = tvShow.title,
                poster = tvShow.poster,
                banner = tvShow.banner,
                releaseDate = null,
                imdbId = tvShow.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = 1,
                title = "Live",
            ),
        )
        val args = Bundle().apply {
            putString("id", tvShow.id)
            putString("title", tvShow.title)
            putString("subtitle", tvShow.title)
            putSerializable("videoType", videoType)
        }
        findNavController().navigate(
            com.streamflixreborn.streamflix.R.id.player,
            args,
            androidx.navigation.NavOptions.Builder()
                .setPopUpTo(com.streamflixreborn.streamflix.R.id.tv_show, true)
                .build()
        )
    }

    /**
     * Lance en sourdine la recherche des serveurs de secours, dès l'ouverture de la fiche.
     *
     * ── 2026-08-06 (user, deux fois de suite) : « le serveur que je convoite n'est pas encore
     *   arrivé ; par contre si je quitte et je reviens, il arrive. » ──────────────────────
     *   Vérifié au journal sur deux lancements consécutifs de la même série : rien n'est
     *   écarté, tout est une question de DÉLAI. À froid, FrenchStream met 34 s à rendre ses
     *   8 serveurs et 1Jour1Film 31 s pour les 3 que le user attendait ; au second passage,
     *   caches chauds, 13 s et 11 s. Le lecteur, lui, démarre en quelques secondes.
     *   Les secondes passées sur la fiche à choisir un épisode sont gratuites : on s'en sert.
     *   Aucun effet visible, aucun blocage — on ne fait que remplir les caches.
     */
    private fun prechaufferServeurs(tvShow: TvShow) {
        val saison = tvShow.seasons.firstOrNull() ?: return
        com.streamflixreborn.streamflix.utils.BackupRegistry.prechauffer(
            tmdbId = tvShow.id.takeIf { id -> id.all { it.isDigit() } },
            videoType = com.streamflixreborn.streamflix.models.Video.Type.Episode(
                id = "",
                number = 1,
                title = null,
                poster = null,
                overview = null,
                season = com.streamflixreborn.streamflix.models.Video.Type.Episode.Season(
                    number = saison.number,
                    title = saison.title,
                ),
                tvShow = com.streamflixreborn.streamflix.models.Video.Type.Episode.TvShow(
                    id = tvShow.id,
                    title = tvShow.title,
                    poster = tvShow.poster,
                    banner = tvShow.banner,
                    releaseDate = null,
                    imdbId = null,
                ),
            ),
            titleHint = tvShow.title,
        )
    }

    private fun displayTvShow(tvShow: TvShow) {
        prechaufferServeurs(tvShow)

        binding.ivTvShowBanner.loadTvShowBanner(tvShow) {
            transition(DrawableTransitionOptions.withCrossFade())
        }

        appAdapter.submitList(listOfNotNull(
            tvShow.apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE },


            tvShow.takeIf {
                    // 2026-05-04 : voir TvShowTvFragment pour le détail. On
                    // affiche dès qu'il y a au moins une saison, plus de
                    // filtrage défensif qui cachait à tort des séries légitimes.
                    it.seasons.isNotEmpty()
                }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_SEASONS_MOBILE },

            tvShow.takeIf { it.directors.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_DIRECTORS_MOBILE },

            tvShow.takeIf { it.cast.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_CAST_MOBILE },

            tvShow.takeIf { it.recommendations.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_RECOMMENDATIONS_MOBILE },
        ))
    }
}
