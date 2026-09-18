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

    // 2026-09-17 (user : « TF1 se deconnecte souvent, je suis toujours oblige de
    //   remettre les identifiants, alors que M6 et RMC non »).
    //
    //   CAUSE. `getToken` EFFACAIT TOUT le fichier de preferences des que le jeton
    //   etait perime (`clearToken` = prefs.edit().clear() : jeton, refresh, exp,
    //   account_id, api_key, uid_signature, signature_timestamp, m6_jwt). Et comme
    //   `isLoggedIn` passe par `getToken`, le SIMPLE AFFICHAGE de la carte TF1 (TV
    //   Hub, reglages) DETRUISAIT la session. Une lecture ne doit jamais detruire.
    //
    //   POURQUOI TF1 ET PAS LES AUTRES. Mesure des points de sauvegarde :
    //     TF1  LoginWebViewActivity:1458 / TF1JwtRefresher:129 -> exp = vraie
    //          expiration du JWT, qui vit ~1 HEURE. Donc destruction garantie a
    //          chaque ouverture passe la premiere heure.
    //     M6   M6UidResolver:412 -> exp = null (=0), la branche ne se declenche
    //          jamais ; et le jeton vit plusieurs heures.
    //     RMC  BfmAuth n'est qu'une facade sur RmcPlusAuth (cookies) : ce code
    //          n'est jamais utilise.
    //
    //   CORRECTIF. (1) Un jeton perime n'est plus SERVI (retour null : l'appelant
    //   declenche son refresh), mais il n'est plus EFFACE. (2) `isLoggedIn` ne veut
    //   plus dire « mon jeton d'une heure est encore frais » mais « j'ai une session
    //   a rafraichir » — le refresh silencieux (TF1JwtRefresher) s'appuie sur les
    //   cookies Gigya, pas sur ce fichier, et peut donc encore aboutir.
    //   NE PAS REMETTRE le clearToken ici : seule une deconnexion explicite de
    //   l'utilisateur (bouton des reglages) doit vider ce fichier.
    fun getToken(ctx: Context): String? {
        val p = prefs(ctx)
        val tok = p.getString("token", null) ?: return null
        val exp = p.getLong("exp", 0L)
        if (exp > 0L && exp < System.currentTimeMillis() / 1000L) {
            Log.d(TAG, "$prefName: token expired (exp=$exp) — conserve pour refresh")
            return null
        }
        return tok
    }

    fun getRefresh(ctx: Context): String? = prefs(ctx).getString("refresh", null)

    // 2026-09-17 bis — MESURE SUR L'APPAREIL, NE PAS "SIMPLIFIER" EN SENS INVERSE.
    //   J'avais d'abord fait renvoyer `true` des qu'un jeton existait, meme perime,
    //   en pensant que le refresh silencieux le renouvellerait. Le test (expiration
    //   forcee dans le passe) a montre le contraire, logs a l'appui :
    //     TF1JwtRefresher: Refresh starting (headless WebView)
    //     authEvent => { parsedJwt=null, authType=STARTUP_TOKEN }   <- page pas connectee
    //     TF1JwtRefresher: Refresh callback: token=0                <- rien obtenu
    //     Mediainfo HTTP 200 : "error_code":"PERMISSION_DENIED"     <- ECRAN NOIR
    //   Afficher « connecte » avec un jeton inutilisable donne donc un ecran noir
    //   sans explication, ce qui est PIRE que de demander une reconnexion.
    //   On revient donc a « connecte = jeton exploitable », mais desormais SANS
    //   destruction (cf. getToken) : le compte, l'apiKey et les signatures DRM
    //   survivent, et si le refresh aboutit plus tard l'etat repasse a connecte.
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

/**
 * Session RMC BFM Play → RMC+.
 *
 * 2026-09-05 : le token `BFM_…` du SSO CAS n'existe plus (RMC BFM Play est devenu RMC+, voir
 * [RmcPlusAuth]). Cet objet reste pour ses NOMBREUX appelants (TV Hub, réglages, dialogs) mais
 * n'est plus qu'une façade : « token » = les cookies de session www.rmcplus.fr.
 */
object BfmAuth {
    fun saveToken(ctx: Context, token: String, refresh: String? = null, exp: Long? = null) =
        RmcPlusAuth.enregistrer(ctx, token, exp?.let { if (it < 100_000_000_000L) it * 1000 else it })
    fun getToken(ctx: Context): String? = RmcPlusAuth.cookies(ctx)
    @Suppress("UNUSED_PARAMETER")
    fun saveAccountId(ctx: Context, accountId: String) { /* plus utilisé (customdata DRM Gaia) */ }
    @Suppress("UNUSED_PARAMETER")
    fun getAccountId(ctx: Context): String? = null
    fun isLoggedIn(ctx: Context): Boolean = RmcPlusAuth.estConnecte(ctx)
    fun clearToken(ctx: Context) = RmcPlusAuth.deconnecter(ctx)
    fun savedAt(ctx: Context): Long = RmcPlusAuth.savedAt(ctx)
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
