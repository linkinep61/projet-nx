package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 2026-06-08 (user "tu le dupliques à l'identique, juste ajouter les 2
 * sections, je veux pas me retaper tous les réglages") : DUPLICATE EXACT
 * du LiveTvHubProvider + ajout des sections extras (World Live TV +
 * 2e playlist à venir).
 *
 * Tous les IDs des chaînes utilisent le PRÉFIXE COMMUN `livehub::*` (= pas
 * un préfixe distinct) → toutes les prefs/bans/favoris/réglages de l'user
 * dans TV Hub s'appliquent AUTOMATIQUEMENT à World Live. Le providerName
 * est aussi "TV Hub" pour partager les SharedPrefs.
 *
 * Seuls le `name` affiché et le `logo` du provider diffèrent (= différencier
 * dans le picker).
 *
 * Tout est délégué à LiveTvHubProvider — ce provider est un simple wrapper
 * qui ajoute en bas les sections World Live TV.
 */
object LiveTvHubPlusProvider : Provider, IptvProvider, ProgressiveHomeProvider {

    override val name = "World Live"
    override val baseUrl = "https://box.xtemus.com"
    override val logo: String =
        "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_worldlive"
    override val language = "fr"

    private const val TAG = "WorldLive"

    private val _additionalServersFlow = MutableSharedFlow<Video.Server>()
    override val additionalServersFlow: SharedFlow<Video.Server> = _additionalServersFlow.asSharedFlow()

    override suspend fun getHome(): List<Category> {
        val sections = mutableListOf<Category>()

        // 2026-06-08 (user "tu gardes QUE les 2 derniers ajouts") : on ne
        //   duplique PAS le contenu de TV Hub (France TV/Dric4rTV/OTF/etc.).
        //   On affiche UNIQUEMENT les ajouts spécifiques à World Live.
        //
        //   Les IDs et providerName restent dans la famille TV Hub (`livehub::*`
        //   + "TV Hub") pour que les réglages utilisateurs (bans, favoris,
        //   prefs player) soient partagés avec TV Hub principal — l'user n'a
        //   rien à reconfigurer.

        // 2026-06-09 (user "on va tout condenser comme dans les IPTV, on fait
        //   une liste, on clique dessus, on choisit la catégorie") : picker
        //   catégorie style Vavoo. Une SEULE section dans le home dont le
        //   contenu dépend de la catégorie choisie.
        //
        //   "Toutes les chaînes" (cat=all) → tout aplati dans 1 section.
        //   "France/Cinéma/Sport/..." → filtre par keywords sur le group-title.
        try {
            val wltvSections = WorldLiveTvProvider.getHome()
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            val currentCode = WorldLiveCategorySettings.getCurrentCode(ctx)
            val currentLabel = WorldLiveCategorySettings.getCurrentLabel(ctx)
            val isAll = currentCode == WorldLiveCategorySettings.ALL_CODE

            // 2026-06-10 (user "une fois la source chargée elle n'affiche pas
            //   directement toutes les chaînes Toutes les catégories — avec
            //   chargement différé pour éviter les crashes, et nous on peut
            //   sélectionner") :
            //   - Mode "all" → toutes les catégories en sections séparées
            //     (= comme Wiseplay). RecyclerView gère le lazy load des
            //     ViewHolders donc pas de crash même sur 16k chaînes.
            //   - Mode "catégorie X" → seulement cette section (= picker actif).
            //
            //   Cap MAX_PER_SECTION pour éviter qu'une section géante
            //   (ex: Github IPTV ~10000) plombe l'init. Les chaînes au-delà
            //   du cap restent accessibles via l'onglet "Toutes les chaînes".
            val allChannelsMap = mutableMapOf<String, TvShow>()
            val sectionsBuffer = mutableListOf<Category>()

            for (cat in wltvSections) {
                val matchesCategory = WorldLiveCategorySettings.matches(ctx, cat.name)
                val channelsInSection = mutableListOf<TvShow>()
                (cat.list as? List<*>)?.forEach { item ->
                    val tv = item as? TvShow ?: return@forEach
                    val newId = "livehub::worldlivetv::${tv.id}"
                    if (com.streamflixreborn.streamflix.fragments.player.settings
                            .IptvBannedChannels.isBanned(newId)) return@forEach
                    val rebadged = TvShow(id = newId, title = tv.title ?: "").apply {
                        providerName = "World Live"
                        poster = tv.poster
                        banner = tv.banner
                    }
                    val canonical = "wl" + tv.id.substringAfterLast("::").lowercase().trim()
                    allChannelsMap[canonical] = rebadged
                    if (matchesCategory) channelsInSection.add(rebadged)
                }
                if (channelsInSection.isNotEmpty()) {
                    // Cap par section pour éviter crash sur init (16k chaînes).
                    val capped = if (channelsInSection.size > MAX_PER_SECTION)
                        channelsInSection.take(MAX_PER_SECTION) else channelsInSection
                    sectionsBuffer.add(
                        Category(
                            name = if (isAll) cat.name else currentLabel,
                            list = capped,
                        )
                    )
                }
            }

            // Section "Favoris" en haut si l'user en a marqué.
            try {
                // 2026-06-10 (user "favoris dans folder pas affichés") :
                //   ajoute les chaînes des folders à allChannelsMap pour
                //   résoudre les favoris marqués via le dialog folder.
                WorldLiveTvProvider.folderContents.values.forEach { items ->
                    items.forEach { ch ->
                        if (ch.isFolder) return@forEach
                        val newId = "livehub::worldlivetv::${ch.id}"
                        val rebadged = TvShow(id = newId, title = ch.name).apply {
                            providerName = "World Live"
                            poster = ch.logo
                            banner = ch.logo
                        }
                        val canonical = "wl" + ch.id.substringAfterLast("::").lowercase().trim()
                        allChannelsMap.putIfAbsent(canonical, rebadged)
                    }
                }
                val favKeys = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                    .getFavorites("World Live")
                // 2026-06-10 (user "favoris dans folder ne s'affiche pas") :
                //   fallback metadata cache si la chaîne n'est pas dans
                //   allChannelsMap (= folderContents pas encore re-rempli).
                val favShows = favKeys.mapNotNull { canonical ->
                    allChannelsMap[canonical] ?: run {
                        try {
                            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
                            val prefs = ctx.getSharedPreferences(
                                "world_live_fav_meta", android.content.Context.MODE_PRIVATE,
                            )
                            val cachedName = prefs.getString("${canonical}_name", null)
                                ?: return@run null
                            val cachedLogo = prefs.getString("${canonical}_logo", "")
                            // Reconstruct un id minimal — le canonical commence
                            //   par "wl", on récupère le slug derrière.
                            val slug = canonical.removePrefix("wl")
                            val syntheticId = "livehub::worldlivetv::wltv::cache::$slug"
                            TvShow(id = syntheticId, title = cachedName).apply {
                                providerName = "World Live"
                                poster = cachedLogo
                                banner = cachedLogo
                            }
                        } catch (_: Throwable) { null }
                    }
                }
                if (favShows.isNotEmpty()) {
                    sections.add(Category(name = "Favoris", list = favShows))
                }
            } catch (_: Throwable) {}

            // Mode all → toutes les sections. Mode catégorie → on les fusionne
            //   en une seule section "currentLabel" (= comportement avant).
            if (isAll) {
                sections.addAll(sectionsBuffer)
            } else {
                val flat = sectionsBuffer.flatMap {
                    (it.list as? List<*>)?.filterIsInstance<TvShow>() ?: emptyList()
                }
                if (flat.isNotEmpty()) {
                    sections.add(
                        Category(
                            name = currentLabel,
                            list = if (flat.size > MAX_PER_SECTION)
                                flat.take(MAX_PER_SECTION) else flat,
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "World Live TV getHome failed: ${t.message}")
        }

        return sections
    }

    /** Cap de chaînes par section sur le home. Évite qu'une catégorie géante
     *  (ex: Github IPTV 10k chaînes) crashe l'init. Les chaînes au-delà
     *  restent accessibles via l'onglet "Toutes les chaînes". */
    private const val MAX_PER_SECTION = 100

    /** 2026-06-10 (user "chargement différé qui arrive au fur et à mesure") :
     *  délègue au WorldLiveTvProvider.getHomeProgressive() avec un map pour
     *  ré-appliquer le rebadge (livehub::worldlivetv::*) + favoris + filtre
     *  catégorie. Chaque émission est ré-buildée comme un home complet. */
    override fun getHomeProgressive(): kotlinx.coroutines.flow.Flow<List<Category>> {
        return kotlinx.coroutines.flow.flow {
            WorldLiveTvProvider.getHomeProgressive().collect { wltvSections ->
                emit(rebuildSectionsFromWltv(wltvSections))
            }
        }
    }

    private fun rebuildSectionsFromWltv(wltvSections: List<Category>): List<Category> {
        val sections = mutableListOf<Category>()
        try {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            val currentCode = WorldLiveCategorySettings.getCurrentCode(ctx)
            val currentLabel = WorldLiveCategorySettings.getCurrentLabel(ctx)
            val isAll = currentCode == WorldLiveCategorySettings.ALL_CODE
            val allChannelsMap = mutableMapOf<String, TvShow>()
            val sectionsBuffer = mutableListOf<Category>()
            for (cat in wltvSections) {
                val matchesCategory = WorldLiveCategorySettings.matches(ctx, cat.name)
                val channelsInSection = mutableListOf<TvShow>()
                (cat.list as? List<*>)?.forEach { item ->
                    val tv = item as? TvShow ?: return@forEach
                    val newId = "livehub::worldlivetv::${tv.id}"
                    if (com.streamflixreborn.streamflix.fragments.player.settings
                            .IptvBannedChannels.isBanned(newId)) return@forEach
                    val rebadged = TvShow(id = newId, title = tv.title ?: "").apply {
                        providerName = "World Live"
                        poster = tv.poster
                        banner = tv.banner
                    }
                    val canonical = "wl" + tv.id.substringAfterLast("::").lowercase().trim()
                    allChannelsMap[canonical] = rebadged
                    if (matchesCategory) channelsInSection.add(rebadged)
                }
                if (channelsInSection.isNotEmpty()) {
                    val capped = if (channelsInSection.size > MAX_PER_SECTION)
                        channelsInSection.take(MAX_PER_SECTION) else channelsInSection
                    sectionsBuffer.add(
                        Category(
                            name = if (isAll) cat.name else currentLabel,
                            list = capped,
                        )
                    )
                }
            }
            try {
                // 2026-06-10 (user "favoris ajouté dans le folder n'apparaît
                //   pas sur le home") : ajoute les chaînes DES FOLDERS à
                //   allChannelsMap pour que les favoris résolvent même quand
                //   l'user a marqué une chaîne via le dialog folder.
                WorldLiveTvProvider.folderContents.values.forEach { items ->
                    items.forEach { ch ->
                        if (ch.isFolder) return@forEach
                        val newId = "livehub::worldlivetv::${ch.id}"
                        val rebadged = TvShow(id = newId, title = ch.name).apply {
                            providerName = "World Live"
                            poster = ch.logo
                            banner = ch.logo
                        }
                        val canonical = "wl" + ch.id.substringAfterLast("::").lowercase().trim()
                        // Ne pas overwrite si déjà présent dans top-level.
                        allChannelsMap.putIfAbsent(canonical, rebadged)
                    }
                }
                val favKeys = com.streamflixreborn.streamflix.utils.IptvFavoritesStore
                    .getFavorites("World Live")
                // 2026-06-10 (user "favoris dans folder ne s'affiche pas") :
                //   fallback metadata cache si la chaîne n'est pas dans
                //   allChannelsMap (= folderContents pas encore re-rempli).
                val favShows = favKeys.mapNotNull { canonical ->
                    allChannelsMap[canonical] ?: run {
                        try {
                            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
                            val prefs = ctx.getSharedPreferences(
                                "world_live_fav_meta", android.content.Context.MODE_PRIVATE,
                            )
                            val cachedName = prefs.getString("${canonical}_name", null)
                                ?: return@run null
                            val cachedLogo = prefs.getString("${canonical}_logo", "")
                            // Reconstruct un id minimal — le canonical commence
                            //   par "wl", on récupère le slug derrière.
                            val slug = canonical.removePrefix("wl")
                            val syntheticId = "livehub::worldlivetv::wltv::cache::$slug"
                            TvShow(id = syntheticId, title = cachedName).apply {
                                providerName = "World Live"
                                poster = cachedLogo
                                banner = cachedLogo
                            }
                        } catch (_: Throwable) { null }
                    }
                }
                if (favShows.isNotEmpty()) {
                    sections.add(Category(name = "Favoris", list = favShows))
                }
            } catch (_: Throwable) {}
            if (isAll) {
                sections.addAll(sectionsBuffer)
            } else {
                val flat = sectionsBuffer.flatMap {
                    (it.list as? List<*>)?.filterIsInstance<TvShow>() ?: emptyList()
                }
                if (flat.isNotEmpty()) {
                    sections.add(
                        Category(
                            name = currentLabel,
                            list = if (flat.size > MAX_PER_SECTION)
                                flat.take(MAX_PER_SECTION) else flat,
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "rebuildSectionsFromWltv failed: ${t.message}")
        }
        return sections
    }

    // Tout le reste = délégué à LiveTvHubProvider (qui sait gérer
    //   `livehub::*` y compris `livehub::worldlivetv::*` qu'on a re-câblé).

    override suspend fun getTvShows(page: Int): List<TvShow> {
        // Onglet "Toutes les chaînes" du provider World Live : uniquement
        //   les chaînes World Live TV (pas TV Hub).
        if (page > 1) return emptyList()
        return try {
            WorldLiveTvProvider.getTvShows(0).map { tv ->
                TvShow(id = "livehub::worldlivetv::${tv.id}", title = tv.title).apply {
                    providerName = "World Live"
                    poster = tv.poster
                    banner = tv.banner
                }
            }
        } catch (_: Throwable) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun search(query: String, page: Int): List<TvShow> {
        if (page > 1) return emptyList()
        return try {
            WorldLiveTvProvider.search(query, 0).map { tv ->
                TvShow(id = "livehub::worldlivetv::${tv.id}", title = tv.title).apply {
                    providerName = "World Live"
                    poster = tv.poster
                    banner = tv.banner
                }
            }
        } catch (_: Throwable) { emptyList() }
    }

    override suspend fun getMovie(id: String): Movie =
        throw UnsupportedOperationException("World Live n'a pas de films")

    override suspend fun getTvShow(id: String): TvShow {
        return try {
            LiveTvHubProvider.getTvShow(id)
        } catch (e: Exception) {
            Log.w(TAG, "getTvShow failed for $id: ${e.message}")
            TvShow(id = id, title = id).apply { providerName = "World Live" }
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return try { LiveTvHubProvider.getEpisodesBySeason(seasonId) } catch (_: Throwable) { emptyList() }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return try { LiveTvHubProvider.getGenre(id, page) } catch (_: Throwable) { Genre(id = id, name = id) }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return try { LiveTvHubProvider.getPeople(id, page) } catch (_: Throwable) { People(id = id, name = "") }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            LiveTvHubProvider.getServers(id, videoType)
        } catch (e: Exception) {
            Log.w(TAG, "getServers failed for $id: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return LiveTvHubProvider.getVideo(server)
    }

    // ─────────────────────────────────────────────────────────────────
    // 2026-06-09 (user "il n'y a rien dans la liste des chaînes" +
    //   "n'oublie pas le bouton chaîne suivante des chaînes précédentes")
    //   Helpers pour le panel D-pad LEFT et boutons prev/next sur le
    //   player TV. Patterns calqués sur les autres providers IPTV.
    //
    //   Liste ordonnée = toutes les chaînes de la catégorie courante
    //   (= ce que getHome() retourne). Cache local rempli en même temps
    //   pour que `getChannelDisplayName/Poster` restent synchrones.
    // ─────────────────────────────────────────────────────────────────

    private data class CachedChannel(val name: String, val poster: String?)
    private val channelMetaCache = mutableMapOf<String, CachedChannel>()

    /** IDs des chaînes World Live dans l'ordre du home (catégorie courante).
     *  Préfixés `livehub::worldlivetv::` pour rester cohérents avec les IDs
     *  exposés au reste de l'app. */
    suspend fun getOrderedChannelIds(): List<String> {
        return try {
            val wltvSections = WorldLiveTvProvider.getHome()
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
            val ids = mutableListOf<String>()
            channelMetaCache.clear()
            for (cat in wltvSections) {
                if (!WorldLiveCategorySettings.matches(ctx, cat.name)) continue
                (cat.list as? List<*>)?.forEach { item ->
                    val tv = item as? TvShow ?: return@forEach
                    val newId = "livehub::worldlivetv::${tv.id}"
                    if (com.streamflixreborn.streamflix.fragments.player.settings
                            .IptvBannedChannels.isBanned(newId)) return@forEach
                    if (channelMetaCache.containsKey(newId)) return@forEach
                    ids.add(newId)
                    channelMetaCache[newId] = CachedChannel(
                        name = tv.title ?: "",
                        poster = tv.poster,
                    )
                }
            }
            ids
        } catch (t: Throwable) {
            Log.w(TAG, "getOrderedChannelIds failed: ${t.message}")
            emptyList()
        }
    }

    fun getChannelDisplayName(id: String): String? =
        channelMetaCache[id]?.name

    fun getChannelPoster(id: String): String? =
        channelMetaCache[id]?.poster
}
