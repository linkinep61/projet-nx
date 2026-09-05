package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Résolveur RMC+ (ex-RMC BFM Play) — directs et replays via le BFF du site.
 *
 * ── 2026-09-05 : RÉÉCRITURE COMPLÈTE (user : « il faut réparer la totalité ») ──────────
 * RMC BFM Play est devenu RMC+ (www.rmcplus.fr). L'ancien pipeline (SSO CAS → token BFM_ →
 * gaia-core /replay/play → licence asgard + customdata) est mort : le SSO répond 504 à tout
 * POST, mesuré dans l'app, dans Chrome et depuis un serveur neutre. Sauvegarde de l'ancien
 * code : `BfmResolver.kt.bak-rmcplus`.
 *
 * Nouveau pipeline, RELEVÉ SUR LE SITE avec une session connectée :
 *   • direct  : GET /api/bff/v1/page?model=web&page_type=direct&page_id=<chaine>
 *               → sections[type=player].video.stream.hls (+ .dash pour certaines) ;
 *   • replay  : GET /api/bff/v1/page?model=web&page_type=player&page_id=<id numérique>
 *               → sections[type=player].video.stream.{dash,hls} ;
 *   • DRM     : video.drm.play_token (JWT DRMtoday) + widevine.license_server_url
 *               (`lic.drmtoday.com/license-proxy-widevine/cenc/`) — même mécanique que M6+ :
 *               en-tête `x-dt-auth-token`, réponse JSON `{"license": base64}` décodée par
 *               [M6DrmCallback] (les lecteurs le choisissent dès qu'ils voient cet en-tête).
 *   • sans session, le BFF répond `authent: true` et ne donne AUCUN flux → on ouvre la
 *     reconnexion (WebView RMC+, voir [RmcPlusAuth]).
 *
 * Mesuré :
 *   BFMTV / BFM2 / Tech&Co / RMC / Brut / chaînes FAST → HLS en clair (pas de drm) ;
 *   RMC Story / RMC Découverte / RMC Life → HLS SAMPLE-AES + drm. Nos lecteurs ne câblent
 *   Widevine que sur DASH : on prend alors `manifest.mpd` (même hôte, vérifié : 200 avec
 *   ContentProtection Widevine) à la place de `master.m3u8`.
 *   Replays → DASH Widevine (transco.nextradiotv.com, géo-bloqué hors France : 403).
 *
 * Les ids du M3U (data-replay-bfm.m3u) sont les ids gaia `NEUF_BFMTV_BFM1021143163386` :
 * le suffixe numérique EST l'id vidéo RMC+ (vérifié sur deux ids). Le catalogue gaia-core
 * (ws-cdn.tv.sfr.net) répond toujours, donc [fetchEpisodes] garde son rôle pour les séries.
 *
 * ⚠️ Les directs et replays nécessitent un compte RMC+ connecté (gratuit).
 */
object BfmResolver {

    private const val TAG = "BfmResolver"
    private val UA get() = RmcPlusAuth.UA

    private const val BFF = "${RmcPlusAuth.SITE}/api/bff/v1"
    private const val LICENCE_WIDEVINE = "https://lic.drmtoday.com/license-proxy-widevine/cenc/"

    // Catalogue gaia-core (SFR) — encore en service, sert aux épisodes des séries.
    private const val API_CDN = "https://ws-cdn.tv.sfr.net/gaia-core/rest/api"

    /**
     * Anciens identifiants du M3U (`bfmlive://<clé>`) → id de chaîne RMC+ (page_id).
     * Les 16 ids RMC+ relevés sur l'accueil : bfmtv, rmc_story, rmc_decouverte, rmc_life,
     * brut, bfm_business, bfm_tech, after_foot_tv, j_irai_dormir_chez_vous,
     * bfm_grands_reportages, rmc, bfm_2, rmc_mystere, rmc_mecanic, rmc_wow, rmc_alerte_secours.
     * Un id inconnu est passé tel quel (permet d'ajouter les nouvelles chaînes au M3U).
     */
    private val CHAINES = mapOf(
        "bfmtv" to "bfmtv",
        "rmcstory" to "rmc_story",
        "rmcdecouverte" to "rmc_decouverte",
        "bfmbusiness" to "bfm_business",
        "rmclife" to "rmc_life",
        "techco" to "bfm_tech",
        "bfm2" to "bfm_2",
        "rmcradio" to "rmc",
    )

    // Cache DRM par URL de flux (lu par les lecteurs, même patron que M6Resolver)
    private val widevineLicenseCache = ConcurrentHashMap<String, String>()
    private val widevineHeadersCache = ConcurrentHashMap<String, Map<String, String>>()

    private var appContext: Context? = null

    // 2026-06-23 (user "quand le cookie a expiré l'option reconnexion devrait
    //   apparaître DIRECTEMENT") : on ouvre le dialog de reconnexion PILE quand la
    //   lecture échoue faute de session. Cooldown 8 s pour ne pas le spammer.
    private var lastReconnectDialogAt = 0L
    private const val RECONNECT_DIALOG_COOLDOWN_MS = 8_000L
    private fun triggerReconnectDialog() {
        val now = System.currentTimeMillis()
        if (now - lastReconnectDialogAt < RECONNECT_DIALOG_COOLDOWN_MS) return
        lastReconnectDialogAt = now
        val activity = com.streamflixreborn.streamflix.StreamFlixApp.currentActivity ?: return
        try {
            activity.runOnUiThread {
                try {
                    com.streamflixreborn.streamflix.activities.BfmLoginDialog.show(activity)
                } catch (t: Throwable) {
                    Log.w(TAG, "dialog reconnexion RMC+ impossible : ${t.message}")
                }
            }
        } catch (_: Throwable) {}
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
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
            bfmUrl.startsWith("bfmlive://", ignoreCase = true) ->
                resolveLive(ctx, bfmUrl.removePrefix("bfmlive://").trim())
            bfmUrl.startsWith("bfmplay://", ignoreCase = true) ->
                resolveReplay(ctx, bfmUrl.removePrefix("bfmplay://").trim())
            else -> null
        }
    }

    /** Variante compat : retourne juste l'URL. */
    suspend fun resolve(bfmUrl: String): String? = resolveTyped(bfmUrl)?.url

    // ── Lookup cache DRM (utilisé par PlayerFragment / MiniPlayerController) ──

    fun getWidevineLicenseUrl(streamUrl: String): String? = widevineLicenseCache[streamUrl]
    fun getWidevineHeaders(streamUrl: String): Map<String, String>? = widevineHeadersCache[streamUrl]

    // ── BFF RMC+ ──

    /** Le bloc `player` d'une page BFF (direct ou replay), ou null. */
    private class Lecteur(val titre: String?, val hls: String?, val dash: String?, val playToken: String?, val authent: Boolean)

    /**
     * GET d'une page BFF avec la session. Retourne le bloc lecteur, ou null si la page
     * n'existe pas / n'a pas de lecteur. Marque `sessionMorte` si le BFF réclame une
     * authentification alors qu'on a envoyé des cookies (= session expirée côté site).
     */
    private fun pageLecteur(ctx: Context, pageType: String, pageId: String): Lecteur? {
        val entetes = RmcPlusAuth.entetes(ctx) ?: run {
            Log.w(TAG, "RMC+ : pas de session — $pageType/$pageId")
            triggerReconnectDialog()
            return null
        }
        val url = "$BFF/page".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("model", "web")
            ?.addQueryParameter("page_type", pageType)
            ?.addQueryParameter("page_id", pageId)
            ?.build()?.toString() ?: return null
        val rb = Request.Builder().url(url)
        entetes.forEach { (k, v) -> rb.header(k, v) }
        val json = try {
            client.newCall(rb.build()).execute().use { r ->
                val corps = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    Log.w(TAG, "BFF HTTP ${r.code} $pageType/$pageId — ${corps.take(160)}")
                    return null
                }
                JSONObject(corps)
            }
        } catch (e: Exception) {
            Log.w(TAG, "BFF KO $pageType/$pageId : ${e.message}")
            return null
        }
        if (json.has("message") && !json.has("sections")) {
            Log.w(TAG, "BFF $pageType/$pageId : ${json.optString("message")}")
            return null
        }
        val sections = json.optJSONArray("sections")
        var lecteur: JSONObject? = null
        if (sections != null) {
            for (i in 0 until sections.length()) {
                val s = sections.optJSONObject(i) ?: continue
                if (s.optString("type") == "player") { lecteur = s; break }
            }
        }
        if (lecteur == null) {
            Log.w(TAG, "BFF $pageType/$pageId : pas de section player")
            return null
        }
        val video = lecteur.optJSONObject("video")
        val stream = video?.optJSONObject("stream")
        val drm = video?.optJSONObject("drm")
        val authent = lecteur.optBoolean("authent", false)
        val res = Lecteur(
            titre = lecteur.optString("titre", null),
            hls = stream?.optString("hls", null)?.takeIf { it.startsWith("http") },
            dash = stream?.optString("dash", null)?.takeIf { it.startsWith("http") },
            playToken = drm?.optString("play_token", null)?.takeIf { it.isNotBlank() },
            authent = authent,
        )
        if (res.hls == null && res.dash == null) {
            // Cookies envoyés mais le site ne donne pas le flux : session périmée (ou
            // contenu payant). On vérifie la session ; si elle est morte, on la jette et on
            // propose la reconnexion — sinon c'est le contenu qui est indisponible.
            val cookies = RmcPlusAuth.cookies(ctx)
            val encoreValide = cookies != null && RmcPlusAuth.verifierSession(cookies) != null
            Log.w(TAG, "BFF $pageType/$pageId : aucun flux (authent=$authent, session valide=$encoreValide)")
            if (!encoreValide) {
                RmcPlusAuth.deconnecter(ctx)
                triggerReconnectDialog()
            }
            return null
        }
        return res
    }

    /** Pose la licence Widevine (DRMtoday, en-tête x-dt-auth-token) en cache pour cette URL. */
    private fun armerDrm(url: String, playToken: String) {
        widevineLicenseCache[url] = LICENCE_WIDEVINE
        widevineHeadersCache[url] = mapOf(
            "x-dt-auth-token" to playToken,
            "User-Agent" to UA,
        )
    }

    // ── Résolution LIVE ──

    private suspend fun resolveLive(ctx: Context, channel: String): Resolved? {
        val id = CHAINES[channel.lowercase()] ?: channel
        val l = pageLecteur(ctx, "direct", id) ?: return null
        // Chaîne chiffrée : DASH obligatoire (nos lecteurs ne font Widevine que sur DASH).
        if (l.playToken != null) {
            val dash = l.dash ?: l.hls?.replace(Regex("""/[^/]*\.m3u8(\?.*)?$"""), "/manifest.mpd")
            if (dash == null) { Log.w(TAG, "live $id : DRM sans DASH"); return null }
            armerDrm(dash, l.playToken)
            Log.d(TAG, "live $id → DASH Widevine ${dash.take(90)}")
            return Resolved(dash, "application/dash+xml", LICENCE_WIDEVINE, widevineHeadersCache[dash])
        }
        val hls = l.hls ?: l.dash ?: return null
        Log.d(TAG, "live $id → ${if (l.hls != null) "HLS" else "DASH"} ${hls.take(90)}")
        return Resolved(hls, if (l.hls != null) "application/x-mpegURL" else "application/dash+xml")
    }

    // ── Résolution REPLAY ──

    /** `NEUF_BFMTV_BFM1021143163386` → `1021143163386` ; `955010561527` → lui-même. */
    private fun idVideo(productId: String): String? =
        Regex("""(\d{6,})$""").find(productId.removePrefix("Product::"))?.groupValues?.get(1)

    private suspend fun resolveReplay(ctx: Context, productId: String): Resolved? {
        val direct = idVideo(productId)?.let { resolveReplayId(ctx, it) }
        if (direct != null) return direct
        if (RmcPlusAuth.cookies(ctx) == null) return null   // reconnexion déjà proposée

        // Id de série/saison ? Le catalogue gaia donne les épisodes, dont le 1er est jouable.
        Log.d(TAG, "replay $productId : pas de lecteur direct, épisodes gaia…")
        val episodes = try { fetchEpisodes(productId) } catch (_: Exception) { null }
        if (episodes.isNullOrEmpty()) {
            Log.w(TAG, "replay $productId : aucun épisode — échec")
            return null
        }
        for (ep in episodes.take(3)) {
            val id = idVideo(ep.contentId) ?: continue
            val r = resolveReplayId(ctx, id)
            if (r != null) return r
        }
        return null
    }

    private fun resolveReplayId(ctx: Context, id: String): Resolved? {
        val l = pageLecteur(ctx, "player", id) ?: return null
        val url = l.dash ?: l.hls ?: return null
        val mime = if (l.dash != null) "application/dash+xml" else "application/x-mpegURL"
        if (l.playToken != null) {
            armerDrm(url, l.playToken)
            Log.d(TAG, "replay $id → ${l.titre} (Widevine) ${url.take(90)}")
            return Resolved(url, mime, LICENCE_WIDEVINE, widevineHeadersCache[url])
        }
        Log.d(TAG, "replay $id → ${l.titre} (clair) ${url.take(90)}")
        return Resolved(url, mime)
    }

    // ── Épisodes (catalogue gaia-core, toujours en service) ──

    data class BfmEpisode(
        val contentId: String,
        val title: String,
        val description: String?,
        val poster: String?,
        val contentType: String?  // "Movie", "Episode", ou autre
    )

    /**
     * 2026-06-21 : fetch la liste d'épisodes d'un contenu BFM (série, émission) :
     *   GET $API_CDN/web/v1/content/{contentId}/episodes
     *   params: universe=PROVIDER, accountTypes=NEXTTV, operators=NEXTTV, page=0, size=1000
     * 2026-09-05 : le paramètre `token` n'est PLUS envoyé (il n'existe plus ; c'est ce
     *   qu'utilise déjà refresh_bfm.py côté nx-data, sans token, et ça répond).
     *
     * @return liste d'épisodes, ou null si pas d'épisodes (= film/single).
     */
    @Suppress("UNUSED_PARAMETER")
    fun fetchEpisodes(contentId: String, token: String? = null): List<BfmEpisode>? {
        val fullId = if (contentId.startsWith("Product::")) contentId else "Product::$contentId"
        val url = "$API_CDN/web/v1/content/$fullId/episodes".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("universe", "PROVIDER")
            ?.addQueryParameter("accountTypes", "NEXTTV")
            ?.addQueryParameter("operators", "NEXTTV")
            ?.addQueryParameter("noTracking", "false")
            ?.addQueryParameter("page", "0")
            ?.addQueryParameter("size", "1000")
            ?.build()?.toString() ?: return null

        Log.d(TAG, "fetchEpisodes GET $url")
        val resp = try {
            httpGetJson(url)
        } catch (e: Exception) {
            Log.w(TAG, "fetchEpisodes failed for $contentId: ${e.message}")
            return null
        } ?: return null

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
            if (item.has("svodId") && !item.isNull("svodId") && item.optString("svodId").isNotBlank()) continue
            val epContentId = item.optJSONObject("action")
                ?.optJSONObject("actionIds")
                ?.optString("contentId", "")
                ?: ""
            val finalId = epContentId.ifEmpty { item.optString("id", "") }
            if (finalId.isEmpty()) continue

            val title = item.optString("title", "Épisode ${i + 1}")
            val description = item.optString("description", null)
            val contentType = item.optString("contentType", null)

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
                return null
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            return try { JSONObject(body) } catch (_: Exception) {
                Log.w(TAG, "Invalid JSON: ${body.take(120)}")
                null
            }
        }
    }
}
