package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-09-10 — Authentification Bowd (bowdtv.com), calquée sur [TF1GigyaAuth].
 *
 *  Mesuré avant d'écrire (ne pas re-tester à l'aveugle) :
 *   • La CONNEXION n'exige PAS de captcha : POST /api/auth/sign-in/username ne réclame
 *     que `username` + `password` (vérifié : le 400 ne liste que ces deux champs).
 *     Seule l'INSCRIPTION exige un turnstileToken — c'est pourquoi le compte se crée
 *     à la main, une fois, et jamais par l'app.
 *   • Ensuite GET /api/auth/token (avec le cookie de session) rend le JWT, et c'est ce
 *     JWT qui ouvre api.bowdtv.com en Bearer. C'est la chaîne que fait leur propre front.
 *
 *  D'où la reconnexion automatique en tâche de fond (user : « il faut que les identifiants
 *  soient sauvegardés pour une connexion automatique après ») : les identifiants sont
 *  gardés localement, et dès que le JWT manque ou expire on refait le login en silence.
 *  Aucun écran, aucune ressaisie — exactement le comportement TF1+/M6.
 *
 *  Le stockage suit la convention du projet (SharedPreferences en clair, comme
 *  `replay_auth_tf1_creds`). C'est le compte de l'utilisateur sur un service tiers,
 *  sur son propre appareil ; si tu veux durcir, la bonne marche est d'ajouter
 *  androidx.security:security-crypto et de migrer TOUS les stockages d'identifiants
 *  d'un coup, pas seulement celui-ci.
 */
object BowdAuth {

    private const val TAG = "BowdAuth"
    private const val AUTH = "https://auth.bowdtv.com"

    private const val PREF_NAME = "bowd_creds"
    private const val K_USER = "username"
    private const val K_PASS = "password"
    private const val K_JWT = "jwt"
    private const val K_JWT_EXP = "jwt_exp"

    private val JSON by lazy { "application/json".toMediaType() }
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Cookie de session, en mémoire : il ne survit pas au redémarrage, et c'est
     *  volontaire — au démarrage suivant on refait un login silencieux. */
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore.getOrPut(url.host) { mutableListOf() }.let { liste ->
                for (c in cookies) {
                    liste.removeAll { it.name == c.name }
                    liste.add(c)
                }
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host]?.toList() ?: emptyList()
    }

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var jwt: String? = null
    @Volatile private var jwtExpSec: Long = 0L
    @Volatile private var ctxAppli: Context? = null

    /** Recharge le jeton persisté au premier usage du process. */
    private fun restaurerJeton(ctx: Context) {
        if (jwt != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val t = p.getString(K_JWT, null) ?: return
        val exp = p.getLong(K_JWT_EXP, 0L)
        if (exp > 0 && System.currentTimeMillis() / 1000L >= exp - 60) return  // périmé
        jwt = t; jwtExpSec = exp
    }

    // ─────────────────────────────── Identifiants

    fun saveCredentials(ctx: Context, username: String, password: String) {
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(K_USER, username).putString(K_PASS, password).apply()
    }

    fun hasCredentials(ctx: Context): Boolean {
        val p = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return !p.getString(K_USER, null).isNullOrBlank() && !p.getString(K_PASS, null).isNullOrBlank()
    }

    fun clearCredentials(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
        jwt = null; jwtExpSec = 0L; cookieStore.clear()
    }

    fun savedUsername(ctx: Context): String? =
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(K_USER, null)

    /** Public depuis 2026-09-10 : les réglages permettent de RELIRE ses identifiants
     *  (cf. « Compte Bowd »), pour pouvoir les ressaisir sur un autre appareil. */
    fun savedPassword(ctx: Context): String? =
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(K_PASS, null)

    // ─────────────────────────────── Connexion

    /** Connexion + récupération du JWT. Rend le JWT, ou null si échec. */
    suspend fun login(username: String, password: String): String? = withContext(Dispatchers.IO) {
        try {
            val corps = JSONObject()
                .put("username", username)
                .put("password", password)
                .toString()
            val req = okhttp3.Request.Builder()
                .url("$AUTH/api/auth/sign-in/username")
                .header("content-type", "application/json")
                .header("User-Agent", UA)
                .post(corps.toRequestBody(JSON))
                .build()
            val ok = client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "connexion refusée : HTTP ${r.code}")
                    false
                } else true
            }
            if (!ok) return@withContext null
            // Le cookie de session est maintenant dans le jar → on échange contre le JWT.
            recupererJwt()
        } catch (t: Throwable) {
            Log.w(TAG, "connexion KO : ${t.message}")
            null
        }
    }

    private fun recupererJwt(): String? = try {
        val req = okhttp3.Request.Builder()
            .url("$AUTH/api/auth/token")
            .header("User-Agent", UA)
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "jeton refusé : HTTP ${r.code}"); null }
            else {
                val t = JSONObject(r.body?.string().orEmpty()).optString("token").takeIf { it.isNotBlank() }
                if (t != null) {
                    jwt = t
                    jwtExpSec = expDuJwt(t) ?: 0L
                    // Persisté : sans ça, chaque démarrage de l'app repayait une
                    //   reconnexion complète (~11 s mesurées) DANS le chemin critique
                    //   des sources de secours, qui abandonnaient avant la fin.
                    ctxAppli?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)?.edit()
                        ?.putString(K_JWT, t)?.putLong(K_JWT_EXP, jwtExpSec)?.apply()
                }
                t
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "jeton KO : ${t.message}")
        null
    }

    /**
     * JWT courant, avec reconnexion silencieuse si besoin. C'est LE point d'entrée :
     * tout appel authentifié passe par ici et n'a jamais à se soucier de l'expiration.
     */
    suspend fun jeton(ctx: Context): String? = withContext(Dispatchers.IO) {
        ctxAppli = ctx.applicationContext
        restaurerJeton(ctx)
        val maintenantSec = System.currentTimeMillis() / 1000L
        jwt?.let { if (jwtExpSec == 0L || maintenantSec < jwtExpSec - 60) return@withContext it }
        // Cookie encore valide ? On tente l'échange direct avant de renvoyer le mot de passe.
        if (cookieStore.isNotEmpty()) recupererJwt()?.let { return@withContext it }
        val u = savedUsername(ctx) ?: return@withContext null
        val p = savedPassword(ctx) ?: return@withContext null
        Log.d(TAG, "session expirée → reconnexion silencieuse")
        login(u, p)
    }

    /**
     * Jette la session courante et en refait une COMPLÈTE depuis les identifiants.
     *
     * Nécessaire parce qu'un cookie encore présent mais mort côté serveur rend un jeton
     * d'apparence valide que l'API refuse ensuite en 401 — c'est exactement le bug observé
     * (index VOD : HTTP 401 après « reconnexion silencieuse »). Leur propre front fait le
     * même geste : sur 401, il force la résolution du JWT et rejoue la requête.
     */
    suspend fun jetonFrais(ctx: Context): String? = withContext(Dispatchers.IO) {
        jwt = null; jwtExpSec = 0L; cookieStore.clear()
        ctxAppli = ctx.applicationContext
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().remove(K_JWT).remove(K_JWT_EXP).apply()
        val u = savedUsername(ctx) ?: return@withContext null
        val p = savedPassword(ctx) ?: return@withContext null
        Log.d(TAG, "401 → reconnexion complète")
        login(u, p)
    }

    fun estConnecte(ctx: Context): Boolean = jwt != null || hasCredentials(ctx)

    private fun expDuJwt(token: String): Long? = try {
        val charge = token.split(".").getOrNull(1) ?: return null
        val json = String(android.util.Base64.decode(charge, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING))
        JSONObject(json).optLong("exp").takeIf { it > 0 }
    } catch (_: Throwable) { null }
}
