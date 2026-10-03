package com.streamflixreborn.streamflix.fragments.global_favorites

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.FragmentGlobalFavoritesTvBinding
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.EpisodeFavorites
import com.streamflixreborn.streamflix.utils.GlobalFavorites
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.launch

/**
 * 2026-05-20 : "Cœur" favoris global (TV) — agrège les favoris (films + séries) de
 * TOUS les providers non-IPTV.
 * 2026-09-27 : refait dans le style du panneau Radio : bascule de filtres
 * (Tout / Films / Séries / Épisodes / Replays / Déjà vus), carte « Reprendre »
 * (▶ direct, ⏮ ⏭ pour passer d'une reprise à l'autre), puces de tri, liste en lignes.
 * Les données et les actions (retirer, vider une section) sont inchangées.
 */
class GlobalFavoritesTvFragment : Fragment() {

    private var _binding: FragmentGlobalFavoritesTvBinding? = null
    private val binding get() = _binding!!

    private val appAdapter = AppAdapter()

    private enum class Filtre(val label: String) {
        TOUT("Tout"), FILMS("Films"), SERIES("Séries"), EPISODES("Épisodes"), REPLAYS("Replays"), VUS("Vus")
    }

    private enum class Tri(val label: String) {
        RECENT("Ajout récent"), AZ("A → Z"), DERNIER_VU("Dernier vu"), ANNEE("Année"), SOURCE("Par source"), NON_VUS("Non vus")
    }

    private var filtre = Filtre.TOUT
    private var tri = Tri.RECENT

    // Données chargées (une fois par loadFavorites)
    private var favFilms: List<Movie> = emptyList()
    private var favSeries: List<TvShow> = emptyList()
    private var favSaisons: List<TvShow> = emptyList()
    private var favEpisodes: List<TvShow> = emptyList()
    private var favReplays: List<TvShow> = emptyList()
    private var favRutube: List<TvShow> = emptyList()
    private var vusFilms: List<Movie> = emptyList()
    private var vusSeries: List<TvShow> = emptyList()
    private var reprises: List<Any> = emptyList()   // Movie ou GlobalFavorites.ContinueWatchingSeriesItem
    private var repriseIndex = 0
    // 2026-09-27 (user : « les films et séries à reprendre, il faut qu'ils soient aussi affichés
    //   dans Films et Séries, sinon on n'a pas une vue globale de ce qu'on a vu ou à voir ») :
    //   les reprises, en plus de la carte Reprendre, rejoignent Films / Séries / Tout.
    private var repFilms: List<Movie> = emptyList()
    private var repSeries: List<TvShow> = emptyList()

    private val chipsFiltre = mutableMapOf<Filtre, TextView>()
    private val chipsTri = mutableMapOf<Tri, TextView>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGlobalFavoritesTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lirePrefs()
        binding.vgvFav.adapter = appAdapter
        binding.vgvFav.setNumColumns(COLONNES)
        construireChips()
        binding.tvFavClear.setOnClickListener { menuVider() }
        binding.btnResumePrev.setOnClickListener { deplacerReprise(-1) }
        binding.btnResumeNext.setOnClickListener { deplacerReprise(+1) }
        binding.btnResumePlay.setOnClickListener { lireReprise() }
        binding.llFavResume.setOnClickListener { lireReprise() }
        loadFavorites()
    }

    override fun onResume() {
        super.onResume()
        loadFavorites()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chipsFiltre.clear(); chipsTri.clear()
        // Débranche la liste : sinon l'adaptateur (gardé par le fragment) retient l'ancienne vue à chaque aller-retour.
        // swapAdapter(null, false) et PAS adapter = null : ce dernier recycle les cartes visibles, ce qui
        // annule leurs jaquettes (cartes noires au retour sur l'accueil).
        binding.vgvFav.swapAdapter(null, false)
        _binding = null
    }

    // ------------------------------------------------------------------ préférences

    private fun prefs() = requireContext().getSharedPreferences("coeur_favoris", Context.MODE_PRIVATE)
    private fun cle(s: String) = "${s}_${ProfileManager.currentProfileIdOrDefault()}"

    private fun lirePrefs() {
        val p = prefs()
        filtre = runCatching { Filtre.valueOf(p.getString(cle("filtre"), Filtre.TOUT.name)!!) }.getOrDefault(Filtre.TOUT)
        tri = runCatching { Tri.valueOf(p.getString(cle("tri"), Tri.RECENT.name)!!) }.getOrDefault(Tri.RECENT)
    }

    private fun ecrirePrefs() {
        prefs().edit().putString(cle("filtre"), filtre.name).putString(cle("tri"), tri.name).apply()
    }

    // ------------------------------------------------------------------ chips

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun construireChips() {
        binding.llFavFilters.removeAllViews()
        chipsFiltre.clear()
        for (f in Filtre.values()) {
            val tv = TextView(requireContext()).apply {
                id = View.generateViewId()
                text = f.label; textSize = 12f; gravity = Gravity.CENTER; isSingleLine = true; isFocusable = true
                setTextColor(resources.getColorStateList(R.color.fav_chip_text_mobile, null))
                setBackgroundResource(R.drawable.bg_fav_chip_tv)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isSelected = f == filtre
                setOnClickListener { filtre = f; ecrirePrefs(); rafraichirChips(); afficher() }
            }
            binding.llFavFilters.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(2) })
            chipsFiltre[f] = tv
        }
        // puces de tri (après le libellé « Trier : » déjà dans le layout)
        while (binding.llFavSorts.childCount > 1) binding.llFavSorts.removeViewAt(1)
        chipsTri.clear()
        for (t in Tri.values()) {
            val tv = TextView(requireContext()).apply {
                id = View.generateViewId()
                text = t.label; textSize = 11f; isFocusable = true
                setTextColor(resources.getColorStateList(R.color.fav_chip_text_mobile, null))
                setBackgroundResource(R.drawable.bg_fav_pill_tv)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                isSelected = t == tri
                setOnClickListener { tri = t; ecrirePrefs(); rafraichirChips(); afficher() }
            }
            binding.llFavSorts.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
            chipsTri[t] = tv
        }
        cablerFocus()
    }

    private fun rafraichirChips() {
        chipsFiltre.forEach { (f, tv) -> tv.isSelected = f == filtre }
        chipsTri.forEach { (t, tv) -> tv.isSelected = t == tri }
    }

    private fun majComptes() {
        val nb = mapOf(
            Filtre.TOUT to (favFilms.size + repFilms.size + favSeries.size + repSeries.size + favSaisons.size + favEpisodes.size + favReplays.size + favRutube.size),
            Filtre.FILMS to (favFilms.size + repFilms.size),
            Filtre.SERIES to (favSeries.size + repSeries.size + favSaisons.size),
            Filtre.EPISODES to favEpisodes.size,
            Filtre.REPLAYS to (favReplays.size + favRutube.size),
            Filtre.VUS to (vusFilms.size + vusSeries.size),
        )
        chipsFiltre.forEach { (f, tv) ->
            val n = nb[f] ?: 0
            tv.text = if (n > 0 && f != Filtre.TOUT) "${f.label} $n" else f.label
        }
        binding.tvFavCount.text = (nb[Filtre.TOUT] ?: 0).toString()
        val sources = (favFilms.map { it.providerName } + favSeries.map { it.providerName }).filterNotNull().distinct().size
        binding.tvFavSources.text = if (sources > 0) "$sources source${if (sources > 1) "s" else ""}" else ""
        binding.tvFavSources.visibility = if (sources > 0) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ chargement

    private fun loadFavorites() {
        binding.pbFavLoading.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val (movies, tvShows) = try {
                GlobalFavorites.load(requireContext())
            } catch (e: Exception) {
                Log.e(TAG, "loadFavorites failed", e)
                emptyList<Movie>() to emptyList<TvShow>()
            }
            if (_binding == null) return@launch

            movies.forEach {
                it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                if (it.providerName.isNullOrBlank()) it.providerName = GlobalFavorites.originByItemId[it.id]
            }
            tvShows.forEach {
                it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
                if (it.providerName.isNullOrBlank()) it.providerName = GlobalFavorites.originByItemId[it.id]
            }

            // Saisons favorites
            val seasonFavs = com.streamflixreborn.streamflix.utils.SeasonFavorites.all().map { e ->
                TvShow(
                    id = e.syntheticId(),
                    title = "${e.showTitle} — Saison ${e.seasonNumber}",
                    poster = e.showPoster,
                    banner = e.showBanner,
                ).apply { itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM }
            }

            // Épisodes favoris — poster SÉRIE + titre "Série — S1E3"
            val episodeFavs = EpisodeFavorites.all().map { e ->
                val epLabel = if (e.seasonNumber > 0) "S${e.seasonNumber}E${e.episodeNumber}" else "E${e.episodeNumber}"
                TvShow(
                    id = e.syntheticId(),
                    title = "${e.showTitle} — $epLabel",
                    overview = e.episodeTitle,
                    poster = e.showPoster ?: e.episodePoster,
                    banner = e.showBanner,
                ).apply { itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM }
            }

            // ★ Rutube (clips) — catégorie dédiée, file de lecture publiée (2026-08-13/14)
            val rutubeFavs = com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.all()
                .filter { com.streamflixreborn.streamflix.providers.RutubeFolder.estClipDossier(it.id) }
                .map { e ->
                    TvShow(id = e.syntheticId(), title = e.title, poster = e.poster, banner = e.banner).apply {
                        itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
                        isMovie = true
                        providerName = "TV Hub"
                    }
                }
            if (rutubeFavs.isNotEmpty()) {
                com.streamflixreborn.streamflix.providers.RutubeFolder.publierListeAffichee(rutubeFavs)
            }

            // Favoris replay (TV Hub)
            val replayFavs = com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.all()
                .filterNot { com.streamflixreborn.streamflix.providers.RutubeFolder.estClipDossier(it.id) }
                .map { e ->
                    TvShow(id = e.syntheticId(), title = e.title, poster = e.poster, banner = e.banner).apply {
                        itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
                        isMovie = e.isMovie
                        providerName = "TV Hub"
                    }
                }

            // Reprises de lecture
            val cwMovies = try { GlobalFavorites.loadContinueWatchingMovies(requireContext(), 20) } catch (_: Exception) { emptyList() }
            cwMovies.forEach {
                it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                if (it.providerName.isNullOrBlank()) it.providerName = GlobalFavorites.originByItemId["resume_movie_${it.id}"]
            }
            val cwSeries = try { GlobalFavorites.loadContinueWatchingSeries(requireContext(), 20) } catch (_: Exception) { emptyList() }
            // Reprises dans les listes Films / Séries (une œuvre déjà en favori n'est pas doublée).
            //   Films : la carte grille ouvre la fiche dans la source d'origine via originByItemId.
            //   Séries : id « resume_series_… », que la carte grille route déjà vers le lecteur.
            val idsFavFilms = movies.map { it.id }.toSet()
            val reprisesFilms = cwMovies.filter { it.id !in idsFavFilms }.onEach { m ->
                GlobalFavorites.originByItemId["resume_movie_${m.id}"]?.let { o -> GlobalFavorites.originByItemId.putIfAbsent(m.id, o) }
            }
            val idsFavSeries = tvShows.map { it.id }.toSet()
            val reprisesSeries = cwSeries.filter { it.tvShow.id !in idsFavSeries }.map { r ->
                TvShow(
                    id = "resume_series_${r.tvShow.id}",
                    title = r.tvShow.title,
                    overview = r.tvShow.overview,
                    released = r.tvShow.released?.format("yyyy-MM-dd"),
                    poster = r.tvShow.poster ?: r.lastEpisode.poster,
                    banner = r.tvShow.banner,
                ).apply {
                    itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
                    providerName = r.providerName
                }
            }

            // Déjà vus
            val (wMovies, wShows) = try { GlobalFavorites.loadWatched(requireContext()) } catch (e: Exception) {
                Log.w(TAG, "loadWatched failed", e); emptyList<Movie>() to emptyList<TvShow>()
            }
            wMovies.forEach {
                it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                if (it.providerName.isNullOrBlank()) it.providerName = GlobalFavorites.originByItemId[it.id]
            }
            wShows.forEach {
                it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
                if (it.providerName.isNullOrBlank()) it.providerName = GlobalFavorites.originByItemId[it.id]
            }

            if (_binding == null) return@launch
            favFilms = movies; favSeries = tvShows; favSaisons = seasonFavs; favEpisodes = episodeFavs
            favReplays = replayFavs; favRutube = rutubeFavs
            vusFilms = wMovies; vusSeries = wShows
            repFilms = reprisesFilms; repSeries = reprisesSeries
            reprises = (cwMovies.map { it as Any } + cwSeries.map { it as Any })
                .sortedByDescending { r ->
                    when (r) {
                        is Movie -> r.watchHistory?.lastEngagementTimeUtcMillis ?: 0L
                        is GlobalFavorites.ContinueWatchingSeriesItem -> r.lastEpisode.watchHistory?.lastEngagementTimeUtcMillis ?: 0L
                        else -> 0L
                    }
                }
            repriseIndex = repriseIndex.coerceIn(0, (reprises.size - 1).coerceAtLeast(0))

            binding.pbFavLoading.visibility = View.GONE
            majComptes()
            afficherReprise()
            afficher()
        }
    }

    // ------------------------------------------------------------------ affichage

    private fun afficher() {
        val b = _binding ?: return
        val items: List<AppAdapter.Item> = when (filtre) {
            Filtre.TOUT -> favFilms + repFilms + favSeries + repSeries + favSaisons + favEpisodes + favReplays + favRutube
            Filtre.FILMS -> favFilms + repFilms
            Filtre.SERIES -> favSeries + repSeries + favSaisons
            Filtre.EPISODES -> favEpisodes
            Filtre.REPLAYS -> favReplays + favRutube
            Filtre.VUS -> {
                val ids = mutableSetOf<String>()
                (vusFilms + favFilms.filter { it.isWatched } + vusSeries).filter { ids.add(idDe(it)) }
            }
        }
        val tries = trier(items)
        appAdapter.submitList(tries)
        b.tvFavEmpty.visibility = if (tries.isEmpty()) View.VISIBLE else View.GONE
        b.tvFavEmpty.text = when (filtre) {
            Filtre.VUS -> "Rien de vu pour l'instant"
            Filtre.TOUT -> "Aucun favori"
            else -> "Aucun favori dans « ${filtre.label} »"
        }
    }

    private fun idDe(i: AppAdapter.Item) = when (i) { is Movie -> "m" + i.id; is TvShow -> "t" + i.id; else -> i.hashCode().toString() }
    private fun titre(i: AppAdapter.Item) = when (i) { is Movie -> i.title; is TvShow -> i.title; else -> "" }
    private fun ajout(i: AppAdapter.Item) = when (i) { is Movie -> i.favoritedAtMillis ?: i.watchHistory?.lastEngagementTimeUtcMillis ?: 0L; is TvShow -> i.favoritedAtMillis ?: repriseSerieTs(i); else -> 0L }
    /** Dernière lecture d'une reprise série (id « resume_series_… »), 0 sinon. */
    private fun repriseSerieTs(t: TvShow): Long = GlobalFavorites.resumeSeriesData[t.id]?.lastEpisode?.watchHistory?.lastEngagementTimeUtcMillis ?: 0L
    private fun annee(i: AppAdapter.Item) = when (i) { is Movie -> i.released?.get(java.util.Calendar.YEAR) ?: 0; is TvShow -> i.released?.get(java.util.Calendar.YEAR) ?: 0; else -> 0 }
    private fun source(i: AppAdapter.Item) = when (i) { is Movie -> i.providerName; is TvShow -> i.providerName; else -> null } ?: ""
    private fun dernierVu(i: AppAdapter.Item): Long = when (i) {
        is Movie -> maxOf(i.watchedDate?.timeInMillis ?: 0L, i.watchHistory?.lastEngagementTimeUtcMillis ?: 0L)
        is TvShow -> maxOf(i.episodeToWatch?.watchHistory?.lastEngagementTimeUtcMillis ?: 0L, repriseSerieTs(i))
        else -> 0L
    }
    private fun estVu(i: AppAdapter.Item) = when (i) {
        is Movie -> i.isWatched
        is TvShow -> i.seasons.flatMap { it.episodes }.let { it.isNotEmpty() && it.all { e -> e.isWatched } }
        else -> false
    }

    private fun trier(items: List<AppAdapter.Item>): List<AppAdapter.Item> = when (tri) {
        Tri.RECENT -> items.sortedByDescending { ajout(it) }
        Tri.AZ -> items.sortedBy { titre(it).lowercase().removePrefix("le ").removePrefix("la ").removePrefix("les ").removePrefix("the ") }
        Tri.DERNIER_VU -> items.sortedByDescending { dernierVu(it) }
        Tri.ANNEE -> items.sortedByDescending { annee(it) }
        Tri.SOURCE -> items.sortedWith(compareBy({ source(it).lowercase() }, { titre(it).lowercase() }))
        Tri.NON_VUS -> items.sortedWith(compareBy<AppAdapter.Item> { estVu(it) }.thenByDescending { ajout(it) })
    }

    // ------------------------------------------------------------------ carte Reprendre

    // ------------------------------------------------------------------ focus télécommande
    // 2026-09-27 (user : « on peut pas aller sur le bouton lecture rouge, et depuis les
    //   jaquettes on est obligé d'aller à droite pour remonter ») : la grille Leanback ne
    //   laisse pas sortir le focus par le haut (focusOutFront = false par défaut), seule la
    //   sortie latérale était possible ; et le chercheur de focus Android choisit ses cibles
    //   à la distance, ce qui rendait le ▶ (petit, à droite) inatteignable depuis les puces.
    //   On câble donc explicitement : puces → ▶ (ou grille) ; ⏮ ▶ ⏭ → grille en bas, tri en
    //   haut ; grille → ▶ (ou 1re puce de filtre) en haut.
    private fun cablerFocus() {
        val b = _binding ?: return
        val premierFiltre = chipsFiltre[Filtre.values().first()]
        val premierTri = chipsTri[Tri.values().first()]
        val repriseVisible = b.llFavResume.visibility == View.VISIBLE
        // Vers la grille : `nextFocusDown` sur un ViewGroup non focusable est ignoré par
        // Android → on prend la touche BAS nous-mêmes et on donne le focus à la grille
        // (Leanback le pose sur la jaquette sélectionnée).
        val versGrille = View.OnKeyListener { _, code, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && code == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                b.vgvFav.adapter?.itemCount ?: 0 > 0) { b.vgvFav.requestFocus(); true } else false
        }
        for (tv in chipsFiltre.values + chipsTri.values) {
            if (repriseVisible) { tv.nextFocusDownId = b.btnResumePlay.id; tv.setOnKeyListener(null) }
            else { tv.nextFocusDownId = View.NO_ID; tv.setOnKeyListener(versGrille) }
        }
        b.tvFavClear.nextFocusDownId = (premierTri ?: premierFiltre ?: b.tvFavClear).id
        for (btn in listOf(b.btnResumePrev, b.btnResumePlay, b.btnResumeNext)) {
            btn.setOnKeyListener(versGrille)
            btn.nextFocusUpId = (premierTri ?: premierFiltre ?: b.tvFavClear).id
        }
        b.btnResumePrev.nextFocusRightId = b.btnResumePlay.id
        b.btnResumePlay.nextFocusLeftId = (if (b.btnResumePrev.visibility == View.VISIBLE) b.btnResumePrev else b.btnResumePlay).id
        b.btnResumePlay.nextFocusRightId = (if (b.btnResumeNext.visibility == View.VISIBLE) b.btnResumeNext else b.btnResumePlay).id
        b.btnResumeNext.nextFocusLeftId = b.btnResumePlay.id
        // (sortie par le haut autorisée : app:focusOutFront="true" dans le layout)
        // Depuis la 1re rangée, HAUT va droit sur ▶ (ou la 1re puce de filtre) : le
        // `nextFocusUp` de la grille n'est pas consulté (c'est la jaquette qui a le focus),
        // on intercepte donc la touche nous-mêmes.
        val cibleHaut: View = if (repriseVisible) b.btnResumePlay else (premierFiltre ?: b.tvFavClear)
        b.vgvFav.setOnKeyInterceptListener { event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                b.vgvFav.selectedPosition in 0 until COLONNES) {
                cibleHaut.requestFocus(); true
            } else false
        }
    }

    private fun afficherReprise() {
        val b = _binding ?: return
        if (reprises.isEmpty()) { b.llFavResume.visibility = View.GONE; cablerFocus(); return }
        b.llFavResume.visibility = View.VISIBLE
        val plusieurs = reprises.size > 1
        b.btnResumePrev.visibility = if (plusieurs) View.VISIBLE else View.GONE
        b.btnResumeNext.visibility = if (plusieurs) View.VISIBLE else View.GONE
        cablerFocus()
        when (val r = reprises[repriseIndex]) {
            is Movie -> {
                b.tvResumeTitle.text = r.title
                b.tvResumeSource.text = r.providerName ?: ""
                val wh = r.watchHistory
                val reste = if (wh != null && wh.durationMillis > 0) ((wh.durationMillis - wh.lastPlaybackPositionMillis) / 60000L).coerceAtLeast(1) else 0L
                b.tvResumeSub.text = if (reste > 0) "Film · reste $reste min" else "Film"
                b.pbResume.progress = if (wh != null && wh.durationMillis > 0) (wh.lastPlaybackPositionMillis * 100 / wh.durationMillis.toDouble()).toInt() else 0
                Glide.with(b.ivResumePoster).load(r.poster ?: r.banner).centerCrop().into(b.ivResumePoster)
            }
            is GlobalFavorites.ContinueWatchingSeriesItem -> {
                b.tvResumeTitle.text = r.tvShow.title
                b.tvResumeSource.text = r.providerName
                val wh = r.lastEpisode.watchHistory
                val ep = (if (r.seasonNumber > 0) "S${r.seasonNumber} " else "") + "E${r.episodeNumber}"
                val reste = if (wh != null && wh.durationMillis > 0) ((wh.durationMillis - wh.lastPlaybackPositionMillis) / 60000L).coerceAtLeast(1) else 0L
                b.tvResumeSub.text = if (reste > 0) "$ep · reste $reste min" else ep
                b.pbResume.progress = if (wh != null && wh.durationMillis > 0) (wh.lastPlaybackPositionMillis * 100 / wh.durationMillis.toDouble()).toInt() else 0
                Glide.with(b.ivResumePoster).load(r.tvShow.poster ?: r.lastEpisode.poster).centerCrop().into(b.ivResumePoster)
            }
        }
    }

    private fun deplacerReprise(delta: Int) {
        if (reprises.isEmpty()) return
        repriseIndex = (repriseIndex + delta + reprises.size) % reprises.size
        afficherReprise()
    }

    /** ▶ : ouvre directement le lecteur sur la reprise affichée (même chemin que la carte du cœur). */
    private fun lireReprise() {
        if (reprises.isEmpty()) return
        when (val r = reprises[repriseIndex]) {
            is Movie -> {
                GlobalFavorites.switchToOrigin("resume_movie_${r.id}")
                findNavController().navigate(R.id.action_global_player, Bundle().apply {
                    putString("id", r.id)
                    putString("title", r.title)
                    putString("subtitle", r.released?.format("yyyy") ?: "")
                    putSerializable("videoType", Video.Type.Movie(
                        id = r.id, title = r.title,
                        releaseDate = r.released?.format("yyyy-MM-dd") ?: "",
                        poster = r.poster ?: r.banner ?: "", imdbId = r.imdbId,
                    ))
                })
            }
            is GlobalFavorites.ContinueWatchingSeriesItem -> {
                GlobalFavorites.switchToOrigin("resume_series_${r.tvShow.id}")
                val ep = r.lastEpisode
                findNavController().navigate(R.id.action_global_player, Bundle().apply {
                    putString("id", ep.id)
                    putString("title", r.tvShow.title)
                    putString("subtitle", "${if (r.seasonNumber > 0) "S${r.seasonNumber}" else ""}E${r.episodeNumber} — ${ep.title ?: ""}")
                    putSerializable("videoType", Video.Type.Episode(
                        id = ep.id, number = ep.number, title = ep.title, poster = ep.poster, overview = ep.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = r.tvShow.id, title = r.tvShow.title, poster = r.tvShow.poster, banner = r.tvShow.banner,
                            releaseDate = r.tvShow.released?.format("yyyy-MM-dd"), imdbId = r.tvShow.imdbId,
                        ),
                        season = Video.Type.Episode.Season(number = r.seasonNumber, title = ep.season?.title),
                    ))
                })
            }
        }
    }

    // ------------------------------------------------------------------ actions

    /** Appui long sur un favori : le retirer + recharger. (inchangé) */
    fun removeFavorite(itemId: String, isMovie: Boolean) {
        if (itemId.startsWith(com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.SYNTHETIC_ID_PREFIX)) {
            com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.removeBySyntheticId(itemId)
            Toast.makeText(requireContext(), "Replay retiré des favoris", Toast.LENGTH_SHORT).show()
            loadFavorites(); return
        }
        if (itemId.startsWith(com.streamflixreborn.streamflix.utils.SeasonFavorites.SYNTHETIC_ID_PREFIX)) {
            com.streamflixreborn.streamflix.utils.SeasonFavorites.removeBySyntheticId(itemId)
            Toast.makeText(requireContext(), "Saison retirée des favoris", Toast.LENGTH_SHORT).show()
            loadFavorites(); return
        }
        if (itemId.startsWith(EpisodeFavorites.SYNTHETIC_ID_PREFIX)) {
            EpisodeFavorites.removeBySyntheticId(itemId)
            Toast.makeText(requireContext(), "Épisode retiré des favoris", Toast.LENGTH_SHORT).show()
            loadFavorites(); return
        }
        if (itemId.startsWith("resume_")) {
            GlobalFavorites.dismissContinueWatching(itemId)
            Toast.makeText(requireContext(), "Reprise retirée", Toast.LENGTH_SHORT).show()
            loadFavorites(); return
        }
        // 2026-10-01 (remonté par un utilisateur : « la catégorie Vus ne peut pas être effacée ») :
        //   dans le filtre « Vus », l'appui long retire le drapeau « vu » (pas le favori).
        if (filtre == Filtre.VUS) {
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = GlobalFavorites.unmarkWatched(requireContext(), itemId, isMovie)
                if (_binding == null) return@launch
                if (ok) {
                    Toast.makeText(requireContext(), "Retiré des vus", Toast.LENGTH_SHORT).show()
                    loadFavorites()
                }
            }
            return
        }
        if (isMovie && GlobalFavorites.originByItemId.containsKey("resume_movie_$itemId")
            && favFilms.none { it.id == itemId } && vusFilms.none { it.id == itemId }) {
            GlobalFavorites.dismissContinueWatching("resume_movie_$itemId")
            Toast.makeText(requireContext(), "Reprise retirée", Toast.LENGTH_SHORT).show()
            loadFavorites(); return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = GlobalFavorites.removeFavorite(requireContext(), itemId, isMovie)
            if (_binding == null) return@launch
            if (ok) {
                Toast.makeText(requireContext(), "Retiré des favoris", Toast.LENGTH_SHORT).show()
                loadFavorites()
            }
        }
    }

    /** 🗑 : vider une section entière (même logique qu'avant, via un menu). */
    private fun menuVider() {
        val sections = listOf("Films", "Séries", "Saisons", "Épisodes", "Replays", "Reprendre", "Vus")
        AlertDialog.Builder(requireContext())
            .setTitle("Vider…")
            .setItems(sections.toTypedArray()) { _, i ->
                val s = sections[i]
                AlertDialog.Builder(requireContext())
                    .setMessage("Vider « $s » ?")
                    .setPositiveButton("Vider") { _, _ -> clearSection(s) }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
            .show()
    }

    private fun clearSection(sectionName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (sectionName) {
                    "Films" -> GlobalFavorites.clearAllFavoriteMovies(requireContext())
                    "Séries" -> GlobalFavorites.clearAllFavoriteTvShows(requireContext())
                    "Saisons" -> com.streamflixreborn.streamflix.utils.SeasonFavorites.clearAll()
                    "Épisodes" -> EpisodeFavorites.clearAll()
                    "Replays" -> com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.clearAll()
                    "Reprendre" -> GlobalFavorites.dismissAllContinueWatching()
                    "Vus" -> GlobalFavorites.clearAllWatched(requireContext())
                }
                if (_binding == null) return@launch
                Toast.makeText(requireContext(), "« $sectionName » vidé", Toast.LENGTH_SHORT).show()
                loadFavorites()
            } catch (e: Exception) {
                Log.e(TAG, "clearSection $sectionName failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "GlobalFavoritesTv"
        private const val COLONNES = 8
    }
}
