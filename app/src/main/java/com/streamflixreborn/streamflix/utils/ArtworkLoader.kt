package com.streamflixreborn.streamflix.utils

import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private object ArtworkRepairCoordinator {
    private val repairScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightRepairs = ConcurrentHashMap.newKeySet<String>()

    fun shouldRepair(url: String?, error: GlideException?): Boolean {
        return ArtworkRepair.shouldRepair(url, error)
    }

    fun repairMovieArtwork(
        imageView: ImageView,
        movie: Movie,
        staleUrl: String,
        onUpdated: (Movie) -> Unit,
    ) {
        val provider = UserPreferences.currentProvider ?: return
        val repairKey = "${provider.name}|movie|${movie.id}|$staleUrl"
        if (!inFlightRepairs.add(repairKey)) return

        repairScope.launch {
            try {
                val database = AppDatabase.getInstance(imageView.context)
                val refreshedMovie = ArtworkRepair.repairMovie(
                    context = imageView.context,
                    provider = provider,
                    database = database,
                    movie = movie,
                ) ?: return@launch

                imageView.post {
                    movie.poster = refreshedMovie.poster
                    movie.banner = refreshedMovie.banner
                    onUpdated(refreshedMovie)
                }
            } finally {
                inFlightRepairs.remove(repairKey)
            }
        }
    }

    fun repairTvShowArtwork(
        imageView: ImageView,
        tvShow: TvShow,
        staleUrl: String,
        onUpdated: (TvShow) -> Unit,
    ) {
        val provider = UserPreferences.currentProvider ?: return
        val repairKey = "${provider.name}|tv|${tvShow.id}|$staleUrl"
        if (!inFlightRepairs.add(repairKey)) return

        repairScope.launch {
            try {
                val database = AppDatabase.getInstance(imageView.context)
                val refreshedTvShow = ArtworkRepair.repairTvShow(
                    context = imageView.context,
                    provider = provider,
                    database = database,
                    tvShow = tvShow,
                ) ?: return@launch

                imageView.post {
                    tvShow.poster = refreshedTvShow.poster
                    tvShow.banner = refreshedTvShow.banner
                    onUpdated(refreshedTvShow)
                }
            } finally {
                inFlightRepairs.remove(repairKey)
            }
        }
    }
}

/** Poster in list/grid: 400×600 cap + RGB_565 (2 bytes/px instead of 4) */
private val POSTER_LIST_OPTIONS = RequestOptions()
    .override(400, 600)
    .format(DecodeFormat.PREFER_RGB_565)

/** Banner/backdrop: 800×450 cap + RGB_565 */
private val BANNER_OPTIONS = RequestOptions()
    .override(800, 450)
    .format(DecodeFormat.PREFER_RGB_565)

private fun ImageView.loadRecoverableArtwork(
    initialUrl: String?,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable>,
    onRepair: (staleUrl: String, onUpdated: (String) -> Unit) -> Unit,
    memoryOptions: RequestOptions? = null,
) {
    var hasRequestedRepairForBlankUrl = false

    fun submit(url: String?) {
        val requestedUrl = url
        if (requestedUrl.isNullOrBlank() && !hasRequestedRepairForBlankUrl) {
            hasRequestedRepairForBlankUrl = true
            onRepair("") { refreshedUrl ->
                if (!isAttachedToWindow || refreshedUrl.isBlank()) return@onRepair
                submit(refreshedUrl)
            }
        }

        val base = Glide.with(this).load(requestedUrl).let { req ->
            if (memoryOptions != null) req.apply(memoryOptions) else req
        }
        configure(base)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    if (!ArtworkRepairCoordinator.shouldRepair(requestedUrl, e)) {
                        return false
                    }

                    onRepair(requestedUrl.orEmpty()) { refreshedUrl ->
                        if (!isAttachedToWindow) return@onRepair
                        submit(refreshedUrl)
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ) = false
            })
            .into(this)
    }

    submit(initialUrl)
}

fun ImageView.loadMoviePoster(
    movie: Movie,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(movie.poster, configure, memoryOptions = POSTER_LIST_OPTIONS, onRepair = { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairMovieArtwork(this, movie, staleUrl) { refreshedMovie ->
            val refreshedUrl = refreshedMovie.poster
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    })
}

fun ImageView.loadMovieBanner(
    movie: Movie,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(movie.banner, configure, memoryOptions = BANNER_OPTIONS, onRepair = { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairMovieArtwork(this, movie, staleUrl) { refreshedMovie ->
            val refreshedUrl = refreshedMovie.banner
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    })
}

fun ImageView.loadTvShowPoster(
    tvShow: TvShow,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(tvShow.poster, configure, memoryOptions = POSTER_LIST_OPTIONS, onRepair = { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairTvShowArtwork(this, tvShow, staleUrl) { refreshedTvShow ->
            val refreshedUrl = refreshedTvShow.poster
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    })
}

fun ImageView.loadTvShowBanner(
    tvShow: TvShow,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(tvShow.banner, configure, memoryOptions = BANNER_OPTIONS, onRepair = { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairTvShowArtwork(this, tvShow, staleUrl) { refreshedTvShow ->
            val refreshedUrl = refreshedTvShow.banner
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    })
}

fun ImageView.loadTvShowCardArtwork(
    tvShow: TvShow,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(tvShow.poster ?: tvShow.banner, configure, memoryOptions = POSTER_LIST_OPTIONS, onRepair = { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairTvShowArtwork(this, tvShow, staleUrl) { refreshedTvShow ->
            val refreshedUrl = refreshedTvShow.poster ?: refreshedTvShow.banner
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    })
}
