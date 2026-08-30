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

    /** 2026-08-05 — Signature CloudFront par identifiant de serveur, relevée dans
     *  `streams[].signCookie` au moment de `getServers` et reposée en cookie à la lecture.
     *  Indispensable : sans elle le CDN répond 403 « MissingKey ». */
    private val signaturesCloudFront = ConcurrentHashMap<String, String>()

    /** Format annoncé par l'API (`DASH` en pratique), pour déclarer le bon type à ExoPlayer. */
    private val formatsFlux = ConcurrentHashMap<String, String>()

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

    // ── 2026-08-30 — VRAI FILM via l'endpoint WEB + Referer (remplace /play-info = leurre) ─────
    //   REVERSE-ENGINEERING (Chrome du user + logs OPPO), tout vérifié en direct :
    //     · `/wefeed-mobile-bff/subject-api/play-info` (mobile signé) ne rend plus qu'un MP4
    //       LEURRE de 5,3 Mo (macdn.aoneroom.com/…1c7de0bd…), identique pour TOUS les films.
    //     · L'endpoint WEB `/wefeed-h5api-bff/subject/play` rend LE VRAI FILM (4 MP4 h264
    //       360/480/720/1080 sur bcdnxw.hakunaymatata.com, signature DANS l'URL, tailles réelles).
    //     · LE SEUL VERROU = l'en-tête **Referer**. Prouvé : depuis la page d'accueil
    //       (Referer=/), `hasResource=false` ; depuis la page détail
    //       (Referer=/movies/<detailPath>), `hasResource=true` + 4 streams. Même IP, mêmes
    //       cookies, même TLS → ce n'est NI l'IP, NI un gate navigateur : juste le Referer.
    //       Un slug bidon échoue → il faut le VRAI detailPath.
    //     · `detailPath` s'obtient sans token : `GET /wefeed-h5api-bff/detail?subjectId=<sid>`
    //       → `data.subject.detailPath`.
    //   → 2 GET NUS (aucune signature, aucun Bearer, aucun cookie), et les URLs bcdnxw se lisent
    //     ensuite direct dans ExoPlayer (206 video/mp4). Pas de WebView.
    // 2026-08-30 — POOL DE FRONTS WEB. officialmoviebox.com n'est qu'UNE porte vers le backend
    //   aoneroom ; le meme /wefeed-h5api-bff/... est servi par plusieurs fronts interchangeables.
    //   Valides VIVANTS depuis la France (hasResource=true, 4 streams) : officialmoviebox.com,
    //   themoviebox.xyz, movieboxhd.net. (netfilm.world / fmoviesunblocked.net = 403 en FR.)
    //   Cascade + lastGoodFront : si un front ferme ou se fait bloquer, on bascule tout seul
    //   -> aucune WebView, aucun crack d'app. Le CDN bcdnxw accepte le Referer de n'importe
    //   lequel de ces fronts (206, verifie).
    private val H5_FRONTS = listOf(
        "https://officialmoviebox.com",
        "https://themoviebox.xyz",
        "https://movieboxhd.net",
    )
    @Volatile private var lastGoodFront: String = H5_FRONTS.first()
    /** Racine du front pour le Referer de lecture (CDN bcdnxw). */
    private val h5PlaybackReferer: String get() = "$lastGoodFront/"
    private fun orderedFronts(): List<String> {
        val g = lastGoodFront
        return if (H5_FRONTS.contains(g)) listOf(g) + H5_FRONTS.filterNot { it == g } else H5_FRONTS
    }

    /** GET nu (pas de signature/Bearer) sur l'API h5, avec Referer optionnel. */
    private suspend fun h5Get(front: String, path: String, referer: String? = null): JSONObject? = withContext(Dispatchers.IO) {
        val url = "$front$path"
        try {
            val req = Request.Builder().url(url)
                .cacheControl(API_NO_CACHE)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .apply { if (referer != null) header("Referer", referer) }
                .build()
            httpClient.newCall(req).execute().use {
                if (!it.isSuccessful) { Log.d(TAG, "h5Get $front$path → ${it.code}"); return@withContext null }
                val body = it.body?.string() ?: return@withContext null
                return@withContext JSONObject(body)
            }
        } catch (e: Exception) {
            Log.d(TAG, "h5Get $front$path erreur: ${e.message}")
            null
        }
    }

    // 2026-08-30 — 4e PASSAGE : BFF TV (tv.aoneroom.com/wefeed-tv-bff). Reverse de l'APK TV
    //   (com.community.mbox.tv, NON packe) : la video passe par un BFF SEPARE, authentifie
    //   par X-Client-Info (empreinte client) + Bearer — PAS de HMAC, PAS de Referer. Endpoint
    //   /subject/play-info/v2. On reutilise notre Bearer + un X-Client-Info aux couleurs TV.
    //   Sonde diagnostic + fallback ultime si tous les fronts web tombent.
    private const val TV_HOST = "https://tv.aoneroom.com"
    private const val TV_PLAY_PATH = "/wefeed-tv-bff/subject/play-info/v2"
    private val TV_CLIENT_INFO: String by lazy {
        """
        {"package_name":"com.community.mbox.tv","version_name":"1.1.9.0820.03","version_code":50040014,
        "os":"android","os_version":"13","install_ch":"gp","device_id":"$persistedDeviceId",
        "install_store":"gp","gaid":"00000000-0000-0000-0000-000000000000","brand":"Redmi",
        "model":"23078RKD5C","system_language":"fr","net":"NETWORK_WIFI","region":"FR",
        "timezone":"Europe/Paris","sp_code":"40401","X-Play-Mode":"2"}
        """.trimIndent().replace("\n", "").replace("        ", "")
    }
    private const val TV_USER_AGENT =
        "com.community.mbox.tv/50040014 (Linux; U; Android 13; en_US; MovieBoxTV; Build/TQ2A.230405.003)"

    /** Sonde le BFF TV. Retourne (resolution, url, format). Vide si refus/erreur. */
    private suspend fun tvBffStreams(subjectId: String, se: Int, ep: Int): List<Triple<Int, String, String>> = withContext(Dispatchers.IO) {
        val token = try { ensureBearer() } catch (e: Exception) { null }
        val url = "$TV_HOST$TV_PLAY_PATH?subjectId=$subjectId&se=$se&ep=$ep&vipLevel=0&host=tv.aoneroom.com"
        try {
            val req = Request.Builder().url(url)
                .cacheControl(API_NO_CACHE)
                .apply {
                    // Le BFF TV exige la meme signature x-tr que le mobile (477 sans).
                    signedHeaders("GET", url).forEach { (k, v) -> header(k, v) }
                    // On repose ensuite l'empreinte TV + le Bearer par-dessus.
                    header("X-Client-Info", TV_CLIENT_INFO)
                    if (token != null) header("Authorization", "Bearer $token")
                }
                .build()
            httpClient.newCall(req).execute().use {
                if (!it.isSuccessful) { Log.d(TAG, "MVBX-TV $url -> ${it.code}"); return@withContext emptyList<Triple<Int, String, String>>() }
                val body = it.body?.string() ?: return@withContext emptyList<Triple<Int, String, String>>()
                val root = JSONObject(body)
                val data = root.optJSONObject("data")
                // Les MP4 progressifs du BFF TV sont dans `resources[]` (url + resolution +
                //   linkType), PAS dans `streams[]` (qui ne porte que le DASH). On lit resources[].
                val resArr = data?.optJSONArray("resources")
                    ?: return@withContext emptyList<Triple<Int, String, String>>()
                val byRes = LinkedHashMap<Int, Triple<Int, String, String>>()
                for (i in 0 until resArr.length()) {
                    val r = resArr.optJSONObject(i) ?: continue
                    // Filtre saison/episode pour les series (0/0 = film → tout garder).
                    if (se > 0 && r.has("se") && !r.isNull("se") && r.optInt("se") != se) continue
                    if (ep > 0 && r.has("ep") && !r.isNull("ep") && r.optInt("ep") != ep) continue
                    val u = r.optString("url").takeIf { it.isNotBlank() } ?: continue
                    // resolution peut valoir "1080", "1080p", "1080,720,480"… → on extrait les chiffres.
                    val res = Regex("\\d+").find(r.optString("resolution"))?.value?.toIntOrNull() ?: 0
                    val fmt = if (r.optString("linkType") == "1") "HLS" else "MP4"
                    byRes.putIfAbsent(res, Triple(res, u, fmt))
                }
                byRes.values.sortedByDescending { it.first }
            }
        } catch (e: Exception) {
            Log.d(TAG, "MVBX-TV erreur: ${e.message}"); emptyList<Triple<Int, String, String>>()
        }
    }
    /** detailPath (slug) d'un subjectId, requis pour le Referer. Caché par sid. */
    private val detailPathCache = ConcurrentHashMap<String, String>()
    private suspend fun h5DetailPath(subjectId: String): String? {
        detailPathCache[subjectId]?.let { return it.ifBlank { null } }
        for (front in orderedFronts()) {
            val resp = h5Get(front, "/wefeed-h5api-bff/detail?subjectId=$subjectId") ?: continue
            val data = resp.optJSONObject("data")
            val dp = data?.optJSONObject("subject")?.optString("detailPath")?.takeIf { it.isNotBlank() }
                ?: data?.optString("detailPath")?.takeIf { it.isNotBlank() }
            // Repli : chercher "detailPath":"…" n'importe où dans la réponse.
            ?: Regex("\"detailPath\"\\s*:\\s*\"([^\"]+)\"").find(resp.toString())?.groupValues?.get(1)
            if (!dp.isNullOrBlank()) { detailPathCache[subjectId] = dp; return dp }
        }
        return null
    }

    /** Résultat complet de résolution web MovieBox. */
    private data class H5PlayResult(
        val progressive: List<Triple<Int, String, String>>,  // (résolution, url, format) MP4
        val dashUrl: String?,        // manifeste DASH adaptatif (qualité auto)
        val dashCookie: String?,     // signCookie éventuel du DASH
        val frCaptions: List<String>,// URLs sous-titres FR (.srt/.vtt)
    )

    /** Cache des sous-titres FR récupérés via l'endpoint web (captions[]), par sid. */
    private val h5CaptionsCache = ConcurrentHashMap<String, List<String>>()

    /**
     * Résolution web complète (inspirée de CineStream/com.megix) : combine
     *   `subject/play` (streams + dash) ET `subject/download` (downloads MP4 + captions),
     *   le tout avec le Referer page-détail obligatoire. Dédup par résolution.
     */
    private suspend fun h5ResolvePlay(subjectId: String, se: Int, ep: Int): H5PlayResult {
        val empty = H5PlayResult(emptyList(), null, null, emptyList())
        val detailPath = h5DetailPath(subjectId)
        if (detailPath.isNullOrBlank()) {
            Log.w(TAG, "h5ResolvePlay : pas de detailPath pour sid=$subjectId → Referer impossible")
            return empty
        }
        val params = "subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath"
        // Cascade : on essaie chaque front jusqu'a en trouver un qui rend de vrais flux
        //   (streams/dash ou hasResource). Le Referer DOIT etre celui du front interroge.
        var play: org.json.JSONObject? = null
        var download: org.json.JSONObject? = null
        for (front in orderedFronts()) {
            val referer = "$front/movies/$detailPath"
            val p = h5Get(front, "/wefeed-h5api-bff/subject/play?$params", referer)
            val d = p?.optJSONObject("data")
            val ok = d != null && (
                (d.optJSONArray("streams")?.length() ?: 0) > 0 ||
                (d.optJSONArray("dash")?.length() ?: 0) > 0 ||
                d.optBoolean("hasResource")
            )
            if (ok) {
                lastGoodFront = front
                play = p
                download = h5Get(front, "/wefeed-h5api-bff/subject/download?$params", referer)
                Log.d(TAG, "h5ResolvePlay sid=$subjectId : front OK = $front")
                break
            }
        }

        // 4e passage — sonde TV-BFF (diagnostic toujours logue ; utilisee en fallback si le web
        //   n'a rien rendu). tv.aoneroom.com/wefeed-tv-bff : X-Client-Info + Bearer, ni Referer ni HMAC.
        val tvStreams = try { tvBffStreams(subjectId, se, ep) } catch (e: Exception) { emptyList() }
        Log.d(TAG, "MVBX-TV sid=$subjectId : ${tvStreams.size} streams via BFF TV")

        // Dédup par résolution ; on garde le premier vu (play prioritaire sur download).
        val byRes = LinkedHashMap<Int, Triple<Int, String, String>>()
        fun ajouter(arr: org.json.JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                if (s.optBoolean("vipLocked", false)) continue
                val u = s.optString("url").takeIf { it.isNotBlank() } ?: continue
                val fmt = s.optString("format").ifBlank { "MP4" }
                if (fmt.equals("DASH", ignoreCase = true)) continue  // DASH traité à part
                val res = s.optString("resolutions").substringBefore(',').trim()
                    .ifEmpty { s.optString("resolution") }.toIntOrNull() ?: 0
                byRes.putIfAbsent(res, Triple(res, u, fmt))
            }
        }
        ajouter(play?.optJSONObject("data")?.optJSONArray("streams"))
        ajouter(download?.optJSONObject("data")?.optJSONArray("downloads"))
        // Fallback TV : n'ajoute que si le web n'a rien donne (ne double pas les qualites web).
        if (byRes.isEmpty()) {
            for (t in tvStreams) byRes.putIfAbsent(t.first, t)
            if (tvStreams.isNotEmpty()) Log.d(TAG, "h5ResolvePlay sid=$subjectId : fallback TV-BFF utilise (${tvStreams.size})")
        }

        // DASH adaptatif (manifeste .mpd, souvent sur h5-api) — lisible avec le Referer.
        var dashUrl: String? = null
        var dashCookie: String? = null
        play?.optJSONObject("data")?.optJSONArray("dash")?.let { dashArr ->
            for (i in 0 until dashArr.length()) {
                val d = dashArr.optJSONObject(i) ?: continue
                if (d.optBoolean("vipLocked", false)) continue
                dashUrl = d.optString("url").takeIf { it.isNotBlank() } ?: continue
                dashCookie = d.optString("signCookie").takeIf { it.isNotBlank() }
                break
            }
        }

        // Sous-titres FR depuis captions[] (réponse download) — 1 appel, multi-langue.
        val frCaps = mutableListOf<String>()
        download?.optJSONObject("data")?.optJSONArray("captions")?.let { caps ->
            for (i in 0 until caps.length()) {
                val c = caps.optJSONObject(i) ?: continue
                if (c.optString("lan").equals("fr", ignoreCase = true) ||
                    c.optString("lanName").contains("fran", ignoreCase = true)) {
                    c.optString("url").takeIf { it.isNotBlank() }?.let { frCaps.add(it) }
                }
            }
        }

        val progressive = byRes.values.sortedByDescending { it.first }
        if (progressive.isEmpty() && dashUrl == null) {
            Log.w(TAG, "h5ResolvePlay sid=$subjectId : 0 flux (play/download vides)")
        }
        return H5PlayResult(progressive, dashUrl, dashCookie, frCaps)
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
                // Sondes du 5 août retirées : question tranchée. Le CDN
                //   `sacdn.hakunaymatata.com` est du CloudFront signé, et la signature ne
                //   passe NI par l'URL NI par un Set-Cookie de réponse — elle est livrée dans
                //   le corps JSON, champ `streams[].signCookie`. Voir getServers/getVideo.
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
            Log.w(TAG, "tous les mirrors KO (domaine mort ?) : ${lastNetError?.message}")
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

        // 2026-08-05 (user : « pourquoi je n'ai pas de serveur Cloudstream alors qu'avant il y
        //   en avait sur ce film ») — CONJONCTIONS ÉQUIVALENTES À L'ESPERLUETTE.
        //
        //   La ligne du dessus remplace toute ponctuation par une espace : « & » disparaît donc,
        //   mais le mot « and » reste. Deux écritures du MÊME titre cessent alors de se
        //   ressembler, et comme le rapprochement se fait par inclusion de chaînes, aucune ne
        //   contient l'autre — le mot parasite est AU MILIEU :
        //     « Asterix & Obelix: The Middle Kingdom »   → asterix obelix the middle kingdom
        //     « Astérix and Obélix: The Middle Kingdom » → asterix and obelix the middle kingdom
        //   Score 10, rejet, et Cloudstream ne rend aucun serveur pour ce film. Constaté en
        //   direct sur Astérix : MovieBox renvoyait bien 22 candidats dont le bon, refusé deux
        //   fois de suite.
        //
        //   On aligne donc les conjonctions sur le sort de l'esperluette : elles disparaissent.
        //   ⚠ C'est une correction d'ÉQUIVALENCE ORTHOGRAPHIQUE, pas un assouplissement du
        //     rapprochement. Le seuil `titleScore <= 3` du 10 juillet reste intact — il protège
        //     des faux appariements sur titres courts, et le principe « pas de serveur plutôt
        //     que le mauvais film » n'est pas entamé : après nettoyage les deux titres sont
        //     STRICTEMENT identiques, donc acceptés par le test d'égalité, sans approximation.
        n = n.replace(Regex("\\b(and|et|und|y|e)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return n
    }

    /** Types MovieBox+ relevés le 2026-08-20 sur une recherche « Star Trek » :
     *  1 = film, 2 = série. 5/6/9 = shorts, clips, extraits, jeux — jamais du VOD. */
    private const val TYPE_FILM = 1
    private const val TYPE_SERIE = 2

    /**
     * @param typeAttendu TYPE_FILM, TYPE_SERIE, ou 0 pour « peu importe ».
     */
    private suspend fun findSubjectId(title: String, year: Int? = null,
                                      typeAttendu: Int = 0): String? {
        val cleanQuery = TitleNormalizer.cleanForTmdbSearch(title).ifBlank { title }
        val normQuery = normalizeForMatch(cleanQuery)
        val cacheKey = "$normQuery|${year ?: 0}|$typeAttendu"
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
            // 0 = type absent de la réponse → inconnu, on ne l'écarte pas.
            val itType = if (s.has("subjectType")) s.optInt("subjectType", 0) else 0
            candidates.add(Candidate(sid, cleanIt, normalizeForMatch(cleanIt), itYear, itType))
        }

        // ⚠ 2026-08-20 (user « t'as récupéré 2 mauvais matchs avec Cloudstream ») —
        //   GARDE DE TYPE, NE PAS LA RETIRER.
        //   `subjectType` était lu depuis 2026-07 et rangé dans Candidate… puis JAMAIS
        //   utilisé par aucune des 5 étapes de matching. Résultat mesuré sur « Star Trek »
        //   (script tools/test_moviebox_type.py, api6.aoneroom.com, 22 résultats) :
        //     0  type 1  2009  Star Trek [Hindi]   ← film
        //     1  type 1  2009  Star Trek           ← film
        //     2  type 2  1966  Star Trek           ← LA série demandée
        //   Pour un épisode, `year` vaut `videoType.tvShow.releaseDate?...` — souvent NUL.
        //   L'étape 1 (exact+année) est donc sautée, l'étape 2 accepte `year == null`,
        //   et elle prend le PREMIER titre exact : le film de 2009. D'où deux serveurs
        //   720p/1080p qui lisent le film sur un épisode de la série de 1966.
        //   Même famille de piège que les identifiants TMDB film/série (cf. VoeLibrary).
        //   Touche tout homonyme film/série : Star Trek, Fargo, Westworld, Le Prisonnier…
        //   Bonus : écarte aussi les types 5/6/9 (clips, extraits, jeux) qui polluaient
        //   les recherches à titre court.
        //   2026-08-20, 2e passe (user « LOG TROP PERMISSIVE ») : la garde était
        //   STRICTE, sans échappatoire. Première version : on laissait passer les
        //   candidats sans `subjectType` (valeur 0). Mesuré avec
        //   tools/test_moviebox_type.py : « Star Trek » 22/22 résultats et « Fargo »
        //   16/16 portent tous un subjectType. L'échappatoire ne couvrait donc AUCUN
        //   cas réel — elle ne servait qu'à laisser le bug revenir en silence le jour
        //   où MovieBox+ omet le champ. Type inconnu = candidat REFUSÉ.
        if (typeAttendu != 0) {
            val avant = candidates.size
            val rejetes = candidates.filter { it.subjectType != typeAttendu }
            candidates.retainAll { it.subjectType == typeAttendu }
            Log.d(TAG, "findSubjectId('$cleanQuery') : garde de type ($typeAttendu) → " +
                "${candidates.size}/$avant retenu(s)" +
                if (rejetes.isEmpty()) ""
                else ", écartés : " + rejetes.joinToString(", ") {
                    "${it.cleanTitle}(t=${it.subjectType},${it.year})"
                })
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

    // ════════════════════════════════════════════════════════════════════════
    //  DISPONIBILITÉ — les mêmes règles que l'accueil Movix
    // ════════════════════════════════════════════════════════════════════════
    /**
     * 2026-08-04 (user : « est-ce que tout ça s'applique aussi à Cloudstream ? »).
     *
     * Réponse : non. Cloudstream construit son propre accueil et n'avait AUCUNE de ces
     * règles — ni délai après sortie, ni exclusion des films encore à l'affiche, ni
     * vérification d'une distribution française. Il affichait donc exactement les fiches
     * sans serveur que l'on venait de chasser côté Movix.
     *
     * Trois critères, identiques à ceux de l'accueil Movix :
     *   1. sorti depuis au moins une semaine — le temps que les hébergeurs publient ;
     *   2. plus à l'affiche en France — aucune copie correcte avant la fenêtre vidéo ;
     *   3. réellement distribué en France si la sortie est récente — un film que personne
     *      n'a sorti ici n'aura jamais de source française.
     *
     * Les deux listes de référence sont mises en cache six heures par `VodCategories`,
     * donc cet appel ne coûte rien au-delà du premier chargement.
     */
    private const val DELAI_SOURCES_JOURS = 7

    /** Onglet de catalogue : pages TMDB agrégées par chargement (compense le filtrage). */
    private const val PAGES_TMDB_PAR_CHARGEMENT = 3

    /** Seuil de notoriété — indissociable du tri par date, cf. getMovies. */
    private const val VOTES_MINIMUM_ONGLET = 15

    /**
     * @param delaiJours délai exigé depuis la sortie. **Zéro pour les rangées de plateforme** :
     *   figurer au catalogue d'un service, c'est y être disponible le jour même (décision user :
     *   « Canal, Amazon, Paramount, Disney, tout ça c'est des sorties instantanées »).
     */
    private suspend fun filtrerFilmsDisponibles(
        items: List<TMDb3.Movie>,
        delaiJours: Int = DELAI_SOURCES_JOURS,
    ): List<TMDb3.Movie> {
        val vod = com.streamflixreborn.streamflix.utils.VodCategories
        val enSalles = runCatching { vod.enSalles() }.getOrDefault(emptySet())
        val sortiesFr = runCatching { vod.sortiesFrancaises() }.getOrDefault(emptySet())
        return items.filter { m ->
            val id = m.id.toString()
            sortiDepuis(m.releaseDate, delaiJours) &&
                id !in enSalles &&
                (!vod.dansLaFenetreFrancaise(m.releaseDate) || id in sortiesFr)
        }
    }

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

    private suspend fun discoverMoviesOnProvider(
        watchProviderId: Int, mapMovie: (TMDb3.Movie) -> Movie,
    ): List<Movie> = runCatching { coroutineScope {
        // 2026-08-04 : quatre pages au lieu de deux. Entre le filtre de disponibilité et le
        //   plafond par franchise, deux pages ne laissaient plus de quoi garnir la rangée.
        // 2026-08-04 (user : « sur le home Cloudstream, les catégories Canal, Netflix, OCS…
        //   ce qui serait bien c'est d'afficher les NOUVEAUTÉS, pas des trucs de 1960 ») —
        //   ces rangées triaient par popularité sur tout le catalogue de la plateforme, d'où
        //   les classiques. Passées en date décroissante : une rangée « Sur Netflix » doit
        //   montrer ce qui vient d'arriver sur Netflix.
        //   Le seuil de notoriété accompagne le tri par date, comme partout ailleurs.
        val pages = (1..4).map { p ->
            async {
                runCatching {
                    TMDb3.Discover.movie(
                        page = p, language = language, region = "FR", watchRegion = "FR",
                        sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                        voteCount = TMDb3.Params.Range(VOTES_MINIMUM_ONGLET, null),
                        // Borne à AUJOURD'HUI, sans délai : le titre est déjà au catalogue de
                        //   la plateforme, donc disponible. On écarte seulement le futur.
                        primaryReleaseDate = TMDb3.Params.Range(lte = java.util.Calendar.getInstance()),
                        withWatchProviders = TMDb3.Params.WithBuilder(watchProviderId),
                        withOriginalLanguage = csOriginalLanguageBuilder(),
                    ).results
                }.getOrDefault(emptyList())
            }
        }.flatMap { it.await() }
        com.streamflixreborn.streamflix.utils.VodCategories.limiterFranchises(
            filtrerFilmsDisponibles(
                pages.distinctBy { it.id }.filter { it.posterPath != null && it.notAnim() },
                delaiJours = 0,
            )
        ) { it.title }.take(25).map(mapMovie)
    } }.getOrDefault(emptyList())

    private suspend fun discoverTvOnProvider(
        watchProviderId: Int, mapTv: (TMDb3.Tv) -> TvShow,
    ): List<TvShow> = runCatching { coroutineScope {
        // Mêmes rangées de plateforme que pour les films : ce sont des NOUVEAUTÉS, donc tri
        //   par date de première diffusion, pas par popularité. Ici la date de première
        //   diffusion est le bon critère — il s'agit de montrer ce qui vient d'arriver au
        //   catalogue, pas de faire remonter une série ancienne dont une saison repart.
        val pages = (1..4).map { p ->
            async {
                runCatching {
                    TMDb3.Discover.tv(
                        page = p, language = language, watchRegion = "FR",
                        sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,
                        voteCount = TMDb3.Params.Range(VOTES_MINIMUM_ONGLET, null),
                        // Sans délai, comme les films de plateforme : une série au catalogue
                        //   d'un service y est diffusée. Seul le futur est écarté.
                        firstAirDate = TMDb3.Params.Range(lte = java.util.Calendar.getInstance()),
                        withWatchProviders = TMDb3.Params.WithBuilder(watchProviderId),
                        withOriginalLanguage = csOriginalLanguageBuilder(),
                    ).results
                }.getOrDefault(emptyList())
            }
        }.flatMap { it.await() }
        pages.distinctBy { it.id }
            .filter { it.posterPath != null && it.notAnim() }.take(25).map(mapTv)
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
        // ⚠ 2026-08-04 : l'identifiant 56 (OCS) ne renvoie plus RIEN sur le catalogue
        //   français — le service a été absorbé. Vérifié sur /watch/providers/movie
        //   watch_region=FR : la bonne entrée est 685, « Ciné+ OCS ». La rangée « Sur OCS »
        //   était donc vide depuis la disparition du service.
        val ocsMoviesD         = async { discoverMoviesOnProvider(685, mapMovie) }   // Ciné+ OCS FR
        val maxMoviesD         = async { discoverMoviesOnProvider(1899, mapMovie) }  // Max FR

        val sections = mutableListOf<Category>()
        val featured = trendingD.await()
            .filter { it.posterPath != null && it.notAnim() }
            .take(10).mapNotNull { it.toAppItem() }
        if (featured.isNotEmpty()) sections.add(Category(name = Category.FEATURED, list = featured))

        // Nouveautés FR (films + séries séparés, pas de section mixte doublon)
        val newMovies = com.streamflixreborn.streamflix.utils.VodCategories.limiterFranchises(
            filtrerFilmsDisponibles(
                newMoviesD.await().filter { it.posterPath != null && it.notAnim() }
            )
        ) { it.title }.map(mapMovie)
        // Séries : ni salles ni distribution à vérifier, mais une série annoncée et non
        //   diffusée n'a aucun épisode — donc aucun serveur. Seul le délai s'applique.
        val newTv = newTvD.await()
            .filter { it.posterPath != null && it.notAnim() }
            .filter { sortiDepuis(it.firstAirDate, DELAI_SOURCES_JOURS) }
            .map(mapTv)
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
        addPlatformRow("Sur Ciné+ OCS",      ocsMoviesD.await(),       emptyList())
        addPlatformRow("Sur Max",            maxMoviesD.await(),       emptyList())

        // 3) Catégories de fond (toutes plateformes) — anime exclu
        val popularTv = popularTvD.await().filter { it.posterPath != null && it.notAnim() }.map(mapTv)
        if (popularTv.isNotEmpty()) sections.add(Category(name = "Séries populaires", list = popularTv))

        val topTv = topTvD.await().filter { it.posterPath != null && it.notAnim() }.map(mapTv)
        if (topTv.isNotEmpty()) sections.add(Category(name = "Séries les mieux notées", list = topTv))

        val popularMovies = com.streamflixreborn.streamflix.utils.VodCategories.limiterFranchises(
            filtrerFilmsDisponibles(
                popularMoviesD.await().filter { it.posterPath != null && it.notAnim() }
            )
        ) { it.title }.map(mapMovie)
        if (popularMovies.isNotEmpty()) sections.add(Category(name = "Films populaires", list = popularMovies))

        val topMovies = com.streamflixreborn.streamflix.utils.VodCategories.limiterFranchises(
            filtrerFilmsDisponibles(
                topMoviesD.await().filter { it.posterPath != null && it.notAnim() }
            )
        ) { it.title }.map(mapMovie)
        if (topMovies.isNotEmpty()) sections.add(Category(name = "Films les mieux notés", list = topMovies))

        // ════════════════════════════════════════════════════════════════════
        //  ANTI-DOUBLON GLOBAL
        // ════════════════════════════════════════════════════════════════════
        // 2026-08-04 (user : « le problème c'est pas que ce soit sur Max, c'est que
        //   Spider-Man il est dans au moins 7 catégories différentes »).
        //
        //   Cloudstream n'avait AUCUN anti-doublon — contrairement à Movix, qui en a un
        //   depuis juillet. Or ses rangées se recoupent par construction : un blockbuster
        //   est à la fois sur une plateforme, populaire ET bien noté, donc il sortait dans
        //   trois ou quatre rangées, décliné sur toute sa franchise.
        //
        //   Chaque rangée est servie dans son ordre d'affichage et « consomme » ses films ;
        //   la suivante ne montre que ce qui n'a pas encore été vu. Le carrousel FEATURED
        //   est exempté : c'est une mise en avant, pas une rangée de parcours.
        //   Une rangée descendue sous six jaquettes est retirée plutôt qu'affichée à moitié
        //   vide — les rangées ont été élargies en amont (quatre pages) pour l'éviter.
        run {
            fun cleDe(item: AppAdapter.Item): String? = when (item) {
                is Movie -> "m:${item.id}"
                is TvShow -> "s:${item.id}"
                else -> null
            }
            val vues = HashSet<String>()
            val propres = mutableListOf<Category>()
            for (section in sections) {
                if (section.name == Category.FEATURED) {
                    propres.add(section)
                    continue
                }
                val gardes = section.list.filter { item ->
                    val cle = cleDe(item) ?: return@filter true
                    vues.add(cle)
                }
                if (gardes.size >= 6) propres.add(section.copy(list = gardes.take(40)))
            }
            sections.clear()
            sections.addAll(propres)
        }

        Log.d(TAG, "getHome Cloudstream : ${sections.size} sections — featured=${featured.size}, nouveauxFilms=${newMovies.size}, nouvellesSeries=${newTv.size}")
        sections
    }

    /** Films onglet : style Movix = TMDB MovieLists.popular. IDs TMDB. */
    override suspend fun getMovies(page: Int): List<Movie> {
        // 2026-05-07 : strict FR original — withOriginalLanguage="fr"
        return runCatching {
            // 2026-08-04 — DATE + FILTRE DE NOTORIÉTÉ, arbitré par le user après deux essais
            //   ratés (cf. le commentaire détaillé dans MovixProvider.getMovies).
            //   Le tri est demandé au SERVEUR, seule façon d'obtenir un ordre cohérent sur
            //   tout le catalogue ; le seuil de votes écarte les productions que personne n'a
            //   notées ; et l'on agrège plusieurs pages TMDB par chargement pour compenser le
            //   volume perdu au filtrage. Les pages venant déjà triées, les mettre bout à
            //   bout préserve l'ordre chronologique global.
            //   ⚠ Les SÉRIES gardent la popularité (demande explicite du user) : une nouvelle
            //   saison d'une série ancienne doit pouvoir rester en tête d'affiche.
            // 2026-08-04 — FILTRE D'ANNÉE (second clic sur « Films », cf. YearFilter).
            //   Borne haute = la plus proche des deux : le délai de sortie ou la fin de
            //   l'année choisie.
            val plage = com.streamflixreborn.streamflix.utils.YearFilter
                .get(name, com.streamflixreborn.streamflix.utils.YearFilter.Type.FILMS)
            val delai = java.util.Calendar.getInstance()
                .apply { add(java.util.Calendar.DAY_OF_YEAR, -DELAI_SOURCES_JOURS) }
            val finAnnee = com.streamflixreborn.streamflix.utils.YearFilter.borneHaute(plage)
            val borne = TMDb3.Params.Range(
                gte = com.streamflixreborn.streamflix.utils.YearFilter.borneBasse(plage),
                lte = if (finAnnee != null && finAnnee.before(delai)) finAnnee else delai,
            )
            val brut = coroutineScope {
                (0 until PAGES_TMDB_PAR_CHARGEMENT).map { decalage ->
                    async {
                        runCatching {
                            TMDb3.Discover.movie(
                                page = (page - 1) * PAGES_TMDB_PAR_CHARGEMENT + decalage + 1,
                                language = language, region = "FR",
                                sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                                voteCount = TMDb3.Params.Range(VOTES_MINIMUM_ONGLET, null),
                                primaryReleaseDate = borne,
                                withOriginalLanguage = csOriginalLanguageBuilder(),
                            ).results
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
            filtrerFilmsDisponibles(brut.distinctBy { it.id })
                // ⚠ AUCUN tri ici : l'ordre vient du serveur et doit rester intact.
                .map { m ->
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
                // Filtre d'année, mémorisé séparément des films (cf. YearFilter.Type).
                firstAirDate = com.streamflixreborn.streamflix.utils.YearFilter
                    .get(name, com.streamflixreborn.streamflix.utils.YearFilter.Type.SERIES)
                    .let { p ->
                        TMDb3.Params.Range(
                            gte = com.streamflixreborn.streamflix.utils.YearFilter.borneBasse(p),
                            lte = com.streamflixreborn.streamflix.utils.YearFilter.borneHaute(p),
                        )
                    },
                withOriginalLanguage = csOriginalLanguageBuilder(),
            // ⚠ Tri par POPULARITÉ conservé volontairement (user : « je touche pas aux séries,
            //   où il peut y avoir des nouvelles saisons en tête d'affiche »). Une série
            //   ancienne qui repart mérite sa place en haut ; la date de PREMIÈRE diffusion
            //   la reléguerait au fond.
            ).results
                .filter { sortiDepuis(it.firstAirDate, DELAI_SOURCES_JOURS) }
                .map { t ->
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
            // 2026-08-04 (demande user : « une catégorie uniquement avec Netflix, Amazon…
            //   avant les genres, et une catégorie Nouveautés avec les sorties de l'année ») :
            //   ces entrées se comportent comme des genres — mêmes tuiles, même navigation —
            //   mais leur identifiant porte un préfixe que getGenre() sait reconnaître.
            //   Elles passent EN TÊTE, avant les genres classiques.
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
        // 2026-08-04 : catégories ajoutées en tête de la recherche (plateformes + Nouveautés).
        //   Traitées AVANT la logique de genre TMDB : leur identifiant n'est pas un genre.
        //   Logique partagée avec Movix et NetMirror — cf. VodCategories.
        if (com.streamflixreborn.streamflix.utils.VodCategories.estCategorieSpeciale(id)) {
            return com.streamflixreborn.streamflix.utils.VodCategories.charger(id, page, language)
        }
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
            // ── 2026-08-05 — LE GENRE N'ÉCRASE PLUS L'ANNÉE NI LE TRI ────────────────────
            //   Même défaut que sur Movix (cf. le commentaire détaillé dans MovixProvider) :
            //   choisir un genre faisait quitter `getMovies()` pour cette branche, qui
            //   ignorait le filtre d'année, le seuil de notoriété, le tri par date et la
            //   lecture multi-pages. On y reprend la recette de l'onglet Films.
            //   ⚠ Les SÉRIES gardent le tri par POPULARITÉ, décision du user : « je touche
            //     pas aux séries, où il peut y avoir des nouvelles saisons en tête d'affiche ».
            //     Seul le filtre d'année leur est ajouté.
            val plageFilms = com.streamflixreborn.streamflix.utils.YearFilter
                .get(name, com.streamflixreborn.streamflix.utils.YearFilter.Type.FILMS)
            val delaiG = java.util.Calendar.getInstance()
                .apply { add(java.util.Calendar.DAY_OF_YEAR, -DELAI_SOURCES_JOURS) }
            val finFilms = com.streamflixreborn.streamflix.utils.YearFilter.borneHaute(plageFilms)
            val borneFilms = TMDb3.Params.Range(
                gte = com.streamflixreborn.streamflix.utils.YearFilter.borneBasse(plageFilms),
                lte = if (finFilms != null && finFilms.before(delaiG)) finFilms else delaiG,
            )
            // Films — année + tri par date + notoriété + plusieurs pages
            val movies = if (genreFilterMovie != null || tmdbGenreId == null) {
                val brutG = coroutineScope {
                    (0 until PAGES_TMDB_PAR_CHARGEMENT).map { decalage ->
                        async {
                            runCatching {
                                TMDb3.Discover.movie(
                                    page = (page - 1) * PAGES_TMDB_PAR_CHARGEMENT + decalage + 1,
                                    language = language, region = "FR",
                                    sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                                    voteCount = TMDb3.Params.Range(VOTES_MINIMUM_ONGLET, null),
                                    primaryReleaseDate = borneFilms,
                                    withOriginCountry = withOrigin,
                                    withOriginalLanguage = langFilter,
                                    withGenres = genreFilterMovie,
                                ).results
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll().flatten()
                }
                filtrerFilmsDisponibles(brutG.distinctBy { it.id }).map { m ->
                    Movie(
                        id = m.id.toString(), title = m.title, overview = m.overview,
                        released = m.releaseDate, rating = m.voteAverage.toDouble(),
                        poster = m.posterPath?.w500, banner = m.backdropPath?.w1280,
                    )
                }
            } else emptyList()  // genre ID existe côté TV mais pas Movie → skip
            // Séries TV — popularité conservée, filtre d'année ajouté
            val tvShows = if (genreFilterTv != null || tmdbGenreId == null) {
                TMDb3.Discover.tv(
                    language = language, page = page,
                    withOriginCountry = withOrigin,
                    withOriginalLanguage = langFilter,
                    withGenres = genreFilterTv,
                    firstAirDate = com.streamflixreborn.streamflix.utils.YearFilter
                        .get(name, com.streamflixreborn.streamflix.utils.YearFilter.Type.SERIES)
                        .let { p ->
                            TMDb3.Params.Range(
                                gte = com.streamflixreborn.streamflix.utils.YearFilter.borneBasse(p),
                                lte = com.streamflixreborn.streamflix.utils.YearFilter.borneHaute(p),
                            )
                        },
                    sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                ).results.map { t ->
                    TvShow(
                        id = t.id.toString(), title = t.name, overview = t.overview,
                        released = t.firstAirDate, rating = t.voteAverage.toDouble(),
                        poster = t.posterPath?.w500, banner = t.backdropPath?.w1280,
                    )
                }
            } else emptyList()  // genre ID existe côté Movie mais pas TV → skip
            // ⚠ Les films arrivent déjà triés par date côté serveur : on ne les retrie PAS
            //   par note, sinon on reperdrait l'ordre qu'on vient de rétablir. Les films
            //   d'abord (ordonnés), les séries ensuite (par popularité, comme demandé).
            val combined = movies + tvShows
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
                    // 2026-08-20 : on dit à findSubjectId CE QU'ON CHERCHE. Sans ça, un
                    //   épisode de série pouvait tomber sur le film homonyme (Star Trek).
                    val typeAttendu = if (videoType is Video.Type.Movie) TYPE_FILM else TYPE_SERIE
                    for (t in titles) {
                        subjectId = findSubjectId(t, year, typeAttendu)
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

        // ⚠ 2026-08-20 (user « et sur le mauvais épisode ? » → « LES 2 ») —
        //   FILET DE SÉCURITÉ SAISON/ÉPISODE. NE PAS RETIRER.
        //   Preuve par les empreintes de fichiers : sur « Charlie X » (S01E02), le
        //   lecteur a chargé `d0956a3c…mp4` et `7983aa02…mp4` — qui sont, relevés en
        //   direct sur l'API (tools/test_moviebox_playinfo.py), les fichiers de
        //   l'ÉPISODE 1. Bon sujet, bonne série, mauvais épisode.
        //   Cause : le `when` ci-dessus ne renseigne se/ep que pour `cs::ep::…` et pour
        //   les identifiants TMDB. Avec `cs::s::…` ou `cs::m::…` ils restent à 0, et
        //   `/play-info` est alors appelé SANS `se` ni `ep` — MovieBox+ répond par
        //   défaut l'épisode 1, sans erreur ni avertissement. Une dégradation muette
        //   qui donne un flux parfaitement lisible… du mauvais épisode.
        if (videoType is Video.Type.Episode) {
            if (se <= 0) se = videoType.season.number
            if (ep <= 0) ep = videoType.number
            if (se <= 0 || ep <= 0) {
                Log.w(TAG, "getServers $id : épisode sans saison/numéro exploitable " +
                    "(se=$se ep=$ep) — on n'interroge pas MovieBox+, il servirait l'épisode 1")
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

        // 2026-08-30 — VRAIS FLUX via endpoint web + Referer (voir h5ResolvePlay).
        //   Le /play-info mobile ne rend qu'un leurre : on l'abandonne au profit de
        //   /wefeed-h5api-bff/subject/play + /subject/download appelés avec le Referer de la
        //   page détail. On combine downloads[]+streams[] (dédup par résolution), on ajoute
        //   un serveur DASH « Auto », et on récupère les sous-titres FR (captions[]).
        if (!subjectId.isNullOrBlank()) {
            val sid: String = subjectId!!
            val h5 = try {
                h5ResolvePlay(sid, se, ep)
            } catch (e: Exception) {
                Log.w(TAG, "h5ResolvePlay KO: ${e.message}")
                H5PlayResult(emptyList(), null, null, emptyList())
            }
            // Sous-titres FR (web) mémorisés pour getVideo.
            if (h5.frCaptions.isNotEmpty()) h5CaptionsCache[sid] = h5.frCaptions
            // MP4 progressifs (downloads + streams fusionnés, dédupés par résolution).
            for ((idx, t) in h5.progressive.withIndex()) {
                val idServeur = "cs_h5play_${sid}_${se}_${ep}_${t.first}_$idx"
                if (t.third.isNotBlank()) formatsFlux[idServeur] = t.third
                servers.add(
                    Video.Server(
                        id = idServeur,
                        name = "Cloudstream [${t.first}p]",
                        src = t.second,
                    )
                )
            }
            // Serveur DASH adaptatif (qualité auto).
            h5.dashUrl?.let { dashUrl ->
                val dashId = "cs_h5dash_${sid}_${se}_${ep}"
                formatsFlux[dashId] = "DASH"
                if (!h5.dashCookie.isNullOrBlank()) signaturesCloudFront[dashId] = h5.dashCookie!!
                servers.add(
                    Video.Server(
                        id = dashId,
                        name = "Cloudstream [Auto]",
                        src = dashUrl,
                    )
                )
            }
            if (h5.progressive.isNotEmpty() || h5.dashUrl != null) {
                Log.d(TAG, "getServers $id : +${h5.progressive.size} MP4" +
                    (if (h5.dashUrl != null) " +1 DASH" else "") +
                    " web (Referer), ${h5.frCaptions.size} sous-titre(s) FR")
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
        // 2026-08-30 — cs_h5play_ : URL bcdnxw signée (endpoint web), directement lisible.
        //   La signature est DANS l'URL → aucun cookie CloudFront ni Bearer. Le Referer FAIT
        //   rejeter le CDN (comme pour les autres flux hakunaymatata) → on ne met QUE l'UA.
        if (server.id.startsWith("cs_h5play_")) {
            val sidH5 = parseSidFromCsId(server.id)
            val capsH5 = sidH5?.let { h5CaptionsCache[it] ?: frenchCaptionsCache[it] } ?: emptyList()
            val subsH5 = capsH5.mapIndexed { idx, u ->
                Video.Subtitle(
                    label = if (idx == 0) "Français" else "Français (${idx + 1})",
                    file = u, default = idx == 0, initialDefault = idx == 0,
                )
            }
            // Le CDN web bcdnxw EXIGE un Referer officialmoviebox.com (vérifié : avec →
            //   206 video/mp4 ; sans → 429/erreur). ⚠ NE PAS confondre avec la vieille note
            //   « Referer fait rejeter le CDN » : celle-là visait l'ANCIEN CDN mobile bcdn avec
            //   le Referer moviebox.ph. Le NOUVEAU CDN web veut officialmoviebox.com. UA
            //   navigateur ; signature déjà dans l'URL (pas de cookie/Bearer).
            return Video(
                source = server.src,
                subtitles = subsH5,
                headers = mutableMapOf(
                    "User-Agent" to com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_UA,
                    "Referer" to h5PlaybackReferer,
                ),
                type = null,  // MP4 progressif — ExoPlayer auto-détecte
            )
        }
        // 2026-08-30 — cs_h5dash_ : manifeste DASH adaptatif (qualité auto). Lisible avec le
        //   même Referer officialmoviebox.com + UA navigateur ; type MPD déclaré explicitement.
        //   signCookie éventuel reposé en Cookie (souvent vide — la signature est dans l'URL).
        if (server.id.startsWith("cs_h5dash_")) {
            val sidD = parseSidFromCsId(server.id)
            val capsD = sidD?.let { h5CaptionsCache[it] ?: frenchCaptionsCache[it] } ?: emptyList()
            val subsD = capsD.mapIndexed { idx, u ->
                Video.Subtitle(
                    label = if (idx == 0) "Français" else "Français (${idx + 1})",
                    file = u, default = idx == 0, initialDefault = idx == 0,
                )
            }
            val hdrsD = mutableMapOf(
                "User-Agent" to com.streamflixreborn.streamflix.utils.WebViewResolver.STEALTH_UA,
                "Referer" to h5PlaybackReferer,
            )
            signaturesCloudFront[server.id]?.takeIf { it.isNotBlank() }?.let { hdrsD["Cookie"] = it }
            return Video(
                source = server.src,
                subtitles = subsD,
                headers = hdrsD,
                type = androidx.media3.common.MimeTypes.APPLICATION_MPD,
            )
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
        // 2026-08-08 (user : « regarde Cloudstream 429 ») — REFERER RETIRÉ, même mesure que
        //   dans AoneroomClient.streamPlaybackHeaders (voir le détail là-bas). Sur la MÊME URL,
        //   en isolant un en-tête à la fois : sans rien / UA seul / Bearer seul → 206 video/mp4 ;
        //   dès que `Referer: https://moviebox.ph/` est présent → refus du CDN. Le Bearer, lui,
        //   reste utile (note du 2026-07-08 ci-dessus) et n'a jamais posé problème seul.
        //   Pour revenir en arrière : décommenter la ligne.
        val hdrs = mutableMapOf(
            // "Referer" to "https://moviebox.ph/",
            "User-Agent" to USER_AGENT,
        )
        val token = bearerToken ?: try { ensureBearer() } catch (_: Exception) { null }
        if (token != null) {
            hdrs["Authorization"] = "Bearer $token"
        }
        // ── 2026-08-05 — SIGNATURE CLOUDFRONT (corrige le 403 « MissingKey ») ────────────
        //   `streams[].signCookie` porte `CloudFront-Policy` / `CloudFront-Signature` /
        //   `CloudFront-Key-Pair-Id`. CloudFront ne les lit QUE dans un en-tête `Cookie` :
        //   sans lui, le manifeste répond 403 et le lecteur basculait sur un autre serveur.
        val signature = signaturesCloudFront[server.id]
        if (!signature.isNullOrBlank()) {
            hdrs["Cookie"] = signature
            Log.d(TAG, "getVideo ${server.id} : signature CloudFront posée en cookie")
        }
        // Le flux est un manifeste DASH (`index_web.mpd`). L'extension ne suffit pas
        //   toujours à le faire reconnaître : on déclare le type explicitement.
        val estDash = formatsFlux[server.id]?.equals("DASH", ignoreCase = true) == true ||
            source.contains(".mpd", ignoreCase = true)
        return Video(
            source = source,
            subtitles = subtitles,
            headers = hdrs,
            type = if (estDash) androidx.media3.common.MimeTypes.APPLICATION_MPD else null,
        )
    }

}
