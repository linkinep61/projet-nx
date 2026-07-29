package com.streamflixreborn.streamflix.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.activities.LocalVideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2026-07-25 (demande user) — VIDÉOS DU TÉLÉPHONE.
 *
 * Rangées par DOSSIER (DCIM/Camera, Download, WhatsApp…) plutôt qu'en vrac, avec une barre de
 * boutons EN HAUT (toujours accessible, contrairement aux boutons d'AlertDialog qui se retrouvaient
 * coincés en bas de l'écran).
 *  • clic sur un dossier → ses vidéos ; clic sur une vidéo → lit la liste depuis celle-ci (boucle)
 *  • ★ Favoris : filtre ; appui long sur une vidéo : ajoute / retire des favoris
 *  • 🔀 Tout lire : lit en aléatoire ce qui est affiché (donc les favoris si le filtre est actif)
 */
object LocalVideoPickerDialog {

    fun show(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        /** Callback pour ouvrir le sélecteur de DOSSIER (SAF) — fourni par le fragment (clé USB sur
         *  box TV). Si null, le bouton « Dossier USB » n'apparaît pas. */
        onPickFolder: (() -> Unit)? = null,
    ) {
        val store = LocalMediaStore
        // 2026-07-28 : si un DOSSIER SAF a été choisi (clé USB sur box TV), on n'a PAS besoin de la
        //   permission média → on saute la demande et on liste directement (MediaStore + dossier SAF).
        if (!store.hasPermission(context, audio = false) && !store.hasVideoFolder(context)) {
            // On demande D'ABORD la permission VISUELLE (« Photos et vidéos ») — c'est le même
            //   groupe que la musique et ça marche sur box TV. La clé USB étant indexée par
            //   MediaStore (prouvé par la musique), la vidéo apparaît une fois cette perm accordée.
            com.streamflixreborn.streamflix.activities.LocalMediaPermissionActivity.request(
                context, store.requiredPermissions(audio = false),
            ) { _ ->
                if (store.hasPermission(context, audio = false)) {
                    show(context, lifecycleOwner, onPickFolder)
                } else if (onPickFolder != null) {
                    // Permission refusée / dialogue absent sur cette box → repli fourni par le
                    //   fragment (accès à tous les fichiers, puis explorateur intégré).
                    onPickFolder()
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
                                android.net.Uri.parse("package:" + context.packageName),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: Exception) {
                        Toast.makeText(context, "Accès aux vidéos refusé", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }

        lifecycleOwner.lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { store.listVideo(context) }
            if (all.isEmpty()) {
                // Rien trouvé (MediaStore n'indexe pas la clé USB sur box TV) → si le fragment sait
                //   ouvrir le sélecteur de dossier, on l'ouvre pour pointer la clé USB.
                if (onPickFolder != null) {
                    Toast.makeText(
                        context,
                        "Choisis le dossier de tes vidéos (ex. ta clé USB)",
                        Toast.LENGTH_LONG,
                    ).show()
                    onPickFolder()
                } else {
                    Toast.makeText(context, "Aucune vidéo locale trouvée", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            var currentFolder: String? = null   // null = on affiche la liste des dossiers
            var onlyFavorites = false

            // 2026-07-28 : refonte visuelle (style du menu radio) — feuille sombre arrondie, en-tête
            //   avec pastilles, bascule segmentée Dossiers/Favoris, actions en pastilles, lignes en
            //   cartes (dossier→chevron, vidéo→étoile dorée si favori). Logique 100 % conservée.
            fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
            fun rounded(color: String, radius: Int, stroke: String? = null, sw: Int = 0) =
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor(color)); cornerRadius = dp(radius).toFloat()
                    if (stroke != null) setStroke(dp(sw), Color.parseColor(stroke))
                }
            // 2026-07-29 (bug user : « curseur invisible sur box TV ») : le liseré de focus était
            //   conditionné à UiModeManager==TELEVISION, or les box RockChip/AOSP ne se déclarent PAS
            //   TV → aucun liseré → curseur invisible. FIX : liseré de focus PERMANENT en state-list
            //   (foreground) — il n'apparaît QUE sur l'état focus (D-pad), donc invisible au tactile.
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
            fun rowLp() = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded("#15171C", 18); setPadding(dp(14), dp(14), dp(14), dp(14))
            }

            // ── En-tête ──
            val hTitle = TextView(context).apply { text = "Vidéos locales"; setTextColor(Color.parseColor("#F2F4F8")); textSize = 15f }
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
                    text = "🎬"; textSize = 18f; gravity = Gravity.CENTER
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

            // ── Bascule segmentée Dossiers / Favoris ──
            fun segTab(label: String) = TextView(context).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8))
            }.also { focusable(it) }
            val tabFolders = segTab("📁 Dossiers")
            val tabFavs = segTab("★ Favoris")
            val seg = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded("#101216", 12); setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(tabFolders, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(tabFavs, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(seg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

            // ── Actions (pastilles) ──
            fun actionPill(label: String, textColor: String, strokeColor: String) = TextView(context).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor(textColor)); setPadding(dp(8), dp(7), dp(8), dp(7))
                background = rounded("#15171C", 20, strokeColor, 1)
            }.also { focusable(it) }
            // Le bouton « Tous les dossiers » ne sert qu'UNE fois (accorder l'accès complet) → une
            //   fois l'accès accordé, il ne sert plus → on ne l'affiche PAS.
            val usbPill = if (onPickFolder != null && !store.hasAllFilesAccess())
                actionPill("📁 Tous les dossiers", "#C4C9D4", "#3A4150") else null
            val playAllPill = actionPill("🔀 Tout lire", "#7CD98A", "#2E7D32")
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                usbPill?.let { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }) }
                addView(playAllPill, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

            // ── Liste ──
            val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
            val scroll = android.widget.ScrollView(context).apply { isFillViewport = true; addView(listBox) }
            root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(340)))

            val dialog = AlertDialog.Builder(context).setView(root).create()
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

            var shownVideos: List<LocalMediaStore.LocalItem> = emptyList()
            fun favUris() = LocalVideoFavoritesStore.all(context).map { it.uri }.toSet()

            fun playList(startIndex: Int, shuffle: Boolean) {
                if (shownVideos.isEmpty()) {
                    Toast.makeText(context, "Ouvre un dossier ou les favoris d'abord", Toast.LENGTH_SHORT).show()
                    return
                }
                context.startActivity(
                    Intent(context, LocalVideoPlayerActivity::class.java).apply {
                        putExtra(LocalVideoPlayerActivity.EXTRA_URIS, shownVideos.map { it.uri }.toTypedArray())
                        putExtra(LocalVideoPlayerActivity.EXTRA_TITLES, shownVideos.map { it.title }.toTypedArray())
                        putExtra(LocalVideoPlayerActivity.EXTRA_START_INDEX, startIndex)
                        putExtra(LocalVideoPlayerActivity.EXTRA_SHUFFLE, shuffle)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                dialog.dismiss()
            }

            fun pastille(emoji: String, tint: String) = TextView(context).apply {
                text = emoji; textSize = 18f; gravity = Gravity.CENTER
                background = rounded("#2C313C", 10); setTextColor(Color.parseColor(tint)); setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            fun twoLines(a: String, b: String): LinearLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(12), 0, dp(8), 0)
                addView(TextView(context).apply { text = a; setTextColor(Color.parseColor("#F2F4F8")); textSize = 14f; maxLines = 2 })
                addView(TextView(context).apply { text = b; setTextColor(Color.parseColor("#8A90A0")); textSize = 12f; maxLines = 1 })
            }
            fun cardRow(): LinearLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded("#20242C", 12, "#2E333D", 1); setPadding(dp(12), dp(10), dp(12), dp(10))
            }.also { focusable(it) }
            fun emptyText(msg: String) = TextView(context).apply {
                text = msg; setTextColor(Color.parseColor("#8A90A0")); textSize = 13f; setPadding(dp(8), dp(16), dp(8), dp(16))
            }

            fun setTab(favMode: Boolean) {
                tabFolders.background = if (!favMode) rounded("#E24B4A", 9) else null
                tabFolders.setTextColor(Color.parseColor(if (!favMode) "#FFFFFF" else "#C4C9D4"))
                tabFavs.background = if (favMode) rounded("#E24B4A", 9) else null
                tabFavs.setTextColor(Color.parseColor(if (favMode) "#FFFFFF" else "#C4C9D4"))
            }

            fun render() {
                val favs = favUris()
                listBox.removeAllViews()
                if (onlyFavorites) {
                    shownVideos = all.filter { it.uri in favs }
                    hTitle.text = "Vidéos favorites"; hSub.text = "★ favoris"; hCount.text = "${shownVideos.size}"
                    if (shownVideos.isEmpty()) { listBox.addView(emptyText("Aucune vidéo favorite")); return }
                    shownVideos.forEach { v ->
                        val row = cardRow()
                        row.addView(pastille("🎬", "#E24B4A")); row.addView(twoLines(v.title, v.subtitle))
                        row.addView(TextView(context).apply { text = "★"; textSize = 20f; setTextColor(Color.parseColor("#F2C14E")) })
                        row.setOnClickListener { playList(shownVideos.indexOf(v).coerceAtLeast(0), false) }
                        row.setOnLongClickListener {
                            LocalVideoFavoritesStore.toggle(context, v.uri, v.title)
                            Toast.makeText(context, "Retirée des favoris", Toast.LENGTH_SHORT).show(); render(); true
                        }
                        listBox.addView(row, rowLp())
                    }
                    return
                }
                val folder = currentFolder
                if (folder == null) {
                    shownVideos = emptyList()
                    val folders = all.groupBy { it.folder.ifBlank { "Autres" } }.toList().sortedByDescending { it.second.size }
                    hTitle.text = "Vidéos locales"; hSub.text = "${folders.size} dossier${if (folders.size > 1) "s" else ""}"; hCount.text = "${all.size}"
                    folders.forEach { (name, list) ->
                        val row = cardRow()
                        row.addView(pastille("📁", "#8FB7F0")); row.addView(twoLines(name, "${list.size} vidéo${if (list.size > 1) "s" else ""}"))
                        row.addView(TextView(context).apply { text = "›"; textSize = 22f; setTextColor(Color.parseColor("#6B7280")); setPadding(dp(4), 0, dp(4), 0) })
                        row.setOnClickListener { currentFolder = name; render() }
                        listBox.addView(row, rowLp())
                    }
                    return
                }
                shownVideos = all.filter { (it.folder.ifBlank { "Autres" }) == folder }
                hTitle.text = folder; hSub.text = "dossier"; hCount.text = "${shownVideos.size}"
                shownVideos.forEach { v ->
                    val fav = v.uri in favs
                    val row = cardRow()
                    row.addView(pastille("🎬", if (fav) "#E24B4A" else "#C4C9D4")); row.addView(twoLines(v.title, v.subtitle))
                    row.addView(TextView(context).apply { text = "★"; textSize = 20f; setTextColor(Color.parseColor(if (fav) "#F2C14E" else "#5A6270")) })
                    row.setOnClickListener { playList(shownVideos.indexOf(v).coerceAtLeast(0), false) }
                    row.setOnLongClickListener {
                        val added = LocalVideoFavoritesStore.toggle(context, v.uri, v.title)
                        Toast.makeText(context, if (added) "★ « ${v.title} » ajoutée" else "Retirée des favoris", Toast.LENGTH_SHORT).show(); render(); true
                    }
                    listBox.addView(row, rowLp())
                }
            }

            tabFolders.setOnClickListener { onlyFavorites = false; currentFolder = null; setTab(false); render() }
            tabFavs.setOnClickListener { onlyFavorites = true; setTab(true); render() }
            playAllPill.setOnClickListener { playList(0, shuffle = true) }
            usbPill?.setOnClickListener { dialog.dismiss(); onPickFolder?.invoke() }
            hClose.setOnClickListener { dialog.dismiss() }

            dialog.show()
            setTab(false)
            render()
        }
    }
}
