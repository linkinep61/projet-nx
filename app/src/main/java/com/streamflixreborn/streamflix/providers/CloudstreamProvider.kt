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
import okhttp3.CacheControl
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
    private const val SEARCH_PATH = "/wefeed-mobile-bff/subject-api/search/v2"
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
            // 2026-07-09 : MovieBox+ API renvoie HTTP 407 pour les erreurs d'auth
            // (au lieu du standard 441 "miss token"). OkHttp lance une ProtocolException
            // pour 407 quand il n'y a pas de proxy configuré → on ne reçoit JAMAIS la
            // réponse. Ce network interceptor convertit 407 → 477 AVANT que le
            // RetryAndFollowUpInterceptor interne ne lance l'exception, permettant à
            // notre code apiPost/apiGet de le traiter normalement.
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 407) {
                    response.newBuilder().code(477).message("Auth Required (remapped from 407)").build()
                } else response
            }
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

    // 2026-07-09 : Bearer JWT pour les POST. L'API MovieBox+ exige un token
    //   sur TOUS les POST (search, etc.) — erreur 441 "miss token" sans lui.
    //   Le token est renvoyé dans le header de réponse `x-user` de n'importe
    //   quel GET réussi (JSON: {"token":"eyJ...","uid":...}).
    //   On le cache et on le rafraîchit automatiquement en cas de 441.
    @Volatile private var bearerToken: String? = null

    /** Extrait le JWT du header `x-user` de la réponse, si présent. */
    private fun captureBearer(resp: okhttp3.Response) {
        val xUser = resp.header("x-user") ?: return
        try {
            val json = JSONObject(xUser)
            val token = json.optString("token", "")
            if (token.isNotBlank()) {
                bearerToken = token
                Log.d(TAG, "Bearer capturé (${token.length} chars)")
            }
        } catch (_: Exception) { /* pas de JSON valide, ignorer */ }
    }

    /** Assure qu'on a un Bearer token. Fait un GET léger si nécessaire. */
    private suspend fun ensureBearer(): String? {
        bearerToken?.let { return it }
        // Pas de token en cache → faire un GET /tab-operating pour en récupérer un
        Log.d(TAG, "Pas de Bearer en cache, fetch via GET...")
        for (host in orderedHosts()) {
            val url = "$host/wefeed-mobile-bff/tab-operating?tab=home"
            try {
                val req = Request.Builder().url(url)
                    .cacheControl(API_NO_CACHE)
                    .apply {
                        signedHeaders("GET", url).forEach { (k, v) -> header(k, v) }
                    }.build()
                val resp = httpClient.newCall(req).execute()
                resp.use {
                    // Toujours tenter de capturer le Bearer, même sur erreur
                    // (l'API peut inclure x-user dans les headers de réponse d'erreur)
                    captureBearer(it)
                    val respCode = it.code
                    if (it.isSuccessful || respCode == 477) {
                        lastGoodHost = host
                        it.body?.string() // drain body
                        bearerToken?.let { tok ->
                            Log.d(TAG, "ensureBearer via $host → OK (HTTP $respCode)")
                            return tok
                        }
                        Log.d(TAG, "ensureBearer $host → HTTP $respCode but no bearer in x-user")
                    } else {
                        Log.d(TAG, "ensureBearer $host → HTTP $respCode")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "ensureBearer $host error: ${e.message}")
            }
        }
        Log.w(TAG, "Impossible de récupérer un Bearer token")
        return null
    }

    /** Retourne la liste des hosts dans l'ordre à essayer : last-good d'abord,
     *  puis le reste du pool. */
    private fun orderedHosts(): List<String> {
        val good = lastGoodHost
        return if (good != null && HOST_POOL.contains(good)) {
            listOf(good) + HOST_POOL.filterNot { it == good }
        } else HOST_POOL
    }

    // 2026-07-07 : CacheControl pour TOUS les appels API MovieBox+.
    //   Les requêtes sont signées HMAC avec un timestamp → chaque appel a une
    //   signature UNIQUE, mais l'URL (= clé de cache OkHttp) reste la MÊME.
    //   Sans noCache+noStore, OkHttp cache la réponse sur disque (extractor-http/)
    //   et sert la réponse CACHÉE aux appels suivants → si c'était une erreur ou
    //   une réponse vide, TOUS les serveurs sont bloqués indéfiniment.
    //   Le user devait effacer les données de l'app pour débloquer.
    private val API_NO_CACHE = CacheControl.Builder().noCache().noStore().build()

    private suspend fun apiGet(path: String, params: Map<String, String> = emptyMap()): JSONObject? = withContext(Dispatchers.IO) {
        val query = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val pathWithQuery = if (query.isNotBlank()) "$path?$query" else path
        // 2026-07-09 : Bearer JWT maintenant obligatoire pour TOUS les endpoints
        //   (GET /resource, /get, /play-info retournent 441 sans).
        val token = ensureBearer()
        // 2026-07-13 : détection « provider cassé » Cloudstream. On ne signale QUE si TOUS les
        //   mirrors du pool échouent au niveau RÉSEAU (dns/connect/ssl = domaine aoneroom mort),
        //   pas sur un 403/429 (le domaine répond alors). anyHostResponded=false + erreur réseau.
        var anyHostResponded = false
        var lastNetError: Throwable? = null
        for (host in orderedHosts()) {
            val url = "$host$pathWithQuery"
            try {
                val req = Request.Builder().url(url)
                    .cacheControl(API_NO_CACHE)
                    .apply {
                        signedHeaders("GET", url).forEach { (k, v) -> header(k, v) }
                        if (token != null) header("Authorization", "Bearer $token")
                    }.build()
                val resp = httpClient.newCall(req).execute()
                anyHostResponded = true
                resp.use {
                    val code = it.code
                    if (code == 441 || code == 477) {
                        // Token manquant/expiré → refresh et retry UNE fois
                        Log.d(TAG, "apiGet $host$pathWithQuery → $code, refresh Bearer")
                        bearerToken = null
                        val newToken = ensureBearer()
                        if (newToken != null) {
                            val req2 = Request.Builder().url(url)
                                .cacheControl(API_NO_CACHE)
                                .apply {
                                    signedHeaders("GET", url).forEach { (k, v) -> header(k, v) }
                                    header("Authorization", "Bearer $newToken")
                                }.build()
                            val resp2 = httpClient.newCall(req2).execute()
                            resp2.use { r2 ->
                                if (r2.isSuccessful) {
                                    captureBearer(r2)
                                    val body2 = r2.body?.string() ?: return@withContext null
                                    Log.d(TAG, "apiGet ${pathWithQuery.take(60)} → ${r2.code} size=${body2.length} (retry OK)")
                                    lastGoodHost = host
                                    return@withContext JSONObject(body2)
                                } else {
                                    Log.d(TAG, "apiGet $host$pathWithQuery retry → ${r2.code}")
                                }
                            }
                        }
                        return@use
                    }
                    if (code in setOf(403, 429, 500, 502, 503, 504)) {
                        Log.d(TAG, "apiGet $host$pathWithQuery returned $code, retry next")
                        return@use
                    }
                    if (!it.isSuccessful) {
                        Log.d(TAG, "apiGet $host$pathWithQuery NOT OK (code=$code), giving up")
                        return@withContext null
                    }
                    captureBearer(it)
                    val body = it.body?.string() ?: return@withContext null
                    Log.d(TAG, "apiGet ${pathWithQuery.take(60)} → ${code} size=${body.length} (head: ${body.take(150)})")
                    lastGoodHost = host
                    return@withContext JSONObject(body)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Host $host error: ${e.message}, retry next")
                lastNetError = e
            }
        }
        // Tous les mirrors épuisés. Si AUCUN n'a répondu (que des erreurs réseau) → domaine mort.
        if (!anyHostResponded && lastNetError != null) {
            runCatching {
                com.streamflixreborn.streamflix.utils.BrokenSourceReporter.maybeReportProvider(
                    providerName = "Cloudstream", url = "https://aoneroom.com", error = lastNetError,
                )
            }
        }
        null
    }

    private suspend fun apiPost(path: String, jsonBody: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val bodyStr = jsonBody.toString()
        // 2026-07-09 : Bearer JWT obligatoire pour les POST (441 "miss token" sans).
        val token = ensureBearer()
        if (token == null) {
            Log.w(TAG, "apiPost $path : pas de Bearer, abandon")
            return@withContext null
        }

        for (host in orderedHosts()) {
            val url = "$host$path"
            try {
                // 2026-07-08 : Content-Type "application/json" pour POST.
                // Le header HTTP utilise "application/json" (comme CNCVerse).
                val postContentType = "application/json"
                val req = Request.Builder().url(url)
                    .cacheControl(API_NO_CACHE)
                    .post(bodyStr.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
                    .apply {
                        signedHeaders("POST", url, bodyStr, contentType = postContentType)
                            .forEach { (k, v) -> header(k, v) }
                        header("Authorization", "Bearer $token")
                    }.build()
                val resp = httpClient.newCall(req).execute()
                resp.use {
                    val code = it.code
                    if (code == 441 || code == 477) {
                        // 441 = "miss token" (original), 477 = remapped 407 (auth error)
                        val errBody477 = it.body?.string()?.take(500) ?: "(no body)"
                        Log.d(TAG, "apiPost $host$path → $code, body=$errBody477")
                        Log.d(TAG, "apiPost $host$path → $code, refresh Bearer")
                        bearerToken = null
                        val newToken = ensureBearer()
                        if (newToken != null) {
                            // Retry UNE fois avec le nouveau token
                            val req2 = Request.Builder().url(url)
                                .cacheControl(API_NO_CACHE)
                                .post(bodyStr.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
                                .apply {
                                    signedHeaders("POST", url, bodyStr, contentType = postContentType)
                                        .forEach { (k, v) -> header(k, v) }
                                    header("Authorization", "Bearer $newToken")
                                }.build()
                            val resp2 = httpClient.newCall(req2).execute()
                            resp2.use { r2 ->
                                if (r2.isSuccessful) {
                                    captureBearer(r2)
                                    val body2 = r2.body?.string() ?: return@withContext null
                                    Log.d(TAG, "apiPost ${path.take(60)} → ${r2.code} size=${body2.length} (retry OK)")
                                    lastGoodHost = host
                                    return@withContext JSONObject(body2)
                                } else {
                                    val retryBody = r2.body?.string()?.take(500) ?: "(no body)"
                                    Log.w(TAG, "apiPost $host$path retry → ${r2.code} body=$retryBody")
                                }
                            }
                        }
                        return@use // essayer host suivant
                    }
                    if (code in setOf(403, 429, 500, 502, 503, 504)) {
                        Log.d(TAG, "apiPost $host$path returned $code, retry next")
                        return@use
                    }
                    if (!it.isSuccessful) {
                        val errBody = it.body?.string()?.take(300) ?: "(no body)"
                        Log.w(TAG, "apiPost $host$path NOT OK (code=$code) body=$errBody")
                        return@withContext null
                    }
                    captureBearer(it)
                    val body = it.body?.string() ?: return@withContext null
                    Log.d(TAG, "apiPost ${path.take(60)} → $code size=${body.length}")
                    lastGoodHost = host
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

        // 2026-07-08 : endpoint v2 (v1 deprecated). page=1 (v2 est 1-indexed).
        val resp = apiPost(SEARCH_PATH, JSONObject().apply {
            put("keyword", cleanQuery)
            put("page", 1)
            put("perPage", 20)
        })
        if (resp == null) {
            Log.w(TAG, "findSubjectId('$cleanQuery') : apiPost retourné null (search API down)")
            return null
        }

        // v2 response : data.results[].subjects[] (au lieu de data.items[] en v1)
        val data = resp.optJSONObject("data")
        val items: MutableList<JSONObject> = mutableListOf()
        // Essayer le format v2 d'abord
        val results = data?.optJSONArray("results")
        if (results != null) {
            for (r in 0 until results.length()) {
                val subjects = results.optJSONObject(r)?.optJSONArray("subjects") ?: continue
                for (s in 0 until subjects.length()) {
                    subjects.optJSONObject(s)?.let { items.add(it) }
                }
            }
        }
        // Fallback format v1 (data.items[]) au cas où
        if (items.isEmpty()) {
            val v1Items = data?.optJSONArray("items")
            if (v1Items != null) {
                for (i in 0 until v1Items.length()) {
                    v1Items.optJSONObject(i)?.let { items.add(it) }
                }
            }
        }
        if (items.isEmpty()) {
            Log.w(TAG, "findSubjectId('$cleanQuery') : 0 items retournés par MovieBox+ (v2)")
            return null
        }

        data class Candidate(
            val sid: String, val cleanTitle: String, val normTitle: String,
            val year: Int, val subjectType: Int,
        )
        val candidates = mutableListOf<Candidate>()
        for (s in items) {
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
            // 2026-07-07 : seuil titleScore ≤ 3 — rejeter les candidats sans aucun match
            // titre (titleScore=10 = ni exact, ni startsWith, ni contains). Sans ce seuil,
            // un film au titre court pouvait matcher n'importe quoi dans la bonne tranche d'année.
            if (best != null && titleScore(best) <= 3) {
                tmdbToSubjectIdCache[cacheKey] = best.sid
                Log.d(TAG, "findSubjectId('$cleanQuery' year=$year) → fallback best=${best.cleanTitle} (${best.year}) sid=${best.sid} (titleScore=${titleScore(best)}, yearDelta=${kotlin.math.abs(best.year - year)})")
                return best.sid
            }
            if (best != null) {
                Log.d(TAG, "findSubjectId('$cleanQuery' year=$year) → rejeté fallback best=${best.cleanTitle} (titleScore=${titleScore(best)} > 3, pas de match titre)")
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

    /** 2026-07-04 : vide TOUS les caches en mémoire (serveurs, sous-titres, mapping
     *  TMDB, signal FR). Appelé par ProviderCacheRefresh. */
    fun clearCaches() {
        frenchCaptionsCache.clear()
        cloudstreamStreamsCache.clear()
        tmdbToSubjectIdCache.clear()
        frSignalCache.clear()
        frAvailabilityCache.clear()
    }

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
    // 2026-07-11 (user « supprime les dessins animés, on a déjà des providers anime »):
    //   exclut le genre TMDB Animation (16). 2 pages → ~15-20 items/catégorie après filtre.
    private fun TMDb3.Movie.notAnim(): Boolean = 16 !in genresIds
    private fun TMDb3.Tv.notAnim(): Boolean = 16 !in genresIds

    private suspend fun discoverMoviesOnProvider(
        watchProviderId: Int, mapMovie: (TMDb3.Movie) -> Movie,
    ): List<Movie> = runCatching { coroutineScope {
        val p1 = async { TMDb3.Discover.movie(
            page = 1, language = language, region = "FR", watchRegion = "FR",
            sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
            withWatchProviders = TMDb3.Params.WithBuilder(watchProviderId),
            withOriginalLanguage = csOriginalLanguageBuilder(),
        ).results }
        val p2 = async { runCatching { TMDb3.Discover.movie(
            page = 2, language = language, region = "FR", watchRegion = "FR",
            sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
            withWatchProviders = TMDb3.Params.WithBuilder(watchProviderId),
            withOriginalLanguage = csOriginalLanguageBuilder(),
        ).results }.getOrDefault(emptyList()) }
        (p1.await() + p2.await()).distinctBy { it.id }
            .filter { it.posterPath != null && it.notAnim() }.take(20).map(mapMovie)
    } }.getOrDefault(emptyList())

    private suspend fun discoverTvOnProvider(
        watchProviderId: Int, mapTv: (TMDb3.Tv) -> TvShow,
    ): List<TvShow> = runCatching { coroutineScope {
        val p1 = async { TMDb3.Discover.tv(
            page = 1, language = language, watchRegion = "FR",
            sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
            withWatchProviders = TMDb3.Params.WithBuilder(watchProviderId),
            withOriginalLanguage = csOriginalLanguageBuilder(),
        ).results }
        val p2 = async { runCatching { TMDb3.Discover.tv(
            page = 2, language = language, watchRegion = "FR",
            sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
            withWatchProviders = TMDb3.Params.WithBuilder(watchProviderId),
            withOriginalLanguage = csOriginalLanguageBuilder(),
        ).results }.getOrDefault(emptyList()) }
        (p1.await() + p2.await()).distinctBy { it.id }
            .filter { it.posterPath != null && it.notAnim() }.take(20).map(mapTv)
    } }.getOrDefault(emptyList())

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
        // 2026-06-13 (user "le home Cloudstream et Movix ne se met pas à jour
        //   j'ai l'impression de voir tout le temps la même chose") : on
        //   randomise la page TMDB entre 1 et 5 pour chaque section. Comme ça
        //   à chaque expiration du cache (5 min), de nouveaux titres apparaissent
        //   au lieu des mêmes top 20 populaires. On reste dans les 100 premiers
        //   les plus populaires, donc pas de titres confidentiels.
        //   Page indépendante PAR SECTION pour pas que tout vienne du même
        //   batch (= sinon trending et popular afficheraient les mêmes items).
        val pageTrending = (1..5).random()
        val pagePopularMovies = (1..5).random()
        val pageTopMovies = (1..5).random()
        val pagePopularTv = (1..5).random()
        val pageTopTv = (1..5).random()
        val trendingD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = pageTrending, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                    withOriginalLanguage = csOriginalLanguageBuilder(),
                    voteCount = TMDb3.Params.Range(50, null),
                ).results
            }.getOrDefault(emptyList())
        }
        val popularMoviesD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = pagePopularMovies, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                    withOriginalLanguage = csOriginalLanguageBuilder(),
                ).results
            }.getOrDefault(emptyList())
        }
        val topMoviesD = async {
            runCatching {
                TMDb3.Discover.movie(
                    page = pageTopMovies, language = language, region = "FR",
                    sortBy = TMDb3.Params.SortBy.Movie.VOTE_AVERAGE_DESC,
                    withOriginalLanguage = csOriginalLanguageBuilder(),
                    voteCount = TMDb3.Params.Range(200, null),
                ).results
            }.getOrDefault(emptyList())
        }
        val popularTvD = async {
            runCatching {
                TMDb3.Discover.tv(
                    page = pagePopularTv, language = language,
                    sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                    withOriginalLanguage = csOriginalLanguageBuilder(),
                ).results
            }.getOrDefault(emptyList())
        }
        val topTvD = async {
            runCatching {
                TMDb3.Discover.tv(
                    page = pageTopTv, language = language,
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
            .filter { it.posterPath != null && it.notAnim() }
            .take(10).mapNotNull { it.toAppItem() }
        if (featured.isNotEmpty()) sections.add(Category(name = Category.FEATURED, list = featured))

        // Nouveautés FR (films + séries séparés, pas de section mixte doublon)
        val newMovies = newMoviesD.await().filter { it.posterPath != null && it.notAnim() }.map(mapMovie)
        val newTv = newTvD.await().filter { it.posterPath != null && it.notAnim() }.map(mapTv)
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

        // 3) Catégories de fond (toutes plateformes) — anime exclu
        val popularTv = popularTvD.await().filter { it.posterPath != null && it.notAnim() }.map(mapTv)
        if (popularTv.isNotEmpty()) sections.add(Category(name = "Séries populaires", list = popularTv))

        val topTv = topTvD.await().filter { it.posterPath != null && it.notAnim() }.map(mapTv)
        if (topTv.isNotEmpty()) sections.add(Category(name = "Séries les mieux notées", list = topTv))

        val popularMovies = popularMoviesD.await().filter { it.posterPath != null && it.notAnim() }.map(mapMovie)
        if (popularMovies.isNotEmpty()) sections.add(Category(name = "Films populaires", list = popularMovies))

        val topMovies = topMoviesD.await().filter { it.posterPath != null && it.notAnim() }.map(mapMovie)
        if (topMovies.isNotEmpty()) sections.add(Category(name = "Films les mieux notés", list = topMovies))

        Log.d(TAG, "getHome Cloudstream : ${sections.size} sections — featured=${featured.size}, nouveauxFilms=${newMovies.size}, nouvellesSeries=${newTv.size}")
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
                    // 2026-07-11 (user « pareil sur Cloudstream ») : aoneroom indexe souvent les
                    //   séries (dramas coréens, anime…) sous un AUTRE nom que le titre fr TMDB
                    //   (ex. Navillera → « Navillera - Like a Butterfly » / « 나빌레라 »). On essaie
                    //   donc TOUS les titres (fr + original + alternative_titles) jusqu'à un match,
                    //   au lieu du seul titre fr → récupère des serveurs là où on avait 0.
                    val titles = linkedSetOf(queryTitle)
                    tmdbId?.toIntOrNull()?.let { tid ->
                        runCatching {
                            if (videoType is Video.Type.Movie) {
                                val d = TMDb3.Movies.details(movieId = tid, language = language,
                                    appendToResponse = listOf(TMDb3.Params.AppendToResponse.Movie.ALTERNATIVE_TITLES))
                                d.originalTitle.takeIf { it.isNotBlank() }?.let { titles.add(it) }
                                d.alternativeTitles?.all()?.mapNotNull { it.title?.takeIf { t -> t.isNotBlank() } }?.forEach { titles.add(it) }
                            } else {
                                val d = TMDb3.TvSeries.details(seriesId = tid, language = language,
                                    appendToResponse = listOf(TMDb3.Params.AppendToResponse.Tv.ALTERNATIVE_TITLES))
                                d.originalName.takeIf { it.isNotBlank() }?.let { titles.add(it) }
                                d.alternativeTitles?.all()?.mapNotNull { it.title?.takeIf { t -> t.isNotBlank() } }?.forEach { titles.add(it) }
                            }
                        }
                    }
                    var matched = queryTitle
                    for (t in titles) {
                        subjectId = findSubjectId(t, year)
                        if (!subjectId.isNullOrBlank()) { matched = t; break }
                    }
                    if (subjectId.isNullOrBlank()) {
                        Log.d(TAG, "getServers : pas de subjectId Cloudstream pour '$queryTitle' ($year) [${titles.size} titres testés] — fallback Nakios si TMDB id dispo")
                    } else {
                        Log.d(TAG, "getServers TMDB→Cloudstream : '$matched' ($year) → sid=$subjectId, se=$se ep=$ep")
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

        // 2026-07-08 : pipeline /resource DÉSACTIVÉ — bcdn.hakunaymatata.com
        // renvoie 429 systématiquement (rate-limit CDN). Les 4 requêtes /resource
        // parallèles gaspillaient du temps réseau pour rien. SEUL /play-info
        // (hcdn3) est utilisé maintenant → lecture immédiate sans 429.
        // NOTE : /resource peut être réactivé si bcdn remarche un jour.
        if (!subjectId.isNullOrBlank()) {
            val sid: String = subjectId!!
            run {
                val params = mutableMapOf("subjectId" to sid)
                if (se > 0) params["se"] = "$se"
                if (ep > 0) params["ep"] = "$ep"
                val resp = apiGet(PLAY_INFO_PATH, params)
                val data = resp?.optJSONObject("data")
                val streamArr = data?.optJSONArray("streams")
                if (streamArr != null) {
                    val existingResolutions = servers.map { s ->
                        s.name.let { n ->
                            val m = Regex("(\\d+)p").find(n)
                            m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        }
                    }.toSet()
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
                                name = "Cloudstream [${t.first}p]",
                                src = t.second,
                            )
                        )
                    }
                    if (list.isNotEmpty()) {
                        Log.d(TAG, "getServers $id : +${list.size} streams play-info (hcdn)")
                    }
                }
            }
            Log.d(TAG, "getServers $id : MovieBox+ → ${servers.size} streams")
        }

        // 2026-07-08 : resourceDetectors (bcdn) DÉSACTIVÉS — renvoient 429.
        // On attend quand même le Deferred pour éviter un leak de coroutine.
        resourceDetectorsD.await()  // drain sans utiliser

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
                val match = Regex("\\[(\\d+)p(?:\\s*(MP4|HLS))?]").find(srv.name) ?: return@mapNotNull null
                val res = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val format = match.groupValues.getOrNull(2)?.ifEmpty { null } ?: "MP4"
                val linkType = if (format == "HLS") 1 else 2
                Triple(res, srv.src, linkType)
            }.distinctBy { it.first }.sortedByDescending { it.first }
            if (streams.isNotEmpty()) {
                cloudstreamStreamsCache[subjectId!!] = streams
                Log.d(TAG, "getServers $id : ${streams.size} qualité(s) Cloudstream cachées : ${streams.map { it.first }.joinToString()}p")
            }
        }
        // 2026-07-08 : bcdn.hakunaymatata.com renvoie 429 systématiquement.
        // On SUPPRIME tous les serveurs bcdn (cs_resource_ + cs_rd_) et on ne
        // garde QUE les play-info (hcdn3) qui fonctionnent. Top 2 résolutions.
        run {
            val bcdnIds = servers.filter {
                it.id.startsWith("cs_resource_") || it.id.startsWith("cs_rd_")
            }.map { it.id }.toSet()
            if (bcdnIds.isNotEmpty()) {
                servers.removeAll { it.id in bcdnIds }
                Log.d(TAG, "getServers $id : ${bcdnIds.size} bcdn supprimés (429)")
            }
        }
        val csServersDedup = servers.filter { it.id.startsWith("cs_") }
        if (csServersDedup.size > 2) {
            val sorted = csServersDedup.sortedByDescending {
                Regex("\\[(\\d+)p").find(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            val seenRes = mutableSetOf<Int>()
            val kept = sorted.filter { srv ->
                val r = Regex("\\[(\\d+)p").find(srv.name)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                seenRes.add(r)
            }.take(2)
            val others = servers.filter { !it.id.startsWith("cs_") }
            servers.clear()
            servers.addAll(kept)
            servers.addAll(others)
            Log.d(TAG, "getServers $id : ${kept.size} Cloudstream gardés (${kept.map { it.name }})")
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
                // 2026-06-13 (user "patch FS Voe HD pollue tous les providers
                //   avec backup, ca doit etre la meme chose partout") :
                //   on filtre les sources `fstream-*` ici aussi (= meme que le
                //   filtre central dans MovixProvider.getServersAsBackup). Ces
                //   sources sont labellisees "FS · X (VF - HD)" et jouent
                //   frequemment du mauvais contenu (= URLs d'autres shows).
                raw
                    .filter { !it.id.startsWith("fstream-") }
                    .map { srv -> srv.copy(id = "movix_backup__${srv.id}") }
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
        val nativeCs = nativeD.await()
        // 2026-07-13 : casse silencieuse — Cloudstream natif (MovieBox+) répond mais 0 source
        //   de façon répétée (structure changée). Titre recherché joint pour aiguiller.
        runCatching {
            val t = when (videoType) {
                is Video.Type.Movie -> videoType.title
                is Video.Type.Episode -> "${videoType.tvShow.title} S${videoType.season.number}E${videoType.number}"
                else -> id
            }
            com.streamflixreborn.streamflix.utils.BrokenSourceReporter.noteProviderResult(
                "Cloudstream (natif)", found = nativeCs.isNotEmpty(), searchedTitle = t,
            )
        }
        out += nativeCs
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
        // 2026-06-13 : track si un batch alive a déjà été émis. Si oui, on
        //   peut émettre les batches all-dead suivants en fin de liste sans
        //   risque de pourrir l'auto-play (déjà fixé sur un alive). Sinon
        //   on skip pour ne pas que l'auto-play tombe sur un mort certain.
        var hasEmittedAlive = false
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
                // 2026-06-13 (user "tu as pas mis un filtre qui masque les
                //   serveurs sans faire exprès je suis sur un episode et pas
                //   de wiflix + plein d'autres serveurs ne sont pas présents") :
                //   la protection hasEmittedAlive masquait trop. Les batches
                //   all-dead arrivant AVANT un alive (= Wiflix sur des
                //   épisodes où toutes ses sources pointent vers VOE déprio)
                //   étaient skippés définitivement → invisibles. Maintenant
                //   on émet TOUJOURS, alive en tête + dead en fin de liste.
                //   Le risque "auto-play sur dead" est déjà mitigé par
                //   startAwaitMoreServers (attend les backups si tous foirent).
                if (alive.isNotEmpty() || dead.isNotEmpty()) {
                    hasEmittedAlive = hasEmittedAlive || alive.isNotEmpty()
                    val emitted = alive + dead
                    Log.w("ServDiag", "CloudstreamProvider emit ${emitted.size} (alive=${alive.size} dead=${dead.size}) : ${emitted.joinToString(" | ") { it.name }}")
                    send(emitted)
                }
            }
        }

        launch {
            try { val n = fetchNativeCloudstreamServers(id, videoType); if (n.isNotEmpty()) emitDeduped(n) }
            catch (e: Exception) { Log.w(TAG, "Progressive native failed: ${e.message}") }
        }
        // 2026-07-04 : backups inline Cloudstream DÉSACTIVÉS → tout passe par le registre central.
        if (!com.streamflixreborn.streamflix.utils.BackupRegistry.INLINE_BACKUPS_DISABLED) {
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
            // 2026-06-12 (user "retarde le lancement de Coflix, il a tendance
            //   à foutre un peu la merde, faire appel à lui en dernier") :
            //   on attend 8 s avant de lancer Coflix → les autres backups
            //   (MovieBox+, Nakios, Movix, Wiflix, FS) ont le temps de
            //   répondre en premier. Coflix arrive en dernier si toujours
            //   pertinent (films absents ailleurs comme Aventures croisées).
            // 2026-07-08 : ancien Coflix DÉSACTIVÉ (domaines morts → page dons Telegram).
            // CoflixWiki (coflix.wiki) est dans BackupRegistry à la place.
            // launch {
            //     kotlinx.coroutines.delay(8_000L)
            //     try { val cf = fetchCoflixBackup(tmdbId, videoType, se, ep); if (cf.isNotEmpty()) emitDeduped(cf) }
            //     catch (e: Exception) { Log.w(TAG, "Progressive Coflix failed: ${e.message}") }
            // }
        }
        } // 2026-07-04 : fin gate INLINE_BACKUPS_DISABLED (backups Cloudstream → registre)
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
        // 2026-07-08 : le CDN bcdn.hakunaymatata.com renvoie 429 sans Bearer.
        // On ajoute le token JWT aux headers de lecture, comme pour les appels API.
        val hdrs = mutableMapOf(
            "Referer" to "https://moviebox.ph/",
            "User-Agent" to USER_AGENT,
        )
        val token = bearerToken ?: try { ensureBearer() } catch (_: Exception) { null }
        if (token != null) {
            hdrs["Authorization"] = "Bearer $token"
        }
        return Video(
            source = source,
            subtitles = subtitles,
            headers = hdrs,
        )
    }

}
