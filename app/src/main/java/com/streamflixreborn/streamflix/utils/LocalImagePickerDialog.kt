package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 2026-07-29 (bug testeur MiBox : « ajouter ses propres photos » ne lit pas la clé USB) — SÉLECTEUR
 * D'IMAGE LOCALE pour le fond d'écran perso. Le picker système (SAF/DocumentsUI) est ABSENT sur les
 * box Android TV → on réutilise EXACTEMENT la méthode des vidéos : MediaStore tous volumes
 * (interne/SD/USB) + scan direct des volumes amovibles, via [LocalMediaStore.listImages].
 *
 * Navigation par DOSSIER (comme le picker vidéo) puis grille de vignettes. À la sélection, l'image
 * est COPIÉE en local (persistance du fond même si l'URI d'origine devient inaccessible) et l'URI
 * locale est renvoyée via [onPicked].
 */
object LocalImagePickerDialog {

    fun show(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        onPicked: (Uri) -> Unit,
    ) {
        val store = LocalMediaStore
        if (!store.hasImagePermission(context)) {
            com.streamflixreborn.streamflix.activities.LocalMediaPermissionActivity.request(
                context, store.requiredPermissions(audio = false),
            ) { _ ->
                if (store.hasImagePermission(context)) {
                    show(context, lifecycleOwner, onPicked)
                } else {
                    Toast.makeText(
                        context,
                        "Active « Photos et vidéos » pour ONYX dans les réglages qui s'ouvrent, puis reviens",
                        Toast.LENGTH_LONG,
                    ).show()
                    try {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + context.packageName),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: Exception) {
                        Toast.makeText(context, "Accès aux photos refusé", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }

        lifecycleOwner.lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { store.listImages(context) }
            if (all.isEmpty()) {
                Toast.makeText(context, "Aucune image trouvée (interne, carte SD ou clé USB)", Toast.LENGTH_LONG).show()
                return@launch
            }

            fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
            fun rounded(color: String, radius: Int, stroke: String? = null, sw: Int = 0) =
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor(color)); cornerRadius = dp(radius).toFloat()
                    if (stroke != null) setStroke(dp(sw), Color.parseColor(stroke))
                }
            fun focusable(v: View) {
                v.isFocusable = true; v.isClickable = true; v.isFocusableInTouchMode = false
                val ring = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); cornerRadius = dp(12).toFloat()
                    setStroke(dp(2), Color.parseColor("#FFFFFF"))
                }
                val none = android.graphics.drawable.GradientDrawable().apply { setColor(Color.TRANSPARENT) }
                v.foreground = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), ring)
                    addState(intArrayOf(android.R.attr.state_pressed), ring)
                    addState(intArrayOf(), none)
                }
            }
            val isTv = try {
                context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
            } catch (_: Throwable) { false }
            val cols = if (isTv) 4 else 3

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded("#15171C", 18); setPadding(dp(14), dp(14), dp(14), dp(14))
            }

            // ── En-tête ──
            val hTitle = TextView(context).apply { setTextColor(Color.parseColor("#F2F4F8")); textSize = 15f }
            val hSub = TextView(context).apply { setTextColor(Color.parseColor("#8A90A0")); textSize = 12f }
            val hCount = TextView(context).apply {
                setTextColor(Color.WHITE); textSize = 12f
                background = rounded("#E24B4A", 20); setPadding(dp(10), dp(2), dp(10), dp(2))
            }
            val hClose = TextView(context).apply {
                text = "✕"; setTextColor(Color.parseColor("#C4C9D4")); textSize = 15f; gravity = Gravity.CENTER
                background = rounded("#2C313C", 9); setPadding(dp(9), dp(6), dp(9), dp(6))
            }.also { focusable(it) }
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "🖼️"; textSize = 18f; gravity = Gravity.CENTER
                    background = rounded("#2C313C", 10); setPadding(dp(8), dp(6), dp(8), dp(6))
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(dp(10), 0, dp(8), 0); addView(hTitle); addView(hSub)
                })
                addView(hCount)
                addView(hClose, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            }
            root.addView(header)

            val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
            val scroll = android.widget.ScrollView(context).apply { isFillViewport = true; addView(listBox) }
            root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)))

            val dialog = AlertDialog.Builder(context).setView(root).create()
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

            fun applyPicked(item: LocalMediaStore.LocalItem) {
                Toast.makeText(context, "Application du fond…", Toast.LENGTH_SHORT).show()
                lifecycleOwner.lifecycleScope.launch {
                    val local = withContext(Dispatchers.IO) { copyToLocal(context, item.uri) }
                    dialog.dismiss()
                    if (local == null) {
                        Toast.makeText(context, "Impossible de lire cette image", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    onPicked(Uri.fromFile(local))
                }
            }

            var currentFolder: String? = null

            fun render() {
                listBox.removeAllViews()
                val folder = currentFolder
                if (folder == null) {
                    val folders = all.groupBy { it.folder.ifBlank { "Images" } }.toList().sortedByDescending { it.second.size }
                    hTitle.text = "Mes photos"; hSub.text = "${folders.size} dossier${if (folders.size > 1) "s" else ""}"; hCount.text = "${all.size}"
                    folders.forEach { (name, list) ->
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            background = rounded("#20242C", 12, "#2E333D", 1); setPadding(dp(12), dp(10), dp(12), dp(10))
                        }.also { focusable(it) }
                        row.addView(TextView(context).apply {
                            text = "📁"; textSize = 18f; gravity = Gravity.CENTER
                            background = rounded("#2C313C", 10); setTextColor(Color.parseColor("#8FB7F0")); setPadding(dp(8), dp(6), dp(8), dp(6))
                        })
                        row.addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            setPadding(dp(12), 0, dp(8), 0)
                            addView(TextView(context).apply { text = name; setTextColor(Color.parseColor("#F2F4F8")); textSize = 14f; maxLines = 2 })
                            addView(TextView(context).apply { text = "${list.size} image${if (list.size > 1) "s" else ""}"; setTextColor(Color.parseColor("#8A90A0")); textSize = 12f })
                        })
                        row.addView(TextView(context).apply { text = "›"; textSize = 22f; setTextColor(Color.parseColor("#6B7280")); setPadding(dp(4), 0, dp(4), 0) })
                        row.setOnClickListener { currentFolder = name; render() }
                        listBox.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                    }
                    return
                }
                hTitle.text = folder; hSub.text = "‹ dossiers"; hCount.text = ""
                hSub.setOnClickListener { currentFolder = null; render() }
                // Retour en tête de dossier via un bouton dédié (D-pad).
                val back = TextView(context).apply {
                    text = "‹ Dossiers"; textSize = 13f; setTextColor(Color.parseColor("#C4C9D4"))
                    background = rounded("#20242C", 20, "#3A4150", 1); setPadding(dp(12), dp(7), dp(12), dp(7)); gravity = Gravity.CENTER
                }.also { focusable(it) }
                back.setOnClickListener { currentFolder = null; render() }
                listBox.addView(back, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

                val imgs = all.filter { it.folder.ifBlank { "Images" } == folder }
                imgs.chunked(cols).forEach { rowItems ->
                    val rowLl = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    rowItems.forEach { item ->
                        val thumb = ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            background = rounded("#20242C", 10)
                            setPadding(0, 0, 0, 0)
                        }.also { focusable(it) }
                        Glide.with(context).load(Uri.parse(item.uri)).centerCrop()
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).into(thumb)
                        thumb.setOnClickListener { applyPicked(item) }
                        rowLl.addView(thumb, LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                    }
                    // remplir la dernière rangée pour garder l'alignement
                    repeat(cols - rowItems.size) {
                        rowLl.addView(View(context), LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                    }
                    listBox.addView(rowLl, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
            }

            hClose.setOnClickListener { dialog.dismiss() }
            dialog.show()
            render()
        }
    }

    /** Copie l'image (content:// ou file://) dans le stockage privé de l'app pour un fond persistant. */
    private fun copyToLocal(context: Context, uriStr: String): File? = try {
        val uri = Uri.parse(uriStr)
        val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
        val out = File(dir, "custom_${System.currentTimeMillis()}.img")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        if (out.length() > 0) out else null
    } catch (_: Throwable) { null }
}
