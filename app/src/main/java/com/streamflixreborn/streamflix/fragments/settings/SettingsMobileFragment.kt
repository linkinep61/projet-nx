package com.streamflixreborn.streamflix.fragments.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.Group
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import androidx.preference.SwitchPreferenceCompat
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.utils.MiniPlayerController
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.activities.tools.QrScannerActivity
import com.streamflixreborn.streamflix.backup.BackupRestoreManager
import com.streamflixreborn.streamflix.backup.ProviderBackupContext
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.providers.FrenchStreamProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.ProviderConfigUrl
import com.streamflixreborn.streamflix.providers.ProviderPortalUrl
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserDataCache
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsMobileFragment : PreferenceFragmentCompat() {
    private data class SettingsScreenState(
        val rootKey: String?,
        val title: String?,
    )

    private val DEFAULT_DOMAIN_VALUE = "streamingunity.biz"
    private val DEFAULT_CUEVANA_DOMAIN_VALUE = "cuevana3.la"
    private val DEFAULT_POSEIDON_DOMAIN_VALUE = "www.poseidonhd2.co"
    private val PREFS_ERROR_VALUE = "PREFS_NOT_INIT_ERROR"
    private var currentScreenState = SettingsScreenState(rootKey = null, title = null)
    private val screenBackStack = ArrayDeque<SettingsScreenState>()
    private lateinit var settingsBackCallback: OnBackPressedCallback
    /** Toolbar de notre wrapper (cf onCreateView) — null avant inflate. */
    private var wrapperToolbar: androidx.appcompat.widget.Toolbar? = null

    private lateinit var backupRestoreManager: BackupRestoreManager
    private var backupLoadingDialog: AlertDialog? = null

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performBackupExport(it)
            }
        }
    }

    private val exportDbBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performDatabaseBackupExport(it)
            }
        }
    }

    private val importDbBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performDatabaseBackupImport(it)
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performBackupImport(it)
            }
        }
    }

    private val scanResolverQrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }

        val rawValue = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_VALUE).orEmpty()
        val uri = rawValue
            .takeIf { it.startsWith("streamflix://resolve") }
            ?.let(Uri::parse)

        if (uri == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.settings_scan_resolver_invalid_qr),
                Toast.LENGTH_SHORT
            ).show()
            return@registerForActivityResult
        }

        val intent = Intent(requireContext(), MainMobileActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        currentScreenState = SettingsScreenState(rootKey = rootKey, title = null)
        renderCurrentScreen()

        val allProvidersToBackup = Provider.providers.keys.toMutableList().apply {
            listOf("it", "en", "es", "de", "fr").forEach { lang ->
                add(TmdbProvider(lang))
            }
        }

        backupRestoreManager = BackupRestoreManager(
            requireContext(),
            allProvidersToBackup.mapNotNull { provider ->
                try {
                    val db = AppDatabase.getInstanceForProvider(provider.name, requireContext())
                    ProviderBackupContext(
                        name = provider.name,
                        movieDao = db.movieDao(),
                        tvShowDao = db.tvShowDao(),
                        episodeDao = db.episodeDao(),
                        seasonDao = db.seasonDao(),
                        provider = provider
                    )
                } catch (e: Exception) {
                    Log.w("BackupRestore", "Skipping ${provider.name}: ${e.message}")
                    null
                }
            }
        )

        displaySettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (screenBackStack.isEmpty()) return
                currentScreenState = screenBackStack.removeLast()
                settingsBackCallback.isEnabled = screenBackStack.isNotEmpty()
                renderCurrentScreen()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, settingsBackCallback)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // 2026-05-08 : on wrappe le PreferenceFragmentCompat dans un layout qui
        // ajoute une Toolbar (flèche back en haut à gauche). Sans ça,
        // l'utilisateur arrivait sur Paramètres sans aucun moyen visuel de
        // revenir à l'écran Fournisseur — il fallait connaître la touche back
        // système (pas évident pour un user lambda sur mobile sans bouton
        // physique).
        val wrapper = inflater.inflate(
            R.layout.fragment_settings_wrapper_mobile,
            container,
            false,
        ) as android.view.ViewGroup
        val host = wrapper.findViewById<android.widget.FrameLayout>(R.id.settings_content_host)

        // IMPORTANT : on récupère la Toolbar AVANT super.onCreateView, car
        // super déclenche onCreatePreferences → renderCurrentScreen →
        // applyScreenTitle, qui veut écrire dans wrapperToolbar.
        val toolbar = wrapper.findViewById<androidx.appcompat.widget.Toolbar>(
            R.id.toolbar_settings_wrapper,
        )
        wrapperToolbar = toolbar
        // Aligne le titre de la Toolbar sur l'état courant (sous-écran ouvert)
        // pour que le user voie où il est dans la hiérarchie de paramètres.
        toolbar.title = currentScreenState.title ?: "Paramètres"

        // 2026-05-31 : cadenas contrôle parental dans la toolbar — verrouillage providers
        val lockIcon = wrapper.findViewById<android.widget.ImageView>(R.id.iv_parental_lock_settings)
        if (lockIcon != null) {
            lockIcon.visibility = android.view.View.VISIBLE
            lockIcon.setOnClickListener {
                com.streamflixreborn.streamflix.ui.PinDialog.showAuth(
                    context = requireContext(),
                    title = "Contrôle parental",
                    onSuccess = {
                        // Ouvrir le dialog de verrouillage des providers
                        val ctx = requireContext()
                        val allProviders = com.streamflixreborn.streamflix.providers.Provider.providers.keys.map { it.name }.distinct().sorted()
                        if (allProviders.isEmpty()) return@showAuth
                        val locked = com.streamflixreborn.streamflix.utils.ProviderLockStore.getLockedProviders(ctx)
                        val workingState = allProviders.map { it in locked }.toBooleanArray()
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("Verrouiller des providers")
                            .setMultiChoiceItems(allProviders.toTypedArray(), workingState) { _, which, isChecked ->
                                workingState[which] = isChecked
                            }
                            .setPositiveButton("Valider") { d, _ ->
                                allProviders.forEachIndexed { i, name ->
                                    if (workingState[i]) com.streamflixreborn.streamflix.utils.ProviderLockStore.lockProvider(ctx, name)
                                    else com.streamflixreborn.streamflix.utils.ProviderLockStore.unlockProvider(ctx, name)
                                }
                                android.widget.Toast.makeText(ctx, "Configuration enregistrée", android.widget.Toast.LENGTH_SHORT).show()
                                d.dismiss()
                            }
                            .setNegativeButton("Annuler", null)
                            .show()
                    }
                )
            }
        }

        // PreferenceFragmentCompat inflate sa propre RecyclerView ; on la place
        // dans notre FrameLayout en dessous de la Toolbar.
        val original = super.onCreateView(inflater, host, savedInstanceState)
        if (original != null) host.addView(original)
        // Le titre suit le sous-écran courant (cf renderCurrentScreen). Sur
        // l'écran racine on affiche "Paramètres" (déjà set en XML).
        toolbar.setNavigationOnClickListener {
            // Si on est dans un sous-écran de paramètres → on remonte d'UN niveau,
            // exactement comme la touche back système (réutilise le callback
            // existant pour ne pas dupliquer la logique).
            // Sinon → on quitte les paramètres et on retourne au Home Fournisseur.
            if (screenBackStack.isNotEmpty()) {
                currentScreenState = screenBackStack.removeLast()
                settingsBackCallback.isEnabled = screenBackStack.isNotEmpty()
                renderCurrentScreen()
            } else {
                findNavController().popBackStack()
            }
        }

        return wrapper
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SettingsListStyler.attach(view, isTv = false)
    }

    override fun onDestroyView() {
        // Évite de retenir une vue détruite (et donc une fuite d'activity).
        wrapperToolbar = null
        super.onDestroyView()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference is PreferenceScreen && !preference.key.isNullOrBlank()) {
            screenBackStack.addLast(currentScreenState)
            currentScreenState = SettingsScreenState(
                rootKey = preference.key,
                title = preference.title?.toString(),
            )
            settingsBackCallback.isEnabled = screenBackStack.isNotEmpty()
            renderCurrentScreen()
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key == "PARENTAL_CONTROL_PIN" || preference.key == "PARENTAL_CONTROL_ADMIN_PIN") {
            return
        }
        super.onDisplayPreferenceDialog(preference)
    }

    private fun applyScreenTitle() {
        val title = currentScreenState.title ?: getString(R.string.player_settings_title)
        activity?.title = title
        // 2026-05-08 : on met aussi le titre dans notre Toolbar wrapper pour
        // que la barre du haut reflète le sous-écran courant ("Sauvegarde",
        // "Extracteurs"…) au lieu de rester figée sur "Paramètres".
        wrapperToolbar?.title = title
    }

    private fun renderCurrentScreen() {
        setPreferencesFromResource(R.xml.settings_mobile, currentScreenState.rootKey)
        if (::backupRestoreManager.isInitialized) {
            displaySettings()
        }
        applyScreenTitle()
    }

    private fun displaySettings() {
        updateOverviewLabels()
        updateProviderVisibilityState()

        // 2026-05-13 (user "quand on clique sur Mes sources IPTV le menu ne s'affiche
        // pas") : handlers click pour les 3 entrées de la section Mon IPTV.
        val isMyIptv = UserPreferences.currentProvider is com.streamflixreborn.streamflix.providers.MyIptvProvider
        findPreference<androidx.preference.PreferenceCategory>("pc_my_iptv")?.isVisible = isMyIptv
        findPreference<Preference>("pref_iptv_manage_sources")?.setOnPreferenceClickListener {
            startActivity(android.content.Intent(
                requireContext(),
                com.streamflixreborn.streamflix.activities.iptv.IptvSourcesActivity::class.java,
            ))
            true
        }
        findPreference<Preference>("pref_iptv_add_source")?.setOnPreferenceClickListener {
            startActivity(android.content.Intent(
                requireContext(),
                com.streamflixreborn.streamflix.activities.iptv.IptvSourcesActivity::class.java,
            ).apply {
                putExtra("auto_add_source", true)
            })
            true
        }
        // 2026-05-13 (user "Home provider n'est toujours pas accessible") :
        // bouton retour au home depuis les paramètres mobile.
        findPreference<Preference>("pref_back_to_home")?.setOnPreferenceClickListener {
            val provider = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
            if (provider == null) {
                Toast.makeText(requireContext(), "Aucun fournisseur sélectionné", Toast.LENGTH_SHORT).show()
            } else try {
                androidx.navigation.fragment.NavHostFragment.findNavController(this)
                    .navigate(R.id.home)
            } catch (_: Throwable) {
                Toast.makeText(requireContext(), "Impossible de revenir au home", Toast.LENGTH_SHORT).show()
            }
            true
        }
        findPreference<Preference>("pref_iptv_clear_cache")?.setOnPreferenceClickListener {
            // 2026-05-13 : invalide les 3 niveaux pour vrai refresh complet
            com.streamflixreborn.streamflix.providers.MyIptvProvider.invalidateCache()
            com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                requireContext(), com.streamflixreborn.streamflix.providers.MyIptvProvider,
            )
            Toast.makeText(requireContext(), "Cache IPTV vidé — re-téléchargement au prochain clic", Toast.LENGTH_LONG).show()
            true
        }

        // 2026-05-13 (user "ça serait bien comme ça on peut avoir vraiment tout
        // le contenu") : quand l'user change le filtre langue, on reset les
        // filtres catégorie (les noms RAW peuvent ne plus matcher) et on vide
        // HomeCacheStore pour forcer un recalcul au prochain affichage.
        findPreference<androidx.preference.ListPreference>("pref_iptv_language_filter")?.apply {
            setOnPreferenceChangeListener { _, _ ->
                val provider = com.streamflixreborn.streamflix.providers.MyIptvProvider
                provider.selectedCategoryLive = null
                provider.selectedCategoryMovie = null
                provider.selectedCategorySeries = null
                com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                    requireContext(), provider,
                )
                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.notifyProviderChanged()
                true
            }
        }

        findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.apply {
            val currentValue = UserPreferences.streamingcommunityDomain
            summary = currentValue
            if (currentValue == DEFAULT_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val typed = (newValue as String).trim()
                val BLOCKED = listOf("streamingcommunityz.green", "streamingunity.club", "streamingunity.bike", "streamingcommunityz.buzz")
                val effectiveDomain = if (BLOCKED.any { typed.contains(it) }) DEFAULT_DOMAIN_VALUE else typed
                UserPreferences.streamingcommunityDomain = effectiveDomain
                preference.summary = effectiveDomain
                if (effectiveDomain != typed) {
                    findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.text = null
                    Toast.makeText(requireContext(), getString(R.string.settings_streamingcommunity_domain_blocked), Toast.LENGTH_LONG).show()
                }
                true
            }
        }

        findPreference<Preference>("provider_streamingcommunity_domain_reset")?.setOnPreferenceClickListener {
            UserPreferences.streamingcommunityDomain = DEFAULT_DOMAIN_VALUE
            findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.apply {
                summary = DEFAULT_DOMAIN_VALUE
                text = null
            }
            Toast.makeText(requireContext(), getString(R.string.settings_streamingcommunity_domain_reset_done), Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<EditTextPreference>("provider_cuevana_domain")?.apply {
            val currentValue = UserPreferences.cuevanaDomain
            summary = currentValue
            if (currentValue == DEFAULT_CUEVANA_DOMAIN_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newDomainFromDialog = newValue as String
                UserPreferences.cuevanaDomain = newDomainFromDialog
                preference.summary = UserPreferences.cuevanaDomain
                if (UserPreferences.currentProvider?.name == "Cuevana 3") {
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
                true
            }
        }

        findPreference<EditTextPreference>("provider_poseidon_domain")?.apply {
            val currentValue = UserPreferences.poseidonDomain
            summary = currentValue
            if (currentValue == DEFAULT_POSEIDON_DOMAIN_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newDomainFromDialog = newValue as String
                UserPreferences.poseidonDomain = newDomainFromDialog
                preference.summary = UserPreferences.poseidonDomain
                if (UserPreferences.currentProvider?.name == "Poseidonhd2") {
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
                true
            }
        }

        findPreference<EditTextPreference>("TMDB_API_KEY")?.apply {
            summary = if (UserPreferences.tmdbApiKey.isEmpty()) getString(R.string.settings_tmdb_api_key_summary) else UserPreferences.tmdbApiKey
            text = UserPreferences.tmdbApiKey
            setOnPreferenceChangeListener { _, newValue ->
                val newKey = (newValue as String).trim()
                UserPreferences.tmdbApiKey = newKey
                summary = if (newKey.isEmpty()) getString(R.string.settings_tmdb_api_key_summary) else newKey
                val message = if (newKey.isEmpty()) {
                    getString(R.string.settings_tmdb_api_key_reset)
                } else {
                    getString(R.string.settings_tmdb_api_key_success)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                true
            }
        }

        findPreference<EditTextPreference>("SUBDL_API_KEY")?.apply {
            summary = if (UserPreferences.subdlApiKey.isEmpty()) getString(R.string.settings_subdl_api_key_summary) else UserPreferences.subdlApiKey
            text = UserPreferences.subdlApiKey
            setOnPreferenceChangeListener { _, newValue ->
                val newKey = (newValue as String).trim()
                UserPreferences.subdlApiKey = newKey
                summary = if (newKey.isEmpty()) getString(R.string.settings_subdl_api_key_summary) else newKey
                val message = if (newKey.isEmpty()) {
                    getString(R.string.settings_subdl_api_key_reset)
                } else {
                    getString(R.string.settings_subdl_api_key_success)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                true
            }
        }

        findPreference<Preference>("p_settings_about")?.apply {
            val palette = ThemeManager.palette(UserPreferences.selectedTheme)
            val titleStr = getString(R.string.settings_version_mobile)
            val spannableTitle = SpannableString(titleStr)
            spannableTitle.setSpan(ForegroundColorSpan(palette.tvHeaderPrimary), 0, titleStr.length, 0)
            title = spannableTitle
            
            val summaryStr = BuildConfig.VERSION_NAME
            val spannableSummary = SpannableString(summaryStr)
            spannableSummary.setSpan(ForegroundColorSpan(palette.tvHeaderSecondary), 0, summaryStr.length, 0)
            summary = spannableSummary
            
            isSelectable = false
            setOnPreferenceClickListener(null)
        }

        findPreference<Preference>("p_settings_telegram")?.setOnPreferenceClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+Jxyj7znoNQsyMjg0")))
            true
        }

        findPreference<Preference>("p_settings_kofi")?.setOnPreferenceClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/nanico")))
            true
        }

        findPreference<Preference>("p_settings_extractor_stats")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.fragment_extractor_stats_mobile)
            true
        }

        findPreference<Preference>("p_settings_extractor_toggle")?.setOnPreferenceClickListener {
            showExtractorToggleDialog()
            true
        }

        findPreference<Preference>("p_scan_resolver_qr")?.setOnPreferenceClickListener {
            scanResolverQrLauncher.launch(Intent(requireContext(), QrScannerActivity::class.java))
            true
        }

        findPreference<SwitchPreference>("AUTOPLAY")?.isChecked = UserPreferences.autoplay
        findPreference<SwitchPreference>("AUTOPLAY")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.autoplay = newValue as Boolean
            true
        }

        findPreference<SwitchPreference>("FORCE_EXTRA_BUFFERING")?.apply {
            isChecked = UserPreferences.forceExtraBuffering
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.forceExtraBuffering = newValue as Boolean
                true
            }
        }

        findPreference<SwitchPreference>("MINI_PLAYER_ENABLED")?.apply {
            isChecked = UserPreferences.miniPlayerEnabled
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.miniPlayerEnabled = newValue as Boolean
                if (!(newValue as Boolean)) {
                    MiniPlayerController.stop()
                }
                true
            }
        }

        findPreference<EditTextPreference>("p_settings_autoplay_buffer")?.apply {
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                val value = pref.text?.toLongOrNull() ?: 3L
                "$value s"
            }
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.autoplayBuffer = (newValue as String).toLongOrNull() ?: 3L
                true
            }
        }

        findPreference<SwitchPreference>("PLAYER_GESTURES")?.isChecked = UserPreferences.playerGestures
        findPreference<SwitchPreference>("PLAYER_GESTURES")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.playerGestures = newValue as Boolean
            true
        }

        findPreference<SwitchPreference>("KEEP_SCREEN_ON_WHEN_PAUSED")?.isChecked = UserPreferences.keepScreenOnWhenPaused
        findPreference<SwitchPreference>("KEEP_SCREEN_ON_WHEN_PAUSED")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.keepScreenOnWhenPaused = newValue as Boolean
            true
        }

        // 2026-05-15 : toggle "Garder l'écran toujours allumé"
        findPreference<SwitchPreference>("KEEP_SCREEN_ON_APP")?.apply {
            isChecked = UserPreferences.keepScreenOnApp
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                UserPreferences.keepScreenOnApp = enabled
                activity?.window?.let { w ->
                    if (enabled) {
                        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                true
            }
        }

        // 2026-05-22 : toggle écran "Qui regarde ?" au lancement.
        findPreference<SwitchPreference>("PROFILE_PICKER_ENABLED")?.apply {
            isChecked = UserPreferences.profilePickerEnabled
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.profilePickerEnabled = newValue as Boolean
                true
            }
        }

        findPreference<SwitchPreference>("UPDATE_CHECK_ENABLED")?.isChecked = UserPreferences.updateCheckEnabled
        findPreference<SwitchPreference>("UPDATE_CHECK_ENABLED")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.updateCheckEnabled = newValue as Boolean
            true
        }

        findPreference<SwitchPreference>("SERVER_AUTO_SUBTITLES_DISABLED")?.apply {
            isChecked = UserPreferences.serverAutoSubtitlesDisabled
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.serverAutoSubtitlesDisabled = newValue as Boolean
                true
            }
        }

        val HasConfigProvider = UserPreferences.currentProvider is ProviderConfigUrl
        findPreference<PreferenceCategory>("pc_provider_settings")?.apply {
            isVisible = HasConfigProvider
        }

        if (HasConfigProvider) {
            val provider = UserPreferences.currentProvider
            val configProvider = provider as? ProviderConfigUrl
            val portalProvider = provider as? ProviderPortalUrl
            var autoUpdateVal = false

            findPreference<SwitchPreference>("provider_autoupdate")?.apply {
                isVisible = portalProvider != null
                if (isVisible) {
                    autoUpdateVal = UserPreferences
                        .getProviderCache(
                            provider!!, UserPreferences
                                .PROVIDER_AUTOUPDATE
                        ) != "false"
                    isChecked = autoUpdateVal
                    setOnPreferenceChangeListener { _, newValue ->
                        val newState = newValue as Boolean
                        UserPreferences.setProviderCache(
                            null,
                            UserPreferences.PROVIDER_AUTOUPDATE,
                            newState.toString()
                        )
                        findPreference<EditTextPreference>("provider_url")?.isEnabled = newState == false
                        true
                    }
                }
            }

            findPreference<EditTextPreference>("provider_url")?.apply {
                isVisible = configProvider != null
                isEnabled = autoUpdateVal == false
                if (isVisible && provider != null && configProvider != null) {
                    summary = UserPreferences
                        .getProviderCache(
                            provider, UserPreferences
                                .PROVIDER_URL
                        )
                        .ifBlank { provider.defaultBaseUrl }
                    setOnBindEditTextListener { editText ->
                        editText.inputType = InputType.TYPE_CLASS_TEXT
                        editText.imeOptions = EditorInfo.IME_ACTION_DONE
                        editText.hint = configProvider.defaultBaseUrl

                        editText.setText(summary)
                    }
                    setOnPreferenceChangeListener { _, newValue ->
                        val toSave = (newValue as String)
                            .ifBlank { configProvider.defaultBaseUrl }
                            .trim()
                            .removeSuffix("/") + "/"
                        UserPreferences.setProviderCache(
                            null,
                            UserPreferences.PROVIDER_URL,
                            toSave
                        )
                        summary = toSave
                        viewLifecycleOwner.lifecycleScope.launch {
                            configProvider.onChangeUrl()
                            ProviderChangeNotifier.notifyProviderChanged()
                        }
                        true
                    }
                }
            }

            findPreference<EditTextPreference>("provider_portal_url")?.apply {
                isVisible = portalProvider != null
                if (isVisible && provider != null && portalProvider != null) {
                    summary = UserPreferences
                        .getProviderCache(
                            provider, UserPreferences
                                .PROVIDER_PORTAL_URL
                        )
                        .ifBlank { portalProvider.defaultPortalUrl }
                    setOnBindEditTextListener { editText ->
                        editText.inputType = InputType.TYPE_CLASS_TEXT
                        editText.imeOptions = EditorInfo.IME_ACTION_DONE
                        editText.hint = portalProvider.defaultPortalUrl
                        editText.setText(summary)
                    }
                    setOnPreferenceChangeListener { _, newValue ->
                        val toSave = (newValue as String)
                            .ifBlank { portalProvider.defaultPortalUrl }
                            .trim()
                            .removeSuffix("/") + "/"
                        summary = toSave
                        UserPreferences.setProviderCache(
                            null,
                            UserPreferences.PROVIDER_PORTAL_URL,
                            toSave
                        )
                        true
                    }
                }
            }

            findPreference<Preference>("provider_autoupdate_now")?.apply {
                isVisible = portalProvider != null
                setOnPreferenceClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        findPreference<EditTextPreference>("provider_url")?.summary =
                            configProvider!!.onChangeUrl(true)
                    }
                    true
                }
            }
        }

        findPreference<ListPreference>("p_doh_provider_url")?.apply {
            value = UserPreferences.dohProviderUrl
            summary = entry
            setOnPreferenceChangeListener { preference, newValue ->
                val newUrl = newValue as String
                UserPreferences.dohProviderUrl = newUrl
                DnsResolver.setDnsUrl(newUrl)
                if (preference is ListPreference) {
                    val index = preference.findIndexOfValue(newUrl)
                    if (index >= 0 && preference.entries != null && index < preference.entries.size) {
                        preference.summary = preference.entries[index]
                    } else {
                        preference.summary = null
                    }
                }
                Toast.makeText(requireContext(), getString(R.string.doh_provider_updated), Toast.LENGTH_LONG).show()
                true
            }
        }

        findPreference<SwitchPreference>("pc_frenchstream_new_interface")?.apply {
            isVisible = UserPreferences.currentProvider is FrenchStreamProvider
            if (isVisible) {
                val provider = UserPreferences.currentProvider ?: return@apply
                val useNewInterface = UserPreferences
                    .getProviderCache(
                        provider, UserPreferences
                            .PROVIDER_NEW_INTERFACE
                    ) != "false"
                isChecked = useNewInterface
                setOnPreferenceChangeListener { _, newValue ->
                    val newState = newValue as Boolean
                    UserPreferences.setProviderCache(
                        null,
                        UserPreferences.PROVIDER_NEW_INTERFACE,
                        newState.toString()
                    )
                    true
                }
            }
        }

        findPreference<ListPreference>("SELECTED_THEME")?.apply {
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                getString(ThemeManager.titleRes(pref.value ?: ThemeManager.DEFAULT))
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newTheme = newValue as String
                UserPreferences.selectedTheme = newTheme
                if (preference is ListPreference) {
                    preference.value = newTheme
                }
                requireActivity().apply {
                    finish()
                    startActivity(Intent(this, MainMobileActivity::class.java))
                }
                true
            }
        }

        findPreference<ListPreference>("APP_LANGUAGE")?.apply {
            entries = AppLanguageManager.buildLanguageEntries(requireContext())
            entryValues = AppLanguageManager.buildLanguageValues(requireContext())
            value = AppLanguageManager.getSelectedLanguage(requireContext())
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                pref.entries.getOrNull(pref.findIndexOfValue(pref.value))
                    ?: getString(R.string.settings_app_language_system)
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newLanguage = newValue as String
                AppLanguageManager.setSelectedLanguage(newLanguage)
                if (preference is ListPreference) {
                    preference.value = newLanguage
                }
                requireActivity().apply {
                    finish()
                    startActivity(
                        Intent(this, MainMobileActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
                true
            }
        }

        findPreference<SwitchPreference>("IMMERSIVE_MODE")?.apply {
            isChecked = UserPreferences.immersiveMode
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.immersiveMode = newValue as Boolean
                (activity as? MainMobileActivity)?.updateImmersiveMode()
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("ENABLE_TMDB")?.apply {
            isChecked = UserPreferences.enableTmdb
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val applyChange = {
                    UserPreferences.enableTmdb = enabled
                    updateParentalControlPreferenceState()
                    ProviderChangeNotifier.notifyProviderChanged()
                    val message = if (enabled) {
                        getString(R.string.settings_enable_tmdb_enabled)
                    } else {
                        getString(R.string.settings_enable_tmdb_disabled)
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }

                if (!enabled && UserPreferences.parentalControlPin.isNotBlank()) {
                    changeParentalSettingWithPinCheck(onVerified = applyChange)
                    false
                } else {
                    applyChange()
                    true
                }
            }
        }

        setupParentalControlPreferences()

        findPreference<Preference>("key_backup_export_mobile")?.setOnPreferenceClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "streamflix_mobile_backup_$timestamp.json"
            exportBackupLauncher.launch(fileName)
            true
        }

        findPreference<Preference>("key_backup_import_mobile")?.setOnPreferenceClickListener {
            importBackupLauncher.launch(arrayOf("application/json"))
            true
        }

        findPreference<Preference>("preferred_player_reset")?.setOnPreferenceClickListener {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .remove("preferred_smarttube_package")
                .apply()
            Toast.makeText(requireContext(), R.string.settings_trailer_player_reset, Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>("key_backup_refresh_cache_mobile")?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_refresh_cache_confirm)
                .setMessage(R.string.settings_refresh_cache_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val refreshed = backupRestoreManager.refreshCachesFromDatabase()
                        Toast.makeText(
                            requireContext(),
                            if (refreshed) {
                                R.string.settings_refresh_cache_success
                            } else {
                                R.string.settings_refresh_cache_success
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        findPreference<Preference>("key_backup_export_db_mobile")?.setOnPreferenceClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "streamflix_mobile_db_backup_$timestamp.zip"
            exportDbBackupLauncher.launch(fileName)
            true
        }

        findPreference<Preference>("key_backup_import_db_mobile")?.setOnPreferenceClickListener {
            importDbBackupLauncher.launch(arrayOf("application/zip"))
            true
        }

        findPreference<Preference>("key_device_sync_mobile")?.setOnPreferenceClickListener {
            startActivity(android.content.Intent(requireContext(), com.streamflixreborn.streamflix.activities.sync.DeviceSyncActivity::class.java))
            true
        }
    }

    private fun updateOverviewLabels() {
        val providerName = UserPreferences.currentProvider?.name

        findPreference<PreferenceScreen>("screen_provider")?.apply {
            title = getString(R.string.settings_provider_connection_title)
            summary = providerName?.let {
                getString(R.string.settings_screen_provider_summary_with_name, it)
            } ?: getString(R.string.settings_screen_provider_summary)
        }

        findPreference<PreferenceCategory>("pc_provider_settings")?.title = providerName?.let {
            getString(R.string.settings_provider_connection_category_title, it)
        } ?: getString(R.string.settings_category_provider_title)

        findPreference<PreferenceCategory>("pc_provider_empty_state")?.title = providerName?.let {
            getString(R.string.settings_provider_connection_category_title, it)
        } ?: getString(R.string.settings_provider_connection_title)
    }

    private fun updateProviderVisibilityState() {
        val isStreamingCommunity = false
        val isCuevana = UserPreferences.currentProvider?.name == "Cuevana 3"
        val isPoseidon = UserPreferences.currentProvider?.name == "Poseidonhd2"
        val hasConfigProvider = UserPreferences.currentProvider is ProviderConfigUrl
        val hasSpecificOptions = isStreamingCommunity || isCuevana || isPoseidon

        findPreference<PreferenceCategory>("pc_streamingcommunity_settings")?.isVisible = isStreamingCommunity
        findPreference<PreferenceCategory>("pc_cuevana_settings")?.isVisible = isCuevana
        findPreference<PreferenceCategory>("pc_poseidon_settings")?.isVisible = isPoseidon
        findPreference<PreferenceCategory>("pc_provider_empty_state")?.isVisible = !hasConfigProvider && !hasSpecificOptions
    }

    private fun setupParentalControlPreferences() {
        val pinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_PIN")
        val adminPinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_ADMIN_PIN")
        val removePinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_PIN")
        val removeAdminPinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_ADMIN_PIN")
        val maxAgePreference = findPreference<ListPreference>("PARENTAL_CONTROL_MAX_AGE")
        val unlockPreference = findPreference<Preference>("PARENTAL_CONTROL_UNLOCK")

        fun bindPinEditText(editText: android.widget.EditText) {
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            editText.imeOptions = EditorInfo.IME_ACTION_DONE
            editText.hint = getString(R.string.settings_parental_pin_hint)
            editText.setText("")
        }

        pinPreference?.setOnBindEditTextListener(::bindPinEditText)
        adminPinPreference?.setOnBindEditTextListener(::bindPinEditText)

        pinPreference?.setOnPreferenceClickListener {
            showParentalPinEditor(maxAgePreference)
            true
        }

        adminPinPreference?.setOnPreferenceClickListener {
            showAdminPinEditor()
            true
        }

        removePinPreference?.setOnPreferenceClickListener {
            changeParentalSettingWithPinCheck {
                UserPreferences.parentalControlPin = ""
                UserPreferences.parentalControlMaxAge = null
                maxAgePreference?.value = ""
                UserPreferences.unlockParentalControls()
                Toast.makeText(requireContext(), getString(R.string.settings_parental_pin_removed), Toast.LENGTH_SHORT).show()
                ProviderChangeNotifier.notifyProviderChanged()
                updateParentalControlPreferenceState()
            }
            true
        }

        removeAdminPinPreference?.setOnPreferenceClickListener {
            changeAdminSettingWithPinCheck {
                UserPreferences.parentalControlAdminPin = ""
                Toast.makeText(requireContext(), getString(R.string.settings_parental_admin_pin_removed), Toast.LENGTH_SHORT).show()
                updateParentalControlPreferenceState()
            }
            true
        }

        maxAgePreference?.setOnPreferenceChangeListener { _, newValue ->
            if (!UserPreferences.enableTmdb) return@setOnPreferenceChangeListener false
            if (UserPreferences.parentalControlPin.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.settings_parental_set_pin_first), Toast.LENGTH_SHORT).show()
                return@setOnPreferenceChangeListener false
            }

            val newMaxAgeValue = newValue as String
            val newMaxAge = newMaxAgeValue.toIntOrNull()

            changeParentalSettingWithPinCheck {
                UserPreferences.parentalControlMaxAge = newMaxAge
                maxAgePreference.value = newMaxAgeValue
                Toast.makeText(requireContext(), getString(R.string.settings_parental_max_age_saved), Toast.LENGTH_SHORT).show()
                ProviderChangeNotifier.notifyProviderChanged()
                updateParentalControlPreferenceState()
            }

            false
        }

        unlockPreference?.setOnPreferenceClickListener {
            if (UserPreferences.parentalControlAdminPin.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.settings_parental_set_admin_pin_first), Toast.LENGTH_SHORT).show()
            } else {
                promptForAdminPin {
                    UserPreferences.unlockParentalControls()
                    Toast.makeText(requireContext(), getString(R.string.settings_parental_unlocked), Toast.LENGTH_SHORT).show()
                    updateParentalControlPreferenceState()
                }
            }
            true
        }

        updateParentalControlPreferenceState()
    }

    private fun updateParentalControlPreferenceState() {
        val tmdbEnabled = UserPreferences.enableTmdb
        val pinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_PIN")
        val adminPinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_ADMIN_PIN")
        val removePinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_PIN")
        val removeAdminPinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_ADMIN_PIN")
        val maxAgePreference = findPreference<ListPreference>("PARENTAL_CONTROL_MAX_AGE")
        val unlockPreference = findPreference<Preference>("PARENTAL_CONTROL_UNLOCK")
        val isLocked = UserPreferences.isParentalControlTemporarilyLocked || UserPreferences.parentalControlHardLocked

        pinPreference?.apply {
            isEnabled = tmdbEnabled && !isLocked
            text = ""
            summary = when {
                !tmdbEnabled -> getString(R.string.settings_parental_requires_tmdb)
                UserPreferences.parentalControlHardLocked -> getString(R.string.settings_parental_locked_hard)
                UserPreferences.isParentalControlTemporarilyLocked -> getString(
                    R.string.settings_parental_locked_temporary,
                    lockRemainingMinutes()
                )
                UserPreferences.parentalControlPin.isBlank() -> getString(R.string.settings_parental_pin_not_set)
                else -> getString(R.string.settings_parental_pin_set)
            }
        }

        adminPinPreference?.apply {
            isEnabled = tmdbEnabled
            text = ""
            summary = when {
                !tmdbEnabled -> getString(R.string.settings_parental_requires_tmdb)
                UserPreferences.parentalControlAdminPin.isBlank() -> getString(R.string.settings_parental_admin_pin_not_set)
                else -> getString(R.string.settings_parental_admin_pin_set)
            }
        }

        removePinPreference?.apply {
            isVisible = tmdbEnabled && UserPreferences.parentalControlPin.isNotBlank()
            isEnabled = !isLocked
        }

        removeAdminPinPreference?.apply {
            isVisible = tmdbEnabled && UserPreferences.parentalControlAdminPin.isNotBlank()
            isEnabled = true
        }

        maxAgePreference?.apply {
            isEnabled = tmdbEnabled && !isLocked
            value = UserPreferences.parentalControlMaxAge?.toString().orEmpty()
            summary = when {
                !tmdbEnabled -> getString(R.string.settings_parental_requires_tmdb)
                UserPreferences.parentalControlHardLocked -> getString(R.string.settings_parental_locked_hard)
                UserPreferences.isParentalControlTemporarilyLocked -> getString(
                    R.string.settings_parental_locked_temporary,
                    lockRemainingMinutes()
                )
                UserPreferences.parentalControlPin.isBlank() -> getString(R.string.settings_parental_set_pin_first)
                UserPreferences.parentalControlMaxAge == null -> getString(R.string.settings_parental_max_age_disabled)
                else -> "${UserPreferences.parentalControlMaxAge}+"
            }
        }

        unlockPreference?.apply {
            isVisible = isLocked
            isEnabled = tmdbEnabled && UserPreferences.parentalControlAdminPin.isNotBlank()
            summary = when {
                UserPreferences.parentalControlAdminPin.isBlank() -> getString(R.string.settings_parental_set_admin_pin_first)
                UserPreferences.parentalControlHardLocked -> getString(R.string.settings_parental_locked_hard)
                UserPreferences.isParentalControlTemporarilyLocked -> getString(
                    R.string.settings_parental_locked_temporary,
                    lockRemainingMinutes()
                )
                else -> getString(R.string.settings_parental_unlock_summary)
            }
        }
    }

    private fun changeParentalSettingWithPinCheck(onVerified: () -> Unit) {
        when {
            UserPreferences.parentalControlHardLocked -> {
                Toast.makeText(requireContext(), getString(R.string.settings_parental_locked_hard), Toast.LENGTH_SHORT).show()
                updateParentalControlPreferenceState()
                return
            }
            UserPreferences.isParentalControlTemporarilyLocked -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_parental_locked_temporary, lockRemainingMinutes()),
                    Toast.LENGTH_SHORT
                ).show()
                updateParentalControlPreferenceState()
                return
            }
        }

        val currentPin = UserPreferences.parentalControlPin
        if (currentPin.isBlank()) {
            onVerified()
            return
        }

        promptForPin(
            titleRes = R.string.settings_parental_enter_current_pin_title,
            messageRes = R.string.settings_parental_enter_current_pin_message,
            onSubmit = { enteredPin ->
                if (enteredPin == currentPin) {
                    UserPreferences.registerParentalPinSuccess()
                    onVerified()
                    null
                } else {
                    UserPreferences.registerParentalPinFailure()
                    updateParentalControlPreferenceState()
                    when {
                        UserPreferences.parentalControlHardLocked -> R.string.settings_parental_locked_hard
                        UserPreferences.isParentalControlTemporarilyLocked -> R.string.settings_parental_locked_temporary
                        else -> R.string.settings_parental_invalid_pin
                    }.let { failureMessageRes ->
                        if (failureMessageRes == R.string.settings_parental_locked_temporary) {
                            getString(failureMessageRes, lockRemainingMinutes())
                        } else {
                            getString(failureMessageRes)
                        }
                    }
                }
            }
        )
    }

    private fun changeAdminSettingWithPinCheck(onVerified: () -> Unit) {
        val currentAdminPin = UserPreferences.parentalControlAdminPin
        if (currentAdminPin.isBlank()) {
            onVerified()
            return
        }

        promptForAdminPin(onVerified)
    }

    private fun promptForAdminPin(onVerified: () -> Unit) {
        val currentAdminPin = UserPreferences.parentalControlAdminPin
        if (currentAdminPin.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.settings_parental_set_admin_pin_first), Toast.LENGTH_SHORT).show()
            return
        }

        promptForPin(
            titleRes = R.string.settings_parental_enter_admin_pin_title,
            messageRes = R.string.settings_parental_enter_admin_pin_message,
            onSubmit = { enteredPin ->
                if (enteredPin == currentAdminPin) {
                    UserPreferences.unlockParentalControls()
                    onVerified()
                    null
                } else {
                    getString(R.string.settings_parental_invalid_admin_pin)
                }
            }
        )
    }

    private fun showParentalPinEditor(maxAgePreference: ListPreference?) {
        if (!UserPreferences.enableTmdb) {
            Toast.makeText(requireContext(), getString(R.string.settings_parental_requires_tmdb), Toast.LENGTH_SHORT).show()
            return
        }

        changeParentalSettingWithPinCheck {
            promptForPinValue(
                titleRes = R.string.settings_parental_pin_title,
                messageRes = if (UserPreferences.parentalControlPin.isBlank()) {
                    R.string.settings_parental_set_new_pin_message
                } else {
                    R.string.settings_parental_change_pin_message
                },
                allowBlank = UserPreferences.parentalControlPin.isNotBlank(),
                onSubmit = { newPin ->
                    when {
                        newPin.isBlank() -> {
                            UserPreferences.parentalControlPin = ""
                            UserPreferences.parentalControlMaxAge = null
                            maxAgePreference?.value = ""
                            UserPreferences.unlockParentalControls()
                            Toast.makeText(requireContext(), getString(R.string.settings_parental_pin_removed), Toast.LENGTH_SHORT).show()
                            ProviderChangeNotifier.notifyProviderChanged()
                            updateParentalControlPreferenceState()
                            null
                        }
                        newPin.length < 4 -> getString(R.string.settings_parental_pin_too_short)
                        else -> {
                            UserPreferences.parentalControlPin = newPin
                            Toast.makeText(requireContext(), getString(R.string.settings_parental_pin_saved), Toast.LENGTH_SHORT).show()
                            ProviderChangeNotifier.notifyProviderChanged()
                            updateParentalControlPreferenceState()
                            null
                        }
                    }
                }
            )
        }
    }

    private fun showAdminPinEditor() {
        changeAdminSettingWithPinCheck {
            promptForPinValue(
                titleRes = R.string.settings_parental_admin_pin_title,
                messageRes = if (UserPreferences.parentalControlAdminPin.isBlank()) {
                    R.string.settings_parental_set_new_admin_pin_message
                } else {
                    R.string.settings_parental_change_admin_pin_message
                },
                allowBlank = UserPreferences.parentalControlAdminPin.isNotBlank(),
                onSubmit = { newPin ->
                    when {
                        newPin.isBlank() -> {
                            UserPreferences.parentalControlAdminPin = ""
                            Toast.makeText(requireContext(), getString(R.string.settings_parental_admin_pin_removed), Toast.LENGTH_SHORT).show()
                            updateParentalControlPreferenceState()
                            null
                        }
                        newPin.length < 4 -> getString(R.string.settings_parental_pin_too_short)
                        else -> {
                            UserPreferences.parentalControlAdminPin = newPin
                            Toast.makeText(requireContext(), getString(R.string.settings_parental_admin_pin_saved), Toast.LENGTH_SHORT).show()
                            updateParentalControlPreferenceState()
                            null
                        }
                    }
                }
            )
        }
    }

    private fun promptForPin(
        titleRes: Int,
        messageRes: Int,
        onSubmit: (String) -> String?,
    ) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.settings_parental_pin_hint)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                input.error = null
                val errorMessage = onSubmit(input.text?.toString()?.trim().orEmpty())
                if (errorMessage == null) {
                    dialog.dismiss()
                } else {
                    input.setText("")
                    input.error = errorMessage
                    input.requestFocus()
                }
            }
        }

        dialog.show()
    }

    private fun promptForPinValue(
        titleRes: Int,
        messageRes: Int,
        allowBlank: Boolean,
        onSubmit: (String) -> String?,
    ) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.settings_parental_pin_hint)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                input.error = null
                val newValue = input.text?.toString()?.trim().orEmpty()
                if (newValue.isBlank() && !allowBlank) {
                    input.setText("")
                    input.error = getString(R.string.settings_parental_pin_too_short)
                    input.requestFocus()
                    return@setOnClickListener
                }

                val errorMessage = onSubmit(newValue)
                if (errorMessage == null) {
                    dialog.dismiss()
                } else {
                    input.setText("")
                    input.error = errorMessage
                    input.requestFocus()
                }
            }
        }

        dialog.show()
    }

    private fun lockRemainingMinutes(): Int {
        val millis = UserPreferences.parentalControlLockRemainingMillis
        return ((millis + 60_000L - 1L) / 60_000L).toInt().coerceAtLeast(1)
    }

    private suspend fun performBackupExport(uri: Uri) {
        withBackupLoading(R.string.backup_export_title) {
            val jsonData = withContext(Dispatchers.IO) {
                backupRestoreManager.exportUserData()
            }
            if (jsonData != null) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.writer().use { it.write(jsonData) }
                        Toast.makeText(requireContext(), getString(R.string.backup_export_success), Toast.LENGTH_LONG).show()
                    }
                } catch (e: IOException) {
                    Toast.makeText(requireContext(), getString(R.string.backup_export_error_write), Toast.LENGTH_LONG).show()
                    Log.e("BackupExportMobile", "Error writing backup file", e)
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.backup_data_not_generated), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun performBackupImport(uri: Uri) {
        withBackupLoading(R.string.backup_import_title) {
            try {
                val jsonData = withContext(Dispatchers.IO) {
                    val stringBuilder = StringBuilder()
                    requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { stringBuilder.append(it) }
                        }
                    }
                    stringBuilder.toString()
                }
                if (jsonData.isNotBlank()) {
                    val success = withContext(Dispatchers.IO) {
                        backupRestoreManager.importUserData(jsonData)
                    }
                    if (success) {
                        Toast.makeText(requireContext(), getString(R.string.backup_import_success), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.backup_import_error), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.backup_import_empty_file), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.backup_import_read_error), Toast.LENGTH_LONG).show()
                Log.e("BackupImportMobile", "Error reading/processing backup file", e)
            }
        }
    }

    private suspend fun performDatabaseBackupExport(uri: Uri) {
        withBackupLoading(R.string.backup_db_export_title) {
            val zipData = withContext(Dispatchers.IO) {
                backupRestoreManager.exportDatabaseZip()
            }
            if (zipData != null) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(zipData)
                        Toast.makeText(requireContext(), getString(R.string.backup_db_export_success), Toast.LENGTH_LONG).show()
                    }
                } catch (e: IOException) {
                    Toast.makeText(requireContext(), getString(R.string.backup_export_error_write), Toast.LENGTH_LONG).show()
                    Log.e("BackupExportMobile", "Error writing database backup file", e)
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.backup_data_not_generated), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun performDatabaseBackupImport(uri: Uri) {
        withBackupLoading(R.string.backup_db_import_title) {
            try {
                val zipBytes = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (zipBytes == null || zipBytes.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.backup_import_empty_file), Toast.LENGTH_LONG).show()
                    return@withBackupLoading
                }
                val success = withContext(Dispatchers.IO) {
                    backupRestoreManager.importDatabaseZip(zipBytes)
                }
                Toast.makeText(
                    requireContext(),
                    if (success) getString(R.string.backup_db_import_success) else getString(R.string.backup_import_error),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.backup_import_read_error), Toast.LENGTH_LONG).show()
                Log.e("BackupImportMobile", "Error reading/processing database backup file", e)
            }
        }
    }

    private suspend fun <T> withBackupLoading(titleRes: Int, block: suspend () -> T): T {
        showBackupLoadingDialog(titleRes)
        return try {
            block()
        } finally {
            hideBackupLoadingDialog()
        }
    }

    private fun showBackupLoadingDialog(titleRes: Int) {
        if (!isAdded) return
        if (backupLoadingDialog?.isShowing == true) {
            backupLoadingDialog?.setTitle(titleRes)
            return
        }

        val contentView = LayoutInflater.from(requireContext()).inflate(
            R.layout.layout_is_loading_mobile,
            null
        )
        contentView.findViewById<android.widget.TextView>(R.id.tv_is_loading_error)?.visibility = View.GONE
        contentView.findViewById<Group>(R.id.g_is_loading_retry)?.visibility = View.GONE

        backupLoadingDialog = AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setView(contentView)
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                show()
            }
    }

    private fun hideBackupLoadingDialog() {
        backupLoadingDialog?.dismiss()
        backupLoadingDialog = null
    }

    override fun onResume() {
        super.onResume()
        applyScreenTitle()
        updateOverviewLabels()
        updateProviderVisibilityState()

        findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.apply {
            val currentValue = UserPreferences.streamingcommunityDomain
            summary = currentValue
            if (currentValue == DEFAULT_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
        }

        findPreference<EditTextPreference>("TMDB_API_KEY")?.apply {
            summary = if (UserPreferences.tmdbApiKey.isEmpty()) getString(R.string.settings_tmdb_api_key_summary) else UserPreferences.tmdbApiKey
            text = UserPreferences.tmdbApiKey
        }

        findPreference<EditTextPreference>("SUBDL_API_KEY")?.apply {
            summary = if (UserPreferences.subdlApiKey.isEmpty()) getString(R.string.settings_subdl_api_key_summary) else UserPreferences.subdlApiKey
            text = UserPreferences.subdlApiKey
        }

        findPreference<ListPreference>("p_doh_provider_url")?.apply {
            summary = entry
        }

        findPreference<ListPreference>("APP_LANGUAGE")?.value =
            AppLanguageManager.getSelectedLanguage(requireContext())

        findPreference<SwitchPreference>("AUTOPLAY")?.isChecked = UserPreferences.autoplay
        findPreference<SwitchPreference>("FORCE_EXTRA_BUFFERING")?.isChecked = UserPreferences.forceExtraBuffering
        findPreference<SwitchPreference>("PLAYER_GESTURES")?.isChecked = UserPreferences.playerGestures
        findPreference<SwitchPreference>("KEEP_SCREEN_ON_WHEN_PAUSED")?.isChecked = UserPreferences.keepScreenOnWhenPaused
        findPreference<SwitchPreferenceCompat>("ENABLE_TMDB")?.isChecked = UserPreferences.enableTmdb
        updateParentalControlPreferenceState()
    }

    // 2026-05-27 : dialog pour activer/désactiver + cœur par provider.
    private fun showExtractorToggleDialog() {
        val store = com.streamflixreborn.streamflix.utils.ExtractorToggleStore
        val allNames = store.allExtractorNames()
        if (allNames.isEmpty()) return
        val providerName = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider?.name ?: ""
        val disabled = store.getDisabled().toMutableSet()
        val favorites = if (providerName.isNotEmpty()) store.getFavorites(providerName).toMutableSet() else mutableSetOf()

        val listView = android.widget.ListView(requireContext())
        val adapter = ExtractorToggleAdapter(requireContext(), allNames, disabled, favorites, providerName)
        listView.adapter = adapter

        val titleSuffix = if (providerName.isNotEmpty()) " — $providerName" else ""
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Gérer les sources (${allNames.size})$titleSuffix")
            .setView(listView)
            .setPositiveButton("OK") { _, _ ->
                store.setDisabled(disabled)
                if (providerName.isNotEmpty()) store.setFavorites(providerName, favorites)
                val disCount = disabled.size
                val favCount = favorites.size
                val msg = buildString {
                    if (disCount > 0) append("$disCount désactivée${if (disCount > 1) "s" else ""}")
                    if (favCount > 0) {
                        if (disCount > 0) append(" · ")
                        append("$favCount favori${if (favCount > 1) "s" else ""}")
                    }
                    if (disCount == 0 && favCount == 0) append("Toutes les sources activées")
                }
                android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .setNeutralButton("Tout réinitialiser") { _, _ ->
                store.setDisabled(emptySet())
                if (providerName.isNotEmpty()) store.setFavorites(providerName, emptySet())
                android.widget.Toast.makeText(requireContext(), "Sources réinitialisées", android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Adapter pour la liste des extracteurs : checkbox activé/désactivé + cœur favori.
     */
    private class ExtractorToggleAdapter(
        private val ctx: android.content.Context,
        private val names: List<String>,
        private val disabled: MutableSet<String>,
        private val favorites: MutableSet<String>,
        private val providerName: String,
    ) : android.widget.BaseAdapter() {
        override fun getCount() = names.size
        override fun getItem(pos: Int) = names[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = convertView ?: android.view.LayoutInflater.from(ctx)
                .inflate(com.streamflixreborn.streamflix.R.layout.item_extractor_toggle, parent, false)
            val cb = view.findViewById<android.widget.CheckBox>(com.streamflixreborn.streamflix.R.id.cb_enabled)
            val heart = view.findViewById<android.widget.ImageView>(com.streamflixreborn.streamflix.R.id.iv_favorite)
            val nameLower = names[position].lowercase()

            cb.setOnCheckedChangeListener(null)
            cb.text = names[position]
            cb.isChecked = nameLower !in disabled
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) disabled.remove(nameLower) else disabled.add(nameLower)
            }

            if (providerName.isNotEmpty()) {
                heart.visibility = android.view.View.VISIBLE
                updateHeartIcon(heart, nameLower in favorites)
                heart.setOnClickListener {
                    if (nameLower in favorites) favorites.remove(nameLower) else favorites.add(nameLower)
                    updateHeartIcon(heart, nameLower in favorites)
                }
            } else {
                heart.visibility = android.view.View.GONE
            }
            return view
        }

        private fun updateHeartIcon(iv: android.widget.ImageView, isFav: Boolean) {
            iv.setImageResource(
                if (isFav) com.streamflixreborn.streamflix.R.drawable.ic_favorite_enable
                else com.streamflixreborn.streamflix.R.drawable.ic_favorite_disable
            )
            iv.alpha = if (isFav) 1.0f else 0.4f
        }
    }
}
