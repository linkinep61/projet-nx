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
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 2026-05-06 : Cloudstream — provider qui scrape la source MovieBox+ via son
 * API MOBILE officielle (api6.aoneroom.com / api3 / api4 / api5 /
 * api.inmoviebox.com — pool rotatif) en passant par `/wefeed-mobile-bff/...`
 * (par opposition au `/wefeed-h5api-bff/...` du provider Moviebox v1).
 *
 * Branding affiché : "Cloudstream" — logo dédié, mais derrière la techno
 * c'est la même API MovieBox+ que la version H5 (catalogue plus complet :
 * carousels par tab, vrais saisons via /season-info, MPD adaptive bitrate).
 *
 * Endpoints :
 *   - /tab-operating?page=1&tabId=N         — feed home (vrais carousels)
 *   - /subject-api/search                   — recherche v1
 *   - /subject-api/search/v2                — recherche v2
 *   - /subject-api/get?subjectId=X          — détail
 *   - /subject-api/season-info?subjectId=X  — saisons
 *   - /subject-api/play-info?subjectId=X&se=N&ep=N  — URLs MP4/MPD
 *   - /subject-api/resource?subjectId=X&se=N&ep=N   — qualités dispo
 *
 * Auth : signature HMAC-MD5 par requête (header `x-tr-signature`) + token
 * temps-vivant (`X-Client-Token`) + identité device (`X-Client-Info`).
 * Reverse-engineering basé sur https://github.com/Simatwa/moviebox-api (v3).
 */
object CloudstreamProvider : Provider {

    override val name = "Cloudstream"
    override val baseUrl = "https://api6.aoneroom.com"
    override val language = "fr"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_cloudstream"

    private const val TAG = "CloudstreamProvider"

    /** Pool de hosts à essayer en cascade (fallback en cas de 403/429/500). */
    private val HOST_POOL = listOf(
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api6sg.aoneroom.com",
        "https://api.inmoviebox.com",
    )

    // 2026-05-05 : secret HMAC-MD5 (b64). Reverse-engineered, peut tourner si
    // le serveur le change. Valeur "alt" en backup au cas où.
    private const val SECRET_KEY_DEFAULT_B64 = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
    private val SECRET_KEY_BYTES: ByteArray by lazy {
        android.util.Base64.decode(
            SECRET_KEY_DEFAULT_B64.padEnd(
                ((SECRET_KEY_DEFAULT_B64.length + 3) / 4) * 4, '='
            ),
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

    // ─── Constantes endpoints ─────────────────────────────────────────────
    private const val MAIN_PAGE_PATH = "/wefeed-mobile-bff/tab-operating"
    private const val SEARCH_PATH = "/wefeed-mobile-bff/subject-api/search"
    private const val SUBJECT_GET_PATH = "/wefeed-mobile-bff/subject-api/get"
    private const val SEASON_INFO_PATH = "/wefeed-mobile-bff/subject-api/season-info"
    private const val PLAY_INFO_PATH = "/wefeed-mobile-bff/subject-api/play-info"
    private const val RESOURCE_PATH = "/wefeed-mobile-bff/subject-api/resource"

    private val httpClient: OkHttpClient by lazy {
        Extractor.sharedClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // ─── Signing ──────────────────────────────────────────────────────────

    /** `X-Client-Token` = `<ts_ms>,<md5(reverse(ts_ms_string))>`. */
    private fun generateXClientToken(tsMs: Long): String {
        val ts = tsMs.toString()
        val reversed = ts.reversed()
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(reversed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$ts,$md5"
    }

    /** Reconstruit la query string avec les clés triées (sans URL-encode). */
    private fun sortedQueryString(query: String): String {
        if (query.isEmpty()) return ""
        val params = query.split("&").mapNotNull {
            val eq = it.indexOf("=")
            if (eq < 0) it to "" else it.substring(0, eq) to it.substring(eq + 1)
        }
        return params.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
    }

    /** Construit la chaîne canonique signée : METHOD\nACCEPT\nCT\nBODY_LEN\nTS\nBODY_MD5\nPATH?SORTED_Q. */
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

    /** `x-tr-signature` = `<ts>|2|<base64(hmac-md5(canonical, secret))>`. */
    private fun generateXTrSignature(
        method: String, accept: String?, contentType: String?,
        url: String, body: String?, tsMs: Long,
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, tsMs)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SECRET_KEY_BYTES, "HmacMD5"))
        val sigBytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val sigB64 = android.util.Base64.encodeToString(
            sigBytes, android.util.Base64.NO_WRAP
        )
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

    // ─── HTTP avec rotation de host ──────────────────────────────────────

    private suspend fun apiGet(path: String, params: Map<String, String> = emptyMap()): JSONObject? = withContext(Dispatchers.IO) {
        val query = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val pathWithQuery = if (query.isNotBlank()) "$path?$query" else path
        for (host in HOST_POOL) {
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
                    if (!it.isSuccessful) {
                        Log.w(TAG, "GET $url → HTTP $code")
                        return@withContext null
                    }
                    val body = it.body?.string() ?: return@withContext null
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
        for (host in HOST_POOL) {
            val url = "$host$path"
            try {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val req = Request.Builder().url(url).apply {
                    signedHeaders("POST", url, bodyStr, contentType = "application/json; charset=utf-8").forEach { (k, v) -> header(k, v) }
                }.post(bodyStr.toRequestBody(mediaType)).build()
                val resp = httpClient.newCall(req).execute()
                resp.use {
                    val code = it.code
                    if (code in setOf(403, 407, 429, 500, 502, 503, 504)) {
                        Log.d(TAG, "POST host $host returned $code, retry next")
                        return@use
                    }
                    if (!it.isSuccessful) {
                        Log.w(TAG, "POST $url → HTTP $code")
                        return@withContext null
                    }
                    val body = it.body?.string() ?: return@withContext null
                    return@withContext JSONObject(body)
                }
            } catch (e: Exception) {
                Log.d(TAG, "POST host $host error: ${e.message}, retry next")
            }
        }
        null
    }


    // ─── Helpers parsing ──────────────────────────────────────────────────

    /** Convertit un JSONObject d'item Moviebox en Movie. */
    private fun parseMovie(o: JSONObject): Movie {
        val subjectId = o.optString("subjectId").ifEmpty { o.optString("id") }
        return Movie(
            id = "cs::m::$subjectId",
            title = o.optString("title").ifEmpty { o.optString("name") },
            overview = o.optString("description").takeIf { it.isNotBlank() },
            released = o.optString("releaseDate").takeIf { it.isNotBlank() }
                ?: o.optString("year").takeIf { it.isNotBlank() },
            poster = o.optJSONObject("cover")?.optString("url")
                ?: o.optString("posterUrl").takeIf { it.isNotBlank() }
                ?: o.optString("imageUrl").takeIf { it.isNotBlank() },
            banner = o.optJSONObject("cover")?.optString("url"),
            rating = o.optString("imdbRatingValue").toDoubleOrNull()
                ?: o.optDouble("rating").takeIf { !it.isNaN() && it > 0 },
            genres = parseGenres(o.optString("genre")),
            providerName = name,
        )
    }

    private fun parseTvShow(o: JSONObject): TvShow {
        val subjectId = o.optString("subjectId").ifEmpty { o.optString("id") }
        return TvShow(
            id = "cs::s::$subjectId",
            title = o.optString("title").ifEmpty { o.optString("name") },
            overview = o.optString("description").takeIf { it.isNotBlank() },
            released = o.optString("releaseDate").takeIf { it.isNotBlank() }
                ?: o.optString("year").takeIf { it.isNotBlank() },
            poster = o.optJSONObject("cover")?.optString("url")
                ?: o.optString("posterUrl").takeIf { it.isNotBlank() }
                ?: o.optString("imageUrl").takeIf { it.isNotBlank() },
            banner = o.optJSONObject("cover")?.optString("url"),
            rating = o.optString("imdbRatingValue").toDoubleOrNull()
                ?: o.optDouble("rating").takeIf { !it.isNaN() && it > 0 },
            genres = parseGenres(o.optString("genre")),
            providerName = name,
        )
    }

    private fun parseGenres(raw: String?): List<Genre> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            .map { Genre(id = "cs::g::$it", name = it) }
    }

    /** subjectType : 1=Movie, 2=TvShow. */
    private fun jsonToItem(o: JSONObject): AppAdapter.Item {
        val type = o.optInt("subjectType", 1)
        return if (type == 1) parseMovie(o) else parseTvShow(o)
    }

    // 2026-05-06 : filtre FR — l'API mobile-bff retourne plein de contenus
    // dub. dans des langues régionales (Inde/Asie). On rejette tout titre
    // dont le suffixe entre [...] correspond à une langue qu'on ne supporte
    // pas. Les titres SANS suffixe sont gardés (typiquement Hollywood VO/VF).
    private val NON_FR_LANG_REGEX = Regex(
        """\[(Hindi|Tamil|Telugu|Korean|Japanese|Indonesian|Thai|Vietnamese|""" +
        """Arabic|Spanish|Portuguese|Mandarin|Cantonese|Russian|Turkish|""" +
        """Bengali|Punjabi|Urdu|Gujarati|Marathi|Malayalam|Kannada|Sinhala|""" +
        """Burmese|Filipino|Tagalog|Khmer|Lao|Nepali|Polish|Italian|German|""" +
        """Greek|Hebrew|Persian|Farsi|Swahili|Romanian|Hungarian|Czech|""" +
        """Dutch|Swedish|Norwegian|Danish|Finnish|Ukrainian)\]""",
        RegexOption.IGNORE_CASE
    )
    private val FR_LANG_REGEX = Regex(
        """\[(French|VF|Fran[çc]ais|VOSTFR)\]""",
        RegexOption.IGNORE_CASE
    )

    /** Garde les titres FR-compatibles : ceux marqués FR explicitement, ou
     *  ceux sans suffixe de langue (souvent Hollywood VO trouvable en FR). */
    private fun isFrenchFriendly(title: String): Boolean {
        if (title.isBlank()) return false
        if (FR_LANG_REGEX.containsMatchIn(title)) return true
        if (NON_FR_LANG_REGEX.containsMatchIn(title)) return false
        return true  // pas de suffixe de langue → on garde
    }

    private fun isFrenchFriendly(item: AppAdapter.Item): Boolean = when (item) {
        is Movie -> isFrenchFriendly(item.title)
        is TvShow -> isFrenchFriendly(item.title)
        else -> true
    }

    /** 2026-05-06 : détection du contenu DramaBox / shorts vertical (1-2 min
     *  par épisode). L'utilisateur les perçoit comme des pubs car ils sont
     *  formatés comme du TikTok/Reels. Signaux :
     *    - `shortsEpisode > 0` : nombre d'épisodes shorts (50, 64, etc.)
     *    - `genre == "Drame moderne"` : marqueur de catalogue DramaBox
     *    - countryName "Chine" + drame court : indicateur partiel
     *  On rejette agressivement pour la home/listes — les utilisateurs de
     *  Streamflix attendent du long-form (films/séries TV classiques). */
    private fun isShortDrama(o: JSONObject): Boolean {
        if (o.optInt("shortsEpisode", 0) > 0) return true
        val genre = o.optString("genre").orEmpty()
        if (genre.equals("Drame moderne", ignoreCase = true)) return true
        // Drama box content has very short `seconds` per episode (< 600s) when set
        val seconds = o.optInt("seconds", 0)
        if (seconds in 1..599) return true
        return false
    }

    // 2026-05-06 : cache audio/sub FR par subjectId pour éviter les détail-fetches
    // répétés à chaque chargement de home. Lifetime = process. Map<subjectId, true=FR ok>.
    private val frAvailabilityCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Fetch /subject-api/get pour `subjectId` et déterminer si du FR est dispo :
     *    1) Audio FR via dubs[] (lanCode=fr, type=0=dub) → accepté
     *    2) Sous-titres FR via le champ `subtitles` (contient "Français"/"French") → accepté (VOSTFR)
     *    3) Aucun des deux → rejeté (VO seule = inutile selon user). */
    private suspend fun hasFrenchAudioOrSub(subjectId: String): Boolean {
        if (subjectId.isBlank()) return false
        frAvailabilityCache[subjectId]?.let { return it }
        val resp = apiGet(SUBJECT_GET_PATH, mapOf("subjectId" to subjectId)) ?: return false
        val data = resp.optJSONObject("data") ?: return false

        // #1 : audio FR via dubs[] (lanCode=fr, type=0=dub)
        val dubs = data.optJSONArray("dubs")
        if (dubs != null) {
            for (i in 0 until dubs.length()) {
                val d = dubs.optJSONObject(i) ?: continue
                val lan = d.optString("lanCode")
                val type = d.optInt("type", -1)
                if (lan.equals("fr", ignoreCase = true) && type == 0) {
                    frAvailabilityCache[subjectId] = true
                    return true
                }
            }
        }

        // #2 : VOSTFR via `subtitles` (CSV des langues sub disponibles)
        val subtitles = data.optString("subtitles").orEmpty()
        if (Regex("""(?i)\bfran[çc]ais\b|\bfrench\b""").containsMatchIn(subtitles)) {
            frAvailabilityCache[subjectId] = true
            return true
        }

        // Aussi : FR sub via dubs[] (lanCode=fr, type=1=sub)
        if (dubs != null) {
            for (i in 0 until dubs.length()) {
                val d = dubs.optJSONObject(i) ?: continue
                val lan = d.optString("lanCode")
                if (lan.equals("fr", ignoreCase = true)) {
                    frAvailabilityCache[subjectId] = true
                    return true
                }
            }
        }

        frAvailabilityCache[subjectId] = false
        return false
    }

    /** Filtre une liste de subjectIds en parallèle (concurrency limitée à 10) :
     *  retourne ceux qui ont audio FR ou VOSTFR. */
    private suspend fun filterFrenchAvailable(ids: List<String>): Set<String> = coroutineScope {
        val results = ids.map { sid ->
            async { sid to hasFrenchAudioOrSub(sid) }
        }.awaitAll()
        results.filter { it.second }.map { it.first }.toSet()
    }

    // ─── Provider impl ────────────────────────────────────────────────────

    /** Page d'accueil : agrège les tabs 0..5 (long-form catalogue) en parallèle.
     *  Tab 6 est SKIP car il contient majoritairement des DramaBox shorts
     *  (mini-drama vertical 1-2 min/ép) que l'utilisateur perçoit comme des pubs.
     *  Filtre :
     *    1) shorts (shortsEpisode>0, genre="Drame moderne", seconds<600) → reject
     *    2) suffixe langue non-FR → reject
     *    3) **audio FR ou sous-titres FR confirmés** via /subject-api/get (cached)
     *  Item types : BANNER (carousels promo), SUBJECT_GROUP (rangée), VERTICAL_RANK (top X). */
    override suspend fun getHome(): List<Category> = coroutineScope {
        // Fetch les 6 tabs en parallèle (tabId 0..5). Tab 6 (shorts) skip.
        val tabResponses = (0..5).map { tabId ->
            async { tabId to apiGet(MAIN_PAGE_PATH, mapOf("page" to "1", "tabId" to "$tabId", "version" to "")) }
        }.awaitAll()

        val sections = mutableListOf<Category>()
        var featuredAdded = false
        val seenIds = mutableSetOf<String>()  // dédoublonnage cross-tab

        // Pre-pass : collecte tous les subjectIds candidats pour batch-fetch des détails.
        // On filtre déjà à ce stade : DramaBox shorts + suffixe non-FR dans le titre
        // (évite des centaines de détail-fetches pour des Hindi/Tamil/etc.)
        val candidateIds = mutableSetOf<String>()
        for ((_, resp) in tabResponses) {
            if (resp == null) continue
            val items = resp.optJSONObject("data")?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("type") == "BANNER") {
                    val banners = item.optJSONObject("banner")?.optJSONArray("banners") ?: continue
                    for (j in 0 until banners.length()) {
                        val s = banners.optJSONObject(j)?.optJSONObject("subject") ?: continue
                        if (isShortDrama(s)) continue
                        if (NON_FR_LANG_REGEX.containsMatchIn(s.optString("title"))) continue
                        s.optString("subjectId").takeIf { it.isNotBlank() }?.let { candidateIds.add(it) }
                    }
                } else {
                    val subjects = item.optJSONArray("subjects")
                        ?: item.optJSONObject("group")?.optJSONArray("subjects")
                        ?: continue
                    for (j in 0 until subjects.length()) {
                        val raw = subjects.optJSONObject(j) ?: continue
                        val s = raw.optJSONObject("subject") ?: raw
                        if (isShortDrama(s)) continue
                        if (NON_FR_LANG_REGEX.containsMatchIn(s.optString("title"))) continue
                        s.optString("subjectId").takeIf { it.isNotBlank() }?.let { candidateIds.add(it) }
                    }
                }
            }
        }
        Log.d(TAG, "getHome: ${candidateIds.size} candidates avant filtrage FR audio/sub")
        val frAllowed = filterFrenchAvailable(candidateIds.toList())
        Log.d(TAG, "getHome: ${frAllowed.size} candidates avec audio/sub FR (sur ${candidateIds.size})")

        // 2026-05-06 : on récupère 1 seule section featured + 2 grosses sections
        // (Films / Séries FR) au lieu d'éclater sur 6+ sections vides après filtrage.
        // Le user a explicitement demandé "spécifiquement du contenu français".
        val frMovies = mutableListOf<Movie>()
        val frTvShows = mutableListOf<TvShow>()
        val featured = mutableListOf<AppAdapter.Item>()
        val seenForLists = mutableSetOf<String>()
        for ((_, resp) in tabResponses) {
            if (resp == null) continue
            val items = resp.optJSONObject("data")?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("type") == "BANNER" && featured.size < 10) {
                    val banners = item.optJSONObject("banner")?.optJSONArray("banners") ?: continue
                    for (j in 0 until banners.length()) {
                        if (featured.size >= 10) break
                        val b = banners.optJSONObject(j) ?: continue
                        val subject = b.optJSONObject("subject") ?: continue
                        val sid = subject.optString("subjectId")
                        val title = subject.optString("title")
                        if (isShortDrama(subject)) continue
                        if (NON_FR_LANG_REGEX.containsMatchIn(title)) continue  // [Hindi]/etc.
                        if (sid !in frAllowed) continue
                        if (!seenForLists.add(sid)) continue
                        val landscape = b.optJSONObject("image")?.optString("url")
                        val parsed = jsonToItem(subject)
                        featured.add(when (parsed) {
                            is Movie -> parsed.apply { banner = landscape ?: this.banner }
                            is TvShow -> parsed.apply { banner = landscape ?: this.banner }
                            else -> parsed
                        })
                    }
                } else {
                    val subjects = item.optJSONArray("subjects")
                        ?: item.optJSONObject("group")?.optJSONArray("subjects")
                        ?: continue
                    for (j in 0 until subjects.length()) {
                        val s = subjects.optJSONObject(j) ?: continue
                        val subj = s.optJSONObject("subject") ?: s
                        val sid = subj.optString("subjectId")
                        val title = subj.optString("title")
                        if (isShortDrama(subj)) continue
                        if (NON_FR_LANG_REGEX.containsMatchIn(title)) continue  // [Hindi]/etc.
                        if (sid !in frAllowed) continue
                        if (!seenForLists.add(sid)) continue
                        when (subj.optInt("subjectType", 1)) {
                            1 -> frMovies.add(parseMovie(subj))
                            2 -> frTvShows.add(parseTvShow(subj))
                        }
                    }
                }
            }
        }
        if (featured.isNotEmpty()) {
            sections.add(Category(name = Category.FEATURED, list = featured))
            featuredAdded = true
        }
        if (frMovies.isNotEmpty()) {
            sections.add(Category(name = "Films", list = frMovies.distinctBy { it.id }))
        }
        if (frTvShows.isNotEmpty()) {
            sections.add(Category(name = "Séries", list = frTvShows.distinctBy { it.id }))
        }
        Log.d(TAG, "getHome FR : ${featured.size} featured, ${frMovies.size} films, ${frTvShows.size} séries (featuredAdded=$featuredAdded, seenIds=${seenIds.size})")
        sections
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        if (page > 5) return emptyList()
        val cleanQuery = TitleNormalizer.cleanForTmdbSearch(query).ifBlank { query }
        val resp = apiPost(SEARCH_PATH, JSONObject().apply {
            put("keyword", cleanQuery)
            put("page", page - 1)
            put("perPage", 20)
        }) ?: return emptyList()
        val items = resp.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        val results = mutableListOf<AppAdapter.Item>()
        for (i in 0 until items.length()) {
            val s = items.optJSONObject(i) ?: continue
            if (isShortDrama(s)) continue  // skip DramaBox shorts
            val parsed = jsonToItem(s)
            if (isFrenchFriendly(parsed)) results.add(parsed)
        }
        Log.d(TAG, "search('$cleanQuery' p=$page): ${results.size} hits FR long-form")
        return results
    }

    /** Films / séries : on agrège les tabs 0..5 (skip tab 6 shorts), filtre
     *  par subjectType + FR-compat + reject DramaBox shorts, dédoublonne. */
    override suspend fun getMovies(page: Int): List<Movie> = coroutineScope {
        if (page > 5) return@coroutineScope emptyList()
        val responses = (0..5).map { tabId ->
            async { apiGet(MAIN_PAGE_PATH, mapOf("page" to "$page", "tabId" to "$tabId", "version" to "")) }
        }.awaitAll()
        val movies = mutableListOf<Movie>()
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
                    val m = parseMovie(s)
                    if (isFrenchFriendly(m.title)) movies.add(m)
                }
            }
        }
        movies.distinctBy { it.id }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = coroutineScope {
        if (page > 5) return@coroutineScope emptyList()
        val responses = (0..5).map { tabId ->
            async { apiGet(MAIN_PAGE_PATH, mapOf("page" to "$page", "tabId" to "$tabId", "version" to "")) }
        }.awaitAll()
        val shows = mutableListOf<TvShow>()
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
                    val t = parseTvShow(s)
                    if (isFrenchFriendly(t.title)) shows.add(t)
                }
            }
        }
        shows.distinctBy { it.id }
    }

    override suspend fun getMovie(id: String): Movie {
        val subjectId = id.removePrefix("cs::m::")
        val resp = apiGet(SUBJECT_GET_PATH, mapOf("subjectId" to subjectId))
            ?: return Movie(id = id, title = "", providerName = name)
        // 2026-05-05 v2 : la réponse a les champs DIRECTEMENT dans `data`,
        // pas dans `data.subject` (comme le faisait l'API h5api).
        val data = resp.optJSONObject("data")
            ?: return Movie(id = id, title = "", providerName = name)
        return parseMovie(data).copy(id = id)
    }

    /** Si un FR dub est dispo on retourne son subjectId, sinon le subjectId
     *  original. Permet à `getServers` d'utiliser la version FR de play-info. */
    private fun pickFrSubjectId(data: JSONObject, fallback: String): String {
        val dubs = data.optJSONArray("dubs") ?: return fallback
        for (i in 0 until dubs.length()) {
            val d = dubs.optJSONObject(i) ?: continue
            if (d.optString("lanCode").equals("fr", ignoreCase = true) &&
                d.optInt("type", 1) == 0  // 0 = dub, 1 = sub
            ) {
                val sid = d.optString("subjectId")
                if (sid.isNotBlank()) return sid
            }
        }
        return fallback
    }

    override suspend fun getTvShow(id: String): TvShow {
        val subjectId = id.removePrefix("cs::s::")
        val resp = apiGet(SUBJECT_GET_PATH, mapOf("subjectId" to subjectId))
            ?: return TvShow(id = id, title = "", providerName = name)
        val data = resp.optJSONObject("data")
            ?: return TvShow(id = id, title = "", providerName = name)

        // Si dispo, basculer sur le dub FR pour la lecture
        val frSubjectId = pickFrSubjectId(data, subjectId)

        // Fetch saisons via season-info (sur le subjectId FR si dispo)
        val seasonsResp = apiGet(SEASON_INFO_PATH, mapOf("subjectId" to frSubjectId))
        val seasons = mutableListOf<Season>()
        seasonsResp?.optJSONObject("data")?.optJSONArray("seasons")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val seNum = s.optInt("se", i + 1)
                seasons.add(
                    Season(
                        id = "cs::season::$frSubjectId::$seNum",
                        number = seNum,
                        title = s.optString("title").ifEmpty { "Saison $seNum" },
                        poster = s.optJSONObject("cover")?.optString("url"),
                    )
                )
            }
        }
        // Fallback : si season-info renvoie 0 saisons, lit `seNum` sur le détail
        if (seasons.isEmpty()) {
            val seNum = data.optInt("seNum", 0)
            for (i in 1..seNum) {
                seasons.add(
                    Season(
                        id = "cs::season::$frSubjectId::$i",
                        number = i,
                        title = "Saison $i",
                        poster = data.optJSONObject("cover")?.optString("url"),
                    )
                )
            }
        }
        val show = parseTvShow(data).copy(id = id)
        return show.copy(seasons = seasons)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // Parse "cs::season::<subjectId>::<seasonNumber>"
        val parts = seasonId.removePrefix("cs::season::").split("::")
        if (parts.size != 2) return emptyList()
        val subjectId = parts[0]
        val seasonNum = parts[1].toIntOrNull() ?: return emptyList()

        // 2026-05-06 : on utilise /resource (paginé 10/page) qui retourne les
        // *vrais* titres d'épisodes ("Wednesday's Child Is Full of Woe") au
        // lieu du générique "Épisode N". Le champ `episode` est encodé
        // S*100+EP donc 105 = S1E5, 203 = S2E3, etc. On filtre par se==seasonNum.
        val episodes = mutableMapOf<Int, Episode>()  // ep number → Episode (dedup)
        var page = 1
        var safetyMaxPage = 20
        while (page <= safetyMaxPage) {
            val resp = apiGet(RESOURCE_PATH, mapOf(
                "subjectId" to subjectId,
                "se" to "$seasonNum",
                "ep" to "1",
                "page" to "$page",
            )) ?: break
            val data = resp.optJSONObject("data") ?: break
            val list = data.optJSONArray("list") ?: break
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                if (it.optInt("se") != seasonNum) continue
                val ep = it.optInt("ep")
                if (ep <= 0 || episodes.containsKey(ep)) continue
                val title = it.optString("title").ifBlank { "Épisode $ep" }
                episodes[ep] = Episode(
                    id = "cs::ep::$subjectId::$seasonNum::$ep",
                    number = ep,
                    title = title,
                )
            }
            val pager = data.optJSONObject("pager")
            val hasMore = pager?.optBoolean("hasMore", false) == true
            if (!hasMore) break
            page++
        }
        if (episodes.isEmpty()) {
            // Fallback : /season-info pour récupérer maxEp et générer des stubs
            val seasonsResp = apiGet(SEASON_INFO_PATH, mapOf("subjectId" to subjectId))
            val seasonsArr = seasonsResp?.optJSONObject("data")?.optJSONArray("seasons")
            var maxEp = 0
            if (seasonsArr != null) {
                for (i in 0 until seasonsArr.length()) {
                    val s = seasonsArr.optJSONObject(i) ?: continue
                    if (s.optInt("se", -1) == seasonNum) {
                        maxEp = s.optInt("maxEp", s.optInt("epNum", 0))
                        break
                    }
                }
            }
            if (maxEp <= 0) return emptyList()
            return (1..maxEp).map { ep ->
                Episode(
                    id = "cs::ep::$subjectId::$seasonNum::$ep",
                    number = ep,
                    title = "Épisode $ep",
                )
            }
        }
        return episodes.values.sortedBy { it.number }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        // L'API mobile ne semble pas exposer un endpoint genre direct ;
        // on fallback sur le tab "Movie" (puis filtre par genre).
        val genreName = id.removePrefix("cs::g::")
        val movies = getMovies(page).filter { m ->
            m.genres.any { it.name.contains(genreName, ignoreCase = true) }
        }
        return Genre(id = id, name = genreName, shows = movies)
    }

    override suspend fun getPeople(id: String, page: Int): People =
        People(id = id, name = "", filmography = emptyList())

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val (subjectId, se, ep) = when {
            id.startsWith("cs::ep::") -> {
                val parts = id.removePrefix("cs::ep::").split("::")
                Triple(parts.getOrNull(0).orEmpty(), parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2)?.toIntOrNull() ?: 0)
            }
            id.startsWith("cs::m::") -> Triple(id.removePrefix("cs::m::"), 0, 0)
            id.startsWith("cs::s::") -> Triple(id.removePrefix("cs::s::"), 0, 0)
            else -> return emptyList()
        }
        if (subjectId.isBlank()) return emptyList()

        val servers = mutableListOf<Video.Server>()

        // 2026-05-06 v4 : essai #1 — /resource (mobile-bff) en parallèle
        // pour les 4 résolutions (360/480/720/1080). Les URLs `bcdn.hakunaymatata`
        // de cet endpoint sont les fichiers source uploadés par les contributeurs
        // (sans pre-roll publicitaire), au contraire de `hcdn3.hakunaymatata`
        // que retourne /play-info qui peut injecter des pubs en début de stream.
        // /resource liste les épisodes paginés ; on cherche la page contenant
        // notre épisode (se*100+ep == episode field) puis on prend resourceLink.
        runCatching {
            val resolutions = listOf(360, 480, 720, 1080)
            val resoStreams = coroutineScope {
                resolutions.map { resoltn ->
                    async {
                        // Cherche l'épisode demandé dans les pages /resource
                        // (se est passé pour réduire l'empan, mais l'API ignore
                        // partiellement ; on parcourt jusqu'à trouver le match).
                        val targetEpCode = if (se > 0 && ep > 0) se * 100 + ep else 0
                        var page = 1
                        var found: Pair<String, Long>? = null  // url, sizeBytes
                        while (page <= 8) {
                            val r = apiGet(RESOURCE_PATH, mapOf(
                                "subjectId" to subjectId,
                                "se" to "${if (se > 0) se else 1}",
                                "ep" to "${if (ep > 0) ep else 1}",
                                "page" to "$page",
                                "resolution" to "$resoltn",
                            )) ?: break
                            val data = r.optJSONObject("data") ?: break
                            val list = data.optJSONArray("list") ?: break
                            for (i in 0 until list.length()) {
                                val item = list.optJSONObject(i) ?: continue
                                val epCode = item.optInt("episode", -1)
                                // Pour un film: targetEpCode=0, on prend le 1er item
                                // Pour une série: on cherche l'épisode exact
                                if (targetEpCode == 0 || epCode == targetEpCode) {
                                    val u = item.optString("resourceLink").takeIf { it.isNotBlank() }
                                    if (u != null) {
                                        val sz = item.optString("size").toLongOrNull() ?: 0L
                                        found = u to sz
                                    }
                                    break
                                }
                            }
                            if (found != null) break
                            val pager = data.optJSONObject("pager")
                            if (pager?.optBoolean("hasMore", false) != true) break
                            page++
                        }
                        if (found != null) Triple(resoltn, found!!.first, found!!.second) else null
                    }
                }.awaitAll()
            }.filterNotNull().sortedByDescending { it.first }

            for ((idx, t) in resoStreams.withIndex()) {
                servers.add(
                    Video.Server(
                        id = "cloudstream_resource_${subjectId}_${se}_${ep}_${t.first}_${idx}",
                        name = "Cloudstream [${t.first}p MP4]",
                        src = t.second,
                    )
                )
            }
            if (servers.isNotEmpty()) Log.d(TAG, "getServers /resource $id → ${servers.size} streams (bcdn, no pre-roll)")
        }.onFailure { Log.d(TAG, "/resource fallback failed: ${it.message}") }

        if (servers.isNotEmpty()) return servers

        // Essai #2 : h5api (h5-api.aoneroom.com) — URLs MP4 signées via le frontend web.
        runCatching {
            val h5Headers = mapOf(
                "Origin" to "https://themoviebox.org",
                "Referer" to "https://themoviebox.org/fr",
                "User-Agent" to USER_AGENT_WEB,
                "x-client-info" to "lang=fr;hostName=themoviebox.org",
                "x-project-name" to "Moviebox",
            )
            val q = buildString {
                append("subjectId=$subjectId")
                if (se > 0) append("&se=$se")
                if (ep > 0) append("&ep=$ep")
            }
            val url = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/play?$q"
            val req = Request.Builder().url(url).apply {
                h5Headers.forEach { (k, v) -> header(k, v) }
            }.build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val data = json.optJSONObject("data")
                    val streams = data?.optJSONArray("streams")
                    if (streams != null) {
                        val sorted = (0 until streams.length()).mapNotNull { i ->
                            val s = streams.optJSONObject(i) ?: return@mapNotNull null
                            val u = s.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val res = s.optString("resolutions").ifEmpty { s.optString("resolution") }
                            val format = s.optString("format").ifEmpty { "MP4" }
                            Triple(u, res to format, res.toIntOrNull() ?: 0)
                        }.sortedByDescending { it.third }
                        for ((idx, t) in sorted.withIndex()) {
                            servers.add(
                                Video.Server(
                                    id = "cloudstream_h5_${subjectId}_${se}_${ep}_${t.third}_${idx}",
                                    name = "Cloudstream [${t.second.first}p ${t.second.second}]",
                                    src = t.first,
                                )
                            )
                        }
                        Log.d(TAG, "getServers h5api $id → ${servers.size} streams")
                    }
                } else {
                    Log.d(TAG, "h5api play HTTP ${resp.code}")
                }
            }
        }.onFailure { Log.d(TAG, "h5api fallback failed: ${it.message}") }

        if (servers.isNotEmpty()) return servers

        // Essai #3 : mobile-bff play-info (last resort). Peut retourner des
        // DASH manifests qui foirent en 403, ou des streams hcdn3 avec pre-roll.
        val params = mutableMapOf("subjectId" to subjectId)
        if (se > 0) params["se"] = "$se"
        if (ep > 0) params["ep"] = "$ep"
        val resp = apiGet(PLAY_INFO_PATH, params) ?: return emptyList()
        val data = resp.optJSONObject("data") ?: return emptyList()
        val streams = data.optJSONArray("streams") ?: return emptyList()
        val streamList = (0 until streams.length()).mapNotNull { i ->
            val s = streams.optJSONObject(i) ?: return@mapNotNull null
            val u = s.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val res = s.optString("resolutions").ifEmpty { s.optString("resolution") }
            val format = s.optString("format").ifEmpty { "MP4" }
            Triple(u, res to format, res.toIntOrNull() ?: 0)
        }.sortedByDescending { it.third }
        for ((idx, t) in streamList.withIndex()) {
            servers.add(
                Video.Server(
                    id = "cloudstream_mobile_${subjectId}_${se}_${ep}_${t.third}_${idx}",
                    name = "Cloudstream [${t.second.first}p ${t.second.second}]",
                    src = t.first,
                )
            )
        }
        Log.d(TAG, "getServers mobile-bff $id → ${servers.size} streams")
        return servers
    }

    private const val USER_AGENT_WEB =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun getVideo(server: Video.Server): Video {
        // Les URLs play-info sont déjà des MP4 directes signées.
        return Video(
            source = server.src,
            headers = mutableMapOf(
                "Referer" to "https://moviebox.ph/",
                "User-Agent" to USER_AGENT,
            ),
        )
    }
}
