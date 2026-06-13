package com.streamflixreborn.streamflix.fragments.providers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.navigation.fragment.findNavController
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.FragmentProvidersTvBinding
import com.streamflixreborn.streamflix.models.Provider as ModelProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.launch
import java.util.Locale

class ProvidersTvFragment : Fragment() {

    private var _binding: FragmentProvidersTvBinding? = null
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
        _binding = FragmentProvidersTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 2026-06-09 : applique le fond d'écran personnalisé. Cache aussi
        //   l'ImageView par défaut (bg_wallpaper_tv) si l'user a setté un
        //   wallpaper perso → sinon le perso est invisible (caché derrière).
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

        // Downloads disabled on TV — not enough storage on these devices
        binding.btnProvidersDownloads.visibility = View.GONE

        // 2026-05-08 : raccourci Paramètres depuis le Home Fournisseur (TV).
        // Focusable D-pad (bg_tv_button_focus appliqué dans le XML).
        binding.btnProvidersSettings.setOnClickListener {
            findNavController().navigate(R.id.settings)
        }

        // 2026-05-20 : cœur favoris global — favoris de tous les providers (sauf TV)
        binding.btnGlobalFavorites.setOnClickListener {
            findNavController().navigate(R.id.global_favorites)
        }
        binding.btnGlobalFavorites.requestFocus()

        // 2026-06-08 (user "à gauche du cœur tu vas mettre une petite radio") :
        //   bouton radio → dialog liste 17 radios Dric4rTV. Au clic, démarre
        //   le mini-bar audio. Aucune navigation, l'user reste sur l'écran
        //   providers (utilisable sans écran).
        binding.btnRadio.setOnClickListener {
            showRadioPicker()
        }
        // 2026-06-09 : long-press = TOUJOURS le picker (pour changer de radio
        //   sans interrompre l'écoute si une dernière radio est mémorisée).
        binding.btnRadio.setOnLongClickListener {
            com.streamflixreborn.streamflix.utils.RadioPickerDialog
                .show(requireContext(), viewLifecycleOwner)
            true
        }

        // 2026-05-12 : bouton "Changer de profil" symétrique du mobile.
        // Clear le profil actif + relance ProfilePickerTvActivity en task neuve.
        binding.btnProvidersProfileSwitch.setOnClickListener {
            com.streamflixreborn.streamflix.utils.ProfileManager.clearCurrentProfile()
            val intent = android.content.Intent(
                requireContext(),
                com.streamflixreborn.streamflix.activities.profile.ProfilePickerTvActivity::class.java
            ).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            startActivity(intent)
        }

        // 2026-05-05 : Bouton cadenas pour le contrôle parental
        binding.btnProvidersLock.setOnClickListener {
            com.streamflixreborn.streamflix.ui.PinDialog.showAuth(
                context = requireContext(),
                title = "Contrôle parental",
                onSuccess = { showLockManagementDialog() }
            )
        }

        // 2026-06-07 (user "tasse de café sur TV, click → QR code Naniko") :
        // équivalent TV de iv_kofi mobile — sur mobile c'est un Intent.ACTION_VIEW
        // direct, sur TV on affiche un dialog avec un QR code (généré localement
        // via QrUtils/ZXing) qui pointe vers https://ko-fi.com/nanico. L'user
        // scanne avec son téléphone et tombe sur la page Naniko Café.
        binding.btnProvidersKofi.setOnClickListener {
            showKofiQrDialog()
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
                        binding.rvProviders.visibility = View.VISIBLE
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
                            binding.rvProviders.visibility = View.GONE
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

            val spinnerAdapter = ArrayAdapter(
                requireContext(),
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
            addItemDecoration(
                SpacingItemDecoration(
                    requireContext().resources.getDimension(R.dimen.providers_spacing).toInt()
                )
            )
        }

        // Restore last selected tab + wire tab change
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

    /** Affiche un dialog avec checkbox pour chaque provider — l'user toggle
     *  ceux qu'il veut verrouiller / déverrouiller. Le PIN est déjà validé. */
    private fun showLockManagementDialog() {
        val ctx = requireContext()
        // Liste tous les providers (toutes catégories)
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
                refilterProviders()  // refresh affichage avec les cadenas
                d.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .setNeutralButton("Changer le code") { _, _ ->
                // Demande ancien + nouveau PIN
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

    /** Filter [lastProviders] by the currently selected tab and feed the adapter. */
    private fun refilterProviders() {
        val targetGroup = when (selectedTabIndex) {
            0 -> Provider.Companion.ProviderGroup.FILMS_SERIES
            1 -> Provider.Companion.ProviderGroup.ANIME
            2 -> Provider.Companion.ProviderGroup.IPTV
            else -> Provider.Companion.ProviderGroup.FILMS_SERIES
        }
        val filtered = lastProviders.filter { mp ->
            if (mp.name.startsWith("TMDb")) {
                return@filter targetGroup == Provider.Companion.ProviderGroup.FILMS_SERIES
            }
            val provider = Provider.providers.keys.find { it.name == mp.name }
            val group = if (provider != null) Provider.providers[provider]?.group else null
            group == targetGroup
        }
        // 2026-06-13 (Favorite Providers) : trie les favoris EN TÊTE.
        val favorites = UserPreferences.favoriteProviders
        val sorted = filtered.sortedByDescending { favorites.contains(it.name) }
        appAdapter.submitList(sorted.onEach {
            it.itemType = AppAdapter.Type.PROVIDER_TV_ITEM
        })
        binding.rvProviders.requestFocus()
    }

    /**
     * 2026-06-08 : ouvre le dialog picker de radios (centralisé via
     * RadioPickerDialog). Inclut Dric4rTV + RadioBrowser API + favoris +
     * bouton Arrêter.
     *
     * 2026-06-09 (user "quand on va cliquer dessus il joue directement la
     *   dernière radio qui a été utilisée") : clic court = relance la
     *   dernière radio. Si une radio joue DÉJÀ → ouvre le picker (pour
     *   changer). Si jamais de dernière radio → ouvre le picker.
     *   Long-press sur le bouton Radio = picker.
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

    /**
     * 2026-06-07 (user) : équivalent TV de iv_kofi mobile. On affiche un
     * dialog avec un QR code 500×500 pointant vers https://ko-fi.com/nanico
     * + un message d'invitation. L'user scanne avec son téléphone et tombe
     * sur la page Naniko Café (sur mobile c'est un Intent ACTION_VIEW direct,
     * mais sur TV pas de navigateur ergonomique → le QR est la meilleure UX).
     */
    private fun showKofiQrDialog() {
        val kofiUrl = "https://ko-fi.com/nanico"

        // Layout vertical centré : titre + QR + sous-titre
        val ctx = requireContext()
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = android.widget.TextView(ctx).apply {
            text = "Soutenir Naniko Café ☕"
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        container.addView(title)

        val qrView = android.widget.ImageView(ctx).apply {
            val size = 500
            val params = android.widget.LinearLayout.LayoutParams(size, size)
            params.gravity = android.view.Gravity.CENTER
            layoutParams = params
            try {
                setImageBitmap(
                    com.streamflixreborn.streamflix.utils.QrUtils.generate(kofiUrl, size)
                )
            } catch (t: Throwable) {
                Toast.makeText(ctx, "Erreur génération QR : ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(qrView)

        val subtitle = android.widget.TextView(ctx).apply {
            text = "Scanne ce QR code avec ton téléphone\npour ouvrir ko-fi.com/nanico"
            textSize = 16f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        container.addView(subtitle)

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setView(container)
            .setNegativeButton("Fermer", null)
            .create()
            .show()
    }
}