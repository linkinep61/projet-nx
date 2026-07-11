package com.streamflixreborn.streamflix.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Résolveur TF1+ replay — port du pipeline `mytf1` de Catchup TV & More.
 *
 * Pipeline :
 *   1. App reçoit URL `tf1plus://<si_id>` depuis le M3U replay
 *   2. Appel `https://mediainfo.tf1.fr/mediainfocombo/L_<si_id>?context=MYTF1&pver=5029000`
 *      → retourne `delivery.url` (= m3u8 ou mpd) + `delivery.format`
 *   3. ExoPlayer joue
 *
 * Notes :
 *   - Pas de login requis pour les replays "gratuits" (= la majorité).
 *   - Token Bearer optionnel si on a une session ; sans token = mode "guest".
 *   - DRM Widevine est appliqué sur les contenus premium (= mention "code"
 *     dans la réponse mediainfocombo). Pour ces contenus on retourne null
 *     pour l'instant (= TODO : ajouter DRM Widevine comme pour M6).
 *
 * Token JWT signé sur IP cliente FR métropole. Hors FR → 412/403.
 */
object TF1Resolver {

    private const val TAG = "TF1Resolver"
    // 2026-06-18 v13 : UA desktop (= comme Catchup TV & More). UA mobile peut
    //   être rejeté par TF1+ mediainfo car le `device=desktop` du query string
    //   incohérent avec un UA mobile.
    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    // v22 : VOD et LIVE utilisent la MÊME URL minimaliste.
    //   Pour VOD, l'ID DOIT être un UUID (= extrait du embedUrl player de la
    //   page HTML), PAS l'ID numérique du URL slug. C'est pour ça qu'on
    //   recevait 404 country:PF avant : on passait 26259212 (= legacy id)
    //   au lieu du UUID 570c806d-... qui est le vrai mediaId.
    private const val URL_MEDIAINFO_VOD =
        "https://mediainfo.tf1.fr/mediainfocombo/{ID}?context=MYTF1&pver=5029000"
    private const val URL_MEDIAINFO_LIVE =
        "https://mediainfo.tf1.fr/mediainfocombo/{ID}?context=MYTF1&pver=5029000"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** Application context capturé au cold start, sert à lire TF1Auth token. */
    @Volatile private var appContextRef: android.content.Context? = null
    fun installContext(ctx: android.content.Context) {
        if (appContextRef == null) {
            appContextRef = ctx.applicationContext
            // 2026-06-18 v8 : un VRAI JWT = `header.payload.signature` (3
            //   parties base64url séparées par `.`). On invalide tout token
            //   qui ne respecte pas ce format (= cookie Didomi, ancien
            //   cookie session, etc.).
            val tok = com.streamflixreborn.streamflix.utils.TF1Auth.getToken(ctx.applicationContext)
            if (tok != null && !isValidJwt(tok)) {
                Log.w(TAG, "Detected non-JWT TF1 token (len=${tok.length}, parts=${tok.split(".").size}). Clearing.")
                com.streamflixreborn.streamflix.utils.TF1Auth.clearToken(ctx.applicationContext)
            }
            // 2026-07-05 (user "rien ne doit tourner au boot de la Chromecast, seulement au
            //   démarrage du provider concerné") : le refresh JWT TF1 au boot est RETIRÉ — il
            //   lançait une WebView tf1.fr (~9s) au démarrage, concurrente du warm DessinAnime.
            //   Il est REDONDANT : resolveVideoId() appelle déjà refreshIfNeeded à la demande
            //   (quand on lit une vidéo TF1). Donc TF1+ aura son JWT frais au moment utile, sans
            //   rien faire au boot.
        }
    }

    /** Vrai JWT = `header.payload.signature` (= 3 parties base64url séparées
     *  par `.`). Filtre les pseudo-tokens stockés par erreur (cookie Didomi
     *  CMP, ancien cookie de session, etc.). */
    private fun isValidJwt(tok: String): Boolean {
        if (tok.length < 100) return false
        val p = tok.split(".")
        if (p.size != 3) return false
        return p[0].startsWith("eyJ") && p[1].startsWith("eyJ") && p[2].isNotEmpty()
    }

    /** JWT TF1 obtenu via TF1GigyaAuth.login() → utilisé en Bearer dans
     *  mediainfo. Le JWT expire (~1h) → si null OU si mediainfo retourne
     *  erreur d'auth, on relance reloginFromSaved(). */
    private fun authBearer(): String? {
        val ctx = appContextRef ?: return null
        val tok = com.streamflixreborn.streamflix.utils.TF1Auth.getToken(ctx) ?: return null
        if (!isValidJwt(tok)) {
            Log.w(TAG, "authBearer: stored token is not a JWT (parts=${tok.split(".").size}, len=${tok.length}). Clearing.")
            com.streamflixreborn.streamflix.utils.TF1Auth.clearToken(ctx)
            return null
        }
        return tok
    }

    /** Re-login en cas de JWT expiré, via les identifiants persistés. */
    private suspend fun reloginIfPossible(): String? {
        val ctx = appContextRef ?: return null
        if (!com.streamflixreborn.streamflix.utils.TF1GigyaAuth.hasCredentials(ctx)) {
            Log.w(TAG, "No saved TF1 credentials → cannot re-login auto")
            return null
        }
        val newToken = com.streamflixreborn.streamflix.utils.TF1GigyaAuth.reloginFromSaved(ctx)
        if (newToken != null) {
            com.streamflixreborn.streamflix.utils.TF1Auth.saveToken(ctx, newToken)
            Log.i(TAG, "JWT refreshed via reloginFromSaved")
        }
        return newToken
    }

    fun isTF1Url(url: String): Boolean =
        url.startsWith("tf1plus://", ignoreCase = true) ||
        url.startsWith("tf1live://", ignoreCase = true)

    /** v34 : cache mémoire stream URL → widevine license URL, populé par
     *  resolveVideoId quand DRM détecté. Le PlayerMobileFragment / MiniPlayer
     *  le consulte pour configurer DrmSessionManager. */
    private val widevineLicenseCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun getWidevineLicenseUrl(streamUrl: String): String? = widevineLicenseCache[streamUrl]

    /** Mapping slug user-friendly → channel ID TF1 pour le live.
     *  Les chaînes FAST n'ont pas besoin d'entrée ici : leur URL M3U utilise
     *  directement tf1live://L_FAST_... qui passe par le shortcut ci-dessous. */
    private val LIVE_CHANNEL_IDS = mapOf(
        // ── Traditionnelles ──
        "tf1"              to "L_TF1",
        "tmc"              to "L_TMC",
        "tfx"              to "L_TFX",
        "tf1-series-films" to "L_TF1_SERIES_FILMS",
        "lci"              to "L_LCI",
        // ── Chaînes externes TF1+ ──
        "arte"             to "L_ARTE",
        "l-equipe"         to "L_L-EQUIPE",
        "lcp-public-senat" to "L_LCP-PUBLIC-SENAT",
        "le-figaro"        to "L_LE-FIGARO",
        "novo19"           to "L_NOVO19",
        "redbulltv"        to "L_REDBULLTV",
    )

    data class Resolved(
        val url: String,
        val mimeType: String,
        /** v34 : license URL Widevine pour décrypter VOD DRM. null si pas DRM. */
        val widevineLicenseUrl: String? = null,
    )

    suspend fun resolveTyped(tf1Url: String): Resolved? {
        // 3 préfixes possibles :
        //  - "tf1live://<slug>" → mediainfo avec L_<CHAN_ID> (live HLS)
        //  - "tf1plus://<chan>/<program-slug>" → page programme + extract ID
        //  - "tf1plus://<numeric_id>" → mediainfo direct
        if (tf1Url.startsWith("tf1live://", ignoreCase = true)) {
            val slug = tf1Url.removePrefix("tf1live://").trim()
            // Si le slug est déjà un channel ID direct (L_FAST_..., L_ARTE, etc.)
            // on le passe tel quel à resolveVideoId — pas besoin de lookup dans la map
            val chanId = if (slug.startsWith("L_", ignoreCase = true)) {
                slug  // ID direct
            } else {
                LIVE_CHANNEL_IDS[slug.lowercase()]
            }
            if (chanId == null) {
                Log.w(TAG, "Unknown live slug: $slug")
                return null
            }
            Log.d(TAG, "Live TF1 $slug → $chanId")
            return resolveVideoId(chanId)
        }
        val path = tf1Url.removePrefix("tf1plus://").trim()
        if (path.isEmpty()) {
            Log.w(TAG, "Empty path: $tf1Url")
            return null
        }
        // v24 : "tf1plus://VIDEO/<slug>" = épisode replay TF1+ directement par slug
        //   → on cherche le chan/show via une page programme... mais on n'a pas
        //   le chan. On essaye tous les chans connus (tf1, tmc, tfx, tf1-series-films, lci)
        //   jusqu'à trouver le bon. C'est ~5 fetchs max mais on cache via la 1ère URL réussie.
        if (path.startsWith("VIDEO/")) {
            val slug = path.removePrefix("VIDEO/")
            return resolveVideoFromSlug(slug)
        }
        return if (path.contains("/")) resolveProgramPath(path) else resolveVideoId(path)
    }

    /** v24 : résout un épisode replay TF1+ depuis son slug seul.
     *  Essaye tous les chans TF1+ connus jusqu'à trouver la bonne URL. */
    private suspend fun resolveVideoFromSlug(slug: String): Resolved? {
        val chans = listOf("tf1", "tmc", "tfx", "tf1-series-films", "lci")
        // Extrait le show slug depuis l'épisode (= partie avant -s01-e01-...-NUM)
        // Le slug est typiquement : <show>-s<X>-e<Y>-<title>-<id>
        val showMatch = Regex("""^([a-z0-9-]+?)-s\d{1,2}-e\d{1,3}-""").find(slug)
        val showSlug = showMatch?.groupValues?.get(1)
            ?: slug.split("-").dropLast(1).joinToString("-")  // fallback : tout sauf l'id final
        for (chan in chans) {
            val videoUrl = "https://www.tf1.fr/$chan/$showSlug/videos/$slug.html"
            val html = try { httpGetText(videoUrl) } catch (_: Exception) { null }
            if (html != null && html.contains("/player/")) {
                val uuid = Regex("""www\.tf1\.fr/player/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""")
                    .find(html)?.groupValues?.get(1)
                if (uuid != null) {
                    Log.d(TAG, "Slug $slug found at $chan/$showSlug → UUID $uuid")
                    return resolveVideoId(uuid)
                }
            }
        }
        Log.w(TAG, "Could not find video for slug=$slug across chans")
        return null
    }

    /** Format "tf1/good-american-family" ou "tf1/show/videos/title-id.html" :
     *  v22 — pipeline DÉFINITIF :
     *  1. Fetch page HTML
     *  2. Extract UUID via regex `https://www.tf1.fr/player/{UUID}` ou `"id":"{UUID}"`
     *  3. Pass UUID directement à mediainfo VOD
     *  L'ID numérique seul (= 26259212) retourne 404. Mediainfo VOD veut le UUID. */
    private suspend fun resolveProgramPath(path: String): Resolved? {
        // Si le path EST déjà un slug video complet (= contient `/videos/...html`),
        //   on fetch direct cette page. Sinon on fetch la page programme et on
        //   prend le 1er lien video.
        val targetUrl = if (path.contains("/videos/") && path.endsWith(".html")) {
            "https://www.tf1.fr/$path"
        } else {
            "https://www.tf1.fr/$path"
        }
        val html = try { httpGetText(targetUrl) }
                   catch (e: Exception) { Log.e(TAG, "Page fetch failed $path: ${e.message}"); null }
                   ?: return null
        // Si on est sur la page programme, navigue vers la 1ère vidéo
        val videoPageHtml = if (!path.contains("/videos/")) {
            val videoLink = Regex("""(/[a-z0-9-]+/[a-z0-9-]+/videos?/[a-z0-9-]+-\d{6,14}\.html)""")
                .find(html)?.groupValues?.get(1)
            if (videoLink == null) {
                Log.w(TAG, "No video link found on programme page $path")
                return null
            }
            Log.d(TAG, "Programme $path → 1ère vidéo : $videoLink")
            try { httpGetText("https://www.tf1.fr$videoLink") }
                catch (_: Exception) { null } ?: return null
        } else {
            html
        }
        // Extrait l'UUID du player (= embedUrl ou JSON inline)
        val uuid = Regex("""www\.tf1\.fr/player/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""")
            .find(videoPageHtml)?.groupValues?.get(1)
            ?: Regex(""""id"\s*:\s*"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"""")
                .find(videoPageHtml)?.groupValues?.get(1)
        if (uuid == null) {
            Log.w(TAG, "No UUID found in page $path")
            return null
        }
        Log.d(TAG, "Page $path → UUID $uuid")
        return resolveVideoId(uuid)
    }

    /** Format ID numérique : appel direct à mediainfo.tf1.fr.
     *  v20 : sélectionne URL_MEDIAINFO_VOD ou URL_MEDIAINFO_LIVE selon le format
     *  de l'ID. Les IDs LIVE commencent par "L_" (ex: L_TF1, L_TMC). */
    private suspend fun resolveVideoId(siId: String): Resolved? {
        // 2026-06-18 v18 : déclenche refresh JWT en background si proche
        //   expiration (= ne bloque pas le call courant, le call suivant aura
        //   un JWT frais).
        val ctxLocal = appContextRef
        if (ctxLocal != null) {
            try {
                com.streamflixreborn.streamflix.utils.TF1JwtRefresher
                    .refreshIfNeeded(ctxLocal)
            } catch (_: Throwable) {}
        }
        val isLive = siId.startsWith("L_", ignoreCase = true)
        val template = if (isLive) URL_MEDIAINFO_LIVE else URL_MEDIAINFO_VOD
        val apiUrl = template.replace("{ID}", siId)
        Log.d(TAG, "mediainfo ${if (isLive) "LIVE" else "VOD"} for $siId")
        val resp = try {
            httpGetJson(apiUrl)
        } catch (e: Exception) {
            Log.e(TAG, "API fetch failed for $siId: ${e.message}", e)
            return null
        } ?: return null

        val code = resp.optInt("code", 0)
        if (code !in listOf(0, 200)) {
            Log.w(TAG, "TF1 error code=$code message='${resp.optString("message")}' for $siId")
            return null
        }

        val delivery = resp.optJSONObject("delivery") ?: run {
            Log.w(TAG, "No 'delivery' field for $siId")
            return null
        }
        // v34 : `drms` est un ARRAY (= [widevine, playready]), pas un objet.
        //   Avant on faisait optJSONObject qui retournait null → DRM passait
        //   → ExoPlayer tentait de décoder un stream chiffré → écran noir.
        //   Maintenant : on extract la widevine license URL et on la passe
        //   au player qui configure DrmSessionManager.
        val drmsArr = delivery.optJSONArray("drms")
        val widevineLicenseUrl = drmsArr?.let {
            (0 until it.length()).asSequence()
                .mapNotNull { i -> it.optJSONObject(i) }
                .firstOrNull { obj -> obj.optString("name").equals("widevine", ignoreCase = true) }
                ?.optString("url")?.takeIf { url -> url.isNotBlank() }
        }
        val format = delivery.optString("format", "").lowercase()
        val streamUrl = delivery.optString("url", "")
        if (streamUrl.isBlank()) {
            Log.w(TAG, "Empty delivery.url for $siId")
            return null
        }
        val mime = when {
            format == "hls" || streamUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            format == "dash" || streamUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
            else -> "video/*"
        }
        Log.d(TAG, "Resolved $siId ($format, drm=${widevineLicenseUrl != null}) → ${streamUrl.take(80)}...")
        if (widevineLicenseUrl != null) {
            widevineLicenseCache[streamUrl] = widevineLicenseUrl
        }
        return Resolved(streamUrl, mime, widevineLicenseUrl)
    }

    suspend fun resolve(tf1Url: String): String? = resolveTyped(tf1Url)?.url

    private fun httpGetText(url: String): String? {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", "https://www.tf1.fr/")
        val req = reqBuilder.build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                Log.w(TAG, "Empty HTML body HTTP ${resp.code} for $url")
                return null
            }
            return body
        }
    }

    private fun httpGetJson(url: String): JSONObject? {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            // v21 : pas d'Accept-Encoding manuel — OkHttp auto-décompresse uniquement quand absent
            .header("Referer", "https://www.tf1.fr/")
            .header("Origin", "https://www.tf1.fr")
            // v19 : headers Chrome fingerprint que mediainfo peut vérifier
            .header("Sec-Ch-Ua", "\"Google Chrome\";v=\"123\", \"Chromium\";v=\"123\", \"Not?A_Brand\";v=\"99\"")
            .header("Sec-Ch-Ua-Mobile", "?0")
            .header("Sec-Ch-Ua-Platform", "\"macOS\"")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-site")
        val jwt = authBearer()
        if (jwt != null && url.contains("mediainfo")) {
            reqBuilder.header("Authorization", "Bearer $jwt")
            Log.d(TAG, "Mediainfo with Bearer JWT (len=${jwt.length})")
        }
        // 2026-06-18 v17 : copie les cookies posés par la WebView pour le
        //   domaine tf1.fr. Hypothèse : mediainfo valide la session via les
        //   cookies (session_id, didomi, bff-*) en plus du Bearer JWT.
        if (url.contains("tf1.fr")) {
            try {
                val cookies = android.webkit.CookieManager.getInstance()
                    .getCookie("https://www.tf1.fr/")
                if (!cookies.isNullOrBlank()) {
                    reqBuilder.header("Cookie", cookies)
                    Log.d(TAG, "Mediainfo with Cookie header (len=${cookies.length})")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "getCookie failed: ${e.message}")
            }
        }
        val req = reqBuilder.build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                Log.w(TAG, "Empty body HTTP ${resp.code} for $url")
                return null
            }
            // 2026-06-18 v12 : log COMPLET pour voir le champ `country` (=
            //   indicateur de géo-blocage outre-mer TF1+) + tout le delivery.
            Log.d(TAG, "Mediainfo HTTP ${resp.code} body: ${body.take(2000)}")
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid JSON: ${body.take(120)}")
                null
            }
        }
    }
}
