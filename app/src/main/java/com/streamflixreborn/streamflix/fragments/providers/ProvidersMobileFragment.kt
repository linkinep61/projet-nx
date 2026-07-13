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

        // 2026-06-09 : applique le fond d'écran personnalisé. Cache aussi le bg par défaut.
        com.streamflixreborn.streamflix.utils.AppearanceManager.applyTo(view)
        view.findViewById<android.widget.ImageView>(R.id.iv_providers_default_bg)
            ?.visibility = if (com.streamflixreborn.streamflix.utils.AppearanceManager
                .hasWallpaper(requireContext())) android.view.View.GONE
            else android.view.View.VISIBLE

        // 2026-05-12 : kill le mini-player IPTV qui pourrait encore tourner
        // en background. Quand l'user revient au home picker provider, il
        // ne doit PLUS entendre le son d'une chaîne TV qu'il venait de quitter.
        // 2026-06-09 (user "l'ouverture d'un provider coupe toujours la radio") :
        //   exception pour les radios — on les laisse jouer entre providers.
        val mpc = com.streamflixreborn.streamflix.utils.MiniPlayerController
        if (!mpc.isRadioChannel(mpc.currentChannelId)) {
            mpc.stop()
        }

        initializeProviders()

        binding.ivDownloads.setOnClickListener {
            findNavController().navigate(R.id.downloads)
        }

        // 2026-05-20 : cœur favoris global — favoris de tous les providers (sauf TV)
        binding.ivGlobalFavorites.setOnClickListener {
            findNavController().navigate(R.id.global_favorites)
        }

        // 2026-07-11 (user "à droite du cœur, un bouton pour changer de fond facilement
        //   au lieu d'aller dans les paramètres") : ouvre direct la galerie Wallhaven.
        binding.ivWallpaper.setOnClickListener {
            try {
                startActivity(android.content.Intent(
                    requireContext(),
                    com.streamflixreborn.streamflix.activities.WallhavenGalleryActivity::class.java
                ))
            } catch (e: Throwable) {
                android.widget.Toast.makeText(requireContext(),
                    "Galerie indisponible : ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        // 2026-06-08 (user "à gauche du cœur tu vas mettre une petite radio") :
        //   bouton radio → dialog liste 17 radios Dric4rTV. Au clic, démarre
        //   le mini-bar audio sans naviguer.
        binding.ivRadio.setOnClickListener {
            showRadioPicker()
        }
        // 2026-06-09 : long-press = TOUJOURS le picker (pour changer de radio
        //   sans interrompre l'écoute si une dernière radio est mémorisée).
        binding.ivRadio.setOnLongClickListener {
            com.streamflixreborn.streamflix.utils.RadioPickerDialog
                .show(requireContext(), viewLifecycleOwner)
            true
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

        // Bouton café Ko-fi
        binding.ivKofi.setOnClickListener {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://ko-fi.com/nanico")))
            } catch (_: Exception) {}
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
        // 2026-07-04 : garde null-safe — le binding peut être null si le fragment
        // a été détruit avant que onConfigurationChanged soit dispatché.
        val b = _binding ?: return
        val spanCount = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        val rv = b.rvProviders
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
                    // 2026-07-12 (user « dès qu'on clique Films/Séries ou Anime, faut suspendre le
                    //   reste ») : onglet ≠ TV/IPTV (0=Films/Séries, 1=Animés) → libère la RAM des
                    //   scans IPTV. EN ARRIÈRE-PLAN + seulement si l'IPTV a déjà servi (sinon l'init
                    //   lourde des objets figeait l'UI à froid).
                    if (selectedTabIndex != 2) {
                        kotlin.concurrent.thread(isDaemon = true, name = "iptv-release-tab") {
                            try {
                                if (com.streamflixreborn.streamflix.providers.OlaTvProvider.wasEverLoaded())
                                    com.streamflixreborn.streamflix.providers.OlaTvProvider.releaseMemory()
                                if (com.streamflixreborn.streamflix.providers.VegetaTvProvider.wasEverLoaded())
                                    com.streamflixreborn.streamflix.providers.VegetaTvProvider.releaseMemory()
                            } catch (_: Throwable) {}
                        }
                    }
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
        // 2026-06-13 (Favorite Providers) : trie les favoris EN TÊTE de la
        //   liste. L'user voit ses providers préférés en 1er sans rien toggler.
        val favorites = UserPreferences.favoriteProviders
        val sorted = filtered.sortedByDescending { favorites.contains(it.name) }
        appAdapter.submitList(sorted.onEach {
            it.itemType = AppAdapter.Type.PROVIDER_MOBILE_ITEM
        })
    }

    /**
     * 2026-06-08 : ouvre le dialog picker de radios (centralisé). Dric4rTV +
     * RadioBrowser API + favoris (long-press) + bouton Arrêter.
     *
     * 2026-06-09 (user "quand on va cliquer dessus il joue directement la
     *   dernière radio") : clic court = relance la dernière radio si pas
     *   de radio en cours. Si radio déjà en cours OU jamais de dernière →
     *   ouvre le picker. Long-press = toujours picker.
     */
    private fun showRadioPicker() {
        val mp = com.streamflixreborn.streamflix.utils.MiniPlayerController
        val playingRadio = mp.currentChannelId?.let { mp.isRadioChannel(it) } == true
        if (!playingRadio) {
            val last = mp.getLastRadio(requireContext())
            if (last != null) {
                try {
                    mp.initPlayer(requireContext())
                    if (last.streamUrl != null) {
                        mp.playRadioDirect(last.id, last.name, last.poster, last.streamUrl)
                    } else {
                        mp.playChannel(last.id, last.name, last.poster)
                    }
                    android.widget.Toast.makeText(
                        requireContext(),
                        "${last.name} — reprise",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return
                } catch (_: Throwable) {}
            }
        }
        com.streamflixreborn.streamflix.utils.RadioPickerDialog
            .show(requireContext(), viewLifecycleOwner)
    }
}