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
import kotlinx.coroutines.async
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

    /**
     * 2026-08-02 : un favori peut désormais être un ARTISTE ou un ALBUM, pas seulement un titre.
     * Ces deux-là n'ont pas d'URL de flux : l'« url » stockée est en fait leur identifiant
     * (`zfartist::…` / `zfalbum::…`). On le restitue tel quel comme id de ligne, sans streamUrl,
     * pour qu'un clic redescende dans le niveau au lieu de tenter une lecture impossible.
     */
    /**
     * Clé de favori d'une ligne musique.
     *
     * ⚠ 2026-08-02 (bug user : « si je mets l'artiste Imagine Dragons en favori, ça met carrément
     * une dizaine d'artistes ») : un artiste et un album n'ont PAS de `streamUrl`. Le code se
     * rabattait sur `""` → tous partageaient la même clé, donc mettre l'un en favori allumait
     * l'étoile de tous les autres. On retombe désormais sur l'identifiant de la ligne, qui est
     * unique (`zfartist::473`, `zfalbum::81491`…). Chaîne vide = ligne non favorisable.
     */
    private fun cleFavori(s: RadioCatalog.RadioStation): String =
        s.streamUrl
            ?: s.id.takeIf { it.startsWith("zfartist::") || it.startsWith("zfalbum::") }
            ?: ""

    private fun trackToStation(t: MusicFavoritesStore.Track): RadioCatalog.RadioStation {
        val estEnsemble = t.url.startsWith("zfartist::") || t.url.startsWith("zfalbum::")
        return RadioCatalog.RadioStation(
            id = if (estEnsemble) t.url else "music::" + t.url,
            name = t.title,
            poster = null,
            streamUrl = if (estEnsemble) null else t.url,
        )
    }

    // 2026-08-02 : `poster` = pochette d'album quand la source en fournit une (ZeffyrMusic).
    //   Pour un titre isolé (FileSearch / NewPipe) elle reste null → pastille d'initiale, comme
    //   avant. La présence d'une pochette indique donc « album complet ».
    private fun audioToStation(a: FileSearchProvider.AudioResult): RadioCatalog.RadioStation =
        RadioCatalog.RadioStation(id = "music::" + a.url, name = a.title, poster = a.thumbnail, streamUrl = a.url)

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
        /**
         * Liseré de focus (télécommande) + RETOUR D'APPUI.
         *
         * 2026-08-02 (user : « une action comme quoi on a bien appuyé sur le bouton, peu importe
         * l'endroit où on clique dans le système de la radio ») : sans retour, un appui sur une
         * action lente (recherche réseau) donne l'impression que rien ne s'est passé. La touche
         * s'enfonce donc légèrement et vibre brièvement, dès le doigt posé — pas à la fin de
         * l'action. Le listener rend `false` : il observe sans consommer, les `setOnClickListener`
         * existants continuent de fonctionner à l'identique.
         */
        fun tvFocus(v: View) {
            if (isTv) v.foreground = androidx.core.content.ContextCompat.getDrawable(
                ctx, com.streamflixreborn.streamflix.R.drawable.bg_focus_white_border)
            v.isHapticFeedbackEnabled = true
            v.setOnTouchListener { vue, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        vue.animate().scaleX(0.93f).scaleY(0.93f).alpha(0.72f)
                            .setDuration(70).start()
                        runCatching {
                            vue.performHapticFeedback(
                                android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                            )
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        vue.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110).start()
                }
                false // n'intercepte pas : le clic normal suit son cours
            }
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

        // 2026-08-02 (user : « fais-le en 3 profondeurs : on cherche → l'artiste avec sa jaquette →
        //   ses albums → ses musiques », et « comme ça ça ne sature pas la limite de musiques
        //   affichées ») : pile de navigation de la partie MUSIQUE. Chaque entrée mémorise
        //   l'écran quitté (titre d'en-tête + liste) pour que « ⬅ Retour » revienne exactement
        //   où on était, sans relancer de recherche réseau.
        val pileMusique = ArrayDeque<Pair<String, List<RadioCatalog.RadioStation>>>()

        /**
         * 2026-08-04 (bug user : « je clique sur un album et je me retrouve sur la page où je
         * venais de faire la recherche ; c'est pas à chaque fois mais ça peut arriver »).
         *
         * Numéro de la recherche en cours. Les sources répondent en parallèle et chacune
         * réécrit la liste à son arrivée ; sans ce jeton, la réponse d'une recherche abandonnée
         * — ou arrivée APRÈS que l'utilisateur soit descendu dans un artiste — venait écraser
         * l'écran affiché. Toute réponse portant un numéro périmé est ignorée.
         */
        var generationRecherche = 0


        // Titre du niveau courant (nom d'artiste, d'album, état de recherche…). Quand il est
        //   renseigné il prime sur l'en-tête générique « Musique » — sinon `refresh()` l'écraserait
        //   à chaque rafraîchissement et on perdrait le repère de navigation.
        var titreNiveau: String? = null

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
            // 2026-08-02 (user : « tu aurais pu rester dans la simple recherche et en priorité
            //   mettre les albums, ensuite les musiques, pour éviter d'avoir une icône
            //   supplémentaire ») : PAS de bouton « Albums » dédié. Les albums apparaissent
            //   directement EN TÊTE des résultats de recherche (voir runMusicSearch).

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
                        // `filter` : purge défensive de l'entrée à clé vide qu'a pu créer le bug
                        //   de favori décrit sur `cleFavori` — sinon elle reste affichée à vie.
                        return MusicFavoritesStore.all()
                            .filter { it.url.isNotBlank() }
                            .map { trackToStation(it) }
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
                    val cle = cleFavori(item)
                    val isFav = if (musicMode) cle.isNotEmpty() && MusicFavoritesStore.isFavorite(cle)
                        else RadioFavoritesStore.isFavorite(item.id)
                    val letter = item.name.trim().firstOrNull { it.isLetterOrDigit() }
                        ?.uppercaseChar()?.toString() ?: "•"
                    // 2026-08-02 (user : « affiche les jaquettes à la place du petit carré…
                    //   comme ça quand il y aura une pochette d'album on saura que derrière
                    //   c'est un album complet, pas une simple musique ») :
                    //   si l'item porte une pochette (= piste issue d'un ALBUM ZeffyrMusic),
                    //   on l'affiche à la place de la pastille d'initiale. Sinon → pastille
                    //   habituelle, donc AUCUN changement pour les titres isolés
                    //   (FileSearch / NewPipe / radios / musiques locales).
                    val pochette = item.poster?.takeIf { it.isNotBlank() }
                    val avatar: View = if (pochette != null) {
                        android.widget.ImageView(ctx).apply {
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            clipToOutline = true
                            background = square(AVATAR_BG, 8)
                            outlineProvider = object : android.view.ViewOutlineProvider() {
                                override fun getOutline(v: View, o: android.graphics.Outline) {
                                    o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat())
                                }
                            }
                            runCatching {
                                com.bumptech.glide.Glide.with(ctx)
                                    .load(pochette)
                                    .centerCrop()
                                    .into(this)
                            }
                        }
                    } else {
                        TextView(ctx).apply {
                            text = letter; textSize = 14f; gravity = Gravity.CENTER
                            setTextColor(Color.parseColor(if (isFav) "#FFFFFF" else TXT_CHIP))
                            background = square(if (isFav) ACCENT else AVATAR_BG, 8)
                        }
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
                            if (musicMode) {
                                if (cle.isEmpty()) return@setOnClickListener // ligne non favorisable
                                MusicFavoritesStore.toggle(cle, item.name)
                            } else RadioFavoritesStore.toggle(item.id)
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
                    musicMode && titreNiveau != null -> titreNiveau!!
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

            // ── Navigation à 3 profondeurs (recherche → artiste → album) ───────────────
            //   Une seule liste à l'écran à la fois : on n'empile pas des centaines de titres,
            //   on descend d'un cran. La ligne « ⬅ Retour » (id `zfback::`) dépile.
            fun albumEnLigne(alb: com.streamflixreborn.streamflix.providers.ZeffyrMusicProvider.Album):
                RadioCatalog.RadioStation {
                val detail = listOfNotNull(
                    alb.artiste.takeIf { it.isNotBlank() },
                    alb.nbPistes.takeIf { it > 0 }?.let { "$it titres" },
                ).joinToString(" ")
                return RadioCatalog.RadioStation(
                    id = "zfalbum::${alb.id}",
                    name = if (detail.isBlank()) "💿 ${alb.titre}" else "💿 ${alb.titre} — $detail",
                    poster = alb.pochette,
                    streamUrl = null,
                )
            }

            fun ligneRetour() = RadioCatalog.RadioStation(
                id = "zfback::", name = "⬅ Retour", poster = null, streamUrl = null)

            fun ouvrirNiveau(titre: String, contenu: List<RadioCatalog.RadioStation>) {
                pileMusique.addLast((titreNiveau ?: "Musique") to musicResults)
                musicResults = listOf(ligneRetour()) + contenu
                showOnlyFavorites = false
                titreNiveau = titre
                refresh()
            }

            /** Remonte d'un cran. `true` s'il y avait bien un niveau à quitter. */
            fun remonterNiveau(): Boolean {
                val precedent = pileMusique.removeLastOrNull() ?: return false
                musicResults = precedent.second
                titreNiveau = precedent.first
                refresh()
                return true
            }

            fun runMusicSearch() {
                val q = currentQuery.trim()
                if (q.isBlank()) {
                    Toast.makeText(ctx, "Tape un artiste ou un titre", Toast.LENGTH_SHORT).show()
                    return
                }
                musicSearchJob?.cancel()
                SearchHistory.add(ctx, q, "music")
                pileMusique.clear()
                showOnlyFavorites = false

                // 2026-08-02 (user : « affiche les résultats progressivement au fur et à mesure
                //   qu'ils arrivent au lieu d'un bloc d'un coup… ça éviterait l'attente »).
                //   Les sources sont interrogées EN PARALLÈLE et chacune s'affiche dès qu'elle
                //   répond ; la liste est RE-TRIÉE à chaque arrivée dans l'ordre voulu :
                //   ARTISTES puis ALBUMS puis TITRES. La plus rapide remplit donc l'écran tout
                //   de suite, les autres viennent s'insérer à leur place sans tout réordonner.
                var lignesArtistes: List<RadioCatalog.RadioStation> = emptyList()
                var lignesAlbums: List<RadioCatalog.RadioStation> = emptyList()
                val titresBruts = mutableListOf<FileSearchProvider.AudioResult>()
                var enCours = true

                // ⚠ 2026-08-04 — DEUX GARDE-FOUS AVANT TOUT RAFRAÎCHISSEMENT. Ne pas les retirer.
                //   1. NUMÉRO DE RECHERCHE : une recherche plus récente invalide les réponses
                //      de la précédente, qui sinon reviendraient s'afficher par-dessus.
                //   2. PILE DE NAVIGATION : dès que l'utilisateur est descendu dans un artiste
                //      ou un album, on ne touche plus à la liste. C'était la cause du retour
                //      intempestif à l'écran de recherche — la source la plus lente répondait
                //      après le clic et réécrivait `musicResults`.
                val gen = ++generationRecherche
                fun recomposer() {
                    if (gen != generationRecherche || pileMusique.isNotEmpty()) return
                    val titres = titresBruts.distinctBy { it.url }.take(300).map { audioToStation(it) }
                    musicResults = lignesArtistes + lignesAlbums + titres
                    refresh()
                }

                // Animation d'attente : tant qu'une source travaille, l'en-tête tourne. Sans ce
                //   repère visuel, une recherche lente passe pour une application figée.
                val roue = charArrayOf('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')
                var tick = 0
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val animation = object : Runnable {
                    override fun run() {
                        if (!enCours || gen != generationRecherche) return
                        titreNiveau = "${roue[tick % roue.size]} Recherche « $q »…"
                        headerTitle.text = titreNiveau
                        tick++
                        handler.postDelayed(this, 90L)
                    }
                }
                handler.post(animation)

                // 2026-08-04 (user : « pour optimiser la recherche, j'aimerais qu'en priorité
                //   ce soit l'artiste avec ses albums qui soient trouvés, et ensuite seulement
                //   on utilise FileSearch ou NewPipe ») — les quatre sources partaient jusqu'ici
                //   toutes en même temps. Elles sont désormais ORDONNÉES EN TROIS ÉTAPES :
                //     1. artiste + albums (les deux en parallèle entre eux) — c'est ce qu'on
                //        veut voir en premier, et ce sont les requêtes les plus légères ;
                //     2. les titres ZeffyrMusic, mieux qualifiés (album, durée) ;
                //     3. le tout-venant FileSearch puis NewPipe, de loin les plus lents.
                //   Bénéfice secondaire : on ne lance plus quatre requêtes réseau simultanées,
                //   donc les deux premières répondent plus vite.
                musicSearchJob = lifecycleOwner.lifecycleScope.launch {
                    fun terminer() {
                        if (gen != generationRecherche) return
                        enCours = false
                        handler.removeCallbacks(animation)
                        if (pileMusique.isNotEmpty()) return   // l'utilisateur a navigué ailleurs
                        titreNiveau = if (musicResults.isEmpty())
                            "Aucun résultat pour « $q »" else "Résultats « $q »"
                        headerTitle.text = titreNiveau
                        if (musicResults.isEmpty())
                            Toast.makeText(ctx, "Aucun morceau trouvé pour « $q »", Toast.LENGTH_SHORT).show()
                    }

                    // ── ÉTAPE 1 — ARTISTES + ALBUMS (prioritaires) ──────────────────────
                    val artistesD = async {
                        try { com.streamflixreborn.streamflix.providers.ZeffyrMusicProvider.searchArtists(q) }
                        catch (_: Throwable) { emptyList() }
                    }
                    val albumsD = async {
                        try { com.streamflixreborn.streamflix.providers.ZeffyrMusicProvider.searchAlbums(q) }
                        catch (_: Throwable) { emptyList() }
                    }
                    lignesArtistes = artistesD.await().map { art ->
                        RadioCatalog.RadioStation(
                            id = "zfartist::${art.id}",
                            name = "🎤 ${art.nom}",
                            poster = art.image,
                            streamUrl = null,
                        )
                    }
                    // 2026-08-14 (user : « fais en sorte qu'ils ramènent les albums plus
                    //   rapidement ») : on affiche les artistes DÈS qu'ils arrivent, sans
                    //   attendre les albums. Avant, un seul affichage après les deux : l'écran
                    //   restait vide le temps de la plus lente des deux requêtes.
                    recomposer()
                    lignesAlbums = albumsD.await().map { alb -> albumEnLigne(alb) }
                    recomposer()

                    // ── ÉTAPE 2 — titres ZeffyrMusic ────────────────────────────────────
                    val zf = try {
                        com.streamflixreborn.streamflix.providers.ZeffyrMusicProvider.searchAudio(q)
                    } catch (_: Throwable) { emptyList() }
                    titresBruts.addAll(0, zf)
                    recomposer()

                    // ── ÉTAPE 3 — (supprimée) ───────────────────────────────────────────
                    // 2026-08-14 (user : « je voudrais enlever FileSearch de l'équation, il
                    //   rapporte des musiques mais la plupart sont brouillons ; je vais
                    //   uniquement garder le site web qu'il y a en place ») : FileSearch ne
                    //   participe donc plus à la recherche MUSIQUE. Il rendait des fichiers
                    //   d'annuaires ouverts, mal nommés et sans album ni durée, et c'était la
                    //   requête la plus lente après NewPipe → la recherche est aussi plus vive.
                    //   ⚠ FileSearchProvider RESTE utilisé ailleurs (sauvegardes, lecture
                    //   d'un résultat déjà en favori) : ne pas le retirer du projet.

                    // ── ÉTAPE 4 — NewPipe, EN DERNIER RECOURS SEULEMENT ─────────────────
                    // 2026-08-04 (user : « la recherche NewPipe, je pense que le dernier site
                    //   qu'on a trouvé est le meilleur, avec FileSearch »). Il a raison sur la
                    //   RECHERCHE : ZeffyrMusic est éditorialisé (albums complets, ordonnés,
                    //   durées) et FileSearch couvre le reste ; NewPipe est de loin le plus lent
                    //   et n'ajoute quelque chose que sur ce que les deux autres n'ont pas.
                    //   On ne l'interroge donc plus que si la récolte est maigre.
                    //
                    // ⚠⚠ NE PAS EN DÉDUIRE QU'ON PEUT RETIRER NewPipeAudio DU PROJET.
                    //   Il a un SECOND rôle, lui indispensable : ZeffyrMusic n'héberge aucun
                    //   son (son lecteur est une iframe YouTube, son API ne renvoie que des
                    //   identifiants YouTube → liens `watch?v=`). C'est `resolveAudioUrl` qui
                    //   les transforme en flux jouable à la lecture. Sans NewPipe, plus AUCUNE
                    //   piste ZeffyrMusic ne se lit. Seule sa RECHERCHE est facultative.
                    //   Décision user : on ne l'interroge QUE si les deux autres n'ont RIEN
                    //   rendu. Un seuil intermédiaire avait été envisagé puis écarté — autant
                    //   ne payer sa lenteur que lorsqu'il n'y a rien d'autre à montrer.
                    if (titresBruts.isEmpty()) {
                        val yt = try {
                            com.streamflixreborn.streamflix.providers.NewPipeAudio.search(q)
                        } catch (_: Throwable) { emptyList() }
                        titresBruts.addAll(yt)
                        recomposer()
                    }
                    terminer()
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
                // On repart d'un écran vierge : la pile de navigation d'un ancien artiste/album
                //   n'aurait plus de sens dans l'autre mode.
                pileMusique.clear()
                titreNiveau = null
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

            // 2026-08-02 (user : « ce qui manque c'est la possibilité de faire retour SANS
            //   recommencer la recherche ») : le bouton RETOUR (téléphone ou télécommande)
            //   remonte d'un niveau — album → artiste → résultats — en réutilisant les listes
            //   déjà chargées, donc sans le moindre appel réseau. Il ne referme la fenêtre
            //   qu'une fois revenu au premier écran.
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                    event.action == android.view.KeyEvent.ACTION_UP
                ) remonterNiveau() else false
            }
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

            listView.setOnItemClickListener { _, vueLigne, position, _ ->
                val r = adapter.getItem(position) ?: return@setOnItemClickListener
                // Retour d'appui sur la ligne elle-même (cf. `tvFocus`) : ouvrir un artiste ou un
                //   album demande un aller-retour réseau, il faut voir que le clic a été pris.
                runCatching {
                    vueLigne?.performHapticFeedback(
                        android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                    )
                    vueLigne?.animate()?.alpha(0.55f)?.setDuration(70)?.withEndAction {
                        vueLigne.animate().alpha(1f).setDuration(140).start()
                    }?.start()
                }
                try {
                    if (musicMode) {
                        if (r.id == "histclear::") { SearchHistory.clear(ctx, "music"); refresh(); return@setOnItemClickListener }
                        if (r.id.startsWith("hist::")) {
                            currentQuery = r.id.removePrefix("hist::")
                            searchInput.setText(currentQuery)
                            runMusicSearch()
                            return@setOnItemClickListener
                        }
                        // ── Profondeur 0 → 1 : ARTISTE cliqué, on affiche sa discographie ──
                        if (r.id.startsWith("zfartist::")) {
                            val idArtiste = r.id.removePrefix("zfartist::")
                            val nom = r.name.removePrefix("🎤 ")
                            val ecranPrecedent = titreNiveau
                            headerTitle.text = "Albums de $nom…"
                            lifecycleOwner.lifecycleScope.launch {
                                val albums = try {
                                    com.streamflixreborn.streamflix.providers.ZeffyrMusicProvider
                                        .getArtistAlbums(idArtiste)
                                } catch (_: Throwable) { emptyList() }
                                titreNiveau = ecranPrecedent // restauré AVANT l'empilement
                                if (albums.isEmpty()) {
                                    Toast.makeText(ctx, "Aucun album pour $nom", Toast.LENGTH_SHORT).show()
                                    refresh()
                                } else {
                                    ouvrirNiveau("🎤 $nom", albums.map { albumEnLigne(it) })
                                }
                            }
                            return@setOnItemClickListener
                        }
                        // ── Profondeur 1 → 2 : ALBUM cliqué, on affiche ses pistes DANS L'ORDRE.
                        //   Pas de lecture immédiate : on voit d'abord le contenu, comme sur une
                        //   fiche d'album ; « ▶ Tout lire » enchaîne ensuite l'album entier.
                        if (r.id.startsWith("zfalbum::")) {
                            val idAlbum = r.id.removePrefix("zfalbum::")
                            val titreAlbum = r.name.removePrefix("💿 ").substringBefore(" — ")
                            val ecranPrecedent = titreNiveau
                            headerTitle.text = "Chargement de l'album…"
                            lifecycleOwner.lifecycleScope.launch {
                                val pistes = try {
                                    com.streamflixreborn.streamflix.providers.ZeffyrMusicProvider
                                        .getAlbumTracks(idAlbum)
                                } catch (_: Throwable) { emptyList() }
                                titreNiveau = ecranPrecedent
                                if (pistes.isEmpty()) {
                                    Toast.makeText(ctx, "Album indisponible", Toast.LENGTH_SHORT).show()
                                    refresh()
                                } else {
                                    ouvrirNiveau("💿 $titreAlbum", pistes.map { audioToStation(it) })
                                    Toast.makeText(
                                        ctx,
                                        "${pistes.size} titres — ▶ Tout lire pour l'album entier",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                            return@setOnItemClickListener
                        }
                        if (r.id == "zfback::") { remonterNiveau(); return@setOnItemClickListener }
                        if (r.streamUrl == null) return@setOnItemClickListener // ligne non jouable
                        mp.initPlayer(ctx)
                        // ⚠ l'index de départ se calcule DANS LA FILE, pas dans la liste affichée :
                        //   celle-ci contient désormais des lignes non jouables (⬅ Retour, artistes,
                        //   albums) qui décaleraient la position et lanceraient le mauvais titre.
                        val jouables = radios.filter { it.streamUrl != null }
                        val queue = jouables.map { st -> st.streamUrl!! to st.name }
                        if (queue.isEmpty()) return@setOnItemClickListener
                        val pos = jouables.indexOfFirst { it.id == r.id }.coerceAtLeast(0)
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
                if (r.id == "zfback::") return@setOnItemLongClickListener false
                val added: Boolean
                val label: String
                if (musicMode) {
                    // 2026-08-02 (user : « n'importe quoi peut être mis en favori : si je mets
                    //   l'artiste j'ai les albums dedans, si je mets l'album ou la musique, ainsi
                    //   de suite ») : un artiste et un album n'ont pas d'URL de flux — on garde
                    //   alors leur identifiant (`zfartist::…` / `zfalbum::…`) comme clé de favori.
                    //   Rouvrir un tel favori redescend dans le niveau correspondant.
                    val cle = cleFavori(r)
                    if (cle.isEmpty()) return@setOnItemLongClickListener false
                    added = MusicFavoritesStore.toggle(cle, r.name)
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
