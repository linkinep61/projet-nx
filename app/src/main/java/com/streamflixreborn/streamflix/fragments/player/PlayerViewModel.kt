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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // 2026-07-04 : job unique pour getServers — cancel le précédent si une nouvelle
    //   navigation déclenche un rechargement (double setupNav, playEpisode, reload).
    //   Sans ça, deux collectProgressiveServers tournent en parallèle et se marchent
    //   dessus : le 2e émet LoadingServers qui écrase le SuccessLoadingServers du 1er.
    private var serverJob: Job? = null

    // 2026-05-21 (user "affiche au fur et à mesure" + "trier VF/VOSTFR/VO") :
    //   mise à jour PROGRESSIVE de la liste de serveurs pour les providers
    //   ProgressiveServersProvider. Porte la liste COMPLÈTE ré-ordonnée par
    //   bucket de langue (VF→VOSTFR→VO) à chaque nouveau lot, SANS relancer la
    //   lecture (≠ SuccessLoadingServers qui auto-joue). Le fragment remplace sa
    //   liste de serveurs et rafraîchit le picker en préservant la sélection.
    private val _serversReordered = MutableSharedFlow<List<Video.Server>>(extraBufferCapacity = 16)
    val serversReordered: SharedFlow<List<Video.Server>> = _serversReordered

    // 2026-06-30 : qualité vidéo détectée par le probe HTTP (GET m3u8 + parse RESOLUTION).
    // Émet Unit à chaque fois qu'au moins un Settings.Server.quality a été mis à jour.
    // Les fragments collectent ce flow pour rafraîchir le picker.
    private val _qualityUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val qualityUpdated: SharedFlow<Unit> = _qualityUpdated

    // Référence aux serveurs connus (pour écrire .quality dessus depuis le probe)
    @Volatile
    private var allKnownServers: List<Video.Server> = emptyList()

    // Job unique pour le probe qualité — idempotent (ne relance pas si déjà actif)
    private var qualityProbeJob: Job? = null

    // 2026-05-28 : flag indiquant que le flow progressif est encore en cours
    // de collecte. Si un onPlayerError arrive et que nextAutoFallbackServer==null,
    // le fragment peut attendre le prochain lot au lieu de déclarer forfait.
    @Volatile var progressiveStillCollecting: Boolean = false
        private set

    // 2026-07-05 : auto-recovery — si 0 serveur après tous les retries,
    //   on fait un nuclearCachePurge() et on retente UNE SEULE FOIS.
    //   Le flag empêche les boucles infinies (= au plus 1 purge par session player).
    @Volatile private var nuclearPurgeAttempted: Boolean = false

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

    /** 2026-06-21 (user "clic sur épisode dans le panel ne change pas
     *  d'épisode") : version qui DÉCLENCHE la navigation via le SharedFlow
     *  `playPreviousOrNextEpisode`. Le collector du fragment fait ensuite
     *  `releasePlayer + navigate(self, new args)`. C'est le même chemin que
     *  Next/Previous qui marche depuis le début. */
    fun switchToEpisode(episode: Video.Type.Episode) {
        playEpisode(episode)
        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(episode)
        }
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
        // 2026-07-03 : NE PLUS cancel qualityProbeJob ici — le probe est idempotent
        // et re-snapshotte allKnownServers à chaque passe. Le cancel provoquait un
        // 2e cycle complet (25s + passe 1 + passe 2) quand les serveurs progressifs
        // arrivaient → 4 passes au lieu de 3. Sans cancel, probeServerQualities()
        // appelé depuis le progressif voit le job actif et skip (L520).
        if (servers.isEmpty()) return
        // v69 + v74 (user "FRAnime galère à chaque serveur") :
        //   FranimeSession utilise un mutex global (WebView single-thread).
        //   Pré-extraire 4 servers en parallèle = file d'attente de 4 × 5s.
        //   v74 : détection par URL — match franime.fr peu importe le provider
        //   actif. Avant v74 on checkait juste UserPreferences.currentProvider
        //   name == "FRAnime", ce qui ratait quand AnimeSama provider servait
        //   des URLs franime.fr. Maintenant on regarde les server.src.
        val providerName = UserPreferences.currentProvider?.name.orEmpty()
        val usesFranimeSession = servers.firstOrNull()?.src?.contains("franime.fr", ignoreCase = true) == true ||
            providerName.equals("FRAnime", ignoreCase = true) ||
            providerName.contains("Franime", ignoreCase = true)
        val effectiveTopN = if (usesFranimeSession) 1 else PRE_EXTRACT_TOP_N
        val toExtract = servers.take(effectiveTopN)
        // 2026-07-06 (user « on avait nettoyé les logiques de classification de serveurs
        //   inutiles ») : SUPPRIMÉ le SCAN HEAD background qui pingait chaque embed en HEAD
        //   pour marquer le serveur « suspect/orange » (UNSURE). Classification coûteuse
        //   (~13 requêtes HEAD par ouverture) et peu fiable (faux négatifs) → retirée. Seul
        //   un vrai onPlayerError marque désormais un serveur. On garde uniquement la
        //   pré-extraction du top N ci-dessous.
        preExtractJob = viewModelScope.launch(Dispatchers.IO) {
            val jobs = mutableListOf<Job>()
            toExtract.forEachIndexed { idx, server ->
                jobs += launch {
                    // 2026-05-21 : ne PAS pré-extraire les sources natives Papadustream :
                    //   elles passent par un Cloudflare Turnstile que SEUL l'utilisateur
                    //   peut résoudre → pré-extraire en fond lancerait l'écran captcha
                    //   sans clic de l'user. On les résout uniquement au clic manuel.
                    if (server.src.contains("papadustream", ignoreCase = true) ||
                        server.src.contains("#xf=")) return@launch
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
                            // 2026-06-30 : probe qualité IMMÉDIATEMENT après extraction,
                            // AVANT le HEAD check qui peut invalider le cache via
                            // invalidateCache(). Comme ça, même si HEAD échoue, le
                            // label qualité est déjà écrit sur le serveur.
                            val extractedUrl = result.source
                            if (extractedUrl.isNotBlank()) {
                                try {
                                    val isHls = extractedUrl.contains(".m3u8", ignoreCase = true) ||
                                        result.type?.contains("mpegurl", ignoreCase = true) == true ||
                                        result.type?.contains("hls", ignoreCase = true) == true
                                    val q = if (isHls) {
                                        probeHlsQuality(extractedUrl, result.headers)
                                    } else null
                                    val finalQ = q ?: inferQualityFromText(extractedUrl) ?: inferQualityFromText(server.name)
                                    if (finalQ != null) {
                                        server.quality = finalQ
                                        _qualityUpdated.emit(Unit)
                                        Log.w("QualityProbe", "pre-extract[$idx]: ${server.name} → $finalQ (${durationMs}ms)")
                                    } else {
                                        Log.w("QualityProbe", "pre-extract[$idx]: ${server.name} → no quality (isHls=$isHls, url=${extractedUrl.take(60)})")
                                    }
                                } catch (e: Exception) {
                                    Log.w("QualityProbe", "pre-extract[$idx]: ${server.name} quality probe error: ${e.message?.take(60)}")
                                }
                            }

                            // 2026-07-07 (user « vire tout ce qui est un gain sauf si ça casse
                            //   de l'important ») : HEAD-check + flag-broken SUPPRIMÉS. Ils
                            //   pré-pingaient chaque stream en HEAD (avec une liste d'exceptions
                            //   qui gonflait : uqload/abyssa/ironbubble/luluvdo…), marquaient le
                            //   serveur « broken », et une 2ᵉ passe le ré-extrayait pour le
                            //   dé-flaguer. Redondant avec l'auto-switch onPlayerError, et le pick
                            //   ne skip plus les « broken » de toute façon. On garde juste la mise
                            //   en cache de l'extraction (clic instantané) + le probe qualité.
                            Log.d(
                                "PlayerViewModel",
                                "Pre-extract OK [$idx] ${server.name} → cached in ${durationMs}ms",
                            )
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
                            "Pre-extract skip [$idx] ${server.name}: ${e.message?.take(200)}",
                        )
                    }
                }
            }

            // 2026-07-07 : 2ᵉ passe SUPPRIMÉE (elle ne servait qu'à dé-flaguer les serveurs
            //   marqués broken par le HEAD-check — lui-même supprimé). La pré-extraction du
            //   top N ci-dessus suffit (cache = clic instantané) ; un échec réel de lecture
            //   est géré par l'auto-switch onPlayerError.
            jobs.joinAll()
        }
    }

    // ─── 2026-06-30 : probe qualité vidéo via HTTP GET simple ─────────
    // Après la pré-extraction, on parcourt les serveurs qui ont un résultat
    // en cache et on GET le master m3u8 pour parser RESOLUTION=WxH.
    // Fallback : inférence depuis l'URL (patterns "1080", "720", etc.).

    /** Providers IPTV exclus du probe (pas pertinent pour du live). */
    private val IPTV_PROVIDER_NAMES = setOf(
        "WiTv", "OlaTv", "VegetaTv", "Vavoo", "LiveTvHub", "SportLive"
    )

    /**
     * Lance le probe qualité en background pour TOUS les serveurs.
     * IDEMPOTENT : si déjà en cours, ne relance PAS (les lots progressifs
     * mettent à jour allKnownServers, la passe les relira automatiquement).
     *
     * Timing (user 2026-06-30) :
     *   - Passe 1 à T+40s  : lecture du cache d'extraction sur TOUS les
     *                         serveurs connus (allKnownServers). Les lots
     *                         progressifs ont le temps d'arriver.
     *   - Passe 2 à T+120s : extraction ACTIVE des serveurs restants.
     */
    private fun probeServerQualities(servers: List<Video.Server>) {
        val providerName = UserPreferences.currentProvider?.name.orEmpty()
        if (providerName in IPTV_PROVIDER_NAMES) return
        allKnownServers = servers

        // Idempotent : une seule instance à la fois
        if (qualityProbeJob?.isActive == true) {
            Log.w("QualityProbe", "Probe déjà en cours — skip (allKnownServers mis à jour: ${servers.size})")
            return
        }

        qualityProbeJob = viewModelScope.launch(Dispatchers.IO) {
            // 2026-07-04 : UNE seule boucle avec marquage `treated`. Chaque serveur traité 1 fois.
            // 2026-07-06 : ATTENDRE que preExtractJob finisse AVANT de boucler.
            //   Sibnet (et d'autres) étaient extraits 2× en parallèle : la pre-extract lançait
            //   l'extraction avec 15s timeout, et le probe (après 4s) trouvait le cache vide
            //   → relançait une DEUXIÈME extraction identique. En attendant que preExtract finisse,
            //   le cache est garanti rempli → peekCachedVideo hit → zéro double extraction.
            preExtractJob?.join()   // attend la fin de la pré-extraction (cache rempli)
            val treated = java.util.Collections.synchronizedSet(HashSet<String>())
            var idleRounds = 0
            while (isActive) {
                // Skip serveurs déjà traités OU qui ont déjà une qualité (assignée par nom)
                val batch = allKnownServers.filter { it.src.isNotBlank() && it.src !in treated && it.quality == null }
                if (batch.isEmpty()) {
                    // Rien de neuf : si le progressif a fini de collecter, on s'arrête après 2 tours.
                    if (!progressiveStillCollecting && ++idleRounds >= 2) break
                    delay(3_000L)
                    continue
                }
                idleRounds = 0
                val sem = kotlinx.coroutines.sync.Semaphore(4)
                batch.map { server ->
                    launch {
                        sem.acquire()
                        try {
                            treated.add(server.src)   // MARQUÉ traité (une seule tentative par serveur)
                            // Captcha (Papadustream) : non probable → marqué mais pas extrait.
                            if (server.src.contains("papadustream", ignoreCase = true) ||
                                server.src.contains("#xf=")) return@launch
                            // 2026-07-04 : 3 sources de Video (du plus rapide au plus lent) :
                            //   1) server.video (si le serveur a déjà été joué)
                            //   2) cache Extractor (si pré-extrait)
                            //   3) extraction fraîche (10s max)
                            val video = server.video
                                ?: com.streamflixreborn.streamflix.extractors.Extractor
                                    .peekCachedVideo(server.src)
                                ?: kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                                    com.streamflixreborn.streamflix.extractors.Extractor.extract(server.src, server)
                                }
                            if (video != null && video.source.isNotBlank()) {
                                probeAndAssignQuality(server, video, "probe")
                            }
                        } catch (e: Exception) {
                            Log.w("QualityProbe", "probe ${server.name}: ${e.message?.take(60)}")
                        } finally { sem.release() }
                    }
                }.joinAll()
                Log.w("QualityProbe", "qualité : traités ${treated.size}/${allKnownServers.size}")
            }
            val total = allKnownServers.count { it.quality != null }
            Log.w("QualityProbe", "Probe terminé — $total/${allKnownServers.size} qualités détectées")
        }
    }

    /**
     * Helper : probe HLS ou URL inference sur un Video, écrit server.quality, émet le flow.
     */
    private suspend fun probeAndAssignQuality(
        server: Video.Server,
        video: com.streamflixreborn.streamflix.models.Video,
        tag: String,
    ) {
        val videoUrl = video.source
        if (videoUrl.isBlank()) return

        val isHls = videoUrl.contains(".m3u8", ignoreCase = true) ||
            video.type?.contains("mpegurl", ignoreCase = true) == true ||
            video.type?.contains("hls", ignoreCase = true) == true

        val quality = if (isHls) probeHlsQuality(videoUrl, video.headers) else null
        val assigned = if (quality != null) {
            server.quality = quality
            Log.w("QualityProbe", "$tag: ${server.name} → $quality")
            true
        } else {
            // 2026-07-04 : fallback URL → nom du serveur. Les sources RSC (nmlnode)
            //   ont des URLs base64 sans résolution, mais le NOM contient "1080p" etc.
            val inferred = inferQualityFromText(videoUrl) ?: inferQualityFromText(server.name)
            if (inferred != null) {
                server.quality = inferred
                Log.w("QualityProbe", "$tag: ${server.name} → $inferred (inferred)")
                true
            } else {
                Log.w("QualityProbe", "$tag: ${server.name} → no quality (isHls=$isHls, url=${videoUrl.take(60)})")
                false
            }
        }
        if (assigned) {
            _qualityUpdated.emit(Unit)
            // 2026-06-30 (user "trie les serveurs par qualité au fur et à mesure,
            //   mais exclus VOSTFR et VO du tri") : re-trie les VF par résolution
            //   (desc) à chaque détection ; VOSTFR/VO gardent leur place.
            if (isVfServer(server)) emitQualitySortedServers()
        }
    }

    /** VRAI FR (bucket 0) = pas VOSTFR, pas VO, pas langue étrangère. */
    private fun isVfServer(s: Video.Server): Boolean {
        val n = s.name.lowercase()
        if (n.contains("vostfr") || n.contains("sous-titr")) return false
        if (Regex("""(^|[^a-z])vo([^a-z]|$)""").containsMatchIn(n)) return false
        if (n.contains(Regex("\\b(raw|eng|english|spa|ita|german|deu|jap)\\b"))) return false
        return true
    }

    /** Rang de qualité pour le tri (plus haut = meilleure résolution).
     *  Générique : extrait le NOMBRE du label → trie N'IMPORTE quelle résolution
     *  (576p, 900p, 1080p…), pas seulement les valeurs standard. */
    private fun qualityRank(label: String?): Int {
        if (label == null) return 0
        if (label.contains("4K", ignoreCase = true) || label.contains("2160")) return 2160
        // Premier nombre du label = la hauteur (ex "1080p" → 1080, "576p" → 576).
        val n = Regex("""\d+""").find(label)?.value?.toIntOrNull()
        if (n != null) return n
        // Pas de chiffre (SD, HD sans nombre…) → bas mais au-dessus de "inconnu".
        return 50
    }

    /** Re-trie les serveurs VF par qualité (desc), VOSTFR/VO EXCLUS (ordre conservé),
     *  et émet la liste réordonnée pour le picker. Tri stable → à qualité égale on
     *  garde l'ordre fiabilité existant. */
    private suspend fun emitQualitySortedServers() {
        val list = allKnownServers
        if (list.isEmpty()) return
        val vfSorted = list.filter { isVfServer(it) }.sortedByDescending { qualityRank(it.quality) }
        val nonVf = list.filter { !isVfServer(it) }
        val merged = vfSorted + nonVf
        allKnownServers = merged
        _serversReordered.emit(collapseIdenticalServers(merged))
    }

    /**
     * GET le master m3u8, parse les lignes RESOLUTION=WxH,
     * retourne la meilleure résolution trouvée sous forme de label.
     * Fallback : estimation via BANDWIDTH si pas de RESOLUTION.
     */
    private suspend fun probeHlsQuality(
        url: String,
        headers: Map<String, String>?,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = true
            // 2026-06-30 : senpai-stream (m3u8 VIP Frembed) renvoie 404 à la sonde
            //   sans Referer/Origin frembed + cf_clearance. On les ré-injecte ici
            //   (même recette que le pass-through Extractor) → la sonde passe en 200
            //   et le label qualité sort dès l'arrivée du 1er serveur.
            val effHeaders = HashMap<String, String>()
            headers?.let { effHeaders.putAll(it) }
            if (url.contains("senpai-stream", ignoreCase = true)) {
                if (!effHeaders.keys.any { it.equals("Referer", true) }) effHeaders["Referer"] = "https://frembed.hair/"
                if (!effHeaders.keys.any { it.equals("Origin", true) }) effHeaders["Origin"] = "https://frembed.hair"
                try {
                    val ck = android.webkit.CookieManager.getInstance().getCookie("https://frembed.hair/")
                    if (!ck.isNullOrBlank() && !effHeaders.keys.any { it.equals("Cookie", true) }) effHeaders["Cookie"] = ck
                } catch (_: Exception) {}
            }
            effHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Accept", "*/*")

            val body = try {
                val code = conn.responseCode
                if (code != 200) {
                    Log.w("QualityProbe", "probeHls: HTTP $code pour ${url.take(80)}")
                    return@withContext null
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            // 1) Parse RESOLUTION=<W>x<H> — prendre la hauteur max
            val resPattern = Regex("""RESOLUTION=\d+x(\d+)""")
            val heights = resPattern.findAll(body).mapNotNull { it.groupValues[1].toIntOrNull() }
            val maxHeight = heights.maxOrNull()
            if (maxHeight != null) return@withContext heightToLabel(maxHeight)

            // 2) Fallback : estimer via BANDWIDTH (bits/s)
            val bwPattern = Regex("""BANDWIDTH=(\d+)""")
            val bandwidths = bwPattern.findAll(body).mapNotNull { it.groupValues[1].toLongOrNull() }
            val maxBw = bandwidths.maxOrNull()
            if (maxBw != null) {
                val estimated = when {
                    maxBw >= 8_000_000 -> "1080p"
                    maxBw >= 3_500_000 -> "720p"
                    maxBw >= 1_500_000 -> "480p"
                    maxBw >= 600_000   -> "360p"
                    else               -> "SD"
                }
                Log.w("QualityProbe", "probeHls: pas de RESOLUTION, estimé via BANDWIDTH=$maxBw → $estimated")
                return@withContext estimated
            }

            // 3) Playlist simple (pas de variantes) → pas de qualité déterminable
            Log.w("QualityProbe", "probeHls: ni RESOLUTION ni BANDWIDTH dans ${url.take(80)}")
            null
        } catch (e: Exception) {
            Log.w("QualityProbe", "probeHls: erreur ${e.message} pour ${url.take(80)}")
            null
        }
    }

    /** Convertit une hauteur en pixels vers un label lisible. */
    private fun heightToLabel(height: Int?): String? {
        if (height == null || height <= 0) return null
        return when {
            height >= 2160 -> "4K"
            height >= 1440 -> "1440p"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height >= 480 -> "480p"
            height >= 360 -> "360p"
            else -> "${height}p"
        }
    }

    /** Inférence de qualité depuis l'URL (fallback si le GET m3u8 échoue). */
    /** Infère la qualité depuis n'importe quel texte (URL, nom de serveur, label).
     *  Cherche les patterns de résolution courants. */
    private fun inferQualityFromText(text: String): String? {
        val lower = text.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> "4K"
            lower.contains("1440") -> "1440p"
            lower.contains("1080") -> "1080p"
            lower.contains("720") -> "720p"
            lower.contains("480") -> "480p"
            lower.contains("360") -> "360p"
            else -> null
        }
    }

    companion object {
        /** Nombre de serveurs à pré-extraire en parallèle. 4 = compromis :
         *  couvre les 3-4 prochains clics probables (Voe, Uqload, Vidoza, Filemoon),
         *  reste safe sur Chromecast (~4 WebView max ≈ 600MB RAM).
         *  2026-05-12 : passé de 3→4 (user request, latency Tahiti élevée). */
        private const val PRE_EXTRACT_TOP_N = 4

        // 2026-07-05 : executor partagé pour orderByFrenchBuckets — isole l'appel du
        //   pool IO des coroutines et fournit un vrai timeout thread-based. Avant, on
        //   créait un newSingleThreadExecutor() par appel handleBatch (pool-7…pool-19+
        //   dans les logs = thread leak). Un CachedThreadPool avec daemon threads :
        //   - réutilise les threads entre appels successifs (≤60s idle)
        //   - daemon=true → ne retient pas le process si le ViewModel fuit
        //   - partagé par TOUTES les instances PlayerViewModel (companion)
        private val ofbExecutor: java.util.concurrent.ExecutorService by lazy {
            java.util.concurrent.Executors.newCachedThreadPool { r ->
                Thread(r, "ofb-worker").apply { isDaemon = true }
            }
        }
    }

    private fun getServers(videoType: Video.Type, id: String) {
        // 2026-07-04 : CANCEL le job précédent avant d'en lancer un nouveau.
        // Évite que 2 collectProgressiveServers tournent en parallèle (double setupNav,
        // playEpisode rapide, reloadServersAfterBypass). Le cancel() est safe : le try/catch
        // dans collectProgressiveServers attrape CancellationException.
        serverJob?.cancel()
        serverJob = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
        lastVideoType = videoType
        lastId = id
        // 2026-07-07 (fix probable blocage aplouf/non-progressif) : mettre l'enrichment
        //   (scraping du home) en pause AUSSI ici — pas seulement dans le chemin progressif.
        //   Sinon l'enrichment tient le mutex du service du provider et getServers hangue.
        com.streamflixreborn.streamflix.fragments.home.HomeViewModel.pauseEnrichmentForPlayback()
        // 2026-07-07 DIAGNOSTIC : si getServers n'a pas rendu la main en 12s (= HANG), on
        //   dumpe les piles de tous les threads app/OkHttp/coroutines dans le logcat pour
        //   voir EXACTEMENT où ça coince (mutex ? HTTP sans timeout ? autre ?).
        run {
            val selfJob = coroutineContext[kotlinx.coroutines.Job]
            viewModelScope.launch(Dispatchers.IO) {
                delay(12_000L)
                if (selfJob?.isActive == true) {
                    Log.e("HANGDUMP", "getServers >12s SANS FIN pour id=$id — DUMP THREADS ↓")
                    Thread.getAllStackTraces().forEach { (t, st) ->
                        val relevant = st.any { it.className.contains("streamflixreborn") } ||
                            t.name.contains("DefaultDispatcher") || t.name.contains("OkHttp")
                        if (relevant && st.isNotEmpty()) {
                            Log.e("HANGDUMP", "── THREAD '${t.name}' [${t.state}]")
                            st.take(12).forEach { Log.e("HANGDUMP", "      at $it") }
                        }
                    }
                    Log.e("HANGDUMP", "── FIN DUMP")
                }
            }
        }
        // 2026-05-21 : statut serveurs PAR TITRE — posé tôt pour que le scan HEAD de
        //   fond (preExtract) enregistre ses "scannés/suspects" sur le bon titre.
        com.streamflixreborn.streamflix.utils.TitleServerStatus.setCurrentTitle(id)
        _state.emit(State.LoadingServers)
        try {
            val provider = UserPreferences.currentProvider ?: run {
                // 2026-07-05 DIAG : ce return silencieux laissait le spinner infini.
                //   Log le nom stocké pour diagnostiquer POURQUOI c'est null.
                val storedName = try {
                    val profileId = com.streamflixreborn.streamflix.utils.ProfileManager.currentProfileIdOrDefault()
                    val prefs = com.streamflixreborn.streamflix.StreamFlixApp.instance
                        .getSharedPreferences("${com.streamflixreborn.streamflix.StreamFlixApp.instance.packageName}.preferences", android.content.Context.MODE_PRIVATE)
                    prefs.getString("CURRENT_PROVIDER_$profileId", "(absent)") ?: "(null)"
                } catch (e: Exception) { "(erreur: ${e.message})" }
                Log.e("ServDiag", "!! currentProvider=NULL (storedName='$storedName') → spinner infini. Video=$id")
                _state.emit(State.FailedLoadingServers(Exception("Provider non sélectionné. Retournez à l'accueil et choisissez un provider.")))
                return@launch
            }
            Log.d("ServDiag", "path provider=${provider.name} progressive=${provider is com.streamflixreborn.streamflix.providers.ProgressiveServersProvider}")

            // 2026-07-07 (FIX BLOCAGE GÉNÉRIQUE TOUS PROVIDERS) : le pool de connexions
            //   OkHttp est PARTAGÉ par tous les providers VOD (NetworkClient.sharedConnectionPool
            //   + Extractor.sharedClient). Un provider lourd en WebView/CF (DessinAnime) laisse
            //   des connexions MORTES/half-open dans le pool. Le provider SUIVANT (aplouf, Wiflix,
            //   n'importe lequel) réutilise une de ces connexions mortes → socketRead HANGUE
            //   jusqu'au callTimeout (45s) → « ça mouline » sur TOUS les providers. C'est
            //   exactement le repro « je vais sur DessinAnime, tout bloque, puis aplouf bloque ».
            //   FIX : à CHAQUE ouverture VOD, on ÉVICTE les connexions idle des pools partagés
            //   AVANT de charger les serveurs → le provider courant part sur des connexions
            //   fraîches, il n'hérite jamais des sockets morts du provider précédent.
            //   evictAll() ne ferme QUE les connexions idle (les actives ne sont pas touchées),
            //   coût quasi nul. IPTV EXCLU (le pool y garde les connexions CDN chaudes pour le
            //   zapping — on n'y touche pas, cf règle « IPTV on touche à rien »).
            if (provider !is IptvProvider) {
                runCatching {
                    com.streamflixreborn.streamflix.utils.NetworkClient.sharedConnectionPool.evictAll()
                    com.streamflixreborn.streamflix.extractors.Extractor.sharedClient.connectionPool.evictAll()
                    Log.d("ServDiag", "pools évictés avant chargement (isolation inter-providers)")
                }
            }

            // 2026-05-21 : si le provider sait streamer ses serveurs au fur et à
            //   mesure, on prend le chemin progressif (1er lot affiché tout de
            //   suite, le reste s'ajoute sans bloquer).
            if (provider is com.streamflixreborn.streamflix.providers.ProgressiveServersProvider) {
                collectProgressiveServers(provider, id, videoType)
                return@launch
            }
            // 2026-05-22 : démarrer le collecteur additionalServers AVANT le getServers
            // pour IPTV, car WiTV v2 émet ses serveurs via _additionalServers DANS
            // getServersInternal. Si on lance le collecteur après, les émissions sont perdues.
            additionalServerJob?.cancel()
            if (provider is IptvProvider) {
                additionalServerJob = viewModelScope.launch(Dispatchers.IO) {
                    provider.additionalServersFlow.collect { server ->
                        Log.d("PlayerViewModel", "Additional server arrived: ${server.name}")
                        _additionalServer.emit(server)
                    }
                }
            }

            // 2026-07-07 (FIX BLOCAGE GÉNÉRIQUE) : le chemin non-progressif (aplouf + tous
            //   les providers TmdbProvider) n'avait AUCUN plafond de temps autour de
            //   fetchServersWithRetry → un getServers coincé dans un execute() OkHttp (connexion
            //   morte) pouvait bloquer 45s × retries SANS que l'écran ne se débloque jamais.
            //   On cape à 35s pour les NON-IPTV : si dépassé → traité comme 0 serveur → la
            //   recovery existante (nuclearCachePurge + retry ci-dessous) prend le relais et
            //   l'écran ne reste plus figé indéfiniment. IPTV EXCLU (serveurs via
            //   additionalServersFlow, getServers peut légitimement rendre vide, pas de cap).
            val rawServers = if (provider is IptvProvider) {
                fetchServersWithRetry(provider, id, videoType)
            } else {
                kotlinx.coroutines.withTimeoutOrNull(35_000L) {
                    fetchServersWithRetry(provider, id, videoType)
                } ?: run {
                    Log.w("ServDiag", "!! fetchServersWithRetry (non-prog) > 35s → traité comme 0 serveur (recovery)")
                    emptyList()
                }
            }
            if (rawServers.isEmpty()) {
                // Pour IPTV : les serveurs arrivent via additionalServersFlow
                // (onglet Chaîne), pas via getServers. Ne pas lancer d'exception.
                if (provider is IptvProvider) {
                    Log.d("PlayerViewModel", "IPTV: 0 serveurs sync, émission via additionalServers (Chaîne tab)")
                    _state.emit(State.SuccessLoadingServers(emptyList()))
                    return@launch
                }
                // 2026-07-05 : AUTO-RECOVERY (chemin non-progressif)
                if (!nuclearPurgeAttempted) {
                    nuclearPurgeAttempted = true
                    val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext
                    Log.w("ServDiag", "!! 0 serveur (non-prog) après retries → NUCLEAR CACHE PURGE + retry")
                    com.streamflixreborn.streamflix.utils.ProviderCacheRefresh.nuclearCachePurge(ctx)
                    kotlinx.coroutines.delay(500)
                    getServers(videoType, id)
                    return@launch
                }
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
            // 2026-07-04 : qualité IMMÉDIATE depuis le nom du serveur (avant affichage)
            for (srv in servers) { if (srv.quality == null) srv.quality = inferQualityFromText(srv.name) }

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
            // 2026-07-07 (user « le tri se fait QUE avec la langue, les favoris et la qualité,
            //   tout le reste on s'en fout ; si un tri peut interférer et bouffer de la mémoire,
            //   on vire ») : SUPPRIMÉ le tri « broken » (brokenServerNames() itérait toute la map
            //   serverHealth). La liste garde son ordre langue/qualité/favoris. Un serveur qui
            //   foire est géré par l'auto-switch onPlayerError, PAS par un pré-tri.
            val sortedServers = servers

            // 2026-05-24 : reprise de lecture — si on a un serveur qui a marché
            // la dernière fois pour ce contenu, on le met en premier (auto-play dessus).
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext
            val lastServerId = com.streamflixreborn.streamflix.utils.LastWorkingServer.get(ctx, id)
            val finalServers = if (lastServerId != null && sortedServers.any { it.id == lastServerId }) {
                val last = sortedServers.first { it.id == lastServerId }
                listOf(last) + sortedServers.filter { it.id != lastServerId }
            } else {
                sortedServers
            }
            if (lastServerId != null && finalServers.firstOrNull()?.id == lastServerId) {
                Log.d("PlayerViewModel", "Reprise: serveur '${finalServers.first().name}' remis en 1er (dernier qui a marché)")
            }

            Log.d("PlayerViewModel", "Ricerca server completata: ${finalServers.size} server trovati")
            _state.emit(State.SuccessLoadingServers(collapseIdenticalServers(finalServers)))

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
            preExtractTopServersInBackground(finalServers)

            // 2026-06-30 : probe qualité vidéo en background (HTTP GET simple)
            probeServerQualities(finalServers)

            // NB: le collecteur additionalServerJob est déjà démarré AVANT le getServers
            // (cf bloc plus haut) pour ne pas rater les émissions IPTV synchrones.
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore ricerca server: ", e)
            _state.emit(State.FailedLoadingServers(e))
        }
        }
    }

    /**
     * 2026-05-21 : chemin PROGRESSIF. Collecte les lots de serveurs au fur et à
     * mesure que les sources répondent. Le 1er lot débloque l'écran via
     * SuccessLoadingServers (auto-play du meilleur natif) ; les lots suivants
     * mettent à jour la liste COMPLÈTE ré-ordonnée par bucket de langue via
     * serversReordered (sans relancer la lecture). Si rien n'arrive → fallback
     * batch puis erreur.
     */
    private suspend fun collectProgressiveServers(
        provider: com.streamflixreborn.streamflix.providers.ProgressiveServersProvider,
        id: String,
        videoType: Video.Type,
    ) {
        val accumulated = mutableListOf<Video.Server>()
        val seenIds = HashSet<String>()
        // 2026-07-07 : dedup par URL embed + langue — élimine les doublons entre
        //   serveurs NATIFS du provider courant et serveurs BACKUP du registre.
        //   Ex : Movix natif retourne "wiflix-fr-0" (Wiflix·Upbolt VF) avec la même
        //   embed URL que "bkreg::Wiflix::xyz" (Wiflix dans la boucle générique).
        //   Le dedup par id ne les attrape pas → doublon visible dans le picker.
        //   Ce Set filtre par langue+URL normalisée (host+path, sans token/signature).
        val seenSrcKeys = HashSet<String>()
        var firstEmitted = false
        // 2026-07-07 : découplage AFFICHAGE / AUTO-PLAY. firstEmitted = « affiché » ;
        //   autoPlayEmitted = « lecture auto autorisée » (VF présent, ou 12s écoulées).
        var autoPlayEmitted = false
        progressiveStillCollecting = true
        Log.d("ServDiag", "PROG enter id=$id")
        // 2026-07-04 : REMIS. L'enrichissement home (scrape flemmix genres + jaquettes TMDB)
        //   INONDE le réseau/CF pendant qu'on est déjà dans le player → il ÉTOUFFE le scrape
        //   natif des serveurs (mesuré : natif à 15s au lieu de 2s, log plein de requêtes
        //   flemmix "36167-marsupilami" / "serie-en-streaming" pendant l'ouverture Punisher).
        //   L'annulation stoppe ce flood. Elle N'ÉMET aucun état → ne touche pas la navigation.
        com.streamflixreborn.streamflix.fragments.home.HomeViewModel.pauseEnrichmentForPlayback()
        // 2026-06-03 (user "patch qui attend qui est vraiment du FR. Si y a pas
        //   on se rabat sur VOSTFR/VO. Sur tous les providers SAUF anime") :
        //   si le 1er lot ne contient que VOSTFR/VO, on attend max FR_GRACE_MS
        //   qu'un VRAI FR arrive avant de lancer l'auto-play. Évite que l'user
        //   tombe sur VOSTFR alors qu'un FR Wiflix/etc. arrivait 2-3 sec plus
        //   tard. Désactivé sur providers anime (VOSTFR = comportement attendu).
        val providerName = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider?.name ?: ""
        val isAnimeProvider = providerName.contains("Anime", ignoreCase = true) ||
            providerName.contains("Manga", ignoreCase = true) ||
            providerName.equals("Franime", ignoreCase = true) ||
            providerName.equals("DessinAnime", ignoreCase = true)
        // 2026-06-03 (user "patch qui attend qui est vraiment du FR. Si y a pas on se rabat
        //   sur VOSTFR/VO. Sur tous les providers SAUF anime") : si le 1er lot ne contient que
        //   VOSTFR/VO, on attend max FR_GRACE_MS qu'un VRAI FR arrive avant l'auto-play.
        //   2026-07-04 : REMIS après un essai à 0 qui lançait du VO d'entrée (user "il lance du
        //   VO en arrivant, le filtre fallait le garder le VF"). Le vrai fix de la lenteur =
        //   head-start natif + providers WebView en 2ᵉ vague (contention WebView), PAS la grâce.
        // 2026-07-07 (user « une carence de 12s : si rien, ça démarre sur le 1er serveur venu ») :
        //   6s → 12s. Ne bloque QUE l'auto-play (pas l'affichage), donc on peut être patient.
        // 2026-07-07 (user « DessinAnime auto-play sur du VOSTFR, il faut attendre le FR ») :
        //   DessinAnime est VF-dub SANS dossier de langue et ses backups sont MIXTES
        //   (FrenchStream VF/VFQ, Wiflix, 1Jour1Film…). Contrairement à AnimeSama/FrenchManga/
        //   etc. où l'user CHOISIT la langue (→ grâce 0), ici il faut la GRÂCE FR : attendre un
        //   VF avant l'auto-play, fallback VOSTFR seulement s'il n'y a que ça. La grâce n'attend
        //   PAS si un VF arrive vite (elle démarre dessus dès qu'il arrive) ; elle ne bloque
        //   l'auto-play que tant qu'il n'y a QUE du VOSTFR, jusqu'au fallback 12s.
        val isDessinAnimeProvider = providerName.equals("DessinAnime", ignoreCase = true)
        val FR_GRACE_MS: Long = if (isAnimeProvider && !isDessinAnimeProvider) 0L else 12_000L
        val gracePeriodStartedAt = mutableListOf<Long>()  // wrapper pour val mutable
        fun isVf(s: Video.Server): Boolean {
            val n = s.name.lowercase()
            // VRAI FR = pas VOSTFR + pas VO. bucket 0 dans orderByFrenchBuckets.
            if (n.contains("vostfr") || n.contains("sous-titr")) return false
            val voRegex = Regex("""(^|[^a-z])vo([^a-z]|$)""")
            if (voRegex.containsMatchIn(n)) return false
            if (n.contains(Regex("\\b(raw|eng|english|spa|ita|german|deu|jap)\\b"))) return false
            return true
        }
        try {
            // 2026-07-04 (reconnexion registre central) : on MERGE le flow natif du provider
            //   avec le REGISTRE central de backups → les serveurs natifs (rapides) arrivent
            //   EN PREMIER, les backups arrivent en progressif au fil de l'eau, tous dans la
            //   MÊME logique d'accumulation/tri/émission. Aucun serveur rapide n'est bloqué par
            //   un backup lent (émission dès qu'un lot arrive). tmdbId si l'id est numérique
            //   (backups par id : Movix/Cloudstream/Nakios/Webflix/Embed), sinon backups par
            //   TITRE seuls (matching STRICT). exclude = le provider courant (pas de doublon).
            val tmdbForBackup = id.takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
            val backupFlow = com.streamflixreborn.streamflix.utils.BackupRegistry.fetchAll(
                tmdbId = tmdbForBackup,
                videoType = videoType,
                exclude = setOf(providerName),
                titleHint = com.streamflixreborn.streamflix.utils.BackupRegistry.titleFromId(id),
                isAnimeProvider = isAnimeProvider,
            )
            // 2026-07-04 : porte de démarrage des backups (registre). Ouverte APRÈS le 1er
            //   tri natif (fait device au repos) → le natif s'affiche vite, PUIS le registre
            //   démarre. Sinon le registre (~20 sources //) saturait la TV et affamait le tri
            //   orderByFrenchBuckets du 1er lot (mesuré 11s au lieu de <100ms).
            val backupGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            // handler PARTAGÉ (lot natif OU backup) : filtre/accumule/tri par langue/émet
            //   avec la grâce FR (attend un VF avant l'auto-play, sinon fallback VOSTFR/VO).
            suspend fun handleBatch(batch: List<Video.Server>) {
                val _tHB = System.currentTimeMillis()
                Log.d("ServDiag", "PROG batch reçu size=${batch.size} firstEmitted=$firstEmitted")
                try {
                val fresh = batch.filter { it.id.isNotBlank() && seenIds.add(it.id) }
                    // 2026-07-07 : 2nd filtre par URL embed normalisée + bucket langue.
                    //   Élimine les doublons cross-source (natif Movix wiflix-fr-0 vs
                    //   bkreg::Wiflix::id — même embed URL, ids différents). Les serveurs
                    //   sans src (src vide = résolveur par id) passent toujours.
                    .filter { srv ->
                        if (srv.src.isBlank()) return@filter true
                        val n = srv.name.lowercase()
                        val lang = when {
                            n.contains("vostfr") || n.contains("vost") || n.contains("sous-titr") -> "vostfr"
                            Regex("""(^|[^a-z])vo([^a-z]|$)""").containsMatchIn(n) ||
                                n.contains(Regex("""\b(raw|eng|english|jap|vosa)\b""")) -> "vo"
                            else -> "vf"
                        }
                        val normUrl = srv.src.substringBefore("#").trim()
                            .substringBefore("?").trimEnd('/').lowercase()
                        seenSrcKeys.add("$lang|$normUrl")
                    }
                if (fresh.isEmpty()) {
                    // 2026-07-04 DIAG : logger POURQUOI c'est vide
                    val blankIds = batch.count { it.id.isBlank() }
                    val dupes = batch.size - blankIds - fresh.size
                    Log.w("ServDiag", "PROG fresh=VIDE! batch=${batch.size} blankIds=$blankIds dupes=$dupes seenIds=${seenIds.size}")
                    if (batch.isNotEmpty()) Log.w("ServDiag", "PROG 1er server id='${batch[0].id}' name='${batch[0].name}'")
                    return
                }
                Log.d("ServDiag", "PROG fresh=${fresh.size} ids=${fresh.joinToString(",") { it.id.take(20) }}")
                // 2026-07-04 (user "à chaque arrivée de source il est censé la contrôler
                //   et afficher la qualité si c'est possible") : détection IMMÉDIATE de la
                //   qualité depuis le NOM du serveur. Pas besoin d'attendre le probe/extract.
                //   Ex : "Hydrax 1080p" → quality="1080p" posé AVANT affichage dans le picker.
                for (srv in fresh) {
                    if (srv.quality == null) srv.quality = inferQualityFromText(srv.name)
                }
                accumulated.addAll(fresh)
                Log.d("ServDiag", "PROG avant orderByFrenchBuckets accumulated=${accumulated.size}")
                // 2026-07-05 : timeout RÉEL (thread Java, pas coopératif) pour empêcher
                //   orderByFrenchBuckets de bloquer indéfiniment. Le bug : parfois
                //   orderByFrenchBuckets ne retourne JAMAIS (thread disparaît). Comme
                //   withTimeoutOrNull est coopératif, il ne peut pas interrompre du code
                //   bloquant → le spinner tourne indéfiniment. Avec un executor + future.get(5s),
                //   on a un vrai deadline. Si ça bloque → fallback unsorted → l'user a ses serveurs.
                val accSnapshot = accumulated.toList() // snapshot immuable pour le thread
                val ordered: List<Video.Server> = try {
                    // 2026-07-05 : executor PARTAGÉ (companion ofbExecutor) au lieu de
                    //   créer/détruire un newSingleThreadExecutor par appel (thread leak).
                    val future = ofbExecutor.submit(java.util.concurrent.Callable { orderByFrenchBuckets(accSnapshot) })
                    future.get(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    Log.e("ServDiag", "!! PROG orderByFrenchBuckets TIMEOUT 5s !! accumulated=${accumulated.size} — fallback unsorted")
                    accSnapshot // fallback : serveurs non triés
                } catch (e: java.util.concurrent.ExecutionException) {
                    Log.e("ServDiag", "!! PROG orderByFrenchBuckets ERREUR: ${e.cause?.message}", e.cause)
                    accSnapshot
                } catch (e: Exception) {
                    Log.e("ServDiag", "!! PROG orderByFrenchBuckets EXCEPTION: ${e.message}", e)
                    accSnapshot
                }
                Log.i("ServDiagT", "PROG orderByFrenchBuckets(${accumulated.size}) fait en ${System.currentTimeMillis()-_tHB}ms")
                // 2026-07-04 : le 1er tri natif est fait (device au repos) → on OUVRE la porte
                //   du registre. Les backups démarrent maintenant, sans avoir affamé ce tri.
                if (!backupGate.isCompleted) {
                    Log.i("ServDiag", "PROG backupGate ouvert après traitement 1er lot natif")
                    backupGate.complete(Unit)
                }
                if (ordered.isEmpty()) {
                    Log.d("ServDiag", "PROG lot reçu mais ordered=0, on attend le suivant")
                    return
                }
                if (!firstEmitted) {
                    firstEmitted = true
                    val hasFr = ordered.any { isVf(it) }
                    // 2026-07-09 : DÉLAI DE STABILISATION avant auto-play. On AFFICHE les serveurs
                    //   immédiatement (autoPlay=false) pour que le user les voie, MAIS on ne lance
                    //   PAS l'auto-play tout de suite. On attend STABILIZE_MS (4s) pour laisser
                    //   d'autres lots arriver et les faux matchs se faire évincer. Après ce délai,
                    //   on émet autoPlay=true avec la liste STABILISÉE (allKnownServers courante).
                    //   Combine avec la grâce FR : si pas de VF au 1er lot, on attend aussi le VF
                    //   (jusqu'à FR_GRACE_MS). L'auto-play part au max(STABILIZE_MS, grâce VF).
                    // 2026-07-09 (user « au moins 3 ou 4 secondes de débattement avant de lancer ») :
                    //   1.5s → 4s. Les mauvais serveurs arrivent et disparaissent dans ce délai ;
                    //   l'auto-play ne lance que sur une liste stabilisée.
                    val STABILIZE_MS = 4000L
                    // 2026-07-07 (user « affichage instantané ; la grâce ne bloque QUE le player ;
                    //   si rien au bout de 12s → 1er serveur venu ») : on AFFICHE tout de suite.
                    //   auto-play retardé par le délai de stabilisation.
                    autoPlayEmitted = false  // JAMAIS d'auto-play immédiat sur le 1er lot
                    if (gracePeriodStartedAt.isEmpty()) {
                        gracePeriodStartedAt.add(System.currentTimeMillis())
                        viewModelScope.launch {
                            // Attendre la stabilisation (4s) — les faux serveurs ont le temps
                            //   d'être évincés et les bons d'arriver.
                            kotlinx.coroutines.delay(STABILIZE_MS)
                            // Puis appliquer la grâce FR si pas de VF
                            val hasVfNow = allKnownServers.any { isVf(it) }
                            if (!hasVfNow && FR_GRACE_MS > STABILIZE_MS) {
                                // Pas de VF → attendre le reste de la grâce FR
                                kotlinx.coroutines.delay(FR_GRACE_MS - STABILIZE_MS)
                            }
                            if (!autoPlayEmitted) {
                                autoPlayEmitted = true
                                val reason = if (allKnownServers.any { isVf(it) }) "VF trouvé" else "timeout ${FR_GRACE_MS}ms"
                                Log.i("ServDiag", "PROG auto-play débloqué après stabilisation ($reason) → ${allKnownServers.size} serveurs")
                                _state.emit(State.SuccessLoadingServers(collapseIdenticalServers(allKnownServers), autoPlay = true))
                            }
                        }
                    }
                    val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext
                    val lastSrvId = com.streamflixreborn.streamflix.utils.LastWorkingServer.get(ctx, id)
                    val finalOrdered = if (lastSrvId != null && ordered.any { it.id == lastSrvId }) {
                        val last = ordered.first { it.id == lastSrvId }
                        listOf(last) + ordered.filter { it.id != lastSrvId }
                    } else ordered
                    val collapsed = collapseIdenticalServers(finalOrdered)
                    Log.i("StreamFlixES", "[SERVERS PROGRESSIVE] 1er lot AFFICHÉ : ${finalOrdered.size} serveurs (autoPlay=false, stabilisation ${STABILIZE_MS}ms)")
                    _state.emit(State.SuccessLoadingServers(collapsed, autoPlay = false))
                    preExtractTopServersInBackground(finalOrdered)
                    allKnownServers = finalOrdered
                } else {
                    allKnownServers = ordered
                    // 2026-07-09 : l'auto-play est géré par le timer de stabilisation (1.5s).
                    //   Un VF qui arrive dans un lot suivant PENDANT la stabilisation sera pris
                    //   en compte quand le timer expire (il lit allKnownServers à ce moment).
                    //   On ne force PLUS l'auto-play immédiat ici — le timer s'en charge.
                    Log.d("PlayerViewModel", "[SERVERS PROGRESSIVE] +${fresh.size} → ${ordered.size} (ré-ordonné)")
                    _serversReordered.emit(collapseIdenticalServers(ordered))
                }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.w("ServDiag", "PROG handleBatch ANNULÉ (coroutine cancel) : ${e.message}")
                    throw e  // re-throw pour respecter le contrat coroutine
                } catch (e: Exception) {
                    Log.e("ServDiag", "PROG handleBatch EXCEPTION INATTENDUE : ${e.message}", e)
                    // Ne pas re-throw → on continue à collecter les lots suivants
                } catch (e: Throwable) {
                    // 2026-07-05 : attraper les Error (StackOverflow, OOM) qui échappent
                    //   à catch(Exception). Sans ça, une Error dans orderByFrenchBuckets
                    //   tue le thread silencieusement → spinner infini.
                    Log.e("ServDiag", "PROG handleBatch ERROR FATAL (Throwable) : ${e.javaClass.simpleName}: ${e.message}", e)
                    // Ne pas re-throw → on continue
                }
            }

            // 2026-07-04 (user "les backups ne doivent PAS empêcher les natifs de se charger" +
            //   "arrivée des natifs = départ des backups ; sinon au bout de 10s les backups
            //   démarrent quand même" + "en moyenne les natifs mettent quelques secondes s'ils
            //   sont pas freinés") : PORTE de démarrage des backups.
            //   Le natif charge et s'AFFICHE d'abord (tri device au repos), PUIS la porte
            //   s'ouvre (dans handleBatch, après le 1er tri natif) → le registre démarre.
            //   Filet : si le natif est muet, la porte s'ouvre au bout de 10s.
            //   merge() → handleBatch sérialisé (aucune mutation concurrente des collections).
            val _tNat = System.currentTimeMillis()
            val nativeFlow = provider.getServersProgressive(id, videoType)
                .onEach { Log.i("ServDiagT", "NATIVE flow a ÉMIS size=${it.size} à ${System.currentTimeMillis()-_tNat}ms") }
                // 2026-07-04 (user "Cloudstream met 20s") : si le natif FINIT sans rien émettre
                //   (ex Cloudstream search API down → 0 serveur), on OUVRE la porte des backups
                //   TOUT DE SUITE au lieu d'attendre le timeout de 20s. Sinon un provider dont le
                //   natif rend 0 vite faisait quand même patienter 20s avant les backups.
                .onCompletion {
                    if (!backupGate.isCompleted) {
                        Log.i("ServDiag", "PROG backupGate ouvert par FIN du flux natif")
                        backupGate.complete(Unit)
                    }
                }
                // 2026-07-04 : forcer l'exécution du flow natif sur IO — sinon
                //   withContext(IO) dans channelFlow reprend sur Main (viewModelScope),
                //   et si le main-thread est occupé (Glide/UI), l'émission est retardée
                //   de 10+ secondes voire indéfiniment.
                .flowOn(kotlinx.coroutines.Dispatchers.IO)
            // 2026-07-04 : PORTE RESTAURÉE (user "en enlevant le blocage 10s, Wiflix ne s'est pas
            //   chargé"). Les natifs WebJS (Wiflix) tournent dans le MÊME WebView que les backups
            //   WebView-lourds → sans porte, les backups démarrent à t=0 et entrent en CONTENTION
            //   sur le WebView avec le natif Wiflix → le natif ne se charge plus. La porte ouvre
            //   les backups après le 1er tri natif (instantané pour les providers à natif rapide)
            //   OU à la fin du flux natif OU au bout de 12s (filet si natif muet). 20s→12s pour
            //   réduire l'attente sur les natifs vraiment lents sans casser la protection Wiflix.
            //   2026-07-04 : la porte NE S'APPLIQUE QU'AUX WebJsProvider (contention WebView).
            //   Les providers natifs Kotlin (FrenchAnime, Papadustream, FrenchManga, Franime,
            //   VoirAnime, AnimeSama, etc.) démarrent les backups IMMÉDIATEMENT en parallèle.
            val isWebJsProvider = provider is com.streamflixreborn.streamflix.providers.WebJsProvider
            // 2026-07-07 (user « on met les backups en PRIORITÉ sur DessinAnime ») :
            //   DessinAnime a un natif WebView CF LENT et souvent en échec (challenge
            //   Cloudflare 12-45s). Le gater derrière ce natif retenait les backups
            //   (mesuré : « backupGate ouvert par FIN du flux natif » = les backups
            //   attendaient le timeout 45s du WebView). → On NE GATE PAS DessinAnime :
            //   ses backups partent à t=0 comme un provider natif Kotlin. Les autres
            //   WebJS gardent la gate anti-famine, mais cap réduit 12s→4s (les serveurs
            //   ne doivent jamais rester bloqués si le natif est muet/coincé sur CF).
            val isDessinAnime = com.streamflixreborn.streamflix.utils.UserPreferences
                .currentProvider?.name?.equals("DessinAnime", ignoreCase = true) ?: false
            val gatedBackupFlow = if (isWebJsProvider && !isDessinAnime) {
                backupFlow.onStart {
                    val opened = kotlinx.coroutines.withTimeoutOrNull(4_000L) { backupGate.await() }
                    if (opened == null) Log.i("ServDiag", "PROG backupGate ouvert par TIMEOUT 4s (natif muet)")
                }
            } else {
                Log.i("ServDiag", "PROG ${if (isDessinAnime) "DessinAnime → backups PRIORITAIRES" else "provider natif Kotlin"} → backups IMMÉDIATS (pas de gate)")
                backupFlow // pas de porte → backups démarrent à t=0
            }
            // 2026-07-04 TEST (user "désactive le pack serveur, on teste avec QUE les natifs
            //   Wiflix") : si false, on collecte UNIQUEMENT le flux natif (aucun registre, aucun
            //   merge, aucune porte) → isole si le trou de 15s vient du merge/registre ou du natif.
            val REGISTRY_BACKUPS_ENABLED = true
            // 2026-07-04 (user "le player ne doit pas se couper tant que les serveurs
            //   n'ont pas fini d'arriver, timeout 2 min minimum") : on laisse le merge
            //   tourner jusqu'à ce que TOUS les flows soient terminés, avec un plafond
            //   absolu de 2 minutes. Aucun serveur lent n'est coupé prématurément.
            // 2026-07-05 v2 (user "2 min ça bloque l'écran, c'est trop" + "45") : 120s → 45s.
            //   Avec le nuclearCachePurge automatique, un cache empoisonné se vide
            //   au 1er 0-serveur → pas besoin d'attendre longtemps. 45s suffit pour
            //   les sources lentes sans bloquer l'écran inutilement.
            val COLLECT_TIMEOUT_MS = 120_000L
            if (REGISTRY_BACKUPS_ENABLED) {
                kotlinx.coroutines.withTimeoutOrNull(COLLECT_TIMEOUT_MS) {
                    kotlinx.coroutines.flow.merge(nativeFlow, gatedBackupFlow).collect { handleBatch(it) }
                } ?: Log.w("ServDiag", "PROG collecte stoppée après ${COLLECT_TIMEOUT_MS/1000}s (plafond atteint)")
            } else {
                Log.i("ServDiag", "PROG TEST natifs-seuls (registre désactivé)")
                kotlinx.coroutines.withTimeoutOrNull(COLLECT_TIMEOUT_MS) {
                    nativeFlow.collect { handleBatch(it) }
                }
            }
        } catch (e: Exception) {
            if (firstEmitted) {
                Log.w("PlayerViewModel", "Progressive: erreur après affichage (ignorée) : ${e.message}")
                progressiveStillCollecting = false
                return
            }
            Log.w("PlayerViewModel", "Progressive: échec avant 1er lot, fallback batch : ${e.message}")
        }
        progressiveStillCollecting = false
        // 2026-07-06 (user « QualityProbe seulement quand plus aucun serveur n'arrive ») :
        //   la collecte est TERMINÉE ici (merge fini OU plafond 45s). On lance le probe
        //   MAINTENANT sur la liste COMPLÈTE — fini la contention CPU/réseau avec
        //   l'extraction de lecture pendant le lancement de l'épisode. Le probe reste
        //   fonctionnel (qualités détectées), juste décalé après l'arrivée des serveurs.
        if (firstEmitted) {
            probeServerQualities(allKnownServers)
        }
        if (!firstEmitted) {
            // 2026-07-05 v2 (user "au premier coup 0 serveur tu vides directement") :
            //   purge IMMÉDIATE dès que le flux progressif n'a rien donné — AVANT le
            //   fallback batch. Le cache OkHttp / cookies CF empoisonnés bloquent
            //   probablement les serveurs ; les vider MAINTENANT donne au fallback batch
            //   une chance de marcher avec un cache propre. Et même si ça ne marche pas,
            //   le PROCHAIN lancement partira sur un cache vierge.
            if (!nuclearPurgeAttempted) {
                nuclearPurgeAttempted = true
                val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext
                Log.w("ServDiag", "!! 0 serveur progressif → NUCLEAR CACHE PURGE immédiate")
                com.streamflixreborn.streamflix.utils.ProviderCacheRefresh.nuclearCachePurge(ctx)
                kotlinx.coroutines.delay(300)
            }
            // Fallback batch AVEC cache propre maintenant
            val raw = try {
                fetchServersWithRetry(provider as com.streamflixreborn.streamflix.providers.Provider, id, videoType)
            } catch (e: Exception) {
                _state.emit(State.FailedLoadingServers(e))
                return
            }
            if (raw.isEmpty()) {
                val name = (provider as? com.streamflixreborn.streamflix.providers.Provider)?.name ?: "ce provider"
                _state.emit(State.FailedLoadingServers(Exception("Aucune source disponible pour ce contenu sur $name. Essayez un autre provider ou réessayez plus tard.")))
                return
            }
            val ordered = orderByFrenchBuckets(raw)
            _state.emit(State.SuccessLoadingServers(collapseIdenticalServers(ordered)))
            preExtractTopServersInBackground(ordered)
            probeServerQualities(ordered)
        }
    }

    /**
     * Ordonne par PRIORITÉ DE LANGUE (VF=0 → VOSTFR=1 → VO=2) en gardant, à
     * l'intérieur de chaque bucket, l'ordre de fiabilité d'ExtractorRanker.
     * sortedBy est stable → la fiabilité est préservée comme axe secondaire.
     */
    // 2026-07-04 : classifie la langue d'un serveur depuis son nom
    private fun serverLang(s: Video.Server): String {
        val n = s.name.lowercase()
        return when {
            n.contains("vostfr") || n.contains("sous-titr") -> "vostfr"
            Regex("""(?i)\b(vf|vff|vfq|vfi)\b""").containsMatchIn(n)
                || n.contains("(vf)") -> "vf"
            Regex("""(^|[^a-z])vo([^a-z]|$)""").containsMatchIn(n)
                || n.contains(Regex("\\b(raw|eng|english|spa|ita|german|deu|jap)\\b")) -> "vo"
            else -> "unknown"
        }
    }

    private fun orderByFrenchBuckets(list: List<Video.Server>): List<Video.Server> {
        val _t = System.currentTimeMillis()
        Log.d("OFB", "ENTER size=${list.size} thread=${Thread.currentThread().name}")
        val ranked = try {
            com.streamflixreborn.streamflix.utils.ExtractorRanker.rankServers(list)
        } catch (e: Exception) { Log.w("OFB", "rankServers ERR: ${e.message}"); list
        } catch (e: Throwable) { Log.e("OFB", "rankServers FATAL: ${e.message}", e); list }
        Log.d("OFB", "A rankDone ${System.currentTimeMillis()-_t}ms ranked=${ranked.size}")
        Log.i("ServDiagT", "  rankServers(${list.size}) = ${System.currentTimeMillis()-_t}ms")
        val voRegex = Regex("""(^|[^a-z])vo([^a-z]|$)""")
        val frRegex = Regex("""(?i)\b(vf|vff|vfq|vfi|fr|french|français|francais|multi|vostfr|vost)\b""")

        // 2026-07-04 : détecter la langue de l'épisode courant
        // Priorité 1 : l'ID porte la langue (AnimeSama slug/saison1/vf/3, FrenchManga @vf…)
        Log.d("OFB", "B langOf lastId=${lastId?.take(40)}")
        var curLang = lastId?.let {
            com.streamflixreborn.streamflix.utils.MultiLangDetector.langOf(it)
        }
        Log.d("OFB", "C langOf=$curLang")
        // Priorité 2 : déduction depuis les serveurs NATIFS (= pas backup bkreg::)
        // si tous les natifs sont homogènes en langue explicite.
        // 2026-07-09 : NE PAS déduire curLang="vo". Quand le natif est VO (ex anime
        //   japonais via NetMirror), ça ne signifie PAS que l'épisode est "VO" — juste
        //   que le provider natif n'a que la VO. Le user veut les alternatives VF/VOSTFR.
        //   Garder curLang=null → comportement par défaut VF > VOSTFR > VO (FR en tête).
        // 2026-07-09 : RESTREINT aux providers ANIME uniquement. Sur un provider mixte
        //   (Movix, 1Jour1Film, Wiflix…) les serveurs natifs VOSTFR ne signifient PAS que
        //   l'épisode EST VOSTFR — les VF arrivent via backups. Déduire curLang=vostfr
        //   masquait les VF. La déduction ne sert QUE pour les providers anime où un
        //   épisode est SOIT VF SOIT VOSTFR (AnimeSama, FrenchAnime, FrenchManga…).
        if (curLang == null) {
            val currentProv = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider
            val isAnimeGroup = currentProv != null &&
                com.streamflixreborn.streamflix.providers.Provider.getGroup(currentProv) ==
                    com.streamflixreborn.streamflix.providers.Provider.Companion.ProviderGroup.ANIME
            if (isAnimeGroup) {
                val nativeServers = ranked.filter { !it.id.startsWith("bkreg::") }
                if (nativeServers.isNotEmpty()) {
                    val nativeLangs = nativeServers.map { serverLang(it) }.filter { it != "unknown" }.toSet()
                    if (nativeLangs.size == 1 && nativeLangs.first() != "vo") {
                        curLang = nativeLangs.first()
                        Log.d("LangSort", "curLang déduit des serveurs natifs (provider ANIME): $curLang")
                    } else if (nativeLangs.size == 1) {
                        Log.d("LangSort", "natifs homogènes VO → curLang reste null (pas de masquage FR)")
                    }
                }
            } else {
                Log.d("LangSort", "provider non-ANIME → pas de déduction curLang par serveurs natifs")
            }
        }
        Log.d("OFB", "D curLang=$curLang")

        // 2026-07-08 : lire les favoris UNE SEULE FOIS pour donner bucket -1
        //   aux serveurs cœur → toujours en tête, quelle que soit la langue.
        Log.d("OFB", "E avant currentProvider")
        val providerName = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider?.name
        Log.d("OFB", "F currentProvider=$providerName")
        val favorites = if (!providerName.isNullOrEmpty())
            com.streamflixreborn.streamflix.utils.ExtractorToggleStore.getFavorites(providerName) else emptySet()
        Log.d("OFB", "F2 favorites=${favorites.size} ${favorites.take(3)}")

        fun isFav(s: Video.Server): Boolean {
            if (favorites.isEmpty()) return false
            // 2026-07-11 : clé LANGUE-AWARE ("vidmoly:vf" ≠ "vidmoly:vostfr")
            val fk = com.streamflixreborn.streamflix.utils.ExtractorRanker.favKeyFor(s)
            return fk in favorites
        }

        fun bucket(s: Video.Server): Int {
            // 2026-07-08 (user "les cœurs ne sont même pas classés dans le VOD") :
            // Un serveur cœur = TOUJOURS en tête, peu importe la langue.
            if (isFav(s)) return -1

            val lang = serverLang(s)
            return if (curLang != null) {
                // Tri orienté par la langue de l'épisode
                when {
                    lang == curLang  -> 0   // langue de l'épisode = tête
                    lang == "unknown"-> 1   // pas de tag = neutre, en bas mais visible
                    lang == "vo"     -> 999 // VO = enterré
                    else             -> 2   // langue opposée explicite (VF sur épisode VOSTFR ou inverse)
                }
            } else {
                // Pas de langue détectable → comportement classique VF > VOSTFR > VO
                when (lang) {
                    "vostfr" -> 1
                    "vo"     -> 999
                    else     -> 0  // vf ou unknown = tête
                }
            }
        }
        val hideVo = providerName != null &&
            com.streamflixreborn.streamflix.utils.CatalogFilter.isSupported(providerName) &&
            com.streamflixreborn.streamflix.utils.CatalogFilter.get(providerName) ==
                com.streamflixreborn.streamflix.utils.CatalogFilter.Mode.POPULAR_INTL
        val filtered = if (hideVo) {
            ranked.filter { s ->
                val b = bucket(s)
                b < 999 || frRegex.containsMatchIn(s.name)
            }
        } else ranked

        Log.d("OFB", "G avant sort filtered=${filtered.size}")
        Log.i("ServDiagT", "  avant sort à ${System.currentTimeMillis()-_t}ms (curLang=$curLang hideVo=$hideVo)")
        val sorted = filtered.sortedBy { bucket(it) }
        Log.d("OFB", "H sorted=${sorted.size} ${System.currentTimeMillis()-_t}ms → RETURN")

        // 2026-07-04 : MASQUAGE langue opposée + VO sur épisode à langue explicite
        // On GARDE bucket 0 (langue épisode) + bucket 1 (inconnu, non marqué).
        // On MASQUE bucket 2 (langue opposée explicite) + 999 (VO).
        // Les inconnus sont affichés EN DERNIER — jamais perdus.
        // FILET : si le masquage vide la liste → on garde tout (la personne
        // aura quand même ses serveurs, même dans la mauvaise langue).
        // 2026-07-09 : JAMAIS masquer sur curLang="vo". Quand le natif est VO,
        //   le masquage tuait TOUS les VF/VOSTFR (AnimeSama, FrenchManga…).
        //   Le masquage ne sert QUE pour VF↔VOSTFR (épisode VF → cacher VOSTFR
        //   et vice versa). VO = pas de masquage, tri par défaut suffit.
        if (curLang != null && curLang != "vo") {
            val kept = sorted.filter { bucket(it) <= 1 }
            if (kept.isNotEmpty()) {
                Log.d("LangSort", "curLang=$curLang : ${sorted.size} → ${kept.size} serveurs (masqué ${sorted.size - kept.size} opposé/VO)")
                return kept
            }
            Log.d("LangSort", "curLang=$curLang : masquage aurait vidé la liste → on garde tout")
        }

        return sorted
    }

    /**
     * 2026-07-08 (user "les cœurs ne sont même pas classés dans le VOD") :
     * Re-trie les serveurs courants et ré-émet serversReordered.
     * Appelé depuis le Fragment quand l'user toggle un cœur VOD.
     */
    fun resortServers() {
        val current = allKnownServers
        if (current.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val reordered = orderByFrenchBuckets(current)
            _serversReordered.emit(collapseIdenticalServers(reordered))
            Log.d("OFB", "resortServers: re-trié ${current.size} serveurs (favori toggle)")
        }
    }

    /**
     * 2026-07-04 : Fusionne les serveurs identiques (même URL source) en un
     * seul item avec indicateur ×N dans le nom. Quand 6 backup providers
     * trouvent le même lien Uqload, l'user voit "Uqload (VF) ×6" au lieu
     * de 6 lignes identiques.
     *
     * Appliquée UNIQUEMENT côté émission UI — allKnownServers reste non-fusionné
     * pour que le probe qualité puisse muter .quality sur les objets originaux.
     *
     * Préserve l'ordre d'entrée (le 1er exemplaire de chaque src gagne).
     */
    private fun collapseIdenticalServers(servers: List<Video.Server>): List<Video.Server> {
        if (servers.size <= 1) return servers
        // LinkedHashMap pour garder l'ordre d'insertion (= ordre tri langue)
        val groups = LinkedHashMap<String, MutableList<Video.Server>>()
        for (s in servers) {
            val key = s.src.ifBlank { "__empty_${s.id}" } // src vide = pas fusionnable
            groups.getOrPut(key) { mutableListOf() }.add(s)
        }
        // Si aucun doublon → shortcut (pas d'allocation)
        if (groups.size == servers.size) return servers
        val result = mutableListOf<Video.Server>()
        for ((_, group) in groups) {
            val base = group.first()
            if (group.size == 1) {
                result.add(base)
            } else {
                // Fusionne les mirrors de tous les doublons
                val allMirrors = group.flatMap { it.mirrors }.distinct()
                // Prend la meilleure qualité déjà probée dans le groupe
                val bestQuality = group.mapNotNull { it.quality }.firstOrNull()
                val bestVideo = group.mapNotNull { it.video }.firstOrNull()
                val merged = Video.Server(
                    id = base.id,
                    name = "${base.name} ×${group.size}",
                    src = base.src,
                    mirrors = allMirrors,
                )
                merged.quality = bestQuality ?: base.quality
                merged.video = bestVideo ?: base.video
                result.add(merged)
                Log.d("ServerMerge", "Fusionné ${group.size} serveurs identiques → ${merged.name}")
            }
        }
        Log.d("ServerMerge", "Collapse: ${servers.size} → ${result.size} serveurs")
        return result
    }

    // 2026-05-16 (user "ça charge à l'infini sans savoir si serveur OK") :
    // référence du Job d'extraction courant, pour permettre au fragment de
    // l'annuler quand l'user tap le loading overlay.
    private var getVideoJob: kotlinx.coroutines.Job? = null

    /** Annule l'extraction vidéo en cours (si une). Appelé par le fragment
     *  quand l'user tap l'overlay de chargement pour changer de serveur. */
    fun cancelGetVideo() {
        getVideoJob?.cancel()
        getVideoJob = null
    }

    fun getVideo(server: Video.Server): kotlinx.coroutines.Job {
        // 2026-05-18 : anti-cascade. Cancel previous getVideo AND any pre-extract
        //   job running en background, sinon plusieurs extractions WebView en
        //   parallèle saturent CPU + mémoire et le player tourne dans le vide.
        getVideoJob?.cancel()
        preExtractJob?.cancel()
        // 2026-06-30 (user "la qualité serveur ne se déclenche plus") : NE PLUS
        //   annuler qualityProbeJob ici. L'auto-play du meilleur serveur appelle
        //   getVideo juste APRÈS getServers → ça tuait le probe qualité (attente
        //   40s) avant qu'il ne tourne → aucun label. Le probe qualité est
        //   indépendant de la lecture (HTTP GET léger en fond) → il doit survivre.
        //   Il n'est réinitialisé qu'au chargement d'une NOUVELLE vidéo
        //   (preExtractTopServersInBackground L236).
        val job = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")
        _state.emit(State.LoadingVideo(server))
        try {
            // 2026-05-16 : timeout global 60s sur l'extraction. Sans ça,
            // certains extracteurs (Movix Premium HD via voe.sx mort, etc.)
            // peuvent hang sans throw → l'app reste en LoadingVideo ad vitam,
            // pas de FailedLoadingVideo emit → pas d'auto-fallback. User
            // signale "ça tourne dans le vide et ne bascule pas auto". Avec
            // timeout, l'extraction throw → FailedLoadingVideo → fallback.
            val provider = UserPreferences.currentProvider ?: return@launch
            val video = kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                // 2026-07-04 (registre central) : un serveur backup (id `bkreg::…`) se lit via
                //   le registre, qui ré-aiguille vers le getVideo de la source d'origine.
                if (server.id.startsWith(com.streamflixreborn.streamflix.utils.BackupRegistry.PREFIX))
                    com.streamflixreborn.streamflix.utils.BackupRegistry.getVideo(server)
                else provider.getVideo(server)
            } ?: throw Exception("Extraction timeout (60s) — server unresponsive")
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
            // 2026-07-04 (user "le serveur en lecture n'a même pas la qualité marquée") :
            //   dès qu'un getVideo réussit, on marque la qualité sur le serveur.
            //   Pas de probe HLS ici (ralentirait le démarrage de lecture), juste
            //   l'inférence rapide depuis l'URL extraite + nom du serveur.
            // Stocker le Video sur le serveur → le probe qualité peut le relire
            // sans re-extraire (server.video lu AVANT Extractor.extract dans la boucle probe).
            server.video = video
            if (server.quality == null) {
                val q = inferQualityFromText(video.source) ?: inferQualityFromText(server.name)
                if (q != null) {
                    server.quality = q
                    _qualityUpdated.emit(Unit)
                    Log.w("QualityProbe", "getVideo: ${server.name} → $q (immédiat)")
                }
            }
            // 2026-05-09 : tracking session pour FilmHealthTracker.
            // Une réussite = on retire la marque "vide" si elle existait
            // (le film marche maintenant) et on flag pour ne pas marquer.
            sessionAnySuccess = true
            recordSessionFilmId()
            unmarkCurrentFilmAsEmpty()
            _state.emit(State.SuccessLoadingVideo(video, server))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 2026-05-18 : NE PAS émettre FailedLoadingVideo en cas
            //   d'annulation propre (par un getVideo suivant qui cancel le
            //   précédent). Sinon : cascade infinie — chaque cancel déclenche
            //   un fallback vers next server qui re-cancel l'actuel etc.
            Log.d("PlayerViewModel", "getVideo cancelled (${server.name}), no fallback emitted")
            throw e
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
        getVideoJob = job
        return job
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
                // 2026-05-15 (user "ajoute le sub anglais pour ceux qui veulent
                // dans OpenSubtitles, censé être activable sur tous les providers") :
                // si UserPreferences.enableEnglishSubtitles est ON, on récupère
                // aussi les subs anglais en parallèle (en plus du FR par défaut).
                val frenchOnly = "fre"
                val includeEnglish = com.streamflixreborn.streamflix.utils.UserPreferences.enableEnglishSubtitles
                // 2026-05-04 : on PASSE TOUJOURS imdb_id quand le provider le
                // fournit. Sans ça la recherche tombait en mode "query texte"
                // et matchait "Fear City: New York Vs The Mafia S01E03" pour
                // "New York 911 S01E03" (tous deux contiennent "New York")
                // -> sous-titre dingue d'une autre série, donc "désynchronisé".
                // Tri : downloads DESC (le plus téléchargé = le mieux synchro).
                val frenchSubtitles = when (videoType) {
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
                }
                val englishSubtitles = if (includeEnglish) {
                    when (videoType) {
                        is Video.Type.Episode -> {
                            OpenSubtitles.search(
                                imdbId = videoType.tvShow.imdbId,
                                query = if (videoType.tvShow.imdbId.isNullOrBlank()) videoType.tvShow.title else null,
                                season = videoType.season.number,
                                episode = videoType.number,
                                subLanguageId = "eng",
                            )
                        }
                        is Video.Type.Movie -> {
                            OpenSubtitles.search(
                                imdbId = videoType.imdbId,
                                query = if (videoType.imdbId.isNullOrBlank()) videoType.title else null,
                                subLanguageId = "eng",
                            )
                        }
                    }
                } else emptyList()
                // FR en premier, puis EN après — tri downloads DESC dans chaque groupe.
                val subtitles = (frenchSubtitles.sortedByDescending { it.subDownloadsCnt } +
                    englishSubtitles.sortedByDescending { it.subDownloadsCnt })

                Log.d("PlayerViewModel", "OpenSubtitles: FR=${frenchSubtitles.size}, EN=${englishSubtitles.size}")
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
        // 2026-07-07 (user « les serveurs s'affichent INSTANTANÉMENT ; la grâce ne bloque QUE le
        //   player, pas l'affichage ; si rien au bout de 12s, démarre sur le 1er serveur venu ») :
        //   autoPlay=false → affiche les serveurs mais NE lance PAS la lecture auto (attend un VF).
        //   Un 2ᵉ emit autoPlay=true débloque l'auto-play (VF arrivé, ou 12s écoulées).
        data class SuccessLoadingServers(val servers: List<Video.Server>, val autoPlay: Boolean = true) : State()
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
