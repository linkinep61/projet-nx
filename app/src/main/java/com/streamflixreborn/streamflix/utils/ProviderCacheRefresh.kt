package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.providers.CloudstreamProvider
import com.streamflixreborn.streamflix.providers.FrenchStreamProvider
import com.streamflixreborn.streamflix.providers.MovixProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.WebJsProvider

/**
 * 2026-07-04 (restauré depuis backup 2026-07-02) : REFRESH PROFOND des providers
 * VOD/anime (hors IPTV).
 *
 * Purge les caches SERVEUR / résolution / extraction / home — débloque un
 * provider dont le cache est avarié SANS effacer les données de l'app.
 *
 * NE TOUCHE PAS : les données Room (favoris, historique, « continuer à
 * regarder »), les profils, ni les providers IPTV.
 */
object ProviderCacheRefresh {

    private const val TAG = "ProviderCacheRefresh"

    /** Refresh profond du SEUL provider courant. IPTV ignoré. */
    fun refreshProvider(context: Context, provider: Provider) {
        if (Provider.getGroup(provider) == Provider.Companion.ProviderGroup.IPTV) return
        when (provider.name) {
            "Cloudstream" -> runCatching { CloudstreamProvider.clearCaches() }
            "Movix" -> runCatching { MovixProvider.clearCaches() }
            "FrenchStream" -> runCatching { FrenchStreamProvider.clearCaches() }
        }
        runCatching { Extractor.clearAllCache() }
        runCatching { HomeCacheStore.clear(context, provider) }
        runCatching { UserPreferences.clearProviderCache(provider.name) }
    }

    /** Refresh profond de TOUS les providers NON-IPTV.
     *  2026-07-04 (user "le bouton refresh doit vider tous les caches sauf
     *  les préchauffes bypass CF") : purge aussi serverHealth (Extractor) et
     *  TitleServerStatus (serveurs marqués dead/broken). NE touche PAS au
     *  warmUp WebJS ni aux cookies CF → les préchauffes restent actives.
     *
     *  2026-07-05 : AJOUT cache disque OkHttp + cookies CF. C'était le
     *  chaînon manquant — des réponses HTTP cachées sur disque ou un
     *  cf_clearance empoisonné bloquaient TOUS les providers. L'user devait
     *  effacer les données de l'app pour s'en sortir. */
    fun refreshNonIptv(context: Context) {
        // Caches SERVEUR des agrégateurs
        runCatching { CloudstreamProvider.clearCaches() }
        runCatching { MovixProvider.clearCaches() }
        runCatching { FrenchStreamProvider.clearCaches() }
        // Cache d'extraction global (URLs m3u8/mp4 résolues) + serverHealth
        runCatching { Extractor.clearAllCache() }
        // Statuts serveur par titre (dead/verified/unsure)
        runCatching { TitleServerStatus.clearAll() }
        // Par provider NON-IPTV : cache du home + données stockées
        for (p in Provider.providers.keys) {
            if (Provider.getGroup(p) == Provider.Companion.ProviderGroup.IPTV) continue
            runCatching { HomeCacheStore.clear(context, p) }
            runCatching { UserPreferences.clearProviderCache(p.name) }
        }
        // 2026-07-05 : cache disque OkHttp (50MB + 20MB). Des réponses HTTP
        //   cachées avec Cache-Control persistent sur disque même après un
        //   refresh in-memory. Un CF 403 caché → tous les providers CF bloqués.
        evictOkHttpCache()
        // 2026-07-05 : cookies CF empoisonnés. Un cf_clearance périmé/corrompu
        //   dans le CookieManager partagé (WebView+OkHttp) bloque toutes les
        //   requêtes OkHttp vers les sites CF (403 en boucle). On retire SEULS
        //   les cookies CF (pas les sessions/tokens des providers).
        evictCfCookies()
        // 2026-07-06 (user "force-stop + vider cache ne débloque PAS, SEUL effacer
        //   les DONNÉES remarche") : le poison survivait à tout SAUF au clear-data.
        //   Ce qui n'est effacé QUE par le clear-data = le stockage PROFOND de la
        //   WebView (localStorage / IndexedDB / service workers dans app_webview/),
        //   où Cloudflare Turnstile stocke son état de challenge. Ni removeAllCookies
        //   ni le cache OkHttp ne le touchent. On le vide ici → plus besoin de
        //   clear-data manuel. (WebStorage.deleteAllData() = API statique, main thread.)
        evictWebViewStorage()
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  2026-07-05 : NUCLEAR CACHE PURGE — appelé automatiquement par le
    //  PlayerViewModel quand 0 serveur après retries (= état bloqué).
    //  Plus agressif que refreshNonIptv : vide TOUT le disque-cache + cookies.
    // ──────────────────────────────────────────────────────────────────────────
    fun nuclearCachePurge(context: Context) {
        Log.w(TAG, "=== NUCLEAR CACHE PURGE === (0 serveur détecté, auto-recovery)")
        refreshNonIptv(context)
        // Vider TOUT le cookie manager (pas seulement CF) — état désespéré
        runCatching {
            CookieManager.getInstance().removeAllCookies { ok ->
                Log.d(TAG, "nuclear: removeAllCookies success=$ok")
            }
        }
        // DNS cache (un DNS poisonné bloque aussi tout)
        runCatching { DnsResolver.clearCache() }
        // 2026-07-07 : ÉVICTION des connection pools + ANNULATION des requêtes en vol.
        //   Quand le flux serveur fige, c'est parce que des threads OkHttp sont bloqués
        //   dans execute() sur des connexions mortes/lentes. evictAll() ferme les
        //   connexions idle, cancelAll() annule les Call actifs (enqueue) → libère les
        //   threads. Il y a 2 pools séparés : NetworkClient.sharedConnectionPool (partagé
        //   par default/systemDns/noRedirects/trustAll) et le pool par défaut d'OkHttp
        //   dans Extractor.sharedClient (+ ses dérivés CloudstreamProvider.httpClient).
        runCatching {
            NetworkClient.sharedConnectionPool.evictAll()
            Log.d(TAG, "nuclear: NetworkClient connectionPool evicted")
        }
        runCatching {
            Extractor.sharedClient.connectionPool.evictAll()
            Log.d(TAG, "nuclear: Extractor connectionPool evicted")
        }
        runCatching {
            NetworkClient.default.dispatcher.cancelAll()
            Log.d(TAG, "nuclear: NetworkClient dispatcher cancelAll")
        }
        runCatching {
            Extractor.sharedClient.dispatcher.cancelAll()
            Log.d(TAG, "nuclear: Extractor dispatcher cancelAll")
        }
        // 2026-07-07 : RESET des compteurs d'échec extracteurs. Un extracteur avec
        //   count ≥ 5 est marqué "dead" dans getServersProgressive (Cloudstream) et
        //   relégué en fin de liste. Ce compteur est persistant en SharedPreferences
        //   et ne se réinitialise QU'au changement de versionCode (= update app).
        //   En situation de blocage, un transitoire (ISP lag, CF rate-limit) peut
        //   cumuler 5 échecs → l'extracteur reste "dead" indéfiniment. Reset ici.
        runCatching {
            ExtractorFailureTracker.resetAll()
            Log.d(TAG, "nuclear: ExtractorFailureTracker reset")
        }
        // 2026-07-06 : ARME le wipe PROFOND de app_webview/ au prochain démarrage.
        //   WebStorage.deleteAllData() (ci-dessus) vide localStorage/IndexedDB, mais
        //   PAS les service workers ni le cache HTTP de la WebView — seuls effacés en
        //   supprimant le dossier app_webview/, ce que faisait le clear-data manuel.
        //   On ne peut pas le supprimer à chaud (WebViews vivantes) → on le fait au
        //   prochain boot AVANT toute création de WebView (StreamFlixApp.onCreate).
        runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putBoolean(WEBVIEW_DEEP_WIPE_PENDING, true).apply()
            Log.w(TAG, "Wipe profond app_webview/ ARMÉ pour le prochain démarrage")
        }
        Log.w(TAG, "=== NUCLEAR CACHE PURGE TERMINÉ ===")
    }

    /** Flag SharedPrefs : demande un wipe complet de app_webview/ au prochain boot. */
    const val WEBVIEW_DEEP_WIPE_PENDING = "webview_deep_wipe_pending"

    /** Éviction de TOUS les caches disque OkHttp (si accessibles).
     *  2026-07-07 : AJOUT Extractor.sharedClient.cache (20MB à
     *  cacheDir/extractor-http). Ce cache était JAMAIS vidé — des réponses HTTP
     *  erreur cachées sur disque (notamment MovieBox+ API via CloudstreamProvider
     *  qui hérite de sharedClient) bloquaient TOUS les appels suivants au même
     *  endpoint. Seul le clear-data manuel l'effaçait → le user devait effacer
     *  les données de l'app pour débloquer. */
    private fun evictOkHttpCache() {
        runCatching {
            NetworkClient.default.cache?.evictAll()
            Log.d(TAG, "OkHttp NetworkClient cache evicted (okhttp/)")
        }.onFailure { Log.w(TAG, "OkHttp NetworkClient cache evict failed: ${it.message}") }
        runCatching {
            Extractor.sharedClient.cache?.evictAll()
            Log.d(TAG, "Extractor HTTP disk cache evicted (extractor-http/)")
        }.onFailure { Log.w(TAG, "Extractor HTTP disk cache evict failed: ${it.message}") }
    }

    /** Retire les cookies Cloudflare (cf_clearance, __cf_bm) du CookieManager
     *  partagé. Ne touche PAS aux cookies de session des providers. */
    private fun evictCfCookies() {
        runCatching {
            val cm = CookieManager.getInstance()
            // Le CookieManager Android ne donne pas la liste des domaines.
            // On vide les cookies CF pour les domaines connus de nos providers.
            val cfDomains = listOf(
                "flemmix.fast", "wiflix.blue", "wiflix.lat", "wiflix.baby",
                "coflix.trade", "nakios.ink", "moiflix.fans",
                "dessinanime.cc", "french-anime.com",
                "vostfree.ws", "frenchstream.re",
                ".cloudflare.com", "challenges.cloudflare.com",
            )
            var removed = 0
            for (domain in cfDomains) {
                val raw = cm.getCookie(domain) ?: continue
                for (part in raw.split(";")) {
                    val name = part.trim().substringBefore("=").trim()
                    if (name == "cf_clearance" || name == "__cf_bm" || name.startsWith("__cflb")) {
                        // Supprimer un cookie = le poser avec Max-Age=0
                        cm.setCookie(domain, "$name=; Max-Age=0; Path=/")
                        removed++
                    }
                }
            }
            cm.flush()
            if (removed > 0) Log.d(TAG, "Removed $removed CF cookies across ${cfDomains.size} domains")
        }.onFailure { Log.w(TAG, "CF cookie evict failed: ${it.message}") }
    }

    /**
     * 2026-07-06 : vide le stockage PROFOND de la WebView — le SEUL truc qui
     * survivait à force-stop + clear-cache et n'était effacé que par clear-data.
     *
     *  - WebStorage.deleteAllData() : localStorage / sessionStorage / IndexedDB /
     *    WebSQL de TOUTES les origines. C'est là que Cloudflare Turnstile persiste
     *    son état de challenge → un état corrompu rejouait le challenge en boucle
     *    (ou bloquait la page) à chaque ré-entrée, d'où le blocage persistant.
     *  - ServiceWorkerController.clearAllServiceWorkers (API 24+, best-effort) :
     *    un SW CF enregistré interceptait/servait des réponses périmées.
     *
     *  APIs WebView → DOIVENT tourner sur le main thread. On dispatch si besoin.
     *  N'exige AUCUNE instance WebView (statique) → sûr sur low-RAM (Chromecast).
     */
    private fun evictWebViewStorage() {
        val work = Runnable {
            runCatching {
                WebStorage.getInstance().deleteAllData()
                Log.d(TAG, "WebStorage.deleteAllData() OK (localStorage/IndexedDB CF vidés)")
            }.onFailure { Log.w(TAG, "WebStorage clear failed: ${it.message}") }
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    android.webkit.ServiceWorkerController.getInstance()
                        .setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {})
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) work.run()
        else Handler(Looper.getMainLooper()).post(work)
    }
}
