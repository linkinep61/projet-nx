package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Movix LiveTV — fournisseur IPTV branché sur l'API publique de Movix
 * (`api.movix.cash/api/livetv/`).
 *
 *  Source primaire : **Vavoo** (catalog `vavoo_tv_fr`) → 771 chaînes FR
 *  généralistes, sport, ciné, info, doc, kids, musique, lifestyle, VOD.
 *
 *  Pourquoi pas SportLiveProvider ? — SportLive a 23 chaînes sport curées
 *  avec multi-source (WiTV/OLA/Vegeta/OTF). Movix LiveTV ouvre une catégorie
 *  totalement différente : les chaînes nationales mainstream (TF1, France
 *  2/3/5, M6, Canal+, Arte, BFM, etc.) que Streamflix n'avait pas du tout.
 *
 *  API Stremio-style :
 *   - `GET /api/livetv/manifest` → catalogue de catalogues (informationnel)
 *   - `GET /api/livetv/catalog/tv/vavoo_tv_fr` → liste des 771 chaînes
 *   - `GET /api/livetv/stream/tv/{channelId}` → 3 mirrors HLS + headers
 *
 *  Pas d'auth, pas de Turnstile sur ces routes au moment de l'écriture.
 *  Si Movix met du Turnstile dessus on est bloqué — voir audit
 *  MovixOpenSource pour la mitigation possible (mirror discovery).
 *
 *  Crédit : MovixOpenSource (CC BY-NC 4.0) — code original à
 *  https://github.com/movixcorp/MovixOpenSource. On consomme leur API
 *  publique, on ne reprend aucun de leur code source.
 */
object MovixLiveTvProvider : Provider, IptvProvider, ProviderConfigUrl {

    override val name = "Movix LiveTV"

    /** API Movix — base par défaut. Si `.cash` meurt, on bascule
     *  automatiquement via [onChangeUrl] qui interroge movix.health pour
     *  trouver le miroir actif (logique partagée avec [MovixProvider]). */
    override val defaultBaseUrl: String = "https://api.movix.cash/"

    /** Mutex pour [onChangeUrl] — empêche plusieurs refreshs concurrents. */
    override val changeUrlMutex = Mutex()

    /** Lit l'URL active depuis le cache PARTAGÉ avec MovixProvider (les deux
     *  providers tapent la même API, donc même URL). Fallback sur defaultBaseUrl
     *  si rien en cache. */
    override val baseUrl: String
        get() {
            val cached = UserPreferences.getProviderCache(MovixProvider, UserPreferences.PROVIDER_URL)
            return cached.ifEmpty { defaultBaseUrl }
        }

    override val logo: String =
        "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_movix"

    override val language = "fr"

    private const val TAG = "MovixLiveTvProvider"

    /** Le user agent et le referer doivent matcher ce que le serveur Vavoo
     *  attend, sinon les m3u8 retournent 403. Les valeurs viennent
     *  directement du `behaviorHints.headers` de la réponse Movix — on a
     *  vérifié au curl avant d'écrire ce code. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    private const val VAVOO_REFERER = "https://vavoo.to/"

    /** Catalog Vavoo France — 771 chaînes FR mainstream. */
    private const val VAVOO_CATALOG_ID = "vavoo_tv_fr"

    /**
     * Ordre canal FR officiel (TNT + Canal+ + bouquets) — utilisé pour trier
     * les chaînes au lieu de l'ordre alphabétique. La liste est volontairement
     * partielle : ce qui n'est pas mappé tombe en queue de tri (ordre alpha).
     *
     * Mapping : `nom normalisé` → `position canal`. Le nom est normalisé via
     * [normChannelName] avant lookup pour matcher les variantes (TF1 / TF1 HD /
     * TF1 FHD → toutes mappées sur 1, mais une "TF1 HD" garde son rang derrière
     * "TF1" via tie-break sur la longueur du nom).
     *
     * Source : https://fr.wikipedia.org/wiki/Liste_des_chaînes_de_la_TNT_française
     * + bouquets Canal+ et chaînes sport principales.
     */
    private val FR_CHANNEL_ORDER: Map<String, Int> = linkedMapOf(
        // ─── TNT gratuite ───
        "tf1" to 1,
        "france2" to 2,
        "france3" to 3,
        "canalplus" to 4, "canal" to 4,
        "france5" to 5,
        "m6" to 6,
        "arte" to 7,
        "c8" to 8,
        "w9" to 9,
        "tmc" to 10,
        "tfx" to 11,
        "nrj12" to 12,
        "lcp" to 13, "publicsenat" to 13,
        "france4" to 14, "culturebox" to 14,
        "bfmtv" to 15,
        "cnews" to 16,
        "cstar" to 17,
        "gulli" to 18,
        "tf1seriesfilms" to 19,
        "lequipe" to 21, "lachainelequipe" to 21,
        "6ter" to 22,
        "rmcstory" to 23,
        "rmcdecouverte" to 24,
        "cherie25" to 25,
        "lci" to 26,
        "franceinfo" to 27,
        "paris1ere" to 41, "parispremiere" to 41,
        "teva" to 44,

        // ─── Canal+ premium (rangs ~45-49 historiques) ───
        "canalplusgrandecran" to 45, "canalpluscinema" to 46,
        "canalplusseries" to 47, "canalplussport" to 48,
        "canalplusfoot" to 49, "canalplusdocs" to 50,
        "canalplusbox" to 51, "canalplus4kultra" to 52,

        // ─── Sport (rangs synthétiques pour grouper) ───
        "beinsports1" to 60, "beinsport1" to 60,
        "beinsports2" to 61, "beinsport2" to 61,
        "beinsports3" to 62, "beinsport3" to 62,
        "beinsportsmax4" to 63, "beinsportmax4" to 63,
        "beinsportsmax5" to 64, "beinsportmax5" to 64,
        "beinsportsmax6" to 65, "beinsportmax6" to 65,
        "beinsportsmax7" to 66, "beinsportmax7" to 66,
        "beinsportsmax8" to 67, "beinsportmax8" to 67,
        "beinsportsmax9" to 68, "beinsportmax9" to 68,
        "beinsportsmax10" to 69, "beinsportmax10" to 69,
        "rmcsport1" to 70, "rmcsport2" to 71,
        "rmcsport3" to 72, "rmcsport4" to 73,
        "eurosport1" to 80, "eurosport2" to 81,
        "eurosportnews" to 82,
        "infosport" to 90, "infosportplus" to 90,

        // ─── Cinéma (rangs synthétiques pour grouper) ───
        "ocs" to 100, "ocsmax" to 101, "ocscity" to 102,
        "ocschoc" to 103, "ocsgeants" to 104,
        "tcm" to 110, "tcmcinema" to 110,
        "syfy" to 111,
        "actionhd" to 112, "action" to 112,

        // ─── News/Info ───
        "tv5monde" to 200, "tv5" to 200,
        "france24" to 201,
        "euronews" to 202,
        "rtbf" to 203,
        "rts" to 204,

        // ─── Kids ───
        "tiji" to 300, "boomerang" to 301,
        "cartoonnetwork" to 302, "nickelodeon" to 303,
        "disney" to 304, "disneychannel" to 304, "disneyjr" to 305,

        // ─── Découverte / Doc ───
        "natgeo" to 400, "nationalgeographic" to 400,
        "histoire" to 401,
        "ushuaia" to 402,
        "discovery" to 403,
        "planete" to 404, "planeteplus" to 404,
    )

    /** Normalise le nom de chaîne pour matcher l'ordre canal :
     *  lowercase, strip diacritics, strip "HD/FHD/4K/SD/+1/FR/etc." et
     *  caractères non alphanumériques. "France 2 HD" → "france2",
     *  "Canal+ Sport 360" → "canalplussport360", etc.
     *
     *  Public car réutilisée par SportLiveProvider pour matcher les chaînes
     *  sport canoniques (witvKey) avec les chaînes Vavoo. */
    fun normChannelName(raw: String): String {
        var s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace("+", "plus")
            .replace("&", "and")
        // Strip qualifiers (HD/FHD/4K/SD/etc.) — on veut "TF1 HD" et "TF1"
        // matchent tous les deux sur la clé "tf1".
        s = s
            .replace(Regex("\\b(fhd|uhd|hd|sd|4k|raw|hevc|h\\.?265|live|backup|fhdr|sdr|test|ott|ppv)\\b"), "")
            .replace(Regex("\\(.*?\\)|\\[.*?]"), "")  // strip parenthèses/crochets
            .replace(Regex("\\b(fr|french|francais|france|belgique|be|suisse|ch|lux|mena|arabic)\\b"), "")
            .replace(Regex("\\+\\s?1\\b"), "")  // "+ 1" / "+1"
            .replace(Regex("\\b(1080p|720p|480p|360p)\\b"), "")
        return s.replace(Regex("[^a-z0-9]"), "")
    }

    /** Comparator qui place les chaînes selon FR_CHANNEL_ORDER d'abord,
     *  puis tri alpha pour les non-mappées. Pour les chaînes mappées sur le
     *  même rang (ex: TF1 + TF1 HD), tie-break sur la longueur du nom (le
     *  nom le plus court gagne — "TF1" avant "TF1 HD"). */
    private val frChannelComparator: Comparator<LiveChannel> = Comparator { a, b ->
        val ra = FR_CHANNEL_ORDER[normChannelName(a.name)] ?: Int.MAX_VALUE
        val rb = FR_CHANNEL_ORDER[normChannelName(b.name)] ?: Int.MAX_VALUE
        when {
            ra != rb -> ra.compareTo(rb)
            // Même rang TNT (ex: TF1, TF1 HD, TF1 FHD) : nom le plus court d'abord.
            a.name.length != b.name.length -> a.name.length.compareTo(b.name.length)
            else -> a.name.compareTo(b.name)
        }
    }

    /** ID format pour notre app : `movixlivetv::<vavoo_id>`. Le vavoo_id
     *  contient des caractères spéciaux (`|`, `%20`) qu'on garde tels quels
     *  en interne ; on URL-encode quand on appelle l'API. */
    private const val ID_PREFIX = "movixlivetv::"

    /** IptvProvider exige ce flow — on ne s'en sert pas (pas de découverte
     *  progressive de servers, l'API renvoie tout d'un coup). */
    private val _additionalServersFlow = MutableSharedFlow<Video.Server>()
    override val additionalServersFlow: SharedFlow<Video.Server> =
        _additionalServersFlow.asSharedFlow()

    // ─────────────── Auto-discovery du miroir API ───────────────

    /** Scope pour les triggers async (refresh URL, etc.). */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Compteur d'échecs HTTP consécutifs (5xx, connect-fail, timeout). Au-dessus
     *  du seuil [FAILURE_THRESHOLD_FOR_REFRESH], on déclenche [onChangeUrl] pour
     *  trouver un nouveau miroir. Reset à 0 sur chaque succès. */
    @Volatile private var consecutiveFailures = 0
    private const val FAILURE_THRESHOLD_FOR_REFRESH = 5

    /** Délègue à [MovixProvider.onChangeUrl] : les deux providers tapent la
     *  même API, partagent la même URL active. MovixProvider connaît la logique
     *  de discovery (parse de movix.health pour trouver le miroir actif).
     *
     *  En revanche on a notre propre [changeUrlMutex] pour pas que les deux
     *  providers refreshent en parallèle quand ils détectent une panne en
     *  même temps.
     */
    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            // Délègue à MovixProvider qui a sa propre logique de discovery
            // (parse movix.health pour trouver le miroir actif).
            try {
                MovixProvider.onChangeUrl(forceRefresh)
                Log.d(TAG, "URL refresh via MovixProvider — nouvelle baseUrl: $baseUrl")
            } catch (e: Exception) {
                Log.w(TAG, "URL refresh failed: ${e.message}")
            }
        }
        return baseUrl
    }

    /** Trigger asynchrone du refresh quand on détecte trop d'échecs consécutifs.
     *  Idempotent : si le refresh est déjà en cours (mutex held), pas de double-fire. */
    private fun triggerRefreshIfNeeded() {
        if (consecutiveFailures < FAILURE_THRESHOLD_FOR_REFRESH) return
        Log.w(TAG, "$consecutiveFailures échecs consécutifs — déclenche onChangeUrl(forceRefresh=true)")
        consecutiveFailures = 0  // reset avant pour pas refire en boucle
        scope.launch {
            try {
                onChangeUrl(forceRefresh = true)
                // Reset le cache catalog pour qu'il refetch avec la nouvelle URL.
                catalogCache = emptyList()
                catalogLoadedAtMs = 0L
            } catch (e: Exception) {
                Log.e(TAG, "Auto-refresh URL failed: ${e.message}")
            }
        }
    }

    // ─────────────── Modèle interne ───────────────

    private data class LiveChannel(
        val rawId: String,        // "vavoo_TF1|group:fr"
        val name: String,         // "TF1"
        val poster: String?,      // poster URL
        val logo: String?,        // logo URL (rectangulaire)
        val background: String?,  // bg URL (paysage)
        val genres: List<String>, // ["General"], ["Sport"], etc.
    )

    /** Cache mémoire du catalog. TTL 30 min — les chaînes changent rarement. */
    @Volatile private var catalogCache: List<LiveChannel> = emptyList()
    @Volatile private var catalogLoadedAtMs: Long = 0L
    private const val CATALOG_TTL_MS = 30L * 60 * 1000L
    private val catalogMutex = Mutex()

    // ─────────────── HTTP ───────────────

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(DnsResolver.doh)  // bypass DNS filters / AV web protection
            .build()
    }

    private suspend fun httpGetJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP ${resp.code} on $url")
                    // 5xx (serveur down) ou 530 (Cloudflare server error) → compte
                    // comme un échec qui peut indiquer que le miroir API est mort.
                    // Les 4xx (404, 403…) sont des erreurs applicatives, pas serveur.
                    if (resp.code >= 500) {
                        consecutiveFailures++
                        triggerRefreshIfNeeded()
                    }
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                // Succès → reset le compteur d'échecs
                consecutiveFailures = 0
                JSONObject(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.javaClass.simpleName}: ${e.message}")
            // Connect-fail, timeout, DNS fail → tous indicateurs que le
            // miroir est mort. Compte comme un échec.
            consecutiveFailures++
            triggerRefreshIfNeeded()
            null
        }
    }

    /** Fetch + cache du catalog Vavoo France. Idempotent : ne re-fetch pas si frais. */
    private suspend fun ensureCatalog(): List<LiveChannel> {
        val age = System.currentTimeMillis() - catalogLoadedAtMs
        if (catalogCache.isNotEmpty() && age < CATALOG_TTL_MS) return catalogCache

        return catalogMutex.withLock {
            // Re-check après lock (autre thread peut avoir rempli pendant qu'on attendait).
            val age2 = System.currentTimeMillis() - catalogLoadedAtMs
            if (catalogCache.isNotEmpty() && age2 < CATALOG_TTL_MS) return@withLock catalogCache

            val url = "${baseUrl}api/livetv/catalog/tv/$VAVOO_CATALOG_ID"
            val json = httpGetJson(url) ?: return@withLock catalogCache  // garde le vieux cache si échec

            val metas = json.optJSONArray("metas") ?: return@withLock catalogCache
            val out = ArrayList<LiveChannel>(metas.length())
            for (i in 0 until metas.length()) {
                val m = metas.optJSONObject(i) ?: continue
                val rawId = m.optString("id").takeIf { it.isNotBlank() } ?: continue
                val nm = m.optString("name").takeIf { it.isNotBlank() } ?: continue
                // 2026-05-08 : filtre langue partagé — exclut les chaînes non-FR
                // (DE/EN/etc.) du catalog Movix LiveTV pour pas polluer l'UI FR.
                if (!com.streamflixreborn.streamflix.utils.IptvLangFilter.isFrCompatible(nm)) {
                    continue
                }
                val genresArr = m.optJSONArray("genres")
                val genres = if (genresArr != null) {
                    (0 until genresArr.length()).mapNotNull { gi ->
                        genresArr.optString(gi).takeIf { it.isNotBlank() }
                    }
                } else emptyList()
                out.add(
                    LiveChannel(
                        rawId = rawId,
                        name = nm,
                        poster = m.optString("poster").takeIf { it.isNotBlank() },
                        logo = m.optString("logo").takeIf { it.isNotBlank() },
                        background = m.optString("background").takeIf { it.isNotBlank() },
                        genres = genres,
                    )
                )
            }
            Log.d(TAG, "Catalog rempli — ${out.size} chaînes")
            catalogCache = out
            catalogLoadedAtMs = System.currentTimeMillis()
            out
        }
    }

    /** Convertit un LiveChannel en TvShow (utilisé partout dans l'app). */
    private fun channelToTvShow(c: LiveChannel): TvShow = TvShow(
        id = ID_PREFIX + c.rawId,
        title = c.name,
        overview = c.genres.joinToString(", ").ifBlank { "Chaîne en direct" },
        quality = "Live",
        poster = c.poster ?: c.logo,
        banner = c.background ?: c.poster,
        providerName = name,
    )

    // ─────────────── Provider impl ───────────────

    /**
     * Home : une catégorie par genre, capée à 30 chaînes pour garder le
     * RecyclerView responsive (771 items full = ANR garanti sur TV bas de
     * gamme). L'utilisateur a accès au reste via `getTvShows()` (page
     * "Toutes les chaînes") et via la recherche.
     *
     * Ordre d'affichage : généralistes en premier (les plus regardés),
     * sport en bas (couvert principalement par SportLiveProvider — on
     * l'inclut quand même pour les variantes Eurosport/DAZN).
     */
    override suspend fun getHome(): List<Category> {
        val channels = ensureCatalog()
        if (channels.isEmpty()) return emptyList()

        // Mappe les noms de genre Vavoo (anglais) → libellés FR.
        // L'ordre de la liste = ordre d'affichage dans le home.
        val genreOrder = listOf(
            "General" to "Généralistes",
            "Movies" to "Cinéma",
            "News" to "Info",
            "Documentary" to "Documentaires",
            "Kids" to "Enfants",
            "Entertainment" to "Divertissement",
            "Music" to "Musique",
            "Sport" to "Sport",
            "Lifestyle" to "Style de vie",
            "VOD" to "VOD",
        )

        val categories = mutableListOf<Category>()
        val maxPerHome = 30

        // 2026-05-08 : helper inline pour filtrer une chaîne bannie. Cross-provider
        // via IptvBannedChannels.normalize() qui strip "movixlivetv::".
        val isChannelBanned: (LiveChannel) -> Boolean = { c ->
            com.streamflixreborn.streamflix.fragments.player.settings
                .IptvBannedChannels.isBanned(ID_PREFIX + c.rawId)
        }

        // 1. Section "(none)" en premier : chaînes sans genre tagué — souvent
        //    des chaînes mainstream que Vavoo n'a pas catégorisées (ex: TF1
        //    SERIES & FILM). On les met sous "Généralistes" plutôt qu'une
        //    catégorie séparée pour éviter le clutter.
        val noGenreChannels = channels.filter { it.genres.isEmpty() }

        for ((vavooGenre, frLabel) in genreOrder) {
            val matching = channels.filter { vavooGenre in it.genres }
            // "Généralistes" récupère aussi les sans-genre.
            val all = if (vavooGenre == "General") matching + noGenreChannels else matching
            if (all.isEmpty()) continue
            val capped = all
                .filter { !isChannelBanned(it) }
                .sortedWith(frChannelComparator)
                .take(maxPerHome)
                .map { channelToTvShow(it) }
            if (capped.isNotEmpty()) categories.add(Category(name = frLabel, list = capped))
        }

        // 2026-05-08 : section "✕ Chaînes bannies" EN BAS du home.
        // Dossier fixe pour l'user. Long-press sur une chaîne bannie → menu
        // "Débannir" pour la réactiver.
        try {
            val banned = channels.filter { isChannelBanned(it) }
            if (banned.isNotEmpty()) {
                val bannedItems = banned
                    .sortedWith(frChannelComparator)
                    .map { channelToTvShow(it) }
                categories.add(Category(name = "✕ Chaînes bannies", list = bannedItems))
            }
        } catch (_: Throwable) { }

        return categories
    }

    /** Pagination simple sur le catalog complet, ordre alphabétique. */
    override suspend fun getTvShows(page: Int): List<TvShow> {
        val channels = ensureCatalog()
        val perPage = 50
        val from = (page - 1) * perPage
        if (from >= channels.size) return emptyList()
        return channels
            .sortedWith(frChannelComparator)
            .drop(from)
            .take(perPage)
            .map { channelToTvShow(it) }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    /** Recherche : filtrage côté client (le catalog tient en mémoire). */
    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return getTvShows(page)
        val q = query.lowercase().trim()
        val channels = ensureCatalog()
        val perPage = 50
        val from = (page - 1) * perPage
        return channels
            .filter { it.name.lowercase().contains(q) }
            .sortedWith(frChannelComparator)
            .drop(from)
            .take(perPage)
            .map { channelToTvShow(it) }
    }

    override suspend fun getMovie(id: String): Movie =
        throw UnsupportedOperationException("Movix LiveTV n'a pas de films")

    override suspend fun getTvShow(id: String): TvShow {
        val rawId = id.removePrefix(ID_PREFIX)
        val channels = ensureCatalog()
        val c = channels.firstOrNull { it.rawId == rawId }
            ?: throw IllegalArgumentException("Chaîne inconnue: $id")
        return TvShow(
            id = ID_PREFIX + c.rawId,
            title = c.name,
            overview = c.genres.joinToString(", ").ifBlank { "Chaîne en direct" },
            quality = "Live",
            poster = c.poster ?: c.logo,
            banner = c.background ?: c.poster,
            providerName = name,
            seasons = listOf(
                Season(
                    id = id,
                    number = 1,
                    title = "Live",
                    episodes = listOf(
                        Episode(
                            id = id,
                            number = 1,
                            title = c.name,
                        )
                    ),
                )
            ),
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val rawId = seasonId.removePrefix(ID_PREFIX)
        val channels = ensureCatalog()
        val c = channels.firstOrNull { it.rawId == rawId } ?: return emptyList()
        return listOf(Episode(id = seasonId, number = 1, title = c.name))
    }

    override suspend fun getGenre(id: String, page: Int): Genre =
        throw UnsupportedOperationException("Movix LiveTV gère ses genres dans getHome")

    override suspend fun getPeople(id: String, page: Int): People =
        throw UnsupportedOperationException("Movix LiveTV n'a pas de people")

    /**
     * Récupère les mirrors HLS depuis l'API Movix.
     * Réponse Stremio :
     * ```
     * { "streams": [
     *     {"name":"Vavoo", "title":"[🏠] TF1 (1)", "url":"https://...m3u8",
     *      "behaviorHints":{"headers":{"User-Agent":"...","Referer":"https://vavoo.to/"}}},
     *     ... (3 mirrors généralement)
     * ]}
     * ```
     */
    /**
     * Cherche les rawIds des chaînes du catalog dont le nom matche une clé
     * normalisée. Utilisé par SportLiveProvider pour récupérer les variantes
     * d'une chaîne sport canonique (Canal+ Sport, beIN Sports 1, …) parmi les
     * 771 chaînes Vavoo.
     *
     *  Note de matching :
     *  -------------------
     *  La clé attendue (witvKey de SportLiveProvider) est typée "canalsport",
     *  "beinsport1", "lequipe" — strip de tout caractère non [a-z0-9] et
     *  "sports" → "sport". Pour matcher un nom Vavoo on doit donc :
     *   1) appliquer la même normalisation
     *   2) strip ALSO les markers de qualité (HD/FHD/4K/SD/+1) pour qu'une
     *      chaîne "BEIN SPORTS 1 HD" matche "beinsport1"
     *
     *  Renvoie au plus [maxResults] rawIds, triés par préférence (le nom le
     *  plus court gagne — version "base" avant les variantes HD/FHD).
     *
     *  @param normKey clé normalisée (ex: "beinsport1")
     *  @param maxResults limite (par défaut 3 = base + HD + FHD typiquement)
     */
    suspend fun findRawIdsForNormKey(normKey: String, maxResults: Int = 3): List<String> {
        if (normKey.isBlank()) return emptyList()
        val channels = ensureCatalog()
        return channels
            .filter { c ->
                // Normalise le nom Vavoo en strippant aussi les markers HD/FHD/etc.
                normChannelNameForSportMatch(c.name) == normKey
            }
            // Préfère les variantes les plus courtes (= sans qualifier ajouté).
            // "BEIN SPORTS 1" avant "BEIN SPORTS 1 HD" avant "BEIN SPORTS 1 FHD".
            .sortedBy { it.name.length }
            .take(maxResults)
            .map { it.rawId }
    }

    /**
     * Variante de [normChannelName] qui matche le format strict "witvKey" :
     *  - strip "+" et "&" (au lieu de les convertir en "plus"/"and")
     *  - strip tout caractère non [a-z0-9]
     *  - "sports" → "sport"
     *
     *  Cible : matcher les noms Vavoo ("BEIN SPORTS 1 HD") avec les witvKey
     *  curés par SportLiveProvider ("beinsport1").
     */
    private fun normChannelNameForSportMatch(raw: String): String {
        var s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
        s = s
            .replace(Regex("\\b(fhd|uhd|hd|sd|4k|raw|hevc|h\\.?265|live|backup|fhdr|sdr|test|ott|ppv)\\b"), "")
            .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
            .replace(Regex("\\b(fr|french|francais|france|belgique|be|suisse|ch|lux|mena|arabic)\\b"), "")
            .replace(Regex("\\+\\s?1\\b"), "")
            .replace(Regex("\\b(1080p|720p|480p|360p)\\b"), "")
        s = s.replace(Regex("[^a-z0-9]"), "")
        return s.replace("sports", "sport")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val rawId = id.removePrefix(ID_PREFIX)
        // ATTENTION ENCODAGE : les rawIds Vavoo arrivent DÉJÀ pré-encodés
        // par l'API Movix (ex: "vavoo_EUROSPORT%201|group:fr"). Re-encoder
        // tout avec URLEncoder.encode() casse tout — le `%` devient `%25`,
        // donc `%20` devient `%2520` et le serveur ne reconnaît plus l'ID.
        // Vérifié au curl : le serveur attend littéralement la string telle
        // quelle, avec juste le `|` à échapper en `%7C` (caractère réservé
        // que OkHttp pourrait refuser dans une path component).
        val urlReady = rawId.replace("|", "%7C")
        val url = "${baseUrl}api/livetv/stream/tv/$urlReady"
        val json = httpGetJson(url) ?: return emptyList()
        val arr = json.optJSONArray("streams") ?: return emptyList()

        val out = mutableListOf<Video.Server>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val streamUrl = s.optString("url").takeIf { it.isNotBlank() } ?: continue
            // Le titre Stremio ressemble à "[🏠] TF1 (1)" — on le simplifie
            // en "Mirror N" pour éviter l'emoji et les redondances.
            val mirrorIdx = i + 1
            out.add(
                Video.Server(
                    id = "movixlivetv__${rawId}__$mirrorIdx",
                    name = "Vavoo — Mirror $mirrorIdx",
                    src = streamUrl,
                )
            )
        }
        Log.d(TAG, "getServers $rawId → ${out.size} mirrors")
        return out
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // Pas d'extraction nécessaire : l'API Movix renvoie déjà l'URL HLS
        // directe. On a juste à fournir les bons headers à ExoPlayer pour
        // que Vavoo ne renvoie pas 403.
        // `type = APPLICATION_M3U8` force ExoPlayer à utiliser HlsMediaSource
        // au lieu de tenter de deviner depuis l'URL — important car certaines
        // URLs n'ont pas .m3u8 visible dans leur path (paths obfusqués).
        return Video(
            source = server.src,
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to VAVOO_REFERER,
            ),
        )
    }
}
