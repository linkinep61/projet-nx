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

        // 2026-05-12 : kill le mini-player IPTV qui pourrait encore tourner
        // en background. Quand l'user revient au home picker provider, il
        // ne doit PLUS entendre le son d'une chaîne TV qu'il venait de quitter.
        com.streamflixreborn.streamflix.utils.MiniPlayerController.stop()

        initializeProviders()

        binding.ivDownloads.setOnClickListener {
            findNavController().navigate(R.id.downloads)
        }

        // 2026-05-08 : raccourci Paramètres depuis le Home Fournisseur
        // (utile pour accéder rapidement à l'onglet "Extracteurs", au choix
        // du provider par défaut, etc. sans passer par le menu hamburger).
        binding.ivSettings.setOnClickListener {
            findNavController().navigate(R.id.settings)
        }

        // 2026-05-12 (user "ajoutes un bouton à côté de settings pour
        // retourner sur ce fameux changement de profil") : raccourci vers
        // l'écran "Qui regarde ?" depuis n'importe où dans la home des
        // providers. Clear le profil actif + relance ProfilePickerActivity.
        binding.ivProfileSwitch.setOnClickListener {
            com.streamflixreborn.streamflix.utils.ProfileManager.clearCurrentProfile()
            val intent = android.content.Intent(
                requireContext(),
                com.streamflixreborn.streamflix.activities.profile.ProfilePickerActivity::class.java
            ).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            startActivity(intent)
        }

        // 2026-05-05 : contrôle parental — bouton cadenas
        binding.ivParentalLock.setOnClickListener {
            com.streamflixreborn.streamflix.ui.PinDialog.showAuth(
                context = requireContext(),
                title = "Contrôle parental",
                onSuccess = { showLockManagementDialog() }
            )
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

    /** Dialog multi-choix pour gérer les providers verrouillés (PIN déjà validé). */
    private fun showLockManagementDialog() {
        val ctx = requireContext()
        val allProviders = lastProviders.map { it.name }.distinct().sorted()
        if (allProviders.isEmpty()) return
        val locked = com.streamflixreborn.streamflix.utils.ProviderLockStore.getLockedProviders(ctx)
        val checkedItems = allProviders.map { it in locked }.toBooleanArray()
        val workingState = checkedItems.copyOf()
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Verrouiller des providers")
            .setMultiChoiceItems(allProviders.toTypedArray(), workingState) { _, which, isChecked ->
                workingState[which] = isChecked
            }
            .setPositiveButton("Valider") { d, _ ->
                allProviders.forEachIndexed { i, name ->
                    if (workingState[i]) com.streamflixreborn.streamflix.utils.ProviderLockStore
                        .lockProvider(ctx, name)
                    else com.streamflixreborn.streamflix.utils.ProviderLockStore
                        .unlockProvider(ctx, name)
                }
                Toast.makeText(ctx, "Configuration enregistrée", Toast.LENGTH_SHORT).show()
                refilterProviders()
                d.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .setNeutralButton("Changer le code") { _, _ ->
                showChangePinDialog()
            }
            .show()
    }

    private fun showChangePinDialog() {
        val ctx = requireContext()
        val oldInput = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            hint = "Ancien code"
        }
        val newInput = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            hint = "Nouveau code (4-8 chiffres)"
        }
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(oldInput); addView(newInput)
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Changer le code parental")
            .setView(container)
            .setPositiveButton("Valider") { d, _ ->
                val ok = com.streamflixreborn.streamflix.utils.ProviderLockStore
                    .changePin(ctx, oldInput.text.toString().trim(), newInput.text.toString().trim())
                if (ok) Toast.makeText(ctx, "Code mis à jour ✓", Toast.LENGTH_SHORT).show()
                else Toast.makeText(ctx, "Échec — ancien code incorrect ?", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
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