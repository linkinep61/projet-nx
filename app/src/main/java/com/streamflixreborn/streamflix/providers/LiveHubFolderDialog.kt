package com.streamflixreborn.streamflix.providers

import android.content.Context
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.MiniPlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2026-06-19 (user "faut peut-être qu'on optimise notre TV hub et faire des
 * dossiers Pour que ça charge moins et que ça charge qu'à l'ouverture du
 * dossier") : dialog qui affiche le contenu d'un dossier du TV Hub.
 *
 * Structure : on liste les sous-catégories du dossier (= Replay TF1, TMC, TFX,
 * etc. pour le dossier "TF1+"). Au clic sur une catégorie, on affiche les
 * chaînes de cette catégorie via un nouveau dialog (= 2 niveaux : dossier →
 * catégorie → chaînes).
 *
 * Pour les dossiers "plats" (= une seule catégorie comme Adrar TV), on saute
 * directement au listing des chaînes.
 */
object LiveHubFolderDialog {

    /** 2026-06-29 (REPAIR — user "on ne trouvait pas '90 IS GOOD' sous plusieurs
     *  formes") : normalise une chaîne pour la recherche → ignore accents,
     *  espaces, apostrophes et ponctuation. Ainsi "90 IS GOOD", "90's", "90 s"
     *  et "90s" deviennent comparables ("90isgood" / "90s"), et taper "90"
     *  matche toutes ces variantes. Mieux que le simple `contains` lowercase. */
    private fun normSearch(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "")

    /** 2026-06-21 (user "fond d'écran des dialogs doit suivre le thème user") :
     *  applique le background du thème user (= ThemeManager.palette
     *  mobileNavBackground) à un AlertDialog. Doit être appelé APRÈS dlg.show()
     *  car le window est null avant. Si l'user change de thème, ouvrir un
     *  nouveau dialog → bg se met à jour automatiquement (lit la pref à chaque
     *  ouverture). */
    private fun applyThemeBackground(dlg: android.app.AlertDialog) {
        try {
            val theme = com.streamflixreborn.streamflix.utils.UserPreferences.selectedTheme
            val palette = com.streamflixreborn.streamflix.utils.ThemeManager.palette(theme)
            val bg = palette.mobileNavBackground
            // Force opacité 0xEE pour bien voir derrière (= transparence légère)
            val r = android.graphics.Color.red(bg)
            val g = android.graphics.Color.green(bg)
            val b = android.graphics.Color.blue(bg)
            val themed = android.graphics.Color.argb(0xEE, r, g, b)
            dlg.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(themed)
            )
        } catch (_: Throwable) {
            // En cas d'erreur, laisse le bg par défaut de l'AlertDialog
        }
    }

    /**
     * 2026-06-24 : helper — emballe un TextView titre dans une barre horizontale
     * avec un handle de drag ≡ (MOBILE ONLY). Le handle permet de glisser
     * verticalement pour ajuster la hauteur du dialog (gravity BOTTOM).
     * NE TOUCHE PAS la version TV.
     * @return Pair(barreVue, connecteur) — appeler connecteur(dlg) APRÈS show().
     */
    private fun wrapWithDragHandle(
        ctx: Context,
        titleTv: android.widget.TextView,
    ): Pair<android.view.View, (android.app.AlertDialog) -> Unit> {
        val dp = ctx.resources.displayMetrics.density
        val isTV = ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        if (isTV) return titleTv to { _ -> }
        // Mobile : titre passe en weight 1f, fond transféré au wrapper
        titleTv.layoutParams = android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        val hadBg = titleTv.background
        titleTv.background = null
        val dragHandle = android.widget.TextView(ctx).apply {
            text = "≡"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding((14 * dp).toInt(), (2 * dp).toInt(), (14 * dp).toInt(), (2 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xFF, 0x55, 0x55, 0x55))
                cornerRadius = 6 * dp
            }
        }
        val titleBar = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = hadBg ?: android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18))
                cornerRadius = 6 * dp
            }
            addView(titleTv)
            addView(dragHandle)
        }
        var dialogRef: android.app.AlertDialog? = null
        dragHandle.setOnTouchListener @android.annotation.SuppressLint("ClickableViewAccessibility")
        { v, event ->
            val dlg = dialogRef ?: return@setOnTouchListener false
            val screenH = ctx.resources.displayMetrics.heightPixels
            val minH = (150 * dp).toInt()
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val curH = dlg.window?.attributes?.height
                        ?: android.view.WindowManager.LayoutParams.WRAP_CONTENT
                    val startH = if (curH < 0) dlg.window?.decorView?.height ?: screenH else curH
                    v.tag = floatArrayOf(event.rawY, startH.toFloat())
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val data = v.tag as? FloatArray ?: return@setOnTouchListener false
                    val deltaY = event.rawY - data[0]
                    val newH = (data[1] - deltaY).toInt().coerceIn(minH, screenH)
                    dlg.window?.setLayout(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT, newH
                    )
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    val data = v.tag as? FloatArray ?: return@setOnTouchListener false
                    val deltaY = event.rawY - data[0]
                    val newH = (data[1] - deltaY).toInt().coerceIn(minH, screenH)
                    dlg.window?.setLayout(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT, newH
                    )
                    val ratio = (newH * 100) / screenH
                    com.streamflixreborn.streamflix.utils.UserPreferences.homeTopOffset = ratio
                    true
                }
                else -> false
            }
        }
        return titleBar to { dlg -> dialogRef = dlg }
    }

    // ── 2026-06-23 : pile de dialogs pour navigation back ──
    // Quand on navigue dans un sous-menu, le dialog parent est CACHÉ (pas
    // fermé). Quand on fait retour, il se ré-affiche. Seul le passage en
    // plein écran ferme TOUT.
    private val dialogStack = mutableListOf<android.app.AlertDialog>()
    private var dismissingAll = false

    /** Empile un dialog et cache le précédent. */
    private fun pushDialog(dlg: android.app.AlertDialog) {
        dialogStack.lastOrNull()?.hide()
        dialogStack.add(dlg)
    }

    /** Ferme TOUS les dialogs de la pile (= passage en plein écran). */
    private fun dismissAllDialogs() {
        dismissingAll = true
        val copy = dialogStack.toList()
        dialogStack.clear()
        copy.forEach { it.dismiss() }
        dismissingAll = false
    }

    /** 2026-06-23 (user "menu synopsis reste caché derrière l'interface") :
     *  exposé public pour permettre à TvShowViewHolder.navigateFromFolderSelection
     *  de fermer les dialogs AVANT d'ouvrir la fiche détail série/film. */
    fun dismissAllPublic() = dismissAllDialogs()

    /** Appelé dans le setOnDismissListener de chaque dialog.
     *  Ré-affiche le parent si c'est un retour (pas un dismiss-all). */
    private fun onDialogDismissed(dlg: android.app.AlertDialog, cleanup: () -> Unit) {
        cleanup()
        dialogStack.remove(dlg)
        if (!dismissingAll) {
            dialogStack.lastOrNull()?.let { parent ->
                parent.show()
                // 2026-06-24 : applique la hauteur sauvegardée au parent
                // (l'user a pu changer la taille sur le sous-dialog)
                val savedRatio = com.streamflixreborn.streamflix.utils.UserPreferences.homeTopOffset
                if (savedRatio in 1..99) {
                    parent.window?.let { w ->
                        val screenH = parent.context.resources.displayMetrics.heightPixels
                        w.setGravity(android.view.Gravity.BOTTOM)
                        w.setLayout(
                            android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            (screenH * savedRatio) / 100
                        )
                        w.decorView.setPadding(0, 0, 0, 0)
                    }
                }
            }
        }
    }

    /** Affiche le dossier `folderKey` avec son contenu (= liste de Category).
     *  `onChannelSelected` est appelé quand l'user sélectionne une chaîne
     *  feuille (= un TvShow non-folder).
     *  2026-06-19 (user "maintenant faut que ça soit optimisé par dossier") :
     *  pour les dossiers Replay (TF1+/M6+/France TV/Arte/Autres), on FETCH
     *  on-demand au clic — au boot, le m3u replay n'est PAS fetché pour
     *  économiser RAM/CPU sur petites box. */
    /** 2026-06-24 : convertit un WlChannel folder id en folderPath utilisable
     *  par WorldLiveTvProvider.folderContents. Ex :
     *    `wltv::cin_ma::folder::samsung_tv_plus-0` → `cin_ma/samsung_tv_plus/0`
     *  Retourne null si l'id n'est pas un WlChannel folder (= pas de "::folder::").
     */
    private fun extractWlFolderPathFromId(wlChannelId: String): String? {
        if (!wlChannelId.contains("::folder::")) return null
        val withoutPrefix = wlChannelId.removePrefix("wltv::")
        val parts = withoutPrefix.split("::folder::")
        if (parts.size != 2) return null
        val group = parts[0]
        val subAndIndex = parts[1] // ex "samsung_tv_plus-0"
        val lastDash = subAndIndex.lastIndexOf('-')
        if (lastDash < 0) return null
        val sub = subAndIndex.substring(0, lastDash)
        val index = subAndIndex.substring(lastDash + 1)
        return "$group/$sub/$index"
    }

    fun show(
        ctx: Context,
        folderKey: String,
        folderName: String,
        onChannelSelected: (TvShow) -> Unit,
    ) {
        // 2026-06-24 : sub-folder World Live TV — ouvert depuis le rebadge
        //   ligne `livehub::folder::wlsub_<folderPath>`. Lit folderContents
        //   du provider et affiche via displayCategories (= même style que
        //   le parent Cinéma).
        if (folderKey.startsWith("wlsub_")) {
            val folderPath = folderKey.removePrefix("wlsub_")
            val wlChannels = WorldLiveTvProvider.folderContents[folderPath] ?: emptyList()
            if (wlChannels.isEmpty()) {
                android.widget.Toast.makeText(
                    ctx, "Sous-dossier vide : $folderName", android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            }
            // Rebadge récursive : sub-folders enfants gardent leur préfixe
            //   wlsub_, chaînes terminales utilisent livehub::worldlivetv::
            val rebadged = wlChannels.map { wl ->
                val childPath = if (wl.isFolder && !wl.folderPath.isNullOrBlank()) {
                    wl.folderPath
                } else null
                val newId = if (childPath != null) {
                    "livehub::folder::wlsub_$childPath"
                } else {
                    "livehub::worldlivetv::${wl.id}"
                }
                TvShow(id = newId, title = wl.name).apply {
                    providerName = "World Live"
                    poster = wl.logo
                    banner = wl.logo
                }
            }
            val asCategory = Category(name = folderName, list = rebadged)
            displayCategories(ctx, folderName, listOf(asCategory), onChannelSelected)
            return
        }
        val cached = LiveTvHubProvider.folderContents[folderKey] ?: emptyList()
        // 2026-06-20 : OTF utilise son propre dialog avec bouton 🌍 Langue.
        //   Si on a un cache OTF, on affiche direct les chaînes (pas de
        //   re-fetch) mais avec le bouton Langue pour pouvoir changer.
        if (folderKey == "otf" && cached.isNotEmpty()) {
            val svc = com.streamflixreborn.streamflix.utils.OtfTvService
            val currentGroup = svc.selectedGroup.ifBlank { "France" }
            // On a besoin de allChannels + groupNames pour le picker langue.
            //   On les récupère depuis le cache OtfTvService.
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                val allChannels = try {
                    kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                        svc.fetchChannels()
                    } ?: emptyList()
                } catch (_: Throwable) { emptyList() }
                val groupNames = allChannels.map { it.group.ifBlank { "Autres" } }
                    .distinct()
                    .sortedWith(compareBy {
                        if (it.equals("France", ignoreCase = true)) "" else it
                    })
                withContext(Dispatchers.Main) {
                    if (allChannels.isEmpty()) {
                        // Fallback : affiche le cache sans bouton langue
                        displayCategories(ctx, folderName, cached, onChannelSelected)
                    } else {
                        displayOtfChannelsWithLanguageButton(
                            ctx, folderName, currentGroup, cached,
                            allChannels, groupNames, onChannelSelected
                        )
                    }
                }
            }
            return
        }
        // Si on a du contenu cache (= WiTV/Adrar déjà chargés au boot, ou
        //   ce dossier a déjà été fetché on-demand) → affichage instant.
        // 2026-06-30 : les dossiers avec un affichage custom (Plex, Pluto,
        //   Autres Replays avec extras, Musique) ne doivent PAS prendre le
        //   raccourci displayCategories sinon on perd leur vue spéciale
        //   (sous-dossiers Films/Séries/Chaînes, Mix FR/WorldWide, agrégation).
        if (cached.isNotEmpty()) {
            when (folderKey) {
                "plex_tv" -> {
                    displayPlexFolder(ctx, folderName, cached, onChannelSelected)
                    return
                }
                "pluto_tv" -> {
                    displayPlutoFolder(ctx, folderName, cached, onChannelSelected)
                    return
                }
                "autres_replay" -> {
                    val mixC = LiveTvHubProvider.folderContents["__ar_mix"] ?: emptyList()
                    val wwC = LiveTvHubProvider.folderContents["__ar_ww"] ?: emptyList()
                    val sportC = LiveTvHubProvider.folderContents["__ar_sport"] ?: emptyList()
                    val rakC = LiveTvHubProvider.folderContents["__ar_rak"] ?: emptyList()
                    val sonyC = LiveTvHubProvider.folderContents["__ar_sony"] ?: emptyList()
                    val hasExtras = mixC.isNotEmpty() || wwC.isNotEmpty() ||
                        sportC.isNotEmpty() || rakC.isNotEmpty() || sonyC.isNotEmpty()
                    if (hasExtras) {
                        displayCategoriesWithMixFr(ctx, folderName, cached, mixC,
                            onChannelSelected, wwC, sportC, rakC, sonyC)
                        return
                    }
                    // Pas d'extras en cache → fall through au lazy fetch ci-dessous
                }
                "musique" -> {
                    // Ne pas prendre le raccourci cache — laisser le bloc musique
                    // ci-dessous faire l'agrégation complète (iptv-org + nos playlists)
                }
                else -> {
                    displayCategories(ctx, folderName, cached, onChannelSelected)
                    return
                }
            }
        }
        // Sinon → fetch on-demand depuis fetchReplayCategories.
        //   On affiche un Toast loading puis le dialog quand prêt.
        if (folderKey in REPLAY_FOLDER_KEYS) {
            android.widget.Toast.makeText(
                ctx, "Chargement du dossier $folderName…", android.widget.Toast.LENGTH_SHORT
            ).show()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try {
                    val allReplays = LiveTvHubProvider.fetchReplayCategoriesPublic()
                    android.util.Log.d("LiveHubFolderDialog",
                        "Lazy fetch for '$folderKey': $allReplays got ${allReplays.size} replay cats")
                    android.util.Log.d("LiveHubFolderDialog",
                        "  cat names: ${allReplays.joinToString { it.name }}")
                    val filtered = filterReplayByFolder(allReplays, folderKey)
                    android.util.Log.d("LiveHubFolderDialog",
                        "  filtered for '$folderKey' = ${filtered.size}: ${filtered.joinToString { it.name }}")
                    // Cache pour les prochains clics
                    if (filtered.isNotEmpty()) {
                        LiveTvHubProvider.folderContents[folderKey] = filtered
                    }
                    // 2026-06-27 (user "Mix FR dans le dossier Autres Replays") :
                    //   on ajoute une entrée imbriquée "📁 Mix FR" qui charge la
                    //   playlist data.m3u (auto-refresh), groupée par group-title.
                    val mixCats = if (folderKey == "autres_replay") {
                        try { LiveTvHubProvider.fetchMixFrCategoriesPublic() }
                        catch (_: Throwable) { emptyList() }
                    } else emptyList()
                    // 2026-06-27 : WorldWide (mirror epg.pw sur git) dans Autres Replays.
                    val wwCats = if (folderKey == "autres_replay") {
                        try { LiveTvHubProvider.fetchWorldwideCategoriesPublic() }
                        catch (_: Throwable) { emptyList() }
                    } else emptyList()
                    // 2026-06-27 : Sport (Vegeta) + Rakuten TV + Sony One déplacés
                    //   dans Autres Replays comme sous-dossiers.
                    val sportCats = if (folderKey == "autres_replay")
                        (LiveTvHubProvider.folderContents["sport"] ?: emptyList()) else emptyList()
                    var rakCats: List<Category> = emptyList()
                    var sonyCats: List<Category> = emptyList()
                    if (folderKey == "autres_replay") {
                        try {
                            val allFast = LiveTvHubProvider.fetchFastCategoriesPublic()
                            rakCats = filterFastByFolder(allFast, "rakuten_tv")
                            sonyCats = filterFastByFolder(allFast, "sony_one")
                        } catch (_: Throwable) {}
                    }
                    val hasExtras = mixCats.isNotEmpty() || wwCats.isNotEmpty() ||
                        sportCats.isNotEmpty() || rakCats.isNotEmpty() || sonyCats.isNotEmpty()
                    // 2026-06-30 : cacher les extras pour que le 2ème clic sur
                    //   "Autres Replays" retrouve la vue complète (avec Mix FR,
                    //   WorldWide, Sport, Rakuten, Sony) au lieu des replays bruts.
                    //   Les clés __ar_* ne sont PAS dans sectionKeys de
                    //   groupSectionsIntoFolders → préservées lors du refresh home.
                    if (folderKey == "autres_replay" && hasExtras) {
                        if (mixCats.isNotEmpty()) LiveTvHubProvider.folderContents["__ar_mix"] = mixCats
                        if (wwCats.isNotEmpty()) LiveTvHubProvider.folderContents["__ar_ww"] = wwCats
                        if (sportCats.isNotEmpty()) LiveTvHubProvider.folderContents["__ar_sport"] = sportCats
                        if (rakCats.isNotEmpty()) LiveTvHubProvider.folderContents["__ar_rak"] = rakCats
                        if (sonyCats.isNotEmpty()) LiveTvHubProvider.folderContents["__ar_sony"] = sonyCats
                    }
                    withContext(Dispatchers.Main) {
                        if (filtered.isEmpty() && !hasExtras) {
                            android.widget.Toast.makeText(
                                ctx, "Aucune catégorie pour $folderName",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else if (hasExtras) {
                            displayCategoriesWithMixFr(ctx, folderName, filtered, mixCats,
                                onChannelSelected, wwCats, sportCats, rakCats, sonyCats)
                        } else {
                            displayCategories(ctx, folderName, filtered, onChannelSelected)
                        }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            ctx, "Erreur : ${t.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            return
        }
        // 2026-06-26 : Plex TV — lineup récupéré côté APP (géo-correct, respecte
        //   le VPN/WARP de l'appareil). Le scraper US ne peut pas fournir le bon
        //   lineup (geo IP-based). On NE passe PAS par data-fast.m3u pour Plex.
        if (folderKey == "plex_tv") {
            android.widget.Toast.makeText(
                ctx, "Chargement de $folderName…", android.widget.Toast.LENGTH_SHORT
            ).show()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try {
                    val cats = LiveTvHubProvider.fetchPlexLineupCategoryPublic()
                    if (cats.isNotEmpty()) {
                        LiveTvHubProvider.folderContents[folderKey] = cats
                    }
                    withContext(Dispatchers.Main) {
                        if (cats.isEmpty()) {
                            android.widget.Toast.makeText(
                                ctx, "Plex indisponible dans ta région — réessaie (ou active ton VPN/WARP)",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else {
                            displayPlexFolder(ctx, folderName, cats, onChannelSelected)
                        }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            ctx, "Erreur Plex : ${t.message}", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            return
        }
        // 2026-06-27 : Pluto TV — live + VOD (films/séries) via API officielle.
        //   2 sessions : catalogue FR (IDs français) + flux Tahiti (lecture sans
        //   décalage, comme l'ancien FAST). Le stitcher sert selon l'ID, pas la session.
        if (folderKey == "pluto_tv") {
            android.widget.Toast.makeText(
                ctx, "Chargement de $folderName…", android.widget.Toast.LENGTH_SHORT
            ).show()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try {
                    val cats = LiveTvHubProvider.fetchPlutoFolderCategoriesPublic()
                    if (cats.isNotEmpty()) LiveTvHubProvider.folderContents[folderKey] = cats
                    withContext(Dispatchers.Main) {
                        if (cats.isEmpty()) {
                            android.widget.Toast.makeText(
                                ctx, "Pluto indisponible — réessaie", android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else {
                            displayPlutoFolder(ctx, folderName, cats, onChannelSelected)
                        }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            ctx, "Erreur Pluto : ${t.message}", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            return
        }
        // 2026-06-27 : Musique — agrégé (iptv-org + nos playlists, dédup), groupé
        //   par langue en sous-dossiers. Lazy fetch, cache 30 min.
        if (folderKey == "musique") {
            android.widget.Toast.makeText(
                ctx, "Chargement de $folderName…", android.widget.Toast.LENGTH_SHORT
            ).show()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try {
                    // Chaînes musicales déjà présentes (Vegeta : MTV/MCM/M6 Music/
                    //   Trace/NRJ Hits…), regroupées par getHome dans la section "Musique".
                    val existing = LiveTvHubProvider.folderContents["musique"]
                        ?.filterNot { it.name.contains("Musique", ignoreCase = true) && it.list.isEmpty() }
                        ?: emptyList()
                    // + agrégation (iptv-org + nos playlists, dédup, par langue).
                    val agg = LiveTvHubProvider.fetchMusiqueCategoriesPublic()
                    val cats = existing + agg
                    // 2026-07-05 (user "chaînes du même dossier = IPTV au lieu de
                    //   musique") : persister les chaînes agrégées dans folderContents
                    //   sous une clé dédiée, pour que folderSiblingsOf() les retrouve
                    //   quand le player demande les frères du dossier courant.
                    //   La clé "__musique_agg" n'est PAS touchée par
                    //   groupSectionsIntoFolders (= pas effacée au refresh getHome).
                    if (agg.isNotEmpty()) {
                        LiveTvHubProvider.folderContents["__musique_agg"] = agg
                    }
                    withContext(Dispatchers.Main) {
                        if (cats.isEmpty()) {
                            android.widget.Toast.makeText(
                                ctx, "Musique indisponible — réessaie", android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else {
                            displayCategories(ctx, folderName, cats, onChannelSelected)
                        }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            ctx, "Erreur Musique : ${t.message}", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            return
        }
        // 2026-07-10 (user "nouveau dossier séparé CF") : Stream4Free CF (test) — scrape live + CF.
        if (folderKey == "stream4cf") {
            android.widget.Toast.makeText(ctx, "Chargement de $folderName…", android.widget.Toast.LENGTH_SHORT).show()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try {
                    val cats = LiveTvHubProvider.fetchStream4CfCategoriesLive()
                    withContext(Dispatchers.Main) {
                        if (cats.isEmpty()) {
                            android.widget.Toast.makeText(ctx, "Stream4Free CF indisponible (CF ?) — réessaie", android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            displayCategories(ctx, folderName, cats, onChannelSelected)
                        }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "Erreur Stream4Free CF : ${t.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }
        // 2026-07-10 (user "supprime la version de base") : ancienne branche Stream4Free `stream4`
        //   (résolveur OkHttp + git) RETIRÉE. Stream4Free = maintenant la clé `stream4cf` (version CF).
        // 2026-07-10 (user "supprime LumiChat partout") : branche lazy-fetch LumiChat RETIRÉE.
        // 2026-06-24 : FAST channels — lazy fetch from data-fast.m3u
        if (folderKey in FAST_FOLDER_KEYS) {
            android.widget.Toast.makeText(
                ctx, "Chargement de $folderName…", android.widget.Toast.LENGTH_SHORT
            ).show()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try {
                    val allFast = LiveTvHubProvider.fetchFastCategoriesPublic()
                    android.util.Log.d("LiveHubFolderDialog",
                        "FAST lazy fetch for '$folderKey': got ${allFast.size} cats")
                    val filtered = filterFastByFolder(allFast, folderKey)
                    android.util.Log.d("LiveHubFolderDialog",
                        "  FAST filtered for '$folderKey' = ${filtered.size}")
                    if (filtered.isNotEmpty()) {
                        LiveTvHubProvider.folderContents[folderKey] = filtered
                    }
                    withContext(Dispatchers.Main) {
                        if (filtered.isEmpty()) {
                            android.widget.Toast.makeText(
                                ctx, "Aucune chaîne pour $folderName",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            displayCategories(ctx, folderName, filtered, onChannelSelected)
                        }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            ctx, "Erreur : ${t.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            return
        }
        // 2026-06-20 (user "au clic ça devrait déclencher le refresh de cette
        //   playlist en question / chargement de la playlist puis ça affiche") :
        //   pour OTF vide au clic, force un fetch frais et affiche le
        //   contenu après plutôt qu'un toast "vide" décevant.
        // 2026-06-20 (user "avant on avait un filtre qui permettait de changer
        //   les langues, il faudrait un truc pour changer de langue") :
        //   pour OTF, on affiche d'abord un picker de langue/groupe.
        if (folderKey == "otf") {
            android.widget.Toast.makeText(
                ctx, "Chargement de la playlist $folderName…",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try {
                    showOtfWithGroupPicker(ctx, folderName, onChannelSelected)
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            ctx, "Erreur chargement $folderName : ${t.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            return
        }
        // 2026-06-23 (user "à l'ouverture de World Live quand on clique sur un
        //   dossier, ça ne fonctionne pas, obligé de rafraîchir le home — faut
        //   que ça active le chargement comme sur TV Hub") : pour les dossiers
        //   World Live (folderKey = "wl_<sanitized>"), fetch on-demand via
        //   WorldLiveTvProvider.getHome() qui retourne toutes les catégories
        //   M3U. On filtre celle qui matche notre folderKey et on populate
        //   folderContents pour les clics suivants.
        if (folderKey.startsWith("wl_")) {
            android.widget.Toast.makeText(
                ctx, "Chargement du dossier $folderName…",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try {
                    val sections = WorldLiveTvProvider.getHome()
                    val matching = sections.firstOrNull { cat ->
                        val k = "wl_" + cat.name.lowercase()
                            .replace(Regex("[^a-z0-9]+"), "_")
                            .trim('_')
                        k == folderKey
                    }
                    if (matching != null) {
                        // Re-badge les chaînes avec le préfixe livehub::worldlivetv::
                        // (= même logique que buildFoldersFromWltv)
                        // 2026-06-24 (user "Samsung TV Plus s'ouvre en plein
                        //   écran sans drag handle") : si l'item est un folder
                        //   WlChannel (= id contient "::folder::"), on préfixe
                        //   en `livehub::folder::wlsub_<folderPath>` pour que
                        //   le clic réouvre LiveHubFolderDialog.show() (=
                        //   même style de dialog que le parent Cinéma).
                        val rebadgedChannels = (matching.list as? List<*>)
                            ?.mapNotNull { it as? TvShow }
                            ?.map { tv ->
                                val folderPath = extractWlFolderPathFromId(tv.id)
                                val newId = if (folderPath != null) {
                                    "livehub::folder::wlsub_$folderPath"
                                } else {
                                    "livehub::worldlivetv::${tv.id}"
                                }
                                TvShow(id = newId, title = tv.title ?: "").apply {
                                    providerName = "World Live"
                                    poster = tv.poster
                                    banner = tv.banner
                                }
                            } ?: emptyList()
                        val asCategory = Category(name = matching.name, list = rebadgedChannels)
                        LiveTvHubProvider.folderContents[folderKey] = listOf(asCategory)
                        withContext(Dispatchers.Main) {
                            displayCategories(ctx, folderName, listOf(asCategory), onChannelSelected)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                ctx, "Aucune catégorie pour $folderName — rafraîchis le home",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            ctx, "Erreur chargement $folderName : ${t.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            return
        }
        // Dossier inconnu sans contenu cache → message
        android.widget.Toast.makeText(
            ctx, "Aucune catégorie disponible pour $folderName", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /** 2026-06-20 : fetch OTF, affiche directement les chaînes du groupe
     *  actuel (France par défaut), avec un bouton 🌍 pour changer de langue. */
    private suspend fun showOtfWithGroupPicker(
        ctx: Context,
        folderName: String,
        onChannelSelected: (TvShow) -> Unit,
    ) {
        val svc = com.streamflixreborn.streamflix.utils.OtfTvService
        svc.resetForRefresh()
        val allChannels = try {
            kotlinx.coroutines.withTimeoutOrNull(20_000L) { svc.fetchChannels() } ?: emptyList()
        } catch (_: Throwable) { emptyList() }
        if (allChannels.isEmpty()) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    ctx, "OTF indisponible — réessaie dans 1 minute",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        // Groupes disponibles (langues/pays)
        val groupNames = allChannels.map { it.group.ifBlank { "Autres" } }
            .distinct()
            .sortedWith(compareBy {
                if (it.equals("France", ignoreCase = true)) "" else it
            })
        val currentGroup = svc.selectedGroup.ifBlank { "France" }
        // Filtre et affiche les chaînes du groupe courant
        val filtered = allChannels.filter {
            (it.group.ifBlank { "Autres" }) == currentGroup
        }
        val sections = buildOtfSections(filtered, currentGroup)
        if (sections.isNotEmpty()) {
            LiveTvHubProvider.folderContents["otf"] = sections
        }
        withContext(Dispatchers.Main) {
            displayOtfChannelsWithLanguageButton(
                ctx, folderName, currentGroup, sections,
                allChannels, groupNames, onChannelSelected
            )
        }
    }

    /** Affiche les chaînes OTF avec un bouton 🌍 en haut à côté du titre
     *  (user "le bouton langue doit être tout en haut, à côté de OTF France,
     *  car on doit défiler toute la liste sur la télé pour l'atteindre"). */
    private fun displayOtfChannelsWithLanguageButton(
        ctx: Context,
        folderName: String,
        currentGroup: String,
        sections: List<Category>,
        allChannels: List<com.streamflixreborn.streamflix.utils.OtfTvService.OtfChannel>,
        groupNames: List<String>,
        onChannelSelected: (TvShow) -> Unit,
    ) {
        val channels = sections.flatMap {
            (it.list as? List<*>)?.filterIsInstance<TvShow>().orEmpty()
        }
        if (channels.isEmpty()) {
            android.widget.Toast.makeText(
                ctx, "Aucune chaîne OTF pour $currentGroup",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val dp = ctx.resources.displayMetrics.density
        val isTV = ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        // ── Header custom : titre + bouton 🌍 sur la même ligne ──
        val headerLayout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }
        val titleTv = android.widget.TextView(ctx).apply {
            text = "📁 $folderName ($currentGroup)"
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (isTV) 20f else 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val langBtn = android.widget.Button(ctx).apply {
            text = "🌍"
            textSize = if (isTV) 22f else 18f
            isFocusable = true
            isFocusableInTouchMode = false
            minimumWidth = (48 * dp).toInt()
            minimumHeight = (48 * dp).toInt()
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            // Style discret : fond transparent, juste le globe
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        headerLayout.addView(titleTv)
        headerLayout.addView(langBtn)

        // ── Liste des chaînes ── (2026-06-22 : ListView custom, pas setItems)
        val listView = android.widget.ListView(ctx).apply {
            adapter = android.widget.ArrayAdapter(
                ctx,
                android.R.layout.simple_list_item_1,
                channels.map { it.title },
            )
            setOnItemClickListener { _, _, idx, _ ->
                onChannelSelected(channels[idx])
            }
        }
        var dlgRef: android.app.AlertDialog? = null
        val dlg = android.app.AlertDialog.Builder(ctx)
            .setCustomTitle(headerLayout)
            .setView(listView)
            .setNegativeButton("Retour", null)
            .create()
        try { dlg.show() } catch (_: android.view.WindowManager.BadTokenException) { return }
        pushDialog(dlg)
        dlgRef = dlg
        applyThemeBackground(dlg)
        val cleanupMiniPlayer = adjustDialogForMiniPlayer(ctx, dlg)
        dlg.setOnDismissListener { onDialogDismissed(dlg, cleanupMiniPlayer) }
        // Branche le clic 🌍 → ferme le dialog, ouvre le picker
        langBtn.setOnClickListener {
            dlgRef?.dismiss()
            showOtfLanguagePicker(ctx, folderName, allChannels, groupNames, onChannelSelected)
        }
    }

    /** Picker de langue OTF : l'user choisit un groupe, on réaffiche les
     *  chaînes de ce groupe. */
    private fun showOtfLanguagePicker(
        ctx: Context,
        folderName: String,
        allChannels: List<com.streamflixreborn.streamflix.utils.OtfTvService.OtfChannel>,
        groupNames: List<String>,
        onChannelSelected: (TvShow) -> Unit,
    ) {
        val svc = com.streamflixreborn.streamflix.utils.OtfTvService
        val currentGroup = svc.selectedGroup.ifBlank { "France" }
        val currentIdx = groupNames.indexOfFirst {
            it.equals(currentGroup, ignoreCase = true)
        }.coerceAtLeast(0)
        val items = groupNames.toTypedArray()
        android.app.AlertDialog.Builder(ctx)
            .setTitle("🌍 Choisir la langue")
            .setSingleChoiceItems(items, currentIdx) { dlg, idx ->
                dlg.dismiss()
                val chosen = groupNames[idx]
                svc.selectedGroup = chosen
                val filtered = allChannels.filter {
                    (it.group.ifBlank { "Autres" }) == chosen
                }
                val sections = buildOtfSections(filtered, chosen)
                if (sections.isNotEmpty()) {
                    LiveTvHubProvider.folderContents["otf"] = sections
                }
                // Réaffiche les chaînes du nouveau groupe avec le bouton 🌍
                displayOtfChannelsWithLanguageButton(
                    ctx, folderName, chosen, sections,
                    allChannels, groupNames, onChannelSelected
                )
            }
            .setNegativeButton("Retour", null)
            .show()
    }

    /** Construit les sections OTF pour un groupe de chaînes donné. */
    private fun buildOtfSections(
        channels: List<com.streamflixreborn.streamflix.utils.OtfTvService.OtfChannel>,
        groupName: String,
    ): List<Category> {
        val sorted = com.streamflixreborn.streamflix.utils.OtfTvService
            .sortChannelsFrenchTntOrder(channels)
        val shows = sorted.distinctBy { it.normalizedKey }.mapNotNull { ch ->
            val id = "livehub::otf::${ch.normalizedKey}"
            if (com.streamflixreborn.streamflix.fragments.player.settings
                    .IptvBannedChannels.isBanned(id)) return@mapNotNull null
            TvShow(id = id, title = ch.name).apply {
                providerName = "TV Hub"
                poster = ch.logo
                banner = ch.logo
            }
        }
        if (shows.isEmpty()) return emptyList()
        return listOf(Category(name = "OTF TV - $groupName", list = shows))
    }

    private val REPLAY_FOLDER_KEYS = setOf(
        "tf1plus", "m6plus", "francetv", "arte", "bfmplay", "autres_replay", "ftvthemes"
    )

    // 2026-06-24 : FAST channel folder keys — lazy fetch from data-fast.m3u
    private val FAST_FOLDER_KEYS = setOf(
        "samsung_tvplus", "pluto_tv", "plex_tv", "lg_channels", "rakuten_tv", "sony_one"
    )

    /** Filtre les Replay catégories selon le folderKey (= même regex que
     *  groupSectionsIntoFolders dans LiveTvHubProvider). */
    /** Regex pour chacun des dossiers Replay. Doit être COHÉRENT avec
     *  groupSectionsIntoFolders dans LiveTvHubProvider. */
    // 2026-06-20 v5 : sous-catégories ("Replay TF1 - Séries U.S", etc.)
    // 2026-06-23 : élargi pour englober les sections thématiques "Replay TF1+ Films - <section>"
    // et "Replay TF1+ Séries - <section>" scrapées du site (Top 10, Action, etc.).
    private val tf1PlusRegex = Regex(
        "^Replay (TF1|TMC|TFX|TF1 Séries Films|LCI)(\\s.*)?$|^Replay TF1\\+ (Films|Séries) - .*$"
    )
    private val m6PlusRegex = Regex("^Replay (M6|W9|6ter|Gulli|Paris Première|Téva)(\\s.*)?$")
    private val francetvRegex = Regex(
        "^Replay (France ?[2-5]|France ?24|france ?info|France ?info|France ô|FranceTV|Slash).*",
        RegexOption.IGNORE_CASE,
    )
    // 2026-06-19 (user "dossier Arte ne fonctionne pas") : noms réels du m3u
    //   = "Arte Cinéma", "Arte Histoire", etc. PAS de préfixe "Replay ".
    private val arteRegex = Regex("^Arte.*", RegexOption.IGNORE_CASE)
    // 2026-06-21 : BFM Play = BFM TV, RMC Story, RMC Découverte, BFM Business, RMC Life
    private val bfmPlayRegex = Regex("^Replay (BFM TV|BFM Business|RMC Story|RMC Découverte|RMC Life)(\\s.*)?$")

    private fun filterReplayByFolder(
        allReplays: List<Category>,
        folderKey: String,
    ): List<Category> {
        return when (folderKey) {
            "tf1plus" -> allReplays.filter { tf1PlusRegex.matches(it.name) }
            "m6plus" -> allReplays.filter { m6PlusRegex.matches(it.name) }
            // 2026-06-26 : fusion — le dossier "France TV" regroupe les CHAÎNES
            //   (Replay France 2/3/...) ET les CATÉGORIES thématiques
            //   ("Thématique France TV - <Cat> - <Rayon>"). displayAggregatedCategories
            //   range les chaînes sous "Chaînes" et chaque catégorie à part.
            "francetv" -> allReplays.filter {
                francetvRegex.matches(it.name) || it.name.startsWith("Thématique France TV - ")
            }
            "arte" -> allReplays.filter { arteRegex.matches(it.name) }
            "bfmplay" -> allReplays.filter { bfmPlayRegex.matches(it.name) }
            // 2026-06-26 : Thématiques France TV — sections "Thématique France TV
            //   - <Catégorie> - <Rayon>" (Dessins animés, Cinéma, etc.). Le drill
            //   Catégorie -> Rayon est géré par displayAggregatedCategories.
            "ftvthemes" -> allReplays.filter { it.name.startsWith("Thématique France TV - ") }
            // 2026-06-19 (user "dans autres replay t'as remis toutes les
            //   mêmes catégories") : EXCLURE ce qui est déjà dans les autres
            //   dossiers Replay. Garde uniquement les Replays qui ne sont
            //   matchés par AUCUN des regex précédents.
            "autres_replay" -> allReplays.filter { cat ->
                cat.name.startsWith("Replay ") &&
                !tf1PlusRegex.matches(cat.name) &&
                !m6PlusRegex.matches(cat.name) &&
                !francetvRegex.matches(cat.name) &&
                !arteRegex.matches(cat.name) &&
                !bfmPlayRegex.matches(cat.name)
            }
            else -> emptyList()
        }
    }

    // 2026-06-24 : filtre les FAST catégories par service.
    //   Les group-title du M3U sont "Samsung TV+ - Divertissement", "Pluto TV - Cinema", etc.
    //   On matche sur le préfixe du service.
    private fun filterFastByFolder(
        allFast: List<Category>,
        folderKey: String,
    ): List<Category> {
        val prefix = when (folderKey) {
            "samsung_tvplus" -> "Samsung TV+"
            "pluto_tv" -> "Pluto TV"
            "plex_tv" -> "Plex TV"
            "lg_channels" -> "LG Channels"
            "rakuten_tv" -> "Rakuten TV"
            "sony_one" -> "Sony One"
            else -> return emptyList()
        }
        return allFast.filter { it.name.startsWith(prefix) }
    }

    private fun displayCategories(
        ctx: Context,
        folderName: String,
        categories: List<Category>,
        onChannelSelected: (TvShow) -> Unit,
    ) {
        // 2026-06-20 v6 (user "Films et Séries séparés, quand on clique sur
        //   Films on voit tous les films de TF1+TMC+TFX+etc. agrégés") :
        //   Si les catégories contiennent des sous-catégories (format
        //   "Replay TF1 - Films"), on agrège par le nom de sous-catégorie
        //   (la partie après " - ") → l'user voit "Films", "Séries",
        //   "Divertissement", etc. au lieu de 26+ catégories par chaîne.
        val hasSubCategories = categories.any { " - " in it.name }
        if (hasSubCategories) {
            displayAggregatedCategories(ctx, folderName, categories, onChannelSelected)
            return
        }
        // Si une seule catégorie → saute direct aux chaînes.
        if (categories.size == 1) {
            showChannelsList(ctx, categories[0], onChannelSelected)
            return
        }
        // 2026-06-22 (user "la première liste passe par dessus le mini lecteur
        //   et quand on fait retour ça quitte tout au lieu de revenir") :
        //   utilise un ListView custom au lieu de setItems (qui auto-dismiss le
        //   dialog au clic). Ainsi le dialog 1er niveau RESTE ouvert quand on
        //   navigue dans un sous-dossier, et le BACK du 2e niveau revient ici.
        var dlgCatRef: android.app.AlertDialog? = null
        // 2026-06-23 (user "à l'ouverture de films/séries il faut qu'il y ait
        //   la recherche pour aller chercher dans tous les dossiers") : ajout
        //   d'une recherche live qui filtre les sections par nom de section ET
        //   par titre des programmes dedans.
        // Maintenir une liste mutable filtrée affichée à l'écran.
        var displayCats: List<Category> = categories.toList()
        val labels = displayCats.map { it.name }.toMutableList()
        val adapter = android.widget.ArrayAdapter(
            ctx, android.R.layout.simple_list_item_1, labels,
        )
        val listView = android.widget.ListView(ctx).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, idx, _ ->
                showChannelsList(ctx, displayCats[idx], onChannelSelected)
            }
        }
        val dp = ctx.resources.displayMetrics.density
        val isTV = ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        // ── 2026-06-22 v3 : FrameLayout overlay (badge titre + RETOUR flottant) ──
        // 2026-06-24 : barre titre avec handle de drag ≡ (mobile only)
        val titleLabelCat = android.widget.TextView(ctx).apply {
            text = "📁 $folderName"
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (isTV) 13f else 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((10 * dp).toInt(), (3 * dp).toInt(), (10 * dp).toInt(), (3 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18))
                cornerRadius = 6 * dp
            }
        }
        val (titleTvCat, connectDragCat) = wrapWithDragHandle(ctx, titleLabelCat)
        val retourBtnCat = android.widget.TextView(ctx).apply {
            text = "RETOUR"
            setTextColor(android.graphics.Color.parseColor("#BB4FC3F7"))
            textSize = if (isTV) 12f else 10f
            setPadding((12 * dp).toInt(), (5 * dp).toInt(), (12 * dp).toInt(), (5 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18))
                cornerRadius = 6 * dp
            }
            isFocusable = true
            isFocusableInTouchMode = false
        }
        // 2026-06-23 : champ recherche au-dessus de la liste — filtre en temps réel
        //   par nom de section ET par titre des programmes (TvShow.title) à l'intérieur.
        val searchBox = android.widget.EditText(ctx).apply {
            hint = "🔍 Rechercher dans $folderName…"
            setHintTextColor(android.graphics.Color.parseColor("#88FFFFFF"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (isTV) 14f else 13f
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x2A, 0x2A, 0x2A))
                cornerRadius = 6 * dp
            }
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    val rawQ = (e?.toString() ?: "").trim()
                    val q = normSearch(rawQ)
                    val newCats: List<Category> = if (q.isEmpty()) {
                        categories.toList()
                    } else {
                        categories.mapNotNull { cat ->
                            val matchSec = normSearch(cat.name).contains(q)
                            val items = (cat.list as? List<*>)?.filterIsInstance<TvShow>().orEmpty()
                            val matchingItems = items.filter { normSearch(it.title).contains(q) }
                            when {
                                matchSec -> cat                                    // section match → garde tout
                                matchingItems.isNotEmpty() -> Category(name = cat.name, list = matchingItems)
                                else -> null
                            }
                        }
                    }
                    displayCats = newCats
                    adapter.clear()
                    adapter.addAll(newCats.map {
                        val n = (it.list as? List<*>)?.size ?: 0
                        if (q.isEmpty()) it.name else "${it.name} ($n)"
                    })
                    adapter.notifyDataSetChanged()
                }
            })
        }
        // Titre dans le flux + champ recherche + liste + RETOUR en ligne après la liste
        val contentCol = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(titleTvCat, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            })
            addView(searchBox, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            })
            addView(listView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            // RETOUR aligné à droite après la liste
            addView(retourBtnCat, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.END
                setMargins(0, (2 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            })
        }
        val containerCat = contentCol
        val dlg = android.app.AlertDialog.Builder(ctx)
            .setView(containerCat)
            .create()
        dlgCatRef = dlg
        connectDragCat(dlg)
        retourBtnCat.setOnClickListener { dlg.dismiss() }
        try { dlg.show() } catch (_: android.view.WindowManager.BadTokenException) { return }
        pushDialog(dlg)
        applyThemeBackground(dlg)
        // 2026-06-22 : ajuste la fenêtre pour ne pas couvrir le mini player
        val cleanupMiniPlayer = adjustDialogForMiniPlayer(ctx, dlg)
        dlg.setOnDismissListener { onDialogDismissed(dlg, cleanupMiniPlayer) }
    }

    /** 2026-06-27 (user "Mix FR dans le dossier Autres Replays") : affiche les
     *  catégories Replay normales + une entrée imbriquée "📁 Mix FR" qui ouvre
     *  un sous-dialog avec les groupes de data.m3u (Premium FR, Sport, etc.).
     *  N'altère PAS l'affichage existant d'Autres Replays (les replays restent
     *  une liste à plat), Mix FR est juste une entrée supplémentaire en bas. */
    private fun displayCategoriesWithMixFr(
        ctx: Context,
        folderName: String,
        baseCategories: List<Category>,
        mixCategories: List<Category>,
        onChannelSelected: (TvShow) -> Unit,
        worldwideCategories: List<Category> = emptyList(),
        sportCategories: List<Category> = emptyList(),
        rakutenCategories: List<Category> = emptyList(),
        sonyCategories: List<Category> = emptyList(),
    ) {
        val dp = ctx.resources.displayMetrics.density
        val isTV = ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        // Sous-dossiers : Replays + Mix FR + WorldWide + (déplacés ici) Sport,
        //   Rakuten TV, Sony One. Chacun ouvre son sous-dialog.
        val folders = ArrayList<Pair<String, () -> Unit>>()
        if (baseCategories.isNotEmpty()) folders.add("📺 Replays" to {
            displayCategories(ctx, "Replays", baseCategories, onChannelSelected) })
        if (mixCategories.isNotEmpty()) folders.add("📁 Mix FR" to {
            displayCategories(ctx, "Mix FR", mixCategories, onChannelSelected) })
        if (worldwideCategories.isNotEmpty()) folders.add("🌍 WorldWide" to {
            displayCategories(ctx, "WorldWide", worldwideCategories, onChannelSelected) })
        if (sportCategories.isNotEmpty()) folders.add("🏆 Sport" to {
            displayCategories(ctx, "Sport", sportCategories, onChannelSelected) })
        if (rakutenCategories.isNotEmpty()) folders.add("🎬 Rakuten TV" to {
            displayCategories(ctx, "Rakuten TV", rakutenCategories, onChannelSelected) })
        if (sonyCategories.isNotEmpty()) folders.add("📡 Sony One" to {
            displayCategories(ctx, "Sony One", sonyCategories, onChannelSelected) })

        // 2026-06-27 (user "mets une recherche à l'ouverture du dossier") :
        //   agrège TOUTES les chaînes des sous-dossiers pour une recherche globale.
        val allChannels = ArrayList<TvShow>()
        val seenIds = HashSet<String>()
        for (cat in (baseCategories + mixCategories + worldwideCategories +
                sportCategories + rakutenCategories + sonyCategories)) {
            (cat.list as? List<*>)?.filterIsInstance<TvShow>()?.forEach {
                if (seenIds.add(it.id)) allChannels.add(it)
            }
        }

        val folderAdapter = android.widget.ArrayAdapter(
            ctx, android.R.layout.simple_list_item_1, folders.map { it.first }.toMutableList())
        val folderList = android.widget.ListView(ctx).apply {
            adapter = folderAdapter
            setOnItemClickListener { _, _, idx, _ -> folders[idx].second.invoke() }
        }
        var results: List<TvShow> = emptyList()
        val resultsAdapter = android.widget.ArrayAdapter(
            ctx, android.R.layout.simple_list_item_1, ArrayList<String>())
        val resultsList = android.widget.ListView(ctx).apply {
            adapter = resultsAdapter
            visibility = android.view.View.GONE
            setOnItemClickListener { _, _, idx, _ ->
                results.getOrNull(idx)?.let { ch ->
                    if (ch.id == MiniPlayerController.currentChannelId) dismissAllDialogs()
                    onChannelSelected(ch)
                }
            }
        }
        val searchInput = android.widget.EditText(ctx).apply {
            hint = "🔍 Rechercher une chaîne…"
            setHintTextColor(android.graphics.Color.parseColor("#80FFFFFF"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (isTV) 13f else 12f
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x20, 0x20, 0x20))
                cornerRadius = 6 * dp
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = normSearch(s?.toString()?.trim().orEmpty())
                    if (q.isEmpty()) {
                        folderList.visibility = android.view.View.VISIBLE
                        resultsList.visibility = android.view.View.GONE
                    } else {
                        results = allChannels.filter { normSearch(it.title).contains(q) }.take(400)
                        resultsAdapter.clear()
                        resultsAdapter.addAll(results.map { it.title })
                        resultsAdapter.notifyDataSetChanged()
                        folderList.visibility = android.view.View.GONE
                        resultsList.visibility = android.view.View.VISIBLE
                    }
                }
            })
        }
        val titleLabel = android.widget.TextView(ctx).apply {
            text = "📁 $folderName"
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (isTV) 13f else 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((10 * dp).toInt(), (3 * dp).toInt(), (10 * dp).toInt(), (3 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18))
                cornerRadius = 6 * dp
            }
        }
        val (titleTv, connectDrag) = wrapWithDragHandle(ctx, titleLabel)
        val retourBtn = android.widget.TextView(ctx).apply {
            text = "RETOUR"
            setTextColor(android.graphics.Color.parseColor("#BB4FC3F7"))
            textSize = if (isTV) 12f else 10f
            setPadding((12 * dp).toInt(), (5 * dp).toInt(), (12 * dp).toInt(), (5 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18))
                cornerRadius = 6 * dp
            }
            isFocusable = true
        }
        val contentCol = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(titleTv, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt()) })
            addView(searchInput, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt()) })
            addView(folderList, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(resultsList, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(retourBtn, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.END; setMargins(0, (2 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt()) })
        }
        val dlg = android.app.AlertDialog.Builder(ctx).setView(contentCol).create()
        connectDrag(dlg)
        retourBtn.setOnClickListener { dlg.dismiss() }
        try { dlg.show() } catch (_: android.view.WindowManager.BadTokenException) { return }
        pushDialog(dlg)
        applyThemeBackground(dlg)
        val cleanupMiniPlayer = adjustDialogForMiniPlayer(ctx, dlg)
        dlg.setOnDismissListener { onDialogDismissed(dlg, cleanupMiniPlayer) }
    }

    /** 2026-06-27 : dossier Plex unique structuré en 3 sous-dossiers
     *  (📡 Chaînes en direct / 🎬 Films / 📺 Séries) pour éviter le vrac.
     *  Chaque sous-dossier ouvre ses catégories (genres) → items. */
    private fun displayPlexFolder(
        ctx: Context,
        folderName: String,
        allCats: List<Category>,
        onChannelSelected: (TvShow) -> Unit,
    ) {
        val live = allCats.filter { it.name == "Chaînes en direct" }
        val films = allCats.filter { it.name.startsWith("Films — ") }
            .map { Category(name = it.name.removePrefix("Films — "), list = it.list) }
        val series = allCats.filter { it.name.startsWith("Séries — ") }
            .map { Category(name = it.name.removePrefix("Séries — "), list = it.list) }
        val folders = ArrayList<Pair<String, () -> Unit>>()
        if (live.isNotEmpty()) folders.add("📡 Chaînes en direct" to {
            if (live.size == 1) showChannelsList(ctx, live[0], onChannelSelected)
            else displayCategories(ctx, "Chaînes en direct", live, onChannelSelected)
        })
        if (films.isNotEmpty()) folders.add("🎬 Films" to {
            displayCategories(ctx, "Films", films, onChannelSelected)
        })
        if (series.isNotEmpty()) folders.add("📺 Séries" to {
            displayCategories(ctx, "Séries", series, onChannelSelected)
        })
        if (folders.isEmpty()) { displayCategories(ctx, folderName, allCats, onChannelSelected); return }
        // 2026-07-04 (user "quand on va dans Stream4Free on pourrait verrouiller
        //   un film ou une série ou un Live") : passe le parentFolderKey pour
        //   activer le contrôle parental sur chaque sous-dossier (long-press).
        showSimpleFolderList(ctx, folderName, folders, parentFolderKey = "folder:stream4")
    }

    /** 2026-06-27 : dossier Pluto unique en 3 sous-dossiers (📡 Chaînes / 🎬 Films /
     *  📺 Séries). Catégories préfixées "Live — " / "Films — " / "Séries — ". */
    private fun displayPlutoFolder(
        ctx: Context,
        folderName: String,
        allCats: List<Category>,
        onChannelSelected: (TvShow) -> Unit,
    ) {
        fun strip(prefix: String) = allCats.filter { it.name.startsWith(prefix) }
            .map { Category(name = it.name.removePrefix(prefix), list = it.list) }
        val live = strip("Live — ")
        val films = strip("Films — ")
        val series = strip("Séries — ")
        val folders = ArrayList<Pair<String, () -> Unit>>()
        if (live.isNotEmpty()) folders.add("📡 Chaînes en direct" to {
            displayCategories(ctx, "Chaînes en direct", live, onChannelSelected)
        })
        if (films.isNotEmpty()) folders.add("🎬 Films" to {
            displayCategories(ctx, "Films", films, onChannelSelected)
        })
        if (series.isNotEmpty()) folders.add("📺 Séries" to {
            displayCategories(ctx, "Séries", series, onChannelSelected)
        })
        if (folders.isEmpty()) { displayCategories(ctx, folderName, allCats, onChannelSelected); return }
        // 2026-07-04 : idem que Stream4 — active le contrôle parental sur les
        //   sous-dossiers Pluto (Chaînes en direct / Films / Séries).
        showSimpleFolderList(ctx, folderName, folders, parentFolderKey = "folder:pluto_tv")
    }

    /** Petit dialog liste-de-dossiers réutilisable (titre + liste + RETOUR).
     *
     *  2026-07-04 (user "quand on va dans Stream4Free on pourrait verrouiller
     *  un film ou une série ou un Live") : `parentFolderKey` optionnel — si
     *  fourni, active le contrôle parental sur chaque sous-dossier (long-press
     *  → PIN dialog, gate au click). Si null → comportement legacy (pas de verrou). */
    private fun showSimpleFolderList(
        ctx: Context,
        folderName: String,
        folders: List<Pair<String, () -> Unit>>,
        parentFolderKey: String? = null,
    ) {
        val dp = ctx.resources.displayMetrics.density
        val isTV = ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val adapter = android.widget.ArrayAdapter(
            ctx, android.R.layout.simple_list_item_1, folders.map { it.first }.toMutableList(),
        )
        val listView = android.widget.ListView(ctx).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, idx, _ ->
                val (label, action) = folders[idx]
                // 2026-07-04 : gate contrôle parental sur les sous-dossiers.
                //   Si le sous-dossier est verrouillé ET pas débloqué session
                //   → PIN dialog (unlock 30 min) puis lance l'action.
                if (parentFolderKey != null) {
                    val subKey = com.streamflixreborn.streamflix.utils.TvHubFolderLockStore
                        .subFolderKey(parentFolderKey, label)
                    if (!com.streamflixreborn.streamflix.utils.TvHubFolderLockStore
                            .isAccessible(ctx, subKey)) {
                        com.streamflixreborn.streamflix.ui.PinDialog.showAuth(
                            context = ctx,
                            title = "🔒 $label — Code parental",
                            onSuccess = {
                                com.streamflixreborn.streamflix.utils.TvHubFolderLockStore
                                    .unlockForSession(subKey)
                                action.invoke()
                            },
                            onCancel = null,
                        )
                        return@setOnItemClickListener
                    }
                    // Refresh timestamp (30 min glissantes) puis exécute l'action.
                    com.streamflixreborn.streamflix.utils.TvHubFolderLockStore
                        .touchSessionUnlock(subKey)
                }
                action.invoke()
            }
            // 2026-07-04 : long-press sur un sous-dossier → menu contrôle parental
            //   (verrouiller/déverrouiller par PIN). Feature disponible SEULEMENT
            //   si parentFolderKey a été fourni par le caller.
            if (parentFolderKey != null) {
                setOnItemLongClickListener { _, _, idx, _ ->
                    val label = folders[idx].first
                    val subKey = com.streamflixreborn.streamflix.utils.TvHubFolderLockStore
                        .subFolderKey(parentFolderKey, label)
                    val wasLocked = com.streamflixreborn.streamflix.utils.TvHubFolderLockStore
                        .isLocked(ctx, subKey)
                    val option = if (wasLocked) "🔓 Retirer le contrôle parental"
                                 else "🔒 Contrôle parental (verrouiller)"
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle(label)
                        .setItems(arrayOf(option)) { _, _ ->
                            val title = if (wasLocked) "Retirer le contrôle parental"
                                        else "Activer le contrôle parental"
                            com.streamflixreborn.streamflix.ui.PinDialog.showAuth(
                                context = ctx,
                                title = title,
                                onSuccess = {
                                    val nowLocked = com.streamflixreborn.streamflix.utils.TvHubFolderLockStore
                                        .toggleLock(ctx, subKey)
                                    val msg = if (nowLocked) "🔒 $label verrouillé"
                                              else "🔓 $label déverrouillé"
                                    android.widget.Toast.makeText(ctx, msg,
                                        android.widget.Toast.LENGTH_SHORT).show()
                                },
                                onCancel = null,
                            )
                        }
                        .show()
                    true
                }
            }
        }
        val titleLabel = android.widget.TextView(ctx).apply {
            text = "📁 $folderName"
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (isTV) 13f else 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((10 * dp).toInt(), (3 * dp).toInt(), (10 * dp).toInt(), (3 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18)); cornerRadius = 6 * dp
            }
        }
        val (titleTv, connectDrag) = wrapWithDragHandle(ctx, titleLabel)
        val retourBtn = android.widget.TextView(ctx).apply {
            text = "RETOUR"
            setTextColor(android.graphics.Color.parseColor("#BB4FC3F7"))
            textSize = if (isTV) 12f else 10f
            setPadding((12 * dp).toInt(), (5 * dp).toInt(), (12 * dp).toInt(), (5 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18)); cornerRadius = 6 * dp
            }
            isFocusable = true
        }
        val contentCol = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(titleTv, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt()) })
            addView(listView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(retourBtn, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.END; setMargins(0, (2 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt()) })
        }
        val dlg = android.app.AlertDialog.Builder(ctx).setView(contentCol).create()
        connectDrag(dlg)
        retourBtn.setOnClickListener { dlg.dismiss() }
        try { dlg.show() } catch (_: android.view.WindowManager.BadTokenException) { return }
        pushDialog(dlg)
        applyThemeBackground(dlg)
        val cleanupMiniPlayer = adjustDialogForMiniPlayer(ctx, dlg)
        dlg.setOnDismissListener { onDialogDismissed(dlg, cleanupMiniPlayer) }
    }

    /** 2026-06-21 v7 : agrège les sous-catégories TF1+ par nom de catégorie.
     *  "Replay TF1 - Films" + "Replay TMC - Films" → "Films" avec liste
     *  fusionnée. Les catégories SANS " - " (= placeholders vides comme
     *  "Replay TF1") sont ignorées — les cartes login/status (🔓/✓) sont
     *  sorties et affichées UNE SEULE FOIS en haut du dialog, pas 5x.
     *  Les items réels des catégories de base sont agrégés dans
     *  "Programmes" au lieu de l'ancien "Autre". */
    private fun displayAggregatedCategories(
        ctx: Context,
        folderName: String,
        categories: List<Category>,
        onChannelSelected: (TvShow) -> Unit,
    ) {
        // Ordre d'affichage des sous-catégories
        val subCatOrder = mapOf(
            "Séries" to 1,
            "Films" to 2,
            "Divertissement" to 3,
            "Téléfilms" to 4,
            "Info" to 5,
            "Docs" to 6,
            "Sport" to 7,
            "Jeunesse" to 8,
            "Impact" to 9,
            "Programmes" to 10,
            "Direct" to 11,
        )
        // Agrège : extrait la partie après " - " comme clé de sous-catégorie.
        // Les cartes login/status (__login_) sont filtrées → une seule sera
        // affichée en haut du dialog si besoin.
        // 2026-06-23 (user "Il est où le dossier film avec toutes les catégories
        //   dedans") : pour les categories "Replay TF1+ Films - <section>" et
        //   "Replay TF1+ Séries - <section>", on les regroupe sous "Films" /
        //   "Séries" au niveau 1, et au CLIC sur "Films" on ouvre un sous-dialog
        //   listant les sections thématiques (Top 10, Action, Comédies, etc.).
        val aggregated = LinkedHashMap<String, MutableList<TvShow>>()
        // themedGroups["Films"]["Top 10"] = [items] — pour 2e niveau de dialog
        val themedGroups = LinkedHashMap<String, LinkedHashMap<String, MutableList<TvShow>>>()
        var loginCard: TvShow? = null   // On ne garde QU'UNE carte login
        for (cat in categories) {
            val channels = (cat.list as? List<*>)?.filterIsInstance<TvShow>().orEmpty()
            if (channels.isEmpty()) continue
            // Sépare les cartes login/status des vrais items
            val realItems = channels.filter { !it.id.contains("__login_") }
            val loginItems = channels.filter { it.id.contains("__login_") }
            if (loginItems.isNotEmpty() && loginCard == null) {
                loginCard = loginItems.first()  // Garde la 1ère
            }
            if (realItems.isEmpty()) continue
            // 2026-06-23 : détection des préfixes thématiques TF1+ ET M6+
            // Pour TF1+ : "Replay TF1+ Films - <section>" → ("Films", "<section>")
            //   Sections thématiques scrapées du site (Top 10, Action, etc.).
            // Pour M6+ et autres : "Replay <chaîne> - Films" → ("Films", "<chaîne>")
            //   M6+ HTML est JS-rendered → pas de sections thématiques accessibles.
            //   On utilise la chaîne (M6, W9, 6ter, Gulli, etc.) comme axe
            //   secondaire pour offrir le pattern 2-niveaux + "Tous les films".
            val m6PlusChannels = setOf("M6", "W9", "6ter", "Gulli", "Paris Première", "Téva")
            val bfmPlayChannels = setOf("BFM TV", "BFM Business", "RMC Story", "RMC Découverte", "RMC Life")
            val themedPrefix: Pair<String, String>? = when {
                cat.name.startsWith("Replay TF1+ Films - ") -> "Films" to cat.name.removePrefix("Replay TF1+ Films - ")
                cat.name.startsWith("Replay TF1+ Séries - ") -> "Séries" to cat.name.removePrefix("Replay TF1+ Séries - ")
                // 2026-06-23 (user "il faudrait faire un sous-dossier Musique
                //   sur Arte comme sur le site") : regroupe TOUS les
                //   "Arte Concert - X" (Classique, Jazz, Metal, etc.) dans un
                //   parent "Musique" → 2-niveaux : Arte → Musique → genre.
                cat.name.startsWith("Arte Concert - ") -> "Musique" to cat.name.removePrefix("Arte Concert - ")
                // 2026-06-26 : France TV thématiques — "Thématique France TV -
                //   <Catégorie> - <Rayon>" → parent = Catégorie (Cinéma, Enfants,
                //   Séries et fictions...), section = Rayon (3-5 ans, Notre
                //   sélection...). 2-niveaux : France TV → Catégorie → Rayon.
                cat.name.startsWith("Thématique France TV - ") -> {
                    val rest = cat.name.removePrefix("Thématique France TV - ")
                    val parentCat = rest.substringBefore(" - ")
                    val rail = if (" - " in rest) rest.substringAfter(" - ") else "Programmes"
                    parentCat to rail
                }
                // 2026-06-26 : chaînes France TV (Replay France 2/3/4/5/Franceinfo/
                //   Slash/Sport/France 24/France Ô, éventuellement suffixées
                //   " - Séries/Films") → parent "Chaînes", section = nom de chaîne.
                francetvRegex.matches(cat.name) -> {
                    val chan = cat.name.removePrefix("Replay ").substringBefore(" - ").trim()
                    "Chaînes" to chan
                }
                else -> {
                    // Détection des "Replay <chaîne> - Films" / "Replay <chaîne> - Séries"
                    val m = Regex("^Replay (.+?) - (Films|Séries|Téléfilms)$").matchEntire(cat.name)
                    if (m != null) {
                        val chan = m.groupValues[1]
                        val type = m.groupValues[2]
                        if (chan in m6PlusChannels || chan in bfmPlayChannels) {
                            type to chan
                        } else null
                    } else null
                }
            }
            if (themedPrefix != null) {
                val (parent, section) = themedPrefix
                themedGroups.getOrPut(parent) { LinkedHashMap() }
                    .getOrPut(section) { mutableListOf() }.addAll(realItems)
                // ON AJOUTE AUSSI au compteur aggregated pour le total affiché.
                aggregated.getOrPut(parent) { mutableListOf() }.addAll(realItems)
                continue
            }
            val subCatName = if (" - " in cat.name) {
                cat.name.substringAfter(" - ").trim()
            } else if (cat.name.startsWith("Arte ")) {
                // 2026-06-23 (user "tout est dans Programmes 414 au lieu d'être
                //   séparé en Cinéma/Histoire/Sciences/Voyages comme sur Arte") :
                //   pour Arte les catégories thématiques sont nommées sans
                //   tiret ("Arte Cinéma", "Arte Histoire", etc.). On strip le
                //   préfixe "Arte " pour avoir "Cinéma", "Histoire", "Voyages
                //   et découvertes" (= contient les programmes animaux/nature),
                //   "Documentaires et reportages", "Sciences", etc.
                cat.name.removePrefix("Arte ")
            } else {
                // Catégorie de base sans suffixe (ex "Replay TF1")
                // → les vrais items vont dans "Programmes"
                "Programmes"
            }
            aggregated.getOrPut(subCatName) { mutableListOf() }.addAll(realItems)
        }
        if (aggregated.isEmpty() && loginCard == null) {
            android.widget.Toast.makeText(
                ctx, "Aucun contenu dans $folderName", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        // Tri par ordre défini, puis alphabétique pour les non listés
        val sortedEntries = aggregated.entries.sortedWith(
            compareBy(
                { subCatOrder[it.key] ?: 99 },
                { it.key }
            )
        )
        // Si une seule sous-catégorie et pas de carte login → saute direct
        if (sortedEntries.size == 1 && loginCard == null) {
            val entry = sortedEntries[0]
            val mergedCat = Category(name = entry.key, list = entry.value)
            showChannelsList(ctx, mergedCat, onChannelSelected)
            return
        }
        // Construit la liste des items du dialog
        val displayEntries = mutableListOf<Pair<String, List<TvShow>>>()
        // Carte connexion unique en tête si présente
        if (loginCard != null) {
            displayEntries.add("🔓 Connexion" to listOf(loginCard))
        }
        for (entry in sortedEntries) {
            displayEntries.add("${entry.key} (${entry.value.size})" to entry.value)
        }
        // 2026-06-22 (user "la première liste passe par dessus le mini lecteur
        //   et quand on fait retour ça quitte tout au lieu de revenir") :
        //   ListView custom au lieu de setItems → pas d'auto-dismiss.
        var dlgAggRef: android.app.AlertDialog? = null
        val labels = displayEntries.map { it.first }
        val listView = android.widget.ListView(ctx).apply {
            adapter = android.widget.ArrayAdapter(
                ctx,
                android.R.layout.simple_list_item_1,
                labels,
            )
            setOnItemClickListener { _, _, idx, _ ->
                val (label, shows) = displayEntries[idx]
                if (label.startsWith("🔓")) {
                    dismissAllDialogs()
                    // 2026-06-23 (user "le bouton connexion lance un playback
                    //   au lieu d'ouvrir la webview") : router DIRECTEMENT
                    //   vers TF1LoginDialog/BfmLoginDialog/LoginWebViewActivity
                    //   selon le service au lieu d'appeler onChannelSelected
                    //   (= qui lance le player car le TvShow login n'a pas de
                    //   URL valide).
                    val firstId = shows.firstOrNull()?.id ?: ""
                    if (firstId.startsWith("livehub::replay::__login_")) {
                        val svc = firstId.removePrefix("livehub::replay::__login_").removeSuffix("__").lowercase()
                        when (svc) {
                            "tf1" -> com.streamflixreborn.streamflix.activities.TF1LoginDialog.show(ctx)
                            "bfm" -> {
                                if (com.streamflixreborn.streamflix.utils.BfmSsoAuth.hasCredentials(ctx)) {
                                    android.widget.Toast.makeText(ctx, "Reconnexion BFM Play…", android.widget.Toast.LENGTH_SHORT).show()
                                    kotlinx.coroutines.CoroutineScope(
                                        kotlinx.coroutines.Dispatchers.IO +
                                        kotlinx.coroutines.SupervisorJob()
                                    ).launch {
                                        val token = try {
                                            com.streamflixreborn.streamflix.utils.BfmSsoAuth.reloginFromSaved(ctx)
                                        } catch (_: Throwable) { null }
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            if (token != null) {
                                                android.widget.Toast.makeText(ctx, "✓ Reconnecté à BFM Play", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                com.streamflixreborn.streamflix.activities.BfmLoginDialog.show(ctx)
                                            }
                                        }
                                    }
                                } else {
                                    com.streamflixreborn.streamflix.activities.BfmLoginDialog.show(ctx)
                                }
                            }
                            else -> com.streamflixreborn.streamflix.activities.LoginWebViewActivity.start(
                                ctx,
                                com.streamflixreborn.streamflix.activities.LoginWebViewActivity.SERVICE_M6
                            )
                        }
                    } else {
                        onChannelSelected(shows.first())
                    }
                } else {
                    // 2026-06-23 : si la sous-catégorie a des sections thématiques
                    //   (themedGroups), on ouvre un sous-dialog avec ces sections
                    //   (= niveau 2 : "Films" → Top 10, Action, Comédies, etc.)
                    //   au lieu d'aller direct aux items.
                    val subCatKey = label.substringBefore(" (")
                    val themedSections = themedGroups[subCatKey]
                    if (themedSections != null && themedSections.isNotEmpty()) {
                        val themedCats = themedSections.map { (sectionName, items) ->
                            Category(name = sectionName, list = items)
                        }
                        // 2026-06-23 (user "il faudrait qu'il reste aussi comme ça
                        //   le dossier où il y a tout en vrac dedans plus toutes
                        //   les catégories en dessous") : tête de liste avec
                        //   "📁 Tous les <subCatKey>" = union de tous les
                        //   programmes des sections thématiques (= avec doublons
                        //   intentionnels car un film peut être dans plusieurs
                        //   sections du site, ex Camping dans Top 10 + Comédies
                        //   + Cinéma français → 3 entries). Le total match
                        //   alors la somme des compteurs de sections.
                        val allItems = themedSections.values.flatten()
                        val allLabel = "📁 Tous les ${subCatKey.lowercase()} (${allItems.size})"
                        val combinedCats = listOf(Category(name = allLabel, list = allItems)) + themedCats
                        // Re-utilise displayCategories pour le 2e niveau
                        displayCategories(ctx, "$folderName / $subCatKey", combinedCats, onChannelSelected)
                    } else {
                        val mergedCat = Category(name = subCatKey, list = shows)
                        showChannelsList(ctx, mergedCat, onChannelSelected)
                    }
                }
            }
        }
        val dp2 = ctx.resources.displayMetrics.density
        val isTV2 = ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        // ── 2026-06-22 v3 : FrameLayout overlay (badge titre + RETOUR flottant) ──
        val titleTvAgg = android.widget.TextView(ctx).apply {
            text = "📁 $folderName"
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (isTV2) 13f else 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((10 * dp2).toInt(), (3 * dp2).toInt(), (10 * dp2).toInt(), (3 * dp2).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18))
                cornerRadius = 6 * dp2
            }
        }
        val (titleBarAgg, connectDragAgg) = wrapWithDragHandle(ctx, titleTvAgg)
        val retourBtnAgg = android.widget.TextView(ctx).apply {
            text = "RETOUR"
            setTextColor(android.graphics.Color.parseColor("#BB4FC3F7"))
            textSize = if (isTV2) 12f else 10f
            setPadding((12 * dp2).toInt(), (5 * dp2).toInt(), (12 * dp2).toInt(), (5 * dp2).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18))
                cornerRadius = 6 * dp2
            }
            isFocusable = true
            isFocusableInTouchMode = false
        }
        // Titre dans le flux + liste + RETOUR en ligne après la liste
        val contentColAgg = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(titleBarAgg, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((6 * dp2).toInt(), (4 * dp2).toInt(), (6 * dp2).toInt(), (2 * dp2).toInt())
            })
            addView(listView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(retourBtnAgg, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.END
                setMargins(0, (2 * dp2).toInt(), (10 * dp2).toInt(), (6 * dp2).toInt())
            })
        }
        val containerAgg = contentColAgg
        val dlg = android.app.AlertDialog.Builder(ctx)
            .setView(containerAgg)
            .create()
        dlgAggRef = dlg
        retourBtnAgg.setOnClickListener { dlg.dismiss() }
        try { dlg.show() } catch (_: android.view.WindowManager.BadTokenException) { return }
        pushDialog(dlg)
        connectDragAgg(dlg)
        applyThemeBackground(dlg)
        // 2026-06-22 : ajuste la fenêtre pour ne pas couvrir le mini player
        val cleanupMiniPlayer = adjustDialogForMiniPlayer(ctx, dlg)
        dlg.setOnDismissListener { onDialogDismissed(dlg, cleanupMiniPlayer) }
    }

    /** Affiche la liste des chaînes d'une catégorie dans un dialog.
     *  2026-06-21 (user "afficher les films avec les jaquettes") : si les items
     *  ont des posters (tvg-logo dans le m3u), on affiche une GRILLE avec images
     *  au lieu d'une simple liste texte. */
    private fun showChannelsList(
        ctx: Context,
        category: Category,
        onChannelSelected: (TvShow) -> Unit,
    ) {
        val channels = (category.list as? List<*>)?.filterIsInstance<TvShow>().orEmpty()
        if (channels.isEmpty()) {
            android.widget.Toast.makeText(
                ctx, "Aucune chaîne dans ${category.name}", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        // 2026-06-20 v11 : la grille posters fonctionne aussi sur TV.
        //   Les cellules NE SONT PLUS focusables — c'est le GridView qui
        //   gère le focus D-pad via descendantFocusability=FOCUS_BLOCK_DESCENDANTS.
        //   Liste texte = UNIQUEMENT pour les chaînes IPTV (= pas de jaquettes).
        val isIptvChannel = channels.any {
            it.id.startsWith("livehub::otf::") ||
            it.id.startsWith("livehub::witv::") ||
            it.id.startsWith("livehub::sport::") ||
            it.id.startsWith("livehub::vegeta::")
        }
        // 2026-06-25 v2 (user "tous les sous-dossiers doivent suivre la règle
        //   de redimensions") : on FORCE showPosterGrid partout. Items sans
        //   poster auront un placeholder ic_menu_view au lieu de fallback
        //   text-list. Comportement uniforme tous sub-folders.
        showPosterGrid(ctx, category, channels, onChannelSelected)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2026-06-22 (user "le mini lecteur reste où il est, les listes
    //   s'écrasent en dessous — bouton retour toujours accessible")
    //
    // Ajuste la fenêtre du dialog pour ne PAS couvrir le mini player du
    //   home. Le dialog se colle en bas et prend la hauteur restante.
    //   Observe MPC.state pour ajuster dynamiquement si le mini player
    //   démarre/s'arrête pendant que le dialog est ouvert.
    //   Retourne une lambda de cleanup (cancel scope).
    // ══════════════════════════════════════════════════════════════════════
    private fun adjustDialogForMiniPlayer(
        ctx: Context,
        dlg: android.app.AlertDialog,
    ): () -> Unit {
        val MPC = com.streamflixreborn.streamflix.utils.MiniPlayerController
        val displayH = ctx.resources.displayMetrics.heightPixels

        fun getMiniPlayerHeight(): Int {
            val activity = ctx as? android.app.Activity ?: return 0
            val container = activity.findViewById<android.view.View>(
                com.streamflixreborn.streamflix.R.id.mini_player_container
            ) ?: return 0
            return if (container.visibility == android.view.View.VISIBLE && container.height > 0) {
                container.height
            } else 0
        }

        fun adjust(active: Boolean) {
            if (!dlg.isShowing) return
            try {
            dlg.window?.let { w ->
                if (active) {
                    // 2026-06-23 v8 : extraDp APPLIQUÉ UNIQUEMENT EN MOBILE.
                    //   En TV (= layout différent, mini-player TOP-RIGHT 50%
                    //   width, dialog avec side panel gauche), l'ancien
                    //   comportement (= miniH brut sans extra) est correct.
                    //   Mon extra +70dp sur TV cassait l'alignement de la
                    //   sidebar de catégories du dialog (user "tu as modifié
                    //   la barre TV alors que je t'avais pas demandé").
                    val isTV = ctx.resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                    val measuredH = getMiniPlayerHeight()
                    val density = ctx.resources.displayMetrics.density
                    val miniH = if (isTV) {
                        // TV : ancien comportement, fallback 80dp
                        measuredH.coerceAtLeast((80 * density).toInt())
                    } else {
                        // Mobile : +70dp pour passer sous la barre des contrôles
                        val extraDp = 70
                        val fallbackDp = 290
                        if (measuredH > 0) measuredH + (extraDp * density).toInt()
                        else (fallbackDp * density).toInt()
                    }
                    val maxH = displayH - miniH
                    val savedRatio = com.streamflixreborn.streamflix.utils.UserPreferences.homeTopOffset
                    // 2026-06-24 (user "le dialog s'ouvre plein écran sans
                    //   drag handle") : si jamais réglé (savedRatio==0),
                    //   force 60% par défaut pour exposer la barre ≡ et
                    //   laisser respirer le mini-player. MOBILE ONLY — TV
                    //   garde l'ancien comportement plein-largeur car la
                    //   sidebar de catégories TV dépend de maxH brut.
                    val finalH = if (isTV) {
                        if (savedRatio in 1..99)
                            ((displayH * savedRatio) / 100).coerceAtMost(maxH)
                        else maxH
                    } else {
                        val ratio = if (savedRatio in 1..99) savedRatio else 60
                        ((displayH * ratio) / 100).coerceAtMost(maxH)
                    }
                    w.setGravity(android.view.Gravity.BOTTOM)
                    w.setLayout(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        finalH,
                    )
                    android.util.Log.d("LiveHubDialog", "adjustDialog v8: miniH=$miniH (measured=$measuredH, isTV=$isTV), displayH=$displayH, finalH=$finalH, density=$density")
                    // 2026-06-22 (user "le haut s'assombrit quand la liste arrive,
                    //   on voit moins bien le mini lecteur") : supprimer le dim
                    //   pour ne pas assombrir la zone du mini player.
                    w.setDimAmount(0f)
                } else {
                    // 2026-06-24 : respecte la hauteur sauvegardée par le drag handle.
                    //   Si jamais réglé (savedRatio==0), force 70% par défaut MOBILE
                    //   seulement (TV garde plein écran historique).
                    val isTvForElse = ctx.resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                    val savedRatio = com.streamflixreborn.streamflix.utils.UserPreferences.homeTopOffset
                    if (savedRatio in 1..99) {
                        w.setGravity(android.view.Gravity.BOTTOM)
                        w.setLayout(
                            android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            (displayH * savedRatio) / 100,
                        )
                        w.setDimAmount(0f)
                    } else if (isTvForElse) {
                        // TV jamais réglé → plein écran historique
                        w.setGravity(android.view.Gravity.CENTER)
                        w.setLayout(
                            android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        )
                        w.setDimAmount(0.4f)
                    } else {
                        // Mobile jamais réglé → 70% bottom-sheet
                        w.setGravity(android.view.Gravity.BOTTOM)
                        w.setLayout(
                            android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            (displayH * 70) / 100,
                        )
                        w.setDimAmount(0f)
                    }
                }
                w.decorView.setPadding(0, 0, 0, 0)
            }
            } catch (_: IllegalArgumentException) { /* view not attached to window manager */ }
        }

        // 2026-06-24 (user "Cinéma s'ouvre en plein écran") : MPC.state peut
        //   être en transition (Buffering/Error) au moment de l'ouverture du
        //   dialog → adjust(false) → plein écran malgré mini player visible.
        //   Solution : considérer le mini-player ACTIF si SON CONTAINER est
        //   VISIBLE à l'écran (= source de vérité visuelle).
        val initialActive = MPC.state.value is MiniPlayerController.State.Playing ||
                MPC.state.value is MiniPlayerController.State.Loading ||
                getMiniPlayerHeight() > 0
        adjust(initialActive)

        // 2026-06-23 v6 (user "c'était bien aligné la barre et tout d'un coup
        //   elle s'est remis par dessus tout") : ANTI-FLICKER. Une fois que le
        //   dialog est ouvert avec le mini visible (= adjust(true) au moins
        //   une fois), on NE le repositionne JAMAIS en plein écran même si le
        //   mini passe brièvement en Error/Idle (= sticky retry du player live
        //   qui fait basculer state pendant ~2s). Le dialog reste sous la
        //   zone du mini-player jusqu'à sa fermeture par l'user.
        val miniWasVisibleOnce = java.util.concurrent.atomic.AtomicBoolean(initialActive)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            MPC.state.collect { state ->
                val active = state is MiniPlayerController.State.Playing ||
                        state is MiniPlayerController.State.Loading
                if (active) {
                    miniWasVisibleOnce.set(true)
                    adjust(true)
                } else if (!miniWasVisibleOnce.get()) {
                    // Le mini n'a jamais été visible — OK de repasser en plein écran
                    adjust(false)
                }
                // Sinon (mini brièvement perdu après avoir été visible) : on ne touche pas
            }
        }

        return { scope.coroutineContext[Job]?.cancel() }
    }

    /** Affiche une grille avec jaquettes (posters) pour les films/séries. */
    private fun showPosterGrid(
        ctx: Context,
        category: Category,
        channels: List<TvShow>,
        onChannelSelected: (TvShow) -> Unit,
    ) {
        // 2026-07-05 (user "chaînes du même dossier affiche des IPTV au lieu du
        //   dossier") : persiste les chaînes de la grille dans folderContents
        //   pour que getOrderedChannelIds → folderSiblingsOf retrouve les frères
        //   du dossier courant. La clé "__grid_active" est écrasée à chaque
        //   ouverture de grille = toujours les chaînes de la dernière grille vue.
        //   N'est PAS touchée par groupSectionsIntoFolders (clé interne __).
        LiveTvHubProvider.folderContents["__grid_active"] = listOf(
            Category(name = category.name, list = channels)
        )
        val MPC = com.streamflixreborn.streamflix.utils.MiniPlayerController
        val dp = ctx.resources.displayMetrics.density
        val isTV = ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        // 2026-06-20 v3 (user "World Live dans les dossiers c'est toujours aussi
        //   gros sur l'Oppo") : mobile réduit à 4 cols 80×110dp (= au lieu de
        //   3×95dp qui débordait). TV inchangé (6 cols 95×130dp).
        // 2026-06-22 v2 (user "logos trop gros et pas beaux, 2 barres noires
        //   inutiles") : logos réduits, plus de colonnes, layout compact.
        val numColumns = if (isTV) 6 else 4
        val posterW = if (isTV) (80 * dp).toInt() else (72 * dp).toInt()
        val posterH = if (isTV) (80 * dp).toInt() else (72 * dp).toInt()

        // 2026-06-20 v13 : cellules NON focusables — le GridView gère le focus
        //   D-pad nativement via son sélecteur + onItemClickListener.
        //   v11 (FOCUS_BLOCK_DESCENDANTS) et v12 (cellules focusables + per-cell
        //   onClick) ne marchaient pas car AbsListView intercepte DPAD_CENTER
        //   AVANT les enfants. Sans onItemClickListener, l'événement est consommé
        //   sans effet. Solution : cellules pas focusables + onItemClickListener.
        var dlgRef: android.app.AlertDialog? = null
        val filteredChannels = channels.toMutableList()

        // ── Barre de recherche ──
        val searchInput = android.widget.EditText(ctx).apply {
            hint = "🔍 Rechercher…"
            setHintTextColor(android.graphics.Color.GRAY)
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (isTV) 16f else 14f
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
        }

        val gridView = android.widget.GridView(ctx).apply {
            this.numColumns = numColumns
            horizontalSpacing = (if (isTV) 8 else 8).let { (it * dp).toInt() }
            verticalSpacing = (if (isTV) 8 else 8).let { (it * dp).toInt() }
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            clipToPadding = false  // voir les items qui arrivent en scrollant
            // Sélecteur natif GridView = surbrillance D-pad sans focusable cells
            setSelector(android.R.drawable.list_selector_background)
        }

        val gridAdapter = object : android.widget.BaseAdapter() {
            override fun getCount() = filteredChannels.size
            override fun getItem(pos: Int) = filteredChannels[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val ch = filteredChannels[pos]
                val isCurrent = ch.id == MPC.currentChannelId
                val cell = (convertView as? android.widget.LinearLayout) ?: run {
                    android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                        // v13 : PAS focusable — le GridView gère focus + sélection
                        val imgView = android.widget.ImageView(ctx).apply {
                            tag = "poster"
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                posterW, posterH
                            )
                        }
                        addView(imgView)
                        val tvTitle = android.widget.TextView(ctx).apply {
                            tag = "title"
                            // 2026-06-20 (user "2 lignes [doivent être] assez
                            //   espacées pour que la lecture soit correcte") :
                            //   3 lignes max + line spacing 1.15 + plus de
                            //   padding vertical pour respirer.
                            maxLines = 3
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setLineSpacing(0f, 1.15f)
                            gravity = android.view.Gravity.CENTER
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = if (isTV) 14f else 12f
                            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                        }
                        addView(tvTitle)
                    }
                }
                val imgView = cell.findViewWithTag<android.widget.ImageView>("poster")
                val tvTitle = cell.findViewWithTag<android.widget.TextView>("title")
                // 2026-06-20 (user "L&apos;Étudiante") : décoder les entités
                //   HTML qui traînent dans certains M3U (L&apos;, &amp;, etc.).
                val decodedTitle = try {
                    android.text.Html.fromHtml(ch.title,
                        android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                } catch (_: Throwable) { ch.title }
                // 2026-07-05 (user "le favori a bien fonctionné mais il n'apparaît
                //   pas sur l'interface") : indicateur ★ sur les chaînes favorites.
                //   Même logique que le long-press : replay + dynamique (segments
                //   imbriqués) → ReplayFavoritesStore ; curé simple → IptvFavoritesStore.
                val chIsDynamic = ch.id.startsWith("livehub::") &&
                    !ch.id.startsWith("livehub::replay::") &&
                    !ch.id.startsWith("livehub::folder::") &&
                    ch.id.removePrefix("livehub::").contains("::")
                val isFavHere = when {
                    ch.id.startsWith("livehub::replay::") || chIsDynamic ->
                        com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.isFavorite(ch.id)
                    ch.id.startsWith("livehub::") ->
                        com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                            .isFavorite(ch.providerName ?: "TV Hub", ch.id)
                    else -> false
                }
                val prefix = when {
                    isCurrent -> "▶ "
                    isFavHere -> "★ "
                    else -> ""
                }
                tvTitle.text = "$prefix$decodedTitle"
                tvTitle.setTextColor(
                    if (isCurrent) android.graphics.Color.parseColor("#FF4CAF50")
                    else if (isFavHere) android.graphics.Color.parseColor("#FFFFD700")
                    else android.graphics.Color.WHITE
                )
                val posterUrl = ch.poster
                if (!posterUrl.isNullOrBlank()) {
                    com.bumptech.glide.Glide.with(ctx)
                        .load(posterUrl)
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .error(android.R.drawable.ic_menu_gallery)
                        .centerCrop()
                        .into(imgView)
                } else {
                    imgView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
                return cell
            }
        }
        gridView.adapter = gridAdapter

        // v13 : clics via onItemClickListener (D-pad + touch compatible)
        gridView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, pos, _ ->
            val ch = filteredChannels[pos]
            android.util.Log.d("LiveHubFolderDialog",
                "Click poster: ${ch.title} (${ch.id}) — currentMini=${MPC.currentChannelId}")
            // 2026-06-22 (user "la page doit se fermer que au 2e clic sur
            //   le même épisode, là ça part en plein écran") :
            //   1er clic = mini player, page reste ouverte.
            //   2e clic sur le MÊME item = plein écran, page se ferme.
            // 2026-06-23 (user "le menu synopsis est censé apparaître devant,
            //   là je suis obligé de fermer la page pour aller voir le menu") :
            //   pour les Replay (= série/film qui ouvre une fiche détail), on
            //   dismiss le dialog AVANT onChannelSelected pour que la fiche
            //   s'affiche au premier plan, pas derrière le dialog folder.
            if (ch.id == MPC.currentChannelId) {
                // 2e clic sur le même film = plein écran → ferme tout
                dismissAllDialogs()
            }
            onChannelSelected(ch)
            gridAdapter.notifyDataSetChanged()
        }
        // Long-press pour favoris replay
        gridView.onItemLongClickListener = android.widget.AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            val ch = filteredChannels[pos]
            // 2026-06-27 (user "dans Musique j'ai voulu mettre un favori, ça marche
            //   pas") : l'appui long ne gérait que les ids replay. Les chaînes
            //   Musique/WorldWide/FAST sont des ids `livehub::fast::` → on les
            //   accepte aussi, et on PERSISTE leur URL (id = hash non
            //   reconstructible) pour qu'elles restent jouables depuis les Cœurs.
            val isReplay = ch.id.startsWith("livehub::replay::")
            // 2026-07-05 (user "90 Is Good ne se met pas en favori") :
            //   DYNAMIQUE = tout livehub:: avec segments imbriqués
            //   (livehub::fast::*, livehub::dric4rtv::*, livehub::otf::*,
            //   livehub::adar::*, livehub::francetv::*, etc.). Ces chaînes
            //   ne sont PAS dans la liste HubChannel → IptvFavoritesStore
            //   ne peut pas les retrouver. On les route vers
            //   ReplayFavoritesStore (= "Favoris Replay" sur le home).
            //   CURÉ = livehub::<clé_simple> sans :: imbriqué (TF1, MTV…)
            //   → dans la liste HubChannel → IptvFavoritesStore OK.
            val isDynamic = !isReplay && ch.id.startsWith("livehub::") &&
                !ch.id.startsWith("livehub::folder::") &&
                ch.id.removePrefix("livehub::").contains("::")
            val isCurated = ch.id.startsWith("livehub::") &&
                !isReplay && !isDynamic && !ch.id.startsWith("livehub::folder::")
            if (isReplay || isDynamic) {
                val store = com.streamflixreborn.streamflix.utils.ReplayFavoritesStore
                val nowFav = store.toggle(
                    id = ch.id, title = ch.title,
                    poster = ch.poster, banner = ch.banner,
                    isMovie = ch.isMovie || isDynamic,
                )
                if (isDynamic && nowFav) LiveTvHubProvider.persistFastFavorite(ch.id)
                // 2026-07-05 : invalider le cache mémoire du home pour que le
                //   favori apparaisse dans "Favoris Replay" au retour.
                LiveTvHubProvider.clearHomeCache()
                val msg = if (nowFav) "★ ${ch.title} ajouté aux favoris"
                          else "☆ ${ch.title} retiré des favoris"
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                gridAdapter.notifyDataSetChanged()
                true
            } else if (isCurated) {
                // 2026-07-05 (user "dans le dossier musique, appui long ne met pas
                //   en favori") : les chaînes curées (MTV, MCM, M6 Music…) utilisent
                //   IptvFavoritesStore (= ★ channel-fav), pas ReplayFavoritesStore.
                val provName = ch.providerName ?: "TV Hub"
                val wasFav = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                    .isFavorite(provName, ch.id)
                com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                    .toggle(provName, ch.id)
                // 2026-07-05 : invalider AUSSI le cache mémoire du provider pour
                //   que le favori apparaisse dans "Favoris" (cœur) au retour.
                LiveTvHubProvider.clearHomeCache()
                try {
                    val current = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
                    if (current != null) {
                        com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(ctx, current)
                    }
                } catch (_: Throwable) {}
                val msg = if (!wasFav) "★ ${ch.title} ajouté aux favoris"
                          else "☆ ${ch.title} retiré des favoris"
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                gridAdapter.notifyDataSetChanged()
                true
            } else false
        }

        // ── 2026-06-22 v3 (user "pas de barre noire en haut/en bas, juste
        //   un petit badge titre + petit bouton RETOUR flottants, et on voit
        //   les jaquettes suivantes en bas") : FrameLayout overlay. ──

        val baseTitle = "${category.name} (${channels.size})"

        // Titre = petit badge flottant en haut à gauche (pas une barre pleine largeur)
        val titleTv = android.widget.TextView(ctx).apply {
            text = baseTitle
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (isTV) 13f else 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((10 * dp).toInt(), (3 * dp).toInt(), (10 * dp).toInt(), (3 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18))
                cornerRadius = 6 * dp
            }
        }
        val (titleBarPoster, connectDragPoster) = wrapWithDragHandle(ctx, titleTv)

        // RETOUR = petit bouton flottant en bas à droite (pas une barre pleine largeur)
        val retourBtn = android.widget.TextView(ctx).apply {
            text = "RETOUR"
            setTextColor(android.graphics.Color.parseColor("#BB4FC3F7"))
            textSize = if (isTV) 12f else 10f
            setPadding((12 * dp).toInt(), (5 * dp).toInt(), (12 * dp).toInt(), (5 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18))
                cornerRadius = 6 * dp
            }
            isFocusable = true
            isFocusableInTouchMode = false
        }

        // Recherche compacte (padding réduit)
        searchInput.setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        searchInput.textSize = if (isTV) 14f else 12f

        // Grid prend tout l'espace — padding bas pour ne pas cacher le dernier rang derrière RETOUR
        gridView.setPadding(
            (4 * dp).toInt(), (4 * dp).toInt(),
            (4 * dp).toInt(), (32 * dp).toInt()
        )
        gridView.clipToPadding = false

        // Contenu principal = titre(WRAP) + recherche + grille (vertical)
        // Le titre est DANS le flux (pas en overlay) pour ne pas empiéter sur la recherche
        val contentCol = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            // Titre avec handle drag ≡ (mobile only)
            addView(titleBarPoster, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            })
            addView(searchInput, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(gridView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            ))
        }

        // Root = FrameLayout : contenu remplit tout, RETOUR flotte en bas à droite
        val container = android.widget.FrameLayout(ctx).apply {
            addView(contentCol, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(retourBtn, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.END
            ).apply {
                setMargins(0, 0, (10 * dp).toInt(), (6 * dp).toInt())
            })
        }
        // ── TextWatcher : filtre en temps réel ──
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: ""
                filteredChannels.clear()
                if (query.isEmpty()) {
                    filteredChannels.addAll(channels)
                    titleTv.text = baseTitle
                } else {
                    val matches = channels.filter {
                        it.title.contains(query, ignoreCase = true)
                    }
                    filteredChannels.addAll(matches)
                    titleTv.text = "${category.name} (${matches.size}/${channels.size})"
                }
                gridAdapter.notifyDataSetChanged()
            }
        })

        // Dialog sans titre ni bouton → pas de barres noires
        val dlg = android.app.AlertDialog.Builder(ctx)
            .setView(container)
            .create()
        dlgRef = dlg
        retourBtn.setOnClickListener { dlg.dismiss() }

        try { dlg.show() } catch (_: android.view.WindowManager.BadTokenException) { return }
        pushDialog(dlg)
        connectDragPoster(dlg)
        applyThemeBackground(dlg)
        // 2026-06-22 : ajuste la fenêtre du dialog pour ne pas couvrir le mini player
        val cleanupMiniPlayer = adjustDialogForMiniPlayer(ctx, dlg)
        dlg.setOnDismissListener { onDialogDismissed(dlg, cleanupMiniPlayer) }
        // Focus la grille au démarrage (pas le clavier)
        gridView.post { gridView.requestFocus() }
    }

    /** Affiche une liste texte classique (chaînes IPTV sans posters). */
    private fun showTextList(
        ctx: Context,
        category: Category,
        channels: List<TvShow>,
        onChannelSelected: (TvShow) -> Unit,
    ) {
        val MPC = com.streamflixreborn.streamflix.utils.MiniPlayerController
        val dp = ctx.resources.displayMetrics.density
        val isTV = ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val filteredChannels = channels.toMutableList()

        // ── Barre de recherche (si beaucoup d'items) ──
        val showSearch = channels.size > 15
        val searchInput = if (showSearch) android.widget.EditText(ctx).apply {
            hint = "🔍 Rechercher…"
            setHintTextColor(android.graphics.Color.GRAY)
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (isTV) 16f else 14f
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
        } else null

        val itemsAdapter = object : android.widget.ArrayAdapter<TvShow>(
            ctx, android.R.layout.simple_list_item_1, filteredChannels
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(position, convertView, parent)
                val tv = v.findViewById<android.widget.TextView>(android.R.id.text1)
                val ch = filteredChannels[position]
                val isCurrent = ch.id == MPC.currentChannelId
                tv.text = if (isCurrent) "▶ ${ch.title}  ⤴ Plein écran" else ch.title
                return v
            }
        }
        val listView = android.widget.ListView(ctx).apply {
            adapter = itemsAdapter
        }

        // ── 2026-06-22 v3 : FrameLayout overlay (titre badge + RETOUR flottant, pas de barres noires) ──
        val baseTitle = if (showSearch) "${category.name} (${channels.size})" else category.name
        val titleTv = android.widget.TextView(ctx).apply {
            text = baseTitle
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (isTV) 13f else 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((10 * dp).toInt(), (3 * dp).toInt(), (10 * dp).toInt(), (3 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18))
                cornerRadius = 6 * dp
            }
        }
        val (titleBarText, connectDragText) = wrapWithDragHandle(ctx, titleTv)
        val retourBtn = android.widget.TextView(ctx).apply {
            text = "RETOUR"
            setTextColor(android.graphics.Color.parseColor("#BB4FC3F7"))
            textSize = if (isTV) 12f else 10f
            setPadding((12 * dp).toInt(), (5 * dp).toInt(), (12 * dp).toInt(), (5 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(0xCC, 0x18, 0x18, 0x18))
                cornerRadius = 6 * dp
            }
            isFocusable = true
            isFocusableInTouchMode = false
        }

        // Recherche compacte
        searchInput?.apply {
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            textSize = if (isTV) 14f else 12f
        }

        // Liste prend tout l'espace — padding bas pour voir les éléments derrière RETOUR
        listView.setPadding(0, 0, 0, (32 * dp).toInt())
        listView.clipToPadding = false

        // Contenu principal = titre(WRAP) + recherche (optionnel) + liste (vertical)
        // Le titre est DANS le flux pour ne pas empiéter sur la recherche
        val contentCol = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(titleBarText, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            })
            if (searchInput != null) {
                addView(searchInput, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            addView(listView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            ))
        }

        // Root = FrameLayout : contenu remplit tout, RETOUR flotte en bas à droite
        val container = android.widget.FrameLayout(ctx).apply {
            addView(contentCol, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(retourBtn, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.END
            ).apply {
                setMargins(0, 0, (10 * dp).toInt(), (6 * dp).toInt())
            })
        }
        val dlg = android.app.AlertDialog.Builder(ctx)
            .setView(container)
            .create()

        // ── TextWatcher : filtre en temps réel ──
        searchInput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: ""
                filteredChannels.clear()
                if (query.isEmpty()) {
                    filteredChannels.addAll(channels)
                    titleTv.text = baseTitle
                } else {
                    val matches = channels.filter {
                        it.title.contains(query, ignoreCase = true)
                    }
                    filteredChannels.addAll(matches)
                    titleTv.text = "${category.name} (${matches.size}/${channels.size})"
                }
                itemsAdapter.notifyDataSetChanged()
            }
        })
        retourBtn.setOnClickListener { dlg.dismiss() }

        listView.setOnItemClickListener { _, _, idx, _ ->
            val sel = filteredChannels[idx]
            android.util.Log.d("LiveHubFolderDialog", "Click chaîne: ${sel.title} (${sel.id}) — currentMini=${MPC.currentChannelId}")
            // 2026-06-22 (user "la page doit se fermer que au 2e clic sur
            //   le même épisode, là ça part en plein écran") :
            //   1er clic = mini player, page reste ouverte.
            //   2e clic sur le MÊME item = plein écran, page se ferme.
            if (sel.id == MPC.currentChannelId) {
                // 2e clic sur le même item = plein écran → ferme tout
                dismissAllDialogs()
            }
            onChannelSelected(sel)
            itemsAdapter.notifyDataSetChanged()
        }
        listView.setOnItemLongClickListener { _, _, idx, _ ->
            val sel = filteredChannels[idx]
            // 2026-06-27 (user "favoris par appui long sur film/série/chaîne dans
            //   tous les replays + visible dans les cœurs") : étendu aux items FAST
            //   (films VOD + chaînes live Plex/Pluto), pas seulement aux replay.
            val isReplay = sel.id.startsWith("livehub::replay::")
            val isFast = sel.id.startsWith("livehub::fast::")
            if (isReplay || isFast) {
                val store = com.streamflixreborn.streamflix.utils.ReplayFavoritesStore
                // FAST (film VOD ou chaîne live) → lecture directe = isMovie true.
                // Replay série → synopsis = isMovie false (film replay reste movie).
                val asMovie = isFast || sel.isMovie
                val nowFav = store.toggle(
                    id = sel.id,
                    title = sel.title,
                    poster = sel.poster,
                    banner = sel.banner,
                    isMovie = asMovie,
                )
                val msg = if (nowFav) "★ ${sel.title} ajouté aux favoris"
                          else "☆ ${sel.title} retiré des favoris"
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                itemsAdapter.notifyDataSetChanged()
                true
            } else false
        }
        try { dlg.show() } catch (_: android.view.WindowManager.BadTokenException) { return }
        pushDialog(dlg)
        connectDragText(dlg)
        applyThemeBackground(dlg)
        // 2026-06-22 : ajuste la fenêtre du dialog pour ne pas couvrir le mini player
        val cleanupMiniPlayer = adjustDialogForMiniPlayer(ctx, dlg)
        dlg.setOnDismissListener { onDialogDismissed(dlg, cleanupMiniPlayer) }
        // Focus la liste au démarrage (pas le clavier)
        if (searchInput != null) listView.post { listView.requestFocus() }
    }
}
