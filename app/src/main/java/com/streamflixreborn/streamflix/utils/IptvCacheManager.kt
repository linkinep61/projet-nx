package com.streamflixreborn.streamflix.utils

import android.util.Log

/**
 * 2026-05-18 v85 : orchestrateur de cache IPTV — évite l'OOM observé quand
 *   plusieurs providers IPTV (Vavoo 4063 ch + OlaTv + VegetaTv + MyIptv
 *   M3U) accumulent leurs catalogues en heap.
 *
 * Règle : un seul provider IPTV peut tenir son catalogue en mémoire à la fois.
 *   Quand l'user switche vers un autre provider (IPTV ou autre), on vide le
 *   cache de TOUS les autres providers IPTV.
 *
 * Hook : appelé par [UserPreferences.currentProvider] setter + onTrimMemory
 *   dans StreamFlixApp.
 *
 * Chaque IPTV provider expose un `clearCache()` libre (no-op si rien à clear).
 */
object IptvCacheManager {
    private const val TAG = "IptvCacheManager"

    /**
     * Vide le cache de tous les providers IPTV sauf [activeProviderName].
     * Si [activeProviderName] est null ou non-IPTV, vide TOUS les caches IPTV.
     */
    fun clearAllExcept(activeProviderName: String?) {
        val keep = activeProviderName ?: ""
        var cleared = 0
        try {
            if (keep != "Vavoo TV") {
                com.streamflixreborn.streamflix.providers.VavooProvider.clearCache()
                cleared++
            }
        } catch (e: Throwable) { Log.w(TAG, "Vavoo clear failed: ${e.message}") }
        try {
            if (keep != "OLA TV") {
                com.streamflixreborn.streamflix.providers.OlaTvProvider.clearCache()
                cleared++
            }
        } catch (e: Throwable) { Log.w(TAG, "OlaTv clear failed: ${e.message}") }
        try {
            if (keep != "Vegeta TV") {
                com.streamflixreborn.streamflix.providers.VegetaTvProvider.clearCache()
                cleared++
            }
        } catch (e: Throwable) { Log.w(TAG, "VegetaTv clear failed: ${e.message}") }
        try {
            if (keep != "TV Hub") {
                com.streamflixreborn.streamflix.providers.LiveTvHubProvider.clearCache()
                cleared++
            }
        } catch (e: Throwable) { Log.w(TAG, "LiveTvHub clear failed: ${e.message}") }
        try {
            if (keep != "Mon IPTV") {
                com.streamflixreborn.streamflix.providers.MyIptvProvider.clearCache()
                cleared++
            }
        } catch (e: Throwable) { Log.w(TAG, "MyIptv clear failed: ${e.message}") }
        val rt = Runtime.getRuntime()
        val freeM = rt.freeMemory() / 1024 / 1024
        val totalM = rt.totalMemory() / 1024 / 1024
        val maxM = rt.maxMemory() / 1024 / 1024
        Log.d(TAG, "clearAllExcept('$keep'): cleared $cleared other IPTV provider(s). Heap ${totalM - freeM}/${totalM}MB, max ${maxM}MB")
    }

    /** Vide TOUS les caches IPTV (utilisé par onTrimMemory). */
    fun clearAll() = clearAllExcept(null)
}
