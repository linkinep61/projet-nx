package com.streamflixreborn.streamflix.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Singleton that orchestrates downloads.
 * Downloads are processed directly in coroutines (no foreground service needed).
 * Provides UI-facing flows for download state.
 */
object DownloadManager {

    private const val TAG = "DownloadManager"
    private const val CHANNEL_ID = "streamflix_downloads"
    private const val NOTIFICATION_ID = 9001
    private const val COMPLETED_NOTIFICATION_ID = 9002
    private const val BUFFER_SIZE = 32_768 // 32KB buffer for faster I/O

    /** Number of parallel connections for IDM-style multi-connection downloads */
    private const val PARALLEL_CONNECTIONS = 4

    /** Minimum file size (bytes) to use parallel download for direct files */
    private const val PARALLEL_MIN_SIZE = 5_000_000L

    private val gson = Gson()

    private lateinit var appContext: Context
    private lateinit var dao: DownloadDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val MAX_CONCURRENT = 2

    /** Set of download IDs that have been requested to pause. */
    private val pauseRequests = mutableSetOf<String>()

    /** Currently active download IDs. */
    private val activeDownloads = mutableSetOf<String>()

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            dao = DownloadDatabase.getInstance(appContext).downloadDao()
            createNotificationChannel()
            Log.d(TAG, "init() success — DAO ready")

            // Resume any stuck downloads from a previous session
            scope.launch {
                val stuck = dao.getActiveDownloads()
                for (dl in stuck) {
                    if (dl.isDownloading) {
                        Log.w(TAG, "Resetting stuck download from previous session: ${dl.id}")
                        dao.updateStatus(dl.id, DownloadEntity.Status.PENDING)
                    }
                }
                if (stuck.any { it.isDownloading || it.isPending }) {
                    triggerProcessQueue()
                }

                // One-time migration: convert pre-MediaStore downloads (file paths in
                // app-private dir) into proper MediaStore entries so they become visible
                // to other apps. Idempotent — fast no-op when nothing to migrate.
                migrateLegacyToMediaStore()
            }
        } catch (e: Exception) {
            Log.e(TAG, "init() FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /**
     * For each completed download that still references a plain file path (i.e. a download
     * created before we switched to MediaStore), copy the bytes into MediaStore and rewrite
     * the filePath in the DB to the resulting content:// URI.
     *
     * Safe to call on every app start. Skips entries already in MediaStore (content://) and
     * entries whose source file no longer exists.
     */
    private suspend fun migrateLegacyToMediaStore() {
        val all = try {
            dao.getAllSnapshot()
        } catch (e: Exception) {
            Log.e(TAG, "migrateLegacyToMediaStore: getAllSnapshot failed", e)
            return
        }

        var migrated = 0
        for (entry in all) {
            if (!entry.isCompleted) continue
            if (entry.filePath.startsWith("content://")) continue

            val src = File(entry.filePath)
            if (!src.exists() || src.length() == 0L) continue

            val mime = guessMimeType(src, isHls = src.extension.equals("ts", ignoreCase = true))
            val uri = com.streamflixreborn.streamflix.utils.MediaStoreHelper.publish(
                context = appContext,
                srcFile = src,
                displayName = src.name,
                mimeType = mime,
            )
            if (uri != null) {
                dao.updateFilePath(entry.id, uri.toString())
                migrated++
                Log.d(TAG, "Migrated legacy → MediaStore: ${entry.id} → $uri")
            } else {
                Log.w(TAG, "Migration to MediaStore failed for ${entry.id}, keeping original path")
            }
        }
        if (migrated > 0) {
            Log.d(TAG, "migrateLegacyToMediaStore: migrated $migrated download(s)")
        }
    }

    // ── UI Flows ──

    fun getAllDownloads(): Flow<List<DownloadEntity>> = dao.getAll()

    fun getCompletedCount(): Flow<Int> = dao.getCompletedCount()

    // ── Actions ──

    /**
     * Enqueue a new download from a resolved Video.
     * @param video The resolved Video with source URL
     * @param videoType The video type (Movie or Episode) for metadata
     * @param providerName Current provider name
     */
    suspend fun enqueue(
        video: Video,
        videoType: Video.Type,
        providerName: String,
        serverName: String = "",
    ) {
        val serverTag = sanitizeFileName(serverName).take(30)
        val id: String
        val title: String
        val subtitle: String?
        val poster: String?
        val type: String
        val fileName: String

        // ⚠ 2026-08-20 — TOUT SORT EN .mp4, Y COMPRIS LE HLS. NE PAS REVENIR À .ts.
        //   Avant : « .ts pour le HLS, car les segments concaténés exigent un conteneur TS ».
        //   C'était vrai tant qu'on se contentait de coller les segments bout à bout.
        //   Depuis, les segments sont MUXÉS dans un MP4 au moment de l'assemblage
        //   (voir `assemblerEnMp4`) — sans ré-encodage, donc sans perte ni coût.
        //   Raison du changement, mesurée : VOE refuse les .ts. L'envoi montait 1,3 Go
        //   entier puis répondait « File type not allowed ». Or le HLS couvre VOE lui-même,
        //   Movix·Voe et la plupart des serveurs en m3u8 — donc l'essentiel de ce que le
        //   user veut remettre en ligne pour ajouter des serveurs.
        //   Les deux cas qui restent en .ts rectifient le nom eux-mêmes : l'enregistrement
        //   direct (flux sans fin) et l'échec de muxage (repli sûr).
        val isHls = video.type?.contains("mpegURL", ignoreCase = true) == true
                || video.type?.contains("m3u8", ignoreCase = true) == true
                || video.source.contains(".m3u8", ignoreCase = true)
        val ext = "mp4"

        when (videoType) {
            is Video.Type.Movie -> {
                id = "${providerName}_movie_${videoType.id}_$serverTag"
                title = videoType.title
                subtitle = serverName.ifBlank { null }
                poster = videoType.poster
                type = "movie"
                // « 550 - Fight Club (1999).mp4 » — cf. le pavé NOMMAGE plus bas.
                val idFilm = idTmdbOuNull(videoType.id)
                val annee = videoType.releaseDate.take(4).takeIf { a -> a.length == 4 && a.all(Char::isDigit) }
                fileName = nomPropre(
                    (if (idFilm != null) "$idFilm - " else "") +
                        videoType.title +
                        (if (annee != null) " ($annee)" else "")
                ) + ".$ext"
            }
            is Video.Type.Episode -> {
                id = "${providerName}_ep_${videoType.tvShow.id}_S${videoType.season.number}E${videoType.number}_$serverTag"
                title = videoType.tvShow.title
                subtitle = "S${videoType.season.number} E${videoType.number}" +
                        (videoType.title?.let { " - $it" } ?: "") +
                        if (serverName.isNotBlank()) " ($serverName)" else ""
                poster = videoType.poster ?: videoType.tvShow.poster
                type = "episode"
                // « Star Trek - S01E03 - Where No Man Has Gone Before [tv-253].mp4 »
                //   Ordre imposé par VoeLibrary : série en mot de tête, puis SxxExx sur
                //   deux chiffres, et l'identifiant de série en FIN (jamais en tête, il
                //   serait lu comme un identifiant de FILM). Cf. le pavé NOMMAGE plus bas.
                val idSerie = idTmdbOuNull(videoType.tvShow.id)
                // Saison RÉELLE, pas `season.number` — voir le pavé ANIME plus bas.
                val marque = "S${deuxChiffres(saisonReelle(videoType))}E${deuxChiffres(videoType.number)}"
                val titreEp = videoType.title?.takeIf { t -> t.isNotBlank() }
                // Sur AnimeSama le titre de série peut être vide : on le reconstruit depuis
                //   le slug, exactement comme BackupRegistry.titleFromId le fait pour ses
                //   recherches. Sans nom de série en tête, `motTeteCouvert` rejette le fichier.
                //   `cleanTitle` est celui qu'utilise BackupRegistry pour bâtir `key.title`,
                //   donc le nom écrit ici et le titre recherché plus tard sont le MÊME texte
                //   (« Spider-Noir (Version Couleur) – Saison 1 » → « Spider-Noir »).
                val nomSerie = com.streamflixreborn.streamflix.utils.BackupRegistry.cleanTitle(
                    videoType.tvShow.title.ifBlank {
                        com.streamflixreborn.streamflix.utils.BackupRegistry.titleFromId(videoType.id)
                    }
                )
                val langue = langueDansId(videoType.id)
                fileName = nomPropre(
                    nomSerie + " - " + marque +
                        (if (titreEp != null) " - $titreEp" else "") +
                        (if (langue != null) " ($langue)" else "") +
                        (if (idSerie != null) " [tv-$idSerie]" else "")
                ) + ".$ext"
            }
        }

        // Check if already exists
        val existing = dao.getById(id)
        if (existing != null && (existing.isCompleted || existing.isDownloading)) {
            Log.d(TAG, "Download already exists: $id (${existing.status})")
            showToast("Téléchargement déjà en cours ou terminé")
            return
        }

        val downloadDir = getDownloadDir()
        // ⚠ 2026-08-20 — DEUX TÉLÉCHARGEMENTS NE DOIVENT JAMAIS VISER LE MÊME FICHIER.
        //   Le nom ne porte plus le tag du serveur (il polluait le nom au repartage), donc
        //   le même épisode pris sur deux serveurs produit le même nom. Or la reprise se
        //   fait par en-tête `Range: bytes=<taille du fichier>-` : le second téléchargement
        //   croirait reprendre là où le premier s'est arrêté et écrirait la suite d'une
        //   source dans le fichier d'une AUTRE. Résultat : un fichier illisible, sans la
        //   moindre erreur signalée. On suffixe donc « (2) », « (3) »…
        //   Exception volontaire : si c'est LE MÊME téléchargement qui repart (échec ou
        //   pause), on garde son chemin — sinon on repartirait de zéro à chaque reprise.
        val filePath = existing?.filePath?.takeIf { !it.startsWith("content://") }
            ?: cheminLibre(downloadDir, fileName, id).absolutePath

        val headersJson = video.headers?.let { gson.toJson(it) }

        val entity = DownloadEntity(
            id = id,
            title = title,
            subtitle = subtitle,
            poster = poster,
            type = type,
            providerName = providerName,
            sourceUrl = video.source,
            headersJson = headersJson,
            filePath = filePath,
            mimeType = video.type,
        )

        dao.insert(entity)
        Log.d(TAG, "Enqueued download: $id → ${video.source}")
        Log.i(TAG, "nom du fichier : « ${File(filePath).name} »")
        showToast("⬇ Téléchargement ajouté : $title")

        // Start processing the queue directly
        triggerProcessQueue()
    }

    // Toute action MANUELLE remet le compteur de reprises à zéro : l'utilisateur reprend
    //   la main, il repart donc avec ses 20 tentatives entières.
    fun requestPause(id: String) {
        synchronized(pauseRequests) {
            pauseRequests.add(id)
        }
        tentatives.remove(id)
        scope.launch {
            dao.updateStatus(id, DownloadEntity.Status.PAUSED)
        }
    }

    suspend fun pause(id: String) {
        synchronized(pauseRequests) {
            pauseRequests.add(id)
        }
        tentatives.remove(id)
        dao.updateStatus(id, DownloadEntity.Status.PAUSED)
    }

    suspend fun resume(id: String) {
        tentatives.remove(id)
        dao.updateStatus(id, DownloadEntity.Status.PENDING)
        triggerProcessQueue()
    }

    suspend fun cancel(id: String) {
        val download = dao.getById(id) ?: return
        synchronized(pauseRequests) { pauseRequests.add(id) } // stop if active
        tentatives.remove(id)
        delay(200) // let the download loop notice
        deletePhysicalFile(download.filePath)
        dao.deleteById(id)
    }

    suspend fun retry(id: String) {
        tentatives.remove(id)
        synchronized(pauseRequests) { pauseRequests.remove(id) }
        dao.updateStatus(id, DownloadEntity.Status.PENDING)
        triggerProcessQueue()
    }

    suspend fun deleteCompleted(id: String) {
        val download = dao.getById(id) ?: return
        deletePhysicalFile(download.filePath)
        dao.deleteById(id)
    }

    /** Force-remove a download row from the DB regardless of state, ignoring any
     *  file cleanup errors. Used as fallback when the regular delete path fails
     *  (e.g. failed live recording with broken filePath). */
    suspend fun forceDelete(id: String) {
        val download = dao.getById(id)
        if (download != null) {
            try { deletePhysicalFile(download.filePath) } catch (_: Exception) {}
        }
        try { synchronized(pauseRequests) { pauseRequests.remove(id) } } catch (_: Exception) {}
        dao.deleteById(id)
    }

    /**
     * Delete the actual bytes referenced by a download row's filePath.
     * Handles both legacy file-system paths and MediaStore content:// URIs.
     */
    private fun deletePhysicalFile(filePath: String) {
        if (filePath.startsWith("content://")) {
            com.streamflixreborn.streamflix.utils.MediaStoreHelper.delete(appContext, filePath)
        } else {
            runCatching { File(filePath).delete() }
        }
    }

    // ── Internal ──

    internal fun getDao(): DownloadDao = dao

    /**
     * Try to fill available download slots with pending items.
     * Launches up to MAX_CONCURRENT workers concurrently.
     */
    private fun triggerProcessQueue() {
        scope.launch {
            while (true) {
                val currentCount = synchronized(activeDownloads) { activeDownloads.size }
                if (currentCount >= MAX_CONCURRENT) break

                val next = dao.getNextPending() ?: break

                // Claim this download — skip if already active
                val claimed = synchronized(activeDownloads) { activeDownloads.add(next.id) }
                if (!claimed) continue

                // Mark as DOWNLOADING immediately to prevent double-pick
                dao.updateStatus(next.id, DownloadEntity.Status.DOWNLOADING)

                // Launch a worker for this download
                scope.launch { processDownload(next) }

                delay(100) // small gap before filling next slot
            }

            // If nothing is active anymore, clear the notification
            val isEmpty = synchronized(activeDownloads) { activeDownloads.isEmpty() }
            if (isEmpty) clearNotification()
        }
    }

    /**
     * Process a single download. Runs in its own coroutine.
     */
    private suspend fun processDownload(download: DownloadEntity) {
        Log.d(TAG, "Processing download: id=${download.id} title=${download.title}")
        showToast("⬇ Téléchargement : ${download.title}")
        synchronized(pauseRequests) { pauseRequests.remove(download.id) }

        try {
            updateNotification("Téléchargement : ${download.title}", 0)

            val isHls = download.sourceUrl.contains(".m3u8") ||
                    download.sourceUrl.contains("m3u8", ignoreCase = true) ||
                    download.mimeType?.contains("mpegURL", ignoreCase = true) == true ||
                    download.mimeType?.contains("m3u8", ignoreCase = true) == true

            // 2026-08-20 : le HLS choisit lui-même son fichier final (MP4 muxé, ou .ts si
            //   le muxage a échoué, ou .ts pour un enregistrement direct). On travaille
            //   ensuite sur CE fichier et non sur `download.filePath`, qui date de la mise
            //   en file et serait périmé.
            val fichierFinal: File = if (isHls) {
                downloadHls(download)
            } else {
                downloadDirect(download)
                File(download.filePath)
            }

            // Clean up live-stop flag (set by downloadHlsLive when user stops a live
            // recording — we already cleared pauseRequests so the if-pause branch
            // below skips and we go straight to COMPLETED + publish to MediaStore).
            val wasLiveStop = synchronized(liveStopRequests) {
                liveStopRequests.remove(download.id)
            }
            if (wasLiveStop) Log.d(TAG, "Live recording stop → finalizing as COMPLETED: ${download.id}")

            if (isPauseRequested(download.id)) {
                Log.d(TAG, "Download paused: ${download.id}")
                synchronized(pauseRequests) { pauseRequests.remove(download.id) }
                updateNotification("En pause : ${download.title}", 0)
            } else {
                // Download fully written to app-private dir. Now publish into MediaStore
                // so the file appears in /Movies/StreamFlix/ and is visible to VLC, gallery,
                // file managers, etc. The DB filePath becomes the content:// URI.
                val srcFile = fichierFinal
                val displayName = srcFile.name
                val mime = guessMimeType(srcFile, isHls)
                val publishedUri = com.streamflixreborn.streamflix.utils.MediaStoreHelper.publish(
                    context = appContext,
                    srcFile = srcFile,
                    displayName = displayName,
                    mimeType = mime,
                )
                if (publishedUri != null) {
                    // Replace filePath with the MediaStore URI so playback uses ContentResolver.
                    dao.updateFilePath(download.id, publishedUri.toString())
                    Log.d(TAG, "Published to MediaStore: ${download.id} → $publishedUri")
                } else {
                    Log.w(TAG, "MediaStore publish failed for ${download.id} — keeping app-private path as fallback")
                }
                dao.markCompleted(download.id)
                tentatives.remove(download.id)   // compteur de reprises remis à zéro
                showCompletedNotification(download.title)
                showToast("✅ Téléchargement terminé : ${download.title}")
                Log.d(TAG, "Download completed: ${download.id}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${download.id}", e)
            if (!reprogrammerApresEchec(download, e)) {
                dao.markFailed(download.id, e.message ?: "Unknown error")
                showToast("❌ Échec : ${download.title}")
            }
        } finally {
            synchronized(activeDownloads) {
                activeDownloads.remove(download.id)
            }
            // Try to fill the freed slot with next pending
            triggerProcessQueue()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    // REPRISE AUTOMATIQUE APRÈS COUPURE — 2026-08-20
    //   (user « quand on télécharge, souvent le téléchargement se met en échec et on est
    //    obligé de le relancer manuellement… des fois la coupure peut être 5 à 6 fois
    //    voire 8 fois pendant le téléchargement »)
    //
    // Avant : toute exception menait à markFailed(), point final. Le fichier partiel était
    //   conservé mais plus personne ne le reprenait — d'où le bouton « réessayer » pressé
    //   huit fois de suite sur un seul film.
    //
    // Ce qui rend la reprise sûre ici : les deux moteurs SAVENT déjà repartir de l'octet
    //   où ils se sont arrêtés. `downloadDirectSingle` envoie `Range: bytes=<taille>-` à
    //   partir du fichier existant, et le mode parallèle relit ses fichiers de progression
    //   par tronçon. Relancer ne retélécharge donc PAS ce qui est déjà là.
    //
    // Ce qu'on ne relance PAS, volontairement :
    //   · une pause ou une annulation de l'utilisateur — c'est sa décision, pas une panne ;
    //   · une erreur qui ne guérira pas d'elle-même (404, 403, 410, disque plein). Réessayer
    //     vingt fois un lien mort ne fait que masquer la vraie cause dans le journal.
    //
    // Le compteur vit en mémoire et non en base : au redémarrage de l'app on repart à zéro,
    //   ce qui est le comportement voulu — une nouvelle session mérite une nouvelle chance,
    //   et ça évite une migration Room pour une valeur aussi éphémère.
    // ══════════════════════════════════════════════════════════════════════════════════

    private const val MAX_TENTATIVES = 20

    /** Nombre de reprises déjà consommées, par identifiant de téléchargement. */
    private val tentatives = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Une erreur dont on sait qu'elle ne guérira pas en réessayant. */
    private fun erreurDefinitive(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase()
        return m.contains("http 404") || m.contains("http 403") || m.contains("http 410") ||
            m.contains("http 401") || m.contains("enospc") || m.contains("no space left")
    }

    /**
     * Programme une reprise après coupure.
     * @return true si une reprise est programmée (l'appelant ne doit alors PAS marquer
     *   l'échec comme définitif), false s'il faut abandonner.
     */
    private suspend fun reprogrammerApresEchec(download: DownloadEntity, e: Exception): Boolean {
        if (isPauseRequested(download.id)) return false
        if (erreurDefinitive(e)) {
            Log.w(TAG, "pas de reprise pour ${download.id} : erreur définitive (${e.message})")
            tentatives.remove(download.id)
            return false
        }
        val n = tentatives.merge(download.id, 1, Int::plus) ?: 1
        if (n > MAX_TENTATIVES) {
            Log.w(TAG, "abandon de ${download.id} après $MAX_TENTATIVES reprises")
            tentatives.remove(download.id)
            return false
        }
        // 5s, 10s, 20s, 40s, puis 60s pour toutes les suivantes.
        val attenteMs = minOf(60_000L, 5_000L shl minOf(n - 1, 4))
        val dejaLa = runCatching { File(download.filePath).length() }.getOrDefault(0L)
        Log.i(
            TAG,
            "coupure sur ${download.id} (${e.message}) — reprise $n/$MAX_TENTATIVES dans " +
                "${attenteMs / 1000}s, ${dejaLa / 1_000_000} Mo déjà écrits",
        )
        dao.markFailed(
            download.id,
            "Coupure — reprise automatique $n/$MAX_TENTATIVES dans ${attenteMs / 1000} s",
        )
        showToast("↻ Coupure — reprise auto ($n/$MAX_TENTATIVES) : ${download.title}")

        scope.launch {
            delay(attenteMs)
            val actuel = dao.getById(download.id) ?: return@launch   // supprimé entre-temps
            if (actuel.isCompleted || actuel.isDownloading) return@launch
            if (actuel.isPaused || isPauseRequested(download.id)) {
                Log.d(TAG, "reprise annulée pour ${download.id} : mis en pause entre-temps")
                return@launch
            }
            dao.updateStatus(download.id, DownloadEntity.Status.PENDING)
            triggerProcessQueue()
        }
        return true
    }

    // ── Download Methods ──

    /**
     * Download a direct MP4/video file. Routes to parallel or single-connection mode.
     * Uses IDM-style multi-connection download when the server supports Range requests.
     */
    private suspend fun downloadDirect(download: DownloadEntity) {
        val file = File(download.filePath)
        file.parentFile?.mkdirs()
        val headers = parseHeaders(download.headersJson)

        // Probe server for Range support and file size
        val headRequest = Request.Builder()
            .url(download.sourceUrl)
            .head()
            .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        var totalBytes = -1L
        var supportsRange = false

        try {
            val headResponse = withContext(Dispatchers.IO) {
                Extractor.sharedClient.newCall(headRequest).execute()
            }
            totalBytes = headResponse.header("Content-Length")?.toLongOrNull() ?: -1
            supportsRange = headResponse.header("Accept-Ranges").equals("bytes", ignoreCase = true)
            headResponse.close()
        } catch (e: Exception) {
            Log.w(TAG, "HEAD request failed, falling back to single connection: ${e.message}")
        }

        if (totalBytes > PARALLEL_MIN_SIZE && supportsRange) {
            Log.d(TAG, "Direct parallel download: $PARALLEL_CONNECTIONS connections for ${totalBytes / 1_000_000}MB file")
            downloadDirectParallel(download, file, headers, totalBytes)
        } else {
            Log.d(TAG, "Direct single-connection download (size=${totalBytes}, range=$supportsRange)")
            downloadDirectSingle(download, file, headers)
        }
    }

    /**
     * Single-connection direct download with resume support (original behavior).
     */
    private suspend fun downloadDirectSingle(
        download: DownloadEntity,
        file: File,
        headers: Map<String, String>,
    ) {
        val existingLength = if (file.exists()) file.length() else 0L

        val requestBuilder = Request.Builder()
            .url(download.sourceUrl)
            .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }

        if (existingLength > 0) {
            requestBuilder.header("Range", "bytes=$existingLength-")
        }

        val response = withContext(Dispatchers.IO) {
            // ⚠ downloadClient et NON sharedClient : ce dernier a un callTimeout de
            //   30 s qui tuait la socket en plein transfert. Voir Extractor.downloadClient.
            Extractor.downloadClient.newCall(requestBuilder.build()).execute()
        }

        if (!response.isSuccessful && response.code != HttpURLConnection.HTTP_PARTIAL) {
            response.close()
            throw Exception("HTTP ${response.code}: ${response.message}")
        }

        val body = response.body ?: throw Exception("Empty response body")
        val contentLength = body.contentLength()
        val totalBytes = if (response.code == HttpURLConnection.HTTP_PARTIAL) {
            existingLength + contentLength
        } else {
            contentLength
        }

        val append = response.code == HttpURLConnection.HTTP_PARTIAL
        val outputStream = FileOutputStream(file, append)
        val inputStream = body.byteStream()

        try {
            copyStream(inputStream, outputStream, download.id, existingLength, totalBytes)
        } finally {
            inputStream.close()
            outputStream.close()
            response.close()
        }
    }

    /**
     * IDM-style parallel chunk download for direct files.
     * Splits the file into PARALLEL_CONNECTIONS chunks, downloads each with Range headers,
     * writing directly to the correct file offset via RandomAccessFile.
     */
    private suspend fun downloadDirectParallel(
        download: DownloadEntity,
        file: File,
        headers: Map<String, String>,
        totalBytes: Long,
    ) {
        // Progress tracking directory
        val tempDir = File(file.parent, ".dlp_${download.id.hashCode().toUInt()}")
        tempDir.mkdirs()

        // Pre-allocate file if not already done
        if (!file.exists() || file.length() != totalBytes) {
            withContext(Dispatchers.IO) {
                RandomAccessFile(file, "rw").use { it.setLength(totalBytes) }
            }
        }

        // Calculate chunk boundaries
        val chunkSize = totalBytes / PARALLEL_CONNECTIONS
        data class Chunk(val index: Int, val start: Long, val end: Long)

        val chunks = (0 until PARALLEL_CONNECTIONS).map { i ->
            val start = i * chunkSize
            val end = if (i == PARALLEL_CONNECTIONS - 1) totalBytes - 1 else (i + 1) * chunkSize - 1
            Chunk(i, start, end)
        }

        val downloadedTotal = AtomicLong(0L)

        // Check resume progress for each chunk
        for (chunk in chunks) {
            val progressFile = File(tempDir, "chunk_${chunk.index}")
            if (progressFile.exists()) {
                val resumed = progressFile.readText().trim().toLongOrNull()
                if (resumed != null && resumed > chunk.start && resumed <= chunk.end + 1) {
                    downloadedTotal.addAndGet(resumed - chunk.start)
                }
            }
        }

        val resumedBytes = downloadedTotal.get()
        if (resumedBytes > 0) {
            Log.d(TAG, "Direct parallel resume: ${resumedBytes / 1_000_000}MB already downloaded for ${download.id}")
        }

        coroutineScope {
            chunks.map { chunk ->
                launch(Dispatchers.IO) {
                    if (isPauseRequested(download.id)) return@launch

                    // Check resume progress for this chunk
                    val progressFile = File(tempDir, "chunk_${chunk.index}")
                    var currentStart = chunk.start
                    if (progressFile.exists()) {
                        val resumed = progressFile.readText().trim().toLongOrNull()
                        if (resumed != null && resumed > chunk.start && resumed <= chunk.end + 1) {
                            currentStart = resumed
                        }
                    }

                    // Skip if chunk already complete
                    if (currentStart > chunk.end) return@launch

                    val request = Request.Builder()
                        .url(download.sourceUrl)
                        .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                        .header("Range", "bytes=$currentStart-${chunk.end}")
                        .apply { headers.forEach { (k, v) -> header(k, v) } }
                        .build()

                    val response = Extractor.sharedClient.newCall(request).execute()
                    try {
                        if (!response.isSuccessful && response.code != HttpURLConnection.HTTP_PARTIAL) {
                            throw Exception("Chunk ${chunk.index}: HTTP ${response.code}")
                        }

                        val input = response.body?.byteStream()
                            ?: throw Exception("Empty body for chunk ${chunk.index}")

                        val raf = RandomAccessFile(file, "rw")
                        raf.seek(currentStart)

                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        var position = currentStart
                        var lastUpdateTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (isPauseRequested(download.id)) {
                                progressFile.writeText(position.toString())
                                raf.close()
                                return@launch
                            }

                            raf.write(buffer, 0, bytesRead)
                            position += bytesRead
                            val totalDone = downloadedTotal.addAndGet(bytesRead.toLong())

                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime > 500) {
                                lastUpdateTime = now
                                progressFile.writeText(position.toString())
                                val progress = ((totalDone * 100) / totalBytes).toInt().coerceIn(0, 100)
                                dao.updateProgress(download.id, DownloadEntity.Status.DOWNLOADING, totalDone, totalBytes)
                                updateNotification("${download.title}", progress)
                            }
                        }

                        raf.close()
                        progressFile.writeText((chunk.end + 1).toString())
                    } finally {
                        response.close()
                    }
                }
            }
        }

        if (isPauseRequested(download.id)) return

        // Final progress update
        dao.updateProgress(download.id, DownloadEntity.Status.DOWNLOADING, totalBytes, totalBytes)

        // Clean up progress files
        tempDir.listFiles()?.forEach { it.delete() }
        tempDir.delete()
        Log.d(TAG, "Direct parallel download complete: ${download.id}")
    }

    /**
     * Download HLS/m3u8 stream using IDM-style parallel segment downloads.
     * Downloads N segments concurrently to temp files, then concatenates in order.
     * Supports resume by checking which temp segment files already exist.
     */
    private suspend fun downloadHls(download: DownloadEntity): File {
        val file = File(download.filePath)
        file.parentFile?.mkdirs()
        val headers = parseHeaders(download.headersJson)

        // Fetch the playlist body once to detect live vs VOD
        val (playlistBody, mediaPlaylistUrl) = fetchHlsPlaylist(download.sourceUrl, headers)
        val isLive = !playlistBody.contains("#EXT-X-ENDLIST")
        if (isLive) {
            Log.d(TAG, "HLS LIVE detected for ${download.id} — switching to continuous live recording")
            // ⚠ 2026-08-20 : l'enregistrement direct reste en .ts, et c'est voulu. Il écrit
            //   au fil de l'eau, sans fin connue ; un MP4 ne peut pas être clôturé
            //   proprement si l'utilisateur coupe le courant, alors qu'un .ts tronqué
            //   reste lisible. On rectifie donc le nom, décidé en .mp4 à la mise en file.
            val fichierDirect = File(file.parentFile, file.nameWithoutExtension + ".ts")
            if (fichierDirect.absolutePath != file.absolutePath) {
                dao.updateFilePath(download.id, fichierDirect.absolutePath)
            }
            // Pass the body we just fetched to avoid an immediate re-fetch (which can
            // return a different segment window or fail).
            downloadHlsLive(download, mediaPlaylistUrl, headers, initialBody = playlistBody,
                sortie = fichierDirect)
            return fichierDirect
        }

        // VOD path: parse all segments and download in parallel as before
        val segBaseUrl = mediaPlaylistUrl.substringBeforeLast("/") + "/"
        val segments = parseSegmentPlaylist(playlistBody, segBaseUrl)
        if (segments.isEmpty()) throw Exception("No segments found in HLS playlist")

        Log.d(TAG, "HLS parallel download: ${segments.size} segments × $PARALLEL_CONNECTIONS connections for ${download.id}")

        // Temp directory for individual segment files
        val tempDir = File(file.parent, ".hls_${download.id.hashCode().toUInt()}")
        tempDir.mkdirs()

        val completedSegments = AtomicInteger(0)

        // Check which segments are already downloaded (resume support)
        for (i in segments.indices) {
            val segFile = File(tempDir, "seg_${String.format("%05d", i)}")
            if (segFile.exists() && segFile.length() > 0) {
                completedSegments.incrementAndGet()
            }
        }

        val alreadyDone = completedSegments.get()
        if (alreadyDone > 0) {
            Log.d(TAG, "HLS resume: $alreadyDone/${segments.size} segments already downloaded for ${download.id}")
            val progress = (alreadyDone * 100) / segments.size
            dao.updateProgress(download.id, DownloadEntity.Status.DOWNLOADING, alreadyDone.toLong(), segments.size.toLong())
            updateNotification("${download.title} ($alreadyDone/${segments.size})", progress)
        }

        // Build work queue of segments that still need downloading
        val workChannel = Channel<Pair<Int, String>>(Channel.UNLIMITED)
        for (i in segments.indices) {
            val segFile = File(tempDir, "seg_${String.format("%05d", i)}")
            if (!segFile.exists() || segFile.length() == 0L) {
                workChannel.send(i to segments[i])
            }
        }
        workChannel.close()

        // Launch PARALLEL_CONNECTIONS workers consuming from the channel
        coroutineScope {
            repeat(PARALLEL_CONNECTIONS) { workerIndex ->
                launch(Dispatchers.IO) {
                    for ((index, segUrl) in workChannel) {
                        if (isPauseRequested(download.id)) break

                        val segFile = File(tempDir, "seg_${String.format("%05d", index)}")
                        val request = Request.Builder()
                            .url(segUrl)
                            .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                            .apply { headers.forEach { (k, v) -> header(k, v) } }
                            .build()

                        // ⚠ downloadClient : le callTimeout de 30 s de sharedClient
                        //   coupait les segments de ~7 Mo en pleine réception.
                        val response = Extractor.downloadClient.newCall(request).execute()
                        try {
                            if (!response.isSuccessful) {
                                throw Exception("Segment $index: HTTP ${response.code}")
                            }

                            response.body?.byteStream()?.use { input ->
                                // Write to a .tmp file first, rename when complete (atomic)
                                val tmpFile = File(tempDir, "seg_${String.format("%05d", index)}.tmp")
                                FileOutputStream(tmpFile).use { output ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        if (isPauseRequested(download.id)) {
                                            tmpFile.delete() // partial segment, discard
                                            return@launch
                                        }
                                        output.write(buffer, 0, bytesRead)
                                    }
                                }
                                tmpFile.renameTo(segFile) // atomic completion marker
                            }
                        } finally {
                            response.close()
                        }

                        val done = completedSegments.incrementAndGet()
                        val progress = (done * 100) / segments.size
                        dao.updateProgress(download.id, DownloadEntity.Status.DOWNLOADING, done.toLong(), segments.size.toLong())
                        updateNotification("${download.title} ($done/${segments.size})", progress)
                    }
                }
            }
        }

        if (isPauseRequested(download.id)) return file

        // Verify all segments are present
        val missing = (0 until segments.size).filter { i ->
            val segFile = File(tempDir, "seg_${String.format("%05d", i)}")
            !segFile.exists() || segFile.length() == 0L
        }
        if (missing.isNotEmpty()) {
            throw Exception("Missing ${missing.size} segments after download: first=${missing.first()}")
        }

        // ── ASSEMBLAGE ────────────────────────────────────────────────────────────────
        //   2026-08-20 (user, après le refus « File type not allowed » de VOE) :
        //   on MUXE les segments dans un MP4 au lieu de les coller bout à bout en .ts.
        //   Voir le pavé `assemblerEnMp4`. Si le muxage échoue, on retombe sur la
        //   concaténation d'origine et le fichier reprend son extension .ts — mieux
        //   vaut un .ts à convertir à la main qu'aucun fichier du tout.
        val segFiles = segments.indices.map { i -> File(tempDir, "seg_${String.format("%05d", i)}") }
        var sortie = file

        // ══════════════════════════════════════════════════════════════════════════
        // ⚠ 2026-08-21 — ON COLLE D'ABORD, ON MUXE ENSUITE. NE PAS REVENIR EN ARRIÈRE.
        //
        //   Avant, on muxait SEGMENT PAR SEGMENT. Résultat, dans le journal de l'Oppo :
        //       « muxage MP4 impossible : IOException — Failed to instantiate extractor. »
        //   MediaExtractor n'arrivait pas à ouvrir un segment isolé : un morceau de flux
        //   MPEG-TS sans extension, et sans forcément de PAT/PMT en tête, n'est pas
        //   reconnu par le renifleur d'Android.
        //
        //   PREUVE que le contenu n'y est pour rien : le même flux, une fois collé,
        //   est lu SANS PROBLÈME par Android — MediaStore en a extrait
        //   `duration=1412371` et `mime_type=video/mp2ts` sur le fichier produit.
        //   Le conteneur, les codecs (H.264 High + AAC-LC) et la taille étaient donc
        //   tous hors de cause ; seule la découpe posait problème.
        //
        //   On concatène donc TOUJOURS dans un .ts temporaire, puis on muxe CE fichier
        //   unique et continu — celui-là, Android sait le lire. Bénéfice annexe : plus
        //   aucun recollage d'horloge entre segments, qui était la partie la plus
        //   fragile du code.
        // ══════════════════════════════════════════════════════════════════════════
        val brut = File(tempDir, "flux_complet.ts")
        withContext(Dispatchers.IO) {
            FileOutputStream(brut).use { output ->
                for (segFile in segFiles) {
                    if (!segFile.exists()) continue
                    segFile.inputStream().use { input -> input.copyTo(output, BUFFER_SIZE) }
                }
            }
        }

        val muxe = withContext(Dispatchers.IO) { assemblerEnMp4(listOf(brut), file) }
        if (!muxe) {
            sortie = File(file.parentFile, file.nameWithoutExtension + ".ts")
            Log.w(TAG, "HLS : muxage impossible → on garde le flux brut ${sortie.name}")
            withContext(Dispatchers.IO) {
                runCatching { file.delete() }
                // Simple déplacement : le fichier collé est déjà exactement ce qu'il faut.
                if (!brut.renameTo(sortie)) {
                    brut.inputStream().use { input ->
                        FileOutputStream(sortie).use { output -> input.copyTo(output, BUFFER_SIZE) }
                    }
                }
            }
            dao.updateFilePath(download.id, sortie.absolutePath)
        }

        // Clean up temp directory
        tempDir.listFiles()?.forEach { it.delete() }
        tempDir.delete()
        Log.d(TAG, "HLS parallel download complete: ${download.id} → ${sortie.name}")
        return sortie
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    // MUXAGE DES SEGMENTS HLS EN MP4 — 2026-08-20
    //   (user « je vais télécharger un anime déjà présent pour le réuploader et avoir
    //    des serveurs supplémentaires », puis choix « muxer pendant le téléchargement »)
    //
    // POURQUOI. VOE refuse les `.ts` : l'envoi monte entièrement puis se solde par
    //   « File type not allowed » — 1,3 Go de débit perdus. Or `.ts` était l'extension de
    //   TOUT téléchargement HLS, donc de tout ce qui vient de VOE lui-même, de Movix·Voe,
    //   et de la plupart des serveurs en m3u8.
    //
    // POURQUOI ICI ET PAS APRÈS COUP. Les segments sont déjà écrits séparément et
    //   n'étaient assemblés qu'à la toute fin. On remplace donc l'assemblage, sans rien
    //   ajouter : les segments sont lus une seule fois, comme avant. Un remux après coup
    //   aurait coûté une seconde passe complète sur 1,3 Go et le double d'espace disque.
    //   La reprise par segment, elle, reste intacte — elle se joue avant cette étape.
    //
    // CE QUE FAIT LE MUXAGE. Il ne décode ni ne ré-encode RIEN : il sort les échantillons
    //   vidéo et audio déjà compressés de leur conteneur MPEG-TS et les réécrit tels quels
    //   dans un conteneur MP4. Qualité rigoureusement identique, coût processeur nul.
    //
    // LES DEUX PIÈGES DES HORODATAGES, traités plus bas :
    //   · un flux MPEG-TS ne commence pas à zéro (l'horloge de départ est arbitraire, par
    //     exemple 10 s) — sans correction, le MP4 débuterait par 10 s de vide ;
    //   · d'un segment à l'autre l'horloge peut repartir en arrière — sans correction, le
    //     lecteur verrait le film revenir en arrière au milieu.
    //
    // FILET. Toute erreur (codec que MediaMuxer ne sait pas écrire, segment illisible…)
    //   renvoie `false` : l'appelant reprend alors la concaténation d'origine. On ne perd
    //   jamais le téléchargement.
    // ══════════════════════════════════════════════════════════════════════════════════

    /** Tampon d'échantillon : départ large, plafond de sécurité. Voir le pavé plus bas. */
    private val TAMPON_DEPART = 8 * 1024 * 1024
    private val TAMPON_MAX = 64 * 1024 * 1024

    /** @return true si le MP4 a été écrit, false s'il faut retomber sur le .ts brut. */
    private fun assemblerEnMp4(segments: List<File>, sortie: File): Boolean {
        if (segments.isEmpty()) return false
        var muxer: MediaMuxer? = null
        var demarre = false
        try {
            muxer = MediaMuxer(sortie.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // pistes du muxeur, indexées par type (« video » / « audio ») : les segments
            //   suivants n'ont aucune raison de présenter leurs pistes dans le même ordre.
            val pistes = HashMap<String, Int>()
            val finPar = HashMap<String, Long>()      // dernier horodatage écrit par piste
            var decalage = 0L                          // correction appliquée au segment courant
            var premierSegment = true
            // ⚠ 2026-08-21 — LE TAMPON ÉTAIT LA CAUSE DES .ts QUI RESSORTAIENT.
            //
            //   Symptôme : « yomi no tsugai - S01E19 » est sorti en .ts, donc refusé
            //   par VOE. Codecs pourtant irréprochables (mesuré à ffprobe sur le
            //   fichier lui-même) : H.264 High 1080p + AAC-LC stéréo, tout ce que
            //   MediaMuxer accepte. Ce n'était donc pas un problème de format.
            //
            //   CAUSE : `readSampleData` lève une exception si l'échantillon ne tient
            //   pas dans le tampon — et le catch global transformait ça en « muxage
            //   impossible », donc en repli .ts, SANS jamais dire pourquoi. Or
            //   l'en-tête x264 du fichier annonce `vbv_bufsize=16000`, soit
            //   16 000 kbit ≈ 1,91 Mio pour une seule image. Le tampon faisait
            //   2 Mio : la moindre image-clé un peu chargée le dépassait.
            //
            //   CORRECTIF : on démarre plus large ET on AGRANDIT à la volée au lieu
            //   d'abandonner. Un échantillon plus gros que prévu ne doit jamais
            //   coûter tout le muxage.
            var tampon = java.nio.ByteBuffer.allocate(TAMPON_DEPART)
            val info = MediaCodec.BufferInfo()

            for (segment in segments) {
                if (!segment.exists() || segment.length() == 0L) return false
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(segment.absolutePath)
                    // piste du segment -> piste du muxeur
                    val corr = HashMap<Int, String>()
                    for (i in 0 until ex.trackCount) {
                        val fmt = ex.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                        val type = mime.substringBefore('/')
                        if (type != "video" && type != "audio") continue
                        if (type in corr.values) continue      // une seule piste par type
                        if (!pistes.containsKey(type)) {
                            if (demarre) {
                                // Une piste apparaît en cours de route : MediaMuxer
                                //   n'accepte plus d'ajout après start(). On abandonne.
                                Log.w(TAG, "muxage : piste $type apparue en cours de flux")
                                return false
                            }
                            pistes[type] = muxer.addTrack(fmt)
                        }
                        corr[i] = type
                        ex.selectTrack(i)
                    }
                    if (pistes.isEmpty()) return false
                    if (!demarre) {
                        muxer.start()
                        demarre = true
                    }

                    // Premier horodatage du segment, tous types confondus.
                    var premierPts = Long.MAX_VALUE
                    run {
                        val t = ex.sampleTime
                        if (t >= 0) premierPts = t
                    }
                    if (premierPts == Long.MAX_VALUE) { ex.release(); continue }

                    val finGlobale = finPar.values.maxOrNull() ?: 0L
                    decalage = when {
                        // 1er segment : on ramène le début du film à zéro.
                        premierSegment -> -premierPts
                        // Horloge repartie en arrière : on recolle derrière ce qui est écrit.
                        premierPts + decalage < finGlobale -> finGlobale - premierPts
                        else -> decalage
                    }
                    premierSegment = false

                    while (true) {
                        // Lecture avec agrandissement du tampon si nécessaire.
                        var taille = -1
                        while (true) {
                            try {
                                taille = ex.readSampleData(tampon, 0)
                                break
                            } catch (e: Exception) {
                                if (tampon.capacity() >= TAMPON_MAX) throw e
                                val neuf = minOf(tampon.capacity() * 2, TAMPON_MAX)
                                Log.w(TAG, "muxage : échantillon trop grand pour " +
                                    "${tampon.capacity() / 1024} Ko → tampon porté à " +
                                    "${neuf / 1024} Ko")
                                tampon = java.nio.ByteBuffer.allocate(neuf)
                            }
                        }
                        if (taille < 0) break
                        val type = corr[ex.sampleTrackIndex]
                        val piste = type?.let { pistes[it] }
                        if (piste != null) {
                            info.offset = 0
                            info.size = taille
                            info.presentationTimeUs = ex.sampleTime + decalage
                            info.flags = ex.sampleFlags
                            if (info.presentationTimeUs >= 0) {
                                muxer.writeSampleData(piste, tampon, info)
                                val precedent = finPar[type] ?: 0L
                                if (info.presentationTimeUs > precedent) {
                                    finPar[type] = info.presentationTimeUs
                                }
                            }
                        }
                        ex.advance()
                    }
                } finally {
                    runCatching { ex.release() }
                }
            }
            if (!demarre) return false
            Log.d(TAG, "muxage MP4 : ${segments.size} segment(s) → ${sortie.name}")
            return true
        } catch (e: Exception) {
            // ⚠ Ce message est la SEULE trace du pourquoi. Sans lui, on ne voit qu'un
            //   fichier .ts inexplicable — c'est ce qui a coûté une matinée le 21/08.
            Log.w(TAG, "muxage MP4 impossible : ${e.javaClass.simpleName} — ${e.message}", e)
            return false
        } finally {
            runCatching { if (demarre) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /**
     * Live HLS recording: poll the playlist forever and append new segments to the
     * output file as they arrive. Stops when isPauseRequested(download.id) returns
     * true (user paused / cancelled). Hard cap at 4 hours.
     */
    /** Set of download IDs whose pause was actually a "stop" on a live recording.
     *  processDownload checks this to decide whether to publish to MediaStore or
     *  just leave the file in app-private storage. */
    private val liveStopRequests = mutableSetOf<String>()

    private suspend fun downloadHlsLive(
        download: DownloadEntity,
        mediaPlaylistUrl: String,
        headers: Map<String, String>,
        initialBody: String? = null,
        // 2026-08-20 : fichier de sortie explicite. `download.filePath` porte désormais
        //   un nom en .mp4 (décidé à la mise en file pour les flux HLS), or un
        //   enregistrement direct s'écrit en .ts — voir le commentaire dans downloadHls.
        sortie: File? = null,
    ) {
        val file = sortie ?: File(download.filePath)
        val outputStream = FileOutputStream(file)
        val seenSegments = mutableSetOf<String>()
        var totalSegments = 0
        val recordingStart = System.currentTimeMillis()
        val MAX_RECORDING_MS = 4L * 60L * 60L * 1000L // 4h cap
        val baseUrl = mediaPlaylistUrl.substringBeforeLast("/") + "/"

        // Session cookies (captured from playlist Set-Cookie headers). Some Xtream-style
        // servers gate /hlsr/<token>.ts segments behind a session cookie set during
        // the initial m3u8 fetch — without forwarding it we get HTTP 401 on every seg.
        val sessionCookies = mutableMapOf<String, String>()

        // Helper to capture cookies from a response
        fun captureCookies(resp: okhttp3.Response) {
            for (header in resp.headers("Set-Cookie")) {
                val pair = header.substringBefore(";").trim()
                val eq = pair.indexOf('=')
                if (eq > 0) sessionCookies[pair.substring(0, eq)] = pair.substring(eq + 1)
            }
        }

        // Refetch the playlist with cookie capture (overrides initialBody if needed)
        suspend fun fetchPlaylistWithCookies(): String = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(mediaPlaylistUrl)
                .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                .apply {
                    headers.forEach { (k, v) -> header(k, v) }
                    if (sessionCookies.isNotEmpty()) {
                        header("Cookie", sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    }
                }
                .build()
            Extractor.sharedClient.newCall(req).execute().use { resp ->
                captureCookies(resp)
                resp.body?.string() ?: ""
            }
        }

        try {
            // Always fetch with cookie capture (initialBody from caller didn't capture cookies).
            // CRITICAL: this fetch must happen RIGHT BEFORE the segment fetches, on the
            // same OkHttp client (connection pool reuses TCP socket). Some IPTV servers
            // tie the segment token to the TCP connection that fetched the playlist.
            val initBody = fetchPlaylistWithCookies().ifBlank { initialBody ?: "" }
            val targetDurationSec = Regex("#EXT-X-TARGETDURATION:(\\d+)")
                .find(initBody)?.groupValues?.get(1)?.toLongOrNull() ?: 6L
            val initSegs = parseSegmentPlaylist(initBody, baseUrl)
            Log.d(TAG, "LIVE init: ${initSegs.size} segments parsed from ${initBody.length}B body, ${sessionCookies.size} cookies captured")
            if (initSegs.isEmpty()) {
                Log.w(TAG, "LIVE init body sample: ${initBody.take(300)}")
            }
            // Build effective headers (with session cookies + Referer)
            fun effectiveHeaders(): Map<String, String> {
                val h = headers.toMutableMap()
                if (sessionCookies.isNotEmpty()) {
                    h["Cookie"] = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                }
                h["Referer"] = mediaPlaylistUrl
                return h
            }
            for (seg in initSegs) {
                if (isPauseRequested(download.id)) break
                if (seenSegments.add(seg)) {
                    try {
                        appendOneSegment(seg, effectiveHeaders(), outputStream, download.id)
                        totalSegments++
                    } catch (e: Exception) {
                        Log.w(TAG, "LIVE init segment failed: ${e.message} url=${seg.take(120)}")
                    }
                }
            }
            Log.d(TAG, "LIVE recording started: $totalSegments initial segments downloaded, target=${targetDurationSec}s")

            // Main loop
            while (!isPauseRequested(download.id)) {
                if (System.currentTimeMillis() - recordingStart > MAX_RECORDING_MS) {
                    Log.w(TAG, "LIVE recording: hit 4h cap — stopping")
                    break
                }
                val waitMs = (targetDurationSec * 1000L).coerceAtLeast(4000L)
                delay(waitMs)
                if (isPauseRequested(download.id)) break

                val refreshBody = try {
                    fetchPlaylistWithCookies()
                } catch (e: Exception) {
                    Log.w(TAG, "LIVE refresh failed: ${e.message}, retrying")
                    continue
                }
                val newList = parseSegmentPlaylist(refreshBody, baseUrl)
                val newSegs = newList.filter { seenSegments.add(it) }
                if (newSegs.isEmpty()) continue

                for (segUrl in newSegs) {
                    if (isPauseRequested(download.id)) break
                    try {
                        appendOneSegment(segUrl, effectiveHeaders(), outputStream, download.id)
                        totalSegments++
                    } catch (e: Exception) {
                        Log.w(TAG, "LIVE segment failed: ${e.message}")
                    }
                }

                val elapsedSec = (System.currentTimeMillis() - recordingStart) / 1000
                val mins = elapsedSec / 60; val secs = elapsedSec % 60
                dao.updateProgress(download.id, DownloadEntity.Status.DOWNLOADING, totalSegments.toLong(), -1L)
                updateNotification("${download.title} — Live ${mins}m${secs}s ($totalSegments segments)", -1)
            }

            Log.d(TAG, "LIVE recording stopped: $totalSegments segments captured for ${download.id}")
            // Mark this as a "stop" (not a pause). processDownload will treat the
            // pause as completion: publish file to MediaStore + mark COMPLETED.
            // Also clear the pause flag so processDownload's "if isPauseRequested"
            // branch doesn't fire.
            if (totalSegments > 0) {
                synchronized(liveStopRequests) { liveStopRequests.add(download.id) }
                synchronized(pauseRequests) { pauseRequests.remove(download.id) }
            }
        } finally {
            outputStream.close()
        }
    }

    /** Append one HLS segment's bytes to outputStream. */
    private suspend fun appendOneSegment(
        segmentUrl: String,
        headers: Map<String, String>,
        outputStream: FileOutputStream,
        downloadId: String,
    ) = withContext(Dispatchers.IO) {
        // Respect the User-Agent provided by the Video.headers (e.g. Vegeta uses
        // VLC/3.0.18 — the IPTV server gates /hlsr/<token>.ts by UA pattern).
        // Only fall back to ExoPlayer-style UA if caller didn't supply one.
        val callerUa = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val effectiveUa = callerUa ?: "ExoPlayer/2.19.1 (Linux; Android 14)"
        val req = Request.Builder().url(segmentUrl)
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
                header("User-Agent", effectiveUa)
                if (headers.keys.none { it.equals("Accept", true) }) header("Accept", "*/*")
                header("Connection", "keep-alive")
            }
            .build()
        Extractor.sharedClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Diagnostic: log response headers + first 200 chars of body on 4xx
                if (resp.code == 401 || resp.code == 403) {
                    val headerSummary = resp.headers.toMultimap().entries
                        .filter { it.key.lowercase() in setOf("www-authenticate", "set-cookie", "server", "content-type", "x-error") }
                        .joinToString(", ") { "${it.key}=${it.value.joinToString(";")}" }
                    val body = try { resp.body?.string()?.take(300) ?: "" } catch (_: Exception) { "" }
                    Log.w(TAG, "Segment ${resp.code} headers: $headerSummary | UA used: $effectiveUa | body: ${body.replace("\n", " ")}")
                }
                throw Exception("Segment HTTP ${resp.code}")
            }
            resp.body?.byteStream()?.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isPauseRequested(downloadId)) return@withContext
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    /**
     * Fetch the HLS playlist. If it's a master playlist, resolve to the highest-quality
     * media playlist. Returns (mediaPlaylistBody, mediaPlaylistUrl).
     */
    private suspend fun fetchHlsPlaylist(sourceUrl: String, headers: Map<String, String>): Pair<String, String> {
        val playlistResponse = withContext(Dispatchers.IO) {
            Extractor.sharedClient.newCall(
                Request.Builder()
                    .url(sourceUrl)
                    .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .build()
            ).execute()
        }
        val playlistBody = playlistResponse.body?.string() ?: throw Exception("Empty HLS playlist")
        playlistResponse.close()

        return if (playlistBody.contains("#EXT-X-STREAM-INF")) {
            val baseUrl = sourceUrl.substringBeforeLast("/") + "/"
            val bestStreamUrl = parseMasterPlaylist(playlistBody, baseUrl)
                ?: throw Exception("No streams found in master playlist")
            val segResponse = withContext(Dispatchers.IO) {
                Extractor.sharedClient.newCall(
                    Request.Builder().url(bestStreamUrl)
                        .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                        .apply { headers.forEach { (k, v) -> header(k, v) } }
                        .build()
                ).execute()
            }
            val segBody = segResponse.body?.string() ?: throw Exception("Empty media playlist")
            segResponse.close()
            segBody to bestStreamUrl
        } else {
            playlistBody to sourceUrl
        }
    }

    /**
     * Fetch and parse HLS playlist, resolving master playlists to segment lists.
     */
    private suspend fun fetchHlsSegments(sourceUrl: String, headers: Map<String, String>): List<String> {
        val playlistResponse = withContext(Dispatchers.IO) {
            Extractor.sharedClient.newCall(
                Request.Builder()
                    .url(sourceUrl)
                    .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .build()
            ).execute()
        }
        val playlistBody = playlistResponse.body?.string() ?: throw Exception("Empty HLS playlist")
        playlistResponse.close()

        val baseUrl = sourceUrl.substringBeforeLast("/") + "/"

        return if (playlistBody.contains("#EXT-X-STREAM-INF")) {
            // Master playlist — pick highest quality, then parse segment playlist
            val bestStreamUrl = parseMasterPlaylist(playlistBody, baseUrl)
                ?: throw Exception("No streams found in master playlist")

            val segResponse = withContext(Dispatchers.IO) {
                Extractor.sharedClient.newCall(
                    Request.Builder().url(bestStreamUrl)
                        .header("User-Agent", Extractor.DEFAULT_USER_AGENT).build()
                ).execute()
            }
            val segBody = segResponse.body?.string() ?: throw Exception("Empty segment playlist")
            segResponse.close()

            val segBaseUrl = bestStreamUrl.substringBeforeLast("/") + "/"
            parseSegmentPlaylist(segBody, segBaseUrl)
        } else {
            parseSegmentPlaylist(playlistBody, baseUrl)
        }
    }

    private fun parseMasterPlaylist(content: String, baseUrl: String): String? {
        var bestBandwidth = 0L
        var bestUrl: String? = null

        val lines = content.lines()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                val bandwidthMatch = Regex("BANDWIDTH=(\\d+)").find(lines[i])
                val bandwidth = bandwidthMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
                if (bandwidth >= bestBandwidth && i + 1 < lines.size) {
                    bestBandwidth = bandwidth
                    val url = lines[i + 1].trim()
                    bestUrl = if (url.startsWith("http")) url else baseUrl + url
                }
            }
        }
        return bestUrl
    }

    private fun parseSegmentPlaylist(content: String, baseUrl: String): List<String> {
        // Resolve relative URLs properly: handle absolute paths (/foo/bar), protocol-
        // relative URLs (//host/path), and relative paths (foo/bar.ts).
        // baseUrl is the directory containing the playlist (e.g. http://host:port/live/path/).
        val origin = try {
            val u = java.net.URL(baseUrl)
            "${u.protocol}://${u.authority}"  // e.g. "http://81.31.194.66:8080"
        } catch (_: Exception) { "" }
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { url ->
                when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.startsWith("//") -> "http:$url"
                    url.startsWith("/") -> origin + url
                    else -> baseUrl + url
                }
            }
    }

    private suspend fun copyStream(
        input: InputStream,
        output: FileOutputStream,
        downloadId: String,
        startBytes: Long,
        totalBytes: Long,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var downloadedBytes = startBytes
        var lastNotifyTime = 0L
        var bytesRead: Int

        while (input.read(buffer).also { bytesRead = it } != -1) {
            if (isPauseRequested(downloadId)) return

            output.write(buffer, 0, bytesRead)
            downloadedBytes += bytesRead

            val now = System.currentTimeMillis()
            if (now - lastNotifyTime > 500) {
                lastNotifyTime = now
                val progress = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                dao.updateProgress(downloadId, DownloadEntity.Status.DOWNLOADING, downloadedBytes, totalBytes)
                updateNotification("Téléchargement…", progress)
            }
        }

        // Final progress update
        dao.updateProgress(downloadId, DownloadEntity.Status.DOWNLOADING, downloadedBytes, totalBytes)
    }

    // ── Helpers ──

    /**
     * Parse download headers JSON into a Map.
     */
    private fun parseHeaders(headersJson: String?): Map<String, String> {
        if (headersJson == null) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(headersJson, Map::class.java) as? Map<String, String> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun isPauseRequested(id: String): Boolean {
        return synchronized(pauseRequests) { pauseRequests.contains(id) }
    }

    // ── Notifications ──

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Téléchargements",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifications de progression des téléchargements"
            setShowBadge(false)
        }
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_downloads)
            .setContentTitle("StreamFlix")
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    private fun clearNotification() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }

    private fun showCompletedNotification(title: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_downloads)
            .setContentTitle("Téléchargement terminé")
            .setContentText(title)
            .setAutoCancel(true)
            .setSilent(false)
            .build()
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.notify(COMPLETED_NOTIFICATION_ID + title.hashCode(), notification)
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun getDownloadDir(): File {
        // Temporary buffer dir for in-progress downloads. Once the download is complete,
        // bytes are moved into MediaStore (/Movies/StreamFlix/) where they become visible
        // to other apps. The app-private dir keeps partial files invisible while downloading.
        val dir = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "StreamFlix"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace("[^a-zA-Z0-9._\\-àâéèêëïîôùûüçÀÂÉÈÊËÏÎÔÙÛÜÇ ]".toRegex(), "_")
            .replace("\\s+".toRegex(), "_")
            .take(200)
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    // NOMMAGE DES FICHIERS TÉLÉCHARGÉS — 2026-08-20
    //   (user « que l'épisode porte au moins le nom de la série suivi de la saison et de
    //    l'épisode, pour qu'on puisse plus facilement le repartager de notre côté »)
    //
    // Le nom produit doit être relu par NOTRE PROPRE bibliothèque après remise en ligne
    //   sur VOE, sinon le fichier repartagé ne sera jamais proposé comme serveur. Trois
    //   contraintes viennent directement de VoeLibrary et ne sont pas négociables :
    //
    //   1. `RE_ID_TETE = ^(\d{2,8})\s*-\s*` — l'identifiant TMDB n'est reconnu qu'en TÊTE,
    //      suivi d'un tiret entouré d'ESPACES. D'où `nomPropre` ci-dessous : l'ancien
    //      `sanitizeFileName` remplace les espaces par des underscores, ce qui rendrait
    //      l'identifiant illisible (« 550_-_Fight_Club » ne correspond pas au motif).
    //
    //   2. ⚠ L'identifiant en tête est TOUJOURS interprété comme un identifiant de FILM
    //      (les fiches sont résolues via /3/movie/{id}). Poser l'identifiant d'une SÉRIE
    //      en tête ferait afficher l'affiche d'un film sans rapport. C'est le piège déjà
    //      documenté dans VoeLibrary : « l'identifiant 550 désigne Fight Club côté film ET
    //      une série sans aucun rapport côté série ». Pour les épisodes, l'identifiant va
    //      donc en FIN, sous la forme `[tv-253]` — hors de portée de RE_ID_TETE.
    //      C'est le « préfixe distinct (tv:<id>) » que le commentaire de VoeLibrary
    //      appelait de ses vœux ; `:` étant interdit dans un nom de fichier, on écrit `tv-`.
    //
    //   3. Le rattachement d'un épisode exige DEUX choses (VoeLibrary.serveursPour) :
    //      le motif `s0*<saison>[ ._-]?e0*<épisode>` ET le nom de la série en MOT DE TÊTE
    //      (`motTeteCouvert`). D'où « Star Trek - S01E03 - … » : série d'abord, SxxExx
    //      ensuite, avec des numéros sur deux chiffres.
    //
    // Exemples produits :
    //   film    « 550 - Fight Club (1999).mp4 »
    //   épisode « Star Trek - S01E03 - Where No Man Has Gone Before [tv-253].mp4 »
    // ══════════════════════════════════════════════════════════════════════════════════

    /** Nettoyage minimal : on n'ôte que ce qu'Android et FAT32 refusent vraiment.
     *  Espaces, tirets, parenthèses et crochets sont PRÉSERVÉS — ils portent du sens
     *  pour le rapprochement (voir le pavé ci-dessus). */
    private fun nomPropre(name: String): String =
        name.replace("[\\\\/:*?\"<>|\\r\\n\\t]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .take(180)

    /** Premier chemin libre pour `nom` dans `dir` : « … (2).mp4 », « … (3).mp4 »…
     *  Un chemin est considéré pris s'il existe sur le disque OU s'il est déjà réservé
     *  par un autre téléchargement en base. Voir le commentaire dans `enqueue`. */
    private suspend fun cheminLibre(dir: File, nom: String, idCourant: String): File {
        val base = nom.substringBeforeLast('.')
        val ext = nom.substringAfterLast('.', "")
        val reserves = runCatching { dao.getAllSnapshot() }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.id != idCourant }
            .map { it.filePath.substringAfterLast('/') }
            .filter { it.isNotBlank() }
            .toHashSet()
        var n = 1
        while (n <= 50) {
            val candidat = File(
                dir,
                if (n == 1) nom
                else if (ext.isBlank()) "$base ($n)" else "$base ($n).$ext",
            )
            if (!candidat.exists() && candidat.name !in reserves) return candidat
            n++
        }
        return File(dir, "$base ${System.currentTimeMillis()}.$ext")
    }

    /** Numéro sur deux chiffres : 3 → « 03 ». Au-delà de 99 on laisse tel quel. */
    private fun deuxChiffres(n: Int): String = if (n in 0..9) "0$n" else "$n"

    // ── ANIME : LA SAISON N'EST PAS OÙ ON CROIT ───────────────────────────────────────
    // ⚠ 2026-08-20 (user « il faudrait que le provider anime comme Anime Sama puisse aussi
    //   accéder à ce dossier… c'est pour ça qu'il faut que le nom soit parfait ») —
    //   NE PAS REMPLACER PAR `videoType.season.number`.
    //   Sur AnimeSama, une œuvre disponible en VF ET en VOSTFR expose les DEUX LANGUES sous
    //   forme de « saisons » : `season.number` vaut 1 ou 2 selon la langue, jamais la vraie
    //   saison. Celle-ci n'existe que dans l'identifiant d'épisode, qui est un chemin :
    //     « mushoku-tensei/saison4/vostfr/2 »
    //   Nommer d'après `season.number` produirait « S01E02 » pour un épisode de la saison 4,
    //   et le fichier remis en ligne ne serait JAMAIS rattaché : BackupRegistry, lui, cherche
    //   la vraie saison (il fait déjà cette extraction dans `keyOf`, ligne « saison(\d+) »).
    //   On reproduit donc exactement sa règle, pour que les deux bouts de la chaîne — le nom
    //   écrit au téléchargement et la recherche faite à la lecture — parlent de la même saison.
    private fun saisonReelle(ep: Video.Type.Episode): Int =
        Regex("(?i)saison\\s*(\\d+)").find(ep.id)?.groupValues?.get(1)?.toIntOrNull()
            ?: ep.season.number.let { if (it <= 0) 1 else it }

    /** « VF » / « VOSTFR » quand l'identifiant du provider le porte (cas AnimeSama).
     *  Sert à distinguer les deux versions d'un même épisode, qui portent sinon
     *  rigoureusement le même nom. */
    private fun langueDansId(id: String): String? = when {
        Regex("(?i)(^|[/_-])vostfr([/_-]|$)").containsMatchIn(id) -> "VOSTFR"
        Regex("(?i)(^|[/_-])vf([/_-]|$)").containsMatchIn(id) -> "VF"
        else -> null
    }

    /** L'identifiant n'est utilisable que s'il est purement numérique : selon le
     *  fournisseur, `videoType.id` peut être un slug (« cs::m::87203… ») qui n'a
     *  aucun sens pour TMDB et polluerait le nom. */
    private fun idTmdbOuNull(id: String?): String? =
        id?.takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isDigit) }

    /**
     * Guess a sensible MIME type for the downloaded file. We use the file extension first
     * (since enqueue() sets it explicitly to "ts" for HLS or "mp4" for direct), and fall
     * back to the original mimeType reported by the provider when possible.
     */
    private fun guessMimeType(file: File, isHls: Boolean): String {
        return when (file.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "ts" -> "video/mp2t" // HLS-concatenated transport stream
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "m4v" -> "video/x-m4v"
            else -> if (isHls) "video/mp2t" else "video/mp4"
        }
    }
}
