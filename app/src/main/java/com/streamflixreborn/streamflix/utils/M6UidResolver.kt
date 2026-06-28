package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Résolveur autonome de l'UID Gigya pour M6+.
 *
 * Problème :
 *   - L'endpoint `login-gigya.m6.fr/accounts.getAccountInfo?login_token=<glt>`
 *     échoue avec errorCode 403005 "Unauthorized user" depuis le serveur (=
 *     le cookie `glt_<apiKey>` n'est pas un login_token API standard).
 *   - On a besoin du UID Gigya pour fetcher l'upfront-token Widevine via
 *     `drm.6cloud.fr/.../users/<UID>/videos/<id>/upfront-token`.
 *   - Sans UID, pas de license → MPD chiffré joue son audio mais pas l'image.
 *
 * Solution autonome (= pas besoin que l'user reconnecte) :
 *   - Crée une WebView non-attachée à l'UI (headless).
 *   - Charge `https://www.m6.fr/` — les cookies Gigya (`glt_*` + `gigya-mid_*`)
 *     sont auto-chargés depuis le CookieManager partagé (= ceux posés lors du
 *     login précédent persistent ~1 an).
 *   - Une fois la page chargée et le SDK Gigya prêt, exécute
 *     `gigya.accounts.getAccountInfo()` qui retourne le UID via callback.
 *   - Save le UID via `M6Auth.saveAccountId`.
 *
 * L'user ne voit rien. Tant qu'il ne se déconnecte pas explicitement, le
 * refresh continue de marcher.
 */
object M6UidResolver {
    private const val TAG = "M6UidResolver"

    /** Empêche les refreshes parallèles (= 1 à la fois max). */
    private val refreshInFlight = AtomicBoolean(false)

    // 2026-06-19 v40 (user "répare M6 et toutes ces chaînes") : pipeline Live
    //   M6+ via WebView headless (= pareil que TF1+ JWT capture). On charge
    //   www.m6.fr/m6plus/direct-<chan>-f_999, hook fetch/XHR, capte les
    //   requêtes vers /upfront-token et stocke le token par channel.
    data class LiveTokenInfo(
        val streamUrl: String,
        val licenseUrl: String,
        val upfrontToken: String,
        val capturedAt: Long = System.currentTimeMillis(),
    )
    /** Cache des tokens DRM live par channel (m6/w9/6ter/gulli). TTL 4h. */
    private val liveTokenCache = java.util.concurrent.ConcurrentHashMap<String, LiveTokenInfo>()
    private const val LIVE_TOKEN_TTL_MS = 4L * 60L * 60L * 1000L

    /** Empêche les resolves live parallèles pour le même channel. */
    private val liveResolveInFlight = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()

    fun getCachedLiveToken(channel: String): LiveTokenInfo? {
        val cached = liveTokenCache[channel.lowercase()] ?: return null
        if (System.currentTimeMillis() - cached.capturedAt > LIVE_TOKEN_TTL_MS) {
            liveTokenCache.remove(channel.lowercase())
            return null
        }
        return cached
    }

    /** UA desktop = identique à M6Resolver pour cohérence avec les cookies. */
    private const val UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Résout le UID Gigya en arrière-plan via WebView headless. Si un refresh
     *  est déjà en cours, no-op. Le résultat est sauvegardé dans M6Auth. */
    @SuppressLint("SetJavaScriptEnabled")
    fun resolveAsync(ctx: Context, onDone: ((String?) -> Unit)? = null) {
        if (!refreshInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Resolve already in flight, skip")
            onDone?.invoke(M6Auth.getAccountId(ctx))
            return
        }
        Handler(Looper.getMainLooper()).post {
            try {
                doResolve(ctx.applicationContext, onDone)
            } catch (e: Throwable) {
                Log.e(TAG, "Resolve init failed: ${e.message}")
                refreshInFlight.set(false)
                onDone?.invoke(null)
            }
        }
    }

    /** 2026-06-19 v40 : variante LIVE = ouvre la page direct M6 d'une chaîne
     *  spécifique (m6/w9/6ter/gulli) et capture le upfront-token via fetch hook.
     *  Block jusqu'à timeoutMs (35s par défaut, le SPA M6+ prend ~10-15s à
     *  init son player + appeler /upfront-token). */
    fun resolveLiveSync(ctx: Context, channel: String, timeoutMs: Long = 35_000L): LiveTokenInfo? {
        val key = channel.lowercase()
        // Cache hit ?
        getCachedLiveToken(key)?.let {
            Log.d(TAG, "resolveLiveSync: cached token for $key (age=${(System.currentTimeMillis() - it.capturedAt) / 1000}s)")
            return it
        }
        // Verrou par channel pour éviter resolves parallèles
        val lock = liveResolveInFlight.getOrPut(key) { AtomicBoolean(false) }
        if (!lock.compareAndSet(false, true)) {
            Log.d(TAG, "resolveLiveSync: already in flight for $key, wait...")
            // Attend un peu et re-check cache
            val latch = java.util.concurrent.CountDownLatch(1)
            Handler(Looper.getMainLooper()).postDelayed({ latch.countDown() }, 5000L)
            latch.await(5000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            return getCachedLiveToken(key)
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                doResolveLive(ctx.applicationContext, key) { _ -> latch.countDown() }
            } catch (e: Throwable) {
                Log.e(TAG, "Live resolve init failed: ${e.message}")
                lock.set(false)
                latch.countDown()
            }
        }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        lock.set(false)
        return getCachedLiveToken(key)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun doResolveLive(ctx: Context, channel: String, onDone: (LiveTokenInfo?) -> Unit) {
        Log.i(TAG, "Live resolve starting for $channel (headless WebView)")
        val webView = WebView(ctx)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = UA
            databaseEnabled = true
        }
        var done = false
        val finish = { token: LiveTokenInfo? ->
            if (!done) {
                done = true
                if (token != null) {
                    liveTokenCache[channel] = token
                    Log.i(TAG, "Live $channel token captured: streamUrl=${token.streamUrl.take(60)} licenseUrl=${token.licenseUrl.take(60)} tokenLen=${token.upfrontToken.length}")
                }
                Handler(Looper.getMainLooper()).post {
                    try { webView.destroy() } catch (_: Throwable) {}
                }
                onDone(token)
            }
        }
        // Track les éléments capturés en attendant qu'on ait au moins token + license
        var capturedToken: String? = null
        var capturedLicenseUrl: String? = null
        var capturedStreamUrl: String? = null
        val tryFinish = {
            if (capturedToken != null && capturedLicenseUrl != null) {
                val streamUrl = capturedStreamUrl
                    ?: "https://sr-m6web.live.6cloud.fr/out/v1/6play/6play-$channel/cmaf_cenc00/dash-short-hd720.mpd"
                finish(LiveTokenInfo(streamUrl, capturedLicenseUrl!!, capturedToken!!))
            }
        }
        webView.addJavascriptInterface(object {
            @Suppress("unused")
            @JavascriptInterface
            fun onLiveUpfrontIntercepted(url: String?, body: String?) {
                Log.i(TAG, "Live upfront intercepted url=${url?.take(80)} body[0:40]=${body?.take(40)}")
                if (body.isNullOrBlank()) return
                // Le body de upfront-token est soit le token brut, soit JSON {token:"..."}
                val token = try {
                    val trimmed = body.trim()
                    if (trimmed.startsWith("{")) {
                        org.json.JSONObject(trimmed).optString("token", "").ifBlank { trimmed }
                    } else trimmed
                } catch (_: Throwable) { body.trim() }
                if (token.length > 50) {
                    capturedToken = token
                    tryFinish()
                }
            }

            @Suppress("unused")
            @JavascriptInterface
            fun onLiveLicenseIntercepted(url: String?) {
                Log.i(TAG, "Live license URL intercepted: ${url?.take(100)}")
                if (!url.isNullOrBlank() && url.startsWith("http")) {
                    capturedLicenseUrl = url
                    tryFinish()
                }
            }

            @Suppress("unused")
            @JavascriptInterface
            fun onLiveStreamUrlIntercepted(url: String?) {
                Log.i(TAG, "Live stream URL intercepted: ${url?.take(100)}")
                if (!url.isNullOrBlank() && url.contains(".mpd")) {
                    capturedStreamUrl = url
                    tryFinish()
                }
            }
        }, "OnyxM6Live")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null && (url.contains("m6.fr") || url.contains("6play.fr"))) {
                    view?.evaluateJavascript(JS_INSTALL_LIVE_HOOK, null)
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && (url.contains("m6.fr") || url.contains("6play.fr"))) {
                    Log.d(TAG, "Live page loaded $url → re-inject hook + trigger player")
                    view?.evaluateJavascript(JS_INSTALL_LIVE_HOOK, null)
                    // 2026-06-19 v40 : démarre le player après 3s (= temps pour
                    //   que le SPA M6+ init son DOM + dismisse les consent banners)
                    Handler(Looper.getMainLooper()).postDelayed({
                        view?.evaluateJavascript(JS_TRIGGER_LIVE_PLAYER, null)
                    }, 3000L)
                    Handler(Looper.getMainLooper()).postDelayed({
                        view?.evaluateJavascript(JS_TRIGGER_LIVE_PLAYER, null)
                    }, 8000L)
                    Handler(Looper.getMainLooper()).postDelayed({
                        view?.evaluateJavascript(JS_TRIGGER_LIVE_PLAYER, null)
                    }, 15000L)
                }
            }
        }
        // Timeout 40s
        Handler(Looper.getMainLooper()).postDelayed({
            if (!done) {
                Log.w(TAG, "Live resolve timeout 40s for $channel")
                finish(null)
            }
        }, 40_000L)
        // Charge la page direct de la chaîne. Le SPA M6+ va automatiquement
        //   appeler son endpoint /upfront-token + license que notre hook capte.
        // 2026-06-19 fix : `m6.fr/m6plus/direct-<chan>-f_999` redirigeait
        //   TOUJOURS vers direct-m6-f_999 quand on chargeait 6ter/W9/Gulli
        //   → impossible de capturer un token spécifique. On bascule sur
        //   `6play.fr/<chan>/direct` qui sert chaque chaîne sans redirection.
        val url = "https://www.6play.fr/$channel/direct"
        Log.d(TAG, "Loading $url")
        webView.loadUrl(url)
    }

    /** 2026-06-19 v40 : JS qui DÉMARRE le player live du SPA M6+ qui ne se
     *  lance pas automatiquement (= consent banner / paywall / autoplay block).
     *  Stratégies : 1) clique tous les boutons "Accepter" 2) clique le bouton
     *  "Lecture" du player 3) appelle play() sur le <video> directement. */
    private const val JS_TRIGGER_LIVE_PLAYER = """
(() => {
  console.log('[OnyxLive] trigger player attempt');
  // 1. Dismiss consent banners
  const consentSelectors = [
    '#didomi-notice-agree-button',
    'button[id*="consent"]',
    'button[id*="accept"]',
    'button[class*="consent"][class*="accept"]',
    'button[data-testid="banner-accept"]',
  ];
  for (const sel of consentSelectors) {
    document.querySelectorAll(sel).forEach(el => {
      try { console.log('[OnyxLive] click consent: ' + sel); el.click(); } catch (e) {}
    });
  }
  // 2. Click play button
  const playSelectors = [
    'button[aria-label*="Lecture"]',
    'button[aria-label*="lecture"]',
    'button[aria-label*="Play"]',
    'button[title*="Lecture"]',
    'button[class*="play-button"]',
    'button[class*="PlayButton"]',
    'button[class*="player__play"]',
    'div[role="button"][aria-label*="Lecture"]',
  ];
  for (const sel of playSelectors) {
    document.querySelectorAll(sel).forEach(el => {
      try { console.log('[OnyxLive] click play: ' + sel); el.click(); } catch (e) {}
    });
  }
  // 3. Force play() sur le <video> tag
  document.querySelectorAll('video').forEach(v => {
    try {
      console.log('[OnyxLive] video found, muted=' + v.muted + ' paused=' + v.paused);
      v.muted = true;
      const p = v.play();
      if (p && p.catch) p.catch(e => console.log('[OnyxLive] video.play err: ' + e.message));
    } catch (e) { console.log('[OnyxLive] video err: ' + e.message); }
  });
  // 4. Cherche dans les iframes
  document.querySelectorAll('iframe').forEach(iframe => {
    try {
      const doc = iframe.contentDocument;
      if (doc) {
        doc.querySelectorAll('video').forEach(v => {
          try { v.muted = true; v.play(); console.log('[OnyxLive] iframe video.play()'); } catch (e) {}
        });
      }
    } catch (e) {}
  });
})();
"""

    /** JS hook étendu : capture les requêtes vers /upfront-token, license URLs
     *  et MPD URLs depuis le SPA M6+ pour le live. */
    private const val JS_INSTALL_LIVE_HOOK = """
(() => {
  if (window.__onyxLiveHookInstalled) return;
  window.__onyxLiveHookInstalled = true;
  console.log('[OnyxLive] installing live fetch+XHR interceptors');
  const origFetch = window.fetch;
  window.fetch = async function(...args) {
    const url = (typeof args[0] === 'string') ? args[0] : (args[0] && args[0].url) || '';
    // Détection license URL avant l'appel (= souvent dans les init segments DRM)
    if (url && url.toLowerCase().includes('license')) {
      try { OnyxM6Live.onLiveLicenseIntercepted(url); } catch (e) {}
    }
    if (url && url.includes('.mpd')) {
      try { OnyxM6Live.onLiveStreamUrlIntercepted(url); } catch (e) {}
    }
    const resp = await origFetch.apply(this, args);
    try {
      if (url && url.includes('upfront-token')) {
        const clone = resp.clone();
        const txt = await clone.text();
        console.log('[OnyxLive] upfront-token resp status=' + resp.status + ' len=' + txt.length);
        try { OnyxM6Live.onLiveUpfrontIntercepted(url, txt); } catch (e) {}
      }
    } catch (e) {}
    return resp;
  };
  const origOpen = XMLHttpRequest.prototype.open;
  const origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url) {
    this.__onyxUrl = url;
    if (url && url.toLowerCase().includes('license')) {
      try { OnyxM6Live.onLiveLicenseIntercepted(url); } catch (e) {}
    }
    if (url && url.includes('.mpd')) {
      try { OnyxM6Live.onLiveStreamUrlIntercepted(url); } catch (e) {}
    }
    return origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function() {
    if (this.__onyxUrl && this.__onyxUrl.includes('upfront-token')) {
      this.addEventListener('load', () => {
        try {
          const txt = this.responseText || '';
          console.log('[OnyxLive] XHR upfront-token resp len=' + txt.length);
          try { OnyxM6Live.onLiveUpfrontIntercepted(this.__onyxUrl, txt); } catch (e) {}
        } catch (e) {}
      });
    }
    return origSend.apply(this, arguments);
  };
})();
"""

    /** Variante synchrone (= block jusqu'à 15s) pour M6Resolver qui en a
     *  besoin avant de fetcher l'upfront-token. */
    fun resolveSync(ctx: Context, timeoutMs: Long = 15_000L, forceWebView: Boolean = false): String? {
        if (!forceWebView) {
            // Si déjà cached, retourne direct
            M6Auth.getAccountId(ctx)?.takeIf { it.isNotBlank() }?.let { return it }
        } else {
            // 2026-06-22 : forceWebView = on DOIT relancer la WebView (ex:
            //   JWT M6 expiré, on veut que le SPA refetch /getJwt et que le
            //   hook JS intercepte le nouveau JWT). Reset le verrou anti-doublon.
            Log.d(TAG, "forceWebView=true, resetting refreshInFlight + launching WebView")
            refreshInFlight.set(false)
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: String? = null
        resolveAsync(ctx) { uid ->
            result = uid
            latch.countDown()
        }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result ?: M6Auth.getAccountId(ctx)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun doResolve(ctx: Context, onDone: ((String?) -> Unit)?) {
        Log.i(TAG, "UID resolve starting (headless WebView)")
        val webView = WebView(ctx)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = UA
            databaseEnabled = true
        }
        var done = false
        val finish = { uid: String? ->
            if (!done) {
                done = true
                // 2026-06-19 v4 : restaure aussi le token Gigya (= cookie
                //   glt_<apiKey>) depuis les cookies WebView persistants. Si
                //   l'user a un cookie de session valide (= il y a moins
                //   d'1 an), le `glt_*` est encore là même si on a effacé
                //   les SharedPreferences M6Auth. Permet de récupérer une
                //   session perdue sans demander un re-login.
                try {
                    val cookieMgr = android.webkit.CookieManager.getInstance()
                    val all = cookieMgr.getCookie("https://www.m6.fr/") ?: ""
                    if (all.isNotBlank()) {
                        val glt = all.split(";").mapNotNull { kv ->
                            val parts = kv.trim().split("=", limit = 2)
                            if (parts.size == 2 && parts[0].startsWith("glt_")) parts[1] else null
                        }.firstOrNull()
                        if (!glt.isNullOrBlank() && M6Auth.getToken(ctx).isNullOrBlank()) {
                            // Save sans exp (= sera revalidé au prochain check)
                            M6Auth.saveToken(ctx, glt, refresh = null, exp = null)
                            Log.i(TAG, "Restored M6 token from WebView cookies (len=${glt.length})")
                        }
                        // Capture mid si pas encore set (fallback si UID null)
                        val mid = all.split(";").mapNotNull { kv ->
                            val parts = kv.trim().split("=", limit = 2)
                            if (parts.size == 2 && parts[0].startsWith("gigya-mid_")) parts[1] else null
                        }.firstOrNull()
                        if (uid.isNullOrBlank() && !mid.isNullOrBlank()) {
                            M6Auth.saveAccountId(ctx, mid)
                            Log.i(TAG, "Saved gigya-mid as account_id fallback (len=${mid.length})")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Cookie restore failed: ${e.message}")
                }
                if (!uid.isNullOrBlank()) {
                    M6Auth.saveAccountId(ctx, uid)
                    Log.i(TAG, "UID resolved silently and cached (len=${uid.length})")
                } else {
                    Log.w(TAG, "UID resolve returned null (fallback may apply)")
                }
                refreshInFlight.set(false)
                Handler(Looper.getMainLooper()).post {
                    try { webView.destroy() } catch (_: Throwable) {}
                }
                // Si pas d'UID propre via SDK, retourne le mid (= cookie) si
                //   on en a un. Sinon null.
                val effective = if (!uid.isNullOrBlank()) uid else M6Auth.getAccountId(ctx)
                onDone?.invoke(effective)
            }
        }
        // Bridge JS → Kotlin pour récupérer le UID + signature + timestamp + JWT M6.
        //   Le JWT M6 est obtenu via POST front-auth.6cloud.fr/getJwt DEPUIS LA
        //   WEBVIEW elle-même (= TLS fingerprint Chrome natif + cookies inclus
        //   automatiquement). Le call OkHttp Kotlin était rejeté HTTP 403 par
        //   CloudFront WAF (même avec Origin + Referer). En passant par le JS
        //   de la page m6.fr en cours, CloudFront accepte (= traffic légitime).
        webView.addJavascriptInterface(object {
            @Suppress("unused")
            @JavascriptInterface
            fun onUid(uid: String?, sig: String?, ts: String?, jwt: String?) {
                Log.i(TAG, "JS callback: uid=${uid?.take(12)}... sig=${sig?.take(12)}... ts=$ts jwt=${jwt?.take(12)}... (jwtLen=${jwt?.length ?: 0})")
                if (!sig.isNullOrBlank()) M6Auth.saveUidSignature(ctx, sig)
                if (!ts.isNullOrBlank()) M6Auth.saveSignatureTimestamp(ctx, ts)
                // v16 : save l'UID IMMÉDIATEMENT, pas seulement dans finish().
                //   Sans ça, onJwtIntercepted ne peut pas finish car uid est
                //   null en cache. Bug détecté dans logs v15.
                if (!uid.isNullOrBlank()) M6Auth.saveAccountId(ctx, uid)
                // v16 : ne JAMAIS écraser un vrai JWT M6 (= eyJ...) avec un
                //   fallback "GIGYA::..." du JS path.
                if (!jwt.isNullOrBlank()) {
                    val existing = M6Auth.getM6Jwt(ctx)
                    val keepExisting = existing?.startsWith("eyJ") == true &&
                        jwt.startsWith("GIGYA::")
                    if (!keepExisting) M6Auth.saveM6Jwt(ctx, jwt)
                }
                // Trigger finish si on a un vrai JWT M6 maintenant
                if (!uid.isNullOrBlank() && M6Auth.getM6Jwt(ctx)?.startsWith("eyJ") == true) {
                    finish(uid)
                }
            }

            /** v15 : appelé par le hook fetch/XHR quand un response /getJwt
             *  est capté du SPA M6+. C'est LE jwt M6 valide pour upfront. */
            @Suppress("unused")
            @JavascriptInterface
            fun onJwtIntercepted(jwt: String?) {
                Log.i(TAG, "Intercepted M6 JWT from SPA: len=${jwt?.length ?: 0}")
                if (!jwt.isNullOrBlank() && jwt.startsWith("eyJ")) {
                    M6Auth.saveM6Jwt(ctx, jwt)
                    // v16 : trigger finish même si UID pas encore en cache.
                    //   Sans UID on ne peut pas faire upfront mais on a au
                    //   moins le JWT cached pour le prochain essai.
                    val uid = M6Auth.getAccountId(ctx)
                    if (!uid.isNullOrBlank()) {
                        finish(uid)
                    } else {
                        Log.d(TAG, "JWT cached but UID not yet ready, waiting...")
                    }
                }
            }
        }, "OnyxM6Uid")
        webView.webViewClient = object : WebViewClient() {
            // v15 : inject le hook AU DEBUT (= avant que le SPA M6+ exécute
            //   son code de player). Comme ça window.fetch et XHR sont déjà
            //   overridden quand le SPA fait ses calls /getJwt et /upfront.
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null && (url.contains("m6.fr") || url.contains("6play.fr"))) {
                    Log.d(TAG, "Page started: $url → inject fetch hook")
                    view?.evaluateJavascript(JS_INSTALL_HOOK, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && (url.contains("m6.fr") || url.contains("6play.fr")) &&
                    !url.contains("accounts.google") && !url.contains("facebook.com")) {
                    Log.d(TAG, "Page loaded: $url → exec resolveUid JS")
                    // Re-inject le hook au cas où la page client-side route ait shadow
                    view?.evaluateJavascript(JS_INSTALL_HOOK, null)
                    view?.evaluateJavascript(JS_RESOLVE_UID, null)
                }
            }
        }
        // v15 : timeout 40s — le SPA M6+ prend qq sec à initialiser +
        //   appeler les API DRM. Pas critique car la WebView tourne en async.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!done) {
                Log.w(TAG, "Resolve timeout 40s — releasing lock")
                finish(null)
            }
        }, 40_000L)
        // v15 : charge la page LIVE direct M6 qui déclenche le SPA player.
        //   Le SPA fait son pipeline DRM complet (= /getJwt + /upfront-token)
        //   avec ses propres cookies session. Le JS hook (injecté via
        //   evaluateJavascript dans onPageFinished) intercepte les responses
        //   et les forwarde à OnyxM6Uid.onJwt.
        webView.loadUrl("https://www.m6.fr/m6plus/direct-m6-f_999")
    }

    /** v15 : JS hook injecté EN PREMIER. Override window.fetch + XHR pour
     *  intercepter les responses /getJwt + /upfront-token que le SPA M6+
     *  fait naturellement quand il joue une vidéo. Le SPA utilise sa session
     *  same-origin → pas de CORS, et fait passer CloudFront WAF avec ses
     *  bons cookies + headers. On capte le JWT M6 dès qu'il transite. */
    private const val JS_INSTALL_HOOK = """
(() => {
  if (window.__onyxHookInstalled) return;
  window.__onyxHookInstalled = true;
  console.log('[OnyxHook] installing fetch+XHR interceptors');
  const origFetch = window.fetch;
  window.fetch = async function(...args) {
    const resp = await origFetch.apply(this, args);
    try {
      const url = (typeof args[0] === 'string') ? args[0] : (args[0] && args[0].url) || '';
      if (url.includes('front-auth.6cloud.fr') && url.includes('/getJwt')) {
        const clone = resp.clone();
        const txt = await clone.text();
        console.log('[OnyxHook] getJwt response captured len=' + txt.length);
        try {
          const j = JSON.parse(txt);
          const t = (j && (j.token || j.jwt)) || '';
          if (t) {
            console.log('[OnyxHook] → M6 JWT len=' + t.length);
            try { OnyxM6Uid.onJwtIntercepted(t); } catch (e) {}
          }
        } catch (e) {}
      }
    } catch (e) {}
    return resp;
  };
  const origOpen = XMLHttpRequest.prototype.open;
  const origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url) {
    this.__onyxUrl = url;
    return origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function() {
    if (this.__onyxUrl && this.__onyxUrl.includes('front-auth.6cloud.fr') && this.__onyxUrl.includes('/getJwt')) {
      this.addEventListener('load', () => {
        try {
          const txt = this.responseText || '';
          console.log('[OnyxHook] XHR getJwt response captured len=' + txt.length);
          const j = JSON.parse(txt);
          const t = (j && (j.token || j.jwt)) || '';
          if (t) {
            console.log('[OnyxHook] → M6 JWT (via XHR) len=' + t.length);
            try { OnyxM6Uid.onJwtIntercepted(t); } catch (e) {}
          }
        } catch (e) {}
      });
    }
    return origSend.apply(this, arguments);
  };
})();
"""

    /** Récupère le UID via le SDK Gigya côté m6.fr. Le SDK est auto-injecté
     *  par le site, on attend qu'il soit prêt (polling 200ms × 30 = 6s) puis
     *  on appelle getAccountInfo qui retourne le UID via callback. Fallback
     *  sur session.verify si getAccountInfo échoue. */
    private const val JS_RESOLVE_UID = """
(async () => {
  const waitGigya = async () => {
    for (let i = 0; i < 30; i++) {
      if (window.gigya && window.gigya.accounts) return true;
      await new Promise(r => setTimeout(r, 200));
    }
    return false;
  };
  const tryGetAccountInfo = () => new Promise((resolve) => {
    try {
      window.gigya.accounts.getAccountInfo({
        include: 'profile,emails',
        callback: resolve
      });
    } catch (e) { resolve({ errorCode: -1, errorMessage: e.message }); }
  });
  const trySessionVerify = () => new Promise((resolve) => {
    try {
      if (window.gigya.accounts.session && window.gigya.accounts.session.verify) {
        window.gigya.accounts.session.verify({ callback: resolve });
      } else { resolve({ errorCode: -1 }); }
    } catch (e) { resolve({ errorCode: -1 }); }
  });
  try {
    const ok = await waitGigya();
    if (!ok) { console.log('[OnyxM6Uid] gigya SDK never loaded'); OnyxM6Uid.onUid(null); return; }
    const r1 = await tryGetAccountInfo();
    console.log('[OnyxM6Uid] getAccountInfo errorCode=' + r1.errorCode + ' UID=' + (r1.UID ? r1.UID.substring(0, 8) + '...' : 'none'));
    // Étape 2 : avec UID + signature + timestamp, fait getJwt côté browser
    //   (= TLS Chrome + cookies → passe CloudFront contrairement à OkHttp).
    // 2026-06-19 v9 : payload correct selon Catchup TV plugin.video.6play :
    //   {idtoken, utc_offset, consents, gdpr_apply}. L'idtoken = JWT Gigya
    //   obtenu via gigya.accounts.getJWT (signé par Gigya, contient l'UID).
    //   NOT uid+signature+timestamp comme je pensais. Le serveur 6cloud
    //   front-auth valide le JWT Gigya + génère son propre JWT M6.
    const getGigyaJwt = () => new Promise((resolve) => {
      try {
        window.gigya.accounts.getJWT({ callback: resolve });
      } catch (e) { resolve({ errorCode: -1, errorMessage: e.message }); }
    });
    const fetchJwt = async () => {
      const gj = await getGigyaJwt();
      console.log('[OnyxM6Uid] getJWT errorCode=' + gj.errorCode + ' id_token len=' + ((gj.id_token || '').length));
      if (gj.errorCode !== 0 || !gj.id_token) return '';
      try {
        // v10 : Content-Type 'text/plain' rend la requête CORS "simple"
        //   = pas de preflight OPTIONS = pas de check Access-Control-Allow-Origin.
        //   Le serveur lit quand même le body comme JSON s'il sait le parser.
        const resp = await fetch('https://front-auth.6cloud.fr/v2/platforms/m6group_web/getJwt', {
          method: 'POST',
          credentials: 'omit',
          headers: { 'Content-Type': 'text/plain' },
          body: JSON.stringify({
            idtoken: gj.id_token,
            utc_offset: '0',
            consents: [],
            gdpr_apply: false
          })
        });
        console.log('[OnyxM6Uid] front-auth status=' + resp.status);
        if (!resp.ok) {
          const t = await resp.text();
          console.log('[OnyxM6Uid] front-auth body[:200]=' + t.substring(0, 200));
          return '';
        }
        const j = await resp.json();
        const token = (j && (j.token || j.jwt)) || '';
        console.log('[OnyxM6Uid] M6 JWT token len=' + token.length);
        return token;
      } catch (e) {
        console.log('[OnyxM6Uid] front-auth err: ' + e.message);
        // Fallback : retourne au moins le JWT Gigya. Kotlin tentera l'OkHttp.
        return 'GIGYA::' + gj.id_token;
      }
    };
    if (r1.errorCode === 0 && r1.UID) {
      const jwt = await fetchJwt();
      OnyxM6Uid.onUid(r1.UID, r1.UIDSignature || '', String(r1.signatureTimestamp || ''), jwt);
      return;
    }
    const r2 = await trySessionVerify();
    console.log('[OnyxM6Uid] session.verify errorCode=' + r2.errorCode + ' UID=' + (r2.UID ? r2.UID.substring(0, 8) + '...' : 'none'));
    if (r2.errorCode === 0 && r2.UID) {
      const jwt = await fetchJwt();
      OnyxM6Uid.onUid(r2.UID, r2.UIDSignature || '', String(r2.signatureTimestamp || ''), jwt);
      return;
    }
  } catch (e) { console.log('[OnyxM6Uid] err: ' + e.message); }
  OnyxM6Uid.onUid(null, '', '', '');
})();
"""
}
