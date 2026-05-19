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
                    val evictor = androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(100L * 1024 * 1024) // 100 MB
                    val databaseProvider = androidx.media3.database.StandaloneDatabaseProvider(context.applicationContext)
                    androidx.media3.datasource.cache.SimpleCache(cacheDir, evictor, databaseProvider).also {
                        _liveCache = it
                        Log.d("StreamFlixApp", "Live DVR SimpleCache initialized (100 MB at $cacheDir)")
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
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { currentActivity = activity }
            override fun onActivityPaused(activity: Activity) { if (currentActivity == activity) currentActivity = null }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
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
            if (!recentSession) {
                UserPreferences.currentProvider = null
                ProfileStore.setCurrentProfileId(null)
                Log.d("StreamFlixApp", "Cold start: profile + provider cleared (Netflix-style)")
            } else {
                Log.d("StreamFlixApp", "Process recreated mid-session: profile preserved")
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
    fun refreshProviderUrlAsync(provider: com.streamflixreborn.streamflix.providers.Provider?) {
        if (provider == null || provider !is com.streamflixreborn.streamflix.providers.ProviderConfigUrl) return
        applicationScope.launch(Dispatchers.IO) {
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

