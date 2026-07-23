package com.streamflixreborn.streamflix.utils

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Résolveur M6+ replay — port du pipeline `6play` de Catchup TV & More.
 *
 * Pipeline (URL M3U source = `m6play://<service>/<program_id>`) :
 *   1. App reçoit URL `m6play://<service>/<program_id>` depuis le M3U replay
 *   2. GET pc.middleware.6play.fr/.../{service}/programs/{program_id}/videos
 *      → liste des épisodes/vidéos disponibles → on prend la première (= la
 *      plus récente)
 *   3. GET pc.middleware.6play.fr/.../{service}/videos/{video_id}?with=clips
 *      → `clips[0].assets[0].full_physical_path` = URL MPD signée (signed
 *      token déjà inclus dans le query string `?ex=...`)
 *   4. Si `video_protection == "software"` → DRM Widevine activé. Pipeline DRM :
 *      a. POST front-auth.6cloud.fr/v2/platforms/m6group_web/getJwt
 *         { uid: account_id, token: gigya_login_token } → JWT M6
 *      b. POST drm.6cloud.fr/.../upfront-token avec JWT → upfront-token Widevine
 *      c. Widevine license URL = lic.drmtoday.com/license-proxy-widevine/cenc/
 *         avec header `x-dt-auth-token: upfront-token`
 *   5. PlayerMobileFragment / MiniPlayerController lit `getWidevineLicenseUrl
 *      (streamUrl)` pour configurer DrmSessionManager (= même pattern que TF1+).
 *
 * Note : compte M6+ requis pour le DRM. Sans login → pas de license → MPD
 * chiffré non décodable. Le catalogue lui est public, donc on peut tjr
 * lister/afficher les programmes.
 */
object M6Resolver {

    private const val TAG = "M6Resolver"
    private const val UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // === Endpoints ===
    private const val URL_VIDEOS_LIST =
        "https://pc.middleware.6play.fr/6play/v2/platforms/m6group_web/services/{SVC}/programs/{PROG}/videos?csa=6&with=clips,freemiumpacks&type=vi,vc,playlist&limit=10&offset=0"
    private const val URL_VIDEO_DETAIL =
        "https://pc.middleware.6play.fr/6play/v2/platforms/m6group_web/services/{SVC}/videos/{VID}?csa=6&with=clips,freemiumpacks"
    private const val URL_GET_JWT =
        "https://front-auth.6cloud.fr/v2/platforms/m6group_android/getJwt"
    // Upfront token replay : /customers/m6web/platforms/m6group_web/services/m6replay/users/<uid>/videos/<vid>/upfront-token
    //   Note : `m6replay` est le service générique pour les replays (NOT le service de la chaîne).
    private const val URL_UPFRONT_REPLAY =
        "https://drm.6cloud.fr/v1/customers/m6web/platforms/m6group_web/services/m6replay/users/{UID}/videos/{VID}/upfront-token"
    private const val WIDEVINE_LICENSE_URL =
        "https://lic.drmtoday.com/license-proxy-widevine/cenc/"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** Application context capturé au cold start, sert à lire M6Auth. */
    @Volatile private var appContextRef: android.content.Context? = null
    fun installContext(ctx: android.content.Context) {
        if (appContextRef == null) {
            appContextRef = ctx.applicationContext
        }
    }

    /** 2026-07-23 (testeur admin "sur Kodi mon compte n'a JAMAIS été déconnecté,
     *  sur ONYX je dois me reconnecter aléatoirement" — compte email/mdp Yopmail) :
     *  si le login_token Gigya est expiré/absent ET qu'on a des identifiants
     *  email/mdp persistés, on relance un login Gigya SILENCIEUX (M6GigyaAuth,
     *  HTTP rapide, pas de WebView) — exactement ce que fait Kodi catchuptvandmore.
     *  Aucun effet pour les comptes Google/Apple/FB (pas de mdp stocké → skip). */
    private suspend fun ensureFreshM6Token(ctx: android.content.Context) {
        try {
            val tok = M6Auth.getToken(ctx)
            if (tok.isNullOrBlank() && M6GigyaAuth.hasCredentials(ctx)) {
                Log.d(TAG, "ensureFreshM6Token: token expiré/absent → re-login Gigya silencieux")
                val fresh = M6GigyaAuth.reloginFromSaved(ctx)
                if (!fresh.isNullOrBlank()) Log.i(TAG, "ensureFreshM6Token: re-login silencieux OK")
                else Log.w(TAG, "ensureFreshM6Token: re-login échoué (identifiants invalides ?)")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ensureFreshM6Token: ${e.message}")
        }
    }

    fun isM6Url(url: String): Boolean = url.startsWith("m6play://", ignoreCase = true) ||
        url.startsWith("m6live://", ignoreCase = true)

    /** Cache mémoire stream URL → widevine license URL. Le PlayerMobileFragment
     *  / MiniPlayerController le consulte pour configurer DrmSessionManager.
     *  Mappé sur le pattern TF1Resolver. */
    private val widevineLicenseCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun getWidevineLicenseUrl(streamUrl: String): String? = widevineLicenseCache[streamUrl]

    /** Cache des headers DRM (x-dt-auth-token) à passer avec la license request. */
    private val widevineHeadersCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    fun getWidevineHeaders(streamUrl: String): Map<String, String>? = widevineHeadersCache[streamUrl]

    data class Resolved(
        val url: String,
        val mimeType: String,
        val widevineLicenseUrl: String? = null,
        val widevineHeaders: Map<String, String>? = null,
    )

    suspend fun resolveTyped(m6Url: String): Resolved? {
        // 2026-07-23 : re-login Gigya silencieux si le token a expiré (couvre
        //   live ET replay) — comptes email/mdp seulement, no-op sinon.
        appContextRef?.let { ensureFreshM6Token(it) }
        // 2026-06-19 v38 (user "ajoute Live M6+ M6/W9/6ter/Gulli") : si scheme
        //   m6live:// → délègue à resolveLive (= pipeline live, différent du
        //   replay car endpoint /live au lieu de /videos).
        if (m6Url.startsWith("m6live://", ignoreCase = true)) {
            return resolveLive(m6Url)
        }
        val path = m6Url.removePrefix("m6play://").trim().removePrefix("/")
        if (path.isEmpty()) {
            Log.w(TAG, "Empty path in URL: $m6Url")
            return null
        }
        val parts = path.split("/", limit = 2)
        if (parts.size != 2) {
            Log.w(TAG, "Invalid m6play:// URL format: $m6Url (expected service/program_id)")
            return null
        }
        val service = parts[0]
        val idPart = parts[1]
        // 2026-06-19 : si l'id commence par "clip_", c'est un video_id direct
        //   (= ép. d'une série, on a déjà identifié l'épisode précis). Sinon
        //   c'est un program_id, on fetch la 1ère vidéo via /programs/{id}/videos.
        return if (idPart.startsWith("clip_")) {
            Log.d(TAG, "Direct video id detected: $idPart → resolveVideo")
            resolveVideo(service, idPart)
        } else {
            resolveProgram(service, idPart)
        }
    }

    /** Étape 1 : récupère la 1ère vidéo (= dernier épisode) d'un programme,
     *  puis appelle resolveVideo pour avoir l'URL MPD + DRM. */
    private suspend fun resolveProgram(service: String, programId: String): Resolved? {
        val listUrl = URL_VIDEOS_LIST
            .replace("{SVC}", service)
            .replace("{PROG}", programId)
        Log.d(TAG, "videos list for service=$service program=$programId")
        val listResp = try {
            httpGetJson(listUrl)
        } catch (e: Exception) {
            Log.e(TAG, "List fetch failed for $service/$programId: ${e.message}", e)
            return null
        } ?: return null
        // listResp est un JSONArray représenté comme {"$arr": [...]} car notre
        //   wrapper httpGetJson retourne un JSONObject. On stocke l'array sous "$arr".
        val arr = listResp.optJSONArray("\$arr") ?: run {
            Log.w(TAG, "No videos array for $service/$programId")
            return null
        }
        if (arr.length() == 0) {
            Log.w(TAG, "Empty videos list for $service/$programId")
            return null
        }
        val firstVideo = arr.optJSONObject(0) ?: return null
        val videoId = firstVideo.optString("id", "")
        if (videoId.isBlank()) {
            Log.w(TAG, "First video has no id for $service/$programId")
            return null
        }
        Log.d(TAG, "First video id=$videoId title='${firstVideo.optString("title", "?")}'")
        return resolveVideo(service, videoId)
    }

    /** Étape 2 : pour un video_id donné, récupère l'URL MPD + configure DRM
     *  Widevine si DRM software. */
    private suspend fun resolveVideo(service: String, videoId: String): Resolved? {
        val detailUrl = URL_VIDEO_DETAIL
            .replace("{SVC}", service)
            .replace("{VID}", videoId)
        val detailResp = try {
            httpGetJson(detailUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Video detail fetch failed for $service/$videoId: ${e.message}", e)
            return null
        } ?: return null

        val clips = detailResp.optJSONArray("clips") ?: run {
            Log.w(TAG, "No 'clips' field for $service/$videoId")
            return null
        }
        if (clips.length() == 0) {
            Log.w(TAG, "Empty clips array for $service/$videoId")
            return null
        }
        val clip = clips.optJSONObject(0) ?: return null
        val assets = clip.optJSONArray("assets") ?: return null
        // Picker le meilleur asset : on cherche un asset DASH/CENC avec
        //   video_protection != "drm-cas". On préfère software (Widevine L3).
        var bestUrl: String? = null
        var bestProtection: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val type = a.optString("type", "")
            val protocol = a.optString("protocol", "")
            val protection = a.optString("video_protection", "")
            val mpdUrl = a.optString("full_physical_path", "")
            if (mpdUrl.isBlank()) continue
            // Prend le 1er asset DASH/CENC software (= jouable Widevine L3)
            if (type.contains("dashcenc", ignoreCase = true) ||
                protocol.contains("usp", ignoreCase = true) ||
                mpdUrl.contains(".mpd", ignoreCase = true)) {
                bestUrl = mpdUrl
                bestProtection = protection
                if (protection.equals("software", ignoreCase = true)) break
            }
        }
        if (bestUrl == null) {
            Log.w(TAG, "No usable asset for $service/$videoId")
            return null
        }
        val mime = "application/dash+xml"
        val needsDrm = bestProtection?.lowercase() in setOf("software", "hardware", "drm-cas")

        if (!needsDrm) {
            Log.d(TAG, "Resolved $service/$videoId (no DRM) → ${bestUrl.take(80)}...")
            return Resolved(bestUrl, mime, null, null)
        }

        // === DRM Widevine pipeline ===
        // 1. Récupère le JWT M6 via getJwt (besoin de M6Auth.token)
        val ctx = appContextRef
        if (ctx == null) {
            Log.w(TAG, "No app context, can't get DRM for $service/$videoId")
            return Resolved(bestUrl, mime, null, null)
        }
        // 2026-06-19 v3 : si pas de token en cache, tente une restauration
        //   silencieuse via M6UidResolver (= charge m6.fr/m6plus/direct en
        //   WebView headless qui intercepte AUSSI les responses /getJwt du
        //   SPA M6+ via JS hook fetch/XHR).
        var gigyaToken = com.streamflixreborn.streamflix.utils.M6Auth.getToken(ctx)
        var m6JwtCached = com.streamflixreborn.streamflix.utils.M6Auth.getM6Jwt(ctx)
        val needsResolve = gigyaToken.isNullOrBlank() ||
            (m6JwtCached.isNullOrBlank() || m6JwtCached!!.startsWith("GIGYA::"))
        if (needsResolve) {
            // 2026-06-22 fix : forceWebView=true car le JWT est manquant ou
            //   invalide. Sans forceWebView, resolveSync retourne l'accountId
            //   en cache en 15ms sans lancer la WebView → le JWT n'est jamais
            //   rafraîchi. Le SPA M6+ chargé par la WebView appelle /getJwt
            //   que le hook JS intercepte et sauve dans M6Auth.
            Log.d(TAG, "No M6 token or no valid M6 JWT, attempt restore via WebView headless (forceWebView)...")
            com.streamflixreborn.streamflix.utils.M6UidResolver
                .resolveSync(ctx, 30_000L, forceWebView = true)
            gigyaToken = com.streamflixreborn.streamflix.utils.M6Auth.getToken(ctx)
            m6JwtCached = com.streamflixreborn.streamflix.utils.M6Auth.getM6Jwt(ctx)
        }
        // v16 : si on a un JWT M6 valide (= eyJ... intercepté du SPA), on
        //   skip le check token Gigya. Le JWT M6 suffit pour upfront-token.
        val hasValidM6Jwt = m6JwtCached?.startsWith("eyJ") == true
        if (gigyaToken.isNullOrBlank() && !hasValidM6Jwt) {
            Log.w(TAG, "No M6 token + no valid M6 JWT, DRM will fail for $service/$videoId")
            return Resolved(bestUrl, mime, null, null)
        }
        // 2026-06-19 v2 : account_id (= UID Gigya) capturé au login via cookie
        //   gigya-mid_<apiKey>. Si pas dispo, fallback runtime sur
        //   login-gigya.m6.fr/accounts.getAccountInfo qui retourne le UID
        //   propre. Cache le résultat pour les calls suivants.
        var accountId = com.streamflixreborn.streamflix.utils.M6Auth.getAccountId(ctx)
        if (accountId.isNullOrBlank() && !gigyaToken.isNullOrBlank()) {
            Log.d(TAG, "No cached account_id, fetching from Gigya REST...")
            accountId = fetchGigyaUid(gigyaToken!!, ctx)
            if (accountId == null) {
                // 2026-06-19 v3 : fallback WebView headless. Gigya REST échoue
                //   avec 403005 "Unauthorized user" parce que le cookie glt_*
                //   n'est pas un login_token API direct. Mais le SDK Gigya
                //   côté m6.fr le reconnaît via getAccountInfo (= session
                //   restorée depuis les cookies du domaine m6.fr). On charge
                //   m6.fr dans une WebView headless qui appelle le SDK et
                //   retourne le UID via JavascriptInterface. Block max 15s.
                Log.d(TAG, "Gigya REST fail, fallback WebView headless...")
                accountId = com.streamflixreborn.streamflix.utils.M6UidResolver
                    .resolveSync(ctx, 15_000L)
            }
            if (!accountId.isNullOrBlank()) {
                com.streamflixreborn.streamflix.utils.M6Auth.saveAccountId(ctx, accountId)
                Log.d(TAG, "Cached account_id (len=${accountId.length})")
            } else {
                Log.w(TAG, "Could not resolve account_id, DRM will fail for $service/$videoId")
                return Resolved(bestUrl, mime, null, null)
            }
        }

        // 2. Récupère/cache le JWT M6 (= différent du token Gigya glt_*) via
        //   POST front-auth.6cloud.fr/v2/platforms/m6group_web/getJwt avec
        //   {uid, signature, signatureTimestamp}. C'est CE JWT qu'il faut
        //   passer en Authorization: Bearer pour upfront-token. Test sandbox
        //   confirme : sans JWT valide → HTTP 498 "Invalid JWT (invalid format)"
        //   sur l'endpoint upfront. Avec → HTTP 200 + upfront-token Widevine.
        val resolvedAccountId: String = accountId!!  // non-null garanti
        // v16 : on a déjà m6JwtCached chargé en haut. Si c'est un vrai eyJ...
        //   (= intercepté du SPA via JS hook), on l'utilise direct, pas
        //   besoin du pipeline fetchM6Jwt qui se prend 403 CloudFront.
        var m6Jwt = m6JwtCached
        if (m6Jwt?.startsWith("eyJ") != true) {
            // Pas de vrai JWT M6 → tente une nouvelle résolution
            if (m6Jwt.isNullOrBlank() || m6Jwt!!.startsWith("GIGYA::")) {
                Log.d(TAG, "No valid M6 JWT cached, trying via WebView headless (forceWebView)...")
                com.streamflixreborn.streamflix.utils.M6UidResolver
                    .resolveSync(ctx, 30_000L, forceWebView = true)
                m6Jwt = com.streamflixreborn.streamflix.utils.M6Auth.getM6Jwt(ctx)
            }
            // Si on a un id_token Gigya fallback, tente OkHttp/Cronet POST
            if (m6Jwt?.startsWith("GIGYA::") == true) {
                Log.d(TAG, "Got Gigya id_token from WebView, exchange via OkHttp/Cronet...")
                val newJwt = fetchM6Jwt(ctx, resolvedAccountId)
                if (!newJwt.isNullOrBlank()) {
                    m6Jwt = newJwt
                    com.streamflixreborn.streamflix.utils.M6Auth.saveM6Jwt(ctx, m6Jwt!!)
                }
            }
        }
        if (m6Jwt.isNullOrBlank() || m6Jwt!!.startsWith("GIGYA::")) {
            Log.w(TAG, "Could not obtain valid M6 JWT, DRM will fail for $service/$videoId")
            return Resolved(bestUrl, mime, null, null)
        }
        // 2026-06-22 : vérifier si le JWT est expiré AVANT d'appeler upfront-token.
        //   HTTP 498 "Invalid JWT (invalid time)" = JWT expiré côté serveur.
        //   On détecte via le claim "exp" du JWT (standard RFC 7519).
        if (isJwtExpired(m6Jwt)) {
            Log.d(TAG, "M6 JWT expired (exp claim), forcing refresh via WebView...")
            com.streamflixreborn.streamflix.utils.M6Auth.saveM6Jwt(ctx, "")
            // 2026-06-22 fix : forceWebView=true pour bypasser le early-return
            //   sur accountId en cache. Sans ça, resolveSync retournait en 15ms
            //   sans lancer la WebView → JWT jamais rafraîchi.
            com.streamflixreborn.streamflix.utils.M6UidResolver
                .resolveSync(ctx, 30_000L, forceWebView = true)
            m6Jwt = com.streamflixreborn.streamflix.utils.M6Auth.getM6Jwt(ctx)
            if (m6Jwt?.startsWith("eyJ") != true) {
                Log.w(TAG, "JWT refresh failed (expired), DRM will fail for $service/$videoId")
                return Resolved(bestUrl, mime, null, null)
            }
            Log.d(TAG, "JWT refreshed OK (len=${m6Jwt!!.length})")
        }
        Log.d(TAG, "Using M6 JWT (len=${m6Jwt!!.length}, prefix=${m6Jwt!!.take(10)})")
        // 3. Get upfront-token Widevine. videoId garde le préfixe "clip_" dans
        //   le path — test sandbox : sans clip_ → HTTP 404, avec → HTTP 498
        //   "Invalid JWT" (= path reconnu, manque juste l'auth).
        val upfrontUrl = URL_UPFRONT_REPLAY
            .replace("{UID}", resolvedAccountId)
            .replace("{VID}", videoId)
        var upfrontToken = try {
            fetchUpfrontToken(upfrontUrl, m6Jwt)
        } catch (e: Exception) {
            Log.e(TAG, "upfront-token fetch failed: ${e.message}", e)
            null
        }
        // 2026-06-22 : retry une fois si upfront-token échoue (JWT peut être
        //   techniquement non-expiré côté claim mais rejeté côté serveur pour
        //   clock skew ou révocation). On force un refresh WebView et retente.
        if (upfrontToken == null && m6Jwt?.startsWith("eyJ") == true) {
            Log.d(TAG, "upfront-token failed, retrying with fresh JWT...")
            com.streamflixreborn.streamflix.utils.M6Auth.saveM6Jwt(ctx, "")
            com.streamflixreborn.streamflix.utils.M6UidResolver
                .resolveSync(ctx, 30_000L, forceWebView = true)
            m6Jwt = com.streamflixreborn.streamflix.utils.M6Auth.getM6Jwt(ctx)
            if (m6Jwt?.startsWith("eyJ") == true) {
                upfrontToken = try {
                    fetchUpfrontToken(upfrontUrl, m6Jwt)
                } catch (e: Exception) {
                    Log.e(TAG, "upfront-token retry failed: ${e.message}", e)
                    null
                }
            }
        }
        if (upfrontToken == null) {
            Log.w(TAG, "No upfront-token, DRM lookup failed for $service/$videoId")
            return Resolved(bestUrl, mime, null, null)
        }
        val licenseUrl = WIDEVINE_LICENSE_URL
        val drmHeaders = mapOf(
            "x-dt-auth-token" to upfrontToken,
            "Content-Type" to "application/octet-stream",
            "User-Agent" to UA,
        )
        widevineLicenseCache[bestUrl] = licenseUrl
        widevineHeadersCache[bestUrl] = drmHeaders
        Log.d(TAG, "Resolved $service/$videoId (Widevine $bestProtection) → ${bestUrl.take(80)}... license=${licenseUrl.take(60)}")
        return Resolved(bestUrl, mime, licenseUrl, drmHeaders)
    }

    /** 2026-06-19 : appelle login-gigya.m6.fr/accounts.getAccountInfo avec le
     *  login_token Gigya pour récupérer le UID utilisateur. Endpoint public
     *  (= JSONP-style, mais aussi accepte format=json). */
    private fun fetchGigyaUid(gigyaToken: String, ctx: android.content.Context? = null): String? {
        // 2026-06-19 fix : l'apiKey peut différer selon le flow login (OAuth
        //   Google vs email/password). On utilise celui extrait du cookie
        //   glt_<apiKey> à la connexion (= aligné avec le token), fallback
        //   hardcoded si pas en cache.
        val apiKey = ctx?.let { com.streamflixreborn.streamflix.utils.M6Auth.getApiKey(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "3_hH5KBv25qZTd_sURpixbQW6a4OsiIzIEF2Ei_2H7TXTGLJb_1Hr4THKZianCQhWK"
        // L'URL-encode est nécessaire au cas où le token contient des
        //   caractères spéciaux (%) qui auraient été décodés par CookieManager.
        val encodedToken = try {
            java.net.URLEncoder.encode(gigyaToken, "UTF-8")
        } catch (_: Throwable) { gigyaToken }
        val url = "https://login-gigya.m6.fr/accounts.getAccountInfo?login_token=$encodedToken&apiKey=$apiKey&format=json"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Gigya getAccountInfo HTTP ${resp.code}")
                    return@use null
                }
                val body = resp.body?.string().orEmpty()
                val obj = JSONObject(body)
                val errorCode = obj.optInt("errorCode", -1)
                if (errorCode != 0) {
                    Log.w(TAG, "Gigya getAccountInfo errorCode=$errorCode msg='${obj.optString("errorMessage")}'")
                    return@use null
                }
                obj.optString("UID", "").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gigya fetchGigyaUid failed: ${e.message}", e)
            null
        }
    }

    /** 2026-06-22 : vérifie si un JWT M6 est expiré en lisant le claim "exp".
     *  Renvoie true si expiré ou dans les 60s de l'expiration (= marge sécurité).
     *  Renvoie false si pas de claim exp (= on tente quand même) ou si erreur parse. */
    private fun isJwtExpired(jwt: String?): Boolean {
        if (jwt == null || !jwt.startsWith("eyJ")) return true
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return true
            val payloadB64 = parts[1].replace('-', '+').replace('_', '/')
            val padded = payloadB64 + "=".repeat((4 - payloadB64.length % 4) % 4)
            val payloadBytes = android.util.Base64.decode(padded, android.util.Base64.NO_WRAP)
            val payloadJson = JSONObject(String(payloadBytes, Charsets.UTF_8))
            val exp = payloadJson.optLong("exp", 0L)
            if (exp == 0L) false  // pas de claim exp → on assume valide
            else (System.currentTimeMillis() / 1000) > (exp - 60)  // expiré ou <60s restantes
        } catch (_: Exception) { false }
    }

    /** 2026-06-19 : décode le payload du JWT upfront-token (= partie centrale
     *  base64) et extrait le champ `optData` qui contient le JSON
     *  {"merchant", "sessionId", "userId"}. C'est cette string qu'on doit
     *  utiliser comme `dt-custom-data` pour la license Widevine. */
    private fun extractOptDataFromJwt(jwt: String): String? {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            val payloadB64 = parts[1].replace('-', '+').replace('_', '/')
            val padded = payloadB64 + "=".repeat((4 - payloadB64.length % 4) % 4)
            val payloadBytes = android.util.Base64.decode(padded, android.util.Base64.NO_WRAP)
            val payloadJson = JSONObject(String(payloadBytes, Charsets.UTF_8))
            payloadJson.optString("optData", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "extractOptDataFromJwt failed: ${e.message}")
            null
        }
    }

    /** GET drm.6cloud.fr/v1/.../upfront-token avec le JWT M6 (= Authorization: Bearer).
     *  2026-06-19 v3 KODI PIPELINE : ajout des headers X-Customer-Name + X-Client-Release
     *  qui sont obligatoires (Kodi 6play.py les envoie systematiquement). Sans eux le
     *  serveur peut renvoyer 403 ou un token au mauvais format. */
    private fun fetchUpfrontToken(url: String, m6Jwt: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $m6Jwt")
            .header("X-Customer-Name", "m6web")
            .header("X-Client-Release", "5.103.3")
            // 2026-06-19 v6 : CloudFront sur drm.6cloud.fr aussi → Origin/Referer m6.fr
            .header("Origin", "https://www.m6.fr")
            .header("Referer", "https://www.m6.fr/")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "upfront HTTP ${resp.code} on ${url.take(80)}: ${body.take(150)}")
                return null
            }
            // 2026-06-19 : log le body brut pour diagnose format upfront-token
            Log.d(TAG, "upfront 200 body[:400]=${body.take(400)}")
            if (body.startsWith("{")) {
                return try {
                    val obj = JSONObject(body)
                    obj.optString("upfront_token", "").takeIf { it.isNotBlank() }
                        ?: obj.optString("token", "").takeIf { it.isNotBlank() }
                        ?: obj.optString("value", "").takeIf { it.isNotBlank() }
                        ?: obj.optString("authToken", "").takeIf { it.isNotBlank() }
                        ?: obj.optString("auth_token", "").takeIf { it.isNotBlank() }
                } catch (_: Throwable) { null }
            }
            return body.trim().takeIf { it.isNotBlank() }
        }
    }

    /** POST front-auth.6cloud.fr/v2/platforms/m6group_web/getJwt avec
     *  { idtoken, utc_offset, consents, gdpr_apply } → JWT M6. L'idtoken est
     *  le JWT Gigya obtenu via gigya.accounts.getJWT côté WebView (= différent
     *  du token glt_*). C'est ce JWT M6 qui sert d'Authorization: Bearer pour
     *  drm.6cloud.fr/.../upfront-token. */
    private fun fetchM6Jwt(ctx: android.content.Context, uid: String): String? {
        // Si M6UidResolver a retourné un fallback "GIGYA::<id_token>", on
        //   utilise cet id_token pour le POST OkHttp. Sinon, on tente de le
        //   relancer pour obtenir l'idtoken.
        val cached = com.streamflixreborn.streamflix.utils.M6Auth.getM6Jwt(ctx)
        val idtoken = if (cached?.startsWith("GIGYA::") == true) {
            cached.removePrefix("GIGYA::")
        } else {
            // Sans idtoken (= cas où la WebView n'a même pas fourni le fallback)
            //   on ne peut pas faire le POST avec un payload valide. Return null.
            Log.w(TAG, "No Gigya id_token, can't get M6 JWT (need WebView resolve)")
            return null
        }
        // 2026-06-19 (reverse APK fr.m6.m6replay v5.104.14) : le body /getJwt
        //   demande aussi UIDSignature + signatureTimestamp (= ce qui distingue
        //   un vrai JWT M6 du fallback GIGYA::). Ces 2 champs sont fournis par
        //   M6UidResolver (hook JS gigya.accounts.getJWT) → sauvés dans M6Auth.
        val payload = JSONObject().apply {
            put("idtoken", idtoken)
            put("utc_offset", "0")
            put("consents", org.json.JSONArray())
            put("gdpr_apply", false)
            // UID + signature (= captés via M6UidResolver). Sans ces 2 champs,
            //   le serveur retourne probablement le `GIGYA::` fallback.
            com.streamflixreborn.streamflix.utils.M6Auth.getUidSignature(ctx)?.let {
                put("UIDSignature", it)
            }
            com.streamflixreborn.streamflix.utils.M6Auth.getSignatureTimestamp(ctx)?.let {
                put("signatureTimestamp", it)
            }
        }
        // v13 : Cronet + cookies du WebView CookieManager (= ce qui différencie
        //   Chrome desktop user qui marche, de notre OkHttp/Cronet anonyme qui
        //   se prend 403). Le SDK Gigya côté m6.fr pose des cookies session
        //   6cloud.fr persistants après le 1er login + navigation video. Sans
        //   ces cookies, CloudFront WAF ne reconnaît pas la session.
        val bodyStr = payload.toString()
        val cookieMgr = android.webkit.CookieManager.getInstance()
        val cookieM6 = cookieMgr.getCookie("https://www.m6.fr/") ?: ""
        val cookie6cloud = cookieMgr.getCookie("https://front-auth.6cloud.fr/") ?: ""
        // Concat all cookies for the Cookie: header
        val cookieFull = listOf(cookieM6, cookie6cloud)
            .filter { it.isNotBlank() }
            .joinToString("; ")
        Log.d(TAG, "Cookies for getJwt: m6.fr len=${cookieM6.length}, 6cloud len=${cookie6cloud.length}")
        val cronetHeaders = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "fr-FR,fr;q=0.9,en;q=0.8",
            "Content-Type" to "application/json",
            "Origin" to "https://www.m6.fr",
            "Referer" to "https://www.m6.fr/",
            "Sec-Ch-Ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
        )
        if (cookieFull.isNotBlank()) {
            cronetHeaders["Cookie"] = cookieFull
        }
        val cronetResp = com.streamflixreborn.streamflix.utils.CronetPost.post(
            ctx,
            "https://front-auth.6cloud.fr/v2/platforms/m6group_android/getJwt",
            bodyStr,
            cronetHeaders,
            15_000L
        )
        if (cronetResp != null) {
            val (st, b) = cronetResp
            Log.d(TAG, "Cronet getJwt HTTP $st body[:80]=${b.take(80)}")
            if (st in 200..299 && b.isNotBlank()) {
                if (b.startsWith("{")) {
                    val obj = JSONObject(b)
                    return obj.optString("token", "").takeIf { it.isNotBlank() }
                        ?: obj.optString("jwt", "").takeIf { it.isNotBlank() }
                }
                return b.trim().takeIf { it.startsWith("eyJ") }
            }
            Log.w(TAG, "Cronet getJwt HTTP $st: ${b.take(120)}")
        } else {
            Log.w(TAG, "Cronet POST unavailable, fallback OkHttp")
        }

        // Fallback OkHttp (= probablement même résultat 403 mais on tente)
        val req = Request.Builder()
            .url("https://front-auth.6cloud.fr/v2/platforms/m6group_android/getJwt")
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Origin", "https://www.m6.fr")
            .header("Referer", "https://www.m6.fr/")
            .post(
                bodyStr.toRequestBody("application/json".toMediaType())
            )
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "OkHttp getJwt HTTP ${resp.code}: ${resp.body?.string()?.take(120)}")
                    return@use null
                }
                val body = resp.body?.string().orEmpty()
                Log.d(TAG, "OkHttp getJwt OK, body[:80]=${body.take(80)}")
                if (body.startsWith("{")) {
                    val obj = JSONObject(body)
                    obj.optString("token", "").takeIf { it.isNotBlank() }
                        ?: obj.optString("jwt", "").takeIf { it.isNotBlank() }
                } else {
                    body.trim().takeIf { it.isNotBlank() && it.startsWith("eyJ") }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchM6Jwt failed: ${e.message}", e)
            null
        }
    }

    suspend fun resolve(m6Url: String): String? = resolveTyped(m6Url)?.url

    /** 2026-06-19 v39 (user "Les Live M6 et W9 fonctionnent pas") :
     *  pipeline LIVE M6+ pour M6/W9/6ter/Gulli (= 4 gratuites en clair).
     *  2026-06-19 fix : reverse-engineering via DevTools Chrome desktop sur
     *  www.m6.fr/m6/direct → le SPA web charge le MPD à l'URL :
     *    https://edge-cf-m6web.live.6cloud.fr/out/v1/6play/6play-<chan>/cmaf_cenc00/dash-short-hd.mpd
     *  (profil `cmaf_cenc00`, file `dash-short-hd.mpd`). C'est un DASH+CENC
     *  PlayReady+Widevine. 2026-07-23 : l'ancien profil `cmaf_cenc00_N3` a été
     *  retiré côté 6cloud (403) → bascule sur `cmaf_cenc00` (cf. plus bas).
     *  Le license URL est lic.drmtoday.com mais le browser passe par un CDM
     *  helper (= invisible aux DevTools Network tab) avec un `x-dt-auth-token`
     *  que seule l'app native M6+ peut fournir. Sans PCAP de cette app, on ne
     *  peut pas jouer le live encrypted depuis Android. */
    suspend fun resolveLive(m6Url: String): Resolved? {
        val service = m6Url.removePrefix("m6live://").trim().removePrefix("/").lowercase()
        if (service.isEmpty()) {
            Log.w(TAG, "Empty service in URL: $m6Url")
            return null
        }
        // Map service vers le chan path utilisé par 6cloud
        val chanPath = when (service) {
            "m6"    -> "6play-m6"
            "w9"    -> "6play-w9"
            "6ter"  -> "6play-6ter"
            "gulli" -> "6play-gulli"
            else -> {
                Log.w(TAG, "resolveLive: unsupported live channel '$service'")
                return null
            }
        }
        // 2026-07-23 (user "M6 live W9 etc ne fonctionne plus") : le profil
        //   `cmaf_cenc00_N3` a été SUPPRIMÉ côté 6cloud → 403 (vérifié en direct :
        //   m6/w9 sur _N3 = 403, sur `cmaf_cenc00` = 200). Le player web M6+
        //   (www.m6.fr/m6/direct, migré depuis 6play.fr) charge désormais
        //   `.../6play-<chan>/cmaf_cenc00/dash-short-hd.mpd` (SANS `_N3`, sans
        //   token — manifeste public, la DRM Widevine reste le seul gate).
        val streamUrl = "https://edge-cf-m6web.live.6cloud.fr/out/v1/6play/$chanPath/cmaf_cenc00/dash-short-hd.mpd"
        val mime = "application/dash+xml"
        Log.d(TAG, "resolveLive: $service → $streamUrl")
        val ctx = appContextRef
        if (ctx == null) {
            Log.w(TAG, "resolveLive: no Context → ABORT")
            return null
        }
        // 2026-06-19 v41 OPTI : SKIP le WebView headless live (= ne marche
        //   jamais, ajoute 35s de latence inutile). On va direct au pipeline
        //   Kodi HTTP qui marche en ~1s si UID + JWT sont déjà cachés.
        //   On lance seulement M6UidResolver.resolveSync (= VOD pipeline, ~3s)
        //   si l'UID n'est pas en cache, pour récupérer l'UID via JS hook
        //   gigya.accounts.getJWT, mais on n'attend PAS le live live-trigger.
        Log.d(TAG, "resolveLive: skipping WebView live trigger (35s saved), going direct to Kodi HTTP pipeline")
        var accountId = com.streamflixreborn.streamflix.utils.M6Auth.getAccountId(ctx) ?: ""
        // 2026-06-19 fix : sur les flows OAuth Google le cookie
        //   `gigya-mid_<apiKey>` n'est pas posé (= seul le flow email/password
        //   le pose). On a quand même le glt_ token (gigyaToken) ; on appelle
        //   donc /accounts.getAccountInfo pour résoudre le UID et le cacher,
        //   comme on le fait pour les VOD (lignes ~242-260).
        if (accountId.isBlank()) {
            val gigyaToken = com.streamflixreborn.streamflix.utils.M6Auth.getToken(ctx)
            if (!gigyaToken.isNullOrBlank()) {
                Log.d(TAG, "resolveLive: no cached account_id, fetching from Gigya REST...")
                val fetched = fetchGigyaUid(gigyaToken, ctx)
                if (!fetched.isNullOrBlank()) {
                    accountId = fetched
                    com.streamflixreborn.streamflix.utils.M6Auth.saveAccountId(ctx, fetched)
                    Log.d(TAG, "resolveLive: Cached account_id (len=${fetched.length})")
                }
            }
        }
        // 2026-06-19 v2 fix : sur OAuth Google, le glt_ token est rejeté par
        //   Gigya getAccountInfo (errorCode 403005 "Unauthorized user").
        //   Mais le pipeline VOD M6UidResolver.resolveSync utilise un hook JS
        //   dans la WebView m6.fr qui appelle gigya.accounts.getJWT côté SDK
        //   (= privilégié) et récupère le UID directement. On l'appelle ici
        //   en fallback pour résoudre l'account_id même quand Gigya REST échoue.
        if (accountId.isBlank()) {
            Log.d(TAG, "resolveLive: Gigya REST failed, trying M6UidResolver (VOD pipeline) for UID")
            try {
                com.streamflixreborn.streamflix.utils.M6UidResolver.resolveSync(ctx, 30_000L)
                accountId = com.streamflixreborn.streamflix.utils.M6Auth.getAccountId(ctx) ?: ""
                if (accountId.isNotBlank()) {
                    Log.d(TAG, "resolveLive: M6UidResolver recovered account_id (len=${accountId.length})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolveLive: M6UidResolver failed: ${e.message}")
            }
        }
        if (accountId.isBlank()) {
            Log.w(TAG, "resolveLive: no M6 account_id → ABORT")
            return null
        }
        var m6Jwt = com.streamflixreborn.streamflix.utils.M6Auth.getM6Jwt(ctx)
        if (m6Jwt.isNullOrBlank() || m6Jwt.startsWith("GIGYA::")) {
            Log.d(TAG, "resolveLive: no fresh M6 JWT, trying M6UidResolver")
            try {
                com.streamflixreborn.streamflix.utils.M6UidResolver.resolveSync(ctx, 30_000L)
                m6Jwt = com.streamflixreborn.streamflix.utils.M6Auth.getM6Jwt(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "resolveLive: M6UidResolver failed: ${e.message}")
            }
        }
        if (m6Jwt.isNullOrBlank() || m6Jwt.startsWith("GIGYA::")) {
            Log.w(TAG, "resolveLive: still no valid M6 JWT for live $service → ABORT")
            return null
        }
        // 2026-06-19 v39 (suite) : pipeline LIVE M6+ équivalent à TF1+
        //   mediainfocombo. On appelle l'API play_resource de 6play middleware
        //   avec Bearer JWT M6, qui retourne le live asset + Widevine license.
        //   Endpoint trouvé par fuzz : services/<service>/play_resource
        val playResourceUrl = "https://pc.middleware.6play.fr/6play/v2/platforms/m6group_web/services/$service/play_resource"
        val playRespJson = try {
            httpPostBearerJson(playResourceUrl, m6Jwt!!)
        } catch (e: Exception) {
            Log.e(TAG, "resolveLive: play_resource HTTP error: ${e.message}", e)
            null
        }
        if (playRespJson != null) {
            // Parse play_resource response : { full_physical_path, drm_license_url, ... }
            val assets = playRespJson.optJSONArray("assets")
                ?: playRespJson.optJSONObject("\$arr")?.optJSONArray("assets")
            var apiStreamUrl: String? = null
            var apiLicenseUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val path = a.optString("full_physical_path", "").takeIf { it.startsWith("http") }
                        ?: a.optString("path", "").takeIf { it.startsWith("http") }
                        ?: continue
                    val prot = a.optString("protection_type", "")
                    if (apiStreamUrl == null || prot.contains("widevine", true)) {
                        apiStreamUrl = path
                        apiLicenseUrl = a.optString("drm_license_url", "").takeIf { it.startsWith("http") }
                            ?: a.optJSONObject("drm")?.optString("widevine_license_url")
                    }
                }
            }
            if (apiStreamUrl != null) {
                Log.d(TAG, "resolveLive: play_resource OK $service → ${apiStreamUrl.take(60)}")
                val finalUrl = apiStreamUrl
                val finalMime = if (finalUrl.contains(".mpd")) "application/dash+xml"
                                else "application/x-mpegURL"
                if (apiLicenseUrl != null) {
                    widevineLicenseCache[finalUrl] = apiLicenseUrl
                    val hdrs = mapOf(
                        "Authorization" to "Bearer $m6Jwt",
                        "User-Agent" to UA,
                    )
                    widevineHeadersCache[finalUrl] = hdrs
                    return Resolved(finalUrl, finalMime, apiLicenseUrl, hdrs)
                }
                return Resolved(finalUrl, finalMime, null, null)
            }
            Log.w(TAG, "resolveLive: play_resource returned no playable asset (json=${playRespJson.toString().take(300)})")
        }
        // 2026-06-19 v3 KODI PIPELINE (reverse plugin.video.catchuptvandmore 6play.py) :
        //   La pipeline qui marche RÉELLEMENT (Kodi 6play live sur tous devices L3) est :
        //   - Service = `6play` (constant, pas le code chaîne !)
        //   - Platform = `m6group_web` (= web, pas android/tv)
        //   - videoId = `dashcenc_<CHAN_UPPER>` (M6/W9/6T/GULLI)
        //   - Method = GET, V1 (= drm.6cloud.fr/v1)
        //   - License = juste `x-dt-auth-token` (= PAS de dt-custom-data !)
        //   - Headers en plus : X-Customer-Name=m6web, X-Client-Release=5.103.3
        val channelIdUpper = when (service) {
            "m6" -> "M6"
            "w9" -> "W9"
            "6ter" -> "6T"
            "gulli" -> "GULLI"
            else -> service.uppercase()
        }
        val upfrontPatterns = listOf(
            "https://drm.6cloud.fr/v1/customers/m6web/platforms/m6group_web/services/6play/users/$accountId/live/dashcenc_$channelIdUpper/upfront-token",
        )
        var upfrontToken: String? = null
        for (upfrontUrl in upfrontPatterns) {
            upfrontToken = try { fetchUpfrontToken(upfrontUrl, m6Jwt) } catch (e: Exception) { null }
            if (upfrontToken != null) {
                Log.d(TAG, "resolveLive: upfront-token OK via $upfrontUrl")
                break
            }
        }
        if (upfrontToken == null) {
            // 2026-06-19 v39 : pas de DRM token = lecture IMPOSSIBLE (= MPD
            //   CENC obligatoire). On retourne NULL au lieu de tenter de jouer
            //   (qui crashe Media3 MediaSession sur getBufferedPercentage live).
            //   Si le user n'a pas de compte M6+ → message clair "compte requis".
            Log.w(TAG, "resolveLive: no upfront-token for $service → ABORT (DRM required, no token)")
            return null
        }
        val licenseUrl = WIDEVINE_LICENSE_URL
        // 2026-06-19 v3 KODI PIPELINE : license DRMtoday avec UNIQUEMENT
        //   `x-dt-auth-token` (= PAS de dt-custom-data, supprimé). Kodi montre
        //   que DRMtoday accepte juste l'auth-token sans custom-data pour le
        //   pipeline 6play live. Le 403 / 20101 venait du fait qu'on ENVOYAIT
        //   un dt-custom-data malformé (= DRMtoday rejette si présent et invalide).
        val drmHeaders = mapOf(
            "x-dt-auth-token" to upfrontToken,
            "Content-Type" to "application/octet-stream",
            "User-Agent" to UA,
        )
        widevineLicenseCache[streamUrl] = licenseUrl
        widevineHeadersCache[streamUrl] = drmHeaders
        Log.d(TAG, "resolveLive: $service Widevine OK (x-dt-auth len=${upfrontToken.length})")
        return Resolved(streamUrl, mime, licenseUrl, drmHeaders)
    }


    /** 2026-06-19 v39 : POST avec Bearer M6 JWT. Body vide ou JSON minimal.
     *  Utilisé pour endpoints play_resource live qui nécessitent l'auth user. */
    private fun httpPostBearerJson(url: String, m6Jwt: String): JSONObject? {
        val emptyBody = okhttp3.RequestBody.create(
            "application/json; charset=utf-8".toMediaType(),
            "{}"
        )
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            .header("Authorization", "Bearer $m6Jwt")
            .header("Origin", "https://www.6play.fr")
            .header("Referer", "https://www.6play.fr/")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-site")
            .post(emptyBody)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Log.d(TAG, "POST $url → ${resp.code} (${body.length}b)")
            if (body.isBlank()) return null
            return try {
                if (body.trimStart().startsWith("[")) {
                    JSONObject().put("\$arr", JSONArray(body))
                } else {
                    JSONObject(body)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invalid JSON from POST $url: ${body.take(120)}")
                null
            }
        }
    }

    private fun httpGetJson(url: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                Log.w(TAG, "Empty body HTTP ${resp.code} for $url")
                return null
            }
            return try {
                // L'API M6 retourne soit un JSONObject (videos détail) soit un
                //   JSONArray (videos list). On normalise sur JSONObject avec
                //   l'array stocké sous la clé "$arr".
                if (body.trimStart().startsWith("[")) {
                    JSONObject().put("\$arr", JSONArray(body))
                } else {
                    JSONObject(body)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invalid JSON: ${body.take(120)}")
                null
            }
        }
    }
}
