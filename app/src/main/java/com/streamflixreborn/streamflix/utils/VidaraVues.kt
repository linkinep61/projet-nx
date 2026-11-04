package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 2026-11-04 (user : « que ce soit comptabilisé sur leur site, le nombre de personnes qui
 * lisent les vidéos ») — « Visionnage en direct » Vidara pour les lectures faites DANS l'appli.
 *
 * L'appli lit le flux HLS en natif (VidaraExtractor → /api/stream) sans charger le lecteur de
 * la page d'embed : Vidara ne voit donc aucun spectateur. Relevé dans Chrome sur
 * https://vidara.so/e/<code> : le lecteur envoie POST /api/heartbeat {file_id, session_id}
 * au démarrage puis toutes les 30 s → compteur « Visionnage en direct » du tableau de bord.
 * C'est un appel en clair qui dit « quelqu'un regarde ce fichier maintenant » : exact.
 *
 * ⚠ VOLONTAIREMENT PAS de /api/count (« Vues aujourd'hui »). Ce tracker est chiffré, porte des
 *   signaux faits pour distinguer un humain dans un navigateur d'un robot (souris, GPU, onglet,
 *   bloqueur de pub…) et alimente « Gains du jour ». Les reproduire depuis l'appli reviendrait
 *   à tromper leur anti-fraude. On ne le fait pas.
 *
 * `file_id` (numérique) n'est pas rendu par /api/stream : lu une fois dans la page d'embed.
 * Branchement : VidaraExtractor enregistre (flux → front, filecode) à la résolution ; les
 * fragments lecteur appellent `lecture(video, isPlaying)` sur onIsPlayingChanged.
 */
object VidaraVues {
    private const val TAG = "VidaraVues"
    /** 2026-11-05 (user : « désactiver pour l'instant ») : les conditions de l'hébergeur excluent
     *  le trafic des applications tierces ; on n'envoie plus rien tant que ce drapeau est faux. */
    private const val ACTIF = false
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private class Lecture(val front: String, val filecode: String) {
        var fileId: Long = 0L
        val sessionId: String = "${System.currentTimeMillis()}-${rnd()}"
        @Volatile var job: Job? = null
    }

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** flux HLS résolu → lecture (posé par l'extracteur, consommé par le lecteur). */
    private val lectures = java.util.concurrent.ConcurrentHashMap<String, Lecture>()
    @Volatile private var enCours: Lecture? = null

    private fun rnd(): String = java.lang.Long.toString(SecureRandom().nextLong() and 0x7fffffffffffL, 36)

    /** Appelé par VidaraExtractor une fois le flux résolu. */
    fun enregistrer(streamUrl: String, front: String, filecode: String) {
        if (!ACTIF) return
        if (lectures.size > 50) lectures.clear()
        lectures[streamUrl] = Lecture(front.trimEnd('/'), filecode)
    }

    /** Appelé par les fragments lecteur sur onIsPlayingChanged. */
    fun lecture(video: Video?, enLecture: Boolean) {
        if (!ACTIF) return
        val l = video?.source?.let { lectures[it] }
        if (!enLecture) {
            if (l == null || enCours === l) arreter()
            return
        }
        if (l == null) { arreter(); return }
        if (enCours === l && l.job?.isActive == true) return
        arreter()
        enCours = l
        l.job = portee.launch {
            try {
                if (l.fileId == 0L) l.fileId = lireFileId(l) ?: return@launch
                var secondes = 0
                while (isActive && enCours === l) {
                    if (secondes % 30 == 0) heartbeat(l)
                    delay(1000); secondes++
                    if (secondes > 6 * 3600) break     // garde-fou : jamais plus de 6 h
                }
            } catch (e: Exception) {
                Log.w(TAG, "suivi ${l.filecode} interrompu : ${e.message}")
            }
        }
    }

    private fun arreter() {
        enCours?.job?.cancel()
        enCours = null
    }

    private fun lireFileId(l: Lecture): Long? {
        val req = Request.Builder().url("${l.front}/e/${l.filecode}")
            .header("User-Agent", UA).header("Referer", "https://vidara.so/").build()
        val html = runCatching {
            Extractor.sharedClient.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull() ?: return null
        val id = Regex("""file_id\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)?.toLongOrNull()
        if (id == null) Log.w(TAG, "file_id introuvable dans la page d'embed ${l.filecode}")
        return id
    }

    private fun heartbeat(l: Lecture) {
        val corps = JSONObject().put("file_id", l.fileId).put("session_id", l.sessionId).toString()
        val req = Request.Builder().url("${l.front}/api/heartbeat")
            .post(corps.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", UA)
            .header("Referer", "${l.front}/e/${l.filecode}")
            .header("Origin", l.front)
            .header("Accept", "*/*")
            .build()
        val code = runCatching { Extractor.sharedClient.newCall(req).execute().use { it.code } }.getOrDefault(-1)
        Log.d(TAG, "heartbeat ${l.filecode} (file_id ${l.fileId}) → HTTP $code")
    }
}
