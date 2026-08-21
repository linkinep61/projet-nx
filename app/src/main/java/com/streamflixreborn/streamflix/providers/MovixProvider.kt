package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object MovixProvider : Provider, ProviderConfigUrl, ProviderPortalUrl, ProgressiveServersProvider {

    /** 2026-05-07 : Flag anti-récursion. Quand Cloudstream appelle Movix en backup,
     *  il met ce flag à true pour que Movix skippe ses propres backups (Cloudstream,
     *  Moviebox, Papa, Coflix) — sinon Cloudstream→Movix→Cloudstream→… infinite loop.
     *  Restauré à false dans le finally côté Cloudstream. Race conditions possibles
     *  avec appels parallèles mais en pratique le user clique 1 film à la fois. */
    @Volatile var skipBackupsForBackupCall: Boolean = false

    /** 2026-06-02 — Helper a utiliser par TOUS les providers qui appellent
     *  Movix comme backup (FS, Wiflix, Moviebox, Frembed, Papa, aplouf, etc.).
     *  Set le flag skipBackupsForBackupCall=true pendant l'appel pour eviter
     *  que Movix re-call ses propres backups (FS direct, Wiflix direct…) qui
     *  produisent des doublons "fs_direct__movix_backup__XXX" dans le picker.
     *  Restaure la valeur precedente dans le finally (safe pour multi-callers). */
    suspend fun getServersAsBackup(id: String, videoType: com.streamflixreborn.streamflix.models.Video.Type): List<com.streamflixreborn.streamflix.models.Video.Server> {
        val prev = skipBackupsForBackupCall
        skipBackupsForBackupCall = true
        return try {
            // 2026-06-13 (user "le patch FS Voe HD pollue tous les providers,
            //   ca doit etre la meme chose sur tous les providers avec backup") :
            //   centralise le filtre `fstream-*` ici → tout provider qui
            //   appelle getServersAsBackup recoit automatiquement la liste
            //   nettoyee (= plus de "FS · X (VF - HD)" pollues qui jouent
            //   mauvais contenu). Affecte Cloudstream, Frembed, FrenchStream,
            //   Wiflix, Moviebox, Papa, aplouf, DessinAnime, NetMirror...
            getServers(id, videoType).filter { !it.id.startsWith("fstream-") }
        } finally {
            skipBackupsForBackupCall = prev
        }
    }

    override val name = "Movix"
    // 2026-07-30 : movix.show + api.movix.show MORTS (NXDOMAIN). `movix.online` (page de statut,
    //   vivante) annonce désormais **movix.fun** comme domaine courant (movix.show = « bloqué par
    //   les FAI »). Défaut bumpé sur movix.fun / api.movix.fun (vérifié vivants). L'auto-update
    //   (fetchActiveDomain via <title> de movix.online) confirme en runtime ; on garde un défaut
    //   VIVANT au cas où le cache serait vide (sinon 0 catalogue au 1er boot).
    override val defaultBaseUrl: String = "https://api.movix.fun/"
    override val baseUrl: String = defaultBaseUrl
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            // ignore un cache pointant sur un domaine mort connu → force le re-fallback/redécouverte.
            val dead = listOf("movix.date", "movix.cloud", "movix.show")
            return if (cacheURL.isNotEmpty() && dead.none { cacheURL.contains(it) }) cacheURL else field
        }
    override val defaultPortalUrl: String = "https://movix.fun/"
    override val portalUrl: String = defaultPortalUrl
        get() {
            val cachePortalURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL)
            return cachePortalURL.ifEmpty { field }
        }
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_movix"
    override val language = "fr"
    override val changeUrlMutex = Mutex()

    private const val AUTO_UPDATE_URL = "https://movix.online/"  // v90 2026-05-27 : portail d'adresses officiel (remplace movix.health mort)

    private lateinit var movixServiceInstance: MovixService
    private var serviceInitialized = false
    private val initializationMutex = Mutex()

    // ══════════════════════════════════════════════════════════════════════════════════
    // 2026-08-11 — CONTRÔLE D'IDENTITÉ DE L'ÉPISODE SERVI (user : « faut éviter d'avoir
    //   des mauvais matchs, c'est la priorité dans cette application »)
    //
    // LE PROBLÈME. Sur « La Quatrième Dimension » (TMDB 6357, 1959, noir et blanc,
    //   26 min), l'endpoint tmdb-tv rendait 12 lecteurs pointant sur un fichier EN
    //   COULEUR de 47 min. On demande pourtant le bon identifiant.
    //
    // LA CAUSE. Quatre séries portent le titre « The Twilight Zone » chez TMDB :
    //     6357   1959   La Quatrième Dimension
    //     1918   1985   La Cinquième Dimension
    //     16399  2002   La Treizième Dimension
    //     83135  2019   The Twilight Zone : La Quatrième Dimension
    //   Movix les confond et sert le remake de 2019.
    //
    // LA PREUVE, capturée en direct sur l'Oppo. Movix SE CONTREDIT dans sa propre réponse :
    //     "tmdb_details":  {"title":"La Quatrième Dimension","release_date":"1959-10-02"}
    //     "current_episode":{"title":"The Twilight Zone : La Quatrième Dimension - S01E04…"}
    //   Il confirme l'œuvre demandée, puis annonce l'épisode d'une autre. Le titre déclaré
    //   est mot pour mot celui de la série 2019.
    //
    // POURQUOI PAS LA DURÉE (piste abandonnée, décision user). Une pub incrustée dans le
    //   flux fausse la mesure, et on supprimerait alors un bon serveur. Ici on ne mesure
    //   rien : on compare deux chaînes que Movix nous donne lui-même, dans la même
    //   réponse, sans une seule requête de plus. Un encodage ne change pas un titre.
    //
    // POURQUOI UNE ÉGALITÉ ET PAS UN « CONTAINS » NI UN CONTRÔLE DE MOTS EN TROP. Le titre
    //   de 2019 CONTIENT celui de 1959, et il est entièrement bâti sur les mots des deux
    //   titres connus de 1959 (« The Twilight Zone » + « La Quatrième Dimension »). Un
    //   test d'inclusion le laisse passer ; un contrôle de vocabulaire aussi, puisqu'il
    //   n'introduit aucun mot étranger. Seule l'égalité tranche.
    // ══════════════════════════════════════════════════════════════════════════════════

    /** Retire accents, ponctuation et espaces multiples. */
    private fun normTitre(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val sansAccent = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return sansAccent.lowercase()
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Isole le nom de l'ŒUVRE dans le titre d'épisode déclaré par Movix.
     *
     * Format observé : `<œuvre> - S01E04 <œuvre> 1x4`. On coupe au premier marqueur de
     * position, et on retire une éventuelle année entre parenthèses.
     */
    private fun oeuvreDansTitreEpisode(titreEpisode: String?): String {
        if (titreEpisode.isNullOrBlank()) return ""
        val coupe = Regex("(?i)\\s*[-–|]?\\s*(s\\d{1,3}\\s*e\\d{1,3}|\\d{1,3}x\\d{1,3}|episode\\s*\\d+)")
            .find(titreEpisode)?.range?.first
        val brut = if (coupe != null && coupe > 0) titreEpisode.substring(0, coupe) else titreEpisode
        return normTitre(brut.replace(Regex("\\(\\s*\\d{4}\\s*\\)"), " "))
    }

    /**
     * L'épisode annoncé appartient-il bien à l'œuvre demandée ?
     *
     * @return `true` si on garde (correspondance, ou information absente — dans le doute on
     *   ne supprime jamais), `false` si Movix déclare explicitement une autre œuvre.
     */
    private fun titreOeuvreCorrespond(
        titreEpisode: String?,
        details: TmdbMovixDetails?,
        titresConnus: List<String>,
    ): Boolean {
        val declare = oeuvreDansTitreEpisode(titreEpisode)
        if (declare.isBlank()) return true          // rien de déclaré → on ne juge pas
        val attendus = (
            listOfNotNull(details?.title, details?.original_title) + titresConnus
            ).map { normTitre(it) }.filter { it.isNotBlank() }.distinct()
        if (attendus.isEmpty()) return true         // rien à comparer → on ne juge pas
        return attendus.any { it == declare }
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    // CONTRÔLE D'IDENTITÉ CPASMAL — 2026-08-20 (user « ET MOVIX un mauvais match »)
    //
    // Sur un épisode de Star Trek 1966 (TMDB tv 253, S01E03), l'app affichait 10 serveurs
    //   Movix (5 VF + 5 VOSTFR) qui lisaient une AUTRE série. Relevé en direct sur l'API
    //   (tools/test_movix_253.py) :
    //     GET api.movix.fun/api/cpasmal/tv/253/1/3
    //     {"title":"Star Trek","year":"1966",
    //      "cpasmalUrl":"https://www.cpasmal.rip/19695-star-trek-starfleet-academy.html",
    //      "links":{"vf":[5],"vostfr":[5]}}
    //   Les 10 URLs correspondaient exactement à celles du journal de l'Oppo. L'œuvre
    //   réellement servie est « Star Trek: Starfleet Academy » (2025).
    //
    // POURQUOI PAS `title` NI `year`. Ils valent « Star Trek » et « 1966 » : l'API les
    //   recopie de TMDB. Ils décrivent la DEMANDE, pas la RÉPONSE. Les contrôler revient
    //   à comparer une chaîne avec elle-même — ça passe toujours.
    //
    // POURQUOI UNE ÉGALITÉ ET PAS UN « CONTAINS ». Le slug servi
    //   (« star trek starfleet academy ») CONTIENT le titre demandé (« star trek »).
    //   Même leçon que La Quatrième Dimension 1959/2019 plus haut : seule l'égalité tranche.
    //
    // POURQUOI ON RETIRE DES MARQUEURS AVANT DE COMPARER. Les slugs cpasmal portent
    //   souvent « -saison-1 », « -vostfr », « -1966 » — présents sur le BON contenu aussi.
    //   Les garder ferait échouer des matchs légitimes. Ils sont retirés des deux côtés.
    //
    // FAIL-OPEN ASSUMÉ : slug illisible ou absent → on garde. On ne supprime que sur une
    //   contradiction explicite, jamais sur un doute.
    // ══════════════════════════════════════════════════════════════════════════════════

    /** Marqueurs de langue / position / qualité présents dans les slugs cpasmal. */
    private val MARQUEURS_SLUG = Regex(
        "(?i)\\b(saison\\s*\\d{1,3}|s\\d{1,3}e?\\d{0,3}|\\d{1,3}x\\d{1,3}|episode\\s*\\d+|" +
            "vostfr|vost|vf|truefrench|french|multi|streaming|complet|complete|" +
            "integrale|hd|4k|1080p|720p|\\d{4})\\b",
    )

    /** Nom de l'œuvre tel qu'il apparaît dans l'URL cpasmal servie.
     *  `…/19695-star-trek-starfleet-academy.html` → `star trek starfleet academy`. */
    private fun oeuvreDansSlugCpasmal(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val fichier = url.substringBefore('?').substringAfterLast('/').removeSuffix(".html")
        val sansId = fichier.replaceFirst(Regex("^\\d+-"), "")
        val n = MARQUEURS_SLUG.replace(normTitre(sansId.replace('-', ' ')), " ")
        return n.replace(Regex("\\s+"), " ").trim()
    }

    /** L'URL cpasmal servie désigne-t-elle bien l'œuvre demandée ?
     *  @return `true` si on garde (correspondance, ou information absente). */
    private fun slugCpasmalCorrespond(url: String?, titresConnus: List<String?>): Boolean {
        val declare = oeuvreDansSlugCpasmal(url)
        if (declare.isBlank()) return true
        val attendus = titresConnus.filterNotNull()
            .map { MARQUEURS_SLUG.replace(normTitre(it), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }.distinct()
        if (attendus.isEmpty()) return true
        return attendus.any { it == declare }
    }

    private const val TMDB_API_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
    private const val TMDB_BASE_URL = "https://api.themoviedb.org/3/"
    private const val TMDB_IMG_W500 = "https://image.tmdb.org/t/p/w500"
    private const val TMDB_IMG_ORIGINAL = "https://image.tmdb.org/t/p/original"

    /**
     * Formate le nom de la langue pour l'affichage.
     * Ex: "vf" -> "VF", "vostfr" -> "VOSTFR", "vo" -> "VO"
     */
    private fun formatLang(lang: String): String {
        val l = lang.trim().lowercase()
        return when {
            l == "vf" || l == "french" || l == "français" -> "VF"
            l == "vostfr" || l == "vost" -> "VOSTFR"
            l == "vo" || l == "original" -> "VO"
            l == "multi" -> "Multi"
            else -> lang.uppercase()
        }
    }

    // ── 2026-05-04 : Circuit breaker par endpoint ────────────────────────
    // Movix expose 6+ sub-APIs (fstream, links, wiflix, cpasmal, tmdb, drama,
    // purstream...). Plusieurs renvoient 404 systématiquement sur les vieux
    // contenus (cpasmal sur séries pré-2010 par ex.) et nous font perdre des
    // secondes à chaque chargement. On garde l'appel "au cas où" mais avec un
    // timeout strict + désactivation temporaire après N timeouts/erreurs.
    // 2026-05-11 : 4s → 2.5s. La plupart des endpoints répondent en 200-500ms ;
    // 4s c'était over-conservative et bottleneck-ait getServers via awaitAll
    // (qui attend le plus lent). 2.5s = bonne tolérance réseau sans plomber.
    private const val ENDPOINT_TIMEOUT_MS = 2_500L
    private const val ENDPOINT_FAILURE_THRESHOLD = 10 // plus tolérant
    private const val ENDPOINT_DISABLED_MS = 2L * 60L * 1000L // 2 min seulement
    private data class EndpointHealth(
        var consecutiveFailures: Int = 0,
        var disabledUntilMs: Long = 0L,
    )
    private val endpointHealth = ConcurrentHashMap<String, EndpointHealth>()

    /** Wrap d'appel endpoint Movix : timeout 4s + circuit breaker.
     *  Si ça timeout ou throw, on retourne emptyList(). 5 fails consécutifs
     *  → endpoint désactivé 30 min (skip immédiat). 1 succès → reset le compteur. */
    private suspend inline fun <T : List<Video.Server>> runEndpoint(
        endpointName: String,
        crossinline block: suspend () -> T,
    ): List<Video.Server> {
        // 2026-06-01 : circuit breaker DÉSACTIVÉ (causait des blocages serveurs).
        // On garde juste le timeout par requête (ENDPOINT_TIMEOUT_MS).
        val result = try {
            withTimeoutOrNull(ENDPOINT_TIMEOUT_MS) { block() }
        } catch (e: Exception) {
            Log.w("MovixProvider", "Endpoint '$endpointName' threw: ${e.message}")
            null
        }
        val health = endpointHealth.getOrPut(endpointName) { EndpointHealth() }
        synchronized(health) {
            if (result.isNullOrEmpty()) {
                health.consecutiveFailures++
            } else {
                health.consecutiveFailures = 0
            }
        }
        return result ?: emptyList()
    }

    // ── 2026-05-04 : Cache du résultat agrégé getServers ──────────────────
    // L'utilisateur ferme le player et y revient -> on refaisait 6 appels API
    // + l'assemblage à chaque fois (1-3s). Maintenant on cache le résultat
    // par clé (tmdbId, season, episode) pendant 5 min.
    // (Le cache d'EXTRACTION m3u8 est séparé, géré dans Extractor.kt avec
    // sa propre TTL — plus court car les m3u8 expirent vite.)
    private const val SERVERS_CACHE_TTL_MS = 5L * 60L * 1000L
    private data class CachedServers(val servers: List<Video.Server>, val expiresAtMs: Long)
    private val serversCache = ConcurrentHashMap<String, CachedServers>()

    /** 2026-07-04 : vide les caches en mémoire. Appelé par ProviderCacheRefresh. */
    fun clearCaches() {
        endpointHealth.clear()
        serversCache.clear()
    }

    /**
     * 2026-08-16 — VERSION de la logique d'assemblage, inscrite dans la clé de cache.
     *
     * Le cache garde l'agrégat 5 min. Tant qu'il est chaud, `getServers` rend l'ANCIENNE liste
     * sans rappeler un seul endpoint — donc sans écrire une ligne de journal non plus. Un
     * correctif de filtrage passait ainsi pour inopérant : on relisait une liste bâtie avec le
     * code précédent, et le journal restait muet, ce qui donnait l'impression que le filtre ne
     * s'appliquait pas (constaté ce jour avec les 15 serveurs injectés via Cloudstream).
     *
     * En versionnant la clé, toute entrée produite par une version antérieure devient
     * inatteignable : le premier appel après mise à jour reconstruit forcément la liste.
     * ⚠ À INCRÉMENTER à chaque modification de ce qui est émis (filtres, bridages, libellés).
     */
    private const val SERVERS_CACHE_VERSION = 7

    private fun serversCacheKey(videoType: Video.Type): String = when (videoType) {
        is Video.Type.Movie -> "v$SERVERS_CACHE_VERSION:movie:${videoType.id}"
        is Video.Type.Episode ->
            "v$SERVERS_CACHE_VERSION:tv:${videoType.tvShow.id}:s${videoType.season.number}:e${videoType.number}"
    }

    private val tmdbService: TmdbService by lazy { buildTmdbService() }

    // ── 2026-05-04 : TMDB-iframe backups en QUEUE de liste ───────────────
    // Movix a déjà beaucoup de sources natives FR (fstream, wiflix, cpasmal,
    // etc.) qui marchent très bien. On ajoute ces 5 backups TMDB-iframe à la
    // FIN du sélecteur de servers — ils sont là "au cas où" un film/série
    // n'a aucune source native FR. Ordre = sources clean d'abord, vidsrc.to
    // (CF-prone) en dernier.
    private data class TmdbBackupSource(
        val key: String,
        val displayName: String,
        val movieUrl: (tmdbId: String) -> String,
        val tvUrl: (tmdbId: String, season: Int, episode: Int) -> String,
    )

    private val tmdbBackupSources: List<TmdbBackupSource> = listOf(
        TmdbBackupSource(
            key = "vidsrc-icu",
            displayName = "Vidsrc.icu",
            movieUrl = { id -> "https://vidsrc.icu/embed/movie/$id" },
            tvUrl = { id, s, e -> "https://vidsrc.icu/embed/tv/$id/$s/$e" },
        ),
        TmdbBackupSource(
            key = "2embed-skin",
            displayName = "2Embed",
            movieUrl = { id -> "https://2embed.skin/embed/tmdb-movie-$id" },
            tvUrl = { id, s, e -> "https://2embed.skin/embed/tmdb-tv-$id&s=$s&e=$e" },
        ),
        TmdbBackupSource(
            key = "nontongo",
            displayName = "Nontongo",
            movieUrl = { id -> "https://nontongo.win/embed/movie/$id" },
            tvUrl = { id, s, e -> "https://nontongo.win/embed/tv/$id/$s/$e" },
        ),
        TmdbBackupSource(
            key = "111movies",
            displayName = "111Movies",
            movieUrl = { id -> "https://111movies.net/movie/$id" },
            tvUrl = { id, s, e -> "https://111movies.net/tv/$id/$s/$e" },
        ),
        TmdbBackupSource(
            key = "vidsrc-to",
            displayName = "Vidsrc.to",
            movieUrl = { id -> "https://vidsrc.to/embed/movie/$id" },
            tvUrl = { id, s, e -> "https://vidsrc.to/embed/tv/$id/$s/$e" },
        ),
    )

    /** Construit la liste des Video.Server backups TMDB-iframe pour Movix.
     *  Identique à NakiosProvider mais avec son propre wiring local
     *  (paramètres season/episode en Int car Movix Episode VideoType les a
     *  déjà parsés).
     *  2026-05-11 : exposé en public pour permettre à d'autres providers
     *  (VoirDrama, AnimeSama, FrenchAnime, FrenchManga, VoirAnime, UnJourUnFilm)
     *  d'enrichir leur picker de serveurs avec les sources Movix. */
    fun buildTmdbBackupServersForMovix(
        tmdbId: String,
        videoType: Video.Type,
    ): List<Video.Server> {
        if (!tmdbId.all { it.isDigit() }) return emptyList()
        // 2026-08-16 : DEUXIÈME ARRIVÉE de Movix, hors de `links` — les 5 backups
        //   TMDB-iframe (Vidsrc.icu et consorts). Ils sont ajoutés par SIX providers
        //   (VoirDrama, AnimeSama, FrenchAnime, FrenchManga, VoirAnime, UnJourUnFilm)
        //   et n'ont jamais rien de natif : ce sont des iframes TMDB génériques.
        //   C'est cette voie qui continuait à gonfler la liste alors que le filtrage de
        //   `links` était bien actif — d'où l'impression que rien ne changeait.
        if (NATIFS_SEULEMENT) {
            Log.d("MovixProvider", "backups TMDB-iframe écartés (natifs seulement)")
            return emptyList()
        }
        return when (videoType) {
            is Video.Type.Movie -> tmdbBackupSources.map { source ->
                Video.Server(
                    id = "${source.key}_movix_movie_$tmdbId",
                    name = source.displayName,
                    src = source.movieUrl(tmdbId),
                )
            }
            is Video.Type.Episode -> {
                val s = videoType.season.number
                val e = videoType.number
                tmdbBackupSources.map { source ->
                    Video.Server(
                        id = "${source.key}_movix_tv_${tmdbId}_${s}_$e",
                        name = source.displayName,
                        src = source.tvUrl(tmdbId, s, e),
                    )
                }
            }
        }
    }

    // ── 2026-05-04 : Yflix.to backup source ──────────────────────────────
    // yflix.to est un aggregator FR (UI traduite via Google Translate côté
    // browser, pas de Cloudflare challenge bloquant). On l'utilise en
    // SOURCE COMPLEMENTAIRE quand Movix natif n'a rien — ou en plus pour
    // augmenter le nombre de servers dispos.
    //
    // Workflow :
    //   1. TMDB id → title+year (déjà fait par tmdbService pour le home)
    //   2. /ajax/film/search?keyword={title} → JSON avec HTML <a class="item">
    //   3. Match best result par (title, year, type)
    //   4. Build Video.Server avec src = yflix.to/watch/{slug}.{id}#ep=S,E
    //   5. YflixExtractor (WebView) intercepte le m3u8 final
    private const val YFLIX_BASE = "https://yflix.to/"
    // 2026-05-21 : nb de serveurs yflix exposés comme sources séparées (le site en
    //   propose ~2/titre). Fixe pour éviter une énumération WebView coûteuse au
    //   moment du getServers ; les entrées en trop échouent et sont reléguées.
    private const val YFLIX_MAX_SERVERS = 2
    // 2026-05-21 (user "pour pas pénaliser l'app on désactive yflix pour l'instant,
    //   on en trouvera un autre") : yflix est trop instable (WebView lente, hosts qui
    //   tournent, fichiers morts "data center burned"). DÉSACTIVÉ temporairement —
    //   searchYflix renvoie null donc aucune source yflix n'est générée. Remettre
    //   à true pour réactiver (le split serveurs + auto-fallback restent en place).
    private const val YFLIX_ENABLED = false

    /** Cherche un titre sur yflix.to via l'AJAX search public.
     *  Retourne le path /watch/{slug}.{id} du meilleur match, ou null si rien.
     *  Match heuristique : titre (case-insensitive contains) + année (±1) si fournie.
     *  Type filter : "Movie" ou "TV" pour préférer le bon kind. */
    private suspend fun searchYflix(
        title: String,
        year: Int? = null,
        preferType: String = "Movie",
    ): String? {
        if (!YFLIX_ENABLED) return null // 2026-05-21 : yflix désactivé temporairement
        if (title.isBlank()) return null
        // 2026-05-05 : normalise les apostrophes typographiques (U+2019 → ') avant URL-encoding
        // sinon le keyword devient %E2%80%99 et Yflix renvoie 0 résultats.
        val cleanTitle = com.streamflixreborn.streamflix.utils.TitleNormalizer.stripUnicodeArtifacts(title)
        return try {
            val url = "${YFLIX_BASE}ajax/film/search?keyword=" +
                java.net.URLEncoder.encode(cleanTitle, "UTF-8")
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", YFLIX_BASE)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build()
            val client = Extractor.sharedClient.newBuilder()
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val body = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.body?.string() }
            } ?: return null
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            val resultObj = json.getAsJsonObject("result") ?: return null
            val html = resultObj.get("html")?.asString ?: return null
            // Parse les <a class="item" href="/watch/{slug}.{id}"> avec leurs metadata
            val doc = org.jsoup.Jsoup.parse(html)
            val items = doc.select("a.item").mapNotNull { item ->
                val href = item.attr("href").takeIf { it.startsWith("/watch/") } ?: return@mapNotNull null
                val itemTitle = item.selectFirst("div.title")?.text()?.trim() ?: return@mapNotNull null
                val metadata = item.select("div.metadata span").map { it.text().trim() }
                val itemType = metadata.getOrNull(0) ?: "" // "Movie" ou "TV"
                val itemYear = metadata.getOrNull(1)?.takeIf { it.matches(Regex("\\d{4}")) }?.toIntOrNull()
                Triple(href, itemTitle, Pair(itemType, itemYear))
            }
            if (items.isEmpty()) {
                Log.d("MovixProvider", "Yflix search '$title' : no results")
                return null
            }
            // 2026-05-05 v2 : matching strict (cf searchMoiflix) — pas de
            // substring lâche. Exige match exact OU word-for-word + longueur
            // similaire pour éviter "The Boys" → "The Boyfriend".
            val targetTitleNorm = title.lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val targetWords = targetTitleNorm.split(" ").filter { it.length > 1 }.toSet()
            val scored = items.map { (href, t, meta) ->
                val (itype, iyear) = meta
                val tNorm = t.lowercase()
                    .replace(Regex("[^a-z0-9 ]"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                val candidateWords = tNorm.split(" ").filter { it.length > 1 }.toSet()
                val lenDiffPct = if (kotlin.math.max(tNorm.length, targetTitleNorm.length) > 0)
                    kotlin.math.abs(tNorm.length - targetTitleNorm.length).toDouble() /
                        kotlin.math.max(tNorm.length, targetTitleNorm.length)
                else 0.0
                var score = when {
                    tNorm == targetTitleNorm -> 100
                    targetWords.isNotEmpty() && candidateWords.containsAll(targetWords) && lenDiffPct <= 0.30 -> 90
                    candidateWords.isNotEmpty() && targetWords.containsAll(candidateWords) && lenDiffPct <= 0.30 -> 80
                    else -> 0
                }
                // 2026-05-21 (user "le Rose voulu c'est un Rose 2026" alors que yflix
                //   renvoyait Rose 2021) : l'année n'était qu'un BONUS, jamais une
                //   pénalité — un titre exact mais mauvais millésime passait quand même
                //   (100+10=110 > seuil 90). On pénalise désormais un écart d'année franc
                //   pour repasser SOUS le seuil → mieux vaut ne rien renvoyer que le
                //   mauvais film. Tolérance ±1 (bonus), 2 ans = neutre (metadata floue),
                //   ≥3 ans = mauvais film → grosse pénalité.
                if (year != null && iyear != null) {
                    val yearDiff = Math.abs(iyear - year)
                    when {
                        yearDiff <= 1 -> score += 30
                        yearDiff >= 3 -> score -= 60
                    }
                }
                if (itype == preferType) score += 10
                Triple(href, score, "$t ($itype, ${iyear ?: "?"})")
            }
            val best = scored.maxByOrNull { it.second }
            // Seuil 90 (était 50) — match strict requis.
            if (best == null || best.second < 90) {
                Log.d("MovixProvider", "Yflix '$title' : pas de match fiable (best=${best?.third} score=${best?.second}), skip")
                return null
            }
            Log.d("MovixProvider", "Yflix '$title' → ${best.third} score=${best.second}")
            best.first // /watch/{slug}.{id}
        } catch (e: Exception) {
            Log.w("MovixProvider", "Yflix search '$title' failed: ${e.message}")
            null
        }
    }

    /** 2026-05-21 (user "yflix doit afficher TOUTES ses sources, sinon l'algo de
     *  tri ne peut pas bien le classer") : on génère UNE entrée Video.Server PAR
     *  serveur yflix (le site expose ~2 serveurs/titre). Chaque entrée encode son
     *  index via `yfsrv=N` pour que YflixExtractor cible le bon serveur. Ainsi
     *  l'ExtractorRanker classe chaque serveur individuellement (au lieu d'un seul
     *  bloc "Multi"). Pour un épisode, on ajoute aussi le hash #ep=S,E. */
    private fun buildYflixServers(watchPath: String, season: Int? = null, episode: Int? = null): List<Video.Server> {
        val base = if (watchPath.startsWith("http")) watchPath
            else YFLIX_BASE.trimEnd('/') + watchPath
        val epFrag = if (season != null && episode != null)
            "#ep=$season,${episode.toString().padStart(2, '0')}" else ""
        val idBase = watchPath.substringAfterLast("/")
        return (1..YFLIX_MAX_SERVERS).map { n ->
            val sep = if (epFrag.isEmpty()) "#" else "&"
            Video.Server(
                id = "yflix_${idBase}_s$n",
                name = "Yflix Serveur $n",
                src = "$base$epFrag${sep}yfsrv=$n",
            )
        }
    }

    // ── 2026-05-04 : Moiflix.com backup source ───────────────────────────
    // Aggregator FR avec catalog complet (films + shows). Architecture :
    //   1. Search via JSON public : /ajax/posts?q={title}
    //   2. Match best result par titre + type (Film / Show)
    //   3. URL : /movie/{slug} ou /show/{slug}
    //   4. Episode : /episode/{slug}/season-N-episode-N
    //   5. Player iframe → lecteur1.xtremestream.xyz/player/index.php?...
    //   6. MoiflixExtractor (WebView) intercepte le m3u8
    //
    // Avantages : audio FR garanti (testé), pas de CF challenge, pas de
    // login, search JSON propre. Le bouton "Continuer" sur la page episode
    // est auto-cliqué par MoiflixExtractor.
    private const val MOIFLIX_BASE = "https://moiflix.fans/" // 2026-06-21 : .dad redirige vers .fans (chaîne : .click → .dad → .fans)

    /** Cherche un titre sur moiflix via l'AJAX search public.
     *  Retourne le path /movie/{slug} ou /show/{slug} du meilleur match, ou null. */
    private suspend fun searchMoiflix(
        title: String,
        year: Int? = null,
        preferType: String = "Film",
    ): String? {
        if (title.isBlank()) return null
        // 2026-05-05 : pareil que Yflix : Moiflix foire sur les apostrophes typographiques.
        val cleanTitle = com.streamflixreborn.streamflix.utils.TitleNormalizer.stripUnicodeArtifacts(title)
        return try {
            val url = "${MOIFLIX_BASE}ajax/posts?q=" +
                java.net.URLEncoder.encode(cleanTitle, "UTF-8")
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", MOIFLIX_BASE)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build()
            val client = Extractor.sharedClient.newBuilder()
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val body = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.body?.string() }
            } ?: return null
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            val items = json.getAsJsonArray("data") ?: return null
            if (items.size() == 0) {
                Log.d("MovixProvider", "Moiflix search '$title' : no results")
                return null
            }
            // 2026-05-05 v2 : matching strict pour éviter les faux positifs.
            // Avant : "The Boys" pouvait matcher "The Boyfriend" via substring
            // (score 50). Maintenant on exige soit un match exact, soit un
            // match WORD-FOR-WORD (tous les mots du target présents dans le
            // candidat + différence de longueur ≤ 30%).
            val targetTitleNorm = title.lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val targetWords = targetTitleNorm.split(" ").filter { it.length > 1 }.toSet()
            var bestUrl: String? = null
            var bestScore = -1
            var bestName: String? = null
            for (i in 0 until items.size()) {
                val item = items[i].asJsonObject
                val itemName = item.get("name")?.asString ?: continue
                val itemUrl = item.get("url")?.asString ?: continue
                val itemType = item.get("type")?.asString ?: ""
                val nNorm = itemName.lowercase()
                    .replace(Regex("[^a-z0-9 ]"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                val candidateWords = nNorm.split(" ").filter { it.length > 1 }.toSet()
                var score = 0
                when {
                    // 1. Match exact (insensible à la casse/ponctuation)
                    nNorm == targetTitleNorm -> score = 100
                    // 2. Tous les mots du target sont dans le candidat ET
                    //    longueur similaire (≤ 30% de différence) → fort match
                    targetWords.isNotEmpty() && candidateWords.containsAll(targetWords) &&
                        kotlin.math.abs(nNorm.length - targetTitleNorm.length).toDouble() /
                        kotlin.math.max(nNorm.length, targetTitleNorm.length) <= 0.30 -> score = 90
                    // 3. Tous les mots du candidat dans target ET longueur similaire
                    candidateWords.isNotEmpty() && targetWords.containsAll(candidateWords) &&
                        kotlin.math.abs(nNorm.length - targetTitleNorm.length).toDouble() /
                        kotlin.math.max(nNorm.length, targetTitleNorm.length) <= 0.30 -> score = 80
                    // Sinon : pas un match valide. Pas de fallback substring.
                    else -> score = 0
                }
                if (itemType == preferType) score += 20
                if (score > bestScore) {
                    bestScore = score
                    bestUrl = itemUrl
                    bestName = itemName
                }
            }
            // Seuil 90 (au lieu de 50) — on exige un VRAI match.
            if (bestUrl == null || bestScore < 90) {
                Log.d("MovixProvider", "Moiflix '$title' : pas de match fiable (best='$bestName' score=$bestScore), skip")
                return null
            }
            Log.d("MovixProvider", "Moiflix '$title' → '$bestName' $bestUrl (score=$bestScore)")
            bestUrl
        } catch (e: Exception) {
            Log.w("MovixProvider", "Moiflix search '$title' failed: ${e.message}")
            null
        }
    }

    /** Construit l'URL complète moiflix pour un movie ou episode.
     *  movie : /movie/{slug}
     *  episode : /episode/{slug}/season-N-episode-N (slug récupéré depuis /show/{slug}) */
    private fun buildMoiflixServer(matchUrl: String, season: Int? = null, episode: Int? = null): Video.Server {
        val finalUrl = if (season != null && episode != null) {
            // matchUrl = "/show/{slug}" → on transforme en "/episode/{slug}/season-N-episode-N"
            val slug = matchUrl.substringAfterLast("/")
            "${MOIFLIX_BASE.trimEnd('/')}/episode/$slug/season-$season-episode-$episode"
        } else {
            "${MOIFLIX_BASE.trimEnd('/')}$matchUrl"
        }
        return Video.Server(
            id = "moiflix_${matchUrl.substringAfterLast("/")}",
            name = "Moiflix",
            src = finalUrl,
        )
    }

    private val genreNames = mapOf(
        "28" to "Action", "12" to "Aventure", "16" to "Animation",
        "35" to "Comédie", "80" to "Crime", "99" to "Documentaire",
        "18" to "Drame", "10751" to "Famille", "14" to "Fantaisie",
        "27" to "Horreur", "9648" to "Mystère", "10749" to "Romance",
        "878" to "Science-Fiction", "53" to "Thriller", "10752" to "Guerre",
        "37" to "Western"
    )

    // ==================== DATA CLASSES ====================

    // --- Movix API responses ---

    /**
     * 2026-05-03 : l'API Movix /api/search retourne {"results":[...], ...} pas
     * une liste directe. Le retour Retrofit était typé List<MovixSearchItem>
     * → "Expected BEGIN_ARRAY but was BEGIN_OBJECT" au root → searchMovix
     * throw → seriesDlDeferred toujours vide → on perd les m3u8 Darkibox HLS
     * que Movix sert via api/series/download/{movixId}/season/X/episode/Y.
     */
    data class MovixSearchResponse(
        val results: List<MovixSearchItem>?
    )

    data class MovixSearchItem(
        val id: Int?,
        val name: String?,
        val type: String?,
        val tmdb_id: Int?,
        val poster: String?,
        val backdrop: String?,
        val description: String?,
        val release_date: String?,
        val imdb_id: String?
    )

    data class FstreamPlayer(
        val url: String?,
        val type: String?,
        val quality: String?,
        val player: String?
    )

    data class FstreamMovieResponse(
        val success: Boolean?,
        val players: Map<String, List<FstreamPlayer>>?,
        val error: String?,
        val message: String?
    )

    // 2026-07-16 : SwiftFlow — nouvel endpoint Movix `api/swiftflow/movie/{tmdbId}`
    //   (api.movix.show / api.movix.date). L'app ne l'appelait PAS → le serveur SwiftFlow
    //   n'apparaissait jamais (user « il n'apparaît même pas dans les serveurs »).
    //   Structure : players.{vf,vostfr}[] = {name, url (iframe swiftflow.lol/api/v1/index.php
    //   ?route=movies/{id}/player&api_key=…), type:"iframe", label (taille ex "1005.2 MB")}.
    //   Le player swiftflow.lol sert un mp4 direct citron-edge.lol (ad-gate → cheksum sign) →
    //   joué via SwiftFlowExtractor (overlay WebView). FILMS UNIQUEMENT (TV = « partenaires »,
    //   403 sur swiftflow.lol). Confirmé en direct dans Chrome sur L'Odyssée (tmdb 1368337).
    data class SwiftflowPlayer(
        val name: String?,
        val url: String?,
        val type: String?,
        val label: String?
    )

    data class SwiftflowMovieResponse(
        val success: Boolean?,
        val players: Map<String, List<SwiftflowPlayer>>?,
        val error: String?
    )

    // 2026-07-25 : nouvelles sources Movix (vues sur movix.show) que l'app n'intégrait pas.
    //   j1f (1Jour1Film via Movix) : players.{vf,vostfr}[] = {name,url,type,label,source}.
    data class J1fPlayer(
        val name: String?,
        val url: String?,
        val type: String?,
        val label: String?,
        val source: String?,
    )
    data class J1fMovieResponse(
        val success: Boolean?,
        val players: Map<String, List<J1fPlayer>>?,
        /** 2026-08-01 : fiche 1jour1film réellement utilisée — son slug porte l'ANNÉE
         *  (ex. `/films/les-specialistes-vf-1985/`), seul moyen de détecter que l'API a
         *  confondu deux films homonymes. */
        @com.google.gson.annotations.SerializedName("j1f_url")
        val j1fUrl: String? = null,
    )
    //   purstream : sources[] = {url,name,format} (source directe, pas de langue).
    data class PurstreamSource(
        val url: String?,
        val name: String?,
        val format: String?,
    )
    data class PurstreamMovieResponse(
        val purstream_id: String?,
        val sources: List<PurstreamSource>?,
    )

    data class FstreamTvEpisode(
        val number: Int?,
        val title: String?,
        val languages: Map<String, List<FstreamPlayer>>?
    )

    data class FstreamTvResponse(
        val success: Boolean?,
        val episodes: Map<String, FstreamTvEpisode>?
    )

    data class LinksMovieData(
        val id: String?,
        // 2026-07-09 : l'API Movix a changé — `links` mélange désormais des chaînes ET des
        //   objets (ex {"url":"…","quality":"…"}). List<String> throw sur les objets → tout
        //   l'endpoint perdu. On parse en JsonElement et on extrait l'URL via movixLinkUrl().
        val links: List<com.google.gson.JsonElement>?
    )

    data class LinksMovieResponse(
        val success: Boolean?,
        val data: LinksMovieData?,
        val error: String?,
        val message: String?
    )

    data class LinksTvItem(
        val series_id: String?,
        val season_number: Int?,
        val episode_number: Int?,
        val links: List<com.google.gson.JsonElement>?
    )

    data class LinksTvResponse(
        val success: Boolean?,
        val data: List<LinksTvItem>?
    )

    data class WiflixEpisodeSource(
        val name: String?,
        val url: String?,
        val episode: Int?,
        val type: String?
    )

    data class WiflixTvResponse(
        val success: Boolean?,
        val episodes: Map<String, Map<String, List<WiflixEpisodeSource>>>?
    )

    data class WiflixMovieResponse(
        val success: Boolean?,
        val players: Map<String, List<WiflixEpisodeSource>>?,
        val error: String?,
        val message: String?
    )

    // --- Cpasmal API responses ---

    data class CpasmalLink(
        val server: String?,
        val url: String?
    )

    // ⚠ 2026-08-20 : `cpasmalUrl` est le SEUL champ qui trahit une mauvaise œuvre.
    //   `title` et `year` sont recopiés de TMDB par l'API movix — ils décrivent ce
    //   qu'on a DEMANDÉ, jamais ce qui est SERVI. Voir slugCpasmalCorrespond().
    data class CpasmalResponse(
        val title: String?,
        val year: String?,
        val cpasmalUrl: String?,
        val links: Map<String, List<CpasmalLink>>?
    )

    data class CpasmalMovieResponse(
        val title: String?,
        val year: String?,
        val cpasmalUrl: String?,
        val links: Map<String, List<CpasmalLink>>?
    )

    // --- Series Download API responses ---

    data class SeriesDownloadSource(
        val src: String?,
        val language: String?,
        val quality: String?,
        val m3u8: String?
    )

    data class SeriesDownloadResponse(
        val sources: List<SeriesDownloadSource>?
    )

    // --- TMDB Movix API responses ---

    data class TmdbMovixPlayerLink(
        val decoded_url: String?,
        val quality: String?,
        val language: String?
    )

    data class TmdbMovixEpisode(
        val season_number: Int?,
        val episode_number: Int?,
        val title: String?,
        val iframe_src: String?,
        val player_links: List<TmdbMovixPlayerLink>?
    )

    /**
     * Fiche de l'œuvre telle que Movix la RENVOIE pour l'id qu'on lui a demandé.
     * 2026-08-11 : ce bloc existait dans la réponse depuis toujours ; Gson le jetait
     *   silencieusement faute de champ correspondant. C'est lui qui permet de confondre
     *   Movix quand il attache l'épisode d'une AUTRE série — voir [titreOeuvreCorrespond].
     */
    data class TmdbMovixDetails(
        val id: Int?,
        val title: String?,
        val original_title: String?,
        val release_date: String?
    )

    data class TmdbMovixTvResponse(
        val tmdb_details: TmdbMovixDetails?,
        val current_episode: TmdbMovixEpisode?,
        val seasons: List<Any>?
    )

    data class TmdbMovixMovieResponse(
        val iframe_src: String?,
        val player_links: List<TmdbMovixPlayerLink>?
    )

    // --- TMDB API responses ---

    data class TmdbMovieResult(
        val id: Int,
        val title: String?,
        val overview: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val release_date: String?,
        val vote_average: Double?,
        val runtime: Int?,
        val imdb_id: String?,
        val genres: List<TmdbGenre>?,
        val credits: TmdbCredits?
    )

    data class TmdbTvResult(
        val id: Int,
        val name: String?,
        val overview: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val first_air_date: String?,
        val vote_average: Double?,
        val number_of_seasons: Int?,
        val seasons: List<TmdbSeason>?,
        val genres: List<TmdbGenre>?,
        val credits: TmdbCredits?,
        val external_ids: TmdbExternalIds?
    )

    data class TmdbSeason(
        val id: Int?,
        val season_number: Int?,
        val name: String?,
        val poster_path: String?,
        val episode_count: Int?,
        val air_date: String?
    )

    data class TmdbSeasonDetail(
        val id: Int?,
        val season_number: Int?,
        val name: String?,
        val episodes: List<TmdbEpisode>?
    )

    data class TmdbEpisode(
        val id: Int?,
        val episode_number: Int?,
        val name: String?,
        val overview: String?,
        val still_path: String?,
        val air_date: String?
    )

    data class TmdbGenre(
        val id: Int?,
        val name: String?
    )

    data class TmdbCredits(
        val cast: List<TmdbCast>?,
        val crew: List<TmdbCrew>?
    )

    data class TmdbCast(
        val id: Int?,
        val name: String?,
        val profile_path: String?,
        val character: String?
    )

    data class TmdbCrew(
        val id: Int?,
        val name: String?,
        val profile_path: String?,
        val job: String?
    )

    data class TmdbExternalIds(
        val imdb_id: String?
    )

    data class TmdbPageResult<T>(
        val page: Int?,
        val results: List<T>?,
        val total_pages: Int?
    )

    data class TmdbMovieListItem(
        val id: Int,
        val title: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val release_date: String?,
        val vote_average: Double?,
        val overview: String?,
        val genre_ids: List<Int>? = null
    )

    data class TmdbTvListItem(
        val id: Int,
        val name: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val first_air_date: String?,
        val vote_average: Double?,
        val overview: String?,
        val genre_ids: List<Int>? = null
    )

    data class TmdbTrendingItem(
        val id: Int,
        val title: String?,
        val name: String?,
        val media_type: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val release_date: String?,
        val first_air_date: String?,
        val vote_average: Double?,
        val overview: String?,
        val genre_ids: List<Int>? = null
    )

    // 2026-07-11 — collections TMDB (« Les sagas incontournables » sur movix.chat)
    data class TmdbCollectionResult(
        val id: Int,
        val name: String?,
        val parts: List<TmdbMovieListItem>?
    )

    // ==================== INITIALIZATION ====================

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            // v90 2026-05-27 : purger le cache si l'ancien domaine mort est encore présent
            val cachedUrl = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            if (cachedUrl.contains("movix.tax") || cachedUrl.contains("movix.cash") || cachedUrl.contains("movix.health")) {
                Log.w("MovixProvider", "Purging stale cached domain: $cachedUrl")
                UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, "")
                UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL, "")
            }
            if (forceRefresh || UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_AUTOUPDATE) != "false") {
                try {
                    val activeDomain = fetchActiveDomain()
                    if (!activeDomain.isNullOrEmpty()) {
                        val portalBase = if (activeDomain.endsWith("/")) activeDomain else "$activeDomain/"
                        val apiBase = portalBase.replace("://", "://api.")

                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_URL, apiBase)
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_PORTAL_URL, portalBase)
                        UserPreferences.setProviderCache(this, UserPreferences.PROVIDER_LOGO, "${portalBase}movix.png")
                        Log.d("MovixProvider", "Auto-update: active domain -> $portalBase (API: $apiBase)")
                    }
                } catch (e: Exception) {
                    Log.e("MovixProvider", "Auto-update failed: ${e.message}")
                }
            }
            movixServiceInstance = buildMovixService()
            serviceInitialized = true
        }
        return baseUrl
    }

    /**
     * Fetches the active Movix domain from movix.health.
     *
     * movix.health is a React SPA that embeds domain data in its JS bundle.
     * The domains are stored as an array of objects with properties:
     *   id, label, url, blocked (boolean), blockedReason (optional)
     *
     * Strategy:
     * 1. Fetch the HTML page to find the JS bundle filename
     * 2. Fetch the JS bundle
     * 3. Regex for the first domain entry with blocked:!1 (= not blocked)
     */
    private fun fetchActiveDomain(): String? {
        val client = OkHttpClient.Builder()
            .readTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()

        // Step 1: Fetch the HTML to find the JS bundle path
        val htmlRequest = Request.Builder()
            .url(AUTO_UPDATE_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val html = client.newCall(htmlRequest).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }

        // v89 2026-05-20 : parser ROBUSTE depuis le <title> (ex: "Movix ... | movix.tax").
        //   Le bundle JS change de format souvent (ancien pattern blocked:!1 mort) ;
        //   le <title> de la page d'adresses contient le domaine actif de maniere stable.
        val titleBlock = Regex("""<title>([^<]*)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        if (titleBlock != null) {
            val titleDomain = Regex("""movix\.[a-z]{2,}""").findAll(titleBlock).lastOrNull()?.value
            if (!titleDomain.isNullOrBlank()) {
                Log.d("MovixProvider", "Active domain from <title>: $titleDomain")
                return "https://$titleDomain"
            }
        }

        // Extract JS bundle path: <script ... src="/assets/index-XXXX.js">
        val jsPath = Regex("""src="(/assets/index-[^"]+\.js)"""")
            .find(html)?.groupValues?.get(1)
            ?: return null

        val jsUrl = AUTO_UPDATE_URL.trimEnd('/') + jsPath

        // Step 2: Fetch the JS bundle
        val jsRequest = Request.Builder()
            .url(jsUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val jsContent = client.newCall(jsRequest).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }

        // Step 3: Find the first domain with blocked:!1 (not blocked)
        // Format: url:'https://movix.XXXX',blocked:!1
        val activeDomainUrl = Regex("""url:'(https://movix\.[a-z]+)',blocked:!1""")
            .find(jsContent)?.groupValues?.get(1)

        return activeDomainUrl
    }

    private suspend fun initializeService() {
        initializationMutex.withLock {
            if (serviceInitialized) return
            onChangeUrl()
        }
    }

    // ==================== PROVIDER METHODS ====================

    /** 2026-05-21 — langue d'origine TMDB selon le filtre catalogue choisi par
     *  l'utilisateur (item « Filtre »). null = pas de filtre (Monde). */
    private fun movixCatalogOriginalLanguage(): String? =
        com.streamflixreborn.streamflix.utils.CatalogFilter.originalLanguage(name)

    // 2026-07-29 (user « Movix donne des films pas encore sortis → aucun serveur », ex
    //   « Spider-Man: Brand New Day » 2026) : un film/série n'est gardé dans le catalogue que si sa
    //   date de sortie est passée (≤ aujourd'hui). Comparaison lexicographique de dates ISO
    //   (yyyy-MM-dd) = ordre chronologique, sans dépendre d'une API de date récente. Date absente =
    //   gardé (vieux titres parfois sans date ; les non-sortis ont TOUJOURS une date future).
    private fun isReleased(date: String?): Boolean {
        if (date.isNullOrBlank()) return true
        val d = date.trim().take(10)
        if (d.length < 10) return true
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        return d <= today
    }

    // Surcharge pour les modèles ONYX (Movie.released / TvShow.released = Calendar?).
    private fun isReleased(cal: java.util.Calendar?): Boolean {
        if (cal == null) return true
        return !cal.after(java.util.Calendar.getInstance())
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DISPONIBILITÉ — mêmes règles pour l'accueil ET les onglets Films/Séries
    // ════════════════════════════════════════════════════════════════════════
    /**
     * 2026-08-04 (user : « le dernier filtre que tu viens de mettre, est-ce que tu peux
     * l'ajouter à l'onglet quand on va dans Films, pour éviter la même chose que sur le
     * home ? »).
     *
     * Les onglets Films et Séries ne vérifiaient que la date du jour : un film sorti la
     * veille, ou encore à l'affiche, ou jamais distribué en France, y figurait toujours.
     * On y applique désormais les trois mêmes règles que l'accueil.
     */
    private const val DELAI_SOURCES_JOURS = 7

    /**
     * Onglet de catalogue : nombre de pages TMDB agrégées à chaque chargement.
     * Le filtre de notoriété et les règles de disponibilité retirent une bonne part de
     * chaque page ; en agréger trois rend au chargement le volume d'une page pleine.
     */
    private const val PAGES_TMDB_PAR_CHARGEMENT = 3

    /** Seuil de notoriété de l'onglet — indissociable du tri par date (cf. getMovies). */
    private const val VOTES_MINIMUM_ONGLET = 15

    /** Vrai si la date est antérieure d'au moins [jours] jours à aujourd'hui. */
    private fun sortiDepuis(date: String?, jours: Int): Boolean {
        if (date.isNullOrBlank()) return true
        val limite = java.util.Calendar.getInstance()
            .apply { add(java.util.Calendar.DAY_OF_YEAR, -jours) }.time
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .parse(date.trim().take(10))?.before(limite) ?: true
        }.getOrDefault(true)
    }

    override suspend fun getHome(): List<Category> {
        initializeService()
        val categories = mutableListOf<Category>()
        // 2026-08-04 (user : « la catégorie Les sagas incontournables, tu la descends tout en
        //   bas de la liste, c'est tout le temps des vieux films là-dedans ») — la rangée est
        //   calculée à sa place habituelle mais ajoutée EN DERNIER. Double effet : elle sort du
        //   haut de l'accueil, et elle cesse de « consommer » les films avant les rangées
        //   fraîches (l'anti-doublon plus bas sert les rangées dans leur ordre d'ajout, or les
        //   sagas raflaient les blockbusters que Films populaires aurait pu montrer).
        var sagaRow: List<com.streamflixreborn.streamflix.models.Movie> = emptyList()

        try {
            // 2026-07-11 (user « prends la copie EXACTE du home de movix.chat » +
            //   « l'interface se renouvelle n'importe comment à chaque refresh, enlève ça ») :
            //   home = RÉPLIQUE FIDÈLE de movix.chat, DÉTERMINISTE (aucune page random),
            //   SANS filtre langue (movix.chat n'en met pas → mêmes jaquettes). Ordre + requêtes
            //   TMDB identiques : Tendances jour → Tendances semaine → Sagas incontournables
            //   (collections) → Films populaires → Séries récentes → Films récents → genres.
            // Filtre catalogue (bouton « Filtrer le catalogue »). null = Monde → réplique
            //   EXACTE movix.chat (aucun param langue). Sinon on filtre les rangées discover.
            val langFilter = movixCatalogOriginalLanguage()
            // 2026-07-11 (user « enlève dessins animés / manga, on a déjà des providers anime »):
            //   on exclut le genre TMDB Animation (16) partout SAUF dans les Sagas (respectées).
            //   Côté discover = param serveur without_genres ; côté trending/populaires = filtre
            //   client sur genre_ids.
            val ANIM_GENRE = 16
            fun notAnim(ids: List<Int>?): Boolean = ids?.contains(ANIM_GENRE) != true

            // ════════════════════════════════════════════════════════════════
            // 2026-08-04 (user : « Movix affiche encore des trucs qui ne sont même pas
            //   encore sortis ») — FILTRE DE DISPONIBILITÉ sur l'accueil.
            //
            //   L'accueil réplique movix.chat, qui met en avant des titres à venir : la fiche
            //   s'ouvre, aucun serveur n'existe, et l'app n'a aucun moyen de le savoir avant
            //   d'avoir interrogé les vingt sources.
            //
            //   Même règle que les catégories Nouveautés/plateformes : on écarte ce qui n'est
            //   pas sorti, ET ce qui l'est depuis moins d'une semaine — le temps que les
            //   hébergeurs publient.
            //
            //   Date absente ou illisible → on GARDE. Un format inattendu ne doit pas vider
            //   l'accueil ; on ne filtre que ce dont on est certain.
            // ════════════════════════════════════════════════════════════════
            val limiteSortie = java.util.Calendar.getInstance()
                .apply { add(java.util.Calendar.DAY_OF_YEAR, -7) }.time
            val formatTmdb = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            fun dejaSorti(date: String?): Boolean {
                if (date.isNullOrBlank()) return true
                return runCatching { formatTmdb.parse(date)?.before(limiteSortie) ?: true }
                    .getOrDefault(true)
            }
            // 2026-08-04 (user : « si le film est du 3 juillet et qu'il sort au cinéma, il peut
            //   pas être déjà là ») — DEUXIÈME ÉTAGE : la semaine ci-dessus suffit pour une
            //   sortie plateforme, pas pour une sortie en SALLES, qui n'a aucune copie correcte
            //   avant l'ouverture de la fenêtre vidéo. Les rangées « tendances » et
            //   « populaires » ne portent aucun type de sortie, donc on ne peut pas trancher
            //   film par film : on écarte la liste des films encore à l'affiche en France.
            //   Réseau indisponible → liste vide → aucun écartement, l'accueil reste garni.
            val vod = com.streamflixreborn.streamflix.utils.VodCategories
            val enSalles = vod.enSalles()
            val sortiesFr = vod.sortiesFrancaises()

            // TROISIÈME ÉTAGE — un film RÉCENT jamais distribué en France n'aura jamais de
            //   source française. C'est le cas de `The Odyssey` de Marcel Walz, homonyme du
            //   film de Nolan, que TMDB classe parmi ses films populaires : aucune date de
            //   sortie française, donc invisible pour la liste des salles.
            //   Hors de la fenêtre récente on ne filtre plus — les dates des vieux catalogues
            //   sont trop lacunaires pour qu'une absence signifie quoi que ce soit.
            fun distribueEnFrance(id: Int, date: String?): Boolean =
                !vod.dansLaFenetreFrancaise(date) || id.toString() in sortiesFr

            fun dispo(id: Int, release: String?, firstAir: String?): Boolean {
                val date = release ?: firstAir
                if (!dejaSorti(date)) return false
                if (id.toString() in enSalles) return false
                // Les séries ne passent pas par les sorties cinéma : on ne leur applique que
                //   les deux premiers étages (`release` nul = série).
                return release == null || distribueEnFrance(id, date)
            }
            fun dispoFilm(id: Int, release: String?): Boolean =
                dejaSorti(release) && id.toString() !in enSalles &&
                    distribueEnFrance(id, release)

            // 2026-08-04 (user : « la catégorie films populaires est très pauvre une fois qu'il
            //   n'y aura plus les films de cinéma ; essaye de compléter sans avoir de doublon »).
            //   Les rangées ne piochaient que dans DEUX pages ; en retirant les films encore à
            //   l'affiche il ne restait plus grand-chose. On pagine désormais JUSQU'À atteindre
            //   le compte voulu, en s'arrêtant dès qu'il est atteint (donc sans requête inutile
            //   quand la première page suffit). Le dédoublonnage se fait sur l'identifiant TMDB,
            //   et la première occurrence l'emporte — l'ordre de popularité est préservé.
            suspend fun <T> completer(
                voulu: Int,
                pageMax: Int,
                cle: (T) -> Int,
                garde: (T) -> Boolean,
                page: suspend (Int) -> List<T>,
            ): List<T> {
                val retenus = LinkedHashMap<Int, T>()
                for (p in 1..pageMax) {
                    val lot = runCatching { page(p) }.getOrNull().orEmpty()
                    if (lot.isEmpty()) break
                    for (item in lot) {
                        if (!garde(item)) continue
                        val k = cle(item)
                        if (!retenus.containsKey(k)) retenus[k] = item
                    }
                    if (retenus.size >= voulu) break
                }
                return retenus.values.toList()
            }
            fun mv(item: TmdbMovieListItem) = Movie(
                id = item.id.toString(),
                title = item.title ?: "",
                poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                rating = item.vote_average,
                overview = item.overview,
                released = item.release_date,
            )
            fun tv(item: TmdbTvListItem) = TvShow(
                id = item.id.toString(),
                title = item.name ?: "",
                poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                rating = item.vote_average,
                overview = item.overview,
                released = item.first_air_date,
            )

            // 2026-07-11 (user « sur Movix le bouton filtre ne produit aucun changement ») :
            //   le trending TMDB n'est PAS filtrable par langue → le haut du home (Featured,
            //   Tendances, Populaires) restait identique quel que soit le filtre. FIX : si une
            //   langue est choisie (langFilter != null), on construit TOUT le haut via discover
            //   filtré par langue (donc le bouton change visiblement le catalogue). Si « Monde »
            //   (null) → réplique EXACTE movix.chat (trending), comportement historique inchangé.
            if (langFilter == null) {
                // ===== MONDE — réplique movix.chat (trending, non filtrable par langue) =====
                val trending = tmdbService.getTrending(apiKey = TMDB_API_KEY)
                fun trendItem(item: TmdbTrendingItem): AppAdapter.Item? = when (item.media_type) {
                    "movie" -> Movie(
                        id = item.id.toString(),
                        title = item.title ?: item.name ?: "",
                        poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                        banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                        rating = item.vote_average, overview = item.overview, released = item.release_date,
                    )
                    "tv" -> TvShow(
                        id = item.id.toString(),
                        title = item.name ?: item.title ?: "",
                        poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                        banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                        rating = item.vote_average, overview = item.overview, released = item.first_air_date,
                    )
                    else -> null
                }
                // FEATURED (carrousel) et « Tendances du jour » = INSTANCES DISTINCTES (itemType
                //   mutable par instance ; partager = crash ViewPager2 « Pages must fill... »).
                // 2026-08-04 (user : « on a Tendances du jour et Tendances, est-ce que ça vaut
                //   le coup d'en avoir deux ? ») — NON, mesuré : sur cinq pages de chaque,
                //   93 titres d'un côté, 93 de l'autre, 69 EN COMMUN — 74 % de recouvrement.
                //   Deux rangées qui se répètent aux trois quarts n'apportent rien ; réunies
                //   et dédoublonnées elles donnent 117 titres, soit une rangée bien garnie.
                //   Le jour passe en premier (l'actualité prime), la semaine complète derrière.
                val trendingRaw = completer(
                    voulu = 60, pageMax = 10, cle = { it.id },
                    garde = { notAnim(it.genre_ids) && dispo(it.id, it.release_date, it.first_air_date) },
                    page = { p ->
                        when {
                            p == 1 -> trending.results ?: emptyList()
                            p <= 5 -> tmdbService.getTrending(apiKey = TMDB_API_KEY, page = p)
                                .results ?: emptyList()
                            else -> tmdbService.getTrendingWeek(apiKey = TMDB_API_KEY, page = p - 5)
                                .results ?: emptyList()
                        }
                    },
                )
                // FEATURED (carrousel) et « Tendances » = INSTANCES DISTINCTES (itemType
                //   mutable par instance ; partager = crash ViewPager2 « Pages must fill... »).
                val featuredList = trendingRaw.take(10).mapNotNull { trendItem(it) }
                val tendances = trendingRaw.mapNotNull { trendItem(it) }
                if (featuredList.isNotEmpty()) {
                    categories.add(Category(name = Category.FEATURED, list = featuredList))
                    categories.add(Category(name = "Tendances", list = tendances))
                }

                // Les sagas incontournables (collections TMDB — franchises = language-agnostic,
                //   donc uniquement en vue Monde ; en vue langue elles n'auraient pas de sens).
                val sagas = listOf(
                    "Star Wars" to 10, "Harry Potter" to 1241, "Le Seigneur des Anneaux" to 119,
                    "Avengers" to 86311, "Spider-Man" to 556, "Fast & Furious" to 9485,
                    "Matrix" to 2344, "Alien" to 8091, "X-Men" to 748,
                    "Iron Man" to 131292, "Thor" to 131296, "Captain America" to 131295,
                    "Le Hobbit" to 121938, "Pirates des Caraïbes" to 295,
                    "Mission: Impossible" to 87359, "Die Hard" to 1570,
                    "Terminator" to 528, "L'Arme Fatale" to 945,
                )
                val sagaFilms = coroutineScope {
                    sagas.map { (_, sId) ->
                        async {
                            runCatching {
                                tmdbService.getCollection(id = sId, apiKey = TMDB_API_KEY).parts
                                    ?.filter { !it.poster_path.isNullOrBlank() && !it.title.isNullOrBlank() }
                                    ?.sortedByDescending { it.vote_average ?: 0.0 }
                                    ?.take(3)
                                    ?.map { mv(it) }
                                    ?: emptyList()
                            }.getOrNull().orEmpty()
                        }
                    }.awaitAll()
                }
                // Ajoutée tout en bas, après l'assemblage (cf. déclaration de `sagaRow`).
                sagaRow = sagaFilms.flatten().distinctBy { it.id }

                // Films populaires — pagination jusqu'à 20 retenus (cf. `completer`).
                runCatching {
                    val items = completer(
                        voulu = 40, pageMax = 10, cle = { it.id },
                        garde = { notAnim(it.genre_ids) && dispoFilm(it.id, it.release_date) },
                        page = { p ->
                            tmdbService.getPopularMovies(apiKey = TMDB_API_KEY, page = p).results
                                ?: emptyList()
                        },
                    ).map { mv(it) }
                    if (items.isNotEmpty()) categories.add(Category(name = "Films populaires", list = items))
                }
            } else {
                // ===== FILTRE LANGUE — haut du home via discover filtré (le bouton agit) =====
                suspend fun discMoviePage(p: Int) = tmdbService.discoverMovies(
                    apiKey = TMDB_API_KEY, page = p, sortBy = "popularity.desc",
                    withOriginalLanguage = langFilter, withoutGenres = "16",
                ).results ?: emptyList()
                val popMovies = completer(
                    voulu = 40, pageMax = 10, cle = { it.id },
                    garde = { notAnim(it.genre_ids) && dispoFilm(it.id, it.release_date) },
                    page = { p -> discMoviePage(p) },
                )
                if (popMovies.isNotEmpty()) {
                    // Featured + Populaires = mv() crée des instances NEUVES à chaque map → pas de
                    //   partage d'instance (donc pas de crash ViewPager2).
                    categories.add(Category(name = Category.FEATURED, list = popMovies.take(10).map { mv(it) }))
                    categories.add(Category(name = "Films populaires", list = popMovies.take(20).map { mv(it) }))
                }
                suspend fun discTvPage(p: Int) = tmdbService.discoverTvShows(
                    apiKey = TMDB_API_KEY, page = p, sortBy = "popularity.desc",
                    withOriginalLanguage = langFilter, withoutGenres = "16",
                ).results ?: emptyList()
                val popTv = completer(
                    voulu = 40, pageMax = 10, cle = { it.id },
                    garde = { notAnim(it.genre_ids) && dejaSorti(it.first_air_date) },
                    page = { p -> discTvPage(p) },
                )
                if (popTv.isNotEmpty()) {
                    categories.add(Category(name = "Séries populaires", list = popTv.map { tv(it) }))
                }
            }

            // Fenêtre « récents » : 18 derniers mois, déterministe (borne = aujourd'hui)
            val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val todayStr = dateFmt.format(java.util.Date())
            val sinceStr = dateFmt.format(java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.MONTH, -18)
            }.time)

            // Séries récentes (2 pages → reste rempli après retrait des doublons)
            runCatching {
                suspend fun recTvPage(p: Int) = tmdbService.discoverTvShows(
                    apiKey = TMDB_API_KEY, page = p, sortBy = "first_air_date.desc",
                    voteCountGte = 30, firstAirDateGte = sinceStr, firstAirDateLte = todayStr,
                    withOriginalLanguage = langFilter, withoutGenres = "16",
                ).results ?: emptyList()
                val items = completer(
                    voulu = 60, pageMax = 8, cle = { it.id },
                    garde = { dejaSorti(it.first_air_date) },
                    page = { p -> recTvPage(p) },
                ).map { tv(it) }
                if (items.isNotEmpty()) categories.add(Category(name = "Séries récentes", list = items))
            }

            // Films récents (2 pages)
            runCatching {
                suspend fun recMoviePage(p: Int) = tmdbService.discoverMovies(
                    apiKey = TMDB_API_KEY, page = p, sortBy = "primary_release_date.desc",
                    voteCountGte = 30, primaryReleaseDateGte = sinceStr, primaryReleaseDateLte = todayStr,
                    withOriginalLanguage = langFilter, withoutGenres = "16",
                ).results ?: emptyList()
                val items = completer(
                    voulu = 60, pageMax = 8, cle = { it.id },
                    garde = {
                        !it.title.isNullOrBlank() && dispoFilm(it.id, it.release_date)
                    },
                    page = { p -> recMoviePage(p) },
                ).map { mv(it) }
                if (items.isNotEmpty()) categories.add(Category(name = "Films récents", list = items))
            }

            // Rangées par genre — discover/movie?with_genres=X&sort_by=popularity.desc&page=1
            //   ordre movix.chat, PAGE 1 déterministe. langFilter = null (Monde) → jaquettes
            //   identiques à movix.chat ; sinon filtrées par la langue choisie au bouton.
            val genreRows = listOf(
                "Aventure" to "12", "Fantastique" to "14", "Drame" to "18",
                "Action" to "28", "Comédie" to "35", "Thriller" to "53", "Crime" to "80",
                "Science-Fiction" to "878", "Horreur" to "27",
            )
            val genreCats = coroutineScope {
                genreRows.map { (gName, gId) ->
                    async {
                        runCatching {
                            // 2026-08-04 (capture user : la rangée Science-Fiction n'affichait
                            //   plus QU'UNE seule jaquette) — deux pages ne suffisaient plus.
                            //   L'anti-doublon global sert les rangées dans leur ordre d'ajout
                            //   et les genres passent en DERNIER : tous les blockbusters du
                            //   genre avaient déjà été consommés par Tendances, Populaires et
                            //   Récents. On pioche donc large — jusqu'à soixante candidats —
                            //   pour qu'il en reste après filtrage.
                            //   Au passage les genres subissent enfin les mêmes règles de
                            //   disponibilité que le reste (sorti, hors salles, distribué en
                            //   France) ; ils y échappaient complètement jusqu'ici.
                            val items = completer(
                                voulu = 60, pageMax = 8, cle = { it.id },
                                garde = {
                                    !it.title.isNullOrBlank() &&
                                        notAnim(it.genre_ids) &&
                                        dispoFilm(it.id, it.release_date)
                                },
                                page = { p ->
                                    tmdbService.discoverMovies(
                                        apiKey = TMDB_API_KEY, page = p,
                                        sortBy = "popularity.desc", withGenres = gId,
                                        withOriginalLanguage = langFilter, withoutGenres = "16",
                                    ).results ?: emptyList()
                                },
                            ).map { mv(it) }
                            if (items.isNotEmpty()) Category(name = gName, list = items) else null
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            categories.addAll(genreCats)

        } catch (e: kotlinx.coroutines.CancellationException) {
            // 2026-05-12 : ne PAS swallow CancellationException — sinon HomeViewModel
            // pense que c'est un succès et cache le résultat partiel (ex: 2 catégories
            // au lieu de 8). Propager pour que le caller skip l'écriture cache.
            Log.w("MovixProvider", "getHome cancelled — propagating")
            throw e
        } catch (e: Exception) {
            Log.e("MovixProvider", "Error loading home: ${e.message}")
        }

        // Les sagas ferment la marche (cf. déclaration de `sagaRow` en tête de getHome).
        if (sagaRow.isNotEmpty()) {
            categories.add(Category(name = "Les sagas incontournables", list = sagaRow))
        }

        // 2026-07-11 (user « un antidoublon sur les catégories ») : ANTI-DOUBLON GLOBAL.
        //   Un même film n'apparaît qu'une fois. Les rangées de RÉFÉRENCE (Tendances jour/
        //   semaine, Sagas, Récents) restent PLEINES (contenu exact des endpoints du site)
        //   mais « consomment » leurs films ; « Films populaires » + toutes les rangées de
        //   GENRE sont filtrées contre tout ce qui précède → plus de blockbuster répété
        //   dans Populaires + Action + Aventure + Tendances. FEATURED (héros) exempt.
        //   Les 2 rangées Tendances jour/semaine se recouvrent par nature (comme le site).
        run {
            fun keyOf(item: AppAdapter.Item): String? = when (item) {
                is com.streamflixreborn.streamflix.models.Movie -> "m:${item.id}"
                is com.streamflixreborn.streamflix.models.TvShow -> "s:${item.id}"
                else -> null
            }
            // Seules ces 2 rangées restent 100 % pleines : Tendances (référence trending)
            //   + Sagas (respectées). TOUT le reste (Récents, Populaires, genres) est filtré
            //   contre ce qui précède → aucune jaquette en double.
            // ⚠ 2026-08-04 : le libellé DOIT suivre le nom réel de la rangée. « Tendances du
            //   jour » et « Tendances » ont fusionné ; l'ancien nom laissé ici aurait privé la
            //   rangée de sa protection et l'aurait rabotée à quelques jaquettes.
            val keepFullButConsume = setOf(
                "Tendances", "Les sagas incontournables",
            )
            val seen = HashSet<String>()
            val deduped = mutableListOf<Category>()
            for (cat in categories) {
                when {
                    cat.name == Category.FEATURED -> deduped.add(cat) // héros : ni filtre ni consommation
                    cat.name in keepFullButConsume -> {
                        cat.list.forEach { keyOf(it)?.let(seen::add) }
                        deduped.add(cat)
                    }
                    else -> {
                        val kept = cat.list.filter { item ->
                            val key = keyOf(item) ?: return@filter true
                            seen.add(key)
                        }
                        // 2026-08-04 (user : « essaye d'avoir le maximum ») — le plafond était
                        //   à 20 ; il rabotait des rangées déjà amaigries par le filtrage.
                        if (kept.isNotEmpty()) deduped.add(cat.copy(list = kept.take(40)))
                    }
                }
            }
            // 2026-08-04 (user : « ça sert à rien d'avoir une catégorie avec 2 jaquettes ») —
            //   après le filtrage anti-doublon, certaines rangées ne gardaient qu'un ou deux
            //   titres. Une rangée trop courte ne se parcourt pas, elle occupe juste une ligne.
            //   En dessous de ce seuil on la retire plutôt que de l'afficher à moitié vide.
            //   FEATURED (le carrousel) échappe à la règle : il a sa propre logique.
            val MINIMUM_PAR_RANGEE = 6
            categories.clear()
            categories.addAll(
                deduped.filter { it.name == Category.FEATURED || it.list.size >= MINIMUM_PAR_RANGEE }
            )
        }

        // 2026-05-09 : pousser les films marqués "présumés vides" en bas
        // de chaque section. FilmHealthTracker apprend des échecs réels de
        // l'user (films qu'il a cliqués où TOUS les serveurs ont fail en
        // dead-content). TTL 7j, auto-reset au prochain succès.
        // ZERO film n'est caché — juste rétrogradé.
        val sortedByHealth = categories.map { cat ->
            val healthSorted = com.streamflixreborn.streamflix.utils.FilmHealthTracker
                .sortByHealth(name, cat.list) { item ->
                    when (item) {
                        is com.streamflixreborn.streamflix.models.Movie -> item.id
                        is com.streamflixreborn.streamflix.models.TvShow -> item.id
                        else -> ""
                    }
                }
            cat.copy(list = healthSorted)
        }

        // 2026-07-29 (user, ex « Spider-Man: Brand New Day ») : on retire de TOUTES les rangées les
        //   films/séries pas encore sortis (date future) — ils n'ont aucun serveur. Les rangées qui
        //   deviennent vides sont supprimées.
        val releasedOnly = sortedByHealth.map { cat ->
            cat.copy(
                list = cat.list.filter { item ->
                    when (item) {
                        is com.streamflixreborn.streamflix.models.Movie -> isReleased(item.released)
                        is com.streamflixreborn.streamflix.models.TvShow -> isReleased(item.released)
                        else -> true
                    }
                },
            )
        }.filter { it.list.isNotEmpty() }

        // 2026-07-11 : ordre d'insertion PRÉSERVÉ = réplique exacte de movix.chat
        //   (FEATURED déjà en tête). Plus de re-tri par nom (cassait l'ordre du site).
        return releasedOnly
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            if (page > 1) return emptyList()
            // 2026-08-04 (demande user) : plateformes + Nouveautés AVANT les genres.
            //   Logique partagée avec Cloudstream et NetMirror — cf. VodCategories.
            return com.streamflixreborn.streamflix.utils.VodCategories.enTete() + listOf(
                Genre(id = "28", name = "Action"),
                Genre(id = "12", name = "Aventure"),
                Genre(id = "16", name = "Animation"),
                Genre(id = "35", name = "Comédie"),
                Genre(id = "80", name = "Crime"),
                Genre(id = "99", name = "Documentaire"),
                Genre(id = "18", name = "Drame"),
                Genre(id = "10751", name = "Famille"),
                Genre(id = "14", name = "Fantaisie"),
                Genre(id = "27", name = "Horreur"),
                Genre(id = "k-drama", name = "K-Drama"),
                Genre(id = "9648", name = "Mystère"),
                Genre(id = "10749", name = "Romance"),
                Genre(id = "878", name = "Science-Fiction"),
                Genre(id = "53", name = "Thriller"),
                Genre(id = "10752", name = "Guerre"),
                Genre(id = "37", name = "Western")
            )
        }

        return try {
            // 2026-05-05 : normalise la query (apostrophes typo, annotations parasites)
            val cleanQuery = com.streamflixreborn.streamflix.utils.TitleNormalizer
                .cleanForTmdbSearch(query).ifBlank { query }
            val tmdbResults = TMDb3.Search.multi(cleanQuery, language = "fr-FR", page = page)
            tmdbResults.results.mapNotNull { item ->
                when (item) {
                    is TMDb3.Movie -> Movie(
                        id = item.id.toString(),
                        title = item.title,
                        poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                        banner = item.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
                        overview = item.overview,
                        released = item.releaseDate
                    )
                    is TMDb3.Tv -> TvShow(
                        id = item.id.toString(),
                        title = item.name,
                        poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                        banner = item.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
                        overview = item.overview,
                        released = item.firstAirDate
                    )
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.e("MovixProvider", "TMDB search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            // 2026-08-04 (user : « pourquoi quand on va dans Films le dossier n'est pas trié
            //   par date ? j'ai un Spider-Man 2021 en haut, à côté un 2026, et après un truc
            //   de 1960 ») — l'onglet n'avait AUCUN tri : TMDB retombait donc sur son défaut,
            //   la popularité, d'où le mélange d'époques. Passé en date décroissante.
            //   Le seuil de quinze votes accompagne obligatoirement ce tri : sans lui, trier
            //   par date fait remonter tout ce que TMDB recense, y compris des productions
            //   confidentielles que personne n'a jamais sourcées (constaté le matin même sur
            //   la catégorie Nouveautés). Quinze est la valeur validée à l'usage.
            val limiteSortie = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Calendar.getInstance()
                    .apply { add(java.util.Calendar.DAY_OF_YEAR, -DELAI_SOURCES_JOURS) }.time)
            // ⚠ 2026-08-04 — HISTORIQUE DES ESSAIS, pour ne pas refaire les mêmes erreurs :
            //   1. date seule, une page → « ils ne chargent plus qu'une dizaine de films » ;
            //   2. popularité + tri par date CÔTÉ APP → dents de scie (la page descend de
            //      2026 à 1978, la suivante repart de 2026), capture à l'appui.
            //   Choix arbitré par le user : DATE + FILTRE DE NOTORIÉTÉ. Le tri est demandé au
            //   SERVEUR — seul moyen d'un ordre cohérent d'un bout à l'autre du catalogue —
            //   et le seuil de votes écarte les productions que personne n'a notées, qui
            //   noyaient sinon les premières pages.
            //   Le manque de volume dû au filtre est compensé en récupérant PLUSIEURS pages
            //   TMDB par chargement. Les pages étant déjà triées côté serveur, les mettre
            //   bout à bout conserve l'ordre chronologique global — c'est justement ce qui
            //   manquait au tri local.
            val vod = com.streamflixreborn.streamflix.utils.VodCategories
            val enSalles = vod.enSalles()
            val sortiesFr = vod.sortiesFrancaises()
            // 2026-08-04 — FILTRE D'ANNÉE (second clic sur « Films », cf. YearFilter).
            //   La borne haute retenue est la PLUS BASSE des deux : le délai de sortie et la
            //   fin de l'année choisie. Les dates sont au format `yyyy-MM-dd`, dont l'ordre
            //   lexicographique coïncide avec l'ordre chronologique — la comparaison directe
            //   est donc correcte.
            val plage = com.streamflixreborn.streamflix.utils.YearFilter
                .get(name, com.streamflixreborn.streamflix.utils.YearFilter.Type.FILMS)
            val debutAnnee = com.streamflixreborn.streamflix.utils.YearFilter
                .texte(com.streamflixreborn.streamflix.utils.YearFilter.borneBasse(plage))
            val finAnnee = com.streamflixreborn.streamflix.utils.YearFilter
                .texte(com.streamflixreborn.streamflix.utils.YearFilter.borneHaute(plage))
            val borneHaute = listOfNotNull(limiteSortie, finAnnee).min()

            val brut = coroutineScope {
                (0 until PAGES_TMDB_PAR_CHARGEMENT).map { decalage ->
                    async {
                        runCatching {
                            tmdbService.discoverMovies(
                                apiKey = TMDB_API_KEY,
                                page = (page - 1) * PAGES_TMDB_PAR_CHARGEMENT + decalage + 1,
                                sortBy = "primary_release_date.desc",
                                voteCountGte = VOTES_MINIMUM_ONGLET,
                                primaryReleaseDateGte = debutAnnee,
                                primaryReleaseDateLte = borneHaute,
                                withOriginalLanguage = movixCatalogOriginalLanguage(),
                            ).results ?: emptyList()
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
            brut.distinctBy { it.id }
                .filter { m ->
                    // 1. sorti, et depuis au moins une semaine (le temps que les sources
                    //    soient publiées) ; 2. plus à l'affiche en France ; 3. bel et bien
                    //    distribué en France si la sortie est récente.
                    sortiDepuis(m.release_date, DELAI_SOURCES_JOURS) &&
                        m.id.toString() !in enSalles &&
                        (!vod.dansLaFenetreFrancaise(m.release_date) ||
                            m.id.toString() in sortiesFr)
                }
                // ⚠ AUCUN tri ici : l'ordre vient du serveur et doit être conservé tel quel.
                .map { item ->
                    Movie(
                        id = item.id.toString(),
                        title = item.title ?: "",
                        poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                        banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                        rating = item.vote_average,
                        released = item.release_date
                    )
                }
        } catch (e: Exception) {
            Log.e("MovixProvider", "getMovies error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            // Filtre d'année, mémorisé séparément des films (cf. YearFilter.Type).
            val plage = com.streamflixreborn.streamflix.utils.YearFilter
                .get(name, com.streamflixreborn.streamflix.utils.YearFilter.Type.SERIES)
            val result = tmdbService.discoverTvShows(
                apiKey = TMDB_API_KEY, page = page,
                firstAirDateGte = com.streamflixreborn.streamflix.utils.YearFilter
                    .texte(com.streamflixreborn.streamflix.utils.YearFilter.borneBasse(plage)),
                firstAirDateLte = com.streamflixreborn.streamflix.utils.YearFilter
                    .texte(com.streamflixreborn.streamflix.utils.YearFilter.borneHaute(plage)),
                withOriginalLanguage = movixCatalogOriginalLanguage(),
            )
            result.results
                // Une série n'a ni salles ni fenêtre de distribution : seul le délai
                //   d'une semaine s'applique, comme sur l'accueil.
                ?.filter { sortiDepuis(it.first_air_date, DELAI_SOURCES_JOURS) }
                ?.map { item ->
                TvShow(
                    id = item.id.toString(),
                    title = item.name ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average,
                    released = item.first_air_date
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("MovixProvider", "getTvShows error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val tmdb = tmdbService.getMovieDetails(
            id = id.toInt(),
            apiKey = TMDB_API_KEY,
            appendToResponse = "credits"
        )

        val directors = tmdb.credits?.crew
            ?.filter { it.job == "Director" }
            ?.map { People(id = it.id.toString(), name = it.name ?: "", image = it.profile_path?.let { p -> "$TMDB_IMG_W500$p" }) }
            ?: emptyList()

        val cast = tmdb.credits?.cast?.take(20)?.map {
            People(
                id = it.id.toString(),
                name = it.name ?: "",
                image = it.profile_path?.let { p -> "$TMDB_IMG_W500$p" }
            )
        } ?: emptyList()

        val genres = tmdb.genres?.map {
            Genre(id = it.id.toString(), name = it.name ?: "")
        } ?: emptyList()

        // Get recommendations
        val recommendations: List<Show> = try {
            val recs = tmdbService.getMovieRecommendations(id = id.toInt(), apiKey = TMDB_API_KEY)
            recs.results?.map { item ->
                Movie(
                    id = item.id.toString(),
                    title = item.title ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average
                )
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }

        return Movie(
            id = tmdb.id.toString(),
            title = tmdb.title ?: "",
            overview = tmdb.overview,
            released = tmdb.release_date,
            runtime = tmdb.runtime,
            rating = tmdb.vote_average,
            poster = tmdb.poster_path?.let { "$TMDB_IMG_W500$it" },
            banner = tmdb.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
            imdbId = tmdb.imdb_id,
            genres = genres,
            directors = directors,
            cast = cast,
            recommendations = recommendations
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val tmdb = tmdbService.getTvDetails(
            id = id.toInt(),
            apiKey = TMDB_API_KEY,
            appendToResponse = "credits,external_ids"
        )

        val seasons = tmdb.seasons
            ?.filter { (it.season_number ?: 0) > 0 }
            ?.map { s ->
                Season(
                    id = "$id/${s.season_number}",
                    number = s.season_number ?: 0,
                    title = s.name ?: "Saison ${s.season_number}",
                    poster = s.poster_path?.let { "$TMDB_IMG_W500$it" }
                )
            } ?: emptyList()

        val cast = tmdb.credits?.cast?.take(20)?.map {
            People(
                id = it.id.toString(),
                name = it.name ?: "",
                image = it.profile_path?.let { p -> "$TMDB_IMG_W500$p" }
            )
        } ?: emptyList()

        val genres = tmdb.genres?.map {
            Genre(id = it.id.toString(), name = it.name ?: "")
        } ?: emptyList()

        val recommendations: List<Show> = try {
            val recs = tmdbService.getTvRecommendations(id = id.toInt(), apiKey = TMDB_API_KEY)
            recs.results?.map { item ->
                TvShow(
                    id = item.id.toString(),
                    title = item.name ?: "",
                    poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                    banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                    rating = item.vote_average
                )
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }

        return TvShow(
            id = tmdb.id.toString(),
            title = tmdb.name ?: "",
            overview = tmdb.overview,
            released = tmdb.first_air_date,
            rating = tmdb.vote_average,
            poster = tmdb.poster_path?.let { "$TMDB_IMG_W500$it" },
            banner = tmdb.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
            imdbId = tmdb.external_ids?.imdb_id,
            seasons = seasons,
            genres = genres,
            cast = cast,
            recommendations = recommendations
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("/")
        if (parts.size < 2) return emptyList()
        val tvShowId = parts[0]
        val seasonNumber = parts[1].toIntOrNull() ?: return emptyList()

        return try {
            val detail = tmdbService.getSeasonDetails(
                tvId = tvShowId.toInt(),
                seasonNumber = seasonNumber,
                apiKey = TMDB_API_KEY
            )
            detail.episodes?.map { ep ->
                Episode(
                    id = "$tvShowId-s${seasonNumber}e${ep.episode_number}",
                    number = ep.episode_number ?: 0,
                    title = ep.name ?: "Épisode ${ep.episode_number}",
                    released = ep.air_date,
                    poster = ep.still_path?.let { "$TMDB_IMG_W500$it" },
                    overview = ep.overview
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("MovixProvider", "getEpisodesBySeason error: ${e.message}")
            emptyList()
        }
    }

    // Genres spéciaux basés sur le pays d'origine (pas un ID TMDB numérique)
    private val specialGenres = mapOf(
        "k-drama" to "KR",
        "drama-coreen" to "KR",
        "K-Drama|" to "KR"
    )

    override suspend fun getGenre(id: String, page: Int): Genre {
        // 2026-08-04 : catégories plateformes/Nouveautés — leur identifiant n'est pas un genre,
        //   on les traite avant la logique habituelle.
        if (com.streamflixreborn.streamflix.utils.VodCategories.estCategorieSpeciale(id)) {
            return com.streamflixreborn.streamflix.utils.VodCategories.charger(id, page, language)
        }
        // ── 2026-08-05 — LE GENRE N'ÉCRASE PLUS L'ANNÉE NI LE TRI ────────────────────────────
        //   Constat user : « quand on met Action ça bouge un petit peu mais ça trie pas
        //   vraiment », sur mobile comme sur TV. Le journal montrait pourtant le genre
        //   correctement transmis (`genreId=28`, 20 résultats).
        //
        //   La cause : dès qu'un genre est choisi, `MoviesViewModel` quitte `getMovies()` pour
        //   `getGenre()` — une branche qui ne lisait NI le filtre d'année, NI le seuil de
        //   notoriété, NI le tri par date, et ne demandait qu'UNE page. On perdait donc tout le
        //   travail de l'onglet Films : d'où un mélange 2021 / 2026 / 1960 et une liste courte.
        //
        //   Attendu (user) : « si je mets des films de 2025 d'action ça doit prendre en compte ;
        //   si je mets un film Drame de 2022 ça doit trier comme ça ». Genre ET année ensemble.
        //
        //   On reprend donc mot pour mot la recette de `getMovies()`/`getTvShows()` en y ajoutant
        //   `withGenres`. Les règles de sortie restent les mêmes : délai d'une semaine, films
        //   encore en salles écartés, fenêtre de distribution française respectée.
        //
        //   ⚠ Ne PAS retrier côté application : l'ordre vient du serveur, page après page. Un tri
        //     local redonnerait les « dents de scie » déjà constatées le 4 août (la page descend
        //     de 2026 à 1978, la suivante repart de 2026).
        return try {
            val shows = mutableListOf<Show>()
            val originCountry = specialGenres[id]
            val genreFilter = if (originCountry != null) null else id
            val vod = com.streamflixreborn.streamflix.utils.VodCategories
            val enSalles = vod.enSalles()
            val sortiesFr = vod.sortiesFrancaises()
            val limiteSortie = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Calendar.getInstance()
                    .apply { add(java.util.Calendar.DAY_OF_YEAR, -DELAI_SOURCES_JOURS) }.time)

            // Films du genre (ou du pays d'origine) — année + tri + notoriété + 3 pages
            try {
                val plageFilms = com.streamflixreborn.streamflix.utils.YearFilter
                    .get(name, com.streamflixreborn.streamflix.utils.YearFilter.Type.FILMS)
                val debutFilms = com.streamflixreborn.streamflix.utils.YearFilter
                    .texte(com.streamflixreborn.streamflix.utils.YearFilter.borneBasse(plageFilms))
                val finFilms = com.streamflixreborn.streamflix.utils.YearFilter
                    .texte(com.streamflixreborn.streamflix.utils.YearFilter.borneHaute(plageFilms))
                val borneHaute = listOfNotNull(limiteSortie, finFilms).min()

                val brut = coroutineScope {
                    (0 until PAGES_TMDB_PAR_CHARGEMENT).map { decalage ->
                        async {
                            runCatching {
                                tmdbService.discoverMovies(
                                    apiKey = TMDB_API_KEY,
                                    page = (page - 1) * PAGES_TMDB_PAR_CHARGEMENT + decalage + 1,
                                    sortBy = "primary_release_date.desc",
                                    voteCountGte = VOTES_MINIMUM_ONGLET,
                                    primaryReleaseDateGte = debutFilms,
                                    primaryReleaseDateLte = borneHaute,
                                    withGenres = genreFilter,
                                    withOriginCountry = originCountry,
                                    withOriginalLanguage = movixCatalogOriginalLanguage(),
                                ).results ?: emptyList()
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll().flatten()
                }
                brut.distinctBy { it.id }
                    .filter { m ->
                        sortiDepuis(m.release_date, DELAI_SOURCES_JOURS) &&
                            m.id.toString() !in enSalles &&
                            (!vod.dansLaFenetreFrancaise(m.release_date) ||
                                m.id.toString() in sortiesFr)
                    }
                    .forEach { item ->
                        shows.add(
                            Movie(
                                id = item.id.toString(),
                                title = item.title ?: "",
                                poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                                banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                                rating = item.vote_average,
                                released = item.release_date
                            )
                        )
                    }
            } catch (e: Exception) {
                Log.e("MovixProvider", "getGenre movies error: ${e.message}")
            }

            // Séries TV du genre — même traitement, avec sa propre plage d'années.
            try {
                val plageSeries = com.streamflixreborn.streamflix.utils.YearFilter
                    .get(name, com.streamflixreborn.streamflix.utils.YearFilter.Type.SERIES)
                val tvResult = tmdbService.discoverTvShows(
                    apiKey = TMDB_API_KEY,
                    page = page,
                    sortBy = "first_air_date.desc",
                    voteCountGte = VOTES_MINIMUM_ONGLET,
                    firstAirDateGte = com.streamflixreborn.streamflix.utils.YearFilter
                        .texte(com.streamflixreborn.streamflix.utils.YearFilter.borneBasse(plageSeries)),
                    firstAirDateLte = com.streamflixreborn.streamflix.utils.YearFilter
                        .texte(com.streamflixreborn.streamflix.utils.YearFilter.borneHaute(plageSeries)),
                    withGenres = genreFilter,
                    withOriginCountry = originCountry,
                    withOriginalLanguage = movixCatalogOriginalLanguage(),
                )
                tvResult.results
                    ?.filter { sortiDepuis(it.first_air_date, DELAI_SOURCES_JOURS) }
                    ?.forEach { item ->
                        shows.add(
                            TvShow(
                                id = item.id.toString(),
                                title = item.name ?: "",
                                poster = item.poster_path?.let { "$TMDB_IMG_W500$it" },
                                banner = item.backdrop_path?.let { "$TMDB_IMG_ORIGINAL$it" },
                                rating = item.vote_average,
                                released = item.first_air_date
                            )
                        )
                    }
            } catch (e: Exception) {
                Log.e("MovixProvider", "getGenre tvShows error: ${e.message}")
            }

            // ⚠ Films et séries arrivent déjà triés par date côté serveur. On les entrelace
            //   sur cette même clé plutôt que de retrier par note — sinon on reperdrait
            //   exactement l'ordre chronologique qu'on vient de rétablir.
            // ⚠ `released` est un Calendar (converti dans le modèle), pas une chaîne : pas de
            //   `?: ""` ici, sinon l'inférence de type échoue à la compilation.
            val sorted = shows.sortedByDescending { show ->
                when (show) {
                    is Movie -> show.released
                    is TvShow -> show.released
                }
            }
            Log.d("MovixProvider", "getGenre id=$id page=$page → ${sorted.size} résultats (année + tri appliqués)")

            val genreName = when {
                originCountry == "KR" -> "K-Drama"
                else -> genreNames[id] ?: id
            }
            Genre(id = id, name = genreName, shows = sorted)
        } catch (e: Exception) {
            Genre(id = id, name = genreNames[id] ?: id)
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id = id, name = "")
        return try {
            val person = tmdbService.getPersonDetails(id = id.toInt(), apiKey = TMDB_API_KEY)
            People(
                id = id,
                name = person.name ?: "",
                image = person.profile_path?.let { "$TMDB_IMG_W500$it" },
                biography = person.biography,
                birthday = person.birthday,
                deathday = person.deathday,
                placeOfBirth = person.place_of_birth
            )
        } catch (e: Exception) {
            People(id = id, name = "")
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        initializeService()

        // 2026-05-04 : cache mémoire de l'agrégat (TTL 5 min)
        val cacheKey = serversCacheKey(videoType)
        val now = System.currentTimeMillis()
        serversCache[cacheKey]?.let { cached ->
            if (now < cached.expiresAtMs) {
                Log.d("MovixProvider", "getServers cache HIT for $cacheKey (${cached.servers.size} servers)")
                return cached.servers
            }
            serversCache.remove(cacheKey)
        }

        val servers = mutableListOf<Video.Server>()
        val nativeMovix = fetchNativeMovixServers(id, videoType)
        servers.addAll(nativeMovix)
        // 2026-07-04 : backups inline DÉSACTIVÉS → registre central.
        if (!skipBackupsForBackupCall && !com.streamflixreborn.streamflix.utils.BackupRegistry.INLINE_BACKUPS_DISABLED) {
            servers.addAll(fetchMovixBackups(id, videoType))
            // 2026-05-28 : Wiflix + FrenchStream DIRECT scrape en complément
            try { servers.addAll(fetchWiflixDirectBackup(id, videoType)) }
            catch (e: Exception) { Log.d("MovixProvider", "Wiflix direct failed: ${e.message}") }
            try { servers.addAll(fetchFrenchStreamDirectBackup(id, videoType)) }
            catch (e: Exception) { Log.d("MovixProvider", "FS direct failed: ${e.message}") }
        }

        // 2026-06-02 : dédup par URL embed (src) — élimine les doublons quand
        //   2 chemins remontent le MÊME hoster URL avec des id différents.
        val seenSrc = HashSet<String>()
        val dedupedBySrc = servers.filter { srv ->
            val key = srv.src.trim()
            if (key.isBlank()) true
            else seenSrc.add(key)
        }
        if (dedupedBySrc.size < servers.size) {
            Log.d("MovixProvider", "Dedup src: ${servers.size} → ${dedupedBySrc.size} (-${servers.size - dedupedBySrc.size} doublons)")
        }

        // 2026-06-02 : si plusieurs entrées partagent le MÊME display name
        //   (= même hoster + même langue mais URLs upload différentes — ex :
        //   2 uploads Voe distincts du même épisode), on ajoute un suffix
        //   "#2", "#3"... à partir du 2e pour que l'user les distingue.
        //   Sinon il voit "FS · Voe (VF - HD)" ×2 sans savoir lequel cliquer.
        val nameCount = mutableMapOf<String, Int>()
        val disambiguated = dedupedBySrc.map { srv ->
            val baseName = srv.name
            val seenCount = (nameCount[baseName] ?: 0) + 1
            nameCount[baseName] = seenCount
            if (seenCount > 1) srv.copy(name = "$baseName #$seenCount") else srv
        }
        val renamedCount = disambiguated.count { it.name != dedupedBySrc.find { d -> d.id == it.id }?.name }
        if (renamedCount > 0) {
            Log.d("MovixProvider", "Disambiguated $renamedCount doublons de noms (suffix #N)")
        }

        // Tri global : VF d'abord, VOSTFR ensuite, VO en dernier (toutes sources confondues)
        val finalList = sortServersByLanguage(disambiguated)

        // 2026-05-04 : on cache l'agrégat (TTL 5 min). Les m3u8 individuels
        // sont re-extracted à la demande (cache séparé Extractor.cacheTtlMs)
        // donc pas de risque de servir un m3u8 périmé depuis ce cache.
        if (finalList.isNotEmpty()) {
            serversCache[cacheKey] = CachedServers(
                servers = finalList,
                expiresAtMs = System.currentTimeMillis() + SERVERS_CACHE_TTL_MS,
            )
        }

        return finalList
    }

    /**
     * Sources NATIVES Movix (fstream, links, wiflix, cpasmal, tmdb, videasy,
     * seriesDl, mazQuest, yflix, moiflix…). Extrait de getServers pour pouvoir
     * être émis EN PREMIER par getServersProgressive (avant les backups).
     */
    // 2026-07-07 (user « affichage instantané, chaque endpoint dès qu'il répond, plus d'awaitAll
    //   qui attend le plus lent ») : onPartial (optionnel) → chaque sous-source émet son lot DÈS
    //   qu'elle a répondu, au lieu d'attendre TOUTES les sous-sources. Non fourni (getServers
    //   classique) = comportement inchangé (retour du lot complet).
    /**
     * 2026-07-09 : garde pour l'endpoint fstream (FrenchStream via API Movix).
     * L'API fait du matching par titre CÔTÉ SERVEUR — trop imprécis pour les
     * titres courts (1 mot sig. : "FROM" → "From Dusk Till Dawn", "OZ" → n'importe
     * quoi). Pour ces titres, on SKIP fstream et FrenchStream arrive via la boucle
     * générique BackupRegistry (titleMatches STRICT, aucun faux positif).
     * Seuil ≤ 1 sigWord : "FROM" = 1 (from), "Stranger Things" = 2 (stranger,things) → OK.
     */
    private fun isTitleTooShortForFstream(title: String): Boolean {
        val sigWords = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
            .replace(Regex("[^a-zA-Z0-9 ]"), " ")
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in FSTREAM_STOPWORDS }
        return sigWords.size <= 1
    }
    private val FSTREAM_STOPWORDS = setOf(
        "le", "la", "les", "un", "une", "des", "du", "de", "et", "the", "and", "of",
        "a", "an", "to", "in", "on", "for", "saison", "season"
    )

    /**
     * 2026-08-16 (décision user : « je veux garder QUE les natifs de Movix, le reste on gère
     * déjà ») — Movix est un AGRÉGATEUR : la plupart de ses endpoints rescrapent des sites que
     * l'app interroge déjà en direct. Mesuré sur Game of Thrones S08E03 : un lot de 13 serveurs
     * Movix rejeté INTÉGRALEMENT comme doublons (`PROG fresh=VIDE! batch=13 dupes=13`).
     *
     * NATIF conservé = `links` (Lecteur Movix, SeekStreaming 1 et 2 — ce que le site affiche
     * sous « 🌟 »), plus `tmdb` qui est sa propre agrégation.
     * Bridés : fstream, wiflix, purstream, j1f (on a le direct) et videasy, swiftflow,
     * mazquest, seriesdl, cpasmal (tiers sans apport propre).
     *
     * ⚠ CPASMAL est le seul dont on n'a PAS d'équivalent direct : Cpasmal et Cpasmieux ont été
     * retirés le 01/08 (cpasmieux.life exige une connexion). Le brider fait donc PERDRE cette
     * source, ce n'est pas un simple retrait de doublon. Signalé au user.
     *
     * ⚠ 2026-08-16, SOIR — RESTE À `true` (user : « t'es pas obligé de tout déverrouiller,
     * fallait juste débloquer notre backup Movix »). Ce filtre-ci ne touche PAS aux endpoints
     * secondaires : il ne fait qu'écarter, dans `links` et `tmdb`, les hébergeurs TIERS que nos
     * extracteurs couvrent déjà. C'est lui qui évite les lots de 13 serveurs en doublon.
     * Le déverrouillage du soir porte uniquement sur `movixSecondaireUtile` (BackupRegistry).
     *
     * ⚠ Coût connu et assumé de ce filtre, mesuré sur « Nando entre deux mondes » (tmdb
     * 1487864) : `api/links/movie/1487864` ne rendait que DEUX liens, `bll.embedseek.com/#ngw8j`
     * et `luluvdo.com/e/56mgplx7n9c3`. Le second a été écarté (« déjà géré en direct ») alors
     * qu'aucune autre source ne l'apportait sur ce film, et c'était le meilleur des deux :
     * `…MULTI.1080p.WEB.X264-HiggsBoson`, piste française incluse. Si ce cas se reproduit, la
     * correction ciblée est de ne plus filtrer `links` (qui ne rend qu'un ou deux liens choisis)
     * tout en gardant le filtre sur `tmdb` (le gros agrégateur) — et PAS de passer cette
     * constante à `false`, qui rouvrirait aussi les 13 hébergeurs de `tmdb`.
     */
    private const val NATIFS_SEULEMENT = true

    /**
     * 2026-08-16, SOIR — le filtre par hébergeur ne s'applique PLUS à `links`.
     *
     * Raisonnement du user, et il est juste : « pour que tu les aies bridés, c'est qu'ils sont
     * présents déjà — s'ils sont pas présents, c'est complètement foutu ». Le motif du rejet
     * (« déjà géré en direct ») est une AFFIRMATION DE PRÉSENCE. Elle n'est jamais vérifiée.
     *
     * Sur « Nando entre deux mondes », elle était fausse : `links` ne rendait que deux liens,
     * EmbedSeek et `luluvdo.com/e/56mgplx7n9c3`, et AUCUNE autre source n'apportait de LuluVdo
     * (relevé exhaustif des serveurs de CoflixWiki et Nakios : vidzy, doood, voe3, rien d'autre).
     * Le lien écarté était le meilleur du film : `…MULTI.1080p.WEB.X264-HiggsBoson`.
     *
     * `links` ne rend qu'un ou deux liens CHOISIS — ce n'est pas l'agrégateur massif. On le
     * laisse donc passer entièrement : les vrais doublons sont éliminés en aval par la dédup
     * (langBucket|normSrc), qui MESURE au lieu de supposer.
     *
     * `tmdb`, lui, reste filtré : c'est celui qui émet les lots de 13 hébergeurs tiers.
     */
    private const val FILTRER_LINKS = false

    /**
     * 2026-08-16, SOIR — cpasmal RÉACTIVÉ.
     *
     * Même raisonnement, en plus net encore : Cpasmal et Cpasmieux ont été retirés des sources
     * directes le 01/08 (cpasmieux.life exige une connexion). L'endpoint Movix est donc la
     * SEULE voie d'accès qui reste. Le brider au motif que « c'est déjà géré en direct » ne
     * pouvait pas être vrai — il n'y a pas de direct. C'était une perte sèche à chaque lecture,
     * et le commentaire de NATIFS_SEULEMENT le disait déjà noir sur blanc.
     */
    private const val CPASMAL_ACTIF = true

    /**
     * 2026-08-16, SOIR — l'endpoint `wiflix` de Movix est rebridé, et cette fois sur MESURE,
     * pas sur supposition.
     *
     * ⚠ D'abord, une découverte : cet endpoint ne scrape PAS Wiflix. Sa réponse porte un champ
     * `wiflix_url` qui vaut « https://cinestream.info/film/… », et son message d'erreur dit
     * « Film non trouve sur CineStream ». La vraie source est **cinestream.info**, un site FR de
     * films absent de notre code. Nos serveurs s'affichaient donc « Wiflix · … » à tort.
     *
     * Preuve du doublon, relevée sur Spider-Man : Homecoming (tmdb 315635) — CineStream à
     * gauche, notre Wiflix direct (flemmix.men) à droite, comparés par CODE DE FICHIER :
     *
     *     uqload.cx/embed-v1alx4mfphrg   ==  uqload.is/embed-v1alx4mfphrg
     *     vidmoly.net/embed-r9olsqo1yu0x ==  vidmoly.org/embed-r9olsqo1yu0x
     *     …/e/ifkxiqdbprms (VOE)         ==  …/e/ifkxiqdbprms
     *
     * Le troisième est confirmé jusqu'au flux : les deux serveurs joués l'un après l'autre ont
     * rendu la MÊME URL CDN finale, à l'octet près
     * (`ugc-cdn-caching-…/engine/hls2-c/01/14154/ifkxiqdbprms`).
     *
     * Seul l'HÔTE diffère (.cx/.is, .net/.org) : ce sont des miroirs du même hébergeur. Notre
     * dédup compare l'URL entière, hôte compris — elle ne peut donc pas les fusionner, et on
     * affichait chaque fichier deux fois.
     *
     * ⚠ Ce bridage est un PANSEMENT. La vraie correction est de dédoublonner sur le code de
     * fichier plutôt que sur l'URL : elle réglerait ces trois cas ET tous les mêmes doublons
     * entre sources qui n'ont rien à voir avec Movix. Quand elle sera en place, cette constante
     * pourra repasser à `true` — CineStream a 10 lecteurs contre 7 à Wiflix, donc il en a
     * peut-être d'exclusifs qu'on perd ici.
     */
    private const val MOVIX_WIFLIX_ACTIF = false

    /**
     * 2026-08-16, SOIR — l'endpoint `fstream` de Movix est bridé lui aussi, pour la même raison
     * que `wiflix` et avec la même preuve : il double le direct ET il sert des hôtes morts.
     *
     * Mesuré sur Spider-Man : No Way Home (tmdb 634649). L'endpoint a émis 18 serveurs, dont
     * TROIS `Fembed` et HUIT `RedirectProxy`. Les quatre premiers de la liste — donc ceux que le
     * lecteur choisit en premier — ont échoué en cascade :
     *
     *     FS · Uqload (VFQ)        → « embed sans source (427b) → miroir mort »
     *     FS · RedirectProxy (VFQ) → « Could not find md5 path on playmogo.com/e/8m8q46a5121p »
     *     FS · RedirectProxy #2    → kakaflix.lol/sbtm//newPlayer.php ne redirige pas
     *                                (double slash, et le segment `sbtm` n'est pas dans notre table)
     *     FS · Fembed (VFQ)        → « HOTE NON COUVERT : www.fembed.com »
     *
     * Fembed est mort depuis des années et n'a aucun extracteur ici : ces trois-là ne pourront
     * jamais jouer. Pendant ce temps, FrenchStream en DIRECT rendait 10 serveurs neufs sur 11.
     *
     * Coût pour l'utilisateur : quatre tentatives de lecture ratées avant d'atteindre un serveur
     * qui marche — visible à l'écran, quatre entrées rouges avant la première verte.
     *
     * ⚠ CONCLUSION INVERSÉE (user : « attends, t'es sûr de toi, on va pas perdre les serveurs »)
     * — il avait raison, et j'allais refaire l'erreur de la soirée. Sur SA capture d'écran, le
     * serveur qui a EFFECTIVEMENT joué, en 1440p, est `FS · RedirectProxy (VFF - HD)` =
     * `kakaflix.lol/voe1/newPlayer.php?id=6e112c78…` — c'est-à-dire un serveur de CET endpoint.
     * Le brider aurait supprimé le seul qui marchait, sans que rien ne prouve que FrenchStream
     * en direct fournisse le même fichier. Contrairement au cas `wiflix`, je n'avais AUCUNE
     * preuve d'identité de flux ici. Supposer une présence au lieu de la mesurer : exactement
     * la faute qu'on corrige depuis ce matin.
     *
     * → L'endpoint RESTE OUVERT. Seuls les hôtes INJOUABLES sont écartés, cf. HOTES_MORTS.
     */
    private const val MOVIX_FSTREAM_ACTIF = true

    /**
     * 2026-08-16 — Hôtes qu'on ne sait PAS lire : aucun extracteur, aucun alias dans le projet.
     * Ce ne sont pas des doublons ni des sources concurrentes — ce sont des entrées rouges
     * garanties, sur tous les films, qui remontent en tête de liste et coûtent une tentative de
     * lecture ratée chacune avant d'atteindre un serveur valide.
     *
     * `fembed.com` : vérifié en cherchant « fembed » dans TOUT le code source — 6 occurrences,
     *   les 6 étant des commentaires écrits ce soir. Zéro extracteur, zéro alias. Le journal le
     *   confirme : « HOTE NON COUVERT: www.fembed.com (aucun extracteur) ». Movix en servait
     *   TROIS sur Spider-Man : No Way Home (VFQ, DEFAULT, VOSTFR).
     *   ⚠ À NE PAS CONFONDRE avec **Frembed** (`frembed.casa`), notre source de backup bien
     *   vivante — 4 serveurs neufs sur ce même film. Un seul caractère les sépare.
     *
     * Si un jour on écrit un extracteur pour l'un d'eux, il suffit de le retirer d'ici.
     */
    private val HOTES_MORTS = Regex("""(^|\.)fembed\.com""", RegexOption.IGNORE_CASE)

    private fun estHoteMort(url: String): Boolean =
        HOTES_MORTS.containsMatchIn(runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault(""))

    /**
     * 2026-08-16 — Hôtes réellement MAISON de Movix, dans l'endpoint `links`.
     *
     * ⚠ Correction d'une erreur d'analyse : j'avais conservé `links` en croyant que c'était le
     * natif, en me fiant à ce que le site affiche sous ★ (« Lecteur Movix 1 », « SeekStreaming
     * 1 et 2 ») et à un test d'API qui ne remontait que 3 hôtes sur cet épisode-là. En réalité
     * `links` est le GROS agrégateur : mesuré sur Game of Thrones S08E03, il émet 13 serveurs —
     * Filemoon, VOE, Uqload, Netu, Streamwish, LuluVdo, VidHide, VidGuard, Darkibox, Veev… tous
     * des hébergeurs TIERS que nos propres extracteurs couvrent déjà et que nos backups
     * fournissent en direct.
     *
     * On ne garde donc de `links` que les lecteurs propres à Movix : Moiflix/xtremestream,
     * Rpmvid (coflix.upn…), SeekStreaming/seekplayer et EmbedSeek. Le reste est écarté.
     */
    private val HOTES_NATIFS_MOVIX = Regex(
        "(xtremestream|seekplayer|embedseek|seekstreaming|rpmvid|rpmplay|rpmstream|upns?\\.|upn\\.one|moiflix)",
        RegexOption.IGNORE_CASE,
    )

    private fun estLecteurNatifMovix(url: String): Boolean = HOTES_NATIFS_MOVIX.containsMatchIn(url)

    private suspend fun fetchNativeMovixServers(
        id: String,
        videoType: Video.Type,
        onPartial: (suspend (List<Video.Server>) -> Unit)? = null,
    ): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()

        when (videoType) {
            is Video.Type.Movie -> {
                val tmdbId = id
                Log.d("MovixProvider", "getServers Movie tmdbId=$tmdbId")

                // Parallel fetch all 5 API sources (timeout 4s + circuit breaker)
                val allResults = coroutineScope {
                    // 2026-07-09 : tmdbMovieDetailsDeferred DOIT être déclaré AVANT
                    //   fstreamDeferred car ce dernier l'await() pour la garde titre.
                    val tmdbMovieDetailsDeferred = async {
                        val tmdbIdInt = tmdbId.toIntOrNull() ?: return@async null
                        try { tmdbService.getMovieDetails(tmdbIdInt, TMDB_API_KEY) }
                        catch (_: Exception) { null }
                    }
                    val fstreamDeferred = async {
                        // 2026-08-16 (décision user : « on devrait prendre que les sources
                        //   natives de Movix, tout le reste on est censé le gérer ») —
                        //   ENDPOINT SECONDAIRE : Movix se contente ici de rescraper
                        //   FrenchStream, que le registre interroge DÉJÀ en direct. En marche
                        //   normale ses liens sont donc des doublons, fusionnés par la dédup
                        //   après avoir coûté un appel réseau pour rien.
                        //   On le rappelle UNIQUEMENT si la source directe est désactivée ou a
                        //   échoué récemment (< 30 min) : Movix garde ses liens en cache côté
                        //   serveur et devient alors le seul à les fournir.
                        if (!MOVIX_FSTREAM_ACTIF) {
                            Log.d("MovixProvider", "fstream-movie SKIP : doublons + hôtes morts (Fembed)")
                            return@async emptyList()
                        }
                        runEndpoint("fstream-movie") {
                            // 2026-07-09 : GARDE TITRE — l'API Movix fait du scraping FrenchStream
                            //   CÔTÉ SERVEUR dont le matching est mauvais pour les titres courts
                            //   (ex: "FROM" → "From Dusk Till Dawn" = faux contenu). Si le titre
                            //   a ≤ 1 mot significatif, on SKIP fstream (trop de faux positifs).
                            //   FrenchStream passe alors par BackupRegistry (boucle générique avec
                            //   titleMatches STRICT qui filtre correctement).
                            val movieTitle = tmdbMovieDetailsDeferred.await()?.title
                            if (movieTitle != null && isTitleTooShortForFstream(movieTitle)) {
                                Log.d("MovixProvider", "fstream-movie SKIP: titre '$movieTitle' trop court (faux positifs FrenchStream)")
                                return@runEndpoint emptyList()
                            }
                            val fstream = movixServiceInstance.getFstreamMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            if (fstream.success == true) {
                                fstream.players?.forEach { (lang, players) ->
                                    players.forEach { player ->
                                        val url = player.url ?: return@forEach
                                        if (url.isBlank()) return@forEach
                                        val displayLang = formatLang(lang)
                                        val quality = player.quality?.takeIf { it.isNotBlank() } ?: "HD"
                                        val urlBasedName = guessPlayerName(url)
                                        val apiClaimedName = player.player?.takeIf { it.isNotBlank() } ?: ""
                                        val playerName = when {
                                            urlBasedName.isNotBlank() && !urlBasedName.equals("Unknown", true) -> urlBasedName
                                            apiClaimedName.isNotBlank() -> apiClaimedName
                                            else -> "Lecteur"
                                        }
                                        list.add(Video.Server(id = "fstream-$lang-${list.size}", name = "FS · $playerName ($displayLang - $quality)", src = url))
                                    }
                                }
                            }
                            list
                        }
                    }

                    val linksDeferred = async {
                        runEndpoint("links-movie") {
                            val links = movixServiceInstance.getLinksMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            if (links.success == true) {
                                links.data?.links?.forEachIndexed { index, el ->
                                    val url = movixLinkUrl(el)
                                    if (!url.isNullOrBlank()) {
                                        val playerName = guessPlayerName(url)
                                        // 2026-08-16 SOIR : filtre DÉSACTIVÉ sur `links` (cf. FILTRER_LINKS).
                                        //   Il écartait des hébergeurs en AFFIRMANT qu'on les avait
                                        //   ailleurs, sans jamais le vérifier — faux sur les films rares.
                                        if (FILTRER_LINKS && !estLecteurNatifMovix(url)) {
                                            android.util.Log.d(
                                                "MovixProvider",
                                                "links-movie : tiers écarté ($playerName) — déjà géré en direct",
                                            )
                                            return@forEachIndexed
                                        }
                                        // 2026-07-31 : on précise la LANGUE quand on la connaît,
                                        //   sinon « LuluVdo #2 » (VOSTFR) se confond avec les VF.
                                        val lang = movixLinkLang(el, url)
                                        if (estVostfrAEcarter(lang)) {
                                            android.util.Log.d(
                                                "MovixProvider",
                                                "VOSTFR écarté : $playerName ($lang)",
                                            )
                                            return@forEachIndexed
                                        }
                                        val label = if (lang != null) "$playerName ($lang)" else playerName
                                        list.add(Video.Server(id = "links-$index", name = label, src = url))
                                    }
                                }
                            }
                            list
                        }
                    }

                    // 2026-07-07 : GARDÉ — le Wiflix de Movix passe par api.movix.date/api/wiflix
                    //   (scrapé CÔTÉ SERVEUR = zéro captcha CF pour l'app). C'est NOTRE backup Wiflix
                    //   direct (WiflixProvider, bypass CF avec captcha à chaque fois) qu'on retire.
                    val wiflixDeferred = async {
                        // 2026-08-16 : bridé APRÈS réparation du Wiflix direct. La note du 07/07
                        //   disait de le garder parce que le direct exigeait un captcha CF à
                        //   chaque fois — mais le direct était en réalité cassé par un SUFFIXE
                        //   D'ÉQUIPE dans le slug (« …-saison-8-stm.html »), corrigé ce jour :
                        //   il rend désormais 6 serveurs neufs sur 6 bruts. La copie Movix est
                        //   donc redevenue un doublon. Repli automatique si le direct retombe.
                        if (!MOVIX_WIFLIX_ACTIF) {
                            Log.d("MovixProvider", "wiflix-movie SKIP : doublons CineStream prouvés")
                            return@async emptyList()
                        }
                        runEndpoint("wiflix-movie") {
                            val wiflix = movixServiceInstance.getWiflixMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            if (wiflix.success == true) {
                                wiflix.players?.forEach { (lang, sources) ->
                                    val displayLang = formatLang(lang)
                                    sources.forEach { source ->
                                        val url = source.url ?: return@forEach
                                        if (url.isBlank()) return@forEach
                                        if (isHiddenHost(url)) return@forEach
                                        // 2026-07-31 (user « il s'appelle toujours Jessica ») : l'API Movix
                                        //   renvoie parfois un NOM DE DOMAINE comme nom de lecteur (ex.
                                        //   « jessicayeahcatch.com » = un miroir rotatif de VOE). On affichait
                                        //   ce domaine brut. Si le nom ressemble à un domaine, on lui préfère
                                        //   le VRAI nom du service déduit de l'URL (→ « VOE »).
                                        val playerName = prettyPlayerName(source.name, url)
                                        list.add(Video.Server(id = "wiflix-$lang-${list.size}", name = "Wiflix · $playerName ($displayLang)", src = url))
                                    }
                                }
                            }
                            list
                        }
                    }

                    val cpasmalDeferred = async {
                        // 2026-08-16 SOIR : RÉACTIVÉ (cf. CPASMAL_ACTIF) — aucune source directe
                        //   n'existe depuis le retrait de Cpasmal/Cpasmieux le 01/08.
                        if (!CPASMAL_ACTIF) return@async emptyList()
                        runEndpoint("cpasmal-movie") {
                            val cpasmal = movixServiceInstance.getCpasmalMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            // CONTRÔLE D'IDENTITÉ (cf. pavé slugCpasmalCorrespond).
                            //   Posé aussi côté film : rien ne garantit que le mauvais
                            //   rattachement observé sur les séries épargne les films.
                            if (!slugCpasmalCorrespond(
                                    cpasmal.cpasmalUrl,
                                    listOf((videoType as? Video.Type.Movie)?.title, cpasmal.title),
                                )
                            ) {
                                Log.w(
                                    "MovixProvider",
                                    "cpasmal-movie ÉCARTÉ — mauvaise œuvre. Demandé " +
                                        "« ${(videoType as? Video.Type.Movie)?.title} » (tmdb=$tmdbId), " +
                                        "cpasmal sert « ${oeuvreDansSlugCpasmal(cpasmal.cpasmalUrl)} » " +
                                        "(${cpasmal.cpasmalUrl}) → " +
                                        "${cpasmal.links?.values?.sumOf { it.size } ?: 0} lecteur(s) supprimé(s)",
                                )
                                return@runEndpoint list
                            }
                            cpasmal.links?.forEach { (lang, links) ->
                                val displayLang = formatLang(lang)
                                links.forEach { link ->
                                    val url = link.url ?: return@forEach
                                    if (url.isBlank()) return@forEach
                                    val playerName = link.server?.replaceFirstChar { it.uppercase() } ?: guessPlayerName(url)
                                    list.add(Video.Server(id = "cpasmal-$lang-${list.size}", name = "CPasMal · $playerName ($displayLang)", src = url))
                                }
                            }
                            list
                        }
                    }

                    // 2026-08-14 (user : « j'ai des serveurs toujours énumérés TMDb alors que
                    //   TMDB ne fournit aucun serveur, j'avais demandé de retirer cette étiquette
                    //   car ce n'est pas vrai ») : il avait raison. Ces serveurs viennent de
                    //   l'endpoint `api/tmdb/movie/{id}` de MOVIX — « tmdb » n'y désigne que la
                    //   CLÉ DE RECHERCHE (on interroge Movix par identifiant TMDB), pas la source
                    //   du flux. L'étiquette annonçait donc un fournisseur qui n'existe pas.
                    //   Le vrai fournisseur est Movix, comme pour les autres endpoints du fichier
                    //   (« Wiflix · », « FS · », « CPasMal · »).
                    val tmdbMovixDeferred = async {
                        // 2026-08-16 : c'est CE constructeur qui produit les libellés
                        //   « Movix · Filemoon - FileMon Vidéo 10 (VF) » — et donc les 13
                        //   hébergeurs tiers vus au journal (Filemoon, VOE, Uqload, Vidoza,
                        //   VidMoLy, Streamwish, LuluVdo, VidHide, VidGuard, Darkibox, Veev…).
                        //   Je l'avais gardé en le croyant natif : il ne l'est pas, c'est une
                        //   agrégation d'hébergeurs tiers que nos extracteurs couvrent déjà.
                        //   Preuve à l'exécution : deux émissions à 78 ms d'écart — « emit 2 :
                        //   EmbedSeek | SeekStreaming » (links, filtré) puis « emit 13 : Movix ·
                        //   Filemoon… » (ici, non filtré). C'est aussi lui qui alimentait le
                        //   « +15 Movix » injecté par CloudstreamProvider.
                        //   ⚠ NE PAS couper l'endpoint EN ENTIER : il porte aussi DEUX natifs,
                        //   Moiflix et Rpmvid (coflix.upn). Première tentative supprimée d'un
                        //   bloc → le user a perdu « Movix · Moiflix » qu'il voulait garder.
                        //   On filtre donc PAR HÉBERGEUR, comme sur `links`.
                        runEndpoint("tmdb-movie") {
                            val tmdbMovix = movixServiceInstance.getTmdbMovixMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            tmdbMovix.player_links?.forEach { link ->
                                val url = link.decoded_url ?: return@forEach
                                if (url.isBlank()) return@forEach
                                val lang = if (link.language?.lowercase()?.contains("french") == true) "VF"
                                    else link.language ?: ""
                                val qualityLabel = link.quality?.substringBefore("/")?.trim() ?: "HD"
                                val playerName = guessPlayerName(url)
                                // 2026-08-16 : ne garder que les lecteurs MAISON (Moiflix,
                                //   Rpmvid…). Les tiers (Filemoon, VOE, Uqload, Streamwish…)
                                //   sont déjà servis par nos backups directs.
                                if (NATIFS_SEULEMENT && !estLecteurNatifMovix(url)) {
                                    Log.d("MovixProvider", "tmdb : tiers écarté ($playerName)")
                                    return@forEach
                                }
                                list.add(Video.Server(id = "tmdbmovix-${list.size}", name = "Movix · $playerName - $qualityLabel ($lang)", src = url))
                            }
                            list
                        }
                    }

                    val videasyDeferred = async {
                        try {
                            val videasy = com.streamflixreborn.streamflix.extractors.VideasyExtractor()
                            val frServers = videasy.servers(videoType, "fr")
                            // 2026-05-03 : Neon (myflixerzupcloud) renvoie souvent
                            // des sources Netflix random (player de fallback) sur
                            // les contenus mal indexés -> on l'exclut côté Movix
                            // pour eviter les "sources fantômes" pénibles à filtrer.
                            val enServers = videasy.servers(videoType, "en")
                                .filterNot { it.id.startsWith("Neon ") }
                            (frServers + enServers).also {
                                Log.d("MovixProvider", "Videasy movie: ${it.size} servers (Neon filtered)")
                            }
                        } catch (e: Exception) {
                            Log.e("MovixProvider", "Videasy movie error: ${e.message}")
                            emptyList()
                        }
                    }

                    // 2026-05-11 : Yflix + Moiflix partagent la même lookup TMDB
                    // (title+year). tmdbMovieDetailsDeferred est déclaré en haut du
                    // coroutineScope (avant fstreamDeferred qui en dépend).
                    // 2026-07-25 : yflix/moiflix RETIRÉS (morts — yflix.to down/non-FR, moiflix.fans
                    //   down sans successeur ; le SITE Movix ne les appelle plus non plus). Remplacés
                    //   par 2 nouvelles sources Movix vues sur movix.show (structure inspectée en direct) :
                    //   j1f (1Jour1Film via Movix) : players.{vf,vostfr}[] = {name,url,type,label,source}.
                    val j1fDeferred = async {
                        // 2026-08-16 : endpoint SECONDAIRE — 1Jour1Film est interrogé en direct
                        //   par le registre (boucle générique, avec ses titres alternatifs).
                        if (!com.streamflixreborn.streamflix.utils.BackupRegistry.movixSecondaireUtile("1Jour1Film")) {
                            Log.d("MovixProvider", "j1f-movie SKIP : 1Jour1Film répond en direct")
                            return@async emptyList()
                        }
                        runEndpoint("j1f-movie") {
                            val j1f = movixServiceInstance.getJ1fMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            // 2026-08-01 (user : « Movix VF FHD joue un mauvais film », durée
                            //   mesurée 1 h 29 au lieu de 1 h 38) : l'API J1F MAPPE MAL les
                            //   films homonymes. Preuve relevée en direct pour tmdb=1122573
                            //   (« Les Spécialistes » / In the Grey, 2026) :
                            //       j1f_url = …/films/les-specialistes-vf-1985/
                            //   → elle renvoyait le film FRANÇAIS DE 1985, tout en affichant le
                            //   bon `title`. Même piège que « Joker » 2019/2015 sur CoflixWiki.
                            //   L'année est pourtant présente dans le slug : si elle contredit
                            //   celle du film demandé, on REJETTE la source (principe user :
                            //   « pas de serveur plutôt que le mauvais film »).
                            val anneeDemandee = tmdbMovieDetailsDeferred.await()
                                ?.release_date?.take(4)?.toIntOrNull()?.takeIf { it > 1800 }
                            val anneeSlug = Regex("""-(\d{4})/?$""")
                                .find(j1f.j1fUrl?.trimEnd('/') ?: "")?.groupValues?.get(1)?.toIntOrNull()
                            if (anneeDemandee != null && anneeSlug != null &&
                                kotlin.math.abs(anneeSlug - anneeDemandee) > 1
                            ) {
                                Log.w(
                                    "MovixProvider",
                                    "J1F REJETÉ : slug année=$anneeSlug ≠ film demandé=$anneeDemandee " +
                                        "(homonyme → mauvais film)",
                                )
                                return@runEndpoint list
                            }
                            if (j1f.success == true) {
                                j1f.players?.forEach { (lang, players) ->
                                    val displayLang = formatLang(lang)
                                    players.forEach { p ->
                                        val url = p.url ?: return@forEach
                                        if (url.isBlank()) return@forEach
                                        val nm = p.name?.takeIf { it.isNotBlank() } ?: guessPlayerName(url)
                                        val lbl = p.label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                                        list.add(Video.Server(
                                            id = "j1f-$lang-${list.size}",
                                            name = "J1F · $nm ($displayLang)$lbl",
                                            src = url,
                                        ))
                                    }
                                }
                            }
                            list
                        }
                    }
                    //   purstream : sources[] = {url,name,format} (source directe, sans langue).
                    val purstreamDeferred = async {
                        // 2026-08-16 : endpoint SECONDAIRE. On a désormais un provider Purstream
                        //   DIRECT (PurstreamProvider, films ET séries, mapping par tmdbId) —
                        //   cette copie est donc un doublon en marche normale.
                        if (!com.streamflixreborn.streamflix.utils.BackupRegistry.movixSecondaireUtile("Purstream")) {
                            Log.d("MovixProvider", "purstream-movie SKIP : Purstream répond en direct")
                            return@async emptyList()
                        }
                        runEndpoint("purstream-movie") {
                            val ps = movixServiceInstance.getPurstreamMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            ps.sources?.forEachIndexed { i, s ->
                                val url = s.url ?: return@forEachIndexed
                                if (url.isBlank()) return@forEachIndexed
                                val nm = s.name?.takeIf { it.isNotBlank() } ?: "Purstream"
                                list.add(Video.Server(id = "purstream-$i", name = "Purstream · $nm", src = url))
                            }
                            list
                        }
                    }
                    // 2026-07-16 : SwiftFlow — endpoint dédié Movix (api/swiftflow/movie/{id}).
                    //   players.{vf,vostfr}[] → 1 Video.Server par player (src = iframe swiftflow.lol).
                    //   Résolu par SwiftFlowExtractor (overlay WebView). FILMS uniquement.
                    val swiftflowDeferred = async {
                        runEndpoint("swiftflow-movie") {
                            val sf = movixServiceInstance.getSwiftflowMovie(tmdbId)
                            val list = mutableListOf<Video.Server>()
                            if (sf.success == true) {
                                sf.players?.forEach { (lang, players) ->
                                    val displayLang = formatLang(lang)
                                    players.forEach { p ->
                                        val url = p.url ?: return@forEach
                                        if (url.isBlank() || !url.contains("swiftflow", ignoreCase = true)) return@forEach
                                        val label = p.label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                                        list.add(Video.Server(
                                            id = "swiftflow-$lang-${list.size}",
                                            name = "SwiftFlow ($displayLang)$label",
                                            src = url,
                                        ))
                                    }
                                }
                            }
                            list
                        }
                    }
                    // 2026-05-11 : ROLLBACK lien VoirDrama-Movix. Le user veut garder
                    // les providers séparés pour bien voir quelle source produit quel
                    // serveur. VoirDrama reste accessible en standalone.
                    // 2026-07-07 : on await CHAQUE deferred et on émet son lot dès qu'il est prêt
                    //   (onPartial) au lieu d'attendre le plus lent (awaitAll). Retourne pareil.
                    // 2026-07-09 : fstream REMIS — le user préfère avoir les serveurs FS même si
                    //   le matching Movix API est parfois imprécis (ex: "FROM" → "From Dusk Till Dawn").
                    //   Mieux vaut quelques serveurs en trop que zéro FrenchStream.
                    listOf(fstreamDeferred, linksDeferred, wiflixDeferred, cpasmalDeferred, tmdbMovixDeferred, videasyDeferred, j1fDeferred, purstreamDeferred, swiftflowDeferred)
                        .map { d -> async { val r = d.await(); if (r.isNotEmpty() && onPartial != null) onPartial(r); r } }
                        .awaitAll()
                }

                allResults.forEach { servers.addAll(it) }
                Log.i("MovixProvider", "Total servers for movie $tmdbId: ${servers.size}")
            }

            is Video.Type.Episode -> {
                val tmdbId = videoType.tvShow.id
                val seasonNum = videoType.season.number
                val episodeNum = videoType.number

                // 2026-08-06 : GARDE NUMÉRIQUE. Quand la fiche est ouverte depuis un autre
                //   provider (Wiflix, Coflix…), `tvShow.id` est le SLUG de ce provider, pas un
                //   tmdbId — ex. "36342-game-of-thrones-house-of-the-dragon-saison-3.html".
                //   Les routes Movix attendent un id numérique : vérifié en direct sur
                //   api.movix.fun, wiflix/tv/94997/3 → 200 / 103 592 o, le même avec le slug
                //   → 200 / 171 o (enveloppe vide) ; links/tv → 404. Les 4 requêtes partaient
                //   donc à vide et coûtaient ~23 s avant de conclure « Movix → 0 ».
                //   Même esprit que la garde titre de fstream ci-dessous.
                //   ⚠ On sort AVANT de lancer quoi que ce soit : rien n'est perdu, ces routes
                //   n'auraient rien rendu de toute façon.
                if (!tmdbId.all { it.isDigit() }) {
                    Log.d(
                        "MovixProvider",
                        "SKIP épisode : id '$tmdbId' non numérique (slug d'un autre provider) " +
                            "→ les routes Movix exigent un tmdbId"
                    )
                    return emptyList()
                }

                // Parallel fetch all 6 API sources (timeout 4s + circuit breaker)
                val allResults = coroutineScope {
                    val fstreamDeferred = async {
                        // 2026-08-16 : endpoint SECONDAIRE (copie de FrenchStream) — cf. bloc film.
                        if (!MOVIX_FSTREAM_ACTIF) {
                            Log.d("MovixProvider", "fstream-tv SKIP : doublons + hôtes morts (Fembed)")
                            return@async emptyList()
                        }
                        runEndpoint("fstream-tv") {
                            // 2026-07-09 : GARDE TITRE (même que bloc movie) — titre court → skip fstream.
                            val showTitle = videoType.tvShow.title
                            if (showTitle != null && isTitleTooShortForFstream(showTitle)) {
                                Log.d("MovixProvider", "fstream-tv SKIP: titre '$showTitle' trop court (faux positifs FrenchStream)")
                                return@runEndpoint emptyList()
                            }
                            val fstream = movixServiceInstance.getFstreamTv(tmdbId, seasonNum)
                            val list = mutableListOf<Video.Server>()
                            fstream.episodes?.get(episodeNum.toString())?.languages?.forEach { (lang, players) ->
                                val displayLang = formatLang(lang)
                                players.forEach { player ->
                                    val url = player.url ?: return@forEach
                                    if (url.isBlank()) return@forEach
                                    val quality = player.quality?.takeIf { it.isNotBlank() } ?: "HD"
                                    val playerName = player.player?.takeIf { it.isNotBlank() } ?: guessPlayerName(url)
                                    list.add(Video.Server(id = "fstream-$lang-${list.size}", name = "FS · $playerName ($displayLang - $quality)", src = url))
                                }
                            }
                            list
                        }
                    }

                    val linksDeferred = async {
                        runEndpoint("links-tv") {
                            val links = movixServiceInstance.getLinksTv(tmdbId, seasonNum, episodeNum)
                            val list = mutableListOf<Video.Server>()
                            links.data?.forEach { item ->
                                item.links?.forEachIndexed { index, el ->
                                    val url = movixLinkUrl(el)
                                    if (!url.isNullOrBlank()) {
                                        val playerName = guessPlayerName(url)
                                        // 2026-08-16 SOIR : filtre DÉSACTIVÉ sur `links` (cf. FILTRER_LINKS),
                                        //   parité avec le bloc film.
                                        if (FILTRER_LINKS && !estLecteurNatifMovix(url)) {
                                            android.util.Log.d(
                                                "MovixProvider",
                                                "links-tv : tiers écarté ($playerName) — déjà géré en direct",
                                            )
                                            return@forEachIndexed
                                        }
                                        // 2026-07-31 : langue affichée (parité avec les films)
                                        val lang = movixLinkLang(el, url)
                                        if (estVostfrAEcarter(lang)) {
                                            android.util.Log.d(
                                                "MovixProvider",
                                                "VOSTFR écarté (série) : $playerName ($lang)",
                                            )
                                            return@forEachIndexed
                                        }
                                        val label = if (lang != null) "$playerName ($lang)" else playerName
                                        list.add(Video.Server(id = "links-tv-$index", name = label, src = url))
                                    }
                                }
                            }
                            list
                        }
                    }

                    val wiflixDeferred = async {
                        // 2026-08-16 : endpoint SECONDAIRE — cf. bloc film. Wiflix direct réparé
                        //   ce jour (suffixe d'équipe dans le slug) → cette copie est un doublon.
                        if (!MOVIX_WIFLIX_ACTIF) {
                            Log.d("MovixProvider", "wiflix-tv SKIP : doublons CineStream prouvés")
                            return@async emptyList()
                        }
                        runEndpoint("wiflix-tv") {
                            val wiflix = movixServiceInstance.getWiflixTv(tmdbId, seasonNum)
                            val list = mutableListOf<Video.Server>()
                            wiflix.episodes?.get(episodeNum.toString())?.forEach { (lang, sources) ->
                                val displayLang = formatLang(lang)
                                sources.forEach { source ->
                                    val url = source.url ?: return@forEach
                                    if (url.isBlank()) return@forEach
                                    if (isHiddenHost(url)) return@forEach
                                    val playerName = source.name?.takeIf { it.isNotBlank() } ?: guessPlayerName(url)
                                    list.add(Video.Server(id = "wiflix-$lang-${list.size}", name = "Wiflix · $playerName ($displayLang)", src = url))
                                }
                            }
                            list
                        }
                    }

                    val cpasmalDeferred = async {
                        // 2026-08-16 SOIR : RÉACTIVÉ (cf. CPASMAL_ACTIF) — parité avec le bloc film.
                        if (!CPASMAL_ACTIF) return@async emptyList()
                        runEndpoint("cpasmal-tv") {
                            val cpasmal = movixServiceInstance.getCpasmalTv(tmdbId, seasonNum, episodeNum)
                            val list = mutableListOf<Video.Server>()
                            // CONTRÔLE D'IDENTITÉ (cf. pavé slugCpasmalCorrespond).
                            if (!slugCpasmalCorrespond(
                                    cpasmal.cpasmalUrl,
                                    listOf(videoType.tvShow.title, cpasmal.title),
                                )
                            ) {
                                Log.w(
                                    "MovixProvider",
                                    "cpasmal-tv ÉCARTÉ — mauvaise œuvre. Demandé " +
                                        "« ${videoType.tvShow.title} » (tmdb=$tmdbId, " +
                                        "S${seasonNum}E$episodeNum), cpasmal sert " +
                                        "« ${oeuvreDansSlugCpasmal(cpasmal.cpasmalUrl)} » " +
                                        "(${cpasmal.cpasmalUrl}) → " +
                                        "${cpasmal.links?.values?.sumOf { it.size } ?: 0} lecteur(s) supprimé(s)",
                                )
                                return@runEndpoint list
                            }
                            cpasmal.links?.forEach { (lang, links) ->
                                val displayLang = formatLang(lang)
                                links.forEach { link ->
                                    val url = link.url ?: return@forEach
                                    if (url.isBlank()) return@forEach
                                    val playerName = link.server?.replaceFirstChar { it.uppercase() } ?: guessPlayerName(url)
                                    list.add(Video.Server(id = "cpasmal-$lang-${list.size}", name = "CPasMal · $playerName ($displayLang)", src = url))
                                }
                            }
                            list
                        }
                    }

                    val tmdbMovixDeferred = async {
                        // 2026-08-16 : idem bloc film — c'est ce constructeur qui émet les 13
                        //   hébergeurs tiers sous le libellé « Movix · … ». Non natif → bridé.
                        //   ⚠ Filtrage PAR HÉBERGEUR (cf. bloc film) : Moiflix et Rpmvid sont
                        //   natifs et doivent rester ; seuls les tiers sont écartés.
                        runEndpoint("tmdb-tv") {
                            val tmdbMovix = movixServiceInstance.getTmdbMovixTv(tmdbId, seasonNum, episodeNum)
                            val list = mutableListOf<Video.Server>()
                            // ── CONTRÔLE D'IDENTITÉ ────────────────────────────────────
                            // Movix déclare le titre de l'épisode qu'il sert. S'il annonce une
                            //   AUTRE œuvre que celle demandée, on jette les 12 lecteurs d'un
                            //   coup : ils pointent tous sur la mauvaise série. Voir le pavé
                            //   explicatif près de `titreOeuvreCorrespond`.
                            val ep = tmdbMovix.current_episode
                            val titresOeuvre = listOfNotNull(
                                videoType.tvShow.title.takeIf { it.isNotBlank() },
                            )
                            if (ep != null && !titreOeuvreCorrespond(ep.title, tmdbMovix.tmdb_details, titresOeuvre)) {
                                Log.w(
                                    "MovixProvider",
                                    "tmdb-tv ÉCARTÉ — mauvaise œuvre. Demandé « ${
                                        tmdbMovix.tmdb_details?.title ?: videoType.tvShow.title
                                    } » (tmdb=$tmdbId, S${seasonNum}E$episodeNum), Movix déclare " +
                                        "« ${ep.title} » → ${ep.player_links?.size ?: 0} lecteur(s) supprimé(s)",
                                )
                                return@runEndpoint list
                            }
                            tmdbMovix.current_episode?.player_links?.forEach { link ->
                                val url = link.decoded_url ?: return@forEach
                                if (url.isBlank()) return@forEach
                                val lang = if (link.language?.lowercase()?.contains("french") == true) "VF"
                                    else link.language ?: ""
                                val qualityLabel = link.quality?.substringBefore("/")?.trim() ?: "HD"
                                val playerName = guessPlayerName(url)
                                // 2026-08-16 : ne garder que les lecteurs MAISON (Moiflix,
                                //   Rpmvid…). Les tiers (Filemoon, VOE, Uqload, Streamwish…)
                                //   sont déjà servis par nos backups directs.
                                if (NATIFS_SEULEMENT && !estLecteurNatifMovix(url)) {
                                    Log.d("MovixProvider", "tmdb : tiers écarté ($playerName)")
                                    return@forEach
                                }
                                list.add(Video.Server(id = "tmdbmovix-${list.size}", name = "Movix · $playerName - $qualityLabel ($lang)", src = url))
                            }
                            list
                        }
                    }

                    val seriesDlDeferred = async {
                        // 2026-08-16 : non natif → bridé (cf. NATIFS_SEULEMENT).
                        if (NATIFS_SEULEMENT) return@async emptyList()
                        runEndpoint("seriesdl-tv") {
                            val showTitle = videoType.tvShow.title
                            val searchResults = movixServiceInstance.searchMovix(showTitle)
                            // 2026-05-03 : Movix renvoie type="series" pour les TV (pas
                            // "tv"). Le filtre précédent excluait TOUS les résultats
                            // série -> seriesDl ne récupérait jamais rien -> on ratait
                            // les sources Darkibox HLS sur des séries comme New York 911.
                            val movixShow = searchResults.results?.firstOrNull {
                                (it.type == "tv" || it.type == "series") && it.tmdb_id?.toString() == tmdbId
                            }
                            val movixId = movixShow?.id?.toString()
                            val list = mutableListOf<Video.Server>()
                            if (movixId != null) {
                                val dl = movixServiceInstance.getSeriesDownload(movixId, seasonNum, episodeNum)
                                dl.sources?.forEach { source ->
                                    val m3u8Url = source.m3u8
                                    val embedUrl = source.src
                                    val url = if (!m3u8Url.isNullOrBlank()) m3u8Url else embedUrl ?: return@forEach
                                    if (url.isBlank()) return@forEach
                                    val displayLang = source.language?.uppercase() ?: "MULTI"
                                    val quality = source.quality ?: "HD"
                                    val playerName = if (!m3u8Url.isNullOrBlank()) "Darkibox HLS" else guessPlayerName(url)
                                    list.add(Video.Server(id = "seriesdl-${list.size}", name = "SériesDL · $playerName ($displayLang - $quality)", src = url))
                                }
                            }
                            list
                        }
                    }

                    // 2026-05-03 : Videasy direct (FR + EN). Movix UI utilise
                    // player.videasy.net pour les sources VO/VOSTFR sur les vieilles
                    // séries, sans cet appel on perdait jusqu'à 4-8 sources par
                    // épisode (Chamber + 8 servers EN type Neon/Yoru/Cypher…).
                    val videasyDeferred = async {
                        try {
                            val videasy = com.streamflixreborn.streamflix.extractors.VideasyExtractor()
                            val frServers = videasy.servers(videoType, "fr")
                            // 2026-05-03 : Neon (myflixerzupcloud) renvoie souvent
                            // des sources Netflix random (player de fallback) sur
                            // les vieilles séries non indexées -> on l'exclut côté
                            // Movix.
                            val enServers = videasy.servers(videoType, "en")
                                .filterNot { it.id.startsWith("Neon ") }
                            (frServers + enServers).also {
                                Log.d("MovixProvider", "Videasy tv: ${it.size} servers (${frServers.size} FR + ${enServers.size} EN, Neon filtered)")
                            }
                        } catch (e: Exception) {
                            Log.e("MovixProvider", "Videasy tv error: ${e.message}")
                            emptyList()
                        }
                    }

                    // 2026-05-04 : MazQuest = backend allostreaming.one + waaatch.art.
                    // Sert des yadi.sk (Yandex Disk) avec gros catalogue VF de
                    // vieilles séries (NY911, Friends, etc.) que Movix ne couvre
                    // plus. Pré-check de l'API ici pour ne pas afficher de
                    // serveur fantôme sur les épisodes qui n'ont pas de source.
                    val mazQuestDeferred = async {
                        // 2026-08-16 : non natif → bridé (cf. NATIFS_SEULEMENT).
                        if (NATIFS_SEULEMENT) return@async emptyList()
                        runEndpoint("mazquest-tv") {
                            val ssPad = "%02d".format(seasonNum)
                            val epPad = "%02d".format(episodeNum)
                            val url = "https://embed.maz.quest/tv/api/$tmdbId/$ssPad/$epPad"
                            // Pré-check : on hit l'API tout de suite. Si `error=true`
                            // ou `links` vide -> on n'expose pas le serveur. Sinon
                            // on l'ajoute, l'extracteur retapera l'API au play
                            // (plus fiable car le yadi.sk peut périmer entre temps).
                            val req = okhttp3.Request.Builder()
                                .url(url)
                                .header("Accept", "application/json")
                                .build()
                            val resp = com.streamflixreborn.streamflix.utils.NetworkClient.default
                                .newCall(req).execute()
                            val body = resp.body?.string()
                            if (body.isNullOrBlank()) return@runEndpoint emptyList<Video.Server>()
                            val json = org.json.JSONObject(body)
                            val hasSource = !json.optBoolean("error", true)
                                && (json.optJSONArray("links")?.length() ?: 0) > 0
                            if (!hasSource) {
                                Log.d("MovixProvider", "MazQuest tv: no source for $tmdbId S${ssPad}E$epPad")
                                return@runEndpoint emptyList<Video.Server>()
                            }
                            Log.d("MovixProvider", "MazQuest tv: source found for $tmdbId S${ssPad}E$epPad")
                            listOf(
                                Video.Server(
                                    id = "mazquest-tv-$tmdbId-$ssPad-$epPad",
                                    name = "Yandex VF (Maz)",
                                    src = url,
                                )
                            )
                        }
                    }

                    // 2026-05-11 : Yflix + Moiflix partagent la même lookup TMDB TV
                    // (1 call au lieu de 2) → gain ~150-300ms.
                    val tmdbTvDetailsDeferred = async {
                        val tmdbIdInt = tmdbId.toIntOrNull() ?: return@async null
                        try { tmdbService.getTvDetails(tmdbIdInt, TMDB_API_KEY) }
                        catch (_: Exception) { null }
                    }
                    // 2026-07-25 : yflix/moiflix RETIRÉS ici aussi (morts, cf bloc film). Les endpoints
                    //   série j1f/purstream ne sont pas fiables (j1f/tv sans `players`, purstream/tv = 404)
                    //   → non ajoutés pour les séries ; ils restent films uniquement.
                    listOf(fstreamDeferred, linksDeferred, wiflixDeferred, cpasmalDeferred, tmdbMovixDeferred, seriesDlDeferred, videasyDeferred, mazQuestDeferred)
                        .map { d -> async { val r = d.await(); if (r.isNotEmpty() && onPartial != null) onPartial(r); r } }
                        .awaitAll()
                }

                allResults.forEach { servers.addAll(it) }
            }
        }
        return servers
    }

    /**
     * Backups cross-provider (Cloudstream, Moviebox, Papadustream, Coflix), dans
     * cet ordre. Extrait de getServers. Le caller décide de les appeler ou non
     * (anti-récursion Cloudstream↔Movix via skipBackupsForBackupCall).
     */
    private suspend fun fetchMovixBackups(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()

        // 2026-05-06 : Cloudstream backup #2 — démarre vite, MovieBox+ via /resource
        // bcdn (sans pre-roll). Insertion juste après les sources Movix natives.
        val cloudstreamBackup = try {
            val csId = when (videoType) {
                is Video.Type.Movie -> id
                is Video.Type.Episode -> id.substringBefore("-").let { tid ->
                    "$tid:${videoType.season.number}:${videoType.number}"
                }
            }
            CloudstreamProvider.getServers(csId, videoType)
        } catch (e: Exception) {
            Log.d("MovixProvider", "Cloudstream backup failed for $id: ${e.message}")
            emptyList()
        }
        if (cloudstreamBackup.isNotEmpty()) {
            Log.d("MovixProvider", "+ Cloudstream backup : ${cloudstreamBackup.size} sources")
            servers.addAll(cloudstreamBackup)
        }

        // 2026-05-05 : Moviebox backup — recherche par titre TMDB + filtrage FR
        // strict côté Moviebox (subtitles ou dubs FR uniquement).
        val movieboxBackup = try {
            val tmdbIdInt = when (videoType) {
                is Video.Type.Movie -> id.toIntOrNull()
                is Video.Type.Episode -> id.substringBefore("-").toIntOrNull()
            }
            if (tmdbIdInt != null) MovieboxProvider.getMovieboxSourcesByTmdbId(tmdbIdInt, videoType)
            else emptyList()
        } catch (e: Exception) {
            Log.d("MovixProvider", "Moviebox backup failed for $id: ${e.message}")
            emptyList()
        }
        if (movieboxBackup.isNotEmpty()) {
            Log.d("MovixProvider", "+ Moviebox backup : ${movieboxBackup.size} sources")
            servers.addAll(movieboxBackup)
        }

        // 2026-06-02 : Papadustream DÉSACTIVÉ comme backup (user request).
        //   Le captcha Cloudflare Turnstile sur chaque source est trop intrusif
        //   et casse l'UX (popup d'écran qui interrompt la lecture). On laisse
        //   le provider Papa fonctionnel en accès direct si l'user choisit
        //   explicitement "Papadustream" dans la sélection de providers — mais
        //   il n'apparaît plus dans le picker de serveurs des autres providers.
        // val papaBackup = try {
        //     PapadustreamProvider.getPapaSourcesByTmdbId(id, videoType)
        // } catch (e: Exception) {
        //     Log.d("MovixProvider", "Papadustream backup failed for $id: ${e.message}")
        //     emptyList()
        // }
        // if (papaBackup.isNotEmpty()) {
        //     Log.d("MovixProvider", "+ Papadustream backup (last resort) : ${papaBackup.size} sources")
        //     servers.addAll(papaBackup)
        // }

        // 2026-05-05 : Coflix backup — site français multi-hosters (Lulustream,
        // VOE, Vidoza, Darkibox, Veev, Goodstream...). Recherche par titre via
        // /suggest.php (pas besoin d'ID, ça marche pour tout le catalogue).
        val coflixBackup = try {
            // On a l'ID TMDB ; on récupère le titre/year depuis TMDB pour la recherche
            val tmdbIdInt = when (videoType) {
                is Video.Type.Movie -> id.toIntOrNull()
                is Video.Type.Episode -> id.substringBefore("-").toIntOrNull()
            }
            if (tmdbIdInt != null) {
                val (title, year) = when (videoType) {
                    is Video.Type.Movie -> {
                        val det = TMDb3.Movies.details(movieId = tmdbIdInt, language = "fr-FR")
                        (det.title.takeIf { it.isNotBlank() } ?: det.originalTitle) to
                            det.releaseDate?.take(4)?.toIntOrNull()
                    }
                    is Video.Type.Episode -> {
                        val det = TMDb3.TvSeries.details(seriesId = tmdbIdInt, language = "fr-FR")
                        (det.name.takeIf { it.isNotBlank() } ?: det.originalName) to
                            det.firstAirDate?.take(4)?.toIntOrNull()
                    }
                }
                when (videoType) {
                    is Video.Type.Movie -> CoflixSourceProvider.getMovieSources(title, year)
                    is Video.Type.Episode -> CoflixSourceProvider.getEpisodeSources(
                        showTitle = title,
                        year = year,
                        seasonNumber = videoType.season.number,
                        episodeNumber = videoType.number,
                    )
                }
            } else emptyList()
        } catch (e: Exception) {
            Log.d("MovixProvider", "Coflix backup failed for $id: ${e.message}")
            emptyList()
        }
        if (coflixBackup.isNotEmpty()) {
            Log.d("MovixProvider", "+ Coflix backup : ${coflixBackup.size} sources")
            servers.addAll(coflixBackup)
        }

        return servers
    }

    /**
     * Scrape DIRECT de flemmix.win : recherche par titre TMDB → meilleur match →
     * getServers. Les serveurs sont préfixés "Wiflix · " pour identification.
     * Pas de prefix d'ID (les URLs embed passent par le même extracteur que Movix).
     */
    internal suspend fun fetchWiflixDirectBackup(id: String, videoType: Video.Type): List<Video.Server> {
        // Timeout global généreux : le progressif affiche déjà les serveurs natifs Movix,
        // donc on peut laisser 60s à Wiflix (init CF + search + getServers).
        return withTimeoutOrNull(60_000) {
            fetchWiflixDirectBackupInner(id, videoType)
        } ?: run {
            Log.w("MovixProvider", "Wiflix direct: GLOBAL timeout 60s")
            emptyList()
        }
    }

    /** Log Wiflix direct backup (Log.d, pas d'écriture fichier). */
    private fun wfLog(msg: String) {
        Log.d("WiflixDirect", msg)
    }

    /**
     * Scrape HTTP direct de flemmix.win — contourne complètement WiflixProvider
     * et son mécanisme CF/Retrofit/WebView qui timeout. On fait :
     *   1. POST search sur flemmix.win (DLE standard)
     *   2. Parse les <a> avec href film-en-streaming/ ou serie/
     *   3. GET la page du match
     *   4. Parse les loadVideo('url', this) → serveurs
     * Tout en OkHttp simple, ~2-3 secondes max.
     */
    private suspend fun fetchWiflixDirectBackupInner(id: String, videoType: Video.Type): List<Video.Server> =
        withContext(Dispatchers.IO) {
            wfLog("=== fetchWiflixDirectBackup START id=$id type=${videoType::class.simpleName} ===")
            val tmdbIdInt = when (videoType) {
                is Video.Type.Movie -> id.toIntOrNull()
                is Video.Type.Episode -> id.substringBefore("-").toIntOrNull()
            } ?: run {
                wfLog("cannot parse tmdbId from '$id'")
                return@withContext emptyList()
            }

            val tmdbDetails = resolveTmdbTitleYear(tmdbIdInt, videoType) ?: run {
                wfLog("TMDB resolve failed for $tmdbIdInt")
                return@withContext emptyList()
            }
            val (title, year) = tmdbDetails

            // Titre original aussi (ex: "Swapped" vs "Aventures croisées")
            val originalTitle = try {
                when (videoType) {
                    is Video.Type.Movie -> TMDb3.Movies.details(movieId = tmdbIdInt, language = "fr-FR").originalTitle
                    is Video.Type.Episode -> TMDb3.TvSeries.details(seriesId = tmdbIdInt, language = "fr-FR").originalName
                }
            } catch (_: Exception) { null }

            val queries = listOfNotNull(title, originalTitle?.takeIf { it != title }).distinct()
            wfLog("queries=$queries (year=$year)")

            // Résoudre le domaine actif de Wiflix (peut changer)
            val wiflixBase = try { WiflixProvider.baseUrl.trimEnd('/') } catch (_: Exception) { "https://flemmix.win" }
            wfLog("wiflixBase=$wiflixBase")

            // 2026-06-13 (user "toujours pas de wiflix" apres clear data) :
            //   flemmix.city sert un challenge CF aux POST search via HTTP brut
            //   (= reponse 18 bytes). Le main provider Wiflix a un cookie
            //   cf_clearance dans son cookie jar partage (NetworkClient.default)
            //   apres son 1er bypass WebView. On reutilise ce client pour
            //   herit du cookie CF, sans dupliquer la logique de bypass.
            val client = com.streamflixreborn.streamflix.utils.NetworkClient.default
                .newBuilder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            // 2026-06-13 (user "pas de serveur wiflix" - refactor autonome, ne
            //   touche PAS au main provider) :
            //   flemmix.city sert un CF challenge sur les POST search direct
            //   (= 18 bytes). Le scraping HTTP brut ne peut pas le passer. On
            //   utilise WebViewResolver (utils/) en silent mode qui :
            //     1) load l'URL dans un WebView Android (= JS execute, CF gere
            //        tout seul, cookies poses dans CookieManager)
            //     2) detecte le challenge + attend la resolution (timeout 30s
            //        en silent mode = fail-fast pas de dialog au user)
            //     3) retourne le HTML resolu
            //   DLE accepte search en GET aussi (= ?do=search&...) donc on
            //   construit l'URL search en GET au lieu du POST direct.
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance

            /** 2026-06-13 (user "tu touches pas a l'officiel provider" +
             *  "toujours pas wiflix") : WebViewResolver silent mode fail-fast
             *  apres 3 polls (~1.5s) avec "<!-- silent fail -->" si pas de
             *  content reconnu. flemmix.city CF challenge prend 5-10s a se
             *  resoudre → mon code echouait toujours. Helper inline : mini
             *  WebView Android programmatique avec attente patiente de 15s
             *  (= laisse le temps au CF de se resoudre). Le UA est celui qu'un
             *  vrai Chrome poserait → CF accepte. Pas de dialog visible,
             *  cleanup garanti. */
            suspend fun getViaBypass(url: String, tag: String): String {
                val t0 = System.currentTimeMillis()
                return try {
                    val html = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        kotlinx.coroutines.withTimeoutOrNull(20_000) {
                            kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                                val webView = android.webkit.WebView(ctx)
                                webView.settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 " +
                                        "Mobile Safari/537.36"
                                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                }
                                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
                                var pollRunnable: Runnable? = null

                                fun finish(html: String) {
                                    if (!resolved.compareAndSet(false, true)) return
                                    handler.removeCallbacksAndMessages(null)
                                    try {
                                        webView.stopLoading()
                                        webView.destroy()
                                    } catch (_: Throwable) {}
                                    if (cont.isActive) cont.resume(html) {}
                                }

                                // Poll toutes les 800ms, accepte des qu'on detecte
                                //   du contenu reconnaissable (mov-t / posterimg /
                                //   serie-en-streaming / saison-complete / film-en-streaming).
                                //   Sinon on attend jusqu'au timeout global (20s).
                                val poll = object : Runnable {
                                    var count = 0
                                    override fun run() {
                                        if (resolved.get()) return
                                        count++
                                        webView.evaluateJavascript(
                                            "(function(){return document.documentElement.outerHTML;})();"
                                        ) { rawJs ->
                                            val clean = rawJs?.trim()?.removeSurrounding("\"")
                                                ?.replace("\\u003C", "<")
                                                ?.replace("\\\"", "\"")
                                                ?.replace("\\n", "\n") ?: ""
                                            val hasContent =
                                                clean.contains("mov-t") ||
                                                clean.contains("posterimg") ||
                                                clean.contains("serie-en-streaming") ||
                                                clean.contains("film-en-streaming") ||
                                                clean.contains("saison-complete") ||
                                                clean.contains("loadVideo")
                                            if (hasContent && clean.length > 5000) {
                                                finish(clean)
                                                return@evaluateJavascript
                                            }
                                            if (count < 25) {
                                                handler.postDelayed(this, 800)
                                            }
                                        }
                                    }
                                }
                                pollRunnable = poll

                                webView.webViewClient = object : android.webkit.WebViewClient() {
                                    override fun onPageFinished(view: android.webkit.WebView?, currentUrl: String?) {
                                        if (resolved.get()) return
                                        // Premier check apres 500ms (= laisse le DOM
                                        // se stabiliser + CF auto-resolve si fast)
                                        handler.postDelayed(poll, 500)
                                    }
                                }
                                webView.loadUrl(url)
                                cont.invokeOnCancellation { finish("") }
                            }
                        } ?: "<html><!-- timeout 20s --></html>"
                    }
                    wfLog("$tag bypass HTML len=${html.length} in ${System.currentTimeMillis() - t0}ms")
                    html
                } catch (e: Exception) {
                    wfLog("$tag bypass exception: ${e.message}")
                    ""
                }
            }

            // 2026-06-13 (user "wilflix backup n'apparaît pas" + capture
            //   flemmix.city/saison-complete/27119-from-2022-saison-2.html) :
            //   le nouveau domaine flemmix.city sert AUSSI les séries au format
            //   /saison-complete/<id>-<slug>.html (= un layout différent de
            //   /serie-en-streaming/). L'ancien filtre rejetait 100% des
            //   resultats saison-complete → pas de backup Wiflix pour les séries
            //   format saison. On accepte les deux slugs.
            val typeSlugs: List<String> = when (videoType) {
                is Video.Type.Movie -> listOf("film-en-streaming/")
                is Video.Type.Episode -> listOf("serie-en-streaming/", "saison-complete/")
            }

            for (query in queries) {
                try {
                    // ① 2026-06-13 (user autorisation : "oui si ca casse rien") :
                    //    on remplace le WebView search bypass (qui ramait
                    //    20s et echouait sur le bot shield de flemmix.city) par
                    //    un appel a WiflixProvider.searchRaw — nouvelle methode
                    //    publique qui delegue au main provider sa logique CF
                    //    + WebView bypass deja eprouvee + retourne les hrefs
                    //    BRUTS (sans filtrer film/serie/saison-complete).
                    //    Avantages :
                    //      - bypass CF/bot shield 100% gere par le main
                    //      - From en saison-complete passe (= n'est plus rejete
                    //        par le filtre serie-en-streaming/vf du main search)
                    //      - zero code dupplique pour le challenge handling
                    // 2026-06-13 (user "From apparait dans la home Wiflix mais
                    //   la search ramene rien" + capture site Wiflix navigateur OK) :
                    //   Le bot shield bloque le search depuis l'app. MAIS la
                    //   home Wiflix charge sans probleme et liste From + autres
                    //   series avec leur URL complete. On a un cache
                    //   (WiflixUrlCache) qui se peuple opportunistement a chaque
                    //   page Wiflix browse par l'user. On regarde D'ABORD ce
                    //   cache → si match, on a directement l'URL → bypass total
                    //   du search bot-shieldé.
                    val cachedUrl = try {
                        com.streamflixreborn.streamflix.utils.WiflixUrlCache
                            .lookup(ctx, query)
                    } catch (_: Throwable) { null }
                    val rawResults: List<Pair<String, String>> = if (cachedUrl != null) {
                        wfLog("URL cache HIT pour '$query' → $cachedUrl (skip searchRaw)")
                        listOf(cachedUrl to query)
                    } else {
                        wfLog("URL cache MISS pour '$query' → fallback searchRaw")
                        wfLog("searchRaw '$query' via WiflixProvider...")
                        val t0 = System.currentTimeMillis()
                        val results = try {
                            kotlinx.coroutines.withTimeoutOrNull(20_000) {
                                WiflixProvider.searchRaw(query, 1)
                            } ?: emptyList()
                        } catch (e: Exception) {
                            wfLog("searchRaw exception: ${e.message}")
                            emptyList()
                        }
                        wfLog("searchRaw '$query' returned ${results.size} results in ${System.currentTimeMillis() - t0}ms")
                        results
                    }
                    if (rawResults.isEmpty()) continue

                    // Filtrer par type (film ou serie) en utilisant typeSlugs
                    // (= serie-en-streaming/ OU saison-complete/ pour les Episodes).
                    val typed = rawResults.filter { (href, _) ->
                        typeSlugs.any { slug -> href.contains(slug) }
                    }
                    wfLog("${typed.size} after type filter ($typeSlugs)")

                    // Wrapper local pour s'adapter a l'ancienne API regex
                    // (anciennement MatchResult, maintenant Pair<href, title>).
                    val results: List<Pair<String, String>> = typed

                    // 2026-06-02 : matching qui préfère le titre LE PLUS PROCHE
                    //   de la query (= moins de suffixes parasites). Avant on
                    //   prenait `firstOrNull` → pour "Spider-Noir" on tombait sur
                    //   "Spider-Noir Version Noir et Blanc" (VOSTFR) au lieu de
                    //   "Spider-Noir" tout court (VF). On classe par diff de
                    //   longueur normQuery vs normResult — le candidat qui a le
                    //   moins de caractères additionnels gagne.
                    // 2026-06-03 (user "FS Ah le bon film lui" — les serveurs
                    //   FS jouent le bon film, les Wiflix · ... lancent Marvel.
                    //   Cause : (1) le fallback `?: typed.firstOrNull()` prenait
                    //   N'IMPORTE QUEL résultat Wiflix si aucun candidate ne
                    //   matchait → on jouait un random film. (2) `contains` était
                    //   trop laxiste : query "Scary Movie" matche "Scary Movie 5",
                    //   "Scary Movie The Sequel", etc. → mauvais film.
                    //   Maintenant : (a) match strict = égalité de titre normalisé
                    //   OU le résultat commence par la query suivie d'un séparateur ;
                    //   (b) PLUS de fallback firstOrNull → mieux vaut 0 backup que
                    //   le mauvais film ; (c) validation d'année si possible.
                    val normQuery = normalizeTitle(query)
                    fun isStrictMatch(normResult: String): Boolean {
                        if (normResult == normQuery) return true
                        // Résultat commence par la query + séparateur (espace, tiret, parenthèse)
                        if (normResult.startsWith("$normQuery ") ||
                            normResult.startsWith("$normQuery-") ||
                            normResult.startsWith("$normQuery(")) return true
                        // Query commence par le résultat + séparateur (cas inverse)
                        if (normQuery.startsWith("$normResult ") ||
                            normQuery.startsWith("$normResult-") ||
                            normQuery.startsWith("$normResult(")) return true
                        return false
                    }
                    val candidates = typed.mapNotNull { (href, title) ->
                        val rawTitle = title.replace(Regex("<[^>]+>"), "")
                        val normResult = normalizeTitle(rawTitle)
                        if (isStrictMatch(normResult)) {
                            Triple(href to rawTitle, normResult, kotlin.math.abs(normResult.length - normQuery.length))
                        } else null
                    }
                    val match = candidates.minByOrNull { it.third }?.first
                    if (match == null) {
                        wfLog("STRICT match failed for '$query' (had ${typed.size} typed results) — drop backup (mieux que mauvais film)")
                        continue
                    }

                    val matchUrl = match.first
                    val matchTitle = match.second.trim()
                    wfLog("match '$matchTitle' => $matchUrl")

                    // ③ GET la page du film/episode via OkHttp simple.
                    //    2026-06-13 : verifie en direct que flemmix.city/
                    //    saison-complete/...html (= la page produit) n'a PAS
                    //    de bot shield (= retourne 86KB HTML complet avec
                    //    loadVideo embeds). Seul l'endpoint /index.php?do=search
                    //    est bot-shielded, on le contourne via WiflixProvider
                    //    .searchRaw au-dessus. La page produit elle-meme = OK.
                    val t1 = System.currentTimeMillis()
                    val pageReq = Request.Builder()
                        .url(matchUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
                        .header("Referer", "$wiflixBase/")
                        .build()
                    val pageHtml = try {
                        val resp = client.newCall(pageReq).execute()
                        val body = resp.body?.string() ?: ""
                        resp.close()
                        wfLog("page HTTP ${resp.code} len=${body.length} in ${System.currentTimeMillis() - t1}ms")
                        body
                    } catch (e: Exception) {
                        wfLog("page GET exception: ${e.message}")
                        ""
                    }
                    if (pageHtml.length < 500) {
                        wfLog("page empty/short → skip")
                        continue
                    }

                    // ④ Pour les SÉRIES, chaque épisode a DEUX blocs sur flemmix.team :
                    //    <div class="ep{N}vf"> = serveurs VF (Lecteur 1/2/3)
                    //    <div class="ep{N}vs"> = serveurs VOSTFR (Lecteur 1/2/3)
                    //    Avant on ne lisait que "vs" → 100 % VOSTFR, jamais VF.
                    //    Maintenant : VF prioritaire, VOSTFR en fallback ET ajouté
                    //    en complément si dispo (l'user voit les 2 packs taggés).
                    //    Pour les FILMS : on prend tout (pas de wrapper epXxx).
                    // 2026-06-02 : accepte loadVideo('URL') OU loadVideo('URL', this) ou loadVideo('URL', autre)
//   Wiflix utilise les deux formats : séries = sans 2e arg, films = `, this`.
val serverPattern = Regex("""onclick="loadVideo\('([^']+)'[^)]*\)"[^>]*>\s*<span[^>]*>([^<]+)</span>""")

                    fun extractEpBlock(suffix: String): String? {
                        val epNum = (videoType as Video.Type.Episode).number
                        val pattern = Regex(
                            """<div\s+class="ep${epNum}${suffix}"[^>]*>(.*?)</div>""",
                            RegexOption.DOT_MATCHES_ALL
                        )
                        return pattern.find(pageHtml)?.groupValues?.get(1)
                    }

                    val allServers = mutableListOf<Pair<String, String>>()  // (url, name)
                    val isEpisode = videoType is Video.Type.Episode
                    if (isEpisode) {
                        // 2026-06-02 (user request) : on ne lit QUE le bloc VF.
                        //   Avant on émettait aussi les VOSTFR mais le user n'en
                        //   veut pas en backup ("Et tu retires aussi Wiflix VOSTFR").
                        //   Si pas de VF dispo → fallback VOSTFR pour ne pas perdre
                        //   complètement la source (sinon série anglais récente = 0 backup).
                        extractEpBlock("vf")?.let { blockHtml ->
                            wfLog("scoped to ep${(videoType as Video.Type.Episode).number}vf (len=${blockHtml.length})")
                            serverPattern.findAll(blockHtml).forEach { m ->
                                allServers.add(m.groupValues[1] to "${m.groupValues[2].trim()} [VF]")
                            }
                        }
                        // VOSTFR uniquement en fallback si VF absent.
                        if (allServers.isEmpty()) {
                            extractEpBlock("vs")?.let { blockHtml ->
                                wfLog("VF absent, fallback ep${(videoType as Video.Type.Episode).number}vs (len=${blockHtml.length})")
                                serverPattern.findAll(blockHtml).forEach { m ->
                                    allServers.add(m.groupValues[1] to "${m.groupValues[2].trim()} [VOSTFR]")
                                }
                            }
                        }
                        if (allServers.isEmpty()) {
                            wfLog("WARN: ni ep${(videoType as Video.Type.Episode).number}vf ni vs trouvé, fallback full page")
                            serverPattern.findAll(pageHtml).forEach { m ->
                                allServers.add(m.groupValues[1] to m.groupValues[2].trim())
                            }
                        }
                    } else {
                        // Film : pas de wrapper, parse la page entière
                        serverPattern.findAll(pageHtml).forEach { m ->
                            allServers.add(m.groupValues[1] to m.groupValues[2].trim())
                        }
                    }

                    wfLog("parsed ${allServers.size} servers from scoped HTML (VF first if present)")

                    if (allServers.isEmpty()) continue

                    // Dédup par URL embed + filtre robuste contre faux serveurs
                    //   (2026-06-03 user "y a un WIFLIX SAVE ça existe même pas")
                    //   Le regex `loadVideo\('([^']+)'` capture aussi des URLs
                    //   non-HTTP (javascript:void(0), ancres, hashes, vides) qui
                    //   apparaissent comme faux serveurs (ex: bouton "Save" du
                    //   site Wiflix). On exige une vraie URL HTTP(S) et un nom
                    //   qui ressemble à un host de streaming (pas un mot anglais
                    //   isolé comme "Save", "Download", etc.).
                    val seenUrls = HashSet<String>()
                    val knownNonServerNames = setOf(
                        "save", "download", "telecharger", "sauvegarder",
                        "favoris", "favorite", "share", "partager",
                    )
                    val result = allServers.mapNotNull { (embedUrl, name) ->
                        // Filtre 1 : URL doit être HTTP/S valide
                        if (embedUrl.isBlank() ||
                            !embedUrl.startsWith("http", ignoreCase = true)) {
                            wfLog("DROPPED non-http server: name='$name' url='$embedUrl'")
                            return@mapNotNull null
                        }
                        // Filtre 2 : nom ne doit pas être un libellé bouton
                        val cleanName = name.lowercase().trim()
                            .removeSuffix(" [vf]").removeSuffix(" [vostfr]").trim()
                        if (cleanName in knownNonServerNames) {
                            wfLog("DROPPED button-named server: name='$name' url='$embedUrl'")
                            return@mapNotNull null
                        }
                        if (!seenUrls.add(embedUrl)) return@mapNotNull null
                        Video.Server(
                            id = "wiflix_direct__${seenUrls.size - 1}_${name.lowercase().replace(" ", "_").replace("[","").replace("]","")}",
                            name = "Wiflix · $name",
                            src = embedUrl,
                        )
                    }
                    wfLog("SUCCESS: ${result.size} Wiflix servers (after dedup) !")
                    return@withContext result

                } catch (e: Exception) {
                    wfLog("FAILED for '$query': ${e::class.simpleName}: ${e.message}")
                }
            }

            wfLog("=== NO RESULTS after all queries ===")
            emptyList()
        }

    /**
     * Scrape DIRECT de fs17.lol : recherche par titre TMDB → meilleur match →
     * getServers. Les serveurs sont préfixés "FS · " pour identification.
     */
    internal suspend fun fetchFrenchStreamDirectBackup(id: String, videoType: Video.Type): List<Video.Server> {
        return withTimeoutOrNull(60_000) {
            fetchFrenchStreamDirectBackupInner(id, videoType)
        } ?: run {
            Log.w("MovixProvider", "FS direct: GLOBAL timeout 60s")
            emptyList()
        }
    }

    private suspend fun fetchFrenchStreamDirectBackupInner(id: String, videoType: Video.Type): List<Video.Server> {
        val tmdbIdInt = when (videoType) {
            is Video.Type.Movie -> id.toIntOrNull()
            is Video.Type.Episode -> id.substringBefore("-").toIntOrNull()
        } ?: run {
            Log.w("MovixProvider", "FS direct: cannot parse tmdbId from '$id'")
            return emptyList()
        }

        val tmdbDetails = resolveTmdbTitleYear(tmdbIdInt, videoType) ?: run {
            Log.w("MovixProvider", "FS direct: TMDB resolve failed for $tmdbIdInt")
            return emptyList()
        }
        val (title, year) = tmdbDetails

        val originalTitle = try {
            when (videoType) {
                is Video.Type.Movie -> TMDb3.Movies.details(movieId = tmdbIdInt, language = "fr-FR").originalTitle
                is Video.Type.Episode -> TMDb3.TvSeries.details(seriesId = tmdbIdInt, language = "fr-FR").originalName
            }
        } catch (_: Exception) { null }

        // 2026-06-13 (user "FS Voe HD pas bon backup sur cloudstream / From
        //   S2E5 mauvaise serie") :
        //   FrenchStream indexe souvent les series PAR SAISON (= "FROM -
        //   Saison 1", "FROM - Saison 2", "FROM - Saison 3"...) plutot que par
        //   show. Avec la query "From" + matching contains() permissif, on
        //   piochait le 1er TvShow contenant "from" = "FROM - Saison 4" (= la
        //   plus recente, en haut de la SERP) au lieu de la S2 demandee. Puis
        //   getServers cherchait l'ep 5 sur la page de S4 → mauvais contenu.
        //   Strategie : pour les Episodes on tente D'ABORD une query
        //   saisonnee "Title Saison N" avec match EXACT normalise (= verifie
        //   qu'on tombe sur la bonne saison). Si exact-match trouve → on
        //   l'utilise et on s'arrete. Sinon → on retombe sur le matching
        //   permissif d'origine (zero regression pour les series mono-show).
        val baseQueries = listOfNotNull(title, originalTitle?.takeIf { it != title }).distinct()
        val seasonNum = (videoType as? Video.Type.Episode)?.season?.number
        val seasonedQueries = if (seasonNum != null && seasonNum > 0) {
            baseQueries.map { "$it Saison $seasonNum" }
        } else emptyList()
        val queries = (seasonedQueries + baseQueries).distinct()
        val seasonedSet = seasonedQueries.toSet()
        Log.d("MovixProvider", "FS direct: queries=$queries (year=$year)")

        for (query in queries) {
            try {
                Log.d("MovixProvider", "FS direct: searching '$query'...")
                val searchResults = FrenchStreamProvider.search(query, 1)
                Log.d("MovixProvider", "FS direct: search '$query' returned ${searchResults.size} items")

                val typed = searchResults.filter { item ->
                    when (videoType) {
                        is Video.Type.Movie -> item is Movie
                        is Video.Type.Episode -> item is TvShow
                    }
                }

                val isSeasonedQuery = query in seasonedSet
                val match = if (isSeasonedQuery) {
                    // Pour la query saisonnee, on EXIGE match exact normalise
                    // (= "from saison 2" = "from saison 2"). Si rien d'exact,
                    // on passe a la query suivante (= base query non saisonnee
                    // avec matching permissif) → on ne pioche JAMAIS la
                    // mauvaise saison ici.
                    val normQuery = normalizeTitle(query)
                    typed.firstOrNull { item ->
                        val itemTitle = when (item) {
                            is Movie -> item.title
                            is TvShow -> item.title
                            else -> ""
                        }
                        normalizeTitle(itemTitle) == normQuery
                    }
                } else {
                    // 2026-07-07 : matching strict via BackupRegistry.titleMatches
                    // (remplace contains permissif + fallback firstOrNull qui prenait
                    // n'importe quel 1er résultat = faux film).
                    typed.firstOrNull { item ->
                        val itemTitle = when (item) {
                            is Movie -> item.title
                            is TvShow -> item.title
                            else -> ""
                        }
                        com.streamflixreborn.streamflix.utils.BackupRegistry.titleMatches(itemTitle, query)
                    }
                }

                if (match == null) {
                    Log.d("MovixProvider", "FS direct: no match for '$query'" +
                        if (isSeasonedQuery) " (seasoned, exigeait match exact)" else "")
                    continue
                }
                Log.d("MovixProvider", "FS direct: matched '${(match as? TvShow)?.title ?: (match as? Movie)?.title}' for query='$query'")

                val matchId = when (match) {
                    is Movie -> match.id
                    is TvShow -> match.id
                    else -> continue
                }
                Log.d("MovixProvider", "FS direct: match found id=$matchId")

                Log.d("MovixProvider", "FS direct: fetching servers for $matchId...")
                val servers = FrenchStreamProvider.getServers(matchId, videoType)
                if (servers.isEmpty()) {
                    Log.w("MovixProvider", "FS direct: getServers returned empty for $matchId")
                    continue
                }
                // 2026-06-13 (user "FS HD pa bon" + capture serveurs From S2E5 :
                //   "FS · Vidzy (VF - HD)" en vert joue mauvais contenu) :
                //   FrenchStreamProvider.getServers retourne nativeServers (sx.php
                //   = 6 sources correctes scoped a E5) + cloudstreamBackup +
                //   movixBackup + movieboxBackup. Quand FS est appele comme
                //   BACKUP de Cloudstream, les sources Movix/Cloudstream/Moviebox
                //   ramenes par FS sont DUPLIQUEES avec celles que Cloudstream
                //   collecte deja en parallele. Pire : le pipeline Movix natif
                //   inclut un endpoint /api/search?title=FROM qui matche par
                //   titre generique = sources d'autres shows polluees, taggees
                //   "(VF - HD)" car quality default Movix. Ces "FS · X (VF - HD)"
                //   jouent du mauvais contenu.
                //   Fix : ne garder que les NATIFS FS (= "fs_ajax_..." pose par
                //   fetchPlayersFromAjax) dans le wrap "FS · ...". Les backups
                //   restent dispo pour le user via Cloudstream/Movix direct
                //   (zero duplication + zero pollution).
                // 2026-06-21 (user "normalement si tu prends les backups FS
                //   actuels on est censé avoir [Vidzy/Uqload/Voe/Netu]") :
                //   ÉLARGIR le filtre — FrenchStreamProvider produit aussi des
                //   IDs `fs_ep<N>_*` via le pipeline DIRECT PARSE (ligne 1170
                //   FrenchStreamProvider) + `fs_player_*` en fallback movie.
                //   Sans ces IDs, FROM S2E1 ramenait 0 backup → l'user n'avait
                //   que les "FS · Voe (VF - HD)" pollués de Movix native
                //   fstream-*. On garde tout `fs_*` (= tous formats natifs FS),
                //   on exclut seulement les backups upstream (cs_*, movix_*, etc).
                val nativeFsOnly = servers.filter { it.id.startsWith("fs_") }
                Log.d("MovixProvider", "FS direct: ${servers.size} servers total, ${nativeFsOnly.size} natifs FS retenus (filtre fs_*)")
                if (nativeFsOnly.isEmpty()) {
                    Log.w("MovixProvider", "FS direct: 0 natif FS pour $matchId (backups exclus exprès)")
                    continue
                }
                return nativeFsOnly.map { srv ->
                    val name = if (srv.name.startsWith("FS")) srv.name else "FS · ${srv.name}"
                    srv.copy(id = "fs_direct__${srv.id}", name = name)
                }
            } catch (e: Exception) {
                Log.w("MovixProvider", "FS direct: failed for '$query': ${e.message}")
            }
        }

        Log.d("MovixProvider", "FS direct: no results after all queries")
        return emptyList()
    }

    /** Résout le titre et l'année depuis TMDB. Réutilisé par les backups directs. */
    private suspend fun resolveTmdbTitleYear(tmdbIdInt: Int, videoType: Video.Type): Pair<String, Int?>? = try {
        when (videoType) {
            is Video.Type.Movie -> {
                val det = TMDb3.Movies.details(movieId = tmdbIdInt, language = "fr-FR")
                (det.title.takeIf { it.isNotBlank() } ?: det.originalTitle) to
                    det.releaseDate?.take(4)?.toIntOrNull()
            }
            is Video.Type.Episode -> {
                val det = TMDb3.TvSeries.details(seriesId = tmdbIdInt, language = "fr-FR")
                (det.name.takeIf { it.isNotBlank() } ?: det.originalName) to
                    det.firstAirDate?.take(4)?.toIntOrNull()
            }
        }
    } catch (e: Exception) {
        Log.w("MovixProvider", "TMDB resolve failed: ${e.message}")
        null
    }

    /** Normalise un titre pour la comparaison fuzzy (minuscule, sans accents, sans ponctuation). */
    private fun normalizeTitle(title: String): String =
        java.text.Normalizer.normalize(title.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")

    // 2026-05-21 (user "affiche ce qu'il récupère au fur et à mesure, les autres
    //   arrivent ensuite sans attendre") : version PROGRESSIVE de getServers.
    //   Émet les natifs Movix dès qu'ils sont prêts, PUIS les backups, sans
    //   attendre que tout soit fini. Natifs et backups tournent en parallèle :
    //   le premier prêt est envoyé en premier (en général les natifs). Le
    //   ViewModel affiche le 1er lot puis ré-ordonne par bucket de langue.
    override fun getServersProgressive(
        id: String,
        videoType: Video.Type,
    ): Flow<List<Video.Server>> = channelFlow {
        initializeService()
        // 2026-06-02 : état partagé cross-emits pour dédup + disambiguation.
        //   Avant : chaque batch (native, backups, wiflix, fs) émis séparément
        //   sans coordination → doublons par src + noms identiques dans le picker.
        //   Maintenant : seenSrc/nameCount partagés par tous les launch{} en
        //   utilisant Mutex pour synchroniser les modifications concurrentes.
        val seenSrc = HashSet<String>()
        val nameCount = mutableMapOf<String, Int>()
        val mutex = kotlinx.coroutines.sync.Mutex()
        // 2026-06-13 : track si un batch alive a déjà été émis. Si oui, on
        //   peut émettre les batches all-dead suivants en fin de liste
        //   (= ne casse pas l'auto-play : il s'est déjà fixé sur un alive).
        var hasEmittedAlive = false

        // 2026-06-02 : pré-calcul des extracteurs en échec récent. On ne les
        //   SUPPRIME PAS (l'user a explicitement demandé de garder Videasy &
        //   autres : "c'est pas parce qu'il fonctionne pas sur cette vidéo
        //   qu'il va pas fonctionner sur les autres"). On les DÉPRIORISE en
        //   fin de batch pour que l'auto-play ne tombe pas dessus en 1er.
        val deadExtractors = try {
            com.streamflixreborn.streamflix.utils.ExtractorFailureTracker
                .getFailures()
                .filter { it.count >= 5 }
                .map { it.name.lowercase() }
                .toSet()
        } catch (e: Exception) { emptySet() }
        if (deadExtractors.isNotEmpty()) {
            Log.d("MovixProvider", "Progressive: extracteurs déprioritisés (fin de liste) = $deadExtractors")
        }

        fun isDeadServer(srv: Video.Server): Boolean {
            val srvNameLower = srv.name.lowercase()
            val extractorName = com.streamflixreborn.streamflix.utils.ExtractorRanker
                .resolveExtractorName(srv)?.lowercase()
            return deadExtractors.any { dead ->
                srvNameLower.contains(dead) || (extractorName != null && extractorName == dead)
            }
        }

        suspend fun emitDeduped(batch: List<Video.Server>) {
            val cleaned = mutex.withLock {
                batch.mapNotNull { srv ->
                    val key = srv.src.trim()
                    if (key.isNotBlank() && !seenSrc.add(key)) return@mapNotNull null
                    val cnt = (nameCount[srv.name] ?: 0) + 1
                    nameCount[srv.name] = cnt
                    if (cnt > 1) srv.copy(name = "${srv.name} #$cnt") else srv
                }
            }
            if (cleaned.isNotEmpty()) {
                // Sort : VF (alive) → VOSTFR (alive) → VO (alive) → ANY (dead, last)
                val sorted = sortServersByLanguage(cleaned)
                val (alive, dead) = sorted.partition { !isDeadServer(it) }
                // 2026-06-13 (user "tu as pas mis un filtre qui masque les
                //   serveurs sans faire exprès") : on émet TOUJOURS, même les
                //   batches all-dead. La protection hasEmittedAlive masquait
                //   trop (Wiflix VOE-only invisible). Auto-play sur dead est
                //   couvert par startAwaitMoreServers côté fragments.
                if (alive.isNotEmpty() || dead.isNotEmpty()) {
                    hasEmittedAlive = hasEmittedAlive || alive.isNotEmpty()
                    val emitted = alive + dead
                    val names = emitted.joinToString(" | ") { it.name }
                    Log.w("ServDiag", "MovixProvider emit ${emitted.size} (alive=${alive.size} dead=${dead.size}) : $names")
                    send(emitted)
                }
            }
        }

        launch {
            try {
                // 2026-07-07 : chaque endpoint Movix émet DÈS qu'il répond (onPartial → emitDeduped),
                //   plus d'attente du plus lent. Le lot complet n'est plus ré-émis (déjà envoyé).
                val native = fetchNativeMovixServers(id, videoType) { batch -> emitDeduped(batch) }
                Log.w("MovixProvider", "[NATIF_PROGRESSIF] Movix a émis ${native.size} sources au fil de l'eau")
            } catch (e: Exception) {
                Log.w("MovixProvider", "Progressive native failed: ${e.message}")
            }
        }
        // 2026-06-13 (user "active seulement les sources de wiflix, vu qu'il
        //   y en a déjà pas mal comme ça") :
        //   - fetchMovixBackups : OFF (= autres sources internes Movix)
        //   - fetchWiflixDirectBackup : ON (= scraping direct Wiflix)
        //   - fetchFrenchStreamDirectBackup : OFF (= scraping direct FS)
        // 2026-06-21 (user "FrenchStream n'est plus là maintenant") :
        //   RÉACTIVE fetchFrenchStreamDirectBackup. Le natif Movix fstream-*
        //   produit fréquemment "FS · X (VF - HD)" pollués (= URLs d'autres
        //   shows). Le scraping live de FrenchStream est l'unique source
        //   fiable pour avoir les VRAIS Vidzy/Uqload/Voe/Netu. Sans lui,
        //   l'user n'a aucune source FS correcte pour les shows mal mappés.
        // 2026-07-04 : backups inline DÉSACTIVÉS → registre central.
        if (!skipBackupsForBackupCall && !com.streamflixreborn.streamflix.utils.BackupRegistry.INLINE_BACKUPS_DISABLED) {
            launch {
                try {
                    val wf = fetchWiflixDirectBackup(id, videoType)
                    if (wf.isNotEmpty()) {
                        emitDeduped(wf)
                        com.streamflixreborn.streamflix.utils.BackupAlertTracker
                            .recordSuccess("Wiflix", wf.size)
                    } else {
                        com.streamflixreborn.streamflix.utils.BackupAlertTracker
                            .recordSuspiciousEmpty("Wiflix", "0 sources pour $id")
                    }
                } catch (e: Exception) {
                    Log.w("MovixProvider", "Progressive Wiflix direct failed: ${e.message}")
                    com.streamflixreborn.streamflix.utils.BackupAlertTracker
                        .recordFailure("Wiflix", e)
                }
            }
            launch {
                try {
                    val fs = fetchFrenchStreamDirectBackup(id, videoType)
                    if (fs.isNotEmpty()) {
                        emitDeduped(fs)
                        com.streamflixreborn.streamflix.utils.BackupAlertTracker
                            .recordSuccess("FrenchStream", fs.size)
                    } else {
                        com.streamflixreborn.streamflix.utils.BackupAlertTracker
                            .recordSuspiciousEmpty("FrenchStream", "0 sources pour $id")
                    }
                } catch (e: Exception) {
                    Log.w("MovixProvider", "Progressive FS direct failed: ${e.message}")
                    com.streamflixreborn.streamflix.utils.BackupAlertTracker
                        .recordFailure("FrenchStream", e)
                }
            }
            // 2026-06-21 (user "le backup Coflix n'apparaît pas alors qu'il est
            //   bien là-bas, il y a au moins 3 serveurs de bons par épisode") :
            //   Coflix backup réactivé dans progressive. fetchMovixBackups (qui
            //   contient Coflix + Cloudstream + Moviebox) était OFF, mais user
            //   veut au moins Coflix actif. Coflix utilise CoflixSourceProvider
            //   qui auto-discover le mirror actif via coflix.blog → toujours
            //   à jour quand Coflix migre de domaine (= actuellement coflix.band).
            launch {
                try {
                    val coflix = fetchCoflixBackup(id, videoType)
                    if (coflix.isNotEmpty()) emitDeduped(coflix)
                } catch (e: Exception) {
                    Log.w("MovixProvider", "Progressive Coflix backup failed: ${e.message}")
                }
            }
            // 2026-06-21 v3 (user "non c'est ça que tu dois faire, tu dois
            //   pas les désactiver surtout pas, tu dois juste faire en sorte
            //   que les serveurs qui sont déjà arrivés s'affichent et ça pour
            //   tous les providers, et le reste arrive ensuite peu importe,
            //   faut juste que ça soit pas bloqué. Et si les serveurs déjà
            //   arrivés sont cassés ou fonctionnent pas faut que ça attende
            //   les autres jusqu'à tant que ça fonctionne, faut pas que la
            //   page se ferme") :
            //   Tous les backups ON. Cloudstream + Moviebox sont les plus
            //   lourds (Cloudstream → MovieBox+ /resource sur 4 hosts +
            //   Nakios ; Moviebox → /search sur 8 hosts). Wrap chacun dans
            //   withTimeoutOrNull(25_000) → si un backup hang, il rend la
            //   main proprement à 25s sans bloquer les autres NI fermer
            //   le channelFlow. Le picker reste donc ouvert (attend toutes
            //   les sources même si les premières échouent à lire).
            launch {
                try {
                    val cs = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                        fetchCloudstreamBackup(id, videoType)
                    }
                    if (cs != null && cs.isNotEmpty()) {
                        emitDeduped(cs)
                        com.streamflixreborn.streamflix.utils.BackupAlertTracker
                            .recordSuccess("Cloudstream", cs.size)
                    } else if (cs != null) {
                        com.streamflixreborn.streamflix.utils.BackupAlertTracker
                            .recordSuspiciousEmpty("Cloudstream", "0 sources pour $id")
                    } else {
                        Log.w("MovixProvider", "Progressive Cloudstream backup timed out (25s) for $id")
                    }
                } catch (e: Exception) {
                    Log.w("MovixProvider", "Progressive Cloudstream backup failed: ${e.message}")
                    com.streamflixreborn.streamflix.utils.BackupAlertTracker
                        .recordFailure("Cloudstream", e)
                }
            }
            launch {
                try {
                    val mb = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                        fetchMovieboxBackup(id, videoType)
                    }
                    if (mb != null && mb.isNotEmpty()) {
                        emitDeduped(mb)
                        com.streamflixreborn.streamflix.utils.BackupAlertTracker
                            .recordSuccess("Moviebox", mb.size)
                    } else if (mb != null) {
                        com.streamflixreborn.streamflix.utils.BackupAlertTracker
                            .recordSuspiciousEmpty("Moviebox", "0 sources pour $id")
                    } else {
                        Log.w("MovixProvider", "Progressive Moviebox backup timed out (25s) for $id")
                    }
                } catch (e: Exception) {
                    Log.w("MovixProvider", "Progressive Moviebox backup failed: ${e.message}")
                    com.streamflixreborn.streamflix.utils.BackupAlertTracker
                        .recordFailure("Moviebox", e)
                }
            }
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)
    // 2026-06-21 v3 (user "rien charge en progressive, il faut optimiser ça
    //   pour pas qu'il y ait de blocage" — logs OPPO montraient ANR_LOG 1.8s) :
    //   Force tout le upstream (channelFlow + tous les launch{} HTTP) sur
    //   Dispatchers.IO. Avant : les fetch tapaient le Main thread du caller
    //   (ViewModel collect) → ANR. Maintenant : 100% IO, le Main reste libre
    //   pour l'UI.

    /** 2026-06-21 : Cloudstream backup standalone — extrait de fetchMovixBackups. */
    private suspend fun fetchCloudstreamBackup(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val csId = when (videoType) {
                is Video.Type.Movie -> id
                is Video.Type.Episode -> id.substringBefore("-").let { tid ->
                    "$tid:${videoType.season.number}:${videoType.number}"
                }
            }
            CloudstreamProvider.getServers(csId, videoType)
        } catch (e: Exception) {
            Log.d("MovixProvider", "Cloudstream backup progressive failed for $id: ${e.message}")
            emptyList()
        }
    }

    /** 2026-06-21 : Moviebox backup standalone — extrait de fetchMovixBackups. */
    private suspend fun fetchMovieboxBackup(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val tmdbIdInt = when (videoType) {
                is Video.Type.Movie -> id.toIntOrNull()
                is Video.Type.Episode -> id.substringBefore("-").toIntOrNull()
            }
            if (tmdbIdInt != null) MovieboxProvider.getMovieboxSourcesByTmdbId(tmdbIdInt, videoType)
            else emptyList()
        } catch (e: Exception) {
            Log.d("MovixProvider", "Moviebox backup progressive failed for $id: ${e.message}")
            emptyList()
        }
    }

    /** 2026-06-21 : Coflix backup standalone — extrait de fetchMovixBackups
     *  pour pouvoir être appelé en parallèle dans getServersProgressive
     *  (= sans dépendre de Cloudstream/Moviebox qui sont OFF en progressive). */
    private suspend fun fetchCoflixBackup(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val tmdbIdInt = when (videoType) {
                is Video.Type.Movie -> id.toIntOrNull()
                is Video.Type.Episode -> id.substringBefore("-").toIntOrNull()
            } ?: return emptyList()
            val (title, year) = when (videoType) {
                is Video.Type.Movie -> {
                    val det = TMDb3.Movies.details(movieId = tmdbIdInt, language = "fr-FR")
                    (det.title.takeIf { it.isNotBlank() } ?: det.originalTitle) to
                        det.releaseDate?.take(4)?.toIntOrNull()
                }
                is Video.Type.Episode -> {
                    val det = TMDb3.TvSeries.details(seriesId = tmdbIdInt, language = "fr-FR")
                    (det.name.takeIf { it.isNotBlank() } ?: det.originalName) to
                        det.firstAirDate?.take(4)?.toIntOrNull()
                }
            }
            val result = when (videoType) {
                is Video.Type.Movie -> CoflixSourceProvider.getMovieSources(title, year)
                is Video.Type.Episode -> CoflixSourceProvider.getEpisodeSources(
                    showTitle = title,
                    year = year,
                    seasonNumber = videoType.season.number,
                    episodeNumber = videoType.number,
                )
            }
            Log.d("MovixProvider", "Coflix backup progressive: ${result.size} sources for '$title' (${videoType.javaClass.simpleName})")
            result
        } catch (e: Exception) {
            Log.d("MovixProvider", "Coflix backup progressive failed for $id: ${e.message}")
            emptyList()
        }
    }

    /**
     * Devine le nom du player/extracteur à partir de l'URL (pour le fallback par nom dans Extractor).
     * Ex: "https://filemoon.sx/e/abc123" -> "Filemoon"
     *     "https://streamtape.com/e/xyz" -> "Streamtape"
     */
    // 2026-07-09 : extrait l'URL d'une entrée `links` Movix, qu'elle soit une chaîne
    //   (ancien format) ou un objet (nouveau format {"url":…} / {"link":…} / etc.). Pour un
    //   objet sans champ url explicite, on prend le 1er membre string qui ressemble à une URL.
    /**
     * 2026-07-31 (user : « il faut préciser que le #2 est VOSTFR, il va se mélanger avec les
     * autres FR ») : l'endpoint `links` de Movix affichait juste « LuluVdo », « LuluVdo #2 »…
     * sans la langue — impossible de distinguer un VOSTFR d'un VF dans la liste.
     * On lit donc la langue si l'API la fournit (l'élément peut être un objet), et à défaut on
     * la déduit des marqueurs présents dans l'URL. Retourne null si vraiment inconnue
     * (on n'invente pas : mieux vaut pas de mention qu'une mention fausse).
     */
    private fun movixLinkLang(el: com.google.gson.JsonElement?, url: String?): String? {
        // 2026-08-01 (user : Rpmvid, EmbedSeek ET VidHide annoncés « VF » jouent du VOSTFR) :
        //   le champ de langue de Movix N'EST PAS FIABLE. Vérifié sur un même film : le lien
        //   donné pour « Rpmvid (VF) » sert le fichier « In.the.Grey.2026.VOSTFR.1080p… ».
        //   Annoncer un faux VF est pire que ne rien annoncer — `orderByFrenchBuckets` trie
        //   par langue, donc ces serveurs passaient DEVANT les vrais VF.
        //   → On n'accepte plus l'affirmation « VF » venant de Movix. Les mentions négatives
        //     (VOSTFR/VO) et MULTI sont conservées : elles ne peuvent pas faire passer un
        //     contenu étranger pour du français.
        //   La vraie langue est renseignée ensuite par des sources FIABLES : le NOM DE FICHIER
        //   réel remonté par l'extracteur, ou les balises LANGUAGE du manifeste HLS.
        // 2026-08-02 : le rejet systématique du « VF » est LEVÉ. Vérifié en direct sur movix.bet :
        //   les liens sont livrés par PAIRES sur le champ `version` — un « VF » et un « VOSTFR »
        //   par serveur, avec deux liens DIFFÉRENTS — et c'est ce champ qui alimente les onglets
        //   VF/VOSTFR du site. Il est donc fiable. En le jetant, on rendait « non énumérés » tous
        //   les vrais VF (symptôme rapporté par l'utilisateur), pour un cas isolé de mauvais
        //   étiquetage. Ce cas reste couvert autrement : les VOSTFR sont désormais écartés
        //   (`estVostfrAEcarter`) et le nom de fichier corrige à la lecture (`Video.fileName`).
        fun fiable(s: String?): String? = s
        try {
            if (el != null && !el.isJsonNull && el.isJsonObject) {
                val obj = el.asJsonObject
                // `version` EN PREMIER : c'est le champ réellement utilisé par movix.bet pour ses
                //   onglets VF/VOSTFR (constaté dans les données de la page). `type` est en
                //   revanche piégeux — il vaut « embed », pas une langue — d'où son rang final.
                for (k in listOf("version", "lang", "language", "langue", "audio", "vf", "type")) {
                    val v = obj.get(k)
                    if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                        val s = v.asString.trim()
                        if (s.isNotBlank() && !s.startsWith("http")) return fiable(formatLang(s))
                    }
                }
            }
        } catch (_: Exception) {}
        // Repli : marqueurs dans l'URL (ordre important, VOSTFR avant VF).
        //   L'URL, elle, reste digne de confiance quand elle porte un marqueur explicite —
        //   contrairement au champ déclaratif de l'API traité au-dessus.
        val u = url?.lowercase().orEmpty()
        return when {
            u.contains("vostfr") || u.contains("vost") || u.contains("subfrench") -> "VOSTFR"
            u.contains("truefrench") || u.contains("multi") -> "MULTI"
            Regex("""[/._-]vff?[/._-]""").containsMatchIn(u) || u.contains("french") -> "VF"
            else -> null
        }
    }

    /**
     * 2026-08-02 (user : « pour moi on s'en fiche du VOSTFR avec tous les serveurs qu'on a déjà ») :
     * les liens Movix identifiés comme VOSTFR / VO sont écartés de la liste.
     *
     * ⚠ Ne filtre QUE Movix. Les autres providers (animes notamment) gardent leur VOSTFR : c'est
     * parfois la seule version existante, et l'y appliquer viderait des catalogues entiers.
     *
     * ⚠ Ce filtre seul ne suffit pas et n'est pas censé suffire : Movix étiquette « VF » des liens
     * qui servent du VOSTFR, et ceux-là passeraient au travers. C'est le nom de fichier remonté par
     * les extracteurs (`Video.fileName`) qui les démasque à la lecture. Les deux se complètent :
     * ici on retire les VOSTFR déclarés, là on corrige les VOSTFR déguisés.
     */
    private fun estVostfrAEcarter(lang: String?): Boolean {
        val l = lang?.trim()?.uppercase() ?: return false
        return l == "VOSTFR" || l == "VO" || l == "SUBFRENCH"
    }

    private fun movixLinkUrl(el: com.google.gson.JsonElement?): String? {
        if (el == null || el.isJsonNull) return null
        try {
            if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                return el.asString.takeIf { it.startsWith("http") }
            }
            if (el.isJsonObject) {
                val obj = el.asJsonObject
                for (k in listOf("url", "link", "src", "file", "source", "embed", "player")) {
                    val v = obj.get(k)
                    if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                        val s = v.asString
                        if (s.startsWith("http")) return s
                    }
                }
                // Aucun champ nommé → 1er membre string http
                for ((_, v) in obj.entrySet()) {
                    if (v.isJsonPrimitive && v.asJsonPrimitive.isString && v.asString.startsWith("http")) return v.asString
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * 2026-07-17 — Hosts VOLONTAIREMENT masqués (décision user), jamais listés.
     *
     * anonmp4.help : extraction native impossible (le manifeste n'est servable que
     *   depuis le contexte de la page, et le player ne s'initialise que sur un vrai
     *   geste utilisateur — profil Abyss). Seul un overlay WebView aurait marché,
     *   jugé pas rentable pour 1 serveur sur les 15 que propose Wiflix. L'auto-switch
     *   bascule déjà tout seul. Même logique que Streamhg côté Coflix.
     *   → masqué ICI (jamais proposé) + extracteur retiré du routage (Extractor.kt).
     */
    private val hiddenHosts = listOf("anonmp4.help")

    private fun isHiddenHost(url: String): Boolean =
        hiddenHosts.any { url.contains(it, ignoreCase = true) }

    /**
     * 2026-07-31 (user « il s'appelle toujours Jessica », VOE) : l'API Movix renvoie
     * parfois un NOM DE DOMAINE en guise de nom de lecteur — typiquement un miroir
     * rotatif de VOE (« jessicayeahcatch.com », « matthewhotelscience.com »…). Affiché
     * tel quel, l'utilisateur ne reconnaît pas le service.
     *
     * Règle : si le nom fourni ressemble à un domaine, on lui préfère le VRAI nom du
     * service déduit de l'URL (« VOE », « Uqload »…). Sinon on garde le nom de l'API
     * (souvent plus précis, ex. « Uqload Premium »).
     */
    private fun prettyPlayerName(apiName: String?, url: String): String {
        val raw = apiName?.trim()?.takeIf { it.isNotBlank() }
        val looksLikeDomain = raw != null &&
            Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)+$""", RegexOption.IGNORE_CASE).matches(raw)
        if (looksLikeDomain) {
            Extractor.identifyServiceName(url)?.let { return it }
        }
        return raw ?: guessPlayerName(url)
    }

    private fun guessPlayerName(url: String): String {
        // Try the accurate extractor-based detection first
        Extractor.identifyServiceName(url)?.let { return it }
        // Fallback: derive from domain name
        return try {
            val host = url.substringAfter("://").substringBefore("/").substringBefore(":")
            val domain = host.removePrefix("www.")
            val name = domain.substringBeforeLast(".").substringBeforeLast(".")
                .ifEmpty { domain.substringBeforeLast(".") }
            name.replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            "Serveur"
        }
    }

    /**
     * Trie les serveurs par priorité de langue (request user 2026-05-05) :
     * 1. **VF** (Version Française) en premier — sous-trié par fiabilité extracteur
     * 2. **VOSTFR** (Version Originale Sous-Titrée FR) en deuxième
     * 3. **VO** (Version Originale) en dernier
     *
     * Détection :
     * - VOSTFR : contient "vostfr" ou "sous-titr"
     * - VF : contient "[vf]", "vf]", " vf ", " vf$", "french", "français", "francais"
     *        OU n'a pas de tag explicite (default → traité comme VF)
     * - VO : contient "[vo]", " vo ", " vo$", "(vo)" SANS être déjà classé VF/VOSTFR
     *
     * Dédup par URL en bonus pour éviter qu'une même source remonte deux fois.
     */
    private fun sortServersByLanguage(servers: List<Video.Server>): List<Video.Server> {
        // 2026-08-16 : hôtes INJOUABLES écartés ici, au point de passage COMMUN à tous les
        //   endpoints (fstream, links, wiflix, tmdb, cpasmal…) — plutôt qu'au cas par cas, où
        //   on en oublie forcément un. cf. HOTES_MORTS.
        val vivants = servers.filterNot { s ->
            estHoteMort(s.src).also { mort ->
                if (mort) android.util.Log.d(
                    "MovixProvider",
                    "hôte injouable écarté (${s.name}) — aucun extracteur pour ${s.src}",
                )
            }
        }

        val seen = HashSet<String>()
        val unique = vivants.filter { server ->
            val key = server.src.lowercase().trim()
            key.isEmpty() || seen.add(key)
        }

        val vfServers = mutableListOf<Video.Server>()
        val vostfrServers = mutableListOf<Video.Server>()
        val voServers = mutableListOf<Video.Server>()

        // Detection VO robuste : "vo" comme MOT entouré de non-lettres
        // → catche "Phoenix (Videasy VO)" mais pas "vodplay"/"video"
        val voRegex = Regex("""(^|[^a-z])vo([^a-z]|$)""")
        unique.forEach { server ->
            val name = server.name.lowercase()
            when {
                name.contains("vostfr") || name.contains("sous-titr") ->
                    vostfrServers.add(server)
                voRegex.containsMatchIn(name) ->
                    voServers.add(server)
                // Tout le reste → VF (par défaut)
                else -> vfServers.add(server)
            }
        }

        // 2026-05-05 v2 : Natives Movix en premier, BACKUPS en dernier.
        //   (0) Movix natif (fstream, links, wiflix, cpasmal, tmdb, drama,
        //       purstream, seriesDl, videasy, mazQuest, yflix, moiflix)
        //   (1) Backups Papa / Moviebox (catalogue cross-provider sain)
        //   (2) Coflix EN DERNIER (link rot fréquent depuis lecteurvideo.com)
        //   Au sein d'un même bucket, par fiabilité d'extracteur.
        fun isPapa(s: Video.Server) = s.name.contains("Papadustream — ", ignoreCase = false) ||
            s.id.startsWith("papadustream_")
        fun isMoviebox(s: Video.Server) = s.name.startsWith("Moviebox", ignoreCase = false) ||
            s.id.startsWith("moviebox_")
        fun isCoflix(s: Video.Server) = s.name.startsWith("Coflix", ignoreCase = false) ||
            s.id.startsWith("coflix_")
        // 2026-05-05 v3 : bonus qualité — bumps les sources HD/4K/1080p en
        // tête de leur tier respectif. -2 = passe avant les SD, -3 = passe
        // avant tout dans le même score. Permet à "VOE HD" de battre un
        // "VOE SD" du même tier sans casser la hiérarchie inter-tiers.
        fun qualityBonus(s: Video.Server): Int {
            val n = s.name.lowercase()
            return when {
                n.contains("4k") || n.contains("2160") || n.contains("uhd") -> -3
                n.contains("1080") || n.contains("fhd") || n.contains("full hd") -> -2
                n.contains(" hd") || n.endsWith("hd") || n.contains("[hd]") || n.contains("(hd)") -> -1
                n.contains("720") -> 0
                n.contains("480") || n.contains(" sd") -> 1
                else -> 0
            }
        }
        val cmp = compareBy<Video.Server>(
            {
                when {
                    isCoflix(it) -> 2
                    isPapa(it) || isMoviebox(it) -> 1
                    else -> 0
                }
            },
            { serverReliabilityScore(it) },
            { qualityBonus(it) },
        )
        val sortedVf = vfServers.sortedWith(cmp)
        val sortedVostfr = vostfrServers.sortedWith(cmp)
        val sortedVo = voServers.sortedWith(cmp)

        // Order final : VF → VOSTFR → VO (request user)
        val result = mutableListOf<Video.Server>()
        result.addAll(sortedVf)
        result.addAll(sortedVostfr)
        result.addAll(sortedVo)

        Log.d("MovixProvider", "Servers triés (dedup ${servers.size}→${unique.size}) : ${vfServers.size} VF • ${vostfrServers.size} VOSTFR • ${voServers.size} VO")
        return result
    }

    /**
     * 2026-05-05 v2 : score de fiabilité refait en fonction des observations
     * réelles sur la Chromecast (Darkibox hang silencieux, VidMoLy lent via
     * WebView, etc.). Plus bas = mieux = essayé en premier dans le cascade.
     */
    private fun serverReliabilityScore(server: Video.Server): Int {
        val name = server.name.lowercase()
        val src = server.src.lowercase()
        val id = server.id.lowercase()
        return when {
            // === Tier 1 : Direct & rapide (priorité absolue) ===
            // Moviebox VF natif (URL directe themoviebox.org, extracteur WebView rapide)
            id.startsWith("moviebox_") || src.contains("themoviebox") -> 0
            // Filemoon : extracteur API, link rot rare sur contenu frais
            src.contains("filemoon") || name.contains("filemoon") -> 1
            // VOE : streaming rapide, anti-bot OK
            src.contains("voe.sx") || src.contains("voe-")
                || (name.contains("voe") && !name.contains("vovo")) -> 2
            // Uqload, Vidoza, Doodstream, Streamtape : hosters classiques fiables
            src.contains("uqload") || name.contains("uqload") -> 3
            src.contains("vidoza") || name.contains("vidoza") -> 4
            src.contains("dood") || name.contains("doodstream") -> 5
            src.contains("streamtape") || name.contains("streamtape") -> 6

            // === Tier 2 : Fiable mais init plus lente ===
            // Netu/Waaw : Cronet + TLS Chromium, init 1-2s puis stable
            src.contains("netu") || name.contains("netu")
                || src.contains("waaw") || name.contains("waaw") -> 10
            src.contains("frembed") || name.contains("frembed") -> 11
            src.contains("mixdrop") || name.contains("mixdrop") -> 12
            src.contains("mp4upload") || name.contains("mp4upload") -> 13
            src.contains("savefiles") || name.contains("savefiles") -> 14
            src.contains("playmogo") || name.contains("playmogo") -> 15

            // === Tier 3 : WebView ou anti-bot lourd (slow init) ===
            // VidMoLy : WebView extraction 5-10s
            src.contains("vidmoly") || name.contains("vidmoly") || name.contains("vidmoly") -> 20
            // Streamwish/Wishonly : extraction de script JS, parfois échoue
            src.contains("streamwish") || src.contains("wishonly")
                || name.contains("streamwish") -> 21
            src.contains("lulustream") || src.contains("luluvdo")
                || name.contains("lulustream") || name.contains("luluvdo") -> 22
            src.contains("vidsrc") || name.contains("vidsrc") -> 23
            src.contains("vixsrc") -> 24
            src.contains("flemmix") || name.contains("flemmix") -> 25
            src.contains("vidhide") || name.contains("vidhide")
                || src.contains("filelions") -> 26
            src.contains("vidguard") || name.contains("vidguard") -> 27
            src.contains("vidzy") || name.contains("vidzy") -> 28
            src.contains("vidsonic") || name.contains("vidsonic") -> 29
            src.contains("vidara") || name.contains("vidara") -> 30
            src.contains("minochinos") || name.contains("minochinos") -> 31

            // === Tier 4 : Problématiques (Cloudflare hang, link rot) ===
            // 2026-05-05 : DARKIBOX déclasse — observé : répond HTTP 200 mais le
            // stream stalle indéfiniment (Cloudflare drop silencieux). Avec
            // notre watchdog buffering 10s c'est récupérable mais on les met
            // en bas pour ne pas perdre de temps.
            src.contains("darkibox") || name.contains("darkibox") || name.contains("darki") -> 50
            src.contains("veev") || name.contains("veev") -> 51
            src.contains("goodstream") || name.contains("goodstream") -> 52
            src.contains("megaup") || name.contains("megaup") -> 53

            // === Tier 5 : Cassés ===
            src.contains("hgcloud") || name.contains("hgcloud") -> 90
            src.contains("xshotcok") || name.contains("xshotcok") -> 99

            // Défaut (extracteurs inconnus / nouveaux) — milieu de tier 3
            else -> 19
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.video != null) return server.video!!

        var url = server.src.trim()

        // Résolution de redirections courantes avant extraction
        try {
            if (url.contains("/redirect") || url.contains("/go/") || url.contains("/out/")) {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(30, TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                val response = client.newCall(request).execute()
                val finalUrl = response.request.url.toString()
                response.close()
                if (finalUrl.isNotEmpty() && finalUrl != url) {
                    Log.d("MovixProvider", "Redirect resolved: $url -> $finalUrl")
                    url = finalUrl
                }
            }
        } catch (e: Exception) {
            Log.w("MovixProvider", "Redirect resolution failed: ${e.message}")
        }

        // Gestion des liens vidéo directs (m3u8, mp4, etc.)
        val directExtensions = listOf(".m3u8", ".mp4", ".mkv", ".webm", ".avi")
        val urlLower = url.lowercase().split("?").first()
        if (directExtensions.any { urlLower.endsWith(it) }) {
            val type = when {
                urlLower.endsWith(".m3u8") -> "application/x-mpegURL"
                urlLower.endsWith(".mp4") -> "video/mp4"
                urlLower.endsWith(".mkv") -> "video/x-matroska"
                urlLower.endsWith(".webm") -> "video/webm"
                else -> "video/mp4"
            }
            // Add Referer for darkibox direct HLS URLs
            val headers = if (url.contains("darkibox.com")) {
                mapOf("Referer" to "https://darkibox.com")
            } else {
                emptyMap()
            }
            return Video(
                source = url,
                type = type,
                headers = headers
            )
        }

        // Extraction via les extracteurs enregistrés (avec server pour le fallback par nom)
        return Extractor.extract(url, server)
    }

    // ==================== TMDB PERSON ====================

    data class TmdbPersonDetail(
        val id: Int?,
        val name: String?,
        val biography: String?,
        val birthday: String?,
        val deathday: String?,
        val place_of_birth: String?,
        val profile_path: String?
    )

    // ==================== RETROFIT SERVICES ====================

    private interface MovixService {
        @GET("api/search")
        suspend fun search(
            @Query("title") title: String
        ): List<MovixSearchItem>

        @GET("api/fstream/movie/{tmdbId}")
        suspend fun getFstreamMovie(
            @Path("tmdbId") tmdbId: String
        ): FstreamMovieResponse

        @GET("api/fstream/tv/{tmdbId}/season/{season}")
        suspend fun getFstreamTv(
            @Path("tmdbId") tmdbId: String,
            @Path("season") season: Int
        ): FstreamTvResponse

        @GET("api/swiftflow/movie/{tmdbId}")
        suspend fun getSwiftflowMovie(
            @Path("tmdbId") tmdbId: String
        ): SwiftflowMovieResponse

        // 2026-07-25 : nouvelles sources Movix.
        @GET("api/j1f/movie/{tmdbId}")
        suspend fun getJ1fMovie(
            @Path("tmdbId") tmdbId: String
        ): J1fMovieResponse

        @GET("api/purstream/movie/{tmdbId}/stream")
        suspend fun getPurstreamMovie(
            @Path("tmdbId") tmdbId: String
        ): PurstreamMovieResponse

        @GET("api/links/movie/{tmdbId}")
        suspend fun getLinksMovie(
            @Path("tmdbId") tmdbId: String
        ): LinksMovieResponse

        @GET("api/links/tv/{tmdbId}")
        suspend fun getLinksTv(
            @Path("tmdbId") tmdbId: String,
            @Query("season") season: Int,
            @Query("episode") episode: Int
        ): LinksTvResponse

        @GET("api/wiflix/movie/{tmdbId}")
        suspend fun getWiflixMovie(
            @Path("tmdbId") tmdbId: String
        ): WiflixMovieResponse

        @GET("api/wiflix/tv/{tmdbId}/{season}")
        suspend fun getWiflixTv(
            @Path("tmdbId") tmdbId: String,
            @Path("season") season: Int
        ): WiflixTvResponse

        // --- Cpasmal endpoints ---

        @GET("api/cpasmal/movie/{tmdbId}")
        suspend fun getCpasmalMovie(
            @Path("tmdbId") tmdbId: String
        ): CpasmalMovieResponse

        @GET("api/cpasmal/tv/{tmdbId}/{season}/{episode}")
        suspend fun getCpasmalTv(
            @Path("tmdbId") tmdbId: String,
            @Path("season") season: Int,
            @Path("episode") episode: Int
        ): CpasmalResponse

        // --- Series Download endpoints ---

        @GET("api/series/download/{seriesId}/season/{season}/episode/{episode}")
        suspend fun getSeriesDownload(
            @Path("seriesId") seriesId: String,
            @Path("season") season: Int,
            @Path("episode") episode: Int
        ): SeriesDownloadResponse

        @GET("api/series/download/{movieId}")
        suspend fun getMovieDownload(
            @Path("movieId") movieId: String
        ): SeriesDownloadResponse

        // --- TMDB Movix endpoints ---

        @GET("api/tmdb/tv/{tmdbId}")
        suspend fun getTmdbMovixTv(
            @Path("tmdbId") tmdbId: String,
            @Query("season") season: Int,
            @Query("episode") episode: Int
        ): TmdbMovixTvResponse

        @GET("api/tmdb/movie/{tmdbId}")
        suspend fun getTmdbMovixMovie(
            @Path("tmdbId") tmdbId: String
        ): TmdbMovixMovieResponse

        // --- Search for internal series ID ---

        @GET("api/search")
        suspend fun searchMovix(
            @Query("title") title: String
        ): MovixSearchResponse

        // Custom Links / SeekStreaming (bysebuho, rumble) déjà intégrés via
        // getLinksMovie / getLinksTv ci-dessus (api/links/movie et api/links/tv)
    }

    private interface TmdbService {
        @GET("trending/all/day")
        suspend fun getTrending(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            // 2026-08-04 : pagination ajoutée pour pouvoir compléter la rangée après
            //   l'écartement des films encore à l'affiche (cf. `completer` dans getHome).
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbTrendingItem>

        @GET("trending/all/week")
        suspend fun getTrendingWeek(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbTrendingItem>

        @GET("collection/{id}")
        suspend fun getCollection(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR"
        ): TmdbCollectionResult

        @GET("movie/popular")
        suspend fun getPopularMovies(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbMovieListItem>

        @GET("movie/top_rated")
        suspend fun getTopRatedMovies(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbMovieListItem>

        @GET("tv/popular")
        suspend fun getPopularTvShows(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbTvListItem>

        @GET("tv/top_rated")
        suspend fun getTopRatedTvShows(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1
        ): TmdbPageResult<TmdbTvListItem>

        @GET("discover/movie")
        suspend fun discoverMovies(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1,
            @Query("sort_by") sortBy: String = "popularity.desc",
            @Query("with_genres") withGenres: String? = null,
            @Query("with_origin_country") withOriginCountry: String? = null,
            @Query("with_original_language") withOriginalLanguage: String? = null,
            @Query("vote_count.gte") voteCountGte: Int? = null,
            @Query("primary_release_date.gte") primaryReleaseDateGte: String? = null,
            @Query("primary_release_date.lte") primaryReleaseDateLte: String? = null,
            @Query("without_genres") withoutGenres: String? = null,
            @Query("include_adult") includeAdult: Boolean = false
        ): TmdbPageResult<TmdbMovieListItem>

        @GET("discover/tv")
        suspend fun discoverTvShows(
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("page") page: Int = 1,
            @Query("sort_by") sortBy: String = "popularity.desc",
            @Query("with_genres") withGenres: String? = null,
            @Query("with_origin_country") withOriginCountry: String? = null,
            @Query("with_original_language") withOriginalLanguage: String? = null,
            @Query("vote_count.gte") voteCountGte: Int? = null,
            @Query("first_air_date.gte") firstAirDateGte: String? = null,
            @Query("first_air_date.lte") firstAirDateLte: String? = null,
            @Query("without_genres") withoutGenres: String? = null,
            @Query("include_adult") includeAdult: Boolean = false
        ): TmdbPageResult<TmdbTvListItem>

        @GET("movie/{id}")
        suspend fun getMovieDetails(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("append_to_response") appendToResponse: String? = null
        ): TmdbMovieResult

        @GET("tv/{id}")
        suspend fun getTvDetails(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("append_to_response") appendToResponse: String? = null
        ): TmdbTvResult

        @GET("tv/{tv_id}/season/{season_number}")
        suspend fun getSeasonDetails(
            @Path("tv_id") tvId: Int,
            @Path("season_number") seasonNumber: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR"
        ): TmdbSeasonDetail

        @GET("movie/{id}/recommendations")
        suspend fun getMovieRecommendations(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR"
        ): TmdbPageResult<TmdbMovieListItem>

        @GET("tv/{id}/recommendations")
        suspend fun getTvRecommendations(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR"
        ): TmdbPageResult<TmdbTvListItem>

        @GET("person/{id}")
        suspend fun getPersonDetails(
            @Path("id") id: Int,
            @Query("api_key") apiKey: String,
            @Query("language") language: String = "fr-FR",
            @Query("append_to_response") appendToResponse: String? = null
        ): TmdbPersonDetail
    }

    // ==================== SERVICE BUILDERS ====================

    private fun buildMovixService(): MovixService {
        val client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Referer", portalUrl)
                    .header("Origin", portalUrl.trimEnd('/'))
                    .build()
                Log.d("MovixProvider", "API Request: ${request.url}")
                val response = chain.proceed(request)
                if (!response.isSuccessful) {
                    val body = response.peekBody(2048).string()
                    Log.e("MovixProvider", "API Error ${response.code}: ${request.url} -> $body")
                }
                response
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(MovixService::class.java)
    }

    private fun buildTmdbService(): TmdbService {
        val client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()

        return Retrofit.Builder()
            .baseUrl(TMDB_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(TmdbService::class.java)
    }
}
