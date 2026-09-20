package com.streamflixreborn.streamflix.fragments.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentSearchTvBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import android.app.AlertDialog
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.SearchHistory
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.VoiceRecognitionHelper
import com.streamflixreborn.streamflix.utils.hideKeyboard
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import com.streamflixreborn.streamflix.providers.Provider

class SearchTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var globalSearchScrollDone: Boolean = false
    private var _binding: FragmentSearchTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { SearchViewModel(database) }
    private var isGlobalSearchChecked: Boolean = false
    private var currentGridColumns: Int = 1

    private val appAdapter by lazy {
        AppAdapter().apply {
            onMovieClickListener = { movie ->

                if (movie.providerName != UserPreferences.currentProvider?.name) {
                    UserPreferences.currentProvider = Provider.providers.keys.find { it.name == movie.providerName }
                    Toast.makeText(requireContext(), getString(R.string.switching_to_provider, movie.providerName), Toast.LENGTH_SHORT).show()
                }
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToMovie(id = movie.id)
                )
            }
            onTvShowClickListener = { tvShow ->

                if (tvShow.providerName != UserPreferences.currentProvider?.name) {
                    UserPreferences.currentProvider = Provider.providers.keys.find { it.name == tvShow.providerName }
                    Toast.makeText(requireContext(), getString(R.string.switching_to_provider, tvShow.providerName), Toast.LENGTH_SHORT).show()
                }
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToTvShow(
                        id = tvShow.id,
                        poster = tvShow.poster,
                        banner = tvShow.banner,
                    )
                )
            }
        }
    }

    private lateinit var voiceHelper: VoiceRecognitionHelper

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSearch()
        installerMiniLecteur()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->

                when (state) {
                    is State.Searching, is State.GlobalSearching -> {
                        binding.isLoading.apply {
                            root.visibility = View.VISIBLE
                            pbIsLoading.visibility = View.VISIBLE
                            gIsLoadingRetry.visibility = View.GONE
                        }
                        appAdapter.isLoading = false
                        appAdapter.setOnLoadMoreListener(null)
                    }
                    is State.SearchingMore -> appAdapter.isLoading = true
                    is State.SuccessSearching -> {
                        displaySearch(state.results, state.hasMore)
                        appAdapter.isLoading = false
                        binding.vgvSearch.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is State.SuccessGlobalSearching -> {
                        displayGlobalSearch(state.providerResults)
                        appAdapter.isLoading = false
                        binding.vgvSearch.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is State.FailedSearching -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
                            viewModel.search(viewModel.query)
                            return@collect
                        }
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.search(viewModel.query) }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                    viewModel.search(viewModel.query)
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                                binding.vgvSearch.visibility = View.INVISIBLE
                                binding.etSearch.nextFocusDownId = binding.isLoading.btnIsLoadingRetry.id
                                binding.isLoading.btnIsLoadingRetry.nextFocusUpId = binding.etSearch.id
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 2026-09-22 (demande user : « au niveau de la recherche pour les lives, faire
     * apparaitre le mini lecteur aussi ; mais pas sur les VOD »). Pendant TV.
     *
     * Sur TV, l'encart est FLOTTANT en haut a droite (comme dans l'onglet des chaines) :
     * il ne pousse pas la grille et ne prend pas le focus, donc la navigation a la
     * telecommande n'est pas modifiee.
     *
     * ⚠ Aucune garde a ajouter pour exclure les VOD : l'intercepteur n'est consulte que
     *   depuis `TvShowViewHolder.handleDirectPlay`, garde par `isIptvProvider()` qui teste
     *   l'IDENTIFIANT de l'element et exclut deja films et programmes de replay.
     */
    private fun installerMiniLecteur() {
        val estIptv = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider is
            com.streamflixreborn.streamflix.providers.IptvProvider
        if (!estIptv || !com.streamflixreborn.streamflix.utils.UserPreferences.miniPlayerEnabled) {
            binding.miniPlayerContainer.visibility = View.GONE
            com.streamflixreborn.streamflix.utils.MiniPlayerController.onIptvChannelClick = null
            return
        }

        com.streamflixreborn.streamflix.utils.MiniPlayerController.initPlayer(requireContext())
        binding.miniPlayerView.player = com.streamflixreborn.streamflix.utils.MiniPlayerController.getPlayer()

        if (com.streamflixreborn.streamflix.utils.MiniPlayerController.currentChannelId != null) {
            com.streamflixreborn.streamflix.utils.MiniPlayerController.applyMiniPlayerVisibility(binding.miniPlayerContainer, View.VISIBLE)
            binding.miniPlayerChannelName.text = com.streamflixreborn.streamflix.utils.MiniPlayerController.currentChannelName ?: ""
            com.streamflixreborn.streamflix.utils.MiniPlayerController.currentChannelPoster?.let { poster ->
                com.bumptech.glide.Glide.with(this).load(poster).into(binding.miniPlayerChannelLogo)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            com.streamflixreborn.streamflix.utils.MiniPlayerController.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { etat ->
                val etaitVisible = binding.miniPlayerContainer.visibility == View.VISIBLE
                fun animer() {
                    androidx.transition.TransitionManager.beginDelayedTransition(
                        binding.root as ViewGroup,
                        androidx.transition.AutoTransition().apply { duration = 300 }
                    )
                }
                when (etat) {
                    is com.streamflixreborn.streamflix.utils.MiniPlayerController.State.Idle -> {
                        if (etaitVisible) animer()
                        binding.miniPlayerContainer.visibility = View.GONE
                        binding.miniPlayerOverlay.visibility = View.GONE
                    }
                    is com.streamflixreborn.streamflix.utils.MiniPlayerController.State.Loading -> {
                        if (!etaitVisible) animer()
                        // 2026-09-22 : sur TV, `MiniPlayerBarre` n'affiche la rangee de
                        //   commandes qu'apres un appui sur la video — geste qui n'existe pas
                        //   a la telecommande. On la montre donc des que le lecteur tourne.
                        binding.miniPlayerOverlay.visibility = View.VISIBLE
                        // 2026-09-22 : un ExoPlayer ne dessine que sur UNE surface a la fois,
                        //   celle de la derniere PlayerView a laquelle il a ete rattache. Le
                        //   mini-lecteur ayant demarre depuis un autre onglet, l'image restait
                        //   sur la vue de CET onglet et la notre restait noire, alors que le son
                        //   sortait (mesure : « DIAG decodeur mini: rendu=0 saute=365 »).
                        //   Le detacher puis le rattacher RAMENE la surface ici. Sans condition :
                        //   l'objet lecteur est le meme, c'est sa surface qui doit changer de vue.
                        binding.miniPlayerView.player = null
                        binding.miniPlayerView.player = com.streamflixreborn.streamflix.utils.MiniPlayerController.getPlayer()
                        com.streamflixreborn.streamflix.utils.MiniPlayerController.applyMiniPlayerVisibility(binding.miniPlayerContainer, View.VISIBLE)
                        binding.miniPlayerChannelName.text = etat.channelName
                        binding.miniPlayerLoading.visibility = View.VISIBLE
                    }
                    is com.streamflixreborn.streamflix.utils.MiniPlayerController.State.Playing -> {
                        if (!etaitVisible) animer()
                        // 2026-09-22 : sur TV, `MiniPlayerBarre` n'affiche la rangee de
                        //   commandes qu'apres un appui sur la video — geste qui n'existe pas
                        //   a la telecommande. On la montre donc des que le lecteur tourne.
                        binding.miniPlayerOverlay.visibility = View.VISIBLE
                        // 2026-09-22 : un ExoPlayer ne dessine que sur UNE surface a la fois,
                        //   celle de la derniere PlayerView a laquelle il a ete rattache. Le
                        //   mini-lecteur ayant demarre depuis un autre onglet, l'image restait
                        //   sur la vue de CET onglet et la notre restait noire, alors que le son
                        //   sortait (mesure : « DIAG decodeur mini: rendu=0 saute=365 »).
                        //   Le detacher puis le rattacher RAMENE la surface ici. Sans condition :
                        //   l'objet lecteur est le meme, c'est sa surface qui doit changer de vue.
                        binding.miniPlayerView.player = null
                        binding.miniPlayerView.player = com.streamflixreborn.streamflix.utils.MiniPlayerController.getPlayer()
                        com.streamflixreborn.streamflix.utils.MiniPlayerController.applyMiniPlayerVisibility(binding.miniPlayerContainer, View.VISIBLE)
                        binding.miniPlayerChannelName.text = etat.channelName
                        binding.miniPlayerLoading.visibility = View.GONE
                        etat.channelPoster?.let { poster ->
                            com.bumptech.glide.Glide.with(this@SearchTvFragment)
                                .load(poster).into(binding.miniPlayerChannelLogo)
                        }
                    }
                    is com.streamflixreborn.streamflix.utils.MiniPlayerController.State.Error -> {
                        binding.miniPlayerLoading.visibility = View.GONE
                        android.util.Log.e("SearchTv", "mini lecteur : ${etat.message}")
                        Toast.makeText(
                            requireContext(),
                            "Stream indisponible \u2014 essaie une autre chaine",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        com.streamflixreborn.streamflix.utils.MiniPlayerController.onIptvChannelClick = { tvShow ->
            if (tvShow.id == com.streamflixreborn.streamflix.utils.MiniPlayerController.currentChannelId) {
                com.streamflixreborn.streamflix.utils.MiniPlayerController.transitioningToFullscreen = true
                if (_binding != null) { binding.miniPlayerView.player = null }
                com.streamflixreborn.streamflix.utils.MiniPlayerController.stopAsync()
                false
            } else {
                com.streamflixreborn.streamflix.utils.MiniPlayerController.playChannel(tvShow.id, tvShow.title, tvShow.poster)
                true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceHelper.stopRecognition()
        _binding = null
    }

    /** 2026-06-13 : retourne le bon hint selon le type de provider courant.
     *   - IptvProvider → "Rechercher des chaînes"
     *   - Autres → "Rechercher des films, séries TV"
     *  (porté depuis upstream v1.7.220 — search_input_hint_iptv) */
    private fun getSearchHintRes(): Int {
        val current = UserPreferences.currentProvider
        return if (current is com.streamflixreborn.streamflix.providers.IptvProvider) {
            R.string.search_input_hint_iptv
        } else {
            R.string.search_input_hint
        }
    }

    private fun submitSearch(): Boolean {
        val query = binding.etSearch.text?.toString().orEmpty()
        if (query.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.search_empty_query), Toast.LENGTH_SHORT).show()
            return true
        }
        hideKeyboard()
        SearchHistory.add(requireContext(), query)

        if (isGlobalSearchChecked) {
            globalSearchScrollDone = false
            val currentProvider = UserPreferences.currentProvider
            val currentLanguage = currentProvider?.language ?: "fr"
            val group = currentProvider?.let { Provider.getGroup(it) }
                ?: Provider.Companion.ProviderGroup.FILMS_SERIES
            viewModel.searchGlobal(query, currentLanguage, group)
        } else {
            viewModel.search(query)
        }
        return true
    }

    private fun showHistoryDialog() {
        val history = SearchHistory.getAll(requireContext())
        if (history.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.search_history_cleared), Toast.LENGTH_SHORT).show()
            return
        }
        val items = history.toMutableList()
        items.add(getString(R.string.search_history_clear))

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.search_history_title))
            .setItems(items.toTypedArray()) { _, which ->
                if (which == items.size - 1) {
                    SearchHistory.clear(requireContext())
                    Toast.makeText(requireContext(), getString(R.string.search_history_cleared), Toast.LENGTH_SHORT).show()
                } else {
                    val query = items[which]
                    binding.etSearch.setText(query)
                    binding.etSearch.setSelection(query.length)
                    submitSearch()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun initializeSearch() {
        binding.llGlobalSearch.nextFocusUpId = binding.etSearch.id
        binding.vgvSearch.nextFocusUpId = binding.llGlobalSearch.id

        // 2026-05-18 : restore saved state (default=true → recherche globale active
        //   d'office sur tous les providers du même language).
        isGlobalSearchChecked = UserPreferences.isGlobalSearchEnabled
        binding.ivGlobalSearchSwitch.setImageResource(
            if (isGlobalSearchChecked) R.drawable.ic_switch_on else R.drawable.ic_switch_off
        )

        binding.llGlobalSearch.setOnClickListener {
            isGlobalSearchChecked = !isGlobalSearchChecked
            UserPreferences.isGlobalSearchEnabled = isGlobalSearchChecked
            binding.ivGlobalSearchSwitch.setImageResource(
                if (isGlobalSearchChecked) R.drawable.ic_switch_on else R.drawable.ic_switch_off
            )
        }

        // 2026-06-13 : hint dynamique selon provider courant (IPTV vs VOD).
        binding.etSearch.hint = getString(getSearchHintRes())

        // Historique de recherche (bouton → dialog)
        binding.btnSearchHistory.setOnClickListener { showHistoryDialog() }

        binding.etSearch.apply {
            setOnEditorActionListener { _, actionId, event ->
                val isSubmitAction =
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                        actionId == EditorInfo.IME_ACTION_DONE ||
                        actionId == EditorInfo.IME_NULL
                val isSubmitKey =
                    event?.action == KeyEvent.ACTION_DOWN &&
                        (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)

                if (isSubmitAction || isSubmitKey) {
                    return@setOnEditorActionListener submitSearch()
                }
                return@setOnEditorActionListener false
            }

            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }

                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    return@setOnKeyListener focusSearchContent()
                }

                if (
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    keyCode == KeyEvent.KEYCODE_SEARCH
                ) {
                    return@setOnKeyListener submitSearch()
                }

                false
            }

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (s.isNullOrBlank()) {
                        binding.etSearch.hint = getString(getSearchHintRes())
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        val blink = AlphaAnimation(1f, 0.3f).apply {
            duration = 500
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }

        voiceHelper = VoiceRecognitionHelper(
            fragment = this,
            onResult = { query ->
                binding.btnSearchVoice.clearAnimation()
                binding.etSearch.setText(query)
                submitSearch()
            },
            onError = { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                binding.btnSearchVoice.clearAnimation()
                binding.etSearch.hint = getString(getSearchHintRes())
            },
            onListeningStateChanged = { isListening ->
                binding.btnSearchVoice.startAnimation(blink)
                binding.etSearch.hint = getString(R.string.voice_prompt)
            }
        )

        binding.btnSearchVoice.apply {
            requestFocus()
            visibility = if (voiceHelper.isAvailable()) View.VISIBLE else View.GONE
            setOnClickListener { if (!voiceHelper.isListening) voiceHelper.startWithPermissionCheck() }
        }

        listOf(binding.btnSearchClear, binding.btnSearchVoice, binding.llGlobalSearch).forEach { view ->
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                    focusSearchContent()
                } else {
                    false
                }
            }
        }

        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.setText("")
            binding.etSearch.hint = getString(getSearchHintRes())
            viewModel.search("")
        }

        binding.vgvSearch.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.search_spacing).toInt())
            addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int,
                ) {
                    child?.itemView?.nextFocusUpId =
                        if (position in 0 until currentGridColumns) binding.llGlobalSearch.id
                        else View.NO_ID
                }
            })
        }

        binding.root.requestFocus()
    }

    private fun focusSearchContent(): Boolean {
        val hasResults = appAdapter.itemCount > 0 && binding.vgvSearch.visibility == View.VISIBLE
        return when {
            hasResults -> {
                binding.vgvSearch.requestFocus()
            }
            binding.llGlobalSearch.visibility == View.VISIBLE -> {
                binding.llGlobalSearch.requestFocus()
            }
            else -> false
        }
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        currentGridColumns = if (viewModel.query == "") 5 else 6
        binding.vgvSearch.setNumColumns(currentGridColumns)

        appAdapter.submitList(list.onEach {
            when (it) {
                is Genre -> it.itemType = AppAdapter.Type.GENRE_GRID_TV_ITEM
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
            }
        })

        if (hasMore && viewModel.query != "") {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun displayGlobalSearch(providerResults: List<ProviderResult>) {
        val categories = providerResults.map { providerResult ->
            val headerTitle = when (val state = providerResult.state) {
                is ProviderResult.State.Loading -> "${providerResult.provider.name} - ${getString(R.string.searching)}"
                is ProviderResult.State.Error -> "${providerResult.provider.name} - ${getString(R.string.search_error)}"
                is ProviderResult.State.Success -> {
                    val count = state.results.size
                    val resultText = if (count == 1) getString(R.string.result) else getString(R.string.results)
                    "${providerResult.provider.name} - $count $resultText"
                }
            }

            val items = (providerResult.state as? ProviderResult.State.Success)?.results?.onEach {
                when (it) {
                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                }
            } ?: emptyList()

            Category(name = headerTitle, list = items).apply {
                itemType = AppAdapter.Type.CATEGORY_TV_ITEM
            }
        }

        currentGridColumns = 1
        binding.vgvSearch.setNumColumns(currentGridColumns) // La lista de categorías es una sola columna vertical
        appAdapter.submitList(categories)
        appAdapter.setOnLoadMoreListener(null)

        // Scroll en haut lors de la 1ère émission (sinon résultats progressifs poussent la vue en bas)
        if (!globalSearchScrollDone) {
            globalSearchScrollDone = true
            binding.vgvSearch.post { binding.vgvSearch.scrollToPosition(0) }
        }
    }
}
