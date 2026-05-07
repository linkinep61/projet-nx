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
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TMDb3.w780
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 2026-05-06 v2 : Cloudstream — refacto inspiré de MovixProvider.
 *
 * **Catalogue & home : 100% TMDB** (popular/top_rated/trending). Plus de
 * dépendance à l'API mobile-bff pour le browsing — TMDB fournit un catalogue
 * propre, mainstream, déjà filtré (pas de DramaBox, pas de pubs, pas de
 * contenu régional aléatoire).
 *
 * **Lecture : MovieBox+ mobile-bff via /resource**. Au moment du play, on
 * fait un search sur l'API Cloudstream (api*.aoneroom.com/wefeed-mobile-bff)
 * avec le titre TMDB + année, on prend le subjectId qui matche et on récupère
 * les streams MP4 signés via /resource (URLs `bcdn.hakunaymatata.com` sans
 * pre-roll publicitaire, contrairement à /play-info qui retourne `hcdn3` avec
 * des pubs).
 *
 * IDs :
 *   - Movie / TvShow : TMDB id (entier, e.g. "12345")
 *   - Season         : "<tmdbId>-<seasonNumber>"
 *   - Episode        : "<tmdbId>:<seasonNumber>:<episodeNumber>"
 *
 * Réfs reverse-engineering : https://github.com/Simatwa/moviebox-api (v3).
 */
object CloudstreamProvider : Provider {

    override val name = "Cloudstream"
    override val baseUrl = "https://api.themoviedb.org/3/"  // TMDB pour browsing
    override val language = "fr"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_cloudstream"

    private const val TAG = "CloudstreamProvider"

    // ── Cloudstream backend (api*.aoneroom.com / wefeed-mobile-bff) ──────

    /** Pool de hosts à essayer en cascade (fallback en cas de 403/429/500/timeout).
     *  Sondage actif (probe_hosts.ps1, 2026-05-06) confirmant les mirrors vivants :
     *    api3, api4, api5, api6, api7, api4sg, api.aoneroom.com, api.inmoviebox.com
     *  Plus quelques anticipations (api1/2/8/9) au cas où ils remontent.
     *  Si MovieBox+ migre en masse, élargir cette liste sans push d'APK = pas possible
     *  ; à terme on pourra ajouter ProviderConfigUrl pour permettre une URL custom
     *  côté utilisateur. */
    private val HOST_POOL = listOf(
        // Mirrors confirmés vivants au 2026-05-06
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api7.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api.aoneroom.com",
        "https://api.inmoviebox.com",
        // Anticipations (mirrors qui peuvent remonter ou exister à charge variable)
        "https://api1.aoneroom.com",
        "https://api2.aoneroom.com",
        "https://api8.aoneroom.com",
        "https://api9.aoneroom.com",
        "https://api1sg.aoneroom.com",
        "https://api2sg.aoneroom.com",
        "https://api3sg.aoneroom.com",
        "https://api5sg.aoneroom.com",
        "https://api6sg.aoneroom.com",
        "https://api7sg.aoneroom.com",
        "https://api1.inmoviebox.com",
        "https://api3.inmoviebox.com",
        "https://api6.inmoviebox.com",
    )

    private const val SECRET_KEY_DEFAULT_B64 = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
    private val SECRET_KEY_BYTES: ByteArray by lazy {
        android.util.Base64.decode(
            SECRET_KEY_DEFAULT_B64.padEnd(((SECRET_KEY_DEFAULT_B64.length + 3) / 4) * 4, '='),
            android.util.Base64.DEFAULT
        )
    }

    private const val USER_AGENT =
        "com.community.oneroom/50020045 (Linux; U; Android 13; en_US; 23078RKD5C; Build/TQ2A.230405.003; Cronet/135.0.7012.3)"
    private val CLIENT_INFO = """
        {"package_name":"com.community.oneroom","version_name":"3.0.03.0529.03","version_code":50020045,
        "os":"android","os_version":"13","install_ch":"ps","device_id":"a1b2c3d4e5f60718293a4b5c6d7e8f90",
        "install_store":"ps","gaid":"00000000-0000-0000-0000-000000000000","brand":"Redmi",
        "model":"23078RKD5C","system_language":"fr","net":"NETWORK_WIFI","region":"FR",
        "timezone":"Europe/Paris","sp_code":"40401","X-Play-Mode":"2"}
    """.trimIndent().replace("\n", "").replace("        ", "")

    private const val MAIN_PAGE_PATH = "/wefeed-mobile-bff/tab-operating"
    private const val SEARCH_PATH = "/wefeed-mobile-bff/subject-api/search"
    private const val SUBJECT_GET_PATH = "/wefeed-mobile-bff/subject-api/get"
    private const val RESOURCE_PATH = "/wefeed-mobile-bff/subject-api/resource"
    private const val PLAY_INFO_PATH = "/wefeed-mobile-bff/subject-api/play-info"
    private const val SEASON_INFO_PATH = "/wefeed-mobile-bff/subject-api/season-info"

    /** Regex pour identifier un suffixe de langue non-FR dans un titre. */
    private val LANG_NON_FR_REGEX = Regex(
        """\[(Hindi|Tamil|Telugu|Korean|Japanese|Indonesian|Thai|Vietnamese|""" +
        """Arabic|Spanish|Portuguese|Mandarin|Cantonese|Russian|Turkish|""" +
        """Bengali|Punjabi|Urdu|Gujarati|Marathi|Malayalam|Kannada|Sinhala|""" +
        """Burmese|Filipino|Tagalog|Khmer|Lao|Nepali|Polish|Italian|German|""" +
        """Greek|Hebrew|Persian|Farsi|Swahili|Romanian|Hungarian|Czech|""" +
        """Dutch|Swedish|Norwegian|Danish|Finnish|Ukrainian)\]""",
        RegexOption.IGNORE_CASE
    )

    /** Regex pour stripper n'importe quel suffixe `[XYZ]` à la fin du titre. */
    private val LANG_SUFFIX_REGEX = Regex("""\s*\[[A-Za-z]+\]\s*$""")

    /** Détection de DramaBox / shorts (signaux multiples). */
    private fun isShortDrama(o: JSONObject): Boolean {
        if (o.optInt("shortsEpisode", 0) > 0) return true
        val genre = o.optString("genre").orEmpty()
        if (genre.equals("Drame moderne", ignoreCase = true)) return true
        val seconds = o.optInt("seconds", 0)
        if (seconds in 1..599) return true
        return false
    }

    private val httpClient: OkHttpClient by lazy {
        Extractor.sharedClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // ── HMAC-MD5 signing ──────────────────────────────────────────────────

    private fun generateXClientToken(tsMs: Long): String {
        val ts = tsMs.toString()
        val reversed = ts.reversed()
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(reversed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$ts,$md5"
    }

    private fun sortedQueryString(query: String): String {
        if (query.isEmpty()) return ""
        val params = query.split("&").mapNotNull {
            val eq = it.indexOf("=")
            if (eq < 0) it to "" else it.substring(0, eq) to it.substring(eq + 1)
        }
        return params.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun buildCanonicalString(
        method: String, accept: String?, contentType: String?,
        url: String, body: String?, tsMs: Long,
    ): String {
        val uri = java.net.URI(url)
        val path = uri.rawPath ?: ""
        val query = sortedQueryString(uri.rawQuery ?: "")
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
        val (bodyHash, bodyLen) = if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val md5 = java.security.MessageDigest.getInstance("MD5")
                .digest(bytes.take(102_400).toByteArray())
                .joinToString("") { "%02x".format(it) }
            md5 to bytes.size.toString()
        } else "" to ""
        return "${method.uppercase()}\n${accept.orEmpty()}\n${contentType.orEmpty()}\n$bodyLen\n$tsMs\n$bodyHash\n$canonicalUrl"
    }

    private fun generateXTrSignature(
        method: String, accept: String?, contentType: String?,
        url: String, body: String?, tsMs: Long,
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, tsMs)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SECRET_KEY_BYTES, "HmacMD5"))
        val sigBytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val sigB64 = android.util.Base64.encodeToString(sigBytes, android.util.Base64.NO_WRAP)
        return "$tsMs|2|$sigB64"
    }

    private fun signedHeaders(
        method: String, url: String, body: String? = null,
        accept: String = "application/json",
        contentType: String = "application/json",
    ): Map<String, String> {
        val ts = System.currentTimeMillis()
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to accept,
            "Content-Type" to contentType,
            "Connection" to "keep-alive",
            "X-Client-Token" to generateXClientToken(ts),
            "x-tr-signature" to generateXTrSignature(method, accept, contentType, url, body, ts),
            "X-Client-Info" to CLIENT_INFO,
            "X-Client-Status" to "0",
        )
    }

    // ── HTTP avec rotation de host ──────────────────────────────────────

    /** Last-known-good host : essayé en premier à chaque requête.
     *  Évite de cycler sur 21 hosts morts avant de tomber sur un vivant. */
    @Volatile private var lastGoodHost: String? = null

    /** Retourne la liste des hosts dans l'ordre à essayer : last-good d'abord,
     *  puis le reste du pool. */
    private fun orderedHosts(): List<String> {
        val good = lastGoodHost
        return if (good != null && HOST_POOL.contains(good)) {
            listOf(good) + HOST_POOL.filterNot { it == good }
        } else HOST_POOL
    }

    private suspend fun apiGet(path: String, params: Map<String, String> = emptyMap()): JSONObject? = withContext(Dispatchers.IO) {
        val query = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val pathWithQuery = if (query.isNotBlank()) "$path?$query" else path
        for (host in orderedHosts()) {
            val url = "$host$pathWithQuery"
            try {
                val req = Request.Builder().url(url).apply {
                    signedHeaders("GET", url).forEach { (k, v) -> header(k, v) }
                }.build()
                val resp = httpClient.newCall(req).execute()
                resp.use {
                    val code = it.code
                    if (code in setOf(403, 407, 429, 500, 502, 503, 504)) {
                        Log.d(TAG, "Host $host returned $code, retry next")
                        return@use
                    }
                    if (!it.isSuccessful) return@withContext null
                    val body = it.body?.string() ?: return@withContext null
                    lastGoodHost = host  // mémorise le host qui répond
                    return@withContext JSONObject(body)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Host $host error: ${e.message}, retry next")
            }
        }
        null
    }

    private suspend fun apiPost(path: String, jsonBody: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val bodyStr = jsonBody.toString()
        for (host in orderedHosts()) {
            val url = "$host$path"
            try {
                // 2026-05-07 : Content-Type "application/json" sans charset.
                // Pour éviter qu'OkHttp ajoute un charset, on construit le RequestBody
                // avec mediaType=null puis on set Content-Type manuellement après .post().
                val req = Request.Builder().url(url)
                    .post(bodyStr.toRequestBody(null))
                    .apply {
                        signedHeaders("POST", url, bodyStr).forEach { (k, v) -> header(k, v) }
                    }.build()
                val resp = httpClient.newCall(req).execute()
                resp.use {
                    val code = it.code
                    if (code in setOf(403, 407, 429, 500, 502, 503, 504)) return@use
                    if (!it.isSuccessful) return@withContext null
                    val body = it.body?.string() ?: return@withContext null
                    lastGoodHost = host  // mémorise le host qui répond
                    return@withContext JSONObject(body)
                }
            } catch (e: Exception) {
                Log.d(TAG, "POST host $host error: ${e.message}")
            }
        }
        null
    }

    // ── TMDB → Cloudstream subjectId mapping (avec cache) ──────────────

    /** Cache process-wide : cleanTitle|year → subjectId (vide = pas trouvé). */
    private val tmdbToSubjectIdCache = ConcurrentHashMap<String, String>()

    /** Recherche dans l'API Cloudstream le subjectId qui matche `title`+`year`.
     *  Préfère un match exact sur le titre nettoyé (sans suffixe [Hindi]/etc.)
     *  et sur l'année. Fallback : meilleur match disponible. */
    /** Normalise un titre pour comparaison robuste : lowercase, sans accents,
     *  sans ponctuation, sans articles courants ("le", "la", "les", "un", "une",
     *  "the", "a"), espaces multiples écrasés. */
    private fun normalizeForMatch(s: String): String {
        var n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Strip leading articles for fuzzy matching
        n = n.replace(Regex("^(le|la|les|l|un|une|des|the|a|an)\\s+"), "")
        return n
    }

    private suspend fun findSubjectId(title: String, year: Int? = null): String? {
        val cleanQuery = TitleNormalizer.cleanForTmdbSearch(title).ifBlank { title }
        val normQuery = normalizeForMatch(cleanQuery)
        val cacheKey = "$normQuery|${year ?: 0}"
        tmdbToSubjectIdCache[cacheKey]?.let { return it.ifBlank { null } }

        val resp = apiPost(SEARCH_PATH, JSONObject().apply {
            put("keyword", cleanQuery)
            put("page", 0)
            put("perPage", 20)  // Le serveur MovieBox+ refuse perPage>20 avec LIMIT_EXCEED
        })
        if (resp == null) {
            Log.w(TAG, "findSubjectId('$cleanQuery') : apiPost retourné null (search API down)")
            return null
        }
        val items = resp.optJSONObject("data")?.optJSONArray("items")
        if (items == null || items.length() == 0) {
            Log.w(TAG, "findSubjectId('$cleanQuery') : 0 items retournés par MovieBox+")
            return null
        }

        data class Candidate(
            val sid: String, val cleanTitle: String, val normTitle: String,
            val year: Int, val subjectType: Int,
        )
        val candidates = mutableListOf<Candidate>()
        for (i in 0 until items.length()) {
            val s = items.optJSONObject(i) ?: continue
            val sid = s.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
            val itTitle = s.optString("title")
            val cleanIt = itTitle.replace(LANG_SUFFIX_REGEX, "").trim()
            val itYear = s.optString("releaseDate").take(4).toIntOrNull() ?: 0
            val itType = s.optInt("subjectType", 1)
            candidates.add(Candidate(sid, cleanIt, normalizeForMatch(cleanIt), itYear, itType))
        }
        if (candidates.isEmpty()) {
            tmdbToSubjectIdCache[cacheKey] = ""
            Log.d(TAG, "findSubjectId('$cleanQuery' year=$year): 0 candidates")
            return null
        }

        // Stratégie de matching, par ordre décroissant de confiance :
        // 1. Match normalisé EXACT + année exacte
        // 2. Match normalisé EXACT + année ±2
        // 3. Match normalisé EXACT (n'importe quelle année)
        // 4. Match préfixe (norme query est début ou contient norme candidate)
        // 5. Premier candidat (fallback : MovieBox+ a déjà trié par pertinence)

        candidates.firstOrNull { it.normTitle == normQuery && year != null && it.year == year }?.let {
            tmdbToSubjectIdCache[cacheKey] = it.sid
            Log.d(TAG, "findSubjectId('$cleanQuery' year=$year) → exact+year=${it.cleanTitle} (${it.year}) sid=${it.sid}")
            return it.sid
        }
        candidates.firstOrNull { it.normTitle == normQuery && (year == null || kotlin.math.abs(it.year - year) <= 2) }?.let {
            tmdbToSubjectIdCache[cacheKey] = it.sid
            Log.d(TAG, "findSubjectId('$cleanQuery' year=$year) → exact+nearYear=${it.cleanTitle} (${it.year}) sid=${it.sid}")
            return it.sid
        }
        candidates.firstOrNull { it.normTitle == normQuery }?.let {
            tmdbToSubjectIdCache[cacheKey] = it.sid
            Log.d(TAG, "findSubjectId('$cleanQuery' year=$year) → exactTitle=${it.cleanTitle} (${it.year}) sid=${it.sid}")
            return it.sid
        }
        // Match contains AVEC année proche : "Mission: Impossible" vs "Mission Impossible 7"
        // (year ±2 obligatoire pour éviter de matcher un autre film du même franchise)
        candidates.firstOrNull {
            (it.normTitle.contains(normQuery) || normQuery.contains(it.normTitle))
                && year != null && kotlin.math.abs(it.year - year) <= 2
        }?.let {
            tmdbToSubjectIdCache[cacheKey] = it.sid
            Log.d(TAG, "findSubjectId('$cleanQuery' year=$year) → contains+nearYear=${it.cleanTitle} (${it.year}) sid=${it.sid}")
            return it.sid
        }
        // Fallback : premier candidat avec année proche (±5) seulement, pour éviter
        // de jouer un film différent du même titre (ex: Mortal Kombat 2021 au lieu
        // de Mortal Kombat II 2026). Si pas d'année ou pas de candidat proche : null.
        if (year != null) {
            candidates.firstOrNull { kotlin.math.abs(it.year - year) <= 5 }?.let {
                tmdbToSubjectIdCache[cacheKey] = it.sid
                Log.d(TAG, "findSubjectId('$cleanQuery' year=$year) → fallback nearYear=${it.cleanTitle} (${it.year}) sid=${it.sid}")
                return it.sid
            }
        }
        Log.d(TAG, "findSubjectId('$cleanQuery' year=$year) → no match (${candidates.size} candidats, mais aucun avec année proche)")
        tmdbToSubjectIdCache[cacheKey] = ""
        return null
    }

    // ── Filtre FR audio/sub via /subject-api/get (cached) ────────────────

    /** Type de signal FR détecté pour un subject MovieBox+. */
    private enum class FrSignal { NONE, FR_NATIVE, FR_DUB, FR_SUB }

    /** Cache process-wide : subjectId → signal FR (NONE = pas de FR, autre = FR dispo). */
    private val frSignalCache = ConcurrentHashMap<String, FrSignal>()
    /** Vue compat : raccourci pour l'ancien `hasFrenchAudioOrSub`. */
    private val frAvailabilityCache = ConcurrentHashMap<String, Boolean>()

    /** Fetch /subject-api/get pour `subjectId` et détermine quel signal FR existe :
     *
     *   • FR_DUB    — dubs[] contient lanCode=fr type=0 (piste audio doublée FR)
     *   • FR_NATIVE — language=fr ou countryName=France (audio originel FR garanti)
     *   • FR_SUB    — dubs[] contient lanCode=fr type=1 (piste sous-titres FR jouable)
     *   • NONE      — aucun FR jouable
     *
     *  2026-05-06 : on NE SE FIE PLUS au champ `subtitles` du détail (catalogue
     *  théorique non garanti d'avoir un fichier réel). Vérifié sur Perfect Crown
     *  (KR) / Your Heart Will Be Broken[CAM] (RU) / Arafta (TR) : tous trois
     *  listent "Français" dans subtitles mais leurs dubs[] n'ont AUCUN track FR. */
    private suspend fun frSignalOf(subjectId: String): FrSignal {
        if (subjectId.isBlank()) return FrSignal.NONE
        frSignalCache[subjectId]?.let { return it }
        val resp = apiGet(SUBJECT_GET_PATH, mapOf("subjectId" to subjectId))
        if (resp == null) return FrSignal.NONE
        val data = resp.optJSONObject("data") ?: return FrSignal.NONE

        // 1) FR_DUB — la piste doublée FR est le signal le plus fort
        val dubs = data.optJSONArray("dubs")
        if (dubs != null) {
            for (i in 0 until dubs.length()) {
                val d = dubs.optJSONObject(i) ?: continue
                if (d.optString("lanCode").equals("fr", ignoreCase = true) &&
                    d.optInt("type", -1) == 0) {
                    frSignalCache[subjectId] = FrSignal.FR_DUB
                    return FrSignal.FR_DUB
                }
            }
        }
        // 2) FR_NATIVE — content originellement français (audio FR par construction)
        val origLang = data.optString("language").lowercase()
        val country  = data.optString("countryName").lowercase()
        if (origLang == "fr" || origLang == "french" || country == "france") {
            frSignalCache[subjectId] = FrSignal.FR_NATIVE
            return FrSignal.FR_NATIVE
        }
        // 3) FR_SUB — piste sous-titres FR explicite dans dubs[] (VOSTFR garanti)
        if (dubs != null) {
            for (i in 0 until dubs.length()) {
                val d = dubs.optJSONObject(i) ?: continue
                if (d.optString("lanCode").equals("fr", ignoreCase = true) &&
                    d.optInt("type", -1) == 1) {
                    frSignalCache[subjectId] = FrSignal.FR_SUB
                    return FrSignal.FR_SUB
                }
            }
        }
        // PAS de fallback sur le champ `subtitles` : trop de faux positifs (le user a
        // confirmé que des films Malayalam avec subtitles="Français" listé n'avaient
        // AUCUNE piste FR jouable). On n'accepte que des signaux explicites.
        frSignalCache[subjectId] = FrSignal.NONE
        return FrSignal.NONE
    }

    /** Compat : true si du FR est dispo (audio dub OU natif OU sous-titres). */
    private suspend fun hasFrenchAudioOrSub(subjectId: String): Boolean {
        frAvailabilityCache[subjectId]?.let { return it }
        val ok = frSignalOf(subjectId) != FrSignal.NONE
        frAvailabilityCache[subjectId] = ok
        return ok
    }

    /** Filtre une liste de subjectIds en parallèle : retourne ceux ayant audio/sub FR. */
    private suspend fun filterFrenchAvailable(ids: List<String>): Set<String> = coroutineScope {
        ids.map { sid -> async { sid to hasFrenchAudioOrSub(sid) } }
            .awaitAll()
            .filter { it.second }
            .map { it.first }
            .toSet()
    }

    /** Filtre + classifie en parallèle (concurrence limitée à 15) : retourne sid → FrSignal. */
    private suspend fun classifyFrench(ids: List<String>): Map<String, FrSignal> = coroutineScope {
        val sem = kotlinx.coroutines.sync.Semaphore(15)
        var ok = 0; var none = 0; var err = 0
        val res = ids.map { sid ->
            async {
                sem.acquire()
                try {
                    val s = runCatching { frSignalOf(sid) }.getOrElse { err++; FrSignal.NONE }
                    if (s == FrSignal.NONE) none++ else ok++
                    sid to s
                } finally { sem.release() }
            }
        }.awaitAll()
        Log.d(TAG, "classifyFrench : ${ids.size} ids → ok=$ok, none=$none, err=$err")
        res.filter { it.second != FrSignal.NONE }.toMap()
    }

    // ── Provider impl : home/catalog 100% TMDB (à la Movix) ─────────────

    private fun TMDb3.MultiItem.toAppItem(): AppAdapter.Item? = when (this) {
        is TMDb3.Movie -> Movie(
            id = id.toString(),
            title = title,
            overview = overview,
            released = releaseDate,
            rating = voteAverage.toDouble(),
            poster = posterPath?.w500,
            banner = backdropPath?.w1280,
        )
        is TMDb3.Tv -> TvShow(
            id = id.toString(),
            title = name,
            overview = overview,
            released = firstAirDate,
            rating = voteAverage.toDouble(),
            poster = posterPath?.w500,
            banner = backdropPath?.w1280,
        )
        else -> null
    }

    /** Construit une catégorie TMDB Discover Movie filtrée par plateforme de streaming
     *  (région FR). Retourne une liste vide si l'appel échoue. */
    private suspend fun discoverMoviesOnProvider(
        watchProviderId: Int, mapMovie: (TMDb3.Movie) -> Movie,
    ): List<Movie> = runCatching {
        TMDb3.Discover.movie(
            page = 1, language = language, region = "FR",
            watchRegion = "FR",
            sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
            withWatchProviders = TMDb3.Params.WithBuilder(watchProviderId),
        ).results.map(mapMovie)
    }.getOrDefault(emptyList())

    private suspend fun discoverTvOnProvider(
        watchProviderId: Int, mapTv: (TMDb3.Tv) -> TvShow,
    ): List<TvShow> = runCatching {
        TMDb3.Discover.tv(
            page = 1, language = language,
            watchRegion = "FR",
            sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
            withWatchProviders = TMDb3.Params.WithBuilder(watchProviderId),
        ).results.map(mapTv)
    }.getOrDefault(emptyList())

    /** Home Cloudstream :
     *   1. Featured (carousel) — TMDB Trending Day FR
     *   2. Nouveau sur Cloudstream — MovieBox+ home avec filtre FR par
     *      language=fr / countryName=France / tag titre [VF]/[VOSTFR]
     *   3. Sections par plateforme (Netflix / Amazon / Disney+ / Canal+ / OCS /
     *      Paramount+ / Apple TV+) via TMDB watch_providers (région FR)
     *   4. Tendances classiques (séries pop / top / films pop / top)
     *
     *  IDs : les items des sections TMDB ont un ID numérique. À la lecture,
     *  getServers() recherche le titre+année dans MovieBox+ via findSubjectId.
     *  Les items "Nouveau sur Cloudstream" portent un ID `cs::m::<sid>` ou
     *  `cs::s::<sid>` (déjà des subjectIds MovieBox+). */
    override suspend fun getHome(): List<Category> = coroutineScope {
        val mapMovie: (TMDb3.Movie) -> Movie = { m ->
            Movie(
                id = m.id.toString(),
                title = m.title,
                overview = m.overview,
                released = m.releaseDate,
                rating = m.voteAverage.toDouble(),
                poster = m.posterPath?.w500,
                banner = m.backdropPath?.w1280,
            )
        }
        val mapTv: (TMDb3.Tv) -> TvShow = { t ->
            TvShow(
                id = t.id.toString(),
                title = t.name,
                overview = t.overview,
                released = t.firstAirDate,
                rating = t.voteAverage.toDouble(),
                poster = t.posterPath?.w500,
                banner = t.backdropPath?.w1280,
            )
        }

        val trendingD = async {
            runCatching { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 1, language = language).results }
                .getOrDefault(emptyList())
        }
        val popularMoviesD = async {
            runCatching { TMDb3.MovieLists.popular(page = 1, language = language).results }
                .getOrDefault(emptyList())
        }
        val topMoviesD = async {
            runCatching { TMDb3.MovieLists.topRated(page = 1, language = language).results }
                .getOrDefault(emptyList())
        }
        val popularTvD = async {
            runCatching { TMDb3.TvSeriesLists.popular(page = 1, language = language).results }
                .getOrDefault(emptyList())
        }
        val topTvD = async {
            runCatching { TMDb3.TvSeriesLists.topRated(page = 1, language = language).results }
                .getOrDefault(emptyList())
        }
        // Nouveaux films + Nouvelles séries — TMDB Discover, sortie récente, marché FR.
        // voteCount>=10 pour exclure les sorties confidentielles. À la lecture,
        // getServers() résout vers MovieBox+ par titre+année.
        val newMoviesD = async {
            runCatching {
                // Page 1+2 pour avoir plus d'items après mapping
                val p1 = async {
                    TMDb3.Discover.movie(
                        page = 1, language = language, region = "FR",
                        sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                        voteCount = TMDb3.Params.Range(10, null),
                    ).results
                }
                val p2 = async {
                    TMDb3.Discover.movie(
                        page = 2, language = language, region = "FR",
                        sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                        voteCount = TMDb3.Params.Range(10, null),
                    ).results
                }
                (p1.await() + p2.await()).distinctBy { it.id }
            }.getOrDefault(emptyList())
        }
        val newTvD = async {
            runCatching {
                val p1 = async {
                    TMDb3.Discover.tv(
                        page = 1, language = language,
                        sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,
                        voteCount = TMDb3.Params.Range(10, null),
                        watchRegion = "FR",
                    ).results
                }
                val p2 = async {
                    TMDb3.Discover.tv(
                        page = 2, language = language,
                        sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,
                        voteCount = TMDb3.Params.Range(10, null),
                        watchRegion = "FR",
                    ).results
                }
                (p1.await() + p2.await()).distinctBy { it.id }
            }.getOrDefault(emptyList())
        }

        // [LEGACY supprimé] Anciennes approches "Nouveau sur Cloudstream" via
        // TMDB→MovieBox+ search et via tab-operating MovieBox+. Remplacées par TMDB
        // Discover region=FR (cf. newMoviesD/newTvD ci-dessus).
        if (false) async {
            runCatching {
                val tmdbMovies = async {
                    TMDb3.Discover.movie(
                        page = 1, language = language, region = "FR",
                        sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                        voteCount = TMDb3.Params.Range(10, null),
                    ).results.take(20)
                }
                val tmdbTv = async {
                    TMDb3.Discover.tv(
                        page = 1, language = language,
                        sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,
                        voteCount = TMDb3.Params.Range(10, null),
                        watchRegion = "FR",
                    ).results.take(20)
                }
                val tmdbList = (tmdbMovies.await().map { it.title to it.releaseDate?.take(4)?.toIntOrNull() } +
                                tmdbTv.await().map { it.name to it.firstAirDate?.take(4)?.toIntOrNull() })

                // 2) Pour chaque, on tente de trouver un subjectId sur MovieBox+ via search
                val sem = kotlinx.coroutines.sync.Semaphore(10)
                val resolved = tmdbList.map { (title, year) ->
                    async {
                        sem.acquire()
                        try {
                            val sid = runCatching { findSubjectId(title, year) }.getOrNull()
                            if (sid.isNullOrBlank()) null else Triple(sid, title, year)
                        } finally { sem.release() }
                    }
                }.awaitAll().filterNotNull()

                Log.d(TAG, "Nouveau sur Cloudstream (TMDB→MovieBox+) : ${resolved.size}/${tmdbList.size} items confirmés sur MovieBox+")

                // 3) On récupère un peu de metadata via /get pour avoir poster/desc/type
                val items = mutableListOf<AppAdapter.Item>()
                for ((sid, title, year) in resolved) {
                    val resp = runCatching { apiGet(SUBJECT_GET_PATH, mapOf("subjectId" to sid)) }.getOrNull()
                    val data = resp?.optJSONObject("data") ?: continue
                    val cover = data.optJSONObject("cover")?.optString("url")
                    val descr = data.optString("description").takeIf { it.isNotBlank() }
                    val rating = data.optString("imdbRatingValue").toDoubleOrNull()
                    val release = data.optString("releaseDate").takeIf { it.isNotBlank() }
                    val sType = data.optInt("subjectType", 1)
                    items += when (sType) {
                        1 -> Movie(
                            id = "cs::m::$sid", title = title,
                            overview = descr, released = release,
                            poster = cover, banner = cover,
                            rating = rating, providerName = name,
                        )
                        2 -> TvShow(
                            id = "cs::s::$sid", title = title,
                            overview = descr, released = release,
                            poster = cover, banner = cover,
                            rating = rating, providerName = name,
                        )
                        else -> continue
                    }
                }
                items
            }.getOrDefault(emptyList())
        }

        // Aspire les tabs MovieBox+ et garde uniquement les items avec signal FR strict
        // (audio FR / natif français / piste sub FR explicite). Pas de fallback subtitles.
        val newReleasesD = async {
            runCatching {
                val tabRequests = mutableListOf<Deferred<JSONObject?>>()
                for (tabId in 0..5) {
                    for (page in 1..2) {
                        tabRequests += async {
                            apiGet(MAIN_PAGE_PATH, mapOf("page" to "$page", "tabId" to "$tabId", "version" to ""))
                        }
                    }
                }
                val tabs = tabRequests.awaitAll()
                // sid → (item, releaseDateForSort, originalRanking)
                val items = mutableMapOf<String, Pair<AppAdapter.Item, String>>()
                var rank = 0
                var seen = 0; var rejShort = 0; var rejNoSid = 0; var rejDup = 0
                fun consumeSubject(raw: JSONObject) {
                    seen++
                    val s = raw.optJSONObject("subject") ?: raw
                    if (isShortDrama(s)) { rejShort++; return }
                    // La home MovieBox+ ne fournit PAS language/countryName. Le filtre FR
                    // sera fait après ingestion via /subject-api/get (cf. classifyFrench).
                    // On garde tout sauf shorts/dupes/items sans sid.
                    val title = s.optString("title")
                    val sid = s.optString("subjectId").takeIf { it.isNotBlank() }
                    if (sid == null) { rejNoSid++; return }
                    if (sid in items) { rejDup++; return }
                    val release = s.optString("releaseDate").orEmpty()
                    val cleanTitle = title.replace(LANG_SUFFIX_REGEX, "").trim()
                    val poster = s.optJSONObject("cover")?.optString("url")
                    val rating = s.optString("imdbRatingValue").toDoubleOrNull()
                    val sType = s.optInt("subjectType", 1)
                    val parsed: AppAdapter.Item = when (sType) {
                        1 -> Movie(
                            id = "cs::m::$sid", title = cleanTitle,
                            overview = s.optString("description").takeIf { it.isNotBlank() },
                            released = release.takeIf { it.isNotBlank() },
                            poster = poster, banner = poster,
                            rating = rating, providerName = name,
                        )
                        2 -> TvShow(
                            id = "cs::s::$sid", title = cleanTitle,
                            overview = s.optString("description").takeIf { it.isNotBlank() },
                            released = release.takeIf { it.isNotBlank() },
                            poster = poster, banner = poster,
                            rating = rating, providerName = name,
                        )
                        else -> return
                    }
                    // Pour le tri : releaseDate si présent, sinon clé d'ordre d'arrivée pour
                    // garder un fallback déterministe ("Z9999..." est < "AAAA" donc passe en tail)
                    val sortKey = if (release.isNotBlank()) release else "0000-00-00#%05d".format(rank)
                    rank++
                    items[sid] = parsed to sortKey
                }
                Log.d(TAG, "tab-operating : ${tabs.size} réponses, ${tabs.count { it != null }} non-null")
                var totalBanner = 0; var totalGroup = 0; var totalFilter = 0; var totalOther = 0
                var subjectsFromBanners = 0; var subjectsFromGroups = 0
                for ((idx, resp) in tabs.withIndex()) {
                    val arr = resp?.optJSONObject("data")?.optJSONArray("items")
                        ?: resp?.optJSONObject("data")?.optJSONArray("topics")
                    if (arr == null) {
                        Log.d(TAG, "  tab[$idx] : data.items NULL")
                        continue
                    }
                    for (i in 0 until arr.length()) {
                        val it = arr.optJSONObject(i) ?: continue
                        val type = it.optString("type")
                        when (type) {
                            "FILTER" -> { totalFilter++; continue }
                            "BANNER" -> {
                                totalBanner++
                                val banners = it.optJSONObject("banner")?.optJSONArray("banners")
                                if (banners != null) {
                                    for (j in 0 until banners.length()) {
                                        val b = banners.optJSONObject(j) ?: continue
                                        subjectsFromBanners++
                                        consumeSubject(b)
                                    }
                                }
                                continue
                            }
                            else -> {}
                        }
                        val subjs = it.optJSONArray("subjects")
                            ?: it.optJSONObject("group")?.optJSONArray("subjects")
                        if (subjs != null) {
                            totalGroup++
                            for (j in 0 until subjs.length()) {
                                val raw = subjs.optJSONObject(j) ?: continue
                                subjectsFromGroups++
                                consumeSubject(raw)
                            }
                        } else {
                            totalOther++
                            // Inspect first one to see what type/keys this is
                            if (idx == 0 && totalOther <= 3) {
                                Log.d(TAG, "  tab[0] item type='$type' keys=${it.keys().asSequence().toList()}")
                            }
                        }
                    }
                }
                Log.d(TAG, "ingestion : BANNER=$totalBanner (→$subjectsFromBanners subjects), GROUP=$totalGroup (→$subjectsFromGroups subjects), FILTER=$totalFilter, OTHER=$totalOther")
                Log.d(TAG, "filtres : seen=$seen, rejShort=$rejShort, rejNoSid=$rejNoSid, rejDup=$rejDup, kept=${items.size}")
                // Filtre FR via /subject-api/get : on classifie TOUT le pool unique
                // (semaphore=15 ≈ 6-10s). Garde tout item ayant un signal FR : audio FR
                // (dub) / originellement français / piste sub FR explicite (dubs type=1)
                // / champ subtitles listant Français/French (VOSTFR jouable).
                val sortedByDate = items.entries.sortedByDescending { it.value.second }
                val signals = classifyFrench(sortedByDate.map { it.key })
                val frItems = sortedByDate.filter { signals.containsKey(it.key) }.map { it.value.first }
                Log.d(TAG, "Nouveau sur Cloudstream : pool=${items.size} → testés=${sortedByDate.size} → FR=${frItems.size}")
                frItems.take(30)
            }.getOrElse { emptyList<AppAdapter.Item>() }
        }

        // Catégories par plateforme (Discover Movie + Tv, sortBy popularity desc, FR)
        val netflixMoviesD     = async { discoverMoviesOnProvider(8, mapMovie) }
        val netflixTvD         = async { discoverTvOnProvider(8, mapTv) }
        val amazonMoviesD      = async { discoverMoviesOnProvider(9, mapMovie) }
        val amazonTvD          = async { discoverTvOnProvider(119, mapTv) }
        val disneyMoviesD      = async { discoverMoviesOnProvider(337, mapMovie) }
        val disneyTvD          = async { discoverTvOnProvider(337, mapTv) }
        val appleTvMoviesD     = async { discoverMoviesOnProvider(350, mapMovie) }
        val appleTvTvD         = async { discoverTvOnProvider(350, mapTv) }
        val paramountMoviesD   = async { discoverMoviesOnProvider(531, mapMovie) }
        val paramountTvD       = async { discoverTvOnProvider(531, mapTv) }
        val canalMoviesD       = async { discoverMoviesOnProvider(381, mapMovie) }   // Canal+ FR
        val ocsMoviesD         = async { discoverMoviesOnProvider(56, mapMovie) }    // OCS FR
        val maxMoviesD         = async { discoverMoviesOnProvider(1899, mapMovie) }  // Max FR

        val sections = mutableListOf<Category>()
        val featured = trendingD.await().take(10).mapNotNull { it.toAppItem() }
        if (featured.isNotEmpty()) sections.add(Category(name = Category.FEATURED, list = featured))

        // 1) Nouveau sur Cloudstream — toutes les nouveautés FR + VOSTFR sorties sur le
        //    marché français, triées par date. TMDB Discover region=FR retourne le
        //    contenu localisé pour la France (films français natifs + films étrangers
        //    sortis en France = VOSTFR/VF disponibles). Jaquettes garanties (TMDB).
        val newMovies = newMoviesD.await().map(mapMovie)
        val newTv = newTvD.await().map(mapTv)
        val nouveauTout = mutableListOf<AppAdapter.Item>()
        nouveauTout += newMovies
        nouveauTout += newTv
        val nouveauSorted = nouveauTout
            .distinctBy { (it as? Movie)?.id ?: (it as? TvShow)?.id ?: "" }
            .sortedByDescending { item ->
                val r = (item as? Movie)?.released ?: (item as? TvShow)?.released
                r?.toString() ?: ""
            }
            .take(30)
        if (nouveauSorted.isNotEmpty()) {
            sections.add(Category(name = "Nouveau sur Cloudstream", list = nouveauSorted))
        }

        // 2) Films + Séries séparés
        if (newMovies.isNotEmpty()) sections.add(Category(name = "Nouveaux films", list = newMovies))
        if (newTv.isNotEmpty()) sections.add(Category(name = "Nouvelles séries", list = newTv))

        // 3) Catégories par plateforme — un row par plateforme, films + séries combinés
        fun addPlatformRow(label: String, movies: List<Movie>, tv: List<TvShow>) {
            val combined = mutableListOf<AppAdapter.Item>()
            combined += movies.take(15)
            combined += tv.take(15)
            if (combined.isNotEmpty()) sections.add(Category(name = label, list = combined))
        }
        addPlatformRow("Sur Netflix",        netflixMoviesD.await(),   netflixTvD.await())
        addPlatformRow("Sur Amazon Prime",   amazonMoviesD.await(),    amazonTvD.await())
        addPlatformRow("Sur Disney+",        disneyMoviesD.await(),    disneyTvD.await())
        addPlatformRow("Sur Canal+",         canalMoviesD.await(),     emptyList())
        addPlatformRow("Sur Paramount+",     paramountMoviesD.await(), paramountTvD.await())
        addPlatformRow("Sur Apple TV+",      appleTvMoviesD.await(),   appleTvTvD.await())
        addPlatformRow("Sur OCS",            ocsMoviesD.await(),       emptyList())
        addPlatformRow("Sur Max",            maxMoviesD.await(),       emptyList())

        // 3) Catégories de fond (toutes plateformes)
        val popularTv = popularTvD.await().map(mapTv)
        if (popularTv.isNotEmpty()) sections.add(Category(name = "Séries populaires", list = popularTv))

        val topTv = topTvD.await().map(mapTv)
        if (topTv.isNotEmpty()) sections.add(Category(name = "Séries les mieux notées", list = topTv))

        val popularMovies = popularMoviesD.await().map(mapMovie)
        if (popularMovies.isNotEmpty()) sections.add(Category(name = "Films populaires", list = popularMovies))

        val topMovies = topMoviesD.await().map(mapMovie)
        if (topMovies.isNotEmpty()) sections.add(Category(name = "Films les mieux notés", list = topMovies))

        Log.d(TAG, "getHome Cloudstream : ${sections.size} sections — featured=${featured.size}, nouveau=${nouveauSorted.size}, nouveauxFilms=${newMovies.size}, nouvellesSeries=${newTv.size}")
        sections
    }

    /** Ancien getHome basé MovieBox+ — désactivé. Garde le code en référence. */
    @Suppress("unused")
    private suspend fun getHomeFromMoviebox(): List<Category> = coroutineScope {
        val tabResponses = (0..5).map { tabId ->
            async { apiGet(MAIN_PAGE_PATH, mapOf("page" to "1", "tabId" to "$tabId", "version" to "")) }
        }.awaitAll()

        val featured = mutableListOf<AppAdapter.Item>()
        val allMovies = mutableMapOf<String, Pair<Movie, Double>>()  // sid → (Movie, rating)
        val allTvShows = mutableMapOf<String, Pair<TvShow, Double>>()
        var featuredFromBanner = false

        for (resp in tabResponses) {
            if (resp == null) continue
            val data = resp.optJSONObject("data") ?: continue
            val items = data.optJSONArray("items") ?: data.optJSONArray("topics") ?: continue

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val type = item.optString("type")

                if (type == "BANNER" && !featuredFromBanner) {
                    val banners = item.optJSONObject("banner")?.optJSONArray("banners") ?: continue
                    for (j in 0 until banners.length()) {
                        val b = banners.optJSONObject(j) ?: continue
                        val subject = b.optJSONObject("subject") ?: continue
                        if (isShortDrama(subject)) continue
                        val rawTitle = subject.optString("title")
                        val sid = subject.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
                        val title = rawTitle.replace(LANG_SUFFIX_REGEX, "").trim()
                        val landscape = b.optJSONObject("image")?.optString("url")
                        val rating = subject.optString("imdbRatingValue").toDoubleOrNull()
                        val parsed: AppAdapter.Item = when (subject.optInt("subjectType", 1)) {
                            1 -> Movie(
                                id = "cs::m::$sid", title = title,
                                overview = subject.optString("description").takeIf { it.isNotBlank() },
                                released = subject.optString("releaseDate").takeIf { it.isNotBlank() },
                                poster = subject.optJSONObject("cover")?.optString("url"),
                                banner = landscape ?: subject.optJSONObject("cover")?.optString("url"),
                                rating = rating, providerName = name,
                            )
                            else -> TvShow(
                                id = "cs::s::$sid", title = title,
                                overview = subject.optString("description").takeIf { it.isNotBlank() },
                                released = subject.optString("releaseDate").takeIf { it.isNotBlank() },
                                poster = subject.optJSONObject("cover")?.optString("url"),
                                banner = landscape ?: subject.optJSONObject("cover")?.optString("url"),
                                rating = rating, providerName = name,
                            )
                        }
                        featured.add(parsed)
                    }
                    if (featured.isNotEmpty()) featuredFromBanner = true
                } else if (type != "FILTER") {
                    // Aspirer tous les subjects des autres sections (Trending, Popular, Top, Cinema, etc.)
                    val subjects = item.optJSONArray("subjects")
                        ?: item.optJSONObject("group")?.optJSONArray("subjects")
                        ?: continue
                    for (j in 0 until subjects.length()) {
                        val s = subjects.optJSONObject(j) ?: continue
                        val subj = s.optJSONObject("subject") ?: s
                        if (isShortDrama(subj)) continue
                        val rawTitle = subj.optString("title")
                        if (LANG_NON_FR_REGEX.containsMatchIn(rawTitle)) continue
                        val sid = subj.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
                        val title = rawTitle.replace(LANG_SUFFIX_REGEX, "").trim()
                        val rating = subj.optString("imdbRatingValue").toDoubleOrNull() ?: 0.0
                        val poster = subj.optJSONObject("cover")?.optString("url")
                        val release = subj.optString("releaseDate").takeIf { it.isNotBlank() }
                        val descr = subj.optString("description").takeIf { it.isNotBlank() }
                        when (subj.optInt("subjectType", 1)) {
                            1 -> if (sid !in allMovies) {
                                allMovies[sid] = Movie(
                                    id = "cs::m::$sid", title = title,
                                    overview = descr, released = release,
                                    poster = poster, banner = poster,
                                    rating = if (rating > 0) rating else null,
                                    providerName = name,
                                ) to rating
                            }
                            2 -> if (sid !in allTvShows) {
                                allTvShows[sid] = TvShow(
                                    id = "cs::s::$sid", title = title,
                                    overview = descr, released = release,
                                    poster = poster, banner = poster,
                                    rating = if (rating > 0) rating else null,
                                    providerName = name,
                                ) to rating
                            }
                        }
                    }
                }
            }
        }

        // Filtre strict FR : audio FR ou VOSTFR confirmé via /subject-api/get
        val candidateIds = (allMovies.keys + allTvShows.keys + featured.mapNotNull {
            (it as? Movie)?.id?.removePrefix("cs::m::") ?: (it as? TvShow)?.id?.removePrefix("cs::s::")
        }).toSet()
        val frAllowed = filterFrenchAvailable(candidateIds.toList())
        Log.d(TAG, "getHome FR-filter : ${frAllowed.size}/${candidateIds.size} ont audio FR ou VOSTFR")

        // Featured filtré FR
        val frFeatured = featured.filter {
            val sid = (it as? Movie)?.id?.removePrefix("cs::m::")
                ?: (it as? TvShow)?.id?.removePrefix("cs::s::")
            sid != null && sid in frAllowed
        }
        val frMoviesPairs = allMovies.entries.filter { it.key in frAllowed }
        val frTvShowsPairs = allTvShows.entries.filter { it.key in frAllowed }

        // Helpers pour extraire genre + année d'un Movie/TvShow (genres sont stockés
        // dans les items issus du JSON MovieBox+ via le champ s.genre, mais on les
        // a perdus ; on relit depuis allMovies/allTvShows valeurs).
        fun yearOf(item: Any): Int = when (item) {
            is Movie -> item.released?.get(java.util.Calendar.YEAR) ?: 0
            is TvShow -> item.released?.get(java.util.Calendar.YEAR) ?: 0
            else -> 0
        }
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val recentYear = currentYear - 2

        val frMovies = frMoviesPairs.map { it.value.first }
        val frMoviesWithRating = frMoviesPairs.filter { it.value.second > 0 }
        val frTv = frTvShowsPairs.map { it.value.first }
        val frTvWithRating = frTvShowsPairs.filter { it.value.second > 0 }

        // Bucketing EXCLUSIF : chaque item ne va que dans UNE section.
        // Priorité : Récent (>=2 ans) > Top noté (>=7.5) > Populaire (reste).
        val featuredSids = frFeatured.mapNotNull {
            (it as? Movie)?.id?.removePrefix("cs::m::") ?: (it as? TvShow)?.id?.removePrefix("cs::s::")
        }.toSet()

        val recentMovies = mutableListOf<Movie>()
        val topMovies = mutableListOf<Movie>()
        val popularMovies = mutableListOf<Movie>()
        for ((sid, pair) in frMoviesPairs) {
            if (sid in featuredSids) continue  // déjà en featured
            val (m, rating) = pair
            when {
                yearOf(m) >= recentYear -> recentMovies.add(m)
                rating >= 7.5 -> topMovies.add(m)
                else -> popularMovies.add(m)
            }
        }

        val recentTv = mutableListOf<TvShow>()
        val topTv = mutableListOf<TvShow>()
        val popularTv = mutableListOf<TvShow>()
        for ((sid, pair) in frTvShowsPairs) {
            if (sid in featuredSids) continue
            val (t, rating) = pair
            when {
                yearOf(t) >= recentYear -> recentTv.add(t)
                rating >= 7.5 -> topTv.add(t)
                else -> popularTv.add(t)
            }
        }
        // Tri du top par rating desc
        topMovies.sortByDescending { frMoviesPairs.first { e -> e.value.first == it }.value.second }
        topTv.sortByDescending { frTvShowsPairs.first { e -> e.value.first == it }.value.second }

        val sections = mutableListOf<Category>()
        if (frFeatured.isNotEmpty()) {
            sections.add(Category(name = Category.FEATURED, list = frFeatured.take(10)))
        }
        if (recentMovies.isNotEmpty()) {
            sections.add(Category(name = "Films récents", list = recentMovies))
        }
        if (topMovies.isNotEmpty()) {
            sections.add(Category(name = "Films les mieux notés", list = topMovies))
        }
        if (popularMovies.isNotEmpty()) {
            sections.add(Category(name = "Films populaires", list = popularMovies))
        }
        if (recentTv.isNotEmpty()) {
            sections.add(Category(name = "Séries récentes", list = recentTv))
        }
        if (topTv.isNotEmpty()) {
            sections.add(Category(name = "Séries les mieux notées", list = topTv))
        }
        if (popularTv.isNotEmpty()) {
            sections.add(Category(name = "Séries populaires", list = popularTv))
        }

        Log.d(TAG, "getHome (Movix-style FR exclusif) : ${sections.size} sections — featured=${frFeatured.size}, films récents=${recentMovies.size} top=${topMovies.size} pop=${popularMovies.size}, séries récentes=${recentTv.size} top=${topTv.size} pop=${popularTv.size}")
        sections
    }

    /** Films onglet : style Movix = TMDB MovieLists.popular. IDs TMDB. */
    override suspend fun getMovies(page: Int): List<Movie> {
        return runCatching {
            TMDb3.MovieLists.popular(page = page, language = language).results.map { m ->
                Movie(
                    id = m.id.toString(),
                    title = m.title,
                    overview = m.overview,
                    released = m.releaseDate,
                    rating = m.voteAverage.toDouble(),
                    poster = m.posterPath?.w500,
                    banner = m.backdropPath?.w1280,
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return runCatching {
            TMDb3.TvSeriesLists.popular(page = page, language = language).results.map { t ->
                TvShow(
                    id = t.id.toString(),
                    title = t.name,
                    overview = t.overview,
                    released = t.firstAirDate,
                    rating = t.voteAverage.toDouble(),
                    poster = t.posterPath?.w500,
                    banner = t.backdropPath?.w1280,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Ancien getMovies depuis MovieBox+ — désactivé. */
    @Suppress("unused")
    private suspend fun getMoviesFromMoviebox(page: Int): List<Movie> = coroutineScope {
        if (page > 8) return@coroutineScope emptyList()
        val responses = (0..5).map { tabId ->
            async { apiGet(MAIN_PAGE_PATH, mapOf("page" to "$page", "tabId" to "$tabId", "version" to "")) }
        }.awaitAll()
        val movies = mutableMapOf<String, Movie>()  // sid → Movie pour dédoublonnage
        for (resp in responses) {
            val items = resp?.optJSONObject("data")?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val subjects = it.optJSONArray("subjects")
                    ?: it.optJSONObject("group")?.optJSONArray("subjects")
                    ?: continue
                for (j in 0 until subjects.length()) {
                    val raw = subjects.optJSONObject(j) ?: continue
                    val s = raw.optJSONObject("subject") ?: raw
                    if (s.optInt("subjectType", 1) != 1) continue
                    if (isShortDrama(s)) continue
                    val sid = s.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
                    if (sid in movies) continue
                    val cleanTitle = s.optString("title").replace(LANG_SUFFIX_REGEX, "").trim()
                    movies[sid] = Movie(
                        id = "cs::m::$sid", title = cleanTitle,
                        overview = s.optString("description").takeIf { it.isNotBlank() },
                        released = s.optString("releaseDate").takeIf { it.isNotBlank() },
                        poster = s.optJSONObject("cover")?.optString("url"),
                        banner = s.optJSONObject("cover")?.optString("url"),
                        rating = s.optString("imdbRatingValue").toDoubleOrNull(),
                        providerName = name,
                    )
                }
            }
        }
        // Filtre FR audio/VOSTFR via /subject-api/get
        val frAllowed = filterFrenchAvailable(movies.keys.toList())
        Log.d(TAG, "getMovies FR-filter p=$page : ${frAllowed.size}/${movies.size}")
        movies.entries.filter { it.key in frAllowed }.map { it.value }
    }

    /** Ancien getTvShows depuis MovieBox+ — désactivé. */
    @Suppress("unused")
    private suspend fun getTvShowsFromMoviebox(page: Int): List<TvShow> = coroutineScope {
        if (page > 8) return@coroutineScope emptyList()
        val responses = (0..5).map { tabId ->
            async { apiGet(MAIN_PAGE_PATH, mapOf("page" to "$page", "tabId" to "$tabId", "version" to "")) }
        }.awaitAll()
        val shows = mutableMapOf<String, TvShow>()
        for (resp in responses) {
            val items = resp?.optJSONObject("data")?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val subjects = it.optJSONArray("subjects")
                    ?: it.optJSONObject("group")?.optJSONArray("subjects")
                    ?: continue
                for (j in 0 until subjects.length()) {
                    val raw = subjects.optJSONObject(j) ?: continue
                    val s = raw.optJSONObject("subject") ?: raw
                    if (s.optInt("subjectType", 1) != 2) continue
                    if (isShortDrama(s)) continue
                    val sid = s.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
                    if (sid in shows) continue
                    val cleanTitle = s.optString("title").replace(LANG_SUFFIX_REGEX, "").trim()
                    shows[sid] = TvShow(
                        id = "cs::s::$sid", title = cleanTitle,
                        overview = s.optString("description").takeIf { it.isNotBlank() },
                        released = s.optString("releaseDate").takeIf { it.isNotBlank() },
                        poster = s.optJSONObject("cover")?.optString("url"),
                        banner = s.optJSONObject("cover")?.optString("url"),
                        rating = s.optString("imdbRatingValue").toDoubleOrNull(),
                        providerName = name,
                    )
                }
            }
        }
        val frAllowed = filterFrenchAvailable(shows.keys.toList())
        Log.d(TAG, "getTvShows FR-filter p=$page : ${frAllowed.size}/${shows.size}")
        shows.entries.filter { it.key in frAllowed }.map { it.value }
    }

    /** Recherche : copie du style Movix.
     *  - query vide → liste de Genres (tiles Action / Comédie / Drame / etc.)
     *  - query non vide → TMDB Search.multi (Movies + TV + People mélangés)
     *  IDs TMDB. La playback bascule sur MovieBox+ via findSubjectId. */
    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            // Genres TMDB (mêmes IDs que Movix pour cohérence)
            return listOf(
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
                Genre(id = "10402", name = "Musique"),
                Genre(id = "9648", name = "Mystère"),
                Genre(id = "10749", name = "Romance"),
                Genre(id = "878", name = "Science-Fiction"),
                Genre(id = "10770", name = "Téléfilm"),
                Genre(id = "53", name = "Thriller"),
                Genre(id = "10752", name = "Guerre"),
                Genre(id = "37", name = "Western"),
            )
        }
        return runCatching {
            val cleanQuery = TitleNormalizer.cleanForTmdbSearch(query).ifBlank { query }
            TMDb3.Search.multi(cleanQuery, language = language, page = page).results.mapNotNull { item ->
                when (item) {
                    is TMDb3.Movie -> Movie(
                        id = item.id.toString(),
                        title = item.title,
                        overview = item.overview,
                        released = item.releaseDate,
                        rating = item.voteAverage.toDouble(),
                        poster = item.posterPath?.w500,
                        banner = item.backdropPath?.w1280,
                    )
                    is TMDb3.Tv -> TvShow(
                        id = item.id.toString(),
                        title = item.name,
                        overview = item.overview,
                        released = item.firstAirDate,
                        rating = item.voteAverage.toDouble(),
                        poster = item.posterPath?.w500,
                        banner = item.backdropPath?.w1280,
                    )
                    // Note : on ne retourne PAS de People() — la propriété
                    // `itemType` est lateinit et est initialisée par les ViewHolders
                    // selon le mode Mobile/TV. La créer ici plante avec
                    // UninitializedPropertyAccessException dans DiffUtil.
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun getMovie(id: String): Movie {
        // Si c'est un ID Cloudstream (cs::m::<subjectId>), lookup direct via /subject-api/get
        if (id.startsWith("cs::m::")) {
            return getCloudstreamMovie(id)
        }
        val tmdbId = id.toIntOrNull() ?: return Movie(id = id, title = "", providerName = name)
        return runCatching {
            val m = TMDb3.Movies.details(
                movieId = tmdbId,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Movie.CREDITS,
                    TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                    TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
                ),
                language = language,
            )
            Movie(
                id = m.id.toString(),
                title = m.title,
                overview = m.overview,
                released = m.releaseDate,
                runtime = m.runtime,
                trailer = m.videos?.results?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = m.voteAverage.toDouble(),
                poster = m.posterPath?.w780,
                banner = m.backdropPath?.w1280,
                imdbId = m.externalIds?.imdbId,
                genres = m.genres.map { Genre(it.id.toString(), it.name) },
                cast = m.credits?.cast?.map { c ->
                    People(id = c.id.toString(), name = c.name, image = c.profilePath?.w500)
                } ?: emptyList(),
                recommendations = m.recommendations?.results?.mapNotNull {
                    it.toAppItem() as? com.streamflixreborn.streamflix.models.Show
                } ?: emptyList(),
                providerName = name,
            )
        }.getOrElse { Movie(id = id, title = "", providerName = name) }
    }

    /** Lookup détail Cloudstream pour un movie via /subject-api/get. */
    private suspend fun getCloudstreamMovie(id: String): Movie {
        val sid = id.removePrefix("cs::m::")
        val resp = apiGet(SUBJECT_GET_PATH, mapOf("subjectId" to sid))
            ?: return Movie(id = id, title = "", providerName = name)
        val data = resp.optJSONObject("data") ?: return Movie(id = id, title = "", providerName = name)
        val title = data.optString("title").replace(LANG_SUFFIX_REGEX, "").trim()
        return Movie(
            id = id,
            title = title,
            overview = data.optString("description").takeIf { it.isNotBlank() },
            released = data.optString("releaseDate").takeIf { it.isNotBlank() },
            poster = data.optJSONObject("cover")?.optString("url"),
            banner = data.optJSONObject("cover")?.optString("url"),
            rating = data.optString("imdbRatingValue").toDoubleOrNull(),
            providerName = name,
        )
    }

    /** Lookup détail Cloudstream pour une série via /subject-api/get + /season-info. */
    private suspend fun getCloudstreamTvShow(id: String): TvShow = coroutineScope {
        val sid = id.removePrefix("cs::s::")
        val detailD = async { apiGet(SUBJECT_GET_PATH, mapOf("subjectId" to sid)) }
        val seasonsD = async { apiGet(SEASON_INFO_PATH, mapOf("subjectId" to sid)) }
        val resp = detailD.await() ?: return@coroutineScope TvShow(id = id, title = "", providerName = name)
        val data = resp.optJSONObject("data") ?: return@coroutineScope TvShow(id = id, title = "", providerName = name)
        val title = data.optString("title").replace(LANG_SUFFIX_REGEX, "").trim()
        // Saisons via season-info, fallback sur seNum du détail
        val seasons = mutableListOf<Season>()
        seasonsD.await()?.optJSONObject("data")?.optJSONArray("seasons")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val seNum = s.optInt("se", i + 1)
                seasons.add(Season(
                    id = "cs::season::$sid::$seNum",
                    number = seNum,
                    title = s.optString("title").ifEmpty { "Saison $seNum" },
                    poster = s.optJSONObject("cover")?.optString("url"),
                ))
            }
        }
        if (seasons.isEmpty()) {
            val seNum = data.optInt("seNum", 0)
            for (i in 1..seNum) {
                seasons.add(Season(
                    id = "cs::season::$sid::$i",
                    number = i,
                    title = "Saison $i",
                    poster = data.optJSONObject("cover")?.optString("url"),
                ))
            }
        }
        TvShow(
            id = id,
            title = title,
            overview = data.optString("description").takeIf { it.isNotBlank() },
            released = data.optString("releaseDate").takeIf { it.isNotBlank() },
            poster = data.optJSONObject("cover")?.optString("url"),
            banner = data.optJSONObject("cover")?.optString("url"),
            rating = data.optString("imdbRatingValue").toDoubleOrNull(),
            seasons = seasons,
            providerName = name,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        if (id.startsWith("cs::s::")) {
            return getCloudstreamTvShow(id)
        }
        val tmdbId = id.toIntOrNull() ?: return TvShow(id = id, title = "", providerName = name)
        return runCatching {
            val tv = TMDb3.TvSeries.details(
                seriesId = tmdbId,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Tv.CREDITS,
                    TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                    TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
                ),
                language = language,
            )
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                trailer = tv.videos?.results?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.w780,
                banner = tv.backdropPath?.w1280,
                imdbId = tv.externalIds?.imdbId,
                seasons = tv.seasons.map { s ->
                    Season(
                        id = "${tv.id}-${s.seasonNumber}",
                        number = s.seasonNumber,
                        title = s.name,
                        poster = s.posterPath?.w500,
                    )
                },
                genres = tv.genres.map { Genre(it.id.toString(), it.name) },
                cast = tv.credits?.cast?.map { c ->
                    People(id = c.id.toString(), name = c.name, image = c.profilePath?.w500)
                } ?: emptyList(),
                recommendations = tv.recommendations?.results?.mapNotNull {
                    it.toAppItem() as? com.streamflixreborn.streamflix.models.Show
                } ?: emptyList(),
                providerName = name,
            )
        }.getOrElse { TvShow(id = id, title = "", providerName = name) }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // Cloudstream backend : seasonId = "cs::season::<sid>::<seNum>"
        if (seasonId.startsWith("cs::season::")) {
            val parts = seasonId.removePrefix("cs::season::").split("::")
            if (parts.size != 2) return emptyList()
            val sid = parts[0]
            val seNum = parts[1].toIntOrNull() ?: return emptyList()
            return getCloudstreamEpisodes(sid, seNum)
        }
        // TMDB : seasonId = "<tmdbId>-<seNum>"
        val parts = seasonId.split("-")
        if (parts.size != 2) return emptyList()
        val tvId = parts[0].toIntOrNull() ?: return emptyList()
        val seNum = parts[1].toIntOrNull() ?: return emptyList()
        return runCatching {
            TMDb3.TvSeasons.details(seriesId = tvId, seasonNumber = seNum, language = language)
                .episodes?.map {
                    Episode(
                        id = "$tvId:$seNum:${it.episodeNumber}",
                        number = it.episodeNumber,
                        title = it.name ?: "",
                        released = it.airDate,
                        poster = it.stillPath?.w500,
                    )
                } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Liste des épisodes d'une série Cloudstream via /resource paginé.
     *  Le champ `episode` est encodé S*100+EP (101 = S1E1, 203 = S2E3). */
    private suspend fun getCloudstreamEpisodes(sid: String, seNum: Int): List<Episode> {
        val episodes = mutableMapOf<Int, Episode>()
        var page = 1
        while (page <= 20) {
            val r = apiGet(RESOURCE_PATH, mapOf(
                "subjectId" to sid,
                "se" to "$seNum",
                "ep" to "1",
                "page" to "$page",
            )) ?: break
            val data = r.optJSONObject("data") ?: break
            val list = data.optJSONArray("list") ?: break
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                if (it.optInt("se") != seNum) continue
                val ep = it.optInt("ep")
                if (ep <= 0 || episodes.containsKey(ep)) continue
                val title = it.optString("title").ifBlank { "Épisode $ep" }
                episodes[ep] = Episode(
                    id = "cs::ep::$sid::$seNum::$ep",
                    number = ep,
                    title = title,
                )
            }
            val pager = data.optJSONObject("pager")
            if (pager?.optBoolean("hasMore", false) != true) break
            page++
        }
        if (episodes.isEmpty()) {
            // Fallback : season-info pour récupérer maxEp
            val seasonsResp = apiGet(SEASON_INFO_PATH, mapOf("subjectId" to sid))
            val arr = seasonsResp?.optJSONObject("data")?.optJSONArray("seasons")
            var maxEp = 0
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    if (s.optInt("se", -1) == seNum) {
                        maxEp = s.optInt("maxEp", s.optInt("epNum", 0))
                        break
                    }
                }
            }
            if (maxEp <= 0) return emptyList()
            return (1..maxEp).map { ep ->
                Episode(id = "cs::ep::$sid::$seNum::$ep", number = ep, title = "Épisode $ep")
            }
        }
        return episodes.values.sortedBy { it.number }
    }

    /** Genre browsing : style Movix.
     *  - id numérique (TMDB genre id) → Discover par genre
     *  - id "k-drama" → Discover par origin_country=KR (mêmes specialGenres que Movix) */
    override suspend fun getGenre(id: String, page: Int): Genre {
        // Genres spéciaux basés sur pays d'origine
        val originCountry = when (id.lowercase()) {
            "k-drama", "drama-coreen" -> "KR"
            else -> null
        }
        return runCatching {
            val tmdbGenreId = id.toIntOrNull()
            val withOrigin: TMDb3.Params.WithBuilder<String>? = originCountry?.let {
                TMDb3.Params.WithBuilder(it)
            }
            // Films
            val movies = TMDb3.Discover.movie(
                language = language, page = page,
                withOriginCountry = withOrigin,
            ).results.let { all ->
                if (tmdbGenreId != null) all.filter { it.genresIds.contains(tmdbGenreId) }
                else all
            }.map { m ->
                Movie(
                    id = m.id.toString(), title = m.title, overview = m.overview,
                    released = m.releaseDate, rating = m.voteAverage.toDouble(),
                    poster = m.posterPath?.w500, banner = m.backdropPath?.w1280,
                )
            }
            // Séries TV
            val tvShows = TMDb3.Discover.tv(
                language = language, page = page,
                withOriginCountry = withOrigin,
            ).results.let { all ->
                if (tmdbGenreId != null) all.filter { it.genresIds.contains(tmdbGenreId) }
                else all
            }.map { t ->
                TvShow(
                    id = t.id.toString(), title = t.name, overview = t.overview,
                    released = t.firstAirDate, rating = t.voteAverage.toDouble(),
                    poster = t.posterPath?.w500, banner = t.backdropPath?.w1280,
                )
            }
            // Tri par popularité (rating desc) façon Movix
            val combined = (movies + tvShows).sortedByDescending {
                when (it) {
                    is Movie -> it.rating ?: 0.0
                    is TvShow -> it.rating ?: 0.0
                    else -> 0.0
                }
            }
            Genre(id = id, name = "", shows = combined)
        }.getOrDefault(Genre(id = id, name = "", shows = emptyList()))
    }

    /** Ancien getGenre depuis MovieBox+ search — désactivé. */
    @Suppress("unused")
    private suspend fun getGenreFromMoviebox(id: String, page: Int): Genre = coroutineScope {
        val genreName = id.removePrefix("cs::g::")
        val resp = apiPost(SEARCH_PATH, JSONObject().apply {
            put("keyword", genreName)
            put("page", page - 1)
            put("perPage", 20)  // limite serveur LIMIT_EXCEED
        }) ?: return@coroutineScope Genre(id = id, name = genreName, shows = emptyList())
        val items = resp.optJSONObject("data")?.optJSONArray("items") ?: return@coroutineScope Genre(id = id, name = genreName, shows = emptyList())
        val shows = mutableListOf<com.streamflixreborn.streamflix.models.Show>()
        for (i in 0 until items.length()) {
            val s = items.optJSONObject(i) ?: continue
            if (isShortDrama(s)) continue
            val title = s.optString("title")
            if (LANG_NON_FR_REGEX.containsMatchIn(title)) continue
            // Filtre les items dont le genre matche réellement
            val itemGenre = s.optString("genre").orEmpty()
            if (!itemGenre.contains(genreName, ignoreCase = true)) continue
            val sid = s.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
            val cleanTitle = title.replace(LANG_SUFFIX_REGEX, "").trim()
            when (s.optInt("subjectType", 1)) {
                1 -> shows.add(Movie(
                    id = "cs::m::$sid",
                    title = cleanTitle,
                    overview = s.optString("description").takeIf { it.isNotBlank() },
                    released = s.optString("releaseDate").takeIf { it.isNotBlank() },
                    poster = s.optJSONObject("cover")?.optString("url"),
                    rating = s.optString("imdbRatingValue").toDoubleOrNull(),
                    providerName = name,
                ))
                2 -> shows.add(TvShow(
                    id = "cs::s::$sid",
                    title = cleanTitle,
                    overview = s.optString("description").takeIf { it.isNotBlank() },
                    released = s.optString("releaseDate").takeIf { it.isNotBlank() },
                    poster = s.optJSONObject("cover")?.optString("url"),
                    rating = s.optString("imdbRatingValue").toDoubleOrNull(),
                    providerName = name,
                ))
            }
        }
        Genre(id = id, name = genreName, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People =
        People(id = id, name = "", filmography = emptyList())

    // ── Lecture : recherche subjectId Cloudstream + /resource ───────────

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = coroutineScope {
        // Cas 1 : id Cloudstream (cs::m::sid, cs::s::sid, cs::ep::sid::se::ep)
        // → on a déjà le subjectId, pas besoin de search
        var subjectId: String? = null
        var se = 0
        var ep = 0
        var queryTitle = ""
        var year: Int? = null
        var tmdbId: String? = null  // pour le backup Nakios (TMDB-id-based)

        when {
            id.startsWith("cs::ep::") -> {
                val parts = id.removePrefix("cs::ep::").split("::")
                subjectId = parts.getOrNull(0)
                se = parts.getOrNull(1)?.toIntOrNull() ?: 0
                ep = parts.getOrNull(2)?.toIntOrNull() ?: 0
            }
            id.startsWith("cs::m::") -> {
                subjectId = id.removePrefix("cs::m::")
            }
            id.startsWith("cs::s::") -> {
                subjectId = id.removePrefix("cs::s::")
            }
            else -> {
                // Cas 2 : id TMDB → extract title/year/se/ep depuis videoType, search backend
                tmdbId = when (videoType) {
                    is Video.Type.Movie -> id.takeIf { it.all(Char::isDigit) }
                    is Video.Type.Episode -> id.split(":").firstOrNull()?.takeIf { it.all(Char::isDigit) }
                }
                when (videoType) {
                    is Video.Type.Movie -> {
                        queryTitle = videoType.title
                        year = videoType.releaseDate.take(4).toIntOrNull()
                    }
                    is Video.Type.Episode -> {
                        queryTitle = videoType.tvShow.title
                        year = videoType.tvShow.releaseDate?.take(4)?.toIntOrNull()
                        val parts = id.split(":")
                        se = parts.getOrNull(1)?.toIntOrNull() ?: videoType.season.number
                        ep = parts.getOrNull(2)?.toIntOrNull() ?: videoType.number
                    }
                }
                if (!queryTitle.isBlank()) {
                    subjectId = findSubjectId(queryTitle, year)
                    if (subjectId.isNullOrBlank()) {
                        Log.d(TAG, "getServers : pas de subjectId Cloudstream pour '$queryTitle' ($year) — fallback Nakios si TMDB id dispo")
                    } else {
                        Log.d(TAG, "getServers TMDB→Cloudstream : '$queryTitle' ($year) → sid=$subjectId, se=$se ep=$ep")
                    }
                } else {
                    Log.d(TAG, "getServers : titre vide pour $id")
                }
            }
        }

        // Backup Nakios — lance EN PARALLÈLE de MovieBox+ (TMDB-id-based, indépendant)
        val nakiosBackupD = async {
            val tid = tmdbId ?: return@async emptyList<Video.Server>()
            try {
                NakiosProvider.fetchNakiosBackupServers(tid, videoType, season = se, episode = ep)
            } catch (e: Exception) {
                Log.w(TAG, "Nakios backup failed: ${e.message}")
                emptyList()
            }
        }

        val servers = mutableListOf<Video.Server>()

        // Pipeline MovieBox+ : seulement si on a un subjectId
        if (!subjectId.isNullOrBlank()) {
            val sid: String = subjectId!!
            // Tenter /resource pour les 4 résolutions en parallèle
            val resolutions = listOf(360, 480, 720, 1080)
            val streams = resolutions.map { resoltn ->
                async { findResourceStream(sid, se, ep, resoltn) }
            }.awaitAll().filterNotNull().distinctBy { it.first }.sortedByDescending { it.first }

            for ((idx, t) in streams.withIndex()) {
                servers.add(
                    Video.Server(
                        id = "cs_resource_${sid}_${se}_${ep}_${t.first}_$idx",
                        name = "Cloudstream [${t.first}p MP4]",
                        src = t.second,
                    )
                )
            }
            // 2026-05-07 : fallback /play-info DÉSACTIVÉ. Les URLs `hcdn3` retournées
            // par /play-info contiennent des pre-rolls publicitaires. Préférer 0 stream
            // que des streams avec pub. Si /resource n'a rien, Nakios prendra le relais.
            Log.d(TAG, "getServers $id : MovieBox+ /resource → ${servers.size} streams (sans /play-info pour éviter les pubs)")
        }

        // Append Nakios backup (lance toujours, ne dépend pas de MovieBox+)
        val nakiosServers = nakiosBackupD.await()
        if (nakiosServers.isNotEmpty()) {
            servers += nakiosServers
            Log.d(TAG, "getServers $id : +${nakiosServers.size} streams Nakios backup")
        }

        Log.d(TAG, "getServers $id → total=${servers.size}")
        servers
    }

    /** Cherche le stream d'un épisode/film donné dans /resource (paginé par 10).
     *  Retourne (resolution, url) ou null si pas trouvé. */
    private suspend fun findResourceStream(subjectId: String, se: Int, ep: Int, resoltn: Int): Pair<Int, String>? {
        val targetCode = if (se > 0 && ep > 0) se * 100 + ep else 0
        var page = 1
        while (page <= 8) {
            val r = apiGet(RESOURCE_PATH, mapOf(
                "subjectId" to subjectId,
                "se" to "${if (se > 0) se else 1}",
                "ep" to "${if (ep > 0) ep else 1}",
                "page" to "$page",
                "resolution" to "$resoltn",
            )) ?: return null
            val data = r.optJSONObject("data") ?: return null
            val list = data.optJSONArray("list") ?: return null
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val epCode = item.optInt("episode", -1)
                if (targetCode == 0 || epCode == targetCode) {
                    val u = item.optString("resourceLink").takeIf { it.isNotBlank() } ?: continue
                    return resoltn to u
                }
            }
            val pager = data.optJSONObject("pager")
            if (pager?.optBoolean("hasMore", false) != true) break
            page++
        }
        return null
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // Backup Nakios → délégue à NakiosProvider qui sait gérer URLs proxy
        // + extracteurs pour les embeds (Doodstream, Vidoza, Filemoon, etc.)
        if (server.id.startsWith("nakios_backup_")) {
            return try {
                NakiosProvider.getVideo(server)
            } catch (e: Exception) {
                Log.w(TAG, "Nakios getVideo failed for ${server.src}: ${e.message}")
                Video(source = server.src)
            }
        }
        // MovieBox+ direct (bcdn ou hcdn3 — déjà MP4/HLS direct)
        return Video(
            source = server.src,
            headers = mutableMapOf(
                "Referer" to "https://moviebox.ph/",
                "User-Agent" to USER_AGENT,
            ),
        )
    }

}
