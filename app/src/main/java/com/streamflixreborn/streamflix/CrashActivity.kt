package com.streamflixreborn.streamflix

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.ViewGroup

/**
 * 2026-07-07 : Ecran de rapport de crash — s'affiche automatiquement
 * quand l'app plante (via UncaughtExceptionHandler dans StreamFlixApp).
 *
 * Tourne dans un process séparé (:crash) pour survivre au kill du
 * process principal. Fonctionne sur Mobile ET TV (D-pad).
 *
 * Boutons :
 *   - Copier → clipboard
 *   - Redémarrer → relance l'app
 *   - Fermer → quitte
 */
class CrashActivity : Activity() {

    companion object {
        const val EXTRA_CRASH = "crash"
        private const val TAG = "CrashReporter"
    }

    private var crashReport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        crashReport = intent.getStringExtra(EXTRA_CRASH)
            ?: try {
                java.io.File(getExternalFilesDir(null), "last_crash.txt").readText()
            } catch (_: Exception) {
                try {
                    java.io.File(cacheDir, "last_crash.txt").readText()
                } catch (_: Exception) {
                    "Aucune donnee de crash disponible"
                }
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
            setBackgroundColor(0xFF121212.toInt())
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        // ── Title ──
        val isAnr = crashReport.startsWith("ANR ")
        val title = TextView(this).apply {
            text = if (isAnr) "ANR detecte" else "Crash detecte"
            setTextColor(if (isAnr) 0xFFFF9800.toInt() else 0xFFFF5252.toInt())
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(title)

        // ── Subtitle ──
        val subtitle = TextView(this).apply {
            text = if (isAnr) "Le thread principal est reste bloque trop longtemps."
                   else "L'application a rencontre une erreur fatale."
            setTextColor(0xFFB0B0B0.toInt())
            textSize = 14f
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(subtitle)

        // ── Device info ──
        val infoSection = TextView(this).apply {
            val info = buildString {
                append("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("Android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                append("App : v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n")
                append("Date : ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date())}")
            }
            text = info
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 13f
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(infoSection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        // ── Buttons row ──
        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // ══════════════════════════════════════════════════════════════════════
        // 2026-08-21 (user « le report de bug, on l'arrête ») : L'ENVOI VERS
        //   GITHUB A ÉTÉ RETIRÉ.
        //
        // Cet écran reste ce qu'il était — il montre la trace du plantage, permet
        // de la copier et de relancer l'application. C'est lui que lancent
        // StreamFlixApp (exception non rattrapée) et AnrWatchdog (figeage > 5 s) ;
        // le supprimer ferait retomber les utilisateurs sur le « l'application
        // s'est arrêtée » d'Android.
        //
        // Ne restaient que le bouton « Envoyer le rapport » et son envoi
        // automatique, qui ouvraient une issue avec un jeton compilé dans l'APK.
        // Ce jeton n'était de toute façon jamais injecté par le workflow de
        // release : le mécanisme était déjà inerte chez les utilisateurs.
        // ══════════════════════════════════════════════════════════════════════

        val copyBtn = Button(this).apply {
            id = View.generateViewId()
            text = "Copier"
            setTextColor(0xFFFFFFFF.toInt())
            background = buttonBg(0xFF424242.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { copyToClipboard() }
        }
        buttonsLayout.addView(copyBtn, btnLp())

        val restartBtn = Button(this).apply {
            id = View.generateViewId()
            text = "Redemarrer"
            setTextColor(0xFFFFFFFF.toInt())
            background = buttonBg(0xFF388E3C.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { restartApp() }
        }
        buttonsLayout.addView(restartBtn, btnLp())

        val closeBtn = Button(this).apply {
            id = View.generateViewId()
            text = "Fermer"
            setTextColor(0xFFFFFFFF.toInt())
            background = buttonBg(0xFF616161.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { finishAndRemoveTask() }
        }
        buttonsLayout.addView(closeBtn, btnLp())

        root.addView(buttonsLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        // ── Stacktrace ──
        val scrollView = ScrollView(this).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        val traceView = TextView(this).apply {
            text = crashReport
            textSize = 10f
            setTextColor(0xFFCCCCCC.toInt())
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFF1A1A1A.toInt())
        }
        scrollView.addView(traceView)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        // ── Chaînage D-pad explicite pour télécommande TV ──
        val allBtns = listOf(copyBtn, restartBtn, closeBtn)
        for (i in allBtns.indices) {
            allBtns[i].nextFocusLeftId = allBtns[if (i > 0) i - 1 else 0].id
            allBtns[i].nextFocusRightId = allBtns[if (i < allBtns.lastIndex) i + 1 else allBtns.lastIndex].id
            allBtns[i].nextFocusUpId = allBtns[i].id   // rester sur les boutons (rien au-dessus)
            allBtns[i].nextFocusDownId = scrollView.id
        }
        scrollView.nextFocusUpId = allBtns.first().id

        setContentView(root)

        // Auto-focus premier bouton pour la TV (D-pad) — post pour attendre le layout
        val firstBtn = copyBtn
        firstBtn.post { firstBtn.requestFocus() }
    }

    // ────────────────────────────────────────────────────────────

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun btnLp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = dp(8) }

    /** Background avec focus indicator pour TV (D-pad) : bordure blanche quand focusé */
    private fun buttonBg(normalColor: Int): StateListDrawable {
        val focused = GradientDrawable().apply {
            setColor(normalColor)
            setStroke(dp(3), 0xFFFFFFFF.toInt())
            cornerRadius = dp(6).toFloat()
        }
        val pressed = GradientDrawable().apply {
            setColor(normalColor)
            setStroke(dp(3), 0xFFFF9800.toInt())
            cornerRadius = dp(6).toFloat()
        }
        val normal = GradientDrawable().apply {
            setColor(normalColor)
            cornerRadius = dp(6).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun copyToClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("crash_report", crashReport))
        Toast.makeText(this, "Rapport copie !", Toast.LENGTH_SHORT).show()
    }

    private fun restartApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(launchIntent)
        finishAndRemoveTask()
    }

    // 2026-08-21 : sendToGitHub retire avec le reste du rapporteur.

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finishAndRemoveTask()
    }
}
