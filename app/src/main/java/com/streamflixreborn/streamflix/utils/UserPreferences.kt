package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.Provider.Companion.providers
import com.streamflixreborn.streamflix.providers.TmdbProvider
import androidx.core.content.edit
import com.streamflixreborn.streamflix.database.AppDatabase
import org.json.JSONObject

object UserPreferences {

    private const val TAG = "UserPrefsDebug"

    /** Lock for read-modify-write operations on providerCache and failedChannels. */
    private val lock = Any()

    private lateinit var prefs: SharedPreferences

    // Default DoH Provider URL (Cloudflare)
    private const val DEFAULT_DOH_PROVIDER_URL = "https://cloudflare-dns.com/dns-query"
    const val DOH_DISABLED_VALUE = "" // Value to represent DoH being disabled
    private const val DEFAULT_STREAMINGCOMMUNITY_DOMAIN = "streamingunity.biz"
    private const val DEFAULT_CUEVANA_DOMAIN = "cuevana.gs"
    private const val DEFAULT_POSEIDON_DOMAIN = "www.poseidonhd2.co"

    const val PROVIDER_URL = "URL"
    const val PROVIDER_LOGO = "LOGO"
    const val PROVIDER_PORTAL_URL = "PORTAL_URL"
    const val PROVIDER_AUTOUPDATE = "AUTOUPDATE_URL"
    const val PROVIDER_NEW_INTERFACE = "NEW_INTERFACE"

    lateinit var providerCache: JSONObject

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message())
        }
    }

    fun setup(context: Context) {
        val prefsName = "${BuildConfig.APPLICATION_ID}.preferences"
        prefs = context.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE,
        )
        if (::prefs.isInitialized) {
            debugLog { "prefs initialized: ${prefs.hashCode()}" }

            val jsonString = Key.PROVIDER_CACHE.getString() ?: "{}"
            providerCache = runCatching { JSONObject(jsonString) }.getOrDefault(JSONObject())

            // 2026-05-14 (user "il faut que ça soit pareil pour tout le monde,
            // je veux pas que ça soit Mon IPTV qui s'ouvre en premier") :
            // wipe one-shot des clés CURRENT_PROVIDER_<profileId> et de la
            // clé globale legacy. Garantit que tous les profils (y compris
            // Principal) démarrent sur Home Fournisseur après cette update.
            val WIPE_KEY = "_v188_currentprovider_wipe_done"
            if (!prefs.getBoolean(WIPE_KEY, false)) {
                val toRemove = prefs.all.keys.filter { it.startsWith("CURRENT_PROVIDER") }
                val editor = prefs.edit()
                toRemove.forEach { editor.remove(it) }
                editor.putBoolean(WIPE_KEY, true)
                editor.apply()
                Log.d(TAG, "One-shot wipe: cleared ${toRemove.size} CURRENT_PROVIDER keys")
            }
        }
    }


    // Flag pour la recherche globale (toggle dans SearchFragment).
    // 2026-05-20 : default = false — c'est à l'utilisateur de l'activer.
    @Volatile
    var isGlobalSearchEnabled: Boolean = false

    // 2026-06-30 (user "case à cocher dans l'option lecteur externe : toujours
    //   l'utiliser, ne plus passer par le lecteur interne, et mémoriser le
    //   lecteur choisi") — persisté, mobile + TV.
    private const val KEY_ALWAYS_EXTERNAL_PLAYER = "always_external_player"
    private const val KEY_EXTERNAL_PLAYER_PACKAGE = "external_player_package"

    /** Si true ET externalPlayerPackage non nul → la lecture lance directement
     *  le lecteur externe mémorisé, sans sélecteur ni lecteur interne. */
    var alwaysUseExternalPlayer: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_ALWAYS_EXTERNAL_PLAYER, false) else false
        set(value) { if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_ALWAYS_EXTERNAL_PLAYER, value).apply() }

    // 2026-07-11 (user "sur émulateur x86 elle prend la version téléphone alors que le mieux
    //   c'est la TV → il faut un choix") : override manuel de l'interface. Le dispatcher
    //   (SplashActivity) le respecte AVANT l'auto-détection leanback. "auto" = détection device.
    const val UI_MODE_AUTO = "auto"
    const val UI_MODE_MOBILE = "mobile"
    const val UI_MODE_TV = "tv"
    private const val KEY_UI_MODE_OVERRIDE = "ui_mode_override"
    var uiModeOverride: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_UI_MODE_OVERRIDE, UI_MODE_AUTO) ?: UI_MODE_AUTO else UI_MODE_AUTO
        set(value) { if (::prefs.isInitialized) prefs.edit().putString(KEY_UI_MODE_OVERRIDE, value).apply() }

    // 2026-07-10 (user "pouvoir désactiver les backups UN PAR UN, pas tous d'un coup") :
    //   MultiSelectListPreference « ENABLED_BACKUPS » = ensemble des sources COCHÉES (= actives).
    //   - jamais configuré (null) → TOUTES actives (défaut).
    //   - une source hors de la liste connue (BACKUP_SOURCE_KEYS) → toujours active (on ne
    //     désactive QUE ce que l'utilisateur a explicitement décoché).
    private const val KEY_ENABLED_BACKUPS = "ENABLED_BACKUPS"
    // 2026-07-12 : compteur de migration. Quand on ajoute une nouvelle source de backup
    //   (ex: Coflix Boston), on bump ce compteur → la 1ère vérification ajoute les sources
    //   manquantes au set sauvé. L'user peut ensuite les désactiver dans les Paramètres.
    private const val KEY_BACKUP_MIGRATION_V = "BACKUP_MIGRATION_V"
    private const val CUR_BACKUP_MIGRATION = 2 // bump quand on ajoute de nouvelles sources

    // 2026-07-13 (user "une option au-dessus de Gérer les sources pour activer/désactiver les
    //   backups — ça permet de tester si les sources natives du provider sont encore valables") :
    //   MASTER switch. Décoché → AUCUN backup (seuls les serveurs natifs du provider s'affichent).
    private const val KEY_BACKUPS_ENABLED = "pref_backups_enabled"
    val backupsEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_BACKUPS_ENABLED, true) else true

    // 2026-07-13 (user "quand un backup/provider échoue parce que l'URL a changé, je veux
    //   recevoir UNE notification GitHub (xdata-mix/onyx-crash-reports) pour réparer sans
    //   chercher — sans spam, 1 fois par URL") : master switch du rapport auto de sources cassées.
    private const val KEY_REPORT_BROKEN = "pref_report_broken_sources"
    val reportBrokenSources: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_REPORT_BROKEN, true) else true

    fun isBackupSourceEnabled(source: String): Boolean {
        if (!::prefs.isInitialized) return true
        // Master switch OFF → tous les backups désactivés (test des serveurs natifs seuls).
        if (!prefs.getBoolean(KEY_BACKUPS_ENABLED, true)) return false
        val enabled = prefs.getStringSet(KEY_ENABLED_BACKUPS, null) ?: return true
        if (source !in com.streamflixreborn.streamflix.utils.BackupRegistry.BACKUP_SOURCE_KEYS) return true
        // Migration : si de nouvelles sources ont été ajoutées depuis la dernière config,
        //   les ajouter automatiquement au set enabled (1 fois par version).
        val migV = prefs.getInt(KEY_BACKUP_MIGRATION_V, 1)
        if (migV < CUR_BACKUP_MIGRATION) {
            val expanded = enabled.toMutableSet()
            com.streamflixreborn.streamflix.utils.BackupRegistry.BACKUP_SOURCE_KEYS.forEach {
                if (it !in expanded) expanded.add(it)
            }
            prefs.edit()
                .putStringSet(KEY_ENABLED_BACKUPS, expanded)
                .putInt(KEY_BACKUP_MIGRATION_V, CUR_BACKUP_MIGRATION)
                .apply()
            return source in expanded
        }
        return source in enabled
    }

    /** Package du lecteur externe mémorisé (ex. org.videolan.vlc). null = aucun. */
    var externalPlayerPackage: String?
        get() = if (::prefs.isInitialized) prefs.getString(KEY_EXTERNAL_PLAYER_PACKAGE, null) else null
        set(value) {
            if (!::prefs.isInitialized) return
            if (value.isNullOrBlank()) prefs.edit().remove(KEY_EXTERNAL_PLAYER_PACKAGE).apply()
            else prefs.edit().putString(KEY_EXTERNAL_PLAYER_PACKAGE, value).apply()
        }

    /**
     * 2026-06-17 (user "il faut faire un bouton de bascule, de base il est pas
     * activé et on l'active") : toggle pour activer le tunnel Shadowsocks via
     * serveurs free VYPN, pour débloquer Vavoo en France métropolitaine sans
     * VPN externe. OFF par défaut (= Vavoo direct, comportement actuel).
     *
     * 2026-06-17 v5 (Fred/Fantomial/Bob/Nani : "panneau vide après update/TV
     * reboot, désactiver/réactiver répare") = VRAI BUG = flag pas persisté.
     * Au cold start le flag retombait à false → tunnel pas démarré → Vavoo
     * geoblock France → panneau vide. Maintenant persisté SharedPrefs.
     */
    private const val KEY_VAVOO_USE_TUNNEL = "vavoo_use_tunnel"
    /**
     * 2026-06-18 (user "On appuie sur un bouton Ça active le 2e VPN c'est pas
     * compliqué") : `vavooUseTunnel` retourne true pour VYPN ET pour "VPN 2"
     * (= TUNNEL_MODE_PLANETVPN, repensé en "VYPN serveur alternatif"). Permet
     * à StreamFlixApp cold-start de démarrer le tunnel sur le bon serveur.
     */
    var vavooUseTunnel: Boolean
        get() {
            if (!::prefs.isInitialized) return false
            // Si le nouveau pref enum est posé, on suit le mode
            val mode = prefs.getString(KEY_VAVOO_TUNNEL_MODE, null)
            if (mode != null) {
                return mode == TUNNEL_MODE_VYPN || mode == TUNNEL_MODE_PLANETVPN
            }
            return prefs.getBoolean(KEY_VAVOO_USE_TUNNEL, false)
        }
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_VAVOO_USE_TUNNEL, value).apply()
            }
        }

    /** Index "skip best N" à passer à VavooTunnel.start() en fonction du mode.
     *  0 = serveur primaire (= meilleur load), 1 = serveur secondaire (= VPN 2). */
    fun vavooTunnelSkipBestN(): Int {
        return when (vavooTunnelMode) {
            TUNNEL_MODE_PLANETVPN -> 1
            else -> 0
        }
    }

    /**
     * 2026-06-18 — Mode tunnel Vavoo unifié (mutex strict — un seul actif).
     *   - "OFF" (= aucun tunnel, Vavoo direct)
     *   - "VYPN" (= notre tunnel Shadowsocks intégré, ex-vavooUseTunnel=true)
     *   - "PLANETVPN" (= "VPN 2", route via le SOCKS5 local de l'app PlanetVPN)
     *
     * Rétrocompat : si la nouvelle pref n'existe pas, on dérive du flag
     * vavooUseTunnel pré-existant (true → VYPN, false → OFF).
     *
     * Le setter applique aussi la mutex : sélectionner VYPN ou PLANETVPN met
     * vavooUseTunnel = (mode == VYPN) pour que tout code legacy reste cohérent.
     */
    const val TUNNEL_MODE_OFF = "OFF"
    const val TUNNEL_MODE_VYPN = "VYPN"
    const val TUNNEL_MODE_PLANETVPN = "PLANETVPN"
    private const val KEY_VAVOO_TUNNEL_MODE = "vavoo_tunnel_mode"
    var vavooTunnelMode: String
        get() {
            if (!::prefs.isInitialized) return TUNNEL_MODE_OFF
            prefs.getString(KEY_VAVOO_TUNNEL_MODE, null)?.let { return it }
            // Migration depuis l'ancien Boolean vavooUseTunnel
            return if (prefs.getBoolean(KEY_VAVOO_USE_TUNNEL, false)) TUNNEL_MODE_VYPN
                   else TUNNEL_MODE_OFF
        }
        set(value) {
            if (!::prefs.isInitialized) return
            val sanitized = when (value) {
                TUNNEL_MODE_VYPN, TUNNEL_MODE_PLANETVPN -> value
                else -> TUNNEL_MODE_OFF
            }
            prefs.edit()
                .putString(KEY_VAVOO_TUNNEL_MODE, sanitized)
                .putBoolean(KEY_VAVOO_USE_TUNNEL, sanitized == TUNNEL_MODE_VYPN)
                .apply()
        }

    /** 2026-05-13 (user "à l'ouverture d'un profil pas par maman c'est Mon IPTV
     *  qui s'ouvre au lieu du home fournisseur") : currentProvider est
     *  maintenant stocké PAR PROFIL. Chaque profil a sa propre clé
     *  CURRENT_PROVIDER_<profileId>. Un nouveau profil démarre avec
     *  currentProvider=null → l'app affiche le picker de providers. */
    private fun currentProviderKey(): String {
        val profileId = ProfileManager.currentProfileIdOrDefault()
        return "CURRENT_PROVIDER_$profileId"
    }

    var currentProvider: Provider?
        get() {
            val perProfileKey = currentProviderKey()
            // 2026-05-14 (user "je veux pas que ça soit mon IPTV qui s'ouvre
            // en premier") : pas de migration depuis la clé globale legacy.
            // TOUS les profils démarrent avec currentProvider=null → app ouvre
            // Home Fournisseur. L'user choisit lui-même à chaque profil.
            val providerName = prefs.getString(perProfileKey, null)
            if (providerName?.startsWith("TMDb (") == true && providerName.endsWith(")")) {
                val lang = providerName.substringAfter("TMDb (").substringBefore(")")
                return TmdbProvider(lang)
            }
            return Provider.providers.keys.find { it.name == providerName }
        }
        set(value) {
            // 2026-05-19 v85c (user "il faut faire en sorte que la fermeture
            //   de ce provider qu'il y ait vraiment un lavage de fée") :
            //   quand on QUITTE Mon IPTV (vers un autre provider), purge
            //   tout le state mémoire de MyIptvProvider — cache parsé,
            //   classificationCache, filtres user. Le cache DISQUE
            //   (.tsv + .classif3) reste pour permettre une réouverture
            //   rapide. Évite que des chaînes d'une session précédente
            //   réapparaissent quand on retourne sur Mon IPTV plus tard.
            // 2026-07-12 : lire currentProvider (le getter) force le chargement de
            //   Provider.providers → 15+ class-inits → 2.7s sur Chromecast ARM.
            //   Quand value=null (cold start boot), on SAUTE la lecture : null n'est
            //   pas MyIptv, donc le cleanup est inutile. Provider.providers sera chargé
            //   plus tard (1er accès home) quand le main thread est libre → zéro fige.
            if (value != null) {
                try {
                    val previous = currentProvider
                    val previousIsMyIptv = previous is com.streamflixreborn.streamflix.providers.MyIptvProvider
                    val nextIsMyIptv = value is com.streamflixreborn.streamflix.providers.MyIptvProvider
                    if (previousIsMyIptv && !nextIsMyIptv) {
                        com.streamflixreborn.streamflix.providers.MyIptvProvider.clearCache()
                    }
                } catch (_: Throwable) {}
                // 2026-07-12 (user « sur une VOD le serveur met du temps, un truc monopolise la
                //   mémoire ») : DÈS le changement de provider (bien AVANT la lecture), on suspend
                //   les scans IPTV OLA/Vegeta s'ils ne sont PAS la cible. Avant, releaseMemory ne
                //   se déclenchait qu'après 10s de lecture → le probe IPTV (m3u de 30000 chaînes)
                //   saturait encore la RAM pendant la recherche des serveurs VOD.
                //   2026-07-12 v2 (user « l'interface est bloquée ») : EN ARRIÈRE-PLAN. Référencer
                //   les objets OLA/Vegeta force leur <clinit> (lourd, ~2-3s à froid sur Chromecast) ;
                //   sur le thread principal ça figeait l'UI. Un thread daemon l'évite. On ne le fait
                //   QUE si l'IPTV a réellement été chargé cette session (évite l'init inutile à froid).
                val nextIsOla = value is com.streamflixreborn.streamflix.providers.OlaTvProvider
                val nextIsVegeta = value is com.streamflixreborn.streamflix.providers.VegetaTvProvider
                if (!nextIsOla || !nextIsVegeta) {
                    kotlin.concurrent.thread(isDaemon = true, name = "iptv-release") {
                        try {
                            if (!nextIsOla && com.streamflixreborn.streamflix.providers.OlaTvProvider.wasEverLoaded()) {
                                com.streamflixreborn.streamflix.providers.OlaTvProvider.releaseMemory()
                            }
                            if (!nextIsVegeta && com.streamflixreborn.streamflix.providers.VegetaTvProvider.wasEverLoaded()) {
                                com.streamflixreborn.streamflix.providers.VegetaTvProvider.releaseMemory()
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
            // CRITICO: Resetta l'istanza del database prima di cambiare provider
            // per forzare la creazione di un nuovo database file corretto.
            AppDatabase.resetInstance()

            val perProfileKey = currentProviderKey()
            if (value == null) {
                prefs.edit().remove(perProfileKey).apply()
            } else {
                prefs.edit().putString(perProfileKey, value.name).apply()
            }
            // Garde aussi la clé globale legacy à jour pour compat (lecteurs
            // tiers qui pourraient encore la lire).
            Key.CURRENT_PROVIDER.setString(value?.name)
            runCatching {
                ArtworkRepairScheduler.schedule(StreamFlixApp.instance, value)
            }
            // 2026-05-17 (user "fermeture du provider doit être vidé") : clear
            //   le cache DVR sur changement de provider — segments précédents
            //   inutiles + risque de servir du contenu obsolète.
            try {
                StreamFlixApp.clearLiveCache()
            } catch (_: Exception) { }
            // Notify all ViewModels that the provider has changed
            ProviderChangeNotifier.notifyProviderChanged()
        }

    fun getProviderCache(provider: Provider, key: String): String {
        return providerCache
            .optJSONObject(provider.name)
            ?.optString(key)
            .orEmpty()
    }

    fun setProviderCache(provider: Provider?, key: String, value: String) {
        synchronized(lock) {
            val providerName = provider?.name ?: currentProvider?.name ?: return
            val innerJson = providerCache.optJSONObject(providerName)
                ?: JSONObject().also { providerCache.put(providerName, it) }
            innerJson.put(key, value)
            commitString(Key.PROVIDER_CACHE, providerCache.toString())
        }
    }

    fun clearProviderCache(providerName: String) {
        synchronized(lock) {
            if (providerCache.has(providerName)) {
                debugLog { "CACHE: removing stored data for $providerName" }
                providerCache.remove(providerName)
                commitString(Key.PROVIDER_CACHE, providerCache.toString())
            }
        }
    }

    var currentLanguage: String?
        get() = Key.CURRENT_LANGUAGE.getString()
        set(value) = Key.CURRENT_LANGUAGE.setString(value)

    var providerLanguage: String?
        get() = Key.PROVIDER_LANGUAGE.getString()
        set(value) = Key.PROVIDER_LANGUAGE.setString(value)

    /** Index of the selected tab on the providers home (mobile): 0=Films/Séries,
     *  1=Animés, 2=TV/IPTV. Persisted across app restarts. */
    var providerTabIndex: Int
        get() = Key.PROVIDER_TAB_INDEX.getInt() ?: 0
        set(value) = Key.PROVIDER_TAB_INDEX.setInt(value)

    // ── Failed channels (hidden until a working server is found) ──

    private fun getFailedChannels(): MutableSet<String> {
        val raw = Key.FAILED_CHANNELS.getString() ?: return mutableSetOf()
        return raw.split("||").filter { it.isNotBlank() }.toMutableSet()
    }

    /**
     * Synchronous write for critical read-modify-write operations.
     * Uses commit() instead of apply() to guarantee the write is persisted
     * before the lock is released, preventing stale reads from other threads.
     */
    private fun commitString(key: Key, value: String) {
        prefs.edit().putString(key.name, value).commit()
    }

    fun markChannelFailed(channelId: String) {
        synchronized(lock) {
            val set = getFailedChannels()
            set.add(channelId)
            commitString(Key.FAILED_CHANNELS, set.joinToString("||"))
        }
    }

    fun unmarkChannelFailed(channelId: String) {
        synchronized(lock) {
            val set = getFailedChannels()
            if (set.remove(channelId)) {
                commitString(Key.FAILED_CHANNELS, set.joinToString("||"))
            }
        }
    }

    fun isChannelFailed(channelId: String): Boolean {
        synchronized(lock) {
            return getFailedChannels().contains(channelId)
        }
    }

    fun clearFailedChannels() {
        synchronized(lock) {
            Key.FAILED_CHANNELS.remove()
        }
    }

    var captionTextSize: Float
        get() = Key.CAPTION_TEXT_SIZE.getFloat()
            ?: PlayerSettingsView.Settings.Subtitle.Style.TextSize.DEFAULT.value
        set(value) {
            Key.CAPTION_TEXT_SIZE.setFloat(value)
        }

    var autoplay: Boolean
        get() = Key.AUTOPLAY.getBoolean() ?: true
        set(value) {
            Key.AUTOPLAY.setBoolean(value)
        }

    // 2026-05-15 (user "je voudrais ajouter une option sur l'application pour
    // éviter que les écrans mettre en veille tous les 5 minutes") : flag global
    // pour garder l'écran allumé pendant l'utilisation de l'app (navigation
    // home / picker / catalogue / etc.). N'affecte PAS la lecture vidéo
    // 2026-07-12 (user « les télés s'éteignent toutes seules au bout d'un moment ») : défaut
    //   passé à ON. Garde l'écran allumé dans toute l'app (évite la mise en veille TV pendant
    //   la navigation / entre deux épisodes). Reste désactivable dans les réglages.
    var keepScreenOnApp: Boolean
        get() = Key.KEEP_SCREEN_ON_APP.getBoolean() ?: true
        set(value) {
            Key.KEEP_SCREEN_ON_APP.setBoolean(value)
        }

    // 2026-07-12 : défaut ON aussi — garde l'écran allumé quand la lecture est EN PAUSE (sinon la
    //   TV s'éteint si on met en pause un moment).
    var keepScreenOnWhenPaused: Boolean
        get() = Key.KEEP_SCREEN_ON_WHEN_PAUSED.getBoolean() ?: true
        set(value) {
            Key.KEEP_SCREEN_ON_WHEN_PAUSED.setBoolean(value)
        }

    /** 2026-06-21 (user "quand on quitte l'application, souvent elle se met en
     *  tout petit comme ça [PiP]. Il faudrait mettre une option de base dans
     *  paramètres > Apparence pour désactiver ce comportement. Beaucoup de
     *  personnes ne veulent pas que ça soit comme ça quand ils quittent —
     *  ils veulent quitter tout simplement") :
     *  Si false (default), le PiP ne se déclenche PAS quand l'user quitte
     *  l'app (home button / app switch). Si true, le player passe en PiP
     *  flottant (= comportement historique). */
    var pipOnExit: Boolean
        get() = Key.PIP_ON_EXIT.getBoolean() ?: false
        set(value) {
            Key.PIP_ON_EXIT.setBoolean(value)
        }

    /** 2026-06-14 (user "à chaque fin d'épisode il y a un overlay pour passer
     *  à l'épisode suivant, ajoute une option pour le désactiver pour ceux qui
     *  veulent aller jusqu'à la fin") : si false, on ne montre PAS l'overlay
     *  "Lancer maintenant / dans Xs" en fin d'épisode → les users qui aiment
     *  regarder le générique jusqu'au bout ne sont pas interrompus visuellement.
     *  Default true (= comportement actuel preservé). */
    var showNextEpisodeOverlay: Boolean
        get() = Key.SHOW_NEXT_EPISODE_OVERLAY.getBoolean() ?: true
        set(value) {
            Key.SHOW_NEXT_EPISODE_OVERLAY.setBoolean(value)
        }

    /** 2026-06-13 (user "l'épisode next en automatique a tendance à sauter
     *  les épisodes") : toggle pour désactiver l'auto-skip d'épisode anime
     *  cassé. Quand activé (= comportement historique), si tous les serveurs
     *  d'un épisode anime échouent, on passe automatiquement à l'épisode
     *  suivant (jusqu'à 5 sauts en cascade max). Quand désactivé, on reste
     *  sur l'épisode courant + retour à la fiche série au lieu de sauter →
     *  l'user peut choisir manuellement le prochain épisode. Default false
     *  (= ne saute plus par défaut, l'user activera s'il veut le binge). */
    var animeAutoSkipBroken: Boolean
        get() = Key.ANIME_AUTO_SKIP_BROKEN.getBoolean() ?: false
        set(value) {
            Key.ANIME_AUTO_SKIP_BROKEN.setBoolean(value)
        }

    /** 2026-06-13 (porté upstream v1.7.224 Favorite Providers) :
     *  Liste des noms de providers favoris (= long-press sur leur logo).
     *  Stocké comme Set<String> de noms. Utilisé pour :
     *   - Afficher une étoile en overlay sur la tuile du provider
     *   - Filtrer "Favoris" dans le dropdown langue providers. */
    var favoriteProviders: Set<String>
        get() = Key.FAVORITE_PROVIDERS.getStringSet() ?: emptySet()
        set(value) {
            Key.FAVORITE_PROVIDERS.setStringSet(value)
        }

    /** Toggle un provider dans la liste des favoris. Renvoie true si ajouté,
     *  false si retiré. */
    fun toggleFavoriteProvider(name: String): Boolean {
        val current = favoriteProviders.toMutableSet()
        val isNowFavorite = if (current.contains(name)) {
            current.remove(name)
            false
        } else {
            current.add(name)
            true
        }
        favoriteProviders = current
        return isNowFavorite
    }

    fun isFavoriteProvider(name: String): Boolean = favoriteProviders.contains(name)

    /** 2026-06-13 (Favorite Providers) : si true, l'écran providers affiche
     *  uniquement les providers favoris (= filtre "Favoris" du dropdown). */
    var providerShowFavoritesOnly: Boolean
        get() = Key.PROVIDER_SHOW_FAVORITES_ONLY.getBoolean() ?: false
        set(value) {
            Key.PROVIDER_SHOW_FAVORITES_ONLY.setBoolean(value)
        }

    /** 2026-06-09 (user "tu coches actives tout tu la décoches désactive tous") :
     *  toggle global "carrousel comme fond d'écran". Si false, updateBackground
     *  et pinBackground ne touchent plus à ivHomeBackground → le fond du
     *  layout reste visible. Default true pour ne rien casser. */
    /** 2026-06-21 (user "réunir toutes ces préférences en une seule option
     *  pour éviter d'avoir trop de trucs partout") :
     *  4 modes d'affichage du home dans UNE seule pref :
     *  - "none"        : pas de carrousel, fond statique (= mode minimal)
     *  - "carousel"    : carrousel visible, fond statique (= juste le slider)
     *  - "carousel_bg" : carrousel + bannière en fond (= mode immersif TV)
     *  - "black"       : pas de carrousel, fond noir uni (= mode sombre)
     *  2026-06-21 v2 (user "mets carrousel uniquement par défaut pour les
     *  gens, après ils changeront s'ils veulent") : default unifié = carousel
     *  pour TV + mobile (= bon compromis : on voit le slider mais pas
     *  d'effet bannière plein écran). */
    var homeBackgroundMode: String
        get() = Key.HOME_BG_MODE.getString() ?: "carousel"
        set(value) {
            Key.HOME_BG_MODE.setString(value)
        }

    /** Helpers dérivés du mode courant — gardent l'API existante du code. */
    val carouselAsBackground: Boolean
        get() = homeBackgroundMode == "carousel_bg"
    val blackBackground: Boolean
        get() = homeBackgroundMode == "black"
    val showCarousel: Boolean
        get() = homeBackgroundMode == "carousel" || homeBackgroundMode == "carousel_bg"

    /** 2026-06-09 (user "rendre transparente la bande noire que tu vois sur la
     *  gauche pour voir le fond d'écran") : opacité 0-100 de la sidebar TV.
     *  100 = opaque (= comportement actuel), 0 = totalement transparente. */
    var sidebarOpacity: Int
        get() = Key.SIDEBAR_OPACITY.getInt() ?: 100
        set(value) {
            Key.SIDEBAR_OPACITY.setInt(value.coerceIn(0, 100))
        }

    /** Écran "Qui regarde ?" au lancement + verrouillage auto Home.
     *  Activé par défaut. Si désactivé, pas de ProfilePicker auto,
     *  l'user change de profil manuellement via les paramètres. */
    var profilePickerEnabled: Boolean
        get() = Key.PROFILE_PICKER_ENABLED.getBoolean() ?: true
        set(value) {
            Key.PROFILE_PICKER_ENABLED.setBoolean(value)
        }

    var playerGestures: Boolean
        get() = Key.PLAYER_GESTURES.getBoolean() ?: true
        set(value) {
            Key.PLAYER_GESTURES.setBoolean(value)
        }

    /** 2026-06-10 (bug ami Google TV Streamer kirkwood/MTK MT8696) : force
     *  le décodeur software FFmpeg en priorité. Par défaut OFF (= HW
     *  prioritaire, comportement standard). À activer si la lecture freeze
     *  (image figée, son OK) ou si certains formats ne se lisent pas. */
    var preferSoftwareDecoder: Boolean
        get() = Key.PREFER_SOFTWARE_DECODER.getBoolean() ?: false
        set(value) {
            Key.PREFER_SOFTWARE_DECODER.setBoolean(value)
        }

    /** 2026-06-10 (user "j'ai ouvert une chaîne de dessin animé sous-titré
     *  mais les sous-titres n'y sont pas") : active les sous-titres FR
     *  embarqués dans les chaînes IPTV (WebVTT HLS / TTML DASH). Par défaut
     *  OFF pour rester compatible avec l'ancien comportement qui désactivait
     *  les CC pour éviter les "HUGO! HUGO!" gênants. */
    var iptvShowSubtitlesFr: Boolean
        get() = Key.IPTV_SHOW_SUBTITLES_FR.getBoolean() ?: false
        set(value) {
            Key.IPTV_SHOW_SUBTITLES_FR.setBoolean(value)
        }

    var immersiveMode: Boolean
        get() = Key.IMMERSIVE_MODE.getBoolean() ?: false // Default changed to false
        set(value) {
            Key.IMMERSIVE_MODE.setBoolean(value)
        }

    var forceExtraBuffering: Boolean
        get() = Key.FORCE_EXTRA_BUFFERING.getBoolean() ?: false
        set(value) {
            Key.FORCE_EXTRA_BUFFERING.setBoolean(value)
        }

    var autoplayBuffer: Long
        get() = Key.AUTOPLAY_BUFFER.getLong() ?: 3L
        set(value) {
            Key.AUTOPLAY_BUFFER.setLong(value)
        }

    var serverAutoSubtitlesDisabled: Boolean
        // 2026-06-09 (user "VIDZY y a plus ces sous-titres embarqués d'origine
        //   comme avant, je vais sur l'autre version ça fonctionne") : default
        //   FALSE = laisse passer le flag `default=true` des subs extracteur
        //   (Vidzy/etc.) → ExoPlayer auto-sélectionne le sous-titre VOSTFR
        //   embarqué d'origine avec la vidéo (comme avant).
        get() = Key.SERVER_AUTO_SUBTITLES_DISABLED.getBoolean() ?: false
        set(value) {
            Key.SERVER_AUTO_SUBTITLES_DISABLED.setBoolean(value)
        }

    // 2026-06-09 (user "Imagine on tombe sur une vidéo française et ça se met
    //   à faire des sous titres") : toggle pour DÉSACTIVER l'overlay de subs
    //   externe (ExternalSubtitleOverlay). True = pas d'overlay même si
    //   l'extracteur fournit une URL VTT. Default false = overlay actif.
    var externalSubsOverlayDisabled: Boolean
        get() = Key.EXTERNAL_SUBS_OVERLAY_DISABLED.getBoolean() ?: false
        set(value) {
            Key.EXTERNAL_SUBS_OVERLAY_DISABLED.setBoolean(value)
        }

    // 2026-05-15 (user "ajoute le sub anglais pour ceux qui veulent dans Open
    // subtitles, censé être activable sur tous les providers") : flag global
    // pour récupérer ÉGALEMENT les subs anglais (en plus du FR par défaut).
    // Default = false pour préserver le comportement actuel.
    var enableEnglishSubtitles: Boolean
        get() = Key.ENABLE_ENGLISH_SUBTITLES.getBoolean() ?: false
        set(value) {
            Key.ENABLE_ENGLISH_SUBTITLES.setBoolean(value)
        }

    var selectedTheme: String
        get() = Key.SELECTED_THEME.getString() ?: "nero_amoled_oled"
        set(value) = Key.SELECTED_THEME.setString(value)

    var tmdbApiKey: String
        get() = Key.TMDB_API_KEY.getString() ?: ""
        set(value) {
            Key.TMDB_API_KEY.setString(value)
            TMDb3.rebuildService()
        }
    var enableTmdb: Boolean
        get() = Key.ENABLE_TMDB.getBoolean() ?: true
        set(value) {
            Key.ENABLE_TMDB.setBoolean(value)
            TMDb3.rebuildService()
            if (value) {
                runCatching {
                    ArtworkRepairScheduler.schedule(StreamFlixApp.instance, currentProvider)
                }
            }
        }

    var parentalControlPin: String
        get() = Key.PARENTAL_CONTROL_PIN.getString() ?: ""
        set(value) {
            Key.PARENTAL_CONTROL_PIN.setString(value.trim())
        }

    var parentalControlAdminPin: String
        get() = Key.PARENTAL_CONTROL_ADMIN_PIN.getString() ?: ""
        set(value) {
            Key.PARENTAL_CONTROL_ADMIN_PIN.setString(value.trim())
        }

    var parentalControlMaxAge: Int?
        get() = Key.PARENTAL_CONTROL_MAX_AGE.getInt()
        set(value) {
            Key.PARENTAL_CONTROL_MAX_AGE.setInt(value)
        }

    var parentalControlFailedAttempts: Int
        get() = Key.PARENTAL_CONTROL_FAILED_ATTEMPTS.getInt() ?: 0
        set(value) {
            Key.PARENTAL_CONTROL_FAILED_ATTEMPTS.setInt(value)
        }

    var parentalControlLockedUntilMillis: Long
        get() = Key.PARENTAL_CONTROL_LOCKED_UNTIL.getLong() ?: 0L
        set(value) {
            Key.PARENTAL_CONTROL_LOCKED_UNTIL.setLong(value)
        }

    var parentalControlHardLocked: Boolean
        get() = Key.PARENTAL_CONTROL_HARD_LOCKED.getBoolean() ?: false
        set(value) {
            Key.PARENTAL_CONTROL_HARD_LOCKED.setBoolean(value)
        }

    val isParentalControlActive: Boolean
        get() = enableTmdb && parentalControlPin.isNotBlank() && parentalControlMaxAge != null

    val isParentalControlTemporarilyLocked: Boolean
        get() = parentalControlLockedUntilMillis > System.currentTimeMillis()

    val parentalControlLockRemainingMillis: Long
        get() = (parentalControlLockedUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)

    fun registerParentalPinSuccess() {
        synchronized(lock) {
            parentalControlFailedAttempts = 0
            parentalControlLockedUntilMillis = 0L
            parentalControlHardLocked = false
        }
    }

    fun registerParentalPinFailure(nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val attempts = parentalControlFailedAttempts + 1
            parentalControlFailedAttempts = attempts

            when {
                attempts >= 7 && parentalControlAdminPin.isNotBlank() -> {
                    parentalControlHardLocked = true
                    parentalControlLockedUntilMillis = 0L
                }
                attempts >= 7 -> {
                    parentalControlLockedUntilMillis = nowMillis + 24L * 60L * 60L * 1000L
                }
                attempts >= 5 -> {
                    parentalControlLockedUntilMillis = nowMillis + 30L * 60L * 1000L
                }
                attempts >= 3 -> {
                    parentalControlLockedUntilMillis = nowMillis + 5L * 60L * 1000L
                }
            }
        }
    }

    var updateCheckEnabled: Boolean
        get() = Key.UPDATE_CHECK_ENABLED.getBoolean() ?: true
        set(value) {
            Key.UPDATE_CHECK_ENABLED.setBoolean(value)
        }

    fun unlockParentalControls() {
        synchronized(lock) {
            parentalControlFailedAttempts = 0
            parentalControlLockedUntilMillis = 0L
            parentalControlHardLocked = false
        }
    }

    var subdlApiKey: String
        get() = Key.SUBDL_API_KEY.getString() ?: ""
        set(value) {
            Key.SUBDL_API_KEY.setString(value)
        }

    var bypassWsAdvertisedHost: String
        get() = Key.BYPASS_WS_ADVERTISED_HOST.getString() ?: ""
        set(value) {
            Key.BYPASS_WS_ADVERTISED_HOST.setString(value.trim())
        }

    var hlsProxyUrl: String
        get() = Key.HLS_PROXY_URL.getString() ?: "https://hls-proxy.nanico61250.workers.dev"
        set(value) {
            Key.HLS_PROXY_URL.setString(value.trim().trimEnd('/'))
        }

    /**
     * Liste de proxys HLS avec fallback automatique.
     * Si le proxy principal atteint la limite de 100k requêtes/jour (HTTP 429),
     * on bascule sur le suivant dans la liste.
     * Le proxy configuré par l'utilisateur est toujours en premier.
     */
    val hlsProxyUrls: List<String>
        get() {
            val userProxy = hlsProxyUrl
            val defaults = listOf(
                "https://hls-proxy.nanico61250.workers.dev",
                "https://hls-proxy-2.moctis.workers.dev"
            )
            return if (userProxy.isNotEmpty() && userProxy !in defaults) {
                listOf(userProxy) + defaults
            } else {
                defaults
            }
        }

    enum class PlayerResize(
        val stringRes: Int,
        val resizeMode: Int,
    ) {
        Fit(R.string.player_aspect_ratio_fit, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        Fill(R.string.player_aspect_ratio_fill, AspectRatioFrameLayout.RESIZE_MODE_FILL),
        Zoom(R.string.player_aspect_ratio_zoom, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
        Stretch43(R.string.player_aspect_ratio_zoom_4_3, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        StretchVertical(R.string.player_aspect_ratio_stretch_vertical, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        SuperZoom(R.string.player_aspect_ratio_super_zoom, AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    var playerResize: PlayerResize
        get() = PlayerResize.entries.find { it.resizeMode == Key.PLAYER_RESIZE.getInt() && it.name == Key.PLAYER_RESIZE_NAME.getString() }
            ?: PlayerResize.entries.find { it.resizeMode == Key.PLAYER_RESIZE.getInt() }
            ?: PlayerResize.Fit
        set(value) {
            Key.PLAYER_RESIZE.setInt(value.resizeMode)
            Key.PLAYER_RESIZE_NAME.setString(value.name)
        }

    var captionStyle: CaptionStyleCompat
        get() = CaptionStyleCompat(
            Key.CAPTION_STYLE_FONT_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.foregroundColor,
            Key.CAPTION_STYLE_BACKGROUND_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.backgroundColor,
            Key.CAPTION_STYLE_WINDOW_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.windowColor,
            Key.CAPTION_STYLE_EDGE_TYPE.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.edgeType,
            Key.CAPTION_STYLE_EDGE_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.edgeColor,
            PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.typeface
        )
        set(value) {
            Key.CAPTION_STYLE_FONT_COLOR.setInt(value.foregroundColor)
            Key.CAPTION_STYLE_BACKGROUND_COLOR.setInt(value.backgroundColor)
            Key.CAPTION_STYLE_WINDOW_COLOR.setInt(value.windowColor)
            Key.CAPTION_STYLE_EDGE_TYPE.setInt(value.edgeType)
            Key.CAPTION_STYLE_EDGE_COLOR.setInt(value.edgeColor)
        }

    var captionMargin: Int
        get() = Key.CAPTION_STYLE_MARGIN.getInt() ?: 24
        set(value) {
            Key.CAPTION_STYLE_MARGIN.setInt(value)
        }

    var qualityHeight: Int?
        get() = Key.QUALITY_HEIGHT.getInt()
        set(value) {
            Key.QUALITY_HEIGHT.setInt(value)
        }

    var subtitleName: String?
        get() = Key.SUBTITLE_NAME.getString()
        set(value) = Key.SUBTITLE_NAME.setString(value)
    var streamingcommunityDomain: String
        get() {
            if (!::prefs.isInitialized) {
                Log.e(TAG, "streamingcommunityDomain GET: prefs is not initialized")
                return DEFAULT_STREAMINGCOMMUNITY_DOMAIN
            }
            val storedValue = prefs.getString(Key.STREAMINGCOMMUNITY_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) {
                DEFAULT_STREAMINGCOMMUNITY_DOMAIN
            } else {
                storedValue
            }
        }
        set(value) {
            synchronized(lock) {
                val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.STREAMINGCOMMUNITY_DOMAIN.name, null) else null
                if (!::prefs.isInitialized) {
                    Log.e(TAG, "streamingcommunityDomain SET: prefs is not initialized")
                    return
                }

                if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                    clearProviderCache("StreamingCommunity")
                }

                prefs.edit().apply {
                    if (value.isNullOrEmpty()) {
                        remove(Key.STREAMINGCOMMUNITY_DOMAIN.name)
                    } else {
                        putString(Key.STREAMINGCOMMUNITY_DOMAIN.name, value)
                    }
                    commit()
                }
            }
        }

    var cuevanaDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_CUEVANA_DOMAIN
            val storedValue = prefs.getString(Key.CUEVANA_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) DEFAULT_CUEVANA_DOMAIN else storedValue
        }
        set(value) {
            synchronized(lock) {
                val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.CUEVANA_DOMAIN.name, null) else null
                if (!::prefs.isInitialized) return

                if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                    clearProviderCache("Cuevana 3")
                }

                prefs.edit().apply {
                    if (value.isNullOrEmpty()) {
                        remove(Key.CUEVANA_DOMAIN.name)
                    } else {
                        putString(Key.CUEVANA_DOMAIN.name, value)
                    }
                    commit()
                }
            }
        }

    var poseidonDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_POSEIDON_DOMAIN
            val storedValue = prefs.getString(Key.POSEIDON_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) DEFAULT_POSEIDON_DOMAIN else storedValue
        }
        set(value) {
            synchronized(lock) {
                val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.POSEIDON_DOMAIN.name, null) else null
                if (!::prefs.isInitialized) return

                if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                    clearProviderCache("Poseidonhd2")
                }

                prefs.edit().apply {
                    if (value.isNullOrEmpty()) {
                        remove(Key.POSEIDON_DOMAIN.name)
                    } else {
                        putString(Key.POSEIDON_DOMAIN.name, value)
                    }
                    commit()
                }
            }
        }

    var dohProviderUrl: String
        get() = Key.DOH_PROVIDER_URL.getString() ?: DEFAULT_DOH_PROVIDER_URL
        set(value) {
            Key.DOH_PROVIDER_URL.setString(value)
            DnsResolver.setDnsUrl(value)
        }

    var paddingX: Int
        get() = Key.SCREEN_PADDING_X.getInt() ?: 0
        set(value) = Key.SCREEN_PADDING_X.setInt(value)

    var paddingY: Int
        get() = Key.SCREEN_PADDING_Y.getInt() ?: 0
        set(value) = Key.SCREEN_PADDING_Y.setInt(value)

    var miniPlayerEnabled: Boolean
        get() = Key.MINI_PLAYER_ENABLED.getBoolean() ?: true
        set(value) {
            Key.MINI_PLAYER_ENABLED.setBoolean(value)
        }

    /**
     * 2026-06-24 (user "un réglage manuel pour descendre la barre et la fixer") :
     * décalage en dp ajouté en haut du RecyclerView home mobile. 0 = automatique
     * (barrier), >0 = marge supplémentaire pour les mobiles non compatibles
     * (Honor 200, MagicOS densité 520 dpi, etc.).
     * Plage : 0–120 dp. Persisté SharedPrefs.
     */
    var homeTopOffset: Int
        get() = Key.HOME_TOP_OFFSET.getInt() ?: 0
        set(value) {
            Key.HOME_TOP_OFFSET.setInt(value)
        }

    // 2026-06-29 RESTAURÉ depuis l'APK v1.7.226 (= système de luminosité TV) :
    //   playerDim : opacité 0-100 d'un View noir overlay au-dessus du PlayerView TV.
    //              0 = pas de dim (image normale), 100 = écran noir total.
    //   carouselDim : pareil pour le carrousel/swiper du home TV (= bannière du haut).
    //   Les SeekBars sont dans settings_tv.xml. Les views overlay sont
    //   view_player_dim (fragment_player_tv.xml) et view_carousel_dim (fragment_home_tv.xml).
    var playerDim: Int
        get() = Key.PLAYER_DIM.getInt() ?: 0
        set(value) {
            Key.PLAYER_DIM.setInt(value.coerceIn(0, 100))
        }

    var carouselDim: Int
        get() = Key.CAROUSEL_DIM.getInt() ?: 0
        set(value) {
            Key.CAROUSEL_DIM.setInt(value.coerceIn(0, 100))
        }

    // 2026-07-09 (user "dans Apparence, un bouton pour réduire la luminosité de
    //   l'application ENTIÈRE, comme le slider Luminosité du carrousel") :
    //   appDim = opacité 0-90 d'un overlay noir posé sur le decorView de CHAQUE
    //   activité (via AppDimManager). 0 = normal, 90 = très sombre. Capé à 90 pour
    //   ne jamais rendre l'écran totalement noir.
    var appDim: Int
        get() = Key.APP_DIM.getInt() ?: 0
        set(value) {
            Key.APP_DIM.setInt(value.coerceIn(0, 90))
        }

    private enum class Key {
        PLAYER_DIM,
        CAROUSEL_DIM,
        APP_DIM,
        APP_LAYOUT,
        CURRENT_LANGUAGE,
        CURRENT_PROVIDER,
        PLAYER_RESIZE,
        PLAYER_RESIZE_NAME,
        CAPTION_TEXT_SIZE,
        CAPTION_STYLE_FONT_COLOR,
        CAPTION_STYLE_BACKGROUND_COLOR,
        CAPTION_STYLE_WINDOW_COLOR,
        CAPTION_STYLE_EDGE_TYPE,
        CAPTION_STYLE_EDGE_COLOR,
        CAPTION_STYLE_MARGIN,
        SCREEN_PADDING_X,
        SCREEN_PADDING_Y,
        QUALITY_HEIGHT,
        SUBTITLE_NAME,
        STREAMINGCOMMUNITY_DOMAIN,
        CUEVANA_DOMAIN,
        POSEIDON_DOMAIN,
        DOH_PROVIDER_URL, // Removed STREAMINGCOMMUNITY_DNS_OVER_HTTPS, added DOH_PROVIDER_URL
        AUTOPLAY,
        PROVIDER_CACHE,
        KEEP_SCREEN_ON_WHEN_PAUSED,
        PIP_ON_EXIT,
        SHOW_NEXT_EPISODE_OVERLAY,
        ANIME_AUTO_SKIP_BROKEN,
        FAVORITE_PROVIDERS,
        PROVIDER_SHOW_FAVORITES_ONLY,
        CAROUSEL_AS_BACKGROUND,
        BLACK_BACKGROUND,
        SHOW_CAROUSEL,
        HOME_BG_MODE,
        SIDEBAR_OPACITY,
        KEEP_SCREEN_ON_APP,
        PLAYER_GESTURES,
        PREFER_SOFTWARE_DECODER,
        IPTV_SHOW_SUBTITLES_FR,
        IMMERSIVE_MODE,
        TMDB_API_KEY,
        SUBDL_API_KEY,
        FORCE_EXTRA_BUFFERING,
        AUTOPLAY_BUFFER,
        SERVER_AUTO_SUBTITLES_DISABLED,
        EXTERNAL_SUBS_OVERLAY_DISABLED,
        ENABLE_ENGLISH_SUBTITLES,
        ENABLE_TMDB,
        PARENTAL_CONTROL_PIN,
        PARENTAL_CONTROL_ADMIN_PIN,
        PARENTAL_CONTROL_MAX_AGE,
        PARENTAL_CONTROL_FAILED_ATTEMPTS,
        PARENTAL_CONTROL_LOCKED_UNTIL,
        PARENTAL_CONTROL_HARD_LOCKED,
        SELECTED_THEME,
        BYPASS_WS_ADVERTISED_HOST,
        UPDATE_CHECK_ENABLED,
        PROVIDER_LANGUAGE,
        PROVIDER_TAB_INDEX,
        HLS_PROXY_URL,
        MINI_PLAYER_ENABLED,
        FAILED_CHANNELS,
        PROFILE_PICKER_ENABLED,
        HOME_TOP_OFFSET;

        fun getBoolean(): Boolean? = when {
            prefs.contains(name) -> prefs.getBoolean(name, false)
            else -> null
        }

        fun getFloat(): Float? = when {
            prefs.contains(name) -> prefs.getFloat(name, 0F)
            else -> null
        }

        fun getInt(): Int? = when {
            prefs.contains(name) -> try {
                prefs.getInt(name, 0)
            } catch (_: ClassCastException) {
                // 2026-06-09 : ListPreference stocke en String. Si la valeur
                //   est une String, on essaye de la parser en Int.
                try { prefs.getString(name, null)?.toIntOrNull() } catch (_: Throwable) { null }
            }
            else -> null
        }

        fun getLong(): Long? = when {
            prefs.contains(name) -> prefs.getLong(name, 0)
            else -> null
        }

        fun getString(): String? = when {
            prefs.contains(name) -> prefs.getString(name, null)
            else -> null
        }

        fun setBoolean(value: Boolean?) = value?.let {
            with(prefs.edit()) {
                putBoolean(name, value)
                apply()
            }
        } ?: remove()

        fun setFloat(value: Float?) = value?.let {
            with(prefs.edit()) {
                putFloat(name, value)
                apply()
            }
        } ?: remove()

        fun setInt(value: Int?) = value?.let {
            with(prefs.edit()) {
                putInt(name, value)
                apply()
            }
        } ?: remove()

        fun setLong(value: Long?) = value?.let {
            with(prefs.edit()) {
                putLong(name, value)
                apply()
            }
        } ?: remove()

        fun setString(value: String?) = value?.let {
            with(prefs.edit()) {
                putString(name, value)
                apply()
            }
        } ?: remove()

        /** 2026-06-13 (Favorite Providers) : Set<String> persistance. */
        fun getStringSet(): Set<String>? = when {
            prefs.contains(name) -> prefs.getStringSet(name, emptySet())
            else -> null
        }

        fun setStringSet(value: Set<String>?) = value?.let {
            with(prefs.edit()) {
                putStringSet(name, value)
                apply()
            }
        } ?: remove()

        fun remove() = with(prefs.edit()) {
            remove(name)
            apply()
        }
    }
}
