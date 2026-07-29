package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import com.streamflixreborn.streamflix.activities.LocalVideoPlayerActivity
import java.io.File

/**
 * 2026-07-28 — EXPLORATEUR DE FICHIERS INTÉGRÉ pour les vidéos locales (box TV sans DocumentsUI
 * ni écran « accès à tous les fichiers »). Navigue directement le système de fichiers en java.io.File
 * (comme un vrai explorateur), liste dossiers + vidéos, et joue une vidéo au clic. Affiche aussi
 * l'état de lecture de chaque emplacement (utile pour diagnostiquer un stockage cloisonné).
 *
 * Ne dépend NI de MediaStore, NI de SAF, NI d'un écran de réglages — juste des droits de lecture
 * du système de fichiers (souvent OK sur box TV, surtout avec MANAGE_EXTERNAL_STORAGE quand dispo).
 */
object LocalVideoFolderBrowser {

    private val VIDEO_EXT =
        Regex("\\.(mp4|mkv|avi|mov|webm|m4v|flv|wmv|mpg|mpeg|3gp|ts|m2ts)$", RegexOption.IGNORE_CASE)

    /** Systèmes de fichiers typiques d'une clé USB / carte SD (dans /proc/mounts). */
    private val REMOVABLE_FS = setOf(
        "vfat", "exfat", "texfat", "sdfat", "fuseblk", "ntfs", "ntfs3", "msdos",
        "iso9660", "udf", "f2fs", "ext4", "ext3", "ext2",
    )

    /** Emplacements de départ : stockage interne + volumes amovibles (USB/SD) découverts
     *  DYNAMIQUEMENT via /proc/mounts (comme un explorateur) + points de montage classiques. */
    private fun roots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()
        try { Environment.getExternalStorageDirectory()?.let { if (it.isDirectory) roots.add(it) } } catch (_: Throwable) {}
        try {
            (context.getExternalFilesDirs(null).toList() + context.externalCacheDirs.toList()).forEach { dir ->
                dir ?: return@forEach
                val p = dir.absolutePath
                val idx = p.indexOf("/Android/")
                if (idx > 0) { val r = File(p.substring(0, idx)); if (r.isDirectory) roots.add(r) }
            }
        } catch (_: Throwable) {}

        // ── Découverte DYNAMIQUE via /proc/mounts (trouve la clé USB où qu'elle soit montée) ──
        try {
            File("/proc/mounts").readLines().forEach { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3) return@forEach
                val mountPoint = parts[1].replace("\\040", " ")
                val fsType = parts[2].lowercase()
                val mpLower = mountPoint.lowercase()
                val looksRemovable = fsType in REMOVABLE_FS &&
                    (mpLower.startsWith("/storage/") || mpLower.startsWith("/mnt/") ||
                        mpLower.contains("usb") || mpLower.contains("udisk") ||
                        mpLower.contains("sd") || mpLower.startsWith("/media/"))
                // on écarte le système / l'interne émulé
                val isSystem = mpLower == "/" || mpLower.startsWith("/system") ||
                    mpLower.startsWith("/vendor") || mpLower.contains("/emulated") ||
                    mpLower == "/data" || mpLower.startsWith("/apex") || mpLower.startsWith("/proc")
                if (looksRemovable && !isSystem) {
                    val f = File(mountPoint)
                    if (f.isDirectory) roots.add(f)
                }
            }
        } catch (_: Throwable) {}

        // ── Points de montage classiques (au cas où /proc/mounts serait filtré) ──
        for (mp in listOf(
            "/storage", "/mnt/media_rw", "/mnt/usb", "/mnt/usbotg", "/mnt/usb_storage",
            "/mnt/external_sd", "/mnt/sdcard", "/udisk", "/mnt",
        )) {
            try {
                File(mp).listFiles()?.forEach { child ->
                    val n = child.name.lowercase()
                    if (child.isDirectory && n != "self" && n != "emulated") roots.add(child)
                }
            } catch (_: Throwable) {}
        }
        // Racine « / » en dernier recours pour naviguer partout à la main.
        try { File("/").let { if (it.isDirectory) roots.add(it) } } catch (_: Throwable) {}

        return roots.toList()
    }

    fun show(context: Context, lifecycleOwner: LifecycleOwner) {
        var current: File? = null // null = liste des emplacements

        val rootList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 8)
        }
        fun btn(label: String, color: String) = TextView(context).apply {
            text = label; setPadding(24, 22, 24, 22); textSize = 14f
            gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(color)); isClickable = true; isFocusable = true
            foreground = androidx.core.content.ContextCompat.getDrawable(
                context, com.streamflixreborn.streamflix.R.drawable.bg_focus_white_border,
            )
        }
        val upBtn = btn("⬆ Remonter", "#455A64")
        val allBtn = btn("🔀 Tout lire", "#2E7D32")
        val closeBtn = btn("✕ Fermer", "#B71C1C")
        listOf(upBtn, allBtn, closeBtn).forEach {
            bar.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) })
        }
        rootList.addView(bar)
        val listView = ListView(context).apply { isFocusable = true }
        rootList.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val dialog = AlertDialog.Builder(context).setView(rootList).create()

        var currentVideos: List<File> = emptyList()

        fun playFolder(startIndex: Int, shuffle: Boolean) {
            if (currentVideos.isEmpty()) {
                Toast.makeText(context, "Aucune vidéo dans ce dossier", Toast.LENGTH_SHORT).show(); return
            }
            context.startActivity(
                Intent(context, LocalVideoPlayerActivity::class.java).apply {
                    putExtra(LocalVideoPlayerActivity.EXTRA_URIS, currentVideos.map { Uri.fromFile(it).toString() }.toTypedArray())
                    putExtra(LocalVideoPlayerActivity.EXTRA_TITLES, currentVideos.map { it.name.substringBeforeLast('.') }.toTypedArray())
                    putExtra(LocalVideoPlayerActivity.EXTRA_START_INDEX, startIndex)
                    putExtra(LocalVideoPlayerActivity.EXTRA_SHUFFLE, shuffle)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            dialog.dismiss()
        }

        // Chaque ligne : (libellé, cible). cible null = ligne info non cliquable.
        var rows: List<Pair<String, File?>> = emptyList()

        fun render() {
            val cur = current
            val entries = ArrayList<Pair<String, File?>>()
            if (cur == null) {
                dialog.setTitle("📂 Emplacements")
                upBtn.visibility = View.GONE
                allBtn.visibility = View.GONE
                currentVideos = emptyList()
                val rs = roots(context)
                if (rs.isEmpty()) entries.add(Pair("Aucun emplacement détecté", null))
                rs.forEach { r ->
                    val readable = try { r.canRead() && r.listFiles() != null } catch (_: Throwable) { false }
                    val tag = if (readable) "📁" else "🔒"
                    entries.add(Pair("$tag ${r.absolutePath}", if (readable) r else null))
                }
            } else {
                dialog.setTitle(cur.absolutePath)
                upBtn.visibility = View.VISIBLE
                val files = try { cur.listFiles() } catch (_: Throwable) { null }
                currentVideos = files?.filter { it.isFile && VIDEO_EXT.containsMatchIn(it.name) }
                    ?.sortedBy { it.name.lowercase() } ?: emptyList()
                allBtn.visibility = if (currentVideos.isNotEmpty()) View.VISIBLE else View.GONE
                if (files == null) {
                    entries.add(Pair("🔒 Dossier illisible (accès refusé par le système)", null))
                } else {
                    files.filter { it.isDirectory && !it.name.startsWith(".") }
                        .sortedBy { it.name.lowercase() }
                        .forEach { d -> entries.add(Pair("📁 ${d.name}", d)) }
                    currentVideos.forEach { v -> entries.add(Pair("🎬 ${v.name}", v)) }
                    if (entries.isEmpty()) entries.add(Pair("(dossier vide)", null))
                }
            }
            rows = entries
            listView.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, entries.map { it.first })
        }

        listView.setOnItemClickListener { _, _, pos, _ ->
            val (_, target) = rows.getOrNull(pos) ?: return@setOnItemClickListener
            if (target == null) return@setOnItemClickListener
            when {
                target.isDirectory -> { current = target; render() }
                target.isFile -> {
                    val idx = currentVideos.indexOf(target).coerceAtLeast(0)
                    playFolder(idx, shuffle = false)
                }
            }
        }

        upBtn.setOnClickListener {
            val cur = current
            current = if (cur == null) null else {
                val parent = cur.parentFile
                // si on remonte au-dessus d'un emplacement de départ → revenir à la liste
                if (parent == null || roots(context).any { it.absolutePath == cur.absolutePath }) null else parent
            }
            render()
        }
        allBtn.setOnClickListener { playFolder(0, shuffle = true) }
        closeBtn.setOnClickListener { dialog.dismiss() }

        dialog.show()
        render()

        // Info d'usage la première fois (surtout si tout est verrouillé).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !LocalMediaStore.hasAllFilesAccess()) {
            Toast.makeText(
                context,
                "Navigue jusqu'à ta clé USB. Un 🔒 = dossier bloqué par le système.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
