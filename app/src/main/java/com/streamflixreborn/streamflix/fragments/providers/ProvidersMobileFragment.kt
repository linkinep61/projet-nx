package com.streamflixreborn.streamflix.fragments.providers

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.FragmentProvidersMobileBinding
import com.streamflixreborn.streamflix.models.Provider as ModelProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import kotlinx.coroutines.launch
import java.util.Locale

class ProvidersMobileFragment : Fragment() {

    private var _binding: FragmentProvidersMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<ProvidersViewModel>()

    private val appAdapter = AppAdapter()

    /** Last list received from the ViewModel — re-filtered when the user switches tab. */
    private var lastProviders: List<ModelProvider> = emptyList()
    /** Currently selected tab: 0 = Films/Séries, 1 = Animés, 2 = TV/IPTV */
    private var selectedTabIndex: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProvidersMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeProviders()

        binding.ivDownloads.setOnClickListener {
            findNavController().navigate(R.id.downloads)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    ProvidersViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is ProvidersViewModel.State.SuccessLoading -> {
                        displayProviders(state.providers)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is ProvidersViewModel.State.FailedLoading -> {
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener {
                                viewModel.getProviders()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val spanCount = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        val rv = binding.rvProviders
        (rv.layoutManager as? GridLayoutManager)?.let { lm ->
            lm.spanCount = spanCount
            // Force a full re-measure: changing spanCount alone doesn't ask
            // children to re-measure their widths, so cards stay at the old
            // column width and the second row drops below the first one
            // (visible misalignment after rotating the device).
            lm.requestLayout()
            rv.invalidateItemDecorations()
            rv.adapter?.notifyDataSetChanged()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun initializeProviders() {
        // Hide language selector (all providers are French)
        binding.sProvidersLanguage.visibility = View.GONE
        binding.sProvidersLanguage.apply {
            class Language(
                val code: String,
                val name: String,
            )

            val languages = Provider.providers.keys
                .distinctBy { it.language }
                .map {
                    val locale = Locale.forLanguageTag(it.language)

                    Language(
                        code = it.language,
                        name = locale.getDisplayLanguage(locale)
                            .replaceFirstChar { char -> char.titlecase() },
                    )
                }
                .sortedBy { it.name.lowercase() }

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (position == 0) {
                        viewModel.getProviders()
                        UserPreferences.providerLanguage = null
                    } else {
                        viewModel.getProviders(languages[position - 1].code)
                        UserPreferences.providerLanguage = languages[position - 1].code
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                }
            }

            // CORREZIONE: Usiamo il contesto dello Spinner (che ha il tema scuro) invece di requireContext()
            val spinnerAdapter = ArrayAdapter(
                this.context,
                android.R.layout.simple_spinner_item,
                languages.map { it.name }.toMutableList()
                    .also { it.add(0, context.getString(R.string.providers_all_languages)) }
                    .toTypedArray()
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setAdapter(spinnerAdapter)

            setSelection(
                UserPreferences.providerLanguage?.let {
                    languages.indexOfFirst { language -> language.code == it } + 1
                } ?: 0
            )
        }

        binding.rvProviders.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 2
            (layoutManager as? GridLayoutManager)?.spanCount = spanCount
            addItemDecoration(
                SpacingItemDecoration(32.dp(requireContext()))
            )
        }

        // Restore last selected tab and wire tab change → re-filter providers
        selectedTabIndex = UserPreferences.providerTabIndex.coerceIn(0, 2)
        binding.tlProviderGroups.apply {
            getTabAt(selectedTabIndex)?.select()
            addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                    selectedTabIndex = tab?.position ?: 0
                    UserPreferences.providerTabIndex = selectedTabIndex
                    refilterProviders()
                }
                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            })
        }
    }

    private fun displayProviders(providers: List<ModelProvider>) {
        lastProviders = providers
        refilterProviders()
    }

    /** Filter [lastProviders] by the currently selected tab and feed the adapter. */
    private fun refilterProviders() {
        val targetGroup = when (selectedTabIndex) {
            0 -> Provider.Companion.ProviderGroup.FILMS_SERIES
            1 -> Provider.Companion.ProviderGroup.ANIME
            2 -> Provider.Companion.ProviderGroup.IPTV
            else -> Provider.Companion.ProviderGroup.FILMS_SERIES
        }
        val filtered = lastProviders.filter { mp ->
            // TMDb providers are added at runtime by the ViewModel — not in the
            // static Provider.providers map. Treat them as FILMS_SERIES so they
            // show up under the right tab.
            if (mp.name.startsWith("TMDb")) {
                return@filter targetGroup == Provider.Companion.ProviderGroup.FILMS_SERIES
            }
            val provider = Provider.providers.keys.find { it.name == mp.name }
            val group = if (provider != null) Provider.providers[provider]?.group else null
            group == targetGroup
        }
        appAdapter.submitList(filtered.onEach {
            it.itemType = AppAdapter.Type.PROVIDER_MOBILE_ITEM
        })
    }
}