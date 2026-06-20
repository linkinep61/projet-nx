package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Stockage des tokens de session pour les replays qui nécessitent un compte
 * utilisateur (TF1+, M6 6play, etc.). Le token est obtenu par
 * `LoginWebViewActivity` qui intercepte les cookies après login réussi.
 *
 * Implémenté comme 2 objects : `TF1Auth` et `M6Auth`, qui partagent la même
 * mécanique. Chacun lit/écrit dans son propre fichier SharedPreferences pour
 * isoler les services.
 */
private abstract class ReplayAuthBase(private val prefName: String) {
    companion object { const val TAG = "ReplayAuth" }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)

    fun saveToken(ctx: Context, token: String, refresh: String? = null, exp: Long? = null) {
        prefs(ctx).edit()
            .putString("token", token)
            .putString("refresh", refresh)
            .putLong("exp", exp ?: 0L)
            .putLong("saved_at", System.currentTimeMillis())
            .apply()
        Log.d(TAG, "$prefName: token saved (len=${token.length}, exp=$exp)")
    }

    /** 2026-06-19 : sauve l'account_id (= UID Gigya pour M6, persona id
     *  pour TF1). Nécessaire pour le DRM Widevine M6 (upfront-token endpoint
     *  prend /users/<UID>/videos/<id>/upfront-token). */
    fun saveAccountId(ctx: Context, accountId: String) {
        prefs(ctx).edit().putString("account_id", accountId).apply()
        Log.d(TAG, "$prefName: account_id saved (len=${accountId.length})")
    }

    fun getAccountId(ctx: Context): String? = prefs(ctx).getString("account_id", null)

    /** 2026-06-19 : pour M6+, l'endpoint front-auth.6cloud.fr/getJwt prend
     *  UID + UIDSignature + signatureTimestamp pour produire un JWT M6, qui
     *  est ensuite utilisé pour fetcher l'upfront-token Widevine. */
    fun saveUidSignature(ctx: Context, sig: String) {
        prefs(ctx).edit().putString("uid_signature", sig).apply()
    }
    fun getUidSignature(ctx: Context): String? = prefs(ctx).getString("uid_signature", null)

    fun saveSignatureTimestamp(ctx: Context, ts: String) {
        prefs(ctx).edit().putString("signature_timestamp", ts).apply()
    }
    fun getSignatureTimestamp(ctx: Context): String? = prefs(ctx).getString("signature_timestamp", null)

    /** JWT M6 produit par front-auth.6cloud.fr/getJwt (= différent du token
     *  Gigya glt_*). Cache-able tant que valide. */
    fun saveM6Jwt(ctx: Context, jwt: String) {
        prefs(ctx).edit().putString("m6_jwt", jwt).apply()
    }
    fun getM6Jwt(ctx: Context): String? = prefs(ctx).getString("m6_jwt", null)

    /** 2026-06-19 : apiKey Gigya extrait du nom du cookie glt_<apiKey>. Permet
     *  d'appeler login-gigya.m6.fr/accounts.getAccountInfo avec le BON apiKey
     *  (= sinon Gigya retourne errorCode 403005 "Unauthorized user"). */
    fun saveApiKey(ctx: Context, apiKey: String) {
        prefs(ctx).edit().putString("api_key", apiKey).apply()
    }
    fun getApiKey(ctx: Context): String? = prefs(ctx).getString("api_key", null)

    fun getToken(ctx: Context): String? {
        val p = prefs(ctx)
        val tok = p.getString("token", null) ?: return null
        val exp = p.getLong("exp", 0L)
        if (exp > 0L && exp < System.currentTimeMillis() / 1000L) {
            Log.d(TAG, "$prefName: token expired (exp=$exp)")
            clearToken(ctx)
            return null
        }
        return tok
    }

    fun getRefresh(ctx: Context): String? = prefs(ctx).getString("refresh", null)

    fun isLoggedIn(ctx: Context): Boolean = getToken(ctx) != null

    fun clearToken(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        Log.d(TAG, "$prefName: token cleared")
    }

    fun savedAt(ctx: Context): Long = prefs(ctx).getLong("saved_at", 0L)
}

/** Token de session TF1+ — récupéré via WebView sur tf1.fr/compte/connexion. */
object TF1Auth {
    private val backend = object : ReplayAuthBase("replay_auth_tf1") {}
    fun saveToken(ctx: Context, token: String, refresh: String? = null, exp: Long? = null) =
        backend.saveToken(ctx, token, refresh, exp)
    fun getToken(ctx: Context): String? = backend.getToken(ctx)
    fun getRefresh(ctx: Context): String? = backend.getRefresh(ctx)
    fun isLoggedIn(ctx: Context): Boolean = backend.isLoggedIn(ctx)
    fun clearToken(ctx: Context) = backend.clearToken(ctx)
    fun savedAt(ctx: Context): Long = backend.savedAt(ctx)
}

/** Token de session M6 6play — récupéré via WebView sur 6play.fr/connexion. */
object M6Auth {
    private val backend = object : ReplayAuthBase("replay_auth_m6") {}
    fun saveToken(ctx: Context, token: String, refresh: String? = null, exp: Long? = null) =
        backend.saveToken(ctx, token, refresh, exp)
    fun getToken(ctx: Context): String? = backend.getToken(ctx)
    fun getRefresh(ctx: Context): String? = backend.getRefresh(ctx)
    fun saveAccountId(ctx: Context, accountId: String) = backend.saveAccountId(ctx, accountId)
    fun getAccountId(ctx: Context): String? = backend.getAccountId(ctx)
    fun saveUidSignature(ctx: Context, sig: String) = backend.saveUidSignature(ctx, sig)
    fun getUidSignature(ctx: Context): String? = backend.getUidSignature(ctx)
    fun saveSignatureTimestamp(ctx: Context, ts: String) = backend.saveSignatureTimestamp(ctx, ts)
    fun getSignatureTimestamp(ctx: Context): String? = backend.getSignatureTimestamp(ctx)
    fun saveM6Jwt(ctx: Context, jwt: String) = backend.saveM6Jwt(ctx, jwt)
    fun getM6Jwt(ctx: Context): String? = backend.getM6Jwt(ctx)
    fun saveApiKey(ctx: Context, apiKey: String) = backend.saveApiKey(ctx, apiKey)
    fun getApiKey(ctx: Context): String? = backend.getApiKey(ctx)
    fun isLoggedIn(ctx: Context): Boolean = backend.isLoggedIn(ctx)
    fun clearToken(ctx: Context) = backend.clearToken(ctx)
    fun savedAt(ctx: Context): Long = backend.savedAt(ctx)
}
