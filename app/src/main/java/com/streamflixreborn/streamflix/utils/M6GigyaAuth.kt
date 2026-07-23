package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 2026-07-23 (user "Ajoute une méthode pour enregistrer les identifiants qui ne
 *   sont PAS Google pour TF1, M6 et BFM — tous ceux qui se connectent pas avec
 *   Google se déconnectent et ne peuvent pas se reconnecter automatiquement") :
 *
 * Pipeline d'authentification M6 / 6play via Gigya (= même moteur SAP CDC que
 * TF1). Port direct de TF1GigyaAuth mais pour 6play :
 *   - host branded Gigya = login-gigya.m6.fr (CNAME datacenter EU)
 *   - apiKey NON hardcodée : elle est capturée au login interactif (cookie
 *     `glt_<apiKey>`) et stockée via M6Auth.saveApiKey → on la relit ici.
 *   - PAS d'échange JWT ici (M6Resolver fait le POST getJwt à part avec le
 *     login_token Gigya) → on se contente de refresh le login_token (= la
 *     valeur du cookie glt_) + l'accountId (UID) + la signature.
 *
 * But : quand le login_token Gigya expire (quelques heures) et que la
 * restauration par cookies WebView (M6UidResolver) échoue, on relance
 * `accounts.login` avec l'email/mot de passe persistés → nouveau login_token,
 * SANS que l'utilisateur ait à ressaisir quoi que ce soit.
 *
 * NB : ce chemin ne concerne QUE les comptes email/mot de passe. Les comptes
 * connectés via Google/Apple/Facebook n'ont pas de mot de passe à stocker —
 * pour eux, rien ne change (comportement actuel).
 */
object M6GigyaAuth {
    private const val TAG = "M6GigyaAuth"

    /** Host branded Gigya de 6play (CNAME vers le datacenter EU de SAP CDC). */
    private const val GIGYA_HOST = "https://login-gigya.m6.fr"

    /** 2026-07-23 : apiKey Gigya 6play/M6+ (relevée en direct via window.gigya.apiKey
     *  sur www.m6.fr). Sert de SECOURS quand aucune apiKey n'a été capturée au
     *  login (le nouveau login OIDC auth.m6.fr ne pose plus le cookie `glt_<apiKey>`
     *  d'où on la tirait). Le backend Gigya reste actif côté 6play (window.gigya
     *  présent) → accounts.login fonctionne toujours avec cette clé. */
    private const val FALLBACK_API_KEY =
        "3_hH5KBv25qZTd_sURpixbQW6a4OsiIzIEF2Ei_2H7TXTGLJb_1Hr4THKZianCQhWK"

    /** apiKey effective : celle capturée au login si dispo, sinon le secours. */
    fun apiKeyFor(ctx: Context): String =
        M6Auth.getApiKey(ctx)?.takeIf { it.isNotBlank() } ?: FALLBACK_API_KEY
    private const val URL_BOOTSTRAP = "$GIGYA_HOST/accounts.webSdkBootstrap"
    private const val URL_LOGIN = "$GIGYA_HOST/accounts.login"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** Pref keys pour la persistance des identifiants (plain SharedPrefs, comme
     *  TF1GigyaAuth — un chiffrement KeyStore serait idéal pour une future release). */
    private const val PREF_NAME = "replay_auth_m6_creds"
    private const val K_EMAIL = "email"
    private const val K_PASSWORD = "password"

    // Cookie jar partagé entre bootstrap + login (Gigya pose des cookies de session)
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val matching = mutableListOf<Cookie>()
            for ((host, list) in cookieStore) {
                if (url.host.endsWith(host) || host.endsWith(url.host)) {
                    matching.addAll(list)
                }
            }
            return matching
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .cookieJar(cookieJar)
            .build()
    }

    /** Stocke email/password pour re-login auto si le login_token expire. */
    fun saveCredentials(ctx: Context, email: String, password: String) {
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(K_EMAIL, email).putString(K_PASSWORD, password).apply()
    }

    fun hasCredentials(ctx: Context): Boolean {
        val p = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return !p.getString(K_EMAIL, null).isNullOrBlank() &&
               !p.getString(K_PASSWORD, null).isNullOrBlank()
    }

    fun clearCredentials(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun savedEmail(ctx: Context): String? =
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(K_EMAIL, null)

    fun savedPassword(ctx: Context): String? =
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(K_PASSWORD, null)

    /**
     * Re-login avec les identifiants persistés. Retourne le nouveau login_token
     * Gigya (= valeur du cookie glt_) ou null. Persiste aussi apiKey/accountId/
     * signature dans M6Auth pour que M6Resolver puisse refaire son getJwt.
     * À appeler en IO. Ne fait RIEN si aucun apiKey n'a été capturé au login
     * interactif (= on ne devine pas l'apiKey).
     */
    suspend fun reloginFromSaved(ctx: Context): String? = withContext(Dispatchers.IO) {
        val appCtx = ctx.applicationContext
        val p = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val email = p.getString(K_EMAIL, null)
        val pass = p.getString(K_PASSWORD, null)
        if (email.isNullOrBlank() || pass.isNullOrBlank()) {
            Log.d(TAG, "reloginFromSaved: no saved M6 credentials")
            return@withContext null
        }
        // apiKey capturée si dispo, sinon secours (le login OIDC ne la pose plus).
        val apiKey = apiKeyFor(appCtx)
        login(appCtx, email, pass, apiKey)
    }

    /**
     * Pipeline bootstrap → accounts.login. Retourne le login_token Gigya ou
     * null. Persiste le token + apiKey + accountId (UID) + signature via M6Auth.
     */
    suspend fun login(
        ctx: Context,
        email: String,
        password: String,
        apiKey: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            cookieStore.clear()

            // === 1. Bootstrap (init session Gigya) ===
            val bootstrapUrl = "$URL_BOOTSTRAP?" +
                "apiKey=${URLEncoder.encode(apiKey, "UTF-8")}" +
                "&pageURL=" + URLEncoder.encode("https://www.6play.fr/", "UTF-8") +
                "&sd=js_latest&sdkBuild=13987&format=json"
            val bootstrapResp = httpGet(bootstrapUrl, mapOf("Referer" to "https://www.6play.fr/"))
            Log.d(TAG, "Bootstrap body: ${bootstrapResp?.take(150)}")

            // === 2. Login Gigya ===
            val loginBody = "loginID=${URLEncoder.encode(email, "UTF-8")}" +
                "&password=${URLEncoder.encode(password, "UTF-8")}" +
                "&apiKey=${URLEncoder.encode(apiKey, "UTF-8")}" +
                "&format=json&sdk=js_latest&include=identities-all,profile,data,emails," +
                "subscriptions,preferences,&loginMode=standard&riskContext=" +
                URLEncoder.encode("{\"b0\":0,\"b2\":0,\"b3\":0,\"b4\":0}", "UTF-8") +
                "&context=R0&authMode=cookie"
            val loginResp = httpPost(
                URL_LOGIN,
                "application/x-www-form-urlencoded",
                loginBody,
                mapOf("Referer" to "https://www.6play.fr/")
            ) ?: run {
                Log.e(TAG, "Login HTTP failed")
                return@withContext null
            }
            val loginJson = try { JSONObject(loginResp) } catch (e: Exception) {
                Log.e(TAG, "Login JSON parse fail: ${loginResp.take(200)}")
                return@withContext null
            }
            val errCode = loginJson.optInt("errorCode", -1)
            if (errCode != 0) {
                Log.e(TAG, "Gigya login error code=$errCode msg='${loginJson.optString("errorMessage", "?")}'")
                return@withContext null
            }
            // login_token = valeur du cookie glt_<apiKey> (= ce que stocke le login interactif)
            val sessionInfo = loginJson.optJSONObject("sessionInfo")
            val loginToken = sessionInfo?.optString("login_token")?.takeIf { it.isNotBlank() }
                ?: sessionInfo?.optString("cookieValue")?.takeIf { it.isNotBlank() }
            if (loginToken.isNullOrBlank()) {
                Log.e(TAG, "No login_token in sessionInfo: ${loginResp.take(200)}")
                return@withContext null
            }
            val uid = loginJson.optString("UID").takeIf { it.isNotBlank() }
                ?: loginJson.optJSONObject("userInfo")?.optString("UID")?.takeIf { it.isNotBlank() }
            val sig = loginJson.optString("UIDSignature").takeIf { it.isNotBlank() }
            val ts = loginJson.optInt("signatureTimestamp", 0)

            // Expiration : sessionInfo.expiration = epoch seconds (sinon ~4h par défaut).
            val exp = sessionInfo.optLong("expiration", 0L)
                .takeIf { it > 0L }
                ?: ((System.currentTimeMillis() / 1000L) + 4 * 3600L)

            // === 3. Persistance via M6Auth (mêmes clés que le login interactif) ===
            M6Auth.saveToken(ctx, loginToken, refresh = null, exp = exp)
            M6Auth.saveApiKey(ctx, apiKey)
            if (!uid.isNullOrBlank()) M6Auth.saveAccountId(ctx, uid)
            if (!sig.isNullOrBlank()) M6Auth.saveUidSignature(ctx, sig)
            if (ts != 0) M6Auth.saveSignatureTimestamp(ctx, ts.toString())

            Log.i(TAG, "✓ M6 re-login OK (login_token len=${loginToken.length}, uid=${uid?.take(10)}…, exp=$exp)")
            return@withContext loginToken
        } catch (e: Throwable) {
            Log.e(TAG, "login pipeline crashed: ${e.message}", e)
            null
        }
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp -> return resp.body?.string() }
    }

    private fun httpPost(
        url: String, contentType: String, body: String,
        headers: Map<String, String> = emptyMap()
    ): String? {
        val b = Request.Builder().url(url).header("User-Agent", UA)
            .post(body.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp -> return resp.body?.string() }
    }
}
