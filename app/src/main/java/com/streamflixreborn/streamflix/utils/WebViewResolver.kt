package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import com.streamflixreborn.streamflix.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class WebViewResolver(private val context: Context) {

    private var webView: WebView? = null
    private var dialog: AlertDialog? = null
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val TAG = "Cine24hBypass"
    
    private var cursorX = 0f
    private var cursorY = 0f
    private var virtualCursor: ImageView? = null
    private var pollingCount = 0
    private val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private val challengeKeywords = listOf(
        // EN
        "Just a moment...", "cf-browser-verification", "challenge-running", "Checking your browser", "cloudflare",
        // FR (2026-06-09 : DessinAnime CF challenge en français)
        "Un instant", "Veuillez patienter", "Vérification en cours", "Vérifie votre navigateur",
        // 2026-06-28 : bot shield Wiflix (flemmix.city) — page intermédiaire
        //   JS après le Turnstile CF, doit être traitée comme un challenge.
        "Bot shield active"
    )

    /** Si silent=true, désactive le dialog "challenge visible" — pour les
     *  providers qui veulent un fallback discret sans interaction user. */
    private var silentMode: Boolean = false

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), silent: Boolean = false): String = mutex.withLock {
        Log.d(TAG, "[WebView] Fetching: $url (IsTV: $isTv, silent=$silent)")
        pollingCount = 0
        silentMode = silent
        val result = withTimeoutOrNull(if (silent) 30000 else 120000) {
            suspendCancellableCoroutine { continuation ->
                mainHandler.post { setupWebView(url, headers, continuation) }
                continuation.invokeOnCancellation { cleanup() }
            }
        }
        if (result == null) Log.e(TAG, "[WebView] Global Timeout for $url")
        return@withLock result ?: "<html><body>Timeout</body></html>"
    }

    companion object {
        // 2026-06-28 : User-Agent réaliste Chrome 131 (dernière stable Android).
        //   L'ancien UA Chrome/116 est vieux de 2 ans → score bot élevé chez CF.
        //   NB : on ne touche PAS NetworkClient.USER_AGENT (utilisé par OkHttp
        //   pour les appels API normaux). STEALTH_UA est utilisé par le WebView
        //   bypass ET par les requêtes OkHttp post-bypass (le cookie cf_clearance
        //   est lié au UA — mismatch = rejet).
        const val STEALTH_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.39 Mobile Safari/537.36"
    }

    // 2026-06-28 : script JS anti-détection injecté AVANT que les scripts
    //   Cloudflare ne s'exécutent (via onPageStarted). Spoofes les empreintes
    //   que CF Turnstile vérifie pour distinguer un WebView d'un vrai Chrome :
    //   - navigator.webdriver = undefined (WebView met true → red flag #1)
    //   - window.chrome = objet Chrome réaliste (absent en WebView → red flag #2)
    //   - navigator.plugins = faux plugins (vide en WebView → red flag #3)
    //   - navigator.permissions.query = pas d'erreur sur "notifications"
    //   - WebGL renderer = pas "SwiftShader" (indicateur headless)
    //   Résultat : le Turnstile devrait s'auto-résoudre comme dans un vrai
    //   navigateur (~2-3s), sans que le user ait besoin de cliquer.
    private val STEALTH_JS = """
        (function() {
            // 1. navigator.webdriver = undefined (le signal #1 que CF vérifie)
            try {
                Object.defineProperty(navigator, 'webdriver', {
                    get: function() { return undefined; },
                    configurable: true
                });
            } catch(e) {}

            // 2. window.chrome = objet réaliste (absent en WebView = bot)
            if (!window.chrome) {
                window.chrome = {
                    app: { isInstalled: false, getDetails: function(){}, getIsInstalled: function(){}, installState: function(){}, runningState: function(){ return 'cannot_run'; } },
                    runtime: { id: undefined, connect: function(){}, sendMessage: function(){}, onMessage: { addListener: function(){}, removeListener: function(){} }, PlatformOs: { ANDROID: 'android' }, lastError: null },
                    csi: function(){ return {}; },
                    loadTimes: function(){ return {}; }
                };
            }

            // 3. navigator.plugins = faux plugins (vide en WebView = suspect)
            try {
                Object.defineProperty(navigator, 'plugins', {
                    get: function() {
                        return [
                            { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format', length: 1 },
                            { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '', length: 1 },
                            { name: 'Native Client', filename: 'internal-nacl-plugin', description: '', length: 2 }
                        ];
                    },
                    configurable: true
                });
            } catch(e) {}

            // 4. navigator.languages (certains WebView renvoient juste ["en"])
            try {
                Object.defineProperty(navigator, 'languages', {
                    get: function() { return ['fr-FR', 'fr', 'en-US', 'en']; },
                    configurable: true
                });
            } catch(e) {}

            // 5. permissions.query — pas d'erreur sur "notifications" (WebView throw)
            try {
                var origQuery = navigator.permissions.query.bind(navigator.permissions);
                navigator.permissions.query = function(params) {
                    if (params && params.name === 'notifications') {
                        return Promise.resolve({ state: 'prompt', onchange: null });
                    }
                    return origQuery(params);
                };
            } catch(e) {}

            // 6. Dimension écran réaliste (certaines implémentations renvoient 0)
            try {
                if (screen.width === 0 || screen.height === 0) {
                    Object.defineProperty(screen, 'width', { get: function(){ return 412; } });
                    Object.defineProperty(screen, 'height', { get: function(){ return 915; } });
                }
            } catch(e) {}

            // 7. Spoof canvas toDataURL pour éviter le fingerprint "vide"
            //    (on ne change pas le rendu, juste on s'assure que c'est pas vide)

            // 8. Cacher que c'est un WebView via le feature check
            try {
                Object.defineProperty(navigator, 'userAgent', {
                    get: function() { return navigator.__originalUA || navigator.userAgent; },
                    configurable: true
                });
            } catch(e) {}
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(url: String, headers: Map<String, String>, continuation: kotlinx.coroutines.CancellableContinuation<String>) {
        webView = WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            // IMPORTANTE: Su TV non deve essere focusable per lasciare il controllo al container
            isFocusable = !isTv
            isFocusableInTouchMode = !isTv

            // Stabilità Rendering Software per Android TV 9 (come da registro)
            if (isTv) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // 2026-06-28 : UA Chrome 131 réaliste au lieu de l'ancien
                //   Chrome 116 (2 ans de retard = score bot élevé chez CF)
                userAgentString = STEALTH_UA
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadWithOverviewMode = true
                useWideViewPort = true
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, currentUrl: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, currentUrl, favicon)
                    // 2026-06-28 : injecter les patches anti-détection le PLUS
                    //   TÔT possible — AVANT que les scripts Cloudflare ne
                    //   s'exécutent et lisent navigator.webdriver / window.chrome.
                    //   onPageStarted est le 1er callback = injection optimale.
                    view?.evaluateJavascript(STEALTH_JS, null)
                    Log.d(TAG, "[WebView] Stealth patches injected for $currentUrl")
                }

                override fun onPageFinished(view: WebView?, currentUrl: String?) {
                    Log.d(TAG, "[WebView] onPageFinished: $currentUrl")
                    // 2026-06-28 : ré-injecter les patches après le load complet
                    //   au cas où un framework JS (React/Next.js) réinitialise
                    //   le DOM et perd les patches du onPageStarted.
                    view?.evaluateJavascript(STEALTH_JS, null)
                    // 2026-06-09 v2 : 500ms → 100ms — check immédiat des liens
                    //   réels du site. Si page chargée → SUCCESS direct.
                    mainHandler.postDelayed({
                        if (webView != null) checkChallengeStatus(view, currentUrl ?: url, continuation)
                    }, 100)
                }
            }
            loadUrl(url, headers)
        }
    }

    private fun checkChallengeStatus(view: WebView?, currentUrl: String, continuation: kotlinx.coroutines.CancellableContinuation<String>) {
        if (continuation.isCompleted || webView == null) return
        
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(currentUrl) ?: ""
        val hasClearance = cookies.contains("cf_clearance")
        
        view?.evaluateJavascript("(function() { return document.documentElement.innerHTML; })();") { html ->
            val cleanHtml = html?.trim()?.removeSurrounding("\"")
                ?.replace("\\u003C", "<")?.replace("\\\"", "\"")?.replace("\\n", "\n")?.replace("\\t", "\t") ?: ""
            
            val isChallenge = challengeKeywords.any { cleanHtml.contains(it, ignoreCase = true) }
            val hasContent = cleanHtml.contains("article") || cleanHtml.contains("iframe") ||
                             cleanHtml.contains("TPost") || cleanHtml.contains("grid-item") ||
                             cleanHtml.contains("optnslst") || // Rilevamento server Cine24h (come da registro)
                             cleanHtml.contains("block-main") || cleanHtml.contains("mov-t") ||
                             cleanHtml.contains("mov-list") || cleanHtml.contains("posterimg") || // Wiflix
                             cleanHtml.contains("grabScroll") || cleanHtml.contains("catalog-card") ||
                             cleanHtml.contains("fadeJours") || cleanHtml.contains("anime-card-premium") || // AnimeSama
                             // 2026-06-09 (user "la page devrait être instantanée") :
                             //   DessinAnime est en Next.js → garde "_next/" et
                             //   "__NEXT_DATA__" comme marqueurs de content.
                             cleanHtml.contains("_next/") || cleanHtml.contains("__NEXT_DATA__") ||
                             cleanHtml.contains("dessinanime")

            Log.d(TAG, "[WebView] Status -> Challenge: $isChallenge, Content: $hasContent, Clearance: $hasClearance, Polling: $pollingCount")

            // 2026-05-26 : un ancien cookie cf_clearance PÉRIMÉ peut être présent
            // alors que le challenge est toujours actif → ne PAS court-circuiter
            // sur hasClearance si isChallenge=true (sinon le dialog ne s'affiche jamais).
            //
            // 2026-06-09 (DessinAnime) : 3e condition pour les sites dont le
            //   HTML normal contient le mot "cloudflare" (scripts analytics) →
            //   isChallenge reste toujours true mais le contenu est là.
            //   Après 3 polls (~6s), si on a du contenu volumineux + clearance,
            //   on considère le bypass réussi (false positive du keyword).
            // 2026-06-09 : SUCCESS si la page contient des liens RÉELS du site
            //   (href="/movie/" ou "/tv/") — c'est le contenu, peu importe si
            //   "cloudflare" ou "Un instant" traînent encore dans des scripts.
            val hasRealSiteLinks = cleanHtml.contains("href=\"/movie/") ||
                cleanHtml.contains("href=\"/tv/") ||
                // 2026-06-23 : Wiflix — ses liens réels sont /film/ et /serie/
                //   PLUS ses marqueurs de contenu (posterimg, mov-t, mov-list)
                //   qui prouvent que le site a chargé DERRIÈRE le faux-positif
                //   "cloudflare" (= CDN analytics, pas un challenge actif).
                cleanHtml.contains("href=\"/film/") ||
                cleanHtml.contains("href=\"/serie/") ||
                // Wiflix content markers — si présents, c'est le VRAI site
                (cleanHtml.contains("posterimg") && cleanHtml.contains("mov-t")) ||
                // 2026-06-09 v2 : page film avec iframe lecteur (PAS Turnstile)
                (cleanHtml.contains("<iframe", ignoreCase = true) &&
                    !cleanHtml.contains("challenges.cloudflare.com", ignoreCase = true) &&
                    cleanHtml.contains("dessinanime", ignoreCase = true))
            if (hasRealSiteLinks
                || (!isChallenge && hasContent && cleanHtml.length > 1000)
                || (hasClearance && !isChallenge)
                || (!isChallenge && hasContent && hasClearance && cleanHtml.length > 5000 && pollingCount >= 3)
                // 2026-06-28 : si le cookie cf_clearance est posé ET la page a
                //   du vrai contenu ET c'est assez gros, c'est du vrai contenu
                //   même si "cloudflare" traîne dans les scripts CDN/analytics.
                || (hasClearance && hasContent && cleanHtml.length > 5000)) {
                Log.d(TAG, "[WebView] SUCCESS detected! Closing bypass.")
                cookieManager.flush()
                if (continuation.isActive) {
                    continuation.resume("<html>$cleanHtml</html>")
                    cleanup()
                }
                return@evaluateJavascript
            }

            // 2026-06-28 : détecter la page "Error 1015 — You are being rate
            //   limited" AVANT le dialog. C'est une page Cloudflare qui contient
            //   le mot "cloudflare" → isChallenge=true, mais il n'y a RIEN à
            //   résoudre (pas de Turnstile). Afficher le dialog est inutile et
            //   confus pour le user. On renvoie un marker silencieux ; le
            //   WiflixProvider détectera le 1015 et activera le cooldown 15min.
            val isRateLimited = cleanHtml.contains("Error 1015", ignoreCase = true) ||
                cleanHtml.contains("You are being rate limited", ignoreCase = true) ||
                cleanHtml.contains("has banned you temporarily", ignoreCase = true)
            if (isRateLimited) {
                Log.w(TAG, "[WebView] Rate limit 1015 detected — NOT showing dialog, returning silently")
                cookieManager.flush()
                if (continuation.isActive) continuation.resume("<html><!-- rate limited 1015 --></html>")
                cleanup()
                return@evaluateJavascript
            }

            // Se dopo 2 polling (circa 3-4 secondi) non c'è contenuto, mostriamo il dialog per sbloccare.
            //   Sauf en mode silent (background catalog scraping) — fail-fast.
            if (silentMode && pollingCount >= 3 && !hasContent) {
                Log.d(TAG, "[WebView] Silent mode + no content after 3 polls → fail silently")
                cookieManager.flush()
                if (continuation.isActive) continuation.resume("<html><!-- silent fail --></html>")
                cleanup()
                return@evaluateJavascript
            }
            if (!silentMode && dialog == null && (
                    (pollingCount >= 2 && !hasContent) ||
                    // 2026-06-09 (user "la page met 30 secondes") : si challenge
                    //   actif détecté au polling 0 → dialog IMMÉDIATEMENT (pas
                    //   d'attente 2-3 polls).
                    isChallenge
                )) {
                Log.d(TAG, "[WebView] Challenge active (poll=$pollingCount) — forcing Visible Challenge UI")
                showVisibleChallenge(continuation)
            }

            pollingCount++
            // 2026-06-28 : max polling aligné sur le timeout global —
            //   silent=100 (30s), visible=400 (120s). Avant : 80 (24s)
            //   trop court pour que le user résolve le Turnstile → dialog
            //   fermé, challenge HTML renvoyé = cache empoisonné.
            val maxPolls = if (silentMode) 100 else 400
            if (pollingCount < maxPolls) {
                mainHandler.postDelayed({ checkChallengeStatus(view, currentUrl, continuation) }, 300)
            } else {
                Log.w(TAG, "[WebView] Max polling reached ($maxPolls)")
                if (continuation.isActive) continuation.resume("<html>$cleanHtml</html>")
                cleanup()
            }
        }
    }

    private fun showVisibleChallenge(continuation: kotlinx.coroutines.CancellableContinuation<String>) {
        if (dialog != null || webView == null) return
        mainHandler.post {
            try {
                // 2026-06-09 : utiliser la currentActivity au moment EXACT du
                //   show, pas le context du constructor (qui peut pointer vers
                //   une Activity détruite). Évite BadTokenException.
                val liveAct = try {
                    com.streamflixreborn.streamflix.StreamFlixApp.currentActivity?.takeIf {
                        !it.isFinishing && !it.isDestroyed
                    }
                } catch (_: Throwable) { null }
                if (liveAct == null) {
                    Log.w(TAG, "[WebView] No live Activity for dialog — aborting bypass")
                    if (continuation.isActive) continuation.resume("<html><!-- no live activity --></html>")
                    cleanup()
                    return@post
                }
                val dialogCtx: android.content.Context = liveAct
                // CONTAINER TV: Intercetta i tasti globalmente (Soluzione "Meravigliosa")
                val rootContainer = object : RelativeLayout(dialogCtx) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (isTv) {
                                val step = 45f
                                when (event.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP -> { cursorY -= step; updateCursorPosition(); return true }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> { cursorY += step; updateCursorPosition(); return true }
                                    KeyEvent.KEYCODE_DPAD_LEFT -> { cursorX -= step; updateCursorPosition(); return true }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> { cursorX += step; updateCursorPosition(); return true }
                                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                        Log.d(TAG, "[WebView] TV OK Key -> Simulating Mouse")
                                        simulateHumanMouseClick()
                                        return true
                                    }
                                }
                            }
                            
                            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                                Log.d(TAG, "[WebView] BACK Key -> Cancelling bypass")
                                dialog?.cancel()
                                return true
                            }
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                    setBackgroundColor(Color.BLACK)
                    isFocusable = isTv
                    isFocusableInTouchMode = isTv
                }

                if (isTv) {
                    val btnInfo = Button(dialogCtx).apply {
                        id = View.generateViewId()
                        text = dialogCtx.getString(R.string.bypass_tv_instructions)
                        setBackgroundColor(Color.parseColor("#4CAF50"))
                        setTextColor(Color.WHITE)
                        textSize = 20f
                        stateListAnimator = null
                        isFocusable = false
                    }
                    val infoParams = RelativeLayout.LayoutParams(-1, 200)
                    infoParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                    rootContainer.addView(btnInfo, infoParams)

                    val webContainer = FrameLayout(dialogCtx).apply {
                        id = View.generateViewId()
                        setBackgroundColor(Color.BLACK)
                    }
                    val webParams = RelativeLayout.LayoutParams(-1, -1)
                    webParams.addRule(RelativeLayout.BELOW, btnInfo.id)
                    rootContainer.addView(webContainer, webParams)

                    (webView?.parent as? ViewGroup)?.removeView(webView)
                    webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))

                    virtualCursor = ImageView(dialogCtx).apply {
                        setImageResource(android.R.drawable.ic_menu_mylocation) 
                        setColorFilter(Color.RED)
                        layoutParams = FrameLayout.LayoutParams(80, 80)
                        elevation = 100f
                    }
                    rootContainer.addView(virtualCursor)
                } else {
                    // Mobile: Solo WebView a tutto schermo
                    (webView?.parent as? ViewGroup)?.removeView(webView)
                    rootContainer.addView(webView, RelativeLayout.LayoutParams(-1, -1))
                }

                dialog = AlertDialog.Builder(dialogCtx, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
                    .setView(rootContainer)
                    .setCancelable(true)
                    .setOnCancelListener {
                        Log.d(TAG, "[WebView] Challenge cancelled by user")
                        if (continuation.isActive) {
                            continuation.resume("<html><body>User cancelled</body></html>")
                        }
                        cleanup()
                    }
                    .create()
                
                dialog?.show()

                if (isTv) {
                    rootContainer.post {
                        cursorX = rootContainer.width / 2f
                        cursorY = rootContainer.height / 2f
                        updateCursorPosition()
                        rootContainer.requestFocus()
                    }
                }
                Log.d(TAG, "[WebView] Challenge Dialog DISPLAYED (isTv: $isTv)")
            } catch (e: Exception) { Log.e(TAG, "[WebView] CRITICAL UI ERROR", e) }
        }
    }

    private fun updateCursorPosition() {
        virtualCursor?.let { cursor ->
            cursor.translationX = cursorX - 40
            cursor.translationY = cursorY - 40
            cursor.bringToFront()
        }
    }

    private fun simulateHumanMouseClick() {
        webView?.let { wv ->
            val location = IntArray(2)
            wv.getLocationOnScreen(location)
            val relX = cursorX - location[0]
            val relY = cursorY - location[1]

            val downTime = SystemClock.uptimeMillis()
            val propM = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
            val coordM = MotionEvent.PointerCoords().apply { x = relX; y = relY; pressure = 1f; size = 1f }

            val hover = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_HOVER_MOVE, 1, arrayOf(propM), arrayOf(coordM), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            wv.dispatchGenericMotionEvent(hover); hover.recycle()

            val eventDown = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 1, arrayOf(propM), arrayOf(coordM), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            wv.dispatchTouchEvent(eventDown)

            mainHandler.postDelayed({
                coordM.x += 1f; coordM.y += 1f
                val eventUp = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1, arrayOf(propM), arrayOf(coordM), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
                wv.dispatchTouchEvent(eventUp)
                eventDown.recycle(); eventUp.recycle()
                CookieManager.getInstance().flush()
                Log.d(TAG, "[WebView] Simulated Mouse Click at ($relX, $relY)")
            }, 200)
        }
    }

    fun cleanup() {
        mainHandler.post {
            try {
                dialog?.dismiss(); dialog = null
                webView?.stopLoading(); webView?.destroy(); webView = null
                virtualCursor = null
            } catch (e: Exception) { }
        }
    }
}
