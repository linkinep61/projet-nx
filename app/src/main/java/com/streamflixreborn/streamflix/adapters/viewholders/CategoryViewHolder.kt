package com.streamflixreborn.streamflix.adapters.viewholders

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.os.postDelayed
import androidx.core.view.children
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.viewpager2.widget.ViewPager2
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.ContentCategorySwiperMobileBinding
import com.streamflixreborn.streamflix.databinding.ContentCategorySwiperTvBinding
import com.streamflixreborn.streamflix.databinding.ItemCategoryMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemCategoryTvBinding
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragment
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragmentDirections
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.utils.toActivity
import java.util.Locale
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.providers.Provider

class CategoryViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    companion object {
        /** Shared ViewHolder pool across all horizontal category RecyclerViews.
         *  Reduces inflation count when scrolling the vertical list of categories. */
        val sharedPool = RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, 12) // keep up to 12 recycled VHs of type 0
        }
    }

    private val context = itemView.context
    private lateinit var category: Category

    // ---- Swiper lifecycle tracking (prevent leaks on recycle) ----
    private var swiperHandler: Handler? = null
    private var swiperCallback: ViewPager2.OnPageChangeCallback? = null
    private var swiperViewPager: ViewPager2? = null

    /** Must be called before each new bind AND from onViewRecycled. */
    fun cleanup() {
        swiperHandler?.removeCallbacksAndMessages(null)
        swiperHandler = null
        swiperCallback?.let { swiperViewPager?.unregisterOnPageChangeCallback(it) }
        swiperCallback = null
        swiperViewPager = null
    }

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ItemCategoryMobileBinding -> _binding.rvCategory
            is ItemCategoryTvBinding -> _binding.hgvCategory
            is ContentCategorySwiperMobileBinding -> try {
                _binding.vpCategorySwiper.javaClass
                    .getDeclaredField("mRecyclerView").let {
                        it.isAccessible = true
                        it.get(_binding.vpCategorySwiper) as? RecyclerView
                    }
            } catch (_: Exception) { null }
            else -> null
        }

    fun bind(
        category: Category,
        onMovieClick: ((Movie) -> Unit)? = null,
        onTvShowClick: ((TvShow) -> Unit)? = null,
        onSwiperPageChanged: ((bannerUrl: String?) -> Unit)? = null
    ) {
        cleanup() // cancel pending handlers & unregister old callbacks
        this.category = category

        when (_binding) {
            is ItemCategoryMobileBinding -> displayMobileItem(_binding, onMovieClick, onTvShowClick)
            is ItemCategoryTvBinding -> displayTvItem(_binding, onMovieClick, onTvShowClick)
            is ContentCategorySwiperMobileBinding -> displayMobileSwiper(_binding, onMovieClick, onTvShowClick, onSwiperPageChanged)
            is ContentCategorySwiperTvBinding -> displayTvSwiper(_binding)
        }
    }

    private fun displayMobileItem(
        binding: ItemCategoryMobileBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?
    ) {
        binding.tvCategoryTitle.text = category.name

        binding.rvCategory.apply {
            setHasFixedSize(true)
            setRecycledViewPool(sharedPool)
            val categoryAdapter = (adapter as? AppAdapter) ?: AppAdapter().also { adapter = it }
            categoryAdapter.apply {
                this.onMovieClickListener = onMovieClick
                this.onTvShowClickListener = onTvShowClick
                submitList(category.list)
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(category.itemSpacing))
            }
        }
        preloadRowArtwork(category.list)
    }

    private fun displayTvItem(
        binding: ItemCategoryTvBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?
    ) {
        binding.tvCategoryTitle.text = category.name
        binding.hgvCategory.apply {
            setHasFixedSize(true)
            setRecycledViewPool(sharedPool)
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)

            val categoryAdapter = (adapter as? AppAdapter) ?: AppAdapter().also { adapter = it }
            categoryAdapter.apply {
                this.onMovieClickListener = onMovieClick
                this.onTvShowClickListener = onTvShowClick
                submitList(category.list)
            }
            setItemSpacing(category.itemSpacing)

            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        preloadRowArtwork(category.list)
    }

    /**
     * 2026-05-21 (user "des jaquettes ne s'affichent qu'au moment où je navigue dessus,
     *   faut précharger les suivantes") : précharge en cache Glide les jaquettes de la
     *   rangée dès qu'elle s'affiche. Quand on scrolle, le téléchargement réseau est
     *   déjà fait → la carte s'affiche tout de suite au lieu de rester vide. Limité à
     *   ~15 items/rangée (Glide traite les cartes visibles en priorité, préchargées après).
     */
    private fun preloadRowArtwork(list: List<AppAdapter.Item>) {
        list.take(15).forEach { item ->
            val url = when (item) {
                is Movie -> item.poster
                is TvShow -> item.poster ?: item.banner
                is com.streamflixreborn.streamflix.models.Episode -> item.poster
                else -> null
            }
            if (!url.isNullOrBlank()) {
                try {
                    com.bumptech.glide.Glide.with(context)
                        .load(com.streamflixreborn.streamflix.utils.optimizeArtworkUrl(url, 400))
                        .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                        .preload(342, 513)
                } catch (_: Throwable) {
                }
            }
        }
    }


    private fun displayMobileSwiper(
        binding: ContentCategorySwiperMobileBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onSwiperPageChanged: ((bannerUrl: String?) -> Unit)? = null
    ) {
        binding.tvCategoryTitle.text = category.name
        val handler = Handler(Looper.getMainLooper())
        swiperHandler = handler
        swiperViewPager = binding.vpCategorySwiper
        handler.postDelayed(15_000) {
            binding.vpCategorySwiper.currentItem += 1
        }

        val items = listOf(
            listOfNotNull(category.list.lastOrNull()),
            category.list,
            listOfNotNull(category.list.firstOrNull()),
        ).flatten()
        binding.vpCategorySwiper.apply {
            adapter = AppAdapter().apply {
                this.onMovieClickListener = onMovieClick
                this.onTvShowClickListener = onTvShowClick
                submitList(category.list)
                post { (adapter as AppAdapter).submitList(items) }
            }
        }

        // Notifier le fond d'écran global avec le premier item du carrousel
        val firstBanner = (category.list.firstOrNull() as? Movie)?.banner
            ?: (category.list.firstOrNull() as? TvShow)?.banner
        onSwiperPageChanged?.invoke(firstBanner)

        binding.llDotsIndicator.apply {
            removeAllViews()
            repeat(category.list.size) {
                val view = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(15, 15).apply {
                        setMargins(10, 0, 10, 0)
                    }
                    setBackgroundResource(R.drawable.bg_dot_indicator)
                }
                addView(view)
            }
        }

        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val indicatorPosition = when (position) {
                    0 -> category.list.lastIndex
                    items.lastIndex -> 0
                    else -> position - 1
                }
                binding.llDotsIndicator.children.forEachIndexed { index, view ->
                    view.isSelected = (indicatorPosition == index)
                }

                // Notifier le fond d'écran global du home avec le backdrop de l'item courant
                val currentShow = items.getOrNull(position)
                val bannerUrl = when (currentShow) {
                    is Movie -> currentShow.banner
                    is TvShow -> currentShow.banner
                    else -> null
                }
                onSwiperPageChanged?.invoke(bannerUrl)

                handler.removeCallbacksAndMessages(null)
                handler.postDelayed(15_000) {
                    binding.vpCategorySwiper.currentItem += 1
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    when (binding.vpCategorySwiper.currentItem) {
                        0 -> binding.vpCategorySwiper.setCurrentItem(
                            items.lastIndex - 1,
                            false
                        )
                        items.lastIndex -> binding.vpCategorySwiper.setCurrentItem(
                            1,
                            false
                        )
                    }
                }
            }
        }
        swiperCallback = callback
        binding.vpCategorySwiper.registerOnPageChangeCallback(callback)
    }

    private fun displayTvSwiper(binding: ContentCategorySwiperTvBinding) {
        binding.tvCategoryTitle.text = category.name
        val selected = category.list.getOrNull(category.selectedIndex) as? Show ?: return

        fun checkProviderAndRun(show: Show, action: () -> Unit) {
            val providerName = when(show){
                is Movie -> show.providerName
                is TvShow -> show.providerName
            }

            if (!providerName.isNullOrBlank() && providerName != UserPreferences.currentProvider?.name) {
                Provider.providers.keys.find { it.name == providerName }?.let {
                    UserPreferences.currentProvider = it
                }
            }
            action()
        }

        // Aggiornamento dello sfondo forzato per TV all'inizio o al cambio indice
        val poster = when (selected) {
            is Movie -> selected.banner
            is TvShow -> selected.banner
            else -> null
        }

        when (val fragment = context.toActivity()?.getCurrentFragment()) {
            is HomeTvFragment -> {
                if (poster != null) {
                    fragment.updateBackground(poster, false) // Imposta lo sfondo senza marcare come focalizzato
                }

                // Se l'elemento è stato appena selezionato (indice cambiato), assicura che l'aggiornamento sia visibile
                if (category.selectedIndex == category.list.indexOf(selected)) {
                    fragment.resetSwiperSchedule() // Riavvia lo scheduler per assicurarsi che continui
                }
            }
        }

        binding.tvSwiperTitle.text = when (selected) {
            is Movie -> selected.title
            is TvShow -> selected.title
        }

        binding.tvSwiperTvShowLastEpisode.apply {
            text = when (selected) {
                is TvShow -> selected.seasons.lastOrNull()?.let { season ->
                    season.episodes.lastOrNull()?.let { episode ->
                        if (season.number != 0) {
                            context.getString(
                                R.string.tv_show_item_season_number_episode_number,
                                season.number,
                                episode.number
                            )
                        } else {
                            context.getString(
                                R.string.tv_show_item_episode_number,
                                episode.number
                            )
                        }
                    }
                } ?: context.getString(R.string.tv_show_item_type)
                else -> context.getString(R.string.movie_item_type)
            }
        }

        binding.tvSwiperQuality.apply {
            text = when (selected) {
                is Movie -> selected.quality
                is TvShow -> selected.quality
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvSwiperReleased.apply {
            text = when (selected) {
                is Movie -> selected.released?.format("yyyy")
                is TvShow -> selected.released?.format("yyyy")
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvSwiperRating.apply {
            text = when (selected) {
                is Movie -> selected.rating?.let { String.format(Locale.ROOT, "%.1f", it) }
                is TvShow -> selected.rating?.let { String.format(Locale.ROOT, "%.1f", it) }
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.ivSwiperRatingIcon.visibility = binding.tvSwiperRating.visibility

        binding.tvSwiperOverview.text = when (selected) {
            is Movie -> selected.overview
            is TvShow -> selected.overview
        }


        binding.btnSwiperWatchNow.apply {
            setOnClickListener {
                checkProviderAndRun(selected) {
                    findNavController().navigate(
                        when (selected) {
                            is Movie -> HomeTvFragmentDirections.actionHomeToMovie(selected.id)
                            is TvShow -> HomeTvFragmentDirections.actionHomeToTvShow(
                                id = selected.id,
                                poster = selected.poster,
                                banner = selected.banner,
                            )
                        }
                    )
                }
            }
            setOnKeyListener { _, _, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            when (val fragment = context.toActivity()?.getCurrentFragment()) {
                                is HomeTvFragment -> fragment.resetSwiperSchedule()
                            }
                            category.selectedIndex = (category.selectedIndex + 1) % category.list.size
                            when (val fragment = context.toActivity()?.getCurrentFragment()) {
                                is HomeTvFragment -> when (val it = category.list[category.selectedIndex]) {
                                    is Movie -> fragment.updateBackground(it.banner, true)
                                    is TvShow -> fragment.updateBackground(it.banner, true)
                                }
                            }
                            bindingAdapter?.notifyItemChanged(bindingAdapterPosition)
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }
        }

        binding.pbSwiperProgress.apply {
            val watchHistory = when (selected) {
                is Movie -> selected.watchHistory
                is TvShow -> null
            }

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.llDotsIndicator.apply {
            removeAllViews()
            repeat(category.list.size) { index ->
                val view = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(15, 15).apply {
                        setMargins(10, 0, 10, 0)
                    }
                    setBackgroundResource(R.drawable.bg_dot_indicator)
                    isSelected = (category.selectedIndex == index)
                }
                addView(view)
            }
        }
    }
}
