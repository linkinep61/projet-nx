package com.streamflixreborn.streamflix.adapters.viewholders

import android.view.animation.AnimationUtils
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemSeasonMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemSeasonTvBinding
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.utils.SeasonFavorites
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.optimizeArtworkUrl

class SeasonViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var season: Season

    fun bind(season: Season) {
        this.season = season

        when (_binding) {
            is ItemSeasonMobileBinding -> displayMobileItem(_binding)
            is ItemSeasonTvBinding -> displayTvItem(_binding)
        }
    }


    /** 2026-05-21 : toggle favori SAISON (appui long). Non-IPTV uniquement (les
     *  saisons n'existent que pour les séries des providers VOD). Retourne true
     *  (long-click consommé). */
    private fun toggleSeasonFavorite(): Boolean {
        val provider = UserPreferences.currentProvider?.name ?: return true
        val show = season.tvShow ?: return true
        val showId = show.id
        val nowFav = SeasonFavorites.toggle(
            SeasonFavorites.Entry(
                provider = provider,
                showId = showId,
                showTitle = show.title ?: "",
                showPoster = show.poster,
                showBanner = show.banner,
                seasonId = season.id,
                seasonNumber = season.number,
                seasonTitle = season.title,
                favoritedAt = 0L,
            )
        )
        android.widget.Toast.makeText(
            context,
            if (nowFav) "Saison ajoutée aux favoris ♥" else "Saison retirée des favoris",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    private fun displayMobileItem(binding: ItemSeasonMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                findNavController().navigate(
                    TvShowMobileFragmentDirections.actionTvShowToSeason(
                        tvShowId = season.tvShow?.id ?: "",
                        tvShowTitle = season.tvShow?.title ?: "",
                        tvShowPoster = season.tvShow?.poster,
                        tvShowBanner = season.tvShow?.banner,
                        seasonId = season.id,
                        seasonNumber = season.number,
                        seasonTitle = season.title,
                    )
                )
            }
            // 2026-05-21 : appui long → ajoute/retire la saison des favoris.
            setOnLongClickListener { toggleSeasonFavorite() }
        }

        binding.ivSeasonPoster.apply {
            clipToOutline = true
            Glide.with(context)
                .load(optimizeArtworkUrl(season.poster, 400))
                // 2026-05-21 : si le proxy weserv échoue (covers AnimeSama jsDelivr
                //   « @img », que weserv n'arrive pas toujours à récupérer), on recharge
                //   l'URL BRUTE en direct (Glide OkHttp) AVANT de tomber sur la jaquette
                //   de secours grise. .error(RequestBuilder) = méthode Glide propre.
                .error(
                    Glide.with(context)
                        .load(season.poster)
                        .centerCrop()
                        .error(R.drawable.glide_fallback_cover)
                )
                .fallback(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }

        binding.tvSeasonTitle.text = season.title ?: context.getString(
            R.string.season_number,
            season.number
        )
    }

    private fun displayTvItem(binding: ItemSeasonTvBinding) {
        binding.root.apply {
            setOnClickListener {
                findNavController().navigate(
                    TvShowTvFragmentDirections.actionTvShowToSeason(
                        tvShowId = season.tvShow?.id ?: "",
                        tvShowTitle = season.tvShow?.title ?: "",
                        tvShowPoster = season.tvShow?.poster,
                        tvShowBanner = season.tvShow?.banner,
                        seasonId = season.id,
                        seasonNumber = season.number,
                        seasonTitle = season.title,
                    )
                )
            }
            // 2026-05-21 : appui long → ajoute/retire la saison des favoris.
            setOnLongClickListener { toggleSeasonFavorite() }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.ivSeasonPoster.startAnimation(animation)
                animation.fillAfter = true
            }
        }

        binding.ivSeasonPoster.apply {
            clipToOutline = true
            Glide.with(context)
                .load(optimizeArtworkUrl(season.poster, 400))
                // 2026-05-21 : si le proxy weserv échoue (covers AnimeSama jsDelivr
                //   « @img », que weserv n'arrive pas toujours à récupérer), on recharge
                //   l'URL BRUTE en direct (Glide OkHttp) AVANT de tomber sur la jaquette
                //   de secours grise. .error(RequestBuilder) = méthode Glide propre.
                .error(
                    Glide.with(context)
                        .load(season.poster)
                        .centerCrop()
                        .error(R.drawable.glide_fallback_cover)
                )
                .fallback(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }

        binding.tvSeasonTitle.text = season.title ?: context.getString(
            R.string.season_number,
            season.number
        )
    }
}
