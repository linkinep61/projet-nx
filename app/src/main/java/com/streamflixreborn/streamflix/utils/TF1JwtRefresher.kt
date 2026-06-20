package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
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
 * Triggers possibles :
 *   - Au boot de l'app si le JWT est expiré ou < 1h de vie restante.
 *   - Avant chaque appel mediainfo si le JWT est en train d'expirer.
 *   - Sur erreur 401/403 du mediainfo (= bearer rejeté).
 *
 * L'user ne voit rien. Les cookies Gigya vivent ~1 an dans le CookieManager,
 * donc tant que l'user ne se déconnecte pas explicitement (= via une option
 * Settings, ou en désinstallant l'app), le refresh continue de marcher.
 */
object TF1JwtRefresher {
    private const val TAG = "TF1JwtRefresher"

    /** Empêche les refreshes parallèles (= 1 à la fois max). */
    private val refreshInFlight = AtomicBoolean(false)

    /** UA desktop = identique à TF1Resolver pour cohérence. */
    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

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
        // Bridge pour récupérer le JWT
        webView.addJavascriptInterface(object {
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
                    try { webView.destroy() } catch (_: Throwable) {}
                }
            }
        }, "OnyxRefresh")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url.contains("tf1.fr") &&
                    !url.contains("accounts.google") &&
                    !url.contains("facebook.com")) {
                    Log.d(TAG, "Refresh page loaded: $url → exec JS")
                    view?.evaluateJavascript(JS_REFRESH, null)
                }
            }
        }
        // Timeout 30s : si le refresh n'aboutit pas, on libère le verrou
        Handler(Looper.getMainLooper()).postDelayed({
            if (refreshInFlight.get()) {
                Log.w(TAG, "Refresh timeout 30s — releasing lock")
                refreshInFlight.set(false)
                try { webView.destroy() } catch (_: Throwable) {}
            }
        }, 30_000L)
        webView.loadUrl("https://www.tf1.fr/")
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
  const trySdk = async () => {
    if (!window.gigya || !window.gigya.accounts) return null;
    const r1 = await new Promise((resolve) => {
      try {
        window.gigya.accounts.getAccountInfo({
          include: 'profile,emails', callback: resolve
        });
      } catch (e) { resolve({ errorCode: -1 }); }
    });
    if (r1.errorCode === 0 && r1.UID && r1.UIDSignature && r1.signatureTimestamp) {
      return await tradeWithUid(r1.UID, r1.UIDSignature, r1.signatureTimestamp);
    }
    const r2 = await new Promise((resolve) => {
      try {
        if (window.gigya.accounts.session && window.gigya.accounts.session.verify) {
          window.gigya.accounts.session.verify({ callback: resolve });
        } else { resolve({ errorCode: -1 }); }
      } catch (e) { resolve({ errorCode: -1 }); }
    });
    if (r2.errorCode === 0 && r2.UID && r2.UIDSignature && r2.signatureTimestamp) {
      return await tradeWithUid(r2.UID, r2.UIDSignature, r2.signatureTimestamp);
    }
    return null;
  };
  // Wait for window.gigya to be ready (= polling 200ms x 25 = 5s max)
  for (let i = 0; i < 25; i++) {
    if (window.gigya && window.gigya.accounts) break;
    await new Promise(r => setTimeout(r, 200));
  }
  try {
    const tf1Jwt = await trySdk();
    if (tf1Jwt) {
      OnyxRefresh.onJwt(tf1Jwt);
      return;
    }
  } catch (e) {}
  OnyxRefresh.onJwt(null);
})();
"""
}
