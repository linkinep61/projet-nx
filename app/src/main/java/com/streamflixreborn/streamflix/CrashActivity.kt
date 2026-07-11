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
 *   - Envoyer le rapport → crée une GitHub Issue (si token configuré)
 *   - Copier → clipboard
 *   - Redémarrer → relance l'app
 *   - Fermer → quitte
 */
class CrashActivity : Activity() {

    companion object {
        const val EXTRA_CRASH = "crash"
        private const val TAG = "CrashReporter"
        private const val PREFS_NAME = "crash_reporter"
        private const val KEY_SENT_HASHES = "sent_hashes"    // fingerprints déjà envoyés
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

        val token = try { BuildConfig.GITHUB_CRASH_TOKEN } catch (_: Throwable) { "" }
        val repo = try { BuildConfig.GITHUB_CRASH_REPO } catch (_: Throwable) { "" }

        // Anti-spam : chaque crash unique ne peut être envoyé qu'une seule fois
        val fingerprint = computeFingerprint(crashReport)
        val alreadySent = isAlreadySent(fingerprint)

        // "Envoyer" — visible seulement si token + repo configurés
        var sendBtn: Button? = null
        if (!token.isNullOrBlank() && !repo.isNullOrBlank()) {
            sendBtn = Button(this).apply {
                id = View.generateViewId()
                if (alreadySent) {
                    text = "Deja envoye"
                    background = buttonBg(0xFF616161.toInt())
                    isEnabled = false
                } else {
                    text = "Envoyer le rapport"
                    background = buttonBg(0xFF2196F3.toInt())
                }
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener { sendToGitHub(this, fingerprint) }
            }
            buttonsLayout.addView(sendBtn, btnLp())
        }

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

        // ── Status line (feedback envoi) ──
        val statusView = TextView(this).apply {
            tag = "statusView"
            setTextColor(0xFF81C784.toInt())
            textSize = 13f
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(statusView)

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
        val allBtns = listOfNotNull(sendBtn, copyBtn, restartBtn, closeBtn)
        for (i in allBtns.indices) {
            allBtns[i].nextFocusLeftId = allBtns[if (i > 0) i - 1 else 0].id
            allBtns[i].nextFocusRightId = allBtns[if (i < allBtns.lastIndex) i + 1 else allBtns.lastIndex].id
            allBtns[i].nextFocusUpId = allBtns[i].id   // rester sur les boutons (rien au-dessus)
            allBtns[i].nextFocusDownId = scrollView.id
        }
        scrollView.nextFocusUpId = allBtns.first().id

        setContentView(root)

        // Auto-focus premier bouton pour la TV (D-pad) — post pour attendre le layout
        val firstBtn = sendBtn ?: copyBtn
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

    /**
     * Crée une GitHub Issue avec le rapport de crash.
     * POST https://api.github.com/repos/{owner}/{repo}/issues
     */
    private fun sendToGitHub(button: Button, fingerprint: String = "") {
        val token = try { BuildConfig.GITHUB_CRASH_TOKEN } catch (_: Throwable) { "" }
        val repo = try { BuildConfig.GITHUB_CRASH_REPO } catch (_: Throwable) { "" }
        Log.d(TAG, "sendToGitHub: repo=$repo tokenLen=${token?.length ?: 0}")
        if (token.isNullOrBlank() || repo.isNullOrBlank()) {
            Log.e(TAG, "sendToGitHub: ABORT — token or repo blank")
            return
        }

        button.isEnabled = false
        button.text = "Envoi..."
        val statusView = window.decorView.findViewWithTag<TextView>("statusView")

        Thread {
            try {
                // Détecter ANR vs Crash
                val isAnr = crashReport.startsWith("ANR ")

                // Titre = première ligne d'exception / ANR
                val exceptionLine = crashReport.lines()
                    .firstOrNull { it.contains("Exception") || it.contains("Error") || it.contains("ANR") }
                    ?: if (isAnr) "ANR detecte" else "Crash inconnu"
                val shortTitle = if (exceptionLine.length > 100)
                    exceptionLine.take(100) + "..." else exceptionLine

                Log.d(TAG, "sendToGitHub: isAnr=$isAnr title=$shortTitle")

                val typeTag = if (isAnr) "ANR" else "Crash"

                // Corps de l'issue en Markdown
                val body = buildString {
                    append("## Informations appareil\n\n")
                    append("| Champ | Valeur |\n|---|---|\n")
                    append("| Type | **$typeTag** |\n")
                    append("| Fabricant | ${Build.MANUFACTURER} |\n")
                    append("| Modele | ${Build.MODEL} |\n")
                    append("| Android | ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) |\n")
                    append("| App | v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) |\n")
                    append("| ABI | ${Build.SUPPORTED_ABIS.joinToString()} |\n")
                    append("| Date | ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss z", java.util.Locale.FRANCE).format(java.util.Date())} |\n")
                    append("\n## Stack Trace\n\n```\n")
                    append(crashReport)
                    append("\n```\n")
                }

                val issueTitle = "[$typeTag] $shortTitle"

                // POST sans label (le token n'a pas forcément le droit
                // d'ajouter des labels sur un repo d'organisation)
                val jsonPayload = org.json.JSONObject().apply {
                    put("title", issueTitle)
                    put("body", body)
                }
                Log.d(TAG, "sendToGitHub: POST to $repo...")
                val result = postGitHubIssue(repo, token, jsonPayload)
                val code = result.first
                val responseBody = result.second
                Log.d(TAG, "sendToGitHub: HTTP $code (bodyLen=${responseBody?.length ?: 0})")

                runOnUiThread {
                    if (code in 200..299) {
                        val issueUrl = try {
                            org.json.JSONObject(responseBody ?: "").optString("html_url", "")
                        } catch (_: Exception) { "" }
                        Log.d(TAG, "sendToGitHub: SUCCESS issueUrl=$issueUrl")
                        if (fingerprint.isNotBlank()) markAsSent(fingerprint)
                        button.text = "Envoye !"
                        button.isEnabled = false
                        button.background = buttonBg(0xFF388E3C.toInt())
                        statusView?.apply {
                            text = if (issueUrl.isNotBlank()) "Issue creee : $issueUrl"
                                   else "Rapport envoye avec succes"
                            setTextColor(0xFF81C784.toInt())
                            visibility = View.VISIBLE
                        }
                    } else {
                        Log.e(TAG, "sendToGitHub: FAIL HTTP $code body=${responseBody?.take(500)}")
                        button.text = "Erreur ($code)"
                        button.background = buttonBg(0xFFD32F2F.toInt())
                        button.isEnabled = true
                        statusView?.apply {
                            text = "Erreur HTTP $code"
                            setTextColor(0xFFFF5252.toInt())
                            visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendToGitHub: EXCEPTION ${e.javaClass.simpleName}: ${e.message}", e)
                runOnUiThread {
                    button.text = "Pas de reseau"
                    button.background = buttonBg(0xFFD32F2F.toInt())
                    button.isEnabled = true
                    statusView?.apply {
                        text = "Erreur : ${e.message}"
                        setTextColor(0xFFFF5252.toInt())
                        visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }

    /**
     * POST une issue sur GitHub. Retourne (httpCode, responseBody).
     */
    private fun postGitHubIssue(
        repo: String,
        token: String,
        json: org.json.JSONObject
    ): Pair<Int, String?> {
        val apiUrl = "https://api.github.com/repos/$repo/issues"
        Log.d(TAG, "postGitHubIssue: POST $apiUrl (jsonLen=${json.toString().length})")
        val url = java.net.URL(apiUrl)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "token $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", "ONYX-CrashReporter")
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        Log.d(TAG, "postGitHubIssue: responseCode=$code")
        val body = try {
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { "" }
        }
        return code to body
    }

    // ── Anti-spam : dédup par empreinte du crash ──

    /**
     * Calcule une empreinte unique du crash : type d'exception + 5 premières
     * lignes de stack trace. Deux crashs au même endroit = même empreinte,
     * même si le timestamp ou les infos appareil diffèrent.
     */
    private fun computeFingerprint(report: String): String {
        val lines = report.lines()
        // Garder les lignes "at " (stack frames) + la ligne d'exception
        val sigLines = lines.filter { line ->
            val t = line.trim()
            t.startsWith("at ") || t.contains("Exception") || t.contains("Error") || t.contains("ANR")
        }.take(6)
        val sig = sigLines.joinToString("|")
        // Hash simple (int → hex)
        return sig.hashCode().toUInt().toString(16)
    }

    /** Vérifie si cette empreinte a déjà été envoyée. */
    private fun isAlreadySent(fingerprint: String): Boolean {
        if (fingerprint.isBlank()) return false
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val sent = prefs.getStringSet(KEY_SENT_HASHES, emptySet()) ?: emptySet()
        return fingerprint in sent
    }

    /** Enregistre l'empreinte comme envoyée. */
    private fun markAsSent(fingerprint: String) {
        if (fingerprint.isBlank()) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val sent = (prefs.getStringSet(KEY_SENT_HASHES, emptySet()) ?: emptySet()).toMutableSet()
        sent.add(fingerprint)
        // Garder max 200 empreintes (éviter croissance infinie)
        val trimmed = if (sent.size > 200) sent.toList().takeLast(200).toMutableSet() else sent
        prefs.edit().putStringSet(KEY_SENT_HASHES, trimmed).apply()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finishAndRemoveTask()
    }
}
