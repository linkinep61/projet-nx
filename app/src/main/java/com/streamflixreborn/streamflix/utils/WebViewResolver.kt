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
        "Un instant", "Veuillez patienter", "Vérification en cours", "Vérifie votre navigateur"
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(url: String, headers: Map<String, String>, continuation: kotlinx.coroutines.CancellableContinuation<String>) {
        webView = WebView(context).apply {
            setBackgroundColor(Color.WHITE)
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
                userAgentString = NetworkClient.USER_AGENT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadWithOverviewMode = true
                useWideViewPort = true
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, currentUrl: String?) {
                    Log.d(TAG, "[WebView] onPageFinished: $currentUrl")
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
                ?.replace("\\u003C", "<")?.replace("\\\"", "\"")?.replace("\\n", "\n") ?: ""
            
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
                // 2026-06-09 v2 : page film avec iframe lecteur (PAS Turnstile)
                (cleanHtml.contains("<iframe", ignoreCase = true) &&
                    !cleanHtml.contains("challenges.cloudflare.com", ignoreCase = true) &&
                    cleanHtml.contains("dessinanime", ignoreCase = true))
            if (hasRealSiteLinks
                || (!isChallenge && hasContent && cleanHtml.length > 1000)
                || (hasClearance && !isChallenge)
                || (!isChallenge && hasContent && hasClearance && cleanHtml.length > 5000 && pollingCount >= 3)) {
                Log.d(TAG, "[WebView] SUCCESS detected! Closing bypass.")
                cookieManager.flush()
                if (continuation.isActive) {
                    continuation.resume("<html>$cleanHtml</html>")
                    cleanup()
                }
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
            if (pollingCount < 80) {
                // 2026-06-09 v3 : 800ms → 300ms pour détection SUCCESS quasi-
                //   instantanée dès que les liens réels du site apparaissent.
                mainHandler.postDelayed({ checkChallengeStatus(view, currentUrl, continuation) }, 300)
            } else {
                Log.w(TAG, "[WebView] Max polling reached")
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
                        setBackgroundColor(Color.WHITE)
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
