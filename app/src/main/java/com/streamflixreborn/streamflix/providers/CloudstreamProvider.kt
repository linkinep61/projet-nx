package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
object CloudstreamProvider : Provider, ProgressiveServersProvider {

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
        "https://api2.inmoviebox.com",
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

    // 2026-05-08 : FIX /resource 406 "find no content" + ROBUSTIFICATION.
    // Cause identifiée : le serveur MovieBox+ refuse les `device_id` de 32 chars
    // (notre ancienne valeur UUID-like). Format attendu = 16 chars hex.
    // Pour pas se reprendre le piège (si le serveur blacklist un device_id précis),
    // on génère un device_id 16 chars hex RANDOM au premier launch et on le
    // persiste en SharedPreferences. Chaque user a donc son propre device_id stable.
    // Ref : github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension (CNCVerse).
    private const val USER_AGENT =
        "com.community.oneroom/50020045 (Linux; U; Android 13; en_US; 23078RKD5C; Build/TQ2A.230405.003; Cronet/135.0.7012.3)"

    /** device_id 16 chars hex stable par install. Généré au 1er accès, persisté. */
    private val persistedDeviceId: String by lazy {
        val prefs = StreamFlixApp.instance.applicationContext
            .getSharedPreferences("cloudstream_provider", Context.MODE_PRIVATE)
        val existing = prefs.getString("device_id_v1", null)
        // Sanity-check : exactement 16 chars hex, sinon on régénère
        if (existing != null && existing.length == 16 && existing.all { it.isDigit() || it in 'a'..'f' }) {
            existing
        } else {
            val bytes = ByteArray(8)
            java.security.SecureRandom().nextBytes(bytes)
            val newId = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("device_id_v1", newId).apply()
            Log.d(TAG, "Generated new persisted device_id: $newId")
            newId
        }
    }

    private val CLIENT_INFO: String by lazy {
        """
        {"package_name":"com.community.oneroom","version_name":"3.0.03.0529.03","version_code":50020045,
        "os":"android","os_version":"13","install_ch":"ps","device_id":"$persistedDeviceId",
        "install_store":"ps","gaid":"00000000-0000-0000-0000-000000000000","brand":"Redmi",
        "model":"23078RKD5C","system_language":"fr","net":"NETWORK_WIFI","region":"FR",
        "timezone":"Europe/Paris","sp_code":"40401","X-Play-Mode":"2"}
        """.trimIndent().replace("\n", "").replace("        ", "")
    }

    private const val MAIN_PAGE_PATH = "/wefeed-mobile-bff/tab-operating"
    private const val SEARCH_PATH = "/wefeed-mobile-bff/subject-api/search"
    private const val SUBJECT_GET_PATH = "/wefeed-mobile-bff/subject-api/get"
    private const val RESOURCE_PATH = "/wefeed-mobile-bff/subject-api/resource"
    private const val PLAY_INFO_PATH = "/wefeed-mobile-bff/subject-api/play-info"
    private const val SEASON_INFO_PATH = "/wefeed-mobile-bff/subject-api/season-info"
    private const val EXT_CAPTIONS_PATH = "/wefeed-mobile-bff/subject-api/get-ext-captions"

    /** Cache des URLs SRT FR par subjectId, populé au getServers, lu au getVideo.
     *  FR-only : Streamflix est FR-only et le user veut JUSTE les sous-titres FR
     *  (pas le bordel multi-langue qu'affiche Cloudstream officiel). */
    private val frenchCaptionsCache = ConcurrentHashMap<String, List<String>>()

    /** Cache des streams MP4/HLS par subjectId, populé avant unification, lu au
     *  getVideo pour construire un master m3u8 multi-quality. Liste de
     *  (resolution, url, linkType) où linkType=1 HLS, =2 MP4. Permet à ExoPlayer
     *  d'exposer les qualités réelles du film dans son onglet Qualité au lieu
     *  d'avoir un MP4 unique fixe. */
    private val cloudstreamStreamsCache = ConcurrentHashMap<String, List<Triple<Int, String, Int>>>()

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
                        // 2026-05-09 : log explicite incluant le path pour debug
                        // (MovieBox+ /resource qui retournerait silencieusement 407
                        // serait un signal de signature cassée).
                        Log.d(TAG, "apiGet $host$pathWithQuery returned $code, retry next")
                        return@use
                    }
                    if (!it.isSuccessful) {
                        Log.d(TAG, "apiGet $host$pathWithQuery NOT OK (code=$code), giving up")
                        return@withContext null
                    }
                    val body = it.body?.string() ?: return@withContext null
                    // 2026-05-09 : diagnostic. Si le body est vide ou ne contient
                    // pas "list", c'est suspect (changement format API ?).
                    Log.d(TAG, "apiGet ${pathWithQuery.take(60)} → ${code} size=${body.length} (head: ${body.take(150)})")
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
        // 2026-05-26 : garde longueur — le titre court doit faire ≥50% du long,
        // sinon "Faux" (4 chars) matche dans "Vrais voisins, faux amis" (24 chars).
        candidates.firstOrNull {
            val shorter = minOf(it.normTitle.length, normQuery.length)
            val longer  = maxOf(it.normTitle.length, normQuery.length)
            (it.normTitle.contains(normQuery) || normQuery.contains(it.normTitle))
                && shorter >= longer / 2
                && year != null && kotlin.math.abs(it.year - year) <= 2
        }?.let {
            tmdbToSubjectIdCache[cacheKey] = it.sid
            Log.d(TAG, "findSubjectId('$cleanQuery' year=$year) → contains+nearYear=${it.cleanTitle} (${it.year}) sid=${it.sid}")
            return it.sid
        }
        // Fallback : meilleur candidat dans ±5 ans, classé par :
        //   1. proximité titre (commence par normQuery, ou contains, ou levenshtein faible)
        //   2. proximité année (|year - target|)
        // Évite de matcher Les Tuche 4 (2021) sur une recherche "Les Tuche 3" (2018) :
        // les deux sont ±5 ans mais Les Tuche 3 doit gagner si présent.
        if (year != null) {
            val withinRange = candidates.filter { kotlin.math.abs(it.year - year) <= 5 }
            // Score : plus c'est petit, mieux c'est
            // 2026-05-26 : garde longueur — le titre court doit faire ≥50% du long
            // pour les niveaux 1-3 (startsWith/contains), sinon "Faux" matche "Vrais voisins faux amis".
            fun titleScore(c: Candidate): Int {
                val shorter = minOf(c.normTitle.length, normQuery.length)
                val longer  = maxOf(c.normTitle.length, normQuery.length)
                val lenOk   = shorter >= longer / 2
                return when {
                    c.normTitle == normQuery -> 0
                    lenOk && c.normTitle.startsWith(normQuery) -> 1
                    lenOk && normQuery.startsWith(c.normTitle) -> 2
                    lenOk && (c.normTitle.contains(normQuery) || normQuery.contains(c.normTitle)) -> 3
                    else -> 10
                }
            }
            val best = withinRange.minByOrNull {
                titleScore(it) * 100 + kotlin.math.abs(it.year - year)
            }
            if (best != null) {
                tmdbToSubjectIdCache[cacheKey] = best.sid
                Log.d(TAG, "findSubjectId('$cleanQuery' year=$year) → fallback best=${best.cleanTitle} (${best.year}) sid=${best.sid} (titleScore=${titleScore(best)}, yearDelta=${kotlin.math.abs(best.year - year)})")
                return best.sid
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
            withOriginalLanguage = csOriginalLanguageBuilder(),
        ).results.filter { it.posterPath != null }.map(mapMovie)
    }.getOrDefault(emptyList())

    private suspend fun discoverTvOnProvider(
        watchProviderId: Int, mapTv: (TMDb3.Tv) -> TvShow,
    ): List<TvShow> = runCatching {
        TMDb3.Discover.tv(
            page = 1, language = language,
            watchRegion = "FR",
            sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
            withWatchProviders = TMDb3.Params.WithBuilder(watchProviderId),
            withOriginalLanguage = csOriginalLanguageBuilder(),
        ).results.filter { it.posterPath != null }.map(mapTv)
    }.getOrDefault(emptyList())

    /** 2026-05-20 — langue d'origine TMDB à appliquer selon le filtre catalogue
     *  choisi par l'utilisateur (bouton filtre du home). null = pas de filtre de
     *  langue (populaire international, souvent VF). Remplace le `"fr"` figé. */
    private fun csOriginalLanguageBuilder(): TMDb3.Params.WithBuilder<String>? {
        val lang = com.streamflixreborn.streamflix.utils.CatalogFilter.originalLanguage(name)
        return lang?.let { TMDb3.Params.WithBuilder(it) }
    }

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

        // 2026-05-07 : on remplace les listes Popular/TopRated globales (qui contiennent
        // beaucoup de non-FR juste traduits) par des Discover avec
        // withOriginalLanguage="fr" pour ne garder QUE le contenu de langue
        // originale française. Featured = TMDB Trending DAY filtré côté client par
        // originalLanguage=="fr".
        val trendingD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = 1, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                    withOriginalLanguage = csOriginalLanguageBuilder(),
                    voteCount = TMDb3.Params.Range(50, null),
                ).results
            }.getOrDefault(emptyList())
        }
        val popularMoviesD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = 1, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                    withOriginalLanguage = csOriginalLanguageBuilder(),
                ).results
            }.getOrDefault(emptyList())
        }
        val topMoviesD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = 1, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.VOTE_AVERAGE_DESC,
                    withOriginalLanguage = csOriginalLanguageBuilder(),
                    voteCount = TMDb3.Params.Range(200, null),
                ).results
            }.getOrDefault(emptyList())
        }
        val popularTvD = async {
            runCatching {
                TMDb3.Discover.tv(
                    page = 1, language = language,
                    sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                    withOriginalLanguage = csOriginalLanguageBuilder(),
                ).results
            }.getOrDefault(emptyList())
        }
        val topTvD = async {
            runCatching {
                TMDb3.Discover.tv(
                    page = 1, language = language,
                    sortBy = TMDb3.Params.SortBy.Tv.VOTE_AVERAGE_DESC,
                    withOriginalLanguage = csOriginalLanguageBuilder(),
                    voteCount = TMDb3.Params.Range(50, null),
                ).results
            }.getOrDefault(emptyList())
        }
        // Nouveaux films + Nouvelles séries — TMDB Discover, sortie récente, marché FR.
        // voteCount>=10 pour exclure les sorties confidentielles. À la lecture,
        // getServers() résout vers MovieBox+ par titre+année.
        val newMoviesD = async {
            runCatching {
                // Page 1+2 pour avoir plus d'items après mapping. STRICT FR ORIGINAL.
                val p1 = async {
                    TMDb3.Discover.movie(
                        page = 1, language = language, region = "FR",
                        sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                        voteCount = TMDb3.Params.Range(10, null),
                        withOriginalLanguage = csOriginalLanguageBuilder(),
                    ).results
                }
                val p2 = async {
                    TMDb3.Discover.movie(
                        page = 2, language = language, region = "FR",
                        sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                        voteCount = TMDb3.Params.Range(10, null),
                        withOriginalLanguage = csOriginalLanguageBuilder(),
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
                        withOriginalLanguage = csOriginalLanguageBuilder(),
                    ).results
                }
                val p2 = async {
                    TMDb3.Discover.tv(
                        page = 2, language = language,
                        sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,
                        voteCount = TMDb3.Params.Range(10, null),
                        watchRegion = "FR",
                        withOriginalLanguage = csOriginalLanguageBuilder(),
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

        // 2026-05-12 : SUPPRIMÉ — `newReleasesD` (MovieBox+ tabs + classifyFrench
        // sur 400+ items via /subject-api/get) tournait en async background MAIS
        // n'était JAMAIS await(). 12 calls /main_page + ~424 calls /subject-api/get
        // = ~18-25 secondes de travail HTTP pour résultat jamais consommé.
        // Le "Nouveau sur Cloudstream" est déjà construit à partir de newMovies
        // + newTv (TMDB Discover region=FR) plus bas — pas besoin de doublon.

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
        val featured = trendingD.await()
            .filter { it.posterPath != null }
            .take(10).mapNotNull { it.toAppItem() }
        if (featured.isNotEmpty()) sections.add(Category(name = Category.FEATURED, list = featured))

        // Nouveautés FR (films + séries séparés, pas de section mixte doublon)
        val newMovies = newMoviesD.await().filter { it.posterPath != null }.map(mapMovie)
        val newTv = newTvD.await().filter { it.posterPath != null }.map(mapTv)
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
        val popularTv = popularTvD.await().filter { it.posterPath != null }.map(mapTv)
        if (popularTv.isNotEmpty()) sections.add(Category(name = "Séries populaires", list = popularTv))

        val topTv = topTvD.await().filter { it.posterPath != null }.map(mapTv)
        if (topTv.isNotEmpty()) sections.add(Category(name = "Séries les mieux notées", list = topTv))

        val popularMovies = popularMoviesD.await().filter { it.posterPath != null }.map(mapMovie)
        if (popularMovies.isNotEmpty()) sections.add(Category(name = "Films populaires", list = popularMovies))

        val topMovies = topMoviesD.await().filter { it.posterPath != null }.map(mapMovie)
        if (topMovies.isNotEmpty()) sections.add(Category(name = "Films les mieux notés", list = topMovies))

        Log.d(TAG, "getHome Cloudstream : ${sections.size} sections — featured=${featured.size}, nouveauxFilms=${newMovies.size}, nouvellesSeries=${newTv.size}")
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
        // 2026-05-07 : strict FR original — withOriginalLanguage="fr"
        return runCatching {
            TMDb3.Discover.movie(
                page = page, language = language, region = "FR",
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                withOriginalLanguage = csOriginalLanguageBuilder(),
            ).results.map { m ->
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
        // 2026-05-07 : strict FR original — withOriginalLanguage="fr"
        return runCatching {
            TMDb3.Discover.tv(
                page = page, language = language,
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                withOriginalLanguage = csOriginalLanguageBuilder(),
            ).results.map { t ->
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
        // 2026-05-07 : retour à TMDB Search.multi sans filtre langue (catalogue
        // mondial). À la lecture, Cloudstream tente MovieBox+ /resource via
        // findSubjectId puis Nakios backup si MovieBox+ n'a pas. L'user voit tous
        // les résultats TMDB et peut tenter chaque film/série.
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
                    // Note : pas de People() — itemType lateinit plante DiffUtil.
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
        // 2026-05-26 : respecte le CatalogFilter (comme getMovies/getTvShows)
        // au lieu de forcer "fr" en dur — sinon genres de niche (Musique, Western…) = vides.
        val langFilter: TMDb3.Params.WithBuilder<String>? =
            if (originCountry == null) csOriginalLanguageBuilder() else null
        return runCatching {
            val tmdbGenreId = id.toIntOrNull()
            val withOrigin: TMDb3.Params.WithBuilder<String>? = originCountry?.let {
                TMDb3.Params.WithBuilder(it)
            }
            // 2026-05-26 : passe withGenres à l'API TMDB au lieu de filtrer côté client.
            // L'API renvoie directement les résultats du bon genre → beaucoup plus de résultats.
            val genreFilterMovie: TMDb3.Params.WithBuilder<TMDb3.Genre.Movie>? =
                tmdbGenreId?.let { gid ->
                    TMDb3.Genre.Movie.entries.find { it.id == gid }
                        ?.let { TMDb3.Params.WithBuilder(it) }
                }
            val genreFilterTv: TMDb3.Params.WithBuilder<TMDb3.Genre.Tv>? =
                tmdbGenreId?.let { gid ->
                    TMDb3.Genre.Tv.entries.find { it.id == gid }
                        ?.let { TMDb3.Params.WithBuilder(it) }
                }
            // Films
            val movies = if (genreFilterMovie != null || tmdbGenreId == null) {
                TMDb3.Discover.movie(
                    language = language, page = page,
                    withOriginCountry = withOrigin,
                    withOriginalLanguage = langFilter,
                    withGenres = genreFilterMovie,
                    sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                ).results.map { m ->
                    Movie(
                        id = m.id.toString(), title = m.title, overview = m.overview,
                        released = m.releaseDate, rating = m.voteAverage.toDouble(),
                        poster = m.posterPath?.w500, banner = m.backdropPath?.w1280,
                    )
                }
            } else emptyList()  // genre ID existe côté TV mais pas Movie → skip
            // Séries TV
            val tvShows = if (genreFilterTv != null || tmdbGenreId == null) {
                TMDb3.Discover.tv(
                    language = language, page = page,
                    withOriginCountry = withOrigin,
                    withOriginalLanguage = langFilter,
                    withGenres = genreFilterTv,
                    sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                ).results.map { t ->
                    TvShow(
                        id = t.id.toString(), title = t.name, overview = t.overview,
                        released = t.firstAirDate, rating = t.voteAverage.toDouble(),
                        poster = t.posterPath?.w500, banner = t.backdropPath?.w1280,
                    )
                }
            } else emptyList()  // genre ID existe côté Movie mais pas TV → skip
            // Tri par popularité desc (les plus populaires en haut)
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

    // 2026-05-21 : sources NATIVES Cloudstream (MovieBox+ : /resource + /play-info +
    //   resourceDetectors + politique FR-only + unification multi-qualité). Extrait
    //   de getServers pour être émis comme un étage du flux progressif. Résout son
    //   propre subjectId. NE contient PLUS les backups Nakios/Movix (helpers séparés).
    private suspend fun fetchNativeCloudstreamServers(id: String, videoType: Video.Type): List<Video.Server> = coroutineScope {
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

        // 2026-05-08 : AUTO-SWITCH dub FR — MovieBox+ stocke chaque dub comme
        // un sujet séparé. findSubjectId retourne souvent le dub d'origine
        // (anglais, Hindi pour le marché indien). Si un dub français existe
        // (ex: "MovieBox (French Audio)" visible dans Cloudstream officiel), on
        // swap subjectId AVANT lancement des async pour récupérer ses streams
        // FR natifs au lieu de l'anglais avec sub.
        // Coût : 1 appel /get sequentiel (~200ms). Cache la réponse pour
        // éviter le re-fetch dans getRespD.
        var initialGetCached: JSONObject? = null
        if (!subjectId.isNullOrBlank()) {
            try {
                initialGetCached = apiGet(SUBJECT_GET_PATH, mapOf("subjectId" to subjectId!!))
                val frSid = initialGetCached?.let { findFrenchDubSubjectId(it) }
                if (frSid != null && frSid != subjectId) {
                    Log.d(TAG, "Auto-switch vers dub FR : sid=$subjectId → $frSid")
                    subjectId = frSid
                    initialGetCached = null  // invalide pour le nouveau sid → refetch
                }
            } catch (e: Exception) {
                Log.w(TAG, "Initial /get for FR-dub check failed: ${e.message}")
            }
        }

        // 2026-05-21 : backups Nakios/Movix déplacés dans des helpers séparés
        //   (fetchNakiosBackup / fetchMovixBackupForCs) appelés par getServers et
        //   getServersProgressive. Ici on ne produit QUE les sources natives MovieBox+.
        val servers = mutableListOf<Video.Server>()

        // 2026-05-08 : FAST PATH + DÉTECTION LANGUE — `/get` retourne 2 trésors :
        //  1. `data.resourceDetectors[].resolutionList[]` = liens MP4 directs
        //     (CDN bcdn.hakunaymatata.com, zéro pubs).
        //  2. `data.dubs[]` (avec lanCode) + `data.subtitles` (CSV) = dispo audio/sub
        //     pour calculer si le stream est VF / VOSTFR / VO.
        //  On fait 1 SEUL appel /get partagé entre le fast path et le marquage langue.
        val getRespD = async {
            // Réutilise initialGetCached si pas de switch (cache hit)
            initialGetCached?.let { return@async it }
            val sid = subjectId ?: return@async null
            try {
                apiGet(SUBJECT_GET_PATH, mapOf("subjectId" to sid))
            } catch (e: Exception) {
                Log.w(TAG, "/get prefetch failed: ${e.message}")
                null
            }
        }

        val resourceDetectorsD = async {
            val resp = getRespD.await() ?: return@async emptyList<Video.Server>()
            try {
                parseResourceDetectorsFromJson(resp, subjectId!!, se, ep)
            } catch (e: Exception) {
                Log.w(TAG, "resourceDetectors fast path failed: ${e.message}")
                emptyList()
            }
        }

        // 2026-05-08 : SOUS-TITRES AUTO — fetch SRT FR en parallèle. Populé dans
        // frenchCaptionsCache pour que getVideo les attache au Video automatiquement.
        // Si captions FR dispo → langSuffix devient [VF auto] (pas pénalisé) au lieu
        // de [VOSTFR] (pénalisé -100). UX = équivalent VF.
        val frenchCaptionsD = async {
            val resp = getRespD.await() ?: return@async emptyList<String>()
            val sid = subjectId ?: return@async emptyList()
            val rid = firstResourceIdFromGet(resp, se, ep) ?: return@async emptyList()
            try {
                fetchFrenchCaptions(sid, rid, ep)
            } catch (e: Exception) {
                Log.w(TAG, "Fetch FR captions failed: ${e.message}")
                emptyList()
            }
        }

        // 2026-05-08 : pipeline /resource RÉACTIVÉ. Les 406 venaient du bump
        // UA/CLIENT_INFO en 50090111 — restauration en 50020045 = ça remarche.
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
            // 2026-05-07 v2 : fallback /play-info RÉACTIVÉ uniquement quand /resource
            // n'a rien (cas films comme Les Tuche 2 où MovieBox+ stocke uniquement
            // sur hcdn3). Marqué "(pub possible)" pour clarté — souvent pre-roll.
            if (servers.isEmpty()) {
                val params = mutableMapOf("subjectId" to sid)
                if (se > 0) params["se"] = "$se"
                if (ep > 0) params["ep"] = "$ep"
                val resp = apiGet(PLAY_INFO_PATH, params)
                val data = resp?.optJSONObject("data")
                val streamArr = data?.optJSONArray("streams")
                if (streamArr != null) {
                    val list = (0 until streamArr.length()).mapNotNull { i ->
                        val s = streamArr.optJSONObject(i) ?: return@mapNotNull null
                        val u = s.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val res = s.optString("resolutions").ifEmpty { s.optString("resolution") }
                        (res.toIntOrNull() ?: 0) to u
                    }.sortedByDescending { it.first }
                    for ((idx, t) in list.withIndex()) {
                        servers.add(
                            Video.Server(
                                id = "cs_playinfo_${sid}_${se}_${ep}_${t.first}_$idx",
                                name = "Cloudstream [${t.first}p MP4] (pub possible)",
                                src = t.second,
                            )
                        )
                    }
                }
            }
            Log.d(TAG, "getServers $id : MovieBox+ → ${servers.size} streams")
        }

        // 2026-05-08 : Append fast path resourceDetectors EN TÊTE (les MP4 directs
        // bcdn.hakunaymatata.com sont les plus rapides + zéro pubs = best UX).
        val rdServers = resourceDetectorsD.await()
        if (rdServers.isNotEmpty()) {
            // Insert au début : ils s'affichent en premier dans le picker
            servers.addAll(0, rdServers)
            Log.d(TAG, "getServers $id : +${rdServers.size} streams resourceDetectors (fast path)")
        }

        // 2026-05-08 : POLITIQUE STRICTE FR-only (user s'est plaint des [VF auto]
        // qui menteaient pour Matrix anglais). Règle : les servers Cloudstream
        // n'apparaissent dans le picker QUE si le film est confirmé FR :
        //   - dub FR détecté dans dubs[] → [VF] OK, on garde
        //   - sinon, captions FR vraiment récupérées via /get-ext-captions → [VF auto] OK
        //   - sinon (VO/VOSTFR/inconnu) → SUPPRESSION des servers Cloudstream
        //     du picker. User tombe sur Movix/Nakios FR (les backups TMDB-id-based).
        val frenchCaps = frenchCaptionsD.await()
        if (frenchCaps.isNotEmpty() && subjectId != null) {
            frenchCaptionsCache[subjectId] = frenchCaps
            Log.d(TAG, "getServers $id : ${frenchCaps.size} sous-titre(s) FR cachés pour sid=$subjectId")
        }
        // 2026-05-08 : UNIFICATION + multi-qualité.
        //  1. Extrait les Triple(resolution, url, linkType) depuis tous les
        //     csServers AVANT d'unifier → cache pour le master m3u8 au getVideo.
        //  2. Unifie en 1 seul "Cloudstream" dans le picker.
        //  3. Au getVideo, construit un master HLS playlist côté client agrégeant
        //     ces qualités → ExoPlayer parse → onglet Qualité expose les vraies
        //     qualités MovieBox+ (360p/480p/720p/1080p selon ce que le film a).
        val csServers = servers.filter { it.id.startsWith("cs_") }
        if (csServers.size >= 1 && subjectId != null) {
            val streams = csServers.mapNotNull { srv ->
                val match = Regex("\\[(\\d+)p (MP4|HLS)]").find(srv.name) ?: return@mapNotNull null
                val res = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val format = match.groupValues[2]
                val linkType = if (format == "HLS") 1 else 2
                Triple(res, srv.src, linkType)
            }.distinctBy { it.first }.sortedByDescending { it.first }
            if (streams.isNotEmpty()) {
                cloudstreamStreamsCache[subjectId!!] = streams
                Log.d(TAG, "getServers $id : ${streams.size} qualité(s) Cloudstream cachées : ${streams.map { it.first }.joinToString()}p")
            }
        }
        if (csServers.size > 1) {
            // 2026-05-08 (revert) : user veut UNIQUEMENT les 2 premières qualités
            // (1080p + 720p, ou les 2 plus hautes dispo). 480p/360p drop —
            // niveau de qualité inacceptable + clutter le picker. Trie par
            // résolution desc, dédup par résolution (MP4 prioritaire), puis
            // take(2).
            val sorted = csServers.sortedWith(compareByDescending<Video.Server> {
                Regex("\\[(\\d+)p").find(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }.thenBy { if (it.name.contains("MP4")) 0 else 1 })
            val seenRes = mutableSetOf<Int>()
            val uniqueByRes = sorted.filter { srv ->
                val r = Regex("\\[(\\d+)p").find(srv.name)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                seenRes.add(r)
            }.take(2)  // 2026-05-08 : top 2 résolutions seulement
            val others = servers.filter { !it.id.startsWith("cs_") }
            servers.clear()
            servers.addAll(uniqueByRes)
            servers.addAll(others)
            Log.d(TAG, "getServers $id : ${uniqueByRes.size} qualités Cloudstream gardées (top 2 : ${uniqueByRes.map { it.name }})")
        }

        val getRespFinal = getRespD.await()
        val hasFrDub = getRespFinal?.let { hasFrenchDub(it) } == true
        val hasFrCaptions = frenchCaps.isNotEmpty()
        // 2026-05-21 (user "surtout les serveurs de Cloudstream lui-même") : on ne
        // SUPPRIME plus les sources Cloudstream natives quand le dub FR n'est pas
        // confirmé — on les GARDE et on les TAGUE honnêtement par langue :
        //   dub FR → [VF] ; sous-titres FR dispo → [VOSTFR] ; sinon → [VO].
        // Ainsi elles apparaissent toujours, et le ranker (pénalité langue) les place
        // VF en haut / VOSTFR au milieu / VO en bas. Plus de "faux VF" (l'ancienne
        // raison du filtre dur) : un contenu anglais est tagué [VO], pas [VF].
        val csLangTag = when {
            hasFrDub -> "[VF]"
            hasFrCaptions -> "[VOSTFR]"
            else -> "[VO]"
        }
        for (i in servers.indices) {
            val srv = servers[i]
            if (srv.id.startsWith("cs_") && !srv.name.contains(Regex("\\[V[FO]"))) {
                servers[i] = srv.copy(name = "${srv.name} $csLangTag")
            }
        }
        Log.d(TAG, "getServers $id : Cloudstream natifs gardés, tag $csLangTag (dub=$hasFrDub, caps=$hasFrCaptions)")

        Log.d(TAG, "getServers $id (natif) → ${servers.size} sources Cloudstream")
        servers
    }

    // ───────── Backups (helpers extraits pour le flux progressif) ─────────

    /** Backup Nakios (TMDB-id-based, indépendant de MovieBox+). */
    private suspend fun fetchNakiosBackup(
        tmdbId: String?, videoType: Video.Type, se: Int, ep: Int,
    ): List<Video.Server> {
        val tid = tmdbId ?: return emptyList()
        return try {
            NakiosProvider.fetchNakiosBackupServers(tid, videoType, season = se, episode = ep)
        } catch (e: Exception) {
            Log.w(TAG, "Nakios backup failed: ${e.message}")
            emptyList()
        }
    }

    /** Backup Movix #3 (couvre les contenus absents de MovieBox+/Nakios).
     *  skipBackupsForBackupCall=true → Movix skippe ses propres backups (anti-récursion
     *  Cloudstream↔Movix). Servers wrappés `movix_backup__*` pour délégation getVideo. */
    private suspend fun fetchMovixBackupForCs(
        tmdbId: String?, videoType: Video.Type, timeoutMs: Long = 8_000L,
    ): List<Video.Server> {
        val tid = tmdbId ?: return emptyList()
        if (!tid.all { it.isDigit() }) return emptyList()
        return try {
            val prev = MovixProvider.skipBackupsForBackupCall
            MovixProvider.skipBackupsForBackupCall = true
            try {
                val raw = withTimeoutOrNull(timeoutMs) { MovixProvider.getServers(tid, videoType) } ?: emptyList()
                raw.map { srv -> srv.copy(id = "movix_backup__${srv.id}") }
            } finally {
                MovixProvider.skipBackupsForBackupCall = prev
            }
        } catch (e: Exception) {
            Log.w(TAG, "Movix backup failed: ${e.message}")
            emptyList()
        }
    }

    /** Parse cheap du tmdbId/se/ep depuis l'id (sans findSubjectId), pour les backups. */
    private fun parseCsIds(id: String, videoType: Video.Type): Triple<String?, Int, Int> = when {
        id.startsWith("cs::ep::") -> {
            val parts = id.removePrefix("cs::ep::").split("::")
            Triple(null, parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2)?.toIntOrNull() ?: 0)
        }
        id.startsWith("cs::m::") || id.startsWith("cs::s::") -> Triple(null, 0, 0)
        else -> when (videoType) {
            is Video.Type.Movie -> Triple(id.takeIf { it.all(Char::isDigit) }, 0, 0)
            is Video.Type.Episode -> {
                val parts = id.split(":")
                val tmdb = parts.firstOrNull()?.takeIf { it.all(Char::isDigit) }
                val se = parts.getOrNull(1)?.toIntOrNull() ?: videoType.season.number
                val ep = parts.getOrNull(2)?.toIntOrNull() ?: videoType.number
                Triple(tmdb, se, ep)
            }
        }
    }

    // 2026-05-21 : version BATCH (inchangée pour l'extérieur — appels backup depuis
    //   Movix, fallback ViewModel). Combine natif + Nakios + Movix, en parallèle.
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = coroutineScope {
        val (tmdbId, se, ep) = parseCsIds(id, videoType)
        val nativeD = async { fetchNativeCloudstreamServers(id, videoType) }
        val nakiosD = async { fetchNakiosBackup(tmdbId, videoType, se, ep) }
        val movixD = async { fetchMovixBackupForCs(tmdbId, videoType) }
        val out = mutableListOf<Video.Server>()
        out += nativeD.await()
        nakiosD.await().let { if (it.isNotEmpty()) { Log.d(TAG, "getServers $id : +${it.size} Nakios"); out += it } }
        movixD.await().let { if (it.isNotEmpty()) { Log.d(TAG, "getServers $id : +${it.size} Movix"); out += it } }
        Log.d(TAG, "getServers $id → total=${out.size}")
        out
    }

    // 2026-05-21 : version PROGRESSIVE — émet natif MovieBox+, Nakios et Movix au fur
    //   et à mesure (Nakios/Movix partent tout de suite, basés tmdbId ; le natif après
    //   résolution du subjectId). Le 1er prêt s'affiche en premier.
    override fun getServersProgressive(
        id: String, videoType: Video.Type,
    ): Flow<List<Video.Server>> = channelFlow {
        val (tmdbId, se, ep) = parseCsIds(id, videoType)

        // 2026-06-02 : port du pipeline Movix — dedup src + disambiguation #N +
        //   skip all-dead-batches + sort par langue. Sans ça, le 1er emit
        //   (souvent natif) peut contenir seulement "Chamber (Videasy VOSTFR)"
        //   qui est dead → auto-play casse tout. Idem dedup cross-batch.
        val seenSrc = HashSet<String>()
        val nameCount = mutableMapOf<String, Int>()
        val mutex = Mutex()
        val deadExtractors = try {
            com.streamflixreborn.streamflix.utils.ExtractorFailureTracker
                .getFailures()
                .filter { it.count >= 5 }
                .map { it.name.lowercase() }
                .toSet()
        } catch (e: Exception) { emptySet() }

        fun isDeadServer(srv: Video.Server): Boolean {
            val srvNameLower = srv.name.lowercase()
            val extractorName = com.streamflixreborn.streamflix.utils.ExtractorRanker
                .resolveExtractorName(srv)?.lowercase()
            return deadExtractors.any { dead ->
                srvNameLower.contains(dead) || (extractorName != null && extractorName == dead)
            }
        }

        suspend fun emitDeduped(batch: List<Video.Server>) {
            val cleaned: List<Video.Server> = mutex.withLock {
                batch.mapNotNull { srv: Video.Server ->
                    val key = srv.src.trim()
                    if (key.isNotBlank() && !seenSrc.add(key)) return@mapNotNull null
                    val cnt = (nameCount[srv.name] ?: 0) + 1
                    nameCount[srv.name] = cnt
                    if (cnt > 1) srv.copy(name = "${srv.name} #$cnt") else srv
                }
            }
            if (cleaned.isNotEmpty()) {
                // Trier par langue (VF d'abord), puis pousser les dead en fin
                val sorted = cleaned.sortedBy { srv: Video.Server ->
                    val n = srv.name.uppercase()
                    when {
                        n.contains("VFF") || n.contains("TRUEFRENCH") -> 0
                        n.contains("VF") && !n.contains("VOSTFR") -> 1
                        n.contains("VOSTFR") || n.contains("VOST") -> 5
                        n.contains("VO") -> 6
                        else -> 3
                    }
                }
                val alive = sorted.filter { !isDeadServer(it) }
                val dead = sorted.filter { isDeadServer(it) }
                if (alive.isEmpty()) {
                    Log.d(TAG, "Skip emit : batch all-dead (${dead.size} serveurs) — attend backups")
                } else {
                    send(alive + dead)
                }
            }
        }

        launch {
            try { val n = fetchNativeCloudstreamServers(id, videoType); if (n.isNotEmpty()) emitDeduped(n) }
            catch (e: Exception) { Log.w(TAG, "Progressive native failed: ${e.message}") }
        }
        launch {
            try { val k = fetchNakiosBackup(tmdbId, videoType, se, ep); if (k.isNotEmpty()) emitDeduped(k) }
            catch (e: Exception) { Log.w(TAG, "Progressive Nakios failed: ${e.message}") }
        }
        launch {
            try { val m = fetchMovixBackupForCs(tmdbId, videoType, timeoutMs = 15_000L); if (m.isNotEmpty()) emitDeduped(m) }
            catch (e: Exception) { Log.w(TAG, "Progressive Movix failed: ${e.message}") }
        }
        // 2026-05-28 : scrape direct Wiflix + FrenchStream (HTTP simple, ~3s)
        if (tmdbId != null) {
            launch {
                try { val wf = MovixProvider.fetchWiflixDirectBackup(tmdbId, videoType); if (wf.isNotEmpty()) emitDeduped(wf) }
                catch (e: Exception) { Log.w(TAG, "Progressive Wiflix direct failed: ${e.message}") }
            }
            launch {
                try { val fs = MovixProvider.fetchFrenchStreamDirectBackup(tmdbId, videoType); if (fs.isNotEmpty()) emitDeduped(fs) }
                catch (e: Exception) { Log.w(TAG, "Progressive FS direct failed: ${e.message}") }
            }
            // 2026-06-03 (user "Comment ça se fait qu'il n'était pas dans la
            //   liste Y avait que Movix") : Coflix manquait dans les backups
            //   Cloudstream → films comme Aventures croisées 2026 qui sont sur
            //   Coflix mais pas sur MovieBox+/Nakios/Movix → 0 source. On
            //   résout title+year via TMDB puis on appelle CoflixSourceProvider.
            launch {
                try { val cf = fetchCoflixBackup(tmdbId, videoType, se, ep); if (cf.isNotEmpty()) emitDeduped(cf) }
                catch (e: Exception) { Log.w(TAG, "Progressive Coflix failed: ${e.message}") }
            }
        }
    }

    /** 2026-06-03 — Backup Coflix. Cloudstream est tmdbId-based ; Coflix est
     *  titre/année-based. On résout via TMDB puis on appelle
     *  CoflixSourceProvider.getMovie/EpisodeSources (qui ont eux-mêmes un
     *  fallback titre original + traduction FR via TMDB). */
    private suspend fun fetchCoflixBackup(
        tmdbId: String?,
        videoType: Video.Type,
        se: Int?,
        ep: Int?,
    ): List<Video.Server> = runCatching {
        val tmdbIdInt = tmdbId?.toIntOrNull() ?: return@runCatching emptyList()
        when (videoType) {
            is Video.Type.Movie -> {
                val details = TMDb3.Movies.details(movieId = tmdbIdInt, language = "fr-FR")
                val title = details.title.takeIf { it.isNotBlank() }
                    ?: details.originalTitle?.takeIf { it.isNotBlank() }
                    ?: return@runCatching emptyList()
                val year = details.releaseDate?.take(4)?.toIntOrNull()
                CoflixSourceProvider.getMovieSources(title, year, altTitle = details.originalTitle)
            }
            is Video.Type.Episode -> {
                val sn = se ?: videoType.season.number
                val en = ep ?: videoType.number
                val details = TMDb3.TvSeries.details(seriesId = tmdbIdInt, language = "fr-FR")
                val showTitle = details.name.takeIf { it.isNotBlank() }
                    ?: details.originalName?.takeIf { it.isNotBlank() }
                    ?: return@runCatching emptyList()
                val year = details.firstAirDate?.take(4)?.toIntOrNull()
                CoflixSourceProvider.getEpisodeSources(
                    showTitle = showTitle,
                    year = year,
                    seasonNumber = sn,
                    episodeNumber = en,
                    altShowTitle = details.originalName,
                )
            }
        }
    }.getOrElse {
        Log.w(TAG, "fetchCoflixBackup failed: ${it.message}")
        emptyList()
    }

    /** Vrai si la réponse `/get` liste un dub `fr` dans `data.dubs[]`.
     *  Utilisé pour décider si on garde les servers Cloudstream dans le picker. */
    private fun hasFrenchDub(getResp: JSONObject): Boolean {
        val dubs = getResp.optJSONObject("data")?.optJSONArray("dubs") ?: return false
        for (i in 0 until dubs.length()) {
            val dub = dubs.optJSONObject(i) ?: continue
            val code = dub.optString("lanCode").lowercase()
            val name = dub.optString("lanName").lowercase()
            if (code == "fr" || code == "fre" || code == "fra" ||
                name.contains("french") || name.contains("français") || name.contains("francais")) {
                return true
            }
        }
        return false
    }

    /** Cherche dans `data.dubs[]` un dub français et retourne son subjectId.
     *  MovieBox+ stocke chaque dub comme un sujet séparé : Hidden Figures EN
     *  est SID_A, son dub Hindi est SID_B, son dub FR est SID_C. Notre
     *  findSubjectId tombe souvent sur le dub Hindi (catalogue indien). Pour
     *  avoir le vrai dub FR, il faut sélectionner le bon subjectId.
     *
     *  Format : `dubs: [{subjectId, lanCode, lanName, original, type}]` */
    private fun findFrenchDubSubjectId(getResp: JSONObject): String? {
        val dubs = getResp.optJSONObject("data")?.optJSONArray("dubs") ?: return null
        for (i in 0 until dubs.length()) {
            val dub = dubs.optJSONObject(i) ?: continue
            val code = dub.optString("lanCode").lowercase()
            val name = dub.optString("lanName").lowercase()
            if (code == "fr" || code == "fre" || code == "fra" ||
                name.contains("french") || name.contains("français") || name.contains("francais")) {
                val sid = dub.optString("subjectId").takeIf { it.isNotBlank() }
                if (sid != null) return sid
            }
        }
        return null
    }

    /** Cherche dans la JSON `/get` le 1er `resourceId` qui matche l'épisode demandé.
     *  Utilisé pour appeler `/get-ext-captions` qui retourne les SRT FR/etc. */
    private fun firstResourceIdFromGet(resp: JSONObject, se: Int, ep: Int): String? {
        val detectors = resp.optJSONObject("data")?.optJSONArray("resourceDetectors") ?: return null
        for (i in 0 until detectors.length()) {
            val detector = detectors.optJSONObject(i) ?: continue
            val resolutionList = detector.optJSONArray("resolutionList") ?: continue
            for (j in 0 until resolutionList.length()) {
                val item = resolutionList.optJSONObject(j) ?: continue
                if (item.optInt("se", 0) != se || item.optInt("ep", 0) != ep) continue
                val rid = item.optString("resourceId").takeIf { it.isNotBlank() }
                if (rid != null) return rid
            }
        }
        return null
    }

    /** Fetche les sous-titres FR pour un film via `/get-ext-captions`.
     *  FR-only : on ne ramène QUE les français (le user veut juste ça, pas le
     *  bordel multi-langue d'autres apps). Returns liste d'URLs SRT.
     *
     *  Format réponse :
     *  ```
     *  data.extCaptions: [
     *    { lan: "fr", lanName: "Français", url: "https://cacdn.../subtitle/X.srt?..." }, ...
     *  ]
     *  ```
     */
    private suspend fun fetchFrenchCaptions(subjectId: String, resourceId: String, episode: Int): List<String> {
        val resp = apiGet(EXT_CAPTIONS_PATH, mapOf(
            "subjectId" to subjectId,
            "resourceId" to resourceId,
            "episode" to "$episode",
        )) ?: return emptyList()
        val captions = resp.optJSONObject("data")?.optJSONArray("extCaptions") ?: return emptyList()
        val frUrls = mutableListOf<String>()
        for (i in 0 until captions.length()) {
            val c = captions.optJSONObject(i) ?: continue
            val lan = c.optString("lan").lowercase()
            val lanName = c.optString("lanName").lowercase()
            if (lan == "fr" || lan == "fre" || lan == "fra" ||
                lanName.contains("fran") || lanName.contains("french")) {
                val url = c.optString("url").takeIf { it.isNotBlank() } ?: continue
                frUrls += url
            }
        }
        return frUrls
    }

    /** Extrait le subjectId depuis l'id d'un Video.Server Cloudstream natif.
     *  Format : `cs_{prefix}_{subjectId}_{...}` → retourne subjectId. */
    private fun parseSidFromCsId(id: String): String? {
        if (!id.startsWith("cs_")) return null
        val parts = id.split("_")
        // cs_rd_SID_..., cs_resource_SID_..., cs_playinfo_SID_...
        return parts.getOrNull(2)?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
    }

    /** Construit un master HLS playlist avec les MP4/HLS comme variants.
     *  Encodé en data URI pour pas avoir besoin de proxy HTTP local.
     *  ExoPlayer parse le master, expose chaque variant comme une qualité dans
     *  son onglet Qualité (Auto + 360p/480p/720p/1080p selon ce qu'on lui passe).
     *
     *  Pour les MP4 : on les wrappe dans un sub-playlist VOD single-segment
     *  (data URI lui-même) avec EXT-X-MAP-style. ExoPlayer Media3 supporte
     *  les MP4 progressifs en sub-playlists HLS.
     *
     *  Pour les HLS m3u8 : on les laisse tels quels comme variant URL.
     */
    private fun buildMasterM3u8Uri(streams: List<Triple<Int, String, Int>>): String {
        val resMap = mapOf(
            240 to (426 to 240), 360 to (640 to 360), 480 to (854 to 480),
            720 to (1280 to 720), 1080 to (1920 to 1080),
            1440 to (2560 to 1440), 2160 to (3840 to 2160),
        )
        val bandwidthMap = mapOf(
            240 to 400_000, 360 to 800_000, 480 to 1_500_000,
            720 to 4_000_000, 1080 to 8_000_000,
            1440 to 16_000_000, 2160 to 35_000_000,
        )
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n")
        for ((res, url, linkType) in streams) {
            val (w, h) = resMap[res] ?: (1280 to 720)
            val bw = bandwidthMap[res] ?: 4_000_000
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=$bw,RESOLUTION=${w}x${h}\n")
            if (linkType == 1) {
                // HLS m3u8 direct → variant URL inline
                sb.append("$url\n")
            } else {
                // MP4 progressif → wrappé dans un sub-playlist VOD single-segment
                val subPlaylist = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-TARGETDURATION:9999
#EXTINF:9999.0,
$url
#EXT-X-ENDLIST"""
                val subDataUri = "data:application/vnd.apple.mpegurl;base64," +
                    android.util.Base64.encodeToString(
                        subPlaylist.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                sb.append("$subDataUri\n")
            }
        }
        return "data:application/vnd.apple.mpegurl;base64," +
            android.util.Base64.encodeToString(
                sb.toString().toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
    }

    /** FAST PATH : exploite `data.resourceDetectors[].resolutionList[]` retourné
     *  par `/subject-api/get`. Évite les 4-5 appels paginés `/resource`.
     *  Prend la JSON déjà fetchée (partagée avec [computeLangSuffix]) pour ne
     *  faire qu'1 seul appel /get total. Filtre par `se`/`ep` pour l'épisode.
     *
     *  Format retourné par MovieBox+ :
     *  ```
     *  resourceDetectors: [{
     *    resolutionList: [
     *      { resolution: 720, resourceLink: "...", linkType: 2, se: 0, ep: 0 }, ...
     *    ]
     *  }]
     *  ```
     *  `linkType: 2` = MP4 direct, `linkType: 1` = HLS m3u8.
     *  CDN observé : `bcdn.hakunaymatata.com` (rapide, sans pubs).
     */
    private fun parseResourceDetectorsFromJson(resp: JSONObject, subjectId: String, se: Int, ep: Int): List<Video.Server> {
        val detectors = resp.optJSONObject("data")?.optJSONArray("resourceDetectors") ?: return emptyList()

        val candidates = mutableListOf<Triple<Int, String, Int>>()  // (resolution, url, linkType)
        for (i in 0 until detectors.length()) {
            val detector = detectors.optJSONObject(i) ?: continue
            val resolutionList = detector.optJSONArray("resolutionList") ?: continue
            for (j in 0 until resolutionList.length()) {
                val item = resolutionList.optJSONObject(j) ?: continue
                val itemSe = item.optInt("se", 0)
                val itemEp = item.optInt("ep", 0)
                if (itemSe != se || itemEp != ep) continue
                val link = item.optString("resourceLink").takeIf { it.isNotBlank() } ?: continue
                val res = item.optInt("resolution", 0)
                val linkType = item.optInt("linkType", 2)
                candidates += Triple(res, link, linkType)
            }
        }

        val deduped = candidates.distinctBy { it.second }.sortedByDescending { it.first }

        return deduped.mapIndexed { idx, t ->
            val (res, url, linkType) = t
            val format = if (linkType == 1) "HLS" else "MP4"
            Video.Server(
                id = "cs_rd_${subjectId}_${se}_${ep}_${res}_$idx",
                name = "Cloudstream [${res}p $format]",
                src = url,
            )
        }
    }

    /** Détecte la langue audio/sous-titres dispo pour un sujet MovieBox+ et
     *  retourne le suffix à coller au name d'un Video.Server pour que
     *  [ExtractorRanker.computeLanguagePenalty] applique la bonne pénalité.
     *
     *  Logique :
     *   - `data.dubs[].lanCode` contient "fr" → `[VF]` (score full)
     *   - sinon, `frenchCaptionsAvailable=true` → `[VF auto]` (sub FR auto-attaché)
     *     → ranker matche "vf" mais pas "vostfr" → score full aussi
     *   - sinon, `data.subtitles` contient FR mais pas auto-attaché → `[VOSTFR]`
     *     (-100 pénalité pour redescendre)
     *   - sinon → `[VO]` (-150 pénalité)
     *   - dubs vide ET subs vides → "" (no marker, ranker assume VF)
     */
    private fun computeLangSuffix(getResp: JSONObject?, frenchCaptionsAvailable: Boolean = false): String {
        if (getResp == null) return ""
        val data = getResp.optJSONObject("data") ?: return ""

        // 1. Check dubs[] FR → vrai VF
        val dubs = data.optJSONArray("dubs")
        if (dubs != null) {
            for (i in 0 until dubs.length()) {
                val dub = dubs.optJSONObject(i) ?: continue
                val code = dub.optString("lanCode").lowercase()
                val name = dub.optString("lanName").lowercase()
                if (code == "fr" || code == "fre" || code == "fra" ||
                    name.contains("french") || name.contains("français") || name.contains("francais")) {
                    return " [VF]"
                }
            }
        }

        // 2. Captions FR auto-attachées → équivalent VF côté UX, pas de pénalité
        if (frenchCaptionsAvailable) return " [VF auto]"

        // 3. Check subtitles (string CSV ou array) — fallback si captions API a foiré
        val subtitlesRaw = data.opt("subtitles")
        val subtitlesStr = when (subtitlesRaw) {
            is String -> subtitlesRaw
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until subtitlesRaw.length()) {
                    if (i > 0) sb.append(",")
                    sb.append(subtitlesRaw.optString(i))
                }
                sb.toString()
            }
            else -> ""
        }.lowercase()
        if (subtitlesStr.contains("français") || subtitlesStr.contains("francais") ||
            subtitlesStr.contains("french") ||
            subtitlesStr.split(",").map { it.trim() }.any { it == "fr" || it == "fre" || it == "fra" }) {
            return " [VOSTFR]"
        }

        // 3. Edge case : metadata vide (dubs=[] ET subtitles="") = serveur n'a pas
        //    indexé la langue. Ne PAS marquer [VO] qui pénaliserait à tort un film
        //    FR (cas observé : Les Tuche 2, OSS 117). Sans suffix, ranker assume VF.
        val dubsEmpty = dubs == null || dubs.length() == 0
        val subsEmpty = subtitlesStr.isBlank()
        if (dubsEmpty && subsEmpty) return ""

        // 4. Au moins une dub/sub existe mais aucune n'est FR → vraiment VO
        return " [VO]"
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
        // 2026-05-07 : Backup Movix #3 → délégue à MovixProvider qui sait extraire
        // les embeds via ses extractors (filemoon, vidoza, doodstream, etc.)
        if (server.id.startsWith("movix_backup__")) {
            val original = server.copy(id = server.id.removePrefix("movix_backup__"))
            return try {
                MovixProvider.getVideo(original)
            } catch (e: Exception) {
                Log.w(TAG, "Movix getVideo failed for ${original.src}: ${e.message}")
                Video(source = original.src)
            }
        }
        // 2026-05-27 : Wiflix/FrenchStream direct scraping → les URLs embed
        // doivent passer par Extractor.extract() pour décoder le m3u8 réel
        // (VidSonic hex, Voe decrypt, etc.). Sans ça, ExoPlayer reçoit la page
        // HTML embed au lieu du stream → crash immédiat → rouge à tort.
        if (server.id.startsWith("wiflix_direct__") || server.id.startsWith("fs_direct__")) {
            return com.streamflixreborn.streamflix.extractors.Extractor.extract(server.src, server)
        }
        // 2026-06-03 (user "tous les serveurs ne fonctionnent pas comme la
        //   dernière fois sur movix") : pareil pour les serveurs Coflix
        //   (id = "coflix_N", source = embed hoster Lulustream/Voe/Minochinos/
        //   etc.). Sans Extractor.extract, ExoPlayer recevait la page HTML
        //   embed → UnrecognizedInputFormatException sur minochinos.com/embed/
        //   xxx.html et lulustream.com/e/xxx (vus dans les logs).
        if (server.id.startsWith("coflix_")) {
            return com.streamflixreborn.streamflix.extractors.Extractor.extract(server.src, server)
        }
        // MovieBox+ direct (MP4 1080p max). Master m3u8 multi-quality testé via
        // data URI imbriqué → ExoPlayer Media3 le rejette. Pour vraie multi-
        // qualité dans l'onglet Qualité, faudrait un proxy HTTP local (NanoHTTPD
        // ou OkHttp interceptor) qui sert master + sub-playlists. Pas prio.
        // En attendant : 1 server "Cloudstream" pointant sur la max résolution.
        val sid = parseSidFromCsId(server.id)
        val source = server.src

        val frenchSubs = sid?.let { frenchCaptionsCache[it] } ?: emptyList()
        val subtitles = frenchSubs.mapIndexed { idx, url ->
            Video.Subtitle(
                label = if (idx == 0) "Français" else "Français (${idx + 1})",
                file = url,
                default = idx == 0,
                initialDefault = idx == 0,
            )
        }
        if (subtitles.isNotEmpty()) {
            Log.d(TAG, "getVideo ${server.id} : +${subtitles.size} sous-titre(s) FR auto")
        }
        return Video(
            source = source,
            subtitles = subtitles,
            headers = mutableMapOf(
                "Referer" to "https://moviebox.ph/",
                "User-Agent" to USER_AGENT,
            ),
        )
    }

}
