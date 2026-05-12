package com.streamflixreborn.streamflix.fragments.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.CustomTabHelper
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.streamflixreborn.streamflix.utils.SubDL

class PlayerViewModel(
    videoType: Video.Type,
    id: String,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingServers)
    // 2026-05-12 : exposé en StateFlow (au lieu de Flow) pour accès .value depuis le fragment.
    // Permet de re-process l'état courant après init lazy du player (scénario double-click
    // où state.servers peut arriver AVANT que ::player.isInitialized soit true).
    val state: kotlinx.coroutines.flow.StateFlow<State> = _state

    private val _subtitleState = MutableSharedFlow<SubtitleState>()
    val subtitleState: SharedFlow<SubtitleState> = _subtitleState

    private val _playPreviousOrNextEpisode = MutableSharedFlow<Video.Type.Episode>()
    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode

    // Progressive additional servers (e.g. OLA TV emitting as discovered)
    private val _additionalServer = MutableSharedFlow<Video.Server>(extraBufferCapacity = 100)
    val additionalServer: SharedFlow<Video.Server> = _additionalServer
    private var additionalServerJob: Job? = null

    // ─── 2026-05-09 : tracking session pour FilmHealthTracker ─────────────
    // Compte les échecs dead-content dans cette session de player. Sert au
    // FilmHealthTracker pour décider de marquer le film "vide" quand tous
    // les serveurs ont été épuisés en dead-content (= contenu pas dispo).
    private var sessionFilmId: String? = null
    private var sessionFailCount = 0
    private var sessionDeadContentCount = 0
    private var sessionAnySuccess = false

    init {
        getServers(videoType, id)
        getSubtitles(videoType)
    }

    fun playEpisode(direction: Direction) {
        val hasEpisode = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.hasPreviousEpisode()
            Direction.NEXT -> EpisodeManager.hasNextEpisode()
        }

        if (!hasEpisode) return

        val ep = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.getPreviousEpisode()
            Direction.NEXT -> EpisodeManager.getNextEpisode()
        } ?: return

        val nextEpisode = Video.Type.Episode(
            id = ep.id,
            number = ep.number,
            title = ep.title,
            poster = ep.poster,
            overview = ep.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = ep.tvShow.id,
                title = ep.tvShow.title,
                poster = ep.tvShow.poster,
                banner = ep.tvShow.banner,
                releaseDate = ep.tvShow.releaseDate,
                imdbId = ep.tvShow.imdbId
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title
            )
        )

        playEpisode(nextEpisode)

        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }
    fun playPreviousEpisode() =
        playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() =
        playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }
    fun playEpisode(episode: Video.Type.Episode) {
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    /**
     * Appelle [com.streamflixreborn.streamflix.providers.Provider.getServers]
     * avec auto-retry si la liste retournée est vide.
     *
     *  Backoff : essai immédiat → +2s → +5s. Total max 7s d'attente avant
     *  d'abandonner. La majorité des transient (AnimeSama backend lent,
     *  parsing race, upstream Sibnet/VidMoLy down momentané) se résolvent
     *  à la 1ère retry.
     *
     *  Cas où le retry n'aide pas (provider effectivement sans source pour
     *  ce contenu) → on retourne quand même empty au bout des 3 essais et
     *  le caller throw normalement avec son message "Aucune source...".
     *
     *  Si une exception survient pendant un retry, on la propage
     *  (re-throw) — c'est le comportement attendu, pas un cas "empty".
     */
    private suspend fun fetchServersWithRetry(
        provider: com.streamflixreborn.streamflix.providers.Provider,
        id: String,
        videoType: Video.Type,
    ): List<Video.Server> {
        val backoffsMs = listOf(0L, 2_000L, 5_000L)
        var lastResult: List<Video.Server> = emptyList()
        for ((attemptIdx, delayMs) in backoffsMs.withIndex()) {
            if (delayMs > 0) {
                Log.d("PlayerViewModel", "fetchServersWithRetry: empty result, attempt ${attemptIdx + 1}/${backoffsMs.size} in ${delayMs}ms")
                delay(delayMs)
            }
            lastResult = provider.getServers(id, videoType)
            if (lastResult.isNotEmpty()) {
                if (attemptIdx > 0) {
                    Log.i("PlayerViewModel", "fetchServersWithRetry: recovered on attempt ${attemptIdx + 1} → ${lastResult.size} servers")
                }
                return lastResult
            }
        }
        Log.w("PlayerViewModel", "fetchServersWithRetry: all ${backoffsMs.size} attempts returned empty for $id on ${provider.name}")
        return lastResult
    }

    /**
     * Job de pré-extraction. Annulé proprement quand le ViewModel est cleared
     * (changement de fragment) ou quand un nouveau getServers démarre — évite
     * de continuer à hammer les CDN pour un contenu que l'user n'attend plus.
     */
    private var preExtractJob: Job? = null

    /**
     * Lance en background l'extraction des [PRE_EXTRACT_TOP_N] premiers serveurs.
     * Fire-and-forget : on n'attend pas. Les résultats vont automatiquement
     * dans [com.streamflixreborn.streamflix.extractors.Extractor]'s
     * `extractionCache` (clé = URL, TTL 10 min). Quand l'user cliquera sur
     * un de ces serveurs, [getVideo] → `Extractor.extract()` → cache HIT.
     *
     *  Limites du pattern
     *  ------------------
     *  - **IPTV/live** : les providers IPTV (WiTV, OLA, Vegeta, Sport Live,
     *    MovixLiveTV) ne passent pas tous par Extractor.extract() — leur
     *    getVideo retourne souvent l'URL m3u8 directement avec headers. Pour
     *    eux la pré-extraction est inefficace mais sans dommage : l'appel
     *    Extractor.extract sortira en "No extractors found" silencieusement.
     *  - **Provider qui transforme l'URL avant extract** : si certains
     *    providers font `extract(transformedUrl)` au lieu de `extract(server.src)`
     *    dans leur getVideo, le cache utilise une clé différente et on rate
     *    le hit. Pas grave — fallback sur extraction normale au clic.
     *
     *  Avantages
     *  ---------
     *  - Démarrage 4-8s → <500ms quand l'user clique le 1er serveur
     *  - Si le 1er échoue (vraie panne), on a déjà l'extraction du 2e
     *    en cache aussi → failover instantané
     *  - Aucun changement UI nécessaire
     */
    private fun preExtractTopServersInBackground(servers: List<Video.Server>) {
        preExtractJob?.cancel()
        if (servers.isEmpty()) return
        val toExtract = servers.take(PRE_EXTRACT_TOP_N)
        // 2026-05-12 : skip IPTV — chaînes live, pas pertinent
        val providerNameForHead = UserPreferences.currentProvider?.name.orEmpty()
        val isIptvProvider = providerNameForHead in setOf(
            "WiTv", "OlaTv", "VegetaTv", "Vavoo", "3BoxTV", "LiveTvHub", "SportLive"
        )
        preExtractJob = viewModelScope.launch(Dispatchers.IO) {
            // 2026-05-12 : SCAN HEAD léger sur TOUS les servers en background.
            // Pas d'extraction (pas de WebView, pas de JS), juste HEAD HTTP sur
            // server.src (URL embed). HEAD 404/410/connect-refused → server mort
            // → flagged broken → tri pousse en bas, fallback skip.
            // Servers déjà inclus dans le top N (toExtract) sont skipped — l'extract
            // les couvre déjà avec HEAD post-extraction (plus précis car teste l'URL
            // de stream final).
            // Coût : ~13 HEAD requests × ~200-500ms ≈ 2-3s en parallèle.
            if (!isIptvProvider) {
                val toScan = servers.drop(toExtract.size)
                toScan.forEach { srv ->
                    launch {
                        val embedUrl = srv.src
                        if (!embedUrl.startsWith("http", ignoreCase = true)) return@launch
                        if (embedUrl.startsWith("data:")) return@launch
                        try {
                            val alive = withTimeoutOrNull(2_500L) {
                                val client = okhttp3.OkHttpClient.Builder()
                                    .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                                    .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                                    .followRedirects(true)
                                    .build()
                                val req = okhttp3.Request.Builder().url(embedUrl).head().build()
                                client.newCall(req).execute().use { resp ->
                                    // 4xx (sauf 405 Method Not Allowed) → mort
                                    // 405 = serveur n'accepte pas HEAD mais peut servir GET
                                    val code = resp.code
                                    code != 405 && (code in 200..399 || code == 403)
                                }
                            } ?: false
                            if (!alive) {
                                com.streamflixreborn.streamflix.extractors.Extractor
                                    .recordFailureExternal(srv.name, "embed-head-failed")
                                Log.d("PlayerViewModel", "Background HEAD-scan: ${srv.name} → embed dead, flagged broken")
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            // ignore
                        } catch (_: Exception) {
                            // ignore — un échec réseau ne flag pas
                        }
                    }
                }
            }

            val jobs = mutableListOf<Job>()
            toExtract.forEachIndexed { idx, server ->
                jobs += launch {
                    try {
                        val startMs = System.currentTimeMillis()
                        // 2026-05-09 v2 : timeout 10s → 15s. Donne le temps aux
                        // extractors lents (Filemoon JS unpacker, VidMoLy CF
                        // challenge) de finir leur job. Reste un pré-extract
                        // background — ne bloque pas le user, donc on peut
                        // se permettre d'attendre. Si on dépasse 15s, le user
                        // qui clique fera l'extraction normalement (durée
                        // perçue = celle du watchdog ExoPlayer = 45s).
                        val result = withTimeoutOrNull(15_000L) {
                            com.streamflixreborn.streamflix.extractors.Extractor
                                .extract(server.src, server)
                        }
                        val durationMs = System.currentTimeMillis() - startMs
                        if (result != null) {
                            // 2026-05-12 : validation HEAD du stream après extraction.
                            // Si le serveur de stream final est 404/410/timeout, on
                            // l'écarte AVANT qu'ExoPlayer perde 15s à constater l'échec.
                            // Skip pour data: URIs (Vidoza/Filemoon master.m3u8 inline),
                            // skip pour les hosts WebView-only (LuluVdo).
                            val streamUrl = result.source
                            // 2026-05-12 : skip HEAD pour IPTV — les chaînes live ont
                            // des URLs avec tokens dynamiques qui rejettent souvent les
                            // HEAD requests, et le user n'a pas un grand nombre de fallbacks
                            // (typiquement 1-2 par chaîne), donc l'optim apporte rien.
                            val providerName = UserPreferences.currentProvider?.name.orEmpty()
                            val isIptv = providerName in setOf(
                                "WiTv", "OlaTv", "VegetaTv", "Vavoo", "3BoxTV", "LiveTvHub", "SportLive"
                            )
                            val needsHeadCheck = !isIptv &&
                                streamUrl.startsWith("http", ignoreCase = true) &&
                                !streamUrl.startsWith("data:") &&
                                !streamUrl.contains("luluvdo", ignoreCase = true) &&
                                !streamUrl.contains("cfglobalcdn", ignoreCase = true)
                            val headOk = if (!needsHeadCheck) true else {
                                runCatching {
                                    withTimeoutOrNull(3_000L) {
                                        val client = okhttp3.OkHttpClient.Builder()
                                            .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                                            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                                            .followRedirects(true)
                                            .build()
                                        val req = okhttp3.Request.Builder()
                                            .url(streamUrl)
                                            .head()
                                            .apply {
                                                result.headers?.forEach { (k, v) -> header(k, v) }
                                            }
                                            .build()
                                        client.newCall(req).execute().use { resp -> resp.isSuccessful }
                                    } ?: false
                                }.getOrDefault(false)
                            }
                            if (!headOk) {
                                // 2026-05-12 : HEAD échoué → flag broken (Option A user).
                                // Le tri va pousser ce server en bas de liste donc
                                // l'user n'attend pas dessus. Mais HEAD peut mentir
                                // (CDN refuse HEAD mais GET marche) → la 2nde passe
                                // (kickée plus loin) ré-extrait SANS HEAD et un-flag
                                // si la vraie extraction passe.
                                com.streamflixreborn.streamflix.extractors.Extractor
                                    .invalidateCache(server.src)
                                com.streamflixreborn.streamflix.extractors.Extractor
                                    .recordFailureExternal(server.name, "head-failed")
                                Log.d(
                                    "PlayerViewModel",
                                    "Pre-extract HEAD-fail [$idx] ${server.name} after ${durationMs}ms → flagged broken (2nd pass will retry)",
                                )
                            } else {
                                Log.d(
                                    "PlayerViewModel",
                                    "Pre-extract OK [$idx] ${server.name} → cached in ${durationMs}ms",
                                )
                            }
                        } else {
                            Log.d(
                                "PlayerViewModel",
                                "Pre-extract timeout [$idx] ${server.name} after ${durationMs}ms",
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Annulation propre (user a quitté ou nouveau getServers)
                        throw e
                    } catch (e: Exception) {
                        // Échec silencieux — pas grave, fallback au clic.
                        Log.d(
                            "PlayerViewModel",
                            "Pre-extract skip [$idx] ${server.name}: ${e.message?.take(80)}",
                        )
                    }
                }
            }

            // 2026-05-12 : 2nde passe DOUCE après que la 1re finit.
            // But : récupérer les CDN qui mentent sur HEAD (refus HEAD mais GET OK).
            // Couvre maintenant TOUS les servers flaggés broken (pas seulement les
            // TOP N pre-extract). Comme ça si le background HEAD scan flag à tort
            // une URL embed légitime, on récupère.
            // Limite concurrence à 4 pour éviter de saturer (les broken sont jusqu'à
            // 13-15 dans une liste de 17).
            jobs.joinAll()
            launch {
                kotlinx.coroutines.delay(5_000L)  // laisse l'user voir la liste
                val sem = kotlinx.coroutines.sync.Semaphore(4)
                servers.forEachIndexed { idx, server ->
                    launch {
                        sem.acquire()
                        try {
                            val extractorName = server.name
                            val isStillBroken = com.streamflixreborn.streamflix.extractors.Extractor
                                .brokenServerNames()
                                .any { extractorName.uppercase().contains(it.uppercase()) }
                            if (!isStillBroken) return@launch
                            val result = withTimeoutOrNull(15_000L) {
                                com.streamflixreborn.streamflix.extractors.Extractor
                                    .extract(server.src, server)
                            }
                            if (result != null && result.source.isNotBlank()) {
                                // Extraction OK sans HEAD → faux positif HEAD → un-flag.
                                com.streamflixreborn.streamflix.extractors.Extractor
                                    .recordSuccessExternal(extractorName)
                                Log.d(
                                    "PlayerViewModel",
                                    "2nd-pass RECOVERED [$idx] $extractorName → un-flagged broken",
                                )
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            // ignore
                        } catch (_: Exception) {
                            // reste broken, ok.
                        } finally {
                            sem.release()
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Nombre de serveurs à pré-extraire en parallèle. 4 = compromis :
         *  couvre les 3-4 prochains clics probables (Voe, Uqload, Vidoza, Filemoon),
         *  reste safe sur Chromecast (~4 WebView max ≈ 600MB RAM).
         *  2026-05-12 : passé de 3→4 (user request, latency Tahiti élevée). */
        private const val PRE_EXTRACT_TOP_N = 4
    }

    private fun getServers(videoType: Video.Type, id: String) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
        lastVideoType = videoType
        lastId = id
        _state.emit(State.LoadingServers)
        try {
            val provider = UserPreferences.currentProvider ?: return@launch
            // 2026-05-09 : auto-retry sur empty servers — gère les transient
            // (timeout backend AnimeSama, parsing race, upstream Sibnet/VidMoLy
            // en glitch momentané). 3 tentatives total avec backoff 0/2/5s :
            // si la 1ère retourne du contenu, on continue direct (zéro délai
            // ajouté). Si vide, on retry à 2s puis 5s. La majorité des
            // transient se résolvent à la 1ère retry. Coût pire cas : ~7s
            // d'attente AVANT de jeter l'erreur, mais SEULEMENT dans les cas
            // où on aurait failed sans nous.
            val rawServers = fetchServersWithRetry(provider, id, videoType)
            if (rawServers.isEmpty()) {
                // Message clair pour l'utilisateur (vs "No servers found" en
                // anglais). Souvent ça arrive sur des films/séries trop récents
                // ou trop obscurs où le provider courant n'a pas indexé de
                // source. Suggérer de changer de provider évite la confusion
                // "ça marche pas, l'app est cassée".
                throw Exception("Aucune source disponible pour ce contenu sur ${provider.name}. Essayez un autre provider ou réessayez plus tard.")
            }

            // 2026-05-09 : tri intelligent par fiabilité observée.
            // ExtractorRanker croise healthScore (broken < 5min) + failure
            // count persistant (depuis dernier succès) → les extracteurs
            // récemment-cassés tombent au fond du picker, sans être éliminés.
            // Couvre aussi le cas ISP-blocked (netu/waaw/etc.) qui était géré
            // par le sort hardcodé qu'on remplace ici.
            val servers = com.streamflixreborn.streamflix.utils.ExtractorRanker
                .rankServers(rawServers)

            Log.i("StreamFlixES", "[SERVERS LIST] -> Provider: ${provider.name}")
            Log.i("StreamFlixES", "[SERVERS LIST] -> Found ${servers.size} servers: ${servers.joinToString { it.name }}")

            // 2026-05-12 : tri stable global qui pousse les extracteurs flaggés
            // `markedBroken` (≥3 échecs dans la fenêtre, healthScore=0f) en bas
            // de liste. Préserve l'ordre interne des providers (VF→VOSTFR→VO,
            // qualité, etc.) pour les serveurs sains. Profite à tous les
            // providers d'un coup, sans toucher leur logique de tri.
            // Le pre-extract qui suit cible alors les N PREMIERS SAINS plutôt
            // que de gaspiller des slots à retenter des morts récents.
            //
            // Détection broken : on extrait le "core" extractor de chaque server name
            // pour faire match symétrique. Exemples :
            //   "Movix — VidMoLy - VidMoly Vidéo 12 (VF)" → core "VIDMOLY"
            //   "VidMoLy - VidMoly Vidéo 12 (VF)"        → core "VIDMOLY"
            //   "Movix — Uqload (VF - HD)"               → core "UQLOAD"
            //   "Premium (VF - HD)"                       → core "PREMIUM"
            //   "Cloudstream [1080p MP4]"                 → core "CLOUDSTREAM"
            // Si le core d'un broken == core d'un candidate → même extracteur → broken.
            fun extractorCore(name: String): String {
                val stripped = name.substringAfter(" — ").substringAfter(" · ").trim()
                return stripped
                    .substringBefore(" - ")
                    .substringBefore(" (")
                    .substringBefore(" [")
                    .trim()
                    .uppercase()
            }
            val brokenCores = com.streamflixreborn.streamflix.extractors.Extractor.brokenServerNames()
                .map { extractorCore(it) }
                .filter { it.isNotBlank() }
                .toSet()
            fun isBroken(serverName: String): Boolean {
                if (brokenCores.isEmpty()) return false
                return extractorCore(serverName) in brokenCores
            }
            val sortedServers = servers.sortedBy { srv -> if (isBroken(srv.name)) 1 else 0 }
            val brokenCount = sortedServers.count { isBroken(it.name) }
            if (brokenCount > 0) {
                Log.d("PlayerViewModel", "Pushed $brokenCount broken servers to bottom (cores=$brokenCores)")
            }

            Log.d("PlayerViewModel", "Ricerca server completata: ${sortedServers.size} server trovati")
            _state.emit(State.SuccessLoadingServers(sortedServers))

            // 2026-05-09 : pré-extraction parallèle des 3 premiers serveurs en
            // background. Le but : quand l'user clique "Watch", l'URL m3u8 est
            // déjà dans le cache d'extraction (cf Extractor.extractionCache,
            // 10 min TTL) → démarrage en <500ms au lieu de 3-5s.
            //
            // Coût : 1-3 requêtes HTTP supplémentaires par ouverture de player
            // (~100-500KB chacune). Acceptable en Wi-Fi/Chromecast.
            //
            // Stratégie : fire-and-forget — on attend pas le résultat, on laisse
            // les coroutines remplir le cache. Si l'user clique sur le 4e
            // serveur (non pré-extrait) → fallback normal sans régression.
            // Si une pré-extraction échoue → silencieuse, normale path au clic.
            preExtractTopServersInBackground(sortedServers)

            // Start collecting progressive servers for any IPTV provider (WiTv / OlaTv).
            additionalServerJob?.cancel()
            if (provider is IptvProvider) {
                additionalServerJob = viewModelScope.launch(Dispatchers.IO) {
                    provider.additionalServersFlow.collect { server ->
                        Log.d("PlayerViewModel", "Additional server arrived: ${server.name}")
                        _additionalServer.emit(server)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore ricerca server: ", e)
            _state.emit(State.FailedLoadingServers(e))
        }
    }

    fun getVideo(server: Video.Server) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")
        _state.emit(State.LoadingVideo(server))
        try {
            val video = (UserPreferences.currentProvider ?: return@launch).getVideo(server)
            if (video.source.isEmpty()) throw Exception("No source found")

            // LOGICA SOTTOTITOLI GLOBALE: 
            // Se il provider non ha già impostato un default (es. i "forced" in spagnolo),
            // allora proviamo ad attivare l'ultimo sottotitolo usato dall'utente.
            // MA: se siamo su un provider spagnolo e non ci sono forced, non dobbiamo attivare nulla.
            val currentProviderLang = UserPreferences.currentProvider?.language ?: ""
            val hasDefaultAlready = video.subtitles.any { it.default }

            if (!hasDefaultAlready && currentProviderLang != "es") {
                if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                    video.subtitles
                        .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                        ?.default = true
		}
            }

            Log.d("PlayerViewModel", "Estrazione video completata con successo")
            // 2026-05-09 : tracking session pour FilmHealthTracker.
            // Une réussite = on retire la marque "vide" si elle existait
            // (le film marche maintenant) et on flag pour ne pas marquer.
            sessionAnySuccess = true
            recordSessionFilmId()
            unmarkCurrentFilmAsEmpty()
            _state.emit(State.SuccessLoadingVideo(video, server))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore estrazione video: ", e)
            // Tracking session : compte ce fail, et si dead-content, augmente
            // le compteur dédié. Quand tous les serveurs seront épuisés
            // (signal envoyé par le fragment via [markFilmEmptyIfAllDeadContent]),
            // on décide de marquer le film vide.
            recordSessionFilmId()
            sessionFailCount++
            val errorType = com.streamflixreborn.streamflix.extractors.Extractor.classifyError(e)
            if (errorType == "dead-content") sessionDeadContentCount++
            _state.emit(State.FailedLoadingVideo(e, server))
        }
    }

    /** Capture l'ID du film/épisode courant à partir du videoType. */
    private fun recordSessionFilmId() {
        if (sessionFilmId != null) return  // déjà set
        sessionFilmId = lastId
    }

    /** Retire la marque "film vide" pour ce film (appelé sur succès). */
    private fun unmarkCurrentFilmAsEmpty() {
        val provider = UserPreferences.currentProvider ?: return
        val filmId = sessionFilmId ?: return
        com.streamflixreborn.streamflix.utils.FilmHealthTracker.unmark(provider.name, filmId)
    }

    /**
     * Appelé par PlayerFragment quand TOUS les serveurs ont été tentés et
     * qu'aucun n'a marché (= nextAutoFallbackServer retourne null).
     *
     * Décide de marquer le film comme "vide" si :
     *  - Aucun succès dans cette session
     *  - ≥2 fails enregistrés (sinon c'est un cas suspect — peut-être
     *    juste un transient)
     *  - **La majorité des fails étaient dead-content** (≥ moitié)
     *
     * Critères stricts pour réduire le risque de faux positifs.
     */
    fun markFilmEmptyIfAllDeadContent() {
        if (sessionAnySuccess) return  // au moins un serveur a marché → pas vide
        if (sessionFailCount < 2) return  // pas assez de samples
        if (sessionDeadContentCount * 2 < sessionFailCount) return  // pas une majorité dead-content
        val provider = UserPreferences.currentProvider ?: return
        val filmId = sessionFilmId ?: return
        com.streamflixreborn.streamflix.utils.FilmHealthTracker.markEmpty(provider.name, filmId)
        Log.d(
            "PlayerViewModel",
            "markFilmEmptyIfAllDeadContent: ${provider.name}:$filmId marked (fails=$sessionFailCount, deadContent=$sessionDeadContentCount)",
        )
    }

    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca sottotitoli")
        _subtitleState.emit(SubtitleState.Loading)

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca OpenSubtitles")
                // App-wide policy: French subtitles only. ISO 639-2/B code "fre".
                val frenchOnly = "fre"
                // 2026-05-04 : on PASSE TOUJOURS imdb_id quand le provider le
                // fournit. Sans ça la recherche tombait en mode "query texte"
                // et matchait "Fear City: New York Vs The Mafia S01E03" pour
                // "New York 911 S01E03" (tous deux contiennent "New York")
                // -> sous-titre dingue d'une autre série, donc "désynchronisé".
                // Tri : downloads DESC (le plus téléchargé = le mieux synchro).
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        OpenSubtitles.search(
                            imdbId = videoType.tvShow.imdbId,
                            query = if (videoType.tvShow.imdbId.isNullOrBlank()) videoType.tvShow.title else null,
                            season = videoType.season.number,
                            episode = videoType.number,
                            subLanguageId = frenchOnly,
                        )
                    }
                    is Video.Type.Movie -> {
                        OpenSubtitles.search(
                            imdbId = videoType.imdbId,
                            query = if (videoType.imdbId.isNullOrBlank()) videoType.title else null,
                            subLanguageId = frenchOnly,
                        )
                    }
                }.sortedByDescending { it.subDownloadsCnt }
                
                Log.d("PlayerViewModel", "Ricerca OpenSubtitles completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca SubDL")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        SubDL.search(
                            filmName = videoType.tvShow.title,
                            seasonNumber = videoType.season.number,
                            episodeNumber = videoType.number,
                            type = "tv"
                        )
                    }
                    is Video.Type.Movie -> {
                        SubDL.search(
                            filmName = videoType.title,
                            type = "movie"
                        )
                    }
                }
                
                Log.d("PlayerViewModel", "Ricerca SubDL completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore SubDL: ", e)
                _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
            }
        }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo OpenSubtitles: ${subtitle.subFileName}")
        _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
        try {
            val uri = OpenSubtitles.download(subtitle)
            Log.d("PlayerViewModel", "Download OpenSubtitles completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download OpenSubtitles: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo SubDL: ${subtitle.name}")
        _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
        try {
            val uri = SubDL.download(subtitle)
            Log.d("PlayerViewModel", "Download SubDL completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download SubDL: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
        }
    }

    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(val error: Exception) : State()
        data class LoadingVideo(val server: Video.Server) : State()
        data class SuccessLoadingVideo(val video: Video, val server: Video.Server) : State()
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server) : State()
    }

    sealed class SubtitleState {
        data object Loading : SubtitleState()
        data class SuccessOpenSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : SubtitleState()
        data class FailedOpenSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingOpenSubtitle : SubtitleState()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingOpenSubtitle(val error: Exception, val subtitle: OpenSubtitles.Subtitle) : SubtitleState()

        data class SuccessSubDLSubtitles(val subtitles: List<SubDL.Subtitle>) : SubtitleState()
        data class FailedSubDLSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingSubDLSubtitle : SubtitleState()
        data class SuccessDownloadingSubDLSubtitle(val subtitle: SubDL.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingSubDLSubtitle(val error: Exception, val subtitle: SubDL.Subtitle) : SubtitleState()
    }
    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null
    fun reloadServersAfterBypass() {
        val type = lastVideoType ?: return
        val id = lastId ?: return
        getServers(type, id)
    }
}
