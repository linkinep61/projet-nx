package com.streamflixreborn.streamflix.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 2026-09-26 — NOUVELLE RECHERCHE (user : « un nouvel onglet de recherche, joli comme la radio :
 *   on choisit Film, Série ou Live, puis on tape ; les résultats sont rangés par provider »).
 *
 * Écran à part, indépendant de l'ancienne recherche globale (SearchViewModel n'est pas touché).
 * Ouvert par l'icône loupe en haut à droite de l'écran d'accueil (à la place de Téléchargements),
 * sur téléphone comme sur TV.
 *
 *  - Films  : providers Films/Séries + Animés qui font des films, résultats de type film ;
 *             + « Ciné Films » (catalogues VOD Vegeta et OLA du TV Hub).
 *  - Séries : providers qui font des séries, résultats de type série ; + séries de Ciné Films.
 *  - Live   : providers TV / IPTV (Vavoo, OLA TV, Vegeta TV, TV Hub, World Live, Mon IPTV).
 *
 * Un clic bascule sur le provider du résultat puis ouvre sa fiche par les actions globales
 * de navigation (comme le cœur des favoris). La dernière recherche est gardée en mémoire :
 * au retour d'une fiche, on retrouve ses résultats sans relancer.
 */
object RechercheUnifiee {
    private const val TAG = "RechercheUnifiee"

    private const val FOND = "#101216"
    private const val CARD = "#20242C"
    private const val BORDER = "#2F343E"
    private const val ACCENT = "#E23B3B"
    private const val TXT = "#F5F6F8"
    private const val TXT2 = "#9AA0AA"
    private const val PAR_SOURCE = 30
    // 40 s : une box TV est plus lente qu'un téléphone ; les résultats s'affichent au fil de l'eau.
    private const val DELAI_MS = 40_000L
    private const val SEAU_HISTORIQUE = "recherche_unifiee"

    enum class Mode(val libelle: String) { FILMS("🎬  Films"), SERIES("📺  Séries"), LIVE("📡  Live") }

    /** [groupes] : pour le TV Hub en Live, une rangée par dossier d'origine (« TV Hub · OTF TV »…). */
    private class Section(val titre: String, val provider: Provider?, var items: List<AppAdapter.Item>? = null,
                          val dossier: String? = null, var groupes: List<Pair<String, List<AppAdapter.Item>>>? = null)

    private var mode = Mode.FILMS
    private var requete = ""
    private var memo: List<Section> = emptyList()
    private var job: Job? = null

    /**
     * 2026-09-26 (user : « pourquoi moins de résultats sur la TV que sur le téléphone ») —
     *   mesuré sur la box : Vavoo 22 s, OLA 33 s, Vegeta 98 s alors que le délai était de 25 s.
     *   Pire : la recherche de certains providers ne rend jamais la main (lecture réseau
     *   bloquante), donc `withTimeoutOrNull` autour d'elle n'arrêtait RIEN. On lance donc la
     *   recherche à part et on n'attend que son RÉSULTAT, avec le délai : passé ce délai, on
     *   continue sans elle (elle finit en fond, sans bloquer l'écran).
     */
    private val horsEcran = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private suspend fun <T> auPlus(ms: Long, bloc: suspend () -> T): T? {
        val d = horsEcran.async { runCatching { bloc() }.getOrNull() }
        return withTimeoutOrNull(ms) { d.await() }
    }

    // 2026-09-26 (user : « reprises de lecture + appui long pour mettre en favori… il devrait
    //   apparaître dans le cœur du menu Home, suivant le provider ») : état perso, relu à chaque
    //   recherche dans les bases de TOUS les providers (mêmes sources que le cœur).
    /** "provider|id" → (pourcentage vu, film enregistré) pour les films en cours. */
    private var reprisesFilms: Map<String, Pair<Int, Movie>> = emptyMap()
    /** "provider|idSérie" → (pourcentage vu, « ▶ S1E3 »). */
    private var reprisesSeries: Map<String, Pair<Int, String>> = emptyMap()
    /** "provider|id" des favoris. */
    private var favoris: Set<String> = emptySet()

    private suspend fun chargerPerso(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val (fm, ft) = GlobalFavorites.load(ctx)
            favoris = (fm.map { it.id } + ft.map { it.id })
                .mapNotNull { id -> GlobalFavorites.originByItemId[id]?.let { "$it|$id" } }.toSet()
        }.onFailure { Log.w(TAG, "favoris : ${it.message}") }
        runCatching {
            reprisesFilms = GlobalFavorites.loadContinueWatchingMovies(ctx, 500).mapNotNull { m ->
                val o = GlobalFavorites.originByItemId["resume_movie_${m.id}"] ?: return@mapNotNull null
                val wh = m.watchHistory ?: return@mapNotNull null
                val duree = wh.durationMillis ?: 0L
                val pos = wh.lastPlaybackPositionMillis ?: 0L
                "$o|${m.id}" to ((if (duree > 0) (pos * 100 / duree).toInt() else 5) to m)
            }.toMap()
        }.onFailure { Log.w(TAG, "reprises films : ${it.message}") }
        runCatching {
            reprisesSeries = GlobalFavorites.loadContinueWatchingSeries(ctx, 500).associate { r ->
                val wh = r.lastEpisode.watchHistory
                val duree = wh?.durationMillis ?: 0L
                val pos = wh?.lastPlaybackPositionMillis ?: 0L
                "${r.providerName}|${r.tvShow.id}" to
                    ((if (duree > 0) (pos * 100 / duree).toInt() else 5) to "▶ S${r.seasonNumber}E${r.episodeNumber}")
            }
        }.onFailure { Log.w(TAG, "reprises séries : ${it.message}") }
    }

    /** Providers interrogés pour un mode, dans l'ordre d'affichage de l'accueil. */
    private fun sources(m: Mode): List<Provider> {
        val langue = UserPreferences.currentLanguage
        return Provider.providers.entries
            .filter { (p, _) -> langue == null || p.language == langue }
            .filter { (_, s) ->
                val iptv = s.group == Provider.Companion.ProviderGroup.IPTV
                when (m) {
                    Mode.FILMS -> !iptv && s.movies
                    Mode.SERIES -> !iptv && s.tvShows
                    Mode.LIVE -> iptv
                }
            }
            .map { it.key }
    }

    private fun titre(i: AppAdapter.Item): String = when (i) {
        is Movie -> i.title
        is TvShow -> i.title
        else -> ""
    }

    /** Garde ce qui contient la requête (accents dépliés), sinon les correspondances approchées. */
    private fun filtrer(items: List<AppAdapter.Item>, q: String): List<AppAdapter.Item> {
        val nq = RechercheFloue.normaliser(q)
        if (nq.length < 2) return items
        val contient = items.filter { RechercheFloue.normaliser(titre(it)).contains(nq) }
        if (contient.isNotEmpty()) return contient
        return items.filter { RechercheFloue.correspond(titre(it), q) }
    }

    /** Résultats d'un provider pour le mode choisi. */
    private suspend fun chercherDans(p: Provider, m: Mode, q: String): List<AppAdapter.Item> {
        val t0 = System.currentTimeMillis()
        val brutsOuNull = auPlus(DELAI_MS) { p.search(q) }
        val bruts = brutsOuNull.orEmpty()
        Log.i(TAG, "${p.name} « $q » : ${bruts.size} bruts en ${System.currentTimeMillis() - t0} ms" +
            if (brutsOuNull == null) " (DÉLAI DÉPASSÉ)" else "")
        val types = bruts.filter {
            when (m) {
                Mode.FILMS -> it is Movie && !it.isSeries
                Mode.SERIES -> it is TvShow || (it is Movie && it.isSeries)
                // Pas les tuiles « dossier » du TV Hub : elles n'ouvrent rien hors de leur écran.
                Mode.LIVE -> (it is Movie || it is TvShow) && !(it is TvShow && it.id.startsWith("livehub::folder::"))
            }
        }.onEach {
            when (it) {
                is Movie -> it.providerName = p.name
                is TvShow -> it.providerName = p.name
            }
        }
        val filtres = if (m == Mode.LIVE) types else filtrer(types, q)
        return ParentalControlUtils.filterItems(filtres).take(PAR_SOURCE)
    }

    /** « Ciné Films » du TV Hub : catalogues VOD Vegeta + OLA, cherchés dans l'index local. */
    private suspend fun chercherCineFilms(m: Mode, q: String): List<AppAdapter.Item> {
        val nq = RechercheFloue.normaliser(q)
        if (nq.length < 2) return emptyList()
        fun ok(t: String) = RechercheFloue.normaliser(t).contains(nq)
        val vv = auPlus(DELAI_MS) { VegetaVod.index() }
        val ov = auPlus(DELAI_MS) { OlaVod.index() }
        val out = ArrayList<TvShow>()
        if (m == Mode.FILMS) {
            vv?.films?.filter { ok(it.titre) }?.forEach { out += VegetaVod.tuileFilm(it) }
            ov?.films?.filter { ok(it.titre) }?.forEach { out += OlaVod.tuileFilm(it) }
        } else {
            vv?.series?.filter { ok(it.titre) }?.forEach { out += VegetaVod.tuileSerie(it) }
            ov?.series?.filter { ok(it.titre) }?.forEach { out += OlaVod.tuileSerie(it) }
        }
        // Un même titre (même année) présent dans les deux catalogues n'apparaît qu'une fois.
        return out.onEach { it.providerName = "TV Hub" }
            .distinctBy { RechercheFloue.normaliser(it.title) }
            .sortedBy { if (RechercheFloue.normaliser(it.title).startsWith(nq)) 0 else 1 }
            .take(PAR_SOURCE * 2)
    }

    private fun estTv(ctx: Context): Boolean = runCatching {
        val ui = (ctx.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val pm = ctx.packageManager
        ui || pm.hasSystemFeature("android.software.leanback") ||
            !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }.getOrDefault(false)

    fun show(fragment: Fragment) {
        val ctx = fragment.requireContext()
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun arrondi(fond: String, r: Int, bord: String? = null) = GradientDrawable().apply {
            cornerRadius = dp(r).toFloat(); setColor(Color.parseColor(fond))
            if (bord != null) setStroke(dp(1), Color.parseColor(bord))
        }
        val tv = estTv(ctx)
        fun focusTv(v: View) {
            v.isFocusable = true; v.isClickable = true
            if (tv) v.foreground = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_focus_white_border)
        }

        val dialog = Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val racine = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(FOND))
            setPadding(dp(16), dp(34), dp(16), dp(8))   // 34 dp en haut : sous l'encoche / la caméra
        }

        // ── En-tête ────────────────────────────────────────────────────────────
        val entete = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        entete.addView(TextView(ctx).apply {
            text = "Rechercher"; textSize = 22f; setTextColor(Color.parseColor(TXT))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val fermer = TextView(ctx).apply {
            text = "✕"; textSize = 20f; setTextColor(Color.parseColor(TXT2)); gravity = Gravity.CENTER
            background = arrondi(CARD, 20); focusTv(this)
            setOnClickListener { dialog.dismiss() }
        }
        entete.addView(fermer, LinearLayout.LayoutParams(dp(40), dp(40)))
        racine.addView(entete, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // ── Bascule Films / Séries / Live ───────────────────────────────────────
        val bascule = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; background = arrondi(CARD, 12, BORDER)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val boutonsMode = Mode.values().map { m ->
            TextView(ctx).apply {
                text = m.libelle; textSize = 14f; gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10)); focusTv(this)
            }.also { bascule.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        }
        fun peindreModes() = boutonsMode.forEachIndexed { i, b ->
            val actif = Mode.values()[i] == mode
            b.background = if (actif) arrondi(ACCENT, 9) else null
            b.setTextColor(Color.parseColor(if (actif) "#FFFFFF" else TXT2))
        }
        racine.addView(bascule, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // ── Barre de recherche ──────────────────────────────────────────────────
        val ligne = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val saisie = EditText(ctx).apply {
            hint = "Titre d'un film, d'une série, d'une chaîne…"; setText(requete); textSize = 15f
            setTextColor(Color.parseColor(TXT)); setHintTextColor(Color.parseColor(TXT2))
            background = arrondi(CARD, 12, BORDER); setPadding(dp(14), dp(11), dp(14), dp(11))
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            if (tv) foreground = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_focus_white_border)
        }
        val go = TextView(ctx).apply {
            text = "🔍"; textSize = 18f; gravity = Gravity.CENTER; background = arrondi(ACCENT, 12)
            setTextColor(Color.WHITE); focusTv(this)
        }
        // 2026-09-26 (user : « il nous manque l'historique ») : 🕘 = recherches récentes,
        //   comme dans la radio et les dossiers du TV Hub. Mémorisées à chaque recherche lancée.
        val histo = TextView(ctx).apply {
            text = "🕘"; textSize = 18f; gravity = Gravity.CENTER; background = arrondi(CARD, 12, BORDER)
            focusTv(this)
        }
        ligne.addView(saisie, LinearLayout.LayoutParams(0, -2, 1f))
        ligne.addView(histo, LinearLayout.LayoutParams(dp(48), dp(46)).apply { leftMargin = dp(8) })
        ligne.addView(go, LinearLayout.LayoutParams(dp(48), dp(46)).apply { leftMargin = dp(8) })
        racine.addView(ligne, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // ── Mini-lecteur (mode Live) ─────────────────────────────────────────────
        // 2026-09-26 (user : « quand on est sur Live, j'aimerais que le mini-lecteur apparaisse…
        //   un paquet de chaînes ne marche pas, ça aide à choisir la chaîne ») : 1er appui sur
        //   une chaîne = aperçu ici, sans quitter la recherche ; 2e appui sur la MÊME chaîne
        //   (ou ⛶) = plein écran. Même lecteur partagé que les autres mini-lecteurs de l'app.
        val MPC = MiniPlayerController
        val panneau = android.widget.FrameLayout(ctx).apply {
            background = arrondi("#000000", 12); clipToOutline = true; visibility = View.GONE
        }
        val vue = androidx.media3.ui.PlayerView(ctx).apply {
            useController = false
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        panneau.addView(vue, android.widget.FrameLayout.LayoutParams(-1, -1))
        val attente = ProgressBar(ctx)
        panneau.addView(attente, android.widget.FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        val barreMini = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#99000000")); setPadding(dp(10), dp(4), dp(4), dp(4))
        }
        val nomChaine = TextView(ctx).apply {
            textSize = 13f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        barreMini.addView(nomChaine, LinearLayout.LayoutParams(0, -2, 1f))
        fun boutonMini(t: String) = TextView(ctx).apply {
            text = t; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; focusTv(this)
        }.also { barreMini.addView(it, LinearLayout.LayoutParams(dp(40), dp(36))) }
        val bPause = boutonMini("⏯")
        val bPlein = boutonMini("⛶")
        val bStop = boutonMini("✕")
        panneau.addView(barreMini, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        racine.addView(panneau, LinearLayout.LayoutParams(-1, dp(if (tv) 230 else 200)).apply { bottomMargin = dp(8) })
        var versPleinEcran = false
        fun pleinEcran() {
            if (MPC.currentChannelId == null) return
            versPleinEcran = true
            MPC.transitioningToFullscreen = true
            dialog.dismiss()
            MPC.navigateToFullscreenForCurrent()
        }
        bPause.setOnClickListener { MPC.togglePause() }
        bPlein.setOnClickListener { pleinEcran() }
        bStop.setOnClickListener { MPC.stop(); panneau.visibility = View.GONE }

        val statut = TextView(ctx).apply { textSize = 12f; setTextColor(Color.parseColor(TXT2)) }
        racine.addView(statut, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        val defil = ScrollView(ctx).apply { isFillViewport = true }
        val liste = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        defil.addView(liste)
        racine.addView(defil, LinearLayout.LayoutParams(-1, 0, 1f))

        // ── Ouverture d'un résultat ─────────────────────────────────────────────
        fun ouvrir(item: AppAdapter.Item) {
            val nom = when (item) { is Movie -> item.providerName; is TvShow -> item.providerName; else -> null }
            val idItem = when (item) { is Movie -> item.id; is TvShow -> item.id; else -> "" }
            // Chaîne en direct : aperçu dans le mini-lecteur (les replays, clips, fichiers et
            //   films/séries du TV Hub ouvrent toujours leur fiche).
            val estChaine = mode == Mode.LIVE && item is TvShow &&
                !Regex("^livehub::(replay|rutube|ytclip|voe|voecom|vidara|vidaracom|vegetavod|olavod|folder)::").containsMatchIn(idItem) &&
                !idItem.startsWith("myiptv-movie::") && !idItem.startsWith("myiptv-ep")
            if (estChaine && UserPreferences.miniPlayerEnabled) {
                Provider.findByName(nom ?: "")?.let {
                    if (UserPreferences.currentProvider?.name != it.name) UserPreferences.currentProvider = it
                }
                if (idItem == MPC.currentChannelId) { pleinEcran(); return }
                MPC.initPlayer(ctx)
                vue.player = null; vue.player = MPC.getPlayer()
                panneau.visibility = View.VISIBLE; attente.visibility = View.VISIBLE
                nomChaine.text = titre(item)
                MPC.playChannel(idItem, titre(item), (item as TvShow).poster)
                return
            }
            Provider.findByName(nom ?: "")?.let {
                if (UserPreferences.currentProvider?.name != it.name) UserPreferences.currentProvider = it
            }
            dialog.dismiss()
            val nav = runCatching { fragment.findNavController() }.getOrNull() ?: return
            runCatching {
                when (item) {
                    is Movie -> {
                        // Film déjà commencé : droit au lecteur, qui reprend à la bonne position
                        //   (comme depuis le cœur).
                        val rep = reprisesFilms["$nom|${item.id}"]?.second
                        if (rep != null) {
                            nav.navigate(R.id.action_global_player, Bundle().apply {
                                putString("id", item.id); putString("title", item.title); putString("subtitle", "")
                                putSerializable("videoType", com.streamflixreborn.streamflix.models.Video.Type.Movie(
                                    id = item.id, title = item.title, releaseDate = "",
                                    poster = item.poster ?: rep.poster ?: "", imdbId = rep.imdbId ?: item.imdbId,
                                ))
                            })
                            return@runCatching
                        }
                        // Mêmes règles que MovieViewHolder : ces providers rangent leurs « films »
                        //   comme des séries (fiche à saisons).
                        val commeSerie = nom in setOf("VoirDrama", "VoirAnime") ||
                            (nom in setOf("FrenchAnime", "AnimeSama", "FrenchManga") && item.isSeries)
                        if (commeSerie) nav.navigate(R.id.action_global_tv_show, Bundle().apply {
                            putString("id", item.id); putString("poster", item.poster); putString("banner", item.banner)   // « banner » exigé par le graphe TV
                        }) else nav.navigate(R.id.action_global_movie, Bundle().apply { putString("id", item.id) })
                    }
                    is TvShow -> nav.navigate(R.id.action_global_tv_show, Bundle().apply {
                        putString("id", item.id); putString("poster", item.poster); putString("banner", item.banner)   // « banner » exigé par le graphe TV
                    })
                    else -> {}
                }
            }.onFailure { Log.e(TAG, "ouverture impossible : ${it.message}") }
        }

        fun cle(item: AppAdapter.Item): String {
            val nom = when (item) { is Movie -> item.providerName; is TvShow -> item.providerName; else -> null }
            val id = when (item) { is Movie -> item.id; is TvShow -> item.id; else -> "" }
            return "$nom|$id"
        }

        /** Appui long : ajoute / retire le résultat des favoris de SON provider. Le cœur de
         *  l'accueil rassemble les favoris de tous les providers → il y apparaît aussitôt. */
        fun basculerFavori(item: AppAdapter.Item, coeur: View) {
            val nom = when (item) { is Movie -> item.providerName; is TvShow -> item.providerName; else -> null } ?: return
            val p = Provider.findByName(nom)
            if (p == null || Provider.getGroup(p) == Provider.Companion.ProviderGroup.IPTV) {
                android.widget.Toast.makeText(ctx, "Les chaînes se mettent en favori depuis le lecteur", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val k = cle(item)
            val ajout = k !in favoris
            runCatching { coeur.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val db = com.streamflixreborn.streamflix.database.AppDatabase.getInstanceForProvider(nom, ctx)
                        try {
                            when (item) {
                                is Movie -> db.movieDao().upsertFavorite(item, ajout)
                                is TvShow -> db.tvShowDao().upsertFavorite(item, ajout)
                                else -> {}
                            }
                        } finally { runCatching { db.close() } }
                    }.onFailure { Log.w(TAG, "favori $k : ${it.message}") }.isSuccess
                }
                if (!ok) {
                    android.widget.Toast.makeText(ctx, "Favori impossible", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                favoris = if (ajout) favoris + k else favoris - k
                coeur.visibility = if (ajout) View.VISIBLE else View.GONE
                android.widget.Toast.makeText(ctx,
                    if (ajout) "♥ Ajouté aux favoris ($nom)" else "Retiré des favoris ($nom)",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        fun carte(item: AppAdapter.Item, live: Boolean): View {
            val w = if (live) dp(120) else dp(108)
            val h = if (live) dp(80) else dp(160)
            val k = cle(item)
            val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(3), dp(3), dp(3), dp(3)); focusTv(this) }
            val cadre = android.widget.FrameLayout(ctx)
            val img = ImageView(ctx).apply {
                scaleType = if (live) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                background = arrondi(CARD, 10); clipToOutline = true
                if (live) setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            val url = when (item) { is Movie -> item.poster; is TvShow -> item.poster; else -> null }
            if (!url.isNullOrBlank()) runCatching { Glide.with(img).load(url).into(img) }
            cadre.addView(img, android.widget.FrameLayout.LayoutParams(w, h))
            // Cœur = déjà en favori (appui long pour basculer).
            val coeur = TextView(ctx).apply {
                text = "♥"; textSize = 13f; setTextColor(Color.parseColor(ACCENT)); gravity = Gravity.CENTER
                background = arrondi("#CC101216", 12)
                visibility = if (k in favoris) View.VISIBLE else View.GONE
            }
            cadre.addView(coeur, android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(5), dp(5), 0) })
            // Reprise de lecture : barre de progression en bas de l'affiche.
            val pct = when (item) {
                is Movie -> reprisesFilms[k]?.first
                is TvShow -> reprisesSeries[k]?.first
                else -> null
            }
            if (pct != null) {
                val fond = View(ctx).apply { setBackgroundColor(Color.parseColor("#99000000")) }
                cadre.addView(fond, android.widget.FrameLayout.LayoutParams(w, dp(4), Gravity.BOTTOM))
                val barre = View(ctx).apply { setBackgroundColor(Color.parseColor(ACCENT)) }
                cadre.addView(barre, android.widget.FrameLayout.LayoutParams((w * pct.coerceIn(3, 100)) / 100, dp(4), Gravity.BOTTOM or Gravity.START))
            }
            c.addView(cadre, LinearLayout.LayoutParams(w, h))
            c.addView(TextView(ctx).apply {
                text = titre(item); textSize = 12f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.parseColor(TXT)); setPadding(dp(2), dp(4), dp(2), 0)
            }, LinearLayout.LayoutParams(w, -2))
            val repriseTxt = when (item) {
                is Movie -> if (reprisesFilms.containsKey(k)) "▶ Reprendre" else null
                is TvShow -> reprisesSeries[k]?.second
                else -> null
            }
            if (repriseTxt != null) c.addView(TextView(ctx).apply {
                text = repriseTxt; textSize = 11f; setTextColor(Color.parseColor(ACCENT)); setPadding(dp(2), dp(1), dp(2), 0)
            }, LinearLayout.LayoutParams(w, -2))
            c.setOnClickListener { ouvrir(item) }
            c.setOnLongClickListener { basculerFavori(item, coeur); true }
            return c
        }

        // ── Sections : un bloc par source, dans l'ordre fixe des providers. Chaque bloc se
        //   remplit à l'arrivée de SES résultats, sans redessiner les autres (le focus
        //   télécommande et le défilement horizontal ne sautent pas pendant la recherche).
        val blocs = ArrayList<LinearLayout>()
        val chargeur = ProgressBar(ctx)

        fun majStatut() {
            val total = memo.sumOf { it.items?.size ?: 0 }
            val enCours = memo.count { it.items == null }
            statut.text = when {
                requete.isBlank() -> "Choisis Films, Séries ou Live, puis tape un titre."
                enCours > 0 -> "Recherche de « $requete »… $total résultat(s), encore $enCours source(s)"
                total == 0 -> "Aucun résultat pour « $requete »."
                else -> "$total résultat(s) pour « $requete »"
            }
            chargeur.visibility = if (enCours > 0) View.VISIBLE else View.GONE
        }

        fun remplir(i: Int) {
            val s = memo.getOrNull(i) ?: return
            val bloc = blocs.getOrNull(i) ?: return
            val items = s.items
            bloc.removeAllViews()
            if (items.isNullOrEmpty()) { bloc.visibility = View.GONE; majStatut(); return }
            val live = mode == Mode.LIVE
            val rangees = s.groupes?.map { (nom, l) -> "${s.titre} · $nom" to l } ?: listOf(s.titre to items)
            for ((nomRangee, contenu) in rangees) {
            val tete = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            tete.addView(TextView(ctx).apply {
                text = nomRangee; textSize = 16f; setTextColor(Color.parseColor(TXT))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            tete.addView(TextView(ctx).apply {
                text = contenu.size.toString(); textSize = 11f; setTextColor(Color.WHITE)
                background = arrondi(ACCENT, 9); setPadding(dp(7), dp(1), dp(7), dp(1))
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            bloc.addView(tete, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(6) })
            val rangee = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            contenu.forEach { rangee.addView(carte(it, live), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }) }
            bloc.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(rangee) },
                LinearLayout.LayoutParams(-1, -2))
            }
            bloc.visibility = View.VISIBLE
            majStatut()
        }

        var lancerRef: () -> Unit = {}   // `lancer` est défini plus bas
        fun preparer() {
            liste.removeAllViews(); blocs.clear()
            memo.forEach { _ ->
                val b = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
                blocs += b; liste.addView(b, LinearLayout.LayoutParams(-1, -2))
            }
            liste.addView(chargeur, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16)
            })
            memo.indices.forEach { remplir(it) }
            majStatut()
            if (memo.isEmpty()) {
                val recents = SearchHistory.getAll(ctx, SEAU_HISTORIQUE).take(12)
                if (recents.isNotEmpty()) {
                    liste.addView(TextView(ctx).apply {
                        text = "Recherches récentes"; textSize = 14f; setTextColor(Color.parseColor(TXT2))
                        setPadding(dp(2), dp(10), 0, dp(6))
                    }, 0)
                    val flux = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    recents.forEach { r ->
                        flux.addView(TextView(ctx).apply {
                            text = r; textSize = 13f; setTextColor(Color.parseColor(TXT))
                            background = arrondi(CARD, 14, BORDER); setPadding(dp(12), dp(7), dp(12), dp(7)); focusTv(this)
                            setOnClickListener { saisie.setText(r); saisie.setSelection(r.length); lancerRef() }
                        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(8), dp(8)) })
                    }
                    liste.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(flux) }, 1)
                }
            }
        }

        fun lancer() {
            val q = saisie.text.toString().trim()
            if (q.length < 2) { statut.text = "Tape au moins 2 lettres."; return }
            requete = q
            runCatching { SearchHistory.add(ctx, q, SEAU_HISTORIQUE) }
            runCatching {
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(saisie.windowToken, 0)
            }
            job?.cancel()
            val m = mode
            val provs = sources(m)
            val sections = ArrayList<Section>()
            if (m != Mode.LIVE) sections += Section("Ciné Films", null)
            val hub = com.streamflixreborn.streamflix.providers.LiveTvHubProvider
            provs.forEach { p ->
                if (m == Mode.LIVE && p === hub) sections += Section(p.name, p, dossier = "*")
                else sections += Section(p.name, p)
            }
            memo = sections
            preparer()
            job = fragment.viewLifecycleOwner.lifecycleScope.launch {
                chargerPerso(ctx.applicationContext)
                val hubIdx = sections.indices.filter { sections[it].dossier != null }
                val hubJob = if (hubIdx.isEmpty()) null else async(Dispatchers.IO) {
                    val t0 = System.currentTimeMillis()
                    val groupes = (auPlus(DELAI_MS) { hub.rechercheParDossier(q) }
                        .also { Log.i(TAG, "TV Hub « $q » : ${it?.size} dossiers en ${System.currentTimeMillis() - t0} ms") })
                        .orEmpty()
                        .map { (nom, l) ->
                            nom to ParentalControlUtils.filterItems(
                                l.filter { !it.id.startsWith("livehub::folder::") }.onEach { it.providerName = hub.name }
                            ).take(PAR_SOURCE)
                        }.filter { it.second.isNotEmpty() }
                    for (i in hubIdx) {
                        val s = sections[i]
                        withContext(Dispatchers.Main) {
                            if (memo === sections) { s.groupes = groupes; s.items = groupes.flatMap { it.second }; remplir(i) }
                        }
                    }
                }
                (sections.mapIndexedNotNull { i, s -> if (s.dossier != null) null else i to s }).map { (i, s) ->
                    async(Dispatchers.IO) {
                        val res = runCatching {
                            if (s.provider == null) chercherCineFilms(m, q) else chercherDans(s.provider, m, q)
                        }.onFailure { Log.w(TAG, "${s.titre} : ${it.message}") }.getOrDefault(emptyList())
                        withContext(Dispatchers.Main) {
                            if (memo === sections) { s.items = res; remplir(i) }
                        }
                    }
                }.awaitAll()
                hubJob?.await()
            }
        }

        fun ouvrirHistorique() {
            val liste0 = SearchHistory.getAll(ctx, SEAU_HISTORIQUE).take(40)
            if (liste0.isEmpty()) {
                android.widget.Toast.makeText(ctx, "Aucune recherche récente", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val d = Dialog(ctx)
            val boite = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; background = arrondi(CARD, 16, BORDER)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            boite.addView(TextView(ctx).apply {
                text = "🕘  Recherches récentes"; textSize = 16f; setTextColor(Color.parseColor(TXT))
                setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(4), 0, 0, dp(8))
            })
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            liste0.forEach { r ->
                col.addView(TextView(ctx).apply {
                    text = r; textSize = 15f; setTextColor(Color.parseColor(TXT))
                    setPadding(dp(10), dp(10), dp(10), dp(10)); focusTv(this)
                    setOnClickListener { d.dismiss(); saisie.setText(r); saisie.setSelection(r.length); lancer() }
                    // Appui long : retire cette entrée.
                    setOnLongClickListener {
                        SearchHistory.remove(ctx, r, SEAU_HISTORIQUE); visibility = View.GONE; true
                    }
                }, LinearLayout.LayoutParams(-1, -2))
            }
            boite.addView(ScrollView(ctx).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
            boite.addView(TextView(ctx).apply {
                text = "Effacer l'historique"; textSize = 13f; setTextColor(Color.parseColor(ACCENT)); gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(4)); focusTv(this)
                setOnClickListener { SearchHistory.clear(ctx, SEAU_HISTORIQUE); d.dismiss() }
            }, LinearLayout.LayoutParams(-1, -2))
            d.setContentView(boite)
            d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            d.show()
            d.window?.setLayout((ctx.resources.displayMetrics.widthPixels * if (tv) 0.45 else 0.9).toInt(),
                (ctx.resources.displayMetrics.heightPixels * 0.7).toInt())
        }
        histo.setOnClickListener { ouvrirHistorique() }

        lancerRef = { lancer() }
        boutonsMode.forEachIndexed { i, b ->
            b.setOnClickListener {
                if (mode == Mode.values()[i]) return@setOnClickListener
                mode = Mode.values()[i]; peindreModes()
                if (mode != Mode.LIVE && panneau.visibility == View.VISIBLE) { MPC.stop(); panneau.visibility = View.GONE }
                if (saisie.text.toString().trim().length >= 2) lancer()
            }
        }
        go.setOnClickListener { lancer() }
        saisie.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { lancer(); true } else false
        }

        // 2026-09-26 (user, version TV : « une fois que le mini-lecteur apparaît, on ne voit plus
        //   les résultats… mets Film/Série/Live à gauche pour placer le mini-lecteur en haut à
        //   droite ») : sur TV le haut de l'écran passe en DEUX COLONNES — à gauche le titre, la
        //   bascule, la barre de recherche et le statut ; à droite le mini-lecteur (16:9, 38 % de
        //   la largeur). Les résultats gardent toute la largeur en dessous. Téléphone inchangé.
        if (tv) {
            val colonne = listOf<View>(entete, bascule, ligne, statut)
            colonne.forEach { racine.removeView(it) }
            racine.removeView(panneau)
            val gauche = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            colonne.forEach { gauche.addView(it) }
            val larg = (ctx.resources.displayMetrics.widthPixels * 0.38).toInt()
            val haut = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            haut.addView(gauche, LinearLayout.LayoutParams(0, -2, 1f))
            haut.addView(panneau, LinearLayout.LayoutParams(larg, larg * 9 / 16).apply { leftMargin = dp(16) })
            racine.addView(haut, 0, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }

        peindreModes()
        preparer()
        dialog.setContentView(racine)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val suivi = fragment.viewLifecycleOwner.lifecycleScope.launch {
            MPC.state.collect { e ->
                when (e) {
                    is MiniPlayerController.State.Loading -> if (panneau.visibility == View.VISIBLE) {
                        attente.visibility = View.VISIBLE; nomChaine.text = e.channelName
                    }
                    is MiniPlayerController.State.Playing -> if (panneau.visibility == View.VISIBLE) {
                        vue.player = null; vue.player = MPC.getPlayer()
                        attente.visibility = View.GONE; nomChaine.text = e.channelName
                    }
                    is MiniPlayerController.State.Error -> if (panneau.visibility == View.VISIBLE) {
                        attente.visibility = View.GONE
                        android.widget.Toast.makeText(ctx, "Chaîne indisponible — essaie une autre", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    is MiniPlayerController.State.Idle -> panneau.visibility = View.GONE
                }
            }
        }
        dialog.setOnDismissListener {
            job?.cancel(); suivi.cancel()
            runCatching { vue.player = null }
            // Fermé sans passer en plein écran : l'aperçu s'arrête (sauf une radio en fond).
            if (!versPleinEcran && panneau.visibility == View.VISIBLE &&
                !MPC.isRadioChannel(MPC.currentChannelId)) runCatching { MPC.stop() }
            runCatching { MPC.reattachHomePlayerView() }
        }
        dialog.show()
        // 2026-09-26 (user : « elle ne va pas jusqu'en haut de la page… on a une petite croix pour
        //   fermer, pas besoin de voir les icônes tout en haut ») : plein écran réel — la fenêtre
        //   occupe tout l'écran et la barre d'état (heure, batterie…) est masquée.
        dialog.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor(FOND)))
            // La fenêtre du dialogue réservait la hauteur de la barre d'état (bande du haut où
            //   l'écran d'accueil restait visible) : on dessine jusqu'au bord, encoche comprise.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            if (android.os.Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            runCatching {
                androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                }
            }
        }
        // Recherche interrompue (clic sur un résultat avant la fin) : on la relance au retour.
        if (requete.length >= 2 && memo.any { it.items == null }) lancer()
        if (requete.isBlank()) {
            saisie.requestFocus()
            if (!tv) saisie.postDelayed({
                runCatching {
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(saisie, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 200)
        } else if (tv) go.requestFocus()
    }
}
