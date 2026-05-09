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
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sport Live — provider META qui agrège les chaînes Sport des 3 providers IPTV
 * (WiTV, OLA TV, Vegeta TV).
 *
 *  Architecture
 *  ------------
 *  • Pas de backend propre : zéro requête réseau directe à l'init.
 *  • Liste curée de chaînes Sport canoniques (~25 entrées). Chaque chaîne a
 *    un mapping vers les keys utilisées par chaque provider (différentes :
 *    WiTV strip "+", OLA/Vegeta remplacent "+" par "plus").
 *  • Une chaîne = 1 seule entrée dans le home (pas de doublon).
 *  • Si plusieurs providers la possèdent → 1 server par provider qui répond.
 *  • À la lecture, getVideo délègue au provider d'origine via le préfixe ID.
 *
 *  Anti-récursion / risque
 *  -----------------------
 *  • Aucun appel circulaire : Sport Live consomme WiTv/OlaTv/VegetaTv mais
 *    les providers IPTV n'ont aucun backup chez Sport Live.
 *  • Délégation getVideo via prefix `sportlive__witv__` / `sportlive__ola__` /
 *    `sportlive__vegeta__`.
 *  • Timeouts 6 s par provider pour ne pas bloquer le user si l'un est down.
 */
object SportLiveProvider : Provider, IptvProvider {

    override val name = "Sport Live"

    /** Pas de backend propre — on agrège 3 providers. */
    override val baseUrl: String = "https://localhost/"

    override val logo: String =
        "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_sportlive"

    override val language = "fr"

    private const val TAG = "SportLiveProvider"

    /** Stream zapping flow — IptvProvider exige un SharedFlow pour les
     *  variantes additionnelles découvertes pendant la lecture. On le garde
     *  vide (aucun stream auto-injecté). */
    private val _additionalServersFlow = MutableSharedFlow<Video.Server>()
    override val additionalServersFlow: SharedFlow<Video.Server> = _additionalServersFlow.asSharedFlow()

    // ─────────────── Catalogue curé ───────────────

    private data class SportChannel(
        val displayName: String,
        /** Clé WiTV : norm(displayName) — strip non-alphanumeric, sports→sport.
         *  Pour Canal+ Sport → "canalsport". Pour beIN Sports 1 → "beinsport1". */
        val witvKey: String,
        /** Clé OLA/Vegeta : "+" → "plus", "&" → "and", strip non-alphanumeric.
         *  Pour Canal+ Sport → "canalplussport". */
        val olaVegetaKey: String,
    )

    private val sportChannels: List<SportChannel> = listOf(
        SportChannel("Canal+ Sport", "canalsport", "canalplussport"),
        SportChannel("Canal+ Sport 360", "canalsport360", "canalplussport360"),
        SportChannel("Canal+ Foot", "canalfoot", "canalplusfoot"),
        SportChannel("beIN Sports 1", "beinsport1", "beinsports1"),
        SportChannel("beIN Sports 2", "beinsport2", "beinsports2"),
        SportChannel("beIN Sports 3", "beinsport3", "beinsports3"),
        SportChannel("beIN Sports MAX 4", "beinsportmax4", "beinsportmax4"),
        SportChannel("beIN Sports MAX 5", "beinsportmax5", "beinsportmax5"),
        SportChannel("beIN Sports MAX 6", "beinsportmax6", "beinsportmax6"),
        SportChannel("beIN Sports MAX 7", "beinsportmax7", "beinsportmax7"),
        SportChannel("beIN Sports MAX 8", "beinsportmax8", "beinsportmax8"),
        SportChannel("beIN Sports MAX 9", "beinsportmax9", "beinsportmax9"),
        SportChannel("beIN Sports MAX 10", "beinsportmax10", "beinsportmax10"),
        SportChannel("RMC Sport 1", "rmcsport1", "rmcsport1"),
        SportChannel("RMC Sport 2", "rmcsport2", "rmcsport2"),
        SportChannel("RMC Sport 3", "rmcsport3", "rmcsport3"),
        SportChannel("RMC Sport 4", "rmcsport4", "rmcsport4"),
        SportChannel("Eurosport 1", "eurosport1", "eurosport1"),
        SportChannel("Eurosport 2", "eurosport2", "eurosport2"),
        SportChannel("Eurosport News", "eurosportnews", "eurosportnews"),
        SportChannel("L'Équipe", "lequipe", "lequipe"),
        SportChannel("La Chaîne L'Équipe", "lachainelequipe", "lachainelequipe"),
        SportChannel("Infosport+", "infosport", "infosportplus"),
    )

    /** Map sport_live id → SportChannel (lookup rapide). */
    private val channelById: Map<String, SportChannel> by lazy {
        sportChannels.associateBy { "sportlive::${it.witvKey}" }
    }

    /** Logo placeholder via ui-avatars : carré avec initiales sur fond vert
     *  cohérent avec le néon vert du logo Sport Live. */
    private fun logoUrlFor(channelName: String): String {
        val initials = channelName
            .replace(Regex("[^A-Za-z0-9 +]"), "")
            .split(" ")
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "S" }
        val encoded = java.net.URLEncoder.encode(initials, "UTF-8")
        return "https://ui-avatars.com/api/?name=$encoded&background=00B600&color=fff&size=512&bold=true&format=png"
    }

    /** Construit un TvShow avec les mêmes champs que les autres providers IPTV
     *  (`providerName` est critique pour le routing du mini player). */
    private fun channelToTvShow(c: SportChannel): TvShow = TvShow(
        id = "sportlive::${c.witvKey}",
        title = c.displayName,
        overview = "Chaîne sport — agrégée depuis WiTV, OLA TV et Vegeta TV.",
        quality = "Live",
        poster = logoUrlFor(c.displayName),
        banner = logoUrlFor(c.displayName),
        providerName = name,
    )

    // ─────────────── Provider impl ───────────────

    // ─────────── OTF TV — 4e source (BeIN/Alwan/Arabic Sports = 38 chaînes) ───────────

    private const val OTF_API_URL = "https://app.otf-tv.com/otf/authV3.php"
    private const val OTF_AES_KEY = "@z5wFi5vDgtF_vds"

    /** Cache process-wide : key normalisée → liste d'URLs m3u8 OTF.
     *  TTL 30 min — l'API OTF a parfois du rate-limiting. */
    @Volatile private var otfStreamCache: Map<String, List<String>> = emptyMap()
    @Volatile private var otfCacheLoadedAtMs: Long = 0L
    private const val OTF_CACHE_TTL_MS = 30L * 60 * 1000

    /** Norm WiTV-style : "Canal+ Sport" → "canalsport".
     *  Sert à matcher les channel names OTF avec nos witvKey. */
    private fun normForOtf(s: String): String {
        var n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("\\[.*?]"), " ")
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("[^a-z0-9]"), "")
        n = n.replace("sports", "sport")
        return n
    }

    private fun otfEncrypt(plaintext: String): String {
        val keySpec = SecretKeySpec(OTF_AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(iv + ct, android.util.Base64.DEFAULT)
    }

    private fun otfDecrypt(encrypted: String): String {
        val keySpec = SecretKeySpec(OTF_AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        val raw = android.util.Base64.decode(encrypted, android.util.Base64.DEFAULT)
        val iv = raw.copyOfRange(0, 16)
        val ct = raw.copyOfRange(16, raw.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private val otfClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Charge la liste des chaînes OTF + leurs m3u8, parse uniquement les groupes
     *  Sport (BeIN/Alwan/Arabic Sports). Mémorise dans `otfStreamCache`.
     *  Idempotent : ne re-fetch pas si cache frais (<30 min). */
    private suspend fun ensureOtfCache() = withContext(Dispatchers.IO) {
        val age = System.currentTimeMillis() - otfCacheLoadedAtMs
        if (otfStreamCache.isNotEmpty() && age < OTF_CACHE_TTL_MS) return@withContext
        try {
            val deviceId = "sf" + android.os.Build.FINGERPRINT.hashCode().toUInt().toString(16).padStart(8, '0') + "otf"
            val hash = otfEncrypt("5wF${deviceId}_Opd")
            val formBody = FormBody.Builder()
                .add("DeviceID", deviceId).add("hash", hash).build()
            val req = Request.Builder().url(OTF_API_URL).post(formBody)
                .header("User-Agent", SPORTS_USER_AGENT).build()
            val body = otfClient.newCall(req).execute().body?.string() ?: return@withContext
            if (body.length < 50 || body.contains("denied") || body.contains("error")) {
                Log.w(TAG, "OTF: API error or rate-limit (${body.take(80)})")
                return@withContext
            }
            val decrypted = otfDecrypt(body)
                .replace(Regex(",\\s*\\]"), "]")
                .replace(Regex(",\\s*\\}"), "}")
            val json = JSONObject(decrypted)
            val streams = json.optJSONArray("Streams") ?: return@withContext

            // 2026-05-08 v2 : on filtre UNIQUEMENT le groupe "France" (57 chaînes FR :
            // Canal+ Sport, beINSport1/2/3, RMC.Sport1/2, Eurosport1/2, etc.).
            // Les groupes "BeIN Sports", "Alwan Sports", "Arabic Sports" sont
            // en arabe → on les ignore strictement pour rester FR-only.
            val map = mutableMapOf<String, MutableList<String>>()
            for (g in 0 until streams.length()) {
                val group = streams.optJSONObject(g) ?: continue
                val groupName = group.optString("name", "").lowercase()
                if (groupName != "france") continue
                val channels = group.optJSONArray("Channels") ?: continue
                for (c in 0 until channels.length()) {
                    val ch = channels.optJSONObject(c) ?: continue
                    val chName = ch.optString("name", "").trim()
                    if (chName.isBlank()) continue
                    val key = normForOtf(chName)
                    if (key.isEmpty()) continue
                    val vq = ch.optJSONArray("vq") ?: continue
                    for (q in 0 until vq.length()) {
                        val u = vq.optJSONObject(q)?.optString("url", "")?.trim() ?: continue
                        if (u.startsWith("http")) {
                            map.getOrPut(key) { mutableListOf() }.add(u)
                        }
                    }
                }
            }
            otfStreamCache = map
            otfCacheLoadedAtMs = System.currentTimeMillis()
            Log.d(TAG, "OTF: cache rempli — ${map.size} channels sport, total ${map.values.sumOf { it.size }} URLs")
        } catch (e: Exception) {
            Log.w(TAG, "OTF: load failed — ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Construit les Video.Server pour une chaîne donnée depuis le cache OTF.
     *  Match par norm(channelName) — "beIN Sports 1" → "beinsport1" matche.
     *  IMPORTANT : pour beIN Sports OTF n'a que la version Moyen-Orient (arabe).
     *  On les garde car le user peut vouloir l'image (le sport est visuel) même
     *  avec commentary étranger. Label "MENA" (Middle East/North Africa)
     *  prévient l'utilisateur. */
    private suspend fun otfServersFor(channel: SportChannel): List<Video.Server> {
        ensureOtfCache()
        val urls = otfStreamCache[channel.witvKey] ?: return emptyList()
        return urls.mapIndexed { idx, url ->
            Video.Server(
                id = "sportlive__otf__${channel.witvKey}_$idx",
                name = "OTF TV [FR] Q${idx + 1}",
                src = url,
            )
        }
    }

    // ─────────── Matchs du jour via ESPN Scoreboard API (public, sans key) ───────────

    /** Liste des ligues ESPN à interroger.
     *  Format : (espnPath, displayName, defaultFrChannelKey).
     *  La FR channel est celle qui diffuse PRINCIPALEMENT — au click on
     *  donne accès à plusieurs chaînes (Canal+/beIN/RMC) car la diffusion
     *  exacte par match peut varier.
     *
     *  Source diffusion 2025-26 :
     *  - Ligue 1 : Ligue1+ (8 matchs/9), beIN Sports 1 (1 match/9)
     *  - Premier League : DAZN (actuellement Canal+ Foot)
     *  - La Liga, Serie A, Bundesliga, Liga PT : beIN Sports 1
     *  - Champions / Europa / Conference : Canal+ Sport
     *  - NBA : beIN Sports 1
     *  - NHL, MLB : pas en clair en France
     *  - F1, MotoGP : Canal+ Sport
     */
    private data class EspnLeague(
        val espnSport: String,      // "soccer", "basketball", "hockey", etc.
        val espnLeague: String,     // "fra.1", "uefa.champions", etc.
        val displayName: String,    // "Ligue 1", "Champions League"
        val frChannelKey: String,   // witvKey de la chaîne FR diffuseur principale
    )

    private val espnLeagues = listOf(
        // Soccer FR + EU
        EspnLeague("soccer", "fra.1", "Ligue 1", "canalfoot"),
        EspnLeague("soccer", "fra.2", "Ligue 2", "beinsport1"),
        EspnLeague("soccer", "eng.1", "Premier League", "canalfoot"),
        EspnLeague("soccer", "esp.1", "La Liga", "beinsport1"),
        EspnLeague("soccer", "ita.1", "Serie A", "beinsport1"),
        EspnLeague("soccer", "ger.1", "Bundesliga", "beinsport1"),
        EspnLeague("soccer", "por.1", "Liga Portugal", "beinsport1"),
        EspnLeague("soccer", "uefa.champions", "Champions League", "canalsport"),
        EspnLeague("soccer", "uefa.europa", "Europa League", "canalsport"),
        EspnLeague("soccer", "uefa.conference", "Conference League", "canalsport"),
        EspnLeague("soccer", "uefa.nations_league_a", "Nations League", "canalsport"),
        EspnLeague("soccer", "fifa.world", "Coupe du Monde", "canalsport"),
        // Basket
        EspnLeague("basketball", "nba", "NBA", "beinsport1"),
        // F1 / MotoGP — pas de scoreboard ESPN, on les skippe
    )

    /** Fetch un league depuis ESPN Scoreboard public. */
    private suspend fun fetchEspnLeague(l: EspnLeague): List<TvShow> = withContext(Dispatchers.IO) {
        try {
            val url = "https://site.api.espn.com/apis/site/v2/sports/${l.espnSport}/${l.espnLeague}/scoreboard"
            val req = Request.Builder().url(url)
                .header("User-Agent", SPORTS_USER_AGENT).build()
            val body = sportsClient.newCall(req).execute().body?.string() ?: return@withContext emptyList()
            val obj = org.json.JSONObject(body)
            val events = obj.optJSONArray("events") ?: return@withContext emptyList()
            (0 until events.length()).mapNotNull { i ->
                val e = events.optJSONObject(i) ?: return@mapNotNull null
                val eventId = e.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val competitions = e.optJSONArray("competitions") ?: return@mapNotNull null
                val comp = competitions.optJSONObject(0) ?: return@mapNotNull null
                val competitors = comp.optJSONArray("competitors") ?: return@mapNotNull null
                if (competitors.length() < 2) return@mapNotNull null
                val home = competitors.optJSONObject(0)?.optJSONObject("team")?.optString("displayName")
                    ?: return@mapNotNull null
                val away = competitors.optJSONObject(1)?.optJSONObject("team")?.optString("displayName")
                    ?: return@mapNotNull null
                val date = e.optString("date").take(16)
                val status = e.optJSONObject("status")?.optJSONObject("type")?.optString("description") ?: "?"
                val title = "$home - $away"
                val timeOnly = if (date.length >= 16) date.substring(11, 16) else ""
                val titleWithTime = if (timeOnly.isNotBlank()) "[$timeOnly] $title — ${l.displayName}" else "$title — ${l.displayName}"
                TvShow(
                    id = "match::${l.frChannelKey}::$eventId",
                    title = titleWithTime,
                    overview = "${l.displayName} • $status",
                    poster = null,
                    providerName = name,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "ESPN ${l.espnLeague}: ${e.message}")
            emptyList()
        }
    }

    /** Charge tous les matchs du jour en parallèle. */
    private suspend fun loadFootballMatches(): List<TvShow> = coroutineScope {
        espnLeagues.map { league -> async { fetchEspnLeague(league) } }
            .awaitAll()
            .flatten()
            .take(40)
    }

    // ─────────── Sport en Direct (events sportsonline.vc — désactivé) ───────────

    private const val SPORTS_PROG_URL = "https://sportsonline.vc/prog.txt"
    private const val SPORTS_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val sportsClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** Charge les events live depuis sportsonline.vc/prog.txt.
     *  Format de ligne attendu : "Match name | https://embed.url".
     *  Limité à 20 events. Retourne vide si erreur réseau. */
    private suspend fun loadSportsOnlineEvents(): List<TvShow> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(SPORTS_PROG_URL)
                .header("User-Agent", SPORTS_USER_AGENT).build()
            val text = sportsClient.newCall(req).execute().body?.string() ?: return@withContext emptyList()
            text.lines()
                .filter { it.contains("|") && it.contains("http") }
                .take(20)
                .mapNotNull { line ->
                    val parts = line.split("|", limit = 2)
                    if (parts.size < 2) return@mapNotNull null
                    val event = parts[0].trim()
                    val url = parts[1].trim()
                    if (event.isBlank() || url.isBlank()) return@mapNotNull null
                    TvShow(
                        id = "sport::$url",
                        title = event,
                        poster = null,
                        providerName = name,
                    )
                }
        } catch (e: Exception) {
            Log.w(TAG, "SportsOnline events error: ${e.message}")
            emptyList()
        }
    }

    // ─── streamed.pk API — events live multi-sport (REST publique) ──────
    private const val STREAMED_PK_BASE = "https://streamed.pk"

    /** Charge les matchs live depuis streamed.pk/api/matches/live (multi-sport).
     *  Format : id "streamedpk::<source>::<matchSrcId>" pour pouvoir router à la
     *  lecture vers /api/stream/{source}/{id}. Ne load PAS encore le stream
     *  (lazy : streams résolus à getServers pour économiser les requêtes). */
    private suspend fun loadStreamedPkEvents(): List<TvShow> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$STREAMED_PK_BASE/api/matches/live")
                .header("User-Agent", SPORTS_USER_AGENT).build()
            val body = sportsClient.newCall(req).execute().body?.string() ?: return@withContext emptyList()
            if (!body.startsWith("[")) return@withContext emptyList()
            val arr = org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = m.optString("title").trim()
                if (title.isBlank()) return@mapNotNull null
                val sources = m.optJSONArray("sources") ?: return@mapNotNull null
                if (sources.length() == 0) return@mapNotNull null
                // On stocke le 1er source dans l'id ; à la lecture on tentera tous
                val s0 = sources.optJSONObject(0) ?: return@mapNotNull null
                val source = s0.optString("source")
                val srcId = s0.optString("id")
                if (source.isBlank() || srcId.isBlank()) return@mapNotNull null
                val poster = m.optString("poster").takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("http")) it else "$STREAMED_PK_BASE$it" }
                TvShow(
                    id = "streamedpk::$source::$srcId",
                    title = title,
                    poster = poster,
                    providerName = name,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "streamed.pk events error: ${e.message}")
            emptyList()
        }
    }

    /** Norm pour dédup events : minuscules, sans accents/ponctuation, "vs" → " ". */
    private fun normEventTitle(s: String): String {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("\\bvs\\b"), " ")
            .replace(Regex("\\bcontre\\b"), " ")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Combine events des 2 sources, dédup par titre normalisé.
     *  Priorité streamed.pk (API plus propre, plus de méta).
     *  Limite 30 items. */
    private suspend fun loadAllEvents(): List<TvShow> = coroutineScope {
        val streamedD = async { loadStreamedPkEvents() }
        val sportsD = async { loadSportsOnlineEvents() }
        val streamed = streamedD.await()
        val sports = sportsD.await()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<TvShow>()
        // streamed.pk d'abord (priorité)
        for (e in streamed) {
            val k = normEventTitle(e.title)
            if (k.isNotBlank() && seen.add(k)) result += e
        }
        // sportsonline.vc en complément
        for (e in sports) {
            val k = normEventTitle(e.title)
            if (k.isNotBlank() && seen.add(k)) result += e
        }
        Log.d(TAG, "events combinés : streamed.pk=${streamed.size}, sportsonline=${sports.size}, dédup=${result.size}")
        result.take(30)
    }

    /** Helper : mappe une liste de displayNames vers les TvShow (skip si non trouvé,
     *  skip si la chaîne est bannie). */
    private fun byNames(names: List<String>): List<AppAdapter.Item> {
        val byName = sportChannels.associateBy { it.displayName }
        return names.mapNotNull { name ->
            val c = byName[name] ?: return@mapNotNull null
            val id = "sportlive::${c.witvKey}"
            if (com.streamflixreborn.streamflix.fragments.player.settings
                    .IptvBannedChannels.isBanned(id)) return@mapNotNull null
            channelToTvShow(c)
        }
    }

    override suspend fun getHome(): List<Category> {
        val sections = mutableListOf<Category>()

        // 2026-05-07 : pas de carrousel FEATURED — comme WiTV/OLA/Vegeta, on
        // affiche directement les rangs de chaînes (1 clic = mini-player,
        // 2e clic = grand player, comportement géré par MiniPlayerController
        // via le check `provider is IptvProvider`).

        // ─── 0) Matchs du jour (via ESPN Scoreboard API public) ───
        // Approche Vegeta TV : on liste les matchs du jour et au click on
        // ouvre la chaîne FR (Canal+/beIN/RMC) qui les diffuse via WiTV/OLA/
        // Vegeta. Donc commentary FR garanti.
        // 2026-05-08 : sportsonline.vc retiré (PT/EN/BR seulement, pas FR).
        // Timeout 5s STRICT — si ESPN lent ou indispo, on skip la section
        // (ne bloque pas les autres rangs de chaînes).
        try {
            val matches = withTimeoutOrNull(5_000L) { loadFootballMatches() } ?: emptyList()
            if (matches.isNotEmpty()) {
                sections.add(Category(name = "Matchs du jour", list = matches))
            }
        } catch (_: Exception) {}

        // ─── 1) Canal+ ───
        val canalPlus = byNames(listOf(
            "Canal+ Sport",
            "Canal+ Sport 360",
            "Canal+ Foot",
        ))
        if (canalPlus.isNotEmpty()) sections.add(Category(name = "Canal+", list = canalPlus))

        // ─── 2) beIN Sports (principales) ───
        val bein = byNames(listOf(
            "beIN Sports 1",
            "beIN Sports 2",
            "beIN Sports 3",
        ))
        if (bein.isNotEmpty()) sections.add(Category(name = "beIN Sports", list = bein))

        // ─── 3) beIN Sports MAX (événementielles) ───
        val beinMax = byNames(listOf(
            "beIN Sports MAX 4",
            "beIN Sports MAX 5",
            "beIN Sports MAX 6",
            "beIN Sports MAX 7",
            "beIN Sports MAX 8",
            "beIN Sports MAX 9",
            "beIN Sports MAX 10",
        ))
        if (beinMax.isNotEmpty()) sections.add(Category(name = "beIN Sports MAX", list = beinMax))

        // ─── 4) RMC Sport ───
        val rmc = byNames(listOf(
            "RMC Sport 1",
            "RMC Sport 2",
            "RMC Sport 3",
            "RMC Sport 4",
        ))
        if (rmc.isNotEmpty()) sections.add(Category(name = "RMC Sport", list = rmc))

        // ─── 5) Eurosport ───
        val euro = byNames(listOf(
            "Eurosport 1",
            "Eurosport 2",
            "Eurosport News",
        ))
        if (euro.isNotEmpty()) sections.add(Category(name = "Eurosport", list = euro))

        // ─── 6) L'Équipe & info sport ───
        val equipe = byNames(listOf(
            "La Chaîne L'Équipe",
            "L'Équipe",
            "Infosport+",
        ))
        if (equipe.isNotEmpty()) sections.add(Category(name = "L'Équipe & Info Sport", list = equipe))

        // 2026-05-08 (pivot) : section "Favoris" RETIRÉE de Sport Live.
        // Favoris UNIQUEMENT dans TV Hub (option 1). Note : si l'onglet
        // global "Favoris" du bottom-bar dépendait de cette section, il
        // faudra le câbler à TV Hub à la place.

        // 2026-05-08 : section "✕ Chaînes bannies" EN BAS du home.
        // Dossier fixe pour ranger les chaînes sport bannies. Long-press → menu
        // "Débannir" pour réactiver.
        try {
            val banned = sportChannels.filter { c ->
                com.streamflixreborn.streamflix.fragments.player.settings
                    .IptvBannedChannels.isBanned("sportlive::${c.witvKey}")
            }
            if (banned.isNotEmpty()) {
                val bannedItems = banned.map { channelToTvShow(it) }
                sections.add(Category(name = "✕ Chaînes bannies", list = bannedItems))
            }
        } catch (_: Throwable) { }

        return sections
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        return sportChannels
            .filter { it.displayName.lowercase().contains(q) }
            .map { channelToTvShow(it) }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page > 1) return emptyList()
        return sportChannels.map { channelToTvShow(it) }
    }

    override suspend fun getMovie(id: String): Movie =
        throw UnsupportedOperationException("Sport Live n'a pas de films")

    override suspend fun getTvShow(id: String): TvShow {
        // Cas event "Sport en Direct" — via sportsonline.vc (id sport::<url>)
        // ou via streamed.pk (id streamedpk::<source>::<srcId>)
        // ou via ESPN scoreboard (id match::<frChannelKey>::<eventId>)
        if (id.startsWith("sport::") || id.startsWith("streamedpk::") || id.startsWith("match::")) {
            val title = if (id.startsWith("match::")) "Match en direct" else "Sport en Direct"
            return TvShow(
                id = id,
                title = title,
                overview = "Événement sportif en direct",
                providerName = name,
                seasons = listOf(
                    Season(
                        id = id,
                        number = 1,
                        title = "En Direct",
                        episodes = listOf(Episode(id = id, number = 1, title = "Regarder")),
                    )
                ),
            )
        }
        val c = channelById[id]
            ?: throw IllegalArgumentException("Chaîne sport inconnue: $id")
        return TvShow(
            id = "sportlive::${c.witvKey}",
            title = c.displayName,
            overview = "Chaîne sport — agrégée depuis WiTV, OLA TV et Vegeta TV.",
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
                            title = c.displayName,
                        )
                    ),
                )
            ),
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        if (seasonId.startsWith("sport::") || seasonId.startsWith("streamedpk::") || seasonId.startsWith("match::")) {
            return listOf(Episode(id = seasonId, number = 1, title = "Regarder"))
        }
        val c = channelById[seasonId] ?: return emptyList()
        return listOf(Episode(id = seasonId, number = 1, title = c.displayName, poster = null))
    }

    override suspend fun getGenre(id: String, page: Int): Genre =
        throw UnsupportedOperationException("Sport Live n'a pas de genres")

    override suspend fun getPeople(id: String, page: Int): People =
        throw UnsupportedOperationException("Sport Live n'a pas de people")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        // Cas match ESPN (id format `match::<frChannelKey>::<eventId>`)
        // → on remplace par les servers de la chaîne FR diffusant le match,
        // PLUS quelques alternatives (Canal+ Sport, beIN Sports 1, RMC Sport 1).
        if (id.startsWith("match::")) {
            val rest = id.removePrefix("match::")
            val parts = rest.split("::", limit = 2)
            val mainKey = parts.getOrNull(0) ?: "canalsport"
            // 3 fallback channels pour couvrir cas variés (Canal+ peut diffuser
            // une compétition prévue beIN, etc.)
            val fallbackKeys = listOf(mainKey, "canalsport", "beinsport1", "rmcsport1").distinct()
            return coroutineScope {
                fallbackKeys.flatMap { fk ->
                    val canonical = sportChannels.firstOrNull { it.witvKey == fk } ?: return@flatMap emptyList()
                    // Réutilise la logique getServers d'une chaîne canonique
                    val fakeId = "sportlive::${canonical.witvKey}"
                    runCatching { getServers(fakeId, videoType) }.getOrElse { emptyList() }
                }.distinctBy { it.id }.take(15)
            }
        }
        // Cas event sportsonline.vc (id format `sport::<url>`)
        // 2026-05-08 : extraction via MeritendExtractor (WebView qui intercepte
        // le m3u8 chargé par clappr) — au lieu de déléguer à WiTV.getVideo qui
        // ne sait pas suivre les embeds modernes type meritend.net.
        if (id.startsWith("sport::")) {
            val url = id.removePrefix("sport::")
            return listOf(
                Video.Server(
                    id = "sportlive__sport__$url",
                    name = "Sport en Direct",
                    src = url,
                )
            )
        }
        // Cas event streamed.pk (id format `streamedpk::<source>::<srcId>`)
        // → fetch /api/stream/{source}/{id} → liste de streams (streamNo 1..N).
        // Chaque streamNo = un Video.Server distinct (HD, langues différentes).
        if (id.startsWith("streamedpk::")) {
            return withContext(Dispatchers.IO) {
                runCatching {
                    val rest = id.removePrefix("streamedpk::")
                    val parts = rest.split("::", limit = 2)
                    if (parts.size != 2) return@runCatching emptyList<Video.Server>()
                    val source = parts[0]
                    val srcId = parts[1]
                    val req = Request.Builder()
                        .url("$STREAMED_PK_BASE/api/stream/$source/$srcId")
                        .header("User-Agent", SPORTS_USER_AGENT).build()
                    val body = withTimeoutOrNull(8_000L) {
                        sportsClient.newCall(req).execute().body?.string()
                    } ?: return@runCatching emptyList<Video.Server>()
                    if (!body.startsWith("[")) return@runCatching emptyList<Video.Server>()
                    val arr = org.json.JSONArray(body)
                    (0 until arr.length()).mapNotNull { i ->
                        val s = arr.optJSONObject(i) ?: return@mapNotNull null
                        val embed = s.optString("embedUrl").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val streamNo = s.optInt("streamNo", i + 1)
                        val lang = s.optString("language").ifBlank { "Live" }
                        val hd = if (s.optBoolean("hd", false)) " HD" else ""
                        Video.Server(
                            id = "sportlive__streamedpk__${source}::${srcId}::$streamNo",
                            name = "streamed.pk — Stream $streamNo ($lang$hd)",
                            src = embed,
                        )
                    }
                }.getOrElse {
                    Log.w(TAG, "streamed.pk getServers '$id': ${it.message}")
                    emptyList<Video.Server>()
                }
            }
        }
        val c = channelById[id] ?: run {
            Log.w(TAG, "getServers : chaîne inconnue id=$id")
            return emptyList()
        }
        return coroutineScope {
            val witvD = async {
                runCatching {
                    withTimeoutOrNull(6_000L) {
                        WiTvProvider.getServers("ch::${c.witvKey}", videoType)
                    } ?: emptyList()
                }.getOrElse {
                    Log.w(TAG, "WiTV getServers '${c.displayName}': ${it.message}")
                    emptyList()
                }.map { srv ->
                    srv.copy(
                        id = "sportlive__witv__${srv.id}",
                        name = "WiTV — ${srv.name}",
                    )
                }
            }
            val olaD = async {
                runCatching {
                    withTimeoutOrNull(6_000L) {
                        OlaTvProvider.getServers("ola::${c.olaVegetaKey}", videoType)
                    } ?: emptyList()
                }.getOrElse {
                    Log.w(TAG, "OLA TV getServers '${c.displayName}': ${it.message}")
                    emptyList()
                }.map { srv ->
                    srv.copy(
                        id = "sportlive__ola__${srv.id}",
                        name = "OLA TV — ${srv.name}",
                    )
                }
            }
            val vegetaD = async {
                runCatching {
                    // 2026-05-08 : timeout augmenté de 6s à 15s. Vegeta TV
                    // a 71+ servers par chaîne avec backfill async — 6s
                    // retournait 1-2 servers (toujours les mêmes), 15s
                    // permet de récupérer 8-10 variants distincts.
                    withTimeoutOrNull(15_000L) {
                        VegetaTvProvider.getServers("vegeta::${c.olaVegetaKey}", videoType)
                    } ?: emptyList()
                }.getOrElse {
                    Log.w(TAG, "Vegeta TV getServers '${c.displayName}': ${it.message}")
                    emptyList()
                }.map { srv ->
                    srv.copy(
                        id = "sportlive__vegeta__${srv.id}",
                        name = "Vegeta TV — ${srv.name}",
                    )
                }
            }
            // 2026-05-08 v2 : OTF réactivé en filtrant UNIQUEMENT le groupe "France"
            // (57 chaînes FR : Canal+ Sport, beINSport1/2/3, RMC.Sport1/2, Eurosport1/2,
            // Infosport, etc.). Les groupes BeIN/Alwan/Arabic Sports sont arabes → ignorés.
            val otfD = async {
                runCatching {
                    withTimeoutOrNull(10_000L) { otfServersFor(c) } ?: emptyList()
                }.getOrElse {
                    Log.w(TAG, "OTF getServers '${c.displayName}': ${it.message}")
                    emptyList()
                }
            }
            // 2026-05-09 : 5e source — Movix LiveTV via Vavoo. 219 chaînes Sport
            // dans le catalog Vavoo France, dont la plupart des FR mainstream
            // (Canal+ Sport, beIN, RMC, Eurosport). On match par nom normalisé
            // sur witvKey et on prend les 3 premières variantes (base + HD + FHD)
            // pour offrir le choix de qualité dans le picker. Chaque variante a
            // 1-3 mirrors CDN renvoyés par /api/livetv/stream/tv/<id>.
            val movixD = async {
                runCatching {
                    withTimeoutOrNull(10_000L) { movixServersFor(c) } ?: emptyList()
                }.getOrElse {
                    Log.w(TAG, "Movix LiveTV getServers '${c.displayName}': ${it.message}")
                    emptyList()
                }
            }
            val combined = witvD.await() + olaD.await() + vegetaD.await() + otfD.await() + movixD.await()
            Log.d(TAG, "getServers '${c.displayName}' → ${combined.size} servers (WiTV/OLA/Vegeta/OTF/Movix combinés)")
            combined
        }
    }

    /**
     * Récupère les Video.Server Movix LiveTV pour une chaîne sport canonique.
     * Match par witvKey (normForOtf-style) sur le catalog Vavoo, prend les
     * 3 premières variantes par longueur de nom (base d'abord), puis fetch
     * leurs mirrors via [MovixLiveTvProvider.getServers].
     *
     * Idéalement on fait 1-3 HTTP calls en parallèle (un par variante).
     * Si une variante n'a pas de mirror disponible, on l'ignore silencieusement.
     */
    private suspend fun movixServersFor(channel: SportChannel): List<Video.Server> = coroutineScope {
        val rawIds = MovixLiveTvProvider.findRawIdsForNormKey(channel.witvKey, maxResults = 3)
        if (rawIds.isEmpty()) {
            Log.d(TAG, "Movix: pas de match pour ${channel.witvKey}")
            return@coroutineScope emptyList<Video.Server>()
        }
        Log.d(TAG, "Movix: ${rawIds.size} variantes pour ${channel.witvKey} → $rawIds")
        // Fetch en parallèle pour minimiser la latence totale.
        rawIds.map { rawId ->
            async {
                runCatching {
                    val movixId = "movixlivetv::$rawId"
                    MovixLiveTvProvider.getServers(movixId, Video.Type.Episode(
                        id = movixId,
                        number = 1,
                        title = channel.displayName,
                        poster = null, overview = null,
                        tvShow = Video.Type.Episode.TvShow(
                            id = movixId, title = channel.displayName,
                            poster = null, banner = null,
                            releaseDate = null, imdbId = null,
                        ),
                        season = Video.Type.Episode.Season(number = 1, title = "Live"),
                    ))
                }.getOrElse { emptyList() }
            }
        }.awaitAll().flatten().map { srv ->
            // Préfixe pour identifier l'origine au moment de getVideo, et
            // libellé clair dans le picker du player.
            srv.copy(
                id = "sportlive__movix__${srv.id}",
                name = "Movix LiveTV — ${srv.name}",
            )
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // Délégation au provider d'origine selon le préfixe.
        return when {
            server.id.startsWith("sportlive__witv__") -> {
                val original = server.copy(id = server.id.removePrefix("sportlive__witv__"))
                runCatching { WiTvProvider.getVideo(original) }.getOrElse { e ->
                    Log.w(TAG, "WiTV getVideo failed: ${e.message}")
                    Video(source = original.src)
                }
            }
            server.id.startsWith("sportlive__ola__") -> {
                val original = server.copy(id = server.id.removePrefix("sportlive__ola__"))
                runCatching { OlaTvProvider.getVideo(original) }.getOrElse { e ->
                    Log.w(TAG, "OLA TV getVideo failed: ${e.message}")
                    Video(source = original.src)
                }
            }
            server.id.startsWith("sportlive__vegeta__") -> {
                val original = server.copy(id = server.id.removePrefix("sportlive__vegeta__"))
                runCatching { VegetaTvProvider.getVideo(original) }.getOrElse { e ->
                    Log.w(TAG, "Vegeta TV getVideo failed: ${e.message}")
                    Video(source = original.src)
                }
            }
            server.id.startsWith("sportlive__otf__") -> {
                // OTF returns direct m3u8 URLs sur stm.linkip.org → pas
                // d'extraction nécessaire, juste les bons headers.
                Video(
                    source = server.src,
                    headers = mapOf(
                        "User-Agent" to SPORTS_USER_AGENT,
                        "Referer" to "https://www.otf-tv.com/",
                    ),
                )
            }
            server.id.startsWith("sportlive__movix__") -> {
                // Movix LiveTV (Vavoo) renvoie déjà l'URL HLS directe avec ses
                // headers requis. Délègue à MovixLiveTvProvider.getVideo qui
                // construit le Video avec les bons headers (User-Agent Chrome
                // 116 + Referer https://vavoo.to/) et le mime type
                // APPLICATION_M3U8.
                val original = server.copy(id = server.id.removePrefix("sportlive__movix__"))
                runCatching { MovixLiveTvProvider.getVideo(original) }.getOrElse { e ->
                    Log.w(TAG, "Movix LiveTV getVideo failed: ${e.message}")
                    Video(source = original.src)
                }
            }
            server.id.startsWith("sportlive__sport__") -> {
                // Event sportsonline.vc → routé via MeritendExtractor (WebView
                // intercept m3u8). MeritendExtractor a sportssonline.click et
                // sportsonline.vc dans aliasUrls donc Extractor.extract route
                // correctement même si l'URL initiale est sportssonline.click.
                val url = server.src
                runCatching {
                    withTimeoutOrNull(35_000L) {
                        Extractor.extract(url, server)
                    } ?: Video(source = url)
                }.getOrElse { e ->
                    Log.w(TAG, "Sport event getVideo failed: ${e.message}")
                    Video(source = url)
                }
            }
            server.id.startsWith("sportlive__streamedpk__") -> {
                // streamed.pk retourne des embedUrl (ex: embedsports.top/embed/...)
                // Tente l'extraction via Extractor.extract pour résoudre m3u8.
                // Fallback : returne l'embedUrl tel quel (le player tentera).
                runCatching {
                    withTimeoutOrNull(10_000L) {
                        Extractor.extract(server.src, server)
                    } ?: Video(
                        source = server.src,
                        headers = mapOf(
                            "User-Agent" to SPORTS_USER_AGENT,
                            "Referer" to "$STREAMED_PK_BASE/",
                        ),
                    )
                }.getOrElse { e ->
                    Log.w(TAG, "streamed.pk getVideo failed: ${e.message}")
                    Video(source = server.src)
                }
            }
            else -> Video(source = server.src)
        }
    }
}
