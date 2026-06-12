package com.streamflixreborn.streamflix.providers

import android.content.Context

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
        val items = WorldLiveTvProvider.folderContents[folderPath] ?: emptyList()
        if (items.isEmpty()) {
            android.widget.Toast.makeText(
                context,
                "Dossier vide : $title",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }

        // Labels : folder = "▸ Name (N items)", chaîne = nom direct
        val labels = items.map { ch ->
            if (ch.isFolder) {
                val nb = WorldLiveTvProvider.folderContents[ch.folderPath]?.size ?: 0
                "▸ ${ch.name}  ($nb items)"
            } else {
                ch.name
            }
        }

        // 2026-06-10 (user "la liste disparaît une fois qu'on a cliqué") :
        //   ListView custom au lieu de setItems → onclick ne ferme pas le
        //   dialog. L'user peut cliquer une chaîne, voir le player démarrer
        //   dans le mini-player en arrière-plan, et cliquer une 2e fois sur
        //   la même chaîne ou une autre.
        val listView = android.widget.ListView(context).apply {
            adapter = android.widget.ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                labels,
            )
            // 2026-06-10 (user "appui long pour ajouter en favoris") :
            //   les chaînes du folder ne sont accessibles qu'ici → long-press
            //   sur une chaîne feuille toggle le favoris (= comme TvShowViewHolder
            //   pour les chaînes IPTV normales). Pas applicable aux folders.
            setOnItemLongClickListener { _, _, idx, _ ->
                val item = items[idx]
                if (!item.isFolder) {
                    val leafId = "livehub::worldlivetv::${item.id}"
                    val store = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                    val nowFav = store.toggle("World Live", leafId)
                    // 2026-06-10 (user "favoris dans folder ne s'affiche pas") :
                    //   stocke aussi metadata (name + poster) en cache séparé
                    //   pour pouvoir afficher la carte même si folderContents
                    //   est vidé/re-fetch entre temps.
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
                    // Force un re-build du home pour que la section Favoris
                    //   apparaisse immédiatement (sans attendre re-navigation).
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
            setOnItemClickListener { _, _, idx, _ ->
                val item = items[idx]
                if (item.isFolder && item.folderPath != null) {
                    showFolder(context, item.folderPath, item.name, onChannel)
                } else {
                    val now = System.currentTimeMillis()
                    val leafId = "livehub::worldlivetv::${item.id}"
                    val isDoubleClick = lastClickedChannelId == leafId &&
                            (now - lastClickedAt) < DOUBLE_CLICK_WINDOW_MS
                    if (isDoubleClick) {
                        // 2e clic rapide sur la même chaîne → fullscreen.
                        //   Ferme le dialog et navigue vers le player full.
                        lastClickedChannelId = null
                        lastClickedAt = 0L
                        try {
                            com.streamflixreborn.streamflix.utils.MiniPlayerController
                                .navigateToFullscreenForCurrent()
                        } catch (_: Throwable) {}
                    } else {
                        // 1er clic : lance dans le mini-player (sans fermer).
                        lastClickedChannelId = leafId
                        lastClickedAt = now
                        onChannel(item)
                    }
                }
            }
        }

        android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setView(listView)
            .setNegativeButton("Fermer", null)
            .show()
    }
}
