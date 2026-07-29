package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 2026-07-25 (demande user : « récupérer les musiques et vidéos enregistrées sur le téléphone,
 * jouables dans l'app et mises en favori ») — accès à la MÉDIATHÈQUE LOCALE du téléphone.
 *
 * On passe par MediaStore : pas besoin d'explorateur de fichiers ni d'accès disque brut, et ça
 * couvre Téléchargements, Musique, Films, WhatsApp, etc. Les URI `content://` renvoyées sont
 * jouables directement par ExoPlayer ET stockables en favori (MusicFavoritesStore garde une URL
 * + un titre : une URI locale s'y range comme une URL distante).
 */
object LocalMediaStore {

    data class LocalItem(
        val uri: String,
        val title: String,
        val subtitle: String,
        val durationMs: Long,
        /** Dossier de rangement (ex. « DCIM/Camera », « Download ») pour un classement lisible.
         *  Pour l'AUDIO = artiste (compat Android Auto) ; pour la VIDÉO = chemin relatif. */
        val folder: String = "",
        /** Pochette de l'album (audio) — affichée sur la carte de lecture d'Android Auto. */
        val artUri: String? = null,
        /** Dossier RÉEL (RELATIVE_PATH, ex. « Music/Rock/ ») — pour choisir un dossier précis. */
        val dir: String = "",
    )

    /** Permissions à DEMANDER selon la version d'Android.
     *  2026-07-27 (bug testeur TV : la musique s'autorise mais pas la vidéo) : sur certaines box
     *  Android TV, une demande de READ_MEDIA_VIDEO SEULE n'affiche aucun dialogue (alors que
     *  READ_MEDIA_AUDIO oui). → on demande TOUJOURS les DEUX ensemble : ainsi, quand l'utilisateur
     *  autorise (ce qui marche via l'audio), la vidéo est accordée du même coup. Le [audio] n'importe
     *  plus pour la demande ; il ne sert qu'à la vérification ciblée (hasPermission). */
    fun requiredPermissions(audio: Boolean): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            // 2026-07-28 : la VIDÉO appartient au groupe VISUEL (« Photos et vidéos ») accordé
            //   d'un bloc avec les IMAGES — distinct du groupe « Musique et audio ». Sur box TV,
            //   demander VIDEO seul ne déclenche aucun dialogue ; il faut demander VIDEO+IMAGES
            //   ensemble pour que la box propose « Autoriser Photos et vidéos ».
            if (audio) arrayOf("android.permission.READ_MEDIA_AUDIO")
            else arrayOf("android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_IMAGES")
        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** « Accès à tous les fichiers » (MANAGE_EXTERNAL_STORAGE) — LA méthode des explorateurs de
     *  fichiers pour lire une clé USB / carte SD sur box TV (Android 11+). Sur < Android 11 il n'y
     *  a pas de scoped storage strict → considéré comme accordé (on retombe sur READ_EXTERNAL_STORAGE). */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true

    /** Vérifie UNIQUEMENT la permission réellement nécessaire (audio→AUDIO, vidéo→VIDEO), pour ne
     *  pas boucler si une seule des deux est accordée. */
    fun hasPermission(context: Context, audio: Boolean): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (audio) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_MEDIA_VIDEO"
        } else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Permission IMAGES (fonds d'écran perso) — même groupe visuel que la vidéo. */
    fun hasImagePermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            "android.permission.READ_MEDIA_IMAGES"
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * 2026-07-25 (user : « une clé USB sur la TV, ou dans le système ») : on interroge TOUS les
     * volumes de stockage indexés par MediaStore (interne + carte SD + clé USB), pas seulement le
     * volume principal. Sur une box Android TV, une clé USB montée apparaît ainsi dans la liste.
     */
    private fun audioUris(context: Context): List<Uri> = volumeUris(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.getContentUri(it)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private fun videoUris(context: Context): List<Uri> = volumeUris(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(it)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    private fun imageUris(context: Context): List<Uri> = volumeUris(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(it)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun volumeUris(context: Context, uriFor: (String) -> Uri): List<Uri> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).map { uriFor(it) }
        } else {
            listOf(uriFor(MediaStore.VOLUME_EXTERNAL))
        }
    } catch (_: Exception) {
        listOf(uriFor(MediaStore.VOLUME_EXTERNAL))
    }

    /** Musiques locales (interne + carte SD + clé USB), triées par titre. */
    fun listAudio(context: Context, query: String? = null, limit: Int = 500): List<LocalItem> =
        audioUris(context).flatMap { list(context, it, audio = true, query = query, limit = limit) }
            .distinctBy { it.uri }.take(limit)

    /** Vidéos locales : MediaStore (interne/SD/USB) + éventuel dossier SAF choisi par l'utilisateur
     *  (clé USB sur box TV, SANS permission). Les plus récentes d'abord. */
    fun listVideo(context: Context, query: String? = null, limit: Int = 500): List<LocalItem> {
        val fromFolder = try { listVideoFromFolder(context, query) } catch (_: Throwable) { emptyList() }
        // 2026-07-29 (user : « il ne détecte que 2 dossiers alors que j'en ai plein ») : Android
        //   n'indexe dans MediaStore QUE les fichiers scannés (Download, Camera…) — les vidéos
        //   copiées à la main dans d'autres dossiers restent invisibles. Avec « Accès à tous les
        //   fichiers », on scanne le DISQUE en entier (interne + amovible) → on trouve TOUT, indexé
        //   ou non ; on n'utilise alors PAS MediaStore (le scan disque est complet → zéro doublon).
        //   Sans cette permission : MediaStore + scan des volumes amovibles (clé USB, box TV).
        val items = if (hasAllFilesAccess()) {
            try { listVideoFromFileSystem(context, query) } catch (_: Throwable) { emptyList() }
        } else {
            val fromStore = try {
                videoUris(context).flatMap { list(context, it, audio = false, query = query, limit = limit) }
            } catch (_: Throwable) { emptyList() }
            val fromFsRemovable = try { listVideoFromFileSystem(context, query) } catch (_: Throwable) { emptyList() }
            fromStore + fromFsRemovable
        }
        return (items + fromFolder).distinctBy { it.uri }.take(limit)
    }

    // ── IMAGES locales (fonds d'écran perso) — MÊME MÉTHODE QUE LES VIDÉOS ───────────────────
    //   2026-07-29 (bug testeur MiBox : « ajouter ses propres photos » ne lit pas la clé USB) :
    //   le picker système (SAF/DocumentsUI) est absent sur les box → on liste les images comme les
    //   vidéos : MediaStore TOUS volumes (interne/SD/USB) + scan direct des volumes amovibles.
    fun listImages(context: Context, query: String? = null, limit: Int = 800): List<LocalItem> {
        val fromStore = try {
            imageUris(context).flatMap { listImagesInVolume(context, it, query, limit) }
        } catch (_: Throwable) { emptyList() }
        val fromFs = try { listImageFromFileSystem(context, query) } catch (_: Throwable) { emptyList() }
        return (fromStore + fromFs).distinctBy { it.uri }.take(limit)
    }

    private val IMAGE_EXT = Regex("\\.(jpe?g|png|webp|bmp|gif|heic|heif)$", RegexOption.IGNORE_CASE)

    private fun listImagesInVolume(context: Context, collection: Uri, query: String?, limit: Int): List<LocalItem> {
        val out = ArrayList<LocalItem>()
        val hasRel = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        ) + (if (hasRel) arrayOf(MediaStore.MediaColumns.RELATIVE_PATH) else emptyArray())
        val selection = if (query.isNullOrBlank()) null else "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = if (query.isNullOrBlank()) null else arrayOf("%${query.trim()}%")
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                collection, projection, selection, args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )
            cursor ?: return out
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext() && out.size < limit) {
                val id = cursor.getLong(idCol)
                val uri = android.content.ContentUris.withAppendedId(collection, id).toString()
                val display = cursor.getString(nameCol) ?: continue
                val relPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                out.add(
                    LocalItem(
                        uri = uri,
                        title = display.substringBeforeLast('.'),
                        subtitle = buildString {
                            val f = (relPath ?: "").trim('/')
                            if (f.isNotEmpty()) append(f)
                            if (size > 0) { if (isNotEmpty()) append(" • "); append("${size / 1024} Ko") }
                        },
                        durationMs = 0L,
                        folder = (relPath ?: "").trim('/').ifBlank { "Images" },
                        dir = (relPath ?: "").trim('/').ifBlank { "Images" },
                    ),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "lecture images KO: ${e.message}")
        } finally {
            runCatching { cursor?.close() }
        }
        return out
    }

    private fun listImageFromFileSystem(context: Context, query: String?): List<LocalItem> {
        val q = query?.trim()?.lowercase()
        val out = ArrayList<LocalItem>()
        val seen = HashSet<String>()
        fun walk(dir: File, depth: Int) {
            if (depth > 6 || out.size >= 1200) return
            val files = try { dir.listFiles() } catch (_: Throwable) { null } ?: return
            for (f in files) {
                if (out.size >= 1200) return
                try {
                    if (f.isDirectory) {
                        val n = f.name
                        if (n == "Android" || n.startsWith(".")) continue
                        walk(f, depth + 1); continue
                    }
                    val name = f.name
                    if (!IMAGE_EXT.containsMatchIn(name)) continue
                    if (!q.isNullOrEmpty() && !name.lowercase().contains(q)) continue
                    val path = f.absolutePath
                    if (!seen.add(path)) continue
                    out.add(
                        LocalItem(
                            uri = Uri.fromFile(f).toString(),
                            title = name.substringBeforeLast('.'),
                            subtitle = f.parentFile?.name ?: "USB",
                            durationMs = 0L,
                            folder = f.parentFile?.name ?: "USB",
                        ),
                    )
                } catch (_: Throwable) {}
            }
        }
        for (root in removableRoots(context)) walk(root, 0)
        return out
    }

    // ── Scan direct du système de fichiers (volumes amovibles) ─────────────────────────────
    /** Racines des volumes AMOVIBLES (USB/SD) — on EXCLUT le stockage interne (déjà couvert par
     *  MediaStore) pour éviter les doublons. Découverte robuste via les dossiers app-spécifiques
     *  (chaque volume monté en a un) + points de montage classiques des box TV. */
    private fun removableRoots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()
        // 0) Stockage INTERNE (/storage/emulated/0) UNIQUEMENT si « accès à tous les fichiers » est
        //    accordé (sinon scoped storage bloque la lecture des fichiers non-app). Permet de trouver
        //    les vidéos qu'Android n'a PAS indexées dans MediaStore (fichiers copiés sans scan média).
        if (hasAllFilesAccess()) {
            try { Environment.getExternalStorageDirectory()?.let { if (it.isDirectory) roots.add(it) } } catch (_: Throwable) {}
        }
        // 1) Volumes vus par le système : getExternalFilesDirs[0] = interne (ignoré), les suivants
        //    = carte SD / clé USB. On remonte de .../Android/data/<pkg>/files à la racine du volume.
        try {
            val dirs = context.getExternalFilesDirs(null)
            dirs.forEachIndexed { i, dir ->
                if (i == 0 || dir == null) return@forEachIndexed
                val path = dir.absolutePath
                val idx = path.indexOf("/Android/")
                if (idx > 0) {
                    val r = File(path.substring(0, idx))
                    if (r.isDirectory) roots.add(r)
                }
            }
        } catch (_: Throwable) {}
        // 2) Points de montage classiques (box TV) : chaque sous-dossier = un volume monté.
        for (mp in listOf("/storage", "/mnt/media_rw", "/mnt/usb", "/mnt/usbotg", "/mnt/external_sd")) {
            try {
                File(mp).listFiles()?.forEach { child ->
                    val n = child.name.lowercase()
                    // on écarte le stockage émulé/interne et self/primary
                    if (child.isDirectory && n != "emulated" && n != "self" && n != "primary") {
                        roots.add(child)
                    }
                }
            } catch (_: Throwable) {}
        }
        return roots.filter { try { it.canRead() && it.listFiles() != null } catch (_: Throwable) { false } }
    }

    private fun listVideoFromFileSystem(context: Context, query: String?): List<LocalItem> {
        val q = query?.trim()?.lowercase()
        val out = ArrayList<LocalItem>()
        val seen = HashSet<String>()
        fun walk(dir: File, depth: Int) {
            if (depth > 6 || out.size >= 800) return
            val files = try { dir.listFiles() } catch (_: Throwable) { null } ?: return
            for (f in files) {
                if (out.size >= 800) return
                try {
                    if (f.isDirectory) {
                        val n = f.name
                        if (n == "Android" || n.startsWith(".")) continue // données d'app + caches
                        walk(f, depth + 1); continue
                    }
                    val name = f.name
                    if (!VIDEO_EXT.containsMatchIn(name)) continue
                    if (!q.isNullOrEmpty() && !name.lowercase().contains(q)) continue
                    val path = f.absolutePath
                    if (!seen.add(path)) continue
                    out.add(LocalItem(
                        uri = Uri.fromFile(f).toString(),
                        title = name.substringBeforeLast('.'),
                        subtitle = f.parentFile?.name ?: "USB",
                        durationMs = 0L,
                        folder = f.parentFile?.name ?: "USB",
                        artUri = null,
                    ))
                } catch (_: Throwable) {}
            }
        }
        for (root in removableRoots(context)) walk(root, 0)
        return out
    }

    // ── SAF (Storage Access Framework) : dossier vidéo choisi par l'utilisateur (clé USB) ──
    //   2026-07-28 : sur certaines box Android TV la permission READ_MEDIA_VIDEO ne s'accorde pas
    //   (seul « photos/médias + stockage » est proposé). SAF contourne ça : l'user pointe son dossier
    //   une fois, on garde l'accès (persistable), aucune permission Android nécessaire.
    private fun folderPrefs(context: Context) =
        context.getSharedPreferences("local_video_folder", Context.MODE_PRIVATE)

    fun setVideoFolder(context: Context, treeUri: Uri) {
        folderPrefs(context).edit().putString("tree_uri", treeUri.toString()).apply()
    }

    fun getVideoFolder(context: Context): Uri? =
        folderPrefs(context).getString("tree_uri", null)?.let { try { Uri.parse(it) } catch (_: Exception) { null } }

    fun hasVideoFolder(context: Context): Boolean {
        val uri = getVideoFolder(context) ?: return false
        return try {
            val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
            doc != null && doc.canRead()
        } catch (_: Throwable) { false }
    }

    private val VIDEO_EXT = Regex("\\.(mp4|mkv|avi|mov|webm|m4v|flv|wmv|mpg|mpeg|3gp|ts|m2ts)$", RegexOption.IGNORE_CASE)

    private fun listVideoFromFolder(context: Context, query: String?): List<LocalItem> {
        val uri = getVideoFolder(context) ?: return emptyList()
        val root = try { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri) } catch (_: Throwable) { null }
            ?: return emptyList()
        val q = query?.trim()?.lowercase()
        val out = ArrayList<LocalItem>()
        fun walk(dir: androidx.documentfile.provider.DocumentFile, depth: Int) {
            if (depth > 5 || out.size >= 800) return
            val files = try { dir.listFiles() } catch (_: Throwable) { return }
            for (f in files) {
                if (f.isDirectory) { walk(f, depth + 1); continue }
                val name = f.name ?: continue
                val isVideo = (f.type?.startsWith("video") == true) || VIDEO_EXT.containsMatchIn(name)
                if (!isVideo) continue
                if (!q.isNullOrEmpty() && !name.lowercase().contains(q)) continue
                out.add(LocalItem(
                    uri = f.uri.toString(),
                    title = name.substringBeforeLast('.'),
                    subtitle = dir.name ?: "USB",
                    durationMs = 0L,
                    folder = dir.name ?: "USB",
                    artUri = null,
                ))
            }
        }
        walk(root, 0)
        return out
    }

    private fun list(
        context: Context,
        collection: Uri,
        audio: Boolean,
        query: String?,
        limit: Int,
    ): List<LocalItem> {
        val out = ArrayList<LocalItem>()
        // RELATIVE_PATH n'existe qu'à partir d'Android 10 (API 29) → on ne l'ajoute au projection
        //   AUDIO que sur Q+ (sinon la requête audio casse sur les vieux Android). Pour la VIDÉO,
        //   le comportement historique (RELATIVE_PATH dans le projection) est conservé.
        val hasRel = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = if (audio) {
            (arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.TITLE,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.SIZE,
                MediaStore.Audio.Media.ARTIST,
            ) + (if (hasRel) arrayOf(MediaStore.MediaColumns.RELATIVE_PATH) else emptyArray()))
                .plus(MediaStore.Audio.Media.ALBUM_ID)
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.TITLE,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
        }
        val selection: String?
        val args: Array<String>?
        if (query.isNullOrBlank()) {
            selection = null
            args = null
        } else {
            selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            args = arrayOf("%${query.trim()}%")
        }
        val order = if (audio) {
            "${MediaStore.MediaColumns.TITLE} COLLATE NOCASE ASC"
        } else {
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        }

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(collection, projection, selection, args, order)
            cursor ?: return out
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val titleCol = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE)
            val durCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val albumCol = if (audio) cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID) else -1
            val artistCol = if (audio) cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST) else -1
            val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext() && out.size < limit) {
                val id = cursor.getLong(idCol)
                val uri = android.content.ContentUris.withAppendedId(collection, id).toString()
                val display = cursor.getString(nameCol) ?: continue
                val title = (if (titleCol >= 0) cursor.getString(titleCol) else null)
                    ?.takeIf { it.isNotBlank() } ?: display.substringBeforeLast('.')
                val dur = if (durCol >= 0) cursor.getLong(durCol) else 0L
                val relPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                val artist = if (artistCol >= 0) cursor.getString(artistCol) else null
                // subtitle/folder historiques : audio→artiste, vidéo→chemin (inchangé).
                val extra = if (audio) artist else relPath
                val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                out.add(
                    LocalItem(
                        uri = uri,
                        title = title,
                        subtitle = buildString {
                            if (!extra.isNullOrBlank()) append(extra)
                            if (dur > 0) {
                                if (isNotEmpty()) append(" • ")
                                append(formatDuration(dur))
                            }
                            if (size > 0) {
                                if (isNotEmpty()) append(" • ")
                                append("${size / 1024 / 1024} Mo")
                            }
                        },
                        durationMs = dur,
                        artUri = if (audio && albumCol >= 0) {
                            val albumId = cursor.getLong(albumCol)
                            if (albumId > 0) "content://media/external/audio/albumart/$albumId" else null
                        } else null,
                        folder = if (audio) {
                            (extra ?: "").ifBlank { "Musique" }
                        } else {
                            (extra ?: "").trim('/').ifBlank { "Autres" }
                        },
                        dir = (relPath ?: "").trim('/').ifBlank { if (audio) "Musique" else "Autres" },
                    ),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "lecture médiathèque KO: ${e.message}")
        } finally {
            runCatching { cursor?.close() }
        }
        return out
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private const val TAG = "LocalMediaStore"
}
