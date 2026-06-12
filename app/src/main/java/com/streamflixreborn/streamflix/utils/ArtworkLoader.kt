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
import com.streamflixreborn.streamflix.R
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

/**
 * Poster in list/grid: 300×450 cap + RGB_565 (2 bytes/px instead of 4).
 * 2026-05-21 (user "tu peux réduire la qualité des jaquettes sur TV si ça aide,
 *   la netteté a de la marge") : baissé de 400×600 → 300×450. Les cartes font
 *   ~250 px de large sur une TV 1080p → 300 px reste sur-échantillonné (net),
 *   et on économise ~25-40% de RAM par poster. Le téléchargement (TMDB w342)
 *   ne change pas — le but ici est la mémoire, pas la vitesse.
 */
private val POSTER_LIST_OPTIONS = RequestOptions()
    .override(342, 513)
    .format(DecodeFormat.PREFER_RGB_565)
    .placeholder(R.drawable.glide_fallback_cover)
    .error(R.drawable.glide_fallback_cover)

/** Banner/backdrop: 1280×720 cap + RGB_565 */
private val BANNER_OPTIONS = RequestOptions()
    .override(1280, 720)
    .format(DecodeFormat.PREFER_RGB_565)
    .placeholder(R.drawable.glide_fallback_cover)
    .error(R.drawable.glide_fallback_cover)

/**
 * Optimiseur d'URL d'image CENTRAL — 2026-05-20 (user "les jaquettes mettent
 *   trop de temps à charger, c'est général à tous les providers, surtout sur
 *   Chromecast"). Le vrai goulot = le POIDS téléchargé (AnimeSama ~680 Ko/cover,
 *   TMDB w500 ~100 Ko). Glide.override() ne réduit QUE le décodage, pas le
 *   download → on réduit la taille à la SOURCE :
 *   - blank / schéma local (android.resource, file, content, data) / déjà-weserv → inchangé
 *   - TMDB → réduit dans l'URL native, gratuit & CDN rapide : w342 poster, w780 banner
 *   - autres http(s) (covers de sites souvent full-res) → resize webp via proxy
 *     weserv (préfixe ssl: pour les origines https).
 * targetWidthPx = largeur d'affichage cible (~400 poster, ~800 bannière).
 */
/** 2026-06-12 (user "toutes les chaines World TV ont la meme pub XXX
 *  dans le carrousel hero") : blacklist d hosts connus pour servir des
 *  bandeaux publicitaires adultes. Ces hosts peuvent etre injectes par
 *  les playlists IPTV communautaires comme URL de logo/banner et imposer
 *  un overlay pub XXX dans le swiper. On retourne null → Glide tombe sur
 *  le placeholder nom de chaine. */
private val ADULT_AD_HOSTS = listOf(
    "mycamtv", "livejasmin", "xcam", "sexcam", "bongacams",
    "chaturbate", "camsoda", "stripchat", "cams.com",
    "xnxx", "xvideos", "pornhub", "redtube", "youporn",
    "spankbang", "tnaflix", "hclips", "porn.com",
)

private fun isAdultAdHost(url: String): Boolean {
    val lower = url.lowercase()
    return ADULT_AD_HOSTS.any { lower.contains(it) }
}

fun optimizeArtworkUrl(url: String?, targetWidthPx: Int): String? {
    if (url.isNullOrBlank()) return url
    if (url.contains("images.weserv.nl")) return url
    val isHttp = url.startsWith("http://")
    val isHttps = url.startsWith("https://")
    if (!isHttp && !isHttps) return url
    // 2026-06-12 — Si l URL provient d un host pub XXX connu (banner
    // hijacké), on retourne null pour que Glide bascule sur le placeholder.
    if (isAdultAdHost(url)) return null
    if (url.contains("image.tmdb.org/t/p/")) {
        // 2026-05-21 : ajout palier w1280 pour la bannière hero plein écran
        //   (1080p+ sur grande TV) — net mais bien plus léger que /original/.
        //   Les appelants existants (poster 400→w342, banner carte 800→w780)
        //   restent inchangés.
        val size = when {
            targetWidthPx >= 1000 -> "w1280"
            targetWidthPx >= 600 -> "w780"
            targetWidthPx >= 280 -> "w342"
            else -> "w185"
        }
        return url.replace(Regex("/t/p/(?:w\\d+|original)/"), "/t/p/$size/")
    }
    // 2026-05-21 : hôtes Cloudflare-protégés — weserv se fait bloquer par CF quand
    // il tente de récupérer l'image côté serveur → jaquettes grises. On les charge
    // en DIRECT via Glide (OkHttp CF-aware), comme avant le fix weserv global.
    if (url.contains("voirdrama.") || url.contains("dramacool")
        || url.contains("voir-anime") || url.contains("voiranime")) return url
    val noScheme = url.substringAfter("://")
    val originParam = if (isHttps) "ssl:$noScheme" else noScheme
    return "https://images.weserv.nl/?url=" +
        java.net.URLEncoder.encode(originParam, "UTF-8") +
        "&w=$targetWidthPx&output=webp&q=82"
}

/** Délais de retry croissants (ms) pour les jaquettes qui échouent au 1er chargement. */
private val ARTWORK_RETRY_DELAYS = longArrayOf(3_000, 8_000, 20_000)

private fun ImageView.loadRecoverableArtwork(
    initialUrl: String?,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable>,
    onRepair: (staleUrl: String, onUpdated: (String) -> Unit) -> Unit,
    memoryOptions: RequestOptions? = null,
    targetWidthPx: Int = 400,
) {
    var hasRequestedRepairForBlankUrl = false
    var retryCount = 0
    // 2026-05-21 : une fois basculé sur l'URL DIRECTE (sans proxy weserv), on ne
    //   re-tente plus le proxy pour cette vue.
    var triedDirect = false

    fun submit(url: String?, isRetry: Boolean = false, useDirect: Boolean = false) {
        val requestedUrl = url
        // Guard against loading into a destroyed activity
        val activity = (context as? android.app.Activity)
        if (activity != null && activity.isDestroyed) return
        // 2026-05-21 : ce garde ne doit s'appliquer QU'aux retries. Sur une grille,
        //   une vue est bindée AVANT d'être attachée à la fenêtre → bloquer le
        //   chargement INITIAL ici donnait des jaquettes grises partout (carré vide).
        //   Glide gère très bien le chargement dans une vue RecyclerView pas encore
        //   attachée (il attend le layout). On ne garde le check que pour les retries
        //   asynchrones (où la vue a pu être recyclée entre-temps).
        if (isRetry && !isAttachedToWindow) return

        if (requestedUrl.isNullOrBlank() && !hasRequestedRepairForBlankUrl) {
            hasRequestedRepairForBlankUrl = true
            onRepair("") { refreshedUrl ->
                if (!isAttachedToWindow || refreshedUrl.isBlank()) return@onRepair
                submit(refreshedUrl)
            }
        }

        // Pour les retries, invalider le cache Glide de cette URL pour forcer un re-fetch réseau.
        // useDirect = on charge l'URL BRUTE (sans le proxy weserv) — 2e appel de secours
        // quand le proxy a échoué (covers AnimeSama, hôtes que weserv refuse).
        val optimizedUrl = if (useDirect) requestedUrl else optimizeArtworkUrl(requestedUrl, targetWidthPx)
        var base = Glide.with(this).load(optimizedUrl).let { req ->
            if (memoryOptions != null) req.apply(memoryOptions) else req
        }
        if (isRetry) {
            base = base.skipMemoryCache(true)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
        }
        configure(base)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    // 0) 2e APPEL EN DIRECT (sans proxy weserv) dès le 1er échec, si le
                    //    chargement passait par le proxy/resize. Les covers AnimeSama via
                    //    weserv chokent parfois → l'URL brute (OkHttp+DoH, CF-aware) passe
                    //    souvent là où weserv a échoué. (idée user « faire 2 appels ».)
                    if (!triedDirect && !useDirect && !requestedUrl.isNullOrBlank()
                        && optimizeArtworkUrl(requestedUrl, targetWidthPx) != requestedUrl) {
                        triedDirect = true
                        // 2026-05-21 (FIX CRASH) : NE JAMAIS appeler submit()/into()
                        //   directement dans onLoadFailed — Glide lève
                        //   IllegalStateException ("You can't start or clear loads in
                        //   RequestListener or Target callbacks") → crash de l'app dès
                        //   qu'une jaquette échoue (AnimeSama, etc.), mobile ET TV.
                        //   On poste le repli sur le thread principal (recommandation Glide).
                        post {
                            if (isAttachedToWindow) submit(requestedUrl, useDirect = true)
                        }
                        return false
                    }

                    // 1) Tenter une réparation (nouvelle URL TMDB)
                    if (ArtworkRepairCoordinator.shouldRepair(requestedUrl, e)) {
                        onRepair(requestedUrl.orEmpty()) { refreshedUrl ->
                            if (!isAttachedToWindow) return@onRepair
                            if (refreshedUrl.isNotBlank() && refreshedUrl != requestedUrl) {
                                retryCount = 0 // nouvelle URL → reset compteur
                                triedDirect = false // nouvelle URL → on pourra re-tenter direct
                                submit(refreshedUrl)
                            }
                        }
                    }

                    // 2) Retry automatique avec backoff (en direct si on a déjà basculé direct)
                    if (retryCount < ARTWORK_RETRY_DELAYS.size && !requestedUrl.isNullOrBlank()) {
                        val delayMs = ARTWORK_RETRY_DELAYS[retryCount]
                        retryCount++
                        val retryDirect = triedDirect
                        postDelayed({
                            if (isAttachedToWindow) {
                                submit(requestedUrl, isRetry = true, useDirect = retryDirect)
                            }
                        }, delayMs)
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    retryCount = 0 // succès → reset pour un éventuel rebind
                    return false
                }
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
    // Fallback poster si pas de banner (certains providers n'ont pas de backdrop)
    val bannerUrl = movie.banner.takeUnless { it.isNullOrBlank() } ?: movie.poster
    loadRecoverableArtwork(bannerUrl, configure, memoryOptions = BANNER_OPTIONS, targetWidthPx = 1280, onRepair = { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairMovieArtwork(this, movie, staleUrl) { refreshedMovie ->
            val refreshedUrl = refreshedMovie.banner.takeUnless { it.isNullOrBlank() }
                ?: refreshedMovie.poster
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
    // Fallback : poster dernière saison > poster série si pas de banner
    val bannerUrl = tvShow.banner.takeUnless { it.isNullOrBlank() }
        ?: tvShow.seasons.lastOrNull()?.poster?.takeUnless { it.isNullOrBlank() }
        ?: tvShow.poster
    loadRecoverableArtwork(bannerUrl, configure, memoryOptions = BANNER_OPTIONS, targetWidthPx = 1280, onRepair = { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairTvShowArtwork(this, tvShow, staleUrl) { refreshedTvShow ->
            val refreshedUrl = refreshedTvShow.banner.takeUnless { it.isNullOrBlank() }
                ?: refreshedTvShow.seasons.lastOrNull()?.poster?.takeUnless { it.isNullOrBlank() }
                ?: refreshedTvShow.poster
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
