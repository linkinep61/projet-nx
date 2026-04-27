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
import com.streamflixreborn.streamflix.utils.IsrgRootTrustProvider
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
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

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

        // 0. Initialize Conscrypt for modern SSL on old Android
        Security.insertProviderAt(Conscrypt.newProvider(), 1)

        // 1. Install ISRG Root X1 globally for Let's Encrypt. On Android < 7 (API 24)
        // network_security_config.xml is not supported so the certificate must be injected manually.
        IsrgRootTrustProvider.install()

        // 2. Inizializzazione preferenze (con applicationContext)
        UserPreferences.setup(this)
        DnsResolver.setDnsUrl(UserPreferences.dohProviderUrl)

        val appContext = applicationContext
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val threshold = if (isTv) 10L else 50L

        applicationScope.launch(Dispatchers.IO) {
            // Pre-init Cronet in background — saves 300-800ms when player opens
            try { getCronetEngine(appContext) } catch (_: Exception) {}

            AppDatabase.setup(appContext)
            // Skip ArtworkRepair on TV — too expensive for Chromecast's limited resources
            if (!isTv) {
                ArtworkRepairScheduler.schedule(appContext, UserPreferences.currentProvider)
            }
            CacheUtils.autoClearIfNeeded(appContext, thresholdMb = threshold)

            // Refresh URLs only for the ACTIVE provider at startup.
            // Other providers refresh lazily when the user switches to them.
            refreshActiveProviderUrl()
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
        val current = UserPreferences.currentProvider
        if (current == null || current !is ProviderConfigUrl) {
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
            (current as ProviderConfigUrl).onChangeUrl()
            Log.d("StreamFlixApp", "  ${current.name}: URL refreshed → ${current.baseUrl}")
        } catch (e: Exception) {
            Log.w("StreamFlixApp", "  ${current.name}: URL refresh failed: ${e.message}")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            CacheUtils.clearAppCache(this)
        }
    }
}

