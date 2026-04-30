package com.streamflixreborn.streamflix

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stackTrace = intent.getStringExtra("crash")
            ?: try { java.io.File(getExternalFilesDir(null), "last_crash.txt").readText() } catch (_: Exception) {
                try { java.io.File(cacheDir, "last_crash.txt").readText() } catch (_: Exception) { "No crash data" }
            }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(0xFF1a1a2e.toInt())
        }

        val title = TextView(this).apply {
            text = "Crash Report"
            textSize = 20f
            setTextColor(0xFFe94560.toInt())
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        val copyBtn = Button(this).apply {
            text = "Copier"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", stackTrace))
                Toast.makeText(this@CrashActivity, "Copié !", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(copyBtn)

        val restartBtn = Button(this).apply {
            text = "Redémarrer l'app"
            setOnClickListener {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finish()
            }
        }
        layout.addView(restartBtn)

        val scrollView = ScrollView(this)
        val traceView = TextView(this).apply {
            text = stackTrace
            textSize = 11f
            setTextColor(0xFFe0e0e0.toInt())
            setTextIsSelectable(true)
            setPadding(0, 16, 0, 0)
        }
        scrollView.addView(traceView)
        layout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(layout)
    }
}
