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
 * Pipeline d'authentification RMC BFM Play via le SSO REST CAS.
 *
 * Inspiré de TF1GigyaAuth : sauvegarde email/password en SharedPrefs et
 * permet le re-login automatique quand le token BFM expire (~24h).
 *
 * Endpoint SSO :
 *   POST https://sso.rmcbfmplay.com/cas/services/rest/3.2/createToken.json
 *   Authorization: Basic Uk1DQkZNUGxheUFuZHJvaWR2MTptb2ViaXVzMTk3MA==
 *   Content-Type: application/x-www-form-urlencoded
 *   Body: username=<email>&password=<password>
 *   → JSON { "accessToken": "BFM_xxxx", "expiresIn": 86400 }
 *
 * Le token est ensuite sauvé dans BfmAuth (SharedPrefs replay_auth_bfm)
 * pour être utilisé par BfmResolver (replays + lives DRM).
 */
object BfmSsoAuth {
    private const val TAG = "BfmSsoAuth"

    // ── Endpoints ──

    // Ancien REST SSO (stratégie 3 = fallback)
    private const val SSO_CREATE_TOKEN =
        "https://sso.rmcbfmplay.com/cas/services/rest/3.2/createToken.json"
    // Secret app BFM Play Android (base64 de "RMCBFMPlayAndroidv1:moebius1970")
    private const val APP_SECRET =
        "Basic Uk1DQkZNUGxheUFuZHJvaWR2MTptb2ViaXVzMTk3MA=="

    // OIDC ROPC (stratégie 1 — même client_id que le WebView OIDC)
    private const val OIDC_TOKEN_ENDPOINT =
        "https://sso.rmcbfmplay.com/cas/oidc/accessToken"
    private const val OIDC_CLIENT_ID =
        "uMgFIVzSfbUsjxGCHSALcyZJbdjSfMqasY"
    private const val OIDC_REDIRECT_URI =
        "https://www.rmcbfmplay.com"

    // CAS REST TGT (stratégie 2)
    private const val CAS_TICKETS_ENDPOINT =
        "https://sso.rmcbfmplay.com/cas/v1/tickets"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** SharedPrefs dédiées pour les credentials BFM (séparées du token). */
    private const val PREF_NAME = "replay_auth_bfm_creds"
    private const val K_EMAIL = "email"
    private const val K_PASSWORD = "password"

    // 2026-06-23 (user "BFMTV connexion fait que de ban mon identifiant mot
    //   de passe — on avait mis une règle pour éviter ça") : protection
    //   anti-ban serveur. Compteur d'échecs persisté + cooldown 24h.
    //   Au-delà de MAX_FAIL_COUNT échecs consécutifs, REFUSE tout nouveau
    //   relogin pendant FAIL_COOLDOWN_MS pour ne pas que le compte BFM se
    //   fasse bloquer par sécurité serveur (5 tentatives → password reset
    //   obligatoire). Reset auto à chaque login réussi.
    private const val K_FAIL_COUNT = "fail_count"
    private const val K_LAST_FAIL_TS = "last_fail_ts"
    private const val MAX_FAIL_COUNT = 3   // 3 échecs max (user safety) — au-delà = blocage local
    private const val FAIL_COOLDOWN_MS = 24L * 60 * 60 * 1000  // 24h

    // 2026-06-23 (protection anti-ban) : fast-fail sur 401/bad creds.
    //   Si la 1ère stratégie OIDC FORM détecte "mauvais mot de passe" dans
    //   la réponse, on STOP les 3 autres stratégies inutiles (= chacune
    //   compterait pour 1 tentative côté serveur BFM → ban encore plus
    //   rapide). Le user devra reset son password OU mettre à jour son
    //   password dans Paramètres → BFM Play.
    @Volatile private var lastDetectedBadCreds = false

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    // ── Gestion des credentials ──

    fun saveCredentials(ctx: Context, email: String, password: String) {
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(K_EMAIL, email)
            .putString(K_PASSWORD, password)
            // 2026-06-23 : reset auto du compteur d'échecs quand l'user
            //   met à jour son mot de passe (= il vient de le reset après ban).
            .putInt(K_FAIL_COUNT, 0)
            .putLong(K_LAST_FAIL_TS, 0L)
            .apply()
        Log.d(TAG, "Credentials saved (email=${email.take(5)}…) + compteur reset")
    }

    fun hasCredentials(ctx: Context): Boolean {
        val p = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return !p.getString(K_EMAIL, null).isNullOrBlank() &&
               !p.getString(K_PASSWORD, null).isNullOrBlank()
    }

    fun clearCredentials(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
        Log.d(TAG, "Credentials cleared")
    }

    // 2026-06-23 (protection anti-ban) : compteur d'échecs ──────────────

    /** Lit le compteur d'échecs consécutifs. Reset auto si dernier fail > 24h. */
    private fun getFailCount(ctx: Context): Int {
        val p = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val ts = p.getLong(K_LAST_FAIL_TS, 0L)
        if (ts > 0 && System.currentTimeMillis() - ts > FAIL_COOLDOWN_MS) {
            // Cooldown expiré → reset auto
            p.edit().putInt(K_FAIL_COUNT, 0).putLong(K_LAST_FAIL_TS, 0L).apply()
            Log.d(TAG, "Cooldown 24h expiré → compteur d'échecs reset")
            return 0
        }
        return p.getInt(K_FAIL_COUNT, 0)
    }

    /** Vérifie si on a atteint le plafond d'échecs (= ban anti-spam). */
    fun isLoginBlocked(ctx: Context): Boolean {
        val count = getFailCount(ctx)
        if (count >= MAX_FAIL_COUNT) {
            val p = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val ts = p.getLong(K_LAST_FAIL_TS, 0L)
            val remainingH = ((FAIL_COOLDOWN_MS - (System.currentTimeMillis() - ts)) / 1000L / 3600L).toInt()
            Log.w(TAG, "LOGIN BLOQUÉ : $count échecs consécutifs, cooldown encore ${remainingH}h")
            return true
        }
        return false
    }

    /** Incrément compteur d'échec après un login fail. */
    private fun recordLoginFailure(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val newCount = p.getInt(K_FAIL_COUNT, 0) + 1
        p.edit()
            .putInt(K_FAIL_COUNT, newCount)
            .putLong(K_LAST_FAIL_TS, System.currentTimeMillis())
            .apply()
        Log.w(TAG, "Login fail #$newCount/$MAX_FAIL_COUNT (au-delà = blocage anti-ban 24h)")
    }

    /** Reset compteur après login réussi. */
    private fun recordLoginSuccess(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (p.getInt(K_FAIL_COUNT, 0) > 0) {
            p.edit().putInt(K_FAIL_COUNT, 0).putLong(K_LAST_FAIL_TS, 0L).apply()
            Log.d(TAG, "Login OK → compteur d'échecs reset")
        }
    }

    /** Reset manuel (= utilisé quand l'user reset son mot de passe). */
    fun resetFailCounter(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putInt(K_FAIL_COUNT, 0).putLong(K_LAST_FAIL_TS, 0L).apply()
        Log.d(TAG, "Compteur d'échecs reset manuellement")
    }

    fun savedEmail(ctx: Context): String? =
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(K_EMAIL, null)

    fun savedPassword(ctx: Context): String? =
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(K_PASSWORD, null)

    // ── Login REST ──

    // Client dédié au flow OIDC form : cookie jar + PAS de redirect auto
    private val oidcFormClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)   // on suit les redirects manuellement
            .followSslRedirects(false)
            .cookieJar(object : CookieJar {
                private val store = mutableListOf<Cookie>()
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    store.removeAll { existing ->
                        cookies.any { it.name == existing.name && it.domain == existing.domain }
                    }
                    store.addAll(cookies)
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    store.filter { it.matches(url) }
            })
            .build()
    }

    /**
     * Login via SSO. Tente 4 stratégies dans l'ordre :
     *   1. OIDC FORM (headless) — simule le formulaire CAS login via HTTP
     *      pur, comme un navigateur : GET authorize → parse form → POST
     *      credentials → suit les redirects → capture le JWT.
     *      C'est la stratégie la plus fiable car c'est EXACTEMENT ce que
     *      fait le navigateur (et ça marche).
     *   2. OIDC ROPC (grant_type=password)
     *   3. CAS REST TGT
     *   4. Ancien REST SSO (Basic Auth)
     *
     * Retourne le token BFM (BFM_xxx) ou null.
     * À appeler en IO dispatcher.
     */
    suspend fun login(ctx: Context, email: String, password: String): String? =
        withContext(Dispatchers.IO) {
            // 2026-06-23 (protection anti-ban) : check compteur d'échecs AVANT
            //   de tenter le login. Au-delà de MAX_FAIL_COUNT échecs, on
            //   refuse pour ne pas que le compte BFM se fasse bloquer.
            if (isLoginBlocked(ctx)) {
                Log.w(TAG, "Login REFUSÉ — compteur d'échecs atteint, cooldown 24h actif")
                return@withContext null
            }
            // Reset le flag bad creds avant chaque tentative
            lastDetectedBadCreds = false

            // Stratégie 1 : OIDC FORM headless (simule le navigateur)
            val formToken = loginViaOidcForm(ctx, email, password)
            if (formToken != null) {
                recordLoginSuccess(ctx)
                return@withContext formToken
            }
            // 2026-06-23 (fast-fail) : si OIDC FORM a détecté un 401/bad creds,
            //   c'est inutile (et dangereux) d'essayer les 3 autres stratégies
            //   avec le même mot de passe — chaque tentative compte côté BFM
            //   et peut accélérer le ban. On stop net.
            if (lastDetectedBadCreds) {
                Log.w(TAG, "FAST-FAIL : OIDC FORM a confirmé bad credentials — skip ROPC/CAS/REST")
                recordLoginFailure(ctx)
                return@withContext null
            }

            // Stratégie 2 : OIDC ROPC (Resource Owner Password Credentials)
            val ropcToken = loginViaRopc(ctx, email, password)
            if (ropcToken != null) {
                recordLoginSuccess(ctx)
                return@withContext ropcToken
            }

            // Stratégie 3 : CAS REST TGT → Service Ticket → validate
            val tgtToken = loginViaCasTgt(ctx, email, password)
            if (tgtToken != null) {
                recordLoginSuccess(ctx)
                return@withContext tgtToken
            }

            // Stratégie 4 : Ancien REST SSO (Basic Auth)
            val ssoToken = loginViaRestSso(ctx, email, password)
            if (ssoToken != null) {
                recordLoginSuccess(ctx)
                return@withContext ssoToken
            }

            Log.w(TAG, "All 4 login strategies failed")
            recordLoginFailure(ctx)  // ← protection anti-ban : +1 au compteur
            null
        }

    /**
     * Stratégie 1 : OIDC FORM headless.
     * Simule exactement le flow navigateur :
     *   GET /cas/oidc/authorize?... → 302 → /cas/login?service=...
     *   → parse HTML form (lt, execution, _eventId)
     *   → POST /cas/login avec credentials + hidden fields
     *   → 302 → /cas/oidc/callbackAuthorize?... → 302 → redirect_uri?access_token=JWT
     *   → extraire le JWT → décoder tu → BFM_ token
     */
    private fun loginViaOidcForm(ctx: Context, email: String, password: String): String? {
        return try {
            val authorizeUrl = "https://sso.rmcbfmplay.com/cas/oidc/authorize" +
                "?client_id=$OIDC_CLIENT_ID" +
                "&response_type=token" +
                "&redirect_uri=${URLEncoder.encode(OIDC_REDIRECT_URI, "UTF-8")}" +
                "&scope=openid"

            // Étape 1 : GET authorize → suit les redirects jusqu'au formulaire login
            var currentUrl = authorizeUrl
            var loginPageHtml: String? = null
            var loginFormUrl: String? = null

            for (i in 0 until 10) { // max 10 redirects
                val req = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .build()
                val resp = oidcFormClient.newCall(req).execute()
                val code = resp.code
                val body = resp.body?.string().orEmpty()
                resp.close()

                if (code in 301..303 || code == 307 || code == 308) {
                    val location = resp.header("Location") ?: break
                    // Vérifier si le redirect contient déjà le token
                    val tokenFromRedirect = extractTokenFromUrl(location)
                    if (tokenFromRedirect != null) {
                        val bfmToken = extractBfmToken(tokenFromRedirect)
                        if (bfmToken.isNotBlank()) {
                            BfmAuth.saveToken(ctx, bfmToken,
                                exp = (System.currentTimeMillis() / 1000L) + 86400L)
                            fetchAccountId(ctx, bfmToken)
                            Log.i(TAG, "✓ OIDC FORM login OK at redirect #$i (token len=${bfmToken.length})")
                            return bfmToken
                        }
                    }
                    // Résoudre URL relative
                    currentUrl = resolveUrl(currentUrl, location)
                    continue
                }

                if (code == 200 && body.contains("name=\"username\"", ignoreCase = true)) {
                    loginPageHtml = body
                    loginFormUrl = currentUrl
                    break
                }

                // Réponse inattendue
                Log.w(TAG, "OIDC FORM: unexpected HTTP $code at redirect #$i for $currentUrl")
                break
            }

            if (loginPageHtml == null || loginFormUrl == null) {
                Log.w(TAG, "OIDC FORM: could not reach login form")
                return null
            }

            Log.d(TAG, "OIDC FORM: login form found at $loginFormUrl (html len=${loginPageHtml.length})")

            // Étape 2 : parse le formulaire pour extraire les champs hidden
            val lt = extractHiddenField(loginPageHtml, "lt") ?: ""
            val execution = extractHiddenField(loginPageHtml, "execution") ?: ""
            val eventId = extractHiddenField(loginPageHtml, "_eventId") ?: "submit"
            // 2026-06-22 (user "BFM ne se connecte plus, ça fonctionnait avant,
            //   ils ont changé quelque chose") : nouveau champ caché `lrt`
            //   (Login Reverse Token) ajouté côté CAS sso.rmcbfmplay.com.
            //   Sans ce champ, le POST login retourne 200 sans redirect (=
            //   credentials rejetés silencieusement).
            val lrt = extractHiddenField(loginPageHtml, "lrt") ?: ""

            // Déterminer l'URL de POST du formulaire
            val formAction = extractFormAction(loginPageHtml)
            val postUrl = if (formAction != null) resolveUrl(loginFormUrl, formAction) else loginFormUrl

            Log.d(TAG, "OIDC FORM: POST $postUrl (lt=${lt.take(20)}, lrt=${lrt.take(20)}, execution=${execution.take(20)}, eventId=$eventId)")

            // Étape 3 : POST les credentials
            val postBody = buildString {
                append("username=").append(URLEncoder.encode(email, "UTF-8"))
                append("&password=").append(URLEncoder.encode(password, "UTF-8"))
                if (lt.isNotBlank()) append("&lt=").append(URLEncoder.encode(lt, "UTF-8"))
                if (lrt.isNotBlank()) append("&lrt=").append(URLEncoder.encode(lrt, "UTF-8"))
                if (execution.isNotBlank()) append("&execution=").append(URLEncoder.encode(execution, "UTF-8"))
                append("&_eventId=").append(URLEncoder.encode(eventId, "UTF-8"))
                // 2026-06-22 : remember-me coché par défaut côté CAS (= session
                //   plus longue). Sans ça, certains serveurs CAS rejettent le
                //   POST si une checkbox attendue est absente.
                append("&remember-me=on")
                // Certaines versions CAS ont aussi un "submit" button
                append("&submit=Se+connecter")
            }

            val loginReq = Request.Builder()
                .url(postUrl)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", loginFormUrl)
                .post(postBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            val loginResp = oidcFormClient.newCall(loginReq).execute()
            val loginCode = loginResp.code
            val loginBody = loginResp.body?.string().orEmpty()
            loginResp.close()

            // Étape 4 : suivre les redirects après le POST login
            if (loginCode == 200 && loginBody.contains("errors", ignoreCase = true) &&
                (loginBody.contains("mot de passe", ignoreCase = true) ||
                 loginBody.contains("incorrect", ignoreCase = true) ||
                 loginBody.contains("invalid", ignoreCase = true))) {
                Log.w(TAG, "OIDC FORM: bad credentials (login page returned with error)")
                lastDetectedBadCreds = true  // ← fast-fail : stop les 3 autres stratégies
                return null
            }
            // 2026-06-23 (fast-fail) : 401 Unauthorized = bad creds confirmé.
            if (loginCode == 401 || loginCode == 403) {
                Log.w(TAG, "OIDC FORM: HTTP $loginCode — bad credentials confirmé")
                lastDetectedBadCreds = true
                return null
            }

            // Suivre les redirects chaînés
            var nextUrl: String? = if (loginCode in 301..303 || loginCode == 307) {
                loginResp.header("Location")
            } else {
                // Peut-être que le body contient une meta redirect ou le token directement
                extractTokenFromUrl(loginBody)?.let { rawToken ->
                    val bfmToken = extractBfmToken(rawToken)
                    if (bfmToken.isNotBlank()) {
                        BfmAuth.saveToken(ctx, bfmToken,
                            exp = (System.currentTimeMillis() / 1000L) + 86400L)
                        fetchAccountId(ctx, bfmToken)
                        Log.i(TAG, "✓ OIDC FORM login OK from body (token len=${bfmToken.length})")
                        return bfmToken
                    }
                }
                Log.w(TAG, "OIDC FORM: POST login returned $loginCode, no redirect (body: ${loginBody.take(300)})")
                return null
            }

            for (i in 0 until 15) { // max 15 redirects chaînés
                if (nextUrl == null) break

                val fullUrl = resolveUrl(postUrl, nextUrl)

                // Vérifier si cette URL contient le token
                val tokenFromUrl = extractTokenFromUrl(fullUrl)
                if (tokenFromUrl != null) {
                    val bfmToken = extractBfmToken(tokenFromUrl)
                    if (bfmToken.isNotBlank()) {
                        BfmAuth.saveToken(ctx, bfmToken,
                            exp = (System.currentTimeMillis() / 1000L) + 86400L)
                        fetchAccountId(ctx, bfmToken)
                        Log.i(TAG, "✓ OIDC FORM login OK at post-redirect #$i (token len=${bfmToken.length})")
                        return bfmToken
                    }
                }

                // Suivre le redirect
                val redirReq = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .build()
                val redirResp = oidcFormClient.newCall(redirReq).execute()
                val redirCode = redirResp.code
                val redirBody = redirResp.body?.string().orEmpty()
                redirResp.close()

                if (redirCode in 301..303 || redirCode == 307) {
                    nextUrl = redirResp.header("Location")
                    continue
                }

                // Plus de redirects — vérifier le body pour un token
                val tokenFromBody = extractTokenFromUrl(redirBody)
                if (tokenFromBody != null) {
                    val bfmToken = extractBfmToken(tokenFromBody)
                    if (bfmToken.isNotBlank()) {
                        BfmAuth.saveToken(ctx, bfmToken,
                            exp = (System.currentTimeMillis() / 1000L) + 86400L)
                        fetchAccountId(ctx, bfmToken)
                        Log.i(TAG, "✓ OIDC FORM login OK from final body (token len=${bfmToken.length})")
                        return bfmToken
                    }
                }

                Log.w(TAG, "OIDC FORM: post-redirect #$i ended with HTTP $redirCode, no token")
                break
            }

            Log.w(TAG, "OIDC FORM: completed redirect chain without finding token")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "OIDC FORM login crashed: ${e.message}", e)
            null
        }
    }

    /** Extrait un access_token d'une URL (query string ou fragment). */
    private fun extractTokenFromUrl(url: String): String? {
        // Chercher dans query string: ?access_token=... ou &access_token=...
        val queryMatch = Regex("""[?&]access_token=([A-Za-z0-9._\-+/=%]+)""").find(url)
        if (queryMatch != null) return java.net.URLDecoder.decode(queryMatch.groupValues[1], "UTF-8")

        // Chercher dans fragment: #access_token=...
        val fragMatch = Regex("""#access_token=([A-Za-z0-9._\-+/=%]+)""").find(url)
        if (fragMatch != null) return java.net.URLDecoder.decode(fragMatch.groupValues[1], "UTF-8")

        return null
    }

    /** Extrait la valeur d'un champ hidden d'un formulaire HTML. */
    private fun extractHiddenField(html: String, name: String): String? {
        // Pattern: <input type="hidden" name="lt" value="..." />
        val regex = Regex(
            """<input[^>]*name\s*=\s*["']${Regex.escape(name)}["'][^>]*value\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(html)
        if (match != null) return match.groupValues[1]

        // Ordre inversé: value avant name
        val regex2 = Regex(
            """<input[^>]*value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']${Regex.escape(name)}["']""",
            RegexOption.IGNORE_CASE
        )
        return regex2.find(html)?.groupValues?.get(1)
    }

    /** Extrait l'action du premier <form> avec method POST. */
    private fun extractFormAction(html: String): String? {
        val regex = Regex(
            """<form[^>]*action\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
    }

    /** Résout une URL relative par rapport à une URL de base. */
    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return try {
            java.net.URI(base).resolve(relative).toString()
        } catch (_: Exception) {
            // Fallback simple : base scheme+authority + relative path
            val baseUri = java.net.URI(base)
            "${baseUri.scheme}://${baseUri.authority}$relative"
        }
    }

    /**
     * Stratégie 2 : OAuth2 ROPC (Resource Owner Password Credentials).
     * Utilise le même client_id que le WebView OIDC.
     */
    private fun loginViaRopc(ctx: Context, email: String, password: String): String? {
        return try {
            val body = "grant_type=password" +
                "&client_id=$OIDC_CLIENT_ID" +
                "&username=${URLEncoder.encode(email, "UTF-8")}" +
                "&password=${URLEncoder.encode(password, "UTF-8")}" +
                "&scope=openid"

            val req = Request.Builder()
                .url(OIDC_TOKEN_ENDPOINT)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "ROPC login HTTP ${resp.code}: ${respBody.take(200)}")
                    return null
                }
                val json = try { JSONObject(respBody) } catch (_: Exception) {
                    Log.e(TAG, "ROPC JSON parse error: ${respBody.take(200)}")
                    return null
                }
                val rawToken = json.optString("access_token",
                    json.optString("id_token", ""))
                if (rawToken.isBlank()) {
                    Log.w(TAG, "ROPC: no access_token in response: ${respBody.take(200)}")
                    return null
                }
                val bfmToken = extractBfmToken(rawToken)
                if (bfmToken.isBlank()) {
                    Log.w(TAG, "ROPC: could not extract BFM_ token from JWT")
                    return null
                }
                val expiresIn = json.optLong("expires_in", 86400L)
                val expTimestamp = (System.currentTimeMillis() / 1000L) + expiresIn
                BfmAuth.saveToken(ctx, bfmToken, exp = expTimestamp)
                fetchAccountId(ctx, bfmToken)
                Log.i(TAG, "✓ ROPC login OK (token len=${bfmToken.length}, expires_in=$expiresIn)")
                bfmToken
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ROPC login crashed: ${e.message}")
            null
        }
    }

    /**
     * Stratégie 2 : CAS REST protocol (standard CAS 5/6/7).
     *   POST /cas/v1/tickets → TGT
     *   POST /cas/v1/tickets/TGT-xxx → ST
     *   GET  /cas/p3/serviceValidate?ticket=ST&service=... → XML avec JWT
     */
    private fun loginViaCasTgt(ctx: Context, email: String, password: String): String? {
        return try {
            // Étape 1 : obtenir un TGT
            val tgtBody = "username=${URLEncoder.encode(email, "UTF-8")}" +
                "&password=${URLEncoder.encode(password, "UTF-8")}"
            val tgtReq = Request.Builder()
                .url(CAS_TICKETS_ENDPOINT)
                .header("User-Agent", UA)
                .post(tgtBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            val tgtUrl: String
            client.newCall(tgtReq).execute().use { resp ->
                if (resp.code != 201 && !resp.isSuccessful) {
                    Log.w(TAG, "CAS TGT HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                    return null
                }
                // Le TGT est dans le header Location ou dans le body
                tgtUrl = resp.header("Location")
                    ?: resp.body?.string()?.trim()?.let { body ->
                        if (body.contains("TGT-")) body else null
                    }
                    ?: run {
                        Log.w(TAG, "CAS TGT: no Location header")
                        return null
                    }
            }
            Log.d(TAG, "CAS TGT obtained: ${tgtUrl.takeLast(30)}")

            // Étape 2 : obtenir un Service Ticket
            val stBody = "service=${URLEncoder.encode(OIDC_REDIRECT_URI, "UTF-8")}"
            val stReq = Request.Builder()
                .url(tgtUrl)
                .header("User-Agent", UA)
                .post(stBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            val serviceTicket: String
            client.newCall(stReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "CAS ST HTTP ${resp.code}")
                    return null
                }
                serviceTicket = resp.body?.string()?.trim() ?: return null
            }
            Log.d(TAG, "CAS ST obtained: ${serviceTicket.take(30)}")

            // Étape 3 : valider le ST pour obtenir un JWT
            val validateUrl = "https://sso.rmcbfmplay.com/cas/p3/serviceValidate" +
                "?ticket=${URLEncoder.encode(serviceTicket, "UTF-8")}" +
                "&service=${URLEncoder.encode(OIDC_REDIRECT_URI, "UTF-8")}" +
                "&format=JSON"
            val valReq = Request.Builder()
                .url(validateUrl)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .build()

            client.newCall(valReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "CAS validate HTTP ${resp.code}: ${body.take(200)}")
                    return null
                }
                // Chercher un token dans la réponse (JSON ou XML)
                val tokenMatch = Regex("""BFM_[A-Za-z0-9+/=]+""").find(body)
                    ?: Regex("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+""").find(body)
                if (tokenMatch == null) {
                    Log.w(TAG, "CAS validate: no token found in response: ${body.take(300)}")
                    return null
                }
                val rawToken = tokenMatch.value
                val bfmToken = extractBfmToken(rawToken)
                if (bfmToken.isBlank()) {
                    Log.w(TAG, "CAS validate: could not extract BFM_ from token")
                    return null
                }
                BfmAuth.saveToken(ctx, bfmToken, exp = (System.currentTimeMillis() / 1000L) + 86400L)
                fetchAccountId(ctx, bfmToken)
                Log.i(TAG, "✓ CAS TGT login OK (token len=${bfmToken.length})")
                return bfmToken
            }
        } catch (e: Throwable) {
            Log.w(TAG, "CAS TGT login crashed: ${e.message}")
            null
        }
    }

    /**
     * Stratégie 3 : Ancien REST SSO avec Basic Auth.
     * Peut redevenir actif si BFM réactive le vieux endpoint.
     */
    private fun loginViaRestSso(ctx: Context, email: String, password: String): String? {
        return try {
            val body = "username=${URLEncoder.encode(email, "UTF-8")}" +
                "&password=${URLEncoder.encode(password, "UTF-8")}"

            val req = Request.Builder()
                .url(SSO_CREATE_TOKEN)
                .header("Authorization", APP_SECRET)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "REST SSO login HTTP ${resp.code}: ${respBody.take(200)}")
                    return null
                }

                val json = try { JSONObject(respBody) } catch (_: Exception) {
                    Log.e(TAG, "REST SSO JSON parse error: ${respBody.take(200)}")
                    return null
                }

                var token = json.optString("accessToken", "")
                if (token.isBlank()) token = json.optString("access_token", "")
                if (token.isBlank()) token = json.optString("tgt", "")
                if (token.isBlank()) token = json.optString("ticket", "")

                val bfmToken = extractBfmToken(token)
                if (bfmToken.isBlank()) {
                    Log.e(TAG, "REST SSO: No token in response: ${respBody.take(200)}")
                    return null
                }

                val expiresIn = json.optLong("expiresIn",
                    json.optLong("expires_in", 86400L))
                val expTimestamp = (System.currentTimeMillis() / 1000L) + expiresIn
                BfmAuth.saveToken(ctx, bfmToken, exp = expTimestamp)
                fetchAccountId(ctx, bfmToken)
                Log.i(TAG, "✓ REST SSO login OK (token len=${bfmToken.length}, expires_in=$expiresIn)")
                bfmToken
            }
        } catch (e: Throwable) {
            Log.w(TAG, "REST SSO login crashed: ${e.message}")
            null
        }
    }

    /**
     * Extraire le token BFM_ d'un JWT ou retourner tel quel si déjà BFM_.
     */
    private fun extractBfmToken(rawToken: String): String {
        if (rawToken.isBlank()) return ""
        if (rawToken.startsWith("BFM_")) return rawToken
        if (!rawToken.startsWith("eyJ")) return rawToken // token inconnu, tenter tel quel

        // Décoder le JWT pour extraire le champ "tu" (token user = BFM_xxx)
        return try {
            val parts = rawToken.split(".")
            if (parts.size < 2) return rawToken
            val payloadB64 = parts[1]
                .replace('-', '+').replace('_', '/')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }
            val payloadJson = String(
                android.util.Base64.decode(payloadB64, android.util.Base64.DEFAULT)
            )
            val payload = JSONObject(payloadJson)
            val tu = payload.optString("tu", "")
            if (tu.startsWith("BFM_")) {
                Log.d(TAG, "JWT decoded → tu extracted (len=${tu.length})")
                tu
            } else {
                Log.w(TAG, "JWT has no BFM_ tu field, using raw JWT")
                rawToken
            }
        } catch (e: Exception) {
            Log.w(TAG, "JWT decode failed: ${e.message}")
            rawToken
        }
    }

    /**
     * Re-login automatique avec les credentials sauvegardés.
     * Retourne le token BFM ou null si pas de credentials / échec.
     */
    suspend fun reloginFromSaved(ctx: Context): String? {
        // 2026-06-23 (user "BFMTV ban mon mot de passe encore — éviter 5
        //   erreurs sinon password reset obligatoire") : check compteur
        //   AVANT de tenter le relogin. Si bloqué, retourne null direct +
        //   Toast pour informer l'user qu'il doit reset son password.
        if (isLoginBlocked(ctx)) {
            Log.w(TAG, "Auto-relogin BLOQUÉ par protection anti-ban (3 fails consécutifs)")
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        ctx,
                        "BFM Play : connexion bloquée localement (3 échecs). Vérifie ton mot de passe dans Paramètres → BFM Play et reset le compteur après l'avoir mis à jour.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: Throwable) {}
            return null
        }
        val p = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val email = p.getString(K_EMAIL, null) ?: return null
        val pass = p.getString(K_PASSWORD, null) ?: return null
        Log.d(TAG, "Attempting auto-relogin for ${email.take(5)}…")
        return login(ctx, email, pass)
    }

    /**
     * Récupère l'accountId BFM via l'API profils (background, non bloquant).
     */
    private fun fetchAccountId(ctx: Context, token: String) {
        try {
            val url = "https://ws-backendtv.rmcbfmplay.com/heimdall-core/public/api/v2/userProfiles?token=$token"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    val accountId = json.optString("accountId",
                        json.optString("userId",
                            json.optString("id", "")))
                    if (accountId.isNotBlank()) {
                        BfmAuth.saveAccountId(ctx, accountId)
                        Log.d(TAG, "accountId saved: ${accountId.take(10)}…")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchAccountId failed: ${e.message}")
        }
    }
}
