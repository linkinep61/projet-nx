package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2026-06-08 : dialog dédié pour le picker de radios (mobile + TV).
 *
 * 2026-07-25 (user « rendre le menu radio plus joli, moins artisanal ») : REFONTE VISUELLE.
 *   Feuille sombre arrondie, en-tête avec compteur, bascule segmentée Radios/Musique, barre de
 *   recherche arrondie, actions en pastilles, carte « en lecture » (⏮ ⏹ ⏭), et lignes de station
 *   avec pastille d'initiale + nom + étoile favorite. TOUTE la logique est conservée (mode musique
 *   FileSearch+NewPipe, musiques locales, historique, favoris, aléatoire, focus télécommande TV).
 */
object RadioPickerDialog {

    // Palette (voir maquette validée par le user).
    private const val SURFACE = "#15171C"
    private const val CARD = "#20242C"
    private const val CARD_DARK = "#1B1E24"
    private const val BORDER = "#2F343E"
    private const val DIVIDER = "#23262E"
    private const val ACCENT = "#E23B3B"
    private const val STAR_ON = "#E2B33B"
    private const val STAR_OFF = "#565C66"
    private const val TXT = "#F5F6F8"
    private const val TXT2 = "#9AA0AA"
    private const val TXT_CHIP = "#DDE0E5"
    private const val AVATAR_BG = "#2A2E37"

    private fun trackToStation(t: MusicFavoritesStore.Track): RadioCatalog.RadioStation =
        RadioCatalog.RadioStation(id = "music::" + t.url, name = t.title, poster = null, streamUrl = t.url)

    private fun audioToStation(a: FileSearchProvider.AudioResult): RadioCatalog.RadioStation =
        RadioCatalog.RadioStation(id = "music::" + a.url, name = a.title, poster = null, streamUrl = a.url)

    fun show(ctx: Context, lifecycleOwner: LifecycleOwner) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun rounded(fill: String, radius: Int, stroke: String? = null, strokeW: Int = 1) =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(radius).toFloat()
                setColor(Color.parseColor(fill))
                if (stroke != null) setStroke(dp(strokeW), Color.parseColor(stroke))
            }
        fun square(fill: String, radius: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(Color.parseColor(fill))
        }
        // Le liseré de focus blanc n'a de sens qu'à la TÉLÉCOMMANDE (D-pad) : sur téléphone il
        //   dessine un vilain rectangle autour du champ/de la ligne sélectionnée. On le réserve à la TV.
        // 2026-07-29 (bug user : curseur invisible sur box RockChip) : beaucoup de box AOSP/RockChip
        //   ne se déclarent PAS TV via UiModeManager → le liseré de focus n'était jamais posé. On
        //   détecte aussi le mode leanback / l'absence d'écran tactile (= appareil piloté à la télécommande).
        val isTv = runCatching {
            val ui = (ctx.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager)
                .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
            val pm = ctx.packageManager
            ui || pm.hasSystemFeature("android.software.leanback") ||
                pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION) ||
                !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        }.getOrDefault(false)
        fun tvFocus(v: View) {
            if (isTv) v.foreground = androidx.core.content.ContextCompat.getDrawable(
                ctx, com.streamflixreborn.streamflix.R.drawable.bg_focus_white_border)
        }

        var all: List<RadioCatalog.RadioStation> = emptyList()
        var showOnlyFavorites = false
        var currentQuery = ""
        var loadJob: Job? = null

        var musicMode = false
        var musicShuffle = false
        var musicResults: List<RadioCatalog.RadioStation> = emptyList()
        var musicSearchJob: Job? = null
        var localMusicMode = false
        var localMusicDir: String? = null // dossier choisi (null = tout)

        lifecycleOwner.lifecycleScope.launch {

            val mp = MiniPlayerController
            val isPlaying = mp.isRadioChannel(mp.currentChannelId)

            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(SURFACE, 20)
                setPadding(dp(14), dp(14), dp(14), dp(10))
            }

            // ── En-tête : icône + titre + compteur + fermer ────────────────────────────
            val headerIcon = TextView(ctx).apply {
                text = "📻"; textSize = 20f
            }
            val headerTitle = TextView(ctx).apply {
                text = "Radios"; textSize = 18f
                setTextColor(Color.parseColor(TXT))
                setPadding(dp(8), 0, dp(8), 0)
            }
            val countPill = TextView(ctx).apply {
                text = "…"; textSize = 12f
                setTextColor(Color.parseColor(TXT_CHIP))
                background = rounded(AVATAR_BG, 999)
                setPadding(dp(9), dp(2), dp(9), dp(2))
            }
            val closeBtn = TextView(ctx).apply {
                text = "✕"; textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(TXT2))
                setPadding(dp(10), dp(6), dp(6), dp(6))
                isClickable = true; isFocusable = true
            }
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), 0, dp(2), dp(12))
            }
            header.addView(headerIcon)
            header.addView(headerTitle)
            header.addView(countPill)
            header.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            header.addView(closeBtn)
            container.addView(header)

            // ── Bascule segmentée Radios / Musique ─────────────────────────────────────
            fun tab(label: String) = TextView(ctx).apply {
                text = label; textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(TXT_CHIP))
                setPadding(0, dp(9), 0, dp(9))
                isClickable = true; isFocusable = true
            }
            val radiosTab = tab("📻 Radios")
            val musiqueTab = tab("🎵 Musique")
            val segment = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(CARD, 12)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            segment.addView(radiosTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            segment.addView(musiqueTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(segment, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(10) })

            fun paintSegment() {
                radiosTab.background = if (!musicMode) rounded(ACCENT, 9) else null
                musiqueTab.background = if (musicMode) rounded(ACCENT, 9) else null
                radiosTab.setTextColor(Color.parseColor(if (!musicMode) "#FFFFFF" else TXT_CHIP))
                musiqueTab.setTextColor(Color.parseColor(if (musicMode) "#FFFFFF" else TXT_CHIP))
            }
            paintSegment()

            // ── Carte « en lecture » (⏮ ⏹ ⏭) ──────────────────────────────────────────
            val nowAvatar = TextView(ctx).apply {
                textSize = 15f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = square(ACCENT, 9)
                text = "♪"
            }
            val nowName = TextView(ctx).apply {
                text = "En lecture"; textSize = 14f
                setTextColor(Color.parseColor(TXT))
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            fun ctrl(glyph: String, color: String, size: Float) = TextView(ctx).apply {
                text = glyph; textSize = size
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(color))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                isClickable = true; isFocusable = true
            }
            val prevBtn = ctrl("⏮", TXT_CHIP, 18f)
            val stopBtn = ctrl("⏹", "#FFFFFF", 18f).apply { background = rounded(ACCENT, 8); setPadding(dp(10), dp(6), dp(10), dp(6)) }
            val nextBtn = ctrl("⏭", TXT_CHIP, 18f)
            // ★ favori de la piste/station EN COURS (le nom suit l'aléatoire, cf collecte de state).
            val favNow = ctrl("☆", STAR_OFF, 20f)
            val nowCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD_DARK, 12, BORDER, 1)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                visibility = View.GONE
            }
            nowCard.addView(nowAvatar, LinearLayout.LayoutParams(dp(38), dp(38)))
            val nameWrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            nameWrap.addView(nowName)
            nowCard.addView(nameWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(12); rightMargin = dp(8) })
            nowCard.addView(prevBtn); nowCard.addView(stopBtn); nowCard.addView(nextBtn); nowCard.addView(favNow)
            container.addView(nowCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })

            // Est-ce que la piste/station en cours est en favori ? (music:: → playlist, sinon radio)
            fun currentIsFav(): Boolean {
                val id = mp.currentChannelId ?: return false
                return if (id.startsWith("music::")) MusicFavoritesStore.isFavorite(id.removePrefix("music::"))
                    else RadioFavoritesStore.isFavorite(id)
            }
            fun refreshFavNow() {
                val fav = currentIsFav()
                favNow.text = if (fav) "★" else "☆"
                favNow.setTextColor(Color.parseColor(if (fav) STAR_ON else STAR_OFF))
            }
            favNow.setOnClickListener {
                val id = mp.currentChannelId ?: return@setOnClickListener
                val name = mp.currentChannelName ?: ""
                if (id.startsWith("music::")) MusicFavoritesStore.toggle(id.removePrefix("music::"), name)
                else RadioFavoritesStore.toggle(id)
                refreshFavNow()
            }

            fun setPlaying(visible: Boolean, name: String?) {
                nowCard.visibility = if (visible) View.VISIBLE else View.GONE
                if (name != null) {
                    nowName.text = name
                    nowAvatar.text = name.trim().firstOrNull { it.isLetterOrDigit() }
                        ?.uppercaseChar()?.toString() ?: "♪"
                }
                refreshFavNow()
            }

            // ── Barre de recherche arrondie (loupe + champ + historique) ───────────────
            val searchIcon = TextView(ctx).apply {
                text = "🔍"; textSize = 15f
                setTextColor(Color.parseColor(TXT2))
                setPadding(dp(12), 0, dp(6), 0)
            }
            val searchInput = EditText(ctx).apply {
                hint = "Rechercher une radio…"
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                setTextColor(Color.parseColor(TXT))
                setHintTextColor(Color.parseColor(TXT2))
                background = null
                setPadding(0, dp(9), 0, dp(9))
                isFocusable = true; isFocusableInTouchMode = true
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
            val histBtn = TextView(ctx).apply {
                text = "🕘"; textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(TXT2))
                setPadding(dp(10), dp(6), dp(12), dp(6))
                isClickable = true; isFocusable = true
            }
            val searchRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD, 12, BORDER, 1)
            }
            searchRow.addView(searchIcon)
            searchRow.addView(searchInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            searchRow.addView(histBtn)
            container.addView(searchRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })

            // ── Pastilles d'action ─────────────────────────────────────────────────────
            fun pill(label: String) = TextView(ctx).apply {
                text = label; textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(TXT_CHIP))
                background = rounded(CARD, 10, BORDER, 1)
                setPadding(dp(6), dp(9), dp(6), dp(9))
                isClickable = true; isFocusable = true
            }
            val musicSearchBtn = pill("🔍 Rechercher")
            val shuffleBtn = pill("🔀 Off").apply { visibility = View.GONE }
            val playAllBtn = pill("▶ Tout lire").apply { visibility = View.GONE }
            val toggleBtn = pill("★ Favoris")
            val localBtn = pill("📁 Local").apply { visibility = View.GONE }

            val actionRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            fun addCell(v: View) = actionRow.addView(
                v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(dp(3), 0, dp(3), 0) },
            )
            addCell(musicSearchBtn); addCell(localBtn); addCell(shuffleBtn); addCell(playAllBtn); addCell(toggleBtn)
            container.addView(actionRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })

            // Focus TÉLÉCOMMANDE uniquement (sur téléphone → pas de rectangle blanc).
            //   favNow = étoile favori de la carte « en cours » (doit être atteignable au D-pad TV).
            listOf(radiosTab, musiqueTab, searchInput, histBtn, closeBtn, stopBtn, prevBtn, nextBtn,
                favNow, musicSearchBtn, localBtn, shuffleBtn, playAllBtn, toggleBtn).forEach { tvFocus(it) }

            fun computeVisible(): List<RadioCatalog.RadioStation> {
                if (musicMode) {
                    if (showOnlyFavorites)
                        return MusicFavoritesStore.all().map { trackToStation(it) }
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

            // ── Liste : lignes pastille + nom + étoile ─────────────────────────────────
            val listView = ListView(ctx)
            val adapter = object : ArrayAdapter<RadioCatalog.RadioStation>(
                ctx, 0, radios.toMutableList()
            ) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val item = getItem(position)
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(4), dp(10), dp(4), dp(10))
                    }
                    item ?: return row
                    val isHist = item.id.startsWith("hist")
                    val isFav = if (musicMode) MusicFavoritesStore.isFavorite(item.streamUrl ?: "")
                        else RadioFavoritesStore.isFavorite(item.id)
                    val letter = item.name.trim().firstOrNull { it.isLetterOrDigit() }
                        ?.uppercaseChar()?.toString() ?: "•"
                    val avatar = TextView(ctx).apply {
                        text = letter; textSize = 14f; gravity = Gravity.CENTER
                        setTextColor(Color.parseColor(if (isFav) "#FFFFFF" else TXT_CHIP))
                        background = square(if (isFav) ACCENT else AVATAR_BG, 8)
                    }
                    val name = TextView(ctx).apply {
                        text = item.name.removePrefix("🕘 ")
                        textSize = 15f
                        setTextColor(Color.parseColor("#EAECEF"))
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    }
                    row.addView(avatar, LinearLayout.LayoutParams(dp(34), dp(34)))
                    row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { leftMargin = dp(12); rightMargin = dp(8) })
                    if (!isHist) {
                        val star = TextView(ctx).apply {
                            text = if (isFav) "★" else "☆"; textSize = 20f
                            setTextColor(Color.parseColor(if (isFav) STAR_ON else STAR_OFF))
                            setPadding(dp(8), dp(2), dp(4), dp(2))
                            isFocusable = false; isClickable = true
                        }
                        star.setOnClickListener {
                            if (musicMode) MusicFavoritesStore.toggle(item.streamUrl ?: "", item.name)
                            else RadioFavoritesStore.toggle(item.id)
                            if (musicMode && showOnlyFavorites) { radios = computeVisible(); clear(); addAll(radios) }
                            notifyDataSetChanged()
                        }
                        row.addView(star)
                    }
                    return row
                }
            }
            listView.adapter = adapter
            listView.divider = ColorDrawable(Color.parseColor(DIVIDER))
            listView.dividerHeight = dp(1)
            // Sélecteur liseré blanc réservé à la TV (D-pad) ; sur téléphone → sélecteur par défaut.
            if (isTv) {
                listView.selector = androidx.core.content.ContextCompat.getDrawable(
                    ctx, com.streamflixreborn.streamflix.R.drawable.bg_list_selector_tv,
                )
                listView.isDrawSelectorOnTop = true
            } else {
                listView.selector = ColorDrawable(Color.TRANSPARENT)
            }
            container.addView(listView)

            val dialog = AlertDialog.Builder(ctx)
                .setView(container)
                .create()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            fun refresh() {
                radios = computeVisible()
                adapter.clear()
                adapter.addAll(radios)
                adapter.notifyDataSetChanged()
                headerIcon.text = if (musicMode) "🎵" else "📻"
                headerTitle.text = when {
                    musicMode && showOnlyFavorites -> "Ma playlist"
                    musicMode -> "Musique"
                    else -> "Radios"
                }
                countPill.text = radios.size.toString()
            }

            setPlaying(isPlaying, null)

            stopBtn.setOnClickListener {
                try {
                    mp.stopAsync()
                    Toast.makeText(ctx, "Lecture arrêtée", Toast.LENGTH_SHORT).show()
                    setPlaying(false, null)
                } catch (_: Throwable) {}
            }
            prevBtn.setOnClickListener { try { mp.previousRadio() } catch (_: Throwable) {} }
            nextBtn.setOnClickListener { try { mp.nextRadio() } catch (_: Throwable) {} }
            closeBtn.setOnClickListener { dialog.dismiss() }

            fun runMusicSearch() {
                val q = currentQuery.trim()
                if (q.isBlank()) {
                    Toast.makeText(ctx, "Tape un artiste ou un titre", Toast.LENGTH_SHORT).show()
                    return
                }
                musicSearchJob?.cancel()
                SearchHistory.add(ctx, q, "music")
                headerTitle.text = "Recherche…"
                musicSearchJob = lifecycleOwner.lifecycleScope.launch {
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

            fun promptQuery(isMusic: Boolean) {
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(SURFACE, 20)
                    setPadding(dp(18), dp(18), dp(18), dp(14))
                }
                box.addView(TextView(ctx).apply {
                    text = if (isMusic) "🎵 Rechercher de la musique" else "🔍 Rechercher une radio"
                    textSize = 17f
                    setTextColor(Color.parseColor(TXT))
                    setPadding(0, 0, 0, dp(14))
                })
                val input = EditText(ctx).apply {
                    hint = if (isMusic) "Artiste ou titre…" else "Nom de la radio…"
                    setSingleLine(true)
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    isFocusable = true; isFocusableInTouchMode = true
                    setTextColor(Color.parseColor(TXT))
                    setHintTextColor(Color.parseColor(TXT2))
                    background = rounded(CARD, 12, BORDER, 1)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setText(currentQuery)
                    setSelection(text?.length ?: 0)
                }
                box.addView(input, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                val cancelBtn = TextView(ctx).apply {
                    text = "Annuler"; textSize = 14f; gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(TXT_CHIP))
                    background = rounded(CARD, 10, BORDER, 1)
                    setPadding(dp(20), dp(10), dp(20), dp(10))
                    isClickable = true; isFocusable = true
                }
                val okBtn = TextView(ctx).apply {
                    text = "Rechercher"; textSize = 14f; gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = rounded(ACCENT, 10)
                    setPadding(dp(20), dp(10), dp(20), dp(10))
                    isClickable = true; isFocusable = true
                }
                val btnRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                btnRow.addView(cancelBtn)
                btnRow.addView(okBtn, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(10) })
                box.addView(btnRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) })

                listOf(input, cancelBtn, okBtn).forEach { tvFocus(it) }

                val dq = AlertDialog.Builder(ctx).setView(box).create()
                dq.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                dq.window?.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                val apply = {
                    currentQuery = input.text?.toString().orEmpty()
                    searchInput.setText(currentQuery)
                    dq.dismiss()
                    if (isMusic) runMusicSearch() else refresh()
                }
                cancelBtn.setOnClickListener { dq.dismiss() }
                okBtn.setOnClickListener { apply() }
                input.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { apply(); true } else false
                }
                dq.setOnShowListener {
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
                dq.show()
            }

            // Bascule de mode (les deux onglets appellent setMode).
            fun setMode(music: Boolean) {
                musicMode = music
                showOnlyFavorites = false
                currentQuery = ""
                searchInput.setText("")
                localMusicMode = false
                if (musicMode) {
                    searchInput.hint = "Artiste, titre…"
                    musicSearchBtn.text = "🔎 Rechercher"
                    shuffleBtn.visibility = View.VISIBLE
                    playAllBtn.visibility = View.VISIBLE
                    localBtn.visibility = View.VISIBLE
                    toggleBtn.text = "★ Playlist"
                } else {
                    searchInput.hint = "Rechercher une radio…"
                    musicSearchBtn.text = "🔍 Rechercher"
                    shuffleBtn.visibility = View.GONE
                    playAllBtn.visibility = View.GONE
                    localBtn.visibility = View.GONE
                    toggleBtn.text = "★ Favoris"
                }
                paintSegment()
                refresh()
            }
            radiosTab.setOnClickListener { if (musicMode) setMode(false) }
            musiqueTab.setOnClickListener { if (!musicMode) setMode(true) }

            fun loadLocalMusic(query: String? = null, dir: String? = localMusicDir) {
                val store = com.streamflixreborn.streamflix.utils.LocalMediaStore
                localMusicDir = dir
                headerTitle.text = "Médiathèque…"
                lifecycleOwner.lifecycleScope.launch {
                    val raw = withContext(Dispatchers.IO) { store.listAudio(ctx, query) }
                    // Filtre sur le dossier choisi (le sous-dossier ET ses sous-dossiers).
                    val items = if (dir.isNullOrBlank()) raw
                        else raw.filter { it.dir == dir || it.dir.startsWith("$dir/") }
                    musicResults = items.map {
                        audioToStation(
                            FileSearchProvider.AudioResult(
                                title = it.title,
                                url = it.uri,
                                size = it.subtitle.ifBlank { "Local" },
                            ),
                        )
                    }
                    showOnlyFavorites = false
                    localMusicMode = true
                    searchInput.hint = "Chercher en local"
                    refresh()
                    Toast.makeText(
                        ctx,
                        if (musicResults.isEmpty()) {
                            if (query.isNullOrBlank()) "Aucune musique locale trouvée"
                            else "Aucune musique « $query » en local"
                        } else "${musicResults.size} musiques locales",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }

            // 2026-07-28 : au lieu de charger TOUTE la musique de l'appareil, on ouvre un sélecteur
            //   de DOSSIER (avec favoris) → on ne lit que le dossier voulu.
            fun openLocalMusic() {
                com.streamflixreborn.streamflix.utils.LocalMusicFolderPicker.show(ctx, lifecycleOwner) { dir ->
                    loadLocalMusic(currentQuery.ifBlank { null }, dir)
                }
            }
            localBtn.setOnClickListener {
                val store = com.streamflixreborn.streamflix.utils.LocalMediaStore
                if (!store.hasPermission(ctx, audio = true)) {
                    com.streamflixreborn.streamflix.activities.LocalMediaPermissionActivity.request(
                        ctx, store.requiredPermissions(audio = true),
                    ) { _ ->
                        // Re-vérifie la perm AUDIO précise (on demande désormais audio+vidéo
                        //   ensemble ; un refus de la vidéo ne doit pas bloquer la musique accordée).
                        if (store.hasPermission(ctx, audio = true)) openLocalMusic()
                        else Toast.makeText(ctx, "Accès aux musiques refusé", Toast.LENGTH_SHORT).show()
                    }
                    return@setOnClickListener
                }
                openLocalMusic()
            }

            musicSearchBtn.setOnClickListener {
                if (musicMode) {
                    localMusicMode = false
                    searchInput.hint = "Artiste, titre… (web)"
                    if (currentQuery.isNotBlank()) runMusicSearch() else promptQuery(true)
                } else promptQuery(false)
            }

            shuffleBtn.setOnClickListener {
                musicShuffle = !musicShuffle
                shuffleBtn.text = if (musicShuffle) "🔀 On" else "🔀 Off"
                mp.setMusicShuffle(musicShuffle)
            }

            playAllBtn.setOnClickListener {
                val queue = radios.mapNotNull { st -> st.streamUrl?.let { it to st.name } }
                if (queue.isEmpty()) {
                    Toast.makeText(ctx, "Rien à lire (fais une recherche)", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                mp.initPlayer(ctx)
                mp.playMusicPlaylist(queue, 0, musicShuffle)
                Toast.makeText(ctx, "▶ Lecture de ${queue.size} morceaux${if (musicShuffle) " (aléatoire)" else ""}", Toast.LENGTH_SHORT).show()
                setPlaying(true, "Ma sélection")
            }

            searchInput.setOnClickListener { openKeyboard() }

            fun showHistory() {
                val hist = SearchHistory.getAll(ctx, "music")
                if (hist.isEmpty()) {
                    Toast.makeText(ctx, "Aucune recherche récente", Toast.LENGTH_SHORT).show()
                    return
                }
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(SURFACE, 20)
                    setPadding(dp(18), dp(18), dp(18), dp(14))
                }
                box.addView(TextView(ctx).apply {
                    text = "🕘 Recherches récentes"; textSize = 17f
                    setTextColor(Color.parseColor(TXT)); setPadding(0, 0, 0, dp(10))
                })
                val hd = AlertDialog.Builder(ctx).setView(box).create()
                hd.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

                val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                hist.forEach { h ->
                    val row = TextView(ctx).apply {
                        text = "🕘  $h"; textSize = 15f
                        setTextColor(Color.parseColor("#EAECEF"))
                        setPadding(dp(6), dp(11), dp(6), dp(11))
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        isClickable = true; isFocusable = true
                        setOnClickListener {
                            currentQuery = h
                            searchInput.setText(currentQuery)
                            hd.dismiss()
                            if (musicMode) runMusicSearch() else refresh()
                        }
                    }
                    tvFocus(row)
                    listBox.addView(row)
                    listBox.addView(View(ctx).apply { setBackgroundColor(Color.parseColor(DIVIDER)) },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
                }
                val scroll = android.widget.ScrollView(ctx)
                scroll.addView(listBox)
                box.addView(scroll, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(260)))

                val clearBtn = TextView(ctx).apply {
                    text = "🗑 Tout effacer"; textSize = 14f; gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(TXT_CHIP))
                    background = rounded(CARD, 10, BORDER, 1)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    isClickable = true; isFocusable = true
                }
                val closeH = TextView(ctx).apply {
                    text = "Fermer"; textSize = 14f; gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = rounded(ACCENT, 10)
                    setPadding(dp(20), dp(10), dp(20), dp(10))
                    isClickable = true; isFocusable = true
                }
                val btnRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                }
                btnRow.addView(clearBtn)
                btnRow.addView(closeH, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(10) })
                box.addView(btnRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) })
                listOf(clearBtn, closeH).forEach { tvFocus(it) }
                clearBtn.setOnClickListener {
                    SearchHistory.clear(ctx, "music"); refresh(); hd.dismiss()
                    Toast.makeText(ctx, "Historique effacé", Toast.LENGTH_SHORT).show()
                }
                closeH.setOnClickListener { hd.dismiss() }
                hd.show()
            }
            histBtn.setOnClickListener { showHistory() }
            searchInput.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && (
                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    openKeyboard()
                    true
                } else false
            }
            searchInput.setOnEditorActionListener { _, actionId, _ ->
                if (musicMode && actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    if (localMusicMode) loadLocalMusic(currentQuery) else runMusicSearch()
                    true
                } else false
            }

            listView.setOnItemClickListener { _, _, position, _ ->
                val r = adapter.getItem(position) ?: return@setOnItemClickListener
                try {
                    if (musicMode) {
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
                    setPlaying(true, r.name)
                } catch (t: Throwable) {
                    Toast.makeText(ctx, "Erreur : ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }

            listView.setOnItemLongClickListener { _, _, position, _ ->
                val r = adapter.getItem(position) ?: return@setOnItemLongClickListener false
                if (r.id.startsWith("hist")) return@setOnItemLongClickListener false
                val added: Boolean
                val label: String
                if (musicMode) {
                    added = MusicFavoritesStore.toggle(r.streamUrl ?: "", r.name)
                    label = if (added) "ajouté à ma playlist" else "retiré de ma playlist"
                    if (showOnlyFavorites) refresh()
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
                        if (musicMode) "Playlist vide. Appui long sur un morceau pour l'ajouter."
                        else "Aucun favori. Appui long sur une radio pour l'ajouter.",
                        Toast.LENGTH_LONG).show()
                }
            }

            searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentQuery = s?.toString().orEmpty()
                    if (!musicMode) refresh()
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            dialog.show()

            // 2026-07-25 (user « en aléatoire, afficher le nom au lieu de Ma sélection ») : la carte
            //   « en cours » suit le nom de la piste jouée (le mini-player émet State.Playing à chaque
            //   transition, aléatoire inclus) et rafraîchit l'étoile favori.
            lifecycleOwner.lifecycleScope.launch {
                MiniPlayerController.state.collect { st ->
                    if (st is MiniPlayerController.State.Playing) setPlaying(true, st.channelName)
                }
            }

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
