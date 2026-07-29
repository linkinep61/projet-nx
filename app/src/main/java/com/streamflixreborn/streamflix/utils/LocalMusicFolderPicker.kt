package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2026-07-28 (demande user : « choisir le dossier musique qu'on veut, pas tout prendre ; et mettre
 * un dossier en favori pour éviter de fouiller ») — SÉLECTEUR DE DOSSIER pour la musique locale.
 *
 * Feuille sombre assortie au menu radio. Liste les dossiers de la médiathèque audio (RELATIVE_PATH),
 * favoris ★ en premier, filtre « Favoris », choix « Tout ». `onChosen(dir)` : dir = dossier choisi,
 * ou null pour toute la musique.
 */
object LocalMusicFolderPicker {

    fun show(context: Context, lifecycleOwner: LifecycleOwner, onChosen: (dir: String?) -> Unit) {
        val store = LocalMediaStore
        lifecycleOwner.lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { store.listAudio(context) }
            if (all.isEmpty()) {
                Toast.makeText(context, "Aucune musique locale trouvée", Toast.LENGTH_SHORT).show()
                onChosen(null)
                return@launch
            }
            // Dossier réel (dir) → nombre de morceaux.
            val folders = all.groupBy { it.dir.ifBlank { "Musique" } }
                .map { it.key to it.value.size }

            var onlyFav = false

            fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
            fun rounded(color: String, radius: Int, stroke: String? = null, sw: Int = 0) =
                GradientDrawable().apply {
                    setColor(Color.parseColor(color)); cornerRadius = dp(radius).toFloat()
                    if (stroke != null) setStroke(dp(sw), Color.parseColor(stroke))
                }
            // Liseré de focus permanent (visible à la télécommande, invisible au tactile) — les box
            //   RockChip ne se déclarent pas TV, donc on ne conditionne PLUS le liseré à UiModeManager.
            fun ring(v: View) {
                v.isFocusable = true; v.isClickable = true; v.isFocusableInTouchMode = false
                val on = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(12).toFloat(); setStroke(dp(2), Color.parseColor("#FFFFFF")) }
                val off = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
                v.foreground = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), on)
                    addState(intArrayOf(android.R.attr.state_pressed), on)
                    addState(intArrayOf(), off)
                }
            }

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded("#15171C", 18)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }

            // En-tête
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val icon = TextView(context).apply {
                text = "🎵"; textSize = 18f; gravity = Gravity.CENTER
                background = rounded("#2C313C", 10); setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            val title = TextView(context).apply {
                text = "  Choisir un dossier"; setTextColor(Color.parseColor("#F2F4F8"))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val count = TextView(context).apply {
                text = "${folders.size}"; setTextColor(Color.WHITE); textSize = 12f
                background = rounded("#E24B4A", 20); setPadding(dp(10), dp(2), dp(10), dp(2))
            }
            header.addView(icon); header.addView(title); header.addView(count)
            root.addView(header)

            // Barre : Tout / Favoris
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, dp(10))
            }
            fun pill(label: String) = TextView(context).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#C4C9D4")); setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded("#101216", 12, "#3A4150", 1)
                ring(this)
            }
            val allBtn = pill("📁 Tout")
            val favBtn = pill("★ Favoris")
            bar.addView(allBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            bar.addView(favBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(bar)

            val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(context).apply { isFillViewport = true; addView(listBox) }
            root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)))

            val dialog = AlertDialog.Builder(context).setView(root).create()
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

            fun renderList() {
                listBox.removeAllViews()
                val favs = LocalMediaFolderStore.all(context, audio = true)
                val shown = folders
                    .filter { !onlyFav || favs.contains(it.first) }
                    .sortedWith(compareByDescending<Pair<String, Int>> { favs.contains(it.first) }.thenBy { it.first.lowercase() })
                favBtn.setTextColor(Color.parseColor(if (onlyFav) "#FFFFFF" else "#C4C9D4"))
                favBtn.background = rounded(if (onlyFav) "#E24B4A" else "#101216", 12, if (onlyFav) "#E24B4A" else "#3A4150", if (onlyFav) 0 else 1)
                if (shown.isEmpty()) {
                    listBox.addView(TextView(context).apply {
                        text = if (onlyFav) "Aucun dossier favori" else "Aucun dossier"
                        setTextColor(Color.parseColor("#8A90A0")); textSize = 13f
                        setPadding(dp(8), dp(16), dp(8), dp(16))
                    })
                    return
                }
                for ((dir, n) in shown) {
                    val isFav = favs.contains(dir)
                    val rowRoot = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        background = rounded("#20242C", 12, "#2E333D", 1)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        ring(this)
                    }
                    val fIcon = TextView(context).apply {
                        text = "📁"; textSize = 18f; gravity = Gravity.CENTER
                        background = rounded("#2C313C", 10); setPadding(dp(8), dp(6), dp(8), dp(6))
                    }
                    val labelBox = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setPadding(dp(12), 0, dp(8), 0)
                    }
                    val name = dir.substringAfterLast('/', dir).ifBlank { dir }
                    labelBox.addView(TextView(context).apply {
                        text = name; setTextColor(Color.parseColor("#F2F4F8")); textSize = 14f
                    })
                    labelBox.addView(TextView(context).apply {
                        text = "$n morceau${if (n > 1) "x" else ""}"
                        setTextColor(Color.parseColor("#8A90A0")); textSize = 12f
                    })
                    val star = TextView(context).apply {
                        text = "★"; textSize = 20f
                        setTextColor(Color.parseColor(if (isFav) "#F2C14E" else "#5A6270"))
                        isFocusable = false; isClickable = false
                    }
                    rowRoot.addView(fIcon); rowRoot.addView(labelBox); rowRoot.addView(star)
                    rowRoot.setOnClickListener { dialog.dismiss(); onChosen(dir) }
                    rowRoot.setOnLongClickListener {
                        val added = LocalMediaFolderStore.toggle(context, audio = true, dir)
                        Toast.makeText(context, if (added) "★ « $name » en favori" else "Retiré des favoris", Toast.LENGTH_SHORT).show()
                        renderList(); true
                    }
                    listBox.addView(rowRoot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                }
            }

            allBtn.setOnClickListener { dialog.dismiss(); onChosen(null) }
            favBtn.setOnClickListener { onlyFav = !onlyFav; renderList() }

            dialog.show()
            renderList()
            Toast.makeText(context, "Appui long sur un dossier = favori ★", Toast.LENGTH_SHORT).show()
        }
    }
}
