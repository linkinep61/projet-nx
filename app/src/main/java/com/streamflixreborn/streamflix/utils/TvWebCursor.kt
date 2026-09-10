package com.streamflixreborn.streamflix.utils

import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 2026-09-10 — Souris virtuelle pour WebView sur TV.
 *
 * Reprise du mécanisme éprouvé de [com.streamflixreborn.streamflix.activities.LoginWebViewActivity]
 * (v35/v36), extrait ici pour être réutilisable. Motif d'origine, user : « souris virtuelle
 * pour aller cliquer sur les trucs ». Sans elle, une case Cloudflare ou un bouton non
 * focusable est INATTEIGNABLE à la télécommande — le D-pad ne sait viser que ce que la page
 * déclare focusable, ce qu'un captcha n'est pas.
 *
 * ⚠ Ce composant ne coche rien tout seul : il donne à l'utilisateur un pointeur pour aller
 * cliquer lui-même. C'est un moyen de saisie, pas un automate.
 *
 * Le clic passe par `elementFromPoint(x, y).click()` en JS plutôt que par un MotionEvent
 * synthétique : un événement fabriqué arrive avec `isTrusted=false` côté page et se fait
 * rejeter par les formulaires sérieux (constaté sur OAuth Google). Le clic DOM, lui, est
 * indistinguable d'un vrai. La remontée vers le parent cliquable est indispensable pour les
 * icônes en SVG, où `click` n'existe pas sur la cible directe.
 *
 * [LoginWebViewActivity] garde sa propre copie : elle est éprouvée en production et gère en
 * plus les popups OAuth. Rien n'y a été touché.
 */
class TvWebCursor private constructor(
    private val activity: Activity,
    private val racine: FrameLayout,
    private val webView: WebView,
) {

    companion object {
        private const val TAG = "TvWebCursor"
        private const val TAILLE = 64

        /** Rend null hors TV : sur mobile le doigt suffit, un pointeur gênerait. */
        fun attacher(activity: Activity, webView: WebView): TvWebCursor? {
            val surTv = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            if (!surTv) return null
            val racine = activity.findViewById<FrameLayout>(android.R.id.content) ?: return null
            return TvWebCursor(activity, racine, webView).also { it.init() }
        }
    }

    private var vue: ImageView? = null
    private var x = 0f
    private var y = 0f

    private fun init() {
        val c = ImageView(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x66FFD700.toInt())
                setStroke(4, 0xFF000000.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(TAILLE, TAILLE)
            elevation = 100f
            isClickable = false
            isFocusable = false
        }
        vue = c
        racine.addView(c)
        racine.post {
            x = (racine.width / 2f) - (TAILLE / 2f)
            y = (racine.height / 2f) - (TAILLE / 2f)
            placer()
        }
        Log.d(TAG, "souris virtuelle activée (TV)")
    }

    private fun placer() {
        vue?.translationX = x
        vue?.translationY = y
    }

    /** À appeler depuis `dispatchKeyEvent`. Rend true si la touche a été consommée. */
    fun onKey(event: KeyEvent): Boolean {
        if (vue == null || event.action != KeyEvent.ACTION_DOWN) return false

        // Accélération à l'auto-répétition : viser vite de loin, finement de près.
        val pas = 40f * (1f + minOf(event.repeatCount, 12) * 0.5f)
        val maxX = (racine.width - TAILLE).toFloat().coerceAtLeast(0f)
        val maxY = (racine.height - TAILLE).toFloat().coerceAtLeast(0f)
        val pasScroll = (pas * 3f).toInt().coerceAtLeast(80)
        // Le défilement démarre dès la bande de 200 px, sinon il faut pousser le curseur
        //   jusqu'au bord exact pour faire bouger la page — laborieux à la télécommande.
        val bande = 200f
        val appuiLong = event.repeatCount >= 3

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (appuiLong || y <= bande) webView.scrollBy(0, -pasScroll)
                y = (y - pas).coerceAtLeast(0f); placer(); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (appuiLong || y >= maxY - bande) webView.scrollBy(0, pasScroll)
                y = (y + pas).coerceAtMost(maxY); placer(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (x <= 0.5f) webView.scrollBy(-pasScroll, 0)
                else { x = (x - pas).coerceAtLeast(0f); placer() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (x >= maxX - 0.5f) webView.scrollBy(pasScroll, 0)
                else { x = (x + pas).coerceAtMost(maxX); placer() }
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                webView.scrollBy(0, -(racine.height * 0.8f).toInt().coerceAtLeast(400)); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                webView.scrollBy(0, (racine.height * 0.8f).toInt().coerceAtLeast(400)); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                cliquer(); return true
            }
        }
        return false  // BACK et le reste restent au système
    }

    /** Clic DOM à la position du curseur, avec remontée vers le parent cliquable. */
    private fun cliquer() {
        val cx = x + TAILLE / 2f
        val cy = y + TAILLE / 2f

        // Un bouton Android a la priorité sur la page.
        //   2026-09-10 (user : « l'option annuler ou continuer ne paraît pas focusable ») :
        //   la recherche ne parcourait que les enfants DIRECTS de la racine. Or les boutons
        //   d'un layout sont imbriqués (ici dans le LinearLayout de activity_bypass_webview),
        //   donc ils échappaient au pointeur — et comme le curseur consomme tout le D-pad,
        //   le focus ne pouvait plus les atteindre non plus : ils devenaient inutilisables
        //   sur TV. On descend donc dans TOUT l'arbre.
        boutonSous(racine, cx, cy)?.let { it.performClick(); flash(); return }

        val pos = IntArray(2); webView.getLocationOnScreen(pos)
        val d = activity.resources.displayMetrics.density
        val jsX = (cx - pos[0]) / d
        val jsY = (cy - pos[1]) / d
        val js = """
            (function() {
              try {
                var el = document.elementFromPoint($jsX, $jsY);
                if (!el) return 'RIEN';
                var c = el;
                while (c && c !== document.body && c !== document.documentElement) {
                  var t = c.tagName ? c.tagName.toUpperCase() : '';
                  if (c instanceof HTMLElement && (
                      t === 'A' || t === 'BUTTON' || t === 'INPUT' || t === 'SELECT' ||
                      t === 'LABEL' || t === 'IFRAME' ||
                      c.onclick !== null ||
                      c.getAttribute('role') === 'button' ||
                      c.getAttribute('role') === 'checkbox' ||
                      c.hasAttribute('tabindex') ||
                      window.getComputedStyle(c).cursor === 'pointer')) break;
                  c = c.parentElement;
                }
                if (!c || c === document.body || c === document.documentElement) c = el;
                if (c.focus) { try { c.focus(); } catch (e) {} }
                c.click();
                return c.tagName || 'OK';
              } catch (e) { return 'ERREUR ' + e.message; }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { r -> Log.d(TAG, "clic → $r") }
        flash()
    }

    /** Cherche en profondeur une vue cliquable sous le point donné (coords racine). */
    private fun boutonSous(vueParente: View, cx: Float, cy: Float): View? {
        if (vueParente.visibility != View.VISIBLE) return null
        if (vueParente is android.view.ViewGroup) {
            // Du dernier au premier : le dessus de la pile gagne, comme un vrai clic.
            for (i in vueParente.childCount - 1 downTo 0) {
                boutonSous(vueParente.getChildAt(i), cx, cy)?.let { return it }
            }
        }
        if (vueParente === webView || vueParente === vue) return null
        if (!vueParente.isClickable) return null
        val p = IntArray(2); vueParente.getLocationOnScreen(p)
        val r = IntArray(2); racine.getLocationOnScreen(r)
        val g = p[0] - r[0]; val h = p[1] - r[1]
        return if (cx >= g && cx <= g + vueParente.width && cy >= h && cy <= h + vueParente.height) vueParente
        else null
    }

    private fun flash() {
        vue?.let { c ->
            c.alpha = 1f
            c.animate().alpha(0.4f).setDuration(80L).withEndAction {
                c.animate().alpha(1f).setDuration(120L).start()
            }.start()
        }
    }
}
