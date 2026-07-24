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
import com.streamflixreborn.streamflix.providers.FileSearchProvider
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
 *  - Boutons Arrêter / Fermer EN HAUT.
 *  - Au clic, démarre le mini-bar audio.
 *
 * 2026-07-24 (user « ajouter la musique à la radio, 2e barre de recherche + favori
 *   pour faire des playlists ») : MODE MUSIQUE. Bouton bascule « 📻 Radios / 🎵 Musique ».
 *   En mode musique la barre cherche des fichiers AUDIO directs (FileSearchProvider),
 *   la lecture réutilise le mini-player (URL directe = comme une radio), le long-press
 *   range dans « Ma playlist ★ » (MusicFavoritesStore).
 */
object RadioPickerDialog {

    private fun trackToStation(t: MusicFavoritesStore.Track): RadioCatalog.RadioStation =
        RadioCatalog.RadioStation(id = "music::" + t.url, name = t.title, poster = null, streamUrl = t.url)

    private fun audioToStation(a: FileSearchProvider.AudioResult): RadioCatalog.RadioStation =
        RadioCatalog.RadioStation(id = "music::" + a.url, name = a.title, poster = null, streamUrl = a.url)

    fun show(ctx: Context, lifecycleOwner: LifecycleOwner) {
        var all: List<RadioCatalog.RadioStation> = emptyList()
        var showOnlyFavorites = false
        var currentQuery = ""
        var loadJob: Job? = null

        // Mode musique
        var musicMode = false
        var musicShuffle = false
        var musicResults: List<RadioCatalog.RadioStation> = emptyList()
        var musicSearchJob: Job? = null

        lifecycleOwner.lifecycleScope.launch {

            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }

            // Barre de boutons EN HAUT (Arrêter / Fermer)
            val topBar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(32, 24, 32, 24)
                gravity = Gravity.CENTER_VERTICAL
            }
            val mp = MiniPlayerController
            val isPlaying = mp.isRadioChannel(mp.currentChannelId)

            val stopBtn = TextView(ctx).apply {
                text = "⏹ Arrêter"
                setPadding(40, 24, 40, 24)
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#B71C1C"))
                isClickable = true
                isFocusable = true
                visibility = if (isPlaying) View.VISIBLE else View.GONE
            }
            // ⏮ Précédent / ⏭ Suivant (autour d'Arrêter). Saut de PISTE en musique, de station
            //   en radio (via MiniPlayerController.previousRadio/nextRadio).
            val prevBtn = TextView(ctx).apply {
                text = "⏮"
                setPadding(36, 24, 36, 24)
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#455A64"))
                isClickable = true
                isFocusable = true
                visibility = if (isPlaying) View.VISIBLE else View.GONE
            }
            val nextBtn = TextView(ctx).apply {
                text = "⏭"
                setPadding(36, 24, 36, 24)
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#455A64"))
                isClickable = true
                isFocusable = true
                visibility = if (isPlaying) View.VISIBLE else View.GONE
            }
            val wrap = { LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
            topBar.addView(prevBtn, wrap())
            topBar.addView(stopBtn, wrap())
            topBar.addView(nextBtn, wrap())

            prevBtn.setOnClickListener { try { mp.previousRadio() } catch (_: Throwable) {} }
            nextBtn.setOnClickListener { try { mp.nextRadio() } catch (_: Throwable) {} }

            val spacer = View(ctx)
            topBar.addView(spacer, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            ))

            val closeBtn = TextView(ctx).apply {
                text = "✕ Fermer"
                setPadding(40, 24, 40, 24)
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#455A64"))
                isClickable = true
                isFocusable = true
            }
            topBar.addView(closeBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            container.addView(topBar)

            // Bascule 📻 Radios / 🎵 Musique
            val modeBtn = TextView(ctx).apply {
                text = "🎵 Chercher de la musique"
                setPadding(48, 28, 48, 28)
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#6A1B9A"))
                isClickable = true
                isFocusable = true
            }
            container.addView(modeBtn)

            val searchInput = EditText(ctx).apply {
                hint = "🔍 Rechercher une radio…"
                setPadding(48, 32, 48, 32)
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                setTextColor(android.graphics.Color.BLACK)
                setHintTextColor(android.graphics.Color.parseColor("#666666"))
                setBackgroundColor(android.graphics.Color.WHITE)
                isFocusable = true
                isFocusableInTouchMode = true
            }
            val openKeyboard = {
                try {
                    searchInput.requestFocus()
                    searchInput.postDelayed({
                        try {
                            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                                as android.view.inputmethod.InputMethodManager
                            imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                        } catch (_: Throwable) {}
                    }, 80)
                } catch (_: Throwable) {}
            }
            // Bouton HISTORIQUE au bout de la barre blanche (🕘). Affiche les recherches récentes.
            val histBtn = TextView(ctx).apply {
                text = "🕘"
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(28, 32, 28, 32)
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#455A64"))
                isClickable = true
                isFocusable = true
            }
            val searchRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            searchRow.addView(searchInput, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            searchRow.addView(histBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            container.addView(searchRow)

            // 2026-07-24 (user TV : « des petites cases sur la largeur au lieu de barres pleine
            //   largeur, sinon ça écrase la liste, on ne voit que 2 titres ») : les actions sur UNE
            //   rangée horizontale de petites cases (mêmes couleurs), pour libérer la place aux résultats.
            fun cell(bg: String) = TextView(ctx).apply {
                setPadding(8, 22, 8, 22)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor(bg))
                isClickable = true
                isFocusable = true
            }
            val musicSearchBtn = cell("#1565C0").apply { text = "🔍 Rechercher" }
            val shuffleBtn = cell("#00695C").apply { text = "🔀 Off"; visibility = View.GONE }
            val playAllBtn = cell("#2E7D32").apply { text = "▶ Tout lire"; visibility = View.GONE }
            val toggleBtn = cell("#37474F").apply { text = "★ Favoris" }

            val actionRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            fun addCell(v: View) = actionRow.addView(
                v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(4, 4, 4, 4) },
            )
            addCell(musicSearchBtn); addCell(shuffleBtn); addCell(playAllBtn); addCell(toggleBtn)
            container.addView(actionRow)

            // Focus TV visible : liseré blanc en overlay (foreground) sur toutes les cases/boutons
            //   du dialog (fonds pleins → sinon aucune surbrillance au D-pad).
            listOf(modeBtn, searchInput, histBtn, stopBtn, prevBtn, nextBtn, closeBtn,
                musicSearchBtn, shuffleBtn, playAllBtn, toggleBtn).forEach { v ->
                v.foreground = androidx.core.content.ContextCompat.getDrawable(
                    ctx, com.streamflixreborn.streamflix.R.drawable.bg_focus_white_border,
                )
            }

            fun computeVisible(): List<RadioCatalog.RadioStation> {
                if (musicMode) {
                    if (showOnlyFavorites)
                        return MusicFavoritesStore.all().map { trackToStation(it) }
                    // Pas encore de résultats ET champ vide → afficher l'HISTORIQUE (tap = relancer).
                    if (musicResults.isEmpty() && currentQuery.isBlank()) {
                        val hist = SearchHistory.getAll(ctx, "music")
                        if (hist.isEmpty()) return emptyList()
                        val items = hist.map {
                            RadioCatalog.RadioStation(id = "hist::$it", name = "🕘 $it", poster = null, streamUrl = null)
                        }.toMutableList()
                        items.add(RadioCatalog.RadioStation(
                            id = "histclear::", name = "🗑 Effacer l'historique", poster = null, streamUrl = null))
                        return items
                    }
                    return musicResults
                }
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
                    val isFav = if (musicMode) MusicFavoritesStore.isFavorite(item.streamUrl ?: "")
                        else RadioFavoritesStore.isFavorite(item.id)
                    tv.text = (if (isFav) "★ " else "") + item.name
                    return v
                }
            }
            listView.adapter = adapter
            // Focus TV visible dans la LISTE : sélecteur liseré blanc dessiné PAR-DESSUS la ligne.
            listView.selector = androidx.core.content.ContextCompat.getDrawable(
                ctx, com.streamflixreborn.streamflix.R.drawable.bg_list_selector_tv,
            )
            listView.isDrawSelectorOnTop = true
            container.addView(listView)

            val dialog = AlertDialog.Builder(ctx)
                .setTitle("Radios (chargement…)")
                .setView(container)
                .create()

            fun refresh() {
                radios = computeVisible()
                adapter.clear()
                adapter.addAll(radios)
                adapter.notifyDataSetChanged()
                dialog.setTitle(
                    if (musicMode) {
                        if (showOnlyFavorites) "🎵 Ma playlist (${radios.size})"
                        else "🎵 Musique (${radios.size})"
                    } else "Radios (${radios.size})"
                )
            }

            stopBtn.setOnClickListener {
                try {
                    mp.stopAsync()
                    Toast.makeText(ctx, "Lecture arrêtée", Toast.LENGTH_SHORT).show()
                    stopBtn.visibility = View.GONE
                    prevBtn.visibility = View.GONE
                    nextBtn.visibility = View.GONE
                } catch (_: Throwable) {}
            }
            closeBtn.setOnClickListener { dialog.dismiss() }

            fun runMusicSearch() {
                val q = currentQuery.trim()
                if (q.isBlank()) {
                    Toast.makeText(ctx, "Tape un artiste ou un titre", Toast.LENGTH_SHORT).show()
                    return
                }
                musicSearchJob?.cancel()
                SearchHistory.add(ctx, q, "music") // historique musique (réutilise le système de l'app)
                dialog.setTitle("🎵 Recherche…")
                musicSearchJob = lifecycleOwner.lifecycleScope.launch {
                    // 2 sources : FileSearch (mp3 directs open-dirs) + NewPipe (audio YouTube = tout le reste).
                    val fs = try { FileSearchProvider.searchAudio(q) } catch (_: Throwable) { emptyList() }
                    val yt = try {
                        com.streamflixreborn.streamflix.providers.NewPipeAudio.search(q)
                    } catch (_: Throwable) { emptyList() }
                    val merged = (fs + yt).distinctBy { it.url }.take(300)
                    musicResults = merged.map { audioToStation(it) }
                    showOnlyFavorites = false
                    refresh()
                    if (musicResults.isEmpty())
                        Toast.makeText(ctx, "Aucun morceau trouvé pour « $q »", Toast.LENGTH_SHORT).show()
                }
            }

            // 2026-07-24 (bug TV : le D-pad SAUTE le champ de recherche inline du dialog →
            //   impossible de taper, RADIO comme MUSIQUE). FIX universel : une boîte de saisie
            //   DÉDIÉE avec UN SEUL champ → le focus D-pad se pose forcément dessus et le clavier
            //   à l'écran (leanback) monte. Sur OK : musique → recherche réseau ; radio → filtre.
            fun promptQuery(isMusic: Boolean) {
                val input = EditText(ctx).apply {
                    hint = if (isMusic) "Artiste ou titre…" else "Nom de la radio…"
                    setSingleLine(true)
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    isFocusable = true
                    isFocusableInTouchMode = true
                    // Contraste : texte NOIR sur fond BLANC (le thème dialog rendait blanc-sur-gris).
                    setTextColor(android.graphics.Color.BLACK)
                    setHintTextColor(android.graphics.Color.parseColor("#666666"))
                    setBackgroundColor(android.graphics.Color.WHITE)
                    setPadding(32, 28, 32, 28)
                    setText(currentQuery)
                    setSelection(text?.length ?: 0)
                }
                val apply = {
                    currentQuery = input.text?.toString().orEmpty()
                    searchInput.setText(currentQuery)
                    if (isMusic) runMusicSearch() else refresh()
                }
                val d = AlertDialog.Builder(ctx)
                    .setTitle(if (isMusic) "🎵 Rechercher de la musique" else "🔍 Rechercher une radio")
                    .setView(input)
                    .setPositiveButton("Rechercher") { _, _ -> apply() }
                    .setNegativeButton("Annuler", null)
                    .create()
                d.window?.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                input.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                        d.dismiss(); apply(); true
                    } else false
                }
                d.setOnShowListener {
                    input.requestFocus()
                    input.postDelayed({
                        try {
                            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                                as android.view.inputmethod.InputMethodManager
                            if (!imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_FORCED)) {
                                imm.toggleSoftInput(
                                    android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0)
                            }
                        } catch (_: Throwable) {}
                    }, 120)
                }
                d.show()
            }

            // Bascule de mode
            modeBtn.setOnClickListener {
                musicMode = !musicMode
                showOnlyFavorites = false
                currentQuery = ""
                searchInput.setText("")
                if (musicMode) {
                    modeBtn.text = "📻 Revenir aux radios"
                    searchInput.visibility = View.VISIBLE
                    searchInput.hint = "🔍 Artiste, titre…"
                    musicSearchBtn.text = "🔎 Rechercher"
                    shuffleBtn.visibility = View.VISIBLE
                    playAllBtn.visibility = View.VISIBLE
                    toggleBtn.text = "★ Playlist"
                } else {
                    modeBtn.text = "🎵 Chercher de la musique"
                    searchInput.visibility = View.VISIBLE
                    searchInput.hint = "🔍 Rechercher une radio…"
                    musicSearchBtn.text = "🔍 Rechercher"
                    shuffleBtn.visibility = View.GONE
                    playAllBtn.visibility = View.GONE
                    toggleBtn.text = "★ Favoris"
                }
                refresh()
            }

            musicSearchBtn.setOnClickListener {
                if (musicMode) {
                    // Texte déjà tapé (clavier inline) → recherche ; sinon (TV) boîte dédiée.
                    if (currentQuery.isNotBlank()) runMusicSearch() else promptQuery(true)
                } else promptQuery(false)
            }

            shuffleBtn.setOnClickListener {
                musicShuffle = !musicShuffle
                shuffleBtn.text = if (musicShuffle) "🔀 On" else "🔀 Off"
                mp.setMusicShuffle(musicShuffle) // s'applique aussi à une playlist déjà en cours
            }

            // ▶ Tout lire : joue toute la liste visible (résultats OU playlist) — pas besoin
            //   de mettre en favori pour lire.
            playAllBtn.setOnClickListener {
                val queue = radios.mapNotNull { st -> st.streamUrl?.let { it to st.name } }
                if (queue.isEmpty()) {
                    Toast.makeText(ctx, "Rien à lire (fais une recherche)", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                mp.initPlayer(ctx)
                mp.playMusicPlaylist(queue, 0, musicShuffle)
                Toast.makeText(ctx, "▶ Lecture de ${queue.size} morceaux${if (musicShuffle) " (aléatoire)" else ""}", Toast.LENGTH_SHORT).show()
                stopBtn.visibility = View.VISIBLE
                prevBtn.visibility = View.VISIBLE
                nextBtn.visibility = View.VISIBLE
            }

            // Taper sur la barre blanche → clavier (mobile). Sur TV le D-pad n'atteint pas le champ →
            //   le bouton bleu « Rechercher » ouvre la boîte dédiée (promptQuery).
            // Clic sur le champ blanc = clavier inline (radio ET musique — c'est ce qui marche chez
            //   le user ; l'AlertDialog promptQuery ne faisait pas monter le clavier). En musique,
            //   taper puis Entrée lance la recherche (setOnEditorActionListener / setOnKeyListener).
            searchInput.setOnClickListener { openKeyboard() }

            // Bouton HISTORIQUE (🕘 au bout de la barre) : liste les recherches musique récentes.
            fun showHistory() {
                val hist = SearchHistory.getAll(ctx, "music")
                if (hist.isEmpty()) {
                    Toast.makeText(ctx, "Aucune recherche récente", Toast.LENGTH_SHORT).show()
                    return
                }
                AlertDialog.Builder(ctx)
                    .setTitle("🕘 Recherches récentes")
                    .setItems(hist.toTypedArray()) { _, which ->
                        currentQuery = hist[which]
                        searchInput.setText(currentQuery)
                        if (musicMode) runMusicSearch() else refresh()
                    }
                    .setNegativeButton("🗑 Tout effacer") { _, _ ->
                        SearchHistory.clear(ctx, "music")
                        refresh()
                        Toast.makeText(ctx, "Historique effacé", Toast.LENGTH_SHORT).show()
                    }
                    .setPositiveButton("Fermer", null)
                    .show()
            }
            histBtn.setOnClickListener { showHistory() }
            searchInput.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && (
                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    // OK/Entrée sur le champ = OUVRIR LE CLAVIER (radio ET musique). La recherche se
                    //   lance ensuite via la touche « rechercher » du clavier (setOnEditorActionListener).
                    openKeyboard()
                    true
                } else false
            }
            searchInput.setOnEditorActionListener { _, actionId, _ ->
                if (musicMode && actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    runMusicSearch(); true
                } else false
            }

            // Click → play. Musique : lance TOUTE la liste visible en playlist (enchaînement +
            //   aléatoire) depuis la position cliquée. Radio : lecture simple de la station.
            listView.setOnItemClickListener { _, _, position, _ ->
                val r = adapter.getItem(position) ?: return@setOnItemClickListener
                try {
                    if (musicMode) {
                        // Entrées d'HISTORIQUE : relancer / effacer (pas de lecture).
                        if (r.id == "histclear::") { SearchHistory.clear(ctx, "music"); refresh(); return@setOnItemClickListener }
                        if (r.id.startsWith("hist::")) {
                            currentQuery = r.id.removePrefix("hist::")
                            searchInput.setText(currentQuery)
                            runMusicSearch()
                            return@setOnItemClickListener
                        }
                        mp.initPlayer(ctx)
                        val queue = radios.mapNotNull { st -> st.streamUrl?.let { it to st.name } }
                        if (queue.isEmpty()) return@setOnItemClickListener
                        val pos = radios.indexOfFirst { it.id == r.id }.coerceAtLeast(0)
                        mp.playMusicPlaylist(queue, pos, musicShuffle)
                    } else if (r.streamUrl != null) {
                        mp.initPlayer(ctx)
                        mp.playRadioDirect(r.id, r.name, r.poster, r.streamUrl, r.fallbackUrls)
                    } else {
                        mp.initPlayer(ctx)
                        mp.playChannel(r.id, r.name, r.poster)
                    }
                    Toast.makeText(ctx, "${r.name} — lecture", Toast.LENGTH_SHORT).show()
                    stopBtn.visibility = View.VISIBLE
                    prevBtn.visibility = View.VISIBLE
                    nextBtn.visibility = View.VISIBLE
                } catch (t: Throwable) {
                    Toast.makeText(ctx, "Erreur : ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }

            // Long-press → favori (radio) ou ajout/retrait de playlist (musique)
            listView.setOnItemLongClickListener { _, _, position, _ ->
                val r = adapter.getItem(position) ?: return@setOnItemLongClickListener false
                val added: Boolean
                val label: String
                if (musicMode) {
                    added = MusicFavoritesStore.toggle(r.streamUrl ?: "", r.name)
                    label = if (added) "ajouté à ma playlist" else "retiré de ma playlist"
                    if (showOnlyFavorites) refresh() // la vue playlist doit refléter le retrait
                } else {
                    added = RadioFavoritesStore.toggle(r.id)
                    label = if (added) "ajoutée aux favoris" else "retirée des favoris"
                }
                Toast.makeText(ctx, "${r.name} — $label", Toast.LENGTH_SHORT).show()
                adapter.notifyDataSetChanged()
                true
            }

            toggleBtn.setOnClickListener {
                showOnlyFavorites = !showOnlyFavorites
                if (musicMode) {
                    toggleBtn.text = if (showOnlyFavorites) "✕ Résultats" else "★ Playlist"
                } else {
                    toggleBtn.text = if (showOnlyFavorites) "✕ Toutes" else "★ Favoris"
                }
                refresh()
                if (radios.isEmpty() && showOnlyFavorites) {
                    Toast.makeText(ctx,
                        if (musicMode) "Playlist vide. Long-press sur un morceau pour l'ajouter."
                        else "Aucun favori. Long-press sur une radio pour l'ajouter.",
                        Toast.LENGTH_LONG).show()
                }
            }

            searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentQuery = s?.toString().orEmpty()
                    if (!musicMode) refresh() // radio = filtre live ; musique = attend la validation
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            dialog.show()

            loadJob = lifecycleOwner.lifecycleScope.launch {
                try {
                    RadioCatalog.loadProgressive().collect { newList ->
                        all = newList
                        if (!musicMode) refresh()
                    }
                    if (all.isEmpty() && !musicMode) {
                        Toast.makeText(ctx,
                            "Aucune radio disponible (vérifie la connexion)",
                            Toast.LENGTH_LONG).show()
                    }
                } catch (_: Throwable) {}
            }

            dialog.setOnDismissListener {
                loadJob?.cancel()
                musicSearchJob?.cancel()
            }
        }
    }
}
