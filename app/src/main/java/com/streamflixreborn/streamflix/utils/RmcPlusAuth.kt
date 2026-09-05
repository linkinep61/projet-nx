package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * RmcPlusAuth — session du site RMC+ (ex-RMC BFM Play).
 *
 * 2026-09-05 (user : « il y a un problème de connexion avec RMC BFM dans le TV Hub, quand on
 * clique sur Se connecter ça fait rien » → « il faut réparer la totalité »).
 *
 * ── CE QUI S'EST PASSÉ ─────────────────────────────────────────────────────────────────
 * RMC BFM Play a été remplacé par RMC+ (www.rmcplus.fr). Mesuré ce jour :
 *   • l'ancien SSO CAS `sso.rmcbfmplay.com` répond 504 au POST des identifiants (dans l'app,
 *     dans Chrome, et depuis un serveur neutre) — il est abandonné, pas en panne ;
 *   • `www.rmcbfmplay.com` redirige (301) vers `www.rmcplus.fr` ;
 *   • le nouveau site se connecte via `connect.rmcbfm.com` (OAuth2 authorization code), le
 *     retour `/api/auth/oauth2/callback/rmcbfm` pose une SESSION sur www.rmcplus.fr
 *     (`/api/auth/get-session` → `session.expiresAt` à +6 mois) ;
 *   • les flux viennent du BFF (`/api/bff/v1/page?page_type=direct|player&page_id=…`) qui
 *     ne rend `video.stream` QUE si la session est présente (`authent: true` sinon).
 *
 * ── CE QUE FAIT CE FICHIER ─────────────────────────────────────────────────────────────
 * On ne réimplémente PAS le login (formulaire SPA sur connect.rmcbfm.com) : la WebView de
 * [com.streamflixreborn.streamflix.activities.LoginWebViewActivity] fait la connexion comme
 * un navigateur, puis on récupère les COOKIES de www.rmcplus.fr dans le CookieManager, on les
 * vérifie avec `/api/auth/get-session`, et on les garde ici. Ils servent d'en-tête `Cookie`
 * à toutes les requêtes BFF de [BfmResolver]. Les anciens `BfmAuth` (token `BFM_…`) et
 * `BfmSsoAuth` (login REST) ne servent plus qu'en façade — voir leurs en-têtes.
 */
object RmcPlusAuth {

    private const val TAG = "RmcPlusAuth"
    const val SITE = "https://www.rmcplus.fr"

    /**
     * Point d'entrée de la connexion : redirige (307) vers connect.rmcbfm.com/oauth2/authorize,
     * puis revient sur www.rmcplus.fr une fois connecté. Mesuré : `/auth/sign-in` et
     * `/connexion` n'existent pas (404), c'est bien `/auth/resume` que le site utilise.
     */
    const val URL_CONNEXION = "$SITE/auth/resume?redirectTo=%2F&brand=rmcplus"

    const val UA = "Mozilla/5.0 (Linux; Android 13; CPH2211) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val PREF = "rmcplus_auth"
    private const val K_COOKIES = "cookies"
    private const val K_SAVED_AT = "saved_at"
    private const val K_EXPIRES_AT = "expires_at"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Cookies de session (chaîne `a=b; c=d`) ou null si pas connecté / expiré. */
    fun cookies(ctx: Context): String? {
        val p = prefs(ctx)
        val c = p.getString(K_COOKIES, null)?.takeIf { it.isNotBlank() } ?: return null
        val exp = p.getLong(K_EXPIRES_AT, 0L)
        if (exp > 0L && System.currentTimeMillis() > exp) {
            Log.w(TAG, "session RMC+ expirée (${java.util.Date(exp)})")
            return null
        }
        return c
    }

    fun estConnecte(ctx: Context): Boolean = cookies(ctx) != null

    fun savedAt(ctx: Context): Long = prefs(ctx).getLong(K_SAVED_AT, 0L)

    fun enregistrer(ctx: Context, cookies: String, expiresAtMs: Long?) {
        prefs(ctx).edit()
            .putString(K_COOKIES, cookies)
            .putLong(K_SAVED_AT, System.currentTimeMillis())
            .putLong(K_EXPIRES_AT, expiresAtMs ?: 0L)
            .apply()
        Log.i(TAG, "session RMC+ enregistrée (${cookies.count { it == ';' } + 1} cookies, " +
            "expire ${expiresAtMs?.let { java.util.Date(it) } ?: "?"})")
    }

    /** Oublie la session côté app ET côté WebView (sinon la WebView se reconnecte toute seule). */
    fun deconnecter(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        try {
            val cm = CookieManager.getInstance()
            for (site in listOf(SITE, "https://rmcplus.fr", "https://connect.rmcbfm.com", "https://rmcbfm.com")) {
                val actuels = cm.getCookie(site) ?: continue
                actuels.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
                    .forEach { nom ->
                        cm.setCookie(site, "$nom=; Max-Age=0; Path=/")
                        cm.setCookie(site, "$nom=; Max-Age=0; Path=/; Secure")
                    }
            }
            cm.flush()
        } catch (e: Throwable) {
            Log.w(TAG, "purge cookies WebView : ${e.message}")
        }
    }

    /** Cookies actuellement posés dans la WebView pour www.rmcplus.fr (null si aucun). */
    fun cookiesWebView(): String? = try {
        CookieManager.getInstance().getCookie(SITE)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    /**
     * Vérifie une chaîne de cookies auprès de `/api/auth/get-session`.
     * Retourne la date d'expiration (ms) si la session est valide, null sinon.
     * Réseau : à appeler hors du thread principal.
     */
    fun verifierSession(cookies: String): Long? = try {
        val req = Request.Builder()
            .url("$SITE/api/auth/get-session")
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Cookie", cookies)
            .build()
        client.newCall(req).execute().use { r ->
            val corps = r.body?.string().orEmpty()
            if (!r.isSuccessful) { Log.w(TAG, "get-session HTTP ${r.code}"); null }
            else {
                val session = JSONObject(corps).optJSONObject("session")
                if (session == null) { Log.w(TAG, "get-session : pas de session (${corps.take(120)})"); null }
                else {
                    val iso = session.optString("expiresAt", "")
                    parseIso(iso) ?: (System.currentTimeMillis() + 30L * 24 * 3600 * 1000)
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "get-session KO : ${e.message}"); null
    }

    /**
     * Après la WebView : prend les cookies de www.rmcplus.fr, les vérifie, les enregistre.
     * Retourne true si la session est valide.
     */
    suspend fun capturerDepuisWebView(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val c = cookiesWebView() ?: run { Log.d(TAG, "WebView : aucun cookie rmcplus.fr"); return@withContext false }
        val exp = verifierSession(c) ?: return@withContext false
        enregistrer(ctx, c, exp)
        true
    }

    /** En-têtes à joindre aux requêtes BFF (null si pas connecté). */
    fun entetes(ctx: Context): Map<String, String>? {
        val c = cookies(ctx) ?: return null
        return mapOf(
            "Cookie" to c,
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Referer" to "$SITE/",
        )
    }

    /** `2027-03-04T18:14:37.819Z` → ms epoch (null si illisible). */
    private fun parseIso(s: String): Long? {
        if (s.isBlank()) return null
        return try {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            f.parse(s.substringBefore('.').removeSuffix("Z"))?.time
        } catch (_: Exception) { null }
    }
}
