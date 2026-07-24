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

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "OnyxMedia").apply {
            setCallback(callback)
            setPlaybackState(stateBuilder(PlaybackStateCompat.STATE_NONE).build())
        }
        sessionToken = session.sessionToken
        session.isActive = true
        Log.i(TAG, "MediaBrowserService créé")
    }

    override fun onDestroy() {
        runCatching { session.release() }
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // ALLOW tout client (voiture) + déclare la RECHERCHE supportée (sinon Android Auto n'affiche
        // pas la loupe → onSearch jamais appelé).
        val extras = Bundle().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) }
        return BrowserRoot(ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach() // chargement en arrière-plan (le catalogue radio est réseau)
        thread {
            val items = ArrayList<MediaBrowserCompat.MediaItem>()
            try {
                when (parentId) {
                    ROOT -> {
                        items.add(browsable(FOLDER_MUSIC, "🎵 Ma playlist"))
                        items.add(browsable(FOLDER_RADIO, "📻 Radios favorites"))
                        items.add(browsable(FOLDER_ALL_RADIOS, "📻 Toutes les radios"))
                    }
                    FOLDER_MUSIC -> MusicFavoritesStore.all().forEach { t ->
                        items.add(playable("music::${t.url}", t.title, null))
                    }
                    FOLDER_RADIO -> {
                        val favIds = runCatching { RadioFavoritesStore.all() }.getOrDefault(emptySet())
                        allStations().filter { it.id in favIds && !it.streamUrl.isNullOrBlank() }.forEach { s ->
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
                        allStations()
                            .filter { !it.streamUrl.isNullOrBlank() && groupLetter(it.name) == letter }
                            .sortedBy { it.name.lowercase() }
                            .take(200)
                            .forEach { s -> items.add(playable("radio::${s.streamUrl}::${s.name}", s.name, s.poster)) }
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
                    allStations().asSequence()
                        .filter { !it.streamUrl.isNullOrBlank() && it.name.contains(q, ignoreCase = true) }
                        .take(30)
                        .forEach { s -> items.add(playable("radio::${s.streamUrl}::${s.name}", "📻 ${s.name}", s.poster)) }
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
    private fun browsable(id: String, title: String): MediaBrowserCompat.MediaItem {
        val d = MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build()
        return MediaBrowserCompat.MediaItem(d, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
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
        return b
    }

    private fun markPlaying(title: String) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title).build(),
        )
        session.setPlaybackState(stateBuilder(PlaybackStateCompat.STATE_PLAYING).build())
        session.isActive = true
    }

    private fun setNowPlayingMusic(url: String, title: String) {
        favKind = 1; favMusicUrl = url; favMusicTitle = title; favRadioId = null
    }

    private fun setNowPlayingRadio(url: String, name: String) {
        favKind = 2; favMusicUrl = null
        val cached = cachedStations ?: emptyList() // pas de réseau ici (déjà chargé au browse/search)
        favRadioId = cached.firstOrNull { it.streamUrl == url }?.id
            ?: cached.firstOrNull { it.name == name }?.id
    }

    // ── Commandes voiture ────────────────────────────────────────────────────
    private val callback = object : MediaSessionCompat.Callback() {
        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action != ACTION_FAV) return
            runCatching {
                when (favKind) {
                    1 -> favMusicUrl?.let { MusicFavoritesStore.toggle(it, favMusicTitle ?: "") }
                    2 -> favRadioId?.let { RadioFavoritesStore.toggle(it) }
                }
            }.onFailure { Log.w(TAG, "favori KO: ${it.message}") }
            // Rafraîchit l'icône ★ (plein/vide) sur l'écran de lecture.
            val st = if (CarRadioController.isPlaying()) PlaybackStateCompat.STATE_PLAYING
            else PlaybackStateCompat.STATE_PAUSED
            session.setPlaybackState(stateBuilder(st).build())
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
                                markPlaying(fav[favIdx].second)
                            }
                            // Résultat de recherche → joue toute la liste de recherche depuis cette piste
                            else -> {
                                val search = lastMusicSearch
                                val sIdx = search.indexOfFirst { it.first == url }
                                if (sIdx >= 0) {
                                    CarRadioController.playPlaylist(ctx, search, sIdx, false)
                                    setNowPlayingMusic(url, search[sIdx].second)
                                    markPlaying(search[sIdx].second)
                                } else {
                                    val title = url.substringAfterLast('/').ifBlank { "Lecture" }
                                    CarRadioController.playPlaylist(ctx, listOf(url to title), 0, false)
                                    setNowPlayingMusic(url, title)
                                    markPlaying(title)
                                }
                            }
                        }
                    }
                    id.startsWith("radio::") -> {
                        val rest = id.removePrefix("radio::")
                        val url = rest.substringBeforeLast("::")
                        val name = rest.substringAfterLast("::")
                        CarRadioController.play(ctx, name, url, emptyList())
                        setNowPlayingRadio(url, name)
                        markPlaying(name)
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

        override fun onSkipToNext() {
            CarRadioController.skipNext()
            CarRadioController.currentName?.let { markPlaying(it) }
        }

        override fun onSkipToPrevious() {
            CarRadioController.skipPrevious()
            CarRadioController.currentName?.let { markPlaying(it) }
        }
    }

    companion object {
        private const val TAG = "OnyxMediaBrowser"
        private const val ROOT = "onyx_root"
        private const val FOLDER_MUSIC = "onyx_music"
        private const val FOLDER_RADIO = "onyx_radio"
        private const val ACTION_FAV = "onyx_fav_toggle"        // bouton ★ de l'écran de lecture
        private const val FOLDER_ALL_RADIOS = "onyx_all_radios" // toutes les stations, groupées par lettre
        private const val GRP_PREFIX = "onyx_radiogrp::"       // + lettre → stations de cette lettre
        private val CACHE_LOCK = Any()
        @Volatile private var cachedStations: List<com.streamflixreborn.streamflix.utils.RadioCatalog.RadioStation>? = null
        @Volatile private var lastMusicSearch: List<Pair<String, String>> = emptyList() // résultats musique de la dernière recherche
    }
}
