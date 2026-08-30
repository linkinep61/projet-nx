package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.extractors.Extractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
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
 * 2026-07-10 : client AUTONOME pour l'API MOBILE d'aoneroom (le backend de MovieBox).
 *
 * Découplé de tout provider (notamment CloudstreamProvider) : MovieboxProvider joue
 * SES propres flux via ce client, sans dépendre d'aucun autre provider. C'est le SEUL
 * chemin qui passe en OkHttp (l'endpoint navigateur h5-api refuse OkHttp avec
 * hasResource=false quels que soient les headers — vérifié en direct). L'API mobile,
 * elle, accepte OkHttp dès qu'elle est signée HMAC-MD5 (`x-tr-signature`) + Bearer JWT.
 *
 * Fournit :
 *   - [resolveStreamsBySubjectId] : streams (résolution, url) d'un subjectId PRÉCIS
 *     (pas de recherche par titre = pas une délégation).
 *   - [streamPlaybackHeaders] : headers de lecture pour le CDN hakunaymatata (Referer
 *     moviebox.ph + UA mobile + Bearer), sinon le CDN renvoie 403/429.
 */
object AoneroomClient {
    private const val TAG = "AoneroomClient"

    private val HOST_POOL = listOf(
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api7.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api.aoneroom.com",
        "https://api.inmoviebox.com",
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

    private const val PLAY_INFO_PATH = "/wefeed-mobile-bff/subject-api/play-info"
    private const val SEARCH_PATH = "/wefeed-mobile-bff/subject-api/search/v2"
    private const val TAB_OPERATING_PATH = "/wefeed-mobile-bff/tab-operating"

    private const val SECRET_KEY_DEFAULT_B64 = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
    private val SECRET_KEY_BYTES: ByteArray by lazy {
        android.util.Base64.decode(
            SECRET_KEY_DEFAULT_B64.padEnd(((SECRET_KEY_DEFAULT_B64.length + 3) / 4) * 4, '='),
            android.util.Base64.DEFAULT,
        )
    }

    private const val USER_AGENT =
        "com.community.oneroom/50020045 (Linux; U; Android 13; en_US; 23078RKD5C; Build/TQ2A.230405.003; Cronet/135.0.7012.3)"

    /** device_id 16 chars hex stable par install (fichier prefs PROPRE à ce client). */
    private val persistedDeviceId: String by lazy {
        val prefs = StreamFlixApp.instance.applicationContext
            .getSharedPreferences("moviebox_aoneroom", Context.MODE_PRIVATE)
        val existing = prefs.getString("device_id_v1", null)
        if (existing != null && existing.length == 16 && existing.all { it.isDigit() || it in 'a'..'f' }) {
            existing
        } else {
            val bytes = ByteArray(8)
            java.security.SecureRandom().nextBytes(bytes)
            val newId = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("device_id_v1", newId).apply()
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

    private val httpClient: OkHttpClient by lazy {
        Extractor.sharedClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // MovieBox+ renvoie 407 pour les erreurs d'auth → OkHttp lance une
            //   ProtocolException sans proxy. On convertit 407 → 477 pour le gérer.
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 407) response.newBuilder().code(477).build() else response
            }
            .build()
    }

    private val API_NO_CACHE = CacheControl.Builder().noCache().noStore().build()

    @Volatile private var lastGoodHost: String? = null
    @Volatile private var bearerToken: String? = null

    private fun orderedHosts(): List<String> {
        val good = lastGoodHost
        return if (good != null && HOST_POOL.contains(good)) {
            listOf(good) + HOST_POOL.filterNot { it == good }
        } else HOST_POOL
    }

    // ── Signature HMAC-MD5 ────────────────────────────────────────────────
    private fun generateXClientToken(tsMs: Long): String {
        val ts = tsMs.toString()
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(ts.reversed().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$ts,$md5"
    }

    private fun sortedQueryString(query: String): String {
        if (query.isEmpty()) return ""
        return query.split("&").mapNotNull {
            val eq = it.indexOf("=")
            if (eq < 0) it to "" else it.substring(0, eq) to it.substring(eq + 1)
        }.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun buildCanonicalString(
        method: String, accept: String?, contentType: String?, url: String, body: String?, tsMs: Long,
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

    private fun generateXTrSignature(method: String, accept: String, contentType: String, url: String, body: String?, tsMs: Long): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, tsMs)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SECRET_KEY_BYTES, "HmacMD5"))
        val sigBytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val sigB64 = android.util.Base64.encodeToString(sigBytes, android.util.Base64.NO_WRAP)
        return "$tsMs|2|$sigB64"
    }

    private fun signedHeaders(method: String, url: String, body: String? = null): Map<String, String> {
        val ts = System.currentTimeMillis()
        val accept = "application/json"
        val contentType = "application/json"
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

    // ── Bearer JWT ────────────────────────────────────────────────────────
    private fun captureBearer(resp: okhttp3.Response) {
        val xUser = resp.header("x-user") ?: return
        try {
            val token = JSONObject(xUser).optString("token", "")
            if (token.isNotBlank()) bearerToken = token
        } catch (_: Exception) {}
    }

    private suspend fun ensureBearer(): String? = withContext(Dispatchers.IO) {
        bearerToken?.let { return@withContext it }
        for (host in orderedHosts()) {
            val url = "$host$TAB_OPERATING_PATH?tab=home"
            try {
                val req = Request.Builder().url(url).cacheControl(API_NO_CACHE)
                    .apply { signedHeaders("GET", url).forEach { (k, v) -> header(k, v) } }.build()
                httpClient.newCall(req).execute().use {
                    captureBearer(it)
                    if (it.isSuccessful || it.code == 477) {
                        lastGoodHost = host
                        it.body?.string()
                        bearerToken?.let { tok -> return@withContext tok }
                    }
                }
            } catch (_: Exception) {}
        }
        null
    }

    private suspend fun apiGet(path: String, params: Map<String, String>): JSONObject? = withContext(Dispatchers.IO) {
        val query = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val pathWithQuery = if (query.isNotBlank()) "$path?$query" else path
        val token = ensureBearer()
        for (host in orderedHosts()) {
            val url = "$host$pathWithQuery"
            try {
                fun build(tok: String?) = Request.Builder().url(url).cacheControl(API_NO_CACHE)
                    .apply {
                        signedHeaders("GET", url).forEach { (k, v) -> header(k, v) }
                        if (tok != null) header("Authorization", "Bearer $tok")
                    }.build()
                httpClient.newCall(build(token)).execute().use {
                    val code = it.code
                    if (code == 441 || code == 477) {
                        bearerToken = null
                        val newToken = ensureBearer()
                        if (newToken != null) {
                            httpClient.newCall(build(newToken)).execute().use { r2 ->
                                if (r2.isSuccessful) {
                                    captureBearer(r2)
                                    lastGoodHost = host
                                    val body2 = r2.body?.string() ?: return@withContext null
                                    return@withContext JSONObject(body2)
                                }
                            }
                        }
                        return@use
                    }
                    if (code in setOf(403, 429, 500, 502, 503, 504)) return@use
                    if (!it.isSuccessful) return@withContext null
                    captureBearer(it)
                    val body = it.body?.string() ?: return@withContext null
                    lastGoodHost = host
                    return@withContext JSONObject(body)
                }
            } catch (_: Exception) {}
        }
        null
    }

    private suspend fun apiPost(path: String, jsonBody: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val bodyStr = jsonBody.toString()
        val token = ensureBearer() ?: return@withContext null
        for (host in orderedHosts()) {
            val url = "$host$path"
            try {
                fun build(tok: String) = Request.Builder().url(url).cacheControl(API_NO_CACHE)
                    .post(bodyStr.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
                    .apply {
                        signedHeaders("POST", url, bodyStr).forEach { (k, v) -> header(k, v) }
                        header("Authorization", "Bearer $tok")
                    }.build()
                httpClient.newCall(build(token)).execute().use {
                    val code = it.code
                    if (code == 441 || code == 477) {
                        bearerToken = null
                        val newToken = ensureBearer()
                        if (newToken != null) {
                            httpClient.newCall(build(newToken)).execute().use { r2 ->
                                if (r2.isSuccessful) {
                                    captureBearer(r2)
                                    lastGoodHost = host
                                    val b2 = r2.body?.string() ?: return@withContext null
                                    return@withContext JSONObject(b2)
                                }
                            }
                        }
                        return@use
                    }
                    if (code in setOf(403, 429, 500, 502, 503, 504)) return@use
                    if (!it.isSuccessful) return@withContext null
                    captureBearer(it)
                    val body = it.body?.string() ?: return@withContext null
                    lastGoodHost = host
                    return@withContext JSONObject(body)
                }
            } catch (_: Exception) {}
        }
        null
    }

    private fun normalizeForMatch(s: String): String = s.lowercase().trim()
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Recherche un titre via l'API mobile signée (search/v2) et renvoie le subjectId
     * du meilleur match (titre normalisé + année ±2). isMovie filtre le type (1=film, 2=série).
     * Renvoie null si aucun match fiable — on préfère RIEN à un mauvais film.
     */
    suspend fun findSubjectId(title: String, year: Int?, isMovie: Boolean): String? {
        val cleanQuery = title.trim()
        if (cleanQuery.isBlank()) return null
        val normQuery = normalizeForMatch(cleanQuery)
        val resp = apiPost(SEARCH_PATH, JSONObject().apply {
            put("keyword", cleanQuery); put("page", 1); put("perPage", 20)
        }) ?: return null
        val data = resp.optJSONObject("data")
        val items = mutableListOf<JSONObject>()
        data?.optJSONArray("results")?.let { results ->
            for (r in 0 until results.length()) {
                val subjects = results.optJSONObject(r)?.optJSONArray("subjects") ?: continue
                for (s in 0 until subjects.length()) subjects.optJSONObject(s)?.let { items.add(it) }
            }
        }
        if (items.isEmpty()) data?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
        }
        if (items.isEmpty()) return null

        val wantType = if (isMovie) 1 else 2
        data class C(val sid: String, val norm: String, val year: Int, val type: Int)
        val cands = items.mapNotNull { s ->
            val sid = s.optString("subjectId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val t = s.optString("title")
            val yr = s.optString("releaseDate").take(4).toIntOrNull() ?: 0
            C(sid, normalizeForMatch(t), yr, s.optInt("subjectType", 1))
        }.filter { it.type == wantType }
        if (cands.isEmpty()) return null

        // Match STRICT : titre normalisé EXACT (+ année exacte, puis ±2, puis n'importe).
        cands.firstOrNull { it.norm == normQuery && year != null && it.year == year }?.let { return it.sid }
        cands.firstOrNull { it.norm == normQuery && (year == null || kotlin.math.abs(it.year - year) <= 2) }?.let { return it.sid }
        cands.firstOrNull { it.norm == normQuery }?.let { return it.sid }

        // 2026-07-10 (user "War Machine ne matche pas — Moviebox décore ses titres") :
        //   Moviebox suffixe ses titres (« War Machine [Version française] », « … _360P »),
        //   donc l'égalité exacte échoue. On accepte le candidat qui CONTIENT TOUS les mots
        //   cherchés (query ⊆ candidat) AVEC année proche (±2) → décoration tolérée sans
        //   ouvrir la porte aux faux positifs (année + couverture 100% des mots requis).
        val qWords = normQuery.split(" ").filter { it.length > 1 }.toSet()
        if (qWords.isNotEmpty()) {
            cands.firstOrNull { c ->
                val cWords = c.norm.split(" ").filter { it.length > 1 }.toSet()
                cWords.containsAll(qWords) && (year == null || kotlin.math.abs(c.year - year) <= 2)
            }?.let { return it.sid }
            // Dernier recours : couverture ≥ 60 % des mots + année EXACTE (départage sûr).
            if (year != null) cands.firstOrNull { c ->
                val cWords = c.norm.split(" ").filter { it.length > 1 }.toSet()
                val shared = qWords.count { it in cWords }
                shared.toDouble() / qWords.size >= 0.6 && c.year == year
            }?.let { return it.sid }
        }
        return null
    }

    /** Streams (résolution, url) d'un subjectId précis, triés par qualité décroissante. */
    suspend fun resolveStreamsBySubjectId(subjectId: String, se: Int, ep: Int): List<Pair<Int, String>> {
        if (subjectId.isBlank()) return emptyList()
        val params = mutableMapOf("subjectId" to subjectId)
        if (se > 0) params["se"] = "$se"
        if (ep > 0) params["ep"] = "$ep"
        val resp = apiGet(PLAY_INFO_PATH, params) ?: return emptyList()
        // 2026-07-14 DIAG mauvaise-saison : ce que play-info renvoie réellement pour se/ep demandé.
        run {
            val d = resp.optJSONObject("data")
            val streamsN = d?.optJSONArray("streams")?.length() ?: 0
            android.util.Log.i("AoneroomClient", "MVBX-DIAG play-info sid=$subjectId demandé se=$se ep=$ep → streams=$streamsN | resp.se=${d?.opt("se")} resp.ep=${d?.opt("ep")} title='${d?.optString("title")}' epTitle='${d?.optString("episodeTitle")}' resource=${d?.opt("resourceId") ?: d?.opt("resource")}")
        }
        val streamArr = resp.optJSONObject("data")?.optJSONArray("streams") ?: return emptyList()
        val bruts = (0 until streamArr.length()).mapNotNull { i ->
            val s = streamArr.optJSONObject(i) ?: return@mapNotNull null
            val u = s.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // 2026-07-10 : le manifeste/segments sont protégés par CloudFront signed COOKIES,
            //   fournis ici dans `signCookie` (CloudFront-Policy/Signature/Key-Pair-Id). La Policy
            //   couvre tout le dossier (wildcard `/*`), donc UN cookie autorise manifeste + segments.
            //   On l'indexe par le PRÉFIXE de dossier de l'URL pour le retrouver au moment de lire
            //   (les segments ont une URL différente du manifeste mais le même dossier).
            // 2026-08-01 (user, « Moviebox français ne joue pas ») : la lecture échoue en
            //   HTTP 428 avec `cookie=false cacheSize=0` → le `signCookie` n'est JAMAIS
            //   trouvé, donc aucun cookie CloudFront n'accompagne la requête. Soit l'API a
            //   renommé le champ, soit il est ailleurs dans la réponse. On journalise les
            //   CLÉS du stream (noms seuls, aucune valeur : ce sont des jetons signés) pour
            //   savoir quoi lire, et on tolère les variantes de nommage les plus probables.
            val signCookie = sequenceOf("signCookie", "signCookies", "sign_cookie", "cookie", "cookies")
                .map { k -> s.optString(k) }
                .firstOrNull { it.isNotBlank() }
            if (i == 0) {
                android.util.Log.i(
                    "AoneroomClient",
                    "MVBX-STREAMKEYS " + s.keys().asSequence().joinToString(",") +
                        " | signCookieTrouve=${signCookie != null}",
                )
            }
            if (signCookie != null) cookieCache[cookieKey(u)] = normalizeCookie(signCookie)
            // `resolutions` est une LISTE (« 1080,720,480 ») : `toIntOrNull()` rendait null
            //   sur ce format, d'où les serveurs affichés « Moviebox MP4 » / [0p]. On prend la
            //   première valeur, qui est la plus haute. Même correctif que CloudstreamProvider.
            val res = s.optString("resolutions").substringBefore(',').trim()
                .ifEmpty { s.optString("resolution") }.toIntOrNull() ?: 0
            res to u
        }.sortedByDescending { it.first }
        val leurres = urlsLeurres(streamArr)
        if (leurres.isEmpty()) return bruts
        val gardes = bruts.filterNot { it.second in leurres }
        Log.w(TAG, "MVBX-LEURRE ${bruts.size - gardes.size} flux ecartes sur ${bruts.size} (sid=$subjectId)")
        return gardes
    }

    /**
     * 2026-08-01 (user : « le serveur Moviebox français ne joue pas ») : true si l'URL peut
     * réellement être lue.
     *
     * Le CDN `hakunaymatata` (CloudFront) EXIGE un cookie signé, fourni par `play-info` dans
     * `signCookie`. Vérifié en direct : le champ est bien présent dans la réponse mais sa
     * VALEUR est vide → aucun cookie en cache → le CDN répond **HTTP 428 (Precondition
     * Required)** et la lecture échoue, en 1080p comme en 480p.
     *
     * Plutôt que de retirer Moviebox en dur (le cookie peut revenir), on écarte seulement les
     * flux CloudFront pour lesquels aucun cookie n'est disponible : dès que l'API en renvoie
     * un de nouveau, les serveurs réapparaissent automatiquement. Les URLs d'autres hôtes
     * (mp4 directs) ne sont pas concernées.
     */
    // ─── Garde-fou anti-leurre (2026-08-29) ────────────────────────────────────
    //
    // Constat mesure ce jour : `/play-info` repond toujours 200 / code=0 / "ok" avec des
    // metadonnees credibles (size 537 Mo, 648 Mo, 2,44 Go, 4 resolutions, un signCookie de
    // 642 caracteres) mais l'`url` est LA MEME pour tous les films :
    //
    //   The Matrix ........... macdn.aoneroom.com/other/2026/08/11/1c7de0bd...f8d.mp4
    //   Matrix Revolutions ... la meme
    //   Asterix & Obelix ..... la meme
    //
    // et le fichier reel pese 5 595 350 octets (5,3 Mo, ftypqt) la ou l'API annonce 2,44 Go.
    // Autrement dit MovieBox a neutralise ses clients mobiles en gardant l'apparence du
    // succes : rien ne remonte en erreur, et sans ce filtre ONYX proposait des serveurs
    // VERTS qui lisaient un clip de 5 Mo a la place du film. Un mauvais film qui se lance
    // est pire qu'un serveur rouge.
    //
    // Trois regles, de la moins chere a la plus chere :
    //   1. empreinte connue        — gratuit, mais contournable s'ils changent de fichier
    //   2. meme URL sur plusieurs resolutions — gratuit, et structurellement anormal :
    //      un vrai flux a une URL par resolution
    //   3. taille reelle vs taille annoncee — un seul HEAD, mis en cache, et c'est la
    //      regle qui survit a une rotation d'empreinte
    private val LEURRES_CONNUS = listOf(
        "1c7de0bd3393702d9191801f15f88f8d",
    )

    /** Verdict par URL, pour ne sonder le reseau qu'une fois par session. */
    private val verdictLeurre = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * URLs de `streams[]` a ECARTER parce que ce sont des leurres. Ensemble vide = tout bon.
     */
    suspend fun urlsLeurres(streamArr: JSONArray): Set<String> {
        val n = streamArr.length()
        if (n == 0) return emptySet()

        data class Flux(val url: String, val taille: Long, val res: String)

        val flux = (0 until n).mapNotNull { i ->
            val s = streamArr.optJSONObject(i) ?: return@mapNotNull null
            val u = s.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Flux(u, s.optLong("size", 0L), s.optString("resolutions").ifEmpty { s.optString("resolution") })
        }
        if (flux.isEmpty()) return emptySet()

        val ecartees = mutableSetOf<String>()

        // Regle 1 — empreinte connue.
        for (f in flux) {
            if (LEURRES_CONNUS.any { f.url.contains(it, ignoreCase = true) }) {
                Log.w(TAG, "MVBX-LEURRE empreinte connue : ...${f.url.takeLast(45)}")
                ecartees += f.url
            }
        }

        // Regle 2 — une meme URL servie pour plusieurs resolutions distinctes.
        flux.groupBy { it.url }
            .filter { (_, g) -> g.size >= 2 && g.map { it.res }.distinct().size >= 2 }
            .forEach { (u, g) ->
                Log.w(TAG, "MVBX-LEURRE meme URL pour ${g.size} resolutions (${g.joinToString { it.res }})")
                ecartees += u
            }

        // Regle 3 — la taille reelle contredit la taille annoncee.
        for (f in flux.distinctBy { it.url }) {
            if (f.url in ecartees) continue
            if (f.taille < 50L * 1024 * 1024) continue  // rien d'annonce de credible : on ne sonde pas
            val connu = verdictLeurre[f.url]
            if (connu != null) {
                if (connu) ecartees += f.url
                continue
            }
            val reelle = tailleReelle(f.url)
            if (reelle <= 0L) { verdictLeurre[f.url] = false; continue }
            val leurre = reelle * 4 < f.taille   // moins du quart de ce qui est annonce
            verdictLeurre[f.url] = leurre
            if (leurre) {
                Log.w(TAG, "MVBX-LEURRE taille reelle $reelle o contre ${f.taille} o annonces : ...${f.url.takeLast(45)}")
                ecartees += f.url
            }
        }
        return ecartees
    }

    /** Content-Length reel du fichier, -1 si indeterminable. Un seul aller-retour. */
    private suspend fun tailleReelle(url: String): Long = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).head()
                .header("User-Agent", USER_AGENT)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            httpClient.newCall(req).execute().use { r ->
                val cl = r.header("Content-Length")?.toLongOrNull() ?: -1L
                if (cl > 0) return@withContext cl
            }
            // Certains CDN ignorent HEAD : on demande alors un octet et on lit le total.
            val req2 = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-0")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            httpClient.newCall(req2).execute().use { r ->
                val cr = r.header("Content-Range") ?: return@withContext -1L
                cr.substringAfter('/', "").trim().toLongOrNull() ?: -1L
            }
        } catch (e: Exception) {
            Log.d(TAG, "tailleReelle KO : ${e.javaClass.simpleName}")
            -1L
        }
    }

    fun isPlayable(url: String): Boolean {
        if (!url.contains("hakunaymatata", ignoreCase = true)) return true
        return cookieCache[cookieKey(url)] != null ||
            cookieCache.entries.any { url.startsWith(it.key) }
    }

    /** Cookies CloudFront signés, indexés par préfixe de dossier de l'URL du stream. */
    private val cookieCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Clé = dossier parent de l'URL (sans le fichier), pour matcher manifeste ET segments. */
    private fun cookieKey(url: String): String = url.substringBeforeLast('/', url)

    /** `A=..;B=..;` → `A=..; B=..` (header Cookie), trailing `;` retiré. */
    private fun normalizeCookie(raw: String): String =
        raw.trim().trimEnd(';').split(';').map { it.trim() }.filter { it.isNotBlank() }.joinToString("; ")

    /**
     * Headers de lecture pour le CDN hakunaymatata (sinon 403/429).
     * @param streamUrl si fourni, ajoute le Cookie CloudFront correspondant (obligatoire
     *   pour lire le manifeste DASH + ses segments).
     */
    suspend fun streamPlaybackHeaders(streamUrl: String? = null): Map<String, String> {
        // 2026-08-08 (user : « regarde Cloudstream 429 ») — REFERER RETIRÉ.
        //   Constaté sur « Cocorico » (subjectId 826759171835052664) : le serveur
        //   « Cloudstream [1080p] [VF] » passait au ROUGE avec, dans le log,
        //   `InvalidResponseCode: Response code: 428` puis `DIAG-403 … en-têtes de
        //   l'extracteur = {Referer=https://moviebox.ph/, User-Agent=…, Authorization=Bearer …}`.
        //   Le flux, lui, est parfaitement vivant. Test en isolant UN SEUL en-tête à la fois
        //   sur la MÊME URL (play-info fraîche, hcdn3.hakunaymatata.com) :
        //     aucun en-tête .................... 206  video/mp4   ✅
        //     User-Agent (celui-ci) seul ....... 206  video/mp4   ✅
        //     Authorization Bearer seule ....... 206  video/mp4   ✅
        //     Referer: https://moviebox.ph/ .... 429              ❌
        //     User-Agent + Referer ............. 429              ❌
        //     User-Agent + Referer + Bearer .... 429              ❌
        //   Le Referer est le seul en-tête qui fasse refuser le CDN, et son retrait suffit :
        //   la requête nue rend la vidéo immédiatement. Même famille que le piège upbolt
        //   (`; charset=utf-8` en trop) — un en-tête superflu qu'un serveur refuse.
        //   ⚠ Le Cookie CloudFront (plus bas) reste indispensable pour les contenus qui en
        //   fournissent un : c'est SON absence qui donne les vrais 428 (contenu premium
        //   verrouillé). Les deux causes sont distinctes, ne pas les confondre.
        //   Pour revenir en arrière : décommenter la ligne ci-dessous.
        val hdrs = mutableMapOf(
            // "Referer" to "https://moviebox.ph/",
            "User-Agent" to USER_AGENT,
        )
        val token = bearerToken ?: try { ensureBearer() } catch (_: Exception) { null }
        if (token != null) hdrs["Authorization"] = "Bearer $token"
        if (streamUrl != null) {
            (cookieCache[cookieKey(streamUrl)] ?: cookieCache.entries.firstOrNull {
                streamUrl.startsWith(it.key)
            }?.value)?.let { hdrs["Cookie"] = it }
        }
        // 2026-08-01 (user : « le serveur Moviebox français ne joue pas ») : la lecture
        //   échoue en HTTP 428 (Precondition Required). Or le Cookie CloudFront signé est
        //   OBLIGATOIRE pour ce CDN, et il n'est mis en cache que si le play-info a renvoyé
        //   un `signCookie`. Ce log dit lequel des 3 en-têtes manque au moment de lire —
        //   sans exposer les valeurs (tokens).
        android.util.Log.i(
            "AoneroomClient",
            "MVBX-PLAYHDR cookie=${hdrs.containsKey("Cookie")} bearer=${hdrs.containsKey("Authorization")} " +
                "cacheSize=${cookieCache.size} cle='${streamUrl?.let { cookieKey(it) }?.takeLast(40)}'",
        )
        return hdrs
    }
}
