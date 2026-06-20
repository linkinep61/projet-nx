package com.streamflixreborn.streamflix

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import java.security.Security
import org.conscrypt.Conscrypt
import android.util.Log
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.ProviderConfigUrl
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ArtworkRepairScheduler
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.IptvTlsHelper
import com.streamflixreborn.streamflix.utils.IsrgRootTrustProvider
import com.streamflixreborn.streamflix.utils.ProfileStore
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StreamFlixApp : Application() {
    companion object {
        lateinit var instance: StreamFlixApp
            private set

        @Volatile
        var currentActivity: Activity? = null
            private set

        /** Compteur d'activités visibles — quand il tombe à 0, l'app
         *  est en background (Home TV, switch app, etc.). */
        @Volatile
        private var visibleActivityCount = 0

        /** True quand l'app revient du background → les Main activities
         *  doivent vérifier si le ProfilePicker doit être affiché. */
        @Volatile
        var shouldLockOnResume = false

        /**
         * Pre-initialized Cronet engine (lazy, thread-safe).
         * CronetEngine.Builder().build() takes 300-800ms on ARM/Chromecast.
         * By initializing once at app level, the player avoids this cost.
         *
         * Stored as Any? to avoid forcing the classloader to resolve
         * org.chromium.net.CronetEngine when StreamFlixApp is loaded.
         * If Cronet isn't on the device, the app still starts normally.
         */
        @Volatile
        private var _cronetEngine: Any? = null

        fun getCronetEngine(context: Context): Any? {
            return _cronetEngine ?: synchronized(this) {
                _cronetEngine ?: try {
                    val clz = Class.forName("org.chromium.net.CronetEngine\$Builder")
                    val ctor = clz.getConstructor(Context::class.java)
                    val builder = ctor.newInstance(context.applicationContext)
                    val buildMethod = clz.getMethod("build")
                    buildMethod.invoke(builder).also {
                        _cronetEngine = it
                        Log.d("StreamFlixApp", "CronetEngine initialized")
                    }
                } catch (e: Exception) {
                    Log.w("StreamFlixApp", "CronetEngine init failed (Cronet may not be available): ${e.message}")
                    null
                }
            }
        }

        // 2026-05-17 : DVR live cache. SimpleCache 100 Mo persistant sur disque.
        //   Stocke les segments HLS Live qui passent. Bénéfices :
        //   - Channel-switch rapide (segments cachés)
        //   - Manifest persistance across sessions
        //   - Protection contre les fetch transitoires échoués
        //   Pour Chromecast (peu de stockage), 100 Mo = ~5-10 min de live HD.
        @Volatile
        private var _liveCache: androidx.media3.datasource.cache.SimpleCache? = null

        fun getLiveCache(context: Context): androidx.media3.datasource.cache.SimpleCache? {
            return _liveCache ?: synchronized(this) {
                _liveCache ?: try {
                    val cacheDir = java.io.File(context.cacheDir, "live_dvr_cache")
                    cacheDir.mkdirs()
                    // 2026-05-20 (optim mémoire/stockage) : 100 Mo c'est trop pour la
                    //   Chromecast (384 Mo RAM + peu de stockage). 40 Mo sur TV =
                    //   toujours ~3-5 min de live HD, largement assez pour le DVR.
                    val isTv = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
                    val cacheBytes = if (isTv) 40L * 1024 * 1024 else 100L * 1024 * 1024
                    val evictor = androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(cacheBytes)
                    val databaseProvider = androidx.media3.database.StandaloneDatabaseProvider(context.applicationContext)
                    androidx.media3.datasource.cache.SimpleCache(cacheDir, evictor, databaseProvider).also {
                        _liveCache = it
                        Log.d("StreamFlixApp", "Live DVR SimpleCache initialized (${cacheBytes / 1024 / 1024} MB at $cacheDir, isTv=$isTv)")
                    }
                } catch (e: Exception) {
                    Log.w("StreamFlixApp", "Live cache init failed: ${e.message}")
                    null
                }
            }
        }

        // 2026-05-17 (user "le cache est vidé à chaque changement de chaîne, il
        //   y a intérêt") : clear le cache disque. Appelé à chaque channel-switch
        //   et au retour Home pour garantir que les 100 Mo sont dédiés au flux
        //   courant uniquement (pas pollués par segments d'anciennes chaînes).
        fun clearLiveCache() {
            synchronized(this) {
                try {
                    _liveCache?.release()
                    _liveCache = null
                    val cacheDir = java.io.File(instance.cacheDir, "live_dvr_cache")
                    cacheDir.deleteRecursively()
                    Log.d("StreamFlixApp", "Live DVR cache cleared (channel switch or home return)")
                } catch (e: Exception) {
                    Log.w("StreamFlixApp", "Live cache clear failed: ${e.message}")
                }
            }
        }
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 2026-06-09 (user "VIDZY y a plus ces sous-titres embarqués d'origine
        //   comme avant") : MIGRATION ONE-SHOT. Si la pref SERVER_AUTO_
        //   SUBTITLES_DISABLED a été persistée à `true` (default historique),
        //   on la reset à `false` une fois pour redonner accès aux subs
        //   embarqués VOSTFR auto-sélectionnés des extracteurs (Vidzy etc.).
        //   Un sentinel `subs_default_migration_v2` empêche de re-tripper si
        //   l'user re-toggle ensuite via le picker player.
        try {
            val sp = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
            if (!sp.getBoolean("subs_default_migration_v2", false)) {
                sp.edit()
                    .putBoolean("SERVER_AUTO_SUBTITLES_DISABLED", false)
                    .putBoolean("subs_default_migration_v2", true)
                    .apply()
            }
        } catch (_: Throwable) {}

        // 2026-06-03 : eager-load de l'index IPTV (seed Firebase + cache user).
        //   Avant : chargé uniquement quand l'user ouvrait OLA TV (lazy).
        //   Maintenant : chargé au démarrage de l'app, sur un thread BG pour
        //   ne pas bloquer le main. Comme ça quand OLA TV s'ouvre, les
        //   fast-tracks sont déjà en mémoire → chaînes démarrent direct.
        Thread {
            try {
                com.streamflixreborn.streamflix.utils.LocalIptvChannelIndex.loadLocalCache()
            } catch (_: Throwable) {}
        }.start()

        // 2026-05-18 : UncaughtExceptionHandler — sauvegarde le stack trace
        //   dans last_crash.txt (lu par CrashActivity + buildBugReport).
        //   Sans ça, le fichier n'était jamais écrit, donc le rapport bug
        //   ne pouvait pas inclure la cause d'un crash récent.
        try {
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val sw = java.io.StringWriter()
                    sw.append("Crash at ${java.util.Date()}\n")
                    sw.append("Thread: ${thread.name}\n")
                    sw.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})\n")
                    sw.append("App: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n\n")
                    throwable.printStackTrace(java.io.PrintWriter(sw))
                    val crashFile = java.io.File(getExternalFilesDir(null), "last_crash.txt")
                    crashFile.writeText(sw.toString())
                } catch (_: Throwable) {
                    try {
                        val sw = java.io.StringWriter()
                        throwable.printStackTrace(java.io.PrintWriter(sw))
                        java.io.File(cacheDir, "last_crash.txt").writeText(sw.toString())
                    } catch (_: Throwable) {}
                }
                previousHandler?.uncaughtException(thread, throwable)
            }
        } catch (_: Throwable) {}

        // 2026-05-17 (user "ça peut faire cracher l'application au démarrage
        //   sinon") : clear le cache DVR au démarrage de l'app. Évite d'hériter
        //   d'un cache stale d'une session précédente qui pourrait avoir crashé
        //   et laissé le cache dans un état inconsistant (entries DB orphelines,
        //   fichiers tronqués, etc.).
        try {
            val cacheDir = java.io.File(cacheDir, "live_dvr_cache")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                Log.d("StreamFlixApp", "Live DVR cache cleared at app startup")
            }
        } catch (e: Exception) {
            Log.w("StreamFlixApp", "Startup cache clear failed: ${e.message}")
        }

        // Track current foreground Activity for WebView dialogs
        // + compteur d'activités visibles pour détecter le background (Home TV)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { currentActivity = activity }
            override fun onActivityPaused(activity: Activity) { if (currentActivity == activity) currentActivity = null }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                visibleActivityCount++
            }
            override fun onActivityStopped(activity: Activity) {
                visibleActivityCount--
                if (visibleActivityCount <= 0) {
                    visibleActivityCount = 0
                    // L'app entière est en background (Home TV, switch app…)
                    // → marquer pour verrouillage au retour
                    shouldLockOnResume = true
                    Log.d("StreamFlixApp", "App went to background — lock on resume")
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) { if (currentActivity == activity) currentActivity = null }
        })

        // 0. Initialize Conscrypt for modern SSL on old Android.
        // 2026-05-12 : wrap try-catch — sur certains firmwares custom (Sharp Aquos
        // TVE19A Android 11 confirmé), Conscrypt.newProvider() peut throw et
        // crasher l'app au démarrage. Si Conscrypt échoue, on tombe sur le SSL
        // système (suffisant Android 11+).
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (e: Throwable) {
            Log.e("StreamFlixApp", "Conscrypt init failed (using system SSL): ${e.message}")
        }

        // 1. Install ISRG Root X1 globally for Let's Encrypt. On Android < 7 (API 24)
        // network_security_config.xml is not supported so the certificate must be injected manually.
        try {
            IsrgRootTrustProvider.install()
        } catch (e: Throwable) {
            Log.e("StreamFlixApp", "IsrgRootTrustProvider failed: ${e.message}")
        }

        // 1b. trust-all SSLSocketFactory pour les hosts IPTV.
        try {
            IptvTlsHelper.install()
        } catch (e: Throwable) {
            Log.e("StreamFlixApp", "IptvTlsHelper failed: ${e.message}")
        }

        // 2. Inizializzazione preferenze.
        try {
            UserPreferences.setup(this)
            DnsResolver.setDnsUrl(UserPreferences.dohProviderUrl)
        } catch (e: Throwable) {
            Log.e("StreamFlixApp", "UserPreferences/DNS setup failed: ${e.message}", e)
        }

        // 2026-06-17 v3 (user reports Fred/Fantomial/Bob : "panneau vide après
        //   redémarrage TV ou ferme app, désactiver puis réactiver tunnel répare")
        //   = state stale tunnel + ExoPlayer ne se warm-up pas. Au cold start,
        //   on FORCE un STOP avant le START — reproduit le workaround manuel
        //   Fantomial automatiquement et de façon invisible pour l'user.
        // 2026-06-17 v6 (Fred bug : "désactiver/réactiver tunnel ne marche pas,
        //   seul pm clear marche") = cachedClient OkHttp garde un connection
        //   pool avec sockets zombies vers le vieux tunnel. Fix v3 invalidait
        //   AVANT start (cassait Fred). Fix v6 : invalidate APRÈS start success
        //   = vieux client utilisé tant que start pas confirmé, puis remplacé
        //   par fresh client avec nouveau proxy.
        try {
            // 2026-06-18 : `vavooUseTunnel` retourne true pour VYPN ET pour
            //   "VPN 2" (= TUNNEL_MODE_PLANETVPN, désormais = "VYPN serveur
            //   alternatif"). On passe skipBestN au démarrage pour cibler
            //   le bon serveur du pool VYPN.
            if (UserPreferences.vavooUseTunnel) {
                val skip = UserPreferences.vavooTunnelSkipBestN()
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    try {
                        try {
                            com.streamflixreborn.streamflix.utils.VavooTunnel.stop()
                        } catch (_: Throwable) {}
                        kotlinx.coroutines.delay(500)
                        val ok = com.streamflixreborn.streamflix.utils.VavooTunnel.start(skipBestN = skip)
                        Log.d("StreamFlixApp", "Vavoo tunnel cold-start (skip=$skip): $ok")
                        if (ok) {
                            try {
                                com.streamflixreborn.streamflix.providers.VavooProvider.invalidateClientCache()
                                Log.d("StreamFlixApp", "Vavoo client cache invalidated (post-start)")
                            } catch (_: Throwable) {}
                        }
                    } catch (e: Throwable) {
                        Log.e("StreamFlixApp", "VavooTunnel.start: ${e.message}", e)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("StreamFlixApp", "Tunnel auto-start init failed: ${e.message}", e)
        }

        // 2026-06-18 (user "ça met du temps à charger Le replay") : pré-charge
        //   le M3U replay en background dès le cold start. Cache disque +
        //   RAM → ouverture du TV Hub instantanée à partir du 2e lancement.
        try {
            com.streamflixreborn.streamflix.providers.LiveTvHubProvider
                .installReplayDiskCache(cacheDir)
            com.streamflixreborn.streamflix.providers.LiveTvHubProvider
                .installAppContext(applicationContext)
            // 2026-06-18 : TF1Resolver lit le token TF1 via TF1Auth → Context.
            com.streamflixreborn.streamflix.utils.TF1Resolver
                .installContext(applicationContext)
            // 2026-06-19 : M6Resolver lit le token M6 via M6Auth → Context.
            com.streamflixreborn.streamflix.utils.M6Resolver
                .installContext(applicationContext)
            // 2026-06-19 v38 (user "creuse pourquoi OTF") : OtfTvService a
            //   besoin du Context pour Settings.Secure.ANDROID_ID (= le vrai
            //   DeviceID OTF, décompilé depuis l'APK officielle V3.2).
            com.streamflixreborn.streamflix.utils.OtfTvService
                .installContext(applicationContext)
            // 2026-06-19 (user "World Live crash au démarrage sur certaines box,
            //   pareil pour le TV hub Faut qu'on fasse en sorte qu'ils soient
            //   moins gourmands") : warm UNIQUEMENT si TV Hub est le provider
            //   actif au boot. Sur petites box (RAM/CPU limités), fetch+parse
            //   452 KB de m3u replay au boot alors que l'user est sur Cloudstream
            //   = gaspillage RAM/CPU qui peut faire crash le process.
            //   Si TV Hub actif → warm en background (chauffe le cache pour
            //   ouverture instantanée). Sinon → on attend que l'user navigue.
            val warmTvHub = try {
                com.streamflixreborn.streamflix.utils.UserPreferences
                    .currentProvider is com.streamflixreborn.streamflix.providers.LiveTvHubProvider
            } catch (_: Throwable) { false }
            if (warmTvHub) {
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    try {
                        com.streamflixreborn.streamflix.providers.LiveTvHubProvider
                            .warmReplayCache()
                        Log.d("StreamFlixApp", "Replay cache warmed at cold start (TV Hub active)")
                    } catch (e: Throwable) {
                        Log.w("StreamFlixApp", "Replay warm failed: ${e.message}")
                    }
                }
            } else {
                Log.d("StreamFlixApp", "Replay cache warm SKIPPED (TV Hub not active provider)")
            }
        } catch (e: Throwable) {
            Log.w("StreamFlixApp", "Replay cache init exception: ${e.message}")
        }

        // 2026-05-31 : Google Cast — init CastContext pour la découverte Chromecast
        try {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(
                this, java.util.concurrent.Executors.newSingleThreadExecutor()
            ).addOnSuccessListener {
                Log.d("StreamFlixApp", "CastContext initialized OK")
            }.addOnFailureListener {
                Log.w("StreamFlixApp", "CastContext init failed: ${it.message}")
            }
        } catch (e: Throwable) {
            Log.w("StreamFlixApp", "CastContext init exception: ${e.message}")
        }

        // 2026-05-12 : setup ProfileStore (multi-utilisateur Netflix-style).
        // Bootstrap crée le profil "Principal" à la 1re ouverture après update.
        // AppDatabase.setup (étape suivante) renomme les anciens fichiers DB
        // pour les associer au profil par défaut → aucune perte de données.
        try {
            ProfileStore.setup(this)
            ProfileStore.bootstrapIfNeeded()
        } catch (e: Throwable) {
            Log.e("StreamFlixApp", "ProfileStore setup failed: ${e.message}", e)
        }

        // 2026-05-12 : setup IptvSourceStore (MyIptvProvider — sources IPTV
        // configurables par l'utilisateur).
        try {
            com.streamflixreborn.streamflix.utils.IptvSourceStore.setup(this)
        } catch (e: Throwable) {
            Log.e("StreamFlixApp", "IptvSourceStore setup failed: ${e.message}", e)
        }

        // 2.5 Cold start = no active provider + no active profile (Netflix-style).
        // 2026-05-17 : sur Chromecast (low RAM), Android tue/recrée souvent le
        // process pendant que l'app est en background. Sans précaution, chaque
        // recréation effacerait le profil et bouncerait l'user vers le picker.
        // Solution : check timestamp de dernière activité. Si < 30 min, c'est
        // une recréation de process → on PRÉSERVE le profil. Sinon, vrai cold
        // start → on clear comme avant.
        try {
            val recentSession = ProfileStore.isRecentlyActive()
            val pickerEnabled = UserPreferences.profilePickerEnabled
            if (recentSession) {
                // 2026-06-19 (user "mon home est bloqué une fois sur 2 ; après
                //   le crash ça remarche bien") : SUR CHROMECAST low-RAM,
                //   l'OS freeze + recrée souvent le process. À chaque retour
                //   on perdait `currentProvider` (clear systématique quand
                //   pickerEnabled=false), donc getHome ne se lançait jamais,
                //   le bouton Refresh n'apparaissait pas, spinner infini.
                //   Fix : si la session est récente (< 30 min), PRÉSERVER
                //   le provider quel que soit pickerEnabled.
                Log.d("StreamFlixApp", "Process recreated mid-session: profile + provider preserved")
            } else if (pickerEnabled) {
                UserPreferences.currentProvider = null
                ProfileStore.setCurrentProfileId(null)
                Log.d("StreamFlixApp", "Cold start: profile + provider cleared (Netflix-style)")
            } else {
                // ProfilePicker désactivé + pas de session récente → on garde
                //   le profil principal, mais on clear le provider pour
                //   atterrir sur le Home Fournisseur.
                UserPreferences.currentProvider = null
                Log.d("StreamFlixApp", "Cold start (picker disabled): provider cleared, profile kept")
            }
        } catch (e: Throwable) {
            Log.e("StreamFlixApp", "currentProvider/profile reset failed: ${e.message}")
        }

        // 3. Download manager
        try {
            com.streamflixreborn.streamflix.download.DownloadManager.init(this)
        } catch (e: Throwable) {
            Log.e("StreamFlixApp", "DownloadManager init failed: ${e.message}")
        }

        val appContext = applicationContext
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val threshold = if (isTv) 10L else 50L

        applicationScope.launch(Dispatchers.IO) {
            // Pre-init Cronet in background — saves 300-800ms when player opens
            try { getCronetEngine(appContext) } catch (_: Exception) {}

            try { AppDatabase.setup(appContext) } catch (_: Throwable) {}
            // Skip ArtworkRepair on TV — too expensive for Chromecast's limited resources
            if (!isTv) {
                try { ArtworkRepairScheduler.schedule(appContext, UserPreferences.currentProvider) } catch (_: Throwable) {}
            }
            try { CacheUtils.autoClearIfNeeded(appContext, thresholdMb = threshold) } catch (_: Throwable) {}

            // Refresh URLs only for the ACTIVE provider at startup.
            try { refreshActiveProviderUrl() } catch (_: Throwable) {}
        }

        // Pre-warm ExoPlayer class loading + codec enumeration on a background thread.
        // On Chromecast, the first ExoPlayer.Builder.build() triggers MediaCodecList
        // enumeration which takes 5-10 seconds. Pre-warming caches these results
        // so the player fragment's build() is near-instant (~100ms).
        applicationScope.launch(Dispatchers.Default) {
            try {
                val start = android.os.SystemClock.elapsedRealtime()
                // Force MediaCodecList enumeration (cached per-process)
                android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                Log.d("StreamFlixApp", "Codec enumeration pre-warmed in ${elapsed}ms")
            } catch (e: Exception) {
                Log.w("StreamFlixApp", "Codec warmup failed: ${e.message}")
            }
        }

    }

    /**
     * Refresh URL only for the currently active provider at startup.
     * Other providers refresh lazily when the user switches to them.
     * This saves 10+ seconds of sequential HTTP requests on startup.
     */
    private suspend fun refreshActiveProviderUrl() {
        refreshProviderUrl(UserPreferences.currentProvider)
    }

    /**
     * Public entry point so the providers screen can trigger a URL refresh
     * for the provider the user just picked. Cold start clears
     * currentProvider in onCreate, so refreshActiveProviderUrl() at startup
     * never has anyone to refresh — every provider has to refresh on click.
     */
    /**
     * Job public pour que le HomeViewModel puisse attendre la fin du refresh
     * avant de lancer getHome(). Sans ça, getHome() part sur une vieille URL
     * → timeout → écran vide (user doit re-cliquer).
     */
    @Volatile
    var providerUrlRefreshJob: kotlinx.coroutines.Job? = null
        private set

    fun refreshProviderUrlAsync(provider: com.streamflixreborn.streamflix.providers.Provider?) {
        if (provider == null || provider !is com.streamflixreborn.streamflix.providers.ProviderConfigUrl) return
        providerUrlRefreshJob = applicationScope.launch(Dispatchers.IO) {
            refreshProviderUrl(provider)
        }
    }

    private suspend fun refreshProviderUrl(provider: com.streamflixreborn.streamflix.providers.Provider?) {
        val current = provider
        if (current == null || current !is com.streamflixreborn.streamflix.providers.ProviderConfigUrl) {
            Log.d("StreamFlixApp", "Active provider doesn't need URL refresh")
            return
        }
        try {
            val autoUpdate = UserPreferences.getProviderCache(
                current,
                UserPreferences.PROVIDER_AUTOUPDATE
            )
            if (autoUpdate == "false") {
                Log.d("StreamFlixApp", "  ${current.name}: auto-update disabled, skipping")
                return
            }
            (current as com.streamflixreborn.streamflix.providers.ProviderConfigUrl).onChangeUrl()
            Log.d("StreamFlixApp", "  ${current.name}: URL refreshed → ${current.baseUrl}")
        } catch (e: Exception) {
            Log.w("StreamFlixApp", "  ${current.name}: URL refresh failed: ${e.message}")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("StreamFlixApp", "onTrimMemory(level=$level)")
        // 2026-05-18 : libérer Glide cache mémoire agressivement quand le
        //   système signale memory pressure. Sur Chromecast (384MB heap) c'est
        //   souvent déclenché — sans ça l'app OOMait après plusieurs sessions.
        try {
            when {
                level >= TRIM_MEMORY_RUNNING_CRITICAL -> {
                    // Critique → drop tout le cache mémoire
                    com.bumptech.glide.Glide.get(this).clearMemory()
                }
                level >= TRIM_MEMORY_RUNNING_LOW -> {
                    // Faible → trim partiel
                    com.bumptech.glide.Glide.get(this).trimMemory(level)
                }
                level == TRIM_MEMORY_UI_HIDDEN -> {
                    // App en background → drop cache mémoire (les images restent
                    // sur disque, rechargées si on revient)
                    com.bumptech.glide.Glide.get(this).clearMemory()
                }
            }
        } catch (e: Throwable) {
            Log.w("StreamFlixApp", "Glide trim failed: ${e.message}")
        }
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            CacheUtils.clearAppCache(this)
        }
    }
}

