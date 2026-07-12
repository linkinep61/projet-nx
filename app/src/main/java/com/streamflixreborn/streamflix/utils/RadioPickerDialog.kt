package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 2026-06-08 : dialog dédié pour le picker de radios.
 * Centralise mobile + TV.
 *
 * Features :
 *  - Liste toutes les radios (Dric4rTV + RadioBrowser FR).
 *  - Toggle "Toutes" / "Favoris ★".
 *  - Recherche live par nom.
 *  - Long-press sur une radio = toggle favori.
 *  - Boutons Arrêter / Fermer EN HAUT (user "on doit défiler toute la liste
 *    pour les toucher si en bas").
 *  - Au clic, démarre le mini-bar audio. Pas de fullscreen (cf
 *    MiniPlayerController.isRadioChannel).
 */
object RadioPickerDialog {

    fun show(ctx: Context, lifecycleOwner: LifecycleOwner) {
        // 2026-06-08 (user "tu peux pas faire un truc pour que ça s'affiche
        //   en différé") : on ouvre le dialog IMMÉDIATEMENT (avec liste
        //   vide), puis le flow émet Dric4rTV (~instant), puis Browser
        //   (~5-10s). La ListView se met à jour à chaque emit.
        var all: List<RadioCatalog.RadioStation> = emptyList()
        var showOnlyFavorites = false
        var currentQuery = ""
        var loadJob: Job? = null

        lifecycleOwner.lifecycleScope.launch {

            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }

            // 2026-06-08 (user "le bouton arrêter tout en haut car on doit
            //   défiler toute la liste pour pouvoir le toucher. Pareil pour
            //   fermer") : barre de boutons HORIZONTALE EN HAUT du dialog.
            val topBar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(32, 24, 32, 24)
                gravity = Gravity.CENTER_VERTICAL
            }
            val mp = MiniPlayerController
            val isPlaying = mp.isRadioChannel(mp.currentChannelId)

            // Bouton ⏹ Arrêter (visible uniquement si une radio joue)
            val stopBtn = TextView(ctx).apply {
                text = "⏹ Arrêter"
                setPadding(32, 24, 32, 24)
                textSize = 15f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.btn_default)
                visibility = if (isPlaying) View.VISIBLE else View.GONE
            }
            topBar.addView(stopBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            // Spacer pour pousser Fermer à droite
            val spacer = View(ctx)
            topBar.addView(spacer, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            ))

            // Bouton ✕ Fermer
            val closeBtn = TextView(ctx).apply {
                text = "✕ Fermer"
                setPadding(32, 24, 32, 24)
                textSize = 15f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.btn_default)
            }
            topBar.addView(closeBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            container.addView(topBar)

            // 2026-06-08 (user "le bouton recherche faut cliquer dessus au
            //   lieu d'être dessus sinon ça ouvre le clavier d'office") :
            //   focus désactivé par défaut. Au clic, on active + on demande
            //   le focus + on montre le clavier.
            // 2026-06-09 (user "la barre de recherche de la radio n'est pas
            //   focusable, je t'avais demandé de faire un bouton qu'on puisse
            //   aller dessus et cliquer pour faire une recherche, là on peut
            //   carrément plus faire une recherche") : le D-pad n'arrivait
            //   pas à se poser sur le champ (focusable=false). Maintenant :
            //   focusable=true → D-pad peut naviguer dessus, le clavier ne
            //   s'ouvre QU'au OK (KEYCODE_DPAD_CENTER / ENTER / clic souris).
            //   → focus passif sans clavier auto + OK pour taper.
            val searchInput = EditText(ctx).apply {
                hint = "🔍 Rechercher une radio…"
                setPadding(48, 32, 48, 32)
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isFocusable = true
                isFocusableInTouchMode = true
                // Ne PAS ouvrir le clavier au focus simple (sinon il s'ouvre
                //   dès l'ouverture du dialog quand D-pad pose le focus
                //   automatiquement sur le premier élément focusable).
                val openKeyboard = {
                    try {
                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    } catch (_: Throwable) {}
                }
                setOnClickListener { openKeyboard() }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN && (
                            keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        openKeyboard()
                        true
                    } else false
                }
            }
            container.addView(searchInput)

            // Toggle Toutes / Favoris
            val toggleBtn = TextView(ctx).apply {
                text = "★ Afficher uniquement les favoris"
                setPadding(48, 24, 48, 24)
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            container.addView(toggleBtn)

            fun computeVisible(): List<RadioCatalog.RadioStation> {
                var list = if (showOnlyFavorites) {
                    val favIds = RadioFavoritesStore.all()
                    all.filter { it.id in favIds }
                } else all
                if (currentQuery.isNotBlank()) {
                    val q = currentQuery.lowercase().trim()
                    list = list.filter { it.name.lowercase().contains(q) }
                }
                return list
            }

            var radios = computeVisible()

            val listView = ListView(ctx)
            val adapter = object : ArrayAdapter<RadioCatalog.RadioStation>(
                ctx, android.R.layout.simple_list_item_1, radios.toMutableList()
            ) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val v = super.getView(position, convertView, parent)
                    val item = getItem(position) ?: return v
                    val tv = v.findViewById<TextView>(android.R.id.text1)
                    val star = if (RadioFavoritesStore.isFavorite(item.id)) "★ " else ""
                    tv.text = star + item.name
                    return v
                }
            }
            listView.adapter = adapter
            container.addView(listView)

            val dialog = AlertDialog.Builder(ctx)
                .setTitle("Radios (chargement…)")
                .setView(container)
                .create()

            // Boutons du haut
            stopBtn.setOnClickListener {
                try {
                    mp.stopAsync()
                    Toast.makeText(ctx, "Radio arrêtée", Toast.LENGTH_SHORT).show()
                    stopBtn.visibility = View.GONE
                } catch (_: Throwable) {}
            }
            closeBtn.setOnClickListener {
                dialog.dismiss()
            }

            // Click → play (2026-06-08 user "quand on clique sur une radio
            //   ça ferme la page, vaut mieux que l'user la ferme lui-même")
            //   : on NE dismiss PAS le dialog. L'user peut choisir une autre
            //   radio ou cliquer ⏹/✕ Fermer en haut.
            listView.setOnItemClickListener { _, _, position, _ ->
                val r = adapter.getItem(position) ?: return@setOnItemClickListener
                try {
                    mp.initPlayer(ctx)
                    if (r.streamUrl != null) {
                        mp.playRadioDirect(r.id, r.name, r.poster, r.streamUrl, r.fallbackUrls)
                    } else {
                        mp.playChannel(r.id, r.name, r.poster)
                    }
                    Toast.makeText(ctx, "${r.name} — lecture", Toast.LENGTH_SHORT).show()
                    // Maintenant qu'une radio joue, le bouton Stop devient
                    // pertinent → on l'affiche.
                    stopBtn.visibility = View.VISIBLE
                } catch (t: Throwable) {
                    Toast.makeText(ctx, "Erreur : ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }

            // Long-press → toggle favori
            listView.setOnItemLongClickListener { _, _, position, _ ->
                val r = adapter.getItem(position) ?: return@setOnItemLongClickListener false
                val isFav = RadioFavoritesStore.toggle(r.id)
                Toast.makeText(
                    ctx,
                    "${r.name} — ${if (isFav) "ajoutée aux favoris" else "retirée des favoris"}",
                    Toast.LENGTH_SHORT
                ).show()
                adapter.notifyDataSetChanged()
                true
            }

            fun refresh() {
                radios = computeVisible()
                adapter.clear()
                adapter.addAll(radios)
                adapter.notifyDataSetChanged()
                dialog.setTitle("Radios (${radios.size})")
            }

            toggleBtn.setOnClickListener {
                showOnlyFavorites = !showOnlyFavorites
                toggleBtn.text = if (showOnlyFavorites)
                    "✕ Afficher toutes les radios"
                else
                    "★ Afficher uniquement les favoris"
                refresh()
                if (radios.isEmpty() && showOnlyFavorites) {
                    Toast.makeText(ctx,
                        "Aucun favori. Long-press sur une radio pour l'ajouter.",
                        Toast.LENGTH_LONG).show()
                }
            }

            searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentQuery = s?.toString().orEmpty()
                    refresh()
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            // Show le dialog AVANT de charger pour que l'user voit qqch
            // immédiatement (titre "chargement…").
            dialog.show()

            // 2026-06-08 : abonnement au flow progressif. 1er emit = Dric4rTV
            //   (instant ~17 radios). 2e emit = + RadioBrowser (~5-10s
            //   réseau, jusqu'à 5000 radios FR avant dedup). À chaque emit,
            //   on remplace `all` + refresh().
            loadJob = lifecycleOwner.lifecycleScope.launch {
                try {
                    RadioCatalog.loadProgressive().collect { newList ->
                        all = newList
                        refresh()
                    }
                    if (all.isEmpty()) {
                        Toast.makeText(ctx,
                            "Aucune radio disponible (vérifie la connexion)",
                            Toast.LENGTH_LONG).show()
                    }
                } catch (_: Throwable) {}
            }

            // Cancel le job de chargement quand l'user ferme le dialog
            dialog.setOnDismissListener {
                loadJob?.cancel()
            }
        }
    }
}
