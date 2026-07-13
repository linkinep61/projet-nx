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
         * 2026-07-12 : Security init (Conscrypt + IsrgRootTrust + IptvTlsHelper +
         *   mergedKeyStore) déportée sur Dispatchers.IO pour libérer le main thread
         *   au boot (~3.5s sur Chromecast ARM). NetworkClient.buildClient() await
         *   ce deferred avant la 1re connexion TLS → zéro race condition.
         */
        val securityReady = kotlinx.coroutines.CompletableDeferred<Unit>()

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

        // 2026-07-07 v2 : WIPE INCONDITIONNEL de app_webview/ à CHAQUE cold start.
        //   Avant : le wipe ne se faisait que quand le flag WEBVIEW_DEEP_WIPE_PENDING
        //   était armé par nuclearCachePurge. MAIS la purge ne se déclenche QUE si le
        //   flux serveur timeout (45s) → or les threads OkHttp bloqués dans execute()
        //   empêchaient le timeout de JAMAIS se déclencher → le flag n'était JAMAIS armé
        //   → le wipe n'avait JAMAIS lieu → blocage permanent, seul clear-data débloquait.
        //   Solution : wiper à CHAQUE boot, AVANT toute création de WebView. Le warmUp
        //   WebJS (CF pré-chauffe) tourne APRÈS onCreate → il écrit dans un app_webview/
        //   propre. Pas de perte de warm-up (il re-roulera), gain = zéro état CF corrompu.
        //   Coût : ~0ms (juste un deleteRecursively sur un dossier souvent petit).
        try {
            // Consommer le flag si armé (compat avec l'ancien chemin)
            val spWipe = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            if (spWipe.getBoolean(
                    com.streamflixreborn.streamflix.utils.ProviderCacheRefresh.WEBVIEW_DEEP_WIPE_PENDING,
                    false
                )
            ) {
                spWipe.edit().putBoolean(
                    com.streamflixreborn.streamflix.utils.ProviderCacheRefresh.WEBVIEW_DEEP_WIPE_PENDING,
                    false
                ).apply()
            }
            // Wipe INCONDITIONNEL
            val wvDir = java.io.File(applicationInfo.dataDir, "app_webview")
            val okWipe = runCatching { if (wvDir.exists()) wvDir.deleteRecursively() else true }
                .getOrDefault(false)
            runCatching { java.io.File(cacheDir, "WebView").deleteRecursively() }
            // Éviction des connection pools au boot (connexions idle mortes du process
            //   précédent, si le process a été keep-alive au lieu de killed par le système)
            runCatching { com.streamflixreborn.streamflix.utils.NetworkClient.sharedConnectionPool.evictAll() }
        } catch (_: Throwable) {}

        // 2026-07-11 (user « désactiver ANR pour éviter d'envoyer des rapports
        //   pour rien ») : watchdog ANR complètement coupé. Le rapport fatal
        //   (CrashActivity/GitHub) était déjà disabled depuis le 07/07, mais le
        //   thread daemon continuait de tourner (log + fichier anr_stacks.txt).
        //   Seuls les vrais crashs (UncaughtExceptionHandler) déclenchent un rapport.
        // com.streamflixreborn.streamflix.utils.AnrWatchdog.start()

        // 2026-07-07 (user « autant télécharger tous les CI au démarrage, ça évite que ça cherche
        //   pour rien ») : précharge la liste OLA des cids FR VALIDÉS (nx-data live-cids.json) en
        //   fond. 1 fetch léger vers raw.githubusercontent, isolé (pas de WebView, pas de handshake
        //   portail, n'impacte aucun autre provider). À l'ouverture d'OLA, la liste est déjà en
        //   mémoire → Phase 3 ingère direct les cids validés au lieu de scanner à l'aveugle.
        // 2026-07-07 FIX CRASH BOOT : NE PAS toucher OlaTvProvider sur le main thread au boot —
        //   son <clinit> construit un OkHttpClient avec .dns(DnsResolver.doh), pas prêt si tôt →
        //   ExceptionInInitializerError = crash au lancement. On déporte sur IO, APRÈS un délai
        //   (DnsResolver stabilisé), sous try/catch (un throw d'init est rattrapé, jamais fatal).
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                kotlinx.coroutines.delay(8_000L)
                com.streamflixreborn.streamflix.providers.OlaTvProvider.prefetchLiveCidsAtBoot()
            } catch (_: Throwable) {}
        }

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

        // 2026-07-05 (user "rien d'IPTV ne doit se charger au boot de la Chromecast, seulement à
        //   l'ouverture du provider") : l'eager-load de l'index IPTV au démarrage est RETIRÉ. Il
        //   est REDONDANT — OlaTvProvider appelle déjà LocalIptvChannelIndex.loadLocalCache() à son
        //   ouverture (le chargement est idempotent + rapide ~5ms). Donc l'index se charge quand on
        //   ouvre OLA TV, pas au boot → moins de travail au démarrage de l'app.

        // 2026-07-03 : pre-warm RÉTABLI pour les WebJsProviders (CF challenge
        //   au boot = jaquettes + home instantanés). Séquentiel (1 par 1),
        //   background (Main thread coroutine), skip low-RAM (Chromecast).
        // 2026-07-07 (user « un film qui marchait se bloque juste après avoir relancé l'app ») :
        //   ne pré-chauffer les WebJS QUE si le provider actif en est un. Ce warm utilise le
        //   WebView CF + l'OkHttp PARTAGÉS ; lancé quand l'user est sur Cloudstream/Movix, il
        //   entre en contention avec le getServers du film en cours → hang → « serveurs bloqués ».
        run {
            val activeIsWebJs = try {
                com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider is
                    com.streamflixreborn.streamflix.providers.WebJsProvider
            } catch (_: Throwable) { false }
            if (activeIsWebJs)
                com.streamflixreborn.streamflix.providers.WebJsProvider.warmUpAll()
        }

        // 2026-07-12 : DessinAnime warm-up au boot RETIRÉ. Le home DessinAnime est 100%
        //   TMDB (zéro CF) et getHome() lance warmUpCf() en background non-bloquant quand
        //   l'user OUVRE le provider. Les backups (Cloudstream/Movix/AnimeSama) alimentent
        //   les serveurs → pas besoin du CF bypass prêt dès le boot. Économise ~12-25s de
        //   WebView/Chromium init + RAM sur Chromecast + contention WebView éliminée.

        // 2026-07-12 : Stream4Free CF warm-up au boot RETIRÉ. Le scrape LIVE + CF bypass se
        //   font à l'ouverture du dossier (LiveHubFolderDialog → fetchStream4CfCategoriesLive).
        //   Fallback BAKED (53 chaînes EN DUR dans l'APK) = dossier jamais vide, zéro attente.
        //   Économise une WebView + moteur Chromium au boot (lourd sur Chromecast 2 Go).

        // 2026-07-06 (user « tant qu'on est sur le home DessinAnime, re-simuler un clic jaquette
        //   de temps en temps pour garder le captcha résolu, un truc pas gourmand ») : keep-alive
        //   périodique. Gardé pour ne rien faire SAUF quand DessinAnime est le provider actif, et
        //   même là keepAliveIfActive() ne re-warm que si le cf_clearance vieillit/expire (sinon
        //   simple check cookie = quasi zéro coût). → au clic sur une jaquette, jamais de captcha.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                try {
                    kotlinx.coroutines.delay(8L * 60L * 1000L) // toutes les 8 min
                    val onDessinAnime = try {
                        com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider is
                            com.streamflixreborn.streamflix.providers.DessinAnimeProvider
                    } catch (_: Throwable) { false }
                    if (onDessinAnime) {
                        com.streamflixreborn.streamflix.providers.DessinAnimeProvider.keepAliveIfActive()
                    }
                } catch (_: Throwable) {}
            }
        }

        // 2026-07-03 (user "jaquettes FrenchAnime grises au démarrage, pas de préchauffage
        //   CF ; en revenant elles chargent") : FrenchAnime est repassé NATIF ; ses posters
        //   sont hébergés sur french-anime.com (Cloudflare). On pré-résout son challenge CF
        //   au boot → cf_clearance prêt AVANT que Glide demande les jaquettes → posters dès
        //   la 1re ouverture. S'exécute AUSSI sur TV (le bypass est une WebView TRANSITOIRE,
        //   créée puis détruite → aucune WebView persistante, contrairement au warmup WebJS).
        // 2026-07-03 : préchauffage CF FrenchAnime au boot RETIRÉ. Le bypass CF tourne dans
        //   une WebView sur le thread principal ; sur le CPU faible de la Chromecast il gelait
        //   l'UI (ANR + restart en boucle, "Skipped 419 frames"). Les jaquettes CF se chargent
        //   à la 1re navigation (bypass à la demande). Fix propre des posters = passer par TMDB
        //   (zéro CF) plutôt que les images french-anime.com — à faire séparément.

        // 2026-07-12 : préchauffage CF proactif ABANDONNÉ (échouait sur ce réseau, cf_clearance
        //   effacé → inutile). On revient au comportement d'avant : CF résolu À LA DEMANDE, et les
        //   backups CF passent en 2ᵉ vague (après les backups sans CF), sauf Wiflix (boucle générique).

        // 2026-07-11 (user « désactive aussi les crashs, on garde ça que pour
        //   les versions test ») : UncaughtExceptionHandler + CrashActivity
        //   désactivés en release/debug normal. Le handler interceptait les crashs,
        //   affichait CrashActivity (écran de rapport) et envoyait sur GitHub.
        //   Désormais Android gère les crashs normalement (dialog système standard).
        //   Pour réactiver en version test : décommenter le bloc ci-dessous.
        /*
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
                    val crashText = sw.toString()

                    // Sauvegarde fichier (backup pour relecture ultérieure)
                    try {
                        java.io.File(getExternalFilesDir(null), "last_crash.txt").writeText(crashText)
                    } catch (_: Throwable) {
                        try {
                            java.io.File(cacheDir, "last_crash.txt").writeText(crashText)
                        } catch (_: Throwable) {}
                    }

                    // Lancer CrashActivity dans le process :crash
                    val intent = android.content.Intent(this@StreamFlixApp, CrashActivity::class.java).apply {
                        putExtra(CrashActivity.EXTRA_CRASH, crashText)
                        addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                    }
                    startActivity(intent)
                } catch (_: Throwable) {
                    // Si même le lancement de CrashActivity échoue, fallback
                    try {
                        val sw2 = java.io.StringWriter()
                        throwable.printStackTrace(java.io.PrintWriter(sw2))
                        java.io.File(cacheDir, "last_crash.txt").writeText(sw2.toString())
                    } catch (_: Throwable) {}
                    previousHandler?.uncaughtException(thread, throwable)
                    return@setDefaultUncaughtExceptionHandler
                }

                // Tuer le process principal (CrashActivity vit dans :crash)
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(1)
            }
        } catch (_: Throwable) {}
        */

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
            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
                // 2026-07-09 : applique la luminosité globale de l'app (overlay noir)
                //   à chaque activité qui revient au premier plan.
                com.streamflixreborn.streamflix.utils.AppDimManager.apply(activity)
            }
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

        // 0-1b. Security init (Conscrypt + ISRG Root + IPTV TLS) sur BACKGROUND THREAD.
        // 2026-07-12 : déportée de main → IO. Sur Chromecast ARM, Conscrypt.newProvider()
        //   charge libconscrypt_jni.so + 288 JNI methods = ~2.6s, puis mergedKeyStore
        //   fusionne 137 certs = ~0.9s → total ~3.5s de main thread LIBÉRÉ.
        //   NetworkClient.buildClient() (lazy) await securityReady avant la 1re connexion
        //   TLS → zéro race condition. Les appels réseau au boot sont tous sur IO avec
        //   des délais (OLA 8s, Vavoo 0.5s) → security sera prête bien avant.
        applicationScope.launch(Dispatchers.IO) {
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            } catch (e: Throwable) {
                Log.e("StreamFlixApp", "Conscrypt init failed (using system SSL): ${e.message}")
            }
            try {
                IsrgRootTrustProvider.install()
            } catch (e: Throwable) {
                Log.e("StreamFlixApp", "IsrgRootTrustProvider failed: ${e.message}")
            }
            try {
                IptvTlsHelper.install()
            } catch (e: Throwable) {
                Log.e("StreamFlixApp", "IptvTlsHelper failed: ${e.message}")
            }
            // Force-build le merged KeyStore (137 certs, ~0.9s) hors main thread
            try {
                @Suppress("UNUSED_EXPRESSION")
                IsrgRootTrustProvider.mergedKeyStore
            } catch (_: Throwable) {}
            securityReady.complete(Unit)
            Log.d("StreamFlixApp", "Security init DONE on IO thread (Conscrypt + ISRG + TLS)")
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
        // 2026-07-06 : ces installContext (TF1/M6/Bfm/Otf/LiveTvHub) DOIVENT rester sur le
        //   main thread / synchrones — les déporter en fond cassait le démarrage de TF1
        //   (TF1Auth lisait son Context avant que la coroutine de fond l'ait posé). On les
        //   remet sur main. (Le blocage démarrage OtfTvService→OkHttp sera traité autrement,
        //   ex. client OkHttp lazy, sans toucher au timing des installContext.)
        try {
            com.streamflixreborn.streamflix.providers.LiveTvHubProvider
                .installReplayDiskCache(cacheDir)
            com.streamflixreborn.streamflix.providers.LiveTvHubProvider
                .installFastDiskCache(cacheDir)
            com.streamflixreborn.streamflix.providers.LiveTvHubProvider
                .installAppContext(applicationContext)
            // 2026-06-18 : TF1Resolver lit le token TF1 via TF1Auth → Context.
            com.streamflixreborn.streamflix.utils.TF1Resolver
                .installContext(applicationContext)
            // 2026-06-19 : M6Resolver lit le token M6 via M6Auth → Context.
            com.streamflixreborn.streamflix.utils.M6Resolver
                .installContext(applicationContext)
            // 2026-06-20 : BfmResolver lit le token BFM via BfmAuth → Context.
            com.streamflixreborn.streamflix.utils.BfmResolver
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
                        com.streamflixreborn.streamflix.providers.LiveTvHubProvider
                            .warmFastCache()
                        Log.d("StreamFlixApp", "FAST cache warmed at cold start (TV Hub active)")
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

        // 2026-05-31 : Google Cast — init CastContext pour la découverte Chromecast.
        // 2026-07-06 (blocage démarrage ~2,5s) : CastContext.getSharedInstance initialise
        //   synchronement le MediaRouter (GlobalMediaRouter.start → scan des providers),
        //   qui DOIT tourner sur le main thread (contrainte du SDK) et prend ~2,5s sur
        //   Chromecast → figeait le cold start. On la DIFFÈRE de 6s : le home s'affiche
        //   d'abord, puis la découverte Cast s'initialise (main libre au moment critique).
        // 2026-07-06 : sur un device TV/leanback (Chromecast, box Android TV), l'app tourne
        //   DÉJÀ sur le récepteur Cast → être ÉMETTEUR Cast (CastContext) n'y sert à rien et
        //   ne fait que payer le scan MediaRouter (~2,5s). On SAUTE l'init Cast sur TV.
        //   Sur mobile, on la garde mais différée (le sender est utile pour caster vers une TV).
        val isTvDevice = try {
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        } catch (_: Throwable) { false }
        if (!isTvDevice) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    com.google.android.gms.cast.framework.CastContext.getSharedInstance(
                        this, java.util.concurrent.Executors.newSingleThreadExecutor()
                    ).addOnSuccessListener {
                        Log.d("StreamFlixApp", "CastContext initialized OK (deferred)")
                    }.addOnFailureListener {
                        Log.w("StreamFlixApp", "CastContext init failed: ${it.message}")
                    }
                } catch (e: Throwable) {
                    Log.w("StreamFlixApp", "CastContext init exception: ${e.message}")
                }
            }, 6000L)
        } else {
            Log.d("StreamFlixApp", "CastContext init SKIPPED (TV/leanback device — pas d'émetteur Cast)")
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

