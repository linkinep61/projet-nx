package com.streamflixreborn.streamflix.providers

import android.content.Context
import com.streamflixreborn.streamflix.utils.MiniPlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 2026-06-10 (user "comme Wiseplay multi-niveau, dossiers explorables") :
 *  POC d'explorateur de sous-bouquets World TV via AlertDialog modal.
 *
 *  - Click sur folder → re-ouvre le dialog avec le sous-folder
 *  - Click sur chaîne → callback play
 *  - Back via le bouton "Retour" du dialog ou la touche back hardware
 *
 *  Plus tard on remplacera ça par un vrai fragment (= comme Wiseplay) avec
 *  layout cards + navigation stack. Pour l'instant ce dialog est le moyen
 *  le plus rapide d'avoir du contenu navigable.
 */
object WorldLiveFolderDialog {

    /** 2026-06-22 : thème bg (copié de LiveHubFolderDialog). */
    private fun applyThemeBackground(dlg: android.app.AlertDialog) {
        try {
            val theme = com.streamflixreborn.streamflix.utils.UserPreferences.selectedTheme
            val palette = com.streamflixreborn.streamflix.utils.ThemeManager.palette(theme)
            val bg = palette.mobileNavBackground
            val r = android.graphics.Color.red(bg)
            val g = android.graphics.Color.green(bg)
            val b = android.graphics.Color.blue(bg)
            val themed = android.graphics.Color.argb(0xEE, r, g, b)
            dlg.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(themed)
            )
        } catch (_: Throwable) {}
    }

    /** Ouvre le dialog pour le folder identifié par [folderPath].
     *  [title] = nom à afficher en titre (= nom du sub-bouquet).
     *  [onChannel] = appelé quand l'user clique sur une chaîne feuille. */
    /** 2026-06-10 : track de la dernière chaîne cliquée par l'user pour
     *  détecter un 2e clic rapide → fullscreen au lieu de re-play. */
    @Volatile private var lastClickedChannelId: String? = null
    @Volatile private var lastClickedAt: Long = 0L
    private const val DOUBLE_CLICK_WINDOW_MS = 5_000L

    fun showFolder(
        context: Context,
        folderPath: String,
        title: String,
        onChannel: (WorldLiveTvProvider.WlChannel) -> Unit,
    ) {
        // 2026-06-25 (user "click sur dossier pas rafraîchi auto, obligé
        //   de rafraîchir le home") : si folderContents est vide ou stale,
        //   on déclenche un re-fetch global async + retry showFolder dès
        //   que le contenu arrive. Évite à l'user de retourner au home.
        // 2026-06-25 v2 (user "refresh fonctionne mal, obligé de refresh
        //   le home entier") : items maintenant MUTABLE + refresh async
        //   à CHAQUE ouverture (= même si cache non vide). Quand nouvelles
        //   items arrivent → notifyDataSetChanged live sans flicker.
        val items = (WorldLiveTvProvider.folderContents[folderPath] ?: emptyList()).toMutableList()
        if (items.isEmpty()) {
            // Toast indicatif + déclenche async fetch
            android.widget.Toast.makeText(
                context,
                "Chargement : $title…",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            refreshScope.launch {
                try {
                    // Force re-load complet du registry : invalidateCache vide
                    //   le cache mémoire+disque, getHome() déclenche ensureRegistry
                    //   qui re-fetch toutes les playlists + sub-folders.
                    WorldLiveTvProvider.invalidateCache()
                    WorldLiveTvProvider.getHome()
                } catch (t: Throwable) {
                    android.util.Log.w("WorldLiveFolder", "refresh failed: ${t.message}")
                }
                // Retry sur le main thread après le fetch
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    val refreshed = WorldLiveTvProvider.folderContents[folderPath] ?: emptyList()
                    if (refreshed.isNotEmpty()) {
                        // Re-show dialog avec contenu frais
                        showFolder(context, folderPath, title, onChannel)
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "Dossier vide : $title",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            return
        }

        // 2026-06-25 (user "il faut suivre la règle de redimensions pour
        //   tous les sous-dossiers") : refactor ListView text-only → GridView
        //   cards avec jaquettes, IDENTIQUE à LiveHubFolderDialog. Tous les
        //   sub-folders à n'importe quelle profondeur ont maintenant le
        //   même rendu visuel uniforme.
        val ctx = context
        val dp = ctx.resources.displayMetrics.density
        val isTV = (ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val numColumns = if (isTV) 6 else 4
        val posterW = if (isTV) (80 * dp).toInt() else (72 * dp).toInt()
        val posterH = if (isTV) (80 * dp).toInt() else (72 * dp).toInt()

        val gridView = android.widget.GridView(ctx).apply {
            this.numColumns = numColumns
            horizontalSpacing = (8 * dp).toInt()
            verticalSpacing = (8 * dp).toInt()
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            clipToPadding = false
            setSelector(android.R.drawable.list_selector_background)
        }

        val gridAdapter = object : android.widget.BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(pos: Int) = items[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val ch = items[pos]
                val cell = (convertView as? android.widget.LinearLayout) ?: run {
                    android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                        val imgView = android.widget.ImageView(ctx).apply {
                            tag = "poster"
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            layoutParams = android.widget.LinearLayout.LayoutParams(posterW, posterH)
                        }
                        addView(imgView)
                        val tvTitle = android.widget.TextView(ctx).apply {
                            tag = "title"
                            maxLines = 3
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setLineSpacing(0f, 1.15f)
                            gravity = android.view.Gravity.CENTER
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = if (isTV) 14f else 12f
                            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        }
                        addView(tvTitle)
                    }
                }
                val imgView = cell.findViewWithTag<android.widget.ImageView>("poster")
                val tvTitle = cell.findViewWithTag<android.widget.TextView>("title")
                val nameLabel = if (ch.isFolder) {
                    val nb = WorldLiveTvProvider.folderContents[ch.folderPath]?.size ?: 0
                    "${ch.name}\n($nb items)"
                } else ch.name
                tvTitle.text = nameLabel
                tvTitle.setTextColor(
                    if (ch.isFolder) android.graphics.Color.parseColor("#FFFFC107")
                    else android.graphics.Color.WHITE
                )
                val posterUrl = ch.logo
                if (!posterUrl.isNullOrBlank()) {
                    com.bumptech.glide.Glide.with(ctx)
                        .load(posterUrl)
                        .placeholder(if (ch.isFolder) android.R.drawable.ic_menu_gallery
                                     else android.R.drawable.ic_menu_view)
                        .error(android.R.drawable.ic_menu_gallery)
                        .centerCrop()
                        .into(imgView)
                } else {
                    imgView.setImageResource(
                        if (ch.isFolder) android.R.drawable.ic_menu_gallery
                        else android.R.drawable.ic_menu_view
                    )
                }
                return cell
            }
        }
        gridView.adapter = gridAdapter

        gridView.setOnItemLongClickListener { _, _, idx, _ ->
            val item = items[idx]
            if (!item.isFolder) {
                val leafId = "livehub::worldlivetv::${item.id}"
                val store = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                val nowFav = store.toggle("World Live", leafId)
                try {
                    val canonical = "wl" + item.id.substringAfterLast("::").lowercase().trim()
                    val prefs = context.getSharedPreferences(
                        "world_live_fav_meta", android.content.Context.MODE_PRIVATE,
                    )
                    if (nowFav) {
                        prefs.edit().apply {
                            putString("${canonical}_name", item.name)
                            putString("${canonical}_logo", item.logo ?: "")
                            apply()
                        }
                    } else {
                        prefs.edit().apply {
                            remove("${canonical}_name")
                            remove("${canonical}_logo")
                            apply()
                        }
                    }
                } catch (_: Throwable) {}
                kotlin.runCatching {
                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(
                        context,
                        com.streamflixreborn.streamflix.providers.LiveTvHubPlusProvider,
                    )
                }
                kotlin.runCatching {
                    com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
                        .notifyProviderChanged(forceRelaunch = true)
                }
                android.widget.Toast.makeText(
                    context,
                    if (nowFav) "Ajouté aux favoris : ${item.name}"
                    else "Retiré des favoris : ${item.name}",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                true
            } else false
        }

        gridView.setOnItemClickListener { _, _, idx, _ ->
            val item = items[idx]
            if (item.isFolder && item.folderPath != null) {
                showFolder(context, item.folderPath, item.name, onChannel)
            } else {
                val now = System.currentTimeMillis()
                val leafId = "livehub::worldlivetv::${item.id}"
                val isDoubleClick = lastClickedChannelId == leafId &&
                        (now - lastClickedAt) < DOUBLE_CLICK_WINDOW_MS
                if (isDoubleClick) {
                    lastClickedChannelId = null
                    lastClickedAt = 0L
                    try {
                        com.streamflixreborn.streamflix.utils.MiniPlayerController
                            .navigateToFullscreenForCurrent()
                    } catch (_: Throwable) {}
                } else {
                    lastClickedChannelId = leafId
                    lastClickedAt = now
                    onChannel(item)
                }
            }
        }

        val dlg = android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setView(gridView)
            .setNegativeButton("Retour", null)
            .show()
        applyThemeBackground(dlg)

        // 2026-06-22 : ajuste la fenêtre du dialog pour ne pas couvrir le mini player
        val displayH = context.resources.displayMetrics.heightPixels
        val MPC = MiniPlayerController

        fun getMiniPlayerHeight(): Int {
            val activity = context as? android.app.Activity ?: return 0
            val container = activity.findViewById<android.view.View>(
                com.streamflixreborn.streamflix.R.id.mini_player_container
            ) ?: return 0
            return if (container.visibility == android.view.View.VISIBLE && container.height > 0) {
                container.height
            } else 0
        }

        fun adjust(active: Boolean) {
            dlg.window?.let { w ->
                if (active) {
                    val miniH = getMiniPlayerHeight().coerceAtLeast(
                        (80 * context.resources.displayMetrics.density).toInt()
                    )
                    w.setGravity(android.view.Gravity.BOTTOM)
                    w.setLayout(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        displayH - miniH,
                    )
                } else {
                    w.setGravity(android.view.Gravity.CENTER)
                    w.setLayout(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    )
                }
                w.decorView.setPadding(0, 0, 0, 0)
            }
        }

        val initialActive = MPC.state.value is MiniPlayerController.State.Playing ||
                MPC.state.value is MiniPlayerController.State.Loading
        adjust(initialActive)

        // 2026-06-25 v2 (user "refresh fonctionne mal") : déclenche refresh
        //   background à CHAQUE ouverture du dialog. Si nouvelles items →
        //   notifyDataSetChanged live. Pas de flicker, dialog reste ouvert.
        val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        refreshScope.launch {
            try {
                WorldLiveTvProvider.invalidateCache()
                WorldLiveTvProvider.getHome()
            } catch (t: Throwable) {
                android.util.Log.w("WorldLiveFolder", "bg refresh failed: ${t.message}")
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                val refreshed = WorldLiveTvProvider.folderContents[folderPath] ?: emptyList()
                // Compare par taille + IDs pour détecter changement
                val oldIds = items.map { it.id }.toSet()
                val newIds = refreshed.map { it.id }.toSet()
                if (refreshed.isNotEmpty() && (refreshed.size != items.size || oldIds != newIds)) {
                    items.clear()
                    items.addAll(refreshed)
                    gridAdapter.notifyDataSetChanged()
                    android.util.Log.d("WorldLiveFolder", "bg refresh updated: ${items.size} items")
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            MPC.state.collect { state ->
                val active = state is MiniPlayerController.State.Playing ||
                        state is MiniPlayerController.State.Loading
                adjust(active)
            }
        }
        dlg.setOnDismissListener {
            scope.coroutineContext[Job]?.cancel()
            refreshScope.coroutineContext[Job]?.cancel()
        }
    }
}
