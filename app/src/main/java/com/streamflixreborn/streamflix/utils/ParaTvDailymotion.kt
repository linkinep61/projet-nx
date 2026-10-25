package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.extractors.DailymotionExtractor
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-10-25 (audit du dossier Sport, user : « vérifier si on n'a pas des lecteurs à réparer ») :
 * les liens Dailymotion de la playlist Mix FR (L'Équipe Live 1-7, la chaîne L'Équipe, Tennis
 * Channel, Sport en France…) pointent vers des fichiers ParaTV `…-dm.m3u8`. Ces fichiers
 * contiennent des URL `dmcdn.net/sec2(…)` signées pour l'IP du robot qui les a générés : chez
 * nous, HTTP 403 sur la sous-liste, donc écran noir. L'identifiant Dailymotion de la chaîne est
 * pourtant dans le chemin (`/cloud/3/x6hd012/d/live-480.m3u8`) : on le lit et on demande un lien
 * FRAIS à Dailymotion (DailymotionExtractor), signé pour l'appareil de l'utilisateur.
 */
object ParaTvDailymotion {

    private const val TAG = "ParaTvDailymotion"
    private val ID_DANS_CHEMIN = Regex("""dmcdn\.net/[^\s]*?/cloud/\d+/(x[a-z0-9]{4,})/""", RegexOption.IGNORE_CASE)
    /** URL ParaTV → id Dailymotion (l'id ne change pas, inutile de relire le fichier). */
    private val ids = ConcurrentHashMap<String, String>()

    fun estLienParaTvDm(url: String): Boolean =
        url.contains("ParaTV", ignoreCase = true) && url.substringBefore('?').endsWith("-dm.m3u8")

    /** Null si ce n'est pas un lien ParaTV Dailymotion ou si l'id est introuvable. */
    suspend fun video(url: String): Video? {
        if (!estLienParaTvDm(url)) return null
        val id = ids[url] ?: withContext(Dispatchers.IO) {
            runCatching {
                NetworkClient.default.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    ID_DANS_CHEMIN.find(r.body?.string().orEmpty())?.groupValues?.get(1)
                }
            }.onFailure { Log.w(TAG, "lecture ${url.takeLast(50)} : ${it.message}") }.getOrNull()
        }?.also { ids[url] = it } ?: return null
        Log.i(TAG, "${url.substringAfterLast('/')} → Dailymotion $id")
        return DailymotionExtractor().extract("https://geo.dailymotion.com/player.html?video=$id")
    }
}
