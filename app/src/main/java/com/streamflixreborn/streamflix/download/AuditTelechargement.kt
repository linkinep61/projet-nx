package com.streamflixreborn.streamflix.download

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.BackupRegistry
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 2026-09-27 — Résolution d'un serveur pour le TÉLÉCHARGEMENT, partagée TV / mobile / audit.
 *
 * Même chemin que la lecture (PlayerViewModel.getVideo) : un serveur de secours (bkreg::)
 * passe par le registre, qui ré-aiguille vers la source d'origine. Avant, le bouton
 * télécharger appelait provider.getVideo directement → le provider courant ne connaissait
 * pas le serveur et renvoyait l'adresse de la page embed, téléchargée à la place du film.
 */
object ResolutionTelechargement {

    private val FLUX = Regex("""\.(m3u8|mp4|mkv|webm|ts)(\?|#|$)""", RegexOption.IGNORE_CASE)

    suspend fun resoudre(server: Video.Server): Video {
        val provider = UserPreferences.currentProvider ?: throw Exception("Aucun provider")
        var video = if (server.id.startsWith(BackupRegistry.PREFIX)) BackupRegistry.getVideo(server)
        else provider.getVideo(server)
        // Filet : on n'a toujours que la page embed → on passe par les extracteurs.
        if (video.source == server.src && !FLUX.containsMatchIn(video.source)) {
            video = Extractor.extract(server.src, server)
        }
        return video
    }
}

/**
 * 2026-09-27 — AUDIT DES TÉLÉCHARGEMENTS (debug uniquement, déclenché par adb).
 *
 *   adb shell am broadcast -a com.streamfr.AUDIT_DL
 *
 * Prend la liste de serveurs du lecteur ouvert, résout chaque serveur exactement comme le
 * bouton télécharger, puis simule le téléchargement avec le code de DownloadManager
 * (mêmes clients, mêmes en-têtes) sur un petit morceau, SANS rien écrire.
 * Résultat : lignes « AUDIT_DL » dans logcat.
 */
object AuditTelechargement {
    const val ACTION = "com.streamfr.AUDIT_DL"
    private const val TAG = "AUDIT_DL"

    suspend fun lancer(servers: List<Video.Server>) = withContext(Dispatchers.IO) {
        val liste = servers.filter { it.src.isNotBlank() }.distinctBy { it.src }
        Log.i(TAG, "=== DÉBUT audit : ${liste.size} serveurs ===")
        val sem = Semaphore(3)
        val lignes = coroutineScope {
            liste.mapIndexed { i, s ->
                async {
                    sem.withPermit {
                        val t0 = System.currentTimeMillis()
                        val r = withTimeoutOrNull(60_000L) {
                            try { tester(s) } catch (e: Throwable) {
                                "KO  résolution : ${e.javaClass.simpleName} ${e.message?.take(90)}"
                            }
                        } ?: "KO  délai dépassé (60 s)"
                        val l = "${(i + 1).toString().padStart(2)}. ${s.name.take(45).padEnd(45)} | $r | ${(System.currentTimeMillis() - t0) / 1000}s"
                        Log.i(TAG, l)
                        l
                    }
                }
            }.awaitAll()
        }
        val ok = lignes.count { it.contains("| OK") }
        Log.i(TAG, "=== FIN audit : $ok/${lignes.size} OK ===")
        lignes.forEach { Log.i(TAG, "RÉCAP $it") }
    }

    private suspend fun tester(s: Video.Server): String {
        val video = ResolutionTelechargement.resoudre(s)
        if (video.source.isBlank()) return "KO  aucune source"
        if (video.needsWebViewClick && !video.webViewUrl.isNullOrBlank()) return "KO  navigateur requis (clic)"
        if (video.source.startsWith("data:")) return "KO  source data: (navigateur requis)"
        return DownloadManager.sonder(video)
    }
}