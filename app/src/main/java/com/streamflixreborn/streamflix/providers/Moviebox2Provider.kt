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
 * 2026-05-05 : Second Moviebox provider qui consomme l'API MOBILE
 * (api6.aoneroom.com / api3 / api4 / api5 / api.inmoviebox.com — pool rotatif)
 * via le segment `/wefeed-mobile-bff/...` au lieu du `/wefeed-h5api-bff/...`
 * que MovieboxProvider utilise déjà.
 *
 * Endpoints :
 *   - /tab-operating?page=1&tabId=All       — feed home (vrais carousels)
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
 *
 * Le but est d'avoir un catalogue plus complet que la version H5 (qui se
 * limite au trending). L'API mobile expose en plus de vrais carousels par
 * tab (Movie/TV/Music/People/Education/Sports), des MPD streams pour
 * adaptive bitrate, et des sous-titres externes.
 */
object Moviebox2Provider : Provider {

    override val name = "Moviebox 2"
    override val baseUrl = "https://api6.aoneroom.com"
    override val language = "fr"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_moviebox"

    private const val TAG = "Moviebox2Provider"

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
            id = "mvbx2::m::$subjectId",
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
            id = "mvbx2::s::$subjectId",
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
            .map { Genre(id = "mvbx2::g::$it", name = it) }
    }

    /** subjectType : 1=Movie, 2=TvShow. */
    private fun jsonToItem(o: JSONObject): AppAdapter.Item {
        val type = o.optInt("subjectType", 1)
        return if (type == 1) parseMovie(o) else parseTvShow(o)
    }

    // ─── Provider impl ────────────────────────────────────────────────────

    /** Page d'accueil : un appel GET à `/tab-operating?tabId=0` retourne une
     *  liste d'`items` (= rangées). Chaque item a un `type` :
     *    - BANNER : promo carousel (data.banners[].subject)
     *    - SUBJECT_GROUP : rangée de subjects (subjects[])
     *    - VERTICAL_RANK : top X (subjects[])
     *  Il faut `tabId=0` (int) — `tabId=All` (str) renvoie 400. */
    override suspend fun getHome(): List<Category> {
        val resp = apiGet(MAIN_PAGE_PATH, mapOf("page" to "1", "tabId" to "0", "version" to ""))
            ?: return emptyList()
        val data = resp.optJSONObject("data") ?: return emptyList()
        val items = data.optJSONArray("items") ?: data.optJSONArray("topics") ?: return emptyList()
        val sections = mutableListOf<Category>()
        var featuredAdded = false

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val type = item.optString("type")
            val name = item.optString("title", "Section ${i + 1}")
            val sectionItems = mutableListOf<AppAdapter.Item>()

            when (type) {
                "BANNER" -> {
                    // banner.banners[].subject — typiquement les 5-10 promos
                    val banners = item.optJSONObject("banner")?.optJSONArray("banners")
                    if (banners != null) {
                        for (j in 0 until banners.length()) {
                            val b = banners.optJSONObject(j) ?: continue
                            val subject = b.optJSONObject("subject") ?: continue
                            val landscape = b.optJSONObject("image")?.optString("url")
                            val parsed = jsonToItem(subject)
                            // Override banner with the landscape image
                            sectionItems.add(when (parsed) {
                                is Movie -> parsed.apply { banner = landscape ?: this.banner }
                                is TvShow -> parsed.apply { banner = landscape ?: this.banner }
                                else -> parsed
                            })
                        }
                    }
                    if (sectionItems.isNotEmpty() && !featuredAdded) {
                        sections.add(Category(name = Category.FEATURED, list = sectionItems.take(10)))
                        featuredAdded = true
                    }
                    continue  // skip — already added as featured
                }
                else -> {
                    // SUBJECT_GROUP, VERTICAL_RANK, etc. → subjects[]
                    val subjects = item.optJSONArray("subjects")
                        ?: item.optJSONObject("group")?.optJSONArray("subjects")
                        ?: continue
                    for (j in 0 until subjects.length()) {
                        val s = subjects.optJSONObject(j) ?: continue
                        // Si l'item a un wrapper `subject`, prend l'enfant
                        val subj = s.optJSONObject("subject") ?: s
                        sectionItems.add(jsonToItem(subj))
                        if (sectionItems.size >= 20) break
                    }
                }
            }
            if (sectionItems.isNotEmpty()) {
                sections.add(Category(name = name, list = sectionItems))
            }
        }
        Log.d(TAG, "getHome: ${sections.size} sections (featured=$featuredAdded)")
        return sections
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
            results.add(jsonToItem(s))
        }
        Log.d(TAG, "search('$cleanQuery' p=$page): ${results.size} hits")
        return results
    }

    /** Pour les films/séries on récupère le tab All puis on filtre par
     *  subjectType (1=Movie, 2=TV). Les tabId int spécifiques (1=Music,
     *  2=People...) ne sont pas tous mappables à Movie/TV directement. */
    override suspend fun getMovies(page: Int): List<Movie> {
        if (page > 5) return emptyList()
        val resp = apiGet(MAIN_PAGE_PATH, mapOf("page" to "$page", "tabId" to "0", "version" to ""))
            ?: return emptyList()
        val items = resp.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        val movies = mutableListOf<Movie>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val subjects = it.optJSONArray("subjects") ?: continue
            for (j in 0 until subjects.length()) {
                val raw = subjects.optJSONObject(j) ?: continue
                val s = raw.optJSONObject("subject") ?: raw
                if (s.optInt("subjectType", 1) == 1) movies.add(parseMovie(s))
            }
        }
        return movies.distinctBy { it.id }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page > 5) return emptyList()
        val resp = apiGet(MAIN_PAGE_PATH, mapOf("page" to "$page", "tabId" to "0", "version" to ""))
            ?: return emptyList()
        val items = resp.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        val shows = mutableListOf<TvShow>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val subjects = it.optJSONArray("subjects") ?: continue
            for (j in 0 until subjects.length()) {
                val raw = subjects.optJSONObject(j) ?: continue
                val s = raw.optJSONObject("subject") ?: raw
                if (s.optInt("subjectType", 1) == 2) shows.add(parseTvShow(s))
            }
        }
        return shows.distinctBy { it.id }
    }

    override suspend fun getMovie(id: String): Movie {
        val subjectId = id.removePrefix("mvbx2::m::")
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
        val subjectId = id.removePrefix("mvbx2::s::")
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
                        id = "mvbx2::season::$frSubjectId::$seNum",
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
                        id = "mvbx2::season::$frSubjectId::$i",
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
        // Parse "mvbx2::season::<subjectId>::<seasonNumber>"
        val parts = seasonId.removePrefix("mvbx2::season::").split("::")
        if (parts.size != 2) return emptyList()
        val subjectId = parts[0]
        val seasonNum = parts[1].toIntOrNull() ?: return emptyList()

        // L'API mobile retourne le nombre d'épisodes via /resource. Sans ça,
        // on tente /season-info pour la liste d'épisodes connus.
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
                id = "mvbx2::ep::$subjectId::$seasonNum::$ep",
                number = ep,
                title = "Épisode $ep",
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        // L'API mobile ne semble pas exposer un endpoint genre direct ;
        // on fallback sur le tab "Movie" (puis filtre par genre).
        val genreName = id.removePrefix("mvbx2::g::")
        val movies = getMovies(page).filter { m ->
            m.genres.any { it.name.contains(genreName, ignoreCase = true) }
        }
        return Genre(id = id, name = genreName, shows = movies)
    }

    override suspend fun getPeople(id: String, page: Int): People =
        People(id = id, name = "", filmography = emptyList())

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val (subjectId, se, ep) = when {
            id.startsWith("mvbx2::ep::") -> {
                val parts = id.removePrefix("mvbx2::ep::").split("::")
                Triple(parts.getOrNull(0).orEmpty(), parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2)?.toIntOrNull() ?: 0)
            }
            id.startsWith("mvbx2::m::") -> Triple(id.removePrefix("mvbx2::m::"), 0, 0)
            id.startsWith("mvbx2::s::") -> Triple(id.removePrefix("mvbx2::s::"), 0, 0)
            else -> return emptyList()
        }
        if (subjectId.isBlank()) return emptyList()

        val servers = mutableListOf<Video.Server>()

        // 2026-05-05 v3 : essai #1 — l'API h5api (h5-api.aoneroom.com) qui
        // renvoie des URLs MP4 signées (?sign=X&t=Y). Plus fiable que les
        // DASH manifests du mobile-bff qui sortent en 403 silencieux.
        // L'API h5api ne nécessite pas de signing HMAC, juste les headers
        // simples Origin/Referer/x-client-info/x-project-name.
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
                                    id = "moviebox2_h5_${subjectId}_${se}_${ep}_${t.third}_${idx}",
                                    name = "Moviebox 2 [${t.second.first}p ${t.second.second}]",
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

        // Essai #2 : mobile-bff play-info (fallback). Souvent retourne des
        // DASH manifests qui peuvent foirer en 403, mais on tente quand même.
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
                    id = "moviebox2_mobile_${subjectId}_${se}_${ep}_${t.third}_${idx}",
                    name = "Moviebox 2 [${t.second.first}p ${t.second.second}]",
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
