package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Refresh silencieux du JWT TF1+ via WebView headless.
 *
 * Architecture :
 *   - Crée une WebView NON-attachée à l'UI (pas de display).
 *   - Charge `https://www.tf1.fr/` — tous les cookies Gigya (`glt_*`) sont
 *     auto-chargés depuis le CookieManager partagé.
 *   - Une fois la page chargée et le SDK gigya prêt, exécute le pipeline
 *     `getAccountInfo` → `/token/gigya/web` pour obtenir un nouveau JWT TF1+.
 *   - Save le JWT + l'exp parsé du payload.
 *
 * Déclencheurs réels :
 *   - Au démarrage de l'app (StreamFlixApp.onCreate, différé de 8 s), si une
 *     session Gigya est en réserve. AJOUTÉ le 2026-09-18 : ce déclencheur était
 *     annoncé ici mais n'existait pas, si bien que le jeton n'était jamais
 *     renouvelé tant que l'utilisateur ne lançait pas une vidéo — or on ne
 *     lance pas de vidéo sur un service affiché « déconnecté ». D'où les
 *     ressaisies d'identifiants.
 *   - Avant chaque appel mediainfo (TF1Resolver.resolveVideoId).
 *
 * L'user ne voit rien. Le JWT TF1, lui, vit 12 h (mesuré) ; c'est la session
 * Gigya conservée à part (TF1GigyaSession) qui permet d'en obtenir un neuf.
 */
object TF1JwtRefresher {
    private const val TAG = "TF1JwtRefresher"

    /** Empêche les refreshes parallèles (= 1 à la fois max). */
    private val refreshInFlight = AtomicBoolean(false)

    /** Vraie page d'accueil TF1, celle qui porte le SDK Gigya. */
    private const val URL_TF1 = "https://www.tf1.fr/"

    /**
     * 2026-09-18 — DOIT ÊTRE EXACTEMENT L'AGENT DE L'ÉCRAN DE CONNEXION
     * (LoginWebViewActivity.USER_AGENT, Windows). Il était réglé sur un agent
     * Macintosh « pour cohérence avec TF1Resolver » : la session Gigya était
     * donc ouverte sous un agent et présentée sous un autre.
     *
     * Ce que ça donnait, mesuré sur l'Oppo : une connexion réussie à 13 h 11,
     * un renouvellement lancé à 13 h 16 avec le MÊME pot de cookies (jeton de
     * connexion vieux de cinq minutes, rien d'effacé entre-temps) — et Gigya
     * répondait quand même 403005 « Unauthorized user ». Ni l'expiration, ni
     * les cookies manquants, ni le localStorage n'expliquaient ça : seul
     * l'agent changeait entre les deux écrans.
     *
     * Si un jour l'agent de l'écran de connexion change, changer celui-ci avec.
     */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

    /**
     * Refresh proactif : si JWT expiré ou expire dans moins de `thresholdMs`,
     * déclenche un refresh silencieux. Sinon no-op.
     *
     * Appelé depuis :
     *   - `StreamFlixApp.onCreate` (au boot)
     *   - `TF1Resolver.resolveVideoId` (avant chaque play)
     */
    fun refreshIfNeeded(ctx: Context, thresholdMs: Long = 30 * 60 * 1000L) {
        val needs = needsRefresh(ctx, thresholdMs)
        if (!needs) return
        if (refreshInFlight.get()) {
            Log.d(TAG, "Refresh already in flight, skip")
            return
        }
        refresh(ctx)
    }

    /** Force un refresh, même si le JWT actuel est encore valide. */
    @SuppressLint("SetJavaScriptEnabled")
    fun refresh(ctx: Context) {
        if (!refreshInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Refresh already in flight")
            return
        }
        Handler(Looper.getMainLooper()).post {
            try {
                doRefresh(ctx.applicationContext)
            } catch (e: Throwable) {
                Log.e(TAG, "Refresh init failed: ${e.message}")
                refreshInFlight.set(false)
            }
        }
    }

    /** Vérifie si un refresh est nécessaire. */
    fun needsRefresh(ctx: Context, thresholdMs: Long = 30 * 60 * 1000L): Boolean {
        val token = TF1Auth.getToken(ctx) ?: return true  // pas de token = besoin refresh
        val expSec = parseJwtExp(token) ?: return false   // pas d'exp = on suppose OK
        val nowSec = System.currentTimeMillis() / 1000L
        val thresholdSec = thresholdMs / 1000L
        val expiresInSec = expSec - nowSec
        Log.d(TAG, "JWT expires in ${expiresInSec}s (threshold=${thresholdSec}s)")
        return expiresInSec < thresholdSec
    }

    /** Parse le `exp` (= unix timestamp) depuis le payload base64 du JWT. */
    fun parseJwtExp(jwt: String): Long? {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return null
            // base64url padding
            val raw = parts[1].replace('-', '+').replace('_', '/')
            val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
            val payload = String(Base64.decode(padded, Base64.DEFAULT))
            val json = JSONObject(payload)
            if (json.has("exp")) json.getLong("exp") else null
        } catch (e: Throwable) {
            Log.w(TAG, "parseJwtExp failed: ${e.message}")
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun doRefresh(ctx: Context) {
        Log.i(TAG, "Refresh starting (headless WebView)")
        // Crée une WebView sans parent → pas de display, mais le code JS tourne.
        val webView = WebView(ctx)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = UA
            // Cache + DB pour avoir les cookies persistés
            databaseEnabled = true
        }
        // 2026-09-18 — COOKIES TIERS. L'écran de connexion les autorise
        //   (LoginWebViewActivity), cette WebView-ci ne le faisait pas : par
        //   défaut Android les REFUSE. Or le SDK Gigya dialogue avec
        //   accounts.eu1.gigya.com, un autre domaine que tf1.fr : sans cookies
        //   tiers, son identité d'appareil n'est ni envoyée ni conservée, et
        //   Gigya rejette le jeton de connexion avec 403005 « Unauthorized
        //   user ». Les deux WebViews doivent avoir la même politique, sinon la
        //   session valide à la connexion devient invalide au renouvellement.
        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
        // Bridge pour récupérer le JWT
        webView.addJavascriptInterface(object {
            /**
             * Diagnostic du pipeline côté page. Sert à savoir POURQUOI un refresh
             * rend `token=0` : SDK gigya absent, cookie de session non lu, ou
             * compte refusé par Gigya (errorCode/errorMessage).
             */
            @Suppress("unused")
            @JavascriptInterface
            fun onDiag(json: String?) {
                Log.i(TAG, "Refresh diag: $json")
            }

            @Suppress("unused")
            @JavascriptInterface
            fun onJwt(token: String?) {
                Log.i(TAG, "Refresh callback: token=${token?.length ?: 0}")
                if (!token.isNullOrBlank()) {
                    val expSec = parseJwtExp(token)
                    TF1Auth.saveToken(ctx, token, refresh = null, exp = expSec)
                    Log.i(TAG, "JWT refreshed silently (len=${token.length}, exp=$expSec)")
                }
                refreshInFlight.set(false)
                Handler(Looper.getMainLooper()).post {
                    // 2026-09-18 — RENOUVELLEMENT TOURNANT DE LA SESSION. Un
                    //   navigateur ne garde pas le même jeton de connexion Gigya
                    //   pendant des mois : le serveur le rafraîchit à chaque
                    //   visite. En n'archivant qu'une photo prise le jour de la
                    //   connexion, on finissait fatalement avec une session
                    //   périmée. On re-capture donc à chaque passage réussi.
                    val detruit = AtomicBoolean(false)
                    val detruire = {
                        if (detruit.compareAndSet(false, true)) {
                            try { webView.destroy() } catch (_: Throwable) {}
                        }
                    }
                    if (!token.isNullOrBlank()) {
                        runCatching { TF1GigyaSession.capturer(ctx) }
                    }
                    detruire()
                }
            }
        }, "OnyxRefresh")
        // tf1.fr déclenche onPageFinished plusieurs fois pour un seul chargement
        // (2 fois mesuré). Sans ce verrou, le pipeline partait deux fois et
        // réclamait deux jetons d'affilée pour rien.
        val jsLance = AtomicBoolean(false)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url.contains("tf1.fr") &&
                    !url.contains("accounts.google") &&
                    !url.contains("facebook.com")) {
                    if (!jsLance.compareAndSet(false, true)) return
                    Log.d(TAG, "Refresh page loaded: $url → exec JS")
                    view?.evaluateJavascript(JS_REFRESH, null)
                }
            }
        }
        // Timeout 60 s : le JS attend le SDK gigya jusqu'à 20 s après le
        // chargement de la page, 30 s coupaient le pipeline avant sa fin.
        Handler(Looper.getMainLooper()).postDelayed({
            if (refreshInFlight.get()) {
                Log.w(TAG, "Refresh timeout 60s — releasing lock")
                refreshInFlight.set(false)
                try { webView.destroy() } catch (_: Throwable) {}
            }
        }, 60_000L)
        // 2026-09-17 : ON RÉINJECTE LA SESSION GIGYA AVANT DE CHARGER LA PAGE.
        //   Sans ça, tf1.fr ne reconnaît aucun compte et le pipeline rend
        //   `parsedJwt=null` puis `callback: token=0` — mesuré sur l'Oppo, base
        //   de cookies WebView vide de tout `glt_`. La session est capturée au
        //   moment de la connexion (LoginWebViewActivity.saveJwtAndFinish).
        //   NE PAS DÉPLACER APRÈS loadUrl : les cookies doivent être en place
        //   avant la requête, sinon la page part déconnectée.
        val nbCookies = TF1GigyaSession.restaurer(ctx)
        if (nbCookies == 0) {
            Log.w(TAG, "Aucune session Gigya en réserve — le refresh a peu de chances " +
                "d'aboutir (reconnexion manuelle probablement nécessaire)")
        }
        webView.loadUrl(URL_TF1)
    }

    /** Même pipeline que JS_CAPTURE_TF1_JWT_SILENT, mais appelle `OnyxRefresh.onJwt`. */
    private const val JS_REFRESH = """
(async () => {
  const isJwt = (s) => {
    if (typeof s !== 'string' || s.length < 100) return false;
    const p = s.split('.');
    if (p.length !== 3) return false;
    return p[0].startsWith('eyJ') && p[1].startsWith('eyJ') && p[2].length > 0;
  };
  const tradeWithUid = async (uid, signature, timestamp) => {
    try {
      const ex = await fetch('https://www.tf1.fr/token/gigya/web', {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          uid: uid, signature: signature, timestamp: parseInt(timestamp),
          consent_ids: ['1','2','3','4','10001','10003','10005','10007','10013','10015','10017','10019','10009','10011','13002','13001','10004','10014','10016','10018','10020','10010','10012','10006','10008']
        })
      });
      const ej = await ex.json();
      if (ej && ej.token && isJwt(ej.token)) return ej.token;
    } catch (e) {}
    return null;
  };
  const diag = { gigya: false, cookies: '', r1: null, r2: null, trade: null };
  const trySdk = async () => {
    if (!window.gigya || !window.gigya.accounts) return null;
    const r1 = await new Promise((resolve) => {
      try {
        window.gigya.accounts.getAccountInfo({
          include: 'profile,emails', callback: resolve
        });
      } catch (e) { resolve({ errorCode: -1 }); }
    });
    diag.r1 = { code: r1 && r1.errorCode, msg: r1 && r1.errorMessage, uid: !!(r1 && r1.UID) };
    if (r1.errorCode === 0 && r1.UID && r1.UIDSignature && r1.signatureTimestamp) {
      const t = await tradeWithUid(r1.UID, r1.UIDSignature, r1.signatureTimestamp);
      diag.trade = t ? 'ok' : 'echec';
      return t;
    }
    const r2 = await new Promise((resolve) => {
      try {
        if (window.gigya.accounts.session && window.gigya.accounts.session.verify) {
          window.gigya.accounts.session.verify({ callback: resolve });
        } else { resolve({ errorCode: -1 }); }
      } catch (e) { resolve({ errorCode: -1 }); }
    });
    diag.r2 = { code: r2 && r2.errorCode, msg: r2 && r2.errorMessage, uid: !!(r2 && r2.UID) };
    if (r2.errorCode === 0 && r2.UID && r2.UIDSignature && r2.signatureTimestamp) {
      const t2 = await tradeWithUid(r2.UID, r2.UIDSignature, r2.signatureTimestamp);
      diag.trade = t2 ? 'ok' : 'echec';
      return t2;
    }
    return null;
  };
  // Attente du SDK gigya. 200 ms x 100 = 20 s : la page TF1 est lourde et le
  // SDK arrive souvent bien après `onPageFinished` (5 s ne suffisaient pas).
  for (let i = 0; i < 100; i++) {
    if (window.gigya && window.gigya.accounts) break;
    await new Promise(r => setTimeout(r, 200));
  }
  diag.gigya = !!(window.gigya && window.gigya.accounts);
  try {
    diag.cookies = (document.cookie || '').split(';')
      .map(c => c.trim().split('=')[0])
      .filter(n => /^(glt_|gig_|gmid|ucid|hasGmid)/.test(n)).join(',');
  } catch (e) {}
  // Où le SDK range-t-il son identité d'appareil ? (clés seulement, pas de valeurs)
  try {
    diag.api = window.gigya ? (window.gigya.apiDomain || window.gigya.dataCenter || '?') : '?';
    diag.ls = Object.keys(localStorage).slice(0, 40).join(',');
    diag.ss = Object.keys(sessionStorage).slice(0, 20).join(',');
  } catch (e) {}
  try {
    const tf1Jwt = await trySdk();
    try { OnyxRefresh.onDiag(JSON.stringify(diag)); } catch (e) {}
    if (tf1Jwt) {
      OnyxRefresh.onJwt(tf1Jwt);
      return;
    }
  } catch (e) {
    try { OnyxRefresh.onDiag(JSON.stringify({ err: String(e), diag: diag })); } catch (e2) {}
  }
  OnyxRefresh.onJwt(null);
})();
"""
}
