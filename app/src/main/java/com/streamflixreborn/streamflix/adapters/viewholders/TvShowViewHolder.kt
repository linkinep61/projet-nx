package com.streamflixreborn.streamflix.adapters.viewholders

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.Toast
import android.util.Log
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.*
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragmentDirections
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import android.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.streamflixreborn.streamflix.fragments.movie.MovieMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.movies.MoviesMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.movies.MoviesTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.search.SearchMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.search.SearchTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.genre.GenreMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.genre.GenreTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.people.PeopleMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.people.PeopleTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.home.HomeMobileFragmentDirections
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.ui.ShowOptionsMobileDialog
import com.streamflixreborn.streamflix.ui.ShowOptionsTvDialog
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.toActivity
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.utils.LanguageTag
import com.streamflixreborn.streamflix.utils.RatingService
import com.streamflixreborn.streamflix.utils.CommunityRatingView
import com.streamflixreborn.streamflix.utils.CommunityLanguageView
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.loadTvShowBanner
import com.streamflixreborn.streamflix.utils.loadTvShowPoster
import com.streamflixreborn.streamflix.utils.ArtworkRepair
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.MiniPlayerController
import java.util.Locale

class TvShowViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    companion object {
        /** 2026-05-31 : debounce GLOBAL pour éviter les double-clics rapides
         *  quand RecyclerView réutilise un ViewHolder différent. */
        @Volatile private var lastClickTime = 0L
    }

    private val context = itemView.context
    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)
    private lateinit var tvShow: TvShow

    private val clickDebounceMs = 800L

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ContentTvShowSeasonsMobileBinding -> _binding.rvTvShowSeasons
            is ContentTvShowSeasonsTvBinding -> _binding.hgvTvShowSeasons
            is ContentTvShowCastMobileBinding -> _binding.rvTvShowCast
            is ContentTvShowCastTvBinding -> _binding.hgvTvShowCast
            is ContentTvShowRecommendationsMobileBinding -> _binding.rvTvShowRecommendations
            is ContentTvShowRecommendationsTvBinding -> _binding.hgvTvShowRecommendations
            else -> null
        }

    fun bind(tvShow: TvShow) {
        this.tvShow = tvShow

        when (_binding) {
            is ItemTvShowMobileBinding -> displayMobileItem(_binding)
            is ItemTvShowTvBinding -> displayTvItem(_binding)
            is ItemTvShowGridMobileBinding -> displayGridMobileItem(_binding)
            is ItemTvShowGridBinding -> displayGridTvItem(_binding)
            is ItemCategorySwiperMobileBinding -> displaySwiperMobileItem(_binding)

            is ContentTvShowMobileBinding -> displayTvShowMobile(_binding)
            is ContentTvShowTvBinding -> displayTvShowTv(_binding)
            is ContentTvShowSeasonsMobileBinding -> displaySeasonsMobile(_binding)
            is ContentTvShowSeasonsTvBinding -> displaySeasonsTv(_binding)
            is ContentTvShowDirectorsMobileBinding -> displayDirectorsMobile(_binding)
            is ContentTvShowDirectorsTvBinding -> displayDirectorsTv(_binding)
            is ContentTvShowCastMobileBinding -> displayCastMobile(_binding)
            is ContentTvShowCastTvBinding -> displayCastTv(_binding)
            is ContentTvShowRecommendationsMobileBinding -> displayRecommendationsMobile(_binding)
            is ContentTvShowRecommendationsTvBinding -> displayRecommendationsTv(_binding)
        }

        // 2026-05-08 : si chaîne IPTV bannie, grise la card (alpha 0.4).
        // L'user peut quand même cliquer pour la lire (override volontaire)
        // ou long-press → menu → "Débannir" pour la réactiver.
        if (isIptvProvider()) {
            val isBanned = com.streamflixreborn.streamflix.fragments.player.settings
                .IptvBannedChannels.isBanned(tvShow.id)
            try {
                _binding.root.alpha = if (isBanned) 0.4f else 1.0f
            } catch (_: Throwable) { }
        }
    }

    /**
     * Handle long-press for an IPTV channel: toggle favorite + Toast.
     * For non-IPTV items the regular ShowOptionsXxxDialog is shown instead.
     *
     * Returns `true` if the long-press was handled here (i.e. it WAS an IPTV
     * channel), `false` otherwise so the caller falls back to the dialog.
     *
     * Side effect on toggle: re-navigates to home so the Favoris section is
     * refreshed immediately. Without this the user would have to leave/re-enter
     * the home page to see their newly-favorited channel.
     */
    private fun handleIptvFavoriteLongPress(): Boolean {
        if (!isIptvProvider()) return false
        // 2026-06-20 (user "si on veut le retirer des favoris Actuellement sur
        //   l'image il est affiché On peut pas le retirer d'ici obligé d'aller
        //   dans le hall avec le cœur Pour le retirer") : TV Hub a aussi des
        //   favoris REPLAY (= films/séries replay stockés dans
        //   ReplayFavoritesStore, id `livehub::replay::*`). On les détecte
        //   AVANT le menu IPTV channels et on propose un retrait direct.
        if (tvShow.id.startsWith("livehub::replay::")) {
            val isFav = com.streamflixreborn.streamflix.utils.ReplayFavoritesStore
                .isFavorite(tvShow.id)
            val option = if (isFav) "★ Retirer des favoris" else "★ Ajouter aux favoris"
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(tvShow.title)
                .setItems(arrayOf(option)) { _, _ ->
                    // 2026-06-20 (user "le film Titanic est traité comme une
                    //   série et ouvre une page synopsis") : préserver isMovie
                    //   depuis le tvShow (= valeur correcte du M3U tvg-type),
                    //   sinon Titanic perd son isMovie=true et le clic ouvre
                    //   la synopsis au lieu du player.
                    val nowFav = com.streamflixreborn.streamflix.utils.ReplayFavoritesStore
                        .toggle(tvShow.id, tvShow.title, tvShow.poster, tvShow.banner,
                            isMovie = tvShow.isMovie)
                    val msg = if (nowFav) "Ajouté aux favoris" else "Retiré des favoris"
                    Toast.makeText(context, "${tvShow.title} — $msg", Toast.LENGTH_SHORT).show()
                    // Invalide le cache TV Hub + refresh home pour que la
                    //   section "Favoris Replay" se mette à jour immédiatement.
                    try {
                        val current = UserPreferences.currentProvider
                        if (current != null) {
                            (current as? com.streamflixreborn.streamflix.providers.LiveTvHubProvider)
                                ?.clearHomeCache()
                            com.streamflixreborn.streamflix.utils.HomeCacheStore
                                .clear(context, current)
                            UserPreferences.currentProvider = current
                        }
                    } catch (_: Throwable) { }
                }
                .show()
            return true
        }
        // 2026-05-08 : long-press IPTV propose un menu contextuel
        //  - Ajouter / Retirer des favoris (★ + ❤ servers)
        //  - Bannir / Débannir cette chaîne
        val providerName = tvShow.providerName ?: UserPreferences.currentProvider?.name ?: return false
        // 2026-05-08 : "favori" = ★ store OU ❤ servers store. Si l'user a ajouté
        // la chaîne via ❤ sur un server, on doit pouvoir la retirer du Hub via
        // ce menu. Donc on détecte les 2 sources.
        val isStoreFavorite = com.streamflixreborn.streamflix.utils.IptvFavoritesStore.isFavorite(providerName, tvShow.id)
        val hasServerFavorites = com.streamflixreborn.streamflix.fragments.player.settings
            .IptvFavorites.count(tvShow.id) > 0
        val isFavorite = isStoreFavorite || hasServerFavorites
        val isBanned = com.streamflixreborn.streamflix.fragments.player.settings.IptvBannedChannels.isBanned(tvShow.id)

        val options = arrayOf(
            if (isFavorite) "★ Retirer des favoris" else "★ Ajouter aux favoris",
            if (isBanned) "✕ Débannir cette chaîne" else "✕ Bannir cette chaîne",
        )

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(tvShow.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (isFavorite) {
                            // Retirer des 2 stores : ★ channel-fav + ❤ server-favs.
                            // Sinon un seul ❤ resté actif laisse la chaîne dans le Hub.
                            if (isStoreFavorite) {
                                com.streamflixreborn.streamflix.utils.IptvFavoritesStore.toggle(providerName, tvShow.id)
                            }
                            if (hasServerFavorites) {
                                com.streamflixreborn.streamflix.fragments.player.settings
                                    .IptvFavorites.clearAllForChannel(tvShow.id)
                            }
                            Toast.makeText(context, "${tvShow.title} — Retiré des favoris", Toast.LENGTH_SHORT).show()
                        } else {
                            com.streamflixreborn.streamflix.utils.IptvFavoritesStore.toggle(providerName, tvShow.id)
                            Toast.makeText(context, "${tvShow.title} — Ajouté aux favoris", Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        val nowBanned = com.streamflixreborn.streamflix.fragments.player.settings.IptvBannedChannels.toggle(tvShow.id)
                        val msg = if (nowBanned) "Bannie (cachée du catalogue)" else "Réactivée"
                        Toast.makeText(context, "${tvShow.title} — $msg", Toast.LENGTH_SHORT).show()
                    }
                }
                // 2026-06-08 (user "les chaînes bannies se mettent enfoncées
                //   mais restent présentes") : le HomeCacheStore garde l'ancien
                //   home en mémoire + disque, donc le filter isBanned de
                //   getHome() ne tournait pas après le toggle. Clear le cache
                //   du provider courant pour FORCER un re-getHome au refresh.
                try {
                    val current = UserPreferences.currentProvider
                    if (current != null) {
                        com.streamflixreborn.streamflix.utils.HomeCacheStore
                            .clear(context, current)
                    }
                } catch (_: Throwable) { }
                // Refresh home — seulement si PAS OTF (le reload OTF est lent)
                if (!tvShow.id.startsWith("livehub::otf::")) {
                    try {
                        val current = UserPreferences.currentProvider
                        if (current != null) UserPreferences.currentProvider = current
                    } catch (_: Throwable) { }
                }
            }
            .show()
        return true
    }

    /** 2026-05-13 (user "on peut peut être garder le carré c'est peut être pas
     *  dérangeant à voir du moment que derrière on refond des fonds d'écran") :
     *  carré clair arrondi derrière le logo IPTV (style icône d'app), comme ça
     *  les logos avec contenu noir (France 2/5, France Inter) restent lisibles.
     *  Le gradient du home est visible PARTOUT autour, donc pas dérangeant. */
    private fun applyIptvLogoStyle(iv: ImageView) {
        if (isIptvProvider()) {
            iv.setBackgroundResource(R.drawable.bg_iptv_logo_light)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (10 * iv.resources.displayMetrics.density).toInt()
            iv.setPadding(pad, pad, pad, pad)
        } else {
            iv.background = null
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setPadding(0, 0, 0, 0)
        }
    }

    private fun isIptvProvider(): Boolean {
        // Match OlaTv (ola::, ola_ep::), Vegeta TV (vegeta::,
        // vegeta_ep::), Sport Live (sportlive::), Movix LiveTV (movixlivetv::),
        // TV Hub (livehub::) IDs, plus les providerNames.
        // 2026-06-08 : ajout World Live (livehubplus::) — duplicate de TV Hub
        //   pour les playlists Wiseplay supplémentaires (World Live TV).
        // 2026-06-18 v24 : EXCEPTION replay TF1+/M6 — ces ids contiennent
        //   `replay::tf1/<show>` ou `replay::m6/<show>` (= un programme avec
        //   plusieurs épisodes). Click DOIT ouvrir la page détail (= liste
        //   épisodes), PAS lancer auto-play. On exclut donc de isIptvProvider().
        val id = tvShow.id
        // 2026-06-19 : étendu aux 6 services M6+ (= m6replay/w9replay/6terreplay/gulli/tevareplay/parispremierereplay)
        // 2026-06-19 v19 : SI le tvShow est marqué isMovie=true (= film, spectacle,
        //   documentaire, etc.), on lance en mini lecteur direct → on garde
        //   isIptvProvider=true. Seules les VRAIES séries (= isMovie=false)
        //   doivent ouvrir la fiche détail avec saisons/épisodes.
        // 2026-06-20 v6 : ajout tmc/ tfx/ tf1-series-films/ lci/ (= chaînes
        //   TF1+ issues du M3U data-replay.m3u format "tf1plus://tmc/slug")
        val isReplayProgram = id.startsWith("livehub::replay::tf1/")
            || id.startsWith("livehub::replay::tmc/")
            || id.startsWith("livehub::replay::tfx/")
            || id.startsWith("livehub::replay::tf1-series-films/")
            || id.startsWith("livehub::replay::lci/")
            || id.startsWith("livehub::replay::m6/")
            || id.startsWith("livehub::replay::m6replay/")
            || id.startsWith("livehub::replay::w9replay/")
            || id.startsWith("livehub::replay::6terreplay/")
            || id.startsWith("livehub::replay::gulli/")
            || id.startsWith("livehub::replay::tevareplay/")
            || id.startsWith("livehub::replay::parispremierereplay/")
            || id.startsWith("livehub::replay::program/")
            || id.startsWith("livehub::replay::plexshow::")
            || id.startsWith("livehub::replay::plutoshow::")
        if (isReplayProgram && !tvShow.isMovie) {
            android.util.Log.d("TvShowViewHolder", "v25 isIptvProvider FALSE for replay program: $id")
            return false
        }
        return tvShow.providerName == "OLA TV"
            || tvShow.providerName == "Vegeta TV"
            || tvShow.providerName == "Vavoo TV"
            || tvShow.providerName == "Sport Live"
            || tvShow.providerName == "Movix LiveTV"
            || tvShow.providerName == "TV Hub"
            || tvShow.providerName == "World Live"
            || tvShow.providerName == "Mon IPTV"
            || id.startsWith("ch::")
            || id.startsWith("sport::")
            || id.startsWith("ola::")
            || id.startsWith("ola_ep::")
            || id.startsWith("vegeta::")
            || id.startsWith("vegeta_ep::")
            || id.startsWith("vavoo::")
            || id.startsWith("sportlive::")
            || id.startsWith("match::")
            || id.startsWith("movixlivetv::")
            || id.startsWith("livehub::")
            || id.startsWith("livehubplus::")
            || id.startsWith("myiptv-live::")
    }

    private fun checkProviderAndRun(action: () -> Unit) {
        if (!tvShow.providerName.isNullOrBlank() && tvShow.providerName != UserPreferences.currentProvider?.name) {
            Provider.providers.keys.find { it.name == tvShow.providerName }?.let {
                UserPreferences.currentProvider = it
            }
        }
        action()
    }

    /** 2026-06-20 (user "le film Titanic est traité comme une série et ouvre
     *  une page synopsis Il devrait partir instantanément") : pour les replay
     *  TV Hub (id `livehub::replay::*`), consulter le ReplayFavoritesStore qui
     *  est la source de vérité pour `isMovie`. Si l'item est un favori connu
     *  comme film → lancer le player direct (= openReplayFavorite). Retourne
     *  true si la navigation a été gérée. */
    private fun routeReplayFavoriteIfMovie(navController: NavController): Boolean {
        if (!tvShow.id.startsWith("livehub::replay::")) return false
        val e = com.streamflixreborn.streamflix.utils.ReplayFavoritesStore
            .all().firstOrNull { it.id == tvShow.id } ?: return false
        if (!e.isMovie && !tvShow.isMovie) return false
        // Direct play en passant par les TvShow stockées (= toutes les data
        //   nécessaires sont dans `tvShow` côté ViewHolder).
        try {
            // 2026-06-20 : ne set currentProvider que si différent (évite
            //   AppDatabase.resetInstance + cascade notifier inutiles).
            val current = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
            val alreadyOnHub = current?.name == "TV Hub" || current?.name == "World Live"
            if (!alreadyOnHub) {
                com.streamflixreborn.streamflix.providers.Provider.findByName("TV Hub")?.let {
                    com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider = it
                }
            }
            handleDirectPlay(navController)
        } catch (_: Throwable) {}
        return true
    }

    private fun handleDirectPlay(navController: NavController) {
        // Debounce rapid clicks to prevent double-navigation crash
        val now = System.currentTimeMillis()
        if (now - lastClickTime < clickDebounceMs) {
            Log.d("TvShowViewHolder", "Click debounced for ${tvShow.title}")
            return
        }
        lastClickTime = now

        // 2026-05-14 (user "j'arrive pas à lire mes épisodes de série") :
        // BYPASS du mini player pour les épisodes série (myiptv-ep::). Le mini
        // player prend channel.url qui est VIDE pour les séries Stalker → fail
        // silencieux + skip getVideo() → ExoPlayer crash "No extractors for URL: ".
        // Seules les chaînes live IPTV doivent passer par le mini player.
        // 2026-06-08 (user "j'aimerais que le mini lecteur revienne pour les
        //   chaînes TV normales") : France TV + Dric4rTV RETIRÉS du bypass.
        //   Le mini lecteur reprend du service, le re-clic transitionne en
        //   fullscreen via transferPlayer() (handoff propre — pas de re-create
        //   ExoPlayer). Stalker épisodes/films restent au bypass (URL vide).
        val bypassMiniPlayer = tvShow.id.startsWith("myiptv-ep::") ||
            tvShow.id.startsWith("myiptv-movie::") ||
            tvShow.id.startsWith("myiptv-ep-stk::")
        val interceptor = MiniPlayerController.onIptvChannelClick
        Log.d("TvShowViewHolder", "handleDirectPlay: interceptor=${if (interceptor != null) "SET" else "NULL"}, bypassMiniPlayer=$bypassMiniPlayer, tvShow=${tvShow.title}")
        if (!bypassMiniPlayer && interceptor != null && interceptor(tvShow)) {
            return // interceptor handled the click (playing in mini player)
        }
        // 2026-06-08 (user "pour les radios on est obligé d'ouvrir le lecteur
        //   en vrai juste pour avoir le son") : une chaîne RADIO ne s'ouvre
        //   JAMAIS en fullscreen. Si on est ici, l'interceptor a renvoyé false
        //   (= même chaîne re-cliquée, mini déjà actif). On laisse le mini
        //   tourner et on stoppe net le flow fullscreen. Clear le flag de
        //   transition pour ne pas laisser une trace orpheline.
        if (MiniPlayerController.isRadioChannel(tvShow.id)) {
            Log.d("TvShowViewHolder", "Radio channel — pas de fullscreen (mini-bar audio uniquement): ${tvShow.title}")
            try { MiniPlayerController.clearTransitionFlag() } catch (_: Exception) {}
            return
        }
        // 2026-05-31 : si l'interceptor est null (le provider courant n'est pas IPTV,
        // donc le mini player n'a pas été initialisé) mais qu'on clique sur une chaîne
        // IPTV (TV Hub, OLA, etc.), le flow tombe dans le fullscreen player ci-dessous.
        // MiniPlayerController.resolveIptvProvider résout le bon provider depuis l'ID.
        // Interceptor returned false or not set OR isStalkerEpisode → navigate to full player

        // If mini player is active, flag transition so onPause doesn't release it.
        // PlayerTvFragment.onViewCreated will call transferPlayer() to reuse it.
        // 2026-06-08 (user "ça lance la mauvaise chaîne / plus aucune chaîne marche") :
        //   si bypassMiniPlayer=true (Stalker / France TV), il NE FAUT PAS
        //   reprendre le mini-player dans le fullscreen — ni son flag (qui peut
        //   avoir été setté par un clic précédent), ni son ExoPlayer (qui peut
        //   jouer une autre chaîne). On FORCE le reset du flag de transition +
        //   STOP du mini s'il était actif. PlayerTvFragment initialisera alors
        //   un nouveau player propre via viewModel.getVideo() pour notre item.
        if (bypassMiniPlayer) {
            MiniPlayerController.transitioningToFullscreen = false
            try {
                if (MiniPlayerController.isPlaying()) {
                    MiniPlayerController.stopAsync()
                }
            } catch (_: Exception) {}
        } else if (MiniPlayerController.isPlaying()) {
            MiniPlayerController.transitioningToFullscreen = true
        }

        val videoType = Video.Type.Episode(
            id = tvShow.id,
            number = 1,
            title = tvShow.title,
            poster = tvShow.poster,
            overview = tvShow.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = tvShow.id,
                title = tvShow.title,
                poster = tvShow.poster,
                banner = tvShow.banner,
                releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                imdbId = tvShow.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = 1,
                title = "Live",
            ),
        )

        val args = Bundle().apply {
            putString("id", tvShow.id)
            putString("title", tvShow.title)
            putString("subtitle", tvShow.title)
            putSerializable("videoType", videoType)
        }
        try {
            navController.navigate(R.id.action_global_player, args)
        } catch (e: Exception) {
            Log.e("TvShowViewHolder", "navigate to player failed: ${e.message}")
        }
    }

    private fun tvShowArgs(): Bundle {
        return Bundle().apply {
            putString("id", tvShow.id)
            putString("poster", tvShow.poster)
            putString("banner", tvShow.banner)
        }
    }

    /** 2026-06-19 (user "quand on clique sur un dossier qu'on clique sur un
     *  épisode, une fois qu'on revient sur le home le dossier s'est transformé
     *  en l'épisode") : navigue VERS la chaîne/programme sélectionné dans le
     *  dialog d'un dossier TV Hub, SANS modifier tvShow (= sinon le RecyclerView
     *  re-bind la card du dossier avec l'id du programme).
     *
     *  Distingue :
     *  - Chaînes Live (livehub::replay::tf1live/m6live, et toutes les autres
     *    chaînes IPTV WiTV/OTF/Adrar) → mini-player via MiniPlayerController.
     *  - Programmes Replay VOD (Arte, France TV, etc.) → fiche tv_show pour
     *    afficher saisons/épisodes. */
    private fun navigateFromFolderSelection(root: android.view.View, selected: TvShow) {
        val isReplayVod = selected.id.startsWith("livehub::replay::") &&
            !selected.id.startsWith("livehub::replay::tf1live::") &&
            !selected.id.startsWith("livehub::replay::m6live::") &&
            !selected.id.startsWith("livehub::replay::__")
        // 2026-06-23 v2 (user "la barre est censée se fermer directement
        //   quand on clique sur une série pas un film — film on regarde dans
        //   le mini-player, série on passe par la synopsis") : le dismiss
        //   est appliqué CONDITIONNELLEMENT au moment de navigate vers la
        //   fiche série (= hasEpisodeBrowsing plus bas). Pour les films on
        //   garde le dialog ouvert car ils jouent dans le mini-player.
        // 2026-06-20 v8 : findNavController via Activity (R.id.nav_main_fragment)
        //   car root.findNavController() échoue silencieusement quand appelé
        //   depuis le callback d'un AlertDialog (le root n'est plus dans la
        //   hiérarchie NavHost à ce moment-là).
        val navController: NavController? = try {
            val activity = (root.context as? android.app.Activity)
                ?: ((root.context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity)
            if (activity != null) {
                androidx.navigation.Navigation.findNavController(activity, R.id.nav_main_fragment)
            } else {
                root.findNavController()
            }
        } catch (_: Throwable) {
            try { root.findNavController() } catch (_: Throwable) { null }
        }
        if (navController == null) {
            Log.e("TvShowViewHolder", "navigateFromFolderSelection: NavController introuvable !")
            return
        }
        // 2026-06-24 (user "Samsung TV Plus s'ouvre plein écran sans drag
        //   handle") : si selected est un folder World Live TV (= id
        //   contient "::folder::"), on rouvre LiveHubFolderDialog au lieu
        //   de naviger vers TvShowMobileFragment. Le dialog a la barre ≡
        //   et se règle comme les autres dossiers.
        val originalWlId = selected.id.removePrefix("livehub::worldlivetv::")
        if (originalWlId.contains("::folder::")) {
            val withoutPrefix = originalWlId.removePrefix("wltv::")
            val parts = withoutPrefix.split("::folder::")
            if (parts.size == 2) {
                val groupSlug = parts[0]
                val subAndIdx = parts[1]
                val lastDash = subAndIdx.lastIndexOf('-')
                if (lastDash >= 0) {
                    val subSlug = subAndIdx.substring(0, lastDash)
                    val idx = subAndIdx.substring(lastDash + 1)
                    val folderPath = "$groupSlug/$subSlug/$idx"
                    val key = "wlsub_$folderPath"
                    com.streamflixreborn.streamflix.providers.LiveHubFolderDialog.show(
                        root.context, key, selected.title,
                    ) { sub -> navigateFromFolderSelection(root, sub) }
                    return
                }
            }
        }
        if (isReplayVod) {
            // 2026-06-20 (user "le home se met en chargement infini après BACK
            //   depuis synopsis Zodiaque") : ne PAS set currentProvider si déjà
            //   sur TV Hub / World Live (= les 2 partagent le pipeline replay).
            //   Sans cette garde, AppDatabase.resetInstance + notifyProviderChanged
            //   se déclenchaient en parallèle de la navigation vers la synopsis
            //   → race condition → home stuck en Loading après BACK.
            val currentProvider = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
            val alreadyOnHubProvider = currentProvider?.name == "TV Hub" ||
                currentProvider?.name == "World Live"
            if (!alreadyOnHubProvider) {
                com.streamflixreborn.streamflix.providers.Provider.findByName("TV Hub")?.let {
                    com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider = it
                }
            }
            // 2026-06-20 v8 : francetv / arte = vidéos unitaires (pas de saisons/
            //   épisodes à browser) → lecture directe TOUJOURS, même si isMovie=false.
            //   Seuls les programmes TF1+ et M6+ ont un vrai browsing épisodes via
            //   buildTf1ReplayShow / buildM6ReplayShow.
            val siId = selected.id.removePrefix("livehub::replay::")
            val tf1Prefixes = listOf("tf1/", "tmc/", "tfx/", "tf1-series-films/", "lci/",
                "tf1ep::", "tf1plus::")
            val m6Prefixes = listOf("m6replay/", "w9replay/", "6terreplay/",
                "gullireplay/", "m6ep::", "m6play::")
            // 2026-06-26 : France TV programmes (séries / dessins animés Okoo) =
            //   id "livehub::replay::program/<path>" → browsing épisodes via
            //   buildFrancetvReplayShow (/apps/program/<path>).
            val francetvPrefixes = listOf("program/", "plexshow::", "plutoshow::")
            // 2026-06-21 : BFM Play replay = vidéos unitaires (chaque entrée
            //   M3U est déjà un épisode, pas de saisons à browser comme TF1/M6)
            //   → lecture directe TOUJOURS.
            val hasEpisodeBrowsing = !selected.isMovie &&
                (tf1Prefixes.any { siId.startsWith(it) } ||
                 m6Prefixes.any { siId.startsWith(it) } ||
                 francetvPrefixes.any { siId.startsWith(it) })
            if (hasEpisodeBrowsing) {
                // TF1+/M6+/BFM séries → fiche synopsis avec saisons/épisodes
                Log.d("TvShowViewHolder", "navigateFromFolderSelection: série TF1+/M6+/BFM → synopsis (${selected.id})")
                // 2026-06-23 (user "la barre est censée se fermer directement
                //   quand on clique sur une série") : ferme tous les dialogs
                //   AVANT la navigation pour que la fiche synopsis s'affiche
                //   au premier plan. Films restent dans le mini → pas de dismiss.
                try {
                    com.streamflixreborn.streamflix.providers.LiveHubFolderDialog.dismissAllPublic()
                } catch (_: Throwable) {}
                // 2026-06-23 (user "quand on clique sur l'épisode de la série
                //   ça ne remplace pas la lecture déjà en cours dans le mini
                //   Player") : stop le mini-player AVANT de naviguer vers la
                //   fiche série. Comme ça la chaîne live s'arrête et l'épisode
                //   peut prendre sa place sans conflit de Player.
                try {
                    MiniPlayerController.stop()
                } catch (_: Throwable) {}
                val args = Bundle().apply {
                    putString("id", selected.id)
                    putString("poster", selected.poster)
                    putString("banner", selected.banner)
                }
                try {
                    navController.navigate(R.id.action_global_tv_show, args)
                } catch (e: Exception) {
                    Log.e("TvShowViewHolder",
                        "navigateFromFolderSelection série FAIL: ${e.message}", e)
                }
            } else {
                // 2026-06-22 (user "films/téléfilms replay dans le mini lecteur
                //   au lieu du grand lecteur — ça permet de voir s'il fonctionne")
                //   Film OU vidéo unitaire (francetv/arte) → mini-player
                Log.d("TvShowViewHolder", "navigateFromFolderSelection: replay film → mini-player (${selected.id}, isMovie=${selected.isMovie})")
                val interceptor = MiniPlayerController.onIptvChannelClick
                if (interceptor != null && interceptor(selected)) {
                    return  // handled by mini player
                }
                // Fallback si mini player indisponible → lecture directe fullscreen
                val title = selected.title.ifBlank { "Replay" }
                val videoType = com.streamflixreborn.streamflix.models.Video.Type.Movie(
                    id = selected.id,
                    title = title,
                    releaseDate = "",
                    poster = selected.poster ?: "",
                    imdbId = null,
                )
                val args = Bundle().apply {
                    putString("id", selected.id)
                    putString("title", title)
                    putString("subtitle", title)
                    putSerializable("videoType", videoType)
                }
                try {
                    navController.navigate(R.id.action_global_player, args)
                } catch (e: Exception) {
                    Log.e("TvShowViewHolder",
                        "navigateFromFolderSelection film FAIL: ${e.message}", e)
                }
            }
        } else {
            // Chaîne Live → mini-player (= comportement normal IPTV)
            val interceptor = MiniPlayerController.onIptvChannelClick
            if (interceptor != null && interceptor(selected)) {
                return
            }
            val title = selected.title.ifBlank { "Live" }
            val videoType = com.streamflixreborn.streamflix.models.Video.Type.Movie(
                id = selected.id,
                title = title,
                releaseDate = "",
                poster = selected.poster ?: "",
                imdbId = null,
            )
            val args = Bundle().apply {
                putString("id", selected.id)
                putString("title", title)
                putString("subtitle", title)
                putSerializable("videoType", videoType)
            }
            try {
                navController.navigate(R.id.action_global_player, args)
            } catch (e: Exception) {
                Log.e("TvShowViewHolder",
                    "navigateFromFolderSelection live FAIL: ${e.message}", e)
            }
        }
    }

    private fun resolveEpisodeSeason(episode: Episode?): Season? {
        if (episode == null) return null

        val currentSeason = episode.season
        val seasonKey = episode.id.substringBeforeLast("/", "")
            .takeIf { it.isNotBlank() }
        if (currentSeason != null && currentSeason.number != 0) {
            return currentSeason
        }

        return tvShow.seasons.firstOrNull { season ->
            season.id == seasonKey ||
                season.id == currentSeason?.id ||
                season.episodes.any { it.id == episode.id } ||
                (episode.number != 0 && season.episodes.any { it.number == episode.number && it.title == episode.title })
        } ?: currentSeason
    }

    private fun setPoster(imageView: ImageView) {
        if (isIptvProvider()) {
            // IPTV channel logos are rectangular and don't fill a 2:3 portrait card.
            // FIT_CENTER preserves the aspect ratio without cropping; leave background
            // transparent so the natural card background shows around the logo (no
            // double-frame "gray square" artefact from our previous solid bg).
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            imageView.setBackgroundColor(0x00000000)
            imageView.setPadding(0, 0, 0, 0)
        } else {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.setBackgroundColor(0x00000000)
            imageView.setPadding(0, 0, 0, 0)
        }
        imageView.loadTvShowPoster(tvShow) {
            if (isIptvProvider()) {
                // POSTER_LIST_OPTIONS in ArtworkLoader.kt sets placeholder + error to
                // the gray "glide_fallback_cover" drawable. For IPTV logos (which are
                // transparent PNGs sitting on the channel card) the gray drawable
                // bleeds through and shows behind the logo. Wipe BOTH via a fresh
                // RequestOptions applied AFTER POSTER_LIST_OPTIONS — chained
                // .placeholder() alone won't reset it because the underlying
                // RequestOptions has been merged already.
                val transparentDrawable = android.graphics.drawable.ColorDrawable(0)
                // 2026-06-12 — Placeholder universel : peu importe la playlist
                // (World Live / 3boxTv / Dric4r / Fire TV / Cineverse / etc.), si
                // le logo est absent (404, URL vide, host mort), on dessine le
                // nom de la chaîne sur fond SEMI-TRANSPARENT pour laisser passer
                // le card backdrop derrière. Plus de tuiles vides.
                val placeholder = com.streamflixreborn.streamflix.utils
                    .ChannelPlaceholderDrawable(tvShow.title)
                apply(
                    com.bumptech.glide.request.RequestOptions()
                        .placeholder(transparentDrawable)
                        .fallback(placeholder)
                        .error(placeholder)
                )
                // For OlaTv we also have a curated fallback URL (initials avatar).
                if (tvShow.providerName == "OLA TV") {
                    val fallbackUrl = com.streamflixreborn.streamflix.providers.OlaTvProvider
                        .fallbackLogoUrlFor(tvShow.title)
                    error(
                        com.bumptech.glide.Glide.with(imageView.context)
                            .load(fallbackUrl)
                            .apply(
                                com.bumptech.glide.request.RequestOptions()
                                    .placeholder(transparentDrawable)
                                    .fallback(placeholder)
                                    .error(placeholder)
                            )
                    )
                }
                // 2026-05-11 (Option C) : pour Vavoo, fallback ui-avatars quand
                // l'URL primaire 404 (typiquement les logos cassés renvoyés par
                // l'API Vavoo). Plus de tuile noire — initiales coloriées par
                // catégorie pour rester lisible.
                if (tvShow.providerName == "Vavoo TV") {
                    val fallbackUrl = com.streamflixreborn.streamflix.providers.VavooProvider
                        .fallbackLogoUrlFor(tvShow.title)
                    error(
                        com.bumptech.glide.Glide.with(imageView.context)
                            .load(fallbackUrl)
                            .apply(
                                com.bumptech.glide.request.RequestOptions()
                                    .placeholder(transparentDrawable)
                                    .fallback(placeholder)
                                    .error(placeholder)
                            )
                    )
                }
                // 2026-05-17 : 3BoxTV/BoxXtemus retiré du projet — bloc de
                // fallback logo supprimé.
            } else {
                fallback(R.drawable.glide_fallback_cover)
            }
            transition(DrawableTransitionOptions.withCrossFade())
        }
    }

    private fun displayMobileItem(binding: ItemTvShowMobileBinding) {
        binding.root.setOnClickListener {
            // 2026-06-19 (user "fais un dossier pour eux ils cliquent et dedans
            //   il y a OTF qui apparaît") : card dossier TV Hub → ouvre dialog
            //   au lieu d'aller au player.
            if (tvShow.id.startsWith("livehub::folder::")) {
                val key = tvShow.id.removePrefix("livehub::folder::")
                val label = tvShow.title.removePrefix("📁 ").substringBefore(" (")
                com.streamflixreborn.streamflix.providers.LiveHubFolderDialog.show(
                    context, key, label
                ) { selected ->
                    // 2026-06-19 (user "quand on clique sur un dossier puis
                    //   épisode, le dossier s'est transformé en l'épisode") :
                    //   NE PAS modifier tvShow (= sinon RecyclerView re-bind
                    //   la card avec l'id du programme). Navigation directe
                    //   avec un Bundle construit à partir du selected.
                    navigateFromFolderSelection(binding.root, selected)
                }
                return@setOnClickListener
            }
            // 2026-05-25 : guards pour items synthétiques du cœur (favoris saison/épisode, reprises)
            if (tvShow.id.startsWith(com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.SYNTHETIC_ID_PREFIX)) {
                openReplayFavorite(binding.root.findNavController()); return@setOnClickListener
            }
            if (tvShow.id.startsWith(com.streamflixreborn.streamflix.utils.SeasonFavorites.SYNTHETIC_ID_PREFIX)) {
                openSeasonFavorite(binding.root.findNavController()); return@setOnClickListener
            }
            if (tvShow.id.startsWith(com.streamflixreborn.streamflix.utils.EpisodeFavorites.SYNTHETIC_ID_PREFIX)) {
                openEpisodeFavorite(binding.root.findNavController()); return@setOnClickListener
            }
            if (tvShow.id.startsWith("resume_series_")) {
                openResumeSeries(binding.root.findNavController()); return@setOnClickListener
            }
            // 2026-06-20 (user "Titanic ouvre une synopsis") : replay favori film → direct.
            if (routeReplayFavoriteIfMovie(binding.root.findNavController())) return@setOnClickListener
            // 2026-06-18 (user "Le système de connexion à côté du nom de la
            //   Playlist") : card synthétique "Se connecter à TF1/M6" → lance
            //   LoginWebViewActivity au lieu du player.
            if (tvShow.id.startsWith("livehub::replay::__login_")) {
                val svc = tvShow.id.removePrefix("livehub::replay::__login_").removeSuffix("__")
                when (svc.lowercase()) {
                    "tf1" -> {
                        // 2026-06-18 : TF1+ utilise form natif (= pipeline Gigya
                        //   nécessite email+password explicites, WebView ne sait
                        //   pas extraire le JWT depuis localStorage).
                        com.streamflixreborn.streamflix.activities.TF1LoginDialog.show(context)
                    }
                    "bfm" -> {
                        // 2026-06-22 (user "à chaque fois la reconnexion nous
                        //   ramène à la page WebView au lieu de réutiliser
                        //   le mot de passe sauvé") :
                        //   Si credentials déjà sauvés → relogin SILENT direct
                        //   via BfmSsoAuth.reloginFromSaved (= comme TF1 fait).
                        //   Sinon → BfmLoginDialog (form natif + saveCredentials).
                        //   Ancien code lançait LoginWebViewActivity à chaque
                        //   fois → form WebView à re-remplir manuellement.
                        val ctxRef = context
                        if (com.streamflixreborn.streamflix.utils.BfmSsoAuth.hasCredentials(ctxRef)) {
                            android.widget.Toast.makeText(ctxRef,
                                "Reconnexion BFM Play…",
                                android.widget.Toast.LENGTH_SHORT).show()
                            kotlinx.coroutines.CoroutineScope(
                                kotlinx.coroutines.Dispatchers.IO +
                                kotlinx.coroutines.SupervisorJob()
                            ).launch {
                                val token = try {
                                    com.streamflixreborn.streamflix.utils.BfmSsoAuth.reloginFromSaved(ctxRef)
                                } catch (_: Throwable) { null }
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    if (token != null) {
                                        android.widget.Toast.makeText(ctxRef,
                                            "✓ Reconnecté à BFM Play",
                                            android.widget.Toast.LENGTH_SHORT).show()
                                        try {
                                            com.streamflixreborn.streamflix.utils.UserPreferences
                                                .currentProvider?.let { p ->
                                                com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(ctxRef, p)
                                            }
                                            com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
                                                .notifyProviderChanged()
                                        } catch (_: Throwable) {}
                                    } else {
                                        // Relogin silent échoué → ouvre le dialog
                                        //   pour permettre à l'user de re-saisir
                                        //   ou de vérifier ses credentials.
                                        com.streamflixreborn.streamflix.activities
                                            .BfmLoginDialog.show(ctxRef)
                                    }
                                }
                            }
                        } else {
                            // Pas de credentials → dialog natif (= comme TF1).
                            com.streamflixreborn.streamflix.activities.BfmLoginDialog.show(ctxRef)
                        }
                    }
                    else -> {
                        com.streamflixreborn.streamflix.activities.LoginWebViewActivity.start(
                            context,
                            com.streamflixreborn.streamflix.activities.LoginWebViewActivity.SERVICE_M6
                        )
                    }
                }
                return@setOnClickListener
            }
            // 2026-06-18 (user "on peut pas mettre un bouton pour ça Pour
            //   forcer le Refresh") : card "🔄 Actualiser" → vide les caches
            //   replay et re-fetch immédiatement le M3U depuis GitHub.
            if (tvShow.id == "livehub::replay::__refresh__") {
                val ctxRef = context
                android.widget.Toast.makeText(ctxRef,
                    "Rafraîchissement du TV Hub…",
                    android.widget.Toast.LENGTH_SHORT).show()
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.Dispatchers.IO +
                    kotlinx.coroutines.SupervisorJob()
                ).launch {
                    val n = try {
                        com.streamflixreborn.streamflix.providers.LiveTvHubProvider
                            .forceRefreshReplay()
                    } catch (_: Throwable) { 0 }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(ctxRef,
                            "TV Hub : $n catégories chargées",
                            android.widget.Toast.LENGTH_SHORT).show()
                        try {
                            com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(ctxRef,
                                com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider ?: return@withContext)
                        } catch (_: Throwable) {}
                        try {
                            com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
                                .notifyProviderChanged()
                        } catch (_: Throwable) {}
                    }
                }
                return@setOnClickListener
            }
            checkProviderAndRun {
                if (context.toActivity()?.getCurrentFragment() is com.streamflixreborn.streamflix.fragments.global_favorites.GlobalFavoritesMobileFragment) {
                    com.streamflixreborn.streamflix.utils.GlobalFavorites.switchToOrigin(tvShow.id)
                }
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                }
            }
        }
        binding.root.setOnLongClickListener {
            // 2026-05-25 : retirer favori depuis le cœur (mobile)
            val cf = context.toActivity()?.getCurrentFragment()
            // 2026-06-27 (user "favori replay on peut pas les retirer") : un favori
            //   replay/fast (TV Hub) affiché AILLEURS que les Cœurs (home, dossier)
            //   a son id RÉEL → l'appui long propose de le retirer (toggle off).
            //   Dans les Cœurs l'id est synthétique (replayfav::) → isFavorite=false.
            if (com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.isFavorite(tvShow.id)) {
                showFavoriteLongPressDialog(context, tvShow.title, onRemove = {
                    com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.toggle(
                        tvShow.id, tvShow.title, tvShow.poster, tvShow.banner, tvShow.isMovie)
                    android.widget.Toast.makeText(context, "Retiré des favoris", android.widget.Toast.LENGTH_SHORT).show()
                }, onDownload = null)
                return@setOnLongClickListener true
            }
            if (cf is com.streamflixreborn.streamflix.fragments.global_favorites.GlobalFavoritesMobileFragment) {
                showFavoriteLongPressDialog(context, tvShow.title, onRemove = {
                    cf.removeFavorite(tvShow.id, false)
                }, onDownload = null)
            // 2026-05-31 : retirer favori IPTV depuis l'onglet Favoris IPTV
            } else if (cf is com.streamflixreborn.streamflix.fragments.iptv_favorites.IptvFavoritesMobileFragment) {
                showFavoriteLongPressDialog(context, tvShow.title, onRemove = {
                    val provName = tvShow.providerName ?: UserPreferences.currentProvider?.name ?: "TV Hub"
                    com.streamflixreborn.streamflix.utils.IptvFavoritesStore.toggle(provName, tvShow.id)
                    cf.loadFavorites()
                }, onDownload = null)
            } else if (!handleIptvFavoriteLongPress()) {
                ShowOptionsMobileDialog(context, tvShow).show()
            }
            true
        }
        setPoster(binding.ivTvShowPoster)
        applyIptvLogoStyle(binding.ivTvShowPoster)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        // 2026-06-19 (user "bug de confusion Entre les séries et les films Sur toute notre liste") :
        //   pour les programmes Replay (= téléfilms, émissions one-off,
        //   documentaires single-program), ni épisodes ni année → fallback
        //   sur "Série" alors que c'est PAS une série. Fix : ne PAS afficher
        //   le label "Série"/"Film" par défaut. On affiche juste l'année si
        //   dispo, sinon rien.
        binding.tvTvShowLastEpisode.text = when {
            isIptvProvider() -> ""
            tvShow.id.startsWith("livehub::replay::") -> ""
            else -> tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()
                ?.let { "E${it.number}" }
                ?: tvShow.released?.format("yyyy")
                ?: context.getString(if (tvShow.isMovie) R.string.movie_item_type else R.string.tv_show_item_type)
        }
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun displayTvItem(binding: ItemTvShowTvBinding) {
        binding.root.apply {
            setOnClickListener {
                // 2026-06-19 (user "fais un dossier pour eux ils cliquent et
                //   dedans il y a OTF qui apparaît") : card dossier TV Hub →
                //   ouvre dialog au lieu d'aller au player.
                if (tvShow.id.startsWith("livehub::folder::")) {
                    val key = tvShow.id.removePrefix("livehub::folder::")
                    val label = tvShow.title.removePrefix("📁 ").substringBefore(" (")
                    com.streamflixreborn.streamflix.providers.LiveHubFolderDialog.show(
                        context, key, label
                    ) { selected ->
                        navigateFromFolderSelection(binding.root, selected)
                    }
                    return@setOnClickListener
                }
                // 2026-06-18 (user "système de connexion") : card login synthétique
                if (tvShow.id.startsWith("livehub::replay::__login_")) {
                    val svc = tvShow.id.removePrefix("livehub::replay::__login_").removeSuffix("__")
                    // 2026-06-22 (user "quand on clique sur reconnexion, ça
                    //   nous reconnecte vraiment, pas aller sur la page") :
                    //   pour BFM, si credentials sauvés → relogin SILENT
                    //   direct. Pour TF1 → TF1LoginDialog (form natif).
                    //   Sinon WebView OIDC.
                    when (svc.lowercase()) {
                        "tf1" -> com.streamflixreborn.streamflix.activities.TF1LoginDialog.show(context)
                        "bfm" -> {
                            val ctxRef = context
                            if (com.streamflixreborn.streamflix.utils.BfmSsoAuth.hasCredentials(ctxRef)) {
                                android.widget.Toast.makeText(ctxRef,
                                    "Reconnexion BFM Play…",
                                    android.widget.Toast.LENGTH_SHORT).show()
                                kotlinx.coroutines.CoroutineScope(
                                    kotlinx.coroutines.Dispatchers.IO +
                                    kotlinx.coroutines.SupervisorJob()
                                ).launch {
                                    val token = try {
                                        com.streamflixreborn.streamflix.utils.BfmSsoAuth.reloginFromSaved(ctxRef)
                                    } catch (_: Throwable) { null }
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        if (token != null) {
                                            android.widget.Toast.makeText(ctxRef,
                                                "✓ Reconnecté à BFM Play",
                                                android.widget.Toast.LENGTH_SHORT).show()
                                            try {
                                                com.streamflixreborn.streamflix.utils.UserPreferences
                                                    .currentProvider?.let { p ->
                                                    com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(ctxRef, p)
                                                }
                                                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
                                                    .notifyProviderChanged()
                                            } catch (_: Throwable) {}
                                        } else {
                                            com.streamflixreborn.streamflix.activities.BfmLoginDialog.show(ctxRef)
                                        }
                                    }
                                }
                            } else {
                                com.streamflixreborn.streamflix.activities.BfmLoginDialog.show(context)
                            }
                        }
                        else -> com.streamflixreborn.streamflix.activities.LoginWebViewActivity.start(
                            context,
                            com.streamflixreborn.streamflix.activities.LoginWebViewActivity.SERVICE_M6
                        )
                    }
                    return@setOnClickListener
                }
                // 2026-06-18 : card refresh TV Hub
                if (tvShow.id == "livehub::replay::__refresh__") {
                    val ctxRef = context
                    android.widget.Toast.makeText(ctxRef,
                        "Rafraîchissement du TV Hub…",
                        android.widget.Toast.LENGTH_SHORT).show()
                    kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.Dispatchers.IO +
                        kotlinx.coroutines.SupervisorJob()
                    ).launch {
                        val n = try {
                            com.streamflixreborn.streamflix.providers.LiveTvHubProvider
                                .forceRefreshReplay()
                        } catch (_: Throwable) { 0 }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(ctxRef,
                                "TV Hub : $n catégories chargées",
                                android.widget.Toast.LENGTH_SHORT).show()
                            try {
                                com.streamflixreborn.streamflix.utils.HomeCacheStore.clear(ctxRef,
                                    com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider ?: return@withContext)
                            } catch (_: Throwable) {}
                            try {
                                com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
                                    .notifyProviderChanged()
                            } catch (_: Throwable) {}
                        }
                    }
                    return@setOnClickListener
                }
                // 2026-05-24 : guards pour items synthétiques du cœur (favoris saison/épisode, reprises)
                if (tvShow.id.startsWith(com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.SYNTHETIC_ID_PREFIX)) {
                    openReplayFavorite(findNavController()); return@setOnClickListener
                }
                if (tvShow.id.startsWith(com.streamflixreborn.streamflix.utils.SeasonFavorites.SYNTHETIC_ID_PREFIX)) {
                    openSeasonFavorite(findNavController()); return@setOnClickListener
                }
                if (tvShow.id.startsWith(com.streamflixreborn.streamflix.utils.EpisodeFavorites.SYNTHETIC_ID_PREFIX)) {
                    openEpisodeFavorite(findNavController()); return@setOnClickListener
                }
                if (tvShow.id.startsWith("resume_series_")) {
                    openResumeSeries(findNavController()); return@setOnClickListener
                }
                checkProviderAndRun {
                    if (context.toActivity()?.getCurrentFragment() is com.streamflixreborn.streamflix.fragments.global_favorites.GlobalFavoritesTvFragment) {
                        com.streamflixreborn.streamflix.utils.GlobalFavorites.switchToOrigin(tvShow.id)
                    }
                    if (isIptvProvider()) {
                        handleDirectPlay(findNavController())
                    } else {
                        findNavController().navigate(R.id.tv_show, tvShowArgs())
                    }
                }
            }
            setOnLongClickListener {
                val cf = context.toActivity()?.getCurrentFragment()
                // 2026-06-27 : favori replay/fast affiché hors Cœurs → retrait par appui long.
                if (com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.isFavorite(tvShow.id)) {
                    com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.toggle(
                        tvShow.id, tvShow.title, tvShow.poster, tvShow.banner, tvShow.isMovie)
                    android.widget.Toast.makeText(context, "Retiré des favoris", android.widget.Toast.LENGTH_SHORT).show()
                } else if (cf is com.streamflixreborn.streamflix.fragments.global_favorites.GlobalFavoritesTvFragment) {
                    cf.removeFavorite(tvShow.id, false)
                } else if (!handleIptvFavoriteLongPress()) {
                    ShowOptionsTvDialog(context, tvShow).show()
                }
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = if (hasFocus) AnimationUtils.loadAnimation(context, R.anim.zoom_in) else AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                startAnimation(animation)
                animation.fillAfter = true
                // Fond d'écran carrousel uniquement — plus de pinBackground par item
                // pour économiser la mémoire (le carrousel gère le fond)
            }
        }
        setPoster(binding.ivTvShowPoster)
        applyIptvLogoStyle(binding.ivTvShowPoster)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        // 2026-06-19 (user "bug de confusion Entre les séries et les films Sur toute notre liste") :
        //   pour les programmes Replay (= téléfilms, émissions one-off,
        //   documentaires single-program), ni épisodes ni année → fallback
        //   sur "Série" alors que c'est PAS une série. Fix : ne PAS afficher
        //   le label "Série"/"Film" par défaut. On affiche juste l'année si
        //   dispo, sinon rien.
        binding.tvTvShowLastEpisode.text = when {
            isIptvProvider() -> ""
            tvShow.id.startsWith("livehub::replay::") -> ""
            else -> tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()
                ?.let { "E${it.number}" }
                ?: tvShow.released?.format("yyyy")
                ?: context.getString(if (tvShow.isMovie) R.string.movie_item_type else R.string.tv_show_item_type)
        }
        binding.tvTvShowTitle.text = tvShow.title
    }

    /** 2026-05-21 : ouvre une saison favorite (carte synthétique du cœur) sur sa
     *  liste d'épisodes, dans le provider d'origine. La fiche saison affiche la
     *  progression par épisode → l'user reprend là où il en était. */
    private fun openSeasonFavorite(navController: androidx.navigation.NavController) {
        val e = com.streamflixreborn.streamflix.utils.SeasonFavorites.findBySyntheticId(tvShow.id) ?: return
        com.streamflixreborn.streamflix.providers.Provider.findByName(e.provider)?.let {
            com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider = it
        }
        val args = android.os.Bundle().apply {
            putString("tvShowId", e.showId)
            putString("tvShowTitle", e.showTitle)
            putString("tvShowPoster", e.showPoster)
            putString("tvShowBanner", e.showBanner)
            putString("seasonId", e.seasonId)
            putInt("seasonNumber", e.seasonNumber)
            putString("seasonTitle", e.seasonTitle)
        }
        try { navController.navigate(R.id.season, args) } catch (_: Exception) {}
    }

    /** 2026-06-20 : ouvre un favori replay (carte synthétique du cœur).
     *  Films → player direct. Séries → fiche synopsis (= liste saisons/épisodes).
     *  Switch au provider TV Hub avant navigation. */
    private fun openReplayFavorite(navController: androidx.navigation.NavController) {
        val e = com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.findBySyntheticId(tvShow.id) ?: return
        // Switch au provider TV Hub — sauf si on est déjà sur TV Hub / World Live.
        val current = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
        val alreadyOnHub = current?.name == "TV Hub" || current?.name == "World Live"
        if (!alreadyOnHub) {
            com.streamflixreborn.streamflix.providers.Provider.findByName("TV Hub")?.let {
                com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider = it
            }
        }
        if (e.isMovie) {
            // Film → lecture directe
            val videoType = Video.Type.Movie(
                id = e.id,
                title = e.title,
                releaseDate = "",
                poster = e.poster ?: "",
                imdbId = null,
            )
            val args = Bundle().apply {
                putString("id", e.id)
                putString("title", e.title)
                putString("subtitle", e.title)
                putSerializable("videoType", videoType)
            }
            try { navController.navigate(R.id.action_global_player, args) } catch (_: Exception) {}
        } else {
            // Série → synopsis (saisons/épisodes)
            val args = Bundle().apply {
                putString("id", e.id)
                putString("poster", e.poster)
                putString("banner", e.banner)
            }
            try { navController.navigate(R.id.tv_show, args) } catch (_: Exception) {}
        }
    }

    /** 2026-05-24 : ouvre un épisode favori (carte synthétique du cœur)
     *  → navigation DIRECTE au player sur cet épisode. */
    private fun openEpisodeFavorite(navController: androidx.navigation.NavController) {
        val e = com.streamflixreborn.streamflix.utils.EpisodeFavorites.findBySyntheticId(tvShow.id) ?: return
        com.streamflixreborn.streamflix.providers.Provider.findByName(e.provider)?.let {
            com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider = it
        }
        if (com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider == null) return
        val args = Bundle().apply {
            putString("id", e.episodeId)
            putString("title", e.showTitle)
            putString("subtitle", "${if (e.seasonNumber > 0) "S${e.seasonNumber}" else ""}E${e.episodeNumber} — ${e.episodeTitle ?: ""}")
            putSerializable(
                "videoType",
                Video.Type.Episode(
                    id = e.episodeId,
                    number = e.episodeNumber,
                    title = e.episodeTitle,
                    poster = e.episodePoster,
                    overview = null,
                    tvShow = Video.Type.Episode.TvShow(
                        id = e.showId,
                        title = e.showTitle,
                        poster = e.showPoster,
                        banner = e.showBanner,
                        releaseDate = null,
                        imdbId = null,
                    ),
                    season = Video.Type.Episode.Season(
                        number = e.seasonNumber,
                        title = e.seasonTitle,
                    ),
                )
            )
        }
        try { navController.navigate(R.id.action_global_player, args) } catch (_: Exception) {}
    }

    /** 2026-05-24 : ouvre une reprise de lecture série (carte synthétique du cœur)
     *  → navigation DIRECTE au player sur le dernier épisode regardé. */
    private fun openResumeSeries(navController: androidx.navigation.NavController) {
        val origin = com.streamflixreborn.streamflix.utils.GlobalFavorites.originByItemId[tvShow.id]
        if (origin != null) {
            com.streamflixreborn.streamflix.providers.Provider.findByName(origin)?.let {
                com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider = it
            }
        }
        val resumeData = com.streamflixreborn.streamflix.utils.GlobalFavorites.resumeSeriesData[tvShow.id]
        if (resumeData != null) {
            val ep = resumeData.lastEpisode
            val args = Bundle().apply {
                putString("id", ep.id)
                putString("title", resumeData.tvShow.title)
                putString("subtitle", "${if (resumeData.seasonNumber > 0) "S${resumeData.seasonNumber}" else ""}E${resumeData.episodeNumber} — ${ep.title ?: ""}")
                putSerializable(
                    "videoType",
                    Video.Type.Episode(
                        id = ep.id,
                        number = ep.number,
                        title = ep.title,
                        poster = ep.poster,
                        overview = ep.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = resumeData.tvShow.id,
                            title = resumeData.tvShow.title,
                            poster = resumeData.tvShow.poster,
                            banner = resumeData.tvShow.banner,
                            releaseDate = resumeData.tvShow.released?.format("yyyy-MM-dd"),
                            imdbId = resumeData.tvShow.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = resumeData.seasonNumber,
                            title = ep.season?.title,
                        ),
                    )
                )
            }
            try { navController.navigate(R.id.action_global_player, args) } catch (_: Exception) {}
        } else {
            // Fallback : ouvre la fiche série (uniquement si un provider est défini)
            if (com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider == null) return
            val rawId = tvShow.id.removePrefix("resume_series_")
            val args = Bundle().apply {
                putString("id", rawId)
                putString("poster", tvShow.poster)
                putString("banner", tvShow.banner)
            }
            try { navController.navigate(R.id.tv_show, args) } catch (_: Exception) {}
        }
    }

    private fun displayGridMobileItem(binding: ItemTvShowGridMobileBinding) {
        binding.root.setOnClickListener {
            // 2026-06-20 (user "dossiers inutilisables dans Toutes les chaînes") :
            //   les cards dossier TV Hub doivent ouvrir le dialog comme sur le home.
            if (tvShow.id.startsWith("livehub::folder::")) {
                val key = tvShow.id.removePrefix("livehub::folder::")
                val label = tvShow.title.removePrefix("📁 ").substringBefore(" (")
                com.streamflixreborn.streamflix.providers.LiveHubFolderDialog.show(
                    context, key, label
                ) { selected ->
                    navigateFromFolderSelection(binding.root, selected)
                }
                return@setOnClickListener
            }
            if (tvShow.id.startsWith(com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.SYNTHETIC_ID_PREFIX)) {
                openReplayFavorite(binding.root.findNavController()); return@setOnClickListener
            }
            if (tvShow.id.startsWith(com.streamflixreborn.streamflix.utils.SeasonFavorites.SYNTHETIC_ID_PREFIX)) {
                openSeasonFavorite(binding.root.findNavController()); return@setOnClickListener
            }
            // 2026-05-22 : épisode favori → ouvre la saison dans le provider d'origine
            if (tvShow.id.startsWith(com.streamflixreborn.streamflix.utils.EpisodeFavorites.SYNTHETIC_ID_PREFIX)) {
                openEpisodeFavorite(binding.root.findNavController()); return@setOnClickListener
            }
            // 2026-05-22 : reprise de lecture série → ouvre la fiche série dans le provider d'origine
            if (tvShow.id.startsWith("resume_series_")) {
                openResumeSeries(binding.root.findNavController()); return@setOnClickListener
            }
            // 2026-06-20 (user "Titanic ouvre une synopsis") : replay favori film → direct.
            if (routeReplayFavoriteIfMovie(binding.root.findNavController())) return@setOnClickListener
            checkProviderAndRun {
                if (context.toActivity()?.getCurrentFragment() is com.streamflixreborn.streamflix.fragments.global_favorites.GlobalFavoritesMobileFragment) {
                    com.streamflixreborn.streamflix.utils.GlobalFavorites.switchToOrigin(tvShow.id)
                }
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                }
            }
        }
        binding.root.setOnLongClickListener {
            val cf = context.toActivity()?.getCurrentFragment()
            // 2026-06-27 : favori replay/fast affiché hors Cœurs → retrait par appui long.
            if (com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.isFavorite(tvShow.id)) {
                showFavoriteLongPressDialog(context, tvShow.title, onRemove = {
                    com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.toggle(
                        tvShow.id, tvShow.title, tvShow.poster, tvShow.banner, tvShow.isMovie)
                    android.widget.Toast.makeText(context, "Retiré des favoris", android.widget.Toast.LENGTH_SHORT).show()
                }, onDownload = null)
                return@setOnLongClickListener true
            }
            if (cf is com.streamflixreborn.streamflix.fragments.global_favorites.GlobalFavoritesMobileFragment) {
                showFavoriteLongPressDialog(context, tvShow.title, onRemove = {
                    cf.removeFavorite(tvShow.id, false)
                }, onDownload = null)
            } else if (!handleIptvFavoriteLongPress()) {
                ShowOptionsMobileDialog(context, tvShow).show()
            }
            true
        }
        setPoster(binding.ivTvShowPoster)
        applyIptvLogoStyle(binding.ivTvShowPoster)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        // 2026-06-19 (user "bug de confusion Entre les séries et les films Sur toute notre liste") :
        //   pour les programmes Replay (= téléfilms, émissions one-off,
        //   documentaires single-program), ni épisodes ni année → fallback
        //   sur "Série" alors que c'est PAS une série. Fix : ne PAS afficher
        //   le label "Série"/"Film" par défaut. On affiche juste l'année si
        //   dispo, sinon rien.
        binding.tvTvShowLastEpisode.text = when {
            isIptvProvider() -> ""
            tvShow.id.startsWith("livehub::replay::") -> ""
            else -> tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()
                ?.let { "E${it.number}" }
                ?: tvShow.released?.format("yyyy")
                ?: context.getString(if (tvShow.isMovie) R.string.movie_item_type else R.string.tv_show_item_type)
        }
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun displayGridTvItem(binding: ItemTvShowGridBinding) {
        binding.root.apply {
            setOnClickListener {
                // 2026-06-20 (user "dossiers inutilisables dans Toutes les chaînes") :
                //   les cards dossier TV Hub doivent ouvrir le dialog comme sur le home.
                if (tvShow.id.startsWith("livehub::folder::")) {
                    val key = tvShow.id.removePrefix("livehub::folder::")
                    val label = tvShow.title.removePrefix("📁 ").substringBefore(" (")
                    com.streamflixreborn.streamflix.providers.LiveHubFolderDialog.show(
                        context, key, label
                    ) { selected ->
                        navigateFromFolderSelection(binding.root, selected)
                    }
                    return@setOnClickListener
                }
                if (tvShow.id.startsWith(com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.SYNTHETIC_ID_PREFIX)) {
                    openReplayFavorite(findNavController()); return@setOnClickListener
                }
                if (tvShow.id.startsWith(com.streamflixreborn.streamflix.utils.SeasonFavorites.SYNTHETIC_ID_PREFIX)) {
                    openSeasonFavorite(findNavController()); return@setOnClickListener
                }
                // 2026-05-22 : épisode favori → ouvre la saison dans le provider d'origine
                if (tvShow.id.startsWith(com.streamflixreborn.streamflix.utils.EpisodeFavorites.SYNTHETIC_ID_PREFIX)) {
                    openEpisodeFavorite(findNavController()); return@setOnClickListener
                }
                // 2026-05-22 : reprise de lecture série → ouvre la fiche série dans le provider d'origine
                if (tvShow.id.startsWith("resume_series_")) {
                    openResumeSeries(findNavController()); return@setOnClickListener
                }
                // 2026-06-20 (user "le film Titanic ouvre une page synopsis") :
                //   replay favori marqué comme film → player direct.
                if (routeReplayFavoriteIfMovie(findNavController())) return@setOnClickListener
                checkProviderAndRun {
                    if (context.toActivity()?.getCurrentFragment() is com.streamflixreborn.streamflix.fragments.global_favorites.GlobalFavoritesTvFragment) {
                        com.streamflixreborn.streamflix.utils.GlobalFavorites.switchToOrigin(tvShow.id)
                    }
                    if (isIptvProvider()) {
                        handleDirectPlay(findNavController())
                    } else {
                        findNavController().navigate(R.id.tv_show, tvShowArgs())
                    }
                }
            }
            setOnLongClickListener {
                val cf = context.toActivity()?.getCurrentFragment()
                // 2026-06-27 : favori replay/fast affiché hors Cœurs → retrait par appui long.
                if (com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.isFavorite(tvShow.id)) {
                    com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.toggle(
                        tvShow.id, tvShow.title, tvShow.poster, tvShow.banner, tvShow.isMovie)
                    android.widget.Toast.makeText(context, "Retiré des favoris", android.widget.Toast.LENGTH_SHORT).show()
                } else if (cf is com.streamflixreborn.streamflix.fragments.global_favorites.GlobalFavoritesTvFragment) {
                    cf.removeFavorite(tvShow.id, false)
                } else if (!handleIptvFavoriteLongPress()) {
                    ShowOptionsTvDialog(context, tvShow).show()
                }
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = if (hasFocus) AnimationUtils.loadAnimation(context, R.anim.zoom_in) else AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                startAnimation(animation)
                animation.fillAfter = true
            }
        }
        setPoster(binding.ivTvShowPoster)
        applyIptvLogoStyle(binding.ivTvShowPoster)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        // 2026-06-19 (user "bug de confusion Entre les séries et les films Sur toute notre liste") :
        //   pour les programmes Replay (= téléfilms, émissions one-off,
        //   documentaires single-program), ni épisodes ni année → fallback
        //   sur "Série" alors que c'est PAS une série. Fix : ne PAS afficher
        //   le label "Série"/"Film" par défaut. On affiche juste l'année si
        //   dispo, sinon rien.
        binding.tvTvShowLastEpisode.text = when {
            isIptvProvider() -> ""
            tvShow.id.startsWith("livehub::replay::") -> ""
            else -> tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()
                ?.let { "E${it.number}" }
                ?: tvShow.released?.format("yyyy")
                ?: context.getString(if (tvShow.isMovie) R.string.movie_item_type else R.string.tv_show_item_type)
        }
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getInstalledSmartTubePackages(): List<String> {
        val installed = mutableListOf<String>()
        if (isPackageInstalled("org.smarttube.stable")) installed.add("org.smarttube.stable")
        if (isPackageInstalled("org.smarttube.beta")) installed.add("org.smarttube.beta")
        return installed
    }

    private fun launchSmartTube(packageName: String, trailerUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, trailerUrl.toUri())
        intent.setPackage(packageName)
        context.startActivity(intent)
    }

    private fun showSmartTubeVersionDialog(packages: List<String>, trailerUrl: String, shouldSavePreference: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()

        val items = packages.map { pkg ->
            if (pkg == "org.smarttube.stable") context.getString(R.string.smarttube_stable)
            else context.getString(R.string.smarttube_beta)
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.choose_smarttube_version))
            .setItems(items) { _, which ->
                val selectedPackage = packages[which]

                if (shouldSavePreference) {
                    editor.putString("preferred_smarttube_package", selectedPackage).apply()
                }

                launchSmartTube(selectedPackage, trailerUrl)
            }.show()
    }

    private fun handleSmartTubeSelection(trailerUrl: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedPackage = prefs.getString("preferred_smarttube_package", null)
        val stPackages = getInstalledSmartTubePackages()

        if (stPackages.isEmpty()) {
            context.startActivity(Intent(Intent.ACTION_VIEW, trailerUrl.toUri()))
            return
        }

        if (stPackages.size == 1) {
            launchSmartTube(stPackages[0], trailerUrl)
            return
        }

        if (savedPackage != null && stPackages.contains(savedPackage)) {
            launchSmartTube(savedPackage, trailerUrl)
        } else {
            showSmartTubeVersionDialog(stPackages, trailerUrl, true)
        }
    }

    private fun safeLaunchYoutube(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("TvShowViewHolder", "Failed to launch YouTube intent", e)
            Toast.makeText(context, context.getString(R.string.player_external_player_error_video), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleTrailerClick(trailer: String) {
        val youtubeIntent = Intent(Intent.ACTION_VIEW, trailer.toUri())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val preferredPlayer = prefs.getString("preferred_player", "ask")

        when (preferredPlayer) {
            "smarttube" -> {
                handleSmartTubeSelection(trailer)
            }
            "smarttube_stable" -> {
                launchSmartTube("org.smarttube.stable", trailer)
            }
            "smarttube_beta" -> {
                launchSmartTube("org.smarttube.beta", trailer)
            }
            "youtube" -> {
                safeLaunchYoutube(youtubeIntent)
            }
            else -> {
                val stPackages = getInstalledSmartTubePackages()
                if (stPackages.isNotEmpty()) {
                    AlertDialog.Builder(context)
                        .setTitle(context.getString(R.string.watch_trailer_with))
                        .setItems(arrayOf(context.getString(R.string.youtube), context.getString(R.string.smarttube))) { _, which ->
                            if (which == 0) {
                                safeLaunchYoutube(youtubeIntent)
                            } else {
                                if (stPackages.size > 1) {
                                    showSmartTubeVersionDialog(stPackages, trailer, false)
                                } else {
                                    launchSmartTube(stPackages[0], trailer)
                                }
                            }
                        }.show()
                } else {
                    safeLaunchYoutube(youtubeIntent)
                }
            }
        }
    }

    private fun displaySwiperMobileItem(binding: ItemCategorySwiperMobileBinding) {
        binding.ivSwiperBackground.loadTvShowBanner(tvShow) {
            centerCrop().transition(DrawableTransitionOptions.withCrossFade())
        }
        binding.tvSwiperTitle.text = tvShow.title
        binding.tvSwiperTvShowLastEpisode.text = if (isIptvProvider()) "" else tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let { "E${it.number}" } ?: context.getString(if (tvShow.isMovie) R.string.movie_item_type else R.string.tv_show_item_type)
        
        binding.tvSwiperQuality.apply {
            text = tvShow.quality
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvSwiperReleased.apply {
            text = tvShow.released?.format("yyyy")
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvSwiperRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) }
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivSwiperRatingIcon.isVisible = binding.tvSwiperRating.isVisible

        binding.tvSwiperOverview.text = tvShow.overview
        // 2026-06-21 (user "quand je clique sur un épisode du carrousel sur
        //   AnimeSama, ça ne lance rien comme si c'était pas connecté") :
        //   le click n'était câblé QUE sur btnSwiperWatchNow. Si l'user tape
        //   ailleurs sur la carte (jaquette, titre, synopsis), rien ne se
        //   passait. Fix : étend le click handler à TOUTE la carte (root) +
        //   bouton. Même comportement IPTV/non-IPTV qu'avant.
        val swiperClick = View.OnClickListener {
            if (isIptvProvider()) {
                handleDirectPlay(binding.root.findNavController())
            } else {
                binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
            }
        }
        binding.btnSwiperWatchNow.setOnClickListener(swiperClick)
        binding.root.setOnClickListener(swiperClick)
        // Long-press on the swiper card (featured carousel) — toggle favori for IPTV,
        // ouvre le dialog d'options (Téléchargement/Favori/Marquer vu/etc.) pour les
        // autres providers. Le binding du swiper est partagé Mobile/TV donc on
        // choisit le bon dialog selon l'activité hôte.
        binding.root.setOnLongClickListener {
            if (!handleIptvFavoriteLongPress()) {
                val isTv = context.toActivity() is com.streamflixreborn.streamflix.activities.main.MainTvActivity
                if (isTv) {
                    com.streamflixreborn.streamflix.ui.ShowOptionsTvDialog(context, tvShow).show()
                } else {
                    ShowOptionsMobileDialog(context, tvShow).show()
                }
            }
            true
        }
    }

    private fun displayTvShowMobile(binding: ContentTvShowMobileBinding) {
        // MOBILE TV SHOW DETAIL
        // Poster GONE = le goneMarginTop du titre s'applique (espace pour la bannière)
        // La bannière backdrop est gérée par le fragment (iv_tv_show_banner), pas ici.
        binding.ivTvShowPoster.visibility = View.GONE
        binding.tvTvShowTitle.text = tvShow.title

        binding.tvTvShowRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivTvShowRatingIcon.isVisible = binding.tvTvShowRating.isVisible

        binding.tvTvShowQuality.apply {
            text = tvShow.quality
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowReleased.apply {
            text = tvShow.released?.format("yyyy")
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowRuntime.apply {
            text = tvShow.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(R.string.tv_show_runtime_hours_minutes, hours, minutes)
                    else -> context.getString(R.string.tv_show_runtime_minutes, minutes)
                }
            }
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowGenres.apply {
            text = tvShow.genres.joinToString(", ") { it.name }
            isVisible = tvShow.genres.isNotEmpty()
        }

        val overviewText = LanguageTag.prefixOverview(
            overview = tvShow.overview,
            title = tvShow.title,
            quality = tvShow.quality,
            seasonLabels = tvShow.seasons.mapNotNull { it.title },
            version = tvShow.version,
        )
        binding.tvTvShowOverview.apply {
            text = if (overviewText.isNullOrBlank()) "Aucun synopsis disponible." else overviewText
            isVisible = true
        }
        binding.tvTvShowOverviewLabel.isVisible = true

        // Note communautaire
        binding.root.findViewById<View>(R.id.include_community_rating)?.let { ratingRoot ->
            val yearStr = tvShow.released?.format("yyyy")
            val contentKey = RatingService.contentKey(
                tmdbId = tvShow.id.takeIf { it.all { c -> c.isDigit() } },
                title = tvShow.title,
                year = yearStr,
            )
            CommunityRatingView.bind(ratingRoot, contentKey, tvShow.title, context, year = yearStr, isTvShow = true)
        }

        // Langue communautaire (vote VF/VOSTFR/VO)
        binding.root.findViewById<View>(R.id.include_community_language)?.let { langRoot ->
            val contentKey = RatingService.contentKey(
                tmdbId = tvShow.id.takeIf { it.all { c -> c.isDigit() } },
                title = tvShow.title,
                year = tvShow.released?.format("yyyy"),
            )
            CommunityLanguageView.bind(langRoot, contentKey, tvShow.title, context)
        }

        val episodeToWatch = tvShow.episodeToWatch
        val episodeSeason = resolveEpisodeSeason(episodeToWatch)
        binding.btnTvShowWatchNow.apply {
            isVisible = episodeToWatch != null
            setOnClickListener {
                // 2026-05-15 (user "sur Mon IPTV cliquer sur Regarder S1 E1 ne
                // lance pas la lecture, suivant/précédent puis ça marche") :
                // pour Mon IPTV SÉRIES, episodeToWatch.id encode déjà l'épisode
                // précis (format myiptv-stalkerep::sourceId::chIdx::sN::eN). Si
                // on passe par handleDirectPlay(), c'est tvShow.id (myiptv-show::)
                // qui est envoyé au player → MyIptvProvider.getServers ne match
                // aucun case → emptyList → écran noir. Fix : si on a un
                // episodeToWatch valide pour Mon IPTV série, on prend le même
                // chemin que les providers non-IPTV (navigue avec l'episode.id).
                val isMonIptvEpisode = episodeToWatch != null && (
                    episodeToWatch.id.startsWith("myiptv-stalkerep::") ||
                    episodeToWatch.id.startsWith("myiptv-ep::")
                )
                if (isIptvProvider() && !isMonIptvEpisode) {
                    handleDirectPlay(findNavController())
                } else if (episodeToWatch != null
                    && (episodeToWatch.id.startsWith("@subfolder:")
                        || episodeToWatch.overview == "@subfolder")
                ) {
                    // 2026-06-21 (user "sur AnimeSama, quand on lance le bouton
                    //   Regarder Saison 1 ça lance directement, on n'a pas pu
                    //   choisir VF ou VOSTFR, du coup il tombe sur aucun
                    //   serveur. Si on clique en dessous sur VOSTFR ou VF ça
                    //   marche") :
                    //   episodeToWatch est un wrapper langue (@subfolder).
                    //   Le player ne peut pas le lire — il faut d'abord
                    //   choisir VF ou VOSTFR. On navigue vers la Season
                    //   fragment qui affiche les sous-dossiers (cards VF /
                    //   VOSTFR / saisons individuelles).
                    val realSeasonId = episodeToWatch.id.removePrefix("@subfolder:")
                    val args = Bundle().apply {
                        putString("tvShowId", tvShow.id)
                        putString("tvShowTitle", tvShow.title)
                        putString("tvShowPoster", tvShow.poster)
                        putString("tvShowBanner", tvShow.banner)
                        putString("seasonId", realSeasonId)
                        putInt("seasonNumber", episodeToWatch.number)
                        putString("seasonTitle", episodeToWatch.title)
                    }
                    findNavController().navigate(R.id.season, args)
                } else {
                    val videoType = Video.Type.Episode(
                        id = episodeToWatch!!.id,
                        number = episodeToWatch.number,
                        title = episodeToWatch.title,
                        poster = episodeToWatch.poster,
                        overview = episodeToWatch.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = tvShow.id,
                            title = tvShow.title,
                            poster = tvShow.poster,
                            banner = tvShow.banner,
                            releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                            imdbId = tvShow.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = episodeSeason?.number ?: 1,
                            title = episodeSeason?.title ?: "",
                        ),
                    )
                    val args = Bundle().apply {
                        putString("id", episodeToWatch.id)
                        putString("title", tvShow.title)
                        putString("subtitle", "S${videoType.season.number} E${videoType.number}  •  ${videoType.title}")
                        putSerializable("videoType", videoType)
                    }
                    findNavController().navigate(R.id.player, args)
                }
            }
            val isSingleEpisode = tvShow.seasons.size <= 1 && (tvShow.seasons.firstOrNull()?.episodes?.size ?: 0) <= 1
            // 2026-05-15 : pour Mon IPTV séries, afficher "S1 E1" au lieu de
            // "Regarder maintenant" → cohérent avec ce qui sera réellement lu.
            val isMonIptvEpisodeText = episodeToWatch != null && (
                episodeToWatch.id.startsWith("myiptv-stalkerep::") ||
                episodeToWatch.id.startsWith("myiptv-ep::")
            )
            text = when {
                (isIptvProvider() && !isMonIptvEpisodeText) || isSingleEpisode ->
                    context.getString(R.string.movie_watch_now)
                else -> context.getString(R.string.tv_show_watch_season_episode, episodeSeason?.number ?: 1, episodeToWatch?.number ?: 1)
            }
        }

        binding.pbTvShowProgressEpisode.apply {
            val watchHistory = episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }

        binding.btnTvShowTrailer.apply {
            val trailer = tvShow.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer)
            }
            isVisible = trailer != null
        }

        binding.btnTvShowFavorite.apply {
            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.tvShowDao()
                        val current = dao.getById(tvShow.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedTvShow = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, newValue)

                        dao.upsertFavorite(resolvedTvShow, newValue)

                        withContext(Dispatchers.Main) {
                            tvShow.poster = resolvedTvShow.poster
                            tvShow.banner = resolvedTvShow.banner
                            tvShow.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, tvShow.isFavorite.drawable())
            )
        }
    }

    private fun displayTvShowTv(binding: ContentTvShowTvBinding) {
        // 2026-05-21 (user "le poster de gauche est redondant avec le backdrop, et
        //   flou depuis qu'on a réduit les jaquettes → on le vire et on réagence la
        //   fiche TV") : poster masqué. Le goneMarginStart=70dp du layout fait
        //   glisser titre/synopsis/boutons à gauche automatiquement.
        binding.ivTvShowPoster.visibility = View.GONE
        binding.tvTvShowTitle.text = tvShow.title

        binding.tvTvShowRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivTvShowRatingIcon.isVisible = binding.tvTvShowRating.isVisible

        binding.tvTvShowQuality.apply {
            text = tvShow.quality
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowReleased.apply {
            text = tvShow.released?.format("yyyy")
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowRuntime.apply {
            text = tvShow.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(R.string.tv_show_runtime_hours_minutes, hours, minutes)
                    else -> context.getString(R.string.tv_show_runtime_minutes, minutes)
                }
            }
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowGenres.apply {
            text = tvShow.genres.joinToString(", ") { it.name }
            isVisible = tvShow.genres.isNotEmpty()
        }

        val overviewText = LanguageTag.prefixOverview(
            overview = tvShow.overview,
            title = tvShow.title,
            quality = tvShow.quality,
            seasonLabels = tvShow.seasons.mapNotNull { it.title },
            version = tvShow.version,
        )
        binding.tvTvShowOverview.apply {
            text = if (overviewText.isNullOrBlank()) "Aucun synopsis disponible." else overviewText
            isVisible = true
        }
        binding.tvTvShowOverviewLabel.isVisible = true

        // Note communautaire
        binding.root.findViewById<View>(R.id.include_community_rating)?.let { ratingRoot ->
            val yearStr = tvShow.released?.format("yyyy")
            val contentKey = RatingService.contentKey(
                tmdbId = tvShow.id.takeIf { it.all { c -> c.isDigit() } },
                title = tvShow.title,
                year = yearStr,
            )
            CommunityRatingView.bind(ratingRoot, contentKey, tvShow.title, context, year = yearStr, isTvShow = true)
        }

        // Langue communautaire (vote VF/VOSTFR/VO)
        binding.root.findViewById<View>(R.id.include_community_language)?.let { langRoot ->
            val contentKey = RatingService.contentKey(
                tmdbId = tvShow.id.takeIf { it.all { c -> c.isDigit() } },
                title = tvShow.title,
                year = tvShow.released?.format("yyyy"),
            )
            CommunityLanguageView.bind(langRoot, contentKey, tvShow.title, context)
        }

        val episodeToWatch = tvShow.episodeToWatch
        val episodeSeason = resolveEpisodeSeason(episodeToWatch)
        binding.btnTvShowWatchNow.apply {
            isVisible = episodeToWatch != null
            setOnClickListener {
                // 2026-05-15 (user "sur Mon IPTV cliquer sur Regarder S1 E1 ne
                // lance pas la lecture, suivant/précédent puis ça marche") :
                // pour Mon IPTV SÉRIES, episodeToWatch.id encode déjà l'épisode
                // précis (format myiptv-stalkerep::sourceId::chIdx::sN::eN). Si
                // on passe par handleDirectPlay(), c'est tvShow.id (myiptv-show::)
                // qui est envoyé au player → MyIptvProvider.getServers ne match
                // aucun case → emptyList → écran noir. Fix : si on a un
                // episodeToWatch valide pour Mon IPTV série, on prend le même
                // chemin que les providers non-IPTV (navigue avec l'episode.id).
                val isMonIptvEpisode = episodeToWatch != null && (
                    episodeToWatch.id.startsWith("myiptv-stalkerep::") ||
                    episodeToWatch.id.startsWith("myiptv-ep::")
                )
                if (isIptvProvider() && !isMonIptvEpisode) {
                    handleDirectPlay(findNavController())
                } else if (episodeToWatch != null
                    && (episodeToWatch.id.startsWith("@subfolder:")
                        || episodeToWatch.overview == "@subfolder")
                ) {
                    // 2026-06-21 (user "sur AnimeSama, quand on lance le bouton
                    //   Regarder Saison 1 ça lance directement, on n'a pas pu
                    //   choisir VF ou VOSTFR, du coup il tombe sur aucun
                    //   serveur. Si on clique en dessous sur VOSTFR ou VF ça
                    //   marche") :
                    //   episodeToWatch est un wrapper langue (@subfolder).
                    //   Le player ne peut pas le lire — il faut d'abord
                    //   choisir VF ou VOSTFR. On navigue vers la Season
                    //   fragment qui affiche les sous-dossiers (cards VF /
                    //   VOSTFR / saisons individuelles).
                    val realSeasonId = episodeToWatch.id.removePrefix("@subfolder:")
                    val args = Bundle().apply {
                        putString("tvShowId", tvShow.id)
                        putString("tvShowTitle", tvShow.title)
                        putString("tvShowPoster", tvShow.poster)
                        putString("tvShowBanner", tvShow.banner)
                        putString("seasonId", realSeasonId)
                        putInt("seasonNumber", episodeToWatch.number)
                        putString("seasonTitle", episodeToWatch.title)
                    }
                    findNavController().navigate(R.id.season, args)
                } else {
                    val videoType = Video.Type.Episode(
                        id = episodeToWatch!!.id,
                        number = episodeToWatch.number,
                        title = episodeToWatch.title,
                        poster = episodeToWatch.poster,
                        overview = episodeToWatch.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = tvShow.id,
                            title = tvShow.title,
                            poster = tvShow.poster,
                            banner = tvShow.banner,
                            releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                            imdbId = tvShow.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = episodeSeason?.number ?: 1,
                            title = episodeSeason?.title ?: "",
                        ),
                    )
                    val args = Bundle().apply {
                        putString("id", episodeToWatch.id)
                        putString("title", tvShow.title)
                        putString("subtitle", "S${videoType.season.number} E${videoType.number}  •  ${videoType.title}")
                        putSerializable("videoType", videoType)
                    }
                    findNavController().navigate(R.id.player, args)
                }
            }
            val isSingleEpisode = tvShow.seasons.size <= 1 && (tvShow.seasons.firstOrNull()?.episodes?.size ?: 0) <= 1
            // 2026-05-15 : pour Mon IPTV séries, afficher "S1 E1" au lieu de
            // "Regarder maintenant" → cohérent avec ce qui sera réellement lu.
            val isMonIptvEpisodeText = episodeToWatch != null && (
                episodeToWatch.id.startsWith("myiptv-stalkerep::") ||
                episodeToWatch.id.startsWith("myiptv-ep::")
            )
            text = when {
                (isIptvProvider() && !isMonIptvEpisodeText) || isSingleEpisode ->
                    context.getString(R.string.movie_watch_now)
                else -> context.getString(R.string.tv_show_watch_season_episode, episodeSeason?.number ?: 1, episodeToWatch?.number ?: 1)
            }
        }

        binding.pbTvShowProgressEpisode.apply {
            val watchHistory = episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }

        binding.btnTvShowTrailer.apply {
            val trailer = tvShow.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer)
            }
            isVisible = trailer != null
        }

        binding.btnTvShowFavorite.apply {
            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.tvShowDao()
                        val current = dao.getById(tvShow.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedTvShow = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, newValue)

                        dao.upsertFavorite(resolvedTvShow, newValue)

                        withContext(Dispatchers.Main) {
                            tvShow.poster = resolvedTvShow.poster
                            tvShow.banner = resolvedTvShow.banner
                            tvShow.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, tvShow.isFavorite.drawable())
            )
        }

        // Force le focus initial sur "Regarder" (évite que les boutons langue/étoiles
        // sous la jaquette captent le focus D-pad à l'ouverture de la fiche)
        binding.btnTvShowWatchNow.requestFocus()
    }

    private val languageCodes = setOf("VOSTFR", "VF", "VOSTFR/VF", "VF/VOSTFR", "MULTI")

    private fun isLanguageChoice(): Boolean {
        return tvShow.seasons.isNotEmpty() && tvShow.seasons.all { season ->
            season.title?.uppercase()?.trim()?.let { it in languageCodes } == true
        }
    }

    private fun displaySeasonsMobile(binding: ContentTvShowSeasonsMobileBinding) {
        if (isLanguageChoice()) {
            binding.tvTvShowSeasonsLabel.text = "Langue"
        }
        // 2026-06-24 v8 RESET (user "il y a pas besoin de boutons
        //   supplémentaires") : on cache les boutons VOSTFR/VF dans la
        //   synopsis. Le switch langue se fait dans le panel épisodes du
        //   player (cf onglets bleus VOSTFR/VF déjà fonctionnels).
        try { binding.llTvShowLangTabs.visibility = android.view.View.GONE } catch (_: Throwable) {}
        binding.rvTvShowSeasons.apply {
            adapter = AppAdapter().apply { submitList(tvShow.seasons.onEach { it.itemType = AppAdapter.Type.SEASON_MOBILE_ITEM }) }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displaySeasonsTv(binding: ContentTvShowSeasonsTvBinding) {
        if (isLanguageChoice()) {
            binding.tvTvShowSeasonsLabel.text = "Langue"
        }
        try { binding.llTvShowLangTabs.visibility = android.view.View.GONE } catch (_: Throwable) {}
        binding.hgvTvShowSeasons.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply { submitList(tvShow.seasons.onEach { it.itemType = AppAdapter.Type.SEASON_TV_ITEM }) }
            setItemSpacing(20)
        }
    }

    /**
     * 2026-06-24 (user "VOSTFR/VF dans le menu synepsie ET dans le panel
     *   épisodes") : helper unifié qui binde 2 boutons VOSTFR/VF dans la
     *   synopsis. Détecte AnimeSama, affiche les boutons, highlight celui
     *   sélectionné, et au click navigate vers le même tvShow avec id
     *   slug@vf/slug@vostfr → re-fetch des saisons en bonne langue.
     */
    private fun bindLangTabsForAnimeSama(
        container: android.widget.LinearLayout,
        btnVostfr: android.widget.TextView,
        btnVf: android.widget.TextView,
        isTv: Boolean,
    ) {
        val provider = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
        val isAnimeSama = provider?.name == "AnimeSama"
        if (!isAnimeSama) {
            container.visibility = android.view.View.GONE
            return
        }
        container.visibility = android.view.View.VISIBLE
        val idAfter = tvShow.id.substringAfter("@", "")
        val currentLang = when {
            idAfter.startsWith("vf") -> "vf"
            idAfter.startsWith("vostfr") -> "vostfr"
            else -> "vostfr" // défaut AnimeSama
        }
        // Sur TV : on garde le background = drawable selector (focus rouge auto).
        // Sur mobile : on force la couleur bleue pour celui sélectionné.
        if (!isTv) {
            val selectedBg = android.graphics.Color.parseColor("#CC1565C0")
            val unselectedBg = android.graphics.Color.parseColor("#33FFFFFF")
            btnVostfr.setBackgroundColor(if (currentLang == "vostfr") selectedBg else unselectedBg)
            btnVf.setBackgroundColor(if (currentLang == "vf") selectedBg else unselectedBg)
        }
        // 2026-06-24 v2 (user "on voit pas le quadrant quand on se déplace
        //   dessus") : effet visuel SCALE + alpha au focus pour que le user
        //   voie clairement quel bouton est focusé sur la télécommande.
        if (isTv) {
            val onFocus: (android.view.View, Boolean) -> Unit = { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.12f else 1.0f)
                    .scaleY(if (hasFocus) 1.12f else 1.0f)
                    .alpha(if (hasFocus) 1.0f else 0.85f)
                    .setDuration(150L).start()
            }
            btnVostfr.alpha = 0.85f
            btnVf.alpha = 0.85f
            btnVostfr.setOnFocusChangeListener(onFocus)
            btnVf.setOnFocusChangeListener(onFocus)
        }
        val slug = tvShow.id.substringBefore("@")
        btnVostfr.setOnClickListener {
            if (currentLang != "vostfr") {
                val args = android.os.Bundle().apply {
                    putString("id", "$slug@vostfr")
                    putString("title", tvShow.title)
                    putString("poster", tvShow.poster)
                    putString("banner", tvShow.banner)
                }
                androidx.navigation.Navigation.findNavController(itemView)
                    .navigate(com.streamflixreborn.streamflix.R.id.tv_show, args)
            }
        }
        btnVf.setOnClickListener {
            if (currentLang != "vf") {
                val args = android.os.Bundle().apply {
                    putString("id", "$slug@vf")
                    putString("title", tvShow.title)
                    putString("poster", tvShow.poster)
                    putString("banner", tvShow.banner)
                }
                androidx.navigation.Navigation.findNavController(itemView)
                    .navigate(com.streamflixreborn.streamflix.R.id.tv_show, args)
            }
        }
    }

    private fun displayDirectorsMobile(binding: ContentTvShowDirectorsMobileBinding) { binding.rvTvShowDirectors.text = tvShow.directors.joinToString(", ") { it.name } }
    private fun displayDirectorsTv(binding: ContentTvShowDirectorsTvBinding) { binding.hgvTvShowDirectors.text = tvShow.directors.joinToString(", ") { it.name } }
    private fun displayCastMobile(binding: ContentTvShowCastMobileBinding) {
        binding.rvTvShowCast.apply {
            adapter = AppAdapter().apply {
                submitList(tvShow.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayCastTv(binding: ContentTvShowCastTvBinding) {
        binding.hgvTvShowCast.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(tvShow.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                })
            }
            setItemSpacing(20)
        }
    }
    private fun displayRecommendationsMobile(binding: ContentTvShowRecommendationsMobileBinding) {
        binding.rvTvShowRecommendations.apply {
            adapter = AppAdapter().apply {
                submitList(tvShow.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                    }
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayRecommendationsTv(binding: ContentTvShowRecommendationsTvBinding) {
        binding.hgvTvShowRecommendations.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(tvShow.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                })
            }
            setItemSpacing(20)
        }
    }
}
