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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
                    // 2026-07-27 : Filemoon exige une vérif humaine q8y5z (5-6 clics, confirmé user) →
                    //   impossible à passer en pré-extraction background (15s), et surtout CHAQUE
                    //   re-passe du pré-extract (serveurs progressifs) ANNULE le fallback WebView en
                    //   cours au clic de l'user (« StandaloneCoroutine was cancelled ») → Filemoon ne
                    //   se lançait JAMAIS. On ne le pré-extrait plus : résolution au clic manuel seul.
                    run {
                        val s = server.src.lowercase()
                        if (s.contains("filemoon") || s.contains("lukefirst") || s.contains("weneverbeenfree") ||
                            s.contains("moflix-stream") || s.contains("bysebuho") || s.contains("bysezoxexe") ||
                            s.contains("bysejikuar") || s.contains("bysekoze") || s.contains("bysesayeveum") ||
                            s.contains("bysejikuar") || s.contains("gn1r5n")) return@launch
                    }
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
                            // 2026-08-01 (user : « Movix Rpmvid VF est en réalité en VOSTFR ») :
                            //   quand l'hébergeur donne le NOM DE FICHIER réel, il prime sur
                            //   l'étiquette du site. Vérifié : Movix annonçait « (VF) » un lien
                            //   dont le fichier s'appelle « In.the.Grey.2026.VOSTFR.1080p… ».
                            //   Le nom de fichier ne ment pas — on corrige l'affichage.
                            langueDepuisNomFichier(result.fileName)?.let { vraie ->
                                val avant = server.language
                                if (!server.name.contains(vraie, ignoreCase = true)) {
                                    Log.w(
                                        "ServDiag",
                                        "LANGUE CORRIGÉE '${server.name}' → $vraie " +
                                            "(fichier: ${result.fileName})",
                                    )
                                }
                                server.language = vraie
                                // 2026-08-02 (user : « vaut mieux l'écarter direct avant la
                                //   lecture ; si c'est de l'auto-lecture on l'écarte, mais si
                                //   l'utilisateur clique dessus volontairement, il ne s'écarte
                                //   pas ») : ce démasquage a lieu pendant le PRÉ-EXTRACT, donc
                                //   AVANT toute lecture. On re-trie alors la liste : le serveur
                                //   rétrogradé passe derrière les vrais VF, et l'auto-play — qui
                                //   prend le premier — ne le choisit plus.
                                //   Il n'est PAS supprimé : il reste sélectionnable à la main,
                                //   simplement plus bas. Le choix explicite reste donc respecté,
                                //   sans avoir à distinguer les deux cas dans le code.
                                if (avant != vraie &&
                                    (vraie.equals("VOSTFR", true) || vraie.equals("VO", true))
                                ) {
                                    Log.i(
                                        "ServDiag",
                                        "'${server.name}' rétrogradé ($vraie) → re-tri groupé",
                                    )
                                    demanderRetri()
                                }
                            }
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
                                        // 2026-07-31 : `server` transmis → la langue réelle est
                                        //   lue dans le même manifeste (aucune requête en plus).
                                        probeHlsQuality(extractedUrl, result.headers, server)
                                    } else null
                                    val finalQ = q ?: inferQualityFromText(extractedUrl) ?: inferQualityFromText(server.name)
                                    if (finalQ != null) {
                                        server.quality = finalQ
                                        _qualityUpdated.emit(Unit)
                                        Log.w("QualityProbe", "pre-extract[$idx]: ${server.name} → $finalQ (${durationMs}ms)")
                                    } else {
                                        Log.w("QualityProbe", "pre-extract[$idx]: ${server.name} → no quality (isHls=$isHls, url=${extractedUrl.take(60)})")
                                        // 2026-08-03 (user : « on va virer ça, ça va pas servir ») :
                                        //   le sondage de POIDS par requête HEAD est SUPPRIMÉ.
                                        //   Il ne pouvait toucher que les 4 serveurs pré-extraits,
                                        //   et parmi eux les seuls fichiers directs restés sans
                                        //   qualité — soit zéro à un serveur par ouverture. Coût
                                        //   négligeable, mais rendement quasi nul : on retire.
                                        //   Le classement s'appuie sur les définitions MESURÉES
                                        //   (manifeste HLS) ou ÉCRITES dans le nom du fichier, et
                                        //   sur `debitKbps` lu gratuitement dans le manifeste.
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
     * 2026-08-03 — REGROUPEMENT DES LOTS (user : diagnostic « les serveurs sont bloqués »).
     *
     * Constat mesuré sur un lancement réel : 14 serveurs arrivés en 7 lots ⇒ 7 tris complets,
     * dont un `rankServers(7)` à **6 324 ms** alors que le travail utile (filtrage) prenait
     * 82 ms. Le tri n'est pas lourd : il est AFFAMÉ. Une vingtaine de sources de backup
     * interrogées en parallèle saturent l'appareil, et chaque nouveau lot relance un tri qui
     * entre en concurrence avec le précédent — jusqu'à dépasser le plafond de 5 s et afficher
     * la liste NON TRIÉE.
     *
     * Remède : ne plus trier à chaque arrivée. Le PREMIER lot passe immédiatement (c'est lui
     * qui déclenche l'affichage, il ne doit jamais attendre) ; les suivants sont accumulés et
     * remis en UN SEUL lot au plus toutes les [fenetreMs]. On supprime ainsi la majorité des
     * tris concurrents, donc la contention qui les faisait durer des secondes.
     *
     * L'aval reste appelé séquentiellement (un seul collecteur), donc la sérialisation de
     * `handleBatch` — dont dépend l'absence de mutation concurrente de `accumulated`/`seenIds` —
     * est préservée.
     */
    private fun Flow<List<Video.Server>>.lotsGroupes(
        fenetreMs: Long,
    ): Flow<List<Video.Server>> = kotlinx.coroutines.flow.channelFlow {
        val tampon = mutableListOf<Video.Server>()
        val verrou = kotlinx.coroutines.sync.Mutex()
        var premierPasse = false
        var vidange: Job? = null

        this@lotsGroupes.collect { lot ->
            if (!premierPasse) {
                premierPasse = true
                send(lot)   // affichage immédiat : jamais retardé
                return@collect
            }
            verrou.withLock { tampon.addAll(lot) }
            // Une seule vidange programmée à la fois : les lots qui arrivent pendant la
            //   fenêtre rejoignent le tampon au lieu de déclencher leur propre tri.
            if (vidange?.isActive == true) return@collect
            vidange = launch {
                delay(fenetreMs)
                val groupe = verrou.withLock { val c = tampon.toList(); tampon.clear(); c }
                if (groupe.isNotEmpty()) {
                    Log.d("ServDiag", "PROG lots regroupés → ${groupe.size} serveurs en 1 tri")
                    send(groupe)
                }
            }
        }
        // Amont terminé : on évacue ce qui reste en tampon, rien ne doit être perdu.
        vidange?.join()
        val reste = verrou.withLock { val c = tampon.toList(); tampon.clear(); c }
        if (reste.isNotEmpty()) send(reste)
    }

    /**
     * Sonde la qualité de tous les serveurs, en arrière-plan, en UNE SEULE PASSE.
     *
     * IDEMPOTENT : si un sondage tourne déjà, on ne relance pas — les lots progressifs
     * alimentent `allKnownServers`, que la passe lira au moment venu.
     *
     * Déroulé (2026-08-03, user : « on va effectuer qu'une seule passe ») :
     *   1. attendre la fin de la pré-extraction (le cache est alors rempli — sans quoi
     *      certains serveurs étaient extraits DEUX fois en parallèle) ;
     *   2. attendre la fin de la collecte progressive, plafonnée à 45 s, pour que la
     *      liste soit complète ;
     *   3. une seule vague, 4 serveurs en parallèle, 10 s par serveur — puis terminé.
     *
     * L'ancienne version bouclait toutes les 3 s pendant toute la collecte (une dizaine
     * de tours), consommant réseau et CPU PENDANT la lecture. Couverture identique,
     * mais une salve unique au lieu d'un réveil permanent.
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
            // 2026-07-06 : ATTENDRE que preExtractJob finisse AVANT de sonder.
            //   Sibnet (et d'autres) étaient extraits 2× en parallèle : la pre-extract lançait
            //   l'extraction avec 15s timeout, et le probe (après 4s) trouvait le cache vide
            //   → relançait une DEUXIÈME extraction identique. En attendant que preExtract finisse,
            //   le cache est garanti rempli → peekCachedVideo hit → zéro double extraction.
            preExtractJob?.join()   // attend la fin de la pré-extraction (cache rempli)
            // 2026-08-03 (user : « on va effectuer qu'une seule passe ») : AVANT, la boucle
            //   `while (isActive)` se réveillait toutes les 3 s et relançait une vague à chaque
            //   nouvelle arrivée de serveurs — soit une dizaine de tours étalés sur toute la
            //   collecte, donc du réseau et du CPU consommés PENDANT la lecture (concurrence
            //   directe avec le décodage sur Chromecast).
            //   Maintenant : on attend que la collecte progressive soit TERMINÉE (liste
            //   complète), puis on fait UNE seule vague, puis on s'arrête. Même couverture,
            //   une seule salve au lieu d'un harcèlement continu.
            //   Plafond d'attente : si la collecte s'éternise, on sonde quand même ce qu'on a.
            kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                while (progressiveStillCollecting) delay(1_000L)
            }
            run {
                // Une seule passe : tous les serveurs encore sans qualité, dédupliqués par src.
                val batch = allKnownServers
                    .filter { it.src.isNotBlank() && it.quality == null }
                    .distinctBy { it.src }
                Log.w("QualityProbe", "passe unique : ${batch.size} serveurs à sonder")
                val sem = kotlinx.coroutines.sync.Semaphore(4)
                batch.map { server ->
                    launch {
                        sem.acquire()
                        try {
                            // Captcha (Papadustream) : non sondable sans clic humain → écarté.
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

        val quality = if (isHls) probeHlsQuality(videoUrl, video.headers, server) else null
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
        // ── 2026-08-06 : UN SERVEUR QUI ANNONCE LES DEUX EST UN VF ───────────────────────
        //   User, capture à l'appui : « Vidzy · VF/VOSTFR » arrivait en tête en 360p.
        //   Cette fonction lit le nom ENTIER et rejetait tout libellé contenant « vostfr » —
        //   y compris ceux qui annoncent AUSSI du VF. Trois effets, tous mauvais :
        //     · le serveur échappait au tri par résolution réservé aux VF, d'où un 360p en
        //       première position ;
        //     · il ne comptait pas comme « un VF est arrivé », donc la période de grâce
        //       attendait pour rien ;
        //     · il passait derrière de vrais VOSTFR alors qu'il propose bien du français.
        //   Décision user : « tu mets qu'il est VF, un point c'est tout ». C'est le bon
        //   arbitrage — la piste française EXISTE, et le choix de la piste se fait ensuite
        //   dans le lecteur.
        if (Regex("""(^|[^a-z])vf([^a-z]|$)""").containsMatchIn(n)) return true
        if (n.contains("vostfr") || n.contains("sous-titr")) return false
        if (Regex("""(^|[^a-z])vo([^a-z]|$)""").containsMatchIn(n)) return false
        if (n.contains(Regex("\\b(raw|eng|english|spa|ita|german|deu|jap)\\b"))) return false
        return true
    }

    /**
     * Décide de l'affichage des sous-titres d'après la langue du serveur.
     *
     * ── 2026-08-06 (user : « ça me choque qu'on mette automatiquement les sous-titres sur
     *   des films français… on les active sur du VOSTFR et on les désactive sur du VF ») ──
     *   Il existait bien un réglage manuel (« sous-titres automatiques du serveur »), mais il
     *   fallait y penser et il s'appliquait indistinctement. Or l'information est déjà là :
     *   `isVfServer` sait dire si la piste est française. On s'en sert.
     *
     *   · Serveur VF → aucun sous-titre par défaut. Un film français sous-titré en français
     *     n'a aucun sens, c'est ce qui gênait.
     *   · Serveur VOSTFR / VO → on active le sous-titre FRANÇAIS s'il existe ; sinon on
     *     laisse ce que la source avait prévu, plutôt que de tout couper.
     *
     *   ⚠ `isVfServer` considère « VF » par défaut quand aucun marqueur n'est présent :
     *     c'est volontaire pour une application française, et ça va dans le bon sens ici —
     *     dans le doute, pas de sous-titres imposés. L'utilisateur garde la main dans le
     *     menu du lecteur, et le réglage manuel reste prioritaire quand il est actif.
     */
    private fun reglerSousTitresSelonLangue(server: Video.Server, video: Video) {
        if (video.subtitles.isEmpty()) return
        // ⚠ On n'interroge PAS `serverAutoSubtitlesDisabled` ici : ce drapeau empêche un
        //   serveur d'imposer ses sous-titres, il ne doit pas priver le VOSTFR des siens.
        //   C'est la LANGUE qui tranche, comme demandé : « si VOSTFR tu actives, si VF tu
        //   désactives ». Cette règle passe donc en dernier et a le dernier mot.
        if (isVfServer(server)) {
            video.subtitles.forEach { it.default = false }
            Log.d("PlayerViewModel", "sous-titres : serveur VF → aucun par défaut (${server.name})")
            return
        }

        val fr = video.subtitles.firstOrNull {
            val l = it.label.lowercase()
            l.contains("fr") || l.contains("français") || l.contains("french")
        } ?: return
        video.subtitles.forEach { it.default = false }
        fr.default = true
        Log.d("PlayerViewModel", "sous-titres : serveur VOSTFR/VO → « ${fr.label} » activé (${server.name})")
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
    /**
     * 2026-07-31 : langue(s) déclarée(s) par le manifeste HLS.
     *   Les pistes audio y sont annoncées via `#EXT-X-MEDIA:TYPE=AUDIO,…,LANGUAGE="fre"`.
     *   - une seule piste NON française  → « VOSTFR » (audio étranger, sous-titres FR)
     *   - français présent avec d'autres → « VF+VO » (multi-langue)
     *   - français seul                  → « VF »
     *   Retourne null si le manifeste ne déclare aucune langue (on n'invente pas).
     */
    /**
     * 2026-08-01 : langue déduite du NOM DE FICHIER réel du flux.
     * Les sites étiquettent souvent « VF » sans vérifier ; le nom du fichier, lui, porte la
     * mention exacte de la release (VOSTFR, TRUEFRENCH, MULTI…). Quand il est disponible, il
     * fait autorité sur ce qu'annonce le site.
     * Retourne null si le nom ne dit rien — on n'invente jamais une langue.
     */
    private fun langueDepuisNomFichier(nom: String?): String? {
        val n = nom?.lowercase() ?: return null
        // ordre important : VOSTFR/SUBFRENCH avant VF (« vostfr » contient… « vo »).
        return when {
            Regex("""vostfr|subfrench|sub[ ._-]?fr|vost\b""").containsMatchIn(n) -> "VOSTFR"
            Regex("""multi""").containsMatchIn(n) -> "MULTI"
            Regex("""truefrench|\bvff\b|\bvfq\b|\bvfi\b|\bvf2\b|\bvf\b|french""").containsMatchIn(n) -> "VF"
            Regex("""\bvo\b|\bvostv?o\b""").containsMatchIn(n) -> "VO"
            else -> null
        }
    }

    private fun hlsLanguageLabel(manifest: String): String? {
        val langs = Regex("""LANGUAGE\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(manifest)
            .map { it.groupValues[1].lowercase().take(3) }
            .toSet()
        if (langs.isEmpty()) return null
        val hasFr = langs.any { it.startsWith("fr") }
        val hasOther = langs.any { !it.startsWith("fr") }
        return when {
            hasFr && hasOther -> "VF+VO"
            hasFr -> "VF"
            else -> "VOSTFR"
        }
    }

    private suspend fun probeHlsQuality(
        url: String,
        headers: Map<String, String>?,
        server: Video.Server? = null,
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

            // 2026-07-31 : le manifeste est DÉJÀ en mémoire → on y lit la langue au passage,
            //   sans aucune requête supplémentaire (idée du user : « au check qualité,
            //   normalement tu es capable de voir la langue »).
            if (server != null && server.language == null) {
                hlsLanguageLabel(body)?.let {
                    server.language = it
                    Log.w("QualityProbe", "langue: ${server.name} → $it")
                }
            }

            // 2026-08-02 : on MÉMORISE le débit du manifeste (aucune requête de plus, le corps est
            //   déjà là). Il sert ensuite à départager deux serveurs affichant la même définition —
            //   à « 1080p » égal, le mieux encodé est celui qui a le plus gros débit.
            val bwPattern = Regex("""BANDWIDTH=(\d+)""")
            val bandwidths = bwPattern.findAll(body).mapNotNull { it.groupValues[1].toLongOrNull() }
            val maxBw = bandwidths.maxOrNull()
            if (server != null && maxBw != null && maxBw > 0) {
                server.debitKbps = (maxBw / 1000).toInt()
                Log.d("QualityProbe", "débit ${server.name} → ${server.debitKbps} kb/s")
            }

            // 1) Parse RESOLUTION=<W>x<H> — prendre la hauteur max
            val resPattern = Regex("""RESOLUTION=\d+x(\d+)""")
            val heights = resPattern.findAll(body).mapNotNull { it.groupValues[1].toIntOrNull() }
            val maxHeight = heights.maxOrNull()
            if (maxHeight != null) return@withContext heightToLabel(maxHeight)

            // 2) Fallback : estimer via BANDWIDTH (bits/s)
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
        // 2026-08-03 : ce guetteur criait au loup à CHAQUE lancement. Sur un provider
        //   progressif, `collectProgressiveServers` s'exécute DANS cette même coroutine et
        //   collecte jusqu'à COLLECT_TIMEOUT_MS (120 s) : le job est donc forcément encore
        //   actif à 12 s, sans le moindre blocage. Le dump partait alors en plein milieu de
        //   l'arrivée des serveurs — or `Thread.getAllStackTraces()` fige la VM et parcourt
        //   la pile de TOUS les threads (~490 lignes de log mesurées), exactement quand
        //   l'appareil est le plus chargé.
        //   Il visait à l'origine un blocage sur le chemin NON-progressif (cf. « fix probable
        //   blocage aplouf/non-progressif » ci-dessus) → on le CONSERVE là, et on le désarme
        //   en entrant dans le chemin progressif, où sa condition n'a aucun sens.
        val hangWatcher = run {
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

            // ⚠ 2026-08-11 (user : « il doit être désactivable comme les autres ») :
            //   le provider COURANT émettait ses serveurs natifs quoi qu'il arrive. Après un
            //   « tout désactiver », les 21 backups étaient bien coupés mais Cloudstream —
            //   le provider en cours de navigation — continuait de servir les siens
            //   (`cs_playinfo_…`, API aoneroom). Décoché doit vouloir dire coupé, sans
            //   exception : on n'interroge donc PAS un provider que l'utilisateur a refusé.
            //   Le contrôle est ici, AVANT tout appel réseau, et non au filtrage : l'objectif
            //   est qu'aucune requête ne parte, pas qu'on jette la réponse.
            //   IPTV exclu : ses chaînes ne sont pas des « sources » au sens du sélecteur, et
            //   les couper reviendrait à vider l'écran des chaînes sans rapport avec la demande.
            //   ⚠ 2026-08-11, correction immédiate (user : « ça fait zéro serveur dans la
            //   foulée ? il devrait quand même tenter de chercher une source ») : ma 1ʳᵉ
            //   version coupait TOUT le chemin, backups compris. C'était faux. Décocher un
            //   provider ne doit supprimer QUE ses serveurs à lui ; les autres sources
            //   doivent continuer à chercher normalement. On ne pose donc qu'un drapeau, et
            //   c'est le flux NATIF seul qui est remplacé par un flux vide plus bas.
            val natifsRefuses = provider !is IptvProvider &&
                !UserPreferences.isBackupSourceEnabled(provider.name)
            if (natifsRefuses) {
                Log.i(
                    "ServDiag",
                    "${provider.name} → provider DÉSACTIVÉ : ses serveurs natifs sont ignorés, " +
                        "les backups continuent de chercher",
                )
            }

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
                // 2026-08-03 : ici la collecte dure LÉGITIMEMENT jusqu'à 120 s → le guetteur
                //   de blocage à 12 s ne peut que produire un faux positif coûteux. On le
                //   désarme. Il reste armé sur le chemin non-progressif, celui pour lequel
                //   il avait été écrit.
                hangWatcher.cancel()
                collectProgressiveServers(provider, id, videoType, natifsRefuses)
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
            val rawServers = if (natifsRefuses) {
                emptyList()   // provider décoché : on ne l'interroge pas, mais on n'arrête rien
            } else if (provider is IptvProvider) {
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
                // Provider décoché : 0 natif est le comportement VOULU, pas une panne. Ni
                // purge de cache, ni exception — on laisse la main aux autres sources.
                if (natifsRefuses) {
                    Log.i("ServDiag", "${provider.name} décoché → 0 natif (normal)")
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
        // 2026-08-11 (user : « il devrait quand même tenter de chercher une source ») :
        //   true = le provider a été décoché par l'utilisateur. On remplace son flux natif par
        //   un flux VIDE, et RIEN d'autre ne change : le registre de backups tourne exactement
        //   comme d'habitude et peut remplir la liste tout seul.
        ignorerNatifs: Boolean = false,
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
        reinitialiserLecture()   // 2026-08-07 : nouvelle collecte → le cœur redevient prioritaire
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
                episodeIdHint = id,
            )
            // 2026-08-03 (user : « retire le code mort ») : la PORTE DE DÉMARRAGE des backups
            //   (`backupGate`) est SUPPRIMÉE. Elle ne s'appliquait qu'aux `WebJsProvider`
            //   (contention WebView), or plus aucun provider sélectionnable n'en est un depuis
            //   que FrenchAnime et DessinAnime sont repassés en natif Kotlin : la condition
            //   `provider is WebJsProvider` était toujours fausse, la branche jamais empruntée.
            //   Les `WebJsProvider` restants ne sont que des sources de BACKUP dynamiques, qui
            //   ne passent pas par ici. Les backups démarrent donc à t=0, comme c'était déjà
            //   le cas en pratique. Si un provider WebJS redevenait sélectionnable un jour,
            //   c'est la contention WebView qu'il faudrait traiter, pas cette porte.
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
                        // 2026-08-02 (user : « FRAnime n'a émis qu'un seul serveur sur les 4 »,
                        //   Hajime no Ippo E3) : cette normalisation coupe AVANT `#` ET `?`
                        //   pour ignorer les jetons de signature. Mais chez FRAnime, c'est
                        //   EXACTEMENT là que se trouve l'identité du lecteur :
                        //     …/anime/fighting-spirit?s=1&ep=3&lang=vo&l=0#lecteur=filemoon
                        //     …/anime/fighting-spirit?s=1&ep=3&lang=vo&l=3#lecteur=vidmoly
                        //   Tronquées, les 4 entrées donnaient la MÊME clé → 3 jetées comme
                        //   doublons (Filemoon et Vidmoly disparaissaient de la liste).
                        //   → On conserve le discriminant `#lecteur=…` quand il existe : il
                        //     nomme le lecteur et ne contient jamais de jeton, donc il ne peut
                        //     pas ré-introduire de faux doublons.
                        val lecteurTag = Regex("""#lecteur=([a-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
                            .find(srv.src)?.groupValues?.get(1)?.lowercase()
                        val normUrl = srv.src.substringBefore("#").trim()
                            .substringBefore("?").trimEnd('/').lowercase() +
                            (lecteurTag?.let { "#$it" } ?: "")
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

                // ═══════════════════════════════════════════════════════════════════════
                // 2026-08-07 (user : « s'il y a aucune lecture et qu'un cœur rouge apparaît,
                //   faudrait que ça parte dessus instantanément — s'il le met en rouge, c'est
                //   qu'il y a une raison : soit il est de qualité, soit il est rapide »)
                //   → RACCOURCI CŒUR.
                //   Un serveur mis en favori par l'utilisateur est un choix DÉJÀ FAIT : il n'y
                //   a plus rien à arbitrer. On court-circuite donc tout ce qui suit dès qu'il
                //   apparaît — le tri complet (jusqu'à 5 s), la stabilisation adaptative
                //   (1,2 à 4 s) et la grâce FR (jusqu'à 12 s). Le favori passe en tête et
                //   l'auto-play part immédiatement.
                //   Règles : n'importe lequel des cœurs fait l'affaire (le premier arrivé), et
                //   ⚠ UNIQUEMENT si aucune lecture n'a encore démarré — `autoPlayEmitted`
                //   garantit qu'on ne coupe jamais une lecture en cours.
                //   Coût : `favKeyFor` n'est calculé que si l'utilisateur a réellement des
                //   favoris sur ce provider (sinon `favorisDuProvider()` rend un set vide).
                // 2026-08-07 (2ᵉ passe) : la condition n'est PLUS `!autoPlayEmitted` mais
                //   `!lectureDemarree`. Un serveur lent déjà lancé mais qui n'affiche encore
                //   rien ne doit pas priver l'utilisateur de son cœur. Le raccourci reste donc
                //   actif sur les lots SUIVANTS, jusqu'à la première image. Une fois que ça
                //   joue pour de vrai, on ne coupe plus jamais.
                if (!lectureDemarree) {
                    val favoris = favorisDuProvider()
                    val coeur = if (favoris.isEmpty()) null else fresh.firstOrNull {
                        com.streamflixreborn.streamflix.utils.ExtractorRanker.favKeyFor(it) in favoris
                    }
                    if (coeur != null) {
                        // Lot tardif (un serveur a déjà été choisi) → il faut un jeton pour
                        //   que le fragment accepte de basculer malgré `initialServerPicked`.
                        if (firstEmitted) raccourciCoeurEnAttente = true
                        autoPlayEmitted = true
                        firstEmitted = true
                        val liste = listOf(coeur) + accumulated.filter { it.id != coeur.id }
                        allKnownServers = liste
                        Log.i(
                            "ServDiag",
                            "PROG RACCOURCI CŒUR : '${coeur.name}' favori → lecture immédiate " +
                                "(ni tri, ni stabilisation, ni grâce FR)",
                        )
                        _state.emit(State.SuccessLoadingServers(collapseIdenticalServers(liste), autoPlay = true))
                        preExtractTopServersInBackground(liste)
                        return
                    }
                }

                Log.d("ServDiag", "PROG avant orderByFrenchBuckets accumulated=${accumulated.size}")
                // 2026-07-05 : timeout RÉEL (thread Java, pas coopératif) pour empêcher
                //   orderByFrenchBuckets de bloquer indéfiniment. Le bug : parfois
                //   orderByFrenchBuckets ne retourne JAMAIS (thread disparaît). Comme
                //   withTimeoutOrNull est coopératif, il ne peut pas interrompre du code
                //   bloquant → le spinner tourne indéfiniment. Avec un executor + future.get(5s),
                //   on a un vrai deadline. Si ça bloque → fallback unsorted → l'user a ses serveurs.
                val accSnapshot = accumulated.toList() // snapshot immuable pour le thread
                // 2026-07-05 : executor PARTAGÉ (companion ofbExecutor) au lieu de
                //   créer/détruire un newSingleThreadExecutor par appel (thread leak).
                // 2026-08-03 : `future` SORTI du try — sans référence dessus, on ne pouvait pas
                //   l'annuler au timeout. Or `ofbExecutor` est un pool NON BORNÉ : le tri
                //   abandonné continuait de tourner pendant que le lot suivant en démarrait un
                //   autre sur un thread neuf (deux « ofb-worker » simultanés dans les logs),
                //   chacun ralentissant l'autre. On l'annule désormais explicitement.
                val future = ofbExecutor.submit(java.util.concurrent.Callable { orderByFrenchBuckets(accSnapshot) })
                val ordered: List<Video.Server> = try {
                    future.get(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    future.cancel(true)   // libère le thread au lieu de le laisser courir
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
                            // 2026-08-03 (user : « fais le premier levier ») : la stabilisation
                            //   n'est plus une attente FERME de 4 s, elle devient ADAPTATIVE.
                            //   Avant, une liste déjà propre à la 1ʳᵉ seconde patientait autant
                            //   qu'une liste douteuse — 4 s perdues à chaque lancement.
                            //   Désormais on sort dès que les DEUX conditions sont réunies :
                            //     • un VF est présent (sinon la grâce FR ci-dessous s'applique) ;
                            //     • la liste n'a pas bougé pendant une tranche entière
                            //       (= plus aucun lot en vol, donc plus rien à évincer).
                            //   Garde-fous : jamais avant STABILIZE_FLOOR_MS (on laisse toujours
                            //   passer au moins un lot supplémentaire, sinon on repart sur le
                            //   tout premier arrivé — exactement ce qu'on voulait éviter), et
                            //   jamais au-delà de STABILIZE_MS (le plafond d'avant, inchangé).
                            //   Cas propre : ~1,2 s au lieu de 4 s. Cas douteux : 4 s comme avant.
                            val STABILIZE_FLOOR_MS = 1_200L
                            val TRANCHE_MS = 400L
                            var attendu = 0L
                            var tailleAvant = allKnownServers.size
                            while (attendu < STABILIZE_MS) {
                                kotlinx.coroutines.delay(TRANCHE_MS)
                                attendu += TRANCHE_MS
                                val tailleMaintenant = allKnownServers.size
                                val listeStable = tailleMaintenant == tailleAvant
                                tailleAvant = tailleMaintenant
                                if (attendu >= STABILIZE_FLOOR_MS && listeStable &&
                                    allKnownServers.any { isVf(it) }
                                ) {
                                    Log.i(
                                        "ServDiag",
                                        "PROG stabilisation ANTICIPÉE à ${attendu}ms " +
                                            "(liste stable à $tailleMaintenant serveurs, VF présent) " +
                                            "— ${STABILIZE_MS - attendu}ms économisées",
                                    )
                                    break
                                }
                            }
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

            // merge() → handleBatch sérialisé (aucune mutation concurrente des collections).
            val _tNat = System.currentTimeMillis()
            // Provider décoché → flux natif VIDE. On n'appelle même pas getServersProgressive,
            // donc aucune requête ne part vers lui. Le backupFlow ci-dessus, lui, est intact.
            val nativeFlow = if (ignorerNatifs) {
                Log.i("ServDiagT", "NATIVE flow IGNORÉ (provider décoché) — seuls les backups alimentent la liste")
                kotlinx.coroutines.flow.emptyFlow()
            } else provider.getServersProgressive(id, videoType)
                .onEach { Log.i("ServDiagT", "NATIVE flow a ÉMIS size=${it.size} à ${System.currentTimeMillis()-_tNat}ms") }
                // 2026-07-04 : forcer l'exécution du flow natif sur IO — sinon
                //   withContext(IO) dans channelFlow reprend sur Main (viewModelScope),
                //   et si le main-thread est occupé (Glide/UI), l'émission est retardée
                //   de 10+ secondes voire indéfiniment.
                .flowOn(kotlinx.coroutines.Dispatchers.IO)
            // 2026-08-03 : backups à t=0, en parallèle du natif (cf. suppression de `backupGate`
            //   plus haut). C'était déjà le comportement réel pour TOUS les providers actifs.
            val gatedBackupFlow = backupFlow
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
            // 2026-08-03 : `lotsGroupes` — 1er lot immédiat, suivants regroupés par fenêtres
            //   de 500 ms (cf. doc de l'opérateur). Divise le nombre de tris, donc la
            //   contention CPU qui les faisait déborder du plafond de 5 s.
            val COALESCE_MS = 500L
            if (REGISTRY_BACKUPS_ENABLED) {
                kotlinx.coroutines.withTimeoutOrNull(COLLECT_TIMEOUT_MS) {
                    kotlinx.coroutines.flow.merge(nativeFlow, gatedBackupFlow)
                        .lotsGroupes(COALESCE_MS)
                        .collect { handleBatch(it) }
                } ?: Log.w("ServDiag", "PROG collecte stoppée après ${COLLECT_TIMEOUT_MS/1000}s (plafond atteint)")
            } else {
                Log.i("ServDiag", "PROG TEST natifs-seuls (registre désactivé)")
                kotlinx.coroutines.withTimeoutOrNull(COLLECT_TIMEOUT_MS) {
                    nativeFlow.lotsGroupes(COALESCE_MS).collect { handleBatch(it) }
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
        // ⚠ 2026-08-02 : la LANGUE CORRIGÉE prime sur le nom. `server.language` est renseigné par
        //   le pré-extract à partir du NOM DE FICHIER réel (« …VOSTFR 1080p… »), qui fait autorité
        //   sur l'étiquette du site. Sans cette priorité, un serveur démasqué VOSTFR mais toujours
        //   nommé « EmbedSeek (VF) » restait trié comme un VF : il gardait sa place en tête et
        //   l'auto-play continuait de le choisir — le démasquage ne servait donc à rien.
        s.language?.lowercase()?.trim()?.takeIf { it.isNotBlank() }?.let { l ->
            when {
                l.contains("vostfr") || l.contains("subfrench") -> return "vostfr"
                l == "vo" || l.contains("vostf") -> return "vo"
                l.contains("vf") || l.contains("multi") || l.contains("french") -> return "vf"
            }
        }
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

    /**
     * Clés « cœur » (favoris d'extracteur) enregistrées pour le provider courant.
     * Set vide si aucun provider ou aucun favori — auquel cas les appelants évitent
     * de calculer `favKeyFor`, qui parcourt tous les extracteurs.
     */
    /**
     * Vraie lecture en cours : posé par le fragment quand le player passe à `isPlaying`.
     *
     * 2026-08-07 (user : « un cœur rouge apparaissait alors qu'une lecture a commencé sur un
     *   serveur super long, il ne switche pas dessus — le cœur est censé être prioritaire
     *   TANT QUE LA LECTURE N'A PAS COMMENCÉ ») — il a raison, et mon garde-fou était faux.
     *   `autoPlayEmitted` signifie « on a DEMANDÉ une lecture », pas « ça joue ». Un serveur
     *   qui met 20 s à extraire bloquait donc le cœur arrivé à la 5ᵉ seconde, alors qu'il n'y
     *   avait toujours aucune image à l'écran. On distingue désormais les deux : tant que
     *   ce drapeau est faux, un favori qui apparaît prend la main.
     */
    @Volatile private var lectureDemarree = false

    /** Appelé par les fragments sur `onIsPlayingChanged(true)`. */
    fun signalerLectureDemarree() {
        if (!lectureDemarree) {
            lectureDemarree = true
            Log.i("ServDiag", "PROG lecture RÉELLEMENT démarrée → le raccourci cœur ne s'applique plus")
        }
    }

    /**
     * Jeton posé quand le raccourci cœur se déclenche sur un lot TARDIF, c'est-à-dire après
     * qu'un premier serveur a déjà été choisi. Le fragment garde `initialServerPicked` pour
     * ne pas relancer `getVideo` à chaque vague ; sans ce jeton, le cœur arrivé en retard
     * serait simplement ignoré — exactement ce que le user a constaté. Consommé une fois.
     */
    @Volatile private var raccourciCoeurEnAttente = false

    /** True UNE seule fois, si un cœur attend d'être joué. Remet le jeton à zéro. */
    fun consommerRaccourciCoeur(): Boolean {
        if (!raccourciCoeurEnAttente) return false
        raccourciCoeurEnAttente = false
        return true
    }

    /** Remis à zéro à chaque nouvelle collecte de serveurs (nouvel épisode / nouveau titre). */
    private fun reinitialiserLecture() {
        lectureDemarree = false
        raccourciCoeurEnAttente = false
    }

    private fun favorisDuProvider(): Set<String> {
        val providerName = com.streamflixreborn.streamflix.utils.UserPreferences.currentProvider?.name
        return if (!providerName.isNullOrEmpty())
            com.streamflixreborn.streamflix.utils.ExtractorToggleStore.getFavorites(providerName)
        else emptySet()
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
        val favorites = favorisDuProvider()
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
                // Pas de langue détectable → VF prouvé > VOSTFR prouvé > inconnu > VO
                // ⚠ 2026-08-11 (user : « EMBED, gros problème, ils me proposent des serveurs
                //   qui ne sont pas dans la bonne langue… soit c'est en VOSTFR soit en FR,
                //   mais rien d'autre »).
                //
                //   AVANT : `else -> 0`, c'est-à-dire « vf OU INCONNU = tête ». Un serveur
                //   dont le nom ne porte aucun marqueur de langue était donc promu AU MÊME
                //   RANG qu'un VF prouvé — et passait devant lui au tri suivant. C'est ce qui
                //   plaçait « VixSrc », « VidLink » et compagnie en tête de liste alors qu'ils
                //   servent de la VO : ils ne se glissaient pas dans la liste, ils la prenaient.
                //
                //   MAINTENANT : l'inconnu a son propre rang, derrière tout ce qui est prouvé
                //   français. Rien ne disparaît — c'est un changement d'ORDRE, pas de filtre,
                //   donc aucun serveur ne peut être perdu. Mais plus rien d'incertain ne passe
                //   devant un VF avéré.
                //
                //   La branche `curLang != null` juste au-dessus faisait déjà exactement ça
                //   (« pas de tag = neutre, en bas mais visible ») ; les deux branches sont
                //   enfin cohérentes.
                when (lang) {
                    "vf"      -> 0
                    "vostfr"  -> 1
                    "vo"      -> 999
                    else      -> 2   // inconnu : visible, mais jamais devant un français prouvé
                }
            }
        }
        val hideVo = providerName != null &&
            com.streamflixreborn.streamflix.utils.CatalogFilter.isSupported(providerName) &&
            com.streamflixreborn.streamflix.utils.CatalogFilter.get(providerName) ==
                com.streamflixreborn.streamflix.utils.CatalogFilter.Mode.POPULAR_INTL
        // Buckets calculés UNE FOIS ici, réutilisés par le filtre ET par le tri (cf. plus bas).
        val bucketsPreCalcules = HashMap<String, Int>(ranked.size * 2)
        ranked.forEach { bucketsPreCalcules[it.id] = bucket(it) }
        val filtered = if (hideVo) {
            ranked.filter { s ->
                val b = bucketsPreCalcules[s.id] ?: 999
                b < 999 || frRegex.containsMatchIn(s.name)
            }
        } else ranked

        Log.d("OFB", "G avant sort filtered=${filtered.size}")
        Log.i("ServDiagT", "  avant sort à ${System.currentTimeMillis()-_t}ms (curLang=$curLang hideVo=$hideVo)")
        // 2026-08-02 (user : « fais en sorte que les serveurs FileSearch montent en priorité, ce
        //   sont les meilleurs niveaux de qualité ») : FileSearch sert des FICHIERS DIRECTS
        //   (.mkv/.mp4 d'open-directories, souvent 1080p MULTi de 1,5 à 4 Go) — pas de
        //   ré-encodage, pas d'extracteur, pas de lecteur web : c'est la meilleure image
        //   disponible et la lecture la plus fiable quand l'hôte répond.
        //   ⚠ La LANGUE reste le critère premier : on n'ordonne qu'À L'INTÉRIEUR de chaque
        //   bucket, sinon un FileSearch VOSTFR passerait devant un vrai VF.
        fun rangSource(s: Video.Server): Int =
            if (s.id.startsWith("bkreg::FileSearch::") || s.name.startsWith("FileSearch")) 0 else 1

        // 2026-08-02 (user : « faire remonter les serveurs de qualité au classement ») : à langue et
        //   origine égales, on classe par DÉFINITION décroissante puis par DÉBIT décroissant.
        //   Le débit départage ce que le label ne distingue pas — deux « 1080p » n'ont pas le même
        //   encodage — et rattrape les serveurs sans définition connue grâce au poids mesuré.
        //   Valeur inconnue = 0 : le serveur n'est pas rétrogradé pour autant, il garde son rang
        //   naturel derrière ceux qu'on a pu mesurer.
        fun rangQualite(s: Video.Server): Int = when (s.quality?.lowercase()?.trim()) {
            "2160p", "4k" -> 0
            "1440p" -> 1
            "1080p" -> 2
            "720p" -> 3
            "480p" -> 4
            "360p", "sd" -> 5
            else -> 6   // inconnue → après les qualités établies
        }
        // ⚠ 2026-08-02 (user : « le tri VF/VOSTFR ne doit aucunement retarder l'arrivée des
        //   serveurs ») : les CLÉS SONT CALCULÉES UNE SEULE FOIS, avant de trier.
        //   `sortedWith` appelle le comparateur O(n log n) fois ; comme `bucket()` reconstruit à
        //   chaque appel une clé de favori et repasse des expressions régulières sur le nom, le
        //   même travail était refait des centaines de fois. Mesuré : 1,6 à 2,9 s pour 29 à 53
        //   serveurs — à chaque lot, donc plusieurs fois par ouverture.
        //   Trier sur des valeurs déjà calculées donne EXACTEMENT le même ordre, sans le coût.
        val clés = HashMap<String, IntArray>(filtered.size * 2)
        filtered.forEach { s ->
            val b = bucketsPreCalcules[s.id] ?: bucket(s)   // déjà calculé au-dessus
            clés[s.id] = intArrayOf(b, rangSource(s), rangQualite(s), -s.debitKbps)
        }
        fun clé(s: Video.Server): IntArray = clés[s.id] ?: intArrayOf(9, 9, 9, 0)
        val sorted = filtered.sortedWith { a, b ->
            val x = clé(a); val y = clé(b)
            var r = 0
            for (i in 0..3) { r = x[i].compareTo(y[i]); if (r != 0) break }
            r
        }
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
    /**
     * 2026-08-02 (user : « j'ai trouvé l'arrivée des serveurs un peu longue, t'as pas créé un truc
     * qui ralentit ? ») — oui, et c'était ceci.
     *
     * Chaque langue corrigée déclenchait un `resortServers()` immédiat. Or un tri complet coûte
     * 1,5 à 2,8 s (mesuré : `orderByFrenchBuckets(53) fait en 2852ms`) : avec plusieurs faux VF
     * démasqués, on empilait autant de tris pendant l'arrivée des serveurs.
     *
     * On REGROUPE donc les demandes : plusieurs corrections rapprochées ne produisent qu'UN seul
     * tri, déclenché une fois le calme revenu. Le résultat affiché est identique, le coût divisé.
     */
    private var retriJob: kotlinx.coroutines.Job? = null

    private fun demanderRetri() {
        retriJob?.cancel()
        retriJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1_200L)   // laisse les corrections voisines s'accumuler
            resortServers()
        }
    }

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
    /** Id du serveur dont l'extraction est en cours — évite de relancer (donc d'annuler) une
     *  extraction déjà EN VOL pour le MÊME serveur. Filemoon, dont la vérif WebView q8y5z dure
     *  ~20s, se faisait tuer par un 2e getVideo du même serveur (déclenché par la liste progressive). */
    private var getVideoServerId: String? = null

    /** Annule l'extraction vidéo en cours (si une). Appelé par le fragment
     *  quand l'user tap l'overlay de chargement pour changer de serveur. */
    fun cancelGetVideo() {
        getVideoJob?.cancel()
        getVideoJob = null
        getVideoServerId = null
    }

    /** 2026-07-12 (user « une fois qu'un film joue, il faut arrêter des choses qui chargent en
     *  fond ») : coupe la RECHERCHE de serveurs de secours (BackupRegistry + WebView CF lourdes
     *  type cpasmal/Filemoon) qui traîne après le démarrage de la lecture — inutile une fois qu'on
     *  regarde, et ça bouffe CPU/réseau/RAM sur la TV. Les serveurs DÉJÀ trouvés restent dans le
     *  picker ; on arrête juste d'en chercher plus. Appelé par le fragment après ~lecture stable. */
    fun stopBackgroundServerSearch() {
        if (serverJob?.isActive == true || additionalServerJob?.isActive == true) {
            Log.d("PlayerViewModel", "stopBackgroundServerSearch: lecture stable → coupe la recherche de backups")
        }
        serverJob?.cancel()
        additionalServerJob?.cancel()
        preExtractJob?.cancel()
        progressiveStillCollecting = false
    }

    fun getVideo(server: Video.Server): kotlinx.coroutines.Job {
        // 2026-07-27 : si une extraction est DÉJÀ en cours pour CE serveur, on la RÉUTILISE au lieu
        //   de l'annuler+relancer. Sinon Filemoon (vérif WebView ~20s) se faisait tuer par un 2e
        //   getVideo du même serveur (déclenché par la liste progressive) → « cancelled » en boucle,
        //   la vérif q8y5z (5-6 clics) n'avait jamais le temps d'aboutir.
        val existing = getVideoJob
        if (existing != null && existing.isActive && getVideoServerId == server.id) {
            Log.d("PlayerViewModel", "getVideo déjà en cours pour ${server.name} — réutilisation (pas de cancel/restart)")
            return existing
        }
        // 2026-05-18 : anti-cascade. Cancel previous getVideo AND any pre-extract
        //   job running en background, sinon plusieurs extractions WebView en
        //   parallèle saturent CPU + mémoire et le player tourne dans le vide.
        getVideoJob?.cancel()
        preExtractJob?.cancel()
        getVideoServerId = server.id
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

            reglerSousTitresSelonLangue(server, video)

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
                // 2026-07-22 (user « si les utilisateurs veulent changer de langue, tu mets en
                //   place ») : on interroge TOUTES les langues choisies dans les Paramètres
                //   (MultiSelect « Langues des sous-titres »), le français restant le défaut.
                //   Le toggle anglais historique est inclus via UserPreferences.subtitleLanguages.
                //   L'ordre suit celui de la préférence (français d'abord par convention).
                // 2026-05-04 : on PASSE TOUJOURS imdb_id quand le provider le fournit (sinon la
                //   recherche tombe en mode « query texte » et peut matcher une autre œuvre).
                val languages = com.streamflixreborn.streamflix.utils.UserPreferences.subtitleLanguages
                val orderedLangs = (listOf("fre") + languages).distinct().filter { it in languages }
                val perLang = LinkedHashMap<String, List<OpenSubtitles.Subtitle>>()
                for (lang in orderedLangs) {
                    val res = when (videoType) {
                        is Video.Type.Episode -> OpenSubtitles.search(
                            imdbId = videoType.tvShow.imdbId,
                            query = if (videoType.tvShow.imdbId.isNullOrBlank()) videoType.tvShow.title else null,
                            season = videoType.season.number,
                            episode = videoType.number,
                            subLanguageId = lang,
                        )
                        is Video.Type.Movie -> OpenSubtitles.search(
                            imdbId = videoType.imdbId,
                            query = if (videoType.imdbId.isNullOrBlank()) videoType.title else null,
                            subLanguageId = lang,
                        )
                    }
                    perLang[lang] = res.sortedByDescending { it.subDownloadsCnt }
                }
                // Concatène langue par langue (tri downloads DESC dans chaque groupe).
                val subtitles = perLang.values.flatten()

                Log.d("PlayerViewModel", "OpenSubtitles: " + perLang.entries.joinToString { "${it.key}=${it.value.size}" })
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca SubDL")
                // 2026-07-22 : mêmes langues que OpenSubtitles (préférence commune), converties
                //   au format SubDL (2 lettres MAJ) via SubDL.osToSubdl().
                val subdlLangs = com.streamflixreborn.streamflix.utils.UserPreferences.subtitleLanguages
                    .map { SubDL.osToSubdl(it) }.distinct()
                val subdlLangParam = (listOf("FR") + subdlLangs).distinct()
                    .filter { it in subdlLangs }.joinToString(",")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        SubDL.search(
                            filmName = videoType.tvShow.title,
                            seasonNumber = videoType.season.number,
                            episodeNumber = videoType.number,
                            type = "tv",
                            languages = subdlLangParam,
                        )
                    }
                    is Video.Type.Movie -> {
                        SubDL.search(
                            filmName = videoType.title,
                            type = "movie",
                            languages = subdlLangParam,
                        )
                    }
                }

                Log.d("PlayerViewModel", "Ricerca SubDL completata ($subdlLangParam): ${subtitles.size} risultati")
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
