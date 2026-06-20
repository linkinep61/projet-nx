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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Pipeline d'authentification TF1+ via Gigya — port direct du plugin Kodi
 * Catchup TV & More (`tf1plus.py`).
 *
 * 3 étapes :
 *   1. Bootstrap Gigya (= init session côté SAP/Gigya)
 *      GET https://compte.tf1.fr/accounts.webSdkBootstrap?apiKey=...
 *   2. Login utilisateur
 *      POST https://compte.tf1.fr/accounts.login (form-urlencoded)
 *      → réponse JSON avec userInfo.UID + UIDSignature + signatureTimestamp
 *   3. Échange Gigya signature → JWT TF1
 *      POST https://www.tf1.fr/token/gigya/web (JSON)
 *      Body: {uid, signature, timestamp, consent_ids:[...]}
 *      → réponse JSON {token: "<JWT>"}
 *
 * Le JWT est ensuite utilisé en `Authorization: Bearer <JWT>` dans
 * mediainfo.tf1.fr pour récupérer l'URL HLS/DASH du stream.
 *
 * NB : le JWT expire (~1h). Si l'app reçoit un 401/error_code TOKEN
 * de mediainfo, elle relance login() avec email/password persistés.
 */
object TF1GigyaAuth {
    private const val TAG = "TF1GigyaAuth"
    private const val API_KEY =
        "3_hWgJdARhz_7l1oOp3a8BDLoR9cuWZpUaKG4aqF7gum9_iK3uTZ2VlDBl8ANf8FVk"
    private const val URL_BOOTSTRAP = "https://compte.tf1.fr/accounts.webSdkBootstrap"
    private const val URL_LOGIN = "https://compte.tf1.fr/accounts.login"
    private const val URL_TOKEN = "https://www.tf1.fr/token/gigya/web"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** Pref keys pour la persistance des identifiants (= chiffrés Android KS
     *  serait idéal, mais pour cette release on stocke en plain SharedPrefs). */
    private const val PREF_NAME = "replay_auth_tf1_creds"
    private const val K_EMAIL = "email"
    private const val K_PASSWORD = "password"

    // Cookie jar partagé entre les 3 calls (= Gigya pose des cookies de session)
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
            .cookieJar(cookieJar)
            .build()
    }

    /** Stocke email/password pour re-login auto si le JWT expire. */
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

    /**
     * Pipeline complet bootstrap → login → JWT. Retourne le JWT ou null si
     * échec. À appeler en IO dispatcher.
     */
    suspend fun login(email: String, password: String): String? = withContext(Dispatchers.IO) {
        try {
            // Reset cookies pour partir d'un état propre
            cookieStore.clear()

            // === 1. Bootstrap ===
            val bootstrapUrl = "$URL_BOOTSTRAP?" +
                "apiKey=${URLEncoder.encode(API_KEY, "UTF-8")}" +
                "&pageURL=" + URLEncoder.encode("https://www.tf1.fr/", "UTF-8") +
                "&sd=js_latest&sdkBuild=13987&format=json"
            val bootstrapResp = httpGet(bootstrapUrl, mapOf("Referer" to "https://www.tf1.fr/"))
            Log.d(TAG, "Bootstrap HTTP body: ${bootstrapResp?.take(200)}")
            if (bootstrapResp == null) {
                Log.e(TAG, "Bootstrap failed (null response)")
                return@withContext null
            }

            // === 2. Login Gigya ===
            val loginBody = "loginID=${URLEncoder.encode(email, "UTF-8")}" +
                "&password=${URLEncoder.encode(password, "UTF-8")}" +
                "&apiKey=${URLEncoder.encode(API_KEY, "UTF-8")}" +
                "&format=json&sdk=js_latest&include=identities-all,profile,data,emails," +
                "subscriptions,preferences,&loginMode=standard&riskContext=" +
                URLEncoder.encode("{\"b0\":0,\"b2\":0,\"b3\":0,\"b4\":0}", "UTF-8") +
                "&context=R0&authMode=cookie"
            val loginResp = httpPost(
                URL_LOGIN,
                "application/x-www-form-urlencoded",
                loginBody,
                mapOf("Referer" to "https://www.tf1.fr/")
            )
            if (loginResp == null) {
                Log.e(TAG, "Login HTTP failed")
                return@withContext null
            }
            val loginJson = try { JSONObject(loginResp) }
                            catch (e: Exception) { Log.e(TAG, "Login JSON parse: $loginResp"); return@withContext null }
            val errCode = loginJson.optInt("errorCode", -1)
            if (errCode != 0) {
                val msg = loginJson.optString("errorMessage", "?")
                Log.e(TAG, "Gigya login error code=$errCode message='$msg'")
                return@withContext null
            }
            val userInfo = loginJson.optJSONObject("userInfo") ?: run {
                Log.e(TAG, "No userInfo in login response")
                return@withContext null
            }
            val uid = userInfo.optString("UID")
            val sig = userInfo.optString("UIDSignature")
            val ts = loginJson.optInt("signatureTimestamp", 0)
            if (uid.isBlank() || sig.isBlank() || ts == 0) {
                Log.e(TAG, "Missing uid/sig/ts in login response (uid=$uid, ts=$ts)")
                return@withContext null
            }
            Log.d(TAG, "Gigya login OK : uid=${uid.take(12)}… ts=$ts")

            // === 3. Échange Gigya signature → JWT TF1 ===
            val consentIds = listOf("1","2","3","4","10001","10003","10005","10007","10013",
                                     "10015","10017","10019","10009","10011","13002","13001",
                                     "10004","10014","10016","10018","10020","10010","10012",
                                     "10006","10008")
            val tokenBody = JSONObject().apply {
                put("uid", uid)
                put("signature", sig)
                put("timestamp", ts)
                put("consent_ids", JSONArray(consentIds))
            }.toString()
            val tokenResp = httpPost(
                URL_TOKEN,
                "application/json",
                tokenBody,
                mapOf("Referer" to "https://www.tf1.fr/")
            )
            if (tokenResp == null) {
                Log.e(TAG, "Token exchange HTTP failed")
                return@withContext null
            }
            val tokenJson = try { JSONObject(tokenResp) }
                            catch (e: Exception) { Log.e(TAG, "Token JSON parse: $tokenResp"); return@withContext null }
            val token = tokenJson.optString("token", "")
            if (token.isBlank()) {
                Log.e(TAG, "Empty token in response: ${tokenResp.take(200)}")
                return@withContext null
            }
            Log.i(TAG, "✓ TF1+ JWT obtained (len=${token.length})")
            return@withContext token
        } catch (e: Throwable) {
            Log.e(TAG, "login pipeline crashed: ${e.message}", e)
            null
        }
    }

    /** Re-login avec les identifiants persistés. Retourne le JWT ou null. */
    suspend fun reloginFromSaved(ctx: Context): String? {
        val p = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val email = p.getString(K_EMAIL, null) ?: return null
        val pass = p.getString(K_PASSWORD, null) ?: return null
        return login(email, pass)
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            return resp.body?.string()
        }
    }

    private fun httpPost(
        url: String, contentType: String, body: String,
        headers: Map<String, String> = emptyMap()
    ): String? {
        val b = Request.Builder().url(url).header("User-Agent", UA)
            .post(body.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            return resp.body?.string()
        }
    }
}
