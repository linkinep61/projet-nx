package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Résolveur RMC BFM Play — pipeline replay + live basé sur l'API Gaia-core.
 *
 * Pipeline (reverse-engineered via Kodi addon Catch-up TV & More) :
 *   1. URL custom `bfmplay://<productId>` depuis le M3U replay
 *   2. GET CDN options → récupère les options de lecture (audio/sous-titres)
 *   3. POST backend /replay/play avec token BFM → récupère entitlementId
 *   4. Construit la customdata DRM et retourne l'URL DASH + Widevine license
 *
 * Pour le live :
 *   1. URL custom `bfmlive://<channel>` (bfmtv, rmcstory, rmcdecouverte, etc.)
 *   2. POST backend /live/play avec token BFM → récupère stream URL + entitlementId
 *   3. Même pipeline DRM que le replay
 *
 * ⚠️ Les replays/lives nécessitent un compte RMC BFM Play connecté.
 */
object BfmResolver {

    private const val TAG = "BfmResolver"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; AndroidTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // API endpoints
    private const val API_CDN = "https://ws-cdn.tv.sfr.net/gaia-core/rest/api"
    private const val API_BACKEND = "https://ws-backendtv.rmcbfmplay.com/gaia-core/rest/api"
    private const val LICENSE_URL = "https://ws-backendtv.rmcbfmplay.com/asgard-drm-widevine/public/licence"

    // Endpoint service-list pour les streams live (comme dans l'addon Kodi)
    private const val SERVICE_LIST_URL = "https://ws-backendtv.rmcbfmplay.com/sekai-service-plan/public/v2/service-list"
    // Endpoint profils pour récupérer le nexttvId (accountId)
    private const val PROFILES_URL = "https://ws-backendtv.rmcbfmplay.com/heimdall-core/public/api/v2/userProfiles"

    // SSO endpoint pour créer un token via REST (alternative au WebView OIDC)
    private const val SSO_CREATE_TOKEN = "https://sso.rmcbfmplay.com/cas/services/rest/3.2/createToken.json"
    // Secret app BFM Play Android (base64 de "RMCBFMPlayAndroidv1:moebius1970")
    private const val APP_SECRET = "Basic Uk1DQkZNUGxheUFuZHJvaWR2MTptb2ViaXVzMTk3MA=="

    // Cache DRM par streamUrl (même pattern que M6Resolver)
    private val widevineLicenseCache = ConcurrentHashMap<String, String>()
    private val widevineHeadersCache = ConcurrentHashMap<String, Map<String, String>>()

    private var appContext: Context? = null

    // 2026-06-23 (user "quand le cookie a expiré l'option reconnexion devrait
    //   apparaître DIRECTEMENT, sinon on doit aller dans Paramètres") :
    //   au lieu d'un simple Toast "va dans Paramètres", on ouvre le dialog
    //   de re-connexion BFM PILE quand la lecture échoue pour cause de token
    //   expiré/manquant. Cooldown 8s pour éviter de spammer si plusieurs
    //   chaînes BFM sont sondées en série au démarrage.
    private var lastReconnectDialogAt = 0L
    private const val RECONNECT_DIALOG_COOLDOWN_MS = 8_000L
    private fun triggerReconnectDialog(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastReconnectDialogAt < RECONNECT_DIALOG_COOLDOWN_MS) return
        lastReconnectDialogAt = now
        val activity = com.streamflixreborn.streamflix.StreamFlixApp.currentActivity ?: return
        try {
            activity.runOnUiThread {
                try {
                    com.streamflixreborn.streamflix.activities.BfmLoginDialog.show(activity)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to show BFM reconnect dialog: ${t.message}")
                }
            }
        } catch (_: Throwable) {}
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun installContext(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /** True si l'URL est de la forme `bfmplay://<productId>` ou `bfmlive://<channel>`. */
    fun isBfmUrl(url: String): Boolean =
        url.startsWith("bfmplay://", ignoreCase = true) ||
            url.startsWith("bfmlive://", ignoreCase = true)

    /** Résultat de résolution : URL finale + DRM info. */
    data class Resolved(
        val url: String,
        val mimeType: String,
        val widevineLicenseUrl: String? = null,
        val widevineHeaders: Map<String, String>? = null
    )

    /**
     * Résout une URL `bfmplay://<productId>` ou `bfmlive://<channel>`.
     * Retourne null si la résolution échoue.
     */
    suspend fun resolveTyped(bfmUrl: String): Resolved? {
        val ctx = appContext ?: run {
            Log.e(TAG, "No app context — call installContext() first")
            return null
        }
        return when {
            bfmUrl.startsWith("bfmlive://", ignoreCase = true) -> {
                val channel = bfmUrl.removePrefix("bfmlive://").trim()
                resolveLive(ctx, channel)
            }
            bfmUrl.startsWith("bfmplay://", ignoreCase = true) -> {
                val productId = bfmUrl.removePrefix("bfmplay://").trim()
                resolveReplay(ctx, productId)
            }
            else -> null
        }
    }

    /** Variante compat : retourne juste l'URL. */
    suspend fun resolve(bfmUrl: String): String? = resolveTyped(bfmUrl)?.url

    // ── Lookup cache DRM (utilisé par PlayerFragment / MiniPlayerController) ──

    fun getWidevineLicenseUrl(streamUrl: String): String? = widevineLicenseCache[streamUrl]
    fun getWidevineHeaders(streamUrl: String): Map<String, String>? = widevineHeadersCache[streamUrl]

    // ── Helpers préfixe Product:: ──

    /**
     * L'API Gaia-core exige le préfixe "Product::" dans certains endpoints
     * (notamment /episodes), mais le M3U stocke les IDs bruts (sans préfixe).
     * Ce helper garantit que le préfixe est toujours présent.
     */
    private fun withProductPrefix(contentId: String): String =
        if (contentId.startsWith("Product::")) contentId else "Product::$contentId"

    // ── Résolution REPLAY ──

    /**
     * Pipeline replay corrigé 2026-06-21 d'après le code Kodi rmcbfmplay.py :
     *  1. GET $API_CDN/web/v2/content/{contentId}/options
     *     → JSON array, [0]["offers"][0] contient "offerId" + "streams" array
     *  2. Si vide (= ID de série/saison) → fallback /episodes pour récupérer
     *     le 1er épisode, puis re-tenter /options sur cet épisode-là.
     *     C'est exactement ce que fait le Kodi addon :
     *       contentType "Movie"|"Episode" → /options direct
     *       contentType autre (Series/Season) → /episodes d'abord
     *  3. POST $API_BACKEND/web/v1/replay/play → entitlementId
     *  4. customdata + headers DRM (Origin, Content-Type vide)
     */
    private suspend fun resolveReplay(ctx: Context, productId: String): Resolved? {
        var token = BfmAuth.getToken(ctx)
        if (token == null) {
            // Tenter le re-login automatique si on a des credentials sauvegardés
            if (BfmSsoAuth.hasCredentials(ctx)) {
                Log.d(TAG, "BFM token null — attempting auto-relogin…")
                token = BfmSsoAuth.reloginFromSaved(ctx)
            }
            if (token == null) {
                Log.w(TAG, "BFM not logged in — cannot resolve replay $productId")
                // 2026-06-23 (user "quand le cookie a expiré l'option reconnexion
                //   devrait apparaître DIRECTEMENT") : ouvre AUTOMATIQUEMENT le
                //   dialog BfmLoginDialog (avec son éventuel warning "connexion
                //   bloquée" si 3 fails) au lieu de juste un Toast. L'user
                //   tape son nouveau mot de passe sur place, plus besoin
                //   d'aller dans Paramètres.
                triggerReconnectDialog(ctx)
                return null
            }
        }

        // 1ère tentative directe (Movie/Episode)
        var resolved = resolveReplayDirect(token, productId, ctx)
        if (resolved != null) return resolved

        // Token peut-être expiré (403 GAIA_EXPIRED_TOKEN) → refresh et réessayer
        if (BfmSsoAuth.hasCredentials(ctx)) {
            Log.d(TAG, "First attempt failed for $productId — refreshing BFM token…")
            val freshToken = BfmSsoAuth.reloginFromSaved(ctx)
            if (freshToken != null && freshToken != token) {
                token = freshToken
                resolved = resolveReplayDirect(token, productId, ctx)
                if (resolved != null) return resolved
            } else if (freshToken == null) {
                // 2026-06-23 : le re-login auto a échoué (= probablement le
                //   password a changé côté BFM). Ouvre le dialog reconnexion
                //   pour que l'user retape ses nouveaux credentials sur place.
                Log.w(TAG, "Auto-relogin failed — credentials may have changed")
                triggerReconnectDialog(ctx)
            }
        }

        // Fallback : c'est peut-être un ID de série/saison → fetch episodes (avec token)
        Log.d(TAG, "Direct options empty for $productId — trying /episodes fallback…")
        val episodes = try { fetchEpisodes(productId, token) } catch (_: Exception) { null }
        if (episodes.isNullOrEmpty()) {
            Log.w(TAG, "No episodes found either for $productId — resolution failed")
            return null
        }
        Log.d(TAG, "Found ${episodes.size} episodes, resolving first: ${episodes[0].contentId}")

        // Résoudre le premier épisode avec le token frais
        return resolveReplayDirect(token, episodes[0].contentId, ctx)
    }

    /**
     * Résolution directe d'un contentId via /options + /replay/play.
     * Retourne null si /options est vide (= pas un Movie/Episode).
     */
    private fun resolveReplayDirect(token: String, contentId: String, ctx: Context): Resolved? {
        // Étape 1 : GET les options de lecture via CDN
        val fullId = withProductPrefix(contentId)
        val optionsUrl = "$API_CDN/web/v2/content/$fullId/options".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("app", "bfmrmc")
            ?.addQueryParameter("device", "browser")
            ?.addQueryParameter("token", token)
            ?.addQueryParameter("universe", "provider")
            ?.build()?.toString() ?: run {
            Log.e(TAG, "Failed to build options URL for $contentId")
            return null
        }

        Log.d(TAG, "Step 1 (options) GET $optionsUrl")
        val optionsResp = try {
            httpGetJsonArray(optionsUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Step 1 (options) failed for $contentId: ${e.message}")
            return null
        }

        if (optionsResp == null || optionsResp.length() == 0) {
            Log.d(TAG, "No options returned for $contentId (may be series/season)")
            return null
        }

        // Parse: resp[0]["offers"][0]["streams"] pour WIDEVINE + offerId
        val firstContent = optionsResp.optJSONObject(0)
        val firstOffer = firstContent?.optJSONArray("offers")?.optJSONObject(0)
        val offerId = firstOffer?.optString("offerId", "") ?: ""
        val streams = firstOffer?.optJSONArray("streams")

        var streamUrl: String? = null
        if (streams != null) {
            for (j in 0 until streams.length()) {
                val s = streams.optJSONObject(j) ?: continue
                val drm = s.optString("drm", "")
                if (drm.equals("WIDEVINE", ignoreCase = true)) {
                    streamUrl = s.optString("url", null)
                    break
                }
            }
        }

        if (streamUrl.isNullOrEmpty()) {
            Log.w(TAG, "No WIDEVINE stream for $contentId (offers=${firstContent?.optJSONArray("offers")?.length()}, streams=${streams?.length()})")
            return null
        }
        Log.d(TAG, "Step 1 OK: offerId=$offerId, streamUrl=${streamUrl.take(80)}...")

        // Étape 2 : POST /v1/replay/play pour obtenir l'entitlementId
        val playUrl = "$API_BACKEND/web/v1/replay/play".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("app", "bfmrmc")
            ?.addQueryParameter("device", "browser")
            ?.addQueryParameter("token", token)
            ?.build()?.toString() ?: run {
            Log.e(TAG, "Failed to build replay/play URL")
            return null
        }

        val playBody = JSONObject()
            .put("app", "bfmrmc")
            .put("device", "browser")
            .put("macAddress", "PC")
            .put("offerId", offerId)
            .put("token", token)
            .toString()

        Log.d(TAG, "Step 2 (replay/play) POST $playUrl body=$playBody")
        val playResp = try {
            httpPostJson(playUrl, playBody)
        } catch (e: Exception) {
            Log.e(TAG, "Step 2 (replay/play) failed for $contentId: ${e.message}")
            return null
        } ?: return null

        val entitlementId = playResp.optString("entitlementId", "")
        Log.d(TAG, "Step 2 OK: entitlementId=$entitlementId")

        // Étape 3 : construire la customdata pour Widevine
        val accountId = BfmAuth.getAccountId(ctx) ?: ""
        val customdata = buildCustomdata(token, accountId, "REPLAY", entitlementId)

        // Cache DRM — headers alignés avec Kodi (Origin + Content-Type vide)
        widevineLicenseCache[streamUrl] = LICENSE_URL
        widevineHeadersCache[streamUrl] = mapOf(
            "customdata" to customdata,
            "Origin" to "https://www.rmcbfmplay.com",
            "Content-Type" to "",
            "User-Agent" to UA
        )

        Log.d(TAG, "Resolved replay $contentId → ${streamUrl.take(80)}...")
        return Resolved(
            url = streamUrl,
            mimeType = "application/dash+xml",
            widevineLicenseUrl = LICENSE_URL,
            widevineHeaders = widevineHeadersCache[streamUrl]
        )
    }

    // ── Résolution LIVE ──

    /**
     * Mapping channel slug (bfmlive://xxx) → noms possibles dans la réponse service-list.
     * L'API retourne des noms variables ("BFM TV" ou "BFMTV"), on teste plusieurs variantes.
     */
    private val LIVE_CHANNELS = mapOf(
        "bfmtv"          to listOf("BFM TV", "BFMTV", "BFM"),
        "rmcstory"       to listOf("RMC Story", "RMC STORY"),
        "rmcdecouverte"  to listOf("RMC Découverte", "RMC DECOUVERTE", "RMC Decouverte"),
        "rmclife"        to listOf("RMC Life", "RMC LIFE"),
        "bfmbusiness"    to listOf("BFM Business", "BFM BUSINESS"),
        "techco"         to listOf("Tech & Co", "Tech&Co", "TECH & CO")
    )

    /**
     * Résout un live BFM via l'endpoint service-list (comme l'addon Kodi).
     * Le service-list retourne directement les URLs MPD + infos DRM sans passer par /live/play.
     */
    private suspend fun resolveLive(ctx: Context, channel: String): Resolved? {
        var token = BfmAuth.getToken(ctx)
        if (token == null) {
            if (BfmSsoAuth.hasCredentials(ctx)) {
                Log.d(TAG, "BFM token expired — attempting auto-relogin for live…")
                token = BfmSsoAuth.reloginFromSaved(ctx)
            }
            if (token == null) {
                Log.w(TAG, "BFM not logged in — cannot resolve live $channel")
                // 2026-06-23 : ouvre AUTO le dialog reconnexion (= mêmes
                //   rationale que resolveReplay).
                triggerReconnectDialog(ctx)
                return null
            }
        }

        // GET service-list avec le token en query param (URL-encodé proprement)
        val serviceListUrl = SERVICE_LIST_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("app", "bfmrmc")
            ?.addQueryParameter("device", "browser")
            ?.addQueryParameter("token", token)
            ?.build()
            ?.toString() ?: run {
            Log.e(TAG, "Failed to build service-list URL")
            return null
        }

        Log.d(TAG, "Fetching service-list for live $channel...")

        val serviceList = try {
            httpGetJsonArray(serviceListUrl)
        } catch (e: Exception) {
            Log.e(TAG, "service-list failed: ${e.message}")
            return null
        }

        if (serviceList == null || serviceList.length() == 0) {
            Log.w(TAG, "service-list returned empty or null")
            return null
        }

        Log.d(TAG, "service-list returned ${serviceList.length()} services")

        // Chercher le service correspondant au channel demandé
        val possibleNames = LIVE_CHANNELS[channel] ?: listOf(channel)
        var streamUrl: String? = null

        for (i in 0 until serviceList.length()) {
            val service = serviceList.optJSONObject(i) ?: continue
            val serviceName = service.optString("name", "")

            // Log tous les noms pour debug
            if (i < 10) Log.d(TAG, "  service[$i] name='$serviceName'")

            // Match insensible à la casse
            val matches = possibleNames.any { it.equals(serviceName, ignoreCase = true) }
            if (!matches) continue

            Log.d(TAG, "Found matching service: '$serviceName'")

            // Chercher le stream WIDEVINE
            val streams = service.optJSONArray("streams") ?: continue
            for (j in 0 until streams.length()) {
                val stream = streams.optJSONObject(j) ?: continue
                val drm = stream.optString("drm", "")
                val url = stream.optString("url", "")

                Log.d(TAG, "  stream[$j] drm='$drm' url=${url.take(60)}...")

                if (drm.equals("WIDEVINE", ignoreCase = true) && url.isNotEmpty()) {
                    streamUrl = url
                    break
                }
            }

            if (streamUrl != null) break
        }

        if (streamUrl == null) {
            // Log tous les noms pour aider au debug
            val allNames = (0 until serviceList.length()).mapNotNull {
                serviceList.optJSONObject(it)?.optString("name")
            }
            Log.w(TAG, "No matching service for '$channel'. Available: $allNames")
            return null
        }

        // Customdata pour live — accountId = "undefined" (comme Kodi)
        val customdata = buildCustomdata(token, "undefined", "LIVEOTT", null)

        widevineLicenseCache[streamUrl] = LICENSE_URL
        widevineHeadersCache[streamUrl] = mapOf(
            "customdata" to customdata,
            "Origin" to "https://www.rmcbfmplay.com",
            "Content-Type" to "",
            "User-Agent" to UA
        )

        Log.d(TAG, "Resolved live $channel → ${streamUrl.take(80)}...")
        return Resolved(
            url = streamUrl,
            mimeType = "application/dash+xml",
            widevineLicenseUrl = LICENSE_URL,
            widevineHeaders = widevineHeadersCache[streamUrl]
        )
    }

    // ── Listing épisodes pour séries BFM ──

    /**
     * Représente un épisode/item BFM retourné par l'API content/{id}/episodes.
     */
    data class BfmEpisode(
        val contentId: String,
        val title: String,
        val description: String?,
        val poster: String?,
        val contentType: String?  // "Movie", "Episode", ou autre
    )

    /**
     * 2026-06-21 : fetch la liste d'épisodes d'un contenu BFM (série, émission).
     * Utilise l'endpoint CDN comme le fait Kodi :
     *   GET $API_CDN/web/v1/content/{contentId}/episodes
     *   params: universe=PROVIDER, accountTypes=NEXTTV, operators=NEXTTV, page=0, size=1000
     *
     * @return liste d'épisodes, ou null si pas d'épisodes (= film/single).
     */
    fun fetchEpisodes(contentId: String, token: String? = null): List<BfmEpisode>? {
        val fullId = withProductPrefix(contentId)
        val builder = "$API_CDN/web/v1/content/$fullId/episodes".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("universe", "PROVIDER")
            ?.addQueryParameter("accountTypes", "NEXTTV")
            ?.addQueryParameter("operators", "NEXTTV")
            ?.addQueryParameter("noTracking", "false")
            ?.addQueryParameter("page", "0")
            ?.addQueryParameter("size", "1000")
        // Ajouter le token BFM — obligatoire pour les contenus non-FranceTV
        // (BFMTV, Ciné+ OCS, L'Equipe TV, Virgin17, etc.)
        // Sans token, le serveur retourne HTTP 500 au lieu de 403.
        if (!token.isNullOrBlank()) {
            builder?.addQueryParameter("app", "bfmrmc")
            builder?.addQueryParameter("device", "browser")
            builder?.addQueryParameter("token", token)
        }
        val url = builder?.build()?.toString() ?: return null

        Log.d(TAG, "fetchEpisodes GET $url")
        val resp = try {
            httpGetJson(url)
        } catch (e: Exception) {
            Log.w(TAG, "fetchEpisodes failed for $contentId: ${e.message}")
            return null
        } ?: return null

        // Kodi cherche dans : items, spots, content, tiles
        val items = resp.optJSONArray("items")
            ?: resp.optJSONArray("content")
            ?: resp.optJSONArray("tiles")
            ?: resp.optJSONArray("spots")
        if (items == null || items.length() == 0) {
            Log.d(TAG, "fetchEpisodes: no items for $contentId (keys: ${resp.keys().asSequence().toList()})")
            return null
        }

        val episodes = mutableListOf<BfmEpisode>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            // contentId de l'épisode = dans action.actionIds.contentId
            val epContentId = item.optJSONObject("action")
                ?.optJSONObject("actionIds")
                ?.optString("contentId", "")
                ?: ""
            // Fallback sur l'id direct de l'item si pas d'action
            val finalId = epContentId.ifEmpty { item.optString("id", "") }
            if (finalId.isEmpty()) continue

            val title = item.optString("title", "Épisode ${i + 1}")
            val description = item.optString("description", null)
            val contentType = item.optString("contentType", null)

            // Poster : préférer 2/3, sinon 16/9, sinon 1/1
            var poster: String? = null
            val images = item.optJSONArray("images")
            if (images != null) {
                for (j in 0 until images.length()) {
                    val img = images.optJSONObject(j) ?: continue
                    val format = img.optString("format", "")
                    val imgUrl = img.optString("url", "")
                    if (imgUrl.isEmpty()) continue
                    if (format == "2/3") { poster = imgUrl; break }
                    if (format == "16/9" && poster == null) poster = imgUrl
                    if (format == "1/1" && poster == null) poster = imgUrl
                }
            }

            episodes.add(BfmEpisode(finalId, title, description, poster, contentType))
        }

        Log.d(TAG, "fetchEpisodes $contentId: ${episodes.size} episodes found")
        return episodes.ifEmpty { null }
    }

    // ── Helpers ──

    /**
     * Construit la chaîne customdata pour le header de licence DRM.
     * Format inversé du Kodi addon rmcbfmplay.py (CUSTOMDATALIVE/CUSTOMDATAREPLAY).
     */
    private fun buildCustomdata(
        token: String,
        accountId: String,
        type: String,
        entitlementId: String?
    ): String {
        val sb = StringBuilder()
        sb.append("description=$UA&deviceId=byPassARTHIUS")
        sb.append("&deviceName=AndroidTV----ONYX")
        sb.append("&deviceType=AndroidTV")
        sb.append("&osName=Android&osVersion=14")
        sb.append("&persistent=false&resolution=1920x1080")
        sb.append("&tokenType=castoken")
        sb.append("&tokenSSO=$token")
        sb.append("&type=$type")
        sb.append("&accountId=$accountId")
        if (!entitlementId.isNullOrEmpty()) {
            sb.append("&entitlementId=$entitlementId")
        }
        return sb.toString()
    }

    /** Extrait la première URL DASH depuis la réponse /options. */
    private fun extractDashUrl(json: JSONObject): String? {
        // La réponse contient options[] → chaque option a streams[]
        val options = json.optJSONArray("options") ?: return null
        for (i in 0 until options.length()) {
            val opt = options.optJSONObject(i) ?: continue
            val streams = opt.optJSONArray("streams") ?: continue
            for (j in 0 until streams.length()) {
                val s = streams.optJSONObject(j) ?: continue
                if (s.optString("format", "").equals("dash", ignoreCase = true)) {
                    return s.optString("url", null)
                }
            }
        }
        return null
    }

    private fun httpGetJson(url: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                Log.w(TAG, "HTTP ${resp.code} for $url — ${errBody.take(200)}")
                return null  // Ne PAS parser le body d'erreur comme du contenu valide
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            return try { JSONObject(body) } catch (_: Exception) {
                Log.w(TAG, "Invalid JSON: ${body.take(120)}")
                null
            }
        }
    }

    private fun httpPostJson(url: String, jsonBody: String): JSONObject? {
        val mediaType = "application/json".toMediaType()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody(mediaType))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} POST $url → ${body.take(200)}")
            }
            if (body.isBlank()) return null
            return try { JSONObject(body) } catch (_: Exception) {
                Log.w(TAG, "Invalid JSON: ${body.take(120)}")
                null
            }
        }
    }

    /** GET qui parse une réponse JSON Array (pour service-list). */
    private fun httpGetJsonArray(url: String): JSONArray? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Content-type", "application/json")
            .header("Accept", "application/json, text/plain, */*")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Log.d(TAG, "httpGetJsonArray HTTP ${resp.code}, body length=${body.length}")
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} GET → ${body.take(500)}")
                return null
            }
            if (body.isBlank()) {
                Log.w(TAG, "Empty body from service-list")
                return null
            }
            // Log le début de la réponse pour debug
            Log.d(TAG, "Response preview: ${body.take(300)}")
            return try { JSONArray(body) } catch (_: Exception) {
                // Peut-être un JSONObject qui wrap un array
                try {
                    val obj = JSONObject(body)
                    val arr = obj.optJSONArray("services") ?: obj.optJSONArray("items")
                        ?: obj.optJSONArray("data")
                    if (arr == null) {
                        Log.w(TAG, "JSONObject keys: ${obj.keys().asSequence().toList()}")
                    }
                    arr
                } catch (_: Exception) {
                    Log.w(TAG, "Invalid JSON array: ${body.take(300)}")
                    null
                }
            }
        }
    }
}
