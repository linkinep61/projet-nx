package com.streamflixreborn.streamflix.activities.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences

class BypassWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_COOKIE_HEADER = "extra_cookie_header"

        /** 2026-09-10 (user : « quand il arrive sur la page web pour créer le compte, ça le
         *  met automatiquement au bon endroit ») : identifiants SAISIS PAR L'UTILISATEUR,
         *  recopiés dans le formulaire de la page. L'app ne les invente pas, ne valide pas
         *  le formulaire et ne touche pas au controle anti-robot : c'est du remplissage,
         *  comme le ferait un gestionnaire de mots de passe. */
        const val EXTRA_PREREMPLIR_USER = "extra_preremplir_user"
        const val EXTRA_PREREMPLIR_PASS = "extra_preremplir_pass"
        private const val COOKIE_POLL_INTERVAL_MS = 1000L
    }

    private lateinit var webView: WebView

    /** 2026-09-10 (user : « on a oublié de mettre une souris virtuelle pour valider le
     *  captcha ») : sur TV, une case Cloudflare n'est pas focusable et reste donc
     *  inatteignable au D-pad. Ce pointeur permet d'aller la cocher soi-même. Null hors TV. */
    private var curseurTv: com.streamflixreborn.streamflix.utils.TvWebCursor? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var continueButton: Button
    private lateinit var cancelButton: Button
    private var isCleaningUp = false
    private var currentPageUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (isCleaningUp || isDestroyed || isFinishing) return
            updateBypassState(currentPageUrl)
            mainHandler.postDelayed(this, COOKIE_POLL_INTERVAL_MS)
        }
    }

    private val targetUrl: String by lazy {
        intent.getStringExtra(EXTRA_URL).orEmpty()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.mobileThemeRes(UserPreferences.selectedTheme))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bypass_webview)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        webView = findViewById(R.id.bypass_webview)
        curseurTv = com.streamflixreborn.streamflix.utils.TvWebCursor.attacher(this, webView)
        progressBar = findViewById(R.id.bypass_progress)
        statusView = findViewById(R.id.bypass_status)
        continueButton = findViewById(R.id.bypass_continue)
        cancelButton = findViewById(R.id.bypass_cancel)

        cancelButton.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        continueButton.setOnClickListener {
            val cookies = collectCookieHeader()
            if (cookies.isBlank()) {
                Toast.makeText(
                    this,
                    getString(R.string.bypass_status_complete_bypass_first),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_COOKIE_HEADER, cookies)
            )
            finish()
        }

        setupWebView()

        if (targetUrl.isBlank()) {
            Toast.makeText(this, getString(R.string.bypass_status_missing_url), Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.loadUrl(targetUrl)
        mainHandler.post(cookiePollRunnable)
    }

    /**
     * Recopie l'identifiant et le mot de passe choisis par l'utilisateur dans les champs
     * du formulaire, s'il y en a. Ne soumet rien : l'utilisateur coche le controle et
     * valide lui-meme. Les selecteurs sont volontairement generiques — on ne connait pas
     * la structure exacte de la page et elle peut changer.
     */
    private fun preremplirSiDemande() {
        val u = intent.getStringExtra(EXTRA_PREREMPLIR_USER)?.takeIf { it.isNotBlank() } ?: return
        val p = intent.getStringExtra(EXTRA_PREREMPLIR_PASS).orEmpty()
        // Passage par JSON : un mot de passe peut contenir guillemets, antislash ou accents.
        val uJs = org.json.JSONObject.quote(u)
        val pJs = org.json.JSONObject.quote(p)
        val js = """
            (function() {
              try {
                function poser(el, v) {
                  if (!el || !v) return false;
                  var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                  setter.call(el, v);
                  el.dispatchEvent(new Event('input', { bubbles: true }));
                  el.dispatchEvent(new Event('change', { bubbles: true }));
                  return true;
                }
                var mdp = document.querySelector('input[type=password]');
                var ident = document.querySelector(
                  'input[autocomplete=username], input[name=username], input[name=user], input[id*=user]'
                );
                if (!ident) {
                  var champs = Array.prototype.slice.call(document.querySelectorAll('input'));
                  var i = champs.indexOf(mdp);
                  for (var k = (i > 0 ? i - 1 : 0); k >= 0; k--) {
                    var t = (champs[k].type || 'text').toLowerCase();
                    if (t === 'text' || t === 'email') { ident = champs[k]; break; }
                  }
                }
                var a = poser(ident, $uJs);
                var b = poser(mdp, $pJs);
                return 'identifiant=' + a + ' motdepasse=' + b;
              } catch (e) { return 'ERREUR ' + e.message; }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { r -> android.util.Log.d("BypassWebView", "pre-remplissage : $r") }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (curseurTv?.onKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        isCleaningUp = true
        mainHandler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            runCatching { webView.stopLoading() }
            runCatching { webView.webChromeClient = WebChromeClient() }
            runCatching { webView.webViewClient = WebViewClient() }
            runCatching { webView.destroy() }
        }
        super.onDestroy()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = NetworkClient.USER_AGENT
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 0..99) View.VISIBLE else View.GONE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                if (isCleaningUp) return true

                val url = request?.url?.toString().orEmpty()
                val isMainFrame = request?.isForMainFrame ?: true
                if (!isMainFrame || url.isBlank()) return false

                if (isAllowedBypassHost(url)) {
                    currentPageUrl = url
                    return false
                }

                val cookies = collectCookieHeader()
                if (cookies.isNotBlank()) {
                    continueButton.isEnabled = true
                    statusView.text = getString(R.string.bypass_status_completed_continue)
                } else {
                    statusView.text = getString(R.string.bypass_status_external_redirect_blocked)
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (isCleaningUp) return
                progressBar.visibility = View.VISIBLE
                currentPageUrl = url
                updateBypassState(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                preremplirSiDemande()
                if (isCleaningUp) return
                currentPageUrl = url
                updateBypassState(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                currentPageUrl = request?.url?.toString()
                updateBypassState(request?.url?.toString())
            }
        }
    }

    private fun updateBypassState(currentUrl: String?) {
        if (isCleaningUp) return
        val cookies = collectCookieHeader()
        val hasClearance = cookies.contains("cf_clearance")
        continueButton.isEnabled = cookies.isNotBlank()
        statusView.text = when {
            hasClearance -> getString(R.string.bypass_status_completed_continue)
            cookies.isNotBlank() -> getString(R.string.bypass_status_cookies_detected)
            else -> getString(R.string.bypass_status_complete_in_page)
        }

        if (!currentUrl.isNullOrBlank()) {
            title = Uri.parse(currentUrl).host ?: getString(R.string.app_name)
        }
    }

    private fun collectCookieHeader(): String {
        if (isCleaningUp) return ""

        val cookieManager = CookieManager.getInstance()
        val candidates = linkedSetOf<String>()
        val currentUrl = currentPageUrl

        if (!currentUrl.isNullOrBlank()) {
            candidates += currentUrl
        }
        if (targetUrl.isNotBlank()) {
            candidates += targetUrl
        }

        val host = runCatching { Uri.parse(currentUrl ?: targetUrl).host.orEmpty() }.getOrDefault("")
        if (host.isNotBlank()) {
            candidates += "https://$host/"
            candidates += "http://$host/"
        }

        return candidates
            .mapNotNull { candidate -> cookieManager.getCookie(candidate)?.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun isAllowedBypassHost(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        return host.equals("s.to", ignoreCase = true) ||
            host.equals("challenges.cloudflare.com", ignoreCase = true) ||
            host.endsWith("flemmix.farm", ignoreCase = true) ||
            host.endsWith("flemmix.upns.pro", ignoreCase = true) ||
            host.endsWith("wiflix.zone", ignoreCase = true) ||
            host.endsWith("wiflix.dev", ignoreCase = true) ||
            host.endsWith("wiflix.fun", ignoreCase = true) ||
            host.endsWith("flemmix.golf", ignoreCase = true) ||
            host.endsWith("flemmix.irish", ignoreCase = true) ||
            host.endsWith("flemmix.town", ignoreCase = true) ||
            host.endsWith("flemmix.vip", ignoreCase = true) ||
            host.endsWith("wiflix.red", ignoreCase = true) ||
            host.endsWith("wiflix.re", ignoreCase = true)
    }
}
