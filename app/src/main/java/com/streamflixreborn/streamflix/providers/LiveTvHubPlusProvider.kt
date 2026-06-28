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

    // 2026-06-20 (user "remettre les dossiers, reconnecté au TV hub") :
    //   mode DOSSIER — chaque catégorie M3U (Premium FR, Sport, TNT France,
    //   etc.) devient un dossier cliquable, comme dans TV Hub. Au clic,
    //   LiveHubFolderDialog affiche les chaînes du dossier. Plus de crash
    //   sur les petits appareils car le home n'affiche que ~20 cards dossier
    //   au lieu de 500+ cards chaînes.
    //
    //   Les contenus des dossiers sont stockés dans
    //   LiveTvHubProvider.folderContents (= même registre que TV Hub) avec
    //   des clés préfixées "wl_" pour éviter tout conflit avec TV Hub.

    override suspend fun getHome(): List<Category> {
        return try {
            val wltvSections = WorldLiveTvProvider.getHome()
            buildFoldersFromWltv(wltvSections)
        } catch (t: Throwable) {
            Log.w(TAG, "World Live TV getHome failed: ${t.message}")
            emptyList()
        }
    }

    override fun getHomeProgressive(): kotlinx.coroutines.flow.Flow<List<Category>> {
        return kotlinx.coroutines.flow.flow {
            WorldLiveTvProvider.getHomeProgressive().collect { wltvSections ->
                emit(buildFoldersFromWltv(wltvSections))
            }
        }
    }

    /**
     * Groupe les sections World Live en dossiers. Chaque catégorie du M3U
     * (Premium FR, Sport, etc.) = un dossier. Stocke le contenu dans
     * [LiveTvHubProvider.folderContents] pour que [LiveHubFolderDialog]
     * puisse l'afficher au clic (= même pipeline que TV Hub).
     */
    private fun buildFoldersFromWltv(wltvSections: List<Category>): List<Category> {
        val sections = mutableListOf<Category>()
        val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance
        val allChannelsMap = mutableMapOf<String, TvShow>()
        val folderShows = mutableListOf<TvShow>()

        // 2026-06-23 : catégorie sélectionnée via le picker globe
        val selectedCode = WorldLiveCategorySettings.getCurrentCode(ctx)
        val filterActive = selectedCode != WorldLiveCategorySettings.ALL_CODE

        // Quand une catégorie est sélectionnée, on affiche ses chaînes
        // directement au lieu des folder cards.
        val filteredChannels = mutableListOf<TvShow>()

        for (cat in wltvSections) {
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
                channelsInSection.add(rebadged)
            }
            if (channelsInSection.isEmpty()) continue

            // Clé dossier unique : wl_<catName_sanitized>
            val folderKey = "wl_" + cat.name.lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')

            // Stocke dans LiveTvHubProvider.folderContents (= même registre
            //   que TV Hub). LiveHubFolderDialog y accède au clic.
            LiveTvHubProvider.folderContents[folderKey] = listOf(
                Category(name = cat.name, list = channelsInSection)
            )

            // Si filtre actif et cette catégorie matche → collecter les chaînes
            if (filterActive && cat.name.equals(selectedCode, ignoreCase = true)) {
                filteredChannels.addAll(channelsInSection)
            }

            // Card dossier (pour le mode "Toutes les chaînes")
            val count = channelsInSection.size
            folderShows.add(
                TvShow(
                    id = "livehub::folder::$folderKey",
                    title = "📁 ${cat.name} ($count)",
                ).apply {
                    providerName = "World Live"
                    // Jaquette = logo de la 1re chaîne du dossier
                    poster = channelsInSection.firstOrNull()?.poster
                    banner = channelsInSection.firstOrNull()?.banner
                }
            )
        }

        // Section Favoris en haut si l'user en a marqué.
        try {
            // Ajoute les chaînes des folders WorldLive à allChannelsMap
            //   pour résoudre les favoris marqués via le dialog folder.
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
            val favShows = favKeys.mapNotNull { canonical ->
                allChannelsMap[canonical] ?: run {
                    try {
                        val prefs = ctx.getSharedPreferences(
                            "world_live_fav_meta", android.content.Context.MODE_PRIVATE,
                        )
                        val cachedName = prefs.getString("${canonical}_name", null)
                            ?: return@run null
                        val cachedLogo = prefs.getString("${canonical}_logo", "")
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

        if (filterActive) {
            // Catégorie sélectionnée → chaînes réparties en lignes
            // verticales pour remplir l'écran (on scroll vers le bas).
            if (filteredChannels.isNotEmpty()) {
                val isTvFilter = com.streamflixreborn.streamflix.BuildConfig.APP_LAYOUT == "tv"
                // TV : 7 chaînes/ligne (remplit la largeur d'un grand écran)
                // Mobile : 8 chaînes/ligne
                val perLineFilter = if (isTvFilter) 7 else 8
                filteredChannels.chunked(perLineFilter).forEach { chunk ->
                    sections.add(Category(name = selectedCode, list = chunk))
                }
            }
        } else {
            // "Toutes les chaînes" → dossiers répartis sur plusieurs lignes
            // 2026-06-23 : on fixe le nombre PAR LIGNE (pas le nombre de
            //   lignes) pour que chaque ligne soit bien remplie.
            //   TV = 7 dossiers/ligne, mobile = 3 lignes.
            if (folderShows.isNotEmpty()) {
                val isTv = com.streamflixreborn.streamflix.BuildConfig.APP_LAYOUT == "tv"
                val perLine = if (isTv) 7 else {
                    (folderShows.size + 2) / 3  // 3 lignes sur mobile
                }
                folderShows.chunked(perLine).forEach { chunk ->
                    sections.add(Category(name = "📁 Dossiers", list = chunk))
                }
            }
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
