package com.streamflixreborn.streamflix.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.streamflixreborn.streamflix.utils.M6Auth
import com.streamflixreborn.streamflix.utils.TF1Auth

/**
 * Activité fullscreen contenant une WebView qui charge la page de connexion
 * d'un service de replay (TF1+ ou M6 6play). L'utilisateur saisit ses
 * identifiants directement dans la WebView (= page officielle, sécurisé).
 * L'activité écoute les changements d'URL et intercepte le cookie de
 * session quand le login est validé.
 *
 * Usage :
 *   LoginWebViewActivity.start(context, LoginWebViewActivity.SERVICE_M6)
 *
 * Au retour (RESULT_OK), `M6Auth.isLoggedIn(ctx) == true` (resp. TF1Auth).
 */
class LoginWebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginWebView"
        const val EXTRA_SERVICE = "service"
        const val SERVICE_M6 = "M6"
        const val SERVICE_TF1 = "TF1"

        fun start(ctx: Context, service: String) {
            val i = Intent(ctx, LoginWebViewActivity::class.java).apply {
                putExtra(EXTRA_SERVICE, service)
                if (ctx !is Activity) flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(i)
        }
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var rootLayout: FrameLayout
    private var popupWebView: WebView? = null
    private var service: String = SERVICE_M6
    private var captured: Boolean = false

    // 2026-06-19 v35 (user "souris virtuelle pour aller cliquer sur les trucs
    //   on peut toujours pas les sélectionner Google Pour se connecter") :
    //   sur TV, vrai pointeur souris déplaçable au D-pad + clic central.
    //   Permet de cliquer N'IMPORTE QUEL bouton OAuth (Google, Apple, etc.)
    //   même non-focusable.
    private var cursorView: ImageView? = null
    private var cursorX: Float = 0f
    private var cursorY: Float = 0f
    private val cursorSize: Int = 64  // dp final ~ 64px
    private val isTv: Boolean by lazy {
        com.streamflixreborn.streamflix.BuildConfig.APP_LAYOUT == "tv" ||
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = intent.getStringExtra(EXTRA_SERVICE) ?: SERVICE_M6

        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }
        val root = rootLayout
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 2026-06-18 (user "M6 page bleue close window") : support
            //   popups OAuth Facebook/Google/Apple. Sans ces réglages, les
            //   popups OAuth restent ouvertes en arrière-plan et ne savent
            //   pas se fermer = écran bleu "Close window" infini.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.userAgentString = USER_AGENT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = LoginWebViewClient()
            webChromeClient = LoginChromeClient()
            // 2026-06-18 v3 : interface JS pour capture JWT TF1
            if (service == SERVICE_TF1) {
                addJavascriptInterface(TF1JwtBridge(), "OnyxBridge")
            }
        }
        progress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                100, 100, android.view.Gravity.CENTER
            )
        }
        // 2026-06-18 : bouton CLOSE X en haut à droite pour quitter la
        //   WebView si l'user est perdu (= popup OAuth coincée, etc.).
        val closeBtn = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                120, 120,
                android.view.Gravity.TOP or android.view.Gravity.END
            ).apply { setMargins(16, 60, 16, 0) }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xCC000000.toInt())
            }
            setPadding(20, 20, 20, 20)
            setColorFilter(0xFFFFFFFF.toInt())
            isClickable = true
            setOnClickListener {
                Toast.makeText(this@LoginWebViewActivity,
                    "Connexion annulée", Toast.LENGTH_SHORT).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        root.addView(webView)
        root.addView(progress)
        root.addView(closeBtn)

        // 2026-06-18 v4 : pour TF1, bouton flottant "✓ Terminer connexion"
        //   en bas-centre. À cliquer APRÈS avoir fini l'OAuth → injecte
        //   le JS qui récupère le JWT.
        if (service == SERVICE_TF1) {
            val finishBtn = android.widget.Button(this).apply {
                text = "✓ Terminer connexion TF1+"
                setBackgroundColor(0xCC1E88E5.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                ).apply { setMargins(0, 0, 0, 80) }
                setPadding(48, 24, 48, 24)
                setOnClickListener { triggerTf1JwtCapture() }
            }
            root.addView(finishBtn)
        }
        setContentView(root)

        // 2026-06-19 v35 (user "souris virtuelle pour aller cliquer sur les
        //   trucs on peut toujours pas les sélectionner Google") : sur TV,
        //   ajoute un cursor overlay déplaçable au D-pad + clic central.
        if (isTv) {
            initVirtualCursor()
        }

        val loginUrl = when (service) {
            SERVICE_TF1 -> "https://www.tf1.fr/compte/connexion"
            else -> "https://www.6play.fr/connexion"
        }
        title = when (service) {
            SERVICE_TF1 -> "Connexion TF1+"
            else -> "Connexion M6 6play"
        }
        Log.d(TAG, "Loading login URL: $loginUrl")
        webView.loadUrl(loginUrl)
    }

    override fun onDestroy() {
        try { webView.stopLoading(); webView.removeAllViews(); webView.destroy() } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ─────────────── SOURIS VIRTUELLE TV (v35) ───────────────
    // Permet de cliquer sur N'IMPORTE QUEL bouton de la WebView (y compris
    // OAuth Google/Apple/Facebook non-focusables) avec la télécommande.
    // D-pad UP/DOWN/LEFT/RIGHT bougent le curseur, OK/ENTER simule un tap.

    private fun initVirtualCursor() {
        val cursor = ImageView(this).apply {
            // Drawable cursor : cercle jaune semi-transparent avec point noir.
            val ring = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x66FFD700.toInt())  // jaune semi-transparent
                setStroke(4, 0xFF000000.toInt())  // bord noir
            }
            background = ring
            layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
            elevation = 100f
            isClickable = false
            isFocusable = false
        }
        cursorView = cursor
        rootLayout.addView(cursor)
        // Position initiale = centre de l'écran après que le layout soit mesuré.
        rootLayout.post {
            cursorX = (rootLayout.width / 2f) - (cursorSize / 2f)
            cursorY = (rootLayout.height / 2f) - (cursorSize / 2f)
            updateCursorPosition()
        }
        Log.d(TAG, "Virtual cursor initialized (size=${cursorSize}px)")
    }

    private fun updateCursorPosition() {
        val c = cursorView ?: return
        c.translationX = cursorX
        c.translationY = cursorY
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Si la souris virtuelle n'existe pas (= mobile), comportement standard.
        val c = cursorView ?: return super.dispatchKeyEvent(event)
        // Ne traite que les ACTION_DOWN (= 1 event par appui logique).
        if (event.action != android.view.KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        // Accélération avec auto-repeat : plus on tient appuyé, plus c'est rapide.
        val baseStep = 40f
        val accel = 1f + minOf(event.repeatCount, 12) * 0.5f
        val step = baseStep * accel
        val maxX = (rootLayout.width - cursorSize).toFloat().coerceAtLeast(0f)
        val maxY = (rootLayout.height - cursorSize).toFloat().coerceAtLeast(0f)
        // 2026-06-19 v36 (user "on peut pas descendre dans le bas de la page
        //   pour se connecter, ça déclenche pas le menu déroulant") : quand le
        //   curseur atteint le bord vertical, continuer à appuyer DOWN/UP
        //   SCROLL la WebView au lieu d'être bloqué.
        val targetWebView: WebView = popupWebView ?: webView
        val scrollStep = (step * 3f).toInt().coerceAtLeast(80)
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                if (cursorY <= 0.5f) {
                    targetWebView.scrollBy(0, -scrollStep)
                } else {
                    cursorY = (cursorY - step).coerceAtLeast(0f)
                    updateCursorPosition()
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (cursorY >= maxY - 0.5f) {
                    targetWebView.scrollBy(0, scrollStep)
                } else {
                    cursorY = (cursorY + step).coerceAtMost(maxY)
                    updateCursorPosition()
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (cursorX <= 0.5f) {
                    targetWebView.scrollBy(-scrollStep, 0)
                } else {
                    cursorX = (cursorX - step).coerceAtLeast(0f)
                    updateCursorPosition()
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (cursorX >= maxX - 0.5f) {
                    targetWebView.scrollBy(scrollStep, 0)
                } else {
                    cursorX = (cursorX + step).coerceAtMost(maxX)
                    updateCursorPosition()
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                simulateTapAtCursor()
                return true
            }
            // KEYCODE_BACK / KEYCODE_ESCAPE / autres laissés au système.
        }
        return super.dispatchKeyEvent(event)
    }

    private fun simulateTapAtCursor() {
        // Position du tap = centre visible du curseur, dans les coords de la WebView.
        val tapX = cursorX + cursorSize / 2f
        val tapY = cursorY + cursorSize / 2f
        // Si une popup OAuth est ouverte, on tape DESSUS, sinon sur la WebView principale.
        val targetView: WebView = popupWebView ?: webView

        // 2026-06-19 (user "pourquoi ça marche sur mobile et pas sur TV") :
        //   le ancien dispatchTouchEvent() crée un MotionEvent SYNTHÉTIQUE
        //   (event.isTrusted=false côté JS). Google OAuth détecte ça et
        //   ferme la popup avec "Permission denied".
        //   FIX : on convertit les coords absolues écran → coords relatives à
        //   la WebView (scale + scroll), puis on appelle elementFromPoint(x,y).click()
        //   via JS. C'est un click DOM natif sur l'élément, indistinguible
        //   d'un vrai clic humain pour Google.
        val location = IntArray(2)
        targetView.getLocationOnScreen(location)
        val relX = tapX - location[0]
        val relY = tapY - location[1]
        // Densité d'affichage : WebView reçoit les coords CSS, pas device pixels.
        val density = resources.displayMetrics.density
        val cssX = relX / density
        val cssY = relY / density
        val js = """
            (function() {
              try {
                var el = document.elementFromPoint($cssX, $cssY);
                if (!el) return 'NO_ELEMENT';
                // Remonte au premier ancêtre cliquable
                var clickable = el;
                while (clickable && clickable !== document.body) {
                  var tag = clickable.tagName ? clickable.tagName.toUpperCase() : '';
                  if (tag === 'A' || tag === 'BUTTON' || tag === 'INPUT' ||
                      clickable.onclick || clickable.getAttribute('role') === 'button' ||
                      window.getComputedStyle(clickable).cursor === 'pointer') {
                    break;
                  }
                  clickable = clickable.parentElement;
                }
                var target = clickable || el;
                // Focus si input pour permettre saisie clavier
                if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA') {
                  target.focus();
                  return 'FOCUS:' + target.tagName + ':' + (target.type || '');
                }
                target.click();
                return 'CLICK:' + target.tagName + ':' + (target.textContent ? target.textContent.substring(0, 30) : '');
              } catch(e) { return 'ERR:' + e.message; }
            })();
        """.trimIndent()
        targetView.evaluateJavascript(js) { result ->
            Log.d(TAG, "Virtual click at ($cssX, $cssY) on ${if (popupWebView != null) "popup" else "main"} → $result")
            // 2026-06-19 (user "arrivée ici pour cliquer dans l'adresse ça
            //   marche pas") : si on a focusé un input, on ouvre le clavier
            //   virtuel TV pour permettre la saisie. result format =
            //   "FOCUS:INPUT:email" → on parse pour décider.
            val isInput = result?.contains("FOCUS:INPUT") == true ||
                          result?.contains("FOCUS:TEXTAREA") == true
            if (isInput) {
                runOnUiThread {
                    targetView.requestFocus()
                    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(
                        targetView,
                        android.view.inputmethod.InputMethodManager.SHOW_FORCED
                    )
                    Log.d(TAG, "IME requested for input")
                }
            }
        }
        // Petit flash visuel du curseur pour confirmer le clic.
        cursorView?.let { c ->
            c.alpha = 1f
            c.animate().alpha(0.4f).setDuration(80L).withEndAction {
                c.animate().alpha(1f).setDuration(120L).start()
            }.start()
        }
    }

    /** Gère les popups OAuth (Facebook/Google/Apple). On crée une vraie
     *  WebView fille en plein écran par-dessus la WebView principale →
     *  l'user voit la page Google login + choisit son compte. Quand
     *  Google appelle `window.close()`, on ferme la fille + re-check
     *  les cookies sur la principale (= les cookies de session 6play
     *  sont alors posés grâce au callback OAuth). */
    private inner class LoginChromeClient : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean,
            isUserGesture: Boolean, resultMsg: android.os.Message?
        ): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            // 2026-06-18 (user "M6 page bleue Close Window") : WebView fille
            //   ATTACHÉE au layout (= visible par-dessus la principale).
            val popup = WebView(this@LoginWebViewActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0xFFFFFFFF.toInt())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportMultipleWindows(true)
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.userAgentString = USER_AGENT
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(v, url, favicon)
                        // 2026-06-19 v43 (user "Impossible de vous connecter"
                        //   sur la popup OAuth) : inject le bypass JS aussi
                        //   dans la POPUP. Sans ça, Google détecte la WebView
                        //   fille même si la principale a le bypass.
                        v?.evaluateJavascript(JS_HIDE_WEBVIEW_FINGERPRINT, null)
                    }
                    override fun onPageFinished(v: WebView?, url: String?) {
                        // Re-inject sur pageFinished aussi (= certaines pages
                        //   Google check le fingerprint après le load).
                        v?.evaluateJavascript(JS_HIDE_WEBVIEW_FINGERPRINT, null)
                        // Capture cookies sur la fille aussi (= au cas où
                        //   le login se ferme automatiquement après auth).
                        checkCookiesForToken(url)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        Log.d(TAG, "Popup OAuth window.close() → close")
                        closePopup()
                    }
                }
            }
            popupWebView = popup
            rootLayout.addView(popup)
            transport.webView = popup
            resultMsg.sendToTarget()
            Log.d(TAG, "Popup OAuth WebView attached")
            return true
        }
    }

    /** Ferme la WebView fille (popup OAuth) et re-check les cookies sur
     *  la WebView principale. */
    private fun closePopup() {
        val popup = popupWebView ?: return
        try { rootLayout.removeView(popup) } catch (_: Throwable) {}
        try { popup.destroy() } catch (_: Throwable) {}
        popupWebView = null
        Log.d(TAG, "Popup closed → re-check cookies on main WebView (${webView.url})")
        // Donne ~1s pour que les cookies se posent côté main avant de check
        webView.postDelayed({ checkCookiesForToken(webView.url) }, 1000)
    }

    /** BACK button : si popup ouverte → ferme popup. Sinon ferme l'activity. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (popupWebView != null) {
            closePopup()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private inner class LoginWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            progress.visibility = View.VISIBLE
            // 2026-06-19 v42 (user "Impossible de vous connecter / Ce navigateur
            //   ou cette application ne sont peut-être pas sécurisés") :
            //   Google détecte la WebView via JS (navigator.webdriver, plugins,
            //   window.chrome, etc.). On masque ces signaux AVANT que la page
            //   ne charge son script de fingerprinting. Inject sur CHAQUE page
            //   pour couvrir les redirects accounts.google.com.
            view?.evaluateJavascript(JS_HIDE_WEBVIEW_FINGERPRINT, null)
            checkCookiesForToken(url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            progress.visibility = View.GONE
            checkCookiesForToken(url)
            // 2026-06-19 v35 (user "les logos pour se connecter avec Facebook
            //   Google et Apple apparaissent 2 secondes et s'en vont") :
            //   l'injection CSS hide cachait les logos OAuth. On la DÉSACTIVE
            //   complètement (= les logos restent visibles) — la souris
            //   virtuelle (ci-dessous, dispatchKeyEvent) permet de cliquer
            //   sur n'importe quel bouton OAuth.
            //   L'ancien CSS reste défini en bas du fichier au cas où (gardé
            //   pour fallback), juste plus appelé.
            // 2026-06-18 v5 (user "quand j'arrive sur la page je suis déjà
            //   connecté Je fais retour et ça fait rien") : pour TF1, tente
            //   la capture JWT AUTO-SILENCIEUSEMENT à chaque page chargée.
            //   Si user déjà loggé → JWT capturé sans clic. Sinon le JS échoue
            //   silencieusement (= ne ferme PAS l'activity, user finit son login).
            if (service == SERVICE_TF1 && !captured && url != null
                && (url.contains("tf1.fr") && !url.contains("accounts.google") &&
                    !url.contains("facebook.com") && !url.contains("apple.com"))) {
                Log.d(TAG, "TF1 onPageFinished → tentative auto-capture JWT silencieuse")
                view?.evaluateJavascript(JS_CAPTURE_TF1_JWT_SILENT, null)
            }
        }
    }

    /** 2026-06-19 : CSS qui masque les boutons OAuth Gigya (Google/Apple/
     *  Facebook) sur les pages de login TF1+ et M6+ pour les users TV qui
     *  n'ont pas de souris. Force le login par email/mot de passe qui est
     *  navigable au D-pad. Inject silencieusement à chaque onPageFinished. */
    private val JS_HIDE_OAUTH_BUTTONS_FOR_TV = """
(() => {
  try {
    if (window.__onyxTvLoginInjected) return;
    window.__onyxTvLoginInjected = true;
    const css = `
      /* Gigya social login buttons + icones OAuth génériques */
      .gigya-composite-control-social-login,
      .gigya-login-providers-container,
      .gigya-social-login-area,
      .gigya-social-login,
      [data-screenset-element-id*="social"],
      [data-bound-component*="SocialLogin"],
      [data-testid*="social"],
      [aria-label*="Google"],
      [aria-label*="Apple"],
      [aria-label*="Facebook"],
      button[class*="oauth"],
      button[class*="social"],
      button[class*="google"],
      button[class*="apple"],
      button[class*="facebook"] {
        display: none !important;
        visibility: hidden !important;
        height: 0 !important;
        overflow: hidden !important;
      }
      /* Agrandir email + password inputs pour faciliter le focus D-pad */
      input[type="email"], input[type="password"], input[type="text"] {
        font-size: 18px !important;
        padding: 14px !important;
        min-height: 44px !important;
      }
      /* Le bouton submit doit être bien visible et focusable */
      button[type="submit"], input[type="submit"] {
        font-size: 18px !important;
        padding: 14px 24px !important;
        min-height: 48px !important;
      }
      /* Outline focus visible pour D-pad navigation */
      *:focus {
        outline: 3px solid #FFD700 !important;
        outline-offset: 2px !important;
      }
    `;
    const style = document.createElement('style');
    style.textContent = css;
    (document.head || document.documentElement).appendChild(style);
    console.log('[OnyxTV] OAuth buttons hidden, email/password login forced');
  } catch (e) { console.log('[OnyxTV] inject err: ' + e.message); }
})();
"""

    /** Inspecte les cookies posés pour le domaine actuel. Si on détecte
     *  les cookies de session attendus → on persiste le token + on ferme. */
    private fun checkCookiesForToken(currentUrl: String?) {
        if (captured || currentUrl == null) return
        // 2026-06-19 v41 (user "on clique à peine sur Google que ça se ferme") :
        //   ne capte le cookie QUE si on est revenu sur le site M6/TF1 (= pas
        //   pendant la popup OAuth Google/Facebook/Apple qui redirige plein
        //   de fois et fait apparaître des cookies stale).
        val isOnTargetSite = when (service) {
            SERVICE_M6 -> currentUrl.contains("6play.fr") || currentUrl.contains("m6.fr")
            SERVICE_TF1 -> currentUrl.contains("tf1.fr")
            else -> false
        }
        if (!isOnTargetSite) {
            Log.d(TAG, "checkCookiesForToken skip — URL on third-party OAuth: ${currentUrl.take(80)}")
            return
        }
        val cookieStr = CookieManager.getInstance().getCookie(currentUrl) ?: return
        val cookies = cookieStr.split(";")
            .map { it.trim() }
            .mapNotNull {
                val idx = it.indexOf("=")
                if (idx > 0) it.substring(0, idx) to it.substring(idx + 1) else null
            }
            .toMap()

        when (service) {
            SERVICE_M6 -> captureM6(cookies)
            SERVICE_TF1 -> captureTF1(cookies)
        }
    }

    /**
     * Cookies M6 6play après login Gigya :
     *   - `gigya-mid_<apikey>`      = identifiant Gigya
     *   - `glt_<apikey>`            = login token Gigya (= le bearer)
     *   - `gltexp_<apikey>`         = expiration login token
     *   - `gig_canary`              = canary
     *
     * On cherche un cookie nom = `glt_<apikey>` (préfixe `glt_`).
     */
    private fun captureM6(cookies: Map<String, String>) {
        // 2026-06-19 v40 (user "ta reconnexion ne fonctionne pas") : élargit
        //   la recherche cookies sur les 3 domaines M6+ (= les cookies Gigya
        //   peuvent être posés sur 6play.fr, m6.fr, login.6play.fr selon les
        //   redirects post-login). On merge tout dans une seule map.
        val mergedCookies = cookies.toMutableMap()
        try {
            val cm = CookieManager.getInstance()
            for (domain in listOf(
                "https://www.6play.fr/", "https://6play.fr/",
                "https://www.m6.fr/", "https://m6.fr/",
                "https://login.6play.fr/", "https://accounts.6play.fr/",
                "https://login-gigya.m6.fr/", "https://compte.m6.fr/",
            )) {
                val c = cm.getCookie(domain) ?: continue
                c.split(";").forEach { kv ->
                    val parts = kv.trim().split("=", limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank()) {
                        mergedCookies.putIfAbsent(parts[0], parts[1])
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read multi-domain cookies: ${t.message}")
        }
        Log.d(TAG, "M6 captureM6: ${mergedCookies.size} unique cookies across all domains")
        @Suppress("NAME_SHADOWING") val cookies = mergedCookies
        val gltEntry = cookies.entries.firstOrNull { it.key.startsWith("glt_") }
        val loginToken = gltEntry?.value
        if (loginToken.isNullOrBlank()) {
            Log.d(TAG, "M6 captureM6: no glt_ cookie yet (probably login not finished)")
            return
        }
        // 2026-06-19 (user "M6 se connecte mal" + log montre WebView fermée
        //   1 seconde après ouverture sans saisie user, token "capturé" mais
        //   ABORT au resolveLive) : le cookie glt_X seul ne suffit PAS — il
        //   peut être un token visiteur résiduel d'un précédent essai foiré.
        //   Le SEUL signe fiable d'une vraie connexion Gigya = présence du
        //   cookie `gigya-mid_<apiKey>` (= UID Gigya unique au compte user).
        //   Sans gigya-mid → on N'ARRÊTE PAS la WebView, on attend que
        //   l'user saisisse vraiment ses identifiants.
        val gigyaMid = cookies.entries.firstOrNull { it.key.startsWith("gigya-mid_") }?.value
        if (gigyaMid.isNullOrBlank()) {
            Log.d(TAG, "M6 captureM6: glt_ present but gigya-mid missing — WAIT for real login (not finishing)")
            return
        }
        val exp = cookies.entries.firstOrNull { it.key.startsWith("gltexp_") }?.value?.toLongOrNull()
        captured = true
        M6Auth.saveToken(this, loginToken, refresh = null, exp = exp)
        val apiKeyFromCookie = gltEntry.key.removePrefix("glt_")
        if (apiKeyFromCookie.isNotBlank()) {
            M6Auth.saveApiKey(this, apiKeyFromCookie)
            Log.d(TAG, "M6 apiKey saved from cookie (len=${apiKeyFromCookie.length})")
        }
        M6Auth.saveAccountId(this, gigyaMid)
        Log.d(TAG, "M6 gigya-mid captured as account_id (len=${gigyaMid.length})")
        Toast.makeText(this, "✓ Connecté à M6 6play", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "M6 token captured (exp=$exp)")
        setResult(RESULT_OK)
        finish()
    }

    /**
     * 2026-06-18 v9 : dès qu'on détecte la session Gigya posée (cookie
     * `glt_<apiKey>` présent), on FORCE la WebView à charger une page
     * vidéo TF1+. Sur cette page, le SPA va automatiquement fetch
     * `mediainfo.tf1.fr` avec `Authorization: Bearer <JWT>` → notre hook
     * dans JS_CAPTURE_TF1_JWT_SILENT capture le token.
     * Le `gltNavTriggered` empêche les re-navigations en boucle. */
    @Volatile private var gltNavTriggered: Boolean = false
    private fun captureTF1(cookies: Map<String, String>) {
        if (gltNavTriggered || captured) return
        val gltCookie = cookies.entries.firstOrNull { it.key.startsWith("glt_") }
        if (gltCookie != null && gltCookie.value.isNotBlank()) {
            gltNavTriggered = true
            Log.d(TAG, "TF1 cookie ${gltCookie.key} présent → navigation vers page vidéo pour déclencher mediainfo+hook")
            // Page TF1+ "Good American Family" (= série stable, page video sûre)
            // SPA TF1 va appeler mediainfo.tf1.fr avec Bearer dès chargement player
            runOnUiThread {
                webView.loadUrl("https://www.tf1.fr/tf1/good-american-family/videos")
            }
        }
    }

    /** Déclenchée par le bouton "✓ Terminer connexion" pour TF1. */
    private fun triggerTf1JwtCapture() {
        if (captured) return
        captured = true
        Log.d(TAG, "User a cliqué 'Terminer connexion' → injection JS")
        Toast.makeText(this, "Récupération du token TF1+…", Toast.LENGTH_SHORT).show()
        webView.evaluateJavascript(JS_CAPTURE_TF1_JWT, null)
    }

    /** Interface JS → Java pour recevoir le JWT capté côté browser.
     *  v5 : 2 callbacks distincts :
     *    - onJwt(token, error) : depuis bouton "Terminer connexion" → ferme
     *      l'activity dans tous les cas
     *    - onJwtSilent(token) : depuis auto-capture onPageFinished → ferme
     *      l'activity SEULEMENT si token (= user pas encore loggé = silent fail) */
    inner class TF1JwtBridge {
        @android.webkit.JavascriptInterface
        fun onJwt(token: String?, error: String?) {
            runOnUiThread {
                if (token.isNullOrBlank()) {
                    Log.e(TAG, "JWT capture failed (button): $error")
                    Toast.makeText(this@LoginWebViewActivity,
                        "Échec : $error",
                        Toast.LENGTH_LONG).show()
                    setResult(RESULT_CANCELED)
                } else {
                    saveJwtAndFinish(token)
                }
                if (token.isNullOrBlank()) finish()
            }
        }

        @android.webkit.JavascriptInterface
        fun onJwtSilent(token: String?) {
            if (token.isNullOrBlank()) {
                Log.d(TAG, "Auto-capture silent fail (user pas encore loggé)")
                return  // = on attend que user fasse son login
            }
            runOnUiThread { saveJwtAndFinish(token) }
        }
    }

    private fun saveJwtAndFinish(token: String) {
        // 2026-06-18 v18 : parse exp depuis JWT payload pour permettre refresh
        //   automatique avant expiration (= TF1JwtRefresher.needsRefresh()).
        val expSec = com.streamflixreborn.streamflix.utils.TF1JwtRefresher.parseJwtExp(token)
        TF1Auth.saveToken(this, token, refresh = null, exp = expSec)
        Toast.makeText(this,
            "✓ Connecté à TF1+ (JWT ${token.length} chars)",
            Toast.LENGTH_SHORT).show()
        Log.i(TAG, "JWT TF1+ saved (len=${token.length}, exp=$expSec)")
        setResult(RESULT_OK)
        finish()
    }
}

/** 2026-06-19 v42 (user "Impossible de vous connecter" sur OAuth Google) :
 *  Google's allow_browser_login API check le JS fingerprint pour détecter
 *  les WebView. On override les indicateurs principaux pour faire passer
 *  notre WebView pour un vrai Chrome. Inject sur CHAQUE pageStart. */
private const val JS_HIDE_WEBVIEW_FINGERPRINT = """
(() => {
  try {
    // 1. navigator.webdriver doit être undefined (true dans WebView/automation)
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined, configurable: true });
    // 2. window.chrome doit avoir runtime + app (= signature Chrome desktop)
    if (!window.chrome || !window.chrome.runtime) {
      window.chrome = {
        runtime: {},
        app: {
          isInstalled: false,
          InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
          RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
        },
        csi: () => {},
        loadTimes: () => {}
      };
    }
    // 3. navigator.plugins doit avoir des entrées (WebView en a 0)
    Object.defineProperty(navigator, 'plugins', {
      get: () => [{ name: 'Chrome PDF Plugin' }, { name: 'Chrome PDF Viewer' }, { name: 'Native Client' }],
      configurable: true
    });
    // 4. navigator.languages doit être un array (= souvent vide dans WebView)
    Object.defineProperty(navigator, 'languages', {
      get: () => ['fr-FR', 'fr', 'en-US', 'en'],
      configurable: true
    });
    // 5. window.outerHeight/outerWidth - WebView les met à 0 souvent
    if (!window.outerWidth) Object.defineProperty(window, 'outerWidth', { get: () => 1920 });
    if (!window.outerHeight) Object.defineProperty(window, 'outerHeight', { get: () => 1080 });
    // 6. Notification permission par défaut (= "denied" sur WebView, "default" sur Chrome)
    if (window.Notification && Notification.permission === 'denied') {
      Object.defineProperty(Notification, 'permission', { get: () => 'default' });
    }
    console.log('[OnyxBypass] WebView fingerprint hidden');
  } catch (e) {
    console.log('[OnyxBypass] err: ' + e.message);
  }
})();
"""

// 2026-06-19 v41 (user "Connexion impossible veuillez réessayer plus tard" sur
//   le bouton Google + screenshot confirme : sur Chrome desktop ça marche
//   direct) : Google bloque l'OAuth dans les WebView Android (= politique de
//   sécurité depuis 2021 — "Disallow embedded WebViews from accessing Google
//   sign-in"). Détection via navigator.userAgent qui contient "Android" ou
//   "wv" (= WebView marker). Solution : utiliser un UA DESKTOP (Linux/Mac/Win
//   Chrome) qui n'a aucun signal Android/WebView → Google nous prend pour un
//   navigateur normal et autorise le login. Trade-off : le site M6 voit aussi
//   un desktop client (= layout web normal, pas mobile/responsive), c'est OK.
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

/** 2026-06-18 v9 : 3 approches en parallèle :
 *  (1) Hook `window.fetch` global pour intercepter le Bearer envoyé par
 *      le SPA TF1 lui-même à mediainfo.tf1.fr (= le SPA détient déjà le
 *      JWT et l'envoie en clair).
 *  (2) Appel direct `window.gigya.accounts.getJWT()` (= SDK Gigya
 *      officiel chargé par tf1.fr, a les bonnes permissions). Mon
 *      fetch direct rate avec 403007 car il contourne le SDK.
 *  (3) Scan localStorage / sessionStorage / cookies STRICT 3-parties.
 *  Toutes les tentatives logguent leur résultat en console pour debug. */
private const val JS_CAPTURE_TF1_JWT_SILENT = """
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
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          uid: uid,
          signature: signature,
          timestamp: parseInt(timestamp),
          consent_ids: ['1','2','3','4','10001','10003','10005','10007','10013','10015','10017','10019','10009','10011','13002','13001','10004','10014','10016','10018','10020','10010','10012','10006','10008']
        })
      });
      const ej = await ex.json();
      console.log('[TF1JWT] trade UID resp:' + JSON.stringify(ej).substring(0, 200));
      if (ej && ej.token && isJwt(ej.token)) return ej.token;
    } catch (e) { console.log('[TF1JWT] trade UID err:' + e.message); }
    return null;
  };

  // === APPROCHE 1 : Hook window.fetch global pour intercepter Bearer
  if (!window.__onyx_fetchHookInstalled) {
    window.__onyx_fetchHookInstalled = true;
    const origFetch = window.fetch.bind(window);
    window.fetch = async function(input, init) {
      let __mediainfoUrl = '';
      let __mediainfoAuth = '';
      try {
        const url = (typeof input === 'string') ? input : (input && input.url) || '';
        const hdrs = (init && init.headers) || (typeof input === 'object' ? input.headers : null);
        if (url.includes('mediainfo.tf1.fr')) {
          __mediainfoUrl = url;
          // 2026-06-18 v16 : log TOUS les headers du SPA pour reproduire en OkHttp
          let allHeaders = {};
          if (hdrs instanceof Headers) { hdrs.forEach((v, k) => allHeaders[k] = v); }
          else if (typeof hdrs === 'object') { allHeaders = Object.assign({}, hdrs); }
          console.log('[TF1JWT] SPA mediainfo URL: ' + url);
          console.log('[TF1JWT] SPA mediainfo headers: ' + JSON.stringify(allHeaders));
          let auth = '';
          if (hdrs instanceof Headers) auth = hdrs.get('Authorization') || hdrs.get('authorization') || '';
          else if (typeof hdrs === 'object') auth = hdrs.Authorization || hdrs.authorization || '';
          if (auth && auth.toLowerCase().startsWith('bearer ')) {
            __mediainfoAuth = auth;
            const tok = auth.substring(7).trim();
            if (isJwt(tok)) {
              console.log('[TF1JWT] HOOK mediainfo Bearer captured len=' + tok.length);
              try { OnyxBridge.onJwtSilent(tok); } catch (e) {}
            }
          }
        }
      } catch (e) {}
      const resp = await origFetch(input, init);
      // Log SPA response body to compare with our OkHttp response
      if (__mediainfoUrl) {
        try {
          const clone = resp.clone();
          const body = await clone.text();
          console.log('[TF1JWT] SPA mediainfo RESP status=' + resp.status + ' body=' + body.substring(0, 1500));
        } catch (e) { console.log('[TF1JWT] SPA mediainfo RESP read err:' + e.message); }
      }
      return resp;
    };
    // Hook aussi XHR au cas où
    const origOpen = XMLHttpRequest.prototype.open;
    const origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.open = function(method, url) {
      this.__onyx_url = url;
      return origOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
      try {
        if (name && name.toLowerCase() === 'authorization' &&
            this.__onyx_url && String(this.__onyx_url).includes('mediainfo.tf1.fr') &&
            value && value.toLowerCase().startsWith('bearer ')) {
          const tok = value.substring(7).trim();
          if (isJwt(tok)) {
            console.log('[TF1JWT] HOOK XHR mediainfo Bearer captured len=' + tok.length);
            try { OnyxBridge.onJwtSilent(tok); } catch (e) {}
          }
        }
      } catch (e) {}
      return origSetHeader.apply(this, arguments);
    };
    console.log('[TF1JWT] fetch+XHR hooks installed');
  }

  // === APPROCHE 2 : SDK Gigya officiel — pipeline exact Catchup TV & More.
  //   AWAITed pour éviter que l'IIFE sorte avant le callback async.
  const trySdkGigya = async () => {
    if (!window.gigya || !window.gigya.accounts) {
      console.log('[TF1JWT] gigya SDK NOT available (window.gigya=' + !!window.gigya + ')');
      return null;
    }
    console.log('[TF1JWT] gigya SDK available → getAccountInfo');
    // Wrap getAccountInfo dans Promise pour await
    const resp = await new Promise((resolve) => {
      try {
        window.gigya.accounts.getAccountInfo({
          include: 'profile,emails',
          callback: resolve
        });
      } catch (e) { resolve({ errorCode: -1, errorMessage: e.message }); }
    });
    console.log('[TF1JWT] gigya.getAccountInfo cb errorCode=' + resp.errorCode + ' UID=' + (resp.UID ? resp.UID.substring(0, 8) + '...' : 'none'));
    if (resp.errorCode === 0 && resp.UID && resp.UIDSignature && resp.signatureTimestamp) {
      const tf1Jwt = await tradeWithUid(resp.UID, resp.UIDSignature, resp.signatureTimestamp);
      if (tf1Jwt) {
        console.log('[TF1JWT] FINAL via getAccountInfo+trade len=' + tf1Jwt.length);
        return tf1Jwt;
      }
    }
    // Fallback : session.verify
    console.log('[TF1JWT] getAccountInfo insufficient → try session.verify');
    const r2 = await new Promise((resolve) => {
      try {
        if (window.gigya.accounts.session && window.gigya.accounts.session.verify) {
          window.gigya.accounts.session.verify({ callback: resolve });
        } else { resolve({ errorCode: -1 }); }
      } catch (e) { resolve({ errorCode: -1, errorMessage: e.message }); }
    });
    console.log('[TF1JWT] gigya.session.verify cb errorCode=' + r2.errorCode + ' UID=' + (r2.UID ? r2.UID.substring(0, 8) + '...' : 'none'));
    if (r2.errorCode === 0 && r2.UID && r2.UIDSignature && r2.signatureTimestamp) {
      const tf1Jwt = await tradeWithUid(r2.UID, r2.UIDSignature, r2.signatureTimestamp);
      if (tf1Jwt) {
        console.log('[TF1JWT] FINAL via session.verify+trade len=' + tf1Jwt.length);
        return tf1Jwt;
      }
    }
    return null;
  };
  try {
    const tf1Jwt = await trySdkGigya();
    if (tf1Jwt) {
      // 2026-06-18 v15 : TEST mediainfo DEPUIS la WebView (= même origine
      //   www.tf1.fr, cookies + headers envoyés par browser). Si CE call
      //   marche → on sait que c'est les cookies / origine qui font la diff
      //   vs notre OkHttp call.
      try {
        const testUrl = 'https://mediainfo.tf1.fr/mediainfocombo/26259212?context=MYTF1&pver=5029000';
        const mi = await fetch(testUrl, {
          method: 'GET',
          credentials: 'include',
          headers: { 'Authorization': 'Bearer ' + tf1Jwt }
        });
        const miBody = await mi.text();
        console.log('[TF1JWT] mediainfo TEST status=' + mi.status + ' body=' + miBody.substring(0, 800));
      } catch (e) { console.log('[TF1JWT] mediainfo TEST err:' + e.message); }
      OnyxBridge.onJwtSilent(tf1Jwt);
      return;
    }
  } catch (e) { console.log('[TF1JWT] gigya SDK err:' + e.message); }

  // === APPROCHE 3 : scan localStorage / sessionStorage / cookies STRICT
  try {
    const deepFindJwt = (obj, depth) => {
      if (!obj || depth > 5) return null;
      if (typeof obj === 'string' && isJwt(obj)) return obj;
      if (typeof obj !== 'object') return null;
      for (const key of Object.keys(obj)) {
        try {
          const v = obj[key];
          if (typeof v === 'string' && isJwt(v)) return v;
          if (typeof v === 'object') {
            const r = deepFindJwt(v, depth + 1);
            if (r) return r;
          }
        } catch (e) {}
      }
      return null;
    };
    for (const k of Object.keys(localStorage)) {
      const v = localStorage.getItem(k);
      if (isJwt(v)) { console.log('[TF1JWT] localStorage hit:' + k); OnyxBridge.onJwtSilent(v); return; }
      if (v && v.length > 50 && (v.startsWith('{') || v.startsWith('['))) {
        try {
          const found = deepFindJwt(JSON.parse(v), 0);
          if (found) { console.log('[TF1JWT] localStorage deep hit:' + k); OnyxBridge.onJwtSilent(found); return; }
        } catch (e) {}
      }
    }
    for (const k of Object.keys(sessionStorage)) {
      const v = sessionStorage.getItem(k);
      if (isJwt(v)) { console.log('[TF1JWT] sessionStorage hit:' + k); OnyxBridge.onJwtSilent(v); return; }
      if (v && v.length > 50 && (v.startsWith('{') || v.startsWith('['))) {
        try {
          const found = deepFindJwt(JSON.parse(v), 0);
          if (found) { console.log('[TF1JWT] sessionStorage deep hit:' + k); OnyxBridge.onJwtSilent(found); return; }
        } catch (e) {}
      }
    }
  } catch (e) { console.log('[TF1JWT] scan err:' + e.message); }

  // === APPROCHE 4 : forcer la navigation vers une page vidéo pour
  //   déclencher le SPA TF1 qui appellera mediainfo → notre hook captera
  if (location.host.includes('tf1.fr') && !location.pathname.includes('/videos/')) {
    console.log('[TF1JWT] no JWT found → navigate to trigger mediainfo fetch');
    // On ne navigue PAS automatiquement (= risque de boucle), juste log
  }
})();
"""

/** Ancien JS_CAPTURE_TF1_JWT_SILENT (API Gigya — gardé en commentaire pour ref). */
private const val JS_CAPTURE_TF1_JWT_SILENT_OLD = """
(async () => {
  try {
    const API_KEY = '3_hWgJdARhz_7l1oOp3a8BDLoR9cuWZpUaKG4aqF7gum9_iK3uTZ2VlDBl8ANf8FVk';
    // 2026-06-18 v6 : getAccountInfo retournait "Permission denied" pour
    //   les scopes profile/emails. accounts.session.verify retourne juste
    //   UID + signature + timestamp = ce qu'il nous faut pour token/gigya/web.
    let j1 = null;
    // Tentative 1 : accounts.session.verify (= plus permissif)
    try {
      const r0 = await fetch(
        'https://compte.tf1.fr/accounts.session.verify?apiKey=' + API_KEY + '&format=json',
        { method: 'POST', credentials: 'include' }
      );
      j1 = await r0.json();
    } catch (e) {}
    // Tentative 2 (fallback) : getAccountInfo MINIMAL (sans include)
    if (!j1 || j1.errorCode !== 0 || !j1.UID) {
      const r1 = await fetch(
        'https://compte.tf1.fr/accounts.getAccountInfo?apiKey=' + API_KEY + '&format=json',
        { method: 'POST', credentials: 'include' }
      );
      j1 = await r1.json();
    }
    if (j1.errorCode !== 0 || !j1.UID) return;  // silent : user pas loggé
    const consentIds = ['1','2','3','4','10001','10003','10005','10007','10013',
      '10015','10017','10019','10009','10011','13002','13001','10004','10014',
      '10016','10018','10020','10010','10012','10006','10008'];
    const r2 = await fetch('https://www.tf1.fr/token/gigya/web', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        uid: j1.UID, signature: j1.UIDSignature,
        timestamp: parseInt(j1.signatureTimestamp),
        consent_ids: consentIds
      })
    });
    const j2 = await r2.json();
    if (j2.token) OnyxBridge.onJwtSilent(j2.token);
  } catch (e) { /* silent */ }
})();
"""

/** 2026-06-18 v10 : pipeline via SDK Gigya officiel (= window.gigya).
 *  Mon fetch direct sur compte.tf1.fr/accounts.* renvoyait 403007 Permission
 *  denied car il contourne le SDK. Le SDK chargé par tf1.fr a les bonnes
 *  permissions. On AWAIT le callback pour éviter sortie prématurée. */
private const val JS_CAPTURE_TF1_JWT = """
(async () => {
  try {
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
            consent_ids: ['mesure_audience','publicite_personnalisee','partage_donnees_partenaires','reseaux_sociaux','personnalisation_contenu']
          })
        });
        const ej = await ex.json();
        console.log('[TF1JWT-btn] trade resp:' + JSON.stringify(ej).substring(0, 200));
        if (ej && ej.token && isJwt(ej.token)) return ej.token;
      } catch (e) { console.log('[TF1JWT-btn] trade err:' + e.message); }
      return null;
    };
    if (!window.gigya || !window.gigya.accounts) {
      OnyxBridge.onJwt(null, 'Gigya SDK not loaded — try reload');
      return;
    }
    // session.verify d'abord (plus permissif), puis getAccountInfo
    const r1 = await new Promise((resolve) => {
      try {
        if (window.gigya.accounts.session && window.gigya.accounts.session.verify) {
          window.gigya.accounts.session.verify({ callback: resolve });
        } else { resolve({ errorCode: -1 }); }
      } catch (e) { resolve({ errorCode: -1, errorMessage: e.message }); }
    });
    console.log('[TF1JWT-btn] session.verify cb errorCode=' + r1.errorCode);
    let UID = r1.UID, UIDSignature = r1.UIDSignature, signatureTimestamp = r1.signatureTimestamp;
    if (r1.errorCode !== 0 || !UID) {
      const r2 = await new Promise((resolve) => {
        try {
          window.gigya.accounts.getAccountInfo({ include: 'profile,emails', callback: resolve });
        } catch (e) { resolve({ errorCode: -1, errorMessage: e.message }); }
      });
      console.log('[TF1JWT-btn] getAccountInfo cb errorCode=' + r2.errorCode);
      if (r2.errorCode === 0 && r2.UID) {
        UID = r2.UID; UIDSignature = r2.UIDSignature; signatureTimestamp = r2.signatureTimestamp;
      }
    }
    if (!UID || !UIDSignature || !signatureTimestamp) {
      OnyxBridge.onJwt(null, 'Aucune méthode SDK gigya n’a fourni UID/Signature');
      return;
    }
    const tf1Jwt = await tradeWithUid(UID, UIDSignature, signatureTimestamp);
    if (tf1Jwt) {
      console.log('[TF1JWT-btn] FINAL len=' + tf1Jwt.length);
      OnyxBridge.onJwt(tf1Jwt, null);
    } else {
      OnyxBridge.onJwt(null, '/token/gigya/web n’a pas renvoyé de JWT');
    }
  } catch (e) {
    OnyxBridge.onJwt(null, 'JS error: ' + e.message);
  }
})();
"""
