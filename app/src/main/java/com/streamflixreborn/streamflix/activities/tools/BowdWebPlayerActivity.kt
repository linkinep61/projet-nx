package com.streamflixreborn.streamflix.activities.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences

/**
 * 2026-09-10 — Lecteur WebView Bowd (bowdtv.com).
 *
 * Pourquoi une WebView et pas ExoPlayer (mesuré, cf. l'en-tête de [com.streamflixreborn.streamflix.utils.BowdTv]) :
 * l'URL HLS de Bowd est signée par session et leur endpoint /stream répond 403
 * ACCOUNT_REQUIRED en anonyme. On charge donc leur propre page player, où
 * l'utilisateur se connecte UNE FOIS chez eux, dans leur formulaire. On ne
 * manipule aucun identifiant, on ne touche pas à leur captcha : c'est leur page.
 *
 * La session vit dans les cookies + le stockage DOM de la WebView, tous deux
 * persistants — d'où `setAcceptThirdPartyCookies` (Cloudflare pose les siens sur
 * challenges.cloudflare.com) et `domStorageEnabled` (better-auth y range sa session).
 * Sans ces deux-là, l'utilisateur devrait se reconnecter à chaque lecture.
 *
 * Volontairement SANS le bricolage NetMirror (UA « OS.Gatu », en-tête X-Requested-With
 * vide, cookies net52/net77) : Bowd n'en a pas besoin et ça ne ferait que le fragiliser.
 */
class BowdWebPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BowdWebPlayer"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"

        /** UA Chrome mobile standard : leur site est du Expo web, il sert le rendu
         *  mobile sur cet UA et le player s'y comporte comme dans Chrome. */
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun launch(context: Context, url: String, title: String? = null) {
            context.startActivity(
                Intent(context, BowdWebPlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }

    private lateinit var webView: WebView

    /** 2026-09-10 (user : « on a oublié de mettre une souris virtuelle pour valider le
     *  captcha ») : sur TV, une case Cloudflare n'est pas focusable, donc inatteignable
     *  au D-pad. Ce pointeur permet à l'utilisateur d'aller la cocher lui-même. Null hors TV. */
    private var curseurTv: com.streamflixreborn.streamflix.utils.TvWebCursor? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var errorOverlay: LinearLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var targetUrl: String = com.streamflixreborn.streamflix.utils.BowdTv.SITE

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.mobileThemeRes(UserPreferences.selectedTheme))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_player)

        webView = findViewById(R.id.webview_player)
        progressBar = findViewById(R.id.webview_progress)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        errorOverlay = findViewById(R.id.error_overlay)
        findViewById<TextView>(R.id.btn_retry).setOnClickListener { charger() }

        targetUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: targetUrl
        intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }

        configurer()
        curseurTv = com.streamflixreborn.streamflix.utils.TvWebCursor.attacher(this, webView)
        charger()
    }

    private fun configurer() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = UA
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            /** Seules les erreurs de la page principale méritent l'écran d'erreur : une
             *  ressource secondaire qui rate (pub, télémétrie) ne doit pas masquer un player
             *  qui joue très bien. */
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    errorOverlay.visibility = View.VISIBLE
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) { callback?.onCustomViewHidden(); return }
                customView = view
                customViewCallback = callback
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                fullscreenContainer.removeAllViews()
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) progressBar.visibility = View.VISIBLE
            }
        }
    }

    private fun charger() {
        errorOverlay.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        android.util.Log.d(TAG, "chargement $targetUrl")
        webView.loadUrl(targetUrl)
    }

    /** 2026-09-10 (user : « quand on fait retour il retourne directement sur notre
     *  application, on se balade pas dans la leur ») : Retour FERME le lecteur au lieu
     *  de reculer dans l'historique de bowdtv. Seule exception, le plein écran vidéo :
     *  le premier Retour en sort, le suivant ferme. On ne rappelle donc JAMAIS goBack(). */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
            return
        }
        finish()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (curseurTv?.onKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        // La session Bowd doit survivre à la fermeture : sinon reconnexion à chaque lecture.
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        runCatching {
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
}
