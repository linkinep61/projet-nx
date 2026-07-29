package com.streamflixreborn.streamflix.car

import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.streamflixreborn.streamflix.utils.MusicFavoritesStore
import com.streamflixreborn.streamflix.utils.RadioCatalog
import com.streamflixreborn.streamflix.utils.RadioFavoritesStore
import kotlin.concurrent.thread

/**
 * 2026-07-24 — VOIE APP MÉDIA pour Android Auto.
 *
 * Google a mis à jour Android Auto : la voie projection (CarAppService NAVIGATION) est DENIED
 * pour les apps sideloadées (Play-check obligatoire, cf logcat CAR.VALIDATOR). La catégorie MÉDIA
 * (MediaBrowserService) est validée par un AUTRE chemin qui ne fait PAS le Play-check → ONYX
 * réapparaît chez tous les utilisateurs. On expose la Radio (favoris) + Ma playlist musique.
 * Lecture déléguée à CarRadioController (ExoPlayer singleton, survit à la navigation).
 */
@androidx.media3.common.util.UnstableApi
class OnyxMediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat

    // Item en cours de lecture → cible du bouton ★ Favori de l'écran de lecture (AA n'a pas d'appui long).
    private var favKind = 0 // 0=aucun, 1=musique, 2=radio
    private var favMusicUrl: String? = null
    private var favMusicTitle: String? = null
    private var favRadioId: String? = null
    private var favRadioUrl: String? = null
    private var favRadioName: String? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "OnyxMedia").apply {
            setCallback(callback)
            setPlaybackState(stateBuilder(PlaybackStateCompat.STATE_NONE).build())
        }
        sessionToken = session.sessionToken
        session.isActive = true

        // 2026-07-25 (user « suivant ne passe pas à la radio/musique suivante des favoris ») :
        //   à chaque changement de piste (⏭/⏮ du poste, de l'appli ou du volant), on met à jour
        //   la carte « en cours » (titre + pochette/logo) et la cible du bouton ★.
        CarRadioController.onTrackChanged = { _, uri, title ->
            runCatching {
                val t = title ?: return@runCatching
                when (favKind) {
                    2 -> {
                        val u = uri ?: favRadioUrl
                        val logo = (cachedStations ?: emptyList())
                            .firstOrNull { it.streamUrl == u || it.name == t }?.poster
                        if (u != null) setNowPlayingRadio(u, t)
                        markPlaying(t, subtitle = "Radio", artUri = logo)
                    }
                    1 -> {
                        if (uri != null) setNowPlayingMusic(uri, t)
                        markPlaying(t, subtitle = "Musique", artUri = uri?.let { phoneArt[it] })
                    }
                    else -> markPlaying(t)
                }
            }
        }
        Log.i(TAG, "MediaBrowserService créé")
    }

    override fun onDestroy() {
        runCatching { session.release() }
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // ALLOW tout client (voiture) + déclare la RECHERCHE supportée (sinon Android Auto n'affiche
        // pas la loupe → onSearch jamais appelé).
        // 2026-07-25 (user « une interface un peu plus propre, comme Spotify ») : les « content
        // style hints » d'Android Auto — dossiers en GRILLE avec pochettes (comme Spotify) et
        // morceaux/stations en LISTE. Sans ces indices, AA affiche tout en liste terne.
        val extras = Bundle().apply {
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            // ⚠️ Dossiers en LISTE, pas en grille : en grille, les dossiers sans image (les lettres
            //    A, B, C… de « Toutes les radios ») affichent un gros triangle d'alerte à la place
            //    de la vignette. La liste reste propre ; les pochettes servent aux morceaux/stations.
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", CONTENT_STYLE_LIST)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", CONTENT_STYLE_LIST)
        }
        return BrowserRoot(ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach() // chargement en arrière-plan (le catalogue radio est réseau)
        thread {
            val items = ArrayList<MediaBrowserCompat.MediaItem>()
            try {
                when (parentId) {
                    // 2026-07-25 (user « une barre en haut plus propre, plus lisible ») : titres
                    //   COURTS + vraies icônes. Les libellés longs avec emoji étaient tronqués par
                    //   Android Auto (« Musiques… », « Radios fa… », « Toutes le… »).
                    ROOT -> {
                        items.add(browsable(FOLDER_MUSIC, "Playlist", com.streamflixreborn.streamflix.R.drawable.ic_favorite_enable))
                        items.add(browsable(FOLDER_PHONE, "Local", com.streamflixreborn.streamflix.R.drawable.ic_downloads))
                        items.add(browsable(FOLDER_RADIO, "Favoris", com.streamflixreborn.streamflix.R.drawable.ic_radio))
                        items.add(browsable(FOLDER_ALL_RADIOS, "Radios", com.streamflixreborn.streamflix.R.drawable.ic_radio))
                    }
                    // 2026-07-25 (demande user) : musiques stockées SUR LE TÉLÉPHONE, accessibles
                    //   directement depuis la voiture. ★ sur l'écran de lecture = ajout à la playlist.
                    FOLDER_PHONE -> {
                        val local = runCatching {
                            com.streamflixreborn.streamflix.utils.LocalMediaStore.listAudio(applicationContext)
                        }.getOrDefault(emptyList())
                        phoneArt = local.mapNotNull { m -> m.artUri?.let { m.uri to it } }.toMap()
                        // 2026-07-28 (demande user) : si des DOSSIERS favoris ont été marqués sur le
                        //   téléphone, la voiture affiche CES dossiers (curation) + une entrée « Tout ».
                        //   Aucun favori → toute la musique, comme avant (aucune régression).
                        val favDirs = runCatching {
                            com.streamflixreborn.streamflix.utils.LocalMediaFolderStore.all(applicationContext, audio = true)
                        }.getOrDefault(emptySet())
                        val presentFav = favDirs.filter { fav ->
                            local.any { it.dir == fav || it.dir.startsWith("$fav/") }
                        }.sortedBy { it.lowercase() }
                        if (presentFav.isNotEmpty()) {
                            items.add(browsable(FOLDER_PHONE_ALL, "Tout", com.streamflixreborn.streamflix.R.drawable.ic_downloads))
                            presentFav.forEach { dir ->
                                val name = dir.substringAfterLast('/', dir).ifBlank { dir }
                                items.add(browsable("$PHONE_DIR_PREFIX$dir", name))
                            }
                        } else {
                            lastPhoneTracks = local.map { it.uri to it.title }
                            local.take(300).forEach { m ->
                                items.add(playable("music::${m.uri}", m.title, m.artUri))
                            }
                        }
                    }
                    FOLDER_PHONE_ALL -> {
                        val local = runCatching {
                            com.streamflixreborn.streamflix.utils.LocalMediaStore.listAudio(applicationContext)
                        }.getOrDefault(emptyList())
                        phoneArt = local.mapNotNull { m -> m.artUri?.let { m.uri to it } }.toMap()
                        lastPhoneTracks = local.map { it.uri to it.title }
                        local.take(300).forEach { m ->
                            items.add(playable("music::${m.uri}", m.title, m.artUri))
                        }
                    }
                    FOLDER_MUSIC -> MusicFavoritesStore.all().forEach { t ->
                        items.add(playable("music::${t.url}", t.title, null))
                    }
                    FOLDER_RADIO -> {
                        val favIds = runCatching { RadioFavoritesStore.all() }.getOrDefault(emptySet())
                        val favs = allStations().filter { it.id in favIds && !it.streamUrl.isNullOrBlank() }
                        // Contexte de ⏭/⏮ : la liste des favoris (skip = station favorite suivante).
                        lastRadioList = favs.map { it.streamUrl!! to it.name }
                        favs.forEach { s ->
                            items.add(playable("radio::${s.streamUrl}::${s.name}", s.name, s.poster))
                        }
                    }
                    // Toutes les radios → dossiers par 1re lettre (AA plafonne le nb d'items par nœud :
                    // ~2000 stations d'un coup = injouable, donc on groupe A, B, C… #).
                    FOLDER_ALL_RADIOS -> {
                        val letters = allStations()
                            .filter { !it.streamUrl.isNullOrBlank() }
                            .map { groupLetter(it.name) }
                            .distinct().sorted()
                        letters.forEach { l -> items.add(browsable("$GRP_PREFIX$l", l)) }
                    }
                    else -> if (parentId.startsWith(GRP_PREFIX)) {
                        val letter = parentId.removePrefix(GRP_PREFIX)
                        val grp = allStations()
                            .filter { !it.streamUrl.isNullOrBlank() && groupLetter(it.name) == letter }
                            .sortedBy { it.name.lowercase() }
                            .take(200)
                        // Contexte de ⏭/⏮ : les stations de cette lettre.
                        lastRadioList = grp.map { it.streamUrl!! to it.name }
                        grp.forEach { s -> items.add(playable("radio::${s.streamUrl}::${s.name}", s.name, s.poster)) }
                    } else if (parentId.startsWith(PHONE_DIR_PREFIX)) {
                        // Morceaux d'un DOSSIER favori du téléphone (choisi sur le mobile).
                        val dir = parentId.removePrefix(PHONE_DIR_PREFIX)
                        val local = runCatching {
                            com.streamflixreborn.streamflix.utils.LocalMediaStore.listAudio(applicationContext)
                        }.getOrDefault(emptyList())
                            .filter { it.dir == dir || it.dir.startsWith("$dir/") }
                        phoneArt = phoneArt + local.mapNotNull { m -> m.artUri?.let { m.uri to it } }
                        lastPhoneTracks = local.map { it.uri to it.title }
                        local.take(300).forEach { m ->
                            items.add(playable("music::${m.uri}", m.title, m.artUri))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "onLoadChildren KO: ${e.message}")
            }
            runCatching { result.sendResult(items) }
        }
    }

    // ── Recherche voiture (Android Auto : loupe / voix) : musique + radios ────
    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        thread {
            val items = ArrayList<MediaBrowserCompat.MediaItem>()
            try {
                val q = query.trim()
                if (q.isNotBlank()) {
                    // Radios : filtre le catalogue local par nom
                    val radioHits = allStations()
                        .filter { !it.streamUrl.isNullOrBlank() && it.name.contains(q, ignoreCase = true) }
                        .take(30)
                    // Contexte de ⏭/⏮ : les radios trouvées.
                    lastRadioList = radioHits.map { it.streamUrl!! to it.name }
                    radioHits.forEach { s -> items.add(playable("radio::${s.streamUrl}::${s.name}", "📻 ${s.name}", s.poster)) }
                    // 2026-07-25 (user « la recherche ne cherche pas dans les dossiers du
                    //   téléphone ») : Android Auto n'offre pas de bouton d'option dans sa barre de
                    //   recherche → on cherche DANS LES DEUX et on préfixe pour distinguer.
                    //   📁 = fichier du téléphone, 🎵 = web, 📻 = radio.
                    val localHits = runCatching {
                        com.streamflixreborn.streamflix.utils.LocalMediaStore
                            .listAudio(applicationContext, q, limit = 40)
                    }.getOrDefault(emptyList())
                    if (localHits.isNotEmpty()) {
                        lastPhoneTracks = localHits.map { it.uri to it.title }
                        phoneArt = phoneArt + localHits.mapNotNull { m -> m.artUri?.let { m.uri to it } }
                        localHits.forEach { m ->
                            items.add(playable("music::${m.uri}", "📁 ${m.title}", m.artUri))
                        }
                    }
                    // Musique : FileSearch (mp3 direct) + NewPipe (YouTube)
                    val music = runCatching {
                        kotlinx.coroutines.runBlocking {
                            val fs = com.streamflixreborn.streamflix.providers.FileSearchProvider
                                .searchAudio(q).map { it.url to it.title }
                            val yt = com.streamflixreborn.streamflix.providers.NewPipeAudio
                                .search(q).map { it.url to it.title }
                            (fs + yt).distinctBy { it.first }.take(60)
                        }
                    }.getOrDefault(emptyList())
                    lastMusicSearch = music
                    music.forEach { (url, title) -> items.add(playable("music::$url", "🎵 $title", null)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "onSearch KO: ${e.message}")
            }
            runCatching { result.sendResult(items) }
        }
    }

    // ── Catalogue radio (mis en cache : ~2000 stations, évite de refetch à chaque browse) ──
    private fun allStations() = synchronized(CACHE_LOCK) {
        cachedStations ?: runCatching {
            kotlinx.coroutines.runBlocking { RadioCatalog.list() }
        }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) cachedStations = it }
    }

    /** 1re lettre pour le groupement (A-Z, sinon « # »). */
    private fun groupLetter(name: String): String {
        val c = name.trim().firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c.toString() else "#"
    }

    // ── Catalogue ────────────────────────────────────────────────────────────
    /**
     * Dossier de la barre du haut. Un `iconRes` facultatif remplace les emojis dans le titre :
     * Android Auto affiche alors une vraie icône + un libellé court (donc non tronqué).
     */
    private fun browsable(id: String, title: String, iconRes: Int? = null): MediaBrowserCompat.MediaItem {
        val b = MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title)
        if (iconRes != null) {
            runCatching { drawableToBitmap(iconRes) }.getOrNull()?.let { b.setIconBitmap(it) }
        }
        return MediaBrowserCompat.MediaItem(b.build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    /** Convertit une icône vectorielle en bitmap (les MediaDescription n'acceptent pas les vecteurs). */
    private fun drawableToBitmap(resId: Int, size: Int = 128): android.graphics.Bitmap? {
        val d = androidx.core.content.ContextCompat.getDrawable(this, resId) ?: return null
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, size, size)
        runCatching { androidx.core.graphics.drawable.DrawableCompat.setTint(d, android.graphics.Color.WHITE) }
        d.draw(canvas)
        return bmp
    }

    private fun playable(id: String, title: String, art: String?): MediaBrowserCompat.MediaItem {
        val b = MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title)
        if (!art.isNullOrBlank()) runCatching { b.setIconUri(Uri.parse(art)) }
        return MediaBrowserCompat.MediaItem(b.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    // ── État de lecture ──────────────────────────────────────────────────────
    private fun stateBuilder(state: Int): PlaybackStateCompat.Builder {
        val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        val b = PlaybackStateCompat.Builder().setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
        // Bouton ★ Favori sur l'écran de lecture (remplace l'appui long, absent d'Android Auto).
        if (favKind != 0) {
            val isFav = when (favKind) {
                1 -> favMusicUrl?.let { MusicFavoritesStore.isFavorite(it) } ?: false
                2 -> favRadioId?.let { RadioFavoritesStore.isFavorite(it) } ?: false
                else -> false
            }
            val icon = if (isFav) com.streamflixreborn.streamflix.R.drawable.ic_favorite_enable
            else com.streamflixreborn.streamflix.R.drawable.ic_favorite_disable
            val label = if (isFav) "Retirer des favoris" else "Ajouter aux favoris"
            b.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(ACTION_FAV, label, icon).build(),
            )
        }
        // 2026-07-25 (user « il manque le mode aléatoire pour la musique ») : bouton 🔀 sur
        //   l'écran de lecture (uniquement en musique — sans objet pour une radio en direct).
        if (favKind == 1) {
            b.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_SHUFFLE,
                    if (CarRadioController.isShuffle) "Aléatoire : activé" else "Lecture aléatoire",
                    com.streamflixreborn.streamflix.R.drawable.ic_car_shuffle,
                ).build(),
            )
        }
        return b
    }

    /**
     * Carte « en cours de lecture » d'Android Auto (celle façon Spotify sur l'accueil) : elle a
     * besoin d'un titre, d'un sous-titre ET d'une pochette. Sans pochette, AA affiche une vignette
     * grise ; avec, on a le même rendu que Spotify.
     */
    private fun markPlaying(title: String, subtitle: String? = null, artUri: String? = null) {
        val b = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
        val sub = subtitle ?: "ONYX"
        b.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sub)
        b.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, sub)
        if (!artUri.isNullOrBlank()) {
            b.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artUri)
            b.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUri)
            b.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artUri)
        }
        session.setMetadata(b.build())
        session.setPlaybackState(stateBuilder(PlaybackStateCompat.STATE_PLAYING).build())
        session.isActive = true
    }

    private fun setNowPlayingMusic(url: String, title: String) {
        favKind = 1; favMusicUrl = url; favMusicTitle = title; favRadioId = null
    }

    private fun setNowPlayingRadio(url: String, name: String) {
        favKind = 2; favMusicUrl = null
        favRadioUrl = url; favRadioName = name
        val cached = cachedStations ?: emptyList() // pas de réseau ici (déjà chargé au browse/search)
        favRadioId = cached.firstOrNull { it.streamUrl == url }?.id
            ?: cached.firstOrNull { it.name == name }?.id
    }

    /** Résout l'id de la radio en cours (peut recharger le catalogue → à appeler hors thread binder). */
    private fun resolveRadioId(): String? {
        favRadioId?.let { return it }
        val url = favRadioUrl; val name = favRadioName
        val cached = allStations() // charge le catalogue si besoin (on est déjà en arrière-plan)
        val id = cached.firstOrNull { it.streamUrl == url }?.id
            ?: cached.firstOrNull { it.name == name }?.id
        favRadioId = id
        return id
    }

    // ── Commandes voiture ────────────────────────────────────────────────────
    private val callback = object : MediaSessionCompat.Callback() {
        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == ACTION_SHUFFLE) {
                CarRadioController.setShuffle(!CarRadioController.isShuffle)
                val st = if (CarRadioController.isPlaying()) PlaybackStateCompat.STATE_PLAYING
                else PlaybackStateCompat.STATE_PAUSED
                runCatching { session.setPlaybackState(stateBuilder(st).build()) }
                return
            }
            if (action != ACTION_FAV) return
            val kind = favKind
            // En arrière-plan : la résolution de l'id radio peut recharger le catalogue (réseau).
            thread {
                runCatching {
                    when (kind) {
                        1 -> favMusicUrl?.let { MusicFavoritesStore.toggle(it, favMusicTitle ?: "") }
                        2 -> resolveRadioId()?.let { RadioFavoritesStore.toggle(it) }
                    }
                }.onFailure { Log.w(TAG, "favori KO: ${it.message}") }
                // Rafraîchit l'icône ★ (plein/vide) + le dossier de favoris concerné (AA garde en cache).
                val st = if (CarRadioController.isPlaying()) PlaybackStateCompat.STATE_PLAYING
                else PlaybackStateCompat.STATE_PAUSED
                runCatching { session.setPlaybackState(stateBuilder(st).build()) }
                runCatching { notifyChildrenChanged(if (kind == 1) FOLDER_MUSIC else FOLDER_RADIO) }
            }
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val id = mediaId ?: return
            val ctx = applicationContext
            try {
                when {
                    id.startsWith("music::") -> {
                        val url = id.removePrefix("music::")
                        val fav = MusicFavoritesStore.all().map { it.url to it.title }
                        val favIdx = fav.indexOfFirst { it.first == url }
                        when {
                            // Piste des favoris → joue toute la playlist depuis cette piste
                            favIdx >= 0 -> {
                                CarRadioController.playPlaylist(ctx, fav, favIdx, false)
                                setNowPlayingMusic(url, fav[favIdx].second)
                                markPlaying(fav[favIdx].second, "Ma playlist", phoneArt[url])
                            }
                            // Résultat de recherche → joue toute la liste de recherche depuis cette piste
                            else -> {
                                // Résultats de recherche OU musiques du téléphone : on enchaîne la liste.
                                val search = if (lastMusicSearch.any { it.first == url }) lastMusicSearch
                                else if (lastPhoneTracks.any { it.first == url }) lastPhoneTracks
                                else lastMusicSearch
                                val sIdx = search.indexOfFirst { it.first == url }
                                if (sIdx >= 0) {
                                    CarRadioController.playPlaylist(ctx, search, sIdx, false)
                                    setNowPlayingMusic(url, search[sIdx].second)
                                    markPlaying(search[sIdx].second, "Musique", phoneArt[url])
                                } else if (url.startsWith("content://")) {
                                    // 2026-07-25 (bug user : « suivant ne va pas à la musique
                                    //   suivante » + titre affiché « 1000001105 ») : le morceau
                                    //   n'était dans aucune liste connue → il était joué SEUL avec
                                    //   l'ID du fichier comme titre. On recharge la médiathèque pour
                                    //   retrouver son vrai titre ET toute la liste (⏭ / ⏮ marchent).
                                    val local = runCatching {
                                        com.streamflixreborn.streamflix.utils.LocalMediaStore
                                            .listAudio(applicationContext)
                                    }.getOrDefault(emptyList())
                                    val tracks = local.map { it.uri to it.title }
                                    val idx = tracks.indexOfFirst { it.first == url }
                                    if (idx >= 0) {
                                        lastPhoneTracks = tracks
                                        phoneArt = phoneArt + local.mapNotNull { m -> m.artUri?.let { m.uri to it } }
                                        CarRadioController.playPlaylist(ctx, tracks, idx, false)
                                        setNowPlayingMusic(url, tracks[idx].second)
                                        markPlaying(tracks[idx].second, "Téléphone", phoneArt[url])
                                    } else {
                                        val title = local.firstOrNull { it.uri == url }?.title ?: "Lecture"
                                        CarRadioController.playPlaylist(ctx, listOf(url to title), 0, false)
                                        setNowPlayingMusic(url, title)
                                        markPlaying(title, "Téléphone", phoneArt[url])
                                    }
                                } else {
                                    val title = url.substringAfterLast('/').ifBlank { "Lecture" }
                                    CarRadioController.playPlaylist(ctx, listOf(url to title), 0, false)
                                    setNowPlayingMusic(url, title)
                                    markPlaying(title, "Musique", phoneArt[url])
                                }
                            }
                        }
                    }
                    id.startsWith("radio::") -> {
                        val rest = id.removePrefix("radio::")
                        val url = rest.substringBeforeLast("::")
                        val name = rest.substringAfterLast("::")
                        setNowPlayingRadio(url, name) // favKind=2 AVANT lecture (le hook s'appuie dessus)
                        // 2026-07-25 : jouer la LISTE affichée (favoris / lettre / recherche) depuis la
                        //   station tapée → ⏭/⏮ passe à la station suivante/précédente. Station isolée
                        //   ou hors liste → lecture simple (comme avant).
                        val list = lastRadioList
                        val idx = list.indexOfFirst { it.first == url }
                        if (idx >= 0 && list.size > 1) {
                            CarRadioController.playPlaylist(ctx, list, idx, false)
                        } else {
                            CarRadioController.play(ctx, name, url, emptyList())
                        }
                        // Logo de la station → pochette de la carte « en cours » (façon Spotify).
                        val logo = (cachedStations ?: emptyList())
                            .firstOrNull { it.streamUrl == url || it.name == name }?.poster
                        markPlaying(name, subtitle = "Radio", artUri = logo)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "onPlayFromMediaId KO: ${e.message}")
            }
        }

        override fun onPlay() {
            CarRadioController.resume()
            session.setPlaybackState(stateBuilder(PlaybackStateCompat.STATE_PLAYING).build())
        }

        override fun onPause() {
            CarRadioController.pause()
            session.setPlaybackState(stateBuilder(PlaybackStateCompat.STATE_PAUSED).build())
        }

        override fun onStop() {
            CarRadioController.stop()
            session.setPlaybackState(stateBuilder(PlaybackStateCompat.STATE_STOPPED).build())
            session.isActive = false
        }

        // La carte « en cours » + la cible du ★ sont rafraîchies par CarRadioController.onTrackChanged.
        override fun onSkipToNext() { CarRadioController.skipNext() }

        override fun onSkipToPrevious() { CarRadioController.skipPrevious() }
    }

    companion object {
        private const val TAG = "OnyxMediaBrowser"
        private const val ROOT = "onyx_root"
        private const val FOLDER_MUSIC = "onyx_music"
        private const val FOLDER_RADIO = "onyx_radio"
        private const val ACTION_FAV = "onyx_fav_toggle"        // bouton ★ de l'écran de lecture
        private const val ACTION_SHUFFLE = "onyx_shuffle_toggle" // bouton 🔀 (musique uniquement)
        private const val FOLDER_ALL_RADIOS = "onyx_all_radios" // toutes les stations, groupées par lettre
        private const val GRP_PREFIX = "onyx_radiogrp::"       // + lettre → stations de cette lettre
        // Styles d'affichage Android Auto : 1 = liste, 2 = grille (vignettes façon Spotify).
        private const val CONTENT_STYLE_LIST = 1
        private const val CONTENT_STYLE_GRID = 2
        private val CACHE_LOCK = Any()
        @Volatile private var cachedStations: List<com.streamflixreborn.streamflix.utils.RadioCatalog.RadioStation>? = null
        @Volatile private var lastMusicSearch: List<Pair<String, String>> = emptyList() // résultats musique de la dernière recherche
        @Volatile private var lastPhoneTracks: List<Pair<String, String>> = emptyList() // musiques locales listées dans la voiture
        @Volatile private var phoneArt: Map<String, String> = emptyMap() // uri morceau → pochette d'album
        // Dernière liste de radios affichée (favoris / lettre / recherche) = contexte de ⏭/⏮ radio.
        @Volatile private var lastRadioList: List<Pair<String, String>> = emptyList() // (streamUrl, nom)
        private const val FOLDER_PHONE = "onyx_phone_music"
        private const val FOLDER_PHONE_ALL = "onyx_phone_music_all" // toute la musique locale
        private const val PHONE_DIR_PREFIX = "onyx_phone_dir::"      // + dossier → morceaux du dossier
    }
}
